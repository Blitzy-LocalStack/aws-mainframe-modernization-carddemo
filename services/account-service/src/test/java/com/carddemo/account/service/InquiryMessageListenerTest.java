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
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import com.carddemo.common.messaging.MessagingCorrelationId;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
 * <h2>The absence of a transactional outbox, which is what this class exists to pin</h2>
 *
 * <p>Assumptions: this exchange needs NO transactional outbox, and the ground for that is in the reference
 * program rather than in a preference, which is why it is asserted here instead of merely stated. Every
 * message-queue option in {@code app/app-vsam-mq/cbl/COACCT01.cbl} is a syncpoint option: physical line 347
 * computes the get options as {@code MQGMO-SYNCPOINT} and physical line 475 computes the put options as
 * {@code MQPMO-SYNCPOINT}, while a search of that file for the negated forms
 * {@code NO-SYNCPOINT} and {@code NO_SYNCPOINT} returns ZERO hits. The get, the datastore read and the put
 * are therefore ONE unit of work, so no window exists in which the read is committed and the reply is lost,
 * and an outbox would have nothing to close.</p>
 *
 * <p>Assumptions: the pending-authorization consumer is the exact OPPOSITE discipline, and the contrast is
 * recorded here because getting the two backwards is the likeliest cross-service error in this migration.
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} computes its get options as
 * {@code MQGMO-NO-SYNCPOINT} at physical line 389 and its put options as {@code MQPMO-NO-SYNCPOINT} at
 * physical line 753, so it publishes its reply OUTSIDE the commit and does have a lost-reply window. That is
 * why {@code authorization-service} ships {@code OutboxPublisher} and {@code OutboxRepository} and this
 * service ships neither. Adding an outbox here would add durable machinery to close a window that does not
 * exist; removing it there would drop machinery that flow cannot do without.</p>
 *
 * <p>Refactoring Rationale: the syncpoint bracket becomes one transactional boundary plus delete-on-success
 * acknowledgement rather than being emulated, which is the {@code EXEC CICS} verb mapping the specification
 * fixes for {@code SYNCPOINT} in its rule T5. The baseline opens the unit of work with the
 * {@code EXEC CICS SYNCPOINT} at physical line 327, reads the account master inside it at physical lines 396
 * to 400, and puts the reply still inside it at physical lines 475 to 477. The target keeps the same property
 * by different means: the handler returns normally only after the reply has been sent, so the framework
 * deletes the request only then, and a failure propagates instead so the request becomes visible again. What
 * this class asserts is that the property survived the change of mechanism -- not that a queue or a database
 * behaved, which belongs to the container-backed classes of the sibling packages.</p>
 *
 * <p>Assumptions: no executable parity oracle exists for this program, so every expectation here is authored
 * from the reference paragraphs directly and none is a golden-master comparison. {@code tests/README.md}
 * establishes both halves of that: its L83 through L85 record that the online programs cannot be run end to
 * end without a CICS runtime, which the runner does not have, and the business rules its section 13 asserts
 * verbatim from L553 onward name the posting, interest and category-balance programs of other contexts while
 * naming neither this program nor the account master at all. These Java cases are strictly additive to that
 * suite, which is reference material and is neither modified nor re-pinned from here.</p>
 *
 * <p>Trade-offs: this class keeps its {@code Test} suffix, so it runs under Surefire in the {@code test}
 * phase, and it therefore substitutes every collaborator that would otherwise need infrastructure rather than
 * starting any. The suffix is part of this package's contract, recorded in its {@code package-info}, and the
 * report path {@code target/surefire-reports} that {@code .github/workflows/services-ci.yml} collects from
 * depends on the phase split staying exactly as configured. Alternatives Considered: renaming to end in
 * {@code IT} so a real queue emulator and a real database could back these cases under Failsafe. Rejected on
 * two independent counts -- the rename would move this class out of the phase its package declares, and the
 * service-connection support such wiring uses ships in {@code spring-boot-testcontainers}, which this
 * module's POM does not declare, so it is not on this classpath to be used. The behaviour that genuinely
 * needs a broker, the provisioned redelivery and dead-letter routing, is asserted where a broker exists.</p>
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
     * A broker-assigned delivery identifier, in the shape the queue service assigns.
     */
    private static final String BROKER_MESSAGE_ID = "9f0b1c2d-3e4f-5a6b-7c8d-9e0f1a2b3c4d";

    /**
     * A second broker-assigned delivery identifier, for the two-request cases.
     */
    private static final String SECOND_BROKER_MESSAGE_ID = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d";

    /**
     * The declared width of the durable ledger's key column.
     *
     * <p>Assumptions: this restates {@code request_key VARCHAR(128)} from
     * {@code V2__account_inquiry_reply_ledger.sql} as a literal, because the schema is not reachable from a
     * unit test. It is the ceiling the intake bound is asserted against below.</p>
     */
    private static final int LEDGER_KEY_COLUMN_WIDTH = 128;

    /**
     * The outbox types this exchange must not acquire, named individually rather than counted.
     *
     * <p>Assumptions: the first two names are the ones {@code authorization-service} really does ship, in the
     * form {@code com.carddemo.authorization.service.OutboxPublisher} and
     * {@code com.carddemo.authorization.repository.OutboxRepository}, and the remaining names are the
     * account-context analogues a future change would most plausibly introduce. They are listed as names
     * because the assertion is that the TYPE does not exist on this module's classpath, and a type that does
     * not exist cannot be referenced in source without failing to compile.</p>
     */
    private static final List<String> FORBIDDEN_OUTBOX_TYPES = List.of(
            "com.carddemo.authorization.service.OutboxPublisher",
            "com.carddemo.authorization.repository.OutboxRepository",
            "com.carddemo.account.service.OutboxPublisher",
            "com.carddemo.account.repository.OutboxRepository",
            "com.carddemo.account.repository.InquiryReplyOutbox",
            "com.carddemo.account.domain.OutboxMessage");

    /**
     * The label the reference layout opens its reply block with, carried across character for character.
     *
     * <p>Assumptions: this is the literal declared at {@code app/app-vsam-mq/cbl/COACCT01.cbl} physical lines
     * 132 and 133, and it is reproduced verbatim under the specification's rule T8 because a consumer reading
     * the reply locates the identifier by the offset this label's own width establishes.</p>
     */
    private static final String LABEL_ACCOUNT_ID = "ACCOUNT ID : ";

    /**
     * The label preceding the active-status flag, from physical lines 135 and 136 of the reference.
     */
    private static final String LABEL_STATUS = "ACCOUNT STATUS : ";

    /**
     * The label preceding the current balance, from physical lines 138 and 139 of the reference.
     */
    private static final String LABEL_BALANCE = "BALANCE : ";

    /**
     * The declared width of the account identifier in the reply block.
     *
     * <p>Assumptions: {@code WS-ACCT-ID PIC 9(11)} at physical line 134 of the reference, matching
     * {@code ACCT-ID PIC 9(11)} at line 5 of {@code app/cpy/CVACT01Y.cpy}, so the two agree on eleven.</p>
     */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * The poll wait the reference program asks its get to wait, expressed in seconds.
     *
     * <p>Assumptions: physical line 337 of the reference moves 5000 into {@code MQGMO-WAITINTERVAL}, which
     * that interface expresses in milliseconds, so the target's equivalent is five seconds.</p>
     */
    private static final int BASELINE_POLL_WAIT_SECONDS = 5;

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

        // WHY : Assumptions: the ledger's claim answers TRUE by default, which is the first-delivery
        //   outcome every pre-existing case here is about. A mock answers false unstubbed, and false is
        //   the redelivery outcome -- so leaving it unstubbed would silently route every one of those
        //   cases down the duplicate-suppression path and assert nothing they were written for.
        // WHY : Refactoring Rationale: the transaction-manager rationale that used to open this block has
        //   been removed from here rather than reworded. It described the manager passed to the constructor
        //   below, so sitting above the ledger mock it documented a statement it was not about, and the
        //   identical paragraph already sits at the construction it explains. Rule 1 requires a comment to
        //   be adjacent to the code it explains, and a duplicated paragraph also decays independently.
        this.ledger = mock(InquiryReplyLedger.class);
        when(this.ledger.claim(anyString(), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class))).thenReturn(true);
        when(this.ledger.markSent(anyString(), any(LocalDateTime.class))).thenReturn(true);

        // WHY : Assumptions: the transaction manager is substituted, so the read template runs its callback
        //   and commits nothing. What the cases below assert is which store is touched and in what order,
        //   not that a database committed, and a read-only lookup has nothing to commit in any case.
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

    /**
     * Verifies the durable claim is keyed on the identity the BROKER assigned, not on a producer attribute.
     *
     * <p>Purpose: this is the property finding 26 of the code review asks for. The broker identifier is the
     * only value on a delivery that is both stable across redeliveries and unique per accepted send, so it is
     * the only sound duplicate-suppression key. The case supplies a producer attribute AND a correlation
     * identifier alongside it, both different from the broker value, so a fallback silently taking precedence
     * again would fail here rather than in production.</p>
     *
     * <p>Assumptions: the header name is read from the class under test, which derives it from the framework's
     * own {@code SqsHeaders.MessageSystemAttributes.MESSAGE_ID}. Writing the literal here instead would let a
     * framework rename pass this case while the deployed consumer read an absent header.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the claim is keyed on the broker-assigned message identifier")
    void theClaimIsKeyedOnTheBrokerMessageIdentifier() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));

        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of(
                InquiryMessageListener.HEADER_BROKER_MESSAGE_ID, BROKER_MESSAGE_ID,
                InquiryMessageListener.ATTRIBUTE_MESSAGE_ID, "producer-supplied-1",
                InquiryMessageListener.ATTRIBUTE_CORRELATION_ID, "reused-correlation")));

        verify(this.ledger).claim(eq(BROKER_MESSAGE_ID), anyString(), anyString(),
                eq("reused-correlation"), eq("producer-supplied-1"), any(LocalDateTime.class));
        verify(this.ledger).markSent(eq(BROKER_MESSAGE_ID), any(LocalDateTime.class));
    }

    /**
     * Verifies the unconverted broker header is used when the system-attribute header is absent.
     *
     * <p>Assumptions: both headers are broker-assigned, and which of the two carries the value depends on
     * whether the framework is configured to republish the identifier as a UUID. Reading only one would make
     * the durable key depend on a framework setting, so the second source is asserted rather than assumed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the unconverted broker header is used when the system attribute is absent")
    void theRawBrokerHeaderIsUsedWhenTheSystemAttributeIsAbsent() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));

        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of(
                InquiryMessageListener.HEADER_BROKER_RAW_MESSAGE_ID, BROKER_MESSAGE_ID,
                InquiryMessageListener.ATTRIBUTE_MESSAGE_ID, "producer-supplied-1")));

        verify(this.ledger).claim(eq(BROKER_MESSAGE_ID), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class));
    }

    /**
     * Verifies two distinct requests sharing one correlation identifier are keyed apart.
     *
     * <p>Purpose: this is the failure the earlier keying produced, asserted directly. A requester is entitled
     * to reuse one correlation identifier across several questions; under the earlier rule the second
     * question's claim collided with the first's row and the requester received the earlier answer. Two
     * deliveries carrying one correlation identifier and two broker identifiers must claim two different
     * keys.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("two requests reusing one correlation identifier claim two different keys")
    void twoRequestsReusingOneCorrelationIdentifierAreKeyedApart() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        String reusedCorrelation = "one-correlation-for-many-questions";

        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of(
                InquiryMessageListener.HEADER_BROKER_MESSAGE_ID, BROKER_MESSAGE_ID,
                InquiryMessageListener.ATTRIBUTE_CORRELATION_ID, reusedCorrelation)));
        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of(
                InquiryMessageListener.HEADER_BROKER_MESSAGE_ID, SECOND_BROKER_MESSAGE_ID,
                InquiryMessageListener.ATTRIBUTE_CORRELATION_ID, reusedCorrelation)));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(this.ledger, times(2)).claim(keys.capture(), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class));
        assertThat(keys.getAllValues())
                .as("a reused correlation identifier must not make a second question a redelivery")
                .containsExactly(BROKER_MESSAGE_ID, SECOND_BROKER_MESSAGE_ID)
                .doesNotHaveDuplicates();
    }

    /**
     * Verifies an over-long producer identity is neither recorded nor echoed, and the request is still answered.
     *
     * <p>Purpose: this is the property finding 44 of the code review asks for. An unbounded producer value used
     * to reach a {@code VARCHAR(128)} column and an outbound message attribute, so the insert or the send
     * raised, the request was redelivered, and the requester ended up with no reply and its request on the
     * dead-letter queue. The bound is applied at intake instead: the value is treated as absent, the business
     * answer still goes out, and a controlled protocol diagnostic goes to the error sink.</p>
     *
     * <p>Assumptions: the length used is one character beyond the shared queue-identity bound rather than an
     * arbitrary large number, so the case pins the boundary the rule states rather than a value comfortably
     * past it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an over-long producer identity is refused at intake and the request is still answered")
    void anOverLongProducerIdentityIsRefusedAtIntake() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        String overLong = "x".repeat(MessagingCorrelationId.MAX_LENGTH + 1);

        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of(
                InquiryMessageListener.HEADER_BROKER_MESSAGE_ID, BROKER_MESSAGE_ID,
                InquiryMessageListener.ATTRIBUTE_CORRELATION_ID, overLong)));

        verify(this.ledger).claim(eq(BROKER_MESSAGE_ID), anyString(), anyString(), eq(null), eq(null),
                any(LocalDateTime.class));

        ArgumentCaptor<SendMessageRequest> sends = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, times(2)).sendMessage(sends.capture());
        assertThat(sends.getAllValues())
                .as("one controlled diagnostic to the error sink and one business reply to the requester")
                .extracting(SendMessageRequest::queueUrl)
                .containsExactly(ERROR_URL, REPLY_URL);
        assertThat(sends.getAllValues())
                .allSatisfy(sent -> assertThat(sent.messageAttributes().values())
                        .as("no message may carry the refused value")
                        .noneMatch(attribute -> overLong.equals(attribute.stringValue())));
        assertThat(sends.getAllValues().get(0).messageBody())
                .as("the diagnostic names the attribute and its length and never its bytes")
                .contains("identity-refused")
                .contains(InquiryMessageListener.ATTRIBUTE_CORRELATION_ID)
                .contains(String.valueOf(MessagingCorrelationId.MAX_LENGTH + 1))
                .doesNotContain(overLong);
        assertThat(this.captured.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(recorded -> recorded.startsWith("event=account.inquiry.identity-refused"))
                .noneMatch(recorded -> recorded.contains(overLong));
    }

    /**
     * Verifies a control-character-bearing producer identity is refused the same way an over-long one is.
     *
     * <p>Assumptions: a value that carries a line terminator is the other half of what the shared rule
     * excludes, and it is the half that matters for anything the value is written into -- a log record, a
     * diagnostic buffer or a message attribute the queue service itself rejects. Both halves are asserted so a
     * future narrowing of the rule to length alone fails here.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a control-character-bearing producer identity is refused at intake")
    void aControlCharacterProducerIdentityIsRefusedAtIntake() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        String withTerminator = "corr\nid";

        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of(
                InquiryMessageListener.HEADER_BROKER_MESSAGE_ID, BROKER_MESSAGE_ID,
                InquiryMessageListener.ATTRIBUTE_MESSAGE_ID, withTerminator)));

        verify(this.ledger).claim(eq(BROKER_MESSAGE_ID), anyString(), anyString(), eq(""), eq(null),
                any(LocalDateTime.class));
        ArgumentCaptor<SendMessageRequest> sends = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, times(2)).sendMessage(sends.capture());
        assertThat(sends.getAllValues().get(1).messageAttributes())
                .as("the reply must not carry an attribute the queue service would reject")
                .doesNotContainKey(InquiryMessageListener.ATTRIBUTE_MESSAGE_ID);
    }

    /**
     * No transactional outbox takes part in the exchange, and the reply is published inside the invocation.
     *
     * <p>Purpose: this is the assertion the class descriptor argues for at length, expressed in three
     * independent ways so that no single change can quietly introduce an outbox. The first is that no outbox
     * TYPE is reachable from this module at all. The second is that no collaborator the consumer is
     * constructed with, and no state it holds, is one. The third is behavioural and the most important: the
     * reply reaches the queue client during the handler's own invocation, and once that invocation returns
     * the client is touched no further -- so there is no deferred publication step for a relay to perform
     * later.</p>
     *
     * <p>Assumptions: the ground is the reference program's own syncpoint discipline, recorded once in the
     * class descriptor and cited here so this case is checkable on its own -- physical line 347 of
     * {@code app/app-vsam-mq/cbl/COACCT01.cbl} computes the get options as {@code MQGMO-SYNCPOINT}, physical
     * line 475 computes the put options as {@code MQPMO-SYNCPOINT}, and that file yields ZERO hits for the
     * negated {@code NO-SYNCPOINT} form. Get, read and put are one unit of work, so no reply can be lost
     * after a commit and an outbox has nothing to guarantee. The contrasting consumer,
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}, uses the negated options at its physical
     * lines 389 and 753 and therefore does need one.</p>
     *
     * <p>Assumptions: the claim ledger this consumer DOES hold is not an outbox, and the distinction is the
     * guarantee rather than the mechanism. An outbox guarantees a reply EXISTS for every committed decision;
     * this exchange commits no decision, because its read is read-only. The ledger guarantees a reply is not
     * sent TWICE. Asserting the absence of the first while the second is present is the whole point of the
     * case, so the ledger is deliberately not treated as a violation.</p>
     *
     * <p>Trade-offs: the type check is by name through reflection rather than by a layering rule, and it
     * therefore cannot catch an outbox introduced under a name this list does not anticipate. Alternatives
     * Considered: expressing it as an architecture rule instead. Rejected because layering in this reactor
     * has exactly one owner, the rule class in {@code common-lib}, and a second declaration here could drift
     * from it; the behavioural half of this case is the part that holds whatever a new type is called.</p>
     *
     * <p>This test takes no parameter, returns no value and declares no exception. The reflective lookup
     * below does raise a checked exception for every forbidden name, which is the outcome being asserted, but
     * it is raised inside the assertion's own callable and never leaves this method, so declaring it here
     * would name an exception this method cannot propagate.</p>
     */
    @Test
    @DisplayName("no transactional outbox participates, and the reply is published inside the invocation")
    void noTransactionalOutboxParticipatesInTheExchange() {
        // WHY : Assumptions: absence is asserted through the loader rather than by referencing the types,
        //   because a type that does not exist cannot be named in source without failing to compile -- so a
        //   direct reference would make this case impossible to write while the property held.
        for (String forbidden : FORBIDDEN_OUTBOX_TYPES) {
            assertThatThrownBy(() -> Class.forName(forbidden))
                    .as("no outbox type may be reachable from this module: %s", forbidden)
                    .isInstanceOf(ClassNotFoundException.class);
        }

        // WHY : Assumptions: the constructor parameters are the complete set of collaborators this consumer
        //   can reach, because it acquires nothing by field injection or static lookup, so scanning them is
        //   scanning every store the exchange can touch.
        for (Class<?> parameter : InquiryMessageListener.class.getDeclaredConstructors()[0]
                .getParameterTypes()) {
            assertThat(parameter.getSimpleName())
                    .as("no collaborator of the consumer may be an outbox")
                    .doesNotContainIgnoringCase("outbox");
        }
        for (Field field : InquiryMessageListener.class.getDeclaredFields()) {
            assertThat(field.getType().getSimpleName())
                    .as("no state the consumer holds may be an outbox: field %s", field.getName())
                    .doesNotContainIgnoringCase("outbox");
        }

        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));

        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of(
                InquiryMessageListener.HEADER_BROKER_MESSAGE_ID, BROKER_MESSAGE_ID)));

        // WHY : Assumptions: the reply having reached the client by the time the handler returns is what
        //   distinguishes direct publication from an outbox, which would instead have committed a row and
        //   returned with nothing sent. Pinning the interaction count closed is what makes the distinction
        //   an assertion rather than an observation: an added relay would raise it.
        SendMessageRequest sent = captureSend();
        assertThat(sent.queueUrl()).isEqualTo(REPLY_URL);
        assertThat(sent.messageBody()).startsWith(LABEL_ACCOUNT_ID);
        verify(this.sqs, times(1)).getQueueUrl(any(GetQueueUrlRequest.class));

        // WHY : Assumptions: the ORDER is the other half of the distinction, and it runs the opposite way
        //   round from an outbox. An outbox records the reply and commits, and a relay sends it afterwards in
        //   a different unit of work. Here the send sits BETWEEN the two ledger writes -- the claim is taken
        //   first so a redelivery can discover it, the send follows, and only then is the claim retired -- so
        //   all three happen inside this one invocation and none is deferred to anything else.
        InOrder sequence = inOrder(this.ledger, this.sqs);
        sequence.verify(this.ledger).claim(eq(BROKER_MESSAGE_ID), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class));
        sequence.verify(this.sqs).sendMessage(any(SendMessageRequest.class));
        sequence.verify(this.ledger).markSent(eq(BROKER_MESSAGE_ID), any(LocalDateTime.class));
        verifyNoMoreInteractions(this.sqs);
    }

    /**
     * A send that fails is never recorded as delivered, and the failure leaves the handler.
     *
     * <p>Purpose: this is the sharpest available discriminator between this flow and an outbox-backed one,
     * and it is asserted separately from the case that merely shows the failure propagating. An outbox-backed
     * handler would commit a durable row describing the reply and then return normally, so the request would
     * be acknowledged and a relay would deliver the reply afterwards. This handler must do the opposite: the
     * claim must NOT be retired, because nothing delivered the reply, and the failure must leave the handler
     * so the request becomes visible again instead of being acknowledged unanswered.</p>
     *
     * <p>Assumptions: retiring the claim is the only durable act that marks a reply as sent, so its absence
     * is the observable form of "nothing believes this reply was delivered". The claim itself is expected to
     * have been taken, because the recorded copy is what a redelivery re-sends rather than recomposing it.</p>
     *
     * <p>Refactoring Rationale: the baseline obtains this property from the syncpoint bracket instead. Its
     * put at physical lines 475 to 477 of {@code app/app-vsam-mq/cbl/COACCT01.cbl} joins the unit of work the
     * {@code EXEC CICS SYNCPOINT} at physical line 327 opened, so a failed put rolls the whole iteration back
     * and the request is never consumed. Propagation plus an unretired claim is the target's way of reaching
     * the same end without a distributed transaction, which the specification records as eliminated.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a failed send is not recorded as delivered and the failure propagates")
    void aFailedSendIsNotRecordedAsDeliveredAndPropagates() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SdkClientException.create("the queue is unreachable"));
        Message<String> request = message(request("INQA", "12345678901"), Map.of(
                InquiryMessageListener.HEADER_BROKER_MESSAGE_ID, BROKER_MESSAGE_ID));

        assertThatThrownBy(() -> this.listener.onRequest(request))
                .isInstanceOf(SdkClientException.class);

        verify(this.ledger).claim(eq(BROKER_MESSAGE_ID), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class));
        verify(this.ledger, never()).markSent(anyString(), any(LocalDateTime.class));
    }

    /**
     * The polling contract carries the reference program's own wait and hard-codes no destination.
     *
     * <p>Purpose: the container, not this class, performs the baseline's driver loop, so the only place the
     * loop's parameters are visible is the annotation that configures it. This case reads that annotation and
     * asserts the four properties carried across from the reference, none of which any behavioural case can
     * observe.</p>
     *
     * <p>Assumptions: the wait is the baseline's own. Physical line 337 of
     * {@code app/app-vsam-mq/cbl/COACCT01.cbl} moves 5000 into {@code MQGMO-WAITINTERVAL}, expressed by that
     * interface in milliseconds, so the target's default of five seconds is the same wait and not a chosen
     * one.</p>
     *
     * <p>Assumptions: no queue location appears anywhere in the configuration, which is the baseline's own
     * position rather than a modern convention. Its queue block at physical line 92 declares four names --
     * the queue manager at 93, the input queue at 94, the reply queue at 95 and the error queue at 96 -- and
     * every one of the four is {@code PIC X(48) VALUE SPACES}, so the program that ran on the mainframe
     * carried no destination in its source either and expected one at run time. The assertion is therefore
     * that the configured name is a property placeholder, not that it equals any particular value.</p>
     *
     * <p>Assumptions: the container-factory attribute is empty, which is the contract
     * {@code config/SqsConfig.java} establishes by declaring no factory bean. That file is read for the
     * contract and deliberately never imported here, because a service test verifying the listener has no
     * business depending on a configuration class; naming a factory that does not exist would fail at context
     * refresh rather than at compilation, which is why the absence is pinned.</p>
     *
     * <p>Assumptions: neither the visibility nor the acknowledgement attribute is overridden. Leaving the
     * first unset is what lets the PROVISIONED queue's own visibility timeout govern redelivery, and leaving
     * the second unset is what keeps acknowledgement on successful processing -- the delete-on-success model
     * that replaces the syncpoint bracket. An acknowledgement mode that acknowledged before processing would
     * delete a request before its answer existed, which is the one outcome this flow's guarantee forbids.</p>
     *
     * <p>Trade-offs: the redelivery and dead-letter behaviour these settings defer to is provisioned by the
     * infrastructure module rather than by this annotation, so it is not observable from a unit test and is
     * not claimed here. Alternatives Considered: asserting the receive count at which a request is
     * dead-lettered. Rejected because that value lives in the queue definition, and a test that restated it
     * from here would assert a literal of its own rather than the deployed value.</p>
     *
     * <p>Assumptions: no attribute imposes a ceiling on the number of messages an execution may process, so
     * the loop ends on queue emptiness alone -- the target form of physical lines 377 and 378, which set the
     * no-more-messages condition when the get reports nothing available. The five-hundred-message ceiling
     * that does exist in this system belongs to the authorization consumer and is not this program's
     * discipline, so asserting a batch size here is deliberately not the same as asserting a ceiling.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws NoSuchMethodException if the handler method cannot be found, which would mean the contract this
     *     case reads has been renamed rather than that the assertion failed
     */
    @Test
    @DisplayName("the polling contract carries the baseline wait and hard-codes no destination")
    void thePollingContractCarriesTheBaselineWaitInterval() throws NoSuchMethodException {
        SqsListener annotation = InquiryMessageListener.class
                .getMethod("onRequest", Message.class)
                .getAnnotation(SqsListener.class);

        assertThat(annotation)
                .as("the handler must be bound to a queue by the container")
                .isNotNull();
        assertThat(annotation.queueNames())
                .as("exactly one request queue, named by placeholder so no location is committed")
                .hasSize(1);
        assertThat(annotation.queueNames()[0])
                .as("a destination must be resolved at run time, as COACCT01 L93-L96 expect")
                .startsWith("${")
                .endsWith("}");
        assertThat(annotation.pollTimeoutSeconds())
                .as("the wait must be the baseline's 5000 milliseconds from COACCT01 L337")
                .endsWith(":" + BASELINE_POLL_WAIT_SECONDS + "}");
        assertThat(annotation.factory())
                .as("SqsConfig declares no container factory, so none may be named")
                .isEmpty();
        assertThat(annotation.messageVisibilitySeconds())
                .as("the provisioned queue's own visibility timeout must govern redelivery")
                .isEmpty();
        assertThat(annotation.acknowledgementMode())
                .as("acknowledgement must stay on successful processing, never before it")
                .isEmpty();
    }

    /**
     * No state carries between messages, so one request cannot be answered out of another's data.
     *
     * <p>Purpose: the reference program declares {@code PROGRAM-ID. COACCT01 IS INITIAL.} at physical line 2
     * of {@code app/app-vsam-mq/cbl/COACCT01.cbl}, and that clause resets working storage on every
     * invocation. The target has no equivalent clause, so the same property has to be established by
     * construction and then asserted, because losing it would be invisible until two requests interfered.</p>
     *
     * <p>Assumptions: the structural half is that every field the consumer declares is final. That is what
     * makes per-message state impossible to accumulate rather than merely absent today, and it is asserted
     * over the declared fields rather than argued in prose. The one mutable container among them is a cache
     * of resolved configuration, which is why the assertion is about the reference being final rather than
     * about the class holding no map at all.</p>
     *
     * <p>Assumptions: the behavioural half runs three messages through ONE consumer instance -- an answered
     * inquiry, then a key the lookup does not find, then the first inquiry again. The middle reply must carry
     * the not-found sentence and none of the account block, and the third must be byte-identical to the
     * first. Two messages would not be enough: an implementation that cached the first answer and returned it
     * for everything afterwards would pass a two-message check that ended on the same key.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("no state carries between messages, as the reference program's initial clause guarantees")
    void noListenerStateCarriesBetweenMessages() {
        // WHY : Assumptions: the sweep covers static fields as well as instance fields, because a mutable
        //   static would be state shared across every message AND across every consumer in the process,
        //   which is strictly worse than the per-message accumulation this case is named for. Both are
        //   excluded by the same requirement, so both are checked by the same loop rather than by two.
        for (Field field : InquiryMessageListener.class.getDeclaredFields()) {
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("field %s must be final: no state may survive a message or be shared between two",
                            field.getName())
                    .isTrue();
        }

        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(this.accounts.findById(22_222_222_222L)).thenReturn(Optional.empty());

        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of()));
        this.listener.onRequest(message(request("INQA", "22222222222"), Map.of()));
        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of()));

        ArgumentCaptor<SendMessageRequest> sends = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, times(3)).sendMessage(sends.capture());
        List<SendMessageRequest> replies = sends.getAllValues();

        assertThat(replies.get(1).messageBody())
                .as("a lookup that found nothing must not be answered out of the previous request's data")
                .doesNotContain(LABEL_STATUS)
                .doesNotContain(LABEL_BALANCE);
        assertThat(replies.get(2).messageBody())
                .as("the same request must be answered identically however many preceded it")
                .isEqualTo(replies.get(0).messageBody());
    }

    /**
     * The published reply is the byte-exact fixed layout, framed to the declared message length.
     *
     * <p>Purpose: the payload is declared as a string on the wire, so its field ORDER and its OFFSETS are the
     * contract rather than a formatting choice -- a consumer locates each value by position. This case asserts
     * the shape a consumer actually depends on: the total length, the labelled block's own length, that
     * everything past the block is blank filler, and that the first three labels sit at the offsets their own
     * widths imply.</p>
     *
     * <p>Assumptions: the labelled block is {@code WS-ACCT-RESPONSE}, declared at physical lines 130 to 169
     * of {@code app/app-vsam-mq/cbl/COACCT01.cbl}, whose twenty-two field widths sum to the block length the
     * mapper publishes. The framing to the full message length is the baseline's own: physical line 467 moves
     * whichever reply it built into the buffer and physical line 468 puts a literal 1000 as the length,
     * regardless of how much of it carries data.</p>
     *
     * <p>Assumptions: the offsets are derived from the verbatim label widths rather than written as numbers,
     * so a label re-transcribed here into closer agreement with the reference moves the expectation with it
     * instead of leaving a stale literal behind. The labels themselves come from physical lines 132 to 139 of
     * the reference, which is read-only specification, and are reproduced character for character, which is
     * the specification's rule T8.</p>
     *
     * <p>Assumptions: every money field in this block is exact fixed point end to end. The account row is
     * built from {@code BigDecimal} values and rendered through the shared zoned-decimal codec, so no binary
     * floating-point type appears anywhere on the path -- which the specification's rule T3 requires and the
     * architecture rules in {@code common-lib} enforce independently.</p>
     *
     * <p>Assumptions: the block is compared against the renderer's own output for the same row rather than
     * against a transcribed expected string, and the two are not the same claim. What is under test here is
     * that the CONSUMER publishes that rendering unmodified and merely frames it, so a consumer that
     * re-ordered, trimmed or re-padded a field would fail even though the renderer still agreed with the
     * copybook. Alternatives Considered: a literal expected block written out in full. Rejected because the
     * money fields carry a sign overpunch whose character depends on the shared codec's convention, so a
     * transcribed literal would pin this case to that convention and fail on a change the wire contract does
     * not actually forbid -- while the renderer's agreement with the copybook is already asserted, field by
     * field, by the test that covers the renderer itself.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the published reply is the byte-exact fixed layout framed to the message length")
    void theReplyBlockIsTheByteExactFixedLayout() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));

        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of()));

        String body = captureSend().messageBody();
        assertThat(body)
                .as("the reply is framed to the declared message length, as COACCT01 L468 puts it")
                .hasSize(InquiryRequestCodec.MESSAGE_LENGTH);

        String block = body.substring(0, AccountInquiryReplyMapper.REPLY_BLOCK_LENGTH);
        assertThat(body.substring(AccountInquiryReplyMapper.REPLY_BLOCK_LENGTH))
                .as("everything past the labelled block is filler, so an offset reader is never misled")
                .isBlank();

        // WHY : Assumptions: each offset is the sum of the widths declared before it, so the expectation is
        //   computed from the verbatim labels rather than from numbers that would silently fall out of step
        //   with a label re-transcribed from the reference.
        int statusLabelOffset = LABEL_ACCOUNT_ID.length() + ACCOUNT_ID_WIDTH;
        int balanceLabelOffset = statusLabelOffset + LABEL_STATUS.length() + 1;
        assertThat(block).startsWith(LABEL_ACCOUNT_ID);
        assertThat(block.substring(LABEL_ACCOUNT_ID.length(), statusLabelOffset))
                .as("the identifier occupies its declared eleven digits")
                .isEqualTo("12345678901");
        assertThat(block.indexOf(LABEL_STATUS))
                .as("the status label sits immediately after the identifier")
                .isEqualTo(statusLabelOffset);
        assertThat(block.indexOf(LABEL_BALANCE))
                .as("the balance label sits immediately after the one-character status")
                .isEqualTo(balanceLabelOffset);
        assertThat(block)
                .as("the consumer publishes the rendered block unmodified and only frames it")
                .isEqualTo(new AccountInquiryReplyMapper().accountFound(account()));
    }

    /**
     * Verifies the intake bound cannot exceed the durable column the accepted value is written into.
     *
     * <p>Assumptions: the column is {@code request_key VARCHAR(128)} in
     * {@code V2__account_inquiry_reply_ledger.sql}, and the intake rule bounds an accepted identity at
     * {@link MessagingCorrelationId#MAX_LENGTH}. The relationship between the two is what makes an accepted
     * value storable by construction rather than by inspection, so it is asserted: widening the shared bound
     * past the column width fails here instead of on an insert in production.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the intake bound fits the durable ledger key column")
    void theIntakeBoundFitsTheLedgerKeyColumn() {
        assertThat(MessagingCorrelationId.MAX_LENGTH)
                .as("an identity admitted at intake must fit request_key VARCHAR(%d)",
                        LEDGER_KEY_COLUMN_WIDTH)
                .isLessThanOrEqualTo(LEDGER_KEY_COLUMN_WIDTH);
        assertThat(BROKER_MESSAGE_ID.length())
                .as("a broker identifier must fit the same column")
                .isLessThanOrEqualTo(LEDGER_KEY_COLUMN_WIDTH);
    }
}
