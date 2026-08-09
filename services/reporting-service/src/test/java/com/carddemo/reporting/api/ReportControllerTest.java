package com.carddemo.reporting.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reporting.dto.ReportRequest;
import com.carddemo.reporting.dto.ReportSubmissionResponse;
import com.carddemo.reporting.dto.ReportTotalsResponse;
import com.carddemo.reporting.dto.TransactionReportLineResponse;
import com.carddemo.reporting.repository.TransactionReportRepository;
import com.carddemo.reporting.service.ReportExecutionService;
import com.carddemo.reporting.service.TransactionReportService;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the report surface over a real request pipeline, without a running application context.
 *
 * <p>Alternatives Considered: a sliced web context, which is the form the migration plan names for a
 * controller test. Rejected for this module because its configuration package builds a token decoder
 * that resolves the issuer's discovery document over the network at bean-creation time, so ANY context
 * that includes it fails in an isolated environment for a reason unrelated to the controller under
 * test. A standalone pipeline registers the handler, the argument resolvers, the message converters
 * and the shared exception advice, which is everything these assertions are about, and it excludes
 * exactly the one bean that cannot be built here.
 *
 * <p>Assumptions: the shared exception advice is registered explicitly rather than relied upon, and
 * that registration is the point of several assertions below. A standalone pipeline resolves no
 * {@code @RestControllerAdvice} from a context, so without it a refusal would surface as a raw servlet
 * error and the status and body this contract publishes would go unverified.
 *
 * <p>Assumptions: the money module is registered on the converter for the same reason. Without it a
 * monetary amount serialises as a bare JSON number, which is the one thing the published contract
 * forbids, and a test that did not register it would assert against a payload no deployed service
 * emits.
 */
class ReportControllerTest {

    /** A fixed instant, so a failure body's timestamp is a known value rather than a clock read. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T09:14:27.481903Z");

    /** The verbatim mark the baseline treats as a selection: any non-blank character. */
    private static final String MARK = "Y";

    /**
     * The mapper used to WRITE request bodies in this class.
     *
     * <p>Assumptions: it is deliberately a plain mapper with no money module, because a request body
     * in this context carries no monetary member. Registering the module on the writing side would
     * make this test unable to distinguish a response the service encoded from one this test encoded,
     * which is the property the response assertions rest on.</p>
     */
    private static final JsonMapper REQUEST_MAPPER = JsonMapper.builder().build();

    private ReportExecutionService executions;

    private TransactionReportService reports;

    private MockMvc mockMvc;

    /**
     * Test-only cursor key material, at the sealer's minimum length and fixed for reproducibility.
     *
     * <p>Assumptions: this stands for the signing key a deployment resolves from its secret store. It
     * is declared here rather than defaulted inside the sealer, because a sealer that defaulted a key
     * is how a development default becomes the committed secret a sealed cursor exists to prevent.</p>
     */
    private static final byte[] CURSOR_KEY =
            "carddemo-reporting-cursor-test-k!".repeat(2).getBytes(StandardCharsets.UTF_8);

    /**
     * How long a cursor this class seals stays redeemable.
     *
     * <p>Assumptions: an hour, which is generous for a test and is deliberately not the deployed
     * value. Nothing here asserts expiry -- the cases that matter seal and redeem within one method --
     * so a short lifetime would only make the class fail on a slow machine for a reason unrelated to
     * what it asserts.</p>
     */
    private static final Duration CURSOR_LIFETIME = Duration.ofHours(1);

    /**
     * The authenticated caller every paged request in this class is made as.
     *
     * <p>Assumptions: a principal is supplied because the lines handler takes one -- the page's
     * boundary tokens are bound to the caller's name, so a token issued to one operator cannot be
     * redeemed by another. Supplying it through the request builder keeps this class a plain
     * standalone slice with no security test dependency.</p>
     */
    private static final Principal PRINCIPAL = () -> "11111111-2222-3333-4444-555555555555";

    /**
     * Builds a standalone pipeline over the controller with both collaborators mocked.
     */
    @BeforeEach
    void setUp() {
        executions = Mockito.mock(ReportExecutionService.class);
        reports = Mockito.mock(TransactionReportService.class);

        // WHY : Assumptions: the money module is registered on the converter rather than left out,
        //       because without it a monetary amount serialises as a bare JSON number -- which is the
        //       one encoding the published contract forbids -- and a test asserting against that
        //       payload would be asserting against a shape no deployed service emits.
        JsonMapper mapper = JsonMapper.builder().addModule(new MoneyModule()).build();
        JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter(mapper);

        // WHY : Assumptions: a REAL sealer is built over fixed test key material rather than mocked,
        //       because the boundary tokens this controller returns are opened again by the next
        //       request and a mocked sealer would let a page be sealed with one binding and opened
        //       with another without the test noticing. The key is fixed so a token asserted here is
        //       reproducible from the source alone.
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ReportController(executions, reports, new CursorToken(CURSOR_KEY, CURSOR_LIFETIME)))
                .setMessageConverters(converter)
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * Asserts that a confirmed request answers 200 with the accepted run and the verbatim sentence.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a confirmed request answers 200 carrying the accepted run")
    void aConfirmedRequestAnswersWithTheAcceptedRun() throws Exception {
        ReportRequest request = monthlyRequest("Y");
        LocalDate start = LocalDate.of(2022, 7, 1);
        LocalDate end = LocalDate.of(2022, 7, 18);

        when(executions.resolveReportName(any())).thenReturn("Monthly");
        when(executions.resolveRange(any(), eq("Monthly")))
                .thenReturn(new ReportExecutionService.DateRange(start, end));
        when(executions.isConfirmed(any())).thenReturn(true);
        when(executions.start(any(), eq("Monthly"), eq(start), eq(end)))
                .thenReturn(new ReportSubmissionResponse(
                        "arn:aws:states:us-east-1:000000000000:execution:m:Monthly-1",
                        "Monthly",
                        "Monthly Transaction Report",
                        "Monthly Transaction Detail Report",
                        "2022-07-01",
                        "2022-07-18",
                        "2026-08-05 09:14:27.481903"));

        mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.message")
                        .value("Monthly" + ReportController.SUBMITTED_SUFFIX))
                .andExpect(jsonPath("$.submission.reportName").value("Monthly"));
    }

    // WHY : Assumptions: the assertion that NOTHING was started is as important as the status. A
    //       cancellation answered 200 while still starting a run would look correct to every client
    //       and would run a report the caller declined, so the interaction is verified and not just
    //       the body.
    /**
     * Asserts that a declined request answers 200 with no run, and starts nothing.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a declined request answers 200 with no run and starts nothing")
    void aDeclinedRequestAnswersWithNoRun() throws Exception {
        when(executions.resolveReportName(any())).thenReturn("Monthly");
        when(executions.resolveRange(any(), eq("Monthly")))
                .thenReturn(new ReportExecutionService.DateRange(
                        LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 18)));
        when(executions.isConfirmed(any())).thenReturn(false);

        mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest("N"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(false))
                .andExpect(jsonPath("$.message").value(ReportController.CANCELLED_MESSAGE))
                .andExpect(jsonPath("$.submission").doesNotExist());

        verify(executions, never()).start(any(), any(), any(), any());
    }

    /**
     * Asserts that an unrecognised confirmation answers 400 with the per-field array.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unrecognised confirmation answers 400 with the per-field array")
    void anUnrecognisedConfirmationAnswersWithTheFieldArray() throws Exception {
        when(executions.resolveReportName(any())).thenReturn("Monthly");
        when(executions.resolveRange(any(), eq("Monthly")))
                .thenReturn(new ReportExecutionService.DateRange(
                        LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 18)));
        when(executions.isConfirmed(any())).thenThrow(new ClientInputException(
                ApiError.CODE_VALIDATION, "confirm", "confirm must be Y or N but was of length 1"));

        mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest("Q"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("confirm"))
                .andExpect(jsonPath("$.status").value(400));

        verify(executions, never()).start(any(), any(), any(), any());
    }

    // WHY : Assumptions: the ORDER of the three steps is verified, not merely their outcomes. The
    //       reference resolves a type and a range before it looks at the confirmation, and a
    //       controller that inverted the order would answer a request selecting no report type at all
    //       as a successful cancellation -- a success reported for a request that could never run.
    /**
     * Asserts that a request selecting no report type is refused before the confirmation is read.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("no report type is refused before the confirmation is evaluated")
    void noReportTypeIsRefusedBeforeTheConfirmationIsRead() throws Exception {
        when(executions.resolveReportName(any())).thenThrow(new ClientInputException(
                ApiError.CODE_VALIDATION, "reportType",
                "exactly one of monthly, yearly or custom must be selected but 0 were"));

        ReportRequest request = new ReportRequest(
                null, null, null, null, null, null, null, null, null, null, null, "N", null);

        mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reportType"));

        verify(executions, never()).isConfirmed(any());
        verify(executions, never()).start(any(), any(), any(), any());
    }

    /**
     * Asserts that the lines read and the totals read each answer their own population, money quoted.
     *
     * <p>Refactoring Rationale: the two populations are asserted through TWO requests, and an earlier
     * revision asserted them through one. The published contract declares them as separate
     * operations -- the lines paged and the totals accumulated over the whole range -- so a single
     * request would assert a body no deployed route serves.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the lines and totals reads answer their own populations, with money as quoted text")
    void theDetailReadAnswersLinesAndBands() throws Exception {
        TransactionReportLineResponse line = new TransactionReportLineResponse(
                "0000000000000001",
                "00000000011",
                "01",
                "Purchase       ",
                "0001",
                "Groceries                    ",
                "POS       ",
                Money.of("-1234.56"));
        // WHY : Refactoring Rationale: the lines read is stubbed on readDetailLinePage rather than on
        //       composeDetailLines, and the envelope it returns is the SERVICE's own rather than one
        //       this method assembles from a list. The previous stub returned a bare list and let the
        //       controller wrap it, which is exactly the ordinal-slicing shape the handler no longer
        //       has: asserting through it would have kept passing after the handler stopped being able
        //       to produce the body it asserted.
        when(reports.readDetailLinePage(
                eq(LocalDate.of(2022, 7, 1)), eq(LocalDate.of(2022, 7, 31)),
                eq(null), eq(false), any()))
                .thenAnswer(invocation -> {
                    TransactionReportRepository.CursorSealer sealer = invocation.getArgument(4);
                    return PageResponse.ofRows(List.of(line),
                            sealer.seal("0000000000000001", true),
                            sealer.seal("0000000000000001", false),
                            false,
                            false);
                });
        when(reports.composeTotals(LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31)))
                .thenReturn(List.of(
                        new ReportTotalsResponse(
                                ReportTotalsResponse.Band.GRAND, "Grand Total", Money.of("-1234.56"))));

        mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                        .principal(PRINCIPAL)
                        .param("startDate", "2022-07-01")
                        .param("endDate", "2022-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].transactionId").value("0000000000000001"))
                .andExpect(jsonPath("$.items[0].amount").value("-1234.56"))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(false))
                .andExpect(jsonPath("$.firstKey").isNotEmpty())
                .andExpect(jsonPath("$.lastKey").isNotEmpty());

        mockMvc.perform(get(ReportController.BASE_PATH + ReportController.TOTALS_PATH)
                        .param("startDate", "2022-07-01")
                        .param("endDate", "2022-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bands[0].band").value("GRAND"))
                .andExpect(jsonPath("$.bands[0].label").value("Grand Total"))
                .andExpect(jsonPath("$.bands[0].amount").value("-1234.56"));
    }

    // WHY : Assumptions: the amount is asserted as a quoted STRING and not merely as the right digits.
    //       A component holding a correct value under a decimal type compiles, runs, and emits a bare
    //       JSON number that looks entirely correct; only the quoting reveals the difference, and the
    //       difference is what keeps a client from parsing an amount into a binary double.
    /**
     * Asserts that a monetary amount reaches the wire quoted rather than as a JSON number.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a monetary amount reaches the wire quoted")
    void aMonetaryAmountReachesTheWireQuoted() throws Exception {
        when(reports.readDetailLinePage(any(), any(), any(), eq(false), any()))
                .thenReturn(PageResponse.empty());
        when(reports.composeTotals(any(), any())).thenReturn(List.of());

        String lines = mockMvc.perform(
                        get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                                .principal(PRINCIPAL)
                                .param("startDate", "2022-07-01")
                                .param("endDate", "2022-07-31"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String totals = mockMvc.perform(
                        get(ReportController.BASE_PATH + ReportController.TOTALS_PATH)
                                .param("startDate", "2022-07-01")
                                .param("endDate", "2022-07-31"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(lines)
                .as("an empty range answers 200 with an empty page rather than 404, because a query"
                        + " that matched nothing succeeded")
                .contains("\"items\":[]")
                .contains("\"hasNext\":false");
        org.assertj.core.api.Assertions.assertThat(totals)
                .as("an empty range answers 200 with no band rather than 404")
                .contains("\"bands\":[]");
    }

    /**
     * Asserts that a date query parameter that is not a calendar date answers 400.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a non-calendar date query parameter answers 400 naming the parameter")
    void aNonCalendarDateAnswersBadRequest() throws Exception {
        mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                        .principal(PRINCIPAL)
                        .param("startDate", "2022-13-45")
                        .param("endDate", "2022-07-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("startDate"));

        verify(reports, never()).readDetailLinePage(any(), any(), any(), anyBoolean(), any());
    }

    // WHY : Assumptions: the refusal is asserted through the HANDLER rather than through the service.
    //       The service refuses a backward step with no cursor too, but it refuses with an
    //       IllegalArgumentException, which would reach a caller as a 500; only the handler's own check
    //       produces the 400 the contract publishes, so a test that reached the service would assert a
    //       status no client ever sees.
    /**
     * Asserts that a paging direction sent without a cursor answers 400 and reads nothing.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a paging direction with no cursor answers 400 naming the cursor")
    void aDirectionWithoutACursorAnswersBadRequest() throws Exception {
        mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                        .principal(PRINCIPAL)
                        .param("startDate", "2022-07-01")
                        .param("endDate", "2022-07-31")
                        .param("direction", ReportController.PREVIOUS_DIRECTION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cursor"));

        verify(reports, never()).readDetailLinePage(any(), any(), any(), anyBoolean(), any());
    }

    // WHY : Assumptions: the token is opened by a REAL sealer bound to a REAL principal, so this case
    //       proves the binding rather than the plumbing. A cursor minted for one operator and replayed
    //       by another has to be refused, and with a mocked sealer both requests would succeed and the
    //       test would prove nothing about who may redeem a page boundary.
    /**
     * Asserts that a boundary token minted for one caller cannot be redeemed by another.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a boundary token minted for one caller is refused for another")
    void aBoundaryTokenIsRefusedForAnotherCaller() throws Exception {
        when(reports.readDetailLinePage(any(), any(), any(), eq(false), any()))
                .thenAnswer(invocation -> {
                    TransactionReportRepository.CursorSealer sealer = invocation.getArgument(4);
                    return PageResponse.ofRows(List.of(),
                            sealer.seal("0000000000000001", true),
                            sealer.seal("0000000000000009", false), true, false);
                });

        String issued = REQUEST_MAPPER.readTree(
                        mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                                        .principal(PRINCIPAL)
                                        .param("startDate", "2022-07-01")
                                        .param("endDate", "2022-07-31"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .get("lastKey").asString();

        mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                        .principal(() -> "99999999-8888-7777-6666-555555555555")
                        .param("startDate", "2022-07-01")
                        .param("endDate", "2022-07-31")
                        .param("cursor", issued))
                .andExpect(status().isBadRequest());
    }

    // WHY : Assumptions: the DIRECTION is part of the binding too, and this case is what makes the
    //       published contract sentence true rather than aspirational -- reporting-api.yaml states that
    //       the direction a position was issued for is sealed into it, so replaying a trailing position
    //       backward is refused rather than answered with the wrong page. Answering it would walk the
    //       caller past rows it never saw, which is invisible from the response.
    /**
     * Asserts that a trailing boundary token cannot be replayed as a backward step.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a trailing boundary token is refused when replayed backward")
    void aTrailingTokenIsRefusedWhenReplayedBackward() throws Exception {
        when(reports.readDetailLinePage(any(), any(), any(), eq(false), any()))
                .thenAnswer(invocation -> {
                    TransactionReportRepository.CursorSealer sealer = invocation.getArgument(4);
                    return PageResponse.ofRows(List.of(),
                            sealer.seal("0000000000000001", true),
                            sealer.seal("0000000000000009", false), true, false);
                });

        String body = mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                        .principal(PRINCIPAL)
                        .param("startDate", "2022-07-01")
                        .param("endDate", "2022-07-31"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String trailing = REQUEST_MAPPER.readTree(body).get("lastKey").asString();
        String leading = REQUEST_MAPPER.readTree(body).get("firstKey").asString();

        mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                        .principal(PRINCIPAL)
                        .param("startDate", "2022-07-01")
                        .param("endDate", "2022-07-31")
                        .param("cursor", trailing)
                        .param("direction", ReportController.PREVIOUS_DIRECTION))
                .andExpect(status().isBadRequest());

        // WHY : Assumptions: the LEADING token is asserted to be accepted backward in the same case, so
        //       the refusal above cannot pass by refusing every backward step. A one-sided assertion
        //       would be satisfied by an implementation that had broken backward paging outright.
        // WHY : Assumptions: this second stub is written in the doReturn form, and the difference is not
        //       stylistic. The when form INVOKES the method it is describing, and during that invocation
        //       Mockito supplies each matcher's default -- false for the boolean -- so the call matches
        //       the forward stub registered above and runs its answer with a null sealer. The doReturn
        //       form registers without invoking.
        org.mockito.Mockito.doReturn(PageResponse.empty()).when(reports)
                .readDetailLinePage(any(), any(), any(), eq(true), any());
        mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                        .principal(PRINCIPAL)
                        .param("startDate", "2022-07-01")
                        .param("endDate", "2022-07-31")
                        .param("cursor", leading)
                        .param("direction", ReportController.PREVIOUS_DIRECTION))
                .andExpect(status().isOk());
    }

    // WHY : Assumptions: the range is part of the binding too, so a token issued over one range cannot
    //       be replayed over another. Without that, a caller holding a boundary from a narrow range
    //       could widen the range and keep walking from a position that means something different in
    //       the wider one.
    /**
     * Asserts that a boundary token issued over one range is refused over another.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a boundary token issued over one range is refused over another")
    void aBoundaryTokenIsRefusedOverAnotherRange() throws Exception {
        when(reports.readDetailLinePage(any(), any(), any(), eq(false), any()))
                .thenAnswer(invocation -> {
                    TransactionReportRepository.CursorSealer sealer = invocation.getArgument(4);
                    return PageResponse.ofRows(List.of(),
                            sealer.seal("0000000000000001", true),
                            sealer.seal("0000000000000009", false), true, false);
                });

        String issued = REQUEST_MAPPER.readTree(
                        mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                                        .principal(PRINCIPAL)
                                        .param("startDate", "2022-07-01")
                                        .param("endDate", "2022-07-31"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .get("lastKey").asString();

        mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                        .principal(PRINCIPAL)
                        .param("startDate", "2022-07-01")
                        .param("endDate", "2022-08-31")
                        .param("cursor", issued))
                .andExpect(status().isBadRequest());
    }

    /**
     * Builds a monthly report request carrying one confirmation answer.
     *
     * @param confirm the confirmation answer to carry
     * @return the request
     */
    private static ReportRequest monthlyRequest(String confirm) {
        return new ReportRequest(
                null, null, null, null, null, null, MARK, null, null, null, null, confirm, null);
    }
}
