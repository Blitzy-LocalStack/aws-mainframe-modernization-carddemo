package com.carddemo.auth.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.auth.dto.SignOnChallenge;
import com.carddemo.auth.dto.SignOnChallengeRequest;
import com.carddemo.auth.dto.SignOnRequest;
import com.carddemo.auth.dto.SignOnResponse;
import com.carddemo.auth.dto.TokenRefreshRequest;
import com.carddemo.auth.service.CognitoIdentityService;
import com.carddemo.common.error.GlobalExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asserts the three sign-on routes this adapter serves and the status each outcome answers with.
 *
 * <h2>What this class exists to catch</h2>
 *
 * <p>Purpose: the review this class answers found that two of the three routes the contract publishes
 * under this tag were not served at all -- the gateway forwarded them and the service answered 404 -- and
 * that the one route which was served could not express the challenge outcome, so every provisioned
 * user's first sign-on answered 500. This class asserts that all three routes exist, that the sign-on
 * route can answer with either success shape, and that a refused session is told apart from a refused
 * credential by the sentence its status carries.
 *
 * <p>Assumptions: the adapter is exercised through a standalone setup with the shared advice registered,
 * because the mapping under test is the pairing of an outcome with a STATUS and a status is only
 * observable through a response. Calling the handler directly would assert the delegation and nothing
 * about what a caller receives.
 *
 * <p>Trade-offs: no filter chain is installed, so nothing here proves these three routes are reachable
 * without a token. That property is asserted where it is declared, against the rule table the chain is
 * built from, by the security configuration cases in this module; duplicating it here would assert a
 * chain this setup does not run.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception clause.</p>
 */
class AuthControllerTest {

    /** A fixed instant, so a stamped problem body is reproducible from the source alone. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-08T09:14:27.481903Z");

    /** The serialiser the request bodies below are written with. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private CognitoIdentityService identity;

    private MockMvc mockMvc;

    /**
     * Builds the adapter over a substituted identity service with the shared advice registered.
     */
    @BeforeEach
    void buildMockMvc() {
        identity = mock(CognitoIdentityService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(identity,
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * Asserts a sign-on that yields tokens answers 200 with the authenticated discriminator.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a sign-on that yields tokens answers 200 with outcome AUTHENTICATED")
    void aSignOnThatYieldsTokensAnswersAuthenticated() throws Exception {
        when(identity.authenticate(any(SignOnRequest.class))).thenReturn(new SignOnResponse(
                SignOnResponse.OUTCOME_AUTHENTICATED, "ADMIN001", "access-token", "id-token",
                "refresh-token", "Bearer", 3600));

        mockMvc.perform(post(AuthController.SIGNON_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(
                                new SignOnRequest("ADMIN001", "Passw0rd!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.expiresIn").value(3600));
    }

    /**
     * Asserts a sign-on that raises a challenge answers 200 with the challenge shape and no token.
     *
     * <p>Refactoring Rationale: this is the case the review named critical. The handler's return type was
     * the token set alone, so this outcome could not be expressed and was reported as a fault -- which,
     * since every provisioned account is created with a temporary password, is what every user's first
     * sign-on received.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a sign-on that raises a challenge answers 200 with outcome CHALLENGE and no token")
    void aSignOnThatRaisesAChallengeAnswersChallenge() throws Exception {
        when(identity.authenticate(any(SignOnRequest.class)))
                .thenReturn(SignOnChallenge.newPasswordRequired("session-value", "ADMIN001"));

        mockMvc.perform(post(AuthController.SIGNON_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(
                                new SignOnRequest("ADMIN001", "TempPassw0rd!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CHALLENGE"))
                .andExpect(jsonPath("$.challengeName").value("NEW_PASSWORD_REQUIRED"))
                .andExpect(jsonPath("$.session").value("session-value"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    /**
     * Asserts a refused credential answers 401 with the reference sentence and no field entry.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a refused credential answers 401 with the reference sentence")
    void aRefusedCredentialAnswers401() throws Exception {
        when(identity.authenticate(any(SignOnRequest.class)))
                .thenThrow(new org.springframework.security.authentication.BadCredentialsException(
                        AuthController.MESSAGE_CREDENTIAL_REFUSED));

        mockMvc.perform(post(AuthController.SIGNON_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(
                                new SignOnRequest("ADMIN001", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Wrong Password. Try again ..."))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    /**
     * Asserts the challenge answer route is served and returns the token set the pool then issued.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the challenge route answers 200 with the token set the sign-on could not issue")
    void theChallengeRouteAnswersWithTokens() throws Exception {
        when(identity.answerChallenge(any(SignOnChallengeRequest.class)))
                .thenReturn(new SignOnResponse(SignOnResponse.OUTCOME_AUTHENTICATED, "ADMIN001",
                        "access-token", "id-token", "refresh-token", "Bearer", 3600));

        mockMvc.perform(post(AuthController.CHALLENGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(new SignOnChallengeRequest(
                                "ADMIN001", "session-value", "Perm4nentPassw0rd!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.accessToken").value("access-token"));
    }

    /**
     * Asserts a refused session answers 401 with the authored sentence, not the credential sentence.
     *
     * <p>Assumptions: the distinction is the point of the second handler. On this exchange the credential
     * was accepted -- the challenge being what proves it -- so reporting that the password was wrong would
     * send the caller to reset a credential that works.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a refused session answers 401 with the sign-on-again sentence")
    void aRefusedSessionAnswers401WithItsOwnSentence() throws Exception {
        when(identity.answerChallenge(any(SignOnChallengeRequest.class))).thenAnswer(invocation -> {
            throw refusedSession();
        });

        mockMvc.perform(post(AuthController.CHALLENGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(new SignOnChallengeRequest(
                                "ADMIN001", "stale-session", "Perm4nentPassw0rd!"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Please sign on again ..."));
    }

    /**
     * Asserts the renewal route is served and returns the renewed set with a null renewal token.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the renewal route answers 200 with a null renewal token")
    void theRenewalRouteAnswersWithANullRenewalToken() throws Exception {
        when(identity.refresh(any(TokenRefreshRequest.class)))
                .thenReturn(new SignOnResponse(SignOnResponse.OUTCOME_AUTHENTICATED, "ADMIN001",
                        "new-access-token", "new-id-token", null, "Bearer", 3600));

        mockMvc.perform(post(AuthController.REFRESH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(
                                new TokenRefreshRequest("ADMIN001", "refresh-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.refreshToken").value(org.hamcrest.Matchers.nullValue()));
    }

    /**
     * Asserts a blank submitted field is answered 400 without the identity service being consulted.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a blank submitted field answers 400 with the reference sentence")
    void aBlankFieldAnswers400() throws Exception {
        mockMvc.perform(post(AuthController.SIGNON_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(new SignOnRequest("   ", "        "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please enter User ID ..."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("userId"));
    }

    /**
     * Builds the session refusal the service raises, which is package-private to its own package.
     *
     * <p>Assumptions: it is built reflectively because its constructor is package-private in
     * {@code com.carddemo.auth.service}, which is deliberate there -- the refusal is decided in one place
     * -- and this class sits in a different package. Widening the constructor to let a test build one
     * would weaken the property the visibility exists to hold.</p>
     *
     * @return the refusal to throw from the substituted service; never {@code null}
     * @throws ReflectiveOperationException if the constructor cannot be resolved, which a rename would
     *     cause and which should surface here rather than as a silent skip
     */
    private static Throwable refusedSession() throws ReflectiveOperationException {
        var constructor = CognitoIdentityService.SessionRefusedException.class
                .getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(CognitoIdentityService.MESSAGE_SESSION_REFUSED);
    }
}
