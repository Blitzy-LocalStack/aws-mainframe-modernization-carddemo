package com.carddemo.authorization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.repository.OutboxRepository;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.codec.CsvAuthCodec.AuthMessageFormatException;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import com.carddemo.common.money.Money;
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
        // WHY : Refactoring Rationale: the window boundary arrives as a lambda rather than as the
        //       production container-cycling implementation. The bound is what these tests assert, and the
        //       mechanism that acts on it needs a listener container registry and a live queue; separating
        //       the two is what makes the bound assertable at all, and it is why the seam is an interface.
        this.listener = new AuthorizationRequestListener(this.summaries, this.details, this.outbox,
                new AuthorizationDecisionService(), this.accounts, List.of(ALLOWED_REPLY_QUEUE),
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), WINDOW_LIMIT,
                handled -> this.closedWindows.add(handled));
    }

    /**
     * Builds a listener whose window size is the value supplied, over the same mocks.
     *
     * @param limit the window size to configure
     * @return a listener bound to the shared mocks and the shared recording boundary
     */
    private AuthorizationRequestListener listenerWithWindow(int limit) {
        return new AuthorizationRequestListener(this.summaries, this.details, this.outbox,
                new AuthorizationDecisionService(), this.accounts, List.of(ALLOWED_REPLY_QUEUE),
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), limit,
                handled -> this.closedWindows.add(handled));
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
        when(this.summaries.findWithLockByAccountId(ACCOUNT_ID))
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
        verify(this.summaries, never()).findWithLockByAccountId(anyLong());
        verify(this.details, never()).save(any(PendingAuthDetail.class));
        verify(this.summaries, never()).save(any(PendingAuthSummary.class));

        ArgumentCaptor<AuthReplyOutbox> reply = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox).save(reply.capture());
        assertEquals(CARD_NUM, reply.getValue().getMessageGroupId());
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
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a missing summary is created with the account's limits and the decision's counters")
    void aMissingSummaryIsCreated() {
        givenResolvableCard();
        when(this.summaries.findWithLockByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        this.listener.onRequest(messageFor(requestFor(Money.of("100.99")), ALLOWED_REPLY_QUEUE));

        ArgumentCaptor<PendingAuthSummary> saved = ArgumentCaptor.forClass(PendingAuthSummary.class);
        verify(this.summaries).save(saved.capture());
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
     * A correlation attribute that does not conform to the shared rule is dropped, not persisted.
     *
     * <p>Assumptions: the request is still decided, which is the point. The queue transport drops the
     * attribute and proceeds, because a queued authorization cannot be corrected by its sender in time to
     * matter; the servlet transport refuses the request outright. Both apply the same predicate.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a nonconforming correlation attribute is dropped while the request is still decided")
    void aNonconformingCorrelationAttributeIsDropped() {
        givenResolvableCard();
        when(this.summaries.findWithLockByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));
        Message<String> message = MessageBuilder
                .withPayload(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .setHeader(AuthorizationRequestListener.HEADER_REPLY_TO, ALLOWED_REPLY_QUEUE)
                .setHeader(AuthorizationRequestListener.HEADER_CORRELATION_ID,
                        "a\",\"level\":\"ERROR")
                .build();

        this.listener.onRequest(message);

        ArgumentCaptor<AuthReplyOutbox> reply = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox).save(reply.capture());
        assertNull(reply.getValue().getCorrelationId());
        verify(this.details).save(any(PendingAuthDetail.class));
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
        when(this.summaries.findWithLockByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));
        Message<String> message = MessageBuilder
                .withPayload(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .setHeader(AuthorizationRequestListener.HEADER_REPLY_TO, ALLOWED_REPLY_QUEUE)
                .setHeader(AuthorizationRequestListener.HEADER_CORRELATION_ID, "req-0123456789ab")
                .build();

        this.listener.onRequest(message);

        ArgumentCaptor<AuthReplyOutbox> reply = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox).save(reply.capture());
        assertEquals("req-0123456789ab", reply.getValue().getCorrelationId());
    }

    /**
     * A reply destination outside the configured allowlist is not published to.
     *
     * <p>Assumptions: the decision is still recorded and only the reply is suppressed. The account's
     * history is its own and a requester does not get to withdraw an authorization by naming a bad
     * address; what it does forfeit is the answer.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unlisted reply destination is suppressed while the decision is still recorded")
    void anUnlistedReplyDestinationIsSuppressed() {
        givenResolvableCard();
        when(this.summaries.findWithLockByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(summaryWithRoom()));

        this.listener.onRequest(messageFor(requestFor(Money.of("100.99")),
                "https://sqs.us-east-1.amazonaws.com/999999999999/attacker-owned"));

        verify(this.outbox, never()).save(any(AuthReplyOutbox.class));
        verify(this.details).save(any(PendingAuthDetail.class));
        verify(this.summaries).save(any(PendingAuthSummary.class));
    }

    /**
     * A request whose expiry has already passed is dropped without being decided.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an expired request is dropped without a lookup or a reply")
    void anExpiredRequestIsDropped() {
        Message<String> message = MessageBuilder
                .withPayload(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .setHeader(AuthorizationRequestListener.HEADER_REPLY_TO, ALLOWED_REPLY_QUEUE)
                .setHeader(AuthorizationRequestListener.HEADER_EXPIRES_AT,
                        String.valueOf(FIXED_INSTANT.minusSeconds(1).toEpochMilli()))
                .build();

        this.listener.onRequest(message);

        verifyNoInteractions(this.accounts);
        verifyNoInteractions(this.outbox);
        verifyNoInteractions(this.details);
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
        ArgumentCaptor<AuthReplyOutbox> reply = ArgumentCaptor.forClass(AuthReplyOutbox.class);
        verify(this.outbox).save(reply.capture());
        assertEquals(TRANSACTION_ID, reply.getValue().getDeduplicationId());
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
     * Wraps a request as a message carrying a reply destination and no other attribute.
     *
     * @param request the request to encode as the payload; must not be {@code null}
     * @param replyQueueUrl the reply destination attribute to set
     * @return the message, never {@code null}
     */
    private Message<String> messageFor(AuthRequest request, String replyQueueUrl) {
        return MessageBuilder.withPayload(CsvAuthCodec.encodeRequest(request))
                .setHeader(AuthorizationRequestListener.HEADER_REPLY_TO, replyQueueUrl)
                .build();
    }

    /**
     * Builds a well-formed request carrying the supplied amount.
     *
     * @param amount the transaction amount the request carries
     * @return the request, never {@code null}
     */
    private AuthRequest requestFor(Money amount) {
        return new AuthRequest("250801", "104530", CARD_NUM, "0100", "1230", "0100", "POS001",
                "000000", amount, "5411", "840", "05", "MERCHANT0000001",
                "TEST MERCHANT NAME 01", "SPRINGFIELD", "IL", "627010000", TRANSACTION_ID);
    }

    /**
     * Builds one already-expired request, which the listener drops but still counts towards its window.
     *
     * @return a message the listener will drop as stale
     */
    private Message<String> expiredMessage() {
        return MessageBuilder
                .withPayload(CsvAuthCodec.encodeRequest(requestFor(Money.of("100.99"))))
                .setHeader(AuthorizationRequestListener.HEADER_REPLY_TO, ALLOWED_REPLY_QUEUE)
                .setHeader(AuthorizationRequestListener.HEADER_EXPIRES_AT,
                        String.valueOf(FIXED_INSTANT.minusSeconds(1).toEpochMilli()))
                .build();
    }

    /**
     * A window closes on exactly its quota, not one message past it.
     *
     * <p>Assumptions: this is the assertion the whole bound exists for. The reference consumer declares a
     * limit of 500 at {@code cbl/COPAUA0C.cbl} L40 but handles 501, its counter being incremented at L332
     * and then tested with {@code >} at L339, so counts one through the limit all read another request. The
     * target enforces the DECLARED number, and the check below is what would fail if the comparison here
     * were ever loosened to reproduce the off-by-one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a window closes on exactly its declared quota, not one message past it")
    void aWindowClosesOnExactlyItsQuota() {
        for (int handled = 1; handled < WINDOW_LIMIT; handled++) {
            this.listener.onRequest(expiredMessage());
            assertEquals(List.of(), this.closedWindows,
                    "no window may close before the quota is reached");
        }

        this.listener.onRequest(expiredMessage());

        assertEquals(List.of(WINDOW_LIMIT), this.closedWindows,
                "the window must close on the quota-th message carrying the quota as its handled count");
    }

    /**
     * The counter resets at a boundary, so successive windows each close on their own full quota.
     *
     * <p>Assumptions: two windows are driven rather than one, because a counter that closed the first
     * window correctly and then never reset would show up only on the second -- and a consumer that closed
     * one window and then ran unbounded forever is the failure this asserts against.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("successive windows each close on their own full quota")
    void successiveWindowsEachCloseOnTheirOwnQuota() {
        for (int handled = 0; handled < WINDOW_LIMIT * 2; handled++) {
            this.listener.onRequest(expiredMessage());
        }

        assertEquals(List.of(WINDOW_LIMIT, WINDOW_LIMIT), this.closedWindows,
                "two full windows must produce two boundaries, each reporting the full quota");
    }

    /**
     * Every message the consumer takes off the queue counts, whatever the outcome of handling it.
     *
     * <p>Assumptions: a dropped stale request and a request that could not be decoded both count. The
     * reference program increments its counter after the get returns and before any outcome is known, at
     * {@code cbl/COPAUA0C.cbl} L332, so a request it could not act on still consumed one of its 500.
     * Counting only successful decisions would let a flood of expired or malformed requests keep one window
     * open indefinitely.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a dropped and a malformed request each count towards the window")
    void everyReceivedMessageCountsTowardsTheWindow() {
        AuthorizationRequestListener bounded = listenerWithWindow(2);

        bounded.onRequest(expiredMessage());
        assertEquals(List.of(), this.closedWindows, "one message must not close a two-message window");

        assertThrows(AuthMessageFormatException.class,
                () -> bounded.onRequest(MessageBuilder.withPayload("not,a,request")
                        .setHeader(AuthorizationRequestListener.HEADER_REPLY_TO, ALLOWED_REPLY_QUEUE)
                        .build()));

        assertEquals(List.of(2), this.closedWindows,
                "a message whose handling threw must still have occupied its place in the window");
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
