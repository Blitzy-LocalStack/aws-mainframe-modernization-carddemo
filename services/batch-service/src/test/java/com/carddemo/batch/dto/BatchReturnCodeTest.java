package com.carddemo.batch.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.domain.BatchRun;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds {@link BatchReturnCode} to the completion-tier contract the orchestration layer depends on:
 * three tiers, their numbers, the sense of the gate predicate, and the refusal to invent a tier.
 *
 * <p>Assumptions: the assertion that matters most in this class is
 * {@link #permitsDownstreamRunAnswersInTheRunSenseAndNotTheSkipSense()}. The predicate under test
 * translates a job-control condition, which states when a step is SKIPPED, into an orchestration
 * choice, which states when a state RUNS, and the two are opposites. A body written the other way
 * round still compiles, still passes any test that only exercises a clean run, and diverges from the
 * contract only on those runs that produced rejects. That is the one defect this class exists to
 * catch, and it is why the predicate is asserted for all three tiers rather than sampled.</p>
 *
 * <p>Alternatives Considered: restating the three numbers as literals in this file and asserting
 * against those alone. Rejected for the tiers that have an external authority, because a literal
 * expectation is edited by whoever changes the constant, in the same commit, so it records agreement
 * with the author rather than agreement with the contract. Where a second declaration of the same
 * tier exists in this module it is compared against directly instead:
 * {@link #numbersMatchTheEntryPointExitStatusContract()} compares against the constants the process
 * entry point publishes, and {@link #constantNamesDoNotCollideWithTheLifecycleVocabulary()} compares
 * against the lifecycle enumeration the step ledger owns. The literals are still asserted once, in
 * {@link #reportsTheContractNumbers()}, because a contract of three fixed numbers should fail loudly
 * if any of them moves.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the parameterised methods below carry their own.</p>
 */
class BatchReturnCodeTest {

    /**
     * The number of completion tiers the contract declares, three.
     *
     * <p>Assumptions: the count is asserted rather than assumed because a fourth tier added here
     * would be a number no step can report and no gate was written to interpret, and it would
     * compile cleanly. The reference implementation assigns a non-zero code in exactly one statement,
     * so there is no fourth outcome to model.</p>
     */
    private static final int DECLARED_TIER_COUNT = 3;

    /**
     * The number above which every value collapses onto the failure tier, four.
     *
     * <p>Assumptions: this is the threshold the orchestration choice compares against, so it is named
     * once here and reused by the two tests that assert the gate rather than being spelled inline in
     * each of them.</p>
     */
    private static final int HIGHEST_NUMBER_THAT_PERMITS_A_DOWNSTREAM_RUN = 4;

    /**
     * Verifies that each tier reports the number the contract fixes for it.
     */
    @Test
    void reportsTheContractNumbers() {
        assertThat(BatchReturnCode.CLEAN.numericValue()).isZero();
        assertThat(BatchReturnCode.SOFT_WARN.numericValue()).isEqualTo(4);
        assertThat(BatchReturnCode.HARD_FAILURE.numericValue()).isEqualTo(8);
    }

    /**
     * Verifies that the three numbers are the same three the process entry point publishes as its
     * exit-status contract.
     */
    @Test
    void numbersMatchTheEntryPointExitStatusContract() {
        // WHY : Assumptions: the tier numbers are declared twice in this module, once as this
        //       enumeration and once as the integer constants the entry point hands to the process
        //       exit call, and nothing in the compiler relates the two. Comparing them here is what
        //       makes the duplication safe: a number changed on one side alone fails this assertion
        //       instead of producing a task whose exit status disagrees with its own tier.
        assertThat(BatchReturnCode.CLEAN.numericValue())
                .isEqualTo(BatchApplication.EXIT_STATUS_CLEAN);
        assertThat(BatchReturnCode.SOFT_WARN.numericValue())
                .isEqualTo(BatchApplication.EXIT_STATUS_SOFT_WARN);
        assertThat(BatchReturnCode.HARD_FAILURE.numericValue())
                .isEqualTo(BatchApplication.EXIT_STATUS_HARD_FAILURE);
    }

    /**
     * Verifies that the gate predicate answers in the RUN sense, admitting the tier that produced
     * rejects, and not in the SKIP sense of the job-control condition it replaces.
     *
     * <p>This is the regression guard against re-inverting the translated condition. A predicate that
     * mirrored the condition keyword rather than its meaning would answer {@code false} for
     * {@link BatchReturnCode#SOFT_WARN}, and the state following a run that correctly rejected
     * transactions would be skipped, so the chain would stop on exactly those runs that produced
     * rejects and on no others.</p>
     */
    @Test
    void permitsDownstreamRunAnswersInTheRunSenseAndNotTheSkipSense() {
        // WHY : Assumptions: the middle tier is the assertion that carries the whole test. A clean
        //       tier answers true and a failure tier answers false under either reading of the
        //       condition, so neither of those two would detect an inverted body; only the middle one
        //       distinguishes the run sense from the skip sense.
        assertThat(BatchReturnCode.CLEAN.permitsDownstreamRun()).isTrue();
        assertThat(BatchReturnCode.SOFT_WARN.permitsDownstreamRun()).isTrue();
        assertThat(BatchReturnCode.HARD_FAILURE.permitsDownstreamRun()).isFalse();
    }

    /**
     * Verifies that the gate predicate agrees, for every tier, with the numeric comparison the
     * orchestration choice actually evaluates.
     *
     * @param tier one of the three constants, supplied by the enumeration source so that adding a
     *     tier automatically extends this test rather than leaving the new one unasserted
     */
    @ParameterizedTest
    @EnumSource(BatchReturnCode.class)
    void permitsDownstreamRunAgreesWithTheNumericGate(BatchReturnCode tier) {
        // WHY : Assumptions: the orchestration choice cannot call this predicate -- it evaluates a
        //       number in a state definition -- so the predicate and that comparison are two
        //       independent expressions of one rule. Asserting the equivalence per tier is what keeps
        //       them from drifting apart, which would otherwise show up only as a chain that gated
        //       differently from the Java that reported the tier.
        boolean gateAdmitsThisTier =
                tier.numericValue() <= HIGHEST_NUMBER_THAT_PERMITS_A_DOWNSTREAM_RUN;
        assertThat(tier.permitsDownstreamRun()).isEqualTo(gateAdmitsThisTier);
    }

    /**
     * Verifies that each tier's own number resolves back to that tier.
     *
     * @param tier one of the three constants, supplied by the enumeration source
     */
    @ParameterizedTest
    @EnumSource(BatchReturnCode.class)
    void resolutionRoundTripsEveryTier(BatchReturnCode tier) {
        // WHY : Assumptions: identity is asserted rather than equality. An enumeration constant is a
        //       singleton, so a resolver that somehow produced a different instance carrying an equal
        //       number would satisfy an equality assertion while breaking every switch and every
        //       reference comparison downstream of it.
        assertThat(BatchReturnCode.fromNumericValue(tier.numericValue())).isSameAs(tier);
    }

    /**
     * Verifies that every number at or above the failure tier resolves to the failure tier instead of
     * being rejected.
     *
     * @param exitStatusAboveTheFailureTier a number at or above the failure tier, including the two
     *     values a container conventionally reports when the platform terminates the task with a
     *     signal, which are 128 plus the signal number
     */
    @ParameterizedTest
    @ValueSource(ints = {8, 9, 12, 16, 100, 137, 139, 255, Integer.MAX_VALUE})
    void resolutionClampsEveryNumberAtOrAboveTheFailureTier(int exitStatusAboveTheFailureTier) {
        // WHY : Assumptions: the failure tier is declared as a threshold rather than as a single
        //       value, so this is the contract and not tolerance around it. A step does not always
        //       choose the number it exits with, and a caller that received an exception here would
        //       have to re-implement the clamp to recover the tier it already knew it needed.
        assertThat(BatchReturnCode.fromNumericValue(exitStatusAboveTheFailureTier))
                .isSameAs(BatchReturnCode.HARD_FAILURE);
    }

    /**
     * Verifies that a number falling strictly between two tiers is rejected rather than mapped onto a
     * neighbouring tier.
     *
     * @param numberBetweenTiers a number no tier claims, drawn from both gaps in the contract
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 6, 7})
    void resolutionRejectsANumberBetweenTiers(int numberBetweenTiers) {
        // WHY : Alternatives Considered: mapping such a number upward to the next tier was rejected,
        //       and the values 1, 2 and 3 are why. The next tier above them is the middle one, which
        //       PASSES the gate, so a step that failed before processing a single record and reported
        //       one of those numbers would be waved downstream as though it had merely written
        //       rejects. Asserting the rejection is what keeps that convenience from being added back.
        assertThatThrownBy(() -> BatchReturnCode.fromNumericValue(numberBetweenTiers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(numberBetweenTiers));
    }

    /**
     * Verifies that a negative number is rejected rather than resolving to the clean tier.
     *
     * @param negativeNumber a number below every tier
     */
    @ParameterizedTest
    @ValueSource(ints = {-1, -4, -8, Integer.MIN_VALUE})
    void resolutionRejectsANegativeNumber(int negativeNumber) {
        // WHY : Assumptions: a negative number is below the clean tier rather than above the failure
        //       tier, so a rule that clamped in both directions would report an impossible status as a
        //       clean finish -- the one outcome that would let it pass the gate unnoticed. Rejecting
        //       it keeps the fault at the boundary it entered.
        assertThatThrownBy(() -> BatchReturnCode.fromNumericValue(negativeNumber))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies that the enumeration declares exactly three tiers, no more and no fewer.
     */
    @Test
    void declaresExactlyThreeTiers() {
        assertThat(BatchReturnCode.values()).hasSize(DECLARED_TIER_COUNT);
    }

    /**
     * Verifies that the tiers are declared in ascending order of the number they report.
     */
    @Test
    void tiersAreDeclaredInAscendingNumericOrder() {
        // WHY : Assumptions: the order is asserted because the type documents it, not because
        //       anything reads the ordinal -- the documentation states that the ordinal is not part of
        //       any contract. A test that let the declaration order drift would leave that
        //       documentation false, which is the failure this assertion prevents.
        List<Integer> numbersInDeclarationOrder = Arrays.stream(BatchReturnCode.values())
                .map(BatchReturnCode::numericValue)
                .toList();
        assertThat(numbersInDeclarationOrder).isSorted();
    }

    /**
     * Verifies that no tier name collides with the lifecycle vocabulary the step ledger owns, so the
     * two axes stay distinguishable in a log and in a reader's head.
     */
    @Test
    void constantNamesDoNotCollideWithTheLifecycleVocabulary() {
        // WHY : Assumptions: the comparison is made against the lifecycle enumeration itself rather
        //       than against a restated list of its names, so a lifecycle state added or renamed there
        //       is picked up here automatically. The two enumerations describe independent axes -- how
        //       far a step got, and what a finished step reports -- and one name appearing in both
        //       would make a ledger row ambiguous about which axis it was quoting.
        List<String> tierNames = Arrays.stream(BatchReturnCode.values())
                .map(BatchReturnCode::name)
                .toList();
        List<String> lifecycleStateNames = Arrays.stream(BatchRun.BatchRunStatus.values())
                .map(BatchRun.BatchRunStatus::name)
                .toList();
        assertThat(tierNames).doesNotContainAnyElementsOf(lifecycleStateNames);
    }

    /**
     * Verifies that the rejection message names both the offending number and the tiers that exist,
     * so a container log identifies an unmodelled number without access to the source.
     */
    @Test
    void rejectionMessageNamesTheOffendingNumberAndTheTiers() {
        assertThatThrownBy(() -> BatchReturnCode.fromNumericValue(5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5")
                .hasMessageContaining(BatchReturnCode.CLEAN.name())
                .hasMessageContaining(BatchReturnCode.SOFT_WARN.name())
                .hasMessageContaining(BatchReturnCode.HARD_FAILURE.name());
    }
}
