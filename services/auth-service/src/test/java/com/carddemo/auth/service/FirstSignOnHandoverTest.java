package com.carddemo.auth.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.auth.dto.SignOnChallenge;
import com.carddemo.auth.dto.SignOnChallengeRequest;
import com.carddemo.auth.dto.SignOnOutcome;
import com.carddemo.auth.dto.SignOnRequest;
import com.carddemo.auth.dto.SignOnResponse;
import com.carddemo.auth.repository.UserRepository;
import com.carddemo.common.error.ClientInputException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ChallengeNameType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RespondToAuthChallengeRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RespondToAuthChallengeResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


/**
 * Asserts that the credential a user creation hands back is the credential that account's first sign-on
 * accepts, all the way through to a token set.
 *
 * <h2>What this class exists to catch</h2>
 *
 * <p>Purpose: the property under test belongs to no single service, which is why it is asserted here and
 * not in either service's own class. Creation is carried by
 * {@link CognitoUserProvisioningService#provision(String, String, String, String)} and first sign-on by
 * {@link CognitoIdentityService#authenticate} and {@link CognitoIdentityService#answerChallenge}; the
 * value that has to survive the gap between them is the one-time credential the pool account is created
 * with. Each service can be entirely correct about its own half while the handover is broken, and that is
 * exactly the state this context was in.</p>
 *
 * <p>⚠️ Refactoring Rationale: the account used to be created with delivery suppressed, no supplied
 * temporary password and no contact attribute, and creation answered with the read projection. So the
 * provider minted a credential internally, sent it nowhere, and the operation returned nothing carrying
 * it -- a create that produced an account with no reachable way in. Nothing failed: creation answered
 * 201, the row was written and bound to a real subject, and the account was simply unusable. That is the
 * class of defect this journey is written to make loud, because every assertion that could have caught it
 * lived on one side of the gap or the other.</p>
 *
 * <p>Assumptions: the substituted pool ANSWERS CONDITIONALLY rather than unconditionally, and that is the
 * whole design of this class. The sign-on stub reads the credential out of the submitted authentication
 * parameters and raises the pool's own refusal unless it is the value the create call was made with. An
 * unconditional stub would answer a challenge for any credential at all -- including the empty string a
 * broken handover would leave a caller holding -- so it would pass in exactly the state the fix
 * corrects.</p>
 *
 * <p>Assumptions: both services are built over ONE substituted provider client, because two would let the
 * create call be made against one pool and the sign-on against another, and the linkage being asserted is
 * that they are the same account.</p>
 *
 * <p>Trade-offs: the provider is substituted, so nothing here proves a real user pool accepts the
 * credential this service generates, admits it under its configured password policy, or raises
 * {@code NEW_PASSWORD_REQUIRED} for an account created this way. Those are properties of the pool's
 * configuration, held by {@code infra/modules/cognito}, and no unit build can reach them. What this class
 * does prove is the part the reactor owns: one value is generated, supplied to the create call, returned
 * to the caller, and accepted by the exchange that turns it into tokens.</p>
 *
 * <p>Assumptions: there is no executable oracle for any of this. Sign-on and user administration are
 * online programs of the reference system, and {@code tests/README.md} lines 83 and 84 record that they
 * cannot run end to end without a CICS runtime the runner does not have -- and the credential handover has
 * no reference counterpart in any case, because the baseline stores an eight-character plaintext password
 * in {@code app/cpy/CSUSR01Y.cpy} line 21 and compares it directly at {@code app/cbl/COSGN00C.cbl} line
 * 223. The divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception clause.</p>
 */
class FirstSignOnHandoverTest {

    /** The pool the substituted provider stands for. */
    private static final String POOL_ID = "eu-west-1_TESTPOOL";

    /** The group an administrator row's account joins. */
    private static final String ADMIN_GROUP = "carddemo-admin";

    /** The group an ordinary row's account joins. */
    private static final String USER_GROUP = "carddemo-user";

    /** The identifier the journey creates and then signs on as, at the reference eight-character width. */
    private static final String USER_ID = "USER0042";

    /** The subject the substituted pool mints for the created account. */
    private static final UUID SUBJECT = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    /** The continuation value the substituted pool issues with its challenge. */
    private static final String SESSION = "session-token-value";

    /** The permanent credential the challenge is answered with. */
    private static final String REPLACEMENT_CREDENTIAL = "Perm4nent-Passw0rd!";

    /** The substituted provider both services are built over. */
    private CognitoIdentityProviderClient provider;

    /** The substituted local store the sign-on existence probe reads. */
    private UserRepository users;

    /** The creation half of the journey. */
    private CognitoUserProvisioningService provisioning;

    /** The sign-on half of the journey. */
    private CognitoIdentityService identity;

    /** Every event emitted anywhere during a case, so a credential leak into a log can be refused. */
    private ListAppender<ILoggingEvent> captured;

    /** The root logger's level before a case lowered it, restored afterwards. */
    private Level previousLevel;

    /** The root logger the appender is attached to. */
    private ch.qos.logback.classic.Logger rootLogger;

    /**
     * The prefix the derived managed-secret entry names are built beneath.
     *
     * <p>Assumptions: a fixed non-blank value, because the construction refuses a blank one and this
     * class asserts the derived name against the service's own derivation rather than against a
     * literal.</p>
     */
    private static final String SECRET_PREFIX = "carddemo/test/auth";

    /**
     * The customer-managed key the credential entry is encrypted with.
     *
     * <p>Assumptions: a well-formed ARN rather than a placeholder, since the construction validates the
     * shape and a substituted store never resolves it.</p>
     */
    private static final String SECRET_KMS_KEY_ARN =
            "arn:aws:kms:eu-west-1:000000000000:key/00000000-0000-0000-0000-000000000000";

    /**
     * The generated credential's length.
     *
     * <p>Assumptions: inside the admitted range, so construction succeeds, and asserted through
     * {@code hasSize} below so a change to this constant cannot silently weaken the case.</p>
     */
    private static final int PASSWORD_LENGTH = 20;

    /** The substituted managed-secret store the generated credential is published to. */
    private SecretsManagerClient secrets;

    /**
     * Builds both services over one substituted provider and captures every log event.
     *
     * <p>Assumptions: the appender is attached to the ROOT logger rather than to either service's own,
     * because the assertion it serves is that the credential appears in NO line from anywhere -- a leak
     * from a collaborator, a framework or an exception renderer counts exactly as much as one from the
     * service. Attaching per class would make the assertion true of the two loggers named and silent
     * about every other.</p>
     *
     * <p>Assumptions: the level is lowered to {@code TRACE}, which is deliberately lower than anything
     * this code emits. A leak assertion that ran at the default level would pass merely because the
     * offending line was filtered out, and would then start failing the day an operator raised
     * verbosity to diagnose something else.</p>
     */
    @BeforeEach
    void buildBothHalvesOverOnePool() {
        this.provider = mock(CognitoIdentityProviderClient.class);
        this.users = mock(UserRepository.class);
        // WHY : Assumptions: the secret store is a substitute because this case is about the SIGN-ON
        //       journey and not about the publication -- the credential is collected from the request
        //       the pool received, which is where a real operator's copy also originates. The
        //       publication itself is asserted by CognitoUserProvisioningServiceTest.
        this.secrets = mock(SecretsManagerClient.class);
        this.provisioning = new CognitoUserProvisioningService(this.provider, this.secrets, POOL_ID,
                ADMIN_GROUP, USER_GROUP, SECRET_PREFIX, SECRET_KMS_KEY_ARN, PASSWORD_LENGTH);
        // WHY : Assumptions: the client identifier and secret are constant non-empty values, because the
        //       confidential-client proof is computed over the secret and nothing here asserts the
        //       digest. A blank secret is refused at construction, so neither could be omitted.
        this.identity = new CognitoIdentityService(this.provider, this.users, "test-client-id",
                "test-client-secret");

        this.rootLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        this.previousLevel = this.rootLogger.getLevel();
        this.rootLogger.setLevel(Level.TRACE);
        this.captured = new ListAppender<>();
        this.captured.start();
        this.rootLogger.addAppender(this.captured);
    }

    /**
     * Detaches the appender and restores the root logger's level.
     *
     * <p>Assumptions: the level is restored rather than left at {@code TRACE}, because the root logger is
     * a process-wide singleton and a case that left it lowered would change what every later class in the
     * same fork emits -- and would leave this fork's output buried under framework tracing.</p>
     */
    @AfterEach
    void restoreRootLogger() {
        this.rootLogger.detachAppender(this.captured);
        this.captured.stop();
        this.rootLogger.setLevel(this.previousLevel);
    }

    /**
     * Stubs the create and membership calls, and stubs sign-on to accept only the credential created with.
     *
     * <p>Assumptions: the credential is read off the CAPTURED create request rather than off the value the
     * service returned, so the stub's acceptance condition is anchored to what the pool was actually told
     * rather than to what the caller was told. Anchoring it to the return value would make the two agree
     * by construction and assert nothing.</p>
     *
     * @return the credential the created account will accept
     */
    private String createAccountAndBindSignOnToItsCredential() {
        when(this.provider.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(AdminCreateUserResponse.builder()
                        .user(UserType.builder()
                                .username(USER_ID)
                                .attributes(AttributeType.builder()
                                        .name(CognitoUserProvisioningService.ATTRIBUTE_SUBJECT)
                                        .value(SUBJECT.toString())
                                        .build())
                                .build())
                        .build());
        when(this.provider.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenReturn(AdminAddUserToGroupResponse.builder().build());

        ProvisionedIdentity provisioned = this.provisioning.provision(USER_ID, "Ada", "Lovelace",
                CognitoUserProvisioningService.USER_TYPE_ADMIN);

        ArgumentCaptor<AdminCreateUserRequest> createdWith =
                ArgumentCaptor.forClass(AdminCreateUserRequest.class);
        verify(this.provider).adminCreateUser(createdWith.capture());
        String suppliedToPool = createdWith.getValue().temporaryPassword();

        assertThat(suppliedToPool)
                .as("the account must be created WITH a credential; a provider left to mint one"
                        + " internally sends it nowhere and the account is unreachable")
                .isNotBlank()
                .hasSize(PASSWORD_LENGTH);
        assertThat(provisioned.credentialSecretName())
                .as("the caller must be told WHERE the credential was published, since the value itself"
                        + " never travels in a response; without a locator the account is unreachable")
                .isEqualTo(this.provisioning.credentialSecretName(USER_ID));
        assertThat(provisioned.toString())
                .as("the credential must not be reachable through the returned pair at all")
                .doesNotContain(suppliedToPool);

        when(this.users.existsById(USER_ID)).thenReturn(true);
        when(this.provider.initiateAuth(any(InitiateAuthRequest.class)))
                .thenAnswer(submitted -> {
                    InitiateAuthRequest request = submitted.getArgument(0);
                    String presented = request.authParameters().get("PASSWORD");
                    if (!suppliedToPool.equals(presented)) {
                        throw NotAuthorizedException.builder()
                                .message("Incorrect username or password.")
                                .build();
                    }
                    return InitiateAuthResponse.builder()
                            .challengeName(ChallengeNameType.NEW_PASSWORD_REQUIRED)
                            .session(SESSION)
                            .build();
                });

        // WHY : Refactoring Rationale: the credential is read from the request the pool RECEIVED rather
        //       than from the returned pair, because the pair carries the managed entry's name and not
        //       the value. That is the point of the published surface: what an operator collects comes
        //       from the secret store, so the test collects it from the same place the store did.
        return suppliedToPool;
    }

    /**
     * Asserts the created account's credential carries a first sign-on through to a token set.
     *
     * <p>Assumptions: all three steps are asserted in ONE case rather than three, because the property is
     * the chain and not the links. Split into three, each would need the previous step's output supplied
     * by hand, which is precisely the substitution that let the broken handover pass.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the credential a creation returns carries that account's first sign-on to a token set")
    void theReturnedCredentialCompletesFirstSignOn() {
        String credential = createAccountAndBindSignOnToItsCredential();

        SignOnOutcome first = this.identity.authenticate(new SignOnRequest(USER_ID, credential));

        // Assumptions: the challenge shape is asserted rather than a token set, because a pool account
        //   created with a temporary password is in the forced-change state and CANNOT answer with tokens
        //   on the first exchange. A case expecting tokens here would be asserting a pool configuration
        //   this deployment does not have.
        assertThat(first)
                .as("a first sign-on on a temporary credential is answered with the change challenge")
                .isInstanceOf(SignOnChallenge.class);
        SignOnChallenge challenge = (SignOnChallenge) first;
        assertThat(challenge.challengeName())
                .isEqualTo(SignOnChallenge.CHALLENGE_NEW_PASSWORD_REQUIRED);
        assertThat(challenge.userId()).isEqualTo(USER_ID);
        assertThat(challenge.session()).isEqualTo(SESSION);

        when(this.provider.respondToAuthChallenge(any(RespondToAuthChallengeRequest.class)))
                .thenReturn(RespondToAuthChallengeResponse.builder()
                        .authenticationResult(AuthenticationResultType.builder()
                                .accessToken("access-token")
                                .idToken("id-token")
                                .refreshToken("refresh-token")
                                .tokenType("Bearer")
                                .expiresIn(3600)
                                .build())
                        .build());

        SignOnResponse tokens = this.identity.answerChallenge(new SignOnChallengeRequest(
                USER_ID, challenge.session(), REPLACEMENT_CREDENTIAL));

        assertThat(tokens.outcome()).isEqualTo(SignOnResponse.OUTCOME_AUTHENTICATED);
        assertThat(tokens.userId()).isEqualTo(USER_ID);
        assertThat(tokens.accessToken()).isEqualTo("access-token");
    }

    /**
     * Asserts a credential other than the created account's does not reach a challenge.
     *
     * <p>Assumptions: this case is what gives the case above its force. Without it, the journey would pass
     * against a stub that answered a challenge for any input at all, including the blank value a caller
     * holds when the handover is broken -- so the evidence would be that the exchange runs rather than
     * that the credential is the right one.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a credential the account was not created with is refused rather than challenged")
    void aDifferentCredentialIsRefused() {
        createAccountAndBindSignOnToItsCredential();

        assertThatThrownBy(() -> this.identity.authenticate(
                new SignOnRequest(USER_ID, "Some0ther-Credential!")))
                .as("the acceptance condition is the created credential, not merely a non-empty one")
                .isInstanceOf(BadCredentialsException.class);
    }

    /**
     * Asserts an empty credential -- what a broken handover leaves a caller holding -- reaches no pool.
     *
     * <p>Assumptions: the refusal happens before any provider call, at the submitted-field check, which is
     * why this asserts the exception type rather than the pool's answer. The value of the case is that it
     * names the state the fix removes: if creation stopped returning the credential, a caller reading the
     * absent property off the body would submit exactly this.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the blank credential a broken handover would leave a caller holding reaches no pool")
    void aBlankCredentialIsRefusedBeforeThePool() {
        createAccountAndBindSignOnToItsCredential();
        clearInvocations(this.provider);

        assertThatThrownBy(() -> this.identity.authenticate(new SignOnRequest(USER_ID, "   ")))
                .isInstanceOf(ClientInputException.class);

        verify(this.provider, never()).initiateAuth(any(InitiateAuthRequest.class));
    }

    /**
     * Asserts no log line emitted anywhere during the journey carries the credential.
     *
     * <p>Assumptions: the whole journey is run first and the log is examined afterwards, rather than
     * asserting on one service's lines, because the credential passes through creation, a returned record,
     * a request record and an exchange, and any of those could render it. The search is over the FORMATTED
     * message and over every argument, so a line that logged the record rather than the string is caught
     * too -- that being the likeliest accident, and the reason both {@code ProvisionedIdentity} and
     * {@code CreatedUserResponse} override their generated rendering.</p>
     *
     * <p>Trade-offs: this proves the credential is absent from the lines these two services emit on the
     * paths driven here, not that it is absent from every line the deployment can produce. A leak from a
     * path no case drives would not be caught. The alternative -- forbidding the value from ever reaching
     * a string -- is not expressible, since the response body is a string; so the assertion is placed
     * where the value actually travels and its limit is stated rather than implied.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("no log line from anywhere in the journey carries the credential")
    void theCredentialReachesNoLogLine() {
        String credential = createAccountAndBindSignOnToItsCredential();

        SignOnOutcome first = this.identity.authenticate(new SignOnRequest(USER_ID, credential));
        when(this.provider.respondToAuthChallenge(any(RespondToAuthChallengeRequest.class)))
                .thenReturn(RespondToAuthChallengeResponse.builder()
                        .authenticationResult(AuthenticationResultType.builder()
                                .accessToken("access-token")
                                .idToken("id-token")
                                .refreshToken("refresh-token")
                                .tokenType("Bearer")
                                .expiresIn(3600)
                                .build())
                        .build());
        this.identity.answerChallenge(new SignOnChallengeRequest(
                USER_ID, ((SignOnChallenge) first).session(), REPLACEMENT_CREDENTIAL));

        assertThat(this.captured.list)
                .as("the journey must emit at least one line, or this case would pass vacuously")
                .isNotEmpty();

        for (ILoggingEvent event : this.captured.list) {
            assertThat(event.getFormattedMessage())
                    .as("a rendered line carrying the one-time credential would outlive the single"
                            + " handover for the whole log retention period")
                    .doesNotContain(credential)
                    .doesNotContain(REPLACEMENT_CREDENTIAL);
            if (event.getArgumentArray() != null) {
                for (Object argument : event.getArgumentArray()) {
                    assertThat(String.valueOf(argument))
                            .as("an argument carrying the credential is a leak even when the pattern"
                                    + " that consumed it happens to drop it")
                            .doesNotContain(credential)
                            .doesNotContain(REPLACEMENT_CREDENTIAL);
                }
            }
        }
    }
}
