package com.carddemo.account.config;

import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.security.CognitoAccessTokenValidator;
import com.carddemo.common.security.JwtRoleConverter;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * <p>Refactoring Rationale: this is the migrated form of the authorization check the baseline performs by
 * reading the user type out of the session structure it echoes between screen turns, declared at
 * {@code app/cpy/COCOM01Y.cpy} lines 19 to 44. The identity fields are the eight-character user
 * identifier at line 25 and the one-character user type at line 26, whose two condition names at lines 27
 * and 28 carry the administrator and ordinary-user values that map onto this application's two group
 * names. What was wrong with the old arrangement is structural rather than cosmetic: that structure is
 * storage the CLIENT hands back, so a client could in principle assert its own user type. Here the
 * equivalent claim is SIGNED by the identity provider and validated on every request, so the caller
 * cannot assert its own privilege at all. This is a genuine security improvement rather than a change of
 * transport, and it is the reason every rule below is written against an authority derived from the claim
 * instead of against any value the caller supplied.</p>
 *
 * <p>Assumptions: the two user-type values at lines 27 and 28 are QUOTED character literals, whereas the
 * two re-entry values of the very next field at lines 30 and 31 are BARE numerics. Anything deriving from
 * that record has to preserve the distinction rather than normalising the four onto one form, because a
 * quoted digit is not the number it resembles and it goes wrong quietly in either direction.</p>
 *
 * <p>Assumptions: this class has the same three responsibilities in every context of this repository --
 * a filter chain, the group-to-authority conversion, and the decoder that installs the token checks the
 * issuer alone does not make. It reads the same five property keys in every context, so the eight chains
 * cannot diverge in what they accept, and the shared classes it composes live in {@code common-lib}
 * rather than being restated per service. Only the route table below is specific to this context.</p>
 *
 * <p>Refactoring Rationale: an earlier revision of this repository declared those five keys in each
 * service's configuration and read none of them. The values were therefore documentation of an intent
 * rather than a control: a resource server configured with an issuer location alone validates the
 * signature, the issuer and the time window, and the identity token this provider mints from the same
 * signing key satisfies all three. This class is where the declared keys became effective.</p>
 *
 * <p>Assumptions: no BUSINESS route here is administrator-only, and the omission is deliberate. The
 * baseline reaches account view and account update from the MAIN menu, {@code app/cbl/COMEN01C.cbl},
 * which both user types reach, so restricting either operation to an administrator would REMOVE a
 * capability an ordinary user has today. Transformation rule T9 permits no behavioural change without a
 * documented divergence, and tightening a control is still a change. The management namespace is the one
 * exception, and it is not a business route -- see {@link #MANAGEMENT_PATH}.</p>
 *
 * <p>Assumptions: three of this context's operations are read by another CardDemo WORKLOAD rather than by
 * a signed-on user -- the card cross-reference lookup, the account read and the customer existence check,
 * which the pending-authorization consumer makes while handling a queue message. That message carries no
 * user and therefore no token, so those calls can present no group authority at all. They are NOT decided
 * by this chain: {@link InternalApiSecurityConfig} installs an earlier-ordered chain whose security
 * matcher names those paths exactly -- among the other internal reads it claims -- and which accepts only a
 * machine token, and the rules below are reached by everything else.</p>
 *
 * <p>Refactoring Rationale: the internal mechanism this chain used to carry has been withdrawn, and both
 * halves of the decision are recorded because the withdrawal was not a simplification. This chain
 * previously installed a bespoke workload-assertion filter and three either-or rules over the same three
 * paths -- an HMAC credential minted per request over its method and path, verified by code in this
 * repository. Two mechanisms for one hop is one too many, and the surviving one is a signed JWT whose
 * verification is the framework's {@code NimbusJwtDecoder} rather than ours: re-implementing expiry,
 * length and MAC checking is re-implementing audited code, which is the reason the shared kernel adds no
 * resilience library either. What the withdrawn form bought was replay protection bound into the
 * signature, and that property is not lost -- it is asserted on the VERIFYING side instead, because the
 * surviving chain's security matcher admits its token on the exact method-and-path pairs it enumerates and
 * nowhere else, so a captured token replayed against any other route of this service reaches this chain,
 * which knows nothing about it, and is refused. The replay property rests on the matcher being exact rather
 * than on its arity, which is why this sentence names no number: the arity rose when the customer reads were
 * matched there and the property did not change.</p>
 *
 * <p>Assumptions: an earlier-ordered chain is workable here only because the internal matcher names EXACT
 * method-and-path pairs rather than the {@code /api/v1/accounts/**} subtree. The account subtree is
 * legitimately reached by a signed-on user as well, and Spring Security serves a request with the first
 * chain whose matcher accepts it, so a subtree matcher would have captured every user request to that
 * subtree and refused it for carrying no machine token. Exact matchers are what keep both callers working.
 * Refactoring Rationale: this sentence counted the internal matcher's paths, and the count went stale when
 * the customer record read and the customer scan were matched there. The count is dropped rather than
 * raised -- the property that makes the ordering safe is exactness, not arity, and
 * {@code InternalApiSecurityConfig.internalPaths()} is the one place the addresses are enumerated.</p>
 *
 * <p>Alternatives Considered: forwarding the end user's token from the calling workload, which needs no
 * second chain and no second credential. Rejected twice over -- there is no user token in a queue message
 * to forward, and if there were, letting a cardholder's session authorize a cross-context read of master
 * records would grant that session an authority the migration's security mapping gives it nowhere
 * else.</p>
 *
 * <p>Alternatives Considered: publishing the internal paths through the public edge and relying on the
 * machine token alone to protect them. Rejected because the edge's route keys and the load balancer's path
 * patterns are separate lists, and the narrower of the two is the better place to stop a request that has
 * no business reaching the edge: the environment roots forward these paths on the INTERNAL load balancer
 * only, and {@code infra/modules/api-gateway-http} publishes no route key for either subtree.</p>
 *
 * <p>Assumptions: this chain does NOT register the shared correlation filter, and the omission is the
 * contract rather than an oversight. That filter reaches this context through
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, which the framework loads from the shared
 * module's registration resource and which places one instance across every request path at an order
 * ahead of this chain. Ordering ahead of the chain is exactly what makes the identifier present on an
 * authentication failure and an authorization denial as well as on a success, so the property this chain
 * relies on is already guaranteed by a registration it does not own. Its width is not arbitrary either:
 * the baseline analogue captures a 24-byte correlation identifier at
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl} line 55 and echoes it back on the reply at line 470, and the
 * HTTP filter carries the same width. Declaring a second registration here would give one filter two
 * registrations whose relative order is settled by bean ordering rather than by anything written down,
 * which is why this package's charter forbids it.</p>
 *
 * <p>Assumptions: this context is a resource server ONLY. It mints no token, runs no sign-on exchange and
 * holds no credential of any kind. The baseline's sign-on comparison at {@code app/cbl/COSGN00C.cbl} line
 * 223, and the plaintext field it compares against, belong to the authentication context and are out of
 * scope here; the branch that comparison guards is at lines 230 to 239 of that file. All this chain does
 * is accept a token another component minted, which is why the only credential-shaped things it names are
 * property placeholders.</p>
 *
 * <p>Assumptions: every issuer, client, group and scope value this class needs arrives from configuration
 * rather than from a literal, so nothing deployment-specific is committed. The baseline is a partial
 * precedent rather than a full one, and it is cited honestly: {@code app/app-vsam-mq/cbl/COACCT01.cbl}
 * declares all four of its queue names as blank-initialised fields at lines 92 to 96, but only the input
 * name is genuinely injected at run time, at lines 191 to 192 and line 197, while the reply and error
 * names are hard-coded at lines 198 and 294. Externalising every value is therefore an improvement on
 * that arrangement rather than a port of it.</p>
 *
 * <p>Alternatives Considered: neither a retry nor a circuit breaker is configured on this chain. The
 * application framework moved retry into its own core, so a declarative retry was available here with no
 * added library at all, and it was still rejected: a filter chain performs no outbound call, so there is
 * nothing on this path for a retry to re-attempt or for a breaker to open around, and adding either would
 * introduce a failure mode without removing one. The wider prohibition is asserted mechanically rather
 * than by agreement -- rule A5 of
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java} fails
 * the build if any CardDemo class so much as references a resilience library.</p>
 *
 * <p>Trade-offs: no cross-origin policy is declared here either, which to a reader expecting a
 * browser-facing service looks like an omission. It is deliberate. This service is reached through the
 * edge and the internal load balancer rather than directly from a browser origin, so the preflight a
 * cross-origin request would send never reaches this chain, and the component the browser actually
 * negotiates with is the edge. Declaring a policy here would additionally require naming an origin, and a
 * deployment-specific origin is precisely the kind of value this file keeps out of source. The cost
 * accepted is that the cross-origin posture has to be read at the edge rather than beside the rules it
 * would appear to accompany.</p>
 *
 * <p>Assumptions: no fifth configuration class joins this package on account of this chain, and no
 * chunk-oriented batch wiring belongs here at all. That starter is version-managed centrally but is
 * deliberately not declared by this module -- {@code services/account-service/pom.xml} records the
 * exclusion in prose at line 612 -- so the types such a class would reference are absent from this
 * module's compile classpath and the omission is enforced by the compiler rather than by convention.</p>
 *
 * <p>Trade-offs: the patterns below are stated as subtrees rather than as an enumeration of published
 * operations, and they have to be revisited in lockstep with this context's controllers and its published
 * contract at {@code services/account-service/src/main/resources/openapi/account-api.yaml}, neither of
 * which this file may author. A subtree pattern cannot express which verbs exist beneath it, so a route
 * added later inherits its rule silently. The compensating control is the catch-all: because it demands a
 * group authority rather than mere authentication, the rule a new route inherits is the safe one rather
 * than the permissive one.</p>
 *
 * @see CognitoAccessTokenValidator
 * @see InternalApiSecurityConfig
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
     * The path the load balancer's health check and the container's own probe read.
     *
     * <p>Assumptions: this path is reachable WITHOUT a token, and it has to be. The target group polls
     * it with no credentials of any kind, so a chain that required one would fail every health check and
     * the task would be replaced continuously while being perfectly healthy. Only the health group is
     * opened; the remaining actuator endpoints stay behind the chain.</p>
     */
    public static final String HEALTH_PATH = "/actuator/health/**";

    /**
     * The management namespace, which is reachable only by an operator.
     *
     * <p>Assumptions: this pattern is declared AFTER {@link #HEALTH_PATH} in the rule set below, so the
     * health group keeps its own more specific rule and stays open. Everything else this service exposes
     * under {@code /actuator} -- {@code info} and {@code prometheus}, per the {@code management} block of
     * {@code application.yml} -- describes the deployment rather than answering a business question, so it
     * belongs to the operator rather than to every holder of a valid token.</p>
     *
     * <p>Assumptions: this pattern is MATCHED by a rule of its own in the chain below, and that rule
     * DENIES. It is ordered after the three endpoints the module publishes -- health, and the two
     * granted by network position -- so each of those keeps its own more specific rule, and before
     * every business rule, so nothing under this namespace can reach one. What the rule answers for is
     * therefore the management addresses the exposure list withholds, which reach no handler today.</p>
     *
     * <p>Trade-offs: exposing a further management endpoint takes two edits rather than one -- the
     * exposure list in {@code application.yml}, and a rule naming the new path ahead of this one. That
     * is deliberate: the alternative left the namespace inheriting the business rule, so a newly
     * exposed endpoint became readable by every holder of a CardDemo group authority without anything
     * being decided or recorded.</p>
     */
    public static final String MANAGEMENT_PATH = "/actuator/**";

    /**
     * The build-identity management path, reachable only from inside the task.
     *
     * <p>Assumptions: this endpoint is named EXPLICITLY rather than covered by the
     * {@link #MANAGEMENT_PATH} namespace pattern, because it is one of the endpoints this
     * module's exposure list publishes beyond health and it describes the deployment to
     * whatever reaches the port. Naming it is what lets the chain state a rule for it instead
     * of letting it inherit one.</p>
     */
    public static final String BUILD_IDENTITY_PATH = "/actuator/info";

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
     * The loopback addresses the task-local collector can reach this service from.
     *
     * <p>Assumptions: the only configured consumer of the endpoints above is TASK-LOCAL. The
     * collector sidecar scrapes {@code https://127.0.0.1:<container-port>} at
     * {@code metrics_path: /actuator/prometheus} every sixty seconds --
     * {@code infra/modules/ecs-service/main.tf} -- and that scrape configuration carries no
     * authorization header at all, so it can present no token.</p>
     *
     * <p>Refactoring Rationale: an earlier revision covered the whole {@link #MANAGEMENT_PATH}
     * namespace with one rule requiring the administrator authority, on the ground that a metrics
     * scraper should present an operator token. It was corrected because the configured scraper
     * sends no header: that rule would not have narrowed who may read metrics, it would have stopped
     * the only consumer that exists, and silently, since a failed scrape is not a failed request
     * anybody sees. The alternative is recorded rather than dropped because the instinct behind it
     * was right -- before either revision this namespace was authorized by the catch-all alone.</p>
     *
     * <p>Trade-offs: a range rather than an authority is what grants telemetry, so this one rule
     * cannot be audited from a token. Both alternatives are worse: requiring a token breaks the only
     * consumer that exists, and admitting any authenticated principal grants these endpoints to every
     * token the identity provider will issue, including one carrying no CardDemo group at all.</p>
     *
     * <p>Assumptions: both address families are listed because the address a container resolves
     * loopback to is a property of its network stack. IPv4 is what Fargate presents under
     * {@code awsvpc}; the IPv6 form is included so a stack presenting {@code ::1} does not silently
     * lose its metrics.</p>
     */
    private static final List<String> LOOPBACK_RANGES = List.of("127.0.0.1/32", "::1/128");

    /**
     * The card cross-reference subtree, which only another CardDemo workload reads.
     *
     * <p>Assumptions: no user-facing screen reads this address. The baseline resolves a card to its
     * account inside {@code COPAUA0C} paragraph {@code 5100-READ-XREF-RECORD}, which runs on the
     * authorization path and nowhere near a terminal, so this subtree is granted to NO group. The one
     * internal address inside it is matched by {@link InternalApiSecurityConfig}'s earlier chain; the
     * rest of the subtree is denied by this one.</p>
     */
    public static final String CARD_XREF_PATH_PATTERN = "/api/v1/card-xrefs/**";

    /**
     * The customer subtree, which only another CardDemo workload reads.
     *
     * <p>Assumptions: the account-view screen renders customer fields, but it renders them through the
     * account read below rather than by addressing this subtree, so this subtree too is granted to no
     * group. Its baseline callers are {@code COPAUA0C} paragraph {@code 5300-READ-CUST-RECORD} and the
     * batch reader {@code app/cbl/CBCUS01C.cbl}, neither of which runs anywhere near a terminal, and each
     * address they become is matched by {@link InternalApiSecurityConfig}'s earlier chain.</p>
     */
    public static final String CUSTOMER_PATH_PATTERN = "/api/v1/customers/**";

    /**
     * The account subtree, which BOTH a signed-on user and another CardDemo workload read.
     *
     * <p>Assumptions: this one subtree is legitimately reached two ways, and the two are separated by
     * CHAIN rather than by an either-or rule. The baseline reaches account view from the main menu at
     * {@code app/cbl/COMEN01C.cbl}, which both user types reach, and {@code COPAUA0C} paragraph
     * {@code 5200-READ-ACCT-RECORD} reads the same master record to decide an authorization -- so the
     * operation is the same whether the caller is a person or the posting decision. What differs is the
     * credential, and {@link InternalApiSecurityConfig} matches the machine caller's exact path ahead of
     * this chain, leaving the rest of the subtree to the catch-all's business authorities below.</p>
     *
     * <p>Assumptions: this constant is retained although no rule below names it, because it states the
     * subtree's shape once for the tests that assert which addresses fall inside it. Deleting it would
     * move that statement into each test.</p>
     *
     * <p>Alternatives Considered: a second, workload-only address for the same record, so that each rule
     * named one caller. Rejected because it would mean two published contracts and two handlers for one
     * query, and the two would drift the first time a field was added to either.</p>
     */
    public static final String ACCOUNT_PATH_PATTERN = "/api/v1/accounts/**";

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
     * has a genuinely per-path authority split. Rejected here: this context has no such split, so the
     * per-path table would restate one authority set once per route and a route added later would default
     * to the weaker rule again. Requiring the authority set in the catch-all makes the safe outcome the
     * DEFAULT rather than something each new route has to remember to opt into.</p>
     *
     * <p>Trade-offs: a path this service does not publish now answers 403 rather than 404 for a token
     * with no group, where a token WITH a group still receives 404. The leak is bounded to callers who
     * already hold a validly signed token for this pool and reveals nothing about which paths exist,
     * which is a smaller cost than admitting an unauthorized principal to every published route.</p>
     *
     * <p>Assumptions: the end-user decoder is injected by NAME and handed to the resource server
     * explicitly, rather than left to be resolved by type. This context holds TWO beans of that type --
     * this class's own {@link #jwtDecoder} for end-user tokens and {@link InternalApiSecurityConfig}'s
     * {@code internalTokenDecoder} for the machine token -- and the resource-server configurer resolves
     * an unspecified decoder by type alone, which with two candidates and no primary among them fails
     * while the context is being built. Naming it here is what keeps this deployable startable, and it
     * matches what the internal chain already does with its own qualified decoder.</p>
     *
     * @param http the chain builder; must not be {@code null}
     * @param endUserTokenDecoder the decoder validating an END-USER token, named so it is never confused
     *     with the internal chain's machine-token decoder; must not be {@code null}
     * @param authenticationConverter the token-to-authentication translation; must not be {@code null}
     * @param clock the clock the rendered refusal bodies read their failure instant from; must not be
     *     {@code null}
     * @return the configured chain, never {@code null}
     * @throws Exception when the chain cannot be built, which the builder declares
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
            @Qualifier("jwtDecoder") JwtDecoder endUserTokenDecoder,
            JwtAuthenticationConverter authenticationConverter, Clock clock) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // WHY : Purpose: the container's ERROR dispatch is admitted before any other rule,
                        //       so that a request whose response could not be written is answered by the
                        //       error page rather than by this chain's catch-all. The catch-all requires a
                        //       group authority, and the error page is not a path any rule above names, so
                        //       without this rule the caller received a refusal on /error -- a path it
                        //       never addressed -- in place of the failure it provoked.
                        // WHY : Measured: that refusal is 401 rather than 403, even for a caller whose
                        //       original request presented a valid group token, and the reason matters
                        //       when reading a log. The framework's authentication filters extend
                        //       OncePerRequestFilter, whose shouldNotFilterErrorDispatch returns true, so
                        //       BearerTokenAuthenticationFilter is skipped on an error dispatch and the
                        //       rules are reached with NO principal; the authorization filter is not one of
                        //       those and does run. Neutralising this rule and re-running
                        //       SecurityChainDispatchTest fails exactly the two end-user error-dispatch
                        //       cases, both with 401.
                        // WHY : Assumptions: the rule matches the DISPATCHER TYPE and not the error path's
                        //       name, because the deployment owns that mapping and this configuration does
                        //       not. A path-based permit would additionally let any caller address the error
                        //       page directly and provoke the container's own error body, which no
                        //       operation in openapi/account-api.yaml declares; matching the dispatcher
                        //       type leaves a direct request to that path refused by the catch-all exactly
                        //       as before.
                        // WHY : Assumptions: it is stated here even though carddemo-common-defaults.yml
                        //       registers the security filter for REQUEST and ASYNC only, which keeps a
                        //       deployed chain from seeing an error dispatch at all. A chain must state its
                        //       own security intent: a sliced or hand-wired context builds this chain
                        //       WITHOUT that property, and the property is a registration detail a later
                        //       deployment could widen without anyone re-reading this file.
                        // WHY : Trade-offs: this rule does NOT decide the error dispatches of the internal
                        //       machine-token addresses. Those are claimed by the earlier-ordered chain in
                        //       InternalApiSecurityConfig, which matches them on their ORIGINAL address, so
                        //       each dispatch is governed by the rules of the chain that admitted the
                        //       request itself. Both halves are needed: this one keeps an end-user error
                        //       page off this chain's catch-all, and that one keeps an internal caller's
                        //       error page off a rule that demands a Cognito group its credential cannot
                        //       carry.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HEALTH_PATH).permitAll()
                        // WHY : Assumptions: the management endpoints this module publishes beyond
                        //       health are granted by NETWORK POSITION and not by authority,
                        //       because their only configured consumer is the task-local collector
                        //       sidecar, which presents no token. See LOOPBACK_RANGES.
                        .requestMatchers(BUILD_IDENTITY_PATH, METRIC_SCRAPE_PATH)
                        .access(loopbackOnly())
                        // WHY : Assumptions: the management namespace is matched HERE, after the three
                        //       endpoints this module publishes have each been given their own rule and
                        //       BEFORE any business rule, and it is denied. What reaches it is every
                        //       management address other than those three, which is every address the
                        //       exposure list withholds -- so the rule answers for paths that reach no
                        //       handler today and exists for the ones that might tomorrow.
                        //
                        //       Refactoring Rationale: without this rule the namespace fell through to
                        //       the catch-all, so exposing any further management endpoint -- an
                        //       environment dump, a logger control, a heap dump -- would have granted
                        //       it to every holder of a CardDemo group authority the moment it was
                        //       exposed, by inheriting a rule written for business data. Nothing would
                        //       have been logged and no rule would have been edited, which is what
                        //       made it worth closing before rather than after such an endpoint
                        //       appears. Alternatives Considered: requiring the administrator
                        //       authority instead of denying, so an operator could reach a newly
                        //       exposed endpoint without a code change. Rejected because it presumes
                        //       the next endpoint is safe for any administrator token and decides that
                        //       in advance for an endpoint nobody has looked at; denying makes
                        //       exposing one a deliberate act that has to name its own rule here.
                        //
                        //       Trade-offs: an operator who exposes an endpoint and forgets this rule
                        //       gets a 403 rather than a working endpoint. That is the intended
                        //       failure direction -- loud and safe rather than quiet and open -- and
                        //       the constant's own documentation records where to add the rule.
                        .requestMatchers(MANAGEMENT_PATH).denyAll()
                        // WHY : Assumptions: the two internal-only subtrees are DENIED here, and the
                        //       rule is not dead. The earlier-ordered internal chain matches the two
                        //       exact internal paths beneath them, so what reaches this rule is every
                        //       OTHER address in those subtrees -- and no browser client addresses
                        //       either subtree at all, which ui/src/api/contracts.test.ts asserts by
                        //       excluding account-api.yaml from its client inventory. Denying is
                        //       therefore the narrower reading and the correct one: no baseline screen
                        //       reads a cross-reference or a customer record directly, so granting a
                        //       group here would ADD a capability rather than preserve one.
                        .requestMatchers(CARD_XREF_PATH_PATTERN, CUSTOMER_PATH_PATTERN)
                        .denyAll()
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
                        .jwt(jwt -> jwt.decoder(endUserTokenDecoder)
                                .jwtAuthenticationConverter(authenticationConverter)))
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
