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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
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

    private CognitoUserProvisioningService provisioning;

    private PlatformTransactionManager transactions;

    private IdentitySyncService service;

    /**
     * Builds the ledger service over an in-memory ledger, a substituted provider and a fixed clock.
     *
     * <p>This method takes no parameter and yields no value. It raises nothing under normal operation.</p>
     */
    @BeforeEach
    void buildService() {
        ledger = inMemoryLedger();
        provisioning = mock(CognitoUserProvisioningService.class);
        transactions = mock(PlatformTransactionManager.class);
        service = new IdentitySyncService(ledger, provisioning,
                Clock.fixed(NOW, ZoneOffset.UTC), transactions);
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
}
