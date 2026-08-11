package com.carddemo.reporting.service;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.observability.ThrowableDigest;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.common.validation.DateEditValidator.LanguageEnvironmentResult;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.reporting.dto.ReportRequest;
import com.carddemo.reporting.dto.ReportSubmissionResponse;
import com.carddemo.reporting.mapper.ReportBandLayouts;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

/**
 * Starts an on-demand transaction-report run for the report-request screen.
 *
 * <p>{@code app/cbl/CORPT00C.cbl} is a 649-line online program. It offers three mutually exclusive
 * report types, resolves each one to a pair of business dates, gates the run behind a confirmation
 * answer, and then hands the request to the job entry subsystem. This class carries the same four
 * steps and hands the request to a second, smaller state machine instead.</p>
 *
 * <h2>Refactoring Rationale: the submission transport</h2>
 *
 * <p>The baseline serialises a report request as JCL TEXT and appends it, 80 bytes at a time, to an
 * extrapartition transient data queue whose destination is the internal reader. The program's own
 * header comment at L6 of {@code app/cbl/CORPT00C.cbl} names that mechanism. The queue is defined
 * across L499 to L505 of {@code app/csd/CARDDEMO.CSD}: L499 declares it, L500 describes it as
 * submitting jobs from the region, L501 carries {@code TYPE(EXTRA) DATABUFFERS(1) DDNAME(INREADER)
 * ERROROPTION(IGNORE)}, L502 carries {@code OPENTIME(INITIAL) TYPEFILE(OUTPUT) RECORDSIZE(80)} and
 * L503 carries {@code RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) DISPOSITION(MOD)}. Two things were
 * wrong with that as a request transport, and they are DIFFERENT statements that must both be made,
 * because either one alone is false.</p>
 *
 * <p>The first half concerns the region. {@code ERROROPTION(IGNORE)} on L501 is what makes CICS
 * ITSELF swallow an extrapartition write error, so a request could be accepted at the screen and
 * never reach the job entry subsystem with nothing anywhere recording the loss.</p>
 *
 * <p>The second half concerns the program, and it is the opposite of a program omission. The program
 * DOES check the response code and DOES carry its own failure message: the paragraph
 * {@code WIRTE-JOBSUB-TDQ} runs from L515 to L535, requests a response code on L521, evaluates it
 * from L525, and on any non-normal outcome moves the sentence
 * {@code 'Unable to Write TDQ (JOBS)...'} at L531. That paragraph name is spelled exactly as the
 * baseline spells it and is quoted rather than respelled. It is performed at L507 and named again in
 * the comment at L513. So the program stands ready to report a condition that the queue definition
 * tells the region to ignore. Attributing the silence to the program would be false; attributing the
 * readiness to check to the region would be false too.</p>
 *
 * <p>The replacement answers exactly that. A {@code states:StartExecution} call accepts typed input
 * rather than card images, returns an execution ARN that makes the run addressable afterwards, and
 * raises on refusal instead of discarding it, so a submission this class cannot place can no longer
 * be lost quietly. The silence is the one property deliberately NOT reproduced. None of this
 * disparages or retires the baseline path: the migration adds a path, it does not remove one.</p>
 *
 * <h2>Assumptions: the request payload collapses seventeen card images into typed values</h2>
 *
 * <p>The baseline builds its request as a group of 80-byte items at L83 to L125 of
 * {@code app/cbl/CORPT00C.cbl}. A count of the {@code 05}-level items in that span gives SEVENTEEN
 * of them, at L83, L85, L87, L89, L91, L93, L95, L97, L99, L101, L103, L108, L113, L115, L117, L122
 * and L124, so the assembled payload is 17 x 80 = 1360 bytes. A peer description of this program
 * puts the count one higher, at 18, while its own enumeration lists 17; the count above is taken
 * directly from the program text, and first-hand COBOL supersedes derived prose. The overlay
 * declared at L126 and L127 redefines that group as an array of 1000 entries of which only the
 * seventeen are ever populated, so its declared arity describes the redefinition and is not a
 * capacity of anything.</p>
 *
 * <p>Three of those cards are worth naming because each carries a contract rather than boilerplate.
 * The card at L93 and L94 invokes a CATALOGUED PROCEDURE named {@code TRANREPT} rather than naming
 * the report program directly, so the step structure the run acquires is the procedure's and not
 * this program's. The card at L97 and L98 is a step-qualified inline data-definition override for
 * the sort symbol table. The two cards after it, at L99 to L102, declare the two sort symbols
 * {@code TRAN-CARD-NUM,263,16,ZD} and {@code TRAN-PROC-DT,305,10,CH} byte for byte as L41 and L42 of
 * {@code app/jcl/TRANREPT.jcl} declare them, which is the corroboration that the two paths address
 * the same record positions. The group at L117 to L121 is the parameter record the report program
 * reads: a 10-character start bound, EXACTLY ONE space, a 10-character end bound and 59 characters
 * of padding, which is 10 + 1 + 10 + 59 = 80 and therefore the whole declared record.</p>
 *
 * <p>Assumptions: the write loop at L496 to L509 reaches the terminator card as well as the sixteen
 * before it. The loop sets its stop flag inside the test at L502 to L505 but performs
 * {@code WIRTE-JOBSUB-TDQ} at L507, AFTER that test, so the card that satisfied the test is written
 * before the loop ends and all seventeen arrive. Reading the flag as ending the iteration
 * immediately would drop the terminator, which is why the order is recorded here rather than
 * inferred. None of the seventeen survives the transport change: they collapse into the typed values
 * this class passes as execution input, and no job control text is composed anywhere in this file.</p>
 *
 * <h2>Refactoring Rationale: one resolved range serves both the record filter and the report
 * header</h2>
 *
 * <p>The batch-only path carries the range on two independent channels that nothing reconciles.
 * {@code app/jcl/TRANREPT.jcl} selects records against two literals written into its symbol table,
 * {@code PARM-START-DATE,C'2022-01-01'} on L43 and {@code PARM-END-DATE,C'2022-07-06'} on L44, which
 * the sort condition at L47 and L48 compares each record's processing date against. The report
 * program heads the output from a different source entirely: the parameter dataset declared at L73
 * and L74 of the same job, whose values it moves into the header fields at L277 and L278 of
 * {@code app/cbl/CBTRN03C.cbl}. Nothing in that path makes the two agree, so a report can be
 * filtered by one range and titled with another.</p>
 *
 * <p>The online program does not have that exposure, and the reason is worth stating precisely: it
 * writes both channels from a single value. L429 to L432 move the resolved start into
 * {@code PARM-START-DATE-1} and {@code PARM-START-DATE-2} together and the resolved end into
 * {@code PARM-END-DATE-1} and {@code PARM-END-DATE-2} together, the first of each pair landing in
 * the symbol-table cards and the second in the parameter record at L117 to L121. The monthly and
 * yearly arms do the same at L220, L221, L235, L236, L247, L248, L252 and L253. This class is where
 * that single value originates in the target, and it is handed onward once: the same pair reaches
 * the execution input the generator filters on and the response the caller titles from. The property
 * secured is agreement between the filter and the header, which is materially stronger than
 * reproducible reruns and is the reason the range is resolved here rather than twice downstream.</p>
 *
 * <h2>Assumptions: the business date is resolved once, at this edge and nowhere else</h2>
 *
 * <p>The baseline reads the current date at REQUEST time, on L215 for the monthly preset and L241
 * for the yearly one, and then submits the RESOLVED pair as parameters. This class may therefore
 * resolve a preset, and it is the only class in this package that may: the two generators receive an
 * explicit range and must never consult a clock, because a run whose range came from a clock could
 * not be reproduced. The resolution here goes through an injected {@link Clock} so that a test can
 * pin a date and assert the pair it produces, and the timestamp on the acceptance comes from
 * {@code TimestampFormatter.formatNow(Clock)}. That class publishes no no-argument format method, no
 * now method and no current-timestamp method, so a reader hunting for one will not find it and
 * should not add one. The package charter one level up records the boundary in full; the consequence
 * for this file is stated here so that a later reader neither deletes the clock from this class nor
 * copies it into a generator.</p>
 *
 * <h2>Alternatives Considered: what this class deliberately does not do</h2>
 *
 * <p>Running the report inline on the request thread was rejected. Report generation walks a whole
 * date range and the state machine exists to carry it, so holding a request thread open for the
 * duration would tie the caller's connection to the length of the range and put the run beyond
 * observation once the connection dropped. Starting an execution and returning its identifier lets
 * the caller poll a run that outlives the request that asked for it.</p>
 *
 * <p>Acquiring a job repository here was rejected. This class STARTS a state machine; the batch
 * context owns the chunk-oriented job repository and the durable step ledger that give a batch run
 * its restart identity, and a second repository in this module would be a second restart authority
 * with its own idea of which steps had completed. This module's build declares no batch starter of
 * any kind, so the absence is structural rather than conventional.</p>
 *
 * <p>Adopting a resilience library, and wrapping the orchestration call in a circuit breaker, were
 * both rejected. Retry needs no library: Spring Framework 7, which arrives with the Spring Boot
 * 4.1.0 parent this module inherits, moved retry into the core, where the enabling annotation is
 * {@code @EnableResilientMethods} and the attempt-bounding attribute is {@code maxRetries}, whose
 * total attempt count is one plus its value and whose default is three. Both names differ from those
 * of the older module they replace, and they are recorded because reaching for the older spelling
 * compiles cleanly and silently enables nothing. No retry is applied here in any case: a duplicate
 * start is answered by the orchestrator refusing a name already in use, so a retried submission is a
 * refusal rather than a second run of the same report. A breaker would add a failure mode without
 * removing one, the synchronous hop being an in-network control-plane call already bounded by the
 * per-call ceiling {@code com.carddemo.reporting.config.StepFunctionsConfig} sets on the client this
 * class is given.</p>
 *
 * <h2>Trade-offs: parity here rests on transcription rather than on a golden master</h2>
 *
 * <p>The two report GENERATORS this package holds are driven by batch programs, and L40 to L42 of
 * {@code tests/README.md} records that the twelve batch programs contain no CICS verbs and ten of
 * them run standalone, so those two have a golden-master oracle. {@code CORPT00C} has none. L43 to
 * L46 of the same file, restated at L83 to L85, records that the online programs use the CICS
 * command-level API and cannot be driven end to end without a CICS runtime, so only their
 * extractable field-validation logic is unit-tested. Parity for this class therefore rests on
 * transcribed logic and on the record contracts it cites, which is a weaker footing than a
 * byte-for-byte comparison. It is stated plainly rather than glossed over, in the spirit of the
 * auditability doctrine at L50 and L51 of that file, precisely because the absence of an oracle
 * raises rather than lowers what the documentation has to carry.</p>
 *
 * <p>No monetary value passes through this class, and it composes no report bytes: the record
 * layouts, the edit masks and the band widths belong to the mapper package, the queries to the
 * repository package, and the status codes and request binding to the API package. It owns no schema
 * and performs no write of any kind against the database.</p>
 */
@Service
public class ReportExecutionService {

    // Assumptions: the value behind this key arrives as an environment variable the task definition
    //     resolves from Parameter Store, as application.yml L1236 declares, so no resource
    //     identifier literal appears in this file. It carries no fallback: a reporting service that
    //     started without knowing what to submit to would accept requests it could never fulfil.
    /**
     * Configuration key naming the state machine an on-demand submission starts.
     */
    public static final String STATE_MACHINE_ARN_PROPERTY =
            "carddemo.reporting.step-functions.state-machine-arn";

    /**
     * Report name a monthly run carries, exactly as the baseline assigns it at L214.
     */
    public static final String MONTHLY_REPORT_NAME = "Monthly";

    /**
     * Report name a yearly run carries, exactly as the baseline assigns it at L240.
     */
    public static final String YEARLY_REPORT_NAME = "Yearly";

    /**
     * Report name a custom run carries, exactly as the baseline assigns it at L433.
     */
    public static final String CUSTOM_REPORT_NAME = "Custom";

    // Assumptions: the two accepted answers are the ones the baseline tests for at L478 and L480 of
    //     app/cbl/CORPT00C.cbl, where each is matched in upper and lower case. The comparison below
    //     is therefore case-insensitive rather than exact, and AAP Rule T8 keeps the letters
    //     themselves verbatim.
    /**
     * Answer that confirms a submission.
     */
    public static final String CONFIRM_YES = "Y";

    /**
     * Answer that declines a submission deliberately.
     */
    public static final String CONFIRM_NO = "N";

    // Assumptions: the four values below are the literals the baseline moves rather than derived
    //     quantities, so they are named here and used in place of bare digits. L219 moves '01' as
    //     the monthly start day and L223 moves 1 as the day it rolls from; L227 moves 1 as the month
    //     it rolls to and L225 compares the incremented month against 12; L245 and L246 move '01' as
    //     the yearly start month and day; L250 and L251 move '12' and '31' as the yearly end pair.
    /**
     * First day of any calendar month.
     */
    private static final int FIRST_DAY_OF_MONTH = 1;

    /**
     * Ordinal of January, the month a monthly roll lands on and a yearly range opens with.
     */
    private static final int JANUARY = 1;

    /**
     * Ordinal of December, the month a yearly range closes with.
     */
    private static final int DECEMBER = 12;

    /**
     * Last day of December, the day a yearly range closes with.
     */
    private static final int LAST_DAY_OF_DECEMBER = 31;

    /**
     * Count of months in a year, the threshold above which a monthly roll increments the year.
     */
    private static final int MONTHS_IN_YEAR = 12;

    /**
     * Request field naming the lower bound of a custom range.
     */
    private static final String START_DATE_FIELD = "startDate";

    /**
     * Request field naming the upper bound of a custom range.
     */
    private static final String END_DATE_FIELD = "endDate";

    /**
     * Request field naming the report-type selection as a whole.
     */
    private static final String REPORT_TYPE_FIELD = "reportType";

    /**
     * Request field naming the confirmation answer.
     */
    private static final String CONFIRM_FIELD = "confirm";

    // Assumptions: the failure path below renders through ThrowableDigest rather than through the
    //     throwable itself, because that helper bounds what reaches an operational record -- 8 cause
    //     levels and 3 head frames -- and omits the message text a driver attached, which is the one
    //     part of a failure that can carry a value this service never validated.
    private static final Logger LOG = LoggerFactory.getLogger(ReportExecutionService.class);

    private final SfnClient sfnClient;

    private final String stateMachineArn;

    // Assumptions: this is the ONE legitimate clock in this package and the reason is the request
    //     edge itself. app/cbl/CORPT00C.cbl reads the current date at L215 and L241 to expand a
    //     preset, so a class that expands presets cannot be clock-free; the two generators receive
    //     the expanded pair and must stay clock-free so that a rerun reproduces its own output.
    //     Injecting the clock rather than reading the current date directly is what lets a test pin
    //     a day and assert the pair, and it is the only reason this field exists.
    private final Clock clock;

    /**
     * Records the collaborators this service composes.
     *
     * <p>Assumptions: all three arrive by constructor injection and none is discovered at a call
     * site, so an instance is complete once built and holds no mutable working storage. A COBOL
     * program's working storage is process-wide and single-threaded -- the report request group at
     * L83 to L125 of {@code app/cbl/CORPT00C.cbl} is owned outright by the one running program --
     * whereas one instance of this class serves many concurrent callers, so every value that varies
     * per submission below is a parameter or a local.</p>
     *
     * @param sfnClient the orchestration client this service submits through, contributed by
     *     {@code com.carddemo.reporting.config.StepFunctionsConfig}, which is also where the bounded
     *     per-call ceiling on it is set
     * @param stateMachineArn the state machine an on-demand submission starts, bound from
     *     {@value #STATE_MACHINE_ARN_PROPERTY}
     * @param clock the clock a preset is expanded against, injected so that a pinned day yields a
     *     predictable pair of bounds
     * @throws NullPointerException if {@code sfnClient}, {@code stateMachineArn} or {@code clock} is
     *     {@code null}, because an instance missing any of the three could accept a submission it
     *     could never place
     */
    public ReportExecutionService(
            SfnClient sfnClient,
            @Value("${" + STATE_MACHINE_ARN_PROPERTY + "}") String stateMachineArn,
            Clock clock) {
        this.sfnClient = Objects.requireNonNull(sfnClient, "sfnClient must not be null");
        this.stateMachineArn =
                Objects.requireNonNull(stateMachineArn, "stateMachineArn must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Resolves which of the three report types a request selected.
     *
     * <p>Assumptions: the three selections are mutually exclusive one-character marks, and the
     * baseline chooses between them with a single condition chain whose arms are at L213, L239 and
     * L256 of {@code app/cbl/CORPT00C.cbl}. Each arm tests its mark against BOTH the space and the
     * low-value figurative constants, so a mark holding low values is absent and not present. A test
     * against blankness alone would misclassify it, because a low-value character is not whitespace;
     * that is why the presence test below goes through the shared field-state helper, whose
     * never-supplied predicate answers true for a null, an empty value, a run of low values and a run
     * of spaces alike.</p>
     *
     * <p>Assumptions: resolving the type is separated from starting the run so that a caller can
     * establish what was asked for before the confirmation answer is read. The baseline resolves the
     * type and the range first and reaches the confirmation only inside the submission paragraph at
     * L462, so a caller that read the confirmation first could answer a request selecting nothing at
     * all as a successful cancellation.</p>
     *
     * @param request the report request whose selection is wanted; must not be {@code null}
     * @return the report name the baseline assigns for the selected type, one of
     *     {@value #MONTHLY_REPORT_NAME}, {@value #YEARLY_REPORT_NAME} or
     *     {@value #CUSTOM_REPORT_NAME}; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws ClientInputException if no type is selected or more than one is, neither of which the
     *     baseline's condition chain resolves to a range
     */
    public String resolveReportName(ReportRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        boolean monthly = isMarked(request.monthly());
        boolean yearly = isMarked(request.yearly());
        boolean custom = isMarked(request.custom());
        int selected = (monthly ? 1 : 0) + (yearly ? 1 : 0) + (custom ? 1 : 0);

        // WHY : Assumptions: the marks are counted rather than merely tested for emptiness, because
        //       two distinct request faults both reach here and the count is what names which one
        //       occurred. A request with no mark has no range to run over, and the baseline answers
        //       it from the final arm of the chain at L437 to L440. A request with two marks has two
        //       candidate ranges, and the baseline resolves only the first arm that matches, so
        //       accepting it would silently discard a selection the caller made.
        if (selected != 1) {
            // WHY : Assumptions: the refusal is the shared client-input type rather than a plain
            //       argument exception, so GlobalExceptionHandler renders it under
            //       ApiError.CODE_VALIDATION as one entry in the structured per-field error array,
            //       carrying the correlation identity and the request path the problem shape needs.
            //       This class holds none of those three, which is why the package charter places
            //       the handler in the shared kernel and not here. The baseline answers the same
            //       fault from the final arm of its own chain, at L437 to L440 of
            //       app/cbl/CORPT00C.cbl.
            throw new ClientInputException(ApiError.CODE_VALIDATION, REPORT_TYPE_FIELD,
                    "exactly one of monthly, yearly or custom must be selected but " + selected
                            + " were");
        }
        if (monthly) {
            return MONTHLY_REPORT_NAME;
        }
        return yearly ? YEARLY_REPORT_NAME : CUSTOM_REPORT_NAME;
    }

    /**
     * Reports whether a request confirmed its submission.
     *
     * <p>Assumptions: the baseline distinguishes THREE answers and this method distinguishes the same
     * three. An absent answer is refused with its own sentence at L464 to L474 of
     * {@code app/cbl/CORPT00C.cbl}; an affirmative answer continues to the write loop through L478
     * and L479; a negative answer clears the screen and stops at L480 to L483; and an unrecognised
     * answer is refused at L484 to L493. The gate that guards the loop is the flag test at L476,
     * which is why a submission is reached at L496 only for the affirmative case.</p>
     *
     * <p>Trade-offs: the two refusal sentences below are constants and neither repeats the answer
     * that caused it, whereas the baseline interpolates the offending value into its message at L487.
     * The compromise accepted is a slightly less specific sentence in exchange for a message an alert
     * rule can match on without matching on caller input, which is the same discipline the rest of
     * this module applies to operational records. Nothing about the CONTROL FLOW differs: the same
     * three answers reach the same three outcomes.</p>
     *
     * @param request the report request whose confirmation answer is wanted; must not be {@code null}
     * @return {@code true} when the answer confirms the submission, {@code false} when it declines
     *     deliberately
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws ClientInputException if the answer is absent, or is neither of the two the baseline
     *     recognises
     */
    public boolean isConfirmed(ReportRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        String answer = request.confirm();
        if (FieldValidationFlag.isNeverSupplied(answer)) {
            // WHY : Assumptions: an absent answer is answered separately from an unrecognised one
            //       because the baseline answers them separately, at L464 and L484 respectively, with
            //       two different sentences. Collapsing them would tell a caller who supplied nothing
            //       that it supplied something invalid.
            throw new ClientInputException(ApiError.CODE_VALIDATION, CONFIRM_FIELD,
                    "confirm must be supplied before a report is submitted");
        }
        if (CONFIRM_YES.equalsIgnoreCase(answer)) {
            return true;
        }
        if (CONFIRM_NO.equalsIgnoreCase(answer)) {
            return false;
        }
        // WHY : Assumptions: this arm stays reachable even though ReportRequest constrains the
        //       answer declaratively to the 4 letters of its pattern within 1 declared position,
        //       because that constraint is applied only on the way in over HTTP. A caller inside the
        //       application reaches this method directly, and a service that trusted a boundary it
        //       does not own would start a run on an answer nobody validated.
        throw new ClientInputException(ApiError.CODE_VALIDATION, CONFIRM_FIELD,
                "confirm must be " + CONFIRM_YES + " or " + CONFIRM_NO + " but was of length "
                        + answer.length());
    }

    /**
     * Resolves the inclusive pair of business dates the selected report type covers.
     *
     * <p>Each of the three types derives its pair differently and each derivation is the baseline's
     * own, so the three are transcribed into three separate helpers below rather than into one
     * branching block. Every one of them is documented in full, including the private ones, because
     * the presence clause of the user-specified explainability rule at L15 of the rules document
     * names no visibility.</p>
     *
     * @param request the report request whose range is wanted; must not be {@code null}
     * @param reportName the resolved report name, as {@link #resolveReportName(ReportRequest)}
     *     returns it; must not be {@code null}
     * @return the inclusive pair the report covers; never {@code null}
     * @throws NullPointerException if {@code request} or {@code reportName} is {@code null}
     * @throws ClientInputException if a custom range omits a bound, states a bound the shared date
     *     edit rejects, or states an upper bound below its lower bound
     * @throws IllegalArgumentException if {@code reportName} is none of the three names the baseline
     *     assigns, which would mean a caller had bypassed
     *     {@link #resolveReportName(ReportRequest)}
     */
    public DateRange resolveRange(ReportRequest request, String reportName) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(reportName, "reportName must not be null");

        if (MONTHLY_REPORT_NAME.equals(reportName)) {
            return resolveMonthlyRange();
        }
        if (YEARLY_REPORT_NAME.equals(reportName)) {
            return resolveYearlyRange();
        }
        if (CUSTOM_REPORT_NAME.equals(reportName)) {
            return resolveCustomRange(request);
        }
        // WHY : Assumptions: an unrecognised name is an internal invariant failure and not a caller
        //       fault, so it is raised as a plain argument exception rather than as the client-input
        //       type. GlobalExceptionHandler tests for the client-input type specifically and lets a
        //       bare argument exception fall through to ApiError.CODE_INTERNAL, which is the channel
        //       the alerting watches and where a service that constructed its own bad argument
        //       belongs; routing it to the caller would tell a client to correct a request it had
        //       sent correctly. The 3 names admitted are the ones the baseline assigns at L214, L240
        //       and L433 and nothing else.
        throw new IllegalArgumentException(
                "reportName must be one of " + MONTHLY_REPORT_NAME + ", " + YEARLY_REPORT_NAME
                        + " or " + CUSTOM_REPORT_NAME + ", but was " + reportName);
    }

    /**
     * Expands the monthly preset to the whole of the current calendar month.
     *
     * <p>Assumptions: a monthly run covers the FIRST day of the current month through the LAST day of
     * the same month, and the second half of that is where a reading can go wrong. The baseline sets
     * the start at L217 to L219 of {@code app/cbl/CORPT00C.cbl} from the current year and month with
     * a literal day of one. It then derives the end at L223 to L230 with the canonical
     * last-day-of-month idiom: move one into the day, add one to the month, roll the year and reset
     * the month to one when the incremented month exceeds twelve, and finally convert the date to its
     * integer form, subtract a single day and convert back. L232 to L234 move that year, month and
     * day into the end bound. A peer document describing this program reads the end bound as the
     * current day, making a monthly run month-to-date; that reading is superseded by the program text
     * cited here, which reaches the end bound only after subtracting a day from the first of the
     * FOLLOWING month. The two readings agree on exactly one day of each month and differ on every
     * other, so the distinction is not cosmetic.</p>
     *
     * <p>Alternatives Considered: asking the date class for the length of the month and setting the
     * day to it, which is shorter. Rejected because the four steps below are the baseline's four
     * steps in the baseline's order, including the year roll it performs explicitly, so a reader
     * comparing this method against L223 to L230 can see the correspondence line by line. The
     * shorter form would produce the same date while hiding the roll that L225 to L228 makes
     * visible.</p>
     *
     * @return the first and last days of the current calendar month; never {@code null}
     */
    private DateRange resolveMonthlyRange() {
        LocalDate today = LocalDate.now(clock);
        LocalDate firstOfMonth = today.withDayOfMonth(FIRST_DAY_OF_MONTH);

        int rolledYear = today.getYear();
        int rolledMonth = today.getMonthValue() + 1;
        if (rolledMonth > MONTHS_IN_YEAR) {
            rolledYear = rolledYear + 1;
            rolledMonth = JANUARY;
        }

        // WHY : Assumptions: the subtraction is a day and not a month, which is what makes the
        //       result correct for a 28, 29, 30 or 31 day month without a table of month lengths and
        //       without a leap-year test. Subtracting a month from the first of the following month
        //       would return the first of THIS month, which is the start bound rather than the end.
        LocalDate lastOfMonth =
                LocalDate.of(rolledYear, rolledMonth, FIRST_DAY_OF_MONTH).minusDays(1);

        return new DateRange(firstOfMonth, lastOfMonth);
    }

    /**
     * Expands the yearly preset to the whole of the current calendar year.
     *
     * <p>Assumptions: both bounds are literal in the baseline rather than derived. L243 and L244 of
     * {@code app/cbl/CORPT00C.cbl} move the current year into the start and the end alike, L245 and
     * L246 move a literal month and day of one into the start, and L250 and L251 move a literal
     * twelve and thirty-one into the end. The end bound is therefore the last day of December and not
     * the current day, which is the same shape the monthly preset has: both presets cover a COMPLETE
     * calendar period and both name an end bound still in the future for most of that period.</p>
     *
     * @return the first and last days of the current calendar year; never {@code null}
     */
    private DateRange resolveYearlyRange() {
        int year = LocalDate.now(clock).getYear();

        return new DateRange(
                LocalDate.of(year, JANUARY, FIRST_DAY_OF_MONTH),
                LocalDate.of(year, DECEMBER, LAST_DAY_OF_DECEMBER));
    }


    /**
     * Takes both bounds of a custom range from the request and puts each through the shared date
     * edit.
     *
     * <p>Assumptions: the baseline assembles each bound from THREE typed screen components before it
     * edits anything, moving the year, month and day of the start at L381 to L383 of
     * {@code app/cbl/CORPT00C.cbl} and the same three of the end at L384 to L386, so the six
     * components the user types become two ten-character values. Those two values are what reach this
     * class: the request type carries the bounds already assembled, in the same ten-character
     * separated form the baseline names at L72 with
     * {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'}, and it constrains each of them
     * declaratively to that shape. The assembly therefore happens at the client edge rather than
     * here, and what this method inherits is the pair, not the six.</p>
     *
     * <p>Alternatives Considered: taking the six components as six parameters so that a refusal could
     * name the individual component at fault, as the baseline does when it positions the cursor on
     * the month component at L403 and L423. Rejected because the bounds cross the service boundary as
     * assembled values and re-splitting them here would invent a component identity the request never
     * carried; the refusal names the bound instead, which is the field a client can actually
     * correct.</p>
     *
     * @param request the report request carrying the two assembled bounds; must not be {@code null}
     * @return the inclusive pair the request stated; never {@code null}
     * @throws ClientInputException if either bound is absent, is rejected by the shared date edit, or
     *     if the upper bound falls below the lower bound
     */
    private DateRange resolveCustomRange(ReportRequest request) {
        LocalDate start = requireEditedBound(request.startDate(), START_DATE_FIELD);
        LocalDate end = requireEditedBound(request.endDate(), END_DATE_FIELD);

        // WHY : Alternatives Considered: leaving the ordering of the two bounds to
        //       TransactionReportService, which already refuses an inverted range on entry to its
        //       generation method and registers that refusal as its own divergence from the
        //       baseline's silent empty run. Rejected because THIS path is asynchronous: that
        //       generator runs inside the execution this class starts, so its refusal would arrive
        //       after the caller had already been handed an execution ARN and told the run was
        //       accepted. The request edge is the only place the caller can be told synchronously,
        //       and this test refuses nothing that generator would have accepted, because both
        //       compare the same 2 bounds with the same strict ordering.
        if (end.isBefore(start)) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, END_DATE_FIELD,
                    END_DATE_FIELD + " must not be earlier than " + START_DATE_FIELD);
        }
        return new DateRange(start, end);
    }

    /**
     * Puts one bound through the shared date edit, honouring the tolerance this caller applies.
     *
     * <p>Refactoring Rationale: the edit itself is DELEGATED and not transcribed here.
     * {@code app/cbl/CORPT00C.cbl} does not implement its own date rules either: it calls the
     * dynamically-invoked subprogram at {@code app/cbl/CSUTLDTC.cbl}, a 157-line wrapper, at L392 and
     * again at L412. AAP Rule T2 turns one such shared contract into one type import from the single
     * package that owns it, so the rules live in the shared kernel and this class calls them. Writing
     * the leap-year and range tests out again here would create a second authority on whether a date
     * is valid, and two authorities can disagree.</p>
     *
     * <p>Assumptions: the parameter group the baseline passes is declared at L129 to L136. The date
     * and the mask are ten characters each at L130 and L131; the result at L132 is composed of a
     * four-character severity at L133, eleven characters of padding at L134, a four-character message
     * number at L135 and a sixty-one character message at L136, which is 4 + 11 + 4 + 61 = 80 and
     * therefore the whole declared result. The severity, the number and the message are the triple a
     * refusal reports; the padding is dropped and the drop is recorded here, as AAP Rule T1 requires
     * of a dropped filler item.</p>
     *
     * <p>Assumptions: this caller FORGIVES one particular rejection and the tolerance is
     * caller-specific rather than general. Both call sites accept a zero severity and additionally
     * accept a non-zero severity whose message number is 2513, the test being written as an
     * inequality against that number at L399 for the start bound and at L419 for the end bound. That
     * number identifies the unsupported-range outcome, which the shared kernel reaches only for a
     * WELL-FORMED calendar date that falls below the supported calendar floor, so a bound this
     * tolerance forgives is always still a date this method can convert. The proof that the tolerance
     * belongs to this caller and not to the shared rules is {@code app/cpy/CSUTLDPY.cpy} L298, which
     * calls the same subprogram and tests the severity alone with no tolerance at all. Pushing it
     * down into the shared validator would therefore relax every other caller, and dropping it here
     * would refuse a bound the baseline accepts.</p>
     *
     * @param bound the bound as the request stated it, which may be {@code null} or unsupplied
     * @param field the request field the bound came from, used to name a refusal against the field a
     *     client can correct
     * @return the bound as a calendar date; never {@code null}
     * @throws ClientInputException if the bound is unsupplied, is not the ten-character width the
     *     mask declares, or is rejected by the shared edit with a severity this caller does not
     *     forgive
     */
    private static LocalDate requireEditedBound(String bound, String field) {
        if (FieldValidationFlag.isNeverSupplied(bound)) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    field + " must be supplied for a custom report");
        }

        // WHY : Assumptions: the width is established HERE, before the shared edit is called, and
        //       the reason is which side owns the answer. A width disagreement is the one condition
        //       that edit raises about a caller's value rather than reporting as feedback, and it
        //       raises it as an argument exception, which the shared advice routes to the internal
        //       channel. Checking first keeps a caller's short bound a caller refusal on the named
        //       field instead of an internal report, and it does so without discarding anything:
        //       the 'YYYY-MM-DD' mask admits exactly one width, the 10 positions
        //       app/cbl/CORPT00C.cbl declares at L72, so no value that clears this test can still
        //       trip that condition.
        if (bound.length() != DateEditValidator.MASKED_DATE_LENGTH) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    field + " must be exactly " + DateEditValidator.MASKED_DATE_LENGTH
                            + " characters in the form " + DateEditValidator.DATE_FORMAT_MASK);
        }

        LanguageEnvironmentResult edited = DateEditValidator.evaluateWithLanguageEnvironment(
                bound, DateEditValidator.DATE_FORMAT_MASK);

        // WHY : Assumptions: the acceptance is the DISJUNCTION the two baseline call sites write and
        //       not the severity test alone. The first arm is the zero severity both sites check
        //       first, at L396 and L416; the second is the tolerance those sites add at L399 and
        //       L419, named here by the shared predicate rather than by a bare number so that the one
        //       place the number is declared stays the only place it appears.
        if (edited.acceptable() || edited.unsupportedRange()) {
            // WHY : Assumptions: the conversion cannot fail once the edit has accepted the value,
            //       and this is provable rather than hoped for. The shared edit CONSTRUCTS the
            //       calendar date itself and only then compares it against the supported calendar
            //       floor of 1582-10-15 to choose between acceptance and the unsupported-range
            //       outcome, so both accepted outcomes describe a value the date class can parse. A
            //       guarded conversion here would add a branch that no input can reach and that no
            //       test could therefore cover.
            return LocalDate.parse(bound);
        }

        throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                field + " was rejected by the date edit with severity " + edited.severity()
                        + " and message number " + edited.messageNumber());
    }

    /**
     * An inclusive pair of business dates a report covers.
     *
     * <p>Assumptions: both bounds are inclusive, which is the baseline's own reading. Its sort
     * condition at L47 and L48 of {@code app/jcl/TRANREPT.jcl} selects a record whose processing date
     * is greater than or equal to the lower bound AND less than or equal to the upper one, so a
     * transaction dated on either bound is inside the range. The half-open instant interval a query
     * needs is derived from this pair where the query is issued, so that this type states one thing
     * and the conversion happens once.</p>
     *
     * @param start the first business date covered; never {@code null}
     * @param end the last business date covered, never earlier than {@code start}; never {@code null}
     */
    public record DateRange(LocalDate start, LocalDate end) {

        /**
         * Refuses a pair missing either bound at construction.
         *
         * @param start the first business date covered, required because a range open at the lower
         *     end would select every record ever posted
         * @param end the last business date covered, required for the same reason at the other end
         * @throws NullPointerException if either bound is {@code null}
         */
        public DateRange {
            Objects.requireNonNull(start, "start must not be null");
            Objects.requireNonNull(end, "end must not be null");
        }
    }


    /**
     * Starts an execution for a confirmed request and describes what was started.
     *
     * <p>Assumptions: the resolved pair is passed as execution INPUT rather than being read from a
     * clock inside the state machine, which is the discipline {@code app/jcl/INTCALC.jcl} applies at
     * L22 when it injects a business date as a job parameter. A run whose range came from a clock
     * could not be rerun to the same output. The same pair is also echoed back on the response, so the
     * single value resolved at this edge reaches both the selection the generator performs and the
     * header a caller titles the run with, which is the agreement L429 to L432 of
     * {@code app/cbl/CORPT00C.cbl} secures by moving one value into two destinations.</p>
     *
     * <p>Assumptions: the two header names come from the report record contract and not from the
     * screen. {@code app/cpy/CVTRA07Y.cpy} declares them as values of the report header group,
     * {@code REPT-SHORT-NAME PIC X(38)} at L5 and L6 and {@code REPT-LONG-NAME PIC X(41)} at L7 and
     * L8, and they are read from the one place in this module that already carries the group's
     * literals rather than being written out again here. The screen's own two title lines are a
     * different contract at a different declared width and are not these.</p>
     *
     * <p>Assumptions: the gate is re-asserted before anything is started. The baseline reaches its
     * write loop at L496 only through the flag test at L476, so a request that was never confirmed
     * never reaches the queue. Re-testing here rather than trusting the call order means a future
     * caller that inverted the sequence could not start a run the requester had declined.</p>
     *
     * @param request the confirmed report request; must not be {@code null}
     * @param reportName the resolved report name, as {@link #resolveReportName(ReportRequest)}
     *     returns it; must not be {@code null}
     * @param rangeStart the first business date of the resolved range; must not be {@code null}
     * @param rangeEnd the last business date of the resolved range; must not be {@code null}
     * @return a description of the accepted run, carrying the execution ARN a caller observes it
     *     through; never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws ClientInputException if the request does not carry a confirming answer, so that nothing
     *     is started for a request that declined or never answered
     * @throws IllegalStateException if the orchestrator refuses the start, which is the loud failure
     *     that replaces the discarded write and is raised from the guarded call below rather than
     *     declared by it
     * @throws IllegalArgumentException if the assembled description is one the response contract
     *     refuses, which has two reachable causes worth naming because neither is visible from this
     *     method's own statements: a {@code reportName} that is not one of the three the baseline
     *     assigns, reaching here only from a caller that bypassed
     *     {@link #resolveReportName(ReportRequest)}, and a start that returned no execution handle at
     *     all. The contract is asserted by the response type rather than restated here, so that one
     *     type stays the single authority on what a submission description may hold
     */
    public ReportSubmissionResponse start(
            ReportRequest request, String reportName, LocalDate rangeStart, LocalDate rangeEnd) {

        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(reportName, "reportName must not be null");
        Objects.requireNonNull(rangeStart, "rangeStart must not be null");
        Objects.requireNonNull(rangeEnd, "rangeEnd must not be null");

        if (!isConfirmed(request)) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, CONFIRM_FIELD,
                    "a report is started only for a confirmed request");
        }

        String startDate = rangeStart.toString();
        String endDate = rangeEnd.toString();

        // WHY : Alternatives Considered: assembling this input through a serialisation mapper rather
        //       than by concatenation. Rejected because all 3 values are scalars this method has
        //       already established -- a report name drawn from the 3 constants above and 2 bounds
        //       each rendered from a calendar date into 10 characters -- so none of them can carry a
        //       character a mapper would shape differently, while routing them through one would put
        //       the shape of the execution input under a module-wide serialisation configuration
        //       this class does not own. The state machine reads these 3 names, so the shape is a
        //       contract between this method and the infrastructure code that declares the machine.
        String executionInput = "{\"reportType\":\"" + reportName.toLowerCase(Locale.ROOT)
                + "\",\"startDate\":\"" + startDate
                + "\",\"endDate\":\"" + endDate + "\"}";

        StartExecutionResponse started;
        try {
            started = sfnClient.startExecution(StartExecutionRequest.builder()
                    .stateMachineArn(stateMachineArn)
                    .name(executionName(reportName, startDate, endDate))
                    .input(executionInput)
                    .build());
        } catch (SdkException refused) {
            // WHY : Assumptions: the guard is SdkException, the software development kit's own
            //       exception supertype, and NOTHING wider -- the same 1 type S3ArtifactWriter in
            //       this module guards every one of its own storage calls on. A wider guard would
            //       absorb an invariant failure raised by the call site, such as the response
            //       contract refusing an assembled description, and report it as a submission
            //       problem.
            //
            // WHY : Refactoring Rationale: this line is the POINT OF USE of the one transport
            //       decision recorded on this class, restated beside the code that carries it out
            //       rather than left to be inferred from the class header. The baseline's queue
            //       definition carries ERROROPTION(IGNORE) on L501 of app/csd/CARDDEMO.CSD, which is
            //       what lets the region drop an extrapartition write and leave the request
            //       unrecorded -- while the program itself, at L521 to L531 of
            //       app/cbl/CORPT00C.cbl, checks the response code and holds a sentence ready for
            //       exactly that condition. Rethrowing here rather than returning a null or an empty
            //       result is what keeps the target from reproducing the one behaviour the transport
            //       change exists to remove. The refusal is logged with the report type and the
            //       range, because the shared advice records the exception class and the request path
            //       but not which report failed to start, and that is the datum an operator needs.
            LOG.error("report submission refused reportName={} startDate={} endDate={} failure={}",
                    reportName, startDate, endDate, ThrowableDigest.of(refused));

            // WHY : Assumptions: the type raised is a plain state exception and NOT the client-input
            //       type, because the caller's request was well formed and the orchestrator declined
            //       it. GlobalExceptionHandler routes a bare state exception to
            //       ApiError.CODE_INTERNAL, which is the channel the alerting watches and where a
            //       submission this service could not place belongs; reporting it as a caller fault
            //       would answer ApiError.CODE_VALIDATION instead, telling a client to correct a
            //       request that was already correct and keeping a real outage out of the internal
            //       channel. The client-input type could not carry the cause in any case, declaring
            //       no constructor that accepts one, and the cause is the whole point here: it is the
            //       evidence the baseline held ready at L531 of app/cbl/CORPT00C.cbl and that the
            //       region was told to ignore.
            throw new IllegalStateException(
                    "the report execution could not be started for a " + reportName + " report",
                    refused);
        }

        return new ReportSubmissionResponse(
                started.executionArn(),
                reportName,
                ReportBandLayouts.REPORT_SHORT_NAME,
                ReportBandLayouts.REPORT_LONG_NAME,
                startDate,
                endDate,
                TimestampFormatter.formatNow(clock));
    }

    /**
     * Builds the execution name a submission is started under.
     *
     * <p>Alternatives Considered: generating a name with a random component, which never collides.
     * Rejected because the 10-second per-call ceiling
     * {@code com.carddemo.reporting.config.StepFunctionsConfig} sets on the client can abandon a call
     * the orchestrator went on to accept, and a caller retrying after that would start the same report
     * twice, producing 2 sets of output objects with nothing to say which was current. Deriving the
     * name from the report type and both bounds makes the second attempt a refusal by the orchestrator
     * instead, which is the behaviour a duplicate submission should have.</p>
     *
     * <p>Assumptions: the separator is a hyphen because an execution name admits a restricted
     * character set, and the colon that a timestamp form would join values with is not in it. The 3
     * parts joined are the report type and the 2 bounds, each bound already 10 characters.</p>
     *
     * @param reportName the resolved report name
     * @param startDate the first business date of the range, in its ten-character form
     * @param endDate the last business date of the range, in its ten-character form
     * @return the execution name; never {@code null}
     */
    private static String executionName(String reportName, String startDate, String endDate) {
        return reportName.toLowerCase(Locale.ROOT) + "-" + startDate + "-" + endDate;
    }

    /**
     * Reports whether a one-character selection field carries a mark.
     *
     * <p>Assumptions: presence is decided by the shared field-state predicate rather than by a
     * blankness test, because the baseline compares each selection against BOTH the space and the
     * low-value figurative constants at L213, L239 and L256 of {@code app/cbl/CORPT00C.cbl}. A
     * low-value character is not whitespace, so a blankness test would report a field holding low
     * values as marked and select a report the caller never asked for. The value of the mark is not
     * examined beyond its presence, which is also what the baseline does.</p>
     *
     * @param field the selection field to inspect, which may be {@code null} or unsupplied
     * @return {@code true} when the field carries a mark; {@code false} when it was never supplied
     */
    private static boolean isMarked(String field) {
        return !FieldValidationFlag.isNeverSupplied(field);
    }
}

