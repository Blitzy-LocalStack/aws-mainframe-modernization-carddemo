package com.carddemo.account.api;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.account.dto.AccountContextView;
import com.carddemo.account.service.AccountUpdateService;
import com.carddemo.account.service.AccountViewService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.validation.FieldValidationFlag;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the account and customer routes through a real dispatcher rather than by calling handlers.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Purpose: every other test in this package invokes a controller METHOD. That proves the method body
 * and proves nothing about the mapping, and the difference is not theoretical: two consuming contexts
 * publish body-based {@code POST} addresses for the account read and the customer probe, this context
 * mounted keyed {@code GET} and {@code HEAD} routes instead, and every one of those calls was answered
 * 404 — read by the consumer as "the row does not exist" and turned into a committed decline. A static
 * census of the published contract passed throughout, because both sides of that comparison described
 * this context. Only a dispatcher can answer "does a request to this literal address with this literal
 * body reach a handler".
 *
 * <p>Assumptions: the dispatcher is assembled with {@code standaloneSetup} rather than through a full
 * application context. What is under test is request mapping, body binding, bean validation and the
 * shared advice's rendering; a context would additionally bring the filter chains, whose behaviour is
 * asserted by {@code SecurityConfigTest} and {@code InternalApiSecurityConfigTest} against the matchers
 * themselves. Splitting the two keeps each failure attributable to one layer.
 *
 * <p>Assumptions: the shared advice is registered, because several of the outcomes asserted here ARE the
 * advice's — a rejected body renders 400 with one entry per offending property, and an absent row renders
 * 404. Omitting it would leave those cases asserting a servlet-container default.
 *
 * <p>A test class accepts no parameter and yields no value, so this block carries no parameter or return
 * clause.
 */
class AccountDispatcherTest {

    /**
     * The instant the rendered problem documents are stamped with.
     *
     * <p>Assumptions: fixed, so a rendered body is reproducible from the source alone.</p>
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-18T10:15:30Z");

    /**
     * An account identifier inside the declared eleven-digit width.
     */
    private static final long ACCOUNT_ID = 11L;

    /**
     * A customer identifier inside the declared nine-digit width.
     */
    private static final long CUSTOMER_ID = 987_654_321L;

    /**
     * The read path both controllers are built over.
     */
    private AccountViewService reads;

    /**
     * The write path the account controller is built over.
     */
    private AccountUpdateService writes;

    /**
     * The dispatcher under test.
     */
    private MockMvc mockMvc;

    /**
     * Builds the dispatcher over both controllers with the shared advice registered.
     */
    @BeforeEach
    void buildDispatcher() {
        this.reads = mock(AccountViewService.class);
        this.writes = mock(AccountUpdateService.class);

        // WHY : Assumptions: the money module is installed on the converter, because the account context
        //       view renders three amounts and the migration's fixed-point rule requires each to leave as
        //       a JSON STRING. Without the module they would serialise as JSON numbers and the assertion
        //       below would pass against a representation a client parses into a binary floating-point
        //       value, which is the exactness failure the rule exists to prevent.
        JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter(
                JsonMapper.builder().addModule(new MoneyModule()).build());

        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new AccountController(this.reads, this.writes),
                        new CustomerController(this.reads))
                .setMessageConverters(converter)
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * The account lookup answers the literal address and body the consuming contexts send.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /api/v1/accounts/lookup answers the three amounts as JSON strings")
    void theAccountLookupAnswersTheConsumersLiteralRequest() throws Exception {
        when(this.reads.readAccountContext(ACCOUNT_ID)).thenReturn(new AccountContextView(
                Money.of(new BigDecimal("5000.00")), Money.of(new BigDecimal("500.00")),
                Money.of(new BigDecimal("1234.56"))));

        // WHY : Assumptions: the address and the body member are written as LITERALS rather than composed
        //       from this module's constants. Both consuming contexts hold their own literal, so an
        //       expectation composed from this side would move with a rename here and stop measuring the
        //       agreement -- which is exactly how the earlier keyed mounting passed its own census.
        this.mockMvc.perform(post("/api/v1/accounts/lookup")
                        .contentType("application/json")
                        .content("{\"accountId\":11}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditLimit").value("5000.00"))
                .andExpect(jsonPath("$.cashCreditLimit").value("500.00"))
                .andExpect(jsonPath("$.currentBalance").value("1234.56"));
    }

    /**
     * An absent account renders 404 from the lookup, which is the consumer's decision input.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /api/v1/accounts/lookup renders 404 for an absent account")
    void theAccountLookupRendersNotFoundForAnAbsentRow() throws Exception {
        when(this.reads.readAccountContext(ACCOUNT_ID))
                .thenThrow(new NoSuchElementException("the account master holds no such row"));

        this.mockMvc.perform(post("/api/v1/accounts/lookup")
                        .contentType("application/json")
                        .content("{\"accountId\":11}"))
                .andExpect(status().isNotFound());
    }

    /**
     * A lookup body outside the declared width is refused before the read is attempted.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /api/v1/accounts/lookup refuses a body outside the declared width")
    void theAccountLookupRefusesAnOutOfRangeBody() throws Exception {
        this.mockMvc.perform(post("/api/v1/accounts/lookup")
                        .contentType("application/json")
                        .content("{\"accountId\":100000000000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION));

        verify(this.reads, never()).readAccountContext(anyLong());
    }

    /**
     * No keyed account address is mounted under any method, for the read, the edit or the walk.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("no keyed account address is mounted under any method")
    void theRetiredKeyedAccountRoutesAreNotMounted() throws Exception {
        // WHY : Assumptions: this is asserted rather than assumed, because leaving any keyed address
        //       mounted would preserve the disclosure the moves exist to remove -- the load balancer
        //       composes its access record from the request line before any application code runs, so a
        //       caller using an old shape would keep writing account identifiers into it.
        // WHY : Refactoring Rationale: this case expected 405 and asserted the keyed TEMPLATE survived
        //       under a different method, because PUT /api/v1/accounts/{accountId} was then a mounted
        //       end-user route. It is not one any more: the edit moved to POST /api/v1/accounts/update,
        //       the human read to POST /api/v1/accounts/view and the cross-reference walk to
        //       POST /api/v1/accounts/card-cross-references/search, each so that its identifier travels
        //       in a body. No method is mapped at a keyed address now, so 404 is the honest expectation
        //       and 405 would fail. All four verbs are driven rather than the one this case used to
        //       drive, because a partial retirement -- one method left behind at a keyed address -- is
        //       exactly the state a single-verb assertion would not see.
        assertNoHandler(get("/api/v1/accounts/11"));
        assertNoHandler(put("/api/v1/accounts/11"));
        assertNoHandler(get("/api/v1/accounts/11/view"));
        assertNoHandler(get("/api/v1/accounts/11/card-cross-references"));

        verify(this.reads, never()).readAccountContext(anyLong());
        verify(this.reads, never()).readAccountView(anyLong());
    }

    /**
     * The customer probe answers the literal address and body the consuming context sends.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /api/v1/customers/lookup answers 204 with no body for a present customer")
    void theCustomerLookupAnswersNoContentForAPresentRow() throws Exception {
        when(this.reads.customerExists(CUSTOMER_ID)).thenReturn(true);

        this.mockMvc.perform(post("/api/v1/customers/lookup")
                        .contentType("application/json")
                        .content("{\"customerId\":987654321}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    /**
     * An absent customer renders 404 with no body, matching the published shape of both answers.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /api/v1/customers/lookup answers 404 with no body for an absent customer")
    void theCustomerLookupAnswersNotFoundWithNoBody() throws Exception {
        when(this.reads.customerExists(CUSTOMER_ID)).thenReturn(false);

        this.mockMvc.perform(post("/api/v1/customers/lookup")
                        .contentType("application/json")
                        .content("{\"customerId\":987654321}"))
                .andExpect(status().isNotFound())
                // WHY : Assumptions: the empty body is asserted as well as the status, because the
                //       consuming context's contract is that neither answer carries one. Letting the
                //       shared advice render a problem document here would announce a body on a response
                //       a caller is entitled to read as bodyless.
                .andExpect(content().string(""));
    }

    /**
     * The retired keyed customer probe is no longer mounted under either of its former methods.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the retired keyed customer probe is not mounted for GET or HEAD")
    void theRetiredKeyedCustomerProbeIsNotMounted() throws Exception {
        assertNoHandler(get("/api/v1/customers/987654321"));
        assertNoHandler(head("/api/v1/customers/987654321"));

        verify(this.reads, never()).customerExists(anyLong());
    }

    /**
     * Asserts that a request reaches no handler because no route is mounted at its address.
     *
     * <p>Assumptions: the absence is measured on a dispatcher assembled WITHOUT the shared advice, and
     * that is required rather than tidy. The advice declares a catch-all handler, so with it registered an
     * unmapped address renders 500 -- measured -- and a 500 expectation would pass equally against a
     * MOUNTED handler that threw, which is the opposite of what this assertion is for. Without the advice
     * the same request renders 404, which distinguishes "no route here" from "a route that failed".</p>
     *
     * <p>Trade-offs: a second dispatcher is assembled per call. That is a negligible cost beside stating
     * the assertion honestly, and it keeps the primary dispatcher configured the way every other case in
     * this class needs it.</p>
     *
     * @param request the request to dispatch; must not be {@code null}
     * @throws Exception if the dispatcher itself cannot be built or driven
     */
    private void assertNoHandler(RequestBuilder request) throws Exception {
        adviceFreeDispatcher().perform(request).andExpect(status().isNotFound());
    }

    /**
     * Builds a dispatcher carrying the two controllers and no exception advice.
     *
     * @return a dispatcher that renders framework dispatch failures rather than delegating them
     */
    private MockMvc adviceFreeDispatcher() {
        return MockMvcBuilders
                .standaloneSetup(new AccountController(this.reads, this.writes),
                        new CustomerController(this.reads))
                .build();
    }

    /**
     * An all-zeroes account identifier is refused by the end-user view with the published 400.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an all-zeroes selector is refused 400 naming the accountId property")
    void theAllZeroesKeyIsRefusedByTheEndUserView() throws Exception {
        // WHY : Assumptions: the edit is driven through the read path's own entry rather than stubbed to
        //       a boolean, because the sentence and the property name the response carries are the
        //       reference's and belong to that edit. Substituting them here would assert wording this
        //       test invented.
        when(this.reads.accountFilterFieldErrors("00000000000")).thenReturn(List.of(
                new ApiError.FieldError("accountId", FieldValidationFlag.NOT_OK,
                        "Account Filter must be a non-zero 11 digit number")));

        this.mockMvc.perform(post("/api/v1/accounts/view")
                        .contentType("application/json")
                        .content("{\"accountId\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("accountId"));

        verify(this.reads, never()).readAccountView(anyLong());
    }

    /**
     * A non-zero account identifier reaches the end-user view, so the refusal above is the edit's.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a non-zero account identifier reaches the end-user view")
    void aNonZeroKeyReachesTheEndUserView() throws Exception {
        // WHY : Assumptions: the positive half is asserted beside the refusal because the refusal alone
        //       would pass against an edit that rejected EVERY value. Together they establish that the
        //       all-zeroes boundary is one the dispatcher actually applies, and that it is applied by the
        //       reference's own edit rather than by the binding layer: the submitted selector declares a
        //       floor of ZERO, so a zero binds successfully and is refused afterwards with the sentence
        //       app/cbl/COACTVWC.cbl moves into its message channel at L672.
        when(this.reads.accountFilterFieldErrors("00000000011")).thenReturn(List.of());
        when(this.reads.readAccountView(ACCOUNT_ID))
                .thenThrow(new NoSuchElementException("the account master holds no such row"));

        this.mockMvc.perform(post("/api/v1/accounts/view")
                        .contentType("application/json")
                        .content("{\"accountId\":11}"))
                .andExpect(status().isNotFound());

        verify(this.reads).readAccountView(ACCOUNT_ID);
    }
}
