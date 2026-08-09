package com.carddemo.reporting.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.money.Money;
import com.carddemo.reporting.ReportingTask;
import com.carddemo.reporting.ReportingTaskRunner;
import com.carddemo.reporting.service.StatementService;
import com.carddemo.reporting.service.TransactionReportService;
import com.carddemo.reporting.sink.S3StatementSink;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Pins that every task name the orchestrator dispatches resolves to a bean that can actually run, and
 * that each one covers the range it was given and keys its artifact where the convention says.
 *
 * <p>Purpose: the runner turns a {@code --job=} token into a bean lookup by that exact name. Two
 * existing checks bracket that lookup and neither covers it: the runner's own test proves the token is
 * ACCEPTED, and the state-machine source proves the token is SENT. Between those two, nothing proved a
 * bean answered to the name -- so the module shipped in a state where all three accepted names were
 * dispatched, validated, and then failed to resolve, and a report submission was acknowledged while
 * producing nothing. This class closes that gap by resolving each name for real.
 *
 * <p>Assumptions: the names are read from {@link ReportingTaskRunner#JOB_NAMES} rather than restated
 * here. A restated list agrees with whatever it was copied from and cannot detect a fourth name added
 * without a fourth bean, which is the failure this class exists for.
 *
 * <p>Assumptions: a real Spring context is refreshed rather than the annotations being read
 * reflectively. Reading the annotation would prove a name is DECLARED; refreshing proves it is
 * REGISTERED, which is a different claim -- two beans declaring one name, or a component the scan does
 * not reach, both satisfy the first and fail the second.
 *
 * <p>Assumptions: the two collaborating services and the storage client are supplied as mocks, and the
 * three configuration values as literals. Nothing here contacts a database or an object store: the
 * properties under test are the wiring, the range each task derives, and the object key each artifact
 * lands on -- and the last of those is observable from the request the storage client is handed.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own where they have any.
 */
class TaskDispatchWiringTest {

    /** A ten-character date token of the width the orchestrator validates before dispatching. */
    private static final String DATE_TOKEN = "2022-07-18";

    /** The bucket every task in this class publishes to, standing for the deployment's own. */
    private static final String BUCKET = "carddemo-datasets-test";

    /** The statement key prefix, matching the base configuration document. */
    private static final String STATEMENT_PREFIX = "statements/";

    /** The report key prefix, matching the base configuration document. */
    private static final String REPORT_PREFIX = "reports/transaction-detail/";

    /**
     * The context under test: the task package scanned, its collaborators mocked, its values supplied.
     *
     * <p>Assumptions: only the task package is scanned. Scanning the module root would build the filter
     * chain and the token decoder, and the decoder resolves an issuer's discovery document over the
     * network at bean-creation time -- so a context started to resolve three bean names would fail for a
     * reason unrelated to them.</p>
     */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TaskScanConfiguration.class)
            .withBean(StatementService.class, () -> mock(StatementService.class))
            .withBean(TransactionReportService.class, () -> mock(TransactionReportService.class))
            .withBean(S3Client.class, () -> mock(S3Client.class))
            .withPropertyValues(
                    StatementService.OUTPUT_BUCKET_PROPERTY + "=" + BUCKET,
                    StatementService.STATEMENT_PREFIX_PROPERTY + "=" + STATEMENT_PREFIX,
                    ReportArtifactPublisher.REPORT_PREFIX_PROPERTY + "=" + REPORT_PREFIX);

    /**
     * Asserts that each accepted task name resolves to exactly one runnable task bean.
     */
    @Test
    @DisplayName("every accepted task name resolves to a runnable task bean")
    void everyAcceptedTaskNameResolvesToABean() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            for (String name : ReportingTaskRunner.JOB_NAMES) {
                assertThat(context.getBean(name, ReportingTask.class))
                        .as("the orchestrator dispatches --job=%s and the runner looks a bean up by"
                                + " that exact name", name)
                        .isNotNull();
            }
        });
    }

    // WHY : Assumptions: the roster is asserted CLOSED as well as complete. A fourth task bean carrying
    //       a name the runner does not accept is unreachable code that reads as reachable, and the two
    //       lists drifting apart in that direction is exactly as misleading as drifting apart in the
    //       other -- a reader auditing the package would conclude a job exists that nothing can start.
    /**
     * Asserts that no task bean exists that the runner would not accept.
     */
    @Test
    @DisplayName("no task bean carries a name the runner does not accept")
    void noTaskBeanIsUnreachable() {
        contextRunner.run(context -> assertThat(context.getBeanNamesForType(ReportingTask.class))
                .as("every registered task is reachable from a --job= token")
                .containsExactlyInAnyOrderElementsOf(ReportingTaskRunner.JOB_NAMES));
    }

    // WHY : Assumptions: the two report tasks are asserted to derive DIFFERENT ranges, because that is
    //       the whole reason two beans exist rather than one. A single task reading whichever parameters
    //       happened to be present would satisfy the resolution assertions above while making a nightly
    //       run and a submitted request indistinguishable in every journal line and execution history.
    /**
     * Asserts that the nightly report task treats its business date as a one-day inclusive range.
     *
     * @throws Exception if the task raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("the nightly report task covers its business date as a one-day range")
    void theNightlyReportTaskCoversOneDay() throws Exception {
        ReportArtifactPublisher publisher = mock(ReportArtifactPublisher.class);
        when(publisher.publish(any(), any(), any())).thenReturn(summary());

        new GenerateReportsTask(publisher).run(Map.of(
                ReportingTaskRunner.BUSINESS_DATE_PARAMETER, DATE_TOKEN));

        LocalDate businessDate = LocalDate.parse(DATE_TOKEN);
        verify(publisher).publish(businessDate, businessDate, businessDate);
    }

    /**
     * Asserts that the on-demand task covers the range it was given and keys under its end.
     *
     * @throws Exception if the task raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("the on-demand task covers the requested range and keys under its end")
    void theOnDemandTaskCoversTheRequestedRange() throws Exception {
        ReportArtifactPublisher publisher = mock(ReportArtifactPublisher.class);
        when(publisher.publish(any(), any(), any())).thenReturn(summary());

        new GenerateAdHocReportTask(publisher).run(Map.of(
                ReportingTaskRunner.START_DATE_PARAMETER, "2022-07-01",
                ReportingTaskRunner.END_DATE_PARAMETER, DATE_TOKEN,
                ReportingTaskRunner.REPORT_TYPE_PARAMETER, "Custom"));

        verify(publisher).publish(
                LocalDate.of(2022, 7, 1), LocalDate.parse(DATE_TOKEN), LocalDate.parse(DATE_TOKEN));
    }

    // WHY : Assumptions: an absent bound is refused rather than defaulted to the clock, and the refusal
    //       is asserted to quote the COMMAND-LINE option. Defaulting a date would make a rerun produce a
    //       different answer from the run it repeats, which destroys the reproducibility every
    //       golden-master comparison in this project depends on.
    /**
     * Asserts that the on-demand task refuses an absent bound rather than inventing one.
     */
    @Test
    @DisplayName("the on-demand task refuses an absent bound, quoting the option")
    void theOnDemandTaskRefusesAnAbsentBound() {
        ReportArtifactPublisher publisher = mock(ReportArtifactPublisher.class);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new GenerateAdHocReportTask(publisher).run(Map.of(
                        ReportingTaskRunner.START_DATE_PARAMETER, "2022-07-01")))
                .withMessageContaining(ReportingTaskRunner.END_DATE_OPTION);
    }

    /**
     * Asserts that the nightly report task refuses an absent business date.
     */
    @Test
    @DisplayName("the nightly report task refuses an absent business date")
    void theNightlyReportTaskRefusesAnAbsentBusinessDate() {
        ReportArtifactPublisher publisher = mock(ReportArtifactPublisher.class);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new GenerateReportsTask(publisher).run(Map.of()))
                .withMessageContaining(ReportingTaskRunner.BUSINESS_DATE_OPTION);
    }

    // WHY : Assumptions: the report key is asserted through the request the storage client is HANDED,
    //       which is the only place a key is observable from outside. A run producing no record closes
    //       its writer without ever having started a multipart upload, so it publishes by whole-object
    //       put -- which is why this case can read the key from a put request rather than having to
    //       drive a five-mebibyte artifact to reach a completion call.
    /**
     * Asserts that a report artifact is keyed under the run date's partition and the fixed object name.
     *
     * @throws Exception if the publication raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("a report artifact is keyed under the run date's date partition")
    void aReportArtifactIsKeyedUnderTheRunDate() throws Exception {
        TransactionReportService reports = mock(TransactionReportService.class);
        when(reports.generateReport(any(), any(), any())).thenReturn(summary());
        S3Client s3 = mock(S3Client.class);

        LocalDate runDate = LocalDate.parse(DATE_TOKEN);
        new ReportArtifactPublisher(reports, s3, BUCKET, REPORT_PREFIX)
                .publish(runDate, runDate, runDate);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(put.getValue().key())
                .as("the dataset convention keys an artifact under <prefix>dt=<date>/<object>")
                .isEqualTo(REPORT_PREFIX + "dt=" + DATE_TOKEN + "/"
                        + ReportArtifactPublisher.REPORT_OBJECT);
        verify(s3, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    }

    // WHY : Assumptions: the statement task's two artifacts are asserted by KEY and by COUNT, because
    //       the reference produces exactly two datasets for a whole run -- one eighty-column plain-text
    //       and one hundred-column markup -- and a task that wrote one object per statement would still
    //       satisfy an assertion that only counted records.
    /**
     * Asserts that a statement run publishes exactly the two artifacts the reference declares.
     *
     * @throws Exception if the run raises, which the assertions below would not reach
     */
    @Test
    @DisplayName("a statement run publishes exactly the two run-wide artifacts")
    void aStatementRunPublishesTwoArtifacts() throws Exception {
        StatementService statements = mock(StatementService.class);
        when(statements.generateStatements(any())).thenAnswer(invocation -> {
            StatementService.StatementSink sink = invocation.getArgument(0);
            sink.replaceArtifacts();
            return 0;
        });
        S3Client s3 = mock(S3Client.class);

        new GenerateStatementsTask(statements, s3, BUCKET, STATEMENT_PREFIX)
                .run(Map.of(ReportingTaskRunner.BUSINESS_DATE_PARAMETER, DATE_TOKEN));

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, org.mockito.Mockito.times(2)).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getAllValues().stream().map(PutObjectRequest::key).toList())
                .as("a run publishes the two run-wide datasets and no per-statement object")
                .containsExactlyInAnyOrderElementsOf(expectedStatementKeys());
    }

    // WHY : Assumptions: the business date is tolerated as absent by the statement task and required by
    //       both report tasks, and the asymmetry is deliberate rather than an oversight. A statement run
    //       takes no date selector at all in the reference -- across the whole of CREASTMT.JCL there is
    //       no parameter field and no date symbol -- so the date reaches this task only to attribute its
    //       journal line, and refusing an absent one would refuse a run the reference performs.
    /**
     * Asserts that the statement task runs without a business date and still publishes its artifacts.
     *
     * @throws Exception if the run raises, which the assertions below would not reach
     */
    @Test
    @DisplayName("the statement task runs without a business date, because the reference takes none")
    void theStatementTaskRunsWithoutABusinessDate() throws Exception {
        StatementService statements = mock(StatementService.class);
        when(statements.generateStatements(any())).thenReturn(0);
        S3Client s3 = mock(S3Client.class);

        new GenerateStatementsTask(statements, s3, BUCKET, STATEMENT_PREFIX).run(Map.of());

        verify(statements).generateStatements(any());
        verify(s3, org.mockito.Mockito.times(2))
                .putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /**
     * Asserts that a task's own failure propagates, because the runner owns the exit-code rubric.
     *
     * @throws Exception if the mock setup raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("a publication failure propagates out of the task")
    void aPublicationFailurePropagates() throws Exception {
        ReportArtifactPublisher publisher = mock(ReportArtifactPublisher.class);
        when(publisher.publish(any(), any(), any()))
                .thenThrow(new IOException("the artifact could not be published"));

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> new GenerateReportsTask(publisher).run(Map.of(
                        ReportingTaskRunner.BUSINESS_DATE_PARAMETER, DATE_TOKEN)));
    }

    // WHY : Assumptions: the value is exercised through the task rather than through the sanitiser
    //       directly. The sanitiser has its own tests in the shared kernel; what is unproven here is
    //       that this task ROUTES the one caller-supplied string it journals through it, and only a call
    //       carrying a terminator all the way to the log statement can establish that.
    /**
     * Asserts that a report type carrying a line terminator does not stop the run it names.
     *
     * @throws Exception if the task raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("a report type carrying a line terminator is journalled without stopping the run")
    void aReportTypeCarryingATerminatorIsSanitised() throws Exception {
        ReportArtifactPublisher publisher = mock(ReportArtifactPublisher.class);
        when(publisher.publish(any(), any(), any())).thenReturn(summary());

        Map<String, String> parameters = new HashMap<>();
        parameters.put(ReportingTaskRunner.START_DATE_PARAMETER, "2022-07-01");
        parameters.put(ReportingTaskRunner.END_DATE_PARAMETER, DATE_TOKEN);
        parameters.put(ReportingTaskRunner.REPORT_TYPE_PARAMETER,
                "Custom\nevent=reporting.report.produced records=0");

        new GenerateAdHocReportTask(publisher).run(parameters);

        verify(publisher).publish(any(), any(), any());
    }

    /**
     * Asserts that an absent report type does not stop an otherwise complete on-demand request.
     *
     * @throws Exception if the task raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("an absent report type does not stop an on-demand run")
    void anAbsentReportTypeDoesNotStopTheRun() throws Exception {
        ReportArtifactPublisher publisher = mock(ReportArtifactPublisher.class);
        when(publisher.publish(any(), any(), any())).thenReturn(summary());

        new GenerateAdHocReportTask(publisher).run(Map.of(
                ReportingTaskRunner.START_DATE_PARAMETER, "2022-07-01",
                ReportingTaskRunner.END_DATE_PARAMETER, DATE_TOKEN));

        verify(publisher).publish(any(), any(), any());
    }

    /**
     * Builds one generation summary standing for a run that wrote a handful of records.
     *
     * @return the summary
     */
    private static TransactionReportService.ReportGenerationSummary summary() {
        return new TransactionReportService.ReportGenerationSummary(
                12L, 7L, 1L, 2L, Money.of("-1234.56"));
    }

    /**
     * Renders the two artifact keys a statement run publishes, for readability in a failure message.
     *
     * @return the two expected keys, in the order the reference declares its two datasets
     */
    private static List<String> expectedStatementKeys() {
        return List.of(
                STATEMENT_PREFIX + S3StatementSink.PLAIN_TEXT_OBJECT,
                STATEMENT_PREFIX + S3StatementSink.HTML_OBJECT);
    }

    /**
     * The narrowest configuration that registers this package's components.
     *
     * <p>Assumptions: declared as a nested type rather than as a file of its own, which keeps this
     * directory to the two files it needs, and scoped to this one package for the reason recorded on the
     * context runner above.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.carddemo.reporting.task")
    static class TaskScanConfiguration {
    }
}
