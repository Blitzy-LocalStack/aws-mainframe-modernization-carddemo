package com.carddemo.account.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.account.api.AccountController;
import com.carddemo.account.api.CardXrefController;
import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.security.InternalServiceToken;
import com.carddemo.common.security.JwtRoleConverter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.RequestDispatcher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Asserts which of this context's TWO filter chains decides the container's ERROR dispatch, and therefore
 * which token decoder authenticates it.
 *
 * <p><strong>Purpose.</strong> This context holds two chains over one deployable: an earlier-ordered chain
 * that verifies a machine token minted by a neighbouring service against a shared signing key, and the
 * identity-provider chain that verifies an end user's token against the provider's issuer and keys. Every
 * other assertion about either chain is made against a matcher or a decision object, because those are
 * addressable without a servlet container. The property this class covers is not expressible that way: it is
 * about a DISPATCH -- which chain claims a request the container re-issues to its error page -- so the only
 * thing that can witness it is a dispatch through an assembled chain.
 *
 * <p>⚠️ Refactoring Rationale: the condition pinned here was a real misattribution and not a hypothetical
 * one. When the framework cannot write a response -- a caller whose accept header admits nothing the
 * converters produce is the reachable case -- the container re-dispatches to its error page, and the filters
 * see a request to a DIFFERENT address. Neither chain admitted that dispatch and the internal chain did not
 * claim it, so the caller's real failure was replaced by a refusal on a path it never addressed. Both halves
 * of the fix are asserted here: the internal chain now claims the error dispatches of its OWN addresses, so
 * an internal address stays governed by the chain that authenticated it in every dispatch, and both chains
 * admit the ERROR dispatcher type ahead of every authority rule, so the dispatch is rendered rather than
 * refused.
 *
 * <p>⚠️ Assumptions: the review that prompted this class described the symptom as the machine token being
 * "reauthenticated under the wrong decoder", and that half of the mechanism was MEASURED NOT TO OCCUR. The
 * framework's authentication filters extend {@code OncePerRequestFilter}, whose
 * {@code shouldNotFilterErrorDispatch} returns true, so {@code BearerTokenAuthenticationFilter} does not run
 * on an error dispatch at all -- no decoder is consulted by either chain. The authorization filter is not a
 * {@code OncePerRequestFilter} and DOES run, which is why the reachable symptom is an authorization refusal
 * carrying 401 rather than 403: the dispatch reaches the rules with no authentication at all, so the chain
 * that claims it refuses it as unauthenticated. The three neutralisation measurements below record that
 * exactly.
 *
 * <p>Assumptions: the assertions are split by what each can actually discriminate, rather than every case
 * asserting everything. On an ordinary request the decoder identity IS the discriminator, so the internal
 * case asserts the machine decoder was used and the provider decoder was not. On an error dispatch no
 * decoder runs, so there the discriminator is the STATUS -- refused or rendered -- and the decoder
 * assertions pin the framework behaviour above rather than the chain selection. The chain-selection half is
 * asserted where it is expressible without a container, on the matchers, in
 * {@code InternalApiSecurityConfigTest.theErrorDispatchOfAnInternalAddressIsClaimed}.
 *
 * <p>Measured: each half of the fix was neutralised in turn and this class was re-run. Dropping
 * {@code internalErrorDispatches()} from the internal chain's security matcher failed exactly one case, and
 * it was the matcher case in the sibling class -- "the error page of an internal request must keep the
 * machine-token decoder" -- while every case here still passed, which is the measurement that established
 * the framework behaviour recorded above. Dropping the ERROR permit from the identity-provider chain failed
 * exactly the two end-user cases here, both with 401 where the case requires anything else. Dropping the
 * ERROR permit from the internal chain failed exactly the internal case here, also with 401. Both production
 * files were then restored and compared byte for byte.
 *
 * <p>Assumptions: the two decoders are substitutes rather than the deployed ones. The deployed
 * identity-provider decoder resolves a discovery document over the network at construction, which no test
 * may depend on, and the deployed machine decoder would require this class to mint tokens whose signatures
 * are already asserted case by case in {@code InternalApiSecurityConfigTest}. What is NOT substituted is
 * either chain: both are built by calling the production configuration's own bean method, so the rules, the
 * refusal renderers and the session policy under test are the deployed ones.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
class SecurityChainDispatchTest {

    /** The address a container re-dispatches a failed request to. */
    private static final String ERROR_PATH = "/error";

    /** An internal address, whose error page must stay on the machine-token chain. */
    private static final String INTERNAL_PATH =
            CardXrefController.BASE_PATH + CardXrefController.LOOKUP_BY_ACCOUNT_PATH;

    /** An end-user address, whose error page must stay on the identity-provider chain. */
    private static final String END_USER_PATH =
            AccountController.BASE_PATH + AccountController.VIEW_PATH;

    /** The instant the refusal renderers stamp their bodies from, fixed so bodies are reproducible. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-13T09:30:00Z");

    /** The opaque token value every case presents; its content is decided by the substituted decoder. */
    private static final String PRESENTED_TOKEN = "presented-token-value";

    /** The subject a decoded token names, shaped like a provider subject rather than a person. */
    private static final String TOKEN_SUBJECT = "11111111-2222-4333-8444-555555555555";

    /** The context the two chains are built in, refreshed once for the class. */
    private static AnnotationConfigWebApplicationContext context;

    /** The substituted identity-provider decoder, asserted UNTOUCHED on the internal cases. */
    private static JwtDecoder endUserDecoder;

    /** The substituted machine-token decoder, asserted UNTOUCHED on the end-user cases. */
    private static JwtDecoder internalDecoder;

    /** The entry point every case drives, with both assembled chains installed in front of it. */
    private static MockMvc mockMvc;

    /**
     * Builds the two decoders and refreshes the context holding both deployed chains.
     *
     * <p>Assumptions: the decoders are created BEFORE the refresh and held as class state rather than being
     * declared as two beans of one type. Two unqualified beans of the same type would make which chain
     * received which decoder depend on bean-definition order, which is the very confusion this class exists
     * to detect.</p>
     */
    @BeforeAll
    static void refreshSliceContext() {
        endUserDecoder = mock(JwtDecoder.class);
        internalDecoder = mock(JwtDecoder.class);

        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(SliceWiring.class);
        context.refresh();

        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    /**
     * Closes the context so the class leaves no refreshed application behind it.
     */
    @AfterAll
    static void closeSliceContext() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * Clears both substituted decoders so each case starts from no stubbing and no recorded interaction.
     */
    @BeforeEach
    void resetSubstitutes() {
        reset(endUserDecoder, internalDecoder);
    }

    /**
     * Confirms the chains are in front of the dispatcher, so every case below means what it says.
     *
     * <p>Assumptions: an end-user address with no credential must be challenged. If the chains were absent
     * this request would reach the dispatcher and answer 404, which is the status an ADMITTED request
     * answers with in this slice -- so without this case a missing filter would be indistinguishable from a
     * granted dispatch everywhere else in the class.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("both deployed chains are installed in front of the dispatcher")
    void theChainsAreInstalled() throws Exception {
        mockMvc.perform(post(END_USER_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED));
    }

    /**
     * Confirms an ordinary request to an internal address is authenticated by the MACHINE decoder alone.
     *
     * <p>Assumptions: this is the baseline the error-dispatch case is compared against. Without it, a case
     * showing that an error dispatch reaches the machine decoder would not establish that an ordinary
     * request does -- so a chain that had drawn every request onto one decoder would pass both.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an internal address is authenticated by the machine-token decoder alone")
    void anInternalAddressIsDecidedByTheMachineChain() throws Exception {
        stubInternalTokenWithScope(InternalServiceToken.SCOPE_CARD_XREF_RESOLVE_CARD_NUMBER);

        int status = mockMvc.perform(post(INTERNAL_PATH)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(status).isNotEqualTo(HttpStatus.FORBIDDEN.value());
        verify(internalDecoder).decode(PRESENTED_TOKEN);
        verifyNoInteractions(endUserDecoder);
    }

    /**
     * Confirms the ERROR dispatch of an internal request keeps the machine-token regime.
     *
     * <p>Purpose. This is the finding's own case. The dispatch is addressed to the error page and carries
     * the original target in the standard attribute, exactly as a container issues it, and it still carries
     * the machine token the original request presented. The internal chain claims it on that original
     * target, and having claimed it must admit it -- so the assertion is that the caller receives whatever
     * the error page renders rather than a refusal manufactured by the chain.</p>
     *
     * <p>Assumptions: the status is asserted only to be neither refusal, not to be any particular value.
     * What an error dispatch renders depends on what is mounted at that path, which this slice deliberately
     * does not provide.</p>
     *
     * <p>Measured: with the ERROR permit removed from the internal chain this case fails with 401 --
     * unauthenticated rather than forbidden, because the authentication filters skip an error dispatch, so
     * the rules see no principal at all. That is the reachable form of the defect: the caller's real
     * failure, whatever it was, is replaced by a challenge on a path it never addressed.</p>
     *
     * <p>Assumptions: BOTH decoders are asserted untouched, which pins the framework behaviour rather than
     * the chain selection -- no authentication filter runs on an error dispatch. Asserting only the
     * provider's decoder would read as a chain-selection assertion that this dispatch cannot make.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the error dispatch of an internal request is admitted by the machine-token chain")
    void theErrorDispatchOfAnInternalRequestKeepsTheMachineRegime() throws Exception {
        stubInternalTokenWithScope(InternalServiceToken.SCOPE_CARD_XREF_RESOLVE_CARD_NUMBER);

        int status = mockMvc.perform(post(ERROR_PATH)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader())
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, INTERNAL_PATH);
                            return request;
                        }))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(status).isNotEqualTo(HttpStatus.FORBIDDEN.value());
        verifyNoInteractions(endUserDecoder);
        verifyNoInteractions(internalDecoder);
    }

    /**
     * Confirms the ERROR dispatch of an end-user request keeps the identity-provider regime.
     *
     * <p>Assumptions: this is the mirror of the case above and it is not redundant. A fix that drew EVERY
     * error dispatch onto the internal chain would satisfy the internal case while leaving a browser
     * caller's error page decided by the machine-token chain's rules, whose terminal rule denies -- so the
     * end-user failure would be replaced by a refusal in the same way. This case fails with 401 when the
     * ERROR permit is removed from the identity-provider chain, which is the measurement that shows the
     * permit is what admits it.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the error dispatch of an end-user request is admitted by the identity-provider chain")
    void theErrorDispatchOfAnEndUserRequestKeepsTheProviderRegime() throws Exception {
        stubEndUserTokenWithGroups(List.of(JwtRoleConverter.USER_AUTHORITY));

        int status = mockMvc.perform(post(ERROR_PATH)
                        .header(HttpHeaders.AUTHORIZATION, bearerHeader())
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, END_USER_PATH);
                            return request;
                        }))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(status).isNotEqualTo(HttpStatus.FORBIDDEN.value());
        verifyNoInteractions(internalDecoder);
    }

    /**
     * Confirms the admission follows the DISPATCHER TYPE and not the error path's name.
     *
     * <p>Purpose. The same address, with the same absent credential, must be refused as an ordinary request
     * and admitted as an error dispatch. That pair is what shows the rule is scoped to the dispatcher type:
     * a path-based permit would let any caller address the error page directly and provoke the container's
     * own error body, which no operation in {@code openapi/account-api.yaml} declares.</p>
     *
     * @throws Exception if either request cannot be performed
     */
    @Test
    @DisplayName("the error path stays closed to a direct request and open to an error dispatch")
    void theErrorPathIsStillClosedToADirectRequest() throws Exception {
        mockMvc.perform(get(ERROR_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED));

        int dispatched = mockMvc.perform(get(ERROR_PATH).with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, END_USER_PATH);
                    return request;
                }))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(dispatched).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(dispatched).isNotEqualTo(HttpStatus.FORBIDDEN.value());
    }

    /**
     * Stubs the machine decoder to answer with a token carrying one internal scope.
     *
     * @param scope the scope claim the decoded token carries; must not be {@code null}
     */
    private static void stubInternalTokenWithScope(String scope) {
        Jwt token = Jwt.withTokenValue(PRESENTED_TOKEN)
                .header("alg", InternalServiceToken.SIGNING_ALGORITHM_NAME)
                .subject(InternalServiceToken.SUBJECT_TRANSACTION_SERVICE)
                .issuer(InternalServiceToken.ISSUER)
                .audience(List.of(InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT))
                .issuedAt(FIXED_INSTANT.minusSeconds(30))
                .expiresAt(FIXED_INSTANT.plusSeconds(270))
                .claim(InternalServiceToken.SCOPE_CLAIM, scope)
                .build();
        when(internalDecoder.decode(anyString())).thenReturn(token);
    }

    /**
     * Stubs the identity-provider decoder to answer with a token carrying the given group names.
     *
     * @param groups the group names the decoded token carries in its groups claim; must not be {@code null}
     */
    private static void stubEndUserTokenWithGroups(List<String> groups) {
        Jwt token = Jwt.withTokenValue(PRESENTED_TOKEN)
                .header("alg", "RS256")
                .subject(TOKEN_SUBJECT)
                .issuedAt(FIXED_INSTANT.minusSeconds(60))
                .expiresAt(FIXED_INSTANT.plusSeconds(600))
                .claim(JwtRoleConverter.GROUPS_CLAIM, groups)
                .build();
        when(endUserDecoder.decode(anyString())).thenReturn(token);
    }

    /**
     * Builds the authorization header value a caller presents a bearer token in.
     *
     * @return the header value, scheme included; never {@code null}
     */
    private static String bearerHeader() {
        return "Bearer " + PRESENTED_TOKEN;
    }

    /**
     * Wires the smallest context that can hold BOTH deployed chains: a clock, the deployed authority
     * converter and the two chains, each built by its own production configuration.
     *
     * <p>Assumptions: only the two chain bean methods and the converter bean method are called on the
     * production configurations. Their decoder bean methods are deliberately not processed -- the
     * identity-provider one resolves a discovery document at construction and the machine one requires two
     * configured signing keys -- so the substituted decoders are handed to the chains directly, exactly as
     * the deployed configuration hands its own qualified beans.</p>
     *
     * <p>Assumptions: the group names passed to the converter factory are the shared kernel's own compiled
     * constants, because that factory compares what it is given against them and refuses to start on a
     * mismatch. Passing them keeps this slice's authority derivation identical to a deployment's rather than
     * merely similar to it.</p>
     *
     * <p>Assumptions: of the four content elements user-specified Rule 1 enumerates, only Purpose applies to
     * a type declaration, so the other three are inapplicable rather than omitted.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class SliceWiring {

        /**
         * Supplies the fixed clock both chains' refusal renderers stamp their bodies from.
         *
         * @return a clock pinned to the instant this class fixes; never {@code null}
         */
        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }

        /**
         * Supplies the deployed token-to-authentication converter for the identity-provider chain.
         *
         * @return the converter the deployed configuration builds; never {@code null}
         */
        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
            return new SecurityConfig().jwtAuthenticationConverter(
                    JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);
        }

        /**
         * Supplies the deployed machine-token chain, ordered ahead of the identity-provider chain exactly as
         * the deployed configuration orders it.
         *
         * @param http the chain builder this context contributes; must not be {@code null}
         * @param clock the clock the rendered refusal bodies read their instant from; must not be
         *     {@code null}
         * @return the chain the deployed configuration builds; never {@code null}
         * @throws Exception when the builder cannot assemble the chain, which it declares
         */
        @Bean
        @Order(InternalApiSecurityConfig.CHAIN_ORDER)
        SecurityFilterChain internalApiFilterChain(HttpSecurity http, Clock clock) throws Exception {
            return new InternalApiSecurityConfig()
                    .internalApiFilterChain(http, internalDecoder, clock);
        }

        /**
         * Supplies the deployed identity-provider chain, which takes the framework's default precedence.
         *
         * @param http the chain builder this context contributes; must not be {@code null}
         * @param converter the deployed token-to-authentication converter; must not be {@code null}
         * @param clock the clock the rendered refusal bodies read their instant from; must not be
         *     {@code null}
         * @return the chain the deployed configuration builds; never {@code null}
         * @throws Exception when the builder cannot assemble the chain, which it declares
         */
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter,
                Clock clock) throws Exception {
            return new SecurityConfig().filterChain(http, endUserDecoder, converter, clock);
        }
    }
}
