package com.carddemo.auth.service;

import com.carddemo.common.observability.ThrowableDigest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
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
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DeleteSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceExistsException;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

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
 * deliberately does not name the provider, so that the provider is named only in the two classes that
 * talk to it: this one, for the administrative user-management API, and
 * {@link CognitoIdentityService}, for the authentication API the sign-on exchange uses.</p>
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
 * <h2>How the first credential reaches its owner</h2>
 *
 * <p>Purpose: the account is created with the provider's message delivery suppressed, carrying a
 * temporary password this class generates, and that password is published to a per-user managed-secret
 * entry encrypted with the deployment's customer-managed key. The account therefore stands in its
 * force-change state with a credential that exists, is retrievable by a principal holding
 * {@code GetSecretValue} and the key's permission, and buys exactly one sign-in before the pool
 * requires it to be replaced.</p>
 *
 * <p>Refactoring Rationale: no credential was created at all. The account was created with delivery
 * suppressed AND with no temporary password, so the provider generated one and sent it nowhere; the
 * pool's schema carries the two names and the custom type and no email or phone attribute, so there
 * was no address a message could have gone to either. The account was therefore unreachable: a
 * runtime-created user could not obtain a credential, could not complete the force-change challenge,
 * and could not sign in by any path. The one operation the reference system's whole user-administration
 * suite exists to enable -- {@code app/cbl/COUSR01C.cbl} creating a user who then signs on -- had no
 * working equivalent.</p>
 *
 * <p>Assumptions: this is the SAME handover the seed identities use, and the shape is deliberately
 * copied from {@code infra/modules/cognito/seed_user_bootstrap.py} so that a runtime-created account
 * and a seeded one are reached by one procedure. Both generate a policy-compliant value from the same
 * alphabet, both apply it as a TEMPORARY password so the pool forces a change at first sign-in, and
 * both write {@code {"username", "password"}} into an entry encrypted with the secrets key. The
 * username travels inside the protected payload for the reason that script records: a holder of the
 * secret must be able to tell which identity it opens, and naming the identity inside the encrypted
 * value is strictly better than naming it in the entry's own name.</p>
 *
 * <p>Assumptions: the temporary password is applied on the CREATE call rather than by a following
 * administrative reset, and the difference is a permission. The seed script resets because its identity
 * already exists -- Terraform's own user resource made it -- so a create would fail on every apply. This
 * class owns the create, so it can supply the value there and the task role needs no
 * {@code AdminSetUserPassword} grant at all. It also removes a window: there is never a moment where
 * the account exists holding a provider-generated password nobody can obtain.</p>
 *
 * <p>Trade-offs: no credential is returned to the caller and none is logged, so this operation's
 * response says nothing about the handover. That is the point rather than an omission -- the reference
 * system's handling of a credential is the defect this migration is undoing, since
 * {@code app/cpy/CSUSR01Y.cpy} L21 stores {@code SEC-USR-PWD PIC X(08)} in the clear,
 * {@code app/cbl/COSGN00C.cbl} L223 compares it in the clear and {@code app/cbl/COUSR02C.cbl} L169
 * writes it back out onto a screen. What the operator needs instead is the entry's NAME, and that is
 * derived rather than transported: it is the configured prefix, the fixed segment
 * {@value #SECRET_NAME_INFIX}, and the hexadecimal digest of the user identifier, so anyone who knows
 * the identifier can recompute it and nobody who merely lists entries learns an identifier from one.
 * The honest limit of that opacity is stated where the name is built: an eight-character identifier
 * space is small enough to enumerate against a digest, so the name is defence in depth and the controls
 * that actually protect the value are {@code GetSecretValue} and the key policy -- exactly as for the
 * seed entries.</p>
 *
 * <h2>Assumptions: nothing survives a failed provisioning</h2>
 *
 * <p>The pool account must exist before the row can carry its subject, so provisioning happens first
 * and the row is written second. Two windows follow from that, and both are closed rather than
 * documented. Inside this class, every step after the account is created -- reading the subject back,
 * joining the group, publishing the credential -- runs under a handler that WITHDRAWS the account and
 * discards its credential entry before re-raising, so a post-create failure leaves nothing behind and a
 * retry meets a clean pool. Outside it, a caller whose row write then fails calls
 * {@link #withdraw(String)}, which is that same cleanup and is the caller's obligation because only the
 * caller knows whether its transaction committed. It treats an absent account and an absent entry as
 * success, so it may be invoked without first establishing how far provisioning got.</p>
 *
 * <p>Refactoring Rationale: the internal handler is the half that did not exist. A failure of the
 * group-membership call, or a response that described no subject, left an account that could
 * authenticate with a row that was never written; the identifier then became permanently unusable,
 * because a later create met the pool's duplicate-username condition, which this service deliberately
 * does not translate into a client-facing conflict. Nothing reported it and nothing repaired it.</p>
 *
 * <h2>Where this is called from</h2>
 *
 * <p>Assumptions: the callers are {@code UserService} in this package -- the create path, which calls
 * {@link #provision}, and the update and delete paths, which reach {@link #synchronise} and
 * {@link #withdraw} through the durable task ledger {@link IdentitySyncService} drains -- behind the
 * handlers {@code com.carddemo.auth.api.UserController} publishes. The order the create path must
 * follow is fixed by the paragraph above, provision then write the row with the returned subject then
 * withdraw if the write fails, and it is stated here because this class is where the ordering
 * constraint originates.</p>
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

    /**
     * The fixed segment separating the configured prefix from the digest in a credential entry's name.
     *
     * <p>Assumptions: the segment distinguishes an entry this service writes from the seed entries the
     * infrastructure writes under the same prefix, which use {@code /seed-user/}. Two families under one
     * prefix is what lets the task role be granted write permission on this family alone, so a defect
     * here cannot overwrite a seed identity's handover.</p>
     */
    static final String SECRET_NAME_INFIX = "/runtime-user/";

    /**
     * How many hexadecimal characters of the identifier's digest name the entry.
     *
     * <p>Assumptions: thirty-two characters are half of a SHA-256 digest, which is far beyond what
     * uniqueness over an eight-character identifier space requires and is chosen for that reason rather
     * than for collision resistance -- a shorter prefix would be equally unique here and would look as
     * though it had been tuned. The name is not a security control; the reasoning is on
     * {@link #credentialSecretName(String)}.</p>
     */
    static final int SECRET_NAME_DIGEST_LENGTH = 32;

    /** The smallest temporary-password length this service will generate. */
    static final int MIN_TEMPORARY_PASSWORD_LENGTH = 14;

    /** The largest temporary-password length the provider's own policy admits. */
    static final int MAX_TEMPORARY_PASSWORD_LENGTH = 128;

    /** The lower-case characters a generated temporary password draws from. */
    private static final String PASSWORD_LOWERCASE = "abcdefghijklmnopqrstuvwxyz";

    /** The upper-case characters a generated temporary password draws from. */
    private static final String PASSWORD_UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** The digits a generated temporary password draws from. */
    private static final String PASSWORD_DIGITS = "0123456789";

    /**
     * The symbols a generated temporary password draws from.
     *
     * <p>Assumptions: this is character for character the set
     * {@code infra/modules/cognito/main.tf} declares as {@code password_special_charset} at its line
     * 266 and hands the seed bootstrap as {@code CARDDEMO_PASSWORD_SYMBOLS}. Every member is inside the
     * provider's permitted symbol set, and the two paths draw from one alphabet so a value generated
     * here and one generated there are indistinguishable in shape. Alternatives Considered: the
     * provider's whole permitted set, which is wider. Declined because it includes characters that need
     * escaping in a shell and in a JSON document, and this value is read by a person out of a stored
     * document and typed once.</p>
     */
    private static final String PASSWORD_SYMBOLS = "!#%*+-:=?@^_~";

    /**
     * The source of randomness a temporary password is drawn from.
     *
     * <p>Assumptions: {@link SecureRandom} and not {@code java.util.Random}, because this value is
     * credential material for one sign-in and a predictable generator would make it guessable from
     * another value the same generator produced. It is a static field because the instance is
     * thread-safe and seeding one per call would add cost with no benefit.</p>
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Records provisioning outcomes for an operator; carries no attribute value and no subject. */
    private static final Logger LOG = LoggerFactory.getLogger(CognitoUserProvisioningService.class);

    /** The administrative provider client this class issues its pool calls through. */
    private final CognitoIdentityProviderClient provider;

    /** The managed-secret client the initial-credential handover is published through. */
    private final SecretsManagerClient secrets;

    /** The user pool every call below is scoped to. */
    private final String userPoolId;

    /** The group an administrator row's account is added to. */
    private final String adminGroupName;

    /** The group an ordinary user row's account is added to. */
    private final String userGroupName;

    /** The managed-secret name prefix every credential entry this service writes sits beneath. */
    private final String credentialSecretPrefix;

    /** The customer-managed key a credential entry this service creates is encrypted with. */
    private final String credentialSecretKmsKeyArn;

    /** How many characters a generated temporary password carries. */
    private final int temporaryPasswordLength;

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
     * <p>Assumptions: the credential-entry prefix and the key that encrypts an entry are injected with
     * no fallback, for the same reason the pool identifier is: a default would let the service start and
     * write a runtime user's one-time credential somewhere nobody looks, or unencrypted by the
     * customer-managed key the deployment's own audit rests on. A missing value stops startup instead.
     * The length has a default because it is a policy preference rather than a deployment address, and
     * it is validated here so a value the pool would refuse is found at startup rather than on the first
     * create.</p>
     *
     * @param provider the administrative provider client; must not be {@code null}
     * @param secrets the managed-secret client the credential handover is published through; must not
     *     be {@code null}
     * @param userPoolId the pool every call is scoped to; must not be blank
     * @param adminGroupName the group name for the {@code 'A'} user type; must not be blank
     * @param userGroupName the group name for the {@code 'U'} user type; must not be blank
     * @param credentialSecretPrefix the managed-secret name prefix credential entries sit beneath, being
     *     the same prefix the infrastructure writes seed entries under; must not be blank
     * @param credentialSecretKmsKeyArn the customer-managed key a created entry is encrypted with; must
     *     not be blank
     * @param temporaryPasswordLength how many characters a generated temporary password carries; must be
     *     between {@link #MIN_TEMPORARY_PASSWORD_LENGTH} and {@link #MAX_TEMPORARY_PASSWORD_LENGTH}
     *     inclusive
     * @throws NullPointerException if the provider or the secret client is {@code null}
     * @throws IllegalStateException if the configured password length is outside the admitted range
     */
    public CognitoUserProvisioningService(CognitoIdentityProviderClient provider,
            SecretsManagerClient secrets,
            @Value("${carddemo.auth.cognito.user-pool-id}") String userPoolId,
            @Value("${carddemo.security.cognito.admin-group-name}") String adminGroupName,
            @Value("${carddemo.security.cognito.user-group-name}") String userGroupName,
            @Value("${carddemo.auth.cognito.credential-secret-prefix}") String credentialSecretPrefix,
            @Value("${carddemo.auth.cognito.credential-secret-kms-key-arn}")
            String credentialSecretKmsKeyArn,
            @Value("${carddemo.auth.cognito.temporary-password-length:24}")
            int temporaryPasswordLength) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.secrets = Objects.requireNonNull(secrets, "secrets must not be null");
        this.userPoolId = userPoolId;
        this.adminGroupName = adminGroupName;
        this.userGroupName = userGroupName;
        this.credentialSecretPrefix = credentialSecretPrefix;
        this.credentialSecretKmsKeyArn = credentialSecretKmsKeyArn;
        // WHY : Assumptions: the length is checked at CONSTRUCTION and not at generation, because the
        //       failure it prevents is a configuration mistake and the useful moment to report one is
        //       startup. Checked at generation instead, the first create-user request after a bad
        //       deployment would fail with a fault the caller can do nothing about, and every later one
        //       would fail the same way. Trade-offs: the lower bound is this class's own and is above
        //       the provider's minimum, because a value shorter than the pool's configured minimum is
        //       refused by the pool anyway and the pool's minimum is not readable from here; the upper
        //       bound is the provider's own ceiling.
        if (temporaryPasswordLength < MIN_TEMPORARY_PASSWORD_LENGTH
                || temporaryPasswordLength > MAX_TEMPORARY_PASSWORD_LENGTH) {
            throw new IllegalStateException("carddemo.auth.cognito.temporary-password-length must be"
                    + " between " + MIN_TEMPORARY_PASSWORD_LENGTH + " and "
                    + MAX_TEMPORARY_PASSWORD_LENGTH + " but was " + temporaryPasswordLength
                    + ": a shorter value is refused by the pool's own password policy and a longer one"
                    + " exceeds the provider's ceiling, and either way no runtime-created user could"
                    + " receive a credential");
        }
        this.temporaryPasswordLength = temporaryPasswordLength;
    }

    /**
     * Creates the pool account for a new user row, adds it to the group its type selects, and returns
     * the subject the provider minted.
     *
     * <p>Assumptions: the order of the calls is fixed and is not interchangeable. The account has to
     * exist before it can be added to a group and before a password can be set on it, and the subject is
     * only available from the create response, so nothing can be reordered ahead of the create. Group
     * membership precedes the credential handover so that a value is only ever published for an account
     * that already carries the authority its row describes.
     *
     * <p>Refactoring Rationale: every step after the create runs under a handler that WITHDRAWS the
     * account before re-raising, and previously none did. A failed membership call, or a response
     * describing no subject, left an account that could authenticate with no row behind it; the
     * identifier then became permanently unusable, because a later create met the pool's
     * duplicate-username condition, which this service deliberately does not translate into a
     * client-facing conflict. Withdrawing turns every failure on this method into a clean retry.
     * Alternatives Considered: leaving the caller to compensate, which it does for its own row write.
     * Rejected because the caller cannot tell a create that never happened from one that happened and
     * then failed halfway, so it would have to withdraw on every provisioning failure including the ones
     * that created nothing -- and a withdrawal is a provider call, so that is a second call on the
     * common path for the sake of the rare one.
     *
     * @param userId the row's identifier and the provider username, at most the eight characters
     *     {@code SEC-USR-ID PIC X(08)} declares at {@code app/cpy/CSUSR01Y.cpy} L18; must not be
     *     {@code null}
     * @param firstName the given name to record on the account; must not be {@code null}
     * @param lastName the family name to record on the account; must not be {@code null}
     * @param userType {@code "A"} or {@code "U"}, the whole domain at {@code app/cpy/COCOM01Y.cpy}
     *     L27 and L28; must not be {@code null}
     * @return the subject the provider assigned -- the value {@code auth.users.cognito_sub} stores and
     *     the only link between a presented token and the row -- paired with the name of the managed
     *     secret entry holding the account's first credential, so the administrator who created the
     *     user is told where to collect it; never {@code null}, and it never carries the credential
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
    public ProvisionedIdentity provision(
            String userId, String firstName, String lastName, String userType) {
        String groupName = groupFor(userType);
        String temporaryPassword = temporaryPassword();

        AdminCreateUserResponse created = this.provider.adminCreateUser(AdminCreateUserRequest
                .builder()
                .userPoolId(this.userPoolId)
                .username(userId)
                // WHY : Assumptions: delivery is SUPPRESSED because there is nowhere to deliver to. The
                //       pool's schema is the two names and the custom type -- it declares no email and
                //       no phone attribute, following app/cpy/CSUSR01Y.cpy, which declares neither --
                //       so an unsuppressed call would fail rather than notify anyone. The seed
                //       bootstrap at infra/modules/cognito/seed_user_bootstrap.py suppresses for the
                //       same reason, so a runtime account and a seeded one arrive in the same state.
                .messageAction(MessageActionType.SUPPRESS)
                // WHY : Refactoring Rationale: a temporary password is supplied HERE, and previously
                //       none was. Suppressed delivery with no temporary password means the provider
                //       generates a value and sends it nowhere, so the account existed in its
                //       force-change state holding a credential no principal could obtain -- a user
                //       who could never sign in and never complete the challenge. Supplying the value
                //       is what makes the handover below possible at all.
                // WHY : Assumptions: TEMPORARY and not permanent. The pool places the account in its
                //       force-change state, so this value buys exactly one sign-in and is inert
                //       afterwards; that is what makes storing it acceptable, because the person's
                //       real password never exists outside their own session. The seed script records
                //       the identical reasoning at its own Permanent-false argument.
                .temporaryPassword(temporaryPassword)
                .userAttributes(
                        attribute(ATTRIBUTE_GIVEN_NAME, firstName),
                        attribute(ATTRIBUTE_FAMILY_NAME, lastName),
                        attribute(ATTRIBUTE_USER_TYPE, userType))
                .build());

        try {
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

            publishCredential(userId, temporaryPassword);

            // WHY : Assumptions: the line records the identifier and the group and NOT the subject, and
            //       nothing at all about the credential beyond that one was published. The subject is
            //       the value a presented token is matched on, so a log store holding it holds the
            //       linkage between a person and their token claims; the identifier is already the row's
            //       primary key and is disclosed by every other line about this request. The entry's
            //       name is omitted because it is derivable from the identifier, so recording it would
            //       add a second copy of a locator without adding information.
            LOG.info("event=auth.identity.provisioned userId={} group={} credential=published",
                    userId, groupName);

            return new ProvisionedIdentity(subject, credentialSecretName(userId));

        } catch (RuntimeException incomplete) {
            // WHY : Assumptions: the withdrawal removes BOTH the account and any credential entry the
            //       publication may have created, because a half-published handover is worse than none:
            //       a value would sit in the store for an account that is about to be deleted, and the
            //       next create for the identifier would then overwrite it and be indistinguishable
            //       from the first. withdraw treats both absences as success, so it is correct however
            //       far this method got.
            // WHY : Trade-offs: a failure of the withdrawal itself is recorded and DISCARDED, and the
            //       original failure is what propagates. The caller asked why provisioning failed and
            //       that is the answer it needs; the cleanup failure is a second, operational fact that
            //       no caller can act on, so it is attached to the first as a suppressed exception and
            //       named on its own line. Propagating it instead would report a symptom in place of a
            //       cause and would make a transient provider fault with clean cleanup
            //       indistinguishable from a genuine orphan.
            try {
                withdraw(userId);
            } catch (RuntimeException uncleanable) {
                LOG.error("event=auth.identity.provision-orphaned userId={} action=withdraw-account"
                        + " failure={}", userId, ThrowableDigest.of(uncleanable));
                incomplete.addSuppressed(uncleanable);
            }
            throw incomplete;
        }
    }

    /**
     * Publishes one account's temporary credential to the managed-secret entry its owner collects it
     * from.
     *
     * <p>Assumptions: creation is attempted first and an existing entry is written to instead, which
     * makes the publication idempotent under a retry. The entry's name is derived from the identifier,
     * so a second provisioning of the same identifier addresses the same entry rather than accumulating
     * one per attempt -- and the value it then holds is the one that works, because the pool holds the
     * password from the most recent create.</p>
     *
     * <p>Assumptions: the created entry names the customer-managed key explicitly rather than relying on
     * the account's default managed key. The value being stored is the direct replacement for the
     * cleartext {@code SEC-USR-PWD} field at {@code app/cpy/CSUSR01Y.cpy} L21, and a customer-managed key
     * gives an auditable, revocable key policy that the default does not, so possession of the entry is
     * not sufficient without the key's permission. The infrastructure makes the same choice for the seed
     * entries at {@code infra/modules/cognito/main.tf}.</p>
     *
     * <p>Assumptions: the payload pairs the username with the password, and the username is inside the
     * ENCRYPTED value rather than in the entry's name. A holder of the entry has to be able to tell
     * which identity it opens, and naming the identity inside the protected payload is strictly better
     * than naming it in a resource name any principal with list permission can read. This is the same
     * pairing and the same reasoning as the seed bootstrap's own payload.</p>
     *
     * <p>Trade-offs: the payload is composed by concatenation rather than by a serialiser. It is two
     * fixed keys and two values that are a generated password from a known alphabet and an identifier
     * the request boundary has already constrained to eight non-blank characters, so no value here can
     * carry a quote or a backslash to escape; a serialiser would add a dependency and an object graph to
     * emit a two-member document. The alphabet is fixed by this class, which is what makes that safe --
     * a wider alphabet would need escaping and would need a serialiser with it.</p>
     *
     * @param userId the row identifier and provider username the credential belongs to; must not be
     *     {@code null}
     * @param temporaryPassword the generated one-time value, held only for the duration of this call;
     *     must not be {@code null}
     * @throws software.amazon.awssdk.core.exception.SdkException if the store refused both the creation
     *     and the write, which leaves an account whose credential nobody can collect and is therefore
     *     raised rather than absorbed
     */
    private void publishCredential(String userId, String temporaryPassword) {
        String secretName = credentialSecretName(userId);
        String payload = "{\"username\":\"" + userId + "\",\"password\":\"" + temporaryPassword + "\"}";

        try {
            this.secrets.createSecret(CreateSecretRequest.builder()
                    .name(secretName)
                    // WHY : Assumptions: the description carries purpose and lifecycle and NO identity,
                    //       because a description is readable through metadata APIs that do not decrypt
                    //       the value. Naming the user here would undo the point of keeping the identity
                    //       inside the payload.
                    .description("Generated one-time initial credential for a CardDemo identity created"
                            + " at run time. Temporary: must be changed at first sign-in.")
                    .kmsKeyId(this.credentialSecretKmsKeyArn)
                    .secretString(payload)
                    .build());
        } catch (ResourceExistsException alreadyPublished) {
            // WHY : Assumptions: an existing entry is WRITTEN TO rather than treated as a conflict. The
            //       name is derived from the identifier, so this condition means the identifier has been
            //       provisioned before -- either a retry of this create, or a create following a delete
            //       that removed the account. In both cases the value that works is the one the pool
            //       just accepted, so the entry must carry it; refusing here would leave a stale value
            //       that opens nothing. Trade-offs: the previous value is superseded, which is
            //       acceptable precisely because it was inert -- it was either never collected, or
            //       already spent on a sign-in for an account that no longer exists.
            this.secrets.putSecretValue(PutSecretValueRequest.builder()
                    .secretId(secretName)
                    .secretString(payload)
                    .build());
        }
    }

    /**
     * Discards the credential entry an identifier's account collects its first credential from.
     *
     * <p>Assumptions: absence is success, which is what makes this safe on every cleanup path -- the
     * entry may never have been created, because the failure being compensated may have preceded the
     * publication.</p>
     *
     * <p>Trade-offs: the deletion is forced rather than scheduled with a recovery window, and the reason
     * is the name. The name is derived from the identifier, and a scheduled deletion RESERVES the name
     * for the whole recovery window while refusing a write to it, so a re-create of the same identifier
     * inside that window could neither create the entry nor write to it -- the identifier would be
     * unusable for weeks through a path no operator would see. Forcing the deletion gives up the ability
     * to recover a value that is a one-time handover for an account being removed in the same act, which
     * is nothing worth recovering. The infrastructure's seed entries keep a recovery window because their
     * names are opaque and Terraform-managed, so the same reasoning does not reach them.</p>
     *
     * @param userId the row identifier whose credential entry is to be discarded; must not be
     *     {@code null}
     */
    private void discardCredential(String userId) {
        try {
            this.secrets.deleteSecret(DeleteSecretRequest.builder()
                    .secretId(credentialSecretName(userId))
                    .forceDeleteWithoutRecovery(true)
                    .build());
        } catch (ResourceNotFoundException absent) {
            // WHY : Assumptions: swallowed deliberately, and only this one exception is. The method's
            //       contract is that no credential entry remains for the identifier, and one that was
            //       never created already satisfies it. Every other store failure propagates, because an
            //       entry that could not be removed holds a value for an account that no longer exists.
            LOG.info("event=auth.identity.credential-discard-noop userId={}", userId);
        }
    }

    /**
     * Derives the managed-secret name an identifier's credential entry is published under.
     *
     * <p>Assumptions: the name is DERIVED from the identifier rather than generated, so it can be
     * recomputed. An operator who has just created a user needs to find that user's handover entry, and
     * nothing transports the name to them -- it is deliberately absent from the response and from the
     * log. Deriving it means the procedure is "hash the identifier" rather than "find the value we
     * failed to give you". It also makes the publication idempotent, since a retry addresses the entry
     * the previous attempt created rather than making a second one.</p>
     *
     * <p>Trade-offs: the digest is defence in depth and NOT the control that protects the value, and the
     * limit is worth stating plainly rather than leaving to be assumed. {@code SEC-USR-ID PIC X(08)} at
     * {@code app/cpy/CSUSR01Y.cpy} L18 is eight characters, which is a small enough space to enumerate
     * against a published digest, so a principal who can list entries and who guesses an identifier can
     * confirm the guess. What the digest buys is that listing entries does not HAND OUT identifiers. What
     * actually protects the credential is {@code secretsmanager:GetSecretValue} together with the
     * customer-managed key's policy, which is the same pair the seed entries rely on. Alternatives
     * Considered: an opaque random suffix, as the seed entries use. Rejected because a random name cannot
     * be recomputed, so it would have to be transported -- and the two ways to transport it are the
     * create response and the log, which are the two places this design keeps credential locators out
     * of.</p>
     *
     * @param userId the row identifier and provider username; must not be {@code null}
     * @return the fully qualified managed-secret name for that identifier's credential entry; never
     *     {@code null}
     * @throws IllegalStateException if the platform does not provide SHA-256, which would mean the name
     *     could not be derived at all
     */
    // WHY : Refactoring Rationale: the derivation is PUBLISHED rather than private, because the
    //       creation response now carries the entry's NAME (never its value). Two independently
    //       authored resolutions of the same defect met here: one generated the credential and handed
    //       it back in the response body, the other published it to the managed secret store and
    //       returned nothing. The body route leaks a live credential into every proxy and browser
    //       log, so the store route stands; but an administrator still has to be told WHERE the
    //       credential is, and deriving that name in a second place would be two statements of one
    //       rule. Exposing the derivation keeps the rule here and lets the response carry a locator.
    public String credentialSecretName(String userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(userId.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest);
            return this.credentialSecretPrefix + SECRET_NAME_INFIX
                    + hex.substring(0, SECRET_NAME_DIGEST_LENGTH);
        } catch (NoSuchAlgorithmException unavailable) {
            // WHY : Assumptions: raised rather than falling back to the identifier itself. Every Java
            //       platform is required to provide SHA-256, so reaching this is a broken runtime; a
            //       fallback would silently publish entries under a naming scheme that discloses the
            //       identifier and would then be indistinguishable from the intended one.
            throw new IllegalStateException(
                    "SHA-256 is unavailable, so a credential entry name cannot be derived",
                    unavailable);
        }
    }

    /**
     * Generates one policy-compliant temporary password.
     *
     * <p>Assumptions: one character is drawn from EACH of the four classes before the remainder is drawn
     * from their union, and the result is then shuffled. Drawing uniformly from the union alone would
     * satisfy a class requirement only with high probability, and a value that fails the pool's policy is
     * rejected at the create call -- so a create-user request would fail intermittently, for a reason no
     * caller could act on and no test would reproduce reliably.</p>
     *
     * <p>Trade-offs: all four classes are always included rather than being selected from the pool's
     * configured policy. Including all four satisfies a policy that requires any SUBSET of them, so one
     * generator serves every policy shape without this service reading five more properties that could
     * disagree with the pool's actual configuration. The one policy this cannot satisfy is a minimum
     * length above the configured length, which is exactly why the length is configurable and is
     * validated at startup. Alternatives Considered: reading the policy from the pool with
     * {@code DescribeUserPool}. Rejected because it adds a provider call and a permission to a path that
     * runs on every create, to learn a value that changes when the infrastructure changes and is already
     * expressible as configuration.</p>
     *
     * <p>Assumptions: the value is returned as a {@code String} rather than a character array that could
     * be cleared. Every consumer of it -- the request builder, the SDK's serialiser, the payload below --
     * takes a string, so a character array would be converted at the boundary and the conversion would
     * leave exactly the copy the array existed to avoid. {@link SignOnRequest} records the same accepted
     * compromise for the credential a caller presents.</p>
     *
     * @return a generated temporary password of the configured length, drawn from all four character
     *     classes; never {@code null}
     */
    private String temporaryPassword() {
        String alphabet = PASSWORD_LOWERCASE + PASSWORD_UPPERCASE + PASSWORD_DIGITS + PASSWORD_SYMBOLS;
        List<Character> characters = new ArrayList<>(this.temporaryPasswordLength);
        characters.add(pick(PASSWORD_LOWERCASE));
        characters.add(pick(PASSWORD_UPPERCASE));
        characters.add(pick(PASSWORD_DIGITS));
        characters.add(pick(PASSWORD_SYMBOLS));
        while (characters.size() < this.temporaryPasswordLength) {
            characters.add(pick(alphabet));
        }

        // WHY : Assumptions: the shuffle is seeded from the SAME secure source the draws are, because a
        //       shuffle from a predictable source would put the four class members back into a
        //       predictable arrangement and would leak the first four positions' classes.
        Collections.shuffle(characters, RANDOM);

        StringBuilder password = new StringBuilder(this.temporaryPasswordLength);
        for (Character character : characters) {
            password.append(character.charValue());
        }
        return password.toString();
    }

    /**
     * Draws one character uniformly from a class.
     *
     * @param characterClass the characters to draw from; must not be empty
     * @return one character of that class
     */
    private static Character pick(String characterClass) {
        return Character.valueOf(characterClass.charAt(RANDOM.nextInt(characterClass.length())));
    }

    /**
     * Deletes the pool account provisioned for an identifier and discards its credential entry, so a
     * failed row write leaves neither behind.
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
     * <p>Assumptions: the credential entry is discarded BEFORE the account, and the order matters in one
     * direction only. An entry outliving its account holds a value that opens nothing and would be
     * overwritten by the next create for the identifier; an account outliving its entry holds a
     * credential nobody can collect, which is precisely the condition the handover exists to prevent.
     * Discarding first means a failure between the two leaves the recoverable shape rather than the
     * unrecoverable one, and the caller may invoke this method again because both halves treat absence as
     * success.</p>
     *
     * @param userId the provider username to remove, being the row identifier provisioning used; must
     *     not be {@code null}
     */
    public void withdraw(String userId) {
        discardCredential(userId);

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
