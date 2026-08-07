package com.carddemo.reference.service;

import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.reference.dto.DateConversionRequest;
import com.carddemo.reference.dto.DateConversionResponse;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The date evaluation, and the one asynchronous entry point of this context.
 *
 * <p>Purpose: reports a verdict on a candidate date, reached either synchronously through the published
 * read or asynchronously over the queue the baseline reaches it over. Both routes evaluate through the
 * same member, so the two cannot drift.</p>
 *
 * <p>Assumptions: the date edit rules are NOT restated here. They live in
 * {@code com.carddemo.common.validation.DateEditValidator}, and delegating to them reproduces the
 * baseline's own shape rather than taking a liberty: the baseline's date utility is itself a callable
 * subprogram, declaring a procedure division that takes the date, the format and a result and handing
 * control back. One shared implementation is what migration rule T2 requires, one former include
 * becoming one import.</p>
 *
 * <p>Assumptions: the request payload's fields are examined for presence and width and for nothing else,
 * because the baseline program examines no field of the request at all -- it answers with the system
 * date. That is why the fixture directory for this flow is named for the payload being ignored, and why
 * there is no invalid-date branch on the asynchronous route to exercise.</p>
 */
@Service
public class DateConversionMessageListener {

    /**
     * The picture applied when a caller supplies none.
     *
     * <p>Assumptions: the ISO-ordered form, which is the default the published contract declares. It is
     * also the form whose lexical order equals its date order, which is why the migration stores dates
     * of this shape as characters elsewhere without losing comparability.</p>
     */
    public static final String DEFAULT_MASK = "YYYY-MM-DD";

    /** The declared length of the asynchronous reply buffer. */
    public static final int REPLY_LENGTH = 1000;

    /** The verbatim label preceding the date, trailing space included. */
    public static final String DATE_PREFIX = "CURRENT DATE IS ";

    /** The verbatim label preceding the time, trailing space included. */
    public static final String TIME_PREFIX = "CURRENT TIME IS ";

    /** The message header carrying the correlation identifier both ways. */
    public static final String HEADER_CORRELATION_ID = "correlationId";

    /** The month-first hyphenated rendering the platform call produces. */
    private static final DateTimeFormatter REPLY_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd-yyyy");

    /** The colon-separated rendering of the time of day. */
    private static final DateTimeFormatter REPLY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Publishes the reply buffer. */
    private final SqsTemplate replies;

    /** The queue the reply is published to. */
    private final String replyQueue;

    /** The clock the reply is read from, injected so a test is deterministic. */
    private final Clock clock;

    /**
     * Builds the listener over the reply publisher and the clock.
     *
     * <p>Assumptions: the clock is injected rather than read statically, because the reply IS the
     * current date and a test asserting the buffer must be able to fix it. The baseline injects its
     * business date for the same reason wherever a rerun has to be reproducible.</p>
     *
     * @param replies the publisher for the reply queue; must not be {@code null}
     * @param replyQueue the reply queue name, supplied by configuration; must not be {@code null}
     * @param clock the clock the reply is read from; must not be {@code null}
     */
    public DateConversionMessageListener(
            SqsTemplate replies,
            @Value("${carddemo.reference.inquiry.reply-queue}") String replyQueue,
            Clock clock) {
        this.replies = replies;
        this.replyQueue = replyQueue;
        this.clock = clock;
    }

    /**
     * Evaluates one candidate date and reports the verdict.
     *
     * <p>Assumptions: the mask defaults here rather than on the request shape. Defaulting on the shape
     * would make an omitted mask indistinguishable from an explicitly supplied one, and the reply echoes
     * back the mask actually applied so that a caller can tell which it got.</p>
     *
     * @param request the validated candidate date and optional mask; must not be {@code null}
     * @return the verdict, carrying both the named feedback code and the numeric severity and message
     *     number the baseline utility produces
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public DateConversionResponse evaluate(DateConversionRequest request) {
        String mask = request.mask() == null ? DEFAULT_MASK : request.mask();
        DateEditValidator.LanguageEnvironmentResult result =
                DateEditValidator.evaluateWithLanguageEnvironment(request.date(), mask);

        // WHY : Assumptions: both the named code and the two numbers are carried across. The numbers are
        //       what the baseline actually returns and the only values that can be reconciled against it;
        //       the name is what a caller can branch on without embedding a numeric table. Publishing one
        //       without the other would either oblige every client to carry that table or make the reply
        //       impossible to reconcile against the utility it transcribes.
        return new DateConversionResponse(
                result.feedbackCode().name(),
                result.severity(),
                result.messageNumber(),
                result.verdict(),
                result.date(),
                result.mask());
    }

    /**
     * Answers one asynchronous date request over the queue pair.
     *
     * <p>Purpose: the migrated form of the baseline's request-and-reply flow. The request buffer is
     * received, the reply buffer is composed and published to the reply queue, and the receive and the
     * publish sit inside one transaction.</p>
     *
     * <p>Assumptions: the reply is published INSIDE the transaction and no outbox is used, which is the
     * opposite of the authorization consumer and is faithful to this flow's own discipline. The baseline
     * program reads under syncpoint and publishes under syncpoint, so there is no window in which the
     * work is committed and the reply is lost. Adding an outbox here would add machinery this flow has no
     * need of; omitting it there would drop machinery that flow cannot do without.</p>
     *
     * <p>Assumptions: no field of the request buffer is read. The baseline examines none either -- it
     * answers with the system date -- so parsing the payload would invent a contract the reference does
     * not have, and an invalid-date branch on this route would be unreachable.</p>
     *
     * @param message the received request buffer, whose content is deliberately not interpreted
     * @throws NullPointerException if {@code message} is {@code null}
     */
    @SqsListener(queueNames = "${carddemo.reference.inquiry.request-queue}",
            maxConcurrentMessages = "${carddemo.reference.inquiry.max-concurrent-messages:10}",
            maxMessagesPerPoll = "${carddemo.reference.inquiry.max-messages-per-poll:10}",
            pollTimeoutSeconds = "${carddemo.reference.inquiry.poll-timeout-seconds:5}")
    @Transactional
    public void onDateRequest(Message<String> message) {
        // WHY : Assumptions: the correlation identifier is carried from the request onto the reply so a
        //       requester can pair the two, which is the SQS equivalent of the message-descriptor field
        //       the baseline relies on. Nothing else of the request travels, because nothing else of it
        //       is read.
        Object correlation = message.getHeaders().get(HEADER_CORRELATION_ID);
        this.replies.send(sendOptions -> sendOptions
                .queue(this.replyQueue)
                .payload(composeReply())
                .header(HEADER_CORRELATION_ID,
                        correlation == null ? message.getHeaders().getId() : correlation));
    }

    /**
     * Composes the fixed-width reply buffer the baseline publishes.
     *
     * <p>Assumptions: the buffer is exactly {@link #REPLY_LENGTH} characters, holding two labelled values
     * concatenated and left-justified with the remainder blank. The two labels are reproduced character
     * for character from the baseline program, trailing space included, because with a string-format
     * payload the label text and the field order ARE the contract -- a requester locates the values by
     * position, so a label one character shorter shifts everything after it.</p>
     *
     * <p>Assumptions: the date is rendered month-first with hyphen separators, which is what the
     * platform call the baseline uses produces. It is deliberately NOT the ISO order used elsewhere in
     * this service: this buffer is a wire format being preserved, not a new interface being designed.</p>
     *
     * @return the reply buffer, exactly {@link #REPLY_LENGTH} characters
     */
    private String composeReply() {
        LocalDateTime now = LocalDateTime.now(this.clock);
        String body = DATE_PREFIX + now.format(REPLY_DATE_FORMAT)
                + TIME_PREFIX + now.format(REPLY_TIME_FORMAT);
        if (body.length() >= REPLY_LENGTH) {
            return body.substring(0, REPLY_LENGTH);
        }
        return body + " ".repeat(REPLY_LENGTH - body.length());
    }
}
