package com.carddemo.transaction.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.ApiErrorSecurityHandlers;
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
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Asserts what the installed filter chain does with the container's ERROR dispatch, and what it still does
 * with a caller who addresses the error path directly.
 *
 * <p><strong>Purpose.</strong> Every other assertion about this context's authorization matrix is made
 * against the rule table {@link SecurityConfig} declares, because a table is addressable without a servlet
 * container and that keeps the assertion uniform across the eight contexts. The rule this class covers is
 * not expressible that way at all: it matches a DISPATCHER TYPE rather than a path or an authority, so the
 * only thing that can witness it is a dispatch. That is why this one class assembles a web context and the
 * rest of the package does not.
 *
 * <p>⚠️ Refactoring Rationale: the condition pinned here was a real refusal, and this chain
 * reaches it through its CATCH-ALL rather than through a method rule. Every business route of
 * this context is authorized by {@code anyRequest().access(businessAccess())}, so an ERROR
 * forward -- which keeps the original method and arrives with no authentication, because the
 * bearer-token filter skips an error dispatch -- was judged by that same rule with no principal
 * and answered 401. A caller owed 406, 413 or 415 therefore received 401 on a path it never
 * addressed. This document began publishing those three statuses in the same change that added
 * this rule, which is what turned a rare outcome into a routine one and is why the two land
 * together.
 *
 * <p>Measured: with the permit narrowed from the ERROR dispatch to the ASYNC one -- a change that still
 * compiles and still permits a dispatcher type -- exactly the three ERROR-dispatch cases fail:
 * {@link #anErrorDispatchIsNotRefusedAsAnAuthorizationFailure()},
 * {@link #anErrorDispatchOfAMutatingMethodIsAdmitted()} and
 * {@link #anErrorDispatchToAPublishedPathIsAdmitted()}, each reporting {@code Expecting actual: 401 not to
 * be equal to: 401}, while {@link #theChainIsInstalled()} and
 * {@link #aDirectRequestToTheErrorPathIsStillRefused()} keep passing. That division is what shows the five
 * cases separate the rule from the rules it sits in front of rather than measuring one condition five
 * times. The 401 rather than 403 is itself informative and matches what
 * the account, card and authorization contexts measured: the authentication filters extend
 * {@code OncePerRequestFilter}, whose {@code shouldNotFilterErrorDispatch()} answers true, so the
 * bearer-token filter does not run on an ERROR dispatch at all, and the authorization filter -- which is not
 * a {@code OncePerRequestFilter} and does run -- reaches the rules with no principal.
 *
 * <p>Assumptions: the deployed configuration is INSTANTIATED here and only the two bean methods this slice
 * needs are called, rather than the class being registered. That distinction is what makes a web context
 * admissible in this module at all: {@link SecurityConfig#jwtDecoder} resolves an issuer's discovery
 * document eagerly at construction, so registering the class would reach the network from a unit test --
 * against a host the test profile points somewhere unresolvable on purpose. Calling only
 * {@code filterChain} and {@code jwtAuthenticationConverter} and supplying a substituted decoder exercises
 * the deployed chain with none of that.
 *
 * <p>Assumptions: the chain is added to the entry point explicitly. A chain is a container-level filter in a
 * running service and this entry point installs no filter it is not given, so omitting that step would leave
 * every case below passing for the wrong reason. {@link #theChainIsInstalled()} exists to prove the
 * installation took.
 *
 * <p>Trade-offs: the context enables the MVC infrastructure but mounts no handler, so an admitted request
 * answers 404. That is deliberate: this class asserts which component DECIDED the answer, and a 404 from the
 * dispatcher is a decision the chain did not take. Mounting a controller to obtain a 200 would add a handler
 * whose behaviour the dispatcher classes in {@code com.carddemo.transaction.api} already assert.
 *
 * <p>Assumptions: of the four content elements user-specified Rule 1 enumerates, only Purpose applies to a
 * type declaration -- it accepts no parameters, yields no value and raises nothing -- so the other three are
 * inapplicable rather than omitted.
 */
class SecurityChainDispatchTest {

    /** The instant the refusal bodies are stamped from, so a rendered body is reproducible. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-13T00:00:00.000000Z");

    /** The framework's own error path, which the container re-dispatches to. */
    private static final String ERROR_PATH = "/error";

    /** A representative published path of this context, used to prove the rule is not path-scoped. */
    private static final String BUSINESS_PATH = "/api/v1/transactions";

    /** The context the chain is built in, refreshed once for the class. */
    private static AnnotationConfigWebApplicationContext context;

    /** The entry point every request below is issued through, with the deployed chain installed. */
    private static MockMvc mockMvc;

    /** The substituted token decoder, which answers for the header a caller presents. */
    private static JwtDecoder jwtDecoder;

    /** Refreshes the context once and installs the deployed chain in front of the dispatcher. */
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

    /** Closes the context so the class leaves no refreshed application behind it. */
    @AfterAll
    static void closeSliceContext() {
        if (context != null) {
            context.close();
        }
    }

    /** Clears the substituted decoder so each case starts from no stubbing. */
    @BeforeEach
    void resetSubstitutes() {
        reset(jwtDecoder);
    }

    /**
     * Confirms the chain is actually in front of the dispatcher, so the cases below mean what they say.
     *
     * <p>Assumptions: a published path with no token must be challenged. If the chain were absent this
     * request would reach the dispatcher and answer 404, which is exactly the status an ADMITTED request
     * answers with in this slice -- so without this case an omitted filter would be indistinguishable from a
     * granted dispatch in every other case here.</p>
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
     * particular status. What an error dispatch renders depends on what is mounted at that path, which this
     * slice deliberately does not provide; what matters is that the decision is no longer taken by the
     * security chain.</p>
     *
     * <p>Assumptions: no token is presented. An ERROR dispatch carries whatever the original request
     * carried, and the reachable form of this condition is a rendering failure on a request whose
     * authorization was already decided -- by which time re-deciding it grants nothing and refusing it loses
     * the response the caller was owed.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an error dispatch is not refused as an authorization failure")
    void anErrorDispatchIsNotRefusedAsAnAuthorizationFailure() throws Exception {
        assertNotRefused(mockMvc.perform(get(ERROR_PATH).with(asErrorDispatch()))
                .andReturn().getResponse().getStatus());
    }

    /**
     * Confirms an ERROR dispatch of a MUTATING method is admitted, which is this chain's own hazard.
     *
     * <p>Purpose: this case is the one that distinguishes this context from a chain whose only terminal rule
     * is a catch-all. Four method rules here match {@code /**} and demand the administrator authority, and
     * an ERROR forward keeps the original method -- so a refused POST was re-judged as a mutating call to
     * {@code /error} and answered administrator-or-nothing. A GET-only case would have passed while this
     * condition stood, because a GET falls to a different rule.
     *
     * <p>Assumptions: no token is presented, which is the reachable form: a request refused for its media
     * type or its accept header is refused before authentication matters, and the caller is then owed that
     * refusal rather than a demand for an authority.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an error dispatch of a mutating method is admitted despite the admin method rule")
    void anErrorDispatchOfAMutatingMethodIsAdmitted() throws Exception {
        assertNotRefused(mockMvc.perform(post(ERROR_PATH).with(asErrorDispatch()))
                .andReturn().getResponse().getStatus());
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
        assertNotRefused(mockMvc.perform(get(BUSINESS_PATH).with(asErrorDispatch()))
                .andReturn().getResponse().getStatus());
    }

    /**
     * Confirms a caller addressing the error path DIRECTLY is still refused, so the permit widened nothing.
     *
     * <p>Assumptions: the permit matches the dispatcher TYPE and not the path, and this case is what makes
     * that distinction observable. A path-based permit would let any caller provoke the container's own error
     * body, which is not a shape {@code openapi/transaction-api.yaml} declares for any operation.</p>
     *
     * <p>⚠️ Assumptions: the refusal asserted here is the UNAUTHENTICATED one, and the reason is this
     * chain's terminal rule. Its catch-all admits either business authority, so a caller holding a valid
     * token reaches the dispatcher on that path exactly as it reaches any other -- which is a property of
     * the catch-all and not of this rule. What the permit must not have done is admit an UNAUTHENTICATED
     * caller to that path, and that is what this case measures. Alternatives Considered: presenting both
     * groups and asserting a refusal, which is how the contexts whose chains end in a denying rule assert
     * the same property; it is not available here, and asserting it would have failed for the right reason
     * while describing the wrong rule.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a direct request to the error path is still challenged for authentication")
    void aDirectRequestToTheErrorPathIsStillRefused() throws Exception {
        mockMvc.perform(get(ERROR_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED));
    }

    /**
     * Asserts a status is neither of the two refusals the security chain renders.
     *
     * @param status the status the slice answered with
     */
    private static void assertNotRefused(int status) {
        assertThat(status)
                .as("an error dispatch must not be answered by the security chain")
                .isNotEqualTo(HttpStatus.FORBIDDEN.value())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    /**
     * Marks a request as the container's ERROR dispatch rather than an ordinary one.
     *
     * <p>Assumptions: the dispatcher type is set on the request rather than simulated by addressing the
     * error path, because the rule under test matches the type and a path-addressed request would exercise
     * the rules this class asserts are UNCHANGED.</p>
     *
     * @return a post-processor that re-marks the request; never {@code null}
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor asErrorDispatch() {
        return request -> {
            request.setDispatcherType(DispatcherType.ERROR);
            return request;
        };
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
     * <p>Assumptions: of the four content elements user-specified Rule 1 enumerates, only Purpose applies to
     * a type declaration, so the other three are inapplicable rather than omitted.</p>
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
