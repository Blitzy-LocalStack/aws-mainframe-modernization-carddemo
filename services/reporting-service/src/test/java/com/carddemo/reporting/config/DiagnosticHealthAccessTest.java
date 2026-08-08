package com.carddemo.reporting.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.security.JwtRoleConverter;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.util.AntPathMatcher;
import org.yaml.snakeyaml.Yaml;

/**
 * Asserts who may read the reporting context's diagnostic health group, and what the others receive.
 *
 * <h2>Purpose</h2>
 *
 * <p>The development overlay publishes one health group, {@code diagnostics}, whose body names the
 * datasource the read-only reporting role connects with and whether that connection is up. Before this
 * class existed the group was guarded by {@code management.endpoint.health.roles: carddemo-admin}
 * alone, and that guard could not work: the actuator's authorization test delegates to the servlet
 * role check, which prepends the framework's {@code ROLE_} prefix to a value that does not already
 * carry one, while {@link JwtRoleConverter} publishes the provider's group names with NO prefix. The
 * configured value therefore tested for an authority nothing in this system grants, so the group
 * denied every caller -- including the administrator it was written for -- while the path itself sat
 * inside a {@code permitAll()} namespace. This class asserts the replacement at each of its three
 * links: the path is carved out of the permitted namespace, the carve-out admits only the
 * administrator authority, and the two refusals render the body this service's contracts publish.</p>
 *
 * <p>Alternatives Considered: booting a servlet context and issuing three requests, which would
 * assert the three response bodies end to end. Rejected because this module's configuration package
 * builds a token decoder that resolves the issuer's discovery document over the network at
 * bean-creation time, so any context including it fails in an isolated environment for a reason
 * unrelated to the rule under test -- the same constraint {@code ReportControllerTest} records. The
 * assertions below therefore exercise the deployed objects directly: the real matcher, the real
 * authorization manager the chain installs, and the real refusal handlers it registers.</p>
 *
 * <p>Assumptions: the endpoint-level configuration is read from the shipped YAML rather than restated
 * here, because the property that matters is what the deployed file says. A test carrying its own copy
 * of the expected settings would keep passing after the file diverged from it, which is precisely the
 * failure this finding was.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing to anything outside the
 * test engine, so this block carries no parameter, return or exception section; every member below
 * carries its own.</p>
 */
@DisplayName("Reporting diagnostic health: who may read it, and what everyone else receives")
class DiagnosticHealthAccessTest {

    /** The path matcher whose semantics Spring Security's string request matchers follow. */
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** A fixed instant, so a rendered refusal body carries a known timestamp. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-08T10:15:30.123456Z"), ZoneOffset.UTC);

    /** The three paths an orchestrator or load balancer polls with no credential at all. */
    private static final List<String> UNAUTHENTICATED_PROBE_PATHS = List.of(
            "/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness");

    /**
     * Reads the shipped development overlay as a nested map.
     *
     * @return the parsed overlay, never {@code null}
     * @throws UncheckedIOException if the overlay is absent from the test classpath, which is a broken
     *     build rather than a contract failure and is therefore not translated into an assertion
     */
    private static Map<String, Object> developmentOverlay() {
        try (InputStream source = DiagnosticHealthAccessTest.class.getClassLoader()
                .getResourceAsStream("application-dev.yml")) {
            if (source == null) {
                throw new IllegalStateException("application-dev.yml is not on the test classpath");
            }
            return new Yaml().load(source);
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot read application-dev.yml", cause);
        }
    }

    /**
     * Navigates a nested configuration map by successive keys.
     *
     * @param root the map to descend from; must not be {@code null}
     * @param keys the keys to follow in order
     * @return the map found at that position, never {@code null}
     * @throws IllegalStateException if any key is absent or does not hold a map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String... keys) {
        Map<String, Object> current = root;
        for (String key : keys) {
            Object next = current.get(key);
            if (!(next instanceof Map)) {
                throw new IllegalStateException("configuration key " + key + " holds no section");
            }
            current = (Map<String, Object>) next;
        }
        return current;
    }

    /**
     * Evaluates the deployed diagnostic rule for one authentication.
     *
     * @param authentication the principal to judge, or an anonymous token for no principal
     * @return {@code true} when the rule grants access, {@code false} when it refuses
     */
    private static boolean grantsDiagnosticAccess(Authentication authentication) {
        Supplier<Authentication> supplier = () -> authentication;
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", SecurityConfig.HEALTH_DIAGNOSTIC_PATH);
        AuthorizationResult result = SecurityConfig.diagnosticAccess()
                .authorize(supplier, new RequestAuthorizationContext(request));
        return result != null && result.isGranted();
    }

    /**
     * Builds an authenticated principal holding exactly the named authorities.
     *
     * @param authorities the authority strings to grant, verbatim and unprefixed
     * @return an authenticated token carrying those authorities, never {@code null}
     */
    private static Authentication authenticatedWith(String... authorities) {
        TestingAuthenticationToken token = new TestingAuthenticationToken("principal", "credentials",
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
        token.setAuthenticated(true);
        return token;
    }

    /**
     * Asserts the diagnostic path is carved out of the permitted namespace and the probes are not.
     */
    @Test
    @DisplayName("the diagnostic path is carved out of the permitted health namespace")
    void theDiagnosticPathIsCarvedOutOfThePermittedNamespace() {
        assertThat(MATCHER.match(SecurityConfig.HEALTH_DIAGNOSTIC_PATH,
                SecurityConfig.HEALTH_DIAGNOSTIC_PATH)).isTrue();

        // WHY : Assumptions: the permitted namespace is asserted to match the diagnostic path TOO,
        //       which is the whole reason the carve-out has to be declared first. A reader who
        //       assumed the two patterns were disjoint would see no significance in their order, and
        //       reordering them would reopen the path to everyone while every other assertion here
        //       still passed.
        assertThat(MATCHER.match(SecurityConfig.HEALTH_PATH,
                SecurityConfig.HEALTH_DIAGNOSTIC_PATH)).isTrue();

        // WHY : Assumptions: the three unauthenticated probe paths are asserted NOT to match the
        //       carve-out. If they did, an orchestrator polling with no credential would receive 401
        //       on every poll and the task would be replaced continuously while being healthy --
        //       a failure that looks like an infrastructure fault rather than an authorization change.
        for (String probe : UNAUTHENTICATED_PROBE_PATHS) {
            assertThat(MATCHER.match(SecurityConfig.HEALTH_DIAGNOSTIC_PATH, probe))
                    .as("the carve-out must not capture the unauthenticated probe path %s", probe)
                    .isFalse();
            assertThat(MATCHER.match(SecurityConfig.HEALTH_PATH, probe))
                    .as("the probe path %s stays inside the permitted namespace", probe)
                    .isTrue();
        }
    }

    /**
     * Asserts only the administrator authority satisfies the diagnostic rule.
     */
    @Test
    @DisplayName("only the administrator authority reaches the diagnostic group")
    void onlyTheAdministratorAuthorityReachesTheDiagnosticGroup() {
        assertThat(grantsDiagnosticAccess(authenticatedWith(JwtRoleConverter.ADMIN_AUTHORITY)))
                .as("an administrator reads the diagnostic group")
                .isTrue();

        // WHY : Assumptions: the ordinary cardholder group is refused, and it is asserted separately
        //       from the anonymous case because the two are different failures. Reusing the catch-all
        //       rule here -- which admits both groups by design -- would grant the datasource detail
        //       to every valid token, and that mistake would look correct at the call site.
        assertThat(grantsDiagnosticAccess(authenticatedWith(JwtRoleConverter.USER_AUTHORITY)))
                .as("an ordinary cardholder is refused the diagnostic group")
                .isFalse();

        // WHY : Assumptions: a validly signed token granted NO group is refused. JwtRoleConverter
        //       grants an empty authority set for a token whose groups claim is absent or names only
        //       unrecognised groups, and such a token is still fully authenticated -- so a rule
        //       expressed as "authenticated" would have admitted it.
        assertThat(grantsDiagnosticAccess(authenticatedWith())).isFalse();

        // WHY : Assumptions: the prefixed spelling is asserted to be refused, which is the defect
        //       this rule replaced, read from the other direction. Had the rule been written in the
        //       role register it would have matched ROLE_carddemo-admin and refused the unprefixed
        //       authority the converter actually grants; asserting both spellings pins which register
        //       is in force rather than leaving it to be inferred.
        assertThat(grantsDiagnosticAccess(
                authenticatedWith("ROLE_" + JwtRoleConverter.ADMIN_AUTHORITY)))
                .as("the prefixed spelling is not what this system grants and must not satisfy the rule")
                .isFalse();

        assertThat(grantsDiagnosticAccess(new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))))
                .as("an anonymous caller is refused the diagnostic group")
                .isFalse();
    }

    /**
     * Asserts an anonymous caller's refusal renders the contract's error body with status 401.
     *
     * @throws IOException if the handler cannot write to the mock response
     * @throws ServletException if the handler declares one, which it does and this class does not catch
     */
    @Test
    @DisplayName("an anonymous caller receives the contract's 401 body, not a bare status")
    void anAnonymousCallerReceivesTheContractsUnauthenticatedBody()
            throws IOException, ServletException {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", SecurityConfig.HEALTH_DIAGNOSTIC_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiErrorSecurityHandlers.entryPoint(CLOCK).commence(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_token")));

        assertThat(response.getStatus()).isEqualTo(401);

        // WHY : Assumptions: the assertion is on the BODY and not only on the status, because the
        //       framework default answers a missing token with a status and a challenge header and no
        //       body at all -- and every published contract of this service declares an error body
        //       for 401. A status-only assertion would pass for that default.
        assertThat(response.getContentAsString())
                .contains("\"status\":401")
                .contains(SecurityConfig.HEALTH_DIAGNOSTIC_PATH);

        // WHY : Assumptions: the refusal is asserted to disclose NO contributor name. The whole
        //       point of the gate is that the datasource contributor is not named to a caller who may
        //       not read it, so a refusal body that named it would defeat the rule it enforces.
        assertThat(response.getContentAsString()).doesNotContain("diskSpace").doesNotContain("\"db\"");
    }

    /**
     * Asserts an authenticated non-administrator's refusal renders the contract's body with status 403.
     *
     * @throws IOException if the handler cannot write to the mock response
     * @throws ServletException if the handler declares one, which it does and this class does not catch
     */
    @Test
    @DisplayName("an ordinary cardholder receives the contract's 403 body, not a bare status")
    void anOrdinaryCardholderReceivesTheContractsForbiddenBody()
            throws IOException, ServletException {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", SecurityConfig.HEALTH_DIAGNOSTIC_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiErrorSecurityHandlers.accessDeniedHandler(CLOCK).handle(request, response,
                new AccessDeniedException("Access Denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("\"status\":403")
                .contains(SecurityConfig.HEALTH_DIAGNOSTIC_PATH);
        assertThat(response.getContentAsString()).doesNotContain("diskSpace").doesNotContain("\"db\"");
    }

    /**
     * Asserts the chain declares the carve-out BEFORE the permitted namespace it overlaps.
     */
    @Test
    @DisplayName("the chain declares the carve-out before the permitted namespace it overlaps")
    void theChainDeclaresTheCarveOutBeforeThePermittedNamespace() {
        String configuration = configurationOnly(chainSource());

        int carveOut = configuration.indexOf("HEALTH_DIAGNOSTIC_PATH).access(diagnosticAccess())");
        int permitted = configuration.indexOf("HEALTH_PATH).permitAll()");

        assertThat(carveOut).as("the chain installs the diagnostic carve-out").isNotNegative();
        assertThat(permitted).as("the chain still permits the probe namespace").isNotNegative();

        // WHY : Alternatives Considered: booting a servlet context and issuing one request per
        //       principal, which would observe the order as a status code rather than as a source
        //       position. Rejected for the reason recorded on this class: this module's configuration
        //       package resolves the issuer's discovery document at bean-creation time, so no context
        //       including it can start here. Reading the chain method is the only observation of the
        //       ORDER available in this module, and the order is the load-bearing fact -- both patterns
        //       match the diagnostic path, so swapping the two lines reopens it to everyone while every
        //       other assertion in this class still passes.
        assertThat(carveOut)
                .as("both patterns match the diagnostic path, so the carve-out must be declared first")
                .isLessThan(permitted);
    }

    /**
     * Reads the deployed chain configuration's own source.
     *
     * @return the text of {@link SecurityConfig}, never {@code null}
     * @throws UncheckedIOException if the source is absent, which is a broken checkout rather than a
     *     contract failure and is therefore not translated into an assertion
     */
    private static String chainSource() {
        java.nio.file.Path source = java.nio.file.Path
                .of("src/main/java/com/carddemo/reporting/config/SecurityConfig.java");
        try {
            return java.nio.file.Files.readString(source);
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot read " + source, cause);
        }
    }

    /**
     * Strips every comment from a Java source so an assertion reads configuration and not prose.
     *
     * @param source the raw source text; must not be {@code null}
     * @return the same text with block comments and line comments removed
     */
    private static String configurationOnly(String source) {
        // WHY : Assumptions: the comments are removed before the search, and this is not tidiness.
        //       The paragraphs in the file under test NAME both constants while explaining why their
        //       order matters, so a search over the raw text would find the carve-out inside a comment
        //       and the assertion would pass on a chain that had been reordered -- and would equally
        //       pass on one where the rule had been commented out entirely. An assertion about a
        //       source file has to read the configuration, never the prose describing it.
        //
        // WHY : Refactoring Rationale: this began as two passes -- block comments stripped first, then
        //       line comments -- and that ORDER was a defect, caught by this very assertion failing
        //       against a chain that was already correct. The chain's own explanatory paragraph quotes
        //       the ant pattern /actuator/health/** to say why the order matters, and the slash-star
        //       pair inside that pattern reads as a block-comment OPENER to a regex. Stripping blocks
        //       first therefore matched from that pseudo-opener to the next real close delimiter and
        //       swallowed the sixty-odd lines that contain the two rules being asserted on. Measuring
        //       the file showed nineteen openers against thirteen closers, which is the signature of
        //       the mistake.
        //
        // WHY : Trade-offs: one left-to-right pass with an alternation replaces the two passes, so
        //       whichever construct appears FIRST consumes its own run and none can be misread inside
        //       another -- a line comment quoting a slash-star pattern is consumed as a line comment,
        //       a Javadoc block is consumed as a block, and a string literal is consumed as a string.
        //
        // WHY : Assumptions: the STRING-LITERAL branch is not defensive padding, it is required by the
        //       file under test, and this was measured rather than assumed. Two of its declarations
        //       hold an ant pattern whose text contains a slash-star pair inside the quotes --
        //       "/actuator/health/**" and "/actuator/**" -- so a stripper that recognised only the two
        //       comment forms would treat the first of those as a comment opener and silently discard
        //       the run of source that follows it. Consuming quoted runs first makes the pattern text
        //       inert. Ordering is what makes this work in both directions: a quote reached inside a
        //       Javadoc block never starts a string, because the block's opener is reached first and
        //       takes the whole block with it.
        //
        // WHY : Trade-offs: this remains a deliberate approximation rather than a Java lexer. It is
        //       sound for this file, and the two properties it relies on are checked rather than
        //       hoped for: the file contains no text block, whose triple quote would parse here as an
        //       empty literal followed by an opening quote, and neither literal this test searches for
        //       contains a quote or a comment sequence. A real parser would generalise it, at the cost
        //       of a dependency an assertion this small has no business adding.
        return source.replaceAll("(?s)\"(?:\\\\.|[^\"\\\\])*\"|/\\*.*?\\*/|//[^\\n]*", " ");
    }

    /**
     * Asserts the shipped overlay discloses detail on the group alone and no longer names a role.
     */
    @Test
    @DisplayName("the overlay discloses detail on the group alone and names no unsatisfiable role")
    void theOverlayDisclosesDetailOnTheGroupAloneAndNamesNoRole() {
        Map<String, Object> health =
                section(developmentOverlay(), "management", "endpoint", "health");

        // WHY : Assumptions: the absence of the roles key is asserted, not merely the presence of
        //       the chain rule. Leaving both gates in place with one of them unsatisfiable is the
        //       arrangement that produced a group nobody could read, so the deletion is the fix and
        //       an assertion on the chain alone would not notice the key coming back.
        assertThat(health).doesNotContainKey("roles");

        assertThat(health.get("show-details")).isEqualTo("never");
        assertThat(health.get("show-components")).isEqualTo("never");

        Map<String, Object> diagnostics = section(health, "group", "diagnostics");
        assertThat(diagnostics.get("show-details")).isEqualTo("when-authorized");
        assertThat(diagnostics.get("show-components")).isEqualTo("when-authorized");

        // WHY : Assumptions: the included contributors are asserted so the group resolves to
        //       something rather than matching nothing. A group naming a contributor this module does
        //       not register resolves to a smaller set than it appears to name, and an operator
        //       reading it would conclude the missing contributor was healthy.
        assertThat(diagnostics.get("include")).isEqualTo("db,diskSpace,ping");
    }
}
