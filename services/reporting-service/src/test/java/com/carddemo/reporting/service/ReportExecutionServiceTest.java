package com.carddemo.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.reporting.dto.ReportRequest;
import com.carddemo.reporting.dto.ReportSubmissionResponse;
import com.carddemo.reporting.mapper.ReportBandLayouts;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

/**
 * Pins the four decisions {@link ReportExecutionService} makes that no test above it can see: WHICH
 * report type a selection resolves to, WHICH of the reference's three confirmation answers a request
 * carries, WHAT range each of the three presets expands to, and WHAT is handed to the orchestrator.
 *
 * <h2>Why this class exists at all, stated as the gap it closes</h2>
 *
 * <p>Refactoring Rationale: this class is new, and its absence let two wrong behaviours reach review
 * looking correct. The only test that touched this surface was the controller slice, which mocks this
 * service entirely -- so every sentence and every branch it stubbed was whatever the author believed
 * the service did, asserted against a stub that did exactly that. Two beliefs were wrong. The service
 * COUNTED the three type marks and refused any request carrying more than one, where
 * {@code app/cbl/CORPT00C.cbl} L212 is an {@code EVALUATE TRUE} whose first matching arm wins and
 * whose later arms are never reached; and it refused an unanswered confirmation as a validation
 * failure, where L464 to L474 composes a prompt and re-displays. A mocked collaborator cannot
 * contradict its author, which is why the cases below drive the real class.
 *
 * <h2>What is stubbed, what is real, and why the split falls where it does</h2>
 *
 * <p>Assumptions: the orchestrator client is a stand-in and the CLOCK is real -- a fixed one. Those
 * are the only two collaborators this class has. The client is stubbed because what is under test is
 * the argument handed to it, which is observable from a captor and from nothing else: a real client
 * would need a state machine to exist, and would then report the argument's correctness only as the
 * absence of a failure. The clock is fixed rather than mocked because the presets are DERIVED from it
 * and the derivation is the property under test; a stub returning a date would assert the stub.
 *
 * <p>Assumptions: several cases pin one instant and others pin a different one, and each says which
 * and why at its own site. A single class-wide instant would leave the two derivations that only go
 * wrong at a boundary -- the December month roll and a leap February -- unexercised, and those are
 * precisely the two the reference spends six lines on at L223 to L230.
 *
 * <h2>The verbatim sentences are asserted from the production constants</h2>
 *
 * <p>Assumptions: every message assertion below reads the constant the production class publishes
 * rather than retyping the sentence. Retyping would let a case and its subject drift together while
 * both stayed wrong -- which is exactly how the invented sentences survived -- and would put a second
 * copy of a user-visible string in the repository, which transformation rule T8 exists to prevent. The
 * constants themselves are checked against the reference by their own Javadoc, which cites the line
 * each was copied from; what these cases check is that the right constant reaches the right branch.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the members below carry their own where they have any.
 */
@DisplayName("the report request edge: three types, three answers, three ranges and one start")
class ReportExecutionServiceTest {

    /**
     * The instant most cases are pinned to: mid-July, mid-month, in a 31-day month of a common year.
     *
     * <p>Assumptions: this date is chosen so that neither preset's end bound coincides with it. The
     * monthly range ends on the 31st and the yearly on the 31st of December, so a case asserting
     * either would fail if the implementation had resolved the end bound to "today" -- which is the
     * reading a peer document of this program records and which the production Javadoc supersedes by
     * citation. A date at the end of a month would make the two readings agree and the assertion
     * vacuous.</p>
     */
    private static final Instant MID_JULY = Instant.parse("2022-07-18T12:00:00Z");

    /** The state machine identifier this class hands the service, standing for a provisioned one. */
    private static final String STATE_MACHINE_ARN =
            "arn:aws:states:us-east-1:000000000000:stateMachine:carddemo-transaction-report-dev";

    /** The handle the stubbed orchestrator answers a started run with. */
    private static final String EXECUTION_ARN =
            "arn:aws:states:us-east-1:000000000000:execution:carddemo-transaction-report-dev:1";

    /** The mark the reference treats as a selection: any character that is neither space nor low value. */
    private static final String MARK = "Y";

    /**
     * The absent idempotency key, which is how the operation is reached when no header carried one.
     *
     * <p>Assumptions: {@code null} is a MEANINGFUL argument here rather than a placeholder. The landed
     * operation composes a random execution name when no key arrives and a derived, reproducible one when a
     * key does, and {@code ReportSubmissionNamingTest} is where that difference is asserted. Passing the
     * absent value keeps every case here on the arm whose subject is the range, the confirmation and the
     * response rather than the name.</p>
     */
    private static final String NO_SUPPLIED_KEY = null;

    /** The stand-in for the orchestrator, whose argument is what most of the start cases assert on. */
    private final SfnClient sfn = mock(SfnClient.class);

    /**
     * Builds the service under test over the stand-in client and a clock fixed at one instant.
     *
     * <p>Assumptions: the service is constructed per case rather than shared, because each case
     * chooses its own instant and a shared instance would carry the previous case's clock.</p>
     *
     * @param pinned the instant the service's clock reports; must not be {@code null}
     * @return the service under test, never {@code null}
     */
    private ReportExecutionService serviceAt(Instant pinned) {
        return new ReportExecutionService(
                this.sfn, STATE_MACHINE_ARN, Clock.fixed(pinned, ZoneOffset.UTC));
    }

    /**
     * Builds a request carrying the three type marks and a confirmation answer, and nothing else.
     *
     * @param monthly the monthly mark, or {@code null} for an unmarked selector
     * @param yearly the yearly mark, or {@code null} for an unmarked selector
     * @param custom the custom mark, or {@code null} for an unmarked selector
     * @param confirm the confirmation answer, or {@code null} for an unanswered one
     * @return the request, never {@code null}
     */
    private static ReportRequest selection(
            String monthly, String yearly, String custom, String confirm) {
        return new ReportRequest(null, null, null, null, null, null,
                monthly, yearly, custom, null, null, confirm, null);
    }

    /**
     * Builds a custom-type request carrying both range bounds and a confirmation answer.
     *
     * @param startDate the lower bound as the caller stated it, or {@code null} to omit it
     * @param endDate the upper bound as the caller stated it, or {@code null} to omit it
     * @param confirm the confirmation answer, or {@code null} for an unanswered one
     * @return the request, never {@code null}
     */
    private static ReportRequest customRange(String startDate, String endDate, String confirm) {
        return new ReportRequest(null, null, null, null, null, null,
                null, null, MARK, startDate, endDate, confirm, null);
    }

    /**
     * A single mark resolves to the report name the reference assigns for it.
     *
     * <p>Purpose: the three arms at {@code app/cbl/CORPT00C.cbl} L213, L239 and L256 move
     * {@code 'Monthly'}, {@code 'Yearly'} and {@code 'Custom'} into the report name at L214, L240 and
     * L433 respectively, and the name is what the two composed sentences interpolate.</p>
     *
     * @param monthly the monthly mark for this case, blank for unmarked
     * @param yearly the yearly mark for this case, blank for unmarked
     * @param custom the custom mark for this case, blank for unmarked
     * @param expected the report name the reference assigns
     */
    @ParameterizedTest
    @CsvSource({"Y,,,Monthly", ",Y,,Yearly", ",,Y,Custom"})
    @DisplayName("one mark resolves to the reference's own name for that type")
    void oneMarkResolvesToItsName(String monthly, String yearly, String custom, String expected) {
        assertThat(serviceAt(MID_JULY)
                        .resolveReportName(selection(monthly, yearly, custom, MARK)))
                .isEqualTo(expected);
    }

    /**
     * Two or three marks resolve to the FIRST arm that matches, exactly as the reference does.
     *
     * <p>Purpose: this is the whole of finding the earlier revision failed. {@code EVALUATE TRUE} at
     * {@code app/cbl/CORPT00C.cbl} L212 evaluates its arms in order and leaves the chain on the first
     * one whose condition holds, so a screen sent with both the monthly and the yearly mark set runs
     * the MONTHLY report and never reaches the yearly arm. The earlier revision counted the marks and
     * refused the request, which is a refusal the reference does not have.</p>
     *
     * <p>Assumptions: the three combinations below are DISCRIMINATING and a single one would not be.
     * Monthly-and-yearly and monthly-and-custom both resolve to Monthly, so a case asserting only
     * those two would still pass if the implementation always answered Monthly whenever more than one
     * mark was set. Yearly-and-custom resolving to Yearly is what rules that out, because it names an
     * arm that is neither the first nor the last. All-three is included because it is the state a
     * caller reaches by ignoring the screen's exclusivity altogether.</p>
     *
     * @param monthly the monthly mark for this case, blank for unmarked
     * @param yearly the yearly mark for this case, blank for unmarked
     * @param custom the custom mark for this case, blank for unmarked
     * @param expected the report name the reference's first matching arm assigns
     */
    @ParameterizedTest
    @CsvSource({"Y,Y,,Monthly", "Y,,Y,Monthly", ",Y,Y,Yearly", "Y,Y,Y,Monthly"})
    @DisplayName("several marks resolve to the first matching arm, never to a refusal")
    void severalMarksResolveToTheFirstMatchingArm(
            String monthly, String yearly, String custom, String expected) {
        assertThat(serviceAt(MID_JULY)
                        .resolveReportName(selection(monthly, yearly, custom, MARK)))
                .isEqualTo(expected);
    }

    /**
     * A mark holding low values or spaces is absent, not present.
     *
     * <p>Purpose: each arm of the reference's chain tests its mark against BOTH the space and the
     * low-value figurative constants -- {@code NOT = SPACES AND LOW-VALUES} at L213, L239 and L256 --
     * so a field carrying either is unmarked. A low-value character is not whitespace, so a blankness
     * test alone would report it as marked and run a report the caller never asked for.</p>
     *
     * @param absentMark a value the reference reads as no mark at all
     */
    @ParameterizedTest
    @ValueSource(strings = {" ", "\u0000", ""})
    @DisplayName("a mark of spaces, low values or nothing is not a selection")
    void anUnfilledMarkIsNotASelection(String absentMark) {
        ReportExecutionService service = serviceAt(MID_JULY);
        ReportRequest request = selection(absentMark, MARK, null, MARK);

        // WHY : Assumptions: the yearly mark is set alongside, so this case distinguishes "the
        //       monthly mark was ignored" from "the request was refused". Asserting a refusal instead
        //       would pass for a service that treated EVERY value as absent.
        assertThat(service.resolveReportName(request)).isEqualTo("Yearly");
    }

    /**
     * No mark at all is refused with the reference's own sentence, against the selection field.
     *
     * <p>Purpose: this is the final arm of the chain, {@code WHEN OTHER} at
     * {@code app/cbl/CORPT00C.cbl} L437, which moves
     * {@code 'Select a report type to print report...'} at L438 and positions the cursor on the
     * monthly selector at L440.</p>
     *
     * <p>Assumptions: the sentence is asserted from the published constant and the field name is
     * asserted too, because the per-field array is this target's analogue of the cursor move -- a
     * refusal naming no field would leave a client unable to place the operator anywhere.</p>
     */
    @Test
    @DisplayName("no mark is refused with the reference's select-a-type sentence")
    void noMarkIsRefusedWithTheReferenceSentence() {
        ReportExecutionService service = serviceAt(MID_JULY);
        ReportRequest request = selection(null, null, null, MARK);

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.resolveReportName(request))
                .withMessage(ReportExecutionService.MESSAGE_NO_REPORT_TYPE_SELECTED)
                .satisfies(refused -> assertThat(refused.field()).isEqualTo("reportType"));
    }

    /**
     * The two affirmative letters confirm and the two negative letters decline, in either case.
     *
     * <p>Purpose: {@code app/cbl/CORPT00C.cbl} L478 accepts {@code 'Y'} and {@code 'y'} through one
     * arm and L480 accepts {@code 'N'} and {@code 'n'} through the next, so both cases of both letters
     * are the reference's own domain rather than a convenience.</p>
     *
     * @param answer the confirmation answer as a caller supplies it
     * @param expected the outcome the reference's arms select for it
     */
    @ParameterizedTest
    @CsvSource({"Y,CONFIRMED", "y,CONFIRMED", "N,DECLINED", "n,DECLINED"})
    @DisplayName("both cases of both letters reach the reference's own two arms")
    void bothCasesOfBothLettersAreRecognised(
            String answer, ReportExecutionService.Confirmation expected) {
        assertThat(serviceAt(MID_JULY).resolveConfirmation(selection(MARK, null, null, answer)))
                .isEqualTo(expected);
    }

    /**
     * An answer that was never supplied is UNANSWERED, and is not a refusal.
     *
     * <p>Purpose: {@code app/cbl/CORPT00C.cbl} L464 tests the answer against both the space and the
     * low-value constants and, on either, composes a PROMPT at L465 to L470 and re-displays. The flag
     * it raises at L471 exists to suppress the success block at L445, not to report a fault.</p>
     *
     * <p>Refactoring Rationale: an earlier revision RAISED here, which the controller then rendered as
     * an HTTP 400 with a problem body -- telling a caller to correct a request whose only outstanding
     * item was its own answer. The three values below are the three ways that state reaches the
     * service: absent over the wire, empty over the wire, and pad characters from a screen field of
     * declared width.</p>
     *
     * @param unanswered a value the reference reads as no answer at all
     */
    @ParameterizedTest
    @ValueSource(strings = {" ", "\u0000", ""})
    @DisplayName("an unsupplied answer is unanswered, not refused")
    void anUnsuppliedAnswerIsUnanswered(String unanswered) {
        assertThat(serviceAt(MID_JULY)
                        .resolveConfirmation(selection(MARK, null, null, unanswered)))
                .isEqualTo(ReportExecutionService.Confirmation.UNANSWERED);
    }

    /**
     * A null answer is unanswered too, which is how the state arrives over HTTP.
     *
     * <p>Assumptions: this is separated from the parameterised case above because a null cannot be a
     * parameterised string source value without the annotation admitting nulls, and folding it in
     * would make the source's three entries read as four states when one of them was a literal
     * "null" text.</p>
     */
    @Test
    @DisplayName("a null answer is unanswered")
    void aNullAnswerIsUnanswered() {
        assertThat(serviceAt(MID_JULY).resolveConfirmation(selection(MARK, null, null, null)))
                .isEqualTo(ReportExecutionService.Confirmation.UNANSWERED);
    }

    /**
     * An unrecognised answer is refused with the reference's own interpolated sentence.
     *
     * <p>Purpose: {@code app/cbl/CORPT00C.cbl} L484 to L493 is the third arm. It composes the message
     * with a {@code STRING} statement from an opening quotation mark, the answer delimited by a space,
     * and {@code '" is not a valid value to confirm...'}, then raises the flag and positions the
     * cursor on the confirmation field.</p>
     *
     * <p>Assumptions: the offending value is asserted to be PRESENT in the sentence, which is the
     * property an earlier revision withheld deliberately. It emitted a constant naming the accepted
     * letters and the answer's length instead, on the reasoning that keeping caller input out of the
     * message helped operational matching; the value is one character from a domain the request bounds
     * at a single position, and the error code is what alerting matches on.</p>
     *
     * @param answer an answer outside the reference's two accepted letters
     */
    @ParameterizedTest
    @ValueSource(strings = {"Q", "1", "x"})
    @DisplayName("an unrecognised answer is refused with the reference's quoted-value sentence")
    void anUnrecognisedAnswerIsRefusedWithTheQuotedValue(String answer) {
        ReportExecutionService service = serviceAt(MID_JULY);
        ReportRequest request = selection(MARK, null, null, answer);

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.resolveConfirmation(request))
                .withMessage(ReportExecutionService.INVALID_CONFIRM_PREFIX + answer
                        + ReportExecutionService.INVALID_CONFIRM_SUFFIX)
                .satisfies(refused -> assertThat(refused.field()).isEqualTo("confirm"));
    }

    /**
     * The monthly preset covers the whole calendar month, including across a year boundary and a leap
     * February.
     *
     * <p>Purpose: the reference derives the end bound at {@code app/cbl/CORPT00C.cbl} L223 to L230 by
     * moving one into the day, adding one to the month, rolling the year when the incremented month
     * exceeds twelve, and then taking the date of the integer of the date minus one. That lands on the
     * LAST day of the current month, not on today.</p>
     *
     * <p>Assumptions: the three instants below are the three cases the derivation can get wrong, and a
     * single mid-year instant exercises none of them. December is where the year roll at L225 to L228
     * is the only thing standing between the answer and an invalid month thirteen. A leap February is
     * where a table of month lengths would be wrong and the subtract-a-day idiom is right. A 30-day
     * month is included so that a hard-coded 31 fails.</p>
     *
     * @param pinned the instant the clock reports
     * @param expectedStart the first day the range covers
     * @param expectedEnd the last day the range covers
     */
    @ParameterizedTest
    @CsvSource({
        "2022-07-18T12:00:00Z,2022-07-01,2022-07-31",
        "2022-12-05T00:00:00Z,2022-12-01,2022-12-31",
        "2024-02-10T23:59:59Z,2024-02-01,2024-02-29",
        "2022-06-30T00:00:00Z,2022-06-01,2022-06-30"
    })
    @DisplayName("the monthly preset covers a whole calendar month, over a year roll and a leap February")
    void theMonthlyPresetCoversTheWholeMonth(
            String pinned, String expectedStart, String expectedEnd) {
        ReportExecutionService.DateRange range = serviceAt(Instant.parse(pinned))
                .resolveRange(selection(MARK, null, null, MARK),
                        ReportExecutionService.MONTHLY_REPORT_NAME);

        assertThat(range.start()).isEqualTo(LocalDate.parse(expectedStart));
        assertThat(range.end()).isEqualTo(LocalDate.parse(expectedEnd));
    }

    /**
     * The yearly preset covers the first of January through the thirty-first of December.
     *
     * <p>Purpose: both bounds are literal in the reference rather than derived. L243 and L244 of
     * {@code app/cbl/CORPT00C.cbl} move the current year into both bounds, L245 and L246 move a
     * literal month and day of one into the start, and L250 and L251 move a literal twelve and
     * thirty-one into the end.</p>
     *
     * <p>Assumptions: the pinned instant is in July, so an end bound resolved to "today" would be
     * visibly wrong. Both presets legitimately carry an end bound later than the current day for most
     * of their period, and that is the property being pinned.</p>
     */
    @Test
    @DisplayName("the yearly preset covers the whole calendar year, ending after the pinned day")
    void theYearlyPresetCoversTheWholeYear() {
        ReportExecutionService.DateRange range = serviceAt(MID_JULY)
                .resolveRange(selection(null, MARK, null, MARK),
                        ReportExecutionService.YEARLY_REPORT_NAME);

        assertThat(range.start()).isEqualTo(LocalDate.of(2022, 1, 1));
        assertThat(range.end()).isEqualTo(LocalDate.of(2022, 12, 31));
    }

    /**
     * A custom range is taken from the request and both bounds pass the shared date edit.
     *
     * <p>Purpose: the reference assembles each bound from three typed screen components at L381 to
     * L386 and puts each assembled value through {@code CSUTLDTC} at L391 and L406. The bounds reach
     * this class already assembled, so what is left is the edit and the ordering.</p>
     *
     * <p>Assumptions: the clock is pinned and the asserted bounds are in a DIFFERENT year from it, so
     * a custom range that had silently fallen through to a preset would be caught. The two are
     * otherwise indistinguishable when a test picks custom bounds inside the pinned year.</p>
     */
    @Test
    @DisplayName("a custom range is the caller's own pair, not a preset")
    void aCustomRangeIsTheCallersOwnPair() {
        ReportExecutionService.DateRange range = serviceAt(MID_JULY)
                .resolveRange(customRange("2019-03-04", "2019-03-29", MARK),
                        ReportExecutionService.CUSTOM_REPORT_NAME);

        assertThat(range.start()).isEqualTo(LocalDate.of(2019, 3, 4));
        assertThat(range.end()).isEqualTo(LocalDate.of(2019, 3, 29));
    }

    /**
     * A custom range missing a bound, carrying an unparseable bound, or inverted is refused.
     *
     * <p>Purpose: the reference refuses an omitted component through the six blank tests at L258 to
     * L302 and an unparseable value through the two date-edit calls at L391 and L406. The ordering
     * refusal is this target's own and is registered as such by the production method, which explains
     * why it must be raised at this synchronous edge rather than inside the asynchronous generator.</p>
     *
     * <p>Assumptions: the field named by each refusal is asserted, not just the fact of one, because
     * the field is what places the operator's cursor and the two bounds are refused against two
     * different names.</p>
     *
     * <p>Refactoring Rationale: the SENTENCE and the STATE are asserted too, where an earlier revision
     * of this case asserted the field alone. The published contract enumerates the complete catalog a
     * field error of this operation may carry and says of those strings "these strings are the
     * contract", and the earlier production code emitted none of them here: an omitted bound reported a
     * target-authored sentence naming the request member, a wrong-width bound reported a width
     * sentence, and a bound the date edit rejected reported the language environment's severity code
     * and message number -- an internal diagnostic. Asserting the field alone is what let all three
     * pass. The state is asserted because the contract records that BLANK additionally renders the
     * literal asterisk marker the reference writes into an empty field, so a blank bound reported as
     * not-ok would silently lose it.</p>
     *
     * <p>Assumptions: the fifth row is a well-formed date in the WRONG width, {@code 2022-7-1}, and it
     * is there because it is the only row that reaches the width branch. The reference cannot produce
     * such a value -- its three components are fixed-width screen fields -- so no catalog entry names
     * the condition, and the assembled-date sentence is asserted as the one entry that is true of the
     * input rather than merely available.</p>
     *
     * @param startDate the lower bound as stated, or blank to omit it
     * @param endDate the upper bound as stated, or blank to omit it
     * @param expectedField the request member the refusal must name
     * @param expectedState the validation state the refusal must carry, which decides whether a client
     *     renders the reference's asterisk marker
     * @param expectedMessage the catalog sentence the refusal must carry, verbatim
     */
    @ParameterizedTest
    @CsvSource({
        ",2022-07-31,startDate,BLANK,Start Date - Month can NOT be empty...",
        "2022-07-01,,endDate,BLANK,End Date - Month can NOT be empty...",
        "2022-07-XX,2022-07-31,startDate,NOT_OK,Start Date - Not a valid date...",
        "2022-07-01,2022-13-01,endDate,NOT_OK,End Date - Not a valid date...",
        "2022-7-1,2022-07-31,startDate,NOT_OK,Start Date - Not a valid date..."
    })
    @DisplayName("a faulty custom bound is refused by field, by state and with the catalog's sentence")
    void aFaultyCustomRangeIsRefusedByField(String startDate, String endDate, String expectedField,
            FieldValidationFlag expectedState, String expectedMessage) {
        ReportExecutionService service = serviceAt(MID_JULY);
        ReportRequest request = customRange(startDate, endDate, MARK);

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.resolveRange(
                        request, ReportExecutionService.CUSTOM_REPORT_NAME))
                .withMessage(expectedMessage)
                .satisfies(refused -> {
                    assertThat(refused.field()).isEqualTo(expectedField);
                    assertThat(refused.state()).isEqualTo(expectedState);
                });
    }

    /**
     * An inverted range is refused, and its sentence is the one divergence this catalog admits.
     *
     * <p>Purpose: the reference does not refuse an inverted range at all -- its sort condition at L47
     * and L48 of {@code app/jcl/TRANREPT.jcl} simply selects no record, so the run produces an empty
     * report. The target refuses it at this synchronous edge instead, because the generator that would
     * otherwise notice runs INSIDE the execution this class starts, so its refusal would arrive after
     * the caller had been handed an execution handle and told the run was accepted.</p>
     *
     * <p>Trade-offs: this is therefore the only field-error sentence of this operation that is NOT in
     * the published catalog, and it is asserted as such rather than tidied into one. The catalog exists
     * because those strings are reference strings; a documented divergence has no reference string to
     * carry, and borrowing an unrelated one -- the assembled-date message, say -- would tell a caller
     * that a perfectly valid date was invalid. The refusal is registered on the production method that
     * raises it.</p>
     */
    @Test
    @DisplayName("an inverted range is refused with the one sentence the catalog does not hold")
    void anInvertedRangeIsRefusedAsThisTargetsOwnDivergence() {
        ReportExecutionService service = serviceAt(MID_JULY);
        ReportRequest request = customRange("2022-07-31", "2022-07-01", MARK);

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.resolveRange(
                        request, ReportExecutionService.CUSTOM_REPORT_NAME))
                .satisfies(refused -> {
                    assertThat(refused.field()).isEqualTo("endDate");
                    assertThat(refused.getMessage())
                            .as("the divergence names both bounds and borrows no reference sentence")
                            .contains("endDate", "startDate")
                            .doesNotContain(ReportExecutionService.MESSAGE_END_DATE_INVALID);
                });
    }

    /**
     * A report name that is none of the three is an internal fault, not a caller fault.
     *
     * <p>Purpose: the three names the reference assigns are the only three this method admits.
     * Reaching it with a fourth means a caller bypassed the resolution above, which is this service's
     * own invariant broken rather than a request to correct -- so it is raised as a plain argument
     * exception, which the shared advice routes to the internal channel rather than back to the
     * client.</p>
     */
    @Test
    @DisplayName("an unknown report name is an internal fault and not a client refusal")
    void anUnknownReportNameIsAnInternalFault() {
        ReportExecutionService service = serviceAt(MID_JULY);
        ReportRequest request = selection(MARK, null, null, MARK);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.resolveRange(request, "Weekly"))
                .satisfies(raised -> assertThat(raised)
                        .as("a client-input refusal here would tell a caller to correct a correct request")
                        .isNotInstanceOf(ClientInputException.class));
    }

    /**
     * A confirmed start hands the orchestrator the resolved range, a derived name and a typed input,
     * and reports the handle it answers with.
     *
     * <p>Purpose: this is the replacement for {@code WIRTE-JOBSUB-TDQ}, which writes eighty-character
     * job control records to a transient data queue from L515 to L535 of
     * {@code app/cbl/CORPT00C.cbl}. Nothing is composed as job control here; three named values are
     * passed as execution input, and the range comes from the resolution rather than from a clock
     * inside the machine -- the discipline {@code app/jcl/INTCALC.jcl} applies at L22 when it injects
     * a business date as a job parameter, so that a run can be repeated to the same output.</p>
     *
     * <p>Assumptions: all four parts of the request are asserted -- the state machine, the name, the
     * input and the fact that the returned handle is the one the orchestrator gave -- because each can
     * be wrong on its own. A correct input sent to the wrong machine starts nothing; a correct machine
     * with a colliding name is refused; and a response whose handle was dropped leaves a caller with
     * nothing to poll.</p>
     *
     * <p>Assumptions: the execution NAME is asserted by value, because its whole purpose is that a
     * retry after an abandoned call collides rather than starting the report twice. A name carrying a
     * random component would satisfy any assertion about its presence while defeating that purpose,
     * so presence is not what is checked.</p>
     */
    @Test
    @DisplayName("a confirmed start passes the resolved range, a deterministic name and the typed input")
    void aConfirmedStartPassesTheResolvedRange() {
        ReportExecutionService service = serviceAt(MID_JULY);
        when(this.sfn.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().executionArn(EXECUTION_ARN).build());

        ReportSubmissionResponse accepted = service.start(
                selection(MARK, null, null, "Y"),
                ReportExecutionService.MONTHLY_REPORT_NAME,
                LocalDate.of(2022, 7, 1),
                LocalDate.of(2022, 7, 31), NO_SUPPLIED_KEY);

        ArgumentCaptor<StartExecutionRequest> started =
                ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(this.sfn).startExecution(started.capture());

        assertThat(started.getValue().stateMachineArn()).isEqualTo(STATE_MACHINE_ARN);
        // WHY : ⚠️ Assumptions: the name is asserted by its STEM rather than in full, because an unkeyed
        //       submission's name carries a random suffix by design. The orchestrator refuses a repeated
        //       execution name, so two submissions of the same report over the same range would collide
        //       and the second would be reported as a duplicate of a run the caller never asked to reuse;
        //       the suffix is what keeps them distinct. A submission that DOES supply an idempotency key
        //       gets a name derived from it instead, which is reproducible on purpose -- and that pair of
        //       behaviours is asserted in full by ReportSubmissionNamingTest, which is where the name is
        //       the subject. What this case needs is that the resolved RANGE reaches the name and the
        //       input, which the stem carries.
        assertThat(started.getValue().name()).startsWith("monthly-2022-07-01-2022-07-31");
        assertThat(started.getValue().input()).isEqualTo(
                "{\"reportType\":\"monthly\",\"startDate\":\"2022-07-01\","
                        + "\"endDate\":\"2022-07-31\"}");

        assertThat(accepted.executionArn()).isEqualTo(EXECUTION_ARN);
        assertThat(accepted.reportName()).isEqualTo(ReportExecutionService.MONTHLY_REPORT_NAME);
        assertThat(accepted.startDate()).isEqualTo("2022-07-01");
        assertThat(accepted.endDate()).isEqualTo("2022-07-31");
    }

    /**
     * The two header names come from the report record contract, and the stamp comes from the clock.
     *
     * <p>Purpose: {@code app/cpy/CVTRA07Y.cpy} declares the two names as values of the report header
     * group, and they are read from the one class in this module that already carries that group's
     * literals rather than being written out again at this edge. The screen's own two title lines are
     * a different contract at a different declared width and are deliberately not these.</p>
     *
     * <p>Assumptions: the stamp is asserted to the microsecond against the PINNED instant, which is
     * what proves the clock is the injected one rather than an ambient read. A case comparing it
     * against a wall-clock read would pass for a reason unrelated to the code under test and fail
     * whenever the two reads straddled a boundary.</p>
     */
    @Test
    @DisplayName("the response carries the record contract's names and the injected clock's stamp")
    void theResponseCarriesTheContractNamesAndTheInjectedStamp() {
        ReportExecutionService service = serviceAt(MID_JULY);
        when(this.sfn.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().executionArn(EXECUTION_ARN).build());

        ReportSubmissionResponse accepted = service.start(
                selection(null, MARK, null, "y"),
                ReportExecutionService.YEARLY_REPORT_NAME,
                LocalDate.of(2022, 1, 1),
                LocalDate.of(2022, 12, 31), NO_SUPPLIED_KEY);

        assertThat(accepted.shortName()).isEqualTo(ReportBandLayouts.REPORT_SHORT_NAME);
        assertThat(accepted.longName()).isEqualTo(ReportBandLayouts.REPORT_LONG_NAME);
        assertThat(accepted.submittedAt()).isEqualTo("2022-07-18 12:00:00.000000");
    }

    /**
     * A start the orchestrator refuses is raised loudly, carrying the refusal as its cause.
     *
     * <p>Purpose: this is the one behaviour the transport change exists to remove. The baseline's queue
     * definition carries {@code ERROROPTION(IGNORE)} at L501 of {@code app/csd/CARDDEMO.CSD}, which
     * lets the region drop an extrapartition write and leave the request unrecorded -- while the
     * program itself checks the response code at L521 to L531 and holds
     * {@code 'Unable to Write TDQ (JOBS)...'} ready for exactly that condition. Raising here is what
     * keeps a submission this service could not place from being reported as one it did.</p>
     *
     * <p>Assumptions: the raised type is asserted NOT to be the client-input type, and the cause is
     * asserted present. The type decides which channel the shared advice routes it to: a caller's
     * request was well formed and the orchestrator declined it, so reporting it as a caller fault
     * would tell a client to correct a correct request and would keep a real outage out of the
     * internal channel. The cause is the evidence the baseline held ready and the region was told to
     * ignore, so dropping it would reproduce the loss in a different form.</p>
     */
    @Test
    @DisplayName("a refused start is raised loudly, in the internal channel, carrying its cause")
    void aRefusedStartIsRaisedLoudly() {
        ReportExecutionService service = serviceAt(MID_JULY);
        SdkClientException refusal = SdkClientException.create("the orchestrator declined the start");
        when(this.sfn.startExecution(any(StartExecutionRequest.class))).thenThrow(refusal);
        ReportRequest request = selection(MARK, null, null, "Y");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.start(request,
                        ReportExecutionService.MONTHLY_REPORT_NAME,
                        LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31), NO_SUPPLIED_KEY))
                .withCause(refusal)
                .satisfies(raised -> assertThat(raised)
                        .as("a refused start belongs in the internal channel, not the caller's")
                        .isNotInstanceOf(ClientInputException.class));
    }

    /**
     * Nothing is started for a request that declined or never answered.
     *
     * <p>Purpose: the reference reaches its write loop at L496 only through the flag test at L476, so
     * a request that was never confirmed never reaches the queue. This method re-asserts that rather
     * than trusting the caller's call order, so a future caller that inverted the sequence could not
     * start a run the requester had declined.</p>
     *
     * <p>Assumptions: the orchestrator is verified NEVER to have been called, which is the assertion
     * that matters. A case asserting only that an exception was raised would pass for an
     * implementation that started the run and then refused, which is the worst available outcome
     * because the report would exist.</p>
     *
     * @param confirm an answer that is not affirmative: declined, then never supplied
     */
    @ParameterizedTest
    @ValueSource(strings = {"N", "n", " "})
    @DisplayName("an unconfirmed request starts nothing at all")
    void anUnconfirmedRequestStartsNothing(String confirm) {
        ReportExecutionService service = serviceAt(MID_JULY);
        ReportRequest request = selection(MARK, null, null, confirm);

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.start(request,
                        ReportExecutionService.MONTHLY_REPORT_NAME,
                        LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31), NO_SUPPLIED_KEY))
                .satisfies(refused -> assertThat(refused.field()).isEqualTo("confirm"));

        verify(this.sfn, never()).startExecution(any(StartExecutionRequest.class));
    }

    /**
     * Every public entry point refuses a null request rather than dereferencing it.
     *
     * <p>Assumptions: this is asserted because all three methods declare it, and a declared exception
     * nothing exercises is a claim rather than a contract. The refusal names the parameter, which is
     * what tells a caller which of several arguments was missing.</p>
     */
    @Test
    @DisplayName("a null request is refused by name at every entry point")
    void aNullRequestIsRefusedByName() {
        ReportExecutionService service = serviceAt(MID_JULY);

        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> service.resolveReportName(null))
                .withMessage("request must not be null");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> service.resolveConfirmation(null))
                .withMessage("request must not be null");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> service.resolveRange(
                        null, ReportExecutionService.MONTHLY_REPORT_NAME))
                .withMessage("request must not be null");
    }
}
