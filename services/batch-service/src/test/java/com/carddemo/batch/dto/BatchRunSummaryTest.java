package com.carddemo.batch.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Holds {@link BatchRunSummary} to the run-outcome contract the step ledger and the orchestration
 * choice depend on: the reject invariant, the counter floor, the identity pair, and the refusal to
 * carry a lifecycle state, a time or a rendered line.
 *
 * <p>Assumptions: the assertions that matter most in this class are the four quadrants of
 * {@code app/cbl/CBTRN02C.cbl:229-230}. That statement pair sets the soft-warn tier exactly when the
 * rejected count exceeds zero, and a summary that disagrees with it would still compile, would still
 * persist, and would then steer the orchestration choice -- which admits a tier of four or lower --
 * down a path the reference never takes. Both consistent quadrants and both inconsistent ones are
 * asserted rather than sampled, because a constructor that enforced only one direction would pass a
 * test of only one direction.</p>
 *
 * <p>Assumptions: the scope of that invariant is asserted separately and deliberately.
 * {@code app/cbl/CBIMPORT.cbl:146} declares {@code WS-ERROR-RECORDS-WRITTEN} and the program writes
 * those records while setting no return code at all, so an import run that rejected records finishes
 * in the clean tier. A constructor that applied the invariant to every job would make that faithful
 * summary unconstructable, so the import case below is a regression guard against over-reaching the
 * rule rather than a second example of it.</p>
 *
 * <p>Alternatives Considered: asserting the counter values against literals copied from the reference
 * programs. Rejected, because this type stores what a job measured and derives none of it, so a
 * literal here would assert the fixture rather than the contract. The one number taken from the
 * reference is the nine-digit ceiling, which is asserted once to show the chosen width covers it.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the parameterised method below carries its own.</p>
 */
class BatchRunSummaryTest {

    /**
     * A run identifier standing in for one the orchestration execution supplies.
     */
    private static final String RUN_ID = "carddemo-daily-2022-07-18";

    /**
     * A step name standing in for one state of the nightly chain.
     */
    private static final String STEP_NAME = "PostTransactions";

    /**
     * The largest value a reference counter can hold, 999,999,999.
     *
     * <p>Assumptions: every counter in the five reference programs is declared {@code PIC 9(09)}, so
     * this is the ceiling the chosen component width has to cover. It is named once here and used by
     * the width assertion rather than spelled inline, so that the number and its meaning stay
     * together.</p>
     */
    private static final long NINE_DIGIT_CEILING = 999_999_999L;

    /**
     * Builds a summary with the four counters and the tier supplied and everything else defaulted.
     *
     * <p>Assumptions: the helper exists so that each assertion below varies only the components it is
     * about. A test that spelled all nine arguments would make the one changed argument the hardest
     * thing in the line to see, which is the opposite of what a regression guard needs.</p>
     *
     * @param jobName the job the summary describes
     * @param returnCode the tier the summary reports
     * @param recordsRejected the rejected count, the component the reject invariant reads
     * @return a summary carrying the supplied job, tier and rejected count
     */
    private static BatchRunSummary summaryOf(
            BatchJobName jobName, BatchReturnCode returnCode, long recordsRejected) {
        return new BatchRunSummary(
                RUN_ID, STEP_NAME, jobName, returnCode, 100L, 95L, recordsRejected, 0L, Map.of());
    }

    /**
     * Verifies the first consistent quadrant of {@code CBTRN02C:229-230}: posting with nothing
     * rejected constructs in the clean tier.
     */
    @Test
    void postingWithNoRejectsConstructsInTheCleanTierPerCbtrn02cLines229To230() {
        BatchRunSummary summary =
                summaryOf(BatchJobName.POST_TRANSACTIONS, BatchReturnCode.CLEAN, 0L);

        assertThat(summary.returnCode()).isEqualTo(BatchReturnCode.CLEAN);
        assertThat(summary.recordsRejected()).isZero();
    }

    /**
     * Verifies the second consistent quadrant of {@code CBTRN02C:229-230}: posting with records
     * rejected constructs in the soft-warn tier.
     */
    @Test
    void postingWithRejectsConstructsInTheSoftWarnTierPerCbtrn02cLines229To230() {
        BatchRunSummary summary =
                summaryOf(BatchJobName.POST_TRANSACTIONS, BatchReturnCode.SOFT_WARN, 5L);

        assertThat(summary.returnCode()).isEqualTo(BatchReturnCode.SOFT_WARN);
        assertThat(summary.recordsRejected()).isEqualTo(5L);
        assertThat(summary.returnCode().permitsDownstreamRun()).isTrue();
    }

    /**
     * Verifies the first inconsistent quadrant of {@code CBTRN02C:229-230}: the soft-warn tier with
     * nothing rejected is refused.
     */
    @Test
    void postingRefusesTheSoftWarnTierWithNoRejectsPerCbtrn02cLines229To230() {
        assertThatThrownBy(
                () -> summaryOf(BatchJobName.POST_TRANSACTIONS, BatchReturnCode.SOFT_WARN, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("post-transactions")
                .hasMessageContaining("rejected count");
    }

    /**
     * Verifies the second inconsistent quadrant of {@code CBTRN02C:229-230}: the clean tier with
     * records rejected is refused.
     */
    @Test
    void postingRefusesTheCleanTierWithRejectsPerCbtrn02cLines229To230() {
        assertThatThrownBy(
                () -> summaryOf(BatchJobName.POST_TRANSACTIONS, BatchReturnCode.CLEAN, 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("post-transactions")
                .hasMessageContaining("rejected count");
    }

    /**
     * Verifies that a posting run may report the failure tier while also having rejected records.
     *
     * <p>This is the guard against over-extending the invariant. A step that ends abnormally never
     * reaches {@code app/cbl/CBTRN02C.cbl:230} to assign anything, so its tier says nothing about its
     * counters, and a run may reject records and then fail.</p>
     */
    @Test
    void postingWithRejectsMayReportTheFailureTierBecauseAnAbendNeverReachesLine230() {
        BatchRunSummary summary =
                summaryOf(BatchJobName.POST_TRANSACTIONS, BatchReturnCode.HARD_FAILURE, 5L);

        assertThat(summary.returnCode()).isEqualTo(BatchReturnCode.HARD_FAILURE);
        assertThat(summary.recordsRejected()).isEqualTo(5L);
    }

    /**
     * Verifies that a posting run may report the failure tier with nothing rejected, which the
     * invariant must also leave alone.
     */
    @Test
    void postingWithNoRejectsMayAlsoReportTheFailureTier() {
        BatchRunSummary summary =
                summaryOf(BatchJobName.POST_TRANSACTIONS, BatchReturnCode.HARD_FAILURE, 0L);

        assertThat(summary.returnCode()).isEqualTo(BatchReturnCode.HARD_FAILURE);
        assertThat(summary.recordsRejected()).isZero();
    }

    /**
     * Verifies that no job other than posting may report the soft-warn tier.
     *
     * <p>Searching all five reference programs for {@code RETURN-CODE} finds exactly one occurrence
     * in the set, at {@code app/cbl/CBTRN02C.cbl:230}, so the tier arriving from any other job is a
     * defect in that job rather than a business outcome.</p>
     *
     * @param otherJob each job except posting, supplied by the enumeration source
     */
    @ParameterizedTest
    @EnumSource(
            value = BatchJobName.class,
            names = "POST_TRANSACTIONS",
            mode = EnumSource.Mode.EXCLUDE)
    void theSoftWarnTierIsRefusedForEveryJobOtherThanPosting(BatchJobName otherJob) {
        assertThatThrownBy(() -> summaryOf(otherJob, BatchReturnCode.SOFT_WARN, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("soft-warn tier is reportable only by")
                .hasMessageContaining(otherJob.token());
    }

    /**
     * Verifies that each of the four counters is refused when negative, one assertion per counter.
     */
    @Test
    void eachOfTheFourCountersIsRefusedWhenNegative() {
        assertThatThrownBy(() -> new BatchRunSummary(RUN_ID, STEP_NAME,
                BatchJobName.EXPORT, BatchReturnCode.CLEAN, -1L, 0L, 0L, 0L, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("records read");

        assertThatThrownBy(() -> new BatchRunSummary(RUN_ID, STEP_NAME,
                BatchJobName.EXPORT, BatchReturnCode.CLEAN, 0L, -1L, 0L, 0L, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("records written");

        assertThatThrownBy(() -> new BatchRunSummary(RUN_ID, STEP_NAME,
                BatchJobName.EXPORT, BatchReturnCode.CLEAN, 0L, 0L, -1L, 0L, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("records rejected");

        assertThatThrownBy(() -> new BatchRunSummary(RUN_ID, STEP_NAME,
                BatchJobName.EXPORT, BatchReturnCode.CLEAN, 0L, 0L, 0L, -1L, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("records skipped");
    }

    /**
     * Verifies that both identity strings are refused when absent and when blank.
     *
     * <p>The pair is the step ledger's unique key and its per-step idempotency key, so a summary
     * missing either half could not be matched to its row nor recognised by a redrive.</p>
     */
    @Test
    void bothIdentityStringsAreRefusedWhenAbsentOrBlank() {
        assertThatThrownBy(() -> new BatchRunSummary(null, STEP_NAME,
                BatchJobName.EXPORT, BatchReturnCode.CLEAN, 0L, 0L, 0L, 0L, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run identifier");

        assertThatThrownBy(() -> new BatchRunSummary("   ", STEP_NAME,
                BatchJobName.EXPORT, BatchReturnCode.CLEAN, 0L, 0L, 0L, 0L, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run identifier");

        assertThatThrownBy(() -> new BatchRunSummary(RUN_ID, null,
                BatchJobName.EXPORT, BatchReturnCode.CLEAN, 0L, 0L, 0L, 0L, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step name");

        assertThatThrownBy(() -> new BatchRunSummary(RUN_ID, "\t",
                BatchJobName.EXPORT, BatchReturnCode.CLEAN, 0L, 0L, 0L, 0L, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step name");
    }

    /**
     * Verifies that both enumerated components are refused when absent.
     */
    @Test
    void bothEnumeratedComponentsAreRefusedWhenAbsent() {
        assertThatThrownBy(() -> new BatchRunSummary(RUN_ID, STEP_NAME,
                null, BatchReturnCode.CLEAN, 0L, 0L, 0L, 0L, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("job name");

        assertThatThrownBy(() -> new BatchRunSummary(RUN_ID, STEP_NAME,
                BatchJobName.EXPORT, null, 0L, 0L, 0L, 0L, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("return code");
    }

    /**
     * Verifies that the breakdown is copied on the way in, so mutating the caller's map afterwards
     * cannot change a summary that has already been constructed.
     *
     * <p>Without the copy the component would remain reachable through the reference the caller
     * keeps, and a summary already read, compared or written to the ledger could change behind
     * it.</p>
     */
    @Test
    void theBreakdownIsCopiedSoLaterMutationOfTheCallersMapCannotChangeTheSummary() {
        Map<String, Long> callersMap = new LinkedHashMap<>();
        callersMap.put("CUSTOMER", 10L);

        BatchRunSummary summary = new BatchRunSummary(RUN_ID, STEP_NAME,
                BatchJobName.EXPORT, BatchReturnCode.CLEAN, 10L, 10L, 0L, 0L, callersMap);

        callersMap.put("ACCOUNT", 20L);
        callersMap.put("CUSTOMER", 999L);
        callersMap.clear();

        assertThat(summary.recordTypeCounts()).containsExactly(Map.entry("CUSTOMER", 10L));
    }

    /**
     * Verifies that the map handed back refuses modification.
     */
    @Test
    void theBreakdownReturnedRefusesModification() {
        BatchRunSummary summary = new BatchRunSummary(RUN_ID, STEP_NAME,
                BatchJobName.EXPORT, BatchReturnCode.CLEAN, 1L, 1L, 0L, 0L, Map.of("CARD", 1L));

        assertThatThrownBy(() -> summary.recordTypeCounts().put("ACCOUNT", 2L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Verifies that an absent breakdown becomes an empty map rather than a rejection or an absent
     * component.
     */
    @Test
    void anAbsentBreakdownBecomesAnEmptyMap() {
        BatchRunSummary summary = new BatchRunSummary(RUN_ID, STEP_NAME,
                BatchJobName.CALCULATE_INTEREST, BatchReturnCode.CLEAN, 7L, 7L, 0L, 0L, null);

        assertThat(summary.recordTypeCounts()).isNotNull().isEmpty();
    }

    /**
     * Verifies that a breakdown entry obeys the same two rules the four flat counters obey.
     */
    @Test
    void breakdownEntriesAreRefusedWhenTheKeyIsBlankOrTheCountIsNegative() {
        assertThatThrownBy(() -> new BatchRunSummary(RUN_ID, STEP_NAME,
                BatchJobName.EXPORT, BatchReturnCode.CLEAN, 1L, 1L, 0L, 0L, Map.of("  ", 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");

        assertThatThrownBy(() -> new BatchRunSummary(RUN_ID, STEP_NAME,
                BatchJobName.EXPORT, BatchReturnCode.CLEAN, 1L, 1L, 0L, 0L, Map.of("CARD", -1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CARD");
    }

    /**
     * Verifies a realistic posting summary and that every accessor returns the value supplied.
     */
    @Test
    void aRealisticPostingSummaryCarriesEveryValueSupplied() {
        BatchRunSummary summary = new BatchRunSummary(RUN_ID, STEP_NAME,
                BatchJobName.POST_TRANSACTIONS, BatchReturnCode.SOFT_WARN,
                100L, 95L, 5L, 0L, Map.of());

        assertThat(summary.runId()).isEqualTo(RUN_ID);
        assertThat(summary.stepName()).isEqualTo(STEP_NAME);
        assertThat(summary.jobName()).isEqualTo(BatchJobName.POST_TRANSACTIONS);
        assertThat(summary.returnCode()).isEqualTo(BatchReturnCode.SOFT_WARN);
        assertThat(summary.recordsRead()).isEqualTo(100L);
        assertThat(summary.recordsWritten()).isEqualTo(95L);
        assertThat(summary.recordsRejected()).isEqualTo(5L);
        assertThat(summary.recordsSkipped()).isZero();
        assertThat(summary.recordTypeCounts()).isEmpty();
    }

    /**
     * Verifies a realistic interest summary: a read count, no rejects, the clean tier and no
     * breakdown.
     *
     * <p>Grounded in {@code app/cbl/CBACT04C.cbl}, which keeps one counter at line 172, writes no
     * summary line, and contains no {@code RETURN-CODE} statement anywhere. The skipped count carries
     * its rate gate at line 214, {@code IF DIS-INT-RATE NOT = 0}, which passes over a category
     * balance carrying no rate without rejecting it.</p>
     */
    @Test
    void aRealisticInterestSummaryReportsAReadCountWithNoRejectsInTheCleanTier() {
        BatchRunSummary summary = new BatchRunSummary(RUN_ID, "CalculateInterest",
                BatchJobName.CALCULATE_INTEREST, BatchReturnCode.CLEAN,
                1200L, 1150L, 0L, 50L, Map.of());

        assertThat(summary.recordsRead()).isEqualTo(1200L);
        assertThat(summary.recordsRejected()).isZero();
        assertThat(summary.recordsSkipped()).isEqualTo(50L);
        assertThat(summary.returnCode()).isEqualTo(BatchReturnCode.CLEAN);
        assertThat(summary.recordTypeCounts()).isEmpty();
    }

    /**
     * Verifies a realistic import summary: five per-type counts, a non-zero skipped count, and
     * records rejected while still in the clean tier.
     *
     * <p>This is the case that proves the reject invariant is scoped to posting.
     * {@code app/cbl/CBIMPORT.cbl:141-145} keeps the five per-type counters,
     * {@code app/cbl/CBIMPORT.cbl:146} counts written error records and line 147 counts records of no
     * recognised type, and the program sets no return code at all -- so rejects with a clean tier is
     * exactly what this job reports.</p>
     */
    @Test
    void aRealisticImportSummaryCarriesFivePerTypeCountsAndRejectsInTheCleanTier() {
        Map<String, Long> perType = new LinkedHashMap<>();
        perType.put("CUSTOMER", 100L);
        perType.put("ACCOUNT", 200L);
        perType.put("XREF", 200L);
        perType.put("TRANSACTION", 900L);
        perType.put("CARD", 200L);

        BatchRunSummary summary = new BatchRunSummary(RUN_ID, "ImportDatasets",
                BatchJobName.IMPORT, BatchReturnCode.CLEAN, 1610L, 1600L, 3L, 7L, perType);

        assertThat(summary.recordTypeCounts()).hasSize(5)
                .containsEntry("CUSTOMER", 100L)
                .containsEntry("ACCOUNT", 200L)
                .containsEntry("XREF", 200L)
                .containsEntry("TRANSACTION", 900L)
                .containsEntry("CARD", 200L);
        assertThat(summary.recordsRejected()).isEqualTo(3L);
        assertThat(summary.recordsSkipped()).isEqualTo(7L);
        assertThat(summary.returnCode()).isEqualTo(BatchReturnCode.CLEAN);
    }

    /**
     * Verifies that a preflight summary with every counter zero constructs.
     *
     * <p>Grounded in {@code app/cbl/CBTRN01C.cbl}, which declares no counter group at all and writes
     * no summary line, so four zeroes is the faithful report for this step rather than a defect.</p>
     */
    @Test
    void aPreflightSummaryWithEveryCounterZeroConstructs() {
        BatchRunSummary summary = new BatchRunSummary(RUN_ID, "PreflightDailyTransactions",
                BatchJobName.PREFLIGHT_DAILY_TRANSACTIONS, BatchReturnCode.CLEAN,
                0L, 0L, 0L, 0L, Map.of());

        assertThat(summary.recordsRead()).isZero();
        assertThat(summary.recordsWritten()).isZero();
        assertThat(summary.recordsRejected()).isZero();
        assertThat(summary.recordsSkipped()).isZero();
    }

    /**
     * Verifies that this record carries no lifecycle state, no time component and no rendering
     * member.
     *
     * <p>Assumptions: the three exclusions are asserted structurally rather than by reading the
     * source text, because the structure is what a caller can reach. No nested type proves no
     * lifecycle enumeration is declared here; no component in the platform time package proves no
     * start or finish time is carried; and an exact declared-method set proves there is no renderer,
     * since a member that formatted a summary line would have to appear in that set.</p>
     */
    @Test
    void carriesNoLifecycleStateNoTimeComponentAndNoRenderingMember() {
        assertThat(BatchRunSummary.class.getDeclaredClasses()).isEmpty();

        for (RecordComponent component : BatchRunSummary.class.getRecordComponents()) {
            assertThat(component.getType().getName()).doesNotStartWith("java.time");
        }

        assertThat(Arrays.stream(BatchRunSummary.class.getDeclaredMethods())
                .map(Method::getName)
                .toList())
                .containsExactlyInAnyOrder("runId", "stepName", "jobName", "returnCode",
                        "recordsRead", "recordsWritten", "recordsRejected", "recordsSkipped",
                        "recordTypeCounts", "equals", "hashCode", "toString");
    }

    /**
     * Verifies that value equality is the contract and that the breakdown participates in it.
     */
    @Test
    void valueEqualityIncludesTheBreakdown() {
        BatchRunSummary first = new BatchRunSummary(RUN_ID, STEP_NAME,
                BatchJobName.EXPORT, BatchReturnCode.CLEAN, 5L, 5L, 0L, 0L,
                Map.of("CARD", 5L));
        BatchRunSummary second = new BatchRunSummary(RUN_ID, STEP_NAME,
                BatchJobName.EXPORT, BatchReturnCode.CLEAN, 5L, 5L, 0L, 0L,
                Map.of("CARD", 5L));
        BatchRunSummary differing = new BatchRunSummary(RUN_ID, STEP_NAME,
                BatchJobName.EXPORT, BatchReturnCode.CLEAN, 5L, 5L, 0L, 0L,
                Map.of("CARD", 6L));

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(differing);
    }

    /**
     * Verifies that the nine-digit ceiling of a reference counter round-trips through every counter.
     *
     * <p>Every reference counter is {@code PIC 9(09)} and stops at 999,999,999, so this asserts the
     * chosen width covers the reference ceiling with the aggregate headroom the type documents.</p>
     */
    @Test
    void everyCounterCarriesTheNineDigitReferenceCeiling() {
        BatchRunSummary summary = new BatchRunSummary(RUN_ID, STEP_NAME,
                BatchJobName.IMPORT, BatchReturnCode.CLEAN, NINE_DIGIT_CEILING, NINE_DIGIT_CEILING,
                NINE_DIGIT_CEILING, NINE_DIGIT_CEILING, Map.of("CARD", NINE_DIGIT_CEILING));

        assertThat(summary.recordsRead()).isEqualTo(NINE_DIGIT_CEILING);
        assertThat(summary.recordsWritten()).isEqualTo(NINE_DIGIT_CEILING);
        assertThat(summary.recordsRejected()).isEqualTo(NINE_DIGIT_CEILING);
        assertThat(summary.recordsSkipped()).isEqualTo(NINE_DIGIT_CEILING);
        assertThat(summary.recordTypeCounts()).containsEntry("CARD", NINE_DIGIT_CEILING);
    }

    /**
     * Verifies that every job can report the clean tier and the failure tier, so the two rules the
     * constructor enforces restrict nothing else.
     *
     * @param anyJob each of the seven jobs, supplied by the enumeration source
     */
    @ParameterizedTest
    @EnumSource(BatchJobName.class)
    void everyJobMayReportTheCleanTierAndTheFailureTier(BatchJobName anyJob) {
        assertThat(summaryOf(anyJob, BatchReturnCode.CLEAN, 0L).returnCode())
                .isEqualTo(BatchReturnCode.CLEAN);
        assertThat(summaryOf(anyJob, BatchReturnCode.HARD_FAILURE, 0L).returnCode())
                .isEqualTo(BatchReturnCode.HARD_FAILURE);
    }
}
