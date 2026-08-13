package com.carddemo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DeleteSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceExistsException;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

/**
 * Asserts that the identity a new user row is bound to is created by this service and never chosen by a
 * caller.
 *
 * <h2>Purpose</h2>
 * <p>The review finding this class answers is that the create-user request used to carry the Cognito subject
 * as a required input. The subject is the only link between a presented token and a row -- {@code auth.users}
 * declares the column {@code NOT NULL UNIQUE} -- so a caller able to nominate it was a caller able to decide
 * which pool identity a new row authenticates as. The fix has two halves: the property was withdrawn from the
 * request schema, and this service now provisions the pool account itself and reads the subject back from the
 * provider. This class covers the second half, which is the half that can regress silently.</p>
 *
 * <p>Assumptions: the assertions below are written against the provider CALL SHAPE and not only against the
 * returned value, because the shape is what makes a runtime-created account indistinguishable from a seeded
 * one. {@code infra/modules/cognito/seed_user_bootstrap.py} creates seed identities with suppressed delivery
 * and exactly three attributes, then adds group membership as a separate act; a change here that dropped the
 * suppression or renamed the custom attribute would leave the two populations divergent in a way no return
 * value would reveal.</p>
 *
 * <p>Trade-offs: the provider is substituted, so nothing here proves that a real user pool accepts these
 * payloads. What it does prove is every branch this class owns -- the group selection, the refusal that
 * precedes any call, the by-name attribute read, the two malformed-response refusals and the single
 * deliberately swallowed exception -- none of which a live pool would exercise more convincingly.</p>
 *
 * <p>Alternatives Considered: driving the real AWS SDK against an emulator. Rejected because the emulator
 * available in this environment does not implement the custom-attribute schema this pool declares, so the
 * three-attribute payload could not be asserted at all, and a test that silently dropped the attribute it was
 * written to protect would be worse than no test.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
class CognitoUserProvisioningServiceTest {

    /** The pool every provisioning call is scoped to, matching the profile key's shape. */
    private static final String POOL_ID = "us-east-1_TESTPOOL";

    /** The group name an {@code 'A'} row's account must join. */
    private static final String ADMIN_GROUP = "carddemo-admin";

    /** The group name a {@code 'U'} row's account must join. */
    private static final String USER_GROUP = "carddemo-user";

    /** The identifier under test, at the eight characters {@code SEC-USR-ID PIC X(08)} declares. */
    private static final String USER_ID = "TESTUSR1";

    /**
     * A quote-bearing identifier, admissible because the request boundary bounds width and not charset.
     *
     * <p>Assumptions: {@code CreateUserRequest} constrains {@code userId} by width and presence only --
     * {@code @NotBlank}, {@code @Size(max = 8)} and an unanchored {@code @Schema(pattern = "\S")} facet
     * that any one non-whitespace character satisfies -- and {@code UserService.foldedKey} only trims and
     * upper-cases, so this value reaches the provisioning path unchanged. It is upper case already, so
     * the test asserts the same bytes the service would receive from a create request.</p>
     */
    private static final String QUOTE_BEARING_USER_ID = "A\"B";

    /**
     * A backslash-terminated identifier, which is the harder of the two escapes.
     *
     * <p>Assumptions: a trailing backslash is worse than an interior quote under concatenation, because
     * it escapes the very quote that CLOSES the member -- so the document does not merely gain a stray
     * quote, it loses its structure from that point on.</p>
     */
    private static final String BACKSLASH_BEARING_USER_ID = "ABCDEFG\\";

    /**
     * An identifier shaped to inject a third member, at seven characters.
     *
     * <p>Assumptions: this is the value that makes the defect a SECURITY finding rather than a
     * robustness one. Under concatenation it produced {@code {"username":"","X":"","password":"..."}} --
     * valid JSON with three members, an EMPTY username, and a member name the payload's author never
     * wrote. A collector parsing that document reads a credential it cannot attribute to anybody, and
     * nothing in the pipeline had failed.</p>
     */
    private static final String INJECTION_SHAPED_USER_ID = "\",\"X\":\"";

    /** Reads the published credential payload back; owned by the test, never the subject's writer. */
    private static final ObjectMapper PAYLOAD_READER = new ObjectMapper();

    /** The given name under test, recorded as {@code given_name}. */
    private static final String FIRST_NAME = "Ada";

    /** The family name under test, recorded as {@code family_name}. */
    private static final String LAST_NAME = "Lovelace";

    /** The subject the substituted provider mints, standing in for the value the real one assigns. */
    private static final UUID SUBJECT = UUID.fromString("3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");

    /**
     * The managed-secret name prefix under test.
     *
     * <p>Assumptions: it carries no trailing separator, because the service supplies its own -- so a prefix
     * that ended in one would produce a doubled separator and the assertions below would catch it.</p>
     */
    private static final String SECRET_PREFIX = "carddemo/dev/auth";

    /** The customer-managed key a created credential entry is expected to name. */
    private static final String SECRET_KMS_KEY_ARN =
            "arn:aws:kms:us-east-1:000000000000:key/00000000-0000-4000-8000-000000000000";

    /** The temporary-password length under test, inside the admitted range and not its default. */
    private static final int PASSWORD_LENGTH = 20;

    /**
     * The expected credential-entry name for {@link #USER_ID}.
     *
     * <p>Assumptions: the digest is written out rather than recomputed by the test with the same expression
     * the service uses. A test that recomputed it would pass for any naming scheme the service happened to
     * implement, including one that leaked the identifier; a literal is what pins the scheme.</p>
     */
    private static final String EXPECTED_SECRET_NAME =
            SECRET_PREFIX + "/runtime-user/215c33b0d43d5eb833ece113f8c6660d";

    /** The substituted provider whose calls are captured; re-created before each test. */
    private CognitoIdentityProviderClient provider;

    /** The substituted managed-secret client whose calls are captured; re-created before each test. */
    private SecretsManagerClient secrets;

    /** The subject under test, bound to the substituted clients and the configured values. */
    private CognitoUserProvisioningService service;

    /**
     * Re-creates the substituted clients and the subject before each test.
     *
     * <p>Assumptions: a fresh substitute per test is what keeps the no-interaction assertions meaningful. A
     * shared substitute would carry the previous test's calls, so the assertion that an out-of-domain type
     * reaches no provider at all would pass or fail on execution order.</p>
     */
    @BeforeEach
    void setUp() {
        this.provider = mock(CognitoIdentityProviderClient.class);
        this.secrets = mock(SecretsManagerClient.class);
        this.service = new CognitoUserProvisioningService(this.provider, this.secrets, POOL_ID,
                ADMIN_GROUP, USER_GROUP, SECRET_PREFIX, SECRET_KMS_KEY_ARN, PASSWORD_LENGTH);
    }

    /**
     * Builds a create response describing an account that carries the given attributes.
     *
     * @param attributes the attributes the provider is to report on the created account
     * @return a well-formed create response; never {@code null}
     */
    private static AdminCreateUserResponse responseWith(AttributeType... attributes) {
        return AdminCreateUserResponse.builder()
                .user(UserType.builder().username(USER_ID).attributes(attributes).build())
                .build();
    }

    /**
     * Builds one provider attribute.
     *
     * @param name the attribute name, including any {@code custom:} prefix
     * @param value the attribute value
     * @return the attribute; never {@code null}
     */
    private static AttributeType attribute(String name, String value) {
        return AttributeType.builder().name(name).value(value).build();
    }

    /**
     * Stubs the create call to report the standard subject.
     *
     * @return the response the substitute will return, so a caller may assert against it
     */
    private AdminCreateUserResponse stubSuccessfulCreate() {
        AdminCreateUserResponse created = responseWith(
                attribute(CognitoUserProvisioningService.ATTRIBUTE_SUBJECT, SUBJECT.toString()));
        when(this.provider.adminCreateUser(any(AdminCreateUserRequest.class))).thenReturn(created);
        return created;
    }

    /**
     * Captures the single create request the substitute received and projects one member off it.
     *
     * @param <T> the projected member's type
     * @param projection reads the member of interest off the captured request
     * @return the projection of the captured request
     */
    private <T> T capturedCreate(Function<AdminCreateUserRequest, T> projection) {
        ArgumentCaptor<AdminCreateUserRequest> captor =
                ArgumentCaptor.forClass(AdminCreateUserRequest.class);
        verify(this.provider).adminCreateUser(captor.capture());
        return projection.apply(captor.getValue());
    }

    /**
     * Captures the single membership request the substitute received.
     *
     * @return the captured request
     */
    private AdminAddUserToGroupRequest capturedMembership() {
        ArgumentCaptor<AdminAddUserToGroupRequest> captor =
                ArgumentCaptor.forClass(AdminAddUserToGroupRequest.class);
        verify(this.provider).adminAddUserToGroup(captor.capture());
        return captor.getValue();
    }

    /**
     * Verifies an administrator row's account is created with the three attributes and joins the
     * administrator group.
     *
     * <p>Assumptions: the suppression and the PRESENCE of a temporary credential are asserted together,
     * because the two are one decision and either alone is wrong. Suppression is required by the pool's
     * schema, which declares no email and no phone attribute, so there is no address a delivered message
     * could reach; and once delivery is suppressed the temporary credential is the only thing that makes
     * the account reachable, because the value the pool would otherwise generate is sent nowhere.
     * Refactoring Rationale: this case asserted the temporary credential was ABSENT, which pinned the
     * defect -- an account nobody could sign into.</p>
     */
    @Test
    @DisplayName("an administrator row's account carries the three attributes and joins the admin group")
    void anAdministratorRowsAccountJoinsTheAdministratorGroup() {
        stubSuccessfulCreate();

        ProvisionedIdentity provisioned = this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_ADMIN);

        assertThat(provisioned.subject()).isEqualTo(SUBJECT);
        // WHY : Assumptions: the returned pair names the managed entry the credential was published to
        //       and never the credential, so an administrator is told where to collect it while the
        //       value stays in the store. The name is derived, so it is asserted against the same
        //       derivation the service publishes rather than against a copied literal.
        assertThat(provisioned.credentialSecretName())
                .isEqualTo(this.service.credentialSecretName(USER_ID));
        assertThat(provisioned.credentialSecretName())
                .isNotEqualTo(capturedCreate(AdminCreateUserRequest::temporaryPassword));
        assertThat(capturedCreate(AdminCreateUserRequest::userPoolId)).isEqualTo(POOL_ID);
        assertThat(capturedCreate(AdminCreateUserRequest::username)).isEqualTo(USER_ID);
        assertThat(capturedCreate(AdminCreateUserRequest::messageAction))
                .isEqualTo(MessageActionType.SUPPRESS);
        assertThat(capturedCreate(AdminCreateUserRequest::temporaryPassword)).isNotNull();
        // WHY : Assumptions: the attributes are asserted in exact order and exact membership, not merely
        //       "contains". The seed bootstrap sends these same three, so an extra or renamed attribute here
        //       would leave a runtime-created account and a seeded one describable by different attribute
        //       sets -- a divergence the app client's read-attribute list would surface only at sign-on.
        assertThat(capturedCreate(AdminCreateUserRequest::userAttributes))
                .extracting(AttributeType::name, AttributeType::value)
                .containsExactly(
                        tuple(CognitoUserProvisioningService.ATTRIBUTE_GIVEN_NAME, FIRST_NAME),
                        tuple(CognitoUserProvisioningService.ATTRIBUTE_FAMILY_NAME, LAST_NAME),
                        tuple(CognitoUserProvisioningService.ATTRIBUTE_USER_TYPE,
                                CognitoUserProvisioningService.USER_TYPE_ADMIN));

        AdminAddUserToGroupRequest membership = capturedMembership();
        assertThat(membership.userPoolId()).isEqualTo(POOL_ID);
        assertThat(membership.username()).isEqualTo(USER_ID);
        assertThat(membership.groupName()).isEqualTo(ADMIN_GROUP);
    }

    /**
     * Verifies an ordinary row's account joins the user group and not the administrator group.
     *
     * <p>Assumptions: the group is asserted to be the user group AND not the administrator group, which is
     * one assertion more than it looks. Group membership is what the signed claim is derived from, so a
     * lookup that fell through to the administrator name would grant the authority to create administrators
     * to a row that records {@code 'U'} -- and the row's own type would never contradict it, because no
     * authorization decision reads the row.</p>
     */
    @Test
    @DisplayName("an ordinary row's account joins the user group and not the administrator group")
    void anOrdinaryRowsAccountJoinsTheUserGroup() {
        stubSuccessfulCreate();

        this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_USER);

        assertThat(capturedMembership().groupName()).isEqualTo(USER_GROUP).isNotEqualTo(ADMIN_GROUP);
        assertThat(capturedCreate(AdminCreateUserRequest::userAttributes))
                .extracting(AttributeType::name, AttributeType::value)
                .contains(tuple(CognitoUserProvisioningService.ATTRIBUTE_USER_TYPE,
                        CognitoUserProvisioningService.USER_TYPE_USER));
    }

    /**
     * Verifies a user type outside the two-value domain is refused before any provider call.
     *
     * <p>Assumptions: this is the assertion the whole class exists for. The group lookup has no default arm,
     * so an out-of-domain type has no group to join; refusing it AFTER the create call would leave a pool
     * account that authenticates and carries no authority, and the caller would have to compensate for a
     * failure the service could have refused for free. Observing that the collaborator was never touched is
     * the only way to state that ordering.</p>
     */
    @Test
    @DisplayName("a user type outside the two-value domain is refused before any provider call")
    void anOutOfDomainUserTypeIsRefusedBeforeAnyProviderCall() {
        assertThatThrownBy(() -> this.service.provision(USER_ID, FIRST_NAME, LAST_NAME, "X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"A\"")
                .hasMessageContaining("\"U\"");

        verifyNoInteractions(this.provider);
    }

    /**
     * Verifies a lower-case user type is refused, because the domain is the two upper-case literals.
     *
     * <p>Assumptions: case is significant here and the refusal is deliberate rather than an oversight. The
     * two condition names at {@code app/cpy/COCOM01Y.cpy} L27 and L28 test upper-case literals, and the
     * column carries a one-character check constraint on the same two values, so accepting {@code 'a'} would
     * provision a pool account whose row the database then refuses.</p>
     */
    @Test
    @DisplayName("a lower-case user type is refused, because the domain is the two upper-case literals")
    void aLowerCaseUserTypeIsRefused() {
        assertThatThrownBy(() -> this.service.provision(USER_ID, FIRST_NAME, LAST_NAME, "a"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(this.provider);
    }

    /**
     * Verifies a null user type is refused before any provider call.
     *
     * <p>Assumptions: the lookup compares the literals against the argument rather than the reverse, so a
     * null argument reaches the same refusal as an out-of-domain one instead of raising a null-pointer
     * failure. The distinction matters because the refusal names the domain and the null-pointer failure
     * would name a line.</p>
     */
    @Test
    @DisplayName("a null user type is refused before any provider call")
    void aNullUserTypeIsRefusedBeforeAnyProviderCall() {
        assertThatThrownBy(() -> this.service.provision(USER_ID, FIRST_NAME, LAST_NAME, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(this.provider);
    }

    /**
     * Verifies the subject is read out of the create response by attribute name, not by position.
     *
     * <p>Assumptions: the subject is placed LAST behind two attributes the provider echoes back, so a
     * positional read would return a name where a subject belongs. The provider documents no attribute
     * order, so a positional read is not merely fragile -- it is wrong on a response that is entirely well
     * formed, which is the failure a test has to catch because production would surface it as a row bound to
     * a nonsense subject rather than as an error.</p>
     */
    @Test
    @DisplayName("the subject is read out of the create response by attribute name, not by position")
    void theSubjectIsReadByNameAndNotByPosition() {
        when(this.provider.adminCreateUser(any(AdminCreateUserRequest.class))).thenReturn(responseWith(
                attribute(CognitoUserProvisioningService.ATTRIBUTE_GIVEN_NAME, FIRST_NAME),
                attribute(CognitoUserProvisioningService.ATTRIBUTE_FAMILY_NAME, LAST_NAME),
                attribute(CognitoUserProvisioningService.ATTRIBUTE_SUBJECT, SUBJECT.toString())));

        UUID subject = this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_USER).subject();

        assertThat(subject).isEqualTo(SUBJECT);
    }

    /**
     * Verifies a created account carrying no subject attribute is refused and names the identifier.
     *
     * <p>Assumptions: the membership call must not have happened. The subject is the value the row is bound
     * to, so a provisioning run that cannot report one has produced nothing usable; adding the account to a
     * group afterwards would leave an authorized identity that no row will ever reference, which is exactly
     * the orphan the withdrawal exists to prevent.</p>
     */
    @Test
    @DisplayName("a created account carrying no subject attribute is refused and names the identifier")
    void aResponseWithNoSubjectAttributeIsRefused() {
        when(this.provider.adminCreateUser(any(AdminCreateUserRequest.class))).thenReturn(responseWith(
                attribute(CognitoUserProvisioningService.ATTRIBUTE_GIVEN_NAME, FIRST_NAME)));

        assertThatThrownBy(() -> this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CognitoUserProvisioningService.ATTRIBUTE_SUBJECT)
                .hasMessageContaining(USER_ID);

        verify(this.provider, never()).adminAddUserToGroup(any(AdminAddUserToGroupRequest.class));
    }

    /**
     * Verifies a response describing no created account at all is refused.
     *
     * <p>Assumptions: a null created account and an attribute list omitting the subject are two different
     * absences, and both are well-formed responses. This case covers the first; the case above covers the
     * second. Reading the list without the guard would raise a null-pointer failure naming the reading
     * method rather than a statement naming the account it could not describe.</p>
     */
    @Test
    @DisplayName("a response describing no created account at all is refused")
    void aResponseWithNoCreatedAccountIsRefused() {
        when(this.provider.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(AdminCreateUserResponse.builder().build());

        assertThatThrownBy(() -> this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_ADMIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(USER_ID);
    }

    /**
     * Verifies a subject that is not a UUID is refused and keeps the parse failure as its cause.
     *
     * <p>Assumptions: the cause is asserted rather than only the type, because the column that stores the
     * subject is typed and a value that will not parse has to be reported as the provider's fault. Losing
     * the cause would leave an operator unable to tell a malformed value from an absent one.</p>
     */
    @Test
    @DisplayName("a subject that is not a UUID is refused and keeps the parse failure as its cause")
    void aNonUuidSubjectIsRefused() {
        when(this.provider.adminCreateUser(any(AdminCreateUserRequest.class))).thenReturn(responseWith(
                attribute(CognitoUserProvisioningService.ATTRIBUTE_SUBJECT, "not-a-uuid")));

        assertThatThrownBy(() -> this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(USER_ID)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies a membership failure after creation propagates, so the caller knows to withdraw.
     *
     * <p>Assumptions: absorbing this failure would be the dangerous choice, not the tolerant one. An account
     * with no group is refused at every guarded route, so the visible outcome of a swallowed membership
     * failure is a person who can sign on and do nothing -- with a row that records a type nobody is
     * enforcing. Propagating hands the decision to the caller, which is the only party that knows whether
     * its own write committed.</p>
     */
    @Test
    @DisplayName("a membership failure after creation withdraws the account and propagates")
    void aMembershipFailureAfterCreationWithdrawsAndPropagates() {
        stubSuccessfulCreate();
        when(this.provider.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenThrow(NotAuthorizedException.builder().message("refused").build());

        assertThatThrownBy(() -> this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_ADMIN))
                .isInstanceOf(NotAuthorizedException.class);

        verify(this.provider).adminDeleteUser(any(AdminDeleteUserRequest.class));
        verify(this.secrets, never()).createSecret(any(CreateSecretRequest.class));
    }

    /**
     * Verifies a create response describing no subject withdraws the account it had just made.
     *
     * <p>Assumptions: this is the second of the two post-create failures and it is asserted separately
     * because it fails at a different statement. The membership case fails at a provider call, so a handler
     * wrapping only the provider calls would catch it; this one fails while READING the create response, so
     * it catches a handler drawn one statement too narrowly.</p>
     */
    @Test
    @DisplayName("a response describing no subject withdraws the account rather than orphaning it")
    void aResponseWithNoSubjectWithdrawsTheAccount() {
        when(this.provider.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(responseWith(attribute("given_name", FIRST_NAME)));

        assertThatThrownBy(() -> this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_USER))
                .isInstanceOf(IllegalStateException.class);

        verify(this.provider).adminDeleteUser(any(AdminDeleteUserRequest.class));
    }

    /**
     * Verifies a failed credential publication withdraws the account, so no unreachable one survives.
     *
     * <p>Assumptions: an account whose credential could not be published is exactly the condition the
     * handover exists to prevent -- it can authenticate and nobody can obtain the value that would let them.
     * Leaving it would also make the identifier unusable, because a later create meets the pool's
     * duplicate-username condition, which this service deliberately does not translate into a client-facing
     * conflict.</p>
     */
    @Test
    @DisplayName("a failed credential publication withdraws the account it was published for")
    void aFailedCredentialPublicationWithdrawsTheAccount() {
        stubSuccessfulCreate();
        when(this.secrets.createSecret(any(CreateSecretRequest.class)))
                .thenThrow(NotAuthorizedException.builder().message("refused").build());

        assertThatThrownBy(() -> this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_USER))
                .isInstanceOf(NotAuthorizedException.class);

        verify(this.provider).adminDeleteUser(any(AdminDeleteUserRequest.class));
    }

    /**
     * Verifies a failed withdrawal is attached to the original failure rather than substituted for it.
     *
     * <p>Assumptions: the caller asked why provisioning failed, and a cleanup failure reported in place of
     * that would make a transient provider fault with clean cleanup indistinguishable from a genuine orphan.
     * The suppressed exception is what carries the second fact to a handler that logs the whole throwable.</p>
     */
    @Test
    @DisplayName("a failed withdrawal is suppressed onto the provisioning failure, not substituted for it")
    void aFailedWithdrawalIsSuppressedOntoTheProvisioningFailure() {
        stubSuccessfulCreate();
        NotAuthorizedException membershipRefused =
                NotAuthorizedException.builder().message("membership refused").build();
        NotAuthorizedException withdrawalRefused =
                NotAuthorizedException.builder().message("withdrawal refused").build();
        when(this.provider.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenThrow(membershipRefused);
        when(this.provider.adminDeleteUser(any(AdminDeleteUserRequest.class)))
                .thenThrow(withdrawalRefused);

        assertThatThrownBy(() -> this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_USER))
                .isSameAs(membershipRefused)
                .satisfies(raised -> assertThat(raised.getSuppressed())
                        .containsExactly(withdrawalRefused));
    }

    /**
     * Verifies withdrawing deletes the pool account for the identifier in the configured pool.
     *
     * <p>Assumptions: the pool identifier is asserted alongside the username because the withdrawal is a
     * delete. A call scoped to the wrong pool would either fail or, in a deployment sharing an account
     * across environments, remove somebody else's identity.</p>
     */
    @Test
    @DisplayName("withdrawing deletes the pool account for the identifier in the configured pool")
    void withdrawDeletesThePoolAccount() {
        this.service.withdraw(USER_ID);

        ArgumentCaptor<AdminDeleteUserRequest> delete =
                ArgumentCaptor.forClass(AdminDeleteUserRequest.class);
        verify(this.provider).adminDeleteUser(delete.capture());
        assertThat(delete.getValue().userPoolId()).isEqualTo(POOL_ID);
        assertThat(delete.getValue().username()).isEqualTo(USER_ID);
    }

    /**
     * Verifies withdrawing an account the pool never held is treated as success.
     *
     * <p>Assumptions: this is what makes the withdrawal safe to call unconditionally on a failure path. A
     * caller that failed BEFORE provisioning and a caller that failed after it must reach the same clean
     * state, and a caller cannot generally tell which of the two it is. Raising here would make the
     * compensating action report a second, misleading cause on top of the real one.</p>
     */
    @Test
    @DisplayName("withdrawing an account the pool never held is treated as success")
    void withdrawTreatsAnAbsentAccountAsSuccess() {
        when(this.provider.adminDeleteUser(any(AdminDeleteUserRequest.class)))
                .thenThrow(UserNotFoundException.builder().message("absent").build());

        this.service.withdraw(USER_ID);

        verify(this.provider).adminDeleteUser(any(AdminDeleteUserRequest.class));
    }

    /**
     * Verifies withdrawing propagates every provider failure other than an absent account.
     *
     * <p>Assumptions: only the absent-account case is swallowed, and this asserts the boundary of that
     * exemption. An account that could not be deleted is an orphan an operator has to know about; a catch
     * that widened to every provider failure would convert a durable inconsistency into a silent one, which
     * is the failure mode the narrow catch was written to avoid.</p>
     */
    @Test
    @DisplayName("withdrawing propagates every provider failure other than an absent account")
    void withdrawPropagatesEveryOtherProviderFailure() {
        when(this.provider.adminDeleteUser(any(AdminDeleteUserRequest.class)))
                .thenThrow(NotAuthorizedException.builder().message("refused").build());

        assertThatThrownBy(() -> this.service.withdraw(USER_ID))
                .isInstanceOf(NotAuthorizedException.class);
    }

    /**
     * Verifies the account's attributes carry no credential and no contact route.
     *
     * <p>Assumptions: the three attributes are the whole of what the pool's schema declares, and a credential
     * must never travel as one of them. That is the defect the migration of this record is undoing --
     * {@code app/cpy/CSUSR01Y.cpy} L21 stores an eight-character password in the clear and
     * {@code app/cbl/COUSR02C.cbl} L169 writes it back onto a screen -- so a password among the ATTRIBUTES
     * would be a value stored on the identity rather than a one-time value the pool forces to be replaced.
     * An email or phone attribute is refused for a different reason: neither exists in the pool's schema, so
     * either would fail the call, and adding one would add a field the reference record has no analogue
     * for.</p>
     *
     * <p>Refactoring Rationale: this case previously also asserted that the request carried NO temporary
     * password, and that assertion was withdrawn because it pinned the defect rather than the contract. With
     * delivery suppressed and no temporary password, the pool generates a value and delivers it nowhere, so
     * the account could never be signed in to at all. The temporary password is now required and is asserted
     * by the cases below; what remains here is that no credential travels as an ATTRIBUTE.</p>
     */
    @Test
    @DisplayName("the account's attributes carry no credential and no contact route")
    void theAccountAttributesCarryNoCredentialOrContactRoute() {
        stubSuccessfulCreate();

        this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_USER);

        List<AttributeType> attributes = capturedCreate(AdminCreateUserRequest::userAttributes);
        assertThat(attributes).hasSize(3);
        assertThat(attributes).extracting(AttributeType::name)
                .doesNotContain("email", "phone_number", "password");
    }

    /**
     * Verifies the account is created with a temporary password, so its owner can actually be handed one.
     *
     * <p>Assumptions: TEMPORARY and not permanent is the property being asserted, and the SDK expresses that
     * by which builder member carries the value -- {@code temporaryPassword} places the account in its
     * force-change state, so the value buys one sign-in and is inert afterwards. That is what makes storing
     * it acceptable at all, and a permanent password would make the stored value the person's real
     * credential.</p>
     */
    @Test
    @DisplayName("the account is created with a temporary password of the configured length")
    void theAccountIsCreatedWithATemporaryPassword() {
        stubSuccessfulCreate();

        this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_USER);

        assertThat(capturedCreate(AdminCreateUserRequest::temporaryPassword))
                .as("suppressed delivery with no temporary password leaves an account nobody can sign into")
                .isNotNull()
                .hasSize(PASSWORD_LENGTH);
        assertThat(capturedCreate(AdminCreateUserRequest::messageAction))
                .isEqualTo(MessageActionType.SUPPRESS);
    }

    /**
     * Verifies the generated password draws from all four character classes.
     *
     * <p>Assumptions: every class is asserted present rather than the value merely being asserted long
     * enough. Including all four is what lets one generator satisfy any policy that requires a SUBSET of
     * them, and a value that happened to omit a class would be refused by the pool at the create call -- so
     * the failure it produces is an intermittent create, which no caller can act on and no timing-free test
     * would reproduce.</p>
     */
    @Test
    @DisplayName("the generated password carries a lower case, an upper case, a digit and a symbol")
    void theGeneratedPasswordDrawsFromEveryCharacterClass() {
        stubSuccessfulCreate();

        this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_USER);

        String generated = capturedCreate(AdminCreateUserRequest::temporaryPassword);
        assertThat(generated).containsPattern("[a-z]").containsPattern("[A-Z]")
                .containsPattern("[0-9]").containsPattern("[!#%*+:=?@^_~-]");
    }

    /**
     * Verifies the credential is published to a derived entry, encrypted with the configured key.
     *
     * <p>Assumptions: the entry NAME is asserted against a literal, and the payload is asserted to pair the
     * identifier with the very password the create call carried. The pairing is what makes a retrieved value
     * usable -- a holder must be able to tell which identity it opens -- and asserting it against the
     * captured create argument is what proves the stored value is the one the pool accepted rather than a
     * second generated value.</p>
     */
    @Test
    @DisplayName("the credential is published to the derived entry under the configured key")
    void theCredentialIsPublishedToTheDerivedEntry() {
        stubSuccessfulCreate();

        this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_USER);

        String generated = capturedCreate(AdminCreateUserRequest::temporaryPassword);
        ArgumentCaptor<CreateSecretRequest> published =
                ArgumentCaptor.forClass(CreateSecretRequest.class);
        verify(this.secrets).createSecret(published.capture());
        assertThat(published.getValue().name()).isEqualTo(EXPECTED_SECRET_NAME);
        assertThat(published.getValue().kmsKeyId()).isEqualTo(SECRET_KMS_KEY_ARN);
        assertThat(published.getValue().secretString())
                .isEqualTo("{\"username\":\"" + USER_ID + "\",\"password\":\"" + generated + "\"}");
        assertThat(published.getValue().description())
                .as("a description is readable without decrypting the value, so it names no identity")
                .doesNotContain(USER_ID);
    }

    /**
     * Provisions one identity and returns the credential document that was published, parsed.
     *
     * <p>Assumptions: the document is PARSED rather than string-matched. A string assertion would have
     * to spell the escaping the implementation happens to use, so it would pass for a scheme that
     * escaped nothing as readily as for one that escaped correctly; parsing asserts the only property
     * that matters to a collector, which is that the bytes are a JSON object carrying the value back
     * unchanged.</p>
     *
     * @param userId the identifier to provision, already in the folded form the service receives
     * @return the parsed payload of the single created entry, never {@code null}
     * @throws Exception if the published value is not parseable JSON, which is itself the defect
     */
    private JsonNode publishedCredentialFor(String userId) throws Exception {
        stubSuccessfulCreate();
        this.service.provision(userId, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_USER);

        ArgumentCaptor<CreateSecretRequest> published =
                ArgumentCaptor.forClass(CreateSecretRequest.class);
        verify(this.secrets).createSecret(published.capture());
        return PAYLOAD_READER.readTree(published.getValue().secretString());
    }

    /**
     * Verifies a quote-bearing identifier is published as an escaped value rather than breaking the
     * document.
     *
     * <p>Assumptions: the assertion is on the PARSED username, so it fails both for a document that
     * cannot be parsed and for one that parses to a truncated identifier. Under the concatenation this
     * replaced, {@code A"B} closed the username member after {@code A} and left {@code B} where a
     * member name was expected, so the stored value was not JSON at all and the account's credential
     * could not be collected.</p>
     *
     * @throws Exception if the published value is not parseable JSON
     */
    @Test
    @DisplayName("a quote-bearing identifier is escaped, so the credential document still parses")
    void aQuoteBearingIdentifierIsEscaped() throws Exception {
        JsonNode payload = publishedCredentialFor(QUOTE_BEARING_USER_ID);

        String generated = capturedCreate(AdminCreateUserRequest::temporaryPassword);
        assertThat(payload.path("username").asText())
                .as("the identifier must round-trip exactly, not merely survive")
                .isEqualTo(QUOTE_BEARING_USER_ID);
        assertThat(payload.path("password").asText()).isEqualTo(generated);
    }

    /**
     * Verifies a backslash-terminated identifier is published as an escaped value.
     *
     * <p>Assumptions: this case is separate from the quote above because the two failed differently
     * under concatenation. A trailing backslash escaped the closing quote of the username member, so
     * the password's own member name and value were absorbed into the username's string -- the document
     * then held one member whose value was a run of the remaining text, which is a shape a lenient
     * parser can accept while returning the wrong credential.</p>
     *
     * @throws Exception if the published value is not parseable JSON
     */
    @Test
    @DisplayName("a backslash-bearing identifier is escaped, so the password member survives")
    void aBackslashBearingIdentifierIsEscaped() throws Exception {
        JsonNode payload = publishedCredentialFor(BACKSLASH_BEARING_USER_ID);

        String generated = capturedCreate(AdminCreateUserRequest::temporaryPassword);
        assertThat(payload.path("username").asText()).isEqualTo(BACKSLASH_BEARING_USER_ID);
        assertThat(payload.path("password").asText())
                .as("the closing quote of the username must not have been escaped away")
                .isEqualTo(generated);
    }

    /**
     * Verifies an identifier shaped to inject a third member cannot add one.
     *
     * <p>Assumptions: the member COUNT is asserted, not only the two values, because that is the
     * property injection defeats. This identifier produced valid three-member JSON under
     * concatenation, so every assertion that only checked parseability or only read the two expected
     * members would have passed while the document carried a member its author never wrote and an
     * empty username.</p>
     *
     * @throws Exception if the published value is not parseable JSON
     */
    @Test
    @DisplayName("an identifier shaped like a JSON fragment cannot add a member to the document")
    void anInjectionShapedIdentifierCannotAddAMember() throws Exception {
        JsonNode payload = publishedCredentialFor(INJECTION_SHAPED_USER_ID);

        String generated = capturedCreate(AdminCreateUserRequest::temporaryPassword);
        assertThat(payload.size())
                .as("the document carries exactly the two members this service writes")
                .isEqualTo(2);
        assertThat(payload.path("username").asText()).isEqualTo(INJECTION_SHAPED_USER_ID);
        assertThat(payload.path("password").asText()).isEqualTo(generated);
    }

    /**
     * Verifies the entry's name discloses neither the identifier nor the password.
     *
     * <p>Assumptions: this is asserted separately from the name literal above because the two would fail for
     * different reasons. The literal catches a change of scheme; this catches a scheme that still looks
     * derived while embedding the value it was meant to hide -- for instance a prefix plus the identifier
     * plus a digest.</p>
     */
    @Test
    @DisplayName("the credential entry's name discloses neither the identifier nor the password")
    void theCredentialEntryNameDisclosesNothing() {
        stubSuccessfulCreate();

        this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_USER);

        String generated = capturedCreate(AdminCreateUserRequest::temporaryPassword);
        ArgumentCaptor<CreateSecretRequest> published =
                ArgumentCaptor.forClass(CreateSecretRequest.class);
        verify(this.secrets).createSecret(published.capture());
        assertThat(published.getValue().name()).doesNotContain(USER_ID).doesNotContain(generated)
                .startsWith(SECRET_PREFIX + "/runtime-user/");
    }

    /**
     * Verifies an entry that already exists is written to rather than treated as a conflict.
     *
     * <p>Assumptions: the name is derived from the identifier, so this condition means the identifier has
     * been provisioned before -- a retry, or a create following a delete. The value that works is the one the
     * pool has just accepted, so the entry must carry it; refusing would leave a stale value that opens
     * nothing while the account waits for a credential nobody can collect.</p>
     */
    @Test
    @DisplayName("an existing credential entry is written to, which is what makes a retry idempotent")
    void anExistingCredentialEntryIsWrittenTo() {
        stubSuccessfulCreate();
        when(this.secrets.createSecret(any(CreateSecretRequest.class)))
                .thenThrow(ResourceExistsException.builder().message("exists").build());

        this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_USER);

        String generated = capturedCreate(AdminCreateUserRequest::temporaryPassword);
        ArgumentCaptor<PutSecretValueRequest> written =
                ArgumentCaptor.forClass(PutSecretValueRequest.class);
        verify(this.secrets).putSecretValue(written.capture());
        assertThat(written.getValue().secretId()).isEqualTo(EXPECTED_SECRET_NAME);
        assertThat(written.getValue().secretString()).contains(generated);
    }

    /**
     * Verifies withdrawing discards the credential entry before it deletes the account.
     *
     * <p>Assumptions: the ORDER is asserted rather than only the pair of calls. A failure between the two
     * leaves whichever half has not run, and the two halves are not equivalent: an entry outliving its
     * account holds a value that opens nothing and is overwritten by the next create, whereas an account
     * outliving its entry holds a credential nobody can collect -- the exact condition the handover exists to
     * prevent. Discarding first is what makes the survivable failure the one that survives.</p>
     */
    @Test
    @DisplayName("withdrawing discards the credential entry before deleting the account")
    void withdrawDiscardsTheCredentialBeforeDeletingTheAccount() {
        this.service.withdraw(USER_ID);

        ArgumentCaptor<DeleteSecretRequest> discarded =
                ArgumentCaptor.forClass(DeleteSecretRequest.class);
        InOrder ordered = inOrder(this.secrets, this.provider);
        ordered.verify(this.secrets).deleteSecret(discarded.capture());
        ordered.verify(this.provider).adminDeleteUser(any(AdminDeleteUserRequest.class));
        assertThat(discarded.getValue().secretId()).isEqualTo(EXPECTED_SECRET_NAME);
        assertThat(discarded.getValue().forceDeleteWithoutRecovery())
                .as("a scheduled deletion reserves the derived name and would make the identifier "
                        + "unusable for the whole recovery window")
                .isTrue();
    }

    /**
     * Verifies withdrawing treats an absent credential entry as success.
     *
     * <p>Assumptions: this is what makes the withdrawal safe on every cleanup path. The failure being
     * compensated may have preceded the publication, so the entry may never have existed, and a caller
     * generally cannot tell.</p>
     */
    @Test
    @DisplayName("withdrawing an identifier with no credential entry is treated as success")
    void withdrawTreatsAnAbsentCredentialEntryAsSuccess() {
        when(this.secrets.deleteSecret(any(DeleteSecretRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message("absent").build());

        this.service.withdraw(USER_ID);

        verify(this.provider).adminDeleteUser(any(AdminDeleteUserRequest.class));
    }
}
