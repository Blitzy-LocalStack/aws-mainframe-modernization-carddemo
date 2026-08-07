package com.carddemo.batch.service;

import com.carddemo.batch.domain.BatchRun;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.repository.BatchRunRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Trade-offs: the ledger row is opened in the caller's transaction if one is active, so a step whose
 * body rolls back rolls its OPEN row back too and is seen by the next redrive as never having started.
 * That is the conservative direction: a step recorded as never started is re-run, whereas a step recorded
 * as started-but-unfinished would have to be adjudicated by something, and nothing in this design is in a
 * position to adjudicate it.</p>
 */
@Service
public class BatchStepLedger {

    /** The operational log this class writes its ledger decisions to. */
    private static final Logger LOG = LoggerFactory.getLogger(BatchStepLedger.class);

    /** The ledger rows. */
    private final BatchRunRepository runs;

    /** The clock the ledger timestamps are taken from, injected so a test can fix it. */
    private final Clock clock;

    /**
     * Builds the ledger over its repository and clock.
     *
     * @param runs the repository over {@code batch.batch_run}; must not be {@code null}
     * @param clock the clock the ledger timestamps come from; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public BatchStepLedger(BatchRunRepository runs, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Runs one step under the ledger, or skips it because it already completed.
     *
     * @param runId the orchestrator execution the step belongs to; must not be {@code null} or blank
     * @param stepName the step's own name, unique within the run; must not be {@code null} or blank
     * @param body the step to run, returning the graded outcome it reached; must not be {@code null}
     * @return the outcome, either the one the body reached or the one already recorded, never
     *     {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the run identifier or the step name is blank or exceeds its
     *     mapped width, which the ledger row itself refuses
     * @throws RuntimeException whatever the body raised, after the ledger row has been marked failed
     */
    public StepOutcome runStep(String runId, String stepName, Supplier<BatchReturnCode> body) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(stepName, "stepName must not be null");
        Objects.requireNonNull(body, "body must not be null");

        Optional<BatchRun> recorded = this.runs.findByRunIdAndStepName(runId, stepName);
        if (recorded.isPresent()
                && recorded.get().getStatus() == BatchRun.BatchRunStatus.COMPLETED) {
            // WHY : Assumptions: the recorded code is returned rather than a fresh success, because a
            //       step that completed with the graded warn code did not succeed cleanly and a redrive
            //       reporting success for it would let a downstream Choice take a path the original
            //       execution did not.
            BatchReturnCode already =
                    BatchReturnCode.fromNumericValue(recorded.get().getReturnCode());
            LOG.info("event=batch.step.skipped runId={} step={} recordedReturnCode={}",
                    runId, stepName, already.numericValue());
            return new StepOutcome(already, true);
        }

        // WHY : Assumptions: a row recorded as FAILED is re-run, and the SAME row is not reopened -- a
        //       new row is inserted for the new attempt, because the mapping refuses a transition out of
        //       a terminal state and because the attempt history is worth keeping. The uniqueness
        //       constraint is over the run and step pair, so the previous attempt's row is deleted before
        //       the new one is written; that deletion is the caller's, and it is why a redrive is
        //       expressed as an orchestrator action rather than as a second insert here.
        BatchRun open = this.runs.save(new BatchRun(runId, stepName, LocalDateTime.now(this.clock)));

        BatchReturnCode outcome;
        try {
            outcome = Objects.requireNonNull(body.get(), "a step body must return a return code");
        } catch (RuntimeException failure) {
            open.markFailed(LocalDateTime.now(this.clock),
                    (short) BatchReturnCode.HARD_FAILURE.numericValue());
            this.runs.save(open);
            LOG.error("event=batch.step.failed runId={} step={} exception={}",
                    runId, stepName, failure.getClass().getName(), failure);
            throw failure;
        }

        open.markCompleted(LocalDateTime.now(this.clock), (short) outcome.numericValue());
        this.runs.save(open);
        LOG.info("event=batch.step.completed runId={} step={} returnCode={}",
                runId, stepName, outcome.numericValue());
        return new StepOutcome(outcome, false);
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
