package com.carddemo.batch.service;

import com.carddemo.batch.domain.BatchRun;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.repository.BatchRunRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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

    /** The log the refused-attempt diagnosis is written to, named for this class. */
    private static final Logger LOG = LoggerFactory.getLogger(BatchStepLedgerWriter.class);

    /**
     * The stable code reported when another attempt of this step of this run is already in flight.
     *
     * <p>Purpose: it makes a concurrency collision queryable. Two executions carrying one run identity
     * is a scheduling fault rather than a data fault, and the operator's next action -- find the second
     * execution -- is different from every other reason a step can fail, so it needs a code of its own
     * to be filtered, counted and alarmed on.</p>
     *
     * <p>Refactoring Rationale: without it the loser of the race exited on the entry point's generic
     * {@code CARDDEMO-BATCH-0002} with a persistence-layer stack trace quoting the refused SQL
     * statement and the constraint name. That is the one shape an operator cannot act on: it names the
     * database rather than the cause, so a scheduling fault presented as a data-integrity defect and
     * pointed the investigation at the night's records.</p>
     *
     * <p>Assumptions: the number continues the series the entry point declares -- usage, job failed,
     * fatal runtime, already complete at {@code CARDDEMO-BATCH-0001} through
     * {@code CARDDEMO-BATCH-0004} in {@code com.carddemo.batch.BatchApplication} -- and is declared
     * HERE rather than beside those four because this is where the condition is detected. The entry
     * point never sees the constraint: the failure reaches it as an exception from a step, and a code
     * declared where it cannot be raised is a code that drifts from its condition.</p>
     *
     * <p>Assumptions: this is the CLOSEST sibling of {@code CARDDEMO-BATCH-0004}, and the difference
     * between the two is the whole reason the outcomes differ. That one means this step of this run has
     * already COMPLETED, so its work is committed and reporting success is correct; this one means
     * another attempt is still RUNNING, so nothing is known about whether its work will commit.</p>
     */
    public static final String OUTCOME_CODE_STEP_IN_PROGRESS = "CARDDEMO-BATCH-0005";

    /**
     * The one-line diagnosis reported beside the code above, in the log and on the exception.
     *
     * <p>Assumptions: the sentence is a constant rather than two written strings so the log record and
     * the exception message cannot drift apart. An operator matching one against the other is the
     * expected reading order, and two spellings of one condition would make that reading look like two
     * conditions.</p>
     */
    public static final String IN_PROGRESS_REASON =
            "another attempt of this step is already running for this run";

    /**
     * The stable code reported when another attempt recorded this step's outcome first.
     *
     * <p>Purpose: it names the OTHER order in which two executions carrying one run identity collide, and
     * it is a different fact from the code above rather than a restatement of it. There the loser was
     * refused before it ran anything; here the loser ran the step body to completion and found its
     * ledger row already closed by the winner, so its writes may have been applied alongside the
     * winner's. An operator reading the two codes has to be able to tell those apart, because only the
     * second one raises the question of duplicated work.</p>
     *
     * <p>Assumptions: the condition is reached when the ledger row this attempt opened is no longer in
     * the started state by the time the attempt closes it. Within this module the writer is driven by
     * {@link BatchStepLedger}, which closes each row it opens exactly once, so a row found in any other
     * state was moved by a DIFFERENT writer -- which is a second execution holding the same run and
     * step.</p>
     *
     * <p>Refactoring Rationale: this code is new, and without it the loser of this interleaving exited on
     * the entry point's generic {@code CARDDEMO-BATCH-0002} with the row transition's own
     * {@code IllegalStateException} rendered by the batch framework's step logger, frames included -- 58
     * lines of them, which is the same unreadable shape the code above was introduced to remove. The
     * uniqueness constraint catches only the insert-against-insert order, because the reopen path
     * UPDATES a row that is already there and the database has nothing to refuse.</p>
     *
     * <p>Assumptions: the number continues the same series, after
     * {@link #OUTCOME_CODE_STEP_IN_PROGRESS}, and is declared here for the same reason: this is where
     * the condition is detected.</p>
     */
    public static final String OUTCOME_CODE_STEP_OUTCOME_TAKEN = "CARDDEMO-BATCH-0006";

    /**
     * The one-line diagnosis reported beside the code above, in the log and on the exception.
     *
     * <p>Assumptions: the sentence states BOTH facts an operator needs and no others -- that a second
     * attempt reached the outcome first, and that this attempt therefore ran its body alongside it. The
     * second half is the whole difference from {@link #IN_PROGRESS_REASON}, whose attempt wrote
     * nothing, and leaving it out would present the two conditions as one.</p>
     */
    public static final String OUTCOME_TAKEN_REASON =
            "another attempt of this step recorded its outcome for this run while this one was running,"
                    + " so this attempt's work may duplicate the recorded attempt's";

    /**
     * The uniqueness the ledger's natural key is enforced by, named exactly as the schema declares it.
     *
     * <p>Assumptions: the literal is the constraint {@code BatchRun}'s own {@code @Table} annotation
     * declares and {@code db/migration/V1__batch.sql} creates at its L447. It is compared against the
     * name the database reports, case-insensitively, because PostgreSQL folds an unquoted identifier to
     * lower case and a future migration could quote it.</p>
     *
     * <p>Assumptions: it is public so that the integration test which provokes the REAL constraint can
     * assert the discrimination against the name the database actually reported, rather than restating
     * the literal and proving only that two copies of a string agree.</p>
     */
    public static final String STEP_UNIQUENESS_CONSTRAINT = "uq_batch_run_run_step";

    /**
     * The bound on how far a cause chain is walked while classifying an integrity failure.
     *
     * <p>Assumptions: a bound is present because a cause chain can be cyclic -- a library that sets a
     * throwable as its own cause makes an unbounded walk loop forever -- and because the constraint
     * name, when there is one, is within a couple of links of the top. Sixteen is far beyond the
     * observed depth of three and still terminates.</p>
     */
    private static final int CAUSE_CHAIN_LIMIT = 16;

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
     * <h2>The competing attempt is named rather than reported as a persistence fault</h2>
     *
     * <p>Refactoring Rationale: the read above and the write below are two statements in one
     * transaction, so two executions carrying one run identity can both find no row and both insert one.
     * The database settles it -- {@code uq_batch_run_run_step} admits the first and refuses the second,
     * which is exactly the protection this ledger wants -- but the refusal used to escape unhandled: the
     * loser failed on the entry point's generic job-failed code with a persistence-layer stack trace
     * quoting the refused INSERT and the constraint name. The guard was working and the diagnosis
     * described the wrong subsystem, so an operator reading it looked for a data defect in the night's
     * records instead of for the second execution.</p>
     *
     * <p>Assumptions: ONLY that named uniqueness is translated. Every other integrity failure -- the
     * primary key, the status domain, the return-code rubric, the lifecycle predicate, the interval
     * predicate, a not-null column -- keeps its existing handling and propagates unchanged, because each
     * of those says something is wrong with the row being written rather than that someone else is
     * writing it. A translation keyed on the SQL state alone would have absorbed all of them.</p>
     *
     * <p>Assumptions: the refusal is observable at the {@code save} below rather than at commit, and that
     * is a property of the mapping rather than a hope. {@code BatchRun} generates its surrogate key with
     * database identity, so the provider must execute the INSERT during {@code save} to learn the key it
     * returns -- which is why the catch can be here at all, and why {@code save} is not replaced by a
     * flushing variant to force it.</p>
     *
     * <p>Trade-offs: the loser still fails, and fails in the HARD tier, so the exit status stays at
     * eight. Reporting success would be the quiet alternative and it is wrong: this attempt performed
     * none of the step's work, and the attempt that holds the row may itself fail, so a chain allowed to
     * continue would run the next state over work that was never done. The warn tier is not available
     * either -- it carries the reject-count semantic the reference sets at
     * {@code app/cbl/CBTRN02C.cbl:229-230} and nothing else. What changes is the DIAGNOSIS, not the
     * grade: a named code and one line, so the orchestration's catch routes the execution to the failure
     * notification and the operator is pointed at the duplicate execution.</p>
     *
     * @param runId the orchestrator execution the step belongs to; must not be {@code null} or blank
     * @param stepName the step's own name, unique within the run; must not be {@code null} or blank
     * @return the surrogate identity of the open row, so the caller closes the row it opened rather than
     *     re-deriving it from the natural key; never {@code null}
     * @throws IllegalArgumentException if the run identifier or the step name is blank or exceeds its
     *     mapped width, which the ledger row itself refuses
     * @throws IllegalStateException if the recorded row is COMPLETED, which its caller must have handled
     * @throws StepAttemptInProgressException if another attempt of this step of this run holds the
     *     ledger row, which is a concurrency collision and not a defect in the row being written
     * @throws DataIntegrityViolationException if the write is refused for any OTHER reason, which is
     *     rethrown exactly as it arrived so its own diagnosis is not replaced by this one
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
        try {
            return this.runs.save(row).getId();
        } catch (DataIntegrityViolationException refused) {
            if (!namesStepUniqueness(refused)) {
                throw refused;
            }
            // WHY : Assumptions: the diagnosis is logged HERE as well as carried on the exception,
            //       because the two reach different readers. The exception fails the step and reaches
            //       the run's outcome line; this record is written under the run's own correlation
            //       identity at the moment the collision was detected, which is what lets an operator
            //       line the two executions up against each other in one log stream.
            // WHY : Assumptions: neither this line nor the exception below names the constraint, the
            //       statement or the SQL state. Those are facts about the schema, and quoting them is
            //       precisely what made the previous diagnosis unreadable; the run and the step are
            //       what identify the collision, and they are already in every line this run writes.
            LOG.error("event=batch.step.attempt-refused code={} runId={} step={} reason={}",
                    OUTCOME_CODE_STEP_IN_PROGRESS, runId, stepName, IN_PROGRESS_REASON);
            throw new StepAttemptInProgressException(runId, stepName);
        }
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
     * @throws StepOutcomeAlreadyRecordedException if the row this attempt opened has already been closed
     *     by another attempt of the same step of the same run, which is a concurrency collision and not
     *     a defect in the outcome being recorded
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
        // WHY : Assumptions: the state is read and refused HERE rather than left to the row's own
        //       transition, because the two refusals mean different things and only this one is a
        //       collision. The transition refuses a row that is not started OR that carries no start
        //       instant; the second describes a row that is internally wrong, which is a defect and keeps
        //       its own diagnosis, so only the state is tested here.
        // WHY : Assumptions: reaching this branch means a DIFFERENT writer moved the row. The caller
        //       opens a row and closes it once, and the redrive decision returns before opening when the
        //       recorded row is already terminal, so no single-execution path can arrive with a row that
        //       is not started.
        // WHY : Trade-offs: a caller that closed one row twice would now be told it lost a race rather
        //       than that it double-closed. That is accepted because no such caller exists in this module
        //       and because the sentence reported stays literally true either way -- the outcome IS
        //       already recorded -- while the alternative leaves the real collision, which does occur,
        //       reported as a persistence-layer state error with the framework's frames attached.
        if (row.getStatus() != BatchRun.BatchRunStatus.STARTED) {
            // WHY : Assumptions: the recorded status is logged and NOT put on the exception message. It
            //       is the evidence an operator needs to see which way the race went, and the log record
            //       carries the run's correlation identity; the message is read where the framework
            //       renders it, and a status there would invite reading a schema state as the diagnosis.
            LOG.error("event=batch.step.outcome-refused code={} runId={} step={} recordedStatus={}"
                            + " reason={}",
                    OUTCOME_CODE_STEP_OUTCOME_TAKEN, row.getRunId(), row.getStepName(),
                    row.getStatus(), OUTCOME_TAKEN_REASON);
            throw new StepOutcomeAlreadyRecordedException(row.getRunId(), row.getStepName());
        }
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

    /**
     * Decides whether a refused write is the step-uniqueness collision and not some other defect.
     *
     * <p>Assumptions: the decision is made from the CONSTRAINT NAME the database reported, which the
     * persistence provider carries on {@link ConstraintViolationException} in the cause chain of the
     * translated exception. Nothing else in the chain distinguishes the conditions: the SQL state is
     * {@code 23505} for the primary key as well, and the exception TYPE is the same for every integrity
     * failure, so a decision made from either would absorb refusals that mean something entirely
     * different about the row.</p>
     *
     * <p>Alternatives Considered: matching the exception's message text for the constraint name.
     * Rejected because the message is composed by the driver and the provider, so it is locale- and
     * version-dependent, and because reading it here would make the classification depend on a string
     * this class deliberately refuses to log. Alternatives Considered: reading the constraint from the
     * driver's own server-error message, which the PostgreSQL exception exposes directly. Rejected
     * because it puts a driver type in a service class, so the classification would stop compiling on a
     * change of database while the provider's own accessor is portable.</p>
     *
     * <p>Assumptions: a chain carrying NO constraint name is not the collision. That is the conservative
     * answer and it is the one the caller wants: an unrecognised integrity failure keeps the handling it
     * has today rather than being reported as a concurrency collision it may not be.</p>
     *
     * <p>Assumptions: it is static and public so the integration test can drive it with an exception the
     * REAL constraint produced, in a package that cannot see package-private members. A test that
     * provoked the collision end to end would need two connections and a blocking insert to be
     * deterministic; the race itself is exercised against two processes, and what this method has to be
     * right about is the discrimination.</p>
     *
     * @param refused the translated integrity failure to classify; must not be {@code null}
     * @return {@code true} when the chain names {@link #STEP_UNIQUENESS_CONSTRAINT}, {@code false} for
     *     every other integrity failure including one that reports no constraint at all
     */
    public static boolean namesStepUniqueness(DataIntegrityViolationException refused) {
        Objects.requireNonNull(refused, "refused must not be null");
        Throwable link = refused;
        for (int depth = 0; link != null && depth < CAUSE_CHAIN_LIMIT; depth++) {
            if (link instanceof ConstraintViolationException violation
                    && STEP_UNIQUENESS_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            link = link.getCause() == link ? null : link.getCause();
        }
        return false;
    }

    /**
     * Reports that another attempt of one step of one run holds the ledger row.
     *
     * <p>Purpose: it is the named condition the loser of a concurrency collision fails on, and it exists
     * so that the failure carries a diagnosis instead of a persistence-layer trace. Its message is one
     * line: the code, the sentence, the run and the step.</p>
     *
     * <p>Assumptions: it carries NO cause and NO stack trace, and both omissions are deliberate. The
     * refused write is attached to nothing, because a trailing cause would restore the whole disclosure
     * this class removed -- the statement it could not run and the constraint that refused it -- to
     * every log record that renders the failure. The stack trace is suppressed because the frames of a
     * DIAGNOSED condition answer no question: the location is one method, this one, and the batch
     * framework logs whatever escapes a step with its trace, so a populated trace here is exactly the
     * hundred-frame wall the named code replaces.</p>
     *
     * <p>Trade-offs: a suppressed trace means a future caller that re-raised this from somewhere else
     * could not be located from the log alone. That is accepted because the condition is detected in one
     * place and logged there with the run and the step; if it ever gains a second raise site, the record
     * written beside it is what distinguishes them.</p>
     *
     * <p>Assumptions: it extends {@link RuntimeException} rather than {@link IllegalStateException},
     * which is the shape the rest of this class's refusals take. The reason is mechanical: the
     * four-argument constructor that suppresses a stack trace is exposed by {@link RuntimeException} and
     * not by its subclass, so the state-check family cannot express a stackless condition at all.</p>
     */
    public static final class StepAttemptInProgressException extends RuntimeException {

        /** The version this exception's serialised form is compatible with. */
        private static final long serialVersionUID = 1L;

        /**
         * Builds the condition for one step of one run.
         *
         * @param runId the run whose step is already being attempted; must not be {@code null}
         * @param stepName the step another attempt holds; must not be {@code null}
         */
        StepAttemptInProgressException(String runId, String stepName) {
            super(OUTCOME_CODE_STEP_IN_PROGRESS + ": " + IN_PROGRESS_REASON + "; run=" + runId
                    + " step=" + stepName + "; this attempt wrote nothing", null, false, false);
        }
    }

    /**
     * Reports that another attempt of one step of one run recorded its outcome first.
     *
     * <p>Purpose: it is the named condition the loser of the other collision order fails on -- the one
     * that had already run the step body when it found its ledger row closed. It exists for the same
     * reason as its sibling above, and it is a SEPARATE type rather than a second message on that one
     * because the two carry opposite facts about the losing attempt's work: that one wrote nothing, this
     * one may have written everything the winner did.</p>
     *
     * <p>Assumptions: it carries NO cause and NO stack trace, for the reasons its sibling states, and the
     * suppression matters more here than there. This condition is raised from inside the ledger's own
     * failure-handling path as well as its success path, and the batch framework renders whatever escapes
     * a step complete with frames -- which is exactly the 58-line wall this type replaces.</p>
     *
     * <p>Trade-offs: the losing attempt still fails, and it still fails on the hard tier, so the
     * orchestrator routes the execution to its failure path. Reporting success was considered and
     * rejected: the winner's outcome is recorded and may well be clean, but this attempt ran the same
     * body against the same data concurrently, and an execution that may have applied a night's work
     * twice is not something to report as fine. Trade-offs: an operator therefore sees a failed execution
     * for a step whose ledger row reads COMPLETED, which is the correct pair of facts and is why the code
     * and the recorded status are both logged.</p>
     */
    public static final class StepOutcomeAlreadyRecordedException extends RuntimeException {

        /** The version this exception's serialised form is compatible with. */
        private static final long serialVersionUID = 1L;

        /**
         * Builds the condition for one step of one run.
         *
         * @param runId the run whose step outcome another attempt recorded; must not be {@code null}
         * @param stepName the step whose outcome is already recorded; must not be {@code null}
         */
        StepOutcomeAlreadyRecordedException(String runId, String stepName) {
            super(OUTCOME_CODE_STEP_OUTCOME_TAKEN + ": " + OUTCOME_TAKEN_REASON + "; run=" + runId
                    + " step=" + stepName, null, false, false);
        }
    }
}
