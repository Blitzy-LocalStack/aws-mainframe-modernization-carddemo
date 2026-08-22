package com.carddemo.transaction.config;

import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.security.CognitoAccessTokenValidator;
import com.carddemo.common.security.JwtRoleConverter;
import jakarta.servlet.DispatcherType;
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
 * Configures who may reach this context's endpoints, and which tokens are accepted at all.
 *
 * <p>This is the migrated form of the authorization check the baseline performs by reading the user type
 * out of the session structure it echoes between screen turns, declared at
 * {@code app/cpy/COCOM01Y.cpy} lines 19 to 44 with its two condition names for the administrator and
 * the ordinary user. That structure is storage the CLIENT hands back, so a client could in principle
 * assert its own user type; here the equivalent claim is signed by the identity provider and validated
 * on every request, so it cannot be asserted by the caller at all.</p>
 *
 * <p>Assumptions: the baseline performs no transaction-level authorization of its own, which is what
 * makes the signed claim a change of mechanism rather than a tightening of policy. All four transactions
 * of this context are defined with resource-level and command-level security switched off --
 * {@code app/csd/CARDDEMO.CSD} carries {@code RESSEC(NO) CMDSEC(NO)} at lines 426, 436, 446 and 344 --
 * so the user-type byte in that echoed structure is the entire access control the baseline applies to
 * these four paths. The Java reaches the same admit-or-refuse outcome from a claim the caller cannot
 * author, and the divergence is documented.</p>
 *
 * <p>Assumptions: no route here is administrator-only, business or otherwise, and that is a preserved
 * property rather than an oversight. The baseline reaches transaction list, view, add and bill payment
 * from the MAIN menu, {@code app/cbl/COMEN01C.cbl}, so all four are ordinary-user capabilities. The
 * chain below therefore grants exactly three things: the health group is public, the metric scrape path
 * is granted by NETWORK POSITION rather than by authority, and every other request -- including any
 * remaining {@code /actuator} path -- falls to the business catch-all. What this context does tighten
 * is the TOKEN it accepts, for a specific reason: these are the operations whose misuse moves money, so
 * a request admitted on an identity token would post against a real account.</p>
 *
 * <p>Alternatives Considered: registering the shared correlation filter here, ordered before the
 * authentication filter. Rejected because the ordering it would buy is already held --
 * {@code common-lib} auto-configures one {@code CorrelationIdFilter} at
 * {@code Ordered.HIGHEST_PRECEDENCE + 1}, ahead of the servlet position the whole security chain
 * occupies -- and because a second registration is not idempotent by construction: the kernel guards its
 * own registration on the NAME of its registration bean, so a differently named bean here would not
 * suppress it. The position matters specifically because a request refused with 401 or 403 never reaches
 * a controller, so ordering the filter first is what makes an authentication refusal correlatable at
 * all.</p>
 *
 * <p>Assumptions: no BUSINESS route here is administrator-only, and that is a preserved property rather
 * than an oversight -- the management namespace at {@link #MANAGEMENT_PATH} answers no business question,
 * and the one endpoint this module publishes inside it is granted by NETWORK POSITION rather than by any
 * authority, for the reason recorded on {@link #METRIC_SCRAPE_PATH}. The baseline reaches transaction
 * list, view, add and bill payment from
 * the MAIN menu,
 * {@code app/cbl/COMEN01C.cbl}, so all four are ordinary-user capabilities today. What this context
 * does tighten is the TOKEN it accepts, and it tightens it for a specific reason: these are the
 * operations whose misuse moves money, so a request admitted on an identity token would post against a
 * real account.</p>
 *
 * <h2>Why the correlation filter is not registered here</h2>
 *
 * <p>Assumptions: the shared correlation filter has to run BEFORE anything in this chain can refuse a
 * request, and it already does, so this class declares no registration for it. That was verified rather
 * than assumed: {@code common-lib} ships
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, which names
 * {@code CardDemoCommonAutoConfiguration}, and that class contributes a registration bean placing one
 * {@code CorrelationIdFilter} instance at {@code Ordered.HIGHEST_PRECEDENCE + 1} -- ahead of the servlet
 * position the whole security chain occupies, and therefore ahead of the bearer-token filter.</p>
 *
 * <p>Alternatives Considered: registering the filter here, ordered before the authentication filter. It
 * is rejected because the ordering it would buy is already held, and because a second registration is
 * not idempotent by construction: the kernel guards its own registration on the NAME of its
 * registration bean, so a differently named bean here does not suppress it and the framework adapts any
 * unwrapped filter bean it finds into a further registration of its own. The filter carries a
 * once-per-request guard, a request attribute set on entry and removed only by the pass that set it, so
 * the duplicate would mint no second identity and would be invisible -- which is the reason to withdraw
 * it deliberately rather than leave a reader to discover it.</p>
 *
 * <p>Assumptions: the position matters specifically because a request refused with 401 or 403 never
 * reaches a controller. Were the identity established after authentication, the failures an operator
 * most needs to trace would be the ones with no identifier on the response and no entry in the logging
 * context. Ordering it first is what makes an authentication refusal correlatable at all, and the
 * refusal bodies this chain renders are written by handlers that read the same request.</p>
 *
 * <h2>What this class deliberately does not declare</h2>
 *
 * <p>Assumptions: three configuration classes present in other contexts of this repository are absent
 * here, each for a reason specific to this bounded context rather than by oversight. There is no SQS
 * configuration because this context consumes no queue: the module declares no
 * {@code io.awspring.cloud} and no {@code software.amazon.awssdk} dependency and
 * {@code application.yml} defines no {@code spring.cloud.aws} key, so the types would not resolve --
 * that configuration belongs to the authorization and batch contexts, which do consume queues. There is
 * no batch configuration because the module declares no batch starter and no {@code spring.batch} key;
 * the nightly chain is the batch context's. And there is no HTTP-client configuration because the
 * client used for the cross-context cross-reference read is built with its timeouts in the service
 * layer, at the point that owns the call, rather than as a context-wide bean here.</p>
 *
 * <p>Assumptions: the data source and the connection pool are declared by the sibling
 * {@code DataSourceConfig} and the API document metadata by the sibling {@code OpenApiConfig}, while the
 * money codec module and the error advice are contributed by the SHARED KERNEL rather than by anything in
 * this package: {@code com.carddemo.common.CardDemoCommonAutoConfiguration} registers both as beans, so
 * every context gets one identical registration and no module can drift into a second. Attributing them
 * to {@code OpenApiConfig} would send a reader looking for the money serialisation rule to the file that
 * describes the document instead of the file that installs the rule. This file owns the chain, the
 * group-to-authority conversion and the decoder, and nothing else, so the four files of this package do
 * not overlap and a reader looking for one of those beans has exactly one place to look.</p>
 *
 * <h2>Assumptions: the starters this class needs are not inherited, and the two declarations differ</h2>
 *
 * <p>{@code common-lib} marks four starters {@code optional} -- web, validation, security and
 * oauth2-resource-server -- and an optional dependency is not transitive, so none of them reaches this
 * module through the shared kernel and {@code services/transaction-service/pom.xml} re-declares all
 * four. The two this class consumes are not, however, interchangeable, and the difference is a
 * property of the published dependency graph rather than a matter of style: the
 * {@code spring-boot-starter-oauth2-resource-server} pom declares
 * {@code spring-boot-starter-security} as a direct dependency of its own, so the resource-server
 * declaration alone would place both on this module's compile path.</p>
 *
 * <p>Trade-offs: the direct {@code spring-boot-starter-security} declaration is therefore redundant
 * against that graph, and it is kept deliberately. What it buys is that the two capabilities this file
 * uses are each stated where a reader of the module's own pom can see them, rather than one of them
 * being a fact about a third party's pom that this module does not control; what it costs is one
 * declaration that a dependency analyser will report as removable. The analyser is right about the
 * mechanics and the declaration stays for the explicitness.</p>
 *
 * <p>Assumptions: the failure modes of removing each are asymmetric, and stating them concretely is
 * what keeps the redundancy above from being read as an oversight. Removing the
 * <b>resource-server</b> starter withdraws the artifact carrying
 * {@code org.springframework.security.oauth2.jwt} and
 * {@code org.springframework.security.oauth2.server.resource.authentication}, both of which this file
 * imports by name for its decoder, its validator delegation and its authentication converter, so the
 * module fails to <b>COMPILE</b> and the build stops at this file. Removing the redundant direct
 * <b>security</b> declaration changes nothing observable at all, because the resource-server starter
 * still brings the same artifact in transitively -- the chain builder, the session policy and the
 * authorization managers all remain resolvable. Neither removal produces a
 * {@code NoClassDefFoundError} at context start: that failure shape needs a type present at compile
 * time and absent at run time, which is what a {@code provided} scope or a shaded runtime image can
 * produce and what a removed compile-scoped starter cannot.</p>
 *
 * @see CognitoAccessTokenValidator
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Builds the authorization decision that admits only the task-local loopback addresses.
     *
     * <p>Assumptions: the ranges are combined with {@code anyOf} rather than tested in sequence, so a
     * stack presenting either address family satisfies the rule and neither has to be guessed at
     * configuration time.</p>
     *
     * <p>Refactoring Rationale: this method is VISIBLE rather than private, for the same reason
     * {@link #businessAccess()} is: {@code SecurityConfigTest} applies the decision object this chain
     * installs instead of assembling an equivalent one from {@link #LOOPBACK_RANGES}. A test that
     * rebuilt the manager would agree with itself while the installed rule widened, and the rule this
     * one replaced widened in exactly that way without any test noticing.</p>
     *
     * @return a manager granting access from any address in {@link #LOOPBACK_RANGES}; never {@code null}
     */
    public static AuthorizationManager<RequestAuthorizationContext> loopbackOnly() {
        @SuppressWarnings("unchecked")
        AuthorizationManager<RequestAuthorizationContext>[] byRange = LOOPBACK_RANGES.stream()
                .map(IpAddressAuthorizationManager::hasIpAddress)
                .toArray(AuthorizationManager[]::new);

        return AuthorizationManagers.anyOf(byRange);
    }

    /**
     * The path the load balancer's health check and the container's own probe read.
     *
     * <p>Assumptions: exactly two components read this path and NEITHER of them can present a token, so
     * requiring one here would break container orchestration and load-balancer registration at the same
     * time. This module's {@code Dockerfile} declares a {@code HEALTHCHECK} that reads
     * {@code /actuator/health} over the loopback address, so a refused probe marks the container itself
     * unhealthy and it is replaced; the load balancer's target group polls the same path to decide
     * whether the task receives traffic, so a refused poll withdraws a task that is serving correctly.
     * One rule therefore governs two independent failure paths, and neither presents as a rejected
     * business request. Only the health group is opened; the remaining actuator endpoints stay behind the
     * chain.</p>
     */
    public static final String HEALTH_PATH = "/actuator/health/**";

    /**
     * The management namespace, reachable only from inside the task.
     *
     * <p>Assumptions: this pattern is the last entry of {@link #OPERATOR_PATHS} and is therefore
     * MATCHED by the chain, granted by network position alongside the scrape endpoint named
     * individually below. It is matched after {@link #HEALTH_PATH}, so the more specific health pattern
     * keeps its own permit and the uncredentialed probes still reach it; everything else under
     * {@code /actuator} describes the deployment rather than answering a business question, so it
     * belongs to whoever is inside the container rather than to every holder of a business authority.</p>
     *
     * <p>Refactoring Rationale: this constant previously existed and was matched by NOTHING. Its own
     * documentation said so -- "no rule below grants the namespace as a whole" -- on the reasoning that
     * whatever the namespace covered beyond the scrape endpoint "reaches no handler", so leaving it to
     * the catch-all cost nothing. That reasoning was wrong on a matter of fact:
     * {@code application-dev.yml} publishes {@code flyway} and {@code threaddump} beyond the base list,
     * both of which DO reach a handler, and both were therefore authorized by the catch-all -- which
     * admits either business group, so every ordinary user of this context could read the migration
     * history of the ledger schema and a full stack dump of the running task.</p>
     *
     * <p>Assumptions: matching the NAMESPACE rather than enumerating those two ids is deliberate, and it
     * is what makes the rule survive a profile it was not written against. The exposure list is a
     * per-profile property that REPLACES rather than extends the inherited one, so an id can be added in
     * one file without this one being edited; a namespace rule authorizes whatever that file adds, while
     * a list of ids would silently omit it.</p>
     *
     * <p>Alternatives Considered: refusing the namespace outright with {@code denyAll()} instead of
     * granting it to the loopback address. Rejected because it would make the dev profile's own exposure
     * list unreadable by anything, including an operator with a shell inside the task, which is the only
     * consumer those two ids have -- publishing an endpoint and then denying every possible caller
     * states two contradictory intentions in two files. The loopback grant refuses every caller off the
     * box, which is the property the finding asked for.</p>
     */
    public static final String MANAGEMENT_PATH = "/actuator/**";

    /**
     * The metric scrape path, reachable only from inside the task.
     *
     * <p>Assumptions: this endpoint is named EXPLICITLY rather than covered by the
     * {@link #MANAGEMENT_PATH} namespace pattern, because it is one of the endpoints this
     * module's exposure list publishes beyond health and it describes the deployment to
     * whatever reaches the port. Naming it is what lets the chain state a rule for it instead
     * of letting it inherit one.</p>
     */
    public static final String METRIC_SCRAPE_PATH = "/actuator/prometheus";

    /**
     * The paths granted by network position, in the order the chain applies them.
     *
     * <p>Assumptions: the chain is BUILT from this list rather than the list describing the chain, so a
     * test can assert that the management namespace is covered and the assertion cannot pass while the
     * chain omits it. The defect this list closes was a constant naming the namespace with no rule
     * matching it, which is precisely a statement no test could hold to account.</p>
     *
     * <p>Assumptions: the scrape endpoint is retained ahead of the namespace even though both share one
     * decision, so that the one endpoint this module's base exposure list publishes beyond health is
     * readable here as a name instead of having to be inferred from a wildcard. The namespace entry is
     * last because it is the backstop for whatever a profile adds, not the statement of what the base
     * publishes.</p>
     */
    private static final List<String> OPERATOR_PATHS = List.of(METRIC_SCRAPE_PATH, MANAGEMENT_PATH);

    /**
     * The loopback addresses the task-local collector can reach this service from.
     *
     * <p>Assumptions: any consumer of the endpoints above is TASK-LOCAL, and none is configured
     * today. A scraper would reach {@code https://127.0.0.1:<container-port>} at
     * {@code /actuator/prometheus} from inside the task's own network namespace and would carry no
     * authorization header, so it could present no token -- which is why these paths are granted by
     * NETWORK POSITION rather than by authority. Refactoring Rationale: this named a collector
     * sidecar in {@code infra/modules/ecs-service/main.tf} as that consumer, scraping every sixty
     * seconds. The sidecar is WITHDRAWN -- it sat outside the frozen specification -- so the surface
     * is published with nothing collecting from it. The rule is unchanged by that withdrawal,
     * because a token-free consumer inside the namespace and no consumer at all both require
     * exactly the loopback restriction.</p>
     */
    private static final List<String> LOOPBACK_RANGES = List.of("127.0.0.1/32", "::1/128");

    /**
     * The authorities that satisfy the catch-all rule, in one place because the test reads the same list.
     *
     * <p>Assumptions: an administrator satisfies the rule as well as an ordinary user. The baseline agrees
     * -- {@code SEC-USR-TYPE} at {@code app/cpy/CSUSR01Y.cpy} line 22 admits exactly two values and an
     * administrator reached the ordinary screens through the same menu graph -- so this is not a
     * privilege-escalating shortcut but the two-value domain the baseline already had.</p>
     */
    public static final List<String> BUSINESS_AUTHORITIES =
            List.of(JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);

    /**
     * Returns the paths this context grants by network position, in the order the chain applies them.
     *
     * @return the ordered operator paths, the last of which is the management namespace; never
     *     {@code null} and immutable
     */
    public static List<String> operatorPaths() {
        return OPERATOR_PATHS;
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
     * <p>Assumptions: the management namespace is matched by {@link #operatorPaths()} ABOVE the
     * catch-all, so a diagnostic endpoint a profile publishes is authorized by network position rather
     * than inheriting the business grant. That ordering is the whole of the difference: the rule set is
     * otherwise unchanged, and no business route's authority moves.</p>
     *
     * <p>Alternatives Considered: ending this chain with {@code denyAll()} as the auth and card contexts
     * do. Rejected outright HERE, and the reason is structural rather than a difference of appetite:
     * those chains enumerate every published path in a rule table, so their catch-all governs only
     * unpublished paths, whereas this chain publishes no such table and authorizes all four business
     * operations from the catch-all itself. Denying there would refuse transaction list, view, add and
     * bill payment to every caller. The exposure that made denyAll attractive was the management
     * namespace, and the operator rule removes it without touching a business route.</p>
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
                // Trade-offs: no credential reaching this service travels in a cookie. Every
                //     request authenticates with a bearer token that a browser does not attach on its
                //     own, so the confused-deputy condition this protection defends against cannot
                //     arise here, while leaving it enabled would refuse every non-idempotent call the
                //     browser client makes.
                .csrf(csrf -> csrf.disable())
                // Assumptions: the zero transaction work area on all four transactions of this
                //     context means the baseline kept no server-side per-conversation storage beyond
                //     the structure the client echoed back, so statelessness reproduces what was there
                //     rather than simplifying it -- and it is what lets tasks scale out behind the
                //     load balancer with no sticky routing.
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // WHY : (1) ⚠️ Refactoring Rationale: the container's own ERROR dispatch is taken
                        //       off this chain's authorization, and it was on it. A servlet forward to the
                        //       error path keeps the ORIGINAL request method and arrives with no
                        //       authentication -- the bearer-token filter is a once-per-request filter and
                        //       skips an error dispatch -- so a refusal this service rendered before any
                        //       handler ran was re-judged by the catch-all below and answered 401 instead of
                        //       the status it had already decided. A caller owed 415, 413 or 406 therefore
                        //       received 401 on a path it never addressed, and a client with token-refresh
                        //       logic would refresh a token that was never the problem.
                        // WHY : (2) Assumptions: this became reachable when this document began publishing
                        //       406, 413 and 415. Those three are exactly the refusals raised before a
                        //       handler is chosen, which is when the container forwards to its error path,
                        //       so publishing them without this rule would have made the defect a routine
                        //       outcome rather than a rare one.
                        // WHY : (3) Assumptions: the rule matches the DISPATCHER TYPE and not the path, so
                        //       a caller addressing /error directly still arrives on a REQUEST dispatch and
                        //       is judged by the rules below exactly as it is today. A path permit would
                        //       open that path to any caller, and the container's error body is not a shape
                        //       src/main/resources/openapi/transaction-api.yaml publishes anywhere.
                        // WHY : (4) Alternatives Considered: narrowing this chain to the REQUEST dispatch
                        //       alone, which has the same effect here. Rejected because it silently
                        //       withdraws authorization from ASYNC dispatches too, so an asynchronous
                        //       handler added later would run outside every rule below. The auth, account,
                        //       card, authorization and reference contexts resolve it this same way, which
                        //       is what keeps the seven chains comparable.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HEALTH_PATH).permitAll()
                        // WHY : Refactoring Rationale: this rule is built from OPERATOR_PATHS, whose
                        //       last entry is the management NAMESPACE. The preceding revision named
                        //       the scrape endpoint alone and left the rest of the namespace to the
                        //       catch-all, so the two ids application-dev.yml adds -- flyway and
                        //       threaddump -- were authorized by the business rule below, which
                        //       admits every ordinary user of this context. Matching the namespace is
                        //       what confines them to the container they run in.
                        // WHY : Assumptions: every path in the list is granted by NETWORK POSITION
                        //       and not by authority, because the only configured consumer of any of
                        //       them is the task-local collector sidecar, which presents no token,
                        //       and an operator with a shell inside the task. See LOOPBACK_RANGES.
                        .requestMatchers(OPERATOR_PATHS.toArray(String[]::new))
                        .access(loopbackOnly())
                        // WHY : Assumptions: the catch-all stays businessAccess() and does NOT become
                        //       denyAll, which is the opposite of the choice the auth and card
                        //       contexts make, because this chain has no per-path rule table: every
                        //       business route of this context is authorized BY the catch-all, so
                        //       denying there would refuse all four operations. What made denyAll the
                        //       right rule in those contexts -- that the only surface reaching the
                        //       catch-all was the management namespace -- is what this rule above now
                        //       removes here.
                        .anyRequest()
                        .access(businessAccess()))
                .oauth2ResourceServer(server -> server
                        // Assumptions: the bearer-token filter answers a request whose token was
                        //     absent, expired or malformed BEFORE the exception stage below is reached,
                        //     and it resolves neither handler from the application context, so both must
                        //     be set here as well. Setting only the exception stage would leave the more
                        //     common of the two refusals -- a missing token -- rendered as a bodyless
                        //     status, which every published contract of this service contradicts.
                        .authenticationEntryPoint(ApiErrorSecurityHandlers.entryPoint(clock))
                        .accessDeniedHandler(ApiErrorSecurityHandlers.accessDeniedHandler(clock))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter)))
                // Refactoring Rationale: the shared handlers render the ApiError body that this
                //     service's OpenAPI document declares for 401 and 403. Without them the framework
                //     default answers with a status and a WWW-Authenticate header and no body at all,
                //     because a refusal decided by the filter chain never reaches a controller and so
                //     never reaches the shared @RestControllerAdvice. The 401 entry point delegates to
                //     the framework's bearer-token entry point first, so the challenge header the OAuth
                //     2.0 contract requires is composed exactly as before and only the body is added.
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
     * <p>Alternatives Considered: the framework's own {@code audiences} property, which is the obvious
     * declarative route and is wrong for this provider. A Cognito ACCESS token carries no audience claim
     * at all -- the client identity travels in {@code client_id} instead -- so an audience validator
     * would reject every access token the sign-on flow issues while accepting exactly the identity
     * tokens the delegated validator exists to refuse. It would invert the control.</p>
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

        // Assumptions: the configured kind is asserted against the shared validator's compiled
        //     constant instead of being handed to it. The validator decides the token KIND from a
        //     constant because a configurable kind could be configured away; the property exists so
        //     the requirement is visible where an operator reads configuration, and this assertion is
        //     what stops the visible value and the enforced value drifting apart.
        if (!CognitoAccessTokenValidator.ACCESS_TOKEN_USE.equals(expectedTokenUse)) {
            throw new IllegalStateException("carddemo.security.jwt.expected-token-use must be \""
                    + CognitoAccessTokenValidator.ACCESS_TOKEN_USE + "\"");
        }

        // Assumptions: a blank client id is refused rather than tolerated. The shared validator
        //     reads blank as "skip this check", which is correct only for a caller that has decided
        //     the audience validator pins the client instead. No service here has, so a blank value
        //     would mean the check was dropped by omission with nothing saying so.
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
