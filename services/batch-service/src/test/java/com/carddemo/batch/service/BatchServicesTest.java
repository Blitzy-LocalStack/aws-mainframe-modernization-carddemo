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
import com.carddemo.batch.dto.BatchErrorEvent;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.dto.DisclosureGroupKey;
import com.carddemo.batch.dto.InterestRateLookup;
import com.carddemo.batch.job.PostTransactionsJob;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DisclosureGroupRepository;
import com.carddemo.batch.repository.TransactionRepository;
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
import org.mockito.ArgumentCaptor;
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

        /**
         * Builds the accrual service over one stubbed rate repository and mocks for the rest.
         *
         * <p>Assumptions: only the rate repository participates in these cases, because the rules
         * asserted here are the lookup, the fallback and the formula. The remaining three
         * collaborators are supplied as mocks rather than as nulls so that a case which
         * unexpectedly reached one of them fails as an unstubbed interaction naming the
         * collaborator, rather than as a null-pointer failure that names nothing.</p>
         *
         * @param groups the rate repository the case has stubbed; must not be {@code null}
         * @return the service under test, never {@code null}
         */
        private InterestCalculationService serviceOver(DisclosureGroupRepository groups) {
            return new InterestCalculationService(groups, mock(AccountRepository.class),
                    mock(CardXrefRepository.class), mock(TransactionRepository.class));
        }

        /** A group that exists answers directly and records no fallback. */
        @Test
        @DisplayName("answer directly when the account's own group exists")
        void directHitRecordsNoFallback() {
            DisclosureGroupRepository groups = mock(DisclosureGroupRepository.class);
            when(groups.findByIdIs(any())).thenReturn(Optional.of(new DisclosureGroup(
                    new DisclosureGroup.DisclosureGroupId("ZEROAPR   ", "01", "0005"),
                    new BigDecimal("12.00"))));

            InterestRateLookup lookup = serviceOver(groups).rateFor(this.requested);

            assertThat(lookup.defaultGroupFallbackApplied()).isFalse();
            assertThat(lookup.resolvedRate()).isEqualByComparingTo("12.00");
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

            InterestRateLookup lookup = serviceOver(groups).rateFor(this.requested);

            assertThat(lookup.defaultGroupFallbackApplied()).isTrue();
            assertThat(lookup.resolvedRate()).isEqualByComparingTo("18.00");
            assertThat(lookup.effectiveKey().transactionTypeCode())
                    .as("the substitution replaces the account group alone, so :437 carries the type"
                            + " through unchanged")
                    .isEqualTo(this.requested.transactionTypeCode());
            assertThat(lookup.effectiveKey().transactionCategoryCode())
                    .as("the substitution replaces the account group alone, so :437 carries the"
                            + " category through unchanged")
                    .isEqualTo(this.requested.transactionCategoryCode());
        }

        /**
         * An absent {@code DEFAULT} row is fatal, and is emphatically not a zero rate.
         *
         * <p>Assumptions: {@code 1200-A-GET-DEFAULT-INT-RATE} at
         * {@code app/cbl/CBACT04C.cbl:443-460} reads with NO {@code INVALID KEY} clause at all and
         * then accepts only file status {@code '00'}, displaying
         * {@code ERROR READING DEFAULT DISCLOSURE GROUP} and abending otherwise. Answering zero
         * instead would suppress the accrual for every account whose group is unknown while
         * reporting a clean run, which is the outcome this case exists to rule out.</p>
         */
        @Test
        @DisplayName("fail hard when neither the group nor DEFAULT resolves")
        void neitherKeyResolving() {
            DisclosureGroupRepository groups = mock(DisclosureGroupRepository.class);
            when(groups.findByIdIs(any())).thenReturn(Optional.empty());
            InterestCalculationService service = serviceOver(groups);

            assertThatThrownBy(() -> service.rateFor(this.requested))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DEFAULT");
        }

        /**
         * The accrual is the reference's own formula, multiplied before divided.
         *
         * <p>Assumptions: the datum is chosen so the two orders disagree. A balance of 1000.00 at
         * 12.00 percent gives 12000.0000 divided by 1200, which is exactly 10.00; dividing first gives
         * 0.01 at scale two, which multiplied back gives 10.00 as well -- so a coarser datum is used
         * below where the orders separate.</p>
         *
         * <p>Assumptions: the expected value reduces by DISCARDING the surplus digits, which is what
         * the reference statement does and therefore what the shared accrual reproduces. On this datum
         * the two candidate modes are 5.59 discarded against 5.60 rounded half up, and the discarded
         * value is the required one; the difference is asserted where the accrual's own rounding
         * contract is asserted rather than here, so this case is left free to test the operand order
         * alone.</p>
         *
         * <p>Refactoring Rationale: the counterfactual here named {@code HALF_UP} and cited divergence
         * {@code C-ROUNDING} while the accrual reduced half up. It is realigned rather than deleted,
         * because a mode mismatch between this expectation and the shared kernel would make this case
         * fail for a reason that has nothing to do with the operand order it exists to check.</p>
         */
        @Test
        @DisplayName("accrue the reference formula, multiplying before dividing")
        void accrualMultipliesBeforeDividing() {
            DisclosureGroupRepository groups = mock(DisclosureGroupRepository.class);
            InterestCalculationService service = serviceOver(groups);
            InterestRateLookup lookup = InterestRateLookup.ofDirectHit(
                    new DisclosureGroupKey("DEFAULT   ", "01", 5), new BigDecimal("13.25"));

            assertThat(service.monthlyInterest(Money.of("507.03"), lookup).amount())
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
         * <p>Assumptions: the shared constant compared against is
         * {@code BASELINE_INTEREST_ROUNDING} and deliberately NOT {@code GENERAL_ROUNDING}. The money
         * type declares two modes and the accrual is the single operation the narrower one governs, so
         * comparing against the general mode would assert the opposite of the shipped contract. The
         * service's constant is DERIVED from the shared one rather than restated as a literal, so this
         * case fails only if the shared type stops applying the mode it publishes.</p>
         *
         * <p>Refactoring Rationale: this case compared against {@code GENERAL_ROUNDING} while the
         * accrual reduced half up. Both sides are moved together, and the literal value is asserted
         * alongside the identity so that a future edit deriving the service constant from the general
         * mode again fails here rather than passing on a tautology.</p>
         */
        @Test
        @DisplayName("the named accrual mode is the mode the shared arithmetic applies")
        void theNamedAccrualModeIsTheSharedOne() {
            assertThat(InterestCalculationService.ACCRUAL_ROUNDING)
                    .as("a mode named here but not applied by the shared helper would mislead every"
                            + " reader of this service")
                    .isEqualTo(Money.BASELINE_INTEREST_ROUNDING)
                    .isEqualTo(java.math.RoundingMode.DOWN);
        }

        /** A genuine zero rate accrues nothing, and is not the same outcome as an absent group. */
        @Test
        @DisplayName("accrue nothing at a genuine zero rate")
        void zeroRateAccruesNothing() {
            DisclosureGroupRepository groups = mock(DisclosureGroupRepository.class);
            InterestRateLookup lookup = InterestRateLookup.ofDirectHit(
                    new DisclosureGroupKey("ZEROAPR   ", "01", 5), new BigDecimal("0.00"));

            assertThat(serviceOver(groups)
                    .monthlyInterest(Money.of("1000.00"), lookup).amount())
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

        /** The surrogate identity the writer reports for the row it opened. */
        private static final Long ROW_ID = 42L;

        /** The job the cases attribute their step to, carried so a published failure names it. */
        private static final BatchJobName JOB = BatchJobName.POST_TRANSACTIONS;

        /**
         * Builds a ledger over a writer and no failure reporter.
         *
         * <p>Assumptions: the reporter is absent in these cases because the behaviour under test is the
         * ledger's transition record, and an absent reporter is a supported deployment state rather than
         * a stub: the queue configuration is gated on a sink address, so an environment that publishes
         * none registers no implementation. The cases that DO exercise reporting supply a mock
         * explicitly, which keeps each case's collaborator set the smallest one that can observe it.</p>
         *
         * @param writer the ledger writer to build over; must not be {@code null}
         * @return a ledger holding no failure reporter, never {@code null}
         */
        private static BatchStepLedger ledgerOver(BatchStepLedgerWriter writer) {
            return new BatchStepLedger(writer, Optional.empty());
        }

        /** An unrecorded step runs its body and records the completion. */
        @Test
        @DisplayName("run an unrecorded step and record its completion")
        void unrecordedStepRunsAndRecords() {
            BatchStepLedgerWriter writer = mock(BatchStepLedgerWriter.class);
            when(writer.read(RUN_ID, STEP)).thenReturn(Optional.empty());
            when(writer.openAttempt(RUN_ID, STEP)).thenReturn(ROW_ID);

            BatchStepLedger.StepOutcome outcome = ledgerOver(writer)
                    .runStep(RUN_ID, STEP, JOB, () -> BatchReturnCode.CLEAN);

            assertThat(outcome.skipped()).isFalse();
            assertThat(outcome.returnCode()).isEqualTo(BatchReturnCode.CLEAN);
            verify(writer).openAttempt(RUN_ID, STEP);
            verify(writer).closeAttempt(ROW_ID, BatchReturnCode.CLEAN, false);
        }

        /** An already-completed step is a no-op that reports its recorded code. */
        @Test
        @DisplayName("skip an already-completed step and report its recorded code")
        void completedStepIsANoOp() {
            BatchRun completed =
                    new BatchRun(RUN_ID, STEP, LocalDateTime.now(FIXED).minusMinutes(5));
            completed.markCompleted(LocalDateTime.now(FIXED),
                    (short) BatchReturnCode.SOFT_WARN.numericValue());

            BatchStepLedgerWriter writer = mock(BatchStepLedgerWriter.class);
            when(writer.read(RUN_ID, STEP)).thenReturn(Optional.of(completed));

            boolean[] ran = {false};
            BatchStepLedger.StepOutcome outcome = ledgerOver(writer)
                    .runStep(RUN_ID, STEP, JOB, () -> {
                        ran[0] = true;
                        return BatchReturnCode.CLEAN;
                    });

            assertThat(ran[0]).as("the body of a completed step must not run").isFalse();
            assertThat(outcome.skipped()).isTrue();
            assertThat(outcome.returnCode()).isEqualTo(BatchReturnCode.SOFT_WARN);
            verify(writer, never()).openAttempt(any(), any());
            verify(writer, never()).closeAttempt(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        /**
         * A failing body records the failure through the writer and rethrows.
         *
         * <p>Assumptions: the assertion is that the failure is recorded through the WRITER rather than
         * through a repository this class holds, because that is what makes the record survive the
         * caller's rollback. Recording it on a collaborator whose methods run in their own transactions
         * is the whole correction; a test that only asserted "two saves happened" passed equally well
         * before and after it.</p>
         */
        @Test
        @DisplayName("mark a failing step failed through the independent writer and rethrow")
        void failingStepIsRecordedAndRethrown() {
            BatchStepLedgerWriter writer = mock(BatchStepLedgerWriter.class);
            when(writer.read(RUN_ID, STEP)).thenReturn(Optional.empty());
            when(writer.openAttempt(RUN_ID, STEP)).thenReturn(ROW_ID);

            BatchStepLedger ledger = ledgerOver(writer);

            assertThatThrownBy(() -> ledger.runStep(RUN_ID, STEP, JOB, () -> {
                throw new IllegalStateException("step failed");
            })).isInstanceOf(IllegalStateException.class).hasMessage("step failed");

            verify(writer).closeAttempt(ROW_ID, BatchReturnCode.HARD_FAILURE, true);
        }

        /**
         * The failure record carries the fault's type chain and neither its text nor the throwable.
         *
         * <p>Assumptions: the fault's message is fabricated to look like a driver message quoting a
         * bound parameter, because that is the shape this record actually has to withhold -- a posting
         * step's parameters are transaction records, so a driver fault quoting them puts a primary
         * account number in the log stream. The assertion is on ABSENCE of that text and absence of an
         * attached throwable, because attaching one renders every message in the cause chain and would
         * disclose exactly what suppressing the text refuses.</p>
         */
        @Test
        @DisplayName("the failure record carries the type chain, not the fault's text")
        void failureRecordCarriesNoFaultText() {
            // WHY : Refactoring Rationale: the ledger is built over the WRITER seam rather than over a
            //       repository and a clock. The durable attempt model owns the row transitions, so a
            //       repository double here would leave the attempt unopened and the case would assert a
            //       log line produced on a path the service no longer takes.
            BatchStepLedgerWriter writer = mock(BatchStepLedgerWriter.class);
            when(writer.read(RUN_ID, STEP)).thenReturn(Optional.empty());
            when(writer.openAttempt(RUN_ID, STEP)).thenReturn(1L);
            BatchStepLedger ledger = new BatchStepLedger(writer, Optional.empty());

            ch.qos.logback.classic.Logger ledgerLogger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(BatchStepLedger.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
                    new ch.qos.logback.core.read.ListAppender<>();
            ch.qos.logback.classic.Level restore = ledgerLogger.getLevel();
            captured.start();
            ledgerLogger.addAppender(captured);
            ledgerLogger.setLevel(ch.qos.logback.classic.Level.ERROR);
            try {
                assertThatThrownBy(() -> ledger.runStep(RUN_ID, STEP, JOB, () -> {
                    throw new IllegalStateException(
                            "ERROR: value too long for column at INSERT INTO ledger.transactions"
                                    + " (card_num) VALUES ('4111111111111111')");
                })).isInstanceOf(IllegalStateException.class);

                assertThat(captured.list).hasSize(1);
                String rendered = captured.list.getFirst().getFormattedMessage();
                assertThat(rendered).contains("event=batch.step.failed")
                        .contains(IllegalStateException.class.getName());
                assertThat(rendered).doesNotContain("4111111111111111")
                        .doesNotContain("value too long");
                assertThat(captured.list.getFirst().getThrowableProxy()).isNull();
            } finally {
                ledgerLogger.detachAppender(captured);
                captured.stop();
                ledgerLogger.setLevel(restore);
            }
        }

        /** A previously failed step is re-run rather than skipped. */
        @Test
        @DisplayName("re-run a previously failed step")
        void failedStepIsReRun() {
            BatchRun failed =
                    new BatchRun(RUN_ID, STEP, LocalDateTime.now(FIXED).minusMinutes(5));
            failed.markFailed(LocalDateTime.now(FIXED),
                    (short) BatchReturnCode.HARD_FAILURE.numericValue());

            BatchStepLedgerWriter writer = mock(BatchStepLedgerWriter.class);
            when(writer.read(RUN_ID, STEP)).thenReturn(Optional.of(failed));
            when(writer.openAttempt(RUN_ID, STEP)).thenReturn(ROW_ID);

            boolean[] ran = {false};
            BatchStepLedger.StepOutcome outcome = ledgerOver(writer)
                    .runStep(RUN_ID, STEP, JOB, () -> {
                        ran[0] = true;
                        return BatchReturnCode.CLEAN;
                    });

            assertThat(ran[0]).as("the body of a failed step must run again").isTrue();
            assertThat(outcome.skipped()).isFalse();
            verify(writer).openAttempt(RUN_ID, STEP);
        }

        /**
         * A row re-opened for another attempt counts the attempt and clears the previous outcome.
         *
         * <p>Assumptions: this asserts the ROW transition rather than the ledger, because the transition
         * is what the uniqueness constraint forces. A redrive cannot insert a second row for the same run
         * and step, so re-opening the recorded one is the only available shape and the attempt counter is
         * the only record that the step was tried more than once.</p>
         *
         * <p>Assumptions: the start time is asserted to be the NEW attempt's, not the first attempt's,
         * because that is what an operator reading a recovered night asks the row for. This assertion is
         * the in-memory half of the contract; the durable half, which is what the column mapping decides,
         * is asserted against PostgreSQL by
         * {@code BatchRunRepositoryIT.theAttemptCounterIsDurableAcrossAReopen}.</p>
         */
        @Test
        @DisplayName("count the attempt and clear the previous outcome when a failed row is re-opened")
        void reopeningAFailedRowCountsTheAttempt() {
            BatchRun row = new BatchRun(RUN_ID, STEP, LocalDateTime.now(FIXED).minusMinutes(5));
            row.markFailed(LocalDateTime.now(FIXED),
                    (short) BatchReturnCode.HARD_FAILURE.numericValue());
            assertThat(row.getAttempt()).isEqualTo(1);

            LocalDateTime secondAttemptStart = LocalDateTime.now(FIXED).plusMinutes(30);
            row.reopen(secondAttemptStart);

            assertThat(row.getAttempt()).isEqualTo(2);
            assertThat(row.getStatus()).isEqualTo(BatchRun.BatchRunStatus.STARTED);
            assertThat(row.getStartedAt())
                    .as("a re-opened row must carry the start of the attempt it now describes")
                    .isEqualTo(secondAttemptStart);
            assertThat(row.getFinishedAt()).isNull();
            assertThat(row.getReturnCode()).isNull();
        }

        /**
         * A completed row refuses re-opening, so committed work is never applied twice.
         *
         * <p>Assumptions: the refusal lives on the row and not only on the ledger's skip decision, so a
         * future caller that forgot the skip cannot double-apply a completed step's writes.</p>
         */
        @Test
        @DisplayName("refuse to re-open a completed row")
        void reopeningACompletedRowIsRefused() {
            BatchRun row = new BatchRun(RUN_ID, STEP, LocalDateTime.now(FIXED).minusMinutes(5));
            row.markCompleted(LocalDateTime.now(FIXED),
                    (short) BatchReturnCode.CLEAN.numericValue());

            assertThatThrownBy(() -> row.reopen(LocalDateTime.now(FIXED)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("completed row");
        }

        /**
         * A raised failure is reported to the terminal sink, naming its run, step, job and tier.
         *
         * <p>Assumptions: this asserts the report is issued at all, which is the whole of what was
         * missing. The module shipped a validated sink binding that nothing ever called, so the sink
         * stayed empty however a night ended -- a case asserting only that the ledger row was written
         * passed identically before and after the publisher existed.</p>
         */
        @Test
        @DisplayName("report a raised step failure to the terminal sink")
        void raisedFailureIsReported() {
            BatchStepLedgerWriter writer = mock(BatchStepLedgerWriter.class);
            when(writer.read(RUN_ID, STEP)).thenReturn(Optional.empty());
            when(writer.openAttempt(RUN_ID, STEP)).thenReturn(ROW_ID);
            BatchFailureReporter reporter = mock(BatchFailureReporter.class);

            BatchStepLedger ledger = new BatchStepLedger(writer, Optional.of(reporter));

            assertThatThrownBy(() -> ledger.runStep(RUN_ID, STEP, JOB, () -> {
                throw new IllegalStateException("step failed");
            })).isInstanceOf(IllegalStateException.class);

            ArgumentCaptor<BatchErrorEvent> published =
                    ArgumentCaptor.forClass(BatchErrorEvent.class);
            verify(reporter).report(published.capture());
            BatchErrorEvent event = published.getValue();
            assertThat(event.runId()).isEqualTo(RUN_ID);
            assertThat(event.stepName()).isEqualTo(STEP);
            assertThat(event.jobName()).isEqualTo(JOB);
            assertThat(event.returnCode()).isEqualTo(BatchReturnCode.HARD_FAILURE);
            assertThat(event.abendDetail().abendCode())
                    .isEqualTo(BatchStepLedger.BATCH_ABEND_CODE);
            assertThat(event.abendDetail().abendReason())
                    .as("the reason carries the failure's TYPE and never its message, because a"
                            + " library-composed message is an unbounded disclosure channel")
                    .isEqualTo("IllegalStateException")
                    .doesNotContain("step failed");
        }

        /** A body that grades its own outcome as the failure tier is reported too. */
        @Test
        @DisplayName("report a step that graded its own outcome as the failure tier")
        void gradedFailureIsReported() {
            BatchStepLedgerWriter writer = mock(BatchStepLedgerWriter.class);
            when(writer.read(RUN_ID, STEP)).thenReturn(Optional.empty());
            when(writer.openAttempt(RUN_ID, STEP)).thenReturn(ROW_ID);
            BatchFailureReporter reporter = mock(BatchFailureReporter.class);

            BatchStepLedger.StepOutcome outcome =
                    new BatchStepLedger(writer, Optional.of(reporter))
                            .runStep(RUN_ID, STEP, JOB, () -> BatchReturnCode.HARD_FAILURE);

            assertThat(outcome.returnCode()).isEqualTo(BatchReturnCode.HARD_FAILURE);
            verify(reporter).report(any(BatchErrorEvent.class));
        }

        /**
         * The warn tier is never reported, so a correct night that rejected rows raises nobody.
         *
         * <p>Assumptions: the reference reaches the warn tier BY DESIGN at
         * {@code app/cbl/CBTRN02C.cbl:229-230}, where a non-zero reject count moves 4 into
         * {@code RETURN-CODE} on a run that did its job correctly. Publishing that to a terminal sink
         * would raise an operator for a successful night, and the credibility of a sink is spent the
         * first time it does.</p>
         */
        @Test
        @DisplayName("never report the warn tier")
        void warnTierIsNotReported() {
            BatchStepLedgerWriter writer = mock(BatchStepLedgerWriter.class);
            when(writer.read(RUN_ID, STEP)).thenReturn(Optional.empty());
            when(writer.openAttempt(RUN_ID, STEP)).thenReturn(ROW_ID);
            BatchFailureReporter reporter = mock(BatchFailureReporter.class);

            new BatchStepLedger(writer, Optional.of(reporter))
                    .runStep(RUN_ID, STEP, JOB, () -> BatchReturnCode.SOFT_WARN);

            verify(reporter, never()).report(any(BatchErrorEvent.class));
        }

        /**
         * A reporter that raises does not replace the step failure it was reporting.
         *
         * <p>Assumptions: the port's contract obliges an implementation not to throw, and this case
         * asserts the ledger survives one that breaks the contract anyway. Without the guard, a queue
         * permission the task role was never granted would surface as a messaging-library stack trace
         * and the real cause -- the step that actually failed -- would be lost.</p>
         */
        @Test
        @DisplayName("keep the step's own failure when the reporter itself raises")
        void reporterFailureDoesNotReplaceTheStepFailure() {
            BatchStepLedgerWriter writer = mock(BatchStepLedgerWriter.class);
            when(writer.read(RUN_ID, STEP)).thenReturn(Optional.empty());
            when(writer.openAttempt(RUN_ID, STEP)).thenReturn(ROW_ID);
            BatchFailureReporter reporter = mock(BatchFailureReporter.class);
            when(reporter.report(any(BatchErrorEvent.class)))
                    .thenThrow(new IllegalStateException("sink unreachable"));

            BatchStepLedger ledger = new BatchStepLedger(writer, Optional.of(reporter));

            assertThatThrownBy(() -> ledger.runStep(RUN_ID, STEP, JOB, () -> {
                throw new IllegalArgumentException("step failed");
            })).isInstanceOf(IllegalArgumentException.class).hasMessage("step failed");

            verify(writer).closeAttempt(ROW_ID, BatchReturnCode.HARD_FAILURE, true);
        }
    }
}
