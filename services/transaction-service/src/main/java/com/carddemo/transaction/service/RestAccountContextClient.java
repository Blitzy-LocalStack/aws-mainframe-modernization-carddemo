package com.carddemo.transaction.service;

import com.carddemo.common.money.Money;
import com.carddemo.common.security.InternalServiceToken;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads and updates account-owned records over the account context's published HTTP surface.
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
 * <p>Alternatives Considered: composing the lookup path with the card number in it, which reads more
 * directly than a request body does. Rejected because a card number in a path is a card number in an
 * access log, and access-log storage is the one destination the masking applied at the API edge does not
 * reach; the account context publishes the lookup as a POST for the same reason.</p>
 */
@Component
public class RestAccountContextClient implements AccountContextClient {

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
     * The account context's account master read, which takes its key in a request body.
     *
     * <p>Refactoring Rationale: was {@code /api/v1/accounts/{accountId}} as a {@code GET}; it is now the
     * published {@code POST} lookup, for the reason recorded above. The account context's contract moved
     * with it, so this is a change of shape on both sides rather than a client working around a server.</p>
     */
    public static final String PATH_ACCOUNT = "/api/v1/accounts/lookup";

    /**
     * The account context's balance-reducing payment operation, which takes its key in its request body.
     *
     * <p>Refactoring Rationale: was {@code /api/v1/accounts/{accountId}/payments}. This one was ALREADY a
     * {@code POST} carrying a body, so the identifier was in the target for no reason at all -- the body it
     * needed was already there and the amount was already in it. Moving the identifier alongside the
     * amount removes the disclosure at no cost whatsoever.</p>
     */
    public static final String PATH_ACCOUNT_PAYMENT = "/api/v1/accounts/payments";

    /**
     * The address prefix of the cross-reference family, which the cross-reference read scope authorises.
     *
     * <p>Assumptions: each prefix is DERIVED from a path constant above rather than written again, by removing
     * the final segment. Writing them as literals would let the two drift, and the symptom of a drift is a
     * refusal on one operation while every other one keeps working.</p>
     */
    private static final String CARD_XREF_PATH_PREFIX = parentOf(PATH_CARD_XREF_LOOKUP);

    /** The address prefix of the account family, which the account read scope authorises. */
    private static final String ACCOUNT_PATH_PREFIX = parentOf(PATH_ACCOUNT);

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
     * The request member every account-keyed call carries the account identifier in.
     *
     * <p>Assumptions: the spelling matches the {@code accountId} property of the account context's
     * published {@code AccountLookupRequest} schema, and one constant serves all three account-keyed
     * calls so they cannot disagree about it.</p>
     */
    private static final String FIELD_ACCOUNT_ID = "accountId";

    /** The request member the payment operation keys its amount on. */
    private static final String FIELD_AMOUNT = "amount";

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
     * @param connectTimeoutMillis how long to wait for the connection, in milliseconds
     * @param readTimeoutMillis how long to wait for the response, in milliseconds
     */
    public RestAccountContextClient(RestClient.Builder builder,
            InternalServiceToken machineIdentity,
            @Value("${carddemo.account-context.base-url}") String baseUrl,
            @Value("${carddemo.account-context.connect-timeout-ms:2000}") long connectTimeoutMillis,
            @Value("${carddemo.account-context.read-timeout-ms:3000}") long readTimeoutMillis) {

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
        if (path.startsWith(CARD_XREF_PATH_PREFIX)) {
            return InternalServiceToken.SCOPE_CARD_XREF_READ;
        }
        if (path.startsWith(ACCOUNT_PATH_PREFIX)) {
            return InternalServiceToken.SCOPE_ACCOUNT_READ;
        }
        // WHY : Assumptions: there is no customer branch here, and its absence is the point rather than an
        //   omission. This context reads no customer record, so it is not permitted to carry the customer
        //   scope at all -- the closed table in InternalServiceToken withholds it -- and a branch that
        //   returned it would raise from the minter instead of from here, naming the scope rather than the
        //   address. Raising here names the address, which is what a reader adding one needs to see.
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
            return Optional.ofNullable(view).map(CardXrefView::toCardXref);
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
            CardXrefView view = this.client.post()
                    .uri(PATH_CARD_XREF_BY_ACCOUNT)
                    .body(Map.of(FIELD_ACCOUNT_ID, accountId))
                    .retrieve()
                    .body(CardXrefView.class);
            return Optional.ofNullable(view).map(CardXrefView::toCardXref);
        } catch (HttpClientErrorException.NotFound absent) {
            return Optional.empty();
        } catch (RestClientException failure) {
            throw new AccountContextUnavailableException(
                    "card cross-reference lookup by account identifier did not answer", failure);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param accountId {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public Optional<AccountBalance> findAccountBalance(String accountId) {
        try {
            AccountView view = this.client.post()
                    .uri(PATH_ACCOUNT)
                    .body(Map.of(FIELD_ACCOUNT_ID, accountId))
                    .retrieve()
                    .body(AccountView.class);
            return Optional.ofNullable(view)
                    .map(read -> new AccountBalance(accountId, Money.of(read.currentBalance())));
        } catch (HttpClientErrorException.NotFound absent) {
            return Optional.empty();
        } catch (RestClientException failure) {
            throw new AccountContextUnavailableException("account read did not answer", failure);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param accountId {@inheritDoc}
     * @param paymentAmount {@inheritDoc}
     */
    @Override
    public void applyPayment(String accountId, Money paymentAmount) {
        try {
            // WHY : Assumptions: the amount is sent as the quoted decimal string the shared money type
            //       serialises to rather than as a JSON number, because a JSON number is parsed into a
            //       binary double by most clients and this value is a balance. Transformation rule T3
            //       forbids the money path leaving exact fixed point at any hop, and a request body is a
            //       hop.
            this.client.post()
                    .uri(PATH_ACCOUNT_PAYMENT)
                    .body(Map.of(
                            FIELD_ACCOUNT_ID, accountId,
                            FIELD_AMOUNT, paymentAmount.amount().toPlainString()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException failure) {
            throw new AccountContextUnavailableException("account payment was not applied", failure);
        }
    }

    /**
     * The subset of the account context's cross-reference shape this seam reads.
     *
     * <p>Assumptions: only the two members this context needs are declared, and the deserialiser is
     * configured to ignore the rest. Declaring the whole shape would couple this file to every future
     * addition the account context makes to it.</p>
     *
     * @param accountId the account identifier the entry names, as digit characters
     * @param cardNumber the card number the entry is keyed by, as digit characters
     */
    private record CardXrefView(String accountId, String cardNumber) {

        /**
         * Converts the wire shape into the seam's own record.
         *
         * @return the seam record carrying the same two values, never {@code null}
         */
        private CardXref toCardXref() {
            return new CardXref(this.accountId, this.cardNumber);
        }
    }

    /**
     * The subset of the account context's account shape this seam reads.
     *
     * @param currentBalance the current balance as an exact decimal, deserialised from the quoted string
     *     the account context emits
     */
    private record AccountView(BigDecimal currentBalance) {
    }
}
