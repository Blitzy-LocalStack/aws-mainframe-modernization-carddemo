package com.carddemo.auth.config;

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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
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
     * collection matched by no rule at all.</p>
     *
     * <p>Refactoring Rationale: this rationale said such an omission left those operations "reachable by
     * any authenticated user", which was true of the catch-all it was written against and is not true of
     * the present one. The chain now ends in {@code denyAll()}, so the omission fails CLOSED and both
     * operations would answer 403 to an administrator. The assertion is unchanged and is worth more
     * under the new rule, not less: it is now the test that keeps a published operation REACHABLE, and
     * {@link #everyPublishedPathIsGrantedByARuleAboveTheCatchAll()} states that obligation directly.</p>
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
     * match no reachable request, so the routes it was written to guard would reach the catch-all
     * instead.</p>
     *
     * <p>Refactoring Rationale: this rationale called that outcome "a silent fail-open that reads as
     * correct", which described the {@code authenticated()} catch-all it was written against. Against the
     * present {@code denyAll()} the same defect is a fail-closed outage rather than a silent grant. The
     * assertion stands either way, because a pattern that matches no request is wrong under both rules --
     * only the symptom moves, from an unguarded route to an unreachable one.</p>
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
     * Confirms the chain matches the whole management namespace, not only the endpoints the base
     * exposure list names.
     *
     * <p>Assumptions: the list is read from the configuration rather than restated here, and its LAST
     * entry is asserted to be the namespace. The defect this guards is precise: the namespace constant
     * existed for several revisions while no rule matched it, so a management endpoint a profile
     * published inherited the catch-all instead of the operator rule. A test naming only the three
     * specific endpoints would have passed throughout.</p>
     */
    @Test
    @DisplayName("the operator rule the chain installs covers the whole management namespace")
    void theOperatorRuleCoversTheWholeManagementNamespace() {
        assertThat(SecurityConfig.operatorPaths())
                .as("the chain is built from this list, so a missing entry is a missing rule")
                .containsExactly(
                        SecurityConfig.BUILD_IDENTITY_PATH,
                        SecurityConfig.METRICS_PATH,
                        SecurityConfig.METRIC_SCRAPE_PATH,
                        SecurityConfig.MANAGEMENT_PATH);
        assertThat(SecurityConfig.operatorPaths())
                .last()
                .as("the namespace is the backstop, so it must be present and must be last")
                .isEqualTo(SecurityConfig.MANAGEMENT_PATH);
        assertThat(SecurityConfig.operatorPaths())
                .allSatisfy(path -> assertThat(path)
                        .as("an operator rule that reached outside /actuator would take a business route"
                                + " away from every ordinary user")
                        .startsWith("/actuator"));
    }

    /**
     * Confirms every management endpoint the development profile publishes is decided by the health rule
     * or by an operator rule, and never by the catch-all.
     *
     * <p>Assumptions: the exposure list is READ from {@code application-dev.yml} rather than restated,
     * because that file is where the surface grows. It publishes {@code env}, {@code configprops} and
     * {@code flyway} beyond the base four, and each of those reaches a real handler -- the active
     * profiles and the ordered property-source list, every property name the task received, and the
     * migration history of the {@code auth} schema.</p>
     *
     * <p>Assumptions: the second assertion is what stops this test being vacuous. It requires at least
     * one exposed endpoint to be covered ONLY by the namespace entry, which proves the namespace rule is
     * load-bearing rather than decorative: without it those ids would be authorized by the catch-all.</p>
     */
    @Test
    @DisplayName("every endpoint the dev profile publishes is covered by the health or operator rules")
    void everyExposedManagementEndpointIsCoveredByAHealthOrOperatorRule() {
        List<String> exposed = exposedEndpointIds();
        assertThat(exposed)
                .as("the profile must still publish the diagnostic ids this assertion exists for")
                .contains("health", "env", "configprops", "flyway");

        List<String> uncovered = new ArrayList<>();
        List<String> namespaceOnly = new ArrayList<>();
        for (String id : exposed) {
            String path = "/actuator/" + id;
            if (MATCHER.match(SecurityConfig.HEALTH_PATH, path) || matchesANamedOperatorPath(path)) {
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
                        + " catch-all, which is the defect this rule set was corrected to remove")
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
     * than rebuilt here, so the assertion is about the rule the chain enforces. The authentication
     * supplied is {@code null}, and that is deliberate rather than lazy: the collector sidecar presents
     * no credential, so a rule that consulted a principal would refuse the only consumer these endpoints
     * have. Granting a tokenless request from loopback is the behaviour being asserted.</p>
     *
     * <p>Assumptions: a private-subnet address is included among the refusals, not just a public one.
     * The tasks share a VPC with every other service, so the address that must be refused in practice is
     * a peer inside that network rather than something arriving from the internet.</p>
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
                .as("a peer inside the VPC must not read this service's deployment internals")
                .isFalse();
        assertThat(grantedFrom("203.0.113.7"))
                .as("nothing off the box may read them either")
                .isFalse();
    }

    /**
     * Confirms the management namespace reaches neither a business route nor an open path.
     *
     * <p>Assumptions: a namespace pattern that swept a business path in would not fail open, it would
     * fail CLOSED -- the route would be refused for every caller off the box, an outage rather than a
     * disclosure -- so it needs an assertion of its own, since every security assertion here would still
     * pass.</p>
     */
    @Test
    @DisplayName("the management namespace reaches no business route and no open path")
    void theManagementNamespaceReachesNoBusinessRouteOrOpenPath() {
        for (String openPath : SecurityConfig.unauthenticatedPaths()) {
            assertThat(MATCHER.match(SecurityConfig.MANAGEMENT_PATH, openPath))
                    .as("%s must stay reachable without a token from anywhere", openPath)
                    .isFalse();
        }
        assertThat(MATCHER.match(
                        SecurityConfig.MANAGEMENT_PATH, SecurityConfig.USER_COLLECTION_PATH_PATTERN))
                .isFalse();
        assertThat(MATCHER.match(
                        SecurityConfig.MANAGEMENT_PATH, "/api/v1/auth/users/" + SAMPLE_USER_ID))
                .isFalse();
    }

    /**
     * Confirms every path the contract publishes is granted by a rule above the denying catch-all.
     *
     * <p>Assumptions: this is the obligation the catch-all change created. While the catch-all required
     * only authentication, a published path nobody had written a rule for was still reachable -- too
     * widely, but reachable. Now it is refused, so completeness of the rule set is a functional
     * requirement and not only a security one, and it is asserted against the published contract rather
     * than against a list restated here.</p>
     */
    @Test
    @DisplayName("every published path is granted by a rule above the denying catch-all")
    void everyPublishedPathIsGrantedByARuleAboveTheCatchAll() {
        List<String> ungranted = new ArrayList<>();
        for (String template : contractPaths().keySet()) {
            String path = template.replace("{userId}", SAMPLE_USER_ID);
            boolean granted = SecurityConfig.unauthenticatedPaths().contains(path)
                    || SecurityConfig.requiredAuthorityFor(path) != null;
            if (!granted) {
                ungranted.add(template);
            }
        }

        assertThat(ungranted)
                .as("a published path matched by no rule now answers 403 to every caller, so this is"
                        + " the assertion that keeps the contract servable")
                .isEmpty();
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
     * Confirms a blank app client id stops the service at startup rather than silently dropping a check.
     *
     * <p>Assumptions: the assertion covers {@code null}, the empty string and a whitespace-only value.
     * Those last two are the values a property left unset in one profile and inherited from another
     * actually takes, and the shared validator collapses all three to "skip the client check" while
     * reporting nothing about having skipped it. A refusal at context build is therefore the only signal
     * an operator can act on.</p>
     */
    @Test
    @DisplayName("a blank app client id fails fast while the context is built")
    void blankAppClientIdFailsFastWhileTheContextIsBuilt() {
        for (String unset : java.util.Arrays.asList(null, "", "   ")) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("a client id of [%s] must not be accepted as a configured value", unset)
                    .isThrownBy(() -> new SecurityConfig().jwtDecoder("https://issuer.example.invalid",
                            CognitoAccessTokenValidator.ACCESS_TOKEN_USE, unset, "scope"))
                    .withMessageContaining("expected-client-id");
        }
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
     * Reports whether one concrete path is matched by an operator rule other than the namespace entry.
     *
     * @param path the concrete request path to test; must not be {@code null}
     * @return {@code true} when one of the specifically named operator paths matches it
     */
    private boolean matchesANamedOperatorPath(String path) {
        for (String operatorPath : SecurityConfig.operatorPaths()) {
            if (!SecurityConfig.MANAGEMENT_PATH.equals(operatorPath)
                    && MATCHER.match(operatorPath, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies the installed operator decision to a request arriving from one address.
     *
     * @param remoteAddress the address the request appears to come from; must not be {@code null}
     * @return {@code true} when the installed manager grants access from that address
     */
    private boolean grantedFrom(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/env");
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
