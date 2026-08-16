package com.carddemo.account.service;

import static java.util.Objects.requireNonNull;
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
import com.carddemo.common.codec.DateInquiryReplyCodec;
import com.carddemo.common.codec.InquiryRequestCodec;
import com.carddemo.common.messaging.MessageExpiry;
import com.carddemo.common.messaging.MessagingCorrelationId;
import io.awspring.cloud.autoconfigure.sqs.SqsProperties;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.config.SqsEndpoint;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.SqsContainerOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
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
     * The configured reply queue URL.
     *
     * <p>Refactoring Rationale: this was a bare queue NAME and a separate address the substituted client
     * resolved it to. Both are now one value, because a configured destination is an address: the deployment
     * sets every one of these variables from a {@code module.sqs.*_queue_url} output, and the consumer no
     * longer resolves anything. The pair of constants existed only to model a resolution step that could
     * never have succeeded against a real deployment.</p>
     */
    private static final String REPLY_URL = "https://sqs.test.invalid/000000000000/account-test-reply";

    /**
     * The configured request queue URL, which the diagnostic's queue-name field is derived from.
     *
     * <p>Assumptions: an address rather than a bare name, matching what the deployment supplies -- both
     * environment roots set this variable from the queue module's own {@code *_queue_url} output. The
     * bare-name form the listener annotation also accepts is exercised separately, by the case that asserts
     * the derivation, so this constant models the deployed shape and that case models the alternative.</p>
     */
    private static final String REQUEST_URL =
            "https://sqs.test.invalid/000000000000/account-test-request";

    /**
     * The request queue's NAME, as a diagnostic reports it.
     */
    private static final String REQUEST_QUEUE_NAME = "account-test-request";

    /**
     * The reply queue's NAME, as a diagnostic reports it when a reply could not be put.
     */
    private static final String REPLY_QUEUE_NAME = "account-test-reply";

    /**
     * The configured error queue URL.
     */
    private static final String ERROR_URL = "https://sqs.test.invalid/000000000000/account-test-error";

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
     * The destination the FIRST delivery of a request recorded, distinct from either configured queue.
     *
     * <p>Assumptions: it is deliberately neither {@link #REPLY_URL} nor {@link #ERROR_URL}, so a re-send
     * that resolved its destination afresh instead of reading the recorded one fails rather than passing by
     * coincidence. A configuration change between two deliveries of one request is the case the consumer's
     * own contract names for recording the destination at all.</p>
     */
    private static final String RECORDED_URL = "https://sqs.test.invalid/queue/account-recorded-reply";

    /**
     * The correlation identity the first delivery recorded, which a re-send must echo again.
     */
    private static final String RECORDED_CORRELATION_ID = "correlation-of-the-first-delivery";

    /**
     * The message identity the first delivery recorded, which a re-send must echo again.
     */
    private static final String RECORDED_MESSAGE_ID = "message-id-of-the-first-delivery";

    /**
     * The correlation identity a redelivery carries, which a re-send must NOT echo.
     *
     * <p>Assumptions: a broker redelivers the same message, so in a deployed environment this value equals
     * the recorded one. It is made different here on purpose: two differing values are the only way to tell
     * a re-send that republished the RECORDED identities from one that recomposed them out of the delivery
     * in hand, and the two are indistinguishable when they agree.</p>
     */
    private static final String REDELIVERY_CORRELATION_ID = "correlation-of-the-redelivery";

    /**
     * The message identity a redelivery carries, which a re-send must NOT echo.
     */
    private static final String REDELIVERY_MESSAGE_ID = "message-id-of-the-redelivery";

    /**
     * The balance the account carried when the first delivery composed its answer.
     *
     * <p>Assumptions: it differs from the balance {@link #account()} renders, so the recorded payload and
     * the payload a redelivery composes are different strings. That is the state the consumer's contract
     * names -- the account moved between the two deliveries -- and it is what makes "the recorded bytes
     * went out" an assertion rather than a restatement of a value that would match either way.</p>
     */
    private static final BigDecimal RECORDED_BALANCE = new BigDecimal("9876.54");

    /**
     * The framed reply the first delivery recorded, rendered from the account as it stood then.
     *
     * <p>Assumptions: it is rendered through the real mapper rather than transcribed, for the reason the
     * byte-exact layout case records -- the money fields carry a sign overpunch whose character belongs to
     * the shared codec, so a transcribed literal would pin this constant to that convention.</p>
     */
    private static final String RECORDED_PAYLOAD = frameReply(movedAccount());

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

        // WHY : Refactoring Rationale: there is no name-resolution stub here any more, and its absence is
        //   the assertion. The consumer used to call getQueueUrl with the configured value as a queue NAME,
        //   which against a real deployment meant asking for a queue called https://... -- it could only
        //   fail, and it failed only on the reply path, after the request had been consumed and its ledger
        //   claim committed. A stub that answered that call is what let these cases pass while the deployed
        //   behaviour did not work, so removing it is what keeps the two in agreement: an unstubbed mock
        //   would now return null and every send assertion below would fail.
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("m-1").build());

        // WHY : Assumptions: the ledger's claim answers TRUE by default, which is the first-delivery
        //   outcome every pre-existing case here is about. A mock answers false unstubbed, and false is
        //   the redelivery outcome -- so leaving it unstubbed would silently route every one of those
        //   cases down the duplicate-suppression path and assert nothing they were written for.
        // WHY : Assumptions: both of these are DEFAULTS and not the whole contract. The conflict cases
        //   below re-stub the claim to false through their own helper, and the retirement case re-stubs the
        //   mark to false, so the four branches this default forecloses -- suppression, recorded re-send,
        //   the vanished row and the lost retirement race -- are each driven explicitly by a case that
        //   states the condition it is about rather than inheriting it from here.
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
                this.sqs, REQUEST_URL, REPLY_URL, ERROR_URL, this.ledger,
                Clock.fixed(NOW, ZoneOffset.UTC), mock(PlatformTransactionManager.class));

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
     * Builds the same account as it stood when an earlier delivery answered, carrying the earlier balance.
     *
     * <p>Assumptions: only the balance differs, so the two rows are the same account at two moments rather
     * than two accounts. Changing the identifier as well would let a re-send case pass because it answered
     * about a different subject, which is not the property being asserted.</p>
     *
     * @return the row, never {@code null}
     */
    private static Account movedAccount() {
        return new Account(ACCOUNT_ID, "Y",
                RECORDED_BALANCE, new BigDecimal("5000.00"), new BigDecimal("500.00"),
                LocalDate.of(2020, 1, 15), LocalDate.of(2027, 12, 31), LocalDate.of(2024, 6, 1),
                new BigDecimal("111.11"), new BigDecimal("222.22"), "12345", "DEFAULT   ");
    }

    /**
     * Renders and frames the reply block one account row produces, as the consumer frames it before
     * recording it.
     *
     * @param row the account row to render; must not be {@code null}
     * @return the framed reply body, exactly the declared message length, never {@code null}
     */
    private static String frameReply(Account row) {
        AccountInquiryReplyMapper renderer = new AccountInquiryReplyMapper();
        return renderer.frame(renderer.accountFound(row));
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
     * Builds the redelivery of one request: the broker's own identity again, its own echoed identities.
     *
     * <p>Assumptions: the broker identifier is what makes this a REDELIVERY rather than a second question,
     * because that identifier is the key the claim is taken on and it is stable across every redelivery of
     * one message. The two producer-supplied identities are deliberately the redelivery's own, so a case can
     * tell which set a re-send echoed.</p>
     *
     * @return the message, never {@code null}
     */
    private static Message<String> redelivery() {
        return message(request("INQA", "12345678901"), Map.of(
                InquiryMessageListener.HEADER_BROKER_MESSAGE_ID, BROKER_MESSAGE_ID,
                InquiryMessageListener.ATTRIBUTE_CORRELATION_ID, REDELIVERY_CORRELATION_ID,
                InquiryMessageListener.ATTRIBUTE_MESSAGE_ID, REDELIVERY_MESSAGE_ID));
    }

    /**
     * Builds the answer an earlier delivery recorded, in the state named.
     *
     * <p>Assumptions: all four recorded values differ from anything the redelivery in hand carries or
     * composes, which is what makes the two re-send properties -- the recorded bytes and the recorded
     * addressing -- independently observable.</p>
     *
     * @param status the recorded state, {@link InquiryReplyLedger#STATUS_PENDING} or
     *     {@link InquiryReplyLedger#STATUS_SENT}; must not be {@code null}
     * @return the recorded answer, never {@code null}
     */
    private static InquiryReplyLedger.RecordedReply recordedReply(String status) {
        return new InquiryReplyLedger.RecordedReply(status, RECORDED_PAYLOAD, RECORDED_URL,
                RECORDED_CORRELATION_ID, RECORDED_MESSAGE_ID);
    }

    /**
     * Overrides the setup's first-delivery default so this delivery's claim reports a conflict.
     *
     * <p>Assumptions: the default in {@link #setUp()} is re-stubbed here rather than removed from there.
     * The default is what the twenty-seven first-delivery cases are about, and removing it would leave a
     * mock answering {@code false} -- which is the redelivery outcome -- so every one of those cases would
     * silently assert the wrong branch. Alternatives Considered: stubbing the claim explicitly in all cases
     * and having no default at all. Rejected because it would restate one line in every case to express a
     * condition only these four are about.</p>
     */
    private void conflictingClaim() {
        when(this.ledger.claim(anyString(), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class))).thenReturn(false);
    }

    /**
     * Stubs the ledger so this delivery's claim conflicts and a read finds the recorded answer.
     *
     * @param recorded the answer an earlier delivery left behind; must not be {@code null}
     */
    private void claimConflictsWith(InquiryReplyLedger.RecordedReply recorded) {
        conflictingClaim();
        when(this.ledger.find(BROKER_MESSAGE_ID)).thenReturn(Optional.of(recorded));
    }

    /**
     * Stubs the ledger so this delivery's claim conflicts and no row holds the key.
     *
     * <p>Assumptions: the empty answer is stubbed EXPLICITLY even though an unstubbed mock would return it
     * anyway. A case whose subject is the empty outcome must state it, or it would keep passing after a
     * change that made the read answer something else and the case would no longer be about anything.</p>
     */
    private void claimConflictsWithNoRow() {
        conflictingClaim();
        when(this.ledger.find(BROKER_MESSAGE_ID)).thenReturn(Optional.empty());
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
     * Verifies the date function code is answered from the clock, on the same queue, without a lookup.
     *
     * <p>Purpose: this consumer owns the ONE request queue the migrated topology provisions, so it answers
     * both inquiry flows the baseline drove from {@code CARDDEMO.REQUEST.QUEUE} -- the account inquiry of
     * {@code COACCT01.cbl} and the date-and-time inquiry of {@code CODATE01.cbl}. Without this case the merge
     * would be asserted only by the invalid-parameters branch, which cannot distinguish a date request that
     * was ROUTED from one that was refused.</p>
     *
     * <p>Assumptions: the expected body is the labelled fixed-width block {@code CODATE01.cbl} builds at
     * physical lines 216 to 222 -- {@code 'SYSTEM DATE : '} then {@code MM-DD-YYYY}, then
     * {@code 'SYSTEM TIME : '} then {@code HH:MM:SS} -- rendered against the fixed clock this class injects,
     * so the assertion is on a literal rather than on a re-derivation of the formatter under test.</p>
     *
     * <p>Assumptions: no repository call may occur. The reference program performs no file access at all: it
     * reads the clock and replies, so a lookup here would be work the baseline never did and would couple the
     * date answer to the availability of the account store.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a date function code is answered with the labelled system date and time block")
    void aDateFunctionCodeIsAnsweredFromTheClock() {
        this.listener.onRequest(message(request("DATE", "00000000000"), Map.of()));

        assertThat(captureSend().messageBody())
                .startsWith("SYSTEM DATE : 08-07-2026SYSTEM TIME : 12:00:00");
        verify(this.accounts, never()).findById(anyLong());
    }

    /**
     * Verifies the date answer is the shared codec's own rendering, framed to the wire length.
     *
     * <p>Purpose: the layout belongs to {@code common-lib}'s {@link DateInquiryReplyCodec}, beside the request
     * half that decodes the same 1000-character record, so that one context cannot render a body the other
     * cannot read. This case pins that the consumer DELEGATES rather than reimplements: a second rendering
     * here would drift from the codec silently, and the previous case's literal alone would not detect it
     * because a local copy would satisfy the literal too.</p>
     *
     * <p>Assumptions: the comparison is byte-exact over the whole body, including the trailing pad, because
     * the reply is a positional record and a consumer decoding it by offset is affected by its length as much
     * as by its content.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the date answer is the shared codec's rendering, byte for byte")
    void theDateAnswerIsTheSharedCodecRendering() {
        this.listener.onRequest(message(request("DATE", "12345678901"), Map.of()));

        assertThat(captureSend().messageBody())
                .isEqualTo(DateInquiryReplyCodec.framedSystemDateAndTime(
                        LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));
    }

    /**
     * Verifies the date route precedes the account guard, so a key the account route refuses still answers.
     *
     * <p>Purpose: {@code CODATE01.cbl} declares {@code WS-FUNC} and {@code WS-KEY} at physical lines 110 and
     * 111 and reads neither, so the date answer cannot depend on the key. The account route's own guard is
     * the opposite -- {@code IF WS-FUNC = 'INQA' AND WS-KEY > ZEROES} refuses a zero key without a lookup --
     * so driving a zero key here distinguishes an implementation that dispatched before the guard from one
     * that fell through it and refused a request the baseline answered.</p>
     *
     * <p>Assumptions: the same zero key is driven through the account route in
     * {@link #aZeroKeyIsRefusedWithoutALookup()}, so the two cases together establish that the guard still
     * applies where it did and no longer applies where it did not.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the date route ignores the key the account guard refuses")
    void theDateRouteIgnoresTheKey() {
        this.listener.onRequest(message(request("DATE", "00000000000"), Map.of()));

        assertThat(captureSend().messageBody())
                .startsWith("SYSTEM DATE : ")
                .doesNotContain("INVALID REQUEST PARAMETERS");
        verify(this.accounts, never()).findById(anyLong());
    }

    /**
     * Verifies the date discriminator is matched case sensitively, as the baseline's literal test is.
     *
     * <p>Purpose: the function code is compared against a COBOL literal in both reference programs, and a
     * literal comparison is case sensitive, so a lower-case variant is an unrecognised code rather than a
     * date request. This case is the divergence boundary the merge introduces: on separate trigger queues an
     * unrecognised code reaching the date program was answered with the date, because that program tests
     * nothing; on one queue it receives {@code COACCT01}'s own refusal. That is the stricter of the two
     * baseline behaviours and is registered in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Assumptions: the refusal sentence is asserted rather than merely the absence of a date block,
     * because an implementation that dropped the message silently would also lack a date block, and a
     * dropped request is acknowledged with no answer at all.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a lower-case date code is an unrecognised code, not a date request")
    void aLowerCaseDateCodeIsRefused() {
        this.listener.onRequest(message(request("date", "12345678901"), Map.of()));

        assertThat(captureSend().messageBody())
                .startsWith("INVALID REQUEST PARAMETERS ACCT ID : 12345678901FUNCTION : date")
                .doesNotContain("SYSTEM DATE");
        verify(this.accounts, never()).findById(anyLong());
    }

    // WHY : Assumptions: the payload is driven EMPTY rather than short-but-present, because an empty body is
    //       a real wire condition on this queue -- services/reference-service/src/test/resources/fixtures/
    //       date_conversion/empty_input/date-request.txt is a zero-byte fixture of exactly this shape -- and
    //       because it is the one input on which the merged consumer's two halves could disagree. The codec
    //       pads a short payload to the declared length, so the function field arrives as four spaces, which
    //       is neither of the two codes the dispatch recognises.
    // WHY : Trade-offs: the case asserts the message is ANSWERED, not dropped. Refusing it would dead-letter
    //       a delivery the baseline replied to, and answering it with the date would make a blank field mean
    //       DATE. Refusing it IN THE REPLY is the only reading that both consumes the message and keeps the
    //       blank field distinguishable from the date request.
    /**
     * Verifies an empty payload is answered with the refusal rather than with the date or a dead letter.
     */
    @Test
    @DisplayName("an empty payload is answered with the refusal, not with the date")
    void anEmptyPayloadIsAnsweredWithTheRefusal() {
        this.listener.onRequest(message("", Map.of()));

        assertThat(captureSend().messageBody())
                .as("a padded-to-blank function code is neither DATE nor INQA, so the guard answers")
                .startsWith("INVALID REQUEST PARAMETERS ACCT ID : ")
                .doesNotContain("SYSTEM DATE");
        assertThat(InquiryRequestCodec.decode("").functionLabel())
                .as("what a journal line receives for a blank field is the blank token, not the field")
                .isEqualTo(InquiryRequestCodec.FUNCTION_LABEL_BLANK);
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
     * carry NO exception message text. An exception message is the one part of a failure into which a request
     * value can be interpolated, and this body is published onto a queue.</p>
     *
     * <p>⚠️ Refactoring Rationale: this case asserted that the body CONTAINED the failure's type name, and
     * that expectation was the defect written down. The type chain published this service's own class names,
     * the queue client's exception hierarchy and the frame a failure was raised at onto a queue, where none
     * of it is actionable and all of it changes under a refactoring that changes no behaviour. The case now
     * asserts the classified condition instead, and asserts the type name's ABSENCE, so the previous
     * rendering cannot return without failing here.</p>
     *
     * <p>⚠️ Refactoring Rationale: the queue NAME is asserted, and it was not. The name reported was the
     * error queue's own -- the sink the report was published to -- where the baseline names the INPUT queue
     * for a failure that is about no queue at all, at physical line 441 of
     * {@code app/app-vsam-mq/cbl/COACCT01.cbl}.</p>
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
                .contains(REQUEST_QUEUE_NAME)
                .contains("condition=datastore-unavailable")
                .doesNotContain("QueryTimeoutException")
                .doesNotContain("the read timed out");
    }

    /**
     * A reply the queue service refuses is reported as a PUT failure naming the reply queue.
     *
     * <p>Purpose: this is the arm the target did not have. {@code app/app-vsam-mq/cbl/COACCT01.cbl} keeps
     * two failure arms apart -- {@code 4100-PUT-REPLY} moves {@code 'MQPUT ERR'} into the return-message
     * field at physical line 496 with the reply queue's name beside it at 495, while the account-file read
     * arm moves {@code 'ERROR WHILE READING ACCTFILE'} at 442 and 443 with the input queue's name at 441 --
     * and the target reported the read arm for both. An operator reading the sink over a queue outage was
     * therefore sent to the account table.</p>
     *
     * <p>Assumptions: the REPLY send is what fails and the ERROR send succeeds, which is the condition the
     * two arms are distinguished for. The stub answers the first send with a failure and the second
     * normally, so the diagnostic itself reaches the sink and can be read.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a refused reply put is reported as MQPUT ERR against the reply queue")
    void aRefusedReplyPutIsReportedAsAPutFailure() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SdkClientException.create("the reply queue is unreachable"))
                .thenReturn(SendMessageResponse.builder().messageId("m-error").build());

        assertThatThrownBy(() -> this.listener.onRequest(message(request("INQA", "12345678901"), Map.of())))
                .isInstanceOf(SdkClientException.class);

        ArgumentCaptor<SendMessageRequest> sends = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, times(2)).sendMessage(sends.capture());
        SendMessageRequest reported = sends.getAllValues().get(1);

        assertThat(reported.queueUrl())
                .as("the diagnostic still goes to the error sink")
                .isEqualTo(ERROR_URL);
        assertThat(reported.messageBody())
                .hasSize(InquiryRequestCodec.MESSAGE_LENGTH)
                .startsWith("4000-PROCESS-REQUEST-REPL")
                .contains("MQPUT ERR")
                .contains(REPLY_QUEUE_NAME)
                .contains("condition=queue-unavailable");
        assertThat(reported.messageBody())
                .as("the read arm's literal must not be reported for a put failure")
                .doesNotContain("ERROR WHILE READING");
    }

    /**
     * A failure recording the answer durably is reported with no baseline literal and names the request
     * queue.
     *
     * <p>Purpose: the durable claim has no counterpart in {@code app/app-vsam-mq/cbl/COACCT01.cbl}, which
     * takes its idempotency from a syncpoint bracket rather than from a table, so there is no literal to
     * carry into the return-message field. Reporting the read arm's literal would send an operator to the
     * account table for a ledger fault, and reporting the put arm's would send them to the queue -- so the
     * field is left blank and the condition is named in the diagnostic's free-text tail, which is the
     * convention this consumer already follows for the one other condition the baseline does not declare.</p>
     *
     * <p>Assumptions: the failure is raised by the CLAIM, which happens after the reply has been composed,
     * so the read has already succeeded and the exchange is unambiguously in its answer step.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a ledger failure is reported with no baseline literal, against the request queue")
    void aLedgerFailureIsReportedWithoutABaselineLiteral() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(this.ledger.claim(anyString(), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class)))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("the row is locked"));

        // WHY : Assumptions: a BROKER identifier is supplied, because a delivery carrying none is answered
        //   unguarded and never reaches the ledger at all -- so a case built on the plain helper would
        //   assert nothing about a ledger failure.
        assertThatThrownBy(() -> this.listener.onRequest(message(request("INQA", "12345678901"),
                Map.of(InquiryMessageListener.HEADER_BROKER_MESSAGE_ID, BROKER_MESSAGE_ID))))
                .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);

        SendMessageRequest reported = captureSend();
        assertThat(reported.messageBody())
                .hasSize(InquiryRequestCodec.MESSAGE_LENGTH)
                .startsWith("4000-PROCESS-REQUEST-REPL")
                .contains(REQUEST_QUEUE_NAME)
                .contains("condition=datastore-unavailable");
        assertThat(reported.messageBody())
                .as("neither baseline literal describes a ledger write, so neither may be reported")
                .doesNotContain("ERROR WHILE READING")
                .doesNotContain("MQPUT ERR");
        assertThat(reported.messageBody().substring(27, 52).trim())
                .as("the return-message field is blank where the baseline declares no literal")
                .isEmpty();
    }

    /**
     * A queue-service refusal publishes its HTTP status and still names no internal type.
     *
     * <p>Purpose: the status is the one part of a queue failure that belongs to the queue service's PUBLIC
     * contract, so it survives any refactoring here and tells an operator whether the service refused the
     * request or failed to answer it. This case asserts it is published and that the implementation type
     * that carried it is not -- which is the whole of the change: the same information, none of the map of
     * how this service is built.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a queue-service refusal publishes its status and no exception type")
    void aQueueServiceRefusalPublishesItsStatusAndNoType() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(this.sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(QueueDoesNotExistException.builder()
                        .message("the specified queue does not exist")
                        .statusCode(400)
                        .build())
                .thenReturn(SendMessageResponse.builder().messageId("m-error").build());

        assertThatThrownBy(() -> this.listener.onRequest(message(request("INQA", "12345678901"), Map.of())))
                .isInstanceOf(QueueDoesNotExistException.class);

        ArgumentCaptor<SendMessageRequest> sends = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(this.sqs, times(2)).sendMessage(sends.capture());
        String body = sends.getAllValues().get(1).messageBody();

        assertThat(body)
                .contains("MQPUT ERR")
                .contains("condition=queue-unavailable")
                .contains("status=400");
        assertThat(body)
                .as("no implementation type, package or frame may reach the queue")
                .doesNotContain("QueueDoesNotExistException")
                .doesNotContain("software.amazon")
                .doesNotContain("com.carddemo")
                .doesNotContain("the specified queue does not exist");
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
     * Verifies the configured address is published to directly, with no name resolution at any point.
     *
     * <p>Refactoring Rationale: this case asserted that the address was resolved ONCE and cached. The
     * resolution is gone rather than optimised, so the case now asserts that it never happens -- across
     * three deliveries, not one, because a per-message call is exactly what a cache was there to prevent
     * and a single delivery could not tell a removed call from a cached one.</p>
     */
    @Test
    @DisplayName("the configured address is published to directly and no name resolution occurs")
    void noQueueNameResolutionOccurs() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));

        for (int attempt = 0; attempt < 3; attempt++) {
            this.listener.onRequest(message(request("INQA", "12345678901"), Map.of()));
        }

        verify(this.sqs, never()).getQueueUrl(any(GetQueueUrlRequest.class));
        verify(this.sqs, times(3)).sendMessage(any(SendMessageRequest.class));
    }

    /**
     * Verifies a destination configured as a URL is used as one, with no resolution call at all.
     *
     * <p>Purpose: this is the deployed configuration and it is what the checkpoint's dev and prod roots
     * actually produce. Both roots inject {@code CARDDEMO_ACCOUNT_INQUIRY_REPLY_QUEUE_URL} and
     * {@code CARDDEMO_ACCOUNT_INQUIRY_ERROR_QUEUE_URL} from the queue module's {@code *_queue_url} outputs, so
     * the values this consumer receives in a provisioned environment are addresses. Before the fix every
     * one of them was passed to {@code GetQueueUrl} as a queue NAME -- which a URL cannot be, a name
     * admitting only alphanumerics, hyphens and underscores -- so both the reply and the diagnostic failed
     * at the resolution call before their send, the request was redelivered into the same failure and
     * dead-lettered, and the requester received nothing.</p>
     *
     * <p>Assumptions: the assertion is that {@code getQueueUrl} is called ZERO times and not merely that
     * the send reached the right address. A resolution call would still be made against a mock that
     * answers one, so the address alone would pass on the broken code path in this class's fixture; the
     * interaction count is the part that cannot.</p>
     *
     * <p>Assumptions: both destinations are exercised in one case, through a reply and through a
     * diagnostic, because the two are configured independently and a fix applied to one alone would leave
     * the other failing in a provisioned environment while a reply-only case stayed green.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a destination configured as a URL is sent to directly with no resolution call")
    void aUrlDestinationIsUsedWithoutResolution() {
        SqsClient client = mock(SqsClient.class);
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("m-url").build());
        AccountRepository repository = mock(AccountRepository.class);
        when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        InquiryReplyLedger replyLedger = mock(InquiryReplyLedger.class);
        when(replyLedger.claim(anyString(), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class))).thenReturn(true);
        when(replyLedger.markSent(anyString(), any(LocalDateTime.class))).thenReturn(true);

        InquiryMessageListener addressed = new InquiryMessageListener(repository,
                new AccountInquiryReplyMapper(), client, REQUEST_URL, REPLY_URL, ERROR_URL, replyLedger,
                Clock.fixed(NOW, ZoneOffset.UTC), mock(PlatformTransactionManager.class));

        addressed.onRequest(message(request("INQA", "12345678901"), Map.of()));
        addressed.publishError("ERROR WHILE READING ACCTFILE");

        verify(client, never()).getQueueUrl(any(GetQueueUrlRequest.class));
        ArgumentCaptor<SendMessageRequest> sends = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client, times(2)).sendMessage(sends.capture());
        assertThat(sends.getAllValues())
                .extracting(SendMessageRequest::queueUrl)
                .as("the injected addresses are used verbatim, in reply-then-diagnostic order")
                .containsExactly(REPLY_URL, ERROR_URL);
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
     * Verifies a destination that is not a fully-qualified queue URL is refused at construction.
     *
     * <p>Refactoring Rationale: this case checked only that a BLANK destination was refused, which is the
     * weaker half of the contract and the half that was never the problem. A bare queue name passed the old
     * check, and a bare queue name is exactly what the consumer could not publish to -- so the case now
     * covers blank and bare-name alike, on both destinations, and each refusal must still name the property
     * an operator has to set.</p>
     */
    @Test
    @DisplayName("a blank or bare-name destination is refused at construction, naming the property")
    void aDestinationThatIsNotAQueueUrlIsRefused() {
        AccountInquiryReplyMapper mapper = new AccountInquiryReplyMapper();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

        assertThatThrownBy(() -> new InquiryMessageListener(this.accounts, mapper, this.sqs,
                REQUEST_URL, " ", ERROR_URL, this.ledger, clock, transactions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carddemo.account.inquiry.reply-queue-url");
        assertThatThrownBy(() -> new InquiryMessageListener(this.accounts, mapper, this.sqs,
                REQUEST_URL, REPLY_URL, "", this.ledger, clock, transactions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carddemo.account.inquiry.error-queue-url");
        assertThatThrownBy(() -> new InquiryMessageListener(this.accounts, mapper, this.sqs,
                REQUEST_URL, "account-test-reply", ERROR_URL, this.ledger, clock, transactions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carddemo.account.inquiry.reply-queue-url");
        assertThatThrownBy(() -> new InquiryMessageListener(this.accounts, mapper, this.sqs,
                REQUEST_URL, REPLY_URL, "account-test-error", this.ledger, clock, transactions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carddemo.account.inquiry.error-queue-url");
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
     * this exchange commits no decision, because its read is read-only. The ledger guarantees that no
     * requester is answered TWICE with DIFFERING content -- a redelivery either suppresses its duplicate or
     * re-sends the recorded bytes verbatim. Asserting the absence of the first while the second is present is
     * the whole point of the case, so the ledger is deliberately not treated as a violation.</p>
     *
     * <p>Refactoring Rationale: this paragraph stated the guarantee as "a reply is not sent TWICE", and that
     * is not what the implementation provides. {@code answerOnce} commits the claim, sends, and only then
     * marks the claim sent, so a task that dies between the send and the mark leaves the claim outstanding
     * and the redelivery SENDS AGAIN -- the class documentation says so in its own Trade-offs paragraph, and
     * closing that window would need the queue send and the database mark to commit together, which is the
     * two-phase commit AAP section 0.7.6 records as eliminated. A test asserting the stronger property
     * certified something no code here delivers, which is worse than asserting nothing: a reader takes a
     * green suite as evidence. The guarantee is restated as the one that holds, and the two arms that make
     * it hold are now exercised by
     * {@link #anOutstandingClaimResendsTheRecordedReply()} and
     * {@link #aRetiredClaimSuppressesTheDuplicate()} rather than only described here.</p>
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
        // WHY : Refactoring Rationale: the interaction pinned here was a name-resolution call, which no
        //   longer exists -- the consumer publishes to the configured address directly. The count that now
        //   carries the same weight is the send count: exactly one publication per delivery, which an added
        //   relay would move out of this invocation entirely.
        SendMessageRequest sent = captureSend();
        assertThat(sent.queueUrl()).isEqualTo(REPLY_URL);
        assertThat(sent.messageBody()).startsWith(LABEL_ACCOUNT_ID);
        verify(this.sqs, times(1)).sendMessage(any(SendMessageRequest.class));
        verify(this.sqs, never()).getQueueUrl(any(GetQueueUrlRequest.class));

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
     * Verifies a redelivery whose claim is still OUTSTANDING re-sends the recorded bytes, not a fresh reply.
     *
     * <p>Purpose: this is the crash window the class documentation names, and it was the one arm of the claim
     * ledger no case exercised. {@code answerOnce} commits the claim, sends, and only then marks the claim
     * sent, so a task killed between the send and the mark -- or one whose acknowledgement is lost, or whose
     * visibility timeout elapses while the send is in flight -- leaves the row PENDING. The next delivery of
     * the same message finds the claim taken and must re-send what was recorded rather than recompose an
     * answer, because the account may have moved in between and two replies bearing one correlation
     * identifier that disagree about a balance is the outcome the ledger exists to prevent.</p>
     *
     * <p>Assumptions: the crash is represented by the LEDGER's answers rather than by killing anything. The
     * claim reports a conflict and the row reports {@code PENDING}, which is exactly the durable state a task
     * that died after its send leaves behind, so the arm under test is reached without a second process and
     * without a real database.</p>
     *
     * <p>Assumptions: the recorded values are deliberately DIFFERENT from the ones this delivery would
     * compose -- a distinct payload, the error queue as the recorded destination, and distinct identities --
     * so the assertion can tell "re-sent the record" from "recomposed and happened to match". A recorded copy
     * identical to a fresh one would make the case pass on an implementation that ignored the record.</p>
     *
     * <p>Assumptions: the send happens BEFORE the mark on this arm too, and the order is asserted. A
     * redelivery that marked first would suppress the re-send of a reply that never reached the queue,
     * turning a duplicated answer into a missing one -- the worse of the two failures.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a redelivery with an outstanding claim re-sends the recorded reply verbatim")
    void anOutstandingClaimResendsTheRecordedReply() {
        String recordedPayload = "RECORDED REPLY BYTES";
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(this.ledger.claim(anyString(), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class))).thenReturn(false);
        when(this.ledger.find(BROKER_MESSAGE_ID)).thenReturn(Optional.of(
                new InquiryReplyLedger.RecordedReply(InquiryReplyLedger.STATUS_PENDING, recordedPayload,
                        ERROR_URL, "recorded-corr", "recorded-msg")));

        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of(
                InquiryMessageListener.HEADER_BROKER_MESSAGE_ID, BROKER_MESSAGE_ID,
                InquiryMessageListener.ATTRIBUTE_CORRELATION_ID, "this-delivery-corr")));

        SendMessageRequest sent = captureSend();
        assertThat(sent.messageBody())
                .as("the recorded bytes are re-sent rather than an answer composed on this delivery")
                .isEqualTo(recordedPayload);
        assertThat(sent.queueUrl())
                .as("the recorded destination is used, so a configuration change between deliveries cannot"
                        + " split one answer across two queues")
                .isEqualTo(ERROR_URL);
        assertThat(sent.messageAttributes().get(InquiryMessageListener.ATTRIBUTE_CORRELATION_ID)
                        .stringValue())
                .as("the recorded correlation identity is echoed, not this delivery's")
                .isEqualTo("recorded-corr");
        assertThat(sent.messageAttributes().get(InquiryMessageListener.ATTRIBUTE_MESSAGE_ID)
                        .stringValue())
                .as("the recorded message identity is echoed too")
                .isEqualTo("recorded-msg");

        InOrder sequence = inOrder(this.ledger, this.sqs);
        sequence.verify(this.ledger).find(BROKER_MESSAGE_ID);
        sequence.verify(this.sqs).sendMessage(any(SendMessageRequest.class));
        sequence.verify(this.ledger).markSent(eq(BROKER_MESSAGE_ID), any(LocalDateTime.class));
    }

    /**
     * Verifies a redelivery whose claim has been RETIRED sends nothing and still acknowledges the request.
     *
     * <p>Purpose: this is the other half of the guarantee. Once the mark has been committed the requester
     * demonstrably has its answer, so a further delivery of the same message must be dropped: sending again
     * would hand a second copy to a requester that already paired the first to its question, and raising
     * would put a correctly answered request through redelivery to the dead-letter queue.</p>
     *
     * <p>Assumptions: the assertion is that NO send occurs and that the handler returns normally. Those two
     * together are what "acknowledged and suppressed" means over this API -- the container deletes the
     * message precisely because the handler did not throw -- and the absent mark is what says this delivery
     * did not claim credit for a send it never made.</p>
     *
     * <p>Assumptions: the account is deliberately still stubbed as readable, so the suppression is decided
     * from the ledger rather than from a failed read. A case whose account was absent would pass for the
     * wrong reason.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a redelivery with a retired claim suppresses the duplicate and sends nothing")
    void aRetiredClaimSuppressesTheDuplicate() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(this.ledger.claim(anyString(), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class))).thenReturn(false);
        when(this.ledger.find(BROKER_MESSAGE_ID)).thenReturn(Optional.of(
                new InquiryReplyLedger.RecordedReply(InquiryReplyLedger.STATUS_SENT, "SENT BYTES",
                        REPLY_URL, "recorded-corr", "recorded-msg")));

        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of(
                InquiryMessageListener.HEADER_BROKER_MESSAGE_ID, BROKER_MESSAGE_ID)));

        verify(this.sqs, never()).sendMessage(any(SendMessageRequest.class));
        verify(this.ledger, never()).markSent(anyString(), any(LocalDateTime.class));
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
     * The handler's annotation binds a queue and sizes nothing, so one namespace governs the container.
     *
     * <p>Purpose: the container, not this class, performs the baseline's driver loop, and its parameters may
     * be declared in two places -- on this annotation, or under {@code spring.cloud.aws.sqs.listener} in the
     * profiles. This case asserts that the annotation declares NONE of them, which is what leaves the
     * profiles as the single authority; the sibling case asserts what those profiles then resolve to.</p>
     *
     * <p>Refactoring Rationale: this annotation used to carry {@code maxConcurrentMessages},
     * {@code maxMessagesPerPoll} and {@code pollTimeoutSeconds}, each a placeholder over a
     * {@code carddemo.account.inquiry.*} key with a literal default of 10, 10 and 5, and this case read those
     * attribute STRINGS -- asserting that the poll attribute ended in {@code ":5}"}. That assertion passed on
     * text while the deployed behaviour was wrong: none of the three keys was declared in any profile, so the
     * literal defaults applied, and an endpoint value overrides the factory's options rather than defaulting
     * beneath them. The development profile's concurrency of two therefore ran as ten against a
     * four-connection pool, and reading an attribute's text could not have detected it. Asserting the emptiness
     * of every sizing attribute is the assertion that HAS to hold for the profile to be in charge.</p>
     *
     * <p>Assumptions: the wait itself is the baseline's own and is asserted in the sibling case rather than
     * here. Physical line 337 of {@code app/app-vsam-mq/cbl/COACCT01.cbl} moves 5000 into
     * {@code MQGMO-WAITINTERVAL}, expressed by that interface in milliseconds, so five seconds is the same
     * wait and not a chosen one -- and {@code application.yml} is where it now appears, as
     * {@code poll-timeout: 5s}.</p>
     *
     * <p>Refactoring Rationale: the wait is asserted as a bare PLACEHOLDER rather than as a placeholder
     * carrying a five-second fallback, and the change is the point. Every one of the three container
     * attributes used to carry an inline default, and an annotation attribute takes precedence over the
     * container factory's own setting -- so the fallback silently overrode whatever a profile declared for
     * the same limit, and {@code application-dev.yml}'s deliberately smaller concurrency pair had no effect
     * at all. The properties are declared in {@code application.yml} instead, which is where a profile can
     * reach them, and an undeclared limit now stops start-up rather than reinstating a value no file
     * states.</p>
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
     * <p>Refactoring Rationale: this case previously asserted that the annotation CARRIED the five-second
     * wait, and that assertion is inverted here rather than deleted. A non-null annotation attribute is
     * applied over the container factory the {@code spring.cloud.aws.sqs.listener} keys configure, so the
     * three sizing attributes this annotation once declared -- the wait and both concurrency bounds -- made
     * every per-profile value inert, including the development profile's deliberately smaller pair. The
     * attributes are withdrawn, so what this case now pins is that the annotation declares a queue and
     * nothing else; the wait itself is asserted where it now lives by
     * {@link #theBaselineWaitIsDeclaredInConfiguration()}, so the value is not merely dropped here.</p>
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
    @DisplayName("the handler's annotation binds a queue and sizes nothing")
    void thePollingContractCarriesNoSizingAttribute() throws NoSuchMethodException {
        SqsListener annotation = handlerAnnotation();

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
        assertThat(annotation.maxConcurrentMessages())
                .as("concurrency belongs to spring.cloud.aws.sqs.listener, which dev reduces to two")
                .isEmpty();
        assertThat(annotation.maxMessagesPerPoll())
                .as("batch size belongs to spring.cloud.aws.sqs.listener, which dev reduces to two")
                .isEmpty();
        assertThat(annotation.pollTimeoutSeconds())
                .as("the baseline's wait belongs to application.yml as poll-timeout: 5s")
                .isEmpty();
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
     * The handler's annotation names the container identifier the health indicator resolves.
     *
     * <p>Purpose: the container had no identifier, so the framework generated a positional one and nothing
     * could resolve the container by it. That is what left the consumer's running state unreachable and let
     * this service report itself healthy while consuming nothing at all. This case pins the identifier and,
     * separately, pins that the annotation takes it from the indicator's own constant rather than repeating
     * its text -- a mismatch between the two would not fail at start-up, it would make the health signal
     * report the consumer missing on a working task, which costs a task replacement for nothing.</p>
     *
     * <p>Assumptions: the constant's VALUE is asserted as well as the reference, because a reference the
     * two sides share is still wrong if the value it carries is not the one an operator comparing two
     * services' health output expects. It follows the sibling authorization consumer's own spelling.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws NoSuchMethodException if the handler method cannot be found, which would mean the contract
     *     this case reads has been renamed rather than that the assertion failed
     */
    @Test
    @DisplayName("the handler's annotation names the container the health indicator resolves")
    void theHandlerDeclaresTheContainerIdentifier() throws NoSuchMethodException {
        SqsListener annotation = handlerAnnotation();

        assertThat(annotation.id())
                .as("a registry lookup that misses answers nothing and silently loses the health signal")
                .isEqualTo(InquiryListenerHealth.REQUEST_CONTAINER_ID);
        assertThat(InquiryListenerHealth.REQUEST_CONTAINER_ID)
                .as("the identifier is this consumer's stable name, following the sibling consumer's")
                .isEqualTo("carddemo-account-inquiry-listener");
    }

    /**
     * A redelivery that re-sends counts its delivery on the ledger, before it sends.
     *
     * <p>Purpose: {@code attempts} moved only when a send SUCCEEDED, so it never moved for the one
     * condition an operator reads it for -- a reply whose send keeps failing -- and stayed at its inserted
     * value across every redelivery. Counting at the point a delivery decides to re-send, and committing
     * that count before the send, is what makes the column equal the number of deliveries that reached the
     * send step.</p>
     *
     * <p>Assumptions: the ORDER is asserted, not merely the call. A count taken after the send would be
     * the one count never recorded on the path that matters, because on that path the send raises.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a redelivery that re-sends counts the delivery before sending")
    void aRedeliveryThatResendsCountsTheDeliveryFirst() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(this.ledger.claim(anyString(), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class))).thenReturn(false);
        when(this.ledger.find(BROKER_MESSAGE_ID)).thenReturn(Optional.of(
                new InquiryReplyLedger.RecordedReply(InquiryReplyLedger.STATUS_PENDING,
                        "RECORDED", RECORDED_URL, RECORDED_CORRELATION_ID, "recorded-msg")));

        this.listener.onRequest(redelivery());

        InOrder sequence = inOrder(this.ledger, this.sqs);
        sequence.verify(this.ledger).find(BROKER_MESSAGE_ID);
        sequence.verify(this.ledger).countSendAttempt(BROKER_MESSAGE_ID);
        sequence.verify(this.sqs).sendMessage(any(SendMessageRequest.class));
    }

    /**
     * A redelivery whose claim is already retired is suppressed and is NOT counted.
     *
     * <p>Purpose: the count answers how many times the send of one reply was attempted, and this path
     * attempts none -- the requester demonstrably has its answer, so the duplicate is dropped. Counting
     * here would keep advancing for as long as the queue kept redelivering a request that had already been
     * answered, which is the one case where a high count would mean nothing is wrong.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a suppressed duplicate is not counted as a send attempt")
    void aSuppressedDuplicateIsNotCounted() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(this.ledger.claim(anyString(), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class))).thenReturn(false);
        when(this.ledger.find(BROKER_MESSAGE_ID)).thenReturn(Optional.of(
                new InquiryReplyLedger.RecordedReply(InquiryReplyLedger.STATUS_SENT,
                        "RECORDED", RECORDED_URL, RECORDED_CORRELATION_ID, "recorded-msg")));

        this.listener.onRequest(redelivery());

        verify(this.ledger, never()).countSendAttempt(anyString());
        verify(this.sqs, never()).sendMessage(any(SendMessageRequest.class));
    }

    /**
     * A first delivery is counted by the claim itself and never counted again.
     *
     * <p>Purpose: the claim inserts the count for the delivery it admits, so the ordinary successful
     * exchange must not add a second one. This case pins that neither the explicit count nor the
     * retirement contributes to it on the first-delivery path.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a first delivery is counted by the claim alone")
    void aFirstDeliveryIsCountedByTheClaimAlone() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));

        this.listener.onRequest(redelivery());

        verify(this.ledger).claim(eq(BROKER_MESSAGE_ID), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class));
        verify(this.ledger, never()).countSendAttempt(anyString());
        verify(this.ledger).markSent(eq(BROKER_MESSAGE_ID), any(LocalDateTime.class));
    }

    /**
     * The container options the profiles actually resolve to are the profiles' own, not the annotation's.
     *
     * <p>Purpose: the preceding case asserts that the handler's annotation declares no sizing attribute, which
     * is necessary for the profile to govern but not sufficient to show what the profile then produces. This
     * case produces it: it loads the module's real {@code application.yml} and the real profile document on
     * top of it, binds them through the framework's own {@code SqsProperties}, feeds the result to a real
     * {@code SqsMessageListenerContainerFactory} exactly as {@code SqsAutoConfiguration} does, builds a real
     * container for an endpoint derived from the real {@code @SqsListener} annotation, and reads the RESOLVED
     * options back off that container.</p>
     *
     * <p>Refactoring Rationale: the endpoint's three sizing values are derived from the annotation by the same
     * rule the framework applies -- resolve the attribute as a placeholder against the same property sources,
     * then parse it if it has text and contribute {@code null} if it does not -- rather than being hard-coded
     * as absent. That is the whole point of the case. If either sizing attribute is ever restored to the
     * annotation, this test resolves it, the endpoint carries it, the factory applies it through
     * {@code ConfigUtils.acceptIfNotNull}, and the resolved option stops matching the profile, so the
     * regression that made A-5 possible fails here instead of reaching a deployment. Verified by construction
     * against the pinned {@code spring-cloud-aws-sqs 4.1.0}: reinstating
     * {@code maxConcurrentMessages = "${...:10}"} yields a resolved concurrency of ten against the
     * development profile's declared two.</p>
     *
     * <p>Assumptions: an SQS client is required to build a container but is never called, because nothing here
     * starts the container -- {@code createContainer} configures and returns it, and only {@code start} would
     * poll. A mock therefore stands in with no stubbing at all, which is why this case does not need a queue,
     * an emulator or a network.</p>
     *
     * <p>⚠️ Assumptions: the endpoint's queue name is a literal rather than the resolved placeholder,
     * and that is deliberate. This note used to say that {@code carddemo.account.inquiry.request-queue}
     * resolves to {@code ${CARDDEMO_ACCOUNT_INQUIRY_REQUEST_QUEUE_URL}}; it did not resolve to anything,
     * because no profile declared that key -- the declared one is {@code request-queue-url}, which the
     * annotation now names. Using a literal here is what let the mismatch survive, so the sibling case
     * {@code theListenerBindsADeclaredDestination} asserts the annotation's placeholder against the declared
     * keys instead. The destination still has no bearing on container sizing.
     * Alternatives Considered: setting the variable for this case, which was rejected because it would make a
     * sizing assertion depend on a value it does not read.</p>
     *
     * <p>Assumptions: both profiles are exercised rather than development alone, because the failure this case
     * exists to catch is an override that applies to EVERY profile. A case that asserted two under development
     * only would pass just as happily if production's ten were also an annotation default, and production's
     * ten is the number that is paid for in database connections against a pool of twenty.</p>
     *
     * <p>Trade-offs: this reproduces the autoconfiguration's three-line contribution rather than invoking it,
     * because {@code SqsAutoConfiguration.configureProperties} is private and the alternative -- standing up a
     * context with the AWS autoconfiguration active -- would require a region and would construct a real
     * client. What is accepted is that a fourth listener property added to the autoconfiguration would not be
     * covered here; what is bought is that the three properties this module declares are asserted as the
     * container receives them, with no emulator and no context refresh.</p>
     *
     * @param profile the Spring profile whose document is layered over the base, as a resource name component
     * @param expectedConcurrency the concurrent-message ceiling that profile must resolve to
     * @param expectedBatch the messages-per-poll batch that profile must resolve to
     * @param expectedPollSeconds the long-poll wait in seconds that profile must resolve to
     * @throws NoSuchMethodException if the handler method cannot be found, which would mean the contract this
     *     case reads has been renamed rather than that the assertion failed
     */
    @ParameterizedTest(name = "the {0} profile resolves {1} concurrent, {2} per poll, {3}s wait")
    @CsvSource({"dev, 2, 2, 5", "prod, 10, 10, 5"})
    @DisplayName("the resolved container options are the profile's own")
    void theResolvedContainerOptionsAreTheProfilesOwn(String profile, int expectedConcurrency,
            int expectedBatch, int expectedPollSeconds) throws NoSuchMethodException {
        MutablePropertySources sources = profileSources(profile);
        SqsProperties properties = new Binder(ConfigurationPropertySources.from(sources))
                .bindOrCreate(SqsProperties.PREFIX, SqsProperties.class);
        PropertyResolver resolver = new PropertySourcesPropertyResolver(sources);

        SqsContainerOptions resolved = containerFor(properties, resolver);

        assertThat(properties.getListener().getMaxConcurrentMessages())
                .as("the %s profile must declare its own concurrency ceiling", profile)
                .isEqualTo(expectedConcurrency);
        assertThat(resolved.getMaxConcurrentMessages())
                .as("the container must run at the %s profile's ceiling, not an annotation default", profile)
                .isEqualTo(expectedConcurrency);
        assertThat(resolved.getMaxMessagesPerPoll())
                .as("the container must poll the %s profile's batch, not an annotation default", profile)
                .isEqualTo(expectedBatch);
        assertThat(resolved.getPollTimeout())
                .as("the wait must be COACCT01 L337's 5000 milliseconds in every profile")
                .isEqualTo(Duration.ofSeconds(expectedPollSeconds));
        assertThat(expectedPollSeconds)
                .as("the profiles may not vary the transcribed wait")
                .isEqualTo(BASELINE_POLL_WAIT_SECONDS);
    }

    /**
     * Reads the consumer's handler method.
     *
     * @return the method the container dispatches to; never {@code null}
     * @throws NoSuchMethodException if the handler method has been renamed
     */
    private static Method handlerMethod() throws NoSuchMethodException {
        return InquiryMessageListener.class.getMethod("onRequest", Message.class);
    }

    /**
     * Reads the handler's queue-binding annotation.
     *
     * @return the annotation declared on the consumer's handler method; never {@code null} in a green build
     * @throws NoSuchMethodException if the handler method has been renamed
     */
    private static SqsListener handlerAnnotation() throws NoSuchMethodException {
        return handlerMethod().getAnnotation(SqsListener.class);
    }

    /**
     * The handler's destination placeholder names a key this module actually declares.
     *
     * <p>⚠️ Purpose: this case exists because the annotation named
     * {@code carddemo.account.inquiry.request-queue} and no profile declared it. The one key this module
     * publishes is {@code request-queue-url}, so the placeholder was unresolvable, registering the endpoint
     * would have failed the context refresh, and the service could not have started with inquiry consumption
     * enabled. Nothing caught it: the two sibling cases that read this annotation assert its SIZING
     * attributes and deliberately never resolve its destination, and the one that mentions the destination
     * did so in prose that asserted nothing.</p>
     *
     * <p>Assumptions: the placeholder is RESOLVED against this module's own configuration rather than
     * compared to a literal key name. A literal would restate the annotation and pass whenever the two were
     * edited together -- which is how the mismatch arose -- whereas resolution fails unless the key exists in
     * the documents the running service loads.</p>
     *
     * <p>Assumptions: declaration is tested with {@code containsProperty} rather than by resolving the
     * placeholder, and the difference is what makes the case work at all. The declared value is itself a
     * reference to a deployment-supplied environment variable that this case runs without, so
     * {@code resolveRequiredPlaceholders} raises for BOTH the defect and the design -- once for the
     * undeclared outer key and once for the absent inner variable -- and cannot tell them apart.
     * {@code containsProperty} consults the documents without resolving anything, so it answers exactly the
     * question asked: is the key the annotation names one this module declares. Reading its raw value
     * additionally pins that the location stays in the deployment's hands, which needs the nested-placeholder
     * flag set because {@code getProperty} would otherwise resolve the inner reference and raise.</p>
     *
     * <p>Assumptions: both profiles are exercised, because a key declared in only one would start one
     * environment and fail the other, and the base document is where this key belongs.</p>
     *
     * @param profile the profile whose document is layered over the base, as a resource name component
     * @throws NoSuchMethodException if the handler method cannot be found, which would mean the contract this
     *     case reads has been renamed rather than that the assertion failed
     */
    @ParameterizedTest(name = "the {0} profile declares the handler's destination key")
    @CsvSource({"dev", "prod"})
    @DisplayName("the handler is bound to a destination the configuration declares")
    void theListenerBindsADeclaredDestination(String profile) throws NoSuchMethodException {
        PropertySourcesPropertyResolver resolver =
                new PropertySourcesPropertyResolver(profileSources(profile));
        resolver.setIgnoreUnresolvableNestedPlaceholders(true);
        String placeholder = handlerAnnotation().queueNames()[0];

        assertThat(placeholder)
                .as("the destination must stay a placeholder so no location is committed in source")
                .startsWith("${")
                .endsWith("}");
        String key = placeholder.substring(2, placeholder.length() - 1);
        assertThat(resolver.containsProperty(key))
                .as("the %s profile must declare '%s', the key the handler's placeholder names", profile, key)
                .isTrue();
        assertThat(resolver.getProperty(key))
                .as("the declared key must carry the deployment variable, not a committed location")
                .isEqualTo("${CARDDEMO_ACCOUNT_INQUIRY_REQUEST_QUEUE_URL}");
        assertThat(key)
                .as("the destination is a queue URL on every path, so its key must say so")
                .isEqualTo("carddemo.account.inquiry.request-queue-url");
    }

    /**
     * Loads this module's own base configuration with one profile document layered over it.
     *
     * <p>Assumptions: the profile document is added FIRST and the base second, because a
     * {@code MutablePropertySources} resolves in order and the earlier source wins -- which is the precedence
     * Spring Boot itself gives a profile document over the base one.</p>
     *
     * @param profile the profile whose document to layer over the base, as a resource name component
     * @return the two documents as ordered property sources; never {@code null}
     * @throws UncheckedIOException if either document cannot be read, which fails rather than skips because an
     *     unreadable configuration file is the failure this case is looking for
     */
    private static MutablePropertySources profileSources(String profile) {
        MutablePropertySources sources = new MutablePropertySources();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String resource : List.of("application-" + profile + ".yml", "application.yml")) {
            try {
                for (PropertySource<?> loaded : loader.load(resource, new ClassPathResource(resource))) {
                    sources.addLast(loaded);
                }
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot read " + resource, unreadable);
            }
        }
        return sources;
    }

    /**
     * Builds a real listener container the way the framework's own autoconfiguration builds it.
     *
     * <p>Assumptions: the endpoint is bound to the consumer's REAL handler method, because the method's
     * parameter shape is what decides the container's listener mode -- a {@code Message<String>} parameter is
     * a single-message listener, and a {@code List} parameter would be a batch one. The bean beside it is a
     * bare object because nothing invokes the method: {@code createContainer} builds the invocable handler and
     * returns, and only starting the container would dispatch to it.</p>
     *
     * @param properties the listener properties bound from the profile documents; must not be {@code null}
     * @param resolver the resolver over the same documents, used to expand any placeholder an attribute holds
     * @return the options the constructed container actually carries; never {@code null}
     * @throws NoSuchMethodException if the handler method has been renamed
     */
    private static SqsContainerOptions containerFor(SqsProperties properties, PropertyResolver resolver)
            throws NoSuchMethodException {
        SqsListener annotation = handlerAnnotation();
        SqsMessageListenerContainerFactory<Object> factory = SqsMessageListenerContainerFactory
                .<Object>builder()
                .sqsAsyncClient(mock(SqsAsyncClient.class))
                .messageListener(received -> { })
                .configure(options -> {
                    options.maxConcurrentMessages(properties.getListener().getMaxConcurrentMessages());
                    options.maxMessagesPerPoll(properties.getListener().getMaxMessagesPerPoll());
                    options.pollTimeout(properties.getListener().getPollTimeout());
                })
                .build();
        SqsEndpoint endpoint = SqsEndpoint.builder()
                .queueNames(List.of("carddemo-inquiry-request-under-test"))
                .id("account-inquiry-under-test")
                .maxConcurrentMessages(attributeAsInteger(annotation.maxConcurrentMessages(), resolver))
                .maxMessagesPerPoll(attributeAsInteger(annotation.maxMessagesPerPoll(), resolver))
                .pollTimeoutSeconds(attributeAsInteger(annotation.pollTimeoutSeconds(), resolver))
                .build();
        endpoint.setBean(new Object());
        endpoint.setMethod(handlerMethod());
        DefaultMessageHandlerMethodFactory handlerMethods = new DefaultMessageHandlerMethodFactory();
        handlerMethods.afterPropertiesSet();
        endpoint.setHandlerMethodFactory(handlerMethods);
        return factory.createContainer(endpoint).getContainerOptions();
    }

    /**
     * Resolves one annotation attribute to the integer the framework would contribute for it.
     *
     * <p>Assumptions: this reproduces {@code AbstractListenerAnnotationBeanPostProcessor.resolveAsInteger}
     * from the pinned {@code spring-cloud-aws-sqs 4.1.0} -- an attribute with no text contributes
     * {@code null}, which the factory then skips through {@code ConfigUtils.acceptIfNotNull}. Reproducing the
     * rule rather than assuming absence is what lets a restored attribute fail this case.</p>
     *
     * @param attribute the raw attribute value, which may be empty or may hold a property placeholder
     * @param resolver the resolver over the profile documents; must not be {@code null}
     * @return the integer the endpoint should carry, or {@code null} when the attribute contributes nothing
     * @throws NumberFormatException if the attribute resolves to text that is not an integer, which would mean
     *     an unresolvable placeholder rather than a failed assertion
     */
    private static Integer attributeAsInteger(String attribute, PropertyResolver resolver) {
        String resolved = resolver.resolvePlaceholders(attribute);
        return StringUtils.hasText(resolved) ? Integer.valueOf(resolved.trim()) : null;
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

    // WHY : Refactoring Rationale: every case above this point drives the FIRST-DELIVERY path, because
    //   the shared fixture answers the claim with true and no case overrode it. The three outcomes a
    //   REFUSED claim selects between -- suppress a duplicate, re-send an outstanding reply, or fail
    //   because the row the conflict implies is not there -- were therefore unexercised, and they are
    //   the whole reason the ledger exists: they are what a redelivery meets. A consumer that answered
    //   a redelivery from the reply it had just recomposed, or that treated a missing row as a first
    //   delivery, would have passed this class as it stood while sending a second, different answer
    //   under one correlation identifier.
    // WHY : Assumptions: the three cases substitute the ledger's own answers rather than standing up a
    //   database, because what is under test is the CONSUMER's branch on those answers. The durable
    //   behaviour of the claim itself -- that exactly one of two concurrent inserts succeeds -- belongs
    //   to the repository's own integration test against a real engine and is neither repeated nor
    //   contradicted here.

    /**
     * Builds a recorded reply as an earlier delivery would have left it in the ledger.
     *
     * @param status the recorded status, either the sent or the pending sentinel the ledger stores
     * @param payload the reply bytes the earlier delivery recorded
     * @param destination the reply destination the earlier delivery recorded, which is the RESOLVED
     *     queue address rather than a queue name, because the consumer resolves the address before it
     *     records the claim and re-sends whatever it recorded
     * @return the recorded row, never {@code null}
     */
    private static InquiryReplyLedger.RecordedReply recorded(String status, String payload,
            String destination) {

        return new InquiryReplyLedger.RecordedReply(status, payload, destination,
                "recorded-correlation", "recorded-message-id");
    }

    /**
     * Verifies a redelivery whose recorded reply was already sent is dropped rather than answered twice.
     *
     * <p>Purpose: this is the outcome the ledger exists for. The requester already has its answer, so the
     * duplicate is suppressed and the request is still acknowledged -- raising instead would redeliver a
     * request that was correctly answered until it dead-lettered.</p>
     *
     * <p>Assumptions: the assertion is that NO send occurs at all, and it is stated as zero rather than as
     * "not the same body", because a consumer that re-sent an identical body would satisfy a body
     * comparison while putting a second copy of one answer on the queue.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a redelivery of an already-sent reply is suppressed without a second send")
    void anAlreadySentReplyIsSuppressed() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(this.ledger.claim(anyString(), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class))).thenReturn(false);
        when(this.ledger.find(BROKER_MESSAGE_ID)).thenReturn(Optional.of(
                recorded(InquiryReplyLedger.STATUS_SENT, "recorded reply body", REPLY_URL)));

        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of(
                InquiryMessageListener.HEADER_BROKER_MESSAGE_ID, BROKER_MESSAGE_ID)));

        verify(this.sqs, never()).sendMessage(any(SendMessageRequest.class));

        // WHY : Assumptions: the claim is not retired again either. The earlier delivery already
        //   retired it, so a second mark would be a write with nothing to change, and asserting its
        //   absence is what distinguishes suppression from a re-send whose send happened to fail.
        verify(this.ledger, never()).markSent(anyString(), any(LocalDateTime.class));
        assertThat(this.captured.list)
                .as("the suppression is recorded, so an operator can tell it from a lost request")
                .anyMatch(event -> event.getFormattedMessage()
                        .contains("event=account.inquiry.duplicate-suppressed"));
    }


    /**
     * Verifies a conflict with no recorded row fails rather than being answered as a first delivery.
     *
     * <p>Purpose: the claim reported that a row already holds the key, so a read finding none means the
     * row was removed underneath this delivery. Answering anyway would send a reply this consumer can no
     * longer record, so the failure propagates and the request becomes visible again instead of being
     * acknowledged with an unrecorded answer out on the queue.</p>
     *
     * <p>Assumptions: the assertion is that nothing was sent BEFORE the failure, not merely that a
     * failure was raised. A consumer that sent first and then discovered the missing row would raise the
     * same exception while having already published the answer it could not record.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a claim conflict with no recorded row fails and sends nothing")
    void aClaimConflictWithNoRecordedRowFails() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(this.ledger.claim(anyString(), anyString(), anyString(), any(), any(),
                any(LocalDateTime.class))).thenReturn(false);
        when(this.ledger.find(BROKER_MESSAGE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.listener.onRequest(message(request("INQA", "12345678901"),
                Map.of(InquiryMessageListener.HEADER_BROKER_MESSAGE_ID, BROKER_MESSAGE_ID))))
                .isInstanceOf(IllegalStateException.class);

        // WHY : Assumptions: the one send this case permits is the DIAGNOSTIC to the error sink, which
        //   the consumer emits for any unexpected failure before letting it propagate, and the
        //   assertion is written as "nothing reached the reply queue" rather than "nothing was sent" so
        //   that the operational report is not mistaken for the answer. The distinction is the whole
        //   point of the case: an answer this consumer can no longer record must not reach the
        //   requester, while the failure must still be visible to an operator.
        SendMessageRequest reported = captureSend();
        assertThat(reported.queueUrl())
                .as("the only send is the diagnostic, and it goes to the error sink")
                .isEqualTo(ERROR_URL);
        verify(this.ledger, never()).markSent(anyString(), any(LocalDateTime.class));
    }

    /**
     * Verifies a claim retired by a concurrent delivery is reported and does not fail the exchange.
     *
     * <p>Purpose: the mark is not required to succeed for the exchange to be complete. A concurrent
     * delivery may have retired the claim between this one's insert and its mark, in which case THIS
     * send was the duplicate; the request is still acknowledged, because the requester has its answer.
     * Raising here would redeliver a request the requester has already been answered twice for.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a claim already retired by a concurrent delivery is reported, not raised")
    void aClaimAlreadyRetiredIsReportedNotRaised() {
        when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(this.ledger.markSent(anyString(), any(LocalDateTime.class))).thenReturn(false);

        this.listener.onRequest(message(request("INQA", "12345678901"), Map.of(
                InquiryMessageListener.HEADER_BROKER_MESSAGE_ID, BROKER_MESSAGE_ID)));

        verify(this.sqs, times(1)).sendMessage(any(SendMessageRequest.class));
        assertThat(this.captured.list)
                .as("the lost race is recorded, because it means one answer went out twice")
                .anyMatch(event -> event.getFormattedMessage()
                        .contains("event=account.inquiry.claim-already-retired"));
    }
}
