package com.carddemo.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.common.security.CognitoAccessTokenValidator;
import com.carddemo.common.security.JwtRoleConverter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.util.AntPathMatcher;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies that every user-administration path the contract publishes is covered by the administrator gate,
 * and that sign-on is the only business operation left open.
 *
 * <p>Assumptions: the paths are read from the published contract at test time rather than restated here, so
 * an operation added to the contract without a matching gate fails this test instead of shipping
 * unguarded.</p>
 */
class SecurityConfigTest {

    /** The path matcher Spring Security's request matchers use the semantics of. */
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** The one operation the contract publishes without a bearer requirement. */
    private static final String PUBLIC_OPERATION_ID = "signOn";

    /** A representative user identifier: the baseline's eight character key. */
    private static final String SAMPLE_USER_ID = "ADMIN001";

    /**
     * Confirms every operation the contract protects is covered by one of the two administrator patterns.
     *
     * <p>Assumptions: this is the assertion that makes the gate complete rather than merely present. The
     * collection path and its subtree need two patterns, because neither wildcard form matches both, and an
     * author who wrote only the subtree pattern would leave the list and create operations on the bare
     * collection governed by the catch-all -- reachable by any authenticated user.</p>
     */
    @Test
    @DisplayName("every protected contract operation is covered by an administrator gate")
    void everyProtectedOperationIsCoveredByAnAdministratorGate() {
        List<String> gates = List.of(
                SecurityConfig.USER_COLLECTION_PATH_PATTERN, SecurityConfig.USER_SUBTREE_PATH_PATTERN);

        for (String path : protectedPaths()) {
            String requestPath = path.replace("{userId}", SAMPLE_USER_ID);
            boolean covered = gates.stream().anyMatch(gate -> MATCHER.match(gate, requestPath));
            assertThat(covered)
                    .as("protected path %s must be covered by an administrator gate", requestPath)
                    .isTrue();
        }
    }

    /**
     * Confirms the administrator gates do not close the sign-on exchange.
     *
     * <p>Assumptions: sign-on must remain reachable without a credential because it is the operation that
     * issues the first one. A gate that swallowed it would make the whole application unreachable, and it
     * would do so only in a deployed environment where no test had driven the chain.</p>
     */
    @Test
    @DisplayName("the administrator gates do not cover the sign-on exchange")
    void administratorGatesDoNotCoverSignOn() {
        String signOnPath = pathOfOperation(PUBLIC_OPERATION_ID);

        assertThat(SecurityConfig.SIGNON_PATH)
                .as("the open path must be exactly the path the contract publishes as unauthenticated")
                .isEqualTo(signOnPath);
        assertThat(MATCHER.match(SecurityConfig.USER_COLLECTION_PATH_PATTERN, signOnPath)).isFalse();
        assertThat(MATCHER.match(SecurityConfig.USER_SUBTREE_PATH_PATTERN, signOnPath)).isFalse();
    }

    /**
     * Confirms the open path is exact rather than a prefix that would open future sibling routes.
     *
     * <p>Assumptions: the edge's public route allow-list names a challenge and a refresh route that this
     * contract does not declare. Were the open path a prefix, whatever is eventually mounted beneath the
     * auth prefix would become unauthenticated without a further decision being taken.</p>
     */
    @Test
    @DisplayName("the open path is exact and does not open sibling auth routes")
    void openPathIsExactAndDoesNotOpenSiblingRoutes() {
        assertThat(MATCHER.match(SecurityConfig.SIGNON_PATH, "/api/v1/auth/refresh")).isFalse();
        assertThat(MATCHER.match(SecurityConfig.SIGNON_PATH, "/api/v1/auth/users")).isFalse();
    }

    /**
     * Guards the specific defect this repository has already shipped once: a gate written without the
     * published version prefix.
     *
     * <p>Assumptions: the service receives the full path including {@code /api/v1}, because no service sets
     * a servlet context path and the load balancer forwards without rewriting. A prefix-less pattern would
     * match no reachable request, so the guarded routes would fall through to the authenticated-only rule --
     * a silent fail-open that reads as correct.</p>
     */
    @Test
    @DisplayName("the gates require the published /api/v1 prefix and reject the prefix-less form")
    void gatesRequireThePublishedVersionPrefix() {
        assertThat(SecurityConfig.USER_COLLECTION_PATH_PATTERN).startsWith("/api/v1/");
        assertThat(SecurityConfig.USER_SUBTREE_PATH_PATTERN).startsWith("/api/v1/");
        assertThat(SecurityConfig.SIGNON_PATH).startsWith("/api/v1/");

        assertThat(MATCHER.match(SecurityConfig.USER_COLLECTION_PATH_PATTERN, "/auth/users"))
                .as("a prefix-less request must not satisfy the gate, proving the prefix is load-bearing")
                .isFalse();
        assertThat(MATCHER.match(SecurityConfig.USER_SUBTREE_PATH_PATTERN, "/auth/users/" + SAMPLE_USER_ID))
                .isFalse();
    }

    /**
     * Confirms the health path stays open, since the probes call it with no credential.
     */
    @Test
    @DisplayName("the health path is the actuator health group and nothing wider")
    void healthPathIsTheActuatorHealthGroupOnly() {
        assertThat(MATCHER.match(SecurityConfig.HEALTH_PATH, "/actuator/health")).isTrue();
        assertThat(MATCHER.match(SecurityConfig.HEALTH_PATH, "/actuator/env"))
                .as("opening the whole actuator namespace would publish configuration without a credential")
                .isFalse();
    }

    /**
     * Confirms a misconfigured token kind stops the service at startup rather than at every request.
     *
     * <p>Assumptions: the decoder factory is the subject, because that is where this configuration
     * reads its three {@code carddemo.security.jwt} keys. Asserting on it rather than on a constructor
     * is what keeps the assertion attached to the code that actually reads the property: the refusal
     * has to happen while the context is being built, since a decoder that silently applied a
     * different check would report nothing and reject nothing.</p>
     */
    @Test
    @DisplayName("a token-use the shared validator cannot accept fails fast while the context is built")
    void decoderFactoryRejectsATokenUseTheSharedValidatorCannotAccept() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new SecurityConfig().jwtDecoder(
                        "https://issuer.example.invalid", "id", "client", "scope"))
                .withMessageContaining(CognitoAccessTokenValidator.ACCESS_TOKEN_USE);
    }

    /**
     * Confirms the administrator group in the token becomes the authority the gates test for.
     */
    @Test
    @DisplayName("the administrator group becomes the authority the gates require")
    void administratorGroupBecomesTheAuthorityTheGatesRequire() {
        assertThat(authoritiesFor(JwtRoleConverter.ADMIN_AUTHORITY))
                .contains(JwtRoleConverter.ADMIN_AUTHORITY);
    }

    /**
     * Confirms an ordinary user never receives the administrator authority.
     *
     * <p>Assumptions: on this service that is the assertion with the sharpest consequence, because the
     * operations behind the gate are the ones that decide who is an administrator.</p>
     */
    @Test
    @DisplayName("the ordinary-user group does not receive the administrator authority")
    void ordinaryUserGroupDoesNotReceiveTheAdministratorAuthority() {
        assertThat(authoritiesFor(JwtRoleConverter.USER_AUTHORITY))
                .doesNotContain(JwtRoleConverter.ADMIN_AUTHORITY);
    }

    /**
     * Converts a token carrying one group into the authority names the filter chain would see.
     *
     * @param groupName the single group to place in the token's groups claim
     * @return the authority names granted, never {@code null}
     */
    private List<String> authoritiesFor(String groupName) {
        JwtAuthenticationConverter converter = newConfig().jwtAuthenticationConverter(
                JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim(JwtRoleConverter.GROUPS_CLAIM, List.of(groupName))
                .build();

        List<String> names = new ArrayList<>();
        for (GrantedAuthority authority : converter.convert(token).getAuthorities()) {
            names.add(authority.getAuthority());
        }
        return names;
    }

    /**
     * Builds a configuration instance, for the assertions that exercise one of its factory methods.
     *
     * <p>Assumptions: the type carries no state of its own -- every runtime value reaches it as a
     * parameter of the bean method that needs it -- so one plain instance serves every assertion here
     * and none of them has to name a value it does not care about.</p>
     *
     * @return a configuration instance, never {@code null}
     */
    private SecurityConfig newConfig() {
        return new SecurityConfig();
    }

    /**
     * Lists the contract paths that carry a bearer requirement, meaning every path the chain does not
     * leave open.
     *
     * <p>Assumptions: the open set is read from the configuration rather than named here as sign-on
     * alone. The pre-token exchanges are the set that grows -- a renewal operation joined it after this
     * class was first written -- and a helper that excluded only sign-on would report each new one as
     * protected and then demand an administrator gate for an operation that must answer without a
     * token at all.</p>
     *
     * @return the protected path templates, never {@code null}
     */
    private List<String> protectedPaths() {
        List<String> openPaths = SecurityConfig.unauthenticatedPaths();
        List<String> protectedOnes = new ArrayList<>();
        for (String path : contractPaths().keySet()) {
            if (!openPaths.contains(path)) {
                protectedOnes.add(path);
            }
        }
        assertThat(protectedOnes)
                .as("the contract must publish protected operations for this assertion to mean anything")
                .isNotEmpty();
        return protectedOnes;
    }

    /**
     * Finds the contract path that declares the given operation identifier.
     *
     * @param operationId the operation identifier to locate
     * @return the path template declaring it, never {@code null}
     * @throws IllegalStateException if the contract declares no such operation
     */
    private String pathOfOperation(String operationId) {
        for (Map.Entry<String, Map<String, Object>> entry : contractPaths().entrySet()) {
            for (Object operation : entry.getValue().values()) {
                if (operation instanceof Map<?, ?> fields
                        && operationId.equals(fields.get("operationId"))) {
                    return entry.getKey();
                }
            }
        }
        throw new IllegalStateException("auth-api.yaml declares no operation named " + operationId);
    }

    /**
     * Reads the published contract's paths object from the class path.
     *
     * @return the paths object, never {@code null}
     * @throws IllegalStateException if the contract cannot be read, which would mean this module's own
     *     resources are not on the test class path
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> contractPaths() {
        try (InputStream contract =
                SecurityConfigTest.class.getResourceAsStream("/openapi/auth-api.yaml")) {
            if (contract == null) {
                throw new IllegalStateException("auth-api.yaml is not on the test class path");
            }
            Map<String, Object> document = new Yaml().load(contract);
            return (Map<String, Map<String, Object>>) document.get("paths");
        } catch (java.io.IOException problem) {
            throw new IllegalStateException("auth-api.yaml could not be read", problem);
        }
    }
}
