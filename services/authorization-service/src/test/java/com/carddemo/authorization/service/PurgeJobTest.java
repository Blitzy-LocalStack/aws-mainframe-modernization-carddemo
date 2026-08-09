package com.carddemo.authorization.service;


import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives {@link PurgeJob} over the selection, reversal, ordering, cadence and failure behaviour it carries.
 *
 * <p>Purpose: {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} walks every summary and every
 * authorization beneath it, expires the aged ones, reverses each out of its parent's running totals, and
 * removes a summary left with nothing pending. Several of those behaviours diverge deliberately from the
 * reference program and several are preserved exactly; both kinds are asserted here rather than merely
 * documented, because a divergence nothing tests is indistinguishable from a defect and a preserved
 * asymmetry nothing tests is indistinguishable from an oversight a later reader will remove.
 *
 * <p>Assumptions: the fixture rows are BUILT rather than decoded from the committed segment files, and the
 * reason is specific. The purge's subject is arithmetic over decoded columns, and the committed
 * {@code pautdtl-purge-children.bin} family carries its key field UNCOMPLEMENTED - it is enrolled in the
 * raw-geometry and round-trip cases, which never complement - so decoding it through the entity path would
 * refuse before any arithmetic ran. The VALUES here are the fixture's: two approved authorizations of
 * 100.00 and 200.00 summing to the parent's 300.00, and two declined of 50.00 and 100.00 summing to its
 * 150.00, with the parent's counters at two and two. The year-boundary ordinals 23365 and 24001 are
 * likewise the committed {@code pautdtl1-newyear-pair.bin} values.
 *
 * <p>Assumptions: the transaction boundary is a REAL {@link TransactionTemplate} over a stubbed manager
 * rather than a mocked template. The number of boundaries opened is the observable form of the commit
 * cadence, so it has to be counted; mocking the template would count calls to a method whose contract the
 * test itself would then be defining.
 *
 * <p>Assumptions: parity here rests on the copybook and schema contracts plus the transcribed logic and NOT
 * on a golden master, because no golden master exists for any path in this module. This is stated so that
 * the absence is not mistaken for one that was overlooked.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
@ExtendWith(MockitoExtension.class)
class PurgeJobTest {

    /** The account the fixture summary belongs to. */
    private static final Long ACCOUNT_ID = 10_000_000_001L;

    /** A second account, used where the walk has to cross more than one summary. */
    private static final Long SECOND_ACCOUNT_ID = 10_000_000_002L;

    /** The customer the fixture account belongs to. */
    private static final Long CUSTOMER_ID = 451L;

    /** The response code that marks an authorization approved, from {@code cpy/CIPAUDTY.cpy} L31. */
    private static final String APPROVED = "00";

    /** A response code that is not the approved one, so the declined arm is taken. */
    private static final String DECLINED = "05";

    /** An exact zero at the scale every money column stores. */
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /** The credit balance the fixture parent carries, which no purge releases. */
    private static final BigDecimal FIXTURE_CREDIT_BALANCE = new BigDecimal("450.00");

    /** Ordinal 24095, the fixture children's authorization date: day 95 of 2024. */
    private static final int AUTH_DATE = 24_095;

    /** The calendar date {@link #AUTH_DATE} denotes. */
    private static final LocalDate AUTHORIZED_ON = LocalDate.ofYearDay(2024, 95);

    /** Ordinal 23365, 31 December 2023, the older half of the committed year-boundary pair. */
    private static final int YEAR_END_DATE = 23_365;

    /** Ordinal 24001, 1 January 2024, the newer half and the business date the pair is purged for. */
    private static final int NEW_YEAR_DATE = 24_001;

    /** The difference a plain subtraction of the two year-boundary ordinals yields, from L282. */
    private static final long ORDINAL_SUBTRACTION_RESULT = NEW_YEAR_DATE - YEAR_END_DATE;

    /** The summary repository the outer walk reads and the parent delete writes. */
    @Mock
    private PendingAuthSummaryRepository summaries;

    /** The authorization repository the inner walk reads and the child delete writes. */
    @Mock
    private PendingAuthDetailRepository details;

    /** The transaction manager whose boundaries stand in for the reference checkpoints. */
    @Mock
    private PlatformTransactionManager transactionManager;

    /**
     * Builds the job with a real transaction template over the stubbed manager.
     *
     * @return the job under test
     */
    private PurgeJob job() {
        when(this.transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new PurgeJob(this.summaries, this.details,
                new TransactionTemplate(this.transactionManager));
    }

    /**
     * Answers the outer walk from a fixed set of summaries, honouring the position and the page limit.
     *
     * <p>Assumptions: the LIMIT is honoured, not ignored, and that is load-bearing for the cadence cases.
     * The service decides a walk is finished by receiving a page shorter than it asked for, so a double
     * that returned every matching row regardless of the limit would answer the first window with
     * everything and make one window look like the whole walk.
     *
     * @param stored the summaries the walk can see
     */
    private void givenSummaries(List<PendingAuthSummary> stored) {
        when(this.summaries.findAccountIdsAboveOrderByAccountIdAsc(any(), any()))
                .thenAnswer(invocation -> {
                    long after = invocation.<Long>getArgument(0).longValue();
                    Limit limit = invocation.getArgument(1);
                    return stored.stream()
                            .map(PendingAuthSummary::getAccountId)
                            .filter(accountId -> accountId.longValue() > after)
                            .sorted()
                            .limit(limit.max())
                            .toList();
                });
        // WHY : Assumptions: the walk answers KEYS and the locking read answers the summary, because that
        //       is the order the service performs them in and the double has to be able to disagree with
        //       itself. A single stub answering summaries from the walk could not express the case below
        //       where a key is returned and its row is then gone, which is the state a concurrent purge
        //       leaves behind and the one arm of this loop that has no reference equivalent to copy.
        when(this.summaries.findByAccountId(any()))
                .thenAnswer(invocation -> stored.stream()
                        .filter(summary -> summary.getAccountId().equals(invocation.getArgument(0)))
                        .findFirst());
    }

    /**
     * Answers the inner walk with a fixed set of authorizations beneath any account.
     *
     * @param children the authorizations the inner walk returns
     */
    private void givenChildren(List<PendingAuthDetail> children) {
        // WHY : Refactoring Rationale: the double answers the BOUNDED first chunk and the strict keyset
        //       continuation, and the unbounded read it answered before is gone. The purge now walks one
        //       account's authorizations in keyset chunks instead of materialising every one of them, so
        //       a double that returned the whole set to any call would let a purge which had gone back
        //       to reading everything still pass -- and unboundedness there is exactly the defect.
        when(this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(any(), any()))
                .thenAnswer(invocation -> chunk(children, null, null, invocation.getArgument(1)));
        // WHY : Assumptions: the continuation stub is LENIENT because a fixture smaller than one chunk is
        //       answered entirely by the first call, which comes back short and ends the walk. It is still
        //       declared so the cases whose fixtures do span a chunk are answered correctly, and so a
        //       reader can see the double models the whole walk rather than only its first step.
        Mockito.lenient().when(this.details.findOlderThan(any(), any(), any(), any()))
                .thenAnswer(invocation -> chunk(children, invocation.getArgument(1),
                        invocation.getArgument(2), invocation.getArgument(3)));
    }

    /**
     * Returns the bounded slice of a fixed child set that follows a stated keyset position.
     *
     * <p>Assumptions: the comparison is the STRICT descending pair the production query uses -- an earlier
     * date, or the same date and an earlier time -- so the double reproduces the boundary the real query
     * has rather than a looser one that would hide an off-by-one in the caller's position handling.</p>
     *
     * @param children the whole child set, already in the order the query returns
     * @param afterDate the authorization date of the last row handled, or {@code null} for the first chunk
     * @param afterTime the authorization time of the last row handled, or {@code null} for the first chunk
     * @param limit the greatest number of rows to return
     * @return the slice, never {@code null}
     */
    private static List<PendingAuthDetail> chunk(List<PendingAuthDetail> children, Integer afterDate,
            Integer afterTime, Limit limit) {
        return children.stream()
                .filter(child -> afterDate == null
                        || child.getId().getAuthDate() < afterDate
                        || (child.getId().getAuthDate().equals(afterDate)
                                && child.getId().getAuthTime() < afterTime))
                .limit(limit.max())
                .toList();
    }

    /**
     * Builds the fixture summary with the counters and totals the committed parent carries.
     *
     * @param accountId the account the summary belongs to
     * @return a summary with two approved totalling 300.00 and two declined totalling 150.00
     */
    private static PendingAuthSummary fixtureParent(Long accountId) {
        return PendingAuthSummary.rehydrated(accountId, CUSTOMER_ID, "Y",
                null, null, null, null, null,
                new BigDecimal("5000.00"), new BigDecimal("1000.00"),
                FIXTURE_CREDIT_BALANCE, ZERO,
                Short.valueOf((short) 2), Short.valueOf((short) 2),
                new BigDecimal("300.00"), new BigDecimal("150.00"));
    }

    /**
     * Builds one authorization beneath the fixture account.
     *
     * @param authDate the decoded ordinal authorization date
     * @param authTime the decoded time of day, which also makes each key distinct
     * @param respCode the response code, which selects the reversal arm
     * @param transactionAmount the amount the acquirer requested
     * @param approvedAmount the amount approved, zero on a decline
     * @return the authorization
     */
    private static PendingAuthDetail child(int authDate, int authTime, String respCode,
            String transactionAmount, String approvedAmount) {
        return child(authDate, authTime, respCode, transactionAmount, approvedAmount,
                APPROVED.equals(respCode) ? PendingAuthDetail.MATCH_STATUS_PENDING
                        : PendingAuthDetail.MATCH_STATUS_DECLINED);
    }

    /**
     * Builds one authorization beneath the fixture account with a stated match status.
     *
     * @param authDate the decoded ordinal authorization date
     * @param authTime the decoded time of day, which also makes each key distinct
     * @param respCode the response code, which selects the reversal arm
     * @param transactionAmount the amount the acquirer requested
     * @param approvedAmount the amount approved, zero on a decline
     * @param matchStatus the match state the row carries, which must not narrow selection
     * @return the authorization
     */
    private static PendingAuthDetail child(int authDate, int authTime, String respCode,
            String transactionAmount, String approvedAmount, String matchStatus) {
        return PendingAuthDetail.rehydrated(
                new PendingAuthDetailKey(ACCOUNT_ID, authDate, authTime),
                "240404", "091500", "4000123456789010", "0100", "2712", "0100", "0000",
                "A00100", respCode, "0000", "003000",
                new BigDecimal(transactionAmount), new BigDecimal(approvedAmount),
                "5411", "840", Short.valueOf((short) 5), "MERCH000000000001", "ACME HARDWARE",
                "SEATTLE", "WA", "98101", "TXN00000000" + authTime % 1000, matchStatus);
    }

    /**
     * An authorization one day short of the threshold survives; one exactly at it expires.
     *
     * <p>Assumptions: the boundary is INCLUSIVE, transcribing {@code IF WS-DAY-DIFF >= WS-EXPIRY-DAYS} at
     * {@code cbl/CBPAUP0C.cbl} L284. Both sides are asserted in one case because a boundary can only be
     * wrong by one and an assertion on a single side detects neither direction of that error.
     */
    @Test
    @DisplayName("the expiry threshold is inclusive: exactly five days old expires, four days does not")
    void theExpiryThresholdIsInclusive() {
        givenSummaries(List.of(fixtureParent(ACCOUNT_ID)));
        givenChildren(List.of(child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00")));

        PurgeJob.PurgeOutcome fourDays = job().purge(
                PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(4)));
        assertThat(fourDays.detailsDeleted()).isZero();
        verify(this.details, never()).delete(any());

        PurgeJob.PurgeOutcome fiveDays = job().purge(
                PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(5)));
        assertThat(fiveDays.detailsDeleted()).isEqualTo(1);
        verify(this.details, times(1)).delete(any());
    }

    /**
     * Expiring every child drives all four totals to zero and releases neither balance.
     *
     * <p>Assumptions: the two arms subtract from DIFFERENT columns, transcribing
     * {@code cbl/CBPAUP0C.cbl} L289 for the approved arm and L292 for the declined one. The fixture makes
     * that distinguishable on purpose: each declined row carries a transaction amount its approved amount
     * does not equal, so an implementation passing one amount to both arms leaves the declined total short
     * rather than at zero.
     *
     * <p>Assumptions: neither balance moves, which is the preserved asymmetry D-PURGE-BALANCE. A search for
     * {@code BALANCE} across all 386 lines of the reference program returns nothing, so the credit balance
     * the consumer reserved at {@code cbl/COPAUA0C.cbl} L817 is still standing after every authorization
     * has expired.
     */
    @Test
    @DisplayName("expiring every child zeroes all four totals and releases neither balance")
    void expiringEveryChildBalancesTheParentToZero() {
        PendingAuthSummary parent = fixtureParent(ACCOUNT_ID);
        givenSummaries(List.of(parent));
        givenChildren(List.of(
                child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00"),
                child(AUTH_DATE, 91_500_001, APPROVED, "200.00", "200.00"),
                child(AUTH_DATE, 91_500_002, DECLINED, "50.00", "0.00"),
                child(AUTH_DATE, 91_500_003, DECLINED, "100.00", "0.00")));

        PurgeJob.PurgeOutcome outcome = job().purge(
                PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(10)));

        assertThat(outcome.detailsRead()).isEqualTo(4);
        assertThat(outcome.detailsDeleted()).isEqualTo(4);
        // WHY : Refactoring Rationale: the reversal is asserted as ONE arithmetic statement carrying all
        //       four figures, and not as the state of the loaded entity. Mutating the entity per child made
        //       the write a read-modify-write over a row this walk holds no lock on, so two purges -- or a
        //       purge and a live authorization -- could each apply a reversal to the same starting value
        //       and lose one of them. Asserting the CALL is what holds the fix: a single statement, issued
        //       once per account, whose four arguments are the exact totals the expired children carried.
        //       Assumptions: neither balance appears in the statement, which preserves divergence
        //       D-PURGE-BALANCE -- the reference releases no balance here -- and asserting the absence is
        //       what keeps a later reader from "completing" the reversal by adding one.
        verify(this.summaries, times(1)).reverseExpiredAuthorizations(eq(ACCOUNT_ID), eq(2),
                argThat(amount -> amount.compareTo(new BigDecimal("300.00")) == 0), eq(2),
                argThat(amount -> amount.compareTo(new BigDecimal("150.00")) == 0));
        assertThat(outcome.summariesDeleted()).isEqualTo(1);
    }

    /**
     * A summary still holding unexpired declined authorizations survives, where the reference removes it.
     *
     * <p>Assumptions: this is divergence D-F, also D-PURGE-DELETE-GUARD. The reference guard at
     * {@code cbl/CBPAUP0C.cbl} L156 names the approved counter on both sides of its conjunction, so a
     * summary whose approved count has reached zero satisfies it even while declined children remain, and
     * the hierarchical delete then takes those children with it. The fixture expires only the approved
     * pair, which is exactly the state that distinguishes the two guards.
     */
    @Test
    @DisplayName("a summary with unexpired declined children survives, where the reference removes it")
    void aSummaryWithLiveDeclinedChildrenIsNotDeleted() {
        PendingAuthSummary parent = fixtureParent(ACCOUNT_ID);
        givenSummaries(List.of(parent));
        givenChildren(List.of(
                child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00"),
                child(AUTH_DATE, 91_500_001, APPROVED, "200.00", "200.00"),
                child(AUTH_DATE + 4, 91_500_002, DECLINED, "50.00", "0.00"),
                child(AUTH_DATE + 4, 91_500_003, DECLINED, "100.00", "0.00")));

        PurgeJob.PurgeOutcome outcome = job().purge(
                PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(5)));

        assertThat(outcome.detailsDeleted()).isEqualTo(2);
        // WHY : Assumptions: the declined side of the statement is asserted to be ZERO rather than simply
        //       left unasserted. The two live declined children must not be reversed, and a statement that
        //       reversed them would be indistinguishable from one that did not unless the argument is
        //       named -- while the summary's own counters can no longer be read for this, because the
        //       reversal is an arithmetic update against the row rather than a change to the instance.
        verify(this.summaries, times(1)).reverseExpiredAuthorizations(eq(ACCOUNT_ID), eq(2),
                argThat(amount -> amount.compareTo(new BigDecimal("300.00")) == 0), eq(0),
                argThat(amount -> amount.signum() == 0));
        assertThat(outcome.summariesDeleted()).isZero();
        verify(this.summaries, never()).delete(any());
    }

    /**
     * A one-day-old authorization across the year boundary survives instead of being read as 636 days old.
     *
     * <p>Assumptions: this is divergence D-E, also D-PURGE-YEAR-BOUNDARY. The reference recovers the stored
     * date at {@code cbl/CBPAUP0C.cbl} L280 and subtracts the ordinals at L282, which makes 31 December and
     * 1 January 636 apart, and L284's inclusive comparison against the five-day default from L199 then
     * qualifies a one-day-old row. Both values are named in the assertions so that the case cannot pass by
     * arithmetic that happens to agree at the calendar answer while still using the ordinal one.
     */
    @Test
    @DisplayName("a one-day-old authorization across the year boundary survives, not read as 636 days")
    void theYearBoundaryDoesNotExpireAOneDayOldAuthorization() {
        givenSummaries(List.of(fixtureParent(ACCOUNT_ID)));
        givenChildren(List.of(child(YEAR_END_DATE, 235_959_999, APPROVED, "100.00", "100.00")));

        assertThat(ORDINAL_SUBTRACTION_RESULT).isEqualTo(636L);
        assertThat(ORDINAL_SUBTRACTION_RESULT).isGreaterThanOrEqualTo(PurgeJob.DEFAULT_EXPIRY_DAYS);
        assertThat(LocalDate.ofYearDay(2024, 1).toEpochDay()
                - LocalDate.ofYearDay(2023, 365).toEpochDay()).isEqualTo(1L);

        PurgeJob.PurgeOutcome outcome = job().purge(
                PurgeJob.PurgeParameters.forBusinessDate(LocalDate.ofYearDay(2024, 1)));

        assertThat(outcome.detailsRead()).isEqualTo(1);
        assertThat(outcome.detailsDeleted()).isZero();
        verify(this.details, never()).delete(any());
    }

    /**
     * A matched authorization is removed like any other, because age alone selects a row.
     *
     * <p>Assumptions: nothing narrows the reference selection. {@code MATCH-STATUS} occurs zero times in
     * all 386 lines of {@code cbl/CBPAUP0C.cbl}, and that program's whole verb inventory is one
     * {@code CHKP}, two {@code DLET}, one {@code GN} and one {@code GNP}, with no {@code REPL}. This case
     * exists because sibling documentation attributes the {@code 'E'} PENDING-EXPIRED state to this program
     * at {@code domain/PendingAuthDetail.java} L142, L166 and L756, and a reader acting on that wording
     * would add a predicate here that changes which rows a run removes.
     */
    @Test
    @DisplayName("a matched authorization is deleted too: selection is age alone, with no match filter")
    void aMatchedAuthorizationIsDeletedAsWell() {
        PendingAuthSummary parent = fixtureParent(ACCOUNT_ID);
        givenSummaries(List.of(parent));
        givenChildren(List.of(
                child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00",
                        PendingAuthDetail.MATCH_STATUS_MATCHED_WITH_TRAN),
                child(AUTH_DATE, 91_500_001, APPROVED, "200.00", "200.00",
                        PendingAuthDetail.MATCH_STATUS_PENDING_EXPIRED)));

        PurgeJob.PurgeOutcome outcome = job().purge(
                PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(5)));

        assertThat(outcome.detailsDeleted()).isEqualTo(2);
        // WHY : Assumptions: the reversal counts BOTH children, which is what this case is about -- a
        //       matched authorization expires and is reversed exactly like a pending one. The count is
        //       read off the statement because the reversal no longer touches the loaded entity.
        verify(this.summaries, times(1)).reverseExpiredAuthorizations(eq(ACCOUNT_ID), eq(2),
                argThat(amount -> amount.signum() > 0), eq(0),
                argThat(amount -> amount.signum() == 0));
    }

    /**
     * Every child is removed before its parent is.
     *
     * <p>Assumptions: the order is part of the contract, not an incidental consequence of the loop. The
     * reference removes each qualifying child at {@code cbl/CBPAUP0C.cbl} L310 inside its inner loop and
     * only reaches the root delete at L335 afterwards, and the migrated schema states the same dependency
     * as a foreign key, so the reverse order would be refused by the database.
     */
    @Test
    @DisplayName("the child rows are deleted before the parent summary is")
    void childrenAreDeletedBeforeTheParent() {
        givenSummaries(List.of(fixtureParent(ACCOUNT_ID)));
        PendingAuthDetail first = child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00");
        PendingAuthDetail second = child(AUTH_DATE, 91_500_001, APPROVED, "200.00", "200.00");
        PendingAuthDetail third = child(AUTH_DATE, 91_500_002, DECLINED, "50.00", "0.00");
        PendingAuthDetail fourth = child(AUTH_DATE, 91_500_003, DECLINED, "100.00", "0.00");
        givenChildren(List.of(first, second, third, fourth));

        job().purge(PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(10)));

        InOrder order = inOrder(this.details, this.summaries);
        order.verify(this.details).delete(first);
        order.verify(this.details).delete(second);
        order.verify(this.details).delete(third);
        order.verify(this.details).delete(fourth);
        order.verify(this.summaries).delete(any());
    }

    /**
     * One transaction is opened per window, so the window size governs the commit cadence.
     *
     * <p>Assumptions: the reference commits once the count of summaries processed exceeds the frequency, at
     * {@code cbl/CBPAUP0C.cbl} L160 to L163, so the number of boundaries opened is the observable form of
     * that parameter. Three summaries at a window of one produce three working windows plus the closing
     * empty one that discovers the walk is finished.
     */
    @Test
    @DisplayName("one transaction is opened per window, plus the closing empty window")
    void eachWindowCommitsSeparately() {
        givenSummaries(List.of(fixtureParent(ACCOUNT_ID), fixtureParent(SECOND_ACCOUNT_ID),
                fixtureParent(10_000_000_003L)));
        givenChildren(List.of());

        PurgeJob.PurgeOutcome outcome = job().purge(new PurgeJob.PurgeParameters(AUTHORIZED_ON,
                PurgeJob.DEFAULT_EXPIRY_DAYS, 1, PurgeJob.DEFAULT_PROGRESS_LOG_FREQUENCY));

        assertThat(outcome.summariesRead()).isEqualTo(3);
        verify(this.transactionManager, times(4)).getTransaction(any());
    }

    /**
     * The progress frequency changes no commit, and the window size changes no progress report.
     *
     * <p>Assumptions: the two controls are independent, and this is the case that holds them apart. The
     * reference counts summaries in {@code WS-AUTH-SMRY-PROC-CNT} and compares it at
     * {@code cbl/CBPAUP0C.cbl} L160 to decide whether to commit, while it counts checkpoints in
     * {@code WS-NO-CHKP} and compares it at L360 to decide whether to display; the second gates only a
     * message. Driving the same data twice with the progress frequency changed and the window size fixed
     * must therefore produce an identical number of boundaries and an identical outcome. An implementation
     * that conflated the two would open a different number of boundaries in the second run.
     */
    @Test
    @DisplayName("the progress frequency is independent of the commit cadence")
    void theProgressFrequencyIsIndependentOfTheCommitCadence() {
        givenSummaries(List.of(fixtureParent(ACCOUNT_ID), fixtureParent(SECOND_ACCOUNT_ID),
                fixtureParent(10_000_000_003L), fixtureParent(10_000_000_004L)));
        givenChildren(List.of());

        PurgeJob job = job();
        PurgeJob.PurgeOutcome reportedEveryWindow = job.purge(new PurgeJob.PurgeParameters(
                AUTHORIZED_ON, PurgeJob.DEFAULT_EXPIRY_DAYS, 2, 1));
        PurgeJob.PurgeOutcome reportedRarely = job.purge(new PurgeJob.PurgeParameters(
                AUTHORIZED_ON, PurgeJob.DEFAULT_EXPIRY_DAYS, 2, 1_000));

        assertThat(reportedEveryWindow).isEqualTo(reportedRarely);
        assertThat(reportedEveryWindow.summariesRead()).isEqualTo(4);
        verify(this.transactionManager, times(6)).getTransaction(any());
    }

    /**
     * The three counts default to the reference values, and a non-positive one is refused.
     *
     * <p>Assumptions: the defaults are five, five and ten, from {@code cbl/CBPAUP0C.cbl} L199, L202 and
     * L205. Refusing zero is divergence D-PURGE-EXPIRY-FLOOR: the reference guards the expiry parameter
     * with a numeric test alone at L196 while guarding the two frequencies for zero as well at L201 and
     * L204, so the card shipped at {@code jcl/CBPAUP0J.jcl} L37 resolves to a threshold of zero and its
     * L284 comparison then qualifies every row dated on or before the run date.
     */
    @Test
    @DisplayName("the three counts default to five, five and ten, and zero is refused")
    void theRunParametersCarryTheReferenceDefaultsAndRefuseZero() {
        assertThat(PurgeJob.DEFAULT_EXPIRY_DAYS).isEqualTo(5);
        assertThat(PurgeJob.DEFAULT_CHECKPOINT_FREQUENCY).isEqualTo(5);
        assertThat(PurgeJob.DEFAULT_PROGRESS_LOG_FREQUENCY).isEqualTo(10);

        PurgeJob.PurgeParameters defaults = PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON);
        assertThat(defaults.expiryDays()).isEqualTo(5);
        assertThat(defaults.checkpointFrequency()).isEqualTo(5);
        assertThat(defaults.progressLogFrequency()).isEqualTo(10);
        assertThat(defaults.businessDate()).isEqualTo(AUTHORIZED_ON);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PurgeJob.PurgeParameters(AUTHORIZED_ON, 0, 5, 10))
                .withMessageContaining("expiryDays");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PurgeJob.PurgeParameters(AUTHORIZED_ON, 5, 0, 10))
                .withMessageContaining("checkpointFrequency");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PurgeJob.PurgeParameters(AUTHORIZED_ON, 5, 5, 0))
                .withMessageContaining("progressLogFrequency");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new PurgeJob.PurgeParameters(null, 5, 5, 10))
                .withMessageContaining("businessDate");
    }

    /**
     * A failure during the walk reports a non-zero exit status and no statistics.
     *
     * <p>Assumptions: the reference collapses five failure arms onto {@code 9999-ABEND}, which sets sixteen
     * at {@code cbl/CBPAUP0C.cbl} L382 and returns at L383 - before the statistics block at L171 to L178,
     * which only the other return at L180 reaches. The status is asserted to be both sixteen and non-zero
     * because it is the non-zero property an orchestrator acts on, and the cause is asserted to be attached
     * because a status with no diagnosis behind it cannot be investigated.
     */
    @Test
    @DisplayName("a failed run reports exit status sixteen, keeps the cause, and reports no statistics")
    void aFailedRunReportsTheAbendExitStatus() {
        when(this.summaries.findAccountIdsAboveOrderByAccountIdAsc(any(), any()))
                .thenThrow(new IllegalStateException("summary read failed"));

        PurgeJob job = job();
        PurgeJob.PurgeParameters parameters = PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON);

        assertThatExceptionOfType(PurgeJob.PurgeAbendException.class)
                .isThrownBy(() -> job.purge(parameters))
                .withCauseInstanceOf(IllegalStateException.class)
                .satisfies(thrown -> {
                    assertThat(thrown.exitStatus()).isEqualTo(PurgeJob.ABEND_EXIT_STATUS);
                    assertThat(thrown.exitStatus()).isEqualTo(16);
                    assertThat(thrown.exitStatus()).isNotZero();
                });
        verify(this.details, never()).delete(any());
        verify(this.summaries, never()).delete(any());
    }

    /**
     * A failure after a child has been removed leaves nothing committed for that window.
     *
     * <p>Assumptions: the reversal and the delete are ONE unit of work, so a failure inside the window
     * discards both. The reference commits only at its checkpoint, so a failure between two checkpoints
     * discards everything since the last one. What THIS case establishes is narrower than that: the
     * service ASKS for the boundary to be discarded, the manager being told to roll back and never told
     * to commit. That the engine then keeps nothing is a different claim, and a mocked manager cannot
     * carry it; it is established against a real database in
     * {@link PurgeWindowRollbackRepositoryIT#aFailedWindowDiscardsEveryWriteItMade()}, which also covers
     * the durability of an earlier window across a later failure. Reading this case as the durability
     * proof is the misreading the pair exists to prevent. Alternatives
     * Considered: asserting the status is marked rollback-only. Rejected because the template discards a
     * failed boundary by calling the MANAGER's rollback rather than by flagging the status, so against a
     * stubbed manager that assertion is vacuously false whatever the service did. Asserting on the
     * in-memory counter would be equally unsound, because a counter lives on an entity that a discarded
     * transaction never writes back either way.
     */
    @Test
    @DisplayName("a failure inside a window rolls the window back rather than committing part of it")
    void aFailureInsideAWindowRollsTheWindowBack() {
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        when(this.transactionManager.getTransaction(any())).thenReturn(status);
        givenSummaries(List.of(fixtureParent(ACCOUNT_ID)));
        givenChildren(List.of(child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00")));
        doThrow(new IllegalStateException("child delete failed"))
                .when(this.details).delete(any());

        PurgeJob job = new PurgeJob(this.summaries, this.details,
                new TransactionTemplate(this.transactionManager));
        PurgeJob.PurgeParameters parameters =
                PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(10));

        assertThatExceptionOfType(PurgeJob.PurgeAbendException.class)
                .isThrownBy(() -> job.purge(parameters));

        verify(this.transactionManager).rollback(status);
        verify(this.transactionManager, never()).commit(status);
        verify(this.summaries, never()).delete(any());
    }
    /**
     * Every summary is read before anything beneath it is read, reversed or removed.
     *
     * <p>Purpose: the reversal at {@code cbl/CBPAUP0C.cbl} L287 to L292 is per-child arithmetic over four
     * counters the online decision path also adds to, so the order in which this run issues its reads is
     * what decides which counters the deletion is judged against. This asserts that order: the walk
     * answers keys, the summary is loaded by its own read, and only then is the authorization chunk issued
     * and the child removed.
     *
     * <p>Assumptions: the UNBOUNDED child walk is asserted never to be used from this path, and that
     * assertion is the point of the test rather than a decoration. Both overloads are declared on the same
     * repository and either compiles here, so nothing but an assertion prevents a later edit restoring the
     * one that loads an account's entire authorization history inside a transaction whose size nothing
     * bounds. The unbounded overload remains declared because the unload path legitimately uses it -- it
     * copies rows out and writes nothing -- so its presence is not evidence of a defect and its absence
     * cannot be the guard.
     *
     * <p>Refactoring Rationale: this case asserted a LOCKING summary read here and asserted the unlocked
     * one never happened. The lock is withdrawn -- the lost update it guarded is closed instead by
     * reversing the four counters in ONE statement computed in the database, which is the reason
     * {@code PendingAuthSummaryRepository} publishes no locking read at all -- so the ordering property is
     * restated against the reads that survive, and the guard is moved onto the axis that still has two
     * candidates. Left as it was, its two verifications contradicted each other outright: one required the
     * summary read the other forbade, so whichever Mockito evaluated first decided the result.
     *
     * <p>Alternatives Considered: asserting only that each read was called. Rejected because the failure
     * being closed is an ORDERING failure: issuing the child walk before the summary is loaded satisfies a
     * presence assertion while deciding the deletion from counters read after the children were already in
     * hand.
     */
    @Test
    @DisplayName("the summary row is read before its children are read, reversed or removed")
    void theSummaryIsHeldBeforeAnythingBeneathItIsTouched() {
        givenSummaries(List.of(fixtureParent(ACCOUNT_ID)));
        givenChildren(List.of(child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00")));

        PurgeJob job = job();
        job.purge(PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(10)));

        InOrder order = inOrder(this.summaries, this.details);
        order.verify(this.summaries).findAccountIdsAboveOrderByAccountIdAsc(any(), any());
        order.verify(this.summaries).findByAccountId(ACCOUNT_ID);
        order.verify(this.details)
                .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(eq(ACCOUNT_ID), any());
        order.verify(this.details).delete(any());
        verify(this.summaries, never()).findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any());
        verify(this.details, never()).findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(any());
    }

    /**
     * A key whose summary is gone by the time its own read runs is skipped, and the walk still advances.
     *
     * <p>Purpose: the two reads can disagree, because a row removed between them is exactly what a
     * concurrent purge of the same window leaves behind. This asserts the arm that has no reference
     * equivalent to copy: the missing summary contributes nothing to the statistics, no authorization walk
     * is issued for it, and the position advances past its key so the walk terminates.
     *
     * <p>Assumptions: the walk is asserted to have advanced by inspecting the position the SECOND call
     * seeks above, which must be the LAST key of the first page and not the last key a summary read
     * resolved. A position that skipped back to the resolved key would re-seek the missing one on every
     * call and the run would not end, so the assertion is on the value that makes termination true rather
     * than on the absence of an exception.
     *
     * <p>Assumptions: the missing summary is not counted as read. The statistics are what the run reports
     * as its work, and the reference walk would never have returned a row that is not there, so counting
     * it would overstate the run against a program that could not have produced the count.
     */
    @Test
    @DisplayName("a summary removed between the walk and its read is skipped without stalling the walk")
    void aSummaryRemovedBeforeItsOwnReadIsSkipped() {
        PendingAuthSummary survivor = fixtureParent(ACCOUNT_ID);
        Long vanishedAccountId = Long.valueOf(ACCOUNT_ID.longValue() + 1L);
        // WHY : Assumptions: the two reads are stubbed HERE rather than through the shared helper, because
        //       the state under test is a row present to one read and absent to the other, and the helper
        //       answers both from one list so they cannot disagree. Re-stubbing the helper's walk instead
        //       would also re-enter the helper's own answer with null arguments, which is how Mockito
        //       evaluates the inner call of a second when(...) over an already-stubbed method.
        when(this.summaries.findAccountIdsAboveOrderByAccountIdAsc(any(), any()))
                .thenAnswer(invocation -> {
                    long after = invocation.<Long>getArgument(0).longValue();
                    return List.of(ACCOUNT_ID, vanishedAccountId).stream()
                            .filter(accountId -> accountId.longValue() > after)
                            .toList();
                });
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(survivor));
        when(this.summaries.findByAccountId(vanishedAccountId)).thenReturn(Optional.empty());
        givenChildren(List.of(child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00")));

        // WHY : Assumptions: the window is sized to exactly the two keys the walk returns, so the page is
        //       FULL and the run must seek again. At the default window of five a two-key page is short,
        //       which ends the walk after one call and leaves the position unobservable -- the assertion
        //       below would then be asserting against a call the run had no reason to make.
        PurgeJob.PurgeOutcome outcome = job().purge(
                new PurgeJob.PurgeParameters(AUTHORIZED_ON.plusDays(10), 5, 2, 10));

        assertThat(outcome.summariesRead()).isEqualTo(1);
        assertThat(outcome.detailsRead()).isEqualTo(1);
        assertThat(outcome.detailsDeleted()).isEqualTo(1);
        verify(this.summaries).findByAccountId(vanishedAccountId);
        // WHY : Assumptions: the overload named here is the BOUNDED one the purge actually issues.
        //       Naming the unbounded overload instead would pass against a run that walked the missing
        //       account's children in full, because that call is not the one being forbidden -- an
        //       absence assertion aimed at a method the subject never calls cannot fail.
        verify(this.details, never()).findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(
                eq(vanishedAccountId), any());
        verify(this.summaries, times(1))
                .findAccountIdsAboveOrderByAccountIdAsc(vanishedAccountId, Limit.of(2));
    }
    /**
     * Each run parameter is refused above the width its position on the reference card can express.
     *
     * <p>Purpose: only the lower half of each range was checked, so this type accepted values the reference
     * parameter card cannot hold at all -- an expiry of a thousand days in a two-digit field, a frequency of
     * a million in a five-character one. Both halves of each range are now asserted, on the widest accepted
     * value and on the first refused one, because a bound tested only from far away is a bound whose edge is
     * unknown.
     *
     * <p>Assumptions: the accepted side is asserted as well as the refused side. A check written one off --
     * refusing at the width rather than above it -- would pass a test that only tried an obviously
     * oversized value, and it would refuse the largest value an operator can legitimately configure.
     *
     * <p>Assumptions: the widths are read from the card layout recorded on {@link PurgeJob.PurgeParameters}
     * and are asserted through the published constants rather than as literals, so the test and the check
     * cannot disagree about which number is the bound. Their VALUES are asserted separately below, which is
     * what keeps this from being a test that would pass whatever the constants held.
     *
     * <p>Assumptions: the expiry consequence is worth stating because it is why this bound matters more than
     * it looks. An unbounded expiry turns the run into a no-op -- no authorization is old enough to qualify
     * -- and a completed purge that deleted nothing is indistinguishable from a correct one with nothing to
     * delete, so the failure is silent rather than loud.
     */
    @Test
    @DisplayName("each run parameter is refused above the width its card position can express")
    void eachParameterIsRefusedAboveItsCardWidth() {
        assertThat(PurgeJob.MAX_EXPIRY_DAYS)
                .as("the expiry field is two digits on the reference card")
                .isEqualTo(99);
        assertThat(PurgeJob.MAX_CARD_FREQUENCY)
                .as("both frequency fields are five characters on the reference card")
                .isEqualTo(99_999);

        assertThatCode(() -> new PurgeJob.PurgeParameters(
                AUTHORIZED_ON, PurgeJob.MAX_EXPIRY_DAYS, PurgeJob.MAX_CARD_FREQUENCY,
                PurgeJob.MAX_CARD_FREQUENCY))
                .as("the widest value each field holds must be accepted, not refused")
                .doesNotThrowAnyException();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PurgeJob.PurgeParameters(
                        AUTHORIZED_ON, PurgeJob.MAX_EXPIRY_DAYS + 1, 5, 10))
                .withMessageContaining("expiryDays must be between 1 and 99");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PurgeJob.PurgeParameters(
                        AUTHORIZED_ON, 5, PurgeJob.MAX_CARD_FREQUENCY + 1, 10))
                .withMessageContaining("checkpointFrequency must be between 1 and 99999");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PurgeJob.PurgeParameters(
                        AUTHORIZED_ON, 5, 5, PurgeJob.MAX_CARD_FREQUENCY + 1))
                .withMessageContaining("progressLogFrequency must be between 1 and 99999");
    }

    /**
     * A failed window names the window it stopped in and not the account it had reached.
     *
     * <p>Purpose: the abend message is carried into the log by the stack trace whether or not the raising
     * site intended that, so an identifier in the message is an identifier in the log for every failed
     * purge. This asserts the message locates the run by window ordinal and carries no account.
     *
     * <p>Assumptions: the account asserted absent is the one the fixture actually uses, so the assertion
     * can fail. Asserting the absence of an arbitrary number would pass against a message carrying a
     * different account, which is the same defect.
     *
     * <p>Assumptions: the exit status is asserted alongside, because the two properties travel together --
     * an orchestrator acts on the status and an operator reads the message -- and a change that dropped the
     * account by dropping the message would satisfy the absence assertion alone.
     */
    @Test
    @DisplayName("a failed window is reported by its ordinal, carrying no account identifier")
    void aFailedWindowNamesItsOrdinalRatherThanTheAccount() {
        when(this.summaries.findAccountIdsAboveOrderByAccountIdAsc(any(), any()))
                .thenThrow(new IllegalStateException("summary read failed"));

        PurgeJob job = job();
        PurgeJob.PurgeParameters parameters = PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON);

        assertThatExceptionOfType(PurgeJob.PurgeAbendException.class)
                .isThrownBy(() -> job.purge(parameters))
                .withMessageContaining("window 1")
                .withMessageNotContaining(String.valueOf(ACCOUNT_ID))
                .satisfies(thrown ->
                        assertThat(thrown.exitStatus()).isEqualTo(PurgeJob.ABEND_EXIT_STATUS));
    }
}
