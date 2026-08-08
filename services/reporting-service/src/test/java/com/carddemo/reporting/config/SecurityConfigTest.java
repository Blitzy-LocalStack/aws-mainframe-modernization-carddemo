package com.carddemo.reporting.config;

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
 * probe open, and refuses every management path it does not publish.
 *
 * <p>Refactoring Rationale: this charter described the management namespace as held "to the operator
 * authority". It never was: no rule referenced the namespace pattern at all, so anything under it that
 * reached a handler inherited the BUSINESS rule. The namespace is now refused outright by a rule of its
 * own, placed after the three published endpoints have each been granted, and the description says so.</p>
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
    private static final String BUSINESS_PATH = "/api/v1/reports";

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
                    .as("%s must be covered by the management namespace rule", managed)
                    .isTrue();
        }
    }

    /**
     * Confirms the management refusal cannot reach a business route.
     *
     * <p>Assumptions: a management pattern that matched a business path would not fail open, it would fail
     * CLOSED -- every ordinary user would lose the route. That is a functional regression rather than a
     * security one, so it needs an assertion of its own: the security assertions above would still
     * pass.</p>
     */
    @Test
    @DisplayName("the management refusal matches only the actuator namespace")
    void operatorRuleMatchesOnlyTheActuatorNamespace() {
        assertThat(MATCHER.match(SecurityConfig.MANAGEMENT_PATH, BUSINESS_PATH))
                .as("%s must not be swept into the management refusal", BUSINESS_PATH)
                .isFalse();
        assertThat(SecurityConfig.MANAGEMENT_PATH)
                .as("the management refusal must be scoped to the management namespace")
                .startsWith("/actuator");
    }

    /**
     * Confirms an unpublished management endpoint is claimed by the refusal and by nothing else.
     *
     * <p>Purpose: the refusal is only effective if it is the ONLY rule that matches an endpoint this
     * module does not publish. Were one of the three grants to match such a path as well, that grant
     * would be declared first and would answer instead -- so this case asserts the coverage the chain's
     * ordering depends on rather than the ordering itself.</p>
     *
     * <p>Assumptions: the paths asserted are the four the service's own configuration file names as
     * deliberately unexposed, so each is a path a later widening of the exposure list would publish. The
     * environment endpoint is first because that file records the concrete consequence of exposing it:
     * this service's configuration names the datasource, the search path and the token issuer.</p>
     */
    @Test
    @DisplayName("refuse an unpublished management endpoint through the namespace rule alone")
    void anUnpublishedManagementEndpointIsRefusedByTheNamespaceRuleAlone() {
        for (String unexposed : List.of("/actuator/env", "/actuator/configprops",
                "/actuator/loggers", "/actuator/threaddump")) {

            assertThat(MATCHER.match(SecurityConfig.MANAGEMENT_PATH, unexposed))
                    .as("%s must be claimed by the management refusal", unexposed)
                    .isTrue();
            assertThat(MATCHER.match(SecurityConfig.HEALTH_PATH, unexposed))
                    .as("%s must not be reachable through the credential-free health rule", unexposed)
                    .isFalse();
            assertThat(unexposed)
                    .as("%s must not be one of the two endpoints granted by network position",
                            unexposed)
                    .isNotEqualTo(SecurityConfig.BUILD_IDENTITY_PATH)
                    .isNotEqualTo(SecurityConfig.METRIC_SCRAPE_PATH);
        }
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
                    .isThrownBy(() -> new JwtDecoderConfig("https://issuer.example.invalid",
                            CognitoAccessTokenValidator.ACCESS_TOKEN_USE, unset,
                            List.of("scope")))
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
                .isThrownBy(() -> new JwtDecoderConfig(
                        "https://issuer.example.invalid", "id", "client", List.of("scope")))
                .withMessageContaining(CognitoAccessTokenValidator.ACCESS_TOKEN_USE);
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
