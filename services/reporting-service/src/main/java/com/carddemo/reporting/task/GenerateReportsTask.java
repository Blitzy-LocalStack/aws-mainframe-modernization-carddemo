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
     * The publisher of the category-balance report this state also replaces.
     *
     * <p>Assumptions: TWO publishers rather than one, because the state replaces TWO reference jobs
     * that share no input, no record length and no key. The migration plan's section 0.4.1.7 assigns
     * both {@code app/jcl/TRANREPT.jcl} and {@code app/jcl/PRTCATBL.jcl} to this state, and a single
     * publisher would have to branch internally on which report it was producing.</p>
     */
    private final CategoryBalanceArtifactPublisher categoryBalances;

    /**
     * Creates the nightly report task.
     *
     * @param publisher the transaction-report artifact publisher; must not be {@code null}
     * @param categoryBalances the category-balance report publisher; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public GenerateReportsTask(ReportArtifactPublisher publisher,
            CategoryBalanceArtifactPublisher categoryBalances) {

        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.categoryBalances =
                Objects.requireNonNull(categoryBalances, "categoryBalances must not be null");
    }

    /**
     * Produces the night's two report artifacts.
     *
     * <p>Assumptions: the transaction report is produced FIRST and the category-balance report second,
     * and the order is the reference's own. {@code app/jcl/TRANREPT.jcl} and
     * {@code app/jcl/PRTCATBL.jcl} are separate jobs with no condition between them, so no order is
     * transcribed from a gate; what fixes it is that the transaction report is the one an operator
     * reads first and the one whose failure says most about the night, so producing it before the
     * smaller report means a partial state is the more useful half rather than the lesser one.</p>
     *
     * <p>Trade-offs: a failure in either report fails the state, and the transaction report is NOT
     * rolled back when the category-balance report fails. Neither artifact is transactional in object
     * storage, so the alternatives were to delete the first on the second's failure -- which destroys a
     * good artifact over an unrelated fault -- or to swallow the second's failure, which would report a
     * night as complete having produced one of two reports. Failing the state leaves the good report
     * present and the chain stopped, which is the state an operator can act on.</p>
     *
     * @param parameters the validated run parameters, which must carry the business date; must not be
     *     {@code null}
     * @throws Exception if either artifact cannot be published, or if the range holds a transaction
     *     whose dimensions do not resolve, which stops the run as the reference's abend does
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

        ReportArtifactPublisher.PublishedArtifact published = publisher.publishDaily(businessDate);
        TransactionReportService.ReportGenerationSummary summary = published.summary();

        // WHY : Refactoring Rationale: the record names the ARTIFACT and the RUN, where an earlier
        //       revision recorded only the counts. Neither was recoverable afterwards: the key was
        //       assembled inside the publisher and discarded, and the run identity was in the container's
        //       environment and never read. An operator asked which object a night produced had a line
        //       saying how many records it held and nothing saying where they went.
        LOG.info("event=reporting.report.produced businessDate={} run={} artifact={} records={}"
                        + " detailLines={} pageBands={} groupBands={}",
                businessDate, ReportingTaskRunner.runIdentity(), published.locator(),
                summary.recordsWritten(), summary.detailLines(),
                summary.pageTotalBands(), summary.accountTotalBands());

        // WHY : Assumptions: the category-balance report takes NO business date, and passing one would
        //       be inventing a contract. app/jcl/PRTCATBL.jcl:44-45 feeds its sort the whole unloaded
        //       file with no INCLUDE condition, unlike app/jcl/TRANREPT.jcl:47-48 which does carry one,
        //       so the report is a full print of current balances rather than a period report.
        CategoryBalanceArtifactPublisher.PublishedCategoryBalanceReport balances =
                this.categoryBalances.publish();
        // WHY : Assumptions: the total is logged and is NOT written into the artifact. The reference's
        //       sort declares only SORT FIELDS and OUTREC -- no OUTFIL TRAILER and no SECTIONS -- so
        //       every byte of its output is a detail line, and adding a total would make the artifact
        //       differ from the reference's by a line. Logging it gives an operator a figure to
        //       reconcile against the money-parity verification pass without changing the bytes.
        LOG.info("event=reporting.category-balance.produced businessDate={} run={} artifact={}"
                        + " lines={} total={}",
                businessDate, ReportingTaskRunner.runIdentity(), balances.locator(),
                balances.summary().linesWritten(), balances.summary().total());
    }
}
