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

    /** The account context's account-keyed cross-reference read. */
    public static final String PATH_CARD_XREF_BY_ACCOUNT = "/api/v1/card-xrefs/by-account/{accountId}";

    /** The account context's account master read. */
    public static final String PATH_ACCOUNT = "/api/v1/accounts/{accountId}";

    /** The account context's balance-reducing payment operation. */
    public static final String PATH_ACCOUNT_PAYMENT = "/api/v1/accounts/{accountId}/payments";

    /** The request member the lookup keys on, spelled as the account context publishes it. */
    private static final String FIELD_CARD_NUMBER = "cardNumber";

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
     * earlier-ordered chain requiring {@code SCOPE_internal:account-context.read} and its user chain
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
                    InternalServiceToken.SCOPE_ACCOUNT_CONTEXT_READ));
            return execution.execute(request, body);
        };
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
            CardXrefView view = this.client.get()
                    .uri(PATH_CARD_XREF_BY_ACCOUNT, accountId)
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
            AccountView view = this.client.get()
                    .uri(PATH_ACCOUNT, accountId)
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
                    .uri(PATH_ACCOUNT_PAYMENT, accountId)
                    .body(Map.of(FIELD_AMOUNT, paymentAmount.amount().toPlainString()))
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
