package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.domain.OutboxMessage;
import com.carddemo.authorization.repository.OutboxRepository;
import com.carddemo.common.messaging.MessageExpiry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Publishes the authorization replies that the deciding consumer committed into the transactional
 * outbox.
 *
 * <p>This class has no counterpart in the reference system, where a reply is a message and never a
 * record. It exists because of the ORDER in which the reference consumer does its work, and that
 * order runs opposite to the intuitive reading of the source, so it is established from the
 * paragraph structure rather than from line sequence.</p>
 *
 * <p>Refactoring Rationale: in {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} the poll
 * loop opens at L326 and performs {@code 5000-PROCESS-AUTH} at L330. That paragraph decides at L459,
 * performs {@code 7100-SEND-RESPONSE} at L461 -- whose put is the program's single
 * {@code CALL 'MQPUT1'} at L758 -- and only AFTERWARDS performs {@code 8000-WRITE-AUTH-TO-DB} at
 * L463 to L465. The one {@code EXEC CICS SYNCPOINT} is reached later still, at L334 to L336 with the
 * verb on L335, once the paragraph performed at L330 returns. The verified order is therefore
 * decide, PUT the reply, WRITE to the database, COMMIT. Both queue operations are additionally
 * OUTSIDE that unit of work: the get computes {@code MQGMO-NO-SYNCPOINT} at L389 to L391 and the put
 * computes {@code MQPMO-NO-SYNCPOINT} at L753 to L754. The structurally dominant consequence is a
 * PHANTOM REPLY -- a reply on the wire that no committed row accounts for -- and it is unrecoverable,
 * because the destructive get consumed the request outside the unit of work as well, so nothing
 * redelivers it and the answer cannot be re-derived. The target moves publication to AFTER the
 * commit: {@link AuthorizationRequestListener} writes the reply into the database inside the
 * deciding transaction, and this class drains it. A committed decision therefore always has exactly
 * one publishable reply and an uncommitted one has none. This is divergence D-5 in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Assumptions: a reader who knows the line NUMBERS but not the paragraph structure will read that
 * ordering backwards, so the apparent contradiction is named rather than left to be rediscovered.
 * L335 precedes L758 in source-text order, yet L335 EXECUTES AFTER L758, because the paragraph
 * performed at L330 runs the whole decide-and-send subtree before control ever reaches L334. Any
 * description of this seam that has the commit first describes only a lost reply and misses the
 * phantom one; the two share one root cause -- the reply is published outside the transaction -- so
 * both readings lead here, but only this ordering explains which failure dominates.</p>
 *
 * <p>Refactoring Rationale: one corollary divergence follows from the same relocation and is
 * recorded rather than glossed. The reference program has NO rollback path at all -- the token
 * {@code ROLLBACK} does not occur anywhere in its 1026 lines -- so a failed database write is logged
 * and the task ends: {@code 8400-UPDATE-SUMMARY} sets {@code ERR-CRITICAL} at L841 and logs at L846,
 * {@code 8500-INSERT-AUTH} does the same at L926 and L931, and {@code 9500-LOG-ERROR} then reaches
 * L1008, whose criticality test performs {@code 9990-END-ROUTINE} at L1009 and issues
 * {@code EXEC CICS RETURN} at L1021. Because the put at L758 and the get at L400 both sit outside
 * the unit of work, ending the task reverses neither, which is what makes the phantom reply the
 * guaranteed outcome of a critical write failure rather than merely a possible one. The target's
 * {@code @Transactional} boundary plus exception propagation ROLLS BACK where the reference logs and
 * stops, so the decision, its writes and its reply row share one fate. That is a behavioural
 * difference in its own right, and it is a consequence ENTAILED by D-5's own target statement --
 * that the reply row is written inside the deciding transaction -- rather than a separately
 * identified entry in the register. It is stated here rather than claimed under an identifier of its
 * own because the boundary that produces it does not belong to this class: the rollback belongs to
 * {@link AuthorizationRequestListener}, which owns the deciding transaction, and this class
 * deliberately does the OPPOSITE on a send failure. A row whose send fails is left pending with its
 * attempt recorded, so a later drain retries it; rolling back here would discard a reply the
 * database has already committed to sending, which is the single outcome the outbox exists to make
 * impossible.</p>
 *
 * <p>Alternatives Considered: making the receive, the decision and the send ONE transaction, which
 * would close the window without any outbox at all. It is not available in the target and it was
 * available in the reference: the sibling inquiry program
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl} composes the SAME option set as
 * {@code COPAUA0C} and selects the opposite syncpoint flag -- {@code MQGMO-SYNCPOINT} at L347 beside
 * the identical wait, convert and fail-if-quiescing flags, and {@code MQPMO-SYNCPOINT} at L475 and
 * L512 beside the same default-context flag -- so its receive, logic and puts stand or fall
 * together. The reference authors therefore had the option in front of them and chose otherwise. The
 * target cannot simply adopt the inquiry program's shape, because the queue service offers no
 * transactional send that enlists with a database transaction, so the only way to give a reply and
 * its decision one fate is to commit the reply as a row and publish it afterwards.</p>
 *
 * <p>Assumptions: the asymmetry that follows is deliberate and must not be transposed.
 * {@code account-service} has NO outbox, precisely because {@code COACCT01.cbl} already enlists both
 * ends in one unit of work, so there is no phantom-reply and no lost-reply window there for a
 * durable row to close. Adding one would add a table and a drain that remove no failure mode;
 * removing this one would reopen a window that is real. Reading either absence as an oversight
 * breaks one service or the other.</p>
 *
 * <p>Trade-offs: adopting an outbox HERE while {@code docs/adr/ADR-007-service-boundaries.md}
 * rejects a saga for {@code batch-service}'s posting unit of work looks inconsistent until the
 * difference is named. A saga would fragment a commit that is ALREADY ATOMIC and would make
 * intermediate states observable -- a posted transaction whose balance has not moved -- which the
 * golden masters would correctly flag. This outbox fragments nothing: it adds a row to the very
 * transaction that decides and moves publication from before the commit to after it, which is the
 * whole of its effect on a publication that was never atomic with the data in the first place. Both
 * decisions apply one criterion, that no state becomes observable which the reference does not
 * already make observable.</p>
 *
 * <p>Assumptions: publication is at-least-once rather than exactly-once, and that is safe because
 * the reply queue is first-in-first-out and every message carries a purpose-scoped derivation of the
 * card-and-transaction identity as its deduplication identifier. A reply sent twice inside the
 * deduplication window is accepted once; outside it the requester must tolerate a duplicate, which
 * is a genuine obligation this design introduces rather than inherits, since the reference queue
 * never redelivered anything -- its destructive get destroyed each message on read. The obligation
 * is accepted because the reply carries the transaction identifier at ordinal 2 of
 * {@code cpy/CCPAURLY.cpy}, so a requester can suppress duplicates by key, whereas a reply that is
 * never sent at all cannot be reconstructed by anyone.</p>
 *
 * <p>Trade-offs: the drain is POLLED on a fixed delay rather than triggered by the committing
 * transaction. A commit-time trigger would publish sooner in the ordinary case and would publish
 * NOTHING after a crash between the commit and the trigger, which is the exact window this class
 * exists to close; one poll covers both cases with one mechanism. The delay is short and
 * configured, so the added latency is bounded by it.</p>
 */
@Component
public class OutboxPublisher {

    /**
     * The message attribute carrying the correlation identifier echoed back to the requester.
     *
     * <p>Assumptions: this ALIASES the constant its owner declares rather than repeating the wire
     * name, and the aliasing is what makes the echo structural. The requester's identity arrives on
     * {@link AuthorizationRequestListener#HEADER_CORRELATION_ID} and leaves on this attribute, which
     * is the same wire name because the reference moves the saved inbound identifier straight onto
     * the reply descriptor at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L745 after
     * capturing it at L411 to L412. Two independent literals would let the read side and the write
     * side drift apart while every test that checks only one of them still passed.</p>
     */
    public static final String ATTRIBUTE_CORRELATION_ID =
            AuthorizationRequestListener.HEADER_CORRELATION_ID;

    /**
     * The message attribute carrying the instant after which the reply stops being worth sending.
     *
     * <p>Assumptions: this aliases the shared-kernel constant for the same reason as the correlation
     * attribute above, and here the owner is outside this service entirely -- every context that
     * carries a deadline has to spell it identically or a consumer silently stops finding it. The
     * queue service has no per-message time to live, so the deadline the reference sets on its reply
     * put travels as this attribute and the receiving end honours it; the resolution is recorded in
     * {@code docs/adr/ADR-004-messaging.md}.</p>
     */
    public static final String ATTRIBUTE_EXPIRES_AT = MessageExpiry.HEADER_EXPIRES_AT;

    /**
     * The message attribute naming the wire format the payload is expressed in.
     *
     * <p>Assumptions: this is the one attribute name this class OWNS, so the literal belongs here
     * and is not aliased from anywhere. Nothing else in this service declares it, because nothing
     * else sets it: the reference declares its payload as a string format at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L751, and this attribute is what
     * lets a consumer tell the migrated delimited reply from the structured envelope offered
     * additively beside it without inspecting the bytes.</p>
     */
    public static final String ATTRIBUTE_CONTENT_TYPE = "contentType";

    /**
     * The attribute data type used for every attribute this publisher sets.
     */
    private static final String ATTRIBUTE_TYPE_STRING = "String";

    /**
     * The logger for this publisher.
     */
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);

    /**
     * The reason recorded against a reply that was retired because its deadline had passed.
     *
     * <p>Assumptions: a retired reply is a terminal outcome and not a fault, so the text names the
     * outcome rather than an error. It is well inside the diagnostic column's declared width, so it
     * is stored whole rather than truncated.</p>
     */
    private static final String RETIREMENT_REASON = "expired before publication";

    /**
     * The largest batch one drain may claim.
     *
     * <p>Assumptions: 500 is the reference consumer's own per-invocation quota, declared as
     * {@code 05 WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500.} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L40 and enforced at L339, where the
     * loop ends once the processed count exceeds it. That makes it the largest number of replies
     * anything in this migration treats as ONE PASS, so a value above it is a misconfiguration
     * rather than a tuning choice. {@link OutboxRepository#claimGroupHeads(int, java.time.LocalDateTime, java.time.LocalDateTime, int)} cites the same two
     * lines, so the ceiling here and the bound there cannot disagree.</p>
     *
     * <p>Refactoring Rationale: this ceiling was once justified as the largest figure treated as one
     * TRANSACTION, which was true of a superseded revision in which a whole pass -- claims, sends and
     * transitions -- ran inside one. {@link #transition} records why that was split. The number is
     * unchanged and its source is unchanged; what the bound now limits is how many rows one pass
     * claims and leases before it publishes any of them, which is why the wording was corrected
     * rather than the value.</p>
     *
     * <p>Trade-offs: the ceiling refuses a value an operator might have meant as "drain
     * everything". Draining everything is what the fixed-delay poll already does across passes, one
     * bounded batch at a time, so the refusal costs nothing an operator cannot express while
     * keeping one pass from leasing an unbounded number of rows ahead of the sends that clear
     * them.</p>
     */
    private static final int MAX_BATCH_SIZE = 500;

    /**
     * The greatest per-pass row budget an operator may configure.
     *
     * <p>Assumptions: the ceiling is ten times the reference consumer's own per-invocation limit, so an
     * operator draining a backlog has room to raise the figure while the bound still forbids the
     * unbounded pass it exists to prevent.</p>
     */
    private static final int MAX_ROWS_PER_DRAIN_CEILING = 5_000;

    /**
     * The greatest attempt ceiling an operator may configure.
     *
     * <p>Assumptions: a value this large is already far past any transient outage -- with the doubling
     * backoff below, a hundred attempts spans days -- so the bound refuses a figure that is a ceiling
     * only nominally.</p>
     */
    private static final int MAX_ATTEMPTS_CEILING = 100;

    /**
     * How long a claimed row is owned by the pass that claimed it.
     *
     * <p>Assumptions: two minutes comfortably exceeds one send plus its whole retry budget -- the
     * template above allows a small number of retries at sub-second delays -- so a slow but healthy
     * send cannot lose its row mid-flight. Trade-offs: a publisher killed mid-send strands its group
     * for at most this long, which is the price of not holding a database lock across the send.</p>
     */
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);

    /**
     * The base delay before a failed reply is attempted again.
     *
     * <p>Assumptions: five seconds is the reference consumer's own get-with-wait interval, so the first
     * retry of a failed reply arrives no sooner than the reference would have polled for the next
     * request; nothing is gained by retrying a refused transport faster than that.</p>
     */
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(5);

    /**
     * The ceiling the doubling backoff is clamped to.
     */
    private static final Duration MAX_RETRY_BACKOFF = Duration.ofMinutes(15);

    /**
     * The greatest number of doublings applied to the base backoff.
     *
     * <p>Assumptions: the exponent is clamped before the shift rather than after, because a shift wider
     * than the type's own width is undefined in the language's terms and produces a small or negative
     * delay -- reintroducing in the arithmetic exactly the wraparound the widened attempt counter was
     * changed to remove. Twelve doublings of five seconds already exceeds the ceiling above, so
     * clamping here costs nothing.</p>
     */
    private static final int MAX_BACKOFF_DOUBLINGS = 12;

    /**
     * How many published rows one retention transaction deletes.
     */
    private static final int PURGE_CHUNK_SIZE = 500;

    /**
     * How many chunks one retention sweep deletes before yielding to the next scheduled sweep.
     *
     * <p>Assumptions: twenty chunks of five hundred is ten thousand rows per sweep, which at the
     * default hourly interval clears far more than a day of replies; the bound exists so the first
     * sweep after an outage is bounded too, not to ration ordinary retention.</p>
     */
    private static final int MAX_PURGE_CHUNKS_PER_SWEEP = 20;

    /**
     * The shortest polling interval the publisher accepts, in milliseconds.
     *
     * <p>Assumptions: a floor rather than merely a positive test, because this interval is the delay
     * BETWEEN passes and each pass opens a transaction and issues a claiming statement. An interval
     * of one millisecond turns the drain into a busy loop against the database whose only effect is
     * to consume the connection budget the deciding path needs. Fifty milliseconds is well below the
     * second the deployment configures and far above the point at which a pass costs more than it
     * publishes.</p>
     */
    private static final long MIN_POLL_INTERVAL_MILLIS = 50L;

    /**
     * The longest polling interval the publisher accepts, in milliseconds.
     *
     * <p>Assumptions: the ceiling is one hour, and it exists because this interval is the upper
     * bound on how long a committed reply waits before it is published. A value beyond an hour
     * leaves a decided authorization unanswered for longer than any requester waits, which presents
     * as a silent functional outage rather than as a misconfiguration.</p>
     *
     * <p>Assumptions: the test profile sets exactly this value, one hour, to keep the scheduled
     * drain from running during a test that drives it directly. The ceiling is therefore INCLUSIVE,
     * and lowering it would break that profile rather than catch a mistake.</p>
     */
    private static final long MAX_POLL_INTERVAL_MILLIS = 3_600_000L;

    /**
     * How many further attempts one send may make after its first attempt fails transiently.
     *
     * <p>Assumptions: the framework attribute this mirrors is named {@code maxRetries} and NOT
     * {@code maxAttempts}, so the total number of attempts is ONE PLUS this value -- two here -- and
     * an unset budget would be four, because {@code RetryPolicy.Builder.DEFAULT_MAX_RETRIES} is
     * three. Reading it as an attempt count is an off-by-one in a retry budget, which surfaces only
     * under the failure it was meant to handle. The companion fact belongs beside it: the annotation
     * form of this same capability is enabled by {@code @EnableResilientMethods} on a configuration
     * class and NOT by the older {@code @EnableRetry}. Both are recorded in
     * {@code docs/adr/ADR-002-compute-platform.md}.</p>
     *
     * <p>Trade-offs: one further attempt is deliberately the smallest budget that covers a one-off
     * blip, and the reason is arithmetic rather than taste. The send sits inside the drain's unit of
     * work and one row per ordering group can fail per pass, so a budget of N multiplies into N
     * delays across as many groups as the batch holds. A reset connection or a single throttled call
     * clears on a second attempt; a sustained transport fault is the transport's tier to absorb
     * through redelivery and its dead-letter queue, and retrying harder in-process against a
     * throttling service makes the throttling worse rather than better.</p>
     */
    private static final long SEND_MAX_RETRIES = 1L;

    /**
     * How long a send waits before its one further attempt.
     *
     * <p>Assumptions: a multiplier and a maximum delay are deliberately NOT configured alongside
     * this value. A single further attempt has exactly one delay for a multiplier to apply to, so
     * declaring either would read as configured backoff that can never take effect.</p>
     */
    private static final Duration SEND_RETRY_DELAY = Duration.ofMillis(200L);

    /**
     * How much randomness is added to the retry delay.
     *
     * <p>Assumptions: jitter matters here even with a single retry, because a whole batch of sends
     * can be throttled by the same event and would otherwise retry in lockstep, presenting the
     * transport with the same burst a fixed interval later.</p>
     */
    private static final Duration SEND_RETRY_JITTER = Duration.ofMillis(50L);

    /**
     * The bounded retry applied to one send, over a narrow classification of transient faults.
     *
     * <p>Alternatives Considered: adopting an external resilience library, either
     * {@code io.github.resilience4j:resilience4j-spring-boot3} -- whose published artifact targets
     * the previous framework major -- or the superseded {@code spring-retry}. Both are rejected
     * because the framework core inside the Spring Boot parent already supplies this exact
     * capability, so a library would become a SECOND retry authority competing with queue
     * redelivery and orchestrator retry; two budgets multiply rather than add, so a message would
     * reach its dead-letter queue long after an operator expected it to.
     * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
     * makes reaching for either a build failure. No circuit breaker is configured either: it would
     * add a failure mode of its own, an open circuit refusing sends a recovered queue could have
     * accepted, without removing one, because the bounded budget above and the queue's own
     * redelivery already stop a degraded transport from consuming this loop. Recorded in
     * {@code docs/adr/ADR-002-compute-platform.md}.</p>
     *
     * <p>Alternatives Considered: classifying by an {@code includes} list of exception CLASSES,
     * which is the more usual shape. It is rejected because the transport expresses transience as a
     * PROPERTY of the exception rather than as a distinct type, so a class list could only name the
     * SDK's root exception -- wide enough to retry a malformed request forever -- or would miss
     * throttling altogether. A predicate is also the closer analogue of the reference, which
     * classifies by status VALUE rather than by kind.</p>
     *
     * <p>Assumptions: one shared instance is safe to use from the scheduler's thread and from a
     * test's, because the policy is immutable once built and the template creates its per-call state
     * inside the invocation rather than holding it on the instance.</p>
     */
    private static final RetryTemplate SEND_RETRIES = new RetryTemplate(RetryPolicy.builder()
            .maxRetries(SEND_MAX_RETRIES)
            .delay(SEND_RETRY_DELAY)
            .jitter(SEND_RETRY_JITTER)
            .predicate(OutboxPublisher::isTransientTransportFault)
            .build());

    /**
     * The outbox repository the pending rows are claimed from.
     */
    private final OutboxRepository outbox;

    /**
     * The queue client replies are sent with.
     */
    private final SqsClient sqs;

    /**
     * The clock publication instants are read from.
     */
    private final Clock clock;

    /**
     * How many ordering groups one drain claims a head row from.
     */
    private final int batchSize;

    /**
     * For how many days a published reply is retained before the sweep removes it.
     *
     * <p>Assumptions: the window is operational rather than regulatory -- it exists so an operator
     * can answer whether a reply was sent for a given transaction -- so it is configured and short
     * by default. A row still carries an encoded reply containing a primary account number, so
     * keeping one longer than the question needs is a disclosure surface rather than a safety
     * margin.</p>
     */
    private final int retentionDays;

    /**
     * The transaction template every claim, transition and purge chunk runs in.
     *
     * <p>Refactoring Rationale: the unit of work is opened PROGRAMMATICALLY at each of those points
     * rather than declared once on the drain method, and the change is the substance of this class's
     * restructuring. A declarative transaction on the drain spans the whole pass, so it necessarily
     * encloses every synchronous queue send and the retry budget behind each one -- a pooled database
     * connection and the write locks of every claimed row held across seconds of network work. A
     * template lets the transaction END at a visible point in the code, which is what allows the claim
     * to commit before the send begins and the outcome to be written in a second short transaction
     * afterwards.</p>
     *
     * <p>Assumptions: the template propagates as REQUIRES_NEW for the same reason the annotation it
     * replaces did. Both scheduled entry points may also be invoked directly -- by an operator endpoint
     * or a test -- and neither may join a caller's transaction, because joining would put the caller's
     * commit in charge of whether a claim it knows nothing about is durable.</p>
     */
    private final TransactionTemplate shortTransaction;

    /**
     * The greatest number of rows one drain pass may reach a decision about, across every group.
     *
     * <p>Assumptions: the default is five hundred because that is the reference consumer's own
     * per-invocation processing limit -- {@code cbl/COPAUA0C.cbl} bounds one invocation at five hundred
     * messages -- so the migrated publisher inherits the figure rather than inventing one. Refactoring
     * Rationale: before this bound existed the batch size limited only how many GROUPS a pass opened,
     * and each group's follow-on loop then drained that group until it ran dry, so one card with a
     * backlog gave the pass no upper bound at all.</p>
     */
    private final int maxRowsPerDrain;

    /**
     * The greatest number of rows one drain pass may reach a decision about within a single group.
     *
     * <p>Assumptions: this is DERIVED as an even share of the pass budget across the groups the pass
     * opens, rather than configured separately, so the two numbers cannot be set into contradiction. A
     * separate property was rejected because an operator could then configure a per-group budget larger
     * than the pass budget, at which point one hot group consumes the pass and the fairness this
     * division exists to provide silently disappears. The share is at least one row, so a pass always
     * makes progress however many groups it opened.</p>
     */
    private final int perGroupRowBudget;

    /**
     * How long a claimed row is owned by the pass that claimed it.
     *
     * <p>Assumptions: the lease is what replaces the row lock the earlier revision relied on. It must
     * comfortably exceed one send including its retry budget, or a slow but healthy send would lose its
     * row to a concurrent publisher mid-flight; and it must be short enough that a publisher killed
     * mid-send does not strand its group for long.</p>
     */
    private final Duration claimLease;

    /**
     * How many attempts a reply is given before it is abandoned.
     *
     * <p>Assumptions: this is the terminal policy, and it is a bound on ATTEMPTS BEGUN rather than on
     * elapsed time, because the attempt counter is the one durable record of how many times the
     * transport has refused the row. Refactoring Rationale: there was no such bound, so a reply whose
     * queue was permanently unreachable was retried forever and held its group's head position
     * forever.</p>
     */
    private final int maxAttempts;

    /**
     * The base delay before a failed reply is attempted again.
     */
    private final Duration retryBackoff;

    /**
     * The ceiling the doubling backoff is clamped to.
     */
    private final Duration maxRetryBackoff;

    /**
     * How many published rows one retention transaction deletes.
     */
    private final int purgeChunkSize;

    /**
     * How many chunks one retention sweep deletes before yielding to the next scheduled sweep.
     *
     * <p>Assumptions: the sweep yields rather than looping until the table is clear, so its duration is
     * bounded even on the first pass after an outage; the next scheduled sweep resumes with the same
     * predicate, so nothing eligible is missed, only deferred.</p>
     */
    private final int maxPurgeChunksPerSweep;

    /**
     * Creates the publisher over the outbox table, the queue client and the clock it reads.
     *
     * <p>Assumptions: the three configured numbers are properties this service's own
     * {@code application.yml} declares under {@code carddemo.messaging}, so they are bound values
     * rather than literals decided here; queue topology, receive wait and attribute contract remain
     * with {@code com.carddemo.authorization.config.SqsConfig} and none of them is restated.</p>
     *
     * <p>Refactoring Rationale: both retained numbers are VALIDATED here rather than left to fail in
     * use. A non-positive batch size reaches the database as a row limit it rejects, so the failure
     * surfaces once per poll interval from a scheduled thread instead of at start-up, and an
     * enormous one leases an unbounded number of rows at the head of a pass, each of them withheld
     * from any other publisher until this pass reaches it or its lease lapses. Refusing either turns
     * a recurring background fault into a container that does not start, which an orchestrator
     * reports.</p>
     *
     * <p>Alternatives Considered: reading the polling interval inside {@link #drain()} rather than
     * injecting it here only to bound it, the schedule on that method reading the same property
     * independently. Rejected because the check would then run once per pass and could not stop the
     * instance from starting at all, whereas the scheduler's own rejection of a malformed value
     * arrives during context refresh and names the expression rather than the operator's mistake --
     * and bounds nothing that parses but is absurd.</p>
     *
     * @param outbox the outbox repository pending rows are claimed from; must not be {@code null}
     * @param sqs the queue client replies are sent with; must not be {@code null}
     * @param clock the clock publication and retention instants are read from; must not be
     *     {@code null}
     * @param batchSize how many ordering groups one drain claims a head row from; must be positive
     *     and at most {@value #MAX_BATCH_SIZE}
     * @param pollIntervalMillis the delay between drains in milliseconds, bounded here and applied
     *     by the schedule on {@link #drain()}; must be between {@value #MIN_POLL_INTERVAL_MILLIS}
     *     and {@value #MAX_POLL_INTERVAL_MILLIS} inclusive
     * @param retentionDays for how many days a published reply is retained before
     *     {@link #purgePublished()} removes it; must be positive
     * @param transactionManager the manager the short claim, transition and purge transactions are
     *     opened against; must not be {@code null}
     * @param maxRowsPerDrain the greatest number of rows one pass may reach a decision about across
     *     every group; must be at least {@code batchSize} and at most
     *     {@value #MAX_ROWS_PER_DRAIN_CEILING}
     * @param maxAttempts how many attempts a reply is given before it is abandoned; must be positive
     *     and at most {@value #MAX_ATTEMPTS_CEILING}
     * @throws NullPointerException if {@code outbox}, {@code sqs}, {@code clock} or
     *     {@code transactionManager} is {@code null}
     * @throws IllegalArgumentException if any configured number falls outside its stated bounds
     */
    public OutboxPublisher(OutboxRepository outbox, SqsClient sqs, Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${carddemo.messaging.outbox-batch-size:25}") int batchSize,
            @Value("${carddemo.messaging.outbox-poll-interval-ms:1000}") long pollIntervalMillis,
            @Value("${carddemo.messaging.outbox-retention-days:7}") int retentionDays,
            @Value("${carddemo.messaging.outbox-max-rows-per-drain:500}") int maxRowsPerDrain,
            @Value("${carddemo.messaging.outbox-max-attempts:10}") int maxAttempts) {
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.sqs = Objects.requireNonNull(sqs, "sqs must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.batchSize = validatedBatchSize(batchSize);
        requireBoundedPollInterval(pollIntervalMillis);
        this.retentionDays = validatedRetentionDays(retentionDays);
        this.maxRowsPerDrain = validatedMaxRowsPerDrain(maxRowsPerDrain, this.batchSize);
        this.maxAttempts = validatedMaxAttempts(maxAttempts);
        // WHY : Assumptions: integer division rounds the share DOWN and the floor of one lifts it, so
        // the per-group budget never exceeds the pass budget and is never zero. Rounding up was
        // rejected because at a batch size that does not divide the pass budget the rounded-up shares
        // sum above it, which would let the groups collectively exceed the pass bound; the pass bound
        // is then still enforced by the drain loop, but the two numbers would disagree and a reader
        // could not tell which one governed.
        this.perGroupRowBudget = Math.max(1, this.maxRowsPerDrain / this.batchSize);
        this.claimLease = CLAIM_LEASE;
        this.retryBackoff = RETRY_BACKOFF;
        this.maxRetryBackoff = MAX_RETRY_BACKOFF;
        this.purgeChunkSize = PURGE_CHUNK_SIZE;
        this.maxPurgeChunksPerSweep = MAX_PURGE_CHUNKS_PER_SWEEP;
        // WHY : Assumptions: the template is configured here rather than injected as a bean, because
        // its propagation is a property of THIS class's contract -- both scheduled methods must own
        // their units of work -- and a shared bean would carry whatever propagation its other users
        // wanted. Trade-offs: the template is stateless once configured and is safe to reuse across
        // the scheduled threads, so one instance is held rather than one built per pass.
        TransactionTemplate template = new TransactionTemplate(Objects
                .requireNonNull(transactionManager, "transactionManager must not be null"));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.shortTransaction = template;
    }

    /**
     * Returns the per-pass row budget after refusing a value that cannot bound a pass.
     *
     * <p>Assumptions: the budget must be at least the batch size, because a pass claims that many head
     * rows before it publishes anything; a smaller budget would leave heads claimed and unhandled every
     * pass, so their leases would have to lapse before anything drained them and the queue would move
     * at the lease interval rather than the poll interval.</p>
     *
     * @param configured the budget as the configuration bound it
     * @param batchSize the already-validated batch size the budget must accommodate
     * @return that same value once it is known to be inside its bounds
     * @throws IllegalArgumentException if the value is below the batch size or above
     *     {@value #MAX_ROWS_PER_DRAIN_CEILING}
     */
    private static int validatedMaxRowsPerDrain(int configured, int batchSize) {
        if (configured < batchSize) {
            throw new IllegalArgumentException(
                    "carddemo.messaging.outbox-max-rows-per-drain must be at least the batch size "
                            + batchSize + " but was " + configured
                            + "; a pass claims one head per group before publishing any of them, so a"
                            + " smaller budget leaves heads claimed and unhandled every pass");
        }
        if (configured > MAX_ROWS_PER_DRAIN_CEILING) {
            throw new IllegalArgumentException(
                    "carddemo.messaging.outbox-max-rows-per-drain must be at most "
                            + MAX_ROWS_PER_DRAIN_CEILING + " but was " + configured
                            + "; the bound exists to keep one pass short, and a larger value returns"
                            + " the unbounded pass it was introduced to remove");
        }
        return configured;
    }

    /**
     * Returns the attempt ceiling after refusing a value that cannot terminate a failing row.
     *
     * @param configured the ceiling as the configuration bound it
     * @return that same value once it is known to be inside its bounds
     * @throws IllegalArgumentException if the value is not positive or exceeds
     *     {@value #MAX_ATTEMPTS_CEILING}
     */
    private static int validatedMaxAttempts(int configured) {
        if (configured <= 0) {
            throw new IllegalArgumentException(
                    "carddemo.messaging.outbox-max-attempts must be positive but was " + configured
                            + "; a non-positive ceiling abandons every reply on its first attempt");
        }
        if (configured > MAX_ATTEMPTS_CEILING) {
            throw new IllegalArgumentException(
                    "carddemo.messaging.outbox-max-attempts must be at most " + MAX_ATTEMPTS_CEILING
                            + " but was " + configured
                            + "; the ceiling is what stops a permanently unreachable queue holding a"
                            + " group's head forever, and a value this large is not a ceiling");
        }
        return configured;
    }

    /**
     * Returns the batch size after refusing a value one pass must not claim.
     *
     * @param configured the batch size as the configuration bound it
     * @return that same value once it is known to be inside its bounds
     * @throws IllegalArgumentException if the value is not positive or exceeds
     *     {@value #MAX_BATCH_SIZE}
     */
    private static int validatedBatchSize(int configured) {
        if (configured <= 0) {
            throw new IllegalArgumentException(
                    "carddemo.messaging.outbox-batch-size must be positive but was " + configured
                            + "; a non-positive batch reaches the database as a row limit it"
                            + " rejects, once per poll interval, from a scheduled thread");
        }
        if (configured > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "carddemo.messaging.outbox-batch-size must be at most " + MAX_BATCH_SIZE
                            + " but was " + configured + "; one pass claims and LEASES every head"
                            + " before it publishes any of them, so a larger batch holds more leases"
                            + " across more sends than any pass should");
        }
        return configured;
    }

    /**
     * Returns the retention window after refusing a value that would erase its own pass's work.
     *
     * <p>Assumptions: only the FLOOR is enforced. A long window is a disclosure surface rather than
     * a fault -- the argument on {@link #retentionDays} -- and an operator lengthening it
     * deliberately is making a judgement this class is not placed to overrule, whereas a
     * non-positive one is never a judgement at all.</p>
     *
     * @param configured the retention window in days as the configuration bound it
     * @return that same value once it is known to be positive
     * @throws IllegalArgumentException if the value is not positive, a window that would delete a
     *     reply in the same pass that published it
     */
    private static int validatedRetentionDays(int configured) {
        if (configured <= 0) {
            throw new IllegalArgumentException(
                    "carddemo.messaging.outbox-retention-days must be positive but was " + configured
                            + "; a non-positive window would delete a reply in the pass that sent"
                            + " it, so no operator could ever confirm a reply had been published");
        }
        return configured;
    }

    /**
     * Refuses a polling interval that would either busy-loop the database or strand a reply.
     *
     * <p>Assumptions: the value is not retained, because the schedule on {@link #drain()} reads the
     * same property itself. What this method contributes is the refusal, and a refusal thrown from a
     * constructor stops the bean.</p>
     *
     * @param configured the polling interval in milliseconds as the configuration bound it
     * @throws IllegalArgumentException if the value falls outside the inclusive bounds
     */
    private static void requireBoundedPollInterval(long configured) {
        if (configured < MIN_POLL_INTERVAL_MILLIS || configured > MAX_POLL_INTERVAL_MILLIS) {
            throw new IllegalArgumentException(
                    "carddemo.messaging.outbox-poll-interval-ms must be between "
                            + MIN_POLL_INTERVAL_MILLIS + " and " + MAX_POLL_INTERVAL_MILLIS
                            + " milliseconds but was " + configured
                            + "; below the floor the drain busy-loops the database and above the"
                            + " ceiling a committed reply waits longer than any requester does");
        }
    }

    /**
     * Claims and publishes one bounded batch of committed replies, one ordering group at a time.
     *
     * <p>Assumptions: the claim is an atomic STATUS TRANSITION and not a selection under a lock.
     * {@link OutboxRepository#claimGroupHeads(int, java.time.LocalDateTime, java.time.LocalDateTime, int)} moves each row it takes from the attempt count it
     * observed to the next one and returns only the rows whose transition it actually performed, so
     * single delivery is a property of the DATA rather than of anything held open while a reply is
     * sent. A concurrent pass that observed the same candidate finds the comparison no longer true
     * and receives that row not at all, rather than receiving it a second time.</p>
     *
     * <p>Alternatives Considered: claiming by taking a pessimistic row lock in the selection --
     * {@code SELECT ... FOR UPDATE SKIP LOCKED}, or the same lock requested through a JPA lock mode
     * on a query method -- and stepping over rows another transaction holds. Rejected, but NOT because
     * it would introduce locking where there is none. Refactoring Rationale: this paragraph claimed the
     * alternative would bring "lock-wait queueing and deadlock-victim rollback on a path that has
     * neither today", and that was false -- the claim is an UPDATE, so it takes an ordinary row write
     * lock and a concurrent pass waits for it. The true grounds are that correctness does not depend on
     * the lock, the token comparison deciding instead, so a claim that waited and then lost receives the
     * row not at all; and that deadlock is unreachable because every claim acquires in one global order,
     * ascending by identity. The reference evidence bears on the shape rather than the presence of
     * locking: {@code cpy/IMSFUNCS.cpy} declares all three get-hold codes at L19, L21 and L23, no
     * program passes any of them to a data-language call, and both unload views run get-only at
     * {@code ims/PAUTBUNL.PSB} L18 and {@code ims/DLIGSAMP.PSB} L18.</p>
     *
     * <p>Trade-offs: the whole pass is ONE unit of work and the sends happen inside it, so the claiming
     * update's row locks AND the database connection are both held from the first claim until after the
     * last send of the pass returns. That is the cost, and it is stated plainly because it is what sizes
     * a connection pool: a publisher with a slow or unreachable queue occupies one connection for the
     * whole of its retry budget. What it buys is that a second publisher BLOCKS on the row and then
     * finds the claim token stale and skips it, instead of observing the row as pending and sending a
     * reply that is already in flight. The second exposure is that a send which succeeds in a pass that
     * then fails to commit leaves the row pending and it is sent again; the deduplication identity makes
     * that a suppressed duplicate ONLY within the queue's five-minute deduplication interval, so a
     * retried send after a longer outage reaches the requester twice and the requester suppresses it by
     * the transaction identifier the reply carries.</p>
     *
     * <p>Assumptions: the claim is per ORDERING GROUP -- one head row per group -- and that is what
     * preserves per-card order rather than an incidental effect of grouping. A first-in-first-out
     * queue orders messages within a group only after it accepts them, so the sequence of send calls
     * is the delivered sequence; a pass holding two rows of one group whose FIRST send failed and
     * carrying on to the second would place a newer reply ahead of an older one for the same card,
     * which is the single guarantee the group identity exists to provide.</p>
     *
     * <p>Assumptions: a send failure is recorded on its row and the drain CONTINUES with the next
     * group rather than propagating, and it never advances the group that failed. Propagating would
     * roll back the attempt counts of every row already handled in this batch, so one unreachable
     * reply queue would erase the evidence of every other group's progress; advancing the failed
     * group would be the reordering this method exists to prevent.</p>
     *
     * <p>Assumptions: the configured batch size bounds the ROWS this pass handles in total, not merely
     * the groups it starts from. The head claim consumes one unit of that budget per group it takes, and
     * whatever remains is shared across the follow-on claims of those groups in the order they were
     * claimed; when as many groups are pending as the budget allows, no follower is claimed at all and
     * each of those groups advances by one reply this pass. Refactoring Rationale: the budget is threaded
     * through because the follow-on loop had NONE. Its own documentation stated that the follow-on claim
     * was "bounded by the same batch size as the head claim", and nothing bounded it: the loop advanced
     * one group for as long as that group had pending rows, so a single card with a large backlog drained
     * all of it inside one transaction -- holding the connection and the row locks of the whole backlog
     * for however long it took, which is the opposite of the bounded-batch shape the reference works in
     * and the opposite of what the sentence promised.</p>
     *
     * <p>Trade-offs: spending the budget on BREADTH first -- one row of every pending group before any
     * second row of any group -- rather than draining each group fully in turn. Draining fully in turn
     * was the alternative and it starves: one busy card would take the entire budget and the replies of
     * every other card would wait however many passes that took, while the requester of each is holding
     * a five-second deadline. Breadth-first bounds the wait of every group by the drain interval and
     * costs a busy group more passes to clear, which is the direction the deadline argues for.</p>
     *
     * @return how many replies were published in this pass, which a caller may use to decide whether
     *     to drain again immediately
     * @throws org.springframework.dao.DataAccessException if the claiming statement itself cannot be
     *     executed, for instance because the connection's search path does not resolve the table;
     *     a failure to SEND is recorded on the row instead and never propagates
     */
    @Scheduled(fixedDelayString = "${carddemo.messaging.outbox-poll-interval-ms:1000}")
    public int drain() {
        List<AuthReplyOutbox> heads = this.shortTransaction.execute(status -> this.outbox
                .claimGroupHeads(this.batchSize, now(), leaseUntil(), this.maxAttempts));
        if (heads == null || heads.isEmpty()) {
            return 0;
        }
        int passBudget = this.maxRowsPerDrain;
        int published = 0;
        // WHY : Assumptions: the heads already claimed count against the pass budget, so the remainder
        //       is what the follow-on claims may spend. A saturated pass -- as many pending groups as the
        //       budget allows -- therefore leaves nothing for followers, which is the correct answer
        //       rather than a degenerate one: every pending group has already been advanced by one.
        int followerBudget = this.batchSize - heads.size();
        for (AuthReplyOutbox head : heads) {
            if (passBudget <= 0) {
                // WHY : Assumptions: the remaining heads of this pass are already CLAIMED and their
                // leases will lapse, so abandoning the loop here defers them rather than losing them.
                // Breaking is preferred to never claiming them because the head claim is one statement
                // whose cost does not depend on how many of its rows this pass then goes on to publish.
                LOG.info("event=auth.reply.drain-budget-exhausted maxRowsPerDrain={}",
                        this.maxRowsPerDrain);
                break;
            }
            GroupProgress progress = publishGroupFrom(head,
                    Math.min(this.perGroupRowBudget, passBudget));
            published += progress.published();
            passBudget -= progress.handled();
        }
        return published;
    }

    /**
     * How much of one ordering group a single drain pass got through.
     *
     * <p>Assumptions: HANDLED and PUBLISHED are separate counts because they answer different
     * questions, and collapsing them would break one of the two. Handled is every row this pass reached
     * a decision about -- sent, retired, abandoned or failed -- and is what the pass budget is spent
     * from, because each of those cost a claim and a transaction. Published is only what reached the
     * wire, and is what the caller returns so a scheduler can tell whether the queue is moving. A
     * budget spent from the published count alone would let a group of expiring or failing rows consume
     * an unbounded number of claims while reporting no progress, which is the exact shape of the
     * unboundedness this record exists to close.</p>
     *
     * @param handled how many rows of the group this pass reached a decision about
     * @param published how many of those were put on the wire
     */
    private record GroupProgress(int handled, int published) {
    }

    /**
     * Samples the current instant, freshly, in coordinated universal time.
     *
     * <p>Refactoring Rationale: every caller samples through this method at the moment it needs the
     * value, rather than one instant being taken at the start of a pass and reused. An earlier revision
     * did the latter, and it made two separate claims untrue: a reply whose deadline fell DURING the
     * pass was judged against an instant before its expiry and so was sent to a requester that had
     * already stopped waiting, consuming the deduplication identifier a legitimate retry would need;
     * and every row completed in the pass recorded the same publication instant, which is a false
     * timestamp for all but the first and destroys the ordering evidence an operator reads the column
     * for.</p>
     *
     * @return the current instant as a local date-time in coordinated universal time, never
     *     {@code null}
     */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC);
    }

    /**
     * Computes the instant a row claimed right now is leased until.
     *
     * <p>Assumptions: the lease is measured from a FRESH sample rather than from a pass-wide one, for
     * the same reason the readiness sample is: a lease computed from a stale instant is shorter than
     * configured by however long the pass has already run, and at the limit is already expired when it
     * is written, which would hand the row to a concurrent publisher while this one is still sending
     * it.</p>
     *
     * @return the instant until which a row claimed now is owned by this pass, never {@code null}
     */
    private LocalDateTime leaseUntil() {
        return now().plus(this.claimLease);
    }

    /**
     * Publishes one ordering group in order, starting at its head row and stopping at the first
     * failure.
     *
     * <p>Assumptions: the group is advanced only while rows reach a terminal state. The loop
     * re-claims the group's next row after each success, so a group with a backlog drains within one
     * pass rather than one reply per poll interval, while a failure leaves every row behind the
     * failed one untouched and still pending -- the next pass starts at the same head, so the group's
     * order cannot be broken by a retry.</p>
     *
     * <p>Assumptions: an EXPIRED row is retired rather than sent, and the group still advances past
     * it, because retiring it is terminal for that row: the requester has stopped waiting, so nothing
     * about the replies behind it is made out of order by moving past it. A retired row is not
     * counted as published, because nothing was put on the wire.</p>
     *
     * <p>Trade-offs: the follow-on claim is bounded by the same batch size as the head claim, so one
     * busy card cannot monopolise a pass indefinitely and its remaining rows are taken by the next
     * drain. A larger bound would drain a hot group sooner while holding that group's rows leased for
     * longer.</p>
     *
     * <p>Assumptions: the current instant is read ONCE PER ROW, immediately before that row's deadline is
     * judged, and it used to be read once for the whole pass and passed to every row. Refactoring
     * Rationale: a pass is not instantaneous -- each row costs a network send, and a failing send costs a
     * retry with a delay -- so one instant captured at the start becomes progressively staler as the pass
     * runs. Two consequences followed. A reply whose five-second deadline passed WHILE the pass was
     * working was judged live against the stale instant and sent to a requester that had stopped waiting,
     * consuming its deduplication identity so that a legitimate retry would be suppressed. And the
     * publication instant stamped on a successful row was the pass's start rather than the send's
     * completion, so the durable record of when a reply was published was wrong by the length of the
     * pass -- which is precisely the interval an operator reconstructing a latency complaint measures.</p>
     *
     * @param head the already-claimed head row of the group; must not be {@code null}
     * @param groupBudget the greatest number of rows of this group this pass may reach a decision
     *     about, already reduced to whatever remains of the pass budget
     * @return how many rows of this group this pass handled and how many of those reached the wire
     * @throws org.springframework.dao.DataAccessException if a follow-on claim cannot be executed
     */
    private GroupProgress publishGroupFrom(AuthReplyOutbox head, int groupBudget) {
        int published = 0;
        int handled = 0;
        AuthReplyOutbox row = head;
        while (row != null) {
            // WHY : Assumptions: staleness is judged against an instant sampled HERE, once per row,
            // rather than once per pass. A pass that publishes many rows takes real time, so a row
            // whose deadline falls part-way through it must be judged against the clock as it stands
            // when its turn comes -- otherwise it is sent to a requester that has already stopped
            // waiting and its deduplication identifier is spent on an answer nobody reads.
            boolean expired = row.isExpiredAsOf(now());
            handled++;
            if (!handleOne(row, expired)) {
                // WHY : Assumptions: the group stops HERE and its later rows are left pending.
                // Returning rather than continuing is what makes the ordering guarantee hold under
                // failure: the only reply that may follow this one is the one still waiting for it.
                return new GroupProgress(handled, published);
            }
            if (!expired) {
                published++;
            }
            if (handled >= groupBudget) {
                // WHY : Assumptions: the group yields at its budget with rows still pending, and the
                // next pass resumes it from the same head. Refactoring Rationale: an earlier revision
                // had no such stop -- it re-claimed the group's next row until the group ran dry -- so
                // the configured batch size bounded only how many GROUPS a pass opened and not how
                // much work it did, and one card with a large backlog held the publisher for an
                // unbounded number of claims and sends while every other claimed head waited behind
                // it. Yielding costs a hot group some latency and buys a pass whose duration is
                // bounded by configuration rather than by the backlog it happens to meet.
                LOG.info("event=auth.reply.group-budget-reached handled={} groupBudget={}",
                        handled, groupBudget);
                return new GroupProgress(handled, published);
            }
            row = nextInGroup(row);
        }
        return new GroupProgress(handled, published);
    }

    /**
     * Claims the next pending row of one ordering group above the identity just handled.
     *
     * <p>Assumptions: the bound is the identity just handled rather than a re-read of the group's
     * head, which is what keeps this call independent of when this pass's pending in-memory changes
     * reach the database. A row already handled sits at or below that bound and cannot be a candidate
     * whatever its stored state currently says, so the same row cannot be handed back twice within
     * one pass.</p>
     *
     * @param handled the row just published or retired; must not be {@code null}
     * @return the next pending row of the same group, or {@code null} when the group holds none
     * @throws org.springframework.dao.DataAccessException if the claiming statement cannot be
     *     executed
     */
    private AuthReplyOutbox nextInGroup(AuthReplyOutbox handled) {
        List<AuthReplyOutbox> followers = this.shortTransaction.execute(status -> this.outbox
                .claimGroupFollowers(handled.getOrderGroupId(), handled.getOutboxId(), 1, now(),
                        leaseUntil(), this.maxAttempts));
        return followers == null || followers.isEmpty() ? null : followers.get(0);
    }

    /**
     * Retires an expired reply or publishes a live one, and reports whether the group may advance.
     *
     * <p>Assumptions: staleness is judged once by the caller and passed in rather than re-tested
     * here, so the decision that retires a row and the decision that declines to count it as
     * published cannot disagree about the same instant.</p>
     *
     * @param row the already-claimed row; must not be {@code null}
     * @param expired whether the caller judged this row's deadline to have passed at the instant it
     *     sampled for this row
     * @return {@code true} when this row reached a terminal state, so its group may advance
     */
    private boolean handleOne(AuthReplyOutbox row, boolean expired) {
        if (expired) {
            retire(row);
            return true;
        }
        return publishReply(row);
    }

    /**
     * Retires a reply whose deadline passed before it could be sent, leaving the reason on its row.
     *
     * <p>Trade-offs: an expired reply is retired rather than sent. Sending it would deliver an answer
     * the requester has stopped waiting for and would consume the deduplication identifier, so a
     * legitimate retry of the same transaction would then be suppressed as a duplicate of an answer
     * nobody read. Marking it published rather than deleting it keeps the row auditable and keeps it
     * out of the pending index, which is the same index predicate the claim uses.</p>
     *
     * <p>Refactoring Rationale: retirement is now a SINGLE call on the row rather than a publication
     * mark followed by a failure record, and the attempt counter is not advanced by it. The pair it
     * replaces was order-dependent -- the publication mark CLEARS the diagnostic, so only one of the two
     * orders produced the intended row -- and it also incremented the counter a second time on a path
     * that made no attempt at all.</p>
     *
     * @param row the already-claimed row whose deadline has passed; must not be {@code null}
     */
    private void retire(AuthReplyOutbox row) {
        transition(row, (stored, at) -> stored.retire(at, RETIREMENT_REASON));
        LOG.warn("event=auth.reply.expired outboxId={} attempts={}", row.getOutboxId(),
                row.getAttempts());
    }

    /**
     * Applies one terminal or retryable transition to a claimed row in its own short transaction, only
     * if this pass still owns it.
     *
     * <p>Refactoring Rationale: the transition is a SEPARATE, SHORT unit of work that begins after the
     * network send has returned, and the row is re-read inside it. An earlier revision made the whole
     * pass -- claims, sends and transitions -- one transaction, so a database connection and the write
     * locks taken by every claim were held for the duration of every synchronous send and of the retry
     * budget behind it. At a full batch that pinned one pooled connection across many seconds of
     * network work and blocked any concurrent publisher on rows it was not going to reach for most of
     * that time. Splitting it means a connection is held only for the claim and for this transition,
     * and the send holds none.</p>
     *
     * <p>Assumptions: the transition is CONDITIONAL on the row still carrying the attempt count this
     * pass claimed it at, and on its still being neither published nor abandoned. That is what makes
     * the split safe. If this pass's lease lapsed -- because it was slow, or because the process was
     * restarted mid-send -- another publisher may have re-claimed the row, which incremented the
     * counter; writing the outcome of the older send would then overwrite a newer attempt's state. The
     * guard makes the late write a no-op that is logged instead, which is the correct outcome: the
     * reply's fate belongs to whichever pass currently owns it.</p>
     *
     * <p>Trade-offs: a send that succeeded in a pass whose transition is refused leaves the row for the
     * current owner to send again. The deduplication identifier makes that a suppressed duplicate
     * rather than a second answer, which is the same trade the table's own schema comment records for
     * the send-then-mark ordering.</p>
     *
     * @param claimed the row as this pass claimed it, carrying the attempt count the guard compares;
     *     must not be {@code null}
     * @param change what to apply to the stored row, given a freshly sampled instant; must not be
     *     {@code null}
     */
    private void transition(AuthReplyOutbox claimed,
            BiConsumer<AuthReplyOutbox, LocalDateTime> change) {
        Boolean applied = this.shortTransaction.execute(status -> {
            AuthReplyOutbox stored = this.outbox.findById(claimed.getOutboxId()).orElse(null);
            if (stored == null || stored.isPublished() || stored.isAbandoned()
                    || !Objects.equals(stored.getAttempts(), claimed.getAttempts())) {
                return Boolean.FALSE;
            }
            // WHY : Assumptions: the instant is sampled INSIDE this transaction, so a completion
            // records when it actually completed rather than when its pass started. The publication
            // column is the only ordering evidence an operator has for what the publisher did and in
            // what sequence, and a pass-wide instant makes every row after the first carry a time at
            // which nothing happened to it.
            change.accept(stored, now());
            this.outbox.save(stored);
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(applied)) {
            LOG.warn("event=auth.reply.transition-superseded outboxId={} claimedAttempts={}",
                    claimed.getOutboxId(), claimed.getAttempts());
        }
    }

    /**
     * Deletes replies whose retention window has elapsed, published rows only.
     *
     * <p>Assumptions: the deletion predicate requires a publication instant to be PRESENT, so a
     * pending reply is never removed however old it is. Deleting one would lose an answer the
     * committed decision says was produced, which is the very window the outbox exists to close, so
     * that restriction is the whole safety property of this method and it lives on the repository
     * statement rather than being re-expressed here.</p>
     *
     * <p>Alternatives Considered: folding this sweep into the migrated expiry job of
     * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl}, so that one scheduled job owned all
     * of this schema's retention. It is done here instead because the two sweeps have different
     * subjects and different safe cut-offs -- that job expires PENDING AUTHORIZATIONS by business age
     * while this one removes PUBLISHED REPLIES by operational age -- and because binding this table's
     * growth to a job with a different subject would leave one of the two cut-offs wrong.</p>
     *
     * <p>Trade-offs: the sweep runs on a longer fixed delay than the drain, because retention is
     * measured in days and a frequent pass would issue a delete that matched nothing. The window and
     * the interval are both configured, so an operator investigating a reply can widen the window
     * without a rebuild.</p>
     *
     * @return how many published replies were deleted, which a caller may log or assert on
     * @throws org.springframework.dao.DataAccessException if the delete cannot be executed, for
     *     instance because the runtime role holds no delete privilege on the table
     */
    @Scheduled(fixedDelayString = "${carddemo.messaging.outbox-retention-sweep-interval-ms:3600000}")
    public int purgePublished() {
        LocalDateTime cutoff = now().minusDays(this.retentionDays);
        int deleted = 0;
        // WHY : Refactoring Rationale: the sweep is a LOOP of bounded transactions rather than one
        // statement, and it stops as soon as a chunk comes back short. An earlier revision deleted
        // every row matching the cut-off in a single transaction, which is unbounded by construction --
        // the published side of this table is the part that grows without limit -- so a sweep that had
        // not run for a while, or a widened retention window, took a table-wide lock footprint and a
        // transaction whose duration nothing bounded. Alternatives Considered: keeping the single
        // statement and relying on the sweep running often enough that its population stays small;
        // rejected because that makes a correctness property depend on an interval an operator may
        // lengthen, and because the first run after any outage is precisely when the population is
        // largest. Trade-offs: the sweep issues more statements and is not atomic across chunks, so a
        // failure part-way leaves some rows deleted; that is harmless here because each row's deletion
        // is independent and the next sweep resumes with the same predicate.
        for (int pass = 0; pass < this.maxPurgeChunksPerSweep; pass++) {
            Integer removed = this.shortTransaction
                    .execute(status -> this.outbox.deletePublishedBefore(cutoff, this.purgeChunkSize));
            int chunk = removed == null ? 0 : removed;
            deleted += chunk;
            if (chunk < this.purgeChunkSize) {
                break;
            }
        }
        if (deleted > 0) {
            LOG.info("event=auth.reply.retention-swept deleted={} retentionDays={}", deleted,
                    this.retentionDays);
        }
        return deleted;
    }

    /**
     * Publishes one reply, retrying only a transient transport fault, and records the outcome.
     *
     * <p>Assumptions: the retry is applied through the framework's programmatic template rather than
     * through its annotation, and the reason is mechanical. The annotation is applied by a bean
     * post-processor proxy, and this method is reached by SELF-INVOCATION from within the same
     * object, which never passes through that proxy -- so an annotation here would be inert while
     * reading as a configured budget. The template applies the identical policy from the same
     * framework core and does take effect. Nothing external is added to the classpath by either
     * form.</p>
     *
     * <p>Assumptions: whichever attempt fails last, the original unchecked transport exception is the
     * one that reaches the handler below, so the recorded diagnostic names the actual fault rather
     * than a retry wrapper.</p>
     *
     * <p>Assumptions: a row that cannot even be PROJECTED into a publication is treated exactly like
     * a failed send: the reason is recorded and its group stops. That is deliberate rather than
     * incidental. Every row reaching this table was assembled through the same projection, so an
     * unprojectable row implies the stored data was changed outside the application, and stalling one
     * card's replies while the attempt counter and the diagnostic column make it visible is safer
     * than stepping over it and delivering that card's later replies out of order.</p>
     *
     * @param row the already-claimed outbox row; must not be {@code null}
     * @return {@code true} when the reply was put on the wire, {@code false} when the attempt failed
     *     and its group must not advance
     */
    private boolean publishReply(AuthReplyOutbox row) {
        try {
            SendMessageRequest request = requestFor(row);
            // WHY : Assumptions: the lambda is written as a BLOCK that discards the send's result,
            // which selects the template's void overload. An expression lambda would be compatible
            // with both the void and the value-returning overload and so would not compile at all;
            // the result carries only the transport's own message identifier, which nothing here
            // records, so discarding it loses nothing.
            SEND_RETRIES.invoke(() -> {
                // WHY : Assumptions: the attempt counter is NOT advanced here. It is advanced once by
                // the claiming statement, so one pass over one row is one attempt however many times
                // the transport is retried inside it. Counting per transport call instead would burn a
                // permanently unreachable queue's whole ceiling in a handful of passes and abandon
                // replies that were never given the tries the configuration promises -- which is the
                // property OutboxPublisherLifecycleRepositoryIT asserts against a real engine.
                this.sqs.sendMessage(request);
            });
            transition(row, AuthReplyOutbox::markPublished);
            LOG.info("event=auth.reply.published outboxId={} attempts={}", row.getOutboxId(),
                    row.getAttempts());
            return true;
        } catch (RuntimeException failure) {
            // WHY : Assumptions: the recorded reason is the exception's CLASS NAME and never its
            // message. A client failure message can embed the request it was building, and this
            // row's payload carries a primary account number, so the message is the one part that
            // must not be persisted into a column an operator reads. The class name names the fault
            // without carrying the data, which is the package-wide discipline for this service.
            String reason = failure.getClass().getName();
            if (row.getAttempts() >= this.maxAttempts) {
                // WHY : Assumptions: a row that has used its whole attempt budget is ABANDONED rather
                // than failed again, and this is the only place the terminal state is entered.
                // Refactoring Rationale: an earlier revision had no terminal state at all, so a reply
                // whose queue was permanently unreachable -- a deleted queue, a revoked permission --
                // was retried forever, held its group's head position forever, and blocked every later
                // reply for that card. Trade-offs: abandonment gives up on an answer the committed
                // decision says was owed, which is why the row is retained with its diagnostic rather
                // than deleted, is excluded from the retention sweep, and is logged at error.
                transition(row, (stored, at) -> stored.abandon(at, reason));
                LOG.error("event=auth.reply.abandoned outboxId={} attempts={} maxAttempts={} fault={}",
                        row.getOutboxId(), row.getAttempts(), this.maxAttempts, reason);
                // WHY : Assumptions: the group does NOT advance past an abandoned row, so this returns
                // false. Advancing would deliver that card's later replies with a gap where the
                // abandoned one belongs, and the whole reason a group exists is that its replies are
                // only meaningful in order; a stalled card that an operator must look at is the
                // intended outcome, and the error line above is how they learn to.
                return false;
            }
            LocalDateTime retryAt = backoffFrom(row.getAttempts());
            transition(row, (stored, at) -> stored.recordFailure(reason, retryAt));
            LOG.error(
                    "event=auth.reply.publish-failed outboxId={} attempts={} nextAttemptAt={} fault={}",
                    row.getOutboxId(), row.getAttempts(), retryAt, reason);
            return false;
        }
    }

    /**
     * Computes when a row that has just failed its nth attempt becomes eligible again.
     *
     * <p>Assumptions: the delay grows with the attempt count and is capped, so a transport that is
     * briefly unavailable is retried quickly while one that is durably unavailable stops being polled
     * every interval. Refactoring Rationale: without a backoff the failed row stayed immediately
     * eligible, and because the claim selected the globally oldest pending rows a small number of
     * permanently failing heads were re-selected on every poll and every healthy group behind them was
     * starved -- the failing rows never stopped being the oldest, so retrying could not clear it.</p>
     *
     * <p>Trade-offs: the growth is a doubling of the base delay, computed with a SHIFT bounded by the
     * exponent that keeps it inside a long, and then clamped to the configured ceiling. Computing it
     * with an unbounded shift was rejected because a large attempt count would shift past the width of
     * the type and produce a small or negative delay -- the exact failure the widened counter was
     * introduced to remove, reintroduced in the arithmetic.</p>
     *
     * @param attempts how many attempts the row has now had, as the claim recorded it; must be positive
     * @return the instant the row next becomes eligible, never {@code null}
     */
    private LocalDateTime backoffFrom(int attempts) {
        int exponent = Math.min(Math.max(attempts - 1, 0), MAX_BACKOFF_DOUBLINGS);
        Duration delay = this.retryBackoff.multipliedBy(1L << exponent);
        if (delay.compareTo(this.maxRetryBackoff) > 0) {
            delay = this.maxRetryBackoff;
        }
        return now().plus(delay);
    }

    /**
     * Builds the send request for one reply from the publication its row describes.
     *
     * <p>Assumptions: the payload is OPAQUE here. It arrives already encoded, and this class neither
     * measures, trims, pads nor re-derives it -- it sends the characters it was given. That is why
     * this class deliberately does NOT depend on {@code com.carddemo.common.codec.CsvAuthCodec},
     * which is the type that would let it produce a payload of its own. The reason is concrete: the
     * encoded reply was committed inside the deciding transaction, so a publisher that re-derived the
     * bytes could disagree with the bytes that were committed and would publish an answer the stored
     * decision does not account for -- reintroducing exactly the divergence the outbox exists to
     * remove. A second, independent reason is that no single wire length is settled: the reference
     * emitter appends a delimiter after the sixth item as well as between the pairs, and its length
     * field is declared with a non-zero initial value at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L46 and moved straight to the buffer
     * length at L756, so a length check here would have to adopt one of two defensible figures and
     * would reject a correctly-encoded reply whenever it picked the other.</p>
     *
     * <p>Assumptions: the destination is taken PER MESSAGE from the row and never from
     * configuration, mirroring the reference, which sets the reply object name from the queue that
     * request nominated at L741 to L742 after saving it at L413 to L414 from a field declared
     * {@code PIC X(48)} at L44. Its put is {@code MQPUT1} at L758 -- open, put and close in one
     * operation -- which is precisely why per-request routing needs no pre-opened handle there; a
     * send request naming its own queue achieves the same per-message destination selection here.
     * The value stored on the row was already checked against the configured allowlist by
     * {@link AuthorizationRequestListener} before the decision was committed, so honouring it here
     * needs no second policy decision.</p>
     *
     * <p>Assumptions: the correlation identity is echoed UNCHANGED, matching L745, which moves the
     * saved inbound value onto the reply descriptor exactly as it arrived from L411 to L412 -- the
     * get pre-setting {@code MQMI-NONE} and {@code MQCI-NONE} at L395 to L396 so that it filters on
     * neither. It is the only thing pairing an answer with its question, so it is neither derived nor
     * defaulted; an absent identity means the requester sent none, which is why the attribute is
     * omitted rather than sent empty and why the question is asked through the publication's own
     * accessor instead of being re-tested at each send.</p>
     *
     * <p>Assumptions: the ordering and deduplication identities are the CARD NUMBER and the acquirer's
     * TRANSACTION IDENTIFIER, read from the row rather than recomputed here. Sections 0.4.1.8 and 0.7.6
     * of the technical specification freeze them as those literals, and reading them from the row keeps
     * the identity a reply is published under identical to the one committed with the decision.</p>
     *
     * <p>Refactoring Rationale: both were purpose-scoped keyed tokens derived from those values. The
     * derivation preserved each semantic within this one publisher -- equal for equal cards, equal for
     * one authorization -- but it changed the identity every OTHER party computes, so a second publisher,
     * a cross-account consumer or a replay tool written to the frozen contract would group one card's
     * messages separately and would fail to recognise a duplicate of one authorization. Aligning to the
     * frozen identities is what makes the queue's guarantees hold across producers rather than only
     * within this one.</p>
     *
     * <p>Trade-offs: a group identifier and a deduplication identifier are message METADATA, which the
     * queue's server-side encryption of a body does not cover, so the card number reaches queue telemetry
     * and the trace of every send. What bounds that is the deployment -- customer-managed-key encryption,
     * an interface endpoint inside the private network, and task-role-scoped read access -- and the
     * judgement that the frozen identity is worth it belongs to the specification rather than to this
     * class.</p>
     *
     * <p>Assumptions: three descriptor fields the reference sets have no counterpart to set here, and
     * they are named so their absence is not read as an omission. L744 moves {@code MQMT-REPLY} into
     * a message-type field the target transport does not model -- the reply-versus-request
     * distinction is carried instead by the destination being the requester's own reply queue. L746
     * asks for a FRESH message identifier, which the transport assigns of its own accord. L749 asks
     * for non-persistent delivery, whose analogue is the short reply-queue retention provisioned in
     * {@code infra/modules/sqs} rather than anything on an individual send. L747 to L748 blank the
     * reply's own reply-to fields to make it terminal, and a reply built here carries none.</p>
     *
     * <p>Trade-offs: one short-lived projection object is allocated per reply. That is accepted
     * because the send it precedes is a network call, so the allocation is not measurable beside it,
     * and because the projection re-checks that every column a message requires is populated --
     * moving a fault that would otherwise be a rejected send to the moment the row is read.</p>
     *
     * @param row the already-claimed outbox row; must not be {@code null}
     * @return the send request naming that row's own destination, never {@code null}
     * @throws NullPointerException if a column the publication requires is unpopulated on the row
     * @throws IllegalArgumentException if a column on the row is blank or wider than the publication
     *     admits
     */
    private SendMessageRequest requestFor(AuthReplyOutbox row) {
        OutboxMessage publication = OutboxMessage.from(row);
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(ATTRIBUTE_CONTENT_TYPE, stringAttribute(publication.contentType()));
        if (publication.hasCorrelationId()) {
            attributes.put(ATTRIBUTE_CORRELATION_ID, stringAttribute(publication.correlationId()));
        }
        if (publication.expiresAt() != null) {
            attributes.put(ATTRIBUTE_EXPIRES_AT, stringAttribute(publication.expiresAt().toString()));
        }
        return SendMessageRequest.builder()
                .queueUrl(publication.replyQueueUrl())
                .messageBody(publication.payload())
                .messageGroupId(publication.orderGroupId())
                .messageDeduplicationId(publication.deduplicationId())
                .messageAttributes(attributes)
                .build();
    }

    /**
     * Wraps a value as a string message attribute.
     *
     * @param value the attribute value; must not be {@code null}
     * @return the attribute carrying that value, never {@code null}
     */
    private MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder()
                .dataType(ATTRIBUTE_TYPE_STRING)
                .stringValue(value)
                .build();
    }

    /**
     * Reports whether a send failure is one of the three transient categories worth reattempting.
     *
     * <p>Assumptions: the classification is deliberately NARROW because the reference's own is. Its
     * transient set is {@code 88 RETRY-CONDITION VALUE 'BA', 'FH', 'TE'.} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} L87, repeated one line apart at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl} L88 -- three of the SEVEN non-ok
     * statuses declared in that same block. The four data conditions at L80 to L83, segment-not-found
     * and duplicate and wrong-parentage and end-of-database, are excluded there because they would
     * return the same answer however many times they were attempted, and so are their analogues here:
     * every client-side service fault, which is a malformed request or an unauthorised send or a
     * destination that does not exist. The three admitted below are the counterparts of the three the
     * reference admits -- the service reporting itself overloaded, the service attributing a fault to
     * its own side, and a call that never reached a service at all.</p>
     *
     * @param failure the exception a send attempt threw; never {@code null} as the framework supplies
     *     the thrown value
     * @return {@code true} when the fault is transient and one further attempt is worth making
     */
    private static boolean isTransientTransportFault(Throwable failure) {
        if (failure instanceof SdkServiceException service) {
            // WHY : Assumptions: this branch covers the whole service side, because the AWS-specific
            // and queue-specific exceptions both extend this type. Transience is a PROPERTY the
            // service reports rather than a distinct class, which is why the test reads the throttling
            // flag and the status band instead of naming types: a 5xx is the service attributing the
            // fault to itself, whereas every 4xx is a statement about the request that would be
            // repeated identically on a second attempt.
            return service.isThrottlingException() || service.statusCode() >= 500;
        }
        // WHY : Assumptions: a client exception means the call did not reach the service at all -- a
        // connection reset, a socket timeout, a DNS failure -- so the request itself is unjudged and
        // one further attempt can legitimately succeed. Any other runtime failure is a defect in this
        // process rather than a transport condition, and repeating it would only delay recording it.
        return failure instanceof SdkClientException;
    }
}
