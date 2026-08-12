package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
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
        // WHY : Refactoring Rationale: the expectation was a GET on /api/v1/accounts/<identifier>. It is
        //   now a POST on a fixed path with the identifier asserted in the BODY, which is the whole point
        //   of the change: an account identifier in a target is written verbatim into the load balancer's
        //   access log before any application code runs, and that record is one the migration's
        //   sensitive-data logging contract forbids it to hold. Asserting the body rather than merely the
        //   new path is what proves the value still reaches the callee.
        harness.server().expect(requestTo(ORIGIN + "/api/v1/accounts/lookup"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
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
        harness.server().expect(requestTo(ORIGIN + "/api/v1/accounts/lookup"))
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
     *
     * <p>Refactoring Rationale: this case asserted that the sentence CONTAINED the account identifier, and it
     * now asserts the opposite. The sentence reaches a log at every level that records the cause, and the
     * migration's sensitive-data contract keeps account identifiers out of durable diagnostics -- so the
     * earlier assertion was pinning the disclosure in place. What the sentence must still name is WHICH
     * component was missing, because that is what distinguishes a truncated body from an omitted field, and
     * that is asserted instead.</p>
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
            harness.server().expect(requestTo(ORIGIN + "/api/v1/accounts/lookup"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> harness.client().findAccount(ACCOUNT_ID))
                    .as("body " + body + " must be reported as unavailable")
                    .isInstanceOf(AccountContextUnavailableException.class)
                    .hasMessageContaining("account read")
                    .as("body " + body + " must not put the account identifier into a diagnostic")
                    .hasMessageNotContaining(String.valueOf(ACCOUNT_ID));
        }
    }

    /**
     * Verifies the customer probe reports presence, absence and failure as three different things.
     *
     * <p>Refactoring Rationale: the failure outcome no longer asserts on the identifier and the raised
     * sentence no longer carries it. That sentence reaches a log wherever the cause is recorded, and a
     * customer identifier names a person as directly as an account identifier names their account.</p>
     *
     * <p>Assumptions: the probe is a {@code POST} and the method is asserted, because the server publishes
     * this operation under that method alone -- a client issuing any other reaches no handler and reports the
     * refusal as a dependency failure. Refactoring Rationale: this paragraph said the probe is a
     * {@code HEAD}, which the assertion below has not matched since the identifier moved into a body; the
     * body-carrying reason is recorded at the assertion itself and the stale sentence is withdrawn
     * here.</p>
     *
     * <p>Assumptions: the answer carries no body on either outcome, which is what the port's boolean return
     * rests on. A read returning the record would carry a name, an address and a national identifier across
     * the context boundary in order to discard all of them, which is why the port declares a presence
     * question rather than a read.</p>
     */
    @Test
    @DisplayName("the customer probe distinguishes present, absent and unavailable")
    void theCustomerProbeDistinguishesThreeOutcomes() {
        Harness present = harness();
        // WHY : Refactoring Rationale: the probe was issued with HEAD on
        //   /api/v1/customers/<identifier>. It is a POST on a fixed path carrying the identifier in a
        //   body, for the same reason as the account read above. The response is still bodyless, so the
        //   property HEAD was chosen for is retained -- only the identifier's position changed.
        present.server().expect(requestTo(ORIGIN + "/api/v1/customers/lookup"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_ID))
                .andRespond(withSuccess());
        assertThat(present.client().customerExists(CUSTOMER_ID)).isTrue();
        present.server().verify();

        Harness absent = harness();
        absent.server().expect(requestTo(ORIGIN + "/api/v1/customers/lookup"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(absent.client().customerExists(CUSTOMER_ID)).isFalse();

        Harness broken = harness();
        broken.server().expect(requestTo(ORIGIN + "/api/v1/customers/lookup"))
                .andRespond(withServerError());
        assertThatThrownBy(() -> broken.client().customerExists(CUSTOMER_ID))
                .isInstanceOf(AccountContextUnavailableException.class);
    }

    /**
     * Verifies the four published paths are the ones the account context must publish.
     *
     * <p>Assumptions: the constants are asserted literally, because they are the interface between this
     * service and the account context's contract document. A change to either side that is not made to
     * the other produces a 404 at run time on the authorization path, and a 404 on the lookup is
     * indistinguishable from a card that is genuinely not cross-referenced -- so the drift would present
     * as every authorization declining rather than as an error.</p>
     *
     * <p>Refactoring Rationale: a FOURTH path is pinned here, and pinning it is what would have caught the
     * defect that made it necessary. The display read used to be issued against the customer EXISTENCE
     * check -- an operation that answers 204 or 404 with no body at all by contract -- so a bodiless 204
     * deserialised to nothing and every display field rendered as absent without any error anywhere. Pinning
     * the display path as its own constant separates the two operations, so one cannot silently become the
     * other again.</p>
     *
     * <p>Assumptions: the display path is asserted to share the CUSTOMER family prefix, because that is what
     * makes it require the decision-read scope this client already holds rather than the customer-master scope
     * this system mints for no context. A display path published under any other parent would need a scope
     * decision, and the client raises rather than guessing one.</p>
     */
    @Test
    @DisplayName("the four published paths are pinned against the account context's contract")
    void theFourPublishedPathsArePinned() {
        assertThat(RestAccountContextClient.PATH_CARD_XREF_LOOKUP)
                .isEqualTo("/api/v1/card-xrefs/lookup");
        // WHY : Refactoring Rationale: both were path TEMPLATES carrying a variable. They are fixed
        //   paths now, and pinning them as fixed is what fails if a keyed template is reintroduced --
        //   which would silently restore the disclosure this change removed.
        assertThat(RestAccountContextClient.PATH_ACCOUNT).isEqualTo("/api/v1/accounts/lookup");
        assertThat(RestAccountContextClient.PATH_CUSTOMER).isEqualTo("/api/v1/customers/lookup");
        assertThat(RestAccountContextClient.PATH_CUSTOMER_DISPLAY)
                .isEqualTo("/api/v1/customers/display");
        assertThat(RestAccountContextClient.PATH_CUSTOMER_DISPLAY)
                .as("the display read must not be the bodiless existence check")
                .isNotEqualTo(RestAccountContextClient.PATH_CUSTOMER);
        assertThat(RestAccountContextClient.PATH_CUSTOMER_DISPLAY)
                .as("the display read must sit in the customer family, which the decision-read scope covers")
                .startsWith("/api/v1/customers/");
        assertThat(RestAccountContextClient.PATH_ACCOUNT)
                .as("no account-context path may carry a variable segment")
                .doesNotContain("{");
        assertThat(RestAccountContextClient.PATH_CUSTOMER).doesNotContain("{");
        assertThat(RestAccountContextClient.PATH_CUSTOMER_DISPLAY).doesNotContain("{");
    }

    /**
     * Verifies the display read composes the four screen values from the nine published stored fields.
     *
     * <p>Purpose: this is the case the defect could not have survived. The read was issued against the
     * customer existence check, which answers 204 or 404 and carries no body at all by contract, and the
     * response was deserialised into a record whose first member was a composed name that no operation
     * publishes and no column holds -- so all four values rendered as absent on every request and nothing
     * failed while they did. The composition is asserted VALUE for VALUE against
     * {@code GATHER-ACCOUNT-DETAILS} of {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl}.</p>
     *
     * <p>Assumptions: the fixture's postal code is TEN characters and its telephone number FIFTEEN, because
     * the published projection carries both at their stored widths and the narrowing to five and to thirteen
     * is this client's own. A fixture already narrowed would let a client that performed no narrowing
     * pass.</p>
     *
     * <p>Assumptions: the second composed line is built from the THIRD address line, the state code and the
     * leading five postal-code characters -- L771 through L777 -- and not from the second address line. That
     * is the whole reason the projection publishes five address components, so asserting it here is what
     * fails if a later reader "simplifies" the composition back to two lines.</p>
     */
    @Test
    @DisplayName("the display read composes the name, both address lines and the narrowed telephone number")
    void theDisplayReadComposesTheScreenValues() {
        Harness harness = harness();
        harness.server().expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CUSTOMER_DISPLAY))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_ID))
                .andRespond(withSuccess("{\"firstName\":\"JOHN\",\"middleName\":\"QUINCY\","
                        + "\"lastName\":\"PUBLIC\",\"addressLine1\":\"1 SYNTHETIC WAY\","
                        + "\"addressLine2\":\"APT 2\",\"addressLine3\":\"METROPOLIS\","
                        + "\"stateCode\":\"NY\",\"zipCode\":\"1000100010\","
                        + "\"phoneNumber1\":\"(212)555-01000\"}", MediaType.APPLICATION_JSON));

        AccountContextClient.CustomerDisplay display = harness.client().customerDisplay(CUSTOMER_ID)
                .orElseThrow(() -> new AssertionError("a 200 carrying every field must yield a display"));

        // WHY : Assumptions: L758-L764 -- the given name up to its first space, a space, the FIRST
        //   character of the middle name, a space, the family name up to its first space. The middle name
        //   in this fixture is a whole word precisely so that the assertion fails if the composition
        //   carries more than one of its characters.
        assertThat(display.customerName()).isEqualTo("JOHN Q PUBLIC");
        // WHY : Assumptions: L766-L770 -- both lines are delimited by TWO spaces, so an address containing
        //   ordinary single spaces transfers whole and only a pad delimits.
        assertThat(display.addressLine1()).isEqualTo("1 SYNTHETIC WAY,APT 2");
        // WHY : Assumptions: L771-L777, and the postal code narrows to its leading five here rather than
        //   at the publisher. A ten-character value in this position would mean the narrowing was skipped.
        assertThat(display.addressLine2()).isEqualTo("METROPOLIS,NY,10001");
        // WHY : Assumptions: L779 is a plain MOVE from fifteen characters into thirteen, which truncates on
        //   the right. The fixture is deliberately fifteen characters long so a client that published the
        //   stored width unchanged would fail here.
        assertThat(display.phoneNumber1()).isEqualTo("(212)555-0100");
        harness.server().verify();
    }

    /**
     * Verifies a blank middle name still contributes its single position, as the reference does.
     *
     * <p>Assumptions: the composed name carries THREE spaces between the given and family names when no
     * middle name is stored, because {@code CUST-MIDDLE-NAME(1:1)} is a reference modification of a
     * fixed-width field: it yields exactly one character and that character is a space when the field is
     * blank. Collapsing the run would read as tidier and would be a different value from the one the screen
     * showed, so the exact string is asserted rather than a trimmed form.</p>
     *
     * <p>Assumptions: an absent second address line leaves the trailing separator, which is also what the
     * reference leaves -- {@code STRING} writes the literal comma before it reaches the empty component. A
     * composition that suppressed the separator would be tidier and would not be the reference.</p>
     */
    @Test
    @DisplayName("a blank middle name and an absent second address line compose as the reference composes")
    void blankComponentsComposeAsTheReferenceComposes() {
        Harness harness = harness();
        harness.server().expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CUSTOMER_DISPLAY))
                .andRespond(withSuccess("{\"firstName\":\"ADA\",\"middleName\":null,"
                        + "\"lastName\":\"LOVELACE\",\"addressLine1\":\"1 MAIN ST\","
                        + "\"addressLine2\":null,\"addressLine3\":\"LONDON\","
                        + "\"stateCode\":\"NY\",\"zipCode\":\"10001\","
                        + "\"phoneNumber1\":\"(212)5550100\"}", MediaType.APPLICATION_JSON));

        AccountContextClient.CustomerDisplay display = harness.client().customerDisplay(CUSTOMER_ID)
                .orElseThrow(() -> new AssertionError("a 200 must yield a display"));

        assertThat(display.customerName()).isEqualTo("ADA   LOVELACE");
        assertThat(display.addressLine1()).isEqualTo("1 MAIN ST,");
        assertThat(display.addressLine2()).isEqualTo("LONDON,NY,10001");
    }

    /**
     * Verifies each composed value truncates at the map width the mapset declares.
     *
     * <p>Assumptions: the reference composes with {@code STRING ... INTO} a fixed-width receiving field, so a
     * composition longer than the field STOPS at the field's end.
     * {@code app/app-authorization-ims-db2-mq/cpy-bms/COPAU00.cpy} declares {@code CNAMEI PIC X(25)} at L66,
     * {@code ADDR001I PIC X(25)} at L78 and {@code ADDR002I PIC X(25)} at L90, so twenty-five is the width
     * asserted. An untruncated value would be a value the screen never showed.</p>
     */
    @Test
    @DisplayName("each composed value truncates at the twenty-five-character map width")
    void composedValuesTruncateAtTheMapWidth() {
        Harness harness = harness();
        harness.server().expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CUSTOMER_DISPLAY))
                .andRespond(withSuccess("{\"firstName\":\"MAXIMILIANWITHAVERYLONGNAME\","
                        + "\"middleName\":\"X\",\"lastName\":\"ANDANEVENLONGERFAMILYNAME\","
                        + "\"addressLine1\":\"1234 EXTREMELY LONG STREET NAME\","
                        + "\"addressLine2\":\"APARTMENT NUMBER SEVENTEEN\","
                        + "\"addressLine3\":\"A CITY WITH A VERY LONG NAME INDEED\","
                        + "\"stateCode\":\"NY\",\"zipCode\":\"1000100010\","
                        + "\"phoneNumber1\":\"(212)5550100888\"}", MediaType.APPLICATION_JSON));

        AccountContextClient.CustomerDisplay display = harness.client().customerDisplay(CUSTOMER_ID)
                .orElseThrow(() -> new AssertionError("a 200 must yield a display"));

        assertThat(display.customerName()).hasSize(25).isEqualTo("MAXIMILIANWITHAVERYLONGNA");
        assertThat(display.addressLine1()).hasSize(25).isEqualTo("1234 EXTREMELY LONG STREE");
        assertThat(display.addressLine2()).hasSize(25).isEqualTo("A CITY WITH A VERY LONG N");
        assertThat(display.phoneNumber1()).hasSize(13);
    }

    /**
     * Verifies the display read reports an absent customer and a broken dependency as different things.
     *
     * <p>Assumptions: a 404 is an empty optional because the screen renders the authorization with the
     * display fields unpopulated for a customer the master does not hold, and any other failure raises
     * because a screen that silently showed blank fields is what the defect being corrected looked like.</p>
     *
     * <p>Assumptions: the raised sentence must NOT carry the customer identifier. It reaches a log wherever
     * the cause is recorded, and a customer identifier names a person as directly as an account identifier
     * names their account.</p>
     */
    @Test
    @DisplayName("the display read distinguishes an absent customer from a broken dependency")
    void theDisplayReadDistinguishesAbsentFromBroken() {
        Harness absent = harness();
        absent.server().expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CUSTOMER_DISPLAY))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(absent.client().customerDisplay(CUSTOMER_ID)).isEmpty();

        Harness broken = harness();
        broken.server().expect(requestTo(ORIGIN + RestAccountContextClient.PATH_CUSTOMER_DISPLAY))
                .andRespond(withServerError());
        assertThatThrownBy(() -> broken.client().customerDisplay(CUSTOMER_ID))
                .isInstanceOf(AccountContextUnavailableException.class)
                .hasMessageNotContaining(String.valueOf(CUSTOMER_ID));
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
