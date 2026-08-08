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
 * <h2>The sign-on decision this chain deliberately does not reproduce</h2>
 *
 * <p>Refactoring Rationale: the baseline admits a caller by comparing two eight-character fields in
 * clear text. {@code app/cpy/CSUSR01Y.cpy} line 21 declares the stored credential as {@code PIC X(08)}
 * inside an eighty-character record, and {@code app/cbl/COSGN00C.cbl} line 223 compares it directly
 * against what the terminal supplied, carrying the outcome forward in the five moves at lines 224 to
 * 228. The migrated system declines parity at exactly this point: that field is carried forward into no
 * target schema, no comparison of it happens anywhere, and this class accepts an already-minted token
 * from the identity provider instead. What was wrong with the old approach, in the specific sense the
 * project rule asks for, is that a stored credential legible to anyone who can read the record has no
 * confidentiality property to begin with, so no amount of transport protection placed in front of it
 * creates one. This is a deliberate, documented behavioural change and the one place in this migration
 * where parity is explicitly declined rather than preserved. The baseline is reference-only and
 * continues to behave exactly as it does; the migration adds a path and removes none.</p>
 *
 * <p>Refactoring Rationale: the stateless policy installed below replaces continuity the baseline holds
 * on the server between screen turns. {@code app/cbl/COSGN00C.cbl} is pseudo-conversational -- line 65
 * declares {@code 01 DFHCOMMAREA.} as the structure handed across the gap, line 80 tests
 * {@code IF EIBCALEN = 0} to recognise a first entry, and lines 98 to 102 end every turn by returning
 * that structure to the terminal, naming it on line 100. What was wrong with that arrangement for THIS
 * target is not its correctness, which is not in question on its own platform, but its placement:
 * continuity held on the server binds a caller to the instance that served its previous turn, and that
 * binding is precisely what stops horizontally scaled tasks behind a load balancer from serving any
 * request interchangeably. Identity moves into the token and selection context into the request path,
 * which is what makes the stateless policy below a fact about this service rather than a declaration.</p>
 *
 * <p>Alternatives Considered: carrying the baseline's turn discriminator forward as a request field, so
 * that a handler could still tell a first submission from a later one. {@code app/cpy/COCOM01Y.cpy}
 * line 29 declares {@code CDEMO-PGM-CONTEXT PIC 9(01)} with its two condition names on lines 30 and 31,
 * and the contrast with lines 27 and 28 is itself the argument: the user-type values there are quoted
 * character literals, which marks a domain the target still needs, while the context values are bare
 * numerics, which marks a mechanism of the screen-turn cycle. Rejected because a handler that answers
 * with a structured per-field error array has no such distinction to draw -- every request arrives
 * carrying its own complete context -- so the field would have had no reader here and would only have
 * invited one.</p>
 *
 * <h2>The correlation identity: relied on here, registered by the shared kernel</h2>
 *
 * <p>Alternatives Considered: three arrangements were available for
 * {@code com.carddemo.common.web.CorrelationIdFilter} and two of the three are rejected. Declaring a
 * {@code FilterRegistrationBean} for it in this file would hand this class explicit ordering control,
 * and it is rejected because the shared kernel already declares one: that kernel's auto-configuration
 * is named in its {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * resource, and a servlet-only nested configuration inside it contributes the registration under the
 * bean name {@code carddemoCorrelationIdFilterRegistration}. Adding a non-bean instance to this chain
 * with {@code addFilterBefore} is the second arrangement and is also rejected, because it would cover
 * only the requests this chain matches -- a container-level error page, and any refusal decided before
 * the chain is entered, would then carry no correlation identity at all, and those are the failures the
 * identity is most needed for. Delegating to the shared registration is the third and is the one taken;
 * the argument that settles it is ownership rather than brevity, because a registration each service
 * must remember is one a service can omit, and omitting it yields a service that starts and serves
 * requests while silently emitting no identity on any log line.</p>
 *
 * <p>Assumptions: the ordering position relied on is
 * {@link com.carddemo.common.CardDemoCommonAutoConfiguration#CORRELATION_FILTER_ORDER}, the highest
 * precedence available, and relying on it is what makes this file's silence correct rather than merely
 * shorter. Because that position sits ahead of the security filter chain, the identity is already in the
 * logging context before any security filter runs, so an authentication or authorization refusal decided
 * below is recorded under the same identity as a request that reached a handler.</p>
 *
 * <p>Trade-offs: the one risk this delegation accepts is named here so that a later reader who finds no
 * registration in this file does not add one. The shared bean withholds itself only for a bean matching
 * its OWN name, so a registration declared here under any other name would leave that condition
 * unsatisfied and both would take effect, seating the filter in the chain twice. A sibling context has
 * already been through precisely that and withdrew its local declaration for this reason. The duplicate
 * is not destructive, because the filter carries its own once-per-request guard and a second pass
 * delegates without minting a second identity; what it costs is the property this paragraph defends --
 * one owner, one ordering position and one file to read.</p>
 *
 * <p>Assumptions: the filter's contract is depended on and is never restated here as this class's own
 * logic. An identity the caller supplies is echoed back unaltered, which
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 745 establishes by moving the saved
 * inbound correlation field straight into the reply descriptor; an identity the caller omits is minted
 * rather than the request being refused, which the adjacent line 746 establishes by moving the
 * no-identifier constant into the reply's own identifier field. The width is twenty-four characters,
 * declared at line 45 of that program as {@code 05 WS-SAVE-CORRELID PIC X(24).} Three widths are
 * verifiable in that lineage and conflating any two of them would corrupt the contract: twenty
 * characters is the structured log event key at line 40 of
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy}, which is that copybook's final line;
 * twenty-four is the correlation identity itself; and forty-eight belongs to the queue NAMES at lines 43
 * and 44, which describe a destination and are not an identity at all. This class names none of those
 * three values, and no header name or context key either: each is a named constant on the filter, so one
 * spelling governs every service and this file cannot introduce a second.</p>
 *
 * <p>Assumptions: the identity is withdrawn from the logging context as the response completes, on the
 * ordinary and the exceptional path alike, and the reason is cross-request contamination rather than
 * resource economy. A servlet container serves successive unrelated requests on one pooled thread and
 * the mapped diagnostic context is bound to that thread, so a value left in place would attach itself to
 * the log lines of a later request that never carried it, corrupting the very record an operator reads
 * to tell two units of work apart. The key that value occupies is the filter's own
 * {@code CORRELATION_ID_MDC_KEY} constant, declared at line 227 of
 * {@code services/common-lib/src/main/java/com/carddemo/common/web/CorrelationIdFilter.java}, and the
 * removal is performed in that filter's own exit path; this class neither names the key nor repeats the
 * cleanup.</p>
 *
 * <h2>What is resolved elsewhere, and what is deliberately absent</h2>
 *
 * <p>Assumptions: nothing this chain depends on is written into it. The issuer location, the two group
 * names read below and every other deployment-specific value reach the running task from the
 * infrastructure definitions by way of Parameter Store and Secrets Manager, and those values that are
 * credentials are generated at provisioning time straight into Secrets Manager rather than authored
 * anywhere at all. That is what makes the absence of a credential from this source file structural
 * rather than a matter of review vigilance: there is no step at which one would have been written down
 * for someone to remember to remove. The shape is visible in this module's own configuration, where the
 * issuer location is injected from the task environment at line 918 of
 * {@code src/main/resources/application.yml} and the two group names this class reads are injected the
 * same way at its lines 1181 and 1182, each carrying a variable reference rather than a value. No issuer
 * location, pool identifier, region or endpoint appears here in any form. The infrastructure that
 * supplies them is authored and statically validated in this repository; applying it is an operator
 * action outside this module.</p>
 *
 * <p>Alternatives Considered: adding a retry or circuit-breaker library so that this context could
 * defend its own outbound calls. Rejected on two independent grounds. The framework release this module
 * builds against moved retry into its core, so the capability needs no dependency: a configuration class
 * annotated {@code @EnableResilientMethods} activates it, the attribute bounding an attempt sequence is
 * {@code maxRetries}, and the total number of attempts is one plus that value. Neither appears in this
 * class because this class issues no outbound call to bound. A circuit breaker is declined separately:
 * the only synchronous hops in this tier stay inside the private network behind an internal load
 * balancer with explicit connect and read timeouts, so a breaker would add a state machine that can
 * itself fail open or closed without removing any failure this deployment actually has.</p>
 *
 * <p>Alternatives Considered: giving this package a batch configuration or a queue-listener
 * configuration alongside the classes it has. Both are rejected, and this module's dependency set
 * already records the decision -- neither a batch starter nor a messaging starter is declared in its
 * {@code pom.xml}, so neither class would compile here. The substantive reason is ownership rather than
 * absence: this context starts a state-machine execution and returns, while the durable ledger of which
 * runs and which steps completed belongs to the batch service that owns it. A job repository here would
 * be a second, competing record of one restart decision, and two answers to "has this step already run"
 * is a worse position than one answer held in another service.</p>
 *
 * <p>Alternatives Considered: the singular or parenthesised spellings of the four rationale labels this
 * file uses throughout. Rejected in favour of the plural, un-parenthesised, colon-terminated form taken
 * directly from the project rule, and the choice was settled by measurement rather than preference. A
 * case-sensitive search of this repository outside its version-control directory returns 3405
 * occurrences of the plural label across 669 files, against 3830 occurrences of the bare stem across 746
 * files, and 152 occurrences of the parenthesised singular. The parenthesised spelling survives in
 * configuration, markup and shell artifacts; this Java tree uses the plural exclusively, and mixing the
 * two registers inside one file would leave a reader unsure which spelling a search should use.</p>
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
     *
     * <p>Assumptions: both probes read this path on the SAME port as the business surface, and not on a
     * separate management port. The container image declares one port, 8080, at
     * {@code services/reporting-service/Dockerfile} line 169, states at its line 165 that the port
     * carries the business surface and the actuator together, and points its own health check at this
     * path on that port at lines 217 to 220; this module's {@code application.yml} binds the application
     * to the same port at its line 226 and restricts the exposed management set at its line 1028.
     * Splitting the two onto different ports would let the target group and the container health check
     * disagree about whether one task is alive, which produces both halves of the wrong outcome -- a
     * healthy task replaced, and a broken task kept in rotation. Opening the path is a bounded decision
     * rather than a hole because the endpoint discloses no business data.</p>
     */
    public static final String HEALTH_PATH = "/actuator/health/**";

    /**
     * The one health group that carries detail, carved out of the permitted namespace above.
     *
     * <p>Refactoring Rationale: this path had no rule of its own, so {@link #HEALTH_PATH} permitted it
     * to anyone and the ONLY thing standing between an unauthenticated caller and the contributor detail
     * was {@code management.endpoint.health.roles} in the development overlay. That setting could not
     * work as written and the overlay said so: the actuator's authorization test delegates to the
     * servlet role check, which prepends the framework's {@code ROLE_} prefix to a value that does not
     * already carry one, while {@link JwtRoleConverter} publishes the provider's group names with NO
     * prefix. The configured value {@code carddemo-admin} therefore tested for an authority
     * {@code ROLE_carddemo-admin} that nothing in this system ever grants, so the group failed closed
     * for EVERY caller -- an administrator included, which is the half of the outcome that was wrong.
     * The gate moves here, where the authority is compared as it is actually published.</p>
     *
     * <p>Assumptions: the pattern is the EXACT path and not a prefix, and the rule that uses it is
     * declared BEFORE the permitted namespace, because {@code /actuator/health/**} matches this path too
     * and the first matching rule decides. The two probe groups the orchestrator polls --
     * {@code /actuator/health/readiness} and {@code /actuator/health/liveness} -- and the aggregate
     * {@code /actuator/health} are deliberately NOT matched here, so opening detail to an operator
     * cannot close the path a health check reads.</p>
     *
     * <p>Trade-offs: the group is declared only by the development overlay, so on every other profile
     * this path resolves to no group and the actuator would answer 404. With this rule an
     * unauthenticated caller receives 401 there instead of 404, which is a change and is the better of
     * the two: a 404 confirms which group names are absent, and a caller with no token has no business
     * distinguishing them. What is given up is the ability to discover from outside whether a
     * deployment publishes the group at all.</p>
     */
    public static final String HEALTH_DIAGNOSTIC_PATH = "/actuator/health/diagnostics";

    /**
     * The management namespace, named so an assertion can hold the two rules apart.
     *
     * <p>Assumptions: this pattern is MATCHED by a rule of its own in the chain below, and that rule
     * DENIES. It is ordered after {@link #HEALTH_PATH} and after the two endpoints granted by network
     * position, so each of those keeps its own more specific rule, and before the business rule, so
     * nothing under this namespace can reach it. What the rule answers for is therefore the management
     * addresses the exposure list withholds, which reach no handler today.</p>
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
     * The authorization decision that guards {@link #HEALTH_DIAGNOSTIC_PATH}, exposed for assertion.
     *
     * <p>Assumptions: the administrator authority ALONE satisfies this rule, where the catch-all admits
     * either group. The contributor detail this path renders names the datasource the reporting role
     * connects with and whether that connection is up, which is an operator's question and not a
     * cardholder's -- and {@link #BUSINESS_AUTHORITIES} deliberately admits an ordinary user, so reusing
     * it here would have granted the detail to every cardholder holding a valid token.</p>
     *
     * <p>Assumptions: the predicate is in the AUTHORITY register and not the role register, for the
     * reason recorded on {@link #businessAccess()} and repeated in effect by the defect this rule
     * replaces: the role register silently prepends {@code ROLE_} and nothing in this system grants a
     * prefixed authority, so a role predicate here would refuse an administrator without raising
     * anything.</p>
     *
     * <p>Trade-offs: this rule is the whole gate, and the actuator's own {@code roles} list is no longer
     * relied on -- the development overlay stops setting it, so the endpoint's own test reduces to
     * "is the principal authenticated". That is deliberate, not a weakening: the chain admits nobody but
     * an administrator to this path, and leaving BOTH gates in place with one of them unsatisfiable is
     * exactly the arrangement that produced a group nobody could read. The endpoint-level test still
     * requires authentication, so if this rule were ever widened an anonymous caller would receive the
     * bare status word rather than the detail.</p>
     *
     * @return the manager that grants only a principal holding {@link JwtRoleConverter#ADMIN_AUTHORITY},
     *     never {@code null}
     */
    public static AuthorizationManager<RequestAuthorizationContext> diagnosticAccess() {
        return AuthorityAuthorizationManager.hasAuthority(JwtRoleConverter.ADMIN_AUTHORITY);
    }

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
     * <p>Assumptions: this rule is expressed in the AUTHORITY register and not the role register, and the
     * two are not interchangeable. A role predicate matches an authority string bearing the framework's
     * {@code ROLE_} prefix, which the framework supplies on the predicate side; an authority predicate
     * matches the string exactly as granted. {@link JwtRoleConverter} is the source of truth for which
     * register applies: it declares the two names at its lines 250 and 260 with no prefix in either
     * value, and grants each recognised name verbatim through a plain authority construction at its line
     * 339. The authority register is therefore the matching one, and this file uses it and only it,
     * never mixing the two spellings. Choosing the other
     * register is the kind of error that never announces itself: the class would compile, the context
     * would start, and every request would be refused with 403 while the token carried exactly the group
     * the deployment intended, with no exception raised and no line written to say so. The paired unit
     * test reads {@link #BUSINESS_AUTHORITIES} and exercises this very manager, which is what turns that
     * silent outcome into a failing assertion.</p>
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
                        // WHY : Assumptions: the diagnostic group is declared FIRST and the permitted
                        //       health namespace second, because /actuator/health/** matches the
                        //       diagnostic path too and the first matching rule decides. Reversing the two
                        //       lines would silently restore the defect this rule exists to close -- the
                        //       path would be permitted to anyone and the class would still compile,
                        //       start and pass every assertion that reads the constants rather than the
                        //       order.
                        .requestMatchers(HEALTH_DIAGNOSTIC_PATH).access(diagnosticAccess())
                        .requestMatchers(HEALTH_PATH).permitAll()
                        // WHY : Assumptions: the two management endpoints are granted by NETWORK
                        //       POSITION and not by authority, because their only configured consumer
                        //       is the task-local collector sidecar, which presents no token. Declared
                        //       before the business rule so a request of any method to either endpoint
                        //       is judged by position alone.
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
