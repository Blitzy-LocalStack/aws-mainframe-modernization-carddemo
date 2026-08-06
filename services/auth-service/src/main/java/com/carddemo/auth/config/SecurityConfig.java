package com.carddemo.auth.config;

import com.carddemo.common.security.CognitoAccessTokenValidator;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.web.CorrelationIdFilter;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.PathContainer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Configures who may reach this context's endpoints, which of them require the administrator
 * authority, and which two are reachable with no token at all.
 *
 * <p>This is the migrated form of the authorization split the baseline performs by reading the user
 * type out of the session structure it passes between screen turns, declared at
 * {@code app/cpy/COCOM01Y.cpy} lines 19 to 44 with its two condition names for the administrator and
 * the ordinary user. That structure is storage the CLIENT echoes back, so a client could in principle
 * assert its own user type; here the equivalent claim is signed by the identity provider and
 * validated on every request, so it cannot be asserted by the caller at all. The improvement is
 * deliberate and is recorded in {@code docs/architecture/security-and-identity.md}.</p>
 *
 * <h2>Why the route-to-authority table is a value rather than only a chain</h2>
 *
 * <p>Refactoring Rationale: the rules below are declared as an inspectable, ordered list and the
 * filter chain is BUILT from it, rather than the chain being the only statement of them. The reason
 * is the defect this class was authored to close: five of this context's seven operations are the
 * user-administration operations the baseline reached from the ADMINISTRATIVE menu only, and their
 * restriction was stated in the published contract's prose and in a tag name with nothing anywhere
 * enforcing or checking it. Prose is not a control. With the table as a value,
 * {@code AuthApiContractTest} can read the authority this class enforces for a path and compare it
 * with the {@code x-required-authority} the contract publishes for the operation on that path, so the
 * two cannot drift apart while both continue to compile.</p>
 *
 * <p>Assumptions: the group-to-authority translation lives in {@code common-lib} and is imported
 * rather than restated, so all eight contexts agree about what an administrator is. Restating it per
 * service would let two services disagree about one claim, and the disagreement would surface as an
 * authorization gap rather than as a compile error.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The path the load balancer's health check and the container's own probe read.
     *
     * <p>Assumptions: this path is reachable WITHOUT a token, and it has to be. The target group
     * polls it with no credentials of any kind, so a chain that required one would fail every health
     * check and the task would be replaced continuously while being perfectly healthy. Only the
     * health group is opened; the remaining actuator endpoints stay behind the chain.</p>
     */
    public static final String HEALTH_PATH = "/actuator/health/**";

    /**
     * The sign-on path, reachable without a token.
     *
     * <p>Assumptions: this operation cannot require a token, because it is the operation that issues
     * one. That is the only reason it is open, and the exception is stated here as a named constant
     * rather than as a wildcard over the whole {@code /auth} prefix - a prefix-wide exemption would
     * silently open the five user-administration operations, which live under the same prefix.</p>
     */
    public static final String SIGNON_PATH = "/api/v1/auth/signon";

    /**
     * The sign-on challenge path, reachable without a token.
     *
     * <p>Assumptions: a caller answering a challenge has no token yet - obtaining one is what the
     * exchange is for - so this path is open for the same reason sign-on is. It carries its own
     * protection instead: the pool issues a single-use session value bound to the one exchange, so
     * possession of that value, not authentication, is what admits the request. The edge agrees, this
     * being one of the three deliberately unauthenticated route keys in
     * {@code infra/modules/api-gateway-http/variables.tf}.</p>
     */
    public static final String CHALLENGE_PATH = "/api/v1/auth/challenge";

    /**
     * The token-renewal path, reachable without a token.
     *
     * <p>Assumptions: a caller renewing a token set presents the refresh token rather than the access
     * token, and the access token it renews may already have expired -- so requiring a valid one here
     * would make renewal reachable only while it was unnecessary. The contract declares the operation
     * with an empty security requirement for the same reason, and the edge agrees, this being the
     * third of the three deliberately unauthenticated route keys in
     * {@code infra/modules/api-gateway-http/variables.tf}.</p>
     *
     * <p>Trade-offs: opening a third path widens the unauthenticated surface of this service by one
     * operation, which is accepted because the alternative is worse in both directions: gating it
     * makes renewal impossible after expiry, and leaving it ungated but unnamed -- which is what a
     * missing entry here produced -- let it fall through to the catch-all rule, where it required a
     * valid access token without any rule saying so.</p>
     */
    public static final String REFRESH_PATH = "/api/v1/auth/refresh";

    /**
     * The user collection path, matched exactly.
     *
     * <p>Assumptions: the collection and the subtree beneath it are two patterns rather than one,
     * because a single-segment wildcard does not match an empty segment and a subtree pattern does not
     * match the collection itself. Writing only the subtree form would leave the list and create
     * operations falling through to the catch-all rule, which requires authentication but no
     * particular group - so both would have been reachable by any ordinary user.</p>
     */
    public static final String USER_COLLECTION_PATH_PATTERN = "/api/v1/auth/users";

    /**
     * Every path beneath the user collection.
     *
     * <p>Assumptions: the pattern carries the deployed {@code /api/v1} prefix because the service
     * receives it. No servlet context path is configured in this module's {@code application.yml} and
     * neither the load balancer nor the HTTP API rewrites a path, so a pattern written without the
     * prefix would match no request at all - and a security rule that matches nothing does not fail
     * closed, it falls through to the weaker rule beneath it.</p>
     */
    public static final String USER_SUBTREE_PATH_PATTERN = "/api/v1/auth/users/**";

    /**
     * The authority rules of this context, most specific first.
     *
     * <p>Assumptions: the order of this list is the order the rules are evaluated in, matching how a
     * filter chain evaluates its matchers, so a reader may take the first match as the effective one.
     * Both rules here name the same authority, so order is not load-bearing between them; the list is
     * still ordered because {@link #requiredAuthorityFor(String)} reports the first match and a
     * reader must be able to predict which that is.</p>
     */
    private static final List<AuthorityRule> AUTHORITY_RULES = List.of(
            new AuthorityRule(USER_COLLECTION_PATH_PATTERN, JwtRoleConverter.ADMIN_AUTHORITY),
            new AuthorityRule(USER_SUBTREE_PATH_PATTERN, JwtRoleConverter.ADMIN_AUTHORITY));

    /**
     * The paths this chain leaves open, in the order they are applied.
     *
     * <p>Assumptions: this list is closed at three and every entry is an exact path rather than a
     * pattern. An open path is the one kind of rule whose mistakes are invisible in testing - it
     * makes requests succeed - so each is named in full and none admits a subtree. The three are
     * exactly the three operations the contract declares with an empty security requirement, and
     * exactly the three public route keys the edge publishes; a contract test asserts the two sets
     * agree, so neither can gain a member without the other.</p>
     */
    private static final List<String> UNAUTHENTICATED_PATHS =
            List.of(SIGNON_PATH, CHALLENGE_PATH, REFRESH_PATH);

    /**
     * The parser that turns a declared pattern into a matcher.
     *
     * <p>Assumptions: this is the same pattern implementation the filter chain matches with, so a
     * rule this class reports for a path is the rule the chain applies to that path. Using a
     * different matcher here - a plain prefix comparison, say - would make the reported answer and
     * the enforced answer two different things, which is precisely the drift this table exists to
     * foreclose.</p>
     */
    private static final PathPatternParser PATTERN_PARSER = PathPatternParser.defaultInstance;

    /**
     * One route-to-authority rule.
     *
     * <p>Assumptions: the authority named is the MINIMUM one a caller must hold, not the only one. An
     * administrator is not denied an ordinary-user operation, so a rule naming the ordinary-user
     * authority admits both groups; the widening is applied once, in
     * {@link #acceptedAuthorities(String)}, rather than by listing both groups on every rule where a
     * reader would have to check that all of them agree.</p>
     *
     * @param pathPattern the path pattern this rule governs, in the same syntax the filter chain
     *     matches with; never {@code null} and never blank
     * @param requiredAuthority the minimum authority a caller must hold to reach a matching path, one
     *     of the two constants {@code JwtRoleConverter} declares; never {@code null}
     */
    public record AuthorityRule(String pathPattern, String requiredAuthority) {

        /**
         * Rejects a rule that could not be enforced.
         *
         * @param pathPattern the path pattern this rule governs, validated to be non-null and
         *     non-blank
         * @param requiredAuthority the minimum authority a caller must hold, validated to be one of
         *     the two the shared converter recognises
         * @throws NullPointerException if either component is {@code null}
         * @throws IllegalArgumentException if the pattern is blank, or if the authority is not one the
         *     shared converter recognises, because a rule naming an authority no token can carry would
         *     deny every request while reading as though it authorised some
         */
        public AuthorityRule {
            Objects.requireNonNull(pathPattern, "pathPattern must not be null");
            Objects.requireNonNull(requiredAuthority, "requiredAuthority must not be null");
            if (pathPattern.isBlank()) {
                throw new IllegalArgumentException("pathPattern must not be blank");
            }
            if (!JwtRoleConverter.ADMIN_AUTHORITY.equals(requiredAuthority)
                    && !JwtRoleConverter.USER_AUTHORITY.equals(requiredAuthority)) {
                throw new IllegalArgumentException(
                        "requiredAuthority must be one of the two authorities the shared converter"
                                + " recognises; received \"" + requiredAuthority + "\"");
            }
        }
    }

    /**
     * Returns the authority rules this context enforces, in evaluation order.
     *
     * @return the ordered rules; never {@code null} and never empty, and immutable
     */
    public static List<AuthorityRule> authorityRules() {
        return AUTHORITY_RULES;
    }

    /**
     * Returns the paths this context leaves reachable without a token.
     *
     * @return the exact open paths; never {@code null} and immutable
     */
    public static List<String> unauthenticatedPaths() {
        return UNAUTHENTICATED_PATHS;
    }

    /**
     * Reports the authority a caller must hold to reach one concrete request path.
     *
     * <p>Assumptions: an open path reports {@code null} exactly as an unmatched path does, because
     * neither requires a group. The two are distinguished by {@link #unauthenticatedPaths()}, which a
     * caller that needs to tell them apart consults instead; folding the distinction into this method
     * would make its answer mean two things.</p>
     *
     * @param requestPath the concrete path of a request, beginning with a solidus; must not be
     *     {@code null}
     * @return the minimum authority required, or {@code null} when no rule matches, which means the
     *     path is open or falls through to the chain's catch-all
     * @throws NullPointerException if {@code requestPath} is {@code null}
     */
    public static String requiredAuthorityFor(String requestPath) {
        Objects.requireNonNull(requestPath, "requestPath must not be null");
        PathContainer path = PathContainer.parsePath(requestPath);
        for (AuthorityRule rule : AUTHORITY_RULES) {
            PathPattern pattern = PATTERN_PARSER.parse(rule.pathPattern());
            if (pattern.matches(path)) {
                return rule.requiredAuthority();
            }
        }
        return null;
    }

    /**
     * Widens one required authority into the set of authorities that satisfy it.
     *
     * <p>Assumptions: an administrator satisfies a rule that requires the ordinary-user authority.
     * The baseline agrees: {@code SEC-USR-TYPE} at {@code app/cpy/CSUSR01Y.cpy} line 22 admits two
     * values and an administrator reached the ordinary screens through the same menu graph.</p>
     *
     * @param requiredAuthority the minimum authority from a rule; must not be {@code null}
     * @return the authorities that satisfy it, as the chain builder expects them; never {@code null}
     *     and never empty
     */
    private static String[] acceptedAuthorities(String requiredAuthority) {
        if (JwtRoleConverter.ADMIN_AUTHORITY.equals(requiredAuthority)) {
            return new String[] {JwtRoleConverter.ADMIN_AUTHORITY};
        }
        return new String[] {JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY};
    }

    /**
     * Builds the filter chain from the rule table.
     *
     * <p>Assumptions: sessions are STATELESS. The baseline is pseudo-conversational and carries its
     * continuity in a structure the client echoes; the migrated form carries identity in the token
     * and selection context in the request path, so there is nothing left for a server-side session
     * to hold. Permitting one would reintroduce the sticky routing that horizontal scaling exists to
     * avoid - and on this context specifically it would also give the sign-on operation somewhere to
     * put state, which is exactly what the migration removed.</p>
     *
     * <p>Trade-offs: cross-site request forgery protection is disabled, which for a
     * cookie-authenticated application would be a defect. It is not one here: every authenticated
     * request carries a bearer token that a browser does not attach automatically, so the
     * confused-deputy condition the protection defends against cannot arise, and the two open paths
     * are protected by a credential in the body rather than by anything ambient.</p>
     *
     * <p>Assumptions: the catch-all requires authentication rather than denying outright. A path this
     * service does not publish reaches no handler and answers 404, and answering 403 for it instead
     * would tell an unauthenticated caller which paths exist.</p>
     *
     * @param http the chain builder; must not be {@code null}
     * @param authenticationConverter the token-to-authentication translation; must not be
     *     {@code null}
     * @return the configured chain, never {@code null}
     * @throws Exception when the chain cannot be built, which the builder declares
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
            JwtAuthenticationConverter authenticationConverter) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(HEALTH_PATH).permitAll();
                    // WHY : Assumptions: the open paths are applied BEFORE the authority rules, so
                    //       that an exact open path cannot be shadowed by a broader rule declared
                    //       above it. Neither open path lies under the user collection, so no
                    //       shadowing is possible today; ordering it this way keeps that true if a
                    //       rule is ever widened.
                    for (String openPath : UNAUTHENTICATED_PATHS) {
                        requests.requestMatchers(openPath).permitAll();
                    }
                    for (AuthorityRule rule : AUTHORITY_RULES) {
                        requests.requestMatchers(rule.pathPattern())
                                .hasAnyAuthority(acceptedAuthorities(rule.requiredAuthority()));
                    }
                    requests.anyRequest().authenticated();
                })
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter)));
        return http.build();
    }

    /**
     * Adapts the shared group-to-authority translation into the converter the resource server
     * expects.
     *
     * <p>Assumptions: the shared converter maps a token to AUTHORITIES, while the resource server's
     * contract is a token to an AUTHENTICATION. The adapter supplies the missing half rather than the
     * shared class implementing the wider contract, which keeps the shared class free of any
     * dependency on the resource-server module - {@code common-lib} is on the class path of every
     * context, including ones that expose no web endpoint at all.</p>
     *
     * <p>Assumptions: the two group names are read from configuration and passed to the shared
     * converter rather than left implicit. The converter compares them with its own compiled
     * constants and refuses to start on a mismatch, so this is the point at which a deployed pool
     * whose groups were renamed becomes a startup failure instead of a service that authenticates
     * every request and authorizes none.</p>
     *
     * @param configuredAdminGroupName the administrator group name from runtime configuration; must
     *     equal {@link JwtRoleConverter#ADMIN_AUTHORITY}
     * @param configuredUserGroupName the ordinary-user group name from runtime configuration; must
     *     equal {@link JwtRoleConverter#USER_AUTHORITY}
     * @return the authentication converter, never {@code null}
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(
            @Value("${carddemo.security.cognito.admin-group-name}") String configuredAdminGroupName,
            @Value("${carddemo.security.cognito.user-group-name}") String configuredUserGroupName) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                new JwtRoleConverter(configuredAdminGroupName, configuredUserGroupName));
        return converter;
    }

    /**
     * Builds the decoder the resource server authenticates every request with, so that a presented
     * token is checked for its KIND, its minting client and its scope and not only for its signature,
     * issuer and time window.
     *
     * <p>Assumptions: this is the bean this module's {@code application.yml} already states its three
     * {@code carddemo.security.jwt} keys are read by. One Cognito user pool signs two token kinds
     * with one key - an access token, whose {@code token_use} claim is {@code access}, and an identity
     * token, whose is {@code id} - so without this validator an identity token, which exists to
     * describe a user to a client and never to authorize an API call, satisfied every check the
     * issuer property performed. On this context the consequence would be the widest available: the
     * five operations behind it create, alter and delete the rows that decide who is an
     * administrator.</p>
     *
     * <p>Assumptions: the framework's own validators are composed IN rather than replaced. The
     * issuer-and-time validator is obtained from the framework's factory, so a release that adds a
     * default check gains it here too, and this bean adds checks and removes none.</p>
     *
     * <p>Alternatives Considered: the framework's {@code audiences} property, which is the obvious
     * declarative route and is wrong for this provider. A Cognito access token carries no audience
     * claim at all - the client identity travels in {@code client_id} - so an audience validator
     * would reject every access token the sign-on flow issues while accepting the identity tokens
     * this bean exists to refuse. It would invert the control.</p>
     *
     * @param issuerUri the pool's issuer location, read from the same property the framework would
     *     have used so that one value configures both key resolution and the issuer check
     * @param expectedTokenUse the token kind a presented token must declare; the configured value is
     *     {@code access}
     * @param expectedClientId the app client identity a presented token must name
     * @param requiredScope the scope a presented token must carry
     * @return the decoder, carrying the framework's issuer and time validation plus this migration's
     *     token-kind, client and scope validation; never {@code null}
     * @throws IllegalStateException if the configured token kind is not the one the shared validator
     *     enforces, because a deployment that asked for a different kind would be silently given the
     *     access-token check instead of the one it configured
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${carddemo.security.jwt.expected-token-use}") String expectedTokenUse,
            @Value("${carddemo.security.jwt.expected-client-id}") String expectedClientId,
            @Value("${carddemo.security.jwt.required-scope}") String requiredScope) {
        // WHY : Assumptions: the configured token kind is CHECKED against the one the shared
        //       validator enforces rather than passed to it, because that validator takes no such
        //       argument - it enforces the access kind by construction. Reading the property and
        //       ignoring it would let a deployment set a different value and believe it took effect.
        if (!CognitoAccessTokenValidator.ACCESS_TOKEN_USE.equals(expectedTokenUse)) {
            throw new IllegalStateException(
                    "carddemo.security.jwt.expected-token-use must be \""
                            + CognitoAccessTokenValidator.ACCESS_TOKEN_USE
                            + "\"; the shared token validator enforces that kind and no other,"
                            + " so a different value here would not be honoured");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new CognitoAccessTokenValidator(expectedClientId, List.of(requiredScope))));
        return decoder;
    }

    /**
     * Registers the shared correlation filter, which the component scan cannot reach.
     *
     * <p>Assumptions: the shared kernel sits under {@code com.carddemo.common}, outside the scan root
     * of this context, so every shared component is registered deliberately rather than discovered.
     * The filter is registered here because its position in the request pipeline is part of its
     * contract, and this class is what defines that pipeline.</p>
     *
     * @return the correlation filter, never {@code null}
     */
    @Bean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }
}
