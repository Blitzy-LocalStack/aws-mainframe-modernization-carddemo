package com.carddemo.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
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

    /** The entry point under test. */
    private MockMvc mockMvc;

    /** Builds the controller over stubbed services and registers the shared advice. */
    @BeforeEach
    void setUp() {
        this.listService = mock(TransactionListService.class);
        this.viewService = mock(TransactionViewService.class);
        TransactionAddService addService = mock(TransactionAddService.class);
        CursorToken cursorToken = mock(CursorToken.class);

        TransactionController controller = new TransactionController(this.listService, this.viewService,
                addService, cursorToken);

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
}
