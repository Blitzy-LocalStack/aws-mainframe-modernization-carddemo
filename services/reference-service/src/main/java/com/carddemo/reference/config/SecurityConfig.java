package com.carddemo.reference.config;

import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.security.CognitoAccessTokenValidator;
import com.carddemo.common.security.JwtRoleConverter;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
 * Configures who may reach this context's endpoints, and which tokens are accepted at all.
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
 * <h2>Where the reference-only baseline decides authorization, and where this class decides it</h2>
 *
 * <p>Refactoring Rationale: the four transactions declared across the two resource definitions this
 * context is migrated from carry resource security and command security both set to no, so the
 * transaction monitor performs no per-resource authorization on any of them and each program settles
 * the question for itself. Both settings sit on a single line per transaction:
 * {@code app/app-transaction-type-db2/csd/CRDDEMOD.csd} line 32, for the transaction-type inquiry
 * transaction declared at line 25 against the program named at line 26, and line 42 for the maintenance
 * transaction declared at line 35 against the program named at line 36; and
 * {@code app/app-vsam-mq/csd/CRDDEMOM.csd} line 24 and line 34 for the two transactions declared at
 * lines 17 and 27. Each program authorizes in-program instead, by reading the one-character user type
 * out of the communication area it is handed. This class authorizes per endpoint from the signed group
 * claim, before any handler of this service runs, and the divergence is recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md}. What makes the change of mechanism worth
 * making is where the deciding value is held rather than anything about the programs that hold it: the
 * communication area is storage the client receives and returns on its next turn, so an in-program test
 * reads a value the client was in possession of, whereas a claim inside a signed token cannot be
 * asserted by the client at all -- an altered claim fails signature validation before any rule below is
 * consulted.</p>
 *
 * <p>Assumptions: three of those four transactions drive this context and the fourth does not. The one
 * declared at line 17 of {@code app/app-vsam-mq/csd/CRDDEMOM.csd}, against the account-inquiry program
 * named at line 18, belongs to {@code account-service}: that context owns the account, customer and
 * cross-reference data the program reads, and the alternate transport it was reached by is migrated
 * alongside the data rather than beside this service's reference tables. No rule below authorizes a
 * route for it, and none may be added here, because a route in this chain would put an authorization
 * decision for another context's data in a file that context's reader never opens.</p>
 *
 * <p>Assumptions: the identity this chain authorizes on is the migrated form of a one-character field.
 * {@code app/cpy/COCOM01Y.cpy} declares the user type at line 26 inside the communication area that
 * begins at line 19, and gives it exactly two condition names, the administrator at line 27 valued
 * {@code 'A'} and the ordinary user at line 28 valued {@code 'U'}. Those two become the identity
 * provider's two group names, which arrive in the group claim and are turned into authorities by
 * {@link JwtRoleConverter}. The mapping is closed at two because that copybook closes it at two.</p>
 *
 * <p>Assumptions: the two literal forms in that copybook are not interchangeable, and the declarations
 * that use them sit four lines apart. The user-type condition names at lines 27 and 28 take QUOTED
 * character values on a character picture, while the condition names at lines 30 and 31 -- belonging to
 * the one-digit continuation field declared at line 29 -- take BARE numeric values on a numeric picture.
 * Reading either family with the other's literal form yields a comparison that matches nothing and
 * reports no error, so the pair of values this class depends on is the quoted pair at lines 27 and 28
 * and nothing else in that copybook.</p>
 *
 * <h2>The continuation discriminator has no counterpart here</h2>
 *
 * <p>Refactoring Rationale: the one-digit field at line 29 of {@code app/cpy/COCOM01Y.cpy}, with the
 * two condition names at lines 30 and 31 that tell a program's first turn at a screen from a later one,
 * is how the baseline tracks where it is in a pseudo-conversational exchange. Nothing in this module
 * carries it: no parameter of this class, no request parameter of any route it authorizes, and no field
 * of any transfer object. A stateless handler that answers with a structured field-error body has no
 * such distinction to draw, so error presentation is decided by the response body alone. The
 * consequence of reintroducing a parameter of that shape is what keeps it out: per-turn state would be
 * back on the wire, and the module would no longer be one that any task can serve, which is precisely
 * what lets these tasks be scaled horizontally behind a load balancer with no sticky routing and no
 * server-side session store.</p>
 *
 * <p>Assumptions: the communication area was the baseline's only channel for that state, so nothing
 * else has to be accounted for. All four transactions are declared with a transaction work area of size
 * zero -- {@code app/app-transaction-type-db2/csd/CRDDEMOD.csd} lines 26 and 36, and
 * {@code app/app-vsam-mq/csd/CRDDEMOM.csd} lines 18 and 28 -- so no second per-task storage area was
 * allocated for continuity to live in.</p>
 *
 * <h2>The correlation identity: relied on here, registered by the shared kernel</h2>
 *
 * <p>Alternatives Considered: three arrangements were available for
 * {@code com.carddemo.common.web.CorrelationIdFilter} and two of the three are rejected. Declaring a
 * {@code FilterRegistrationBean} for it in this file would hand this class explicit ordering control,
 * and it is rejected because the shared kernel already declares one under the bean name
 * {@code carddemoCorrelationIdFilterRegistration}, withheld only for a bean of that same name; a
 * registration declared here under any other name would leave that condition unsatisfied and both would
 * take effect, seating the filter twice. Adding a non-bean instance to this chain with
 * {@code addFilterBefore} is the second arrangement and is also rejected, because it would cover only
 * the requests this chain matches -- a container-level error page, and any refusal decided before the
 * chain is entered, would then carry no identity at all, and those are the failures the identity is most
 * needed for. Delegating to the shared registration is the third and is the one taken, which is also
 * what keeps one file answerable for the effective order: an identity registered in two places has an
 * order no single file can be read to establish.</p>
 *
 * <p>Assumptions: the position relied on is
 * {@link com.carddemo.common.CardDemoCommonAutoConfiguration#CORRELATION_FILTER_ORDER}, the highest
 * precedence available, applied to every request path. Because that position sits ahead of the security
 * filter chain, the identity is already in the logging context before any filter below runs, so an
 * authentication or authorization refusal decided here is recorded under the same identity as a request
 * that reached a handler -- and the refusals are exactly the records an identity is wanted for, so a
 * position behind the chain would leave them the only ones without it. The shared refusal renderers this
 * chain installs read that identity from the logging context for the same reason.</p>
 *
 * <p>Assumptions: the filter's own contract is depended on and never restated here. An identity the
 * caller supplies is echoed back unaltered, an identity the caller omits is minted rather than the
 * request being refused, and the width is the twenty-four characters its own constant carries. This
 * class names no header, no context key and no width, so one spelling governs every context and this
 * file cannot introduce a second.</p>
 *
 * <h2>The shape of the route table</h2>
 *
 * <p>Assumptions: reference WRITES are administrator-only and reference READS are not. The baseline
 * reaches the transaction-type maintenance screens from the administrative side while every other
 * context reads the seeded lookup rows to validate an address, so the split is by operation rather than
 * by path -- which is why the rules below match on HTTP METHOD. A path-based split would have to
 * enumerate every reference resource and would silently admit a write to one added later. "Not
 * administrator-only" means the ORDINARY-USER authority is still required: a read is open to both groups,
 * not to every holder of a valid token.</p>
 *
 * <p>Trade-offs: requiring the administrator authority on reads as well was the alternative and is
 * declined. Reference data is the shared vocabulary the other bounded contexts read -- transaction types
 * and categories to render a list, the disclosure-group rate to accrue interest, the seeded address rows
 * to validate a customer address -- so an administrator-only read would oblige an ordinary session to
 * escalate merely to populate the option list on a form. What is accepted in exchange is that an
 * ordinary token can read every reference row this service publishes; that is the smaller cost, because
 * the rows are a published vocabulary rather than customer data, and the write rules above still keep
 * the vocabulary itself administrator-only.</p>
 *
 * <p>Assumptions: every rule below is written with the AUTHORITY predicate and none with the role
 * predicate, and the two are not interchangeable here. {@link JwtRoleConverter} records the decision
 * this matches at its own class documentation: the authority it grants is the identity provider's group
 * name verbatim, with no role prefix added, and it names the role predicate as the mistake to avoid
 * because that predicate would look for a prefixed authority the converter never produces. The failure
 * that mismatch produces is why the form is asserted here rather than assumed -- the chain would build,
 * the context would start, and every administrative request would be refused at run time with nothing
 * at start-up to point at the cause.</p>
 *
 * <h2>Baseline attributes with no rule of their own</h2>
 *
 * <p>Assumptions: the four transactions are declared to back their unit of work out on abnormal
 * termination, which is the attribute the target expresses as a transactional boundary rolled back when
 * an exception propagates. That boundary is owned by the service layer of this module and by nothing in
 * this class, and is named here only so a reader does not look for it among the rules below.</p>
 *
 * <p>Assumptions: all four are also declared not to restart, so the baseline holds no checkpoint
 * contract for this context; the target's restart capability is an addition rather than a carried-across
 * behaviour. Their trace configuration does not suppress confidential data either, which is why this
 * migration masks sensitive values where a transfer object is built and never relies on a trace setting
 * to withhold them. Neither attribute has a counterpart in this chain, and the interactive debugging
 * facility the programs enable has no counterpart anywhere in the target.</p>
 *
 * <p>Assumptions: the closest baseline precedent for what this class does is the database authorization
 * attribute at line 47 of {@code app/app-transaction-type-db2/csd/CRDDEMOD.csd}, which authorizes by the
 * signed-on user identity. Authorizing from the identity carried on the request is the same idea; only
 * the mechanism that makes the identity trustworthy differs.</p>
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
     * The reference read surface, which the contract reserves to a CardDemo group.
     *
     * <p>Assumptions: one subtree pattern covers every published path, because all thirteen of them sit
     * under this prefix in {@code openapi/reference-api.yaml}. The contract is regular on authority
     * too, and both halves were read from it rather than assumed: every {@code GET} carries
     * {@code x-required-authority: carddemo-user} and every {@code POST}, {@code PUT} and
     * {@code DELETE} carries {@code carddemo-admin}. The method rules in the chain already expressed
     * the second half; this constant expresses the first.</p>
     *
     * <p>Refactoring Rationale: the read surface previously fell through to a catch-all requiring only
     * authentication, so the declared user authority was published and not enforced. Reference data is
     * not the most sensitive surface in this system, but the gap was the same one the finding names on
     * the authorization context, and leaving it would keep a published marker untrue.</p>
     */
    public static final String READ_PATH_PATTERN = "/api/v1/reference/**";

    /**
     * The authorities that satisfy the reference read rule, in one place because a test reads the list.
     *
     * <p>Assumptions: an administrator satisfies the rule as well as an ordinary user. The baseline
     * agrees -- {@code SEC-USR-TYPE} at {@code app/cpy/CSUSR01Y.cpy} line 22 admits exactly two values
     * and an administrator reached the ordinary screens through the same menu graph -- so this is the
     * two-value domain the baseline already had rather than a privilege-escalating shortcut.</p>
     *
     * <p>Refactoring Rationale: the pair is a named constant rather than two literals inside the chain,
     * so the accompanying test can assert on the SAME list the chain enforces. Restating the pair in the
     * test would let the two drift while the test kept passing.</p>
     */
    public static final List<String> BUSINESS_AUTHORITIES =
            List.of(JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);

    /**
     * The authorization decision the reference read rule installs, exposed so a test can exercise the
     * object the chain actually enforces.
     *
     * <p>Assumptions: this returns exactly what {@code hasAnyAuthority(...)} would have built -- that
     * builder is itself a one-line wrapper around
     * {@link AuthorityAuthorizationManager#hasAnyAuthority(String...)} -- so naming the manager changes
     * nothing about the rule and everything about whether it can be asserted without a servlet
     * container.</p>
     *
     * <p>Refactoring Rationale: the decision is attached to the READ rule and not to the catch-all,
     * because this chain's catch-all is {@code denyAll()}. A test written against a catch-all that
     * admits either group would assert an object this chain does not install, which is the vacuity the
     * layering gate's own guards exist to prevent.</p>
     *
     * @return the manager admitting either CardDemo group; never {@code null}
     */
    public static AuthorizationManager<RequestAuthorizationContext> businessAccess() {
        return AuthorityAuthorizationManager.hasAnyAuthority(
                BUSINESS_AUTHORITIES.toArray(String[]::new));
    }

    /**
     * The management namespace, named so an assertion can hold the two rules apart.
     *
     * <p>Assumptions: this constant NAMES the namespace and no rule below grants the namespace as a
     * whole. {@link #HEALTH_PATH} is permitted, the two endpoints this module publishes beyond health
     * are granted by network position, and what remains under this pattern reaches no handler -- so it
     * is left to the catch-all rather than given a rule that would only ever answer for a path that
     * does not exist.</p>
     */
    public static final String MANAGEMENT_PATH = "/actuator/**";

    /**
     * The build-identity management path, reachable only from inside the task.
     *
     * <p>Assumptions: named explicitly because this module's exposure list is
     * {@code include: health,info,prometheus}, so this and the scrape path below are the two management
     * endpoints it admits beyond health.</p>
     */
    public static final String BUILD_IDENTITY_PATH = "/actuator/info";

    /**
     * The metric scrape path, reachable only from inside the task.
     *
     * <p>Assumptions: the only configured consumer is task-local. The collector configured at
     * {@code infra/modules/ecs-service/main.tf} lines 154 to 172 reaches this path over the loopback
     * address and presents no authorization header, so a rule requiring a token would stop the scrape
     * rather than narrow it.</p>
     */
    public static final String METRIC_SCRAPE_PATH = "/actuator/prometheus";

    /**
     * The loopback addresses the task-local collector can reach this service from.
     *
     * <p>Assumptions: both families are listed because the address a container resolves loopback to is a
     * property of its network stack. IPv4 is what Fargate presents under {@code awsvpc}; the IPv6 form
     * is included so a stack answering {@code ::1} does not silently lose its metrics.</p>
     */
    private static final List<String> LOOPBACK_RANGES = List.of("127.0.0.1/32", "::1/128");

    /**
     * The paths this service publishes its own API description at.
     *
     * <p>Refactoring Rationale: the chain granted none of these paths while the catch-all below denies,
     * so every one of them answered 403 to a valid token of either group and 401 without one -- and this
     * module's {@code application.yml} pins all three of the addresses they are served at and extends
     * {@code spring.web.resources.static-locations} with {@code classpath:/openapi/} specifically so the
     * packaged contract can be fetched. The configuration and the chain now agree. A sibling context
     * reached the same arrangement first and found it unreachable only when it was exercised at run time,
     * which is the reason this rule is stated here rather than left for the same discovery.</p>
     *
     * <p>Assumptions: five patterns are needed for three configured addresses. The generated document has
     * a subtree beneath it because the browser view fetches its own settings from
     * {@code /v3/api-docs/swagger-config}, and the view has an asset subtree because its markup, script
     * and stylesheet ship inside a webjar served from {@code /swagger-ui/}. Granting only the three
     * configured addresses would leave the page loading and then failing to render, which is a harder
     * outcome to read than a refusal. The third address, {@code /reference-api.yaml}, is the committed
     * contract the view is pointed at rather than a generated document.</p>
     *
     * <p>Alternatives Considered: {@code permitAll}, which is what many deployments give a documentation
     * path and which would make the view usable from a browser with no token at all. Rejected because it
     * would publish the complete shape of every operation, every field width and every authority
     * requirement of this service to anything that can reach the listener, and this chain's whole posture
     * is that nothing is reachable without a rule granting it. The document is not a secret -- it is
     * committed to this repository -- but serving it anonymously from a running task widens the reachable
     * surface and buys nothing an operator does not already have from the repository.</p>
     *
     * <p>Alternatives Considered: granting these to the administrator authority alone, on the reasoning
     * that reading an API description is an operator activity. Rejected because the description is the
     * contract an ordinary integrator writes a client against, and refusing it to the group permitted to
     * call every read operation would make the document harder to obtain than the rows it describes.</p>
     *
     * <p>Assumptions: this rule is the same under every profile, and the per-environment difference lives
     * entirely in configuration. The interactive view is left at its library default in the development
     * overlay and switched off in the production one, so in production these patterns match a page that
     * is not served while the document and the committed contract still are. Stating the rule
     * unconditionally is what keeps that difference legible: a rule written to depend on the view being
     * enabled would authorize differently per environment for a reason no reader of this file could
     * see.</p>
     *
     * <p>Trade-offs: because the grant is by authority, a browser opened straight at the view is refused,
     * since a navigation carries no bearer token. The page is reachable to a caller that presents one,
     * which is how the document is fetched for a contract comparison. The accepted cost is that the view
     * is not a click-and-read page; the alternative was to make the whole API description anonymous,
     * which the paragraph above declines.</p>
     */
    private static final List<String> DOCUMENTATION_PATHS = List.of(
            "/v3/api-docs", "/v3/api-docs/**", "/reference-api.yaml",
            "/swagger-ui.html", "/swagger-ui/**");

    /*
     * WHY : Alternatives Considered: one MANAGEMENT_PATH pattern granted to ADMIN_AUTHORITY, with
     *       the catch-all admitting either group. Authored and REJECTED on the measured scrape
     *       configuration: infra/modules/ecs-service/main.tf configures the task-local collector to
     *       scrape the metric path on the loopback address and the container port, and to send NO
     *       authorization header of any kind, so an authority rule over the whole namespace would
     *       not narrow who may read metrics -- it would stop the only consumer that exists from
     *       reading them at all, and the loss would be silent because a scrape failure is not a
     *       request failure anybody sees. Granting the two named endpoints by network position keeps
     *       the scrape working while still refusing every caller that arrives through the load
     *       balancer, and the catch-all is denyAll rather than either-group so a route added without
     *       a rule fails closed instead of inheriting one.
     */

    /**
     * Builds the filter chain.
     *
     * <p>Assumptions: sessions are STATELESS. The baseline is pseudo-conversational and carries its
     * continuity in a structure the client echoes; the migrated form carries identity in the token and
     * selection context in the request path, so there is nothing left for a server-side session to
     * hold. Permitting one would reintroduce the sticky routing that horizontal scaling exists to
     * avoid.</p>
     *
     * <p>Trade-offs: cross-site request forgery protection is disabled, and the condition that makes it
     * unnecessary is stated rather than assumed. The protection guards a session an ambient credential
     * authenticates, and this chain has neither: every request authenticates with a bearer token, which a
     * browser does not attach on its own, so the confused-deputy condition the protection defends against
     * cannot arise. For a cookie-authenticated application the same setting would remove a control that
     * was doing real work. Leaving it enabled here would reject every non-browser client -- including the
     * load balancer -- for no gain.</p>
     *
     * <p>Assumptions: the four mutating methods are named explicitly rather than expressed as "not GET".
     * A negative match would also cover the methods the framework answers itself, and it would silently
     * change meaning if a method were added to the specification.</p>
     *
     * <p>Assumptions: the catch-all requires one of the two GROUP authorities rather than merely
     * requiring authentication. Those are not the same condition. {@link JwtRoleConverter} grants an
     * EMPTY authority set for a token whose {@code cognito:groups} claim is absent, is not a collection,
     * holds a non-textual entry, or names only groups this application does not recognise -- and every
     * one of those tokens is still fully authenticated, because it carries a valid signature from the
     * configured pool. A rule of {@code authenticated()} therefore admits a principal that has been
     * granted nothing, and that state has no counterpart to reason from in the reference-only baseline:
     * the communication area at {@code app/cpy/COCOM01Y.cpy} lines 19 to 44 always carries one of exactly
     * two user types, so no reachable baseline state corresponds to a signed-on user belonging to
     * neither group. Requiring an authority is how this chain keeps that property, and the divergence in
     * mechanism is recorded in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
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
                        // WHY : (1) Refactoring Rationale: the container's ERROR dispatch is permitted
                        //       before every other rule, and this context needed it more than a chain
                        //       ending in a plain catch-all would. When the servlet forwards a refusal it
                        //       rendered -- an unsupported media type, an unacceptable representation, a
                        //       body over the published ceiling -- to its own error path, that forward
                        //       keeps the ORIGINAL request method. A refused POST therefore arrived at
                        //       /error and matched the POST rule below, whose pattern spans the whole
                        //       tree, so the
                        //       forward was re-judged as a mutating call and answered
                        //       carddemo-admin-or-nothing. A caller owed 415 received 401 or 403 on a path
                        //       it never addressed, and an anonymous caller could not be told why its
                        //       request was malformed at all. A GET reached the denying catch-all instead,
                        //       so both halves of the surface were affected by different rules.
                        // WHY : (2) Assumptions: the rule matches the DISPATCHER TYPE and not the path, so
                        //       a direct request to /error is still refused by exactly the rules that
                        //       refuse it today. A path-based permit would open that path to any caller,
                        //       and the container's own error body is not a shape
                        //       src/main/resources/openapi/reference-api.yaml publishes for any operation.
                        // WHY : (3) Assumptions: the shared defaults narrow the filter chain to the
                        //       REQUEST and ASYNC dispatches, so in a fully-configured application this
                        //       rule is defence in depth. It is declared nonetheless, because a sliced web
                        //       test builds this chain WITHOUT that property and would measure the refused
                        //       dispatch rather than the served one -- and because a chain that depends on
                        //       an external property for a security-visible outcome states its intent
                        //       nowhere.
                        // WHY : (4) Alternatives Considered: narrowing this chain to the REQUEST dispatch
                        //       alone, which has the same effect here. Rejected because it silently
                        //       withdraws authorization from ASYNC dispatches too, so an asynchronous
                        //       handler added later would run outside every rule below. The auth, account,
                        //       card and authorization contexts resolve it this same way, which is what
                        //       keeps the five chains comparable.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HEALTH_PATH).permitAll()
                        // WHY : Assumptions: granted by NETWORK POSITION and not by authority,
                        //       because the only configured consumer is the task-local collector and
                        //       it presents no token. Declared before the method rules so a request of
                        //       any method to either endpoint is judged by position alone.
                        .requestMatchers(BUILD_IDENTITY_PATH, METRIC_SCRAPE_PATH)
                        .access(loopbackOnly())
                        // WHY : (1) Assumptions: the documentation grant is stated BEFORE the four
                        //       method rules, and unlike the sibling contexts its position here is
                        //       load-bearing rather than immaterial. Those rules pair a method with a
                        //       whole-tree pattern, so they reach every path this service can be sent,
                        //       documentation paths included; declared after them, a mutating request
                        //       to a documentation path would be judged administrator-or-nothing while
                        //       a read of the same path was judged by this rule. Declared here, one
                        //       rule answers for those paths whatever method arrives, which is also
                        //       what makes the outcome identical under both profiles.
                        // WHY : (2) Assumptions: the accepted authorities are both CardDemo groups,
                        //       matching the read half of the contract this document describes, so an
                        //       administrator reaches it as well as an ordinary user. The reason the
                        //       grant is by authority at all rather than openly is recorded on
                        //       DOCUMENTATION_PATHS.
                        .requestMatchers(DOCUMENTATION_PATHS.toArray(String[]::new))
                        .access(businessAccess())
                        // WHY : (1) Alternatives Considered: pairing each method with the reference
                        //       subtree pattern, or enumerating the resources individually, instead of
                        //       the whole tree. Both are rejected on the same consequence, and it is
                        //       specific rather than defensive: the subtree rule beneath these admits
                        //       EITHER group, so a resource added to that subtree without a matching
                        //       write rule would have its mutating requests matched by that read rule
                        //       and admitted to an ordinary token. Judging every mutating method here
                        //       first makes administrator-only the DEFAULT for anything mutating, so a
                        //       resource added later is guarded before anybody remembers to guard it.
                        // WHY : (2) Assumptions: these four run after the health, operator and
                        //       documentation rules on purpose. Each of those three is reached by a
                        //       consumer that presents no token or only a read authority, and a rule
                        //       pairing a method with the whole tree would otherwise capture them --
                        //       which is why their position relative to this group is stated on the
                        //       documentation rule above rather than left to be inferred.
                        .requestMatchers(HttpMethod.POST, "/**")
                        .hasAuthority(JwtRoleConverter.ADMIN_AUTHORITY)
                        .requestMatchers(HttpMethod.PUT, "/**")
                        .hasAuthority(JwtRoleConverter.ADMIN_AUTHORITY)
                        .requestMatchers(HttpMethod.PATCH, "/**")
                        .hasAuthority(JwtRoleConverter.ADMIN_AUTHORITY)
                        .requestMatchers(HttpMethod.DELETE, "/**")
                        .hasAuthority(JwtRoleConverter.ADMIN_AUTHORITY)
                        // WHY : Assumptions: this rule reaches only what the four method rules above
                        //       did not, which is every GET on the reference surface. EITHER group
                        //       satisfies it, matching the authority model the contract publishes --
                        //       carddemo-user means either group, so an administrator is not denied a
                        //       read, while the mutating methods above still exclude a user-only token.
                        .requestMatchers(READ_PATH_PATTERN)
                        .access(businessAccess())
                        // WHY : Assumptions: denyAll and NOT authenticated, so a valid token carrying
                        //       neither group reaches nothing. Every path this service serves is
                        //       granted above, so a route added without a rule fails closed.
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
                //       never reaches the shared error-rendering advice that common-lib contributes.
                //       This class declares no advice of its own and maps no exception to a status; the
                //       401 entry point delegates to the framework's bearer-token entry point first, so
                //       the challenge header the OAuth 2.0 contract requires is composed exactly as
                //       before and only the body is added.
                .exceptionHandling(ApiErrorSecurityHandlers.renderingRefusals(clock))
                .build();
    }

    /**
     * Builds the authorization manager that admits a request only from a loopback address.
     *
     * <p>Assumptions: composed from the framework's own address manager rather than written here, so the
     * range parsing is the implementation the framework tests and this class contributes only the choice
     * of ranges. {@code anyOf} makes the two families alternatives rather than requirements, which is
     * what a single-stack container needs.</p>
     *
     * <p>Alternatives Considered: a security-group rule instead. Rejected because a security group
     * cannot express a rule about a task's own loopback interface -- traffic that never leaves the task
     * is not subject to it. The scrape target is nevertheless loopback precisely so the endpoints are
     * never published through a security-group rule either, so the two narrowings compound.</p>
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
     * @throws NullPointerException if either configured group name is {@code null}, which the shared
     *     converter raises rather than defaulting, so an unset property stops the context instead of
     *     producing a chain that authorizes nothing
     * @throws IllegalStateException if either configured group name differs from the authority the
     *     shared converter compiles in, which makes a renamed provider group a startup failure rather
     *     than a service that authenticates every request and admits it to no route
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
