package com.carddemo.batch.dto;

import com.carddemo.batch.BatchApplication;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies that {@link BatchJobParameters} refuses every argument combination it documents as
 * impossible, and that {@link BatchApplication#parseArguments(String[])} -- the one parser this
 * module ships -- decodes the container command line the orchestration state actually sends into
 * that record while refusing, by name, every token that is not one of the two options it owns.
 *
 * <p>Refactoring Rationale: the record CARRIES parameters and no longer decodes them, so the
 * decoding cases below call the production entry point rather than a factory on the record. This
 * class previously exercised a {@code fromArguments(String[])} factory that production never called,
 * because the entry point had a parser of its own. The two had already diverged on a rule that
 * matters -- the factory admitted six of the seven jobs with no business date while the entry point
 * required one for every job -- so every case here could pass while the parsing that actually ran
 * behaved differently. The factory is deleted and each of its former call sites now names the entry
 * point, which is what makes a green run of this class evidence about the code the container
 * executes. Reassembling the entry point's parsing steps inside this class was the alternative and is
 * rejected on the same ground the factory was: it would be a second parser again, differing only in
 * who wrote it.</p>
 *
 * <p>Every expectation below is pinned either to an immutable reference artifact or to the argument
 * contract published by {@link BatchApplication}, never to a value invented here. The two vectors
 * exercised throughout are the two that genuinely occur: the container override
 * {@code ["--job=calculate-interest", "--business-date=2022-07-18"]} that the state machine supplies,
 * and the compact {@code PARM='2022071800'} that {@code app/jcl/INTCALC.jcl:22} injects.</p>
 *
 * <p>Assumptions: the business-date requirement has two layers and both are covered, because a test
 * of either layer alone would leave the other free to move. The entry point requires the option for
 * ALL SEVEN jobs, on the job-instance identity ground its own documentation gives, and that layer is
 * asserted by walking the whole enumeration through the parser. Underneath it the record's
 * constructor requires a date for the accrual job specifically, on the two grounds its table records,
 * and that layer is asserted by constructing the record directly -- otherwise the stricter parse
 * above would shadow it, and the invariant would stop being checked while still being documented.</p>
 *
 * <p>Refactoring Rationale: this block previously argued the opposite of what it now asserts. It held
 * that a strict parser "would fail the container the first time anyone passes a profile selection or a
 * property override", and it pinned the permissive behaviour positively so that the purpose would
 * survive a future author's tidying. That reasoning is withdrawn, and it is withdrawn on evidence
 * rather than on taste: the state definition that dispatches this container passes exactly the two
 * options and nothing else, every deployment setting -- the profile included -- reaches the process
 * through the environment, and the tolerance the block defended was observed admitting
 * {@code --business-dat=2022-07-18} in silence and then failing the run for the unrelated-looking
 * reason that no business date had been supplied. The strictness is therefore asserted positively
 * here, in the same place and for the same reason the tolerance was: so that its purpose survives.</p>
 *
 * <p>Alternatives Considered: keeping the tolerance and adding a separate warning for a token that
 * looks like a near-miss of a known option. Rejected because it needs a similarity rule to decide what
 * "looks like" means, and a rule loose enough to catch {@code --business-dat=} would also warn about
 * arguments that are not mistakes, while one tight enough to avoid that would miss the next typo.
 * Refusing everything unrecognised needs no such rule.</p>
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
        BatchJobParameters parameters = BatchApplication.parseArguments(
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
        BatchJobParameters parameters = BatchApplication.parseArguments(
                new String[] {BatchJobParameters.JOB_OPTION + BatchJobName.CALCULATE_INTEREST.token(),
                    BatchJobParameters.BUSINESS_DATE_OPTION + COMPACT_TOKEN});

        assertThat(parameters.requireBusinessDate().token())
                .isEqualTo(COMPACT_TOKEN)
                .doesNotContain("-");
    }

    /**
     * Confirms a framework option on the command line is refused by name rather than absorbed, so a
     * command line that is not the one the caller wrote is reported instead of run.
     *
     * <p>Assumptions: the two options beside it are well formed, so the refusal is attributable to the
     * framework option and not to a fault in the pair. The refused token is asserted to appear in the
     * message, because a refusal that does not name the offending token sends an operator to the usage
     * text with no indication of which of their arguments to change.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("a framework option on the command line is rejected by name")
    void frameworkOptionsAreRejectedRatherThanIgnored() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchApplication.parseArguments(new String[] {
                    "--spring.profiles.active=prod",
                    "--job=post-transactions",
                    "--business-date=" + SEPARATED_TOKEN,
                    "--logging.level.root=INFO"}))
                .withMessageContaining("--spring.profiles.active=prod")
                .withMessageContaining("is not an option this module accepts");
    }

    /**
     * Confirms the two options this module does own are still accepted when they arrive alone, so the
     * refusal above is attributable to the extra token rather than to the pair beside it.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("the two owned options alone decode without complaint")
    void theTwoOwnedOptionsAloneAreAccepted() {
        BatchJobParameters parameters = BatchApplication.parseArguments(new String[] {
            "--job=post-transactions",
            "--business-date=" + SEPARATED_TOKEN});

        assertThat(parameters.jobName()).isSameAs(BatchJobName.POST_TRANSACTIONS);
        assertThat(parameters.businessDate()).map(BusinessDate::token).contains(SEPARATED_TOKEN);
        assertThat(parameters.targetGeneration()).isEmpty();
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
        BatchJobParameters jobFirst = BatchApplication.parseArguments(
                new String[] {"--job=calculate-interest", "--business-date=2022-07-18"});
        BatchJobParameters dateFirst = BatchApplication.parseArguments(
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
                .isThrownBy(() -> BatchApplication.parseArguments(
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
                .isThrownBy(() -> BatchApplication.parseArguments(new String[] {}))
                .withMessageContaining(BatchJobParameters.JOB_OPTION);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchApplication.parseArguments(null))
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
            BatchJobParameters parameters = BatchApplication.parseArguments(new String[] {
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
                .isThrownBy(() -> BatchApplication.parseArguments(
                        new String[] {BatchJobParameters.JOB_OPTION
                                + BatchJobName.CALCULATE_INTEREST.token()}))
                .withMessageContaining(BatchJobParameters.BUSINESS_DATE_OPTION);
    }

    /**
     * Walks the whole enumeration to pin BOTH requiredness rules, so neither the entry point's rule nor
     * the record's own invariant can drift for any of the seven jobs.
     *
     * <p>Refactoring Rationale: an earlier revision of this case asserted that exactly ONE of the seven
     * jobs required a business date and that the other six parsed without one. That was true of a second
     * parser declared on the record and never reached by production, while the entry point had always
     * required a date for every job -- so the suite certified a rule the running code did not apply.
     * That parser is gone. The case now pins the two rules that genuinely exist and states how they
     * relate: the entry point requires a date for EVERY job, which SUBSUMES the record's invariant that
     * the accrual job in particular must carry one.
     *
     * <p>Assumptions: the record's invariant is still exercised directly, through the constructor, and
     * not merely implied by the stricter parse. If the entry point were ever relaxed, that invariant is
     * what would still refuse a dateless accrual run, so it has to be asserted on its own terms rather
     * than shadowed by a rule that happens to be stricter today.
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("every job requires a business date at the entry point, and the accrual job also by invariant")
    void everyJobRequiresABusinessDateAndTheAccrualJobAlsoByInvariant() {
        for (BatchJobName job : BatchJobName.values()) {
            String[] withoutADate = {BatchJobParameters.JOB_OPTION + job.token()};

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("%s must not parse without a business date", job.token())
                    .isThrownBy(() -> BatchApplication.parseArguments(withoutADate))
                    .withMessageContaining(BatchJobParameters.BUSINESS_DATE_OPTION);

            BatchJobParameters parsed = BatchApplication.parseArguments(new String[] {
                BatchJobParameters.JOB_OPTION + job.token(),
                BatchJobParameters.BUSINESS_DATE_OPTION + SEPARATED_TOKEN});

            assertThat(parsed.jobName()).isSameAs(job);
            assertThat(parsed.businessDate()).map(BusinessDate::token).contains(SEPARATED_TOKEN);
        }

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("the record's own invariant singles out the accrual job")
                .isThrownBy(() -> new BatchJobParameters(
                        BatchJobName.CALCULATE_INTEREST, Optional.empty(), Optional.empty()))
                .withMessageContaining(BatchJobName.CALCULATE_INTEREST.token());

        assertThat(new BatchJobParameters(
                        BatchJobName.EXPORT, Optional.empty(), Optional.empty()).businessDate())
                .as("the record permits a dateless set for a job other than the accrual job")
                .isEmpty();
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
                .isThrownBy(() -> BatchApplication.parseArguments(new String[] {
                    "--job=export", "--job=import"}))
                .withMessageContaining(BatchJobParameters.JOB_OPTION)
                .withMessageContaining("more than once");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchApplication.parseArguments(new String[] {
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
    @DisplayName("a blank option value is treated as absent and therefore refused")
    void blankOptionValueIsTreatedAsAbsentAndRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchApplication.parseArguments(new String[] {"--job="}))
                .withMessageContaining(BatchJobParameters.JOB_OPTION)
                .withMessageContaining("required");

        // WHY : Refactoring Rationale: a blank date is ABSENT, and absent is refused here. An earlier
        //       revision of this case asserted the parse SUCCEEDED with an empty date holder, because
        //       it exercised a second parser on the record that admitted a dateless command. That
        //       parser never ran in production and is gone; this entry point has always required a
        //       date for every job, so the blank value is refused and the case now says so.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchApplication.parseArguments(new String[] {
                    "--job=export", "--business-date=   "}))
                .withMessageContaining(BatchJobParameters.BUSINESS_DATE_OPTION)
                .withMessageContaining("required");
    }

    /**
     * Confirms a bare word carrying no option prefix is refused by name rather than skipped.
     *
     * <p>Assumptions: the bare word used is {@code export}, a real job token, because that is the
     * shape of the mistake this refusal exists to catch -- an operator writing
     * {@code --job export} with a space where the equals sign belongs. Skipping it left the run to
     * fail for the unrelated reason that no job had been named.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("a bare token carrying no option prefix is rejected by name")
    void bareTokenCarryingNoOptionPrefixIsRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchApplication.parseArguments(new String[] {
                    "export", "--job=export", "--business-date=" + SEPARATED_TOKEN}))
                .withMessageContaining("export")
                .withMessageContaining("is not an option this module accepts");
    }

    /**
     * Confirms a null ELEMENT is still skipped, which is a different case from a bare word and stays
     * permissive on purpose.
     *
     * <p>Assumptions: a process argument vector cannot carry a null -- the platform passes every
     * argument as text -- so a null can only arrive from Java code calling the parser directly, and a
     * refusal naming it would have no token to name. Skipping leaves the two option gates to report
     * whatever is genuinely missing, which is why this case did not change when the bare-word case
     * did.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("a null argument element is skipped rather than refused")
    void nullArgumentElementIsSkipped() {
        BatchJobParameters parameters = BatchApplication.parseArguments(new String[] {
            "--job=export", null, "--business-date=" + SEPARATED_TOKEN});

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

        assertThat(BatchApplication.parseArguments(vector))
                .isEqualTo(BatchApplication.parseArguments(vector))
                .hasSameHashCodeAs(BatchApplication.parseArguments(vector));
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
        String rendered = BatchApplication.parseArguments(
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
                .isThrownBy(() -> BatchApplication.parseArguments(new String[] {
                    "--job=export", "--business-date=2022-7-18"}))
                .withMessageContaining(BatchJobParameters.BUSINESS_DATE_OPTION);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchApplication.parseArguments(new String[] {
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
                .isThrownBy(() -> BatchApplication.parseArguments(
                        new String[] {"--job=POST-TRANSACTIONS"}));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchApplication.parseArguments(
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
        // WHY : Refactoring Rationale: the parameters are CONSTRUCTED rather than parsed, because a
        //       dateless set can no longer be produced by parsing -- the entry point requires a date
        //       for every job. The accessor's contract is still worth pinning: a caller holding a set
        //       assembled in code, as a job that supplies its own generation coordinate does, can still
        //       reach it with an empty holder. Constructing directly is what keeps this case about the
        //       ACCESSOR rather than about the parser that can no longer reach that state.
        BatchJobParameters parameters = new BatchJobParameters(
                BatchJobName.EXPORT, Optional.empty(), Optional.empty());

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(parameters::requireBusinessDate)
                .withMessageContaining(BatchJobName.EXPORT.token())
                .withMessageContaining(BatchJobParameters.BUSINESS_DATE_OPTION);
    }

    /**
     * Confirms the factory never manufactures a generation coordinate, because no option supplies one
     * and the family a job writes is the job's own knowledge rather than the operator's.
     *
     * <p>Refactoring Rationale: this case formerly appended {@code --generation=0001} to prove the
     * parser did not honour an option nobody defined. That token is now refused as unrecognised, so
     * appending it would assert the refusal a second time instead of asserting the property this case
     * exists for. The command line is reduced to the two owned options, which is where the property is
     * actually observable: a coordinate the parser never manufactures is empty for a command line that
     * mentions no generation at all.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("the factory never manufactures a generation coordinate")
    void factoryNeverManufacturesAGenerationCoordinate() {
        for (BatchJobName job : BatchJobName.values()) {
            BatchJobParameters parameters = BatchApplication.parseArguments(new String[] {
                BatchJobParameters.JOB_OPTION + job.token(),
                BatchJobParameters.BUSINESS_DATE_OPTION + SEPARATED_TOKEN});

            assertThat(parameters.targetGeneration()).isEmpty();
        }
    }

    /**
     * Confirms an option nobody defined is refused rather than quietly discarded, using the coordinate
     * option a reader of this class might reasonably expect to exist.
     *
     * <p>Assumptions: {@code --generation=} is the right token to test with. No option supplies a
     * generation coordinate, so an operator who believes one does is making exactly the mistake this
     * refusal is for, and discarding it would let them believe a coordinate they supplied had been
     * honoured.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("an undefined generation option is refused, not discarded")
    void undefinedGenerationOptionIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BatchApplication.parseArguments(new String[] {
                    BatchJobParameters.JOB_OPTION + BatchJobName.EXPORT.token(),
                    BatchJobParameters.BUSINESS_DATE_OPTION + SEPARATED_TOKEN,
                    "--generation=0001"}))
                .withMessageContaining("--generation=0001")
                .withMessageContaining("is not an option this module accepts");
    }
}
