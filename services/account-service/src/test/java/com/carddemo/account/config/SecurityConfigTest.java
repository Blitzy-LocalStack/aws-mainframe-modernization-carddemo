package com.carddemo.account.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.common.security.CognitoAccessTokenValidator;
import com.carddemo.common.security.JwtRoleConverter;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.AntPathMatcher;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies that {@link SecurityConfig} refuses a token carrying no recognised group, keeps the health
 * probe open, and holds the management namespace to the operator authority.
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
    private static final String BUSINESS_PATH = "/api/v1/accounts/00000000001";

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

        for (String managed : List.of("/actuator/info", "/actuator/prometheus")) {
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
     * Confirms the internal read authority reaches NEITHER group authority, so no signed-on user reaches a
     * record no baseline screen reads.
     *
     * <p>Assumptions: the disjointness is asserted from THIS side as well as from the internal chain's
     * own test, because it is the property that survives either chain being edited. The internal read
     * authority is minted for a machine caller and no Cognito group claim can produce it, so the two
     * authority sets do not overlap; a rule here that admitted it, or a rule there that admitted a group,
     * would each be a widening this assertion catches.</p>
     */
    @Test
    @DisplayName("the internal read authority is neither of the two group authorities")
    void theInternalReadAuthorityIsNeitherGroupAuthority() {
        assertThat(InternalApiSecurityConfig.INTERNAL_READ_AUTHORITY)
                .isNotEqualTo(JwtRoleConverter.ADMIN_AUTHORITY)
                .isNotEqualTo(JwtRoleConverter.USER_AUTHORITY);
        assertThat(grantedBy(SecurityConfig.businessAccess(),
                workload(InternalApiSecurityConfig.INTERNAL_READ_AUTHORITY))).isFalse();
    }

    /**
     * Confirms the business decision this chain installs admits either group authority and nothing else.
     *
     * <p>Refactoring Rationale: this replaces a case asserting that the account subtree admitted "either
     * caller" through one either-or manager. The two callers are now separated by CHAIN rather than by an
     * either-or rule -- {@code InternalApiSecurityConfig} matches the machine caller's exact path ahead of
     * this chain -- so the property to assert here is that this chain's own decision is the group rule and
     * only the group rule. Asserting the withdrawn composite would assert a manager the chain no longer
     * installs.</p>
     */
    @Test
    @DisplayName("the business decision admits either group authority and nothing else")
    void theBusinessDecisionAdmitsEitherGroupAndNothingElse() {
        assertThat(grantedBy(SecurityConfig.businessAccess(),
                workload(JwtRoleConverter.ADMIN_AUTHORITY))).isTrue();
        assertThat(grantedBy(SecurityConfig.businessAccess(),
                workload(JwtRoleConverter.USER_AUTHORITY))).isTrue();
        assertThat(grantedBy(SecurityConfig.businessAccess(),
                workload("carddemo-something-else"))).isFalse();
    }

    /**
     * Confirms the three declared patterns match exactly the three addresses the calling workload uses,
     * and that neither internal-only pattern matches a business route.
     *
     * <p>Assumptions: the three literals are restated here rather than imported from the calling context,
     * because the two are separate deployables and neither may depend on the other. What this asserts is
     * therefore the agreement itself: the paths are
     * {@code RestAccountContextClient.PATH_CARD_XREF_LOOKUP}, {@code PATH_ACCOUNT} and
     * {@code PATH_CUSTOMER} in the pending-authorization context, and a change to either side that is not
     * mirrored fails here.</p>
     */
    @Test
    @DisplayName("the internal patterns match exactly the three addresses the caller uses")
    void internalPatternsMatchTheAddressesTheWorkloadCalls() {
        assertThat(MATCHER.match(SecurityConfig.CARD_XREF_PATH_PATTERN,
                "/api/v1/card-xrefs/lookup")).isTrue();
        assertThat(MATCHER.match(SecurityConfig.ACCOUNT_PATH_PATTERN,
                "/api/v1/accounts/00000000001")).isTrue();
        assertThat(MATCHER.match(SecurityConfig.CUSTOMER_PATH_PATTERN,
                "/api/v1/customers/000000001")).isTrue();

        assertThat(MATCHER.match(SecurityConfig.CARD_XREF_PATH_PATTERN, BUSINESS_PATH)).isFalse();
        assertThat(MATCHER.match(SecurityConfig.CUSTOMER_PATH_PATTERN, BUSINESS_PATH)).isFalse();
    }

    /**
     * Applies one authorization decision to one authentication.
     *
     * @param manager the decision under test; must not be {@code null}
     * @param authentication the principal to test; must not be {@code null}
     * @return {@code true} when the manager grants access, {@code false} when it does not
     */
    private boolean grantedBy(
            org.springframework.security.authorization.AuthorizationManager<
                    org.springframework.security.web.access.intercept.RequestAuthorizationContext>
                    manager,
            Authentication authentication) {
        AuthorizationResult result = manager.authorize(() -> authentication, null);
        return result != null && result.isGranted();
    }

    /**
     * Builds an authenticated principal holding exactly one authority.
     *
     * @param authority the single authority to grant
     * @return the authentication, never {@code null}
     */
    private static Authentication workload(String authority) {
        return org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated("caller", null,
                        List.of(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority(authority)));
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

    /**
     * Confirms every management endpoint the exposure list publishes has a rule of its own.
     *
     * <p>Assumptions: this is the guard that makes the denial of {@link SecurityConfig#MANAGEMENT_PATH}
     * hold its value over time. The chain denies that namespace and grants three endpoints ahead of it
     * by name, so the two halves agree only while the exposure list publishes exactly those three. This
     * case reads the exposure list from {@code application.yml} and fails if it names a fourth.</p>
     *
     * <p>Refactoring Rationale: the namespace previously had no rule at all, so a fourth exposed
     * endpoint would have inherited the business rule and become readable by every holder of a CardDemo
     * group authority -- silently, since nothing compared the exposure list to the chain. Denying the
     * namespace closed the leak but created a quieter failure in the other direction: a newly exposed
     * endpoint would return 403 with no indication why. Asserting the two against each other is what
     * makes either mistake a build failure rather than a discovery in production.</p>
     *
     * <p>Assumptions: the wildcard is rejected explicitly. It would publish the environment listing, the
     * loggers and a heap dump, and it would satisfy any assertion that merely checked the three names
     * were present.</p>
     */
    @Test
    @DisplayName("every exposed management endpoint is named by a rule ahead of the namespace denial")
    void everyExposedManagementEndpointIsNamedByARuleAheadOfTheDenial() {
        List<String> exposed = exposedManagementEndpoints();

        assertThat(exposed)
                .as("the wildcard would publish endpoints no rule below names")
                .doesNotContain("*");
        assertThat(exposed)
                .as("an endpoint added here needs a rule in the chain ahead of the namespace denial")
                .containsExactlyInAnyOrder("health", "info", "prometheus");

        for (String endpoint : exposed) {
            String path = "/actuator/" + endpoint;
            boolean named = MATCHER.match(SecurityConfig.HEALTH_PATH, path)
                    || path.equals(SecurityConfig.BUILD_IDENTITY_PATH)
                    || path.equals(SecurityConfig.METRIC_SCRAPE_PATH);
            assertThat(named)
                    .as("%s is exposed, so it must be granted before the namespace is denied", path)
                    .isTrue();
            assertThat(MATCHER.match(SecurityConfig.MANAGEMENT_PATH, path))
                    .as("%s must also fall inside the denied namespace, so ordering is what admits it",
                            path)
                    .isTrue();
        }
    }

    /**
     * Confirms the denied namespace covers the endpoints the exposure list withholds.
     *
     * <p>Assumptions: the endpoints named here are the ones whose disclosure would matter most -- the
     * environment listing, the configuration properties, the loggers, a thread dump and a heap dump.
     * None is reachable today, and the point of the assertion is that each is covered by a DENY rule
     * rather than by the absence of a handler, so exposing one cannot quietly grant it.</p>
     */
    @Test
    @DisplayName("the denied namespace covers the endpoints the exposure list withholds")
    void deniedNamespaceCoversTheEndpointsTheExposureListWithholds() {
        List<String> withheld = List.of("/actuator/env", "/actuator/configprops",
                "/actuator/loggers", "/actuator/threaddump", "/actuator/heapdump",
                "/actuator/mappings", "/actuator/beans");

        for (String path : withheld) {
            assertThat(MATCHER.match(SecurityConfig.MANAGEMENT_PATH, path))
                    .as("%s must be matched by the namespace rule and not reach the business rule", path)
                    .isTrue();
            assertThat(MATCHER.match(SecurityConfig.HEALTH_PATH, path))
                    .as("%s must not be reachable through the credential-free health rule", path)
                    .isFalse();
            assertThat(path)
                    .isNotEqualTo(SecurityConfig.BUILD_IDENTITY_PATH)
                    .isNotEqualTo(SecurityConfig.METRIC_SCRAPE_PATH);
        }
    }

    /**
     * Reads the management endpoints this module's base profile publishes.
     *
     * @return the exposure list, one entry per endpoint identifier, trimmed; never {@code null}
     * @throws IllegalStateException if the profile is absent, does not parse to a mapping, or declares
     *     no exposure list, any of which would mean the assertions above were reading nothing
     */
    @SuppressWarnings("unchecked")
    private static List<String> exposedManagementEndpoints() {
        Object parsed;
        try (InputStream stream = SecurityConfigTest.class.getResourceAsStream("/application.yml")) {
            if (stream == null) {
                throw new IllegalStateException("/application.yml is absent from the class path");
            }
            parsed = new Yaml().load(stream);
        } catch (IOException failure) {
            throw new IllegalStateException("could not read /application.yml", failure);
        }
        if (!(parsed instanceof Map)) {
            throw new IllegalStateException("/application.yml did not parse to a mapping");
        }
        Object include = parsed;
        for (String key : List.of("management", "endpoints", "web", "exposure", "include")) {
            if (!(include instanceof Map)) {
                throw new IllegalStateException("management.endpoints.web.exposure.include is absent");
            }
            include = ((Map<String, Object>) include).get(key);
        }
        if (include == null) {
            throw new IllegalStateException("management.endpoints.web.exposure.include is absent");
        }
        return java.util.Arrays.stream(String.valueOf(include).split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }
}
