package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.common.CardDemoCommonAutoConfiguration;
import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.security.CognitoAccessTokenValidator;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.web.CorrelationIdFilter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.util.AntPathMatcher;

/**
 * Verifies the route authorization matrix {@link SecurityConfig} installs, together with the health
 * probe's openness, the operator scoping of the management namespace, and the fail-fast property checks.
 *
 * <p>Assumptions: tokens are assembled in memory because signature, issuer and time validation all happen
 * before the authority conversion this class exercises. Each assertion therefore controls only the claim
 * shape whose authorization outcome it asserts.</p>
 *
 * <p>Assumptions: every decision object under test is the one the chain installs, obtained from
 * {@link SecurityConfig#businessAccess()} or {@link SecurityConfig#fraudAccess()}, rather than a manager
 * this test constructs from the same names. Constructing one here would make the test agree with itself
 * while the rule narrowed or widened.</p>
 *
 * <p>Assumptions: no servlet container is stood up for the authorization assertions, and that is a
 * constraint of this module rather than a preference. The decoder the chain builds resolves the issuer's
 * discovery document eagerly, so a Spring context here would require a reachable identity provider; the
 * two rules are therefore exercised as objects. The one assertion that does build a context runs only the
 * shared kernel's auto-configuration, which needs no issuer.</p>
 *
 * <p>Trade-offs: exercising the managers directly binds the DECISION but not the wiring, so it cannot
 * witness that the fraud rule is declared before the read rule. The pattern assertion below covers the
 * premise that ordering rests on -- that the fraud pattern is a strict subset of the read pattern -- and
 * the residual risk of the two rules being transposed is accepted rather than hidden.</p>
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
     * Confirms the read rule's authority set is exactly the two closed groups and nothing else.
     *
     * <p>Refactoring Rationale: this test and the one below were both named for the catch-all, which they
     * have not described since that rule became {@code denyAll()}; the list and the manager they assert on
     * govern the READ surface. The names are brought into line because a test named for the wrong rule
     * sends a reader looking for a gap in the wrong place, and the two rules here have different
     * strengths.</p>
     */
    @Test
    @DisplayName("the read rule's authority set is the two closed groups")
    void readRuleAuthoritySetIsTheTwoClosedGroups() {
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
     *
     * <p>Assumptions: each such principal still carries the authentication-factor authority this framework
     * version's resource-server converter contributes, so "granted no authority" means granted none of the
     * two BUSINESS authorities rather than holding an empty collection. That is why the assertion is on the
     * rule's verdict and not on the collection's size: a size assertion would pass or fail on a
     * framework-supplied value instead of on the rule under test.</p>
     */
    @Test
    @DisplayName("a validly signed token granted no business authority is refused by the read rule")
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
     * Fraud marking admits BOTH business groups, which is the authority the baseline grants.
     *
     * <p>Refactoring Rationale: this case asserted that an ordinary user was REFUSED, and it now asserts
     * the opposite for that group while keeping every other assertion. It was a test that held a parity
     * break in place: the reference reaches this write from the main menu under the access byte
     * {@code 'U'} -- {@code app/cpy/COMEN02Y.cpy} option 11 dispatching to {@code COPAUS0C} -- and the
     * administrative menu table names the program nowhere, so refusing an ordinary user removes a
     * capability from the group the reference gives it to. The argument for the narrowing is not
     * dismissed; it is recorded at {@link SecurityConfig#fraudAccess()} together with what a deployment
     * must do to adopt it deliberately.
     *
     * <p>Assumptions: the case still exercises the manager the CHAIN installs rather than one built here
     * from the same constant, so a future narrowing of the rule fails this assertion rather than passing
     * a test that agrees with itself. The unrecognised group is asserted refused as before, because
     * widening to either business group must not widen to any group at all -- which is the mistake a
     * blanket {@code authenticated()} would be, and it would satisfy the two positive assertions.
     */
    @Test
    @DisplayName("fraud marking admits both business groups and refuses an unrecognised one")
    void fraudMarkingAdmitsBothBusinessGroupsAndRefusesAnUnrecognisedOne() {
        assertThat(grantedBy(SecurityConfig.fraudAccess(), JwtRoleConverter.ADMIN_AUTHORITY))
                .as("an administrator must be able to mark an authorization fraudulent")
                .isTrue();
        assertThat(grantedBy(SecurityConfig.fraudAccess(), JwtRoleConverter.USER_AUTHORITY))
                .as("an ordinary user must be able to mark one too, which is the baseline's own authority")
                .isTrue();
        assertThat(grantedBy(SecurityConfig.fraudAccess(), "carddemo-unknown"))
                .as("an unrecognised group must not reach the rule, so this is not authenticated-only")
                .isFalse();
    }

    /**
     * The read half of the matrix: both recognised groups reach it, and no other principal does.
     *
     * <p>Assumptions: the administrator is asserted alongside the ordinary user because the published
     * contract's ordinary-user marker means EITHER group, so a read rule that admitted only one of them
     * would refuse an administrator a route the contract grants. This is the counterpart of the fraud
     * assertion above and the two together are the whole matrix.</p>
     */
    @Test
    @DisplayName("the read rule admits either recognised group")
    void readRuleAdmitsEitherRecognisedGroup() {
        for (String group : SecurityConfig.BUSINESS_AUTHORITIES) {
            assertThat(grantedBy(SecurityConfig.businessAccess(), group))
                    .as("the %s group must reach this context's read surface", group)
                    .isTrue();
        }
        assertThat(grantedBy(SecurityConfig.businessAccess(), "carddemo-unknown"))
                .as("an unrecognised group must not reach the read surface")
                .isFalse();
    }

    /**
     * Confirms neither rule admits a caller that has not authenticated.
     *
     * <p>Assumptions: an unauthenticated request is normally answered by the entry point before any
     * authorization manager runs, so this asserts the second line rather than the first: were the entry
     * point ever removed or reordered, the managers themselves must still refuse. Both the absent
     * principal and the present-but-unauthenticated one are covered because they arrive by different
     * routes -- no credential at all versus a credential that failed -- and a manager that tested only
     * the authority collection would admit the second.</p>
     */
    @Test
    @DisplayName("neither rule admits an unauthenticated caller")
    void neitherRuleAdmitsAnUnauthenticatedCaller() {
        Authentication unauthenticated =
                UsernamePasswordAuthenticationToken.unauthenticated("someone", "secret-not-checked");

        for (AuthorizationManager<RequestAuthorizationContext> rule :
                List.of(SecurityConfig.fraudAccess(), SecurityConfig.businessAccess())) {
            assertThat(decide(rule, unauthenticated))
                    .as("a principal that failed authentication must reach nothing")
                    .isFalse();
            assertThat(decide(rule, null))
                    .as("a request with no principal at all must reach nothing")
                    .isFalse();
        }
    }

    /**
     * Confirms the two path patterns discriminate the published operations the way the chain relies on.
     *
     * <p>Assumptions: the fraud pattern is a strict SUBSET of the read pattern, which is why the chain
     * declares the fraud rule first. This asserts the subset property rather than assuming it, because it
     * is the premise of the chain's ordering: a request the fraud pattern stopped matching would fall to
     * the read rule instead, so the rule that decided it would change with no rule appearing to have
     * changed. Refactoring Rationale: the two decisions are equal today, so that fall-through would
     * currently be harmless -- the property is asserted anyway, because the fraud rule exists precisely so
     * that it can be narrowed later, and a subset relation that has silently lapsed is what would make
     * such a narrowing ineffective. The three paths used are the ones the contract publishes -- the search
     * listing, the single-resource read and the fraud write.</p>
     */
    @Test
    @DisplayName("the fraud pattern matches only the fraud route and is covered by the read pattern")
    void fraudPatternMatchesOnlyTheFraudRouteAndIsCoveredByTheReadPattern() {
        String search = BUSINESS_PATH + "/search";
        String resource = BUSINESS_PATH + "/v1.some-opaque-cursor-key";
        String fraud = resource + "/fraud";

        assertThat(MATCHER.match(SecurityConfig.FRAUD_PATH_PATTERN, fraud))
                .as("the fraud rule must match the fraud route")
                .isTrue();
        for (String read : List.of(search, resource)) {
            assertThat(MATCHER.match(SecurityConfig.FRAUD_PATH_PATTERN, read))
                    .as("%s must not be swept into the fraud rule", read)
                    .isFalse();
        }
        for (String published : List.of(search, resource, fraud)) {
            assertThat(MATCHER.match(SecurityConfig.READ_PATH_PATTERN, published))
                    .as("%s must be covered by the read pattern, which is why order decides", published)
                    .isTrue();
        }
    }

    /**
     * Confirms the group-to-authority conversion is the shared one and adds no prefix.
     *
     * <p>Assumptions: the group is asserted to appear as an authority of exactly its own name, because
     * that is what makes {@code hasAuthority} the correct predicate and the role predicate the wrong one.
     * A prefix introduced anywhere in the conversion would leave every rule in this chain matching
     * nothing, and it would present as a blanket 403 at run time rather than as a startup failure -- so
     * the prefixed forms are asserted ABSENT rather than merely the plain form present, since a
     * conversion emitting both would satisfy the weaker check while making the vocabulary ambiguous.</p>
     *
     * <p>Assumptions: the authority collection is a SUPERSET of the group names, and the assertion is
     * written to allow that deliberately. This framework version has its resource-server converter
     * contribute an authentication-factor authority of its own alongside whatever the delegated converter
     * returns, so an exact-match assertion would fail on a framework-supplied value rather than on
     * anything this repository decides. The rules are unaffected because both installed managers test for
     * the presence of a named authority, and a factor authority is not one of the two business names;
     * the point being pinned here is the SPELLING of the group authorities, not the size of the
     * collection.</p>
     */
    @Test
    @DisplayName("authorities carry the provider group names verbatim, with no role or scope prefix")
    void authoritiesCarryTheProviderGroupNamesVerbatim() {
        for (String group : SecurityConfig.BUSINESS_AUTHORITIES) {
            Authentication authentication =
                    authenticationFrom(Map.of(JwtRoleConverter.GROUPS_CLAIM, List.of(group)));

            assertThat(authentication.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .as("the %s group must become an authority of exactly that name", group)
                    .contains(group)
                    .as("no prefixed spelling of %s may be minted, or the rules would match nothing",
                            group)
                    .doesNotContain("ROLE_" + group, "SCOPE_" + group);
        }
    }

    /**
     * Confirms the correlation filter is registered by the shared kernel, ahead of the security chain.
     *
     * <p>Assumptions: this class declares no registration of its own, and that omission is only correct
     * if the shared kernel's registration is genuinely present for a servlet application, so the absence
     * is asserted positively rather than assumed. The bean is looked up by the NAME the kernel guards its
     * own registration on, because that name is the mechanism: a registration contributed under any other
     * name would not suppress the kernel's, and a reader needs the name to be load-bearing rather than
     * incidental.</p>
     *
     * <p>Assumptions: the ordering claim is compared against the framework's own default position for the
     * security chain rather than a number written here. The relationship is what matters -- the identity
     * has to be bound before the bearer-token filter runs, or a request refused with 401 or 403, which
     * never reaches a controller, would carry no identity on any of its log lines.</p>
     */
    @Test
    @DisplayName("the shared kernel registers the correlation filter ahead of the security chain")
    void sharedKernelRegistersTheCorrelationFilterAheadOfTheSecurityChain() {
        new WebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(CardDemoCommonAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .as("this chain relies on the kernel's registration instead of its own")
                            .hasBean("carddemoCorrelationIdFilterRegistration");

                    FilterRegistrationBean<?> registration = context.getBean(
                            "carddemoCorrelationIdFilterRegistration", FilterRegistrationBean.class);

                    assertThat(registration.getFilter()).isInstanceOf(CorrelationIdFilter.class);
                    assertThat(registration.getOrder())
                            .as("the identity must be bound before the security chain runs")
                            .isEqualTo(CardDemoCommonAutoConfiguration.CORRELATION_FILTER_ORDER)
                            .isLessThan(SecurityFilterProperties.DEFAULT_FILTER_ORDER);
                });
    }

    /**
     * Confirms the shared kernel contributes the firewall refusal handler this chain relies on.
     *
     * <p>Purpose: the request firewall runs BEFORE this chain's rules, so a path the firewall refuses is
     * never authorized by any rule asserted above -- the refusal is rendered by whichever
     * {@code RequestRejectedHandler} the context holds. This chain declares none of its own, and that
     * omission is only correct while the kernel's contribution is genuinely present, so the presence is
     * asserted here rather than assumed.</p>
     *
     * <p>⚠️ Refactoring Rationale: the framework's default handler answers a bare status with no body, and
     * the container then performs an ERROR dispatch that this chain re-authorizes as an anonymous request --
     * which its closing {@code denyAll()} refuses. A caller that sent a malformed path therefore received a
     * 401 naming an authentication failure it never had, with an empty correlation identifier, because the
     * correlation filter is not replayed on an error dispatch. Rendering the refusal at the point of
     * rejection is what keeps the answer truthful and its identifier populated.</p>
     *
     * <p>Assumptions: the bean is asserted as a SINGLE bean of the interface type, because the framework
     * autowires a sole candidate onto the filter-chain proxy and silently keeps its own default when the
     * context holds more than one. Counting is therefore part of the claim, not decoration.</p>
     */
    @Test
    @DisplayName("the shared kernel contributes the firewall refusal handler this chain depends on")
    void sharedKernelContributesTheFirewallRefusalHandler() {
        new WebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(CardDemoCommonAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .as("this chain relies on the kernel's contribution instead of its own")
                            .hasSingleBean(RequestRejectedHandler.class);
                    assertThat(context.getBean(RequestRejectedHandler.class))
                            .as("the contributed handler must be the one that renders the shared envelope")
                            .isInstanceOf(ApiErrorSecurityHandlers.ApiErrorRequestRejectedHandler.class);
                });
    }

    /**
     * Applies one installed decision to a principal holding exactly one authority.
     *
     * @param rule the decision object the chain installs; must not be {@code null}
     * @param authority the single authority the principal holds; must not be {@code null}
     * @return {@code true} when the rule grants access to that principal, {@code false} when it does not
     */
    private boolean grantedBy(AuthorizationManager<RequestAuthorizationContext> rule,
            String authority) {
        return decide(rule, authenticationFrom(
                Map.of(JwtRoleConverter.GROUPS_CLAIM, List.of(authority))));
    }

    /**
     * Applies one installed decision to one authentication, tolerating an absent principal.
     *
     * @param rule the decision object the chain installs; must not be {@code null}
     * @param authentication the principal to test, or {@code null} to model a request that carried none
     * @return {@code true} when the rule grants access, {@code false} when it refuses or abstains
     */
    private boolean decide(AuthorizationManager<RequestAuthorizationContext> rule,
            Authentication authentication) {
        AuthorizationResult result = rule.authorize(() -> authentication, null);
        return result != null && result.isGranted();
    }

    /**
     * Applies the installed read decision to one authentication.
     *
     * <p>Refactoring Rationale: this delegates to {@link #decide} rather than invoking the manager itself,
     * which it previously did. Two helpers each calling {@code authorize} meant two places where the
     * treatment of an abstaining result could drift, and an abstain read as a grant is the direction that
     * fails open.</p>
     *
     * @param authentication the principal to test; must not be {@code null}
     * @return {@code true} when the installed manager grants access, {@code false} when it does not
     */
    private boolean isGranted(Authentication authentication) {
        return decide(SecurityConfig.businessAccess(), authentication);
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
