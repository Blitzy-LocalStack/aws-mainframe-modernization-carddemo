package com.carddemo.card.config;

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
 * Configures who may reach this context's endpoints, and which of them requires the administrator
 * authority.
 *
 * <p>This is the migrated form of the authorization check the baseline performs by reading the user
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
 * is the defect this class was authored to close: the administrative card read - the one operation
 * that returns an unmasked primary account number - was described as restricted in the published
 * contract's prose and in a tag name, and nothing anywhere enforced or checked it. Prose is not a
 * control. With the table as a value, {@code CardApiContractTest} can read the required authority
 * this class enforces for a path and compare it with the {@code x-required-authority} the contract
 * publishes for the operation on that path, so the two cannot drift apart while both continue to
 * compile.</p>
 *
 * <p>Alternatives Considered: expressing the same rules only as chained {@code requestMatchers}
 * calls, which is the idiomatic form and is what the sibling authorization context does. Rejected
 * here because a chained call is reachable only by standing up an application context and issuing a
 * request through it, and this module has no application class yet, so the rules would have been
 * unverifiable at the exact checkpoint at which they were introduced. The chain is still built from
 * the table, so there is one statement of the rules and not two.</p>
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
     * The one path that renders a primary account number in full.
     *
     * <p>Assumptions: the pattern carries the deployed {@code /api/v1} prefix because the service
     * receives it. No servlet context path is configured in this module's {@code application.yml} and
     * neither the load balancer nor the HTTP API rewrites a path, so a pattern written without the
     * prefix would match no request at all - and a security rule that matches nothing does not fail
     * closed, it falls through to the weaker rule beneath it.</p>
     *
     * <p>Assumptions: the segment matched by the single asterisk is the opaque card selector, never a
     * card number. Nothing in this pattern depends on that, but a reader comparing it against the
     * published contract should know that the value in that position is a twenty-two-character token
     * and that a sixteen-digit account number is refused by the contract's own shape check before any
     * rule here is consulted.</p>
     */
    public static final String ADMIN_CARD_PATH_PATTERN = "/api/v1/cards/*/unmasked";

    /**
     * The collection path of the card resource, matched exactly.
     *
     * <p>Assumptions: the collection and the subtree beneath it are two patterns rather than one,
     * because a single-segment wildcard does not match an empty segment and a subtree pattern does
     * not match the collection itself. Writing only the subtree form would leave the list operation
     * falling through to the catch-all rule.</p>
     */
    public static final String CARD_COLLECTION_PATH_PATTERN = "/api/v1/cards";

    /**
     * Every path beneath the card collection.
     *
     * <p>Assumptions: this is ordered AFTER the administrative pattern in the table below and the
     * order is load-bearing. A subtree pattern also matches the administrative path, so placing it
     * first would grant the ordinary-user authority to the one operation that discloses a full
     * account number - the failure would be silent, because both patterns are valid and both rules
     * authorise something.</p>
     */
    public static final String CARD_SUBTREE_PATH_PATTERN = "/api/v1/cards/**";

    /**
     * The authority rules of this context, most specific first.
     *
     * <p>Assumptions: the order of this list is the order the rules are evaluated in, matching how a
     * filter chain evaluates its matchers, so a reader may take the first match as the effective one.
     * The administrative rule precedes the two general card rules for the reason recorded on
     * {@link #CARD_SUBTREE_PATH_PATTERN}.</p>
     */
    private static final List<AuthorityRule> AUTHORITY_RULES = List.of(
            new AuthorityRule(ADMIN_CARD_PATH_PATTERN, JwtRoleConverter.ADMIN_AUTHORITY),
            new AuthorityRule(CARD_COLLECTION_PATH_PATTERN, JwtRoleConverter.USER_AUTHORITY),
            new AuthorityRule(CARD_SUBTREE_PATH_PATTERN, JwtRoleConverter.USER_AUTHORITY));

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
     * <p>Assumptions: the authority named is the MINIMUM one a caller must hold, not the only one.
     * An administrator is not denied a cardholder operation, so a rule naming the ordinary-user
     * authority admits both groups; the widening is applied once, in
     * {@link #acceptedAuthorities(String)}, rather than by listing both groups on every rule where a
     * reader would have to check that all of them agree.</p>
     *
     * @param pathPattern the path pattern this rule governs, in the same syntax the filter chain
     *     matches with; never {@code null} and never blank
     * @param requiredAuthority the minimum authority a caller must hold to reach a matching path,
     *     one of the two constants {@code JwtRoleConverter} declares; never {@code null}
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
         * @throws IllegalArgumentException if the pattern is blank, or if the authority is not one
         *     the shared converter recognises, because a rule naming an authority no token can carry
         *     would deny every request while reading as though it authorised some
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
     * Reports the authority a caller must hold to reach one concrete request path.
     *
     * <p>Assumptions: the FIRST matching rule wins, which is how a filter chain behaves, so this
     * answer is the effective one rather than the union of every rule that could match.</p>
     *
     * @param requestPath the concrete path of a request, beginning with a solidus; must not be
     *     {@code null}
     * @return the minimum authority required, or {@code null} when no rule matches, which means the
     *     path falls through to the chain's catch-all and requires authentication without requiring
     *     any particular group
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
     * values and an administrator reached the cardholder screens through the same menu graph, so
     * denying an administrator a card read would withdraw a capability the baseline granted.</p>
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
     * avoid.</p>
     *
     * <p>Trade-offs: cross-site request forgery protection is disabled, which for a
     * cookie-authenticated application would be a defect. It is not one here: every request
     * authenticates with a bearer token that a browser does not attach automatically, so the
     * confused-deputy condition the protection defends against cannot arise. Leaving it enabled would
     * reject every non-browser client - including the load balancer - for no gain.</p>
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
                    // WHY : Assumptions: the rules are applied in list order, so the chain's
                    //       first-match evaluation and the order declared on AUTHORITY_RULES are
                    //       the same order. Iterating a map instead would make the effective
                    //       precedence depend on iteration order, which for the administrative
                    //       rule is the difference between guarding an unmasked account number and
                    //       not.
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
     * <p>Assumptions: the authorities the shared converter emits are used VERBATIM, with no role
     * prefix added. The identity provider's group names are already the vocabulary this application
     * authorizes against, so prefixing them would create a second spelling of each group and every
     * path expression would have to know which spelling it was matching.</p>
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
     * <p>Refactoring Rationale: this bean exists because the issuer property alone is not sufficient
     * for the provider this migration adopts, and this module's configuration declared that property
     * and nothing else. One Cognito user pool signs two token kinds with one key - an access token,
     * whose {@code token_use} claim is {@code access}, and an identity token, whose is {@code id} -
     * so an identity token, which exists to describe a user to a client and never to authorize an API
     * call, satisfied every check the declared configuration performed. It is also the token a
     * browser most readily has to hand. On this service the consequence was specific: the
     * administrative read is the one operation in the migration that returns an unmasked primary
     * account number. The three keys this reads are declared in this module's {@code application.yml},
     * whose own rationale states that they are read here.</p>
     *
     * <p>Assumptions: the framework's own validators are composed IN rather than replaced. The
     * issuer-and-time validator is obtained from the framework's factory, so a release that adds a
     * default check gains it here too, and this bean adds checks and removes none. Writing the issuer
     * or time checks by hand would duplicate logic the framework maintains and is the way a
     * hand-built decoder silently drops a check.</p>
     *
     * <p>Alternatives Considered: the framework's {@code audiences} property, which is the obvious
     * declarative route and is wrong for this provider. A Cognito access token carries no audience
     * claim at all - the client identity travels in {@code client_id} - so an audience validator would
     * reject every access token the sign-on flow issues while accepting the identity tokens this bean
     * exists to refuse. It would invert the control.</p>
     *
     * <p>Alternatives Considered: a separate configuration class for the decoder, which is what the
     * reporting context does. Rejected here because this package's charter fixes its inventory at
     * three classes and names them, so a fourth file would sit outside that closed set; the decoder
     * belongs to the same request-authentication concern this class already owns.</p>
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
     * of this context, so every shared component is registered deliberately. This package's charter
     * assigns this registration to this class because the filter's position in the request pipeline
     * is part of its contract.</p>
     *
     * @return the correlation filter, never {@code null}
     */
    @Bean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }
}
