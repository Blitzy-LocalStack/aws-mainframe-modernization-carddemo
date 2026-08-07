package com.carddemo.auth.config;

import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.security.CognitoAccessTokenValidator;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.web.CorrelationIdFilter;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.PathContainer;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.IpAddressAuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Configures who may reach this context's endpoints, which of them require the administrator
 * authority, and which two are reachable with no token at all.
 *
 * <p>Refactoring Rationale: what this replaces is not a role lookup but a byte the caller handed
 * back, and that is the whole reason the chain below reads a claim instead. The baseline decides the
 * same split by reading a one-character user type out of the communication area it passes between
 * screen turns: the field is declared at line 26 of {@code app/cpy/COCOM01Y.cpy}, inside the
 * 160-byte area declared at line 19 of that copybook and shared by all eighteen online programs, and
 * it is populated in exactly one place -- the five consecutive moves at lines 224 to 228 of
 * {@code app/cbl/COSGN00C.cbl}, of which line 226 moves the signed-on identifier into the area and
 * line 228 clears the re-entry discriminator. That area is storage the CLIENT echoes back on the next
 * turn, so the party whose privilege is being decided is in possession of the value that decides it.
 * Here the equivalent value is a claim the identity provider signed: a claim the caller altered fails
 * signature validation before this class is reached, so the caller can no longer assert its own user
 * type at all. That is a security property rather than a change of transport, and it is recorded in
 * {@code docs/architecture/security-and-identity.md}.</p>
 *
 * <p>Refactoring Rationale: one contract of the baseline security record is declined rather than
 * carried across, and the omission is deliberate. Line 21 of {@code app/cpy/CSUSR01Y.cpy} declares an
 * eight-character plaintext password field and line 223 of {@code app/cbl/COSGN00C.cbl} compares it
 * directly against what was typed. The baseline stores and compares a plaintext credential; the
 * migrated form delegates the comparison to the managed identity provider, retains only a subject
 * reference, and reaches this class holding a validated token; and the divergence is recorded in the
 * migration's traceability matrix rather than being applied to the baseline, which stays
 * byte-identical because it is the behavioural oracle this migration is verified against. Nothing in
 * this class holds, compares or names a credential, and nothing here may take on that
 * responsibility.</p>
 *
 * <h2>Why the route-to-authority table is a value rather than only a chain</h2>
 *
 * <p>Refactoring Rationale: the rules below are declared as an inspectable, ordered list and the
 * filter chain is BUILT from it, rather than the chain being the only statement of them. The reason
 * is the defect this class was authored to close: five of this context's eight operations are the
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
 *
 * <h2>Which authority predicate this chain must use, and why the choice is not this file's</h2>
 *
 * <p>Assumptions: the contract source is
 * {@code services/common-lib/src/main/java/com/carddemo/common/security/JwtRoleConverter.java}, and
 * its finished API settles the predicate rather than leaving it to each consumer. That converter emits
 * the identity provider's group name VERBATIM -- {@link JwtRoleConverter#ADMIN_AUTHORITY} and
 * {@link JwtRoleConverter#USER_AUTHORITY} are the group names themselves -- and adds no framework role
 * prefix. Its own documentation records the prefixing alternative as evaluated and rejected, and names
 * the consequence for callers: an authority-family predicate must be used and the ROLE-family
 * predicate must not. This chain therefore gates on authorities, and it names them through those two
 * constants rather than as string literals, so the compiler ties the rule to the converter that
 * produces the value.</p>
 *
 * <p>Trade-offs: getting that pairing wrong is silent in exactly the direction that matters, which is
 * why it is recorded here rather than left to be inferred. The ROLE-family predicate looks for a
 * prefixed authority this converter never produces, so it would match nothing, refuse every
 * administrative request with a forbidden response, and do so with a context that starts cleanly and a
 * test suite that stays green wherever it does not exercise an administrative route. The accepted cost
 * of pinning the pairing here is one paragraph that has to be revisited if the shared converter ever
 * changes its emission; the alternative was discovering the mismatch from behaviour in a deployed
 * environment.</p>
 *
 * <h2>Why the correlation filter is NOT registered here</h2>
 *
 * <p>Refactoring Rationale: this class previously declared the shared correlation filter as a bare
 * {@code @Bean} of the filter type, and that declaration is withdrawn. The shared kernel's
 * {@link com.carddemo.common.CardDemoCommonAutoConfiguration} already contributes a registration bean
 * for it over every request path at a fixed order, and its condition tests for the NAME of its own
 * registration bean -- which a differently named bean here did not satisfy -- so both registrations
 * survived and the filter sat in the chain twice.</p>
 *
 * <p>Alternatives Considered: three registration arrangements were available here and two are
 * rejected. A {@code FilterRegistrationBean<CorrelationIdFilter>} declared in this file would give
 * explicit ordering control, and is rejected because the shared kernel already declares one and a
 * second registration of one filter is what produced the duplicate described above. Adding a non-bean
 * instance to this chain with {@code addFilterBefore} would avoid a duplicate container registration,
 * and is rejected because it would cover only the requests this chain matches: a refusal produced
 * before the chain is entered, and a container-level error page, would then carry no correlation
 * identity, which is the one class of failure the identity is most needed for. Delegating to the
 * shared registration is the third option and the one taken, and the argument that decides it is not
 * brevity but ownership -- the shared filter's own documentation records that requiring a registration
 * in each of the eight services was rejected there, because a registration a service must remember is
 * one a service can omit, and omitting it yields a service that starts and serves requests while
 * silently emitting no correlation identity in any log line.</p>
 *
 * <p>Assumptions: the ordering position this class depends on is
 * {@link com.carddemo.common.CardDemoCommonAutoConfiguration#CORRELATION_FILTER_ORDER}, which is the
 * highest available precedence, and depending on it is what makes this file's silence correct rather
 * than merely shorter. Because that order places the filter ahead of the security filter chain, the
 * identity is in the logging context before any security filter runs, so an authentication or
 * authorization refusal decided below is logged with the same identity as a request that reached a
 * handler, and {@link CorrelationIdFilter#CORRELATION_ID_HEADER} is on the response either way. This
 * class therefore never names that header itself: re-declaring the name here would create a second
 * spelling of one wire contract, and the two could then disagree while both compiled.</p>
 *
 * <p>Assumptions: the duplicate was harmless and is withdrawn anyway, and both halves of that are
 * worth stating because the harmlessness is not obvious. {@link CorrelationIdFilter} carries its own
 * once-per-request guard -- a request attribute keyed on the class name, set on entry and removed only
 * by the pass that set it -- so its second pass delegates and returns without reading a header, minting
 * an identity, touching the logging context or writing a response header. One identity is minted per
 * request however many times the filter is registered. What the duplicate did cost is ownership and
 * legibility: two beans describing one filter, a second ordering position decided in a per-service
 * file, and a redundant filter in the chain of every request. Withdrawing it leaves one mechanism, one
 * order and one file to read.</p>
 *
 * <h2>What this class deliberately does not add</h2>
 *
 * <p>Alternatives Considered: no retry policy and no circuit breaker are placed on this chain, and
 * both were evaluated rather than overlooked. The framework's own core retry support was the candidate
 * for the first and is not used, because a retry belongs to a call that can be usefully repeated and
 * this class makes no outbound call at all: it decodes a presented token against keys the resource
 * server fetches and caches, and it decides an authorization outcome from claims already in hand. A
 * breaker was the candidate for the second and is rejected on a sharper ground -- the only synchronous
 * hop any operation behind this chain makes is in-VPC to a private endpoint behind an internal load
 * balancer, so a breaker would add a failure mode, a half-open state that refuses requests the
 * dependency would have served, without removing one. The prescribed posture is bounded connect and
 * read timeouts on that hop, declared where the hop is made rather than here, and the durable retry
 * tier for asynchronous work is queue redelivery with a dead-letter queue in the contexts that have
 * queues. This one has none.</p>
 *
 * <p>Trade-offs: the cost of that posture is that a dependency which is slow rather than down is
 * waited on until its read timeout expires on every request, where a breaker would have failed the
 * later ones immediately. It is accepted because the requests being waited on are the ones a caller
 * asked for, and because a breaker's own thresholds would then have to be tuned against a workload
 * with no service-level objective recorded anywhere in this repository to tune them against -- an
 * untuned breaker refusing valid requests is a worse outcome than a bounded wait. Adding either
 * mechanism later is a decision for the class that owns the outbound call, not for this one.</p>
 *
 * <p>Assumptions: no client library for the identity provider is referenced by this class. A presented
 * token is decoded from the pool's published signing keys, which is an ordinary retrieval the resource
 * server performs from the issuer location, so nothing here calls a provider operation. The
 * administrative provider client this context does need is declared by the sibling
 * {@code CognitoIdentityConfig} and consumed by the service layer, which keeps the credential-bearing
 * surface out of the class that decides authorization and lets this chain be exercised from a token
 * assembled in memory with no provider reachable at all.</p>
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
     * <p>Refactoring Rationale: this method is VISIBLE rather than private, and the visibility exists
     * for one reason: {@code SecurityConfigTest} applies the decision object this chain installs
     * instead of assembling an equivalent one from {@link #LOOPBACK_RANGES}. A test that rebuilt the
     * manager would agree with itself while the installed rule widened, which is the same drift the
     * inspectable rule table in this class exists to foreclose; the transaction context exposes its
     * own catch-all decision for exactly that reason.</p>
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
     * <p>Assumptions: this path is reachable WITHOUT a token because it has TWO consumers and neither
     * can present one. The load balancer target group registers this task by polling it, and the
     * container health check declared in this module's {@code Dockerfile} probes the same actuator
     * health endpoint on the port that image exposes; both are infrastructure contracts rather than
     * preferences, and neither participant holds a credential of any kind. A chain that demanded a
     * token here would fail every poll and the task would be replaced continuously while being
     * perfectly healthy, which is a failure that reads as an application fault rather than as an
     * authorization rule. The exception is bounded by what the endpoint discloses: it answers with a
     * status and, under this module's {@code show-details: never} setting, exposes no business data and
     * no component detail at all. Only the health group is opened; the remaining actuator endpoints
     * stay behind the chain.</p>
     *
     * <p>Assumptions: the pattern covers the health GROUP rather than the single health path, because
     * this module enables the liveness and readiness probe groups, which the framework publishes as
     * children of this path. Naming only the parent would leave both probes matched by no rule above
     * the catch-all, and the catch-all refuses. A profile that narrows the exposed endpoint list must
     * still publish health at this same fixed path for the same two consumers.</p>
     */
    public static final String HEALTH_PATH = "/actuator/health/**";

    /**
     * The management namespace, reachable only from inside the task.
     *
     * <p>Assumptions: this pattern is the last entry of {@link #OPERATOR_PATHS} and is therefore
     * MATCHED by the chain, granted by network position alongside the three endpoints named
     * individually below. It is matched after {@link #HEALTH_PATH}, so the more specific health
     * pattern keeps its own permit and the uncredentialed probes still reach it; everything else under
     * {@code /actuator} describes the deployment rather than answering a business question, so it
     * belongs to whoever is inside the container rather than to every holder of a valid token.</p>
     *
     * <p>Refactoring Rationale: this constant previously existed and was matched by NOTHING. Its own
     * documentation said so -- "no rule below grants the namespace as a whole" -- on the reasoning
     * that whatever the namespace covered beyond the three named endpoints "reaches no handler", so
     * leaving it to the catch-all cost nothing. That reasoning was wrong on a matter of fact:
     * {@code application-dev.yml} publishes {@code env}, {@code configprops} and {@code flyway}
     * beyond the base list, each of which DOES reach a handler, and each was therefore authorized by
     * the catch-all alone. The catch-all then required only {@code authenticated()}, which a validly
     * signed token carrying no CardDemo group satisfies, so the active profiles, the ordered
     * property-source list, every property name the task received and the migration history of the
     * {@code auth} schema were readable by any token the pool would issue.</p>
     *
     * <p>Assumptions: matching the NAMESPACE rather than enumerating those three ids is deliberate,
     * and it is what makes the rule survive a profile it was not written against. The exposure list is
     * a per-profile property that REPLACES rather than extends the inherited one, so an id can be
     * added in one file without this one being edited; a namespace rule authorizes whatever that file
     * adds, while a list of ids would silently omit it.</p>
     *
     * <p>Alternatives Considered: refusing the namespace outright with {@code denyAll()} instead of
     * granting it to the loopback address. Rejected because it would make the dev profile's own
     * exposure list unreadable by anything, including an operator with a shell inside the task, which
     * is the only consumer those three ids have -- publishing an endpoint and then denying every
     * possible caller states two contradictory intentions in two files. The loopback grant refuses
     * every caller off the box, which is the property the finding asked for.</p>
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
     * The metric registry path, reachable only from inside the task.
     *
     * <p>Assumptions: this endpoint is named EXPLICITLY rather than covered by the
     * {@link #MANAGEMENT_PATH} namespace pattern, because it is one of the endpoints this
     * module's exposure list publishes beyond health and it describes the deployment to
     * whatever reaches the port. Naming it is what lets the chain state a rule for it instead
     * of letting it inherit one.</p>
     */
    public static final String METRICS_PATH = "/actuator/metrics";

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
     * <p>Assumptions: the chain is BUILT from this list rather than the list describing the chain, so
     * a test can assert that the management namespace is covered and the assertion cannot pass while
     * the chain omits it. That is the same arrangement the route-to-authority table above uses, and
     * for the same reason: the defect this list closes was a constant that named the namespace while
     * no rule matched it.</p>
     *
     * <p>Assumptions: the three specific endpoints are retained ahead of the namespace even though all
     * four share one decision, so that the endpoints this module's base exposure list publishes are
     * readable here as names instead of having to be inferred from a wildcard. The namespace entry is
     * last because it is the backstop for whatever a profile adds, not the statement of what the base
     * publishes.</p>
     */
    private static final List<String> OPERATOR_PATHS =
            List.of(BUILD_IDENTITY_PATH, METRICS_PATH, METRIC_SCRAPE_PATH, MANAGEMENT_PATH);

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
     * missing entry here produced -- let it fall through to the catch-all, where it required a valid
     * access token without any rule saying so. Under the present catch-all the same omission is worse
     * and louder: {@code denyAll()} refuses renewal outright, so a missing entry breaks the operation
     * instead of quietly mis-gating it.</p>
     */
    public static final String REFRESH_PATH = "/api/v1/auth/refresh";

    /**
     * The user collection path, matched exactly.
     *
     * <p>Assumptions: the collection and the subtree beneath it are two patterns rather than one,
     * because a single-segment wildcard does not match an empty segment and a subtree pattern does not
     * match the collection itself. Writing only the subtree form would leave the list and create
     * operations matched by no rule at all.</p>
     *
     * <p>Refactoring Rationale: what that omission COSTS changed when the catch-all became
     * {@code denyAll()}, and both halves are worth recording. Under the previous
     * {@code authenticated()} catch-all it was a fail-OPEN: both operations stayed reachable by any
     * ordinary user, silently. Under the present rule it is a fail-CLOSED break: both would answer
     * 403 to an administrator. The second is a defect the first request that exercises the route
     * finds, which is the point of the change; the pattern pair is retained because these operations
     * must WORK, not merely be guarded.</p>
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
     *
     * <p>Assumptions: EVERY user-administration route demands the administrator authority, with no
     * read-only exception carved out for the list and detail operations, and that is a reading of the
     * baseline rather than a preference of this migration. Three independent artefacts of the baseline
     * agree on it. First, the four user-administration programs are reached by their OWN transaction
     * family rather than through any screen an ordinary user reaches: lines 449 and 450 of
     * {@code app/csd/CARDDEMO.CSD} define transaction {@code CU00} against program {@code COUSR00C},
     * and lines 459 and 460, 469 and 470, and 479 and 480 do the same for {@code CU01}, {@code CU02}
     * and {@code CU03} against {@code COUSR01C}, {@code COUSR02C} and {@code COUSR03C}. Second, those
     * programs return to the ADMINISTRATIVE menu by name and not to whatever screen invoked them:
     * {@code app/cbl/COUSR03C.cbl} moves the literal {@code 'COADM01C'} into its next-program field at
     * line 113, as the fallback when no invoking program was supplied, and again unconditionally at
     * line 124 on the cancel key, so the administrative menu is where these flows terminate whichever
     * key ends them. Third, the split being enforced is the baseline's own two-value domain, declared
     * at lines 26 to 28 of {@code app/cpy/COCOM01Y.cpy}: a one-character user type whose only two
     * condition values are the administrator and the ordinary user. A rule admitting an ordinary user
     * to any of these five operations would grant a privilege the baseline never granted, and on this
     * context specifically the operations in question create, alter and delete the very rows that
     * decide who is an administrator.</p>
     *
     * <p>Assumptions: the corroborating record at line 22 of {@code app/cpy/CSUSR01Y.cpy} is treated as
     * a SECOND WITNESS to that domain and not as its authority. That field declares the stored form of
     * the user type at the same width, but it carries no condition names -- the copybook has none
     * anywhere -- so the two admitted values are only readable from the communication-area copybook
     * cited above. Recording which of the two is authoritative keeps a later reader from concluding
     * that the domain is open because the stored declaration does not close it.</p>
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
         * <p>Alternatives Considered: the check lives in the compact constructor rather than at the
         * point the chain is built. Validating during chain assembly was the alternative and is
         * rejected because it would run only for rules the chain happens to consume, leaving a rule
         * that a test or a future caller constructs directly unchecked. Failing at construction means
         * an unenforceable rule cannot exist as a value at all, so the class-initialisation of
         * {@link SecurityConfig#AUTHORITY_RULES} is where a bad authority name surfaces -- at startup,
         * loudly -- instead of becoming a route that refuses every caller while reading as though it
         * authorised some.</p>
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
     * <p>Alternatives Considered: the backing list is returned directly rather than wrapped in a
     * defensive copy. A copy is the safer-looking option and is rejected here because
     * {@link #AUTHORITY_RULES} is built with an immutable factory whose result already refuses
     * mutation, so a copy would allocate on every call and, more importantly, would return a list that
     * is no longer the same object the chain was built from -- which is exactly the identity a contract
     * test relies on when it asserts that the rule it reads is the rule that is enforced.</p>
     *
     * @return the ordered rules; never {@code null} and never empty, and immutable
     */
    public static List<AuthorityRule> authorityRules() {
        return AUTHORITY_RULES;
    }

    /**
     * Returns the paths this context leaves reachable without a token.
     *
     * <p>Assumptions: callers treat this list as the COMPLETE set of paths reachable with no token,
     * which holds only because the chain's catch-all denies. It is returned as the backing immutable
     * list for the same reason given on {@link #authorityRules()}: the object a test inspects is then
     * the object the chain was built from.</p>
     *
     * @return the exact open paths; never {@code null} and immutable
     */
    public static List<String> unauthenticatedPaths() {
        return UNAUTHENTICATED_PATHS;
    }

    /**
     * Returns the paths this context grants by network position, in the order the chain applies them.
     *
     * <p>Assumptions: the ORDER is part of what this method reports, not an incidental property of the
     * list, because the management namespace entry is deliberately last and a caller checking that the
     * namespace is covered needs to see where it sits. It is returned as the backing immutable list for
     * the same reason given on {@link #authorityRules()}.</p>
     *
     * @return the ordered operator paths, the last of which is the management namespace; never
     *     {@code null} and immutable
     */
    public static List<String> operatorPaths() {
        return OPERATOR_PATHS;
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
     *     path is on the open list, on the operator list, or refused by the chain's catch-all
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
     * <p>Refactoring Rationale: sessions are STATELESS because the state a session would hold is the
     * state this migration removed, and naming that state precisely is what shows the policy is a
     * consequence rather than a default. The baseline is strictly pseudo-conversational: its task ends
     * at every screen turn, so all continuity between turns travels in one passed structure.
     * {@code app/cbl/COSGN00C.cbl} opens its {@code LINKAGE SECTION} at line 64 and declares the
     * inbound communication area at line 65, as a character table whose extent depends on a
     * monitor-supplied length -- a declaration spanning lines 66 and 67. Line 80 detects a first entry
     * by that length being zero. Lines 98 to 102 end the turn by returning to the monitor with the area
     * echoed back to the terminal, naming it at line 100. In the migrated form that structure decomposes
     * and nothing replaces it in this process: identity arrives as claims of a validated token,
     * selection context arrives in the request path, and navigation is a route change made on the
     * browser side. What that buys is concrete rather than stylistic -- with no session affinity and no
     * server-side session store, this service runs as horizontally-scaled Fargate tasks behind a load
     * balancer, any task can answer any request, and a task replaced mid-conversation costs a caller
     * nothing. Permitting a session would reintroduce the sticky routing that arrangement exists to
     * avoid, and on this context specifically it would give the sign-on operation somewhere to put
     * state, which is the thing being removed.</p>
     *
     * <p>Refactoring Rationale: the re-entry discriminator has no counterpart here at all, and its
     * absence is the clearest evidence that the policy above is not merely a configuration choice. The
     * baseline declares it at line 29 of {@code app/cpy/COCOM01Y.cpy} with two condition values at
     * lines 30 and 31 -- and those values are written as BARE numerals, in contrast to the QUOTED
     * character values the user type takes at lines 27 and 28 of the same copybook, which is worth
     * noticing by anyone transcribing either pair. A stateless handler has no first-entry-versus-
     * re-entry distinction to draw: it answers a request that carries everything needed to decide it,
     * and a rejected request comes back as a status with a field-error array. That severs a coupling
     * worth recording, because the baseline gates its field-highlight logic on that same re-entry flag,
     * whereas the migrated form drives error presentation purely from the response body.</p>
     *
     * <p>Trade-offs: cross-site request forgery protection is disabled, which for a
     * cookie-authenticated application would be a defect. It is not one here, and the reason is the
     * absence of an ambient credential rather than an appeal to convention: every authenticated request
     * carries a bearer token that a browser attaches only because this application's own client chose
     * to, never automatically the way a cookie is sent, so the confused-deputy condition the protection
     * defends against cannot arise. The three open paths carry no ambient credential either -- each is
     * authorised by a value in the request body -- and the stateless policy above leaves no cookie
     * session for a forged request to ride on. The accepted cost is that introducing any
     * cookie-authenticated route to this service would make this line wrong, so such a route must not
     * be added without restoring the protection alongside it.</p>
     *
     * <p>Refactoring Rationale: the catch-all DENIES rather than requiring authentication. The earlier
     * rule was {@code authenticated()}, justified on the ground that answering 403 for a path this
     * service does not publish "would tell an unauthenticated caller which paths exist". That
     * justification does not hold. An unauthenticated caller is answered by the entry point either
     * way and learns nothing from a deny; it is an AUTHENTICATED caller that could distinguish paths
     * under the old rule, receiving 404 for a path this service does not publish and a real answer
     * for one it does, so a uniform deny discloses strictly LESS. The concrete cost of the old rule
     * was that every path no rule above matched -- which for this module means whatever management ids
     * a profile publishes beyond the base four -- was granted to any valid token, including one
     * carrying no CardDemo group, because membership of neither group still satisfies
     * {@code authenticated()}.</p>
     *
     * <p>Trade-offs: an authenticated caller probing a path this service does not serve now receives
     * 403 where it previously received 404, so it can no longer tell an unserved path from an
     * unauthorized one. That is the intended direction -- the disclosure is removed rather than added
     * -- and the price is that a client debugging a mistyped path reads a refusal instead of a
     * not-found.</p>
     *
     * <p>Assumptions: with the catch-all denying, every reachable path must be granted by a rule ABOVE
     * it, so the three lists this class publishes -- {@link #unauthenticatedPaths()},
     * {@link #operatorPaths()} and {@link #authorityRules()} -- are the complete statement of what
     * this service serves. That is the property being bought: a route added without a rule fails
     * closed and is found by the first request that exercises it, instead of inheriting the widest
     * grant in the chain.</p>
     *
     * <p>Trade-offs: two paths this module's {@code springdoc} keys publish -- the generated document at
     * {@code /v3/api-docs} and the browsable view at {@code /swagger-ui.html} -- are matched by no rule
     * above and are therefore refused, where the previous catch-all admitted them to any validly signed
     * token. That is a real capability given up, and it is recorded at both ends: the keys publishing
     * them carry the same note. Restoring them would mean either opening a path that describes every
     * route of the SIGN-ON service to unauthenticated callers, or adding a fourth list of paths whose
     * only consumer is a developer's browser. Neither is worth the exposure while the committed contract
     * at {@code src/main/resources/openapi/auth-api.yaml} is the document of record; the card, reference
     * and authorization contexts reach the same outcome through the same rule.</p>
     *
     * @param http the chain builder; must not be {@code null}
     * @param authenticationConverter the token-to-authentication translation; must not be
     *     {@code null}
     * @param clock the clock the rendered refusal bodies read their failure instant from; must not be
     *     {@code null}
     * @return the configured chain, never {@code null}
     * @throws Exception when the chain cannot be built, which the builder declares
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
            JwtAuthenticationConverter authenticationConverter, Clock clock) throws Exception {
        // WHY : Trade-offs: the forgery protection is disabled because there is no ambient credential
        //       for a forged request to ride on. Authentication is a bearer token this application's
        //       own client attaches deliberately, never a cookie a browser sends on its own, so the
        //       confused-deputy condition cannot arise; the three open paths are authorised by a value
        //       in the request body. The cost is that adding any cookie-authenticated route to this
        //       service makes this line wrong and must restore the protection alongside it.
        http.csrf(csrf -> csrf.disable())
                // WHY : Refactoring Rationale: no session is created because the continuity a session
                //       would carry is what this migration removed. The baseline ends its task at every
                //       screen turn and echoes one communication area back to the terminal --
                //       COSGN00C.cbl line 65 declares it, line 80 detects a first entry from its
                //       length, and lines 98 to 102 return it, naming it at line 100. Identity now
                //       arrives as token claims and selection context in the request path, so nothing
                //       is left to hold, and the service runs as horizontally-scaled Fargate tasks
                //       behind a load balancer with no session affinity to preserve.
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(HEALTH_PATH).permitAll();
                    // WHY : Refactoring Rationale: this rule is built from OPERATOR_PATHS, whose last
                    //       entry is the management NAMESPACE. The preceding revision named only the
                    //       three endpoints the base exposure list publishes, and the comment here
                    //       claimed the rest of the namespace "reaches the operator rule rather than
                    //       the catch-all". It did not: nothing matched the namespace, so the three
                    //       ids application-dev.yml adds -- env, configprops and flyway -- were
                    //       authorized by a catch-all requiring only a valid token. Matching the
                    //       namespace is what makes the claim this comment used to make true.
                    // WHY : Assumptions: the namespace is matched AFTER the health group, so the more
                    //       specific health pattern keeps its own permit and the uncredentialed
                    //       probes still reach it, and BEFORE the open paths and the authority rules,
                    //       which it cannot shadow because it matches only /actuator.
                    // WHY : Assumptions: every path in the list is granted by NETWORK POSITION and
                    //       not by authority, because the only configured consumer of any of them is
                    //       the task-local collector sidecar, which presents no token, and an
                    //       operator with a shell inside the task. See LOOPBACK_RANGES.
                    requests.requestMatchers(OPERATOR_PATHS.toArray(String[]::new))
                            .access(loopbackOnly());
                    // WHY : Assumptions: the open paths are applied BEFORE the authority rules, so
                    //       that an exact open path cannot be shadowed by a broader rule declared
                    //       above it. Two broader rules are declared above and neither can shadow
                    //       one: the management pattern covers only /actuator, and no open path
                    //       lies under the user collection. Ordering it this way keeps that true if
                    //       a rule is ever widened.
                    for (String openPath : UNAUTHENTICATED_PATHS) {
                        requests.requestMatchers(openPath).permitAll();
                    }
                    // WHY : Assumptions: the AUTHORITY-family predicate is required and the ROLE-family
                    //       predicate must not be substituted for it. The contract source is
                    //       services/common-lib/src/main/java/com/carddemo/common/security/
                    //       JwtRoleConverter.java, whose finished API emits the identity provider's
                    //       group name verbatim with no framework role prefix. The role predicate
                    //       prepends that prefix on the caller's behalf, so it would look for an
                    //       authority the converter never produces, match nothing, and refuse every
                    //       administrative request with a forbidden response while the context started
                    //       cleanly -- a defect visible only in behaviour.
                    for (AuthorityRule rule : AUTHORITY_RULES) {
                        requests.requestMatchers(rule.pathPattern())
                                .hasAnyAuthority(acceptedAuthorities(rule.requiredAuthority()));
                    }
                    // WHY : Assumptions: denyAll and NOT authenticated, so a validly signed token
                    //       carrying neither CardDemo group reaches nothing at all. Every path this
                    //       contract publishes is granted by a rule above: the three open paths and
                    //       the two user-administration patterns cover all five published paths, and
                    //       AuthApiContractTest asserts that coverage against the contract itself
                    //       rather than against this list.
                    requests.anyRequest().denyAll();
                })
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
                .exceptionHandling(ApiErrorSecurityHandlers.renderingRefusals(clock));
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
     * <p>Assumptions: the shared converter is wired EXPLICITLY here because nothing else would wire it.
     * {@code services/common-lib/src/main/java/com/carddemo/common/security/JwtRoleConverter.java}
     * carries no stereotype annotation of any kind and lives outside this context's component-scan
     * root, so it is not a candidate for scanning; the shared kernel's auto-configuration contributes
     * the correlation filter, the clock, the money codec and the error advice, and deliberately not
     * this, because which routes demand which authority is a per-context decision. Omitting this bean
     * would leave the resource server's default converter in place, which reads a scope claim rather
     * than the group claim, so every token would authenticate and carry none of the two authorities the
     * rules above test for.</p>
     *
     * <p>Assumptions: the authorities this converter produces are the group names VERBATIM, which is
     * what obliges the rules above to use the authority-family predicate rather than the role-family
     * one. That emission is settled by the file named in the paragraph above and not here; the reason
     * the pairing is load-bearing, and the silent forbidden response a mismatch produces, are recorded
     * on this class.</p>
     *
     * @param configuredAdminGroupName the administrator group name from runtime configuration; must
     *     equal {@link JwtRoleConverter#ADMIN_AUTHORITY}
     * @param configuredUserGroupName the ordinary-user group name from runtime configuration; must
     *     equal {@link JwtRoleConverter#USER_AUTHORITY}
     * @return the authentication converter, carrying the shared group-to-authority translation and
     *     producing authorities the rules above test with the authority-family predicate; never
     *     {@code null}
     * @throws NullPointerException if either configured group name is absent, which the shared
     *     converter raises rather than tolerating, because a missing name cannot be compared with the
     *     compiled contract and silently skipping the comparison is the outcome this bean exists to
     *     prevent
     * @throws IllegalStateException if either configured group name differs from the compiled
     *     authority contract, raised while the context is being built so that a pool whose groups were
     *     renamed stops the service at startup instead of yielding one that authenticates every request
     *     and authorizes none
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
     * @param expectedClientId the app client identity a presented token must name; must not be
     *     {@code null} or blank, because the shared validator reads a blank value as an instruction to
     *     skip the client check entirely
     * @param requiredScope the scope a presented token must carry
     * @return the decoder, carrying the framework's issuer and time validation plus this migration's
     *     token-kind, client and scope validation; never {@code null}
     * @throws IllegalStateException if the configured token kind is not the one the shared validator
     *     enforces, because a deployment that asked for a different kind would be silently given the
     *     access-token check instead of the one it configured, or if the configured client id is blank,
     *     because the shared validator would then skip the client check with nothing saying so
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
        // WHY : Assumptions: a blank or absent client id is REFUSED rather than tolerated. The shared
        //       validator reads a blank value as "skip the client check", which is a correct contract
        //       only for a caller that has decided some other validator pins the client instead. No
        //       context in this repository has decided that -- the alternative would be the framework's
        //       audiences property, which the paragraph above rules out for this provider -- so a blank
        //       value here would drop the check silently, with the property still present in
        //       configuration and appearing to be in force. Failing at context build is the only outcome
        //       an operator can see. Alternatives Considered: defaulting to a compiled client id, which
        //       is worse: it would authorize tokens minted for a pool this deployment does not own.
        if (expectedClientId == null || expectedClientId.isBlank()) {
            throw new IllegalStateException(
                    "carddemo.security.jwt.expected-client-id must name the app client");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new CognitoAccessTokenValidator(expectedClientId, List.of(requiredScope))));
        return decoder;
    }
}
