package com.carddemo.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.carddemo.common.security.InternalServiceToken;
import com.carddemo.common.web.CorrelationIdFilter;
import com.carddemo.transaction.service.AccountContextClient.AccountContextUnavailableException;
import com.carddemo.transaction.service.AccountContextClient.CardXref;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Verifies the account-context cross-reference seam this service reads through.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>⚠️ Refactoring Rationale: there was NO test of {@link RestAccountContextClient} anywhere, and the
 * absence had a cost that was measured rather than imagined. Both of that class's conversions rendered the
 * account identifier with {@code String.valueOf}, beside a comment claiming the rendering was performed "so
 * a leading zero survives" -- which is the opposite of what {@code String.valueOf} does. Every one of the
 * fifty accounts shipped in {@code app/data/ASCII/acctdata.txt} is numbered {@code 00000000001} through
 * {@code 00000000050}, so the seam handed a ONE-character identifier to a transaction-add preview and to the
 * ledger key path for all fifty of them, while {@link CardXref}'s own Javadoc declares eleven digit
 * characters. A single assertion on the rendered width would have caught it, and none existed.</p>
 *
 * <p>Assumptions: the class is exercised through the package-visible constructor, because the public one
 * installs its own request factory to apply the two timeouts and installing one replaces the mock transport
 * bound to the builder. That seam applies the SAME base-address policy, so nothing asserted here bypasses a
 * check; only the timeouts are omitted, and they are a property of the transport rather than of the
 * status-code, body-shape and identifier-rendering behaviour under test.</p>
 *
 * <p>⚠️ Trade-offs: this class does NOT assert that an undeclared response member is refused, even though
 * the deployed client is configured strictly. {@link MockRestServiceServer#bindTo(RestClient.Builder)}
 * installs its own default message converters, so the mapper answering these tests is NOT the application's
 * strictly-configured one -- an assertion about strictness made here would report the harness's behaviour
 * rather than the deployment's. The closed shape is asserted where it can be asserted honestly: on the
 * producing side, by the account context's own contract test, which reflects over each published record and
 * requires the schema of the same name to declare exactly those properties.</p>
 *
 * <p>Parameters, return values, exceptions or errors: not applicable at type level; each test method
 * declares its own. The inapplicability is stated rather than passed over, because the Explainability rule
 * forbids a docstring that omits them and a reader must be able to tell a declared inapplicability from an
 * oversight.</p>
 */
@DisplayName("the account-context cross-reference seam")
class RestAccountContextClientTest {

    /**
     * The origin every client in this class is built against.
     *
     * <p>Assumptions: HTTPS, because {@code ApprovedOriginPolicy} refuses anything else on a seam that
     * carries a minted credential, and the package-visible constructor applies that policy unchanged.</p>
     */
    private static final String ORIGIN = "https://account.internal.test";

    /**
     * The card number the card-keyed read is issued for.
     *
     * <p>Assumptions: sixteen digits, which is the width {@code XREF-CARD-NUM PIC X(16)} declares at line 5
     * of {@code app/cpy/CVACT03Y.cpy}.</p>
     */
    private static final String CARD_NUMBER = "6500000000000001";

    /**
     * The eleven-digit form of the FIRST shipped account, which is the shape at issue.
     *
     * <p>Assumptions: this is a real value rather than a convenient one -- {@code app/data/ASCII/acctdata.txt}
     * numbers its fifty accounts {@code 00000000001} upward, so ten leading zeros is the ordinary case on
     * this data and not an edge one.</p>
     */
    private static final String FIRST_ACCOUNT = "00000000001";

    /**
     * A correlation identity of the shape the shared filter admits.
     *
     * <p>Assumptions: the value is within the width and character domain {@code CorrelationIdFilter}
     * accepts on an inbound request, so this case exercises an identity the far side would also accept
     * rather than one that only this side's propagation could carry.</p>
     */
    private static final String CORRELATION_ID = "qa-seam-join";

    /**
     * The minter every client in this class presents its credential from.
     *
     * <p>Assumptions: a REAL minter over fixed key material rather than a stub. The account context refuses
     * an unauthenticated request on every one of these addresses, so a stub would let each test exercise a
     * client shape the deployed system never presents.</p>
     */
    private static final InternalServiceToken MINTER = new InternalServiceToken(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8),
            "carddemo-transaction-service", Clock.systemUTC(), Duration.ofMinutes(1));

    /**
     * Holds a constructed client beside the mock transport that answers it.
     *
     * @param client the client under test
     * @param server the mock transport its expectations are declared on
     */
    private record Harness(RestAccountContextClient client, MockRestServiceServer server) {
    }

    /**
     * Builds a client over a mock transport bound to the approved origin.
     *
     * @return the client and its mock transport, never {@code null}
     */
    private static Harness harness() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Harness(new RestAccountContextClient(builder, MINTER, ORIGIN, ORIGIN), server);
    }

    /**
     * Verifies the card-keyed read renders a numeric identifier at its declared eleven characters.
     *
     * <p>⚠️ Purpose: this is the regression this class was written for. The account context publishes
     * {@code accountId} as a JSON NUMBER because its column is {@code BIGINT}; every shape on this side
     * declares eleven digit CHARACTERS because {@code XREF-ACCT-ID} is {@code PIC 9(11)} and an unsigned
     * display numeric is zero-filled. A rendering that dropped the zero-fill produced {@code "1"} for the
     * first shipped account.</p>
     */
    @Test
    @DisplayName("renders a numeric account identifier as eleven digits on the card-keyed read")
    void theCardKeyedReadZeroFillsTheIdentifier() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.cardNumber").value(CARD_NUMBER))
                .andRespond(withSuccess("{\"accountId\":1,\"customerId\":1}",
                        MediaType.APPLICATION_JSON));

        Optional<CardXref> resolved = harness.client().findCardXrefByCardNumber(CARD_NUMBER);

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().accountId())
                .as("the seam's own contract declares digit characters so a leading zero survives")
                .isEqualTo(FIRST_ACCOUNT)
                .hasSize(11);
        assertThat(resolved.orElseThrow().cardNumber())
                .as("the card number is completed from the request, since the answer carries none")
                .isEqualTo(CARD_NUMBER);
        harness.server().verify();
    }

    /**
     * Verifies the account-keyed read renders its identifier the same way.
     *
     * <p>Assumptions: the two reads are asserted SEPARATELY rather than through one parameterised case,
     * because they deserialise two different records -- the account-keyed answer carries a card number and
     * the card-keyed one deliberately does not -- and both records held the same defect independently.</p>
     */
    @Test
    @DisplayName("renders a numeric account identifier as eleven digits on the account-keyed read")
    void theAccountKeyedReadZeroFillsTheIdentifier() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_BY_ACCOUNT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.accountId").value("00000000050"))
                .andRespond(withSuccess("{\"accountId\":50,\"customerId\":9,\"cardNumber\":\""
                        + CARD_NUMBER + "\"}", MediaType.APPLICATION_JSON));

        Optional<CardXref> resolved = harness.client().findCardXrefByAccountId("00000000050");

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().accountId()).isEqualTo("00000000050").hasSize(11);
        assertThat(resolved.orElseThrow().cardNumber())
                .as("this read's answer carries the card number, so it is taken from the answer")
                .isEqualTo(CARD_NUMBER);
        harness.server().verify();
    }

    /**
     * Verifies an identifier already occupying all eleven digits is carried across unchanged.
     *
     * <p>Assumptions: this case exists so the zero-fill cannot be satisfied by something that always pads
     * or always truncates. An identifier at full width has nothing to pad, and a rendering that widened it
     * further would produce a value no field on either side declares.</p>
     */
    @Test
    @DisplayName("leaves an identifier that already occupies eleven digits alone")
    void aFullWidthIdentifierIsUnchanged() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                .andRespond(withSuccess("{\"accountId\":65000000001,\"customerId\":650000001}",
                        MediaType.APPLICATION_JSON));

        Optional<CardXref> resolved = harness.client().findCardXrefByCardNumber(CARD_NUMBER);

        assertThat(resolved.orElseThrow().accountId()).isEqualTo("65000000001").hasSize(11);
        harness.server().verify();
    }

    /**
     * Verifies every request presents a bearer credential.
     *
     * <p>Assumptions: the presence of the header is asserted and its claims are not, because the claim set
     * is {@code InternalServiceToken}'s own contract and is covered by that type's tests. What this case
     * protects is that the package-visible constructor installs the interceptor -- the one thing it does
     * differently from the public one is omit the request factory, and an omission of the interceptor
     * instead would make every other case here exercise a client the callee would answer 401.</p>
     */
    @Test
    @DisplayName("presents a minted bearer credential on every request")
    void everyRequestCarriesACredential() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Bearer ")))
                .andRespond(withSuccess("{\"accountId\":1,\"customerId\":1}",
                        MediaType.APPLICATION_JSON));

        harness.client().findCardXrefByCardNumber(CARD_NUMBER);

        harness.server().verify();
    }

    /**
     * Verifies a documented absence becomes an empty answer rather than a failure.
     *
     * <p>Assumptions: 404 is the ONE client status treated as absence, matching the reference's own
     * division -- its file status 23 selects the not-found sentence and any other status selects the
     * failed-read sentence, at lines 361 and 366 of {@code app/cbl/COBIL00C.cbl}.</p>
     */
    @Test
    @DisplayName("reports an absent cross-reference row as an empty answer")
    void anAbsentRowIsAnEmptyAnswer() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(harness.client().findCardXrefByCardNumber(CARD_NUMBER)).isEmpty();
        harness.server().verify();
    }

    /**
     * Verifies a server failure is reported as the dependency being unavailable.
     *
     * <p>Assumptions: the raised type is the seam's own, not the framework's, because the caller's next
     * decision depends on which dependency failed rather than on how it failed.</p>
     */
    @Test
    @DisplayName("reports a server failure as the account context being unavailable")
    void aServerFailureIsADependencyFailure() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_BY_ACCOUNT))
                .andRespond(withServerError());

        assertThatThrownBy(() -> harness.client().findCardXrefByAccountId(FIRST_ACCOUNT))
                .isInstanceOf(AccountContextUnavailableException.class);
        harness.server().verify();
    }

    /**
     * Verifies a resolved entry discloses neither identifier when it is rendered for a diagnostic.
     *
     * <p>Assumptions: this is asserted on the value the seam actually produces rather than on a
     * hand-built one, because the rendering override lives on {@link CardXref} and the point of the case is
     * that a value returned from this client is safe to let a framework diagnostic print.</p>
     */
    @Test
    @DisplayName("discloses neither identifier when a resolved entry is rendered")
    void aResolvedEntryDisclosesNeitherIdentifier() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                .andRespond(withSuccess("{\"accountId\":1,\"customerId\":1}",
                        MediaType.APPLICATION_JSON));

        String rendered = harness.client().findCardXrefByCardNumber(CARD_NUMBER).orElseThrow().toString();

        assertThat(rendered)
                .doesNotContain(FIRST_ACCOUNT)
                .doesNotContain(CARD_NUMBER);
        harness.server().verify();
    }

    /**
     * Clears the diagnostic context, so a correlation identity set by one case cannot reach another.
     *
     * <p>Assumptions: the mapped diagnostic context is thread-local and this class's cases run on the same
     * thread, so a value left behind by the propagation case below would be picked up by every later case
     * and by any test in this module that shares the thread. Clearing after each case rather than before
     * each one is deliberate: it also protects tests OUTSIDE this class, which a before-hook could not.</p>
     */
    @AfterEach
    void clearDiagnosticContext() {
        MDC.clear();
    }

    /**
     * Verifies the correlation identity travels with the outbound call.
     *
     * <p>⚠️ Purpose: this is the finding. The only interceptor on this client set a credential, so the
     * account context's own filter found no correlation header, minted an identifier of its own and logged
     * the far side of the call under a value the caller was never given -- while every {@code ApiError} body
     * this migration renders instructs that caller to quote the identifier it WAS given. The hops remained
     * joinable by the W3C trace header, which the transport propagates on its own, but that is
     * diagnosability by a different identity than the one published to the operator.</p>
     *
     * <p>Assumptions: the header NAME asserted is the shared kernel's constant rather than a literal, which
     * is the whole property being protected -- {@code docs/architecture/observability.md} rests the design
     * on every service reading and writing the same header name, so a case naming the string itself would
     * still pass if the two ends came to spell it differently.</p>
     */
    @Test
    @DisplayName("carries this request's correlation identity onto the outbound call")
    void theCorrelationIdentityTravelsWithTheCall() {
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, CORRELATION_ID);
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                .andExpect(header(CorrelationIdFilter.CORRELATION_ID_HEADER, CORRELATION_ID))
                .andRespond(withSuccess("{\"accountId\":1,\"customerId\":1}",
                        MediaType.APPLICATION_JSON));

        harness.client().findCardXrefByCardNumber(CARD_NUMBER);

        harness.server().verify();
    }

    /**
     * Verifies no header is sent when the calling thread carries no correlation identity.
     *
     * <p>Assumptions: an outbound call can legitimately run on a thread no inbound request established --
     * a scheduled or message-driven path -- and this case states what happens there. Sending the header
     * empty would be worse than sending none, because the callee's filter treats a blank value as absent and
     * mints its own regardless: the empty header would only add a field to every log line on the far side
     * that never resolves to anything.</p>
     *
     * <p>Assumptions: the diagnostic context is cleared explicitly at the start rather than assumed empty,
     * because it is thread-local and this class's own propagation case populates it. Relying on the
     * after-hook alone would make this case's outcome depend on execution order.</p>
     */
    @Test
    @DisplayName("sends no correlation header when the thread carries no identity")
    void noCorrelationHeaderIsSentWhenTheThreadCarriesNone() {
        MDC.clear();
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                .andExpect(headerDoesNotExist(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .andRespond(withSuccess("{\"accountId\":1,\"customerId\":1}",
                        MediaType.APPLICATION_JSON));

        harness.client().findCardXrefByCardNumber(CARD_NUMBER);

        harness.server().verify();
    }

    /**
     * Verifies a 2xx whose body binds to nothing is a failed read and not an absence.
     *
     * <p>⚠️ Purpose: this is the second finding on this seam. The bound value used to be wrapped in an
     * optional-of-nullable, so a zero-length body on a 200 produced exactly the empty optional a 404
     * produces and the caller reported a broken callee to the operator as a missing card. The class contract
     * has always drawn the line the other way -- a 404 is absence, every unusable body is a failure -- and
     * the two answers send an operator to different work, one to the data and one to the dependency.</p>
     *
     * <p>Assumptions: the DETAIL is asserted and not only the type, because both the empty body and a
     * transport failure raise the same type here. Asserting the type alone would pass if the empty body
     * merely provoked a converter failure that the existing catch arm re-raised, which is a different branch
     * with a different meaning for a reader of the log.</p>
     */
    @Test
    @DisplayName("reports a 2xx with no body as a failed read, not as an absent row")
    void anEmptyBodyIsAFailedReadAndNotAnAbsence() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> harness.client().findCardXrefByCardNumber(CARD_NUMBER))
                .isInstanceOf(AccountContextUnavailableException.class)
                .hasMessageContaining("answered with no body");
        harness.server().verify();
    }

    /**
     * Verifies a body that binds without the account identifier is a failed read too.
     *
     * <p>Purpose: the same contract clause, one step further along. A body of an empty JSON object, or one
     * naming only the customer, binds to a value that is not {@code null} and then reaches the conversion --
     * where unboxing the absent identifier raised a null-pointer failure that no arm of the read catches, so
     * the shared advice answered it with its generic internal wording instead of this seam's own.</p>
     *
     * <p>Assumptions: the customer member is deliberately not required and this case proves the asymmetry is
     * intended rather than accidental, by omitting the account identifier while supplying the customer -- the
     * exact shape that would pass a naive presence check over all members.</p>
     */
    @Test
    @DisplayName("reports a body missing the account identifier as a failed read")
    void aBodyWithoutTheAccountIdentifierIsAFailedRead() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                .andRespond(withSuccess("{\"customerId\":1}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> harness.client().findCardXrefByCardNumber(CARD_NUMBER))
                .isInstanceOf(AccountContextUnavailableException.class)
                .hasMessageContaining("without an account identifier");
        harness.server().verify();
    }

    /**
     * Verifies the account-keyed read refuses an answer carrying no card number.
     *
     * <p>Purpose: this operation exists to resolve the card number, and that value becomes the ledger row's
     * card key -- so an answer omitting it is unusable even though it names the account the caller already
     * knew. The account-keyed shape therefore requires one member more than its card-keyed sibling, and this
     * case is what states that difference.</p>
     *
     * <p>Assumptions: the read is exercised on the account-keyed address rather than trusting the sibling
     * case above to cover both, because the two reads bind DIFFERENT wire shapes and each has its own
     * usability rule; a single case would leave one of the two shapes unchecked.</p>
     */
    @Test
    @DisplayName("reports an account-keyed answer with no card number as a failed read")
    void anAccountKeyedAnswerWithoutACardNumberIsAFailedRead() {
        Harness harness = harness();
        harness.server()
                .expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_BY_ACCOUNT))
                .andRespond(withSuccess("{\"accountId\":1,\"customerId\":1}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> harness.client().findCardXrefByAccountId(FIRST_ACCOUNT))
                .isInstanceOf(AccountContextUnavailableException.class)
                .hasMessageContaining("without the card number it exists to resolve");
        harness.server().verify();
    }
}
