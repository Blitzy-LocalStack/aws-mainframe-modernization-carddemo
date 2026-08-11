package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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
import com.carddemo.common.money.MoneyModule;
import io.awspring.cloud.sqs.annotation.SqsListener;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

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
 *
 * <p><b>Divergences owned here.</b> Three of the eight this package registers are asserted by this class,
 * and the package charter at {@code package-info.java} names it the owner of each. <b>D-D</b> -- a receive
 * failure sets neither exit flag in the reference program, so its poll cycle continues over a get buffer
 * the failed receive never refreshed; here a failure is terminal for that delivery and the shape is
 * structurally unreachable because each delivery carries its own payload. <b>D-F</b> -- the purge program
 * tests one counter twice, and this class owns the ACCUMULATION half: which counter and which amount each
 * arm of the decision moves, so that the paired guard in {@code PurgeJobTest} has a state to be wrong
 * about. <b>D-H</b> -- the wire's fourteen-character money token is parsed through a thirteen-character
 * receiving item there, and here the whole token is read or the request is refused. Nothing in
 * {@code app} is edited for any of the three: they are registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which is the house precedent recorded at
 * {@code tests/README.md} section 1.1 lines 50 to 60 for an unfixable defect in immutable baseline
 * source.</p>
 *
 * <p>Assumptions: NO golden master exists for this path, and three independent structural reasons put one
 * out of reach rather than merely making one inconvenient. The existing suite states at
 * {@code tests/README.md} lines 83 to 85 that the online programs cannot run end to end without a
 * transaction monitor, which the runner does not have; its build step compiles only {@code app/cbl},
 * whose twelve batch programs are the whole of its runnable inventory -- section 1.1 says ten of the
 * twelve at that; and this program in particular issues eight {@code COPY CMQ*} statements, at lines 149,
 * 152, 155, 158, 161, 164, 167 and 170, for which a repository-wide search finds no file at all, so it
 * cannot be compiled by that harness under any flag. Every expectation in this class is therefore derived
 * from the source text by citation and not from a recorded run, which is why each non-obvious assertion
 * carries the line it comes from.</p>
 *
 * <p>Assumptions: the user-specified Rule 1 (Explainability) governs this file, and its ruling here is
 * that the assertion is the WHAT and the reference derivation is the WHY. Its gate is conjunctive -- a
 * missing docstring fails on its own and a missing decision rationale fails on its own -- so every type,
 * test, fixture method and private helper below carries a documentation block, and every assertion whose
 * expected value is not self-evident carries a comment naming the source line that fixes it. The rule is
 * cited by name and its text is not reproduced; it is read through {@code review_rules}, and the
 * convention it is applied under is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
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
     *
     * <p>Assumptions: this is a synthetic DESTINATION a requester nominates per message, and not the queue
     * this consumer reads. The distinction matters because the two are bound in opposite directions: a
     * reply destination arrives as a message attribute and is checked against an allowlist the deployment
     * supplies, so a test has to supply one to exercise the check at all, while the REQUEST queue is never
     * named here -- it is bound from configuration through a property placeholder, which
     * {@link #theTransportContractIsBoundFromConfigurationAndScopedPerMessage()} asserts. The account
     * number in this value is the all-zero placeholder and the queue name is a development one, so nothing
     * here names a real endpoint.</p>
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
     * The processing code an acquirer sends for a cash advance rather than a purchase.
     *
     * <p>Assumptions: the leading two digits of the six-digit code are the transaction class, so this is
     * the value that would make a request a cash advance if any part of this flow branched on it. It
     * exists so the cash-balance case below can show that NOTHING branches on it, which a purchase-coded
     * request could not show.</p>
     */
    private static final String CASH_ADVANCE_PROCESSING_CODE = "010000";

    /**
     * The committed three-record money-boundary fixture, on the classpath.
     */
    private static final String AMOUNT_VARIANTS_FIXTURE = "/fixtures/auth-request-amount-variants.csv";

    /**
     * The committed single-record canonical request wire, on the classpath.
     */
    private static final String CANONICAL_WIRE_FIXTURE = "/fixtures/auth-request-canonical-wire170.csv";

    /**
     * The committed seven-record decline-reason fixture, on the classpath.
     */
    private static final String DECLINED_REASONS_FIXTURE = "/fixtures/auth-reply-declined-reasons.csv";

    /**
     * The card the two committed request fixtures authorize.
     *
     * <p>Assumptions: it differs from {@link #CARD_NUM} because the fixtures are shared with the codec's
     * own vectors and carry their own synthetic test-range number. A case driving a fixture line therefore
     * has to stub the cross-reference for THIS card, and one that silently reused the other constant would
     * exercise the unresolved-card path while appearing to exercise the amount.</p>
     */
    private static final String FIXTURE_CARD_NUM = "4000123456789010";

    /**
     * The widest amount the wire's money picture can express.
     *
     * <p>Assumptions: ten integer digits and two decimals, which is
     * {@code PA-RQ-TRANSACTION-AMT PIC +9(10).99} at
     * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} line 27 at its maximum. It is the one
     * amount in the committed fixture whose final digit the reference receiver's narrower intermediate
     * discards, which is why the D-H case below spells it out rather than deriving it.</p>
     */
    private static final BigDecimal MAXIMUM_DECLARED_AMOUNT = new BigDecimal("9999999999.99");

    /**
     * The two response reasons the detail screen's display table holds and this consumer never emits.
     *
     * <p>Assumptions: {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl} declares a ten-entry
     * table at its lines 58 to 67, and these two -- {@code '4400EXCED DAILY LMT'} at line 63 and
     * {@code '5300LOST CARD'} at line 66 -- are the two entries no branch of the deciding paragraph
     * selects. The table is a superset by exactly two, and it is NOT wrong: a display table that can
     * render a reason another producer might one day send is broader than the producer on purpose.</p>
     */
    private static final List<String> DISPLAY_ONLY_REASONS = List.of("4400", "5300");

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
     * <p>Trade-offs: enforcement of the deadline moves from the BROKER to the CONSUMER, and that is a real
     * loss rather than a relabelling. The reference broker discarded an expired message itself -- the
     * expiry is set by {@code MOVE 50 TO MQMD-EXPIRY} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 750, in TENTHS of a second, so five
     * seconds -- and the guarantee was therefore uniform and unconditional: no program could act on a stale
     * request because none was ever handed one, and a consumer that forgot to check could not go wrong. The
     * target queue has no per-message time to live, so the deadline travels as a message attribute and each
     * consumer must honour it, which means a future consumer that omitted the check would act on a request
     * the reference would never have delivered. What is gained is that the deadline becomes inspectable
     * data rather than broker state: it appears on the message, a drop leaves a record naming why, and both
     * are assertable -- which is why this case asserts the drop AND the record rather than either alone,
     * since each half on its own is satisfied by a defect in the other direction. The alternatives,
     * including keeping a broker that has the feature, are recorded in
     * {@code docs/adr/ADR-004-messaging.md}.</p>
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
        // WHY : Refactoring Rationale: the three stubs moved into the card-parameterised overload below and
        //       this method delegates, because the committed request fixtures carry a different card number
        //       and two copies of the same three stubs would drift the moment one of the account values
        //       changed. The no-argument form is kept because most cases have no interest in which card.
        givenResolvableCard(CARD_NUM);
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

    /**
     * An amount EQUAL to the available credit is approved, and one cent more is declined.
     *
     * <p>Assumptions: the comparison the two halves straddle is
     * {@code IF WS-TRANSACTION-AMT > WS-AVAILABLE-AMT} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 668, where the STRICT operator sits
     * on the REFUSAL branch, so equality falls through to the approval. The available amount is the
     * summary's credit limit minus its credit balance, computed at its lines 666 and 667.</p>
     *
     * <p>Assumptions: both directions are asserted in one case because a single direction is satisfied by
     * an off-by-one in the other. An inclusive comparison on the refusal branch would decline the exact
     * boundary and still pass a case that only sent one cent over; a comparison that ignored the last
     * cent would approve one cent over and still pass a case that only sent the boundary.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an amount equal to the available credit is approved and one cent more is declined")
    void anAmountEqualToTheAvailableCreditIsApprovedAndOneCentMoreIsDeclined() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryWithRoom()));

        // WHY : Assumptions: the boundary value is the summary's own arithmetic and not a number chosen
        //       for the test -- summaryWithRoom() sets a credit limit of 5000.00 against a credit balance
        //       of zero, so the available amount IS 5000.00. Writing the boundary as a literal here would
        //       leave the case passing if the fixture's limit changed underneath it.
        this.listener.onRequest(messageFor(requestFor(Money.of("5000.00")), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthDetail> atBoundary = ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details).save(atBoundary.capture());
        assertEquals(AuthorizationDecisionService.RESP_CODE_APPROVED,
                atBoundary.getValue().getAuthRespCode(),
                "an amount equal to the available credit falls through the strict comparison at L668");
        assertEquals(AuthorizationDecisionService.RESP_REASON_APPROVED,
                atBoundary.getValue().getAuthRespReason());

        clearInvocationsKeepingStubs();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(
                messageFor(requestFor(Money.of("5000.01"), "TXN000000000002"), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthDetail> oneCentOver = ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details).save(oneCentOver.capture());
        assertEquals(AuthorizationDecisionService.RESP_CODE_DECLINED,
                oneCentOver.getValue().getAuthRespCode());
        // WHY : Assumptions: the reason is the insufficient-funds branch at L706 and not the not-found
        //       branch at L704, because this card, its account and its customer all resolved. Asserting
        //       the reason rather than only the response code is what separates the two: both decline with
        //       '05' at L688, and only the reason says which condition selected it.
        assertEquals(AuthorizationDecisionService.DeclineReason.INSUFFICIENT_FUND.responseReason(),
                oneCentOver.getValue().getAuthRespReason());
    }

    /**
     * An approval carries the WHOLE requested amount, and a decline carries none of it.
     *
     * <p>Assumptions: the reference program has no partial authorization at all. Its decline branch moves
     * literal zero into both the reply's approved amount and its own working total at lines 689 and 690,
     * and its approval branch moves the REQUEST'S amount into both at lines 694 and 695 -- so the approved
     * amount is either the requested amount exactly or zero, and no third value is reachable. A consumer
     * that approved part of a request would be inventing a behaviour this wire cannot express, because the
     * reply has one amount field and no partial indicator.</p>
     *
     * <p>Assumptions: approval is the DEFAULT and not an outcome the paragraph has to reach.
     * {@code 5000-PROCESS-AUTH} opens with {@code SET APPROVE-AUTH TO TRUE} at line 441 and optimistically
     * sets the card-found and account-found conditions at lines 445 and 446 before reading anything, so a
     * request is approved unless some later branch declines it. This case therefore asserts the shape of
     * the default path, and the sibling cases assert each way out of it.</p>
     *
     * <p>Assumptions: the reason is asserted on the approval as well as the amount, because
     * {@code MOVE '0000' TO PA-RL-AUTH-RESP-REASON} at line 698 runs UNCONDITIONALLY, BEFORE the
     * {@code IF AUTH-RESP-DECLINED} at line 699 that guards the selection. An approval therefore always
     * carries {@code '0000'}, and it carries it as a pre-set rather than as a selected value -- which is
     * easy to miss when reading the paragraph, because the pre-set sits above the branch that appears to
     * choose every reason.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an approval carries the whole requested amount and the unconditional approved reason")
    void anApprovalCarriesTheWholeRequestedAmountAndNoPartOfIt() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(messageFor(requestFor(Money.of("1234.56")), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthDetail> saved = ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details).save(saved.capture());
        assertEquals(0, new BigDecimal("1234.56").compareTo(saved.getValue().getApprovedAmount()),
                "the approved amount must be the requested amount exactly, as L694 moves it");
        assertEquals(0, new BigDecimal("1234.56").compareTo(saved.getValue().getTransactionAmount()),
                "the requested amount must survive onto the row it was decided from");
        assertEquals(AuthorizationDecisionService.RESP_REASON_APPROVED,
                saved.getValue().getAuthRespReason());

        // WHY : Assumptions: the reply is read back through the codec rather than string-matched, so this
        //       asserts the value a requester will DECODE rather than the bytes this producer happened to
        //       write. The two agree by construction, and asserting the decoded form is what makes the
        //       assertion survive a change to the edit mask that leaves the value alone.
        ArgumentCaptor<AuthReplyOutbox> published = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox).save(published.capture());
        assertEquals(0, new BigDecimal("1234.56").compareTo(
                CsvAuthCodec.decodeReply(published.getValue().getPayload()).approvedAmount().amount()));
    }

    /**
     * The reasons this consumer can emit are exactly eight, and the display table holds two it cannot.
     *
     * <p>Assumptions: eight is one approval reason plus seven decline reasons. The approval reason is the
     * unconditional pre-set at line 698, and the seven come from the eight {@code WHEN} clauses of the
     * selection at lines 700 to 717 -- eight clauses and seven codes, because the first three conditions
     * at lines 701, 702 and 703 all fall to the single {@code MOVE '3100'} at line 704.</p>
     *
     * <p>Assumptions: the seven decline codes are read from the committed fixture rather than written here,
     * so the enumeration this consumer publishes and the enumeration the fixtures pin cannot drift apart.
     * The fixture is one reply record per code, so its own count is the assertion that no code is missing
     * and none is duplicated.</p>
     *
     * <p>Trade-offs: two of the seven decline reasons are reachable through this consumer and five are
     * not, and the five are declared anyway. That is the reference program's own gap rather than this
     * migration's -- it declares five decline conditions at its lines 141 to 145 and SETS exactly one of
     * them -- so the constants exist to keep the published enumeration complete and auditable while no
     * input selects the other five. The cost accepted is that a reader may take the enumeration for a set
     * of outcomes all of which occur; the alternative, declaring only what occurs, would silently drop
     * five values of an externally observable four-character contract.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("this consumer's reason enumeration is exactly eight, and two table entries are display-only")
    void everyReasonThisConsumerEmitsIsOneOfEightAndTwoTableEntriesAreDisplayOnly() {
        Set<String> emitted = new LinkedHashSet<>();
        emitted.add(AuthorizationDecisionService.RESP_REASON_APPROVED);
        for (AuthorizationDecisionService.DeclineReason reason
                : AuthorizationDecisionService.DeclineReason.values()) {
            emitted.add(reason.responseReason());
        }

        assertEquals(8, emitted.size(),
                "one approval reason and seven decline reasons, the three not-found conditions counting once");
        assertThat(emitted).containsExactly("0000", "3100", "4100", "4200", "4300", "5100", "5200", "9000");

        // WHY : Assumptions: the fixture's reason column sits at the same offset in every one of its seven
        //       records because the reply is a fixed-width frame -- sixteen, fifteen, six, two and four
        //       characters with one delimiter after each -- so the reason is decoded rather than sliced,
        //       which keeps this assertion independent of that arithmetic.
        List<String> fromFixture = new ArrayList<>();
        for (String record : linesOf(DECLINED_REASONS_FIXTURE)) {
            fromFixture.add(CsvAuthCodec.decodeReply(record).authRespReason());
        }
        assertEquals(7, fromFixture.size(), "one committed reply record per decline reason");
        assertThat(fromFixture)
                .as("the fixture must pin every decline reason the enumeration declares, and no other")
                .containsExactlyInAnyOrderElementsOf(
                        emitted.stream()
                                .filter(reason ->
                                        !AuthorizationDecisionService.RESP_REASON_APPROVED.equals(reason))
                                .toList());

        // WHY : Assumptions: the detail screen's table is a SUPERSET by exactly two, and neither entry is
        //       an error to be corrected. COPAUS1C.cbl lines 58 to 67 declare ten entries; eight of them
        //       are the values asserted above, and the daily-limit entry at line 63 and the lost-card
        //       entry at line 66 are display-only -- a renderer that can name a reason another producer
        //       might send is deliberately broader than the producer. Asserting their ABSENCE from this
        //       consumer's enumeration is what records the split without changing either side.
        assertThat(emitted).doesNotContainAnyElementsOf(DISPLAY_ONLY_REASONS);
    }

    /**
     * All three not-found conditions decline with ONE reason, and that reason outranks insufficient funds.
     *
     * <p>Assumptions: the selection at lines 700 to 717 lists the three not-found conditions as three
     * subjects of its FIRST branch -- {@code CARD-NFOUND-XREF} at line 701,
     * {@code NFOUND-ACCT-IN-MSTR} at line 702 and {@code NFOUND-CUST-IN-MSTR} at line 703 -- all reaching
     * the single {@code MOVE '3100'} at line 704. A test that read {@code '3100'} as "card not found"
     * alone would therefore be asserting a third of the branch, so each of the three is driven here and
     * named where it is driven.</p>
     *
     * <p>Assumptions: the third condition is reached with a decline ALREADY detected, and that is a
     * property of the reference program rather than a convenience. A missing customer never sets the
     * decline flag -- nothing between lines 665 and 683 does -- and the selection runs only inside
     * {@code IF AUTH-RESP-DECLINED} at line 699, so a missing customer with funds available is APPROVED
     * there and here. The condition becomes observable when some other branch has already declined, and
     * then it OUTRANKS the funds reason because it sits earlier in the selection: an over-limit request on
     * an account whose customer is missing answers {@code '3100'} and not {@code '4100'}.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("all three not-found conditions decline with one reason, which outranks insufficient funds")
    void bothOfTheNotFoundConditionsReachedHereDeclineWithTheSameReason() {
        String notFound = AuthorizationDecisionService.DeclineReason.NOT_FOUND.responseReason();

        // WHY : Assumptions: condition one, CARD-NFOUND-XREF at L701. The cross-reference resolves
        //       nothing, so the gated reads at L450 to L457 do not happen and the decline has no account
        //       to measure against -- which is the bare SET DECLINE-AUTH at L681.
        when(this.accounts.findCardXref(CARD_NUM)).thenReturn(Optional.empty());
        this.listener.onRequest(messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE));
        assertEquals(notFound, reasonOfOnlyReply());

        clearInvocationsKeepingStubs();

        // WHY : Assumptions: condition two, NFOUND-ACCT-IN-MSTR at L702. The card resolves and the
        //       account master does not, which is the outcome L451's read leaves behind. The summary is
        //       absent too, so the decline again arrives through L681 rather than through the funds test.
        when(this.accounts.findCardXref(CARD_NUM)).thenReturn(
                Optional.of(new AccountContextClient.CardXref(ACCOUNT_ID, CUSTOMER_ID)));
        when(this.accounts.findAccount(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(this.accounts.customerExists(CUSTOMER_ID)).thenReturn(true);
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        this.listener.onRequest(
                messageFor(requestFor(Money.of("100.99"), "TXN000000000002"), ALLOWED_REPLY_QUEUE));
        assertEquals(notFound, reasonOfOnlyReply());

        clearInvocationsKeepingStubs();

        // WHY : Assumptions: condition three, NFOUND-CUST-IN-MSTR at L703, driven together with an
        //       over-limit amount because the missing customer alone declines nothing. The account's
        //       available credit is its limit minus its posted balance -- L674 and L675, the fallback arm
        //       taken when no summary exists -- so 6000.00 against a 5000.00 limit selects the funds
        //       branch, and the assertion is that the EARLIER branch wins anyway.
        when(this.accounts.findCardXref(CARD_NUM)).thenReturn(
                Optional.of(new AccountContextClient.CardXref(ACCOUNT_ID, CUSTOMER_ID)));
        when(this.accounts.findAccount(ACCOUNT_ID)).thenReturn(
                Optional.of(new AccountContextClient.Account(new BigDecimal("5000.00"),
                        new BigDecimal("500.00"), new BigDecimal("0.00"))));
        when(this.accounts.customerExists(CUSTOMER_ID)).thenReturn(false);
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        this.listener.onRequest(
                messageFor(requestFor(Money.of("6000.00"), "TXN000000000003"), ALLOWED_REPLY_QUEUE));

        assertEquals(notFound, reasonOfOnlyReply());
        assertThat(reasonOfOnlyReply())
                .as("the not-found branch at L704 precedes the funds branch at L706 in one selection")
                .isNotEqualTo(AuthorizationDecisionService.DeclineReason.INSUFFICIENT_FUND
                        .responseReason());
    }

    /**
     * The widest declared amount reaches the decision whole, and a token missing a cent is refused.
     *
     * <p>Refactoring Rationale: this is divergence D-D's sibling D-H, and what was wrong is arithmetic
     * rather than stylistic. {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} line 27 declares
     * {@code PA-RQ-TRANSACTION-AMT PIC +9(10).99}, which is FOURTEEN characters, while the only consumer
     * of that wire declares {@code WS-TRANSACTION-AMT-AN PIC X(13)} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 63 -- THIRTEEN -- and the
     * {@code UNSTRING} beginning at line 354 delivers the amount token into it as the ninth receiver at
     * line 364. An alphanumeric move into a shorter item drops the LAST character, and the
     * {@code FUNCTION NUMVAL} conversion at lines 376 and 377 then runs on the mutilated text and accepts
     * one fraction digit as readily as two. The reference consumer therefore acts on a plausible wrong
     * number with no diagnostic anywhere. Here the whole token is read, and a token that genuinely lost a
     * cents digit is REFUSED.</p>
     *
     * <p>Assumptions: the defect is INVISIBLE whenever the hundredths digit is zero, which is why this
     * case uses the second of the three committed records and not the first or the third. The first spells
     * {@code -0000000250.00} and the third {@code +0000000000.00}; both end in a zero, so dropping their
     * final character leaves a value that converts to the same amount and the truncation cannot be
     * observed. Only the second, {@code +9999999999.99}, exposes it: truncated to
     * {@code +9999999999.9} it converts to 9999999999.90 and loses nine cents.</p>
     *
     * <p>Assumptions: the refusal follows the house precedent for a malformed monetary record rather than
     * inventing a policy. {@code tests/fixtures/README.md} lines 145 to 151 record that the existing
     * readers reject a row whose length is not exactly the record length -- they do not pad it, do not
     * truncate it and do not drop it -- and give the reason at lines 150 and 151, that a malformed
     * monetary record must never be silently coerced into a well-formed-looking one. Coercing here would
     * divide an amount by ten in the cents position, which is precisely that.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the widest declared amount is decoded whole while a token missing a cent is refused")
    void theMaximumDeclaredAmountIsDecodedWholeWhileAMutilatedOneIsRefused() {
        String widestRecord = linesOf(AMOUNT_VARIANTS_FIXTURE).get(1);
        assertThat(widestRecord)
                .as("record two of the committed fixture is the only one whose final digit is not a zero")
                .contains("+9999999999.99");
        givenResolvableCard(FIXTURE_CARD_NUM);
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(wireMessage(widestRecord).build());

        ArgumentCaptor<PendingAuthDetail> saved = ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details).save(saved.capture());
        // WHY : Assumptions: the value is asserted in its PLAIN STRING form as well as by comparison,
        //       because the two failures this guards against are different. A comparison alone would pass
        //       for a value carried at a different scale, and this amount is also the strongest available
        //       evidence that nothing on the path routed it through binary floating point: 9999999999.99
        //       has no exact double representation, so a value that had been through one would arrive as
        //       9999999999.9899999999906867742538452148437500 and could not print as this.
        assertEquals("9999999999.99", saved.getValue().getTransactionAmount().toPlainString(),
                "the fourteenth character the reference receiver discards must survive to the row");
        assertEquals(0, MAXIMUM_DECLARED_AMOUNT.compareTo(saved.getValue().getTransactionAmount()));
        // WHY : Assumptions: the accumulation is the declined arm because this amount exceeds the
        //       summary's available credit, and the amount it accumulates is the REQUEST'S -- L821. So the
        //       whole token is observable twice over: on the row it was decided from and in the total it
        //       moved.
        verify(this.summaries).addDeclinedAuthorization(ACCOUNT_ID, saved.getValue()
                .getTransactionAmount());

        clearInvocationsKeepingStubs();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryWithRoom()));
        // WHY : Assumptions: the mutilated payload is built by removing the token's LAST character, which
        //       is exactly what a thirteen-character receiving item does to a fourteen-character move. It
        //       is not the same as the thirteen-character form the codec deliberately accepts: that one
        //       lost its leading SIGN position and still carries two fraction digits, whereas this one
        //       lost a cents digit, and the parser requires exactly two.
        Message<String> mutilated =
                wireMessage(widestRecord.replace("+9999999999.99", "+9999999999.9")).build();

        assertThrows(AuthMessageFormatException.class, () -> this.listener.onRequest(mutilated));

        verify(this.details, never()).save(any(PendingAuthDetail.class));
        verify(this.outbox, never()).save(any(AuthReplyOutbox.class));
    }

    /**
     * Every amount this consumer stores, accumulates or publishes is exact at two decimal places.
     *
     * <p>Assumptions: this is asserted here rather than inherited, because the architecture rule that bans
     * binary floating point from the money path is scoped to the shared money package and does not reach
     * this module. Rule T3 of the migration plan requires exact fixed point at every hop -- scale two with
     * half-up rounding in the code, and a STRING on the wire so that no consumer parses the value into a
     * binary double -- and the three hops this consumer owns are the persisted row, the accumulated total
     * and the published payload, so all three are asserted together.</p>
     *
     * <p>Assumptions: the wire hop is asserted as the TEXT of a fixed-width field rather than as a number.
     * The reply is a delimited character payload -- the reference program composes it with a
     * {@code STRING} at lines 722 to 731 -- so its amount is fourteen characters of text, and a payload
     * carrying it as text cannot be routed through a floating-point type by the transport. The width is
     * the declared one from {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy} line 24.</p>
     *
     * <p>Assumptions: the amount chosen has a repeating binary expansion, so a value that had passed
     * through a double would not print back as itself. A round number would satisfy this case under a
     * defect it is written to catch.</p>
     *
     * <p>Assumptions: the reply's amount is rendered in the reference program's EMITTED mask rather than in
     * its copybook's declared one, and the difference is recorded here rather than asserted as a divergence
     * because it belongs to the codec and mapper boundary and not to this consumer. The reply is composed
     * at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 722 to 731 from
     * {@code WS-APPROVED-AMT-DIS}, declared {@code PIC -zzzzzzzzz9.99} at its line 66 -- zero-suppressed,
     * with a blank sign position for a positive value -- whereas
     * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURLY.cpy} line 24 declares
     * {@code PA-RL-APPROVED-AMT PIC +9(10).99}, zero-filled with an explicit sign. That field is populated
     * at lines 689 and 694 and is then never put on the wire at all. Both forms are fourteen characters, so
     * the sixty-three-character payload and its six delimiters are unaffected and the frame the sibling
     * reply case asserts holds under either. The committed REQUEST fixtures carry the copybook's zero-filled
     * form and the committed REPLY fixtures carry the emitted zero-suppressed one, which is what this
     * consumer publishes -- so the assertion below is on the WIDTH and the digits, and not on the padding
     * character.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every stored, accumulated and published amount is exact fixed point at scale two")
    void everyAmountThisConsumerCarriesIsExactAtTwoDecimalPlaces() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(messageFor(requestFor(Money.of("1000.10")), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthDetail> saved = ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details).save(saved.capture());
        assertEquals(Money.SCALE, saved.getValue().getTransactionAmount().scale(),
                "a stored amount at any other scale would compare equal and render differently");
        assertEquals(Money.SCALE, saved.getValue().getApprovedAmount().scale());
        assertEquals("1000.10", saved.getValue().getTransactionAmount().toPlainString());

        ArgumentCaptor<BigDecimal> accumulated = ArgumentCaptor.forClass(BigDecimal.class);
        verify(this.summaries).addApprovedAuthorization(anyLong(), accumulated.capture());
        assertEquals(Money.SCALE, accumulated.getValue().scale());
        assertEquals("1000.10", accumulated.getValue().toPlainString());

        ArgumentCaptor<AuthReplyOutbox> published = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox).save(published.capture());
        String amountField = published.getValue().getPayload()
                .split(String.valueOf(CsvAuthCodec.DELIMITER))[CsvAuthCodec.REPLY_AMOUNT_ORDINAL];
        assertEquals(CsvAuthCodec.MONEY_EDITED_WIDTH, amountField.length(),
                "the published amount is fourteen characters of TEXT, never a numeric type");
        assertThat(amountField).endsWith("1000.10");

        // WHY : Assumptions: half-up is the rounding this migration fixes for general money arithmetic,
        //       and it is asserted rather than assumed because the money type also publishes a SECOND mode
        //       for one purpose -- the interest accrual reproduces the reference program's truncation. A
        //       reader finding two modes on one type is owed the statement of which one this path uses.
        assertEquals(java.math.RoundingMode.HALF_UP, Money.GENERAL_ROUNDING);

        // WHY : Assumptions: the third hop is the HTTP surface this service also publishes, and there money
        //       leaves as a JSON STRING rather than as a JSON number. A number is parsed into a binary
        //       double by most clients, so exactness would be lost at the boundary a caller actually reads
        //       -- which is why the quotation marks are asserted and not only the digits.
        assertEquals("\"1000.10\"",
                JsonMapper.builder().addModule(new MoneyModule()).build()
                        .writeValueAsString(Money.of("1000.10")));
    }

    /**
     * An approval accumulates the APPROVED amount and a decline accumulates the REQUESTED amount.
     *
     * <p>Refactoring Rationale: this is the accumulation half of divergence D-F, and the asymmetry it
     * asserts is DESIGN rather than defect, so it is reproduced faithfully. The approval arm of
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} adds one to the approved count at line 814
     * and the APPROVED amount to the approved total at line 815; the decline arm adds one to the declined
     * count at line 820 and the TRANSACTION amount to the declined total at line 821. Two different
     * amounts, one per arm.</p>
     *
     * <p>Assumptions: the asymmetry is intentional because a second, independently written program mirrors
     * it exactly. {@code CBPAUP0C.cbl} reverses an expiring authorization by subtracting the approved
     * amount at line 289 on its approved branch and the transaction amount at line 292 on its declined
     * branch, selecting between them on the stored response code at line 287. Two programs agreeing on an
     * asymmetry is what distinguishes a rule from a slip, and it is why symmetrising the two arms here --
     * which would look tidier -- would leave the purge unable to reverse what this accumulates.</p>
     *
     * <p>Assumptions: the decline is the case where the two amounts genuinely DIFFER, and that is why it
     * carries the sharper assertion. A declined row records an approved amount of zero, so the amount its
     * total accumulates appears nowhere on the row it accumulated for; an implementation that read the
     * amount off the row would accumulate zero and no count would look wrong.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an approval accumulates the approved amount and a decline the requested amount")
    void anApprovalAccumulatesTheApprovedAmountAndADeclineTheRequestedAmount() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(messageFor(requestFor(Money.of("400.40")), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthDetail> approved = ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details).save(approved.capture());
        verify(this.summaries).addApprovedAuthorization(ACCOUNT_ID, approved.getValue()
                .getApprovedAmount());
        verify(this.summaries, never()).addDeclinedAuthorization(anyLong(), any(BigDecimal.class));

        clearInvocationsKeepingStubs();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(
                messageFor(requestFor(Money.of("7000.00"), "TXN000000000002"), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthDetail> declined = ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details).save(declined.capture());
        assertEquals(0, BigDecimal.ZERO.compareTo(declined.getValue().getApprovedAmount()),
                "a decline approves nothing, so its approved amount is the literal zero of L689");
        verify(this.summaries).addDeclinedAuthorization(ACCOUNT_ID, new BigDecimal("7000.00"));
        // WHY : Assumptions: the declined total takes the amount the requester ASKED for, which on a
        //       decline is not the amount the row records as approved. Asserting the two are different is
        //       what makes the asymmetry observable rather than merely described.
        assertThat(new BigDecimal("7000.00"))
                .as("the amount the declined total accumulates is not the amount the row approved")
                .isNotEqualByComparingTo(declined.getValue().getApprovedAmount());
        verify(this.summaries, never()).addApprovedAuthorization(anyLong(), any(BigDecimal.class));
    }

    /**
     * An account whose authorizations are all declined still carries a declined count and total.
     *
     * <p>Refactoring Rationale: this is the STATE that makes divergence D-F observable, and it exists here
     * so the guard asserted in {@code PurgeJobTest} has a precondition that can be reached.
     * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} line 156 reads
     * {@code IF PA-APPROVED-AUTH-CNT &lt;= 0 AND PA-APPROVED-AUTH-CNT &lt;= 0} -- the approved counter on
     * both sides of the conjunction, with {@code PA-DECLINED-AUTH-CNT} never tested -- and it guards the
     * root delete at line 157. An account in exactly this state, no approvals and live declines, therefore
     * satisfies that guard and has its parent removed from beneath children that have not aged. The target
     * guards both counters, and this case supplies the state.</p>
     *
     * <p>Assumptions: the counters are two-byte binary fields, {@code PA-APPROVED-AUTH-CNT} and
     * {@code PA-DECLINED-AUTH-CNT} declared {@code PIC S9(04) COMP} at
     * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} lines 27 and 28, so they are asserted as
     * short integers and not as amounts. The two totals beside them are packed decimal at scale two and
     * are asserted as amounts.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an account with no approvals still accumulates its declined count and total")
    void aSummaryWithNoApprovalsStillAccumulatesItsDeclinedCounters() {
        // WHY : Assumptions: the account is given a limit SMALLER than the request so the decline comes
        //       from the funds branch on a card that fully resolves. Declining by leaving the card
        //       unresolved would record nothing at all -- the write is guarded by the cross-reference at
        //       L463 -- so no counter would move and the case would assert nothing.
        when(this.accounts.findCardXref(CARD_NUM)).thenReturn(
                Optional.of(new AccountContextClient.CardXref(ACCOUNT_ID, CUSTOMER_ID)));
        when(this.accounts.findAccount(ACCOUNT_ID)).thenReturn(
                Optional.of(new AccountContextClient.Account(new BigDecimal("100.00"),
                        new BigDecimal("50.00"), new BigDecimal("50.00"))));
        when(this.accounts.customerExists(CUSTOMER_ID)).thenReturn(true);
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        this.listener.onRequest(messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthSummary> created = ArgumentCaptor.forClass(PendingAuthSummary.class);
        verify(this.summaries).insertSummaryIfAbsent(created.capture());
        assertEquals(0, created.getValue().getApprovedAuthCount().intValue(),
                "no approval has happened, which is the half of L156's conjunction that is tested");
        assertEquals(1, created.getValue().getDeclinedAuthCount().intValue(),
                "a live decline is the half L156 never tests, and the reason the parent must survive");
        assertEquals(0, BigDecimal.ZERO.compareTo(created.getValue().getApprovedAuthAmount()));
        assertEquals(0, new BigDecimal("100.99").compareTo(created.getValue().getDeclinedAuthAmount()));
        // WHY : Assumptions: a decline reserves nothing, so the credit balance stays where it was. The
        //       reference decline arm at L819 to L821 moves the count and the declined total and touches
        //       neither balance, which is what makes a declined authorization free of credit consequence.
        assertEquals(0, BigDecimal.ZERO.compareTo(created.getValue().getCreditBalance()));

        clearInvocationsKeepingStubs();
        PendingAuthSummary alreadyDeclining = new PendingAuthSummary(ACCOUNT_ID, CUSTOMER_ID);
        alreadyDeclining.refreshLimits(new BigDecimal("100.00"), new BigDecimal("50.00"));
        alreadyDeclining.recordDeclined(new BigDecimal("100.99"));
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(alreadyDeclining));

        this.listener.onRequest(
                messageFor(requestFor(Money.of("200.99"), "TXN000000000002"), ALLOWED_REPLY_QUEUE));

        verify(this.summaries).addDeclinedAuthorization(ACCOUNT_ID, new BigDecimal("200.99"));
        verify(this.summaries, never()).addApprovedAuthorization(anyLong(), any(BigDecimal.class));
    }

    /**
     * An approval leaves the held cash balance at zero whatever the transaction class says.
     *
     * <p>Assumptions: the reference approval arm zeroes the cash balance UNCONDITIONALLY. Its lines 814,
     * 815 and 817 move the count, the approved total and the credit balance, and line 818 then executes
     * {@code MOVE 0 TO PA-CASH-BALANCE} with no condition of any kind above it -- not on the processing
     * code, not on the authorization type, not on the amount. This is carried forward as observable
     * behaviour rather than as an obviously intended rule: an account holding a cash balance has it reset
     * by the next purchase authorization it receives.</p>
     *
     * <p>Assumptions: the request carries a CASH-ADVANCE processing code precisely so the case can show
     * that nothing branches on it. A purchase-coded request would leave the cash balance at zero for the
     * uninteresting reason that nothing had put anything in it, and would pass against an implementation
     * that credited a cash advance to the cash balance.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an approval leaves the cash balance at zero whatever the processing code says")
    void anApprovalLeavesTheCashBalanceAtZeroWhateverTheProcessingCode() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        this.listener.onRequest(messageFor(
                requestWithProcessingCode(Money.of("100.99"), CASH_ADVANCE_PROCESSING_CODE),
                ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthSummary> created = ArgumentCaptor.forClass(PendingAuthSummary.class);
        verify(this.summaries).insertSummaryIfAbsent(created.capture());
        assertEquals(0, BigDecimal.ZERO.compareTo(created.getValue().getCashBalance()),
                "L818 zeroes the cash balance with no condition above it");
        // WHY : Assumptions: the credit balance is asserted beside it because the pair is the property.
        //       The approval reserves the amount against CREDIT -- L817 -- and zeroes CASH, so a value in
        //       the cash balance and none in the credit balance would be the same amount in the wrong
        //       member, which asserting either one alone cannot distinguish.
        assertEquals(0, new BigDecimal("100.99").compareTo(created.getValue().getCreditBalance()));
        // WHY : Assumptions: the processing code itself is asserted to have reached the row unchanged, so
        //       this case cannot pass by the code having been normalised away before the decision. The
        //       field is a six-digit display value, PA-RQ-PROCESSING-CODE at CCPAURQY.cpy L26.
        ArgumentCaptor<PendingAuthDetail> saved = ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details).save(saved.capture());
        assertEquals(CASH_ADVANCE_PROCESSING_CODE, saved.getValue().getProcessingCode());
    }

    /**
     * A newly recorded authorization carries no fraud mark and no fraud report date.
     *
     * <p>Assumptions: the reference insert clears both fields before writing --
     * {@code MOVE SPACE TO PA-AUTH-FRAUD PA-FRAUD-RPT-DATE} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 908 and 909, immediately after the
     * match status is selected at lines 902 to 906. It writes a BLANK and not a null, because a
     * hierarchical segment has no null: every byte of the segment is written on every insert. The target
     * writes neither value at all and leaves the two columns unset, which is why the column's own check
     * constraint admits the absent AND the blank state alongside the two fraud states -- see
     * {@code CHECK (auth_fraud IN ('F', 'R') OR auth_fraud IS NULL OR auth_fraud = ' ')} at line 703 of
     * {@code V1__authorization.sql}. A constraint admitting only the two fraud states would have rejected
     * every row the extract load carries from the reference segment.</p>
     *
     * <p>Assumptions: the absence is asserted rather than left implicit because the fraud state is the one
     * field on this row that a later transition WRITES. A decision that arrived already marked would look
     * to the fraud report exactly like one an analyst had marked, and no constraint would refuse it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a newly recorded authorization carries no fraud mark and no fraud report date")
    void theRecordedAuthorizationCarriesNoFraudMarkOfAnyKind() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthDetail> saved = ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details).save(saved.capture());
        assertNull(saved.getValue().getAuthFraud(),
                "an originated authorization is unmarked; the two fraud states are later transitions");
        assertNull(saved.getValue().getFraudReportDate());
        assertThat(saved.getValue().getAuthFraud())
                .as("neither fraud state may be present on a row this consumer has just decided")
                .isNotEqualTo(PendingAuthDetail.FRAUD_REPORTED)
                .isNotEqualTo(PendingAuthDetail.FRAUD_REMOVED);
    }

    /**
     * A delivery that fails ends there, and the delivery after it is decided on its own payload alone.
     *
     * <p>Refactoring Rationale: this is divergence D-D, and what was wrong is a missing flag.
     * {@code 3100-READ-REQUEST-MQ} at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 386
     * to 434 handles a receive that failed for any reason other than no-message-available at line 418 by
     * setting an error location at line 419, the critical level at line 420, the subsystem at line 421, a
     * message at line 426 and the card number as the event key at line 428, and performing the error
     * paragraph at line 429 -- and then setting NEITHER the no-more-messages condition nor the loop-end
     * flag. Read on its own that returns to the loop at line 326 with the five-hundred-character get buffer
     * declared at line 103 UNCHANGED, so the extract at lines 354 and 355 splits the PREVIOUS request again
     * and an already-committed authorization is decided a second time.</p>
     *
     * <p>Assumptions: the corroborating tell is at line 428, which logs {@code PA-CARD-NUM} -- and that
     * field still holds the previous message's card number, because the receive that failed never
     * populated a new one. A stale card number in the error record is direct evidence that the buffer
     * behind it is stale, and it is the reason this reads as a real reprocessing path rather than as a
     * theoretical one.</p>
     *
     * <p>Assumptions: in the target there is no buffer to leave unchanged. Each delivery carries its own
     * payload into the handler as a parameter, so a failed delivery cannot present a stale one and the
     * shape is unreachable rather than guarded against. What this case asserts is the pair of consequences
     * that makes that claim checkable: the failed delivery writes nothing and its exception leaves the
     * handler, so the transport redelivers it and the redrive policy dead-letters it; and the delivery
     * after it is decided on its own payload, with the committed one untouched and never decided twice.</p>
     *
     * <p>Assumptions: the diagnostic goes to centralized structured logging and NOT to a queue. There is no
     * error-queue publication anywhere on this path -- the only row this consumer ever writes to the outbox
     * is a reply -- so the absence of any publication from the failed delivery is asserted, and the
     * accompanying log record is asserted on the refusal case below where this consumer emits one
     * itself.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a failed delivery ends there and the next is decided on its own payload alone")
    void aMessageWhoseHandlingFailedLeavesTheNextMessageUnaffected() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(
                messageFor(requestFor(Money.of("100.99"), "TXN000000000001"), ALLOWED_REPLY_QUEUE));

        // WHY : Assumptions: the fault is injected at the account-context seam because that is the one
        //       collaborator of this handler that reaches outside the process, so a failure there is the
        //       closest available analogue of the infrastructure fault the reference receive path handles.
        //       The do-form of the stub is used rather than the when-form because the when-form would have
        //       to CALL the already-stubbed method to record the new answer, which would consume the
        //       stubbed value and count as an invocation in the assertions below.
        doThrow(new IllegalStateException("the account context is unreachable"))
                .when(this.accounts).findCardXref(CARD_NUM);

        assertThrows(IllegalStateException.class, () -> this.listener.onRequest(
                messageFor(requestFor(Money.of("100.99"), "TXN000000000002"), ALLOWED_REPLY_QUEUE)));

        // WHY : Assumptions: the seam is restored with the do-form for the same reason it was made to fail
        //       with it. The when-form evaluates its argument, so calling the shared fixture helper here
        //       would invoke the throwing stub and the fixture itself would raise -- which is what happened
        //       when this case was first written with the helper, and it failed in the setup rather than in
        //       the assertion.
        doReturn(Optional.of(new AccountContextClient.CardXref(ACCOUNT_ID, CUSTOMER_ID)))
                .when(this.accounts).findCardXref(CARD_NUM);
        this.listener.onRequest(
                messageFor(requestFor(Money.of("100.99"), "TXN000000000003"), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthDetail> recorded = ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details, times(2)).save(recorded.capture());
        assertThat(recorded.getAllValues())
                .as("the failed delivery must record nothing, and neither committed one may repeat")
                .extracting(PendingAuthDetail::getTransactionId)
                .containsExactly("TXN000000000001", "TXN000000000003");

        ArgumentCaptor<AuthReplyOutbox> published = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox, times(2)).save(published.capture());
        assertThat(published.getAllValues())
                .as("a reply exists for each committed decision and for neither anything else")
                .extracting(AuthReplyOutbox::getDeduplicationId)
                .containsExactly("TXN000000000001", "TXN000000000003");
        assertThat(published.getAllValues())
                .as("every row this consumer publishes is a reply to the requester's own destination")
                .extracting(AuthReplyOutbox::getReplyQueueUrl)
                .containsOnly(ALLOWED_REPLY_QUEUE);
    }

    /**
     * A refusal this consumer detects itself is logged as a structured record and published nowhere.
     *
     * <p>Assumptions: the two halves together are the behaviour. The reference program routes every fault
     * to one error paragraph -- {@code PERFORM 9500-LOG-ERROR} appears at FOURTEEN sites in
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}, at lines 282, 316, 429, 500, 512, 547,
     * 560, 595, 608, 639, 778, 846, 931 and 975 -- and that paragraph writes a log record, so the migrated
     * equivalent of a fault is a log record and not a message. This consumer therefore publishes nothing
     * on a refusal, and a reader looking for an error queue should find the absence asserted rather than
     * merely unmentioned.</p>
     *
     * <p>Assumptions: the reference error record is one hundred and twenty-two bytes of fixed fields at
     * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy} lines 19 to 40, and two of its bytes are
     * why a structured record with SEPARATE fields is the right target rather than one formatted string.
     * {@code ERR-LEVEL} at line 25 and {@code ERR-SUBSYSTEM} at line 30 are adjacent single characters, and
     * the letter {@code 'C'} means the critical level in the first -- {@code ERR-CRITICAL} at line 29 -- and
     * the transaction monitor in the second -- {@code ERR-CICS} at line 32, against {@code ERR-MQ} of
     * {@code 'M'} at line 35. One character with two meanings one byte apart cannot survive being flattened
     * into a single field. Note also that the four-character {@code ERR-LOCATION} at line 24 holds values
     * such as {@code 'M003'} and {@code 'I004'}: those are LOCATIONS in the program and not error codes,
     * which is what the structured event name replaces them with.</p>
     *
     * <p>Assumptions: the receive-failure site classifies its subsystem as the transaction monitor at line
     * 421 while the symmetric reply-failure site classifies the same class of fault as the message
     * transport at line 770, so the two halves of one round trip are attributed to two different
     * subsystems in a persisted byte. The target reports both as messaging faults; nothing observable turns
     * on it, because the field is a log dimension rather than a control value, and it is recorded so a
     * reader comparing the two does not take the difference for a lost behaviour.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a refusal is logged as a structured record and published to no queue at all")
    void aRefusalIsRecordedInTheLogAndPublishedNowhere() {
        Logger listenerLogger =
                (Logger) LoggerFactory.getLogger(AuthorizationRequestListener.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        listenerLogger.addAppender(captured);
        Level previousLevel = listenerLogger.getLevel();
        listenerLogger.setLevel(Level.WARN);
        try {
            Message<String> elsewhere = wireMessage(
                    CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))),
                    ALLOWED_REPLY_QUEUE + "-not-listed").build();

            assertThrows(AuthMessageFormatException.class, () -> this.listener.onRequest(elsewhere));

            assertThat(captured.list)
                    .as("a fault with no record is a fault an operator cannot attribute")
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(recorded -> assertThat(recorded)
                            .contains("event=auth.request.refused")
                            .contains("reason=destination-not-allowlisted"));
            // WHY : Assumptions: the record must not carry the rejected destination, because the value is
            //       requester-supplied text bound for a log field. The allowlist's SIZE is what an
            //       operator needs to tell a missing entry from a hostile address, and it is what the
            //       record carries instead.
            assertThat(captured.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .noneSatisfy(recorded -> assertThat(recorded).contains("-not-listed"));
            verifyNoInteractions(this.outbox);
            verifyNoInteractions(this.details);
        } finally {
            listenerLogger.detachAppender(captured);
            captured.stop();
            listenerLogger.setLevel(previousLevel);
        }
    }

    /**
     * The window at its production default admits five hundred and one requests, not five hundred.
     *
     * <p>Assumptions: both numbers are correct about different things, and the second is the observable
     * one. Five hundred is what the reference program DECLARES --
     * {@code 05 WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 40, which the sibling case above pins
     * as this consumer's configured default. Five hundred and one is what it PROCESSES, and three
     * structural facts of that program together produce the extra one. Its initialisation performs a
     * priming receive BEFORE the loop, at line 246, so the loop begins with a request already in hand. Its
     * counter is advanced AFTER the request is processed, at line 332, so the count reflects work already
     * done rather than work about to be done. And the guard is written with a STRICT comparison,
     * {@code IF WS-MSG-PROCESSED &gt; WS-REQSTS-PROCESS-LIMIT} at line 339, so counts one through five
     * hundred all take the {@code ELSE} at line 341 and receive another request at line 342. The guard
     * first holds at five hundred and one -- after that request has been processed and committed -- and
     * only then is the loop-end flag set at line 340.</p>
     *
     * <p>Assumptions: the default is exercised at its real value rather than at a reduced one, because the
     * OFF-BY-ONE is the property and an implementation that enforced the declared figure would be
     * indistinguishable from a correct one at any limit if the relationship were not checked against the
     * configured default itself. The messages are dropped as stale, which is the cheapest path through the
     * handler that still consumes an admission -- the reservation is taken as the handler's first
     * statement, matching the counter that advances on the get itself.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the window at its production default admits five hundred and one requests")
    void theWindowAdmitsTheDeclaredLimitPlusTheComparisonOffsetAtItsDefault() {
        int declaredLimit = AuthorizationRequestListener.DEFAULT_REQUEST_PROCESS_LIMIT;
        int allowance = declaredLimit + AuthorizationRequestListener.BASELINE_COMPARISON_OFFSET;
        AuthorizationRequestListener atDefaultWindow = listenerWithWindow(declaredLimit);
        // WHY : Assumptions: one message object is built and re-delivered rather than five hundred and one
        //       being built, because the handler holds no per-message state -- every field of it is final
        //       -- so an identical delivery is a faithful second delivery. Building each one would spend
        //       the case's whole runtime in the encoder rather than on the property.
        Message<String> stale = expiredMessage();
        Logger listenerLogger =
                (Logger) LoggerFactory.getLogger(AuthorizationRequestListener.class);
        Level previousLevel = listenerLogger.getLevel();
        // WHY : Trade-offs: the logger is quieted for the duration and restored afterwards. Each of the
        //       five hundred and one drops emits one warn record, and five hundred and one identical
        //       records in a build report obscure every other record in it; the level is restored in the
        //       finally block because this logger is a process-wide singleton and a level left behind
        //       would silence a later case that asserts on a record.
        listenerLogger.setLevel(Level.ERROR);
        try {
            for (int admitted = 0; admitted < declaredLimit; admitted++) {
                atDefaultWindow.onRequest(stale);
            }

            assertEquals(List.of(), this.closedWindows,
                    "the declared limit alone must not close the window, because the guard is strict");

            atDefaultWindow.onRequest(stale);
        } finally {
            listenerLogger.setLevel(previousLevel);
        }

        assertEquals(List.of(allowance), this.closedWindows,
                "the window closes on the declared limit plus the comparison offset");
        assertEquals(501, this.closedWindows.get(0).intValue(),
                "five hundred declared at L40, plus the one request the L339 comparison lets through");
    }

    /**
     * The queue, its wait and the unit of work are all bound outside this handler's body.
     *
     * <p>Assumptions: the queue NAME is a deployment fact and never a literal here. The reference program
     * does not name its queue either: it retrieves the trigger data at lines 233 to 236 and moves the
     * queue name out of it at line 238, so the name arrives from outside the program. The migrated form
     * arrives from configuration through a property placeholder, which is what this case asserts -- and
     * the placeholder deliberately carries NO default, so a deployment that has not been told which queue
     * to read fails to start rather than listening to a name this class invented. Note the reference guard
     * at line 237 has no {@code ELSE}, so a failed retrieve there leaves the name blank and the program
     * continues; a startup failure is the target's answer to the same condition.</p>
     *
     * <p>Assumptions: the WAIT is five seconds, and the two figures it is derived from differ by a factor
     * of one hundred in the source with no comment saying so. {@code MOVE 5000 TO WS-WAIT-INTERVAL} at
     * line 242 feeds the receive's wait at line 393 and is in MILLISECONDS; {@code MOVE 50 TO MQMD-EXPIRY}
     * at line 750 sets the reply's expiry and is in TENTHS of a second. Both are five seconds. Either
     * literal lifted as written would be wrong by two orders of magnitude in one direction or the other,
     * which is why this case asserts that the poll wait and the reply expiry are the SAME five seconds
     * rather than asserting either number on its own.</p>
     *
     * <p>Assumptions: the UNIT OF WORK is one message, expressed as a new transaction per delivery. That
     * is the reference program's per-message {@code EXEC CICS SYNCPOINT} at lines 334 to 336, and a
     * transaction shared across a poll batch would let one malformed message roll back its neighbours'
     * committed decisions. The propagation is asserted structurally because a unit test cannot observe a
     * commit; the commit and rollback themselves are pinned against a real engine by
     * {@code AuthorizationDecisionUnitOfWorkRepositoryIT}.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws NoSuchMethodException if the handler method this class is written against is renamed or its
     *     parameter changes, in which case the contract asserted here no longer exists to assert
     */
    @Test
    @DisplayName("the queue, the five-second wait and the per-message transaction are all bound outside")
    void theTransportContractIsBoundFromConfigurationAndScopedPerMessage()
            throws NoSuchMethodException {
        Method handler = AuthorizationRequestListener.class.getMethod("onRequest", Message.class);

        SqsListener subscription = handler.getAnnotation(SqsListener.class);
        assertEquals(1, subscription.queueNames().length,
                "this consumer subscribes to exactly one queue, the pending-authorization request queue");
        String queueBinding = subscription.queueNames()[0];
        assertTrue(queueBinding.startsWith("${") && queueBinding.endsWith("}"),
                "the queue arrives from configuration, as the reference took it from its trigger data");
        assertThat(queueBinding)
                .as("a placeholder default would let an unconfigured deployment read an invented queue")
                .doesNotContain(":");

        assertEquals(AuthorizationRequestListener.DEFAULT_REPLY_EXPIRY_SECONDS,
                placeholderDefaultOf(subscription.pollTimeoutSeconds()),
                "the poll wait and the reply expiry are one duration expressed in two source units");

        Transactional unitOfWork = handler.getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRES_NEW, unitOfWork.propagation(),
                "one message is one unit of work, as the per-message syncpoint at L335 makes it");
    }

    /**
     * A drain that runs out of messages ends normally, having handled the ones it received.
     *
     * <p>Assumptions: an empty receive after the wait is a NORMAL end and not a fault. The reference
     * program's receive maps the no-message-available reason to its own loop condition at lines 416 and
     * 417 -- {@code SET NO-MORE-MSG-AVAILABLE TO TRUE} -- and the loop at line 326 then ends the poll cycle
     * cleanly, with the messages it did handle already committed one by one at lines 334 to 336. Nothing is
     * raised, nothing is logged as an error, and the count of handled messages is whatever arrived.</p>
     *
     * <p>Assumptions: in the target the poll cycle belongs to the listener container, so "no more messages"
     * is the absence of a further call rather than a value this handler returns. What is assertable, and
     * what this case asserts, is the state a drain leaves behind when it stops: every message that DID
     * arrive is decided and answered, no error record exists, and the window stays open because the
     * allowance was never reached. An implementation that treated an empty poll as a fault would show up
     * here as an error record or as a window closed early.</p>
     *
     * <p>Assumptions: the absence of an error record is asserted at a level that would have captured one.
     * The appender is attached at trace, so a record at any level would be visible, and the assertion is
     * that none of them is at error level rather than that none exists -- the handler legitimately records
     * one informational event per decision.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a drain that runs out of messages ends normally, silently, and with its window open")
    void aDrainThatRunsOutOfMessagesEndsNormallyAndSilently() {
        Logger listenerLogger =
                (Logger) LoggerFactory.getLogger(AuthorizationRequestListener.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        listenerLogger.addAppender(captured);
        Level previousLevel = listenerLogger.getLevel();
        listenerLogger.setLevel(Level.TRACE);
        try {
            givenResolvableCard();
            when(this.summaries.findByAccountId(ACCOUNT_ID))
                    .thenReturn(Optional.of(summaryWithRoom()));

            this.listener.onRequest(
                    messageFor(requestFor(Money.of("100.99"), "TXN000000000001"), ALLOWED_REPLY_QUEUE));
            this.listener.onRequest(
                    messageFor(requestFor(Money.of("100.99"), "TXN000000000002"), ALLOWED_REPLY_QUEUE));

            verify(this.details, times(2)).save(any(PendingAuthDetail.class));
            verify(this.outbox, times(2)).save(any(AuthReplyOutbox.class));
            assertThat(captured.list)
                    .as("an exhausted queue is not a fault, so nothing may be recorded as an error")
                    .noneMatch(recorded -> recorded.getLevel() == Level.ERROR);
            assertEquals(List.of(), this.closedWindows,
                    "two messages of an allowance of four leave the window open, as a short drain does");
        } finally {
            listenerLogger.detachAppender(captured);
            captured.stop();
            listenerLogger.setLevel(previousLevel);
        }
    }

    /**
     * A request is taken whatever correlation identity it carries, including none.
     *
     * <p>Assumptions: the reference receive is NON-SELECTIVE. It moves the no-match constants into both
     * selection fields before the get -- {@code MQMI-NONE} into the message identifier at line 395 and
     * {@code MQCI-NONE} into the correlation identifier at line 396 -- so it takes the next available
     * message and never matches on either. Correlation exists to ROUTE THE REPLY, which it does by echoing
     * the saved value at line 745, and never to select the request.</p>
     *
     * <p>Assumptions: three deliveries with three different identities are driven, one of them carrying
     * none at all, because selection would be invisible with one. An implementation that filtered on the
     * attribute would decide a subset, and an implementation that required the attribute would refuse the
     * third -- and the reference sets its own correlation field to a no-match constant before the read, so
     * a requester that supplies none is ordinary rather than incomplete.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a request is taken whatever correlation identity it carries, including none")
    void theRequestIsTakenWhateverCorrelationIdentityItCarries() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(wireMessage(
                CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"), "TXN000000000001")))
                .setHeader(AuthorizationRequestListener.HEADER_CORRELATION_ID, "CORRELATION-ALPHA-01")
                .build());
        this.listener.onRequest(wireMessage(
                CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"), "TXN000000000002")))
                .setHeader(AuthorizationRequestListener.HEADER_CORRELATION_ID, "CORRELATION-BETA-02")
                .build());
        this.listener.onRequest(wireMessage(
                CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"), "TXN000000000003")))
                .build());

        ArgumentCaptor<AuthReplyOutbox> published = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox, times(3)).save(published.capture());
        assertThat(published.getAllValues())
                .as("all three are decided, in the order they were delivered")
                .extracting(AuthReplyOutbox::getDeduplicationId)
                .containsExactly("TXN000000000001", "TXN000000000002", "TXN000000000003");
        // WHY : Assumptions: the echo is exact and per message, which is the routing use the attribute has
        //       here. The value is carried VERBATIM because the requester pairs the answer to its question
        //       on the opaque value it sent, and the third row's absent identity is asserted beside the two
        //       present ones so that "none" is shown to be carried as none rather than as a substitute.
        assertEquals("CORRELATION-ALPHA-01", published.getAllValues().get(0).getCorrelationId());
        assertEquals("CORRELATION-BETA-02", published.getAllValues().get(1).getCorrelationId());
        assertNull(published.getAllValues().get(2).getCorrelationId());
        assertTrue(MessagingCorrelationId.isCanonical("CORRELATION-ALPHA-01"),
                "the two identities used here are canonical, so neither is refused for its shape");
    }

    /**
     * A failing dependency is called ONCE, and recovery is left to the transport.
     *
     * <p>Assumptions: there is no in-process retry on this handler, and the framing matters because the
     * reference extension DECLARES a retry set and never uses it.
     * {@code 88 RETRY-CONDITION VALUE 'BA', 'FH', 'TE'.} appears at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 94 and in six of its seven sibling
     * programs, and a repository-wide search returns exactly those seven hits -- every one the declaration
     * itself. The condition name is never referenced anywhere. Any retry in the target is therefore a
     * faithful realisation of declared-but-unimplemented INTENT, and describing it as preserving existing
     * retry behaviour would be wrong: there is no working retry to preserve. The only implemented retry in
     * the extension is elsewhere, the schedule-and-retry of {@code COPAUS0C.cbl} lines 1007 to 1016.</p>
     *
     * <p>Alternatives Considered: a declarative retry on this handler, drawn from the framework core that
     * arrives with the platform parent -- and two details of that API are recorded because both are
     * commonly written the other way round: the attribute is {@code maxRetries}, so the total number of
     * attempts is one plus its value and defaults to three, and the enabler is
     * {@code @EnableResilientMethods} rather than the older enabling annotation. It is not applied here
     * because of WHERE it would sit: the handler's whole body is one transaction, so a second attempt
     * inside it would run against a unit of work already marked for rollback, and an attempt outside it
     * would re-decide an authorization whose first attempt may already have committed. Alternatives
     * Considered: an external resilience library, rejected because it installs a second retry authority for
     * a capability the platform already has; and a circuit breaker, rejected because the only synchronous
     * dependency reached from here is inside the private network behind an explicit connect and read
     * timeout, so a breaker would add a state machine without removing a failure mode. Any {@code includes}
     * list would also have to stay narrow: the reference set names three infrastructure statuses and
     * deliberately leaves out the not-found, duplicate, wrong-parentage and end-of-database outcomes at its
     * lines 87 to 90, because each of those describes the state of the DATA and retrying one would repeat a
     * read that has already answered.</p>
     *
     * <p>Trade-offs: the durable retry tier is therefore the transport rather than the process. The
     * exception rolls this delivery back, the message becomes visible again after its visibility timeout,
     * and the redrive policy moves it to the dead-letter queue at the fifth receive -- a depth configured
     * in {@code infra/modules/sqs} rather than in this service, which is why this case asserts the
     * PRECONDITION for that recovery, that the failure is not swallowed, and does not assert the depth
     * itself.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a failing dependency is called once and recovery is left to the transport")
    void aFailedDependencyIsCalledOnceAndRecoveryIsLeftToTheTransport() {
        doThrow(new IllegalStateException("the account context is unreachable"))
                .when(this.accounts).findCardXref(CARD_NUM);

        assertThrows(IllegalStateException.class, () -> this.listener.onRequest(
                messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE)));

        // WHY : Assumptions: exactly one call is the assertion, and it is what distinguishes no retry from
        //       a retry that happens to have exhausted itself. A handler that retried in process would call
        //       the seam again inside a transaction already doomed by the first failure.
        verify(this.accounts, times(1)).findCardXref(CARD_NUM);
        verify(this.accounts, never()).findAccount(anyLong());
        verify(this.details, never()).save(any(PendingAuthDetail.class));
        verify(this.outbox, never()).save(any(AuthReplyOutbox.class));
    }

    /**
     * Two authorizations for one card share its ordering group and keep their own duplicate identities.
     *
     * <p>Alternatives Considered: a STANDARD queue rather than an ordered one, rejected because it cannot
     * preserve the order of two authorizations on one card, and the reference system delivered them in the
     * order they were sent. Alternatives Considered: ONE global ordering group, rejected because it would
     * serialise authorizations for unrelated cards behind each other and turn a per-card guarantee into a
     * platform-wide bottleneck. Grouping by the card number keeps order where order is meaningful and
     * leaves different cards to be handled in parallel. Alternatives Considered: a duplicate identity
     * derived from the payload rather than from the transaction identifier, rejected because it would make
     * suppression depend on byte-for-byte equality of a record a requester may legitimately resend with a
     * different merchant name; the identifier gives content-independent acceptance once per transaction
     * inside the queue's own deduplication interval.</p>
     *
     * <p>Assumptions: both identities are the LITERAL values the technical specification freezes, its
     * sections 0.4.1.8 and 0.7.6 naming the card number as the group and the transaction identifier as the
     * duplicate key. A value derived from either -- which an earlier revision of this producer used -- is
     * computable only by this service, so any other producer publishing for the same card computes a
     * different group and the ordering guarantee stops holding without anything failing.</p>
     *
     * <p>Assumptions: two messages are driven rather than one, because a single message cannot show that
     * the group is SHARED while the duplicate key is not. Both carry the same card and different
     * identifiers, which is exactly the pair the guarantee is about.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("two authorizations for one card share its ordering group and keep their own duplicate keys")
    void theQueueIdentitiesAreTheCardAndTheTransaction() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(
                messageFor(requestFor(Money.of("100.99"), "TXN000000000001"), ALLOWED_REPLY_QUEUE));
        this.listener.onRequest(
                messageFor(requestFor(Money.of("200.99"), "TXN000000000002"), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<AuthReplyOutbox> published = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox, times(2)).save(published.capture());
        assertThat(published.getAllValues())
                .as("both replies belong to one card, so both carry that card as their ordering group")
                .extracting(AuthReplyOutbox::getOrderGroupId)
                .containsOnly(CARD_NUM);
        assertThat(published.getAllValues())
                .as("two transactions are two distinct answers, so neither may suppress the other")
                .extracting(AuthReplyOutbox::getDeduplicationId)
                .containsExactly("TXN000000000001", "TXN000000000002")
                .doesNotHaveDuplicates();
    }

    /**
     * A write that fails leaves NO reply, so no answer can outlive the decision it reports.
     *
     * <p>Assumptions: this is the unit-level half of divergence D-D's atomicity claim, and the reference
     * program's shape is what makes it necessary. Both of its segment writes end the same way:
     * {@code 8400-UPDATE-SUMMARY} at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 837 to
     * 847 sets the location {@code 'I003'}, the critical level, the subsystem and the message
     * {@code 'IMS UPDATE SUMRY FAILED'}, performs the error paragraph at line 846 and falls through; and
     * {@code 8500-INSERT-AUTH} at lines 920 to 932 does the same with {@code 'I004'} and
     * {@code 'IMS INSERT DETL FAILED'}, logging at line 931. Each is shaped
     * {@code IF STATUS-OK CONTINUE ELSE ... END-IF} and neither sets an abort flag.</p>
     *
     * <p>Assumptions: combine those two seams with the ORDER of the paragraph that calls them and the
     * worst case follows. The reply is put at line 461 and the database write is performed at lines 463 to
     * 465, AFTER it -- so a detail insert that fails leaves the requester holding an approval that no row
     * accounts for, on an otherwise normal run, with nothing raised. The target inverts the order and puts
     * both in one unit of work: the reply is written into the outbox in the same transaction as the rows,
     * so either both commit or neither does, and this case asserts the observable half of that at unit
     * level -- a failed row write reaches no reply at all. The durable half, that a rollback removes both,
     * is pinned against a real engine by {@code AuthorizationDecisionUnitOfWorkRepositoryIT}.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a write that fails leaves no reply, so no answer outlives the decision it reports")
    void aFailedWriteLeavesNoReplyForADecisionThatDidNotCommit() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryWithRoom()));
        doThrow(new IllegalStateException("the authorization row could not be written"))
                .when(this.details).save(any(PendingAuthDetail.class));

        assertThrows(IllegalStateException.class, () -> this.listener.onRequest(
                messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE)));

        // WHY : Assumptions: the reply is written AFTER the rows in this handler, which is the inversion of
        //       the reference order and the reason this assertion holds without a transaction manager. A
        //       consumer that published first -- as the reference does at L461 -- would leave a row here
        //       even though the write that justified it failed.
        verify(this.outbox, never()).save(any(AuthReplyOutbox.class));
    }

    /**
     * The reply is routed by the request, carries the declared format and expires, and asks for no reply.
     *
     * <p>Assumptions: every one of these comes from {@code 7100-SEND-RESPONSE} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 738 to 779. The destination is the
     * queue the REQUEST named, moved in at line 742 from the value the receive saved at lines 413 and 414.
     * The correlation identifier is echoed at line 745. The format is declared at line 751 as a string
     * payload, which is the delimited character record this consumer publishes as its own content type. The
     * expiry is set at line 750, in tenths of a second, and is the five seconds this consumer carries as an
     * instant on the row.</p>
     *
     * <p>Assumptions: the reply is TERMINAL, and in the target that is structural rather than cleared. The
     * reference blanks its own reply-to queue and queue manager at lines 747 and 748, so the answer cannot
     * itself request an answer; here the publication type declares exactly one destination member and no
     * second one, so there is nothing to blank. The count of destination-valued members is asserted, which
     * is what would notice a reply-to being added to the publication later.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the reply is routed by the request, declares its format, expires, and asks for no reply")
    void theReplyIsRoutedByTheRequestAndIsTerminal() {
        givenResolvableCard();
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryWithRoom()));
        Message<String> request = wireMessage(
                CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))), ALLOWED_REPLY_QUEUE)
                .setHeader(AuthorizationRequestListener.HEADER_CORRELATION_ID, "CORRELATION-GAMMA-03")
                .build();

        this.listener.onRequest(request);

        ArgumentCaptor<AuthReplyOutbox> published = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox).save(published.capture());
        assertEquals(ALLOWED_REPLY_QUEUE, published.getValue().getReplyQueueUrl(),
                "the destination is the one the request named, as L742 takes it from the request");
        assertEquals("CORRELATION-GAMMA-03", published.getValue().getCorrelationId(),
                "the correlation identity is echoed verbatim, as L745 echoes the saved value");
        assertEquals(AuthReplyOutbox.CONTENT_TYPE_CSV, published.getValue().getContentType());
        assertEquals(java.time.LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC)
                        .plusSeconds(AuthorizationRequestListener.DEFAULT_REPLY_EXPIRY_SECONDS),
                published.getValue().getExpiresAt(),
                "the deadline is the reference expiry of L750, decoded from tenths into seconds");

        long destinations = java.util.Arrays.stream(OutboxMessage.class.getRecordComponents())
                .filter(component -> component.getName().endsWith("QueueUrl"))
                .count();
        assertEquals(1, destinations,
                "a publication names where it goes and nowhere to answer it, so the reply is terminal");
    }

    /**
     * The committed canonical wire record is decided exactly as it stands on disk.
     *
     * <p>Assumptions: the fixture is driven as the PAYLOAD rather than being re-encoded from a decoded
     * form, so this asserts that the listener decides the bytes a producer actually sends. The codec's own
     * round-trip proofs live with the codec; what is asserted here is the service boundary, that a
     * byte-exact committed record is accepted, decided, recorded and answered.</p>
     *
     * <p>Assumptions: the wire is one hundred and seventy characters and its eighteen declared field widths
     * sum to one hundred and fifty-three, the difference being the seventeen delimiters between them. The
     * frame is asserted from the codec's own constants rather than from literals, because the field ORDER,
     * COUNT and DELIMITER are the contract on a string-format payload -- there is no field name anywhere on
     * the wire, so a field inserted, removed or reordered changes the meaning of every field after it with
     * nothing to notice.</p>
     *
     * <p>Assumptions: the recorded row is keyed by the account the CROSS-REFERENCE resolved and not by
     * anything the request carries, which is {@code MOVE XREF-ACCT-ID TO PA-ACCT-ID} at line 911 -- the
     * statement that establishes the parent the two-level insert at lines 913 to 919 hangs its child from.
     * The request has no account field at all, so the linkage can only come from the resolved
     * cross-reference, and asserting it here is what pins the child to the right parent.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the committed canonical wire is decided as it stands and hangs from the resolved account")
    void theCommittedCanonicalWireIsDecidedAsItStands() {
        String canonical = linesOf(CANONICAL_WIRE_FIXTURE).get(0);
        assertEquals(CsvAuthCodec.REQUEST_WIRE_LENGTH, canonical.length(),
                "the committed record is the published wire length, terminator excluded");
        assertEquals(CsvAuthCodec.REQUEST_FIELD_COUNT - 1,
                canonical.chars().filter(each -> each == CsvAuthCodec.DELIMITER).count(),
                "eighteen fields are separated by seventeen delimiters, with none after the last");
        assertEquals(CsvAuthCodec.REQUEST_WIRE_LENGTH,
                CsvAuthCodec.REQUEST_DECLARED_WIDTH_SUM + CsvAuthCodec.REQUEST_FIELD_COUNT - 1,
                "the wire is the declared widths plus one delimiter between each pair of fields");
        givenResolvableCard(FIXTURE_CARD_NUM);
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(wireMessage(canonical).build());

        ArgumentCaptor<PendingAuthDetail> saved = ArgumentCaptor.forClass(PendingAuthDetail.class);
        verify(this.details).save(saved.capture());
        assertEquals(FIXTURE_CARD_NUM, saved.getValue().getCardNum());
        assertEquals(0, new BigDecimal("250.00").compareTo(saved.getValue().getTransactionAmount()));
        assertEquals(ACCOUNT_ID, saved.getValue().getId().getAccountId(),
                "the child hangs from the account the cross-reference resolved, as L911 establishes it");
        ArgumentCaptor<AuthReplyOutbox> published = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox).save(published.capture());
        assertEquals(CsvAuthCodec.REPLY_WIRE_LENGTH, published.getValue().getPayload().length());
    }

    /**
     * Discards every recorded interaction while leaving the fixture's stubbing in place.
     *
     * <p>Alternatives Considered: resetting the mocks, which is what the older cases in this class do.
     * Rejected for the cases that use this helper because a reset discards STUBBING as well as recorded
     * calls, so the shared fixture's row-count answers have to be re-applied afterwards or the listener's
     * own consistency check fails the case for a reason unrelated to what it asserts. Clearing invocations
     * keeps the fixture intact and leaves each case to re-stub only the read it varies.</p>
     */
    private void clearInvocationsKeepingStubs() {
        org.mockito.Mockito.clearInvocations(this.summaries, this.details, this.outbox, this.accounts);
    }

    /**
     * Decodes the response reason from the one reply this consumer has published so far.
     *
     * <p>Assumptions: the reason is read back through the codec rather than sliced out of the payload by
     * offset, so a case asserting a reason does not also depend on the frame's field arithmetic -- which is
     * asserted, once, by the case that owns the wire length.</p>
     *
     * @return the four-character response reason the published reply carries, never {@code null}
     */
    private String reasonOfOnlyReply() {
        ArgumentCaptor<AuthReplyOutbox> published = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox).save(published.capture());
        return CsvAuthCodec.decodeReply(published.getValue().getPayload()).authRespReason();
    }

    /**
     * Reads a committed fixture from the classpath as its records.
     *
     * <p>Assumptions: the resource is read as bytes and split on the line terminator rather than through a
     * line-oriented reader, so a file's FINAL terminator does not silently become an extra empty record.
     * The fixtures are byte-exact artifacts whose lengths are documented per file, and a reader that
     * invented a record would make a count assertion meaningless.</p>
     *
     * @param resource the absolute classpath name of the fixture; must name a committed fixture
     * @return the fixture's records in file order, never {@code null} and never containing an empty record
     * @throws IllegalStateException if the classpath holds no such fixture, which means a committed
     *     resource was moved or renamed rather than that a test input is missing
     * @throws UncheckedIOException if the fixture cannot be read
     */
    private List<String> linesOf(String resource) {
        try (InputStream bytes = getClass().getResourceAsStream(resource)) {
            if (bytes == null) {
                throw new IllegalStateException("the committed fixture " + resource
                        + " is not on the test classpath");
            }
            String content = new String(bytes.readAllBytes(), StandardCharsets.UTF_8);
            List<String> records = new ArrayList<>();
            for (String candidate : content.split("\n")) {
                if (!candidate.isEmpty()) {
                    records.add(candidate);
                }
            }
            return records;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the committed fixture " + resource + " could not be read",
                    unreadable);
        }
    }

    /**
     * Reads the default out of a property placeholder.
     *
     * <p>Assumptions: the placeholder's default is the value an unconfigured deployment runs with, so it is
     * the value a test asserting a documented default has to read. Parsing it here rather than repeating
     * the number is what keeps the assertion about the binding rather than about a copy of it.</p>
     *
     * @param placeholder a property placeholder of the form {@code ${name:default}}; must carry a default
     * @return the integer default the placeholder declares
     * @throws IllegalArgumentException if the placeholder declares no default, because a caller asserting
     *     one is then asserting against a value that does not exist
     */
    private int placeholderDefaultOf(String placeholder) {
        int separator = placeholder.indexOf(':');
        if (separator < 0 || !placeholder.endsWith("}")) {
            throw new IllegalArgumentException("the placeholder " + placeholder
                    + " declares no default to read");
        }
        return Integer.parseInt(
                placeholder.substring(separator + 1, placeholder.length() - 1).trim());
    }

    /**
     * Stubs a cross-reference, an account and a customer that all resolve for the card supplied.
     *
     * <p>Assumptions: the card is a parameter because the committed request fixtures carry their own
     * synthetic card number, which differs from this class's own constant. A case driving a fixture record
     * against the wrong stub would exercise the unresolved-card path while appearing to exercise whatever
     * the fixture varies.</p>
     *
     * @param cardNum the card number the cross-reference resolves; must not be {@code null}
     */
    private void givenResolvableCard(String cardNum) {
        when(this.accounts.findCardXref(cardNum)).thenReturn(
                Optional.of(new AccountContextClient.CardXref(ACCOUNT_ID, CUSTOMER_ID)));
        when(this.accounts.findAccount(ACCOUNT_ID)).thenReturn(
                Optional.of(new AccountContextClient.Account(new BigDecimal("5000.00"),
                        new BigDecimal("500.00"), new BigDecimal("0.00"))));
        when(this.accounts.customerExists(CUSTOMER_ID)).thenReturn(true);
    }

    /**
     * Builds a request whose transaction class is the processing code supplied.
     *
     * <p>Assumptions: the processing code is the only field varied, so a case asserting that nothing
     * branches on it varies nothing else that could account for the outcome.</p>
     *
     * @param amount the transaction amount the request carries; must not be {@code null}
     * @param processingCode the six-digit processing code the request declares; must be digits only
     * @return the request, never {@code null}
     */
    private AuthRequest requestWithProcessingCode(Money amount, String processingCode) {
        return new AuthRequest("250801", "104530", CARD_NUM, "0100", "1230", "0100", "POS001",
                processingCode, amount, "5411", "840", "05", "MERCHANT0000001",
                "TEST MERCHANT NAME 01", "SPRINGFIELD", "IL", "627010000", TRANSACTION_ID);
    }
}
