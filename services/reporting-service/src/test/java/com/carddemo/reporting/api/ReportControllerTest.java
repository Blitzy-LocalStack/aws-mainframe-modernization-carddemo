package com.carddemo.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reporting.config.SecurityConfig;
import com.carddemo.reporting.dto.ReportRequest;
import com.carddemo.reporting.dto.ReportSubmissionOutcome;
import com.carddemo.reporting.dto.ReportSubmissionResponse;
import com.carddemo.reporting.dto.ReportTotalsResponse;
import com.carddemo.reporting.dto.TransactionReportLineResponse;
import com.carddemo.reporting.mapper.ReportBandLayouts;
import com.carddemo.reporting.repository.TransactionReportRepository;
import com.carddemo.reporting.service.ArtifactStore;
import com.carddemo.reporting.service.ReportArtifactLocator;
import com.carddemo.reporting.service.ReportExecutionService;
import com.carddemo.reporting.service.TransactionReportService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
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
 *
 * <h2>What this class does not answer for</h2>
 *
 * <p>Trade-offs: the month-end ARITHMETIC is asserted by the sibling
 * {@code com.carddemo.reporting.service.ReportExecutionServiceTest} and not here, and the accepted
 * compromise is that an arithmetic regression in the resolved range is caught there rather than in
 * this class. {@link ReportController} declares no {@link java.time.Clock}: the clock lives in
 * {@link ReportExecutionService}, which is substituted here, and a substitute returns what it was
 * stubbed with. The four calendar cases below are therefore asserted at the CONTRACT level -- the
 * resolved pair is echoed unchanged -- while the sibling covers the derivation across a 31-day
 * month, a 30-day month and February in both a common and a leap year.
 *
 * <p>Assumptions: {@code CORPT00C} is an online {@code CO*} program, and {@code tests/README.md}
 * L83 to L85 records that such programs cannot be run end to end without a CICS runtime, which the
 * runner does not have. There is consequently no golden master to compare a rendered body against,
 * so the parity of this surface rests entirely on transcribed logic and on the record contracts the
 * copybooks declare. That is why the verbatim-sentence and declared-width assertions below carry
 * the whole weight and why each of them names its source line.
 *
 * <h2>The labelled decision register</h2>
 *
 * <p>Twelve decisions govern what this class asserts. Each is recorded once here under the label the
 * user-specified explainability rule names, and each cites measured evidence rather than describing
 * it.
 *
 * <p>RC-01 Assumptions: the nineteen sentences {@code app/cbl/CORPT00C.cbl} declares are asserted
 * character for character -- the six empty-component sentences at L261, L268, L275, L282, L289 and
 * L296, the six component-range sentences at L331, L340, L348, L357, L366 and L374, the two
 * assembled-date sentences at L400 and L420, and the five flow sentences at L438, L450, L466, L488
 * and L531. A twentieth user-visible string is kept separately attributed because it is declared
 * elsewhere: {@code CCDA-MSG-INVALID-KEY} at {@code app/cpy/CSMSG01Y.cpy} L20 to L21, reached from
 * the unsupported-action arm at {@code app/cbl/CORPT00C.cbl} L190 to L194 whose MOVE sits at L193.
 * Its literal measures 49 characters against the declared {@code PIC X(50)}, a one-space delta this
 * class asserts as two contracts rather than as one padded constant.
 *
 * <p>RC-02 Assumptions: three message widths reach this surface and all three are held apart rather
 * than reconciled. Eighty is the program's own working buffer, {@code 05 WS-MESSAGE PIC X(80)} at
 * {@code app/cbl/CORPT00C.cbl} L39; seventy-eight is the screen field, {@code ERRMSGI PIC X(78)} at
 * {@code app/cpy-bms/CORPT00.CPY} L120 and {@code ERRMSGO PIC X(78)} at its L224; seventy-five is
 * the pair of shared carriers, {@code CCARD-ERROR-MSG PIC X(75)} at {@code app/cpy/CVCRD01Y.cpy}
 * L28 and {@code CCARD-RETURN-MSG PIC X(75)} at its L29. The shared kernel publishes a wider set of
 * four that spans every context it serves; that is a different scope and not a disagreement, so
 * neither set is narrowed to match the other.
 *
 * <p>RC-03 Refactoring Rationale: a deliberate cancellation is not an error, so it answers 200 and
 * only an unrecognised answer answers 400. The four arms are {@code app/cbl/CORPT00C.cbl} L464 for
 * the unanswered turn, L478 for the two accepting answers, L480 for the two declining answers and
 * L484 for everything else; the declining arm performs {@code INITIALIZE-ALL-FIELDS} at L633 to
 * L646, which the success path also performs at L447. Treating a cancellation as a client error was
 * the alternative and it is rejected: it would report a deliberate user choice as a request to
 * correct, which the reference never does.
 *
 * <p>RC-04 Trade-offs: the three screen components of each bound are consolidated into one
 * ten-character value per bound, where {@code app/cbl/CORPT00C.cbl} L60 to L71 assembles each from
 * three fields joined by the hyphen {@code FILLER}s at L62 and L64 for the start bound and L68 and
 * L70 for the end bound. What is given up is the ability to send a component on its own; what is
 * kept is per-component reporting, because the six {@code MOVE -1 TO} cursor statements at L403 and
 * L423 and in the two validation passes prove the reference distinguishes month from day from year,
 * and the field-error array carries the same six identities.
 *
 * <p>RC-05 Assumptions: an identifier is a digits-only string on the wire and never an integer,
 * because {@code app/cpy/CVCRD01Y.cpy} declares each of the three as a character field with a
 * numeric {@code REDEFINES} beside it -- {@code CC-ACCT-ID PIC X(11)} at L34 over
 * {@code CC-ACCT-ID-N PIC 9(11)} at L36, {@code CC-CARD-NUM PIC X(16)} at L37 over L39, and
 * {@code CC-CUST-ID PIC X(09)} at L40 over L42. Characters travel and digits are only arithmetic.
 *
 * <p>RC-06 Assumptions: the two padding states are distinguished and never collapsed. Only the
 * return carrier bears a sentinel, {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES} at
 * {@code app/cpy/CVCRD01Y.cpy} L30, while the error carrier at L28 bears none. Both states occur in
 * practice: {@code app/bms/CORPT00.bms} gives each selector {@code INITIAL=' '} at L85, L99 and
 * L113 while {@code CONFIRM} at L206 carries no {@code INITIAL} clause at all, which is exactly why
 * the reference tests the selectors with {@code NOT = SPACES AND LOW-VALUES} at L213, L239 and L256
 * and the confirmation with {@code = SPACES OR LOW-VALUES} at L464.
 *
 * <p>RC-07 Alternatives Considered: the calendar rules are delegated to
 * {@link com.carddemo.common.validation.DateEditValidator} and are not re-asserted here. The
 * reference's linkage is {@code CSUTLDTC-PARM} at {@code app/cbl/CORPT00C.cbl} L129 to L136, whose
 * result group is eighty bytes made of a four-character severity at L133, eleven characters of
 * {@code FILLER} at L134, a four-character message number at L135 and a sixty-one character message
 * at L136; the padding is dropped and the severity, number and message triple is what travels. Both
 * call sites accept a zero severity and additionally tolerate a non-zero severity whose message
 * number is 2513, at L396 and L399 for the start bound and L416 and L419 for the end bound.
 * Re-asserting leap-year and month-length rules in this class was the alternative and is rejected:
 * it would restate a contract another package owns and let the two drift apart.
 *
 * <p>RC-08 Trade-offs: the four calendar cases are asserted as contract cases, for the reason given
 * under the heading above. The accepted compromise is stated there rather than repeated here.
 *
 * <p>RC-09 Refactoring Rationale: the monthly preset covers a whole calendar month and not the part
 * of it elapsed so far. {@code app/cbl/CORPT00C.cbl} L213 to L238 moves the current year and month
 * with a literal day of one, then sets the day to one, advances the month, rolls the year past
 * twelve and computes {@code DATE-OF-INTEGER(INTEGER-OF-DATE(...) - 1)} -- the first of the next
 * month minus one day. {@code app/bms/CORPT00.bms} corroborates it independently with
 * {@code INITIAL='Monthly (Current Month)'} at L93 beside {@code INITIAL='Yearly (Current Year)'} at
 * L107. There is no asymmetry with the yearly preset at L239 to L253, and both presets normally
 * resolve an end bound later than today, which this class asserts is carried through rather than
 * refused.
 *
 * <p>RC-10 Refactoring Rationale: a submission the orchestration refuses surfaces as a failure and
 * never as a silent success. The reference has a sentence ready for the condition,
 * {@code 'Unable to Write TDQ (JOBS)...'} at {@code app/cbl/CORPT00C.cbl} L531, but the queue it
 * writes to is defined with {@code ERROROPTION(IGNORE)} at {@code app/csd/CARDDEMO.CSD} L501 within
 * the stanza at L499 to L503 that also declares {@code RECORDSIZE(80)} at L502 -- so the platform
 * could swallow the write and leave that sentence unreached. The target raises instead, and the
 * divergence is documented.
 *
 * <p>RC-11 Alternatives Considered: the token decoder is substituted in the group that exercises the
 * deployed chain. {@link com.carddemo.reporting.config.SecurityConfig} declares no decoder bean and
 * relies on the framework building one from the configured issuer discovery property, and
 * {@code src/test/resources/application-test.yml} deliberately declares no issuer for that reason.
 * Pointing the group at a real or stubbed issuer was the alternative and is rejected: it would make
 * the assertions depend on network reachability and on a credential this repository must never hold.
 *
 * <p>RC-12 Refactoring Rationale: field-error rendering is driven purely by the response body. The
 * reference gates its highlight on the re-entry discriminator {@code CDEMO-PGM-CONTEXT}, which
 * {@code app/cbl/CORPT00C.cbl} clears at L547 -- its only occurrence in the program, inside
 * {@code RETURN-TO-PREV-SCREEN} at L540 -- before transferring control, so a first submission
 * would not have been highlighted at all. A stateless handler keeps no turn count, so a first
 * submission now returns the whole field-error array. The divergence is intended and documented, and
 * no request, response, stub or helper in this class carries a turn discriminator of any kind.
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

    /**
     * The report key prefix, matching the base configuration document.
     *
     * <p>Assumptions: a literal here rather than a read of the property, because the case asserting a
     * result location has to know the prefix in order to assert the whole location -- and a location
     * assembled from the same source it is compared against asserts nothing.</p>
     */
    private static final String REPORT_PREFIX = "reports/transaction-detail/";

    /**
     * An execution name of the shape this service composes.
     *
     * <p>⚠️ Refactoring Rationale: this constant now serves the SUBMISSION stubs as well as the
     * status stubs, where those two stubbed an execution ARN literal. The submission response
     * publishes the execution name -- the value the status path is addressed by -- so one constant
     * across both is what shows the two operations agreeing on a spelling; two literals of different
     * kinds is the disagreement a review found in the shipped contract.
     */
    private static final String EXECUTION_NAME = "carddemo-monthly-20220701-20220731-a1b2c3d4";

    /** When a described run started. */
    private static final String STARTED_AT = "2026-08-05 09:14:27.481903";

    /** When a described run stopped. */
    private static final String STOPPED_AT = "2026-08-05 09:19:02.117400";

    /** When a stored artifact was written. */
    private static final String WRITTEN_AT = "2026-08-05 09:19:03.882001";

    /**
     * When an accepted submission was stamped, at the shared formatter's declared width.
     *
     * <p>Assumptions: this is a value of exactly {@link TimestampFormatter#TIMESTAMP_LENGTH}
     * characters carrying the space separator and the six fractional digits the shared formatter
     * emits, because {@link ReportSubmissionResponse} refuses any other width. It is declared apart
     * from the run-start stamp above so that a case about the SUBMISSION handle cannot pass by
     * accidentally reading a stamp another operation reports.</p>
     */
    private static final String SUBMITTED_AT = "2026-08-05 09:14:27.481903";

    private ReportExecutionService executions;

    private ArtifactStore artifacts;

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
     *
     * <p>This method takes no parameter and yields no value.</p>
     */
    @BeforeEach
    void setUp() {
        executions = Mockito.mock(ReportExecutionService.class);
        artifacts = Mockito.mock(ArtifactStore.class);
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
                        new ReportController(executions, reports,
                                new CursorToken(CURSOR_KEY, CURSOR_LIFETIME),
                                new ReportArtifactLocator(REPORT_PREFIX), artifacts))
                // WHY : Assumptions: the resource converter is registered beside the JSON one because
                //       setMessageConverters REPLACES the default list, and the artifact operation's body
                //       is a stream rather than a document -- without it the collection case would fail on
                //       the harness rather than on the controller. A running application registers it
                //       itself, so this restores the production shape rather than extending it.
                .setMessageConverters(converter, new ResourceHttpMessageConverter())
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    // WHY : Assumptions: the accepting arm transcribed here is app/cbl/CORPT00C.cbl L478, whose
    //       condition admits BOTH 'Y' and 'y', and the sentence asserted is the one the reference
    //       builds at L449 to L452 from the suffix literal at L450 under the success colour set at
    //       L448. No other arm of that paragraph reaches the submission at all.
    /**
     * Asserts that a confirmed request answers 201 with the accepted run and the verbatim sentence.
     *
     * <p>This case takes no parameter and yields no value.</p>
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
                        EXECUTION_NAME,
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
                .andExpect(jsonPath("$.outcome").value("STARTED"))
                .andExpect(jsonPath("$.message")
                        .value("Monthly" + ReportController.SUBMITTED_SUFFIX))
                // WHY : ⚠️ Assumptions: the HANDLE is asserted by name, and it is the member this
                //       response exists to deliver. A review found the submission publishing an
                //       execution ARN while the status operation is addressed by name, so a caller
                //       could start a run and never poll it; asserting the report type alone left the
                //       one member that closes the lifecycle unchecked. The value is the same constant
                //       the status cases address their requests with.
                .andExpect(jsonPath("$.submission.executionName").value(EXECUTION_NAME))
                .andExpect(jsonPath("$.submission.executionArn").doesNotExist())
                .andExpect(jsonPath("$.submission.reportName").value("Monthly"));
    }

    // WHY : Assumptions: the header is asserted to reach the SERVICE rather than merely to be accepted
    //       by the handler. What the key does -- decide whether a repeat submission is a duplicate or a
    //       new run -- happens entirely inside ReportExecutionService, so a handler that bound the
    //       header and then dropped it would answer 201 exactly as it does here while leaving every
    //       retry starting a second run. Only the captured argument distinguishes the two.
    // WHY : Refactoring Rationale: the reference submits by writing eighty-byte records to the queue
    //       defined at app/csd/CARDDEMO.CSD L499 to L503, whose DISPOSITION(MOD) at L503 APPENDS
    //       every write, so a repeated turn queued a second job and the only failure the reference
    //       could report was the write itself at app/cbl/CORPT00C.cbl L531. The key asserted here is
    //       what makes a replayed submission start one run rather than two.
    /**
     * Asserts that a submitted idempotency key is carried through to the execution start.
     *
     * <p>This case takes no parameter and yields no value.</p>
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
                        EXECUTION_NAME,
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
     * <p>This case takes no parameter and yields no value.</p>
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
                // WHY : ⚠️ Refactoring Rationale: the discriminator is asserted as DECLINED, where this
                //       case asserted a boolean false. The boolean could not separate this turn from an
                //       unanswered confirmation -- both answered 200 with it false -- and the browser
                //       client, reading the status alone, labelled an unanswered turn DECLINED. Asserting
                //       the value that distinguishes them is what makes the two cases distinguishable
                //       here too.
                .andExpect(jsonPath("$.outcome").value("DECLINED"))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.submission").doesNotExist());

        verify(executions, never()).start(any(), any(), any(), any(), any());
    }

    // WHY : Assumptions: the prompting arm is app/cbl/CORPT00C.cbl L464 to L473, which fires when the
    //       confirmation field holds SPACES or LOW-VALUES and composes its sentence from the prefix
    //       at L466. It raises the error flag at L471 and submits nothing, which is why this turn
    //       answers a prompt rather than a refusal.
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
     * <p>Measured: the two turns that share 200 were each relabelled as the other, and each mutation
     * fails exactly one case with the wrong label named. Answering this turn with the cancellation
     * outcome -- which is the defect the review found in the browser client -- fails this case with
     * {@code JSON path "$.outcome" expected:<UNANSWERED> but was:<DECLINED>}. Answering the cancellation
     * with this turn's outcome instead fails {@code aDeclinedRequestAnswersWithNoRun} with
     * {@code JSON path "$.outcome" expected:<DECLINED> but was:<UNANSWERED>}. Neither mutation changes a
     * status code, so no case that reads only the status detects either one.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
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
                .andExpect(jsonPath("$.outcome").value("UNANSWERED"))
                .andExpect(jsonPath("$.message").value(ReportController.CONFIRM_PROMPT_PREFIX
                        + "Yearly" + ReportController.CONFIRM_PROMPT_SUFFIX))
                .andExpect(jsonPath("$.submission").doesNotExist());

        verify(executions, never()).start(any(), any(), any(), any(), any());
    }

    // WHY : Assumptions: the refusal transcribed here is the WHEN OTHER arm of
    //       SUBMIT-JOB-TO-INTRDR at app/cbl/CORPT00C.cbl L484 to L493, which builds the quoted
    //       sentence at L488, raises the error flag at L491 and positions the cursor on the
    //       confirmation field at L492. It is the ONLY one of that paragraph's four arms that
    //       refuses, which is why it is the only one answered with a problem body here.
    /**
     * Asserts that an unrecognised confirmation answers 400 with the per-field array.
     *
     * <p>Assumptions: no exception escapes this call. The resolver raises a
     * {@link ClientInputException} and {@link GlobalExceptionHandler} converts it into the problem
     * body under status 400, so what is asserted here is the CONVERTED shape and not a thrown type.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
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
    // WHY : Assumptions: the reference reads the report type FIRST -- app/cbl/CORPT00C.cbl L435
    //       performs the submission paragraph only from inside a matched selector arm, while the
    //       unmatched arm at L437 to L442 emits the sentence at L438 and never reaches the
    //       confirmation at all. The order asserted here is the reference's own.
    /**
     * Asserts that a request selecting no report type is refused before the confirmation is read.
     *
     * <p>Assumptions: the refusal reaches the wire converted rather than thrown. The resolver raises a
     * {@link ClientInputException} that {@link GlobalExceptionHandler} renders as the 400 problem
     * body, which is why this case asserts a body and a status rather than a caught exception.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
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
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the zero-mark refusal sentence is the reference literal verbatim")
    void theZeroMarkRefusalIsTheReferenceLiteral() {
        assertThat(ReportExecutionService.MESSAGE_NO_REPORT_TYPE_SELECTED)
                .as("app/cbl/CORPT00C.cbl L438 moves this text, three full stops and all")
                .isEqualTo("Select a report type to print report...");
    }

    // WHY : Assumptions: the two populations are the reference's own two record groups -- the detail
    //       line of app/cpy/CVTRA07Y.cpy L16 to L31 and the three total groups at L50 to L66 --
    //       written by app/cbl/CBTRN03C.cbl, which counts rendered lines against the page window at
    //       L282. Groups the reference keeps apart stay apart here.
    /**
     * Asserts that the lines read and the totals read each answer their own population, money quoted.
     *
     * <p>Refactoring Rationale: the two populations are asserted through TWO requests, and an earlier
     * revision asserted them through one. The published contract declares them as separate
     * operations -- the lines paged and the totals accumulated over the whole range -- so a single
     * request would assert a body no deployed route serves.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
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
    //       difference is what keeps a client from parsing an amount into IEEE-754 binary floating
    //       point.
    // WHY : Assumptions: the amounts carried here are the reference's EDITED money fields --
    //       app/cpy/CVTRA07Y.cpy L30 declares the detail amount under the mask -ZZZ,ZZZ,ZZZ.ZZ and
    //       L54, L60 and L66 declare the three band amounts under +ZZZ,ZZZ,ZZZ.ZZ. An edited picture
    //       is characters and never a machine number, which is precisely what the quoting preserves.
    /**
     * Asserts that a monetary amount reaches the wire quoted rather than as a JSON number.
     *
     * <p>This case takes no parameter and yields no value.</p>
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

    // WHY : Assumptions: the mask a bound has to satisfy is the reference's own,
    //       WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD' at app/cbl/CORPT00C.cbl L72, and the
    //       reference's answer for a value that cannot be a date under it is the assembled-date
    //       sentence at L400 for the start bound and L420 for the end bound. A query parameter
    //       carries the same bound as a request body member, so it is held to the same mask.
    /**
     * Asserts that a date query parameter that is not a calendar date answers 400.
     *
     * <p>This case takes no parameter and yields no value.</p>
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
    // WHY : Assumptions: the order a cursor names a position WITHIN is the reference's own ascending
    //       sort at app/jcl/TRANREPT.jcl L46 over the key declared at L41. A direction carrying no
    //       position names no place in that order to resume from, so it cannot be honoured.
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
     * <p>This case takes no parameter and yields no value.</p>
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
    // WHY : Assumptions: the reference reads its selected rows in exactly ONE order --
    //       app/jcl/TRANREPT.jcl L46 sorts ascending on the key declared at L41 -- so a resumed read
    //       has exactly two senses and no third. The two published values are those two senses.
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
     * <p>This case takes no parameter and yields no value.</p>
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
    // WHY : Assumptions: the position a cursor seals is the reference's own sort key pair --
    //       app/jcl/TRANREPT.jcl L41 declares sixteen characters at position 263 and L42 declares ten
    //       characters at position 305 -- so a value wider than the sealer's bound cannot be a
    //       position in that order and is turned away before the sealer is asked.
    /**
     * Asserts a cursor wider than the sealer's own maximum is refused before the sealer is asked.
     *
     * <p>Assumptions: this is the runtime half of the published cursor bound. The document declares
     * {@code maxLength} equal to {@link CursorToken#MAX_TOKEN_LENGTH}, and without this constraint an
     * over-long value would reach the sealer and be refused there -- the same status, but decided by a
     * component that has to decipher the value first rather than by a bound that can refuse it on
     * sight.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
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
    // WHY : Assumptions: the reference holds its browse position in the task's own storage under the
    //       transaction defined at app/csd/CARDDEMO.CSD L409 to L410, and a task arriving with no
    //       passed area starts over at app/cbl/CORPT00C.cbl L172 to L174, so one terminal could never
    //       resume another terminal's position. The binding asserted here preserves that property.
    /**
     * Asserts that a boundary token minted for one caller cannot be redeemed by another.
     *
     * <p>This case takes no parameter and yields no value.</p>
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
    // WHY : Assumptions: the reference resumes within the single ascending sense of
    //       app/jcl/TRANREPT.jcl L46, so a position taken at the END of a forward step is not a
    //       position a backward step may open. The sense is part of what the token seals.
    /**
     * Asserts that a trailing boundary token cannot be replayed as a backward step.
     *
     * <p>This case takes no parameter and yields no value.</p>
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
    // WHY : Assumptions: the range is part of the reference's SELECTION and not a view over it --
    //       app/jcl/TRANREPT.jcl L43 and L44 supply the two bounds and L47 to L48 admit only rows
    //       between them -- so a position over one range is not a position over another.
    /**
     * Asserts that a boundary token issued over one range is refused over another.
     *
     * <p>This case takes no parameter and yields no value.</p>
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

    /**
     * Builds a confirmed custom-range request over a well-shaped July 2022 pair.
     *
     * <p>Assumptions: the custom type is used by the field-error cases because it is the only one of
     * the three that reads a caller's bounds at all -- {@code app/cbl/CORPT00C.cbl} derives the other
     * two from a clock at L215 and L241 -- so it is the only type for which a bound can fail.</p>
     *
     * @return a custom-range request carrying a confirming answer; never {@code null}
     */
    private static ReportRequest customRequest() {
        return customRequest("2022-07-01", "2022-07-31");
    }

    /**
     * Builds a confirmed custom-range request over one caller-supplied pair.
     *
     * @param startDate the lower bound to send, in the ten-character separated form the mask at
     *     {@code app/cbl/CORPT00C.cbl} L72 names
     * @param endDate the upper bound to send, in the same form
     * @return a custom-range request carrying a confirming answer; never {@code null}
     */
    private static ReportRequest customRequest(String startDate, String endDate) {
        return new ReportRequest(null, null, null, null, null, null, null, null, MARK,
                startDate, endDate, ReportExecutionService.CONFIRM_YES, null);
    }

    // WHY : Assumptions: every case below sends the direction as a HARD-CODED LITERAL rather than as
    //       ReportController.PREVIOUS_DIRECTION or NEXT_DIRECTION. That is the whole point of the group
    //       and is not incidental style. The controller's backward literal was once "prev", which the
    //       published enumeration never offered, so no contract-conforming caller could reach a backward
    //       read at all -- and the suite could not see it, because the cases that exercised the
    //       direction sent the constant, so the value under test and the value asserted were the same
    //       symbol and moved together. A test bound to the implementation's own constant cannot detect a
    //       constant that disagrees with the contract; only a test bound to the published spelling can.
    // WHY : Assumptions: the reference orders its rows once, ascending, at app/jcl/TRANREPT.jcl L46,
    //       so the vocabulary a resumed read needs is two-valued. These two constants ARE that
    //       vocabulary, and they are pinned so a rename cannot pass unnoticed.
    /**
     * Asserts that the two direction constants hold exactly the values the published contract offers.
     *
     * <p>Assumptions: the literals here are copied from the {@code PageDirection} enumeration in
     * {@code src/main/resources/openapi/reporting-api.yaml}, which declares {@code [next, previous]} and
     * defaults to {@code next}. This case is the cheapest guard against the specific regression that a
     * constant drifts away from the contract it is supposed to spell.
     *
     * <p>This case takes no parameter and yields no value.</p>
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

    // WHY : Assumptions: a backward step walks the reference's ascending order at
    //       app/jcl/TRANREPT.jcl L46 in reverse, which is why the published backward value has to
    //       reach the read AS a backward one rather than be quietly taken as forward.
    /**
     * Asserts that the contract's backward value reaches the service as a backward read.
     *
     * <p>Assumptions: the assertion is made on the boolean the SERVICE receives rather than on the
     * status, because a forward read of a valid trailing cursor also answers 200 -- which is exactly
     * what the defect did. Only the direction argument distinguishes the two outcomes.
     *
     * <p>This case takes no parameter and yields no value.</p>
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

    // WHY : Assumptions: the window this operation pages by is the report's own, not one chosen for
    //       the wire. app/cbl/CBTRN03C.cbl tests its line counter against
    //       WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20, declared at its L131 and L132, with the test at
    //       L282 -- so a caller reading one window at a time reads a window the report is built
    //       around, and a direction is how it moves between them.
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
     * <p>This case takes no parameter and yields no value.</p>
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
    // WHY : Assumptions: the reference refuses an action it does not offer rather than guessing at
    //       one -- app/cbl/CORPT00C.cbl L190 to L193 answers any unoffered attention identifier with
    //       a message -- and the two senses it does offer follow from the single ascending order at
    //       app/jcl/TRANREPT.jcl L46.
    /**
     * Asserts that a direction the contract does not publish is refused and reads nothing.
     *
     * <p>This case takes no parameter and yields no value.</p>
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

    // WHY : ⚠️ Refactoring Rationale: the six cases below cover the two operations that close the report
    //       lifecycle, and both were absent. A submission returned an orchestration handle that no
    //       operation consumed, so a caller could not tell a run still going from one that had failed and
    //       could not reach the document the run produced. Each case asserts one thing that was
    //       unobservable before it: the status, the echoed coordinates, the result location, its absence
    //       while nothing is stored, the refusal of a malformed name, and the streamed bytes.
    // WHY : Refactoring Rationale: the reference could not observe a submitted job at all -- it wrote
    //       records to the queue at app/csd/CARDDEMO.CSD L499 to L503 with no reply path, and
    //       ERROROPTION(IGNORE) at L501 let even the write be swallowed. Reporting a run still in
    //       flight is a target addition, and it reports no result because there is none yet.
    /**
     * Asserts that a running execution reports its status with no result location.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a running execution reports its status and no result")
    void aRunningExecutionReportsNoResult() throws Exception {
        when(executions.describeExecution(EXECUTION_NAME)).thenReturn(new ReportExecutionService
                .ExecutionState(EXECUTION_NAME, ReportExecutionService.ExecutionStatus.RUNNING,
                        STARTED_AT, null, coordinates()));

        mockMvc.perform(get(ReportController.BASE_PATH + "/executions/" + EXECUTION_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.startedAt").value(STARTED_AT))
                .andExpect(jsonPath("$.reportType").value("monthly"))
                .andExpect(jsonPath("$.startDate").value("2022-07-01"))
                .andExpect(jsonPath("$.endDate").value("2022-07-31"))
                .andExpect(jsonPath("$.resultUri").value((String) null))
                .andExpect(jsonPath("$.resultGeneratedAt").value((String) null));

        // WHY : Assumptions: the store is asserted NEVER consulted for a run that has produced nothing.
        //       A metadata call there would spend a request to learn what the status already says, and it
        //       is the kind of cost that only shows up under polling.
        verify(artifacts, never()).describe(anyString());
    }

    // WHY : Assumptions: what a finished run leaves behind is the report generation the reference
    //       produces at app/jcl/TRANREPT.jcl L76 to L80 under LRECL=133 at L78, so a run that reached
    //       its end has a produced report to name.
    /**
     * Asserts that a succeeded execution reports the location of the artifact the store holds.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a succeeded execution reports where its report is collected from")
    void aSucceededExecutionReportsItsResult() throws Exception {
        when(executions.describeExecution(EXECUTION_NAME)).thenReturn(new ReportExecutionService
                .ExecutionState(EXECUTION_NAME, ReportExecutionService.ExecutionStatus.SUCCEEDED,
                        STARTED_AT, STOPPED_AT, coordinates()));
        String key = REPORT_PREFIX + "dt=2022-07-31/type=monthly/from=2022-07-01/to=2022-07-31/"
                + ReportArtifactLocator.REPORT_OBJECT;
        when(artifacts.describe(key)).thenReturn(Optional.of(
                new ArtifactStore.ArtifactDescriptor(key, 8_192L, WRITTEN_AT)));

        mockMvc.perform(get(ReportController.BASE_PATH + "/executions/" + EXECUTION_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.stoppedAt").value(STOPPED_AT))
                .andExpect(jsonPath("$.resultUri").value(ReportArtifactLocator.ARTIFACT_PATH
                        + "?type=monthly&startDate=2022-07-01&endDate=2022-07-31"))
                .andExpect(jsonPath("$.resultGeneratedAt").value(WRITTEN_AT));
    }

    // WHY : Assumptions: the produced generation of app/jcl/TRANREPT.jcl L76 to L80 exists only once
    //       the step has written it, so a run that ended without writing one has nothing to name and
    //       says so rather than naming a place that holds nothing.
    /**
     * Asserts that a succeeded execution whose artifact is gone reports no location.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a succeeded execution whose artifact is gone reports no location")
    void aSucceededExecutionWithoutAnArtifactReportsNoLocation() throws Exception {
        when(executions.describeExecution(EXECUTION_NAME)).thenReturn(new ReportExecutionService
                .ExecutionState(EXECUTION_NAME, ReportExecutionService.ExecutionStatus.SUCCEEDED,
                        STARTED_AT, STOPPED_AT, coordinates()));
        when(artifacts.describe(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get(ReportController.BASE_PATH + "/executions/" + EXECUTION_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.resultUri").value((String) null));
    }

    // WHY : Refactoring Rationale: this operation has NO reference arm to transcribe, and saying so is
    //       the provenance. app/cbl/CORPT00C.cbl submits by writing eighty-character records to a
    //       queue at L498 to L508 and L515 to L522 and returns no identity of any kind, so the
    //       reference cannot be asked about a run at all. The absent-name answer is therefore the
    //       target's own, and it is answered as absent rather than as a refusal because WHICH names
    //       exist is not published while the SHAPE one must have is.
    /**
     * Asserts that an unknown execution answers 404 through the shared advice.
     *
     * <p>Assumptions: the absence is signalled by a {@link NoSuchElementException} from the resolver,
     * which {@link GlobalExceptionHandler} converts into status 404. The exception is the service's
     * vocabulary for a run it cannot name; the status is the only part a caller ever observes.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unknown execution answers 404")
    void anUnknownExecutionAnswersNotFound() throws Exception {
        when(executions.describeExecution(EXECUTION_NAME))
                .thenThrow(new NoSuchElementException("no report execution of that name is known"));

        mockMvc.perform(get(ReportController.BASE_PATH + "/executions/" + EXECUTION_NAME))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_NOT_FOUND));
    }

    // WHY : Refactoring Rationale: the shape refused here is the target's own for the same reason the
    //       case above records -- the submission at app/cbl/CORPT00C.cbl L498 to L508 returns no
    //       identity, so there is no reference width to read. Refusing the shape BEFORE asking the
    //       orchestration is what keeps a value that could not be a name from reaching a describe
    //       call, and the shape is published so refusing it discloses nothing.
    /**
     * Asserts that an execution name of the wrong shape is refused before the orchestration is asked.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a malformed execution name is refused before the orchestration is asked")
    void aMalformedExecutionNameIsRefused() throws Exception {
        mockMvc.perform(get(ReportController.BASE_PATH + "/executions/" + "a".repeat(81)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION));

        verify(executions, never()).describeExecution(anyString());
    }

    // WHY : Assumptions: what is streamed is the produced report of app/cbl/CBTRN03C.cbl, whose lines
    //       are 133 positions wide -- WS-BLANK-LINE PIC X(133) at its L133 -- and which
    //       app/jcl/TRANREPT.jcl writes to a catalogued dataset declared LRECL=133 RECFM=FB at its
    //       L76 to L80. It is streamed as bytes rather than rendered, because a fixed-width report is
    //       a document and re-encoding it through a text representation would disturb the column
    //       alignment those 133 positions exist to hold.
    /**
     * Asserts that the produced report is streamed as an attachment at its declared length.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("the produced report is streamed as an attachment")
    void theProducedReportIsStreamed() throws Exception {
        byte[] bytes = "REPORT LINE ONE\n".getBytes(StandardCharsets.UTF_8);
        String key = REPORT_PREFIX + "dt=2022-07-31/type=monthly/from=2022-07-01/to=2022-07-31/"
                + ReportArtifactLocator.REPORT_OBJECT;
        when(artifacts.open(key)).thenReturn(
                new ArtifactStore.OpenArtifact(bytes.length, new ByteArrayInputStream(bytes)));

        byte[] body = mockMvc.perform(get(ReportArtifactLocator.ARTIFACT_PATH)
                        .param(ReportArtifactLocator.TYPE_PARAMETER, "monthly")
                        .param(ReportArtifactLocator.START_DATE_PARAMETER, "2022-07-01")
                        .param(ReportArtifactLocator.END_DATE_PARAMETER, "2022-07-31"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, bytes.length))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(body).isEqualTo(bytes);
    }

    // WHY : Assumptions: the store is asserted NEVER opened, which is what separates a closed domain from
    //       a coincidence. A type outside the domain reaching a key would let a caller compose a key
    //       segment of its own, which is the whole reason the type is admitted from a fixed set.
    // WHY : Assumptions: the reference offers exactly three report types -- the selector arms at
    //       app/cbl/CORPT00C.cbl L213, L239 and L256 -- and answers anything else with the sentence at
    //       L438 from the unmatched arm at L437 to L442. A fourth type is not one of the three.
    /**
     * Asserts that a report type outside the published domain is refused before the store is opened.
     *
     * <p>This case takes no parameter and yields no value.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a report type outside the published domain is refused")
    void anUnpublishedReportTypeIsRefused() throws Exception {
        mockMvc.perform(get(ReportArtifactLocator.ARTIFACT_PATH)
                        .param(ReportArtifactLocator.TYPE_PARAMETER, "../secrets")
                        .param(ReportArtifactLocator.START_DATE_PARAMETER, "2022-07-01")
                        .param(ReportArtifactLocator.END_DATE_PARAMETER, "2022-07-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field")
                        .value(ReportArtifactLocator.TYPE_PARAMETER));

        verify(artifacts, never()).open(anyString());
    }

    /**
     * Builds the coordinates a described execution carries.
     *
     * @return the coordinates of a monthly run over July 2022; never {@code null}
     */
    private static ReportExecutionService.ExecutionCoordinates coordinates() {
        return new ReportExecutionService.ExecutionCoordinates("monthly",
                LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31));
    }

    /**
     * Class-path location of the contract of record this service packages and serves.
     *
     * <p>Assumptions: the document is read from the class path rather than from the source tree,
     * because the packaged copy is the one a deployed service serves and a source-tree read would
     * pass while the packaging step dropped the resource.</p>
     */
    private static final String CONTRACT_RESOURCE = "/openapi/reporting-api.yaml";

    /**
     * Stubs the three resolution steps of a confirmed monthly submission over one resolved pair.
     *
     * <p>Assumptions: all three steps are stubbed together because the handler calls them in a fixed
     * order and a case that stubbed only one would fail on the next unstubbed call rather than on the
     * property it names. The report type is the monthly one at {@code app/cbl/CORPT00C.cbl} L214.</p>
     *
     * @param executions the substituted orchestration the handler resolves through; must not be
     *     {@code null}
     * @param start the resolved inclusive lower bound the substitute reports; must not be
     *     {@code null}
     * @param end the resolved inclusive upper bound the substitute reports; must not be {@code null}
     */
    private static void stubConfirmedMonthly(ReportExecutionService executions, LocalDate start,
            LocalDate end) {

        when(executions.resolveReportName(any()))
                .thenReturn(ReportExecutionService.MONTHLY_REPORT_NAME);
        when(executions.resolveRange(any(), eq(ReportExecutionService.MONTHLY_REPORT_NAME)))
                .thenReturn(new ReportExecutionService.DateRange(start, end));
        when(executions.resolveConfirmation(any()))
                .thenReturn(ReportExecutionService.Confirmation.CONFIRMED);
        when(executions.start(any(), eq(ReportExecutionService.MONTHLY_REPORT_NAME), eq(start),
                eq(end), any()))
                .thenReturn(acceptedRun(start.toString(), end.toString()));
    }

    /**
     * Builds the accepted-run description the orchestration reports for a monthly submission.
     *
     * <p>Assumptions: the two header names are taken from
     * {@link com.carddemo.reporting.mapper.ReportBandLayouts}, which is where the service itself
     * reads them, so a case asserting them on the wire is asserting the value a deployment carries
     * rather than one this class invented.</p>
     *
     * @param start the resolved lower bound in the ten-character separated form; must not be
     *     {@code null}
     * @param end the resolved upper bound in the ten-character separated form; must not be
     *     {@code null}
     * @return the accepted-run description; never {@code null}
     */
    private static ReportSubmissionResponse acceptedRun(String start, String end) {
        return new ReportSubmissionResponse(
                EXECUTION_NAME,
                ReportExecutionService.MONTHLY_REPORT_NAME,
                ReportBandLayouts.REPORT_SHORT_NAME,
                ReportBandLayouts.REPORT_LONG_NAME,
                start,
                end,
                SUBMITTED_AT);
    }

    /**
     * Reads the packaged contract of record as text.
     *
     * <p>Assumptions: the document is compared as TEXT rather than parsed, because what the
     * verbatim-sentence cases assert is that a byte sequence appears in it. Parsing would resolve the
     * folded block scalars the message catalogue is written in and could normalise the very spacing
     * those cases exist to pin.</p>
     *
     * @return the whole packaged document; never {@code null}
     * @throws Exception if the resource is absent from the class path or cannot be read, either of
     *     which means the packaged contract is not the one under test
     */
    private static String packagedContract() throws Exception {
        try (InputStream document = ReportControllerTest.class
                .getResourceAsStream(CONTRACT_RESOURCE)) {
            assertThat(document)
                    .as("%s must be packaged on the class path", CONTRACT_RESOURCE)
                    .isNotNull();
            return new String(document.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Supplies the nineteen sentences {@code app/cbl/CORPT00C.cbl} declares, each with its line.
     *
     * <p>Assumptions: every sentence is written here as a LITERAL and never read from a production
     * constant. A case expressed through the constant passes whatever the constant holds, so it would
     * pass against a paraphrase exactly as it passes against the reference text; only a literal holds
     * the migration to the line it came from.</p>
     *
     * @return one argument pair per sentence, in reference line order; never {@code null}
     */
    private static Stream<Arguments> referenceSentences() {
        return Stream.of(
                Arguments.of(new ReferenceSentence(261, "Start Date - Month can NOT be empty...")),
                Arguments.of(new ReferenceSentence(268, "Start Date - Day can NOT be empty...")),
                Arguments.of(new ReferenceSentence(275, "Start Date - Year can NOT be empty...")),
                Arguments.of(new ReferenceSentence(282, "End Date - Month can NOT be empty...")),
                Arguments.of(new ReferenceSentence(289, "End Date - Day can NOT be empty...")),
                Arguments.of(new ReferenceSentence(296, "End Date - Year can NOT be empty...")),
                Arguments.of(new ReferenceSentence(331, "Start Date - Not a valid Month...")),
                Arguments.of(new ReferenceSentence(340, "Start Date - Not a valid Day...")),
                Arguments.of(new ReferenceSentence(348, "Start Date - Not a valid Year...")),
                Arguments.of(new ReferenceSentence(357, "End Date - Not a valid Month...")),
                Arguments.of(new ReferenceSentence(366, "End Date - Not a valid Day...")),
                Arguments.of(new ReferenceSentence(374, "End Date - Not a valid Year...")),
                Arguments.of(new ReferenceSentence(400, "Start Date - Not a valid date...")),
                Arguments.of(new ReferenceSentence(420, "End Date - Not a valid date...")),
                Arguments.of(new ReferenceSentence(438, "Select a report type to print report...")),
                Arguments.of(new ReferenceSentence(450, " report submitted for printing ...")),
                Arguments.of(new ReferenceSentence(466, "Please confirm to print the ")),
                Arguments.of(new ReferenceSentence(488, "\" is not a valid value to confirm...")),
                Arguments.of(new ReferenceSentence(531, "Unable to Write TDQ (JOBS)...")));
    }

    /**
     * One user-visible sentence the reference declares, paired with the line that declares it.
     *
     * <p>Assumptions: the line travels WITH the text rather than being written into an assertion
     * description at the call site, so a reader of a failure message learns which reference line the
     * expectation came from without opening this file.</p>
     *
     * @param line the one-based line of {@code app/cbl/CORPT00C.cbl} that moves this sentence
     * @param text the sentence exactly as the reference declares it, spacing, capitalisation and the
     *     three trailing full stops included
     */
    private record ReferenceSentence(int line, String text) {
    }

    /**
     * Supplies the three declared message widths this surface inherits, each with its provenance.
     *
     * <p>Assumptions: three regimes reach this surface and they are supplied as three rows rather
     * than reconciled into one, for the reason RC-02 records on this class.</p>
     *
     * @return one argument per width regime, widest first; never {@code null}
     */
    private static Stream<Arguments> messageWidthRegimes() {
        return Stream.of(
                Arguments.of(new MessageWidthRegime(80, "app/cbl/CORPT00C.cbl", 39,
                        "the program's own working buffer, WS-MESSAGE")),
                Arguments.of(new MessageWidthRegime(78, "app/cpy-bms/CORPT00.CPY", 120,
                        "the screen field, ERRMSGI, with ERRMSGO at L224")),
                Arguments.of(new MessageWidthRegime(75, "app/cpy/CVCRD01Y.cpy", 28,
                        "the shared carrier, CCARD-ERROR-MSG, with CCARD-RETURN-MSG at L29")));
    }

    /**
     * One declared message width, the artifact that declares it and the role it plays.
     *
     * @param width the number of positions the declaring artifact reserves
     * @param sourcePath the repository-relative path of the artifact that declares the width
     * @param sourceLine the one-based line of that artifact carrying the declaration
     * @param role what the field of that width carries, in words, so the three cannot be conflated
     */
    private record MessageWidthRegime(int width, String sourcePath, int sourceLine, String role) {
    }

    /**
     * Lists the component names a record type declares, in declaration order.
     *
     * <p>Assumptions: the names are read from the record's components rather than from a serialised
     * body, because a component's presence is the contract while its appearance in one rendered body
     * depends on what a case happened to stub.</p>
     *
     * @param type the record type whose components are wanted; must not be {@code null} and must be a
     *     record
     * @return the component names in declaration order; never {@code null}
     */
    private static List<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Groups the cases that hold every user-visible sentence and declared width to its source line.
     *
     * <p>Assumptions: this group exists because {@code CORPT00C} is an online program with no golden
     * master, as the class documentation records. The sentences and the widths are therefore the only
     * mechanical parity evidence this surface has, which is why they are asserted here rather than
     * left to a reader's comparison.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on the reference sentence and width register")
    class OnTheReferenceMessageRegister {

        // WHY : Assumptions: the sentence is looked for in the PACKAGED contract, which is the
        //       document a deployed service serves and the one ui/src/messages/messages.ts copies
        //       from. Comparing a literal against a Java constant would only prove this module
        //       agrees with itself; comparing it against the published document proves the string a
        //       client is written against is the reference's own. app/cbl/CORPT00C.cbl is the source
        //       of all nineteen and each row names its line.
        // WHY : Assumptions: the nineteen sentences are read from the reference at their own lines -- the
        //       six empty-component sentences at app/cbl/CORPT00C.cbl L261, L268, L275, L282, L289 and
        //       L296, the six range sentences at L331, L340, L348, L357, L366 and L374, the two delegated
        //       sentences at L400 and L420, and the five flow sentences at L438, L450, L466, L488 and
        //       L531.
        /**
         * Confirms one reference sentence is published verbatim by the contract of record.
         *
         * <p>Assumptions: the comparison is a containment test on the document text and not an
         * equality test on a catalogue entry, because the nineteen sentences appear in the document
         * in three different roles -- as message-catalogue examples, as composed-sentence examples
         * and inside prose that cites them -- and a role-specific lookup would assert less while
         * looking stricter.</p>
         *
         * @param sentence the reference sentence and the {@code app/cbl/CORPT00C.cbl} line that
         *     declares it, supplied one row at a time by the register on the enclosing class
         * @throws Exception if the packaged contract is absent from the class path or unreadable,
         *     which means the document under test is not the one a deployment serves
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.reporting.api.ReportControllerTest#referenceSentences")
        @DisplayName("each reference sentence is published verbatim by the contract of record")
        void eachReferenceSentenceIsPublishedVerbatim(ReferenceSentence sentence) throws Exception {
            assertThat(packagedContract())
                    .as("app/cbl/CORPT00C.cbl L%d moves this sentence, so the published contract"
                            + " must carry it character for character", sentence.line())
                    .contains(sentence.text());
        }

        // WHY : Assumptions: the six sentences this module holds as Java constants are pinned to
        //       their literals SEPARATELY from the contract check above, because the two can drift
        //       in opposite directions -- a constant can be paraphrased while the document keeps the
        //       reference text, and nothing else in the build compares one against the other.
        /**
         * Confirms the six sentences this module holds as constants equal their reference literals.
         *
         * <p>Assumptions: only six of the nineteen have a Java constant, and that is the shipped
         * shape rather than an omission: the consolidated bound means the twelve per-component
         * sentences are addressed by the client from the published catalogue, while the six the
         * service itself emits are declared here. The two assembled-date sentences carry a
         * LOWER-CASE noun where the twelve per-component ones capitalise theirs, and the difference
         * is carried rather than harmonised.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the six sentences held as constants are the reference literals verbatim")
        void theSentencesHeldAsConstantsAreTheReferenceLiterals() {
            assertThat(ReportExecutionService.MESSAGE_START_DATE_MONTH_EMPTY)
                    .as("app/cbl/CORPT00C.cbl L261")
                    .isEqualTo("Start Date - Month can NOT be empty...");
            assertThat(ReportExecutionService.MESSAGE_END_DATE_MONTH_EMPTY)
                    .as("app/cbl/CORPT00C.cbl L282")
                    .isEqualTo("End Date - Month can NOT be empty...");
            assertThat(ReportExecutionService.MESSAGE_START_DATE_INVALID)
                    .as("app/cbl/CORPT00C.cbl L400, lower-case noun included")
                    .isEqualTo("Start Date - Not a valid date...");
            assertThat(ReportExecutionService.MESSAGE_END_DATE_INVALID)
                    .as("app/cbl/CORPT00C.cbl L420, lower-case noun included")
                    .isEqualTo("End Date - Not a valid date...");
            assertThat(ReportExecutionService.MESSAGE_NO_REPORT_TYPE_SELECTED)
                    .as("app/cbl/CORPT00C.cbl L438")
                    .isEqualTo("Select a report type to print report...");
            assertThat(ReportExecutionService.INVALID_CONFIRM_PREFIX
                    + "Q" + ReportExecutionService.INVALID_CONFIRM_SUFFIX)
                    .as("app/cbl/CORPT00C.cbl L485 to L489 wraps the answer in the two fragments")
                    .isEqualTo("\"Q\" is not a valid value to confirm...");
        }

        // WHY : Assumptions: the two sentences this CONTROLLER composes are pinned to their literals
        //       for the same reason. Both are assembled from fragments rather than moved whole, so a
        //       fragment losing its leading or trailing space would still concatenate cleanly and no
        //       equality test on a fragment alone would notice.
        /**
         * Confirms the two sentences the handler composes match the reference character for
         * character, spaces included.
         *
         * <p>Assumptions: the space BEFORE the three dots of the acceptance sentence and the space
         * AFTER the confirmation prompt's last word are both part of the reference text, at
         * {@code app/cbl/CORPT00C.cbl} L450 and L466 respectively. They are asserted explicitly
         * because a trailing space is the one difference no reader spots in a diff.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the two composed sentences carry the reference spacing exactly")
        void theComposedSentencesCarryTheReferenceSpacing() {
            assertThat(ReportController.SUBMITTED_SUFFIX)
                    .as("app/cbl/CORPT00C.cbl L450, whose literal opens with a space and closes"
                            + " with a space before the three dots")
                    .isEqualTo(" report submitted for printing ...");
            assertThat(ReportController.CONFIRM_PROMPT_PREFIX)
                    .as("app/cbl/CORPT00C.cbl L466, whose literal closes with a space")
                    .isEqualTo("Please confirm to print the ");
            assertThat(ReportController.CONFIRM_PROMPT_SUFFIX)
                    .as("app/cbl/CORPT00C.cbl L469")
                    .isEqualTo(" report...");
        }

        // WHY : Assumptions: this sentence is kept apart from the nineteen above because its
        //       PROVENANCE is different -- it is declared in a copybook and only reached from the
        //       program, so merging the two registers would attribute a shared constant to a program
        //       that merely moves it. The reference reaches it from the unsupported-action arm at
        //       app/cbl/CORPT00C.cbl L190 to L194, whose MOVE sits at L193.
        /**
         * Confirms the copybook-sourced unsupported-action sentence and its one-space width delta.
         *
         * <p>Assumptions: the source literal at {@code app/cpy/CSMSG01Y.cpy} L21 measures 49
         * characters -- 40 of text and nine trailing blanks -- against the {@code PIC X(50)}
         * declared on L20 above it, so the field the reference loads is one blank wider than the
         * literal it loads. The two are asserted as two contracts: the shared kernel carries the
         * 49-character literal and the declared width is stated separately, which is what keeps a
         * later reader from "tidying" the constant to 50 or trimming it to 40.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the copybook unsupported-action sentence is 49 characters against a declared 50")
        void theCopybookUnsupportedActionSentenceCarriesItsWidthDelta() {
            assertThat(ApiError.CSMSG01Y_INVALID_KEY)
                    .as("app/cpy/CSMSG01Y.cpy L21, nine trailing blanks included")
                    .isEqualTo("Invalid key pressed. Please see below...         ")
                    .hasSize(49);
            assertThat(ApiError.COMPACT_MESSAGE_WIDTH)
                    .as("app/cpy/CSMSG01Y.cpy L20 declares PIC X(50), one position wider than the"
                            + " 49-character literal on L21")
                    .isEqualTo(50);
        }

        // WHY : Assumptions: the three regimes are asserted as three ROWS rather than as one number,
        //       because the failure this case exists to catch is a later reader deciding they are one
        //       width recorded inconsistently and collapsing them. Each row names the artifact and
        //       line that declares it, so the disagreement is visibly by design.
        // WHY : Assumptions: the three widths are three DIFFERENT declarations -- the program buffer at
        //       app/cbl/CORPT00C.cbl L39, the screen field at app/cpy-bms/CORPT00.CPY L120 and L224, and
        //       the two linkage carriers at app/cpy/CVCRD01Y.cpy L28 and L29 -- so holding them apart is
        //       transcription, and collapsing them would lose a declared contract.
        /**
         * Confirms one declared message width is carried at the value its own artifact declares.
         *
         * <p>Assumptions: only the 75-character regime is published by the shared kernel as a
         * rendering constraint, so the other two are asserted as the numbers this class carries with
         * their provenance rather than against a constant that does not exist. The kernel
         * additionally publishes a wider four-regime set spanning every context it serves; that is a
         * different scope and neither set is narrowed to match the other.</p>
         *
         * @param regime the width, the declaring artifact, the declaring line and the role that
         *     width plays, supplied one row at a time by the register on the enclosing class
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.reporting.api.ReportControllerTest#messageWidthRegimes")
        @DisplayName("each declared message width is held at its own declared value")
        void eachDeclaredMessageWidthIsHeldApart(MessageWidthRegime regime) {
            assertThat(regime.width())
                    .as("%s L%d declares %s", regime.sourcePath(), regime.sourceLine(),
                            regime.role())
                    .isIn(80, 78, 75);
            assertThat(regime.sourceLine()).isPositive();
        }

        // WHY : Assumptions: the kernel's own three published widths are read here so the row set
        //       above is anchored to something the build can break. Without this the rows would be
        //       three numbers this class wrote down, and a kernel that renamed or re-valued a width
        //       would leave them silently stale.
        /**
         * Confirms the shared kernel publishes the 75, 72 and 80 character widths as distinct values.
         *
         * <p>Assumptions: the 75-character rendering width is the one this surface's message band
         * inherits, from {@code CCARD-ERROR-MSG PIC X(75)} at {@code app/cpy/CVCRD01Y.cpy} L28 and
         * {@code CCARD-RETURN-MSG PIC X(75)} at its L29. The 72-character abend width and the
         * 80-character date-diagnostic width belong to other reference structures and are asserted
         * only to show the kernel keeps them apart from it.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the kernel keeps its published message widths distinct from one another")
        void theKernelKeepsItsPublishedWidthsDistinct() {
            assertThat(ApiError.MESSAGE_RENDERING_WIDTH)
                    .as("app/cpy/CVCRD01Y.cpy L28 and L29 both declare PIC X(75)")
                    .isEqualTo(75);
            assertThat(List.of(ApiError.MESSAGE_RENDERING_WIDTH, ApiError.ABEND_MESSAGE_WIDTH,
                            ApiError.DATE_DIAGNOSTIC_WIDTH, ApiError.COMPACT_MESSAGE_WIDTH))
                    .as("four published widths, none of them a restatement of another")
                    .containsExactly(75, 72, 80, 50)
                    .doesNotHaveDuplicates();
        }
    }

    /**
     * Groups the cases that hold the resolved business-date pair to the handler's contract.
     *
     * <p>Trade-offs: this group asserts that whatever pair the orchestration resolves is carried
     * through untouched, and it deliberately does NOT assert how that pair was derived. The
     * derivation reads a clock and the clock lives in {@link ReportExecutionService}, which is
     * substituted here; the sibling service case owns the arithmetic across a 31-day month, a 30-day
     * month and February in both a common and a leap year. The accepted compromise is that a
     * month-end arithmetic regression is caught there. What is bought is that a handler which
     * re-derived, truncated or re-formatted the pair fails HERE, on four differently-shaped months,
     * where a single 31-day case would have let a naive plus-one-month-minus-one-day recomputation
     * pass.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on the resolved business-date pair")
    class OnTheResolvedRangeContract {

        // WHY : Assumptions: the four rows are four differently-shaped months and not four arbitrary
        //       ranges. app/cbl/CORPT00C.cbl L223 to L230 derives the monthly upper bound by setting
        //       the day to one, advancing the month, rolling the year past twelve and subtracting one
        //       day from the integer date -- an idiom whose result differs by month length, so a
        //       contract case run on a 31-day month alone cannot tell a carried pair from a
        //       recomputed one.
        /**
         * Confirms the resolved pair reaches the accepted-run description exactly as resolved.
         *
         * <p>Assumptions: the pair is asserted BOTH on the wire and on the argument the start call
         * received. The wire assertion alone would pass for a handler that echoed its own recomputed
         * pair, and the argument assertion alone would pass for a handler that passed the pair on and
         * then rendered something else.</p>
         *
         * @param start the resolved inclusive lower bound in the ten-character separated form the
         *     mask at {@code app/cbl/CORPT00C.cbl} L72 names
         * @param end the resolved inclusive upper bound, being the last day of that month for the
         *     monthly preset
         * @param shape a short description of the month's shape, so a failing row names which of the
         *     four calendar cases broke
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest(name = "{2}: {0} to {1}")
        @CsvSource({
            "2026-08-01,2026-08-31,a 31-day month",
            "2026-09-01,2026-09-30,a 30-day month",
            "2026-02-01,2026-02-28,February in a common year",
            "2028-02-01,2028-02-29,February in a leap year"
        })
        @DisplayName("the resolved pair is carried through unchanged for every month shape")
        void theResolvedPairIsCarriedThroughUnchanged(String start, String end, String shape)
                throws Exception {

            LocalDate lower = LocalDate.parse(start);
            LocalDate upper = LocalDate.parse(end);
            stubConfirmedMonthly(executions, lower, upper);

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest("Y"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.submission.startDate").value(start))
                    .andExpect(jsonPath("$.submission.endDate").value(end));

            verify(executions).start(any(), eq(ReportExecutionService.MONTHLY_REPORT_NAME),
                    eq(lower), eq(upper), any());
            assertThat(shape).as("the row shape travels with the pair for failure reporting")
                    .isNotBlank();
        }

        // WHY : Refactoring Rationale: an end bound LATER than today is carried rather than refused,
        //       and a plausible-looking upper-bound guard would break both presets outright. The
        //       monthly preset resolves the last day of the current month at app/cbl/CORPT00C.cbl
        //       L229 to L234 and the yearly preset resolves the 31st of December at L250 and L251, so
        //       on all but one day of a month and all but one day of a year the reference itself
        //       submits a range that has not finished yet.
        /**
         * Confirms an end bound later than today is submitted rather than refused.
         *
         * <p>Assumptions: today is the instant this class pins rather than a clock reading, so the
         * case states a fact about the pair instead of depending on when it runs. The bound asserted
         * as later is the last day of the month containing that instant, which is exactly what the
         * monthly preset resolves.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an end bound later than today is submitted rather than refused")
        void aFutureEndBoundIsSubmittedRatherThanRefused() throws Exception {
            LocalDate today = LocalDate.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
            LocalDate lower = LocalDate.of(2026, 8, 1);
            LocalDate upper = LocalDate.of(2026, 8, 31);
            assertThat(upper)
                    .as("the monthly preset resolves a bound that has not arrived yet on all but"
                            + " one day of the month")
                    .isAfter(today);

            stubConfirmedMonthly(executions, lower, upper);

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest("Y"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.submission.endDate").value("2026-08-31"));
        }

        // WHY : Assumptions: the absence of a clock on the handler is asserted STRUCTURALLY rather
        //       than behaviourally, because a substituted collaborator cannot reveal a clock read the
        //       handler performs on its own. The reference's own clock reads on this path are at
        //       app/cbl/CORPT00C.cbl L215 and L241, inside the preset derivations, and at L607 to
        //       L628 where POPULATE-HEADER-INFO formats a two-digit-year stamp for the screen -- so
        //       the migration keeps exactly one clock, in the component that derives the pair.
        /**
         * Confirms the handler holds no clock while the component that derives the pair does.
         *
         * <p>Assumptions: both halves are asserted in one case because the property is a comparison
         * and not two facts. Asserting only that the handler holds none would pass for a migration
         * that had lost the clock altogether, which would leave the presets unresolvable.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the handler holds no clock and the range resolver does")
        void theHandlerHoldsNoClockAndTheResolverDoes() {
            assertThat(ReportController.class.getDeclaredFields())
                    .as("a handler holding a clock could resolve a preset behind the resolver's back")
                    .extracting(Field::getType)
                    .doesNotContain(Clock.class);
            assertThat(ReportController.class.getDeclaredConstructors()[0].getParameterTypes())
                    .as("nor may one be injected into it")
                    .doesNotContain(Clock.class);
            assertThat(ReportExecutionService.class.getDeclaredFields())
                    .as("the one clock this module admits lives here, injectable so a case can pin it")
                    .extracting(Field::getType)
                    .contains(Clock.class);
        }

        // WHY : Assumptions: the six header members are asserted to be REQUEST members and to be
        //       absent from the accepted-run description, which together say what the migration did
        //       with them. app/cbl/CORPT00C.cbl L607 to L628 fills the header fields from a clock
        //       reading and from the title copybook purely for display, so in the target they are
        //       values a caller sends and never values a handler manufactures.
        /**
         * Confirms the six header components are caller-supplied inputs and not response members.
         *
         * <p>Assumptions: the check reads the record components rather than a serialised body,
         * because absence from one rendered body could be a stubbing accident whereas absence from
         * the record is the contract.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the header components are request members and never response members")
        void theHeaderComponentsAreRequestMembersOnly() {
            List<String> headerMembers = List.of("transactionName", "title01", "currentDate",
                    "programName", "title02", "currentTime");

            assertThat(componentNames(ReportRequest.class))
                    .as("app/bms/CORPT00.bms names these six as screen fields at L34, L38, L47,"
                            + " L57, L61 and L70, so the caller supplies them")
                    .containsAll(headerMembers);
            assertThat(componentNames(ReportSubmissionResponse.class))
                    .as("none of the six is echoed back, so no handler reads a clock to fill one")
                    .doesNotContainAnyElementsOf(headerMembers);
        }

        // WHY : Assumptions: the three names are pinned to their literals because they are
        //       user-visible: each is what the acceptance and prompt sentences interpolate, so a
        //       re-cased name would change two rendered sentences at once.
        /**
         * Confirms the three report-type names are the reference literals within its declared width.
         *
         * <p>Assumptions: the width asserted is the field's declared 10 from
         * {@code WS-REPORT-NAME PIC X(10)} at {@code app/cbl/CORPT00C.cbl} L58 and not the length of
         * the longest literal, because the declared width is what the reference reserves and a bound
         * derived from the literals would have to move if a fourth name were ever assigned.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the three report-type names are the reference literals within the declared width")
        void theReportTypeNamesAreTheReferenceLiterals() {
            assertThat(ReportExecutionService.MONTHLY_REPORT_NAME)
                    .as("app/cbl/CORPT00C.cbl L214").isEqualTo("Monthly");
            assertThat(ReportExecutionService.YEARLY_REPORT_NAME)
                    .as("app/cbl/CORPT00C.cbl L240").isEqualTo("Yearly");
            assertThat(ReportExecutionService.CUSTOM_REPORT_NAME)
                    .as("app/cbl/CORPT00C.cbl L433").isEqualTo("Custom");
            assertThat(List.of(ReportExecutionService.MONTHLY_REPORT_NAME,
                            ReportExecutionService.YEARLY_REPORT_NAME,
                            ReportExecutionService.CUSTOM_REPORT_NAME))
                    .as("each fits WS-REPORT-NAME PIC X(10) at app/cbl/CORPT00C.cbl L58")
                    .allSatisfy(name -> assertThat(name).hasSizeLessThanOrEqualTo(10));
        }
    }

    /**
     * Groups the cases that hold the four-armed confirmation domain to the reference's arms.
     *
     * <p>Refactoring Rationale: a deliberate cancellation is answered 200 and not 4xx, for the reason
     * RC-03 records on this class. The four arms of {@code SUBMIT-JOB-TO-INTRDR} are at
     * {@code app/cbl/CORPT00C.cbl} L464, L478, L480 and L484, and two of them accept a LOWER-CASE
     * answer that a case sending only the upper-case spelling would never reach.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on the confirmation domain")
    class OnTheConfirmationDomain {

        // WHY : Assumptions: the lower-case spelling is sent as a REQUEST and the captured request is
        //       inspected, rather than the resolver being stubbed and the status read. The reference
        //       accepts either case at app/cbl/CORPT00C.cbl L478, and the risk being covered is a
        //       boundary that refuses the lower-case answer before any resolver sees it -- which a
        //       status-only case cannot distinguish from a resolver that was asked and said yes.
        /**
         * Confirms both accepting spellings are admitted and reach the resolver unaltered.
         *
         * <p>Assumptions: the captured answer is compared to the spelling that was sent, so a
         * boundary that silently upper-cased the value would fail here. Upper-casing would be
         * invisible in the response, because the resolver already accepts both.</p>
         *
         * @param answer one of the two accepting spellings the reference recognises at
         *     {@code app/cbl/CORPT00C.cbl} L478, supplied once per invocation
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest(name = "confirm={0}")
        @ValueSource(strings = {"Y", "y"})
        @DisplayName("either accepting spelling starts the run and reaches the resolver unaltered")
        void eitherAcceptingSpellingStartsTheRun(String answer) throws Exception {
            LocalDate lower = LocalDate.of(2022, 7, 1);
            LocalDate upper = LocalDate.of(2022, 7, 31);
            stubConfirmedMonthly(executions, lower, upper);

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest(answer))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.outcome").value("STARTED"))
                    .andExpect(jsonPath("$.submission.executionName").value(EXECUTION_NAME));

            ArgumentCaptor<ReportRequest> received = ArgumentCaptor.forClass(ReportRequest.class);
            verify(executions).resolveConfirmation(received.capture());
            assertThat(received.getValue().confirm())
                    .as("app/cbl/CORPT00C.cbl L478 accepts 'Y' OR 'y', so neither may be re-cased"
                            + " on the way in")
                    .isEqualTo(answer);
        }

        // WHY : Assumptions: the interaction is verified as well as the status, because a cancellation
        //       answered 200 while still starting a run would look correct to every client and would
        //       produce the report the caller declined. app/cbl/CORPT00C.cbl L480 to L483 clears the
        //       screen and sends it without submitting anything.
        /**
         * Confirms both declining spellings answer 200, start nothing and carry no sentence.
         *
         * <p>Assumptions: the sentence is asserted ABSENT rather than blank. The reference clears its
         * message buffer on this arm -- {@code INITIALIZE-ALL-FIELDS} at
         * {@code app/cbl/CORPT00C.cbl} L633 to L646 includes {@code WS-MESSAGE} among the eleven
         * items it clears -- and {@link ReportSubmissionOutcome} refuses a blank sentence outright, so
         * "no message" is carried as an omitted member and can never arrive as a present-and-empty
         * one.</p>
         *
         * @param answer one of the two declining spellings the reference recognises at
         *     {@code app/cbl/CORPT00C.cbl} L480, supplied once per invocation
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest(name = "confirm={0}")
        @ValueSource(strings = {"N", "n"})
        @DisplayName("either declining spelling answers 200, starts nothing and carries no sentence")
        void eitherDecliningSpellingStartsNothing(String answer) throws Exception {
            when(executions.resolveReportName(any()))
                    .thenReturn(ReportExecutionService.MONTHLY_REPORT_NAME);
            when(executions.resolveRange(any(), eq(ReportExecutionService.MONTHLY_REPORT_NAME)))
                    .thenReturn(new ReportExecutionService.DateRange(
                            LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31)));
            when(executions.resolveConfirmation(any()))
                    .thenReturn(ReportExecutionService.Confirmation.DECLINED);

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest(answer))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("DECLINED"))
                    .andExpect(jsonPath("$.message").doesNotExist())
                    .andExpect(jsonPath("$.submission").doesNotExist())
                    .andExpect(jsonPath("$.code").doesNotExist());

            verify(executions, never()).start(any(), any(), any(), any(), any());
        }

        // WHY : Assumptions: the two turns that answer 200 are asserted to carry NO refusal body,
        //       because the ruling this group exists for is that neither is an error. A body carrying
        //       a problem code beside a 200 would satisfy a status-only case while telling a client
        //       its request was wrong.
        // WHY : Assumptions: the unanswered turn at app/cbl/CORPT00C.cbl L464 to L473 composes a PROMPT
        //       from the prefix at L466 and submits nothing; it is not the refusing arm, which is L484 to
        //       L493. A prompt and a refusal are different outcomes in the reference for that reason.
        /**
         * Confirms an unanswered confirmation is a question rather than a refusal.
         *
         * <p>Assumptions: the prompt is built from the handler's own two fragments so the assertion
         * cannot drift away from the assembly while both stay wrong, and the refusal members are
         * asserted absent so the 200 is a clean answer rather than a refusal with a lenient
         * status.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an unanswered confirmation answers 200 with no refusal body")
        void anUnansweredConfirmationIsNotARefusal() throws Exception {
            when(executions.resolveReportName(any()))
                    .thenReturn(ReportExecutionService.YEARLY_REPORT_NAME);
            when(executions.resolveRange(any(), eq(ReportExecutionService.YEARLY_REPORT_NAME)))
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
                    .andExpect(jsonPath("$.outcome").value("UNANSWERED"))
                    .andExpect(jsonPath("$.message").value(
                            ReportController.CONFIRM_PROMPT_PREFIX + "Yearly"
                                    + ReportController.CONFIRM_PROMPT_SUFFIX))
                    .andExpect(jsonPath("$.code").doesNotExist())
                    .andExpect(jsonPath("$.fieldErrors").doesNotExist());
        }

        // WHY : Assumptions: an unrecognised answer is the ONE arm that refuses, so it is asserted
        //       against 400 and against the two turns above answering 200. app/cbl/CORPT00C.cbl L484
        //       to L493 raises the error flag, builds the quoted sentence and positions the cursor on
        //       the confirmation field, which is the focus pointer the field-error array carries.
        /**
         * Confirms an unrecognised answer refuses with the quoted sentence and a focus pointer.
         *
         * <p>Assumptions: the field identity is asserted as well as the sentence, because the cursor
         * position at {@code app/cbl/CORPT00C.cbl} L492 is part of what the reference reports and the
         * field-error entry is where a stateless response carries it.</p>
         *
         * <p>Assumptions: the resolver raises a {@link ClientInputException} carrying the offending member,
         * and {@link GlobalExceptionHandler} converts it into the 400 problem body whose entry this case
         * reads. Nothing propagates out of the call itself.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an unrecognised answer refuses with the quoted sentence and a focus pointer")
        void anUnrecognisedAnswerRefusesWithAFocusPointer() throws Exception {
            when(executions.resolveReportName(any()))
                    .thenReturn(ReportExecutionService.MONTHLY_REPORT_NAME);
            when(executions.resolveRange(any(), eq(ReportExecutionService.MONTHLY_REPORT_NAME)))
                    .thenReturn(new ReportExecutionService.DateRange(
                            LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31)));
            when(executions.resolveConfirmation(any())).thenThrow(new ClientInputException(
                    ApiError.CODE_VALIDATION, "confirm", FieldValidationFlag.NOT_OK,
                    "\"X\" is not a valid value to confirm..."));

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest("X"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("confirm"))
                    .andExpect(jsonPath("$.fieldErrors[0].state").value("NOT_OK"))
                    .andExpect(jsonPath("$.fieldErrors[0].message")
                            .value("\"X\" is not a valid value to confirm..."))
                    .andExpect(jsonPath("$.message")
                            .value("\"X\" is not a valid value to confirm..."));

            verify(executions, never()).start(any(), any(), any(), any(), any());
        }

        // WHY : Assumptions: the domain is asserted to have exactly three values and no fourth,
        //       because a fourth for the unrecognised answer is the design mistake the reference
        //       forecloses: L484 refuses that answer rather than describing it, so representing it as
        //       a value would let an unrunnable state travel past the point that rejects it.
        /**
         * Confirms the resolved-answer domain and the published outcome domain are both three-valued.
         *
         * <p>Assumptions: both enumerations are asserted in one case because they are two halves of
         * one decision -- the resolver's three answers and the response's three outcomes -- and a
         * migration that widened one without the other would leave a state no client could
         * render.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the answer and outcome domains are three-valued with no arm for a refusal")
        void theAnswerAndOutcomeDomainsAreThreeValued() {
            assertThat(ReportExecutionService.Confirmation.values())
                    .as("app/cbl/CORPT00C.cbl L464, L478 and L480 are the three describable arms;"
                            + " L484 refuses rather than describes")
                    .containsExactly(ReportExecutionService.Confirmation.CONFIRMED,
                            ReportExecutionService.Confirmation.DECLINED,
                            ReportExecutionService.Confirmation.UNANSWERED);
            assertThat(ReportSubmissionOutcome.Outcome.values())
                    .as("the published outcomes mirror the three arms one for one")
                    .hasSize(3);
        }

        // WHY : Assumptions: the two accepting and declining literals are pinned so the resolver's
        //       own constants cannot drift from the arms they transcribe. Both are single characters,
        //       which is what app/cpy-bms/CORPT00.CPY L114 declares for CONFIRMI as PIC X(1) and what
        //       app/bms/CORPT00.bms L209 declares as LENGTH=1.
        /**
         * Confirms the two answer constants are the reference's own single characters.
         *
         * <p>Assumptions: the comparison is against the upper-case spellings alone, because those are
         * the values the resolver publishes; the lower-case acceptances are a property of the
         * comparison the resolver performs and are asserted through the two request cases above
         * rather than as a second pair of constants.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the two answer constants are the reference's own single characters")
        void theAnswerConstantsAreTheReferenceCharacters() {
            assertThat(ReportExecutionService.CONFIRM_YES)
                    .as("app/cbl/CORPT00C.cbl L478").isEqualTo("Y").hasSize(1);
            assertThat(ReportExecutionService.CONFIRM_NO)
                    .as("app/cbl/CORPT00C.cbl L480").isEqualTo("N").hasSize(1);
        }

        // WHY : Refactoring Rationale: neither turn that resets the screen echoes any input back, and
        //       that is the stateless replacement for the reset itself. app/cbl/CORPT00C.cbl performs
        //       INITIALIZE-ALL-FIELDS on the declining arm at L481 and on the success path at L447,
        //       clearing all ten input fields and the message buffer at L635 to L646; a stateless
        //       handler has no screen to clear, so the equivalent property is that it returns no input
        //       state for a client to redisplay.
        /**
         * Confirms neither the accepted nor the cancelled body echoes any request member.
         *
         * <p>Assumptions: all eleven scalar request members are asserted absent from the ROOT of the
         * body rather than a representative one, because the reset the reference performs covers all
         * ten input fields plus its message buffer and a spot check would leave nine of them
         * unpinned. The two consolidated bounds are excluded from the root check because the accepted
         * run legitimately republishes the RESOLVED pair one level down, under the submission
         * member.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("neither reset path echoes a request member back to the caller")
        void neitherResetPathEchoesARequestMember() throws Exception {
            LocalDate lower = LocalDate.of(2022, 7, 1);
            LocalDate upper = LocalDate.of(2022, 7, 31);
            stubConfirmedMonthly(executions, lower, upper);

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest("Y"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.monthly").doesNotExist())
                    .andExpect(jsonPath("$.yearly").doesNotExist())
                    .andExpect(jsonPath("$.custom").doesNotExist())
                    .andExpect(jsonPath("$.confirm").doesNotExist())
                    .andExpect(jsonPath("$.errorMessage").doesNotExist())
                    .andExpect(jsonPath("$.transactionName").doesNotExist())
                    .andExpect(jsonPath("$.programName").doesNotExist())
                    .andExpect(jsonPath("$.title01").doesNotExist())
                    .andExpect(jsonPath("$.title02").doesNotExist())
                    .andExpect(jsonPath("$.currentDate").doesNotExist())
                    .andExpect(jsonPath("$.currentTime").doesNotExist());

            Mockito.reset(executions);
            when(executions.resolveReportName(any()))
                    .thenReturn(ReportExecutionService.MONTHLY_REPORT_NAME);
            when(executions.resolveRange(any(), eq(ReportExecutionService.MONTHLY_REPORT_NAME)))
                    .thenReturn(new ReportExecutionService.DateRange(lower, upper));
            when(executions.resolveConfirmation(any()))
                    .thenReturn(ReportExecutionService.Confirmation.DECLINED);

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest("N"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.monthly").doesNotExist())
                    .andExpect(jsonPath("$.confirm").doesNotExist())
                    .andExpect(jsonPath("$.errorMessage").doesNotExist());
        }

        // WHY : Assumptions: the blank sentence is refused at construction, and the case that proves
        //       it belongs here because it is what makes "no message" unambiguous on this arm. The
        //       reference's own line between absent and present-and-empty is the low-values sentinel
        //       at app/cpy/CVCRD01Y.cpy L30, which attaches to the return carrier alone while the
        //       error carrier at L28 has none.
        /**
         * Confirms a blank sentence is refused rather than normalised, so absent stays distinct.
         *
         * <p>Assumptions: the refusal is an {@link IllegalArgumentException} raised by the compact
         * constructor of {@link ReportSubmissionOutcome} and is asserted directly on that type rather
         * than through a request, because no handler path can construct the value -- which is the
         * point: the state is unreachable by construction and not merely unused.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("a blank sentence is refused, keeping an absent message distinct from an empty one")
        void aBlankSentenceIsRefused() {
            ReportSubmissionResponse run = acceptedRun("2022-07-01", "2022-07-31");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("app/cpy/CVCRD01Y.cpy L30 gives only the return carrier a low-values"
                            + " sentinel, so absence and emptiness are two states")
                    .isThrownBy(() -> ReportSubmissionOutcome.accepted(run, "   "))
                    .withMessageContaining("blank");
        }
    }

    /**
     * Groups the cases that hold each date component individually addressable on the wire.
     *
     * <p>Trade-offs: the reference enters three components per bound and this contract carries one
     * ten-character value per bound, for the reason RC-04 records on this class. What that
     * consolidation must not cost is per-component reporting: the reference distinguishes month from
     * day from year in two separate validation passes and positions the cursor on the component at
     * fault, so a single "the start bound is invalid" entry would be a loss of behaviour rather than a
     * simplification of shape.</p>
     *
     * <p>Alternatives Considered: re-asserting the calendar rules themselves here. Rejected for the
     * reason RC-07 records -- the rules belong to
     * {@link com.carddemo.common.validation.DateEditValidator} and restating them in a handler case
     * would let the two drift apart. What this group asserts is the TRANSPORT of a refusal the shared
     * edit produced, which is the part a handler owns.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on the per-component field errors")
    class OnThePerComponentFieldErrors {

        // WHY : Assumptions: the entry is asserted with its STATE as well as its sentence, because the
        //       reference has two distinguishable failure conditions and the blank one additionally
        //       writes a literal asterisk into the emptied field. Carrying the state is what lets a
        //       client reproduce that marker; carrying only the sentence would render the help text
        //       and lose the marker.
        /**
         * Confirms an empty component is reported on its own identity with the reference's sentence.
         *
         * <p>Assumptions: the refusal is raised by the substituted range resolver rather than
         * constructed by the handler, because the six sentences belong to the resolver's blank chain
         * at {@code app/cbl/CORPT00C.cbl} L258 to L302. What this case owns is that the entry survives
         * the transport with its identity, its state and its text intact.</p>
         *
         * @param field the request-member identity the entry names, spelled as the shared kernel
         *     spells its own per-component identities
         * @param sentence the reference sentence the entry carries, verbatim
         * @param line the {@code app/cbl/CORPT00C.cbl} line that moves that sentence
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest(name = "{0} at L{2}")
        @MethodSource("com.carddemo.reporting.api.ReportControllerTest#emptyComponentFailures")
        @DisplayName("each empty component is reported on its own identity in the blank state")
        void eachEmptyComponentIsReportedIndividually(String field, String sentence, int line)
                throws Exception {

            when(executions.resolveReportName(any()))
                    .thenReturn(ReportExecutionService.CUSTOM_REPORT_NAME);
            when(executions.resolveRange(any(), eq(ReportExecutionService.CUSTOM_REPORT_NAME)))
                    .thenThrow(ClientInputException.ofFieldErrors(ApiError.CODE_VALIDATION,
                            List.of(new ApiError.FieldError(field, FieldValidationFlag.BLANK,
                                    sentence)),
                            sentence));

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(customRequest())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                    .andExpect(jsonPath("$.fieldErrors", org.hamcrest.Matchers.hasSize(1)))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(field))
                    .andExpect(jsonPath("$.fieldErrors[0].state").value("BLANK"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value(sentence));

            assertThat(line)
                    .as("the sentence is the one app/cbl/CORPT00C.cbl moves at this line")
                    .isBetween(261, 296);
        }

        // WHY : Assumptions: this is the reference's SECOND pass over the same six components and it
        //       is asserted separately from the blank pass above. app/cbl/CORPT00C.cbl runs the blank
        //       chain at L258 to L302, then normalises all six components at L305 to L327, then runs
        //       the range chain at L329 to L378 -- so a migration that kept one pass and dropped the
        //       other would still answer every blank input correctly while accepting a month of 13.
        /**
         * Confirms an out-of-range component is reported on its own identity in the not-ok state.
         *
         * <p>Assumptions: the guards behind these six sentences are not the same three tests repeated
         * -- {@code app/cbl/CORPT00C.cbl} tests each month component with
         * {@code IS NOT NUMERIC OR > '12'}, each day component with {@code IS NOT NUMERIC OR > '31'}
         * and each year component with {@code IS NOT NUMERIC} alone -- so the six identities are six
         * distinct conditions and each needs its own row.</p>
         *
         * @param field the request-member identity the entry names
         * @param sentence the reference sentence the entry carries, verbatim
         * @param line the {@code app/cbl/CORPT00C.cbl} line that moves that sentence
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest(name = "{0} at L{2}")
        @MethodSource("com.carddemo.reporting.api.ReportControllerTest#componentRangeFailures")
        @DisplayName("each out-of-range component is reported on its own identity in the not-ok state")
        void eachOutOfRangeComponentIsReportedIndividually(String field, String sentence, int line)
                throws Exception {

            when(executions.resolveReportName(any()))
                    .thenReturn(ReportExecutionService.CUSTOM_REPORT_NAME);
            when(executions.resolveRange(any(), eq(ReportExecutionService.CUSTOM_REPORT_NAME)))
                    .thenThrow(ClientInputException.ofFieldErrors(ApiError.CODE_VALIDATION,
                            List.of(new ApiError.FieldError(field, FieldValidationFlag.NOT_OK,
                                    sentence)),
                            sentence));

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(customRequest())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(field))
                    .andExpect(jsonPath("$.fieldErrors[0].state").value("NOT_OK"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value(sentence));

            assertThat(line)
                    .as("the sentence is the one app/cbl/CORPT00C.cbl moves at this line")
                    .isBetween(331, 374);
        }

        // WHY : Refactoring Rationale: all six entries are asserted in ONE body, and this is the case
        //       that makes the consolidation of RC-04 safe. Six separate rows above each prove one
        //       identity survives; only a body carrying all six proves they are not collapsed into a
        //       single entry naming the whole bound when more than one component fails at once.
        /**
         * Confirms six simultaneous component failures arrive as six entries, not as one.
         *
         * <p>Assumptions: the aggregate sentence is asserted to be the FIRST entry's, because the
         * reference has a single message line and reaches it through a chain whose first matching arm
         * wins -- {@code app/cbl/CORPT00C.cbl} L258 to L302 sends the screen from within the first
         * failing arm, so the operator sees the first failure while every failing field is
         * highlighted.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("six simultaneous component failures arrive as six entries with the first latched")
        void sixSimultaneousComponentFailuresArriveAsSixEntries() throws Exception {
            List<ApiError.FieldError> entries = List.of(
                    new ApiError.FieldError("startDateMonth", FieldValidationFlag.BLANK,
                            "Start Date - Month can NOT be empty..."),
                    new ApiError.FieldError("startDateDay", FieldValidationFlag.BLANK,
                            "Start Date - Day can NOT be empty..."),
                    new ApiError.FieldError("startDateYear", FieldValidationFlag.BLANK,
                            "Start Date - Year can NOT be empty..."),
                    new ApiError.FieldError("endDateMonth", FieldValidationFlag.BLANK,
                            "End Date - Month can NOT be empty..."),
                    new ApiError.FieldError("endDateDay", FieldValidationFlag.BLANK,
                            "End Date - Day can NOT be empty..."),
                    new ApiError.FieldError("endDateYear", FieldValidationFlag.BLANK,
                            "End Date - Year can NOT be empty..."));

            when(executions.resolveReportName(any()))
                    .thenReturn(ReportExecutionService.CUSTOM_REPORT_NAME);
            when(executions.resolveRange(any(), eq(ReportExecutionService.CUSTOM_REPORT_NAME)))
                    .thenThrow(ClientInputException.ofFieldErrors(ApiError.CODE_VALIDATION, entries,
                            entries.get(0).message()));

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(customRequest())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors", org.hamcrest.Matchers.hasSize(6)))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("startDateMonth"))
                    .andExpect(jsonPath("$.fieldErrors[1].field").value("startDateDay"))
                    .andExpect(jsonPath("$.fieldErrors[2].field").value("startDateYear"))
                    .andExpect(jsonPath("$.fieldErrors[3].field").value("endDateMonth"))
                    .andExpect(jsonPath("$.fieldErrors[4].field").value("endDateDay"))
                    .andExpect(jsonPath("$.fieldErrors[5].field").value("endDateYear"))
                    .andExpect(jsonPath("$.fieldErrors[5].message")
                            .value("End Date - Year can NOT be empty..."))
                    .andExpect(jsonPath("$.message")
                            .value("Start Date - Month can NOT be empty..."));
        }

        // WHY : Refactoring Rationale: the FIRST submission carries the whole array, and the reference
        //       would have highlighted nothing on a first entry -- it gates the highlight on the
        //       re-entry discriminator CDEMO-PGM-CONTEXT, which app/cbl/CORPT00C.cbl clears at L547
        //       before transferring control. A stateless handler keeps no turn count, so the divergence
        //       is intended and documented rather than reproduced.
        /**
         * Confirms a first submission is answered with the whole field-error array.
         *
         * <p>Assumptions: no request or response member of this contract carries a turn count, and
         * that is asserted structurally by pinning the request to exactly the thirteen members the
         * screen accounts for. Naming a discriminator in order to assert its absence would put the
         * very vocabulary this migration removed back into the repository.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a first submission is answered with the whole field-error array")
        void aFirstSubmissionCarriesTheWholeArray() throws Exception {
            when(executions.resolveReportName(any()))
                    .thenReturn(ReportExecutionService.CUSTOM_REPORT_NAME);
            when(executions.resolveRange(any(), eq(ReportExecutionService.CUSTOM_REPORT_NAME)))
                    .thenThrow(ClientInputException.ofFieldErrors(ApiError.CODE_VALIDATION,
                            List.of(new ApiError.FieldError("startDateMonth",
                                            FieldValidationFlag.BLANK,
                                            "Start Date - Month can NOT be empty..."),
                                    new ApiError.FieldError("endDateYear",
                                            FieldValidationFlag.NOT_OK,
                                            "End Date - Not a valid Year...")),
                            "Start Date - Month can NOT be empty..."));

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(customRequest())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors", org.hamcrest.Matchers.hasSize(2)));

            assertThat(componentNames(ReportRequest.class))
                    .as("the thirteen members are the 17 named screen fields of"
                            + " app/bms/CORPT00.bms with each three-part bound consolidated, and"
                            + " nothing else")
                    .containsExactly("transactionName", "title01", "currentDate", "programName",
                            "title02", "currentTime", "monthly", "yearly", "custom", "startDate",
                            "endDate", "confirm", "errorMessage");
        }

        // WHY : Assumptions: the delegated refusal names the BOUND and not one of its three
        //       components, and that is the shipped analogue of where the reference puts the cursor.
        //       app/cbl/CORPT00C.cbl positions on SDTMML at L403 and on EDTMML at L423 -- the MONTH
        //       subfield of the failing bound rather than the component at fault -- and the wire
        //       carries one value per bound, so the bound's own identity is the single addressable
        //       position that assembled-date failure has.
        /**
         * Confirms a delegated assembled-date refusal names the bound it concerns.
         *
         * <p>Assumptions: the two sentences carry a lower-case noun where the twelve per-component
         * ones capitalise theirs, at {@code app/cbl/CORPT00C.cbl} L400 and L420, and the difference is
         * carried rather than harmonised.</p>
         *
         * <p>Assumptions: the delegated edit's refusal arrives as a {@link ClientInputException} naming the
         * bound, converted by {@link GlobalExceptionHandler} into the 400 problem body. The exception
         * type is the shared kernel's, which is what keeps the two bounds' refusals identically shaped.</p>
         *
         * @param field the bound the refusal names, either of the two consolidated members
         * @param sentence the reference sentence for that bound, verbatim
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "startDate,Start Date - Not a valid date...",
            "endDate,End Date - Not a valid date..."
        })
        @DisplayName("a delegated assembled-date refusal names the bound it concerns")
        void aDelegatedRefusalNamesTheBound(String field, String sentence) throws Exception {
            when(executions.resolveReportName(any()))
                    .thenReturn(ReportExecutionService.CUSTOM_REPORT_NAME);
            when(executions.resolveRange(any(), eq(ReportExecutionService.CUSTOM_REPORT_NAME)))
                    .thenThrow(new ClientInputException(ApiError.CODE_VALIDATION, field,
                            FieldValidationFlag.NOT_OK, sentence));

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(customRequest())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(field))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value(sentence))
                    .andExpect(jsonPath("$.message").value(sentence));

            verify(executions, never()).start(any(), any(), any(), any(), any());
        }

        // WHY : Assumptions: a bound the shared edit TOLERATES is submitted, and this case is the one
        //       that proves the handler does not second-guess it. app/cbl/CORPT00C.cbl accepts a zero
        //       severity at L396 and L416 and additionally forgives a non-zero severity whose message
        //       number is 2513 at L399 and L419, so a bound outside the supported calendar range still
        //       runs. A handler carrying a calendar test of its own would refuse it and no other case
        //       here would notice.
        /**
         * Confirms a bound the shared edit tolerates reaches the orchestration with no field error.
         *
         * <p>Assumptions: the tolerated condition is named through the shared edit's own constant
         * rather than as the bare number, so the one place the number is declared stays the only place
         * it appears. The bound chosen is before the supported calendar floor, which is the condition
         * that constant describes.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a bound the shared edit tolerates is submitted with no field error")
        void aToleratedBoundIsSubmittedWithNoFieldError() throws Exception {
            LocalDate lower = LocalDate.of(1500, 6, 15);
            LocalDate upper = LocalDate.of(1500, 6, 30);

            when(executions.resolveReportName(any()))
                    .thenReturn(ReportExecutionService.CUSTOM_REPORT_NAME);
            when(executions.resolveRange(any(), eq(ReportExecutionService.CUSTOM_REPORT_NAME)))
                    .thenReturn(new ReportExecutionService.DateRange(lower, upper));
            when(executions.resolveConfirmation(any()))
                    .thenReturn(ReportExecutionService.Confirmation.CONFIRMED);
            when(executions.start(any(), eq(ReportExecutionService.CUSTOM_REPORT_NAME), eq(lower),
                    eq(upper), any()))
                    .thenReturn(new ReportSubmissionResponse(EXECUTION_NAME,
                            ReportExecutionService.CUSTOM_REPORT_NAME,
                            ReportBandLayouts.REPORT_SHORT_NAME,
                            ReportBandLayouts.REPORT_LONG_NAME,
                            "1500-06-15", "1500-06-30", SUBMITTED_AT));

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(
                                    customRequest("1500-06-15", "1500-06-30"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.fieldErrors").doesNotExist())
                    .andExpect(jsonPath("$.submission.startDate").value("1500-06-15"));

            assertThat(DateEditValidator.MSG_NO_UNSUPP_RANGE)
                    .as("app/cbl/CORPT00C.cbl L399 and L419 forgive exactly this message number")
                    .isEqualTo(2513);
            verify(executions).start(any(), eq(ReportExecutionService.CUSTOM_REPORT_NAME), eq(lower),
                    eq(upper), any());
        }

        // WHY : Assumptions: the reference's linkage record is asserted arithmetically rather than
        //       restated, so the padding drop is visible as arithmetic instead of as a claim. The four
        //       items at app/cbl/CORPT00C.cbl L133 to L136 are a 4-character severity, 11 characters
        //       of FILLER, a 4-character message number and a 61-character message, and their widths
        //       sum to the whole declared result group.
        /**
         * Confirms the shared edit's result group is the reference's eighty bytes with the padding
         * dropped.
         *
         * <p>Assumptions: the severity and the message number stay OPERATOR-facing while the
         * published sentence is what reaches a caller. That is the shipped division and it follows the
         * reference: {@code app/cbl/CORPT00C.cbl} tests both values at L396 and L399 for the start
         * bound and at L416 and L419 for the end bound but shows neither to the operator, moving
         * {@code 'Start Date - Not a valid date...'} at L400 or its end-bound twin at L420 instead.
         * The caller-facing entry therefore carries three members and none of them is padding.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the shared edit's result group is eighty bytes with the eleven of padding dropped")
        void theSharedEditResultGroupDropsItsPadding() {
            int severity = 4;
            int padding = 11;
            int messageNumber = 4;
            int message = 61;

            assertThat(severity + padding + messageNumber + message)
                    .as("app/cbl/CORPT00C.cbl L133 to L136 declare the whole CSUTLDTC-RESULT group")
                    .isEqualTo(DateEditValidator.RESULT_LENGTH)
                    .isEqualTo(80);
            assertThat(severity + messageNumber + message)
                    .as("the eleven characters of FILLER at L134 are padding rather than data, so"
                            + " the triple that travels is 69 of the 80")
                    .isEqualTo(69);
            assertThat(componentNames(ApiError.FieldError.class))
                    .as("the caller-facing entry carries an identity, a state and a sentence, and no"
                            + " member for the padding")
                    .containsExactly("field", "state", "message");
            assertThat(DateEditValidator.SEVERITY_VALID)
                    .as("app/cbl/CORPT00C.cbl L396 and L416 accept the zero severity")
                    .isZero();
        }

        // WHY : Assumptions: the zero-padding the reference performs at app/cbl/CORPT00C.cbl L305 to
        //       L327 becomes a PRECONDITION of the wire rather than a step the handler runs. Each
        //       COMPUTE FUNCTION NUMVAL-C there rewrites a component in place, so a single digit typed
        //       into a two-position field becomes two digits before the range chain reads it; the
        //       consolidated bound admits only the already-padded ten-character form, so an unpadded
        //       value is refused at the boundary instead of being silently widened.
        /**
         * Confirms an unpadded bound is refused rather than widened to the padded form.
         *
         * <p>Assumptions: refusal is the safe direction of the two. Widening the value here would
         * accept a bound whose intended month and day a caller could not verify from the echoed
         * response, whereas refusing it names the member and leaves the caller to send the form the
         * mask at {@code app/cbl/CORPT00C.cbl} L72 declares.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an unpadded bound is refused rather than widened to the padded form")
        void anUnpaddedBoundIsRefused() throws Exception {
            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"custom\":\"" + MARK + "\",\"startDate\":\"2022-7-1\","
                                    + "\"endDate\":\"2022-07-31\",\"confirm\":\"Y\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("startDate"));

            verify(executions, never()).start(any(), any(), any(), any(), any());
        }
    }

    /**
     * Groups the cases that hold the two padding states apart while reading both as unset.
     *
     * <p>Assumptions: both states genuinely occur on this screen, for the reason RC-06 records on this
     * class -- the three selectors arrive as spaces because {@code app/bms/CORPT00.bms} gives each an
     * {@code INITIAL=' '} at L85, L99 and L113, while the confirmation arrives as low values because
     * {@code CONFIRM} at L206 carries no {@code INITIAL} clause at all. That is why the reference
     * tests both padding characters rather than one.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on the two padding states of a selector")
    class OnTheSelectorEmptyStates {

        // WHY : Assumptions: the padded body is sent as RAW JSON and not through the request record,
        //       because that record normalises a padded value to absent at construction -- so a body
        //       serialised from it would carry the absent form and the case would assert the omitted
        //       state twice while claiming to assert two. Only raw text puts a space on the wire.
        /**
         * Confirms a space-padded selector and an omitted selector are both read as unset.
         *
         * <p>Assumptions: the assertion is made on the value the resolver RECEIVED as well as on the
         * refusal, because the property is that the two arrive at the same reading. Asserting the
         * refusal alone would pass for a boundary that refused the padded body for being padded, which
         * is a different behaviour reaching the same status.</p>
         *
         * <p>Assumptions: an unset selector is refused by the resolver with a {@link ClientInputException},
         * which {@link GlobalExceptionHandler} converts into the 400 problem body. Both padding states
         * reach that same refusal, which is precisely what makes them one state to this surface.</p>
         *
         * @param body the request body to send, one carrying a single space in the monthly selector
         *     and one omitting the member entirely
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest(name = "body={0}")
        @ValueSource(strings = {"{\"monthly\":\" \",\"confirm\":\"N\"}", "{\"confirm\":\"N\"}"})
        @DisplayName("a space-padded selector and an omitted selector are both read as unset")
        void bothPaddingStatesAreReadAsUnset(String body) throws Exception {
            when(executions.resolveReportName(any())).thenThrow(new ClientInputException(
                    ApiError.CODE_VALIDATION, "reportType",
                    ReportExecutionService.MESSAGE_NO_REPORT_TYPE_SELECTED));

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message")
                            .value("Select a report type to print report..."));

            ArgumentCaptor<ReportRequest> received = ArgumentCaptor.forClass(ReportRequest.class);
            verify(executions).resolveReportName(received.capture());
            assertThat(received.getValue().monthly())
                    .as("app/cbl/CORPT00C.cbl L213 reads NOT = SPACES AND LOW-VALUES, so a padded"
                            + " selector and an omitted one are one reading")
                    .isNull();
        }

        // WHY : Assumptions: the two padding characters are asserted DISTINGUISHABLE even though both
        //       read as unset, because collapsing them is the mistake app/cpy/CVCRD01Y.cpy L30 guards
        //       against -- only the return carrier bears the low-values sentinel and the error carrier
        //       at L28 bears none, so the two bytes carry different meanings elsewhere in the same
        //       structure and a shared kernel that folded them would lose that.
        /**
         * Confirms the shared kernel keeps the two padding characters apart while reading both as
         * absent.
         *
         * <p>Assumptions: the mixed value is the case that proves they are two tests rather than one
         * whitespace test. The shared reading examines the value against each padding character across
         * all positions separately, so a value made of one followed by the other satisfies neither arm
         * and is reported present -- which is the behaviour the record's own normalisation depends on
         * when it strips trailing spaces before asking.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the two padding characters stay distinguishable while both read as absent")
        void theTwoPaddingCharactersStayDistinguishable() {
            assertThat(FieldValidationFlag.ABSENT_INPUT_SPACE)
                    .as("the byte a screen returns for an untouched box")
                    .isNotEqualTo(FieldValidationFlag.ABSENT_INPUT_LOW_VALUE);
            assertThat(FieldValidationFlag.isNeverSupplied(
                    String.valueOf(FieldValidationFlag.ABSENT_INPUT_SPACE))).isTrue();
            assertThat(FieldValidationFlag.isNeverSupplied(
                    String.valueOf(FieldValidationFlag.ABSENT_INPUT_LOW_VALUE))).isTrue();
            assertThat(FieldValidationFlag.isNeverSupplied(
                    String.valueOf(FieldValidationFlag.ABSENT_INPUT_LOW_VALUE)
                            + FieldValidationFlag.ABSENT_INPUT_SPACE))
                    .as("a mixture satisfies neither arm, which is what keeps the two tests two")
                    .isFalse();
        }

        // WHY : Assumptions: the handler is asserted to resolve the type EXACTLY ONCE and to report
        //       whatever came back, because the precedence among the three selectors belongs to the
        //       resolver. app/cbl/CORPT00C.cbl L212 opens one EVALUATE TRUE whose arms at L213, L239
        //       and L256 are tested in that order and whose first match wins, so a body marking two
        //       types resolves to the earlier one; a handler that re-derived the type would decide that
        //       ordering a second time and the two decisions could disagree.
        /**
         * Confirms a body marking two selectors is resolved once, by the resolver, in its own order.
         *
         * <p>Assumptions: the report name is asserted on the wire through the acceptance sentence
         * rather than through a captured argument, because the sentence is what a caller sees and it
         * interpolates the resolved name -- so a handler that resolved correctly and then rendered a
         * different name would fail here.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("two marked selectors are resolved once, in the reference's own arm order")
        void twoMarkedSelectorsAreResolvedOnce() throws Exception {
            LocalDate lower = LocalDate.of(2022, 8, 1);
            LocalDate upper = LocalDate.of(2022, 8, 31);
            stubConfirmedMonthly(executions, lower, upper);

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"monthly\":\"" + MARK + "\",\"yearly\":\"" + MARK
                                    + "\",\"confirm\":\"Y\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message")
                            .value("Monthly" + ReportController.SUBMITTED_SUFFIX));

            verify(executions).resolveReportName(any());
        }
    }

    /**
     * Groups the cases that hold the subtotal bands to the three the reference prints.
     *
     * <p>Assumptions: the bands are a report structure rather than a paging structure. The detail
     * lines are paged by key and the bands cover the whole range, which is why the two are separate
     * operations, and the word "page" in a band label names a printed page of the reference report
     * rather than a window of the listing.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on the subtotal bands")
    class OnTheTotalsBands {

        // WHY : Assumptions: all three bands are asserted in ONE body with their labels, because the
        //       label is not free text -- the band type constructs with its own label and refuses any
        //       other -- so the assertion that matters is that the three published labels are the
        //       three the report prints and that none has been re-worded.
        // WHY : Assumptions: the three labels are the reference's own literals -- 'Page Total' at
        //       app/cpy/CVTRA07Y.cpy L52, 'Account Total' at L58 and 'Grand Total' at L64 -- each declared
        //       beside an amount under the edited mask at L54, L60 and L66.
        /**
         * Confirms the three bands reach the wire with their verbatim labels and quoted amounts.
         *
         * <p>Assumptions: each amount is asserted as a quoted string and not merely as the right
         * digits. A component holding an exact value under a decimal type compiles, runs and emits a
         * bare JSON number that looks entirely correct; only the quoting shows the difference, and the
         * difference is what stops a client parsing an amount into IEEE-754 binary floating point.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the three bands reach the wire with verbatim labels and quoted amounts")
        void theThreeBandsReachTheWireWithVerbatimLabels() throws Exception {
            when(reports.composeTotals(LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31)))
                    .thenReturn(List.of(
                            new ReportTotalsResponse(ReportTotalsResponse.Band.PAGE, "Page Total",
                                    Money.of("10.00")),
                            new ReportTotalsResponse(ReportTotalsResponse.Band.ACCOUNT,
                                    "Account Total", Money.of("-20.50")),
                            new ReportTotalsResponse(ReportTotalsResponse.Band.GRAND, "Grand Total",
                                    Money.of("-10.50"))));

            mockMvc.perform(get(ReportController.BASE_PATH + ReportController.TOTALS_PATH)
                            .param("startDate", "2022-07-01")
                            .param("endDate", "2022-07-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bands", org.hamcrest.Matchers.hasSize(3)))
                    .andExpect(jsonPath("$.bands[0].band").value("PAGE"))
                    .andExpect(jsonPath("$.bands[0].label").value("Page Total"))
                    .andExpect(jsonPath("$.bands[0].amount").value("10.00"))
                    .andExpect(jsonPath("$.bands[1].band").value("ACCOUNT"))
                    .andExpect(jsonPath("$.bands[1].label").value("Account Total"))
                    .andExpect(jsonPath("$.bands[1].amount").value("-20.50"))
                    .andExpect(jsonPath("$.bands[2].band").value("GRAND"))
                    .andExpect(jsonPath("$.bands[2].label").value("Grand Total"))
                    .andExpect(jsonPath("$.bands[2].amount").value("-10.50"));
        }

        // WHY : Assumptions: the domain is asserted to have exactly three members, because a fourth
        //       band would be a report structure the reference does not print and a client rendering
        //       the enumeration would have nothing to show for it.
        // WHY : Assumptions: the reference declares exactly three total groups -- app/cpy/CVTRA07Y.cpy
        //       L50, L56 and L62 -- so a fourth band would have no line in the produced report of
        //       app/cbl/CBTRN03C.cbl to correspond to.
        /**
         * Confirms the band domain holds exactly the three the report prints, each with its own label.
         *
         * <p>Assumptions: the labels are read from the enumeration's own accessor and compared to
         * literals, so the enumeration is held to the printed wording rather than to itself.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the band domain holds exactly three members with their printed labels")
        void theBandDomainHoldsExactlyThreeMembers() {
            assertThat(ReportTotalsResponse.Band.values())
                    .containsExactly(ReportTotalsResponse.Band.PAGE,
                            ReportTotalsResponse.Band.ACCOUNT,
                            ReportTotalsResponse.Band.GRAND);
            assertThat(ReportTotalsResponse.Band.PAGE.reportLabel()).isEqualTo("Page Total");
            assertThat(ReportTotalsResponse.Band.ACCOUNT.reportLabel()).isEqualTo("Account Total");
            assertThat(ReportTotalsResponse.Band.GRAND.reportLabel()).isEqualTo("Grand Total");
        }
    }

    /**
     * Groups the cases that hold the accepted-run description and its failure answer.
     *
     * <p>Refactoring Rationale: a submission the orchestration refuses answers loudly, for the reason
     * RC-10 records on this class. The reference has a sentence ready for a failed write at
     * {@code app/cbl/CORPT00C.cbl} L531, but the queue it writes to is defined with
     * {@code ERROROPTION(IGNORE)} at {@code app/csd/CARDDEMO.CSD} L501, so the platform could absorb
     * the write and leave the operator looking at a success. A silent success is the one answer this
     * group forecloses.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on the accepted-run description")
    class OnTheSubmissionHandle {

        // WHY : Assumptions: the timestamp is asserted OPAQUE -- a width and a separator -- rather
        //       than parsed. Its contract on this record is the width alone, because an all-blank
        //       value of that width is legitimate reference data, so a parse test would refuse a value
        //       the reference admits while a width test still catches one rendered without its
        //       fractional part.
        // WHY : Refactoring Rationale: the reference's clock read at app/cbl/CORPT00C.cbl L611 is
        //       PRESENTATIONAL only -- it formats the screen header fields at L613 to L628, a two-digit
        //       year among them -- so the target forms its acceptance stamp once at the width the shared
        //       formatter declares and carries it opaquely rather than reformatting it downstream.
        /**
         * Confirms the acceptance timestamp crosses the wire at its declared width, unaltered.
         *
         * <p>Assumptions: the separator is asserted to be a space and not the letter that an ISO
         * instant uses, because the shared formatter emits the reference's own form -- year, month and
         * day, one space, then the time to six fractional digits -- and a handler that re-rendered the
         * value through an instant formatter would change the separator while keeping the width.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the acceptance timestamp crosses the wire at its declared width, unaltered")
        void theAcceptanceTimestampCrossesTheWireUnaltered() throws Exception {
            LocalDate lower = LocalDate.of(2022, 7, 1);
            LocalDate upper = LocalDate.of(2022, 7, 31);
            stubConfirmedMonthly(executions, lower, upper);

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest("Y"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.submission.submittedAt").value(SUBMITTED_AT));

            assertThat(SUBMITTED_AT)
                    .as("the shared formatter's declared width, which the response record enforces")
                    .hasSize(TimestampFormatter.TIMESTAMP_LENGTH)
                    .hasSize(26);
            assertThat(SUBMITTED_AT.charAt(10))
                    .as("a space separates the date from the time, never the letter an instant uses")
                    .isEqualTo(' ');
        }

        // WHY : Assumptions: the two header names are pinned to their copybook literals rather than to
        //       the constants alone, because they are printed on every page of the produced report --
        //       so a re-cased or re-spaced value would change the report's own heading and no width
        //       assertion would notice.
        /**
         * Confirms the two report header names are the reference literals within their declared
         * widths.
         *
         * <p>Assumptions: each literal is shorter than the field that holds it --
         * {@code REPT-SHORT-NAME PIC X(38)} at {@code app/cpy/CVTRA07Y.cpy} L5 holds an eight-character
         * value declared on its L6, and {@code REPT-LONG-NAME PIC X(41)} at L7 holds a
         * twenty-four-character value declared on its L8 -- so the literal and the declared width are
         * two separate contracts and the padding is the codec's business rather than the value's.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("the two report header names are the reference literals within their widths")
        void theReportHeaderNamesAreTheReferenceLiterals() throws Exception {
            LocalDate lower = LocalDate.of(2022, 7, 1);
            LocalDate upper = LocalDate.of(2022, 7, 31);
            stubConfirmedMonthly(executions, lower, upper);

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest("Y"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.submission.shortName").value("DALYREPT"))
                    .andExpect(jsonPath("$.submission.longName")
                            .value("Daily Transaction Report"));

            assertThat(ReportBandLayouts.REPORT_SHORT_NAME)
                    .as("app/cpy/CVTRA07Y.cpy L6, the VALUE of the PIC X(38) item declared on L5")
                    .isEqualTo("DALYREPT")
                    .hasSizeLessThanOrEqualTo(38);
            assertThat(ReportBandLayouts.REPORT_LONG_NAME)
                    .as("app/cpy/CVTRA07Y.cpy L8, the VALUE of the PIC X(41) item declared on L7")
                    .isEqualTo("Daily Transaction Report")
                    .hasSizeLessThanOrEqualTo(41);
        }

        // WHY : Refactoring Rationale: a refused start surfaces as a server fault and NOT as a
        //       success, which is the one behaviour the reference platform could not guarantee. Its
        //       queue is defined ERROROPTION(IGNORE) at app/csd/CARDDEMO.CSD L501, so a failed write
        //       could be absorbed and the sentence at app/cbl/CORPT00C.cbl L531 never reached; the
        //       target raises instead, and the divergence is documented.
        /**
         * Confirms a refused start answers as a fault and carries no accepted run.
         *
         * <p>Assumptions: the failure raised by the substituted orchestration is an
         * {@link IllegalStateException}, which is the type its own contract declares for a refused
         * start, and the shared advice answers it as an internal fault rather than as caller input --
         * the caller's request was well formed and the orchestration declined it. The success members
         * are asserted absent as well as the status, because a body carrying an accepted run beside a
         * fault status would be read as a success by any client that checks the body first.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a refused start answers as a fault and carries no accepted run")
        void aRefusedStartAnswersAsAFault() throws Exception {
            LocalDate lower = LocalDate.of(2022, 7, 1);
            LocalDate upper = LocalDate.of(2022, 7, 31);
            when(executions.resolveReportName(any()))
                    .thenReturn(ReportExecutionService.MONTHLY_REPORT_NAME);
            when(executions.resolveRange(any(), eq(ReportExecutionService.MONTHLY_REPORT_NAME)))
                    .thenReturn(new ReportExecutionService.DateRange(lower, upper));
            when(executions.resolveConfirmation(any()))
                    .thenReturn(ReportExecutionService.Confirmation.CONFIRMED);
            when(executions.start(any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException(
                            "the orchestration refused to start the run"));

            mockMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest("Y"))))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_INTERNAL))
                    .andExpect(jsonPath("$.outcome").doesNotExist())
                    .andExpect(jsonPath("$.submission").doesNotExist());
        }

        // WHY : Assumptions: the posture is asserted STRUCTURALLY, on the record components, because
        //       absence from one rendered body could be a stubbing accident whereas absence from the
        //       record is the contract. The published document's own masking rules are asserted by
        //       ReportingApiContractTest beside this class and are not restated here.
        // WHY : Assumptions: the card number is declared as characters over digits at
        //       app/cpy/CVCRD01Y.cpy L37 and L39, and this screen declares no card field whatever --
        //       app/bms/CORPT00.bms names seventeen fields and not one of them is a card field -- so no
        //       response of this operation has a member for either value to occupy.
        /**
         * Confirms no response of this surface declares a card number or a verification value.
         *
         * <p>Assumptions: the report line carries an account identifier and no card identifier at all,
         * which is stronger than masking one: a member that does not exist cannot be rendered
         * unmasked by a later change to a serialiser. The verification value is likewise absent from
         * every response this surface publishes.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("no response of this surface declares a card number or a verification value")
        void noResponseDeclaresACardNumberOrVerificationValue() {
            List<String> withheld = List.of("cardNumber", "cardNum", "cvv",
                    "cardVerificationValue", "pan");

            assertThat(componentNames(TransactionReportLineResponse.class))
                    .as("the report line reports an account and its reference dimensions, nothing"
                            + " that identifies a card")
                    .doesNotContainAnyElementsOf(withheld)
                    .contains("accountId");
            assertThat(componentNames(ReportTotalsResponse.class))
                    .doesNotContainAnyElementsOf(withheld);
            assertThat(componentNames(ReportSubmissionResponse.class))
                    .doesNotContainAnyElementsOf(withheld);
        }
    }

    /**
     * Groups the cases that answer an action this surface does not offer.
     *
     * <p>Assumptions: the reference offers exactly two actions on this screen. {@code MAIN-PARA} at
     * {@code app/cbl/CORPT00C.cbl} L161 to L200 dispatches the enter key to the validation paragraph
     * and the third function key to the previous screen, and its {@code WHEN OTHER} arm at L190 to
     * L194 answers everything else with the copybook sentence whose MOVE sits at L193. The screen says
     * as much itself: {@code INITIAL='ENTER=Continue  F3=Back'} at {@code app/bms/CORPT00.bms} L226,
     * with two spaces between the two legends.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on an action this surface does not offer")
    class OnTheUnsupportedAction {

        // WHY : Assumptions: the transport analogue of the reference's unsupported-key arm is a method
        //       refusal, and it is asserted to carry the shared problem shape rather than a bodyless
        //       status. The reference answers an unsupported action with a SENTENCE on the message
        //       line, so an answer with no body at all would lose the one thing that arm produces.
        // WHY : Assumptions: the reference offers exactly two actions on this screen --
        //       app/cbl/CORPT00C.cbl L185 and L187 -- and answers anything else at L190 to L193 with the
        //       constant declared at app/cpy/CSMSG01Y.cpy L20 to L21. An unoffered method is the target's
        //       analogue of an unoffered attention identifier.
        /**
         * Confirms a method this surface does not offer is refused with the shared problem shape.
         *
         * <p>Assumptions: the submission address is chosen for the case because it is the one address
         * of this surface that admits a mutating method at all, so a refusal there is a refusal of the
         * METHOD and not of the address.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("a method this surface does not offer is refused with the shared problem shape")
        void anUnofferedMethodIsRefusedWithTheSharedProblemShape() throws Exception {
            mockMvc.perform(delete(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value(ApiError.CODE_METHOD_NOT_ALLOWED));

            verify(executions, never()).resolveReportName(any());
            verify(executions, never()).start(any(), any(), any(), any(), any());
        }
    }

    /**
     * Assembles a web context carrying the DEPLOYED filter chain in front of this handler.
     *
     * <p>Alternatives Considered: substituting the chain's authorization decision and installing it by
     * hand in the standalone pipeline the rest of this class uses. Rejected because the decision is
     * only half of the guard -- the order of the rules, the session policy and the refusal renderers
     * are the other half, and a hand-installed decision would assert the half that is easiest to get
     * right. Registering the deployed configuration means the group answers for the chain a deployment
     * runs rather than for a reconstruction of it.</p>
     *
     * <p>Alternatives Considered: registering the module's token-decoder configuration so the chain
     * resolves a decoder the way a deployment does. Rejected for the reason RC-11 records on this
     * class: that class builds its decoder eagerly from the configured issuer's discovery document, so
     * registering it would reach the network from a unit case. A substituted decoder is supplied
     * instead, and no case here presents a token header at all.</p>
     *
     * <p>Assumptions: the two group names are passed to the deployed converter factory rather than
     * spelled here. That factory compares what it is given against its own compiled constants and
     * refuses to start on a mismatch, which is what keeps this slice's authority derivation identical
     * to a deployment's rather than merely similar to it.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class SecuredSliceWiring {

        /**
         * Supplies the pinned clock the chain's refusal renderers stamp their bodies from.
         *
         * @return a clock pinned to the instant this class holds still; never {@code null}
         */
        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }

        /**
         * Supplies the substituted decoder the resource-server filter would resolve a token with.
         *
         * @return a substitute for the token decoder, with no stubbing applied; never {@code null}
         */
        @Bean
        JwtDecoder jwtDecoder() {
            return Mockito.mock(JwtDecoder.class);
        }

        /**
         * Supplies the deployed claim-to-authority converter, both group names included.
         *
         * @return the converter the deployed configuration builds; never {@code null}
         */
        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
            return new SecurityConfig().jwtAuthenticationConverter(
                    JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);
        }

        /**
         * Supplies the deployed chain, rule order, refusal renderers and session policy included.
         *
         * @param http the chain builder this context contributes; must not be {@code null}
         * @param converter the deployed claim-to-authority converter; must not be {@code null}
         * @param clock the clock the rendered refusal bodies read their instant from; must not be
         *     {@code null}
         * @return the chain the deployed configuration builds; never {@code null}
         * @throws Exception when the builder cannot assemble the chain, which it declares
         */
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter,
                Clock clock) throws Exception {
            return new SecurityConfig().filterChain(http, converter, clock);
        }

        /**
         * Supplies the substituted orchestration the handler resolves and starts runs through.
         *
         * @return a substitute for the orchestration, with no stubbing applied; never {@code null}
         */
        @Bean
        ReportExecutionService securedExecutions() {
            return Mockito.mock(ReportExecutionService.class);
        }

        /**
         * Supplies the substituted report composer the listing and totals routes read through.
         *
         * @return a substitute for the report composer, with no stubbing applied; never {@code null}
         */
        @Bean
        TransactionReportService securedReports() {
            return Mockito.mock(TransactionReportService.class);
        }

        /**
         * Supplies the substituted artifact store the collection route opens through.
         *
         * @return a substitute for the artifact store, with no stubbing applied; never {@code null}
         */
        @Bean
        ArtifactStore securedArtifacts() {
            return Mockito.mock(ArtifactStore.class);
        }

        /**
         * Supplies the handler under test, wired to the substitutes above.
         *
         * @param executions the substituted orchestration; must not be {@code null}
         * @param reports the substituted report composer; must not be {@code null}
         * @param artifacts the substituted artifact store; must not be {@code null}
         * @return the handler this group issues its requests against; never {@code null}
         */
        @Bean
        ReportController reportController(ReportExecutionService executions,
                TransactionReportService reports, ArtifactStore artifacts) {
            return new ReportController(executions, reports,
                    new CursorToken(CURSOR_KEY, CURSOR_LIFETIME),
                    new ReportArtifactLocator(REPORT_PREFIX), artifacts);
        }

        /**
         * Supplies the shared advice, so a refusal raised past the chain renders its published shape.
         *
         * @param clock the clock the rendered bodies read their instant from; must not be
         *     {@code null}
         * @return the shared advice the deployed context registers; never {@code null}
         */
        @Bean
        GlobalExceptionHandler globalExceptionHandler(Clock clock) {
            return new GlobalExceptionHandler(clock);
        }
    }

    /**
     * Groups the cases that hold this surface behind the deployed group-claim guard.
     *
     * <p>Assumptions: identity arrives as validated claims and selection context arrives in the
     * request, so there is nothing for the handler to trust that a caller supplied about itself. The
     * two group authorities are the migration of the two user types the reference stores -- the values
     * {@code 'A'} and {@code 'U'} -- and the chain admits either on a business address while admitting
     * nothing else.</p>
     *
     * <p>Trade-offs: this group refreshes a web context per case, which is slower than the standalone
     * pipeline the rest of this class uses. The cost is accepted because a refusal status is produced
     * by the chain and not by the handler, so a standalone pipeline cannot observe it at all.</p>
     *
     * <p>Of the four content elements the explainability rule enumerates, only Purpose applies to a
     * type declaration, so this block carries no parameter, return or exception tag.</p>
     */
    @Nested
    @DisplayName("on the group-claim guard")
    class OnTheGroupClaimGuard {

        /** The refreshed context the deployed chain and the handler are built in. */
        private AnnotationConfigWebApplicationContext securedContext;

        /** The entry point every request in this group is issued through. */
        private MockMvc securedMvc;

        /** The substituted orchestration the admitted cases stub. */
        private ReportExecutionService securedExecutions;

        /** The substituted report composer the admitted cases stub. */
        private TransactionReportService securedReports;

        /**
         * Refreshes the secured context and installs the deployed chain in front of the dispatcher.
         *
         * <p>Assumptions: the security configurer is applied rather than the chain bean being added as
         * a plain filter, because the configurer is what lets a case establish an authentication
         * through a request post-processor instead of presenting a signed token. No case here has a
         * token to present, since the decoder is substituted.</p>
         *
         * <p>This method takes no parameter and yields no value.</p>
         */
        @BeforeEach
        void refreshSecuredSlice() {
            securedContext = new AnnotationConfigWebApplicationContext();
            securedContext.setServletContext(new MockServletContext());
            securedContext.register(SecuredSliceWiring.class);
            securedContext.refresh();

            securedExecutions = securedContext.getBean(ReportExecutionService.class);
            securedReports = securedContext.getBean(TransactionReportService.class);
            securedMvc = MockMvcBuilders.webAppContextSetup(securedContext)
                    .apply(springSecurity())
                    .build();
        }

        /**
         * Closes the refreshed context so this group leaves no application behind it.
         *
         * <p>This method takes no parameter and yields no value.</p>
         */
        @AfterEach
        void closeSecuredSlice() {
            if (securedContext != null) {
                securedContext.close();
            }
        }

        // WHY : Assumptions: BOTH group authorities are exercised against the listing, because the
        //       chain admits either and a case naming one would pass while the other was silently
        //       withdrawn. The two are the migration of the reference's two user types, the values 'A'
        //       and 'U' the sign-on record stores.
        // WHY : Assumptions: the reference's user kind is a single character with exactly two named
        //       values -- app/cpy/COCOM01Y.cpy L26 declares it and L27 and L28 name 'A' and 'U' -- and
        //       this screen restricts neither of them, so both admitted kinds reach the read.
        /**
         * Confirms either group authority reaches the paged listing.
         *
         * <p>Assumptions: the composer is stubbed to answer an empty window, so the case turns on the
         * authorization decision rather than on a rendered population.</p>
         *
         * @param authority one of the two group authorities the deployed chain admits on a business
         *     address, supplied once per invocation
         * @throws Exception if the request cannot be performed
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY})
        @DisplayName("either group authority reaches the paged listing")
        void eitherGroupAuthorityReachesTheListing(String authority) throws Exception {
            when(securedReports.readDetailLinePage(any(), any(), any(), anyBoolean(), any()))
                    .thenReturn(PageResponse.empty());

            securedMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                            .with(jwt().authorities(new SimpleGrantedAuthority(authority)))
                            .param("startDate", "2022-07-01")
                            .param("endDate", "2022-07-31"))
                    .andExpect(status().isOk());
        }

        // WHY : Assumptions: the totals and the submission are exercised as well as the listing,
        //       because the chain authorises by ADDRESS and the three are three addresses. A rule
        //       narrowed to one of them would leave the other two open and no listing case would
        //       notice.
        // WHY : Assumptions: the transaction defined at app/csd/CARDDEMO.CSD L409 to L410 runs the one
        //       program for every admitted user kind of app/cpy/COCOM01Y.cpy L27 and L28, so each address
        //       this operation publishes is reachable by an admitted caller.
        /**
         * Confirms an admitted caller reaches the totals and the submission as well.
         *
         * <p>Assumptions: the submission is stubbed all the way to an accepted run so the case reaches
         * a 201 rather than stopping at a refusal the chain did not cause.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if either request cannot be performed
         */
        @Test
        @DisplayName("an admitted caller reaches the totals and the submission")
        void anAdmittedCallerReachesTheTotalsAndTheSubmission() throws Exception {
            LocalDate lower = LocalDate.of(2022, 7, 1);
            LocalDate upper = LocalDate.of(2022, 7, 31);
            when(securedReports.composeTotals(any(), any())).thenReturn(List.of());
            stubConfirmedMonthly(securedExecutions, lower, upper);

            securedMvc.perform(get(ReportController.BASE_PATH + ReportController.TOTALS_PATH)
                            .with(jwt().authorities(
                                    new SimpleGrantedAuthority(JwtRoleConverter.USER_AUTHORITY)))
                            .param("startDate", "2022-07-01")
                            .param("endDate", "2022-07-31"))
                    .andExpect(status().isOk());

            securedMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .with(jwt().authorities(
                                    new SimpleGrantedAuthority(JwtRoleConverter.ADMIN_AUTHORITY)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest("Y"))))
                    .andExpect(status().isCreated());
        }

        // WHY : Assumptions: the refused caller is fully AUTHENTICATED and merely holds no recognised
        //       group, which is the case a rule of "is the caller authenticated" would have admitted.
        //       The chain requires one of the two groups on every business address for that reason, so
        //       a token minted for another audience of the same pool reaches 403 rather than data.
        // WHY : Assumptions: the user-kind domain of app/cpy/COCOM01Y.cpy L27 and L28 holds exactly two
        //       values, so a caller carrying a third is not an admitted kind and reaches nothing.
        /**
         * Confirms an authenticated caller holding no recognised group is refused on every address.
         *
         * <p>Assumptions: all three business addresses are asserted in one case because the property
         * is the RULE and not one route's wiring, and the composer and orchestration are additionally
         * asserted never consulted -- a refusal rendered after a read would have already spent the
         * query it was supposed to prevent.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if any request cannot be performed
         */
        @Test
        @DisplayName("a caller holding no recognised group is refused on every business address")
        void aCallerHoldingNoRecognisedGroupIsRefused() throws Exception {
            SimpleGrantedAuthority unrecognised = new SimpleGrantedAuthority("carddemo-visitor");

            securedMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                            .with(jwt().authorities(unrecognised))
                            .param("startDate", "2022-07-01")
                            .param("endDate", "2022-07-31"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_FORBIDDEN));

            securedMvc.perform(get(ReportController.BASE_PATH + ReportController.TOTALS_PATH)
                            .with(jwt().authorities(unrecognised))
                            .param("startDate", "2022-07-01")
                            .param("endDate", "2022-07-31"))
                    .andExpect(status().isForbidden());

            securedMvc.perform(post(ReportController.BASE_PATH + ReportController.SUBMISSION_PATH)
                            .with(jwt().authorities(unrecognised))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REQUEST_MAPPER.writeValueAsString(monthlyRequest("Y"))))
                    .andExpect(status().isForbidden());

            verify(securedReports, never())
                    .readDetailLinePage(any(), any(), any(), anyBoolean(), any());
            verify(securedExecutions, never()).start(any(), any(), any(), any(), any());
        }

        // WHY : Assumptions: an unauthenticated request is asserted to be CHALLENGED rather than
        //       answered, which is also what proves the chain is in front of the dispatcher at all.
        //       Without it an omitted filter would be indistinguishable from a granted dispatch in
        //       every other case in this group.
        // WHY : Assumptions: the reference sends a task that arrives with no passed area straight back to
        //       sign-on -- app/cbl/CORPT00C.cbl L172 to L174, with the same default applied at L542 to
        //       L543 -- so an unidentified caller never reaches this screen's work.
        /**
         * Confirms an unauthenticated request is challenged, proving the chain is installed.
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an unauthenticated request is challenged, proving the chain is installed")
        void anUnauthenticatedRequestIsChallenged() throws Exception {
            securedMvc.perform(get(ReportController.BASE_PATH + ReportController.LINES_PATH)
                            .param("startDate", "2022-07-01")
                            .param("endDate", "2022-07-31"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code")
                            .value(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED));
        }

        // WHY : Assumptions: statelessness is asserted on an ADMITTED request, because a refused one
        //       never reaches the handler and could be stateless for the wrong reason. The reference is
        //       pseudo-conversational and carries its continuity in a passed structure between turns;
        //       the migration keeps none of it server-side, which is what lets identical tasks sit
        //       behind one address without any of them holding a caller's turn.
        // WHY : Refactoring Rationale: the reference carries its re-entry indicator in the passed area --
        //       app/cpy/COCOM01Y.cpy L29 declares it with its two named states at L30 and L31, and
        //       app/cbl/CORPT00C.cbl L547 clears it before transferring control -- and the target keeps no
        //       such state at all, which is why an admitted request leaves nothing behind on the server.
        /**
         * Confirms an admitted request creates no session and sets no cookie.
         *
         * <p>Assumptions: both halves are asserted because they fail independently -- a container can
         * create a session that is never advertised, and a chain can advertise a cookie for a session
         * created elsewhere. Neither may happen on a stateless surface.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         *
         * @throws Exception if the request cannot be performed
         */
        @Test
        @DisplayName("an admitted request creates no session and sets no cookie")
        void anAdmittedRequestCreatesNoSession() throws Exception {
            when(securedReports.composeTotals(any(), any())).thenReturn(List.of());

            var result = securedMvc.perform(
                            get(ReportController.BASE_PATH + ReportController.TOTALS_PATH)
                                    .with(jwt().authorities(new SimpleGrantedAuthority(
                                            JwtRoleConverter.USER_AUTHORITY)))
                                    .param("startDate", "2022-07-01")
                                    .param("endDate", "2022-07-31"))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getRequest().getSession(false))
                    .as("the deployed chain declares a stateless session policy, so nothing may be"
                            + " held for the caller between requests")
                    .isNull();
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                    .as("no session may be advertised either")
                    .isNull();
        }
    }

    /**
     * Supplies the six per-component field identities a consolidated bound still reports separately.
     *
     * <p>Assumptions: the identities are spelled as the shared kernel already spells them, which is
     * the camel-cased request-member form its own field-error cases use. Inventing a second spelling
     * here would let a client written against one of them attach help text to nothing.</p>
     *
     * @return one argument row per component, carrying the field identity, the state, the sentence
     *     and the reference line that declares that sentence; never {@code null}
     */
    private static Stream<Arguments> emptyComponentFailures() {
        return Stream.of(
                Arguments.of("startDateMonth", "Start Date - Month can NOT be empty...", 261),
                Arguments.of("startDateDay", "Start Date - Day can NOT be empty...", 268),
                Arguments.of("startDateYear", "Start Date - Year can NOT be empty...", 275),
                Arguments.of("endDateMonth", "End Date - Month can NOT be empty...", 282),
                Arguments.of("endDateDay", "End Date - Day can NOT be empty...", 289),
                Arguments.of("endDateYear", "End Date - Year can NOT be empty...", 296));
    }

    /**
     * Supplies the six component-range field identities of the reference's second validation pass.
     *
     * <p>Assumptions: this pass is supplied separately from the blank pass above because the
     * reference runs TWO passes over the same six components -- the blank chain at
     * {@code app/cbl/CORPT00C.cbl} L258 to L302 and the range chain at L329 to L378 -- with the
     * zero-padding normalisation at L305 to L327 between them. Folding the two into one row set
     * would leave the second pass unasserted.</p>
     *
     * @return one argument row per component, carrying the field identity, the sentence and the
     *     reference line that declares that sentence; never {@code null}
     */
    private static Stream<Arguments> componentRangeFailures() {
        return Stream.of(
                Arguments.of("startDateMonth", "Start Date - Not a valid Month...", 331),
                Arguments.of("startDateDay", "Start Date - Not a valid Day...", 340),
                Arguments.of("startDateYear", "Start Date - Not a valid Year...", 348),
                Arguments.of("endDateMonth", "End Date - Not a valid Month...", 357),
                Arguments.of("endDateDay", "End Date - Not a valid Day...", 366),
                Arguments.of("endDateYear", "End Date - Not a valid Year...", 374));
    }
}
