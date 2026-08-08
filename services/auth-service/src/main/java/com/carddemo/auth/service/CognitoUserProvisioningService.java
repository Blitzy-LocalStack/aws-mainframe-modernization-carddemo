package com.carddemo.auth.service;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminRemoveUserFromGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

/**
 * Creates the managed-identity account a user row authenticates as, assigns its group, and reports
 * the subject the provider minted for it.
 *
 * <h2>Why this exists: the subject must be produced here, not accepted from a caller</h2>
 *
 * <p>Refactoring Rationale: {@code CreateUserRequest} used to carry a {@code cognitoSub} component
 * that a caller filled in, documented as "supplied by whatever provisioned that account, because this
 * service creates no pool account". Two things were wrong with that arrangement, and only the second
 * is obvious. The obvious one is that it made an external act a precondition of a published operation,
 * so a row could be created against a subject that did not exist. The consequential one is that the
 * subject is the ONLY link between a presented token and a row -- {@code auth.users.cognito_sub} is
 * {@code UUID NOT NULL UNIQUE} -- so a caller choosing it was choosing which pool identity the new row
 * authenticates as. An administrator could bind a row to another person's subject, or to a subject
 * whose pool account holds the administrator group while the row records {@code 'U'}; the signed group
 * claim wins every authorization decision, so the row's own type would be decoration. No validation of
 * a UUID can detect either case.</p>
 *
 * <p>Assumptions: producing the subject here removes the choice rather than checking it. The provider
 * assigns the subject when it creates the account, this class reads it back out of that same response,
 * and the row is written from a value no request could influence.</p>
 *
 * <h2>Assumptions: the provider, named once</h2>
 *
 * <p>The managed identity provider is Amazon Cognito, reached through its administrative user-pool
 * API. The package charter beside this file states the responsibility as identity exchange and
 * deliberately does not name the provider, so that the provider can be named in the one class that
 * talks to it -- which is this one, and the sign-on exchange when it lands.</p>
 *
 * <h2>Trade-offs: provisioning is separated from the sign-on exchange</h2>
 *
 * <p>This class does not authenticate anyone and holds no credential. Sign-on belongs to a sibling in
 * this package that this migration's configuration names, and the separation is deliberate on two
 * grounds rather than incidental. The two use disjoint provider APIs -- an administrative
 * user-management API here, an authentication API there -- and they need disjoint permissions: this
 * one needs {@code AdminCreateUser}, {@code AdminAddUserToGroup} and {@code AdminDeleteUser} on the
 * pool, which are exactly the permissions a request-time sign-on path must NOT hold. Folding both into
 * one class would mean one task role carrying both sets, so a defect on the unauthenticated sign-on
 * path would reach account creation.</p>
 *
 * <h2>Trade-offs: no credential is created, and the handover is stated rather than implemented</h2>
 *
 * <p>The account is created with the provider's message delivery suppressed and with no temporary
 * credential supplied, so the provider generates one it does not deliver and the account stands in its
 * force-change state. That is not an oversight, and it is the same decision the published contract
 * already records: {@code CreateUserRequest} carries no password property, because the reference
 * system's handling of one is the defect this migration is undoing -- {@code app/cpy/CSUSR01Y.cpy} L21
 * stores {@code SEC-USR-PWD PIC X(08)} in the clear, {@code app/cbl/COSGN00C.cbl} L223 compares it in
 * the clear, and {@code app/cbl/COUSR02C.cbl} L169 writes it back out onto a screen. Returning a
 * generated credential from this operation would put one on an outbound path again, in the one service
 * whose whole purpose is that credentials stop travelling.</p>
 *
 * <p>Assumptions: the first credential therefore reaches its owner the way every other one in this
 * deployment does -- through the provider's own administrative reset, whose generated values the
 * infrastructure writes to Secrets Manager rather than to a response body. The seed identities are
 * established the same way, by {@code infra/modules/cognito/seed_user_bootstrap.py}, and this class
 * deliberately mirrors that script's call shape so that a runtime-created account and a seeded one are
 * indistinguishable afterwards: the same three attributes, the same suppressed delivery, and group
 * membership as a separate act.</p>
 *
 * <h2>Assumptions: a caller that fails after provisioning must withdraw</h2>
 *
 * <p>The pool account must exist before the row can carry its subject, so provisioning happens first
 * and the row is written second. If the write then fails -- most likely on the primary key, which is
 * what refuses a duplicate identifier -- the pool account would survive with no row referring to it.
 * {@link #withdraw(String)} exists for exactly that path and is the caller's obligation, because only
 * the caller knows whether its transaction committed. It is idempotent, so a caller may invoke it
 * without first establishing whether the account was reached.</p>
 *
 * <h2>Where this is called from</h2>
 *
 * <p>Assumptions: the one caller is the create-user path -- the handler behind {@code POST
 * /api/v1/auth/users}, which the migration plan assigns to {@code com.carddemo.auth.api} and
 * {@code UserService} in this package. Neither is authored yet, and the state of this package is
 * recorded the same way {@code com.carddemo.auth.config}'s charter records its own: by naming what is
 * present and what the contract still owes, rather than by leaving a reader to infer it. The order
 * that handler must follow is fixed by the paragraph above -- provision, then write the row with the
 * returned subject, then {@link #withdraw(String)} if the write fails -- and is stated here because
 * this class is where the ordering constraint originates.</p>
 *
 * <p>Alternatives Considered: deferring this class until that handler exists, so that no bean is
 * present without a call site. Rejected because the two halves of the fix are separable and only one
 * of them is a capability. Withdrawing the subject from
 * {@code com.carddemo.auth.dto.CreateUserRequest} closed the exposure immediately, but it also
 * removed the only way a subject could reach a row; leaving no server-side provisioner would have
 * replaced a caller-chosen subject with no subject at all, which the {@code NOT NULL} column refuses.
 * Landing the provisioner with the withdrawal keeps the contract satisfiable at every point.</p>
 */
@Service
public class CognitoUserProvisioningService {

    /**
     * The provider attribute carrying the given name.
     *
     * <p>Assumptions: {@code given_name} is a Cognito standard attribute and is the mapping the
     * infrastructure already fixes for {@code SEC-USR-FNAME PIC X(20)} at
     * {@code app/cpy/CSUSR01Y.cpy} L19, recorded at L447 of {@code infra/modules/cognito/main.tf}.
     * Using the same name is what makes a runtime-created account readable by the same app-client
     * read-attribute list the module declares.</p>
     */
    static final String ATTRIBUTE_GIVEN_NAME = "given_name";

    /**
     * The provider attribute carrying the family name.
     *
     * <p>Assumptions: the counterpart of the above for {@code SEC-USR-LNAME PIC X(20)} at
     * {@code app/cpy/CSUSR01Y.cpy} L20, mapped at L448 of the same Terraform file.</p>
     */
    static final String ATTRIBUTE_FAMILY_NAME = "family_name";

    /**
     * The custom provider attribute carrying the reference user type.
     *
     * <p>Assumptions: the {@code custom:} prefix is part of the attribute's name and not decoration --
     * Cognito prefixes every non-standard attribute, so omitting it names a standard attribute that
     * does not exist and the call is refused. The mapping from {@code SEC-USR-TYPE PIC X(01)} at
     * {@code app/cpy/CSUSR01Y.cpy} L22 is fixed at L450 of {@code infra/modules/cognito/main.tf}.</p>
     */
    static final String ATTRIBUTE_USER_TYPE = "custom:user_type";

    /**
     * The provider attribute carrying the minted subject.
     *
     * <p>Assumptions: {@code sub} is assigned by the provider at creation and is returned among the
     * created account's attributes, which is why this class reads it from the create response rather
     * than issuing a second read. It is the value {@code auth.users.cognito_sub} stores.</p>
     */
    static final String ATTRIBUTE_SUBJECT = "sub";

    /**
     * The reference user-type value denoting an administrator.
     *
     * <p>Assumptions: {@code 'A'} and {@code 'U'} are the whole domain, from the condition names at
     * {@code app/cpy/COCOM01Y.cpy} L27 and L28. There is no third value and no default arm below.</p>
     */
    static final String USER_TYPE_ADMIN = "A";

    /** The reference user-type value denoting an ordinary user, from {@code COCOM01Y.cpy} L28. */
    static final String USER_TYPE_USER = "U";

    /** Records provisioning outcomes for an operator; carries no attribute value and no subject. */
    private static final Logger LOG = LoggerFactory.getLogger(CognitoUserProvisioningService.class);

    /** The administrative provider client this class issues its three calls through. */
    private final CognitoIdentityProviderClient provider;

    /** The user pool every call below is scoped to. */
    private final String userPoolId;

    /** The group an administrator row's account is added to. */
    private final String adminGroupName;

    /** The group an ordinary user row's account is added to. */
    private final String userGroupName;

    /**
     * Binds the provider client, the pool and the two group names.
     *
     * <p>Assumptions: the pool identifier and the two group names are read from configuration keys
     * that already exist for other purposes rather than from new ones. The pool is
     * {@code carddemo.auth.cognito.user-pool-id}, which the profile document declares with no
     * fallback so a missing value stops startup instead of pointing provisioning at nothing. The group
     * names are {@code carddemo.security.cognito.admin-group-name} and its sibling, which the filter
     * chain already resolves an authority against -- so a pool whose groups were renamed cannot leave
     * this class assigning one name while authorization matched another.</p>
     *
     * @param provider the administrative provider client; must not be {@code null}
     * @param userPoolId the pool every call is scoped to; must not be blank
     * @param adminGroupName the group name for the {@code 'A'} user type; must not be blank
     * @param userGroupName the group name for the {@code 'U'} user type; must not be blank
     */
    public CognitoUserProvisioningService(CognitoIdentityProviderClient provider,
            @Value("${carddemo.auth.cognito.user-pool-id}") String userPoolId,
            @Value("${carddemo.security.cognito.admin-group-name}") String adminGroupName,
            @Value("${carddemo.security.cognito.user-group-name}") String userGroupName) {
        this.provider = provider;
        this.userPoolId = userPoolId;
        this.adminGroupName = adminGroupName;
        this.userGroupName = userGroupName;
    }

    /**
     * Creates the pool account for a new user row, adds it to the group its type selects, and returns
     * the subject the provider minted.
     *
     * <p>Assumptions: the order of the two provider calls is fixed and is not interchangeable. The
     * account has to exist before it can be added to a group, and the subject is only available from
     * the create response, so a failure of the membership call leaves an account that authenticates
     * but carries no authority -- which is why that failure propagates rather than being absorbed. An
     * account with no group is refused at every guarded route, so the visible outcome of a partial
     * provisioning is a caller who can sign on and do nothing, not a caller with unintended authority.
     *
     * @param userId the row's identifier and the provider username, at most the eight characters
     *     {@code SEC-USR-ID PIC X(08)} declares at {@code app/cpy/CSUSR01Y.cpy} L18; must not be
     *     {@code null}
     * @param firstName the given name to record on the account; must not be {@code null}
     * @param lastName the family name to record on the account; must not be {@code null}
     * @param userType {@code "A"} or {@code "U"}, the whole domain at {@code app/cpy/COCOM01Y.cpy}
     *     L27 and L28; must not be {@code null}
     * @return the subject the provider assigned, which is the value {@code auth.users.cognito_sub}
     *     stores and the only link between a presented token and the row; never {@code null}
     * @throws IllegalArgumentException if {@code userType} is outside the two-value domain, raised
     *     before any provider call so an out-of-domain value creates nothing
     * @throws IllegalStateException if the created account carries no subject attribute, which would
     *     mean the provider's response did not describe the account it had just created
     * @throws software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException
     *     if the pool already holds this username, which means a previous attempt provisioned an
     *     account whose row was never written; the remedy is operational and the condition is
     *     deliberately not translated into a client-facing conflict, because the authority for a
     *     duplicate identifier is the primary key on {@code auth.users} and not the pool
     */
    public UUID provision(String userId, String firstName, String lastName, String userType) {
        String groupName = groupFor(userType);

        AdminCreateUserResponse created = this.provider.adminCreateUser(AdminCreateUserRequest
                .builder()
                .userPoolId(this.userPoolId)
                .username(userId)
                // WHY : Assumptions: delivery is SUPPRESSED and no temporary credential is supplied,
                //       which together mean the provider generates one and sends nothing. The pool
                //       declares no email or phone attribute -- its schema is the two names and the
                //       custom type -- so there is no address a message could be delivered to, and an
                //       unsuppressed call would fail rather than notify anyone. The seed bootstrap at
                //       infra/modules/cognito/seed_user_bootstrap.py makes the same choice at its own
                //       AdminCreateUser payload, so a runtime account and a seeded one arrive in the
                //       same state.
                .messageAction(MessageActionType.SUPPRESS)
                .userAttributes(
                        attribute(ATTRIBUTE_GIVEN_NAME, firstName),
                        attribute(ATTRIBUTE_FAMILY_NAME, lastName),
                        attribute(ATTRIBUTE_USER_TYPE, userType))
                .build());

        UUID subject = subjectOf(created, userId);

        // WHY : Assumptions: membership is a separate call rather than an attribute of the account,
        //       which mirrors the infrastructure's own decision to hold it as a separate resource --
        //       recorded above aws_cognito_user_in_group in infra/modules/cognito/main.tf. The
        //       consequence that matters is that a later role change is a membership change and does
        //       not touch the identity or its credential, so a user whose type changes keeps the
        //       subject their row is bound to.
        this.provider.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(this.userPoolId)
                .username(userId)
                .groupName(groupName)
                .build());

        // WHY : Assumptions: the line records the identifier and the group and NOT the subject. The
        //       subject is the value a presented token is matched on, so a log store holding it holds
        //       the linkage between a person and their token claims; the identifier is already the
        //       row's primary key and is disclosed by every other line about this request.
        LOG.info("event=auth.identity.provisioned userId={} group={}", userId, groupName);

        return subject;
    }

    /**
     * Moves an identity's group membership from the group one reference type selects to the group
     * another selects, restoring the original membership if the second half of the move fails.
     *
     * <p>Purpose: this is the operation that gives a user-type change its effect. The local
     * {@code auth.users.user_type} column names an authority but confers none: every authority a
     * request is matched against is derived by {@code com.carddemo.common.security.JwtRoleConverter}
     * from the signed {@code cognito:groups} claim, so until the membership behind that claim moves,
     * a promoted user is still refused every administrative route and a demoted one still reaches
     * every one of them. The baseline had no equivalent, because {@code app/cbl/COUSR02C.cbl} moved
     * {@code SEC-USR-TYPE} in the record it had read at L322 and rewrote it at L360, and that single
     * byte WAS the authority -- {@code app/cbl/COADM01C.cbl} read it back from the same file. Splitting
     * the name of an authority from the grant of it is a consequence of moving identity to a managed
     * provider, and this method is where the two are put back together.</p>
     *
     * <p>Assumptions: the source membership is removed BEFORE the target membership is added, and the
     * order is a security decision rather than an arbitrary one. Either order has a window in which
     * the provider's view is neither the old state nor the new one, and the two windows are not
     * equivalent. Removing first leaves the identity holding NO group for the width of one provider
     * call, so a token minted inside that window carries no authority and is refused everywhere --
     * the fail-closed outcome. Adding first would leave it holding BOTH groups, so a token minted in
     * that window carries administrative authority in every case, including the demotion of an
     * administrator that is precisely the operation intended to take that authority away. A momentary
     * denial is recoverable by retrying a request; a momentary escalation is not recoverable at
     * all.</p>
     *
     * <p>Assumptions: the compensating call reverses only the removal, because the removal is the only
     * half that can have succeeded when this method fails. If the removal itself fails nothing has
     * changed and there is nothing to put back; if the removal succeeded and the addition failed the
     * identity is groupless, and re-adding the source group returns it exactly to the state it held
     * before the call. There is no third case, because the two calls are the whole of the mutation.</p>
     *
     * <p>Trade-offs: a compensation that itself fails is logged at error level and the original failure
     * is what propagates, with the compensation's failure attached as a suppressed exception. What is
     * given up is that the caller is told what it asked about -- why the reassignment did not happen --
     * rather than what is arguably more urgent, that an identity is now groupless. The alternative,
     * propagating the compensation failure instead, was rejected because it would report a symptom in
     * place of a cause and would make the common case, a transient provider failure with a clean
     * compensation, indistinguishable from the rare one. The suppressed exception carries the second
     * failure to any handler that logs the throwable rather than only its message, and the error line
     * names the identifier so an operator can repair the membership directly.</p>
     *
     * <p>Alternatives Considered: reading the current membership with {@code AdminListGroupsForUser}
     * first and skipping calls that are already satisfied. Rejected because it converts one mutation
     * into a read plus a mutation whose decision is based on a state that may have changed between
     * them, and because both provider calls are already idempotent -- adding a member that is present
     * and removing one that is absent both succeed -- so the read would buy nothing that retrying does
     * not already give. The membership the provider holds, not a snapshot of it, is what the calls act
     * on.</p>
     *
     * @param userId the provider username, being the row identifier the membership belongs to; must not
     *     be {@code null}
     * @param fromUserType the reference type the identity currently holds, {@code "A"} or {@code "U"}
     *     per {@code app/cpy/COCOM01Y.cpy} L27 and L28; must not be {@code null}
     * @param toUserType the reference type it is to hold, from the same two-value domain and different
     *     from {@code fromUserType}; must not be {@code null}
     * @return evidence that the provider now holds the target membership, which is what
     *     {@link UserAuthorityService} requires before the local column may be assigned; never
     *     {@code null}
     * @throws IllegalArgumentException if either type is outside the two-value domain, or if the two
     *     are equal -- an equal pair is not a reassignment and is refused here rather than treated as a
     *     silent success, because a caller asking to move an authority to where it already stands has
     *     confused a no-op with a move and {@link AuthorityReassignment#unchanged} states the no-op
     *     without calling the provider at all
     * @throws software.amazon.awssdk.core.exception.SdkException if the provider refused or could not be
     *     reached, after the membership has been restored to what it was
     */
    public AuthorityReassignment reassignGroup(String userId, String fromUserType, String toUserType) {
        String fromGroup = groupFor(fromUserType);
        String toGroup = groupFor(toUserType);
        if (fromGroup.equals(toGroup)) {
            throw new IllegalArgumentException(
                    "fromUserType and toUserType must differ to reassign a group membership");
        }

        this.provider.adminRemoveUserFromGroup(AdminRemoveUserFromGroupRequest.builder()
                .userPoolId(this.userPoolId)
                .username(userId)
                .groupName(fromGroup)
                .build());

        try {
            this.provider.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                    .userPoolId(this.userPoolId)
                    .username(userId)
                    .groupName(toGroup)
                    .build());
        } catch (RuntimeException failure) {
            restoreGroup(userId, fromGroup, failure);
            throw failure;
        }

        // WHY : Assumptions: both group names are recorded and the subject is not, which is the same
        //       division the provisioning line above draws. An authority change is the single most
        //       consequential thing this service does to an identity, so the line has to say which
        //       authority was taken and which was given; the subject would add the linkage between a
        //       person and their token claims to a log store, and no diagnostic here needs it.
        LOG.warn("event=auth.identity.authority-reassigned userId={} fromGroup={} toGroup={}",
                userId, fromGroup, toGroup);

        return new AuthorityReassignment(userId, fromUserType, toUserType, true);
    }

    /**
     * Puts a removed group membership back after the addition that was to replace it failed.
     *
     * <p>Assumptions: this is a compensation and not a retry, so it is attempted exactly once. A loop
     * here would hold the caller's thread across an outage for a state an operator can repair, and
     * would leave the identity groupless for longer than a single call's timeout either way. The error
     * line below is the durable record that the repair is outstanding.</p>
     *
     * @param userId the provider username whose membership is being restored
     * @param fromGroup the group name to restore, being the one this method's caller removed
     * @param failure the failure that made the restoration necessary, which the caller propagates and
     *     which carries any compensation failure as a suppressed exception
     */
    private void restoreGroup(String userId, String fromGroup, RuntimeException failure) {
        try {
            this.provider.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                    .userPoolId(this.userPoolId)
                    .username(userId)
                    .groupName(fromGroup)
                    .build());
            LOG.warn("event=auth.identity.authority-reassign-compensated userId={} restoredGroup={}",
                    userId, fromGroup);
        } catch (RuntimeException compensationFailure) {
            // WHY : Trade-offs: attaching the second failure to the first rather than replacing it, and
            //       the reason is stated on the public method above. What this line adds is the one
            //       piece of information the propagated exception cannot carry to an operator who sees
            //       only a log: that the identity currently holds no group at all and which group it
            //       should hold. Every guarded route refuses a caller in that state, so the visible
            //       symptom is a user who signs on and can do nothing, which is why the message names
            //       the repair rather than only the fault.
            LOG.error("event=auth.identity.authority-reassign-orphaned userId={} missingGroup={} "
                    + "action=restore-membership", userId, fromGroup, compensationFailure);
            failure.addSuppressed(compensationFailure);
        }
    }

    /**
     * Deletes the pool account provisioned for an identifier, so a failed row write leaves none behind.
     *
     * <p>Assumptions: this began as a compensating action alone. It exists because the pool account has
     * to be created before the row can carry its subject, so the window between the two is real and a
     * caller whose write fails is the only party that knows it. Absence is treated as success, which is
     * what makes it safe to call unconditionally on a failure path: a caller that failed BEFORE
     * provisioning and a caller that failed after both reach the same clean state.</p>
     *
     * <p>Refactoring Rationale: it now serves TWO callers -- the compensating path above and the
     * published delete operation -- and it is one method rather than two because the two need identical
     * behaviour in the one respect that is not obvious. Both must treat an absent pool account as
     * success. On the compensating path that is because the failure may have preceded provisioning; on
     * the delete path it is because the ROW is this context's authority on whether the user exists, so a
     * row found with no pool account behind it must still be deletable rather than leaving an
     * undeletable row behind. A second method for the delete path would have had to make the same
     * decision for a different reason and could then drift from this one. The two callers are told apart
     * in the log by the event names, not by the method.</p>
     *
     * <p>Assumptions: every provider failure other than an absent account propagates, on both paths. An
     * account that could not be deleted is an orphan an operator has to know about: on the delete path
     * it means a row was removed while an account that can still authenticate remains, which is a
     * pool identity with no row -- refused at every guarded route, but present.</p>
     *
     * @param userId the provider username to remove, being the row identifier provisioning used; must
     *     not be {@code null}
     */
    public void withdraw(String userId) {
        try {
            this.provider.adminDeleteUser(AdminDeleteUserRequest.builder()
                    .userPoolId(this.userPoolId)
                    .username(userId)
                    .build());
            LOG.warn("event=auth.identity.withdrawn userId={}", userId);
        } catch (UserNotFoundException absent) {
            // WHY : Assumptions: swallowed deliberately, and only this one exception is. The method's
            //       contract is that the pool holds no account for the identifier when it returns, and
            //       an account that was never created already satisfies that. Letting it propagate
            //       would mean a caller compensating for a failure that happened before provisioning
            //       would see a second, misleading failure and would report the wrong cause. Every
            //       other provider failure DOES propagate, because an account that could not be
            //       deleted is an orphan an operator has to know about.
            LOG.info("event=auth.identity.withdraw-noop userId={}", userId);
        }
    }

    /**
     * Selects the group name a user type maps to.
     *
     * <p>Assumptions: there is no default arm and no fallback group, and neither should be added. The
     * two values are the whole domain at {@code app/cpy/COCOM01Y.cpy} L27 and L28, the request record
     * already refuses anything else at its own boundary, and the Terraform lookup that assigns seed
     * membership refuses an out-of-domain value during plan for the same reason. A fallback here would
     * convert a refusal into a silent assignment to the wrong group, which on this context grants or
     * withholds the authority to create administrators.</p>
     *
     * @param userType the reference user type; must be {@code "A"} or {@code "U"}
     * @return the group name that type maps to; never {@code null}
     * @throws IllegalArgumentException if the value is outside the two-member domain
     */
    private String groupFor(String userType) {
        if (USER_TYPE_ADMIN.equals(userType)) {
            return this.adminGroupName;
        }
        if (USER_TYPE_USER.equals(userType)) {
            return this.userGroupName;
        }
        throw new IllegalArgumentException(
                "userType must be \"" + USER_TYPE_ADMIN + "\" or \"" + USER_TYPE_USER + "\"");
    }

    /**
     * Brings the pool account in line with a row that has just been updated.
     *
     * <p>Purpose: the pool holds its own copy of the two names and the type -- as {@code given_name},
     * {@code family_name} and {@code custom:user_type} -- and the type additionally decides group
     * membership, which is what every authorization decision is actually made on. A row updated without
     * this call would leave the pool describing the user as it was before, and in the case of a type
     * change would leave the signed group claim contradicting the stored type. The claim wins, so the
     * row's own type would become decoration.
     *
     * <p>Assumptions: the attribute update is issued unconditionally and the membership change only when
     * the type actually differs, and the asymmetry follows from what each call costs when it is
     * redundant. Rewriting three attributes with the values they already hold is one idempotent call
     * with no observable effect; removing a user from a group and adding it back is two calls that pass
     * through a state in which the account holds no group at all, and an account with no group is
     * refused at every guarded route. Doing that on an update that changed only a surname would open a
     * window in which the user could sign on and do nothing.
     *
     * <p>Assumptions: the removal precedes the addition, and the order is not interchangeable. An
     * account may hold both groups at once, so adding first and removing second would leave the account
     * holding the administrator group for the width of one call even when the type is being lowered from
     * administrator to ordinary user -- which is the one ordering that could grant authority it should
     * be taking away. Removing first fails closed: the window it opens withholds authority rather than
     * granting it.
     *
     * <p>Trade-offs: neither call is compensated on failure, and a failure of either propagates. What
     * that means concretely is worth stating rather than leaving to be discovered: the row has already
     * been written when this runs, so a failed attribute update leaves the pool's copy of the names
     * stale, and a failed membership change leaves the account with no group. Both are visible -- the
     * first as a name that differs between the row and a token's claims, the second as a user who can
     * sign on and reach nothing -- and both are repaired by reissuing the same update, because this
     * method is idempotent. The alternative, rolling the row back to match the pool, was rejected
     * because it would report success to the caller for a change it then undid, and because the row is
     * this context's authority on what the user IS while the pool is a projection of it.
     *
     * @param userId the row identifier, which is also the provider username; must not be {@code null}
     * @param firstName the given name as it now stands on the row; must not be {@code null}
     * @param lastName the family name as it now stands on the row; must not be {@code null}
     * @param previousUserType the type the row held before the update, {@code "A"} or {@code "U"}, used
     *     only to decide whether membership has to move; must not be {@code null}
     * @param userType the type the row holds after the update, {@code "A"} or {@code "U"}; must not be
     *     {@code null}
     * @throws IllegalArgumentException if either type is outside the two-value domain, raised before any
     *     provider call so an out-of-domain value changes nothing
     * @throws software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException if the
     *     pool holds no account for the identifier, which means a row exists whose account was never
     *     provisioned or was removed outside this service; the remedy is operational and the condition
     *     is deliberately not translated into a client-facing status, because the authority on whether
     *     the USER exists is the row and the row was found
     */
    public void synchronise(String userId, String firstName, String lastName,
            String previousUserType, String userType) {

        // WHY : Assumptions: both types are resolved to group names BEFORE any provider call, so an
        //       out-of-domain value on either side raises without having half-applied the change. The
        //       resolution of the previous type is what makes that true of the value the caller read
        //       from storage as well as the one it accepted from a request.
        String targetGroup = groupFor(userType);
        String previousGroup = groupFor(previousUserType);

        this.provider.adminUpdateUserAttributes(AdminUpdateUserAttributesRequest.builder()
                .userPoolId(this.userPoolId)
                .username(userId)
                .userAttributes(
                        attribute(ATTRIBUTE_GIVEN_NAME, firstName),
                        attribute(ATTRIBUTE_FAMILY_NAME, lastName),
                        attribute(ATTRIBUTE_USER_TYPE, userType))
                .build());

        if (!previousGroup.equals(targetGroup)) {
            this.provider.adminRemoveUserFromGroup(AdminRemoveUserFromGroupRequest.builder()
                    .userPoolId(this.userPoolId)
                    .username(userId)
                    .groupName(previousGroup)
                    .build());

            this.provider.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                    .userPoolId(this.userPoolId)
                    .username(userId)
                    .groupName(targetGroup)
                    .build());

            // WHY : Assumptions: the authority change is logged at its own event name rather than folded
            //       into the attribute update, because a change of group is a change of what the user can
            //       do and is the line an audit reads. It records both group names and not the subject,
            //       for the reason the provisioning line records: the subject is the value a presented
            //       token is matched on.
            LOG.warn("event=auth.identity.regrouped userId={} from={} to={}", userId, previousGroup,
                    targetGroup);
        }

        LOG.info("event=auth.identity.synchronised userId={} group={}", userId, targetGroup);
    }

    /**
     * Reads the subject out of a created account's attributes.
     *
     * <p>Assumptions: the attribute list is searched by name rather than by position, because the
     * provider does not document an order and a positional read would break silently on a schema
     * change. A missing subject is raised rather than defaulted: the column that stores it is not
     * nullable, so there is no value to fall back to and a row written without one could never be
     * authenticated.</p>
     *
     * @param created the provider's response to the create call; must not be {@code null}
     * @param userId the identifier the account was created for, named in the failure so an operator
     *     can find the orphan
     * @return the subject as a UUID; never {@code null}
     * @throws IllegalStateException if no subject attribute is present, or if its value is not a UUID
     */
    private static UUID subjectOf(AdminCreateUserResponse created, String userId) {
        // WHY : Assumptions: the user member is null-checked and the attribute list is checked through
        //       the generated has-attributes predicate, which are two different absences. The response
        //       shape makes the created account optional, so a null there is a well-formed response
        //       describing nothing; the predicate distinguishes an attribute list the provider omitted
        //       from one it sent empty, and only the predicate can, because the generated accessor
        //       returns an empty list for both. Either absence reaches the same refusal below, but
        //       reading the list without the guards would raise a null-pointer failure naming this
        //       method rather than a statement naming the account it could not describe.
        List<AttributeType> attributes = created.user() != null && created.user().hasAttributes()
                ? created.user().attributes()
                : List.of();
        for (AttributeType attribute : attributes) {
            if (ATTRIBUTE_SUBJECT.equals(attribute.name())) {
                try {
                    return UUID.fromString(attribute.value());
                } catch (IllegalArgumentException malformed) {
                    throw new IllegalStateException(
                            "the provider returned a non-UUID subject for user " + userId, malformed);
                }
            }
        }
        throw new IllegalStateException(
                "the provider returned no " + ATTRIBUTE_SUBJECT + " attribute for user " + userId);
    }

    /**
     * Builds one provider attribute.
     *
     * @param name the attribute name, including the {@code custom:} prefix where one applies
     * @param value the value to record
     * @return the attribute; never {@code null}
     */
    private static AttributeType attribute(String name, String value) {
        return AttributeType.builder().name(name).value(value).build();
    }
}
