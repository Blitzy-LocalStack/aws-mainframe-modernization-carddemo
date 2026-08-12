package com.carddemo.batch.service;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.domain.BatchRun;
import com.carddemo.batch.dto.BatchErrorEvent;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.common.error.AbendDetail;
import com.carddemo.common.observability.ThrowableDigest;
import com.carddemo.common.web.CorrelationIdFilter;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Runs one batch step under the durable step ledger, so a redriven step that already finished is a no-op.
 *
 * <p>Purpose: the ledger table {@code batch.batch_run} exists to answer one question before a step does
 * anything at all -- has this step of this run already finished, and with what outcome. Nothing used to
 * ask it: the repository had no production caller and the no-op behaviour was described in prose only,
 * which meant a redrive re-ran every step it reached and the ledger recorded nothing. This class is where
 * the question is asked and where the answer is written.</p>
 *
 * <p>Assumptions: the reference has no checkpoint contract to preserve. The only {@code RESTART=} anywhere
 * in the baseline is commented out at {@code app/jcl/DEFGDGD.jcl:2} and there is no {@code CHKPT=} in the
 * tree at all, so this is a documented improvement rather than a transcription -- which is why the
 * behaviour is defined here in full instead of being cited to a paragraph.</p>
 *
 * <p>Assumptions: an already-COMPLETED step returns its recorded return code without running the step
 * body, and an already-FAILED step is re-run. Those are different answers on purpose. A completed step's
 * writes are already committed, so repeating them would double them; a failed step's writes were rolled
 * back with it, so repeating them is the whole point of a redrive.</p>
 *
 * <h2>⭐ Refactoring Rationale: the transitions are written outside the step's transaction</h2>
 *
 * <p>Every ledger read and write goes through {@link BatchStepLedgerWriter}, whose methods each run in a
 * transaction independent of this one. That is a correction of two defects that compounded, and neither
 * was visible in a green build.
 *
 * <p><b>A failure was never recorded.</b> This class used to save the FAILED row itself. The tasklet in
 * {@code BatchConfig.LedgerGuardedStep} runs the whole step body inside one transaction, so that save
 * joined it — and the exception that triggered the save also rolled it back. A hard-failed run therefore
 * left no FAILED row at all, and the promise this ledger exists to keep, that the table says which steps
 * finished and how, was silently unkept for the one outcome that matters most.
 *
 * <p><b>A failed step could not be redriven.</b> The previous design re-ran a FAILED step by inserting a
 * <em>second</em> row for the same run and step, and justified it by saying the previous attempt's row
 * "is deleted before the new one is written; that deletion is the caller's". No caller deletes it — a
 * redrive is an orchestrator action and issues no SQL — and {@code uq_batch_run_run_step} admits one row
 * per pair, so the second insert was refused by the database. A FAILED row that did somehow persist made
 * its step permanently unrunnable. The row is now <b>re-opened</b> and its attempt counted, which keeps
 * the uniqueness constraint and keeps the no-op decision a single-row read.
 *
 * <p>Trade-offs: an independent transaction is not free — it suspends the caller's and checks out a second
 * pooled connection for the length of a single-row write. The alternative is the state above: a ledger
 * whose failure records are destroyed by the failures they describe. The pool is sized for exactly this,
 * on the ground its own configuration records, that sizing follows how many transactions one job runs
 * concurrently.
 *
 * <p>Trade-offs: because the OPEN row now commits immediately, a step whose container dies mid-flight
 * leaves a STARTED row behind where previously it left nothing. That row is re-opened by the next attempt
 * rather than adjudicated, so the redrive behaviour is unchanged; what is gained is that the abandoned
 * attempt is now visible in the table and counted, instead of being indistinguishable from a step that
 * never began.</p>
 */
@Service
public class BatchStepLedger {

    /** The operational log this class writes its ledger decisions to. */
    private static final Logger LOG = LoggerFactory.getLogger(BatchStepLedger.class);

    /** The ledger's own writer, whose every operation runs in a transaction independent of this one. */
    private final BatchStepLedgerWriter writer;

    /**
     * The terminal sink a failed step is reported to, empty when the deployment provisions none.
     *
     * <p>Assumptions: the optional is held rather than resolved per call, because whether a sink exists
     * is a property of the deployment and cannot change while the process runs -- the whole queue
     * configuration is gated on the sink's address at context refresh.</p>
     */
    private final Optional<BatchFailureReporter> reporter;

    /**
     * The four-character abend code a migrated batch failure carries, the reference field's own width.
     *
     * <p>Assumptions: the reference block's {@code ABEND-CODE} is {@code PIC X(04)} at
     * {@code app/cpy/CSMSG02Y.cpy:23} and holds a transaction-abend code such as {@code ASRA}. A batch
     * step has no such code, so this is the migrated analogue: a fixed token that says the failure
     * class is a batch step rather than a transaction abend. Rendering the numeric tier here instead
     * was the alternative and was declined, because the tier is already a component of the event in its
     * own right and a second rendering of it would make the code redundant rather than informative.</p>
     */
    static final String BATCH_ABEND_CODE = "BSTP";

    /**
     * Builds the ledger over its writer and, when one is deployed, the terminal failure sink.
     *
     * @param writer the collaborator that reads and writes the ledger row in independent transactions;
     *     must not be {@code null}
     * @param reporter the terminal sink a failed step is reported to, empty when the deployment
     *     publishes no sink address; must not be {@code null}, though it may be empty
     * @throws NullPointerException if either argument is {@code null}
     */
    // WHY : Refactoring Rationale: the repository and the clock were injected here and are now reached
    //       through the writer instead. Keeping either would leave this class able to write a ledger row
    //       inside the caller's transaction, which is the defect the writer exists to remove -- and a
    //       second path to the same table is how a correction like this one regresses.
    // WHY : Refactoring Rationale: the reporter is new, and its absence was the defect that made the
    //       module's whole queue configuration dead. The terminal error sink the migration plan's
    //       section 0.4.1.8 provisions had no producer anywhere in the module, so it stayed empty
    //       whatever the night did. This class is where the producer belongs because it is the ONE
    //       place that observes every step failure: the entry point sees only the job, and a job body
    //       sees only its own step, so a reporter placed in either would report some failures and not
    //       others.
    // WHY : Assumptions: the dependency is an Optional rather than a required bean, because the sink is
    //       optional by design -- an environment that publishes no sink address registers no
    //       implementation. Declaring it required would make the module unable to start anywhere the
    //       sink is not provisioned, which includes every test context in the module and the local
    //       runtime, and would convert an optional report into a startup precondition.
    public BatchStepLedger(BatchStepLedgerWriter writer, Optional<BatchFailureReporter> reporter) {
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
        this.reporter = Objects.requireNonNull(reporter, "reporter must not be null");
    }

    /**
     * Runs one step under the ledger, or skips it because it already completed.
     *
     * @param runId the orchestrator execution the step belongs to; must not be {@code null} or blank
     * @param stepName the step's own name, unique within the run; must not be {@code null} or blank
     * @param jobName the job the step belongs to, carried so a published failure names it; must not be
     *     {@code null}
     * @param body the step to run, returning the graded outcome it reached; must not be {@code null}
     * @return the outcome, either the one the body reached or the one already recorded, never
     *     {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the run identifier or the step name is blank or exceeds its
     *     mapped width, which the ledger row itself refuses
     * @throws RuntimeException whatever the body raised, after the ledger row has been marked failed in
     *     a committed transaction of its own, so the record survives the caller's rollback
     */
    public StepOutcome runStep(String runId, String stepName, BatchJobName jobName,
            Supplier<BatchReturnCode> body) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(stepName, "stepName must not be null");
        Objects.requireNonNull(jobName, "jobName must not be null");
        Objects.requireNonNull(body, "body must not be null");

        Optional<BatchRun> recorded = this.writer.read(runId, stepName);
        if (recorded.isPresent()
                && recorded.get().getStatus() == BatchRun.BatchRunStatus.COMPLETED) {
            // WHY : Assumptions: the recorded code is returned rather than a fresh success, because a
            //       step that completed with the graded warn code did not succeed cleanly and a redrive
            //       reporting success for it would let a downstream Choice take a path the original
            //       execution did not.
            BatchReturnCode already =
                    BatchReturnCode.fromNumericValue(recorded.get().getReturnCode());
            LOG.info("event=batch.step.skipped runId={} step={} recordedReturnCode={} attempt={}",
                    runId, stepName, already.numericValue(), recorded.get().getAttempt());
            return new StepOutcome(already, true);
        }

        // WHY : Assumptions: a row recorded as FAILED, and one left STARTED by an attempt whose container
        //       died, are both re-opened on the SAME row with the attempt counted. uq_batch_run_run_step
        //       admits one row per run and step, so inserting a second is refused by the database; the
        //       writer's own contract carries the argument.
        Long rowId = this.writer.openAttempt(runId, stepName);

        BatchReturnCode outcome;
        try {
            outcome = Objects.requireNonNull(body.get(), "a step body must return a return code");
        } catch (RuntimeException failure) {
            // WHY : Assumptions: this write COMMITS even though the caller's transaction is about to roll
            //       back, which is the whole reason the writer exists. Recording the failure inside the
            //       failing transaction destroyed the record along with the work.
            this.writer.closeAttempt(rowId, BatchReturnCode.HARD_FAILURE, true);
            // WHY : Refactoring Rationale: the throwable is not handed to the facade. A trailing throwable
            //       argument renders its message and every cause's message, and those messages are
            //       composed by libraries -- a driver quotes the statement and the row it could not
            //       write -- so their content is unbounded and can carry an account identifier or a whole
            //       record. ThrowableDigest keeps the type chain and the frames, which answer what failed
            //       and where, and drops only the messages.
            LOG.error("event=batch.step.failed runId={} step={} failureDigest={}",
                    runId, stepName, ThrowableDigest.of(failure));
            // WHY : Assumptions: the report is issued AFTER the ledger row has been committed as failed
            //       and BEFORE the failure is re-raised. After, so the durable record exists whatever
            //       the send does; before, because once the throwable leaves this method the frame that
            //       knows the step name and the tier is gone and the only remaining reporter is the
            //       entry point, which knows the job and not the step.
            reportFailure(runId, stepName, jobName, abendDetailOf(stepName, failure));
            throw failure;
        }

        this.writer.closeAttempt(rowId, outcome, false);
        LOG.info("event=batch.step.completed runId={} step={} returnCode={}",
                runId, stepName, outcome.numericValue());

        // WHY : Assumptions: a body that RETURNS the failure tier is reported as well as one that
        //       raises, and both arms are needed because the two failure shapes are genuinely distinct.
        //       A step that cannot read its input raises; a step that completed its pass and graded the
        //       result as a hard failure returns. Reporting only the raising arm would leave the second
        //       shape absent from the sink while the ledger recorded it, so the queue and the table
        //       would disagree about which nights failed.
        // WHY : Assumptions: the WARN tier is deliberately NOT reported. The reference reaches it by
        //       design at app/cbl/CBTRN02C.cbl:229-230, where a non-zero reject count moves 4 into
        //       RETURN-CODE on a run that did its job correctly, so publishing it would raise an
        //       operator for a successful night -- and the credibility of a sink is spent the first
        //       time it does that. BatchErrorEvent's own constructor refuses the tier for the same
        //       reason, so this guard and that refusal agree rather than duplicate: this one decides
        //       not to call, and that one would refuse the call.
        if (!outcome.permitsDownstreamRun()) {
            reportFailure(runId, stepName, jobName,
                    new AbendDetail(BATCH_ABEND_CODE, truncatedCulprit(stepName),
                            "step graded its own outcome as the failure tier", ""));
        }
        return new StepOutcome(outcome, false);
    }

    /**
     * Reports one terminal step failure to the sink, when a sink is deployed.
     *
     * <p>Assumptions: the event is built through
     * {@link BatchErrorEvent#withRedactedDiagnostics} rather than through the canonical constructor,
     * even though the diagnostics assembled here are a fixed token, a step name and a
     * {@link ThrowableDigest}, none of which can carry a value a person owns. The redacting factory is
     * used because the digest's content is composed from type and frame names supplied by whichever
     * library failed, so its provenance is not this module's; the canonical constructor REFUSES
     * identifier-shaped text rather than repairing it, and a refusal here would turn an unreportable
     * diagnostic into an exception on the reporting path. Choosing the factory means a surprising
     * digest is masked and still published.</p>
     *
     * <p>Assumptions: this method never raises. The port's contract obliges an implementation not to
     * throw, and the two remaining sources of a throwable here are the event construction and the
     * optional's own access, so both are inside the guard. The one call site pair is a catch block about
     * to re-raise and a graded return, and an exception from either would replace a diagnosed step
     * failure with an undiagnosed reporting failure.</p>
     *
     * @param runId the orchestrator execution the failed step belongs to; must not be {@code null} or
     *     blank
     * @param stepName the failed step's own name; must not be {@code null} or blank
     * @param jobName the job the failed step belongs to; must not be {@code null}
     * @param abendDetail the diagnostics to carry, of any provenance; must not be {@code null}
     */
    private void reportFailure(String runId, String stepName, BatchJobName jobName,
            AbendDetail abendDetail) {
        if (this.reporter.isEmpty()) {
            return;
        }

        try {
            this.reporter.get().report(BatchErrorEvent.withRedactedDiagnostics(
                    runId, stepName, jobName, BatchReturnCode.HARD_FAILURE,
                    correlationIdentity(runId), abendDetail));
        } catch (RuntimeException reportingFailure) {
            LOG.error("event=batch.error.report-refused runId={} step={} failureDigest={}",
                    runId, stepName, ThrowableDigest.of(reportingFailure));
        }
    }

    /**
     * Resolves the correlation identity the run's log lines were written under.
     *
     * <p>Assumptions: the value is read from the diagnostic context rather than taken as a parameter,
     * because the entry point puts it there before the context is even built and every line the process
     * logs carries it from that moment. Passing it down through the step wiring would give the same
     * value a second channel to travel on and a second chance to disagree with the log.</p>
     *
     * <p>Trade-offs: the run identifier is used when the context carries nothing, which happens in a
     * unit test that calls this class directly. The event requires the component to be non-blank, so
     * some value is required; the run identifier is the closest thing to a correlation identity
     * available and is never blank, because the ledger row it was already written under refuses a blank
     * one.</p>
     *
     * @param runId the orchestrator execution identifier, used when the diagnostic context is empty;
     *     must not be {@code null} or blank
     * @return the correlation identity, never {@code null} and never blank
     */
    private static String correlationIdentity(String runId) {
        String fromContext = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        return fromContext == null || fromContext.isBlank() ? runId : fromContext;
    }

    /**
     * Builds the migrated abend block for a step that raised.
     *
     * <p>Assumptions: the reason and the message carry the failure's TYPE and FRAMES and never its
     * messages, which is the same rule the log line above applies and for the same reason -- a library's
     * exception message is composed by that library and can quote a statement, a row or an endpoint,
     * while a type name and a frame list are facts about code. The split puts the type in
     * {@code ABEND-REASON}, the reference's fifty-character explanatory field, and the fuller digest in
     * {@code ABEND-MSG}, its seventy-two-character one; both are truncated to those widths by
     * {@link AbendDetail} itself rather than here, so the widths are declared in one place.</p>
     *
     * @param stepName the failed step's name, which populates the culprit field; must not be
     *     {@code null}
     * @param failure the throwable the step raised; must not be {@code null}
     * @return the abend block, never {@code null}
     */
    private static AbendDetail abendDetailOf(String stepName, Throwable failure) {
        return new AbendDetail(BATCH_ABEND_CODE, truncatedCulprit(stepName),
                failure.getClass().getSimpleName(), ThrowableDigest.of(failure));
    }

    /**
     * Reduces a step name to the reference culprit field's width, keeping its most distinctive end.
     *
     * <p>Assumptions: the reference's {@code ABEND-CULPRIT} is {@code PIC X(08)} at
     * {@code app/cpy/CSMSG02Y.cpy:25} and holds an eight-character program name, while a migrated step
     * name is a hyphenated phrase that is routinely longer. {@link AbendDetail} would truncate it from
     * the LEFT, keeping the first eight characters -- and the step names in this module share long
     * prefixes, so several distinct steps would reduce to the same eight characters and the field would
     * stop identifying anything. Keeping the LAST eight preserves the part that differs.</p>
     *
     * <p>Trade-offs: the value is therefore not a prefix of the step name, so a reader cannot
     * reconstruct the step from this field alone. That is acceptable because the event carries the full
     * step name as a component of its own; this field exists so the abend block is self-describing when
     * read in isolation, and a distinguishing suffix serves that better than an ambiguous prefix.</p>
     *
     * @param stepName the step name to reduce; must not be {@code null}
     * @return the step name when it already fits {@link AbendDetail#ABEND_CULPRIT_LENGTH}, and its last
     *     that-many characters otherwise; never {@code null}
     */
    private static String truncatedCulprit(String stepName) {
        return stepName.length() <= AbendDetail.ABEND_CULPRIT_LENGTH
                ? stepName
                : stepName.substring(stepName.length() - AbendDetail.ABEND_CULPRIT_LENGTH);
    }

    /**
     * What running a step under the ledger produced.
     *
     * @param returnCode the graded outcome, never {@code null}
     * @param skipped {@code true} when the step body was not run because the ledger already recorded a
     *     completion for it
     */
    public record StepOutcome(BatchReturnCode returnCode, boolean skipped) {
    }
}
