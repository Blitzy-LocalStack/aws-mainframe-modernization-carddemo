package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.domain.OutboxMessage;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
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
import com.carddemo.common.observability.FailureSummary;
import com.carddemo.common.observability.LogSafeText;
import io.awspring.cloud.sqs.annotation.SqsListener;
import jakarta.validation.ConstraintViolationException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
 *       subject to the allowlist argued at {@link #requireAllowlistedReplyDestination(Message)}, which
 *       admits the request only when the destination it names is one the deployment listed. A request
 *       that names none, or names one that is not listed, is REFUSED before anything is read: this
 *       method may not commit a decision it cannot answer.</li>
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
 * <p>Assumptions: the card is resolved to an account through {@link AccountContextClient}, the seam to
 * the context that owns the cross-reference, and never from the account recorded on that card's own
 * previous authorizations. Alternatives Considered: exactly that substitute, since this context
 * owns no cross-reference table. Rejected because it cannot resolve the first authorization a card
 * ever presents -- the case that matters most -- and resolves a reissued card to its former
 * account, each failure declining a request the baseline approves.</p>
 *
 * <p>Refactoring Rationale: a WRITE failure inside the unit of work rolls this message's whole
 * transaction back, and the baseline's equivalent path lets its partial write stand. Both of the
 * baseline's segment writes end the same way: {@code 8400-UPDATE-SUMMARY} at lines 835 to 847 and
 * {@code 8500-INSERT-AUTH} at lines 920 to 932 each move the status, test {@code IF STATUS-OK CONTINUE},
 * and on the else branch set an error location -- {@code 'I003'} and {@code 'I004'} -- together with the
 * critical level, the subsystem, the status code, a message and the card number as the event key, then
 * perform the error paragraph at lines 846 and 931 and fall through to their exit. Neither sets an abort
 * flag and neither skips the syncpoint at line 335. What ends the task instead is the critical level
 * itself: the error paragraph tests it at lines 1008 to 1010 and performs the end routine at lines 1016
 * to 1024, which terminates and returns -- and a task returning normally takes the platform's implicit
 * end-of-task syncpoint, so the partial write commits. On the axis of *stopping*, the target is at
 * parity: the exception ends this delivery and the queue redelivers and then dead-letters it. The
 * divergence is on the axis of *durability* -- rollback here, an implicit commit there -- and it is
 * registered as {@code D-D} in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Assumptions: a RECEIVE failure is terminal for the poll cycle, and here that is structural rather
 * than coded. The baseline's {@code 3100-READ-REQUEST-MQ} at lines 386 to 434 handles a reason other
 * than no-message-available at line 418 by logging {@code 'M003'} at line 429, after which the paragraph
 * simply ends at lines 430 to 432 and exits at line 434 -- setting neither the no-more-messages
 * condition nor the loop-end flag. Read on its own, that returns to the loop at line 326 with the
 * 500-byte get buffer declared at line 103 UNCHANGED, so the extract at lines 354 to 355 would split the
 * previous request again and an already-committed authorization would be decided a second time; the
 * corroborating tell is that the failure path logs the PREVIOUS message's card number. The critical
 * level is what actually stops it. In the target there is no buffer to leave unchanged: each delivery
 * carries its own payload into this method, so a failed receive cannot present a stale one, and the
 * shape is unreachable rather than guarded against.</p>
 *
 * <p>Assumptions: one classification difference at that site is NOT a divergence and is recorded so it
 * is not read as one. The baseline sets the subsystem to the transaction monitor at line 421 for a
 * failure of the message transport, while the same program sets it to the message transport at line 770
 * for the reply put; the target reports both as messaging faults. Nothing observable turns on it -- the
 * field is a log dimension, not a control value.</p>
 *
 * <p>Assumptions: the queue is a FIFO queue grouped by card number, so requests for one card are
 * delivered in order and requests for different cards are delivered in parallel. Requests for two
 * DIFFERENT cards of one account are therefore concurrent, which is why
 * {@link #insertFirstSummary(long, AccountContextClient.CardXref, Optional, AuthRequest,
 * AuthorizationDecisionService.Decision)} tolerates a summary that appeared after its own read, and why
 * {@link #contributeToStoredSummary(long, Optional, AuthRequest,
 * AuthorizationDecisionService.Decision)} reaches the stored row through statements ONLY and mutates no
 * loaded instance: a whole-row write from a load-time snapshot would silently discard the concurrent
 * card's contribution.</p>
 *
 * <p><b>Retry.</b> Refactoring Rationale: NO in-process retry is applied to this handler, and the reason
 * is a property of where the retry would sit rather than a preference. The handler's whole body runs
 * inside one transaction, so an attempt that failed part-way has already marked that transaction for
 * rollback; a second attempt within it would run against a doomed unit of work and could only fail
 * again, and an attempt placed OUTSIDE it would re-decide an authorization whose first attempt may
 * already have committed. Durability comes instead from the transport: the exception rolls the
 * transaction back, the message becomes visible again after its timeout, and the redrive policy moves it
 * to the dead-letter queue at the fifth receive. Batch steps get the same treatment one level up, from
 * per-state retry in the orchestrator. Where this service DOES make a synchronous call it is bounded by
 * an explicit connect and read timeout rather than by a retry, so a slow dependency cannot hold a
 * message's transaction open.</p>
 *
 * <p>Assumptions: the boundary any {@code includes} list would have to respect is recorded even though
 * no list is written, so a later reader does not widen one by default. The reference program declares
 * its own transient set as exactly three infrastructure statuses --
 * {@code 88 RETRY-CONDITION VALUE 'BA', 'FH', 'TE'.} at line 94 of
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}, being a database unavailable at line 91, a
 * handler failure named nowhere else in the program, and a failure to schedule its data access at line
 * 93. The same declaration block names four statuses and deliberately leaves each OUT of that set:
 * {@code 'GE'} segment-not-found at line 87, {@code 'II'} duplicate-segment at line 88, {@code 'GP'}
 * wrong-parentage at line 89 and {@code 'GB'} end-of-database at line 90. Each of those four describes a
 * state of the DATA rather than of the infrastructure, so retrying one would repeat a read that has
 * already answered. A fifth, {@code 'TC'} scheduled-more-than-once at line 92, is omitted for a
 * different reason again -- it reports a fault in the program's own sequencing, which a retry would
 * reproduce exactly. The narrowness is a convention of the whole extension rather than one program's
 * choice: the identical three-value set is declared in seven of its programs, at {@code COPAUS0C.cbl}
 * line 87, {@code COPAUS1C.cbl} line 88, {@code CBPAUP0C.cbl} line 92, {@code PAUDBLOD.CBL} line 107,
 * {@code PAUDBUNL.CBL} line 105 and {@code DBUNLDGS.CBL} line 109 besides this program's line 94.</p>
 *
 * <p>Alternatives Considered: adopting a resilience library so this handler could carry a declarative
 * retry policy. Rejected because retry already lives in the framework core that arrives with the Spring
 * Boot parent -- {@code @Retryable}, {@code @ConcurrencyLimit} and a programmatic retry policy built
 * from {@code includes}, {@code excludes}, {@code maxRetries}, {@code delay}, {@code jitter},
 * {@code multiplier} and {@code maxDelay} -- so a library would install a second retry authority for a
 * capability the platform already provides. Two details of that API are recorded because both are
 * commonly written the other way round: the attribute is {@code maxRetries} and NOT
 * {@code maxAttempts}, so the total number of attempts is one plus its value and defaults to three, and
 * the enabling annotation is {@code @EnableResilientMethods} and NOT {@code @EnableRetry}. Neither
 * {@code resilience4j-spring-boot3}, whose published artifact targets the previous major of the
 * framework, nor the superseded {@code spring-retry} is added, and no circuit breaker is configured --
 * the only synchronous dependency reached from here is inside the private network behind a bounded
 * timeout, so a breaker would add a state machine to reason about without removing a failure mode. The
 * absence of the library is recorded in {@code docs/adr/ADR-002-compute-platform.md}.</p>
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
     * The message attribute declaring the payload's wire format, which must be the delimited text form.
     *
     * <p>Assumptions: the same attribute name the reply path sets, so one producer's request and this
     * consumer's reply describe their payloads through one key. The baseline carries the equivalent in
     * the message descriptor, {@code MOVE MQFMT-STRING TO MQMD-FORMAT} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 751, and the broker refuses a
     * mismatched format before the program sees the message.</p>
     */
    public static final String HEADER_CONTENT_TYPE = "contentType";

    /**
     * The one payload format this consumer decodes.
     *
     * <p>Assumptions: because the payload is declared as a string format, the field order and the
     * delimiter ARE the interface. A producer that sent structured text of another shape under another
     * content type would still present sixteen leading characters that decode as a card number, so the
     * failure of accepting it is not a decode error but a decision taken on misread fields.</p>
     */
    public static final String CONTENT_TYPE_CSV = "text/csv";

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
     *
     * <p>Assumptions: five is a DECODED figure and not a copied one, and the decode is the reason this
     * constant is expressed in seconds rather than in either of the source's own units.
     * {@code MOVE 50 TO MQMD-EXPIRY} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 750 is measured in TENTHS of a
     * second, so it is five seconds and not fifty of anything; {@code MOVE 5000 TO WS-WAIT-INTERVAL} at
     * line 242, which the receive then uses at line 393, is measured in MILLISECONDS, so it is also five
     * seconds. The two figures express the same duration in units a hundred apart, and neither carries a
     * comment in the source saying so, so either one lifted as written would be wrong by two orders of
     * magnitude in one direction or the other.</p>
     *
     * <p>Trade-offs: enforcement of the expiry moves from the BROKER to the CONSUMER, and that is a real
     * loss rather than a relabelling. Under the baseline the queue manager discarded an expired message
     * itself, so the guarantee was uniform and unconditional: no program could act on a stale request
     * because no program was ever handed one, and a consumer that forgot to check could not go wrong.
     * The target queue has no per-message time to live, so the instant travels as the
     * {@link #HEADER_EXPIRES_AT} attribute and each consumer must honour it -- which means the guarantee
     * is now only as good as the consumers, and a future consumer that omitted the check would act on a
     * message the baseline would never have delivered. What is gained is that the deadline becomes
     * inspectable data rather than broker state: it appears on the message, it is asserted by this
     * class's tests, and a message dropped for staleness leaves a log record naming why, none of which
     * the discarded-at-the-broker form could offer. The alternatives, including keeping a broker that
     * has the feature, are recorded in {@code docs/adr/ADR-004-messaging.md}.</p>
     */
    public static final int DEFAULT_REPLY_EXPIRY_SECONDS = 5;

    /**
     * How many requests one processing window handles before intake is closed and reopened.
     *
     * <p>Assumptions: read from {@code 05 WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 40. This constant is the DECLARED
     * limit, and the number of requests one window admits is that limit plus
     * {@link #BASELINE_COMPARISON_OFFSET} -- see that constant for why the two differ.</p>
     *
     * <p>Assumptions: the window admits 501 requests, which is the number the reference program actually
     * processes rather than the 500 it declares. Its counter is incremented after the get at line 332 and
     * then tested with {@code >} rather than {@code >=} at line 339, so counts one through 500 all take
     * the {@code ELSE} and read another request at line 342, and only count 501 sets the loop-end flag at
     * line 340. Functional parity is measured against observable behaviour, so the observable 501 is the
     * figure enforced here and no divergence is registered for the declared one.</p>
     *
     * <p>Refactoring Rationale: it is a default rather than a fixed value, overridable by
     * {@code carddemo.messaging.request-process-limit}, so later performance work can change the window
     * size on measured evidence instead of by editing this class. The default is the baseline's declared
     * number so that an unconfigured deployment behaves as the contract published.</p>
     */
    public static final int DEFAULT_REQUEST_PROCESS_LIMIT = 500;

    /**
     * The extra admission the reference program's increment-then-compare grants beyond its declared limit.
     *
     * <p>Assumptions: exactly one. The reference program increments its counter AFTER the get and then
     * compares with strict greater-than, so the request that makes the counter equal the limit is not the
     * last one: one further get is issued, and only the count past the limit ends the run. Naming the
     * offset as a constant rather than writing 501 anywhere keeps the declared limit and the admission
     * count as one decision -- an operator lowering the limit gets the same relationship, and a reader
     * sees why the two numbers differ instead of having to reconcile a 500 in configuration with a 501 in
     * behaviour.</p>
     */
    public static final int BASELINE_COMPARISON_OFFSET = 1;

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
     * The logger for this consumer, used for business conditions only and never for a failure.
     *
     * <p>Refactoring Rationale: every statement issued through this logger records a decision this
     * consumer MADE -- a request dropped past its deadline, a replay recognised, the admission window
     * filled, a decision reached, a reply refused for want of a usable destination. Not one of them
     * SUBSTITUTES for propagation, and that is the property being preserved rather than the absence of
     * warnings. Each statement either precedes a normal return, because the condition it names is the
     * outcome, or it precedes a throw and names the reason the request was refused -- so the
     * transaction still rolls back, the queue still redelivers, and the failure ITSELF is recorded once,
     * where the transport hands it to
     * {@code com.carddemo.authorization.config.SqsConfig.RecordAndPropagateErrorHandler}. Nothing here
     * is logged and then swallowed. The alternative was to log at each
     * failing site and continue, and the reference program is what makes
     * that alternative look sanctioned: it has FOURTEEN {@code PERFORM 9500-LOG-ERROR} sites, at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L282, L316, L429, L500, L512, L547,
     * L560, L595, L608, L639, L778, L846, L931 and L975. Reading those fourteen as fourteen loggers is
     * the misreading: all fourteen are calls, and every one resolves to a SINGLE emitting paragraph at
     * L983 to L1012 that composes one record and writes it once. Fourteen sites funnelling into one
     * emitter is the reference program's own centralization of diagnostics, so per-site logging here
     * would depart from it rather than follow it. The consequence of getting this backwards is concrete:
     * a site that logs a failure and returns has reported it and NOT rolled back, which is precisely the
     * durability difference registered as {@code D-D}.</p>
     *
     * <p>Assumptions: the fourteen is stated because it is easy to under-count. Three documents in this
     * migration enumerate ten and stop at L639, which is where the reading paragraphs end -- the reply
     * put at L778, the two segment writes at L846 and L931 and the queue close at L975 all emit as well.
     * Ten of the fourteen set the critical severity and four set the warning severity, which closes on
     * fourteen exactly; the arithmetic and the severity domain are recorded on
     * {@code AuthorizationMessageMapper.ErrorLogEntry}, which owns the record's shape.</p>
     *
     * <p>Assumptions: the place a propagated failure lands is the transport's error handler and NOT the
     * shared kernel's {@code GlobalExceptionHandler}. An earlier revision of this block named that
     * handler, and the claim was impossible rather than merely imprecise: it is a
     * {@code @RestControllerAdvice} bound to the web dispatcher, and a queue delivery never enters one,
     * so on this path it observes nothing. Naming the wrong destination had a concrete cost -- it made
     * an absent record look accounted for, and until the handler named above was published a request
     * that exhausted its redelivery allowance and dead-lettered left nothing behind at a level any
     * deployment collects.</p>
     *
     * <p>Assumptions: each statement is written as space-separated {@code key=value} pairs led by an
     * {@code event=} name, so the stream is parseable without a per-message format rule, and no
     * statement interpolates a primary account number, an account identifier or a customer identifier.
     * The reference program's own diagnostic puts the key of the item being processed into
     * {@code ERR-EVENT-KEY}, and for this consumer that item is an authorization, so the equivalent
     * value is a protected identifier; it is recorded as a keyed token through the mapper rather than
     * emitted here.</p>
     */
    private static final Logger LOG = LoggerFactory.getLogger(AuthorizationRequestListener.class);

    /**
     * The summary repository, whose accumulation statements do the arithmetic in the database.
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
     * <p>Assumptions: this collaborator is what makes the DECLARED contract the LIVE one. The decoder
     * establishes that a message splits into eighteen fields of admissible widths; it does not establish
     * that those fields satisfy the payload's own domains. Working straight from the decoded record would
     * leave every constraint the payload declares -- requiredness, the digits-only expressions on the two
     * numeric-picture fields, the amount domain -- asserted only by tests, with the live queue path
     * enforcing none of them.</p>
     */
    private final AuthorizationMessageMapper payloads;

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
     * The declared limit this window's admission allowance is derived from.
     */
    private final int requestProcessLimit;

    /**
     * How many requests one window ADMITS, which is the declared limit plus the baseline's off-by-one.
     *
     * <p>Assumptions: derived once at construction rather than recomputed per message, so the two numbers
     * cannot disagree between one admission and the next, and so a reader of the log line that reports a
     * closed window sees the same figure the reservation compared against.</p>
     */
    private final int windowAdmissionLimit;

    /**
     * The action that closes a full window and opens the next.
     */
    private final RequestWindowBoundary windowBoundary;

    /**
     * The current window: its generation, and how many admissions it has granted.
     *
     * <p>Refactoring Rationale: this replaces a plain slot counter advanced in the handler's
     * {@code finally} block, and both halves of the change matter. Counting on COMPLETION bounded the
     * wrong quantity: the container delivers messages on several threads and keeps polling while they
     * run, so nothing stopped it admitting far more than the quota before the quota-th one finished --
     * the bound only took effect once completions caught up, which under load they do not. Counting at
     * ADMISSION bounds what the container is allowed to hand out, which is the quantity the reference
     * program's test-before-next-get bounds. Carrying a GENERATION alongside the count is the other half:
     * the boundary is asynchronous by necessity -- a container cannot be stopped from the thread it is
     * delivering to -- so admissions can still arrive while the previous window is being closed, and the
     * generation is what attributes each of them to the window that is now open rather than letting a
     * late arrival be counted against a window that has already closed.</p>
     *
     * <p>Assumptions: the transition is ONE atomic update, so exactly one thread observes the admission
     * that fills a window and the boundary therefore fires exactly once per generation. A read, a
     * comparison and a separate write could not hold that: two threads could each observe the last slot,
     * each fire the boundary, and the container would be cycled twice for one window.</p>
     *
     * <p>Trade-offs: the window is per INSTANCE and therefore per task, not per queue. Two tasks each
     * admit up to the allowance before each closes its own window, so the platform-wide figure is the
     * allowance times the task count. That matches the reference system, where the limit bounded one
     * running program and the queue could trigger more than one, and a shared counter would need a
     * coordination round trip on the hot path of every authorization to achieve nothing the bound is
     * for.</p>
     */
    private final AtomicReference<WindowState> window =
            new AtomicReference<>(new WindowState(0L, 0, false));

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
     * @param replyQueueAllowlist the reply destinations this consumer may publish to; must not be empty
     * @param clock the clock staleness, timestamps and authorization keys are read from; must not be
     *     {@code null}
     * @param requestProcessLimit how many requests one window handles before intake is closed; must be
     *     positive
     * @param windowBoundary the action that closes a full window and opens the next; must not be
     *     {@code null}
     * @throws IllegalArgumentException if {@code requestProcessLimit} is not positive, a non-positive
     *     window admitting no request at all
     * @throws NullPointerException if {@code windowBoundary} is {@code null}, because a consumer with no
     *     window boundary would admit requests without ever closing a window
     */
    // WHY : Assumptions: this constructor takes NO keyed tokeniser. Both queue identities are
    // the literal values the technical specification freezes, taken from the reply itself, so
    // no collaborator derives them. Declaring a tokeniser parameter would keep a bean
    // qualifier and a startup dependency alive for a derivation nothing performs, and would
    // suggest to a reader that the identities are derived somewhere.
    public AuthorizationRequestListener(PendingAuthSummaryRepository summaries,
            PendingAuthDetailRepository details, OutboxRepository outbox,
            AuthorizationDecisionService decisions, AuthorizationMessageMapper payloads,
            AccountContextClient accounts,
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
        this.replyQueueAllowlist = List.copyOf(replyQueueAllowlist);
        this.clock = clock;
        this.requestProcessLimit = requestProcessLimit;
        // WHY : Assumptions: the allowance is the declared limit plus one, and the addition is made HERE
        //       rather than at the comparison so that the configured number and the enforced number are
        //       derived in one place. BASELINE_COMPARISON_OFFSET carries the reason they differ: the
        //       reference program increments after its get and compares with strict greater-than, so it
        //       issues one get past its declared limit before the loop ends.
        this.windowAdmissionLimit = requestProcessLimit + BASELINE_COMPARISON_OFFSET;
        this.windowBoundary = Objects.requireNonNull(windowBoundary, "windowBoundary must not be null");
    }

    /**
     * Handles one authorization request.
     *
     * <p>Assumptions: this is one iteration of {@code 2000-MAIN-PROCESS} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 323 to 347 -- the loop that
     * extracts a request, processes it, counts it and commits. The loop itself has no method here
     * because the listener container performs the iteration; what remains is the body, and the two
     * loop-control facts that body carries are the admission count at line 332, reserved by
     * {@link #reserveWindowSlot()}, and the per-message commit at line 335, expressed by the annotation
     * below. Line 337's reset of the resource-scheduling flag immediately after that commit is why no
     * field of this class carries per-message state.</p>
     *
     * <p>Assumptions: the MECHANISM that flag belongs to is retired while its consequence is kept.
     * {@code 1200-SCHEDULE-PSB} at lines 292 to 319 schedules the hierarchical database's access block,
     * and it is not dead code -- {@code 5000-PROCESS-AUTH} performs it exactly once, at line 443, so
     * every message re-establishes its own database access before touching a segment. Nothing in the
     * target schedules anything: a connection is drawn from the pool for the transaction this method
     * opens and returned when it closes. What survives is the property the pairing produced, that no
     * resource or accumulated value crosses from one message to the next, and that is why every field
     * of this class is final and every per-message value is a local or a parameter. The retirement is
     * recorded in section 5.1 of
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
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
     * {@link #requireDeclaredContract(AuthRequest)} argues.</p>
     *
     * <p>Assumptions: the two exceptions below are documented deliberately rather than by tooling. The
     * documentation gate's throws validation does not see a {@code throw} raised inside a try block that
     * has a catch clause, and this handler's guards are reached through one, so a missing at-clause here
     * would not fail the build. They are recorded because propagating them IS the mechanism this class
     * relies on -- each one rolls the transaction back and returns the message to the queue -- so a
     * reader who cannot see which exceptions leave cannot see how the rollback is reached.</p>
     *
     * @param message the received message, whose payload is the delimited request and whose headers
     *     carry the reply destination, expiry and correlation identifier; must not be {@code null}
     * @throws CsvAuthCodec.AuthMessageFormatException if the correlation identifier is not canonical, the
     *     reply destination is absent or not allowlisted, the declared wire format is not the expected
     *     one, the payload cannot be decoded, or a decoded field falls outside its record's own domain;
     *     the message is deliberately NOT swallowed, so it becomes visible again and reaches the
     *     dead-letter queue at the configured receive count instead of being deleted unanswered
     * @throws IllegalStateException if this decision's contribution reached no summary row, propagated
     *     from {@link #handleNewRequest(String, AuthRequest, String, LocalDateTime)}; the unit of work
     *     rolls back so that no reply row survives a decision the summary does not account for
     * @throws WindowClosedException if this instance's processing window has reached its allowance and its
     *     container has not finished cycling, propagated from {@link #reserveWindowSlot()}; the request is
     *     redelivered into the next window rather than handled inside a run that has already ended
     */
    // WHY : Assumptions: this method deliberately does NOT consult
    //       com.carddemo.common.control.OnlineWriteGate, even though it writes and even though this
    //       service holds the gate for its HTTP surface. The absence is recorded here because this is
    //       the one consumer in the migration that writes anything, so it is where a reader would
    //       reasonably look for the gate and conclude from silence that it had been forgotten.
    //       Assumptions: the reference bracket protected five VSAM files -- app/jcl/CLOSEFIL.jcl
    //       lines 26 to 30 close the transaction master, the cross-reference, the account master, the
    //       cross-reference alternate index and the security file -- and the authorization store is
    //       none of them. This method writes the authorization schema, which the posting chain does
    //       not touch, so gating it would add a quiesce the reference never had.
    //       Trade-offs: refusing a queued message is not a refusal but a deferral with a deadline.
    //       An unconsumed message becomes visible again after its timeout and is redelivered, and at
    //       the fifth receive it lands in the dead-letter queue -- so a closed window of any length
    //       would convert legitimate authorization traffic into a backlog an operator must redrive by
    //       hand, for a store the window was not protecting. Alternatives Considered: stopping this
    //       listener's container for the duration instead, which avoids the dead-letter consequence;
    //       rejected because it still adds a quiesce the reference does not have, and because a
    //       container that must be restarted afterwards has a failure mode a flag does not.
    @SqsListener(id = ContainerCyclingWindowBoundary.REQUEST_CONTAINER_ID,
            queueNames = "${carddemo.messaging.pauth-request-queue}",
            maxConcurrentMessages = "${carddemo.messaging.max-concurrent-messages:10}",
            maxMessagesPerPoll = "${carddemo.messaging.max-messages-per-poll:10}",
            pollTimeoutSeconds = "${carddemo.messaging.poll-timeout-seconds:5}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRequest(Message<String> message) {
        // WHY : Assumptions: the slot is reserved BEFORE anything else, including the correlation read, so
        //       every message the container hands over occupies one -- exactly as the reference program's
        //       counter advances on the get itself and not on the outcome. A message dropped as stale, one
        //       answered from a recorded decision, and one whose handling throws all consumed a get there
        //       and all consume an admission here.
        // WHY : Assumptions: it is also the one statement able to REFUSE this message, and it being first is
        //       what makes the refusal free of consequence. A window closed for its container cycle throws
        //       from here, before the correlation identifier is read, before any attribute is examined and
        //       before the transaction holds anything -- so the rollback withdraws nothing and the message
        //       returns to the queue exactly as it arrived.
        reserveWindowSlot();
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
            // WHY : Refactoring Rationale: the reply destination is established HERE, before the payload
            // is even decoded, and it used to be checked at the point the outbox row was written --
            // which is after the decision has been taken and its rows written. Both checks there
            // RETURNED NORMALLY, so the transaction committed, the acknowledgement mode acknowledged
            // the request, and a decision existed with no reply row: the exact state this module's
            // outbox exists to make impossible, reached by a requester supplying one bad attribute.
            // Raising here instead rolls the decision back and leaves the request on the queue to
            // redeliver and then dead-letter, which is the treatment every other permanent input fault
            // on this wire already receives.
            String replyQueueUrl = requireAllowlistedReplyDestination(message);
            AuthRequest request = requireDecodableRequest(message);
            Optional<PendingAuthDetail> alreadyDecided = existingDecision(request);
            if (alreadyDecided.isPresent()) {
                // WHY : Assumptions: the queue suppresses duplicates only inside its deduplication
                // window, so a redelivery after that window arrives as a new message. Re-publishing the
                // recorded answer keeps the requester served without incrementing the account's counters
                // a second time, which is what re-deciding would do.
                // WHY : ⚠️ Refactoring Rationale: this statement named the acquirer's TRANSACTION
                // IDENTIFIER and no longer does. That value is the key of the decision this service
                // recorded and of the ledger entry behind it, so a durable log line carrying it links
                // log access to a financial record -- and this is an INFO line, emitted on an ordinary
                // redelivery, so it is among the most frequently written lines this class has. What
                // discriminates the line instead is the CORRELATION IDENTIFIER, which is in the mapped
                // diagnostic context for the whole of this method -- set at the top of the enclosing
                // try and removed in its finally -- so the shared structured format renders it on this
                // line and on every other line of the same handling, including the reply enqueued on
                // the statement below. Alternatives Considered: a keyed opaque token over the
                // identifier, which this context can mint because it already holds a tokeniser bean.
                // Rejected because the constructor's own record states this class deliberately takes no
                // tokeniser, and because the correlation identifier already joins the line to its
                // request; the decision itself remains addressable in the governed table by the
                // identifier, which is where a query for it belongs.
                LOG.info("event=auth.request.replayed");
                enqueueReply(replyQueueUrl, replyFor(alreadyDecided.get()), correlationId, now);
                return;
            }
            handleNewRequest(replyQueueUrl, request, correlationId, now);
        } finally {
            MDC.remove(MDC_CORRELATION_ID);
        }
    }

    /**
     * Reserves this message's place in the current window, closing intake when the window fills.
     *
     * <p>Assumptions: this is called on ADMISSION -- as the first statement of the handler, before the
     * correlation identifier is even read -- so every message the container hands over occupies a place
     * whatever becomes of it. That matches the reference program, whose counter advances at
     * {@code cbl/COPAUA0C.cbl} line 332 immediately after the get returns and before any outcome is known:
     * a request it could not act on still consumed one. A message dropped as stale, one answered from a
     * recorded decision, and one whose handling throws each consume one here for the same reason.</p>
     *
     * <p>Alternatives Considered: taking the reservation on COMPLETION, in the handler's {@code
     * finally} block. Rejected because it bounds the wrong quantity: the container polls
     * continuously and delivers on several threads, so between the first admission and the quota-th
     * completion it can hand over an unbounded number of further messages, and the bound would only
     * take effect once completions caught up with admissions -- which under sustained load they do
     * not. Those extra completions would also land in the reset window and close it early.
     * Reserving on admission bounds what the container is permitted to hand out, which is the
     * quantity the reference program's test-before-next-get bounds, and a completion therefore
     * never touches the window.</p>
     *
     * <p>Assumptions: the update is one atomic transition on a value carrying both the generation and the
     * places granted, so exactly one thread can observe the admission that fills a window and the boundary
     * fires exactly once per generation. The transition also advances the generation and resets the count
     * in the same step, which is what makes an admission arriving while the container is being cycled
     * belong to the window now open rather than to the one just closed -- the boundary is asynchronous by
     * necessity, because a container cannot be stopped from a thread it is delivering to.</p>
     *
     * <p>Assumptions: the allowance bounds messages ADMITTED, not messages in flight at one instant. When
     * the boundary fires, up to the container's configured concurrency of messages may still be executing
     * -- each already holding its place -- and the boundary stops further INTAKE rather than interrupting
     * them. That is the reference program's discipline too: its counter bounds the gets it issues, and the
     * message it holds when the count is reached is processed to completion before the loop exits.</p>
     *
     * <p>Refactoring Rationale: the window now CLOSES when the allowance is reached and stays closed until
     * the container cycle reports it has finished, where the generation used to advance in the same atomic
     * step that fired the boundary. That step opened the next window while the container was still being
     * stopped, so every message the container had already dispatched -- up to its configured concurrency --
     * was admitted into the new generation and handled inside the physical run that was supposed to have
     * ended. One run could therefore exceed the allowance by that concurrency, and nothing in the accounting
     * or the log recorded that it had.</p>
     *
     * @throws WindowClosedException if the allowance has been reached and the container cycle has not yet
     *     finished, so this request must be redelivered into the next window rather than handled inside a
     *     run that has already ended; nothing has been done when it is thrown, because this is the handler's
     *     first statement
     */
    private void reserveWindowSlot() {
        WindowState before = this.window.getAndUpdate(state -> {
            if (state.closed()) {
                // WHY : Assumptions: a closed window grants NOTHING and the state is returned unchanged, so
                //       the count cannot creep past the allowance while the container is being cycled and
                //       the generation cannot advance from here. The reopen action is the only thing that
                //       moves a closed window on, which is what makes "one physical run admits at most the
                //       allowance" a property of this transition rather than of timing.
                return state;
            }
            int granted = state.granted() + 1;
            return new WindowState(state.generation(), granted,
                    granted >= this.windowAdmissionLimit);
        });
        if (before.closed()) {
            // WHY : Assumptions: the refusal is a THROW and not a silent return, because returning would
            //       let the acknowledgement mode delete a request nobody answered. Throwing before any work
            //       has been done rolls back a transaction that holds nothing, and the message becomes
            //       visible again after its timeout.
            //       Trade-offs: each refusal costs one receive against the queue's redrive count. That is
            //       accepted because the interval is one container stop-and-start and the population is at
            //       most the container's configured concurrency, so a message would have to be unlucky in
            //       five separate windows to dead-letter. Alternatives Considered: BLOCKING the thread until
            //       the cycle finished, which costs no receive at all; rejected because it deadlocks --
            //       stopping a container waits for its in-flight invocations and those invocations would be
            //       waiting for the stop.
            LOG.warn("event=auth.request.deferred reason=window-closing generation={}",
                    before.generation());
            throw new WindowClosedException(before.generation());
        }
        if (before.granted() + 1 >= this.windowAdmissionLimit) {
            // WHY : Assumptions: the figures reported are the CLOSING window's generation and the
            //       allowance itself, not a re-read of the state. The thread that took the last place is by
            //       construction the allowance-th admission of that generation, and a re-read could report
            //       the next window if the cycle had already completed.
            LOG.info("event=auth.window.filled generation={} admitted={}",
                    before.generation(), this.windowAdmissionLimit);
            this.windowBoundary.onWindowComplete(before.generation(), this.windowAdmissionLimit,
                    this::openNextWindow);
        }
    }

    /**
     * Opens the next window once the container cycle that closed this one has finished.
     *
     * <p>Purpose: this is the other half of the closing gate. Admission is closed from the instant the
     * allowance is reached, and this is the only thing that reopens it -- which is why the boundary contract
     * requires it to be run whether the cycle succeeded or failed. A window that is never reopened halts
     * every authorization this instance would handle.</p>
     *
     * <p>Assumptions: it is IDEMPOTENT with respect to the count and not with respect to the generation. Two
     * runs would open two windows rather than corrupting one, so a boundary implementation that ran it twice
     * costs one skipped generation number and nothing else; the alternative -- refusing a second run -- would
     * need the closing generation threaded back through the callback to tell a duplicate from the next
     * closure, for a fault that no implementation in this repository can produce.</p>
     *
     * <p>Assumptions: the generation advances HERE rather than at the close, so a generation number counts
     * completed cycles. That is what makes the closing log line and the opening log line a matched pair a
     * reader can align, and it is why {@code event=auth.window.filled} and {@code event=auth.window.reopened}
     * carry the same number.</p>
     */
    private void openNextWindow() {
        WindowState opened = this.window.updateAndGet(
                state -> new WindowState(state.generation() + 1, 0, false));
        LOG.info("event=auth.window.reopened generation={}", opened.generation());
    }

    /**
     * Reports that a request arrived while the processing window was closed for its container cycle.
     *
     * <p>Purpose: it is thrown so the request is REDELIVERED rather than handled inside a physical run that
     * has already reached its allowance, and so the outcome is distinguishable in a log and in a test from
     * every other refusal on this path. A shared exception type would make a deferral read as a fault.</p>
     *
     * <p>Assumptions: it carries the generation and NOT the request. The message has not been decoded when
     * this is thrown -- the admission gate is the handler's first statement -- so there is nothing about the
     * request to carry, and the generation is what ties the deferral to the window that caused it.</p>
     */
    public static final class WindowClosedException extends RuntimeException {

        /** Serialisation identity, fixed because the type crosses no serialisation boundary. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the deferral.
         *
         * @param generation the window that was closing when the request arrived
         */
        WindowClosedException(long generation) {
            super("the request processing window " + generation + " has reached its admission allowance"
                    + " and its container is being cycled; this request is deferred to the next window"
                    + " rather than handled inside a run that has already ended");
        }
    }

    /**
     * Refuses a request that does not declare the one payload format this consumer decodes.
     *
     * <p>Purpose: the decoder splits a delimited record into eighteen fields by position, so it will read
     * ANY text that happens to have the right number of separators. A producer sending a differently
     * shaped record -- another delimited format, or a structured document -- therefore does not fail to
     * decode; it decodes into fields that are in the wrong places, and the consumer takes an authorization
     * decision on them. The declared format is the only thing that distinguishes the two cases before the
     * decode, which is why it is checked here and not after.</p>
     *
     * <p>Refactoring Rationale: the attribute was requested from the transport and then never read. The
     * container asks for every message attribute -- {@code SqsConfig} states why -- and the reply path
     * SETS this attribute on everything it publishes, so the contract was declared in one direction and
     * enforced in neither. The baseline does not have that gap: its broker refuses a format the program
     * did not ask for before the program sees the message.</p>
     *
     * <p>Assumptions: the comparison ignores case and surrounding blanks, because a media type's type and
     * subtype are defined to be case-insensitive and a producer's header library may pad. It does NOT
     * accept a parameterised form such as a trailing character-set: this payload is a fixed-position
     * record of digits and blanks whose encoding is settled by the transport, so a character-set parameter
     * would describe a variation this consumer does not implement, and accepting the header while ignoring
     * the parameter would be the silent kind of tolerance.</p>
     *
     * <p>Assumptions: an ABSENT value is refused as well as a wrong one. Treating absence as consent was
     * the alternative and is rejected on this flow for the reason the expiry guard states: this consumer
     * commits a decision and moves an account's counters, so a producer that has not said what it sent is
     * not a producer to guess for. Every producer in this repository sets the attribute.</p>
     *
     * @param message the received message; must not be {@code null}
     * @throws CsvAuthCodec.AuthMessageFormatException if the attribute is absent, blank or names any
     *     format other than {@link #CONTENT_TYPE_CSV}; the value is quoted only after sanitising, because
     *     it came off the wire
     */
    private void requireDeclaredWireFormat(Message<String> message) {
        String declared = header(message, HEADER_CONTENT_TYPE);
        if (declared == null || declared.isBlank()) {
            throw new CsvAuthCodec.AuthMessageFormatException("the request declares no "
                    + HEADER_CONTENT_TYPE + " attribute; this consumer decodes " + CONTENT_TYPE_CSV
                    + " only, and a positional record read under an undeclared format decodes into"
                    + " fields that may not be the fields the producer sent");
        }
        if (!CONTENT_TYPE_CSV.equalsIgnoreCase(declared.trim())) {
            throw new CsvAuthCodec.AuthMessageFormatException("the request declares "
                    + HEADER_CONTENT_TYPE + " " + LogSafeText.sanitize(declared) + "; this consumer"
                    + " decodes " + CONTENT_TYPE_CSV + " only");
        }
    }

    /**
     * Decodes one request, recording a named refusal before letting a wire-format fault propagate.
     *
     * <p><strong>Purpose.</strong> Wraps the three steps that turn a delivered payload into a request this
     * consumer can act on -- the declared-format check, the positional decode, and the published-contract
     * check -- so that all three report a violation the same way.
     *
     * <p>⚠️ Refactoring Rationale: this method exists because a malformed payload was the one permanent
     * input fault on this wire that reached an operator as nothing but a frame chain. The sibling faults
     * all name themselves: an absent reply destination logs {@code reason=no-reply-destination}, a
     * destination outside the allowlist logs {@code reason=destination-not-allowlisted} with the
     * allowlist's size, an unparseable expiry logs {@code reason=expiry-unparseable} with the value's
     * length. A wire-format fault logged nothing at all, so the only record was the container's own
     * failure line naming a codec frame -- which tells a reader that the codec refused something and not
     * WHAT it refused, on the fault whose cause is most often a producer that changed a field width.
     *
     * <p>⚠️ Assumptions: the refusal's own message is safe to log through the plain summary rendering
     * rather than the digit-redacting one, and the reason is a property of the codec rather than a
     * judgement about this line. {@code CsvAuthCodec} composes every field diagnostic through a single
     * gate that appends the offending VALUE only when the field is not in its sensitive set, and treats an
     * unidentified field as sensitive -- so the card number, the expiry, the amount, the merchant fields
     * and the transaction identifier are all withheld at the source. What reaches this line is the
     * copybook field name and the constraint it breached, which is exactly what a producer needs to be
     * told.
     *
     * <p>⚠️ Trade-offs: the fault is logged and then RETHROWN unchanged, so the delivery outcome is
     * untouched -- the message is not acknowledged, it redelivers, and it dead-letters at the fifth
     * receive, which is the correct treatment for a payload no retry can fix and the treatment every other
     * permanent input fault here already gets. The cost is that one fault produces two lines: this named
     * one and the container's own. Swallowing it to avoid that would delete the message under the
     * acknowledgement mode this module pins, which would lose the only copy of a payload an operator needs
     * to read from the dead-letter queue.
     *
     * @param message the received message whose payload is to be decoded; must not be {@code null}
     * @return the decoded request, never {@code null}
     * @throws CsvAuthCodec.AuthMessageFormatException if the format attribute is absent or names another
     *     format, if the payload does not decode against the published positional record, or if the
     *     decoded request violates the published contract
     */
    private AuthRequest requireDecodableRequest(Message<String> message) {
        try {
            requireDeclaredWireFormat(message);
            AuthRequest request = CsvAuthCodec.decodeRequest(message.getPayload());
            requireDeclaredContract(request);
            return request;
        } catch (CsvAuthCodec.AuthMessageFormatException malformed) {
            LOG.warn("event=auth.request.refused reason=wire-format-invalid detail={}",
                    FailureSummary.of(malformed));
            throw malformed;
        }
    }

    /**
     * One bounded processing window: which window it is, and how many admissions it has granted.
     *
     * <p>Assumptions: the generation is a {@code long} and only ever increases, so it cannot return to a
     * value a concurrent reader might still be holding. An {@code int} would wrap after roughly two
     * billion windows, which at this allowance is not reachable in practice -- the type is chosen because
     * a monotonic identity that provably never repeats needs no argument about reachability.</p>
     *
     * <p>Refactoring Rationale: the CLOSED flag is new, and the generation used to advance in the same
     * atomic step that fired the boundary. That opened the next window while the container was still being
     * stopped, so every message the container had already dispatched -- up to its configured concurrency --
     * was admitted into the new generation and handled inside the physical run that was supposed to have
     * ended. One run could therefore exceed the allowance by that concurrency, silently. The flag holds the
     * window closed for the whole of the cycle instead, and the generation advances only when the cycle
     * reports it has finished.</p>
     *
     * @param generation which window this is, counting from zero and increasing by one each time a window
     *     fills and its container has been cycled; monotonic
     * @param granted how many admissions this window has granted so far, at most the admission allowance
     * @param closed whether the allowance has been reached and the container cycle has not yet finished,
     *     during which no admission is granted at all
     */
    private record WindowState(long generation, int granted, boolean closed) {
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
     * <p>Assumptions: this method takes the ALREADY-VALIDATED reply destination rather than the message
     * it came from, so the decision path holds no means of re-reading a requester-supplied attribute and
     * no second opportunity to reach a different verdict about it. The destination was established before
     * this method was called, which is what makes the write below and the reply that answers it either
     * both happen or neither.</p>
     *
     * @param replyQueueUrl the allowlisted destination this decision's reply will be sent to; must not be
     *     {@code null}
     * @param request the decoded request; must not be {@code null}
     * @param correlationId the requester's correlation identifier; may be {@code null}
     * @param now the current instant in coordinated universal time; must not be {@code null}
     * @throws IllegalStateException if this decision's contribution reached no summary row, propagated
     *     from {@link #contribute(AccountContextClient.CardXref, Optional, Optional, AuthRequest,
     *     AuthorizationDecisionService.Decision, AuthorizationDecisionService.DecisionContext)}; it is
     *     documented here rather
     *     than left to the caller to discover because it is the one way this method can leave without
     *     having enqueued a reply, and the transaction rolling back is what keeps that from being a
     *     decision no answer accounts for
     */
    private void handleNewRequest(String replyQueueUrl, AuthRequest request, String correlationId,
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
            // WHY : Refactoring Rationale: this read used to hold a PESSIMISTIC_WRITE lock on the summary
            //       row for the rest of the transaction. It no longer holds anything. The lock was a
            //       target-side addition -- the reference system passes only non-hold retrieval codes -- and
            //       what it protected, the accumulation of four incremented members, is now performed by
            //       atomic statements in contribute(...). Trade-offs: the decision below is therefore made
            //       against counters a concurrent contribution may already have moved. That is closer to the
            //       reference behaviour rather than further from it: the reference reads its root without a
            //       hold and decides on what it read.
            //       Assumptions: reading without a hold is safe for the DECISION because the decision is not
            //       final here. The approval this read leads to is applied by a statement qualified on the
            //       same credit check, so a headroom this read saw and a concurrent authorization then spent
            //       supersedes the approval at the write rather than committing an over-limit balance. The
            //       stale read is therefore tolerated by design and not merely accepted.
            summary = this.summaries.findByAccountId(accountId);
        }
        AuthorizationDecisionService.DecisionContext context =
                new AuthorizationDecisionService.DecisionContext(xref.isPresent(), account,
                        customerFound, summary);
        AuthorizationDecisionService.Decision proposed = this.decisions.decide(request, context);

        // WHY : Refactoring Rationale: the summary contribution now runs BEFORE the reply is built, and the
        //       decision the reply carries is the one the contribution CONFIRMED rather than the one the
        //       decision service proposed. The two used to be the same value and could not be: the credit
        //       check above is measured against the summary as this transaction read it, and a queue grouped
        //       on card number delivers two requests for two DIFFERENT cards of one account concurrently, so
        //       both measure against the same headroom, both propose an approval, and both contributions then
        //       land atomically -- leaving the account's credit balance above its own credit limit with
        //       nothing in the row to record that a limit was breached. The reference cannot reach that state
        //       because it decides one message at a time.
        // WHY : Assumptions: the reservation is what decides, so the reply cannot be built until it has run.
        //       The approval is applied by a statement qualified on the same credit check, evaluated by the
        //       engine against the row as it stands; when the qualification fails the proposal is superseded
        //       by the decline the account can carry. Building the reply first -- which is what the ordering
        //       below used to do -- would have answered the requester with an approval the store refused.
        AuthorizationDecisionService.Decision decision = proposed;
        if (xref.isPresent()) {
            decision = contribute(xref.get(), account, summary, request, proposed, context);
        }

        // WHY : Refactoring Rationale: the reply wire record is built before the DETAIL ROW, where it used to
        //       be built after it. The detail row and the reply carry the same five decision values -- the
        //       identification code, the response code, the response reason, the approved amount and the card
        //       and transaction identity -- and building the reply first lets the row be PROJECTED from it
        //       rather than assembled a second time from the decision. That is what keeps the persisted state
        //       and the answer sent to the requester two renderings of one decision instead of two
        //       independent ones that could drift.
        // WHY : Alternatives Considered: leaving the reply where it was and passing the decision into the
        //       write. Rejected because the projection the mapper publishes takes the reply, and it is
        //       that projection which refuses a reply naming a different card or transaction than the
        //       request; assembling the row from the decision bypasses the refusal entirely, so the one
        //       check that cannot be satisfied by construction would never run on the path that persists.
        AuthReply reply = new AuthReply(request.cardNum(), request.transactionId(),
                this.decisions.identificationCodeFor(request), decision.responseCode(),
                decision.responseReason(), decision.approvedAmount());

        if (xref.isPresent()) {
            this.details.save(record(xref.get().accountId(), request, decision, reply, now));
        } else {
            // WHY : Assumptions: with no cross-reference row there is no account to hang a summary or a
            // detail row from, so the decline is answered without being recorded. That is the baseline's
            // own behaviour: its line 463 performs the database writes only when the card resolved, so an
            // unknown card leaves no trace in either segment there either.
            LOG.warn("event=auth.request.declined reason=card-not-cross-referenced respReason={}",
                    decision.responseReason());
        }
        enqueueReply(replyQueueUrl, reply, correlationId, now);
        // WHY : Assumptions: the log records whether the CONFIRMED decision was an approval and, separately,
        //       whether a proposal was superseded by the reservation. The second dimension is what makes a
        //       contended account visible in operation: without it a superseded approval is indistinguishable
        //       from a request that never fitted, and the two have different causes.
        LOG.info("event=auth.request.decided approved={} respCode={} respReason={} superseded={}",
                decision.approved(), decision.responseCode(), decision.responseReason(),
                proposed.approved() && !decision.approved());
    }

    /**
     * Applies the proposed decision to the account's summary and reports the decision the store confirmed.
     *
     * <p>Purpose: this is the write half of {@code 8400-UPDATE-SUMMARY}, and it is also where an APPROVAL
     * becomes final. The credit check the decision service made was measured against the summary as this
     * transaction read it, and the approval is applied by a statement qualified on that same check -- so this
     * method returns the proposal when the store admitted it and a decline when it did not. The caller builds
     * the reply from what this returns, which is why the summary contribution precedes the reply rather than
     * following it.</p>
     *
     * <p>Assumptions: the summary is written BEFORE the detail row, matching {@code 8000-WRITE-AUTH-TO-DB}
     * at lines 790 and 791. Within one transaction the order is not observable, but keeping it means the
     * two paragraphs and the two statements here can be read side by side. The detail row is written by the
     * caller, after the reply it is projected from has been built from this method's answer.</p>
     *
     * <p>Assumptions: a missing summary is CREATED rather than skipped, because the baseline inserts the
     * root segment on the first authorization for an account, at lines 801 to 806 and 830 to 834. Skipping
     * the create -- the only option open to a caller with no account identifier to create it with
     * -- would lose the account's approved and declined counters entirely until an authorization
     * happened to find a row the extract load had provided.</p>
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
     * @param summary the summary as this transaction read it, empty when the account had none; must not
     *     be {@code null}
     * @param request the decoded request; must not be {@code null}
     * @param proposed the decision the decision service reached from what this transaction read; must not be
     *     {@code null}
     * @param context the same lookup outcomes that proposal was reached from, needed to select the reason a
     *     superseded approval reports; must not be {@code null}
     * @return the decision the store confirmed -- the proposal itself, or the decline that supersedes an
     *     approval the account's limit no longer admits; never {@code null}
     * @throws IllegalStateException if a statement applying this decision's contribution reached no
     *     summary row, propagated from {@link #contributeToStoredSummary(long, Optional, AuthRequest,
     *     AuthorizationDecisionService.Decision, AuthorizationDecisionService.DecisionContext)}; the
     *     message's whole unit of work rolls back rather than committing a decision whose contribution
     *     reached no summary
     */
    private AuthorizationDecisionService.Decision contribute(AccountContextClient.CardXref xref,
            Optional<AccountContextClient.Account> account, Optional<PendingAuthSummary> summary,
            AuthRequest request, AuthorizationDecisionService.Decision proposed,
            AuthorizationDecisionService.DecisionContext context) {
        // WHY : Assumptions: the accumulation is applied by an ATOMIC statement rather than
        // by reading an entity, mutating it and saving it back. These members are INCREMENTED
        // and not assigned (cbl/COPAUA0C.cbl L814/L815 approved, L820/L821 declined), so two
        // interleaved read-modify-write sequences would lose a contribution. Alternatives
        // Considered: taking a PESSIMISTIC_WRITE lock over such a sequence. Rejected as
        // concurrency machinery the reference system does not have -- cpy/IMSFUNCS.cpy
        // declares three get-hold function codes at L19, L21 and L23 and no reference program
        // passes any of them. Performing the arithmetic in the database removes the read-
        // modify-write entirely, so there is no lost update to lock against: two concurrent
        // contributions each add to whatever the row holds when their statement runs, and
        // both land.
        // WHY : Refactoring Rationale: making both contributions land was NOT the whole remedy, and the
        //       paragraph above used to stop there. Two concurrent requests on two different cards of one
        //       account read the same headroom, both approve, and both contributions then land -- which is a
        //       correct accumulation of an incorrect pair of decisions, and the credit balance ends above the
        //       credit limit. The approval statement is therefore QUALIFIED on the same credit check the
        //       decision made, and this method now RETURNS the decision the store confirmed so the caller
        //       answers the requester with it. Nothing here holds a row, so the reference's absence of any
        //       hold is preserved.
        long accountId = xref.accountId();
        if (summary.isPresent()) {
            return contributeToStoredSummary(accountId, account, summary, request, proposed, context);
        }
        if (insertFirstSummary(accountId, xref, account, request, proposed) == 1) {
            // WHY : Assumptions: the INSERT arm needs no credit guard and is the one approval path that
            //       cannot double-count. It runs only when this transaction found no summary at all, the
            //       statement inserts the row rather than updating one, and exactly one of two concurrent
            //       inserts can succeed -- the other is reported a conflict and falls through below, where
            //       the guarded contribution measures it against the row the winner committed. The seeded
            //       row already carries this decision's own contribution, so the proposal stands as made.
            return proposed;
        }
        {
            // WHY : Refactoring Rationale: a reported conflict now falls through to the ADDITIVE arm,
            //       where the create arm used to end with the inherited save and nothing else. That save
            //       was a merge rather than an insert -- the account identifier is an assigned key, so the
            //       provider issued a select and then an update -- and a row committed by another party
            //       between this transaction's empty read and this write was therefore OVERWRITTEN in
            //       every column, discarding that party's counters, totals and held balance without a
            //       failure anywhere. Re-reading and contributing additively is what makes the two
            //       outcomes of the race the same outcome: both contributions land.
            //       Assumptions: the additive arm succeeds because the insert reported a conflict, and a
            //       conflict is reported only once the conflicting row is committed -- an in-flight
            //       insert makes this statement wait rather than reporting anything -- so the row is
            //       visible to the next statement's snapshot under this deployment's read-committed
            //       isolation.
            // WHY : Refactoring Rationale: the fall-through no longer RE-READS the row it is about to
            //       contribute to. That read existed only to hand a managed instance to the method below,
            //       and the method no longer takes one: every write it issues is a statement addressed by
            //       account identifier, so a loaded instance would be an unused object whose presence
            //       invited exactly the whole-row flush the statements exist to avoid. Its assertion is not
            //       lost -- each statement's row count is checked, and a zero raises the same refusal.
            // WHY : Assumptions: the summary is passed EMPTY here and the counter report below therefore
            //       does not fire, which is correct rather than a gap. This arm is reached only when this
            //       transaction found no summary and its insert was refused as a conflict, so the row it
            //       is about to contribute to was created by another party within this window and carries
            //       a counter of one -- four orders of magnitude from the bound the report exists to
            //       announce.
            return contributeToStoredSummary(accountId, account, Optional.empty(), request, proposed,
                    context);
        }
    }

    /**
     * Creates the summary an account's first authorization finds, without displacing one that appeared.
     *
     * <p>Assumptions: this is {@code 8400-UPDATE-SUMMARY}'s insert arm at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 801 to 806, which initialises the
     * segment and moves in the account and customer identifiers, together with the arm at its lines 829
     * to 834 that inserts rather than replaces. The seeded row carries this decision's own contribution
     * because the reference program applies the same accumulation at its lines 813 to 821 before
     * choosing between its two write arms, so a created segment is never left at zero.</p>
     *
     * <p>Refactoring Rationale: the write is the duplicate-tolerant insert and not the inherited save,
     * and the reason is a measured outcome rather than a precaution. The save was a merge on an assigned
     * key, so a row another party committed after this transaction's empty read was replaced column for
     * column; an insert that does nothing on conflict cannot replace anything, and it REPORTS the
     * conflict so the caller can contribute additively instead. Alternatives Considered: taking a row
     * lock over the account before the read, which is the other way to close the same window. Rejected
     * because the reference system passes no get-hold function code anywhere, and because the lock would
     * have to be held over the account context calls that follow the read -- serialising every
     * authorization for an account behind the slowest lookup in the path.</p>
     *
     * <p>Assumptions: the conflict this tolerates is genuinely reachable, and the ordering guarantee
     * does not exclude it. The request queue orders by MESSAGE GROUP and the group is the CARD NUMBER,
     * not the account -- {@link #enqueueReply(String, AuthReply, String, LocalDateTime)} records why the
     * two queue identities are the literal card and transaction values -- so two cards belonging to one
     * account are two groups and are delivered in parallel. Two first-ever authorizations for one
     * account are therefore an ordinary concurrent case rather than one the transport rules out.</p>
     *
     * @param accountId the account the summary belongs to; never {@code null} in effect, being the
     *     cross-reference row's own account
     * @param xref the resolved cross-reference row, which carries the customer the segment records;
     *     must not be {@code null}
     * @param account the account master record, empty when it was not found; must not be {@code null}
     * @param request the decoded request, whose amount a decline accumulates; must not be {@code null}
     * @param decision the decision reached; must not be {@code null}
     * @return {@code 1} when this call created the summary, {@code 0} when the account already had one
     */
    private int insertFirstSummary(long accountId, AccountContextClient.CardXref xref,
            Optional<AccountContextClient.Account> account, AuthRequest request,
            AuthorizationDecisionService.Decision decision) {
        PendingAuthSummary created = new PendingAuthSummary(accountId, xref.customerId());
        // WHY : ⚠️ Refactoring Rationale: every value entering the seeded row passes through the summary's
        //       own money domain first, where they were assigned as they arrived. Three of the four are
        //       WIDER at source than this segment stores them: the account master's two limits are
        //       PIC S9(10)V99 at cpy/CVACT01Y.cpy L8 and L9 and the requested amount is PIC S9(10)V99 at
        //       cpy/CIPAUDTY.cpy L34, against this segment's PIC S9(09)V99 at cpy/CIPAUSMY.cpy L23 to L26.
        //       An out-of-domain value reached the insert and the database refused the whole statement, so
        //       the message's unit of work rolled back, the requester received no decision at all, and the
        //       request dead-lettered after five receives -- an account was simply unable to transact. The
        //       domain type saturates rather than truncating, and passing each value through the reporting
        //       helper is what makes the reduction visible rather than silent.
        //       Assumptions: reducing here cannot change the stored result, because the entity's own setters
        //       reduce again after their arithmetic; what it changes is that the FIELD is named in the log.
        account.ifPresent(read -> created.refreshLimits(
                narrowedToSummaryDomain(read.creditLimit(), "creditLimit"),
                narrowedToSummaryDomain(read.cashCreditLimit(), "cashCreditLimit")));
        if (decision.approved()) {
            created.recordApproved(narrowedToSummaryDomain(decision.approvedAmount().amount(),
                    "approvedAuthAmount"));
        } else {
            created.recordDeclined(narrowedToSummaryDomain(request.transactionAmount().amount(),
                    "declinedAuthAmount"));
        }
        // WHY : Assumptions: the seeded object is a PARAMETER SOURCE and never becomes managed, because
        //       the statement binds its sixteen components and inserts them itself. That is what keeps a
        //       reported conflict free of consequences: nothing is left in the persistence context to be
        //       written again at commit, so the fall-through is free to contribute through the stored row.
        return this.summaries.insertSummaryIfAbsent(created);
    }

    /**
     * Applies one decision's contribution to a summary the store already holds, and confirms it.
     *
     * <p>Assumptions: this is {@code 8400-UPDATE-SUMMARY}'s replace arm at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 824 to 828, reached when that
     * paragraph's own read found the root. The two halves of the write are deliberately taken by
     * different means: the limits are ASSIGNED from the account read, so a later write simply wins and
     * there is nothing for an atomic statement to protect, while the counters, the totals and the held
     * balance are INCREMENTED at its lines 813 to 821 and are the only members with a lost-update
     * exposure.</p>
     *
     * <p>⚠️ Refactoring Rationale: BOTH halves are now statements, and NEITHER touches a loaded
     * instance. This method used to assign the two limits onto the managed summary and call the
     * inherited {@code save}, and that combination lost concurrent contributions. The mapping declares
     * no version member and no dynamic-update marker, so the pending assignment flushed as a
     * whole-row update carrying every column from the instance's load-time snapshot -- the four
     * accumulators included -- and the flush was triggered by the additive query on the very next line,
     * because the provider flushes before running a bulk operation. A contribution another transaction
     * had committed after this transaction's read was therefore overwritten with the older counters,
     * and this decision's own contribution was then added on top of them, so the other party's
     * authorization disappeared from the account's totals with nothing reporting it. Two disjoint
     * statements cannot do that: the refresh writes only the two limits and the additive query writes
     * only the accumulators, so whichever order the engine serialises them in, neither carries a stale
     * value for a column the other owns.</p>
     *
     * <p>Assumptions: the exposure this closes is reachable rather than theoretical, and the transport
     * does not rule it out. The request queue orders by MESSAGE GROUP and the group is the CARD NUMBER
     * -- {@link #enqueueReply(String, AuthReply, String, LocalDateTime)} records why the two queue
     * identities are the literal card and transaction values -- so two cards belonging to ONE account
     * are two groups and are delivered in parallel. Two authorizations for one account are an ordinary
     * concurrent case.</p>
     *
     * <p>⚠️ Assumptions: the summary instance this transaction read earlier IS passed in, and it is read
     * for exactly one purpose -- reporting a counter that this contribution will leave resting on its
     * four-digit bound -- and is neither mutated nor refreshed. Refactoring Rationale: it used to be
     * withheld, and this paragraph said so, because nothing downstream needed it; the counter clamp the
     * three contribution statements now apply changed that, since a clamp performed inside a statement
     * cannot report itself and the pre-state is the only place the condition is visible without a second
     * query. The reason it was withheld still holds and still governs: no value is assigned to the
     * instance, so there is no dirty state for the commit to flush, and no value is READ BACK from it after
     * a statement runs -- a statement's effect is invisible to an instance the persistence context already
     * holds, so reading one afterwards would report values the row no longer carries.</p>
     *
     * @param accountId the account whose summary receives the contribution
     * @param account the account master record, empty when it was not found; must not be {@code null}
     * @param summary the summary this transaction read, empty on the arm reached after an insert conflict;
     *     read ONLY to report a counter about to rest on its four-digit bound, never written; must not be
     *     {@code null}
     * @param request the decoded request, whose amount a decline accumulates; must not be {@code null}
     * @param proposed the decision the decision service reached; must not be {@code null}
     * @param context the lookup outcomes that proposal was reached from, needed to select the reason a
     *     superseded approval reports; must not be {@code null}
     * @return the decision the store confirmed -- the proposal itself, or the decline that supersedes an
     *     approval whose reservation the account's own limit refused; never {@code null}
     * @throws IllegalStateException if the DECLINE statement reports that it changed no row, which is the
     *     condition the repository documents as "no summary for this account"; the message's unit of
     *     work rolls back rather than committing a decision whose contribution reached no summary
     */
    private AuthorizationDecisionService.Decision contributeToStoredSummary(long accountId,
            Optional<AccountContextClient.Account> account, Optional<PendingAuthSummary> summary,
            AuthRequest request, AuthorizationDecisionService.Decision proposed,
            AuthorizationDecisionService.DecisionContext context) {
        // WHY : Assumptions: the refresh is conditional on the account master having been READ, which is
        //       the divergence persist(...) records as D-SUMMARY-LIMIT-REFRESH. Its row count is checked
        //       on the same terms as the additive statement's below, because a refresh that reached no
        //       row means the summary this decision was decided against is gone, and continuing would
        //       add a contribution to a row that no longer exists.
        if (account.isPresent()) {
            AccountContextClient.Account read = account.get();
            // WHY : ⚠️ Refactoring Rationale: both limits are reduced to the summary's own domain before the
            //       statement runs, where they were passed through as the account context reported them. The
            //       account master's limit is PIC S9(10)V99 and this segment's is PIC S9(09)V99, so a limit
            //       the account context can legally hold overflowed the column -- and the failure was total:
            //       the update rolled the whole decision back, so an account with a large limit could not
            //       process ANY authorization, not even a small one, and the requester received nothing at
            //       all. The reduction is reported by field below so a saturated limit is traceable.
            requireSummaryChanged(this.summaries.refreshStoredLimits(accountId,
                    narrowedToSummaryDomain(read.creditLimit(), "creditLimit"),
                    narrowedToSummaryDomain(read.cashCreditLimit(), "cashCreditLimit")));
        }

        AuthorizationDecisionService.Decision confirmed = proposed;
        if (proposed.approved()) {
            // WHY : Assumptions: the counter's saturation is detected from the PRE-STATE this transaction
            //       already read, so no extra query is issued for a condition that is normally absent. The
            //       statement below clamps the counter atomically -- see the repository's own rationale and
            //       the registered divergence D-SUMMARY-COUNTER-SATURATION -- and clamping cannot report
            //       itself, because a modifying query returns a row count and not which assignment it
            //       bounded.
            reportCounterNarrowing(summary, PendingAuthSummary::getApprovedAuthCount,
                    "approvedAuthCount", 1);
        }
        if (proposed.approved()
                && this.summaries.reserveApprovedAuthorization(accountId,
                        proposed.approvedAmount().amount(),
                        PendingAuthSummary.MONEY_MAX_MAGNITUDE,
                        PendingAuthSummary.COUNTER_MAX) == 0) {
            // WHY : Refactoring Rationale: an approval is applied by a statement QUALIFIED on the same credit
            //       check the decision made, and a zero row count SUPERSEDES the approval rather than being
            //       treated as a missing row. The unguarded statement that stood here made the accumulation
            //       safe and left the DECISION unsafe: a queue grouped on card number delivers two requests
            //       for two different cards of one account concurrently, so both read the same headroom, both
            //       approved, and both contributions then landed -- leaving a credit balance above the credit
            //       limit that no reference program can produce, because the reference decides one message at
            //       a time.
            //       Assumptions: the row EXISTS whenever this branch is reached, so a zero can only mean the
            //       headroom went. The caller enters this method either with a summary it read in this
            //       transaction or with one it re-read after an insert reported a conflict, and a conflict is
            //       reported only once the conflicting row is committed. That is what lets the two outcomes
            //       the repository cannot distinguish be distinguished here.
            //       Alternatives Considered: raising and letting the message redeliver, which is what every
            //       other fault on this path does. Rejected because this is not a fault: the account's limit
            //       genuinely no longer accommodates the request, so a redelivery would re-decide it and
            //       reach the same refusal, and after five receives would dead-letter a request the reference
            //       system answers with an ordinary decline.
            confirmed = this.decisions.declineForConsumedHeadroom(context);
        }
        if (!confirmed.approved()) {
            // WHY : Refactoring Rationale: the amount added here is THIS request's, whereas the baseline
            // adds PA-TRANSACTION-AMT at its line 821 -- a detail-segment field its line 885 does not
            // populate until the following paragraph, so the total it accumulates is the previous
            // message's amount, or zero for the first message of a task. A running total of declined
            // amounts that is off by one message describes nothing, so the current request's amount is
            // used and the divergence is registered as D-DECLINED-AMT-CURRENT in
            // docs/architecture/cobol-to-service-traceability.md.
            // WHY : Assumptions: a SUPERSEDED approval accumulates here as an ordinary decline, because that
            // is what it now is. The requested amount is added to the declined total and the declined count
            // advances, which is exactly the row the reference would hold had it decided the two requests in
            // sequence -- the second reads the first's contribution and declines for want of funds.
            // WHY : Assumptions: the declined counter is the one that reaches the bound first, because an
            //       account whose limit is exhausted declines every further request and advances only this
            //       member; the report is therefore worth making on the path that is expected to trip it.
            reportCounterNarrowing(summary, PendingAuthSummary::getDeclinedAuthCount,
                    "declinedAuthCount", 1);
            requireSummaryChanged(this.summaries.addDeclinedAuthorization(accountId,
                    request.transactionAmount().amount(),
                    PendingAuthSummary.MONEY_MAX_MAGNITUDE,
                    PendingAuthSummary.COUNTER_MAX));
        }
        return confirmed;
    }

    /**
     * Reduces one value to the summary segment's own money domain, reporting the reduction.
     *
     * <p>Purpose: the values this segment accumulates arrive from WIDER fields. The account master's two
     * limits are {@code PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} L8 and L9 and the requested and approved
     * amounts are {@code PIC S9(10)V99} at
     * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} L34 and L35, while every money member of
     * this segment is {@code PIC S9(09)V99} at {@code cpy/CIPAUSMY.cpy} L23 to L26 and L29 to L30 -- one
     * decimal order narrower. The reference program performs the same narrowing implicitly, with a plain
     * {@code MOVE} at {@code cbl/COPAUA0C.cbl} L810 and L811 and a plain {@code ADD} at its L821, both of
     * which discard high-order digits and report nothing. This method performs it as a SATURATION and
     * reports it, which is divergence D-AUTH-SUMMARY-MONEY-DOMAIN.</p>
     *
     * <p>Assumptions: the warning names the FIELD and the bound and never the value, because a limit and an
     * authorization amount are both customer data and this line reaches durable diagnostics. What an
     * operator needs from it is that a value was too wide for the segment and which member it was; the
     * request itself is identified by the correlation identifier the shared filter carries on every line of
     * this message's processing.</p>
     *
     * @param value the value about to be stored on the summary; must not be {@code null}
     * @param field the summary member reported when the value is reduced; must not be {@code null}
     * @return the value, or the segment's greatest storable magnitude when the value exceeds it
     */
    private static BigDecimal narrowedToSummaryDomain(BigDecimal value, String field) {
        if (PendingAuthSummary.exceedsStoredDomain(value)) {
            LOG.warn("event=auth.summary.money-narrowed field={} bound={}", field,
                    PendingAuthSummary.MONEY_MAX_MAGNITUDE);
        }
        return PendingAuthSummary.narrowedToStoredDomain(value);
    }

    /**
     * Refuses a write whose statement reached no summary row.
     *
     * <p>Refactoring Rationale: the check is a named method because TWO statements now report a row
     * count and are held to one contract -- the limits refresh and the additive contribution -- where
     * the comparison was previously written inline at the single call site that existed. Two copies of
     * one refusal drift, and the sentence is the only thing an operator reading the failure works
     * from.</p>
     *
     * <p>Assumptions: the account identifier is NOT a parameter, and the sentence this raises names
     * none, for the reason {@link #rowCountFailureSentence()} records: the message reaches durable
     * diagnostics, where the migration's logging contract keeps account identifiers out, and the
     * correlation identifier the shared filter carries is what ties a failure back to one request.</p>
     *
     * @param rowsChanged the number of rows the statement reported changing
     * @throws IllegalStateException if {@code rowsChanged} is not exactly one, which rolls this
     *     message's unit of work back and leaves the request on the queue for redelivery
     */
    private static void requireSummaryChanged(int rowsChanged) {
        if (rowsChanged != 1) {
            throw new IllegalStateException(rowCountFailureSentence());
        }
    }

    /**
     * Warns when the contribution about to be applied will leave a counter resting on its bound.
     *
     * <p>Purpose: the three statements that move these counters clamp them to the four-digit domain
     * {@code PIC S9(04) COMP} declares, which is what keeps an account answerable once it has recorded
     * 9999 authorizations of one kind. A clamp is silent by construction -- the statement returns a row
     * count -- so this is the line that says a stored count has stopped tracking the authorizations behind
     * it.</p>
     *
     * <p>Assumptions: the check is made against the summary this transaction ALREADY READ, and it is
     * skipped when there is none. That keeps the normal path free of an extra query for a condition an
     * account reaches once in ten thousand authorizations, and the one arm that holds no summary cannot be
     * near the bound, for the reason recorded at that call site.</p>
     *
     * <p>Assumptions: only the account identifier, the member and the two values are named. The account
     * identifier is not a protected value -- every line this class writes carries it -- while the card
     * number and the amounts are, so neither appears here.</p>
     *
     * @param summary the summary read in this transaction, empty when none was read; must not be
     *     {@code null}
     * @param counter the accessor of the member this contribution advances; must not be {@code null}
     * @param field the member's name, reproduced in the report; must not be {@code null}
     * @param addend how much this contribution adds to that member, of type {@code int}
     */
    private static void reportCounterNarrowing(Optional<PendingAuthSummary> summary,
            java.util.function.Function<PendingAuthSummary, Short> counter, String field, int addend) {
        summary.ifPresent(stored -> {
            int next = counter.apply(stored) + addend;
            if (PendingAuthSummary.exceedsCounterDomain(next)) {
                LOG.warn("event=auth.summary.counter-narrowed accountId={} field={} requested={} "
                                + "stored={}",
                        stored.getAccountId(), field, next,
                        PendingAuthSummary.narrowedCounterToStoredDomain(next));
            }
        });
    }

    /**
     * Builds the sentence raised when a contribution statement reports that it changed no row.
     *
     * <p>Refactoring Rationale: the statement's row count is READ, where the calls that preceded this
     * correction discarded it. The repository states the contract explicitly -- a zero is the condition its
     * withdrawn read reported as an empty result, moved to the write -- and discarding it meant a decision
     * could commit with its detail row, its reply row and no contribution to the account's counters at all,
     * which is a silent loss of exactly the kind the outbox exists to remove one queue hop later.</p>
     *
     * <p>Assumptions: raising rolls this message's unit of work back and leaves the request on the queue, so
     * the redelivery re-reads the summary and either finds it or creates it. That is the treatment every
     * other permanent fault on this path already receives.</p>
     *
     * <p>Assumptions: the sentence names NO account identifier. It reaches a log at every level that records
     * the cause, and the migration's sensitive-data logging contract keeps account identifiers out of durable
     * diagnostics; the correlation identifier the shared filter carries is what ties the failure back to one
     * request.</p>
     *
     * @return the diagnostic sentence, never {@code null}
     */
    private static String rowCountFailureSentence() {
        return "a pending-authorization summary changed no row when this decision's contribution was"
                + " applied, so the account's counters would not account for a decision this transaction"
                + " would otherwise commit";
    }

    /**
     * Builds the detail row that records a decision.
     *
     * <p>Refactoring Rationale: the row's key is derived from the SERVER's clock and not from the
     * request. The baseline asks the platform for the current ordinal date and time in
     * {@code 8500-INSERT-AUTH} at lines 858 to 875 and keys the segment with those values; the request's
     * own date and time are moved separately into the two originating fields at its lines 877 and 878,
     * which this row also carries. Keying by the request's values instead would let a requester
     * choose its own primary key, so two requests naming one instant would collide and a requester
     * could place a row wherever it liked in the account's history, including ahead of rows this
     * service had already answered.</p>
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
     * <p>Assumptions: the MATCH STATUS is derived from the decision here and passed in rather than
     * fixed to pending by the entity. The reference insert selects between two values on exactly
     * this condition -- {@code cbl/COPAUA0C.cbl} L902 tests
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
     * <p>Assumptions: this is the LIVE validation boundary, and routing the decoded record through
     * {@link AuthorizationMessageMapper#toPayload(AuthRequest)} is what makes the declared contract
     * the enforced one. Working from the decoded wire record and hand-writing one check here -- the
     * amount domain -- would leave every other constraint {@link AuthorizationRequestPayload}
     * declares enforced only on the structured path that no message travels, with concrete
     * consequences: an absent field would pass, because the decoder only splits and does not
     * require, and a processing code or entry mode holding letters would pass and then be silently
     * rewritten by digit-stripping into a plausible number, persisting a malformed value as a well-
     * formed different one.</p>
     *
     * <p>Assumptions: the crossing runs BEFORE the idempotency seek and before every lookup and every
     * transformation, so a nonconforming request touches neither the account context nor a row and no
     * value is normalised on the way to being refused. Ordering it after the seek would let a malformed
     * message reach the account context and the summary reservation, spending a remote call and a write
     * on a request that was never going to be answered; ordering it after the transformations is what
     * allowed a stripped value to be stored.</p>
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
     * Establishes the one destination this request may be answered at, refusing the request otherwise.
     *
     * <p>Purpose: the destination arrives as a message attribute, so it is chosen by whoever can put a
     * message on the request queue. Without a check this service would send a reply carrying a card
     * number, a transaction identifier and an authorization outcome to any queue address that attribute
     * named, including one in another account. An allowlist is the only form of the check that works,
     * because the set of legitimate reply queues is a deployment fact rather than a pattern: a prefix or
     * a regular-expression test on the address would admit any queue whose name a requester could
     * arrange to match.</p>
     *
     * <p>Refactoring Rationale: this REFUSES where the two checks it replaces returned normally from the
     * point the outbox row was written. Returning there meant the decision rows had already been written,
     * so the transaction committed, the container's acknowledgement mode acknowledged the request, and a
     * committed decision existed that no reply row accounted for -- the phantom-decision state this
     * module's outbox was introduced to make unreachable, reachable again by one attribute a requester
     * controls. Raising instead makes the decision and its reply one atomic outcome: the write rolls back
     * with the refusal, the request stays on the queue, and it redelivers and then dead-letters.</p>
     *
     * <p>Alternatives Considered: keeping the decision and writing a TERMINAL reply row addressed to a
     * configured fallback queue, which the review offered as the second admissible resolution. Rejected
     * because it answers a requester at an address it never nominated -- the reply carries a card number
     * and an outcome, so a fallback destination is a disclosure to whoever reads that queue -- and
     * because a row that can never be delivered to the party that asked is an audit record dressed as a
     * reply. Refusing keeps one invariant instead of two half-truths: every committed decision has a
     * reply row addressed to a destination the deployment listed.</p>
     *
     * <p>Alternatives Considered: refusing at the point of the write rather than here. Rejected because
     * by then the account's counters have been moved and the detail row written, so the refusal would
     * roll back work that need never have been done; and because the ordering that matters is the one
     * the module already applies to a nonconforming payload -- the crossing runs before every lookup and
     * every transformation, so a request that cannot be answered touches no row and no account context.
     * This check therefore sits ahead of the decode, which is the earliest point at which the attribute
     * is available and nothing has yet been read.</p>
     *
     * <p>Assumptions: the refusal message names the ATTRIBUTE and never its value, and the log line
     * carries no value either. The rejected address is attacker-influenced text bound for a dead-letter
     * diagnostic and a log field, which is the same exposure the correlation attribute is sanitised for;
     * the allowlist is short, so its SIZE is enough for an operator to tell a missing entry from a
     * malicious address without this service quoting the address back.</p>
     *
     * <p>Assumptions: the refusal type is the same one a malformed payload raises. That type's own
     * documentation anticipates this use -- it records that a consumer typically validates a transport
     * attribute before handing the body over, and that one type for one failure mode leaves a consumer
     * with one exception to route to its dead-letter queue -- so a second type is not introduced for the
     * transport half of the same contract.</p>
     *
     * @param message the received message, whose attribute names the reply destination; must not be
     *     {@code null}
     * @return the destination, exactly as the deployment listed it, never {@code null} or blank
     * @throws AuthMessageFormatException if the request names no destination or names one the configured
     *     allowlist does not hold
     */
    private String requireAllowlistedReplyDestination(Message<String> message) {
        String replyQueueUrl = header(message, HEADER_REPLY_TO);
        if (replyQueueUrl == null || replyQueueUrl.isBlank()) {
            LOG.warn("event=auth.request.refused reason=no-reply-destination");
            throw new AuthMessageFormatException(
                    "the request names no reply destination in attribute " + HEADER_REPLY_TO
                            + ", so no reply could be guaranteed for a decision");
        }
        if (!this.replyQueueAllowlist.contains(replyQueueUrl)) {
            LOG.warn("event=auth.request.refused reason=destination-not-allowlisted allowlistSize={}",
                    this.replyQueueAllowlist.size());
            throw new AuthMessageFormatException(
                    "the request names a reply destination in attribute " + HEADER_REPLY_TO
                            + " that is not one of the " + this.replyQueueAllowlist.size()
                            + " destinations this deployment allows; the value is withheld because it is"
                            + " requester-supplied");
        }
        return replyQueueUrl;
    }

    /**
     * Writes a reply into the outbox, to be published after this transaction commits.
     *
     * <p>Assumptions: this is the descriptor half of {@code 7100-SEND-RESPONSE} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 738 to 782, and only the
     * descriptor half. That paragraph sets the destination from the saved reply-to queue at line 742,
     * the reply message type at line 744, the requester's correlation identifier at line 745, a fresh
     * message identifier at line 746, blank reply-to fields at lines 747 and 748 so the reply is
     * terminal, non-persistence at line 749 and an expiry at line 750 -- and then PUTS at line 758.
     * Everything except the put is recorded on the row here; the put itself belongs to
     * {@link OutboxPublisher}, which is the whole of the {@code D-5} inversion. Assumptions: the
     * paragraph's put is {@code MQPUT1}, which opens, puts and closes per message, and that is why a
     * destination taken from each request needs no pre-opened handle either there or here.</p>
     *
     * <p>Assumptions: the destination arrives here ALREADY VALIDATED, from
     * {@link #requireAllowlistedReplyDestination(Message)}, and is therefore not re-read from the message
     * and not re-checked. Taking it as a parameter rather than re-deriving it is what makes it impossible
     * for this method to reach a different verdict from the one the request was admitted under, and it is
     * why this method has no branch that declines to write a row: by the time it is called, a row is owed.</p>
     *
     * @param replyQueueUrl the allowlisted destination, established before any decision work; must not be
     *     {@code null}
     * @param reply the reply to publish; must not be {@code null}
     * @param correlationId the requester's correlation identifier; may be {@code null}
     * @param now the current instant in coordinated universal time; must not be {@code null}
     */
    private void enqueueReply(String replyQueueUrl, AuthReply reply, String correlationId,
            LocalDateTime now) {
        // WHY : Refactoring Rationale: the row carries the LITERAL card number and transaction identifier
        //   as the two queue identities, and it carried keyed tokens derived from them before. Sections
        //   0.4.1.8 and 0.7.6 of the technical specification freeze MessageGroupId as card_num and
        //   MessageDeduplicationId as transaction_id, and that specification is the frozen agreement this
        //   code aligns to. The derivation held each semantic within this one producer, but any other
        //   party -- a second publisher, a cross-account consumer, a replay tool -- computing an identity
        //   from the frozen contract computes a different one, so per-card ordering and duplicate
        //   suppression would both silently fail across producers while appearing correct within this one.
        // WHY : Trade-offs: both values become message metadata at publication, and a queue's server-side
        //   encryption covers a body and not its metadata, so the card number reaches queue telemetry and
        //   the trace of every send. The exposure is bounded by the deployment -- customer-managed-key
        //   encryption, an interface endpoint inside the private network, and task-role-scoped read
        //   access -- and the judgement belongs to the specification rather than to this method.
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
                        now.plusSeconds(DEFAULT_REPLY_EXPIRY_SECONDS)));
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
     * <p>Assumptions: the rule applied here is {@link MessagingCorrelationId#isCanonical(String)} and
     * NOT the servlet one, and both halves of that matter. Passing the attribute unchecked into the
     * logging context and into a persisted row would let a requester write a line terminator into a
     * log record. Borrowing the SERVLET predicate instead would bound an identity at twenty-four
     * characters drawn from a short alphabet -- appropriate for a value this system mints for a
     * response header, and wrong for one a requester renders from a twenty-four BYTE queue field,
     * since forty-eight hexadecimal characters, thirty-two base64 characters and a hyphenated
     * identifier are all legitimate renderings of that field and none of them satisfies the servlet
     * rule.</p>
     *
     * <p>Assumptions: a conforming value is returned VERBATIM -- not trimmed, not case-folded, not
     * re-encoded -- because the requester pairs the answer to the question on the exact opaque value it
     * sent. The separately sanitised rendering used for the logging context is produced by
     * {@link MessagingCorrelationId#logSafe(String)}, so the value that reaches a log and the value that
     * reaches the reply are allowed to differ, which is what lets the echo be exact without making the
     * log forgeable.</p>
     *
     * <p>Trade-offs: a value that is PRESENT and non-canonical REFUSES the message rather than being
     * dropped so the request can be decided anyway. That follows from the width of this rule: a
     * value failing it carries a control character or exceeds the width the store can hold, and
     * neither is something a legitimate requester expresses -- whereas under the narrower servlet
     * rule a failing value was usually a legitimate identity in an unexpected shape, which would
     * make refusal the wrong answer. Refusing lets the queue redeliver and then dead-letter the
     * message, which leaves evidence, where dropping the attribute would leave the requester
     * holding a correlation value that never comes back and nothing to explain why.</p>
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

    // WHY : Assumptions: this class holds NO fixed-width numeric parser of its own. record(...) projects
    //   the detail row through AuthorizationMessageMapper.toPendingAuthDetail, so the rule for how a
    //   numeric-picture field crosses from text has exactly one home. Alternatives Considered: keeping a
    //   local pair of parsers as the mapper's delegates, which would give the rule one home while
    //   expressing it in two places. Rejected because two copies of a crossing drift on the first change
    //   made to one of them, and because the mapper already refuses a non-digit at the intake boundary
    //   through the digits-only expressions the payload declares -- so a delegate would add a hop and no
    //   guarantee.
}
