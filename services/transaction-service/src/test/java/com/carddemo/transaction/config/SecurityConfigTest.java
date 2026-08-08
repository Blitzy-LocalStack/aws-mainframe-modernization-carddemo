package com.carddemo.transaction.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.common.security.CognitoAccessTokenValidator;
import com.carddemo.common.security.JwtRoleConverter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.util.AntPathMatcher;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies that {@link SecurityConfig} refuses a token carrying no recognised group, keeps the health
 * probe open, and confines the management namespace to the task-local addresses.
 *
 * <p>Refactoring Rationale: this summary said the namespace was held "to the operator authority", which
 * was wrong twice over -- the namespace was matched by no rule at all, and the rule the specific scrape
 * endpoint does carry grants by network position rather than by any authority. Both halves are now
 * asserted rather than described.</p>
 *
 * <p>Assumptions: tokens are assembled in memory because signature, issuer and time validation all happen
 * before the authority conversion this class exercises. Each assertion therefore controls only the claim
 * shape whose authorization outcome it asserts.</p>
 *
 * <p>Assumptions: the decision object under test is the one the chain installs, obtained from
 * {@link SecurityConfig#businessAccess()}, rather than a manager this test constructs from the same two
 * names. Constructing one here would make the test agree with itself while the rule narrowed or
 * widened.</p>
 */
class SecurityConfigTest {

    /** The path matcher whose semantics Spring Security's string request matchers follow. */
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** A representative request path under this context's published prefix. */
    private static final String BUSINESS_PATH = "/api/v1/transactions";

    /**
     * Confirms the probe path stays open while the rest of the management namespace does not.
     *
     * <p>Assumptions: both the aggregate path and a probe group are asserted, because enabling the probe
     * groups publishes {@code /actuator/health/readiness} and {@code /actuator/health/liveness}
     * alongside {@code /actuator/health}, and the pattern has to admit all three whether or not any of
     * them is polled today. The two current consumers -- this service's container health check and the
     * load balancer's target group -- both poll the AGGREGATE path only, and this module's
     * {@code application.yml} records that the groups exist so a future orchestrator check can
     * distinguish "started but not ready" from "failed". Refactoring Rationale: this paragraph read as
     * though a check already polled the probe paths, which no configuration in this repository sets up;
     * the reason to assert them is that they are published and unauthenticated, not that something
     * calls them.</p>
     */
    @Test
    @DisplayName("the health group is open and the wider management namespace is not")
    void healthGroupIsOpenAndTheWiderManagementNamespaceIsNot() {
        assertThat(MATCHER.match(SecurityConfig.HEALTH_PATH, "/actuator/health")).isTrue();
        assertThat(MATCHER.match(SecurityConfig.HEALTH_PATH, "/actuator/health/readiness")).isTrue();

        for (String managed : List.of("/actuator/prometheus")) {
            assertThat(MATCHER.match(SecurityConfig.HEALTH_PATH, managed))
                    .as("%s must not be reachable through the credential-free health rule", managed)
                    .isFalse();
            assertThat(MATCHER.match(SecurityConfig.MANAGEMENT_PATH, managed))
                    .as("%s must be covered by the operator rule", managed)
                    .isTrue();
        }
    }

    /**
     * Confirms the operator rule cannot reach a business route.
     *
     * <p>Assumptions: a management pattern that matched a business path would not fail open, it would fail
     * CLOSED -- every ordinary user would lose the route. That is a functional regression rather than a
     * security one, so it needs an assertion of its own: the security assertions above would still
     * pass.</p>
     */
    @Test
    @DisplayName("the operator rule matches only the actuator namespace")
    void operatorRuleMatchesOnlyTheActuatorNamespace() {
        assertThat(MATCHER.match(SecurityConfig.MANAGEMENT_PATH, BUSINESS_PATH))
                .as("%s must not be swept into the operator rule", BUSINESS_PATH)
                .isFalse();
        assertThat(SecurityConfig.MANAGEMENT_PATH)
                .as("the operator rule must be scoped to the management namespace")
                .startsWith("/actuator");
    }

    /**
     * Confirms the catch-all admits exactly the two closed groups and nothing else.
     */
    @Test
    @DisplayName("the catch-all authority set is the two closed groups")
    void catchAllAuthoritySetIsTheTwoClosedGroups() {
        assertThat(SecurityConfig.BUSINESS_AUTHORITIES)
                .containsExactly(JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);
    }

    /**
     * Confirms the chain matches the whole management namespace, not only the endpoint the base exposure
     * list names.
     *
     * <p>Assumptions: the list is read from the configuration rather than restated here, and its LAST
     * entry is asserted to be the namespace. The defect this guards is precise: the namespace constant
     * existed while no rule matched it, so a management endpoint a profile published was authorized by
     * the business catch-all. A test naming only the scrape endpoint would have passed throughout.</p>
     */
    @Test
    @DisplayName("the operator rule the chain installs covers the whole management namespace")
    void theOperatorRuleCoversTheWholeManagementNamespace() {
        assertThat(SecurityConfig.operatorPaths())
                .as("the chain is built from this list, so a missing entry is a missing rule")
                .containsExactly(SecurityConfig.METRIC_SCRAPE_PATH, SecurityConfig.MANAGEMENT_PATH);
        assertThat(SecurityConfig.operatorPaths())
                .last()
                .as("the namespace is the backstop, so it must be present and must be last")
                .isEqualTo(SecurityConfig.MANAGEMENT_PATH);
        assertThat(SecurityConfig.operatorPaths())
                .allSatisfy(path -> assertThat(path)
                        .as("an operator rule reaching outside /actuator would take a business route"
                                + " away from every ordinary user")
                        .startsWith("/actuator"));
    }

    /**
     * Confirms every management endpoint the development profile publishes is decided by the health rule
     * or by an operator rule, and never by the business catch-all.
     *
     * <p>Assumptions: the exposure list is READ from {@code application-dev.yml} rather than restated,
     * because that file is where the surface grows. It publishes {@code flyway} and {@code threaddump}
     * beyond the base list, and both reach a real handler -- the migration history of the ledger schema,
     * and a full stack dump of the running task.</p>
     *
     * <p>Assumptions: the second assertion is what stops this test being vacuous. It requires at least
     * one exposed endpoint to be covered ONLY by the namespace entry, which proves that entry is
     * load-bearing rather than decorative: without it those ids would be granted to both business
     * groups, meaning every ordinary user of this context.</p>
     */
    @Test
    @DisplayName("every endpoint the dev profile publishes is covered by the health or operator rules")
    void everyExposedManagementEndpointIsCoveredByAHealthOrOperatorRule() {
        List<String> exposed = exposedEndpointIds();
        assertThat(exposed)
                .as("the profile must still publish the diagnostic ids this assertion exists for")
                .contains("health", "flyway", "threaddump");

        List<String> uncovered = new ArrayList<>();
        List<String> namespaceOnly = new ArrayList<>();
        for (String id : exposed) {
            String path = "/actuator/" + id;
            if (MATCHER.match(SecurityConfig.HEALTH_PATH, path)
                    || MATCHER.match(SecurityConfig.METRIC_SCRAPE_PATH, path)) {
                continue;
            }
            if (MATCHER.match(SecurityConfig.MANAGEMENT_PATH, path)) {
                namespaceOnly.add(id);
            } else {
                uncovered.add(id);
            }
        }

        assertThat(uncovered)
                .as("an exposed endpoint no rule above the catch-all matches is authorized by the"
                        + " business rule, which is the defect this rule set was corrected to remove")
                .isEmpty();
        assertThat(namespaceOnly)
                .as("at least one exposed endpoint must depend on the namespace entry, or this"
                        + " assertion would pass with that entry deleted")
                .isNotEmpty();
    }

    /**
     * Confirms the decision the operator rule installs admits the task-local addresses and refuses every
     * other, with no principal at all.
     *
     * <p>Assumptions: the decision object is obtained from {@link SecurityConfig#loopbackOnly()} rather
     * than rebuilt here, so the assertion is about the rule the chain enforces -- the same reason
     * {@link SecurityConfig#businessAccess()} is exposed. The authentication supplied is {@code null},
     * and that is the behaviour being asserted rather than a shortcut: the collector sidecar presents no
     * credential, so a rule consulting a principal would refuse the only consumer these endpoints
     * have.</p>
     *
     * <p>Assumptions: a private-subnet address is among the refusals rather than only a public one,
     * because the tasks of this deployment share a VPC and the address that must be refused in practice
     * is a peer inside that network.</p>
     */
    @Test
    @DisplayName("the operator decision admits only the task-local addresses, with no token")
    void theOperatorDecisionAdmitsOnlyTheTaskLocalAddresses() {
        assertThat(grantedFrom("127.0.0.1"))
                .as("the container HEALTHCHECK and the collector both read the port over IPv4 loopback")
                .isTrue();
        assertThat(grantedFrom("::1"))
                .as("a stack presenting the IPv6 loopback must not silently lose its metrics")
                .isTrue();
        assertThat(grantedFrom("10.0.4.17"))
                .as("a peer inside the VPC must not read this task's migration state or stack frames")
                .isFalse();
        assertThat(grantedFrom("203.0.113.7"))
                .as("nothing off the box may read them either")
                .isFalse();
    }

    /**
     * The negative case this package exists for: every token shape that yields no authority is refused.
     *
     * <p>Assumptions: all five shapes are validly signed, in-date tokens from the configured pool, so each
     * one is fully AUTHENTICATED. Under the previous rule each was therefore admitted to every route. The
     * shapes are the complete set the shared converter documents as yielding no name: an absent claim, an
     * empty array, an array naming only unrecognised groups, a claim encoded as neither string nor
     * collection, and an array whose entries are not textual.</p>
     */
    @Test
    @DisplayName("a validly signed token granted no authority is refused by the catch-all")
    void validlySignedTokenGrantedNoAuthorityIsRefused() {
        List<Map<String, Object>> shapesGrantingNothing = List.of(
                Map.of("sub", "subject-with-no-group-claim"),
                Map.of(JwtRoleConverter.GROUPS_CLAIM, List.of()),
                Map.of(JwtRoleConverter.GROUPS_CLAIM, List.of("carddemo-unknown")),
                Map.of(JwtRoleConverter.GROUPS_CLAIM, Map.of("group", JwtRoleConverter.ADMIN_AUTHORITY)),
                Map.of(JwtRoleConverter.GROUPS_CLAIM, List.of(42)));

        for (Map<String, Object> claims : shapesGrantingNothing) {
            Authentication authentication = authenticationFrom(claims);

            assertThat(authentication.isAuthenticated())
                    .as("the shape %s must still be an AUTHENTICATED principal, or this proves nothing",
                            claims.keySet())
                    .isTrue();
            assertThat(isGranted(authentication))
                    .as("a principal holding %s must not reach a business route",
                            authentication.getAuthorities())
                    .isFalse();
        }
    }

    /**
     * Confirms the catch-all still admits both closed groups, so the tightening removed no capability.
     *
     * <p>Assumptions: the administrator is asserted as well as the ordinary user. The baseline reaches this
     * context's screens from a menu both user types reach, so a rule that admitted only one of them would
     * be a behavioural change rather than a correction.</p>
     */
    @Test
    @DisplayName("both closed groups still reach a business route")
    void bothClosedGroupsStillReachABusinessRoute() {
        for (String group : SecurityConfig.BUSINESS_AUTHORITIES) {
            assertThat(isGranted(authenticationFrom(
                            Map.of(JwtRoleConverter.GROUPS_CLAIM, List.of(group)))))
                    .as("the %s group must keep the access it has today", group)
                    .isTrue();
        }
    }

    /**
     * Confirms a blank app client id stops the context being built rather than silently dropping a check.
     *
     * <p>Assumptions: the assertion is on the factory that READS the property, because the shared validator
     * treats {@code null} and any blank value alike as an instruction to skip the client check, and reports
     * nothing about having skipped it. A refusal anywhere later than context build would be a refusal
     * nobody sees. All three unset forms are covered because the empty string and a whitespace-only value
     * are what a property left unset in one profile and inherited from another actually takes.</p>
     */
    @Test
    @DisplayName("a blank app client id fails fast while the context is being built")
    void blankAppClientIdFailsFastWhileTheContextIsBeingBuilt() {
        for (String unset : java.util.Arrays.asList(null, "", "   ")) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new SecurityConfig().jwtDecoder("https://issuer.example.invalid",
                            CognitoAccessTokenValidator.ACCESS_TOKEN_USE, unset, "scope"))
                    .withMessageContaining("expected-client-id");
        }
    }

    /**
     * Confirms a token kind the shared validator cannot enforce is refused at context build too.
     */
    @Test
    @DisplayName("a token-use the shared validator cannot enforce fails fast")
    void tokenUseTheSharedValidatorCannotEnforceFailsFast() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new SecurityConfig().jwtDecoder(
                        "https://issuer.example.invalid", "id", "client", "scope"))
                .withMessageContaining(CognitoAccessTokenValidator.ACCESS_TOKEN_USE);
    }

    /**
     * Applies the installed operator decision to a request arriving from one address.
     *
     * @param remoteAddress the address the request appears to come from; must not be {@code null}
     * @return {@code true} when the installed manager grants access from that address
     */
    private boolean grantedFrom(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/flyway");
        request.setRemoteAddr(remoteAddress);
        Supplier<Authentication> noPrincipal = () -> null;

        AuthorizationResult result = SecurityConfig.loopbackOnly()
                .authorize(noPrincipal, new RequestAuthorizationContext(request));
        return result != null && result.isGranted();
    }

    /**
     * Reads the management endpoint ids the development profile publishes.
     *
     * <p>Assumptions: the value binds as a set and a profile REPLACES the inherited collection rather
     * than merging into it, so this one list is the complete exposure of that profile and no base file
     * has to be read alongside it.</p>
     *
     * @return the exposed endpoint ids, never {@code null} and never empty
     * @throws IllegalStateException if the profile document cannot be read or publishes no exposure
     *     list, either of which would make this assertion silently vacuous
     */
    @SuppressWarnings("unchecked")
    private List<String> exposedEndpointIds() {
        try (InputStream profile =
                SecurityConfigTest.class.getResourceAsStream("/application-dev.yml")) {
            if (profile == null) {
                throw new IllegalStateException("application-dev.yml is not on the test class path");
            }
            Map<String, Object> document = new Yaml().load(profile);
            Object include = ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                    ((Map<String, Object>) document.get("management")).get("endpoints")).get("web"))
                            .get("exposure")).get("include");
            if (include == null) {
                throw new IllegalStateException(
                        "application-dev.yml publishes no management exposure list");
            }
            List<String> ids = new ArrayList<>();
            for (String id : String.valueOf(include).split(",")) {
                ids.add(id.trim());
            }
            return ids;
        } catch (java.io.IOException problem) {
            throw new IllegalStateException("application-dev.yml could not be read", problem);
        }
    }

    /**
     * Applies the installed catch-all decision to one authentication.
     *
     * @param authentication the principal to test; must not be {@code null}
     * @return {@code true} when the installed manager grants access, {@code false} when it does not
     */
    private boolean isGranted(Authentication authentication) {
        AuthorizationResult result =
                SecurityConfig.businessAccess().authorize(() -> authentication, null);
        return result != null && result.isGranted();
    }

    /**
     * Converts one claim map into the authentication the filter chain would see for that token.
     *
     * @param claims the claims the token carries; must not be {@code null} and must not be empty,
     *     because a token cannot be constructed without claims
     * @return the authentication the resource server's converter produces, never {@code null}
     */
    private Authentication authenticationFrom(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none");
        claims.forEach(builder::claim);
        return new SecurityConfig()
                .jwtAuthenticationConverter(
                        JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY)
                .convert(builder.build());
    }
}
