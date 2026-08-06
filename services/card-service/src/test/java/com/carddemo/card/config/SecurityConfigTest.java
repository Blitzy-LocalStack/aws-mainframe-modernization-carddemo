package com.carddemo.card.config;

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
 * Verifies that the administrative gate on the unmasked card read matches the path the published contract
 * declares, and nothing else.
 *
 * <p>Assumptions: the contract is read from the class path at test time rather than restated as a string
 * literal here. Restating it would make this test agree with itself while the gate and the contract drifted
 * apart, which is the precise failure it exists to catch.</p>
 */
class SecurityConfigTest {

    /** The path matcher Spring Security's request matchers use the semantics of. */
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** A representative opaque card identifier: twenty-two base64url characters, as the contract fixes. */
    private static final String SAMPLE_OPAQUE_ID = "8Qk2vN7pR4tL0aXsY6bJdF";

    /** The operation the contract designates as the single administrative, unredacted read. */
    private static final String ADMIN_OPERATION_ID = "getCardUnmasked";

    /**
     * Confirms the gate matches the concrete request path the administrative operation will be called on.
     *
     * <p>Assumptions: the template's path variable is substituted with a real twenty-two character token
     * rather than left as {@code {opaqueCardId}}, because the gate is evaluated against a concrete request
     * path at run time and a template would not exercise the wildcard at all.</p>
     */
    @Test
    @DisplayName("the admin gate matches the contract path of the only unmasked operation")
    void adminGateMatchesTheContractPathOfTheUnmaskedOperation() {
        String contractPath = pathOfOperation(ADMIN_OPERATION_ID);
        String requestPath = contractPath.replace("{opaqueCardId}", SAMPLE_OPAQUE_ID);

        assertThat(MATCHER.match(SecurityConfig.ADMIN_CARD_PATH_PATTERN, requestPath))
                .as("gate %s must match the administrative request path %s",
                        SecurityConfig.ADMIN_CARD_PATH_PATTERN, requestPath)
                .isTrue();
    }

    /**
     * Confirms the gate is not wider than the one operation it is meant to protect.
     *
     * <p>Assumptions: a gate that matched a second operation would not fail open, it would fail CLOSED on
     * that operation -- restricting an ordinary user's card browse to administrators. That is a functional
     * regression rather than a security one, which is why it needs its own assertion: the security test
     * above would still pass.</p>
     */
    @Test
    @DisplayName("the admin gate matches no other published operation")
    void adminGateMatchesNoOtherPublishedOperation() {
        for (String path : publishedPaths()) {
            String requestPath = path.replace("{opaqueCardId}", SAMPLE_OPAQUE_ID);
            if (path.equals(pathOfOperation(ADMIN_OPERATION_ID))) {
                continue;
            }
            assertThat(MATCHER.match(SecurityConfig.ADMIN_CARD_PATH_PATTERN, requestPath))
                    .as("gate %s must NOT match %s", SecurityConfig.ADMIN_CARD_PATH_PATTERN, requestPath)
                    .isFalse();
        }
    }

    /**
     * Guards the specific defect this repository has already shipped once: a gate written without the
     * published version prefix.
     *
     * <p>Assumptions: the service receives the full path including {@code /api/v1}, because no service sets
     * a servlet context path and the load balancer forwards without rewriting. A prefix-less pattern
     * therefore matches no reachable request and the guarded route falls through to the
     * authenticated-only rule, which is a silent fail-open. Asserting both directions here is what stops
     * that from being reintroduced by someone shortening the constant.</p>
     */
    @Test
    @DisplayName("the admin gate requires the published /api/v1 prefix and rejects the prefix-less form")
    void adminGateRequiresThePublishedVersionPrefix() {
        assertThat(SecurityConfig.ADMIN_CARD_PATH_PATTERN)
                .as("the gate must be written against the path the service actually receives")
                .startsWith("/api/v1/");

        String prefixLessRequest = "/cards/" + SAMPLE_OPAQUE_ID + "/unmasked";
        assertThat(MATCHER.match(SecurityConfig.ADMIN_CARD_PATH_PATTERN, prefixLessRequest))
                .as("a prefix-less request path must not satisfy the gate, proving the prefix is load-bearing")
                .isFalse();
    }

    /**
     * Confirms the health path stays open, since the probes call it with no credential.
     */
    @Test
    @DisplayName("the health path is the actuator health group and nothing wider")
    void healthPathIsTheActuatorHealthGroupOnly() {
        assertThat(MATCHER.match(SecurityConfig.HEALTH_PATH, "/actuator/health")).isTrue();
        assertThat(MATCHER.match(SecurityConfig.HEALTH_PATH, "/actuator/health/readiness")).isTrue();
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
     * Confirms the administrator group in the token becomes the authority the gate tests for.
     *
     * <p>Assumptions: this is the link that makes the path expression meaningful. The gate names an
     * authority, the token carries a group, and if the translation between them broke the gate would deny
     * every caller including administrators -- so the two halves are asserted together.</p>
     */
    @Test
    @DisplayName("the administrator group becomes the authority the admin gate requires")
    void administratorGroupBecomesTheAuthorityTheGateRequires() {
        List<String> authorities = authoritiesFor(JwtRoleConverter.ADMIN_AUTHORITY);

        assertThat(authorities).contains(JwtRoleConverter.ADMIN_AUTHORITY);
    }

    /**
     * Confirms an ordinary user never receives the administrator authority.
     */
    @Test
    @DisplayName("the ordinary-user group does not receive the administrator authority")
    void ordinaryUserGroupDoesNotReceiveTheAdministratorAuthority() {
        List<String> authorities = authoritiesFor(JwtRoleConverter.USER_AUTHORITY);

        assertThat(authorities).doesNotContain(JwtRoleConverter.ADMIN_AUTHORITY);
    }

    /**
     * Converts a token carrying one group into the authority names the filter chain would see.
     *
     * @param groupName the single group to place in the token's groups claim
     * @return the authority names granted, never {@code null}
     */
    private List<String> authoritiesFor(String groupName) {
        SecurityConfig config = newConfig();
        JwtAuthenticationConverter converter = config.jwtAuthenticationConverter(
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
     * Finds the contract path that declares the given operation identifier.
     *
     * @param operationId the operation identifier to locate
     * @return the path template declaring it, never {@code null}
     * @throws IllegalStateException if the contract declares no such operation, which would mean the gate
     *     protects a route the contract no longer publishes
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
        throw new IllegalStateException("card-api.yaml declares no operation named " + operationId);
    }

    /**
     * Lists every path the contract publishes.
     *
     * @return the published path templates, never {@code null}
     */
    private List<String> publishedPaths() {
        return List.copyOf(contractPaths().keySet());
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
                SecurityConfigTest.class.getResourceAsStream("/openapi/card-api.yaml")) {
            if (contract == null) {
                throw new IllegalStateException("card-api.yaml is not on the test class path");
            }
            Map<String, Object> document = new Yaml().load(contract);
            return (Map<String, Map<String, Object>>) document.get("paths");
        } catch (java.io.IOException problem) {
            throw new IllegalStateException("card-api.yaml could not be read", problem);
        }
    }
}
