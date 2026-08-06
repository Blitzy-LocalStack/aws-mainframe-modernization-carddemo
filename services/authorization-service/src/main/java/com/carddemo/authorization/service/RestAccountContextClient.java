package com.carddemo.authorization.service;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
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
 * redelivered instead of declining an authorization the account might well deserve. A 200 with a body
 * becomes the record.</p>
 *
 * <p>Trade-offs: the calls are SYNCHRONOUS and they run inside the deciding transaction, which is
 * holding a row lock by the time the second one is made. That is why both timeouts below are short and
 * why neither is optional: an unbounded read here would hold a database connection and a row lock for as
 * long as the dependency stayed silent, and a serverless cluster's connection budget would be exhausted
 * by a dependency that never actually returned an error. Alternatives Considered: reading the account
 * context asynchronously and resuming the message on its reply. Rejected because a decision cannot be
 * made without these records, so the consumer would have to suspend a transaction mid-flight and resume
 * it elsewhere, which turns one unit of work into a saga and makes partial states observable.</p>
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
     * The path template of the account read, whose single variable is the account identifier.
     */
    public static final String PATH_ACCOUNT = "/api/v1/accounts/{accountId}";

    /**
     * The path template of the customer existence check, whose single variable is the customer
     * identifier.
     */
    public static final String PATH_CUSTOMER = "/api/v1/customers/{customerId}";

    /**
     * The body member the lookup call carries the card number in.
     */
    private static final String LOOKUP_FIELD_CARD_NUM = "cardNumber";

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
     * @param baseUrl the account context's base address, scheme and authority only; must not be
     *     {@code null}
     * @param connectTimeoutMillis how long to wait for the connection to be established
     * @param readTimeoutMillis how long to wait for the response once connected
     */
    public RestAccountContextClient(RestClient.Builder builder,
            @Value("${carddemo.account-context.base-url}") String baseUrl,
            @Value("${carddemo.account-context.connect-timeout-ms:2000}") long connectTimeoutMillis,
            @Value("${carddemo.account-context.read-timeout-ms:3000}") long readTimeoutMillis) {
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
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * Resolves a card number to the account and customer it belongs to.
     *
     * @param cardNum the sixteen-character primary account number the request carried; must not be
     *     {@code null}
     * @return the account and customer the card maps to, or an empty optional when the card is not
     *     cross-referenced
     * @throws AccountContextUnavailableException if the account context could not be asked
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
                // WHY : Trade-offs: an answer that omits either identifier is treated as NOT FOUND
                // rather than as a failure. A cross-reference row exists to carry those two values, so a
                // response without them describes no usable row; declining on the baseline's own
                // not-found reason is the outcome the baseline reaches for a missing row, whereas raising
                // would retry a request the dependency has already answered successfully.
                return Optional.empty();
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
     * @return the account's limits and balance, or an empty optional when the account master holds no
     *     such row
     * @throws AccountContextUnavailableException if the account context could not be asked
     */
    @Override
    public Optional<Account> findAccount(long accountId) {
        try {
            AccountView view = this.client.get()
                    .uri(PATH_ACCOUNT, accountId)
                    .retrieve()
                    .body(AccountView.class);
            if (view == null || view.creditLimit() == null || view.cashCreditLimit() == null
                    || view.currentBalance() == null) {
                // WHY : Trade-offs: a partial account is treated as not found for the same reason a
                // partial cross-reference is. Substituting zero for an absent limit would decline every
                // authorization on the account with the insufficient-funds reason, which reads as a
                // business outcome and hides a broken contract.
                return Optional.empty();
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
     * <p>Assumptions: the request is a HEAD and not a GET, and that is a data-minimisation choice rather
     * than a micro-optimisation. The baseline reads the whole customer record and uses none of its
     * fields, so a GET here would carry a name, an address and a national identifier across a context
     * boundary in order to be discarded; a HEAD answers the only question this context asks. It needs
     * nothing of the account context beyond the GET it already publishes, because the framework routes a
     * HEAD to the matching GET handler and discards the body.</p>
     *
     * @param customerId the nine-digit customer identifier resolved from the cross-reference
     * @return {@code true} when the customer master holds the row, {@code false} when it does not
     * @throws AccountContextUnavailableException if the account context could not be asked
     */
    @Override
    public boolean customerExists(long customerId) {
        try {
            this.client.head()
                    .uri(PATH_CUSTOMER, customerId)
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
}
