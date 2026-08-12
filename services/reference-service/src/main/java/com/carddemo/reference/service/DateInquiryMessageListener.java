package com.carddemo.reference.service;

import com.carddemo.common.codec.InquiryRequestCodec;
import com.carddemo.common.codec.InquiryRequestCodec.InquiryRequest;
import com.carddemo.common.messaging.MessageExpiry;
import com.carddemo.common.messaging.MessagingCorrelationId;
import com.carddemo.common.observability.ThrowableDigest;
import com.carddemo.reference.mapper.DateInquiryReplyMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * The asynchronous date-conversion entry point, replacing the queue-driven transaction that answered it.
 *
 * <h2>Purpose</h2>
 * <p>This class is the migrated form of {@code app/app-vsam-mq/cbl/CODATE01.cbl} (524 lines), a queue-triggered
 * CICS transaction that reads a fixed one-thousand-character request and answers with the system date and time
 * on a configured reply queue. That file is REFERENCE-ONLY: it is read as the specification for this class and
 * is never modified.</p>
 *
 * <p>The reference program's {@code 1000-CONTROL} opens three queues and loops until the request queue is
 * empty; the loop, the queue handles and the five-second bounded wait at physical line 286 all become
 * listener-container properties, so this class holds only the body of one iteration -- its
 * {@code 4000-PROCESS-REQUEST-REPLY} at physical lines 339 to 364.</p>
 *
 * <h2>This flow reads no database and applies no guard, and both are deliberate</h2>
 * <p>Assumptions: the reference program does NOT inspect the request. {@code WS-FUNC} appears exactly once in
 * the whole file -- in its declaration at physical line 110 -- and no branch tests it or the key. The program
 * answers ANY message on its request queue with the current date and time. This class therefore applies no
 * guard either.</p>
 *
 * <p>Alternatives Considered: adding a function-code guard for symmetry with the account-inquiry consumer,
 * which does apply one. Rejected because it would refuse requests the baseline answers, and because the two
 * flows differ for a reason: the account inquiry needs a key to look something up, while this flow's answer
 * does not depend on its input at all. Imposing one flow's rule on the other would be a behavioural change
 * dressed as consistency. The request is still DECODED, so that a diagnostic can report which KIND of request
 * arrived -- a closed classification of the function field, never its bytes -- and so that a future consumer
 * of the fields does not have to reconstruct the layout.</p>
 *
 * <h2>Delivery discipline: no outbox</h2>
 * <p>Assumptions: as with the account-inquiry consumer, and for the same reason drawn from the reference
 * program rather than from preference: CODATE01 gets and puts inside one unit of work -- physical line 296
 * computes {@code MQGMO-OPTIONS} as {@code MQGMO-SYNCPOINT} and physical line 379 computes
 * {@code MQPMO-OPTIONS} as {@code MQPMO-SYNCPOINT} -- so there is no window in which work is committed and the
 * reply is lost. The target discipline is delete-on-success plus the queue's visibility timeout: this method
 * returns normally only after the reply has been sent, so a failed send propagates, the request becomes visible
 * again, and repeated failure carries it to the dead-letter queue at the configured receive count.</p>
 *
 * <p>Assumptions: a failure is REPORTED to the configured error queue before it propagates, which is the
 * reference program's own order -- its reply-put failure branch at physical lines 396 to 402 fills the
 * diagnostic group, performs {@code 9000-ERROR} and only then terminates. The report is a positional
 * fixed-width diagnostic carrying no wire content, and a failure of the report itself is attached to the
 * original rather than replacing it.</p>
 *
 * <p>Assumptions: no database transaction is declared on the listener, unlike the account-inquiry consumer's.
 * This flow reads no row, so a transaction would open and commit a connection to protect nothing while holding
 * one from the pool for the duration of a queue send.</p>
 *
 * <h2>The reply goes to the configured queue, never to one the message names</h2>
 * <p>Assumptions: the reference program saves the request's reply-to queue at physical line 320 and then does
 * not use it -- {@code 4100-PUT-REPLY} puts to the handle opened from the statically assigned
 * {@code REPLY-QUEUE-NAME} at physical line 210. Honouring the message's own reply-to would be unfaithful and
 * would let a sender direct this service's output to a queue of its choosing.</p>
 */
@Service
public class DateInquiryMessageListener {

    /**
     * The logger for this consumer.
     */
    private static final Logger LOG = LoggerFactory.getLogger(DateInquiryMessageListener.class);

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
     * target equivalent is an explicit content-type attribute, because the payload is a fixed-width text block
     * read by offset and a consumer must not treat it as a structured document.</p>
     */
    private static final String ATTRIBUTE_CONTENT_TYPE = "contentType";

    /**
     * The media type the fixed-width reply and the fixed-width error diagnostic are published as.
     *
     * <p>Assumptions: a media type is a PARSING INSTRUCTION, so the value has to describe how this payload
     * is actually laid out. Nothing this class publishes is delimited: the reply is the
     * forty-six-character positional block {@code DateInquiryReplyMapper} renders and pins by offset, and
     * the diagnostic is the positional nine-member group {@code InquiryRequestCodec.errorDiagnostic}
     * composes. A consumer that split either on commas would recover one field holding the whole record.</p>
     *
     * <p>Refactoring Rationale: this constant held {@code text/csv}, and the value was wrong on the
     * contract's own terms. {@code docs/architecture/messaging-contracts.md} carries a section headed "The
     * inquiry replies declare {@code text/plain}, not {@code text/csv}", which states that the
     * {@code text/csv} row of the descriptor table "belongs to the authorization flow alone" -- that flow
     * really is delimited, eighteen comma-separated request fields and six reply fields through
     * {@code com.carddemo.common.codec.CsvAuthCodec} -- and reserves {@code text/plain} for the positional
     * inquiry replies. The earlier rationale here read the descriptor-table row as an across-all-flows
     * mapping and did not mention the section that qualifies it. Two further claims it made were false in
     * their own right: it cited {@code DateConversionMessageListener.CONTENT_TYPE} as an in-repository
     * corroborator, and no such type exists -- it was superseded by
     * {@code com.carddemo.reference.service.DateConversionService}, which records the supersession in its own
     * documentation; and {@code com.carddemo.account.service.InquiryMessageListener} declares
     * {@code text/plain} rather than the {@code text/csv} the rationale implied, so the two positional
     * inquiry consumers were the ones disagreeing while each described its own value as the shared one.</p>
     *
     * <p>Trade-offs: {@code text/plain} states less than a registered fixed-width type would. No such type
     * is registered, and inventing one under an {@code application/vnd.} name would give consumers a label
     * no library recognises while still telling them nothing about the offsets. What matters at this
     * boundary is the negative claim -- this is not delimited -- and {@code text/plain} makes it in a value
     * every client library already understands. This is the same reasoning, and the same value, that the
     * account-inquiry consumer records for the same wire.</p>
     */
    private static final String CONTENT_TYPE_FIXED_WIDTH = "text/plain";

    // WHY : Assumptions: the reported paragraph name is the BASELINE's paragraph rather than a Java method
    //   name, because an operator reading this sink is diagnosing against the reference program and a name
    //   only the target uses would not locate anything in it.
    // WHY : Trade-offs: the reference's own diagnostic would NOT carry this name on a reply-put failure. Its
    //   paragraph field is set once, to 'CICS RETRIEVE' at physical line 149, and no later branch resets it
    //   -- so a failure in 4000-PROCESS-REQUEST-REPLY reports the paragraph that ran at start-up. Naming the
    //   paragraph that actually failed is a deliberate, documented improvement rather than a parity claim,
    //   and it matches what the account-inquiry consumer already reports for the identical group.
    private static final String PARAGRAPH_PROCESS_REQUEST_REPLY = "4000-PROCESS-REQUEST-REPLY";

    /**
     * The verbatim return message the baseline reports for a failed reply put.
     *
     * <p>Assumptions: this is the literal moved into the diagnostic's return-message field at physical line
     * 400 of {@code app/app-vsam-mq/cbl/CODATE01.cbl}, carried across character for character.</p>
     */
    private static final String DIAGNOSTIC_PUT_FAILED = "MQPUT ERR";

    /**
     * The renderer for the reply body.
     */
    private final DateInquiryReplyMapper replies;

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
     * The clock both the reply value and the expiry comparison read.
     *
     * <p>Assumptions: ONE clock serves both, so a reply cannot report an instant that the same message's expiry
     * check judged against a different one.</p>
     */
    private final Clock clock;

    /**
     * Resolved queue addresses, cached by name.
     *
     * <p>Assumptions: a queue's address is stable for the life of the queue, and this service is configured to
     * fail rather than create an absent queue, so a cached entry cannot become a pointer to a queue this system
     * did not provision. Resolving per message would add an API call to every reply for a value that never
     * changes.</p>
     */
    private final Map<String, String> queueUrls = new ConcurrentHashMap<>();

    /**
     * Creates the consumer.
     *
     * @param replies the reply renderer; must not be {@code null}
     * @param sqs the queue client; must not be {@code null}
     * @param replyQueue the configured reply queue name; must not be {@code null} or blank
     * @param errorQueue the configured error queue name; must not be {@code null} or blank
     * @param clock the clock the reply value and the expiry check read; must not be {@code null}
     * @throws NullPointerException if any reference argument is {@code null}
     * @throws IllegalArgumentException if either queue name is blank, because a consumer that cannot address
     *     its reply queue would take requests off the queue and answer none of them
     */
    public DateInquiryMessageListener(DateInquiryReplyMapper replies,
            SqsClient sqs,
            @Value("${carddemo.reference.inquiry.reply-queue}") String replyQueue,
            @Value("${carddemo.reference.inquiry.error-queue}") String errorQueue,
            Clock clock) {
        this.replies = Objects.requireNonNull(replies, "replies must not be null");
        this.sqs = Objects.requireNonNull(sqs, "sqs must not be null");
        this.replyQueue = requireQueueName(replyQueue, "carddemo.reference.inquiry.reply-queue");
        this.errorQueue = requireQueueName(errorQueue, "carddemo.reference.inquiry.error-queue");
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
     * Answers one date-conversion request.
     *
     * <p>Assumptions: a request whose expiry has passed is DROPPED, not answered, using the shared rule in
     * {@link MessageExpiry} so that all three consumers in this system honour a requester's expiry identically.
     * A date and time is the most perishable value in this migration -- answering an expired request would put
     * a stale timestamp on a reply queue for a requester that has stopped reading.</p>
     *
     * @param message the received message, whose payload is the fixed-width request and whose attributes carry
     *     the correlation identifier and the optional expiry; must not be {@code null}
     * @throws NullPointerException if {@code message} is {@code null}
     */
    @SqsListener(queueNames = "${carddemo.reference.inquiry.request-queue}",
            maxConcurrentMessages = "${carddemo.reference.inquiry.max-concurrent-messages:10}",
            maxMessagesPerPoll = "${carddemo.reference.inquiry.max-messages-per-poll:10}",
            pollTimeoutSeconds = "${carddemo.reference.inquiry.poll-timeout-seconds:5}")
    public void onRequest(Message<String> message) {
        Objects.requireNonNull(message, "message must not be null");
        String correlationId = correlationId(message);

        // WHY : Assumptions: the LOGGING context carries the sanitised rendering while the reply carries the
        //   value verbatim. A log record must not be able to carry a delimiter or a line terminator, and a
        //   reply must carry the requester's own bytes so it can match the answer to its request.
        MDC.put(MDC_CORRELATION_ID, MessagingCorrelationId.logSafe(correlationId));
        try {
            String rawExpiry = attribute(message, MessageExpiry.HEADER_EXPIRES_AT);
            if (MessageExpiry.isMalformed(rawExpiry)) {
                // WHY : Trade-offs: an unparseable expiry is treated as ABSENT rather than as already passed,
                //   because treating it as passed would silently discard every request from a requester whose
                //   formatting differs. Only the LENGTH is logged: the value came off the wire.
                LOG.warn("event=date.inquiry.expiry-unparseable length={}", rawExpiry.trim().length());
            } else if (MessageExpiry.isExpired(rawExpiry, this.clock.instant())) {
                LOG.warn("event=date.inquiry.dropped reason=expired");
                return;
            }

            // WHY : Assumptions: the request is decoded even though no field of it is tested, for two reasons.
            //   It refuses nothing, so decoding costs a substring and cannot fail; and the decoded function
            //   code is what makes the log line below able to report what a requester actually sent, which is
            //   the only diagnostic available for a flow whose answer is input-independent.
            InquiryRequest request = InquiryRequestCodec.decode(message.getPayload());
            LocalDateTime now = LocalDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC);

            // WHY : Refactoring Rationale: the CLASSIFICATION is journalled, where the trimmed function code
            //   itself was. That field is four characters of wire content and this flow constrains it in no
            //   way at all -- it answers every value alike -- so a producer could put a line terminator and a
            //   forged event prefix in it and write its own records into this journal. That is CWE-117, and
            //   it was reachable on the SUCCESS path here, which is the path every request takes.
            // WHY : Trade-offs: the line no longer reports what a requester sent, which was its stated
            //   purpose -- "the only diagnostic available for a flow whose answer is input-independent". The
            //   classification keeps the part of that which is actionable: whether the request named the
            //   inquiry function this system serves, named nothing, or named something else. The bytes
            //   themselves changed no outcome, because this flow branches on none of them.
            LOG.info("event=date.inquiry.answered function={}", request.functionLabel());
            publishReply(this.replies.frame(this.replies.systemDateAndTime(now)), correlationId);
        } catch (RuntimeException failure) {
            // WHY : Refactoring Rationale: this arm did not exist, and its absence was a parity gap rather
            //   than an omission of convenience. The reference program answers a failed reply put by moving
            //   the queue name and the literal 'MQPUT ERR' into its diagnostic group at physical lines 399
            //   and 400, performing 9000-ERROR at 401 to put that diagnostic on a SEPARATE error queue, and
            //   only then terminating at 402. Without this arm a decode or send failure propagated straight
            //   out: the request became visible again, was redelivered, and eventually reached the
            //   dead-letter queue -- and nothing was ever written to the error queue the deployment
            //   provisions and this class is configured with, so an operator watching that queue saw a
            //   silent flow while requests were failing.
            // WHY : Assumptions: the failure is REPORTED and then RETHROWN, in that order, which is the
            //   reference's own order. Swallowing it would acknowledge a request this consumer did not
            //   answer, and reporting after the rethrow is not possible.
            reportFailure(failure);
            throw failure;
        } finally {
            MDC.remove(MDC_CORRELATION_ID);
        }
    }

    /**
     * Reports an unexpected failure to the error sink without letting the report replace it.
     *
     * <p>Purpose: this is the target form of the reference program's failure branch at physical lines 396 to
     * 402, which fills the diagnostic fields, performs {@code 9000-ERROR} and only then performs
     * {@code 8000-TERMINATION}. The caller rethrows afterwards, which is this flow's form of that
     * termination.</p>
     *
     * <p>Assumptions: a failure in the REPORT is attached to the original failure rather than thrown, so an
     * unreachable error sink can never disguise the fault an operator is actually looking for. That matters
     * concretely here: when the queue client is what failed, the report will fail for the same reason, and
     * without this guard the second failure would replace the first on its way out.</p>
     *
     * <p>Assumptions: the report's failure is attached only when it is a DIFFERENT object from the original.
     * Attaching a throwable to itself is rejected outright by the platform, so a client that answers both
     * sends with one exception instance -- which a client is free to do, and which a single unreachable queue
     * makes reachable because it fails the reply and the report identically -- would turn a queue outage into
     * an unrelated argument failure and lose the outage entirely. The guard is an identity comparison rather
     * than an equality one because it is object identity the platform refuses.</p>
     *
     * <p>Assumptions: the failure is rendered as its chain of TYPES with no message text, by the shared
     * digest. A driver's or parser's own message is the one part of a failure into which a request value can
     * be interpolated, and this buffer is published onto a queue, so the type chain answers what failed
     * without opening that channel. It is the same reason this class logs an expiry's LENGTH rather than its
     * value.</p>
     *
     * @param failure the failure to report; must not be {@code null}
     * @throws NullPointerException if {@code failure} is {@code null}, raised by the digest below. It is the
     *     ONE precondition here whose violation is not swallowed: every other failure inside this method is
     *     attached to {@code failure} and suppressed deliberately, so a null argument is the only way this
     *     method can throw, and it means the caller had no failure to report
     */
    private void reportFailure(RuntimeException failure) {
        String digest = ThrowableDigest.of(failure);
        LOG.error("event=date.inquiry.error-sink paragraph={} failure={}",
                PARAGRAPH_PROCESS_REQUEST_REPLY, digest);

        try {
            publishError(InquiryRequestCodec.errorDiagnostic(PARAGRAPH_PROCESS_REQUEST_REPLY,
                    DIAGNOSTIC_PUT_FAILED, this.errorQueue, digest));
        } catch (RuntimeException reportingFailure) {
            if (reportingFailure != failure) {
                failure.addSuppressed(reportingFailure);
            }
        }
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
     * physical lines 405 to 425, which puts a fixed-width diagnostic block on a separate error queue.</p>
     *
     * <p>Refactoring Rationale: this documentation said the operation was "deliberately NOT called from the
     * reply path, because this flow has no business outcome that would warrant one". The premise was right
     * and the conclusion did not follow: a flow with no business refusal can still fail TECHNICALLY, and the
     * reference reports exactly that -- its reply-put failure branch at physical lines 396 to 402 performs
     * this paragraph. The operation now has a production caller,
     * {@link #reportFailure(RuntimeException)}, and it stays public because the sink is reachable in the
     * baseline before any request exists: {@code 1000-CONTROL} opens the error queue ahead of the input and
     * output queues and enters {@code 9000-ERROR} on a start-up failure, at a point where there is nothing
     * to reply to.</p>
     *
     * @param diagnostic the diagnostic text, which must name no value that came off the wire; must not be
     *     {@code null}
     * @throws NullPointerException if {@code diagnostic} is {@code null}
     * @throws IllegalArgumentException if the text is longer than the message length, which is a defect in
     *     the caller's own formatting rather than a wire condition and must not be silently truncated
     * @throws software.amazon.awssdk.core.exception.SdkException if the send fails
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
     * <p>Assumptions: an absent identifier is accepted, because the reference program accepts one -- it
     * initialises its correlation field to spaces and echoes whatever the descriptor carried, including
     * nothing. A non-canonical one is accepted and echoed unchanged, because it is the requester's own value
     * and the reply is useless to it otherwise; what protects this service is that the value never reaches a
     * log unsanitised.</p>
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
