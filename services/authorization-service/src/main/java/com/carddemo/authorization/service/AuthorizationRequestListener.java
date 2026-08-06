package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.dto.AuthorizationRequestPayload;
import com.carddemo.authorization.repository.OutboxRepository;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.codec.CsvAuthCodec.AuthMessageFormatException;
import com.carddemo.common.codec.CsvAuthCodec.AuthReply;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import com.carddemo.common.money.Money;
import com.carddemo.common.web.CorrelationIdFilter;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
 * and customer at lines 448 to 452, decides at lines 657 to 734, writes the decision to its databases at
 * lines 790 to 791, commits with a syncpoint at line 335 and puts its reply at line 753. Four properties
 * of that program are preserved here and one defect in it is deliberately not:</p>
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
 * <p>Refactoring Rationale: the defect not preserved is the lost-reply window. The baseline commits its
 * data and then publishes its reply as two separate units of work, so a failure between them leaves an
 * authorization the data says was decided and a requester that never hears back. This method writes the
 * reply into {@link AuthReplyOutbox} INSIDE the deciding transaction, and {@link OutboxPublisher} sends
 * it afterwards. The row and the decision therefore commit together or neither does, and publication can
 * be retried without re-deciding. The divergence is registered in
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
    public static final String HEADER_EXPIRES_AT = "expiresAt";

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
     * The seam to the context that owns the cross-reference, account and customer records.
     */
    private final AccountContextClient accounts;

    /**
     * The reply destinations this consumer is permitted to publish to.
     */
    private final List<String> replyQueueAllowlist;

    /**
     * The clock, injected so staleness, timestamps and authorization keys are testable without waiting.
     */
    private final Clock clock;

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
     * @param accounts the account-context seam; must not be {@code null}
     * @param replyQueueAllowlist the reply destinations this consumer may publish to; must not be empty
     * @param clock the clock staleness, timestamps and authorization keys are read from; must not be
     *     {@code null}
     */
    public AuthorizationRequestListener(PendingAuthSummaryRepository summaries,
            PendingAuthDetailRepository details, OutboxRepository outbox,
            AuthorizationDecisionService decisions, AccountContextClient accounts,
            @Value("${carddemo.messaging.reply-queue-allowlist}") List<String> replyQueueAllowlist,
            Clock clock) {
        this.summaries = summaries;
        this.details = details;
        this.outbox = outbox;
        this.decisions = decisions;
        this.accounts = accounts;
        this.replyQueueAllowlist = List.copyOf(replyQueueAllowlist);
        this.clock = clock;
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
    @SqsListener(queueNames = "${carddemo.messaging.pauth-request-queue}",
            maxConcurrentMessages = "${carddemo.messaging.max-concurrent-messages:10}",
            maxMessagesPerPoll = "${carddemo.messaging.max-messages-per-poll:10}",
            pollTimeoutSeconds = "${carddemo.messaging.poll-timeout-seconds:5}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRequest(Message<String> message) {
        String correlationId = conformingCorrelationId(message);
        MDC.put(MDC_CORRELATION_ID, correlationId == null ? "" : correlationId);
        try {
            LocalDateTime now = LocalDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC);
            if (isStale(message, now)) {
                // WHY : Trade-offs: a stale request is dropped rather than declined. Declining would
                // publish a reply to a requester that has already stopped waiting and would consume a
                // deduplication identifier, so a legitimate retry of the same transaction would then be
                // suppressed as a duplicate of an answer nobody read.
                LOG.warn("event=auth.request.dropped reason=expired expiresAt={}",
                        header(message, HEADER_EXPIRES_AT));
                return;
            }
            AuthRequest request = CsvAuthCodec.decodeRequest(message.getPayload());
            requireAmountWithinRecordDomain(request.transactionAmount());
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
        if (xref.isPresent()) {
            persist(xref.get(), account, summary, request, decision, now);
        } else {
            // WHY : Assumptions: with no cross-reference row there is no account to hang a summary or a
            // detail row from, so the decline is answered without being recorded. That is the baseline's
            // own behaviour: its line 463 performs the database writes only when the card resolved, so an
            // unknown card leaves no trace in either segment there either.
            LOG.warn("event=auth.request.declined reason=card-not-cross-referenced respReason={}",
                    decision.responseReason());
        }
        AuthReply reply = new AuthReply(request.cardNum(), request.transactionId(),
                this.decisions.identificationCodeFor(request), decision.responseCode(),
                decision.responseReason(), decision.approvedAmount());
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
     * @param now the current instant in coordinated universal time; must not be {@code null}
     */
    private void persist(AccountContextClient.CardXref xref,
            Optional<AccountContextClient.Account> account, Optional<PendingAuthSummary> summary,
            AuthRequest request, AuthorizationDecisionService.Decision decision, LocalDateTime now) {
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
        this.details.save(record(xref.accountId(), request, decision, now));
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
     * @param accountId the resolved account; must not be {@code null}
     * @param request the decoded request; must not be {@code null}
     * @param decision the decision reached; must not be {@code null}
     * @param now the current instant in coordinated universal time; must not be {@code null}
     * @return the unsaved detail row, never {@code null}
     */
    private PendingAuthDetail record(Long accountId, AuthRequest request,
            AuthorizationDecisionService.Decision decision, LocalDateTime now) {
        PendingAuthDetailKey key = new PendingAuthDetailKey(accountId, ordinalDateOf(now),
                timeOfDayOf(now));
        return new PendingAuthDetail(key, request.authDate(), request.authTime(),
                request.cardNum(), request.authType(), request.cardExpiryDate(),
                request.messageType(), request.messageSource(),
                this.decisions.identificationCodeFor(request), decision.responseCode(),
                decision.responseReason(), request.processingCode(),
                request.transactionAmount().amount(), decision.approvedAmount().amount(),
                request.merchantCategoryCode(), request.acquirerCountryCode(),
                shortOf(request.posEntryMode()), request.merchantId(), request.merchantName(),
                request.merchantCity(), request.merchantState(), request.merchantZip(),
                request.transactionId());
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
     * Refuses a requested amount the pending-authorization record cannot hold.
     *
     * <p>Assumptions: the domain itself is stated once, by
     * {@link AuthorizationRequestPayload#isAmountWithinRecordDomain(Money)}, and this method only
     * decides what happens when a value fails it. Duplicating the bounds here would put the same rule in
     * two executable places, and the copy is the one that would fall behind -- which is exactly how the
     * queue payload's constraints and the live path came to differ.</p>
     *
     * <p>Assumptions: the check runs BEFORE the idempotency seek and before every lookup, so a
     * nonconforming request touches neither the account context nor a row. Ordering it after the seek
     * would let a malformed amount take a row lock on the way to being refused.</p>
     *
     * <p>Trade-offs: the request is refused rather than declined. Declining would record an
     * authorization and consume the account's declined counter for a message that never conformed to the
     * contract, and would answer a requester as though its request had been considered; refusing lets the
     * queue redeliver it and then dead-letter it, which is the same treatment every other malformed field
     * already receives from the decoder. The refusal is a divergence from the baseline, which performs no
     * such check and would approve a negative amount, and it is registered as
     * {@code D-NEGATIVE-AUTH-AMOUNT} in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * @param requested the decoded request amount; must not be {@code null}
     * @throws AuthMessageFormatException if the amount is negative or beyond the record's magnitude
     */
    private void requireAmountWithinRecordDomain(Money requested) {
        if (!AuthorizationRequestPayload.isAmountWithinRecordDomain(requested)) {
            throw new AuthMessageFormatException("PA-RQ-TRANSACTION-AMT is outside the domain"
                    + " 0.00 through " + Money.MAX_MAGNITUDE.toPlainString()
                    + " that PA-TRANSACTION-AMT PIC S9(10)V99 COMP-3 accepts for a charge");
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
        this.outbox.save(new AuthReplyOutbox(replyQueueUrl, correlationId, reply.cardNum(),
                reply.transactionId(), CsvAuthCodec.encodeReply(reply),
                now.plusSeconds(DEFAULT_REPLY_EXPIRY_SECONDS), now));
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
     * Reads the correlation attribute and returns it only when it conforms to the shared rule.
     *
     * <p>Refactoring Rationale: this attribute previously went straight from the message into the logging
     * context and into a persisted outbox row with no width and no alphabet check, while the servlet
     * transport applied both to the same field. A requester could therefore write a quotation mark, a
     * comma or sixty-four characters of anything into the log field the other transport refuses one
     * character of. The rule now comes from
     * {@link CorrelationIdFilter#isConformingCorrelationId(String)}, which is the single place it is
     * defined, so the two transports cannot diverge again.</p>
     *
     * <p>Trade-offs: a nonconforming value is DROPPED and the request is still decided, where the servlet
     * transport refuses the request outright. The asymmetry is deliberate: a caller holding an open
     * connection can be told to correct its header and retry, whereas a queued authorization request
     * cannot be corrected by its sender in time to matter, and destroying it over a log field would turn
     * a diagnostics problem into a declined payment.</p>
     *
     * @param message the received message; must not be {@code null}
     * @return the correlation identifier to carry, or {@code null} when none was supplied or it did not
     *     conform
     */
    private String conformingCorrelationId(Message<String> message) {
        String candidate = header(message, HEADER_CORRELATION_ID);
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        if (!CorrelationIdFilter.isConformingCorrelationId(candidate)) {
            // WHY : Assumptions: the LENGTH is reported and the value is not, for the same reason the
            // value was refused -- writing it into this line would achieve exactly what the check
            // prevents.
            LOG.warn("event=auth.request.correlation-id-rejected length={}", candidate.length());
            return null;
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
        String raw = header(message, HEADER_EXPIRES_AT);
        if (raw == null || raw.isBlank()) {
            return false;
        }
        LocalDateTime expiresAt = parseExpiry(raw);
        if (expiresAt == null) {
            // WHY : Trade-offs: an unparseable expiry is treated as ABSENT rather than as already
            // passed. Treating it as passed would let one malformed attribute silently discard every
            // request from a requester whose formatting differs, and the request itself may be perfectly
            // valid; the malformed attribute is logged where it is parsed instead.
            LOG.warn("event=auth.request.expiry-unparseable length={}", raw.length());
            return false;
        }
        return !now.isBefore(expiresAt);
    }

    /**
     * Parses an expiry attribute, accepting either an epoch-millisecond value or a local date and time.
     *
     * <p>Assumptions: two forms are accepted because the attribute crosses a service boundary and the
     * requester is not necessarily this codebase. Accepting one form only would make interoperability
     * depend on a formatting choice nobody negotiated.</p>
     *
     * @param raw the attribute value; must not be {@code null}
     * @return the parsed instant in coordinated universal time, or {@code null} when neither form parses
     */
    private LocalDateTime parseExpiry(String raw) {
        String trimmed = raw.trim();
        try {
            if (!trimmed.isEmpty() && trimmed.chars().allMatch(Character::isDigit)) {
                return LocalDateTime.ofEpochSecond(Long.parseLong(trimmed) / 1000L,
                        (int) (Long.parseLong(trimmed) % 1000L) * 1_000_000, ZoneOffset.UTC);
            }
            return LocalDateTime.parse(trimmed);
        } catch (ArithmeticException | NumberFormatException
                | java.time.format.DateTimeParseException notAnExpiry) {
            // WHY : Assumptions: the caught value is deliberately not logged here. It came off the wire
            // and this class has no way to know it carries no cardholder data, so the caller logs only
            // its length. The exception is not rethrown because an attribute this class treats as
            // optional must not be able to fail a message.
            return null;
        }
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
     * Parses the digits of a fixed-width numeric text field.
     *
     * <p>Assumptions: the field is blank-padded rather than zero-padded in the baseline extract, so a
     * blank field parses to zero rather than failing. Zero is the value the packed field holds when the
     * baseline leaves it unset.</p>
     *
     * @param text the field text; may be {@code null}
     * @return the parsed value, or zero when the text holds no digits
     */
    private Integer digitsOf(String text) {
        if (text == null) {
            return 0;
        }
        StringBuilder digits = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isDigit(character)) {
                digits.append(character);
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
