package com.carddemo.reporting.task;

import com.carddemo.common.observability.LogSafeText;
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
 * The on-demand transaction-report run, registered under the name the orchestrator dispatches.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Purpose: {@code ReportingTaskRunner} dispatches {@code --job=generate-report} -- singular -- by looking
 * a {@link ReportingTask} bean up under that exact name, and no bean carried it. So the request path could
 * accept a report submission, start an orchestration and produce nothing, which is the worst of the three
 * possible outcomes: the caller was told its report had been submitted.</p>
 *
 * <p>Assumptions: the singular-versus-plural distinction between this task's name and the nightly one's is
 * the orchestrator's own and is preserved rather than collapsed. The two are different units of work -- a
 * whole night's reports against one requested report -- and one name for both would make an on-demand
 * request indistinguishable from a nightly run in every journal line and every execution history.</p>
 *
 * <p>Assumptions: both bounds are REQUIRED and neither is defaulted. Every one of the reference's three
 * report types supplies both bounds before submission, so a default would invent a range the specification
 * does not have and a caller could not tell the invented range from one it asked for. The report type
 * arrives with the request and is recorded in the journal line so an execution can be attributed to what
 * was asked for; it does not change the range, because the request path has already reduced a named type to
 * two dates before dispatching.</p>
 *
 * <p>Assumptions: the artifact is keyed under the range's END date. The end is what a reader looking for
 * "the report through the 18th" has, and keying under the start would put a one-month report and a one-day
 * report that begin on the same date at the same key.</p>
 */
@Component("generate-report")
public class GenerateAdHocReportTask implements ReportingTask {

    /** Journal logger for this task's outcome. */
    private static final Logger LOG = LoggerFactory.getLogger(GenerateAdHocReportTask.class);

    /** The publisher that runs the generator and stores the artifact. */
    private final ReportArtifactPublisher publisher;

    /**
     * Creates the on-demand report task.
     *
     * @param publisher the artifact publisher; must not be {@code null}
     * @throws NullPointerException if {@code publisher} is {@code null}
     */
    public GenerateAdHocReportTask(ReportArtifactPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    /**
     * Produces the requested report's artifact.
     *
     * @param parameters the validated run parameters, which must carry both bounds; must not be
     *     {@code null}
     * @throws Exception if the artifact cannot be published, or if the range holds a transaction whose
     *     dimensions do not resolve, which stops the run as the reference's abend does
     * @throws IllegalArgumentException if either bound is absent, because a report is defined by its range
     */
    @Override
    public void run(Map<String, String> parameters) throws Exception {
        Objects.requireNonNull(parameters, "parameters must not be null");
        LocalDate start = requireBound(parameters,
                ReportingTaskRunner.START_DATE_PARAMETER, ReportingTaskRunner.START_DATE_OPTION);
        LocalDate end = requireBound(parameters,
                ReportingTaskRunner.END_DATE_PARAMETER, ReportingTaskRunner.END_DATE_OPTION);
        String reportType = parameters.get(ReportingTaskRunner.REPORT_TYPE_PARAMETER);

        TransactionReportService.ReportGenerationSummary summary = publisher.publish(start, end, end);

        // WHY : Assumptions: the report type is SANITISED where the two dates are not. The dates have been
        //       through a date parser, so they cannot carry a delimiter or a line terminator; the type is a
        //       caller-supplied string that reaches this line verbatim, and a value read from a request
        //       reaching a journal line unsanitised is how a control character forges a log entry.
        LOG.info("event=reporting.report.produced reportType={} startDate={} endDate={} records={}"
                        + " detailLines={} pageBands={} groupBands={}",
                reportType == null ? "unset" : LogSafeText.sanitize(reportType),
                start, end, summary.recordsWritten(), summary.detailLines(),
                summary.pageTotalBands(), summary.accountTotalBands());
    }

    /**
     * Reads one required date bound, refusing an absent one by the option name an operator typed.
     *
     * <p>Assumptions: the refusal names the COMMAND-LINE option rather than the parameter key, because the
     * operator supplied an option and the key is an internal name they never saw.</p>
     *
     * @param parameters the run parameters; must not be {@code null}
     * @param key the parameter key to read; must not be {@code null}
     * @param option the command-line option to quote in a refusal; must not be {@code null}
     * @return the parsed bound, never {@code null}
     * @throws IllegalArgumentException if the bound is absent or blank
     * @throws java.time.format.DateTimeParseException if the bound is not a calendar date in
     *     {@code YYYY-MM-DD} order
     */
    private static LocalDate requireBound(
            Map<String, String> parameters, String key, String option) {
        String token = parameters.get(key);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(option
                    + " is required by the on-demand report task: a transaction report is defined by its"
                    + " date range and has no defensible default");
        }
        return LocalDate.parse(token);
    }
}
