package com.carddemo.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds the reporting task entry point to the commands the batch orchestrator actually dispatches.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Refactoring Rationale: the review that prompted this class found three state-machine states
 * dispatching {@code --job=} commands at a module that could not receive any of them, and the way that
 * failed was worse than the failure itself -- the image started a web server, so the container never
 * terminated and the state reported a timeout rather than a missing implementation. The three tokens now
 * exist as an enforced contract, and this test reads them out of the Terraform module rather than
 * restating them, so the two cannot drift apart in the one direction that matters: a token the
 * orchestrator sends and the image refuses.</p>
 *
 * <p>Assumptions: no application context is started anywhere here. Every assertion is about the argument
 * boundary, which by construction is evaluated before a context exists, so these tests need no database,
 * no parameter store and no credentials -- and asserting them without one is what keeps that property
 * true.</p>
 */
class ReportingTaskRunnerTest {

    /**
     * Repository-relative path of the Terraform module that dispatches these commands.
     *
     * <p>Assumptions: the path is relative to the module directory, which is where Surefire runs, so it
     * resolves the same way from a developer's shell and from continuous integration.</p>
     */
    private static final String STATE_MACHINE_SOURCE = "../../infra/modules/step-functions-batch/main.tf";

    /**
     * Matches every {@code --job=<token>} literal the state machine builds inline.
     *
     * <p>Assumptions: only the on-demand command is built this way. The two nightly commands are
     * interpolated from a Terraform map, which is why {@link #DECLARED_REPORTING_JOB} exists as well --
     * one pattern cannot see both forms, and a test that looked for only the literal form would have
     * declared the two nightly tokens unreachable.</p>
     */
    private static final Pattern DISPATCHED_JOB = Pattern.compile("'--job=([a-z-]+)'");

    /** Matches each {@code job = "<token>"} entry of the module's reporting-job map. */
    private static final Pattern DECLARED_REPORTING_JOB =
            Pattern.compile("job\\s*=\\s*\"([a-z-]+)\"");

    /** Opening of the Terraform local declaring the nightly reporting jobs. */
    private static final String REPORTING_JOBS_OPEN = "reporting_jobs = {";

    /** The declaration that follows the reporting-job map, bounding the region scanned for its entries. */
    private static final String REPORTING_JOBS_CLOSE = "reporting_task_states = {";

    /** A ten-character date token of the width the orchestrator validates before dispatching. */
    private static final String DATE_TOKEN = "2022-07-18";

    /**
     * Confirms every token the state machine dispatches at this module is a token this module accepts.
     *
     * <p>Refactoring Rationale: the comparison is made against the Terraform SOURCE rather than against a
     * list restated in this test. A restated list agrees with whatever it was copied from and cannot
     * detect the failure this test exists for, which is the orchestrator sending a token the image
     * refuses. Reading the source means adding a fourth state without adding a fourth task fails here.
     * </p>
     *
     * <p>Assumptions: two forms are read, because the module builds the commands two ways. The on-demand
     * command is an inline literal, while the two nightly commands are interpolated from the module's
     * reporting-job map, so the map's own entries are read for those. The daily chain's BATCH tokens are
     * excluded by scanning only the reporting map's region and not the batch map's, which is what keeps
     * this assertion about the commands aimed at THIS task definition.</p>
     */
    @Test
    @DisplayName("every token the state machine dispatches at this module is accepted by it")
    void dispatchedTokensAreAllAccepted() {
        String definition = readStateMachineSource();

        List<String> dispatched = new java.util.ArrayList<>();
        Matcher inline = DISPATCHED_JOB.matcher(definition);
        while (inline.find()) {
            dispatched.add(inline.group(1));
        }

        // WHY : Assumptions: the map region is bounded by the declaration that follows it rather than by
        //       brace counting. Brace counting over HCL would have to understand strings and comments to
        //       be correct, and the two declarations are adjacent by construction because the second
        //       comprehends the first -- so if either name changes, the source read below finds nothing
        //       and the emptiness assertion fails rather than the containment passing vacuously.
        int open = definition.indexOf(REPORTING_JOBS_OPEN);
        int close = definition.indexOf(REPORTING_JOBS_CLOSE);
        assertThat(open).as("the reporting-job map is declared").isNotNegative();
        assertThat(close).as("the reporting-task states follow the map").isGreaterThan(open);

        Matcher declared = DECLARED_REPORTING_JOB.matcher(definition.substring(open, close));
        while (declared.find()) {
            dispatched.add(declared.group(1));
        }

        assertThat(dispatched)
                .as("the state machine dispatches literal --job= tokens at the reporting task")
                .isNotEmpty();
        assertThat(ReportingTaskRunner.JOB_NAMES)
                .as("every dispatched token resolves to an accepted task name")
                .containsAll(dispatched);
        assertThat(dispatched)
                .as("no accepted task name is unreachable from the state machine")
                .containsAll(ReportingTaskRunner.JOB_NAMES);
    }

    /**
     * Confirms the presence of the job option is what selects task mode.
     *
     * <p>Assumptions: service mode has to remain the behaviour for an argument list that carries no job
     * option, including an empty one and a null one, because that is how the ECS SERVICE starts this same
     * task definition. A change that made task mode the default would stop the online API starting at
     * all.</p>
     */
    @Test
    @DisplayName("task mode is selected by the job option and by nothing else")
    void taskModeIsSelectedByTheJobOption() {
        assertThat(ReportingTaskRunner.isTaskInvocation(null)).isFalse();
        assertThat(ReportingTaskRunner.isTaskInvocation(new String[0])).isFalse();
        assertThat(ReportingTaskRunner.isTaskInvocation(
                new String[] {"--spring.profiles.active=prod"})).isFalse();
        assertThat(ReportingTaskRunner.isTaskInvocation(
                new String[] {"--business-date=" + DATE_TOKEN})).isFalse();

        for (String name : ReportingTaskRunner.JOB_NAMES) {
            assertThat(ReportingTaskRunner.isTaskInvocation(new String[] {"--job=" + name}))
                    .as("task mode for %s", name)
                    .isTrue();
        }
    }

    /**
     * Confirms an unrecognised or absent task name is refused before any context is built.
     *
     * @param args the whole command line to reject, given as one space-separated string for readability
     */
    @ParameterizedTest(name = "refuses [{0}]")
    @ValueSource(strings = {
        "",
        "--job=",
        "--job=generate-statement",
        "--job=GENERATE-REPORTS",
        "--job=post-transactions"})
    @DisplayName("an absent or unrecognised task name is refused")
    void unrecognisedTaskNamesAreRefused(String args) {
        String[] argv = args.isEmpty() ? new String[0] : args.split(" ");
        assertThatThrownBy(() -> ReportingTaskRunner.requiredJobName(argv))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Confirms the two nightly tasks require a business date of the orchestrator's own width.
     *
     * <p>Assumptions: the width is asserted as well as the presence, because the orchestrator has already
     * refused anything that is not ten characters -- its choice states match a ten-character template --
     * so a boundary here that accepted eight would hold a different contract from the one upstream.</p>
     */
    @Test
    @DisplayName("the nightly tasks require a ten-character business date and nothing else")
    void nightlyTasksRequireABusinessDate() {
        for (String name : List.of("generate-statements", "generate-reports")) {
            Map<String, String> parameters = ReportingTaskRunner.taskParameters(
                    name, new String[] {"--job=" + name, "--business-date=" + DATE_TOKEN});
            assertThat(parameters).containsExactly(
                    Map.entry(ReportingTaskRunner.BUSINESS_DATE_PARAMETER, DATE_TOKEN));

            assertThatThrownBy(() -> ReportingTaskRunner.taskParameters(
                    name, new String[] {"--job=" + name}))
                    .as("%s without a business date", name)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ReportingTaskRunner.taskParameters(
                    name, new String[] {"--job=" + name, "--business-date=20220718"}))
                    .as("%s with an eight-character date", name)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * Confirms the on-demand task requires a range and a report type, and takes no business date.
     *
     * <p>Assumptions: the disjointness is asserted rather than assumed. A union of every option would
     * accept a nightly command carrying a report type and an on-demand command carrying a business date,
     * neither of which the orchestrator sends and neither of which any task would read.</p>
     */
    @Test
    @DisplayName("the on-demand task requires a range and a report type")
    void onDemandTaskRequiresARangeAndAType() {
        String[] complete = {
            "--job=generate-report",
            "--start-date=2022-07-01",
            "--end-date=" + DATE_TOKEN,
            "--report-type=MONTHLY"};

        Map<String, String> parameters =
                ReportingTaskRunner.taskParameters("generate-report", complete);
        assertThat(parameters).containsOnlyKeys(
                ReportingTaskRunner.START_DATE_PARAMETER,
                ReportingTaskRunner.END_DATE_PARAMETER,
                ReportingTaskRunner.REPORT_TYPE_PARAMETER);
        assertThat(parameters).doesNotContainKey(ReportingTaskRunner.BUSINESS_DATE_PARAMETER);

        assertThatThrownBy(() -> ReportingTaskRunner.taskParameters("generate-report",
                new String[] {"--job=generate-report", "--end-date=" + DATE_TOKEN,
                    "--report-type=MONTHLY"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportingTaskRunner.taskParameters("generate-report",
                new String[] {"--job=generate-report", "--start-date=2022-07-01",
                    "--end-date=" + DATE_TOKEN, "--report-type=   "}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Confirms a malformed command line ends in the hard-failure tier rather than in a clean one.
     *
     * <p>Refactoring Rationale: the tier is asserted rather than merely the refusal, because the status is
     * what the orchestrator reads. Every gate on these states tests for equality with zero, so a usage
     * failure reported as zero would let the chain continue past a state that produced nothing -- and a
     * refusal that returns before any context is built is the one case where returning zero by accident is
     * easiest.</p>
     */
    @Test
    @DisplayName("a malformed command line exits in the hard-failure tier")
    void malformedCommandLineExitsHard() {
        assertThat(ReportingTaskRunner.execute(new String[] {"--job=not-a-task"}))
                .isEqualTo(ReportingTaskRunner.EXIT_STATUS_HARD_FAILURE);
        assertThat(ReportingTaskRunner.execute(new String[] {"--job=generate-reports"}))
                .isEqualTo(ReportingTaskRunner.EXIT_STATUS_HARD_FAILURE);
        assertThat(ReportingTaskRunner.EXIT_STATUS_HARD_FAILURE)
                .isNotEqualTo(ReportingTaskRunner.EXIT_STATUS_CLEAN);
    }

    /**
     * Confirms the usage text names every accepted task and the options each one needs.
     *
     * <p>Assumptions: this is asserted because the usage text is the only documentation an operator running
     * the image by hand receives, and it is assembled from the same list the validator checks against, so a
     * task added without its options appearing here would be discoverable only by reading the source.</p>
     */
    @Test
    @DisplayName("the usage text names every accepted task and its options")
    void usageNamesEveryTask() {
        String usage = ReportingTaskRunner.usage();
        for (String name : ReportingTaskRunner.JOB_NAMES) {
            assertThat(usage).contains(name);
        }
        assertThat(usage).contains(ReportingTaskRunner.BUSINESS_DATE_OPTION);
        assertThat(usage).contains(ReportingTaskRunner.START_DATE_OPTION);
        assertThat(usage).contains(ReportingTaskRunner.END_DATE_OPTION);
        assertThat(usage).contains(ReportingTaskRunner.REPORT_TYPE_OPTION);
    }

    /**
     * A failed run's diagnostic carries the class chain and the origin frame and never a message.
     *
     * <p>Refactoring Rationale: the failure tier of this class used to hand the throwable itself to the
     * journal, which renders the whole stack trace including every message in the cause chain. This
     * module assembles customer names, street addresses and transaction descriptions, so a message
     * composed while one of those was in hand became a line in a retained log stream that every holder of
     * log access can read. The two renderers replacing it are asserted here to be message-free, because
     * that property is the whole point of them and nothing in the build would notice if a later edit put
     * {@code getMessage()} back.</p>
     */
    @Test
    @DisplayName("a failure diagnostic names the class chain and the origin frame, never the message")
    void aFailureDiagnosticCarriesNoMessage() {
        String secret = "CARDHOLDER JOHN Q PUBLIC OF 410 TERRY AVE N";
        IllegalStateException cause = new IllegalStateException(secret);
        RuntimeException wrapper = new RuntimeException("wrapping " + secret, cause);

        assertThat(ReportingTaskRunner.causeChainOf(wrapper))
                .isEqualTo(RuntimeException.class.getName() + "<-"
                        + IllegalStateException.class.getName())
                .doesNotContain(secret)
                .doesNotContain("JOHN")
                .doesNotContain("TERRY");

        // Assumptions: the origin frame comes from the DEEPEST cause, so it names this method rather than
        //   whatever caught and re-threw. Both throwables were constructed here, so both traces begin in
        //   this method and the assertion is on the class and method rather than on a line number, which
        //   any edit above would move.
        assertThat(ReportingTaskRunner.originFrameOf(wrapper))
                .startsWith(ReportingTaskRunnerTest.class.getName()
                        + ".aFailureDiagnosticCarriesNoMessage:")
                .doesNotContain(secret);
    }

    /**
     * A cyclic cause chain is bounded rather than followed forever, and an absent trace is named.
     *
     * <p>Assumptions: a self-referential chain is constructed deliberately, because a framework wrapper
     * that initialises its own cause is the realistic source of one and a diagnostic that loops turns a
     * reportable failure into a task the orchestrator waits on until its ceiling elapses.</p>
     */
    @Test
    @DisplayName("a cyclic cause chain is bounded and a throwable with no trace names the absence")
    void aCyclicChainIsBoundedAndAnAbsentTraceIsNamed() {
        RuntimeException selfCaused = new SelfCausedFailure();

        String chain = ReportingTaskRunner.causeChainOf(selfCaused);
        assertThat(chain.split("<-", -1).length)
                .isLessThanOrEqualTo(ReportingTaskRunner.CAUSE_CHAIN_LIMIT);
        assertThat(ReportingTaskRunner.originFrameOf(selfCaused)).isNotBlank();

        Throwable traceless = new IllegalStateException("withheld");
        traceless.setStackTrace(new StackTraceElement[0]);
        assertThat(ReportingTaskRunner.originFrameOf(traceless))
                .isEqualTo(ReportingTaskRunner.ORIGIN_FRAME_UNAVAILABLE);
    }

    /**
     * A throwable whose cause is itself, used to prove the cause walk terminates.
     *
     * <p>Assumptions: the cycle is built by overriding the accessor rather than by calling
     * {@code initCause}, because the platform refuses to set a throwable as its own cause. A framework
     * wrapper that computes its cause can still return itself, which is the condition being guarded
     * against.</p>
     */
    private static final class SelfCausedFailure extends RuntimeException {

        /** Declared because the platform type is serialisable and a fixed value keeps that stable. */
        private static final long serialVersionUID = 1L;

        /**
         * Answers this instance as its own cause.
         *
         * @return this instance, which is the cycle the walk must not follow
         */
        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    /**
     * Reads the Terraform module that builds the dispatched commands.
     *
     * @return the whole file as text; never {@code null}
     * @throws IllegalStateException if the file cannot be found or read, because every assertion that
     *     depends on it would otherwise pass vacuously against empty text
     */
    private static String readStateMachineSource() {
        java.nio.file.Path path = java.nio.file.Path.of(STATE_MACHINE_SOURCE);
        if (!java.nio.file.Files.isReadable(path)) {
            throw new IllegalStateException(
                    "the batch state-machine module is not readable at " + path.toAbsolutePath());
        }
        try (InputStream stream = java.nio.file.Files.newInputStream(path)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(
                    "the batch state-machine module could not be read at " + path.toAbsolutePath(),
                    failure);
        }
    }
}
