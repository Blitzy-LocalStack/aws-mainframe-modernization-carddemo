package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.repository.OutboxRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * inline at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 753, after the syncpoint at
 * line 335 that committed the decision, so a failure between the two loses a reply the data says was
 * produced. {@link AuthorizationRequestListener} writes the reply into the database inside the deciding
 * transaction instead, and this class drains it afterwards -- so a reply exists for every committed
 * decision and publication can be retried as often as it needs to be without re-deciding anything.</p>
 *
 * <p>Assumptions: publication is at-least-once, not exactly-once, and that is safe because the reply
 * queue is a FIFO queue and every message carries the acquirer's transaction identifier as its
 * deduplication identifier. A reply sent twice inside the deduplication window is accepted once; a reply
 * sent twice outside it is a duplicate the requester's own correlation already tolerates, because the
 * baseline's at-least-once queue could deliver one too.</p>
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
     * How many rows one drain claims.
     */
    private final int batchSize;

    /**
     * Creates the publisher.
     *
     * @param outbox the outbox repository; must not be {@code null}
     * @param sqs the queue client; must not be {@code null}
     * @param clock the clock publication instants are read from; must not be {@code null}
     * @param batchSize how many rows one drain claims; must be positive
     */
    public OutboxPublisher(OutboxRepository outbox, SqsClient sqs, Clock clock,
            @Value("${carddemo.messaging.outbox-batch-size:25}") int batchSize) {
        this.outbox = outbox;
        this.sqs = sqs;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * Claims and publishes one batch of pending replies.
     *
     * <p>Assumptions: the whole drain runs in ONE transaction, which is what holds the row locks that
     * keep a second publisher instance off the rows this one claimed. Committing per row would release
     * each lock immediately and let a concurrent instance claim a row this one had already sent.</p>
     *
     * <p>Assumptions: a send failure is recorded on the row and the drain CONTINUES with the next row
     * rather than propagating. Propagating would roll back the attempt counts of every row already
     * handled in this batch, so one unreachable reply queue would erase the evidence of every other
     * row's progress and the batch would retry from the beginning forever.</p>
     *
     * @return how many replies were published in this pass, which a caller may use to decide whether to
     *     drain again immediately
     */
    @Scheduled(fixedDelayString = "${carddemo.messaging.outbox-poll-interval-ms:1000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int drain() {
        List<AuthReplyOutbox> claimed = this.outbox.claimUnpublished(this.batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC);
        int published = 0;
        for (AuthReplyOutbox row : claimed) {
            if (row.isExpiredAsOf(now)) {
                // WHY : Trade-offs: an expired reply is retired rather than sent. Sending it would
                // deliver an answer the requester has stopped waiting for and would consume the
                // deduplication identifier, so a legitimate retry of the same transaction would then be
                // suppressed as a duplicate of an answer nobody read. Marking it published rather than
                // deleting it keeps the row auditable and keeps it out of the pending index.
                row.recordFailedAttempt("expired before publication");
                row.markPublished(now);
                LOG.warn("event=auth.reply.expired outboxId={}", row.getOutboxId());
                continue;
            }
            if (publish(row, now)) {
                published++;
            }
        }
        return published;
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
     * Builds the send request for one reply.
     *
     * <p>Assumptions: the group identifier is the card number and the deduplication identifier is the
     * acquirer's transaction identifier, both taken from the row rather than recomputed. Grouping by card
     * preserves per-card ordering while leaving different cards to be delivered in parallel; deduplicating
     * by transaction identifier makes suppression independent of the payload, so a re-sent reply whose
     * bytes differ is still recognised as the same reply.</p>
     *
     * @param row the claimed outbox row; must not be {@code null}
     * @return the send request, never {@code null}
     */
    private SendMessageRequest requestFor(AuthReplyOutbox row) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(ATTRIBUTE_CONTENT_TYPE, stringAttribute(row.getContentType()));
        if (row.getCorrelationId() != null) {
            attributes.put(ATTRIBUTE_CORRELATION_ID, stringAttribute(row.getCorrelationId()));
        }
        if (row.getExpiresAt() != null) {
            attributes.put(ATTRIBUTE_EXPIRES_AT, stringAttribute(row.getExpiresAt().toString()));
        }
        return SendMessageRequest.builder()
                .queueUrl(row.getReplyQueueUrl())
                .messageBody(row.getPayload())
                .messageGroupId(row.getMessageGroupId())
                .messageDeduplicationId(row.getDeduplicationId())
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
