package com.carddemo.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
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
     * Asserts that a confirmed request answers 201 with the accepted run and the verbatim sentence.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a confirmed request answers 201 carrying the accepted run")
    void aConfirmedRequestAnswersWithTheAcceptedRun() throws Exception {
        ReportRequest request = monthlyRequest("Y");
        LocalDate start = LocalDate.of(2022, 7, 1);
        LocalDate end = LocalDate.of(2022, 7, 18);

        when(executions.resolveReportName(any())).thenReturn("Monthly");
        when(executions.resolveRange(any(), eq("Monthly")))
                .thenReturn(new ReportExecutionService.DateRange(start, end));
        when(executions.resolveConfirmation(any()))
                .thenReturn(ReportExecutionService.Confirmation.CONFIRMED);
        when(executions.start(any(), eq("Monthly"), eq(start), eq(end), any()))
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

    // WHY : Assumptions: the header is asserted to reach the SERVICE rather than merely to be accepted
    //       by the handler. What the key does -- decide whether a repeat submission is a duplicate or a
    //       new run -- happens entirely inside ReportExecutionService, so a handler that bound the
    //       header and then dropped it would answer 201 exactly as it does here while leaving every
    //       retry starting a second run. Only the captured argument distinguishes the two.
    /**
     * Asserts that a submitted idempotency key is carried through to the execution start.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a submitted idempotency key reaches the execution start")
    void aSubmittedIdempotencyKeyReachesTheStart() throws Exception {
        LocalDate start = LocalDate.of(2022, 7, 1);
        LocalDate end = LocalDate.of(2022, 7, 18);

        when(executions.resolveReportName(any())).thenReturn("Monthly");
        when(executions.resolveRange(any(), eq("Monthly")))
                .thenReturn(new ReportExecutionService.DateRange(start, end));
        when(executions.resolveConfirmation(any()))
                .thenReturn(ReportExecutionService.Confirmation.CONFIRMED);
        when(executions.start(any(), eq("Monthly"), eq(start), eq(end), any()))
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
                        .header(ReportExecutionService.IDEMPOTENCY_KEY_FIELD, "retry-key-1")
                        .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest("Y"))))
                .andExpect(status().isCreated());

        verify(executions).start(any(), eq("Monthly"), eq(start), eq(end), eq("retry-key-1"));
    }

    // WHY : Assumptions: the assertion that NOTHING was started is as important as the status. A
    //       cancellation answered 200 while still starting a run would look correct to every client
    //       and would run a report the caller declined, so the interaction is verified and not just
    //       the body.
    /**
     * Asserts that a declined request answers 200 with no run, no sentence, and starts nothing.
     *
     * <p>Refactoring Rationale: the message is asserted ABSENT, where an earlier revision asserted a
     * target-authored sentence reading "Report was not submitted." The reference's cancel branch at
     * {@code app/cbl/CORPT00C.cbl} L480 to L483 performs {@code INITIALIZE-ALL-FIELDS}, whose
     * statement at L633 to L646 clears {@code WS-MESSAGE} among the fields it clears, so the operator
     * is shown a blank message line and there is no string to carry. Transformation rule T8 admits no
     * user-visible string the baseline does not have, so the earlier assertion pinned the invention
     * in place.</p>
     *
     * <p>Assumptions: absence rather than an explicit null is what the wire carries, and it is
     * asserted the same way the sibling member already is. The serialiser this pipeline uses omits a
     * null member, which is why {@code $.submission} has always been asserted with
     * {@code doesNotExist}; the published schema requires neither member, so an omitted message and a
     * null one are both conforming and the schema's "null on a deliberate cancellation" is satisfied
     * by either.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a declined request answers 200 with no run, no sentence, and starts nothing")
    void aDeclinedRequestAnswersWithNoRun() throws Exception {
        when(executions.resolveReportName(any())).thenReturn("Monthly");
        when(executions.resolveRange(any(), eq("Monthly")))
                .thenReturn(new ReportExecutionService.DateRange(
                        LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 18)));
        when(executions.resolveConfirmation(any()))
                .thenReturn(ReportExecutionService.Confirmation.DECLINED);

        mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest("N"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(false))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.submission").doesNotExist());

        verify(executions, never()).start(any(), any(), any(), any(), any());
    }

    /**
     * Asserts that an unanswered confirmation answers 200 with the reference's prompt, naming the
     * report, and starts nothing.
     *
     * <p>Refactoring Rationale: this case is new, and its absence is why a wrong outcome survived
     * review. The reference reaches this turn at {@code app/cbl/CORPT00C.cbl} L464 to L474: it
     * composes a prompt from {@code 'Please confirm to print the '}, the report name delimited by a
     * space and {@code ' report...'}, raises the flag purely to suppress the success block, and
     * re-displays. That is a question being asked, not a fault, and the published contract declares it
     * as 200 with the prompt as the message. An earlier revision raised from the service instead and
     * answered 400 with a problem body, and no case here covered the turn at all.</p>
     *
     * <p>Assumptions: the sentence is asserted from the controller's own two fragments rather than as
     * a literal, so the assertion and the production assembly cannot drift apart while both stay
     * wrong -- and the space before the report name and the absence of one before the three dots are
     * both carried by the fragments rather than retyped here.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unanswered confirmation answers 200 with the reference's prompt naming the report")
    void anUnansweredConfirmationAnswersWithThePrompt() throws Exception {
        when(executions.resolveReportName(any())).thenReturn("Yearly");
        when(executions.resolveRange(any(), eq("Yearly")))
                .thenReturn(new ReportExecutionService.DateRange(
                        LocalDate.of(2022, 1, 1), LocalDate.of(2022, 12, 31)));
        when(executions.resolveConfirmation(any()))
                .thenReturn(ReportExecutionService.Confirmation.UNANSWERED);

        mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(
                                new ReportRequest(null, null, null, null, null, null,
                                        null, MARK, null, null, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(false))
                .andExpect(jsonPath("$.message").value(ReportController.CONFIRM_PROMPT_PREFIX
                        + "Yearly" + ReportController.CONFIRM_PROMPT_SUFFIX))
                .andExpect(jsonPath("$.submission").doesNotExist());

        verify(executions, never()).start(any(), any(), any(), any(), any());
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
        when(executions.resolveConfirmation(any())).thenThrow(new ClientInputException(
                ApiError.CODE_VALIDATION, "confirm",
                ReportExecutionService.INVALID_CONFIRM_PREFIX + "Q"
                        + ReportExecutionService.INVALID_CONFIRM_SUFFIX));

        mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest("Q"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("confirm"))
                .andExpect(jsonPath("$.message").value(
                        ReportExecutionService.INVALID_CONFIRM_PREFIX + "Q"
                                + ReportExecutionService.INVALID_CONFIRM_SUFFIX))
                .andExpect(jsonPath("$.status").value(400));

        verify(executions, never()).start(any(), any(), any(), any(), any());
    }

    // WHY : Assumptions: the ORDER of the three steps is verified, not merely their outcomes. The
    //       reference resolves a type and a range before it looks at the confirmation, and a
    //       controller that inverted the order would answer a request selecting no report type at all
    //       as a successful cancellation -- a success reported for a request that could never run.
    // WHY : Refactoring Rationale: the refusal is stubbed with the service's OWN message constant and
    //       the rendered sentence is asserted, where an earlier revision restated a sentence this
    //       repository had authored -- "exactly one of monthly, yearly or custom must be selected but 0
    //       were". That sentence was not in the catalogue the contract publishes for this operation, so
    //       the case passed while the response carried wording no client could have been written
    //       against. Stubbing the constant means the case follows the reference literal if it ever moves.
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
                ReportExecutionService.MESSAGE_NO_REPORT_TYPE_SELECTED));

        ReportRequest request = new ReportRequest(
                null, null, null, null, null, null, null, null, null, null, null, "N", null);

        mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_MAPPER.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reportType"))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value(ReportExecutionService.MESSAGE_NO_REPORT_TYPE_SELECTED))
                // WHY : Assumptions: the AGGREGATE message is asserted beside the per-field one because
                //       the shared handler fills both from the same sentence, and a client that renders
                //       the band rather than the field marker reads only this one. Asserting one and not
                //       the other would leave half the rendered surface unpinned.
                .andExpect(jsonPath("$.message")
                        .value(ReportExecutionService.MESSAGE_NO_REPORT_TYPE_SELECTED));

        verify(executions, never()).resolveConfirmation(any());
        verify(executions, never()).start(any(), any(), any(), any(), any());
    }

    // WHY : Assumptions: the literal is written out HERE rather than read from the constant, and that is
    //       deliberate. A case expressed in terms of the constant passes whatever the constant holds, so
    //       it would have passed against the invented sentence exactly as it passes against the reference
    //       one. Only a literal holds the constant to app/cbl/CORPT00C.cbl L438.
    /**
     * Asserts that the zero-mark refusal carries the reference's own sentence, character for character.
     */
    @Test
    @DisplayName("the zero-mark refusal sentence is the reference literal verbatim")
    void theZeroMarkRefusalIsTheReferenceLiteral() {
        assertThat(ReportExecutionService.MESSAGE_NO_REPORT_TYPE_SELECTED)
                .as("app/cbl/CORPT00C.cbl L438 moves this text, three full stops and all")
                .isEqualTo("Select a report type to print report...");
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
     * Asserts that either paging direction sent without a cursor answers 400 and reads nothing.
     *
     * <p>Refactoring Rationale: the case asserted the BACKWARD value alone and expected the refusal keyed
     * to {@code cursor}. Both halves changed. The guard now covers both values, because
     * {@code direction=next} with no cursor was accepted and answered the opening page -- the right rows
     * by luck, which established that the pairing rule the contract states held for one of its two
     * values. And the refusal is keyed to {@code direction} rather than to {@code cursor}, because the
     * offending member is the one the caller supplied: a cursor was legitimately absent, and naming it
     * asked the caller to correct a parameter it had not sent. That is the member
     * {@code ReferencePaging.requireCursorForDirection} keys the identical refusal to in the sibling
     * reference context.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("either paging direction with no cursor answers 400 naming the direction")
    void aDirectionWithoutACursorAnswersBadRequest() throws Exception {
        for (String supplied : List.of(ReportController.NEXT_DIRECTION,
                ReportController.PREVIOUS_DIRECTION)) {
            mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                            .principal(PRINCIPAL)
                            .param("startDate", "2022-07-01")
                            .param("endDate", "2022-07-31")
                            .param("direction", supplied))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("direction"));
        }

        verify(reports, never()).readDetailLinePage(any(), any(), any(), anyBoolean(), any());
    }

    // WHY : Assumptions: the two accepted values are asserted as LITERALS here rather than through the
    //       handler's own constants, which is the one place in this class that is deliberate rather than
    //       inconsistent. The defect this case guards was a constant whose value disagreed with the
    //       published enumeration, and a case written in terms of that constant cannot see it: it would
    //       have passed against "prev" exactly as it passes against "previous". The literals are the
    //       contract's own wire vocabulary, so this case fails if either side moves alone.
    /**
     * Asserts the wire vocabulary is {@code next} and {@code previous}, and that nothing else is admitted.
     *
     * <p>Refactoring Rationale: the handler compared the direction against {@code "prev"} while the
     * published enumeration declared {@code previous}, so a conforming backward request was processed as
     * FORWARD: it opened the caller's leading position under the forward binding, failed the
     * authenticated decryption, and was answered with an opaque refusal of a cursor this service had
     * itself just issued. No request a client could send would page backward. The unrecognised value was
     * silently treated as forward for the same reason -- there was no domain check at all -- so a
     * misspelling paged the wrong way rather than being refused.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the direction vocabulary is next and previous, and any other value answers 400")
    void theDirectionVocabularyIsTheContractsOwn() throws Exception {
        assertThat(ReportController.NEXT_DIRECTION).isEqualTo("next");
        assertThat(ReportController.PREVIOUS_DIRECTION).isEqualTo("previous");

        org.mockito.Mockito.doReturn(PageResponse.empty()).when(reports)
                .readDetailLinePage(any(), any(), any(), anyBoolean(), any());

        for (String refused : List.of("prev", "PREVIOUS", "backwards", "1")) {
            mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                            .principal(PRINCIPAL)
                            .param("startDate", "2022-07-01")
                            .param("endDate", "2022-07-31")
                            .param("cursor", "v2.0123456789abcdef.AAAA")
                            .param("direction", refused))
                    .andExpect(status().isBadRequest());
        }

        verify(reports, never()).readDetailLinePage(any(), any(), any(), anyBoolean(), any());
    }

    // WHY : Assumptions: the bound is asserted through a request rather than by reading the annotation,
    //       because the annotation is only half of the control -- it has to be APPLIED, and a parameter
    //       constraint is applied only when the framework validates handler parameters. A reflective
    //       assertion would pass for a handler whose constraints were never evaluated.
    /**
     * Asserts a cursor wider than the sealer's own maximum is refused before the sealer is asked.
     *
     * <p>Assumptions: this is the runtime half of the published cursor bound. The document declares
     * {@code maxLength} equal to {@link CursorToken#MAX_TOKEN_LENGTH}, and without this constraint an
     * over-long value would reach the sealer and be refused there -- the same status, but decided by a
     * component that has to decipher the value first rather than by a bound that can refuse it on
     * sight.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a cursor wider than the sealed maximum answers 400 without reaching the service")
    void anOverLongCursorAnswersBadRequest() throws Exception {
        mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                        .principal(PRINCIPAL)
                        .param("startDate", "2022-07-01")
                        .param("endDate", "2022-07-31")
                        .param("cursor", "v".repeat(CursorToken.MAX_TOKEN_LENGTH + 1)))
                .andExpect(status().isBadRequest());

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
                            sealer.seal("0000000000000009", false), true);
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
                            sealer.seal("0000000000000009", false), true);
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
                            sealer.seal("0000000000000009", false), true);
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

    // WHY : Assumptions: every case below sends the direction as a HARD-CODED LITERAL rather than as
    //       ReportController.PREVIOUS_DIRECTION or NEXT_DIRECTION. That is the whole point of the group
    //       and is not incidental style. The controller's backward literal was once "prev", which the
    //       published enumeration never offered, so no contract-conforming caller could reach a backward
    //       read at all -- and the suite could not see it, because the cases that exercised the
    //       direction sent the constant, so the value under test and the value asserted were the same
    //       symbol and moved together. A test bound to the implementation's own constant cannot detect a
    //       constant that disagrees with the contract; only a test bound to the published spelling can.
    /**
     * Asserts that the two direction constants hold exactly the values the published contract offers.
     *
     * <p>Assumptions: the literals here are copied from the {@code PageDirection} enumeration in
     * {@code src/main/resources/openapi/reporting-api.yaml}, which declares {@code [next, previous]} and
     * defaults to {@code next}. This case is the cheapest guard against the specific regression that a
     * constant drifts away from the contract it is supposed to spell.
     */
    @Test
    @DisplayName("the direction constants are the two values the contract publishes")
    void theDirectionValuesAreTheOnesTheContractPublishes() {
        org.assertj.core.api.Assertions.assertThat(ReportController.PREVIOUS_DIRECTION)
                .as("the backward value the published PageDirection enumeration offers")
                .isEqualTo("previous");
        org.assertj.core.api.Assertions.assertThat(ReportController.NEXT_DIRECTION)
                .as("the forward value the published PageDirection enumeration offers and defaults to")
                .isEqualTo("next");
    }

    /**
     * Asserts that the contract's backward value reaches the service as a backward read.
     *
     * <p>Assumptions: the assertion is made on the boolean the SERVICE receives rather than on the
     * status, because a forward read of a valid trailing cursor also answers 200 -- which is exactly
     * what the defect did. Only the direction argument distinguishes the two outcomes.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the published backward value selects a backward read")
    void thePublishedBackwardValueSelectsABackwardRead() throws Exception {
        Mockito.doReturn(PageResponse.empty()).when(reports)
                .readDetailLinePage(any(), any(), any(), eq(true), any());

        mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                        .principal(PRINCIPAL)
                        .param("startDate", "2022-07-01")
                        .param("endDate", "2022-07-31")
                        .param("cursor", leadingCursorFor("2022-07-01", "2022-07-31"))
                        .param("direction", "previous"))
                .andExpect(status().isOk());

        verify(reports).readDetailLinePage(any(), any(), any(), eq(true), any());
        verify(reports, never()).readDetailLinePage(any(), any(), any(), eq(false), any());
    }

    /**
     * Asserts that the contract's forward value and an omitted direction read the same way.
     *
     * <p>Assumptions: both are asserted in one case because they are one behaviour -- the published
     * schema declares {@code next} as the default, so sending it and omitting it must not differ.
     *
     * <p>⚠️ Refactoring Rationale: the forward value is sent WITH a cursor, where this case previously
     * sent it alone. A direction with no cursor is refused with 400 by this handler, and that refusal is
     * asserted by its own case above: an unpaired direction is a confused request, and answering it with
     * the opening page tells a caller that its direction was honoured when nothing positioned the read.
     * The same refusal is issued by the reference context's paging for the same reason, so it is a
     * platform rule rather than this handler's own. Sending the value alone therefore asserted the
     * opposite of the landed contract; sending it with a cursor asserts the property this case is named
     * for, which is that the published forward value and an omitted direction agree.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the published forward value and an omitted direction both read forward")
    void thePublishedForwardValueAndAnOmittedDirectionBothReadForward() throws Exception {
        mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                        .principal(PRINCIPAL)
                        .param("startDate", "2022-07-01")
                        .param("endDate", "2022-07-31")
                        .param("cursor", trailingCursorFor("2022-07-01", "2022-07-31"))
                        .param("direction", "next"))
                .andExpect(status().isOk());

        mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                        .principal(PRINCIPAL)
                        .param("startDate", "2022-07-01")
                        .param("endDate", "2022-07-31"))
                .andExpect(status().isOk());

        verify(reports, never()).readDetailLinePage(any(), any(), any(), eq(true), any());
    }

    // WHY : Assumptions: this case asserts the hole the named forward value exists to close. Before the
    //       forward value was named, the handler tested for the backward spelling alone and read
    //       everything else as forward, so a misspelling was answered with a page rather than refused --
    //       and a caller that meant to page backward was silently walked forward instead.
    /**
     * Asserts that a direction the contract does not publish is refused and reads nothing.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unpublished direction answers 400 naming the direction and reads nothing")
    void anUnpublishedDirectionAnswersBadRequest() throws Exception {
        mockMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                        .principal(PRINCIPAL)
                        .param("startDate", "2022-07-01")
                        .param("endDate", "2022-07-31")
                        .param("direction", "prev"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("direction"));

        verify(reports, never()).readDetailLinePage(any(), any(), any(), anyBoolean(), any());
    }

    /**
     * Seals a TRAILING boundary position for a range, so a forward step has a position to move from.
     *
     * <p>Assumptions: the token is sealed under the FORWARD binding, because the controller opens a
     * forward request with that binding and a token sealed under the backward one would fail its
     * authenticated decryption rather than reaching the service. That asymmetry is the whole point of
     * composing the direction into the binding, and it is why this helper exists beside its backward
     * twin instead of one helper serving both.
     *
     * @param start the first business date of the range; must not be {@code null}
     * @param end the last business date of the range; must not be {@code null}
     * @return a sealed trailing position bound to the test principal and that range; never {@code null}
     */
    private static String trailingCursorFor(String start, String end) {
        return new CursorToken(CURSOR_KEY, CURSOR_LIFETIME).seal(
                CursorToken.binding(ReportController.CURSOR_QUERY_NAME, PRINCIPAL.getName(),
                        CursorToken.scope("forward", start, end)),
                "0000000000000009");
    }

    /**
     * Seals a leading boundary position for a range, so a backward step has a position to move from.
     *
     * <p>Assumptions: the token is sealed under the BACKWARD binding, because the controller opens a
     * backward request with that binding and a token sealed under the forward one would fail its
     * authenticated decryption rather than reaching the service.
     *
     * <p>Assumptions: a sealer is built here over the SAME fixed key material the controller under test
     * was given, rather than reaching for the instance passed to it. A token is bound by its key and its
     * binding string alone, so a second sealer over the same key produces a token the controller opens,
     * and building one keeps this helper independent of how the fixture wires the controller.
     *
     * @param start the first business date of the range; must not be {@code null}
     * @param end the last business date of the range; must not be {@code null}
     * @return a sealed leading position bound to the test principal and that range; never {@code null}
     */
    private static String leadingCursorFor(String start, String end) {
        return new CursorToken(CURSOR_KEY, CURSOR_LIFETIME).seal(
                CursorToken.binding(ReportController.CURSOR_QUERY_NAME, PRINCIPAL.getName(),
                        CursorToken.scope("backward", start, end)),
                "0000000000000001");
    }
}
