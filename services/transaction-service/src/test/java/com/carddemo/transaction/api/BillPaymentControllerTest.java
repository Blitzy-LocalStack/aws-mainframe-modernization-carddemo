package com.carddemo.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.carddemo.transaction.dto.BillPaymentPreview;
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
import org.springframework.dao.DataAccessResourceFailureException;
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
 *
 * <p>Trade-offs: stubbing the service means NO effect of a payment is reached from here, and naming where
 * those effects ARE reached is part of this class's contract rather than a courtesy to the reader. A
 * review of an earlier revision recorded that the account boundary was mocked everywhere it appeared, so
 * a suite of stubbed controller cases sitting above a stubbed service read as coverage of a path that
 * nothing exercised. Two classes carry the rest:
 * {@code com.carddemo.transaction.service.BillPaymentServiceTest} asserts the branch order and the
 * sentence selected on each branch against the reference's line numbers, and
 * {@code com.carddemo.transaction.repository.BillPaymentAtomicityIT} drives the real service against a
 * real PostgreSQL engine and reads the ledger row and the account balance back to prove they commit
 * together and roll back together. What remains here is the mapping from an outcome to a status, a
 * header and a body -- which is exactly what a stubbed service is the right instrument for, because a
 * status mapping asserted through a real database would fail for reasons that have nothing to do with
 * the mapping.</p>
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

    /** A submission that refuses the payment outright, which is the reference's own line 178 branch. */
    private static final String REFUSED_BODY =
            "{\"accountId\":\"00000000011\",\"confirmation\":\"N\"}";

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
     * <p>Assumptions: the cause carried under the sentence is a data-access failure, which is what this
     * condition now arises from. The service reaches the balance with a statement on its own transaction's
     * connection, so the failure it wraps is the framework's translated data-access exception; an earlier
     * revision named the cause after a remote seam, which was accurate while the change was an HTTP call
     * and became a misdescription of the only failure this path can now report.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("render the reference's failed-update sentence on a 500")
    void reportsFailedUpdateWithReferenceSentence() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenThrow(new IllegalStateException(
                BillPaymentMapper.MESSAGE_ACCOUNT_UPDATE_FAILED,
                new DataAccessResourceFailureException("the balance statement did not complete")));

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
     * <p>⚠️ Refactoring Rationale: the stubbed answer is now {@link BillPaymentPreview} rather than a
     * {@link BillPaymentResponse} whose identifier was null and whose {@code paid} member was false, and
     * the substitution is the point rather than a detail. The adapter chooses its status by matching the
     * outcome's TYPE, because the previous form -- one record for both turns, discriminated by a nullable
     * identifier -- meant the required {@code Location} header depended on a member that is absent on
     * every non-paying turn, and the published acknowledgement schema declared that identifier required
     * while the service was filling it with null. A stub carrying the old shape would now be arranging a
     * state the service cannot produce.</p>
     *
     * <p>Assumptions: the balance is asserted on the preview body as a quoted string, because a reporting
     * turn has to show the operator the figure a confirmation would pay -- the reference displays it at
     * lines 193 and 194 before the prompt at line 237. The quoting is transformation rule T3: a JSON
     * number would be parsed into a double by most clients.</p>
     *
     * <p>Assumptions: the withheld state is an absent confirmation rather than a blank one, matching the
     * baseline's own branch at {@code app/cbl/COBIL00C.cbl} lines 182 to 184, which reads the account and
     * falls through to display the balance for a confirmation of spaces or low values. The prompt the
     * body carries is the sentence that program moves at line 237.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("answer a withheld confirmation 200 with the payable balance, nothing paid, no location")
    void answersWithheldConfirmationWithoutPaying() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any())).thenReturn(BillPaymentPreview.reporting(
                ACCOUNT_ID, Money.of("123.45"), BillPaymentMapper.MESSAGE_CONFIRM_PAYMENT));

        this.mockMvc.perform(post(BillPaymentController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WITHHELD_BODY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.paid").value(false))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.payableBalance").value("123.45"))
                .andExpect(jsonPath("$.returnMessage")
                        .value(BillPaymentMapper.MESSAGE_CONFIRM_PAYMENT))
                // WHY : Assumptions: the identifier property is asserted ABSENT rather than null, because
                //       the preview schema closes its object and declares no such property. A body
                //       carrying it as null would still satisfy a paid-is-false assertion while telling a
                //       client that a transaction identifier was expected here and could not be produced.
                .andExpect(jsonPath("$.transactionId").doesNotExist());
    }

    /**
     * A refused confirmation is answered 200 carrying neither a balance nor a sentence.
     *
     * <p>Purpose: this holds the third of the four turns the operation can take, and it is the one whose
     * body is emptiest -- the reference's refusal branch at lines 178 to 181 of
     * {@code app/cbl/COBIL00C.cbl} performs {@code CLEAR-CURRENT-SCREEN} at line 180 and moves nothing
     * into the message field, so the operator sees a blanked screen and no text.</p>
     *
     * <p>Assumptions: both absences are asserted, and asserting only one would miss the likelier defect.
     * A sentence invented here -- "payment cancelled" being the obvious candidate -- would put text in
     * front of an operator that no line of the reference emits, which transformation rule T8 forbids; and
     * a balance reported here would show a figure line 180 has just removed from view.</p>
     *
     * @throws Exception if the request could not be performed
     */
    @Test
    @DisplayName("answer a refused confirmation 200 with no balance and no sentence")
    void answersRefusedConfirmationWithACearedBody() throws Exception {
        when(this.billPaymentService.payBalanceInFull(any()))
                .thenReturn(BillPaymentPreview.cleared(ACCOUNT_ID));

        this.mockMvc.perform(post(BillPaymentController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFUSED_BODY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.paid").value(false))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.payableBalance").doesNotExist())
                .andExpect(jsonPath("$.returnMessage").doesNotExist());
    }
}
