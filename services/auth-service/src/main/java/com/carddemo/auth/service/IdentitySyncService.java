package com.carddemo.auth.service;

import com.carddemo.auth.domain.IdentitySyncTask;
import com.carddemo.auth.repository.IdentitySyncTaskRepository;
import com.carddemo.auth.repository.UserRepository;
import com.carddemo.common.observability.ThrowableDigest;
import java.time.Clock;
import java.time.Duration;
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
 * <p>Purpose: the create path is the one write that cannot commit its intention with its data, because
 * {@code auth.users.cognito_sub} is not nullable and only the provider can mint the subject the row
 * carries -- so the account must exist before the row can be written at all. It therefore uses the
 * remaining three entry points as an ARM, SETTLE and COMPENSATE triple: {@link #record} arms a withdrawal
 * in a committed transaction BEFORE provisioning, {@link #abandon} settles it inside the same transaction
 * as the insert when the create succeeds, and {@link #applyArmed} performs it when the create does not.
 * The arming is what makes a process death between provisioning and the insert recoverable: the account it
 * leaves behind is named by a durable pending row rather than by nothing at all.</p>
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
 * <p>Assumptions: a withdrawal is NOT unconditionally idempotent, and that is the one asymmetry in the
 * paragraph above. Removing an account twice is harmless, but removing it after the identifier has been
 * created again destroys an account the new row owns rather than the one the task was recorded against --
 * reachable whenever a delete's provider call is lost and the identifier is later reused. Every applier
 * that did not itself provision the account therefore checks the row before withdrawing and abandons the
 * task when one exists, which is what {@link #isSupersededWithdrawal} is for.</p>
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

    /**
     * How long an armed compensation is left alone before an unattributed pass will apply it.
     *
     * <p>Purpose: the create path arms its compensating withdrawal in a COMMITTED transaction of its own
     * BEFORE it provisions the pool account, because a durable intention is the only thing that survives a
     * process death between provisioning and the insert. That makes the intention visible to every other
     * reader while the create is still in flight, so an unattributed pass that applied it immediately
     * would withdraw the account the create is about to bind a row to -- leaving a row whose user cannot
     * sign on at all, which this context's own delete documentation names as the unrecoverable direction.
     * Waiting out this interval is what makes the armed intention safe to publish.</p>
     *
     * <p>Assumptions: thirty seconds, chosen against the two bounds it sits between rather than as a round
     * number. It must exceed the interval a healthy create spends between arming and settling -- one
     * provider round trip plus one insert, ordinarily tens of milliseconds -- with enough margin that a
     * slow provider does not turn a successful create into a withdrawn account. It must stay well below
     * the interval an operator would call a convergence failure, and it is half the scheduled pass's own
     * default delay, so a genuinely orphaned account is withdrawn by the second pass after it was armed
     * rather than by a much later one.</p>
     *
     * <p>Assumptions: the grace applies to the SCHEDULED pass and not to {@link #applyOwed}, and the
     * asymmetry is the whole point. {@code applyOwed} is called by the very request that recorded the
     * intention, after that request has decided the write's outcome, so it knows what the pass cannot; the
     * pass has no requester behind it and must therefore assume any young intention belongs to a request
     * still running.</p>
     *
     * <p>Alternatives Considered: a fourth status meaning "armed but not yet owed", flipped to pending by
     * the write path when compensation actually became necessary. Rejected because a process death is the
     * case the arming exists for and a death leaves the row in the armed status for ever, so the pass
     * would have to select armed rows anyway -- reintroducing this same age test while additionally
     * requiring a value in the column's check constraint, in the applier's reader and in every operator
     * query that partitions the ledger.</p>
     *
     * <p>Trade-offs: a withdrawal whose immediate attempt failed now waits out this interval before the
     * pass retries it, where previously the next pass took it. That costs at most this interval of
     * additional life for an account whose row is already gone -- an identity refused at every guarded
     * route in the meantime -- and it buys the elimination of the window above.</p>
     */
    static final Duration COMPENSATION_GRACE = Duration.ofSeconds(30);

    /**
     * The code recorded on a withdrawal abandoned because a committed row owns the account.
     *
     * <p>Assumptions: a code and not a sentence, matching the column's use everywhere else in this
     * ledger, and short enough for the column's declared width.</p>
     */
    static final String ABANDONED_ROW_OWNS_ACCOUNT = "row-owns-account";

    /** The ledger this class records into and drains from. */
    private final IdentitySyncTaskRepository tasks;

    /**
     * The rows a withdrawal is checked against before it is applied.
     *
     * <p>Purpose: this collaborator exists for ONE question -- does a committed row still name the user
     * whose pool account a pending withdrawal would remove? A withdrawal is recorded by two different
     * situations, and only one of them is unconditional. A delete records it having removed the row in the
     * same transaction, so no row can remain; a create ARMS it before provisioning, so a row may be
     * written moments later. There is also a third path to the same state that neither of those two
     * created: a withdrawal left pending by a delete whose provider call was lost, after which the same
     * identifier is created again -- at which point applying the stale withdrawal would destroy the NEW
     * account. The row is this context's authority on whether a user exists, so the row is what settles
     * whether a withdrawal is still owed.</p>
     *
     * <p>Alternatives Considered: expressing the guard as a predicate inside the ledger query, so a
     * withdrawal whose user has a row were simply not selected. Rejected because the row would then stay
     * pending for ever -- an intention an operator reads as unconverged when it is in fact moot -- where
     * abandoning it with a recorded reason settles it and says why.</p>
     *
     * <p>⚠️ Purpose: a withdrawal is owed only while the pool account it names has NO row in this schema.
     * That is the definition of the orphan this ledger exists to reconcile, and it is why this
     * collaborator is here: a guard recorded before a create, or a withdrawal recorded by a delete, must
     * not be applied against an account a committed row legitimately owns. Without the check, a guard the
     * reconciliation pass reached after its create had completed would withdraw the pool account of a user
     * that exists -- turning a compensation into an outage for that user.</p>
     *
     * <p>Assumptions: the repository and not the user service, so this class depends on the row's
     * existence and on nothing else. Depending on the service would make the ledger and the write paths
     * mutually dependent, and the only question asked here is whether a key is present.</p>
     */
    private final UserRepository users;

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
     * Builds the ledger service over its repository, the provider port, the rows a withdrawal is
     * guarded against, a clock and a transaction manager.
     *
     * @param tasks the ledger repository; must not be {@code null}
     * @param provisioning the managed-identity provider port; must not be {@code null}
     * @param users the user rows, read to decide whether a pending withdrawal is still owed before it
     *     is applied; must not be {@code null}
     * @param clock the clock recorded and settled instants are read from; must not be {@code null}
     * @param transactionManager the manager each short settle unit of work is opened against; must not be
     *     {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public IdentitySyncService(IdentitySyncTaskRepository tasks,
            CognitoUserProvisioningService provisioning, UserRepository users, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.tasks = Objects.requireNonNull(tasks, "tasks must not be null");
        this.provisioning = Objects.requireNonNull(provisioning, "provisioning must not be null");
        this.users = Objects.requireNonNull(users, "users must not be null");
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
     * Records a withdrawal the create path may owe, in its own committed transaction, before it acts.
     *
     * <p>⚠️ Purpose: this is the create path's provisioning guard and it closes the one gap the
     * record-then-apply discipline could not reach. That path cannot record its intention alongside its
     * data, because {@code auth.users.cognito_sub} is not nullable and only the provider mints the subject
     * -- so the pool account has to exist before the row can be written. Before this method existed the
     * path recorded nothing until it had already failed, and its in-process compensation arms are exactly
     * what a process death skips: an account was left able to authenticate, holding a group, with no row
     * here and no record anywhere that it existed. The identifier then read as taken while no row named
     * it, for as long as anyone cared to look.</p>
     *
     * <p>⚠️ Assumptions: the guard is recorded {@code CLAIMED} rather than {@code PENDING}, so neither
     * drain can select it while the create it guards is still in flight. Recording it pending would open a
     * window in which a scheduled pass withdrew the pool account of a create that was about to succeed --
     * a compensation causing the very orphan it exists to prevent, in reverse.</p>
     *
     * <p>⚠️ Assumptions: the transaction is this method's OWN and is committed before it returns, which is
     * what makes the guard survive the process. {@link Propagation#REQUIRES_NEW} rather than
     * {@code MANDATORY}: the create path deliberately holds no transaction across the provider call, and a
     * guard that joined a caller's transaction would be rolled back by the same failure it is meant to
     * compensate for.</p>
     *
     * <p>Assumptions: a failure to record the guard PROPAGATES rather than being swallowed. A create that
     * cannot make its compensation durable must not call the provider at all -- proceeding would recreate
     * the orphan this method exists to prevent, with the added confusion of a log line saying the guard
     * was attempted.</p>
     *
     * @param userId the identifier the pool account will be created under; must not be {@code null}
     * @return the identifier of the committed guard row, which the create path settles or re-owes; never
     *     {@code null}
     * @throws NullPointerException if {@code userId} is {@code null}
     * @throws org.springframework.dao.DataAccessException if the guard cannot be committed, which is
     *     deliberately fatal to the create it would have guarded
     */
    public Long claimProvisioningGuard(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");

        Long taskId = this.taskTransaction.execute(status -> {
            IdentitySyncTask guard = new IdentitySyncTask(userId, IdentitySyncTask.OPERATION_WITHDRAW,
                    null, null, null, null, LocalDateTime.now(this.clock),
                    IdentitySyncTask.STATUS_CLAIMED);
            return this.tasks.save(guard).getTaskId();
        });

        LOG.debug("event=auth.identity-sync.guard-claimed userId={} taskId={}", userId, taskId);
        return taskId;
    }

    /**
     * Closes a claimed guard inside the caller's transaction, because the create it guarded succeeded.
     *
     * <p>⚠️ Assumptions: {@link Propagation#MANDATORY}, so this runs in the SAME transaction as the row
     * insert. That is the whole point: the row's existence and the guard's closure become one atomic fact,
     * so there is no interval in which a committed row is shadowed by a live guard that a reconciliation
     * pass could act on. A separate transaction would reintroduce exactly the window the guard exists to
     * remove, one step later.</p>
     *
     * <p>Assumptions: the guard is closed as {@code CANCELLED} and not {@code APPLIED}, because no
     * provider call was made -- the account is legitimately owned by the row just written. The reasoning is
     * recorded on {@code IdentitySyncTask.STATUS_CANCELLED}.</p>
     *
     * @param taskId the guard {@link #claimProvisioningGuard} returned; must not be {@code null}
     * @throws NullPointerException if {@code taskId} is {@code null}
     * @throws IllegalStateException if the guard is absent or is not claimed, either of which means this
     *     method and the create path have diverged about which row is being closed
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active,
     *     because closing the guard outside the insert's transaction is the defect this method prevents
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeProvisioningGuard(Long taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");

        IdentitySyncTask guard = this.tasks.findById(taskId).orElseThrow(() -> new IllegalStateException(
                "identity sync guard " + taskId + " is absent, so the create path cannot close it; a"
                        + " committed guard is the only thing this method is called with"));
        guard.markCancelled(LocalDateTime.now(this.clock));
        this.tasks.save(guard);
    }

    /**
     * Closes a claimed guard in its own transaction, because this attempt created nothing to withdraw.
     *
     * <p>⚠️ Purpose: the create path calls this when the provider REFUSED the username. A refusal means no
     * account was created by this attempt, so there is nothing for the guard to compensate -- and applying
     * it would withdraw an account belonging to whoever does hold that username: a concurrent create that
     * is about to commit its row, or an earlier interrupted create whose own guard the reconciliation pass
     * owns. Either way this attempt must not act on it.</p>
     *
     * <p>Assumptions: {@link Propagation#REQUIRES_NEW} through the same template the settle path uses,
     * because the create path holds no transaction at this point and the closure must commit on its own.
     * A failure to close is logged and swallowed: the request is already failing with the conflict the
     * caller needs to hear about, and a guard left claimed is inert -- no drain selects it -- so the
     * residue is a row an operator can see rather than an action anything will take.</p>
     *
     * @param taskId the guard {@link #claimProvisioningGuard} returned; must not be {@code null}
     * @throws NullPointerException if {@code taskId} is {@code null}
     */
    public void cancelProvisioningGuard(Long taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        try {
            this.taskTransaction.executeWithoutResult(status -> {
                IdentitySyncTask guard = this.tasks.findById(taskId).orElse(null);
                if (guard == null || !guard.isClaimed()) {
                    return;
                }
                guard.markCancelled(LocalDateTime.now(this.clock));
                this.tasks.save(guard);
            });
        } catch (RuntimeException uncloseable) {
            LOG.error("event=auth.identity-sync.guard-not-cancelled taskId={} digest={}",
                    taskId, ThrowableDigest.of(uncloseable));
        }
    }

    /**
     * Moves a claimed guard into the owed set, because the create failed with the account possibly created.
     *
     * <p>⚠️ Purpose: this is the ambiguous outcome, and it is the one the guard was built for. Either the
     * provider call failed with a fault -- so the account may or may not exist -- or it succeeded and the
     * row write then failed, so the account certainly exists with no row behind it. In both cases a
     * withdrawal is genuinely owed, and the provider's withdrawal is idempotent, so applying it against an
     * account that was never created is a no-op rather than an error.</p>
     *
     * <p>Assumptions: a failure to re-owe the guard is logged and swallowed, because the caller is already
     * raising the failure the client needs. What is lost in that case is the reconciliation of one possible
     * orphan, and what remains is a claimed row naming it -- which is why the guard is recorded with the
     * user identifier rather than only with a task identifier.</p>
     *
     * @param taskId the guard {@link #claimProvisioningGuard} returned; must not be {@code null}
     * @return {@code true} when the guard is now owed and the drains will act on it
     * @throws NullPointerException if {@code taskId} is {@code null}
     */
    public boolean oweProvisioningGuard(Long taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        try {
            Boolean owed = this.taskTransaction.execute(status -> {
                IdentitySyncTask guard = this.tasks.findById(taskId).orElse(null);
                if (guard == null || !guard.isClaimed()) {
                    return Boolean.FALSE;
                }
                guard.markOwed();
                this.tasks.save(guard);
                return Boolean.TRUE;
            });
            return Boolean.TRUE.equals(owed);
        } catch (RuntimeException unowable) {
            LOG.error("event=auth.identity-sync.guard-not-owed taskId={} digest={}",
                    taskId, ThrowableDigest.of(unowable));
            return false;
        }
    }

    /**
     * Withdraws one recorded intention that turned out not to be owed, joining the caller's transaction.
     *
     * <p>Purpose: this is the settle half of an ARMED compensation. The create path records a withdrawal
     * before it provisions the pool account, so that a process death between provisioning and the insert
     * leaves the withdrawal durably owed; when the insert instead COMMITS, that withdrawal must stop being
     * owed in the SAME transaction as the insert. Any later moment would leave a window in which a
     * committed row and a live withdrawal intention coexist, and an applier reaching it would remove the
     * account of a user that had just been created.</p>
     *
     * <p>Assumptions: {@link Propagation#MANDATORY}, for the reason above and not for symmetry with
     * {@link #record}. A caller that settled the intention in a transaction of its own could commit the
     * settle and then fail to commit the insert, which loses the compensation for a create that did not
     * happen -- the precise state the arming exists to prevent.</p>
     *
     * <p>Assumptions: a task that is absent or already settled is accepted silently rather than refused,
     * because both outcomes mean the same thing to this caller: nothing is owed. Raising would turn a
     * successful create into a failure over a ledger row whose work is already done.</p>
     *
     * @param taskId the ledger row to settle; must not be {@code null}
     * @param reason the stable code recording why the intention stopped being owed; must not be
     *     {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is
     *     active, which means the settle would not be atomic with the write that made it moot
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void abandon(Long taskId, String reason) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        IdentitySyncTask task = this.tasks.findById(taskId).orElse(null);
        if (task == null || !task.isPending()) {
            return;
        }
        task.markAbandoned(reason, LocalDateTime.now(this.clock));
        this.tasks.save(task);
        LOG.debug("event=auth.identity-sync.abandoned taskId={} userId={} operation={} reason={}",
                taskId, task.getUserId(), task.getOperation(), reason);
    }

    /**
     * Applies one intention the CALLING request itself recorded and has decided the outcome of.
     *
     * <p>Purpose: this is how a create that provisioned a pool account and then failed to write its row
     * withdraws the account it created. It is separate from {@link #applyOwed} because that method's
     * withdrawal guard is wrong for this one case: the guard refuses to withdraw an account a committed
     * row names, and on the duplicate-insert path a row for that identifier DOES exist -- the one this
     * caller collided with. That row belongs to another writer and does not own the account this request
     * provisioned, so guarding here would orphan exactly the account this method exists to remove.</p>
     *
     * <p>Assumptions: the caller provisioned the account within the request that is calling, which is what
     * makes skipping the guard sound. Nothing else may call this method for that reason, which is why it
     * takes the task the caller armed rather than a user identifier: passing a name would let any caller
     * reach the unguarded path.</p>
     *
     * <p>Assumptions: it never throws, for the reason {@link #applyOwed} does not either -- the failure
     * being reported to the caller is the create's own, and replacing it with one about cleanup would tell
     * the caller nothing it can act on. A withdrawal that could not be applied stays pending and the
     * scheduled pass owns the retry.</p>
     *
     * @param armed the intention this request recorded before provisioning; must not be {@code null}
     * @return {@code true} when the intention is no longer owed after this call
     * @throws NullPointerException if {@code armed} is {@code null}
     */
    public boolean applyArmed(IdentitySyncTask armed) {
        Objects.requireNonNull(armed, "armed must not be null");
        return applyQuietly(armed, false);
    }

    /**
     * Applies one intention the calling request itself recorded, named by its ledger identifier.
     *
     * <p>Purpose: this is {@link #applyArmed(IdentitySyncTask)} for a caller that holds the identifier
     * rather than the row -- which is what {@link #claimProvisioningGuard} hands back, so the create path's
     * compensation reaches the unguarded application without re-reading the row itself.
     *
     * <p>Assumptions: an ABSENT row is answered {@code true} rather than {@code false}, because nothing is
     * owed for a ledger row that is not there and the caller's question is whether anything remains owed.
     * The alternative -- reporting a failure -- would make a compensation log an unrecoverable state for a
     * task another pass had already settled.
     *
     * @param taskId the identifier of the intention to apply; must not be {@code null}
     * @return {@code true} when nothing is owed for that row after this call
     * @throws NullPointerException if {@code taskId} is {@code null}
     */
    public boolean applyArmed(Long taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        IdentitySyncTask armed = this.tasks.findById(taskId).orElse(null);
        if (armed == null) {
            return true;
        }
        return applyQuietly(armed, false);
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
            // WHY : Assumptions: the withdrawal guard IS applied on this path even though the caller is
            //       the request that recorded the intention, because the intention it recorded is not
            //       necessarily the only one owed for that user. A withdrawal left pending by an earlier
            //       delete whose provider call was lost is still selected here, and applying it after the
            //       same identifier has been created again would destroy the new account. The create
            //       path's own compensation reaches applyArmed instead, which is the one caller that
            //       legitimately skips the guard.
            allApplied = applyQuietly(task, true) && allApplied;
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

        LocalDateTime armedAfter = LocalDateTime.now(this.clock).minus(COMPENSATION_GRACE);

        int applied = 0;
        int deferred = 0;
        for (IdentitySyncTask task : pending) {
            // WHY : Assumptions: a task younger than the grace interval is DEFERRED rather than applied,
            //       and this pass is the only place that test belongs. The create path arms its
            //       compensating withdrawal in a committed transaction before it provisions, so a young
            //       pending intention is indistinguishable here from one belonging to a request that is
            //       still running -- and applying that one would withdraw the account the request is
            //       about to bind a row to. The constant's own declaration records the two bounds the
            //       interval sits between.
            // WHY : Assumptions: the cut-off is computed ONCE for the pass rather than per task, so every
            //       task in one pass is judged against one instant. Reading the clock per task would let
            //       two tasks armed together fall on opposite sides of the boundary.
            if (task.getCreatedAt().isAfter(armedAfter)) {
                deferred++;
                continue;
            }
            if (applyQuietly(task, true)) {
                applied++;
            }
        }
        if (!pending.isEmpty()) {
            LOG.info("event=auth.identity-sync.reconciled selected={} applied={} deferred={}",
                    pending.size(), applied, deferred);
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
     * <p>Assumptions: the withdrawal guard is evaluated on the RE-READ row rather than on the handed-in
     * one, and after the double-application check rather than before it, so a task another applier has
     * already settled is never guarded, logged or abandoned a second time.</p>
     *
     * @param task the task the drain selected; must not be {@code null}
     * @param guardWithdrawals whether a withdrawal is to be abandoned rather than applied when a committed
     *     row still names its user; {@code false} only for a compensation the calling request armed itself
     * @return {@code true} when the task is no longer owed -- either this call converged it, another
     *     applier already had, or the guard settled it -- and {@code false} when it remains pending for
     *     reconciliation
     */
    private boolean applyQuietly(IdentitySyncTask task, boolean guardWithdrawals) {
        Long taskId = task.getTaskId();
        try {
            IdentitySyncTask pending = readPending(taskId);
            // WHY : ⚠️ Assumptions: a WITHDRAWAL is abandoned rather than applied while a row for that
            //       identifier EXISTS. A withdrawal is owed only against a pool account no row here owns,
            //       which is the definition of the orphan this ledger reconciles. The check exists because
            //       the create path records its compensation BEFORE it acts: a guard whose create then
            //       succeeded is closed inside the insert's own transaction, but a process that died
            //       between the two leaves the guard owed, and once the identifier is re-created
            //       legitimately the withdrawal would take that user's account away. It also covers the
            //       delete path's withdrawal against a re-created identifier, for the same reason.
            // WHY : Assumptions: the row is re-read HERE rather than at recording time, because what
            //       matters is whether a row exists when the provider WOULD be called -- the interval
            //       between the two is exactly the interval a reconciliation pass spans.
            // WHY : Trade-offs: this cannot distinguish a re-created identifier from the create the guard
            //       was recorded for, and it does not need to: in both cases a row owns the account and
            //       withdrawing it would be wrong. What is given up is the withdrawal of an account whose
            //       identifier was legitimately re-used before reconciliation ran; that account is owned
            //       by a row, so it is not an orphan and nothing is left stranded.
            // WHY : ⚠️ Refactoring Rationale: this gate is why `guardWithdrawals` is a parameter rather
            //       than an invariant. The interlock was briefly present TWICE -- once here and once
            //       unconditionally below -- with a predicate identical to isSupersededWithdrawal. The
            //       lower copy made this one dead for applyOwed and, far worse, fired for applyArmed,
            //       whose whole purpose is to bypass the interlock because its caller provisioned the
            //       account itself. The compensation therefore closed without ever calling the provider,
            //       so the orphan the guard exists to remove was never removed. One gated copy is what
            //       keeps the bypass reachable.
            if (pending != null && guardWithdrawals && isSupersededWithdrawal(pending)) {
                // WHY : Assumptions: the line is a WARNING rather than an information or an error. It is
                //       not routine -- it means a withdrawal was recorded for an identifier a committed
                //       row now names, which is either a delete whose provider call was lost before the
                //       identifier was created again or a create still settling -- and it is not a fault
                //       either, because the guard has just made the outcome correct. An error line here
                //       would report a failure on a path that succeeded.
                LOG.warn("event=auth.identity-sync.withdrawal-superseded taskId={} userId={}",
                        taskId, pending.getUserId());
                return settleAbandoned(taskId, ABANDONED_ROW_OWNS_ACCOUNT);
            }
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
     * Reports whether a pending withdrawal has been superseded by a row that owns the account.
     *
     * <p>Assumptions: only a WITHDRAW task is guarded. A projection assignment against a user with no row
     * is harmless -- it renames an account and moves a group membership, neither of which can admit a
     * sign-on this context would refuse -- so guarding it would suppress a convergence for no gain.</p>
     *
     * <p>Assumptions: the question is asked of the ROW and not of the provider, because the row is this
     * context's authority on whether a user exists; the whole class exists because the two stores cannot be
     * read atomically together.</p>
     *
     * @param task the pending task under consideration; must not be {@code null}
     * @return {@code true} when the task is a withdrawal and a row still names its user
     */
    private boolean isSupersededWithdrawal(IdentitySyncTask task) {
        return IdentitySyncTask.OPERATION_WITHDRAW.equals(task.getOperation())
                && this.users.existsById(task.getUserId());
    }

    /**
     * Settles one task as abandoned in a transaction of its own, and reports that it is no longer owed.
     *
     * <p>Assumptions: the row is re-read and re-checked inside the transaction, exactly as the applied and
     * failed-attempt settles are, so a task another applier converged in the meantime is not rewritten as
     * abandoned.</p>
     *
     * @param taskId the ledger row to settle; must not be {@code null}
     * @param reason the stable code recording why the task stopped being owed
     * @return {@code true} always, because the task is not owed after this call whichever branch ran
     */
    private boolean settleAbandoned(Long taskId, String reason) {
        this.taskTransaction.executeWithoutResult(status -> {
            IdentitySyncTask fresh = this.tasks.findById(taskId).orElse(null);
            if (fresh == null || !fresh.isPending()) {
                return;
            }
            fresh.markAbandoned(reason, LocalDateTime.now(this.clock));
            this.tasks.save(fresh);
        });
        return true;
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
