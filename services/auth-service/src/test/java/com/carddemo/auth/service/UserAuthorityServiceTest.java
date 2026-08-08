package com.carddemo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.auth.domain.User;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Asserts that a user's local reference type never moves unless the identity provider's group membership
 * moved first, and that it is put back when the transaction that moved it does not commit.
 *
 * <h2>Purpose</h2>
 *
 * <p>The review finding this class answers is that updating {@code auth.users.user_type} added and removed
 * no Cognito group, so an {@code 'A'}/{@code 'U'} change granted and revoked nothing while reporting
 * success. Effective authority comes from the signed {@code cognito:groups} claim by way of
 * {@code com.carddemo.common.security.JwtRoleConverter}; the column only names it. Two properties therefore
 * have to hold and neither is visible from a return value: the provider is called BEFORE the column is
 * assigned, and a provider failure leaves the column exactly as it was. Both are asserted below against the
 * mutation the service performs on a real entity rather than against a mock of it, because the defect being
 * prevented is a column that moved.</p>
 *
 * <p>Assumptions: the entity is a genuine {@code User} and only the provider is substituted. A substituted
 * entity would let an assertion pass on a verified call that never changed a value, which is the exact
 * shape of the defect -- a report of success with no state behind it.</p>
 *
 * <p>Trade-offs: the rollback path is exercised by driving the registered transaction synchronization
 * directly rather than by opening a real transaction against a database. What is given up is that nothing
 * here proves Spring invokes the callback with the status this class expects. What is gained is that the
 * compensation's own behaviour -- the direction it reverses, and that a failing compensation neither throws
 * nor is silent -- is asserted in isolation, where a database-backed test would have coupled it to a
 * container and still not have covered the failing-compensation branch.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * at-clause; the convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
class UserAuthorityServiceTest {

    /** The identifier under test, at the eight characters {@code SEC-USR-ID PIC X(08)} declares. */
    private static final String USER_ID = "TESTUSR1";

    /** The subject the row is bound to, which no operation in this class may change. */
    private static final UUID SUBJECT = UUID.fromString("11111111-2222-4333-8444-555555555555");

    /** The substituted provider-facing operations whose calls are captured. */
    private CognitoUserProvisioningService provisioning;

    /** The subject under test. */
    private UserAuthorityService service;

    /**
     * Re-creates the substitute and the subject before each test.
     *
     * <p>Assumptions: a fresh substitute per test is what keeps the no-interaction assertions meaningful,
     * since a shared one would carry the previous test's calls.</p>
     */
    @BeforeEach
    void setUp() {
        this.provisioning = mock(CognitoUserProvisioningService.class);
        this.service = new UserAuthorityService(this.provisioning);
    }

    /**
     * Clears any synchronization a test activated, so one test's transaction state cannot reach another.
     *
     * <p>Assumptions: the manager holds its state in a thread local, and the test framework reuses threads,
     * so an activated synchronization that is not cleared would make a later test's no-transaction branch
     * take the registered path instead. Clearing unconditionally is safe because the manager tolerates a
     * clear when nothing is active.</p>
     */
    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * Builds a row holding the given reference type.
     *
     * @param userType the reference type the row is to hold
     * @return a row under test; never {@code null}
     */
    private static User userHolding(String userType) {
        return new User(USER_ID, "Ada", "Lovelace", userType, SUBJECT);
    }

    /**
     * Verifies a promotion moves the provider membership and only then assigns the column.
     */
    @Test
    @DisplayName("a promotion calls the provider and then assigns the column")
    void aPromotionCallsTheProviderAndThenAssignsTheColumn() {
        User user = userHolding(CognitoUserProvisioningService.USER_TYPE_USER);
        when(this.provisioning.reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_USER,
                CognitoUserProvisioningService.USER_TYPE_ADMIN))
                .thenReturn(new AuthorityReassignment(USER_ID,
                        CognitoUserProvisioningService.USER_TYPE_USER,
                        CognitoUserProvisioningService.USER_TYPE_ADMIN, true));

        AuthorityReassignment reassignment =
                this.service.reassign(user, CognitoUserProvisioningService.USER_TYPE_ADMIN);

        verify(this.provisioning).reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_USER,
                CognitoUserProvisioningService.USER_TYPE_ADMIN);
        assertThat(user.getUserType()).isEqualTo(CognitoUserProvisioningService.USER_TYPE_ADMIN);
        assertThat(reassignment.providerMutated()).isTrue();
    }

    /**
     * Verifies a provider refusal leaves the column exactly as it was loaded.
     *
     * <p>Assumptions: this is the assertion the finding turns on. A column that moved while the provider
     * refused is a row asserting an authority that was never granted, and on the demotion direction it is a
     * row asserting an authority was withdrawn when the user still holds it. The refusal is expected to
     * propagate as well, because a caller that is not told cannot roll back.</p>
     */
    @Test
    @DisplayName("a provider refusal propagates and leaves the reference type untouched")
    void aProviderRefusalLeavesTheReferenceTypeUntouched() {
        User user = userHolding(CognitoUserProvisioningService.USER_TYPE_ADMIN);
        IllegalStateException refused = new IllegalStateException("provider unreachable");
        when(this.provisioning.reassignGroup(anyString(), anyString(), anyString()))
                .thenThrow(refused);

        assertThatThrownBy(
                () -> this.service.reassign(user, CognitoUserProvisioningService.USER_TYPE_USER))
                .isSameAs(refused);

        assertThat(user.getUserType()).isEqualTo(CognitoUserProvisioningService.USER_TYPE_ADMIN);
    }

    /**
     * Verifies a request for the type already held calls the provider not at all.
     *
     * <p>Assumptions: no provider call is the assertion, not merely an unchanged column. Coupling every
     * name-only edit to provider availability would make an update fail for a change that has nothing to
     * synchronize, and the evidence returned records the distinction so a log line does not read as though a
     * move had been made.</p>
     */
    @Test
    @DisplayName("a request for the type already held touches the provider not at all")
    void aRequestForTheTypeAlreadyHeldTouchesTheProviderNotAtAll() {
        User user = userHolding(CognitoUserProvisioningService.USER_TYPE_USER);

        AuthorityReassignment reassignment =
                this.service.reassign(user, CognitoUserProvisioningService.USER_TYPE_USER);

        verifyNoInteractions(this.provisioning);
        assertThat(user.getUserType()).isEqualTo(CognitoUserProvisioningService.USER_TYPE_USER);
        assertThat(reassignment.providerMutated()).isFalse();
        assertThat(reassignment.previousUserType()).isEqualTo(reassignment.currentUserType());
    }

    /**
     * Verifies a rollback after a successful move reverses the provider membership.
     *
     * <p>Assumptions: the reversal's arguments are asserted in the reverse direction, because a compensation
     * that called the provider with the ORIGINAL argument order would complete the move again rather than
     * undo it, and a test asserting only that a call happened would not tell those apart.</p>
     */
    @Test
    @DisplayName("a rolled-back transaction reverses the provider membership")
    void aRolledBackTransactionReversesTheProviderMembership() {
        User user = userHolding(CognitoUserProvisioningService.USER_TYPE_USER);
        when(this.provisioning.reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_USER,
                CognitoUserProvisioningService.USER_TYPE_ADMIN))
                .thenReturn(new AuthorityReassignment(USER_ID,
                        CognitoUserProvisioningService.USER_TYPE_USER,
                        CognitoUserProvisioningService.USER_TYPE_ADMIN, true));
        TransactionSynchronizationManager.initSynchronization();

        this.service.reassign(user, CognitoUserProvisioningService.USER_TYPE_ADMIN);

        List<TransactionSynchronization> registered =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(registered).hasSize(1);
        registered.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(this.provisioning).reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_ADMIN,
                CognitoUserProvisioningService.USER_TYPE_USER);
    }

    /**
     * Verifies a committed transaction reverses nothing.
     *
     * <p>Assumptions: the callback is invoked with the committed status rather than skipped, so the branch
     * that decides not to compensate is the thing under test. Not invoking it at all would leave that branch
     * uncovered and a compensation that ran on every completion would silently undo every successful
     * change.</p>
     */
    @Test
    @DisplayName("a committed transaction reverses nothing")
    void aCommittedTransactionReversesNothing() {
        User user = userHolding(CognitoUserProvisioningService.USER_TYPE_ADMIN);
        when(this.provisioning.reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_ADMIN,
                CognitoUserProvisioningService.USER_TYPE_USER))
                .thenReturn(new AuthorityReassignment(USER_ID,
                        CognitoUserProvisioningService.USER_TYPE_ADMIN,
                        CognitoUserProvisioningService.USER_TYPE_USER, true));
        TransactionSynchronizationManager.initSynchronization();

        this.service.reassign(user, CognitoUserProvisioningService.USER_TYPE_USER);
        TransactionSynchronizationManager.getSynchronizations().get(0)
                .afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(this.provisioning).reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_ADMIN,
                CognitoUserProvisioningService.USER_TYPE_USER);
        verify(this.provisioning, org.mockito.Mockito.never()).reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_USER,
                CognitoUserProvisioningService.USER_TYPE_ADMIN);
    }

    /**
     * Verifies a compensation that itself fails does not escape the completion callback.
     *
     * <p>Assumptions: a throw from a completion callback cannot change an outcome that has already been
     * decided, and would mask the failure that caused the rollback. The divergence is a logged operator
     * action instead, so the assertion here is that the callback returns normally.</p>
     */
    @Test
    @DisplayName("a compensation that fails does not throw out of the completion callback")
    void aCompensationThatFailsDoesNotThrowOutOfTheCallback() {
        User user = userHolding(CognitoUserProvisioningService.USER_TYPE_USER);
        when(this.provisioning.reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_USER,
                CognitoUserProvisioningService.USER_TYPE_ADMIN))
                .thenReturn(new AuthorityReassignment(USER_ID,
                        CognitoUserProvisioningService.USER_TYPE_USER,
                        CognitoUserProvisioningService.USER_TYPE_ADMIN, true));
        when(this.provisioning.reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_ADMIN,
                CognitoUserProvisioningService.USER_TYPE_USER))
                .thenThrow(new IllegalStateException("compensation refused"));
        TransactionSynchronizationManager.initSynchronization();

        this.service.reassign(user, CognitoUserProvisioningService.USER_TYPE_ADMIN);
        TransactionSynchronization registered =
                TransactionSynchronizationManager.getSynchronizations().get(0);

        registered.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    }

    /**
     * Verifies a move made outside a transaction still happens, and registers nothing it cannot honour.
     *
     * <p>Assumptions: refusing the operation for want of a transaction was rejected as a resolution, because
     * it would convert a diagnosable gap into an outage for any caller that has not declared a boundary. The
     * assertion is therefore that the move completes; the warning that no compensation is possible is the
     * service's own record of the open window.</p>
     */
    @Test
    @DisplayName("a move outside a transaction completes and registers no compensation")
    void aMoveOutsideATransactionCompletes() {
        User user = userHolding(CognitoUserProvisioningService.USER_TYPE_USER);
        when(this.provisioning.reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_USER,
                CognitoUserProvisioningService.USER_TYPE_ADMIN))
                .thenReturn(new AuthorityReassignment(USER_ID,
                        CognitoUserProvisioningService.USER_TYPE_USER,
                        CognitoUserProvisioningService.USER_TYPE_ADMIN, true));

        this.service.reassign(user, CognitoUserProvisioningService.USER_TYPE_ADMIN);

        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(user.getUserType()).isEqualTo(CognitoUserProvisioningService.USER_TYPE_ADMIN);
    }

    /**
     * Verifies the evidence type refuses an incomplete instance.
     *
     * <p>Assumptions: the canonical constructor is public because a record's constructor cannot be narrower
     * than the record, so the guard it carries is the only protection against a partly assembled instance
     * reaching the column assignment. A null type would render as the string {@code "null"} in the
     * diagnostic line and would compare unequal to every admitted value, so it is refused where it is
     * built.</p>
     */
    @Test
    @DisplayName("the evidence type refuses a null component")
    void theEvidenceTypeRefusesANullComponent() {
        assertThatThrownBy(() -> new AuthorityReassignment(USER_ID, null,
                CognitoUserProvisioningService.USER_TYPE_ADMIN, true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("previousUserType");
    }

    /**
     * Verifies the evidence renders both type letters and the mutation flag.
     *
     * <p>Assumptions: which way an authority moved is the whole subject of this value, so a rendering that
     * withheld the two letters would leave a line with nothing worth reading. The rendering carries no
     * account identifier, customer identifier, monetary amount or card verification value, which is what
     * {@code docs/architecture/observability.md} withholds.</p>
     */
    @Test
    @DisplayName("the evidence renders both reference types and whether the provider was called")
    void theEvidenceRendersBothReferenceTypes() {
        String rendered = AuthorityReassignment.unchanged(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_USER).toString();

        assertThat(rendered).contains(USER_ID, "previousUserType=U", "currentUserType=U",
                "providerMutated=false");
    }
}
