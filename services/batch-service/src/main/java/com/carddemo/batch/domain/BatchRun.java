package com.carddemo.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Represents one orchestrated batch step in the batch-owned execution ledger.
 *
 * <p>The unique run and step pair supplies the durable idempotency key used to make a
 * redriven, already-completed step a no-op. This ledger is a documented operational
 * improvement rather than a port of baseline restart behavior:
 * {@code app/jcl/DEFGDGD.jcl:2} contains only a commented {@code RESTART=} card, and no
 * {@code CHKPT=} directive exists in the batch JCL tree.</p>
 */
@Entity
@Table(
        // WHY : Assumptions: Flyway migration V1__batch.sql is the sole DDL source of truth;
        //       Hibernate validates this existing shape and never creates or alters it.
        name = "batch_run",
        // WHY : Alternatives Considered: Relying on the JDBC search path was rejected because
        //       this module accesses batch, ledger, account and reference schemas under different
        //       grants; an explicit schema keeps the ownership boundary visible at the mapping.
        schema = "batch",
        // WHY : Assumptions: This owned table is the package's sole constraint-declaring mapping.
        //       The named pair makes an already-completed redriven step a no-op, and its name must
        //       match V1__batch.sql so schema validation and the deployed constraint stay aligned.
        uniqueConstraints = @UniqueConstraint(
                name = "uq_batch_run_run_step",
                columnNames = {"run_id", "step_name"}))
public class BatchRun {

    // WHY : Assumptions: These bounds and return-code sentinels mirror V1__batch.sql; centralizing
    //       them keeps constructor validation and mapping annotations on one authoritative value.
    private static final int RUN_ID_MAX_LENGTH = 80;
    private static final int STEP_NAME_MAX_LENGTH = 100;
    private static final int STATUS_MAX_LENGTH = 20;

    /** The attempt number a freshly opened row carries, matching the column's own DEFAULT. */
    private static final int FIRST_ATTEMPT = 1;

    private static final short RETURN_CODE_CLEAN = 0;
    private static final short RETURN_CODE_SOFT_WARNING = 4;
    private static final short RETURN_CODE_HARD_FAILURE_MINIMUM = 8;

    /**
     * Identifies the lifecycle state persisted for an orchestrated step.
     */
    public enum BatchRunStatus {

        /**
         * Indicates that the step has opened but has not published an outcome.
         */
        STARTED,

        /**
         * Indicates that the step finished with a clean or soft-warning outcome.
         */
        COMPLETED,

        /**
         * Indicates that the step ended with a hard failure or without a process return code.
         */
        FAILED
    }

    /**
     * Surrogate identity assigned by the database.
     */
    // WHY : Alternatives Considered: A composite primary key over run_id and step_name was
    //       rejected because it would couple row identity to the idempotency policy and require
    //       an embedded key type. A surrogate key leaves room for an audit model with multiple
    //       attempts while the separately named unique constraint enforces the current no-op rule.
    @Id
    // WHY : Trade-offs: Identity generation requires an immediate insert to obtain the key and
    //       therefore cannot use sequence allocation batching. A sequence would add a second
    //       Flyway-owned object name to synchronize for only a handful of rows per execution.
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /**
     * Orchestrator execution name that forms the first half of the idempotency key.
     */
    // WHY : Assumptions: The orchestrator supplies an immutable execution name bounded at 80
    //       characters. It is not the business date: app/jcl/INTCALC.jcl:22 passes one explicitly
    //       while app/jcl/POSTTRAN.jcl:23 passes none, so using a date would make a rerun collide.
    @Column(name = "run_id", length = RUN_ID_MAX_LENGTH, nullable = false, updatable = false)
    private String runId;

    /**
     * State-machine step name that forms the second half of the idempotency key.
     *
     * <p>The state vocabulary is {@code QuiesceOnlineWrites}, {@code StageSeedDatasets},
     * {@code PreflightDailyTransactions}, {@code PostTransactions},
     * {@code CalculateInterest}, {@code BackupTransactions},
     * {@code CombineTransactions}, {@code GenerateStatements}, {@code GenerateReports},
     * {@code AnalyzeTables} and {@code ResumeOnlineWrites}. This module writes rows for its
     * container jobs named {@code preflight-daily-transactions}, {@code post-transactions},
     * {@code calculate-interest}, {@code backup-transactions}, {@code combine-transactions},
     * {@code export} and {@code import}.</p>
     */
    // WHY : Alternatives Considered: A Java enum was rejected because some machine states are
    //       implemented outside this module, and adding a recordable state must not require a
    //       batch-service release merely to extend the orchestration vocabulary.
    @Column(name = "step_name", length = STEP_NAME_MAX_LENGTH, nullable = false, updatable = false)
    private String stepName;

    /**
     * Current lifecycle state of the step.
     */
    // WHY : Alternatives Considered: Persisting declaration indexes was rejected because inserting
    //       or reordering an enum constant would silently reinterpret existing rows. String values
    //       cost a few bytes and remain readable during direct database diagnosis.
    // WHY : Assumptions: In flight, completed and failed cover every stored state. A redriven
    //       completed step is detected by its existing row, so the no-op is a read rather than a
    //       separate skipped state.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = STATUS_MAX_LENGTH, nullable = false)
    private BatchRunStatus status;

    /**
     * Operational timestamp at which the attempt this row currently describes began.
     *
     * <p>A first attempt carries the instant supplied to the constructor. Each re-open through
     * {@link #reopen(LocalDateTime)} replaces it with the instant the new attempt begins, so the
     * stored value always describes the attempt named by {@link #getAttempt()} rather than the
     * moment the row was first created.</p>
     */
    // WHY : Assumptions: Callers obtain operational timestamps from an injected Clock and
    //       normalize them to the migration's microsecond precision. Business date remains a job
    //       parameter, as demonstrated by app/jcl/INTCALC.jcl:22, so reruns stay reproducible.
    // WHY : Alternatives Considered: LocalDateTime matches TimestampFormatter and the baseline
    //       record, whose PIC X(05) offset component is never copied into the emitted timestamp.
    //       Instant or OffsetDateTime would therefore manufacture zone information not preserved
    //       by the source contract.
    // WHY : Refactoring Rationale: this column is deliberately updatable, unlike run_id and
    //       step_name beside it. It was mapped updatable = false while the only writer was the
    //       constructor, and reopen() was added afterwards; the provider silently omitted the
    //       column from the UPDATE, so a redriven row durably kept the FIRST attempt's start and
    //       every later attempt was invisible in the ledger. Excluding started_at from the SET
    //       list is what let that regression exist, so the exclusion is removed rather than
    //       worked around at the caller.
    // WHY : Trade-offs: keeping the creation instant as well was rejected. An operator reading a
    //       recovered night asks when the attempt that is running now started, and answering that
    //       from a column also claiming to be the row's birth instant serves neither question.
    //       The attempt counter already carries the retry history, and a per-attempt audit trail
    //       belongs in the orchestrator's own execution history, not in a one-row-per-step ledger
    //       whose purpose is the idempotency decision.
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /**
     * Operational timestamp supplied when execution of the step reaches a terminal state.
     */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /**
     * Process return code published at a terminal outcome, or null when no code was available.
     *
     * <p>The admitted tiers are {@code 0} for a clean outcome, {@code 4} for the posting
     * soft-warning outcome established by {@code app/cbl/CBTRN02C.cbl:229-230}, and
     * {@code >= 8} for a hard failure.</p>
     */
    // WHY : Refactoring Rationale: JCL COND is a skip predicate. app/jcl/TRANBKP.jcl:51 uses
    //       COND=(4,LT), which runs only while the accumulated code is at most 4; the equivalent
    //       orchestration run predicate is rc <= 4. Collapsing 4 into either success or failure
    //       would change observable pipeline behavior.
    // WHY : Assumptions: RETURN-CODE is absent from app/cbl/CBACT04C.cbl, so interest has no
    //       soft-warning path. Interest, backup, combine, export and import record 0 when
    //       successful; only posting records either 0 or 4, while hard failure may record 8+.
    // WHY : Trade-offs: The numeric rubric belongs only to the golden-master oracle and the batch
    //       process exit status consumed by orchestration. Maven, Surefire, Failsafe and JUnit
    //       gates remain binary and must never interpret these values as graded test outcomes.
    @Column(name = "return_code")
    private Short returnCode;

    /**
     * How many times this step of this run has been attempted, counting the current attempt.
     *
     * <p>A first attempt carries {@code 1}. Each re-open through {@link #reopen(LocalDateTime)}
     * increments it, so the value is also the number of terminal outcomes this row has published
     * plus one.</p>
     */
    // WHY : Refactoring Rationale: this column is what makes a redrive of a FAILED step possible at
    //       all. uq_batch_run_run_step admits exactly one row per (run, step), and the previous
    //       design re-ran a failed step by INSERTING a second row -- documented as depending on a
    //       caller deleting the first, which no caller does and none can, because a redrive is an
    //       orchestrator action and not a SQL statement. So the second insert violated the
    //       constraint and a failed step could never be retried. Re-opening this row and counting
    //       the attempt keeps the constraint, keeps the no-op decision a single-row read, and
    //       records the retry count an operator reading a recovered night needs.
    // WHY : Assumptions: primitive int rather than Integer. The column is NOT NULL with a default
    //       of one, so absence is not representable and a boxed type would add a null state the
    //       schema forbids.
    @Column(name = "attempt", nullable = false)
    private int attempt;

    // WHY : Assumptions: The ledger records immutable identity, so id, runId and stepName are
    //       written once by the constructor and are mapped updatable = false; rewriting any of
    //       them would repoint the row at a different step of a different run and destroy its
    //       audit meaning. No field on this entity exposes a setter: startedAt is mutable state,
    //       but only the named reopen transition may rewrite it, and only to the start of the
    //       attempt it is opening.

    /**
     * Creates an uninitialized persistence shell for provider hydration.
     */
    // WHY : Assumptions: The persistence provider requires a non-private no-argument constructor,
    //       while protected visibility prevents application code from creating a half-built row.
    //       Placing @Id on a field selects field access, so hydration does not require setters.
    protected BatchRun() {
        // WHY : Assumptions: The provider populates every mapped field after reflective creation.
    }

    /**
     * Opens a started ledger row for an orchestrated step.
     *
     * @param runId the non-blank String execution name, at most 80 characters
     * @param stepName the non-blank String state-machine step name, at most 100 characters
     * @param startedAt the non-null LocalDateTime at which step execution began
     * @throws NullPointerException if any required argument is null
     * @throws IllegalArgumentException if either String is blank or exceeds its mapped width
     */
    // WHY : Trade-offs: An all-arguments constructor was rejected because it could create a row
    //       claiming completion before work ran. Named transitions preserve the migration's
    //       lifecycle constraint while keeping immutable identity fields constructor-assigned.
    public BatchRun(String runId, String stepName, LocalDateTime startedAt) {
        String checkedRunId = Objects.requireNonNull(runId, "runId must not be null");
        String checkedStepName = Objects.requireNonNull(stepName, "stepName must not be null");
        LocalDateTime checkedStartedAt =
                Objects.requireNonNull(startedAt, "startedAt must not be null");

        // WHY : Assumptions: Blank values cannot identify an external run or step, and checking
        //       mapped widths here reports a contract error before a database flush obscures it.
        if (checkedRunId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (checkedRunId.length() > RUN_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("runId exceeds the mapped width");
        }
        if (checkedStepName.isBlank()) {
            throw new IllegalArgumentException("stepName must not be blank");
        }
        if (checkedStepName.length() > STEP_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("stepName exceeds the mapped width");
        }

        this.runId = checkedRunId;
        this.stepName = checkedStepName;
        this.status = BatchRunStatus.STARTED;
        this.startedAt = checkedStartedAt;
        // WHY : Assumptions: a row exists only once an attempt has begun, so the first attempt is
        //       one and never zero. The column's own DEFAULT says the same thing for a row inserted
        //       by anything other than the provider; both are stated so neither is the only place
        //       the base value lives.
        this.attempt = FIRST_ATTEMPT;
    }

    /**
     * Re-opens a terminal or abandoned row for another attempt, counting it.
     *
     * <p>The supplied instant becomes the row's durable start time, replacing the previous
     * attempt's, so a caller reading the row back sees when the attempt it now describes began.
     * Both nullable outcome columns are cleared and the attempt counter is incremented.</p>
     *
     * @param startedAt the non-null LocalDateTime at which the new attempt begins
     * @throws NullPointerException if startedAt is null
     * @throws IllegalStateException if this row is already COMPLETED, because a completed step is a
     *     redrive no-op and re-opening it would let its writes be applied twice
     */
    // WHY : Refactoring Rationale: this transition exists so that a redrive rewrites the attempt in
    //       place rather than inserting a second row the uniqueness constraint refuses. It clears
    //       BOTH nullable outcome columns, because ck_batch_run_lifecycle admits a STARTED row only
    //       with a null end time AND a null exit status -- clearing one and not the other stores
    //       nothing and fails the constraint at flush, which is a failure inside the recovery path.
    // WHY : Assumptions: a COMPLETED row is refused rather than re-opened, and that asymmetry is the
    //       whole idempotency guarantee. A completed step's writes are committed, so re-running it
    //       would double them -- for the posting step that means posting twice. A FAILED row's
    //       writes rolled back with it, and a STARTED row belongs to an attempt whose container died
    //       without publishing an outcome; re-running either is exactly what a redrive is for.
    public void reopen(LocalDateTime startedAt) {
        LocalDateTime checkedStartedAt =
                Objects.requireNonNull(startedAt, "startedAt must not be null");
        if (status == BatchRunStatus.COMPLETED) {
            throw new IllegalStateException("A completed row must not be re-opened");
        }

        this.status = BatchRunStatus.STARTED;
        this.startedAt = checkedStartedAt;
        this.finishedAt = null;
        this.returnCode = null;
        this.attempt = this.attempt + 1;
    }

    /**
     * Transitions a started step to a completed clean or soft-warning outcome.
     *
     * @param finishedAt the non-null LocalDateTime at which step execution finished
     * @param returnCode the primitive short process code, restricted to 0 or 4
     * @throws NullPointerException if finishedAt is null
     * @throws IllegalArgumentException if finishedAt precedes startedAt or returnCode is not 0 or 4
     * @throws IllegalStateException if this row is not in a valid started state
     */
    // WHY : Alternatives Considered: Independent setters for status, finish time and return code
    //       were rejected because callers could expose an intermediate combination forbidden by
    //       ck_batch_run_lifecycle. One named transition validates and publishes the tuple.
    public void markCompleted(LocalDateTime finishedAt, short returnCode) {
        // WHY : Assumptions: Redrive no-op handling reads an existing completed row before this
        //       method is called, so only an in-flight row may publish a new terminal outcome.
        if (status != BatchRunStatus.STARTED || startedAt == null) {
            throw new IllegalStateException("Only a valid started row can be completed");
        }

        LocalDateTime checkedFinishedAt =
                Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        if (checkedFinishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt must not precede startedAt");
        }
        if (returnCode != RETURN_CODE_CLEAN && returnCode != RETURN_CODE_SOFT_WARNING) {
            throw new IllegalArgumentException("A completed row requires return code 0 or 4");
        }

        this.finishedAt = checkedFinishedAt;
        this.returnCode = returnCode;
        this.status = BatchRunStatus.COMPLETED;
    }

    /**
     * Transitions a started step to a failed outcome.
     *
     * @param finishedAt the non-null LocalDateTime at which step execution failed
     * @param returnCode the nullable Short process code, which must be at least 8 when present
     * @throws NullPointerException if finishedAt is null
     * @throws IllegalArgumentException if finishedAt precedes startedAt or a present code is below 8
     * @throws IllegalStateException if this row is not in a valid started state
     */
    // WHY : Alternatives Considered: Independent setters were rejected for the same lifecycle
    //       integrity reason as completion. A nullable code is retained because a platform-level
    //       termination can prevent the process from publishing any numeric exit status.
    public void markFailed(LocalDateTime finishedAt, Short returnCode) {
        // WHY : Assumptions: A failed outcome also consumes an in-flight row exactly once; retry
        //       orchestration creates or reads its own ledger decision rather than rewriting one.
        if (status != BatchRunStatus.STARTED || startedAt == null) {
            throw new IllegalStateException("Only a valid started row can be failed");
        }

        LocalDateTime checkedFinishedAt =
                Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        if (checkedFinishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt must not precede startedAt");
        }
        if (returnCode != null && returnCode < RETURN_CODE_HARD_FAILURE_MINIMUM) {
            throw new IllegalArgumentException("A failed row requires no code or a code of at least 8");
        }

        this.finishedAt = checkedFinishedAt;
        this.returnCode = returnCode;
        this.status = BatchRunStatus.FAILED;
    }

    /**
     * Returns how many times this step of this run has been attempted.
     *
     * @return the primitive int attempt count, at least 1
     */
    public int getAttempt() {
        return attempt;
    }

    /**
     * Returns the nullable database identity.
     *
     * @return the generated Long identity, or null before persistence
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns the immutable execution name.
     *
     * @return the non-null String execution name
     */
    public String getRunId() {
        return runId;
    }

    /**
     * Returns the immutable state-machine step name.
     *
     * @return the non-null String step name
     */
    public String getStepName() {
        return stepName;
    }

    /**
     * Returns the stored lifecycle state.
     *
     * @return the non-null BatchRunStatus state
     */
    public BatchRunStatus getStatus() {
        return status;
    }

    /**
     * Returns the operational start time of the attempt this row currently describes.
     *
     * @return the non-null LocalDateTime start time of the current attempt
     */
    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    /**
     * Returns the terminal time when available.
     *
     * @return the nullable LocalDateTime finish time
     */
    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    /**
     * Returns the process outcome when available.
     *
     * @return the nullable Short process return code
     */
    public Short getReturnCode() {
        return returnCode;
    }

    /**
     * Compares rows by the immutable idempotency key enforced by the database.
     *
     * @param other the Object to compare with this row
     * @return true when other is a BatchRun with the same runId and stepName; otherwise false
     */
    @Override
    public boolean equals(Object other) {
        // WHY : Alternatives Considered: Generated-identity equality was rejected because new
        //       rows have null ids and could collapse in a HashSet. The immutable business pair is
        //       exactly the key enforced by uq_batch_run_run_step, so memory and database agree.
        if (this == other) {
            return true;
        }
        if (!(other instanceof BatchRun that)) {
            return false;
        }
        // WHY : Assumptions: Public construction always supplies both key parts; null identifies a
        //       provider shell before hydration, and two such shells must not compare as one row.
        if (runId == null || stepName == null) {
            return false;
        }
        return runId.equals(that.getRunId()) && stepName.equals(that.getStepName());
    }

    /**
     * Computes a stable hash from the immutable idempotency key.
     *
     * @return the int hash of runId and stepName
     */
    @Override
    public int hashCode() {
        // WHY : Trade-offs: Mutable lifecycle fields are excluded so transitions cannot make an
        //       instance unreachable in a hashed collection; the pair cannot change after creation.
        return Objects.hash(runId, stepName);
    }

    /**
     * Renders the identifiers and current outcome for operational diagnosis.
     *
     * @return a String containing id, runId, stepName, status and returnCode
     */
    @Override
    public String toString() {
        // WHY : Trade-offs: This form aids diagnosis but is not a serialization contract, so no
        //       caller may parse it or depend on field order.
        return "BatchRun{"
                + "id=" + id
                + ", runId='" + runId + '\''
                + ", stepName='" + stepName + '\''
                + ", status=" + status
                + ", returnCode=" + returnCode
                + ", attempt=" + attempt
                + '}';
    }
}
