package com.carddemo.reporting.config;

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
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.IpAddressAuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Configures who may reach this context's endpoints, and which tokens are accepted at all.
 *
 * <p>This is the migrated form of the authorization check the baseline performs by reading the user type
 * out of the session structure it echoes between screen turns, declared at
 * {@code app/cpy/COCOM01Y.cpy} lines 19 to 44 with its two condition names for the administrator and
 * the ordinary user. That structure is storage the CLIENT hands back, so a client could in principle
 * assert its own user type; here the equivalent claim is signed by the identity provider and validated
 * on every request, so it cannot be asserted by the caller at all.</p>
 *
 * <p>Assumptions: this class carries TWO of the three responsibilities its siblings carry -- the filter
 * chain and the group-to-authority conversion -- and deliberately not the third. The decoder that
 * installs the provider-specific token checks is {@code JwtDecoderConfig} in this same package, which
 * predates this class and already reads the same {@code carddemo.security.jwt.*} keys every sibling
 * reads. Declaring a second decoder bean here would make which of the two the resource server used
 * depend on bean ordering rather than on anything written down, so the split is stated at both ends
 * instead.</p>
 *
 * <p>Refactoring Rationale: an earlier revision of this module had a decoder but no filter chain, so
 * the framework's default chain decided authorization -- which authenticates every valid token and
 * authorizes every path. The report request and the statement download both read ledger and account data
 * through the read-only views, so a chain that admitted an unauthenticated caller would disclose them.
 * This class is where the module gained an authorization decision of its own.</p>
 *
 * <p>Assumptions: no BUSINESS route here is administrator-only. The baseline reaches the report request
 * screen {@code app/cbl/CORPT00C.cbl} from the MAIN menu, {@code app/cbl/COMEN01C.cbl}, which both user
 * types reach, so restricting it to an administrator would REMOVE a capability an ordinary user has
 * today. Transformation rule T9 permits no behavioural change without a documented divergence, and
 * tightening a control is still a change. The two management endpoints are the one rule granted by
 * network position rather than by authority, and neither is a business route -- see
 * {@link #METRIC_SCRAPE_PATH}.</p>
 *
 * @see CognitoAccessTokenValidator
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The path the load balancer's health check and the container's own probe read.
     *
     * <p>Assumptions: this path is reachable WITHOUT a token, and it has to be. The target group polls
     * it with no credentials of any kind, so a chain that required one would fail every health check and
     * the task would be replaced continuously while being perfectly healthy. Only the health group is
     * opened; the remaining actuator endpoints stay behind the chain.</p>
     */
    public static final String HEALTH_PATH = "/actuator/health/**";

    /**
     * The management namespace, named so an assertion can hold the two rules apart.
     *
     * <p>Assumptions: this constant NAMES the namespace and no rule below grants the namespace as a
     * whole. {@link #HEALTH_PATH} is permitted, the two endpoints this module publishes beyond health
     * are granted by network position, and what remains under this pattern reaches no handler -- so it
     * is left to the business rule rather than given a rule that would only ever answer for a path that
     * does not exist.</p>
     */
    public static final String MANAGEMENT_PATH = "/actuator/**";

    /**
     * The build-identity management path, reachable only from inside the task.
     *
     * <p>Assumptions: this path and the scrape path below are named EXPLICITLY rather than covered by an
     * {@code /actuator/**} pattern, because they are the two management endpoints this module's exposure
     * list admits beyond health -- {@code include: health,info,prometheus} in {@code application.yml} --
     * and each describes the deployment to whatever reaches the port. Naming them is what lets the chain
     * state a rule for them instead of letting them inherit the business rule below.</p>
     */
    public static final String BUILD_IDENTITY_PATH = "/actuator/info";

    /**
     * The metric scrape path, reachable only from inside the task.
     *
     * <p>Assumptions: the only configured consumer is TASK-LOCAL. The collector sidecar scrapes
     * {@code https://127.0.0.1:<container-port>} at {@code metrics_path: /actuator/prometheus} every
     * sixty seconds -- {@code infra/modules/ecs-service/main.tf} -- and that scrape configuration
     * carries no authorization header at all, so it can present no token.</p>
     *
     * <p>Refactoring Rationale: an earlier revision of this class covered the whole
     * {@code /actuator/**} namespace with one rule requiring the administrator authority, on the ground
     * that a metrics scraper should present an operator token. It was corrected here because this
     * service's exposure list really does publish {@code prometheus} and the configured scraper really
     * does send no header, so that rule would not have narrowed who may read metrics -- it would have
     * stopped the only consumer that exists, silently, since a failed scrape is not a failed request
     * anybody sees. The alternative is recorded rather than dropped because the instinct behind it was
     * right: before either revision this namespace was authorized by {@code authenticated()} alone.</p>
     */
    public static final String METRIC_SCRAPE_PATH = "/actuator/prometheus";

    /**
     * The loopback addresses the task-local collector can reach this service from.
     *
     * <p>Assumptions: both families are listed because the address a container resolves loopback to is a
     * property of its network stack rather than of this configuration. Fargate tasks carry an IPv4
     * address under the {@code awsvpc} mode the service module uses, so the IPv4 form is the one expected
     * in practice; the IPv6 form is included so a stack presenting {@code ::1} does not silently lose its
     * metrics.</p>
     *
     * <p>Trade-offs: a range rather than an authority is what grants telemetry, so this one rule cannot
     * be audited from a token. It is accepted because both alternatives are worse: requiring a token
     * breaks the only consumer that exists, and admitting any authenticated principal -- which this chain
     * did before it named these paths -- grants the two endpoints to every token the identity provider
     * will issue, including one carrying no CardDemo group at all.</p>
     */
    private static final List<String> LOOPBACK_RANGES = List.of("127.0.0.1/32", "::1/128");

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
     * Builds the authorization decision that admits only the task-local loopback addresses.
     *
     * <p>Assumptions: the ranges are combined with {@code anyOf} rather than tested in sequence, so a
     * stack presenting either family satisfies the rule and neither has to be guessed at configuration
     * time.</p>
     *
     * @return a manager granting access from any address in {@link #LOOPBACK_RANGES}; never {@code null}
     */
    private static AuthorizationManager<RequestAuthorizationContext> loopbackOnly() {
        @SuppressWarnings("unchecked")
        AuthorizationManager<RequestAuthorizationContext>[] byRange = LOOPBACK_RANGES.stream()
                .map(IpAddressAuthorizationManager::hasIpAddress)
                .toArray(AuthorizationManager[]::new);

        return AuthorizationManagers.anyOf(byRange);
    }

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
     * Builds the filter chain.
     *
     * <p>Assumptions: sessions are STATELESS. The baseline is pseudo-conversational and carries its
     * continuity in a structure the client echoes; the migrated form carries identity in the token and
     * selection context in the request path, so there is nothing left for a server-side session to
     * hold. Permitting one would reintroduce the sticky routing that horizontal scaling exists to
     * avoid.</p>
     *
     * <p>Trade-offs: cross-site request forgery protection is disabled, which for a cookie-authenticated
     * application would be a defect. It is not one here: every request authenticates with a bearer token
     * that a browser does not attach automatically, so the confused-deputy condition the protection
     * defends against cannot arise. Leaving it enabled would reject every non-browser client --
     * including the load balancer -- for no gain.</p>
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
                        // WHY : Assumptions: the two management endpoints are granted by NETWORK
                        //       POSITION and not by authority, because their only configured consumer
                        //       is the task-local collector sidecar, which presents no token. Declared
                        //       before the business rule so a request of any method to either endpoint
                        //       is judged by position alone.
                        .requestMatchers(BUILD_IDENTITY_PATH, METRIC_SCRAPE_PATH)
                        .access(loopbackOnly())
                        .anyRequest()
                        .access(businessAccess()))
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
     * Adapts the shared group-to-authority translation into the converter the resource server expects.
     *
     * <p>Assumptions: the shared converter maps a token to AUTHORITIES, while the resource server's
     * contract is a token to an AUTHENTICATION. The adapter supplies the missing half rather than the
     * shared class implementing the wider contract, which keeps the shared class free of any dependency
     * on the resource-server module -- {@code common-lib} is on the class path of every context,
     * including ones that expose no web endpoint at all.</p>
     *
     * <p>Assumptions: the authorities the shared converter emits are used VERBATIM, with no role prefix
     * added. The identity provider's group names are already the vocabulary this application authorizes
     * against, so prefixing them would create a second spelling of each group and every path expression
     * would have to know which spelling it was matching.</p>
     *
     * <p>Assumptions: the two group names are read from configuration and passed to the shared converter
     * rather than left implicit. The converter compares them with its own compiled constants and refuses
     * to start on a mismatch, so this is the point at which a deployed pool whose groups were renamed
     * becomes a startup failure instead of a service that authenticates every request and authorizes
     * none.</p>
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
}
