package com.carddemo.transaction.api;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.transaction.dto.TransactionAddRequest;
import com.carddemo.transaction.dto.TransactionAddResponse;
import com.carddemo.transaction.service.TransactionAddService;
import com.carddemo.transaction.service.TransactionListService;
import com.carddemo.transaction.service.TransactionViewService;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the transaction resource answers each failure with the sentence its contract publishes.
 *
 * <p>Purpose: the published contract promises the reference's own wording on the 400, 404 and 500 paths of
 * the browse and the detail read, and those sentences were unreachable because the shared advice rendered a
 * fixed sentence and discarded the one the service raised. These tests assert the rendered body, so a
 * regression in either the advice or the service is caught at the wire rather than at the raise site.</p>
 *
 * <p>Assumptions: the controller is exercised through the standalone builder rather than a started
 * application context, for the same reason the routing contract test reads annotations -- a context for
 * this module needs a resolvable token issuer and a reachable database. The shared advice is registered
 * explicitly, because without it a refusal surfaces as a raw servlet error and none of these assertions
 * would be about the published shape.</p>
 */
@DisplayName("the transaction resource's published failure contracts")
class TransactionControllerTest {

    /** A fixed instant so the rendered problem timestamp is deterministic. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-18T12:00:00Z");

    /**
     * The authenticated caller every browse request in this class is made as.
     *
     * <p>Assumptions: a principal is supplied because the browse handler takes one -- the page's
     * boundary tokens are bound to the caller's name, so the service refuses a blank subject. Supplying
     * it through the request builder rather than through a security test slice keeps this class a plain
     * web slice with no additional test dependency, which is the arrangement the authorization context's
     * selector-bearing controller tests already use.</p>
     */
    private static final Principal PRINCIPAL = () -> "11111111-2222-3333-4444-555555555555";

    /** A well-formed identifier used where the identifier itself is not what is under test. */
    private static final String PRESENT_ID = "0000000000000001";

    /** The browse this controller delegates to, stubbed per test. */
    private TransactionListService listService;

    /** The detail read this controller delegates to, stubbed per test. */
    private TransactionViewService viewService;

    /** The capture this controller delegates to, stubbed per test. */
    private TransactionAddService addService;

    /** The entry point under test. */
    private MockMvc mockMvc;

    /** Builds the controller over stubbed services and registers the shared advice. */
    @BeforeEach
    void setUp() {
        this.listService = mock(TransactionListService.class);
        this.viewService = mock(TransactionViewService.class);
        this.addService = mock(TransactionAddService.class);
        CursorToken cursorToken = mock(CursorToken.class);

        TransactionController controller = new TransactionController(this.listService, this.viewService,
                this.addService, cursorToken);

        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(
                        JsonMapper.builder().addModule(new MoneyModule()).build()))
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * A never-supplied identifier is refused with the detail program's own empty-identifier sentence.
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("render the reference's empty-identifier sentence on the detail read's 400")
    void detailReadRefusesBlankIdentifierWithReferenceSentence() throws Exception {
        when(this.viewService.viewTransaction(anyString()))
                .thenThrow(new com.carddemo.common.error.ClientInputException(
                        com.carddemo.common.error.ApiError.CODE_VALIDATION,
                        TransactionViewService.FIELD_TRANSACTION_ID, FieldValidationFlag.BLANK,
                        TransactionViewService.MESSAGE_TRAN_ID_EMPTY));

        this.mockMvc.perform(get(TransactionController.BASE_PATH + "/" + PRESENT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(TransactionViewService.MESSAGE_TRAN_ID_EMPTY))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value(TransactionViewService.FIELD_TRANSACTION_ID))
                .andExpect(jsonPath("$.fieldErrors[0].state").value(FieldValidationFlag.BLANK.name()))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value(TransactionViewService.MESSAGE_TRAN_ID_EMPTY));
    }

    /**
     * An absent row is reported with the detail program's own not-found sentence.
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("render the reference's not-found sentence on the detail read's 404")
    void detailReadReportsAbsenceWithReferenceSentence() throws Exception {
        when(this.viewService.viewTransaction(anyString())).thenThrow(
                new NoSuchElementException(TransactionViewService.MESSAGE_TRANSACTION_ID_NOT_FOUND));

        this.mockMvc.perform(get(TransactionController.BASE_PATH + "/" + PRESENT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value(TransactionViewService.MESSAGE_TRANSACTION_ID_NOT_FOUND));
    }

    /**
     * A failed read is reported with the detail program's own capitalised failed-read sentence.
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("render the reference's failed-read sentence on the detail read's 500")
    void detailReadReportsFailureWithReferenceSentence() throws Exception {
        when(this.viewService.viewTransaction(anyString())).thenThrow(new IllegalStateException(
                TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION,
                new RuntimeException("driver")));

        this.mockMvc.perform(get(TransactionController.BASE_PATH + "/" + PRESENT_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value(TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION));
    }

    /**
     * A failed browse is reported with the browse program's own lower-case failed-read sentence.
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("render the reference's failed-browse sentence on the list's 500")
    void listReportsFailureWithReferenceSentence() throws Exception {
        when(this.listService.listTransactions(any(), any(), any())).thenThrow(new IllegalStateException(
                TransactionListService.MESSAGE_LOOKUP_FAILED, new RuntimeException("driver")));

        this.mockMvc.perform(get(TransactionController.BASE_PATH).principal(PRINCIPAL))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(TransactionListService.MESSAGE_LOOKUP_FAILED));
    }

    /**
     * The two failed-read sentences differ, so neither operation may be answered with the other's.
     *
     * <p>Assumptions: this is asserted as its own test rather than left implicit in the two above, because
     * the two sentences differ by one character -- the case of a single letter -- and a reader comparing
     * them by eye will not see it. The browse program spells it lower case at line 615 of
     * {@code app/cbl/COTRN00C.cbl} and the detail program capitalises it at line 292 of
     * {@code app/cbl/COTRN01C.cbl}.</p>
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("keep the two failed-read sentences distinct")
    void theTwoFailedReadSentencesAreNotInterchangeable() throws Exception {
        org.assertj.core.api.Assertions
                .assertThat(TransactionListService.MESSAGE_LOOKUP_FAILED)
                .isNotEqualTo(TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION)
                .isEqualToIgnoringCase(TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION);
    }

    /**
     * A successful browse is answered with the page envelope itself.
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("answer a successful browse with the page envelope")
    void listAnswersWithThePageEnvelope() throws Exception {
        when(this.listService.listTransactions(any(), any(), any())).thenReturn(PageResponse.empty());

        this.mockMvc.perform(get(TransactionController.BASE_PATH).principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    /**
     * A capture carrying BOTH keys clears the boundary and reaches the service.
     *
     * <p>Purpose: this is the boundary the account-first precedence has to be reachable through. Line 195
     * of {@code app/cbl/COTRN02C.cbl} opens an {@code EVALUATE TRUE} whose account arm is first, so a
     * submission carrying both keys is resolved through the account identifier and line 209 writes the
     * resolved card number over the submitted one -- with no message. The reference accepts it, so the
     * boundary must accept it.</p>
     *
     * <p>Refactoring Rationale: the request record constrained the pair with EXCLUSIVE disjunction, so a
     * both-keys submission was refused before the handler ran, and the precedence transcribed in
     * {@code TransactionAddService.validateInputKeyFields} was unreachable through the published API for
     * the one case that precedence exists to decide. A parity branch no request can reach is not parity.
     * The service-level case that asserts the precedence would have passed unchanged with the boundary
     * refusing every such request, which is exactly why the assertion belongs HERE, at the wire, where the
     * refusal happened.</p>
     *
     * <p>Assumptions: this class exercises the standalone builder, which registers a Bean Validation
     * provider when one is on the classpath, so the class-level constraint on the request record is
     * genuinely evaluated rather than skipped. The 201 below is therefore evidence that the constraint
     * passed, and not merely that the handler was mapped.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("admit a capture carrying both keys, which the reference resolves account-first")
    void aCaptureCarryingBothKeysReachesTheService() throws Exception {
        when(this.addService.addTransaction(any(TransactionAddRequest.class)))
                .thenReturn(new TransactionAddResponse(PRESENT_ID, Money.of("125.50"), "written"));

        this.mockMvc.perform(post(TransactionController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(captureBody("\"accountId\": \"00000000011\",\n"
                                + "\"cardNumber\": \"4111111111111111\",")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value(PRESENT_ID));
    }

    /**
     * A capture carrying NEITHER key is refused, and the refusal names both key members.
     *
     * <p>Purpose: this is the only condition the reference's key construct reports. Lines 224 to 229 of
     * {@code app/cbl/COTRN02C.cbl} are reached when neither control was filled and line 226 moves
     * {@code 'Account or Card Number must be entered...'}, so the widened constraint has to keep refusing
     * this case while admitting the case above.</p>
     *
     * <p>Assumptions: the two cases are asserted together because widening a constraint is exactly the
     * change that can widen it too far. A constraint that admitted every submission would satisfy the
     * both-keys case on its own, and this one is what stops that reading.</p>
     *
     * <p>Assumptions: the per-field array is asserted rather than only the status, because the validator
     * suppresses the framework's default violation and raises one per key member so a client can display
     * the message beside an input. A violation attributed to a synthetic property name would still answer
     * 400 and would still be undisplayable.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("refuse a capture carrying neither key, naming both key members")
    void aCaptureCarryingNeitherKeyIsRefused() throws Exception {
        this.mockMvc.perform(post(TransactionController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(captureBody("")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        hasItems("accountId", "cardNumber")))
                .andExpect(jsonPath("$.fieldErrors[*].message",
                        hasItem(TransactionAddRequest.KEY_FIELD_REQUIRED)));
    }

    /**
     * Builds a capture body every constraint but the key rule accepts, with the keys supplied by caller.
     *
     * <p>Assumptions: the body is assembled as text rather than serialised from the request record,
     * because a record serialised through the same mapper the handler binds could not express the
     * key-absent case distinctly from the key-present one at the wire -- and the wire is what the
     * constraint is being asserted at. Every other member carries a value the field constraints accept,
     * so a failure below can only be the key rule.</p>
     *
     * @param keyMembers the key members to include, already rendered as JSON member text with a trailing
     *     comma, of type {@link String}, empty to supply neither key; must not be {@code null}
     * @return the request body, never {@code null}
     */
    private static String captureBody(String keyMembers) {
        return "{\n" + keyMembers + "\n"
                + "\"typeCode\": \"01\",\n"
                + "\"categoryCode\": \"0001\",\n"
                + "\"source\": \"POS TERM\",\n"
                + "\"description\": \"GROCERY PURCHASE\",\n"
                + "\"amount\": \"125.50\",\n"
                + "\"merchantId\": \"123456789\",\n"
                + "\"merchantName\": \"CORNER STORE\",\n"
                + "\"merchantCity\": \"SEATTLE\",\n"
                + "\"merchantZip\": \"98101\",\n"
                + "\"originDate\": \"2026-01-15\",\n"
                + "\"processDate\": \"2026-01-16\"\n"
                + "}";
    }
}
