package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.domain.OutboxMessage;
import com.carddemo.authorization.repository.OutboxRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Publishes authorization replies that the consumer wrote into the transactional outbox.
 *
 * <p>This class has no baseline counterpart, and that is the point. The baseline publishes its reply
 * BEFORE it persists or commits the decision: {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}
 * performs {@code 7100-SEND-RESPONSE} at line 461 -- reaching the no-syncpoint put at lines 753 to 758 --
 * and only then performs {@code 8000-WRITE-AUTH-TO-DB} at line 464, with the syncpoint at line 335
 * arriving later still because the whole paragraph is performed at line 330. A failure after the put and
 * before the write or the commit therefore leaves a reply on the queue that no committed row accounts
 * for, and the request cannot be re-presented to re-derive it because the get at line 389 consumed it
 * destructively outside any unit of work. {@link AuthorizationRequestListener} writes the reply into the
 * database inside the deciding transaction instead, and this class drains it afterwards -- so no reply
 * ever precedes its decision, a reply exists for every committed decision, and publication can be retried
 * as often as it needs to be without re-deciding anything.</p>
 *
 * <p>Assumptions: publication is at-least-once, not exactly-once, and that is safe because the reply
 * queue is a FIFO queue and every message carries a purpose-scoped derivation of the acquirer's
 * card-and-transaction identity as its deduplication identifier. A reply sent twice inside the
 * deduplication window is accepted once. A reply sent twice outside it is a duplicate the requester must
 * tolerate, which is a genuine obligation this design introduces rather than one it inherits: the
 * baseline's own queue never redelivered anything, because its destructive no-syncpoint get destroyed
 * each message on read. The obligation is accepted because the reply carries the transaction identifier
 * at ordinal 2, so a requester can suppress duplicates by key, whereas a reply that is never sent at all
 * cannot be reconstructed by anyone.</p>
 *
 * <p>Trade-offs: the drain is polled on a fixed delay rather than triggered by the committing
 * transaction. A commit-time trigger would publish sooner in the ordinary case and would publish NOTHING
 * after a crash between commit and trigger, which is the exact window this class exists to close; the
 * poll covers both cases with one mechanism. The delay is short and configurable, so the added latency is
 * bounded by it.</p>
 */
@Component
public class OutboxPublisher {

    /**
     * The message attribute carrying the correlation identifier echoed back to the requester.
     */
    public static final String ATTRIBUTE_CORRELATION_ID = "correlationId";

    /**
     * The message attribute carrying the instant after which the reply is no longer worth acting on.
     *
     * <p>Assumptions: the queue service has no per-message time to live, so the expiry the baseline set
     * on its reply put travels as this attribute and the receiving end honours it. The resolution is
     * recorded in {@code docs/adr/ADR-004-messaging.md}.</p>
     */
    public static final String ATTRIBUTE_EXPIRES_AT = "expiresAt";

    /**
     * The message attribute naming the payload's wire format.
     *
     * <p>Assumptions: the baseline declares its payload as a string format, so this attribute is what
     * lets a consumer distinguish the migrated delimited reply from the structured envelope offered
     * additively alongside it, without inspecting the bytes.</p>
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
     * The largest batch one drain may claim.
     *
     * <p>Assumptions: the ceiling exists because the whole pass runs in ONE transaction that holds a row
     * lock on every row it claimed, so the batch size is the number of rows a second publisher instance is
     * kept off for the duration of the pass. Five hundred is the reference program's own message-batch
     * quota at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 332, which makes it the
     * largest figure anything in this migration treats as one unit of work; a value above it is a
     * misconfiguration rather than a tuning choice.</p>
     *
     * <p>Trade-offs: the ceiling refuses a value an operator might have meant as "drain everything".
     * Draining everything is what the fixed-delay poll already does across passes, one bounded batch at a
     * time, so the refusal costs nothing an operator cannot express and prevents a single pass from
     * locking an unbounded number of rows.</p>
     */
    private static final int MAX_BATCH_SIZE = 500;

    /**
     * The shortest polling interval the publisher accepts, in milliseconds.
     *
     * <p>Assumptions: a floor rather than merely a positive test, because this interval is the delay
     * BETWEEN passes and each pass opens a transaction and issues a locking query. An interval of one
     * millisecond turns the drain into a busy loop against the database whose only effect is to consume
     * the connection budget the deciding path needs. Fifty milliseconds is well below the second the
     * deployment configures and far above the point at which the poll costs more than it publishes.</p>
     */
    private static final long MIN_POLL_INTERVAL_MILLIS = 50L;

    /**
     * The longest polling interval the publisher accepts, in milliseconds.
     *
     * <p>Assumptions: the ceiling is one hour, and it exists because this interval is the upper bound on
     * how long a committed reply waits before it is published. A value beyond an hour leaves a decided
     * authorization unanswered for longer than any requester waits, which presents as a silent functional
     * outage rather than as a misconfiguration -- the same failure shape the reply-queue allowlist on the
     * consumer is validated against.</p>
     *
     * <p>Assumptions: the test profile configures exactly this value, one hour, to keep the scheduled
     * drain from running during a test that drives it directly. The ceiling is therefore inclusive, and
     * lowering it would break that profile rather than catch a mistake.</p>
     */
    private static final long MAX_POLL_INTERVAL_MILLIS = 3_600_000L;

    /**
     * How many rows one drain claims.
     */
    private final int batchSize;

    /**
     * For how many days a published reply is retained before the sweep removes it.
     *
     * <p>Assumptions: the window is operational rather than regulatory -- it exists so an operator can
     * answer whether a reply was sent for a given transaction -- so it is configured and short by
     * default. A row still carries an encoded reply containing a primary account number, so keeping one
     * longer than the question needs is a disclosure surface rather than a safety margin.</p>
     */
    private final int retentionDays;

    /**
     * Creates the publisher.
     *
     * <p>Refactoring Rationale: both configured numbers are VALIDATED here, where the documentation
     * already said the batch size must be positive and nothing enforced it. A non-positive batch size
     * reaches the repository as a {@code LIMIT} the database rejects, so the failure surfaces once per
     * poll interval from a scheduled thread rather than at start-up, and an enormous one row-locks every
     * unpublished row in a single pass. Refusing both here converts a recurring background fault into a
     * container that does not start, which an orchestrator reports.</p>
     *
     * <p>Refactoring Rationale: the polling interval is injected only to be validated, the annotation on
     * {@link #drain()} reading the same property independently. That is deliberate: the scheduler's own
     * rejection of a malformed value arrives during context refresh from the scheduling infrastructure
     * and names the expression rather than the operator's mistake, and it bounds nothing that parses but
     * is absurd. Alternatives Considered: reading the interval from the environment inside
     * {@link #drain()} instead. Rejected because the check would then run once per pass and could not
     * stop the instance from starting at all.</p>
     *
     * @param outbox the outbox repository; must not be {@code null}
     * @param sqs the queue client; must not be {@code null}
     * @param clock the clock publication instants are read from; must not be {@code null}
     * @param batchSize how many rows one drain claims; must be positive and at most
     *     {@value #MAX_BATCH_SIZE}
     * @param pollIntervalMillis the delay between drains in milliseconds, validated here and applied by
     *     the schedule on {@link #drain()}; must be between {@value #MIN_POLL_INTERVAL_MILLIS} and
     *     {@value #MAX_POLL_INTERVAL_MILLIS} inclusive
     * @param retentionDays for how many days a published reply is retained before
     *     {@link #purgePublished()} removes it; must be positive
     * @throws NullPointerException if {@code outbox}, {@code sqs} or {@code clock} is {@code null}
     * @throws IllegalArgumentException if any configured number falls outside its bounds
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
     * @throws IllegalArgumentException if the value is not positive or exceeds {@value #MAX_BATCH_SIZE}
     */
    private static int validatedBatchSize(int configured) {
        if (configured <= 0) {
            throw new IllegalArgumentException(
                    "carddemo.messaging.outbox-batch-size must be positive but was " + configured
                            + "; a non-positive batch reaches the database as a LIMIT it rejects, once"
                            + " per poll interval, from a scheduled thread");
        }
        if (configured > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "carddemo.messaging.outbox-batch-size must be at most " + MAX_BATCH_SIZE
                            + " but was " + configured + "; one drain runs in a single transaction and"
                            + " row-locks every row it claims, so a larger batch locks more rows than"
                            + " any pass should hold");
        }
        return configured;
    }

    /**
     * Returns the retention window after refusing a value that would erase its own pass's work.
     *
     * <p>Assumptions: only the FLOOR is enforced. A long window is a disclosure surface rather than a
     * fault -- the argument on {@link #retentionDays} -- and an operator lengthening it deliberately is
     * making a judgement this class is not in a position to overrule, whereas a non-positive one is
     * never a judgement at all.</p>
     *
     * @param configured the retention window in days as the configuration bound it
     * @return that same value once it is known to be positive
     * @throws IllegalArgumentException if the value is not positive, a window that would delete a reply
     *     in the same pass that published it
     */
    private static int validatedRetentionDays(int configured) {
        if (configured <= 0) {
            throw new IllegalArgumentException(
                    "carddemo.messaging.outbox-retention-days must be positive but was " + configured
                            + "; a non-positive window would delete a reply in the pass that sent it,"
                            + " so no operator could ever confirm a reply had been published");
        }
        return configured;
    }

    /**
     * Refuses a polling interval that would either busy-loop the database or strand a committed reply.
     *
     * <p>Assumptions: the value is not retained, because the schedule reads the same property itself.
     * What this method contributes is the refusal, and a refusal from a constructor stops the bean.</p>
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
                            + "; below the floor the drain busy-loops the database and above the ceiling"
                            + " a committed reply waits longer than any requester does");
        }

    }

    /**
     * Claims and publishes one batch of pending replies, one ordering group at a time.
     *
     * <p>Assumptions: the whole drain runs in ONE transaction, which is what holds the row locks that
     * keep a second publisher instance off the rows this one claimed. Committing per row would release
     * each lock immediately and let a concurrent instance claim a row this one had already sent.</p>
     *
     * <p>Refactoring Rationale: the claim is now per ORDERING GROUP -- one head row per group -- and it
     * was a single global claim of the oldest rows. The change closes an ordering defect rather than
     * tuning anything: a first-in-first-out queue orders messages within a group only after it accepts
     * them, so the sequence of send calls is the delivered sequence. With a global claim, a pass holding
     * two rows of one group whose FIRST send failed went on to send the second, placing a newer reply
     * ahead of an older one for the same card. Claiming the group's head, and advancing that group only
     * after its head has been accepted, makes that reversal unrepresentable.</p>
     *
     * <p>Assumptions: a send failure is recorded on the row and the drain CONTINUES with the next GROUP
     * rather than propagating, and it never advances the group that failed. Propagating would roll back
     * the attempt counts of every row already handled in this batch, so one unreachable reply queue would
     * erase the evidence of every other group's progress; advancing the failed group would be the
     * reordering this method exists to prevent.</p>
     *
     * @return how many replies were published in this pass, which a caller may use to decide whether to
     *     drain again immediately
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
     * Publishes one ordering group in order, starting at its head row and stopping at the first failure.
     *
     * <p>Assumptions: the group is advanced only while sends succeed. The loop re-claims the group's next
     * rows after each success, so a group with a backlog drains in one pass rather than one reply per
     * poll interval, while a failure leaves every row behind the failed one untouched and still pending --
     * so the next pass starts at the same head and the group's order cannot be broken by a retry.</p>
     *
     * <p>Assumptions: an EXPIRED row is retired rather than sent and the group still advances past it,
     * because retiring it is a terminal outcome for that row: the requester has stopped waiting, so
     * nothing about the replies behind it is made out of order by skipping it.</p>
     *
     * <p>Trade-offs: the follow-on claim is bounded by the same batch size as the head claim, so one busy
     * card cannot monopolise a pass indefinitely; the remaining rows are picked up by the next drain. A
     * larger bound would drain a hot group faster while holding its locks for longer.</p>
     *
     * @param head the claimed head row of the group; must not be {@code null}
     * @param now the current instant in coordinated universal time; must not be {@code null}
     * @return how many replies of this group were published
     */
    private int publishGroupFrom(AuthReplyOutbox head, LocalDateTime now) {
        int published = 0;
        AuthReplyOutbox row = head;
        while (row != null) {
            if (!handleOne(row, now)) {
                // WHY : Assumptions: the group stops here and its later rows are left pending. Returning
                // rather than continuing is what makes the ordering guarantee hold under failure: the
                // only reply that may follow this one is the one that is still waiting for it.
                return published;
            }
            if (!row.isExpiredAsOf(now)) {
                published++;
            }
            row = nextInGroup(row);
        }
        return published;
    }

    /**
     * Claims the next pending row of one group after the row just handled.
     *
     * @param handled the row just published or retired; must not be {@code null}
     * @return the next row of the same group, or {@code null} when the group holds no further pending row
     */
    private AuthReplyOutbox nextInGroup(AuthReplyOutbox handled) {
        List<AuthReplyOutbox> followers = this.outbox.claimGroupFollowers(
                handled.getOrderGroupToken(), handled.getOutboxId(), 1);
        return followers.isEmpty() ? null : followers.get(0);
    }

    /**
     * Retires an expired reply or publishes a live one, and reports whether the group may advance.
     *
     * @param row the claimed row; must not be {@code null}
     * @param now the current instant in coordinated universal time; must not be {@code null}
     * @return {@code true} when this row reached a terminal state, so the group may advance
     */
    private boolean handleOne(AuthReplyOutbox row, LocalDateTime now) {
        if (row.isExpiredAsOf(now)) {
            // WHY : Trade-offs: an expired reply is retired rather than sent. Sending it would
            // deliver an answer the requester has stopped waiting for and would consume the
            // deduplication identifier, so a legitimate retry of the same transaction would then be
            // suppressed as a duplicate of an answer nobody read. Marking it published rather than
            // deleting it keeps the row auditable and keeps it out of the pending index.
            row.recordFailedAttempt("expired before publication");
            row.markPublished(now);
            LOG.warn("event=auth.reply.expired outboxId={}", row.getOutboxId());
            return true;
        }
        return publish(row, now);
    }

    /**
     * Deletes replies whose retention window has elapsed, published rows only.
     *
     * <p>Assumptions: retention is enforced here, on a schedule of its own, and the deletion predicate
     * requires a publication instant to be present -- so a pending reply is never removed however old it
     * is. Deleting one would lose an answer the committed decision says was produced, which is the very
     * window the outbox exists to close, so the restriction is the whole safety property of this
     * method.</p>
     *
     * <p>Alternatives Considered: folding this into the migrated {@code CBPAUP0C} expiry job, which is
     * where the architecture originally placed it so that one scheduled job owned all of this schema's
     * retention. It is done here instead because the two sweeps have different subjects and different
     * safe cut-offs -- that job expires PENDING AUTHORIZATIONS by business age and this one removes
     * PUBLISHED REPLIES by operational age -- and because binding the outbox's growth to a job that has
     * not been authored would leave this table growing without bound in the meantime. The architecture
     * document records the change of owner.</p>
     *
     * <p>Trade-offs: the sweep runs on a longer fixed delay than the drain, because retention is measured
     * in days and a frequent pass would issue a delete that matched nothing. The window and the interval
     * are both configured, so an operator investigating a reply can widen the window without a
     * rebuild.</p>
     *
     * @return how many published replies were deleted, which a caller may log or assert on
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
     * Publishes one reply and records the outcome on its row.
     *
     * @param row the claimed outbox row; must not be {@code null}
     * @param now the current instant in coordinated universal time; must not be {@code null}
     * @return {@code true} when the reply was published
     */
    private boolean publish(AuthReplyOutbox row, LocalDateTime now) {
        try {
            this.sqs.sendMessage(requestFor(row));
            row.markPublished(now);
            LOG.info("event=auth.reply.published outboxId={} attempts={}", row.getOutboxId(),
                    row.getAttempts());
            return true;
        } catch (RuntimeException failure) {
            // WHY : Assumptions: the recorded reason is the exception's CLASS NAME and not its message.
            // A client failure message can embed the request it was building, and this row's payload
            // carries a primary account number, so the message is the one part that must not be persisted
            // into a column an operator reads. The class name names the fault without carrying the data.
            row.recordFailedAttempt(failure.getClass().getName());
            LOG.error("event=auth.reply.publish-failed outboxId={} attempts={} fault={}",
                    row.getOutboxId(), row.getAttempts(), failure.getClass().getName());
            return false;
        }
    }

    /**
     * Builds the send request for one reply, from the publication the row describes.
     *
     * <p>Assumptions: the group identifier and the deduplication identifier are the purpose-scoped KEYED
     * TOKENS stored on the row, not the card number and the acquirer's transaction identifier they stand
     * for. Both fields are message metadata, which server-side encryption does not cover, so the raw
     * values would appear in queue telemetry and in every send trace; a token is equal for equal cards and
     * equal for one authorization, so per-card ordering and payload-independent deduplication are
     * preserved exactly while neither protected value leaves the encrypted body.</p>
     *
     * <p>Assumptions: both are taken from the row rather than recomputed here, so this publisher holds no
     * key material and a row whose payload cannot be parsed is still publishable.</p>
     *
     * <p>Refactoring Rationale: the row is projected through {@link OutboxMessage#from(AuthReplyOutbox)}
     * and this method reads the projection, where it previously read seven columns off the entity
     * directly. The values sent are identical -- the projection copies each column without altering it --
     * and what changes is that the description of what a reply publication CONSISTS OF now lives in one
     * type instead of two. Before this, that type existed and neither side called it: the deciding
     * listener assembled the row through the entity's constructor and this method took it apart column by
     * column, so the two halves of one contract were written twice and could drift with no test able to
     * see it. Projecting here also refuses a row whose stored format label is not the one the durable row
     * can carry, which reading columns silently accepted.</p>
     *
     * <p>Trade-offs: one short-lived projection object is allocated per published reply. That is accepted
     * because the send it precedes is a network call, so the allocation is not measurable beside it, and
     * because the projection's own construction re-checks that every column a message requires is
     * populated -- moving a fault that would otherwise surface as a rejected send to the moment the row is
     * read.</p>
     *
     * @param row the claimed outbox row; must not be {@code null}
     * @return the send request, never {@code null}
     * @throws NullPointerException if a column the publication requires is unpopulated on the row
     * @throws IllegalArgumentException if a column on the row is blank or wider than the publication
     *     admits
     */
    private SendMessageRequest requestFor(AuthReplyOutbox row) {
        OutboxMessage publication = OutboxMessage.from(row);
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(ATTRIBUTE_CONTENT_TYPE, stringAttribute(publication.contentType()));
        if (publication.correlationId() != null) {
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
     * @return the attribute, never {@code null}
     */
    private MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder()
                .dataType(ATTRIBUTE_TYPE_STRING)
                .stringValue(value)
                .build();
    }
}
