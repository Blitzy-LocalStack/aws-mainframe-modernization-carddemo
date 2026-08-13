package com.carddemo.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of {@code auth.identity_sync_task}: a change this context still owes the managed user pool.
 *
 * <p>Purpose: carry, durably and transactionally with the {@code auth.users} change that caused it, the
 * intention to provision, re-project or withdraw a managed identity. A row is written inside the same
 * database transaction as the user-row change and applied after that transaction commits, which is what
 * lets the two stores converge without either being held open across a call to the other.</p>
 *
 * <p>Refactoring Rationale: this entity exists because the three write operations previously called the
 * identity provider from inside their database transaction and described the arrangement as though it
 * made the two stores atomic. It cannot. A provider call that succeeds followed by a commit that fails
 * leaves a pool account with no local row -- for an administrator, signed authority the local store does
 * not record -- and the update path could leave a token minting authorities {@code auth.users} no longer
 * describes. Only a row committed with the data survives a process death between the two stores: after
 * any crash the intended change is either recorded and pending, or absent because the data change never
 * happened. The physical contract, including why the attempt counter is four bytes and why the pending
 * index is partial, is stated in {@code V2__auth_identity_sync.sql}, which owns it.</p>
 *
 * <p>Assumptions: the PROJECTION the provider is to hold is carried on the task rather than re-read when
 * the task is applied, and the reason is not caching. A withdrawal's row is gone by then; and a
 * projection task applied after a later change would otherwise push the LATER values under the EARLIER
 * task's intention, silently reordering two changes an operator asked for in a definite order.</p>
 *
 * <p>Assumptions: this type carries no reference to the identity provider, to any AWS type or to any web
 * type, which is the layering the context's architecture test enforces. It is a mapping from a Java
 * object onto a physical row; deciding what a row MEANS and calling the provider belong to the service
 * layer.</p>
 *
 * <p>Trade-offs: the status transitions are expressed as methods on this type rather than as a state
 * machine held elsewhere, so an invalid transition is refused at the object that owns the field. The cost
 * is the transitions declared below -- applied, abandoned, and the attempt counter that abandons at its
 * ceiling -- plus the pending predicate all three share; the gain is that no caller can mark a task
 * applied twice or revive a settled one, which is the property the idempotent applier depends on.</p>
 */
@Entity
@Table(name = "identity_sync_task", schema = "auth")
public class IdentitySyncTask {

    /**
     * The status of an intention recorded before the act it compensates, and therefore not yet owed.
     *
     * <p>⚠️ Purpose: this is the CREATE path's provisioning guard, and it exists because that path
     * cannot record its intention in the transaction that carries its data. {@code auth.users.cognito_sub}
     * is not nullable and only the provider mints the subject, so the account must exist before the row
     * can be written -- which used to mean nothing was recorded until the create had already failed, and
     * a process death between the provider call and the insert left a pool account that can authenticate,
     * holds a group, has no row here, and was recorded nowhere at all.</p>
     *
     * <p>⚠️ Assumptions: a task in this state is deliberately INVISIBLE to both drains. Between the
     * guard's commit and the insert's commit the intention is durable and not yet owed, so a
     * reconciliation pass that applied it would withdraw the pool account of a create still in flight.
     * The two paths that know the outcome move it on -- to {@link #STATUS_CANCELLED} when the row was
     * written or the provider refused the username, and to {@link #STATUS_PENDING} when the create failed
     * after the account may already exist. Alternatives Considered: leaving the guard pending and having
     * the pass skip rows younger than a grace period; rejected because a grace period is a guess about how
     * long a create can take, whereas a state the pass does not select cannot be raced at all.</p>
     */
    public static final String STATUS_CLAIMED = "CLAIMED";

    /** The status of a task still owed to the provider. */
    public static final String STATUS_PENDING = "PENDING";

    /** The status of a task the provider confirmed. */
    public static final String STATUS_APPLIED = "APPLIED";

    /**
     * The status of an intention closed without a provider call, because it turned out not to be owed.
     *
     * <p>⚠️ Assumptions: this is distinct from {@link #STATUS_APPLIED} and the distinction is the reason
     * it exists. APPLIED means the provider confirmed the change; a guard whose create completed, or whose
     * provider call was refused because the username was taken, had no provider call at all. Recording
     * either as APPLIED would put a false event in the one table an operator reads to answer whether a
     * pool account was withdrawn.</p>
     */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** The status of a task that reached the attempt ceiling and awaits an operator. */
    public static final String STATUS_ABANDONED = "ABANDONED";

    /**
     * The operation that brings a pool account's projection and group in line with the row.
     *
     * <p>Refactoring Rationale: a third constant, {@code OPERATION_PROVISION}, stood beside these two and
     * has been withdrawn together with the value the column's own constraint admitted, by
     * {@code V3__auth_identity_sync_operations.sql}. No write path could record it: {@code cognito_sub}
     * is not nullable and the subject it holds is minted when the pool account is created, so the create
     * path has to provision BEFORE it can write the row and therefore cannot record an intention first.
     * What the constant cost was a branch on the applier that no deployment could reach and a column
     * comment naming a verb an operator could not produce.</p>
     */
    public static final String OPERATION_SYNCHRONISE = "SYNCHRONISE";

    /** The operation that removes a pool account. */
    public static final String OPERATION_WITHDRAW = "WITHDRAW";

    /**
     * The surrogate key, generated by the database.
     *
     * <p>Assumptions: {@code IDENTITY} and not a table or sequence generator, matching the
     * {@code GENERATED BY DEFAULT AS IDENTITY} column the migration declares and the idiom the sibling
     * outbox in the authorization context already uses. It is also the fair-selection order the applier
     * drains in, so one user's repeated failures cannot starve another user's first task.</p>
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    /**
     * The user this task concerns.
     *
     * <p>Assumptions: eight characters, matching {@code SEC-USR-ID PIC X(08)} at line 18 of
     * {@code app/cpy/CSUSR01Y.cpy} and the {@code CHAR(8)} primary key of {@code auth.users}, so the two
     * compare without an implicit cast.</p>
     */
    // WHY : Assumptions: the three fixed-width columns below carry an explicit JDBC type code, because the
    //       provider maps a String to VARCHAR by default while V2__auth_identity_sync.sql declares CHAR --
    //       and the mismatch is not cosmetic. Schema validation is enabled in every profile, so it refuses
    //       to start against a CHAR column mapped as VARCHAR; the User entity states the same code over the
    //       same two physical types for the same reason. Alternatives Considered: widening the columns to
    //       VARCHAR so no annotation were needed. Rejected because the widths are the baseline record's own
    //       -- SEC-USR-ID is X(08) and the type is one character -- and a variable-width column would stop
    //       the database enforcing them.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 8, nullable = false, updatable = false)
    private String userId;

    /** Which of the three provider verbs this task expresses. */
    @Column(name = "operation", length = 12, nullable = false, updatable = false)
    private String operation;

    /** The given name the provider is to hold, or {@code null} for a withdrawal. */
    @Column(name = "first_name", length = 20, updatable = false)
    private String firstName;

    /** The family name the provider is to hold, or {@code null} for a withdrawal. */
    @Column(name = "last_name", length = 20, updatable = false)
    private String lastName;

    /**
     * The user type the provider currently holds, or {@code null} when there is nothing to leave.
     *
     * <p>Assumptions: this is carried because a group reassignment is a PAIR of provider operations --
     * leave the old group, join the new -- and the applier cannot derive the old value after the row has
     * already been written with the new one.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "previous_user_type", length = 1, updatable = false)
    private String previousUserType;

    /** The user type the provider is to hold, or {@code null} for a withdrawal. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_type", length = 1, updatable = false)
    private String userType;

    /** Whether the task is still owed, was confirmed, or was abandoned. */
    @Column(name = "status", length = 10, nullable = false)
    private String status;

    /** How many times the provider call has been attempted. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** When the task was recorded. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** When the task reached a terminal status, or {@code null} while it is pending. */
    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    /** The stable code of the most recent failure, or {@code null} if none has occurred. */
    @Column(name = "last_failure_code", length = 64)
    private String lastFailureCode;

    /**
     * Required by the persistence provider, which instantiates an entity reflectively before populating
     * it.
     *
     * <p>Assumptions: {@code protected} rather than public, so application code cannot build a task in a
     * state no factory would produce -- a task with no operation and no user, which the columns declare
     * not null and which would fail only at flush.</p>
     */
    protected IdentitySyncTask() {
        // Populated by the persistence provider.
    }

    /**
     * Records one intended change against one user.
     *
     * @param userId the user the change concerns; must not be {@code null}
     * @param operation one of {@link #OPERATION_SYNCHRONISE} or
     *     {@link #OPERATION_WITHDRAW}; must not be {@code null}
     * @param firstName the given name the provider is to hold, or {@code null} for a withdrawal
     * @param lastName the family name the provider is to hold, or {@code null} for a withdrawal
     * @param previousUserType the user type the provider currently holds, or {@code null} when there is
     *     no group to leave
     * @param userType the user type the provider is to hold, or {@code null} for a withdrawal
     * @param recordedAt the instant the task was recorded; must not be {@code null}
     * @throws NullPointerException if {@code userId}, {@code operation} or {@code recordedAt} is
     *     {@code null}
     */
    public IdentitySyncTask(String userId, String operation, String firstName, String lastName,
            String previousUserType, String userType, LocalDateTime recordedAt) {
        this(userId, operation, firstName, lastName, previousUserType, userType, recordedAt,
                STATUS_PENDING);
    }

    /**
     * Records one intended change in a stated initial state.
     *
     * <p>⚠️ Assumptions: the initial state is a PARAMETER on this constructor and defaults to pending on
     * the one above, because the create path's guard has to be durable BEFORE it is owed. Two states are
     * admitted here and no others: {@link #STATUS_PENDING} for an intention recorded alongside a committed
     * change, and {@link #STATUS_CLAIMED} for one recorded ahead of the act it compensates. Admitting a
     * terminal state would let a caller insert a settled row that no applier ever acted on, which is the
     * one thing this ledger must not contain.</p>
     *
     * @param userId the user the change concerns; must not be {@code null}
     * @param operation the provider verb owed; must not be {@code null}
     * @param firstName the given name the provider is to hold, or {@code null} for a withdrawal
     * @param lastName the family name the provider is to hold, or {@code null} for a withdrawal
     * @param previousUserType the user type the provider currently holds, or {@code null} when there is no
     *     group to leave
     * @param userType the user type the provider is to hold, or {@code null} for a withdrawal
     * @param recordedAt the instant the intention was recorded; must not be {@code null}
     * @param initialStatus either {@link #STATUS_PENDING} or {@link #STATUS_CLAIMED}
     * @throws NullPointerException if {@code userId}, {@code operation}, {@code recordedAt} or
     *     {@code initialStatus} is {@code null}
     * @throws IllegalArgumentException if {@code initialStatus} is neither pending nor claimed
     */
    public IdentitySyncTask(String userId, String operation, String firstName, String lastName,
            String previousUserType, String userType, LocalDateTime recordedAt,
            String initialStatus) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
        this.firstName = firstName;
        this.lastName = lastName;
        this.previousUserType = previousUserType;
        this.userType = userType;
        Objects.requireNonNull(initialStatus, "initialStatus must not be null");
        if (!STATUS_PENDING.equals(initialStatus) && !STATUS_CLAIMED.equals(initialStatus)) {
            throw new IllegalArgumentException("initialStatus must be " + STATUS_PENDING + " or "
                    + STATUS_CLAIMED + ", but was " + initialStatus
                    + "; a task inserted in a terminal state would record an outcome no applier reached");
        }
        this.status = initialStatus;
        this.attempts = 0;
        this.createdAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
    }

    /**
     * Returns the surrogate key, or {@code null} before the row is inserted.
     *
     * @return the task identifier, or {@code null} while the task is unsaved
     */
    public Long getTaskId() {
        return this.taskId;
    }

    /**
     * Returns the user this task concerns.
     *
     * @return the eight-character identifier, never {@code null}
     */
    public String getUserId() {
        return this.userId;
    }

    /**
     * Returns which provider verb this task expresses.
     *
     * @return one of the three operation constants, never {@code null}
     */
    public String getOperation() {
        return this.operation;
    }

    /**
     * Returns the given name the provider is to hold.
     *
     * @return the given name, or {@code null} for a withdrawal
     */
    public String getFirstName() {
        return this.firstName;
    }

    /**
     * Returns the family name the provider is to hold.
     *
     * @return the family name, or {@code null} for a withdrawal
     */
    public String getLastName() {
        return this.lastName;
    }

    /**
     * Returns the user type the provider currently holds.
     *
     * @return the previous user type, or {@code null} when there is no group to leave
     */
    public String getPreviousUserType() {
        return this.previousUserType;
    }

    /**
     * Returns the user type the provider is to hold.
     *
     * @return the intended user type, or {@code null} for a withdrawal
     */
    public String getUserType() {
        return this.userType;
    }

    /**
     * Returns whether the task is pending, applied or abandoned.
     *
     * @return one of the three status constants, never {@code null}
     */
    public String getStatus() {
        return this.status;
    }

    /**
     * Returns how many times the provider call has been attempted.
     *
     * @return the attempt count, never negative
     */
    public int getAttempts() {
        return this.attempts;
    }

    /**
     * Returns when the task was recorded.
     *
     * @return the recording instant, never {@code null} once constructed
     */
    public LocalDateTime getCreatedAt() {
        return this.createdAt;
    }

    /**
     * Returns when the task reached a terminal status.
     *
     * @return the settling instant, or {@code null} while the task is pending
     */
    public LocalDateTime getSettledAt() {
        return this.settledAt;
    }

    /**
     * Returns the stable code of the most recent failure.
     *
     * @return the failure code, or {@code null} if no attempt has failed
     */
    public String getLastFailureCode() {
        return this.lastFailureCode;
    }

    /**
     * Reports whether this task is still owed to the provider.
     *
     * @return {@code true} while the status is pending
     */
    public boolean isPending() {
        return STATUS_PENDING.equals(this.status);
    }

    /**
     * Reports whether this task is a recorded intention that is not yet owed.
     *
     * @return {@code true} while the status is claimed
     */
    public boolean isClaimed() {
        return STATUS_CLAIMED.equals(this.status);
    }

    /**
     * Moves a claimed guard into the owed set, so that the drains will apply it.
     *
     * <p>⚠️ Purpose: the create path calls this when its own outcome is AMBIGUOUS -- the provider call
     * failed with a fault rather than a refusal, so the pool account may or may not have been created, or
     * the row write failed after the account certainly was. In both cases a withdrawal is genuinely owed:
     * the provider's withdrawal is idempotent, so applying it against an account that was never created
     * is a no-op, while leaving it claimed would strand an account nothing can reach.</p>
     *
     * <p>Assumptions: refused on a task that is not claimed, so the transition cannot revive a settled
     * guard or re-owe one the create already closed. The instant is not recorded, because nothing has been
     * settled -- the row is entering the state the drains read, not leaving it.</p>
     *
     * @throws IllegalStateException if the task is not claimed
     */
    public void markOwed() {
        if (!isClaimed()) {
            throw new IllegalStateException("identity sync task " + this.taskId + " is " + this.status
                    + " and cannot be marked owed; only a claimed guard enters the owed set, so that a"
                    + " settled task cannot be revived");
        }
        this.status = STATUS_PENDING;
    }

    /**
     * Closes an intention that turned out not to be owed, without recording a provider call.
     *
     * <p>⚠️ Purpose: the create path calls this on the two outcomes where nothing needs withdrawing --
     * the row was written, so the pool account is legitimately owned by a row; or the provider REFUSED the
     * username, so this attempt created nothing. Marking either applied would record a provider call that
     * did not happen, in the one table an operator reads to answer whether an account was withdrawn.</p>
     *
     * <p>⚠️ Assumptions: accepted from the claimed state AND from pending, and refused from either
     * terminal state. Two parties can discover that an intention is not owed, and both need to say so
     * without claiming a provider call. The path that RECORDED a guard closes it when its create completed
     * or the provider refused the username; the APPLIER closes a pending withdrawal when a row for that
     * identifier exists, because a withdrawal is owed only against a pool account no row owns. Refusing the
     * second would force the applier to record it as APPLIED, which is the false event this state exists to
     * avoid.</p>
     *
     * @param settledAt the instant the intention was closed; must not be {@code null}
     * @throws NullPointerException if {@code settledAt} is {@code null}
     * @throws IllegalStateException if the task is already in a terminal state
     */
    public void markCancelled(LocalDateTime settledAt) {
        if (!isClaimed() && !isPending()) {
            throw new IllegalStateException("identity sync task " + this.taskId + " is " + this.status
                    + " and cannot be cancelled; a settled task is terminal so that a second applier"
                    + " cannot revive it");
        }
        this.status = STATUS_CANCELLED;
        this.settledAt = Objects.requireNonNull(settledAt, "settledAt must not be null");
        this.lastFailureCode = null;
    }

    /**
     * Marks the task confirmed by the provider.
     *
     * <p>Assumptions: this is refused on a task that is not pending, which is what makes double
     * application detectable rather than silent. The applier reads a task, calls the provider and marks
     * it; if two appliers overlap, the second one's mark fails here rather than overwriting a settled
     * row's timestamp with a later one.</p>
     *
     * @param settledAt the instant the provider confirmed the change; must not be {@code null}
     * @throws NullPointerException if {@code settledAt} is {@code null}
     * @throws IllegalStateException if the task is not pending
     */
    public void markApplied(LocalDateTime settledAt) {
        requirePending("applied");
        this.status = STATUS_APPLIED;
        this.settledAt = Objects.requireNonNull(settledAt, "settledAt must not be null");
        this.lastFailureCode = null;
    }

    /**
     * Marks the task no longer owed because the condition it was recorded against did not arise.
     *
     * <p>Purpose: a compensating withdrawal is armed BEFORE the pool account it would undo is
     * provisioned, so that a process death between provisioning and the insert leaves the withdrawal
     * durably owed. When the create instead SUCCEEDS -- or when the pool refuses to create the account at
     * all -- that withdrawal must stop being owed, and this is the transition that stops it. Without it
     * the applier would withdraw the account of a user that was created perfectly well.</p>
     *
     * <p>Refactoring Rationale: the status is the existing {@code ABANDONED} one rather than a fourth
     * value, because "will not be applied" is exactly what that status already means: the ceiling arm
     * below reaches it for a task whose provider call can never succeed, and an armed compensation whose
     * condition never arose is likewise a task that will never be applied. A distinct value would have to
     * be added to the column's own check constraint, to the applier's reader and to every operator query
     * that partitions the ledger, and it would tell an operator nothing the recorded reason does not.</p>
     *
     * <p>Assumptions: the reason is recorded in the failure-code column even though no failure occurred,
     * and the alternative -- leaving it null -- was rejected because an abandoned row with no reason is
     * indistinguishable from one abandoned at the ceiling by an operator reading the table. The column is
     * a STABLE CODE rather than a message everywhere else in this ledger, so the values passed here are
     * codes too.</p>
     *
     * <p>Assumptions: it is refused on a task that is not pending, exactly as the two transitions beside
     * it are, so an applied withdrawal cannot be rewritten as an abandoned one by a later caller.</p>
     *
     * @param reason the stable code recording why the task stopped being owed; must not be {@code null}
     * @param settledAt the instant the task stopped being owed; must not be {@code null}
     * @throws NullPointerException if {@code reason} or {@code settledAt} is {@code null}
     * @throws IllegalStateException if the task is not pending
     */
    public void markAbandoned(String reason, LocalDateTime settledAt) {
        requirePending("abandoned");
        this.lastFailureCode = Objects.requireNonNull(reason, "reason must not be null");
        this.settledAt = Objects.requireNonNull(settledAt, "settledAt must not be null");
        this.status = STATUS_ABANDONED;
    }

    /**
     * Records a failed attempt, abandoning the task once the ceiling is reached.
     *
     * <p>Refactoring Rationale: the counter is incremented exactly ONCE per attempt and the ceiling is
     * applied in the same call, so a task whose provider call can never succeed stops being retried and
     * stops hiding the tasks queued behind it. A counter incremented in two places, or a ceiling checked
     * somewhere else, is how an attempt limit turns into an unbounded retry.</p>
     *
     * @param failureCode the stable code of the fault, derived from its type and never from its message;
     *     must not be {@code null}
     * @param ceiling the number of attempts after which the task is abandoned; must be positive
     * @param observedAt the instant of the attempt, recorded only when the task is abandoned; must not be
     *     {@code null}
     * @throws NullPointerException if {@code failureCode} or {@code observedAt} is {@code null}
     * @throws IllegalArgumentException if {@code ceiling} is not positive
     * @throws IllegalStateException if the task is not pending
     */
    public void recordFailedAttempt(String failureCode, int ceiling, LocalDateTime observedAt) {
        requirePending("failed");
        Objects.requireNonNull(failureCode, "failureCode must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        if (ceiling <= 0) {
            throw new IllegalArgumentException(
                    "ceiling must be positive; a non-positive ceiling abandons every task on its first"
                            + " attempt and would make the ledger unable to retry anything");
        }
        this.attempts = this.attempts + 1;
        this.lastFailureCode = failureCode;
        if (this.attempts >= ceiling) {
            this.status = STATUS_ABANDONED;
            this.settledAt = observedAt;
        }
    }

    /**
     * Refuses a transition on a task that has already been settled.
     *
     * @param transition the transition being attempted, reproduced in the refusal so it names which one
     *     failed
     * @throws IllegalStateException if the task is not pending
     */
    private void requirePending(String transition) {
        if (!isPending()) {
            throw new IllegalStateException("identity sync task " + this.taskId + " is " + this.status
                    + " and cannot be marked " + transition
                    + "; a settled task is terminal so that a second applier cannot revive it");
        }
    }
}
