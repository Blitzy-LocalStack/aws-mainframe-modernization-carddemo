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
import org.slf4j.MDC;
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
 * the {@code GenerateStatements} state of the {@code carddemo-daily-batch} machine now produces the
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
 * <p>⚠️ Assumptions: the three object keys of a run are composed from the configured prefix and a
 * freshly minted run identifier, so a run writes each of them EXACTLY ONCE and a rerun of a night
 * accumulates a second complete set beside the first rather than overwriting it. The keys used to be
 * fixed, and a review found what that cost: the three objects became visible one at a time, so a reader
 * could hold one run's index over another run's artifact and report a position that addressed an
 * unrelated cardholder's statement. The run becomes readable only when {@link #run(java.util.Map)}
 * writes the manifest last, and the reasoning for that scheme is recorded on
 * {@code StatementService.MANIFEST_OBJECT}.</p>
 *
 * <p>Trade-offs: superseded runs are now retained as whole key sets rather than as noncurrent versions
 * of a fixed key, so the bucket's five-noncurrent-version lifecycle -- the generation retention the
 * reference expresses as {@code LIMIT(5) SCRATCH} -- no longer reclaims them, and retention of old runs
 * becomes a lifecycle rule on the run prefixes. That cost is accepted because version-paired reads were
 * the alternative to it and they cannot be made coherent without a manifest anyway; and because the
 * previous run staying intact and readable under its own keys is what lets a failed run leave a working
 * one behind.</p>
 *
 * <p>Trade-offs: the sink is COMPLETED inside the try block and its resource clause only aborts, so the
 * artifacts are published exactly when the run has finished writing and a run that throws leaves the
 * previous run current and stores nothing of its own. What is given up is that a partially produced run
 * yields nothing rather than a partial file; what is bought is that no reader can be handed a truncated
 * statement file that looks complete, and no operator has to reclaim an object no manifest names.</p>
 */
@Component("generate-statements")
public class GenerateStatementsTask implements ReportingTask {

    /** Journal logger for this task's outcome. */
    private static final Logger LOG = LoggerFactory.getLogger(GenerateStatementsTask.class);

    /**
     * The diagnostic-context key under which this run's identifier is published while the generator
     * runs, so that the generator's own lines name the run it minted here.
     */
    private static final String RUN_CONTEXT_KEY = "statementRunId";

    /** The statement generator this task drives. */
    private final StatementService statements;

    /** The object-store client the artifacts are published through. */
    private final S3Client s3;

    /** The destination bucket. */
    private final String bucket;

    /** The key prefix a run's own prefix and the manifest sit under. */
    private final String prefix;

    /**
     * Creates the nightly statement task.
     *
     * @param statements the statement generator; must not be {@code null}
     * @param s3 the object-store client; must not be {@code null}
     * @param bucket the destination bucket, supplied by
     *     {@value StatementService#OUTPUT_BUCKET_PROPERTY}; must not be {@code null}
     * @param prefix the key prefix a run's objects and the manifest sit under, supplied by
     *     {@value StatementService#STATEMENT_PREFIX_PROPERTY}; must not be {@code null}
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
     * Produces the run's three immutable objects and then publishes the run.
     *
     * <p>⚠️ Refactoring Rationale: the four writes are ORDERED, and the order is the whole of this
     * task's coherence guarantee. This task used to write its three objects to fixed keys, so each
     * became visible the moment it was written and a reader arriving between two of them saw a mixture
     * of two runs -- the previous run's index over this run's artifact, whose positions then addressed
     * whichever cardholder this run had placed there. The three objects now go to keys that carry a
     * freshly minted run identifier and are therefore written exactly once and never overwritten, and
     * the manifest naming that run is written LAST. Until it is written, nothing addresses this run; a
     * run that fails at any earlier point leaves the previous run current and complete, which is why
     * every write below propagates its failure rather than being caught.
     *
     * <p>Assumptions: the run identifier is minted HERE, once per run, rather than derived from the
     * business date. A rerun of one date is an ordinary operation -- the orchestrator's redrive performs
     * one -- and a date-derived prefix would make the rerun overwrite the run it is replacing, which is
     * the mutation this ordering exists to remove.
     *
     * @param parameters the validated run parameters, from which the business date is read for the journal
     *     line; must not be {@code null}
     * @throws Exception if any of the four objects cannot be published, or if a cross-reference row names
     *     a customer or an account that does not resolve, both of which stop the run as the reference's
     *     abend does
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

        String runId = StatementService.mintRunId();
        String runPrefix = StatementService.runKeyPrefix(prefix, runId);

        StatementRunOutcome outcome;
        // WHY : Assumptions: the run identifier is put into the diagnostic CONTEXT for the duration of
        //       the generator call, so every line the generator logs while it runs names the run --
        //       including its per-statement omission warning, which is the one line an operator needs
        //       attributed and the one the generator cannot attribute itself. The generator takes a sink
        //       and nothing else, and the identifier is minted here, so it has no way to name the run
        //       from inside.
        //       Alternatives Considered: (1) threading the identifier in as a second parameter to
        //       generateStatements. Rejected because the generator would then accept a value it uses for
        //       nothing but a log line, and every implementation of the seam and every test double would
        //       carry it. (2) Adding a fifth key to the console pattern in the shared kernel's defaults.
        //       Rejected because that pattern is read by all eight services and only this one has a run
        //       identifier, so seven would render an always-empty column. Neither is needed: the default
        //       structured console format is `ecs`, which emits every context entry, so a key placed here
        //       reaches the log with no shared change at all.
        //       Trade-offs: the key is removed in a finally rather than left for the next task, because
        //       the runner's thread is reused and a stale entry would attribute a later task's lines to
        //       this run -- which is worse than no attribution, being a confident wrong answer.
        MDC.put(RUN_CONTEXT_KEY, runId);
        try (S3StatementSink sink = new S3StatementSink(
                new S3ArtifactWriter(s3, bucket, runPrefix + S3StatementSink.PLAIN_TEXT_OBJECT),
                new S3ArtifactWriter(s3, bucket, runPrefix + S3StatementSink.HTML_OBJECT))) {
            outcome = statements.generateStatements(sink);
            // WHY : Refactoring Rationale: the two artifacts are completed EXPLICITLY here, where the
            //       resource clause used to complete them on its way out. Completing on the way out
            //       completed them on the failure path too, so a run that raised part way published its
            //       partial artifacts under this run's prefix -- two objects nothing addressed, that no
            //       manifest named, and that no incomplete-upload lifecycle rule reclaimed because the
            //       uploads had been completed rather than abandoned. Reached from here, completion
            //       happens only after the generator has returned, and the resource clause aborts on
            //       every other path. The two writes and their order are unchanged: the same
            //       plain-text-then-markup pair, completed here instead of at the resource clause, and
            //       still ahead of the index and the manifest below.
            sink.complete();
        } finally {
            MDC.remove(RUN_CONTEXT_KEY);
        }
        publishIndex(runPrefix, outcome);
        publishManifest(runId);
        int produced = outcome.statementsProduced();
        int omitted = outcome.statementsOmitted();

        // WHY : Assumptions: the journal line names the business date, the run and the count, and no
        //       cardholder value of any kind. The count is what an operator reconciles against the
        //       previous night and the date is what attributes the artifact; a card number, an account
        //       identifier or a customer name would each be a value docs/architecture/observability.md
        //       names as one an operator-read record must omit. The run identifier is admissible for the
        //       same reason it is publishable in a key: it is 122 random bits and derives from no
        //       cardholder value, and without it an operator reading this line cannot tell which of the
        //       stored runs it describes.
        // WHY : Assumptions: the omitted count is carried on THIS line rather than left to the
        //       generator's own event, because this is the line an operator reconciles a night against.
        //       Reporting the produced figure alone made a run that dropped a cardholder's statement
        //       read exactly like a run that dropped nothing, which is what a review found wrong with
        //       the statement surface: the loss was recoverable from a second event most readers of this
        //       one never see.
        // WHY : Assumptions: the level is raised when anything was omitted, so the condition is
        //       reachable by a log query that filters on level rather than only by one that parses the
        //       count. Trade-offs: the run is still reported as PRODUCED and the process still exits
        //       clean, because the artifacts are published and every statement the run could render is
        //       addressable through the index -- a failure status would discard a night's correct output
        //       over one unrenderable row, which is the disposition the omission boundary exists to
        //       retire.
        if (omitted == 0) {
            LOG.info("event=reporting.statements.produced businessDate={} run={} statements={}"
                            + " omitted={}",
                    businessDate == null ? "unset" : businessDate, runId, produced, omitted);
        } else {
            LOG.warn("event=reporting.statements.produced businessDate={} run={} statements={}"
                            + " omitted={} outcome=artifact-incomplete",
                    businessDate == null ? "unset" : businessDate, runId, produced, omitted);
        }
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
     * then writes is empty. Writing it unconditionally is what keeps the three objects of a run in step:
     * a run that produced nothing publishes an empty index of its own rather than leaving a reader to
     * pair its empty artifact with some other run's index.
     *
     * <p>Assumptions: the writer is closed before this method returns, in the same
     * try-with-resources shape as the two artifact writers above, so a failure mid-index abandons the
     * upload rather than publishing a truncated index -- and a truncated index is the one state the read
     * path refuses outright, because every position derived from it would name the wrong card.
     *
     * @param runPrefix the run's own key prefix, which the caller composed once for all three objects;
     *     must not be {@code null}
     * @param outcome what the generator produced, carrying one index entry per statement; must not be
     *     {@code null}
     * @throws IOException if the index cannot be published
     */
    private void publishIndex(String runPrefix, StatementRunOutcome outcome) throws IOException {
        try (S3ArtifactWriter writer =
                new S3ArtifactWriter(s3, bucket, runPrefix + StatementService.INDEX_OBJECT)) {
            for (StatementIndexEntry entry : outcome.index()) {
                writer.write(entry.encode());
            }
            // WHY : Assumptions: the write is completed EXPLICITLY inside the try, because
            //       S3ArtifactWriter publishes on complete() and its close() aborts an upload that was
            //       never completed. Relying on the close alone would publish no index at all, and a
            //       run whose manifest named a missing index would answer every per-card read with
            //       "not in this artifact" for statements the artifact does contain.
            writer.complete();
        }
    }

    /**
     * Publishes the run, by naming it in the manifest every read resolves against.
     *
     * <p>Purpose: this is the run's commit. Everything before it is invisible to a reader and everything
     * after it is visible atomically, because a single-object write in the store either replaces the
     * object or does not.
     *
     * <p>Assumptions: the manifest is written through the same writer the artifacts use, to the key
     * {@code StatementService.manifestKey} composes, and it is the ONLY object of this task that is
     * written to a fixed key. That asymmetry is the design: the run's data is immutable so a reader
     * cannot be shown a changed artifact, and exactly one pointer moves so a reader cannot be shown half
     * a run. Alternatives Considered: writing a marker object into the run prefix and having readers
     * list the prefix for the newest complete one; rejected because a listing is eventually consistent
     * in ordering terms, costs a request per read that grows with the number of retained runs, and
     * leaves two readers free to disagree about which run is current.
     *
     * @param runId the identifier of the run whose objects have all been published; must not be
     *     {@code null}
     * @throws IOException if the manifest cannot be published, which leaves the previous run current
     */
    private void publishManifest(String runId) throws IOException {
        try (S3ArtifactWriter writer =
                new S3ArtifactWriter(s3, bucket, StatementService.manifestKey(prefix))) {
            writer.write(StatementService.encodeManifest(runId));
            // WHY : Assumptions: completed explicitly for the same reason as the index above -- this is
            //       the run's commit, and an aborted upload would leave the previous run current while
            //       this task reported success.
            writer.complete();
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
