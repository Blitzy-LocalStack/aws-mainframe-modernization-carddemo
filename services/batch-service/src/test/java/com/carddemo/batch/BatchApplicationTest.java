package com.carddemo.batch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.batch.dto.BatchErrorEvent;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.service.BatchErrorPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.context.support.GenericApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/**
 * Verifies the properties of the batch entry point that a reading of the class cannot settle: that
 * an echoed command-line value can neither forge nor flood a log record, that every one of the
 * three exit tiers is reported rather than only the failing ones, and that a run's failure
 * notification is published for exactly the tiers that stop the chain and for no others.
 *
 * <p>Assumptions: the tier assertions read captured log events rather than the returned status,
 * because the status was already correct before these tests existed and the defect was that the middle
 * tier was invisible. Asserting the return value would therefore pass against the defect.</p>
 *
 * <p>Refactoring Rationale: this block opened by counting "the two properties", and the count is
 * withdrawn rather than raised. It was accurate when written and the notification assertions made it a
 * third, so a figure stated beside a list that grows is a figure that will be wrong before anyone
 * notices -- exactly the class of defect these notification assertions were added to close.</p>
 */
class BatchApplicationTest {

    /** A well-formed job token, so a rejection under test is caused by the value beside it. */
    private static final String VALID_JOB = "--job=post-transactions";

    /**
     * Repository-relative path of the Terraform module that dispatches this container's commands.
     *
     * <p>Assumptions: relative to the module directory, which is where Surefire runs, so it resolves the
     * same way from a developer's shell and from continuous integration.</p>
     */
    private static final String STATE_MACHINE_SOURCE =
            "../../infra/modules/step-functions-batch/main.tf";

    /** Opening of the Terraform local declaring the daily chain's jobs. */
    private static final String BATCH_JOBS_OPEN = "batch_jobs = {";

    /** The declaration that follows it, bounding the region scanned for its entries. */
    private static final String BATCH_JOBS_CLOSE = "reporting_jobs = {";

    /** Matches each {@code job = "<token>"} entry of the daily chain's job map. */
    private static final Pattern DECLARED_BATCH_JOB = Pattern.compile("job\\s*=\\s*\"([a-z-]+)\"");

    /**
     * Matches every {@code --job=<token>} literal dispatched inline at THIS container.
     *
     * <p>Assumptions: the container name is required on the preceding line, because the module dispatches
     * inline literals at two different containers -- the dataset round-trip states at this one and the
     * on-demand report state at the reporting one -- and a pattern that ignored the distinction would
     * demand this entry point accept a token belonging to a different image. That is not a hypothetical:
     * the sibling assertion in the reporting module was written without the distinction and broke the
     * moment the dataset states landed.</p>
     */
    private static final Pattern INLINE_BATCH_JOB = Pattern.compile(
            "Name\\s*=\\s*var\\.batch_container_name\\s*\\R\\s*\"Command\\.\\$\"\\s*="
                    + "\\s*\"States\\.Array\\('--job=([a-z-]+)'");

    /** A well-formed business-date token in the ten-character hyphenated spelling. */
    private static final String VALID_DATE = "--business-date=2022-07-18";

    /** The appender capturing what the entry point actually wrote during one test. */
    private ListAppender<ILoggingEvent> captured;

    /** The entry point's own logger, held so the appender can be detached again. */
    private Logger applicationLogger;

    /**
     * Attaches a list appender to the entry point's logger at a level that admits every tier.
     *
     * <p>Assumptions: the level is lowered to informational explicitly, because the clean tier is
     * reported there and a configuration that admitted only warnings would make an empty capture
     * indistinguishable from a passing assertion.</p>
     */
    @BeforeEach
    void attachAppender() {
        this.applicationLogger = (Logger) LoggerFactory.getLogger(BatchApplication.class);
        this.captured = new ListAppender<>();
        this.captured.start();
        this.applicationLogger.addAppender(this.captured);
        this.applicationLogger.setLevel(Level.INFO);
    }

    /**
     * Detaches the appender so one test's capture cannot be read by the next.
     */
    @AfterEach
    void detachAppender() {
        this.applicationLogger.detachAppender(this.captured);
        this.captured.stop();
    }

    /**
     * Confirms a value carrying a line feed cannot split the diagnostic it is echoed into, which is the
     * forged-record failure the sanitisation exists to prevent.
     */
    @Test
    @DisplayName("a rejected job token carrying a line break is echoed with no line break")
    void rejectedJobTokenCannotForgeALogRecord() {
        String forged = "post-transactions\nWARN event=batch.job.outcome outcome=CLEAN";

        assertThatThrownBy(() -> BatchApplication.requiredJobName(new String[] {"--job=" + forged}))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(rejection -> {
                    assertThat(rejection.getMessage()).doesNotContain("\n");
                    assertThat(rejection.getMessage()).doesNotContain("\r");
                    assertThat(rejection.getMessage()).contains("is not a job this module runs");
                });
    }

    /**
     * Confirms an oversized value is bounded and the truncation is marked, so a single malformed
     * invocation cannot write an arbitrarily large record and a reader is not misled about its width.
     */
    @Test
    @DisplayName("a rejected business date longer than the echo bound is truncated and marked")
    void rejectedBusinessDateIsBounded() {
        String oversized = "9".repeat(500);

        assertThatThrownBy(() -> BatchApplication
                .requiredBusinessDate(new String[] {"--business-date=" + oversized}))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(rejection -> {
                    assertThat(rejection.getMessage()).contains(BatchApplication.TRUNCATION_MARKER);
                    assertThat(rejection.getMessage().length())
                            .isLessThan(oversized.length());
                });
    }

    /**
     * Confirms an absent value yields the empty string rather than the word {@code null}, because the
     * exit description this is applied to is absent whenever a job records none.
     */
    @Test
    @DisplayName("an absent value sanitises to the empty string")
    void absentValueSanitisesToEmpty() {
        assertThat(BatchApplication.sanitiseForDiagnostic(null)).isEmpty();
    }

    /**
     * Confirms a legitimate value passes through unchanged, so the sanitisation costs nothing on the
     * ordinary path.
     */
    @Test
    @DisplayName("a printable value is sanitised to itself")
    void printableValueIsUnchanged() {
        assertThat(BatchApplication.sanitiseForDiagnostic("rejects=17 job=post-transactions"))
                .isEqualTo("rejects=17 job=post-transactions");
    }

    /**
     * Confirms a control character is replaced rather than deleted, so the length of the value and the
     * position of the offending byte both stay readable.
     */
    @Test
    @DisplayName("a control character is replaced in place, not removed")
    void controlCharacterIsReplacedInPlace() {
        assertThat(BatchApplication.sanitiseForDiagnostic("a\tb\u0000c")).isEqualTo("a?b?c");
    }

    /**
     * Confirms the command line is validated before anything else happens and reports the usage tier,
     * with the rejected value named in the log line rather than only on standard error.
     */
    @Test
    @DisplayName("a malformed command line reports the usage code at the hard-failure tier")
    void malformedCommandLineReportsUsageCode() {
        int status = BatchApplication.execute(new String[] {VALID_JOB});

        assertThat(status).isEqualTo(BatchApplication.EXIT_STATUS_HARD_FAILURE);
        assertThat(this.captured.list).isNotEmpty();
        assertThat(this.captured.list.getFirst().getFormattedMessage())
                .contains("event=batch.usage.rejected")
                .contains(BatchApplication.ERROR_CODE_USAGE);
    }

    /**
     * Confirms a well-formed command line passes validation, so the previous test's failure is
     * attributable to the missing option and not to the option syntax.
     */
    @Test
    @DisplayName("both options are accepted when well formed")
    void wellFormedOptionsAreAccepted() {
        String[] arguments = {VALID_JOB, VALID_DATE};

        assertThat(BatchApplication.requiredJobName(arguments)).isEqualTo("post-transactions");
        assertThat(BatchApplication.requiredBusinessDate(arguments)).isEqualTo("2022-07-18");
    }

    /**
     * Confirms a clean night reports a line at all, which is the outcome that previously produced none.
     */
    @Test
    @DisplayName("a clean completion reports the clean tier at informational level")
    void cleanCompletionIsReported() {
        JobExecution execution = executionWith(BatchStatus.COMPLETED, ExitStatus.COMPLETED);

        BatchApplication.logJobOutcome("post-transactions", "2022-07-18", execution,
                BatchApplication.EXIT_STATUS_CLEAN);

        assertThat(this.captured.list).hasSize(1);
        assertThat(this.captured.list.getFirst().getLevel()).isEqualTo(Level.INFO);
        assertThat(this.captured.list.getFirst().getFormattedMessage())
                .contains("event=batch.job.outcome")
                .contains("outcome=" + BatchReturnCode.CLEAN.name())
                .contains("exitStatus=" + BatchApplication.EXIT_STATUS_CLEAN)
                .contains("job=post-transactions")
                .contains("businessDate=2022-07-18");
    }

    /**
     * Confirms the soft-warn tier is reported at warn level, carries the orchestration's own warn token
     * so one search matches both the log stream and the execution history, and passes the job's exit
     * description through as the detail where a reject count is recorded.
     */
    @Test
    @DisplayName("a soft-warn completion reports the orchestration warn token and the job detail")
    void softWarnCompletionIsReported() {
        JobExecution execution = executionWith(BatchStatus.COMPLETED,
                new ExitStatus(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS, "rejects=17"));

        BatchApplication.logJobOutcome("post-transactions", "2022-07-18", execution,
                BatchApplication.EXIT_STATUS_SOFT_WARN);

        assertThat(this.captured.list).hasSize(1);
        assertThat(this.captured.list.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(this.captured.list.getFirst().getFormattedMessage())
                .contains("outcome=" + BatchReturnCode.SOFT_WARN.name())
                .contains("code=" + BatchApplication.WARNING_CODE_POSTING_REJECTS)
                .contains("exitStatus=" + BatchApplication.EXIT_STATUS_SOFT_WARN)
                .contains("detail=rejects=17");
    }

    /**
     * Confirms a completion that failed without raising anything is reported too, which is the third
     * outcome that used to reach the orchestrator as an exit status with no log line at all, and that
     * a multi-line exit description cannot split the record.
     */
    @Test
    @DisplayName("a failed completion reports the failure tier with a single-line detail")
    void failedCompletionIsReportedOnOneLine() {
        JobExecution execution = executionWith(BatchStatus.FAILED,
                new ExitStatus("FAILED", "java.lang.IllegalStateException\n\tat step.write()"));

        BatchApplication.logJobOutcome("post-transactions", "2022-07-18", execution,
                BatchApplication.EXIT_STATUS_HARD_FAILURE);

        assertThat(this.captured.list).hasSize(1);
        assertThat(this.captured.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(this.captured.list.getFirst().getFormattedMessage())
                .contains("outcome=" + BatchReturnCode.HARD_FAILURE.name())
                .contains("code=" + BatchApplication.ERROR_CODE_JOB_FAILED)
                .doesNotContain("\n")
                .doesNotContain("\t");
    }

    /**
     * Confirms the three values the tier mapping produces are exactly the three the shared return-code
     * type models, which is what makes the resolution inside the outcome line unable to reject its own
     * input.
     */
    @Test
    @DisplayName("every tier the exit-status mapping produces is one the return-code type models")
    void everyProducedTierIsModelled() {
        assertThat(BatchApplication
                .exitStatusOf(executionWith(BatchStatus.COMPLETED, ExitStatus.COMPLETED)))
                .isEqualTo(BatchReturnCode.CLEAN.numericValue());
        assertThat(BatchApplication.exitStatusOf(executionWith(BatchStatus.COMPLETED,
                new ExitStatus(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS))))
                .isEqualTo(BatchReturnCode.SOFT_WARN.numericValue());
        assertThat(BatchApplication
                .exitStatusOf(executionWith(BatchStatus.FAILED, ExitStatus.FAILED)))
                .isEqualTo(BatchReturnCode.HARD_FAILURE.numericValue());
    }

    /**
     * Confirms a failed run publishes exactly one notification, carrying the step that failed.
     *
     * <p>Assumptions: the producer is a real one over a recording double rather than a mock of the
     * producer itself, because the property under assertion is that the entry point ASSEMBLES a
     * publishable payload -- a mock producer would accept any argument, including one the payload's own
     * constructor would refuse, and so would pass against the defect it is meant to catch.</p>
     */
    @Test
    @DisplayName("a failed run publishes one notification naming the step that failed")
    void failedRunPublishesOneNotification() {
        RecordingPublisher publisher = new RecordingPublisher();
        try (GenericApplicationContext context = contextWith(publisher)) {
            BatchApplication.publishFailureNotification(context, "post-transactions",
                    "post-transactions-step", BatchApplication.EXIT_STATUS_HARD_FAILURE);
        }

        assertThat(publisher.published).hasSize(1);
        BatchErrorEvent event = publisher.published.getFirst();
        assertThat(event.stepName()).isEqualTo("post-transactions-step");
        assertThat(event.jobName()).isEqualTo(BatchJobName.POST_TRANSACTIONS);
        assertThat(event.returnCode()).isEqualTo(BatchReturnCode.HARD_FAILURE);
        assertThat(event.runId()).isEqualTo(event.correlationId());
        assertThat(event.abendDetail()).isEqualTo(BatchErrorEvent.ABSENT_ABEND_DETAIL);
    }

    /**
     * Confirms the warn tier publishes NOTHING, which is the property that keeps the sink credible.
     *
     * <p>Assumptions: this is asserted here as well as being refused by the payload's constructor,
     * because the two guard different things. The constructor refuses a warn-tier payload if one is
     * ever assembled; this asserts that the entry point does not assemble one, so a correctly rejecting
     * constructor is never reached with a value that would make the swallow log an error for a run that
     * did its job. The reference reaches the warn tier by design at
     * {@code app/cbl/CBTRN02C.cbl:229-230}, where a non-zero reject count selects a code of four.</p>
     */
    @Test
    @DisplayName("a soft-warn run publishes nothing at all")
    void softWarnRunPublishesNothing() {
        RecordingPublisher publisher = new RecordingPublisher();
        try (GenericApplicationContext context = contextWith(publisher)) {
            BatchApplication.publishFailureNotification(context, "post-transactions",
                    "post-transactions-step", BatchApplication.EXIT_STATUS_SOFT_WARN);
            BatchApplication.publishFailureNotification(context, "post-transactions",
                    "post-transactions-step", BatchApplication.EXIT_STATUS_CLEAN);
        }

        assertThat(publisher.published).isEmpty();
        assertThat(this.captured.list).isEmpty();
    }

    /**
     * Confirms a deployment with no producer configured still completes the notification attempt.
     *
     * <p>Assumptions: the absent-producer case is the DEFAULT rather than an edge case -- the whole
     * queue configuration is gated on the sink address, so a deployment that supplies none has no
     * producer bean at all -- and a run must not fail because it had nothing to notify.</p>
     */
    @Test
    @DisplayName("an unconfigured deployment publishes nothing and raises nothing")
    void unconfiguredDeploymentRaisesNothing() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            BatchApplication.publishFailureNotification(context, "post-transactions",
                    "post-transactions-step", BatchApplication.EXIT_STATUS_HARD_FAILURE);
        }

        assertThat(this.captured.list).isEmpty();
    }

    /**
     * Confirms a producer that throws does not replace the run's own reported failure.
     *
     * <p>Assumptions: the throwing producer raises an unchecked fault, which is what a closing context
     * or a refused payload would raise. The property is that the entry point returns normally and
     * records the suppression, because its caller's exit status is the only channel the orchestrator
     * reads and a throwable here would replace a graded failure with an unreported one.</p>
     */
    @Test
    @DisplayName("a throwing producer is suppressed and recorded, not propagated")
    void throwingProducerIsSuppressed() {
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.fault = new IllegalStateException("context is closing");
        try (GenericApplicationContext context = contextWith(publisher)) {
            BatchApplication.publishFailureNotification(context, "post-transactions",
                    "post-transactions-step", BatchApplication.EXIT_STATUS_HARD_FAILURE);
        }

        assertThat(this.captured.list).hasSize(1);
        assertThat(this.captured.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
        String rendered = this.captured.list.getFirst().getFormattedMessage();
        assertThat(rendered).contains("event=batch.error.notify-failed")
                .contains("post-transactions-step")
                .contains(IllegalStateException.class.getName());
        // WHY : Assumptions: the fault's own MESSAGE must not appear, because a fault reachable here is
        //       composed by whatever refused the value -- a payload rejection quotes the component it
        //       refused, and that component is the one the payload's constructor redacts.
        assertThat(rendered).doesNotContain("context is closing");
        assertThat(this.captured.list.getFirst().getThrowableProxy()).isNull();
    }

    /**
     * Confirms the failing step is named from the execution, and that a clean run of steps names none.
     */
    @Test
    @DisplayName("the failing step is the first non-completed one, or the no-step token")
    void failingStepIsNamedFromTheExecution() {
        JobExecution execution = executionWith(BatchStatus.FAILED, ExitStatus.FAILED);
        addStep(execution, "preflight-step", BatchStatus.COMPLETED);
        addStep(execution, "post-transactions-step", BatchStatus.FAILED);
        addStep(execution, "interest-step", BatchStatus.ABANDONED);

        assertThat(BatchApplication.failingStepNameOf(execution))
                .isEqualTo("post-transactions-step");

        JobExecution allClean = executionWith(BatchStatus.FAILED, ExitStatus.FAILED);
        addStep(allClean, "preflight-step", BatchStatus.COMPLETED);
        assertThat(BatchApplication.failingStepNameOf(allClean))
                .isEqualTo(BatchApplication.STEP_NAME_NO_STEP);

        assertThat(BatchApplication
                .failingStepNameOf(executionWith(BatchStatus.FAILED, ExitStatus.FAILED)))
                .isEqualTo(BatchApplication.STEP_NAME_NO_STEP);
    }

    /**
     * Attaches one step execution carrying the given name and status to an execution.
     *
     * <p>Assumptions: the step is constructed and added rather than created through the execution,
     * because the framework version in use offers no factory on {@code JobExecution} -- the addition is
     * explicit, and the constructor is the only way to name a step outside a running job.</p>
     *
     * <p>Assumptions: the three-argument constructor is used, which takes an identifier, and NOT the
     * two-argument one that generates it. The shorter form is deprecated and marked for removal in the
     * framework version in use, and this project's build reports a deprecation as a compiler warning --
     * so the shorter spelling would add a warning to a module that currently has none. The identifier
     * is derived from the number of steps already attached so each is distinct without a counter field.</p>
     *
     * @param execution the execution to attach the step to; must not be {@code null}
     * @param stepName the step's name; must not be {@code null}
     * @param status the batch status the step reports, which is what the substitution rule reads
     */
    private static void addStep(JobExecution execution, String stepName, BatchStatus status) {
        StepExecution step = new StepExecution(execution.getStepExecutions().size() + 1L, stepName,
                execution);
        step.setStatus(status);
        execution.addStepExecution(step);
    }

    /**
     * Builds and refreshes a context holding exactly the supplied producer.
     *
     * @param publisher the recording producer to register; must not be {@code null}
     * @return the refreshed context, which the caller closes
     */
    private static GenericApplicationContext contextWith(RecordingPublisher publisher) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(BatchErrorPublisher.class, () -> publisher);
        context.refresh();
        return context;
    }

    /**
     * A producer that records what it was asked to publish, and optionally refuses to.
     *
     * <p>Assumptions: it EXTENDS the production producer rather than implementing an interface,
     * because the production type is a class and introducing an interface for one test would add a seam
     * to production code that nothing else needs. Its superclass constructor is given collaborators it
     * never uses, since every method that would touch them is overridden.</p>
     */
    private static final class RecordingPublisher extends BatchErrorPublisher {

        /** Every event this producer was asked to publish, in order. */
        private final java.util.List<BatchErrorEvent> published = new java.util.ArrayList<>();

        /** When set, the fault raised instead of publishing; null means publish normally. */
        private RuntimeException fault;

        /** Builds the double over collaborators no overridden method reaches. */
        private RecordingPublisher() {
            super(org.mockito.Mockito.mock(software.amazon.awssdk.services.sqs.SqsClient.class),
                    new com.carddemo.batch.config.SqsConfig.ErrorSinkBinding(
                            "https://sqs.us-east-1.amazonaws.com/000000000000/carddemo-error-test",
                            "application/json", "CARDDEMO", "BATCHSVC"),
                    new tools.jackson.databind.ObjectMapper());
        }

        /**
         * Records the event, or raises the configured fault.
         *
         * @param event the event handed in; recorded rather than sent
         * @return {@code true} always, because a recorded event is a delivered one for this double
         * @throws RuntimeException the configured fault, when one is set
         */
        @Override
        public boolean publish(BatchErrorEvent event) {
            if (this.fault != null) {
                throw this.fault;
            }
            this.published.add(event);
            return true;
        }
    }

    /**
     * Asserts no diagnostic in this module hands a caught throwable to the logging facade unrendered.
     *
     * <p>⚠️ Purpose: the two catch arms of {@code runInContext} both did. One bound the throwable to a
     * placeholder named {@code failureDigest={}}, where the facade renders it with {@code String.valueOf}
     * and therefore with {@code Throwable.toString()} -- the type AND the message -- under a parameter name
     * asserting the opposite. The other appended an {@code Error} as a fifth argument to a message carrying
     * four placeholders, which the facade treats as the trailing throwable and renders as the message,
     * every cause's message and the frames. Both are generic boundaries reached by failures nobody
     * anticipated, so the messages arriving there are composed by whichever library failed: a driver
     * reports the statement it could not run, a parser quotes the token it could not read, and either can
     * carry an account identifier or a whole record.</p>
     *
     * <p>Assumptions: a throwable may reach a log line only through {@code ThrowableDigest.of(...)} or a
     * class-name accessor. Those two are facts about CODE -- a type chain, a frame, a class name -- and can
     * hold no request value, which is the property that makes them admissible where a message is not.</p>
     *
     * <p>Measured: with both call sites returned to their reviewed form, this case is the only one of the
     * seventeen here that fails, and it names both -- {@code BatchApplication.java:701} for the
     * placeholder-bound throwable and {@code :742} for the trailing one -- so it discriminates on each
     * independently rather than on the pair.</p>
     *
     * <p>Assumptions: the subjects are the CAUGHT identifiers, collected from every {@code catch} clause in
     * the source rather than from a list written here. A rule naming the two identifiers this class happens
     * to use would be satisfied by a third arm that named its variable something else.</p>
     *
     * <p>Assumptions: string literals are removed from a call before it is examined, and that step is
     * load-bearing rather than tidy. Every one of these templates spells its own parameter name in the
     * format string -- {@code failure=}, {@code batch.job.fatal} -- so a match made against the raw text
     * reports each of them and the rule would have to be weakened to something that passes.</p>
     *
     * <p>Alternatives Considered: capturing the logger and asserting the formatted output, which the cases
     * above do for the tiers they drive. Rejected as the guard because it proves the property only for
     * paths a test reaches: the fatal arm needs an {@code Error} raised inside a started context, which no
     * test here provokes, and that is precisely the arm that carried a full trace. Reading the source
     * asserts every template unconditionally, including the ones on unreachable paths.</p>
     *
     * <p>Alternatives Considered: scoping the walk to {@code BatchApplication.java} alone, since that is
     * where both defects were, or promoting the rule into an ArchUnit rule in {@code common-lib} applied to
     * every service. The first is rejected because nothing would then stop the next occurrence one package
     * away -- the sibling authorization module learnt that from a disclosure rule written one class at a
     * time. The second is deferred rather than dismissed: the sibling rule cannot be shared because what a
     * diagnostic may still carry differs by context, whereas THIS rule is uniform, so a repository-wide
     * form is possible and is a wider change than the finding it would close.</p>
     *
     * <p>Trade-offs: the walk lives in a class named for the entry point although it covers the module, and
     * a new root-level class was rejected on purpose: the package charter beside this file closes the root
     * to exactly one test class, and its own record shows that every figure derived from that roster has
     * lapsed twice. Adding a case to the class whose defect prompted it keeps the roster true and keeps the
     * assertion beside the code it was written for.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws java.io.IOException if the module's sources cannot be read
     */
    @Test
    @DisplayName("no diagnostic in this module logs a caught throwable unrendered")
    void noDiagnosticLogsACaughtThrowableUnrendered() throws java.io.IOException {
        Path sources = Path.of("src", "main", "java");
        assertThat(Files.isDirectory(sources))
                .as("the walk must run from the module directory, or it would assert over nothing")
                .isTrue();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(sources)) {
            for (Path source : tree.filter(path -> path.toString().endsWith(".java")).toList()) {
                collectUnrenderedThrowableLogs(source, offenders);
            }
        }

        assertThat(offenders)
                .as("a throwable reaches a log line only through ThrowableDigest or a class-name accessor")
                .isEmpty();
    }

    /**
     * Records every logging call in one source that names a caught throwable outside an admitted rendering.
     *
     * @param source the source file to examine; must not be {@code null}
     * @param offenders the list each offending call is appended to, as {@code file:line} plus the call
     *     text; must not be {@code null}
     * @throws java.io.IOException if the source cannot be read
     */
    private static void collectUnrenderedThrowableLogs(Path source, List<String> offenders)
            throws java.io.IOException {

        String text = Files.readString(source);
        Set<String> caught = new LinkedHashSet<>();
        Matcher clause = CATCH_CLAUSE.matcher(text);
        while (clause.find()) {
            caught.add(clause.group(1));
        }
        if (caught.isEmpty()) {
            return;
        }
        Matcher call = LOGGING_CALL.matcher(text);
        while (call.find()) {
            String statement = text.substring(call.start(), endOfCall(text, call.end()));
            // WHY : Assumptions: the admitted renderings are erased before the identifiers are looked
            //       for, rather than being detected alongside them. A call may pass the same throwable
            //       twice -- once as a class name and once as a digest, which both arms of this entry
            //       point now do -- so a rule that merely required an admitted form to be PRESENT would
            //       also pass a call that added the raw throwable beside it.
            String examined = STRING_LITERAL.matcher(statement).replaceAll("\"\"");
            examined = ADMITTED_DIGEST.matcher(examined).replaceAll("");
            examined = ADMITTED_CLASS_NAME.matcher(examined).replaceAll("");
            for (String name : caught) {
                if (Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(examined).find()) {
                    long line = text.substring(0, call.start()).chars().filter(c -> c == '\n').count() + 1;
                    offenders.add(source.getFileName() + ":" + line + " names '" + name + "' in "
                            + statement.replaceAll("\\s+", " "));
                }
            }
        }
    }

    /**
     * Finds the index just past the closing parenthesis of a call whose opening one has been consumed.
     *
     * <p>Assumptions: the scan balances parentheses rather than searching for the next {@code )}, because
     * every one of these calls contains at least one nested call -- an accessor or a digest -- and a search
     * for the first closing parenthesis would cut the statement short and hide whatever followed it.</p>
     *
     * @param text the whole source text
     * @param afterOpeningParenthesis the index just past the call's opening parenthesis
     * @return the index just past the matching closing parenthesis, or the text length if unbalanced
     */
    private static int endOfCall(String text, int afterOpeningParenthesis) {
        int depth = 1;
        int index = afterOpeningParenthesis;
        while (index < text.length() && depth > 0) {
            char character = text.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
            }
            index++;
        }
        return index;
    }

    /** Matches the opening of a logging call at any severity this module uses. */
    private static final Pattern LOGGING_CALL =
            Pattern.compile("\\bLOG\\s*\\.\\s*(?:trace|debug|info|warn|error)\\s*\\(");

    /** Matches a catch clause and captures the identifier it binds, multi-catch included. */
    private static final Pattern CATCH_CLAUSE =
            Pattern.compile("catch\\s*\\(\\s*[\\w.]+(?:\\s*\\|\\s*[\\w.]+)*\\s+(\\w+)\\s*\\)");

    /** Matches a string literal, escapes included, so a template's own words are not read as code. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");

    /** Matches the admitted digest rendering. */
    private static final Pattern ADMITTED_DIGEST =
            Pattern.compile("ThrowableDigest\\s*\\.\\s*of\\s*\\(\\s*\\w+\\s*\\)");

    /** Matches the admitted class-name renderings, both the qualified and the simple form. */
    private static final Pattern ADMITTED_CLASS_NAME = Pattern.compile(
            "\\b\\w+\\s*\\.\\s*getClass\\s*\\(\\s*\\)\\s*\\.\\s*get(?:Simple)?Name\\s*\\(\\s*\\)");

    /**
     * Builds a finished job execution carrying the given statuses and nothing else.
     *
     * @param batchStatus the batch status the execution reports, which decides completion
     * @param exitStatus the framework exit status the execution carries, which decides the tier within
     *     a completed execution
     * @return an execution whose two statuses are exactly those supplied, with a fixed instance
     *     identifier so nothing in an assertion depends on a generated value
     */
    private static JobExecution executionWith(BatchStatus batchStatus, ExitStatus exitStatus) {
        JobExecution execution = new JobExecution(1L,
                new JobInstance(1L, "post-transactions"), new JobParameters());
        execution.setStatus(batchStatus);
        execution.setExitStatus(exitStatus);
        return execution;
    }
}
