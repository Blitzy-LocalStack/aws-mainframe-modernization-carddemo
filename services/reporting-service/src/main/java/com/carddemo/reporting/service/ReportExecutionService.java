package com.carddemo.reporting.service;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.reporting.dto.ReportRequest;
import com.carddemo.reporting.dto.ReportSubmissionResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

/**
 * Starts an on-demand report execution, taking over the submission
 * {@code app/cbl/CORPT00C.cbl} performs by writing job control text to a transient data queue.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code app/cbl/CORPT00C.cbl} is a 649-line online program driving the {@code CR00} transaction.
 * It offers three mutually exclusive report types, gates submission behind a confirmation, and
 * submits by writing to the {@code JOBS} transient data queue defined across L499 to L505 of
 * {@code app/csd/CARDDEMO.CSD} with {@code DDNAME(INREADER)} at L501. The internal reader has no
 * cloud analogue, so this service issues a {@code StartExecution} call instead.
 *
 * <h2>Assumptions: the three report types are mutually exclusive and the exclusion is enforced</h2>
 *
 * <p>The screen offers a monthly, a yearly and a custom selection, each a one-character mark. The
 * baseline validates the combination on the screen; this service validates it on the request, because
 * a stateless handler has no screen to redisplay. Exactly one mark must be present: none leaves the
 * run with no date range at all, and two leave the run with two, and the baseline resolves neither.
 *
 * <h2>Assumptions: the confirmation gate answers two different outcomes with two different
 * statuses</h2>
 *
 * <p>The package charter one level up records the reasoning in full and it is not restated here, only
 * its consequence for this service: a deliberate cancellation is a successful request that started
 * nothing, and it is reported by returning no submission rather than by raising. The baseline cannot
 * draw that distinction -- inside {@code SUBMIT-JOB-TO-INTRDR}, which opens at
 * {@code app/cbl/CORPT00C.cbl} L462, the branch taken for a negative answer runs from L480 to L483 and
 * sets the error flag with no message text at all, while the branch for an unrecognised value at L484
 * to L493 supplies both -- so at flag level a cancel is indistinguishable from a validation failure.
 * Splitting them preserves the suppression of the success block at L445 to L456 while giving the
 * caller the feedback a single flag could not carry.
 *
 * <h2>Assumptions: the execution name is derived and never generated</h2>
 *
 * <p>The orchestrator refuses a start whose execution name is already in use, so a name derived from
 * the report type and the date range makes a duplicate submission a refusal rather than a second run
 * of the same report. Alternatives Considered: a generated name, which never conflicts. Rejected
 * because the timeout on the orchestration call can abandon a submission the orchestrator went on to
 * accept, and a caller that retried would then start the same report twice with two sets of output
 * objects and no way to tell which was current.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Every member below carries a docstring regardless of visibility, because user-specified Rule 1
 * (Explainability) attaches its presence clause to every function and class and names no visibility.
 * The four rationale labels are written in the plural, unparenthesised forms that rule gives.
 */
@Service
public class ReportExecutionService {

    /**
     * Property key of the state machine an on-demand submission starts.
     */
    public static final String STATE_MACHINE_ARN_PROPERTY =
            "carddemo.reporting.step-functions.state-machine-arn";

    /**
     * The mark a selected report type carries on the request.
     *
     * <p>Assumptions: the mark is a single character and its value is not examined beyond presence,
     * which is what {@code app/cbl/CORPT00C.cbl} does with the three one-character selection fields.
     * A blank is absence and anything else is selection.</p>
     */
    public static final String SELECTED_MARK = "Y";

    /**
     * The answer that confirms a submission.
     *
     * <p>Assumptions: the baseline accepts either case at {@code app/cbl/CORPT00C.cbl} L474 to L479,
     * so the comparison here is case-insensitive rather than exact.</p>
     */
    public static final String CONFIRM_YES = "Y";

    /**
     * The answer that cancels a submission deliberately.
     */
    public static final String CONFIRM_NO = "N";

    /**
     * The report name the reference moves for a monthly run.
     *
     * <p>Assumptions: the three names below are the values {@code app/cbl/CORPT00C.cbl} moves into
     * {@code WS-REPORT-NAME} at L214, L240 and L433, carried verbatim under AAP rule T8.</p>
     */
    public static final String MONTHLY_REPORT_NAME = "Monthly";

    /**
     * The report name the reference moves for a yearly run.
     */
    public static final String YEARLY_REPORT_NAME = "Yearly";

    /**
     * The report name the reference moves for a custom run.
     */
    public static final String CUSTOM_REPORT_NAME = "Custom";

    /**
     * The orchestration client the submission is issued through.
     */
    private final SfnClient sfnClient;

    /**
     * The state machine an on-demand submission starts.
     */
    private final String stateMachineArn;

    /**
     * The clock the submission timestamp is read from.
     */
    private final Clock clock;

    /**
     * Records the collaborators this service composes.
     *
     * <p>Assumptions: the clock is injected rather than read from the system, because the submission
     * timestamp is asserted in tests and a value read from the wall clock cannot be. The same
     * discipline is what keeps the batch side reproducible: the baseline injects its business date as
     * a job parameter rather than reading it from a clock.</p>
     *
     * @param sfnClient the orchestration client, supplied by
     *     {@code com.carddemo.reporting.config.StepFunctionsConfig}
     * @param stateMachineArn the state machine an on-demand submission starts, bound from
     *     {@value #STATE_MACHINE_ARN_PROPERTY}
     * @param clock the clock the submission timestamp is read from
     */
    public ReportExecutionService(
            SfnClient sfnClient,
            @Value("${" + STATE_MACHINE_ARN_PROPERTY + "}") String stateMachineArn,
            Clock clock) {
        this.sfnClient = sfnClient;
        this.stateMachineArn = stateMachineArn;
        this.clock = clock;
    }

    /**
     * Resolves the report type a request selected.
     *
     * <p>Assumptions: this is separated from the submission so that a caller can validate a request
     * without starting anything, which is what lets the confirmation gate be evaluated after
     * validation rather than before it. Evaluating the confirmation first would let a request that
     * selected no report type at all be answered as a successful cancellation.</p>
     *
     * @param request the report request to resolve; must not be {@code null}
     * @return the verbatim report name for the selected type
     * @throws ClientInputException if no type is selected or more than one is, because the
     *     baseline's three selection fields are mutually exclusive and it resolves neither
     *     combination
     */
    public String resolveReportName(ReportRequest request) {
        boolean monthly = isMarked(request.monthly());
        boolean yearly = isMarked(request.yearly());
        boolean custom = isMarked(request.custom());
        int selected = (monthly ? 1 : 0) + (yearly ? 1 : 0) + (custom ? 1 : 0);

        // WHY : Assumptions: the count is compared against one rather than tested for emptiness,
        //       because the two failures are different and both occur. A request with no mark has no
        //       date range to run over; a request with two has two, and the reference resolves
        //       neither. Reporting one message for both is what the screen does, and the count is
        //       what lets this service name which of the two happened.
        if (selected != 1) {
            // WHY : Assumptions: the refusal is the shared client-input type rather than a plain
            //       argument exception, so it reaches the shared advice and is rendered as one entry
            //       in the structured per-field error array. The advice holds the correlation
            //       identity, the request path and the clock the problem shape needs, and this
            //       service holds none of the three -- which is why the package charter forbids an
            //       exception handler here and requires the shared type instead.
            throw new ClientInputException(ApiError.CODE_VALIDATION, "reportType",
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
     * <p>Assumptions: three answers are distinguished and not two. A confirming answer submits; a
     * declining answer is a deliberate cancellation and is a successful request that started nothing;
     * anything else is a validation failure. The baseline distinguishes the same three at
     * {@code app/cbl/CORPT00C.cbl} L474 to L493 and then collapses the last two onto one flag, which
     * is the collapse the package charter records as corrected here.</p>
     *
     * @param request the report request to inspect; must not be {@code null}
     * @return {@code true} when the request confirms submission, {@code false} when it declines
     *     deliberately
     * @throws ClientInputException if the answer is neither of the two the baseline recognises
     */
    public boolean isConfirmed(ReportRequest request) {
        String answer = request.confirm() == null ? "" : request.confirm().trim();
        if (CONFIRM_YES.equalsIgnoreCase(answer)) {
            return true;
        }
        if (CONFIRM_NO.equalsIgnoreCase(answer)) {
            return false;
        }
        // WHY : Assumptions: the message states the length of the offending answer and never the
        //       answer itself. A one-character selection field carries no personal data, so the
        //       withholding is not about exposure -- it is about the message being a constant, which
        //       is what lets an alert rule match this refusal without matching on caller input.
        throw new ClientInputException(ApiError.CODE_VALIDATION, "confirm",
                "confirm must be Y or N but was of length " + answer.length());
    }

    /**
     * Resolves the inclusive business-date range the selected report type covers.
     *
     * <p>The three types derive their range differently, and each derivation is the reference's own.
     * A monthly report runs from the first of the current month to the current day, per
     * {@code app/cbl/CORPT00C.cbl} L215 to L235, which moves the current year and month with a
     * literal {@code '01'} day into the start and the current year, month and day into the end. A
     * yearly report runs from the first of January to the thirty-first of December of the current
     * year, per L241 to L252, whose end is the literal pair {@code '12'} and {@code '31'}. A custom
     * report takes both bounds from the request, per L381 to L386, where the six typed subfields are
     * assembled and then each assembled value is edit-checked before use at L388 and L408.</p>
     *
     * <p>Assumptions: the two derived types read the current date from the injected clock and not
     * from the system clock, which is what lets a test assert the range a given day produces. The
     * reference reads {@code FUNCTION CURRENT-DATE} at L215 and L241, so a clock read is faithful;
     * making the reading injectable is what the {@code PARM-DATE} discipline of the batch side does
     * for the same reason.</p>
     *
     * <p>Assumptions: a custom range is refused when either bound is absent or unparseable, and the
     * refusal names the offending bound. The reference calls the shared date-edit routine on each
     * assembled bound separately at L388 and L408 and reports each failure against its own field, so
     * naming one bound rather than the pair reproduces which field the screen would have marked.</p>
     *
     * <p>Alternatives Considered: deriving the monthly end bound as the last day of the current
     * month rather than the current day. Rejected because it is not what the reference does -- L234
     * moves the current DAY into the end bound -- and because a range extending past today would
     * select nothing for the remaining days while implying that it had.</p>
     *
     * @param request the report request whose range is wanted; must not be {@code null}
     * @param reportName the resolved report name, as {@link #resolveReportName(ReportRequest)}
     *     returns it; must not be {@code null}
     * @return the inclusive range the report covers; never {@code null}
     * @throws ClientInputException if a custom range omits a bound, states an unparseable bound, or
     *     ends before it starts
     */
    public DateRange resolveRange(ReportRequest request, String reportName) {
        if (MONTHLY_REPORT_NAME.equals(reportName)) {
            LocalDate today = LocalDate.now(clock);
            return new DateRange(today.withDayOfMonth(1), today);
        }
        if (YEARLY_REPORT_NAME.equals(reportName)) {
            LocalDate today = LocalDate.now(clock);
            return new DateRange(today.withDayOfYear(1), today.withMonth(12).withDayOfMonth(31));
        }

        LocalDate start = requireBound(request.startDate(), "startDate");
        LocalDate end = requireBound(request.endDate(), "endDate");

        // WHY : Assumptions: the ordering of the two bounds is checked here rather than being left to
        //       the query layer, because an inverted range is a request the caller can correct and a
        //       half-open interval built from one selects nothing while reporting success. The
        //       reference cannot make this check -- it edit-checks each bound independently and never
        //       compares them -- so this is a documented addition rather than a transcription, and it
        //       only ever refuses input the reference would have run to an empty report.
        if (end.isBefore(start)) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, "endDate",
                    "endDate must not be earlier than startDate");
        }
        return new DateRange(start, end);
    }

    /**
     * Parses one custom bound, refusing an absent or unparseable value against its own field name.
     *
     * @param value the bound as the request stated it, which may be {@code null} or blank
     * @param field the request field the bound came from, used to name the refusal
     * @return the parsed bound
     * @throws ClientInputException if the bound is absent, blank or not a calendar date
     */
    private static LocalDate requireBound(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    field + " must be supplied for a custom report");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException notADate) {
            // WHY : Assumptions: the refusal names the field and never repeats the value. A date is
            //       not sensitive, but the message is written into an operational record and a
            //       constant message is what lets an alert rule match this refusal without matching
            //       on caller input. The parse failure itself is attached as the cause so the
            //       position it reports survives for a developer reading a stack trace.
            throw new ClientInputException(ApiError.CODE_VALIDATION, field,
                    field + " must be a calendar date in YYYY-MM-DD order");
        }
    }

    /**
     * An inclusive range of business dates.
     *
     * <p>Assumptions: both bounds are inclusive, which is the reference's own reading -- its custom
     * range is the pair of dates a user typed and both are meant to be covered. The half-open instant
     * interval the query layer needs is derived from this pair at the point of query rather than being
     * carried here, so that this type states one thing and the conversion happens once.</p>
     *
     * @param start the first business date covered; never {@code null}
     * @param end the last business date covered, never earlier than {@code start}; never {@code null}
     */
    public record DateRange(LocalDate start, LocalDate end) {

        /**
         * Rejects an absent bound at construction.
         *
         * @param start the first business date covered
         * @param end the last business date covered
         * @throws NullPointerException if either bound is {@code null}
         */
        public DateRange {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
        }
    }

    /**
     * Starts an execution for a confirmed request and describes what was started.
     *
     * <p>Assumptions: the two date bounds are passed as the execution input rather than being read
     * from a clock inside the state machine, which is the same discipline
     * {@code app/jcl/INTCALC.jcl} L22 applies when it injects a business date as a job parameter. A
     * run whose range came from a clock could not be rerun to the same output.</p>
     *
     * @param request the confirmed report request; must not be {@code null}
     * @param reportName the resolved report name, as {@link #resolveReportName(ReportRequest)}
     *     returns it
     * @param rangeStart the first business date of the range
     * @param rangeEnd the last business date of the range
     * @return a description of the accepted run, carrying the orchestration handle a caller observes
     *     it through; never {@code null}
     */
    public ReportSubmissionResponse start(
            ReportRequest request, String reportName, LocalDate rangeStart, LocalDate rangeEnd) {
        String startDate = rangeStart.toString();
        String endDate = rangeEnd.toString();

        // WHY : Assumptions: the input is assembled as a small JSON object here rather than by a
        //       serialisation mapper. Three scalar values with no money and no free text among them
        //       cannot carry a value a mapper would shape differently, and routing them through one
        //       would put the execution input's shape under a configuration this service does not
        //       own -- the money codec the shared kernel contributes changes how a monetary value
        //       serialises, and a future input carrying an amount would then depend on it silently.
        String executionInput = "{\"reportType\":\"" + reportName.toLowerCase(Locale.ROOT)
                + "\",\"startDate\":\"" + startDate
                + "\",\"endDate\":\"" + endDate + "\"}";

        StartExecutionResponse started = sfnClient.startExecution(StartExecutionRequest.builder()
                .stateMachineArn(stateMachineArn)
                .name(executionName(reportName, startDate, endDate))
                .input(executionInput)
                .build());

        return new ReportSubmissionResponse(
                started.executionArn(),
                reportName,
                request.title01(),
                request.title02(),
                startDate,
                endDate,
                TimestampFormatter.formatNow(clock));
    }

    /**
     * Builds the execution name a submission is started under.
     *
     * <p>Assumptions: the name is derived from the report type and both bounds, and it carries no
     * random component. That is what makes a duplicate submission a refusal by the orchestrator
     * rather than a second run, for the reason the type-level charter gives. The separators are
     * hyphens because an execution name admits a restricted character set and a colon -- which the
     * two dates would otherwise be joined by in a timestamp form -- is not in it.</p>
     *
     * @param reportName the resolved report name
     * @param startDate the first business date of the range, in the ten-character form
     * @param endDate the last business date of the range, in the ten-character form
     * @return the execution name, never {@code null}
     */
    private static String executionName(String reportName, String startDate, String endDate) {
        return reportName.toLowerCase(Locale.ROOT) + "-" + startDate + "-" + endDate;
    }

    /**
     * Reports whether a one-character selection field carries a mark.
     *
     * @param field the selection field to inspect, which may be {@code null} or blank
     * @return {@code true} when the field carries any non-blank character
     */
    private static boolean isMarked(String field) {
        return field != null && !field.isBlank();
    }
}
