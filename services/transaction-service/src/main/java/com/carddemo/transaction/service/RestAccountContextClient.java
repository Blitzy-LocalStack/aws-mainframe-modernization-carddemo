package com.carddemo.transaction.service;

import com.carddemo.common.security.ApprovedOriginPolicy;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.security.InternalServiceToken;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads the account-owned card cross-reference over the account context's published HTTP surface.
 *
 * <p>Refactoring Rationale: this client also read the account master and posted the payment screen's
 * balance change, and both have been withdrawn. The seam's own charter records why in full: the balance
 * change and the payment row are one unit of work, an HTTP call cannot join this side's transaction, and
 * the address the change was posted to was never declared by the callee. Both concerns are now issued by
 * {@code com.carddemo.transaction.repository.AccountBalanceRepository} inside the payment's own transaction.
 * What remains here are the two cross-reference reads, which write nothing and which no commit depends
 * on.</p>
 *
 * <p>Purpose: this is the only implementation of {@link AccountContextClient} that runs in a deployed
 * environment. It exists as its own class rather than as a lambda in a configuration method because it
 * has to distinguish three outcomes per call -- a record, a documented absence and a failure -- and that
 * distinction is several lines of exception handling per operation.</p>
 *
 * <p>Assumptions: a 404 means the record does not exist and becomes an empty optional, and every other
 * client or server status, every transport failure and every unusable body becomes
 * {@link AccountContextClient.AccountContextUnavailableException}. The reference draws the same line: its
 * file status 23 selects the not-found sentence and any other status selects the failed-read sentence,
 * for instance at lines 361 and 366 of {@code app/cbl/COBIL00C.cbl}.</p>
 *
 * <p>Assumptions: both timeouts are explicit and both are configurable, and the defaults are the ones the
 * authorization context already runs with. An unbounded read on this seam would let one slow account-side
 * read hold a request thread for as long as the operating system's own timeout allows, which on a screen
 * the operator is waiting at is indistinguishable from a hang.</p>
 *
 * <p>Refactoring Rationale: the configured origin is VALIDATED before the client is built, through the
 * shared kernel's {@link com.carddemo.common.security.ApprovedOriginPolicy}. An earlier revision accepted
 * the value verbatim and then attached a freshly minted machine token to every request sent to it, so a
 * base address changed after review -- to plain HTTP, to an unapproved host, to an address carrying user
 * information -- would have received a live internal credential and, on the card-keyed lookup, a primary
 * account number. The check is shared rather than written here because two sibling clients already carried
 * a private copy of it, and a check duplicated three times is a check that gets strengthened in one
 * copy.</p>
 *
 * <p>Alternatives Considered: composing the lookup path with the card number in it, which reads more
 * directly than a request body does. Rejected because a card number in a path is a card number in an
 * access log, and access-log storage is the one destination the masking applied at the API edge does not
 * reach; the account context publishes the lookup as a POST for the same reason.</p>
 */
@Component
public class RestAccountContextClient implements AccountContextClient {

    /**
     * The configuration prefix this seam's two address properties sit under.
     *
     * <p>Assumptions: the prefix is named once and both the base address and the approved origin are read
     * from it, so the two cannot come to sit under different prefixes. Every refusal the shared policy
     * raises names its property from this value.</p>
     */
    public static final String ACCOUNT_CONTEXT_PROPERTY_PREFIX = "carddemo.account-context";

    /** The account context's cross-reference lookup, which takes its key in a request body. */
    public static final String PATH_CARD_XREF_LOOKUP = "/api/v1/card-xrefs/lookup";

    /**
     * The account context's account-keyed cross-reference read, which takes its key in a request body.
     *
     * <p>Refactoring Rationale: this was the path template
     * {@code /api/v1/card-xrefs/by-account/{accountId}} and the read was a {@code GET}, so the account
     * identifier travelled in the request line. The load balancer between this client and the account
     * context composes its access record from that request line before any application code runs, and the
     * migration's sensitive-data logging contract names account identifiers among the values a durable
     * diagnostic may not hold -- so the value had to leave the target rather than be masked after it.</p>
     */
    public static final String PATH_CARD_XREF_BY_ACCOUNT = "/api/v1/card-xrefs/lookup-by-account";

    /**
     * The address prefix of the cross-reference family, which the cross-reference read scope authorises
     * EXCEPT at {@link #PATH_CARD_XREF_BY_ACCOUNT}, whose disclosure carries its own scope.
     *
     * <p>Assumptions: the prefix is DERIVED from the path constant above rather than written again, by
     * removing the final segment. Writing it as a literal would let the two drift, and the symptom of a
     * drift is a refusal on one operation while every other one keeps working.</p>
     *
     * <p>Refactoring Rationale: the prefix no longer decides the scope for every address beneath it. The
     * account-keyed read answers with an unmasked primary account number where its two siblings answer with
     * none, so the callee moved it onto {@code SCOPE_CARD_XREF_RESOLVE_CARD_NUMBER} and
     * {@link #scopeFor(String)} tests that exact address before consulting this prefix. The prefix is kept
     * for the siblings rather than replaced by two equality tests, because it is what keeps a THIRD
     * cross-reference address from silently minting nothing -- an unrecognised address raises there.</p>
     *
     * <p>Refactoring Rationale: there is ONE prefix here where there were two. The account family's prefix
     * and the two addresses under it -- an account master read and a balance-reducing payment -- have been
     * withdrawn. The payment address was never published by the account context at all: it had no
     * controller, no operation in the committed contract, no internal route and no write scope, so every
     * bill payment that reached it was answered 404 and reported as a dependency failure. The read was
     * withdrawn with it because the balance is now read locally, under a named grant, so that it can be
     * locked across the decision and reduced in the same transaction as the ledger row.</p>
     */
    private static final String CARD_XREF_PATH_PREFIX = parentOf(PATH_CARD_XREF_LOOKUP);

    /**
     * Removes the final segment of a path, yielding the family prefix its siblings share.
     *
     * @param path one of the path constants above; must not be {@code null} and must contain a separator
     * @return the path up to but excluding its final separator, never {@code null}
     */
    private static String parentOf(String path) {
        return path.substring(0, path.lastIndexOf('/'));
    }

    /** The request member the lookup keys on, spelled as the account context publishes it. */
    private static final String FIELD_CARD_NUMBER = "cardNumber";

    /**
     * The request member the account-keyed cross-reference read carries the account identifier in.
     *
     * <p>Assumptions: the spelling matches the {@code accountId} property of the account context's
     * published request schema for that address, so the two sides name the field with one literal rather
     * than with two strings that happen to agree.</p>
     */
    private static final String FIELD_ACCOUNT_ID = "accountId";

    /** The configured client, built once at construction with both timeouts already applied. */
    private final RestClient client;

    /**
     * Builds the client against the configured account-context origin.
     *
     * <p>Assumptions: the base URL is required rather than defaulted. A default would let a deployment
     * start with the seam pointed at nothing and fail at the first screen that used it, which reports the
     * misconfiguration to an operator instead of to whoever deployed it.</p>
     *
     * <p>Refactoring Rationale: every request carries a machine bearer token. The account context does
     * not admit an anonymous caller on these addresses -- its {@code InternalApiSecurityConfig} runs an
     * earlier-ordered chain requiring the authority of the operation family each address belongs to,
     * one scope per family, and its user chain
     * refuses the cross-reference and customer subtrees outright -- so a client presenting nothing would
     * be answered 401 on every call. This seam carries no signed-on user to forward either: a bill
     * payment and a transaction add are authorized as the operator who submitted them, but the account
     * records they read belong to another context and the cardholder's session grants no authority over
     * them.</p>
     *
     * @param builder the framework's client builder, never {@code null}
     * @param machineIdentity the minter of the short-lived credential presented on every request; must
     *     not be {@code null}
     * @param baseUrl the origin the account context is served at, never {@code null}
     * @param approvedOrigin the origin the base address is required to equal, defaulting to the base
     *     address itself so a deployment that configures only one value still gets every shape check
     * @param connectTimeoutMillis how long to wait for the connection, in milliseconds
     * @param readTimeoutMillis how long to wait for the response, in milliseconds
     * @throws IllegalStateException if the configured address is absent, is not an absolute HTTPS origin
     *     free of user information, path, query and fragment, or is not the approved origin
     */
    // WHY : ⚠️ Assumptions: the annotation is REQUIRED as soon as this class declares a second
    //       constructor, and its absence would stop this context from starting at all. Spring's implicit
    //       constructor injection applies only to a class with exactly ONE constructor; with two
    //       candidates and neither marked, the container stops looking for an injectable constructor and
    //       falls back to a no-argument one, which this class does not declare -- so bean creation fails
    //       with "No default constructor found" and the whole transaction context fails to refresh rather
    //       than degrading. The identical defect and the identical remedy are recorded on
    //       authorization-service's RestAccountContextClient and on account-service's
    //       RestReferenceAddressLookup; this is the same fix applied to the third occurrence rather than a
    //       new judgement. common-lib's ApplicationContextWiringContractTest asserts the rule across every
    //       module, and it is what reported this one.
    // WHY : Alternatives Considered: not adding the test seam, so one constructor would remain implicit.
    //       Rejected because the absence of any test of this class is exactly how its identifier rendering
    //       came to drop a leading zero for every shipped account, and the public constructor installs its
    //       own request factory -- which replaces whatever transport a test had bound, sending the test to
    //       the network. Marking the injection point keeps the seam and the timeouts both.
    @Autowired
    public RestAccountContextClient(RestClient.Builder builder,
            InternalServiceToken machineIdentity,
            @Value("${carddemo.account-context.base-url}") String baseUrl,
            @Value("${carddemo.account-context.approved-origin:${carddemo.account-context.base-url:}}")
            String approvedOrigin,
            @Value("${carddemo.account-context.connect-timeout-ms:2000}") long connectTimeoutMillis,
            @Value("${carddemo.account-context.read-timeout-ms:3000}") long readTimeoutMillis) {

        // WHY : Assumptions: the address is validated BEFORE the builder is touched, so a misconfigured
        //       deployment fails to start rather than starting and sending a token somewhere. The clauses
        //       name what is at risk on THIS seam: the card-keyed lookup carries a primary account number
        //       in its request body, and every request carries a minted internal credential.
        requireApprovedOrigin(baseUrl, approvedOrigin);

        // WHY : Assumptions: the request factory is built over the platform HTTP client so that the
        //       connect timeout is applied by the client and the read timeout by the factory. Setting
        //       only one of the two leaves the other unbounded, and it is the read that hangs.
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));

        this.client = builder.baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor(bearerTokenInterceptor(machineIdentity))
                .build();
    }

    /**
     * Builds the client over a builder whose transport the caller has already configured.
     *
     * <p>⚠️ Purpose: this seam exists so the status-code, body-shape and identifier-rendering behaviour of
     * this class can be exercised at all. The public constructor installs its own request factory in order
     * to apply the two timeouts, and installing one REPLACES a mock transport bound to the builder -- so a
     * test that constructed the public form would issue real network calls. Until this seam existed there
     * was no test of this class anywhere, which is precisely how the identifier rendering corrected in
     * {@link #renderAccountId(long)} came to publish a one-character account number for every one of the
     * fifty shipped accounts while the seam's own interface documented eleven.</p>
     *
     * <p>Assumptions: the base-address validation is the SAME call the public constructor makes, so this
     * seam cannot be used to point the client at an unapproved origin. What it omits is the request factory
     * and therefore the two timeouts, which are a property of the transport rather than of this class's
     * contract; asserting on them would require a request that actually stalled.</p>
     *
     * <p>Assumptions: the credential interceptor IS installed here, unlike the request factory, because
     * only the factory conflicts with a bound mock transport. Leaving the interceptor out would make every
     * test exercise a client presenting no credential -- the one state the account context refuses on every
     * one of these addresses.</p>
     *
     * <p>Alternatives Considered: extracting the factory construction into a configuration class and
     * injecting a {@code ClientHttpRequestFactory}. That is the better long-term shape and is deliberately
     * not done here: it would move two configuration properties and a bean into another file for a reason
     * unrelated to the finding this class is being corrected for, and the sibling seam in
     * {@code authorization-service} already resolved the identical trade-off the same way.</p>
     *
     * @param builder the builder, with its request factory already configured by the caller; must not be
     *     {@code null}
     * @param machineIdentity the minter of the credential presented on every request; must not be
     *     {@code null}
     * @param baseUrl the account context's base address; must not be {@code null}
     * @param approvedOrigin the exact origin the base address is required to equal; must not be
     *     {@code null}
     * @throws IllegalStateException if the base address fails any check the public constructor applies
     * @throws NullPointerException if {@code builder} or {@code machineIdentity} is {@code null}
     */
    RestAccountContextClient(RestClient.Builder builder, InternalServiceToken machineIdentity,
            String baseUrl, String approvedOrigin) {

        Objects.requireNonNull(builder, "builder must not be null");
        Objects.requireNonNull(machineIdentity, "machineIdentity must not be null");
        requireApprovedOrigin(baseUrl, approvedOrigin);
        this.client = builder.baseUrl(baseUrl)
                .requestInterceptor(bearerTokenInterceptor(machineIdentity))
                .build();
    }

    /**
     * Applies this seam's base-address policy, so both constructors enforce one rule.
     *
     * <p>Assumptions: the sensitivity clauses are declared once here rather than at each constructor,
     * because they describe what is at risk on this SEAM and not what is different about a caller. The
     * clauses name the two things that actually travel: the card-keyed lookup carries a primary account
     * number in its request body, and every request carries a minted internal credential.</p>
     *
     * @param baseUrl the configured base address; may be {@code null} or blank, which the policy refuses
     * @param approvedOrigin the origin the base address is required to equal; may be {@code null}
     * @throws IllegalStateException if the address is absent, is not an absolute HTTPS origin free of user
     *     information, path, query and fragment, or is not the approved origin
     */
    private static void requireApprovedOrigin(String baseUrl, String approvedOrigin) {
        ApprovedOriginPolicy.require(ACCOUNT_CONTEXT_PROPERTY_PREFIX, baseUrl, approvedOrigin,
                new ApprovedOriginPolicy.Sensitivity(
                        "the cross-reference lookup this client makes carries a primary account number and"
                                + " every request carries a minted internal credential, so there is no safe"
                                + " default address to fall back to",
                        "the lookup body carries a primary account number and every request carries a"
                                + " minted internal credential",
                        "an unapproved destination receives a live internal credential, and a card-keyed"
                                + " lookup hands it a primary account number in the same request"));
    }

    /**
     * Builds the interceptor that presents a freshly minted machine token on every request.
     *
     * <p>Assumptions: a FRESH token is minted per request rather than one being minted here and reused
     * for the life of the client. Reuse would hand out a token near its expiry and have it rejected on
     * arrival, which presents as an intermittent authorization failure that looks like a permissions
     * problem; minting per request removes that class of failure and costs one signature per call.</p>
     *
     * <p>Assumptions: the audience and the scope are the shared constants, so the value this side mints
     * and the value the account context requires are the same literal rather than two strings that
     * happen to match.</p>
     *
     * @param machineIdentity the minter; must not be {@code null}
     * @return the interceptor, never {@code null}
     */
    private static ClientHttpRequestInterceptor bearerTokenInterceptor(
            InternalServiceToken machineIdentity) {
        return (request, body, execution) -> {
            request.getHeaders().setBearerAuth(machineIdentity.mint(
                    InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                    scopeFor(request.getURI().getPath())));
            return execution.execute(request, body);
        };
    }

    /**
     * Selects the scope for one request, from the address it is bound for.
     *
     * <p>Refactoring Rationale: the interceptor used to mint ONE scope for every call, which was the whole
     * internal surface of the callee. Deriving the scope from the address means a request carries only what
     * its own operation needs, so a captured token authorises that operation and no other. The derivation is
     * here rather than at each call site because the credential is applied by one interceptor: passing the
     * scope down to it would mean threading a value through every method for the interceptor to read back.</p>
     *
     * <p>Assumptions: an address this method does not recognise raises rather than falling back to any scope.
     * A fallback would be one of two wrong things -- the broadest scope, which reintroduces the excess reach
     * this split removed, or the narrowest, which fails at the callee as a 403 that names no cause. Raising
     * names the path, at the moment a new address is added without a scope decision being made for it.</p>
     *
     * @param path the request path, as the transport resolved it; must not be {@code null}
     * @return the scope that address requires, never {@code null}
     * @throws IllegalStateException if the path is not one this client addresses
     */
    private static String scopeFor(String path) {
        // WHY : Purpose: the account-keyed read is matched on its EXACT address, ahead of the family
        //   prefix, because it is the one address in the family that answers with an unmasked primary
        //   account number -- this client writes that value into its ledger row as the row's key. The
        //   callee authorises it with a scope of its own, granted to this context alone, so a credential
        //   minted for the card-keyed lookup can no longer provoke that disclosure.
        // WHY : Assumptions: the exact-address test is stated FIRST and the prefix test second, which is a
        //   correctness requirement rather than a style: the prefix accepts this address too, so with the
        //   order reversed every request would carry the wider scope and the callee would refuse this one
        //   -- the split would be inert in one direction and breaking in the other.
        // WHY : Alternatives Considered: deriving a second prefix so both branches read alike. Rejected
        //   because there is no prefix that separates one sibling address from another beneath a shared
        //   parent, so a derived one would either match both or match nothing; equality is the only
        //   expression of "this address and not its siblings", and it names the constant rather than a
        //   fragment of it.
        if (PATH_CARD_XREF_BY_ACCOUNT.equals(path)) {
            return InternalServiceToken.SCOPE_CARD_XREF_RESOLVE_CARD_NUMBER;
        }
        if (path.startsWith(CARD_XREF_PATH_PREFIX)) {
            return InternalServiceToken.SCOPE_CARD_XREF_READ;
        }
        // WHY : Refactoring Rationale: there is ONE branch here where there were two, and the account
        //   branch was the defect this method's own note warned about in the abstract. It mapped a prefix to
        //   the account READ scope, and one of the addresses under that prefix was a balance-reducing
        //   WRITE -- so a write was authorised by a read scope, and the token minted for it would have
        //   authorised every account read in the callee. Both addresses are withdrawn: the balance is read
        //   and reduced locally now, under a grant on one named table.
        // WHY : Assumptions: there is no customer branch here either, and its absence is the point rather
        //   than an omission. This context reads no customer record, so it is not permitted to carry the
        //   customer scope at all -- the closed table in InternalServiceToken withholds it -- and a branch
        //   that returned it would raise from the minter instead of from here, naming the scope rather than
        //   the address. Raising here names the address, which is what a reader adding one needs to see.
        throw new IllegalStateException("no internal scope is declared for '" + path
                + "'; every address this client calls must be assigned one, because the account context"
                + " authorises each family of addresses by its own scope");
    }

    /**
     * {@inheritDoc}
     *
     * @param cardNumber {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public Optional<CardXref> findCardXrefByCardNumber(String cardNumber) {
        try {
            CardXrefView view = this.client.post()
                    .uri(PATH_CARD_XREF_LOOKUP)
                    .body(Map.of(FIELD_CARD_NUMBER, cardNumber))
                    .retrieve()
                    .body(CardXrefView.class);
            // WHY : Refactoring Rationale: the card number comes from the REQUEST and not from the answer,
            //       because the answer does not carry one -- the account context's card-keyed lookup
            //       publishes the account and customer identifiers only, deliberately, so a primary account
            //       number is not echoed back over a seam that already knows it. An earlier revision read a
            //       cardNumber member off this response; the member does not exist in the published schema,
            //       so it arrived null on every successful call and the value written into the ledger row
            //       would have been absent.
            return Optional.ofNullable(view).map(read -> read.toCardXref(cardNumber));
        } catch (HttpClientErrorException.NotFound absent) {
            return Optional.empty();
        } catch (RestClientException failure) {
            throw new AccountContextUnavailableException(
                    "card cross-reference lookup by card number did not answer", failure);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param accountId {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public Optional<CardXref> findCardXrefByAccountId(String accountId) {
        try {
            CardXrefByAccountView view = this.client.post()
                    .uri(PATH_CARD_XREF_BY_ACCOUNT)
                    .body(Map.of(FIELD_ACCOUNT_ID, accountId))
                    .retrieve()
                    .body(CardXrefByAccountView.class);
            return Optional.ofNullable(view).map(CardXrefByAccountView::toCardXref);
        } catch (HttpClientErrorException.NotFound absent) {
            return Optional.empty();
        } catch (RestClientException failure) {
            throw new AccountContextUnavailableException(
                    "card cross-reference lookup by account identifier did not answer", failure);
        }
    }

    /**
     * Renders a numeric account identifier as the eleven digit characters this side's contract declares.
     *
     * <p>Purpose: the account context publishes {@code accountId} on both cross-reference reads as a JSON
     * NUMBER, because its column is {@code BIGINT}; every shape on this side declares it as eleven digit
     * CHARACTERS, because {@code XREF-ACCT-ID} is {@code PIC 9(11)} at line 7 of
     * {@code app/cpy/CVACT03Y.cpy} and an unsigned display numeric is right-justified and ZERO-filled. This
     * method is the one place the two representations meet.</p>
     *
     * <p>⚠️ Refactoring Rationale: both conversions used {@code String.valueOf}, and the comment beside one
     * of them claimed the rendering was done "so a leading zero survives" -- which is the opposite of what
     * {@code String.valueOf} does. It renders {@code 1L} as {@code "1"}. That is not a theoretical loss on
     * this data: EVERY one of the fifty shipped accounts is numbered {@code 00000000001} through
     * {@code 00000000050} in {@code app/data/ASCII/acctdata.txt}, so the seam carried a one-character
     * identifier for all fifty of them where its own interface -- {@link AccountContextClient.CardXref},
     * whose Javadoc requires digit characters "so a leading zero survives" -- declares eleven. The
     * identifier then travelled onward into a transaction-add preview and into the ledger key path at a
     * width no other shape in this system uses. Zero-filling here is what makes the stated contract true.</p>
     *
     * <p>Assumptions: the width is taken from {@link TransactionAddService#ACCOUNT_ID_WIDTH} rather than
     * declared again here, so this rendering and the inbound validation that admits an eleven-character
     * submission cannot disagree about how wide the field is. The sibling consumer in
     * {@code authorization-service} renders the same value the same way for the same reason, which is why
     * the two contexts' onward calls agree.</p>
     *
     * <p>Assumptions: {@link java.util.Locale#ROOT} is passed explicitly, because a format specifier for a
     * decimal integer is locale-sensitive -- a default locale with non-Latin digits or a grouping separator
     * would render an identifier this system cannot parse back.</p>
     *
     * @param accountId the identifier as the account context published it, a {@code long}
     * @return the identifier as exactly {@link TransactionAddService#ACCOUNT_ID_WIDTH} digit characters,
     *     never {@code null}
     */
    private static String renderAccountId(long accountId) {
        return String.format(Locale.ROOT, "%0" + TransactionAddService.ACCOUNT_ID_WIDTH + "d", accountId);
    }

    /**
     * The account context's card-keyed cross-reference answer, declared in full.
     *
     * <p>Refactoring Rationale: EVERY published member is declared, including {@code customerId}, which
     * this context does not use. An earlier revision declared a two-member subset and recorded that "the
     * deserialiser is configured to ignore the rest" -- which was false and is the reason this is
     * corrected rather than trimmed: {@code application.yml} sets
     * {@code spring.jackson.deserialization.fail-on-unknown-properties} to true, deliberately, so an
     * undeclared member does not get ignored, it fails the conversion. Every successful lookup was
     * therefore turned into a dependency failure and answered 500. Declaring the closed shape is what
     * makes the strict setting safe, and the setting is what makes a shape change visible.</p>
     *
     * <p>Assumptions: this shape carries NO card number, and its absence is the account context's
     * deliberate choice rather than an omission -- the caller keyed the read on a card number, so echoing
     * it back would put a primary account number in a response for no reader. The seam record is completed
     * from the request instead.</p>
     *
     * @param accountId the account identifier the entry names
     * @param customerId the customer identifier the entry names, declared so the strict deserialiser
     *     admits it and unused by this context
     */
    private record CardXrefView(Long accountId, Long customerId) {

        /**
         * Converts the wire shape into the seam's own record, completing it with the card that was asked
         * about.
         *
         * @param requestedCardNumber the card number this lookup was keyed on, which is the value the
         *     entry is keyed by; must not be {@code null}
         * @return the seam record, never {@code null}
         */
        private CardXref toCardXref(String requestedCardNumber) {
            // WHY : Assumptions: the identifier is rendered back to digit characters rather than carried as
            //       a number, because the seam record declares it as characters so a leading zero survives.
            //       The account context publishes it as a JSON number because its column is BIGINT; the
            //       screen contract on this side is an eleven-character field.
            return new CardXref(renderAccountId(this.accountId), requestedCardNumber);
        }
    
        /**
         * Renders neither identifier, matching the sibling wire shape in this same class.
         *
         * <p>Purpose. Both components are identifiers {@code docs/architecture/observability.md} L1093 to
         * L1112 withholds, and they are the whole record. This is the CARD-keyed wire shape; the
         * account-keyed one beside it renders a masked card number because the card number is the value
         * that operation exists to return, and the two renderings differ for exactly that reason and no
         * other.</p>
         *
         * <p>Assumptions: a binding fault on this shape names the type before any mapping runs, so this
         * rendering is reached earlier than the seam record's and has to be safe on its own rather than
         * relying on the mapping to narrow anything.</p>
         *
         * @return a rendering naming the type with both identifiers withheld; never {@code null}
         */
        @Override
        public String toString() {
            return "CardXrefView[identifiers=[REDACTED]]";
        }
}

    /**
     * The account context's account-keyed cross-reference answer, declared in full.
     *
     * <p>Assumptions: this is a DIFFERENT shape from the card-keyed one above and is declared separately
     * rather than shared, because the account context publishes a card number on this operation and not on
     * that one. The two are separate schemas in the committed contract for the same reason: a caller that
     * keyed on an account does not know the card, so the answer has to carry it, while a caller that keyed
     * on a card already has it.</p>
     *
     * @param accountId the account identifier the read was keyed on
     * @param customerId the customer identifier the entry names, declared so the strict deserialiser admits
     *     it and unused by this context
     * @param cardNumber the card number the entry is keyed by, which this context writes into the ledger
     *     row as its key
     */
    private record CardXrefByAccountView(Long accountId, Long customerId, String cardNumber) {

        /**
         * Converts the wire shape into the seam's own record.
         *
         * @return the seam record carrying the account identifier as characters and the card number as
         *     published, never {@code null}
         */
        private CardXref toCardXref() {
            return new CardXref(renderAccountId(this.accountId), this.cardNumber);
        }

        /**
         * Renders this wire shape for a log line or a diagnostic, disclosing neither the whole card number
         * nor either identifier.
         *
         * <p>Purpose. This is the ONE inbound shape on this seam that carries an unmasked primary account
         * number, and a record's generated rendering prints every component -- so the override matters more
         * here than on any other type in this file. The reachable path is not hypothetical: the strict
         * deserialiser this client configures fails a body whose shape changed, and a conversion failure is
         * reported with the partially bound value in scope, so the generated form would put a whole card
         * number into the diagnostic for a contract mismatch.</p>
         *
         * <p>Trade-offs: the card number is rendered masked through the shared masker and both identifiers
         * are omitted, matching the rendering the account context's own response record carries for the same
         * three columns. Agreeing with it rather than choosing independently is the point: one value read
         * over one seam should not be abbreviated to two different depths at its two ends.</p>
         *
         * @return a single-line rendering naming the type and the masked card number, never {@code null}
         */
        @Override
        public String toString() {
            return "CardXrefByAccountView[cardNumber=" + CardNumberMasker.mask(this.cardNumber) + ']';
        }
    }
}
