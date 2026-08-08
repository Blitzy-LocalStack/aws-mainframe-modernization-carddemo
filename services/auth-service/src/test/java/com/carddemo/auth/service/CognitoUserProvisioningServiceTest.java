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
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminRemoveUserFromGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

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

    /** The given name under test, recorded as {@code given_name}. */
    private static final String FIRST_NAME = "Ada";

    /** The family name under test, recorded as {@code family_name}. */
    private static final String LAST_NAME = "Lovelace";

    /** The subject the substituted provider mints, standing in for the value the real one assigns. */
    private static final UUID SUBJECT = UUID.fromString("3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");

    /** The substituted provider whose calls are captured; re-created before each test. */
    private CognitoIdentityProviderClient provider;

    /** The subject under test, bound to the substituted provider and the two group names. */
    private CognitoUserProvisioningService service;

    /**
     * Re-creates the substituted provider and the subject before each test.
     *
     * <p>Assumptions: a fresh substitute per test is what keeps the no-interaction assertions meaningful. A
     * shared substitute would carry the previous test's calls, so the assertion that an out-of-domain type
     * reaches no provider at all would pass or fail on execution order.</p>
     */
    @BeforeEach
    void setUp() {
        this.provider = mock(CognitoIdentityProviderClient.class);
        this.service = new CognitoUserProvisioningService(this.provider, POOL_ID, ADMIN_GROUP, USER_GROUP);
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
     * <p>Assumptions: the suppression and the absent temporary credential are asserted explicitly because
     * dropping either is a silent change in behaviour rather than a failure a reader would notice. The pool
     * declares no email or phone attribute, so there is no address a delivered message could reach.</p>
     */
    @Test
    @DisplayName("an administrator row's account carries the three attributes and joins the admin group")
    void anAdministratorRowsAccountJoinsTheAdministratorGroup() {
        stubSuccessfulCreate();

        UUID subject = this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_ADMIN);

        assertThat(subject).isEqualTo(SUBJECT);
        assertThat(capturedCreate(AdminCreateUserRequest::userPoolId)).isEqualTo(POOL_ID);
        assertThat(capturedCreate(AdminCreateUserRequest::username)).isEqualTo(USER_ID);
        assertThat(capturedCreate(AdminCreateUserRequest::messageAction))
                .isEqualTo(MessageActionType.SUPPRESS);
        assertThat(capturedCreate(AdminCreateUserRequest::temporaryPassword)).isNull();
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
                CognitoUserProvisioningService.USER_TYPE_USER);

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
    @DisplayName("a membership failure after creation propagates, so the caller knows to withdraw")
    void aMembershipFailureAfterCreationPropagates() {
        stubSuccessfulCreate();
        when(this.provider.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenThrow(NotAuthorizedException.builder().message("refused").build());

        assertThatThrownBy(() -> this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_ADMIN))
                .isInstanceOf(NotAuthorizedException.class);
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
     * Verifies no provisioning call carries a credential or a contact attribute.
     *
     * <p>Assumptions: the absence of a credential is asserted because its presence is the defect the
     * migration of this record is undoing -- {@code app/cpy/CSUSR01Y.cpy} L21 stores an eight-character
     * password in the clear and {@code app/cbl/COUSR02C.cbl} L169 writes it back onto a screen. Neither a
     * temporary password on the request nor an email attribute may reappear here, because either would put a
     * credential, or a route to one, back on an outbound path in the one service whose purpose is that
     * credentials stop travelling.</p>
     */
    @Test
    @DisplayName("no provisioning call carries a credential or a contact attribute")
    void noProvisioningCallCarriesACredentialOrContactAttribute() {
        stubSuccessfulCreate();

        this.service.provision(USER_ID, FIRST_NAME, LAST_NAME,
                CognitoUserProvisioningService.USER_TYPE_USER);

        List<AttributeType> attributes = capturedCreate(AdminCreateUserRequest::userAttributes);
        assertThat(attributes).hasSize(3);
        assertThat(attributes).extracting(AttributeType::name)
                .doesNotContain("email", "phone_number", "password");
        assertThat(capturedCreate(AdminCreateUserRequest::temporaryPassword)).isNull();
    }
    /**
     * Verifies a promotion removes the ordinary-user membership before adding the administrator one.
     *
     * <p>Assumptions: the ORDER is asserted and not merely the pair of calls, because the order is the whole
     * security content of the method. Adding before removing would leave the identity holding both groups for
     * the width of one provider call, and a token minted in that window would carry administrative authority
     * -- including during the demotion whose purpose is to withdraw it. An in-order verification is the only
     * assertion that fails when someone reorders the two statements.</p>
     */
    @Test
    @DisplayName("a promotion removes the user group before it adds the administrator group")
    void aPromotionRemovesTheUserGroupBeforeAddingTheAdministratorGroup() {
        AuthorityReassignment reassignment = this.service.reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_USER,
                CognitoUserProvisioningService.USER_TYPE_ADMIN);

        InOrder order = inOrder(this.provider);
        order.verify(this.provider).adminRemoveUserFromGroup(AdminRemoveUserFromGroupRequest.builder()
                .userPoolId(POOL_ID).username(USER_ID).groupName(USER_GROUP).build());
        order.verify(this.provider).adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(POOL_ID).username(USER_ID).groupName(ADMIN_GROUP).build());
        order.verifyNoMoreInteractions();

        assertThat(reassignment.userId()).isEqualTo(USER_ID);
        assertThat(reassignment.previousUserType())
                .isEqualTo(CognitoUserProvisioningService.USER_TYPE_USER);
        assertThat(reassignment.currentUserType())
                .isEqualTo(CognitoUserProvisioningService.USER_TYPE_ADMIN);
        assertThat(reassignment.providerMutated()).isTrue();
    }

    /**
     * Verifies a demotion removes the administrator membership before adding the ordinary-user one.
     *
     * <p>Assumptions: this direction is asserted separately rather than parameterised with the one above,
     * because the two groups are held in two different fields and a transposition would leave one direction
     * correct and the other silently reversed. A single parameterised case reading both group names from the
     * same accessor could not detect that.</p>
     */
    @Test
    @DisplayName("a demotion removes the administrator group before it adds the user group")
    void aDemotionRemovesTheAdministratorGroupBeforeAddingTheUserGroup() {
        this.service.reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_ADMIN,
                CognitoUserProvisioningService.USER_TYPE_USER);

        InOrder order = inOrder(this.provider);
        order.verify(this.provider).adminRemoveUserFromGroup(AdminRemoveUserFromGroupRequest.builder()
                .userPoolId(POOL_ID).username(USER_ID).groupName(ADMIN_GROUP).build());
        order.verify(this.provider).adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(POOL_ID).username(USER_ID).groupName(USER_GROUP).build());
        order.verifyNoMoreInteractions();
    }

    /**
     * Verifies a reassignment to the type already held is refused before any provider call.
     *
     * <p>Assumptions: refusal rather than a silent success is asserted because the two are
     * indistinguishable from a return value and completely different in meaning. A caller asking to move an
     * authority to where it already stands has confused a no-op with a move, and answering it with a
     * fabricated success would let that confusion reach the column assignment that follows.</p>
     */
    @Test
    @DisplayName("a reassignment whose two types are equal is refused and calls the provider not at all")
    void aReassignmentWhoseTypesAreEqualIsRefused() {
        assertThatThrownBy(() -> this.service.reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_ADMIN,
                CognitoUserProvisioningService.USER_TYPE_ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");

        verifyNoInteractions(this.provider);
    }

    /**
     * Verifies an out-of-domain type is refused before the removal that would otherwise strip a membership.
     *
     * <p>Assumptions: BOTH type arguments are validated before the first call, so a well-formed source and a
     * malformed target still touch nothing. Validating the target only when its turn came would leave an
     * identity groupless on every mistyped request, which is the one failure mode a refusal is supposed to
     * prevent.</p>
     */
    @Test
    @DisplayName("an out-of-domain target type strips no membership")
    void anOutOfDomainTargetTypeStripsNoMembership() {
        assertThatThrownBy(() -> this.service.reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_USER, "X"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(this.provider);
    }

    /**
     * Verifies a failed addition restores the membership that was removed, and reports the original failure.
     *
     * <p>Assumptions: the restored group is asserted to be the SOURCE group, which is what returns the
     * identity to the state it held before the call. Re-adding the target instead would complete the move the
     * provider had just refused, and asserting only that some add happened would not tell the two apart.</p>
     */
    @Test
    @DisplayName("a failed addition puts the removed membership back and reports the original failure")
    void aFailedAdditionPutsTheRemovedMembershipBack() {
        NotAuthorizedException refused =
                NotAuthorizedException.builder().message("provider refused").build();
        when(this.provider.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(POOL_ID).username(USER_ID).groupName(ADMIN_GROUP).build()))
                .thenThrow(refused);

        assertThatThrownBy(() -> this.service.reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_USER,
                CognitoUserProvisioningService.USER_TYPE_ADMIN))
                .isSameAs(refused);

        verify(this.provider).adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(POOL_ID).username(USER_ID).groupName(USER_GROUP).build());
        assertThat(refused.getSuppressed()).isEmpty();
    }

    /**
     * Verifies a compensation that itself fails is attached to the propagated failure rather than replacing
     * it.
     *
     * <p>Assumptions: the suppressed exception is the assertion, because it is the only channel through which
     * the second failure reaches a caller at all. A caller is told why the reassignment did not happen, which
     * is what it asked about; the fact that an identity is now groupless is carried alongside rather than
     * instead, so neither failure is lost and the common case stays distinguishable from the rare one.</p>
     */
    @Test
    @DisplayName("a failed compensation is suppressed onto the original failure, not substituted for it")
    void aFailedCompensationIsSuppressedOntoTheOriginalFailure() {
        NotAuthorizedException refused =
                NotAuthorizedException.builder().message("provider refused the promotion").build();
        UserNotFoundException gone =
                UserNotFoundException.builder().message("account vanished").build();
        when(this.provider.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(POOL_ID).username(USER_ID).groupName(ADMIN_GROUP).build()))
                .thenThrow(refused);
        when(this.provider.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(POOL_ID).username(USER_ID).groupName(USER_GROUP).build()))
                .thenThrow(gone);

        assertThatThrownBy(() -> this.service.reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_USER,
                CognitoUserProvisioningService.USER_TYPE_ADMIN))
                .isSameAs(refused);

        assertThat(refused.getSuppressed()).containsExactly(gone);
    }

    /**
     * Verifies a failure of the removal itself leaves nothing to compensate.
     *
     * <p>Assumptions: no add of any kind is expected, and that is asserted rather than assumed. A
     * compensation issued when the removal never succeeded would ADD a membership the identity may not have
     * held, which on the administrator group is a grant of authority in response to a failure.</p>
     */
    @Test
    @DisplayName("a failed removal adds no membership, because nothing was taken away")
    void aFailedRemovalAddsNoMembership() {
        NotAuthorizedException refused =
                NotAuthorizedException.builder().message("provider refused").build();
        when(this.provider.adminRemoveUserFromGroup(any(AdminRemoveUserFromGroupRequest.class)))
                .thenThrow(refused);

        assertThatThrownBy(() -> this.service.reassignGroup(USER_ID,
                CognitoUserProvisioningService.USER_TYPE_ADMIN,
                CognitoUserProvisioningService.USER_TYPE_USER))
                .isSameAs(refused);

        verify(this.provider, never()).adminAddUserToGroup(any(AdminAddUserToGroupRequest.class));
    }
}
