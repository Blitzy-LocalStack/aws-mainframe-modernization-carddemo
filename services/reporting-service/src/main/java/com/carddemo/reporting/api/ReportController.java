package com.carddemo.reporting.api;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reporting.dto.ReportRequest;
import com.carddemo.reporting.dto.ReportSubmissionOutcome;
import com.carddemo.reporting.dto.ReportSubmissionResponse;
import com.carddemo.reporting.dto.TransactionReportLineResponse;
import com.carddemo.reporting.dto.TransactionReportTotals;
import com.carddemo.reporting.service.ReportExecutionService;
import com.carddemo.reporting.service.TransactionReportService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP boundary of the report-request surface, replacing the online report transaction.
 *
 * <p>This controller carries the two operations that surface {@code app/cbl/CORPT00C.cbl}, a
 * 649-line online program driving the {@code CR00} transaction: submitting a report for execution
 * behind the confirmation gate the screen presents, and reading the assembled detail report for a
 * business-date range. The baseline is reference material, read as the specification and never
 * modified, so what follows encodes it rather than redefining it.
 *
 * <p>The four responsibilities of this layer are binding a request, validating it declaratively,
 * mapping an outcome onto a status and delegating. Every business rule this class appears to apply
 * is applied by {@link ReportExecutionService} or {@link TransactionReportService}: the report-type
 * exclusivity, the confirmation vocabulary, the per-type date-range derivation, the integrity
 * reconciliation and the subtotal accumulation all live there. What this class owns is the mapping of
 * the three outcomes those services can produce -- accepted, deliberately cancelled and refused --
 * onto HTTP.
 *
 * <p>Refactoring Rationale: a deliberate cancellation answers HTTP 200 with an explicit
 * not-submitted outcome and no error payload, while only a validation failure answers HTTP 400 with
 * the shared problem body. The reasoning, and the baseline paragraph that cannot draw the
 * distinction, is registered once at {@code com.carddemo.reporting.api} and at
 * {@link ReportSubmissionOutcome}, and is cited here rather than restated.
 *
 * <p>Assumptions: no exception handler is declared here. Every failure below is raised as
 * {@link ClientInputException} or propagates from a service, and
 * {@code com.carddemo.common.error.GlobalExceptionHandler} -- registered on the application class
 * with {@code @Import} -- renders it into the one problem shape this service publishes. A local
 * handler would give this controller a second shape, which the package charter forbids for exactly
 * that reason.
 *
 * <p>Assumptions: no authority is asserted in code here. The filter chain in
 * {@code com.carddemo.reporting.config.SecurityConfig} admits only a principal holding one of the two
 * configured group authorities and refuses everything else before a handler is reached, so a
 * per-method annotation would restate a rule that is already total and would drift from it silently.
 * The published contract records the requirement per operation as machine-readable metadata, and the
 * contract test holds the two together.
 */
@RestController
@RequestMapping(path = ReportController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class ReportController {

    /**
     * Root path of every operation this controller carries.
     *
     * <p>Assumptions: the path is absolute from the server root and carries the version prefix
     * explicitly, because no servlet context path is configured to prepend one -- a relative
     * assumption would resolve differently once the service sits behind the edge.
     */
    public static final String BASE_PATH = "/api/v1/reports";

    /**
     * Sub-path of the report-submission operation.
     *
     * <p>Refactoring Rationale: the submission sits on its own sub-path rather than on the collection
     * root, and an earlier revision posted it to the root. The published contract at
     * {@code src/main/resources/openapi/reporting-api.yaml} names this path, and that document is the
     * contract of record -- it is what {@code ui/src/api/reporting.ts} composes its request targets
     * from and what the edge forwards. A handler on the root would have answered 404 for every request
     * the browser client sends, and the disagreement was invisible to a build because no step compares
     * a path constant with a YAML key.
     */
    public static final String SUBMISSION_PATH = "/transaction-report";

    /**
     * Sub-path of the paged detail lines.
     */
    public static final String LINES_PATH = SUBMISSION_PATH + "/lines";

    /**
     * Sub-path of the subtotal bands.
     *
     * <p>Assumptions: the lines and the totals are SEPARATE operations, and an earlier revision
     * returned both from one. Separating them is the published contract's arrangement and it is what
     * lets the lines be paged at all: a body carrying one page of lines beside the totals of the whole
     * range would report a grand total that did not match the lines beside it, which is a worse
     * inconsistency than the extra request. The two operations take the same range parameters, so a
     * caller that needs both issues the same range twice and the service reads the same snapshot.
     */
    public static final String TOTALS_PATH = SUBMISSION_PATH + "/totals";

    /**
     * How many detail lines one page of the lines operation carries.
     *
     * <p>Assumptions: the window is taken from the report's own arithmetic rather than chosen for the
     * wire, so a caller rendering one window at a time is rendering a window the report itself is built
     * around. {@code TransactionReportService.LINE_COUNTER_MODULUS} is the modulus
     * {@code app/cbl/CBTRN03C.cbl} tests its line counter against at L282, taken from
     * {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} at its L131 and L132.
     *
     * <p>Assumptions: a window of this operation is NOT one printed page of the report, and the two
     * must not be read as equal. Heading and subtotal bands advance the same counter the modulus is
     * tested against, so the detail lines on a printed page range from 10 to 40 across the 14 pages of
     * {@code tests/golden/reporting/e2e_full_cycle_report.expected}. The subtotal bands are published as
     * a separate operation for exactly that reason, and its own documentation records that they cover
     * the whole range rather than one window.
     */
    public static final int PAGE_SIZE = TransactionReportService.LINE_COUNTER_MODULUS;

    /**
     * Name this operation's cursor tokens are bound to.
     *
     * <p>Assumptions: the name is part of the cursor binding, so a token issued for this listing
     * cannot be redeemed against another service's listing even under the same key and the same
     * caller. It is a constant rather than a derivation from the path, because the path may be
     * re-spelled without invalidating every cursor a caller currently holds.
     */
    public static final String CURSOR_QUERY_NAME = "reporting.transaction-report.lines";

    /**
     * The direction value that reads backward from a held cursor.
     *
     * <p>Assumptions: the value is the one the published contract's direction enumeration declares and
     * the one the browser client sends. It is compared case-insensitively because a query parameter is
     * caller-typed text, and refusing a differently-cased spelling of a value this contract itself
     * names would be a refusal a caller could not act on.
     */
    public static final String PREVIOUS_DIRECTION = "prev";

    /**
     * Verbatim fragment the reference appends to the report name on a successful submission.
     *
     * <p>Assumptions: reproduced character for character from {@code app/cbl/CORPT00C.cbl} L449 to
     * L450, including the leading blank, the lower-case wording and the space before the three
     * trailing full stops. The reference assembles it with a {@code STRING} statement whose first
     * operand is the report name delimited by a space, so the rendered sentence is the trimmed name
     * followed by this fragment; {@link #submittedMessage(String)} performs the same assembly.
     */
    public static final String SUBMITTED_SUFFIX = " report submitted for printing ...";

    /**
     * Sentence returned with a deliberate cancellation.
     *
     * <p>Refactoring Rationale: this sentence has no counterpart in the reference, which writes no
     * message at all on the cancel branch at {@code app/cbl/CORPT00C.cbl} L480 to L483 -- it clears
     * the screen and redisplays it, leaving the operator with no statement of what happened. A target
     * client receiving a 200 with no sentence would face the same ambiguity over the wire, so one is
     * supplied. It is deliberately a constant rather than an assembly over the report name, because
     * nothing was submitted and naming a report would imply that something had been.
     */
    public static final String CANCELLED_MESSAGE = "Report was not submitted.";

    private final ReportExecutionService executions;

    private final TransactionReportService reports;

    /**
     * The cursor seam, held here because key material must not reach the service layer.
     *
     * <p>Assumptions: the controller holds the sealer and the service composes the rows, which is the
     * same division the transaction context uses. A service that sealed its own cursors would have to
     * resolve a signing key in every profile, and that is how a development default becomes the
     * committed secret a sealed cursor exists to prevent.
     */
    private final CursorToken cursorToken;

    /**
     * Creates the controller over the two services it delegates to.
     *
     * <p>Assumptions: both collaborators are injected through the constructor rather than resolved
     * from the context, so this class can be exercised with test doubles and holds no static
     * dependency on a running container.
     *
     * @param executions the submission and range-resolution service; must not be {@code null}
     * @param reports the detail-report assembly service; must not be {@code null}
     * @param cursorToken the sealing and opening seam for the lines cursor; must not be {@code null}
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public ReportController(ReportExecutionService executions, TransactionReportService reports,
            CursorToken cursorToken) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.cursorToken = Objects.requireNonNull(cursorToken, "cursorToken");
    }

    /**
     * Submits a report for execution, or reports a deliberate cancellation.
     *
     * <p>The order of the three steps is load-bearing and is the reference's own. The report type is
     * resolved first, then the range, and the confirmation answer only after both. Evaluating the
     * confirmation first would let a request that selected no report type at all be answered as a
     * successful cancellation, which is a success reported for a request that could never have run.
     *
     * @param request the report request the caller submitted; validated declaratively before this
     *     method is entered
     * @return the outcome, reporting either the accepted run or the cancellation; never {@code null}
     * @throws ClientInputException if no report type is selected or more than one is, if a custom
     *     range omits or misstates a bound, or if the confirmation answer is neither of the two the
     *     reference recognises
     */
    // WHY : Refactoring Rationale: the method name is the published operation identifier verbatim,
    //       and an earlier revision named it submitReport. The documentation library derives an
    //       operation identifier from the method name when none is annotated, so a method named
    //       otherwise makes the document served at run time disagree with the one committed beside
    //       it -- in a field ReportingApiContractTest pins on the committed side only. Naming the
    //       method after the operation removes the divergence at its source rather than adding a
    //       second annotation to paper over it.
    @PostMapping(path = SUBMISSION_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReportSubmissionOutcome> submitTransactionReport(
            @Valid @RequestBody ReportRequest request) {
        String reportName = executions.resolveReportName(request);
        ReportExecutionService.DateRange range = executions.resolveRange(request, reportName);

        if (!executions.isConfirmed(request)) {
            // WHY : Assumptions: a deliberate cancellation answers 200 and a started run answers 201,
            //       which is what the published contract declares and what the browser client
            //       switches on -- it reads the status before it reads the body, so the two outcomes
            //       are distinguishable without inspecting a member. A single 200 for both would make
            //       the outcome recoverable only from the body, and a client that forgot to look
            //       would report a cancellation as a submission.
            return ResponseEntity.ok(ReportSubmissionOutcome.cancelled(CANCELLED_MESSAGE));
        }

        ReportSubmissionResponse accepted =
                executions.start(request, reportName, range.start(), range.end());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ReportSubmissionOutcome.accepted(accepted, submittedMessage(reportName)));
    }

    /**
     * Reads one page of transaction detail lines for an inclusive business-date range.
     *
     * <p>Assumptions: both bounds are required query parameters rather than optional ones defaulting
     * to a period. The reference never runs this report without a range -- every one of its three
     * report types supplies both bounds before submission -- so a default would invent a range the
     * specification does not have, and a caller could not tell the invented range from one it asked
     * for.
     *
     * <p>Refactoring Rationale: the lines are PAGED and the totals moved to their own operation,
     * where an earlier revision returned both populations in one unbounded body. A range of any width
     * can produce thousands of lines -- the service bounds a run at
     * {@code TransactionReportService.MAX_REPORT_LINES} -- so an unbounded body made the response size
     * a function of the caller's range, and the published contract declares a page envelope here for
     * that reason. Pairing one page of lines with the totals of the whole range would additionally
     * report a grand total that did not match the lines beside it.
     *
     * @param startDate the first business date to cover, in {@code YYYY-MM-DD} order
     * @param endDate the last business date to cover, in {@code YYYY-MM-DD} order
     * @param cursor the sealed position a previous page reported, or {@code null} for the first page
     * @param direction the direction to read the cursor in, {@code null} meaning forward
     * @param principal the authenticated caller, supplied by the framework; the page's boundary
     *     tokens are bound to its name, so a cursor issued to another operator is refused
     * @return one bounded page of detail lines with its two sealed boundaries; never {@code null}
     * @throws ClientInputException if either bound is not a calendar date, if the range is inverted,
     *     or if the range selects more rows than the service is willing to assemble
     * @throws IllegalStateException if a detail line cannot be resolved to its reference dimensions,
     *     which is the target's equivalent of the reference abending on an unresolved lookup
     */
    @GetMapping(path = LINES_PATH)
    public PageResponse<TransactionReportLineResponse> listTransactionReportLines(
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "direction", required = false) String direction,
            Principal principal) {

        LocalDate start = parseBound(startDate, "startDate");
        LocalDate end = parseBound(endDate, "endDate");
        List<TransactionReportLineResponse> lines = reports.composeDetailLines(start, end);

        String binding = CursorToken.binding(CURSOR_QUERY_NAME, principal.getName(),
                start + ".." + end);
        int from = pageStart(lines.size(), cursor, direction, binding);
        int to = Math.min(from + PAGE_SIZE, lines.size());
        if (from >= to) {
            return PageResponse.empty();
        }

        List<TransactionReportLineResponse> page = lines.subList(from, to);
        return PageResponse.ofRows(page,
                this.cursorToken.seal(binding, Integer.toString(from)),
                this.cursorToken.seal(binding, Integer.toString(to - 1)),
                to < lines.size());
    }

    /**
     * Reads the subtotal bands of the transaction report for an inclusive business-date range.
     *
     * <p>Assumptions: the bands are accumulated over the WHOLE range rather than over one page, which
     * is why they are a separate operation. The reference accumulates its grand total across every
     * detail line it emits, so a total scoped to a page would not be the reference's total at all.
     *
     * @param startDate the first business date to cover, in {@code YYYY-MM-DD} order
     * @param endDate the last business date to cover, in {@code YYYY-MM-DD} order
     * @return the subtotal bands in the order the report emits them; never {@code null}
     * @throws ClientInputException if either bound is not a calendar date, if the range is inverted,
     *     or if the range selects more rows than the service is willing to assemble
     */
    @GetMapping(path = TOTALS_PATH)
    public TransactionReportTotals readTransactionReportTotals(
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate) {
        LocalDate start = parseBound(startDate, "startDate");
        LocalDate end = parseBound(endDate, "endDate");

        return new TransactionReportTotals(
                reports.composeTotals(reports.composeDetailLines(start, end)));
    }

    /**
     * Resolves where the requested page of detail lines begins.
     *
     * <p>Assumptions: the sealed position is the line's zero-based ORDINAL within the report run for
     * the requested range, not a value from the row. Alternatives Considered: keying the cursor on the
     * transaction identifier, which is what every other listing in this system does. Rejected here
     * because those listings page a relation in key order, whereas this report emits its lines in the
     * order {@code app/cbl/CBTRN03C.cbl} prints them -- by processing date and card, with subtotal
     * breaks -- so an identifier-keyed step would page in an order the report does not emit and the
     * page subtotals would no longer line up with the page they belong to. The range is folded into
     * the binding, so an ordinal sealed for one range cannot be redeemed against another, which is the
     * property an ordinal needs in order to be a safe position at all.
     *
     * <p>Trade-offs: an ordinal is stable only for as long as the underlying rows are, so a row posted
     * between two steps of one walk shifts every ordinal after it. That is accepted because this
     * report is read over a CLOSED business-date range -- both bounds are required and inclusive -- so
     * a row landing inside a range already closed is not an ordinary event, whereas the alternative
     * costs the report's own emission order.
     *
     * @param available how many lines the run produced
     * @param cursor the sealed position a caller supplied, or {@code null} for the first page
     * @param direction the direction the caller asked to read in, or {@code null} for forward
     * @param binding the composed cursor binding this page is produced under
     * @return the zero-based index of the first line of the page, never negative
     * @throws com.carddemo.common.web.CursorToken.InvalidCursorException if the cursor is not a token
     *     this service issued for this query, this caller and this range
     */
    private int pageStart(int available, String cursor, String direction, String binding) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }

        int position = Integer.parseInt(this.cursorToken.open(binding, cursor));
        // WHY : Assumptions: a backward step is expressed relative to the FIRST line of the page the
        //       caller holds, and a forward step relative to its LAST, which is exactly what the two
        //       boundary tokens of the envelope name. Reading backward therefore lands a whole page
        //       earlier than the held page's first line, and reading forward lands one line after its
        //       last -- the two are not symmetrical, and treating them as though they were is how a
        //       backward step comes to repeat a row it has already shown.
        if (PREVIOUS_DIRECTION.equalsIgnoreCase(direction)) {
            return Math.max(0, position - PAGE_SIZE);
        }
        return Math.min(position + 1, Math.max(available, 0));
    }

    /**
     * Assembles the verbatim submission sentence for a report name.
     *
     * <p>Assumptions: the name is trimmed before the fragment is appended, which is what the
     * reference's {@code DELIMITED BY SPACE} operand does at {@code app/cbl/CORPT00C.cbl} L449. The
     * three names are already untrailed constants, so the trim changes nothing today and is written
     * anyway because it is the assembly rule rather than an incidental property of the current values.
     *
     * @param reportName the resolved report name
     * @return the sentence a caller displays
     */
    private static String submittedMessage(String reportName) {
        return reportName.trim() + SUBMITTED_SUFFIX;
    }

    /**
     * Parses one query bound, refusing an unparseable value against its own parameter name.
     *
     * <p>Assumptions: this parses and does not validate a business rule. The ordering of the two
     * bounds and the size of the range they span are checked by
     * {@link TransactionReportService#composeDetailLines(LocalDate, LocalDate)}, which owns those
     * rules; duplicating either here would give one rule two homes.
     *
     * @param value the bound as the caller stated it
     * @param field the query-parameter name, used to name the refusal
     * @return the parsed bound
     * @throws ClientInputException if the value is blank or is not a calendar date
     */
    private static LocalDate parseBound(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    field + " must be supplied");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException notADate) {
            // WHY : Assumptions: the refusal names the parameter and never repeats the value it
            //       refused. The message is written into an operational record, and a constant
            //       message is what lets an alert rule match this refusal without matching on caller
            //       input -- the same discipline every refusal in this context follows.
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    field + " must be a calendar date in YYYY-MM-DD order");
        }
    }
}
