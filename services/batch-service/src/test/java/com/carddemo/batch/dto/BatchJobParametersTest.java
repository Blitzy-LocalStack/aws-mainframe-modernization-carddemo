package com.carddemo.batch.dto;

import com.carddemo.batch.BatchApplication;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies that {@link BatchJobParameters} decodes the container command line the orchestration
 * state actually sends, refuses the combinations it documents as impossible, and stays permissive
 * about every argument it does not own.
 *
 * <p>Every expectation below is pinned either to an immutable reference artifact or to the argument
 * contract published by {@link BatchApplication}, never to a value invented here. The two vectors
 * exercised throughout are the two that genuinely occur: the container override
 * {@code ["--job=calculate-interest", "--business-date=2022-07-18"]} that the state machine supplies,
 * and the compact {@code PARM='2022071800'} that {@code app/jcl/INTCALC.jcl:22} injects.</p>
 *
 * <p>Assumptions: the per-job requiredness rule is covered for ALL SEVEN jobs rather than for the
 * one job that requires a date. The rule lives in exactly two places -- the table in the type's
 * Javadoc and the conditional in its constructor -- and nothing but a test that walks the whole
 * enumeration stops those two drifting apart when a job is added or a ground is revised.</p>
 *
 * <p>Alternatives Considered: asserting the parser rejects arguments it does not recognise, which is
 * what a test of a strict command-line parser would do. Rejected because it would pin exactly the
 * defect this type is built to avoid: the argument vector is shared with the framework, so a strict
 * parser fails the container the first time anyone passes a profile selection or a property
 * override. The permissive behaviour is therefore asserted positively, by a test named so that its
 * purpose survives a future author's tidying.</p>
 */
class BatchJobParametersTest {

    /**
     * The separated token the orchestration state supplies as a container override,
     * {@code --business-date=2022-07-18}.
     */
    private static final String SEPARATED_TOKEN = "2022-07-18";

    /**
     * The compact token the baseline's own driver injects, {@code PARM='2022071800'} at
     * {@code app/jcl/INTCALC.jcl:22}. It is deliberately not a valid ISO local date.
     */
    private static final String COMPACT_TOKEN = "2022071800";

    /**
     * Confirms the exact container override the orchestration state supplies decodes to the accrual
     * job with its date token intact, since that vector is the one production path into this type.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("the Step Functions container override decodes to the interest job and the exact token")
    void stepFunctionsContainerOverrideDecodesToInterestJobAndExactToken() {
        BatchJobParameters parameters = BatchJobParameters.fromArguments(
                new String[] {"--job=calculate-interest", "--business-date=2022-07-18"});

        assertThat(parameters.jobName()).isSameAs(BatchJobName.CALCULATE_INTEREST);
        assertThat(parameters.requireBusinessDate().token()).isEqualTo(SEPARATED_TOKEN);
        assertThat(parameters.targetGeneration()).isEmpty();
    }

    /**
     * Confirms the baseline's own compact parameter form survives decoding byte for byte, so the
     * token that reaches a generated transaction identifier is the token that was supplied.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("the compact INTCALC parameter form decodes with nothing reformatted")
    void compactIntcalcParameterFormDecodesUnmodified() {
        BatchJobParameters parameters = BatchJobParameters.fromArguments(
                new String[] {BatchJobParameters.JOB_OPTION + BatchJobName.CALCULATE_INTEREST.token(),
                    BatchJobParameters.BUSINESS_DATE_OPTION + COMPACT_TOKEN});

        assertThat(parameters.requireBusinessDate().token())
                .isEqualTo(COMPACT_TOKEN)
                .doesNotContain("-");
    }

    /**
     * Guards against a strict parser: an ordinary framework option must be ignored rather than
     * failing the step, because the argument vector is shared with the framework that consumes it.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("unrecognised framework options are ignored rather than rejected")
    void unrecognisedFrameworkOptionsAreIgnoredRatherThanRejected() {
        BatchJobParameters parameters = BatchJobParameters.fromArguments(new String[] {
            "--spring.profiles.active=prod",
            "--job=post-transactions",
            "--logging.level.root=INFO"});

        assertThat(parameters.jobName()).isSameAs(BatchJobName.POST_TRANSACTIONS);
        assertThat(parameters.businessDate()).isEmpty();
    }

    /**
     * Confirms the two options may arrive in either order, since nothing in a container override
     * guarantees the sequence in which the orchestrator assembles them.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("argument order does not affect the decoded result")
    void argumentOrderDoesNotAffectTheDecodedResult() {
        BatchJobParameters jobFirst = BatchJobParameters.fromArguments(
                new String[] {"--job=calculate-interest", "--business-date=2022-07-18"});
        BatchJobParameters dateFirst = BatchJobParameters.fromArguments(
                new String[] {"--business-date=2022-07-18", "--job=calculate-interest"});

        assertThat(dateFirst).isEqualTo(jobFirst);
    }

    /**
     * Confirms a vector carrying only a business date is refused, and that the diagnostic names the
     * option that was missing rather than reporting a downstream symptom.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("a vector carrying only a business date is rejected and names the job option")
    void dateOnlyVectorIsRejectedAndNamesTheJobOption() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchJobParameters.fromArguments(
                        new String[] {"--business-date=2022-07-18"}))
                .withMessageContaining(BatchJobParameters.JOB_OPTION)
                .withMessageContaining("required");
    }

    /**
     * Confirms an empty vector and a null vector are both refused as a missing job option, since an
     * orchestrator that assembled no overrides produces one or the other.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("an empty vector and a null vector are both rejected as a missing job option")
    void emptyAndNullVectorsAreRejectedAsAMissingJobOption() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchJobParameters.fromArguments(new String[] {}))
                .withMessageContaining(BatchJobParameters.JOB_OPTION);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchJobParameters.fromArguments(null))
                .withMessageContaining(BatchJobParameters.JOB_OPTION);
    }

    /**
     * Confirms every one of the seven published job tokens decodes to its own constant, so no token
     * in the closed set is unreachable through the factory.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("all seven job tokens decode to their own constant")
    void allSevenJobTokensDecodeToTheirOwnConstant() {
        assertThat(BatchJobName.values()).hasSize(BatchApplication.JOB_NAMES.size());

        for (BatchJobName job : BatchJobName.values()) {
            BatchJobParameters parameters = BatchJobParameters.fromArguments(new String[] {
                BatchJobParameters.JOB_OPTION + job.token(),
                BatchJobParameters.BUSINESS_DATE_OPTION + SEPARATED_TOKEN});

            assertThat(parameters.jobName()).isSameAs(job);
            assertThat(parameters.requireBusinessDate().token()).isEqualTo(SEPARATED_TOKEN);
        }
    }

    /**
     * Confirms the accrual job is refused without a business date, which is the measured half of the
     * requiredness rule and the one case the reference baseline itself establishes.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("the interest job is rejected when no business date accompanies it")
    void interestJobIsRejectedWithoutABusinessDate() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchJobParameters.fromArguments(
                        new String[] {BatchJobParameters.JOB_OPTION
                                + BatchJobName.CALCULATE_INTEREST.token()}))
                .withMessageContaining(BatchJobParameters.BUSINESS_DATE_OPTION);
    }

    /**
     * Walks the whole enumeration to pin the requiredness table against the constructor, so that the
     * documented rule and the enforced rule cannot diverge for any of the seven jobs.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("exactly one of the seven jobs requires a business date, and it is the accrual job")
    void exactlyOneJobRequiresABusinessDate() {
        for (BatchJobName job : BatchJobName.values()) {
            String[] withoutADate = {BatchJobParameters.JOB_OPTION + job.token()};

            if (job == BatchJobName.CALCULATE_INTEREST) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .isThrownBy(() -> BatchJobParameters.fromArguments(withoutADate));
                continue;
            }

            BatchJobParameters parameters = BatchJobParameters.fromArguments(withoutADate);

            assertThat(parameters.jobName()).isSameAs(job);
            assertThat(parameters.businessDate()).isEmpty();
        }
    }

    /**
     * Confirms a repeated option is refused rather than resolved by preferring an occurrence, since
     * a command assembled from two overlapping sources must not run whichever job a convention
     * happened to favour.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("a repeated job option and a repeated business-date option are both rejected")
    void repeatedOptionsAreRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchJobParameters.fromArguments(new String[] {
                    "--job=export", "--job=import"}))
                .withMessageContaining(BatchJobParameters.JOB_OPTION)
                .withMessageContaining("more than once");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchJobParameters.fromArguments(new String[] {
                    "--job=export",
                    "--business-date=2022-07-18",
                    "--business-date=2022071800"}))
                .withMessageContaining(BatchJobParameters.BUSINESS_DATE_OPTION)
                .withMessageContaining("more than once");
    }

    /**
     * Confirms a present-but-blank value is treated as an absent option, so a blank job option fails
     * on the missing argument rather than on an unrecognised token.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("a blank option value is treated as absent rather than as an empty token")
    void blankOptionValueIsTreatedAsAbsent() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchJobParameters.fromArguments(new String[] {"--job="}))
                .withMessageContaining(BatchJobParameters.JOB_OPTION)
                .withMessageContaining("required");

        BatchJobParameters parameters = BatchJobParameters.fromArguments(new String[] {
            "--job=export", "--business-date=   "});

        assertThat(parameters.businessDate()).isEmpty();
    }

    /**
     * Confirms a bare word carrying no option prefix is skipped, because a container command may
     * append positional arguments this type does not own.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("a bare token carrying no option prefix is ignored")
    void bareTokenCarryingNoOptionPrefixIsIgnored() {
        BatchJobParameters parameters = BatchJobParameters.fromArguments(new String[] {
            "export", "--job=export", null});

        assertThat(parameters.jobName()).isSameAs(BatchJobName.EXPORT);
    }

    /**
     * Confirms decoding is a function of its arguments alone, which is what makes a re-run of one
     * business date reproduce that date's output instead of the output for the day of the re-run.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("decoding one vector twice yields equal instances, so no clock is consulted")
    void decodingOneVectorTwiceYieldsEqualInstances() {
        String[] vector = {"--job=calculate-interest", "--business-date=2022-07-18"};

        assertThat(BatchJobParameters.fromArguments(vector))
                .isEqualTo(BatchJobParameters.fromArguments(vector))
                .hasSameHashCodeAs(BatchJobParameters.fromArguments(vector));
    }

    /**
     * Confirms the rendered form carries the job, the date token and the empty coordinate, and that
     * the job renders as its constant name rather than as its wire token.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("the rendered form carries the job name and date token and nothing surprising")
    void renderedFormCarriesTheJobNameAndDateTokenAndNothingSurprising() {
        String rendered = BatchJobParameters.fromArguments(
                new String[] {"--job=calculate-interest", "--business-date=2022-07-18"}).toString();

        assertThat(rendered)
                .contains(BatchJobName.CALCULATE_INTEREST.name())
                .contains(SEPARATED_TOKEN)
                .contains("Optional.empty")
                .doesNotContain(BatchJobName.CALCULATE_INTEREST.token());
    }

    /**
     * Pins this type's two option constants against the argument contract the module entry point
     * publishes, so the two spellings cannot drift apart unnoticed.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("the two option constants agree with the entry point character for character")
    void optionConstantsAgreeWithTheEntryPointCharacterForCharacter() {
        assertThat(BatchJobParameters.JOB_OPTION).isEqualTo(BatchApplication.JOB_OPTION);
        assertThat(BatchJobParameters.BUSINESS_DATE_OPTION)
                .isEqualTo(BatchApplication.BUSINESS_DATE_OPTION);

        for (BatchJobName job : BatchJobName.values()) {
            assertThat(BatchApplication.JOB_NAMES).contains(job.token());
        }
    }

    /**
     * Confirms a date token of the wrong width or character class is refused here, closing the gap
     * that {@link BusinessDate} documents as gated by its callers rather than by itself.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("a date token of the wrong width or character class is rejected")
    void malformedBusinessDateTokenIsRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchJobParameters.fromArguments(new String[] {
                    "--job=export", "--business-date=2022-7-18"}))
                .withMessageContaining(BatchJobParameters.BUSINESS_DATE_OPTION);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchJobParameters.fromArguments(new String[] {
                    "--job=export", "--business-date=2022-07-1X"}))
                .withMessageContaining(BatchJobParameters.BUSINESS_DATE_OPTION);
    }

    /**
     * Confirms a job token outside the closed set of seven is refused rather than defaulted, so a
     * misspelled override stops the task instead of running an unintended workload.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("a job token outside the closed set of seven is rejected")
    void jobTokenOutsideTheClosedSetIsRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchJobParameters.fromArguments(
                        new String[] {"--job=POST-TRANSACTIONS"}));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchJobParameters.fromArguments(
                        new String[] {"--job=post-transaction"}));
    }

    /**
     * Confirms a carried generation obliges a business date and refuses to disagree with one, so a
     * step cannot write into a partition its own parameters contradict.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("a carried generation requires a business date and must agree with it")
    void carriedGenerationRequiresAnAgreeingBusinessDate() {
        DatasetGeneration rejectGeneration = new DatasetGeneration(
                DatasetGeneration.DatasetFamily.DALYREJS, new BusinessDate(SEPARATED_TOKEN), 1);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new BatchJobParameters(BatchJobName.POST_TRANSACTIONS,
                        Optional.empty(), Optional.of(rejectGeneration)))
                .withMessageContaining("business date");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new BatchJobParameters(BatchJobName.POST_TRANSACTIONS,
                        Optional.of(new BusinessDate(COMPACT_TOKEN)),
                        Optional.of(rejectGeneration)))
                .withMessageContaining("disagrees");

        BatchJobParameters agreeing = new BatchJobParameters(BatchJobName.POST_TRANSACTIONS,
                Optional.of(new BusinessDate(SEPARATED_TOKEN)), Optional.of(rejectGeneration));

        assertThat(agreeing.targetGeneration()).contains(rejectGeneration);
    }

    /**
     * Confirms the constructor refuses an absent job and refuses a null holder where an empty one is
     * the documented spelling of absence.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("an absent job and a null holder are both rejected by the constructor")
    void absentJobAndNullHoldersAreRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new BatchJobParameters(
                        null, Optional.empty(), Optional.empty()))
                .withMessageContaining("job name");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new BatchJobParameters(
                        BatchJobName.EXPORT, null, Optional.empty()));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new BatchJobParameters(
                        BatchJobName.EXPORT, Optional.empty(), null));
    }

    /**
     * Confirms the insisting accessor reports a state failure rather than an argument failure, so a
     * log reader can tell a malformed command from a job demanding more than its invocation carried.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("insisting on an absent business date reports a state failure naming the job")
    void insistingOnAnAbsentBusinessDateReportsAStateFailure() {
        BatchJobParameters parameters = BatchJobParameters.fromArguments(
                new String[] {"--job=export"});

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(parameters::requireBusinessDate)
                .withMessageContaining(BatchJobName.EXPORT.token())
                .withMessageContaining(BatchJobParameters.BUSINESS_DATE_OPTION);
    }

    /**
     * Confirms the factory never manufactures a generation coordinate, because no option supplies one
     * and the family a job writes is the job's own knowledge rather than the operator's.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("the factory never manufactures a generation coordinate")
    void factoryNeverManufacturesAGenerationCoordinate() {
        for (BatchJobName job : BatchJobName.values()) {
            BatchJobParameters parameters = BatchJobParameters.fromArguments(new String[] {
                BatchJobParameters.JOB_OPTION + job.token(),
                BatchJobParameters.BUSINESS_DATE_OPTION + SEPARATED_TOKEN,
                "--generation=0001"});

            assertThat(parameters.targetGeneration()).isEmpty();
        }
    }
}
