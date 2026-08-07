package com.carddemo.account.service;

import com.carddemo.account.domain.Account;
import com.carddemo.account.mapper.AccountInquiryReplyMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.common.codec.InquiryRequestCodec;
import com.carddemo.common.codec.InquiryRequestCodec.InquiryRequest;
import com.carddemo.common.messaging.MessageExpiry;
import com.carddemo.common.messaging.MessagingCorrelationId;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * The asynchronous account-inquiry entry point, replacing the queue-driven transaction that answered it.
 *
 * <h2>Purpose</h2>
 * <p>This class is the migrated form of {@code app/app-vsam-mq/cbl/COACCT01.cbl} (620 lines), a
 * queue-triggered CICS transaction that reads a fixed one-thousand-character request, performs one keyed read
 * of the account master, and puts a labelled fixed-width reply on a configured reply queue. That file is
 * REFERENCE-ONLY: it is read as the specification for this class and is never modified.</p>
 *
 * <p>The reference program's shape maps as follows. Its {@code 1000-CONTROL} opens three queues and then
 * loops {@code PERFORM 4000-MAIN-PROCESS UNTIL NO-MORE-MSGS} (physical lines 214 to 217); the loop, the queue
 * handles and the five-second bounded wait all become listener-container properties, so this class holds only
 * the body of one iteration. Its {@code 4000-PROCESS-REQUEST-REPLY} (physical lines 336 to 458) becomes
 * {@link #onRequest(Message)}.</p>
 *
 * <h2>Delivery discipline: no outbox, and that is a deliberate difference</h2>
 * <p>Assumptions: this consumer needs NO transactional outbox, unlike the pending-authorization consumer, and
 * the reason is in the reference programs rather than in a preference. COACCT01 gets and puts inside one unit
 * of work -- physical line 296 computes {@code MQGMO-OPTIONS} as {@code MQGMO-SYNCPOINT} and physical line 379
 * computes {@code MQPMO-OPTIONS} as {@code MQPMO-SYNCPOINT} -- so there is no window in which its read is
 * committed and its reply is lost. That is the opposite of
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}, which uses the NO-SYNCPOINT forms at lines 389
 * and 753 and therefore does have such a window, which is why that flow needed an outbox. Copying the outbox
 * here would add durable machinery to close a window that does not exist.</p>
 *
 * <p>Assumptions: the target discipline is therefore delete-on-success plus the queue's visibility timeout.
 * The listener returns normally only after the reply has been sent, so the framework deletes the request only
 * then; if the send fails, the exception propagates, the request becomes visible again, and a repeated failure
 * carries it to the dead-letter queue at the configured receive count. Nothing is acknowledged for a request
 * that was not answered.</p>
 *
 * <h2>Why a business outcome is a reply and an infrastructure fault is an exception</h2>
 * <p>Trade-offs: the two are separated deliberately, and conflating them is the failure mode this class is
 * written to avoid. A request naming an account that does not exist, or naming the wrong function, is a
 * BUSINESS outcome: the reference program answers it with an {@code INVALID REQUEST PARAMETERS} sentence and
 * consumes the message, so this class publishes the same sentence and returns normally. Raising instead would
 * redeliver a request whose answer will never change, four more times, and then dead-letter it. Conversely a
 * database or queue failure is an INFRASTRUCTURE fault whose retry may well succeed, so it propagates. The
 * reference program's own equivalent of that distinction is its {@code WHEN OTHER} branch at physical lines
 * 437 to 447, which writes to the error queue and terminates the task rather than replying.</p>
 *
 * <h2>What this class does not do</h2>
 * <p>Assumptions: the reply is published to the CONFIGURED reply queue, never to a destination named by the
 * incoming message. The reference program saves the request's reply-to queue into {@code SAVE-REPLY2Q} at
 * physical line 341 and then does not use it: {@code 4100-PUT-REPLY} puts to {@code OUTPUT-QUEUE-HANDLE},
 * which was opened from the statically assigned {@code REPLY-QUEUE-NAME} at physical line 210. Honouring the
 * message's own reply-to would be both unfaithful and a queue-injection vector, since a request could then
 * direct an account's balance to a queue of the sender's choosing.</p>
 *
 * <p>Parameters, return values and raised exceptions are documented per method. This class holds no mutable
 * state beyond a queue-URL cache whose entries are stable for the life of a queue.</p>
 */
@Service
public class InquiryMessageListener {

    /**
     * The logger for this consumer.
     */
    private static final Logger LOG = LoggerFactory.getLogger(InquiryMessageListener.class);

    /**
     * The diagnostic-context key the correlation identifier is published under.
     */
    private static final String MDC_CORRELATION_ID = "correlationId";

    /**
     * The message attribute carrying the requester's correlation identifier.
     */
    private static final String ATTRIBUTE_CORRELATION_ID = "correlationId";

    /**
     * The attribute name declaring the payload's media type on the reply.
     *
     * <p>Assumptions: the reference program sets {@code MQFMT-STRING} on every put, at physical line 375. The
     * target equivalent is an explicit content-type attribute, because the payload is a fixed-width text
     * block read by offset and a consumer must not treat it as a structured document.</p>
     */
    private static final String ATTRIBUTE_CONTENT_TYPE = "contentType";

    /**
     * The media type the fixed-width reply is published as.
     */
    private static final String CONTENT_TYPE_FIXED_WIDTH = "text/plain";

    /**
     * The account master rows this consumer reads.
     */
    private final AccountRepository accounts;

    /**
     * The renderer for all three reply forms.
     */
    private final AccountInquiryReplyMapper replies;

    /**
     * The queue client replies and error reports are published with.
     */
    private final SqsClient sqs;

    /**
     * The configured queue name replies are published to.
     */
    private final String replyQueue;

    /**
     * The configured queue name error reports are published to.
     */
    private final String errorQueue;

    /**
     * The clock the expiry comparison reads the current instant from.
     */
    private final Clock clock;

    /**
     * Resolved queue names, cached by name.
     *
     * <p>Assumptions: a queue's address is stable for the life of the queue, and this service is configured to
     * FAIL rather than create a queue that does not exist, so a cached entry cannot become a pointer to a
     * queue this system did not provision. Resolving per message instead would add an API call to every reply
     * for a value that never changes.</p>
     */
    private final Map<String, String> queueUrls = new ConcurrentHashMap<>();

    /**
     * Creates the consumer.
     *
     * @param accounts the account master repository; must not be {@code null}
     * @param replies the reply renderer; must not be {@code null}
     * @param sqs the queue client; must not be {@code null}
     * @param replyQueue the configured reply queue name; must not be {@code null} or blank
     * @param errorQueue the configured error queue name; must not be {@code null} or blank
     * @param clock the clock the expiry check reads; must not be {@code null}
     * @throws NullPointerException if any reference argument is {@code null}
     * @throws IllegalArgumentException if either queue name is blank, because a consumer that cannot address
     *     its reply queue would take requests off the queue and answer none of them
     */
    public InquiryMessageListener(AccountRepository accounts,
            AccountInquiryReplyMapper replies,
            SqsClient sqs,
            @Value("${carddemo.account.inquiry.reply-queue}") String replyQueue,
            @Value("${carddemo.account.inquiry.error-queue}") String errorQueue,
            Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.replies = Objects.requireNonNull(replies, "replies must not be null");
        this.sqs = Objects.requireNonNull(sqs, "sqs must not be null");
        this.replyQueue = requireQueueName(replyQueue, "carddemo.account.inquiry.reply-queue");
        this.errorQueue = requireQueueName(errorQueue, "carddemo.account.inquiry.error-queue");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Validates a configured queue name.
     *
     * @param value the configured value
     * @param property the property name, so a refusal names what to set
     * @return the trimmed name, never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if the value is blank
     */
    private static String requireQueueName(String value, String property) {
        Objects.requireNonNull(value, property + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(property
                    + " must name a queue: without it this consumer would take requests off the request"
                    + " queue and be unable to answer any of them");
        }
        return value.trim();
    }

    /**
     * Answers one account-inquiry request.
     *
     * <p>Assumptions: the transaction is {@code REQUIRES_NEW} so each message is its own unit of work, which
     * is the target form of the {@code EXEC CICS SYNCPOINT} the reference program issues at the top of every
     * loop iteration, at physical lines 290 to 293. Sharing a transaction across messages would let one
     * message's failure roll back another's work.</p>
     *
     * <p>Assumptions: a request whose expiry has passed is DROPPED, not answered. Answering would publish a
     * balance to a requester that has already stopped waiting, leaving an account's financial position sitting
     * on a reply queue for nobody. The rule is the shared one in {@link MessageExpiry}, so all three consumers
     * in this system honour a requester's expiry identically.</p>
     *
     * @param message the received message, whose payload is the fixed-width request and whose attributes carry
     *     the correlation identifier and the optional expiry; must not be {@code null}
     * @throws com.carddemo.common.codec.InquiryRequestCodec.InquiryRequest never; structural decoding of this
     *     layout cannot fail for a non-null payload, which is why a malformed request still receives the
     *     reference program's own invalid-parameters reply rather than being dead-lettered
     */
    @SqsListener(queueNames = "${carddemo.account.inquiry.request-queue}",
            maxConcurrentMessages = "${carddemo.account.inquiry.max-concurrent-messages:10}",
            maxMessagesPerPoll = "${carddemo.account.inquiry.max-messages-per-poll:10}",
            pollTimeoutSeconds = "${carddemo.account.inquiry.poll-timeout-seconds:5}")
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onRequest(Message<String> message) {
        Objects.requireNonNull(message, "message must not be null");
        String correlationId = correlationId(message);

        // WHY : Assumptions: the LOGGING context carries the sanitised rendering while the reply carries the
        //   value verbatim. The two are deliberately different renderings of one identity: a log record must
        //   not be able to carry a delimiter or a line terminator, and a reply must carry the requester's own
        //   bytes so it can match the answer to its request.
        MDC.put(MDC_CORRELATION_ID, MessagingCorrelationId.logSafe(correlationId));
        try {
            String rawExpiry = attribute(message, MessageExpiry.HEADER_EXPIRES_AT);
            if (MessageExpiry.isMalformed(rawExpiry)) {
                // WHY : Trade-offs: an unparseable expiry is treated as ABSENT rather than as already passed,
                //   because treating it as passed would silently discard every request from a requester whose
                //   formatting differs. Only the LENGTH is logged: the value came off the wire.
                LOG.warn("event=account.inquiry.expiry-unparseable length={}", rawExpiry.trim().length());
            } else if (MessageExpiry.isExpired(rawExpiry, this.clock.instant())) {
                LOG.warn("event=account.inquiry.dropped reason=expired");
                return;
            }

            InquiryRequest request = InquiryRequestCodec.decode(message.getPayload());
            publishReply(replyFor(request), correlationId);
        } finally {
            MDC.remove(MDC_CORRELATION_ID);
        }
    }

    /**
     * Chooses and renders the reply for one decoded request.
     *
     * <p>Assumptions: the guard is the reference program's own, transcribed rather than reinterpreted.
     * {@code IF WS-FUNC = 'INQA' AND WS-KEY > ZEROES} at physical line 342 admits a request only when both
     * hold, and its {@code ELSE} at physical lines 448 to 457 answers everything else with the sentence that
     * names both the key and the function. Splitting the guard into two separate refusals would produce two
     * messages where the baseline produces one.</p>
     *
     * @param request the decoded request; must not be {@code null}
     * @return the framed reply body, never {@code null}
     */
    private String replyFor(InquiryRequest request) {
        if (!request.isFunction(InquiryRequestCodec.FUNCTION_ACCOUNT_INQUIRY) || !request.hasUsableKey()) {
            LOG.info("event=account.inquiry.rejected reason=guard function={}", request.trimmedFunction());
            return this.replies.frame(this.replies.invalidRequest(request.key(), request.function()));
        }

        Optional<Account> account = this.accounts.findById(request.keyValue());
        if (account.isEmpty()) {
            // WHY : Assumptions: this is answered rather than raised. The reference program's NOTFND branch at
            //   physical lines 428 to 436 replies and consumes the message, so raising here would redeliver a
            //   request whose answer cannot change and then dead-letter a request the baseline answered.
            LOG.info("event=account.inquiry.not-found accountId={}", request.keyValue());
            return this.replies.frame(this.replies.accountNotFound(request.key()));
        }

        LOG.info("event=account.inquiry.answered accountId={}", request.keyValue());
        return this.replies.frame(this.replies.accountFound(account.get()));
    }

    /**
     * Publishes a reply to the configured reply queue.
     *
     * <p>Assumptions: the correlation identifier is echoed VERBATIM, matching the reference program's
     * {@code MOVE SAVE-CORELID TO MQMD-CORRELID} at physical line 374. It is the requester's own value and it
     * is how the requester matches an answer to a request, so altering it -- including sanitising it -- would
     * make the reply unmatchable.</p>
     *
     * @param body the framed reply body; must not be {@code null}
     * @param correlationId the requester's correlation identifier, possibly empty when none was supplied
     * @throws software.amazon.awssdk.core.exception.SdkException if the send fails, which propagates so the
     *     request becomes visible again rather than being acknowledged unanswered
     */
    private void publishReply(String body, String correlationId) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(ATTRIBUTE_CONTENT_TYPE, stringAttribute(CONTENT_TYPE_FIXED_WIDTH));
        if (!correlationId.isEmpty()) {
            attributes.put(ATTRIBUTE_CORRELATION_ID, stringAttribute(correlationId));
        }

        this.sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl(this.replyQueue))
                .messageBody(body)
                .messageAttributes(attributes)
                .build());
    }

    /**
     * Publishes a diagnostic to the configured error queue.
     *
     * <p>Assumptions: this is the target form of the reference program's {@code 9000-ERROR} paragraph at
     * physical lines 405 to 425, which puts a fixed-width diagnostic block on a separate error queue. It is
     * exposed so an operator-facing failure can be reported on the same channel the baseline used, and it is
     * deliberately NOT called from the reply path: a business outcome is a reply, not an error report.</p>
     *
     * <p>Assumptions: the diagnostic text is composed by the caller and must name no value that came off the
     * wire, for the same reason the reply path logs lengths rather than contents.</p>
     *
     * @param diagnostic the diagnostic text; must not be {@code null}
     * @throws NullPointerException if {@code diagnostic} is {@code null}
     */
    public void publishError(String diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic must not be null");
        this.sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl(this.errorQueue))
                .messageBody(this.replies.frame(diagnostic))
                .messageAttributes(Map.of(ATTRIBUTE_CONTENT_TYPE, stringAttribute(CONTENT_TYPE_FIXED_WIDTH)))
                .build());
    }

    /**
     * Reports the requester's correlation identifier, or an empty string when none was supplied.
     *
     * <p>Assumptions: an ABSENT correlation identifier is accepted rather than refused, because the reference
     * program accepts one -- it initialises {@code MQ-CORRELID} to spaces at physical line 288 and echoes
     * whatever the descriptor carried, including nothing. A NON-CANONICAL one is also accepted and is echoed
     * unchanged, because it is the requester's own value and this consumer's reply is useless to it otherwise;
     * what protects this service is that the value never reaches a log unsanitised.</p>
     *
     * <p>Alternatives Considered: refusing a non-canonical identifier, as the pending-authorization consumer
     * does. Rejected for this flow because that consumer's identifier participates in a durable outbox row and
     * a deduplication decision, so an unusable value there corrupts stored state; here it is echoed and
     * forgotten within one message.</p>
     *
     * @param message the received message; must not be {@code null}
     * @return the correlation identifier, or an empty string, never {@code null}
     */
    private static String correlationId(Message<String> message) {
        String candidate = attribute(message, ATTRIBUTE_CORRELATION_ID);
        return candidate == null ? "" : candidate;
    }

    /**
     * Reads a message attribute as text.
     *
     * @param message the received message; must not be {@code null}
     * @param name the attribute name; must not be {@code null}
     * @return the attribute value, or {@code null} when absent or not textual
     */
    private static String attribute(Message<String> message, String name) {
        Object value = message.getHeaders().get(name);
        return value instanceof String text ? text : null;
    }

    /**
     * Resolves a queue name to its address, caching the result.
     *
     * @param name the configured queue name; must not be {@code null}
     * @return the queue address, never {@code null}
     * @throws software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException if the queue is absent,
     *     which propagates rather than being defaulted because a consumer that cannot address its reply queue
     *     must not acknowledge a request it cannot answer
     */
    private String queueUrl(String name) {
        return this.queueUrls.computeIfAbsent(name, queueName -> this.sqs
                .getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build())
                .queueUrl());
    }

    /**
     * Wraps a value as a textual message attribute.
     *
     * @param value the value; must not be {@code null}
     * @return the attribute, never {@code null}
     */
    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
