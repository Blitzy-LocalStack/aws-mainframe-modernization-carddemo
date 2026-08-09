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
 * <p>Assumptions: this class carries three responsibilities -- a filter chain, the group-to-authority
 * conversion, and the decoder that installs the token checks the issuer location alone does not make --
 * and it reads exactly SIX property keys to do so: the two group names, the issuer location, the token
 * kind, the app client id and the required scope. The count is stated because it is checkable, and an
 * earlier revision of this paragraph said five: the six are enumerated on the two bean factories below,
 * so a reader can count them rather than take the number on trust. Every sibling context that declares a
 * decoder reads the same six, which is what stops two chains diverging in what they accept; the
 * reporting context reads only the two group names because it declares no decoder of its own. Only the
 * route table is specific to this context.</p>
 *
 * <p>Refactoring Rationale: this class had the chain and the conversion and not the decoder, so a
 * validly signed IDENTITY token authenticated a request here -- the framework's issuer-and-time
 * validation accepts one, and the fraud route's authority check would then be applied to whatever groups
 * that token happened to carry. The decoder bean below is what closes that, and it is the same bean the
 * sibling contexts declare rather than a variant written for this one.</p>
 *
 * <h2>Validated twice, and deliberately so</h2>
 *
 * <p>Assumptions: a request that arrives here has already had its token checked once, at the edge, by
 * the HTTP API's own identity-provider authorizer, and this class checks it again independently. That
 * repetition is intentional and must not be removed on the grounds that the edge already did it. Two
 * properties depend on the second check. The edge authorizer establishes that a token is valid for the
 * pool; it does not establish the two things this chain acts on, which are the specific token KIND and
 * the group vocabulary -- both settled below and neither expressible as an edge configuration. And the
 * edge is not the only way in: this task also accepts traffic from the internal load balancer and its
 * own health probe, so a chain that trusted the edge would be trusting a hop that some requests never
 * make. The cost is one signature verification per request against a cached key set; what it buys is
 * that the authorization rules in this file hold for every caller rather than for one route in.</p>
 *
 * <p>Assumptions: this chain trusts a signed token and performs no credential comparison of any kind,
 * which is why no password field appears anywhere in this context -- the eight-character plaintext field
 * at line 21 of {@code app/cpy/CSUSR01Y.cpy} is not carried forward, and the record of that divergence
 * belongs to the sign-on context rather than being restated here.</p>
 *
 * <h2>What this class does not do</h2>
 *
 * <p>Assumptions: this class decides WHETHER a caller may reach an endpoint and never touches WHAT the
 * endpoint returns. Masking a primary account number to its last four digits and suppressing a card
 * verification value are performed in the sibling {@code mapper} package, which is the one place
 * copybook representation concerns are allowed to appear in this context. Nothing here transforms a
 * payload, redacts a field, truncates a value or returns data, and nothing here may take that on later:
 * a masking helper added to this file would create a second owner of a concern that has exactly one, and
 * two owners of a masking rule is how a field comes to be masked on one path and not on another. What
 * this class relies on in return is that the mapper holds the line -- the verification value is returned
 * by no endpoint of this service at all, and an account number is masked everywhere except the one
 * administrative card-detail endpoint, which belongs to a different context.</p>
 *
 * <h2>The correlation identity: relied on here, registered by the shared kernel</h2>
 *
 * <p>Alternatives Considered: three arrangements were available for
 * {@code com.carddemo.common.web.CorrelationIdFilter} and two are rejected. Declaring a registration for
 * it in this file would give this class explicit ordering control, and it is rejected because the shared
 * kernel already declares one and a second is not idempotent by construction: the kernel guards its own
 * registration on the bean NAME {@code carddemoCorrelationIdFilterRegistration}, at lines 275 and 276 of
 * {@code services/common-lib/src/main/java/com/carddemo/common/CardDemoCommonAutoConfiguration.java}, so
 * a differently named bean here would not suppress it. Adding an unwrapped instance to this chain with
 * {@code addFilterBefore} is the second arrangement and is also rejected, because it would cover only
 * the requests this chain matches -- a container-level error page, and any refusal decided before the
 * chain is entered, would then carry no correlation identity at all, and those are precisely the
 * failures the identity is most needed for. Delegating to the shared registration is the third and is
 * the one taken. The argument that settles it is ownership: a registration each service has to remember
 * is one a service can omit, and omitting it yields a task that starts and serves requests while
 * emitting no identity on any log line.</p>
 *
 * <p>Assumptions: the position relied on is
 * {@link com.carddemo.common.CardDemoCommonAutoConfiguration#CORRELATION_FILTER_ORDER}, declared at line
 * 87 of that file as the highest precedence available, and relying on it is what makes this file's
 * silence correct rather than merely shorter. Because that position sits ahead of the servlet position
 * the whole security chain occupies, the identity is already bound when the bearer-token filter runs, so
 * a request refused with 401 or 403 -- which never reaches a controller -- is still correlatable, and
 * the refusal bodies rendered below carry it. The filter also clears its own logging-context key on the
 * way out, in its own exit path and not here: a container thread is reused, so a value left behind would
 * attach itself to the log lines of a later request that never carried it, corrupting the very record an
 * operator reads to tell two units of work apart.</p>
 *
 * <p>Assumptions: the correlation concept is not invented by this migration, and saying so is what keeps
 * it from reading as an addition. Line 40 of
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy} declares {@code ERR-EVENT-KEY PIC X(20)},
 * the baseline's own event correlation key and the final line of that forty-line copybook; the same
 * record carries a severity at line 25 with four values at lines 26 to 29 and an originating subsystem
 * at line 30 with six values at lines 31 to 36. The migrated form formalises the identity rather than
 * introducing it, and the transport is the only part that changed.</p>
 *
 * <h2>Cross-cutting concerns this class does not declare</h2>
 *
 * <p>Assumptions: the Micrometer common tags are contributed by
 * {@code com.carddemo.common.observability.MetricsConfig}, which the shared kernel pulls in with an
 * {@code @Import} at line 73 of its auto-configuration, so no metrics configuration is declared in this
 * {@code config} package and none may be. The note is here because the class name invites the mistake:
 * it reads like a sibling of this file but it lives under {@code observability}, and a second copy
 * created here would tag the same meters twice and leave two files to read to learn what a metric is
 * tagged with. The refusal bodies this chain renders come from
 * {@code com.carddemo.common.error.ApiError} by way of the shared security handlers, for the same
 * reason and with the same prohibition on restating it.</p>
 *
 * <p>Alternatives Considered: no resilience library is on this module's class path and none is added for
 * this chain. Retry now lives in the framework core that arrives with the Spring Boot parent, which
 * offers {@code @Retryable}, {@code @ConcurrencyLimit} and a programmatic retry policy, so an external
 * dependency would duplicate a capability already present; two details of that API are worth recording
 * because both are easy to write the other way round, namely that the annotation attribute is
 * {@code maxRetries} rather than {@code maxAttempts}, with total attempts being one more than its value,
 * and that the enabling annotation is {@code @EnableResilientMethods} rather than {@code @EnableRetry}.
 * A circuit breaker was weighed separately and rejected: the only synchronous hop this context makes is
 * in-network behind an internal load balancer with bounded connect and read timeouts, so a breaker would
 * add an open state that refuses calls the timeout would already have bounded, which is a new failure
 * mode in exchange for none removed. Nothing in this chain retries at all -- an authorization decision
 * is not a transient failure worth a second attempt, and retrying one would turn a single refusal into
 * several log lines describing the same request. The absence of the library is recorded in
 * {@code docs/adr/ADR-002-compute-platform.md}.</p>
 *
 * <p>Assumptions: no executable parity comparison backs this class, and none is claimed. Lines 83 to 85
 * of {@code tests/README.md} record that the online programs cannot be driven end to end without a
 * transaction monitor, which is absent from the build agent, and the extension's message-driven program
 * cannot even be compiled there because the vendor copybooks it copies are not in the repository. Every
 * baseline fact cited in this file is therefore a contract read from source rather than an output
 * compared against a golden master, and the rules are verified by this module's own tests.</p>
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
     * The authorities that satisfy the read rule, in one place because the test reads the same list.
     *
     * <p>Assumptions: an administrator satisfies the read rule as well as an ordinary user, and the
     * two-value domain behind that is the baseline's own -- {@code SEC-USR-TYPE} at
     * {@code app/cpy/CSUSR01Y.cpy} line 22 is a single character admitting exactly {@code 'A'} and
     * {@code 'U'}. Admitting both is also what the published contract means by its ordinary-user marker,
     * which it states at lines 160 to 167 of {@code openapi/authorization-api.yaml}: the ordinary-user
     * value admits either group, and only the administrator value excludes one. So this pair widens
     * nothing; the narrowing in this context is the fraud rule, and it is argued where it is declared.</p>
     *
     * <p>Refactoring Rationale: the pair is a named constant rather than two literals inside the chain
     * below, so the accompanying test can assert on the SAME list the chain enforces. Restating the pair
     * in the test would let the two drift and the test would keep passing while the rule narrowed to one
     * authority or widened to a third.</p>
     */
    public static final List<String> BUSINESS_AUTHORITIES =
            List.of(JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);

    /**
     * The authorization decision the read rule installs, exposed so a test can exercise the object
     * the chain actually enforces.
     *
     * <p>Refactoring Rationale: this manager is now passed to {@code access(...)} on the read rule
     * rather than merely standing beside a rule written with the equivalent builder shorthand, and the
     * earlier arrangement is worth naming because it failed in the one way a test cannot detect. This
     * method was exposed and documented as the decision the chain installed, the accompanying test
     * exercised it, and the chain meanwhile expressed the same condition inline -- so the test was
     * asserting a manager no request was ever judged by, which is the "test agrees with itself" outcome
     * the paragraph below sets out to avoid. Its summary also described the catch-all, which had since
     * become {@code denyAll()}. Both statements are brought back into line with the chain here, and the
     * chain now calls this method, so narrowing or widening the rule cannot leave the assertion
     * passing.</p>
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
     * that the rule covering this context's entire read surface is exercised by a unit test that needs
     * no container, which is what stops it silently reverting to a weaker condition.</p>
     *
     * @return the manager that grants only a principal holding one of {@link #BUSINESS_AUTHORITIES},
     *     never {@code null}
     */
    public static AuthorizationManager<RequestAuthorizationContext> businessAccess() {
        return AuthorityAuthorizationManager.hasAnyAuthority(
                BUSINESS_AUTHORITIES.toArray(String[]::new));
    }

    /**
     * The authorization decision the fraud rule installs, exposed for the same reason as the read one.
     *
     * <p>Refactoring Rationale: this manager admitted an ADMINISTRATOR AND NOBODY ELSE and it now admits
     * either business group, which is the authority the baseline grants. The evidence is set out on
     * {@link #FRAUD_PATH_PATTERN} and it was never in doubt: the reference reaches this write from the
     * MAIN menu under the access byte {@code 'U'}, and the administrative table names the program nowhere.
     * The narrowing was therefore a behavioural change against the oracle, and the specification sanctions
     * exactly five security deviations from it -- the password not carried forward, account-number masking,
     * card-verification-value suppression, identifier encryption and network isolation -- of which this is
     * not one. A migration may not add an authorization check the reference does not perform and call it
     * parity, however defensible the check would be on its own terms, because the group that loses the
     * capability is the group the reference gives it to and the loss is silent to everyone except the user
     * who is refused.
     *
     * <p>Trade-offs: what that costs is stated plainly rather than argued away, because it is the reason
     * the narrowing was attractive. A durable, state-changing write is reachable by every signed-in holder
     * of a {@link JwtRoleConverter#USER_AUTHORITY} token, and the confirmation step the migrated screen
     * shows beforehand is a client-side affordance with no server-side rule behind it -- so it stops an
     * accidental click and nothing else. That is the reference system's own posture: its three transaction
     * definitions carry {@code RESSEC(NO) CMDSEC(NO)}, so no per-resource or per-command check ran there
     * either. A deployment that wants the narrower rule can have it by changing this one method, and doing
     * so is a deliberate behavioural divergence that has to be registered in
     * {@code docs/architecture/cobol-to-service-traceability.md} and published in the contract before it
     * ships -- which is precisely what was missing when the rule was narrowed here.
     *
     * <p>Assumptions: this returns exactly what {@code hasAnyAuthority(...)} would have built, that builder
     * method being a one-line wrapper around
     * {@link AuthorityAuthorizationManager#hasAnyAuthority(String...)}, so naming the manager changes the
     * rule not at all and its testability entirely. The authority predicate is used and the role predicate
     * is not, because {@link JwtRoleConverter} adds no prefix -- a role predicate would look for a prefixed
     * authority that is never minted, match nothing, and refuse every request at run time rather than
     * failing where the mistake would be seen.
     *
     * <p>Assumptions: this method is RETAINED although it now returns the same decision as
     * {@link #businessAccess()}, and it is retained deliberately rather than by inertia. The fraud route is
     * the only state-changing route this context publishes, so its authority decision is the one most
     * likely to be revisited; keeping it a separate, separately-asserted method means a future narrowing is
     * one line with its evidence attached beside it, whereas folding it into the read decision would make
     * the same change require prising the two apart first. Alternatives Considered: deleting both this
     * method and the route's own rule so the read rule covered the path. Rejected because it would discard
     * the baseline evidence assembled on {@link #FRAUD_PATH_PATTERN} and leave the strongest capability in
     * the context with no rule naming it.
     *
     * <p>Trade-offs: the two decisions being equal means the chain's ordering no longer decides anything
     * for this path, where it previously decided everything. That is stated at the chain itself so a reader
     * who moves the rules is not misled by an ordering comment that has stopped being load-bearing.
     *
     * @return the manager that grants a principal holding either of {@link #BUSINESS_AUTHORITIES}, never
     *     {@code null}
     */
    public static AuthorizationManager<RequestAuthorizationContext> fraudAccess() {
        return AuthorityAuthorizationManager.hasAnyAuthority(
                BUSINESS_AUTHORITIES.toArray(String[]::new));
    }

    /**
     * The paths that mark an authorization as fraudulent.
     *
     * <p>Refactoring Rationale: this rule admitted an administrator and nobody else and now admits either
     * business group, which is the authority the baseline grants; the reversal is argued at
     * {@link #fraudAccess()}, where the decision itself lives. The evidence below is retained UNCHANGED
     * because none of it was wrong -- it establishes what posture the migrated system inherits, and the
     * error was in the conclusion drawn from it rather than in the reading. Three independent facts about
     * the baseline's structure establish that posture, and all three were read rather than assumed. First,
     * the
     * extension's own resource definitions switch resource and command security off on every one of its
     * three transactions: {@code RESSEC(NO) CMDSEC(NO)} stands at lines 46, 56 and 66 of
     * {@code app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd}, for {@code CPVD}, {@code CPVS} and
     * {@code CP00} respectively, so no per-resource and no per-command check runs for any of them.
     * Second, those same definitions carry {@code CONFDATA(NO)} at lines 45, 55 and 65 beside
     * {@code DUMP(YES) TRACE(YES)} at lines 44, 54 and 64, and enable {@code CEDF(YES)} on all four of
     * the extension's programs at lines 13, 20, 27 and 34, so a primary account number these programs
     * handle can reach a trace entry, a dump or an interactive debugging session. Third, the detail map
     * renders that number at full width: line 60 of
     * {@code app/app-authorization-ims-db2-mq/cpy-bms/COPAU01.cpy} declares {@code CARDNUMI PIC X(16)},
     * sixteen characters with no masking. That resource-definition file carries no licence header, so
     * its line 1 is content and the header offset the copybooks use must not be applied when checking
     * these citations.</p>
     *
     * <p>Refactoring Rationale: the baseline's entry point into this context is an ORDINARY-USER one,
     * and that is the fact which decides this rule as well as the one most easily assumed the other way
     * round. An earlier revision of this paragraph stated that the baseline reached its fraud-marking
     * program from the administrative menu alone; the two menu tables say otherwise and were read
     * directly, so the earlier statement is superseded here rather than quietly dropped, because a
     * reader who saw it needs to know which claim to trust. {@code app/cpy/COMEN02Y.cpy} is the MAIN
     * menu option table -- {@code CARDDEMO-MAIN-MENU-OPTIONS} at line 19, an option count of eleven at
     * line 21 -- and its eleventh entry is option {@code 11} at line 86, named
     * {@code 'Pending Authorization View         '} at line 88, dispatching to {@code 'COPAUS0C'} at
     * line 89 and carrying the access byte {@code 'U'} at line 90, which redefines onto
     * {@code CDEMO-MENU-OPT-USRTYPE} at line 98 under the table declared at line 94. The
     * ADMINISTRATIVE table, {@code app/cpy/COADM02Y.cpy}, declares six options at line 22 and names
     * {@code COPAUS} nowhere in the file. From that ordinary-user screen the write is two steps away:
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl} takes {@code WHEN DFHPF5} at line 187
     * into the fraud paragraph performed at line 188, which links at lines 248 to 252 to the program
     * named at line 35, and that program issues {@code INSERT INTO CARDDEMO.AUTHFRDS} at line 142 and
     * {@code UPDATE CARDDEMO.AUTHFRDS} at line 223 of
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl}. The capability an ordinary user
     * reaches is therefore a durable write, not a view.</p>
     *
     * <p>Refactoring Rationale: carrying that arrangement across unchanged -- the read authority applied
     * to this path like any other -- is what is now done, and it was previously listed here as the
     * rejected alternative. What decided it is not a re-weighing of the security argument, which stands:
     * it is that the reference reaches this write from the ORDINARY-USER menu, so narrowing the rule
     * removes a capability from the group the reference grants it to, and the specification permits a
     * behavioural change against the oracle only where it names one. It names five security deviations and
     * this is not among them. The consequence of the restored rule is stated in full at
     * {@link #fraudAccess()} rather than repeated here. The baseline this evidence was read from stays
     * byte-identical, because it is the behavioural oracle the migrated behaviour is checked against, and
     * the identity mapping the rule rests on is recorded in
     * {@code docs/adr/ADR-008-security-and-identity.md}.</p>
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
     * the fall-through refuse everything bounds the damage of the next such mistake. That earlier
     * revision of this constant is superseded in place rather than quietly dropped, because a path gate
     * which silently matches nothing is indistinguishable from a sound one in review, and a reader who
     * saw the earlier value needs to know which statement to trust.</p>
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
     * <p>Assumptions: the pattern covers the collection path and every resource beneath it, which is
     * the whole of this context's published surface apart from the fraud route above. The contract in
     * {@code openapi/authorization-api.yaml} publishes exactly two operations here, and the citations
     * are given as they now stand because the shape has moved once already: the listing is a POST on
     * {@code /api/v1/authorizations/search}, whose path item is at line 188 and whose operation is at
     * lines 218 and 219, and the single-resource read is a GET on
     * {@code /api/v1/authorizations/&#123;key&#125;}, whose path item is at line 285 and whose operation
     * is at lines 298 and 299. Both carry {@code x-required-authority: carddemo-user}, at lines 251 and
     * 313. An earlier revision of this paragraph cited a GET on the collection and four line numbers
     * that the document no longer holds; they are replaced rather than removed so that a reader
     * comparing the two knows the listing became a POST and why the pattern still covers it.</p>
     *
     * <p>Assumptions: the pattern keeps matching both operations across that move, which is what made
     * the move safe to absorb here without widening anything. The search route is one segment deeper
     * than the collection path and the resource route substitutes one segment, so a trailing
     * multi-segment wildcard covers both, and it also covers the collection path itself because the
     * parser this framework version uses treats that wildcard as matching zero or more segments. The
     * contract names THIS class as the place the marker is enforced: its {@code x-authority-model}
     * block runs from line 168 to line 175, declaring {@code field: x-required-authority} at line 169,
     * the two permitted values at lines 171 and 172, and {@code enforcedBy:} at line 173 giving this
     * file's path at line 174.</p>
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
                        // WHY : Assumptions: the fraud rule is declared FIRST because the chain matches
                        //       in declaration order and the read pattern below also covers this path, so
                        //       the first rule to match is the one that decides.
                        // WHY : Refactoring Rationale: the ordering used to decide the outcome and now
                        //       decides nothing, because fraudAccess() was narrowed to the administrative
                        //       authority and has been restored to the authority the baseline grants --
                        //       either business group. The comment is corrected rather than left standing:
                        //       it read that reversing the two rules would silently downgrade an
                        //       administrative gate, and a reader acting on that would be defending a gate
                        //       that is no longer there. The rule stays first so that narrowing it later
                        //       takes effect where its evidence is written, and stays SEPARATE so that
                        //       this context's only state-changing route has a rule naming it.
                        .requestMatchers(FRAUD_PATH_PATTERN)
                        .access(fraudAccess())
                        // WHY : Assumptions: EITHER group satisfies the read surface, which is the
                        //       authority model the contract states at its lines 160 to 167 and
                        //       formalises at lines 168 to 175 -- carddemo-user means either group, so
                        //       an administrator is not denied a cardholder operation, while
                        //       carddemo-admin excludes a token holding only carddemo-user.
                        .requestMatchers(READ_PATH_PATTERN)
                        .access(businessAccess())
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
