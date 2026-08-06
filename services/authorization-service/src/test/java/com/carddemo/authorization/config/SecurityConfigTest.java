package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.common.security.CognitoAccessTokenValidator;
import com.carddemo.common.security.JwtRoleConverter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.AntPathMatcher;

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
    private static final String BUSINESS_PATH = "/api/v1/authorizations";

    /**
     * Confirms the probe path stays open while the rest of the management namespace does not.
     *
     * <p>Assumptions: both the aggregate path and a probe group are asserted, because enabling the probe
     * groups publishes {@code /actuator/health/readiness} and {@code /actuator/health/liveness}
     * alongside {@code /actuator/health} and an orchestrator check may poll any of the three with no
     * credential.</p>
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
