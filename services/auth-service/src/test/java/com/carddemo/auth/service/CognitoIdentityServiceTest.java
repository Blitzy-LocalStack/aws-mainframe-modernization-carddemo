package com.carddemo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.auth.dto.SignOnChallenge;
import com.carddemo.auth.dto.SignOnChallengeRequest;
import com.carddemo.auth.dto.SignOnOutcome;
import com.carddemo.auth.dto.SignOnRequest;
import com.carddemo.auth.dto.SignOnResponse;
import com.carddemo.auth.dto.TokenRefreshRequest;
import com.carddemo.auth.repository.UserRepository;
import com.carddemo.common.error.ClientInputException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.authentication.BadCredentialsException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ChallengeNameType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RespondToAuthChallengeRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RespondToAuthChallengeResponse;

/**
 * Asserts the three exchanges this service publishes and the four provider outcomes they classify.
 *
 * <h2>What this class exists to catch</h2>
 *
 * <p>Purpose: the review this class answers found that a pool answer of {@code NEW_PASSWORD_REQUIRED}
 * was reported as an unevaluable credential, that no operation existed to answer it, that a
 * data-access failure on the local existence probe escaped untranslated, that a token set the pool
 * reported incompletely was relayed as a success, and that the two refusal paths took measurably
 * different time. Each of those is one case below, and each was invisible to a compiler.</p>
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

    private CognitoIdentityProviderClient provider;

    private UserRepository users;

    private CognitoIdentityService service;

    /**
     * Builds the service over substituted collaborators with a known client identifier and secret.
     */
    @BeforeEach
    void buildService() {
        provider = mock(CognitoIdentityProviderClient.class);
        users = mock(UserRepository.class);
        // WHY : Assumptions: the client secret is a fixed non-empty value rather than a realistic one,
        //       because the confidential-client proof is computed over it and every assertion below is
        //       about the request SHAPE rather than about the digest's value. A blank secret would make
        //       the digest computable but would not exercise the key-initialisation path at all.
        service = new CognitoIdentityService(provider, users, "test-client-id", "test-client-secret");
    }

    /**
     * Asserts a new-password challenge is returned as the challenge shape rather than reported a fault.
     *
     * <p>Assumptions: this is the case the review named critical. Every account the infrastructure
     * provisions is created with a temporary password, so this pool answer is what the FIRST sign-on of
     * every user produces; before the fix it was classified as an unevaluable credential and answered
     * 500, which made no provisioned user able to obtain a token at all.</p>
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
     * <p>Refactoring Rationale: this is the review finding about provider-response validation. Before the
     * fix only the lifetime's presence was checked, so a result whose access token was absent or blank,
     * or whose token type was absent, was relayed as HTTP 200 -- and the caller stored it, sent an empty
     * bearer credential on its next request, was refused with a 401 naming no cause, and was returned to
     * a sign-on screen that had just reported success.</p>
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
     * <p>Refactoring Rationale: this is the review finding about the untranslated store failure. An
     * unwrapped probe let a data-access exception escape this class, and the shared advice then matched
     * it with its unanticipated-failure handler -- which answers 500 with a GENERIC sentence, because it
     * carries a service's own sentence only for the bare illegal-state type. So the one condition the
     * reference's catch-all arm exists for was the one that could not produce the reference's sentence
     * for it.</p>
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
     * <p>Refactoring Rationale: this is the review finding about the timing oracle. The merged refusal
     * sentence closes the WORDING channel, but the locally-refused path performs one indexed probe while
     * the pool-refused path performs a network round trip, so the two were distinguishable by duration
     * and an unauthenticated caller could still enumerate identifiers by timing them.</p>
     *
     * <p>Assumptions: the assertion is a FLOOR and not an equality, because a floor is the property that
     * closes the channel: what must hold is that neither path can complete faster than the other's
     * minimum. Asserting an upper bound as well would make the case fail on a loaded runner for a reason
     * that has nothing to do with the behaviour under test.</p>
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
     */
    @Test
    @DisplayName("a successful sign-on is not padded")
    void aSuccessfulSignOnIsNotPadded() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(provider.initiateAuth(any(InitiateAuthRequest.class)))
                .thenReturn(InitiateAuthResponse.builder()
                        .authenticationResult(completeResult().build())
                        .build());

        long startedAt = System.nanoTime();
        service.authenticate(new SignOnRequest(USER_ID, "Passw0rd!"));
        long elapsed = System.nanoTime() - startedAt;

        assertThat(elapsed)
                .as("padding a success would slow every healthy sign-on for no disclosure it prevents")
                .isLessThan(Duration.ofMillis(400).toNanos());
    }

    /**
     * Asserts the challenge answer sets the password and returns the token set the pool then issues.
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
     * Times one refused sign-on.
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
