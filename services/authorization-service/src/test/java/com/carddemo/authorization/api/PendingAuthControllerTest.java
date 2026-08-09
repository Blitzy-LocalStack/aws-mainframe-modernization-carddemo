package com.carddemo.authorization.api;


import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.dto.PendingAuthListView;
import com.carddemo.authorization.mapper.PendingAuthViewMapper;
import com.carddemo.authorization.service.PendingAuthDetailService;
import com.carddemo.authorization.service.PendingAuthSummaryService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the HTTP boundary of the two read operations: their routes, the two verbatim account-scope
 * refusals, and the money rendering the shared codec applies.
 *
 * <p>Assumptions: the services are doubles and the mapper is REAL, so the body this class asserts is the
 * body the production adapter produces. Mocking the mapper would leave every rendered value unasserted,
 * including the masking that is the whole reason a controller here never handles an entity.</p>
 *
 * <p>Assumptions: every citation is relative to {@code app/app-authorization-ims-db2-mq}, which is reference
 * material this migration reads and never modifies.</p>
 */
class PendingAuthControllerTest {

    /**
     * The clock the screen representation's rendered instant is read from.
     *
     * <p>Assumptions: it is FIXED, so the instant the screen response carries is an assertable value rather
     * than whatever the run happened to observe. The controller reads the instant server-side by design --
     * it is evidence of when the response was produced -- so a test of that response needs the instant to
     * be pinned.</p>
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-05T10:45:35Z"), ZoneOffset.UTC);

    /**
     * The authenticated principal the controller reads the sealed values' binding subject from.
     *
     * <p>Assumptions: a standalone {@code MockMvc} runs no security chain, so the principal is supplied on
     * the request builder and its name is what the handler forwards to the service.</p>
     */
    private static final String SUBJECT = "authorization-operator";

    /**
     * The principal instance every request in this class is performed as.
     */
    private static final Principal PRINCIPAL = () -> SUBJECT;

    /** The collection route the contract publishes. */
    private static final String LIST_ROUTE = "/api/v1/authorizations/search";

    /**
     * Builds the JSON body the list route now takes its criteria in.
     *
     * <p>Refactoring Rationale: these criteria were sent as QUERY PARAMETERS against a GET of
     * {@code /api/v1/authorizations}. They travel in a body because the account scope is an account
     * identifier, and a query string is part of the request line the load balancer writes into its access
     * log itself, before any application code runs -- a record the migration's sensitive-data logging
     * contract forbids it to hold. This helper exists so each case states only the criteria it varies
     * rather than repeating the JSON shape six times.</p>
     *
     * @param accountId the account scope to send, or {@code null} to omit the member entirely
     * @param direction the paging direction to send, or {@code null} to omit the member
     * @return a JSON object carrying exactly the members that were supplied, never {@code null}
     */
    private static String listBody(String accountId, String direction) {
        StringBuilder body = new StringBuilder("{");
        if (accountId != null) {
            body.append("\"accountId\":\"").append(accountId).append('"');
        }
        if (direction != null) {
            if (body.length() > 1) {
                body.append(',');
            }
            body.append("\"direction\":\"").append(direction).append('"');
        }
        return body.append('}').toString();
    }

    /** The member route the contract publishes, with a placeholder for the sealed selector. */
    private static final String READ_ROUTE = "/api/v1/authorizations/{key}";

    /** The account every request in this class scopes itself to, as eleven digits. */
    private static final String ACCOUNT_ID_DIGITS = "00000000011";

    /** The account as the key holds it. */
    private static final long ACCOUNT_ID = 11L;

    /** The customer the summary row names. */
    private static final long CUSTOMER_ID = 11L;

    /** The Julian date key. */
    private static final int AUTH_DATE = 26215;

    /** The composed time key, positionally 09:16:44 and 902 milliseconds. */
    private static final int AUTH_TIME = 9_16_44_902;

    /** The card number the row carries, which the body must publish masked. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The list service double. */
    private PendingAuthSummaryService summaries;

    /** The single-row read service double. */
    private PendingAuthDetailService detail;

    /** The real mapper, used here only to build the views the doubles return. */
    private PendingAuthViewMapper mapper;

    /** The standalone server under test. */
    private MockMvc mockMvc;

    /**
     * Assembles the standalone server over the two doubles, the shared advice and the money codec.
     *
     * <p>Assumptions: the money codec is registered on the converter this server binds bodies with, because
     * transformation rule T3 requires every amount to reach a client as a JSON STRING. A default converter
     * would emit a bare number, which a client parses into binary floating point, and the assertion below
     * would then be asserting the wrong contract rather than failing.</p>
     */
    @BeforeEach
    void setUp() {
        this.summaries = mock(PendingAuthSummaryService.class);
        this.detail = mock(PendingAuthDetailService.class);
        byte[] keyMaterial = new byte[CursorToken.MIN_KEY_LENGTH];
        Arrays.fill(keyMaterial, (byte) 0x3C);
        this.mapper = new PendingAuthViewMapper(new CursorToken(keyMaterial, Duration.ofMinutes(5)));
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new PendingAuthController(this.summaries, this.detail, FIXED_CLOCK))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(
                        JsonMapper.builder().addModule(new MoneyModule()).build()))
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(Instant.parse("2026-08-06T09:20:00Z"), ZoneOffset.UTC)))
                .build();
    }

    /**
     * The list route answers 200 with the summary block, the page and a masked card number.
     *
     * <p>Assumptions: the masked card number is asserted on the wire rather than only on the view, because
     * the property that matters is that no path publishes the sixteen characters -- and a body is where a
     * leak would actually occur. The amount is asserted as a JSON STRING for the same reason: the check has
     * to happen after serialisation to mean anything.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the list route answers 200 with a masked card number and money as a string")
    void listRouteAnswersOkWithMaskedCardAndStringMoney() throws Exception {
        when(this.summaries.list(ACCOUNT_ID, null, null, SUBJECT)).thenReturn(listView());

        this.mockMvc.perform(post(LIST_ROUTE).contentType(MediaType.APPLICATION_JSON)
                        .content(listBody(ACCOUNT_ID_DIGITS, null))
                        .principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.accountId").value(ACCOUNT_ID_DIGITS))
                .andExpect(jsonPath("$.page.items[0].cardNum").value("************1111"))
                .andExpect(jsonPath("$.page.items[0].amount").value("250.00"))
                .andExpect(jsonPath("$.page.hasNext").value(false));
    }

    /**
     * A blank account scope is refused with the reference program's own sentence, as blank rather than wrong.
     *
     * <p>Assumptions: the sentence is asserted character for character, INCLUDING the absence of a space
     * before its ellipsis. It is carried from {@code cbl/COPAUS0C.cbl} L268 to L269, and the sibling
     * sentence below has a space in the same position, so an assertion that normalised whitespace would let
     * the two be swapped without failing.</p>
     *
     * <p>Assumptions: the state is asserted as the blank one rather than the not-acceptable one, because the
     * reference highlight copybook does something EXTRA for a never-supplied value -- it additionally writes
     * a marker -- so a form needs the two told apart.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a blank account scope is refused with the reference sentence and the blank state")
    void blankAccountScopeIsRefusedWithTheReferenceSentence() throws Exception {
        this.mockMvc.perform(post(LIST_ROUTE).contentType(MediaType.APPLICATION_JSON)
                        .content(listBody("", null))
                        .principal(PRINCIPAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.message").value("Please enter Acct Id..."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("accountId"))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.BLANK.name()))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Please enter Acct Id..."));

        verifyNoInteractions(this.summaries);
    }

    /**
     * A non-digit account scope is refused with the reference program's second sentence.
     *
     * <p>Assumptions: the single space before the ellipsis is part of the assertion. The reference program
     * reaches this edit only when the blank edit passed, at {@code cbl/COPAUS0C.cbl} L272, and the sentence
     * comes from L277 to L278.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a non-digit account scope is refused with the reference numeric sentence")
    void nonDigitAccountScopeIsRefusedWithTheReferenceSentence() throws Exception {
        this.mockMvc.perform(post(LIST_ROUTE).contentType(MediaType.APPLICATION_JSON)
                        .content(listBody("0000000001X", null))
                        .principal(PRINCIPAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Acct Id must be Numeric ..."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("accountId"))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.NOT_OK.name()))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value("Acct Id must be Numeric ..."));

        verifyNoInteractions(this.summaries);
    }

    /**
     * A direction outside the two published values is refused at the boundary and keyed to the direction.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unpublished paging direction is refused at the boundary")
    void unpublishedPagingDirectionIsRefusedAtTheBoundary() throws Exception {
        this.mockMvc.perform(post(LIST_ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(listBody(ACCOUNT_ID_DIGITS, "backwards"))
                        .principal(PRINCIPAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("direction"));

        verifyNoInteractions(this.summaries);
    }

    /**
     * A direction supplied with no cursor reaches the service and is refused there, keyed to the direction.
     *
     * <p>Assumptions: this refusal is the SERVICE's rather than the boundary's, because it is a relationship
     * between two parameters and not a property of either alone. Asserting it here is what proves the
     * relationship survives the trip through the boundary rather than being swallowed by a default.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a direction with no cursor is refused and keyed to the direction")
    void directionWithNoCursorIsRefused() throws Exception {
        when(this.summaries.list(ACCOUNT_ID, null, "previous", SUBJECT)).thenThrow(new ClientInputException(
                PendingAuthSummaryService.PAGING_REFUSAL_CODE,
                PendingAuthSummaryService.DIRECTION_FIELD,
                "a paging direction is meaningful only alongside a cursor"));

        this.mockMvc.perform(post(LIST_ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(listBody(ACCOUNT_ID_DIGITS, "previous"))
                        .principal(PRINCIPAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value(PendingAuthSummaryService.DIRECTION_FIELD));
    }

    /**
     * An account with no summary row answers 200 carrying a zeroed summary and an empty page.
     *
     * <p>Assumptions: this route publishes no 404 at all, and the assertion is written at the HTTP boundary
     * because that is where the difference is observable. The baseline settles the case twice over: its keyed
     * retrieval evaluates a found arm and a not-found arm with no end-of-database arm at
     * {@code cbl/COPAUS0C.cbl} L980 to L996, and its caller then RENDERS the absence rather than reporting
     * it, moving zero into all six aggregate positions at L800 to L807 and skipping the browse at L354 to
     * L356. The published contract states the same on this operation's 200, and declares no 404 for it.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an account with no summary row answers 200 with a zeroed summary and no rows")
    void accountWithNoSummaryRowAnswersZeroedEmptyPage() throws Exception {
        when(this.summaries.list(ACCOUNT_ID, null, null, SUBJECT)).thenReturn(zeroedListView());

        this.mockMvc.perform(post(LIST_ROUTE).contentType(MediaType.APPLICATION_JSON)
                        .content(listBody(ACCOUNT_ID_DIGITS, null))
                        .principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.accountId").value(ACCOUNT_ID_DIGITS))
                .andExpect(jsonPath("$.summary.approvedAuthCnt").value(0))
                .andExpect(jsonPath("$.summary.declinedAuthCnt").value(0))
                .andExpect(jsonPath("$.summary.creditBalance").value("0.00"))
                .andExpect(jsonPath("$.summary.approvedAuthAmt").value("0.00"))
                .andExpect(jsonPath("$.page.items").isEmpty())
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath("$.page.firstKey").doesNotExist())
                .andExpect(jsonPath("$.page.lastKey").doesNotExist())
                .andExpect(jsonPath("$.screenMessage").doesNotExist());
    }

    /**
     * The member route answers 200 and publishes the stored values with the card number masked.
     *
     * <p>Assumptions: the two temporal key components ARE published on the detail body, unlike on a list
     * row, and both are asserted as the DECODED values rather than the nines complements the segment stores.
     * A body carrying the complement would look well formed while every date derived from it came out
     * inverted.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the member route answers 200 publishing the decoded key and a masked card number")
    void memberRouteAnswersOkPublishingTheDecodedKey() throws Exception {
        String selector = this.mapper.toRowView(row(), SUBJECT).key();
        when(this.detail.read(selector, SUBJECT)).thenReturn(this.mapper.toDetailView(row(), SUBJECT));

        this.mockMvc.perform(get(READ_ROUTE, selector)
                        .principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID_DIGITS))
                .andExpect(jsonPath("$.authDate").value(AUTH_DATE))
                .andExpect(jsonPath("$.authTime").value(AUTH_TIME))
                .andExpect(jsonPath("$.cardNum").value("************1111"))
                .andExpect(jsonPath("$.transactionAmt").value("250.00"));
    }

    /**
     * A selector that is not of the sealed shape is refused by the service and keyed to the path member.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a selector that is not sealed is refused and keyed to the path member")
    void selectorThatIsNotSealedIsRefused() throws Exception {
        when(this.detail.read(any(), any())).thenThrow(new ClientInputException("AUTH_SELECTOR_REFUSED",
                PendingAuthViewMapper.SELECTOR_FIELD, "sealed value is not a sealed token"));

        this.mockMvc.perform(get(READ_ROUTE, "11111111111:26215:91644902")
                        .principal(PRINCIPAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value(PendingAuthViewMapper.SELECTOR_FIELD));
    }

    /**
     * Builds the list body the double returns: one summary block and one row.
     *
     * @return a mapped list view carrying one row and no further page
     */
    private PendingAuthListView listView() {
        PendingAuthSummary summary = new PendingAuthSummary(ACCOUNT_ID, CUSTOMER_ID);
        summary.refreshLimits(new BigDecimal("5000.00"), new BigDecimal("1000.00"));
        summary.recordApproved(new BigDecimal("250.00"));
        return this.mapper.toListView(summary, List.of(row()), false, false, null, SUBJECT, null);
    }

    /**
     * Builds the body an account with no summary row is answered with: zeros and no rows.
     *
     * <p>Assumptions: the zeroed state comes from the domain type's own identified-and-otherwise-zeroed
     * constructor, which is the same instrument the service under test uses, so this double cannot assert a
     * shape the service does not produce. The customer identifier is zero because that field is sourced from
     * a cross-context read this context does not perform.</p>
     *
     * @return a mapped list view carrying a zeroed summary block, no rows and no boundary cursors
     */
    private PendingAuthListView zeroedListView() {
        return this.mapper.toListView(new PendingAuthSummary(ACCOUNT_ID, 0L), List.of(), false, false,
                null, SUBJECT, null);
    }

    /**
     * Builds the authorization row every body in this class is rendered from.
     *
     * @return a fully populated authorization row
     */
    private static PendingAuthDetail row() {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME),
                "260803", "091644", CARD_NUMBER, "0100", "2712", "0100", "0000",
                "AUTH01", "00", "0000", "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }
}
