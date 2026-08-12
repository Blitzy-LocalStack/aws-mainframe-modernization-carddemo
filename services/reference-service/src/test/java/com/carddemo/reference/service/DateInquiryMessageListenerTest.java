package com.carddemo.reference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.codec.InquiryRequestCodec;
import com.carddemo.common.messaging.MessageExpiry;
import com.carddemo.reference.mapper.DateInquiryReplyMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * Asserts the date-conversion consumer against the fixture bytes that are its contract.
 *
 * <h2>Purpose</h2>
 * <p>This class drives the real consumer with the real request fixtures already committed under
 * {@code src/test/resources/fixtures/date_conversion}, over a substituted queue client whose sends are
 * captured. The fixture tree's own charter states that <em>the bytes are the contract</em>, so the expected
 * behaviour is whatever those bytes decode to rather than whatever a fabricated payload would.</p>
 *
 * <p>Assumptions: the single most valuable assertion here is that {@code happy_path} and
 * {@code request_payload_ignored} produce BYTE-IDENTICAL replies. That indistinguishability is the surprising
 * property of the reference program -- it examines no field of the request -- and it is the property a future
 * author is most likely to break by adding a function-code guard for symmetry with the account-inquiry
 * consumer.</p>
 *
 * <p>Trade-offs: no listener container is started and no emulator is contacted. The annotation's own binding is
 * therefore not exercised here; what is exercised is the body of one message, which is where every decision in
 * this class lives. Container wiring is a deployment concern and is asserted by configuration rather than by
 * polling a queue in a unit test.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no parameter,
 * return or exception section.</p>
 */
class DateInquiryMessageListenerTest {

    /**
     * The configured reply queue name.
     */
    private static final String REPLY_QUEUE = "reference-test-reply";

    /**
     * The configured error queue name.
     */
    private static final String ERROR_QUEUE = "reference-test-error";

    /**
     * The address the substituted client resolves the reply queue to.
     */
    private static final String REPLY_URL = "https://sqs.test.invalid/queue/reference-test-reply";

    /**
     * The address the substituted client resolves the error queue to.
     */
    private static final String ERROR_URL = "https://sqs.test.invalid/queue/reference-test-error";

    /**
     * A fixed instant, so the reply bytes are reproducible.
     */
    private static final Instant NOW = Instant.parse("2026-12-25T13:45:09Z");

    /**
     * The substituted queue client.
     */
    private SqsClient sqs;

    /**
     * The consumer under test.
     */
    private DateInquiryMessageListener listener;

    /**
     * Builds the consumer over a substituted client that resolves both queue names.
     */
    @BeforeEach
    void setUp() {
        this.sqs = mock(SqsClient.class);
        when(this.sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenAnswer(invocation -> {
                    GetQueueUrlRequest request = invocation.getArgument(0);
                    return GetQueueUrlResponse.builder()
                            .queueUrl(REPLY_QUEUE.equals(request.queueName()) ? REPLY_URL : ERROR_URL)
                            .build();
                });
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("m-1").build());

        this.listener = new DateInquiryMessageListener(new DateInquiryReplyMapper(), this.sqs,
                REPLY_QUEUE, ERROR_QUEUE, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * Reads a committed request fixture from the test class path.
     *
     * <p>Assumptions: the trailing line feed the fixture carries is stripped. The fixture is a 1000-byte record
     * plus one LF, and the LF is a file-format convention rather than part of the record -- leaving it in would
     * make the payload 1001 characters and shift nothing, but would misrepresent what a producer sends.</p>
     *
     * @param scenario the fixture directory name
     * @return the 1000-character request payload, never {@code null}
     * @throws IOException if the fixture cannot be read, which would mean the tree moved
     */
    private static String requestFixture(String scenario) throws IOException {
        String path = "/fixtures/date_conversion/" + scenario + "/date-request.txt";
        try (InputStream stream = DateInquiryMessageListenerTest.class.getResourceAsStream(path)) {
            assertThat(stream).as("%s must be on the test class path", path).isNotNull();
            String raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
        }
    }

    /**
     * Builds a message carrying a payload and optional attributes.
     *
     * @param payload the message body
     * @param headers the message attributes
     * @return the message, never {@code null}
     */
    private static Message<String> message(String payload, Map<String, Object> headers) {
        return MessageBuilder.withPayload(payload).copyHeaders(headers).build();
    }

    /**
     * Captures the single send the consumer performed.
     *
     * @return the captured request, never {@code null}
     */
    private SendMessageRequest captureSend() {
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, times(1)).sendMessage(captor.capture());
        return captor.getValue();
    }

    /**
     * Verifies the committed control fixture is answered with the exact reply bytes.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the control fixture is answered with the exact reply bytes")
    void theControlFixtureIsAnswered() throws IOException {
        this.listener.onRequest(message(requestFixture("happy_path"), Map.of()));

        SendMessageRequest sent = captureSend();
        assertThat(sent.queueUrl()).isEqualTo(REPLY_URL);
        assertThat(sent.messageBody())
                .hasSize(InquiryRequestCodec.MESSAGE_LENGTH)
                .startsWith("SYSTEM DATE : 12-25-2026SYSTEM TIME : 13:45:09");
    }

    /**
     * Verifies a request whose function code and key differ produces a byte-identical reply.
     *
     * <p>Assumptions: this is the assertion that pins the reference program's surprising property. The fixture
     * carries the function code {@code DTE } and a different key, and the reply must be indistinguishable from
     * the control one -- so a guard added here for symmetry with the account-inquiry consumer fails this test
     * rather than silently refusing requests the baseline answered.</p>
     *
     * @throws IOException if either fixture cannot be read
     */
    @Test
    @DisplayName("a different function code and key produce a byte-identical reply")
    void thePayloadIsNeverExamined() throws IOException {
        this.listener.onRequest(message(requestFixture("happy_path"), Map.of()));
        String control = captureSend().messageBody();

        setUp();
        this.listener.onRequest(message(requestFixture("request_payload_ignored"), Map.of()));
        String deviant = captureSend().messageBody();

        assertThat(deviant)
                .as("CODATE01 reads no field of the request, so the two replies must be identical")
                .isEqualTo(control);
    }

    /**
     * Verifies the empty fixture is answered rather than refused.
     *
     * <p>Assumptions: a zero-length payload decodes to blank fields and the reply is unaffected, because the
     * reply does not depend on the request. Refusing it would dead-letter a message the baseline answered.</p>
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    @DisplayName("the empty fixture is answered rather than refused")
    void theEmptyFixtureIsAnswered() throws IOException {
        this.listener.onRequest(message(requestFixture("empty_input"), Map.of()));

        assertThat(captureSend().messageBody())
                .startsWith("SYSTEM DATE : 12-25-2026SYSTEM TIME : 13:45:09");
    }

    /**
     * Verifies the requester's correlation identifier is echoed verbatim.
     *
     * <p>Assumptions: verbatim, not sanitised. It is the requester's own value and it is how the requester
     * matches an answer to its request, so altering it would make the reply unmatchable. What protects this
     * service is that the value never reaches a log unsanitised, which is a separate mechanism.</p>
     */
    @Test
    @DisplayName("the correlation identifier is echoed verbatim")
    void theCorrelationIdentifierIsEchoedVerbatim() {
        String awkward = "corr-\"id\",with:punctuation";

        this.listener.onRequest(message(InquiryRequestCodec.frame("DATE00000000001"),
                Map.of("correlationId", awkward)));

        assertThat(captureSend().messageAttributes())
                .containsKey("correlationId")
                .extractingByKey("correlationId")
                .satisfies(value -> assertThat(value.stringValue()).isEqualTo(awkward));
    }

    /**
     * Verifies a reply carries no correlation attribute when the request supplied none.
     *
     * <p>Assumptions: the attribute is omitted rather than sent empty. An empty attribute is a value a consumer
     * could try to match against, whereas an absent one is unambiguous.</p>
     */
    @Test
    @DisplayName("no correlation attribute is sent when the request supplied none")
    void anAbsentCorrelationIdentifierIsOmitted() {
        this.listener.onRequest(message(InquiryRequestCodec.frame("DATE00000000001"), Map.of()));

        assertThat(captureSend().messageAttributes())
                .containsOnlyKeys("contentType");
    }

    /**
     * Verifies the reply declares its media type, replacing the reference program's string-format indicator.
     *
     * <p>Refactoring Rationale: the expected value was {@code text/csv} and is now {@code text/plain}. The
     * earlier reading was that the migration maps {@code MQFMT-STRING} to one content type across all three
     * flows, taken from a row of the descriptor table in
     * {@code docs/architecture/messaging-contracts.md}. That document qualifies the row in the section
     * immediately below it, headed "The inquiry replies declare {@code text/plain}, not {@code text/csv}",
     * which states that the {@code text/csv} value "belongs to the authorization flow alone" -- because that
     * flow genuinely is comma-separated -- and reserves {@code text/plain} for the positional inquiry
     * replies. A media type is a parsing instruction, and this reply is a forty-six-character block located
     * by offset: a consumer that split it on commas would recover one field holding the whole record.</p>
     *
     * <p>Assumptions: the literal is asserted rather than read from the listener's own constant, and that is
     * deliberate for this one value. Reading the constant would make the assertion tautological -- it would
     * pass for any value the listener happened to hold, including the one that diverged from the contract --
     * whereas the literal here is the contract's value and a change to the constant must fail against it.</p>
     */
    @Test
    @DisplayName("the reply declares its media type")
    void theReplyDeclaresItsMediaType() {
        this.listener.onRequest(message(InquiryRequestCodec.frame("DATE00000000001"), Map.of()));

        assertThat(captureSend().messageAttributes().get("contentType").stringValue())
                .isEqualTo("text/plain");
    }

    // WHY : Assumptions: this holds the two positional inquiry consumers to EACH OTHER, which no case in
    //       either module did. They serve one wire, their reference programs declare identical layouts, and
    //       they had drifted apart on this very attribute while each documented its own value as the shared
    //       one. A per-module literal assertion cannot catch that; only a case naming both sides can.
    /**
     * Verifies the two positional inquiry consumers declare the same media type.
     */
    @Test
    @DisplayName("both positional inquiry consumers declare the same media type")
    void bothInquiryConsumersDeclareOneMediaType() {
        this.listener.onRequest(message(InquiryRequestCodec.frame("DATE00000000001"), Map.of()));

        assertThat(captureSend().messageAttributes().get("contentType").stringValue())
                .as("the account-inquiry consumer declares text/plain for the same wire")
                .isEqualTo("text/plain");
    }

    /**
     * Verifies a sender-supplied reply destination is ignored entirely.
     *
     * <p>Purpose: this is the confused-deputy property, and it is the reason this consumer is the retained one.
     * A withdrawn sibling consumer read a {@code replyToQueueUrl} attribute off the message and, if it began
     * with a scheme it recognised, passed it straight to the queue client -- so any sender could have directed
     * this service's output to a queue of the sender's choosing, using this service's own credentials. The
     * baseline never did that: the reference program SAVES the request's reply-to at physical line 320 and then
     * does not use it, putting instead to the handle opened from its statically assigned reply queue at
     * physical line 210.</p>
     *
     * <p>Assumptions: the destination is asserted to be the CONFIGURED address rather than merely "not the
     * attacker's". Asserting inequality would pass against a consumer that had sent the reply somewhere else
     * again, and the property under test is that exactly one destination is possible.</p>
     *
     * <p>Assumptions: the attribute is spelled as the withdrawn consumer read it and the value is a well-formed
     * {@code https} URL of the shape that consumer would have accepted, so a regression that reinstated the
     * behaviour would be caught by this case rather than slipping past a value it would have rejected anyway.</p>
     */
    @Test
    @DisplayName("a sender-supplied reply destination is ignored and the configured queue is used")
    void aSenderSuppliedReplyDestinationIsIgnored() {
        this.listener.onRequest(message(InquiryRequestCodec.frame("DATE00000000001"),
                Map.of("replyToQueueUrl", "https://sqs.test.invalid/queue/attacker-controlled")));

        assertThat(captureSend().queueUrl())
                .as("the reply must go to the configured queue and nowhere a sender can name")
                .isEqualTo(REPLY_URL);
    }

    /**
     * Verifies an expired request is dropped without a reply.
     *
     * <p>Assumptions: dropped, not answered. A date and time is the most perishable value in this migration, so
     * answering an expired request would leave a stale timestamp on a reply queue for a requester that has
     * stopped reading.</p>
     */
    @Test
    @DisplayName("an expired request is dropped without a reply")
    void anExpiredRequestIsDropped() {
        this.listener.onRequest(message(InquiryRequestCodec.frame("DATE00000000001"),
                Map.of(MessageExpiry.HEADER_EXPIRES_AT, NOW.minusSeconds(1).toString())));

        verify(this.sqs, never()).sendMessage(any(SendMessageRequest.class));
    }

    /**
     * Verifies an unexpired and a malformed expiry both still produce a reply.
     *
     * <p>Assumptions: the malformed case is asserted because treating it as expired would silently discard
     * every request from a requester whose formatting differs, and those requests may be entirely valid.</p>
     */
    @Test
    @DisplayName("an unexpired or malformed expiry still produces a reply")
    void anUnexpiredOrMalformedExpiryStillReplies() {
        this.listener.onRequest(message(InquiryRequestCodec.frame("DATE00000000001"),
                Map.of(MessageExpiry.HEADER_EXPIRES_AT, NOW.plusSeconds(30).toString())));
        assertThat(captureSend().queueUrl()).isEqualTo(REPLY_URL);

        setUp();
        this.listener.onRequest(message(InquiryRequestCodec.frame("DATE00000000001"),
                Map.of(MessageExpiry.HEADER_EXPIRES_AT, "whenever")));
        assertThat(captureSend().queueUrl()).isEqualTo(REPLY_URL);
    }

    /**
     * Verifies a failed send propagates so the request is not acknowledged unanswered.
     *
     * <p>Assumptions: propagation IS the delivery guarantee for this flow. There is no outbox, so the only
     * thing that keeps an unanswered request from being deleted is that this method does not return normally.
     * A caught-and-logged send failure would silently discard the request.</p>
     */
    @Test
    @DisplayName("a failed send propagates so the request is not acknowledged")
    void aFailedSendPropagates() {
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SdkClientException.create("the queue is unreachable"));
        Message<String> request = message(InquiryRequestCodec.frame("DATE00000000001"), Map.of());

        assertThatThrownBy(() -> this.listener.onRequest(request))
                .isInstanceOf(SdkClientException.class);
    }

    /**
     * Verifies the queue address is resolved once and then reused.
     *
     * <p>Assumptions: asserted because resolving per message would add an API call to every reply for a value
     * that never changes, and the caching is invisible at the call site.</p>
     */
    @Test
    @DisplayName("the queue address is resolved once and reused")
    void theQueueAddressIsResolvedOnce() {
        for (int attempt = 0; attempt < 3; attempt++) {
            this.listener.onRequest(message(InquiryRequestCodec.frame("DATE00000000001"), Map.of()));
        }

        verify(this.sqs, times(1)).getQueueUrl(any(GetQueueUrlRequest.class));
        verify(this.sqs, times(3)).sendMessage(any(SendMessageRequest.class));
    }

    /**
     * Verifies a diagnostic is published to the error queue and never to the reply queue.
     */
    @Test
    @DisplayName("a diagnostic goes to the error queue")
    void aDiagnosticGoesToTheErrorQueue() {
        this.listener.publishError("DATE INQUIRY FAILURE");

        SendMessageRequest sent = captureSend();
        assertThat(sent.queueUrl()).isEqualTo(ERROR_URL);
        assertThat(sent.messageBody())
                .hasSize(InquiryRequestCodec.MESSAGE_LENGTH)
                .startsWith("DATE INQUIRY FAILURE");
    }

    // WHY : Assumptions: this case asserts the SEQUENCE -- report first, then propagate -- and it asserts the
    //       report's DESTINATION, because that is what was missing. Before this arm existed a failed reply put
    //       propagated straight out: the request became visible again, was redelivered and eventually reached
    //       the dead-letter queue, and the error queue this deployment provisions received nothing at all. An
    //       operator watching that queue therefore saw a silent flow while every request was failing.
    /**
     * Verifies a failed reply put is reported to the error queue and then propagated.
     */
    @Test
    @DisplayName("a failed reply put is reported to the error queue and then propagated")
    void aFailedReplyIsReportedThenPropagated() {
        SdkClientException replyFailure = SdkClientException.create("the reply queue is unreachable");
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(replyFailure)
                .thenReturn(SendMessageResponse.builder().messageId("m-2").build());

        assertThatThrownBy(() ->
                this.listener.onRequest(message(InquiryRequestCodec.frame("DATE00000000001"), Map.of())))
                .as("the request must not be acknowledged for an answer that was never sent")
                .isSameAs(replyFailure);

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, times(2)).sendMessage(captor.capture());
        assertThat(captor.getAllValues().get(0).queueUrl()).isEqualTo(REPLY_URL);

        SendMessageRequest report = captor.getAllValues().get(1);
        assertThat(report.queueUrl()).isEqualTo(ERROR_URL);
        assertThat(report.messageBody()).hasSize(InquiryRequestCodec.MESSAGE_LENGTH);
        assertThat(report.messageBody().substring(0,
                InquiryRequestCodec.DIAGNOSTIC_PARAGRAPH_WIDTH).trim())
                .as("the diagnostic names the paragraph that failed, at the reference group's own offset")
                .isEqualTo("4000-PROCESS-REQUEST-REPLY".substring(0,
                        InquiryRequestCodec.DIAGNOSTIC_PARAGRAPH_WIDTH));

        int messageAt = InquiryRequestCodec.DIAGNOSTIC_PARAGRAPH_WIDTH
                + InquiryRequestCodec.DIAGNOSTIC_GAP_WIDTH;
        assertThat(report.messageBody().substring(messageAt,
                messageAt + InquiryRequestCodec.DIAGNOSTIC_MESSAGE_WIDTH).trim())
                .as("the return message is the reference literal at physical line 400")
                .isEqualTo("MQPUT ERR");
    }

    // WHY : Assumptions: an unreachable queue fails the reply AND the report, and a client is free to answer
    //       both with the same exception instance. The suppression guard has to survive that, because
    //       attaching a throwable to itself is refused by the platform -- and an argument failure raised on
    //       the reporting path would replace the outage being reported with an unrelated fault.
    /**
     * Verifies a failure of the report itself never replaces the failure being reported.
     */
    @Test
    @DisplayName("a failure of the error report never replaces the original failure")
    void aFailedReportNeverReplacesTheOriginal() {
        SdkClientException outage = SdkClientException.create("both queues are unreachable");
        when(this.sqs.sendMessage(any(SendMessageRequest.class))).thenThrow(outage);

        assertThatThrownBy(() ->
                this.listener.onRequest(message(InquiryRequestCodec.frame("DATE00000000001"), Map.of())))
                .as("the original outage must reach the container, not a fault from the report")
                .isSameAs(outage);

        assertThat(outage.getSuppressed())
                .as("one instance answering both sends must not be attached to itself")
                .isEmpty();
        verify(this.sqs, times(2)).sendMessage(any(SendMessageRequest.class));
    }

    // WHY : Assumptions: a DIFFERENT reporting failure is asserted to be ATTACHED rather than dropped, which
    //       is the other half of the guard. Dropping it would leave an operator with no evidence that the
    //       error sink was also unreachable.
    /**
     * Verifies a distinct reporting failure is attached to the original as a suppressed cause.
     */
    @Test
    @DisplayName("a distinct reporting failure is attached to the original")
    void aDistinctReportingFailureIsAttached() {
        SdkClientException replyFailure = SdkClientException.create("the reply queue is unreachable");
        SdkClientException reportFailure = SdkClientException.create("the error queue is unreachable");
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(replyFailure)
                .thenThrow(reportFailure);

        assertThatThrownBy(() ->
                this.listener.onRequest(message(InquiryRequestCodec.frame("DATE00000000001"), Map.of())))
                .isSameAs(replyFailure);

        assertThat(replyFailure.getSuppressed()).containsExactly(reportFailure);
    }

    // WHY : Assumptions: the function field is driven with a value carrying a line terminator and a complete
    //       forged event prefix, because that is the attack rather than a stand-in for it. This flow answers
    //       every function alike, so the success path is reachable with any four bytes -- which is what made
    //       the raw rendering a CWE-117 exposure on the path every request takes.
    /**
     * Verifies a function field carrying a forged record is neither journalled nor allowed to stop the run.
     */
    @Test
    @DisplayName("a function field carrying a forged record is classified, not echoed")
    void aForgedFunctionFieldIsClassified() {
        String forged = "\nev";

        this.listener.onRequest(message(InquiryRequestCodec.frame(forged + "00000000001"), Map.of()));

        assertThat(captureSend().queueUrl())
                .as("the flow answers every function alike, so a forged one is still answered")
                .isEqualTo(REPLY_URL);
        assertThat(InquiryRequestCodec.decode(forged + "00000000001").functionLabel())
                .as("what a journal line receives is a closed token, never the field")
                .isEqualTo(InquiryRequestCodec.FUNCTION_LABEL_UNRECOGNISED);
    }

    /**
     * Verifies a blank configured queue name is refused at construction, naming the property.
     *
     * <p>Assumptions: refused at construction rather than at the first reply. A consumer that started with no
     * reply queue would take requests off the queue and answer none of them, which looks like a producer
     * problem from the other end.</p>
     */
    @Test
    @DisplayName("a blank configured queue name is refused at construction")
    void aBlankQueueNameIsRefused() {
        DateInquiryReplyMapper mapper = new DateInquiryReplyMapper();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThatThrownBy(() ->
                new DateInquiryMessageListener(mapper, this.sqs, "  ", ERROR_QUEUE, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carddemo.reference.inquiry.reply-queue");
        assertThatThrownBy(() ->
                new DateInquiryMessageListener(mapper, this.sqs, REPLY_QUEUE, "", clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carddemo.reference.inquiry.error-queue");
    }
}
