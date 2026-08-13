package com.carddemo.reporting.task;

import com.carddemo.reporting.ReportingTask;
import com.carddemo.reporting.ReportingTaskRunner;
import com.carddemo.reporting.service.StatementIndexEntry;
import com.carddemo.reporting.service.StatementRunOutcome;
import com.carddemo.reporting.service.StatementService;
import com.carddemo.reporting.sink.S3ArtifactWriter;
import com.carddemo.reporting.sink.S3StatementSink;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The nightly statement run, registered under the name the orchestrator dispatches.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Purpose: {@code ReportingTaskRunner} dispatches {@code --job=generate-statements} by looking a
 * {@link ReportingTask} bean up under that exact name, and no bean carried it. The runner's own charter said
 * so plainly -- it described the name list as "a target contract" and recorded that a run would accept the
 * command, start the context, miss the lookup and end in the hard-failure tier. This class closes that:
 * the {@code GenerateStatements} state of the {@code carddemo-daily-batch} machine now produces the two
 * artifacts it is scheduled to produce.</p>
 *
 * <p>Assumptions: the bean NAME is the job token and is set on the annotation rather than derived from the
 * class name. A derived name would be {@code generateStatementsTask}, which is not the token the
 * orchestrator sends, and the disagreement would compile, build an image, start a task and fail only at
 * dispatch. The token is taken from {@link ReportingTaskRunner#JOB_NAMES} rather than retyped for the same
 * reason the runner publishes it as a constant.</p>
 *
 * <p>Assumptions: the business date is accepted and NOT used to select statements. The reference's
 * statement run has no date parameter at all -- {@code app/jcl/CREASTMT.JCL} passes none and
 * {@code app/cbl/CBSTM03A.CBL} reads none -- and it produces one statement per cross-reference row over
 * whatever the transaction dataset holds. The parameter arrives because the orchestrator supplies one
 * uniformly to its nightly states, and it is recorded in the run's journal line so that a produced artifact
 * can be attributed to a night. Filtering on it would be a behavioural change the reference does not
 * have.</p>
 *
 * <p>Assumptions: the two artifact keys are composed from the configured prefix and a fixed object name per
 * artifact, so a rerun of a night replaces that night's output rather than accumulating a second copy. The
 * dataset bucket is versioned and its lifecycle retains five noncurrent versions, which is the generation
 * retention the reference expresses as {@code LIMIT(5) SCRATCH}, so the previous run's artifact remains
 * recoverable without the key having to carry a generation.</p>
 *
 * <p>Trade-offs: the sink is closed in a try-with-resources so the artifacts are published only when the
 * run has finished writing, and a run that throws leaves the previous artifacts in place. What is given up
 * is that a partially produced run yields nothing rather than a partial file; what is bought is that no
 * reader can be handed a truncated statement file that looks complete.</p>
 */
@Component("generate-statements")
public class GenerateStatementsTask implements ReportingTask {

    /** Journal logger for this task's outcome. */
    private static final Logger LOG = LoggerFactory.getLogger(GenerateStatementsTask.class);

    /** The statement generator this task drives. */
    private final StatementService statements;

    /** The object-store client the artifacts are published through. */
    private final S3Client s3;

    /** The destination bucket. */
    private final String bucket;

    /** The key prefix the two artifacts sit under. */
    private final String prefix;

    /**
     * Creates the nightly statement task.
     *
     * @param statements the statement generator; must not be {@code null}
     * @param s3 the object-store client; must not be {@code null}
     * @param bucket the destination bucket, supplied by
     *     {@value StatementService#OUTPUT_BUCKET_PROPERTY}; must not be {@code null}
     * @param prefix the key prefix, supplied by {@value StatementService#STATEMENT_PREFIX_PROPERTY}; must
     *     not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public GenerateStatementsTask(
            StatementService statements,
            S3Client s3,
            @Value("${" + StatementService.OUTPUT_BUCKET_PROPERTY + "}") String bucket,
            @Value("${" + StatementService.STATEMENT_PREFIX_PROPERTY + "}") String prefix) {
        this.statements = Objects.requireNonNull(statements, "statements must not be null");
        this.s3 = Objects.requireNonNull(s3, "s3 must not be null");
        this.bucket = Objects.requireNonNull(bucket, "bucket must not be null");
        this.prefix = Objects.requireNonNull(prefix, "prefix must not be null");
    }

    /**
     * Produces the run's two statement artifacts.
     *
     * @param parameters the validated run parameters, from which the business date is read for the journal
     *     line; must not be {@code null}
     * @throws Exception if either artifact cannot be published, or if a cross-reference row names a
     *     customer or an account that does not resolve, both of which stop the run as the reference's abend
     *     does
     */
    @Override
    public void run(Map<String, String> parameters) throws Exception {
        Objects.requireNonNull(parameters, "parameters must not be null");
        // WHY : Assumptions: the token is PARSED rather than passed through as text, even though nothing
        //       here selects on it. The runner validates its width at the process boundary, which catches
        //       a truncated argument and admits ten characters that are not a date -- so parsing is what
        //       stops a journal line attributing a night's artifacts to a date that does not exist.
        LocalDate businessDate = businessDateOrNull(
                parameters.get(ReportingTaskRunner.BUSINESS_DATE_PARAMETER));

        StatementRunOutcome outcome;
        try (S3StatementSink sink = new S3StatementSink(
                new S3ArtifactWriter(s3, bucket, prefix + S3StatementSink.PLAIN_TEXT_OBJECT),
                new S3ArtifactWriter(s3, bucket, prefix + S3StatementSink.HTML_OBJECT))) {
            outcome = statements.generateStatements(sink);
        }
        publishIndex(outcome);
        int produced = outcome.statementsProduced();

        // WHY : Assumptions: the journal line names the business date and the count and no cardholder
        //       value of any kind. The count is what an operator reconciles against the previous night and
        //       the date is what attributes the artifact; a card number, an account identifier or a
        //       customer name would each be a value docs/architecture/observability.md names as one an
        //       operator-read record must omit.
        LOG.info("event=reporting.statements.produced businessDate={} statements={}",
                businessDate == null ? "unset" : businessDate, produced);
    }

    /**
     * Publishes the run's index, one fixed-width record per statement.
     *
     * <p>⚠️ Refactoring Rationale: this exists because the two artifacts this task writes are RUN-WIDE
     * while the response that points a caller at them describes ONE CARD. Without the index a caller was
     * handed a document covering the whole portfolio and no way to find its own statement inside it,
     * which is half of what a review found wrong with the statement surface. The index is written here
     * rather than by the generator because this task owns the object-store client and every other write
     * of the run, and it is written AFTER the generator returns because a card's record count is not
     * known until its statement has been emitted.
     *
     * <p>Assumptions: the index is written even when the run produced NO statements, and the object it
     * then writes is empty. Writing it unconditionally is what keeps the three artifacts of a run in
     * step: a night that produced nothing leaves an empty index rather than the previous night's, so a
     * read cannot resolve a card into a position in an artifact that no longer contains it.
     *
     * <p>Assumptions: the writer is closed before this method returns, in the same
     * try-with-resources shape as the two artifact writers above, so a failure mid-index abandons the
     * upload rather than publishing a truncated index -- and a truncated index is the one state the read
     * path refuses outright, because every position derived from it would name the wrong card.
     *
     * @param outcome what the generator produced, carrying one index entry per statement; must not be
     *     {@code null}
     * @throws IOException if the index cannot be published
     */
    private void publishIndex(StatementRunOutcome outcome) throws IOException {
        try (S3ArtifactWriter writer =
                new S3ArtifactWriter(s3, bucket, prefix + StatementService.INDEX_OBJECT)) {
            for (StatementIndexEntry entry : outcome.index()) {
                writer.write(entry.encode());
            }
        }
    }

    /**
     * Reduces a business date token to a calendar date, or to nothing when none was supplied.
     *
     * <p>Assumptions: this exists so that a malformed date is refused by this task rather than reaching a
     * journal line as text. The runner validates the token's WIDTH at the process boundary, which catches a
     * truncated argument but admits ten characters that are not a date.</p>
     *
     * @param token the business-date token as the runner passed it, which may be {@code null}
     * @return the parsed date, or {@code null} when no token was supplied
     * @throws java.time.format.DateTimeParseException if the token is not a calendar date in
     *     {@code YYYY-MM-DD} order
     */
    static LocalDate businessDateOrNull(String token) {
        return token == null || token.isBlank() ? null : LocalDate.parse(token);
    }
}
