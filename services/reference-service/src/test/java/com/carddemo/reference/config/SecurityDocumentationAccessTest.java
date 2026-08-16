// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/config/SecurityDocumentationAccessTest.java
// -----------------------------------------------------------------------------
// WHAT:
//      Drives the DEPLOYED filter chain at the five documentation patterns and at the write
//      routes that sit behind the four mutating-method rules, and establishes four things a
//      rule-table assertion cannot: that an anonymous documentation request is challenged,
//      that either business group reaches every documentation address, that a mutating
//      request to a documentation path is judged by the documentation rule rather than by
//      the administrator-only method rules declared after it, and that the same mutating
//      method OFF those paths is still administrator-only.
//
// WHY (non-obvious design decisions):
//   (1) Refactoring Rationale: SecurityConfig's documentation rule is load-bearing and was
//       entirely unexercised. It is declared BEFORE four rules that pair a mutating method
//       with the whole tree, and its own comment says that ordering is what makes one rule
//       answer for those paths whatever method arrives. Ordering is a property of the
//       assembled chain, not of any one rule, so reading the rule table cannot witness it:
//       a table read confirms the rules exist and says nothing about which one a request
//       reaches first. Moving the documentation rule below the method rules -- the exact
//       regression this class exists to catch -- leaves every rule present and every
//       table-shaped assertion green.
//   (2) Assumptions: this class assembles a web context in the same shape the sibling
//       SecurityChainDispatchTest fixes, instantiating SecurityConfig and calling only
//       filterChain and jwtAuthenticationConverter rather than registering the class.
//       SecurityConfig.jwtDecoder resolves an issuer's discovery document eagerly at
//       construction, so registering the class would reach the network from a unit test
//       against a host the test profile points somewhere unresolvable on purpose.
//   (3) Trade-offs: the context mounts no handler, so an ADMITTED request answers 404.
//       Every admission below is therefore asserted as "not either refusal" rather than as a
//       200, and theChainIsInstalled exists so that a 404 from the dispatcher is
//       distinguishable from a chain that was never installed. Mounting controllers to
//       obtain a 200 would drag six handlers and their collaborators into a class about
//       admittance, and the answers those handlers compose are already asserted by the
//       dispatcher classes in com.carddemo.reference.api.
//   (4) Alternatives Considered: asserting the documentation grant once, at /v3/api-docs
//       alone. Rejected because three of the five patterns exist precisely to cover
//       subtrees -- the generated document's settings endpoint and the browser view's webjar
//       assets -- and a single-address case would pass while a subtree pattern was dropped,
//       leaving the view loading and then failing to render. Each pattern is exercised at an
//       address it and only it matches.
//   (5) Assumptions: the authority names and the claim name come from the shared kernel's
//       compiled constants rather than from literals here, so a rename reaches these cases
//       instead of leaving them asserting against a name nothing reads.
//
// Measured: with the documentation rule relocated below the four mutating-method rules --
//      every rule still present, only the order changed -- EXACTLY ONE of the nine cases
//      fails, aMutatingDocumentationRequestIsJudgedByTheDocumentationRule, and the other
//      eight keep passing. That division is the evidence that the ordering property is
//      asserted by one case rather than measured nine times, and it is also the evidence
//      that no rule-table assertion could have caught the relocation.
// =============================================================================

package com.carddemo.reference.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.security.JwtRoleConverter;
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
 * Holds the deployed chain's documentation grant and its mutating-method rules to the order they
 * are declared in.
 *
 * <p>Purpose: exercises {@code SecurityConfig}'s five documentation patterns and the four
 * whole-tree mutating-method rules through an installed filter chain, so that the effect of their
 * relative ORDER is asserted rather than assumed.</p>
 *
 * <p>Assumptions: of the four content elements user-specified Rule 1 enumerates, only Purpose
 * applies to a type declaration -- it accepts no parameters, yields no value and raises nothing --
 * so the other three are inapplicable rather than omitted.</p>
 */
class SecurityDocumentationAccessTest {

    /** The instant the refusal bodies are stamped from, so a rendered body is reproducible. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-13T00:00:00.000000Z");

    /** The token value a caller presents, which the substituted decoder answers for. */
    private static final String PRESENTED_TOKEN = "presented-token-value";

    /** The subject the substituted decoder puts in the token it answers with. */
    private static final String TOKEN_SUBJECT = "11111111-2222-3333-4444-555555555555";

    /**
     * The generated document's own address, matched by the first documentation pattern alone.
     *
     * <p>Assumptions: this address is matched by {@code /v3/api-docs} exactly and not by
     * {@code /v3/api-docs/**}, which requires a further segment. Exercising it is what covers the
     * first pattern rather than its subtree.
     */
    private static final String GENERATED_DOCUMENT = "/v3/api-docs";

    /**
     * The settings endpoint the browser view fetches, matched only by the subtree pattern.
     *
     * <p>Assumptions: this is the address named in {@code DOCUMENTATION_PATHS}' own rationale as
     * the reason the subtree pattern exists, so a case addressing it is the one that fails if the
     * subtree pattern is dropped while the exact one is kept.
     */
    private static final String GENERATED_DOCUMENT_SETTINGS = "/v3/api-docs/swagger-config";

    /** The committed contract this service serves from the packaged classpath. */
    private static final String COMMITTED_CONTRACT = "/reference-api.yaml";

    /** The browser view's entry page, matched by its own exact pattern. */
    private static final String VIEW_PAGE = "/swagger-ui.html";

    /** An asset inside the view's webjar, matched only by the view's subtree pattern. */
    private static final String VIEW_ASSET = "/swagger-ui/swagger-initializer.js";

    /**
     * Every documentation address, one per pattern the chain grants.
     *
     * <p>Assumptions: five addresses for five patterns, chosen so that each address is matched by
     * exactly one of them. A list of three addresses covering three patterns would leave the two
     * subtree grants unexercised, which is the half of the rule that a dropped pattern removes
     * first.
     */
    private static final List<String> DOCUMENTATION_ADDRESSES = List.of(
            GENERATED_DOCUMENT, GENERATED_DOCUMENT_SETTINGS, COMMITTED_CONTRACT, VIEW_PAGE,
            VIEW_ASSET);

    /** A published business collection, used as the off-documentation comparison path. */
    private static final String CATEGORY_COLLECTION = "/api/v1/reference/transaction-categories";

    /** A published business item, used to reach the item-scoped write rules. */
    private static final String CATEGORY_ITEM = CATEGORY_COLLECTION + "/01/0001";

    /** The maintenance batch address, whose only method is a mutating one. */
    private static final String MAINTENANCE_ACTIONS = "/api/v1/reference/maintenance-actions";

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
     * Confirms the chain is in front of the dispatcher, so an admitted 404 means something.
     *
     * <p>Assumptions: an admitted request in this slice answers 404 because no handler is mounted,
     * which is the same status an unfiltered request would answer. Without this case an omitted
     * filter would be indistinguishable from a granted dispatch in every admission case here.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the deployed chain is installed in front of the dispatcher")
    void theChainIsInstalled() throws Exception {
        mockMvc.perform(get(CATEGORY_COLLECTION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED));
    }

    /** The grant itself: who reaches the five documentation addresses, and who does not. */
    @Nested
    @DisplayName("on the documentation grant")
    class OnTheDocumentationGrant {

        /**
         * Every documentation address challenges a caller that presents no token.
         *
         * <p>Assumptions: the rule grants by AUTHORITY rather than openly, which is the decision
         * recorded on {@code DOCUMENTATION_PATHS}, so the anonymous answer is a challenge and not a
         * document. This case is what would fail if the rule were relaxed to permit everything --
         * the change most likely to be made for convenience, and the one that would publish every
         * field width and authority requirement of this service to anything that can reach the
         * listener.</p>
         *
         * @throws Exception if a request cannot be performed
         */
        @Test
        @DisplayName("an anonymous request to any documentation address is challenged")
        void anAnonymousDocumentationRequestIsChallenged() throws Exception {
            for (String address : DOCUMENTATION_ADDRESSES) {
                mockMvc.perform(get(address))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code")
                                .value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED));
            }
        }

        /**
         * An ordinary user reaches every documentation address.
         *
         * <p>Assumptions: the token carries the user authority ALONE. Presenting both groups would
         * let this case pass on the administrator half and would not distinguish the published
         * decision -- that the description is the contract an ordinary integrator writes a client
         * against, so refusing it to the group permitted every read would make the document harder
         * to obtain than the rows it describes.</p>
         *
         * @throws Exception if a request cannot be performed
         */
        @Test
        @DisplayName("an ordinary user reaches every documentation address")
        void anOrdinaryUserReachesEveryDocumentationAddress() throws Exception {
            stubDecoderWithGroups(List.of(JwtRoleConverter.USER_AUTHORITY));

            for (String address : DOCUMENTATION_ADDRESSES) {
                assertAdmitted(address, statusOf(get(address).header(HttpHeaders.AUTHORIZATION,
                        bearerHeader())));
            }
        }

        /**
         * An administrator reaches every documentation address as well.
         *
         * <p>Assumptions: the token carries the administrator authority ALONE, which is the case
         * that fails if the grant is ever narrowed to the user group by name instead of accepting
         * either. Narrowing it that way would refuse the document to the operator who deploys the
         * service.</p>
         *
         * @throws Exception if a request cannot be performed
         */
        @Test
        @DisplayName("an administrator reaches every documentation address")
        void anAdministratorReachesEveryDocumentationAddress() throws Exception {
            stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));

            for (String address : DOCUMENTATION_ADDRESSES) {
                assertAdmitted(address, statusOf(get(address).header(HttpHeaders.AUTHORIZATION,
                        bearerHeader())));
            }
        }

        /**
         * A valid token carrying neither business group is refused the documentation.
         *
         * <p>Assumptions: this is the half of the grant an admission case cannot reach. The rule
         * accepts either business group, so a token that is perfectly valid and carries neither
         * must be refused -- and refused as a FORBIDDEN rather than challenged, because it
         * authenticated. A grant relaxed to {@code authenticated()} would admit exactly this token
         * and would keep both admission cases above green.</p>
         *
         * @throws Exception if a request cannot be performed
         */
        @Test
        @DisplayName("a token carrying neither business group is refused the documentation")
        void aTokenCarryingNeitherGroupIsRefusedTheDocumentation() throws Exception {
            stubDecoderWithGroups(List.of("some-unrelated-group"));

            for (String address : DOCUMENTATION_ADDRESSES) {
                mockMvc.perform(get(address).header(HttpHeaders.AUTHORIZATION, bearerHeader()))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code")
                                .value(GlobalExceptionHandler.CODE_FORBIDDEN));
            }
        }
    }

    /** The ordering property: which rule answers a mutating request, on and off the documentation. */
    @Nested
    @DisplayName("on the order the documentation rule is declared in")
    class OnTheDeclaredOrder {

        /**
         * A mutating request to a documentation path is answered by the documentation rule.
         *
         * <p>Purpose: this is the case that witnesses ORDER, and it is the reason this class exists
         * rather than a table assertion. Four rules pair a mutating method with {@code /**} and
         * demand the administrator authority; the documentation rule is declared before them and
         * grants either group whatever method arrives. So an ordinary user's POST to a
         * documentation address must NOT be refused. Moving the documentation rule below the four
         * would leave every rule present, every table-shaped assertion green, and this case the
         * only failure.</p>
         *
         * <p>Assumptions: the assertion is that the answer is not a refusal rather than that it is
         * any particular status. What a POST to a documentation address renders is decided by what
         * is mounted there, which this slice deliberately does not provide; what matters is which
         * component decided.</p>
         *
         * <p>Trade-offs: the behaviour asserted here is the INTENDED one and not necessarily the
         * useful one -- nothing serves a POST at these addresses, so the request goes on to answer
         * 404 or 405. Asserting it anyway is what pins the ordering, and the rule's own rationale
         * states this outcome deliberately: one rule answers for those paths whatever method
         * arrives, which is also what makes the outcome identical under both profiles.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an ordinary user's mutating request to a documentation path is not refused")
        void aMutatingDocumentationRequestIsJudgedByTheDocumentationRule() throws Exception {
            stubDecoderWithGroups(List.of(JwtRoleConverter.USER_AUTHORITY));

            assertAdmitted(GENERATED_DOCUMENT, statusOf(post(GENERATED_DOCUMENT)
                    .header(HttpHeaders.AUTHORIZATION, bearerHeader())));
            assertAdmitted(VIEW_ASSET, statusOf(delete(VIEW_ASSET)
                    .header(HttpHeaders.AUTHORIZATION, bearerHeader())));
        }

        /**
         * The same mutating methods OFF the documentation paths are still administrator-only.
         *
         * <p>Purpose: this is the other half of the ordering property, and without it the case
         * above is satisfiable by deleting the four method rules altogether. Together the two
         * establish that the documentation grant is scoped to the documentation patterns and did
         * not widen the mutating surface.</p>
         *
         * <p>Assumptions: all four mutating methods are exercised, at the three published write
         * addresses this context serves -- the category collection for the create, the category
         * item for the replace and the delete, and the maintenance batch for its own create. A case
         * covering POST alone would pass while the PUT, PATCH or DELETE rule was dropped, and the
         * category item's replace and delete are exactly the two operations whose authority was
         * previously unexercised anywhere.</p>
         *
         * @throws Exception if a request cannot be performed
         */
        @Test
        @DisplayName("a user-only token is refused every mutating method off the documentation")
        void aMutatingBusinessRequestIsStillAdministratorOnly() throws Exception {
            stubDecoderWithGroups(List.of(JwtRoleConverter.USER_AUTHORITY));

            assertRefusedAsForbidden(statusOf(post(CATEGORY_COLLECTION)
                    .header(HttpHeaders.AUTHORIZATION, bearerHeader())));
            assertRefusedAsForbidden(statusOf(put(CATEGORY_ITEM)
                    .header(HttpHeaders.AUTHORIZATION, bearerHeader())));
            assertRefusedAsForbidden(statusOf(delete(CATEGORY_ITEM)
                    .header(HttpHeaders.AUTHORIZATION, bearerHeader())));
            assertRefusedAsForbidden(statusOf(post(MAINTENANCE_ACTIONS)
                    .header(HttpHeaders.AUTHORIZATION, bearerHeader())));
        }

        /**
         * An administrator reaches every mutating method off the documentation paths.
         *
         * <p>Assumptions: this is what shows the refusal above is the authority rule and not an
         * unmounted address or a rejected body. The same four requests, differing only in the group
         * the token carries, must stop being refused -- so the case pins the rule to the authority
         * rather than to anything else about the request.</p>
         *
         * @throws Exception if a request cannot be performed
         */
        @Test
        @DisplayName("an administrator reaches every mutating method off the documentation")
        void anAdministratorReachesEveryMutatingBusinessRequest() throws Exception {
            stubDecoderWithGroups(List.of(JwtRoleConverter.ADMIN_AUTHORITY));

            assertAdmitted(CATEGORY_COLLECTION, statusOf(post(CATEGORY_COLLECTION)
                    .header(HttpHeaders.AUTHORIZATION, bearerHeader())));
            assertAdmitted(CATEGORY_ITEM, statusOf(put(CATEGORY_ITEM)
                    .header(HttpHeaders.AUTHORIZATION, bearerHeader())));
            assertAdmitted(CATEGORY_ITEM, statusOf(delete(CATEGORY_ITEM)
                    .header(HttpHeaders.AUTHORIZATION, bearerHeader())));
            assertAdmitted(MAINTENANCE_ACTIONS, statusOf(post(MAINTENANCE_ACTIONS)
                    .header(HttpHeaders.AUTHORIZATION, bearerHeader())));
        }

        /**
         * An anonymous mutating request to a write address is challenged, not merely forbidden.
         *
         * <p>Assumptions: the distinction matters to a client, which must be told to obtain a token
         * rather than told its token is insufficient. A chain whose write rules were reached before
         * authentication would answer forbidden here, and a caller acting on that would look for a
         * missing authority it never had.</p>
         *
         * @throws Exception if a request cannot be performed
         */
        @Test
        @DisplayName("an anonymous mutating request to a write address is challenged")
        void anAnonymousMutatingRequestIsChallenged() throws Exception {
            mockMvc.perform(put(CATEGORY_ITEM))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code")
                            .value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED));
            mockMvc.perform(delete(CATEGORY_ITEM))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code")
                            .value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED));
            mockMvc.perform(post(MAINTENANCE_ACTIONS))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code")
                            .value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED));
        }
    }

    /**
     * Performs one prepared request and answers the status the slice rendered.
     *
     * @param request the request to issue; must not be {@code null}
     * @return the HTTP status the slice answered with
     * @throws Exception if the request cannot be performed
     */
    private static int statusOf(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    /**
     * Asserts a status is neither of the two refusals the security chain renders.
     *
     * <p>Assumptions: admission is asserted negatively because this slice mounts no handler, so an
     * admitted request answers 404. Naming the address in the failure description is what makes a
     * loop over five patterns report WHICH pattern lost its grant.</p>
     *
     * @param address the address the request was issued to, named in the failure description
     * @param status the status the slice answered with
     */
    private static void assertAdmitted(String address, int status) {
        assertThat(status)
                .as("the chain must not refuse %s", address)
                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value())
                .isNotEqualTo(HttpStatus.FORBIDDEN.value());
    }

    /**
     * Asserts a status is the authorization refusal rather than the authentication challenge.
     *
     * <p>Assumptions: forbidden and not unauthorized, because the caller presented a valid token
     * and the refusal is about the authority it carries. Accepting either would let a chain that
     * failed to authenticate a good token pass as a chain that judged it.</p>
     *
     * @param status the status the slice answered with
     */
    private static void assertRefusedAsForbidden(int status) {
        assertThat(status)
                .as("a valid token missing the administrator authority is refused, not challenged")
                .isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    /**
     * Stubs the substituted decoder to answer with a token carrying the given group names.
     *
     * <p>Assumptions: the claim name comes from the shared converter's own constant rather than a
     * literal here, so a rename reaches this stub instead of leaving it asserting against a name the
     * converter no longer reads.</p>
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
     * Wires the smallest context that can hold the deployed chain.
     *
     * <p>Purpose: supplies a fixed clock, a substituted decoder, the deployed authority converter
     * and the deployed chain itself, so the cases above exercise a deployment's rules rather than a
     * copy of them.</p>
     *
     * <p>Assumptions: of the four content elements user-specified Rule 1 enumerates, only Purpose
     * applies to a type declaration, so the other three are inapplicable rather than omitted.</p>
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
