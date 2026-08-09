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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
     * loop ends once the processed count exceeds it. That makes it the largest figure anything in
     * this migration treats as one unit of work, so a value above it is a misconfiguration rather
     * than a tuning choice. {@link OutboxRepository#claimGroupHeads(int)} cites the same two lines,
     * so the ceiling here and the bound there cannot disagree.</p>
     *
     * <p>Trade-offs: the ceiling refuses a value an operator might have meant as "drain
     * everything". Draining everything is what the fixed-delay poll already does across passes, one
     * bounded batch at a time, so the refusal costs nothing an operator cannot express while
     * keeping one unit of work from spanning an unbounded number of sends.</p>
     */
    private static final int MAX_BATCH_SIZE = 500;

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
     * enormous one puts an unbounded number of sends inside one unit of work. Refusing either turns
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
     * @throws NullPointerException if {@code outbox}, {@code sqs} or {@code clock} is {@code null}
     * @throws IllegalArgumentException if any configured number falls outside its stated bounds
     */
    public OutboxPublisher(OutboxRepository outbox, SqsClient sqs, Clock clock,
            @Value("${carddemo.messaging.outbox-batch-size:25}") int batchSize,
            @Value("${carddemo.messaging.outbox-poll-interval-ms:1000}") long pollIntervalMillis,
            @Value("${carddemo.messaging.outbox-retention-days:7}") int retentionDays) {
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.sqs = Objects.requireNonNull(sqs, "sqs must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.batchSize = validatedBatchSize(batchSize);
        requireBoundedPollInterval(pollIntervalMillis);
        this.retentionDays = validatedRetentionDays(retentionDays);
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
                            + " but was " + configured + "; one drain runs in a single transaction,"
                            + " so a larger batch puts more sends inside one unit of work than any"
                            + " pass should hold");
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
     * {@link OutboxRepository#claimGroupHeads(int)} moves each row it takes from the attempt count it
     * observed to the next one and returns only the rows whose transition it actually performed, so
     * single delivery is a property of the DATA rather than of anything held open while a reply is
     * sent. A concurrent pass that observed the same candidate finds the comparison no longer true
     * and receives that row not at all, rather than receiving it a second time.</p>
     *
     * <p>Alternatives Considered: claiming by taking a pessimistic row lock in the selection --
     * {@code SELECT ... FOR UPDATE SKIP LOCKED}, or the same lock requested through a JPA lock mode
     * on a query method -- and stepping over rows another transaction holds. Rejected on evidence
     * from the reference rather than on preference: {@code cpy/IMSFUNCS.cpy} DECLARES all three
     * get-hold retrieval codes at L19, L21 and L23 and no program in the reference tree passes any of
     * them to a data-language call, while both unload views run get-only at {@code ims/PAUTBUNL.PSB}
     * L18 and {@code ims/DLIGSAMP.PSB} L18. The concrete consequence of adopting a lock would be
     * lock-wait queueing and deadlock-victim rollback on a path that has neither today, visible only
     * under concurrency and so absent while a single publisher is tested. The transition needs no
     * lock mode to be safe, because it either applies or it does not.</p>
     *
     * <p>Trade-offs: the whole pass is nevertheless ONE unit of work, and the sends happen inside it.
     * What that buys is not the claim -- the transition above is the claim -- but the behaviour of a
     * concurrent pass: the claiming update holds an ordinary row write until this transaction ends,
     * so a second publisher BLOCKS on it and then finds the attempt comparison stale and skips the
     * row, instead of observing it as pending at its new count and sending a reply that is already in
     * flight. What it costs is a connection held across bounded network work, and the exposure that a
     * send which succeeds in a pass that then fails to commit leaves the row pending to be sent
     * again; the deduplication identifier makes that a suppressed duplicate rather than a second
     * answer.</p>
     *
     * <p>Assumptions: the claim is per ORDERING GROUP -- one head row per group -- and that is what
     * preserves per-card order rather than an incidental effect of grouping. A first-in-first-out
     * queue orders messages within a group only after it accepts them, so the sequence of send calls
     * is the delivered sequence; a pass holding two rows of one group whose FIRST send failed and
     * carrying on to the second would place a newer reply ahead of an older one for the same card,
     * which is the single guarantee the group token exists to provide.</p>
     *
     * <p>Assumptions: a send failure is recorded on its row and the drain CONTINUES with the next
     * group rather than propagating, and it never advances the group that failed. Propagating would
     * roll back the attempt counts of every row already handled in this batch, so one unreachable
     * reply queue would erase the evidence of every other group's progress; advancing the failed
     * group would be the reordering this method exists to prevent.</p>
     *
     * @return how many replies were published in this pass, which a caller may use to decide whether
     *     to drain again immediately
     * @throws org.springframework.dao.DataAccessException if the claiming statement itself cannot be
     *     executed, for instance because the connection's search path does not resolve the table;
     *     a failure to SEND is recorded on the row instead and never propagates
     */
    @Scheduled(fixedDelayString = "${carddemo.messaging.outbox-poll-interval-ms:1000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int drain() {
        List<AuthReplyOutbox> heads = this.outbox.claimGroupHeads(this.batchSize);
        if (heads.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC);
        int published = 0;
        for (AuthReplyOutbox head : heads) {
            published += publishGroupFrom(head, now);
        }
        return published;
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
     * drain. A larger bound would drain a hot group sooner while holding one unit of work open
     * longer.</p>
     *
     * @param head the already-claimed head row of the group; must not be {@code null}
     * @param now the current instant in coordinated universal time; must not be {@code null}
     * @return how many replies of this group were put on the wire in this pass
     * @throws org.springframework.dao.DataAccessException if a follow-on claim cannot be executed
     */
    private int publishGroupFrom(AuthReplyOutbox head, LocalDateTime now) {
        int published = 0;
        AuthReplyOutbox row = head;
        while (row != null) {
            boolean expired = row.isExpiredAsOf(now);
            if (!handleOne(row, now, expired)) {
                // WHY : Assumptions: the group stops HERE and its later rows are left pending.
                // Returning rather than continuing is what makes the ordering guarantee hold under
                // failure: the only reply that may follow this one is the one still waiting for it.
                return published;
            }
            if (!expired) {
                published++;
            }
            row = nextInGroup(row);
        }
        return published;
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
        List<AuthReplyOutbox> followers = this.outbox.claimGroupFollowers(
                handled.getOrderGroupToken(), handled.getOutboxId(), 1);
        return followers.isEmpty() ? null : followers.get(0);
    }

    /**
     * Retires an expired reply or publishes a live one, and reports whether the group may advance.
     *
     * <p>Assumptions: staleness is judged once by the caller and passed in rather than re-tested
     * here, so the decision that retires a row and the decision that declines to count it as
     * published cannot disagree about the same instant.</p>
     *
     * @param row the already-claimed row; must not be {@code null}
     * @param now the current instant in coordinated universal time; must not be {@code null}
     * @param expired whether the caller judged this row's deadline to have passed at {@code now}
     * @return {@code true} when this row reached a terminal state, so its group may advance
     */
    private boolean handleOne(AuthReplyOutbox row, LocalDateTime now, boolean expired) {
        if (expired) {
            retire(row, now);
            return true;
        }
        return publishReply(row, now);
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
     * <p>Assumptions: the two calls below are in this order because the first CLEARS the diagnostic.
     * {@link AuthReplyOutbox#markPublished(java.time.LocalDateTime)} sets the publication instant and
     * resets the last-error column, which is right for a reply that succeeded after failing; a row
     * retired for staleness needs the opposite, so the reason is recorded AFTER the mark and would be
     * erased by the reverse order. The attempt counter advancing once more is the cost, and the
     * repository records that no decision anywhere is taken on its value.</p>
     *
     * @param row the already-claimed row whose deadline has passed; must not be {@code null}
     * @param now the instant the retirement is recorded at, in coordinated universal time; must not
     *     be {@code null}
     */
    private void retire(AuthReplyOutbox row, LocalDateTime now) {
        row.markPublished(now);
        row.recordFailedAttempt(RETIREMENT_REASON);
        LOG.warn("event=auth.reply.expired outboxId={} attempts={}", row.getOutboxId(),
                row.getAttempts());
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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgePublished() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC)
                .minusDays(this.retentionDays);
        int deleted = this.outbox.deletePublishedBefore(cutoff);
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
     * @param now the instant a success is recorded at, in coordinated universal time; must not be
     *     {@code null}
     * @return {@code true} when the reply was put on the wire, {@code false} when the attempt failed
     *     and its group must not advance
     */
    private boolean publishReply(AuthReplyOutbox row, LocalDateTime now) {
        try {
            SendMessageRequest request = requestFor(row);
            // WHY : Assumptions: the lambda is written as a BLOCK that discards the send's result,
            // which selects the template's void overload. An expression lambda would be compatible
            // with both the void and the value-returning overload and so would not compile at all;
            // the result carries only the transport's own message identifier, which nothing here
            // records, so discarding it loses nothing.
            SEND_RETRIES.invoke(() -> {
                this.sqs.sendMessage(request);
            });
            row.markPublished(now);
            LOG.info("event=auth.reply.published outboxId={} attempts={}", row.getOutboxId(),
                    row.getAttempts());
            return true;
        } catch (RuntimeException failure) {
            // WHY : Assumptions: the recorded reason is the exception's CLASS NAME and never its
            // message. A client failure message can embed the request it was building, and this
            // row's payload carries a primary account number, so the message is the one part that
            // must not be persisted into a column an operator reads. The class name names the fault
            // without carrying the data, which is the package-wide discipline for this service.
            row.recordFailedAttempt(failure.getClass().getName());
            LOG.error("event=auth.reply.publish-failed outboxId={} attempts={} fault={}",
                    row.getOutboxId(), row.getAttempts(), failure.getClass().getName());
            return false;
        }
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
     * <p>Assumptions: the ordering and deduplication identities are the purpose-scoped KEYED TOKENS
     * stored on the row, not the card number and the acquirer's transaction identifier they stand
     * for, and both are read from the row rather than recomputed. Both fields become message
     * METADATA, which server-side encryption does not cover, so the raw values would appear in queue
     * telemetry and in every send trace; a token is equal for equal cards and equal for one
     * authorization, so per-card ordering and payload-independent deduplication survive exactly.
     * Reading them from the row also leaves this publisher holding no key material and keeps a row
     * publishable even when its payload could not be parsed.</p>
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
                .messageGroupId(publication.orderGroupToken())
                .messageDeduplicationId(publication.deduplicationToken())
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
