package com.carddemo.authorization.service;

import com.carddemo.common.security.InternalServiceToken;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads the account context over its published contract, adapting it to {@link AccountContextClient}.
 *
 * <p><b>Purpose.</b> This is the outbound half of the seam the port describes: it turns the three reads
 * the baseline performs against the cross-reference, account and customer files -- at
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} paragraphs {@code 5100-READ-XREF-RECORD},
 * {@code 5200-READ-ACCT-RECORD} and {@code 5300-READ-CUST-RECORD} -- into calls on the context that owns
 * those records. Everything specific to the transport lives here and nothing else in this service names
 * a URL, a status code or a media type, so a change of transport is a change to this one file.</p>
 *
 * <p>Assumptions: three failure modes are distinguished, and keeping them apart is the whole reason this
 * class is not two lines long. A 404 means the record does not exist and becomes an empty optional,
 * because the baseline treats a not-found read as a decision input. Any other failure -- a timeout, a
 * refused connection, a 5xx, an unreadable body -- becomes
 * {@link AccountContextClient.AccountContextUnavailableException}, so the caller lets the message be
 * redelivered instead of declining an authorization the account might well deserve. A 200 with a
 * complete body becomes the record.</p>
 *
 * <p>Refactoring Rationale: a 200 whose body is ABSENT or INCOMPLETE is now a dependency failure and
 * previously was an empty optional -- that is, it was reported as "the record does not exist". The two
 * are not interchangeable and conflating them inverted the outcome. A cross-reference response missing
 * its account identifier, or an account response missing its credit limit, does not say the row is
 * absent; it says the dependency answered something this contract cannot be built from. Reporting it as
 * not-found made the consumer DECIDE the authorization -- declining it on the baseline's own card-not-
 * found or insufficient-funds reason, committing that decision to the account's history, consuming its
 * declined counter and answering the acquirer -- for a request that was never actually evaluated. Raising
 * instead rolls the transaction back and lets the queue redeliver, so a dependency that briefly returns
 * a truncated body costs a redelivery rather than a wrong permanent decision. The earlier reasoning was
 * that raising "would retry a request the dependency has already answered successfully"; the answer is
 * only successful in its status line, and a status line is not the contract.</p>
 *
 * <p>Trade-offs: a dependency that returns incomplete bodies persistently now dead-letters the message
 * after the queue's receive limit instead of declining it. That is the better failure: a dead-lettered
 * message is visible, holds no decision, and can be replayed once the dependency is fixed, whereas a
 * committed wrong decline is invisible in exactly the same way a correct decline is.</p>
 *
 * <p>Assumptions: the base address is validated at construction rather than trusted, and the checks are
 * on the SHAPE and on an exact approved origin. The first request this client makes carries a primary
 * account number in its body, so a base address pointing anywhere unintended exfiltrates cardholder data
 * on the first authorization rather than failing visibly.</p>
 *
 * <p>Trade-offs: the calls are SYNCHRONOUS and they run inside the deciding transaction, so each one
 * occupies a pooled database connection for as long as the dependency takes to answer even though it
 * touches no row itself. That is why both timeouts below are short and why neither is optional: an
 * unbounded read here would hold a connection for as long as the dependency stayed silent, and a
 * serverless cluster's connection budget would be exhausted by a dependency that never actually returned
 * an error. Alternatives Considered: reading the account context asynchronously and resuming the message
 * on its reply. Rejected because a decision cannot be made without these records, so the consumer would
 * have to suspend a transaction mid-flight and resume it elsewhere, which turns one unit of work into a
 * saga and makes partial states observable.</p>
 *
 * <p>Refactoring Rationale: the paragraph above previously stated that a ROW LOCK was held by the time
 * the second of these calls was made, and used that as the reason the timeouts are short. It was wrong
 * about the ordering and the correction is recorded rather than made silently, because the false version
 * argued for a stronger constraint than the code actually carries and a reader who trusted it would have
 * drawn the wrong conclusion about how long a slow dependency can block a row. The caller,
 * {@code AuthorizationRequestListener.handleNewRequest}, performs all THREE of these reads first and only
 * then reads the summary through the non-locking {@code findByAccountId}, so no lock is held across any
 * call made here.</p>
 *
 * <p>Assumptions: that ordering is a property worth preserving rather than an accident, and it is the
 * safer of the two. Taking the summary lock first would hold it across up to three network calls, so one
 * silent dependency would block every other authorization for the same account for the whole of the
 * timeout budget; reading first confines the lock to the local writes that follow it. Anyone reordering
 * the caller to lock earlier has to revisit this paragraph, because the argument for the timeouts becomes
 * the stronger one the false version already claimed.</p>
 *
 * <p>Refactoring Rationale: every request now carries a WORKLOAD CREDENTIAL, and an earlier revision of
 * this class carried none at all. That revision presented no bearer token, no client certificate and no
 * signed request, while the account context requires one of the two signed-on group authorities on every
 * business route -- so each of these three calls would have been refused before it reached a handler, and
 * the refusal would have surfaced as an unavailable dependency rather than as the missing credential it
 * was. The credential is a signed machine token minted by
 * {@link com.carddemo.common.security.InternalServiceToken} for the account context's audience and the
 * internal read scope, and it travels as an ordinary bearer token so the callee's resource-server decoder
 * verifies it rather than any code in this repository.</p>
 *
 * <p>Assumptions: the credential identifies THIS WORKLOAD and never a user, and that is a requirement
 * rather than a convenience. A pending-authorization request arrives on a queue carrying a card number,
 * an amount and a merchant; there is no signed-on user anywhere in it, so there is no user token to
 * forward even in principle. Forwarding one would also be wrong if one existed: it would let a
 * cardholder's session authorize a read of records the migration's own security mapping does not grant
 * that session, and the authority actually needed here belongs to the platform.</p>
 *
 * <p>Assumptions: the credential is attached by a request INITIALIZER on the builder rather than by each
 * of the three methods below. An initializer runs for every request this client issues, including one
 * added later, so a new call cannot be written that forgets it -- which is the failure the revision
 * above is an instance of.</p>
 *
 * <p>Alternatives Considered: putting the card number in the request PATH, which is the shape a reader
 * expects for a lookup and which an earlier draft of this class used. Rejected because a path is
 * recorded verbatim in the load balancer's access log, in any proxy in between and in this client's own
 * trace spans, so a primary account number in a path is a primary account number written to several logs
 * that are neither encrypted for cardholder data nor scoped to the people who may read it. The card
 * number therefore travels in a request BODY on a lookup call, which costs one non-idiomatic verb and
 * keeps the value out of every access log on the path.</p>
 */
@Component
public class RestAccountContextClient implements AccountContextClient {

    /**
     * The path of the cross-reference lookup, which resolves a card to an account and a customer.
     */
    public static final String PATH_CARD_XREF_LOOKUP = "/api/v1/card-xrefs/lookup";

    /**
     * The path of the account read, which carries the account identifier in its body.
     *
     * <p>Refactoring Rationale: this was the path TEMPLATE {@code /api/v1/accounts/{accountId}} and the
     * read was a {@code GET}. It is a fixed path and a {@code POST} because the load balancer between
     * this client and the account context writes the request line of every hop into its access log,
     * composing that record itself before any application code runs -- and the migration's
     * sensitive-data logging contract names account identifiers among the values a durable diagnostic
     * may not hold. The cross-reference lookup above already had this shape for a card number; the
     * account read now has it for the same reason.</p>
     */
    public static final String PATH_ACCOUNT = "/api/v1/accounts/lookup";

    /**
     * The path of the customer existence check, which carries the customer identifier in its body.
     *
     * <p>Refactoring Rationale: this was the path template {@code /api/v1/customers/{customerId}} and the
     * check was issued with {@code HEAD}. It is a fixed path and a {@code POST} for the same reason as
     * the account read above. The answer is still carried entirely by the status code and the response
     * still has no body, so the property that made {@code HEAD} attractive is retained -- what changes is
     * only that the identifier no longer travels where it would be logged.</p>
     */
    public static final String PATH_CUSTOMER = "/api/v1/customers/lookup";

    /**
     * The address prefix of the cross-reference family, which the cross-reference read scope authorises.
     *
     * <p>Assumptions: each prefix is DERIVED from the path constant above it rather than written again, by
     * removing the final segment. Writing them as literals would let the two drift, and the symptom of a drift
     * is a refusal on one operation while every other one keeps working.</p>
     */
    private static final String CARD_XREF_PATH_PREFIX = parentOf(PATH_CARD_XREF_LOOKUP);

    /** The address prefix of the account family, which the account read scope authorises. */
    private static final String ACCOUNT_PATH_PREFIX = parentOf(PATH_ACCOUNT);

    /** The address prefix of the customer family, which the customer read scope authorises. */
    private static final String CUSTOMER_PATH_PREFIX = parentOf(PATH_CUSTOMER);

    /**
     * Removes the final segment of a path, yielding the family prefix its siblings share.
     *
     * @param path one of the path constants above; must not be {@code null} and must contain a separator
     * @return the path up to but excluding its final separator, never {@code null}
     */
    private static String parentOf(String path) {
        return path.substring(0, path.lastIndexOf('/'));
    }

    /**
     * The body member the lookup call carries the card number in.
     */
    private static final String LOOKUP_FIELD_CARD_NUM = "cardNumber";

    /**
     * The body member the account read carries the account identifier in.
     *
     * <p>Assumptions: the spelling matches the {@code accountId} property of the published
     * {@code AccountLookupRequest} schema. It is a constant so that this client and the test asserting
     * the outgoing body agree on one spelling rather than two that have to match.</p>
     */
    private static final String LOOKUP_FIELD_ACCOUNT_ID = "accountId";

    /**
     * The body member the customer existence check carries the customer identifier in.
     */
    private static final String LOOKUP_FIELD_CUSTOMER_ID = "customerId";

    /**
     * The only scheme the base address may use.
     *
     * <p>Assumptions: this is a constant rather than a configurable value, and that is the point. The
     * first request this client makes carries a primary account number in its body, so plain HTTP is
     * never admissible here regardless of environment -- including a local one, where a developer
     * pointing at a plain-HTTP stub would be exercising a transport the deployed system never uses.</p>
     */
    private static final String REQUIRED_SCHEME = "https";

    /**
     * The configured client, built once with a base address and both timeouts applied.
     */
    private final RestClient client;

    /**
     * Builds the client the three reads are issued through.
     *
     * <p>Assumptions: the builder arrives from the framework rather than being created here, so this
     * client inherits the application's own message converters -- which matters because amounts cross
     * this boundary as JSON STRINGS, per the migration's fixed-point rule, and a hand-built converter
     * set would be a second place that rule could be got wrong.</p>
     *
     * <p>Assumptions: the base address is injected and carries no default. A default would let this
     * service start against a plausible-looking wrong address and decline every authorization with the
     * not-found reason, which is far harder to notice than a service that refuses to start.</p>
     *
     * @param builder the framework's client builder; must not be {@code null}
     * @param machineIdentity the minter of the short-lived credential presented on every request; must not be
     *     {@code null}, because the account context denies anything unauthenticated
     * @param baseUrl the account context's base address, scheme and authority only; must not be
     *     {@code null}
     * @param approvedOrigin the exact origin the base address is required to equal; must not be
     *     {@code null} and must not be blank
     * @param connectTimeoutMillis how long to wait for the connection to be established
     * @param readTimeoutMillis how long to wait for the response once connected
     * @throws IllegalStateException if the base address is absent, is not an absolute HTTPS address,
     *     carries user information, a path, a query or a fragment, or is not the approved origin
     */
    public RestAccountContextClient(RestClient.Builder builder,
            InternalServiceToken machineIdentity,
            @Value("${carddemo.account-context.base-url}") String baseUrl,
            @Value("${carddemo.account-context.approved-origin:${carddemo.account-context.base-url}}")
            String approvedOrigin,
            @Value("${carddemo.account-context.connect-timeout-ms:2000}") long connectTimeoutMillis,
            @Value("${carddemo.account-context.read-timeout-ms:3000}") long readTimeoutMillis) {
        Objects.requireNonNull(machineIdentity, "machineIdentity must not be null");
        // WHY : Refactoring Rationale: the base address was passed straight to the builder unexamined,
        // and it is validated here because the FIRST request this client makes carries a primary account
        // number in its body -- the cross-reference lookup. An address that was plain HTTP would put that
        // number on the wire in clear text; one carrying a different host would send it to whoever
        // answers there; and one carrying user information would additionally leak a credential in a
        // header the request never intended to set. None of those fails visibly: each produces a service
        // that starts, and the exfiltration happens on the first authorization.
        requireApprovedOrigin(baseUrl, approvedOrigin);

        // WHY : Alternatives Considered: the framework's simple request factory, which is the shortest
        // way to set two timeouts. Rejected because it is superseded by this one, which is backed by the
        // platform's own HTTP client and so needs no third-party library to speak HTTP/1.1 with a
        // connection timeout. The connection timeout belongs on the underlying client and the read
        // timeout on the factory, which is why the two are set in two places rather than one.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        this.client = builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor(bearerTokenInterceptor(machineIdentity))
                .build();
    }

    /**
     * Builds the interceptor that presents this service's machine identity on every request.
     *
     * <p>Refactoring Rationale: the credential is attached by an INTERCEPTOR rather than at each of the three
     * call sites, and the difference is not stylistic. The account context denies anything unauthenticated, so a
     * call site that forgot the header would be refused with a 401 -- which this class reports as a dependency
     * being unavailable, so the message would be redelivered until it dead-lettered rather than failing with
     * anything that named the cause. An interceptor makes forgetting impossible: a fourth call added later
     * carries the credential without its author having to know that it must.</p>
     *
     * <p>Assumptions: a FRESH token is minted per request rather than one being minted here and reused for the
     * life of the client. Reuse would hand out a token near its expiry and have it rejected on arrival, which
     * presents as an intermittent authorization failure that looks like a permissions problem; minting per
     * request removes that class of failure and costs a signature against a network call.</p>
     *
     * <p>Assumptions: the audience and the scope are the shared constants, so the value this side mints and the
     * value the other side requires are the same literal rather than two strings that happen to match.</p>
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
        if (path.startsWith(CARD_XREF_PATH_PREFIX)) {
            return InternalServiceToken.SCOPE_CARD_XREF_READ;
        }
        if (path.startsWith(ACCOUNT_PATH_PREFIX)) {
            return InternalServiceToken.SCOPE_ACCOUNT_READ;
        }
        if (path.startsWith(CUSTOMER_PATH_PREFIX)) {
            return InternalServiceToken.SCOPE_CUSTOMER_READ;
        }
        throw new IllegalStateException("no internal scope is declared for '" + path
                + "'; every address this client calls must be assigned one, because the account context"
                + " authorises each family of addresses by its own scope");
    }

    /**
     * Creates a client over a transport the caller has already configured on the builder.
     *
     * <p>Assumptions: this constructor exists so that the REAL client can be driven over a mock
     * transport. It applies the same base-address validation as the public constructor and then leaves the
     * builder's request factory untouched, which is the whole difference between the two: the public
     * constructor installs its own factory in order to apply the two timeouts, and installing one replaces
     * whatever a test harness had bound. Without this seam a test would be reduced to stubbing
     * {@link AccountContextClient} itself, which asserts nothing about the code that actually runs -- and
     * a stubbed port is exactly the state that let a success-with-partial-body be reported as not-found
     * without a single test noticing.</p>
     *
     * <p>Trade-offs: a caller of this constructor gets no timeouts, so nothing reached through it
     * exercises the timeout wiring. That is accepted rather than worked around: the timeouts are a
     * property of the transport and not of this class's contract, and a test that needed to assert on them
     * would have to make a request that actually stalled, which is a slow and flaky thing to assert. What
     * this seam is for is the status-code and body handling, which is where the behaviour under test
     * lives.</p>
     *
     * <p>Alternatives Considered: moving the request-factory construction out into a configuration class
     * and injecting a {@code ClientHttpRequestFactory}. That is arguably the better long-term shape and it
     * is not done here, because it would move two configuration properties and a bean into a different
     * file for a reason unrelated to either finding this class is being corrected for -- and a wider
     * change than the correction needs is a wider change than the correction can be reviewed against.</p>
     *
     * @param builder the builder, with its request factory already configured by the caller; must not be
     *     {@code null}
     * @param machineIdentity the minter of the credential presented on every request; must not be
     *     {@code null}
     * @param baseUrl the account context's base address; must not be {@code null}
     * @param approvedOrigin the exact origin the base address is required to equal; must not be
     *     {@code null}
     * @throws IllegalStateException if the base address fails any of the checks the public constructor
     *     applies, so the seam cannot be used to bypass them
     * @throws NullPointerException if {@code machineIdentity} is {@code null}
     */
    RestAccountContextClient(RestClient.Builder builder, InternalServiceToken machineIdentity,
            String baseUrl, String approvedOrigin) {
        Objects.requireNonNull(machineIdentity, "machineIdentity must not be null");
        requireApprovedOrigin(baseUrl, approvedOrigin);
        // WHY : Assumptions: the credential interceptor IS installed on this seam, unlike the request
        //   factory. The two differ because only the factory conflicts with a bound mock transport; the
        //   interceptor composes with one, so leaving it out would make every test here exercise a client
        //   that presents no credential -- which is exactly the state the account context refuses, and the
        //   one finding C-08 is about.
        this.client = builder
                .baseUrl(baseUrl)
                .requestInterceptor(bearerTokenInterceptor(machineIdentity))
                .build();
    }

    /**
     * Refuses a base address that is not an absolute HTTPS origin equal to the approved one.
     *
     * <p>Assumptions: the shape checks run BEFORE the origin comparison, so a malformed value is
     * reported as malformed rather than as unapproved. The two failures have different remedies -- one is
     * a typo in a value, the other is a value pointing somewhere it should not -- and a single message
     * covering both would name neither.</p>
     *
     * <p>Assumptions: a path, a query and a fragment are each refused rather than trimmed. The client
     * appends its own paths to this value, so a base address carrying any of the three would compose an
     * address this class never declares: a base with a path silently re-roots every call, and a base with
     * a query silently attaches a parameter to all three. Refusing is what keeps the three published path
     * constants the complete description of what this client requests.</p>
     *
     * <p>Alternatives Considered: matching the address against a host suffix or a pattern instead of an
     * exact origin. Rejected for the same reason the reply-queue allowlist is exact rather than a prefix:
     * the legitimate value is a deployment fact published by a Terraform output, not a shape, and any
     * pattern loose enough to cover a legitimate internal origin also covers a host an attacker could
     * arrange to control. Alternatives Considered: a multi-value allowlist. Rejected because there is
     * exactly one account context, so a set with room for a second entry would invite one.</p>
     *
     * <p>Trade-offs: the approved origin defaults to the base address itself, so a deployment that
     * configures only the base address still starts and still gets every shape check. That is deliberate:
     * the origin check defends against a base address CHANGED after review, and requiring two values to
     * be configured identically in the ordinary case would be a step every operator learns to copy
     * without reading. Both Terraform roots publish the two names from one expression, so in a deployed
     * environment they are pinned to each other rather than to a default.</p>
     *
     * @param baseUrl the configured base address; may be {@code null}
     * @param approvedOrigin the origin the base address must equal; may be {@code null}
     * @throws IllegalStateException if either value is absent, or the base address is not an absolute
     *     HTTPS origin, or it is not the approved origin
     */
    private static void requireApprovedOrigin(String baseUrl, String approvedOrigin) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "carddemo.account-context.base-url must be supplied: the cross-reference lookup this"
                            + " client makes first carries a primary account number, so there is no safe"
                            + " default address to fall back to");
        }
        if (approvedOrigin == null || approvedOrigin.isBlank()) {
            throw new IllegalStateException(
                    "carddemo.account-context.approved-origin must be supplied when it is set at all");
        }
        URI address;
        try {
            address = new URI(baseUrl.trim());
        } catch (URISyntaxException malformed) {
            // WHY : Assumptions: the offending value is NOT quoted into the message. It is configuration
            // rather than cardholder data, so quoting it would be defensible -- and it is withheld
            // anyway, because a misconfigured value has on occasion been a credential-bearing address
            // and this message reaches a startup log that is retained. The property name locates the
            // fault without the value.
            throw new IllegalStateException(
                    "carddemo.account-context.base-url is not a valid address", malformed);
        }
        if (!REQUIRED_SCHEME.equalsIgnoreCase(address.getScheme())) {
            throw new IllegalStateException("carddemo.account-context.base-url must use the "
                    + REQUIRED_SCHEME + " scheme, because the first request carries a primary account"
                    + " number and plain HTTP would put it on the wire in clear text");
        }
        if (address.getHost() == null) {
            throw new IllegalStateException(
                    "carddemo.account-context.base-url must be absolute and name a host");
        }
        if (address.getUserInfo() != null) {
            throw new IllegalStateException("carddemo.account-context.base-url must carry no user"
                    + " information: it would place a credential in a header this client never declares");
        }
        if (address.getPath() != null && !address.getPath().isEmpty() && !"/".equals(address.getPath())) {
            throw new IllegalStateException("carddemo.account-context.base-url must carry no path,"
                    + " because this client appends its own and a base path would silently re-root every"
                    + " call");
        }
        if (address.getQuery() != null || address.getFragment() != null) {
            throw new IllegalStateException(
                    "carddemo.account-context.base-url must carry no query and no fragment,"
                            + " because either would be attached to all three published requests");
        }
        if (!normaliseOrigin(baseUrl).equals(normaliseOrigin(approvedOrigin))) {
            throw new IllegalStateException("carddemo.account-context.base-url is not the approved"
                    + " origin: the first request carries a primary account number, so an unapproved"
                    + " destination exfiltrates cardholder data on the first authorization");
        }
    }

    /**
     * Renders which part of an incomplete successful response was missing.
     *
     * <p>Assumptions: the rendering names COMPONENTS and never values, matching the discipline the queue
     * consumer applies to its own contract violations. One of the components a caller may pass here is a
     * money amount and another is derived from a card lookup, so a message quoting values would put
     * transaction detail into a log line whose only job is to tell an operator which field to look at.</p>
     *
     * <p>Trade-offs: an absent body and a present-but-incomplete body produce different text rather than
     * one shared message. They have different causes -- a serialiser that wrote nothing, against a
     * contract that changed shape -- and an operator reading the line acts differently on each.</p>
     *
     * @param bodyAbsent whether no body was returned at all
     * @param firstMissing whether the first named component was absent
     * @param secondMissing whether the second named component was absent
     * @param firstName the first component's name
     * @param secondName the second component's name
     * @return the description, never {@code null}
     */
    private static String describeIncomplete(boolean bodyAbsent, boolean firstMissing,
            boolean secondMissing, String firstName, String secondName) {
        if (bodyAbsent) {
            return "a success status and no body at all";
        }
        StringBuilder missing = new StringBuilder("a success status and a body missing ");
        if (firstMissing && secondMissing) {
            return missing.append(firstName).append(" and ").append(secondName).toString();
        }
        return missing.append(firstMissing ? firstName : secondName).toString();
    }

    /**
     * Reduces an address to a comparable origin.
     *
     * <p>Assumptions: only a trailing separator and surrounding blanks are removed, and the comparison is
     * otherwise exact and case-sensitive on the host. Normalising more -- lower-casing the whole value,
     * resolving a default port, following a redirect -- would make two addresses compare equal that a
     * resolver may treat differently, and the point of the comparison is to be stricter than a resolver
     * rather than more forgiving.</p>
     *
     * @param address the address to reduce; must not be {@code null}
     * @return the comparable form, never {@code null}
     */
    private static String normaliseOrigin(String address) {
        String trimmed = address.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * Resolves a card number to the account and customer it belongs to.
     *
     * @param cardNum the sixteen-character primary account number the request carried; must not be
     *     {@code null}
     * @return the account and customer the card maps to, or an empty optional when the account context
     *     explicitly reports the card is not cross-referenced
     * @throws AccountContextUnavailableException if the account context could not be asked, or answered
     *     with a success status and a body this contract cannot be built from; an empty optional is
     *     reserved for an explicit not-found answer, because only that answer is a decision input
     */
    @Override
    public Optional<CardXref> findCardXref(String cardNum) {
        try {
            CardXrefView view = this.client.post()
                    .uri(PATH_CARD_XREF_LOOKUP)
                    .body(Map.of(LOOKUP_FIELD_CARD_NUM, cardNum))
                    .retrieve()
                    .body(CardXrefView.class);
            if (view == null || view.accountId() == null || view.customerId() == null) {
                // WHY : Refactoring Rationale: this returned an empty optional -- that is, it reported
                // "the card is not cross-referenced" -- and it now raises. A 200 whose body is absent or
                // missing either identifier does not say the row is absent; it says the dependency
                // answered something this contract cannot be built from. Reporting it as not-found made
                // the consumer DECIDE the authorization, declining it on the baseline's card-not-found
                // reason, committing that decision to the account's history and answering the acquirer --
                // for a request that was never evaluated. Raising rolls the transaction back and lets the
                // queue redeliver, which is recoverable; a committed wrong decline is not.
                // WHY : Assumptions: the card number is absent from the message even though it would
                // identify the failed lookup, because this message reaches a log and the migration's
                // security mapping keeps primary account numbers out of logs. Which of the three
                // components was missing IS named, because that is what tells an operator whether the
                // dependency is truncating a body or omitting a field.
                throw new AccountContextUnavailableException("the account context answered the"
                        + " cross-reference lookup with " + describeIncomplete(view == null,
                        view == null || view.accountId() == null,
                        view == null || view.customerId() == null, "accountId", "customerId"));
            }
            return Optional.of(new CardXref(view.accountId(), view.customerId()));
        } catch (HttpClientErrorException.NotFound absent) {
            return Optional.empty();
        } catch (RestClientException failure) {
            // WHY : Assumptions: the card number is deliberately absent from this message, even though
            // it is the one fact that would identify the failed lookup. The message reaches a log, and
            // the migration's security mapping keeps primary account numbers out of logs; the
            // correlation identifier already in the logging context ties this line to the request.
            throw new AccountContextUnavailableException(
                    "the card cross-reference could not be read from the account context", failure);
        }
    }

    /**
     * Reads the account master record an authorization is decided against.
     *
     * @param accountId the eleven-digit account identifier resolved from the cross-reference
     * @return the account's limits and balance, or an empty optional when the account context explicitly
     *     reports the account master holds no such row
     * @throws AccountContextUnavailableException if the account context could not be asked, or answered
     *     with a success status and a body missing any of the three amounts a decision needs
     */
    @Override
    public Optional<Account> findAccount(long accountId) {
        try {
            AccountView view = this.client.post()
                    .uri(PATH_ACCOUNT)
                    .body(Map.of(LOOKUP_FIELD_ACCOUNT_ID, accountId))
                    .retrieve()
                    .body(AccountView.class);
            if (view == null || view.creditLimit() == null || view.cashCreditLimit() == null
                    || view.currentBalance() == null) {
                // WHY : Refactoring Rationale: a partial account raises for the same reason a partial
                // cross-reference does, and it previously returned an empty optional. Substituting zero
                // for an absent limit would decline every authorization on the account with the
                // insufficient-funds reason; reporting not-found declined them with the account-not-found
                // reason. Both read as business outcomes and both hide a broken contract behind a
                // committed decision, which is exactly what makes them worse than a redelivery.
                throw new AccountContextUnavailableException("the account context answered the read of"
                        + " account " + accountId + " with " + describeIncomplete(view == null,
                        view == null || view.creditLimit() == null,
                        view == null || view.cashCreditLimit() == null || view.currentBalance() == null,
                        "creditLimit", "cashCreditLimit or currentBalance"));
            }
            return Optional.of(new Account(view.creditLimit(), view.cashCreditLimit(),
                    view.currentBalance()));
        } catch (HttpClientErrorException.NotFound absent) {
            return Optional.empty();
        } catch (RestClientException failure) {
            throw new AccountContextUnavailableException(
                    "account " + accountId + " could not be read from the account context", failure);
        }
    }

    /**
     * Reports whether the customer master holds the customer a card is cross-referenced to.
     *
     * <p>Assumptions: the answer is carried entirely by the STATUS and the exchange has no response body,
     * which is a data-minimisation choice rather than a micro-optimisation. The baseline reads the whole
     * customer record and uses none of its fields, so a read here would carry a name, an address and a
     * national identifier across a context boundary in order to be discarded; this call answers the only
     * question this context asks. Refactoring Rationale: the call was issued as a {@code HEAD} on a keyed
     * path and is now a {@code POST} on the fixed path above, so the identifier no longer travels in the
     * request line; the property that made {@code HEAD} attractive is retained, because the response still
     * has no body on either answer and the body is discarded here with {@code toBodilessEntity}.</p>
     *
     * @param customerId the nine-digit customer identifier resolved from the cross-reference
     * @return {@code true} when the customer master holds the row, {@code false} when it does not
     * @throws AccountContextUnavailableException if the account context could not be asked
     */
    @Override
    public boolean customerExists(long customerId) {
        try {
            this.client.post()
                    .uri(PATH_CUSTOMER)
                    .body(Map.of(LOOKUP_FIELD_CUSTOMER_ID, customerId))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound absent) {
            return false;
        } catch (RestClientException failure) {
            throw new AccountContextUnavailableException(
                    "customer " + customerId + " could not be read from the account context", failure);
        }
    }

    /**
     * Reads the four customer display fields through the same lookup the existence check uses.
     *
     * <p>Assumptions: the SAME endpoint answers both operations, and the difference is only whether the
     * body is read. The existence check discards it because existence is all it needs; this one reads it
     * because the screen needs four of its fields. Alternatives Considered: a second, narrower endpoint on
     * the account context returning only these four; rejected because it would put a screen's field list
     * into another service's public contract, so a change to this screen would require a change there.</p>
     *
     * <p>Assumptions: only the four fields the screen renders are projected out of the response, and the
     * projection record is private to this class. The account context's customer record also carries a
     * national identifier, a government-issued identifier and a credit score; binding them into a type
     * this class could return would make widening the screen's exposure a matter of nobody's decision.</p>
     *
     * @param customerId the customer the summary's segment names
     * @return the display fields, or an empty optional when no such customer exists
     * @throws AccountContextUnavailableException if the account context cannot be reached
     */
    @Override
    public Optional<CustomerDisplay> customerDisplay(long customerId) {
        try {
            CustomerView view = this.client.post()
                    .uri(PATH_CUSTOMER)
                    .body(Map.of(LOOKUP_FIELD_CUSTOMER_ID, customerId))
                    .retrieve()
                    .body(CustomerView.class);
            if (view == null) {
                return Optional.empty();
            }
            return Optional.of(new CustomerDisplay(view.customerName(), view.addressLine1(),
                    view.addressLine2(), view.phoneNumber1()));
        } catch (HttpClientErrorException.NotFound absent) {
            return Optional.empty();
        } catch (RestClientException failure) {
            throw new AccountContextUnavailableException(
                    "customer " + customerId + " could not be read from the account context", failure);
        }
    }

    /**
     * The cross-reference lookup's response body.
     *
     * <p>Assumptions: both members are boxed so that an omitted member reads as {@code null} rather than
     * as zero. A primitive would turn a contract violation into account zero, which is a valid-looking
     * identifier that no row carries.</p>
     *
     * @param accountId the account the card belongs to, or {@code null} when the response omitted it
     * @param customerId the customer the card belongs to, or {@code null} when the response omitted it
     */
    private record CardXrefView(Long accountId, Long customerId) {
    }

    /**
     * The account read's response body.
     *
     * <p>Assumptions: the three amounts are {@link BigDecimal} and never {@code double}. The account
     * context renders them as JSON strings, which the framework's converter reads into a
     * {@link BigDecimal} without going through a binary floating-point value -- and going through one
     * would lose exactness on ordinary cent amounts, which is the failure this migration's fixed-point
     * rule exists to prevent.</p>
     *
     * @param creditLimit the account's credit limit, or {@code null} when the response omitted it
     * @param cashCreditLimit the account's cash credit limit, or {@code null} when omitted
     * @param currentBalance the account's posted balance, or {@code null} when omitted
     */
    private record AccountView(BigDecimal creditLimit, BigDecimal cashCreditLimit,
            BigDecimal currentBalance) {
    }

    /**
     * The four customer fields this class projects out of the account context's response.
     *
     * <p>Assumptions: the record names FOUR properties and the response carries more, which is deliberate.
     * The deserialiser ignores properties it has no component for, so declaring only these four is what
     * keeps the customer master's national identifier, government-issued identifier and credit score from
     * ever being materialised in this process.</p>
     *
     * @param customerName the customer's name as the account context publishes it
     * @param addressLine1 the first address line
     * @param addressLine2 the second address line
     * @param phoneNumber1 the customer's first telephone number
     */
    private record CustomerView(String customerName, String addressLine1, String addressLine2,
            String phoneNumber1) {
    }
}
