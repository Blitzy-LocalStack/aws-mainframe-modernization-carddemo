package com.carddemo.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.transaction.dto.BillPaymentResponse;
import com.carddemo.transaction.mapper.BillPaymentMapper;
import com.carddemo.transaction.service.BillPaymentService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the payment operation answers each outcome with the sentence its contract publishes.
 *
 * <p>Assumptions: the payment service is stubbed, so these tests are about the wire shape each outcome
 * produces and not about the order the service evaluates its branches in -- that order is asserted against
 * the service itself, where the reference's own line numbers can be cited beside each branch.</p>
 */
@DisplayName("the payment operation's published outcomes")
class BillPaymentControllerTest {

    /** A fixed instant so the rendered problem timestamp is deterministic. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-18T12:00:00Z");

    /** A well-formed eleven digit account identifier. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A submission carrying an affirmative confirmation. */
    private static final String CONFIRMED_BODY =
            "{\"accountId\":\"00000000011\",\"confirmation\":\"Y\"}";

    /**
     * A submission that supplies no confirmation at all, which is the never-confirmed state.
     *
     * <p>Assumptions: the property is omitted rather than sent empty, because the request type caps the
     * confirmation at one character and leaves a null value valid, so omission is how a caller expresses
     * the state the baseline reaches for spaces or low values.</p>
     */
    private static final String WITHHELD_BODY = "{\"accountId\":\"00000000011\"}";

    /** The payment this controller delegates to, stubbed per test. */
    private BillPaymentService billPaymentService;

    /** The entry point under test. */
    private MockMvc mockMvc;

    /** Builds the controller over a stubbed service and registers the shared advice. */
    @BeforeEach
    void setUp() {
        this.billPaymentService = mock(BillPaymentService.class);
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new BillPaymentController(this.billPaymentService))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(
                        JsonMapper.builder().addModule(new MoneyModule()).build()))
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * A never-supplied account identifier is refused with the payment program's own sentence and the
     * blank state.
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("render the reference's empty-account sentence with the blank state")
    void refusesBlankAccountWithReferenceSentence() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenThrow(new ClientInputException(
                ApiError.CODE_VALIDATION, BillPaymentMapper.ACCOUNT_ID_FIELD,
                FieldValidationFlag.BLANK, BillPaymentMapper.MESSAGE_ACCOUNT_ID_EMPTY));

        this.mockMvc.perform(post(BillPaymentController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMED_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(BillPaymentMapper.MESSAGE_ACCOUNT_ID_EMPTY))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value(BillPaymentMapper.ACCOUNT_ID_FIELD))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.BLANK.name()));
    }

    /**
     * An unknown account is reported with the payment program's own not-found sentence.
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("render the reference's not-found sentence on a 404")
    void reportsAbsentAccountWithReferenceSentence() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenThrow(
                new NoSuchElementException(BillPaymentMapper.MESSAGE_ACCOUNT_NOT_FOUND));

        this.mockMvc.perform(post(BillPaymentController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMED_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value(BillPaymentMapper.MESSAGE_ACCOUNT_NOT_FOUND));
    }

    /**
     * A failed account update is reported with the payment program's own failed-update sentence.
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("render the reference's failed-update sentence on a 500")
    void reportsFailedUpdateWithReferenceSentence() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenThrow(new IllegalStateException(
                BillPaymentMapper.MESSAGE_ACCOUNT_UPDATE_FAILED, new RuntimeException("seam")));

        this.mockMvc.perform(post(BillPaymentController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMED_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value(BillPaymentMapper.MESSAGE_ACCOUNT_UPDATE_FAILED));
    }

    /**
     * A posted payment is answered 201 with the acknowledgement, the money as a quoted string and the
     * location of the transaction that was written.
     *
     * <p>Refactoring Rationale: the status asserted here is 201 and not 200, and the change corrects an
     * expectation that agreed with an earlier revision of the adapter rather than with the contract. The
     * published document declares 201 for the outcome that writes a record and marks its {@code Location}
     * header required, and the browser client at {@code ui/src/api/transactions.ts} decides which of the
     * two outcomes occurred from the status alone -- it reports a payment only when it reads 201. An
     * expectation of 200 therefore locked in a surface on which a completed payment was rendered to the
     * operator as a preview that had paid nothing.</p>
     *
     * <p>Assumptions: the header is asserted by value and not merely for presence, because a value of the
     * wrong form is the failure that matters. The contract declares the form
     * {@code /api/v1/transactions/{transactionId}}, which is the path the sibling adapter serves its
     * member read on, so a caller following the header has to arrive at the transaction the payment
     * wrote.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("answer a posted payment 201 with its acknowledgement, quoted money and location")
    void answersPostedPaymentWithQuotedMoney() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenReturn(BillPaymentResponse.posted(
                ACCOUNT_ID, Money.of("123.45"), "0000000000000009", "Payment successful."));

        this.mockMvc.perform(post(BillPaymentController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRMED_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        "/api/v1/transactions/0000000000000009"))
                .andExpect(jsonPath("$.paid").value(true))
                .andExpect(jsonPath("$.currentBalance").value("123.45"));
    }

    /**
     * A submission whose confirmation was withheld is answered 200, reports that nothing was paid, and
     * carries no location.
     *
     * <p>Assumptions: this case exists to hold the other side of the discrimination the case above
     * asserts. A single case fixing only the written outcome would still be satisfied by an adapter that
     * answered 201 to everything, which would tell the client that a preview had taken money. The
     * absence of the header is asserted for the same reason: it is declared required on the written
     * outcome alone, so sending one here would address a transaction that was never written.</p>
     *
     * <p>Assumptions: the withheld state is an absent confirmation rather than a blank one, matching the
     * baseline's own branch at {@code app/cbl/COBIL00C.cbl} lines 182 to 184, which reads the account and
     * falls through to display the balance for a confirmation of spaces or low values. The prompt the
     * body carries is the sentence that program moves at line 237.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("answer a withheld confirmation 200 with nothing paid and no location")
    void answersWithheldConfirmationWithoutPaying() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenReturn(new BillPaymentResponse(
                null, ACCOUNT_ID, Money.of("123.45"), false,
                BillPaymentMapper.MESSAGE_CONFIRM_PAYMENT));

        this.mockMvc.perform(post(BillPaymentController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WITHHELD_BODY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.paid").value(false))
                .andExpect(jsonPath("$.currentBalance").value("123.45"))
                .andExpect(jsonPath("$.returnMessage")
                        .value(BillPaymentMapper.MESSAGE_CONFIRM_PAYMENT));
    }
}
