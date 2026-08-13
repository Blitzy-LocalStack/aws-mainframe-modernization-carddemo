package com.carddemo.reporting.service;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.observability.ThrowableDigest;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.common.validation.DateEditValidator.LanguageEnvironmentResult;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CorrelationIdFilter;
import com.carddemo.reporting.dto.ReportRequest;
import com.carddemo.reporting.dto.ReportSubmissionResponse;
import com.carddemo.reporting.mapper.ReportBandLayouts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionDoesNotExistException;
import software.amazon.awssdk.services.sfn.model.ExecutionAlreadyExistsException;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

/**
 * Starts an on-demand transaction-report run for the report-request screen.
 *
 * <p>{@code app/cbl/CORPT00C.cbl} is a 649-line online program. It offers three report types in an
 * ORDERED condition chain -- the first marked one runs and the others are never evaluated -- resolves
 * that one to a pair of business dates, gates the run behind a confirmation answer, and then hands the
 * request to the job entry subsystem. This class carries the same four steps and hands the request to a
 * second, smaller state machine instead.</p>
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

    /**
     * Header a caller sends a submission key in, and the field a refusal of one is named against.
     *
     * <p>Assumptions: the key travels as a HEADER and not as a member of the request body. It describes
     * the submission ATTEMPT rather than the report being requested, and the body's own descriptor
     * derives its component count arithmetically from the 17 named fields of {@code app/bms/CORPT00.bms}
     * -- so a 14th component would be a member of that record which no field of the screen accounts for,
     * and would falsify a derivation that is currently exact. A header is also where an idempotency key
     * conventionally travels, and this contract already carries one caller-supplied header of the same
     * kind in {@code X-Correlation-Id}.
     */
    public static final String IDEMPOTENCY_KEY_FIELD = "Idempotency-Key";

    /**
     * Longest submission key a caller may send.
     *
     * <p>Assumptions: the ceiling exists so that the composed execution name cannot exceed the
     * orchestrator's own {@value #EXECUTION_NAME_LIMIT}-character limit. The arithmetic is in
     * {@code validatedIdempotencyKey}, and it leaves room to spare at every report type and range.
     */
    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 40;

    /**
     * Longest name the orchestrator accepts for an execution.
     *
     * <p>Assumptions: the figure is the orchestrator's published limit and is recorded here because the
     * key ceiling above is derived FROM it. Naming it makes that derivation checkable instead of leaving
     * the key ceiling looking like a preference.
     */
    public static final int EXECUTION_NAME_LIMIT = 80;

    /**
     * Characters a submission key may hold.
     *
     * <p>Assumptions: the set is letters, digits, the hyphen and the underscore -- comfortably inside
     * what an execution name admits, so it needs no revision if the orchestrator's own list of refused
     * punctuation changes. It is compiled once as a constant rather than per call because a submission
     * is a request-path operation.
     */
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

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

    /**
     * Opening quotation mark the reference wraps an unrecognised confirmation answer in.
     *
     * <p>Assumptions: the reference builds this sentence with a {@code STRING} statement at L485 to
     * L490 from three operands: this quotation mark delimited by size, the answer delimited by a
     * space, and the fragment below delimited by size. The three are declared separately here so the
     * assembly at the point of use reads against those five lines rather than hiding the
     * interpolation inside one literal.</p>
     */
    public static final String INVALID_CONFIRM_PREFIX = "\"";

    /**
     * Verbatim fragment the reference appends to a quoted unrecognised confirmation answer.
     *
     * <p>Assumptions: reproduced character for character from L488 and L489 of
     * {@code app/cbl/CORPT00C.cbl}, opening with the closing quotation mark and ending in three
     * full stops with no space before them.</p>
     */
    public static final String INVALID_CONFIRM_SUFFIX = "\" is not a valid value to confirm...";

    /**
     * Verbatim sentence the reference emits when the start bound's month component is empty.
     *
     * <p>Assumptions: reproduced character for character from L261 of {@code app/cbl/CORPT00C.cbl},
     * including the capitalised {@code NOT}, the spaced hyphen and the three trailing full stops. It
     * is the FIRST arm of the six-way blank chain at L258 to L302, which is why it is the sentence an
     * entirely empty start bound carries.</p>
     */
    public static final String MESSAGE_START_DATE_MONTH_EMPTY =
            "Start Date - Month can NOT be empty...";

    /**
     * Verbatim sentence the reference emits when the end bound's month component is empty.
     *
     * <p>Assumptions: reproduced character for character from L282 of {@code app/cbl/CORPT00C.cbl},
     * being the fourth arm of the same chain and the first one that concerns the end bound.</p>
     */
    public static final String MESSAGE_END_DATE_MONTH_EMPTY =
            "End Date - Month can NOT be empty...";

    /**
     * Verbatim sentence the reference emits when the assembled start bound fails the date edit.
     *
     * <p>Assumptions: reproduced character for character from L400 of {@code app/cbl/CORPT00C.cbl}.
     * Note the LOWER-CASE {@code date}, where the six blank messages and the six component-range
     * messages capitalise their nouns -- the reference is inconsistent here and the inconsistency is
     * carried rather than tidied, because transformation rule T8 admits no editorial improvement to a
     * user-visible string.</p>
     */
    public static final String MESSAGE_START_DATE_INVALID = "Start Date - Not a valid date...";

    /**
     * Verbatim sentence the reference emits when the assembled end bound fails the date edit.
     *
     * <p>Assumptions: reproduced character for character from L420 of {@code app/cbl/CORPT00C.cbl},
     * carrying the same lower-case {@code date} as its counterpart above.</p>
     */
    public static final String MESSAGE_END_DATE_INVALID = "End Date - Not a valid date...";

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
     * Execution-input member naming which report type a run was started for.
     *
     * <p>⚠️ Refactoring Rationale: the three members below are named constants shared by the two halves of
     * ONE contract in this file -- the input {@link #start} composes and the input
     * {@link #describeExecution} reads back -- so a rename cannot land on one half and leave the other
     * reading a member that is no longer written. Both halves previously spelled the names as literals, and
     * a measured mutation of the name on the reading half alone returned no coordinates for every
     * successful run while the case asserting the written document still passed.</p>
     *
     * <p>⚠️ Assumptions: these are deliberately NOT the {@code *_FIELD} constants above, even where the
     * spelling coincides. Those name fields of the published request, which the contract may rename; these
     * name members of the document the state machine reads, which {@code infra/modules/step-functions-batch}
     * declares. Sharing one constant between the two would make an API rename silently change the
     * orchestration input.</p>
     */
    private static final String INPUT_REPORT_TYPE_MEMBER = "reportType";

    /**
     * Execution-input member naming the lower bound of the range a run covers.
     */
    private static final String INPUT_START_DATE_MEMBER = "startDate";

    /**
     * Execution-input member naming the upper bound of the range a run covers.
     */
    private static final String INPUT_END_DATE_MEMBER = "endDate";

    /**
     * The sentence the reference displays when no report type was marked.
     *
     * <p>Assumptions: the literal is {@code app/cbl/CORPT00C.cbl} L438 character for character,
     * including the three ASCII full stops and the lower-case "report" in both positions. It is a named
     * constant rather than an inline string because {@code reporting-api.yaml} publishes the same text at
     * L1988 as one of the messages this operation may carry, and a test can hold the two to each other
     * only if this side has a name.</p>
     *
     * <p>⚠️ Assumptions: the three full stops here have NO space before them, which is where this sentence
     * differs from the submission sentence, whose three dots ARE preceded by a space. The two are similar
     * enough to be mistaken for one string and are never merged.</p>
     *
     * <p>Refactoring Rationale: one constant carries this sentence where two briefly did, under two names
     * that differed only in wording. Two public names for one verbatim string is a hazard rather than a
     * convenience: a later edit correcting the text on one of them leaves the other stating that the same
     * screen shows something else, and the contract test that holds this text to the published catalogue
     * can only guard the name it was written against.</p>
     */
    public static final String MESSAGE_NO_REPORT_TYPE_SELECTED =
            "Select a report type to print report...";

    /**
     * Request field naming the confirmation answer.
     */
    private static final String CONFIRM_FIELD = "confirm";

    // Assumptions: the five values below govern the EXECUTION NAME, which is the orchestrator's
    //     uniqueness key rather than a label. A Standard Workflow refuses a name that any execution
    //     of the same machine has already used and holds that history for 90 days, so what the name
    //     is derived from decides which second submission is a duplicate and which is a new run.
    /**
     * Separator joining the parts of an execution name.
     *
     * <p>Assumptions: a hyphen, because an execution name admits a restricted character set and the
     * colon a timestamp form would join values with is not in it.</p>
     */
    private static final String EXECUTION_NAME_SEPARATOR = "-";

    /**
     * Bytes of the submission digest that reach the execution name.
     *
     * <p>Assumptions: twelve is chosen because twelve bytes is ninety-six bits, an exact multiple of the
     * six bits a base64 character carries, so the encoding needs no padding and renders to exactly
     * sixteen characters. That is what makes the suffix a FIXED width -- ten bytes would render to
     * fourteen characters and eleven to fifteen, both correct but neither landing on a whole character
     * boundary. Eighty is the orchestrator's ceiling on a name and the longest fixed part here is a
     * seven-character report type plus two ten-character bounds plus three separators, so a
     * sixteen-character suffix leaves the assembled name at forty-six and the bound cannot be reached by
     * any input. Ninety-six bits is far beyond what a per-submission key needs to avoid an accidental
     * collision.</p>
     */
    private static final int SUBMISSION_DIGEST_BYTES = 12;

    /**
     * Digest algorithm the submission suffix is derived with.
     *
     * <p>Assumptions: the platform specification requires every implementation to provide this
     * algorithm, so the checked lookup exception the standard library declares is unreachable here and
     * is converted rather than propagated. It is named rather than inlined so the one place it is
     * declared stays the only place it appears.</p>
     */
    private static final String SUBMISSION_DIGEST_ALGORITHM = "SHA-256";

    /**
     * The segment that identifies a state machine inside its own ARN.
     */
    private static final String STATE_MACHINE_ARN_SEGMENT = ":stateMachine:";

    /**
     * The segment that identifies an execution inside an execution ARN.
     */
    private static final String EXECUTION_ARN_SEGMENT = ":execution:";

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
     * <p>Assumptions: the three selections are one-character marks and the baseline chooses between
     * them with a single ordered condition chain whose arms are at L213, L239 and L256 of
     * {@code app/cbl/CORPT00C.cbl}. They are not mutually EXCLUSIVE -- nothing refuses a request that
     * marks two -- they are mutually PRECEDENT: the first marked arm runs and the rest are never
     * evaluated. Each arm tests its mark against BOTH the space and the
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
     * @throws ClientInputException if no type is marked at all, carrying
     *     {@value #MESSAGE_NO_REPORT_TYPE_SELECTED}, which is the reference's own final arm. More than
     *     one mark is NOT refused: the reference resolves the first marked arm and ignores the rest
     */
    public String resolveReportName(ReportRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // WHY : Assumptions: the three marks are tested in the baseline's own order and the FIRST
        //       present one wins, because that is what its condition chain does. EVALUATE TRUE
        //       selects the first arm whose subject is true and leaves the rest unevaluated, so a
        //       request marking both monthly and yearly runs the MONTHLY report at
        //       app/cbl/CORPT00C.cbl L213 and never reaches the yearly arm at L239 or the custom arm
        //       at L256. The precedence is therefore monthly, then yearly, then custom, and it is a
        //       property of the reference rather than a preference of this method.
        // WHY : Refactoring Rationale: this method COUNTED the marks and refused any request that
        //       carried more than one, on the stated ground that accepting it would silently discard
        //       a selection the caller made. That reasoning described the target's own contract
        //       rather than the reference's behaviour, and the reference is the specification: it
        //       accepts the request and runs the first marked type, so the stricter rule refused a
        //       request the baseline answers and answered 400 where the baseline produces a report.
        //       Functional parity is a non-negotiable constraint of this migration and no exception
        //       covers this branch, so the count is gone and the chain is the reference's.
        //       Trade-offs: a caller that marks two types is told nothing about the one that was
        //       ignored, which is the cost of parity here and is the reference's own behaviour --
        //       the screen it answered showed the report that ran, and a caller reads which type it
        //       got from the reportName the response carries.
        if (isMarked(request.monthly())) {
            return MONTHLY_REPORT_NAME;
        }
        if (isMarked(request.yearly())) {
            return YEARLY_REPORT_NAME;
        }
        if (isMarked(request.custom())) {
            return CUSTOM_REPORT_NAME;
        }

        // WHY : Assumptions: the refusal is the shared client-input type rather than a plain
        //       argument exception, so GlobalExceptionHandler renders it under
        //       ApiError.CODE_VALIDATION as one entry in the structured per-field error array,
        //       carrying the correlation identity and the request path the problem shape needs.
        //       This class holds none of those three, which is why the package charter places
        //       the handler in the shared kernel and not here. This is the reference's own final
        //       arm, the WHEN OTHER at app/cbl/CORPT00C.cbl L437 to L440.
        // WHY : Refactoring Rationale: the sentence is now the reference literal VERBATIM, where an
        //       earlier revision reported "exactly one of monthly, yearly or custom must be selected
        //       but 0 were". That sentence was authored here, described the refusal that has just
        //       been withdrawn, and was not in the catalogue reporting-api.yaml publishes as the
        //       messages this operation may carry -- which lists this literal, at L1988, and states
        //       that every message is carried verbatim from the reference. Transformation rule T8
        //       requires user-visible strings to be reproduced character for character, and the
        //       three trailing full stops and the capitalisation are part of the text.
        throw new ClientInputException(ApiError.CODE_VALIDATION, REPORT_TYPE_FIELD,
                MESSAGE_NO_REPORT_TYPE_SELECTED);
    }

    /**
     * Resolves which of the reference's three confirmation answers a request carries.
     *
     * <p>Assumptions: the baseline distinguishes THREE answers plus one refusal and this method
     * distinguishes the same four outcomes. An absent answer re-prompts with its own sentence at L464
     * to L474 of {@code app/cbl/CORPT00C.cbl}; an affirmative answer continues to the write loop
     * through L478 and L479; a negative answer clears the screen and stops at L480 to L483; and an
     * unrecognised answer is refused at L484 to L493. The gate that guards the loop is the flag test
     * at L476, which is why a submission is reached at L496 only for the affirmative case.</p>
     *
     * <p>Refactoring Rationale: this returns a three-valued answer where an earlier revision returned
     * a boolean and RAISED for an absent one, and the change is a correction of the outcome rather
     * than a tidier signature. An absent answer is not a failure in the baseline: L464 to L474 sets
     * the flag purely to suppress the success block and re-displays the screen carrying a PROMPT, so
     * the caller is being asked a question, not told it made a mistake. Raising made that turn a
     * validation failure -- an HTTP 400 with a problem body -- where the published contract at
     * {@code src/main/resources/openapi/reporting-api.yaml} declares 200 with the prompt as the
     * message. A boolean cannot carry three states, so the type is what forced the wrong answer; the
     * enum below removes the choice.</p>
     *
     * <p>Refactoring Rationale: the unrecognised answer is now refused with the reference's own
     * interpolated sentence. The earlier revision emitted a constant naming the two accepted letters
     * and the answer's LENGTH, and justified withholding the offending value as keeping caller input
     * out of a string an alert rule matches on. That reasoning does not survive contact with two
     * facts: transformation rule T8 requires user-visible strings to cross verbatim and admits no
     * exception for alertability, and the value withheld is one character drawn from a domain the
     * request type bounds at a single position -- so nothing unbounded was ever being kept out.
     * Operational matching is served by the error CODE, which is what the alerting reads.</p>
     *
     * @param request the report request whose confirmation answer is wanted; must not be {@code null}
     * @return which of the three answers the request carries; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws ClientInputException if the answer is neither of the two the baseline recognises,
     *     carrying the reference's own quoted-value sentence
     */
    public Confirmation resolveConfirmation(ReportRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        String answer = request.confirm();
        if (FieldValidationFlag.isNeverSupplied(answer)) {
            // WHY : Assumptions: an absent answer is reported separately from an unrecognised one
            //       because the baseline answers them separately, at L464 and L484 respectively, with
            //       two different sentences and two different outcomes. Collapsing them would tell a
            //       caller who supplied nothing that it supplied something invalid.
            // WHY : Assumptions: the never-supplied predicate is used rather than a blankness test,
            //       because L464 compares the answer against BOTH the space and the low-value
            //       figurative constants and a low-value character is not whitespace.
            return Confirmation.UNANSWERED;
        }
        if (CONFIRM_YES.equalsIgnoreCase(answer)) {
            return Confirmation.CONFIRMED;
        }
        if (CONFIRM_NO.equalsIgnoreCase(answer)) {
            return Confirmation.DECLINED;
        }
        // WHY : Assumptions: this arm is the ONLY place an unrecognised answer is refused, and the
        //       request type deliberately no longer constrains the answer declaratively. Both a
        //       declarative pattern and this branch produce an HTTP 400, so the choice is about which
        //       one composes the message: a constraint violation is rendered by the shared advice as
        //       its generic aggregate sentence, which is not the reference's, whereas this refusal
        //       carries the reference's own. The published schema keeps its pattern as the statement
        //       of the accepted domain, which stays true -- a pattern says what is accepted, and this
        //       branch says, in the reference's words, why a value outside it was not.
        // WHY : Assumptions: the answer is interpolated as submitted, unquoted-value first and then
        //       wrapped, which is the operand order of the STRING statement at L485 to L490. The
        //       reference delimits the answer BY SPACE, which for the single declared position of
        //       CONFIRMI at L114 of app/cpy-bms/CORPT00.CPY yields the character itself -- an answer
        //       that WAS a space reached the absent branch above and never arrives here.
        throw new ClientInputException(ApiError.CODE_VALIDATION, CONFIRM_FIELD,
                INVALID_CONFIRM_PREFIX + answer + INVALID_CONFIRM_SUFFIX);
    }

    /**
     * Which of the reference's three confirmation answers a request carries.
     *
     * <p>Assumptions: the three constants below are the three arms of the baseline's own condition
     * chain and there is deliberately no fourth for an invalid answer. An invalid answer is refused
     * rather than described, because the baseline refuses it too -- L484 to L493 raises the error flag
     * and re-displays with a message -- so representing it as a value would let a caller carry an
     * unrunnable state past the point that rejects it.</p>
     */
    public enum Confirmation {

        /** The answer was {@code Y} or {@code y}: the run proceeds, per L478 and L479. */
        CONFIRMED,

        /** The answer was {@code N} or {@code n}: nothing runs and nothing is reported, per L480 to L483. */
        DECLINED,

        /** No answer was supplied: the caller is re-prompted, per L464 to L474. */
        UNANSWERED
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
            // WHY : Refactoring Rationale: the sentence is the reference's MONTH-component blank
            //       message, and an earlier revision emitted a target-authored one naming the request
            //       member. Two facts settle which reference string belongs here. The reference tests
            //       the six typed components in one first-match chain at L258 to L302 of
            //       app/cbl/CORPT00C.cbl, opening with the start month at L258, so a bound with
            //       nothing in it reports the MONTH message and never reaches the day or year arm.
            //       And the published contract at src/main/resources/openapi/reporting-api.yaml
            //       enumerates the complete catalog a field error of this operation may carry and
            //       says of it "these strings are the contract", so a sentence outside the catalog is
            //       off-contract regardless of how well it reads.
            // WHY : Trade-offs: naming the month specifically is less precise than the target could
            //       be, because this class receives the bound already ASSEMBLED and cannot tell which
            //       of the three components the caller left out. The imprecision is the reference's
            //       own and is preferred to inventing a seventh sentence for a seam the reference does
            //       not have; the assembly's relocation to the client edge is registered on
            //       resolveCustomRange, which explains why the six components never arrive here.
            // WHY : Assumptions: the state is BLANK and not the not-ok default the three-argument
            //       constructor supplies. Transformation rule T7 maps the reference's two validation
            //       flags onto these two states, and the contract records that BLANK additionally
            //       renders the literal asterisk marker the reference writes into an empty field --
            //       so a blank bound reported as not-ok would lose that marker.
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    FieldValidationFlag.BLANK, blankMonthMessage(field));
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
            // WHY : Refactoring Rationale: a wrong-width bound carries the ASSEMBLED-DATE message and
            //       not a target-authored width sentence. The reference cannot reach this condition at
            //       all -- its three components are fixed-width screen fields, so an assembled value
            //       is always ten characters -- and the published catalog contains no width message,
            //       so there is no in-contract sentence for the condition as such. The assembled-date
            //       message is the reference's answer for a value that cannot be a date under the
            //       'YYYY-MM-DD' mask at L72, which a wrong-width value cannot be, so it is the one
            //       catalog entry that is true of this input rather than merely available.
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    invalidDateMessage(field));
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

        // WHY : Refactoring Rationale: the sentence is the reference's own assembled-date message,
        //       where an earlier revision reported the edit's severity code and message number. Those
        //       two values are the LANGUAGE ENVIRONMENT's diagnostic, and the reference does not show
        //       them to an operator: it tests them at L396 and L399 for the start bound and L416 and
        //       L419 for the end bound, and on failure moves 'Start Date - Not a valid date...' at
        //       L400 or 'End Date - Not a valid date...' at L420. Reporting the codes instead
        //       disclosed an internal diagnostic to a caller AND withheld the string the published
        //       catalog names, so it failed twice over.
        // WHY : Assumptions: the diagnostic is not lost, it is relocated. The severity and message
        //       number remain available to the caller of the shared edit and are the datum an
        //       operator needs; what changes is that they no longer travel in a user-visible sentence
        //       transformation rule T8 requires to be the reference's own.
        throw new ClientInputException(ApiError.CODE_VALIDATION, field, invalidDateMessage(field));
    }

    /**
     * Selects the reference's blank-month sentence for whichever bound is empty.
     *
     * <p>Assumptions: the two sentences differ only in their leading words and are declared as whole
     * strings rather than assembled from a shared tail, because they are two separate MOVE literals in
     * the reference -- L261 and L282 of {@code app/cbl/CORPT00C.cbl} -- and a shared tail would invite
     * a later reader to "fix" one of them into agreement with a screen caption.</p>
     *
     * @param field the request member the bound arrived on, one of the two range bounds
     * @return the reference's sentence for an empty bound of that end; never {@code null}
     */
    private static String blankMonthMessage(String field) {
        return START_DATE_FIELD.equals(field)
                ? MESSAGE_START_DATE_MONTH_EMPTY
                : MESSAGE_END_DATE_MONTH_EMPTY;
    }

    /**
     * Selects the reference's assembled-date sentence for whichever bound failed the edit.
     *
     * @param field the request member the bound arrived on, one of the two range bounds
     * @return the reference's sentence for an unusable bound of that end; never {@code null}
     */
    private static String invalidDateMessage(String field) {
        return START_DATE_FIELD.equals(field)
                ? MESSAGE_START_DATE_INVALID
                : MESSAGE_END_DATE_INVALID;
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
     * <p>Assumptions: a submission that the orchestrator has already accepted under the same name is
     * answered with the handle of that run rather than as a failure, so a caller retrying one request
     * observes one execution. Which second request counts as the same submission is decided by
     * {@link #currentSubmissionKey()} and joined into the name by
     * {@link #executionName(String, String, String, String)}; the two documents together are the whole
     * of this method's deduplication behaviour.</p>
     *
     * @param request the confirmed report request; must not be {@code null}
     * @param reportName the resolved report name, as {@link #resolveReportName(ReportRequest)}
     *     returns it; must not be {@code null}
     * @param rangeStart the first business date of the resolved range; must not be {@code null}
     * @param rangeEnd the last business date of the resolved range; must not be {@code null}
     * @param idempotencyKey the caller's submission key, or {@code null} when the caller supplied none.
     *     Supplying one makes a re-sent request a duplicate the orchestrator refuses; omitting one makes
     *     every submission a distinct run. See {@code executionName} for why the choice is the
     *     caller's
     * @return a description of the accepted run, carrying the execution ARN a caller observes it
     *     through; never {@code null}
     * @throws NullPointerException if the request, the report name or either bound is {@code null}; the
     *     submission key is the one argument that may be absent
     * @throws ClientInputException if the request does not carry a confirming answer, so that nothing
     *     is started for a request that declined or never answered, or if a supplied submission key
     *     carries a character or a length an execution name may not hold
     * @throws IllegalStateException if the orchestrator refuses the start, which is the loud failure
     *     that replaces the discarded write and is raised from the guarded call below rather than
     *     declared by it; and, for the one refusal that is not a failure -- a name already in use --
     *     only when the configured machine ARN is not the plain shape an execution handle can be
     *     derived from, since every other such refusal is answered with the existing run
     * @throws IllegalArgumentException if the assembled description is one the response contract
     *     refuses, which has two reachable causes worth naming because neither is visible from this
     *     method's own statements: a {@code reportName} that is not one of the three the baseline
     *     assigns, reaching here only from a caller that bypassed
     *     {@link #resolveReportName(ReportRequest)}, and a start that returned no execution handle at
     *     all. The contract is asserted by the response type rather than restated here, so that one
     *     type stays the single authority on what a submission description may hold
     */
    public ReportSubmissionResponse start(
            ReportRequest request, String reportName, LocalDate rangeStart, LocalDate rangeEnd,
            String idempotencyKey) {

        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(reportName, "reportName must not be null");
        Objects.requireNonNull(rangeStart, "rangeStart must not be null");
        Objects.requireNonNull(rangeEnd, "rangeEnd must not be null");

        // WHY : Assumptions: the gate re-asserts the confirmation rather than trusting the caller's
        //       call order, and the sentence it carries is deliberately NOT one of the reference's.
        //       This condition is unreachable through the published operation, which resolves the
        //       answer and branches on it before it reaches here; it is reachable only from a caller
        //       inside the application that started a run for a request it had already been told was
        //       declined or unanswered. That is an internal invariant failure with no baseline
        //       counterpart, so there is no reference string to carry and inventing one would put a
        //       sentence on a screen the reference never shows it on.
        if (resolveConfirmation(request) != Confirmation.CONFIRMED) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, CONFIRM_FIELD,
                    "a report is started only for a confirmed request");
        }

        String suppliedKey = validatedIdempotencyKey(idempotencyKey);
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
        String executionInput = "{\"" + INPUT_REPORT_TYPE_MEMBER + "\":\""
                + reportName.toLowerCase(Locale.ROOT)
                + "\",\"" + INPUT_START_DATE_MEMBER + "\":\"" + startDate
                + "\",\"" + INPUT_END_DATE_MEMBER + "\":\"" + endDate + "\"}";

        // WHY : Assumptions: the key is resolved ONCE, before the call, and the same value is used
        //       for the name and for the log line that reports a duplicate, so that an operator
        //       reading the record can tell which submission was folded onto which run.
        // WHY : Refactoring Rationale: the key has TWO sources and the caller's wins. A caller that
        //       sends the Idempotency-Key header is stating whether a second submission of the same
        //       range is a retry of one attempt or a genuinely new run, and it is the only party that
        //       knows -- an execution name is reserved for the 90 days the orchestrator remembers a
        //       completed run, so a name derived from the range alone refuses every legitimate rerun.
        //       When the header is absent the key is derived from the request's own correlation
        //       identifier instead, which folds the retries of ONE abandoned call onto one run (the
        //       client's per-call ceiling can abandon a call the orchestrator went on to accept)
        //       without making a later, separately-correlated submission collide with it.
        // WHY : Alternatives Considered: a random distinguisher when the header is absent. Rejected
        //       because it makes every retry of an abandoned call start another run, which is the
        //       duplicate-submission defect this deduplication exists to prevent.
        String submissionKey = suppliedKey == null ? currentSubmissionKey() : suppliedKey;
        String executionName = executionName(reportName, startDate, endDate, submissionKey);

        StartExecutionResponse started;
        try {
            started = sfnClient.startExecution(StartExecutionRequest.builder()
                    .stateMachineArn(stateMachineArn)
                    .name(executionName)
                    .input(executionInput)
                    .build());
        } catch (ExecutionAlreadyExistsException alreadyStarted) {
            // WHY : Assumptions: this arm is ABOVE the SdkException arm below and must stay there.
            //       The already-exists type is a subclass of that supertype, so an arm placed after
            //       it would never be entered and every duplicate would be reported as an outage.
            // WHY : Refactoring Rationale: a duplicate is ANSWERED here, where an earlier revision
            //       had no arm for it and let the supertype arm raise a state exception -- reporting
            //       500 for the one condition the deterministic name exists to create. The whole
            //       point of deriving the name from a submission key is that a retry of the SAME
            //       submission collides; answering that collision with an outage would make the
            //       collision a defect rather than the deduplication it is.
            // WHY : Assumptions: the reference has no equivalent -- every submission it writes to the
            //       transient data queue is independent -- so folding a retry onto its own run is an
            //       intentional behavioural difference and is registered as
            //       D-REPORT-SUBMISSION-DEDUPLICATED in
            //       docs/architecture/cobol-to-service-traceability.md.
            // WHY : Trade-offs: the answer is the SAME 201 the first attempt received, carrying the
            //       handle of the run that already exists, and the acceptance stamp on it is read
            //       from the clock now rather than recovered from the first attempt. Recovering it
            //       would need a describe call whose only product is a timestamp, and the stamp
            //       documents when THIS request was accepted, which is what a caller correlating its
            //       own retry against its own log needs.
            String existing = executionArnOf(executionName);
            if (existing == null) {
                // WHY : Assumptions: the ARN could not be derived, which happens only for a
                //       configured machine ARN that is qualified by a version or an alias, and the
                //       failure is reported rather than guessed at. Answering a handle this method
                //       assembled wrongly would give a caller an identifier that resolves to
                //       nothing, which is worse than the loud failure.
                LOG.error("event=report.submission.duplicate.unresolved reportName={}"
                        + " startDate={} endDate={} failure={}",
                        reportName, startDate, endDate, ThrowableDigest.of(alreadyStarted));
                throw new IllegalStateException(
                        "a " + reportName + " report submission was already started but its handle"
                                + " could not be derived from the configured state machine",
                        alreadyStarted);
            }

            // WHY : Assumptions: this is an INFO record and not a warning, because nothing failed --
            //       a retry was recognised and folded onto the run it was retrying. The submission
            //       key is recorded because it is the only field that ties the two requests together,
            //       and it is a digest of the correlation identity rather than the identity itself,
            //       so the record carries no caller-supplied text.
            LOG.info("event=report.submission.deduplicated reportName={} startDate={} endDate={}"
                    + " submission={} execution={}",
                    reportName, startDate, endDate, submissionKey, executionName);

            return new ReportSubmissionResponse(
                    existing,
                    reportName,
                    ReportBandLayouts.REPORT_SHORT_NAME,
                    ReportBandLayouts.REPORT_LONG_NAME,
                    startDate,
                    endDate,
                    TimestampFormatter.formatNow(clock));
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
     * <p>Assumptions: the separator is a hyphen because an execution name admits a restricted
     * character set, and the colon that a timestamp form would join values with is not in it. The 3
     * parts joined are the report type and the 2 bounds, each bound already 10 characters.</p>
     *
     * <p>Assumptions: a name that no caller supplied a key for carries a fresh random component, so 2
     * submissions of the same report over the same range are 2 distinct executions. A name a caller DID
     * supply a key for is a pure function of the report type, the 2 bounds and that key, so re-sending
     * one request with one key is refused by the orchestrator as the duplicate it is. Which of the 2
     * behaviours applies is therefore the caller's decision and not this method's, which is the point:
     * only the caller knows whether a second submission is a retry of the first or a genuinely new run
     * of the same report.</p>
     *
     * <p>⚠️ Refactoring Rationale: this method returned the report type and the 2 bounds alone, with no
     * 4th part, and its rationale recorded that as a deliberate rejection of a random component. The
     * argument it gave was sound as far as it went -- the 10-second per-call ceiling
     * {@code com.carddemo.reporting.config.StepFunctionsConfig} sets on the client can abandon a call
     * the orchestrator went on to accept, and a caller retrying after that would otherwise start the
     * same report twice, producing 2 sets of output objects with nothing to say which was current --
     * but it bought that protection with a cost it did not weigh. A deterministic name is unique to the
     * report and the range for as long as the orchestrator remembers it, and it remembers a completed
     * execution's name for 90 days. So the FIRST submission of a month's report succeeded and every
     * later one inside that window was refused, whatever its reason: a rerun after the underlying rows
     * were corrected, a rerun after an operator deleted the output objects, a rerun of yesterday's
     * report today. Those are not duplicate submissions and refusing them is not idempotency, it is a
     * 90-day lockout of a legitimate operation -- and the refusal surfaced as an orchestrator-raised
     * {@code ExecutionAlreadyExists} that this class did not translate, so the caller received a 500
     * rather than anything it could act on. The 4th part resolves both halves at once by moving the
     * choice to the caller: retry protection is still available, but it is now REQUESTED with a key
     * rather than imposed on every submission, so asking for it twice is a duplicate and asking for a
     * new run is not.</p>
     *
     * <p>Alternatives Considered: keeping the name deterministic and translating the orchestrator's
     * refusal into a 409 instead. Rejected because it names the condition without making the operation
     * available: a caller told that this report already ran 3 weeks ago still has no way to run it
     * again, so the 409 would be an accurate description of a capability the service does not offer.
     * Also considered was appending a timestamp rather than a random token, which is shorter and sorts
     * usefully; rejected because 2 submissions inside the same clock tick would collide, which
     * reintroduces the refusal this change exists to remove, and because a name is not an ordering
     * device -- the orchestrator records its own start time and this method does not need to restate
     * it.</p>
     *
     * <p>Trade-offs: a caller that retries WITHOUT a key after an abandoned call starts a second run,
     * which is the exact failure the previous shape prevented. That is the price of making a rerun
     * possible at all, and it is mitigated rather than ignored: the key is published on the operation,
     * the header's contract description states plainly what omitting it means, and the whole point of
     * the key being optional is that a client which retries automatically can send one and get the old
     * behaviour back for the requests it retries.</p>
     *
     * @param reportName the resolved report name
     * @param startDate the first business date of the range, in its ten-character form
     * @param endDate the last business date of the range, in its ten-character form
     * @param idempotencyKey the caller's submission key, already validated by
     *     {@link #validatedIdempotencyKey(String)}, or {@code null} when the caller supplied none
     * @return the execution name; never {@code null}
     */
    private static String executionName(
            String reportName, String startDate, String endDate, String idempotencyKey) {

        // WHY : Refactoring Rationale: the key is REQUIRED here rather than defaulted, because the
        //       caller resolves it -- the Idempotency-Key header when one arrives, the request's
        //       correlation digest otherwise -- and a second default in this method would make the
        //       name depend on which of two places had filled it in.
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        return reportName.toLowerCase(Locale.ROOT) + "-" + startDate + "-" + endDate
                + "-" + idempotencyKey;
    }

    /**
     * Derives the ARN of an execution of the configured state machine from its name.
     *
     * <p>Assumptions: an execution ARN is its machine's ARN with the machine segment replaced by the
     * execution segment and the execution name appended, which is the published shape of the
     * identifier. Deriving it is what lets a recognised duplicate answer with the handle of the run it
     * was folded onto without a further call.</p>
     *
     * <p>Alternatives Considered: listing the machine's executions and matching on the name. Rejected
     * because it pages over every execution the retention window holds to recover a value that is a
     * function of two strings this method already has, and it needs a list permission the task role
     * does not hold -- widening a role to recover derivable information is the wrong trade.</p>
     *
     * <p>Assumptions: a configured ARN that is qualified further -- by a version or an alias, both of
     * which append another colon-separated part -- yields {@code null} rather than a guess, because
     * the execution of a qualified machine is not named by simple substitution and a wrong handle is
     * worse for a caller than a reported failure.</p>
     *
     * @param executionName the execution name, as {@link #executionName(String, String, String,
     *     String)} assembled it
     * @return the execution ARN, or {@code null} when the configured machine ARN is not the plain
     *     unqualified shape this substitution is defined for
     */
    private String executionArnOf(String executionName) {
        int marker = stateMachineArn.lastIndexOf(STATE_MACHINE_ARN_SEGMENT);
        if (marker < 0) {
            return null;
        }

        String machineName = stateMachineArn.substring(marker + STATE_MACHINE_ARN_SEGMENT.length());
        if (machineName.isEmpty() || machineName.indexOf(':') >= 0) {
            return null;
        }

        return stateMachineArn.substring(0, marker) + EXECUTION_ARN_SEGMENT + machineName
                + ":" + executionName;
    }

    /**
     * Reports what became of one accepted submission, and where its artifact is when there is one.
     *
     * <p>⚠️ Refactoring Rationale: this is the operation the submission handle existed for and did not have.
     * A review found that {@code executionArn} was returned to a caller and consumed by nothing: there was
     * no way to learn whether a run was still going, had succeeded or had failed, and no way to reach the
     * document it produced. The reference has the same gap for the same reason -- it writes card images to a
     * transient data queue and receives no identity back at all -- so this is a documented improvement
     * rather than a port, registered as D-REPORT-LIFECYCLE-OBSERVABLE.
     *
     * <p>Assumptions: the caller names the execution by its NAME and never by an ARN, and the ARN is
     * composed here from the configured state machine. That is a security property rather than a
     * convenience: a caller cannot describe an execution of another state machine, of another account or of
     * another environment, because no part of what it sends reaches the ARN except the final name segment.
     * Alternatives Considered: accepting the whole ARN and validating its prefix, which is the same
     * guarantee expressed as a check that has to be got right rather than as a composition that cannot be
     * got wrong.
     *
     * <p>Measured: taking the caller's value as the handle instead of composing one -- the shape the
     * rejected alternative starts from -- fails exactly one case of {@code ReportExecutionServiceTest},
     * {@code theDescribedHandleIsComposedFromTheConfiguredMachine}, with {@code expected:
     * "arn:aws:states:us-east-1:000000000000:execution:carddemo-transaction-report-dev:some-run-name" but
     * was: "some-run-name"}. Every other case of that class and all 27 of
     * {@code ReportControllerTest} still passed, so the composition is asserted on its own and not as a
     * side effect of some other property.
     *
     * <p>Assumptions: the three coordinates are recovered from the execution's own INPUT rather than being
     * asked of the caller again. The input is the three-name document this class composes at submission, so
     * reading it back is symmetric with writing it; a caller re-supplying them could describe a run under
     * coordinates it did not have, and the artifact location would then name a document belonging to a
     * different range.
     *
     * <p>Assumptions: the artifact location is published ONLY where the store holds the object, on the same
     * terms as a statement location, so a location this method returns always resolves. A run that has
     * succeeded but whose artifact has been expired by a lifecycle rule reports the status and no location,
     * which is the truth rather than a link to nothing.
     *
     * @param executionName the execution name a submission returned; must not be {@code null}
     * @return what the orchestrator reports about the run, together with the artifact's location and write
     *     instant when the store holds it; never {@code null}
     * @throws NullPointerException if {@code executionName} is {@code null}
     * @throws NoSuchElementException if this deployment's state machine has no execution of that name, which
     *     is also the answer for a name that was never issued
     * @throws IllegalStateException if the configured state machine ARN is qualified by a version or an
     *     alias, so an execution ARN cannot be composed from it
     */
    public ExecutionState describeExecution(String executionName) {
        Objects.requireNonNull(executionName, "executionName must not be null");
        String executionArn = executionArnOf(executionName);
        if (executionArn == null) {
            throw new IllegalStateException(
                    "the configured state machine is qualified by a version or an alias, so an"
                            + " execution handle cannot be composed from it");
        }

        DescribeExecutionResponse described;
        try {
            described = sfnClient.describeExecution(DescribeExecutionRequest.builder()
                    .executionArn(executionArn)
                    .build());
        } catch (ExecutionDoesNotExistException absent) {
            // WHY : Assumptions: an unknown execution is reported as an absent RECORD and not as an
            //       orchestration failure, because that is what it is from the caller's side -- and because
            //       a name the orchestrator has forgotten (it retains a completed run for ninety days) is
            //       indistinguishable from a name that was never issued. Both are answered alike so that
            //       neither tells a caller which names exist.
            LOG.debug("event=report.execution.absent execution={} outcome={}", executionName,
                    ThrowableDigest.of(absent));
            throw new NoSuchElementException("no report execution of that name is known");
        } catch (SdkException refused) {
            // WHY : Assumptions: the guard is the software development kit's own supertype and nothing
            //       wider, matching the submission path in this class, so an invariant failure raised by
            //       this method's own assembly is not reported as an orchestration outage.
            LOG.error("event=report.execution.describe.refused execution={} failure={}", executionName,
                    ThrowableDigest.of(refused));
            throw new IllegalStateException(
                    "the report orchestration could not be asked about this run", refused);
        }

        ExecutionCoordinates coordinates = coordinatesOf(described.input());
        return new ExecutionState(
                executionName,
                ExecutionStatus.of(described.statusAsString()),
                TimestampFormatter.format(LocalDateTime.ofInstant(
                        described.startDate(), ZoneOffset.UTC)),
                described.stopDate() == null ? null : TimestampFormatter.format(
                        LocalDateTime.ofInstant(described.stopDate(), ZoneOffset.UTC)),
                coordinates);
    }

    /**
     * Recovers the three coordinates from an execution input document.
     *
     * <p>Assumptions: the three values are read by NAME with an explicit reader rather than through a
     * mapper, which is the mirror image of the decision recorded where the input is composed: the shape is a
     * contract between this class and the state machine, and routing it through a mapper would put it under
     * a module-wide serialisation configuration this class does not own. Reading it back the same way keeps
     * the two halves of one contract in one file.
     *
     * <p>Assumptions: a missing or malformed input yields NO coordinates rather than a failure. An input
     * this class did not compose can only come from an execution started outside this surface -- the nightly
     * schedule starts the same machine -- and the honest answer for such a run is its status without an
     * artifact location, not a refusal to report the status at all.
     *
     * <p>Measured: the reading half and the writing half were mutated separately and fail different
     * cases, which is why both are asserted. Renaming the member this side reads fails
     * {@code theCoordinatesAreRecoveredFromTheInput} and {@code theWrittenInputIsTheReadInput}, both with
     * {@code NullPointerException} on a {@code null} coordinates. Renaming the member the WRITING half
     * emits instead leaves {@code theCoordinatesAreRecoveredFromTheInput} passing -- it stubs a
     * hand-written document, so it cannot see writer drift at all -- and fails
     * {@code aConfirmedStartPassesTheResolvedRange} with {@code expected:
     * "{"reportType":"monthly",...}" but was: "{"report_type":"monthly",...}"} together with
     * {@code theWrittenInputIsTheReadInput}. The round trip is therefore the only case that holds the two
     * halves to each other.</p>
     *
     * @param input the execution input as the orchestrator recorded it, which may be {@code null}
     * @return the coordinates, or {@code null} when the input is not the document this class composes
     */
    private static ExecutionCoordinates coordinatesOf(String input) {
        if (input == null) {
            return null;
        }
        String reportType = quotedValue(input, INPUT_REPORT_TYPE_MEMBER);
        String startDate = quotedValue(input, INPUT_START_DATE_MEMBER);
        String endDate = quotedValue(input, INPUT_END_DATE_MEMBER);
        if (reportType == null || startDate == null || endDate == null) {
            return null;
        }
        try {
            return new ExecutionCoordinates(reportType,
                    LocalDate.parse(startDate), LocalDate.parse(endDate));
        } catch (DateTimeParseException malformed) {
            // WHY : Assumptions: a bound that is not a calendar date is treated as no coordinates rather
            //       than propagated, for the reason above -- and parsing it is what stops an arbitrary
            //       string from reaching an object key through the artifact location.
            LOG.warn("event=report.execution.input.unreadable failure={}",
                    ThrowableDigest.of(malformed));
            return null;
        }
    }

    /**
     * Reads one quoted string member out of the flat execution-input document.
     *
     * <p>Assumptions: the reader is deliberately narrow. It matches the exact {@code "name":"value"} shape
     * this class writes, with no whitespace tolerance and no nesting, so it cannot half-understand a
     * document of another shape -- it either finds the member as written or reports nothing.</p>
     *
     * @param input the input document
     * @param name the member name to read
     * @return the member's value, or {@code null} when the member is not present in that exact shape
     */
    private static String quotedValue(String input, String name) {
        String marker = "\"" + name + "\":\"";
        int start = input.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int from = start + marker.length();
        int end = input.indexOf('"', from);
        return end < 0 ? null : input.substring(from, end);
    }

    /**
     * The coordinates identifying which report an execution produced.
     *
     * @param reportType the report type token the run was started for
     * @param rangeStart the inclusive lower bound of the reported range
     * @param rangeEnd the inclusive upper bound
     */
    public record ExecutionCoordinates(String reportType, LocalDate rangeStart, LocalDate rangeEnd) {

        /**
         * Validates that every coordinate is present.
         *
         * @param reportType the report type token; must not be {@code null}
         * @param rangeStart the inclusive lower bound; must not be {@code null}
         * @param rangeEnd the inclusive upper bound; must not be {@code null}
         * @throws NullPointerException if any coordinate is {@code null}
         */
        public ExecutionCoordinates {
            Objects.requireNonNull(reportType, "reportType must not be null");
            Objects.requireNonNull(rangeStart, "rangeStart must not be null");
            Objects.requireNonNull(rangeEnd, "rangeEnd must not be null");
        }
    }

    /**
     * What the orchestrator reports about one accepted submission.
     *
     * @param executionName the execution name the submission returned
     * @param status which of the orchestrator's terminal or running states the run is in
     * @param startedAt when the run started, in the twenty-six-character form
     * @param stoppedAt when the run stopped, or {@code null} while it is still running
     * @param coordinates which report the run produces, or {@code null} when the run was started outside
     *     this surface and its input is not the document this service composes
     */
    public record ExecutionState(
            String executionName,
            ExecutionStatus status,
            String startedAt,
            String stoppedAt,
            ExecutionCoordinates coordinates) {

        /**
         * Validates the components that are never absent.
         *
         * @param executionName the execution name; must not be {@code null}
         * @param status the run's state; must not be {@code null}
         * @param startedAt when the run started; must not be {@code null}
         * @param stoppedAt when it stopped, which is absent while it runs
         * @param coordinates which report it produces, which is absent for a run started elsewhere
         * @throws NullPointerException if the name, the status or the start instant is {@code null}
         */
        public ExecutionState {
            Objects.requireNonNull(executionName, "executionName must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(startedAt, "startedAt must not be null");
        }

        /**
         * Reports whether the run finished successfully, which is the only state with an artifact.
         *
         * @return {@code true} when the orchestrator reports success
         */
        public boolean succeeded() {
            return status == ExecutionStatus.SUCCEEDED;
        }
    }

    /**
     * The states the orchestrator reports a run in.
     *
     * <p>Assumptions: the six constants are the orchestrator's own execution statuses, carried across by
     * name rather than collapsed into a smaller set. Collapsing them would lose exactly the distinctions an
     * operator acts on: a run that timed out is retried, a run that was aborted was stopped deliberately,
     * and a run awaiting redrive is one an operator has already been told about.</p>
     */
    public enum ExecutionStatus {

        /** The run is still going. */
        RUNNING,

        /** The run finished and produced its artifact. */
        SUCCEEDED,

        /** The run failed; no artifact was published for it. */
        FAILED,

        /** The run exceeded its timeout. */
        TIMED_OUT,

        /** The run was stopped deliberately. */
        ABORTED,

        /** The run failed and is awaiting a redrive. */
        PENDING_REDRIVE;

        /**
         * Maps the orchestrator's own status token onto this domain.
         *
         * <p>Assumptions: an unrecognised token is refused rather than mapped to a nearby state. A status
         * this service does not know is a status it cannot report accurately, and answering {@code FAILED}
         * for a state the orchestrator added later would tell an operator a run had failed when it had
         * not.</p>
         *
         * @param token the status as the orchestrator names it; must not be {@code null}
         * @return the matching constant, never {@code null}
         * @throws NullPointerException if {@code token} is {@code null}
         * @throws IllegalStateException if the token is not one of the six
         */
        public static ExecutionStatus of(String token) {
            Objects.requireNonNull(token, "token must not be null");
            for (ExecutionStatus candidate : values()) {
                if (candidate.name().equals(token)) {
                    return candidate;
                }
            }
            throw new IllegalStateException(
                    "the orchestration reported an execution status this service does not publish");
        }
    }

    /**
     * Derives the key identifying THIS submission, so that a retry of it is recognisable.
     *
     * <p>Assumptions: the correlation identifier is the per-submission key, and it is read from the
     * mapped diagnostic context that {@link CorrelationIdFilter} populates for every request. That
     * filter accepts a caller-supplied identifier and generates one only when none arrives, so a
     * client retrying with the identifier it used the first time is recognised as retrying, and a
     * client submitting afresh gets a new identifier and a new run. Idempotency is therefore something
     * a caller asks for by resending the header it already owns, rather than something inferred from
     * the request's content -- which is the only reading that can tell a retry from a reprint, since a
     * reprint and a retry carry byte-identical bodies.</p>
     *
     * <p>Assumptions: a random key is used when the context carries none, which happens for a caller
     * that is not an HTTP request -- a scheduled invocation or a test. Such a caller has no stable key
     * to retry under, so the alternative to a random one is a constant one, and a constant would make
     * every non-HTTP submission a duplicate of the first for 90 days.</p>
     *
     * <p>Assumptions: the identifier is DIGESTED rather than used verbatim, for two reasons that are
     * both about not depending on another class's rules. Its width is bounded by a constant that class
     * owns, and the punctuation it admits includes the full stop, which is admissible in an execution
     * name today; a digest is a fixed sixteen characters drawn from the base64url alphabet, so the
     * assembled name is provably within the orchestrator's ceiling and provably within its character
     * set whatever the identifier holds. It also keeps caller-supplied text out of a name that appears
     * in operational records.</p>
     *
     * @return the per-submission key, sixteen base64url characters; never {@code null}
     * @throws IllegalStateException if the platform does not provide
     *     {@value #SUBMISSION_DIGEST_ALGORITHM}, which the platform specification forbids
     */
    private static String currentSubmissionKey() {
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        String source = correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;

        byte[] digest;
        try {
            digest = MessageDigest.getInstance(SUBMISSION_DIGEST_ALGORITHM)
                    .digest(source.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException unavailable) {
            // WHY : Assumptions: unreachable on any conforming platform, which is required to provide
            //       this algorithm, so the checked exception is converted rather than declared. It is
            //       converted and not swallowed because a platform that truly lacked it could not
            //       derive a submission key at all, and silently substituting a random one would turn
            //       every retry into a second run without saying so.
            throw new IllegalStateException(
                    "the platform does not provide " + SUBMISSION_DIGEST_ALGORITHM, unavailable);
        }

        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Arrays.copyOf(digest, SUBMISSION_DIGEST_BYTES));
    }

    /**
     * Checks a caller-supplied submission key against what an execution name is allowed to hold.
     *
     * <p>Assumptions: the key is validated HERE rather than by an annotation on the handler, because
     * what makes a key acceptable is not a general opinion about identifiers but the orchestrator's own
     * restriction on the names it accepts -- and this class is the one that composes those names. A
     * constraint declared on the transport would have to be kept in agreement with a rule stated here,
     * which is 2 statements of 1 fact.</p>
     *
     * <p>Assumptions: the admitted characters are letters, digits, the hyphen and the underscore. The
     * orchestrator refuses a name containing whitespace, a control character or any of a list of
     * punctuation marks, so admitting only this set is comfortably inside what it accepts and needs no
     * revision if that list changes. The length ceiling is {@value #IDEMPOTENCY_KEY_MAX_LENGTH}, which
     * leaves the composed name inside the orchestrator's {@value #EXECUTION_NAME_LIMIT}-character limit
     * for every report type and range: the longest report type is 7 characters and the 2 bounds with
     * their 3 separators are 23 more, so the widest name this admits is 60.</p>
     *
     * <p>Trade-offs: a key that is too long or carries an unadmitted character is REFUSED rather than
     * trimmed or rewritten into an acceptable one. Sanitising it would be friendlier at the moment of
     * the call and wrong afterwards, because 2 different keys can sanitise to the same string -- and 2
     * submissions a caller believes are distinct would then be 1 execution, with the second silently
     * refused as a duplicate of the first. A refusal the caller can read and correct is the safer of
     * the 2 failures.</p>
     *
     * @param supplied the key as the caller sent it, which may be {@code null} or blank
     * @return the key to distinguish this submission by, or {@code null} when the caller supplied none
     *     and the submission is to be given a fresh identity
     * @throws ClientInputException if a key was supplied but is longer than the ceiling or carries a
     *     character an execution name may not hold, named against the header the caller sent it in
     */
    private static String validatedIdempotencyKey(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return null;
        }
        if (supplied.length() > IDEMPOTENCY_KEY_MAX_LENGTH
                || !IDEMPOTENCY_KEY_PATTERN.matcher(supplied).matches()) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, IDEMPOTENCY_KEY_FIELD,
                    "a submission key may hold at most " + IDEMPOTENCY_KEY_MAX_LENGTH
                            + " letters, digits, hyphens or underscores");
        }
        return supplied;
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
