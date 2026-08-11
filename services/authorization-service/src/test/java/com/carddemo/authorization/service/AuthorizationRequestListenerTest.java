package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.domain.OutboxMessage;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.mapper.AuthorizationMessageMapper;
import com.carddemo.authorization.repository.OutboxRepository;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.codec.CsvAuthCodec.AuthMessageFormatException;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import com.carddemo.common.messaging.MessagingCorrelationId;
import com.carddemo.common.money.Money;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * Pins the behaviour surrounding the decision: the reads, the key, the refusals and the reply routing.
 *
 * <p>The subject is {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} paragraphs
 * {@code 5000-PROCESS-AUTH} at lines 438 to 468, {@code 8400-UPDATE-SUMMARY} at lines 798 to 850 and
 * {@code 8500-INSERT-AUTH} at lines 855 onward. Five properties are asserted here that the earlier
 * revision of this listener did not have: the three account-context reads happen in the reference order
 * and only when the cross-reference resolved; a summary is created when the account has none; the
 * recorded row is keyed from the server's clock rather than from the request; a negative amount is
 * refused before any lookup; and a reply goes only to an allowlisted destination.</p>
 *
 * <p>Assumptions: every collaborator is a mock and the clock is fixed, so no container, database or queue
 * is involved and the expected key is an arithmetic consequence of the fixed instant rather than of
 * whenever the test ran.</p>
 */
class AuthorizationRequestListenerTest {

    /**
     * The instant every case runs at: the fifth of August 2026 at 10:45:30.123 in coordinated universal
     * time.
     *
     * <p>Assumptions: the date is chosen in a NON-leap year and late enough in it that the day-of-year is
     * three digits, because a two-digit day of year would leave the ordinal-date arithmetic passing even
     * if the year multiplier were wrong.</p>
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T10:45:30.123Z");

    /**
     * The five-digit ordinal date the fixed instant yields: year 26, day 217.
     */
    private static final int EXPECTED_ORDINAL_DATE = 26_217;

    /**
     * The nine-digit time the fixed instant yields: 10:45:30 and 123 milliseconds.
     */
    private static final int EXPECTED_TIME_OF_DAY = 104_530_123;

    /**
     * The one reply destination the listener under test is configured to accept.
     */
    private static final String ALLOWED_REPLY_QUEUE =
            "https://sqs.us-east-1.amazonaws.com/000000000000/carddemo-pauth-reply-dev.fifo";

    /**
     * The card the fixture requests authorize.
     */
    private static final String CARD_NUM = "4111111111111111";

    /**
     * The transaction identifier the fixture requests carry.
     */
    private static final String TRANSACTION_ID = "TXN000000000001";

    /**
     * The account the fixture's cross-reference resolves to.
     */
    private static final long ACCOUNT_ID = 11_111_111_111L;

    /**
     * The customer the fixture's cross-reference resolves to.
     */
    private static final long CUSTOMER_ID = 999_999_999L;

    /**
     * The window size these tests configure, small enough to close several windows cheaply.
     *
     * <p>Assumptions: three rather than the production default of 500. The bound under test is the
     * ARITHMETIC of when a window closes -- exactly on the quota rather than one past it -- and that
     * arithmetic is identical at three and at 500, so driving 500 messages through a mock stack would cost
     * time without testing anything the smaller number does not.</p>
     */
    private static final int WINDOW_LIMIT = 3;

    /**
     * How many admissions the configured window above actually grants.
     *
     * <p>Assumptions: derived from the consumer's own offset constant rather than written as four, so a
     * case asserting the boundary asserts the relationship the production code implements rather than a
     * number copied from it. The offset is the reference program's increment-then-compare artifact: it
     * issues one get past its declared limit before its loop ends.</p>
     */
    private static final int WINDOW_ADMISSIONS =
            WINDOW_LIMIT + AuthorizationRequestListener.BASELINE_COMPARISON_OFFSET;

    /**
     * The validation engine the payload crossing applies.
     *
     * <p>Assumptions: the default provider's engine is built once and shared, because it is stateless
     * and immutable and building one per test costs more than every assertion here put together. It is a
     * REAL engine rather than a stub for the same reason the decision service is real: what these tests
     * assert is that the consumer routes a decoded message through the declared contract, and a stub
     * engine would make that assertion vacuous.</p>
     */
    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    /** The summary repository mock. */
    private PendingAuthSummaryRepository summaries;

    /** The detail repository mock. */
    private PendingAuthDetailRepository details;

    /** The outbox repository mock. */
    private OutboxRepository outbox;

    /** The account-context seam mock. */
    private AccountContextClient accounts;

    /** The listener under test. */
    private AuthorizationRequestListener listener;

    /**
     * The window sizes recorded by the boundary seam, one entry per window this test run closed.
     *
     * <p>Assumptions: the recorded value is the handled count the listener reports, not merely the fact
     * that a boundary fired, so a window that closed one message early or one message late is
     * distinguishable from one that closed exactly on its quota.</p>
     */
    private List<Integer> closedWindows;

    /** The generation each observed window boundary reported, in the order the boundaries fired. */
    private List<Long> closedGenerations;

    /**
     * Builds a listener over fresh mocks, a real decision service and the fixed clock.
     *
     * <p>Assumptions: the decision service is the REAL one rather than a mock, because the assertions
     * here are about what the listener does with a decision and a stubbed decision would let the two
     * drift apart. Only the collaborators that reach outside the process are mocked.</p>
     */
    @BeforeEach
    void setUp() {
        this.summaries = mock(PendingAuthSummaryRepository.class);
        this.details = mock(PendingAuthDetailRepository.class);
        this.outbox = mock(OutboxRepository.class);
        this.accounts = mock(AccountContextClient.class);
        this.closedWindows = new ArrayList<>();
        this.closedGenerations = new ArrayList<>();
        // WHY : Assumptions: the three row-count-returning summary operations are stubbed to their
        //       SUCCESS value here, because a mock's default for an int is zero and zero is the value
        //       each of them uses to report that it changed nothing. Left unstubbed, the duplicate-
        //       tolerant insert reports a conflict that did not happen and each atomic contribution
        //       reports a row that was not there, so every case would fail on the listener's own
        //       consistency check rather than on the property it set out to assert. Stubbing success in
        //       the shared fixture makes "the repository works" the baseline condition and leaves each
        //       case that wants a failing write to say so, which is what the conflict and vanished-row
        //       cases below do.
        // WHY : Trade-offs: this couples the fixture to those three return contracts, so a repository
        //       that stopped reporting one row per contribution would keep these unit cases green. That is
        //       accepted because the contracts are pinned where they are actually exercised --
        //       PendingAuthSummaryRepositoryIT runs them against a real database -- and duplicating that
        //       assertion here would test the mock rather than the repository.
        givenWorkingSummaryWrites();
        // WHY : Refactoring Rationale: the window boundary arrives as a lambda rather than as the
        //       production container-cycling implementation. The bound is what these tests assert, and the
        //       mechanism that acts on it needs a listener container registry and a live queue; separating
        //       the two is what makes the bound assertable at all, and it is why the seam is an interface.
        this.listener = new AuthorizationRequestListener(this.summaries, this.details, this.outbox,
                new AuthorizationDecisionService(),
                new AuthorizationMessageMapper(VALIDATOR), this.accounts,
                List.of(ALLOWED_REPLY_QUEUE),
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), WINDOW_LIMIT,
                this::recordClosedWindow);
    }

    /**
     * Builds a listener whose window size is the value supplied, over the same mocks.
     *
     * @param limit the window size to configure
     * @return a listener bound to the shared mocks and the shared recording boundary
     */
    private AuthorizationRequestListener listenerWithWindow(int limit) {
        return new AuthorizationRequestListener(this.summaries, this.details, this.outbox,
                new AuthorizationDecisionService(),
                new AuthorizationMessageMapper(VALIDATOR), this.accounts,
                List.of(ALLOWED_REPLY_QUEUE),
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), limit,
                this::recordClosedWindow);
    }

    /**
     * A negative amount is refused before any lookup, mutation or reply.
     *
     * <p>Assumptions: the refusal is asserted together with the ABSENCE of every side effect, because the
     * defect this closes was not a missing check but a wrong outcome -- the amount was approved and then
     * added to the account's reserved balance, releasing credit. A test that only asserted the exception
     * would still pass if the check ran after the account read had taken a row lock.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a negative transaction amount is refused before any lookup or mutation")
    void aNegativeAmountIsRefusedBeforeAnyLookup() {
        Message<String> message = messageFor(requestFor(Money.of("-100.99")), ALLOWED_REPLY_QUEUE);

        assertThrows(AuthMessageFormatException.class, () -> this.listener.onRequest(message));

        verifyNoInteractions(this.accounts);
        verifyNoInteractions(this.summaries);
        verifyNoInteractions(this.outbox);
        verifyNoInteractions(this.details);
    }

    /**
     * The card is resolved through the account context, and the recorded row is keyed from the clock.
     *
     * <p>Assumptions: both halves are asserted in one case because they are two observations of one
     * successful path, and separating them would need the same seven stubs twice. The key assertion is the
     * migrated form of lines 868 to 875, which read the platform clock. The request's own date and time
     * remain separate: they are passed into the two originating columns, which lines 877 and 878 populate
     * from the request, and the entity publishes no accessor for either -- so this case asserts the KEY,
     * which is the value that changed, and the originating columns are covered where the entity's own
     * mapping is exercised against a database.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the row key comes from the server clock while the request's own time is kept apart")
    void theRowKeyComesFromTheServerClock() {
        AuthRequest request = requestFor(Money.of("100.99"));
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(messageFor(request, ALLOWED_REPLY_QUEUE));

        verify(this.accounts).findCardXref(CARD_NUM);
        verify(this.accounts).findAccount(ACCOUNT_ID);
        verify(this.accounts).customerExists(CUSTOMER_ID);

        ArgumentCaptor<PendingAuthDetail> saved = ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details).save(saved.capture());
        assertEquals(ACCOUNT_ID, saved.getValue().getId().getAccountId());
        assertEquals(EXPECTED_ORDINAL_DATE, saved.getValue().getId().getAuthDate());
        assertEquals(EXPECTED_TIME_OF_DAY, saved.getValue().getId().getAuthTime());
    }

    /**
     * An approved authorization is recorded as pending a match against a posted transaction.
     *
     * <p>Assumptions: this is the {@code IF} branch of lines 902 to 906, which selects
     * {@code PA-MATCH-PENDING} when the reply carries the approval response code. The stored status is
     * asserted alongside the response code, because the two are set from one predicate in the source
     * and a row carrying one without the other is a combination the reference system never writes.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an approved authorization is recorded with the pending match status")
    void anApprovedAuthorizationIsRecordedAsPending() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthDetail> saved = ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details).save(saved.capture());
        assertEquals(PendingAuthDetail.MATCH_STATUS_PENDING, saved.getValue().getMatchStatus());
        assertEquals("00", saved.getValue().getAuthRespCode());
    }

    /**
     * A declined authorization is recorded as declined and never as pending.
     *
     * <p>Assumptions: this is the {@code ELSE} branch of lines 902 to 906, reached here by requesting
     * more than the summary's remaining room so that the decision refuses it -- the insufficient-funds
     * path at lines 665 to 671. The assertion matters because the two statuses are both valid values of
     * the same column, so recording a decline as pending fails no constraint: it silently moves the row
     * into the population the expiry sweep walks and the pending list renders. The declined counter is
     * asserted beside it, so a row and its summary cannot disagree about the outcome.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a declined authorization is recorded with the declined match status")
    void aDeclinedAuthorizationIsRecordedAsDeclined() {
        givenResolvableCard();
        PendingAuthSummary exhausted = new PendingAuthSummary(ACCOUNT_ID, CUSTOMER_ID);
        exhausted.refreshLimits(new BigDecimal("100.00"), new BigDecimal("50.00"));
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(exhausted));

        this.listener.onRequest(messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthDetail> saved = ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details).save(saved.capture());
        assertEquals(PendingAuthDetail.MATCH_STATUS_DECLINED, saved.getValue().getMatchStatus());
        assertEquals("05", saved.getValue().getAuthRespCode());
        assertEquals(0, BigDecimal.ZERO.compareTo(saved.getValue().getApprovedAmount()));

        // WHY : Refactoring Rationale: the declined pair used to be asserted on the entity handed to
        //       save(...), because the listener read the summary, mutated it and saved it back. The
        //       accumulation is now an ATOMIC statement -- the arithmetic happens in the database, so no
        //       in-memory counter carries the result and there is nothing on the saved entity to read. The
        //       assertion moves to the statement, which is where the contribution now lives.
        // WHY : Assumptions: asserting that the APPROVED statement was not called is as important as
        //       asserting the declined one was. The two arms write different columns, and a decline that
        //       advanced the approved total would overstate the credit an account has committed -- which
        //       is exactly the confusion the two separate statements exist to prevent.
        verify(this.summaries).addDeclinedAuthorization(ACCOUNT_ID, new BigDecimal("100.99"));
        verify(this.summaries, never()).addApprovedAuthorization(anyLong(), any(BigDecimal.class));
    }

    /**
     * No account, customer or summary read happens when the cross-reference does not resolve.
     *
     * <p>Assumptions: this is the conditionality of lines 450 to 456 and the write guard at line 463. An
     * unresolved card costs one call, records nothing in either table, and is still answered -- with the
     * not-found reason, which the reply payload carries.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unresolved card reads nothing further and records nothing")
    void anUnresolvedCardReadsNothingFurther() {
        when(this.accounts.findCardXref(CARD_NUM)).thenReturn(Optional.empty());

        this.listener.onRequest(messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE));

        verify(this.accounts).findCardXref(CARD_NUM);
        verify(this.accounts, never()).findAccount(anyLong());
        verify(this.accounts, never()).customerExists(anyLong());
        verify(this.summaries, never()).findByAccountId(anyLong());
        verify(this.details, never()).save(any(PendingAuthDetail.class));
        verify(this.summaries, never()).save(any(PendingAuthSummary.class));

        // WHY : Refactoring Rationale: this assertion has been moved twice and this revision is the one
        //       the specification mandates. It first required the group identity to equal the card
        //       number, was then changed to require a keyed token of it, and now requires the card number
        //       again -- because specification section 0.4.1.8 states the identity literally,
        //       "MessageGroupId = card_num preserves per-card ordering", and section 0.7.6 repeats it.
        //       The token was not a free improvement: a group identity has to be EQUAL for equal cards
        //       across every producer on the queue, and a value keyed from this service's own secret is
        //       one only this service can compute, so any other producer's replies for the same card land
        //       in a different group and the per-card ordering guarantee silently stops holding.
        // WHY : Trade-offs: the card number therefore appears in queue metadata, which is the exposure
        //       the token was reaching for. It is accepted here and registered as divergence
        //       D-AUTHORIZATION-FIFO-IDENTITY-METADATA in
        //       docs/architecture/cobol-to-service-traceability.md, bounded by the three controls the
        //       queue already carries: server-side encryption under a customer-managed key, reachability
        //       only through a private-network interface endpoint, and read access scoped to the task
        //       roles of this service and of the requesting producer.
        ArgumentCaptor<AuthReplyOutbox> reply = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox).save(reply.capture());
        assertEquals(CARD_NUM, reply.getValue().getOrderGroupId());
    }

    /**
     * The first authorization for an account creates its summary with the account's limits.
     *
     * <p>Assumptions: this is lines 801 to 818 -- initialise, set the two identifiers, refresh the two
     * limits from the account master, then record the approval. The counters are asserted as well as the
     * limits, because the defect this closes lost them entirely: with no summary row created, an
     * account's approved and declined totals stayed absent until an extract load happened to supply
     * one.</p>
     *
     * <p>Refactoring Rationale: the creation is asserted through the DUPLICATE-TOLERANT insert rather
     * than through a plain save, because that is what the listener now issues. A save on an assigned key
     * is a merge, so it reads the row and then writes every column of the instance in hand -- and a
     * concurrent authorization for a different card of the same account, which the queue's per-card
     * ordering permits, would have its counters overwritten by whatever this instance was built from. The
     * insert reports a conflict instead of overwriting, which is why the following case can then assert
     * the additive fall-through.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a missing summary is created with the account's limits and the decision's counters")
    void aMissingSummaryIsCreated() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        this.listener.onRequest(messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthSummary> saved = ArgumentCaptor.forClass(PendingAuthSummary.class);
        verify(this.summaries).insertSummaryIfAbsent(saved.capture());
        verify(this.summaries, never()).save(any(PendingAuthSummary.class));
        assertEquals(ACCOUNT_ID, saved.getValue().getAccountId());
        assertEquals(CUSTOMER_ID, saved.getValue().getCustomerId());
        assertEquals(0, new BigDecimal("5000.00").compareTo(saved.getValue().getCreditLimit()));
        assertEquals(0, new BigDecimal("500.00").compareTo(saved.getValue().getCashLimit()));
        assertEquals(1, saved.getValue().getApprovedAuthCount().intValue());
        assertEquals(0, saved.getValue().getDeclinedAuthCount().intValue());
        assertEquals(0, new BigDecimal("100.99").compareTo(saved.getValue().getApprovedAuthAmount()));
        assertEquals(0, new BigDecimal("100.99").compareTo(saved.getValue().getCreditBalance()));
    }

    /**
     * A correlation identity carrying a control character REFUSES the message.
     *
     * <p>Refactoring Rationale: this test previously asserted that a nonconforming attribute was DROPPED
     * and the request decided anyway, using a value made of ordinary punctuation. Both halves changed
     * with the rule. The messaging rule admits every printable character, so punctuation is now canonical
     * and is echoed -- see the test below -- and what remains non-canonical is a control character or an
     * over-long value, neither of which a legitimate requester sends. Such a message is refused so the
     * queue redelivers and then dead-letters it, which leaves evidence, where dropping the attribute left
     * the requester holding a correlation value that never came back and nothing to explain why.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a correlation identity carrying a control character refuses the message")
    void aNonCanonicalCorrelationAttributeRefusesTheMessage() {
        Message<String> message = wireMessage(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .setHeader(AuthorizationRequestListener.HEADER_CORRELATION_ID, "a\nlevel=ERROR")
                .build();

        assertThrows(AuthMessageFormatException.class, () -> this.listener.onRequest(message));

        verifyNoInteractions(this.outbox);
        verify(this.details, never()).save(any(PendingAuthDetail.class));
    }

    /**
     * A correlation identity the servlet rule would have refused is carried through unaltered.
     *
     * <p>Assumptions: the value is forty-eight hexadecimal characters, which is how a requester renders
     * the baseline's twenty-four BYTE correlation field. It is twice the servlet rule's own width bound,
     * so the borrowed predicate refused it and the consumer answered with a reply carrying no correlation
     * attribute -- leaving the requester unable to pair the answer with its question. Asserting this exact
     * shape is what pins the regression rather than merely testing that some value survives.</p>
     *
     * <p>Assumptions: the punctuation case is asserted alongside it, because that is where the echo and
     * the LOG rendering deliberately differ: the outbox row carries the requester's bytes verbatim while
     * the logged rendering replaces the characters that could forge a log record.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a rendering of the baseline's 24-byte correlation field is echoed verbatim")
    void aWideCorrelationAttributeIsEchoedVerbatim() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));
        String twentyFourBytesAsHex = "0123456789abcdef0123456789abcdef0123456789abcdef";
        Message<String> message = wireMessage(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .setHeader(AuthorizationRequestListener.HEADER_CORRELATION_ID, twentyFourBytesAsHex)
                .build();

        this.listener.onRequest(message);

        ArgumentCaptor<AuthReplyOutbox> reply = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox).save(reply.capture());
        assertEquals(twentyFourBytesAsHex, reply.getValue().getCorrelationId());
        verify(this.details).save(any(PendingAuthDetail.class));
    }

    /**
     * A punctuation-bearing identity is echoed verbatim while its logged rendering is neutralised.
     *
     * <p>Assumptions: the value is a structured-log injection attempt. The echo must be exact because the
     * requester correlates on its own bytes, and the log rendering must not be, because a quotation mark
     * in a structured log field forges a record. Asserting both in one test is what stops a later change
     * collapsing the two renderings into one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a log-injection attempt is echoed verbatim but rendered safely for the log")
    void anInjectionAttemptIsEchoedVerbatimAndLoggedSafely() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));
        String injection = "a\",\"level\":\"ERROR";
        Message<String> message = wireMessage(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .setHeader(AuthorizationRequestListener.HEADER_CORRELATION_ID, injection)
                .build();

        this.listener.onRequest(message);

        ArgumentCaptor<AuthReplyOutbox> reply = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox).save(reply.capture());
        assertEquals(injection, reply.getValue().getCorrelationId());
        // WHY : Assumptions: the expected rendering keeps the colon and replaces every quotation mark
        //       and the comma, because a colon cannot close or split a structured log field once the
        //       quotation marks around it are gone, while a quotation mark can. The rendering is the same
        //       LENGTH as the value, which is what lets an operator still tell two identities apart.
        assertEquals("a...level.:.ERROR", MessagingCorrelationId.logSafe(injection));
        assertEquals(injection.length(), MessagingCorrelationId.logSafe(injection).length());
    }

    /**
     * A conforming correlation attribute is carried onto the outbox row unaltered.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a conforming correlation attribute is carried through unaltered")
    void aConformingCorrelationAttributeIsCarried() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));
        Message<String> message = wireMessage(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .setHeader(AuthorizationRequestListener.HEADER_CORRELATION_ID, "req-0123456789ab")
                .build();

        this.listener.onRequest(message);

        ArgumentCaptor<AuthReplyOutbox> reply = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox).save(reply.capture());
        assertEquals("req-0123456789ab", reply.getValue().getCorrelationId());
    }

    /**
     * A reply destination outside the configured allowlist REFUSES the request and decides nothing.
     *
     * <p>Purpose: this is the atomicity of a decision and its reply, asserted at the one input that could
     * separate them. The destination is a message attribute, so a requester chooses it; if an unlisted
     * value merely stopped the reply being written, the decision rows would still commit and the container
     * would acknowledge the request, leaving a committed authorization that no reply row accounts for and
     * no message left to re-derive it from.</p>
     *
     * <p>Refactoring Rationale: this case previously asserted the OPPOSITE -- that the decision was
     * recorded and only the reply suppressed -- and it passed, because that is what the listener did. The
     * expectation was wrong rather than the code merely incomplete: the outbox exists to make "a committed
     * decision with no reply" unreachable, so a test pinning that state as correct was pinning the defect.
     * Both writes are now asserted ABSENT as well as the refusal being raised, because a refusal that
     * happened after the writes would satisfy an exception-only assertion while leaving the rows.</p>
     *
     * <p>Assumptions: the refusal type is the one a malformed payload raises, so the container routes it
     * the same way -- redelivery and then the dead-letter queue -- rather than a second type having to be
     * configured for the transport half of the same contract.</p>
     *
     * <p>Assumptions: the message of the refusal is asserted NOT to carry the rejected address. The value
     * is requester-supplied and the refusal travels to a dead-letter diagnostic, so quoting it back would
     * put attacker-chosen text into an operational record.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unlisted reply destination refuses the request and records no decision")
    void anUnlistedReplyDestinationRefusesTheRequest() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));
        String attackerOwned = "https://sqs.us-east-1.amazonaws.com/999999999999/attacker-owned";

        AuthMessageFormatException refusal = assertThrows(AuthMessageFormatException.class,
                () -> this.listener.onRequest(
                        messageFor(requestFor(Money.of("100.99")), attackerOwned)));

        assertThat(refusal.getMessage())
                .as("the requester-supplied address must not reach a dead-letter diagnostic")
                .doesNotContain(attackerOwned)
                .contains(AuthorizationRequestListener.HEADER_REPLY_TO);
        verify(this.outbox, never()).save(any(AuthReplyOutbox.class));
        verify(this.details, never()).save(any(PendingAuthDetail.class));
        verify(this.summaries, never()).save(any(PendingAuthSummary.class));
    }

    /**
     * A request naming NO reply destination is refused on the same terms.
     *
     * <p>Assumptions: the absent case is asserted separately from the unlisted case because they are two
     * branches and the earlier revision treated both by returning normally. An implementation that refused
     * only the unlisted value would leave the identical phantom-decision state reachable by the simpler
     * input -- omitting the attribute entirely.</p>
     *
     * <p>Assumptions: no lookup is asserted to have happened either, which is what places the check ahead
     * of the decode and the account-context reads rather than merely ahead of the write.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a request naming no reply destination refuses before any lookup")
    void aRequestNamingNoReplyDestinationIsRefused() {
        Message<String> message = MessageBuilder
                .withPayload(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .setHeader(AuthorizationRequestListener.HEADER_CONTENT_TYPE,
                        AuthorizationRequestListener.CONTENT_TYPE_CSV)
                .build();

        assertThrows(AuthMessageFormatException.class, () -> this.listener.onRequest(message));

        verifyNoInteractions(this.accounts);
        verify(this.outbox, never()).save(any(AuthReplyOutbox.class));
        verify(this.details, never()).save(any(PendingAuthDetail.class));
        verify(this.summaries, never()).save(any(PendingAuthSummary.class));
    }

    /**
     * A request that declares NO payload format is refused before the payload is decoded.
     *
     * <p>Purpose: the decoder splits a delimited record by position, so it reads any text with the right
     * separator count. A producer that has not said what it sent is therefore not detected by the decode --
     * the decode succeeds and puts values in the wrong fields -- which is why the declaration is required
     * rather than merely inspected when present.</p>
     *
     * <p>Assumptions: the payload here is WELL FORMED. Using a valid record is what makes the case about
     * the missing declaration rather than about the decoder: an implementation that dropped the check would
     * decide this request successfully, so the assertion below would fail rather than pass for the wrong
     * reason.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a request declaring no payload format is refused before the payload is decoded")
    void aRequestDeclaringNoWireFormatIsRefused() {
        Message<String> message = MessageBuilder
                .withPayload(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .setHeader(AuthorizationRequestListener.HEADER_REPLY_TO, ALLOWED_REPLY_QUEUE)
                .build();

        AuthMessageFormatException refusal = assertThrows(AuthMessageFormatException.class,
                () -> this.listener.onRequest(message));

        assertThat(refusal.getMessage())
                .as("the refusal must name the attribute and the one format this consumer decodes")
                .contains(AuthorizationRequestListener.HEADER_CONTENT_TYPE)
                .contains(AuthorizationRequestListener.CONTENT_TYPE_CSV);
        verifyNoInteractions(this.accounts);
        verify(this.outbox, never()).save(any(AuthReplyOutbox.class));
        verify(this.details, never()).save(any(PendingAuthDetail.class));
        verify(this.summaries, never()).save(any(PendingAuthSummary.class));
    }

    /**
     * A request declaring a DIFFERENT payload format is refused, and the declared value is not quoted raw.
     *
     * <p>Assumptions: the declared value in this case is a structured-document media type carrying a
     * control character, so one input exercises two rules at once -- the format is wrong, and the value is
     * requester-supplied text heading for a dead-letter diagnostic. The assertion therefore checks both
     * that the request is refused and that the raw value does not survive into the message.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a request declaring a different payload format is refused and the value is sanitised")
    void aRequestDeclaringAnotherWireFormatIsRefused() {
        String foreign = "application/json\nevent=auth.request.accepted";
        Message<String> message = wireMessage(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .setHeader(AuthorizationRequestListener.HEADER_CONTENT_TYPE, foreign)
                .build();

        AuthMessageFormatException refusal = assertThrows(AuthMessageFormatException.class,
                () -> this.listener.onRequest(message));

        assertThat(refusal.getMessage())
                .as("a requester-supplied media type must not reach a diagnostic with its newline intact")
                .doesNotContain(foreign)
                .contains(AuthorizationRequestListener.CONTENT_TYPE_CSV);
        verifyNoInteractions(this.accounts);
        verify(this.details, never()).save(any(PendingAuthDetail.class));
        verify(this.summaries, never()).save(any(PendingAuthSummary.class));
    }

    /**
     * A request declaring the one accepted format is decided normally, including in a padded rendering.
     *
     * <p>Assumptions: the declaration is supplied with surrounding blanks and in mixed case, which a
     * producer's header library may introduce and which a media type's own definition makes immaterial.
     * Accepting that rendering is asserted rather than assumed because a check written with a bare equality
     * would refuse a conforming producer, turning a tolerated variation into a dead letter.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a padded, mixed-case rendering of the accepted format is decided normally")
    void theAcceptedWireFormatIsDecidedNormally() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));
        Message<String> message = wireMessage(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .setHeader(AuthorizationRequestListener.HEADER_CONTENT_TYPE, "  Text/CSV  ")
                .build();

        this.listener.onRequest(message);

        verify(this.details).save(any(PendingAuthDetail.class));
        verify(this.outbox).save(any(AuthReplyOutbox.class));
    }

    /**
     * A request whose expiry has already passed is dropped without being decided.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an expired request is dropped without a lookup or a reply")
    void anExpiredRequestIsDropped() {
        Message<String> message = wireMessage(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .setHeader(AuthorizationRequestListener.HEADER_EXPIRES_AT,
                        String.valueOf(FIXED_INSTANT.minusSeconds(1).toEpochMilli()))
                .build();

        this.listener.onRequest(message);

        verifyNoInteractions(this.accounts);
        verifyNoInteractions(this.outbox);
        verifyNoInteractions(this.details);
    }

    /**
     * A request whose expiry has already passed is dropped and the drop is recorded in the log.
     *
     * <p>Assumptions: the LOG record is asserted alongside the drop, and the two together are the
     * behaviour rather than either one alone. Dropping in silence is the specific failure the expiry
     * attribute exists to prevent: the target transport carries no per-message deadline of its own, so
     * this consumer is the only place a stale request is refused, and a refusal nobody can see is
     * indistinguishable from a request that was never sent. The sibling case above asserts the drop by
     * the absence of a lookup and a reply, which is silent by construction and would still pass if the
     * record were removed.</p>
     *
     * <p>Assumptions: the appender is attached and detached inside this case rather than in shared
     * setup, so no other case in this class observes a captured logger. Capturing globally would let a
     * record emitted by one case be asserted by another.</p>
     *
     * <p>Refactoring Rationale: the LEVEL is now captured and restored as well as the appender. The
     * logger this case configures is a process-wide singleton, so a level left at warn outlasted the
     * case and silenced every finer record this class's other cases -- and any later class's -- might
     * have asserted. That is an order-dependent failure, which is the hardest kind to attribute: the
     * suite stays green until a case that reads a debug record happens to run after this one.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an expired request is dropped and the drop is recorded in the log")
    void anExpiredRequestIsDroppedAndLogged() {
        Logger listenerLogger =
                (Logger) LoggerFactory.getLogger(AuthorizationRequestListener.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        listenerLogger.addAppender(captured);
        // WHY : Assumptions: the prior level may legitimately be null, which is not "no level" but
        //       "inherit from the parent", and restoring null is what puts the logger back into that
        //       state. Substituting a concrete default here -- info, say -- would leave the logger
        //       pinned where it had previously been inheriting, which is a different configuration that
        //       happens to look the same in this class.
        Level previousLevel = listenerLogger.getLevel();
        listenerLogger.setLevel(Level.WARN);
        try {
            Message<String> message = wireMessage(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                    .setHeader(AuthorizationRequestListener.HEADER_EXPIRES_AT,
                            String.valueOf(FIXED_INSTANT.minusSeconds(1).toEpochMilli()))
                    .build();

            this.listener.onRequest(message);

            verifyNoInteractions(this.outbox);
            assertThat(captured.list)
                    .as("a stale request that is dropped without a record is indistinguishable "
                            + "from one that never arrived")
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(recorded -> assertThat(recorded)
                            .contains("event=auth.request.dropped")
                            .contains("reason=expired"));
        } finally {
            listenerLogger.detachAppender(captured);
            captured.stop();
            listenerLogger.setLevel(previousLevel);
        }
    }

    /**
     * A supplied expiry that will not parse is refused, while an absent one is answered normally.
     *
     * <p>Refactoring Rationale: the two halves are asserted in one case because the property is the
     * DISTINCTION between them, and each half alone is satisfied by a defect in the other direction. An
     * implementation that answered both would let a producer defeat expiry enforcement by corrupting the
     * attribute -- {@code expiresAt=not-an-instant} would be honoured -- and an implementation that refused
     * both would silently discard every request from a producer that never adopted the attribute at
     * all.</p>
     *
     * <p>Assumptions: the unparseable half is asserted by the absence of any lookup rather than by a
     * reply, because a refused request produces no reply of any kind; and the absent half is asserted by
     * the decision being recorded, which is the only observable that separates "answered" from
     * "dropped".</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unparseable expiry is refused while an absent one is answered")
    void anUnparseableExpiryIsRefusedWhileAnAbsentOneProceeds() {
        Message<String> unparseable = wireMessage(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .setHeader(AuthorizationRequestListener.HEADER_EXPIRES_AT, "not-an-instant")
                .build();

        this.listener.onRequest(unparseable);

        verifyNoInteractions(this.accounts);
        verifyNoInteractions(this.outbox);
        verifyNoInteractions(this.details);

        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));
        Message<String> noExpiry = wireMessage(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .build();

        this.listener.onRequest(noExpiry);

        verify(this.details).save(any(PendingAuthDetail.class));
    }

    /**
     * A redelivered request is answered from the recorded row rather than decided a second time.
     *
     * <p>Assumptions: the seek is by the CARD and the identifier together, which is the durable key the
     * schema makes unique. Seeking by the identifier alone -- which an earlier revision did -- could
     * return another card's decision, because the identifier is a fifteen-character acquirer value that
     * two acquirers may both issue.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a redelivered request republishes the recorded answer without deciding again")
    void aRedeliveredRequestRepublishesTheRecordedAnswer() {
        PendingAuthDetail recorded = mock(PendingAuthDetail.class);
        when(recorded.getCardNum()).thenReturn(CARD_NUM);
        when(recorded.getTransactionId()).thenReturn(TRANSACTION_ID);
        when(recorded.getAuthIdCode()).thenReturn("104530");
        when(recorded.getAuthRespCode()).thenReturn("00");
        when(recorded.getAuthRespReason()).thenReturn("0000");
        when(recorded.getApprovedAmount()).thenReturn(new BigDecimal("100.99"));
        when(this.details.findByCardNumAndTransactionId(CARD_NUM, TRANSACTION_ID))
                .thenReturn(Optional.of(recorded));

        this.listener.onRequest(messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE));

        verifyNoInteractions(this.accounts);
        verify(this.details, never()).save(any(PendingAuthDetail.class));
        verify(this.summaries, never()).save(any(PendingAuthSummary.class));
        // WHY : Assumptions: the republished row carries the SAME deduplication identity the first
        //       delivery carried, which is why a redelivery inside the five-minute window is suppressed by
        //       the queue rather than sent twice. Specification section 0.4.1.8 fixes that identity as the
        //       literal transaction identifier, so it is asserted as the constant this test already sends
        //       rather than as a value derived from it -- a derived value would make the suppression
        //       depend on this service's secret, and the requester resending the same identifier would
        //       then see two replies from a second producer that keys differently.
        ArgumentCaptor<AuthReplyOutbox> reply = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox).save(reply.capture());
        assertEquals(TRANSACTION_ID, reply.getValue().getDeduplicationId());
        assertEquals(CARD_NUM, reply.getValue().getOrderGroupId());
    }

    /**
     * An approved authorization is recorded pending a match, and a declined one is recorded declined.
     *
     * <p>Assumptions: this is the two-branch condition at lines 902 to 906 of {@code cbl/COPAUA0C.cbl} --
     * {@code SET PA-MATCH-PENDING} when {@code AUTH-RESP-APPROVED} holds and
     * {@code SET PA-MATCH-AUTH-DECLINED} otherwise -- and BOTH branches are asserted in one test because
     * the defect this closes was that they produced the same stored value. A test of the approval alone
     * would have passed against the defective code, so the two cases only mean anything together.</p>
     *
     * <p>Assumptions: the decline is provoked by an OVER-LIMIT amount on a card that resolves, and not by
     * an unresolvable card. That distinction is load-bearing: the sibling case above shows an unresolved
     * card records nothing at all, so it could never exhibit a wrong stored status. An over-limit request
     * against a resolvable card is the decline that reaches the write, which is the one this asserts.</p>
     *
     * <p>Assumptions: the summary counters are asserted beside the detail status, because they are the
     * other half of the same decision and reading them together is what shows the row and the counter
     * agree. A declined row with an incremented APPROVED counter would be the same class of defect.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an approval is recorded pending and a decline is recorded declined")
    void theStoredMatchStatusFollowsTheDecision() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthDetail> approved =
                ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details).save(approved.capture());
        assertEquals(PendingAuthDetail.MATCH_STATUS_PENDING,
                approved.getValue().getMatchStatus());

        // WHY : Refactoring Rationale: this was ONE reset call taking all three mocks. Mockito's reset
        //       is generic varargs, so three arguments of three unrelated types infer a common
        //       supertype and the compiler creates an unchecked generic array for them; one call per
        //       mock infers each concrete type and needs no suppression to be warning-free.
        // WHY : Alternatives Considered: splitting this case into two, which is the usual remedy for a
        //       mid-test reset. Rejected because the LAST assertion of this case depends on the
        //       approved request having run first in the same case -- a decline that reached the
        //       approved statement is only observable to a test that drove both -- so splitting would
        //       discard the property this case exists for, as its own documentation records.
        reset(this.details);
        reset(this.summaries);
        reset(this.outbox);
        // WHY : Assumptions: resetting the summary mock discards its STUBBING as well as its recorded
        //       calls, so the shared fixture's row-count stubs have to be re-applied before the second
        //       message runs. Without this the contribution reports zero rows changed and the listener
        //       refuses the decision, which would fail this case on a consistency check rather than on
        //       the stored match status it exists to assert.
        givenWorkingSummaryWrites();
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(messageFor(requestFor(Money.of("6000.00")), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthDetail> declined =
                ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details).save(declined.capture());
        assertEquals(PendingAuthDetail.MATCH_STATUS_DECLINED,
                declined.getValue().getMatchStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(declined.getValue().getApprovedAmount()));

        // WHY : Refactoring Rationale: as on the declined case above, the counters moved from the saved
        //       entity to an atomic statement. This case additionally covers the SECOND half of the
        //       assertion it used to make -- that the approved arm did not run -- which matters here
        //       because the same test first drove an APPROVED request through the listener, so a decline
        //       that reached the approved statement would be invisible to a per-arm assertion made only
        //       once.
        verify(this.summaries).addDeclinedAuthorization(ACCOUNT_ID, new BigDecimal("6000.00"));
        verify(this.summaries, never()).addApprovedAuthorization(anyLong(), any(BigDecimal.class));
    }

    /**
     * The detail row and the outbox row are both produced by the mapper's own projections.
     *
     * <p>Assumptions: the assertion is made on the MAPPER and not only on the saved rows, because the
     * defect it closes was not a wrong value -- the assembly this class performed and the projection the
     * mapper published produced identical rows -- but two implementations of one crossing, only one of
     * which ran. A value assertion passes under either, so the property under test is that the documented
     * projection is the one the live path goes through, and an observing substitute is what makes that
     * observable.</p>
     *
     * <p>Assumptions: the saved outbox row's deadline is asserted beside it, as five seconds past the
     * fixed clock, because that deadline now comes from the mapper's conversion of the reference
     * descriptor's fifty tenths of a second rather than from a local constant. A projection that was
     * called but handed the wrong instant would still satisfy the interaction assertion alone.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the detail and outbox rows are both produced by the mapper's projections")
    void theRowsAreProducedByTheMapperProjections() {
        AuthorizationMessageMapper observed =
                org.mockito.Mockito.spy(new AuthorizationMessageMapper(VALIDATOR));
        AuthorizationRequestListener throughTheMapper = new AuthorizationRequestListener(
                this.summaries, this.details, this.outbox, new AuthorizationDecisionService(),
                observed, this.accounts, List.of(ALLOWED_REPLY_QUEUE),
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), WINDOW_LIMIT,
                this::recordClosedWindow);
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));

        throughTheMapper.onRequest(messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE));

        verify(observed).toPendingAuthDetail(any(), any(), any(), any());
        verify(observed).toOutboxMessage(any(), any());
        ArgumentCaptor<AuthReplyOutbox> published = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox).save(published.capture());
        assertEquals(java.time.LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC)
                .plusSeconds(5), published.getValue().getExpiresAt());
        assertEquals(AuthReplyOutbox.CONTENT_TYPE_CSV, published.getValue().getContentType());
    }

    /**
     * A reply destination that the routing carrier cannot hold is refused rather than made durable.
     *
     * <p>Assumptions: the destination is checked against the allowlist BEFORE the routing is built, so a
     * value too wide for the column can only arrive from an allowlist entry that is itself too wide --
     * which is a deployment fault rather than a requester's. It is asserted because the refusal is what
     * keeps such an entry from reaching the flush of the deciding transaction, where it would abort a
     * decision that had already been made.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a reply destination wider than its column refuses the message rather than the decision")
    void anOversizedReplyDestinationIsRefused() {
        String tooWide = "https://sqs.us-east-1.amazonaws.com/000000000000/"
                + "q".repeat(OutboxMessage.REPLY_QUEUE_URL_MAX_LENGTH);
        AuthorizationRequestListener wideAllowlist = new AuthorizationRequestListener(
                this.summaries, this.details, this.outbox, new AuthorizationDecisionService(),
                new AuthorizationMessageMapper(VALIDATOR), this.accounts,
                List.of(tooWide), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), WINDOW_LIMIT,
                this::recordClosedWindow);
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));

        assertThrows(IllegalArgumentException.class, () -> wideAllowlist.onRequest(
                messageFor(requestFor(Money.of("100.99")), tooWide)));

        verify(this.outbox, never()).save(any());
    }

    /**
     * Every message in a multi-message run produces exactly ONE reply of exactly the declared length.
     *
     * <p>Purpose: this is the assertion that pins the reply-length divergence registered as
     * {@code D-REPLY-PUT-LENGTH}. The reference program composes its reply with a {@code STRING ... WITH
     * POINTER WS-RESP-LENGTH} at line 730 of {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl},
     * and that pointer is referenced in only three places in the whole program -- its declaration with
     * {@code VALUE 1} at line 46, the composition at line 730, and the length moved to the put buffer at
     * line 756. It is never reset. Within one run of the task the replies therefore accumulate in the
     * buffer: the first occupies positions 1 to 63, the second 64 to 126, the third 127 to 189, and the
     * fourth needs 190 to 252 in a buffer declared as 200 characters, at which point the {@code STRING}
     * has no {@code ON OVERFLOW} clause and is simply not performed, so a stale buffer is sent again.</p>
     *
     * <p>Assumptions: FOUR messages are driven rather than two, because four is where the reference
     * arithmetic changes character -- the first three each land at a fresh offset inside the buffer while
     * the fourth exceeds it and leaves the previous contents in place -- so a run of two would pass
     * against an implementation that accumulated. Each message carries its own transaction identifier, so
     * the assertion can also show that reply N belongs to message N rather than to a buffer shared
     * between them.</p>
     *
     * <p>Assumptions: the length is asserted HERE rather than where the row is published, because the
     * publisher treats the payload as opaque bytes and asserting a wire length there would give it
     * knowledge of a format it deliberately does not have. The delimiter count is asserted beside the
     * length because the two together are what fix the frame: six fields whose widths sum to 57, plus a
     * delimiter after every one of them INCLUDING the last, is what makes 63 rather than the 62 an
     * interior-delimiter count would predict.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("each message produces exactly one reply of the declared 63 characters, never a shared buffer")
    void everyMessageProducesExactlyOneReplyOfTheDeclaredLength() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));
        List<String> transactionIds =
                List.of("TXN000000000001", "TXN000000000002", "TXN000000000003", "TXN000000000004");

        for (String transactionId : transactionIds) {
            this.listener.onRequest(
                    messageFor(requestFor(Money.of("100.99"), transactionId), ALLOWED_REPLY_QUEUE));
        }

        ArgumentCaptor<AuthReplyOutbox> replies = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox, times(transactionIds.size())).save(replies.capture());
        assertEquals(transactionIds.size(), replies.getAllValues().size(),
                "each message must leave exactly one publishable reply row");
        for (int index = 0; index < transactionIds.size(); index++) {
            AuthReplyOutbox row = replies.getAllValues().get(index);
            assertEquals(CsvAuthCodec.REPLY_WIRE_LENGTH, row.getPayload().length(),
                    "reply " + (index + 1) + " must be exactly the declared wire length");
            assertEquals(CsvAuthCodec.REPLY_FIELD_COUNT,
                    row.getPayload().chars().filter(each -> each == CsvAuthCodec.DELIMITER).count(),
                    "reply " + (index + 1) + " must carry one delimiter per field, the last included");
            assertThat(row.getPayload()).startsWith(CARD_NUM + CsvAuthCodec.DELIMITER);
            assertEquals(transactionIds.get(index), row.getDeduplicationId(),
                    "reply " + (index + 1) + " must be keyed by its OWN transaction identifier");
            assertEquals(CARD_NUM, row.getOrderGroupId(),
                    "every reply for one card must share that card's ordering group");
            assertEquals(ALLOWED_REPLY_QUEUE, row.getReplyQueueUrl());
        }
    }

    /**
     * A summary the transaction already read is contributed to rather than created again.
     *
     * <p>Purpose: this is the replace arm of {@code 8400-UPDATE-SUMMARY} at lines 824 to 828, the
     * counterpart of the insert arm the case above asserts, and the two are kept as separate cases because
     * the reference program's own branch is separate -- {@code IF FOUND-PAUT-SMRY-SEG} replaces and the
     * {@code ELSE} at line 829 inserts. An implementation that took one path for both would satisfy a
     * single combined case.</p>
     *
     * <p>Assumptions: the contribution is asserted as an ATOMIC statement against the account identifier
     * rather than as a saved instance carrying new totals, because that is what makes a contribution
     * additive. Reading the row, adding to it in memory and writing it back would lose a concurrent
     * card's contribution on the same account, which the queue's per-card ordering positively permits.
     * The limit refresh is asserted alongside, because the reference program refreshes both limits from
     * the account master on EVERY message at lines 810 and 811, not only when it creates the row.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a stored summary is contributed to atomically and its limits are refreshed")
    void aStoredSummaryIsContributedToAtomically() {
        givenResolvableCard();
        PendingAuthSummary stored = summaryWithRoom();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(stored));

        this.listener.onRequest(messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE));

        verify(this.summaries, never()).insertSummaryIfAbsent(any(PendingAuthSummary.class));
        verify(this.summaries).addApprovedAuthorization(ACCOUNT_ID, new BigDecimal("100.99"));
        verify(this.summaries, never()).addDeclinedAuthorization(anyLong(), any(BigDecimal.class));
        ArgumentCaptor<PendingAuthSummary> refreshed =
                ArgumentCaptor.forClass(PendingAuthSummary.class);
        verify(this.summaries).save(refreshed.capture());
        assertEquals(0,
                new BigDecimal("5000.00").compareTo(refreshed.getValue().getCreditLimit()));
        assertEquals(0, new BigDecimal("500.00").compareTo(refreshed.getValue().getCashLimit()));
    }

    /**
     * A summary that appeared between the read and the insert receives an ADDITIVE contribution.
     *
     * <p>Purpose: this is the path that exists because the insert is duplicate-tolerant. When it reports
     * that a row already existed, this decision's contribution must be applied to the row that is
     * actually there rather than to the instance this transaction built, or the concurrent authorization
     * that created it loses its own counters. The window is real rather than theoretical: the request
     * queue groups by card number, so two cards of one account are delivered concurrently by design.</p>
     *
     * <p>Assumptions: the two reads are stubbed as a SEQUENCE -- empty first, then present -- because that
     * is exactly the interleaving being reproduced: this transaction read no summary, another transaction
     * inserted one, and the insert then reported the conflict. A single stubbed value could not express
     * the change of state that makes the path reachable.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a summary that appeared after the read is contributed to, not overwritten")
    void anAppearedSummaryReceivesAnAdditiveContribution() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(summaryWithRoom()));
        when(this.summaries.insertSummaryIfAbsent(any(PendingAuthSummary.class))).thenReturn(0);

        this.listener.onRequest(messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE));

        verify(this.summaries).insertSummaryIfAbsent(any(PendingAuthSummary.class));
        verify(this.summaries).addApprovedAuthorization(ACCOUNT_ID, new BigDecimal("100.99"));
        verify(this.outbox).save(any(AuthReplyOutbox.class));
    }

    /**
     * A summary reported as existing and then absent REFUSES the decision instead of losing it.
     *
     * <p>Purpose: the insert reporting a conflict asserts that a row exists, so a following read that
     * finds none means the two statements disagree about the state of the account. Continuing would
     * commit a decision and a reply whose contribution reached no summary at all, which is the one
     * outcome the unit of work exists to prevent. The refusal rolls the message back so the queue
     * redelivers it, and the idempotency seek finds nothing on the retry because nothing committed.</p>
     *
     * <p>Assumptions: the absence of the reply row is asserted as well as the exception. A test that
     * only asserted the throw would pass against an implementation that had already written the reply
     * before discovering the disagreement, and the ordering is the property that matters.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a summary reported present on insert and then absent refuses the decision")
    void aVanishedSummaryRefusesTheDecision() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(this.summaries.insertSummaryIfAbsent(any(PendingAuthSummary.class))).thenReturn(0);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> this.listener.onRequest(
                        messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE)));

        assertThat(refused).hasMessageContaining("nowhere to land");
        verify(this.outbox, never()).save(any(AuthReplyOutbox.class));
        verify(this.details, never()).save(any(PendingAuthDetail.class));
    }

    /**
     * Stubs the three row-count-returning summary operations to report the one row each changed.
     *
     * <p>Assumptions: this exists as a named helper rather than three inline stubs because it is needed in
     * two places -- the shared fixture, and again after any case that resets the summary mock mid-run,
     * since a reset discards stubbing as well as recorded calls. Inlining it twice is what allowed the
     * mid-run reset below to leave the contribution reporting zero rows, which surfaced as the listener's
     * own consistency failure in a case about something else entirely.</p>
     */
    private void givenWorkingSummaryWrites() {
        when(this.summaries.insertSummaryIfAbsent(any(PendingAuthSummary.class))).thenReturn(1);
        when(this.summaries.addApprovedAuthorization(anyLong(), any(BigDecimal.class))).thenReturn(1);
        when(this.summaries.addDeclinedAuthorization(anyLong(), any(BigDecimal.class))).thenReturn(1);
    }

    /**
     * Stubs a cross-reference and an account that resolve, with an existing customer.
     *
     * <p>Assumptions: the limits are ample so that the decision is an approval unless a case says
     * otherwise, which keeps each case's own fixture to the one thing it varies.</p>
     */
    private void givenResolvableCard() {
        when(this.accounts.findCardXref(CARD_NUM)).thenReturn(
                Optional.of(new AccountContextClient.CardXref(ACCOUNT_ID, CUSTOMER_ID)));
        when(this.accounts.findAccount(ACCOUNT_ID)).thenReturn(
                Optional.of(new AccountContextClient.Account(new BigDecimal("5000.00"),
                        new BigDecimal("500.00"), new BigDecimal("0.00"))));
        when(this.accounts.customerExists(CUSTOMER_ID)).thenReturn(true);
    }

    /**
     * Builds a summary with an ample limit and nothing reserved against it.
     *
     * @return the summary, never {@code null}
     */
    private PendingAuthSummary summaryWithRoom() {
        PendingAuthSummary summary = new PendingAuthSummary(ACCOUNT_ID, CUSTOMER_ID);
        summary.refreshLimits(new BigDecimal("5000.00"), new BigDecimal("500.00"));
        return summary;
    }

    /**
     * Records one observed window boundary, keeping the generation and the admitted count together.
     *
     * @param generation the closing window's generation
     * @param admittedInWindow how many requests the closing window admitted
     */
    private void recordClosedWindow(long generation, int admittedInWindow) {
        this.closedGenerations.add(generation);
        this.closedWindows.add(admittedInWindow);
    }

    /**
     * Wraps a request as a message carrying a reply destination and the declared wire format.
     *
     * @param request the request to encode as the payload; must not be {@code null}
     * @param replyQueueUrl the reply destination attribute to set
     * @return the message, never {@code null}
     */
    private Message<String> messageFor(AuthRequest request, String replyQueueUrl) {
        return wireMessage(CsvAuthCodec.encodeRequest(request), replyQueueUrl).build();
    }

    /**
     * Starts a message carrying the allowlisted reply destination and the declared wire format.
     *
     * <p>Assumptions: every case that is not ABOUT a missing transport attribute goes through this
     * builder, so the two attributes the consumer requires are set in one place. Setting them per case was
     * the alternative and it is what made adding the format check a change to eleven unrelated tests; a
     * shared builder means the next required attribute is one edit here.</p>
     *
     * @param payload the message body; must not be {@code null}
     * @return a builder with the two required attributes set, never {@code null}
     */
    private MessageBuilder<String> wireMessage(String payload) {
        return wireMessage(payload, ALLOWED_REPLY_QUEUE);
    }

    /**
     * Starts a message carrying the supplied reply destination and the declared wire format.
     *
     * @param payload the message body; must not be {@code null}
     * @param replyQueueUrl the reply destination attribute to set
     * @return a builder with both attributes set, never {@code null}
     */
    private MessageBuilder<String> wireMessage(String payload, String replyQueueUrl) {
        return MessageBuilder.withPayload(payload)
                .setHeader(AuthorizationRequestListener.HEADER_REPLY_TO, replyQueueUrl)
                .setHeader(AuthorizationRequestListener.HEADER_CONTENT_TYPE,
                        AuthorizationRequestListener.CONTENT_TYPE_CSV);
    }

    /**
     * Builds a well-formed request carrying the supplied amount.
     *
     * @param amount the transaction amount the request carries
     * @return the request, never {@code null}
     */
    private AuthRequest requestFor(Money amount) {
        return requestFor(amount, TRANSACTION_ID);
    }

    /**
     * Builds a request for the shared card at the amount and transaction identifier supplied.
     *
     * <p>Assumptions: this overload exists so a multi-message case can vary the ONE field that makes two
     * requests distinct to this listener. The identifier is what the idempotency seek looks up and what
     * becomes the reply's deduplication key, so holding it constant across a run would make the second
     * message a replay of the first and would test the replay path instead of the intended one.</p>
     *
     * @param amount the transaction amount; must not be {@code null}
     * @param transactionId the fifteen-character transaction identifier this request carries; must not be
     *     {@code null}
     * @return the request, never {@code null}
     */
    private AuthRequest requestFor(Money amount, String transactionId) {
        return new AuthRequest("250801", "104530", CARD_NUM, "0100", "1230", "0100", "POS001",
                "000000", amount, "5411", "840", "05", "MERCHANT0000001",
                "TEST MERCHANT NAME 01", "SPRINGFIELD", "IL", "627010000", transactionId);
    }

    /**
     * Builds one already-expired request, which the listener drops but still counts towards its window.
     *
     * @return a message the listener will drop as stale
     */
    private Message<String> expiredMessage() {
        return wireMessage(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .setHeader(AuthorizationRequestListener.HEADER_EXPIRES_AT,
                        String.valueOf(FIXED_INSTANT.minusSeconds(1).toEpochMilli()))
                .build();
    }

    /**
     * A window closes on exactly its admission allowance, not one message earlier or later.
     *
     * <p>Assumptions: this is the assertion the whole bound exists for. The reference consumer declares a
     * limit of 500 at {@code cbl/COPAUA0C.cbl} L40 and PROCESSES 501, its counter being incremented after
     * the get at L332 and then compared with strict greater-than at L339, so counts one through the limit
     * all read another request and only the count past it ends the run. The allowance asserted here is the
     * declared limit plus that offset, so this case fails both if the offset is dropped -- which would
     * reintroduce the divergence this migration withdrew -- and if the window ever runs longer than one
     * message past its limit.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a window closes on exactly its admission allowance, the declared limit plus one")
    void aWindowClosesOnExactlyItsQuota() {
        for (int admitted = 1; admitted < WINDOW_ADMISSIONS; admitted++) {
            this.listener.onRequest(expiredMessage());
            assertEquals(List.of(), this.closedWindows,
                    "no window may close before its allowance is reached");
        }

        this.listener.onRequest(expiredMessage());

        assertEquals(List.of(WINDOW_ADMISSIONS), this.closedWindows,
                "the window must close on the allowance-th message reporting the allowance");
        assertEquals(List.of(0L), this.closedGenerations,
                "the first window to close is generation zero");
    }

    /**
     * The allowance resets at a boundary, so successive windows each close on their own full allowance.
     *
     * <p>Assumptions: two windows are driven rather than one, because a window that closed correctly and
     * then never reset would show up only on the second -- and a consumer that closed one window and then
     * ran unbounded forever is the failure this asserts against.</p>
     *
     * <p>Assumptions: the GENERATIONS are asserted as well as the counts, and they are what distinguishes
     * two windows closing once each from one window closing twice. Two closures reporting one generation
     * would be the defect; consecutive generations are two windows behaving correctly.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("successive windows each close on their own full allowance, in generation order")
    void successiveWindowsEachCloseOnTheirOwnQuota() {
        for (int admitted = 0; admitted < WINDOW_ADMISSIONS * 2; admitted++) {
            this.listener.onRequest(expiredMessage());
        }

        assertEquals(List.of(WINDOW_ADMISSIONS, WINDOW_ADMISSIONS), this.closedWindows,
                "two full windows must produce two boundaries, each reporting the full allowance");
        assertEquals(List.of(0L, 1L), this.closedGenerations,
                "the two boundaries must be two DIFFERENT windows, in order");
    }

    /**
     * Every message the consumer is handed occupies a place, whatever the outcome of handling it.
     *
     * <p>Assumptions: a dropped stale request and a request that could not be decoded both occupy one. The
     * reference program increments its counter after the get returns and before any outcome is known, at
     * {@code cbl/COPAUA0C.cbl} L332, so a request it could not act on still consumed one of its gets.
     * Counting only successful decisions would let a flood of expired or malformed requests keep one window
     * open indefinitely.</p>
     *
     * <p>Assumptions: the place is taken on ADMISSION, so the message whose handling threw is counted
     * because the reservation happened before the throw and not because a {@code finally} block caught up
     * with it. That distinction is the point of the change this case now covers: a reservation taken on
     * completion is one the container can outrun.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a dropped and a malformed request each occupy a place in the window")
    void everyReceivedMessageCountsTowardsTheWindow() {
        int declaredLimit = 1;
        int allowance = declaredLimit + AuthorizationRequestListener.BASELINE_COMPARISON_OFFSET;
        AuthorizationRequestListener bounded = listenerWithWindow(declaredLimit);

        bounded.onRequest(expiredMessage());
        assertEquals(List.of(), this.closedWindows,
                "one message must not close a window that admits two");

        assertThrows(AuthMessageFormatException.class,
                () -> bounded.onRequest(wireMessage("not,a,request").build()));

        assertEquals(List.of(allowance), this.closedWindows,
                "a message whose handling threw must still have occupied its place in the window");
    }

    /**
     * Concurrent handlers close exactly one window per allowance, each closure a distinct generation.
     *
     * <p>Assumptions: the container delivers on several threads at once, and two defects have been closed
     * here in turn. The first was two-step counting -- an increment, a comparison, then a subtraction of
     * the observed value -- under which two threads could each observe the last place, each subtract their
     * own observation, and leave the counter negative, after which one window admitted the allowance plus
     * the deficit and the boundary fired twice. The second was counting on COMPLETION rather than on
     * admission, under which the container could hand out an unbounded number of messages before the
     * allowance-th one finished. The case drives a whole number of allowances from several threads and
     * asserts the closures exactly.</p>
     *
     * <p>Assumptions: the assertion is on the GENERATIONS and not only on the number of closures, because
     * the number alone cannot tell eight windows closing once each from one window closing eight times.
     * Asserting the generations are exactly zero through seven, with no repetition, is what makes
     * "exactly one closure per window" an observed property.</p>
     *
     * <p>Assumptions: every message is an expired one, so the case exercises the admission path without
     * needing account stubs, and the assertions are on counts and identities rather than on timing -- so
     * it is deterministic rather than a race the test hopes to lose.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws InterruptedException if the wait for the worker threads is interrupted
     */
    @Test
    @DisplayName("concurrent handlers close one window per allowance, one closure per generation")
    void concurrentHandlersCloseOneWindowPerQuota() throws InterruptedException {
        int windows = 8;
        int threads = 4;
        int messagesPerThread = WINDOW_ADMISSIONS * windows / threads;
        List<Integer> observed = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Long> generations = java.util.Collections.synchronizedList(new ArrayList<>());
        AuthorizationRequestListener concurrent = new AuthorizationRequestListener(this.summaries,
                this.details, this.outbox, new AuthorizationDecisionService(),
                new AuthorizationMessageMapper(VALIDATOR), this.accounts,
                List.of(ALLOWED_REPLY_QUEUE),
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), WINDOW_LIMIT,
                (generation, admitted) -> {
                    generations.add(generation);
                    observed.add(admitted);
                });

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int worker = 0; worker < threads; worker++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int message = 0; message < messagesPerThread; message++) {
                    concurrent.onRequest(expiredMessage());
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : workers) {
            thread.join();
        }

        assertEquals(windows, observed.size(),
                "a whole number of allowances must close exactly that many windows");
        assertThat(observed).containsOnly(WINDOW_ADMISSIONS);
        assertThat(generations)
                .as("each closure must be a distinct window, so no generation may repeat")
                .doesNotHaveDuplicates()
                .hasSize(windows);
        assertThat(new java.util.TreeSet<>(generations))
                .as("the generations must be the consecutive run zero through %d", windows - 1)
                .containsExactlyElementsOf(java.util.stream.LongStream.range(0, windows).boxed()
                        .toList());
    }

    /**
     * A window size that admits no request at all is refused at construction.
     *
     * <p>Assumptions: refusing at construction rather than at the first message is what turns a
     * misconfiguration into a startup failure. A zero or negative window would otherwise close on every
     * single message, cycling the container continuously and consuming the queue at the rate the cycle
     * takes -- a fault that presents as a throughput problem rather than as a configuration error.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a non-positive window size is refused at construction")
    void aNonPositiveWindowIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> listenerWithWindow(0));
        assertThrows(IllegalArgumentException.class, () -> listenerWithWindow(-1));
    }

    /**
     * The production default is the baseline's declared limit rather than a rounded convenience.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the default window is the baseline's declared five hundred")
    void theDefaultWindowIsTheDeclaredLimit() {
        assertEquals(500, AuthorizationRequestListener.DEFAULT_REQUEST_PROCESS_LIMIT,
                "the default must be the number cbl/COPAUA0C.cbl L40 declares, not the 501 it handles");
    }
}
