package com.carddemo.authorization.api;


import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.dto.PendingAuthDetailResponse;
import com.carddemo.authorization.dto.PendingAuthListView;
import com.carddemo.authorization.mapper.PendingAuthDetailMapper;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    /** The screen-shaped member route the contract publishes. */
    private static final String SCREEN_ROUTE = "/api/v1/authorizations/{key}/screen";

    /** The forward paging move the contract publishes on the member. */
    private static final String NEXT_ROUTE = "/api/v1/authorizations/{key}/next";

    /**
     * The declared width of the screen message line.
     *
     * <p>Assumptions: 78 is declared here as a literal rather than imported, because the response record
     * keeps its own width private and a test that reached for it would be asserting a value against itself.
     * The authority is the map: {@code cpy-bms/COPAU00.cpy} L764 and {@code cpy-bms/COPAU01.cpy} L344 each
     * declare {@code 02  ERRMSGO  PIC X(78)}.</p>
     */
    private static final int SCREEN_MESSAGE_WIDTH = 78;

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

    /**
     * The approval reason the shared row carries, which is itself a table entry.
     *
     * <p>Assumptions: {@code '0000'} is declared as the FIRST entry of the reference display table at
     * {@code cbl/COPAUS1C.cbl} L58, so the default row exercises a table hit rather than the no-entry
     * path -- the reference runs its search on every authorization, approved ones included.</p>
     */
    private static final String APPROVED_REASON = "0000";

    /**
     * The reason the producing ladder writes when it declines for a reason it does not enumerate.
     *
     * <p>Assumptions: {@code '9000'} is BOTH a reachable outcome and a table entry -- L715 and L716 of
     * {@code cbl/COPAUA0C.cbl} write it on the ladder's {@code WHEN OTHER} arm and L67 of
     * {@code cbl/COPAUS1C.cbl} carries its description -- which is what makes it a different case from a
     * code the table does not hold.</p>
     */
    private static final String CATCH_ALL_REASON = "9000";

    /**
     * A response reason no reference program writes and the display table does not hold.
     *
     * <p>Assumptions: the value is outside the table on purpose, so the read takes the no-entry path the
     * reference reaches at L319 to L323. It is not one of the ten table codes and not one of the eight the
     * ladder emits, and it is deliberately NOT {@code '4400'} or {@code '5300'}: those two ARE table
     * entries, held at L63 and L66 while no program writes them, so either would take the hit path and
     * assert the opposite of what this case is for.</p>
     */
    private static final String UNTABLED_REASON = "7777";

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
     * A full page reports a further page, and the envelope carries no counted or numbered member.
     *
     * <p>Assumptions: the boundary is FIVE rows, which is the reference page depth stated three times over
     * in {@code cbl/COPAUS0C.cbl} -- L126 declares {@code CDEMO-CPVS-AUTH-KEYS PIC X(08) OCCURS 5 TIMES},
     * L424 bounds the fill loop with {@code WS-IDX > 5}, and L611 bounds the clearing loop the same way.
     * Asserting at exactly that depth is what distinguishes a further-page indicator from a row count: a
     * page of five with more behind it and a page of five with nothing behind it are the two cases a caller
     * cannot tell apart from the row list alone.</p>
     *
     * <p>Assumptions: the absence of a page number, an offset, a skip and a total is asserted POSITIVELY
     * rather than trusted, because those are the members an offset-paged envelope would carry, and an
     * envelope that quietly grew one would let a client start paging by position against data that is
     * positioned by key. The reference indicator is {@code CDEMO-CPVS-NEXT-PAGE-FLG}, declared at L123 with
     * its two condition names at L124 and L125, and it is a flag rather than a count.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a full page of five reports a further page and the envelope counts nothing")
    void fullPageReportsAFurtherPageAndCarriesNoCountedMember() throws Exception {
        when(this.summaries.list(ACCOUNT_ID, null, null, SUBJECT)).thenReturn(fullPageListView());

        this.mockMvc.perform(post(LIST_ROUTE).contentType(MediaType.APPLICATION_JSON)
                        .content(listBody(ACCOUNT_ID_DIGITS, null))
                        .principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.items.length()").value(PendingAuthSummaryService.PAGE_SIZE))
                .andExpect(jsonPath("$.page.hasNext").value(true))
                .andExpect(jsonPath("$.page.firstKey").exists())
                .andExpect(jsonPath("$.page.lastKey").exists())
                .andExpect(jsonPath("$.page.pageNumber").doesNotExist())
                .andExpect(jsonPath("$.page.page").doesNotExist())
                .andExpect(jsonPath("$.page.offset").doesNotExist())
                .andExpect(jsonPath("$.page.skip").doesNotExist())
                .andExpect(jsonPath("$.page.size").doesNotExist())
                .andExpect(jsonPath("$.page.totalCount").doesNotExist())
                .andExpect(jsonPath("$.page.totalElements").doesNotExist())
                .andExpect(jsonPath("$.page.totalPages").doesNotExist());
    }

    /**
     * The two list-boundary sentences reach the caller on the message line, each on its own move.
     *
     * <p>Assumptions: the two are asserted as SEPARATE cases against separate literals, because they are
     * two strings in the reference and not one parameterised string: {@code cbl/COPAUS0C.cbl} L381 writes
     * the top-of-page sentence on a backward move and L409 writes the bottom-of-page sentence on a forward
     * move. Neither carries a leading space, unlike every error sentence in the same program, so the
     * assertion is character for character.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the top and bottom list boundaries publish their own reference sentences")
    void listBoundarySentencesArePublishedDistinctly() throws Exception {
        String cursor = this.mapper.toRowView(row(), SUBJECT).key();
        when(this.summaries.list(ACCOUNT_ID, cursor, PendingAuthSummaryService.DIRECTION_PREVIOUS, SUBJECT))
                .thenReturn(messageListView(PendingAuthListView.MESSAGE_TOP_OF_PAGE));
        when(this.summaries.list(ACCOUNT_ID, cursor, PendingAuthSummaryService.DIRECTION_NEXT, SUBJECT))
                .thenReturn(messageListView(PendingAuthListView.MESSAGE_BOTTOM_OF_PAGE));

        this.mockMvc.perform(post(LIST_ROUTE).contentType(MediaType.APPLICATION_JSON)
                        .content(pagingBody(cursor, PendingAuthSummaryService.DIRECTION_PREVIOUS))
                        .principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screenMessage")
                        .value("You are already at the top of the page..."));

        this.mockMvc.perform(post(LIST_ROUTE).contentType(MediaType.APPLICATION_JSON)
                        .content(pagingBody(cursor, PendingAuthSummaryService.DIRECTION_NEXT))
                        .principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screenMessage")
                        .value("You are already at the bottom of the page..."));
    }

    /**
     * A successful screen read writes no message line, and every sentence that can be written fits it.
     *
     * <p>Assumptions: the absence is a property of the CONTROLLER, which supplies that component of the
     * screen context absent rather than empty, because the reference program writes its message line only
     * on a refusal or a navigation boundary and leaves it untouched on a read that succeeded. Asserting the
     * member is missing rather than blank is what keeps a client able to tell "nothing was said" from "an
     * empty sentence was said".</p>
     *
     * <p>Assumptions: the width the line admits is 78, per {@code cpy-bms/COPAU00.cpy} L764 and
     * {@code cpy-bms/COPAU01.cpy} L344, and NOT the 75 of the house error line at
     * {@code app/cpy/CVCRD01Y.cpy} L28 and L29. The three sentences that may occupy it are checked against
     * that bound here because a sentence longer than the field could never have appeared on the terminal at
     * all, so publishing one would be a divergence no width annotation on the response would catch -- the
     * list view's message is drawn from a closed set, not from a length-checked string. The declared
     * maximum itself is asserted against the published contract by
     * {@code config/AuthorizationApiContractTest}, which pins the screen line at 78 and the shared error
     * line at 75 so the two cannot converge.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a successful screen read writes no message line, and every sentence fits 78 positions")
    void successfulScreenReadWritesNoMessageLine() throws Exception {
        String selector = this.mapper.toRowView(row(), SUBJECT).key();
        when(this.detail.readForScreen(eq(selector), eq(SUBJECT), any()))
                .thenAnswer(invocation -> screenResponse(invocation.getArgument(2), row()));

        this.mockMvc.perform(get(SCREEN_ROUTE, selector).principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").doesNotExist());

        for (String sentence : PendingAuthListView.BOUNDARY_MESSAGES) {
            org.assertj.core.api.Assertions.assertThat(sentence.length())
                    .as("the reference sentence must fit the message line the map declares")
                    .isLessThanOrEqualTo(SCREEN_MESSAGE_WIDTH);
        }
    }

    /**
     * Contention on the stored row answers 409 rather than surfacing a persistence failure.
     *
     * <p>Assumptions: the mapping belongs to the shared advice and not to the controller, so the case is
     * driven by letting the service double raise the framework's optimistic-lock failure and asserting the
     * status and the conflict code the advice produces. A local handler on the controller would answer this
     * identically while making the advice's contract unobservable, and the two could then disagree.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an optimistic-lock conflict answers 409 through the shared advice")
    void optimisticLockConflictAnswersConflict() throws Exception {
        when(this.detail.read(any(), any()))
                .thenThrow(new OptimisticLockingFailureException("row changed"));

        this.mockMvc.perform(get(READ_ROUTE, this.mapper.toRowView(row(), SUBJECT).key())
                        .principal(PRINCIPAL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_CONFLICT));
    }

    /**
     * The screen route reports the reference chrome, and reports it from the reference declarations.
     *
     * <p>Assumptions: all four chrome values are asserted against the reference literals rather than against
     * the controller's constants, so the assertion cannot follow a constant that drifts. The transaction name
     * is {@code CPVD} per {@code cbl/COPAUS1C.cbl} L36, moved to the screen at L415; the program name is
     * {@code COPAUS1C} per L33, moved at L416; and the two title lines are the complete forty-position
     * {@code CCDA-TITLE01} and {@code CCDA-TITLE02} of {@code app/cpy/COTTL01Y.cpy} L18 to L22, moved at
     * L413 and L414. The leading and trailing blanks are part of each assertion because the reference moves
     * a forty-position item into a forty-position field, so trimming either would be a different value.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the screen route reports the reference transaction, program and title band")
    void screenRouteReportsTheReferenceChrome() throws Exception {
        String selector = this.mapper.toRowView(row(), SUBJECT).key();
        when(this.detail.readForScreen(eq(selector), eq(SUBJECT), any()))
                .thenAnswer(invocation -> screenResponse(invocation.getArgument(2), row()));

        this.mockMvc.perform(get(SCREEN_ROUTE, selector).principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionName").value("CPVD"))
                .andExpect(jsonPath("$.programName").value("COPAUS1C"))
                .andExpect(jsonPath("$.title01").value("      AWS Mainframe Modernization       "))
                .andExpect(jsonPath("$.title02").value("              CardDemo                  "))
                .andExpect(jsonPath("$.currentDate").value("08/05/26"))
                .andExpect(jsonPath("$.currentTime").value("10:45:35"))
                .andExpect(jsonPath("$.cardNumber").value("************1111"))
                .andExpect(jsonPath("$.cardVerificationValue").doesNotExist())
                .andExpect(jsonPath("$.cvv").doesNotExist());
    }

    /**
     * A recognised catch-all reason and a reason absent from the table render differently.
     *
     * <p>Assumptions: the two are asserted as separate cases because they answer different questions and the
     * reference keeps them apart. {@code '9000UNKNOWN'} is a table ENTRY, declared at
     * {@code cbl/COPAUS1C.cbl} L67 and reachable because L715 and L716 of {@code cbl/COPAUA0C.cbl} write
     * {@code '9000'} on the ladder's {@code WHEN OTHER} arm; the table MISS is a different mechanism,
     * rendering the {@code '9999'} and {@code 'ERROR'} pair that L321 to L323 write. A single case could not
     * distinguish them, and a client shown {@code 9999-ERROR} for a declined-for-an-unenumerated-reason
     * authorization would be told the stored row was unreadable when it was not.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the catch-all reason and a reason outside the table render as distinct values")
    void declineReasonRendersTheCatchAllAndTheTableMissDistinctly() throws Exception {
        String selector = this.mapper.toRowView(row(), SUBJECT).key();

        // WHY : Assumptions: the composed value is asserted at the full twenty positions the map's field
        //       declares, blanks included, rather than as the twelve significant characters. The reference
        //       moves a four-position code, a separator and a fifteen-position description into
        //       AUTHRSNO PIC X(20) at cpy-bms/COPAU01.cpy L248, so the trailing blanks are positions the
        //       field has rather than whitespace a comparison may ignore.
        when(this.detail.readForScreen(eq(selector), eq(SUBJECT), any())).thenAnswer(invocation ->
                screenResponse(invocation.getArgument(2), rowWithReason(CATCH_ALL_REASON)));
        this.mockMvc.perform(get(SCREEN_ROUTE, selector).principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authResponseReason").value("9000-UNKNOWN        "));

        when(this.detail.readForScreen(eq(selector), eq(SUBJECT), any())).thenAnswer(invocation ->
                screenResponse(invocation.getArgument(2), rowWithReason(UNTABLED_REASON)));
        this.mockMvc.perform(get(SCREEN_ROUTE, selector).principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authResponseReason").value("9999-ERROR          "));
    }

    /**
     * The forward move on the detail route answers 200 with the reference end-of-data sentence.
     *
     * <p>Assumptions: the sentence is the detail screen's own third boundary string,
     * {@code 'Already at the last Authorization...'} at {@code cbl/COPAUS1C.cbl} L283, and it is asserted
     * against that literal rather than against either list sentence. All three are separate strings in the
     * reference -- the two list ones read "You are already at the top" and "the bottom of the page" -- so an
     * assertion that accepted any of the three would let them be merged into one.</p>
     *
     * <p>Assumptions: the status is 200 and not 404, because "nothing follows" is a successful answer to the
     * forward move: the reference writes the sentence on the screen and leaves the displayed authorization in
     * place rather than refusing the request.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the forward move answers 200 with the detail screen's own boundary sentence")
    void forwardMoveAnswersEndOfDataWithTheDetailBoundarySentence() throws Exception {
        String selector = this.mapper.toRowView(row(), SUBJECT).key();
        when(this.detail.readNext(selector, SUBJECT)).thenReturn(
                new PendingAuthDetailService.NextAuthorization(null, true,
                        PendingAuthDetailService.LAST_AUTHORIZATION_REACHED));

        this.mockMvc.perform(get(NEXT_ROUTE, selector).principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endOfData").value(true))
                .andExpect(jsonPath("$.authorization").doesNotExist())
                .andExpect(jsonPath("$.message").value("Already at the last Authorization..."));
    }

    /**
     * The forward move answers the following authorization with its card number masked.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the forward move publishes the following authorization with a masked card number")
    void forwardMovePublishesTheFollowingAuthorization() throws Exception {
        String selector = this.mapper.toRowView(row(), SUBJECT).key();
        when(this.detail.readNext(selector, SUBJECT)).thenReturn(
                new PendingAuthDetailService.NextAuthorization(
                        this.mapper.toDetailView(row(), SUBJECT), false, null));

        this.mockMvc.perform(get(NEXT_ROUTE, selector).principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endOfData").value(false))
                .andExpect(jsonPath("$.authorization.cardNum").value("************1111"))
                .andExpect(jsonPath("$.authorization.transactionAmt").value("250.00"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    /**
     * Builds the screen-shaped body the double returns, rendered through the real mapper.
     *
     * <p>Assumptions: the chrome is taken from the context the CONTROLLER built rather than restated here,
     * which is what makes the chrome assertions above assertions about the controller. Restating it would
     * test this helper.</p>
     *
     * <p>Assumptions: the description is resolved from the row's own stored reason through the SERVICE's
     * lookup, which is the composition the production read performs -- it resolves the description from
     * {@code detail.getAuthRespReason()} and hands both to the mapper. Passing an unrelated description
     * would let a case assert a pairing the service cannot produce.</p>
     *
     * @param context the screen context the controller passed to the service, captured from the invocation;
     *     must not be {@code null}
     * @param detail the authorization row to render; must not be {@code null}
     * @return the screen-shaped response for that row, never {@code null}
     */
    private static PendingAuthDetailResponse screenResponse(
            PendingAuthDetailMapper.ScreenContext context, PendingAuthDetail detail) {
        return PendingAuthDetailMapper.toResponse(detail,
                PendingAuthDetailService.declineDescriptionFor(detail.getAuthRespReason()), context);
    }

    /**
     * Builds the shared authorization row carrying a chosen stored response reason.
     *
     * @param authRespReason the four-character response reason as the segment would hold it; must not be
     *     {@code null}
     * @return a fully populated authorization row carrying that reason, never {@code null}
     */
    private static PendingAuthDetail rowWithReason(String authRespReason) {
        return row(AUTH_TIME, authRespReason);
    }

    /**
     * Builds the JSON body a paging move sends: a cursor and a direction, with the account scope.
     *
     * @param cursor the sealed cursor to send; must not be {@code null}
     * @param direction the paging direction to send; must not be {@code null}
     * @return a JSON object carrying the scope, the cursor and the direction, never {@code null}
     */
    private static String pagingBody(String cursor, String direction) {
        return "{\"accountId\":\"" + ACCOUNT_ID_DIGITS + "\",\"cursor\":\"" + cursor
                + "\",\"direction\":\"" + direction + "\"}";
    }

    /**
     * Builds a list body carrying a message line and no rows.
     *
     * @param message the message line the view should carry; may be {@code null} for none
     * @return a mapped list view carrying the message and an empty page, never {@code null}
     */
    private PendingAuthListView messageListView(String message) {
        return this.mapper.toListView(new PendingAuthSummary(ACCOUNT_ID, CUSTOMER_ID), List.of(), false,
                false, message, SUBJECT, null);
    }

    /**
     * Builds a list body carrying a full page of rows and a further page behind it.
     *
     * <p>Assumptions: the rows are distinct only in their time component, because the page depth is what this
     * body exists to exercise and the sealed cursors are derived per row -- identical rows would seal to one
     * value and the envelope's two boundary cursors would then be indistinguishable.</p>
     *
     * @return a mapped list view carrying exactly the reference page depth of rows, never {@code null}
     */
    private PendingAuthListView fullPageListView() {
        List<PendingAuthDetail> rows = new java.util.ArrayList<>();
        for (int index = 0; index < PendingAuthSummaryService.PAGE_SIZE; index++) {
            rows.add(rowAt(AUTH_TIME - index));
        }
        return this.mapper.toListView(new PendingAuthSummary(ACCOUNT_ID, CUSTOMER_ID), rows, true, false,
                null, SUBJECT, null);
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
        return rowAt(AUTH_TIME);
    }

    /**
     * Builds the shared authorization row at a chosen time component of its key.
     *
     * <p>Assumptions: only the time component varies, because it is the part of the key that orders rows
     * within one day and it is therefore the smallest change that yields distinct rows -- and distinct sealed
     * selectors -- for a multi-row page.</p>
     *
     * @param authTime the composed time component of the key, positionally hours, minutes, seconds and
     *     milliseconds
     * @return a fully populated authorization row keyed at that time, never {@code null}
     */
    private static PendingAuthDetail rowAt(int authTime) {
        return row(authTime, APPROVED_REASON);
    }

    /**
     * Builds the authorization row every body in this class is rendered from, at a chosen key time and
     * stored response reason.
     *
     * @param authTime the composed time component of the key, positionally hours, minutes, seconds and
     *     milliseconds
     * @param authRespReason the four-character response reason as the segment would hold it; must not be
     *     {@code null}
     * @return a fully populated authorization row, never {@code null}
     */
    private static PendingAuthDetail row(int authTime, String authRespReason) {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, authTime),
                "260803", "091644", CARD_NUMBER, "0100", "2712", "0100", "0000",
                "AUTH01", "00", authRespReason, "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }
}
