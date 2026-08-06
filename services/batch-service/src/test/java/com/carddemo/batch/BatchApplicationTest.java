package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.batch.dto.BatchReturnCode;
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

/**
 * Verifies the two properties of the batch entry point that a reading of the class cannot settle: that
 * an echoed command-line value can neither forge nor flood a log record, and that every one of the
 * three exit tiers is reported rather than only the failing ones.
 *
 * <p>Assumptions: the tier assertions read captured log events rather than the returned status,
 * because the status was already correct before these tests existed and the defect was that the middle
 * tier was invisible. Asserting the return value would therefore pass against the defect.</p>
 */
class BatchApplicationTest {

    /** A well-formed job token, so a rejection under test is caused by the value beside it. */
    private static final String VALID_JOB = "--job=post-transactions";

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
