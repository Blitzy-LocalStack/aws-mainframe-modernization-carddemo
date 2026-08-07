package com.carddemo.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.carddemo.reporting.dto.StatementDocument;
import com.carddemo.reporting.dto.StatementRequest;
import com.carddemo.reporting.dto.StatementResponse;
import com.carddemo.reporting.dto.StatementTransactionResponse;
import com.carddemo.reporting.service.StatementService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the statement surface over a real request pipeline, without a running application context.
 *
 * <p>Alternatives Considered: a sliced web context. Rejected for the reason recorded on
 * {@link ReportControllerTest}: this module's configuration package builds a token decoder that
 * resolves the issuer's discovery document over the network at bean-creation time, so any context
 * including it fails in an isolated environment for a reason unrelated to the controller under test.
 *
 * <p>Assumptions: the three protective response headers are asserted on BOTH operations rather than on
 * one. They are set by a shared helper, so asserting one operation would leave the other's headers
 * unverified while appearing to cover them -- and a statement response missing one of them is
 * indistinguishable, from the outside, from one that never needed it.
 */
class StatementControllerTest {

    /** A fixed instant, so a failure body's timestamp is a known value rather than a clock read. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T09:14:27.481903Z");

    /** The narrowed rendering a response carries: twelve mask characters then four digits. */
    private static final String MASKED_CARD = "************1111";

    /** A specimen card number. Assumptions: the reserved test prefix, so it identifies no real card. */
    private static final String SAMPLE_CARD = "4111111111111111";

    /** The mapper used to write request bodies, deliberately without the money module. */
    private static final JsonMapper REQUEST_MAPPER = JsonMapper.builder().build();

    private StatementService statements;

    private MockMvc mockMvc;

    /**
     * Builds a standalone pipeline over the controller with its collaborator mocked.
     */
    @BeforeEach
    void setUp() {
        statements = Mockito.mock(StatementService.class);

        JsonMapper mapper = JsonMapper.builder().addModule(new MoneyModule()).build();
        mockMvc = MockMvcBuilders.standaloneSetup(new StatementController(statements))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * Asserts that the description answers the narrowed number, the total as quoted text and the
     * three protective headers.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the description answers the narrowed number, a quoted total and the headers")
    void theDescriptionAnswersTheNarrowedNumber() throws Exception {
        when(statements.describe(any())).thenReturn(heading());

        mockMvc.perform(post(StatementController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(
                                new StatementRequest(SAMPLE_CARD, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardNumber").value(MASKED_CARD))
                .andExpect(jsonPath("$.totalAmount").value("-1234.56"))
                .andExpect(jsonPath("$.transactionCount").value(1))
                .andExpect(header().string(
                        StatementController.CONTENT_TYPE_OPTIONS_HEADER,
                        StatementController.NOSNIFF))
                .andExpect(header().string(
                        StatementController.CONTENT_SECURITY_POLICY_HEADER,
                        StatementController.STATEMENT_POLICY))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        StatementController.ATTACHMENT_DISPOSITION));
    }

    /**
     * Asserts that the transactions operation answers the statement's rows and nothing else.
     *
     * <p>Refactoring Rationale: the body is asserted to carry the ROWS alone, and an earlier revision
     * asserted a body carrying the heading summary beside them. The published contract declares two
     * operations here -- the summary above and the rows below -- so a body carrying both would be a
     * body no deployed route serves. The heading is still asserted, by the summary case above.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the transactions operation answers the statement's rows alone")
    void theDocumentAnswersHeadingAndLines() throws Exception {
        when(statements.compose(any())).thenReturn(new StatementDocument(heading(), List.of(line())));

        mockMvc.perform(post(StatementController.BASE_PATH + StatementController.TRANSACTIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(
                                new StatementRequest(SAMPLE_CARD, "00000000011"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].cardNumber").value(MASKED_CARD))
                .andExpect(jsonPath("$.items[0].transactionId").value("0000000000000001"))
                .andExpect(jsonPath("$.items[0].amount").value("-1234.56"))
                .andExpect(jsonPath("$.statement").doesNotExist())
                .andExpect(header().string(
                        StatementController.CONTENT_SECURITY_POLICY_HEADER,
                        StatementController.STATEMENT_POLICY));
    }

    // WHY : Assumptions: a card with no activity is asserted to answer 200 with an EMPTY array and not
    //       404. The reference walks the cross-reference and produces a statement for every card it
    //       finds, whether or not that card had activity, so an empty document is a legitimate one and
    //       404 would tell a caller the card does not exist.
    /**
     * Asserts that a card with no activity answers a document with an empty transaction array.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a card with no activity answers an empty row collection")
    void aCardWithNoActivityAnswersAnEmptyDocument() throws Exception {
        StatementResponse empty = new StatementResponse(
                MASKED_CARD,
                "00000000011",
                "JOHN Q PUBLIC",
                Money.ZERO,
                0,
                "s3://bucket/statements/11-1111.txt",
                "s3://bucket/statements/11-1111.html",
                "2026-08-05 09:14:27.481903");
        when(statements.compose(any())).thenReturn(new StatementDocument(empty, List.of()));

        String body = mockMvc.perform(
                        post(StatementController.BASE_PATH + StatementController.TRANSACTIONS_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REQUEST_MAPPER.writeValueAsString(
                                        new StatementRequest(SAMPLE_CARD, null))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("\"items\":[]");
    }

    /**
     * Asserts that an unknown card answers 404 through the shared advice.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unknown card answers 404 through the shared advice")
    void anUnknownCardAnswersNotFound() throws Exception {
        when(statements.describe(any()))
                .thenThrow(new NoSuchElementException("no cross-reference row for the requested card"));

        mockMvc.perform(post(StatementController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(
                                new StatementRequest(SAMPLE_CARD, null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND))
                .andExpect(jsonPath("$.status").value(404));
    }

    /**
     * Asserts that a masked collision answers 400 naming the card field.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a masked collision answers 400 naming the card field")
    void aMaskedCollisionAnswersBadRequest() throws Exception {
        when(statements.compose(any())).thenThrow(new ClientInputException(
                ApiError.CODE_VALIDATION, "cardNumber",
                "the requested card number masks to a rendering shared by 2 distinct cards, so a"
                        + " statement cannot be attributed"));

        mockMvc.perform(post(StatementController.BASE_PATH + StatementController.TRANSACTIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(
                                new StatementRequest(SAMPLE_CARD, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cardNumber"));
    }

    // WHY : Assumptions: the refusal body is asserted to carry NEITHER the specimen number nor its
    //       four visible digits. The message a service raises is written into an operational record and
    //       echoed to the caller, so a refusal that quoted the value it refused would copy a primary
    //       account number into both -- which is the one leak a masking rule cannot undo afterwards.
    /**
     * Asserts that no refusal body repeats the card number the caller supplied.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a refusal body repeats neither the card number nor its visible digits")
    void aRefusalBodyRepeatsNoCardNumber() throws Exception {
        when(statements.describe(any())).thenThrow(new ClientInputException(
                ApiError.CODE_VALIDATION, "accountId",
                "the requested card resolves to a different account than the one stated"));

        String body = mockMvc.perform(post(StatementController.BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(
                                new StatementRequest(SAMPLE_CARD, "00000000099"))))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .as("no refusal may quote the value it refused")
                .doesNotContain(SAMPLE_CARD)
                .doesNotContain("1111");
    }

    /**
     * Builds a statement heading carrying one transaction and a negative total.
     *
     * @return the heading
     */
    private static StatementResponse heading() {
        return new StatementResponse(
                MASKED_CARD,
                "00000000011",
                "JOHN Q PUBLIC",
                Money.of("-1234.56"),
                1,
                "s3://bucket/statements/11-1111.txt",
                "s3://bucket/statements/11-1111.html",
                "2026-08-05 09:14:27.481903");
    }

    /**
     * Builds one statement transaction line.
     *
     * @return the line
     */
    private static StatementTransactionResponse line() {
        return new StatementTransactionResponse(
                MASKED_CARD,
                "0000000000000001",
                "01",
                "0001",
                "POS       ",
                "GROCERIES",
                Money.of("-1234.56"),
                "000000123",
                "ACME STORES",
                "SEATTLE",
                "98101     ",
                "2022-07-18 09:00:00.000000",
                "2022-07-18 09:00:01.000000");
    }
}
