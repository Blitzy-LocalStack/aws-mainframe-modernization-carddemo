package com.carddemo.auth.service;

import com.carddemo.auth.domain.User;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Moves a user's authority, changing the provider group membership that actually grants it and the
 * local column that records it, in that order and never one without the other.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class exists because the migration split one byte into two facts. In the baseline,
 * {@code app/cbl/COUSR02C.cbl} read the security record at L322, moved a new value into
 * {@code SEC-USR-TYPE} among its four update arms at L219 to L234, and rewrote it at L360; that byte
 * both named the user's role and conferred it, because {@code app/cbl/COADM01C.cbl} read the same file
 * back to decide what the user could reach. In the target, naming and conferring are separate: the
 * column {@code auth.users.user_type} names the role, while the authority a request is matched against
 * is produced by {@code com.carddemo.common.security.JwtRoleConverter} from the signed
 * {@code cognito:groups} claim. Moving the column alone therefore grants and revokes nothing, and
 * leaves a row that reads afterwards as though it had. This is the only place in the context permitted
 * to move that column, and it cannot do so without first moving the membership behind the claim.</p>
 *
 * <h2>Why the ordering is provider-first</h2>
 *
 * <p>Assumptions: the provider is changed before the local column, and the local column is never
 * assigned unless the provider accepted the change. A local-first ordering would report success to a
 * caller on the strength of a row it had written, while the authority that row describes had not moved;
 * that is the precise failure this class was introduced to remove, so reintroducing it as an ordering
 * would defeat the purpose. The cost of provider-first is the window described next, and it is the
 * lesser of the two.</p>
 *
 * <p>Trade-offs: between the provider accepting the change and the surrounding transaction committing,
 * the two disagree. That window is closed by a rollback compensation registered on the current
 * transaction: if the transaction rolls back after the provider agreed, the membership is put back
 * where it was. What is given up is that the compensation runs after completion and so cannot itself be
 * transactional -- a compensation that fails leaves a disagreement an operator must repair, and it is
 * logged at error level naming the identity and both types so that the repair is a single directed
 * action rather than an investigation. The alternative, a transactional outbox publishing the
 * membership change for a worker to apply, was rejected for this operation: the migration reserves the
 * outbox pattern for the authorization context's reply publication, where the message is the deliverable
 * and latency is not observable, whereas an administrator who has just changed a user's role expects
 * that user's next sign-on to reflect it. Deferring the provider call would also mean the local column
 * committed first, which is the local-first ordering already rejected above.</p>
 *
 * <p>Alternatives Considered: performing this coordination inside
 * {@code com.carddemo.auth.mapper.UserMapper}, alongside the two name assignments it already applies.
 * Rejected on the boundary that package's own charter draws: a mapper translates between a stored row
 * and a transport shape and holds no business behaviour, it has no provider dependency and should not
 * acquire one, and every service class in this reactor imports from its sibling mapper package while no
 * mapper imports from a service package. The mapper instead refuses a request whose type differs from
 * the row's, which is what routes the moving case here rather than leaving the routing to a caller's
 * memory.</p>
 */
@Service
public class UserAuthorityService {

    /** Where the diagnostics of an authority change are written. */
    private static final Logger LOG = LoggerFactory.getLogger(UserAuthorityService.class);

    /** The provider-facing operations that hold the membership behind the signed claim. */
    private final CognitoUserProvisioningService provisioning;

    /**
     * Binds the service to the provider-facing operations it coordinates with.
     *
     * @param provisioning the provider-facing provisioning operations, which own every call to the
     *     identity provider made on behalf of this context; must not be {@code null}
     * @throws NullPointerException when {@code provisioning} is null, which would leave a bean that
     *     could move a local column with no way to move the authority behind it
     */
    public UserAuthorityService(CognitoUserProvisioningService provisioning) {
        this.provisioning =
                Objects.requireNonNull(provisioning, "provisioning must not be null");
    }

    /**
     * Sets a loaded user's reference type to a requested value, moving the provider group membership
     * first and assigning the column only once the provider has agreed.
     *
     * <p>Assumptions: the {@code User} handed in is managed by the current transaction, exactly as the
     * baseline's own update held the record it had read under the {@code UPDATE} option at
     * {@code app/cbl/COUSR02C.cbl} L328 and rewrote that same record area at L360. Nothing is saved
     * here and no repository is reached for: the assignment is visible to the persistence provider's
     * dirty checking, and a statement is issued when the surrounding transaction flushes. That is also
     * what makes the rollback compensation meaningful -- the column and the membership are bound to the
     * same commit decision.</p>
     *
     * <p>Assumptions: a request to set the type to the value already held is answered without calling
     * the provider. There is no membership to move, so a call would be a mutation with no subject; the
     * evidence returned records the alignment through
     * {@link AuthorityReassignment#unchanged(String, String)} and carries {@code providerMutated} false,
     * which is what tells a reader of a log line that nothing was changed rather than that a change
     * was made and happened to match.</p>
     *
     * @param user the managed row whose type is to be set, loaded in the current transaction; must not
     *     be {@code null}
     * @param requestedUserType the reference type it is to hold, {@code "A"} or {@code "U"} per
     *     {@code app/cpy/COCOM01Y.cpy} L27 and L28; must not be {@code null}
     * @return evidence of the state the authority now stands in, whether it was moved or already
     *     aligned; never {@code null}
     * @throws NullPointerException when {@code user} or {@code requestedUserType} is null
     * @throws IllegalArgumentException if {@code requestedUserType} is outside the two-value domain,
     *     raised by the provider-facing call before any membership is touched
     * @throws software.amazon.awssdk.core.exception.SdkException if the provider refused or could not be
     *     reached, in which case the membership has been restored and the local column is untouched, so
     *     the caller's transaction may roll back with nothing to undo
     */
    public AuthorityReassignment reassign(User user, String requestedUserType) {
        Objects.requireNonNull(user, "user must not be null when reassigning authority");
        Objects.requireNonNull(
                requestedUserType, "requestedUserType must not be null when reassigning authority");

        String userId = user.getUserId();
        String heldUserType = user.getUserType();

        if (requestedUserType.equals(heldUserType)) {
            return AuthorityReassignment.unchanged(userId, heldUserType);
        }

        AuthorityReassignment reassignment =
                this.provisioning.reassignGroup(userId, heldUserType, requestedUserType);

        registerRollbackCompensation(reassignment);

        // WHY : Assumptions: this assignment is deliberately the LAST statement of the method, after the
        //       provider call and after the compensation is registered. Assigning before registering
        //       would leave a window in which a rollback undoes the column and leaves the membership
        //       moved, which is the disagreement in the direction that grants authority nobody recorded.
        user.setUserType(requestedUserType);

        return reassignment;
    }

    /**
     * Arranges for the provider membership to be put back if the current transaction rolls back.
     *
     * <p>Assumptions: the reversal is registered rather than performed, and it runs on completion rather
     * than on rollback specifically, because only the completion callback is invoked for every outcome
     * and can therefore tell a commit from a rollback by the status it is given. A callback registered
     * to run before commit could not see a failure that happens during the commit itself.</p>
     *
     * <p>Trade-offs: when no transaction is active the compensation cannot be registered, and the method
     * logs that rather than refusing the operation. Refusing would make the class unusable from any
     * caller that has not yet declared a transaction boundary and would convert a diagnosable gap into
     * an outage; proceeding silently would hide the one condition under which the window this class
     * describes stays open. The warning names the identity so that a caller reaching this state is
     * visible in the same place the reassignment itself is recorded.</p>
     *
     * @param reassignment the evidence of the move to reverse, whose previous and current types name the
     *     direction to undo; must not be {@code null}
     */
    private void registerRollbackCompensation(AuthorityReassignment reassignment) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            LOG.warn("event=auth.identity.authority-uncompensated userId={} reason=no-transaction",
                    reassignment.userId());
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            /**
             * Reverses the membership when the transaction that requested it did not commit.
             *
             * @param status the completion status the transaction manager reports, compared against its
             *     rolled-back constant rather than against a committed one so that an unknown outcome is
             *     treated as not-committed and is compensated
             */
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                try {
                    UserAuthorityService.this.provisioning.reassignGroup(
                            reassignment.userId(),
                            reassignment.currentUserType(),
                            reassignment.previousUserType());
                    LOG.warn("event=auth.identity.authority-rolled-back userId={} restoredType={}",
                            reassignment.userId(), reassignment.previousUserType());
                } catch (RuntimeException compensationFailure) {
                    // WHY : Assumptions: this failure is logged and NOT rethrown, because a completion
                    //       callback runs after the transaction has already finished and an exception
                    //       from it cannot change that outcome -- it would only be swallowed by the
                    //       transaction manager or, worse, mask the original failure that caused the
                    //       rollback. The line is therefore the deliverable: it names the identity and
                    //       the type the membership should be returned to, which is everything an
                    //       operator needs to repair it in one action.
                    LOG.error("event=auth.identity.authority-divergent userId={} providerType={} "
                                    + "rowType={} action=restore-membership",
                            reassignment.userId(),
                            reassignment.currentUserType(),
                            reassignment.previousUserType(),
                            compensationFailure);
                }
            }
        });
    }
}
