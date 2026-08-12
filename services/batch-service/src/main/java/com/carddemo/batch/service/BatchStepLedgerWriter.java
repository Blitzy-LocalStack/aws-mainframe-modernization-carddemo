package com.carddemo.batch.service;

import com.carddemo.batch.domain.BatchRun;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.repository.BatchRunRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the step ledger's state transitions in transactions of their own, so a step's own rollback
 * cannot erase the record of what the step did.
 *
 * <h2>Purpose</h2>
 *
 * <p>The ledger table {@code batch.batch_run} answers one question before a redriven step does any work:
 * has this step of this run already finished, and with what outcome. That answer is only worth anything
 * if it survives the failure it describes. Every method here therefore runs in a transaction independent
 * of the caller's, so the row a failed step leaves behind is committed while the step's own writes are
 * rolled back.
 *
 * <p>Parameters, return values, exceptions or errors. This type is a Spring stereotype with one
 * constructor and three operations; each member below carries its own at-clauses. The inapplicability of
 * a type-level parameter list is stated rather than passed over, because user-specified Rule 1 forbids a
 * docstring that omits parameters or return values and a reader must be able to tell a declared
 * inapplicability from an omission.
 *
 * <h2>⭐ Refactoring Rationale: why this is a separate bean and not three methods on the ledger</h2>
 *
 * <p>{@link BatchStepLedger} used to open, complete and fail the row itself. All three writes therefore
 * joined the step's transaction — the tasklet in {@code BatchConfig.LedgerGuardedStep} runs the whole
 * body inside one transaction — and the failure write in particular was rolled back by the very
 * exception it was recording. A run that hard-failed left <b>no</b> FAILED row at all: the next redrive
 * saw a step that had never started, which happened to re-run it and so hid the defect behind correct
 * behaviour, while the ledger's own promise to record failures was never kept.
 *
 * <p>Moving the three writes onto a <b>separate bean</b> is not a stylistic preference; it is the only
 * arrangement that works. Spring applies {@code @Transactional} through a proxy, and a call from one
 * method of a class to another method of the <em>same</em> class does not pass through that proxy — so an
 * annotation on a sibling method of {@link BatchStepLedger} would be silently ignored and the writes
 * would still join the caller's transaction. That failure mode is invisible: the code reads as though it
 * were isolated and behaves as though the annotation were absent.
 *
 * <p>Alternatives Considered: injecting {@code TransactionTemplate} into {@link BatchStepLedger} and
 * running each write through it with {@code PROPAGATION_REQUIRES_NEW}. Rejected because it puts
 * transaction-management plumbing into the class whose subject is the redrive decision, and because the
 * three writes would then each restate the propagation setting — three places for one rule. Alternatives
 * Considered: {@code @Transactional(propagation = NOT_SUPPORTED)} with an auto-commit connection.
 * Rejected because the re-open transition reads a row and writes it, and a non-transactional read-modify-write
 * would let two openers of one step both proceed, which the uniqueness constraint currently makes
 * impossible.
 *
 * <h2>Trade-offs: a second connection is held for the length of each write</h2>
 *
 * <p>An independent transaction suspends the caller's and checks out a second connection from the pool,
 * so a step body running under the ledger holds two connections while a transition is written. The pool
 * is sized at four in {@code application.yml} on the stated ground that sizing follows how many
 * transactions one job runs concurrently, which is exactly this: the accepted cost is one further
 * connection for the duration of a single-row insert or update, and the alternative is a ledger that
 * cannot record a failure.
 *
 * @see BatchStepLedger
 * @see BatchRun
 */
@Service
public class BatchStepLedgerWriter {

    /** The ledger rows. */
    private final BatchRunRepository runs;

    /** The clock the ledger timestamps are taken from, injected so a test can fix it. */
    private final Clock clock;

    /**
     * Builds the writer over its repository and clock.
     *
     * @param runs the repository over {@code batch.batch_run}; must not be {@code null}
     * @param clock the clock the ledger timestamps come from; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public BatchStepLedgerWriter(BatchRunRepository runs, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Reads the recorded state of one step of one run, in a transaction of its own.
     *
     * <p>Assumptions: the read is isolated for the same reason the writes are — it is taken before the
     * step body runs and must see rows a previous attempt committed, which a read joining a caller's
     * transaction would also see, but which a read taken inside a transaction the body later rolls back
     * would be indistinguishable from. Keeping every ledger access in its own transaction means the
     * ledger's view of a run never depends on the fate of the step being decided.</p>
     *
     * @param runId the orchestrator execution the step belongs to; must not be {@code null}
     * @param stepName the step's own name, unique within the run; must not be {@code null}
     * @return the recorded row, or an empty optional when this step of this run has never been attempted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<BatchRun> read(String runId, String stepName) {
        return this.runs.findByRunIdAndStepName(runId, stepName);
    }

    /**
     * Opens an attempt for one step of one run, inserting the row or re-opening the recorded one.
     *
     * <p>Assumptions: a row recorded as FAILED, and a row recorded as STARTED by an attempt whose
     * container died without publishing an outcome, are both <b>re-opened</b> rather than duplicated.
     * {@code uq_batch_run_run_step} admits one row per run and step, so a second insert is refused by the
     * database; re-opening keeps the constraint and counts the attempt.</p>
     *
     * <p>Assumptions: a COMPLETED row is never reached here. Its caller decides the redrive no-op before
     * calling this method, and {@link BatchRun#reopen(LocalDateTime)} refuses one anyway, so a completed
     * step's writes cannot be applied twice even if a future caller forgot the check.</p>
     *
     * @param runId the orchestrator execution the step belongs to; must not be {@code null} or blank
     * @param stepName the step's own name, unique within the run; must not be {@code null} or blank
     * @return the surrogate identity of the open row, so the caller closes the row it opened rather than
     *     re-deriving it from the natural key; never {@code null}
     * @throws IllegalArgumentException if the run identifier or the step name is blank or exceeds its
     *     mapped width, which the ledger row itself refuses
     * @throws IllegalStateException if the recorded row is COMPLETED, which its caller must have handled
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long openAttempt(String runId, String stepName) {
        LocalDateTime now = LocalDateTime.now(this.clock);
        Optional<BatchRun> recorded = this.runs.findByRunIdAndStepName(runId, stepName);
        BatchRun row = recorded.orElse(null);
        if (row == null) {
            row = new BatchRun(runId, stepName, now);
        } else {
            row.reopen(now);
        }
        return this.runs.save(row).getId();
    }

    /**
     * Records a terminal outcome against an open row, in a transaction of its own.
     *
     * <p>Assumptions: the row is located by its surrogate identity rather than by the natural key,
     * because the caller has just opened it and re-deriving it would read a row a concurrent opener could
     * have replaced. A row that has vanished between the two calls is reported rather than recreated: a
     * recreated row would carry a start time this method does not know and an attempt count reset to
     * one.</p>
     *
     * @param rowId the surrogate identity {@link #openAttempt(String, String)} returned; must not be
     *     {@code null}
     * @param outcome the graded outcome the step reached, or {@code null} to record a hard failure with
     *     no published code
     * @param failed {@code true} to record the FAILED state, {@code false} to record COMPLETED
     * @throws IllegalStateException if the row is no longer stored, or if the outcome does not belong to
     *     the state being recorded, which the row's own transitions refuse
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void closeAttempt(Long rowId, BatchReturnCode outcome, boolean failed) {
        BatchRun row = this.runs.findById(rowId).orElseThrow(() -> new IllegalStateException(
                "the ledger row opened for this step is no longer stored, so its outcome cannot be"
                        + " recorded; the row is identified by its surrogate key and is never"
                        + " recreated, because a recreated row would carry a start time and an attempt"
                        + " count that describe a different attempt"));
        LocalDateTime now = LocalDateTime.now(this.clock);
        if (failed) {
            row.markFailed(now, outcome == null
                    ? null
                    : Short.valueOf((short) outcome.numericValue()));
        } else {
            row.markCompleted(now, (short) outcome.numericValue());
        }
        this.runs.save(row);
    }
}
