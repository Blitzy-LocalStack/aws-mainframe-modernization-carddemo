package com.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.account.domain.Account;
import com.carddemo.account.mapper.AccountInquiryReplyMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.common.codec.InquiryRequestCodec;
import com.carddemo.common.messaging.MessageExpiry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
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
 * Asserts the account-inquiry consumer's answers, its refusals and its delivery discipline.
 *
 * <h2>Purpose</h2>
 * <p>This class drives the real consumer over a substituted repository and queue client whose sends are
 * captured. The four outcomes the reference program can produce are asserted separately -- an answered
 * inquiry, a lookup that found nothing, a request that failed the function-and-key guard, and an expired
 * request -- because each corresponds to a distinct branch of
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl} and merging any two would lose a distinction a requester relies
 * on.</p>
 *
 * <p>Assumptions: the most important assertions here are about what does NOT happen. A not-found inquiry must
 * NOT raise, because raising would redeliver a request whose answer cannot change and then dead-letter one the
 * baseline answered; and a failed send MUST raise, because propagation is the only thing that keeps an
 * unanswered request from being deleted in a flow that deliberately has no outbox.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no parameter,
 * return or exception section.</p>
 */
class InquiryMessageListenerTest {

    /**
     * The configured reply queue name.
     */
    private static final String REPLY_QUEUE = "account-test-reply";

    /**
     * The configured error queue name.
     */
    private static final String ERROR_QUEUE = "account-test-error";

    /**
     * The address the substituted client resolves the reply queue to.
     */
    private static final String REPLY_URL = "https://sqs.test.invalid/queue/account-test-reply";

    /**
     * The address the substituted client resolves the error queue to.
     */
    private static final String ERROR_URL = "https://sqs.test.invalid/queue/account-test-error";

    /**
     * A fixed instant so the expiry comparisons are not clock-dependent.
     */
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    /**
     * The account the control request resolves to.
     */
    private static final long ACCOUNT_ID = 12_345_678_901L;

    /**
     * The substituted account repository.
     */
    private AccountRepository accounts;

    /**
     * The substituted queue client.
     */
    private SqsClient sqs;

    /**
     * The consumer under test.
     */
    private InquiryMessageListener listener;

    /**
     * Builds the consumer over substituted collaborators.
     */
    @BeforeEach
    void setUp() {
        this.accounts = mock(AccountRepository.class);
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

        this.listener = new InquiryMessageListener(this.accounts, new AccountInquiryReplyMapper(),
                this.sqs, REPLY_QUEUE, ERROR_QUEUE, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * Builds an account row.
     *
     * @return the row, never {@code null}
     */
    private static Account account() {
        return new Account(ACCOUNT_ID, "Y",
                new BigDecimal("1234.56"), new BigDecimal("5000.00"), new BigDecimal("500.00"),
                LocalDate.of(2020, 1, 15), LocalDate.of(2027, 12, 31), LocalDate.of(2024, 6, 1),
                new BigDecimal("111.11"), new BigDecimal("222.22"), "12345", "DEFAULT   ");
    }

    /**
     * Builds a framed request payload.
     *
     * @param function the four-character function code
     * @param key the eleven-character key
     * @return the framed payload, never {@code null}
     */
    private static String request(String function, String key) {
        return InquiryRequestCodec.frame(function + key);
    }

    /**
     * Builds a message.
     *
     * @param payload the body
     * @param headers the attributes
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
     * Verifies a valid inquiry is answered with the labelled account block on the reply queue.
     */
    @Test
    @DisplayName("a valid inquiry is answered with the labelled account block")
    void aValidInquiryIsAnswered() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));

        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of()));

        SendMessageRequest sent = captureSend();
        assertThat(sent.queueUrl()).isEqualTo(REPLY_URL);
        assertThat(sent.messageBody())
                .hasSize(InquiryRequestCodec.MESSAGE_LENGTH)
                .startsWith("ACCOUNT ID : 12345678901ACCOUNT STATUS : Y")
                .contains("GROUP ID : DEFAULT");
    }

    /**
     * Verifies an inquiry for an absent account is ANSWERED rather than raised.
     *
     * <p>Assumptions: the absence of an exception is the assertion. The reference program's not-found branch
     * replies and consumes the message, so raising would redeliver a request whose answer cannot change four
     * more times and then dead-letter one the baseline answered.</p>
     */
    @Test
    @DisplayName("an absent account is answered with the not-found sentence, not raised")
    void anAbsentAccountIsAnsweredNotRaised() {
        when(this.accounts.findById(anyLong())).thenReturn(Optional.empty());

        this.listener.onRequest(message(request("INQA", "00000000999"), Map.of()));

        assertThat(captureSend().messageBody())
                .startsWith("INVALID REQUEST PARAMETERS ACCT ID : 00000000999")
                .doesNotContain("FUNCTION");
    }

    /**
     * Verifies a wrong function code is answered with the sentence that names both the key and the function.
     *
     * <p>Assumptions: the two invalid-parameters sentences are distinguished. This one names the function and
     * the not-found one does not, and the difference is what tells a requester whether its request was
     * ineligible or its account simply absent.</p>
     */
    @Test
    @DisplayName("a wrong function code is answered with the sentence naming the function")
    void aWrongFunctionCodeIsAnswered() {
        this.listener.onRequest(message(request("BADF", "12345678901"), Map.of()));

        assertThat(captureSend().messageBody())
                .startsWith("INVALID REQUEST PARAMETERS ACCT ID : 12345678901FUNCTION : BADF");
        verify(this.accounts, never()).findById(anyLong());
    }

    /**
     * Verifies a zero key is refused by the guard without a lookup.
     *
     * <p>Assumptions: the all-zero key is the case the reference guard {@code WS-KEY > ZEROES} exists for, so
     * it must be refused even though it is entirely numeric -- and the repository must not be consulted, or the
     * service would look up account zero.</p>
     */
    @Test
    @DisplayName("a zero key is refused by the guard without a lookup")
    void aZeroKeyIsRefusedWithoutALookup() {
        this.listener.onRequest(message(request("INQA", "00000000000"), Map.of()));

        assertThat(captureSend().messageBody()).startsWith("INVALID REQUEST PARAMETERS");
        verify(this.accounts, never()).findById(anyLong());
    }

    /**
     * Verifies a non-numeric key is refused by the guard rather than dead-lettered.
     *
     * <p>Assumptions: this is the case that decides whether a malformed key is a reply or a dead letter. The
     * reference guard fails for it and its {@code ELSE} replies, so the target must reply too -- raising would
     * leave a requester the baseline answered waiting forever.</p>
     */
    @Test
    @DisplayName("a non-numeric key is answered rather than dead-lettered")
    void aNonNumericKeyIsAnswered() {
        this.listener.onRequest(message(request("INQA", "1234567890X"), Map.of()));

        assertThat(captureSend().messageBody())
                .startsWith("INVALID REQUEST PARAMETERS ACCT ID : 1234567890XFUNCTION : INQA");
        verify(this.accounts, never()).findById(anyLong());
    }

    /**
     * Verifies an expired request is dropped without a reply and without a lookup.
     *
     * <p>Assumptions: dropped rather than answered, because answering would publish an account's financial
     * position to a reply queue for a requester that has already stopped waiting.</p>
     */
    @Test
    @DisplayName("an expired request is dropped without a reply or a lookup")
    void anExpiredRequestIsDropped() {
        this.listener.onRequest(message(request("INQA", "12345678901"),
                Map.of(MessageExpiry.HEADER_EXPIRES_AT, NOW.minusSeconds(1).toString())));

        verify(this.sqs, never()).sendMessage(any(SendMessageRequest.class));
        verify(this.accounts, never()).findById(anyLong());
    }

    /**
     * Verifies an unexpired and a malformed expiry both still produce a reply.
     */
    @Test
    @DisplayName("an unexpired or malformed expiry still produces a reply")
    void anUnexpiredOrMalformedExpiryStillReplies() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));

        this.listener.onRequest(message(request("INQA", "12345678901"),
                Map.of(MessageExpiry.HEADER_EXPIRES_AT, NOW.plusSeconds(30).toString())));
        assertThat(captureSend().messageBody()).startsWith("ACCOUNT ID : ");

        setUp();
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        this.listener.onRequest(message(request("INQA", "12345678901"),
                Map.of(MessageExpiry.HEADER_EXPIRES_AT, "sometime")));
        assertThat(captureSend().messageBody()).startsWith("ACCOUNT ID : ");
    }

    /**
     * Verifies the requester's correlation identifier is echoed verbatim.
     */
    @Test
    @DisplayName("the correlation identifier is echoed verbatim")
    void theCorrelationIdentifierIsEchoedVerbatim() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        String awkward = "corr-\"id\",with:punctuation";

        this.listener.onRequest(message(request("INQA", "12345678901"),
                Map.of("correlationId", awkward)));

        assertThat(captureSend().messageAttributes().get("correlationId").stringValue())
                .isEqualTo(awkward);
    }

    /**
     * Verifies the reply declares its media type and omits an absent correlation attribute.
     */
    @Test
    @DisplayName("the reply declares its media type and omits an absent correlation attribute")
    void theReplyDeclaresItsMediaType() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));

        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of()));

        assertThat(captureSend().messageAttributes())
                .containsOnlyKeys("contentType")
                .extractingByKey("contentType")
                .satisfies(value -> assertThat(value.stringValue()).isEqualTo("text/plain"));
    }

    /**
     * Verifies the reply never goes to a destination the message named.
     *
     * <p>Assumptions: this is asserted because honouring a message's own reply-to would be both unfaithful --
     * the reference program saves it and then does not use it -- and a queue-injection vector, since a request
     * could then direct an account's balance to a queue of the sender's choosing.</p>
     */
    @Test
    @DisplayName("the reply ignores any destination the message names")
    void theReplyIgnoresAMessageSuppliedDestination() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));

        this.listener.onRequest(message(request("INQA", "12345678901"),
                Map.of("replyToQueueUrl", "https://sqs.attacker.invalid/queue/harvest",
                        "ReplyToQueue", "attacker-queue")));

        assertThat(captureSend().queueUrl()).isEqualTo(REPLY_URL);
    }

    /**
     * Verifies a database failure propagates so the request is not acknowledged unanswered.
     *
     * <p>Assumptions: an infrastructure fault is NOT a business outcome. It propagates, so the request becomes
     * visible again and a retry may succeed -- which is the opposite of the not-found case above, where a retry
     * could never change the answer.</p>
     */
    @Test
    @DisplayName("a database failure propagates rather than becoming a reply")
    void aDatabaseFailurePropagates() {
        when(this.accounts.findById(anyLong()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("the read timed out"));
        Message<String> request = message(request("INQA", "12345678901"), Map.of());

        assertThatThrownBy(() -> this.listener.onRequest(request))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        verify(this.sqs, never()).sendMessage(any(SendMessageRequest.class));
    }

    /**
     * Verifies a failed send propagates, which is this flow's whole delivery guarantee.
     */
    @Test
    @DisplayName("a failed send propagates so the request is not acknowledged")
    void aFailedSendPropagates() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SdkClientException.create("the queue is unreachable"));
        Message<String> request = message(request("INQA", "12345678901"), Map.of());

        assertThatThrownBy(() -> this.listener.onRequest(request))
                .isInstanceOf(SdkClientException.class);
    }

    /**
     * Verifies the queue address is resolved once and then reused.
     */
    @Test
    @DisplayName("the queue address is resolved once and reused")
    void theQueueAddressIsResolvedOnce() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));

        for (int attempt = 0; attempt < 3; attempt++) {
            this.listener.onRequest(message(request("INQA", "12345678901"), Map.of()));
        }

        verify(this.sqs, times(1)).getQueueUrl(any(GetQueueUrlRequest.class));
        verify(this.sqs, times(3)).sendMessage(any(SendMessageRequest.class));
    }

    /**
     * Verifies a diagnostic goes to the error queue and never to the reply queue.
     */
    @Test
    @DisplayName("a diagnostic goes to the error queue")
    void aDiagnosticGoesToTheErrorQueue() {
        this.listener.publishError("ERROR WHILE READING ACCTFILE");

        SendMessageRequest sent = captureSend();
        assertThat(sent.queueUrl()).isEqualTo(ERROR_URL);
        assertThat(sent.messageBody())
                .hasSize(InquiryRequestCodec.MESSAGE_LENGTH)
                .startsWith("ERROR WHILE READING ACCTFILE");
    }

    /**
     * Verifies a blank configured queue name is refused at construction, naming the property.
     */
    @Test
    @DisplayName("a blank configured queue name is refused at construction")
    void aBlankQueueNameIsRefused() {
        AccountInquiryReplyMapper mapper = new AccountInquiryReplyMapper();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThatThrownBy(() -> new InquiryMessageListener(this.accounts, mapper, this.sqs,
                " ", ERROR_QUEUE, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carddemo.account.inquiry.reply-queue");
        assertThatThrownBy(() -> new InquiryMessageListener(this.accounts, mapper, this.sqs,
                REPLY_QUEUE, "", clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carddemo.account.inquiry.error-queue");
    }
}
