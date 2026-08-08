package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.domain.OutboxMessage;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.config.MessagingIdentityConfig.MessagingTokeniser;
import com.carddemo.authorization.dto.AuthorizationRequestPayload;
import com.carddemo.authorization.mapper.AuthorizationMessageMapper;
import com.carddemo.authorization.repository.OutboxRepository;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.codec.CsvAuthCodec.AuthMessageFormatException;
import com.carddemo.common.codec.CsvAuthCodec.AuthReply;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import com.carddemo.common.messaging.MessageExpiry;
import com.carddemo.common.messaging.MessagingCorrelationId;
import com.carddemo.common.money.Money;
import com.carddemo.common.observability.LogSafeText;
import com.carddemo.common.security.OpaqueIdentifier;
import io.awspring.cloud.sqs.annotation.SqsListener;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes authorization requests from the request queue, decides them, and writes the reply into the
 * transactional outbox.
 *
 * <p>This is the migrated form of {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}. The
 * baseline is a long-running task that reads its request queue at line 389, resolves the card, account
 * and customer at lines 448 to 452, decides at lines 657 to 734, PUTS ITS REPLY at line 461 -- reaching
 * the no-syncpoint put at lines 753 to 758 -- writes the decision to its databases afterwards at lines
 * 790 to 791 under the guard at line 463, and commits with a syncpoint at line 335 later still, the whole
 * paragraph having been performed at line 330. Reading only the line numbers suggests commit-then-publish;
 * the paragraph order is what settles it, and it is publish-then-write-then-commit. Four properties of
 * that program are preserved here, and one of its orderings is deliberately inverted rather than
 * reproduced:</p>
 *
 * <ul>
 *   <li>The unit of work is ONE MESSAGE. The baseline commits per message, so a failure affects the
 *       message being handled and nothing before it; {@link Transactional} on {@link #onRequest} with
 *       {@code REQUIRES_NEW} reproduces exactly that scope.</li>
 *   <li>The wait is bounded, not indefinite. The baseline reads with a five-second wait; the queue's
 *       long-poll timeout carries the same discipline, configured rather than coded.</li>
 *   <li>The batch is bounded. The baseline stops after five hundred messages; the listener container's
 *       messages-per-poll and concurrency limits carry that bound, again configured.</li>
 *   <li>The reply destination comes from the REQUEST, exactly as the message descriptor's reply-to
 *       queue field did, so one consumer serves however many requesters have their own reply queues --
 *       subject to the allowlist argued at {@link #enqueueReply}.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the ordering not reproduced is publish-before-commit. The baseline puts its
 * reply first and writes and commits its decision afterwards, as three separate units of work, so a
 * failure after the put leaves a requester holding an answer that no committed row accounts for -- and
 * the request cannot be presented again to re-derive it, because the destructive no-syncpoint get at line
 * 389 destroyed it on read. This method writes the reply into {@link AuthReplyOutbox} INSIDE the deciding
 * transaction, and {@link OutboxPublisher} sends it afterwards. The row and the decision therefore commit
 * together or neither does, no answer precedes the row that justifies it, and publication can be retried
 * without re-deciding. Assumptions: this is a documented difference between source and target, not an
 * assertion that the baseline is wrong -- the baseline is the parity oracle and is unchanged. The
 * divergence is registered as {@code D-5} in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Refactoring Rationale: the card was previously resolved to an account by reading the account
 * recorded on that card's OWN PREVIOUS authorizations, because this context owns no cross-reference
 * table. That substitute could not resolve the first authorization a card ever presents -- the case that
 * matters most -- and resolved a reissued card to its former account, and each failure declined a request
 * the baseline approves. The three reads now go through {@link AccountContextClient}, which is the seam
 * to the context that owns those records, and the substitute query has been removed so nothing can prefer
 * it again.</p>
 *
 * <p>Assumptions: the queue is a FIFO queue grouped by card number, so requests for one card are
 * delivered in order and requests for different cards are delivered in parallel. That is what lets this
 * method take a row lock on one summary without serialising the whole consumer.</p>
 */
@Component
public class AuthorizationRequestListener {

    /**
     * The message attribute naming the queue a reply must be sent to.
     *
     * <p>Assumptions: this is the migrated form of the message descriptor's reply-to queue field, which
     * the baseline copies out of the request at its own read. Naming it on the message rather than in
     * configuration is what keeps one consumer able to answer several requesters.</p>
     */
    public static final String HEADER_REPLY_TO = "replyToQueueUrl";

    /**
     * The message attribute carrying the instant after which the reply is no longer worth sending.
     *
     * <p>Assumptions: the baseline sets a five-second expiry on its reply message, and the target queue
     * service has no per-message time to live, so the expiry travels as data instead. A request whose
     * expiry has already passed when it is received is DROPPED and logged rather than decided, because
     * deciding it would reserve funds against an authorization whose requester has already given up
     * waiting. The resolution is recorded in {@code docs/adr/ADR-004-messaging.md}.</p>
     */
    // WHY : Refactoring Rationale: this now ALIASES the shared constant rather than repeating its
    //   literal. The expiry attribute is honoured by three consumers and its name is part of the
    //   contract every producer writes against, so a second literal spelling here could drift from
    //   the one the other two consumers read, and nothing would report the divergence.
    public static final String HEADER_EXPIRES_AT = MessageExpiry.HEADER_EXPIRES_AT;

    /**
     * The message attribute carrying the requester's correlation identifier, echoed onto the reply.
     */
    public static final String HEADER_CORRELATION_ID = "correlationId";

    /**
     * The logging context key the shared correlation filter uses, reused here so a message-driven log
     * line and a request-driven one correlate the same way.
     */
    public static final String MDC_CORRELATION_ID = "correlationId";

    /**
     * How long a reply stays worth sending when the request named no expiry of its own.
     *
     * <p>Assumptions: five seconds is the baseline's own expiry, set on its reply put. Carrying the same
     * default means a requester that sets no expiry attribute gets the behaviour the baseline gave it.</p>
     */
    public static final int DEFAULT_REPLY_EXPIRY_SECONDS = 5;

    /**
     * How many requests one processing window handles before intake is closed and reopened.
     *
     * <p>Assumptions: read from {@code 05 WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 40. The DECLARED limit is 500 and
     * that is the number enforced here; the reference program's own control flow handles 501, its counter
     * being incremented at line 332 and then tested with {@code >} rather than {@code >=} at line 339, so
     * counts one through 500 all read another request and only count 501 ends the run. The off-by-one is
     * an artifact of the order of increment and comparison rather than a rule anything states, and it is
     * not reproduced. The correction is registered as divergence D-AUTH-REQUEST-WINDOW in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Refactoring Rationale: it is a default rather than a fixed value, overridable by
     * {@code carddemo.messaging.request-process-limit}, so later performance work can change the window
     * size on measured evidence instead of by editing this class. The default is the baseline's declared
     * number so that an unconfigured deployment behaves as the contract published.</p>
     */
    public static final int DEFAULT_REQUEST_PROCESS_LIMIT = 500;

    /**
     * The multiplier that places a two-digit year ahead of a three-digit day of year.
     *
     * <p>Assumptions: this reproduces the five-digit ordinal date the baseline takes from the platform's
     * own time service, {@code MOVE WS-CUR-DATE-X6(1:5) TO WS-YYDDD} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 868, where the two leading
     * characters are the year and the three trailing ones are the day of year.</p>
     */
    private static final int JULIAN_YEAR_MULTIPLIER = 1000;

    /**
     * The modulus that reduces a four-digit calendar year to the two digits the ordinal date carries.
     */
    private static final int JULIAN_YEAR_MODULUS = 100;

    /**
     * The multiplier that places an hour ahead of minutes, seconds and milliseconds.
     *
     * <p>Assumptions: the three multipliers here and below reproduce
     * {@code COMPUTE WS-TIME-WITH-MS = (WS-CUR-TIME-N6 * 1000) + WS-CUR-TIME-MS} at lines 871 and 872,
     * where the six-digit value is the hour, minute and second read as one decimal number. Multiplying
     * that composite by a thousand is arithmetically the same as scaling each component separately, which
     * is what these three constants do without needing an intermediate composite.</p>
     */
    private static final int TIME_HOUR_MULTIPLIER = 10_000_000;

    /**
     * The multiplier that places a minute ahead of seconds and milliseconds.
     */
    private static final int TIME_MINUTE_MULTIPLIER = 100_000;

    /**
     * The multiplier that places a second ahead of milliseconds.
     */
    private static final int TIME_SECOND_MULTIPLIER = 1000;

    /**
     * The divisor that reduces nanoseconds to the milliseconds the platform's time service reports.
     */
    private static final int NANOS_PER_MILLISECOND = 1_000_000;

    /**
     * The logger for this consumer.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AuthorizationRequestListener.class);

    /**
     * The summary repository, read under a row lock and updated in place.
     */
    private final PendingAuthSummaryRepository summaries;

    /**
     * The detail repository, used for the idempotency seek and the insert.
     */
    private final PendingAuthDetailRepository details;

    /**
     * The outbox repository the reply is written to inside the deciding transaction.
     */
    private final OutboxRepository outbox;

    /**
     * The decision logic, transcribed from the baseline's decision paragraph.
     */
    private final AuthorizationDecisionService decisions;

    /**
     * The validated crossing from the decoded wire record to the structured payload.
     *
     * <p>Refactoring Rationale: this collaborator is what makes the DECLARED contract the LIVE one. The
     * decoder establishes that a message splits into eighteen fields of admissible widths; it does not
     * establish that those fields satisfy the payload's own domains, because those constraints are
     * declared on the payload and there was nothing to apply them to. This consumer previously worked
     * straight from the decoded record, so every constraint the payload declared -- requiredness, the
     * digits-only expressions on the two numeric-picture fields, the amount domain -- was asserted only
     * by tests and by the never-invoked structured path, and the live queue path enforced none of
     * them.</p>
     */
    private final AuthorizationMessageMapper payloads;

    /**
     * The seam to the context that owns the cross-reference, account and customer records.
     */
    private final AccountContextClient accounts;

    /**
     * The keyed tokeniser every identity this consumer writes into queue metadata is derived through.
     *
     * <p>Refactoring Rationale: this collaborator did not exist, and its absence is the whole of finding
     * C-04. Without it the only per-card stable value available for a first-in-first-out group identity
     * was the card number, so the card number was used -- and a group identity is metadata, not payload:
     * the publisher copies it onto the send, where it leaves the encrypted body, appears in queue
     * telemetry and reaches every log and metric that observes the queue. Holding the tokeniser as a
     * collaborator rather than deriving tokens inline also keeps the key in one place, so a rotation is a
     * configuration change rather than a code change.</p>
     *
     * <p>Assumptions: the SAME key is shared with every other producer on this queue. That sharing is
     * required rather than incidental: the group identity has to be equal for equal cards across
     * producers, because that equality IS the per-card ordering guarantee. A per-instance key would put
     * one card's messages into as many groups as there are instances.</p>
     *
     * <p>Assumptions: ONE tokeniser serves both queue identities the outbox row carries -- the ordering
     * group and the deduplication identity -- rather than one collaborator per identity. A second
     * collaborator would be a second key to rotate for no gain: the two derivations are already kept
     * unjoinable by their differing PURPOSE strings, which is a property of the derivation rather than
     * of the key it is performed with.</p>
     */
    private final OpaqueIdentifier messagingTokeniser;

    /**
     * The reply destinations this consumer is permitted to publish to.
     */
    private final List<String> replyQueueAllowlist;

    /**
     * The clock, injected so staleness, timestamps and authorization keys are testable without waiting.
     */
    private final Clock clock;

    /**
     * How many requests this window may still handle before intake is closed.
     */
    private final int requestProcessLimit;

    /**
     * The action that closes a full window and opens the next.
     */
    private final RequestWindowBoundary windowBoundary;

    /**
     * How many requests the current window has handled.
     *
     * <p>Assumptions: an atomic counter rather than a plain field, because the listener container delivers
     * messages on several threads concurrently -- its concurrency is configured on the annotation below --
     * so a non-atomic increment would lose counts and the window would run past its quota by an amount
     * nothing bounds. The counter holds the slots USED in the current window and is advanced and wrapped
     * in one atomic update, so it is always between zero and one less than the quota.</p>
     *
     * <p>Trade-offs: the counter is per INSTANCE and therefore per task, not per queue. Two tasks each
     * handle up to the quota before each closes its own window, so the platform-wide figure is the quota
     * times the task count. That matches the reference system, where the limit bounded one running program
     * and the queue could trigger more than one, and a shared counter would need a coordination round trip
     * on the hot path of every authorization to achieve nothing the bound is for.</p>
     */
    private final AtomicInteger handledInWindow = new AtomicInteger();

    /**
     * Creates the consumer.
     *
     * <p>Assumptions: every collaborator arrives through the constructor rather than through field
     * injection, so an instance is fully formed once constructed and a unit test can supply a fixed clock
     * and a stub account context without a container.</p>
     *
     * <p>Assumptions: the allowlist is a COMMA-SEPARATED property rather than a structured list, because
     * a structured list cannot be supplied by the single environment variable a container task definition
     * sets, and this value is a deployment fact. It carries no default: an unset allowlist stops startup
     * rather than silently answering nobody, which is the same policy the datasource and issuer
     * placeholders in this service's configuration follow.</p>
     *
     * @param summaries the summary repository; must not be {@code null}
     * @param details the detail repository; must not be {@code null}
     * @param outbox the outbox repository; must not be {@code null}
     * @param decisions the decision logic; must not be {@code null}
     * @param payloads the validated crossing from the wire record to the structured payload; must not
     *     be {@code null}
     * @param accounts the account-context seam; must not be {@code null}
     * @param messagingTokeniser the keyed tokeniser every queue identity is derived through; must not be
     *     {@code null}
     * @param replyQueueAllowlist the reply destinations this consumer may publish to; must not be empty
     * @param clock the clock staleness, timestamps and authorization keys are read from; must not be
     *     {@code null}
     * @param requestProcessLimit how many requests one window handles before intake is closed; must be
     *     positive
     * @param windowBoundary the action that closes a full window and opens the next; must not be
     *     {@code null}
     * @throws IllegalArgumentException if {@code requestProcessLimit} is not positive, a non-positive
     *     window admitting no request at all
     * @throws NullPointerException if {@code messagingTokeniser} is {@code null}, because an absent
     *     tokeniser has no safe fallback: the only value available to group by would be the card number
     */
    public AuthorizationRequestListener(PendingAuthSummaryRepository summaries,
            PendingAuthDetailRepository details, OutboxRepository outbox,
            AuthorizationDecisionService decisions, AuthorizationMessageMapper payloads,
            AccountContextClient accounts,
            @MessagingTokeniser OpaqueIdentifier messagingTokeniser,
            @Value("${carddemo.messaging.reply-queue-allowlist}") List<String> replyQueueAllowlist,
            Clock clock,
            @Value("${carddemo.messaging.request-process-limit:" + DEFAULT_REQUEST_PROCESS_LIMIT + "}")
            int requestProcessLimit,
            RequestWindowBoundary windowBoundary) {
        if (requestProcessLimit <= 0) {
            throw new IllegalArgumentException(
                    "carddemo.messaging.request-process-limit must be positive but was "
                            + requestProcessLimit
                            + "; a non-positive window would close before handling any request");
        }
        this.summaries = summaries;
        this.details = details;
        this.outbox = outbox;
        this.decisions = decisions;
        this.payloads = payloads;
        this.accounts = accounts;
        // WHY : Assumptions: an absent tokeniser fails here rather than being tolerated with a fallback.
        //   The only other value this consumer holds that is per-card and stable is the card number
        //   itself, so any fallback would be the exact exposure the tokeniser exists to remove, and it
        //   would appear silently at run time on the reply path rather than at startup.
        this.messagingTokeniser =
                Objects.requireNonNull(messagingTokeniser, "messagingTokeniser must not be null");
        this.replyQueueAllowlist = List.copyOf(replyQueueAllowlist);
        this.clock = clock;
        this.requestProcessLimit = requestProcessLimit;
        this.windowBoundary = Objects.requireNonNull(windowBoundary, "windowBoundary must not be null");
    }

    /**
     * Handles one authorization request.
     *
     * <p>Assumptions: the transaction is {@code REQUIRES_NEW} so that this message's unit of work is its
     * own even when the listener container happens to be invoked inside one, which reproduces the
     * baseline's per-message syncpoint exactly. A transaction shared across a poll batch would make one
     * malformed message roll back its neighbours' decisions.</p>
     *
     * <p>Assumptions: a message that cannot be decoded is allowed to propagate rather than being
     * swallowed, so the queue redelivers it and the dead-letter queue receives it after the configured
     * receive count. Swallowing it would delete a request no one had answered. An amount outside the
     * record's own domain propagates the same way and for the same reason, which
     * {@link #requireAmountWithinRecordDomain} argues.</p>
     *
     * @param message the received message, whose payload is the delimited request and whose headers
     *     carry the reply destination, expiry and correlation identifier; must not be {@code null}
     */
    @SqsListener(id = ContainerCyclingWindowBoundary.REQUEST_CONTAINER_ID,
            queueNames = "${carddemo.messaging.pauth-request-queue}",
            maxConcurrentMessages = "${carddemo.messaging.max-concurrent-messages:10}",
            maxMessagesPerPoll = "${carddemo.messaging.max-messages-per-poll:10}",
            pollTimeoutSeconds = "${carddemo.messaging.poll-timeout-seconds:5}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRequest(Message<String> message) {
        String correlationId = conformingCorrelationId(message);

        // WHY : Assumptions: the LOGGING context carries the sanitised rendering while the reply carries
        // the value verbatim. The two are deliberately different renderings of one identity: a log record
        // must not be able to carry a delimiter or a line terminator, and a reply must carry the
        // requester's own bytes. Putting the raw value here would make every log line this message
        // produces forgeable by its sender.
        MDC.put(MDC_CORRELATION_ID, MessagingCorrelationId.logSafe(correlationId));
        try {
            LocalDateTime now = LocalDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC);
            if (hasUnparseableExpiry(message)) {
                // WHY : Alternatives Considered: treating an unparseable expiry as ABSENT, which is what
                // the two INQUIRY consumers of the same helper do, and which would let a request whose
                // attribute merely differs in formatting still be answered. Rejected on THIS flow because
                // it fails open on the one control that stops a stale request being DECIDED: a producer
                // that wants an expired request honoured need only corrupt the attribute. The two inquiry
                // flows answer read-only questions, so honouring a stale one costs a wasted reply; this one
                // commits a decision and moves the account's counters, and the reference broker never
                // delivered an expired request to the program at all.
                // WHY : Assumptions: only the LENGTH is logged and the value itself never is. It came off
                // the wire, it did not parse as an instant, and therefore nothing bounds what it contains
                // -- which is exactly the case the sanitiser cannot be relied on to make safe to read.
                LOG.warn("event=auth.request.dropped reason=expiry-unparseable length={}",
                        expiryLength(message));
                return;
            }
            if (isStale(message, now)) {
                // WHY : Trade-offs: a stale request is dropped rather than declined. Declining would
                // publish a reply to a requester that has already stopped waiting and would consume a
                // deduplication identifier, so a legitimate retry of the same transaction would then be
                // suppressed as a duplicate of an answer nobody read.
                // WHY : Assumptions: the header is passed through the shared sanitiser even though
                // reaching this line already implies it PARSED as an instant -- the guard above has
                // already refused every value that did not -- so the value here cannot carry free text.
                // The sanitiser is applied anyway because the guarantee is indirect: it holds only while
                // that guard keeps standing in front of this one, and a later edit reordering the two
                // would silently turn this into a raw wire value in a log record. One call is a cheaper
                // guarantee than a comment asking a future reader to re-derive the argument.
                LOG.warn("event=auth.request.dropped reason=expired expiresAt={}",
                        LogSafeText.sanitize(header(message, HEADER_EXPIRES_AT)));
                return;
            }
            AuthRequest request = CsvAuthCodec.decodeRequest(message.getPayload());
            requireDeclaredContract(request);
            Optional<PendingAuthDetail> alreadyDecided = existingDecision(request);
            if (alreadyDecided.isPresent()) {
                // WHY : Assumptions: the queue suppresses duplicates only inside its deduplication
                // window, so a redelivery after that window arrives as a new message. Re-publishing the
                // recorded answer keeps the requester served without incrementing the account's counters
                // a second time, which is what re-deciding would do.
                LOG.info("event=auth.request.replayed transactionId={}", request.transactionId());
                enqueueReply(message, replyFor(alreadyDecided.get()), correlationId, now);
                return;
            }
            handleNewRequest(message, request, correlationId, now);
        } finally {
            MDC.remove(MDC_CORRELATION_ID);
            countTowardsWindow();
        }
    }

    /**
     * Counts this message towards the current window and closes the window when it is full.
     *
     * <p>Assumptions: the count is taken in the {@code finally} block, so every message this consumer took
     * off the queue counts -- including one dropped as stale and one whose reply was a replay of a decision
     * already recorded. That matches the reference program, whose counter is incremented at
     * {@code cbl/COPAUA0C.cbl} line 332 after the get returns and before any outcome is known, so a
     * request it could not act on still consumed one of its 500. Counting only decisions would let a
     * flood of expired requests run a window indefinitely.</p>
     *
     * <p>Assumptions: a message whose handling THREW counts as well, the {@code finally} running on the
     * exceptional path too, and that is deliberate rather than incidental. A request that failed was still
     * received and still occupied the window; not counting it would let one poison message that redelivers
     * indefinitely keep a window open indefinitely.</p>
     *
     * <p>Refactoring Rationale: the count and the reset are ONE atomic step, and they were two -- an
     * increment, a comparison, then a separate subtraction of the observed value. Two steps could not hold
     * under the container's configured concurrency: with several handlers finishing at once, two threads
     * could each observe a count at or past the quota, each subtract its own observation, and leave the
     * counter NEGATIVE -- after which the next window admitted the quota plus the deficit before closing,
     * and the boundary fired twice for one window. The update below is applied by
     * {@link java.util.concurrent.atomic.AtomicInteger#getAndUpdate}, which retries until it wins, so the
     * counter walks 0 to quota-1 and back to 0 and can be neither negative nor greater than the quota.
     * Exactly one thread observes the last slot of a window, so the boundary fires exactly once.</p>
     *
     * <p>Assumptions: the quota bounds the messages ADMITTED to a window, not the messages in flight at
     * one instant. When the boundary fires, up to the container's configured concurrency of messages may
     * still be executing -- each already counted -- and the boundary stops further INTAKE rather than
     * interrupting them; a message that arrives while the container is cycling belongs to the next window
     * and is counted there. That is the same discipline the reference program has: its counter bounds the
     * gets it issues, and the message it is holding when the count is reached is still processed to
     * completion before the loop exits.</p>
     */
    private void countTowardsWindow() {
        int slotsUsedBefore = this.handledInWindow.getAndUpdate(
                used -> used + 1 >= this.requestProcessLimit ? 0 : used + 1);
        if (slotsUsedBefore + 1 >= this.requestProcessLimit) {
            // WHY : Assumptions: the reported figure is the quota itself rather than a recount, because
            //       the thread that took the last slot is by construction the quota-th admission of this
            //       window. Reporting a re-read of the counter would report the NEXT window's count, the
            //       reset having already happened inside the atomic update above.
            this.windowBoundary.onWindowComplete(this.requestProcessLimit);
        }
    }

    /**
     * Decides a request not seen before, records it, and enqueues its reply.
     *
     * <p>Assumptions: the reads happen in the baseline's own order and under the baseline's own
     * condition. {@code 5000-PROCESS-AUTH} at lines 448 to 458 reads the cross-reference FIRST and
     * performs the account, customer and summary reads only when the cross-reference resolved, so a card
     * that does not resolve costs one call rather than four and the three later outcomes stay absent
     * rather than being invented. Its line 463 then guards the database writes with the same condition,
     * which is why the persistence below is guarded by the presence of the cross-reference and not by the
     * decision.</p>
     *
     * @param message the received message, needed for its reply destination and expiry; must not be
     *     {@code null}
     * @param request the decoded request; must not be {@code null}
     * @param correlationId the requester's correlation identifier; may be {@code null}
     * @param now the current instant in coordinated universal time; must not be {@code null}
     */
    private void handleNewRequest(Message<String> message, AuthRequest request, String correlationId,
            LocalDateTime now) {
        Optional<AccountContextClient.CardXref> xref =
                this.accounts.findCardXref(request.cardNum());
        Optional<AccountContextClient.Account> account = Optional.empty();
        boolean customerFound = false;
        Optional<PendingAuthSummary> summary = Optional.empty();
        if (xref.isPresent()) {
            long accountId = xref.get().accountId();
            account = this.accounts.findAccount(accountId);
            customerFound = this.accounts.customerExists(xref.get().customerId());
            summary = this.summaries.findWithLockByAccountId(accountId);
        }
        AuthorizationDecisionService.DecisionContext context =
                new AuthorizationDecisionService.DecisionContext(xref.isPresent(), account,
                        customerFound, summary);
        AuthorizationDecisionService.Decision decision = this.decisions.decide(request, context);

        // WHY : Refactoring Rationale: the reply wire record is built HERE, before the write, where it
        //       used to be built after it. The detail row and the reply carry the same five decision
        //       values -- the identification code, the response code, the response reason, the approved
        //       amount and the card and transaction identity -- and building the reply first lets the row
        //       be PROJECTED from it rather than assembled a second time from the decision. That is what
        //       keeps the persisted state and the answer sent to the requester two renderings of one
        //       decision instead of two independent ones that could drift.
        // WHY : Alternatives Considered: leaving the reply where it was and passing the decision into the
        //       write. Rejected because the projection the mapper publishes takes the reply, and it is
        //       that projection which refuses a reply naming a different card or transaction than the
        //       request; assembling the row from the decision bypasses the refusal entirely, so the one
        //       check that cannot be satisfied by construction would never run on the path that persists.
        AuthReply reply = new AuthReply(request.cardNum(), request.transactionId(),
                this.decisions.identificationCodeFor(request), decision.responseCode(),
                decision.responseReason(), decision.approvedAmount());

        if (xref.isPresent()) {
            persist(xref.get(), account, summary, request, decision, reply, now);
        } else {
            // WHY : Assumptions: with no cross-reference row there is no account to hang a summary or a
            // detail row from, so the decline is answered without being recorded. That is the baseline's
            // own behaviour: its line 463 performs the database writes only when the card resolved, so an
            // unknown card leaves no trace in either segment there either.
            LOG.warn("event=auth.request.declined reason=card-not-cross-referenced respReason={}",
                    decision.responseReason());
        }
        enqueueReply(message, reply, correlationId, now);
        LOG.info("event=auth.request.decided approved={} respCode={} respReason={}",
                decision.approved(), decision.responseCode(), decision.responseReason());
    }

    /**
     * Writes the decision to the summary and the detail rows, creating the summary when the account has
     * none yet.
     *
     * <p>Assumptions: the summary is written BEFORE the detail row, matching {@code 8000-WRITE-AUTH-TO-DB}
     * at lines 790 and 791. Within one transaction the order is not observable, but keeping it means the
     * two paragraphs and the two statements here can be read side by side.</p>
     *
     * <p>Assumptions: a missing summary is CREATED rather than skipped, because the baseline inserts the
     * root segment on the first authorization for an account, at lines 801 to 806 and 830 to 834. Skipping
     * it -- which an earlier revision did, having no account identifier to create it with -- lost the
     * account's approved and declined counters entirely until an authorization happened to find a row the
     * extract load had provided.</p>
     *
     * <p>Refactoring Rationale: the stored limits are refreshed from the account master only when the
     * account was actually read, whereas the baseline moves the account master's limit and cash limit
     * into the summary UNCONDITIONALLY at its lines 810 and 811. The difference matters on exactly one
     * path -- a card that resolves to an account whose master record is missing -- and on that path the
     * baseline's move reads working storage that its own line 451 never populated, so it overwrites a
     * stored credit limit with whatever the previous message left there, or with zero on the first
     * message of the task. Reproducing that would destroy a real limit and then decline every subsequent
     * authorization on the account for want of funds. The divergence is registered as
     * {@code D-SUMMARY-LIMIT-REFRESH} in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * @param xref the resolved cross-reference row; must not be {@code null}
     * @param account the account master record, empty when it was not found; must not be {@code null}
     * @param summary the locked summary, empty when the account has none yet; must not be {@code null}
     * @param request the decoded request; must not be {@code null}
     * @param decision the decision reached; must not be {@code null}
     * @param reply the reply wire record this decision answers with, which the detail row is
     *     projected from so the persisted state and the answer sent cannot drift apart
     * @param now the current instant in coordinated universal time; must not be {@code null}
     */
    private void persist(AccountContextClient.CardXref xref,
            Optional<AccountContextClient.Account> account, Optional<PendingAuthSummary> summary,
            AuthRequest request, AuthorizationDecisionService.Decision decision, AuthReply reply,
            LocalDateTime now) {
        PendingAuthSummary held = summary.orElseGet(
                () -> new PendingAuthSummary(xref.accountId(), xref.customerId()));
        account.ifPresent(read -> held.refreshLimits(read.creditLimit(), read.cashCreditLimit()));
        if (decision.approved()) {
            held.recordApproved(decision.approvedAmount().amount());
        } else {
            // WHY : Refactoring Rationale: the amount added here is THIS request's, whereas the baseline
            // adds PA-TRANSACTION-AMT at its line 821 -- a detail-segment field its line 885 does not
            // populate until the following paragraph, so the total it accumulates is the previous
            // message's amount, or zero for the first message of a task. A running total of declined
            // amounts that is off by one message describes nothing, so the current request's amount is
            // used and the divergence is registered as D-DECLINED-AMT-CURRENT in
            // docs/architecture/cobol-to-service-traceability.md.
            held.recordDeclined(request.transactionAmount().amount());
        }
        this.summaries.save(held);
        this.details.save(record(xref.accountId(), request, decision, reply, now));
    }

    /**
     * Builds the detail row that records a decision.
     *
     * <p>Refactoring Rationale: the row's key is derived from the SERVER's clock and not from the
     * request. The baseline asks the platform for the current ordinal date and time in
     * {@code 8500-INSERT-AUTH} at lines 858 to 875 and keys the segment with those values; the request's
     * own date and time are moved separately into the two originating fields at its lines 877 and 878,
     * which this row also carries. Keying by the request's values -- which an earlier revision did --
     * let a requester choose its own primary key, so two requests naming one instant collided and a
     * requester could place a row wherever it liked in the account's history, including ahead of rows
     * this service had already answered.</p>
     *
     * <p>Assumptions: the key is a five-digit ordinal date and a nine-digit time to the millisecond, in
     * that order and NOT the nines complement the segment stores. The complement exists only to make a
     * descending index scan out of an ascending one, which this schema expresses directly; the argument
     * is recorded once, on the two columns, in {@code V1__authorization.sql}.</p>
     *
     * <p>Trade-offs: two authorizations for one account within the same millisecond collide on this key.
     * The hierarchical original has the same property, and the outcome here is a constraint violation
     * that rolls the message back for redelivery rather than a lost row -- the idempotency seek finds
     * nothing on the retry, because the failed insert never committed, and the retry lands on a later
     * millisecond.</p>
     *
     * <p>Refactoring Rationale: the MATCH STATUS is derived from the decision here and passed in,
     * where an earlier revision let the entity fix it to pending. The reference insert selects between
     * two values on exactly this condition -- {@code cbl/COPAUA0C.cbl} L902 tests
     * {@code IF AUTH-RESP-APPROVED}, L903 sets the pending value on that branch and L905 the declined
     * value on the other -- so a fixed value recorded every decline as an authorization still awaiting
     * a match. Deriving it from {@link AuthorizationDecisionService.Decision#approved()} is what keeps
     * the persisted state, the response code and the approved amount three renderings of ONE decision
     * rather than three independent ones.</p>
     *
     * @param accountId the resolved account; must not be {@code null}
     * @param request the decoded request; must not be {@code null}
     * @param decision the decision reached; must not be {@code null}
     * @param reply the reply wire record this decision answers with, which the detail row is
     *     projected from so the persisted state and the answer sent cannot drift apart
     * @param now the current instant in coordinated universal time; must not be {@code null}
     * @return the unsaved detail row, never {@code null}
     */
    private PendingAuthDetail record(Long accountId, AuthRequest request,
            AuthorizationDecisionService.Decision decision, AuthReply reply, LocalDateTime now) {
        PendingAuthDetailKey key = new PendingAuthDetailKey(accountId, ordinalDateOf(now),
                timeOfDayOf(now));

        // WHY : Refactoring Rationale: the row is PROJECTED by the mapper, where this method used to
        //       assemble it field by field. Both forms produced the same twenty-four components, so the
        //       duplication was invisible -- and that was the problem: the record-to-entity crossing
        //       existed twice, and only the mapper's copy carried the refusal of a reply that answers a
        //       different request. Two copies of a crossing drift on the first change made to one of
        //       them, and the change most likely to be made is the one this class's own key derivation
        //       already needed. The key and the match status stay this class's decisions and are passed
        //       in; every field crossing from the wire is the mapper's.
        return this.payloads.toPendingAuthDetail(key, request, reply, matchStatusFor(decision));
    }

    /**
     * Renders a decision as the match status the segment stores.
     *
     * <p>Assumptions: the mapping is total and has exactly two outcomes, because the reference test at
     * {@code cbl/COPAUA0C.cbl} L902 has exactly two branches and no default. An approval becomes
     * {@link PendingAuthDetail#MATCH_STATUS_PENDING} -- pending a match against a posted transaction,
     * which is a live commitment -- and a decline becomes
     * {@link PendingAuthDetail#MATCH_STATUS_DECLINED}, which is terminal because nothing will ever
     * match a declined authorization.</p>
     *
     * <p>Assumptions: the two states the domain also admits are NOT reachable from here.
     * {@code PA-MATCH-PENDING-EXPIRED} is set when a pending row ages out and
     * {@code PA-MATCHED-WITH-TRAN} when posting matches one, so both are later transitions on an
     * existing row rather than initial states, and the entity refuses either at construction.</p>
     *
     * @param decision the decision reached; must not be {@code null}
     * @return the one-character match status to persist, never {@code null}
     */
    private String matchStatusFor(AuthorizationDecisionService.Decision decision) {
        return decision.approved()
                ? PendingAuthDetail.MATCH_STATUS_PENDING
                : PendingAuthDetail.MATCH_STATUS_DECLINED;
    }

    /**
     * Renders an instant as the five-digit ordinal date the authorization key carries.
     *
     * @param now the instant to render; must not be {@code null}
     * @return the two-digit year followed by the three-digit day of year, as one integer
     */
    private Integer ordinalDateOf(LocalDateTime now) {
        return (now.getYear() % JULIAN_YEAR_MODULUS) * JULIAN_YEAR_MULTIPLIER + now.getDayOfYear();
    }

    /**
     * Renders an instant as the nine-digit time of day the authorization key carries.
     *
     * @param now the instant to render; must not be {@code null}
     * @return the hour, minute, second and millisecond as one integer
     */
    private Integer timeOfDayOf(LocalDateTime now) {
        return now.getHour() * TIME_HOUR_MULTIPLIER
                + now.getMinute() * TIME_MINUTE_MULTIPLIER
                + now.getSecond() * TIME_SECOND_MULTIPLIER
                + now.getNano() / NANOS_PER_MILLISECOND;
    }

    /**
     * Refuses a decoded request that does not satisfy the payload contract this context publishes.
     *
     * <p>Refactoring Rationale: this is the LIVE validation boundary, and it did not exist. The consumer
     * previously applied exactly one hand-written check here -- the amount domain -- and worked from the
     * decoded wire record for everything else, so every other constraint
     * {@link AuthorizationRequestPayload} declares was enforced only on the structured path that no
     * message travels. The consequences were concrete rather than theoretical: an absent field passed,
     * because the decoder only splits and does not require; and a processing code or entry mode holding
     * letters passed and was then silently rewritten by digit-stripping into a plausible number, so a
     * malformed value was persisted as a well-formed different one. Routing the decoded record through
     * {@link AuthorizationMessageMapper#toPayload(AuthRequest)} makes the declared contract the enforced
     * contract, and the amount domain arrives with it rather than being restated.</p>
     *
     * <p>Assumptions: the crossing runs BEFORE the idempotency seek and before every lookup and every
     * transformation, so a nonconforming request touches neither the account context nor a row and no
     * value is normalised on the way to being refused. Ordering it after the seek would let a malformed
     * message take a row lock; ordering it after the transformations is what allowed a stripped value to
     * be stored.</p>
     *
     * <p>Trade-offs: the request is refused rather than declined. Declining would record an
     * authorization and consume the account's declined counter for a message that never conformed to the
     * contract, and would answer a requester as though its request had been considered; refusing lets the
     * queue redeliver it and then dead-letter it, which is the same treatment every malformed field
     * already receives from the decoder. The refusal is a divergence from the baseline, which performs no
     * such check and would approve a negative amount, and it is registered as
     * {@code D-NEGATIVE-AUTH-AMOUNT} in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Assumptions: the raised message names the violated COMPONENTS and never their values. A
     * violation report from the engine quotes the invalid value by default, and one of these components
     * is a primary account number, so the paths are collected and the values are dropped -- which keeps
     * the dead-letter diagnostic useful without writing cardholder data into it.</p>
     *
     * @param request the decoded wire record; must not be {@code null}
     * @throws AuthMessageFormatException if the derived payload violates the published contract
     */
    private void requireDeclaredContract(AuthRequest request) {
        try {
            this.payloads.toPayload(request);
        } catch (ConstraintViolationException violations) {
            String components = violations.getConstraintViolations().stream()
                    .map(violation -> String.valueOf(violation.getPropertyPath()))
                    .sorted()
                    .distinct()
                    .collect(Collectors.joining(", "));
            throw new AuthMessageFormatException(
                    "the request does not satisfy the published payload contract; the components at"
                            + " fault are: " + components);
        }
    }

    /**
     * Writes a reply into the outbox, to be published after this transaction commits.
     *
     * <p>Refactoring Rationale: the destination is checked against a configured allowlist by exact
     * match, and previously it was not. The value arrives as a message attribute, so it is chosen by
     * whoever can put a message on the request queue; without the check this service would send a reply
     * carrying a card number, a transaction identifier and an authorization outcome to any queue address
     * that attribute named, including one in another account. An allowlist is the only form of the check
     * that works, because the set of legitimate reply queues is a deployment fact rather than a pattern:
     * a prefix or a regular-expression test on the address would admit any queue whose name a requester
     * could arrange to match.</p>
     *
     * <p>Trade-offs: a request naming an unlisted destination is decided and then goes unanswered, which
     * is the same outcome as a request naming no destination at all. The decision still stands because it
     * is the account's own history and the requester does not get to withdraw it by supplying a bad
     * address; the unanswered request is logged, without the address, so an operator can add a legitimate
     * new requester to the allowlist.</p>
     *
     * @param message the received message, whose header names the reply destination; must not be
     *     {@code null}
     * @param reply the reply to publish; must not be {@code null}
     * @param correlationId the requester's correlation identifier; may be {@code null}
     * @param now the current instant in coordinated universal time; must not be {@code null}
     */
    private void enqueueReply(Message<String> message, AuthReply reply, String correlationId,
            LocalDateTime now) {
        String replyQueueUrl = header(message, HEADER_REPLY_TO);
        if (replyQueueUrl == null || replyQueueUrl.isBlank()) {
            // WHY : Trade-offs: with no reply destination there is nobody to answer, so the decision
            // stands and no outbox row is written. Falling back to a configured queue would answer a
            // requester that never asked to be answered there, and silently, which is worse than not
            // answering a request that supplied no address.
            LOG.warn("event=auth.reply.suppressed reason=no-reply-destination");
            return;
        }
        if (!this.replyQueueAllowlist.contains(replyQueueUrl)) {
            // WHY : Assumptions: the rejected address is NOT logged. It is attacker-influenced text
            // reaching a log field, which is the same exposure the correlation attribute is checked for
            // above; the allowlist is short and an operator can compare it against the requester's own
            // configuration without this line quoting the value back.
            LOG.warn("event=auth.reply.suppressed reason=destination-not-allowlisted"
                    + " allowlistSize={}", this.replyQueueAllowlist.size());
            return;
        }
        // WHY : Refactoring Rationale: the row carries KEYED TOKENS for the two queue identities, and it
        //   carried the card number and the transaction identifier themselves before. Both values become
        //   message metadata at publication -- MessageGroupId and MessageDeduplicationId -- and the
        //   queue's server-side encryption covers a body and not its metadata, so the raw form put the
        //   primary account number and the acquirer's transaction identifier into queue telemetry and
        //   every send trace. The tokens keep both SEMANTICS intact, because each is equal for equal
        //   inputs and different for different ones, which is the only property first-in-first-out
        //   ordering and duplicate suppression need. This is what docs/adr/ADR-004-messaging.md requires
        //   under "Ordering is grouped by card".
        // WHY : Assumptions: both tokens are derived HERE, inside the deciding transaction, rather than at
        //   publication. The publisher then needs no key material, and a row whose payload could not be
        //   parsed is still publishable -- which is precisely the case where publishing matters most.
        // WHY : Refactoring Rationale: the row is now assembled through the mapper's publication
        //   projection and OutboxMessage.toPendingRow rather than by calling the entity's constructor with
        //   seven positional arguments here. The values written are identical -- the projection derives
        //   the same two tokens from the same tokeniser, encodes the same payload through the same codec,
        //   and toPendingRow assigns the same format label, a null publication instant and a zero attempt
        //   counter that the constructor did. What changes is that ONE type now decides what a reply
        //   publication consists of. Before this, the projection existed and nothing called it: the
        //   listener built the row and the publisher read the row's columns one by one, so the type whose
        //   documented purpose was to be the single description of a publication described nothing, and
        //   two independent assemblies could drift apart with no test able to notice -- four of the seven
        //   constructor arguments are character values of similar shape, so a transposition would have
        //   compiled.
        OutboxMessage publication = this.payloads.toOutboxMessage(reply,
                new AuthorizationMessageMapper.ReplyRouting(replyQueueUrl, correlationId,
                        now.plusSeconds(DEFAULT_REPLY_EXPIRY_SECONDS)),
                this.messagingTokeniser);
        this.outbox.save(publication.toPendingRow(now));
    }

    /**
     * Rebuilds the reply for an authorization already decided.
     *
     * <p>Assumptions: every field comes from the recorded row rather than being re-derived, so a replay
     * returns the same six values the first answer did even if the decision logic has since changed.</p>
     *
     * @param recorded the authorization already recorded; must not be {@code null}
     * @return the reply as first answered, never {@code null}
     */
    private AuthReply replyFor(PendingAuthDetail recorded) {
        return new AuthReply(recorded.getCardNum(), recorded.getTransactionId(),
                recorded.getAuthIdCode(), recorded.getAuthRespCode(), recorded.getAuthRespReason(),
                Money.of(recorded.getApprovedAmount()));
    }

    /**
     * Seeks an authorization already recorded for this request's card and transaction identifier.
     *
     * @param request the decoded request; must not be {@code null}
     * @return the recorded authorization, or an empty optional when the request carries no identifier or
     *     none is recorded
     */
    private Optional<PendingAuthDetail> existingDecision(AuthRequest request) {
        String transactionId = request.transactionId();
        if (transactionId == null || transactionId.isBlank()) {
            // WHY : Assumptions: with no identifier there is nothing to seek on, so the request is
            // treated as new. The baseline has no idempotency check at all, so treating an unidentified
            // request as new is the baseline's behaviour rather than a weakening of this one.
            return Optional.empty();
        }
        return this.details.findByCardNumAndTransactionId(request.cardNum(), transactionId);
    }

    /**
     * Reads the correlation attribute and returns it unaltered when it satisfies the MESSAGING rule.
     *
     * <p>Refactoring Rationale: the rule applied here is
     * {@link MessagingCorrelationId#isCanonical(String)} and not the servlet one. Two defects are being
     * corrected at once. First, this attribute once went straight from the message into the logging
     * context and into a persisted row with no check at all, so a requester could write a line
     * terminator into a log record. Second, the fix for that borrowed the SERVLET predicate, which
     * bounds an identity at twenty-four characters drawn from a short alphabet -- appropriate for a value
     * this system mints for a response header, and wrong for one a requester renders from a twenty-four
     * BYTE queue field. Forty-eight hexadecimal characters, thirty-two base64 characters and a
     * hyphenated identifier are all legitimate renderings of that field and all three failed the
     * borrowed rule, so the consumer discarded identities its requesters were waiting on and answered
     * with a reply carrying no correlation attribute at all.</p>
     *
     * <p>Assumptions: a conforming value is returned VERBATIM -- not trimmed, not case-folded, not
     * re-encoded -- because the requester pairs the answer to the question on the exact opaque value it
     * sent. The separately sanitised rendering used for the logging context is produced by
     * {@link MessagingCorrelationId#logSafe(String)}, so the value that reaches a log and the value that
     * reaches the reply are allowed to differ, which is what lets the echo be exact without making the
     * log forgeable.</p>
     *
     * <p>Trade-offs: a value that is PRESENT and non-canonical now REFUSES the message, where an earlier
     * revision dropped the attribute and decided the request anyway. The reversal follows from the rule
     * having widened: under the borrowed servlet rule a failing value was usually a legitimate identity
     * in an unexpected shape, and destroying a payment authorization over that would have been wrong;
     * under this rule a failing value carries a control character or exceeds the width the store can
     * hold, and neither is something a legitimate requester expresses. Refusing lets the queue redeliver
     * and then dead-letter the message, which leaves evidence, whereas dropping the attribute left the
     * requester holding a correlation value that never came back and nothing to explain why.</p>
     *
     * <p>Assumptions: an ABSENT attribute is not a malformed one and is not refused. The baseline sets
     * its own correlation field to a no-match constant before the read at {@code cbl/COPAUA0C.cbl} L396,
     * so a requester that supplies none is ordinary; its reply carries none either.</p>
     *
     * @param message the received message; must not be {@code null}
     * @return the correlation identifier to echo, exactly as supplied, or {@code null} when the message
     *     carried none
     * @throws AuthMessageFormatException if the attribute is present and not canonical
     */
    private String conformingCorrelationId(Message<String> message) {
        String candidate = header(message, HEADER_CORRELATION_ID);
        if (!MessagingCorrelationId.isPresent(candidate)) {
            return null;
        }
        if (!MessagingCorrelationId.isCanonical(candidate)) {
            // WHY : Assumptions: the length and the SANITISED rendering are reported, and the raw value
            // is not. Reporting the sanitised form gives an operator enough to recognise which requester
            // sent it while keeping out of the log record exactly what the refusal is for.
            LOG.warn("event=auth.request.correlation-id-rejected length={} sanitised={}",
                    candidate.length(), MessagingCorrelationId.logSafe(candidate));
            throw new AuthMessageFormatException("the correlationId attribute is not canonical: a"
                    + " messaging correlation identity must be at most "
                    + MessagingCorrelationId.MAX_LENGTH
                    + " printable US-ASCII characters with no space and no control character");
        }
        return candidate;
    }

    /**
     * Reports whether a message has already expired.
     *
     * @param message the received message; must not be {@code null}
     * @param now the current instant in coordinated universal time; must not be {@code null}
     * @return {@code true} when the message carries a parseable expiry that is at or before {@code now}
     */
    private boolean isStale(Message<String> message, LocalDateTime now) {
        // WHY : Refactoring Rationale: the parsing this delegates to was a private method here until two
        // further consumers acquired the same obligation. Three copies of a rule about when to DISCARD a
        // message would be three chances to disagree about it, and the symptom of a disagreement would be
        // one requester's expiry honoured differently by two consumers -- an intermittently unanswered
        // request rather than an error anywhere.
        // WHY : Assumptions: the helper answers false for an unparseable value, so this method alone would
        // fail open. The caller therefore refuses an unparseable value BEFORE consulting this one, and this
        // method deliberately does not repeat that check: a second copy of the malformed rule here is how
        // the two would drift apart.
        return MessageExpiry.isExpired(header(message, HEADER_EXPIRES_AT),
                now.toInstant(ZoneOffset.UTC));
    }

    /**
     * Reports whether a message supplied an expiry attribute that will not parse as an instant.
     *
     * <p>Assumptions: an ABSENT attribute is not unparseable. A request that states no expiry is answered
     * under the separately documented no-expiry policy, which is what keeps a producer that never adopted
     * the attribute working; a request that states one this consumer cannot read is refused instead,
     * because the alternative is to let the attribute be defeated by corrupting it.</p>
     *
     * @param message the received message; must not be {@code null}
     * @return {@code true} when the attribute is present, non-blank and does not parse
     */
    private boolean hasUnparseableExpiry(Message<String> message) {
        return MessageExpiry.isMalformed(header(message, HEADER_EXPIRES_AT));
    }

    /**
     * Reports the trimmed length of the supplied expiry attribute, for a diagnostic that omits its value.
     *
     * @param message the received message; must not be {@code null}
     * @return the number of characters the attribute carries once trimmed, or zero when it is absent
     */
    private int expiryLength(Message<String> message) {
        String raw = header(message, HEADER_EXPIRES_AT);
        return raw == null ? 0 : raw.trim().length();
    }

    /**
     * Reads a string message header.
     *
     * @param message the received message; must not be {@code null}
     * @param name the header name; must not be {@code null}
     * @return the header value rendered as a string, or {@code null} when the header is absent
     */
    private String header(Message<String> message, String name) {
        Object value = message.getHeaders().get(name);
        return value == null ? null : value.toString();
    }

    /**
     * Parses a fixed-width numeric text field, refusing anything that is not a digit.
     *
     * <p>Assumptions: the field is blank-padded rather than zero-padded in the baseline extract, so a
     * blank field parses to zero rather than failing. Zero is the value the packed field holds when the
     * baseline leaves it unset, and a blank-padded numeric field is the one case where an absence is
     * legitimately a zero rather than a defect.</p>
     *
     * <p>Refactoring Rationale: a non-digit is REFUSED where it was previously STRIPPED. Stripping was
     * the more serious of the two defects this method had: {@code "1A2"} became {@code 12} and
     * {@code "N/A"} became {@code 0}, so a malformed value was silently rewritten into a well-formed
     * DIFFERENT value and then persisted as though the acquirer had sent it. A refusal cannot be
     * mistaken for data. The payload contract now also refuses such a value at the intake boundary, by
     * the digits-only expressions {@link AuthorizationRequestPayload} declares on the two
     * numeric-picture fields, so this method should never see one; it refuses rather than trusting that,
     * because it is the last place the value is still recognisable as text.</p>
     *
     * @param text the field text; may be {@code null}
     * @return the parsed value, or zero when the text is absent or entirely blank
     * @throws AuthMessageFormatException if the text holds any character that is neither a digit nor a
     *     blank
     */
    private Integer digitsOf(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        StringBuilder digits = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character >= '0' && character <= '9') {
                digits.append(character);
            } else if (character != ' ') {
                throw new AuthMessageFormatException(
                        "a numeric-picture field carries a character that is neither a digit nor a"
                                + " blank at position " + (index + 1) + "; the value is not rendered"
                                + " here because this method is reached by fields the acquirer supplies");
            }
        }
        return digits.isEmpty() ? 0 : Integer.valueOf(digits.toString());
    }

    /**
     * Parses a fixed-width numeric text field into a short.
     *
     * @param text the field text; may be {@code null}
     * @return the parsed value, or {@code null} when the text holds no digits
     */
    private Short shortOf(String text) {
        Integer parsed = digitsOf(text);
        return text == null || text.isBlank() ? null : Short.valueOf(parsed.shortValue());
    }
}
