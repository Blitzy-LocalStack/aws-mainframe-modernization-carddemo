package com.carddemo.card.config;

import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.security.CognitoAccessTokenValidator;
import com.carddemo.common.security.JwtRoleConverter;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.PathContainer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
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
 * <h2>Assumptions: which baseline artifacts fix the two-value authority domain</h2>
 *
 * <p>The authority vocabulary is not invented here. {@code app/cpy/COCOM01Y.cpy} declares the field
 * at line 26 and closes its value domain at lines 27 and 28 with one condition name per value, the
 * administrator on the quoted literal {@code 'A'} and the ordinary user on {@code 'U'}; the stored
 * counterpart is the field at line 22 of {@code app/cpy/CSUSR01Y.cpy}, which carries no condition
 * name of its own and is therefore a second witness to the field rather than the authority for its
 * values. Two values in, two authorities out, and no third one reachable. Those two paths are read
 * as reference and are never modified, exactly as the reference suite's own guide records for the
 * whole baseline tree at lines 31 to 33 of {@code tests/README.md}; this class encodes what they
 * specify and does not redefine it, which is the same principle that guide states of itself at lines
 * 35 to 36.</p>
 *
 * <h2>Assumptions: what the baseline delegated outward, and what carries that weight here</h2>
 *
 * <p>All three card transactions are defined with resource-level and command-level security switched
 * off. {@code app/csd/CARDDEMO.CSD} carries {@code RESSEC(NO) CMDSEC(NO)} on one line per
 * transaction, at line 354 for the transaction whose program is named at line 348, at line 364 for
 * the one named at line 358, and at line 375 for the one named at line 369. Authorization for those
 * transactions was therefore not performed by the transaction monitor at all: it was delegated to an
 * external security manager for sign-on and to program logic for everything after it. That manager is
 * a platform component with no in-process counterpart on this side, which is a
 * platform-capability difference rather than a change of business behaviour, so its job is
 * redistributed onto token validation in this class and onto least-privilege task-role policy in the
 * infrastructure tree.</p>
 *
 * <p>Assumptions: the same file leaves {@code CONFDATA(NO)} on each of those transactions, at lines
 * 353, 363 and 374 respectively, so confidential-data suppression is off and the sixteen-character
 * card number declared at line 5 of {@code app/cpy/CVACT02Y.cpy} could appear in a monitor trace.
 * Here the equivalent values never reach a log line or an error payload, and the one route that
 * renders that number in full is the one route this class gates on the administrator authority. The
 * narrowing and encryption of those values are performed in this context's mapper layer; what this
 * class contributes is the part that makes the administrative exemption enforceable rather than
 * advisory.</p>
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
 * calls, which is the idiomatic form. Rejected here because a chained call is reachable only by
 * standing up an application context and issuing a request through it, whereas the published contract
 * this service must agree with is a committed YAML document that a plain unit test can read. With the
 * rules as a value both sides are readable by the same test at the same cost, so the comparison is
 * made on every build rather than only when an integration environment happens to exist. The chain is
 * still built from the table, so there is one statement of the rules and not two.</p>
 *
 * <p>Assumptions: the group-to-authority translation lives in {@code common-lib} and is imported
 * rather than restated, so all eight contexts agree about what an administrator is. Restating it per
 * service would let two services disagree about one claim, and the disagreement would surface as an
 * authorization gap rather than as a compile error.</p>
 *
 * <h2>Assumptions: how the shared kernel's request-pipeline components reach this context</h2>
 *
 * <p>This is the most easily misread thing about this class. The component scan implied by the
 * annotation on {@code CardApplication} is rooted at {@code com.carddemo.card}, while every shared
 * kernel type lives under {@code com.carddemo.common}, outside that root. A shared component is
 * therefore never discovered by scanning, and each one is registered deliberately. Two of them are
 * registered by this class because both take effect as part of the request pipeline it defines and
 * their position within that pipeline is part of their contract: the group-to-authority converter,
 * which the resource server consults after a token has been validated, and the access-token validator,
 * which has to be installed while the decoder is being built and so cannot be contributed from
 * outside. The scan root is never widened to {@code com.carddemo} to shorten that list, because a
 * wider root would pull every shared component into every service including the ones a given context
 * has no use for, and would replace registrations that can be read in two files with an implicit set
 * that changes silently whenever the shared kernel gains a component.</p>
 *
 * <h2>Why the correlation filter is NOT registered here</h2>
 *
 * <p>Refactoring Rationale: this class previously declared the shared correlation filter as a bare
 * {@code @Bean} of the filter type, and that declaration is withdrawn because it produced a second
 * registration of a filter the shared kernel already registers. The shared kernel's
 * {@code CardDemoCommonAutoConfiguration} contributes a registration bean for it over every request
 * path, and the framework additionally adapts ANY unwrapped filter bean it finds into a registration
 * of its own; the kernel's registration is guarded on the NAME of its own registration bean, which a
 * differently named bean here does not satisfy, so both survived.</p>
 *
 * <p>Refactoring Rationale: what a second registration actually costs is stated here as measured
 * rather than as guessed, because an earlier revision of this paragraph guessed and was wrong. It
 * claimed that both passes ran, that two identifiers were minted for one request, and that the inner
 * pass's cleanup stripped the identity from every line the outer pass then logged. None of that
 * happens. {@code CorrelationIdFilter} carries its own once-per-request guard -- a request attribute
 * keyed on the class name, set on entry and removed only by the pass that set it -- and its second
 * pass therefore returns after delegating, without reading a header, minting an identity, touching the
 * logging context or writing a response header. One identity is minted per request no matter how many
 * times the filter is registered, which is precisely why the duplicate was invisible.</p>
 *
 * <p>Assumptions: the duplicate is nonetheless withdrawn, and the reasons are ownership and
 * legibility rather than observable behaviour. Two beans describing one filter means a reader has to
 * find both to learn whether and where the filter runs; the second registration carries its own
 * ordering position, which is a cross-cutting decision this per-service file has no business making;
 * and a redundant filter sits in the chain of every request for the life of the service, delegating.
 * Withdrawing it leaves exactly one mechanism, one order and one file to read.</p>
 *
 * <p>Alternatives Considered: three ways to keep a registration in this class were weighed and all
 * three rejected. Declaring a registration bean here under the exact name the kernel's condition
 * tests for would suppress the kernel's own and leave one registration, but it would move a
 * cross-cutting concern's ordering decision into a per-service file where seven other services could
 * each choose differently. Keeping the bare filter bean and calling the disable method on a second
 * registration would leave two beans describing one filter, so a reader would have to find both to
 * learn whether the filter runs. Adding the filter to the security chain with a non-bean instance
 * would avoid the duplicate but would cover only requests this chain matches, missing container-level
 * error dispatches, which are exactly the requests whose logs most need an identity. Assumptions: the
 * kernel's registration is therefore the single mechanism, and this class does not reference the
 * filter at all.</p>
 *
 * <p>Trade-offs: the ordering position is consequently owned by the shared kernel, which places the
 * registration one step inside the outermost precedence so that the filter runs BEFORE the security
 * chain. That is the position this class depends on and the reason it is acceptable to give up local
 * control of it: the identity has to be in the logging context before any security filter executes,
 * or an authentication failure, which is handled entirely inside the chain, would be the one event
 * with no identity attached. The cost of the kernel owning the order is that changing it is a change
 * to a shared module and a release of all eight services; the gain is that the eight cannot disagree
 * about where in the pipeline correlation begins.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The health group of the management surface, which is the one path this chain leaves open.
     *
     * <p>Assumptions: exactly two components read this path and NEITHER of them can present a token.
     * The load-balancer target group polls it to decide whether a task receives traffic, and the
     * {@code HEALTHCHECK} instruction in {@code services/card-service/Dockerfile} polls it to decide
     * whether the container is alive. Requiring a credential here breaks both in the same deployment
     * and in a way that reads as a healthy service: the task starts, answers nothing either consumer
     * accepts, is deregistered and is replaced, indefinitely. The endpoint is safe to leave open
     * because it exposes no business data at all, which is a property of configuration rather than an
     * assertion: this module's {@code application.yml} sets both the detail and the component listing
     * of the health response to never, so the body is a status word and nothing else.</p>
     *
     * <p>Trade-offs: the pattern names the health GROUP and stops there, rather than the whole
     * management namespace. Opening the namespace would be one character shorter and would publish
     * every endpoint the exposure list admits, which for this module is also the build-identity
     * endpoint and the metric scrape path -- two descriptions of the service handed to anything that
     * reaches the port. The accepted cost of the narrower pattern is that a future probe on a
     * different management path needs an edit here; what it buys is that the exposure list and this
     * pattern are two independent narrowings of the same surface and neither substitutes for the
     * other.</p>
     *
     * <p>Assumptions: the group subtree rather than the bare aggregate path, because enabling the
     * probe groups adds a liveness and a readiness path ALONGSIDE the aggregate, and an orchestrator
     * check pointed at either of those is in the same no-credential position as the two consumers
     * above. The subtree form matches the aggregate as well as its groups, so the path the target
     * group and the container health check actually poll is covered by this one pattern.</p>
     */
    public static final String HEALTH_PATH = "/actuator/health/**";

    /**
     * The build-identity management path, reachable only from inside the task.
     *
     * <p>Assumptions: this path and the scrape path below are named EXPLICITLY rather than left to a
     * catch-all, because they are the two management endpoints this module's exposure list admits
     * beyond health -- {@code include: health,info,prometheus} in {@code application.yml} -- and each
     * describes the service to whatever reaches the port. Naming them is what lets the chain state a
     * rule for them instead of inheriting one.</p>
     */
    public static final String BUILD_IDENTITY_PATH = "/actuator/info";

    /**
     * The metric scrape path, reachable only from inside the task.
     *
     * <p>Assumptions: the only configured consumer is TASK-LOCAL. The collector sidecar scrapes
     * {@code 127.0.0.1:<container-port>} at {@code metrics_path: /actuator/prometheus} --
     * {@code infra/modules/ecs-service/main.tf} lines 154 to 172 -- and that scrape configuration
     * carries no authorization header at all, so it can present no token. Any rule requiring a token
     * would therefore break the scrape rather than secure it.</p>
     */
    public static final String METRIC_SCRAPE_PATH = "/actuator/prometheus";

    /**
     * The loopback addresses the task-local collector can reach this service from.
     *
     * <p>Assumptions: both families are listed because the address the container resolves loopback to
     * is a property of its network stack rather than of this configuration. Fargate tasks run with an
     * IPv4 address under the {@code awsvpc} mode this module uses, so the IPv4 form is the one expected
     * in practice; the IPv6 form is included so a stack that presents {@code ::1} does not silently
     * lose its metrics.</p>
     *
     * <p>Trade-offs: a loopback range rather than an authority is what grants telemetry, and the cost
     * is that a rule enforced by network position cannot be audited from the token. It is accepted
     * because the alternative is worse in both directions: requiring a token breaks the only consumer
     * that exists, and admitting any authenticated token -- which this chain previously did by falling
     * through -- grants the two endpoints to every principal the identity provider will issue a token
     * for, including one carrying no CardDemo group at all.</p>
     */
    private static final List<String> LOOPBACK_RANGES = List.of("127.0.0.1/32", "::1/128");

    /*
     * WHY : Alternatives Considered: one MANAGEMENT_PATH pattern of "/actuator/**" granted to
     *       ADMIN_AUTHORITY, with the catch-all admitting either group. Authored and REJECTED on
     *       the measured scrape configuration: infra/modules/ecs-service/main.tf configures the
     *       task-local collector to scrape https://127.0.0.1:<container-port>/actuator/prometheus
     *       every sixty seconds and sends NO authorization header of any kind, so an authority rule
     *       over the whole namespace would not narrow who may read metrics -- it would stop the
     *       only consumer that exists from reading them at all, and the loss would be silent
     *       because a scrape failure is not a request failure anybody sees. Granting the two named
     *       endpoints by network position keeps the scrape working while still refusing every
     *       caller that arrives through the load balancer, and the catch-all is denyAll rather than
     *       either-group so a route added without a rule fails closed instead of inheriting one.
     */

    /**
     * The paths this service publishes its own API description at.
     *
     * <p>Refactoring Rationale: this list exists because the chain granted none of these paths and the
     * catch-all below denies, so every one of them answered 403 to a valid token of either group and 401
     * without one -- while {@code springdoc} in {@code application.yml} pins all three of the addresses
     * they are served at and {@code spring.web.resources.static-locations} was extended specifically so
     * the packaged contract could be reached. Runtime testing found the whole of that arrangement
     * unreachable. The configuration and the chain now agree, and the agreement is asserted rather than
     * assumed: {@code ContractPublicationTest} reads the three pinned addresses out of the profile and
     * requires that a pattern here covers each of them, so moving a springdoc key without extending this
     * list fails the build instead of returning a refusal to whoever fetches the document.</p>
     *
     * <p>Assumptions: five patterns are needed for three configured addresses. The generated document
     * has a subtree beneath it because the browser view fetches its own configuration from
     * {@code /v3/api-docs/swagger-config}, and the view has an asset subtree because its markup, script
     * and stylesheet ship inside a webjar served from {@code /swagger-ui/}. Granting only the three
     * configured addresses would leave the page loading and then failing to render, which is a harder
     * failure to read than a refusal.</p>
     *
     * <p>Alternatives Considered: {@code permitAll}, which is what most deployments give a
     * documentation path and which would make the view usable from a browser with no further work.
     * Rejected because it would publish the complete shape of every operation, every field width and
     * every authority requirement of this service to anything that can reach the load balancer, and this
     * chain's whole posture is that nothing is reachable without a rule granting it. The document is not
     * a secret -- it is committed to the repository -- but publishing it anonymously from the running
     * service widens the reachable surface for no operational gain.</p>
     *
     * <p>Alternatives Considered: gating these on the administrator authority alone, on the reasoning
     * that reading an API description is an operator activity. Rejected because the description is the
     * contract an ordinary integrator writes a client against, and refusing it to the group that may
     * call every operation but one would make the document harder to obtain than the data it
     * describes.</p>
     *
     * <p>Trade-offs: because the grant is by authority, a browser opened straight at the view is
     * refused -- a navigation carries no bearer token. The page is reachable to a caller that presents
     * one, which is how the document is fetched for a contract comparison and how an operator reaches it
     * through a proxy that attaches a token. The accepted cost is that the view is not a
     * click-and-read page; the alternative was to make the entire API description anonymous, which the
     * paragraph above declines.</p>
     */
    private static final List<String> DOCUMENTATION_PATHS = List.of(
            "/v3/api-docs", "/v3/api-docs/**", "/card-api.yaml", "/swagger-ui.html", "/swagger-ui/**");

    /**
     * The one path that renders a primary account number in full.
     *
     * <p>Assumptions: the pattern carries the deployed {@code /api/v1} prefix because the service
     * receives it. No servlet context path is configured in this module's {@code application.yml} and
     * neither the load balancer nor the HTTP API rewrites a path, so a pattern written without the
     * prefix would match no request at all - and a security rule that matches nothing does not fail
     * closed, it falls through to the weaker rule beneath it.</p>
     *
     * <p>Assumptions: the value this pattern is compared against comes from the committed contract at
     * {@code src/main/resources/openapi/card-api.yaml}, where exactly one operation carries the
     * administrator authority and this is the path it sits on. That agreement is asserted rather than
     * assumed: a unit test in this package reads the contract, matches every published path against
     * this pattern, and requires that this one matches and that no other does.</p>
     *
     * <p>Assumptions: the segment matched by the single asterisk is the sixteen-digit card number,
     * which the published contract refuses at any other width or with any non-digit before a rule here
     * is consulted. Nothing in this pattern depends on that width; it is recorded so a reader comparing
     * the pattern against the contract knows what occupies that position.</p>
     *
     * <p>Refactoring Rationale: this pattern sits under its OWN {@code /api/v1/admin} prefix, and an
     * intermediate revision spelled it as a {@code /unmasked} suffix inside the card subtree. Under
     * that spelling the administrative path was also matched by {@link #CARD_SUBTREE_PATH_PATTERN},
     * so the authority of the one operation that discloses a full account number depended on this
     * table's ORDER: place the subtree rule first and an ordinary user reaches the administrative
     * read, with both rules still individually valid and nothing failing loudly. A separate prefix
     * removes that dependency outright, because no card-subtree pattern can match a path that does
     * not begin with the card collection. The published contract carries the same prefix, and the
     * routing at both infrastructure hops was extended to reach it.</p>
     */
    public static final String ADMIN_CARD_PATH_PATTERN = "/api/v1/admin/cards/*";

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
     * <p>Assumptions: this pattern can no longer match the administrative path, because that path
     * carries its own {@code /api/v1/admin} prefix rather than a suffix beneath this collection. The
     * administrative rule is nonetheless still ordered FIRST in the table below, so that the table
     * remains correct under the most-specific-first reading a reader applies to it and so that adding
     * a future card-subtree operation cannot reintroduce the shadowing this ordering once guarded
     * against.</p>
     */
    public static final String CARD_SUBTREE_PATH_PATTERN = "/api/v1/cards/**";

    /**
     * The authority rules of this context, most specific first.
     *
     * <p>Assumptions: the order of this list is the order the rules are evaluated in, matching how a
     * filter chain evaluates its matchers, so a reader may take the first match as the effective one.
     * The administrative rule precedes the two general card rules, which is no longer load-bearing
     * now that the administrative path sits under its own prefix; the reason it is kept is recorded
     * on {@link #CARD_SUBTREE_PATH_PATTERN}.</p>
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
     * Creates the configuration class.
     *
     * <p>Assumptions: declared explicitly rather than left implicit, because this type is constructed
     * from two places with different needs and a compiler-generated constructor documents neither. The
     * framework instantiates it once while building the application context, and this package's unit
     * test instantiates it directly to exercise the two factory methods below without starting a
     * context at all. Writing the constructor out is what lets both uses be stated here, and it is
     * public rather than narrower so the second use does not depend on the test sharing this
     * package.</p>
     */
    public SecurityConfig() {
        // Assumptions: no state is established here on purpose. Every value this class needs arrives
        // as a parameter of the factory method that needs it, so a field would be a second place the
        // same value could come from and the two could disagree.
    }

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
     * Reports the paths this service serves its own API description at.
     *
     * <p>Assumptions: these are deliberately NOT members of {@link #authorityRules()}, even though the
     * chain grants them the same way. That table is the published-OPERATION table: a test reads it
     * against the contract's operation list in both directions, and a documentation path -- which the
     * contract does not and should not declare as an operation -- would appear there as an operation the
     * contract is missing. Keeping the two lists separate is what lets each be complete on its own
     * terms.</p>
     *
     * @return the documentation paths, in the order the chain grants them; never {@code null} or empty
     */
    public static List<String> documentationPaths() {
        return DOCUMENTATION_PATHS;
    }

    /**
     * Reports the authority a caller must hold to reach one concrete request path.
     *
     * <p>Assumptions: the FIRST matching rule wins, which is how a filter chain behaves, so this
     * answer is the effective one rather than the union of every rule that could match.</p>
     *
     * @param requestPath the concrete path of a request, beginning with a solidus; must not be
     *     {@code null}
     * @return the minimum authority required, or {@code null} when no rule in the published-operation
     *     table matches. A {@code null} answer does NOT mean the path is unreachable: the chain also
     *     grants health, the two telemetry endpoints and the documentation paths of
     *     {@link #documentationPaths()} by rules that are not members of this table. It means only that
     *     the path is not a published operation, and a path that is neither an operation nor one of
     *     those is denied outright by the catch-all
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
     * Builds the authorization manager that admits a request only from a loopback address.
     *
     * <p>Assumptions: the manager is composed from the framework's own address manager rather than
     * written here, so the address-range parsing this rule depends on is the same implementation the
     * framework tests, and this class contributes only the choice of ranges. Composing with
     * {@code anyOf} makes the two families alternatives rather than requirements, which is what a
     * single-stack container needs -- it presents one loopback family, not both.</p>
     *
     * <p>Alternatives Considered: a network-level control instead, either a security-group rule or
     * binding the management endpoints to a second port not reachable from outside the task. The
     * security group was rejected because it cannot express a rule about a task's own loopback
     * interface -- traffic that never leaves the task is not subject to it -- and a separate
     * management port was rejected as a larger change than the finding requires: it would alter the
     * container contract, the scrape configuration and the task definition together, and it would
     * still need this chain to state a rule for whichever port served the endpoints. This rule is
     * additionally not the only control: the scrape target is loopback precisely so the endpoints are
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
     * <p>Refactoring Rationale: the catch-all DENIES rather than requiring authentication, and an
     * earlier revision required authentication on the ground that answering 403 for an unpublished
     * path "would tell an unauthenticated caller which paths exist". That reasoning does not hold, and
     * it had a concrete cost. It does not hold because an unauthenticated caller is answered by the
     * entry point either way and learns nothing from a deny; it is an AUTHENTICATED caller that could
     * previously distinguish paths, receiving 404 for one this service does not publish and a real
     * answer for one it does. A uniform deny discloses strictly less. The cost was that every path no
     * rule above matched -- which for this module means the two management endpoints its exposure list
     * admits -- was granted to any valid token, including one carrying no CardDemo group, because
     * membership of neither group still satisfies {@code authenticated()}.</p>
     *
     * <p>Assumptions: with the catch-all denying, every reachable path must be granted by a rule
     * ABOVE it, so the rules stated below are the complete statement of what this service serves.
     * There are four groups of them and each is granted on its own terms: health openly, because a
     * load-balancer probe presents no token; the two telemetry endpoints by network position, because
     * their only consumer is task-local; the published operations by the authority table; and the
     * documentation paths of {@link #documentationPaths()} by the ordinary-user authority. That is the
     * property being bought: a route added without a rule fails closed and is discovered immediately,
     * rather than inheriting the widest grant in the chain.</p>
     *
     * <p>Refactoring Rationale: the documentation group was the one that had to be added after the
     * catch-all was tightened. Closing the catch-all made every path without a rule unreachable, which
     * is the intended outcome, and the paths this service serves its own API description at had no rule
     * -- so tightening the chain silently withdrew the document that {@code springdoc} in
     * {@code application.yml} is configured at some length to publish. Runtime testing found all three
     * configured addresses answering 403 to a valid token. The lesson recorded here is that a fail-closed
     * catch-all makes the grant list a complete inventory, and an inventory has to include the paths a
     * library serves on this service's behalf as well as the ones its own controllers do.</p>
     *
     * <p>Assumptions: the administrative gate below is decided SERVER-SIDE from a claim the identity
     * provider signed, and there is no parameter anywhere in this chain through which a caller could
     * assert its own privilege. That is the substantive difference from the baseline, whose user type
     * travels in the session structure at line 26 of {@code app/cpy/COCOM01Y.cpy} -- storage the client
     * receives and echoes back on the next turn. The signature makes the claim unforgeable and the
     * absence of any such parameter makes the forgery unattempted, which is why the one route that
     * discloses a full card number is gated here rather than by anything the caller supplies.</p>
     *
     * <p>Alternatives Considered: two other places to make that decision. Deciding it in the browser
     * application by hiding the administrative screen was rejected outright, because a hidden screen
     * is a rendering choice and the route stays callable with any token. Deciding it at the edge
     * through route-level authorization scopes was rejected as a REPLACEMENT and adopted as an
     * addition: the edge cannot see a path this service later reshapes, and a request that reaches the
     * internal load balancer by any other route would bypass the check entirely, so the authoritative
     * gate belongs in the chain that serves the request and the edge check is a second, independent
     * one.</p>
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
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(HEALTH_PATH).permitAll();
                    // WHY : Assumptions: the two management endpoints are granted by NETWORK
                    //       POSITION and not by authority, because their only configured consumer
                    //       is the task-local collector sidecar, which presents no token. See the
                    //       rationale on LOOPBACK_RANGES for why an authority rule would break the
                    //       scrape and why falling through to authenticated() granted them far too
                    //       widely.
                    requests.requestMatchers(BUILD_IDENTITY_PATH, METRIC_SCRAPE_PATH)
                            .access(loopbackOnly());
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
                    // WHY : Assumptions: the documentation grant is stated AFTER the operation rules
                    //       and its position is immaterial, because the two path sets are disjoint --
                    //       no documentation path begins with the versioned API prefix and no
                    //       operation pattern can match one. That disjointness is asserted in
                    //       SecurityConfigTest rather than left to inspection, so a future pattern
                    //       that broke it could not silently reorder the effective rules.
                    // WHY : Assumptions: the required authority is the ordinary-user one, which
                    //       acceptedAuthorities widens to both CardDemo groups, so an administrator
                    //       reaches the document as well. The rationale for granting by authority at
                    //       all rather than openly is recorded on DOCUMENTATION_PATHS.
                    requests.requestMatchers(DOCUMENTATION_PATHS.toArray(String[]::new))
                            .hasAnyAuthority(acceptedAuthorities(JwtRoleConverter.USER_AUTHORITY));
                    // WHY : Assumptions: denyAll and NOT authenticated, so that a principal holding
                    //       a valid token but neither CardDemo group reaches nothing at all. Every
                    //       path this service serves is granted by a rule above.
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
     * <p>Assumptions: the authorities the shared converter emits are used VERBATIM, with no role
     * prefix added, and the contract source for that is
     * {@code services/common-lib/src/main/java/com/carddemo/common/security/JwtRoleConverter.java}.
     * That class builds each authority from the group name as it stands and its own documentation
     * states that a route demanding one is authorised with the authority predicate and never with the
     * role predicate. The rules built above therefore match on authorities, and the difference is not
     * cosmetic: the role predicate silently prepends a prefix before comparing, so pairing it with
     * these unprefixed names would compare {@code ROLE_carddemo-admin} against an authority spelled
     * {@code carddemo-admin} and refuse every administrative request. That failure compiles, passes
     * the documentation gate and passes every test that does not exercise an administrative route,
     * which is why the form is pinned to the converter rather than chosen here.</p>
     *
     * <p>Alternatives Considered: adding the prefix so the role predicate could be used, which is the
     * framework's own convention and would read more conventionally at the call site. Rejected because
     * the group names are already the vocabulary three independent consumers agree on -- this
     * converter, the browser application's token hook and that application's administrative routes --
     * so introducing a prefix would create a second spelling of each group and every path expression
     * would then have to know which of the two it was matching.</p>
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
        //       configuration and appearing to be in force. On this context the dropped check is the
        //       one standing between an identity token minted for ANOTHER app client of the same pool
        //       and the operation that returns an unmasked primary account number, so failing at
        //       context build is the only outcome an operator can be expected to notice.
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
