package com.carddemo.account.config;

import com.carddemo.account.api.AccountController;
import com.carddemo.account.api.CardXrefController;
import com.carddemo.account.api.CustomerController;
import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.security.InternalServiceToken;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Authorises the reads a caller that is a WORKLOAD rather than a signed-in person makes against this context.
 *
 * <h2>Why a second filter chain</h2>
 *
 * <p>Refactoring Rationale: this context authenticates every request against the identity provider, and the
 * cross-context account reads are made by a QUEUE CONSUMER rather than by a signed-in person -- so there is no
 * identity-provider token for it to present, and every one of those calls would have been refused. That refusal
 * is not a security outcome but an outage: the caller treats any non-not-found failure as the dependency being
 * unavailable, so each authorization would have been redelivered until the queue dead-lettered it. Adding those
 * paths to the human chain with a permissive rule would have been the other way to make the calls succeed, and
 * it is the wrong one -- the paths would then be open to any authenticated cardholder, and one of them resolves
 * a primary account number.</p>
 *
 * <p>Refactoring Rationale: this Javadoc read "the three internal reads another bounded context makes" while
 * the customer record read and the customer scan were landing on this chain. Neither of those is called by
 * another bounded context: they are here because {@code SecurityConfig.CUSTOMER_PATH_PATTERN} denies the whole
 * customer subtree on the human chain, since no baseline screen reaches a customer record directly and granting
 * a business group would add a capability the reference never had. The count was replaced rather than raised,
 * because a count goes stale on the next mounted route while the property -- a workload credential, not a
 * person's -- does not. The addresses themselves are enumerated once, in {@link #internalPaths()}.</p>
 *
 * <p>Assumptions: this chain is ordered BEFORE the identity-provider chain and matches only the addresses
 * {@link #internalPaths()} enumerates, so every other request falls through to the chain that was already
 * there. Ordering matters absolutely here: the framework offers a request to each chain in order and the first
 * whose matcher accepts it decides it, so a lower-precedence internal chain would never see a request at
 * all.</p>
 *
 * <p>Assumptions: the token is verified against a SYMMETRIC key rather than against the identity
 * provider's published keys. The reasoning belongs to the shared minter and is not restated here beyond the
 * consequence: verification involves no network call, so an internal read cannot fail because a key document
 * was unreachable.</p>
 *
 * <p>Refactoring Rationale: this context holds ONE KEY PER CALLING SERVICE and selects between them by the
 * token's own key identifier, where it used to hold one key shared by every caller. That is what makes the
 * subject claim worth checking: with a shared key any holder could write any subject, so the claim carried no
 * information and validating it would have refused an unknown name while still admitting a known one written
 * by the wrong party. With one key per caller, a token is verified against the key belonging to the subject it
 * NAMES, so a token minted with the transaction context's key under the authorization context's name fails its
 * signature check before any claim is read.</p>
 *
 * <p>Assumptions: SIX properties of the token are checked and not one. The signature says it was minted by a
 * holder of the key the token's own identifier names; the issuer distinguishes it from a token the identity
 * provider minted with an unrelated key; the audience says it was minted for THIS service, so a token intended
 * for another internal callee cannot be replayed here; the subject names a caller from a closed set; the key
 * identifier and the subject agree, so the caller a decision is recorded against is the caller whose key
 * signed; and the scope is one that caller is permitted to carry. Checking the signature alone would admit any
 * internal token for any purpose, which is the same mistake as having one credential for the whole system.</p>
 *
 * <p>Refactoring Rationale: authorisation is now PER OPERATION FAMILY, where the whole chain used to require a
 * single authority on every request it matched. Under the old rule a caller needing one address was authorised
 * for all eight, including the two customer addresses that expose a national identifier and a
 * government-issued identifier -- which the transaction context never reads. The families and the scope each
 * requires are declared once, in the rules below, and the closed table of which caller may carry which scope
 * lives with the minter so that the two ends cannot disagree.</p>
 *
 * <p>Trade-offs: the paths are enumerated from the controllers' own constants rather than written as a
 * pattern such as an internal path prefix. A prefix would be shorter and would automatically cover an
 * operation added later -- which is exactly the objection to it: a new operation would become reachable by an
 * internal caller without anyone deciding it should be. Enumeration makes each addition a visible edit here,
 * and it has already been paid: the customer scan and the customer record read were added as two further
 * explicit entries rather than admitted for free by a customer prefix.</p>
 */
@Configuration(proxyBeanMethods = false)
public class InternalApiSecurityConfig {

    /**
     * The precedence of this chain, ahead of the identity-provider chain.
     *
     * <p>Assumptions: the value is stated as a constant so a test can assert the ordering rather than a reader
     * having to compare two annotations in two files. It is well below the default precedence the other chain
     * takes, and the gap is deliberate: it leaves room for a chain that must run even earlier without either
     * value having to be renumbered.</p>
     */
    public static final int CHAIN_ORDER = 10;

    /**
     * The authority a verified internal token confers on the decision-path reads.
     *
     * <p>Assumptions: the prefix is stated once and composed into each authority below, so the rules and any
     * test agree on one spelling rather than on several that must match.</p>
     */
    private static final String AUTHORITY_PREFIX = "SCOPE_";

    /** The authority a token authorising the card cross-reference reads confers. */
    public static final String CARD_XREF_READ_AUTHORITY =
            AUTHORITY_PREFIX + InternalServiceToken.SCOPE_CARD_XREF_READ;

    /**
     * The authority a token must confer to reach the ONE cross-reference address that answers with an
     * unmasked primary account number.
     *
     * <p>Refactoring Rationale: the three cross-reference addresses answered to ONE authority, and one of
     * them is not like the other two. The card-keyed lookup answers with two identifiers and no card number,
     * the paged walk answers with the card masked to its last four digits, and the account-keyed lookup
     * answers with the whole sixteen digits -- because its consumer writes that value into its ledger row as
     * the key, which {@code READ-CXACAIX-FILE} at lines 576 to 604 of {@code app/cbl/COTRN02C.cbl} and the
     * same read at line 414 of {@code app/cbl/COBIL00C.cbl} both require. While one authority governed all
     * three, every holder of the cross-reference scope could provoke that disclosure, including the
     * authorization context, which reads the card-keyed form only. Splitting the disclosing address onto its
     * own authority is what makes the exposure purpose-bound rather than merely reachability-bound.</p>
     *
     * <p>Assumptions: the scope this authority is composed from is granted to the TRANSACTION subject alone
     * in {@code InternalServiceToken.PERMITTED_SCOPES}, so the narrowing is enforced at both ends -- the
     * other caller cannot mint the scope, and this chain refuses a token that does not carry it.</p>
     */
    public static final String CARD_XREF_RESOLVE_AUTHORITY =
            AUTHORITY_PREFIX + InternalServiceToken.SCOPE_CARD_XREF_RESOLVE_CARD_NUMBER;

    /** The authority a token authorising the account reads confers. */
    public static final String ACCOUNT_READ_AUTHORITY =
            AUTHORITY_PREFIX + InternalServiceToken.SCOPE_ACCOUNT_READ;

    /** The authority a token authorising the customer reads confers. */
    public static final String CUSTOMER_READ_AUTHORITY =
            AUTHORITY_PREFIX + InternalServiceToken.SCOPE_CUSTOMER_READ;

    /**
     * The authority a verified internal token must confer to reach a whole-customer-record read.
     *
     * <p>Refactoring Rationale: this chain required ONE read authority for every address it
     * matched, so ONE authority governed both the single-key decision reads and the two operations that
     * return or page over whole customer records. Those two groups have different blast radii and now have
     * different authorities. The escalation the single authority permitted was concrete: the two contexts
     * holding the decision authority mint it to resolve one card number, and while the customer record read
     * and the customer master scan answered to the same value, a token minted for that lookup could read
     * every field of any customer row and page the entire master. The reasoning behind the split is recorded
     * once, on
     * {@link com.carddemo.common.security.InternalServiceToken#SCOPE_CUSTOMER_MASTER_READ}.</p>
     *
     * <p>Assumptions: both authorities are verified by the SAME chain, decoder and validator set rather than
     * by a second chain. What differs between the two groups is the authority a token must carry, not how
     * the token is verified or which issuer and audience are acceptable, so a second chain would duplicate
     * every one of those decisions in order to vary none of them -- and two chains matching neighbouring
     * addresses would make which one applied depend on order rather than on the address.</p>
     */
    // WHY : Assumptions: this authority is currently UNREACHABLE, and that is the intended state rather
    //       than an unfinished one. Two controls meet here. The scope it is composed from appears in no row
    //       of the minter's per-subject table, deliberately, so no service can mint it; and this chain's
    //       verifier refuses any scope outside the presenting caller's own row, so a hand-signed token
    //       carrying it is refused as well. The consequence is that the two whole-customer-record
    //       operations stay PUBLISHED and stay addressable only once a subject is granted this scope in
    //       InternalServiceToken.PERMITTED_SCOPES -- a deliberate edit in a shared module, reviewed on the
    //       way in. Trade-offs: the earlier intent was that a holder of the internal signing key could
    //       still reach them; the caller-scope check removes even that, which is strictly narrower and is
    //       preferred, because a capability nobody needs today should cost a review to obtain rather than
    //       a key.
    public static final String INTERNAL_CUSTOMER_MASTER_AUTHORITY =
            "SCOPE_" + InternalServiceToken.SCOPE_CUSTOMER_MASTER_READ;

    /**
     * The exact paths this chain governs.
     *
     * <p>Assumptions: composed from the controllers' own published constants rather than from literals, so a
     * controller whose path moved could not leave this chain matching the old one -- which would silently
     * expose the moved path to the human chain instead.</p>
     *
     * <p>Refactoring Rationale: the set is assembled from GROUPS rather than declared as one flat list,
     * because the groups are governed by different authorities. Previously one list fed one rule, so
     * every address on the chain was reachable by every internal credential -- and two of them disclose a
     * whole customer record while a third discloses an unmasked primary account number. Composing the
     * security matcher from the same groups the authorization rules are written against is what keeps the
     * boundary of the chain and the boundary between the authorities from drifting apart: an address added to
     * a group is governed by that group's authority by construction, and one added to no group reaches no
     * handler at all.</p>
     *
     * <p>Trade-offs: package-visible rather than private, as are both groups. A test in this package asserts
     * that this matcher accepts exactly these internal paths and refuses every neighbouring one, which is
     * the property that decides whether a path is governed by this chain or by the human one, and asserts
     * each group separately so that which authority governs which address is assertable too. Reaching that
     * through the assembled chain instead would require a servlet container and would report a mismatch as a
     * status code, naming neither the pattern that matched nor the one that did not. Widening to package
     * visibility keeps the methods out of the module's API while making the discriminator directly
     * assertable.</p>
     *
     * <p>Assumptions: this method returns the UNION of the authority groups, because it decides only which
     * chain governs an address and every group is governed by this one. Which authority a given address
     * demands is decided by {@link #decisionReadPaths()} and {@link #customerMasterPaths()}, whose members
     * are disjoint and whose union is exactly the address set this chain claims.</p>
     *
     * <p>Refactoring Rationale: a THIRD member joined the union that is not a path group at all --
     * {@link #internalErrorDispatches()}, which claims the container's error dispatch of a request that was
     * addressed to one of these paths. It is part of the chain's security matcher rather than of a group
     * because it carries no authority of its own: it decides which chain, and therefore which RULE SET,
     * renders the error of a request a machine token made. Leaving it out let those dispatches fall to the
     * identity-provider chain, whose terminal rule requires a Cognito group authority that no machine token
     * carries.</p>
     *
     * @return a matcher accepting exactly the internal addresses of this context, and the error dispatches
     *     of those addresses, and nothing else, never {@code null}
     */
    static RequestMatcher internalPaths() {
        return new OrRequestMatcher(decisionReadPaths(), customerMasterPaths(),
                internalErrorDispatches());
    }

    /**
     * The addresses this chain governs, as within-application paths, in the order the groups declare them.
     *
     * <p>Purpose. {@link #internalErrorDispatches()} has to answer a question the path matchers cannot: not
     * "what is this request addressed to" but "what was the request that FAILED addressed to". On the
     * container's error dispatch the request target is the deployment's error page and the original target
     * survives only as the {@code jakarta.servlet.error.request_uri} attribute, so the decision is made
     * against a set of address strings rather than against a matcher over the current target.</p>
     *
     * <p>Trade-offs: this is a SECOND derivation of the same eight addresses, which is duplication accepted
     * on one condition -- that the two cannot drift apart silently. Both are composed from the controllers'
     * own published constants, so a moved path moves in both; and
     * {@code InternalApiSecurityConfigTest} asserts that every entry here is accepted by
     * {@link #internalPaths()} and that the count matches the enumerated groups, so an address added to a
     * group without being added here fails the build. The alternative -- wrapping the error-dispatched
     * request so the existing path matchers could be re-run against the original target -- was rejected
     * because a path matcher resolves its target through the framework's cached request path, which the
     * dispatcher re-derives for the error page, so the wrapper would decide against whichever of the two
     * representations the library happened to consult.</p>
     *
     * @return the within-application paths of every address this chain claims, never {@code null} and never
     *     empty
     */
    static List<String> internalAddresses() {
        return List.of(
                CardXrefController.BASE_PATH + CardXrefController.LOOKUP_PATH,
                CardXrefController.BASE_PATH + CardXrefController.LOOKUP_BY_ACCOUNT_PATH,
                CardXrefController.BASE_PATH + CardXrefController.SEARCH_BY_ACCOUNT_PATH,
                AccountController.BASE_PATH + AccountController.LOOKUP_PATH,
                CustomerController.BASE_PATH + CustomerController.LOOKUP_PATH,
                CustomerController.BASE_PATH + CustomerController.DISPLAY_PATH,
                CustomerController.BASE_PATH + CustomerController.RECORD_PATH,
                CustomerController.BASE_PATH);
    }

    /**
     * The container's ERROR dispatch of a request that was addressed to one of this chain's own addresses.
     *
     * <p>Purpose. Keeps the error page of an internal request on the chain that authenticated the request
     * itself. When the framework cannot write a response -- a caller whose accept header admits nothing the
     * converters produce is the reachable case -- the container re-dispatches to the deployment's error
     * page, and the security filters see that dispatch as a fresh request to a DIFFERENT address. Without
     * this matcher that address belongs to the identity-provider chain, so an internal caller's error page
     * is decided by rules written for browser callers: its terminal rule requires a Cognito group authority,
     * which no machine token carries, so the caller's real failure would be replaced by a refusal on a path
     * it never addressed.</p>
     *
     * <p>Measured: the refusal an unclaimed error dispatch produces is 401 and not 403, and the reason is
     * worth recording because it corrects the mechanism this matcher was first written against. The
     * framework's authentication filters extend {@code OncePerRequestFilter}, whose
     * {@code shouldNotFilterErrorDispatch} returns true, so {@code BearerTokenAuthenticationFilter} does not
     * run on an error dispatch and NO decoder is consulted by either chain -- the token is not
     * "reauthenticated under the wrong decoder", it is not authenticated at all. The authorization filter
     * does run, reaches the rules with no principal, and the refusal is rendered as unauthenticated. What
     * this matcher therefore preserves is which chain's RULES and refusal renderers govern an internal
     * address, in every dispatch, which is the regime the credential was admitted under.
     * {@code SecurityChainDispatchTest} records the three neutralisation measurements behind this.</p>
     *
     * <p>Assumptions: the original ADDRESS decides and the original METHOD is deliberately not consulted,
     * although the attribute for it does not exist and the dispatch preserves the method anyway. What this
     * matcher settles is which authentication regime renders the error of a request the caller made with a
     * machine token, and that is a property of the address the caller reached for rather than of the verb it
     * used. A request whose method no matcher in the groups claims never reached a handler either -- it is
     * exactly the kind of failure that produces an error dispatch -- so excluding it here would send the one
     * case this matcher exists for to the wrong chain.</p>
     *
     * <p>Assumptions: the context path is removed before the comparison, because the attribute carries the
     * full request target including it while the constants carry within-application paths. Comparing the two
     * unmodified would silently stop matching if the deployment were ever mounted under a prefix, and the
     * failure mode would be the wrong-decoder refusal this matcher exists to prevent.</p>
     *
     * <p>Alternatives Considered: matching every ERROR dispatch onto this chain, rather than only those whose
     * original address is internal. Rejected because the identity-provider chain would then never see an
     * error dispatch at all, and its own callers' error pages would be authenticated by the machine-token
     * decoder -- the same defect with the two regimes exchanged.</p>
     *
     * @return a matcher accepting exactly the error dispatches of this chain's own addresses, never
     *     {@code null}
     */
    private static RequestMatcher internalErrorDispatches() {
        List<String> addresses = internalAddresses();
        return request -> {
            if (request.getDispatcherType() != DispatcherType.ERROR) {
                return false;
            }
            Object originalTarget = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
            if (!(originalTarget instanceof String target)) {
                return false;
            }
            String contextPath = request.getContextPath();
            String withinApplication = contextPath != null && !contextPath.isEmpty()
                    && target.startsWith(contextPath)
                    ? target.substring(contextPath.length())
                    : target;
            return addresses.contains(withinApplication);
        };
    }

    /**
     * The addresses a decision read reaches, each demanding its own family's authority --
     * {@link #CARD_XREF_READ_AUTHORITY}, {@link #ACCOUNT_READ_AUTHORITY} or
     * {@link #CUSTOMER_READ_AUTHORITY}.
     *
     * <p>Assumptions: composed from the controllers' own published constants rather than from literals, so a
     * controller whose path moved could not leave this chain matching the old one -- which would silently
     * expose the moved path to the human chain instead.</p>
     *
     * <p>Trade-offs: package-visible for the same reason {@link #internalPaths()} is -- a test in this
     * package asserts which addresses fall in which authority group, and reaching that through the assembled
     * chain would report a mismatch as a status code naming neither pattern.</p>
     *
     * @return a matcher accepting exactly the five decision-read addresses and nothing else, never
     *     {@code null}
     */
    static RequestMatcher decisionReadPaths() {
        PathPatternRequestMatcher.Builder matchers = PathPatternRequestMatcher.withDefaults();

        // WHY : Refactoring Rationale: each matcher is bound to the METHOD its operation serves, where
        //   they were previously bound to a path alone. A path-only matcher claims every method at
        //   that address, and the account prefix is served by TWO surfaces -- the machine reads and the
        //   end-user screens -- separated by chain rather than by prefix. When the end-user edit shared
        //   the machine read's address, claiming its method here demanded the internal read scope from a
        //   browser token, which a browser token never carries, so the update was unreachable: a 403
        //   with no explanation.
        // WHY : Trade-offs: that overlap no longer exists, because every operation on the account prefix
        //   now sits on its own fixed sub-path -- the identifier had to leave the request line, so the
        //   end-user read, edit and cross-reference walk each gained a segment of their own. The method
        //   binding is KEPT anyway, as defence in depth: it costs one argument per matcher, and the
        //   overlap it was written for returns the moment a second method is mounted on any of these
        //   addresses, which is exactly the change a reader would not think to re-examine this chain
        //   for. Removing it to reflect present addresses would trade a standing guarantee for a
        //   tidier line.
        // WHY : Refactoring Rationale: every address in this group is now a POST on an explicit
        //   sub-path, where three of them were previously keyed GETs -- the account context read at
        //   /accounts/{accountId} and the customer probe at /customers/{customerId}, the latter claimed
        //   for both GET and HEAD because one handler served both. All three moved their identifier into
        //   a request body, so the path variables are gone and with them the two extra matchers the
        //   GET-and-HEAD pair required. The reason for the move is recorded on the request records
        //   themselves: an identifier in a path segment is composed into the load balancer's access
        //   record before any application code runs, and nothing inside a service can withdraw it.
        //   Alternatives Considered: keeping the keyed matchers beside the new ones so an older caller
        //   still resolved. Rejected -- a surviving keyed address would leave the disclosure this change
        //   exists to close reachable by anyone who addressed it.
        // WHY : Trade-offs: a method a matcher does not claim falls through to the application chain
        //   instead of being authorised here and refused by the dispatcher afterwards. For the two
        //   internal-only subtrees that is strictly narrower -- the application chain denies them
        //   outright -- so a GET to a lookup address is refused rather than authorised and then answered
        //   as an unsupported method.
        // WHY : Assumptions: the two account-keyed cross-reference operations are claimed HERE alongside
        //       the card-keyed one, because the application chain denies the whole cross-reference
        //       subtree and a path it denies that this chain does not claim reaches no handler at all.
        //       They belong on this chain rather than on the human one on the merits: no baseline screen
        //       reads a cross-reference row directly -- the account-view program reaches it only as a
        //       step inside its own composition at app/cbl/COACTVWC.cbl L723 -- so granting either to a
        //       business group would ADD a capability rather than preserve one, which is the same
        //       reading SecurityConfig.CARD_XREF_PATH_PATTERN records for the subtree as a whole.
        return new OrRequestMatcher(cardXrefResolvePaths(matchers), cardXrefPaths(matchers),
                accountPaths(matchers), customerPaths(matchers));
    }

    /**
     * The cross-reference addresses that disclose NO card number, which the cross-reference read scope
     * authorises.
     *
     * <p>Refactoring Rationale: this group held all THREE cross-reference addresses, on the ground that they
     * "resolve the same row keyed three ways, so a caller entitled to one is entitled to the others on the
     * same grounds", and that splitting them "would produce three values always issued together". The
     * account-keyed lookup has been moved out to {@link #cardXrefResolvePaths}, because that reasoning holds
     * for how the addresses are KEYED and not for what they DISCLOSE: this group's two answer with
     * identifiers and with a card number masked to its last four digits, while the one removed answers with
     * the whole sixteen digits. Nor are the two scopes always issued together -- the authorization context
     * holds this one alone.</p>
     *
     * <p>Assumptions: the two that remain are one group on the original grounds, which still apply between
     * them: neither publishes a primary account number, so a caller entitled to either is entitled to the
     * other.</p>
     *
     * <p>Trade-offs: package-visible rather than private, as the sibling groups are, so a test in this
     * package can assert which of the two cross-reference authorities governs which address directly.
     * Reaching that through the assembled chain would report a mismatch as a status code, naming neither
     * the group that matched nor the one that did not -- and the property being asserted here is exactly
     * which group an address falls in.</p>
     *
     * @param matchers the builder each pattern is composed on; must not be {@code null}
     * @return a matcher accepting exactly the two non-disclosing cross-reference addresses, never
     *     {@code null}
     */
    static RequestMatcher cardXrefPaths(PathPatternRequestMatcher.Builder matchers) {
        return new OrRequestMatcher(
                matchers.matcher(HttpMethod.POST,
                        CardXrefController.BASE_PATH + CardXrefController.LOOKUP_PATH),
                matchers.matcher(HttpMethod.POST,
                        CardXrefController.BASE_PATH + CardXrefController.SEARCH_BY_ACCOUNT_PATH));
    }

    /**
     * The one cross-reference address that answers with an unmasked primary account number, which
     * {@link #CARD_XREF_RESOLVE_AUTHORITY} authorises.
     *
     * <p>Purpose. The account-keyed lookup resolves an account to the card number its lowest-ordering card
     * holds, and it carries that value in full because its consumer writes it into the ledger row as the
     * row's key -- the transcribed form of {@code READ-CXACAIX-FILE} at lines 576 to 604 of
     * {@code app/cbl/COTRN02C.cbl} and of the same read at line 414 of {@code app/cbl/COBIL00C.cbl}. A
     * masked value would be a different key, so the disclosure cannot be withdrawn without losing parity;
     * what a separate authority does is bound who can provoke it.</p>
     *
     * <p>Assumptions: this rule is declared BEFORE {@link #cardXrefPaths} in the chain below, and the order
     * is a correctness requirement rather than a convention. The framework evaluates authorization rules in
     * declaration order and stops at the first whose matcher accepts the request, so with the wider group
     * stated first this address would be authorised by the read authority and the split would be inert while
     * looking complete -- the same trap the customer-master rule records.</p>
     *
     * <p>Trade-offs: package-visible for the reason recorded on {@link #cardXrefPaths}: the split is only
     * a control if the disclosing address is matched by this group and by no wider one, and that is a
     * property of the matchers rather than of any response.</p>
     *
     * @param matchers the builder the pattern is composed on; must not be {@code null}
     * @return a matcher accepting exactly the account-keyed cross-reference lookup, never {@code null}
     */
    static RequestMatcher cardXrefResolvePaths(PathPatternRequestMatcher.Builder matchers) {
        return matchers.matcher(HttpMethod.POST,
                CardXrefController.BASE_PATH + CardXrefController.LOOKUP_BY_ACCOUNT_PATH);
    }

    /**
     * The account address, which {@link #ACCOUNT_READ_AUTHORITY} authorises.
     *
     * <p>Assumptions: the account read is its own family rather than a member of the cross-reference one,
     * even though both callers hold both scopes today. The two answer different questions -- one resolves a
     * card number to the identifiers behind it, the other returns that account's limits and balance -- so a
     * credential issued for the first has no business answering the second, and separating them is what
     * lets a future caller be given one without the other.</p>
     *
     * <p>Assumptions: the address is a {@code POST} on an explicit sub-path rather than a keyed {@code GET},
     * for the reason the request record itself states: an identifier in a path segment is composed into the
     * load balancer's access record before any application code runs, and nothing inside a service can
     * withdraw it.</p>
     *
     * @param matchers the builder the pattern is composed on; must not be {@code null}
     * @return a matcher accepting exactly the account internal address, never {@code null}
     */
    private static RequestMatcher accountPaths(PathPatternRequestMatcher.Builder matchers) {
        return matchers.matcher(HttpMethod.POST,
                AccountController.BASE_PATH + AccountController.LOOKUP_PATH);
    }

    /**
     * The customer decision addresses, which {@link #CUSTOMER_READ_AUTHORITY} authorises.
     *
     * <p>Assumptions: this family is the reason the scopes are split at all. The customer addresses reach a
     * record carrying a national identifier and a government-issued identifier, and only ONE of the two
     * callers reads them -- so a separate scope is what lets this context refuse the other caller rather
     * than trusting it not to ask.</p>
     *
     * <p>Assumptions: the two addresses that answer with, or page over, WHOLE customer records are NOT in
     * this family. They are claimed by {@link #customerMasterPaths()} under an authority nothing currently
     * mints, because the difference between confirming one row and exfiltrating a file is not a difference
     * of degree.</p>
     *
     * <p>Refactoring Rationale: the nine-field screen display read joined this family rather than the
     * customer-master one, and the placement IS the fix it was authored for. Its consumer had been reading
     * display fields out of the existence check's bodiless response, so the fields rendered absent on every
     * request; the two shapes available to it were that empty body and the whole record, and pointing it at
     * the whole record would have required minting the master authority for a context that renders six
     * fields. The display projection carries no encrypted identifier and no credit score, so it belongs on
     * the side of this split that confirms a row rather than the side that reads a file.</p>
     *
     * @param matchers the builder each pattern is composed on; must not be {@code null}
     * @return a matcher accepting exactly the two customer decision addresses, never {@code null}
     */
    private static RequestMatcher customerPaths(PathPatternRequestMatcher.Builder matchers) {
        return new OrRequestMatcher(
                matchers.matcher(HttpMethod.POST,
                        CustomerController.BASE_PATH + CustomerController.LOOKUP_PATH),
                matchers.matcher(HttpMethod.POST,
                        CustomerController.BASE_PATH + CustomerController.DISPLAY_PATH));
    }

    /**
     * The addresses that read whole customer records, which demand
     * {@link #INTERNAL_CUSTOMER_MASTER_AUTHORITY}.
     *
     * <p>Purpose. The two access paths the batch reader {@code app/cbl/CBCUS01C.cbl} declares -- the read
     * positioned by {@code RECORD KEY IS FD-CUST-ID} at L32, and the ascending sweep its loop at L74 through
     * L81 performs.</p>
     *
     * <p>Assumptions: both are claimed by this chain rather than left to the application chain, and the
     * reason is that the application chain DENIES the whole customer subtree outright --
     * {@code SecurityConfig.CUSTOMER_PATH_PATTERN} is refused there because no baseline screen reaches a
     * customer record directly, so granting a business group would add a capability the reference never had.
     * Their reference carries no {@code EXEC CICS} verb and appears in no resource definition, so it is a
     * batch reader with no terminal behind it and a workload credential is the only one that fits. Leaving
     * either address unclaimed would mount a handler nothing could reach and report it as a refusal nobody
     * could explain.</p>
     *
     * <p>Assumptions: the collection address is claimed for {@code GET} alone while the record address is
     * claimed for {@code POST} alone, because each publishes exactly one method. The scan carries no
     * identifier at all -- it is positioned by an opaque cursor and bounded by a size -- so it needs no
     * request body and keeps the method its shape implies; the record read carries the nine-digit key, so it
     * takes a body for the reason its request record states. Claiming any further method would authorise one
     * the dispatcher would then refuse, where leaving it unclaimed lets the application chain deny it first,
     * which for this subtree is the narrower of the two.</p>
     *
     * @return a matcher accepting exactly the two customer-master addresses and nothing else, never
     *     {@code null}
     */
    static RequestMatcher customerMasterPaths() {
        PathPatternRequestMatcher.Builder matchers = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(
                matchers.matcher(HttpMethod.POST,
                        CustomerController.BASE_PATH + CustomerController.RECORD_PATH),
                matchers.matcher(HttpMethod.GET, CustomerController.BASE_PATH));
    }

    /**
     * Builds the chain that authorises the internal reads.
     *
     * <p>Assumptions: the chain is stateless and stores no session, matching the other chain and matching the
     * caller -- which presents a freshly minted token on every request and has nothing to keep between them.
     * </p>
     *
     * <p>Assumptions: cross-site request forgery protection is disabled on this chain. It defends a
     * cookie-authenticated browser session, and there is no browser and no cookie on this path: the credential
     * is a bearer token the caller has to construct, so a third-party site cannot cause it to be sent.</p>
     *
     * <p>Assumptions: the refusal bodies are rendered by the shared handlers, so a refusal on an internal path
     * has the same problem-document shape as one on a business path. Without them the framework answers with a
     * status and a challenge header and no body, and the caller's own error handling reads an unparseable
     * response as a dependency failure rather than as an authorization one.</p>
     *
     * @param http the chain builder; must not be {@code null}
     * @param decoder the internal token decoder; must not be {@code null}
     * @param clock the clock the rendered refusal bodies read their failure instant from; must not be
     *     {@code null}
     * @return the configured chain, never {@code null}
     * @throws Exception when the chain cannot be built, which the builder declares
     */
    @Bean
    @Order(CHAIN_ORDER)
    public SecurityFilterChain internalApiFilterChain(HttpSecurity http,
            @InternalTokenDecoder JwtDecoder decoder, Clock clock) throws Exception {
        return http
                .securityMatcher(internalPaths())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // WHY : Refactoring Rationale: two rules where there was one, and the ORDER between them
                //       is what makes the narrower authority effective. The customer-master rule is
                //       stated FIRST because Spring Security evaluates these rules in declaration order
                //       and stops at the first whose matcher accepts the request -- so with the
                //       catch-all first, a whole-record read would have been authorised by the decision
                //       authority before the narrower rule was ever consulted, and the split would have
                //       been inert while looking complete. Stating the specific rule ahead of the
                //       catch-all is therefore a correctness requirement rather than a convention.
                // WHY : Trade-offs: the second rule stays a catch-all over this chain's matcher rather
                //       than re-enumerating the five decision addresses. The chain's own security
                //       matcher already admits exactly the union of the two groups, so anything reaching
                //       the second rule is a decision address by construction; re-listing them would
                //       put the same enumeration in two places, and the copies would drift the first
                //       time an address was added to one of them. What is accepted is that adding a
                //       matcher to internalPaths without adding it to one of the two groups would give
                //       it the decision authority by default -- which is why internalPaths is composed
                //       FROM the two groups rather than written independently of them, so no address can
                //       reach this chain without having been placed in a group first.
                // WHY : Purpose: the container's ERROR dispatch is admitted before any authority rule is
                //       consulted, so the error page of a request this chain authenticated is rendered
                //       rather than refused. Every rule below ends at denyAll, and the error page is not
                //       one of this chain's addresses, so without this rule an internal caller whose
                //       response could not be written received 403 on a path it never addressed instead of
                //       the failure it actually provoked.
                // WHY : Assumptions: the rule matches the DISPATCHER TYPE and not the error path's name,
                //       because the deployment owns that mapping and this configuration does not. A
                //       path-based permit would additionally let any caller address the error page
                //       directly and provoke the container's own error body, which no operation in
                //       openapi/account-api.yaml declares.
                // WHY : Assumptions: it is stated here even though carddemo-common-defaults.yml registers
                //       the security filter for REQUEST and ASYNC only, which keeps a deployed chain from
                //       seeing an error dispatch at all. A chain has to state its own security intent: a
                //       sliced or hand-wired context builds this chain WITHOUT that property, and the
                //       property is a registration detail that a later deployment could widen without
                //       anyone re-reading this file.
                // WHY : Measured: admitting the dispatch gives up nothing that authentication was
                //       protecting, because authentication does not run on an error dispatch at all. The
                //       framework's authentication filters extend OncePerRequestFilter, whose
                //       shouldNotFilterErrorDispatch returns true, so BearerTokenAuthenticationFilter is
                //       skipped and no decoder is consulted; the authorization filter is not one of those
                //       and does run, which is why an unadmitted dispatch is refused as UNAUTHENTICATED
                //       rather than forbidden -- it reaches the rules with no principal. The original
                //       request's own authorization decision was already taken, under this chain's rules
                //       and this chain's decoder, before the response could not be written.
                // WHY : Trade-offs: what is accepted is that the error page of an internal request is
                //       rendered without a principal, so the shared advice attributes it to the
                //       correlation identifier rather than to a caller. That is the same information a
                //       container's own error page carries, and the alternative -- refusing the dispatch
                //       to keep an attribution -- destroys the response the caller was owed in order to
                //       improve a log line.
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(customerMasterPaths())
                        .hasAuthority(INTERNAL_CUSTOMER_MASTER_AUTHORITY)
                        .requestMatchers(
                                cardXrefResolvePaths(PathPatternRequestMatcher.withDefaults()))
                        .hasAuthority(CARD_XREF_RESOLVE_AUTHORITY)
                        .requestMatchers(cardXrefPaths(PathPatternRequestMatcher.withDefaults()))
                        .hasAuthority(CARD_XREF_READ_AUTHORITY)
                        .requestMatchers(accountPaths(PathPatternRequestMatcher.withDefaults()))
                        .hasAuthority(ACCOUNT_READ_AUTHORITY)
                        .requestMatchers(customerPaths(PathPatternRequestMatcher.withDefaults()))
                        .hasAuthority(CUSTOMER_READ_AUTHORITY)
                        .anyRequest()
                        .denyAll())
                .oauth2ResourceServer(server -> server
                        .authenticationEntryPoint(ApiErrorSecurityHandlers.entryPoint(clock))
                        .accessDeniedHandler(ApiErrorSecurityHandlers.accessDeniedHandler(clock))
                        .jwt(jwt -> jwt
                                .decoder(decoder)
                                .jwtAuthenticationConverter(internalAuthenticationConverter())))
                .exceptionHandling(ApiErrorSecurityHandlers.renderingRefusals(clock))
                .build();
    }

    /**
     * Builds the decoder that verifies an internal token against the key of the caller it names.
     *
     * <p>Assumptions: the decoder is pinned to ONE algorithm, taken from the shared constant. A decoder that
     * accepted whatever its input's header named is how a verifier comes to accept an unsigned token, so the
     * algorithm is stated on this side rather than read from the token.</p>
     *
     * <p>Refactoring Rationale: the decoder is assembled from a key SET rather than from a single secret key,
     * and each member carries the subject it belongs to as its identifier. The library then selects the key by
     * the token's own identifier, so the signature check proves the subject the token claims. The single-key
     * form it replaces could not do that: every caller signed with the same bytes, so a token minted by one
     * caller under another caller's name verified perfectly.</p>
     *
     * <p>Alternatives Considered: holding both keys as unlabelled candidates and accepting whichever verified.
     * Rejected because it reproduces the defect it appears to fix -- a token signed by either key verifies
     * whatever subject it claims, so the subject remains a claim any holder can write.</p>
     *
     * <p>Assumptions: five validators are composed with the framework's default set rather than replacing it.
     * The default set is what enforces the expiry, and an internal token's short life is only a control if
     * something checks it -- so the additions are issuer, audience, subject, key-identifier agreement and
     * scope permission, and the expiry keeps being enforced by the machinery that already did.</p>
     *
     * <p>Assumptions: the bean is qualified rather than declared as the only {@code JwtDecoder}. This context
     * already has one, for the identity provider's tokens, and two unqualified beans of one type would make the
     * choice of which chain got which decoder depend on bean-definition order.</p>
     *
     * @param authorizationKey the authorization context's own signing key from the deployment's secret store,
     *     base64 or raw text; must not be {@code null} and must not be blank
     * @param transactionKey the transaction context's own signing key from the deployment's secret store,
     *     base64 or raw text; must not be {@code null} and must not be blank
     * @return the decoder, never {@code null}
     * @throws IllegalStateException if either configured key is blank or too short, so the failure names the
     *     property, or if the key set cannot be assembled
     */
    @Bean
    @InternalTokenDecoder
    public JwtDecoder internalTokenDecoder(
            @Value("${carddemo.internal-identity.authorization-signing-key}") String authorizationKey,
            @Value("${carddemo.internal-identity.transaction-signing-key}") String transactionKey) {
        // WHY : Assumptions: both keys are required and neither has a fallback. A context that started with
        //   one of them would verify one caller and refuse the other with a 401 that names no property, and
        //   the two callers fail differently -- the authorization consumer redelivers until its queue
        //   dead-letters, while a transaction add answers the end user. Failing at start names the property.
        JWKSet keys = new JWKSet(List.of(
                verificationKey("carddemo.internal-identity.authorization-signing-key",
                        InternalServiceToken.SUBJECT_AUTHORIZATION_SERVICE, authorizationKey),
                verificationKey("carddemo.internal-identity.transaction-signing-key",
                        InternalServiceToken.SUBJECT_TRANSACTION_SERVICE, transactionKey)));

        // WHY : Assumptions: the processor's own claims verifier is replaced with one that checks nothing,
        //   because every claim decision in this class is expressed as a Spring validator below. Leaving
        //   both in place would put claim policy in two mechanisms, and the one inside the processor
        //   reports its refusals as a decode failure rather than as an invalid-token error, so the same
        //   rejection would surface in two shapes depending on which layer noticed.
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                InternalServiceToken.SIGNING_ALGORITHM, new ImmutableJWKSet<>(keys)));
        processor.setJWTClaimsSetVerifier((claims, context) -> { });

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        OAuth2TokenValidator<Jwt> issuerAudienceAndSubject = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                // WHY : (1) Assumptions: the issuer is validated as a STRING claim. The shared issuer is a
                //   deliberately opaque name carrying no colon, so the framework's default claim converter
                //   leaves it a String rather than converting it to a URL, and Jwt.getIssuer() -- which is
                //   URL-typed -- would raise on it. (2) Alternatives Considered: naming an HTTPS issuer so the
                //   typed accessor worked was rejected because it would invent a location that resolves to
                //   nothing and invite a reader to expect discovery metadata at it; there is no provider here,
                //   only services sharing a key apiece.
                new JwtClaimValidator<String>(JwtClaimNames.ISS,
                        InternalServiceToken.ISSUER::equals),
                // WHY : Assumptions: the audience claim is a LIST in the token, so the predicate tests
                //   membership rather than equality. A token may legitimately carry more than one audience,
                //   and an equality test against a single-element list would refuse a token that named this
                //   service correctly alongside another.
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audiences -> audiences != null
                                && audiences.contains(InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT)),
                // WHY : Refactoring Rationale: the SUBJECT is validated, where previously it was carried and
                //   never read. Issuer, audience and signature together establish that a token was minted by
                //   a holder of the shared key for this service, and no more -- so the subject was the only
                //   claim naming WHICH caller minted it, and nothing checked it. Admitting a closed set makes
                //   an unnamed workload's token refused at the decoder rather than authorised as whichever
                //   caller its scope resembled, and it is what lets a future per-caller rule be written
                //   against a value the verifier has already established.
                // WHY : Trade-offs: the admitted set is CLOSED, so standing up a ninth service that reads
                //   this context requires adding its name to InternalServiceToken.ADMITTED_SUBJECTS -- a
                //   deliberate edit in a shared module rather than a configuration value, because an
                //   open-ended or pattern-matched set would readmit exactly the property being removed.
                // WHY : Refactoring Rationale: the per-caller signing key WAS adopted, so the sentence that
                //   stood here -- recording it as the stronger option not taken because "both callers
                //   already hold the same key by deployment" -- no longer describes this class. The key set
                //   assembled above holds one key per admitted subject, labelled with that subject, so the
                //   verifier selects the key belonging to the caller the token NAMES and the signature check
                //   then proves the name. Caller separation is cryptographic here and not merely asserted.
                new JwtClaimValidator<String>(JwtClaimNames.SUB,
                        subject -> subject != null
                                && InternalServiceToken.ADMITTED_SUBJECTS.contains(subject)),
                // WHY : Assumptions: these two validators complete the check and neither is optional. The
                //   claim tests above establish that the token names an admitted caller; they do not
                //   establish that the caller NAMED is the caller whose key signed it, nor that the scope
                //   carried is one that caller may hold. Without the first, a holder of either key mints
                //   under the other's name; without the second, a caller reaches an operation family its
                //   own row does not grant. Both were declared and left unwired, which is invisible until
                //   a token that should be refused is answered.
                subjectMatchesSigningKey(),
                scopePermittedForSubject());
        decoder.setJwtValidator(issuerAudienceAndSubject);
        return decoder;
    }

    /**
     * Builds one verification key, labelled with the subject whose tokens it verifies.
     *
     * @param property the configuration property the value arrived from, named in any refusal so an operator
     *     is told which of the two is wrong; must not be {@code null}
     * @param subject the subject this key belongs to, carried as the key identifier; must not be {@code null}
     * @param configured the configured key value, base64 or raw text; must not be {@code null}
     * @return the labelled key, never {@code null}
     * @throws IllegalStateException if the value is blank or decodes to fewer than the required bytes
     */
    private static OctetSequenceKey verificationKey(String property, String subject,
            String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(property + " must be supplied: without it this service cannot"
                    + " verify an internal caller, and the account-context reads " + subject
                    + " depends on would be refused");
        }
        // WHY : Assumptions: the length is checked HERE rather than left to the key builder, and this was
        //   established by running it: the builder accepts a key shorter than the digest width without
        //   complaint, so a deployment given a short value would start, verify nothing reliably, and
        //   present a weakened credential boundary with no signal at all. The minting side refuses the same
        //   value at construction, so checking it on both ends keeps a misconfiguration a startup failure
        //   on whichever side is deployed first rather than a run-time authorization puzzle.
        byte[] material = decode(configured);
        if (material.length < InternalServiceToken.MIN_KEY_LENGTH) {
            throw new IllegalStateException(property + " must supply at least "
                    + InternalServiceToken.MIN_KEY_LENGTH
                    + " bytes to key the digest; a shorter key weakens the only credential standing"
                    + " between the internal account-context reads and an unauthenticated caller");
        }
        return new OctetSequenceKey.Builder(material)
                .keyID(subject)
                .algorithm(InternalServiceToken.SIGNING_ALGORITHM)
                .build();
    }

    /**
     * Builds the validator requiring the key identifier and the subject to name the same caller.
     *
     * <p>Assumptions: the equality is asserted rather than assumed. Key selection reads the header and every
     * authorization decision reads the payload, so without this check a token could be signed by one caller's
     * key while being attributed to another in the log and in the scope table. The two parts are separate parts
     * of one token and only their agreement makes the signature evidence about the subject.</p>
     *
     * @return the validator, never {@code null}
     */
    private static OAuth2TokenValidator<Jwt> subjectMatchesSigningKey() {
        return token -> {
            Object keyId = token.getHeaders().get(InternalServiceToken.KEY_ID_HEADER);
            if (keyId != null && keyId.equals(token.getSubject())) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_TOKEN,
                    "the internal token's key identifier and subject name different callers",
                    null));
        };
    }

    /**
     * Builds the validator requiring the presented scope to be one the presented subject may carry.
     *
     * <p>Assumptions: the table is the shared minter's, so this context refuses exactly what that minter
     * refuses to issue. The check is not redundant with the per-family authority rules above: those decide
     * whether a scope reaches an address, while this decides whether a CALLER may hold that scope at all, so a
     * caller that obtained a scope it is not entitled to is refused before any address is considered.</p>
     *
     * @return the validator, never {@code null}
     */
    private static OAuth2TokenValidator<Jwt> scopePermittedForSubject() {
        return token -> {
            String scope = token.getClaimAsString(InternalServiceToken.SCOPE_CLAIM);
            if (InternalServiceToken.permits(token.getSubject(), scope)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    OAuth2ErrorCodes.INSUFFICIENT_SCOPE,
                    "the internal token carries a scope its caller is not permitted to hold",
                    null));
        };
    }

    /**
     * Decodes the configured key, accepting either a base64 rendering or raw text.
     *
     * <p>Assumptions: the same rule as on the minting side, and deliberately the same code shape: the two ends
     * must derive identical bytes from one configured value, so a difference in how each decodes it would
     * produce a signature the other could not verify -- a failure that appears only at run time, only on this
     * path, and only as a 401.</p>
     *
     * @param key the configured value; must not be {@code null} and must not be blank
     * @return the key material, never {@code null}
     */
    private static byte[] decode(String key) {
        String trimmed = key.trim();
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            if (decoded.length >= InternalServiceToken.MIN_KEY_LENGTH) {
                return decoded;
            }
        } catch (IllegalArgumentException notBase64) {
            // WHY : Assumptions: an unparseable value is a raw key. The exception is swallowed rather than
            //   logged because it carries the offending input on some implementations and that input is the
            //   signing secret. A value too short to key the digest is refused by the explicit length check
            //   in the caller above -- NOT by the decoder builder, which accepts one silently.
            return trimmed.getBytes(StandardCharsets.UTF_8);
        }
        return trimmed.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds the converter that turns a verified internal token into an authentication.
     *
     * <p>Assumptions: the default scope-to-authority conversion is used with its default prefix rather than
     * configured away. The authority the rule above requires is composed from that same prefix in one
     * constant, so the two agree by construction; changing the prefix here would require changing the constant
     * and nothing would report a mismatch except a 403 in production.</p>
     *
     * <p>Trade-offs: package-visible rather than private, for the same reason as the matcher above. A test in
     * this package feeds it a verified token and asserts the authority set it produces, which is what decides
     * whether the scope claim reaches the rule; asserting that through the chain would observe only the
     * resulting status.</p>
     *
     * @return the converter, never {@code null}
     */
    static JwtAuthenticationConverter internalAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(InternalServiceToken.SCOPE_CLAIM);
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * Reports the authority a decision-path read requires, for a test to assert against.
     *
     * <p>Assumptions: this exists so a test can pin the composed authorities without reconstructing the
     * prefix, which is the part most likely to be got wrong in two places.</p>
     *
     * <p>Assumptions: the name is kept as it stands rather than renamed to match the group it now names, and
     * the reason is that a rename would be a source-compatible change with no behavioural content across the
     * callers that already pin it, while the pairing with
     * {@link #requiredCustomerRecordsAuthority()} makes which group each returns unambiguous at every call
     * site.</p>
     *
     * <p>Refactoring Rationale: the list carries FOUR authorities where it carried three, because the
     * cross-reference family was split: the address that answers with an unmasked primary account number
     * demands {@link #CARD_XREF_RESOLVE_AUTHORITY} and the two that answer with none demand
     * {@link #CARD_XREF_READ_AUTHORITY}. It is returned here beside the three it joins because every
     * caller of this accessor asks the same question -- which authorities does the decision surface of this
     * chain demand -- and an authority the chain enforces but this list omits is one no test would notice
     * had stopped being enforced.</p>
     *
     * @return the authorities the decision-path reads require, in the order the chain's rules state them,
     *     never {@code null}
     */
    public static List<SimpleGrantedAuthority> requiredAuthorities() {
        return List.of(new SimpleGrantedAuthority(CARD_XREF_RESOLVE_AUTHORITY),
                new SimpleGrantedAuthority(CARD_XREF_READ_AUTHORITY),
                new SimpleGrantedAuthority(ACCOUNT_READ_AUTHORITY),
                new SimpleGrantedAuthority(CUSTOMER_READ_AUTHORITY));
    }

    /**
     * Reports the authority a customer-record read requires, for a test to assert against.
     *
     * <p>Assumptions: published beside the decision-path accessor rather than left to a test to compose,
     * because the composed value is the part a second spelling would get wrong -- and a test asserting the
     * wrong spelling of a REFUSAL still passes, which is the failure mode this removes.</p>
     *
     * @return the authority the two customer-record reads require, never {@code null}
     */
    public static SimpleGrantedAuthority requiredCustomerRecordsAuthority() {
        return new SimpleGrantedAuthority(INTERNAL_CUSTOMER_MASTER_AUTHORITY);
    }

    /**
     * Marks the decoder that verifies internal tokens, as distinct from the identity provider's.
     *
     * <p>Assumptions: a qualifier annotation rather than a bean-name string at each injection point. This
     * context holds two decoders of the same type, so injection by type alone would bind whichever the context
     * happened to hold; a qualifier makes the choice explicit and makes a rename a compile error instead of a
     * run-time authorization failure.</p>
     */
    @java.lang.annotation.Documented
    @org.springframework.beans.factory.annotation.Qualifier("internalTokenDecoder")
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target({java.lang.annotation.ElementType.FIELD,
            java.lang.annotation.ElementType.METHOD,
            java.lang.annotation.ElementType.PARAMETER,
            java.lang.annotation.ElementType.TYPE})
    public @interface InternalTokenDecoder {
    }

}
