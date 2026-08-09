package com.carddemo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.auth.domain.User;
import com.carddemo.auth.dto.SignOnChallenge;
import com.carddemo.auth.dto.SignOnChallengeRequest;
import com.carddemo.auth.dto.SignOnOutcome;
import com.carddemo.auth.dto.SignOnRequest;
import com.carddemo.auth.dto.SignOnResponse;
import com.carddemo.auth.dto.TokenRefreshRequest;
import com.carddemo.auth.repository.UserRepository;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.net.ConnectException;
import java.time.Duration;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.authentication.BadCredentialsException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ChallengeNameType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InternalErrorException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RespondToAuthChallengeRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RespondToAuthChallengeResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

/**
 * Asserts the three exchanges this service publishes and the four provider outcomes they classify.
 *
 * <h2>What this class exists to catch</h2>
 *
 * <p>Purpose: this class pins every branch the sign-on exchange owns against the reference paragraph
 * it succeeds, {@code READ-USER-SEC-FILE} at {@code app/cbl/COSGN00C.cbl} line 209. It covers the
 * ordered presence chain and the one sentence it emits, the local existence probe, the credential
 * exchange, the classification of the pool's answer into a token set, a challenge, a refusal or an
 * unevaluable credential, and the disclosure boundary each of those answers has to respect. None of
 * those properties is visible to a compiler, and several of them -- a pool answering
 * {@code NEW_PASSWORD_REQUIRED}, a store failing on the probe, a pool reporting an incomplete token
 * set, two refusal paths taking measurably different time -- are reachable only through a stood-in
 * collaborator.</p>
 *
 * <p>Assumptions: the reference sign-on has no executable oracle here. All five programs of this
 * bounded context are online CICS programs, and {@code tests/README.md} lines 83 and 84 record that
 * they cannot run end to end without a CICS runtime, which the runner does not have. What remains
 * directly verifiable is the message text, so every sentence below is compared character for
 * character; that comparison is the whole of the parity evidence available to this class.</p>
 *
 * <p>Assumptions: the provider is substituted, so nothing here proves a real user pool accepts these
 * payloads. What it proves is every branch this service owns -- the outcome classification, the
 * completeness checks, the failure taxonomy, the flow selectors and the timing floor -- none of which a
 * live pool would exercise more convincingly, and several of which a live pool could not be made to
 * produce on demand at all.</p>
 *
 * <p>Assumptions: the sentences asserted are compared against the reference literals as literals rather
 * than against the service's own constants, wherever a reference line supplies one. Comparing against
 * the constant would pass if both moved together, which is exactly the change transformation rule T8
 * forbids.</p>
 *
 * <p>Alternatives Considered: asserting a stable refusal code and leaving the sentence unpinned, which
 * would let the wording be reworded without any case failing. Rejected because on this exchange the
 * string IS the external interface: transformation rule T8 carries every user-visible string across
 * character for character, and the code is an addition the reference has no counterpart for. A code-only
 * assertion would let the text drift undetected, which is the one regression this class exists to
 * prevent.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception clause.</p>
 */
class CognitoIdentityServiceTest {

    /** The identifier every case authenticates, folded as the service folds it. */
    private static final String USER_ID = "ADMIN001";

    /** The reference sentence a refused credential carries, from {@code COSGN00C.cbl} lines 242 to 243. */
    private static final String CREDENTIAL_REFUSED = "Wrong Password. Try again ...";

    /** The reference sentence an unevaluable credential carries, from {@code COSGN00C.cbl} line 254. */
    private static final String UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /** The authored sentence a refused session or refresh token carries, which has no reference line. */
    private static final String SESSION_REFUSED = "Please sign on again ...";

    /** The session value the pool issues with a challenge in these cases. */
    private static final String SESSION = "AYABeExampleSessionValue";

    /** The reference sentence an absent identifier carries, from {@code COSGN00C.cbl} line 120. */
    private static final String USER_ID_REQUIRED = "Please enter User ID ...";

    /** The reference sentence an absent credential carries, from {@code COSGN00C.cbl} line 125. */
    private static final String PASSWORD_REQUIRED = "Please enter Password ...";

    // WHY : Assumptions: the two keys below are not chosen here and are not read from a styling
    //       copybook either. Each failing reference arm homes the cursor to the field it blames, and
    //       those cursor targets are the whole of the key set the reference can attribute a failure to
    //       -- USERIDL at app/cbl/COSGN00C.cbl lines 121, 250 and 255, and PASSWDL at lines 126 and
    //       244. They are spelled as the two record component names of com.carddemo.auth.dto
    //       .SignOnRequest, which are also the property names the served contract publishes.
    /** The key the served refusal carries when the identifier is the field at fault. */
    private static final String FIELD_USER_ID = "userId";

    /** The key the served refusal carries when the credential is the field at fault. */
    private static final String FIELD_PASSWORD = "password";

    /** A submitted credential the pool accepts in the cases that reach it. */
    private static final String ACCEPTED_CREDENTIAL = "Passw0rd!";

    // WHY : Assumptions: the pair below exists to separate the two halves of the reference fold at
    //       app/cbl/COSGN00C.cbl lines 132 to 136, which folds BOTH values -- the identifier into
    //       WS-USER-ID on line 133 and CDEMO-USER-ID on line 134, and the credential into WS-USER-PWD
    //       on line 136. The target folds the identifier only, so a vector that differed in case on
    //       one value alone could not tell a preserved fold from a dropped one. Both differ in case
    //       here, and the case below asserts one is folded and the other is not.
    /** A lower-case submission of the same identifier the stored row is keyed by. */
    private static final String SUBMITTED_LOWER_CASE_USER_ID = "admin001";

    /** A mixed-case credential submitted alongside it, which must reach the pool unaltered. */
    private static final String MIXED_CASE_CREDENTIAL = "pAsSw0rD!";

    /** A submission that is blank rather than absent, matching the reference test for spaces. */
    private static final String BLANK = "   ";

    private CognitoIdentityProviderClient provider;

    private UserRepository users;

    private CognitoIdentityService service;

    /**
     * Builds the service over substituted collaborators with a known client identifier and secret.
     *
     * <p>Alternatives Considered: loading a Spring context, or standing a database behind the probe with
     * Testcontainers, so that the collaborators were real. Rejected because the class under test owns no
     * persistence surface of its own and no web surface at all -- it calls one derived existence method
     * and one provider operation -- so a context-loading case would pay container and context startup on
     * every run without reaching one branch these substitutions cannot reach. The reverse also holds:
     * four of the branches asserted below, a pool answering a challenge, a pool answering an incomplete
     * token set, a store failing mid-probe and two refusals differing in duration, cannot be produced on
     * demand from a real pool or a real store at all. The repository is exercised for real against a
     * container in {@code com.carddemo.auth.repository}, where the queries rather than this
     * classification live.</p>
     *
     * <p>This method takes no parameter and yields no value.</p>
     */
    @BeforeEach
    void buildService() {
        provider = mock(CognitoIdentityProviderClient.class);
        users = mock(UserRepository.class);
        // WHY : Assumptions: the client secret is a constant non-empty value rather than a realistic
        //       one, because the confidential-client proof is computed over it and every assertion below
        //       is about the request SHAPE rather than about the digest's value. A blank secret is
        //       refused by the constructor, so it could not reach the key-initialisation path at all.
        service = new CognitoIdentityService(provider, users, "test-client-id", "test-client-secret");
    }

    /**
     * Asserts a new-password challenge is returned as the challenge shape rather than reported a fault.
     *
     * <p>Assumptions: every account the infrastructure provisions is created with a temporary password,
     * so this pool answer is what the FIRST sign-on of every user produces. Classifying it as an
     * unevaluable credential would answer 500 and leave no provisioned user able to obtain a token at
     * all, which is why this case pins the challenge shape rather than a status.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a new-password challenge is returned as the challenge outcome, not as a fault")
    void aNewPasswordChallengeIsReturnedAsTheChallengeOutcome() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.initiateAuth(any(InitiateAuthRequest.class))).thenReturn(
                InitiateAuthResponse.builder()
                        .challengeName(ChallengeNameType.NEW_PASSWORD_REQUIRED)
                        .session(SESSION)
                        .build());

        SignOnOutcome outcome = service.authenticate(new SignOnRequest(USER_ID, "TempPassw0rd!"));

        assertThat(outcome).isInstanceOf(SignOnChallenge.class);
        SignOnChallenge challenge = (SignOnChallenge) outcome;
        assertThat(challenge.outcome()).isEqualTo("CHALLENGE");
        assertThat(challenge.challengeName()).isEqualTo("NEW_PASSWORD_REQUIRED");
        assertThat(challenge.session()).isEqualTo(SESSION);
        assertThat(challenge.userId()).isEqualTo(USER_ID);
    }

    /**
     * Asserts the challenge body never renders its session, which is half of a credential.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the challenge body withholds its session from its own rendering")
    void theChallengeBodyWithholdsItsSession() {
        SignOnChallenge challenge = SignOnChallenge.newPasswordRequired(SESSION, USER_ID);

        assertThat(challenge.toString())
                .doesNotContain(SESSION)
                .contains(USER_ID)
                .contains("<withheld>");
    }

    /**
     * Asserts a challenge this contract publishes no answer path for stays an unevaluable credential.
     *
     * <p>Assumptions: a second-factor challenge is used because the pool as provisioned raises none, so
     * the branch is reachable only through a configuration change -- which is the moment a contract
     * revision belongs rather than a body naming a challenge no client can answer.</p>
     *
     * <p>The refusal expected is the bare {@link IllegalStateException}, carrying the reference sentence
     * for an unevaluable credential from {@code app/cbl/COSGN00C.cbl} line 254.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an unpublished challenge is an unevaluable credential, not a challenge body")
    void anUnpublishedChallengeIsUnevaluable() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.initiateAuth(any(InitiateAuthRequest.class))).thenReturn(
                InitiateAuthResponse.builder()
                        .challengeName(ChallengeNameType.SOFTWARE_TOKEN_MFA)
                        .session(SESSION)
                        .build());

        assertThatThrownBy(() -> service.authenticate(new SignOnRequest(USER_ID, "Passw0rd!")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(UNABLE_TO_VERIFY);
    }

    /**
     * Asserts a published challenge arriving without a session is unevaluable rather than returned.
     *
     * <p>Assumptions: the session is the whole means of answering the challenge, so a body carrying the
     * name and no session would tell a caller to do something it has been given no way to do.</p>
     *
     * <p>The refusal expected is the bare {@link IllegalStateException}, carrying the reference sentence
     * for an unevaluable credential from {@code app/cbl/COSGN00C.cbl} line 254.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a challenge with no session is unevaluable rather than unanswerable")
    void aChallengeWithNoSessionIsUnevaluable() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.initiateAuth(any(InitiateAuthRequest.class))).thenReturn(
                InitiateAuthResponse.builder()
                        .challengeName(ChallengeNameType.NEW_PASSWORD_REQUIRED)
                        .build());

        assertThatThrownBy(() -> service.authenticate(new SignOnRequest(USER_ID, "Passw0rd!")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(UNABLE_TO_VERIFY);
    }

    /**
     * Asserts a complete token set is returned with the authenticated discriminator.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a complete token set is returned with the authenticated outcome")
    void aCompleteTokenSetIsReturned() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.initiateAuth(any(InitiateAuthRequest.class)))
                .thenReturn(InitiateAuthResponse.builder()
                        .authenticationResult(completeResult().build())
                        .build());

        SignOnOutcome outcome = service.authenticate(new SignOnRequest(USER_ID, "Passw0rd!"));

        assertThat(outcome).isInstanceOf(SignOnResponse.class);
        SignOnResponse tokens = (SignOnResponse) outcome;
        assertThat(tokens.outcome()).isEqualTo("AUTHENTICATED");
        assertThat(tokens.userId()).isEqualTo(USER_ID);
        assertThat(tokens.accessToken()).isEqualTo("access-token");
        assertThat(tokens.expiresIn()).isEqualTo(3600);
    }

    /**
     * Asserts a token set the pool reported incompletely is refused rather than relayed as a success.
     *
     * <p>Assumptions: completeness is asserted member by member because checking the lifetime alone
     * leaves the other members unguarded. A result whose access token is absent or blank, or whose token
     * type is absent, would then be relayed as HTTP 200 -- and the caller would store it, send an empty
     * bearer credential on its next request, be refused with a 401 naming no cause, and be returned to a
     * sign-on screen that had just reported success. Each member therefore gets its own vector.</p>
     *
     * <p>The refusal expected on every vector is the bare {@link IllegalStateException}, carrying the
     * reference sentence for an unevaluable credential from {@code app/cbl/COSGN00C.cbl} line 254.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an incomplete token set is unevaluable rather than a 200")
    void anIncompleteTokenSetIsUnevaluable() {
        when(users.existsById(USER_ID)).thenReturn(true);

        AuthenticationResultType[] incomplete = {
            completeResult().accessToken(null).build(),
            completeResult().accessToken("   ").build(),
            completeResult().idToken(null).build(),
            completeResult().tokenType("").build(),
            completeResult().expiresIn(null).build(),
            completeResult().expiresIn(0).build(),
            completeResult().expiresIn(-1).build(),
        };

        for (AuthenticationResultType result : incomplete) {
            when(provider.initiateAuth(any(InitiateAuthRequest.class)))
                    .thenReturn(InitiateAuthResponse.builder()
                            .authenticationResult(result)
                            .build());

            assertThatThrownBy(() -> service.authenticate(new SignOnRequest(USER_ID, "Passw0rd!")))
                    .as("a reported token set missing a required member cannot be relayed as a success")
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage(UNABLE_TO_VERIFY);
        }
    }

    /**
     * Asserts the nullable renewal token is passed through as the pool supplied it, including absent.
     *
     * <p>Assumptions: this is asserted alongside the completeness checks above precisely because it is
     * the ONE required-looking member that must not be checked: the pool never reissues a renewal token
     * on the renewal flow, so refusing an absent one would refuse every renewal.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an absent renewal token is passed through rather than refused")
    void anAbsentRenewalTokenIsPassedThrough() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.initiateAuth(any(InitiateAuthRequest.class)))
                .thenReturn(InitiateAuthResponse.builder()
                        .authenticationResult(completeResult().refreshToken(null).build())
                        .build());

        SignOnResponse renewed = service.refresh(new TokenRefreshRequest(USER_ID, "refresh-token"));

        assertThat(renewed.refreshToken()).isNull();
        assertThat(renewed.accessToken()).isEqualTo("access-token");
    }

    /**
     * Asserts a data-access failure on the local probe reports the reference unevaluable sentence.
     *
     * <p>Assumptions: the translation is asserted rather than assumed because an unwrapped probe would
     * let a data-access exception escape this class, and the shared advice would then match it with its
     * unanticipated-failure handler -- which answers 500 with a GENERIC sentence, because it carries a
     * service's own sentence only for the bare illegal-state type. The one condition the reference's
     * catch-all arm exists for would then be the one condition unable to produce the reference's
     * sentence, so the exact type as well as the exact sentence is pinned here.</p>
     *
     * <p>The refusal expected is the bare {@link IllegalStateException}, and the bareness is the point:
     * the shared advice carries a service's own sentence only for that exact type, so a subtype would be
     * rendered with a generic sentence rather than the reference one from
     * {@code app/cbl/COSGN00C.cbl} line 254.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a store failure on the local probe carries the reference unevaluable sentence")
    void aStoreFailureOnTheProbeIsUnevaluable() {
        when(users.existsById(USER_ID)).thenThrow(new QueryTimeoutException("statement timed out"));

        assertThatThrownBy(() -> service.authenticate(new SignOnRequest(USER_ID, "Passw0rd!")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(UNABLE_TO_VERIFY);

        verifyNoInteractions(provider);
    }

    /**
     * Asserts an unknown local identifier and a pool-refused credential answer the same sentence.
     *
     * <p>The refusal expected on both legs is {@link BadCredentialsException}, carrying the reference
     * sentence from {@code app/cbl/COSGN00C.cbl} lines 242 and 243. The sentence the reference reserves
     * for an unknown identifier, on line 249, is deliberately not emitted; the divergence is documented
     * in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an unknown identifier and a wrong credential answer one sentence")
    void bothRefusalsAnswerOneSentence() {
        when(users.existsById(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.authenticate(new SignOnRequest(USER_ID, "Passw0rd!")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage(CREDENTIAL_REFUSED);

        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.initiateAuth(any(InitiateAuthRequest.class)))
                .thenThrow(NotAuthorizedException.builder().message("Incorrect username or password")
                        .build());

        assertThatThrownBy(() -> service.authenticate(new SignOnRequest(USER_ID, "Passw0rd!")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage(CREDENTIAL_REFUSED);
    }

    /**
     * Asserts both refusal paths take at least the floor, so neither can be told from the other by time.
     *
     * <p>Assumptions: the merged refusal sentence closes the WORDING channel only. The locally-refused
     * path performs one indexed probe while the pool-refused path performs a network round trip, so
     * without the floor the two remain distinguishable by duration and an unauthenticated caller can
     * still enumerate identifiers by timing them. The floor is what closes that second channel, and it
     * is behaviour no compiler can observe, so it is asserted here.</p>
     *
     * <p>Assumptions: the assertion is a FLOOR and not an equality, because a floor is the property that
     * closes the channel: what must hold is that neither path can complete faster than the other's
     * minimum. Asserting an upper bound as well would make the case fail on a loaded runner for a reason
     * that has nothing to do with the behaviour under test.</p>
     *
     * <p>The refusal each timed leg expects is {@link BadCredentialsException}, which the timing helper
     * catches and whose absence it reports as an assertion failure.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("both refusal paths take at least the padding floor")
    void bothRefusalPathsTakeAtLeastTheFloor() {
        when(users.existsById(USER_ID)).thenReturn(false);

        long localElapsed = elapsedNanosOfRefusal();

        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.initiateAuth(any(InitiateAuthRequest.class)))
                .thenThrow(NotAuthorizedException.builder().message("refused").build());

        long providerElapsed = elapsedNanosOfRefusal();

        // WHY : Assumptions: the floor asserted is a fraction of the configured one rather than the whole
        //       of it, because a coarse system timer can report a sleep as marginally shorter than it was
        //       asked for and this case is about the channel being closed rather than about the sleep's
        //       precision. Two thirds is far above the unpadded local path, which completes in
        //       microseconds, so the assertion still fails if the padding is removed.
        long asserted = Duration.ofMillis(500).toNanos();
        assertThat(localElapsed)
                .as("a locally-refused sign-on must not complete faster than the floor")
                .isGreaterThan(asserted);
        assertThat(providerElapsed)
                .as("a pool-refused sign-on must not complete faster than the floor")
                .isGreaterThan(asserted);
    }

    /**
     * Asserts a successful sign-on is NOT padded, so the floor costs no healthy request any latency.
     *
     * <p>Assumptions: the first exchange of the run is discarded and only the second is timed, because
     * the quantity this case is about is the padding and the first exchange also pays one-off costs that
     * have nothing to do with it. Resolving the digest algorithm through the security provider,
     * initialising the request builders and inflating the stubbing machinery all happen once, and
     * together they were measured at over half a second on this runner -- enough to exceed the budget
     * below on their own, and enough to make the case pass or fail on where the method happened to fall
     * in the execution order rather than on whether a success is padded. Discarding one exchange removes
     * that term from the measurement entirely.</p>
     *
     * <p>Trade-offs: the budget asserted is roughly half the configured floor rather than a tight bound
     * on a warmed call, which a warmed call clears by three orders of magnitude. A tight bound would
     * fail on a loaded runner for a reason unrelated to the behaviour, while a budget at half the floor
     * still fails immediately if the padding is ever extended to the success path -- which is the only
     * regression this case exists to catch.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a successful sign-on is not padded")
    void aSuccessfulSignOnIsNotPadded() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.initiateAuth(any(InitiateAuthRequest.class)))
                .thenReturn(InitiateAuthResponse.builder()
                        .authenticationResult(completeResult().build())
                        .build());

        service.authenticate(new SignOnRequest(USER_ID, ACCEPTED_CREDENTIAL));

        long startedAt = System.nanoTime();
        service.authenticate(new SignOnRequest(USER_ID, ACCEPTED_CREDENTIAL));
        long elapsed = System.nanoTime() - startedAt;

        assertThat(elapsed)
                .as("padding a success would slow every healthy sign-on for no disclosure it prevents")
                .isLessThan(Duration.ofMillis(400).toNanos());
    }

    /**
     * Asserts the challenge answer sets the password and returns the token set the pool then issues.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("answering the challenge returns the token set the sign-on could not")
    void answeringTheChallengeReturnsTheTokenSet() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.respondToAuthChallenge(any(RespondToAuthChallengeRequest.class)))
                .thenReturn(RespondToAuthChallengeResponse.builder()
                        .authenticationResult(completeResult().build())
                        .build());

        SignOnResponse tokens = service.answerChallenge(
                new SignOnChallengeRequest(USER_ID, SESSION, "Perm4nentPassw0rd!"));

        assertThat(tokens.outcome()).isEqualTo("AUTHENTICATED");
        assertThat(tokens.userId()).isEqualTo(USER_ID);

        ArgumentCaptor<RespondToAuthChallengeRequest> sent =
                ArgumentCaptor.forClass(RespondToAuthChallengeRequest.class);
        verify(provider).respondToAuthChallenge(sent.capture());
        assertThat(sent.getValue().challengeName())
                .isEqualTo(ChallengeNameType.NEW_PASSWORD_REQUIRED);
        assertThat(sent.getValue().session()).isEqualTo(SESSION);
        assertThat(sent.getValue().challengeResponses())
                .containsEntry("USERNAME", USER_ID)
                .containsEntry("NEW_PASSWORD", "Perm4nentPassw0rd!")
                .containsKey("SECRET_HASH");
    }

    /**
     * Asserts a password the pool's policy refuses is reported as caller input with the pool's reason.
     *
     * <p>Assumptions: the pool's own sentence is carried because the policy is configured in the pool and
     * this contract deliberately holds no second copy of it. A sentence authored here would either
     * restate a policy that can change beneath it or leave the caller guessing at a rule it cannot
     * read.</p>
     *
     * <p>The refusal expected is {@link ClientInputException}, the type the shared advice renders as a
     * per-field entry, rather than a credential refusal or an unevaluable credential.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a policy-refused password is a caller-input failure carrying the pool's reason")
    void aPolicyRefusedPasswordIsCallerInput() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.respondToAuthChallenge(any(RespondToAuthChallengeRequest.class)))
                .thenThrow(InvalidPasswordException.builder()
                        .message("Password did not conform with policy: Password not long enough")
                        .build());

        assertThatThrownBy(() -> service.answerChallenge(
                        new SignOnChallengeRequest(USER_ID, SESSION, "short")))
                .isInstanceOf(ClientInputException.class)
                .hasMessageContaining("Password not long enough")
                // WHY : Assumptions: the SHAPE is asserted as well as the content, because carrying the
                //       content is not sufficient for it to reach a caller. The shared advice replaces a
                //       sentence that does not end in an ellipsis, exceeds the declared message width or
                //       is not printable, so a relayed reason failing any of those would be composed here
                //       and silently discarded two layers away -- which was the defect the earlier form
                //       had, and which asserting only the content would not have caught.
                .extracting(failure -> failure.getMessage(),
                        org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .endsWith(" ...")
                .matches("[\\x20-\\x7e]+")
                .hasSizeLessThanOrEqualTo(75);
    }

    /**
     * Asserts a policy reason that cannot be relayed safely falls back to the authored sentence.
     *
     * <p>Assumptions: a long run of digits is the shape of a primary account number, so a provider
     * sentence quoting a submitted value could carry one and shortening it would not make it safe. The
     * whole reason is therefore discarded rather than trimmed, which is the same judgement the shared
     * advice's own gate makes.</p>
     *
     * <p>The refusal expected is {@link ClientInputException}, carrying the authored fallback sentence
     * rather than the pool's reason.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a policy reason carrying a long digit run is discarded rather than relayed")
    void anUnsafePolicyReasonIsDiscarded() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.respondToAuthChallenge(any(RespondToAuthChallengeRequest.class)))
                .thenThrow(InvalidPasswordException.builder()
                        .message("Password must not contain 4111111111111111")
                        .build());

        assertThatThrownBy(() -> service.answerChallenge(
                        new SignOnChallengeRequest(USER_ID, SESSION, "4111111111111111")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage("Please enter Password ...");
    }

    /**
     * Asserts a policy reason too long to carry whole is truncated to fit rather than discarded.
     *
     * <p>Assumptions: the leading words of a policy refusal are the part naming the rule, so a truncated
     * sentence still tells the caller which requirement it missed while a discarded one tells it
     * nothing.</p>
     *
     * <p>The refusal expected is {@link ClientInputException}, carrying a shortened form of the pool's
     * reason rather than the authored fallback.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an over-long policy reason is truncated to the width the advice will carry")
    void anOverLongPolicyReasonIsTruncated() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.respondToAuthChallenge(any(RespondToAuthChallengeRequest.class)))
                .thenThrow(InvalidPasswordException.builder()
                        .message("Password did not conform with policy: Password must have lowercase,"
                                + " uppercase, numeric and symbol characters present")
                        .build());

        assertThatThrownBy(() -> service.answerChallenge(
                        new SignOnChallengeRequest(USER_ID, SESSION, "aaaaaaaaaaaaaa")))
                .isInstanceOf(ClientInputException.class)
                .extracting(failure -> failure.getMessage(),
                        org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith("Password did not conform with policy")
                .endsWith(" ...")
                .hasSizeLessThanOrEqualTo(75);
    }

    /**
     * Asserts a refused session reports the authored sentence and not the reference credential sentence.
     *
     * <p>Assumptions: the reference sentence would be actively misleading here -- on this exchange the
     * credential was accepted, the challenge being what proves it -- which is why a distinct refusal type
     * carrying a distinct sentence exists.</p>
     *
     * <p>The refusal expected is {@link CognitoIdentityService.SessionRefusedException}, the subtype of
     * {@link BadCredentialsException} the service declares for exactly this condition.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a refused session reports the sign-on-again sentence, not the credential sentence")
    void aRefusedSessionReportsItsOwnSentence() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.respondToAuthChallenge(any(RespondToAuthChallengeRequest.class)))
                .thenThrow(NotAuthorizedException.builder().message("Invalid session").build());

        assertThatThrownBy(() -> service.answerChallenge(
                        new SignOnChallengeRequest(USER_ID, SESSION, "Perm4nentPassw0rd!")))
                .isInstanceOf(CognitoIdentityService.SessionRefusedException.class)
                .hasMessage(SESSION_REFUSED);
    }

    /**
     * Asserts a further challenge on the answer exchange is unevaluable so the flow cannot loop.
     *
     * <p>The refusal expected is the bare {@link IllegalStateException}, carrying the reference sentence
     * for an unevaluable credential from {@code app/cbl/COSGN00C.cbl} line 254.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a further challenge on the answer exchange is unevaluable rather than returned")
    void aFurtherChallengeOnTheAnswerIsUnevaluable() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.respondToAuthChallenge(any(RespondToAuthChallengeRequest.class)))
                .thenReturn(RespondToAuthChallengeResponse.builder()
                        .challengeName(ChallengeNameType.SOFTWARE_TOKEN_MFA)
                        .session(SESSION)
                        .build());

        assertThatThrownBy(() -> service.answerChallenge(
                        new SignOnChallengeRequest(USER_ID, SESSION, "Perm4nentPassw0rd!")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(UNABLE_TO_VERIFY);
    }

    /**
     * Asserts the renewal selects the refresh flow and carries the token and the client proof only.
     *
     * <p>Assumptions: the absence of a user-name parameter is asserted as well as the presence of the
     * token, because the renewal flow does not accept one -- the token identifies its own subject -- while
     * the confidential-client proof still has to be computed over the submitted identifier. That pairing
     * is the whole reason this operation requires an identifier at all.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the renewal selects the refresh flow and sends the token with the client proof")
    void theRenewalSelectsTheRefreshFlow() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.initiateAuth(any(InitiateAuthRequest.class)))
                .thenReturn(InitiateAuthResponse.builder()
                        .authenticationResult(completeResult().refreshToken(null).build())
                        .build());

        service.refresh(new TokenRefreshRequest(USER_ID, "refresh-token"));

        ArgumentCaptor<InitiateAuthRequest> sent =
                ArgumentCaptor.forClass(InitiateAuthRequest.class);
        verify(provider).initiateAuth(sent.capture());
        assertThat(sent.getValue().authFlow()).isEqualTo(AuthFlowType.REFRESH_TOKEN_AUTH);
        assertThat(sent.getValue().authParameters())
                .containsEntry("REFRESH_TOKEN", "refresh-token")
                .containsKey("SECRET_HASH")
                .doesNotContainKey("USERNAME");
    }

    /**
     * Asserts a refused refresh token reports the sign-on-again sentence.
     *
     * <p>The refusal expected is {@link CognitoIdentityService.SessionRefusedException} rather than the
     * bare {@link BadCredentialsException}, because the credential was never in question here.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a refused refresh token reports the sign-on-again sentence")
    void aRefusedRefreshTokenReportsItsOwnSentence() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.initiateAuth(any(InitiateAuthRequest.class)))
                .thenThrow(NotAuthorizedException.builder().message("Refresh Token has expired")
                        .build());

        assertThatThrownBy(() -> service.refresh(new TokenRefreshRequest(USER_ID, "expired")))
                .isInstanceOf(CognitoIdentityService.SessionRefusedException.class)
                .hasMessage(SESSION_REFUSED);
    }

    /**
     * Asserts a locally-unknown identifier is refused on both new exchanges without reaching the pool.
     *
     * <p>Assumptions: the probe is not redundant on the challenge answer just because the caller holds a
     * session this service issued: a row deleted between the sign-on and the answer must not be able to
     * complete an exchange that ends in a usable token set.</p>
     *
     * <p>The refusal expected on both exchanges is
     * {@link CognitoIdentityService.SessionRefusedException}.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a locally-unknown identifier is refused on both new exchanges before the pool")
    void aLocallyUnknownIdentifierIsRefusedBeforeThePool() {
        when(users.existsById(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.answerChallenge(
                        new SignOnChallengeRequest(USER_ID, SESSION, "Perm4nentPassw0rd!")))
                .isInstanceOf(CognitoIdentityService.SessionRefusedException.class)
                .hasMessage(SESSION_REFUSED);

        assertThatThrownBy(() -> service.refresh(new TokenRefreshRequest(USER_ID, "refresh-token")))
                .isInstanceOf(CognitoIdentityService.SessionRefusedException.class)
                .hasMessage(SESSION_REFUSED);

        verify(provider, never()).respondToAuthChallenge(any(RespondToAuthChallengeRequest.class));
        verify(provider, never()).initiateAuth(any(InitiateAuthRequest.class));
    }

    /**
     * Asserts each new exchange refuses an absent submitted value before consulting anything.
     *
     * <p>The refusal expected on every vector is {@link ClientInputException}. The two sign-on presence
     * sentences are pinned by their own cases above; these five vectors cover the three members the
     * challenge answer requires and the two the renewal requires, none of which has a reference
     * counterpart, so only the type and the ordering are asserted here.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("each new exchange refuses an absent submitted value before any lookup")
    void eachNewExchangeRefusesAnAbsentValue() {
        assertThatThrownBy(() -> service.answerChallenge(
                        new SignOnChallengeRequest("  ", SESSION, "Perm4nentPassw0rd!")))
                .isInstanceOf(ClientInputException.class);
        assertThatThrownBy(() -> service.answerChallenge(
                        new SignOnChallengeRequest(USER_ID, "   ", "Perm4nentPassw0rd!")))
                .isInstanceOf(ClientInputException.class);
        assertThatThrownBy(() -> service.answerChallenge(
                        new SignOnChallengeRequest(USER_ID, SESSION, "  ")))
                .isInstanceOf(ClientInputException.class);
        assertThatThrownBy(() -> service.refresh(new TokenRefreshRequest("  ", "refresh-token")))
                .isInstanceOf(ClientInputException.class);
        assertThatThrownBy(() -> service.refresh(new TokenRefreshRequest(USER_ID, "   ")))
                .isInstanceOf(ClientInputException.class);

        verifyNoInteractions(provider, users);
    }

    /**
     * Asserts an absent identifier is refused with the reference sentence before either collaborator.
     *
     * <p>The sentence pinned is the reference literal on {@code app/cbl/COSGN00C.cbl} line 120, whose
     * {@code MOVE} occupies that one line; line 121 is the cursor move that supplies the field key. The
     * refusal expected is {@link ClientInputException}, the type the shared advice renders as a
     * per-field entry.</p>
     *
     * <p>Assumptions: the reference gates its file read on an error flag, at
     * {@code app/cbl/COSGN00C.cbl} lines 138 to 140, so a presence failure never reaches the keyed read
     * at lines 211 to 219. The Java analogue of that gate is that neither the repository nor the pool is
     * consulted at all, which is why this case verifies the absence of every interaction rather than
     * only the sentence: asserting the sentence alone would still pass if the probe ran first and the
     * refusal were raised after it.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an absent identifier is refused before either collaborator is consulted")
    void anAbsentIdentifierIsRefusedBeforeEitherCollaborator() {
        // WHY : Alternatives Considered: the raised instance is captured rather than asserted through a
        //       fluent throwable chain, because the properties that matter here are the refusal's own
        //       accessors -- its code, its single field and its field list -- and a chain can reach the
        //       message and the type but not those. Capturing the instance keeps all four assertions on
        //       one object rather than splitting them across two idioms.
        ClientInputException refused = assertThrows(ClientInputException.class,
                () -> service.authenticate(new SignOnRequest(BLANK, ACCEPTED_CREDENTIAL)));

        assertThat(refused).hasMessage(USER_ID_REQUIRED).hasNoCause();
        assertThat(refused.code()).isEqualTo(ApiError.CODE_VALIDATION);
        assertThat(refused.field()).isEqualTo(FIELD_USER_ID);
        assertThat(refused.fields()).containsExactly(FIELD_USER_ID);

        verifyNoInteractions(users, provider);

        // WHY : Assumptions: an omitted member is exercised beside a blank one because the reference arm
        //       at app/cbl/COSGN00C.cbl line 118 tests SPACES OR LOW-VALUES -- two conditions, not one --
        //       and a JSON document can leave the member out entirely where a declared-width screen field
        //       cannot. The omitted case is what the reference's low-values condition becomes, so both
        //       have to reach the one sentence.
        assertThat(assertThrows(ClientInputException.class,
                () -> service.authenticate(new SignOnRequest(null, ACCEPTED_CREDENTIAL))))
                .hasMessage(USER_ID_REQUIRED);

        verifyNoInteractions(users, provider);
    }

    /**
     * Asserts an absent credential is refused naming the credential field and touching nothing.
     *
     * <p>The sentence pinned is the reference literal on {@code app/cbl/COSGN00C.cbl} line 125, whose
     * {@code MOVE} occupies that one line; line 126 is the cursor move that supplies the field key. The
     * refusal expected is {@link ClientInputException}.</p>
     *
     * <p>Assumptions: this arm is reachable only once the identifier has passed, because the reference
     * chain at {@code app/cbl/COSGN00C.cbl} line 117 is an {@code EVALUATE TRUE} whose second arm on
     * line 123 is tested only when the first on line 118 did not match. A submission carrying an
     * identifier and no credential is therefore the only shape that reaches this sentence.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an absent credential is refused naming the credential field")
    void anAbsentCredentialIsRefusedNamingTheCredentialField() {
        ClientInputException refused = assertThrows(ClientInputException.class,
                () -> service.authenticate(new SignOnRequest(USER_ID, BLANK)));

        assertThat(refused).hasMessage(PASSWORD_REQUIRED).hasNoCause();
        assertThat(refused.code()).isEqualTo(ApiError.CODE_VALIDATION);
        assertThat(refused.field()).isEqualTo(FIELD_PASSWORD);
        assertThat(refused.fields()).containsExactly(FIELD_PASSWORD);

        assertThat(assertThrows(ClientInputException.class,
                () -> service.authenticate(new SignOnRequest(USER_ID, null))))
                .hasMessage(PASSWORD_REQUIRED);

        verifyNoInteractions(users, provider);
    }

    /**
     * Asserts a wholly empty submission names only the earlier of the two fields.
     *
     * <p>The refusal expected is {@link ClientInputException} carrying the identifier sentence from
     * {@code app/cbl/COSGN00C.cbl} line 120 and no trace of the credential sentence from line 125.</p>
     *
     * <p>Assumptions: the ordering is the reference's own and it is observable, which is the whole reason
     * it is pinned. The chain at {@code app/cbl/COSGN00C.cbl} line 117 is an {@code EVALUATE TRUE}, so it
     * takes the FIRST matching arm and leaves the rest untested -- the identifier arm on line 118 before
     * the credential arm on line 123 -- and its {@code WHEN OTHER} on lines 128 and 129 is a bare
     * {@code CONTINUE}. A caller submitting an empty screen is therefore told exactly one sentence, not
     * two, and told the identifier one. Emitting both, or emitting the credential one first, would be a
     * different user-visible outcome for the same submission.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an empty submission names only the earlier of the two fields")
    void anEmptySubmissionNamesOnlyTheEarlierField() {
        ClientInputException refused = assertThrows(ClientInputException.class,
                () -> service.authenticate(new SignOnRequest(BLANK, BLANK)));

        assertThat(refused)
                .hasMessage(USER_ID_REQUIRED)
                .hasMessageNotContaining(PASSWORD_REQUIRED);
        // WHY : Assumptions: the field list is asserted to hold exactly one entry as well, because a
        //       served payload carrying both keys with the identifier's sentence would satisfy the
        //       message assertions above while still marking two controls on the screen.
        assertThat(refused.fields()).containsExactly(FIELD_USER_ID);

        verifyNoInteractions(users, provider);
    }

    /**
     * Asserts a locally-absent row is refused without the credential ever reaching the pool.
     *
     * <p>The refusal expected is {@link BadCredentialsException} and specifically the bare type rather
     * than its {@link CognitoIdentityService.SessionRefusedException} subtype, which carries a different
     * sentence. Being a refusal rather than a {@link ClientInputException}, it carries no field key at
     * all, so no control on the screen is marked.</p>
     *
     * <p>Assumptions: the probe stands in for the keyed read at {@code app/cbl/COSGN00C.cbl} lines 211
     * to 219. That read carries NO {@code UPDATE} option and keys on the folded working-storage
     * identifier named on line 215 with its key length on line 216, so it is a plain positioned read and
     * not a read for update. Nothing here acquires a lock for the same reason the reference does not:
     * no row is written.</p>
     *
     * <p>Assumptions: the reference emits a distinct sentence for this condition, on
     * {@code app/cbl/COSGN00C.cbl} line 249, reached through the numeric {@code WHEN 13} arm on line
     * 247. The target does not emit it. An identifier this context holds no row for is answered with the
     * same sentence a pool-refused credential is answered with, so the two cannot be told apart. The
     * reference distinguishes the two conditions; the Java merges them; the divergence is documented in
     * {@code docs/architecture/cobol-to-service-traceability.md}, and the sentence on line 249 has no
     * assertion anywhere in this package.</p>
     *
     * <p>Trade-offs: the merge gives up a diagnostic the reference gave the caller, namely whether the
     * identifier or the credential was at fault. What it buys is that an unauthenticated caller can no
     * longer harvest valid identifiers one request at a time, which the reference's two distinguishable
     * sentences made possible for anyone able to reach the sign-on screen. The duration floor asserted
     * elsewhere in this class closes the same channel in the timing dimension, and the pair of them is
     * why the diagnostic is given up rather than kept.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a locally-absent row is refused before the credential reaches the pool")
    void aLocallyAbsentRowIsRefusedBeforeTheCredentialReachesThePool() {
        when(users.existsById(USER_ID)).thenReturn(false);

        BadCredentialsException refused = assertThrows(BadCredentialsException.class,
                () -> service.authenticate(new SignOnRequest(USER_ID, ACCEPTED_CREDENTIAL)));

        assertThat(refused)
                .isExactlyInstanceOf(BadCredentialsException.class)
                .hasMessage(CREDENTIAL_REFUSED)
                .hasNoCause();

        // WHY : Assumptions: the pool is asserted untouched because relaying a credential for an
        //       identifier this context holds no row for would present that credential to a third party
        //       on behalf of a subject this context does not own, which is the exposure the ownership
        //       probe exists to prevent. The sentence alone cannot show which of the two steps ran first.
        verifyNoInteractions(provider);
        verify(users).existsById(USER_ID);
    }

    /**
     * Asserts both pool refusal types answer the one sentence and disclose nothing the pool reported.
     *
     * <p>The refusal expected is the bare {@link BadCredentialsException} for both vectors.</p>
     *
     * <p>Assumptions: the pool's own not-found refusal is the vector that matters most here, because it
     * is the condition the reference reports with its own sentence on {@code app/cbl/COSGN00C.cbl} line
     * 249. Routing it to the sentence on lines 242 and 243 instead is the same documented merge the
     * locally-absent case above records, arriving this time from the pool rather than from the local
     * probe.</p>
     *
     * <p>Assumptions: the reference's corresponding arm is asymmetric in a way the target has nothing to
     * reproduce. The arm at {@code app/cbl/COSGN00C.cbl} lines 241 to 246 never moves {@code 'Y'} into
     * the error flag, where the arms at lines 119, 124, 248 and 253 all do. The Java carries no such
     * flag, because error rendering is driven by response data alone rather than by a remembered turn,
     * so this path is reported exactly like every other refusal and the asymmetry has no successor.</p>
     *
     * <p>Assumptions: the pool's reported sentence and its fault class name are both asserted absent
     * from the outward message, because those are the two fragments that differ between the vectors. A
     * message merely equal to the reference sentence would pass even if a diagnostic had been appended
     * on only one of the two paths.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("both pool refusal types answer one sentence with no provider diagnostic")
    void bothPoolRefusalTypesCarryNoProviderDiagnostic() {
        when(users.existsById(USER_ID)).thenReturn(true);

        SdkException[] refusals = {
            NotAuthorizedException.builder().message("Incorrect username or password.").build(),
            UserNotFoundException.builder().message("User does not exist.").build(),
        };

        for (SdkException refusal : refusals) {
            when(provider.initiateAuth(any(InitiateAuthRequest.class))).thenThrow(refusal);

            BadCredentialsException reported = assertThrows(BadCredentialsException.class,
                    () -> service.authenticate(new SignOnRequest(USER_ID, ACCEPTED_CREDENTIAL)));

            assertThat(reported)
                    .as("a pool %s must be reported as the one refusal sentence",
                            refusal.getClass().getSimpleName())
                    .isExactlyInstanceOf(BadCredentialsException.class)
                    .hasMessage(CREDENTIAL_REFUSED)
                    .hasNoCause();
            assertThat(reported.getMessage())
                    .doesNotContain(refusal.getClass().getSimpleName())
                    .doesNotContain(refusal.getMessage());
        }
    }

    /**
     * Asserts every transport and protocol fault collapses to the one unevaluable sentence.
     *
     * <p>The refusal expected is the bare {@link IllegalStateException}, and the bareness is asserted
     * rather than assumed: the shared advice carries a service's own sentence only for that exact type,
     * so a subtype would be rendered with a generic sentence instead of the reference one.</p>
     *
     * <p>Assumptions: the reference groups these faults itself, and it groups them by RAW NUMERIC
     * response code rather than through the {@code DFHRESP} condition names its four sibling programs
     * use. Its {@code EVALUATE WS-RESP-CD} opens at {@code app/cbl/COSGN00C.cbl} line 221, takes
     * {@code WHEN 0} on line 222 and {@code WHEN 13} on line 247, and closes on line 257; everything
     * else falls to the {@code WHEN OTHER} on line 252 and is answered with the single sentence on line
     * 254. The numeric form is what makes the taxonomy explicit enough to transcribe as three outcomes,
     * so three faults that are distinct at the transport layer have to arrive here as one.</p>
     *
     * <p>Assumptions: the outward message is compared for exact equality and the cause for absence,
     * which together prove that no fault class name, no reported status, no provider sentence and no
     * stack trace reached the payload. Those three sentences plus the two presence sentences are the
     * entire externally visible failure vocabulary of this exchange.</p>
     *
     * <p>This case yields no value.</p>
     *
     * @param mode the transport failure being exercised, carried only to name the vector in the report
     * @param fault the provider fault the substituted pool raises for that mode
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("transportFaults")
    @DisplayName("every transport fault collapses to the one unevaluable sentence")
    void everyTransportFaultCollapsesToOneSentence(String mode, SdkException fault) {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.initiateAuth(any(InitiateAuthRequest.class))).thenThrow(fault);

        IllegalStateException unevaluable = assertThrows(IllegalStateException.class,
                () -> service.authenticate(new SignOnRequest(USER_ID, ACCEPTED_CREDENTIAL)));

        assertThat(unevaluable)
                .as("a %s must be unevaluable rather than a refused credential", mode)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(UNABLE_TO_VERIFY)
                .hasNoCause();
        // WHY : Assumptions: the fault's own class name is the one fragment guaranteed to differ across
        //       the three vectors, so proving it absent proves the collapse is a real merge rather than
        //       three sentences that happen to agree on this runner.
        assertThat(unevaluable.getMessage()).doesNotContain(fault.getClass().getSimpleName());
    }

    /**
     * Asserts the identifier is folded locale-invariantly while the credential reaches the pool intact.
     *
     * <p>Assumptions: the reference folds BOTH values, across five lines carrying two folds into three
     * receivers at {@code app/cbl/COSGN00C.cbl} lines 132 to 136 -- the identifier into
     * {@code WS-USER-ID} on line 133 and {@code CDEMO-USER-ID} on line 134, the credential into
     * {@code WS-USER-PWD} on line 136. The target folds the identifier only. The identifier is the
     * primary key of the row the probe reads, so folding it is what lets a lower-case submission find an
     * upper-case row; the credential is left exactly as submitted because the pool compares it byte for
     * byte, so altering it would present characters the caller never typed and turn a correct submission
     * into a refusal naming the wrong cause. The reference folds it; the Java does not; the divergence is
     * documented.</p>
     *
     * <p>Assumptions: the process default locale is moved to Turkish for the duration of the exchange,
     * because that locale is the one place the two folds diverge. Turkish upper-cases {@code i} to a
     * dotted capital outside the ASCII range, so an identifier folded in the default locale would key
     * the probe by a string that is not the stored key and the row would not be found, while the same
     * identifier folded in the root locale keys it by the ASCII form. Only this substitution can tell
     * the two apart; a case run in the runner's own locale passes either way and proves nothing about
     * which locale the fold used.</p>
     *
     * <p>Trade-offs: the default locale is process-wide state, so this case mutates something no other
     * case owns, and it restores it in a finally block. That is accepted because the build declares no
     * parallel execution -- the surefire configuration sets none and the project carries no
     * {@code junit-platform.properties} enabling any -- so classes run one at a time in one process and
     * no other case can observe the window. Asserting the fold on the service's own normaliser instead
     * would need that method made visible, which is a production change made for a test.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the identifier is folded in the root locale and the credential is not folded at all")
    void theIdentifierIsFoldedInvariantlyAndTheCredentialIsNot() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.initiateAuth(any(InitiateAuthRequest.class)))
                .thenReturn(InitiateAuthResponse.builder()
                        .authenticationResult(completeResult().build())
                        .build());

        Locale restored = Locale.getDefault();
        SignOnOutcome outcome;
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            outcome = service.authenticate(
                    new SignOnRequest(SUBMITTED_LOWER_CASE_USER_ID, MIXED_CASE_CREDENTIAL));
        } finally {
            Locale.setDefault(restored);
        }

        // WHY : Assumptions: the probe argument is named rather than left to the stub, because the stub
        //       answers only the folded key and any other key would simply miss it and read as an
        //       ordinary refusal. Naming the argument states which key the row was actually looked up by.
        verify(users).existsById(USER_ID);

        ArgumentCaptor<InitiateAuthRequest> sent = ArgumentCaptor.forClass(InitiateAuthRequest.class);
        verify(provider).initiateAuth(sent.capture());
        assertThat(sent.getValue().authFlow()).isEqualTo(AuthFlowType.USER_PASSWORD_AUTH);
        assertThat(sent.getValue().authParameters())
                .containsEntry("USERNAME", USER_ID)
                .containsEntry("PASSWORD", MIXED_CASE_CREDENTIAL);
        assertThat(((SignOnResponse) outcome).userId()).isEqualTo(USER_ID);
    }

    /**
     * Asserts the success shape carries the whole token set, no authority value and no credential.
     *
     * <p>Assumptions: the sign-on screen is structurally incapable of supplying or displaying an
     * authority value, which is the independent reason none is returned. A search of
     * {@code app/cpy-bms/COSGN00.CPY} for a user-type symbol matches nothing at all; the value reaches
     * the reference only from the security record, moved at {@code app/cbl/COSGN00C.cbl} line 227 after
     * the read on lines 211 to 219 has already succeeded, and the reference then branches on it at lines
     * 230 to 240 to reach either {@code 'COADM01C'} on line 232 or {@code 'COMEN01C'} on line 237. In the
     * target that value is not returned: authority is derived downstream from the signed group claim
     * that {@code com.carddemo.common.security.JwtRoleConverter} converts into authorities, and a signed
     * claim cannot be altered without invalidating the signature. That converter is named here in prose
     * and deliberately not imported, because it takes no part in this exchange.</p>
     *
     * <p>Assumptions: no credential survives on this surface either. The owning migration
     * {@code services/auth-service/src/main/resources/db/migration/V1__auth.sql} declares
     * {@code auth.users} with five columns and none able to hold one, which is why the entity's declared
     * fields are censused here beside the response components. The field this replaces is declared as
     * {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy} line 21, eight bytes at zero-based
     * offset 48 of the eighty-byte record, and compared at {@code app/cbl/COSGN00C.cbl} line 223. The
     * reference stores and compares a readable credential; the Java stores none and compares none; the
     * divergence is documented in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Alternatives Considered: naming the expected members one at a time and asserting each is
     * present. Rejected because that catches only a member removed or renamed, whereas the exposure
     * guarded against here is a member ADDED -- an authority value or a credential appearing on a
     * response that has neither today. A reflective census of the whole component list fails on an
     * addition, which is the direction that matters.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the success shape carries the token set, no authority value and no credential")
    void theSuccessShapeCarriesNoAuthorityValueAndNoCredential() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.initiateAuth(any(InitiateAuthRequest.class)))
                .thenReturn(InitiateAuthResponse.builder()
                        .authenticationResult(completeResult().build())
                        .build());

        SignOnResponse tokens = (SignOnResponse) service.authenticate(
                new SignOnRequest(USER_ID, ACCEPTED_CREDENTIAL));

        assertThat(tokens.outcome()).isEqualTo("AUTHENTICATED");
        assertThat(tokens.userId()).isEqualTo(USER_ID);
        assertThat(tokens.accessToken()).isEqualTo("access-token");
        assertThat(tokens.idToken()).isEqualTo("id-token");
        assertThat(tokens.refreshToken()).isEqualTo("refresh-token");
        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(tokens.expiresIn()).isEqualTo(3600);

        // WHY : Assumptions: the seven names below are the served contract's own property list for this
        //       schema, declared at services/auth-service/src/main/resources/openapi/auth-api.yaml, and
        //       the order is the record header's order because the reflective component list preserves
        //       it. An eighth member could not be added without failing here.
        assertThat(SignOnResponse.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("outcome", "userId", "accessToken", "idToken", "refreshToken",
                        "tokenType", "expiresIn");

        // WHY : Assumptions: synthetic members are filtered out rather than counted, because they are
        //       added by tooling rather than declared by this entity and their presence depends on how
        //       the class was built rather than on what the migration declares.
        assertThat(User.class.getDeclaredFields())
                .filteredOn(declared -> !declared.isSynthetic())
                .extracting(Field::getName)
                .containsExactlyInAnyOrder("userId", "firstName", "lastName", "userType",
                        "cognitoSub");
    }

    /**
     * Supplies one provider fault per transport failure mode the reference catch-all arm covers.
     *
     * <p>Assumptions: the three faults enter the service through three different branches of the
     * provider's exception hierarchy, which is why one vector cannot stand for the other two. A refused
     * connection arrives as a client-side fault, an exceeded per-attempt bound arrives as the timeout
     * subtype of that same client-side fault, and a fault response from the pool arrives through the
     * service-side hierarchy instead. All three share only the one common supertype the service catches,
     * and only that supertype is what makes the reference's line 252 arm expressible as a single
     * clause.</p>
     *
     * <p>This factory takes no parameter.</p>
     *
     * @return a stream of vectors, each pairing a mode name with the fault the substituted pool raises
     *     for it; never {@code null}
     */
    private static Stream<Arguments> transportFaults() {
        return Stream.of(
                Arguments.of("connect timeout", SdkClientException.create(
                        "Unable to execute HTTP request", new ConnectException("Connection timed out"))),
                // WHY : Assumptions: the per-attempt bound is exercised rather than the total one because
                //       the service configures both and the shorter of the two is what a stalled read
                //       trips first. The interval handed in is the same three seconds the service sets on
                //       a single attempt, so the vector describes the bound that actually exists.
                Arguments.of("read timeout", ApiCallAttemptTimeoutException.create(
                        Duration.ofSeconds(3).toMillis())),
                // WHY : Assumptions: an internal-fault response is used for the non-success vector rather
                //       than a throttling one, because throttling is the response an operator would read
                //       as load while this one is read as a pool fault -- and the point of the vector is
                //       that neither is distinguishable from the other in what the caller is told.
                Arguments.of("non-success pool response", InternalErrorException.builder()
                        .message("Internal server error")
                        .statusCode(ApiError.INTERNAL_SERVER_ERROR_STATUS)
                        .build()));
    }

    /**
     * Times one refused sign-on.
     *
     * <p>This helper takes no parameter; the substituted collaborators the enclosing case arranged are
     * what decide which of the two refusal paths is measured.</p>
     *
     * <p>Assumptions: the interval is read from the monotonic timer rather than from the wall clock,
     * because the wall clock can be stepped by a time synchroniser mid-measurement and would then report
     * an interval that never elapsed, in either direction. The service's own padding helper reads the
     * same timer, so the two measure against one source and the floor cannot appear breached by a clock
     * adjustment the padding never saw.</p>
     *
     * <p>Alternatives Considered: returning a sentinel interval when the sign-on was not refused, so the
     * caller could branch on it. Rejected because the two callers assert a LOWER bound, and any sentinel
     * large enough to be recognisable would also clear that bound -- so an exchange that stopped being
     * refused at all would read as a passing measurement. Raising instead makes the wrong path
     * impossible to measure silently.</p>
     *
     * @return the elapsed nanoseconds the refusal took, measured on the monotonic timer for the reason
     *     the service's own padding helper records
     * @throws AssertionError if the sign-on was not refused, which would mean the case was measuring a
     *     path other than the one it names
     */
    private long elapsedNanosOfRefusal() {
        long startedAt = System.nanoTime();
        try {
            service.authenticate(new SignOnRequest(USER_ID, "Passw0rd!"));
        } catch (BadCredentialsException expected) {
            return System.nanoTime() - startedAt;
        }
        throw new AssertionError("the sign-on was expected to be refused");
    }

    /**
     * Builds a complete authentication result, for a case to then remove one member from.
     *
     * <p>This factory takes no parameter.</p>
     *
     * <p>Alternatives Considered: returning a finished result and having each case build its own
     * incomplete variant from scratch. Rejected because a case would then restate every member it is not
     * testing, so a member added to the response contract later would have to be added in seven places
     * and a case that missed it would fail for a reason unrelated to the member it names. Returning a
     * builder lets a case withdraw exactly one member and leave the rest complete, which is what keeps
     * each completeness vector attributable to the single member it removed.</p>
     *
     * @return a builder holding every member the published response declares required, plus a renewal
     *     token; never {@code null}
     */
    private static AuthenticationResultType.Builder completeResult() {
        return AuthenticationResultType.builder()
                .accessToken("access-token")
                .idToken("id-token")
                .refreshToken("refresh-token")
                .tokenType("Bearer")
                .expiresIn(3600);
    }
}
