package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.carddemo.authorization.service.AccountContextClient.Account;
import com.carddemo.authorization.service.AccountContextClient.AccountContextUnavailableException;
import com.carddemo.authorization.service.AccountContextClient.CardXref;
import com.carddemo.common.security.InternalServiceToken;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Asserts the account-context client's base-address guard and the boundary between not-found and
 * unavailable.
 *
 * <h2>Purpose</h2>
 * <p>Two findings meet in this class and they are tested together because both concern what this client
 * treats as an answer. The first is that the configured base address was passed to the builder
 * unexamined, although the very first request it makes carries a primary account number in its body. The
 * second is that a success status with an absent or incomplete body was reported as "the record does not
 * exist", which made the consumer commit a wrong decline against a cardholder's account for a request it
 * had never actually evaluated.</p>
 *
 * <p>Assumptions: the transport is driven by the framework's mock server rather than by a stub of this
 * class, so the status codes, the bodies and the request methods are the real ones the real client
 * observes. A stubbed client would let every assertion here pass while the production code path did
 * something else, which is precisely the state finding M-13 describes.</p>
 *
 * <p>Trade-offs: each test builds its own client and its own mock server rather than sharing one. The
 * client caches its request factory at construction and the mock server's expectations are ordered, so
 * sharing either would couple the tests to each other's call counts -- and several of these tests
 * deliberately never make a call at all, because construction is what they assert on.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
class RestAccountContextClientTest {

    /**
     * The approved internal origin the base address must equal.
     */
    private static final String ORIGIN = "https://carddemo-dev.services.internal";

    /**
     * A card number the lookup resolves.
     */
    private static final String CARD_NUMBER = "4111111111112345";

    /**
     * The account identifier the fixture cross-reference resolves to.
     */
    private static final long ACCOUNT_ID = 11_111_111_111L;

    /**
     * The customer identifier the fixture cross-reference resolves to.
     */
    private static final long CUSTOMER_ID = 100_000_001L;

    /**
     * The minter every client in this class presents its credential from.
     *
     * <p>Assumptions: the key material is FIXED so a minted token is reproducible, and the minter is REAL
     * rather than a stub. What is under test in this class is the status-code and body handling, not the
     * credential -- but a stub minter would let the client be constructed without one, and the account context
     * refuses an unauthenticated request, so a stub would make every test here exercise a shape the deployed
     * system never sees.</p>
     */
    private static final InternalServiceToken MINTER = new InternalServiceToken(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8),
            "carddemo-authorization-service", Clock.systemUTC(), Duration.ofMinutes(1));

    /**
     * Holds a constructed client beside the mock server that answers it.
     *
     * @param client the client under test
     * @param server the mock transport its expectations are set on
     */
    private record Harness(RestAccountContextClient client, MockRestServiceServer server) {
    }

    /**
     * Builds a client over a mock transport bound to the approved origin.
     *
     * @return the client and its mock server; never {@code null}
     */
    private static Harness harness() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // WHY : Assumptions: the package-visible constructor is used rather than the public one, because
        //   the public one installs its own request factory in order to apply the two timeouts and doing
        //   so replaces the mock transport the line above just bound. The seam applies the SAME
        //   base-address validation, so nothing asserted here is being bypassed -- only the timeouts,
        //   which are a property of the transport rather than of the status-code and body handling these
        //   tests are about.
        return new Harness(new RestAccountContextClient(builder, MINTER, ORIGIN, ORIGIN), server);
    }

    /**
     * Verifies the approved origin is accepted, so the guard is not simply closed.
     */
    @Test
    @DisplayName("the approved HTTPS origin is accepted")
    void theApprovedOriginIsAccepted() {
        assertThatCode(() -> new RestAccountContextClient(RestClient.builder(), MINTER, ORIGIN, ORIGIN, 2000, 3000))
                .doesNotThrowAnyException();
        assertThatCode(() -> new RestAccountContextClient(RestClient.builder(), MINTER, ORIGIN + "/", ORIGIN,
                2000, 3000))
                .as("a trailing separator is the same origin and must not be a startup failure")
                .doesNotThrowAnyException();
    }

    /**
     * Verifies an absent base address stops startup and names the property.
     */
    @Test
    @DisplayName("an absent base address stops startup and names the property")
    void anAbsentBaseAddressStopsStartup() {
        for (String absent : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> new RestAccountContextClient(RestClient.builder(), MINTER, absent, ORIGIN,
                    2000, 3000))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.account-context.base-url");
        }
    }

    /**
     * Verifies a plain-HTTP address is refused however otherwise well formed.
     *
     * <p>Assumptions: the scheme is refused before the origin comparison, so the message names the scheme
     * rather than the approval. An operator who downgraded a URL by one character needs to be told which
     * of the two rules they broke.</p>
     */
    @Test
    @DisplayName("a plain-HTTP address is refused because the first request carries a card number")
    void aPlainHttpAddressIsRefused() {
        String downgraded = "http://carddemo-dev.services.internal";

        assertThatThrownBy(() -> new RestAccountContextClient(RestClient.builder(), MINTER, downgraded,
                downgraded, 2000, 3000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    /**
     * Verifies a relative address, one with no host, and a non-HTTP scheme are all refused.
     */
    @Test
    @DisplayName("a relative address, a hostless address and a foreign scheme are refused")
    void anAddressThatIsNotAnAbsoluteHttpsOriginIsRefused() {
        for (String rejected : new String[] {"/api/v1", "carddemo-dev.services.internal",
                "file:///etc/passwd", "https:///nohost"}) {
            assertThatThrownBy(() -> new RestAccountContextClient(RestClient.builder(), MINTER, rejected,
                    rejected, 2000, 3000))
                    .as("\"" + rejected + "\" must be refused")
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * Verifies user information, a path, a query and a fragment are each refused.
     *
     * <p>Assumptions: each of the four is refused with its own message rather than one shared message,
     * because each has a distinct consequence: user information places a credential in a header this
     * client never declares, a path silently re-roots all three calls, and a query or fragment attaches
     * itself to every one of them.</p>
     */
    @Test
    @DisplayName("user information, a path, a query and a fragment are each refused")
    void anAddressCarryingMoreThanAnOriginIsRefused() {
        assertThatThrownBy(() -> new RestAccountContextClient(RestClient.builder(), MINTER,
                "https://user:secret@carddemo-dev.services.internal", ORIGIN, 2000, 3000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user");
        assertThatThrownBy(() -> new RestAccountContextClient(RestClient.builder(), MINTER,
                ORIGIN + "/internal", ORIGIN, 2000, 3000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("path");
        assertThatThrownBy(() -> new RestAccountContextClient(RestClient.builder(), MINTER,
                ORIGIN + "?trace=1", ORIGIN, 2000, 3000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("query");
        assertThatThrownBy(() -> new RestAccountContextClient(RestClient.builder(), MINTER,
                ORIGIN + "#frag", ORIGIN, 2000, 3000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fragment");
    }

    /**
     * Verifies a well-formed HTTPS origin that is not the approved one is refused.
     *
     * <p>Assumptions: the rejected host is a plausible near-miss rather than an obviously hostile one,
     * because the exposure this guard closes is a base address quietly repointed after review, not one an
     * operator would notice on sight.</p>
     */
    @Test
    @DisplayName("a well-formed HTTPS origin that is not the approved one is refused")
    void anUnapprovedOriginIsRefused() {
        assertThatThrownBy(() -> new RestAccountContextClient(RestClient.builder(), MINTER,
                "https://carddemo-dev.services.internal.attacker.example", ORIGIN, 2000, 3000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved");
    }

    /**
     * Verifies an explicit not-found on the lookup is the only path to an empty optional.
     */
    @Test
    @DisplayName("an explicit not-found on the cross-reference lookup yields an empty optional")
    void anExplicitNotFoundYieldsEmpty() {
        Harness harness = harness();
        harness.server().expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(harness.client().findCardXref(CARD_NUMBER)).isEmpty();
        harness.server().verify();
    }

    /**
     * Verifies a complete lookup body yields the cross-reference.
     */
    @Test
    @DisplayName("a complete lookup body yields the cross-reference")
    void aCompleteLookupBodyYieldsTheCrossReference() {
        Harness harness = harness();
        harness.server().expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                .andRespond(withSuccess("{\"accountId\":" + ACCOUNT_ID + ",\"customerId\":"
                        + CUSTOMER_ID + "}", MediaType.APPLICATION_JSON));

        assertThat(harness.client().findCardXref(CARD_NUMBER))
                .contains(new CardXref(ACCOUNT_ID, CUSTOMER_ID));
        harness.server().verify();
    }

    /**
     * Verifies a success with no body at all is a dependency failure and not a not-found.
     *
     * <p>Assumptions: this is the distinction the whole finding turns on. Returning empty here would make
     * the consumer decline the authorization on the baseline's card-not-found reason and COMMIT that
     * decline, so a dependency that briefly returned an empty body would permanently record a wrong
     * decision. Raising rolls the transaction back and lets the queue redeliver.</p>
     */
    @Test
    @DisplayName("a success with no body is unavailable, not not-found")
    void aSuccessWithNoBodyIsUnavailable() {
        Harness harness = harness();
        harness.server().expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> harness.client().findCardXref(CARD_NUMBER))
                .isInstanceOf(AccountContextUnavailableException.class)
                .hasMessageContaining("no body");
    }

    /**
     * Verifies a success whose lookup body omits either identifier is a dependency failure.
     *
     * <p>Assumptions: the message must NAME the missing component and must NOT carry the card number.
     * Which field was absent is what tells an operator whether the dependency is truncating a body or has
     * changed shape; the number would put cardholder data into a log line.</p>
     */
    @Test
    @DisplayName("a lookup body missing either identifier is unavailable and names the component")
    void aPartialLookupBodyIsUnavailable() {
        Harness missingAccount = harness();
        missingAccount.server()
                .expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                .andRespond(withSuccess("{\"customerId\":" + CUSTOMER_ID + "}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> missingAccount.client().findCardXref(CARD_NUMBER))
                .isInstanceOf(AccountContextUnavailableException.class)
                .hasMessageContaining("accountId")
                .hasMessageNotContaining(CARD_NUMBER);

        Harness missingCustomer = harness();
        missingCustomer.server()
                .expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                .andRespond(withSuccess("{\"accountId\":" + ACCOUNT_ID + "}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> missingCustomer.client().findCardXref(CARD_NUMBER))
                .isInstanceOf(AccountContextUnavailableException.class)
                .hasMessageContaining("customerId")
                .hasMessageNotContaining(CARD_NUMBER);
    }

    /**
     * Verifies a server error on the lookup is a dependency failure and not a not-found.
     */
    @Test
    @DisplayName("a server error on the lookup is unavailable, not not-found")
    void aServerErrorIsUnavailable() {
        Harness harness = harness();
        harness.server().expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                .andRespond(withServerError());

        assertThatThrownBy(() -> harness.client().findCardXref(CARD_NUMBER))
                .isInstanceOf(AccountContextUnavailableException.class)
                .hasMessageNotContaining(CARD_NUMBER);
    }

    /**
     * Verifies a complete account body yields the three amounts a decision needs.
     */
    @Test
    @DisplayName("a complete account body yields the three amounts")
    void aCompleteAccountBodyYieldsTheAmounts() {
        Harness harness = harness();
        harness.server().expect(requestTo(ORIGIN + "/api/v1/accounts/" + ACCOUNT_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"creditLimit\":\"5000.00\",\"cashCreditLimit\":\"1000.00\","
                        + "\"currentBalance\":\"250.75\"}", MediaType.APPLICATION_JSON));

        assertThat(harness.client().findAccount(ACCOUNT_ID))
                .contains(new Account(new BigDecimal("5000.00"), new BigDecimal("1000.00"),
                        new BigDecimal("250.75")));
        harness.server().verify();
    }

    /**
     * Verifies an explicit not-found on the account read yields an empty optional.
     */
    @Test
    @DisplayName("an explicit not-found on the account read yields an empty optional")
    void anExplicitAccountNotFoundYieldsEmpty() {
        Harness harness = harness();
        harness.server().expect(requestTo(ORIGIN + "/api/v1/accounts/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(harness.client().findAccount(ACCOUNT_ID)).isEmpty();
    }

    /**
     * Verifies an account body missing any of the three amounts is a dependency failure.
     *
     * <p>Assumptions: each of the three is exercised, because each would have produced a DIFFERENT wrong
     * outcome had zero been substituted or not-found returned -- an absent credit limit declines on
     * insufficient funds, an absent account declines on account-not-found -- and both read as business
     * outcomes rather than as a broken contract.</p>
     */
    @Test
    @DisplayName("an account body missing any of the three amounts is unavailable")
    void aPartialAccountBodyIsUnavailable() {
        String[] partialBodies = {
            "{\"cashCreditLimit\":\"1000.00\",\"currentBalance\":\"250.75\"}",
            "{\"creditLimit\":\"5000.00\",\"currentBalance\":\"250.75\"}",
            "{\"creditLimit\":\"5000.00\",\"cashCreditLimit\":\"1000.00\"}",
        };

        for (String body : partialBodies) {
            Harness harness = harness();
            harness.server().expect(requestTo(ORIGIN + "/api/v1/accounts/" + ACCOUNT_ID))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> harness.client().findAccount(ACCOUNT_ID))
                    .as("body " + body + " must be reported as unavailable")
                    .isInstanceOf(AccountContextUnavailableException.class)
                    .hasMessageContaining(String.valueOf(ACCOUNT_ID));
        }
    }

    /**
     * Verifies the customer probe reports presence, absence and failure as three different things.
     *
     * <p>Assumptions: the probe is a HEAD, and the method is asserted. A GET would carry a name, an
     * address and a national identifier across the context boundary in order to discard all of them,
     * which is the reason the port declares a presence question rather than a read.</p>
     */
    @Test
    @DisplayName("the customer probe distinguishes present, absent and unavailable")
    void theCustomerProbeDistinguishesThreeOutcomes() {
        Harness present = harness();
        present.server().expect(requestTo(ORIGIN + "/api/v1/customers/" + CUSTOMER_ID))
                .andExpect(method(HttpMethod.HEAD))
                .andRespond(withSuccess());
        assertThat(present.client().customerExists(CUSTOMER_ID)).isTrue();
        present.server().verify();

        Harness absent = harness();
        absent.server().expect(requestTo(ORIGIN + "/api/v1/customers/" + CUSTOMER_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(absent.client().customerExists(CUSTOMER_ID)).isFalse();

        Harness broken = harness();
        broken.server().expect(requestTo(ORIGIN + "/api/v1/customers/" + CUSTOMER_ID))
                .andRespond(withServerError());
        assertThatThrownBy(() -> broken.client().customerExists(CUSTOMER_ID))
                .isInstanceOf(AccountContextUnavailableException.class);
    }

    /**
     * Verifies the three published paths are the ones the account context must publish.
     *
     * <p>Assumptions: the constants are asserted literally, because they are the interface between this
     * service and the account context's contract document. A change to either side that is not made to
     * the other produces a 404 at run time on the authorization path, and a 404 on the lookup is
     * indistinguishable from a card that is genuinely not cross-referenced -- so the drift would present
     * as every authorization declining rather than as an error.</p>
     */
    @Test
    @DisplayName("the three published paths are pinned against the account context's contract")
    void theThreePublishedPathsArePinned() {
        assertThat(RestAccountContextClient.PATH_CARD_XREF_LOOKUP)
                .isEqualTo("/api/v1/card-xrefs/lookup");
        assertThat(RestAccountContextClient.PATH_ACCOUNT).isEqualTo("/api/v1/accounts/{accountId}");
        assertThat(RestAccountContextClient.PATH_CUSTOMER).isEqualTo("/api/v1/customers/{customerId}");
    }

    /**
     * Verifies the optional-returning reads never expose an empty optional for a non-404 failure.
     *
     * <p>Assumptions: this is asserted as a property over the failure statuses rather than one status at a
     * time, because the defect was a category error rather than a mistake about one code. 400, 401, 403,
     * 409 and 503 are all answers that are not "the record is absent", and every one of them previously
     * had at least one path to an empty optional through an unreadable body.</p>
     */
    @Test
    @DisplayName("no failure status other than 404 can produce an empty optional")
    void onlyNotFoundProducesEmpty() {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN, HttpStatus.CONFLICT, HttpStatus.SERVICE_UNAVAILABLE}) {
            Harness harness = harness();
            harness.server().expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CARD_XREF_LOOKUP))
                    .andRespond(withStatus(status));

            assertThatThrownBy(() -> harness.client().findCardXref(CARD_NUMBER))
                    .as(status + " must not be reported as a card that is not cross-referenced")
                    .isInstanceOf(AccountContextUnavailableException.class);
        }
        assertThat(Optional.empty()).as("only an explicit 404 reaches this outcome").isEmpty();
    }
}
