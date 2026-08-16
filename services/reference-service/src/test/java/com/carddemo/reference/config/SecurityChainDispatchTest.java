package com.carddemo.reference.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.yaml.snakeyaml.Yaml;

/**
 * Asserts what the installed filter chain does with the container's ERROR dispatch, what it still does with
 * a caller who addresses the error path directly, and what it does with the five documentation paths it
 * grants ahead of its administrator-only method rules.
 *
 * <p><strong>Purpose.</strong> Every other assertion about this context's authorization matrix is made
 * against the rule table {@link SecurityConfig} declares, because a table is addressable without a servlet
 * container and that keeps the assertion uniform across the eight contexts. The rule this class covers is
 * not expressible that way at all: it matches a DISPATCHER TYPE rather than a path or an authority, so the
 * only thing that can witness it is a dispatch. That is why this one class assembles a web context and the
 * rest of the package does not.
 *
 * <p>⚠️ Refactoring Rationale: the condition pinned here was a real refusal, and in this context it was
 * worse than in a chain that merely ends in a denying catch-all. A chain authorizes every dispatcher type
 * unless told otherwise, and the container's ERROR forward keeps the ORIGINAL request METHOD. This chain
 * judges mutating methods by a rule whose pattern is {@code /**} and whose authority is
 * carddemo-admin. So when the framework could not write a response to a POST -- an unsupported media type
 * and an unacceptable representation are both reachable, and both are statuses this surface publishes -- the
 * forward to {@code /error} matched that POST rule and was answered
 * administrator-or-nothing, on a path the caller never addressed. A refused GET reached the denying
 * catch-all instead, so the two halves of the surface lost their refusals to two different rules.
 *
 * <p>Measured: with the dispatcher-type permit removed from this context's chain, exactly the three
 * ERROR-dispatch cases fail -- {@link #anErrorDispatchIsNotRefusedAsAnAuthorizationFailure()},
 * {@link #anErrorDispatchOfAMutatingMethodIsAdmitted()} and
 * {@link #anErrorDispatchToAPublishedPathIsAdmitted()} -- each answering 401, while
 * {@link #theChainIsInstalled()} and {@link #aDirectRequestToTheErrorPathIsStillRefused()} keep passing.
 * That division is what shows those five cases separate the rule from the rules it sits in front of rather
 * than measuring one condition five times. The 401 rather than 403 is itself informative and matches what
 * the account, card and authorization contexts measured: the authentication filters extend
 * {@code OncePerRequestFilter}, whose {@code shouldNotFilterErrorDispatch()} answers true, so the
 * bearer-token filter does not run on an ERROR dispatch at all, and the authorization filter -- which is not
 * a {@code OncePerRequestFilter} and does run -- reaches the rules with no principal.
 *
 * <p>⚠️ Refactoring Rationale: the class now covers a SECOND subject, in the nested group at the foot of
 * it, and the two share this file because they share the one thing that is expensive here -- a refreshed
 * web context with the deployed chain installed in front of a dispatcher. That subject is the documentation
 * grant: five path patterns granted to either business group, declared ahead of the four
 * administrator-only method rules. It was reachable and covered by nothing. Two of its properties are not
 * expressible against the rule table either, for the same reason the dispatcher-type rule is not: whether a
 * multi-segment wildcard admits the nested assets a browser fetches, and which of two rules matching the
 * same request wins, are both answers a dispatch gives and a table does not.
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
 * whose behaviour the dispatcher classes in {@code com.carddemo.reference.api} already assert.
 *
 * <p>Assumptions: of the four content elements user-specified Rule 1 enumerates, only Purpose applies to a
 * type declaration -- it accepts no parameters, yields no value and raises nothing -- so the other three are
 * inapplicable rather than omitted.
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
    private static final String BUSINESS_PATH = "/api/v1/reference/transaction-categories";

    /**
     * The machine-readable document's own path, used as the mutating-method probe.
     *
     * <p>Assumptions: this is the first of the five deployed documentation patterns and it is an exact
     * path rather than a wildcard, so a mutating request to it can only be matched by the documentation
     * grant or by one of the four whole-tree method rules -- which is exactly the ambiguity the ordering
     * case has to resolve.</p>
     */
    private static final String DOCUMENT_PATH = "/v3/api-docs";

    /**
     * A group name the deployed authority converter does not recognise.
     *
     * <p>Assumptions: a token carrying only this decodes successfully and yields NO authority, so its
     * caller is authenticated and unauthorised. That is the only way to reach the forbidden answer on a
     * path the grant covers; an absent token reaches the challenge instead.</p>
     */
    private static final String UNRECOGNISED_GROUP = "carddemo-unrecognised-group";

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
     * body, which is not a shape {@code openapi/reference-api.yaml} declares for any operation.</p>
     *
     * <p>Assumptions: the presented token carries BOTH business groups, so the refusal cannot be attributed
     * to a missing authority. A token with no group would be refused for a second reason and the case would
     * pass without distinguishing the two.</p>
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
     * Holds the documentation grant to what the chain actually does with each of its five patterns.
     *
     * <p>Purpose: this group exists because five path patterns were granted to either business group,
     * ahead of the four administrator-only method rules, and no request test covered any of them. The
     * grant is the only rule in this chain that admits a caller to a path the reference surface does not
     * publish, and its POSITION in the rule order changes the answer for four HTTP methods, so both the
     * grant and its ordering are asserted here against the deployed chain rather than against a table.
     *
     * <p>Assumptions: the deployed pattern list is read REFLECTIVELY and asserted to be exactly the five
     * this group probes, rather than the patterns being restated as this class's own constants. The list
     * is private to the deployed configuration and widening it so a test could read it would relax
     * production visibility for a test's convenience. Reading it is what ties every probe below to the
     * production rule: a pattern added, removed or renamed there fails the first case rather than leaving
     * the probes quietly covering a list that no longer exists.
     *
     * <p>Assumptions: an ADMITTED request answers 404 in this slice, because the slice mounts the chain
     * and nothing else -- no controller, no document, no static asset. That is why admission is asserted
     * as "not either refusal" rather than as a particular status, using the same helper the
     * error-dispatch cases use. What a documentation path SERVES is a property of the springdoc
     * configuration and is asserted from that configuration in the last case here.
     *
     * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
     * parameter, return or exception at-clause; every member below carries its own.
     */
    @Nested
    @DisplayName("on the documentation grant and its position in the rule order")
    class OnTheDocumentationGrant {

        /**
         * The deployed grant covers exactly the five patterns this group probes, in that order.
         *
         * <p>Assumptions: the order is asserted as well as the membership. The list is expanded into the
         * matcher arguments in declaration order, and while Spring Security evaluates the resulting
         * matchers as alternatives within one rule, a reader auditing the chain reads the list as the
         * enumeration of what the grant covers. Asserting the sequence keeps this class's probe list
         * readable against the production list line for line.
         *
         * @throws Exception if the field cannot be read, which a rename or a change of modifier causes and
         *     which must surface as a failure rather than as a silently skipped assertion
         */
        @Test
        @DisplayName("the grant covers exactly the five published documentation patterns")
        void theGrantCoversExactlyTheFivePublishedPatterns() throws Exception {
            assertThat(deployedDocumentationPatterns())
                    .as("every probe in this group is chosen to match one of these patterns, so a"
                            + " change here without a matching probe leaves a pattern uncovered")
                    .containsExactly(
                            "/v3/api-docs", "/v3/api-docs/**", "/reference-api.yaml",
                            "/swagger-ui.html", "/swagger-ui/**");
        }

        /**
         * A documentation path presented with no token at all is challenged, not served.
         *
         * <p>Assumptions: the grant is by AUTHORITY and not openly, so an unauthenticated caller must be
         * challenged on every one of the five patterns. This is the case that separates "granted to both
         * groups" from "public": a rule written with a permit instead of an authority manager would pass
         * every other case in this group and fail only this one.
         *
         * @param path a concrete path matching one of the five deployed patterns
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest
        @MethodSource(
                "com.carddemo.reference.config.SecurityChainDispatchTest#documentationProbePaths")
        @DisplayName("a documentation path with no token is challenged rather than served")
        void aDocumentationPathWithNoTokenIsChallenged(String path) throws Exception {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code")
                            .value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED));
        }

        /**
         * A documentation path presented with a token carrying neither business group is refused.
         *
         * <p>Assumptions: the token DECODES successfully and simply carries a group name the authority
         * converter does not recognise, so the caller is authenticated and unauthorised rather than
         * unauthenticated. That is the distinction between this case and the one above, and it is why the
         * expected status is 403 with the forbidden code rather than 401.
         *
         * @param path a concrete path matching one of the five deployed patterns
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest
        @MethodSource(
                "com.carddemo.reference.config.SecurityChainDispatchTest#documentationProbePaths")
        @DisplayName("a documentation path with neither business group is refused")
        void aDocumentationPathWithNeitherGroupIsRefused(String path) throws Exception {
            stubDecoderWithGroups(List.of(UNRECOGNISED_GROUP));

            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_FORBIDDEN));
        }

        /**
         * Either business group is admitted to every documentation path, the administrator included.
         *
         * <p>Assumptions: BOTH groups are driven for every pattern rather than one of them, because the
         * grant names both and a rule naming only the administrator would satisfy an assertion made with
         * an administrator token alone. The administrator half also matters in its own right: the four
         * method rules beneath this grant are administrator-only, so a reader could reasonably expect the
         * administrator to be the one admitted here and the ordinary user to be refused, and this case
         * records that the read half of the contract admits both.
         *
         * @param path a concrete path matching one of the five deployed patterns
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest
        @MethodSource(
                "com.carddemo.reference.config.SecurityChainDispatchTest#documentationProbePaths")
        @DisplayName("both business groups are admitted to every documentation path")
        void bothBusinessGroupsAreAdmittedToADocumentationPath(String path) throws Exception {
            stubDecoderWithGroups(List.of(JwtRoleConverter.USER_AUTHORITY));
            assertNotRefused(mockMvc
                    .perform(get(path).header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                    .andReturn()
                    .getResponse()
                    .getStatus());

            stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));
            assertNotRefused(mockMvc
                    .perform(get(path).header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                    .andReturn()
                    .getResponse()
                    .getStatus());
        }

        /**
         * The two wildcard patterns cover nested assets and the interactive page's own configuration.
         *
         * <p>Purpose: two of the five patterns end in a multi-segment wildcard, and the paths a browser
         * actually fetches under them are nested rather than flat -- the page requests its own
         * configuration document, its stylesheet and its script bundles. A rule written with a
         * single-segment wildcard would satisfy every other case in this group, because each of those
         * probes one flat path, and would refuse exactly the requests a real page makes.
         *
         * <p>Assumptions: the paths below are two and three segments deep beneath their pattern's prefix,
         * so both the multi-segment property and the ordinary one-segment case are exercised. They are
         * driven with an ordinary user token, which is the weaker of the two admitted authorities, and
         * additionally without a token, so the nesting is shown to inherit the grant rather than to
         * escape the chain altogether -- an escape would answer 404 for a reason that has nothing to do
         * with the rule.
         *
         * @param nested a path nested beneath one of the two wildcard patterns
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest
        @ValueSource(strings = {
            "/v3/api-docs/swagger-config",
            "/v3/api-docs/reference/transaction-types",
            "/swagger-ui/index.html",
            "/swagger-ui/assets/swagger-ui.css"
        })
        @DisplayName("nested documentation assets inherit the grant and are still challenged unauthenticated")
        void nestedDocumentationAssetsInheritTheGrant(String nested) throws Exception {
            mockMvc.perform(get(nested))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code")
                            .value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED));

            stubDecoderWithGroups(List.of(JwtRoleConverter.USER_AUTHORITY));
            assertNotRefused(mockMvc
                    .perform(get(nested).header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                    .andReturn()
                    .getResponse()
                    .getStatus());
        }

        /**
         * A mutating request to a documentation path is judged by the grant, not by the method rules.
         *
         * <p>Purpose: this is the ORDERING property, and it is the reason the deployed configuration
         * records at length that this grant's position is load-bearing rather than immaterial. The four
         * method rules beneath it pair a method with the whole tree, so they reach documentation paths
         * too; declared after them, a mutating request to a documentation path would be judged
         * administrator-or-nothing while a read of the same path was judged by the grant. Declared where
         * it is, one rule answers for those paths whatever method arrives.
         *
         * <p>Assumptions: an ordinary user token is used, which is what makes the case decide the
         * ordering. An administrator token satisfies both the grant and the method rules, so it would
         * pass whichever rule matched and the case would establish nothing.
         *
         * <p>Assumptions: the same four methods are driven against a published business path as the
         * CONTROL, and it is what keeps the case from passing vacuously. Without it a chain that had
         * simply stopped judging mutating methods at all would satisfy the first half; the control shows
         * the administrator-only rules are still in force where they are meant to be.
         *
         * @throws Exception if a request cannot be performed
         */
        @Test
        @DisplayName("a mutating request to a documentation path is judged by the grant, not the method rules")
        void aMutatingRequestToADocumentationPathIsJudgedByTheGrant() throws Exception {
            stubDecoderWithGroups(List.of(JwtRoleConverter.USER_AUTHORITY));

            for (MockHttpServletRequestBuilder mutating : List.of(
                    post(DOCUMENT_PATH), put(DOCUMENT_PATH),
                    patch(DOCUMENT_PATH), delete(DOCUMENT_PATH))) {
                assertNotRefused(mockMvc
                        .perform(mutating.header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                        .andReturn()
                        .getResponse()
                        .getStatus());
            }

            for (MockHttpServletRequestBuilder mutating : List.of(
                    post(BUSINESS_PATH), put(BUSINESS_PATH),
                    patch(BUSINESS_PATH), delete(BUSINESS_PATH))) {
                mockMvc.perform(mutating.header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_FORBIDDEN));
            }
        }

        /**
         * Production removes the interactive page through configuration, and the chain rule is unchanged.
         *
         * <p>Purpose: the two halves of the production posture are easy to conflate, and conflating them
         * would leave one of them unasserted. The chain grants the five patterns identically under every
         * profile -- the rule is keyed by path and authority and reads no profile at all -- while the
         * production overlay switches the interactive page off with
         * {@code springdoc.swagger-ui.enabled: false} and deliberately leaves the machine-readable
         * document enabled. So in production an admitted caller reaching the page path is answered by the
         * page's ABSENCE rather than by an authorization refusal, which is a different diagnosis from the
         * one an operator would reach if the chain had been narrowed.
         *
         * <p>Assumptions: the two configuration files are parsed rather than the two values being
         * restated here, so a profile that switched the document off alongside the page, or switched the
         * page back on, fails this case. The base file is read for the document setting because that is
         * where it is declared; the production overlay is read for the page setting because that is the
         * one key the overlay adds, which its own comment block records as a bounded exception.
         *
         * @throws Exception if either configuration file cannot be read
         */
        @Test
        @DisplayName("production disables the page by configuration while the chain grant is unchanged")
        void productionDisablesThePageByConfigurationAndNotByTheChain() throws Exception {
            assertThat(yamlValueAt("src/main/resources/application.yml",
                    "springdoc", "api-docs", "enabled"))
                    .as("the machine-readable document stays served; it is the contract a client"
                            + " generator reads and is not the interactive form")
                    .isEqualTo(Boolean.TRUE);
            assertThat(yamlValueAt("src/main/resources/application-prod.yml",
                    "springdoc", "swagger-ui", "enabled"))
                    .as("production removes the page that composes and sends requests")
                    .isEqualTo(Boolean.FALSE);

            // WHY : Assumptions: the chain is then driven at the page path under an ordinary token, in
            //       the same slice, to show the GRANT is untouched by that configuration. The two
            //       assertions together are what separate "the page is gone" from "the caller was
            //       refused" -- an operator reading a 403 would look at authorities, and an operator
            //       reading a 404 would look at whether the page is enabled, so which one production
            //       answers with decides where the next hour is spent.
            stubDecoderWithGroups(List.of(JwtRoleConverter.USER_AUTHORITY));
            assertNotRefused(mockMvc
                    .perform(get("/swagger-ui.html")
                            .header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                    .andReturn()
                    .getResponse()
                    .getStatus());
        }
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
     * Reads the deployed documentation pattern list out of the configuration that declares it.
     *
     * <p>Assumptions: read reflectively because the field is private to the deployed configuration.
     * Widening it so a test could read it would relax production visibility for a test's convenience,
     * which is the wrong direction of the two; reading it here keeps the probe list below tied to the
     * production rule without changing what production publishes.</p>
     *
     * @return the patterns the deployed grant covers, in declaration order; never {@code null}
     * @throws ReflectiveOperationException if the field is absent or no longer a list of strings, which a
     *     rename or a restructuring causes and which must surface as a failure
     */
    @SuppressWarnings("unchecked")
    private static List<String> deployedDocumentationPatterns() throws ReflectiveOperationException {
        java.lang.reflect.Field declared =
                SecurityConfig.class.getDeclaredField("DOCUMENTATION_PATHS");
        declared.setAccessible(true);
        return (List<String>) declared.get(null);
    }

    /**
     * Supplies one concrete path per deployed documentation pattern.
     *
     * <p>Assumptions: five probes for five patterns, each chosen to match exactly one of them -- the three
     * exact patterns as themselves, and one flat child for each of the two wildcard patterns. The deeper
     * nesting the two wildcards also admit is driven by its own case, because a flat child cannot
     * distinguish a multi-segment wildcard from a single-segment one.</p>
     *
     * @return the probe paths, in the same order as the deployed pattern list; never {@code null}
     */
    static List<String> documentationProbePaths() {
        return List.of(
                "/v3/api-docs", "/v3/api-docs/swagger-config", "/reference-api.yaml",
                "/swagger-ui.html", "/swagger-ui/index.html");
    }

    /**
     * Reads one scalar out of a configuration file by walking a path of mapping keys.
     *
     * <p>Assumptions: the file is parsed as YAML rather than searched as text, so an assertion cannot be
     * satisfied by a key of the same name under a different parent -- which is precisely the mistake a
     * text search makes on a file that declares the same leaf under two nodes.</p>
     *
     * @param file the module-relative path of the configuration file to read
     * @param keys the mapping keys to walk, outermost first
     * @return the scalar found at that path, or {@code null} if any key along the way is absent
     * @throws java.io.IOException if the file cannot be read, which must surface rather than yielding an
     *     absent value that would read as a deliberate omission
     */
    private static Object yamlValueAt(String file, String... keys) throws java.io.IOException {
        Object current;
        try (java.io.InputStream source = java.nio.file.Files.newInputStream(
                java.nio.file.Path.of(file))) {
            current = new Yaml().load(source);
        }
        for (String key : keys) {
            if (!(current instanceof java.util.Map<?, ?> mapping)) {
                return null;
            }
            current = mapping.get(key);
        }
        return current;
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
    // WHY : Refactoring Rationale: this is a @TestConfiguration and not a plain @Configuration, and the
    //       difference is load-bearing rather than stylistic. This module now carries the Spring Boot MVC
    //       test slice, and a slice component-scans from the application package -- which is this package's
    //       own root -- while its include filter admits any WebMvcConfigurer it finds. A plain
    //       @Configuration declared here therefore leaked into every sibling slice's context and collided
    //       with the beans that slice declared for itself; the first symptom was a duplicate clock
    //       definition reported against a class in a different file from the failing test.
    //       @TestConfiguration carries @TestComponent, which the test type-exclude filter removes from
    //       component scanning, while explicit registration and explicit import both still work -- which is
    //       how the case that needs this class obtains it.
    @TestConfiguration(proxyBeanMethods = false)
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
