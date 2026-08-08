package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives {@link PurgeJob} over the expiry, reversal, delete-guard and checkpoint behaviour it migrates.
 *
 * <p><b>Purpose.</b> {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} walks every summary and
 * every authorization beneath it, expires the aged ones, reverses each out of its parent's running totals,
 * and deletes a summary left with nothing pending. Three of those behaviours diverge deliberately from the
 * reference program, and each divergence is asserted here rather than merely documented -- a divergence
 * nothing tests is indistinguishable from a defect.
 *
 * <p>Assumptions: the fixture rows are BUILT rather than decoded from the committed segment files, and the
 * reason is specific. The purge's subject is arithmetic over decoded columns, and the committed
 * {@code pautdtl-purge-children.bin} family carries its key field UNCOMPLEMENTED -- it is enrolled in the
 * raw geometry and round-trip cases, which never complement -- so decoding it through the entity path
 * would refuse before any arithmetic ran. The VALUES here are the fixture's: two approved authorizations of
 * 100.00 and 200.00 summing to the parent's 300.00, and two declined of 50.00 and 100.00 summing to its
 * 150.00, with the parent's counters at two and two. The year-boundary pair's Julians, 23365 and 24001, are
 * likewise the committed {@code pautdtl1-newyear-pair.bin} values.
 *
 * <p>Assumptions: the transaction boundary is a REAL {@link TransactionTemplate} over a stubbed manager
 * rather than a mocked template. The number of boundaries opened is the observable form of the checkpoint
 * frequency, so it has to be counted; mocking the template would count calls to a method whose contract
 * the test itself would then be defining.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
@ExtendWith(MockitoExtension.class)
class PurgeJobTest {

    /** The account the fixture summary belongs to. */
    private static final Long ACCOUNT_ID = 10_000_000_001L;

    /** The customer the fixture account belongs to. */
    private static final Long CUSTOMER_ID = 451L;

    /** The response code that marks an authorization approved, from {@code CIPAUDTY.cpy} L31. */
    private static final String APPROVED = "00";

    /** A response code that is not the approved one, so the declined arm is taken. */
    private static final String DECLINED = "05";

    /** An exact zero at the scale every money column stores. */
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /** Julian 24095, the fixture children's authorization date: day 95 of 2024. */
    private static final int AUTH_DATE = 24_095;

    /** The calendar date {@link #AUTH_DATE} denotes. */
    private static final LocalDate AUTHORIZED_ON = LocalDate.ofYearDay(2024, 95);

    /** Julian 23365, 31 December 2023, the older half of the committed year-boundary pair. */
    private static final int YEAR_END_DATE = 23_365;

    /** Julian 24001, 1 January 2024, the newer half and the business date the pair is purged for. */
    private static final int NEW_YEAR_DATE = 24_001;

    /** The summary repository the outer walk reads and the delete writes. */
    @Mock
    private PendingAuthSummaryRepository summaries;

    /** The authorization repository the inner walk reads and the delete writes. */
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
        when(this.transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        return new PurgeJob(this.summaries, this.details,
                new TransactionTemplate(this.transactionManager),
                Clock.fixed(AUTHORIZED_ON.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    /**
     * Answers the outer walk from a fixed set of summaries, honouring the position and the page limit.
     *
     * <p>Assumptions: the LIMIT is honoured, not ignored, and that is load-bearing for the checkpoint case
     * below. The service decides a walk is finished by receiving a page shorter than it asked for, so a
     * double that returned every matching row regardless of the limit would answer the first window with
     * everything and make one window look like the whole walk.</p>
     *
     * @param stored the summaries the walk can see
     */
    private void givenSummaries(List<PendingAuthSummary> stored) {
        when(this.summaries.findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any()))
                .thenAnswer(invocation -> {
                    long after = invocation.<Long>getArgument(0).longValue();
                    Limit limit = invocation.getArgument(1);
                    return stored.stream()
                            .filter(summary -> summary.getAccountId().longValue() > after)
                            .sorted((left, right) ->
                                    Long.compare(left.getAccountId(), right.getAccountId()))
                            .limit(limit.max())
                            .toList();
                });
    }

    /**
     * Answers the inner walk with a fixed set of authorizations beneath the fixture account.
     *
     * @param children the authorizations beneath {@link #ACCOUNT_ID}
     */
    private void givenChildren(List<PendingAuthDetail> children) {
        when(this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(any()))
                .thenReturn(children);
    }

    /**
     * Builds the fixture summary with the counters and totals the committed parent carries.
     *
     * @return a summary with two approved totalling 300.00 and two declined totalling 150.00
     */
    private static PendingAuthSummary fixtureParent() {
        return PendingAuthSummary.rehydrated(ACCOUNT_ID, CUSTOMER_ID, "Y",
                null, null, null, null, null,
                new BigDecimal("5000.00"), new BigDecimal("1000.00"),
                new BigDecimal("450.00"), ZERO,
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
        return PendingAuthDetail.rehydrated(
                new PendingAuthDetailKey(ACCOUNT_ID, authDate, authTime),
                "240404", "091500", "4000123456789010", "0100", "2712", "0100", "0000",
                "A00100", respCode, "0000", "003000",
                new BigDecimal(transactionAmount), new BigDecimal(approvedAmount),
                "5411", "840", Short.valueOf((short) 5), "MERCH000000000001", "ACME HARDWARE",
                "SEATTLE", "WA", "98101", "TXN00000000" + authTime % 1000,
                APPROVED.equals(respCode) ? PendingAuthDetail.MATCH_STATUS_PENDING
                        : PendingAuthDetail.MATCH_STATUS_DECLINED);
    }

    /**
     * An authorization one day short of the threshold survives; one exactly at it expires.
     *
     * <p>Assumptions: the boundary is INCLUSIVE, transcribing {@code IF WS-DAY-DIFF >= WS-EXPIRY-DAYS} at
     * {@code cbl/CBPAUP0C.cbl} L284. Both sides are asserted in one case because a boundary can only be
     * wrong by one and an assertion on a single side detects neither direction of that error.</p>
     */
    @Test
    @DisplayName("the expiry threshold is inclusive: exactly five days old expires, four days does not")
    void theExpiryThresholdIsInclusive() {
        PendingAuthSummary parent = fixtureParent();
        givenSummaries(List.of(parent));
        givenChildren(new ArrayList<>(List.of(
                child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00"))));

        PurgeJob.PurgeOutcome fourDays = job().purge(AUTHORIZED_ON.plusDays(4),
                PurgeJob.DEFAULT_EXPIRY_DAYS, PurgeJob.DEFAULT_CHECKPOINT_FREQUENCY);
        assertThat(fourDays.detailsRead()).isEqualTo(1);
        assertThat(fourDays.detailsDeleted()).isZero();
        verify(this.details, never()).delete(any());

        PurgeJob.PurgeOutcome fiveDays = job().purge(AUTHORIZED_ON.plusDays(5),
                PurgeJob.DEFAULT_EXPIRY_DAYS, PurgeJob.DEFAULT_CHECKPOINT_FREQUENCY);
        assertThat(fiveDays.detailsDeleted()).isEqualTo(1);
        verify(this.details, times(1)).delete(any());
    }

    /**
     * Expiring all four children reverses the parent's counters and totals to exactly zero.
     *
     * <p>Assumptions: the two arms take their amount from DIFFERENT columns -- the approved arm the
     * approved amount at {@code cbl/CBPAUP0C.cbl} L289, the declined arm the transaction amount at L292 --
     * and this fixture is built so that a single shared amount would not balance. Its declined children
     * carry an approved amount of zero, so an implementation that used the approved amount on both arms
     * would leave the declined total at 150.00 while reporting success.</p>
     *
     * <p>Assumptions: the credit balance is asserted UNCHANGED at 450.00, which is divergence
     * D-PURGE-BALANCE. The reference program releases no balance on expiry, so an implementation that
     * mirrored the approval's balance addition would fail here rather than passing silently.</p>
     */
    @Test
    @DisplayName("expiring every child drives both counters and both totals to zero and no balance moves")
    void expiringEveryChildBalancesTheParentToZero() {
        PendingAuthSummary parent = fixtureParent();
        givenSummaries(List.of(parent));
        givenChildren(new ArrayList<>(List.of(
                child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00"),
                child(AUTH_DATE, 91_500_001, APPROVED, "200.00", "200.00"),
                child(AUTH_DATE, 91_500_002, DECLINED, "50.00", "0.00"),
                child(AUTH_DATE, 91_500_003, DECLINED, "100.00", "0.00"))));

        PurgeJob.PurgeOutcome outcome = job().purge(AUTHORIZED_ON.plusDays(10),
                PurgeJob.DEFAULT_EXPIRY_DAYS, PurgeJob.DEFAULT_CHECKPOINT_FREQUENCY);

        assertThat(outcome.summariesRead()).isEqualTo(1);
        assertThat(outcome.detailsRead()).isEqualTo(4);
        assertThat(outcome.detailsDeleted()).isEqualTo(4);
        assertThat(outcome.summariesDeleted()).isEqualTo(1);

        assertThat(parent.getApprovedAuthCount()).isZero();
        assertThat(parent.getDeclinedAuthCount()).isZero();
        assertThat(parent.getApprovedAuthAmount()).isEqualByComparingTo(ZERO);
        assertThat(parent.getDeclinedAuthAmount()).isEqualByComparingTo(ZERO);
        assertThat(parent.getCreditBalance())
                .as("the reference purge releases no balance, so the reservation stands")
                .isEqualByComparingTo(new BigDecimal("450.00"));
        verify(this.summaries).delete(parent);
    }

    /**
     * A summary keeping unexpired DECLINED children is not deleted, unlike in the reference program.
     *
     * <p>Assumptions: this is divergence D-PURGE-DELETE-GUARD and it is the case the reference program gets
     * wrong. Its guard at {@code cbl/CBPAUP0C.cbl} L156 names the approved counter on BOTH sides of its
     * conjunction, so a summary whose approved count has fallen to zero satisfies it even while unexpired
     * declined authorizations remain beneath -- and the hierarchical delete then removes those live
     * children with the parent. The fixture here is exactly that shape: both approved children expire and
     * both declined children are recent.</p>
     *
     * <p>Assumptions: the declined children are asserted NOT deleted as well as the summary, because the
     * cost of the reference defect is the loss of the children rather than the loss of the parent.</p>
     */
    @Test
    @DisplayName("a summary with unexpired declined children survives, where the reference deletes it")
    void aSummaryWithLiveDeclinedChildrenIsNotDeleted() {
        PendingAuthSummary parent = fixtureParent();
        givenSummaries(List.of(parent));
        PendingAuthDetail recentDecline = child(AUTH_DATE + 10, 91_500_002, DECLINED, "50.00", "0.00");
        PendingAuthDetail anotherDecline =
                child(AUTH_DATE + 10, 91_500_003, DECLINED, "100.00", "0.00");
        givenChildren(new ArrayList<>(List.of(
                child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00"),
                child(AUTH_DATE, 91_500_001, APPROVED, "200.00", "200.00"),
                recentDecline, anotherDecline)));

        PurgeJob.PurgeOutcome outcome = job().purge(AUTHORIZED_ON.plusDays(5),
                PurgeJob.DEFAULT_EXPIRY_DAYS, PurgeJob.DEFAULT_CHECKPOINT_FREQUENCY);

        assertThat(outcome.detailsDeleted())
                .as("only the two approved children have aged past the threshold")
                .isEqualTo(2);
        assertThat(outcome.summariesDeleted()).isZero();
        assertThat(parent.getApprovedAuthCount()).isZero();
        assertThat(parent.getDeclinedAuthCount())
                .as("the declined counter is what the reference guard fails to consult")
                .isEqualTo((short) 2);
        verify(this.summaries, never()).delete(any());
        verify(this.details, never()).delete(recentDecline);
        verify(this.details, never()).delete(anotherDecline);
    }

    /**
     * An authorization one day old across a year boundary survives, rather than being read as 636 days old.
     *
     * <p>Assumptions: this is divergence D-PURGE-YEAR-BOUNDARY, and it is asserted in both directions. The
     * plain ordinal subtraction at {@code cbl/CBPAUP0C.cbl} L282 turns Julian 23365 and Julian 24001 --
     * 31 December 2023 and 1 January 2024, one day apart -- into 636, and L284's inclusive test against
     * the five-day default from L199 then deletes a one-day-old authorization. Asserting only that the
     * record survives would also pass for an implementation that had stopped expiring anything, so the
     * wrong value is named and excluded alongside.</p>
     */
    @Test
    @DisplayName("a one-day-old authorization across the year boundary survives, not read as 636 days")
    void theYearBoundaryDoesNotExpireAOneDayOldAuthorization() {
        PendingAuthSummary parent = fixtureParent();
        givenSummaries(List.of(parent));
        PendingAuthDetail yearEnd = child(YEAR_END_DATE, 235_959_999, APPROVED, "300.00", "300.00");
        givenChildren(new ArrayList<>(List.of(yearEnd)));

        long naiveDifference = (long) NEW_YEAR_DATE - YEAR_END_DATE;
        assertThat(naiveDifference)
                .as("the defect's value: a plain ordinal subtraction across the year boundary")
                .isEqualTo(636L)
                .isGreaterThanOrEqualTo(PurgeJob.DEFAULT_EXPIRY_DAYS);

        PurgeJob.PurgeOutcome outcome = job().purge(LocalDate.ofYearDay(2024, 1),
                PurgeJob.DEFAULT_EXPIRY_DAYS, PurgeJob.DEFAULT_CHECKPOINT_FREQUENCY);

        assertThat(outcome.detailsRead()).isEqualTo(1);
        assertThat(outcome.detailsDeleted())
                .as("31 December to 1 January is one day, which is below the five-day threshold")
                .isZero();
        assertThat(outcome.summariesDeleted()).isZero();
        verify(this.details, never()).delete(any());
    }

    /**
     * The walk opens one transaction per checkpoint window, honouring the frequency parameter.
     *
     * <p>Assumptions: the count of boundaries is the observable form of the reference checkpoint at
     * {@code cbl/CBPAUP0C.cbl} L161, which commits the unit of work and releases database position. Three
     * summaries at a window of one produce three working windows plus the closing window that finds
     * nothing above the last position, which is four.</p>
     *
     * <p>Assumptions: a window is the unit a failure would leave committed, which is why the frequency is
     * honoured rather than collapsed into one transaction. Asserting it here is what stops the parameter
     * becoming decorative.</p>
     */
    @Test
    @DisplayName("one transaction is opened per checkpoint window, plus the closing empty window")
    void eachCheckpointWindowCommitsSeparately() {
        List<PendingAuthSummary> page = new ArrayList<>();
        for (long offset = 0; offset < 3; offset++) {
            page.add(PendingAuthSummary.rehydrated(
                    Long.valueOf(ACCOUNT_ID + offset), CUSTOMER_ID, "Y",
                    null, null, null, null, null, ZERO, ZERO, ZERO, ZERO,
                    Short.valueOf((short) 1), Short.valueOf((short) 0), ZERO, ZERO));
        }
        givenSummaries(page);
        givenChildren(List.of());

        PurgeJob.PurgeOutcome outcome = job().purge(AUTHORIZED_ON, PurgeJob.DEFAULT_EXPIRY_DAYS, 1);

        assertThat(outcome.summariesRead()).isEqualTo(3);
        assertThat(outcome.summariesDeleted())
                .as("each summary still reports one approved authorization, so none is emptied")
                .isZero();
        verify(this.transactionManager, times(4)).getTransaction(any());
    }

    /**
     * The default entry point reads the business date from the clock and applies the reference defaults.
     *
     * <p>Assumptions: the reference program reads its date from the platform,
     * {@code ACCEPT CURRENT-YYDDD FROM DAY} at {@code cbl/CBPAUP0C.cbl} L187, and takes no date parameter
     * at all -- so the no-argument form is the reference's own path and the parameterised one exists to
     * make a run reproducible. The clock here is fixed on the authorization's own day, so nothing has aged
     * and nothing is deleted, which is what distinguishes this from the parameterised cases above.</p>
     */
    @Test
    @DisplayName("the default entry point uses the clock and the reference expiry threshold of five days")
    void theDefaultEntryPointUsesTheClockAndTheReferenceDefaults() {
        assertThat(PurgeJob.DEFAULT_EXPIRY_DAYS).isEqualTo(5);
        assertThat(PurgeJob.DEFAULT_CHECKPOINT_FREQUENCY).isEqualTo(5);

        PendingAuthSummary parent = fixtureParent();
        givenSummaries(List.of(parent));
        givenChildren(new ArrayList<>(List.of(
                child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00"))));

        PurgeJob.PurgeOutcome outcome = job().purge();

        assertThat(outcome.summariesRead()).isEqualTo(1);
        assertThat(outcome.detailsRead()).isEqualTo(1);
        assertThat(outcome.detailsDeleted()).isZero();
    }

    /**
     * A run parameter that is not positive is refused before the walk starts.
     *
     * <p>Assumptions: a threshold of zero would expire every authorization on the day it was taken and a
     * window of zero would never advance, so both are refused rather than clamped -- a clamped value would
     * run a purge the operator did not ask for.</p>
     */
    @Test
    @DisplayName("a non-positive expiry threshold or checkpoint window is refused before anything is read")
    void nonPositiveRunParametersAreRefused() {
        PurgeJob job = new PurgeJob(this.summaries, this.details,
                new TransactionTemplate(this.transactionManager), Clock.systemUTC());

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> job.purge(AUTHORIZED_ON, 0, 5))
                .withMessageContaining("expiryDays must be positive");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> job.purge(AUTHORIZED_ON, 5, -1))
                .withMessageContaining("checkpointFrequency must be positive");

        verify(this.summaries, never())
                .findByAccountIdGreaterThanOrderByAccountIdAsc(eq(0L), any());
    }
}
