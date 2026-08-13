package com.carddemo.authorization.service;

import com.carddemo.common.security.ApprovedOriginPolicy;
import com.carddemo.common.security.InternalServiceToken;
import java.math.BigDecimal;
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
 * Reads the account context over its published contract, adapting it to {@link AccountContextClient}.
 *
 * <p><b>Purpose.</b> This is the outbound half of the seam the port describes: it turns the three reads
 * the baseline performs against the cross-reference, account and customer files -- at
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} paragraphs {@code 5100-READ-XREF-RECORD},
 * {@code 5200-READ-ACCT-RECORD} and {@code 5300-READ-CUST-RECORD} -- into calls on the context that owns
 * those records, plus a fourth read that serves the SCREEN rather than the decision. Everything specific
 * to the transport lives here and nothing else in this service names a URL, a status code or a media type,
 * so a change of transport is a change to this one file.</p>
 *
 * <p>Assumptions: the four calls split two-and-two by WHICH baseline program they come from, and the split
 * is why the customer file is read twice in two different shapes. Three of them serve the decision the
 * queue consumer takes and correspond to the paragraphs above; the fourth,
 * {@link #customerDisplay(long)}, serves {@code GATHER-ACCOUNT-DETAILS} of
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} at L750 through L779 -- the online summary
 * screen, which composes a name, two address lines and a telephone number the decision path never looks
 * at. Collapsing the two customer calls into one would make the decision path materialise nine display
 * fields it discards, on every authorization.</p>
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
 * an error. That paragraph is about the three DECISION reads: the display read is issued from a screen
 * request rather than from the queue consumer, so it occupies a request thread and no deciding
 * transaction. Alternatives Considered: reading the account context asynchronously and resuming the
 * message on its reply. Rejected because a decision cannot be made without these records, so the consumer would
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
 * business route -- so each of these calls would have been refused before it reached a handler, and
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
 * of the four methods below. An initializer runs for every request this client issues, including one
 * added later, so a new call cannot be written that forgets it -- which is the failure the revision
 * above is an instance of, and which is why the display read added later needed no credential code of its
 * own.</p>
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
     * The path of the customer DISPLAY read, which answers the nine stored fields this screen composes from.
     *
     * <p>Refactoring Rationale: {@link #customerDisplay(long)} used to issue {@value #PATH_CUSTOMER} and
     * deserialise its response. That address is the EXISTENCE check: it answers 204 or 404 and carries no body
     * at all by contract, deliberately, so that an absence cannot return a customer identifier inside a problem
     * document that is itself logged. A bodiless 204 deserialises to nothing rather than to an error, so every
     * display field on the pending-authorization screen rendered as absent on every request and nothing
     * anywhere failed while they did. This address is body-bearing and exists for exactly this read.</p>
     *
     * <p>Assumptions: it needs NO new scope. It is a sibling of {@value #PATH_CUSTOMER} beneath the same
     * {@code /api/v1/customers} parent, so {@link #CUSTOMER_PATH_PREFIX} already matches it and
     * {@link #scopeFor(String)} already assigns it
     * {@link InternalServiceToken#SCOPE_CUSTOMER_READ} -- the decision-read scope this client already holds.
     * That is a property of the address the account context chose, not a coincidence: it published this
     * projection beneath the decision-read prefix precisely so that a screen would not have to be granted
     * {@link InternalServiceToken#SCOPE_CUSTOMER_MASTER_READ}.</p>
     *
     * <p>Alternatives Considered: pointing this read at {@code /api/v1/customers/record}, which is
     * body-bearing and needed no new address anywhere. Rejected on least privilege: that operation answers
     * with the WHOLE customer record and is gated on
     * {@link InternalServiceToken#SCOPE_CUSTOMER_MASTER_READ}, an authority this system mints for no context
     * because it reads a national identifier, a government-issued identifier and a credit score for any
     * customer. Minting it so that four values could be shown on a screen is the escalation that scope split
     * was introduced to prevent.</p>
     */
    public static final String PATH_CUSTOMER_DISPLAY = "/api/v1/customers/display";

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

    /**
     * The address prefix of the customer family, which the customer read scope authorises.
     *
     * <p>Assumptions: it is derived from the EXISTENCE check's path and covers the display read beside it,
     * because both are siblings beneath {@code /api/v1/customers}. Deriving it from either yields the same
     * prefix, so the display read acquires the decision-read scope without a second entry being written --
     * and a second entry is exactly what would let the two drift apart.</p>
     */
    private static final String CUSTOMER_PATH_PREFIX = parentOf(PATH_CUSTOMER);

    /**
     * The width the pending-authorization map declares for the composed name and for each address line.
     *
     * <p>Assumptions: the composition TRUNCATES at this width rather than overflowing, because the reference
     * composes with {@code STRING ... INTO} a fixed-width receiving field.
     * {@code app/app-authorization-ims-db2-mq/cpy-bms/COPAU00.cpy} declares {@code CNAMEI PIC X(25)} at L66,
     * {@code ADDR001I PIC X(25)} at L78 and {@code ADDR002I PIC X(25)} at L90, and a {@code STRING} that runs
     * out of receiving positions stops there. Publishing an untruncated value would be a different value from
     * the one the screen showed.</p>
     */
    private static final int SCREEN_TEXT_WIDTH = 25;

    /**
     * The width the pending-authorization map declares for the telephone number.
     *
     * <p>Assumptions: the stored value is FIFTEEN characters and narrows to thirteen HERE, in the consumer,
     * rather than in the account context that published it. {@code CUST-PHONE-NUM-1 PIC X(15)} at L15 of
     * {@code app/cpy/CVCUS01Y.cpy} reaches {@code PHONE1I PIC X(13)} at L96 of
     * {@code app/app-authorization-ims-db2-mq/cpy-bms/COPAU00.cpy} through the plain {@code MOVE} at L779 of
     * {@code COPAUS0C.cbl}, and an alphanumeric {@code MOVE} to a shorter field truncates on the right. The
     * account context publishes the stored width for that reason: the narrowing belongs to this screen.</p>
     */
    private static final int SCREEN_PHONE_WIDTH = 13;

    /**
     * The number of leading postal-code characters the second address line renders.
     *
     * <p>Assumptions: the stored postal code is ten characters and the screen shows five, and the reference
     * takes the leading five explicitly -- {@code CUST-ADDR-ZIP(1:5)} at L775 of {@code COPAUS0C.cbl}. The
     * four-digit extension is therefore dropped at the point of display and is present in what the account
     * context published, which is what lets a later reader see the whole stored value without this screen
     * changing.</p>
     */
    private static final int POSTAL_CODE_SCREEN_LENGTH = 5;

    /**
     * The delimiter the reference composes each name component up to.
     *
     * <p>Assumptions: a SINGLE space, because {@code STRING CUST-FIRST-NAME DELIMITED BY SPACES} at L758 of
     * {@code COPAUS0C.cbl} transfers up to the first space -- so a two-word given name contributes only its
     * first word. That is the reference's behaviour and it is reproduced rather than corrected: the composed
     * name is what the screen showed, and quietly widening it would make one screen disagree with its own
     * golden output.</p>
     */
    private static final String NAME_COMPONENT_DELIMITER = " ";

    /**
     * The delimiter the reference composes each address line up to.
     *
     * <p>Assumptions: TWO spaces, because {@code STRING CUST-ADDR-LINE-1 DELIMITED BY '  '} at L766 of
     * {@code COPAUS0C.cbl} transfers up to the first pair of spaces. On a fixed-width field that pair is the
     * trailing pad, so an address containing single spaces transfers whole -- which is why the address lines
     * use a two-space delimiter and the name components use one.</p>
     */
    private static final String ADDRESS_COMPONENT_DELIMITER = "  ";

    /** The separator the reference writes between the components of each composed address line. */
    private static final String ADDRESS_SEPARATOR = ",";

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
     * The account identifier's width in the published request schema, eleven characters.
     *
     * <p>⚠️ Assumptions: the schema declares this member as digits-only TEXT of at most this width, so
     * the number this class holds is rendered before it is sent. It previously travelled as a JSON
     * number, which the account context accepted only because its reader coerces a number to a string
     * for a textual member -- so the request conformed to nothing the document declared and would be
     * refused outright the moment that leniency were withdrawn, exactly as this context has already
     * withdrawn it for its own request bodies.</p>
     *
     * <p>Assumptions: the rendering is left-zero-padded to the declared width rather than written at
     * whatever length the number happens to have. The pattern admits both forms and they resolve to the
     * same row, so the padding is not required for acceptance; it is chosen because the width is what
     * the reference field declares and a fixed-width value is what an operator reading a captured
     * request would recognise as an account key.</p>
     */
    private static final int ACCOUNT_ID_WIDTH = 11;

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
    /**
     * The configuration prefix this seam's two address properties sit under.
     *
     * <p>Assumptions: the prefix is named once and both the base address and the approved origin are
     * read from it, so the two cannot come to sit under different prefixes. Every refusal the shared
     * policy raises names its property from this value, which is why the messages are unchanged.</p>
     */
    private static final String PROPERTY_PREFIX = "carddemo.account-context";

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
    // WHY : Assumptions: the annotation is REQUIRED here and its absence stopped this context from
    //       starting at all. Spring's implicit constructor injection applies only to a class with
    //       exactly ONE constructor; this class has two, the second being the package-private test
    //       seam below. With two candidates and neither marked, the container stops looking for an
    //       injectable constructor and falls back to a no-argument one, which this class does not
    //       declare, so bean creation failed with "No default constructor found" and the whole
    //       authorization context failed to refresh rather than degrading. The identical defect and
    //       the identical remedy are recorded on
    //       account-service RestReferenceAddressLookup's public constructor; this is the same fix
    //       applied to the second occurrence rather than a new judgement.
    // WHY : Alternatives Considered: withdrawing the test seam so one constructor would again be
    //       implicit. Rejected for the reason recorded on the seam itself -- the public constructor
    //       installs its own request factory to apply the two timeouts, and installing one REPLACES
    //       whatever transport a test had bound, so the tests that drive the real client over a mock
    //       transport would reach the network instead. Marking the injection point keeps the seam and
    //       the timeouts both.
    @Autowired
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
     * Refuses the configured address unless it is the approved absolute HTTPS origin.
     *
     * <p>Refactoring Rationale: the seven refusals this used to perform inline now come from the shared
     * kernel's {@link ApprovedOriginPolicy}, and the messages are unchanged character for character because
     * the prefix and the three risk clauses below are the ones this copy carried. Two modules held a
     * structurally identical copy of the check and a third was about to add one; transformation rule T2 puts
     * a shared concern in the kernel exactly once, and the failure mode of three copies is that one of them
     * gets strengthened.</p>
     *
     * <p>Assumptions: the clauses stay HERE rather than moving into the kernel with the check, because they
     * state what is at risk on THIS seam and no other seam shares it. A kernel-side default would have to be
     * vague enough to fit every caller, and a vague reason is what the Explainability rule forbids.</p>
     *
     * @param baseUrl the configured base address; may be {@code null}
     * @param approvedOrigin the origin the base address must equal; may be {@code null}
     * @throws IllegalStateException if either value is absent, or the base address is not an absolute
     *     HTTPS origin, or it is not the approved origin
     */
    private static void requireApprovedOrigin(String baseUrl, String approvedOrigin) {
        ApprovedOriginPolicy.require(PROPERTY_PREFIX, baseUrl, approvedOrigin,
                new ApprovedOriginPolicy.Sensitivity(
                        "the cross-reference lookup this client makes first carries a primary account"
                                + " number, so there is no safe default address to fall back to",
                        "the first request carries a primary account number",
                        "the first request carries a primary account number, so an unapproved"
                                + " destination exfiltrates cardholder data on the first authorization"));
    }

    /**
     * Renders an account identifier as the fixed-width character key the published schema declares.
     *
     * <p>Assumptions: the format is composed from {@link #ACCOUNT_ID_WIDTH} rather than written as a
     * literal specifier, so the width is stated once and the rendering cannot fall out of step with the
     * constant that documents it.</p>
     *
     * <p>Assumptions: a value wider than the declared width is rendered as it stands rather than being
     * truncated here, and it is then refused by the receiving pattern. Truncating would send a DIFFERENT
     * account's identifier -- a silently wrong lookup answered 200 -- where refusal names the fault.</p>
     *
     * @param accountId the account identifier resolved from the cross-reference
     * @return the identifier as decimal digits, left-zero-padded to the declared width, never
     *     {@code null}
     */
    private static String renderAccountId(long accountId) {
        // WHY : Assumptions: the locale is stated rather than defaulted. This renders a fixed-width
        //       KEY, and the no-locale overload formats with the JVM default, so a non-Latin
        //       numbering system would substitute its own digits into the identifier and the
        //       receiving pattern would refuse a value that is arithmetically correct. A build
        //       runner's default locale is Latin, so no test would have shown it. This matches the
        //       zero-padding idiom PendingAuthDetailMapper already uses for the same reason.
        return String.format(Locale.ROOT, "%0" + ACCOUNT_ID_WIDTH + "d", accountId);
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
                    .body(Map.of(LOOKUP_FIELD_ACCOUNT_ID, renderAccountId(accountId)))
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
                // WHY : Assumptions: the account identifier is ABSENT from this message even though it
                // would name the failed read, for the same reason the card number is absent from the
                // cross-reference sentence above: this message reaches a log at every level that records
                // the cause, and the migration's security mapping keeps account identifiers out of durable
                // diagnostics. Which components were missing IS named, because that is what tells an
                // operator whether the dependency truncated a body or omitted a field, and the correlation
                // identifier the shared filter carries is what ties the failure back to one request.
                throw new AccountContextUnavailableException("the account context answered the account"
                        + " read with " + describeIncomplete(view == null,
                        view == null || view.creditLimit() == null,
                        view == null || view.cashCreditLimit() == null || view.currentBalance() == null,
                        "creditLimit", "cashCreditLimit or currentBalance"));
            }
            return Optional.of(new Account(view.creditLimit(), view.cashCreditLimit(),
                    view.currentBalance()));
        } catch (HttpClientErrorException.NotFound absent) {
            return Optional.empty();
        } catch (RestClientException failure) {
            // WHY : Assumptions: the identifier is absent here for the reason recorded on the partial-body
            //       sentence above. Naming the operation is enough to locate the fault; naming the account
            //       would put a key that identifies a cardholder's account into every log line that
            //       records this cause.
            throw new AccountContextUnavailableException(
                    "the account could not be read from the account context", failure);
        }
    }

    /**
     * Reports whether the customer master holds the customer a card is cross-referenced to.
     *
     * <p>Assumptions: the answer is carried entirely by the STATUS and the exchange has no response body,
     * which is a data-minimisation choice rather than a micro-optimisation. The DECIDING program reads the
     * whole customer record and uses none of its fields, so a read here would carry a name, an address and
     * a national identifier across a context boundary in order to be discarded; this call answers the only
     * question the decision asks. The SCREEN does read customer fields, and it reads them through
     * {@link #customerDisplay(long)} rather than through this method, which is why the two exist
     * separately. Refactoring Rationale: the call was issued as a {@code HEAD} on a keyed
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
            // WHY : Assumptions: the customer identifier is absent from this sentence for the same reason
            //       the account identifier is absent from the read above. A customer identifier names a
            //       person as directly as an account identifier names their account, and this sentence
            //       reaches a log wherever the cause is recorded.
            throw new AccountContextUnavailableException(
                    "the customer existence check could not be answered by the account context", failure);
        }
    }

    /**
     * Reads the nine stored customer fields and composes the four values the screen renders.
     *
     * <p>Refactoring Rationale: this call issued {@value #PATH_CUSTOMER} -- the EXISTENCE check, which
     * answers 204 or 404 and carries no body at all by contract -- and deserialised the response into a
     * record whose first member was a composed name that no operation on that contract publishes and no
     * column exists for. A bodiless 204 deserialises to nothing rather than to an error, so all four display
     * fields rendered as absent on every request and nothing anywhere failed while they did. It now issues
     * {@value #PATH_CUSTOMER_DISPLAY}, which is body-bearing, requires the scope this client already holds,
     * and publishes the stored columns.</p>
     *
     * <p>Refactoring Rationale: the COMPOSITION happens here rather than in the account context, and it is
     * the whole reason nine fields are read to produce four. The account context published the columns as
     * stored -- three name components, three address lines, a state code and an unnarrowed ten-character
     * postal code -- because which separator joins a city to a state, whether an absent middle name collapses
     * the spacing, and whether ten characters narrow to five are all decisions of the screen that shows them.
     * Taking those decisions inside the context that owns the column would fix one consumer's presentation
     * there, on behalf of a screen it cannot see. This adapter is the anti-corruption boundary, so this is
     * where a foreign representation becomes this context's own.</p>
     *
     * <p>Assumptions: the composition reproduces {@code GATHER-ACCOUNT-DETAILS} of
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} statement for statement -- the name at L758
     * through L764, the first address line at L766 through L770, the second at L771 through L777 and the
     * telephone number at L779 -- including three behaviours that look like defects and are not. A blank
     * middle name still contributes its single position, so the name carries THREE spaces between the given
     * and family names rather than one. A given or family name containing a space contributes only its first
     * word, because the reference delimits by a single space. And every composed value truncates at the map
     * width rather than overflowing. Each is what the screen showed, so each is what is produced.</p>
     *
     * <p>Assumptions: only the nine fields the screen composes from are declared on the wire record, and that
     * record stays private to this class. The customer master also holds a national identifier, a
     * government-issued identifier and a credit score, and the published projection carries none of the three
     * -- so this class cannot materialise them even by accident, and a type it could return would make
     * widening that exposure a matter of nobody's decision.</p>
     *
     * @param customerId the customer the summary's segment names
     * @return the four composed screen values, or an empty optional when no such customer exists
     * @throws AccountContextUnavailableException if the account context cannot be reached
     */
    @Override
    public Optional<CustomerDisplay> customerDisplay(long customerId) {
        try {
            CustomerDisplayWire wire = this.client.post()
                    .uri(PATH_CUSTOMER_DISPLAY)
                    .body(Map.of(LOOKUP_FIELD_CUSTOMER_ID, customerId))
                    .retrieve()
                    .body(CustomerDisplayWire.class);
            if (wire == null) {
                return Optional.empty();
            }
            return Optional.of(compose(wire));
        } catch (HttpClientErrorException.NotFound absent) {
            return Optional.empty();
        } catch (RestClientException failure) {
            // WHY : Assumptions: the sentence names the OPERATION and not the customer. The identifier used
            //       to be interpolated here, and this sentence reaches a log at every level that records the
            //       cause -- which the migration's sensitive-data contract forbids for a customer identifier
            //       just as it does for an account identifier. The correlation identifier the shared filter
            //       carries is what ties this failure back to one request, and it does so without putting a
            //       key that names a person into a durable diagnostic.
            throw new AccountContextUnavailableException(
                    "the customer display fields could not be read from the account context", failure);
        }
    }

    /**
     * Composes the four screen values out of the nine stored fields, as the reference program does.
     *
     * <p>Assumptions: each of the four is composed by the statement of {@code COPAUS0C.cbl} named beside it,
     * so that a reader can hold the two side by side. The reference receives into fixed-width map fields, so
     * every result truncates at {@link #SCREEN_TEXT_WIDTH} -- or at {@link #SCREEN_PHONE_WIDTH} for the
     * telephone number, which the reference narrows with a plain {@code MOVE} rather than a {@code STRING}.</p>
     *
     * @param wire the nine stored fields as the account context published them; must not be {@code null}
     * @return the four values the map fields carry, never {@code null}
     */
    private static CustomerDisplay compose(CustomerDisplayWire wire) {
        // WHY : Assumptions: this reproduces L758-L764 exactly, INCLUDING the blank middle position. The
        //       reference concatenates the given name up to its first space, a literal space, the FIRST
        //       CHARACTER of the middle name whatever that character is, another literal space, and the
        //       family name up to its first space. When no middle name is stored that first character is a
        //       space, so the composed name carries three spaces in the middle. Collapsing them would read
        //       as tidier and would be a different value from the one the screen showed.
        String composedName = truncate(upTo(wire.firstName(), NAME_COMPONENT_DELIMITER)
                + NAME_COMPONENT_DELIMITER
                + firstPosition(wire.middleName())
                + NAME_COMPONENT_DELIMITER
                + upTo(wire.lastName(), NAME_COMPONENT_DELIMITER), SCREEN_TEXT_WIDTH);

        // WHY : Assumptions: L766-L770. Both address lines are delimited by TWO spaces rather than one, so an
        //       address containing ordinary single spaces transfers whole and only the trailing pad delimits.
        //       An absent second line contributes nothing after the separator, which leaves the trailing
        //       comma the reference also leaves.
        String composedLine1 = truncate(upTo(wire.addressLine1(), ADDRESS_COMPONENT_DELIMITER)
                + ADDRESS_SEPARATOR
                + upTo(wire.addressLine2(), ADDRESS_COMPONENT_DELIMITER), SCREEN_TEXT_WIDTH);

        // WHY : Assumptions: L771-L777, which is why three of the nine fields exist on the wire record at
        //       all. The screen's SECOND address line is not CUST-ADDR-LINE-2: it is the third line, the
        //       state code and the leading five postal-code characters. The state code is taken whole
        //       because the reference delimits it BY SIZE rather than by spaces.
        String composedLine2 = truncate(upTo(wire.addressLine3(), ADDRESS_COMPONENT_DELIMITER)
                + ADDRESS_SEPARATOR
                + blankIfAbsent(wire.stateCode())
                + ADDRESS_SEPARATOR
                + truncate(blankIfAbsent(wire.zipCode()), POSTAL_CODE_SCREEN_LENGTH), SCREEN_TEXT_WIDTH);

        // WHY : Assumptions: L779 is a plain MOVE from a fifteen-character field into a thirteen-character
        //       one, and an alphanumeric MOVE to a shorter field truncates on the right. The account context
        //       publishes the stored fifteen precisely so this narrowing happens where the map width is
        //       known, rather than being adopted there as though it were the layout.
        String screenPhone = truncate(blankIfAbsent(wire.phoneNumber1()), SCREEN_PHONE_WIDTH);

        return new CustomerDisplay(composedName, composedLine1, composedLine2, screenPhone);
    }

    /**
     * Returns the leading characters of a value up to its first occurrence of a delimiter.
     *
     * <p>Assumptions: an absent value yields the empty string rather than the word {@code null}, because the
     * reference composes from a fixed-width field that is blank rather than from a value that is missing.</p>
     *
     * @param value the stored value, which may be {@code null} when the column holds nothing
     * @param delimiter the delimiter the reference composes up to; must not be {@code null}
     * @return the value up to but excluding the first delimiter, or the whole value when it contains none
     */
    private static String upTo(String value, String delimiter) {
        String present = blankIfAbsent(value);
        int boundary = present.indexOf(delimiter);
        return boundary < 0 ? present : present.substring(0, boundary);
    }

    /**
     * Returns the first character of a value, or a single space when it has none.
     *
     * <p>Assumptions: a space is returned rather than nothing, because {@code CUST-MIDDLE-NAME(1:1)} is a
     * reference modification of a fixed-width field: it always yields exactly one character, and that
     * character is a space when the field is blank. Returning nothing would close the gap the screen
     * showed.</p>
     *
     * @param value the stored middle name, which may be {@code null} when the column holds nothing
     * @return exactly one character, never {@code null}
     */
    private static String firstPosition(String value) {
        String present = blankIfAbsent(value);
        return present.isEmpty() ? NAME_COMPONENT_DELIMITER : present.substring(0, 1);
    }

    /**
     * Truncates a value on the right at a width, leaving a shorter value alone.
     *
     * @param value the composed or stored value; must not be {@code null}
     * @param width the receiving width; must be positive
     * @return the value, truncated on the right when it exceeds the width, never {@code null}
     */
    private static String truncate(String value, int width) {
        return value.length() <= width ? value : value.substring(0, width);
    }

    /**
     * Substitutes the empty string for an absent value.
     *
     * @param value a stored value that may be {@code null} when its column holds nothing
     * @return the value, or the empty string when it is {@code null}, never {@code null}
     */
    private static String blankIfAbsent(String value) {
        return value == null ? "" : value;
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
    
        /**
         * Renders neither identifier, matching the seam record this wire shape is mapped into.
         *
         * <p>Purpose. Both components are identifiers {@code docs/architecture/observability.md} L1093 to
         * L1112 withholds. This is the JSON-binding shape rather than the seam type, and it is the one that
         * reaches a diagnostic FIRST: a deserialisation fault is raised while this record is being built,
         * before any mapping to the seam type has happened.</p>
         *
         * <p>Assumptions: the rendering is identical in content to the seam record's rather than
         * deliberately different. Two shapes for one row that rendered differently would let a reader
         * conclude the values had changed in the mapping when only the renderer had.</p>
         *
         * @return a rendering naming the type with both identifiers withheld; never {@code null}
         */
        @Override
        public String toString() {
            return "CardXrefView[identifiers=[REDACTED]]";
        }
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
    
        /**
         * Renders no value at all: all three components are limits or balances.
         *
         * <p>Purpose. This is the JSON-binding shape for the account seam, and its three components are
         * the credit limit, the cash credit limit and the balance -- withheld as a class by
         * {@code docs/architecture/observability.md} L1093 to L1112. It is stringified earlier than the
         * seam record it maps into, because a binding failure names the type being bound.</p>
         *
         * <p>Assumptions: the content matches the seam record's rendering exactly, for the reason recorded
         * on the cross-reference wire shape beside it -- one row rendered two ways makes neither
         * authoritative.</p>
         *
         * @return a rendering naming the type with all three amounts withheld; never {@code null}
         */
        @Override
        public String toString() {
            return "AccountView[amounts=[REDACTED]]";
        }
}

    /**
     * The nine stored customer fields the display read publishes, exactly as it publishes them.
     *
     * <p>Assumptions: the component list matches the published {@code CustomerDisplayView} schema
     * COMPONENT FOR COMPONENT rather than being a narrower selection out of a wider body. That projection is
     * a closed schema -- it declares {@code additionalProperties: false} and carries no protected value of any
     * kind -- so there is nothing here to leave out, and naming a member the schema does not publish is
     * exactly the defect this record replaces: its predecessor declared a composed {@code customerName} that
     * no operation published and no column held, and every field it fed rendered as absent.</p>
     *
     * <p>Assumptions: nothing here needs masking and nothing here is masked, which is a property of the
     * published field list rather than of this record. The national identifier at L17 of
     * {@code app/cpy/CVCUS01Y.cpy}, the government-issued identifier at L18 and the credit score are absent
     * from the projection altogether, so they cannot reach this process to be masked or unmasked.</p>
     *
     * <p>Assumptions: the postal code arrives UNNARROWED at its stored ten characters and the telephone
     * number at its stored fifteen. Both narrow in {@link #compose(CustomerDisplayWire)}, where the map
     * widths are known. The country code the record declares at L13 is absent from the projection because no
     * line of this screen renders it.</p>
     *
     * @param firstName the given name, {@code CUST-FIRST-NAME PIC X(25)}
     * @param middleName the middle name, {@code CUST-MIDDLE-NAME PIC X(25)}, or {@code null} when blank
     * @param lastName the family name, {@code CUST-LAST-NAME PIC X(25)}
     * @param addressLine1 the first address line, {@code CUST-ADDR-LINE-1 PIC X(50)}
     * @param addressLine2 the second address line, {@code CUST-ADDR-LINE-2 PIC X(50)}, or {@code null}
     * @param addressLine3 the third address line, which carries the city, {@code CUST-ADDR-LINE-3 PIC X(50)}
     * @param stateCode the state code, {@code CUST-ADDR-STATE-CD PIC X(02)}
     * @param zipCode the postal code at its stored ten characters, {@code CUST-ADDR-ZIP PIC X(10)}
     * @param phoneNumber1 the primary telephone number at its stored fifteen characters
     */
    private record CustomerDisplayWire(String firstName, String middleName, String lastName,
            String addressLine1, String addressLine2, String addressLine3, String stateCode,
            String zipCode, String phoneNumber1) {
    
        /**
         * Renders none of the nine values, reporting only which optional members arrived.
         *
         * <p>Purpose. Every component is personal data about an identified cardholder: a name in three
         * parts, an address in five and a telephone number. {@code docs/architecture/observability.md}
         * L1093 to L1112 withholds all of them, and this shape holds the widest set of them anywhere on
         * this seam -- it is the wire form of the account context's display projection, so the generated
         * rendering reproduced a whole customer's postal identity at the point where a binding fault is
         * reported.</p>
         *
         * <p>Assumptions: the two flags cover the two members the published contract permits to be absent,
         * which are the middle name and the second address line. The other seven are declared non-nullable
         * by the owning context, so a flag over them would be a constant dressed as an observation -- the
         * same reasoning the owning projection records for the same nine fields.</p>
         *
         * @return a rendering reporting which optional members are present, with all nine personal values
         *     withheld; never {@code null}
         */
        @Override
        public String toString() {
            return "CustomerDisplayWire[middleNamePresent=" + (this.middleName != null)
                    + ", addressLine2Present=" + (this.addressLine2 != null)
                    + ", personalData=[REDACTED]]";
        }
}
}
