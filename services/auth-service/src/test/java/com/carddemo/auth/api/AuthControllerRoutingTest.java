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
import com.carddemo.auth.service.CognitoIdentityService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Asserts that both published sign-on routes are actually mapped, and that a withheld sign-on reaches a
 * caller as the discriminated challenge body the contract declares.
 *
 * <h2>Purpose</h2>
 *
 * <p>The review finding this class answers is that a seeded identity could not complete a first sign-on,
 * because the pool's {@code NEW_PASSWORD_REQUIRED} challenge was discarded and no operation existed to
 * answer it. Two halves of that fix are only observable over HTTP: that {@code POST
 * /api/v1/auth/challenge} resolves to a handler at all, and that the challenge outcome serialises with the
 * {@code outcome} discriminator the contract's {@code oneOf} keys on. A service-level test cannot see
 * either -- it would pass with the handler unmapped and with the discriminator absent from the wire.</p>
 *
 * <p>Assumptions: the controller is stood up alone rather than through the application context, and the
 * identity service is substituted. What is under test is the mapping and the serialisation, both of which
 * belong to this adapter; the exchange behind it is covered by
 * {@code com.carddemo.auth.service.CognitoIdentityServiceTest} against a substituted pool, and standing up
 * the context here would add a database, a pool client and an issuer to a test that asserts neither.</p>
 *
 * <p>Trade-offs: with the controller stood up alone, the shared error advice and the filter chain are both
 * absent, so nothing here asserts a status either produces -- the chain's treatment of these two paths as
 * open is asserted by {@code com.carddemo.auth.config.SecurityConfigTest}, and the advice's renderings by
 * the shared kernel's own tests. What is given up is end-to-end coverage of one request; what is gained is
 * a test that fails for exactly one reason.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * at-clause; the convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
class AuthControllerRoutingTest {

    /** The identifier used on every exchange below. */
    private static final String USER_ID = "TESTUSR1";

    /** The continuation value the substituted service reports with its challenge. */
    private static final String SESSION = "AYABeExampleContinuationValueFromThePool";

    /** The substituted exchange behind the adapter. */
    private CognitoIdentityService identityService;

    /** The adapter under test, driven without a servlet container. */
    private MockMvc mvc;

    /**
     * Stands the adapter up alone with a substituted service and a fixed clock.
     *
     * <p>Assumptions: the clock is fixed rather than the system one, because a fixed instant keeps any
     * timestamp the adapter renders identical between runs. Nothing under test reads it, and passing the
     * system clock would leave a dependency whose value varies for no reason.</p>
     */
    @BeforeEach
    void setUp() {
        this.identityService = mock(CognitoIdentityService.class);
        this.mvc = MockMvcBuilders.standaloneSetup(new AuthController(this.identityService,
                        Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC)))
                .build();
    }

    /**
     * Verifies a withheld sign-on serialises as the challenge variant with its discriminator.
     *
     * @throws Exception when the request cannot be performed, which the framework raises and no assertion
     *     here recovers from
     */
    @Test
    @DisplayName("a withheld sign-on answers 200 with the challenge variant and its discriminator")
    void aWithheldSignOnAnswersTheChallengeVariant() throws Exception {
        when(this.identityService.authenticate(any(SignOnRequest.class)))
                .thenReturn(new SignOnChallenge(SignOnChallenge.OUTCOME_CHALLENGE,
                        SignOnChallenge.CHALLENGE_NEW_PASSWORD_REQUIRED, SESSION, USER_ID));

        this.mvc.perform(post(AuthController.SIGNON_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + USER_ID + "\",\"password\":\"Sup3rSecret!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value(SignOnChallenge.OUTCOME_CHALLENGE))
                .andExpect(jsonPath("$.challengeName")
                        .value(SignOnChallenge.CHALLENGE_NEW_PASSWORD_REQUIRED))
                .andExpect(jsonPath("$.session").value(SESSION))
                .andExpect(jsonPath("$.userId").value(USER_ID));
    }

    /**
     * Verifies an accepted credential serialises as the authenticated variant with its discriminator.
     *
     * @throws Exception when the request cannot be performed
     */
    @Test
    @DisplayName("an accepted credential answers 200 with the authenticated variant")
    void anAcceptedCredentialAnswersTheAuthenticatedVariant() throws Exception {
        when(this.identityService.authenticate(any(SignOnRequest.class)))
                .thenReturn(new SignOnResponse(SignOnResponse.OUTCOME_AUTHENTICATED, USER_ID,
                        "access", "id", "refresh", "Bearer", 3600));

        this.mvc.perform(post(AuthController.SIGNON_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + USER_ID + "\",\"password\":\"Sup3rSecret!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value(SignOnResponse.OUTCOME_AUTHENTICATED))
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.challengeName").doesNotExist());
    }

    /**
     * Verifies the challenge answer route is mapped and returns the issued token set.
     *
     * <p>Assumptions: this is the assertion the finding turns on. Before the fix the contract published this
     * path and no handler served it, so a seeded operator answering the challenge received the container's
     * not-found page. Asserting a 200 with a token set proves both that the route resolves and that it
     * answers with the shape the contract declares for it.</p>
     *
     * @throws Exception when the request cannot be performed
     */
    @Test
    @DisplayName("the challenge answer route is mapped and returns the issued token set")
    void theChallengeAnswerRouteIsMapped() throws Exception {
        when(this.identityService.answerChallenge(any(SignOnChallengeRequest.class)))
                .thenReturn(new SignOnResponse(SignOnResponse.OUTCOME_AUTHENTICATED, USER_ID,
                        "access", "id", "refresh", "Bearer", 3600));

        this.mvc.perform(post(AuthController.CHALLENGE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + USER_ID + "\",\"session\":\"" + SESSION
                                + "\",\"newPassword\":\"N3wCredential!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value(SignOnResponse.OUTCOME_AUTHENTICATED))
                .andExpect(jsonPath("$.accessToken").value("access"));
    }

    /**
     * Verifies the two paths this adapter serves are the two the filter chain opens.
     *
     * <p>Assumptions: the constants are compared rather than the literals repeated, so a rename in either
     * place fails here instead of leaving a route the chain guards and the contract publishes as open.
     * {@code com.carddemo.auth.config.SecurityConfig} is not imported by the adapter itself, which is why
     * this comparison lives in a test rather than in the class.</p>
     */
    @Test
    @DisplayName("both served paths equal the ones the filter chain opens")
    void bothServedPathsEqualTheOnesTheChainOpens() {
        org.assertj.core.api.Assertions.assertThat(AuthController.SIGNON_PATH)
                .isEqualTo(com.carddemo.auth.config.SecurityConfig.SIGNON_PATH);
        org.assertj.core.api.Assertions.assertThat(AuthController.CHALLENGE_PATH)
                .isEqualTo(com.carddemo.auth.config.SecurityConfig.CHALLENGE_PATH);
    }
}
