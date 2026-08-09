package com.carddemo.reporting.task;

import com.carddemo.reporting.ReportingTask;
import com.carddemo.reporting.ReportingTaskRunner;
import com.carddemo.reporting.service.TransactionReportService;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The nightly transaction-report run, registered under the name the orchestrator dispatches.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Purpose: {@code ReportingTaskRunner} dispatches {@code --job=generate-reports} by looking a
 * {@link ReportingTask} bean up under that exact name, and no bean carried it -- so the
 * {@code GenerateReports} state of the {@code carddemo-daily-batch} machine ran this image to completion
 * and produced no artifact. This class closes that gap for the nightly run.</p>
 *
 * <p>Assumptions: the nightly report covers ONE business date, start and end both, rather than a period.
 * {@code app/jcl/TRANREPT.jcl} supplies its range as two ten-character tokens at L47 and L55, and the
 * nightly chain supplies a single business date; a one-day range is the reading that uses the value the
 * orchestrator actually sends without inventing a window it did not ask for. A caller wanting a period
 * dispatches the on-demand task, which takes two bounds.</p>
 *
 * <p>Assumptions: the business date is REQUIRED here where the statement task treats it as informational.
 * The difference is real: a statement run has no date input in the reference at all, whereas a transaction
 * report is defined by its range and a report with no range is not a report. The reference's own behaviour
 * for a missing range is to terminate having produced nothing and report success, which
 * {@link TransactionReportService} deliberately replaces with a refusal, and this task inherits that.</p>
 *
 * <p>Trade-offs: the artifact composition, the key and the sink lifecycle are delegated to
 * {@link ReportArtifactPublisher} rather than repeated here, because the on-demand task publishes the same
 * artifact from a different range and two copies of the key rule would eventually disagree about where a
 * report lives.</p>
 */
@Component("generate-reports")
public class GenerateReportsTask implements ReportingTask {

    /** Journal logger for this task's outcome. */
    private static final Logger LOG = LoggerFactory.getLogger(GenerateReportsTask.class);

    /** The publisher that runs the generator and stores the artifact. */
    private final ReportArtifactPublisher publisher;

    /**
     * Creates the nightly report task.
     *
     * @param publisher the artifact publisher; must not be {@code null}
     * @throws NullPointerException if {@code publisher} is {@code null}
     */
    public GenerateReportsTask(ReportArtifactPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    /**
     * Produces the night's transaction-report artifact.
     *
     * @param parameters the validated run parameters, which must carry the business date; must not be
     *     {@code null}
     * @throws Exception if the artifact cannot be published, or if the range holds a transaction whose
     *     dimensions do not resolve, which stops the run as the reference's abend does
     * @throws IllegalArgumentException if no business date was supplied, because a report is defined by
     *     its range
     */
    @Override
    public void run(Map<String, String> parameters) throws Exception {
        Objects.requireNonNull(parameters, "parameters must not be null");
        String token = parameters.get(ReportingTaskRunner.BUSINESS_DATE_PARAMETER);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    ReportingTaskRunner.BUSINESS_DATE_OPTION
                            + " is required by the nightly report task: a transaction report is defined by"
                            + " its date range and has no defensible default");
        }
        LocalDate businessDate = LocalDate.parse(token);

        TransactionReportService.ReportGenerationSummary summary =
                publisher.publish(businessDate, businessDate, businessDate);

        LOG.info("event=reporting.report.produced businessDate={} records={} detailLines={}"
                        + " pageBands={} groupBands={}",
                businessDate, summary.recordsWritten(), summary.detailLines(),
                summary.pageTotalBands(), summary.accountTotalBands());
    }
}
