package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.repository.OutboxRepository;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.codec.CsvAuthCodec.AuthReply;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import com.carddemo.common.money.Money;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Limit;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes authorization requests from the request queue, decides them, and writes the reply into the
 * transactional outbox.
 *
 * <p>This is the migrated form of {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}. The
 * baseline is a long-running task that reads its request queue at line 389, decides at lines 657 to 734,
 * writes the decision to its databases at lines 786 to 794 and 798 onward, commits with a syncpoint at
 * line 335 and puts its reply at line 753. Four properties of that program are preserved here and one
 * defect in it is deliberately not:</p>
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
 *       queue field did, so one consumer serves however many requesters have their own reply queues.</li>
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
     * The most recent authorizations consulted when resolving a card to an account.
     *
     * <p>Assumptions: one row is enough, because the resolution wants the single most recent association
     * and the query already orders by it. Asking for more would transfer rows the caller discards.</p>
     */
    private static final Limit ACCOUNT_RESOLUTION_LIMIT = Limit.of(1);

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
     * The clock, injected so staleness and timestamps are testable without waiting.
     */
    private final Clock clock;

    /**
     * Creates the consumer.
     *
     * <p>Assumptions: every collaborator arrives through the constructor rather than through field
     * injection, so an instance is fully formed once constructed and a unit test can supply a fixed clock
     * without a container.</p>
     *
     * @param summaries the summary repository; must not be {@code null}
     * @param details the detail repository; must not be {@code null}
     * @param outbox the outbox repository; must not be {@code null}
     * @param decisions the decision logic; must not be {@code null}
     * @param clock the clock staleness and timestamps are read from; must not be {@code null}
     */
    public AuthorizationRequestListener(PendingAuthSummaryRepository summaries,
            PendingAuthDetailRepository details, OutboxRepository outbox,
            AuthorizationDecisionService decisions, Clock clock) {
        this.summaries = summaries;
        this.details = details;
        this.outbox = outbox;
        this.decisions = decisions;
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
     * receive count. Swallowing it would delete a request no one had answered.</p>
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
        String correlationId = header(message, HEADER_CORRELATION_ID);
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
            Optional<PendingAuthDetail> alreadyDecided = existingDecision(request);
            if (alreadyDecided.isPresent()) {
                // WHY : Assumptions: the queue suppresses duplicates only inside its deduplication
                // window, so a redelivery after that window arrives as a new message. Re-publishing the
                // recorded answer keeps the requester served without incrementing the account's counters
                // a second time, which is what re-deciding would do.
                LOG.info("event=auth.request.replayed transactionId={}", request.transactionId());
                enqueueReply(message, replyFor(request, alreadyDecided.get()), correlationId, now);
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
     * @param message the received message, needed for its reply destination and expiry; must not be
     *     {@code null}
     * @param request the decoded request; must not be {@code null}
     * @param correlationId the requester's correlation identifier; may be {@code null}
     * @param now the current instant in coordinated universal time; must not be {@code null}
     */
    private void handleNewRequest(Message<String> message, AuthRequest request, String correlationId,
            LocalDateTime now) {
        Optional<Long> accountId = resolveAccount(request.cardNum());
        Optional<PendingAuthSummary> summary = accountId
                .flatMap(this.summaries::findWithLockByAccountId);
        AuthorizationDecisionService.Decision decision = this.decisions.decide(request, summary);
        if (accountId.isPresent()) {
            PendingAuthDetail detail = record(accountId.get(), request, decision);
            this.details.save(detail);
            summary.ifPresent(held -> apply(held, decision, request));
        } else {
            // WHY : Assumptions: with no account resolved there is no parent summary row, and the
            // database enforces that a detail row has one, so the decline is answered without being
            // recorded. That matches the baseline, which returns from its cross-reference-not-found path
            // at L516 without writing either segment.
            LOG.warn("event=auth.request.declined reason=account-unresolved respReason={}",
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
     * Builds the detail row that records a decision.
     *
     * <p>Assumptions: the row's key is the account plus the request's own authorization date and time,
     * parsed from the six-character fields the request carries. The baseline keys its child segment by
     * exactly those two values, so using a generated key here would make a migrated row and an
     * ETL-loaded row un-comparable.</p>
     *
     * @param accountId the resolved account; must not be {@code null}
     * @param request the decoded request; must not be {@code null}
     * @param decision the decision reached; must not be {@code null}
     * @return the unsaved detail row, never {@code null}
     */
    private PendingAuthDetail record(Long accountId, AuthRequest request,
            AuthorizationDecisionService.Decision decision) {
        PendingAuthDetailKey key = new PendingAuthDetailKey(accountId,
                digitsOf(request.authDate()), digitsOf(request.authTime()));
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
     * Applies a decision to the account's summary counters.
     *
     * <p>Assumptions: the summary is mutated through the two methods that move its counter, its total and
     * its balance together, so an approved authorization can never increment a count without reserving the
     * funds. The baseline updates the whole segment in one rewrite at its own line 798 onward, which is the
     * same indivisibility expressed differently.</p>
     *
     * @param summary the locked summary to update; must not be {@code null}
     * @param decision the decision reached; must not be {@code null}
     * @param request the decoded request, whose amount is recorded on a decline; must not be {@code null}
     */
    private void apply(PendingAuthSummary summary, AuthorizationDecisionService.Decision decision,
            AuthRequest request) {
        if (decision.approved()) {
            summary.recordApproved(decision.approvedAmount().amount());
        } else {
            summary.recordDeclined(request.transactionAmount().amount());
        }
    }

    /**
     * Writes a reply into the outbox, to be published after this transaction commits.
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
     * @param request the decoded request; must not be {@code null}
     * @param recorded the authorization already recorded; must not be {@code null}
     * @return the reply as first answered, never {@code null}
     */
    private AuthReply replyFor(AuthRequest request, PendingAuthDetail recorded) {
        return new AuthReply(recorded.getCardNum(), recorded.getTransactionId(),
                recorded.getAuthIdCode(), recorded.getAuthRespCode(), recorded.getAuthRespReason(),
                Money.of(recorded.getApprovedAmount()));
    }

    /**
     * Seeks an authorization already recorded for this request's transaction identifier.
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
        return this.details.findByTransactionId(transactionId);
    }

    /**
     * Resolves the account a card authorizes against.
     *
     * @param cardNum the sixteen-digit primary account number; must not be {@code null}
     * @return the resolved account, or an empty optional when the card cannot be resolved
     */
    private Optional<Long> resolveAccount(String cardNum) {
        List<Long> resolved = this.details.findAccountIdsByCardNum(cardNum,
                ACCOUNT_RESOLUTION_LIMIT);
        return resolved.isEmpty() ? Optional.empty() : Optional.of(resolved.get(0));
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
     * blank field parses to zero rather than failing. The key columns are declared not-null, and zero is
     * the value the packed field holds when the baseline leaves it unset.</p>
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
