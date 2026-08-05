package com.carddemo.batch.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.BatchApplication;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds {@link BatchJobName} to the argument contract that infrastructure depends on: the seven job
 * tokens, their spelling, their order, and the refusal to resolve anything else.
 *
 * <p>Assumptions: the token spelling is the only part of this type that cannot be checked by reading
 * this module. It travels from an orchestration state definition, through a container override, onto
 * a process argument list, so the assertion that matters most here is
 * {@link #tokensMatchTheEntryPointArgumentContract()}, which compares this enumeration against
 * {@link BatchApplication#JOB_NAMES} directly. Restating the seven tokens in a literal array in this
 * file would let both sides drift together away from the state definition while every test still
 * passed, which is the one failure this class exists to prevent.</p>
 *
 * <p>Alternatives Considered: asserting each token against a hard-coded string, one test per
 * constant. Rejected for the reason above, and for a second one: a hard-coded expectation is edited
 * by whoever changes the constant, in the same commit, so it records agreement with the author rather
 * than agreement with the contract. Comparing two independently authored declarations is the only
 * form of the assertion that can disagree with the change being made.</p>
 *
 * <p>Assumptions: every behaviour asserted below is documented on the type or the member it belongs
 * to -- exact and case-sensitive resolution, rejection of an unrecognised token by raising, the
 * rejection of {@code null} and blank input as values rather than as programming errors, and the
 * divergence between the {@code IMPORT} constant name and its {@code import} token. The tests are
 * here to keep the documentation true, so a behaviour changed without its documentation fails here
 * rather than being discovered in a container log.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the parameterised methods below carry their own.</p>
 */
class BatchJobNameTest {

    /**
     * The number of jobs the argument contract declares, seven.
     *
     * <p>Assumptions: the count is asserted rather than assumed because an eighth constant added
     * without an eighth orchestration state, or a seventh removed while a state still asks for it, is
     * exactly the kind of change that compiles cleanly and fails at run time.</p>
     */
    private static final int DECLARED_JOB_COUNT = 7;

    /**
     * Verifies that every constant resolves from its own token back to itself.
     *
     * @param job one of the seven constants, supplied by the enumeration source so that adding a
     *     constant automatically extends this test rather than leaving the new one unasserted
     */
    @ParameterizedTest
    @EnumSource(BatchJobName.class)
    void resolveRoundTripsEveryConstant(BatchJobName job) {
        // WHY : Assumptions: identity is asserted rather than equality. An enumeration constant is a
        //       singleton, so a resolver that somehow produced a different instance carrying equal
        //       state would satisfy an equality assertion while breaking every switch and every
        //       reference comparison downstream of it.
        assertThat(BatchJobName.resolve(job.token())).isSameAs(job);
    }

    /**
     * Verifies that the seven tokens are byte-identical, and identically ordered, to the accepted set
     * the process entry point declares.
     */
    @Test
    void tokensMatchTheEntryPointArgumentContract() {
        List<String> tokensDeclaredHere = Arrays.stream(BatchJobName.values())
                .map(BatchJobName::token)
                .toList();
        // WHY : Assumptions: order is asserted alongside content, and the stricter assertion is the
        //       cheaper one. The entry point declares its list in nightly-chain order followed by the
        //       two unscheduled jobs, and this type declares its constants in that same order so a
        //       maintainer can map the enumeration onto states 3 through 7 of the chain. An
        //       order-insensitive comparison would let the two orderings diverge silently, and the
        //       enumeration would then read as though it described a sequence it no longer matched.
        assertThat(tokensDeclaredHere).containsExactlyElementsOf(BatchApplication.JOB_NAMES);
    }

    /**
     * Verifies that the enumeration declares exactly seven jobs, no more and no fewer.
     */
    @Test
    void declaresExactlySevenJobs() {
        assertThat(BatchJobName.values()).hasSize(DECLARED_JOB_COUNT);
    }

    /**
     * Verifies that the seven tokens are distinct, so no two constants answer to one argument.
     */
    @Test
    void tokensAreUnique() {
        Set<String> distinctTokens = Set.copyOf(Arrays.stream(BatchJobName.values())
                .map(BatchJobName::token)
                .toList());
        // WHY : Assumptions: duplication is checked here rather than left to the resolver's linear
        //       scan. That scan returns the first match, so a duplicated token would resolve to
        //       whichever constant was declared earlier and the second constant would become
        //       unreachable through the resolver without any test failing.
        assertThat(distinctTokens).hasSize(DECLARED_JOB_COUNT);
    }

    /**
     * Verifies that an unrecognised token is rejected by raising, and that the message names it.
     */
    @Test
    void resolveRejectsAnUnknownTokenAndNamesIt() {
        // WHY : Assumptions: the exact exception type is asserted, not a supertype. The process entry
        //       point raises the same type for the same fault, so a subtype introduced here would
        //       give one malformed command two different failure shapes depending on which of the two
        //       read it first.
        assertThatThrownBy(() -> BatchJobName.resolve("no-such-job"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-job");
    }

    /**
     * Verifies that resolution is case-sensitive, so an upper-case spelling does not resolve.
     */
    @Test
    void resolveIsCaseSensitive() {
        // WHY : Assumptions: the upper-case spelling is pinned as a REJECTION rather than left
        //       unasserted. The orchestration module supplies lowercase tokens and the entry point
        //       tests membership of its own lowercase list, so folding case here would make this type
        //       accept an argument that entry point has already rejected. A future convenience change
        //       in that direction fails this test rather than reaching a deployment.
        assertThatThrownBy(() -> BatchJobName.resolve("POST-TRANSACTIONS"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("POST-TRANSACTIONS");
    }

    /**
     * Verifies that a {@code null} token is rejected as a value rather than raising a null-pointer
     * failure.
     */
    @Test
    void resolveRejectsNull() {
        // WHY : Assumptions: a null token means the argument list was assembled wrongly, which is the
        //       same class of fault as a misspelled token and is documented to carry the same
        //       treatment. Asserting the type here is what keeps the resolver's loop comparing the
        //       constant's own token against the argument, rather than the other way round, which
        //       would raise a null-pointer failure and read as a defect in the enumeration.
        assertThatThrownBy(() -> BatchJobName.resolve(null))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    /**
     * Verifies that an empty or whitespace-only token is rejected.
     *
     * @param blankToken an empty string, a single space or a run of spaces; none of the three is one
     *     of the seven tokens, and none is trimmed into one
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "    "})
    void resolveRejectsBlank(String blankToken) {
        // WHY : Assumptions: the blank cases are asserted separately from the unknown-token case
        //       because they are the ones a trimming resolver would treat differently. Nothing here
        //       trims, so a token padded by an argument list that quoted it wrongly is rejected rather
        //       than silently accepted as the job whose name it resembles.
        assertThatThrownBy(() -> BatchJobName.resolve(blankToken))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies the documented divergence between the {@code IMPORT} constant name and its token.
     */
    @Test
    void importConstantNameDivergesFromItsToken() {
        // WHY : Assumptions: this pins the reserved-word accommodation that the type documents. The
        //       token is the contract and the constant name is a Java identifier, so a consumer that
        //       derived a token from the constant name would get the right answer for this one
        //       constant by luck and the wrong answer for the other six.
        assertThat(BatchJobName.IMPORT.name()).isEqualTo("IMPORT");
        assertThat(BatchJobName.IMPORT.token()).isEqualTo("import");
        assertThat(BatchJobName.PREFLIGHT_DAILY_TRANSACTIONS.token())
                .isEqualTo("preflight-daily-transactions")
                .isNotEqualTo(BatchJobName.PREFLIGHT_DAILY_TRANSACTIONS.name().toLowerCase());
    }
}
