package com.carddemo.authorization.config;

import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.security.CognitoAccessTokenValidator;
import com.carddemo.common.security.JwtRoleConverter;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.IpAddressAuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Configures who may reach this context's endpoints.
 *
 * <p>This is the migrated form of the authorization check the baseline performs by reading the user type
 * out of the session structure it passes between screen turns, declared at
 * {@code app/cpy/COCOM01Y.cpy} lines 19 to 44 with its two condition names for the administrator and the
 * ordinary user. That structure is storage the CLIENT echoes back, so a client could in principle assert
 * its own user type; here the equivalent claim is signed by the identity provider and validated on every
 * request, so it cannot be asserted by the caller at all. The improvement is deliberate and is recorded
 * in {@code docs/architecture/security-and-identity.md}.</p>
 *
 * <p>Assumptions: the group-to-authority translation lives in {@code common-lib} and is imported rather
 * than restated, so all eight contexts agree about what an administrator is. Restating it per service
 * would let two services disagree about one claim, and the disagreement would surface as an
 * authorization gap rather than as a compile error.</p>
 *
 * <p>Assumptions: this class has the same three responsibilities in every context of this repository --
 * a filter chain, the group-to-authority conversion, and the decoder that installs the token checks the
 * issuer alone does not make. It reads the same five property keys in every context, so the chains
 * cannot diverge in what they accept. Only the route table is specific to this context.</p>
 *
 * <p>Refactoring Rationale: this class had the chain and the conversion and not the decoder, so a
 * validly signed IDENTITY token authenticated a request here -- the framework's issuer-and-time
 * validation accepts one, and the fraud route's authority check would then be applied to whatever groups
 * that token happened to carry. The decoder bean below is what closes that, and it is the same bean the
 * sibling contexts declare rather than a variant written for this one.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The path the load balancer's health check and the container's own probe read.
     *
     * <p>Assumptions: this path is reachable WITHOUT a token, and it has to be. The target group polls it
     * with no credentials of any kind, so a chain that required one would fail every health check and the
     * task would be replaced continuously while being perfectly healthy. Only the health group is opened;
     * the remaining actuator endpoints stay behind the chain.</p>
     */
    public static final String HEALTH_PATH = "/actuator/health/**";

    /**
     * The management namespace, which is reachable only by an operator.
     *
     * <p>Assumptions: this pattern is declared AFTER {@link #HEALTH_PATH} in the rule set below, so the
     * health group keeps its own more specific rule and stays open. Everything else this service exposes
     * under {@code /actuator} -- {@code prometheus}, per the {@code management} block of {@code application.yml}
     * -- describes the deployment rather than answering a business question, so it belongs to the operator
     * rather than to every holder of a valid token.</p>
     *
     * <p>Assumptions: this pattern is a DOCUMENTED BOUNDARY rather than a rule the chain installs, and
     * the distinction is stated because reading it as a rule leads to the wrong conclusion about who may
     * scrape metrics. No {@code requestMatchers} call below names it. The one endpoint this service
     * exposes under the namespace beyond health is {@link #METRIC_SCRAPE_PATH}, and it is granted by
     * {@link #loopbackOnly()} -- a NETWORK-POSITION rule -- so the scraper presents no token at all and
     * an administrator token would neither be sent nor help. Anything else under the namespace falls to
     * the terminal {@code denyAll}. What this constant is FOR is the accompanying unit test, which
     * asserts that the pattern covers the scrape path and covers no business path, so a future edit that
     * widened it into the business surface fails a build rather than a request.</p>
     *
     * <p>Trade-offs: granting telemetry by address rather than by authority means that one rule cannot
     * be audited from a token, which is a real cost. Both alternatives are worse and were measured
     * against the deployment rather than assumed: the configured consumer is the task-local collector
     * sidecar declared in {@code infra/modules/ecs-service/main.tf}, whose scrape configuration carries
     * no authorization header, so requiring the administrator group would not narrow who may read
     * metrics -- it would stop the only consumer that exists, and silently, because a failed scrape is
     * not a failed request anybody sees. Admitting any authenticated principal instead would grant the
     * endpoint to every token the identity provider will issue, including one carrying no CardDemo
     * group at all.</p>
     */
    public static final String MANAGEMENT_PATH = "/actuator/**";

    /**
     * The authorities that satisfy the catch-all rule, in one place because the test reads the same list.
     *
     * <p>Assumptions: an administrator satisfies the rule as well as an ordinary user. The baseline agrees
     * -- {@code SEC-USR-TYPE} at {@code app/cpy/CSUSR01Y.cpy} line 22 admits exactly two values and an
     * administrator reached the ordinary screens through the same menu graph -- so this is not a
     * privilege-escalating shortcut but the two-value domain the baseline already had.</p>
     *
     * <p>Refactoring Rationale: the pair is a named constant rather than two literals inside the chain
     * below, so the accompanying test can assert on the SAME list the chain enforces. Restating the pair
     * in the test would let the two drift and the test would keep passing while the rule narrowed to one
     * authority or widened to a third.</p>
     */
    public static final List<String> BUSINESS_AUTHORITIES =
            List.of(JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);

    /**
     * The authorization decision the catch-all rule installs, exposed so a test can exercise the object
     * the chain actually enforces.
     *
     * <p>Assumptions: this returns exactly what {@code hasAnyAuthority(...)} would have built. That
     * builder method is itself a one-line wrapper around
     * {@link AuthorityAuthorizationManager#hasAnyAuthority(String...)}, so naming the manager here and
     * passing it to {@code access(...)} changes nothing about the rule and everything about whether it
     * can be asserted: a test can invoke this manager directly, whereas a rule expressed only inside the
     * builder lambda is reachable only by standing up a servlet context and issuing a request.</p>
     *
     * <p>Trade-offs: {@code access(businessAccess())} reads less immediately than
     * {@code hasAnyAuthority(ADMIN, USER)} would. The cost is one indirection for a reader; the gain is
     * that the catch-all -- the rule every route not named above depends on -- is covered by a unit test
     * that needs no container, which is what stops it silently reverting to a weaker condition.</p>
     *
     * @return the manager that grants only a principal holding one of {@link #BUSINESS_AUTHORITIES},
     *     never {@code null}
     */
    public static AuthorizationManager<RequestAuthorizationContext> businessAccess() {
        return AuthorityAuthorizationManager.hasAnyAuthority(
                BUSINESS_AUTHORITIES.toArray(String[]::new));
    }

    /**
     * The paths that mark an authorization as fraudulent.
     *
     * <p>Assumptions: fraud marking is restricted to administrators because the baseline reaches its
     * fraud-marking program from the ADMINISTRATIVE menu only. Opening it to an ordinary user would grant
     * a capability the baseline never granted.</p>
     *
     * <p>Assumptions: the pattern carries the published {@code /api/v1} prefix because that is the path
     * this service actually RECEIVES, and this is the single most easily mis-set value in the class. Three
     * facts fix it and each was checked rather than assumed: no service in this repository sets
     * {@code server.servlet.context-path}, so Spring Security matches the full request path; the load
     * balancer rule forwards to the target without rewriting the path; and the environment roots
     * configure this target's path patterns as {@code /api/v1/authorizations} and
     * {@code /api/v1/authorizations/*}. An earlier revision of this constant omitted the prefix. That was
     * not a cosmetic error -- the pattern matched no request the service could ever receive, so fraud
     * marking fell through to the catch-all rule below, which at that time required only authentication,
     * and any authenticated caller, including an ordinary {@code carddemo-user}, could mark an
     * authorization fraudulent. That failure mode is why the catch-all is now {@code denyAll()}: a path
     * gate matching nothing can only be as dangerous as whatever the request falls through to, so making
     * the fall-through refuse everything bounds the damage of the next such mistake. It is
     * corrected rather than deleted here because a path gate that silently matches nothing is
     * indistinguishable from a correct one in review, and a reader who saw the earlier wording needs to
     * know which statement to trust.</p>
     *
     * <p>Trade-offs: the prefix is written literally rather than injected from configuration. Injecting it
     * would let the gate and the route drift apart through a property change, and a mismatch there fails
     * open in exactly the way described above; a literal makes the gate wrong only when someone edits this
     * line, where the reasoning is written down next to it.</p>
     */
    public static final String FRAUD_PATH_PATTERN = "/api/v1/authorizations/*/fraud";

    /**
     * The pending-authorization read surface, which the contract reserves to a CardDemo group.
     *
     * <p>Assumptions: the pattern covers the collection and every resource beneath it, which is the
     * whole of this context's published surface apart from the fraud route above --
     * {@code /api/v1/authorizations} and {@code /api/v1/authorizations/&#123;key&#125;} at lines 289
     * and 363 of {@code openapi/authorization-api.yaml}. Both carry
     * {@code x-required-authority: carddemo-user} at lines 332 and 391, and that document names THIS
     * class as the place the marker is enforced.</p>
     *
     * <p>Refactoring Rationale: this rule exists because the read surface previously fell through to a
     * catch-all requiring only authentication. The data behind it is financial -- an account's pending
     * authorizations, their amounts and their merchants -- and a token carrying no CardDemo group at
     * all satisfies {@code authenticated()}, so the declared authority was published but not
     * enforced.</p>
     */
    public static final String READ_PATH_PATTERN = "/api/v1/authorizations/**";

    /**
     * The metric scrape path, reachable only from inside the task.
     *
     * <p>Assumptions: this module's exposure list is {@code include: health,prometheus}, so the scrape
     * path is the ONE management endpoint beyond health that it admits -- there is no build-identity
     * endpoint to grant here, unlike the card and reference contexts. Its only configured consumer is
     * the task-local collector, which scrapes {@code 127.0.0.1:<container-port>} at
     * {@code infra/modules/ecs-service/main.tf} lines 154 to 172 and presents no authorization header,
     * so a rule requiring a token would break the scrape rather than secure it.</p>
     */
    public static final String METRIC_SCRAPE_PATH = "/actuator/prometheus";

    /**
     * The loopback addresses the task-local collector can reach this service from.
     *
     * <p>Assumptions: both families are listed because the address a container resolves loopback to is
     * a property of its network stack. The IPv4 form is the one Fargate presents under {@code awsvpc};
     * the IPv6 form is included so a stack answering {@code ::1} does not silently lose its
     * metrics.</p>
     */
    private static final List<String> LOOPBACK_RANGES = List.of("127.0.0.1/32", "::1/128");

    /**
     * Builds the filter chain.
     *
     * <p>Assumptions: sessions are STATELESS. The baseline is pseudo-conversational and carries its
     * continuity in a structure the client echoes; the migrated form carries identity in the token and
     * selection context in the request path, so there is nothing left for a server-side session to hold.
     * Permitting one would reintroduce the sticky routing that horizontal scaling exists to avoid.</p>
     *
     * <p>Trade-offs: cross-site request forgery protection is disabled, which for a cookie-authenticated
     * application would be a defect. It is not one here: every request authenticates with a bearer token
     * that a browser does not attach automatically, so the confused-deputy condition the protection
     * defends against cannot arise. Leaving it enabled would reject every non-browser client -- including
     * the load balancer and the queue-driven paths -- for no gain.</p>
     *
     * <p>Assumptions: the catch-all requires one of the two GROUP authorities rather than merely
     * requiring authentication. Those are not the same condition. {@link JwtRoleConverter} grants an
     * EMPTY authority set for a token whose {@code cognito:groups} claim is absent, is not a collection,
     * holds a non-textual entry, or names only groups this application does not recognise -- and every
     * one of those tokens is still fully authenticated, because it carries a valid signature from the
     * configured pool. A rule of {@code authenticated()} therefore admitted a principal that had been
     * granted nothing, which is the missing-authorization defect the baseline does not have: the session
     * structure at {@code app/cpy/COCOM01Y.cpy} lines 19 to 44 always carries one of exactly two user
     * types, so no reachable baseline state corresponds to a signed-on user belonging to neither.</p>
     *
     * <p>Alternatives Considered: leaving the catch-all as {@code authenticated()} and adding an explicit
     * rule per published path, which is the shape the auth and card contexts use because each of those
     * has a genuinely per-path authority split. Rejected here: the per-path table would restate the same
     * authority set once per route and a route added later would default to the weaker rule again.
     * Requiring the authority set in the catch-all makes the safe outcome the DEFAULT rather than
     * something each new route has to remember to opt into.</p>
     *
     * <p>Trade-offs: a path this service does not publish now answers 403 rather than 404 for a token
     * with no group, where a token WITH a group still receives 404. The leak is bounded to callers who
     * already hold a validly signed token for this pool and reveals nothing about which paths exist,
     * which is a smaller cost than admitting an unauthorized principal to every published route.</p>
     *
     * @param http the chain builder; must not be {@code null}
     * @param authenticationConverter the token-to-authentication translation; must not be {@code null}
     * @param clock the clock the rendered refusal bodies read their failure instant from; must not be
     *     {@code null}
     * @return the configured chain, never {@code null}
     * @throws Exception when the chain cannot be built, which the builder declares
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
            JwtAuthenticationConverter authenticationConverter, Clock clock) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HEALTH_PATH).permitAll()
                        // WHY : Assumptions: granted by NETWORK POSITION, not authority, because the
                        //       only configured consumer is the task-local collector and it presents
                        //       no token. See METRIC_SCRAPE_PATH.
                        .requestMatchers(METRIC_SCRAPE_PATH).access(loopbackOnly())
                        // WHY : Assumptions: the fraud rule stays FIRST because the chain matches in
                        //       declaration order and the read pattern below also covers this path.
                        //       Reversing the two would silently downgrade the administrative gate on
                        //       fraud marking to the read authority.
                        .requestMatchers(FRAUD_PATH_PATTERN)
                        .hasAuthority(JwtRoleConverter.ADMIN_AUTHORITY)
                        // WHY : Assumptions: EITHER group satisfies the read surface, which is the
                        //       authority model the contract publishes at its lines 266 to 275 --
                        //       carddemo-user means either group, so an administrator is not denied a
                        //       cardholder operation, while carddemo-admin excludes a user-only token.
                        .requestMatchers(READ_PATH_PATTERN)
                        .hasAnyAuthority(JwtRoleConverter.ADMIN_AUTHORITY,
                                JwtRoleConverter.USER_AUTHORITY)
                        // WHY : Assumptions: denyAll and NOT authenticated, so a valid token carrying
                        //       neither group reaches nothing. Every path this service serves is
                        //       granted by a rule above, so a route added without a rule fails closed.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(server -> server
                        // WHY : Assumptions: the bearer-token filter answers a request whose token was
                        //       absent, expired or malformed BEFORE the exception stage below is reached,
                        //       and it resolves neither handler from the application context, so both must
                        //       be set here as well. Setting only the exception stage would leave the more
                        //       common of the two refusals -- a missing token -- rendered as a bodyless
                        //       status, which every published contract of this service contradicts.
                        .authenticationEntryPoint(ApiErrorSecurityHandlers.entryPoint(clock))
                        .accessDeniedHandler(ApiErrorSecurityHandlers.accessDeniedHandler(clock))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter)))
                // WHY : Refactoring Rationale: the shared handlers render the ApiError body that this
                //       service's OpenAPI document declares for 401 and 403. Without them the framework
                //       default answers with a status and a WWW-Authenticate header and no body at all,
                //       because a refusal decided by the filter chain never reaches a controller and so
                //       never reaches the shared @RestControllerAdvice. The 401 entry point delegates to
                //       the framework's bearer-token entry point first, so the challenge header the OAuth
                //       2.0 contract requires is composed exactly as before and only the body is added.
                .exceptionHandling(ApiErrorSecurityHandlers.renderingRefusals(clock))
                .build();
    }


    /**
     * Builds the authorization manager that admits a request only from a loopback address.
     *
     * <p>Assumptions: composed from the framework's own address manager rather than written here, so
     * the range parsing is the implementation the framework tests and this class contributes only the
     * choice of ranges. {@code anyOf} makes the two families alternatives rather than requirements,
     * which is what a single-stack container needs.</p>
     *
     * <p>Alternatives Considered: a security-group rule instead. Rejected because a security group
     * cannot express a rule about a task's own loopback interface -- traffic that never leaves the task
     * is not subject to it. The scrape target is nevertheless loopback precisely so the endpoint is
     * never published through a security-group rule either, so the two narrowings compound rather than
     * substitute.</p>
     *
     * @return an authorization manager granting access from any configured loopback range, never
     *     {@code null}
     */
    private static AuthorizationManager<RequestAuthorizationContext> loopbackOnly() {
        @SuppressWarnings("unchecked")
        AuthorizationManager<RequestAuthorizationContext>[] byRange = LOOPBACK_RANGES.stream()
                .map(IpAddressAuthorizationManager::hasIpAddress)
                .toArray(AuthorizationManager[]::new);

        return AuthorizationManagers.anyOf(byRange);
    }

    /**
     * Adapts the shared group-to-authority translation into the converter the resource server expects.
     *
     * <p>Assumptions: the shared converter maps a token to AUTHORITIES, while the resource server's
     * contract is a token to an AUTHENTICATION. The adapter supplies the missing half rather than the
     * shared class implementing the wider contract, which keeps the shared class free of any dependency
     * on the resource-server module -- {@code common-lib} is on the class path of every context, including
     * ones that expose no web endpoint at all.</p>
     *
     * <p>Assumptions: the authorities the shared converter emits are used VERBATIM, with no role prefix
     * added. The identity provider's group names are already the vocabulary this application authorizes
     * against, so prefixing them would create a second spelling of each group and every path expression
     * would have to know which spelling it was matching.</p>
     *
     * <p>Assumptions: the two group names are read from configuration and passed to the shared
     * converter rather than left implicit. The converter compares them with its own compiled
     * constants and refuses to start on a mismatch, so this is the point at which a deployed pool
     * whose groups were renamed becomes a startup failure instead of a service that authenticates
     * every request and authorizes none. The values are declared in this service's
     * {@code application.yml}, where the defaults are the same two names
     * {@code infra/modules/cognito} fixes as non-overridable.</p>
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
     * Builds the decoder, with the provider-specific token checks delegated in behind the standard ones.
     *
     * <p>Assumptions: the decoder is declared here rather than left to the framework's own
     * auto-configuration, and that is the only way to add a validator. Setting an issuer location makes
     * the framework compose signature, issuer and time validation and offers no hook to extend the
     * composition, so a service that needs a fourth check has to build the decoder and compose the
     * validators itself. The standard set is composed FIRST and this addition second, so nothing is
     * replaced and no check is weakened -- a token still has to pass everything the framework would have
     * required.</p>
     *
     * <p>Alternatives Considered: the framework's own {@code audiences} property, which is the obvious
     * declarative route and is wrong for this provider. A Cognito ACCESS token carries no audience claim
     * at all -- the client identity travels in {@code client_id} instead -- so an audience validator
     * would reject every access token the sign-on flow issues while accepting exactly the identity
     * tokens the delegated validator exists to refuse. It would invert the control.</p>
     *
     * <p>Assumptions: the configured token-use value is asserted against the shared validator's own
     * compiled constant rather than passed to it. The validator decides the token KIND from a constant
     * because accepting a configurable kind would let a deployment configure the check away; the
     * property therefore exists so the requirement is visible in configuration, and this assertion is
     * what stops the visible value and the enforced value drifting apart.</p>
     *
     * @param issuerUri the user-pool issuer location the framework's standard validators are built
     *     from; must not be {@code null} or blank
     * @param expectedTokenUse the token kind this service accepts, which must equal
     *     {@link CognitoAccessTokenValidator#ACCESS_TOKEN_USE}
     * @param expectedClientId the app client id a token must name; must not be {@code null} or blank,
     *     because a blank value would silently skip the check
     * @param requiredScope the scope a token must carry
     * @return the decoder, never {@code null}
     * @throws IllegalStateException if {@code expectedTokenUse} does not name the access token, or if
     *     {@code expectedClientId} is blank
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${carddemo.security.jwt.expected-token-use}") String expectedTokenUse,
            @Value("${carddemo.security.jwt.expected-client-id}") String expectedClientId,
            @Value("${carddemo.security.jwt.required-scope}") String requiredScope) {

        // WHY : Assumptions: the configured kind is asserted against the shared validator's compiled
        //       constant instead of being handed to it. The validator decides the token KIND from a
        //       constant because a configurable kind could be configured away; the property exists so
        //       the requirement is visible where an operator reads configuration, and this assertion is
        //       what stops the visible value and the enforced value drifting apart.
        if (!CognitoAccessTokenValidator.ACCESS_TOKEN_USE.equals(expectedTokenUse)) {
            throw new IllegalStateException("carddemo.security.jwt.expected-token-use must be \""
                    + CognitoAccessTokenValidator.ACCESS_TOKEN_USE + "\"");
        }

        // WHY : Assumptions: a blank client id is refused rather than tolerated. The shared validator
        //       reads blank as "skip this check", which is correct only for a caller that has decided
        //       the audience validator pins the client instead. No service here has, so a blank value
        //       would mean the check was dropped by omission with nothing saying so.
        if (expectedClientId == null || expectedClientId.isBlank()) {
            throw new IllegalStateException(
                    "carddemo.security.jwt.expected-client-id must name the app client");
        }

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new CognitoAccessTokenValidator(expectedClientId, List.of(requiredScope))));
        return decoder;
    }
}
