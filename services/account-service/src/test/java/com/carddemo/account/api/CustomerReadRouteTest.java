package com.carddemo.account.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.account.dto.CustomerResponse;
import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.service.AccountViewService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Pins the wire contract of the two customer read routes: the keyed record and the bounded ascending scan.
 *
 * <p>Purpose. This class asserts what a client can observe, which is a different set of facts from what the
 * sibling {@code com.carddemo.account.service} package asserts about the same two operations. Here: the
 * status each outcome carries, the property names and JSON types of the body, that a rejected query
 * parameter is answered as a per-field array rather than a bare status, and -- the part no other class can
 * reach -- exactly what the handler hands the service after it has classified the position and resolved the
 * absent page size. There: which query the scan chooses, which row it discards and which key it seals.
 *
 * <p>Refactoring Rationale: the position classifier was the specific gap. It is a private method, so its
 * three outcomes -- absent, blank and supplied-but-padded -- are observable only through the value the
 * handler passes on, and no test observed that value. A classifier that forwarded an empty string instead
 * of folding it into the opening page would have failed nothing: the service would have handed the empty
 * string to the sealer, which would refuse it, and a request that merely asked to start at the beginning
 * would have been reported as a malformed one.
 *
 * <p>Assumptions: the routes are driven through a standalone {@code MockMvc} over the real controller with
 * the service substituted, rather than through a context that stands up the web layer. The choice is
 * deliberate on two grounds. It keeps the assertions to this one adapter -- no filter chain, no
 * authorization expression, no auto-configuration decides an outcome here, and route membership and the
 * security expression are already asserted by the sibling contract class from the metadata itself. And it
 * is what every other controller test in this build does, so a reader moving between modules meets one
 * shape. The one collaborator that is NOT substituted is the shared advice: it is registered for real,
 * because the not-found status, the validation code and the per-field array are its behaviour and a
 * substitute would assert this class's expectations back to itself.
 *
 * <p>Assumptions: the clock handed to that advice is FIXED. The advice stamps the problem document, and a
 * clock read would put a value in the body that changes between runs; nothing here asserts the stamp, but
 * fixing it keeps the response byte-stable for anyone who later wants to.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.
 */
class CustomerReadRouteTest {

    /** The record route, composed from the controller's own two constants so a rename cannot diverge. */
    private static final String RECORD_ROUTE =
            CustomerController.BASE_PATH + CustomerController.RECORD_PATH;

    /** The body the record read carries, the identifier having moved out of the path segment. */
    private static final String RECORD_BODY = "{\"customerId\":42}";

    /** The scan route, which is the collection address itself. */
    private static final String SCAN_ROUTE = CustomerController.BASE_PATH;

    /** The customer every case reads, matching the identifier in the record route above. */
    private static final long CUSTOMER_ID = 42L;

    /** The nine-digit rendering the response publishes for that identifier. */
    private static final String CUSTOMER_ID_DIGITS = "000000042";

    /**
     * The sentence the customer master's absence condition carries, which does NOT reach the wire.
     *
     * <p>Assumptions: carried verbatim from the condition at L133 with L134 of
     * {@code app/cbl/COACTVWC.cbl}, and named here so the case below can assert that it is the sentence
     * being raised while the body carries the generic one. Measured, not assumed: the shared advice renders
     * a carried sentence only when it is provably one of this repository's own, and its proof is that the
     * sentence ends in an ellipsis -- this one does not, so the advice substitutes its fixed text. The
     * sentence therefore survives in the service layer and in the log line the advice writes, and a client
     * needing the reference wording takes it from its own message catalogue keyed by the originating
     * copybook.</p>
     */
    private static final String NOT_FOUND_SENTENCE = "Did not find associated customer in master file";

    /** The instant the shared advice stamps every problem document with in this class. */
    private static final String PINNED_INSTANT = "2026-08-09T06:00:00Z";

    /** Key material for the sealer that mints this class's boundary tokens; test-only, not a credential. */
    private static final byte[] CURSOR_KEY =
            "carddemo-account-customer-route-test-cursor-material-not-a-secret"
                    .getBytes(StandardCharsets.UTF_8);

    /**
     * The sealer that mints the two boundary tokens the page fixture carries.
     *
     * <p>Assumptions: a REAL sealer is used for the fixture even though nothing in this class opens a
     * token, and it is required rather than tidy. Measured, not assumed: {@code PageResponse.ofRows}
     * refuses a boundary that is not a sealed token -- "must be a token sealed by CursorToken, not a raw
     * keyset cursor" -- because publishing a raw key column would disclose the key space and let a client
     * position a scan itself. A literal such as "opening-position" therefore cannot be used, and the
     * envelope's own guard is what says so.</p>
     *
     * <p>Assumptions: the binding these tokens are sealed under is this class's own and NOT the scan's,
     * because the envelope checks the SHAPE of a boundary and never opens it. Reusing the scan's binding
     * here would suggest this class asserts something about which query sealed them, which it does not --
     * that is asserted where the sealing happens, in the sibling service package.</p>
     */
    private static final CursorToken FIXTURE_SEALER =
            new CursorToken(CURSOR_KEY, Duration.ofHours(1));

    /** The leading boundary the page fixture publishes, sealed so the envelope accepts it. */
    private static final String OPENING_POSITION =
            FIXTURE_SEALER.seal("account.customers.route-test", "1");

    /** The trailing boundary the page fixture publishes, sealed so the envelope accepts it. */
    private static final String TRAILING_POSITION =
            FIXTURE_SEALER.seal("account.customers.route-test", "5");

    /** A page width no request in this class exceeds, used where a case supplies one explicitly. */
    private static final int SUPPLIED_PAGE_SIZE = 7;

    /** The read path, substituted so a case controls the answer and can observe what it was asked. */
    private AccountViewService reads;

    /** The web layer under test. */
    private MockMvc mockMvc;

    /**
     * Builds the adapter under test with a substituted service and the real shared advice.
     */
    // WHY : Assumptions: no message converter is registered explicitly, unlike the sibling controller
    //   tests in the authorization and transaction contexts. Those register one to install the money
    //   module, because a body of theirs carries an amount that must serialise as a JSON string. The
    //   customer contract carries no money at all -- every one of its eighteen components is text -- so
    //   registering a converter here would state a dependency this contract does not have, and the
    //   builder's own default converter is what a deployed context would use for these bodies anyway.
    @BeforeEach
    void setUp() {
        this.reads = mock(AccountViewService.class);
        this.mockMvc = MockMvcBuilders.standaloneSetup(new CustomerController(this.reads))
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(Instant.parse(PINNED_INSTANT), ZoneOffset.UTC)))
                .build();
    }

    /**
     * Verifies the record route answers 200 with both stored identifiers masked and every value as text.
     *
     * <p>Assumptions: the masking is asserted ON THE WIRE and not only on the returned object, because a
     * body is where a disclosure would actually occur -- a projection that masked correctly and a
     * serialiser that published a second, unmasked property would satisfy an object-level assertion and
     * still leak. The two identifiers are asserted separately because the reference declares them at
     * different widths and types, {@code PIC 9(09)} at L17 of {@code app/cpy/CVCUS01Y.cpy} against
     * {@code PIC X(20)} at L18, and only their disclosure treatment coincides.</p>
     *
     * <p>Assumptions: the raw body is additionally asserted to carry the identifier QUOTED. Every numeric
     * field of this record travels as digits-only text, and a JSON path assertion comparing against a
     * string would also pass for a numeric member that happened to render the same digits -- while a
     * numeric member would drop the leading zeros the declared width requires and would be parsed into a
     * double by many clients. The quoted form is the only assertion that distinguishes them.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the record route answers 200 with masked identifiers and digits carried as text")
    void theRecordRouteAnswersOkWithMaskedIdentifiers() throws Exception {
        when(this.reads.readCustomer(CUSTOMER_ID)).thenReturn(customer());

        this.mockMvc.perform(post(RECORD_ROUTE)
                        .contentType("application/json")
                        .content(RECORD_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_ID_DIGITS))
                .andExpect(jsonPath("$.ssnMasked").value(CustomerMapper.IDENTIFIER_REDACTED))
                .andExpect(jsonPath("$.governmentIssuedIdMasked")
                        .value(CustomerMapper.IDENTIFIER_REDACTED))
                .andExpect(jsonPath("$.zipCode").value("10001-0000"))
                .andExpect(jsonPath("$.ficoCreditScore").value("789"))
                .andExpect(content().string(containsString("\"customerId\":\"" + CUSTOMER_ID_DIGITS
                        + "\"")))
                .andExpect(content().string(containsString("\"ficoCreditScore\":\"789\"")));
    }

    /**
     * Verifies an absent customer is answered as 404 carrying the reference sentence.
     *
     * <p>Assumptions: the code and the sentence are both asserted. The status alone would also be produced
     * by a route that did not exist, and the sentence alone would not show that the document is the
     * project's problem shape rather than the container's default error page.</p>
     *
     * <p>Assumptions: the sentence asserted is the REFERENCE wording the service raises, which the advice
     * passes through. Refactoring Rationale: this paragraph said the opposite -- that the advice admits a
     * carried sentence only when it ends in an ellipsis, so the customer-absence condition at L133 with
     * L134 of {@code app/cbl/COACTVWC.cbl} was withheld and the generic sentence answered instead. The
     * advice now admits a second shape, reference PROSE, precisely so that a baseline sentence carrying no
     * ellipsis is still carried across character for character as transformation rule T8 requires. The
     * disclosure control is unchanged in substance: the prose shape bounds a digit run more tightly than
     * the terminator shape does, so no identifier this system carries can pass it.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the record route answers 404 with the reference sentence when the row is absent")
    void theRecordRouteAnswersNotFoundWhenTheRowIsAbsent() throws Exception {
        when(this.reads.readCustomer(CUSTOMER_ID))
                .thenThrow(new NoSuchElementException(NOT_FOUND_SENTENCE));

        this.mockMvc.perform(post(RECORD_ROUTE)
                        .contentType("application/json")
                        .content(RECORD_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND))
                .andExpect(jsonPath("$.message").value(NOT_FOUND_SENTENCE));
    }

    /**
     * Verifies the scan route publishes the page envelope, its boundaries and its further-rows answer.
     *
     * <p>Assumptions: the two boundary positions are asserted PRESENT and opaque rather than compared
     * against a value, because what they contain is the sealer's business and is asserted where the sealing
     * happens. What matters here is that both reach the client under the names the published contract
     * gives them, since a client resumes a scan by echoing exactly one of them back.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the scan route answers 200 with the page envelope and both boundaries")
    void theScanRouteAnswersOkWithThePageEnvelope() throws Exception {
        when(this.reads.listCustomers(isNull(), anyInt())).thenReturn(
                PageResponse.ofRows(List.of(customer()), OPENING_POSITION, TRAILING_POSITION, true,
                        false));

        this.mockMvc.perform(get(SCAN_ROUTE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].customerId").value(CUSTOMER_ID_DIGITS))
                .andExpect(jsonPath("$.items[0].ssnMasked").value(CustomerMapper.IDENTIFIER_REDACTED))
                .andExpect(jsonPath("$.firstKey").value(OPENING_POSITION))
                .andExpect(jsonPath("$.lastKey").value(TRAILING_POSITION))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    /**
     * Verifies an absent page size resolves to the scan's own default rather than to a second literal.
     *
     * <p>Assumptions: the value handed to the service is asserted against the service's OWN constant, which
     * is the property the handler was written to hold: the ceiling and the default are one decision, and a
     * request-parameter default would be a second copy of it that could be changed without the bound
     * moving with it. Comparing against a literal twenty here would defeat that.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an absent page size resolves to the scan's declared default")
    void anAbsentPageSizeResolvesToTheScanDefault() throws Exception {
        when(this.reads.listCustomers(isNull(), anyInt())).thenReturn(PageResponse.empty());

        this.mockMvc.perform(get(SCAN_ROUTE)).andExpect(status().isOk());

        verify(this.reads).listCustomers(null, AccountViewService.CUSTOMER_SCAN_DEFAULT_PAGE_SIZE);
    }

    /**
     * Verifies a supplied page size inside the declared bounds travels through unchanged.
     *
     * <p>Assumptions: this is the companion to the case above. Without it, a handler that ignored the
     * parameter and always passed the default would satisfy the default case and fail no assertion.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a supplied page size inside the bounds is passed through unchanged")
    void aSuppliedPageSizeIsPassedThrough() throws Exception {
        when(this.reads.listCustomers(isNull(), anyInt())).thenReturn(PageResponse.empty());

        this.mockMvc.perform(get(SCAN_ROUTE).param("size", String.valueOf(SUPPLIED_PAGE_SIZE)))
                .andExpect(status().isOk());

        verify(this.reads).listCustomers(null, SUPPLIED_PAGE_SIZE);
    }

    /**
     * Verifies an absent, an empty and an all-whitespace position all read the opening page.
     *
     * <p>Assumptions: the three are asserted as ONE outcome and counted, because that is precisely the
     * classifier's contract -- a screen field reaches a COBOL program as characters of declared width
     * rather than as an absence, so the reference cannot distinguish them and neither may this route. The
     * count is asserted rather than merely the absence of a failure: an implementation that forwarded the
     * empty string would pass an assertion phrased as "no exception" while handing the sealer a value it
     * must refuse.</p>
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @DisplayName("an absent, empty or whitespace position all read the opening page")
    void anAbsentEmptyOrWhitespacePositionReadsTheOpeningPage() throws Exception {
        when(this.reads.listCustomers(isNull(), anyInt())).thenReturn(PageResponse.empty());

        this.mockMvc.perform(get(SCAN_ROUTE)).andExpect(status().isOk());
        this.mockMvc.perform(get(SCAN_ROUTE).param("cursor", "")).andExpect(status().isOk());
        this.mockMvc.perform(get(SCAN_ROUTE).param("cursor", "   ")).andExpect(status().isOk());

        verify(this.reads, times(3))
                .listCustomers(null, AccountViewService.CUSTOMER_SCAN_DEFAULT_PAGE_SIZE);
        verify(this.reads, never()).listCustomers(anyString(), anyInt());
    }

    /**
     * Verifies a padded position is trimmed before it travels on.
     *
     * <p>Assumptions: a position is carried in a query string, and a client that reflects a
     * whitespace-padded copy of what it received would otherwise present a token the sealer cannot verify
     * -- so the trim is what keeps a well-behaved client from being told its own position is malformed.
     * The assertion is on the trimmed value specifically, since a handler that passed the padded string
     * through would fail only later, inside the sealer, and would report a client error.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a padded position is trimmed before it reaches the scan")
    void aPaddedPositionIsTrimmed() throws Exception {
        when(this.reads.listCustomers(eq("sealed-position"), anyInt()))
                .thenReturn(PageResponse.empty());

        this.mockMvc.perform(get(SCAN_ROUTE).param("cursor", "  sealed-position  "))
                .andExpect(status().isOk());

        verify(this.reads)
                .listCustomers("sealed-position", AccountViewService.CUSTOMER_SCAN_DEFAULT_PAGE_SIZE);
    }

    /**
     * Verifies a position longer than the sealer's ceiling is refused at the edge, keyed to the parameter.
     *
     * <p>Assumptions: the refusal is asserted to happen BEFORE the service is reached, which is why the
     * length bound is declared on the parameter at all -- an oversized value carried inward would be
     * refused by the sealer instead, one layer further in, having already been copied and logged there.
     * The entry is keyed to {@code cursor} because a client can only correct a control it can
     * identify.</p>
     *
     * <p>Assumptions: the state on the entry is asserted as the not-acceptable one rather than the blank
     * one. The value IS supplied here, and the distinction is the reference's own: {@code app/cbl/
     * COACTUPC.cbl} rejects a blank field and a wrongly shaped field through different conditions, so
     * collapsing the two would lose a difference the baseline maintains.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a position longer than the ceiling is refused as 400 keyed to the cursor")
    void anOversizedPositionIsRefused() throws Exception {
        String oversized = "p".repeat(CursorToken.MAX_TOKEN_LENGTH + 1);

        this.mockMvc.perform(get(SCAN_ROUTE).param("cursor", oversized))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cursor"))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.NOT_OK.name()));

        verify(this.reads, never()).listCustomers(anyString(), anyInt());
        verify(this.reads, never()).listCustomers(isNull(), anyInt());
    }

    /**
     * Verifies a page size below the floor and above the ceiling are both refused at the edge.
     *
     * <p>Assumptions: both ends are asserted, because a bound declared at one end only would let the other
     * through and the service would then clamp it silently -- the caller would receive a page of a size it
     * did not ask for and no indication that its request had been altered. Refusing at the edge and
     * clamping inside are not in conflict: the edge has a client to inform and the service does not.</p>
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @DisplayName("a page size below the floor or above the ceiling is refused as 400 keyed to size")
    void aPageSizeOutsideTheBoundsIsRefused() throws Exception {
        this.mockMvc.perform(get(SCAN_ROUTE).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));

        this.mockMvc.perform(get(SCAN_ROUTE).param("size",
                        String.valueOf(AccountViewService.CUSTOMER_SCAN_MAX_PAGE_SIZE + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));

        verify(this.reads, never()).listCustomers(anyString(), anyInt());
        verify(this.reads, never()).listCustomers(isNull(), anyInt());
    }

    /**
     * Builds the projected customer every case answers with.
     *
     * <p>Assumptions: this is the PROJECTED shape rather than a stored row, because the service is
     * substituted here and the projection belongs to the layer beneath. Both protected components carry the
     * masking marker for the same reason: this class asserts that the marker survives serialisation under
     * the published property names, not that the projection chose it.</p>
     *
     * @return the published customer contract, never {@code null}
     */
    private static CustomerResponse customer() {
        return new CustomerResponse(CUSTOMER_ID_DIGITS, "FIRST", "M", "LAST", "1 FIXTURE WAY", null,
                "FIXTURE CITY", "NY", "USA", "10001-0000", "(212)555-0100  ", null,
                CustomerMapper.IDENTIFIER_REDACTED, CustomerMapper.IDENTIFIER_REDACTED, "1980-01-15",
                "0000000001", "Y", "789");
    }
}
