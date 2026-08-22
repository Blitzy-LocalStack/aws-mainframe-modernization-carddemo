package com.carddemo.reporting.api;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reporting.dto.ReportExecutionStatusResponse;
import com.carddemo.reporting.dto.ReportRequest;
import com.carddemo.reporting.dto.ReportSubmissionOutcome;
import com.carddemo.reporting.dto.ReportSubmissionResponse;
import com.carddemo.reporting.dto.TransactionReportLineResponse;
import com.carddemo.reporting.dto.TransactionReportTotals;
import com.carddemo.reporting.service.ArtifactStore;
import com.carddemo.reporting.service.ReportArtifactLocator;
import com.carddemo.reporting.service.ReportExecutionService;
import com.carddemo.reporting.service.TransactionReportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
 *
 * <p>Assumptions: this layer carries no golden master of its own, and the position has two halves
 * that are both stated because stating either alone would misdescribe the evidence. The program this
 * controller surfaces is an online one, and {@code tests/README.md} records at its L40 to L46, and
 * again at its L83 to L85, that the eighteen online programs reach their screens through the CICS
 * command-level interface and so cannot be driven end to end on a runner that carries no CICS
 * runtime, which leaves their extractable field-validation logic as what is covered there. Parity for
 * this class therefore rests on logic transcribed from the reference and on the copybook contracts
 * rather than on any byte comparison of its own output. The other half is that the batch programs
 * behind the assembled report and the statements do fall under that oracle, because they carry no
 * CICS verbs and run standalone, which is what makes the file cited above a real comparison for the
 * report path. Recording only the favourable half would let a covered claim stand in for a bounded
 * one, which is exactly what the auditability position stated at that document's L50 and L51 rules
 * out.
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
    public static final String BASE_PATH = ReportArtifactLocator.REPORTS_BASE_PATH;

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
    public static final String SUBMISSION_PATH =
            ReportArtifactLocator.TRANSACTION_REPORT_SEGMENT;

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
     * Sub-path of the report-artifact collection operation.
     *
     * <p>Assumptions: DERIVED from {@link ReportArtifactLocator#ARTIFACT_PATH}, which is also what the
     * status operation publishes as a result location, so the route that serves an artifact and the
     * location advertised for it cannot drift apart. The same reasoning, and the same shape, as the
     * statement surface's own artifact route.</p>
     */
    public static final String ARTIFACT_PATH =
            SUBMISSION_PATH + ReportArtifactLocator.ARTIFACT_SEGMENT;

    /**
     * Name of the path variable carrying an execution name.
     */
    public static final String EXECUTION_NAME_VARIABLE = "executionName";

    /**
     * Sub-path of the execution-status operation.
     */
    public static final String EXECUTIONS_PATH = "/executions/{" + EXECUTION_NAME_VARIABLE + "}";

    /**
     * Shape an execution name must have before the orchestration is asked about it.
     *
     * <p>Assumptions: the width is the orchestrator's own limit, which
     * {@link ReportExecutionService#EXECUTION_NAME_LIMIT} publishes, and the alphabet is the one this
     * service composes names from. Refusing a malformed name here means a value that could not be an
     * execution name never reaches a describe call, and it is the same asymmetry the statement artifact
     * route uses: the SHAPE is published in this contract so refusing it discloses nothing, while WHICH
     * names exist is not, so an unknown name is answered as absent.</p>
     */
    public static final String EXECUTION_NAME_PATTERN =
            "^[A-Za-z0-9_-]{1," + ReportExecutionService.EXECUTION_NAME_LIMIT + "}$";

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
     *
     * <p>The published enumeration is authoritative here: {@code PageDirection} in
     * {@code src/main/resources/openapi/reporting-api.yaml} enumerates exactly {@code next} and
     * {@code previous}, and {@code ui/src/api/reporting.ts} types the parameter as that enumeration and
     * defaults it to {@code next}.
     *
     * <p>WARNING -- Refactoring Rationale: this constant held {@code "prev"}, which the published
     * enumeration does not offer, so the only value a contract-conforming caller could send for a
     * backward step was read as no match and answered with a FORWARD page. Backward paging was
     * unreachable over the published interface -- not degraded, but absent -- and a caller holding a
     * leading position fared worse than one holding a trailing one: the leading token is sealed under
     * the backward binding, so opening it with the forward binding fails its authenticated decryption
     * and that caller saw a refusal rather than a page. The defect survived its own test suite because
     * the tests sent {@code ReportController.PREVIOUS_DIRECTION} rather than the literal the contract
     * publishes, so every assertion moved with the constant and none could ever disagree with it.
     * {@code ReportControllerTest.thePublishedBackwardValueSelectsABackwardRead} now sends the published
     * spelling as a hard-coded literal, and
     * {@code ReportControllerTest.theDirectionValuesAreTheOnesTheContractPublishes} pins both constants
     * against those literals, for exactly that reason.
     */
    public static final String PREVIOUS_DIRECTION = "previous";

    /**
     * The direction value that reads forward from a held cursor, which is also the default.
     *
     * <p>Assumptions: the forward value is NAMED rather than left as the absence of the backward one,
     * so that a direction this operation does not recognise can be told apart from the direction the
     * published enumeration declares as its default. While it was unnamed, the predecessor of
     * {@link #backwardRequested(String)} tested for the backward spelling alone and read every other
     * string as forward, so a misspelled direction was answered with a page the caller had not asked to
     * read rather than being refused -- the same class of silent substitution that made the backward
     * spelling itself unreachable.
     */
    public static final String NEXT_DIRECTION = "next";

    /**
     * Name of the query parameter the two direction values are sent in, as the contract publishes it.
     *
     * <p>Assumptions: the name is stated once and read both by the binding annotation and by the
     * refusal that names the offending field, so a rejection cannot name a parameter the request never
     * carried.
     */
    public static final String DIRECTION_PARAMETER = "direction";

    /**
     * The scope element naming a backward walk, so a leading position cannot be replayed forward.
     *
     * <p>Assumptions: the word is an internal binding element and is deliberately NOT the query
     * parameter's value. The parameter's accepted values are part of the published contract and would
     * change a client if they moved; this element only has to be stable and distinct from its
     * counterpart, and keeping the two independent means a contract-level rename cannot silently
     * invalidate every cursor a running deployment has already issued.</p>
     */
    private static final String SCOPE_BACKWARD = "backward";

    /** The scope element naming a forward walk, on the same terms as its counterpart above. */
    private static final String SCOPE_FORWARD = "forward";

    /**
     * Disposition set on an artifact response.
     *
     * <p>Assumptions: stated WITHOUT a filename, matching the statement surface. A filename composed from
     * a type and a range would be harmless, and omitting it keeps one convention for both artifact routes
     * rather than two that differ for no reason a reader could infer.</p>
     */
    private static final String ATTACHMENT_DISPOSITION = "attachment";

    /** Header forbidding content-type sniffing on an artifact response. */
    private static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";

    /** Value of that header. */
    private static final String NOSNIFF = "nosniff";

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
     * Verbatim fragment the reference opens its confirmation prompt with.
     *
     * <p>Assumptions: reproduced character for character from {@code app/cbl/CORPT00C.cbl} L466,
     * including the trailing blank. The reference assembles the prompt with a {@code STRING}
     * statement at L465 to L470 whose second operand is the report name delimited by a space, so the
     * rendered sentence is this fragment, the trimmed name, and the fragment below;
     * {@link #confirmationPrompt(String)} performs the same assembly.
     */
    public static final String CONFIRM_PROMPT_PREFIX = "Please confirm to print the ";

    /**
     * Verbatim fragment the reference closes its confirmation prompt with.
     *
     * <p>Assumptions: reproduced character for character from {@code app/cbl/CORPT00C.cbl} L469,
     * including the leading blank. Note that its three trailing full stops have NO space before them,
     * where {@link #SUBMITTED_SUFFIX} does have one -- the two sentences are similar, differ by that
     * single character, and are never merged.
     */
    public static final String CONFIRM_PROMPT_SUFFIX = " report...";

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
     * The key convention the produced report artifact is stored under.
     *
     * <p>Assumptions: held here as well as by the write path because this controller both PUBLISHES a
     * result location and SERVES it, and both have to name the object the run actually wrote. The
     * convention itself lives in the service layer so that neither side owns it.</p>
     */
    private final ReportArtifactLocator locator;

    /** The read side of the object store, which answers whether an artifact exists and streams it. */
    private final ArtifactStore artifacts;

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
     * @param locator the report-artifact key convention, shared with the run that writes it; must not be
     *     {@code null}
     * @param artifacts the read side of the object store; must not be {@code null}
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public ReportController(ReportExecutionService executions, TransactionReportService reports,
            CursorToken cursorToken, ReportArtifactLocator locator, ArtifactStore artifacts) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.cursorToken = Objects.requireNonNull(cursorToken, "cursorToken");
        this.locator = Objects.requireNonNull(locator, "locator");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    /**
     * Submits a report for execution, or reports a deliberate cancellation.
     *
     * <p>The order of the three steps is load-bearing and is the reference's own. The report type is
     * resolved first, then the range, and the confirmation answer only after both. Evaluating the
     * confirmation first would let a request that selected no report type at all be answered as a
     * successful cancellation, which is a success reported for a request that could never have run.
     *
     * <p>Assumptions: marking MORE THAN ONE report type is admitted rather than refused, and the type
     * that runs is decided by FIRST-MATCH PRECEDENCE in the order monthly, yearly, custom. That is the
     * reference's own condition chain -- {@code app/cbl/CORPT00C.cbl} evaluates the monthly arm at L213,
     * the yearly arm at L239 and the custom arm at L256 under one {@code EVALUATE TRUE}, so the first
     * marked type is the only one reached -- and {@link ReportExecutionService#resolveReportName} applies
     * exactly that chain. The response's report name is what tells a caller which type ran, and
     * {@code reporting-api.yaml} states the same precedence on this operation and on
     * {@code ReportRequest}. Recorded here because the opposite reading is the plausible one: this block
     * previously documented a refusal for a second mark, which would have answered 400 where the
     * baseline produces a report, and functional parity is a non-negotiable constraint of this migration.
     *
     * @param request the report request the caller submitted; validated declaratively before this
     *     method is entered
     * @param idempotencyKey the caller's optional submission key, sent in the
     *     {@link ReportExecutionService#IDEMPOTENCY_KEY_FIELD} header. Sending the same key twice makes
     *     the second submission a duplicate the orchestrator refuses, which is how a client retrying an
     *     abandoned call avoids starting a second run; omitting it makes every submission a distinct
     *     run, which is what lets the same report be produced again over the same range
     * @return the outcome, reporting either the accepted run or the cancellation; never {@code null}
     * @throws ClientInputException if NO report type is marked at all, carrying the reference's own
     *     sentence from {@code app/cbl/CORPT00C.cbl} L438; if a custom range omits a bound, states a
     *     bound the shared date edit rejects, or states an upper bound below its lower bound; if the
     *     confirmation answer is supplied and is neither of the two the reference recognises; or if a
     *     supplied submission key carries a character or a length an execution name may not hold. An
     *     ABSENT confirmation answer is not among them -- it is answered 200 with the reference's
     *     prompt, as the branch below records
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
            @Valid @RequestBody ReportRequest request,
            @RequestHeader(name = ReportExecutionService.IDEMPOTENCY_KEY_FIELD, required = false)
                    String idempotencyKey) {
        String reportName = executions.resolveReportName(request);
        ReportExecutionService.DateRange range = executions.resolveRange(request, reportName);
        ReportExecutionService.Confirmation answer = executions.resolveConfirmation(request);

        // WHY : Refactoring Rationale: the unanswered turn is answered HERE with a 200 and the
        //       reference's prompt, where an earlier revision let the service RAISE for it and so
        //       answered a 400 with a problem body. The reference does not treat an unanswered
        //       confirmation as a fault: L464 to L474 of app/cbl/CORPT00C.cbl composes a prompt naming
        //       the report and re-displays the screen, which is a question being asked rather than a
        //       mistake being reported. The published contract declares the same thing, and a caller
        //       written against it reads a 400 as "correct the request" -- so the earlier shape told a
        //       caller its request was wrong when the only thing outstanding was its own answer.
        if (answer == ReportExecutionService.Confirmation.UNANSWERED) {
            return ResponseEntity.ok(
                    ReportSubmissionOutcome.unanswered(confirmationPrompt(reportName)));
        }

        if (answer == ReportExecutionService.Confirmation.DECLINED) {
            // WHY : Assumptions: a deliberate cancellation answers 200 and a started run answers 201,
            //       which is what the published contract declares. The status separates a run that
            //       STARTED from one that did not, and that is all it separates: a cancellation and an
            //       unanswered confirmation both answer 200, so which of those two turns this was is
            //       recoverable only from the outcome member. An earlier revision of this comment
            //       claimed the status made every outcome distinguishable without inspecting a member,
            //       and the browser client written against that claim labelled an unanswered turn a
            //       cancellation -- which is the defect the three-valued member was introduced for.
            // WHY : Alternatives Considered: two other statuses were weighed for the started run and
            //       both were rejected against the contract of record, which declares 200 and 201 for
            //       this operation at src/main/resources/openapi/reporting-api.yaml and therefore
            //       settles the question. A single 200 for both outcomes would make the outcome
            //       recoverable only from the body, so a client that forgot to read a member would
            //       report a cancellation as a submission. A 202 was the other candidate, and it does
            //       describe the delegated-and-polled design accurately, since what this returns is a
            //       durable handle to an execution that is still running rather than a finished
            //       report; it was declined only because the published document names 201, and a
            //       controller that answered 202 would break the client written against that document.
            // WHY : Refactoring Rationale: the cancellation carries NO sentence, where an earlier
            //       revision carried a target-authored one reading "Report was not submitted." The
            //       reference's cancel branch clears the message line -- INITIALIZE-ALL-FIELDS at L633
            //       to L646 of app/cbl/CORPT00C.cbl includes WS-MESSAGE among the fields it clears --
            //       so the operator is shown a blank line, and transformation rule T8 admits no
            //       user-visible string that the baseline does not carry. The ambiguity the invented
            //       sentence was defending against is already answered by what a caller reads without
            //       it: the status is 200 and the outcome member reads DECLINED, which says the same
            //       thing as the withdrawn sentence in a form no locale has to translate.
            return ResponseEntity.ok(ReportSubmissionOutcome.cancelled());
        }

        // WHY : Assumptions: the submission key is passed straight through rather than defaulted here.
        //       Whether an omitted key means "give this run a fresh identity" or "reuse the previous
        //       one" is a decision about duplicate submissions, which ReportExecutionService owns and
        //       documents on its execution-name builder; minting a value here would move half of that
        //       decision into the transport layer and leave the two halves to be kept in agreement.
        ReportSubmissionResponse accepted = executions.start(
                request, reportName, range.start(), range.end(), idempotencyKey);
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
     * @param direction the direction to read the cursor in, either {@link #NEXT_DIRECTION} or
     *     {@link #PREVIOUS_DIRECTION} as the published enumeration declares them, {@code null} meaning
     *     forward
     * @param principal the authenticated caller, supplied by the framework; the page's boundary
     *     tokens are bound to its name, so a cursor issued to another operator is refused
     * @return one bounded page of detail lines with its two sealed boundaries; never {@code null}
     * @throws ClientInputException if either bound is refused by the same date edit a submitted
     *     bound is held to, if the range is inverted, if the direction is neither of the two values
     *     the contract publishes, if a direction is sent without the cursor it moves from, or if the
     *     range selects more rows than the service is willing to assemble
     * @throws IllegalStateException if a detail line cannot be resolved to its reference dimensions,
     *     which is the target's equivalent of the reference abending on an unresolved lookup
     */
    @GetMapping(path = LINES_PATH)
    public PageResponse<TransactionReportLineResponse> listTransactionReportLines(
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate,
            // WHY : Assumptions: the width ceiling is the runtime token's own ceiling rather than a
            //       round number, so the published schema and the sealed token cannot drift. Without
            //       it an arbitrarily long value reached the authenticated decryption, which spends
            //       cipher work on a string that could not have been issued by this service.
            @RequestParam(name = "cursor", required = false)
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            String cursor,
            @RequestParam(name = DIRECTION_PARAMETER, required = false) String direction,
            Principal principal) {

        ReportExecutionService.DateRange edited = editStatedBounds(startDate, endDate,
                ReportExecutionService.START_DATE_FIELD, ReportExecutionService.END_DATE_FIELD);
        LocalDate start = edited.start();
        LocalDate end = edited.end();

        // WHY : Refactoring Rationale: the page is read by KEYSET and no longer sliced ordinally out of
        //       a materialised range. The previous shape assembled every line the range held -- up to
        //       the service's ten-thousand-row ceiling -- carried a zero-based ordinal inside the sealed
        //       cursor and returned a sub-list of it. Two properties of that were wrong rather than
        //       merely inefficient. An ordinal is not a position in the data: a row posted into the
        //       range between two steps of one walk shifts every ordinal after it, so a page could
        //       repeat a line already shown or skip one never shown, and the previous prose accepted
        //       that on the ground that a closed date range makes a late posting unusual -- which is a
        //       statement about likelihood rather than about correctness, and the migration plan states
        //       keyset positioning as a rule precisely because offset positioning has this failure. And
        //       the whole range was read for every page, so the twentieth page cost twenty scans and the
        //       first page's latency was a function of the range's width rather than the page's.
        // WHY : Assumptions: TWO bindings are composed and they differ by one scope element, because
        //       the published contract states that the direction a position was issued for is sealed
        //       INTO it -- so replaying a trailing position with a backward direction is refused
        //       rather than answered with the wrong page. The leading key of a page is the position a
        //       backward step moves from, so it is sealed under the backward binding; the trailing key
        //       is the position a forward step moves from, so it is sealed under the forward one. A
        //       request then opens with whichever binding matches the direction it asked for, and a
        //       mismatch fails the authenticated decryption rather than being detected afterwards.
        //       Refactoring Rationale: one binding stood here, carrying no direction at all, so the
        //       contract sentence was false and a trailing token was redeemable backward -- which
        //       walks a caller past rows it never saw. The same recipe is stated once, for a module
        //       with several browses, on reference-service's ReferencePaging.binding; this operation
        //       is the only paged read in this context, so the two elements are composed inline
        //       rather than through a helper that would have one caller.
        String backwardBinding = directionBinding(principal.getName(), start, end, true);
        String forwardBinding = directionBinding(principal.getName(), start, end, false);
        boolean backward = backwardRequested(direction);
        boolean held = cursor != null && !cursor.isBlank();
        if (direction != null && !direction.isBlank() && !held) {
            // WHY : Assumptions: a direction with no cursor is refused rather than answered with the
            //       leading page, and the refusal now covers BOTH values rather than the backward one
            //       alone. A step is taken FROM a position, so without one the request names nothing;
            //       answering the first page would silently discard a stated intent, which for a
            //       backward request means telling a caller it had reached the beginning when it had
            //       not asked, and is the one outcome a paging caller cannot detect from the answer.
            //       Refactoring Rationale: the guard tested the backward case only, so
            //       `direction=next` with no cursor was accepted and answered the opening page --
            //       which happens to be the right rows, but establishes that the pairing rule the
            //       contract states holds for one of its two values. The same rule is stated once for
            //       a module with several browses on reference-service's
            //       ReferencePaging.requireCursorForDirection; this context has one browse, so it is
            //       applied inline rather than through a helper with a single caller.
            //       Assumptions: the reverse pairing -- a cursor with no direction -- is NOT refused,
            //       which is deliberate. An absent direction means forward, the contract's published
            //       default, and that is a complete instruction when a position is supplied.
            throw new ClientInputException(ApiError.CODE_VALIDATION, DIRECTION_PARAMETER,
                    "a paging direction must be sent with the cursor it moves from");
        }

        String openedKey = held
                ? this.cursorToken.open(backward ? backwardBinding : forwardBinding, cursor)
                : null;

        return reports.readDetailLinePage(start, end, openedKey, backward,
                (key, leading) -> this.cursorToken.seal(
                        leading ? backwardBinding : forwardBinding, key));
    }

    /**
     * Reports whether a caller asked to step backward, refusing any direction the contract omits.
     *
     * <p>Assumptions: an unsupplied direction reads forward, because the published parameter is
     * optional and its schema declares {@code next} as the default, so a first page carries no
     * direction at all. Every supplied value is then matched against the two the enumeration publishes
     * and anything else is refused, which is the whole point of naming the forward value: a direction
     * that matches neither is a request this operation cannot honour, and answering it with the forward
     * page would report rows the caller never asked for as though they were the ones it did.
     *
     * <p>Alternatives Considered: converting through a shared wire-value parse, the way
     * {@code PageDirection.fromRequestParameter} does for the services that own several browses.
     * Rejected because that enumeration is declared per module -- {@code auth-service} and
     * {@code reference-service} each own a copy in their own {@code dto} package and neither is
     * visible here -- so reaching it would mean either a dependency between two services' DTO packages,
     * which the layering rules in {@code common-lib} forbid outright, or a third copy of a two-value
     * enumeration for the ONE paged read this context has. The same reasoning already stands recorded
     * beside the cursor bindings this method feeds, which are composed inline for the same reason.
     *
     * <p>Trade-offs: matching case-insensitively while refusing unpublished values accepts a spelling
     * the contract does not literally declare, such as {@code PREVIOUS}. That is deliberate and is the
     * trade this class already recorded on {@link #PREVIOUS_DIRECTION}: a differently-cased spelling of
     * a value the contract itself names is unambiguous, so refusing it would be a refusal the caller
     * could not act on, whereas a value the contract never names is genuinely unreadable.
     *
     * @param direction the direction as the caller sent it, which may be {@code null} or blank
     * @return {@code true} when the caller asked to read backward; {@code false} when it asked to read
     *     forward or asked for no direction at all
     * @throws ClientInputException if the direction is neither of the two the contract publishes, named
     *     against the {@code direction} parameter so the caller can correct the request it sent
     */
    private static boolean backwardRequested(String direction) {
        if (direction == null || direction.isBlank()) {
            return false;
        }
        if (PREVIOUS_DIRECTION.equalsIgnoreCase(direction)) {
            return true;
        }
        if (NEXT_DIRECTION.equalsIgnoreCase(direction)) {
            return false;
        }
        throw new ClientInputException(ApiError.CODE_VALIDATION, DIRECTION_PARAMETER,
                "a paging direction must be " + NEXT_DIRECTION + " or " + PREVIOUS_DIRECTION);
    }

    /**
     * Composes the cursor binding one direction's positions are sealed under and opened with.
     *
     * <p>Assumptions: the direction is carried as a scope ELEMENT rather than appended to the range
     * text, because {@code CursorToken.scope} composes its elements injectively -- it length-prefixes
     * each one -- so no combination of a range and a direction can collide with another combination.
     * Concatenating them would admit exactly that: a range ending in the direction word would produce
     * the same binding as the adjacent range without it.</p>
     *
     * @param subject the authenticated caller's name, so a position issued to one operator cannot be
     *     redeemed by another; must not be {@code null}
     * @param start the first business date the page covers; must not be {@code null}
     * @param end the last business date the page covers; must not be {@code null}
     * @param backward {@code true} for the binding a backward step opens with, {@code false} for the
     *     binding a forward step opens with
     * @return the composed binding, never {@code null}
     */
    private static String directionBinding(
            String subject, LocalDate start, LocalDate end, boolean backward) {
        return CursorToken.binding(CURSOR_QUERY_NAME, subject,
                CursorToken.scope(backward ? SCOPE_BACKWARD : SCOPE_FORWARD,
                        start.toString(), end.toString()));
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
     * @throws ClientInputException if either bound is refused by the same date edit a submitted
     *     bound is held to, if the range is inverted, or if the range selects more rows than the
     *     service is willing to assemble
     */
    @GetMapping(path = TOTALS_PATH)
    public TransactionReportTotals readTransactionReportTotals(
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate) {
        ReportExecutionService.DateRange edited = editStatedBounds(startDate, endDate,
                ReportExecutionService.START_DATE_FIELD, ReportExecutionService.END_DATE_FIELD);
        LocalDate start = edited.start();
        LocalDate end = edited.end();

        // WHY : Refactoring Rationale: the totals are computed by the service over the RANGE rather than
        //       over a list of composed lines this method assembled first. The old shape forced the
        //       grouping to be whatever the response type carried, which is an account identifier, while
        //       the emitted report groups by card -- so the group subtotal beside a listing page was a
        //       differently-grouped number presented as the report's own. Reading the range in the
        //       service lets it group on the per-card fingerprint the report groups on, and it also
        //       removes an assembly of up to ten thousand response values performed only to sum them.
        return new TransactionReportTotals(reports.composeTotals(start, end));
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
     * Assembles the reference's confirmation prompt around a report name.
     *
     * <p>Assumptions: the name is trimmed between the two fragments, which is what the reference's
     * {@code DELIMITED BY SPACE} operand does at {@code app/cbl/CORPT00C.cbl} L468. The three names
     * are already untrailed constants, so the trim changes nothing today and is written anyway because
     * it is the assembly rule rather than an incidental property of the current values -- the same
     * reason {@link #submittedMessage(String)} trims.</p>
     *
     * <p>Assumptions: the two fragments are joined in the reference's own order and neither is shared
     * with the submission sentence. They differ from it by one character, the space before the three
     * dots, and a helper that derived one from the other would make that difference an accident
     * waiting to be tidied away.</p>
     *
     * @param reportName the resolved report name the prompt names
     * @return the prompt a caller displays, well inside the outcome's declared message width
     */
    private static String confirmationPrompt(String reportName) {
        return CONFIRM_PROMPT_PREFIX + reportName.trim() + CONFIRM_PROMPT_SUFFIX;
    }

    /**
     * Reports what became of one submitted report, and where to collect it once it exists.
     *
     * <p>⚠️ Refactoring Rationale: this operation is what the submission handle was for. A review found
     * that the handle was returned and consumed by nothing: a caller could not tell a run still
     * going from one that had failed, and the produced document was reachable by no operation at all. The
     * two date-range operations beside this one report the CURRENT contents of the ledger, which is not the
     * same thing as the report a particular run rendered -- so reading them was never an answer to "did my
     * report succeed, and where is it".
     *
     * <p>Assumptions: the run is named by its NAME and never by an ARN. The service composes the ARN from
     * the configured state machine, so nothing a caller sends can reach another machine, another account or
     * another environment -- a guarantee by construction rather than by a prefix check that has to be got
     * right.
     *
     * <p>⚠️ Refactoring Rationale: the value this operation takes is now the value the submission
     * RETURNS, and a second review is what closed that gap. The submission answered with the execution
     * ARN in full, which this path cannot accept -- a caller sending it had its colons and slashes
     * percent-encoded into one segment and was refused on the published shape -- so the lifecycle could
     * be started and never polled. {@code ReportSubmissionResponse.executionName} is the pairing value,
     * and the ARN is withdrawn from that response rather than accepted here alongside the name: accepting
     * both would put two spellings of one handle in the contract and would have to state which wins.
     *
     * <p>Assumptions: the artifact location is published only where the store HOLDS the object, so a
     * location this operation returns always resolves. A run that succeeded and whose artifact a lifecycle
     * rule has since expired reports its status with no location.
     *
     * @param executionName the name a submission returned for this run
     * @return the run's state, its coordinates and, when the artifact exists, where to collect it
     * @throws NoSuchElementException if this deployment's orchestration knows no run of that name --
     *     rendered as 404 by the shared advice
     */
    @GetMapping(path = EXECUTIONS_PATH)
    public ResponseEntity<ReportExecutionStatusResponse> readReportExecution(
            @PathVariable(EXECUTION_NAME_VARIABLE) @Pattern(regexp = EXECUTION_NAME_PATTERN)
                    String executionName) {
        ReportExecutionService.ExecutionState state = executions.describeExecution(executionName);
        ReportExecutionService.ExecutionCoordinates coordinates = state.coordinates();
        ReportExecutionStatusResponse base = ReportExecutionStatusResponse.withoutResult(
                state.executionName(),
                state.status().name(),
                state.startedAt(),
                state.stoppedAt(),
                coordinates == null ? null : coordinates.reportType(),
                coordinates == null ? null : coordinates.rangeStart().toString(),
                coordinates == null ? null : coordinates.rangeEnd().toString());

        // WHY : Assumptions: the store is consulted ONLY for a run the orchestration reports as succeeded
        //       and whose coordinates are known. A running or failed run has published nothing, so a
        //       metadata call for it would spend a request to learn what the status already says; and a run
        //       started outside this surface carries an input this service did not compose, so there are no
        //       coordinates from which to name an object.
        if (!state.succeeded() || coordinates == null) {
            return ResponseEntity.ok(base);
        }

        return artifacts.describe(locator.key(coordinates.reportType(), coordinates.rangeStart(),
                        coordinates.rangeEnd(), coordinates.rangeEnd()))
                .map(stored -> ResponseEntity.ok(ReportExecutionStatusResponse.withResult(base,
                        locator.artifactPath(coordinates.reportType(), coordinates.rangeStart(),
                                coordinates.rangeEnd()),
                        stored.lastModified())))
                .orElseGet(() -> ResponseEntity.ok(base));
    }

    /**
     * Streams the produced transaction-report artifact for one type and range.
     *
     * <p>⚠️ Refactoring Rationale: the artifact a run writes was reachable by nothing. The dataset bucket
     * admits only the VPC endpoint, so even an operator holding the object key could not fetch it through a
     * browser, and no operation served it. This is the delivery half of the lifecycle, and it is deliberately
     * addressed by the COORDINATES a caller already holds rather than by an opaque token -- the reasoning,
     * including why a token would be the wrong instrument for a report type and a date range, is recorded on
     * {@link ReportArtifactLocator}.
     *
     * <p>Assumptions: the body is an opaque attachment rather than declared text, for the same reason the
     * statement artifacts are: the artifact is a fixed-width document compared byte for byte against the
     * golden masters, and declaring a text media type invites a client to re-encode it.
     *
     * <p>Assumptions: the type is admitted against a closed domain and each bound is parsed as a calendar
     * date, so no caller-supplied text reaches an object key uninterpreted. A type outside the domain is
     * refused with 400 rather than answered as absent, because the domain is published in this contract.
     *
     * @param reportType the report type the run was started for
     * @param startDate the inclusive lower bound of the reported range, in {@code YYYY-MM-DD} order
     * @param endDate the inclusive upper bound
     * @return the artifact bytes as an attachment
     * @throws ClientInputException if a bound is absent or is refused by the same date edit a
     *     submitted bound is held to
     * @throws NoSuchElementException if no artifact is stored for those coordinates
     */
    @GetMapping(path = ARTIFACT_PATH, produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<InputStreamResource> collectReportArtifact(
            @RequestParam(ReportArtifactLocator.TYPE_PARAMETER) String reportType,
            @RequestParam(ReportArtifactLocator.START_DATE_PARAMETER) String startDate,
            @RequestParam(ReportArtifactLocator.END_DATE_PARAMETER) String endDate) {
        ReportExecutionService.DateRange edited = editStatedBounds(startDate, endDate,
                ReportArtifactLocator.START_DATE_PARAMETER, ReportArtifactLocator.END_DATE_PARAMETER);
        String key = reportArtifactKey(reportType, edited.start(), edited.end());
        ArtifactStore.OpenArtifact artifact = artifacts.open(key);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ATTACHMENT_DISPOSITION)
                .header(CONTENT_TYPE_OPTIONS_HEADER, NOSNIFF)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(artifact.sizeBytes())
                .body(new InputStreamResource(artifact.content()));
    }

    /**
     * Composes the artifact key for one set of coordinates, refusing an unpublished report type.
     *
     * <p>Assumptions: the closed-domain refusal is translated into a CALLER refusal here. The locator
     * raises an argument failure, which the shared advice would render as an internal error -- correct for
     * a batch task that passed a bad argument, wrong for a request whose caller can correct it.</p>
     *
     * @param reportType the report type as the caller stated it
     * @param start the inclusive lower bound
     * @param end the inclusive upper bound
     * @return the object key
     * @throws ClientInputException if the type is not one this service publishes
     */
    private String reportArtifactKey(String reportType, LocalDate start, LocalDate end) {
        try {
            return locator.key(reportType, start, end, end);
        } catch (IllegalArgumentException unknownType) {
            throw new ClientInputException(ApiError.CODE_VALIDATION,
                    ReportArtifactLocator.TYPE_PARAMETER, unknownType.getMessage());
        }
    }

    /**
     * Edits a range's two query bounds with the same rules a submitted range is held to.
     *
     * <p>Refactoring Rationale: the edit is applied to the PAIR rather than to one bound at a time,
     * because a bound is not the unit the reference edits in. {@code app/cbl/CORPT00C.cbl} runs each of
     * its tiers across both bounds before opening the next -- six emptiness arms at L258 to L299, six
     * component arms at L331 to L374, then the assembled edit for the lower bound at L399 and for the
     * upper at L419 -- so a range stating {@code 2022-02-30} below and {@code 2022-13-15} above is
     * refused there on the UPPER bound, whose month fails a component arm before either assembled edit
     * is reached. Editing each bound to completion in turn refused the same range on the LOWER bound
     * instead. Only one sentence ever reaches a caller, so the difference is in WHICH of two
     * simultaneous faults is named, and naming a different one is an observable difference.
     *
     * <p>Refactoring Rationale: this method also DELEGATES the edit, where it parsed the value with a bare
     * calendar parse of its own. Two surfaces then disagreed about the same value: the submission
     * operation puts each bound through {@link ReportExecutionService#editStatedBound(String, String)}
     * and refused {@code 0000-01-01} because the shared date edit reports an era-zero year, while the
     * two read operations and the artifact collection accepted it and answered rows for a range the
     * same service had just declared unusable. The bare parse was the whole cause: it admits any value
     * the calendar type can construct, which is a wider domain than the reference's edit. Delegating
     * rather than transcribing the rules here is what keeps the three surfaces from drifting apart
     * again, and it carries the reference's own sentences onto the read surfaces at the same time --
     * including the component sentences, which the previous target-authored message could not express.
     *
     * <p>Assumptions: this edits and does not validate a business rule. The ordering of the two
     * bounds and the size of the range they span are checked by
     * {@link TransactionReportService#composeDetailLines(LocalDate, LocalDate)}, which owns those
     * rules; duplicating either here would give one rule two homes.
     *
     * <p>Assumptions: each value is trimmed before the edit, which is the transport normalisation this
     * method already performed and is kept unchanged. The submission path reaches the same edit with
     * its TRAILING padding already removed, because {@code ReportRequest} strips a declared-width
     * field's trailing blanks in its constructor, so on that side the two surfaces agree exactly.
     * They differ on LEADING padding and knowingly so: a body bound carrying a leading blank is
     * eleven characters after that strip and is refused by the request schema's own pattern, while a
     * query bound carrying one is trimmed and accepted. The difference is a property of transport
     * normalisation rather than of the date edit -- the edit itself is now one implementation for
     * every surface -- and narrowing the read surfaces over a percent-encoded blank would change an
     * accepted request into a refused one for no finding's sake.
     *
     * @param startValue the lower bound as the caller stated it
     * @param endValue the upper bound as the caller stated it
     * @param startField the lower bound's query-parameter name, used to name a refusal against it
     * @param endField the upper bound's query-parameter name; the sentence the shared edit selects
     *     depends on which end of the range is being reported, so the two surfaces that read a range
     *     pass {@link ReportExecutionService#START_DATE_FIELD} and
     *     {@link ReportExecutionService#END_DATE_FIELD} while the artifact collection reaches those
     *     same two values through {@link ReportArtifactLocator#START_DATE_PARAMETER} and
     *     {@link ReportArtifactLocator#END_DATE_PARAMETER}, which are the parameter names that
     *     operation publishes and are spelled identically -- a rename on either side would send the
     *     upper bound's sentence for a lower bound, which is why the two are asserted equal by the
     *     contract cases rather than assumed to stay in step
     * @return both edited bounds, in the order stated; never {@code null}
     * @throws ClientInputException if either value is blank, carries a month, day or year component
     *     the reference's component tier refuses, or is rejected by the shared date edit
     */
    private static ReportExecutionService.DateRange editStatedBounds(String startValue, String endValue,
            String startField, String endField) {
        return ReportExecutionService.editStatedBounds(
                startValue == null ? null : startValue.trim(),
                endValue == null ? null : endValue.trim(),
                startField, endField);
    }
}
