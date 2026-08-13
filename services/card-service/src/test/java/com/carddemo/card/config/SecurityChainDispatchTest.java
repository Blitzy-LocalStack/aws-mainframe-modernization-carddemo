package com.carddemo.card.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.security.JwtRoleConverter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
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
 * Asserts what the installed filter chain does with the container's ERROR dispatch, and what it still
 * does with a caller who addresses the error path directly.
 *
 * <p><strong>Purpose.</strong> Every other assertion about this context's authorization matrix is made
 * against the rule table {@link SecurityConfig} declares and the access manager it composes, because both
 * are addressable without a servlet container and that keeps the assertion uniform across the eight
 * contexts. The rule this class covers is not expressible that way at all: it matches a DISPATCHER TYPE
 * rather than a path or an authority, so the only thing that can witness it is a dispatch. That is why this
 * one class assembles a web context and the rest of the package does not.
 *
 * <p>⚠️ Refactoring Rationale: the condition being pinned here was a real refusal, not a hypothetical one.
 * A chain authorizes every dispatcher type unless it is told otherwise, so when the framework could not
 * write a response -- a caller whose accept header admits nothing the converters produce is the reachable
 * case, and this context publishes 406 for exactly that -- the container re-dispatched the request to its
 * own error path, that path matched no rule, and the denying catch-all answered a caller holding a valid
 * group token with 403 "not authorized", on the path {@code /error} rather than its own, with an empty
 * correlation identifier because the filter publishing it had already completed. The rendering failure was
 * reported to the caller as an authorization failure.
 *
 * <p>Measured: with the dispatcher-type permit removed from this context's chain, exactly the two
 * ERROR-dispatch cases fail -- {@link #anErrorDispatchIsNotRefusedAsAnAuthorizationFailure()} and
 * {@link #anErrorDispatchToAPublishedPathIsAdmitted()} -- each because the answer was 401, while
 * {@link #theChainIsInstalled()} and {@link #aDirectRequestToTheErrorPathIsStillRefused()} keep passing.
 * That division is what shows the four cases separate the rule from the catch-all it sits in front of
 * rather than measuring one condition four times. The 401 rather than 403 is itself informative and matches
 * what the account context measured: the authentication filters extend {@code OncePerRequestFilter}, whose
 * {@code shouldNotFilterErrorDispatch()} answers true, so the bearer-token filter does not run on an ERROR
 * dispatch at all and the authorization filter -- which is not a {@code OncePerRequestFilter} and does run
 * -- reaches the rules with no principal.
 *
 * <p>Assumptions: the deployed configuration is INSTANTIATED here and its two needed bean methods are
 * called, rather than registered as a configuration class. That distinction is what makes a web context
 * admissible in this module at all: {@link SecurityConfig#jwtDecoder} builds its decoder from an issuer
 * location and resolves that issuer's discovery document eagerly at construction, so registering the class
 * would reach the network from a unit test -- against a host the test profile points somewhere
 * unresolvable on purpose. Calling only {@code filterChain} and {@code jwtAuthenticationConverter} and
 * supplying a substituted decoder exercises the deployed chain with none of that.
 *
 * <p>Assumptions: the chain is added to the entry point explicitly. A chain is a container-level filter in
 * a running service and this entry point installs no filter it is not given, so omitting that step would
 * leave every case below passing for the wrong reason -- the request would reach the dispatcher with no
 * filter having examined it. {@link #theChainIsInstalled()} exists to prove the installation took.
 *
 * <p>Trade-offs: the context enables the MVC infrastructure but mounts no handler, so an admitted request
 * answers 404. That is deliberate: this class asserts which component DECIDED the answer, and a 404 from
 * the dispatcher is a decision the chain did not take. Mounting a controller to obtain a 200 would add a
 * handler whose behaviour {@code CardControllerTest} already asserts in full.
 *
 * <p>Assumptions: of the four content elements user-specified Rule 1 enumerates, only Purpose applies to a
 * type declaration -- it accepts no parameters, yields no value and raises nothing -- so the other three
 * are inapplicable rather than omitted.
 */
class SecurityChainDispatchTest {

    /** The instant the refusal bodies are stamped from, so a rendered body is reproducible. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-13T00:00:00.000000Z");

    /** The token value a caller presents, which the substituted decoder answers for. */
    private static final String PRESENTED_TOKEN = "presented-token-value";

    /** The subject the substituted decoder puts in the token it answers with. */
    private static final String TOKEN_SUBJECT = "11111111-2222-3333-4444-555555555555";

    /** The framework's own error path, which the container re-dispatches to. */
    private static final String ERROR_PATH = "/error";

    /** A representative published path of this context, used to prove the rule is not path-scoped. */
    private static final String BUSINESS_PATH = "/api/v1/cards";

    /** The context the chain is built in, refreshed once for the class. */
    private static AnnotationConfigWebApplicationContext context;

    /** The entry point every request below is issued through, with the deployed chain installed. */
    private static MockMvc mockMvc;

    /** The substituted token decoder, which answers for the header a caller presents. */
    private static JwtDecoder jwtDecoder;

    /**
     * Refreshes the context once and installs the deployed chain in front of the dispatcher.
     */
    @BeforeAll
    static void refreshSliceContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(SliceWiring.class);
        context.refresh();

        jwtDecoder = context.getBean(JwtDecoder.class);
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
     * Clears the substituted decoder so each case starts from no stubbing.
     */
    @BeforeEach
    void resetSubstitutes() {
        reset(jwtDecoder);
    }

    /**
     * Confirms the chain is actually in front of the dispatcher, so the cases below mean what they say.
     *
     * <p>Assumptions: a published path with no token must be challenged. If the chain were absent this
     * request would reach the dispatcher and answer 404, which is exactly the status an ADMITTED request
     * answers with in this slice -- so without this case an omitted filter would be indistinguishable
     * from a granted dispatch in every other case here.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the deployed chain is installed in front of the dispatcher")
    void theChainIsInstalled() throws Exception {
        mockMvc.perform(get(BUSINESS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED));
    }

    /**
     * Confirms an ERROR dispatch to the framework's error path is not refused by the chain.
     *
     * <p>Assumptions: the assertion is that the answer is NOT either refusal, rather than that it is any
     * particular status. What an error dispatch renders depends on what is mounted at that path, which
     * this slice deliberately does not provide; what matters is that the decision is no longer taken by
     * the security chain.</p>
     *
     * <p>Assumptions: no token is presented. An ERROR dispatch carries whatever the original request
     * carried, and the reachable form of this condition is a rendering failure on a request whose
     * authorization was already decided -- by which time re-deciding it grants nothing and refusing it
     * loses the response the caller was owed.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an error dispatch is not refused as an authorization failure")
    void anErrorDispatchIsNotRefusedAsAnAuthorizationFailure() throws Exception {
        int status = mockMvc.perform(get(ERROR_PATH).with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    return request;
                }))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isNotEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(status).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    /**
     * Confirms the permit follows the dispatcher type rather than the error path's name.
     *
     * <p>Assumptions: the container re-dispatches to whatever path the deployment maps its errors to, and
     * this context does not own that mapping. Asserting the permit on a PUBLISHED path under an ERROR
     * dispatch is what shows the rule would still hold if the error path were renamed, which a case
     * addressing {@code /error} alone cannot distinguish.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an error dispatch to a published path is admitted on its dispatcher type")
    void anErrorDispatchToAPublishedPathIsAdmitted() throws Exception {
        int status = mockMvc.perform(get(BUSINESS_PATH).with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    return request;
                }))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isNotEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(status).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    /**
     * Confirms a caller addressing the error path DIRECTLY is still refused, so the permit widened nothing.
     *
     * <p>Assumptions: the permit matches the dispatcher TYPE and not the path, and this case is what makes
     * that distinction observable. A path-based permit would let any caller provoke the container's own
     * error body, which is not a shape {@code openapi/card-api.yaml} declares for any
     * operation.</p>
     *
     * <p>Assumptions: the presented token carries BOTH business groups, so the refusal cannot be
     * attributed to a missing authority. A token with no group would be refused by the catch-all for a
     * second reason and the case would pass without distinguishing the two.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a direct request to the error path is still refused by the catch-all")
    void aDirectRequestToTheErrorPathIsStillRefused() throws Exception {
        stubDecoderWithGroups(
                List.of(JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY));

        mockMvc.perform(get(ERROR_PATH).header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_FORBIDDEN));
    }

    /**
     * Stubs the substituted decoder to answer with a token carrying the given group names.
     *
     * <p>Assumptions: the claim name comes from the shared converter's own constant rather than a literal
     * here, so a rename reaches this stub instead of leaving it asserting against a name the converter no
     * longer reads.</p>
     *
     * @param groups the group names to carry in the claim; must not be {@code null}
     */
    private static void stubDecoderWithGroups(List<String> groups) {
        Jwt token = Jwt.withTokenValue(PRESENTED_TOKEN)
                .header("alg", "RS256")
                .subject(TOKEN_SUBJECT)
                .issuedAt(FIXED_INSTANT.minusSeconds(60))
                .expiresAt(FIXED_INSTANT.plusSeconds(600))
                .claim(JwtRoleConverter.GROUPS_CLAIM, groups)
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(token);
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
     * Wires the smallest context that can hold the deployed chain: a clock, a substituted decoder, the
     * deployed authority converter and the deployed chain itself.
     *
     * <p>Assumptions: the two group names handed to the converter factory are the shared kernel's own
     * compiled constants. That factory compares what it is given against those constants and refuses to
     * start on a mismatch, so passing them is what keeps this slice's authority derivation identical to a
     * deployment's rather than merely similar to it.</p>
     *
     * <p>Assumptions: of the four content elements user-specified Rule 1 enumerates, only Purpose applies
     * to a type declaration, so the other three are inapplicable rather than omitted.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class SliceWiring {

        /**
         * Supplies the fixed clock the chain's refusal renderers stamp their bodies from.
         *
         * @return a clock pinned to the instant this class fixes; never {@code null}
         */
        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }

        /**
         * Supplies the substituted decoder the resource-server filter resolves a presented token with.
         *
         * @return a substitute for the token decoder, with no stubbing applied; never {@code null}
         */
        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        /**
         * Supplies the deployed token-to-authentication converter, group names included.
         *
         * @return the converter the deployed configuration builds; never {@code null}
         */
        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
            return new SecurityConfig().jwtAuthenticationConverter(
                    JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);
        }

        /**
         * Supplies the deployed filter chain, rules, refusal renderers and session policy included.
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
            return new SecurityConfig().filterChain(http, converter, clock);
        }
    }
}
