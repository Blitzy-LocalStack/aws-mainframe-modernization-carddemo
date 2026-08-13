package com.carddemo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.auth.domain.IdentitySyncTask;
import com.carddemo.auth.repository.IdentitySyncTaskRepository;
import com.carddemo.auth.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InternalErrorException;

/**
 * Asserts the ledger that carries a user change from {@code auth.users} to the managed user pool.
 *
 * <h2>What this class exists to catch</h2>
 *
 * <p>Purpose: this class is the evidence for the construction that replaced calling the identity provider
 * from inside a database transaction. The three user write paths now commit an intention alongside the row
 * and apply it afterwards, so the properties that used to be claimed by the transaction -- that the two
 * stores agree, and that an interruption does not leave one of them changed alone -- are now properties of
 * this class and have to be asserted here.</p>
 *
 * <p>Assumptions: no reference paragraph answers for any of this, and that is stated rather than left for a
 * reader to wonder about. The baseline keeps its users in ONE file, declared at
 * {@code app/csd/CARDDEMO.CSD} line 88, so a user change there is a single file write that cannot be half
 * done and needs no ledger. This class asserts a construction the migration introduced because the
 * migrated context writes to two stores, not a behaviour transcribed from anywhere.</p>
 *
 * <p>Assumptions: the strongest single case here is the one asserting that the provider is called BEFORE
 * any transaction is opened. It is the direct inversion of the defect being corrected, and it is
 * expressible only because the transaction manager is substituted -- a real manager would give this class
 * nothing to observe the boundary with.</p>
 *
 * <p>Alternatives Considered: standing a database container up under this class, so the commit itself were
 * real. Rejected because the branch structure asserted here -- which provider call each operation issues,
 * what a fault records, what a second applier sees, and where the transaction boundary falls relative to
 * the provider call -- is all reachable with a substituted store, and a container-backed assertion would
 * belong in the repository test package where the failsafe phase runs it.</p>
 *
 * <p>Assumptions: the in-memory ledger is built inline here rather than shared with the sibling service
 * test, because this package's charter admits no shared helper: a stub is written in the file that reads it
 * so a reader never opens a second file to learn what it does.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception clause.</p>
 */
class IdentitySyncServiceTest {

    /** The instant every recorded and settled timestamp is read from, fixed so it can be asserted. */
    private static final Instant NOW = Instant.parse("2022-07-18T03:00:00Z");

    /** The user every case below records an intention for. */
    private static final String USER = "USER0001";

    private IdentitySyncTaskRepository ledger;

    /**
     * The user rows the applier reads to decide whether a withdrawal is still owed.
     *
     * <p>⚠️ Assumptions: a substitute answering "no row" by default, which is the state every existing
     * case in this class assumes -- an intention is owed precisely because the row it describes is gone or
     * was never written. The one case that needs the opposite arranges it explicitly.</p>
     */
    private UserRepository users;

    private CognitoUserProvisioningService provisioning;

    private PlatformTransactionManager transactions;

    /**
     * The clock the service reads, advanceable so a case can age a task past the compensation grace.
     *
     * <p>Assumptions: an advanceable clock rather than a fixed one, and the reason is the grace interval
     * the scheduled pass applies. Every task this class records is stamped from this clock, so under a
     * FIXED clock every task is zero seconds old for ever and the pass would defer all of them -- the
     * reconciliation cases could not reach the provider at all. Advancing the clock is what lets one case
     * assert the deferral and another assert the application, with nothing but elapsed time between
     * them.</p>
     *
     * <p>Alternatives Considered: making the grace a constructor parameter or a configuration property so
     * a test could set it to zero. Rejected because the interval is a property of how this class uses the
     * ledger rather than of a deployment -- the three other bounds beside it are constants for the same
     * reason -- and a test that zeroed it would assert the pass's behaviour under a value no deployment
     * runs.</p>
     */
    private AdvanceableClock clock;

    private IdentitySyncService service;

    /**
     * Builds the ledger service over an in-memory ledger, a substituted provider and a fixed clock.
     *
     * <p>This method takes no parameter and yields no value. It raises nothing under normal operation.</p>
     */
    @BeforeEach
    void buildService() {
        ledger = inMemoryLedger();
        users = mock(UserRepository.class);
        provisioning = mock(CognitoUserProvisioningService.class);
        transactions = mock(PlatformTransactionManager.class);
        clock = new AdvanceableClock(NOW);
        // WHY : Assumptions: the constructor argument order is (tasks, provisioning, users, clock,
        //       transactionManager), which is the order the service declares. The clock is the
        //       ADVANCEABLE one rather than a fixed reading, because the scheduled pass defers a task
        //       younger than its compensation grace -- under a fixed clock every task stays zero
        //       seconds old and the reconciliation cases could never reach the provider at all.
        service = new IdentitySyncService(ledger, provisioning, users, clock, transactions);
    }

    /**
     * Asserts a recorded intention is pending, unattempted and stamped from the injected clock.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a recorded intention is pending, unattempted and stamped from the injected clock")
    void aRecordedIntentionIsPendingAndUnattempted() {
        IdentitySyncTask recorded = service.record(USER, IdentitySyncTask.OPERATION_SYNCHRONISE,
                "Grace", "Hopper", "U", "A");

        assertThat(recorded.getStatus()).isEqualTo(IdentitySyncTask.STATUS_PENDING);
        assertThat(recorded.getAttempts()).isZero();
        assertThat(recorded.getSettledAt()).isNull();
        assertThat(recorded.getLastFailureCode()).isNull();
        assertThat(recorded.getCreatedAt())
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

        // Assumptions: recording issues NO provider call of its own, which is the whole point of the
        //   separation. The intention is a row, and a row is all the caller's transaction may contain.
        verifyNoInteractions(provisioning);
    }

    /**
     * Asserts the provider is called BEFORE any transaction is opened, which is the corrected defect.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the provider is called before any settle transaction is opened")
    void theProviderIsCalledBeforeAnyTransactionIsOpened() {
        service.record(USER, IdentitySyncTask.OPERATION_WITHDRAW, null, null, null, null);

        assertThat(service.applyOwed(USER)).isTrue();

        // Assumptions: this ordering IS the finding. The three write paths previously called the provider
        //   from inside their own transaction, so a provider call that succeeded and was followed by a
        //   failed commit left the two stores disagreeing with no record of it. Asserting that the only
        //   transaction this service opens begins AFTER the provider has answered is what pins the
        //   correction; asserting the absence of a transaction directly is not expressible, because a
        //   substituted manager is the only thing that can report when a boundary was requested.
        InOrder ordered = inOrder(provisioning, transactions);
        ordered.verify(provisioning).withdraw(USER);
        ordered.verify(transactions).getTransaction(any());
    }

    /**
     * Asserts each of the two operations issues the provider call its intention names.
     *
     * <p>Refactoring Rationale: this case covered THREE operations, and the third was
     * {@code PROVISION} -- an intention no write path can record, because the create path has to
     * provision before it can insert the row an intention would commit with. The value and the applier's
     * branch for it were withdrawn together by {@code V3__auth_identity_sync_operations.sql}, so a third
     * arm here would assert behaviour the column's constraint now refuses to admit.</p>
     *
     * <p>Assumptions: the reconciliation count is asserted alongside the two calls, because a pass that
     * issued both calls and reported the wrong count would still leave an operator unable to tell a
     * drained ledger from a stalled one.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("each operation issues the provider call its intention names")
    void eachOperationIssuesItsOwnProviderCall() {
        service.record(USER, IdentitySyncTask.OPERATION_SYNCHRONISE, "Grace", "Hopper", "U", "A");
        service.record("USER0003", IdentitySyncTask.OPERATION_WITHDRAW, null, null, null, null);

        // Assumptions: the clock is advanced past the compensation grace before the pass runs, because the
        //   pass defers a task younger than that interval. The advance is what makes this case about which
        //   provider call each operation issues rather than about the deferral, which the case below owns.
        clock.advance(IdentitySyncService.COMPENSATION_GRACE.plusSeconds(1));

        assertThat(service.reconcile(10)).isEqualTo(2);

        verify(provisioning).synchronise(USER, "Grace", "Hopper", "U", "A");
        verify(provisioning).withdraw("USER0003");
        verify(provisioning, never()).provision(any(), any(), any(), any());
    }

    /**
     * Asserts an applied intention is settled and never applied a second time.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an applied intention is settled once and never applied twice")
    void anAppliedIntentionIsSettledOnce() {
        IdentitySyncTask recorded =
                service.record(USER, IdentitySyncTask.OPERATION_WITHDRAW, null, null, null, null);

        assertThat(service.applyOwed(USER)).isTrue();
        assertThat(recorded.getStatus()).isEqualTo(IdentitySyncTask.STATUS_APPLIED);
        assertThat(recorded.getSettledAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

        // Assumptions: the second drain finds nothing pending, so it issues no second provider call. This
        //   is what makes the post-commit application safe to repeat -- both the write path and the
        //   scheduled reconciliation pass can reach the same task.
        assertThat(service.applyOwed(USER)).isTrue();
        verify(provisioning).withdraw(USER);
    }

    /**
     * Asserts a provider fault leaves the intention pending with its attempt and digest recorded.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a provider fault leaves the intention pending with one attempt recorded")
    void aProviderFaultLeavesTheIntentionPending() {
        IdentitySyncTask recorded =
                service.record(USER, IdentitySyncTask.OPERATION_WITHDRAW, null, null, null, null);
        doThrow(InternalErrorException.builder().message("the pool is unavailable").build())
                .when(provisioning).withdraw(USER);

        // Assumptions: the drain reports the failure through its return value and raises nothing, because
        //   it runs after the caller's write has already committed. Raising would report a failed request
        //   for a change that took effect, and a client acting on that report would repeat it.
        assertThat(service.applyOwed(USER)).isFalse();

        assertThat(recorded.getStatus()).isEqualTo(IdentitySyncTask.STATUS_PENDING);
        assertThat(recorded.getAttempts()).isEqualTo(1);
        assertThat(recorded.getLastFailureCode())
                .as("the recorded cause is a stable digest, never the provider's own message")
                .isNotNull()
                .doesNotContain("unavailable");
    }

    /**
     * Asserts an intention is abandoned once the attempt ceiling is reached, not retried for ever.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an intention is abandoned once the attempt ceiling is reached")
    void anIntentionIsAbandonedAtTheCeiling() {
        IdentitySyncTask recorded =
                service.record(USER, IdentitySyncTask.OPERATION_WITHDRAW, null, null, null, null);
        doThrow(InternalErrorException.builder().message("the pool is unavailable").build())
                .when(provisioning).withdraw(USER);

        for (int attempt = 0; attempt < IdentitySyncService.ATTEMPT_CEILING; attempt++) {
            assertThat(service.applyOwed(USER)).isFalse();
        }

        // Assumptions: the counter advances once per pass, which is only true because the failure path
        //   FLUSHES the increment in a transaction of its own. An increment discarded by a rollback would
        //   turn this ceiling into an unbounded retry, and the count below is what would catch that.
        assertThat(recorded.getAttempts()).isEqualTo(IdentitySyncService.ATTEMPT_CEILING);
        assertThat(recorded.getStatus()).isEqualTo(IdentitySyncTask.STATUS_ABANDONED);
        assertThat(recorded.getSettledAt()).isNotNull();

        // Assumptions: an abandoned intention is no longer owed, so a later drain reads nothing and the
        //   provider is not called a sixth time. The count is asserted rather than the mere absence of a
        //   further call, because a ceiling that never advanced would show as a sixth call here.
        assertThat(service.applyOwed(USER)).isTrue();
        verify(provisioning, times(IdentitySyncService.ATTEMPT_CEILING)).withdraw(USER);
    }

    /**
     * Asserts a non-positive reconciliation bound is refused rather than read as an empty ledger.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws IllegalArgumentException always, raised by the service and captured by the assertion below
     */
    @Test
    @DisplayName("a non-positive reconciliation bound is refused")
    void aNonPositiveReconciliationBoundIsRefused() {
        assertThatThrownBy(() -> service.reconcile(0))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be positive");
    }

    /**
     * Asserts the scheduled pass swallows a store failure so one bad pass cannot silence the schedule.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the scheduled pass swallows a store failure")
    void theScheduledPassSwallowsAStoreFailure() {
        when(ledger.findByStatusOrderByTaskIdAsc(any(), any(Limit.class)))
                .thenThrow(new QueryTimeoutException("statement timed out"));

        // Assumptions: nothing is raised, because the scheduler's only response to an exception is to log
        //   it and run again on the next delay. Catching it lets this service log its own event and
        //   guarantees an unreachable store cannot stop the schedule.
        service.reconcileOnSchedule();

        verifyNoInteractions(provisioning);
    }

    /**
     * Asserts the scheduled pass DEFERS an intention younger than the compensation grace.
     *
     * <p>Purpose: this is the window the create path's arming opens and this interval closes. That path
     * commits its compensating withdrawal BEFORE it provisions the account, because a durable intention is
     * the only thing that survives a process death between provisioning and the insert -- which makes the
     * intention visible to this pass while the create is still running. A pass that applied it would
     * withdraw the account the create is about to bind a row to, leaving a row whose user cannot sign on at
     * all.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the scheduled pass defers an intention younger than the compensation grace")
    void theScheduledPassDefersAYoungIntention() {
        IdentitySyncTask armed =
                service.record(USER, IdentitySyncTask.OPERATION_WITHDRAW, null, null, null, null);

        // Assumptions: the clock is advanced to just INSIDE the grace rather than not at all, so the case
        //   fails if the boundary is written as an inclusive comparison in the wrong direction. A task
        //   recorded at the same instant the pass reads would satisfy either spelling.
        clock.advance(IdentitySyncService.COMPENSATION_GRACE.minusSeconds(1));

        assertThat(service.reconcile(10))
                .as("a deferred task is not an applied one, so the pass reports nothing applied")
                .isZero();

        verifyNoInteractions(provisioning);
        assertThat(armed.getStatus())
                .as("a deferred task stays PENDING, so a later pass still owns it")
                .isEqualTo(IdentitySyncTask.STATUS_PENDING);
        assertThat(armed.getAttempts())
                .as("a deferral is not an attempt, so the ceiling is not consumed by waiting")
                .isZero();

        // Assumptions: the same task IS applied once the interval has elapsed, which is what makes the
        //   deferral a delay rather than a suppression. Without this half the case would pass against a
        //   pass that never applied a withdrawal at all.
        clock.advance(Duration.ofSeconds(2));
        assertThat(service.reconcile(10)).isEqualTo(1);
        verify(provisioning).withdraw(USER);
    }

    /**
     * Asserts a pending withdrawal is abandoned rather than applied when a row still owns the account.
     *
     * <p>Purpose: a withdrawal is not unconditionally idempotent, which is the one asymmetry in this
     * ledger's idempotence. Removing an account twice is harmless; removing it after the identifier has
     * been created again destroys an account the NEW row owns. That state is reachable without any
     * programming error: a delete records a withdrawal, its provider call is lost, the withdrawal stays
     * pending, and the identifier is later created afresh.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a pending withdrawal is abandoned when a committed row still owns the account")
    void aPendingWithdrawalIsAbandonedWhenARowOwnsTheAccount() {
        IdentitySyncTask stale =
                service.record(USER, IdentitySyncTask.OPERATION_WITHDRAW, null, null, null, null);
        when(users.existsById(USER)).thenReturn(true);

        assertThat(service.applyOwed(USER))
                .as("a guarded withdrawal is CONVERGED rather than failed -- nothing is owed once a row"
                        + " owns the account")
                .isTrue();

        verify(provisioning, never()).withdraw(any());
        assertThat(stale.getStatus())
                .as("the task is settled rather than left pending, so an operator does not read it as"
                        + " unconverged for ever")
                .isEqualTo(IdentitySyncTask.STATUS_ABANDONED);
        assertThat(stale.getLastFailureCode())
                .isEqualTo(IdentitySyncService.ABANDONED_ROW_OWNS_ACCOUNT);
    }

    /**
     * Asserts a projection assignment is NOT guarded by the presence of a row.
     *
     * <p>Assumptions: only a withdrawal is destructive. A projection assignment renames an account and
     * moves one group membership, neither of which can admit a sign-on this context would refuse, so
     * guarding it would suppress a convergence for no gain.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a projection assignment is applied whether or not a row exists")
    void aProjectionAssignmentIsNotGuardedByTheRow() {
        service.record(USER, IdentitySyncTask.OPERATION_SYNCHRONISE, "Grace", "Hopper", "U", "A");
        when(users.existsById(USER)).thenReturn(true);

        assertThat(service.applyOwed(USER)).isTrue();

        verify(provisioning).synchronise(USER, "Grace", "Hopper", "U", "A");
    }

    /**
     * Asserts an ARMED withdrawal is applied even while a row exists, because its caller provisioned it.
     *
     * <p>Purpose: this is the one caller that legitimately skips the guard. A create whose insert was
     * refused as a duplicate provisioned an account of its own AND finds a row for that identifier -- the
     * row it collided with, which belongs to another writer and does not own this request's account.
     * Guarding there would leave exactly the orphan the compensation exists to remove.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an armed withdrawal is applied even while a row exists")
    void anArmedWithdrawalIsAppliedEvenWhileARowExists() {
        IdentitySyncTask armed =
                service.record(USER, IdentitySyncTask.OPERATION_WITHDRAW, null, null, null, null);
        when(users.existsById(USER)).thenReturn(true);

        assertThat(service.applyArmed(armed)).isTrue();

        verify(provisioning).withdraw(USER);
        assertThat(armed.getStatus()).isEqualTo(IdentitySyncTask.STATUS_APPLIED);
    }

    /**
     * Asserts an abandoned intention stops being owed and carries the reason it was abandoned for.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an abandoned intention is no longer owed and records why")
    void anAbandonedIntentionIsNoLongerOwed() {
        IdentitySyncTask armed =
                service.record(USER, IdentitySyncTask.OPERATION_WITHDRAW, null, null, null, null);

        service.abandon(armed.getTaskId(), "create-committed");

        assertThat(armed.getStatus()).isEqualTo(IdentitySyncTask.STATUS_ABANDONED);
        assertThat(armed.getLastFailureCode()).isEqualTo("create-committed");
        assertThat(armed.getSettledAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

        // Assumptions: a second settle is accepted silently rather than refused, because both callers mean
        //   the same thing -- nothing is owed. Raising would turn a successful create into a failure over a
        //   ledger row whose work is already done.
        service.abandon(armed.getTaskId(), "create-committed");

        clock.advance(IdentitySyncService.COMPENSATION_GRACE.plusSeconds(1));
        assertThat(service.reconcile(10))
                .as("an abandoned intention is invisible to the pass")
                .isZero();
        verifyNoInteractions(provisioning);
    }

    /**
     * A clock whose instant this class moves forward, so a case can age a task deterministically.
     *
     * <p>Assumptions: it extends {@link Clock} rather than wrapping one, because the service takes a
     * {@code Clock} and reads it repeatedly; a supplier would not fit that parameter. Every method the
     * service reaches is implemented, and {@link #withZone} returns a clock at the same instant so a
     * caller that re-zoned it would not silently get a fixed one.</p>
     */
    private static final class AdvanceableClock extends Clock {

        /** The instant this clock currently reports. */
        private Instant instant;

        /**
         * Builds the clock at one instant.
         *
         * @param start the instant to report until advanced; must not be {@code null}
         */
        AdvanceableClock(Instant start) {
            this.instant = start;
        }

        /**
         * Moves the reported instant forward.
         *
         * @param amount how far forward to move; must not be {@code null}
         */
        void advance(Duration amount) {
            this.instant = this.instant.plus(amount);
        }

        /**
         * Reports the zone this clock reads instants in.
         *
         * @return the UTC offset, because every stamp this ledger records is read at that offset
         */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * Yields this clock at another zone.
         *
         * @param zone the zone to read in
         * @return a fixed clock at the current instant in that zone; never {@code null}
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(this.instant, zone);
        }

        /**
         * Reports the current instant.
         *
         * @return the instant this clock has been advanced to; never {@code null}
         */
        @Override
        public Instant instant() {
            return this.instant;
        }
    }

    /**
     * Builds a ledger repository that keeps its rows in a map and assigns identifiers on save.
     *
     * <p>Assumptions: the identifier is assigned by reflection, because the column is declared
     * {@code GENERATED BY DEFAULT AS IDENTITY} and the entity therefore exposes no setter for it -- a
     * setter would let application code choose a value the sequence had not issued.</p>
     *
     * @return a substituted ledger repository backed by insertion-ordered storage; never {@code null}
     */
    private static IdentitySyncTaskRepository inMemoryLedger() {
        IdentitySyncTaskRepository stub = mock(IdentitySyncTaskRepository.class);
        Map<Long, IdentitySyncTask> rows = new LinkedHashMap<>();
        AtomicLong sequence = new AtomicLong();

        when(stub.save(any(IdentitySyncTask.class))).thenAnswer(call -> {
            IdentitySyncTask task = call.getArgument(0);
            if (task.getTaskId() == null) {
                ReflectionTestUtils.setField(task, "taskId", sequence.incrementAndGet());
            }
            rows.put(task.getTaskId(), task);
            return task;
        });
        when(stub.saveAndFlush(any(IdentitySyncTask.class)))
                .thenAnswer(call -> stub.save(call.getArgument(0)));
        when(stub.findById(any())).thenAnswer(
                call -> Optional.ofNullable(rows.get(call.<Long>getArgument(0))));
        when(stub.findByUserIdAndStatusOrderByTaskIdAsc(any(), any(), any(Limit.class)))
                .thenAnswer(call -> selectFrom(rows, call.getArgument(1), call.getArgument(0),
                        call.<Limit>getArgument(2)));
        when(stub.findByStatusOrderByTaskIdAsc(any(), any(Limit.class)))
                .thenAnswer(call -> selectFrom(rows, call.getArgument(0), null,
                        call.<Limit>getArgument(1)));
        return stub;
    }

    /**
     * Selects the stored ledger rows of one status, optionally for one user, in identifier order.
     *
     * @param rows the ledger's backing storage
     * @param status the status a row must carry to be selected
     * @param userId the user a row must name, or {@code null} to select every user's rows
     * @param limit the greatest number of rows to yield
     * @return the selected rows in ascending identifier order; never {@code null}
     */
    private static List<IdentitySyncTask> selectFrom(Map<Long, IdentitySyncTask> rows, String status,
            String userId, Limit limit) {
        return rows.values().stream()
                .filter(row -> status.equals(row.getStatus()))
                .filter(row -> userId == null || userId.equals(row.getUserId()))
                .sorted(Comparator.comparing(IdentitySyncTask::getTaskId))
                .limit(limit.max())
                .toList();
    }

    /**
     * Asserts a withdrawal is NOT carried out when a row for the identifier exists.
     *
     * <p>⚠️ Assumptions: this is the safety interlock on the create path's guard, and without it the
     * guard would be more dangerous than the gap it closes. The guard is committed BEFORE the provider
     * call and is closed by the insert, so a reconciliation pass that ran between a committed insert and
     * its guard closure -- or against a guard whose closure was lost -- would withdraw the pool account
     * of a user whose row exists and is being served. The row's existence is the authority: if it is
     * there, the account it names is legitimately owned and no withdrawal is owed.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a withdrawal whose user row exists is not carried out against the provider")
    void aWithdrawalIsNotCarriedOutWhenTheRowExists() {
        service.record(USER, IdentitySyncTask.OPERATION_WITHDRAW, null, null, null, null);
        when(users.existsById(USER)).thenReturn(true);

        assertThat(service.applyOwed(USER))
                .as("the pass settles the intention, because leaving it pending would retry a withdrawal"
                        + " that must never be made")
                .isTrue();

        verifyNoInteractions(provisioning);
    }

    /**
     * Asserts a withdrawal that stopped being owed is settled with a reason rather than as applied.
     *
     * <p>⚠️ Assumptions: the distinction is the operational record's honesty. Applied states that the
     * provider carried the withdrawal out. Recording this as applied would leave an audit trail
     * asserting that a live user's pool account had been withdrawn, which is both false and precisely
     * the kind of claim an operator would act on.</p>
     *
     * <p>⚠️ Refactoring Rationale: this case asserted a CANCELLED settle, and the expectation is
     * withdrawn rather than the case. {@link IdentitySyncTask#markCancelled} nulls the reason code and
     * means the intention was NEVER owed; this withdrawal was owed when a delete recorded it and stopped
     * being owed only because the identifier was created again, which is what
     * {@link IdentitySyncTask#markAbandoned} exists to record and why it demands a reason. Asserting
     * CANCELLED here also contradicted {@code aPendingWithdrawalIsAbandonedWhenARowOwnsTheAccount},
     * whose arrangement is identical to this one -- a settle cannot be both.</p>
     *
     * <p>Assumptions: the case is KEPT rather than folded into that sibling, because its lens is
     * different: it reads the LEDGER by status and so can assert that no applied row and no pending row
     * survive anywhere, which assertions on the returned task object cannot see.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a withdrawal that stopped being owed settles with a reason, never as applied")
    void aWithdrawalThatWasNotOwedSettlesAsCancelled() {
        service.record(USER, IdentitySyncTask.OPERATION_WITHDRAW, null, null, null, null);
        when(users.existsById(USER)).thenReturn(true);

        service.applyOwed(USER);

        assertThat(ledger.findByStatusOrderByTaskIdAsc(IdentitySyncTask.STATUS_APPLIED, Limit.of(10)))
                .as("nothing was applied, because nothing was carried out")
                .isEmpty();
        assertThat(ledger.findByStatusOrderByTaskIdAsc(IdentitySyncTask.STATUS_PENDING, Limit.of(10)))
                .as("the intention is settled, so no pass retries it")
                .isEmpty();
        assertThat(ledger.findByStatusOrderByTaskIdAsc(IdentitySyncTask.STATUS_ABANDONED, Limit.of(10)))
                .singleElement()
                .satisfies(settled -> assertThat(settled.getLastFailureCode())
                        .isEqualTo(IdentitySyncService.ABANDONED_ROW_OWNS_ACCOUNT));
    }

    /**
     * Asserts a synchronisation is still carried out when the row exists, which is the ordinary case.
     *
     * <p>⚠️ Assumptions: the row-existence interlock is asserted to be scoped to WITHDRAW alone, and the
     * scoping matters as much as the interlock. A synchronisation exists precisely BECAUSE a row exists --
     * it pushes that row's values to the provider -- so applying the same test to it would disable
     * synchronisation entirely and leave the two stores permanently divergent.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the row-existence interlock applies to withdrawals only, not to synchronisations")
    void theInterlockDoesNotSuppressSynchronisation() {
        service.record(USER, IdentitySyncTask.OPERATION_SYNCHRONISE, "Ada", "Lovelace", "A", null);
        when(users.existsById(USER)).thenReturn(true);

        assertThat(service.applyOwed(USER)).isTrue();

        verify(provisioning).synchronise(USER, "Ada", "Lovelace", "A", null);
    }

    /**
     * Asserts a withdrawal IS carried out when no row names the identifier, which is the orphan case.
     *
     * <p>Assumptions: this is the case the guard was introduced for -- a pool account left behind by an
     * interrupted create, with no row describing it. It is asserted alongside the suppression case so that
     * the interlock cannot be satisfied by suppressing every withdrawal, which would make the reconciler
     * inert while every one of its assertions still passed.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a withdrawal with no user row behind it is carried out, which is the orphan case")
    void aWithdrawalWithNoRowIsCarriedOut() {
        service.record(USER, IdentitySyncTask.OPERATION_WITHDRAW, null, null, null, null);
        when(users.existsById(USER)).thenReturn(false);

        assertThat(service.applyOwed(USER)).isTrue();

        verify(provisioning).withdraw(USER);
        assertThat(ledger.findByStatusOrderByTaskIdAsc(IdentitySyncTask.STATUS_APPLIED, Limit.of(10)))
                .as("a withdrawal that was carried out is recorded as applied")
                .hasSize(1);
    }
}
