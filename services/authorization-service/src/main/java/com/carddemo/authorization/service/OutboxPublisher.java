package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.domain.OutboxMessage;
import com.carddemo.authorization.repository.OutboxRepository;
import com.carddemo.common.messaging.MessageExpiry;
import com.carddemo.common.observability.FailureSummary;
import com.carddemo.common.observability.ThrowableDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
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
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

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
     * The placeholder logged in place of a broker-assigned value the transport did not return.
     *
     * <p>Assumptions: an absent value is named rather than rendered as {@code null}, so a reader of a
     * publication line can tell "the transport returned nothing here" from "this field was never
     * formatted". The sequence number is the field this is expected for: the transport assigns one only
     * to an ordered queue, and a standard queue leaves it unset.</p>
     */
    private static final String ABSENT_BROKER_VALUE = "(absent)";

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
     * <p>Refactoring Rationale: the pass is NOT one unit of work, and this paragraph said it was. Every
     * database touch here runs in its OWN short transaction -- the head claim, each follower claim and each
     * publication, failure or abandonment record -- so no row lock and no connection is held across a send. The
     * withdrawn sentence went on to size a connection pool from a claim that was false, which is worse than
     * saying nothing: a reader would have provisioned for one connection per publisher for the whole of a
     * retry budget, and would have believed a second publisher blocks on the row.</p>
     *
     * <p>Assumptions: single delivery therefore rests entirely on the token comparison and the lease, not on
     * anything held. A concurrent pass that observed the same candidate finds the attempt count already moved
     * and receives the row not at all, and a publisher killed mid-send strands its group only until the lease
     * lapses.</p>
     *
     * <p>Trade-offs: because each row commits on its own, a send that succeeds and whose recording then fails
     * leaves that row pending and it is sent again. The deduplication identity makes that a suppressed
     * duplicate ONLY within the queue's five-minute deduplication interval, so a retried send after a longer
     * outage reaches the requester twice and the requester suppresses it by the transaction identifier the
     * reply carries. What the per-row boundary buys is that one unreachable queue costs one row's progress
     * rather than the whole pass's, and that the connection is free between rows.</p>
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
     * <p>Assumptions: the pass budget bounds the ROWS this pass handles in total, not merely the groups it
     * starts from, and it is spent one unit per row-turn whatever that turn produced. The head round spends
     * one unit per claimed group; whatever remains is then spent by the follower rotation.</p>
     *
     * <p>Refactoring Rationale: the handling is BREADTH-first and it previously was not, although this
     * paragraph described it as though it were. Every claimed head is now handled once, in claim order,
     * before any group advances a second time; the withdrawn shape reached the first head and drained that
     * group up to a per-group share before touching the second head at all. With the shipped defaults --
     * twenty-five groups per claim and five hundred rows per pass, giving a share of twenty -- the last head
     * of a saturated pass waited behind up to four hundred and eighty row-turns, each a claim, a send and a
     * short transaction, while its requester held a five-second deadline. A dead {@code followerBudget}
     * local sat beside the loop as the only surviving trace of the intended shape.</p>
     *
     * <p>Assumptions: the followers spend a GLOBAL remainder round-robin -- one row per surviving group per
     * round -- rather than each group spending a private share. A private share both starves the later groups
     * of a saturated pass and wastes the shares of groups that turned out to hold a single row.</p>
     *
     * <p>Trade-offs: breadth-first costs a busy group more passes to clear, and that is the direction the
     * deadline argues for. Draining each group fully in turn was the alternative and it starves: one busy
     * card would take the entire budget and the replies of every other card would wait however many passes
     * that took. The per-group ceiling is retained on top of the rotation, so one very long backlog cannot
     * hold the rotation open against a group arriving in a later claim.</p>
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

        // WHY : Refactoring Rationale: the pass handles every claimed HEAD before it advances any group a
        //       second time, and it previously drained each group in turn up to a per-group budget before
        //       reaching the next head. The documentation described breadth-first handling and the code was
        //       depth-first: with the shipped defaults -- twenty-five groups per claim and five hundred rows
        //       per pass, giving a per-group share of twenty -- the FIRST claimed group could reach a decision
        //       about twenty rows before the second claimed head was touched at all. Each of those decisions
        //       is a claim, a send and a short transaction, so the last head of a saturated pass waited behind
        //       the whole of that work while its requester held a five-second deadline. A dead
        //       `followerBudget` local sat beside the loop as the only trace of the intended shape; it is
        //       gone, and the shape is now in the loop.
        // WHY : Assumptions: the head round spends ONE unit of the pass budget per claimed group, in claim
        //       order, and a group only enters the follower rotation once its head has been handled and it
        //       still has a row to offer. That is what makes the first row of the last claimed group arrive
        //       no later than the number of claimed groups, rather than no later than that number times the
        //       per-group share.
        Deque<GroupCursor> rotation = new ArrayDeque<>();
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
            boolean sent = handleRow(head);
            passBudget--;
            if (sent) {
                published++;
                AuthReplyOutbox follower = nextInGroup(head);
                if (follower != null) {
                    rotation.add(new GroupCursor(follower, 1));
                }
            }
        }

        // WHY : Assumptions: the followers spend a GLOBAL remainder round-robin -- one row per surviving
        //       group per round -- rather than each group spending a private share. A private share is what
        //       the withdrawn shape had, and it both starves the later groups of a saturated pass and wastes
        //       the shares of groups that turned out to hold a single row. One rotation over one remainder
        //       gives every surviving group its next row before any of them gets the row after that, which
        //       is the property the five-second reply deadline argues for.
        // WHY : Assumptions: the per-group cap still applies, as a CEILING on how much of one pass a single
        //       group may consume rather than as its allowance. Round-robin already bounds the wait of every
        //       group, so the cap is now only there to stop one very long backlog holding the rotation open
        //       against a group that arrives in a later claim.
        while (passBudget > 0 && !rotation.isEmpty()) {
            GroupCursor cursor = rotation.poll();
            boolean sent = handleRow(cursor.row());
            passBudget--;
            if (!sent) {
                continue;
            }
            published++;
            int handledInGroup = cursor.handledInGroup() + 1;
            if (handledInGroup >= this.perGroupRowBudget) {
                // WHY : Assumptions: the group leaves the rotation with rows still pending and the next pass
                // resumes it from its own head. Yielding costs a hot group some latency and buys a pass
                // whose duration is bounded by configuration rather than by the backlog it happens to meet.
                LOG.info("event=auth.reply.group-budget-reached handled={} groupBudget={}",
                        handledInGroup, this.perGroupRowBudget);
                continue;
            }
            AuthReplyOutbox follower = nextInGroup(cursor.row());
            if (follower != null) {
                rotation.add(new GroupCursor(follower, handledInGroup));
            }
        }
        return published;
    }

    /**
     * One surviving ordering group's position in the follower rotation.
     *
     * <p>Assumptions: the ROW is carried rather than only the group identity, because it is already claimed
     * and leased by this pass -- re-deriving it from the group would claim a second row and spend a second
     * transaction for a row this pass already owns.</p>
     *
     * <p>Assumptions: the count of rows already handled travels WITH the cursor rather than being held in a
     * map beside the rotation. The rotation is the only place a group exists during a pass, so keeping the
     * two together is what makes it impossible for a group to be re-queued while its count is left
     * behind.</p>
     *
     * @param row the claimed, leased row whose turn is next for this group; never {@code null}
     * @param handledInGroup how many rows of this group this pass has already reached a decision about
     */
    private record GroupCursor(AuthReplyOutbox row, int handledInGroup) {
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
     * Takes one row's turn: reports a passed deadline, sends the reply, and says whether it reached the
     * wire.
     *
     * <p>⚠️ Refactoring Rationale: a row whose deadline had passed used to be RETIRED here -- marked
     * published without ever being sent, with {@code "expired before publication"} left in its
     * diagnostic column -- and that is withdrawn outright. The arithmetic made it the normal outcome
     * rather than an exceptional one: the reference deadline this service stamps is five seconds
     * (<code>app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl</code> L750, denominated in tenths) and
     * the first retry backoff is also five seconds, so ANY reply that failed its first attempt was past
     * its deadline before its second attempt was due, and every one of them was retired unsent. The
     * observed effect was a row reading {@code published_at} populated with zero messages delivered:
     * the retention sweep, whose predicate is that same column, then deleted the evidence. That
     * inverts the guarantee this outbox exists for -- §0.4.3 of the technical specification states it
     * as "a reply is published for every committed authorization" -- and it silently converted a
     * transport outage into permanent, unrecorded reply loss.</p>
     *
     * <p>Assumptions: the deadline is a TRANSPORT attribute the consumer honours, not a publisher gate.
     * §0.7.6 and {@code docs/adr/ADR-004-messaging.md} both record the resolution that way: the queue
     * service has no per-message time to live, so the deadline travels as an attribute and the
     * RECEIVING end drops what has gone stale. A publisher that also refuses to send applies the same
     * rule twice, and the second application is the one that destroys the row. So a late reply is still
     * sent, and the lateness is reported instead -- a named warning an operator can alert on, carrying
     * the deadline that passed. The receiver remains free to discard it, which is where that judgement
     * belongs.</p>
     *
     * <p>Assumptions: {@link AuthReplyOutbox#isExpiredAsOf(java.time.LocalDateTime)} is retained and
     * still called, because the QUESTION it answers is worth asking even though the answer no longer
     * suppresses the send. It is judged against an instant sampled HERE, once per row rather than once
     * per pass, so a row whose deadline falls part-way through a long pass is described by the clock as
     * it stood when its turn came.</p>
     *
     * <p>Assumptions: a row that did not reach the wire stops its group, and after this change that is
     * the ONLY reason a group stops -- which is why the two-flag outcome record this used to return is
     * gone. The ordering guarantee the group identity provides is that the only reply which may follow
     * this one is the one still waiting for it, so a group whose send failed must not advance; with the
     * retirement arm withdrawn there is no longer any third case where a row fails to reach the wire and
     * yet its group may continue, and a record whose two components are always equal is a record that
     * invites them to be set inconsistently.</p>
     *
     * <p>Assumptions: every turn costs exactly ONE unit of the caller's pass budget whatever it produced,
     * so the budget is spent from the turns taken rather than from the rows published. A budget spent
     * from publications alone would let a group of failing rows consume an unbounded number of claims,
     * sends and short transactions while reporting no progress at all.</p>
     *
     * @param row the claimed, leased row whose turn it is; must not be {@code null}
     * @return {@code true} when the reply reached the wire, so its group may advance
     */
    private boolean handleRow(AuthReplyOutbox row) {
        if (row.isExpiredAsOf(now())) {
            // WHY : Assumptions: this is a WARNING and not an error, and it is emitted BEFORE the send
            // rather than instead of it. A late reply is a service-level observation -- the requester's
            // deadline elapsed while this reply waited -- and the reply is still owed, so the send
            // proceeds and the receiver decides. Trade-offs: a durable transport outage produces one of
            // these lines per attempt per row, which is noisier than a single terminal retirement line;
            // that is accepted because the alternative was silence about a reply that was never sent.
            LOG.warn("event=auth.reply.late outboxId={} attempts={} expiresAt={}", row.getOutboxId(),
                    row.getAttempts(), row.getExpiresAt());
        }
        return publishReply(row);
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
     * @param handled the row whose turn has just been taken; must not be {@code null}
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
            // WHY : Assumptions: a row the broker has ALREADY accepted is reconciled and never sent
            //       again. The acceptance instant is written by its own transaction immediately after
            //       the broker answers, so a row carrying one and no publication instant is a reply
            //       that reached the wire and whose status write did not land. Sending it again would
            //       enqueue a second copy of one authorization's reply as soon as the queue's
            //       five-minute deduplication window had passed -- the window expires and the row does
            //       not, which is why the decision is taken from the row.
            if (row.isSendAccepted()) {
                transition(row, AuthReplyOutbox::markPublished);
                LOG.warn(
                        "event=auth.reply.publish-reconciled outboxId={} attempts={} sentAt={} "
                                + "brokerMessageId={} brokerSequenceNumber={}",
                        row.getOutboxId(), row.getAttempts(), row.getSentAt(),
                        brokerValue(row.getBrokerMessageId()),
                        brokerValue(row.getBrokerSequenceNumber()));
                return true;
            }

            // WHY : Assumptions: the deadline is computed PER ATTEMPT, from the window the deciding
            //       transaction chose, and is recorded with the acceptance rather than before the send.
            //       Alternatives Considered: fixing it at the first attempt and reusing it, so that two
            //       attempts at one reply are byte-identical. Rejected because the first attempt may
            //       never have reached the broker at all -- a connect refusal is the commonest transport
            //       fault there is -- and a frozen deadline would then be stamped on a message sent
            //       after it had already passed, which docs/adr/ADR-004-messaging.md records as
            //       guaranteed non-delivery of a reply the committed decision says is owed. The defect
            //       that frozen stamping was meant to close is closed by the acceptance record instead:
            //       an accepted send is never sent a second time, so the two values cannot disagree
            //       except in the one window recorded on recordSendAccepted.
            LocalDateTime deadline = sendDeadlineFor(row);

            SendMessageRequest request = requestFor(row, deadline);
            // WHY : Assumptions: the send is wrapped in a Supplier held in a LOCAL rather than passed
            // as a lambda literal. The template overloads `invoke` on Supplier and on Runnable, and an
            // expression lambda returning a method-invocation result is compatible with both -- so
            // `invoke(() -> this.sqs.sendMessage(request))` is ambiguous and does not compile at all.
            // Refactoring Rationale: the previous form was a BLOCK lambda that discarded the result to
            // select the void overload, and the comment justifying it said the result "carries only the
            // transport's own message identifier, which nothing here records". That identifier is
            // exactly what was missing: a FIFO send whose deduplication identifier matches one the
            // broker already accepted is SUPPRESSED and answered with the identity of the message it
            // already holds, and the publication line then read the same either way. Naming the local
            // is what makes the value reachable without changing which overload is selected.
            Supplier<SendMessageResponse> send = () -> {
                // WHY : Assumptions: the attempt counter is NOT advanced here. It is advanced once by
                // the claiming statement, so one pass over one row is one attempt however many times
                // the transport is retried inside it. Counting per transport call instead would burn a
                // permanently unreachable queue's whole ceiling in a handful of passes and abandon
                // replies that were never given the tries the configuration promises -- which is the
                // property OutboxPublisherLifecycleRepositoryIT asserts against a real engine.
                return this.sqs.sendMessage(request);
            };
            SendMessageResponse accepted = SEND_RETRIES.invoke(send);
            // WHY : Assumptions: the acceptance is recorded in its OWN transaction, before the
            //       publication instant, and the two are deliberately not combined. Combining them
            //       would restore the defect: one transaction failing would leave no evidence of the
            //       accepted send, and the next pass would send again. Split this way the ambiguous
            //       interval is one statement wide, and any failure after it commits leads to the
            //       reconciling branch above rather than to a second send. Trade-offs: two round trips
            //       per publication instead of one, which is not measurable beside the network call
            //       they follow.
            String brokerMessageId = messageIdOf(accepted);
            String brokerSequenceNumber = sequenceNumberOf(accepted);
            transition(row, (stored, at) -> stored.recordSendAccepted(brokerMessageId,
                    brokerSequenceNumber, deadline, at));
            transition(row, AuthReplyOutbox::markPublished);
            // WHY : Assumptions: the BROKER'S OWN identities are logged beside this service's row
            // identity, because they are the only evidence that distinguishes a message the broker
            // newly enqueued from one it suppressed as a duplicate of an earlier accept. Both answer
            // 200 and both reach this line. ⚠️ Refactoring Rationale: this comment said neither
            // identity was persisted "because the column set is frozen by the applied migration, and
            // adding one would change a Flyway checksum in every environment that has already run it".
            // That reasoning was wrong on its own terms: a checksum is per MIGRATION, so adding columns
            // in a NEW version leaves V1's checksum untouched -- which is exactly what V2 and V3
            // already did to this table, and what V4 now does to persist both identities. They are
            // logged here as well because a log query joins them to the send, while the columns answer
            // the different question the reconciling branch above asks.
            LOG.info(
                    "event=auth.reply.published outboxId={} attempts={} brokerMessageId={} "
                            + "brokerSequenceNumber={}",
                    row.getOutboxId(), row.getAttempts(), brokerValue(brokerMessageId),
                    brokerValue(brokerSequenceNumber));
            return true;
        } catch (RuntimeException failure) {
            // WHY : Assumptions: the reason PERSISTED on the row stays message-free -- it is the cause
            // chain's types and frames and nothing else. A transport failure message can embed the
            // request it was building and this row's payload carries a primary account number, so the
            // message is the one part that is not written into a column that outlives the incident.
            // Refactoring Rationale: it was the exception's CLASS NAME alone, which named the outermost
            // type and discarded every cause beneath it -- an `SdkClientException` recorded that way is
            // indistinguishable from any other, and the connect refusal, the timeout and the unresolved
            // host underneath it are the whole diagnosis. The digest carries the chain instead.
            String reason = ThrowableDigest.of(failure);
            // WHY : Assumptions: the MESSAGE is logged and not persisted, and the asymmetry is the point.
            // Alternatives Considered: persisting it too, which would put the "why" on the retained row.
            // Rejected because an abandoned row deliberately survives the retention sweep, so persisting
            // a message would give a transport-authored string an unbounded lifetime in the database
            // while the log it also reaches is retained by policy. FailureSummary is what makes it
            // sayable at all: it neutralises control characters, masks any embedded card number and
            // bounds the length, in that order.
            // WHY : ⚠️ Refactoring Rationale: the renderer is the WITHHOLDING one, and it used to be
            //       FailureSummary.of. This catch receives whatever a send raised, so it cannot reason
            //       about who composed the message it is holding, and the masking of `of` recognises
            //       card-shaped digit runs only: a transport failure reporting a connect refusal
            //       against a signed location, or an access-key identifier, carries no digit run at all
            //       and passed through untouched into a retained log. FailureSummary's own contract
            //       names exactly this shape of site -- a generic handler -- and directs it to
            //       databaseConditionOf, which admits a message only when some link in the chain
            //       carries a database state code and withholds it otherwise. Trade-offs: a transport
            //       message is now withheld at this site, which is the diagnostic an operator would
            //       most like to read; what replaces it is the digest below, which names every type and
            //       frame in the chain, plus the queue's own metrics. Withholding by default cannot
            //       fail open, and this catch is not the place that knows the provenance of its input.
            String detail = FailureSummary.databaseConditionOf(failure);
            // WHY : ⚠️ Refactoring Rationale: the absence token comes from the shared kernel now, where
            // this method previously rendered it through this class's own broker-value helper. The two
            // produced the same text, and that agreement was a coincidence of two literals rather than
            // one definition -- the queue error handler needed the same field and would have been the
            // third site to choose a token for itself. Naming it once is what keeps one log query able
            // to match every site's absence.
            String sqlState = FailureSummary.sqlStateOrAbsent(failure);
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
                // WHY : ⚠️ Refactoring Rationale: this line named the acquirer's TRANSACTION
                // IDENTIFIER and no longer does. The argument for naming it was that the identifier "is
                // message metadata rather than a protected value" because the specification freezes it
                // as the deduplication identity, "so it already travels in queue telemetry on every
                // send". Both halves are true and the conclusion does not follow: queue telemetry is a
                // different sink with a different retention and a different audience from this
                // service's application log, so a value being present in one is not a reason to write
                // it into the other. The identifier is the key of a committed decision and of the
                // ledger entry behind it, which is precisely what makes an unanswered-transaction
                // report answerable -- and equally what makes a log line carrying it a link from log
                // access to a financial record. This module's own request payload type already renders
                // the same field as withheld in its diagnostic form, so naming it here was also the
                // outlier.
                // WHY : Assumptions: outboxId, which was already on this line, is what an operator
                // pivots on. The path from a requester's report to this row runs through the governed
                // table -- the identifier is the deduplication column, so one indexed query answers
                // "was a reply owed for this transaction, and was it abandoned" -- and that query is
                // access-controlled and audited where a log read is neither. Alternatives Considered: a
                // keyed opaque token over the identifier, which this context can mint because it holds
                // a tokeniser bean for its queue metadata. Rejected because it would put a second
                // purpose on that key and would still need the same governed query to be useful, so it
                // would add a coupling and remove nothing.
                LOG.error(
                        "event=auth.reply.abandoned outboxId={} attempts={} "
                                + "maxAttempts={} fault={} reason={} sqlState={}",
                        row.getOutboxId(), row.getAttempts(),
                        this.maxAttempts, reason, detail, sqlState);
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
                    "event=auth.reply.publish-failed outboxId={} attempts={} nextAttemptAt={} fault={} "
                            + "reason={} sqlState={}",
                    row.getOutboxId(), row.getAttempts(), retryAt, reason, detail, sqlState);
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
     * Reads the broker-assigned message identifier from an accepted send, tolerating an absent response.
     *
     * <p>Assumptions: a {@code null} response is tolerated rather than dereferenced, and the reason is a
     * correctness one rather than defensive habit. This value is read AFTER the publication has already
     * been committed to the row, so a null dereference here would be caught by the failure handler below
     * and would record a failure -- or an abandonment -- against a reply that was successfully sent. A
     * live transport never returns null; a test double that stubs the client without stubbing this one
     * call does, which is exactly how that inversion would first reach a build.</p>
     *
     * @param accepted the transport's answer to an accepted send; may be {@code null}
     * @return the broker's message identifier, or {@code null} when none was returned
     */
    private static String messageIdOf(SendMessageResponse accepted) {
        return accepted == null ? null : accepted.messageId();
    }

    /**
     * Reads the broker-assigned sequence number from an accepted send, tolerating an absent response.
     *
     * <p>Assumptions: absence is ORDINARY for this field rather than exceptional -- the transport assigns
     * a sequence number only on an ordered queue -- so it is rendered as the absent placeholder and never
     * treated as a fault. It is logged because on an ordered queue it is the broker's own statement of
     * where in the card's sequence this reply landed, which is the one thing this service cannot derive.</p>
     *
     * @param accepted the transport's answer to an accepted send; may be {@code null}
     * @return the broker's sequence number, or {@code null} when none was returned
     */
    private static String sequenceNumberOf(SendMessageResponse accepted) {
        return accepted == null ? null : accepted.sequenceNumber();
    }

    /**
     * Renders a value the broker or a driver may not have supplied, naming its absence explicitly.
     *
     * <p>Assumptions: an absent value is NAMED rather than rendered as {@code null}, because a log reader
     * cannot tell a formatted {@code null} from a field the template forgot to populate, and both blank
     * and {@code null} arrive here from the same accessors.</p>
     *
     * @param value the value to render; may be {@code null} or blank
     * @return the value itself when present, otherwise the absent placeholder, never {@code null}
     */
    private static String brokerValue(String value) {
        return value == null || value.isBlank() ? ABSENT_BROKER_VALUE : value;
    }

    /**
     * Computes the deadline to STAMP on this send, rebased onto the instant the send is actually made.
     *
     * <p>⚠️ Refactoring Rationale: the stored absolute instant used to be sent verbatim, and that made a
     * retried reply arrive already stale. The reference denominates its deadline as a DURATION -- fifty
     * tenths of a second, five seconds, set on the descriptor immediately before the put at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L750 -- and the transport there starts
     * counting it from the put. It is not an absolute wall-clock instant chosen when the decision was
     * committed. Sending the stored instant therefore mistranslated the semantic: a reply committed at
     * {@code T} with a five-second window and first sent at {@code T+7s} was published carrying a
     * deadline of {@code T+5s}, so the receiving end -- which honours the attribute, as
     * {@code docs/adr/ADR-004-messaging.md} directs -- discarded it on arrival. That turns any delay at
     * all into guaranteed loss of a reply the committed decision says is owed.</p>
     *
     * <p>Assumptions: the WINDOW is preserved and the ORIGIN moves. The window is taken as the interval
     * the deciding transaction chose, {@code expiresAt - createdAt}, and re-applied from the current
     * instant, so a requester's five seconds stays five seconds however many attempts precede the send.
     * Trade-offs: a reply retried for an hour is stamped with a fresh five-second window each time
     * rather than one that expired long ago, so the attribute stops being evidence of how long the reply
     * has been owed. That evidence is not lost -- it is the {@code event=auth.reply.late} warning and the
     * row's own {@code created_at} and {@code attempts} columns -- and it belongs there rather than in a
     * field whose only consumer is a receiver deciding whether to act on the message in front of it.</p>
     *
     * <p>Assumptions: a row with no stored deadline is stamped with no attribute, and a row whose stored
     * deadline is at or before its creation instant is stamped with that stored value UNCHANGED. The
     * second case is a window of zero or less, which no positive rebasing could honestly represent; the
     * verbatim value is passed through so a receiver applies the sender's own arithmetic rather than this
     * method's guess at it.</p>
     *
     * <p>⚠️ Assumptions: the value this method returns is RECORDED on the row, in the same transaction
     * that records the broker's acceptance, and that is what makes the recomputation safe. Refactoring
     * Rationale: the recomputation was previously stored nowhere, and the consequence was measurable. The
     * queue deduplicates on the acquirer's transaction identifier, so a retry after an accepted send was
     * SUPPRESSED and answered with the identity of the message the broker already held -- the one carrying
     * the earlier deadline -- while the row was then marked published against a value that attempt had
     * computed and nothing had sent; and beyond the five-minute deduplication window the same
     * recomputation enqueued a genuine second reply whose window had been refreshed, so a duplicate looked
     * live long after the answer was owed. Neither is reachable now: a row whose send the broker accepted
     * is reconciled and never sent again, and the deadline that accompanied the accepted send is the value
     * stored beside it.</p>
     *
     * @param row the already-claimed row being sent; must not be {@code null}
     * @return the deadline to stamp on this send, or {@code null} when the row carries none
     */
    private LocalDateTime sendDeadlineFor(AuthReplyOutbox row) {
        LocalDateTime stored = row.getExpiresAt();
        if (stored == null) {
            return null;
        }
        LocalDateTime createdAt = row.getCreatedAt();
        if (createdAt == null || !createdAt.isBefore(stored)) {
            return stored;
        }
        return now().plus(Duration.between(createdAt, stored));
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
     * @param sendDeadline the deadline to stamp, already fixed and persisted by the caller, of type
     *     {@code LocalDateTime}; {@code null} when this reply carries none
     * @return the send request naming that row's own destination, never {@code null}
     * @throws NullPointerException if a column the publication requires is unpopulated on the row
     * @throws IllegalArgumentException if a column on the row is blank or wider than the publication
     *     admits
     */
    private SendMessageRequest requestFor(AuthReplyOutbox row, LocalDateTime sendDeadline) {
        OutboxMessage publication = OutboxMessage.from(row);
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(ATTRIBUTE_CONTENT_TYPE, stringAttribute(publication.contentType()));
        if (publication.hasCorrelationId()) {
            attributes.put(ATTRIBUTE_CORRELATION_ID, stringAttribute(publication.correlationId()));
        }
        // WHY : Assumptions: the presence question is asked of the PUBLICATION -- which is the projection
        // that has already validated the row -- and the VALUE is the one the CALLER fixed and persisted
        // before this method was reached. Refactoring Rationale: this method used to call the deadline
        // helper itself, which computed a fresh value per attempt and stored none, so the attribute a
        // retry carried differed from the attribute the queue was holding for the same reply; taking the
        // value as a parameter is what makes "the row records what the wire carries" a property of the
        // call graph rather than of two computations agreeing.
        if (publication.expiresAt() != null) {
            attributes.put(ATTRIBUTE_EXPIRES_AT, stringAttribute(sendDeadline.toString()));
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
