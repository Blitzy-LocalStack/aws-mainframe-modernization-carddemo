package com.carddemo.auth.service;

import com.carddemo.auth.domain.IdentitySyncTask;
import com.carddemo.auth.repository.IdentitySyncTaskRepository;
import com.carddemo.common.observability.ThrowableDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Records and applies the changes this context owes the managed user pool.
 *
 * <p>Purpose: this is the state machine behind {@code auth.identity_sync_task}. A write path calls
 * {@link #record} inside its own database transaction, so the intention commits with the data; it then
 * calls {@link #applyOwed} once that transaction has committed, so the provider call happens with no
 * database transaction and no row lock held. A reconciliation pass calls {@link #reconcile} to pick up
 * anything a process death left behind.</p>
 *
 * <p>Refactoring Rationale: this class exists because the three user write operations previously called
 * the provider from INSIDE their database transaction and documented that as making the two stores
 * atomic. It cannot. Update reassigned a Cognito group and then committed, so a commit failure left a
 * token minting authorities {@code auth.users} no longer described; create provisioned an identity before
 * an unflushed insert, so a deferred failure bypassed the compensating withdrawal entirely and left an
 * administrator identity with no local row; delete removed an identity while holding a transaction open
 * across network latency. Splitting the two stores into a committed intention plus an idempotent
 * post-commit application is the only construction that survives a process death between them.</p>
 *
 * <p>Assumptions: application is IDEMPOTENT by construction and not by convention, which is what makes a
 * retry safe, and it holds for both of the operations a task can carry. A projection assignment ASSIGNS
 * three attributes and, when the type moved, moves one group membership -- adding a member that is
 * present and removing one that is absent both succeed at the provider -- so applying it twice reaches
 * the state it reached the first time and accumulates nothing. Withdrawal treats an absent account and
 * an absent credential entry as success. The terminal status transition is refused by the entity on a
 * task that is already settled, so two overlapping appliers cannot both mark one task.</p>
 *
 * <p>Refactoring Rationale: this paragraph asserted a third property, that "provisioning tolerates an
 * account that already exists and re-reads its subject rather than failing", and that was not true of any
 * code: the provisioner issues no {@code AdminGetUser}, and the provider's duplicate-username condition
 * propagates untranslated. The claim was also about an operation this ledger cannot carry -- account
 * creation is not among the two verbs a task admits, for the reason
 * {@code V3__auth_identity_sync_operations.sql} records -- so it described a path that does not reach
 * here. Stating an idempotence the code did not have is the more damaging half: a reader would have
 * concluded a duplicated create was safe to retry.</p>
 *
 * <p>Assumptions: applying one task is THREE short units of work rather than one -- read the pending row,
 * call the provider with no transaction open, then settle the row -- and no single unit spans the provider
 * call. That is the property this whole class exists to establish, so it would be self-defeating to
 * re-introduce it one layer down: an applier that held a transaction across the provider would have moved
 * the defect from the write path into the ledger rather than removing it.</p>
 *
 * <p>Trade-offs: three units cost two extra round trips per task against one, and they admit a duplicate
 * provider call -- a process death between the call and the settle, or two appliers racing, issues the
 * call twice. That is affordable precisely because every one of the three provider operations is
 * idempotent, which is stated above as a construction rather than a hope. What it buys is that no database
 * transaction is ever open while this service waits on the provider, so a slow or hanging provider cannot
 * consume the connection pool or hold a row against the reconciliation pass. The authorization context's
 * outbox reaches the same conclusion by the same reasoning.</p>
 *
 * <p>Alternatives Considered: one transaction per task, spanning the provider call, declared
 * {@link Propagation#REQUIRES_NEW}. Rejected on the reasoning above. Also considered and rejected: an
 * intermediate CLAIMED status, so a task in flight is visibly distinct from one not yet started. It would
 * make the duplicate call impossible for concurrent appliers but not for a process death, which is the
 * case that actually needs the idempotence, and it would add a status the table's check constraint and
 * every reader of it would have to carry for no further guarantee.</p>
 *
 * <p>Refactoring Rationale: the transaction boundary is a {@code TransactionTemplate} and not an
 * annotation, and here that is load bearing rather than stylistic. Both settle paths are reached from a
 * private method of this same class, and a self-invocation does not pass through the transactional proxy,
 * so an annotated method would have run with NO transaction at all while appearing to declare one -- the
 * defect the reference-service disclosure path was reported for. A template needs no proxy.</p>
 */
@Service
public class IdentitySyncService {

    /** The logger this class reports task outcomes on. */
    private static final Logger LOG = LoggerFactory.getLogger(IdentitySyncService.class);

    /**
     * How many times one task is attempted before it is abandoned.
     *
     * <p>Assumptions: five, matching the receive ceiling the migration's dead-letter policy uses
     * everywhere else, so an operator learns one number rather than one per subsystem. It is a ceiling and
     * not a target: a provider fault that is going to clear does so on the first retry, and the remaining
     * attempts exist for the case where it clears minutes later.</p>
     */
    static final int ATTEMPT_CEILING = 5;

    /**
     * How many tasks one per-user drain applies.
     *
     * <p>Assumptions: three is above the number a single request can owe -- a write path records exactly
     * one -- and leaves room for tasks an earlier request left pending for the same user, which is the
     * case worth serving here because those tasks concern the row the caller just changed. A larger bound
     * would make one caller's latency depend on an unrelated backlog, which is what {@link #reconcile}
     * is for.</p>
     */
    static final int OWED_DRAIN_LIMIT = 3;

    /**
     * How many tasks one scheduled reconciliation pass applies.
     *
     * <p>Assumptions: fifty, which is large enough to clear a backlog accumulated over several minutes of
     * provider unavailability within a few passes and small enough that one pass cannot occupy the
     * scheduler thread indefinitely. Trade-offs: a larger bound clears a backlog in fewer passes and makes
     * each pass longer; the pass is idempotent and re-run on a fixed delay, so a smaller bound costs only
     * elapsed time, while a larger one costs a longer period during which nothing can stop it.</p>
     */
    static final int RECONCILE_PASS_LIMIT = 50;

    /** The ledger this class records into and drains from. */
    private final IdentitySyncTaskRepository tasks;

    /** The provider port every application calls. */
    private final CognitoUserProvisioningService provisioning;

    /**
     * The clock every recorded and settled instant is read from.
     *
     * <p>Assumptions: an injected clock rather than {@code LocalDateTime.now()}, so a test can assert the
     * exact instant a task carries. The same reasoning the shared timestamp formatter records applies
     * here: a value read from the wall clock cannot be asserted, only tolerated.</p>
     */
    private final Clock clock;

    /**
     * The template each short settle unit of work runs inside.
     *
     * <p>Assumptions: {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW}, so settling one task is a
     * unit of work of its own. One task's settle failure must not roll back the tasks settled before it in
     * the same pass, and a caller that has just committed its own write must not have that work re-opened
     * by a drain running afterwards.</p>
     */
    private final TransactionTemplate taskTransaction;

    /**
     * Builds the ledger service over its repository, the provider port, a clock and a transaction manager.
     *
     * @param tasks the ledger repository; must not be {@code null}
     * @param provisioning the managed-identity provider port; must not be {@code null}
     * @param clock the clock recorded and settled instants are read from; must not be {@code null}
     * @param transactionManager the manager each short settle unit of work is opened against; must not be
     *     {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public IdentitySyncService(IdentitySyncTaskRepository tasks,
            CognitoUserProvisioningService provisioning, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.tasks = Objects.requireNonNull(tasks, "tasks must not be null");
        this.provisioning = Objects.requireNonNull(provisioning, "provisioning must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");

        this.taskTransaction = new TransactionTemplate(transactionManager);
        this.taskTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Records one intended change, joining the caller's transaction.
     *
     * <p>Assumptions: {@link Propagation#MANDATORY} and not {@code REQUIRED}, so a caller that forgot to
     * open a transaction is refused here rather than silently committing the intention on its own. The
     * whole value of this row is that it commits WITH the data change that caused it; a row committed
     * separately records an intention for a change that may never have happened.</p>
     *
     * @param userId the user the change concerns; must not be {@code null}
     * @param operation one of the three operation constants on {@link IdentitySyncTask}; must not be
     *     {@code null}
     * @param firstName the given name the provider is to hold, or {@code null} for a withdrawal
     * @param lastName the family name the provider is to hold, or {@code null} for a withdrawal
     * @param previousUserType the user type the provider currently holds, or {@code null} when there is no
     *     group to leave
     * @param userType the user type the provider is to hold, or {@code null} for a withdrawal
     * @return the recorded task, as stored; never {@code null}
     * @throws NullPointerException if {@code userId} or {@code operation} is {@code null}
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is
     *     active, which means the caller would have committed an intention independently of its data
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IdentitySyncTask record(String userId, String operation, String firstName, String lastName,
            String previousUserType, String userType) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(operation, "operation must not be null");

        IdentitySyncTask task = new IdentitySyncTask(userId, operation, firstName, lastName,
                previousUserType, userType, LocalDateTime.now(this.clock));
        IdentitySyncTask recorded = this.tasks.save(task);

        LOG.debug("event=auth.identity-sync.recorded userId={} operation={}", userId, operation);
        return recorded;
    }

    /**
     * Applies the changes owed for one user, and reports whether every one of them succeeded.
     *
     * <p>Assumptions: this is called AFTER the caller's transaction has committed and it never throws. A
     * failure to converge the provider is not a failure of the write the caller already committed: the row
     * is correct, the intention is durable, and the reconciliation pass will retry. Propagating here would
     * report a failed request for a write that succeeded, and a client acting on that report would repeat
     * a change that had already taken effect.</p>
     *
     * @param userId the user whose owed changes are to be applied; must not be {@code null}
     * @return {@code true} when no task remained owed after this call, {@code false} when at least one
     *     could not be applied and was left for reconciliation
     * @throws NullPointerException if {@code userId} is {@code null}
     */
    public boolean applyOwed(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");

        List<IdentitySyncTask> owed = this.tasks.findByUserIdAndStatusOrderByTaskIdAsc(
                userId, IdentitySyncTask.STATUS_PENDING, Limit.of(OWED_DRAIN_LIMIT));

        boolean allApplied = true;
        for (IdentitySyncTask task : owed) {
            allApplied = applyQuietly(task) && allApplied;
        }
        return allApplied;
    }

    /**
     * Applies one bounded pass of the oldest pending tasks across every user.
     *
     * <p>Purpose: this is the reconciliation entry point. It exists for the state no per-user drain can
     * reach -- a task recorded by a request whose process then died, so that nothing is left to call
     * {@link #applyOwed} for it.</p>
     *
     * <p>Assumptions: the pass is BOUNDED and returns how much it did, so a caller drives it to
     * completion by calling it again rather than by this method looping until the ledger is empty. An
     * unbounded pass over a backlog accumulated while the provider was unreachable would issue that many
     * provider calls from one thread with no opportunity to stop.</p>
     *
     * @param limit the greatest number of tasks this pass applies; must be positive
     * @return how many tasks this pass applied successfully
     * @throws IllegalArgumentException if {@code limit} is not positive
     */
    public int reconcile(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "limit must be positive; a non-positive limit would make a reconciliation pass a"
                            + " no-op that reads as though the ledger were empty");
        }

        List<IdentitySyncTask> pending = this.tasks.findByStatusOrderByTaskIdAsc(
                IdentitySyncTask.STATUS_PENDING, Limit.of(limit));

        int applied = 0;
        for (IdentitySyncTask task : pending) {
            if (applyQuietly(task)) {
                applied++;
            }
        }
        if (!pending.isEmpty()) {
            LOG.info("event=auth.identity-sync.reconciled selected={} applied={}",
                    pending.size(), applied);
        }
        return applied;
    }

    /**
     * Drives one bounded reconciliation pass on a fixed delay.
     *
     * <p>Purpose: this is the only production caller of {@link #reconcile}, and it exists because the
     * per-user drain cannot reach a task whose recording request then died -- nothing is left to drain for
     * it. Without a scheduled pass the ledger would record intentions that converge only when the same user
     * is next changed, which for a deleted user is never.</p>
     *
     * <p>Assumptions: the interval is a configuration property with a one-minute default, so an operator
     * can shorten it during a provider incident without a redeployment. Alternatives Considered: a
     * cron expression, rejected because convergence latency is what matters here and a fixed delay bounds
     * it directly, whereas a cron time bounds only when a pass STARTS.</p>
     *
     * <p>Trade-offs: every instance of this service runs the pass, so two instances can select the same
     * task and both call the provider. That is accepted rather than coordinated because the three provider
     * operations are idempotent and the settle transaction admits one winner, so the cost is a duplicate
     * call and the alternative -- a distributed lock -- is a new failure mode for a table this small. A
     * leader election would be the answer if the pass had a side effect that were not idempotent.</p>
     *
     * <p>This method takes no parameter and yields no value.</p>
     */
    @Scheduled(fixedDelayString = "${carddemo.auth.identity-sync.reconcile-interval-ms:60000}")
    public void reconcileOnSchedule() {
        try {
            reconcile(RECONCILE_PASS_LIMIT);

            // WHY : Assumptions: a failure of the pass itself is swallowed here rather than propagated,
            //       because the scheduler's only response to an exception is to log it and run again on the
            //       next delay -- which is exactly what happens anyway. Catching it lets this class log the
            //       digest in its own event vocabulary instead of a framework stack trace, and guarantees
            //       one unreachable database cannot silence the schedule.
        } catch (RuntimeException unreachable) {
            LOG.error("event=auth.identity-sync.reconcile-failed digest={}",
                    ThrowableDigest.of(unreachable));
        }
    }

    /**
     * Applies one task as three short units of work, converting every failure into a {@code false} return.
     *
     * <p>Assumptions: the failure is recorded on the task rather than raised, because the caller of a
     * drain has nothing useful to do with one task's fault: the intention stays durable and the next pass
     * retries it. The fault's DIGEST is recorded and its message is not -- a provider message can carry an
     * account identifier or a request identifier, and this column is read by operators and copied into
     * support tickets.</p>
     *
     * <p>Assumptions: the row is re-read here rather than the entity handed in being trusted, and
     * re-reading is what makes the double-application check real. An entity loaded by the drain that
     * selected it carries the status it had at selection time, so two overlapping appliers would each see a
     * pending task; re-reading makes the second one see the first one's outcome.</p>
     *
     * @param task the task the drain selected; must not be {@code null}
     * @return {@code true} when the task is no longer owed -- either this call converged it or another
     *     applier already had -- and {@code false} when it remains pending for reconciliation
     */
    private boolean applyQuietly(IdentitySyncTask task) {
        Long taskId = task.getTaskId();
        try {
            IdentitySyncTask pending = readPending(taskId);
            if (pending == null) {
                // WHY : Assumptions: a task that is no longer pending is reported as CONVERGED and not as a
                //       failure, which is the opposite of what an "applied nothing" return would say. The
                //       provider owes nothing for it either way, so counting it against the drain would
                //       make a caller repeat work another applier had finished.
                return true;
            }

            try {
                // WHY : Refactoring Rationale: the provider is called with NO transaction open, which is the
                //       whole point of this class and is why the read above is a unit of its own. Calling
                //       from inside the settle transaction would have re-created, one layer down, exactly
                //       the defect the three user write paths were corrected for.
                call(pending);
            } catch (SdkException providerFault) {
                recordAttempt(taskId, ThrowableDigest.of(providerFault));
                LOG.error("event=auth.identity-sync.apply-failed taskId={} userId={} operation={}"
                        + " digest={}", taskId, task.getUserId(), task.getOperation(),
                        ThrowableDigest.of(providerFault));
                return false;
            }

            return settle(taskId);

        } catch (RuntimeException failure) {
            // WHY : Assumptions: a failure of the LEDGER WRITE, as distinct from a failure of the provider
            //       call, also lands here, and it is logged rather than raised for the same reason. Both
            //       leave the task pending, which is the state reconciliation reads, so neither can lose
            //       the intention.
            LOG.error("event=auth.identity-sync.apply-failed taskId={} userId={} operation={} digest={}",
                    taskId, task.getUserId(), task.getOperation(), ThrowableDigest.of(failure));
            return false;
        }
    }

    /**
     * Re-reads one ledger row and yields it only while it is still owed.
     *
     * <p>Assumptions: no transaction is opened for this read. It is a single-row primary-key lookup and the
     * repository proxy already runs it read-only, so wrapping it would add a boundary that guards nothing;
     * the value it returns is re-checked inside the settle transaction anyway.</p>
     *
     * @param taskId the ledger row to re-read; must not be {@code null}
     * @return the row while it is pending, or {@code null} when it is absent or already settled
     */
    private IdentitySyncTask readPending(Long taskId) {
        IdentitySyncTask task = this.tasks.findById(taskId).orElse(null);
        return task != null && task.isPending() ? task : null;
    }

    /**
     * Records one failed attempt against a task, in a transaction of its own.
     *
     * <p>Assumptions: the row is re-read and re-checked inside the transaction, so an attempt is never
     * recorded against a task another applier settled successfully in the meantime -- which would leave an
     * applied task carrying a failure code and mislead whoever read it.</p>
     *
     * @param taskId the ledger row the attempt is recorded against; must not be {@code null}
     * @param failureCode the stable digest of the provider fault, never its message
     */
    private void recordAttempt(Long taskId, String failureCode) {
        this.taskTransaction.executeWithoutResult(status -> {
            IdentitySyncTask fresh = this.tasks.findById(taskId).orElse(null);
            if (fresh == null || !fresh.isPending()) {
                return;
            }
            fresh.recordFailedAttempt(failureCode, ATTEMPT_CEILING, LocalDateTime.now(this.clock));
            this.tasks.save(fresh);
        });
    }

    /**
     * Marks one task applied, in a transaction of its own, and reports whether it is now settled.
     *
     * @param taskId the ledger row to settle; must not be {@code null}
     * @return {@code true} when the row is settled after this call, whether by this call or by an applier
     *     that reached it first
     */
    private boolean settle(Long taskId) {
        Boolean settled = this.taskTransaction.execute(status -> {
            IdentitySyncTask fresh = this.tasks.findById(taskId).orElse(null);
            if (fresh == null) {
                // WHY : Assumptions: an absent row is settled, because nothing is owed for a task that no
                //       longer exists. The provider call above has already succeeded, so the only thing
                //       lost is the record of it.
                return Boolean.TRUE;
            }
            if (!fresh.isPending()) {
                return Boolean.TRUE;
            }
            fresh.markApplied(LocalDateTime.now(this.clock));
            this.tasks.save(fresh);
            LOG.info("event=auth.identity-sync.applied taskId={} userId={} operation={}",
                    fresh.getTaskId(), fresh.getUserId(), fresh.getOperation());
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(settled);
    }

    /**
     * Issues the provider call one task expresses.
     *
     * <p>Assumptions: the switch is EXHAUSTIVE over the two operations the column's own check constraint
     * admits, and its default arm refuses rather than ignoring. An unrecognised operation means the
     * constraint and this method disagree, which is a programming error and not a provider fault, so it
     * must not be counted as an attempt and retried four more times.</p>
     *
     * <p>Refactoring Rationale: there was a third arm, for a {@code PROVISION} intention, and it was
     * unreachable -- no caller of {@link #record} ever named that operation, because the create path
     * cannot record an intention it has already had to act on. The arm and the value were withdrawn
     * together, by {@code V3__auth_identity_sync_operations.sql}, so the branches here and the column's
     * domain are now the same set. Alternatives Considered: keeping the arm on the reasoning that the
     * constraint still admitted the value. Declined because that reasoning is circular -- the constraint
     * admitted it only because the arm existed.</p>
     *
     * @param task the pending task whose provider call is to be issued; must not be {@code null}
     * @throws SdkException if the provider rejects or cannot serve the call
     * @throws IllegalStateException if the task carries an operation this method does not implement
     */
    private void call(IdentitySyncTask task) {
        switch (task.getOperation()) {
            case IdentitySyncTask.OPERATION_SYNCHRONISE -> this.provisioning.synchronise(
                    task.getUserId(), task.getFirstName(), task.getLastName(),
                    task.getPreviousUserType(), task.getUserType());
            case IdentitySyncTask.OPERATION_WITHDRAW -> this.provisioning.withdraw(task.getUserId());
            default -> throw new IllegalStateException(
                    "identity sync task " + task.getTaskId() + " carries operation "
                            + task.getOperation()
                            + ", which this applier does not implement; the column's check constraint and"
                            + " this switch have diverged");
        }
    }
}
