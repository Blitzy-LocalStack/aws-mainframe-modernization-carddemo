package com.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.account.domain.Account;
import com.carddemo.account.mapper.AccountInquiryReplyMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.InquiryReplyLedger;
import com.carddemo.common.codec.InquiryRequestCodec;
import com.carddemo.common.messaging.MessageExpiry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
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
     * The substituted claim ledger, which decides whether a delivery is the first for its request.
     */
    private InquiryReplyLedger ledger;

    /** The consumer's own log events, captured so a removed identifier can be asserted absent. */
    private ListAppender<ILoggingEvent> captured;

    /** The consumer's logger, held so the appender attached in setup can be detached again. */
    private ch.qos.logback.classic.Logger serviceLogger;

    /** The level the consumer's logger carried before setup lowered it. */
    private Level previousLevel;

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

        // Assumptions: the transaction manager is substituted, so the read template runs its callback and
        //   commits nothing. What the cases below assert is which store is touched and in what order, not
        //   that a database committed, and a read-only lookup has nothing to commit in any case.
        // WHY : Assumptions: the ledger's claim answers TRUE by default, which is the first-delivery
        //   outcome every pre-existing case here is about. A mock answers false unstubbed, and false is
        //   the redelivery outcome -- so leaving it unstubbed would silently route every one of those
        //   cases down the duplicate-suppression path and assert nothing they were written for.
        this.ledger = mock(InquiryReplyLedger.class);
        when(this.ledger.claim(anyString(), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class))).thenReturn(true);
        when(this.ledger.markSent(anyString(), any(LocalDateTime.class))).thenReturn(true);

        // Assumptions: the transaction manager is substituted, so the read template runs its callback and
        //   commits nothing. What the cases below assert is which store is touched and in what order, not
        //   that a database committed, and a read-only lookup has nothing to commit in any case.
        this.listener = new InquiryMessageListener(this.accounts, new AccountInquiryReplyMapper(),
                this.sqs, REPLY_QUEUE, ERROR_QUEUE, this.ledger, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(PlatformTransactionManager.class));

        this.serviceLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(InquiryMessageListener.class);
        this.previousLevel = this.serviceLogger.getLevel();
        this.serviceLogger.setLevel(Level.INFO);
        this.captured = new ListAppender<>();
        this.captured.start();
        this.serviceLogger.addAppender(this.captured);
    }

    /**
     * Detaches the captured appender and restores the logger's level.
     *
     * <p>Assumptions: the level is restored rather than left lowered, because the logger is a process-wide
     * singleton and a case that lowered it would change what every later class in the same fork emits.</p>
     */
    @AfterEach
    void restoreLogger() {
        this.serviceLogger.detachAppender(this.captured);
        this.captured.stop();
        this.serviceLogger.setLevel(this.previousLevel);
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

    // WHY : Assumptions: the function code is driven with a value carrying a line terminator and a complete
    //       forged event prefix, because that is the attack rather than a stand-in for it. The REPLY must
    //       still carry the requester's own bytes -- the reference's invalid-parameters sentence names the
    //       function it refused -- while the journal must not, and only a case that asserts both halves
    //       establishes that the two destinations were separated rather than both sanitised or both raw.
    /**
     * Verifies a forged function code reaches the reply verbatim and the journal only as a classification.
     */
    @Test
    @DisplayName("a forged function code is echoed to the reply but classified for the journal")
    void aForgedFunctionCodeIsClassifiedForTheJournal() {
        String forged = "\nev";

        this.listener.onRequest(message(request(forged, "12345678901"), Map.of()));

        assertThat(captureSend().messageBody())
                .as("the reference sentence names the function it refused, so the reply carries the bytes")
                .contains("FUNCTION : " + forged);
        assertThat(InquiryRequestCodec.decode(request(forged, "12345678901")).functionLabel())
                .as("what a journal line receives is a closed token, never the field")
                .isEqualTo(InquiryRequestCodec.FUNCTION_LABEL_UNRECOGNISED);
        verify(this.accounts, never()).findById(anyLong());
    }

    // WHY : Assumptions: the CLASSIFICATION is asserted to be closed over every shape the field can take,
    //       including the two the guard treats alike -- a blank field and an unrecognised one. Only the
    //       membership assertion establishes that a caller always has something safe to journal; asserting
    //       the absence of forged text alone would pass for a method returning nothing at all.
    /**
     * Verifies the journalled classification is closed over every function the wire can carry.
     */
    @Test
    @DisplayName("the journalled function classification is closed")
    void theJournalledClassificationIsClosed() {
        for (String function : java.util.List.of("INQA", "inqa", "    ", "BADF", "\nev", "\u0000A\u0000")) {
            assertThat(InquiryRequestCodec.decode(request(function, "12345678901")).functionLabel())
                    .isIn(InquiryRequestCodec.FUNCTION_ACCOUNT_INQUIRY,
                            InquiryRequestCodec.FUNCTION_LABEL_BLANK,
                            InquiryRequestCodec.FUNCTION_LABEL_UNRECOGNISED);
        }
    }

    // WHY : Assumptions: the ABSENCE of the account identifier is asserted on the record's own rendering,
    //       which is the value a journal line would carry if one interpolated the request. Asserting the two
    //       log statements directly would need a log appender; asserting the rendering they would have used
    //       establishes the same property at the source, and it is the property the observability contract
    //       states -- omitted, not abbreviated.
    /**
     * Verifies a decoded request's own rendering discloses neither the account identifier nor the function.
     */
    @Test
    @DisplayName("a decoded request discloses neither the account identifier nor the raw function")
    void aDecodedRequestDisclosesNoIdentifier() {
        String rendered = InquiryRequestCodec.decode(request("INQA", "12345678901")).toString();

        assertThat(rendered)
                .doesNotContain("12345678901")
                .doesNotContain("2345")
                .contains(InquiryRequestCodec.FUNCTION_ACCOUNT_INQUIRY)
                .contains("keyUsable=true");
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
                .containsOnlyKeys(InquiryMessageListener.ATTRIBUTE_CONTENT_TYPE)
                .extractingByKey(InquiryMessageListener.ATTRIBUTE_CONTENT_TYPE)
                // WHY : Refactoring Rationale: the expected value is text/plain where it was text/csv,
                //   and the literal is written out here rather than read from the class under test so
                //   that a change to the published media type fails this case instead of moving with it.
                //   Nothing this listener publishes is comma-separated: the reply is the labelled
                //   fixed-width block located by offset and framed to the declared message length, and a
                //   consumer trusting a text/csv label would split on commas and find one field, or
                //   would split a free-text value containing a comma into two. text/csv is reserved for
                //   the authorization request and reply, which genuinely are comma-separated.
                .satisfies(value -> assertThat(value.stringValue()).isEqualTo("text/plain"));
    }

    /**
     * Verifies the request's message identifier is echoed onto the reply as an attribute.
     *
     * <p>Assumptions: the reference program restores the saved message identifier onto the reply descriptor
     * at physical line 469. A message identifier cannot be set on a send in the target, so the captured value
     * has to travel as an attribute; asserting it here is what stops that half of the descriptor mapping from
     * being dropped silently.</p>
     */
    @Test
    @DisplayName("the request's message identifier is echoed onto the reply")
    void theMessageIdentifierIsEchoed() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));

        this.listener.onRequest(message(request("INQA", "12345678901"),
                Map.of(InquiryMessageListener.ATTRIBUTE_MESSAGE_ID, "inbound-message-1")));

        assertThat(captureSend().messageAttributes())
                .extractingByKey(InquiryMessageListener.ATTRIBUTE_MESSAGE_ID)
                .satisfies(value -> assertThat(value.stringValue()).isEqualTo("inbound-message-1"));
    }

    /**
     * Verifies a requested reply destination that matches this context's own destination is honoured.
     *
     * <p>Assumptions: the reference program captures the request's reply-to queue at physical line 366 and
     * saves it at physical line 371, so the value IS part of the request contract and is read rather than
     * discarded. What the target adds is that it is honoured only after an exact match against the
     * environment-owned destination, which is the contract recorded in
     * {@code docs/architecture/messaging-contracts.md} and the only destination the task role is granted a
     * send action on.</p>
     */
    @Test
    @DisplayName("a requested reply destination that matches the permitted one is honoured")
    void aMatchingRequestedDestinationIsHonoured() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));

        this.listener.onRequest(message(request("INQA", "12345678901"),
                Map.of(InquiryMessageListener.ATTRIBUTE_REPLY_TO_QUEUE_URL, REPLY_URL)));

        assertThat(captureSend().queueUrl()).isEqualTo(REPLY_URL);
    }

    /**
     * Verifies the reply never goes to a destination outside the permitted set.
     *
     * <p>Assumptions: the requested route is routing data rather than authority, so a value that does not
     * match the environment-owned destination exactly is substituted rather than honoured. Honouring an
     * arbitrary address would make this consumer a confused deputy, able to direct an account's financial
     * position to a queue of the sender's choosing; the reference program reaches its own destination through
     * a handle opened once from the configured name, so substituting rather than failing is also what keeps a
     * request the baseline answered from being dead-lettered.</p>
     */
    @Test
    @DisplayName("the reply ignores a destination outside the permitted set")
    void theReplyIgnoresAMessageSuppliedDestination() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));

        this.listener.onRequest(message(request("INQA", "12345678901"),
                Map.of("replyToQueueUrl", "https://sqs.attacker.invalid/queue/harvest",
                        "ReplyToQueue", "attacker-queue")));

        assertThat(captureSend().queueUrl()).isEqualTo(REPLY_URL);
    }

    /**
     * Verifies a database failure is reported to the error sink and then propagates, and is never a reply.
     *
     * <p>Assumptions: an infrastructure fault is NOT a business outcome. It propagates, so the request becomes
     * visible again and a retry may succeed -- which is the opposite of the not-found case above, where a retry
     * could never change the answer. The report precedes the propagation because the reference program's
     * {@code WHEN OTHER} branch performs its error paragraph at physical line 444 and only then terminates at
     * physical line 445.</p>
     *
     * <p>Assumptions: the report's body is asserted to carry the reference program's own verbatim return
     * message, truncated into its 25-character field exactly as a move into that picture truncates it, and to
     * carry NO exception message text -- only the failure's type chain. An exception message is the one part
     * of a failure into which a request value can be interpolated, and this body is published onto a queue.</p>
     */
    @Test
    @DisplayName("a database failure is reported to the error sink and then propagates")
    void aDatabaseFailurePropagates() {
        when(this.accounts.findById(anyLong()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("the read timed out"));
        Message<String> request = message(request("INQA", "12345678901"), Map.of());

        assertThatThrownBy(() -> this.listener.onRequest(request))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);

        SendMessageRequest reported = captureSend();
        assertThat(reported.queueUrl()).isEqualTo(ERROR_URL);
        assertThat(reported.messageBody())
                .hasSize(InquiryRequestCodec.MESSAGE_LENGTH)
                .startsWith("4000-PROCESS-REQUEST-REPL")
                .contains("ERROR WHILE READING ACCTF")
                .contains("QueryTimeoutException")
                .doesNotContain("the read timed out");
    }

    /**
     * Verifies a failure in the error report cannot replace the failure it was reporting.
     *
     * <p>Assumptions: a single unreachable queue fails the reply AND the report, and a client is free to
     * answer both with one exception instance. Attaching a throwable to itself is rejected by the platform, so
     * without an identity guard that shared instance would surface as an unrelated argument failure and the
     * outage would be lost. This asserts the original failure is what reaches the caller.</p>
     */
    @Test
    @DisplayName("a shared failure instance from both sends does not replace the original failure")
    void aSharedFailureInstanceDoesNotReplaceTheOriginal() {
        SdkClientException shared = SdkClientException.create("the queue is unreachable");
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(this.sqs.sendMessage(any(SendMessageRequest.class))).thenThrow(shared);
        Message<String> request = message(request("INQA", "12345678901"), Map.of());

        assertThatThrownBy(() -> this.listener.onRequest(request)).isSameAs(shared);
        assertThat(shared.getSuppressed()).isEmpty();
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

        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

        assertThatThrownBy(() -> new InquiryMessageListener(this.accounts, mapper, this.sqs,
                " ", ERROR_QUEUE, this.ledger, clock, transactions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carddemo.account.inquiry.reply-queue");
        assertThatThrownBy(() -> new InquiryMessageListener(this.accounts, mapper, this.sqs,
                REPLY_QUEUE, "", this.ledger, clock, transactions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carddemo.account.inquiry.error-queue");
    }

    /**
     * No log line this consumer writes names the account the inquiry is about.
     *
     * <p>Purpose: this consumer's two outcome lines carried {@code accountId=} and the value behind it,
     * so ORDINARY successful traffic wrote an eleven-digit account identifier into log storage once per
     * message -- {@code ACCT-ID PIC 9(11)} at line 5 of {@code app/cpy/CVACT01Y.cpy}. That exposure
     * arrived through a hand-written format argument rather than through a metadata field, so no character
     * rule and no shape rule anywhere in the kernel could see it; only an assertion over what was actually
     * emitted can.</p>
     *
     * <p>Assumptions: BOTH outcomes are exercised in one case, because they were one defect and a fix
     * applied to one of them would leave the other. The answered path and the not-found path are the two
     * the reference program reaches with a usable key, so between them they cover every line that had the
     * subject available to log.</p>
     *
     * <p>Assumptions: the assertion is that the DIGITS do not appear, rather than that the token
     * {@code accountId=} does not. A rename to {@code account=} or {@code key=} would satisfy a
     * token-based assertion while disclosing exactly the same value, so the check is on the value.</p>
     *
     * <p>Assumptions: the events are still asserted PRESENT. Removing the identifier must not turn into
     * removing the record -- an operator needs to know an inquiry was answered and which of the two ways
     * it went, and the correlation identity already in the diagnostic context is what attributes the line
     * to one request.</p>
     */
    @Test
    @DisplayName("no log line names the account, and both outcome events are still recorded")
    void noLogLineNamesTheAccount() {
        Logger listenerLogger = (Logger) LoggerFactory.getLogger(InquiryMessageListener.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        listenerLogger.addAppender(captured);
        // WHY : Assumptions: the previous level may legitimately be null, which means "inherit" rather
        //       than "unset", so null is what is restored. Substituting a concrete default would pin a
        //       logger that had been inheriting.
        Level previousLevel = listenerLogger.getLevel();
        listenerLogger.setLevel(Level.INFO);
        try {
            when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
            this.listener.onRequest(message(request("INQA", "12345678901"), Map.of()));

            when(this.accounts.findById(999L)).thenReturn(Optional.empty());
            this.listener.onRequest(message(request("INQA", "00000000999"), Map.of()));

            assertThat(captured.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .as("the subject of the inquiry must not reach a log line")
                    .noneMatch(recorded -> recorded.contains("12345678901"))
                    .noneMatch(recorded -> recorded.contains(String.valueOf(ACCOUNT_ID)))
                    .noneMatch(recorded -> recorded.contains("00000000999"))
                    .noneMatch(recorded -> recorded.contains("999"));

            assertThat(captured.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .as("and both outcomes are still recorded, so the record is not simply gone")
                    .contains("event=account.inquiry.answered", "event=account.inquiry.not-found");
        } finally {
            listenerLogger.detachAppender(captured);
            captured.stop();
            listenerLogger.setLevel(previousLevel);
        }
    }
}
