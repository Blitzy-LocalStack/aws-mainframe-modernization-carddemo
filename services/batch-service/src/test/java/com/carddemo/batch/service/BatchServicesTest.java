package com.carddemo.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.domain.BatchRun;
import com.carddemo.batch.domain.DisclosureGroup;
import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.dto.DisclosureGroupKey;
import com.carddemo.batch.dto.InterestRateLookup;
import com.carddemo.batch.repository.BatchRunRepository;
import com.carddemo.batch.repository.DisclosureGroupRepository;
import com.carddemo.batch.repository.TransactionCategoryBalanceRepository;
import com.carddemo.common.money.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Proves the four batch business services behave as the reference paragraphs they transcribe.
 *
 * <p>Purpose: these four services were declared by the package charter and absent from the tree, so the
 * rules they carry -- the two category-balance arms, the {@code DEFAULT} rate fallback, the accrual
 * formula, the generation-retention discipline and the ledger's redrive no-op -- had no implementation to
 * assert against. Each case below drives production code rather than restating it.</p>
 */
@DisplayName("the batch business services")
class BatchServicesTest {

    /** A fixed clock so every ledger timestamp is deterministic. */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2022-07-18T02:00:00Z"), ZoneOffset.UTC);

    /** The account the category-balance cases operate on. */
    private static final long ACCOUNT_ID = 11L;

    /** The category-balance key those cases use. */
    private static final TransactionCategoryBalanceId KEY =
            new TransactionCategoryBalanceId(ACCOUNT_ID, "01", "0001");

    /** The two category-balance arms, kept separately observable. */
    @Nested
    @DisplayName("the category-balance arms")
    class CategoryBalanceArms {

        /** An absent key writes a row whose balance is the amount alone. */
        @Test
        @DisplayName("write the amount alone when the key is absent")
        void createArmStoresTheAmountAlone() {
            TransactionCategoryBalanceRepository balances =
                    mock(TransactionCategoryBalanceRepository.class);
            when(balances.findByIdIs(KEY)).thenReturn(Optional.empty());

            CategoryBalanceService.Outcome outcome = new CategoryBalanceService(balances)
                    .accumulate(KEY, Money.of("100.00"));

            assertThat(outcome.arm()).isEqualTo(CategoryBalanceService.Arm.CREATED);
            assertThat(outcome.balance()).isEqualByComparingTo("100.00");
        }

        /** A present key adds to the balance that was read and leaves the arm distinguishable. */
        @Test
        @DisplayName("add to the balance that was read when the key is present")
        void updateArmStoresTheSum() {
            TransactionCategoryBalanceRepository balances =
                    mock(TransactionCategoryBalanceRepository.class);
            when(balances.findByIdIs(KEY)).thenReturn(Optional.of(
                    new TransactionCategoryBalance(KEY, new BigDecimal("100.00"))));

            CategoryBalanceService.Outcome outcome = new CategoryBalanceService(balances)
                    .accumulate(KEY, Money.of("504.77"));

            assertThat(outcome.arm()).isEqualTo(CategoryBalanceService.Arm.UPDATED);
            assertThat(outcome.balance()).isEqualByComparingTo("604.77");
        }

        /** A negative amount reduces the balance, because the reference adds a signed amount. */
        @Test
        @DisplayName("reduce the balance for a negative amount")
        void updateArmAcceptsASignedAmount() {
            TransactionCategoryBalanceRepository balances =
                    mock(TransactionCategoryBalanceRepository.class);
            when(balances.findByIdIs(KEY)).thenReturn(Optional.of(
                    new TransactionCategoryBalance(KEY, new BigDecimal("100.00"))));

            assertThat(new CategoryBalanceService(balances)
                    .accumulate(KEY, Money.of("-25.50")).balance())
                    .isEqualByComparingTo("74.50");
        }
    }

    /** The rate lookup and the accrual formula. */
    @Nested
    @DisplayName("the interest accrual")
    class InterestAccrual {

        /** The key the cases look up. */
        private final DisclosureGroupKey requested = new DisclosureGroupKey("ZEROAPR   ", "01", 5);

        /** A group that exists answers directly and records no fallback. */
        @Test
        @DisplayName("answer directly when the account's own group exists")
        void directHitRecordsNoFallback() {
            DisclosureGroupRepository groups = mock(DisclosureGroupRepository.class);
            when(groups.findByIdIs(any())).thenReturn(Optional.of(new DisclosureGroup(
                    new DisclosureGroup.DisclosureGroupId("ZEROAPR   ", "01", "0005"),
                    new BigDecimal("12.00"))));

            Optional<InterestRateLookup> lookup =
                    new InterestCalculationService(groups).resolveRate(this.requested);

            assertThat(lookup).isPresent();
            assertThat(lookup.get().defaultGroupFallbackApplied()).isFalse();
            assertThat(lookup.get().resolvedRate()).isEqualByComparingTo("12.00");
        }

        /**
         * An absent group falls back to {@code DEFAULT}, replacing the account-group component alone.
         */
        @Test
        @DisplayName("fall back to the DEFAULT group, replacing the account group alone")
        void fallbackReplacesTheAccountGroupAlone() {
            DisclosureGroupRepository groups = mock(DisclosureGroupRepository.class);
            when(groups.findByIdIs(new DisclosureGroup.DisclosureGroupId("ZEROAPR   ", "01", "0005")))
                    .thenReturn(Optional.empty());
            when(groups.findByIdIs(new DisclosureGroup.DisclosureGroupId("DEFAULT   ", "01", "0005")))
                    .thenReturn(Optional.of(new DisclosureGroup(
                            new DisclosureGroup.DisclosureGroupId("DEFAULT   ", "01", "0005"),
                            new BigDecimal("18.00"))));

            Optional<InterestRateLookup> lookup =
                    new InterestCalculationService(groups).resolveRate(this.requested);

            assertThat(lookup).isPresent();
            assertThat(lookup.get().defaultGroupFallbackApplied()).isTrue();
            assertThat(lookup.get().resolvedRate()).isEqualByComparingTo("18.00");
        }

        /** Neither the requested key nor its fallback resolving yields an empty answer. */
        @Test
        @DisplayName("answer nothing when neither the group nor DEFAULT resolves")
        void neitherKeyResolving() {
            DisclosureGroupRepository groups = mock(DisclosureGroupRepository.class);
            when(groups.findByIdIs(any())).thenReturn(Optional.empty());

            assertThat(new InterestCalculationService(groups).resolveRate(this.requested)).isEmpty();
        }

        /**
         * The accrual is the reference's own formula, multiplied before divided.
         *
         * <p>Assumptions: the datum is chosen so the two orders disagree. A balance of 1000.00 at
         * 12.00 percent gives 12000.0000 divided by 1200, which is exactly 10.00; dividing first gives
         * 0.01 at scale two, which multiplied back gives 10.00 as well -- so a coarser datum is used
         * below where the orders separate.</p>
         */
        @Test
        @DisplayName("accrue the reference formula, multiplying before dividing")
        void accrualMultipliesBeforeDividing() {
            DisclosureGroupRepository groups = mock(DisclosureGroupRepository.class);
            InterestCalculationService service = new InterestCalculationService(groups);
            InterestRateLookup lookup = InterestRateLookup.ofDirectHit(
                    new DisclosureGroupKey("DEFAULT   ", "01", 5), new BigDecimal("13.25"));

            assertThat(service.accrue(Money.of("507.03"), lookup).amount())
                    .isEqualByComparingTo(new BigDecimal("507.03")
                            .multiply(new BigDecimal("13.25"))
                            .divide(new BigDecimal("1200"), 2, java.math.RoundingMode.DOWN));
        }

        /**
         * The mode this service names is the mode the shared arithmetic actually applies.
         *
         * <p>Assumptions: {@code ACCRUAL_ROUNDING} is documentation rather than a parameter -- the
         * accrual reduces through {@code Money.monthlyInterest}, which fixes the mode -- so the
         * constant could drift from the behaviour it describes without any other case failing. This
         * asserts the two are the same value, which is what lets the constant stay where a reader of
         * this service looks for the reference's mode.</p>
         *
         * <p>Assumptions: the shared constant compared against is {@code BASELINE_INTEREST_ROUNDING},
         * the accrual's own mode, and not {@code GENERAL_ROUNDING}. Comparing against the general
         * mode is what let this service name half up while its own summary said the constant records
         * the reference's mode, which truncates.</p>
         */
        @Test
        @DisplayName("the named accrual mode is the mode the shared arithmetic applies")
        void theNamedAccrualModeIsTheSharedOne() {
            assertThat(InterestCalculationService.ACCRUAL_ROUNDING)
                    .as("a mode named here but not applied by the shared helper would mislead every"
                            + " reader of this service")
                    .isEqualTo(Money.BASELINE_INTEREST_ROUNDING);
        }

        /** A genuine zero rate accrues nothing, and is not the same outcome as an absent group. */
        @Test
        @DisplayName("accrue nothing at a genuine zero rate")
        void zeroRateAccruesNothing() {
            DisclosureGroupRepository groups = mock(DisclosureGroupRepository.class);
            InterestRateLookup lookup = InterestRateLookup.ofDirectHit(
                    new DisclosureGroupKey("ZEROAPR   ", "01", 5), new BigDecimal("0.00"));

            assertThat(new InterestCalculationService(groups)
                    .accrue(Money.of("1000.00"), lookup).amount())
                    .isEqualByComparingTo("0.00");
            assertThat(lookup.interestApplicable()).isFalse();
        }
    }

    /** The generation-retention discipline. */
    @Nested
    @DisplayName("the generation discipline")
    class GenerationDiscipline {

        /** The family and date the cases use. */
        private final DatasetFamily family = DatasetFamily.resolveByMainframeBaseName(
                "AWS.M2.CARDDEMO.TRANSACT.BKUP");

        /** The injected business date. */
        private final BusinessDate businessDate = new BusinessDate("2022-07-18");

        /**
         * The service under test.
         *
         * <p>Assumptions: the object-store client is a double and the bucket name is a placeholder,
         * because every case in this nested class exercises a decision that performs no input or output:
         * the successor rule and the retention rule both take the existing generations as an argument.
         * Supplying a double rather than an emulator keeps these cases as fast unit tests, and the
         * listing path that does reach the object store is covered separately against an emulator.</p>
         */
        private final DatasetGenerationService service =
                new DatasetGenerationService(mock(S3Client.class), "carddemo-datasets-test");

        /** An empty family yields the minimum generation number. */
        @Test
        @DisplayName("yield the minimum generation for an empty family")
        void emptyFamilyYieldsTheMinimum() {
            assertThat(this.service
                    .nextGeneration(this.family, this.businessDate, List.of()).generationNumber())
                    .isEqualTo(DatasetGeneration.MINIMUM_GENERATION_NUMBER);
        }

        /** A populated family yields one past the highest present generation. */
        @Test
        @DisplayName("yield one past the highest present generation")
        void populatedFamilyYieldsOnePastTheHighest() {
            List<DatasetGeneration> existing = List.of(
                    new DatasetGeneration(this.family, this.businessDate, 1),
                    new DatasetGeneration(this.family, this.businessDate, 3),
                    new DatasetGeneration(this.family, this.businessDate, 2));

            assertThat(this.service.nextGeneration(this.family, this.businessDate, existing)
                    .generationNumber()).isEqualTo(4);
        }

        /** The retained count is kept and nothing is scratched at or below it. */
        @Test
        @DisplayName("scratch nothing at or below the retained count")
        void scratchNothingAtTheRetainedCount() {
            List<DatasetGeneration> existing = generations(
                    DatasetGeneration.RETAINED_GENERATION_COUNT);

            assertThat(this.service.generationsToScratch(existing)).isEmpty();
        }

        /** One past the retained count scratches exactly the oldest generation. */
        @Test
        @DisplayName("scratch the oldest one past the retained count")
        void scratchTheOldestOnePastTheRetainedCount() {
            List<DatasetGeneration> existing = generations(
                    DatasetGeneration.RETAINED_GENERATION_COUNT + 1);

            assertThat(this.service.generationsToScratch(existing))
                    .singleElement()
                    .extracting(DatasetGeneration::generationNumber)
                    .isEqualTo(DatasetGeneration.MINIMUM_GENERATION_NUMBER);
        }

        /**
         * Scratched generations are returned oldest first, so a caller deletes in a stable order.
         *
         * <p>Assumptions: the expected numbers are derived from the minimum rather than written as
         * literals. The run this case builds starts at the minimum, so the three oldest of it ARE the
         * minimum and its two successors -- and writing them as literals is what made this case need
         * editing when the minimum moved from zero to one, which is exactly the coupling a derived
         * expectation removes.</p>
         */
        @Test
        @DisplayName("return scratched generations oldest first")
        void scratchedGenerationsAreOldestFirst() {
            List<DatasetGeneration> existing = generations(
                    DatasetGeneration.RETAINED_GENERATION_COUNT + 3);
            int first = DatasetGeneration.MINIMUM_GENERATION_NUMBER;

            assertThat(this.service.generationsToScratch(existing))
                    .extracting(DatasetGeneration::generationNumber)
                    .containsExactly(first, first + 1, first + 2);
        }

        /**
         * Builds a run of consecutive generations from the minimum upward.
         *
         * @param count how many to build
         * @return the generations, never {@code null}
         */
        private List<DatasetGeneration> generations(int count) {
            return java.util.stream.IntStream
                    .range(DatasetGeneration.MINIMUM_GENERATION_NUMBER,
                            DatasetGeneration.MINIMUM_GENERATION_NUMBER + count)
                    .mapToObj(number ->
                            new DatasetGeneration(this.family, this.businessDate, number))
                    .toList();
        }
    }

    /** The durable step ledger. */
    @Nested
    @DisplayName("the durable step ledger")
    class DurableStepLedger {

        /** The run and step the cases use. */
        private static final String RUN_ID = "exec-0001";

        /** The step name the cases use. */
        private static final String STEP = "PostTransactions";

        /** An unrecorded step runs its body and records the completion. */
        @Test
        @DisplayName("run an unrecorded step and record its completion")
        void unrecordedStepRunsAndRecords() {
            BatchRunRepository runs = mock(BatchRunRepository.class);
            when(runs.findByRunIdAndStepName(RUN_ID, STEP)).thenReturn(Optional.empty());
            when(runs.save(any())).thenAnswer(call -> call.getArgument(0));

            BatchStepLedger.StepOutcome outcome = new BatchStepLedger(runs, FIXED)
                    .runStep(RUN_ID, STEP, () -> BatchReturnCode.CLEAN);

            assertThat(outcome.skipped()).isFalse();
            assertThat(outcome.returnCode()).isEqualTo(BatchReturnCode.CLEAN);
            verify(runs, org.mockito.Mockito.times(2)).save(any());
        }

        /** An already-completed step is a no-op that reports its recorded code. */
        @Test
        @DisplayName("skip an already-completed step and report its recorded code")
        void completedStepIsANoOp() {
            BatchRun completed =
                    new BatchRun(RUN_ID, STEP, LocalDateTime.now(FIXED).minusMinutes(5));
            completed.markCompleted(LocalDateTime.now(FIXED),
                    (short) BatchReturnCode.SOFT_WARN.numericValue());

            BatchRunRepository runs = mock(BatchRunRepository.class);
            when(runs.findByRunIdAndStepName(RUN_ID, STEP)).thenReturn(Optional.of(completed));

            boolean[] ran = {false};
            BatchStepLedger.StepOutcome outcome = new BatchStepLedger(runs, FIXED)
                    .runStep(RUN_ID, STEP, () -> {
                        ran[0] = true;
                        return BatchReturnCode.CLEAN;
                    });

            assertThat(ran[0]).as("the body of a completed step must not run").isFalse();
            assertThat(outcome.skipped()).isTrue();
            assertThat(outcome.returnCode()).isEqualTo(BatchReturnCode.SOFT_WARN);
            verify(runs, never()).save(any());
        }

        /** A failing body marks the row failed and rethrows, so the orchestrator can catch it. */
        @Test
        @DisplayName("mark a failing step failed and rethrow")
        void failingStepIsRecordedAndRethrown() {
            BatchRunRepository runs = mock(BatchRunRepository.class);
            when(runs.findByRunIdAndStepName(RUN_ID, STEP)).thenReturn(Optional.empty());
            when(runs.save(any())).thenAnswer(call -> call.getArgument(0));

            BatchStepLedger ledger = new BatchStepLedger(runs, FIXED);

            assertThatThrownBy(() -> ledger.runStep(RUN_ID, STEP, () -> {
                throw new IllegalStateException("step failed");
            })).isInstanceOf(IllegalStateException.class).hasMessage("step failed");

            verify(runs, org.mockito.Mockito.times(2)).save(any());
        }

        /** A previously failed step is re-run rather than skipped. */
        @Test
        @DisplayName("re-run a previously failed step")
        void failedStepIsReRun() {
            BatchRun failed =
                    new BatchRun(RUN_ID, STEP, LocalDateTime.now(FIXED).minusMinutes(5));
            failed.markFailed(LocalDateTime.now(FIXED),
                    (short) BatchReturnCode.HARD_FAILURE.numericValue());

            BatchRunRepository runs = mock(BatchRunRepository.class);
            when(runs.findByRunIdAndStepName(RUN_ID, STEP)).thenReturn(Optional.of(failed));
            when(runs.save(any())).thenAnswer(call -> call.getArgument(0));

            boolean[] ran = {false};
            BatchStepLedger.StepOutcome outcome = new BatchStepLedger(runs, FIXED)
                    .runStep(RUN_ID, STEP, () -> {
                        ran[0] = true;
                        return BatchReturnCode.CLEAN;
                    });

            assertThat(ran[0]).as("the body of a failed step must run again").isTrue();
            assertThat(outcome.skipped()).isFalse();
        }
    }
}
