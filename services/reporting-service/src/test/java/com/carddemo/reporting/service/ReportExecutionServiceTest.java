package com.carddemo.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.common.validation.DateEditValidator.LanguageEnvironmentResult;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.reporting.dto.ReportRequest;
import com.carddemo.reporting.dto.ReportSubmissionResponse;
import com.carddemo.reporting.mapper.ReportBandLayouts;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionDoesNotExistException;
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
 * <h2>There is no golden master for this class, and that is stated rather than glossed</h2>
 *
 * <p>Trade-offs: the two report generators this package holds are driven by batch programs and do
 * have a byte-for-byte oracle. {@code CORPT00C} has none. L83 to L85 of {@code tests/README.md}
 * records that the online programs cannot be run end to end without a CICS runtime, which the runner
 * does not have, and that only their extractable field-validation logic is therefore unit-tested.
 * Parity for every case below consequently rests on transcribed logic plus the copybook, job and
 * resource-definition contracts each case cites by line -- a weaker footing than a captured artifact,
 * and one that raises rather than lowers what the citations have to carry. No case here should be
 * read as comparing output against a recorded baseline, because none of them can.
 *
 * <h2>Alternatives Considered: a stubbed orchestration client rather than an emulator</h2>
 *
 * <p>Driving these cases against an emulated orchestrator was considered and rejected, and the
 * module settles it rather than preference. The emulator module is absent from
 * {@code services/reporting-service/pom.xml} by design, where the dependency block records the
 * omission deliberately, and the register of deliberate omissions in
 * {@code src/test/resources/application-test.yml} states that no test in this module loads a context
 * instantiating the classes that read an execution identifier or a bucket, so the orchestration call
 * is proven by a unit test verifying the request the service builds against a stubbed client with no
 * framework context at all. Two further objections stand on their own. An emulator converts a
 * by-design absence into a hard failure on any host without one, so a case about which input the
 * request carries would fail for a reason unrelated to the input. And an emulator reports the
 * argument's correctness only as the absence of a refusal, whereas the argument IS the subject here:
 * a captor makes the state machine, the execution name and the input document each independently
 * assertable, which is what the cases below actually do.
 *
 * <h2>Assumptions: this is the one class in the package whose clock is genuinely consulted</h2>
 *
 * <p>The request edge resolves a preset exactly once, and it does so through an injected
 * {@link Clock} rather than an ambient read, so a pinned day yields a predictable pair of bounds. The
 * two generators sit on the other side of that line and must never consult a clock at all, because a
 * run whose range came from a clock could not be repeated to the same output. The baseline holds the
 * same division: {@code app/jcl/TRANREPT.jcl} fixes the selection range as two literals in its sort
 * symbol table at L43 and L44, and {@code app/cbl/CBTRN03C.cbl} receives its range as a parameter
 * record it reads at L220 rather than reading a date of its own. An injected date is what makes a
 * rerun reproducible in both systems. {@code TransactionReportServiceTest} and
 * {@code StatementServiceTest} each assert their subject declares no clock on any field, constructor
 * or method; this class is the counterpart that pins what the permitted clock is for, and the case
 * below asserts the generator side of the same line from here.
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

    // WHY : Assumptions: the three identifiers below are OPAQUE STAND-INS and are deliberately not
    //       written in the provider's resource-identifier form. The module's own test profile settles
    //       this rather than taste: the register of deliberate omissions in
    //       src/test/resources/application-test.yml declines to carry an orchestrator execution
    //       identifier at all and records that an identifier of that shape is prohibited in this
    //       directory unconditionally, even inside a comment, so that a scan of the directory has
    //       nothing to report. A stand-in cannot be mistaken for a reachable location, which is the
    //       whole property that entry buys.
    // WHY : Assumptions: the SHAPE is still load-bearing and is not free to be arbitrary. The service
    //       derives an execution handle by locating the machine segment in the configured identifier
    //       and substituting the execution segment for it, and it answers with nothing at all when the
    //       segment is absent or the part after it holds a further colon. The token below therefore
    //       keeps that segment and gives it a colon-free tail, so the derivation under test is
    //       exercised rather than short-circuited into its null arm.
    /** The state machine this class hands the service, standing in for a provisioned one. */
    private static final String STATE_MACHINE_ARN =
            "stub-orchestrator:stateMachine:carddemo-transaction-report-under-test";

    /** The handle the stubbed orchestrator answers a started run with. */
    private static final String EXECUTION_ARN =
            "stub-orchestrator:execution:carddemo-transaction-report-under-test:1";

    /**
     * The handle prefix the service composes an execution handle under.
     *
     * <p>Assumptions: derived by hand from {@link #STATE_MACHINE_ARN} rather than read from the service, so
     * a case asserting the composed handle is comparing against an independently written value.</p>
     */
    private static final String EXECUTION_ARN_PREFIX =
            "stub-orchestrator:execution:carddemo-transaction-report-under-test:";

    /** The execution input this service composes, as the describe path expects to read it back. */
    private static final String RECOGNISED_INPUT =
            "{\"reportType\":\"monthly\",\"startDate\":\"2022-07-01\",\"endDate\":\"2022-07-31\"}";

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
     *
     * <p>This case takes no parameter and yields no value.</p>
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
     *
     * <p>This case takes no parameter and yields no value.</p>
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
     * <p>Purpose: the reference derives the end bound over L213 to L238 of
     * {@code app/cbl/CORPT00C.cbl}, and the derivation is four steps rather than a lookup. L223 moves
     * one into the day, L224 adds one to the month, L225 to L228 roll the year when the incremented
     * month exceeds twelve, and L229 to L230 take the date of the integer of that date minus one. That
     * lands on the LAST day of the CURRENT month. The start bound is built alongside it at L217 to
     * L219 by moving a literal day of one into the current year and month.</p>
     *
     * <p>Assumptions: the monthly preset is a WHOLE calendar month, which is recorded on the
     * production method as correction F1 and is asserted here rather than assumed. A peer document of
     * this program reads the same span as ending on the current day and describes the preset as
     * asymmetric with the yearly one; the four steps above admit no such reading, because nothing in
     * them carries the current day forward -- L223 overwrites it with one before the arithmetic
     * begins. First-hand program text supersedes derived prose, so the assertions below are taken from
     * L223 to L230 and the peer reading is not reconciled with.</p>
     *
     * <p>Assumptions: the five instants below are the five outcomes the derivation can get wrong, and
     * a single mid-year instant exercises none of them. A 31-day month is the case a naive
     * add-a-month-and-subtract-a-day would also pass, so it is the control rather than the evidence. A
     * 30-day month is included so that a hard-coded thirty-one fails. February in a NON-leap year and
     * February in a leap year are separated deliberately: a single February row would let an
     * implementation that always answered twenty-eight, or always twenty-nine, pass on whichever one
     * it was pinned to, and the pair is what forces the length to be computed. December is where the
     * year roll at L225 to L228 is the only thing standing between the answer and an invalid month
     * thirteen.</p>
     *
     * @param pinned the instant the clock reports, chosen to sit inside the month under test
     * @param expectedStart the first day the range covers, always the first of the pinned month
     * @param expectedEnd the last day the range covers, which is the pinned month's own final day and
     *     never the pinned day itself
     */
    @ParameterizedTest
    @CsvSource({
        "2022-07-18T12:00:00Z,2022-07-01,2022-07-31",
        "2022-06-30T00:00:00Z,2022-06-01,2022-06-30",
        "2023-02-10T23:59:59Z,2023-02-01,2023-02-28",
        "2024-02-10T23:59:59Z,2024-02-01,2024-02-29",
        "2022-12-05T00:00:00Z,2022-12-01,2022-12-31"
    })
    @DisplayName("the monthly preset covers a whole calendar month, over both Februaries and a year roll")
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
     *
     * <p>This case takes no parameter and yields no value.</p>
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
     * Neither preset stops at the pinned day, so the two are shaped alike rather than asymmetrically.
     *
     * <p>Purpose: this case exists to deny one specific misreading directly instead of leaving it to
     * be inferred from the two cases above. Both presets open on a period boundary and close on the
     * same period's own final boundary: the monthly one at L217 to L219 and L223 to L230 of
     * {@code app/cbl/CORPT00C.cbl}, the yearly one at L243 to L246 and L250 to L251. Neither carries
     * the current day into its end bound, so for every day of a period except its last the resolved
     * end bound lies AFTER the day the clock reports, and that is a property of both rather than a
     * quirk of one.</p>
     *
     * <p>Assumptions: the pinned instant is deliberately mid-period on both scales -- the pinned day
     * of July is neither the last day of its month nor the last day of its year -- so both presets
     * have a strictly later end bound to be wrong about. A case pinned to the thirty-first of December
     * would make an end-bound-is-today implementation agree with a whole-period one on both presets at
     * once, and would assert nothing.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("both presets end after the pinned day, so neither is month-limited to it")
    void neitherPresetEndsOnThePinnedDay() {
        ReportExecutionService service = serviceAt(MID_JULY);
        LocalDate pinnedDay = LocalDate.of(2022, 7, 18);

        ReportExecutionService.DateRange monthly = service.resolveRange(
                selection(MARK, null, null, MARK), ReportExecutionService.MONTHLY_REPORT_NAME);
        ReportExecutionService.DateRange yearly = service.resolveRange(
                selection(null, MARK, null, MARK), ReportExecutionService.YEARLY_REPORT_NAME);

        // WHY : Assumptions: the comparison is strictly AFTER rather than not-before, because an
        //       implementation that resolved the end bound to the pinned day would satisfy a
        //       not-before test while being exactly the reading this case exists to rule out.
        assertThat(monthly.end())
                .as("the monthly end bound is the month's own last day, later than the pinned day")
                .isAfter(pinnedDay);
        assertThat(yearly.end())
                .as("the yearly end bound is the year's own last day, later than the pinned day")
                .isAfter(pinnedDay);

        // WHY : Assumptions: both presets are asserted to open on a first-of-period boundary in the
        //       same case as their end bounds, because the symmetry claim is about the SHAPE of the
        //       pair and a case that checked only the end bounds would leave the openings unpinned.
        assertThat(monthly.start().getDayOfMonth())
                .as("the monthly range opens on the first of the month, per L219")
                .isEqualTo(1);
        assertThat(yearly.start())
                .as("the yearly range opens on the first of January, per L245 and L246")
                .isEqualTo(LocalDate.of(2022, 1, 1));
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
     *
     * <p>This case takes no parameter and yields no value.</p>
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
     * <p>Refactoring Rationale: the third and fourth rows expected the assembled-date sentence and now
     * expect a COMPONENT sentence, and the change is the point of the row rather than an adjustment to
     * it. {@code 2022-07-XX} has a non-numeric day and {@code 2022-13-01} a month above twelve, and the
     * reference answers each from its component tier at L340 and L357 of
     * {@code app/cbl/CORPT00C.cbl} rather than from its assembled tier -- which the target could not
     * do while it went straight from the width test to the shared edit. The two rows added below them
     * hold the other side of that boundary: {@code 2022-02-30} has a month and a day the component
     * tier admits and a date the calendar does not, so it must still carry the assembled sentence, at
     * each end of the range. Without those two the tier could be strengthened past the reference's own
     * tests and no case would notice.</p>
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
        "2022-07-XX,2022-07-31,startDate,NOT_OK,Start Date - Not a valid Day...",
        "2022-07-01,2022-13-01,endDate,NOT_OK,End Date - Not a valid Month...",
        "2022-7-1,2022-07-31,startDate,NOT_OK,Start Date - Not a valid date...",
        "2022-02-30,2022-07-31,startDate,NOT_OK,Start Date - Not a valid date...",
        "2022-07-01,2022-02-30,endDate,NOT_OK,End Date - Not a valid date..."
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
     * Each of the reference's six component sentences is reachable, at the bound that carries it.
     *
     * <p>Purpose: this is the case that decides whether the reference's component tier exists in the
     * target at all. Ten of the fourteen date sentences were unreachable before it, so a caller
     * stating a month of thirteen, a day of thirty-two or a non-numeric year received the single
     * assembled-date sentence for all three -- while the published contract enumerated all fourteen as
     * the catalog this operation may carry. The six rows below are the six arms of
     * {@code app/cbl/CORPT00C.cbl} L328 to L379, three per bound, each asserted against the constant
     * the production class publishes rather than against a literal, so a drift in either the constant
     * or the selection fails here.</p>
     *
     * <p>Assumptions: the field is asserted to be the BOUND on every row, never a component name. The
     * request publishes {@code startDate} and {@code endDate} and the browser's field-to-control map
     * recognises only those, so a synthetic per-component field identity would arrive with no control
     * to mark. The component travels in the message; the bound travels in the field. Both halves are
     * asserted on each row because asserting either alone would let the other regress.</p>
     *
     * <p>Assumptions: the two year rows carry a letter in the year, which the request schema's ISO
     * pattern would refuse at the wire. They are here because this case drives the service directly,
     * which is the level at which the selection lives, and because the sentence is the correct in-catalog
     * answer for a caller that reaches the service with such a value -- the read operations take their
     * bounds as unannotated query parameters. The reachability arithmetic this implies is registered as
     * D-REPORT-DATE-MESSAGE-REACH.</p>
     *
     * @param startDate the lower bound as stated
     * @param endDate the upper bound as stated
     * @param expectedField the bound the refusal must name
     * @param expectedMessage the reference sentence the refusal must carry, verbatim
     */
    @ParameterizedTest
    @CsvSource({
        "2022-13-01,2022-07-31,startDate,Start Date - Not a valid Month...",
        "2022-07-32,2022-07-31,startDate,Start Date - Not a valid Day...",
        "2O22-07-01,2022-07-31,startDate,Start Date - Not a valid Year...",
        "2022-07-01,2022-99-31,endDate,End Date - Not a valid Month...",
        "2022-07-01,2022-07-99,endDate,End Date - Not a valid Day...",
        "2022-07-01,20x2-07-31,endDate,End Date - Not a valid Year..."
    })
    @DisplayName("all six of the reference's component sentences are selected, at the bound that owns them")
    void eachComponentSentenceIsReachable(String startDate, String endDate, String expectedField,
            String expectedMessage) {
        ReportExecutionService service = serviceAt(MID_JULY);
        ReportRequest request = customRange(startDate, endDate, MARK);

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.resolveRange(
                        request, ReportExecutionService.CUSTOM_REPORT_NAME))
                .withMessage(expectedMessage)
                .satisfies(refused -> {
                    assertThat(refused.field())
                            .as("the field names the bound, because that is the control a client marks")
                            .isEqualTo(expectedField);
                    assertThat(refused.state())
                            .as("a stated but faulty component is not-ok, never blank")
                            .isEqualTo(FieldValidationFlag.NOT_OK);
                });
    }

    /**
     * Asserts that when the two bounds fault in DIFFERENT tiers, the earlier tier names the refusal
     * whichever bound it belongs to.
     *
     * <p>Purpose: the reference does not edit a bound at a time. {@code app/cbl/CORPT00C.cbl} runs each
     * tier across both bounds before opening the next -- the six emptiness arms at L258 to L299, then
     * the six component arms at L331 to L374, then the assembled edit for the lower bound at L399 and
     * for the upper at L419. So the tier a fault sits in decides which of two simultaneous faults is
     * named, and the bound it sits on does not.</p>
     *
     * <p>Assumptions: the two rows below are the same pair of faults in both arrangements, which is
     * what makes the case an ordering assertion rather than two selection assertions. {@code 2022-02-30}
     * passes the component tier -- day thirty is inside the reference's one-to-thirty-one range -- and is
     * refused only by the assembled edit, because February has no thirtieth. {@code 2022-13-15} is
     * refused by the component tier, because month thirteen is outside one to twelve. Whichever bound
     * carries the month, the month is named: editing a bound to completion first would name the OTHER
     * bound on one of these two rows, and only one sentence ever reaches a caller.</p>
     *
     * <p>Trade-offs: this asserts the message and the field rather than an ordering of internal calls.
     * A call-order assertion would pass on an implementation that produced the wrong sentence and fail
     * on a correct one that reorganised its methods, which is the wrong way round for a contract whose
     * whole observable surface is which sentence a client is shown.</p>
     *
     * @param startDate the lower bound as stated
     * @param endDate the upper bound as stated
     * @param expectedField the bound whose tier is reached first, and which the refusal must name
     * @param expectedMessage the reference sentence the refusal must carry, verbatim
     */
    @ParameterizedTest
    @CsvSource({
        "2022-02-30,2022-13-15,endDate,End Date - Not a valid Month...",
        "2022-13-15,2022-02-30,startDate,Start Date - Not a valid Month..."
    })
    @DisplayName("the earlier tier names the refusal, whichever bound carries it")
    void theEarlierTierNamesTheRefusal(String startDate, String endDate, String expectedField,
            String expectedMessage) {
        ReportExecutionService service = serviceAt(MID_JULY);
        ReportRequest request = customRange(startDate, endDate, MARK);

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.resolveRange(
                        request, ReportExecutionService.CUSTOM_REPORT_NAME))
                .withMessage(expectedMessage)
                .satisfies(refused -> assertThat(refused.field())
                        .as("the component tier runs for both bounds before either assembled edit")
                        .isEqualTo(expectedField));
    }

    /**
     * The component tier is no stronger than the reference's, so six faults still reach the edit.
     *
     * <p>Purpose: this is the counterweight to the case above and it is the one that stops the tier
     * being "improved". Every row below states a component the reference's arms ADMIT -- its month arm
     * tests {@code NOT NUMERIC OR > '12'} and so passes {@code 00}, its day arm tests
     * {@code NOT NUMERIC OR > '31'} and so passes {@code 00} and a thirty-first of a thirty-day month,
     * and its year arm tests {@code NOT NUMERIC} alone and so passes {@code 0000}. Each therefore has
     * to arrive at the assembled edit and carry the assembled-date sentence, exactly as it did before
     * the tier existed. A lower bound added to either arm, or a calendar test moved into the tier,
     * would read better and would fail these rows.</p>
     *
     * <p>Assumptions: the three impossible calendar days are chosen to cover the three ways a day can
     * be impossible while remaining under thirty-one -- a thirtieth of February, a thirty-first of a
     * thirty-day month, and a twenty-ninth of February in a common year -- so the row set does not
     * depend on one arithmetic path in the shared edit.</p>
     *
     * @param bound the lower bound as stated, which is the bound each row makes faulty
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "2022-00-15", "2022-03-00", "0000-03-15", "2022-02-30", "2022-04-31", "2023-02-29"
    })
    @DisplayName("a fault the reference's component arms admit still carries the assembled sentence")
    void aFaultTheComponentTierAdmitsReachesTheAssembledEdit(String bound) {
        ReportExecutionService service = serviceAt(MID_JULY);
        ReportRequest request = customRange(bound, "2023-12-31", MARK);

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.resolveRange(
                        request, ReportExecutionService.CUSTOM_REPORT_NAME))
                .withMessage(ReportExecutionService.MESSAGE_START_DATE_INVALID)
                .satisfies(refused -> assertThat(refused.field())
                        .isEqualTo(ReportExecutionService.START_DATE_FIELD));
    }

    /**
     * A well-formed date below the supported calendar floor is accepted, at both bounds.
     *
     * <p>Purpose: the reference TOLERATES the unsupported-range verdict on both bounds -- L399 and
     * L419 of {@code app/cbl/CORPT00C.cbl} each read
     * {@code IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'} -- so a date under the floor is processed rather
     * than declined. This case pins that at the extremes on both ends of the range, which is what
     * stops a floor being introduced here in the belief that declining such a date is a correction.
     * Declining it would change behaviour, which transformation rule T9 does not admit.</p>
     *
     * <p>Assumptions: the highest admissible year is included alongside the two low ones because a
     * ceiling is as easy to introduce accidentally as a floor, and neither end has one in the
     * reference. The pair is edited as a range rather than as two independent bounds so the ordering
     * test is crossed as well.</p>
     *
     * @param startDate the lower bound as stated
     * @param endDate the upper bound as stated
     */
    @ParameterizedTest
    @CsvSource({
        "1582-10-14,1582-10-15",
        "0001-01-01,9999-12-31",
        "1581-01-01,2022-07-31"
    })
    @DisplayName("a bound below the calendar floor is processed, because both reference call sites forgive it")
    void aBoundBelowTheCalendarFloorIsProcessed(String startDate, String endDate) {
        ReportExecutionService.DateRange range = serviceAt(MID_JULY).resolveRange(
                customRange(startDate, endDate, MARK),
                ReportExecutionService.CUSTOM_REPORT_NAME);

        assertThat(range.start()).isEqualTo(LocalDate.parse(startDate));
        assertThat(range.end()).isEqualTo(LocalDate.parse(endDate));
    }

    /**
     * A year of zero is refused, which is the one low-end verdict the reference does not forgive.
     *
     * <p>Purpose: this is the boundary that separates the tolerance above from an absence of rules. The
     * shared edit reports an era-zero year as its own outcome rather than as the unsupported-range one,
     * and the reference's tolerance names only the latter by number, so a bound of {@code 0000-01-01}
     * is refused where {@code 0001-01-01} is accepted. The two are one day apart in the calendar the
     * date type models and on opposite sides of this rule, which is why they are asserted together
     * rather than in separate cases.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an era-zero year is refused where the year above it is accepted")
    void anEraZeroYearIsRefused() {
        LanguageEnvironmentResult verdict = DateEditValidator.evaluateWithLanguageEnvironment(
                "0000-01-01", DateEditValidator.DATE_FORMAT_MASK);

        assertThat(verdict.unsupportedRange())
                .as("era zero is NOT the forgiven outcome, which is why the tolerance misses it")
                .isFalse();

        ReportExecutionService service = serviceAt(MID_JULY);

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.resolveRange(
                        customRange("0000-01-01", "2022-07-31", MARK),
                        ReportExecutionService.CUSTOM_REPORT_NAME))
                .withMessage(ReportExecutionService.MESSAGE_START_DATE_INVALID)
                .satisfies(refused -> assertThat(refused.field())
                        .isEqualTo(ReportExecutionService.START_DATE_FIELD));

        assertThat(serviceAt(MID_JULY).resolveRange(
                        customRange("0001-01-01", "2022-07-31", MARK),
                        ReportExecutionService.CUSTOM_REPORT_NAME).start())
                .as("the year above era zero is accepted, so the refusal is the era rule and not a floor")
                .isEqualTo(LocalDate.of(1, 1, 1));
    }

    /**
     * The shared edit is one entry point, and every report surface reaches the same one.
     *
     * <p>Purpose: the two read operations and the artifact collection each parsed their bounds with a
     * bare calendar parse, so the three surfaces disagreed about the same value. This case asserts the
     * shared entry point directly -- the one {@code ReportController} now delegates all six of its
     * bound parses to -- so the agreement is pinned at the method the surfaces share rather than only
     * through each surface's own transport case.</p>
     *
     * <p>Assumptions: the field constants are asserted to be the strings every surface publishes,
     * because the sentence selection is by field equality. A surface that spelled the lower bound
     * differently would be answered with the UPPER bound's sentence and nothing would fail, which is
     * the failure this assertion exists to make impossible.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("one shared bound edit serves every surface, and the two field names are its selector")
    void oneSharedBoundEditServesEverySurface() {
        assertThat(ReportExecutionService.START_DATE_FIELD).isEqualTo("startDate");
        assertThat(ReportExecutionService.END_DATE_FIELD).isEqualTo("endDate");

        assertThat(ReportExecutionService.editStatedBound(
                        "1582-10-14", ReportExecutionService.START_DATE_FIELD))
                .as("the forgiven range verdict is forgiven here too, on every surface")
                .isEqualTo(LocalDate.of(1582, 10, 14));

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> ReportExecutionService.editStatedBound(
                        "0000-01-01", ReportExecutionService.END_DATE_FIELD))
                .withMessage(ReportExecutionService.MESSAGE_END_DATE_INVALID)
                .satisfies(refused -> assertThat(refused.field())
                        .as("the upper bound's sentence is selected by the upper bound's field name")
                        .isEqualTo(ReportExecutionService.END_DATE_FIELD));

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> ReportExecutionService.editStatedBound(
                        null, ReportExecutionService.START_DATE_FIELD))
                .withMessage(ReportExecutionService.MESSAGE_START_DATE_MONTH_EMPTY)
                .satisfies(refused -> assertThat(refused.state())
                        .as("an absent bound is blank, so a client still renders the asterisk marker")
                        .isEqualTo(FieldValidationFlag.BLANK));
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
     *
     * <p>This case takes no parameter and yields no value.</p>
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
     *
     * <p>This case takes no parameter and yields no value.</p>
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
     * input and the fact that the returned handle is the name the run was started UNDER -- because each
     * can be wrong on its own. A correct input sent to the wrong machine starts nothing; a correct
     * machine with a colliding name is refused; and a response whose handle was dropped leaves a caller
     * with nothing to poll.</p>
     *
     * <p>⚠️ Refactoring Rationale: the returned handle is compared with the name that was SENT, and it
     * was compared with the orchestrator's execution ARN. A review found the two halves of the lifecycle
     * unable to meet -- {@code describeExecution} is addressed by name and composes the ARN itself, so
     * the value this response carried was the one value it does not accept. Comparing the answer with
     * the captured request is what makes the pairing the subject of the assertion: an implementation that
     * answered a name of its own, or the ARN again, fails here rather than passing on a literal that
     * agrees with nothing.</p>
     *
     * <p>Assumptions: the execution NAME is asserted by value, because its whole purpose is that a
     * retry after an abandoned call collides rather than starting the report twice. A name carrying a
     * random component would satisfy any assertion about its presence while defeating that purpose,
     * so presence is not what is checked.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
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

        assertThat(accepted.executionName()).isEqualTo(started.getValue().name());
        assertThat(accepted.reportName()).isEqualTo(ReportExecutionService.MONTHLY_REPORT_NAME);
        assertThat(accepted.startDate()).isEqualTo("2022-07-01");
        assertThat(accepted.endDate()).isEqualTo("2022-07-31");
    }

    /**
     * The correlation identity of the submitting request crosses into the execution input.
     *
     * <p>Purpose: the input carried the report type and the two bounds and nothing else, so the
     * correlation identity stopped at the asynchronous boundary. It was on the request, in every log
     * line the request emitted and on the response header, and then it was gone: a stored input named
     * the report and the range but not the request that ordered it, so neither direction of the
     * lookup an operator actually performs -- identity to run, run to identity -- could be walked.
     * This case asserts the member is present, is the identity the context held, and is the fourth
     * member rather than a replacement for one of the three the state machine reads.</p>
     *
     * <p>Assumptions: the identity is placed in the logging context directly rather than by driving a
     * request through the shared filter, because the filter is exercised by its own tests in
     * {@code common-lib} and what this case needs is the contract between the two: the key the filter
     * publishes is read here, so a rename on either side fails this. The context is cleared in a
     * {@code finally} block because the value would otherwise leak into every later case on this
     * thread and make the three-member assertion above pass or fail by ordering.</p>
     *
     * <p>Assumptions: the absent case is asserted in the SAME case rather than in one of its own,
     * because the two readings are only meaningful against each other -- an implementation that
     * always appended a member, and one that never did, each satisfy half of this and neither
     * carries the identity. The absent reading is also the one every other case in this class relies
     * on, since none of them establishes a context.</p>
     *
     * <p>This case takes no parameter and yields no value beyond the assertions it makes.</p>
     *
     * @throws Exception if capturing the interaction raises, which fails the case rather than being
     *     reported as an absent member
     */
    @Test
    @DisplayName("a conforming correlation identity becomes the input's fourth member, and no member when absent")
    void aCorrelationIdentityCrossesIntoTheExecutionInput() throws Exception {
        ReportExecutionService service = serviceAt(MID_JULY);
        when(this.sfn.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().executionArn(EXECUTION_ARN).build());

        try {
            org.slf4j.MDC.put(com.carddemo.common.web.CorrelationIdFilter.CORRELATION_ID_MDC_KEY,
                    "CDQATRACE20260822AA14");

            service.start(selection(MARK, null, null, "Y"),
                    ReportExecutionService.MONTHLY_REPORT_NAME,
                    LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31), NO_SUPPLIED_KEY);

            ArgumentCaptor<StartExecutionRequest> withIdentity =
                    ArgumentCaptor.forClass(StartExecutionRequest.class);
            verify(this.sfn).startExecution(withIdentity.capture());

            // WHY : Assumptions: the whole input is compared rather than searched for the member,
            //       because the three members the state machine reads are a contract with the
            //       infrastructure that declares it -- an implementation that carried the identity by
            //       renaming or reordering one of them would satisfy a containment assertion and would
            //       break every run. Comparing the document makes the addition visibly additive.
            assertThat(withIdentity.getValue().input()).isEqualTo(
                    "{\"reportType\":\"monthly\",\"startDate\":\"2022-07-01\","
                            + "\"endDate\":\"2022-07-31\","
                            + "\"correlationId\":\"CDQATRACE20260822AA14\"}");
        } finally {
            org.slf4j.MDC.remove(com.carddemo.common.web.CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        }

        ReportExecutionService withoutContext = serviceAt(MID_JULY);
        withoutContext.start(selection(MARK, null, null, "Y"),
                ReportExecutionService.MONTHLY_REPORT_NAME,
                LocalDate.of(2022, 8, 1), LocalDate.of(2022, 8, 31), NO_SUPPLIED_KEY);

        ArgumentCaptor<StartExecutionRequest> withoutIdentity =
                ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(this.sfn, times(2)).startExecution(withoutIdentity.capture());

        assertThat(withoutIdentity.getValue().input())
                .as("with no request context the member is omitted, never emitted empty")
                .isEqualTo("{\"reportType\":\"monthly\",\"startDate\":\"2022-08-01\","
                        + "\"endDate\":\"2022-08-31\"}")
                .doesNotContain("correlationId");
    }

    /**
     * A malformed correlation identity is left out of the input rather than carried into it.
     *
     * <p>Purpose: the input is assembled by concatenation, so this is the case that keeps that safe.
     * The value is re-tested against the rule the shared filter publishes instead of being trusted
     * because it is in the context, and the two readings below are the two that matter: a value
     * carrying the quotation mark would end the member early and change the document's shape, and a
     * value beyond the permitted length is refused for the same reason the filter refuses it. Either
     * would otherwise reach the orchestrator inside a document that no longer parses.</p>
     *
     * <p>Assumptions: the assertion is that the input is EXACTLY the three-member document, not
     * merely that it excludes the offending characters. An implementation that sanitised the value
     * and carried what was left would pass a containment assertion while putting a value into a
     * stored input that correlates with nothing and looks like it correlates with something.</p>
     *
     * @param unusable a context value the shared conformance rule does not admit
     * @throws Exception if capturing the interaction raises
     */
    @ParameterizedTest
    @ValueSource(strings = {"has\"quote", "has\\solidus", "has space", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    @DisplayName("a correlation identity the shared rule refuses is omitted from the input")
    void aMalformedCorrelationIdentityIsOmitted(String unusable) throws Exception {
        ReportExecutionService service = serviceAt(MID_JULY);
        when(this.sfn.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().executionArn(EXECUTION_ARN).build());

        try {
            org.slf4j.MDC.put(com.carddemo.common.web.CorrelationIdFilter.CORRELATION_ID_MDC_KEY,
                    unusable);

            service.start(selection(MARK, null, null, "Y"),
                    ReportExecutionService.MONTHLY_REPORT_NAME,
                    LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31), NO_SUPPLIED_KEY);
        } finally {
            org.slf4j.MDC.remove(com.carddemo.common.web.CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        }

        ArgumentCaptor<StartExecutionRequest> started =
                ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(this.sfn).startExecution(started.capture());

        assertThat(started.getValue().input()).isEqualTo(
                "{\"reportType\":\"monthly\",\"startDate\":\"2022-07-01\","
                        + "\"endDate\":\"2022-07-31\"}");
    }

    /**
     * An accepted submission is journalled, and its record carries the same fields as the duplicate's.
     *
     * <p>Purpose: the only submission record this method raised was the duplicate one, so a resubmitted
     * request was traceable and a first-time one was not -- the inverse of what an operator needs,
     * because the first-time submission is the one that starts work. This case asserts the accepted
     * record exists, is raised at information level, and names the five values that let it be joined to
     * the duplicate record and to the run: the report, both bounds, the submission key and the
     * execution name.</p>
     *
     * <p>Assumptions: the case also asserts what the record must NOT contain. The submission surface
     * receives no customer, account or card value, so none can appear here -- but a later change that
     * logged the whole request would satisfy every positive assertion above and quietly put caller data
     * into an operational record. Asserting the absence is what makes that change fail a case rather
     * than pass review.</p>
     *
     * <p>Assumptions: the correlation identity is asserted to be ABSENT from the message text. It
     * reaches the record through the logging context, which the structured encoder renders alongside
     * the message, so naming it in the message as well would put one value in a line twice and would
     * disagree with every other event this class raises.</p>
     *
     * <p>This case takes no parameter and yields no value beyond the assertions it makes.</p>
     *
     * @throws Exception if capturing the emitted records raises
     */
    @Test
    @DisplayName("an accepted submission raises an information record naming the run and its range")
    void anAcceptedSubmissionIsJournalled() throws Exception {
        ReportExecutionService service = serviceAt(MID_JULY);
        when(this.sfn.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().executionArn(EXECUTION_ARN).build());

        // WHY : Assumptions: the capture is attached to this service's own logger rather than to the
        //       root logger, so the assertion that no record mentions a card or an account is a
        //       statement about this class and not about whatever else the test runtime logs. The
        //       appender is detached and the level restored in a finally block because a logger is
        //       process-wide state and leaving either in place would make later cases in the module
        //       depend on the order they ran in.
        ch.qos.logback.classic.Logger serviceLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ReportExecutionService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
                new ch.qos.logback.core.read.ListAppender<>();
        ch.qos.logback.classic.Level restore = serviceLogger.getLevel();
        captured.start();
        serviceLogger.addAppender(captured);
        serviceLogger.setLevel(ch.qos.logback.classic.Level.INFO);

        ReportSubmissionResponse accepted;
        try {
            accepted = service.start(selection(MARK, null, null, "Y"),
                    ReportExecutionService.MONTHLY_REPORT_NAME,
                    LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31), "QA0822");
        } finally {
            serviceLogger.detachAppender(captured);
            captured.stop();
            serviceLogger.setLevel(restore);
        }

        java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> submissionRecords =
                captured.list.stream()
                        .filter(event -> event.getFormattedMessage()
                                .contains("event=report.submission.accepted"))
                        .toList();

        assertThat(submissionRecords)
                .as("exactly one accepted record per accepted submission, so a count is a run count")
                .hasSize(1);
        assertThat(submissionRecords.get(0).getLevel())
                .as("nothing failed, so the level matches the duplicate record beside it")
                .isEqualTo(ch.qos.logback.classic.Level.INFO);
        assertThat(submissionRecords.get(0).getFormattedMessage())
                .contains("reportName=" + ReportExecutionService.MONTHLY_REPORT_NAME)
                .contains("startDate=2022-07-01")
                .contains("endDate=2022-07-31")
                .contains("submission=QA0822")
                .contains("execution=" + accepted.executionName())
                .doesNotContain("correlationId");
    }

    /**
     * One resolved range reaches both of the baseline's two range channels, for all three types.
     *
     * <p>Purpose: the baseline carries its range on TWO structurally distinct channels and writes both
     * from a single value. {@code PARM-START-DATE-1} at L106 and {@code PARM-END-DATE-1} at L111 of
     * {@code app/cbl/CORPT00C.cbl} sit inside the two cards that follow the
     * {@code "//STEP05R.SYMNAMES DD *"} override at L98, so they are sort SYMBOLS and therefore the
     * record-selection channel; {@code PARM-START-DATE-2} at L118 and {@code PARM-END-DATE-2} at L120
     * sit inside the card that follows the {@code "//STEP10R.DATEPARM DD *"} override at L116, so they
     * are the parameter RECORD and therefore the report-heading channel. Every one of the three types
     * writes both: the monthly arm at L220 to L221 and L235 to L236, the yearly arm at L247 to L248 and
     * L252 to L253, and the custom arm at L429 to L432, which writes both pairs BEFORE moving the
     * literal {@code 'Custom'} into the report name at L433. This case asserts the target keeps the two
     * channels agreeing.</p>
     *
     * <p>Assumptions: the parameter-record channel's layout is corroborated independently, which is
     * why the two channels can be treated as carrying the same pair rather than merely similar values.
     * The group {@code FILLER-3} at L117 to L121 is a ten-character start bound, EXACTLY ONE space at
     * L119, a ten-character end bound and fifty-nine characters of padding, which is 10 + 1 + 10 + 59 =
     * 80 and therefore a whole record of the {@code RECORDSIZE(80)} the queue declares at L502 of
     * {@code app/csd/CARDDEMO.CSD}. The consuming program declares the same record from the other side:
     * {@code app/cbl/CBTRN03C.cbl} holds {@code 01 FD-DATEPARM-REC PIC X(80).} at its L88 and reads it
     * at L220 to L221 into the twenty-one-character working copy at L122 to L125, which is a
     * ten-character field, a one-character filler and a ten-character field in that order. Two
     * independently written declarations agreeing byte for byte is what makes the channel's shape a
     * fact rather than a reading.</p>
     *
     * <p>Assumptions: one artifact of the baseline is recorded here as an OBSERVATION and is neither
     * acted on nor described as a defect, because {@code app/jcl/**} is reference material this
     * migration reads and never edits. The step-qualified override at L98 names {@code STEP05R}, and
     * {@code app/jcl/TRANREPT.jcl} declares that step name TWICE -- at L23 as
     * {@code EXEC PROC=REPROC} and again at L37 as {@code EXEC PGM=SORT} -- so which step the symbol
     * override attaches to is genuinely ambiguous in the baseline. The other override at L116 has no
     * such ambiguity: {@code STEP10R} is declared once, at L59. The observation is noted so that a
     * reader who finds the target carrying one unambiguous range knows the ambiguity was seen and left
     * where it is.</p>
     *
     * <p>Assumptions: the target's two channels are the execution input, which the generator filters
     * records on, and the accepted response, which the caller heads its report from. They are the same
     * two roles under different transports, so a case asserting only one of them would leave the other
     * free to carry a different range -- which is exactly the exposure the batch-only path has, where
     * {@code app/jcl/TRANREPT.jcl} fixes the selection range in its own symbols at L43 and L44 while
     * {@code app/cbl/CBTRN03C.cbl} heads the output from the separate parameter record it reads at
     * L220, and nothing reconciles the two.</p>
     *
     * <p>Assumptions: the bounds are asserted to be EQUAL ACROSS the two channels rather than each
     * compared against a literal, which is the stronger of the two available statements. Comparing
     * each against its own expected literal would pass for an implementation that wrote one channel
     * from the resolved range and the other from a second, coincidentally equal computation; asserting
     * the identity is what pins them to one origin. The literals are asserted too, so the pair cannot
     * agree on a wrong value.</p>
     *
     * @param reportName the report name whose channels are under test, one of the three the reference
     *     assigns at L214, L240 and L433
     * @param monthlyMark the monthly selector mark for this case, blank when another type is selected
     * @param yearlyMark the yearly selector mark for this case, blank when another type is selected
     * @param customMark the custom selector mark for this case, blank when another type is selected
     * @param expectedStart the lower bound both channels must carry
     * @param expectedEnd the upper bound both channels must carry
     */
    @ParameterizedTest
    @CsvSource({
        "Monthly,Y,,,2022-07-01,2022-07-31",
        "Yearly,,Y,,2022-01-01,2022-12-31",
        "Custom,,,Y,2019-03-04,2019-03-29"
    })
    @DisplayName("one resolved range reaches the filter channel and the heading channel alike")
    void oneResolvedRangeReachesBothChannels(String reportName, String monthlyMark,
            String yearlyMark, String customMark, String expectedStart, String expectedEnd) {
        ReportExecutionService service = serviceAt(MID_JULY);
        when(this.sfn.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().executionArn(EXECUTION_ARN).build());
        ReportRequest request = new ReportRequest(null, null, null, null, null, null,
                monthlyMark, yearlyMark, customMark, "2019-03-04", "2019-03-29", MARK, null);

        ReportExecutionService.DateRange resolved = service.resolveRange(request, reportName);
        ReportSubmissionResponse accepted = service.start(
                request, reportName, resolved.start(), resolved.end(), NO_SUPPLIED_KEY);

        ArgumentCaptor<StartExecutionRequest> started =
                ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(this.sfn).startExecution(started.capture());

        assertThat(resolved.start()).isEqualTo(LocalDate.parse(expectedStart));
        assertThat(resolved.end()).isEqualTo(LocalDate.parse(expectedEnd));
        assertThat(started.getValue().input())
                .as("the filter channel carries the resolved range")
                .contains("\"startDate\":\"" + expectedStart + "\"")
                .contains("\"endDate\":\"" + expectedEnd + "\"");
        assertThat(accepted.startDate())
                .as("the heading channel opens on the same day the filter channel does")
                .isEqualTo(expectedStart);
        assertThat(accepted.endDate())
                .as("the heading channel closes on the same day the filter channel does")
                .isEqualTo(expectedEnd);
    }

    /**
     * Nothing resembling a job control card image survives the transport change.
     *
     * <p>Refactoring Rationale: the baseline serialises this request as SEVENTEEN eighty-character job
     * control card images, declared as the {@code 05}-level items of the request group at L81 to L127
     * of {@code app/cbl/CORPT00C.cbl} -- at L83, L85, L87, L89, L91, L93, L95, L97, L99, L101, L103,
     * L108, L113, L115, L117, L122 and L124. Three of them are worth naming because the count is easy
     * to get wrong in either direction: L126 and L127 are a redefinition of that same group as a
     * thousand-entry array, not a further card, and the terminator at L124 to L125 holding
     * {@code "/*EOF"} IS one of the seventeen rather than an extra. The write loop reaches it too,
     * because L502 to L505 set the stop flag inside the test while L507 performs the write OUTSIDE it,
     * so the card that satisfied the test is written before the loop ends. Those seventeen serialised
     * writes collapse to ONE typed call here, and this case asserts the collapse is total: not one
     * fragment of the card syntax is reproduced under a new name.</p>
     *
     * <p>Assumptions: three distinct fragments are searched for rather than one, because each would
     * betray a different half-migration. A line opening with the job control prefix would mean the
     * step structure was still being composed as text; the {@code "/*EOF"} sentinel would mean the
     * internal-reader submission protocol was still being spoken to a transport that has no reader;
     * and an eighty-character run of padding would mean the fixed record width of the queue -- the
     * {@code RECORDSIZE(80)} at L502 of {@code app/csd/CARDDEMO.CSD} -- was still being honoured by a
     * transport that imposes no record width at all.</p>
     *
     * <p>Assumptions: every string the operation emits is searched, not just the execution input. The
     * execution name and the accepted response are the other two places a card fragment could
     * plausibly be assembled, so checking the input alone would leave two escape routes open.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("no job control card image, terminator sentinel or fixed-width padding is composed")
    void noJobControlCardImageIsComposed() {
        ReportExecutionService service = serviceAt(MID_JULY);
        when(this.sfn.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().executionArn(EXECUTION_ARN).build());

        ReportSubmissionResponse accepted = service.start(
                selection(MARK, null, null, MARK),
                ReportExecutionService.MONTHLY_REPORT_NAME,
                LocalDate.of(2022, 7, 1),
                LocalDate.of(2022, 7, 31), NO_SUPPLIED_KEY);

        ArgumentCaptor<StartExecutionRequest> started =
                ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(this.sfn).startExecution(started.capture());

        // WHY : Assumptions: the padding probe is a run of eighty spaces rather than a length check,
        //       because the input document is legitimately longer than eighty characters and a length
        //       assertion would therefore say nothing. What would betray a card image is a FIXED-WIDTH
        //       run, which is what the reference's PIC X(80) items produce and what a structured
        //       document never contains.
        String eightyByteRun = " ".repeat(80);
        for (String emitted : java.util.List.of(
                started.getValue().input(),
                started.getValue().name(),
                accepted.reportName(),
                accepted.startDate(),
                accepted.endDate(),
                accepted.executionName())) {
            assertThat(emitted)
                    .as("no job control prefix, no terminator sentinel and no fixed-width padding")
                    .doesNotContain("//")
                    .doesNotContain("/*EOF")
                    .doesNotContain("/*")
                    .doesNotContain(eightyByteRun);
        }

        // WHY : Assumptions: the input is asserted to be a STRUCTURED document naming its three
        //       members, which is the positive half of the same claim. Proving only that card syntax
        //       is absent would also pass for an empty input, and an empty input starts a run that
        //       filters on nothing.
        assertThat(started.getValue().input())
                .startsWith("{")
                .endsWith("}")
                .contains("\"reportType\"", "\"startDate\"", "\"endDate\"");
    }

    /**
     * The request edge STARTS a run and holds nothing it could generate a report with.
     *
     * <p>Purpose: the baseline's request path submits and returns; it does not produce the report. The
     * driver paragraph {@code SUBMIT-JOB-TO-INTRDR} at L462 of {@code app/cbl/CORPT00C.cbl} -- reached
     * from the monthly arm at L238, the yearly arm at L255 and the custom arm at L435 -- writes card
     * images to a queue through {@code WIRTE-JOBSUB-TDQ}, and the report is produced later by the job
     * the reader starts, so the online program never holds a report record at all. This case pins the
     * same separation in the target.</p>
     *
     * <p>Alternatives Considered: injecting stand-ins for the two generator classes and asserting no
     * interaction with them. Rejected because the service under test declares only THREE constructor
     * parameters -- the orchestration client, the configured state machine and the clock -- so there is
     * no seam to inject a generator through, and adding one merely to observe that it goes unused
     * would introduce the very dependency this case exists to deny. Asserting the absence structurally
     * is also the stronger statement: an uninteracted stand-in shows the current path does not call a
     * generator, whereas this shows no path can, because the class holds no reference to reach one
     * through. The package already settles this idiom -- {@code TransactionReportServiceTest} and
     * {@code StatementServiceTest} deny their own clock the same way.</p>
     *
     * <p>Assumptions: the orchestration client is additionally asserted to receive exactly one call
     * and nothing further, which closes the other half. A service that started the run correctly and
     * then also polled, described or stopped it would be doing work on the request path that the
     * baseline's fire-and-return submission does not, and no assertion above would notice.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the request edge starts the run and cannot generate a report inline")
    void theRequestEdgeStartsAndCannotGenerateInline() {
        ReportExecutionService service = serviceAt(MID_JULY);
        when(this.sfn.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().executionArn(EXECUTION_ARN).build());

        service.start(
                selection(MARK, null, null, MARK),
                ReportExecutionService.MONTHLY_REPORT_NAME,
                LocalDate.of(2022, 7, 1),
                LocalDate.of(2022, 7, 31), NO_SUPPLIED_KEY);

        verify(this.sfn).startExecution(any(StartExecutionRequest.class));
        verifyNoMoreInteractions(this.sfn);

        for (Class<?> generator
                : java.util.List.of(TransactionReportService.class, StatementService.class)) {
            for (Field field : ReportExecutionService.class.getDeclaredFields()) {
                assertThat(field.getType())
                        .as("field %s is not a report generator", field.getName())
                        .isNotEqualTo(generator);
            }
            for (Constructor<?> constructor
                    : ReportExecutionService.class.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("no constructor takes a report generator")
                        .doesNotContain(generator);
            }
            for (Method method : ReportExecutionService.class.getDeclaredMethods()) {
                assertThat(method.getParameterTypes())
                        .as("method %s takes no report generator", method.getName())
                        .doesNotContain(generator);
                assertThat(method.getReturnType())
                        .as("method %s returns no report generator", method.getName())
                        .isNotEqualTo(generator);
            }
        }
    }

    /**
     * The range handed downstream is the explicit pair, so no clock reaches a generator.
     *
     * <p>Purpose: this is the generator half of the line the class documentation draws. The request
     * edge is permitted a clock and consults it to expand a preset; from that point the range travels
     * as an explicit pair, which is the discipline {@code app/jcl/TRANREPT.jcl} keeps at L43 and L44
     * by fixing the selection range as two literals, and which {@code app/cbl/CBTRN03C.cbl} keeps at
     * L220 by reading its range from a parameter record rather than a date of its own. An injected
     * range is what lets a rerun produce the same report.</p>
     *
     * <p>Assumptions: two services built on two DIFFERENT clocks are driven over the SAME explicit
     * range and asserted to emit the identical execution input, which is the property that matters
     * downstream. A single-clock case would establish only that one run is self-consistent; the pair
     * establishes that once a range is explicit the clock contributes nothing further to it, so
     * nothing a generator receives can vary with the day the submission happened to be made.</p>
     *
     * <p>Assumptions: neither generator is asserted here to consult a clock indirectly -- that would
     * need their own collaborators -- but both are asserted to declare none, which is the structural
     * form of the same claim and is what each generator's own test class also asserts of itself.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an explicit range yields the same input under two clocks, and no generator has one")
    void anExplicitRangeIsInvariantAcrossClocksAndNoGeneratorHoldsOne() {
        when(this.sfn.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().executionArn(EXECUTION_ARN).build());
        LocalDate start = LocalDate.of(2022, 1, 1);
        LocalDate end = LocalDate.of(2022, 7, 6);

        // WHY : Assumptions: the explicit pair is the canonical fixture range the baseline hard-codes
        //       as sort symbols at L43 and L44 of app/jcl/TRANREPT.jcl, used here so the case is
        //       driven by the same range the reference's own reproducible run is driven by.
        serviceAt(MID_JULY).start(selection(null, MARK, null, MARK),
                ReportExecutionService.YEARLY_REPORT_NAME, start, end, "fixedkey");
        serviceAt(Instant.parse("2031-11-09T03:17:44Z")).start(selection(null, MARK, null, MARK),
                ReportExecutionService.YEARLY_REPORT_NAME, start, end, "fixedkey");

        ArgumentCaptor<StartExecutionRequest> started =
                ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(this.sfn, times(2)).startExecution(started.capture());

        assertThat(started.getAllValues().get(0).input())
                .as("an explicit range carries no trace of the day it was submitted on")
                .isEqualTo(started.getAllValues().get(1).input());
        assertThat(started.getAllValues().get(0).name())
                .as("a keyed submission names the same run under either clock")
                .isEqualTo(started.getAllValues().get(1).name());

        for (Class<?> generator
                : java.util.List.of(TransactionReportService.class, StatementService.class)) {
            for (Field field : generator.getDeclaredFields()) {
                assertThat(field.getType())
                        .as("%s field %s is not a time source", generator.getSimpleName(),
                                field.getName())
                        .isNotEqualTo(Clock.class);
            }
            for (Constructor<?> constructor : generator.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("no %s constructor takes a time source", generator.getSimpleName())
                        .doesNotContain(Clock.class);
            }
            for (Method method : generator.getDeclaredMethods()) {
                assertThat(method.getParameterTypes())
                        .as("%s method %s takes no time source", generator.getSimpleName(),
                                method.getName())
                        .doesNotContain(Clock.class);
            }
        }
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
     *
     * <p>This case takes no parameter and yields no value.</p>
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
     * <p>Refactoring Rationale: two SEPARATE facts make this the one behaviour the transport change
     * exists to remove, and each is false on its own. The queue the baseline writes to is defined with
     * {@code ERROROPTION(IGNORE)} at L501 of {@code app/csd/CARDDEMO.CSD}, within the stanza spanning
     * L499 to L505 that carries {@code DDNAME(INREADER)} on that same L501 -- and that option is what
     * makes the REGION itself swallow an extrapartition write error, so a request could be accepted at
     * the screen and never reach the job entry subsystem with nothing recording the loss. The PROGRAM,
     * meanwhile, does the opposite of omitting the check: {@code WIRTE-JOBSUB-TDQ} at L515 of
     * {@code app/cbl/CORPT00C.cbl} -- the paragraph name quoted with the baseline's own spelling
     * rather than respelled -- requests a response code at L521, EVALUATES it at L525, and on any
     * non-normal outcome moves {@code 'Unable to Write TDQ (JOBS)...'} at L531. Attributing the
     * silence to the program would be false, and attributing the readiness to check to the region
     * would be false too. Raising here reproduces the program's readiness and declines the region's
     * silence, which is the only combination that loses nothing.</p>
     *
     * <p>Assumptions: the exception this case captures is the AWS SDK's
     * {@link SdkClientException}, thrown by the stubbed client to stand for the orchestrator declining
     * the start, and it surfaces as a {@link IllegalStateException} carrying that refusal as its
     * cause. Both types are named because {@code validateThrows} cannot inspect a lambda, so the pair
     * is recorded here rather than inferred from the body. The surfaced type is additionally asserted
     * NOT to be {@link ClientInputException}: the type is what decides which channel the shared advice
     * routes the failure to, and a caller whose request was well formed must not be told to correct
     * it while a real outage stays out of the internal channel.</p>
     *
     * <p>Trade-offs: the surfaced exception's OWN message is target-authored and is deliberately not
     * the reference sentence, which is a divergence rather than an oversight and is recorded as such.
     * The reference has one channel for both audiences -- it moves the sentence into the screen's
     * message field at L531 and L532 and re-sends the screen at L534 -- whereas the target separates
     * them, so the sentence a caller is shown is enumerated on the published contract for this
     * surface while the internal exception carries the report name an operator needs in order to tell
     * WHICH report failed to start. Asserting the report name here is therefore asserting the datum
     * the reference emits alongside the sentence, in its {@code DISPLAY} of the response and reason
     * codes at L529. The full catalog of nineteen reference sentences, this one included at its L531,
     * is asserted against the published contract by
     * {@code com.carddemo.reporting.api.ReportControllerTest}; it is named rather than duplicated here
     * so this class does not become a second authority on a catalog it does not own.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
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
                        .isNotInstanceOf(ClientInputException.class))
                // WHY : Assumptions: the report name is asserted to be PRESENT in the internal
                //       sentence, which is the analogue of the reference emitting the response and
                //       reason codes at L529 beside the sentence it moves at L531. A failure naming no
                //       report leaves an operator unable to tell which of the three submissions was
                //       lost, and the shared advice records the exception class and the request path
                //       but not the report.
                .satisfies(raised -> assertThat(raised.getMessage())
                        .as("the internal sentence names which report failed to start")
                        .contains(ReportExecutionService.MONTHLY_REPORT_NAME));
    }

    /**
     * The refusal is reported, never absorbed, so no accepted response is produced for a failed start.
     *
     * <p>Refactoring Rationale: this is the second half of declining the region's silence, and it is a
     * different claim from the one above. That case establishes that SOMETHING is raised; this one
     * establishes that nothing is also RETURNED, which is the shape the {@code ERROROPTION(IGNORE)}
     * option at L501 of {@code app/csd/CARDDEMO.CSD} produces in the baseline: the write is dropped,
     * the program's check at L525 of {@code app/cbl/CORPT00C.cbl} is never reached because the region
     * reported normal completion, and the screen goes on to the success path. An implementation that
     * caught the refusal, logged it and answered a response anyway would satisfy every assertion about
     * the loud path while reproducing exactly that outcome.</p>
     *
     * <p>Assumptions: the captured exception is the {@link IllegalStateException} the surfaced failure
     * arrives as, and the stub raises the AWS SDK's {@link SdkClientException} beneath it. The
     * assertion is that the call yields NO value at all -- the reference for the response is left
     * unassigned and asserted null afterwards -- because a returned response is the one outcome that
     * would let a caller believe a report was queued when none was.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a refused start yields no accepted response, so the loss cannot read as a success")
    void aRefusedStartYieldsNoAcceptedResponse() {
        ReportExecutionService service = serviceAt(MID_JULY);
        when(this.sfn.startExecution(any(StartExecutionRequest.class)))
                .thenThrow(SdkClientException.create("the orchestrator declined the start"));
        ReportRequest request = selection(MARK, null, null, "Y");
        ReportSubmissionResponse[] answered = new ReportSubmissionResponse[1];

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> answered[0] = service.start(request,
                        ReportExecutionService.MONTHLY_REPORT_NAME,
                        LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31), NO_SUPPLIED_KEY));

        assertThat(answered[0])
                .as("a submission that could not be placed produces no acceptance to report")
                .isNull();
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
     *
     * <p>This case takes no parameter and yields no value.</p>
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

    // WHY : ⚠️ Refactoring Rationale: the six cases below cover the describe operation, which did not
    //       exist. The submission returned an orchestration handle that no operation consumed, so a caller
    //       could not tell a run still going from one that had failed. The first thing each case asserts is
    //       something that was unobservable before: the composed handle, the recovered coordinates, an
    //       absent run, and an input this service did not write.
    /**
     * Asserts that the handle is composed from the configured machine rather than from the caller.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the described handle is composed from the configured state machine")
    void theDescribedHandleIsComposedFromTheConfiguredMachine() {
        when(sfn.describeExecution(any(DescribeExecutionRequest.class)))
                .thenReturn(describedAs("RUNNING", null));

        serviceAt(MID_JULY).describeExecution("some-run-name");

        ArgumentCaptor<DescribeExecutionRequest> asked =
                ArgumentCaptor.forClass(DescribeExecutionRequest.class);
        verify(sfn).describeExecution(asked.capture());
        assertThat(asked.getValue().executionArn())
                .as("nothing the caller sends may reach the handle except the final name segment")
                .isEqualTo(EXECUTION_ARN_PREFIX + "some-run-name");
    }

    /**
     * Asserts that the three coordinates are recovered from the execution's own input.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the coordinates are recovered from the execution input")
    void theCoordinatesAreRecoveredFromTheInput() {
        when(sfn.describeExecution(any(DescribeExecutionRequest.class)))
                .thenReturn(describedAs("SUCCEEDED", Instant.parse("2022-07-18T12:05:00Z")));

        ReportExecutionService.ExecutionState state =
                serviceAt(MID_JULY).describeExecution("some-run-name");

        assertThat(state.status()).isEqualTo(ReportExecutionService.ExecutionStatus.SUCCEEDED);
        assertThat(state.succeeded()).isTrue();
        assertThat(state.coordinates().reportType()).isEqualTo("monthly");
        assertThat(state.coordinates().rangeStart()).isEqualTo(LocalDate.of(2022, 7, 1));
        assertThat(state.coordinates().rangeEnd()).isEqualTo(LocalDate.of(2022, 7, 31));
        assertThat(state.stoppedAt()).isEqualTo("2022-07-18 12:05:00.000000");
    }

    // WHY : Assumptions: the round trip is asserted through the SUBMISSION path rather than against a
    //       hand-written input document, because the property under test is that the reader and the writer
    //       agree. A hand-written document would pass against a reader that had drifted from the writer,
    //       which is the only way this can break.
    /**
     * Asserts that the input the submission path writes is the input the describe path reads.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the input the submission writes is the input the describe reads")
    void theWrittenInputIsTheReadInput() {
        when(sfn.startExecution(any(StartExecutionRequest.class))).thenReturn(
                StartExecutionResponse.builder().executionArn(EXECUTION_ARN)
                        .startDate(MID_JULY).build());
        ReportExecutionService service = serviceAt(MID_JULY);
        service.start(
                selection(MARK, null, null, "Y"),
                ReportExecutionService.MONTHLY_REPORT_NAME,
                LocalDate.of(2022, 7, 1),
                LocalDate.of(2022, 7, 31), NO_SUPPLIED_KEY);

        ArgumentCaptor<StartExecutionRequest> started =
                ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfn).startExecution(started.capture());
        when(sfn.describeExecution(any(DescribeExecutionRequest.class))).thenReturn(
                DescribeExecutionResponse.builder()
                        .executionArn(EXECUTION_ARN)
                        .status("SUCCEEDED")
                        .startDate(MID_JULY)
                        .input(started.getValue().input())
                        .build());

        ReportExecutionService.ExecutionCoordinates recovered =
                service.describeExecution("some-run-name").coordinates();

        assertThat(recovered.reportType()).isEqualTo("monthly");
        assertThat(recovered.rangeStart()).isEqualTo(LocalDate.of(2022, 7, 1));
        assertThat(recovered.rangeEnd()).isEqualTo(LocalDate.of(2022, 7, 31));
    }

    /**
     * Asserts that an unknown execution is reported as an absent record.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an unknown execution is reported as an absent record")
    void anUnknownExecutionIsAbsent() {
        when(sfn.describeExecution(any(DescribeExecutionRequest.class)))
                .thenThrow(ExecutionDoesNotExistException.builder().message("gone").build());

        assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(() -> serviceAt(MID_JULY).describeExecution("some-run-name"))
                .withMessageContaining("no report execution of that name is known");
    }

    // WHY : Assumptions: a run started OUTSIDE this surface is asserted to report its status with no
    //       coordinates, rather than to fail. The nightly schedule starts the same state machine with an
    //       input of its own shape, so refusing to describe it would make the operation unusable for
    //       exactly the runs an operator most often asks about.
    /**
     * Asserts that an input this service did not compose yields a status without coordinates.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an input this service did not compose yields no coordinates")
    void anUnrecognisedInputYieldsNoCoordinates() {
        when(sfn.describeExecution(any(DescribeExecutionRequest.class))).thenReturn(
                DescribeExecutionResponse.builder()
                        .executionArn(EXECUTION_ARN)
                        .status("RUNNING")
                        .startDate(MID_JULY)
                        .input("{\"businessDate\":\"2022-07-18\"}")
                        .build());

        ReportExecutionService.ExecutionState state =
                serviceAt(MID_JULY).describeExecution("some-run-name");

        assertThat(state.status()).isEqualTo(ReportExecutionService.ExecutionStatus.RUNNING);
        assertThat(state.coordinates()).isNull();
    }

    /**
     * Asserts that a status the orchestration adds later is refused rather than mapped to a nearby one.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an unpublished orchestration status is refused")
    void anUnpublishedStatusIsRefused() {
        when(sfn.describeExecution(any(DescribeExecutionRequest.class))).thenReturn(
                DescribeExecutionResponse.builder()
                        .executionArn(EXECUTION_ARN)
                        .status("SOMETHING_NEW")
                        .startDate(MID_JULY)
                        .input(RECOGNISED_INPUT)
                        .build());

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> serviceAt(MID_JULY).describeExecution("some-run-name"))
                .withMessageContaining("status this service does not publish");
    }

    /**
     * The custom bounds are edited by the shared validator, not by rules restated here.
     *
     * <p>Refactoring Rationale: the date rules are DELEGATED and this group asserts the delegation
     * rather than the rules. The baseline delegates too: it does not implement its own date arithmetic
     * but calls the dynamically-invoked subprogram {@code CSUTLDTC} twice, at L392 for the lower bound
     * and L412 for the upper one, passing the parameter group declared at L129 to L136 of
     * {@code app/cbl/CORPT00C.cbl} with the mask {@code 'YYYY-MM-DD'} that its L72 fixes. AAP
     * transformation rule T2 turns one such shared contract into exactly one type import from the
     * single package that owns it, so the rules live in {@code com.carddemo.common.validation} and this
     * service calls them. Restating the leap-year test, the month and day ranges or the feedback
     * severities here would create a second authority on whether a date is valid, and two authorities
     * can disagree -- so those belong to the shared kernel's own test class and are deliberately not
     * re-tested in this module.</p>
     */
    @Nested
    @DisplayName("the shared date edit, delegated rather than restated")
    class DelegatedDateEdit {

        /**
         * A well-formed bound the shared edit rejects only for range is FORGIVEN by this caller.
         *
         * <p>Assumptions: this tolerance is CALLER-SPECIFIC and is the reason this case exists. The
         * two call sites in {@code app/cbl/CORPT00C.cbl} are the {@code CALL 'CSUTLDTC'} at L392 for
         * the lower bound and the one at L412 for the upper, each passing the parameter group declared
         * at L129 to L136. Both test the severity first -- at L396 for the lower bound and L416 for the
         * upper -- and then add a second, narrower acceptance: L399 and L419 each read
         * {@code IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'}, so a non-zero severity whose message number
         * IS 2513 is accepted anyway. That number names the unsupported-range outcome, which the shared
         * kernel reaches only for a well-formed calendar date below the supported calendar floor, so a
         * bound this tolerance forgives is always still a date that converts. Dropping the tolerance
         * would refuse a bound the baseline accepts. The proof that the tolerance belongs to THIS
         * caller and not to the shared rules is that {@code app/cpy/CSUTLDPY.cpy} calls the same
         * subprogram and tests the severity alone with no tolerance at all, so pushing it down into the
         * shared validator would relax every other caller.</p>
         *
         * <p>Assumptions: the bound is the day IMMEDIATELY below the floor rather than a date centuries
         * under it, which makes the case a boundary rather than an illustration. A bound far below the
         * floor would pass equally against an implementation whose threshold was off by any amount.</p>
         *
         * <p>Assumptions: the shared edit's verdict on that same bound is asserted FIRST, so the case
         * cannot silently become vacuous. If a future change to the shared kernel started accepting the
         * bound outright, the tolerance would no longer be what carries it and this case would pass
         * while proving nothing -- the precondition assertion is what fails instead.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("an unsupported-range bound is forgiven, because the two call sites forgive it")
        void anUnsupportedRangeBoundIsForgiven() {
            LanguageEnvironmentResult verdict = DateEditValidator.evaluateWithLanguageEnvironment(
                    "1582-10-14", DateEditValidator.DATE_FORMAT_MASK);

            assertThat(verdict.acceptable())
                    .as("the shared edit does NOT accept this bound outright")
                    .isFalse();
            assertThat(verdict.unsupportedRange())
                    .as("it rejects it for range alone, which is the condition the tolerance names")
                    .isTrue();

            ReportExecutionService.DateRange range = serviceAt(MID_JULY).resolveRange(
                    customRange("1582-10-14", "2022-07-31", MARK),
                    ReportExecutionService.CUSTOM_REPORT_NAME);

            assertThat(range.start())
                    .as("the caller-specific tolerance at L399 and L419 carries the bound through")
                    .isEqualTo(LocalDate.of(1582, 10, 14));
        }

        /**
         * A bound the shared edit rejects for any OTHER reason is refused, so the tolerance is narrow.
         *
         * <p>Assumptions: this is the contrast that makes the case above meaningful. A service that
         * forgave every non-zero severity would pass the tolerance case and would accept a bound the
         * baseline refuses, so the tolerance has to be shown to be narrow as well as present. The bound
         * below is rejected by the shared edit with a message number that is NOT the forgiven one,
         * which is asserted rather than assumed, and the service must then refuse it with the
         * reference's own sentence for an unusable assembled bound -- the string L420 moves for the
         * upper bound.</p>
         *
         * <p>Assumptions: the captured exception is {@link ClientInputException}, because a bound the
         * caller supplied and the edit refused is a request to correct rather than an internal fault.
         * The field it names is asserted too, since the field is this target's analogue of the cursor
         * position the reference sets.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("a bound rejected for any other reason is refused, so the tolerance stays narrow")
        void aDifferentlyRejectedBoundIsRefused() {
            // WHY : Refactoring Rationale: the bound was 2022-13-01 and is now 2022-02-30, because a
            //       month of thirteen no longer REACHES the shared edit -- the reference's component
            //       tier answers it first, from L357 of app/cbl/CORPT00C.cbl, so this case would have
            //       asserted the component sentence and stopped exercising the tolerance's narrowness
            //       at all. A thirtieth of February has a month and a day the component tier admits
            //       and a date the calendar does not, so it is the shape that still arrives at the
            //       edit and is still rejected for something other than the forgiven range outcome.
            LanguageEnvironmentResult verdict = DateEditValidator.evaluateWithLanguageEnvironment(
                    "2022-02-30", DateEditValidator.DATE_FORMAT_MASK);

            assertThat(verdict.acceptable()).as("the shared edit rejects this bound").isFalse();
            assertThat(verdict.messageNumber())
                    .as("and it rejects it for something other than the forgiven range outcome")
                    .isNotEqualTo(DateEditValidator.MSG_NO_UNSUPP_RANGE);
            assertThat(verdict.unsupportedRange())
                    .as("so the tolerance does not apply to it")
                    .isFalse();

            ReportExecutionService service = serviceAt(MID_JULY);
            ReportRequest request = customRange("2022-07-01", "2022-02-30", MARK);

            assertThatExceptionOfType(ClientInputException.class)
                    .isThrownBy(() -> service.resolveRange(
                            request, ReportExecutionService.CUSTOM_REPORT_NAME))
                    .withMessage(ReportExecutionService.MESSAGE_END_DATE_INVALID)
                    .satisfies(refused -> assertThat(refused.field()).isEqualTo("endDate"));
        }

        /**
         * The verdict carries the severity, number and message TRIPLE, with the padding dropped.
         *
         * <p>Assumptions: the baseline transports the edit's answer as a fixed-width group and this
         * case pins which parts of it survive. {@code CSUTLDTC-RESULT} is declared at L132 to L136 of
         * {@code app/cbl/CORPT00C.cbl} as a four-character severity at L133, ELEVEN characters of
         * {@code FILLER} at L134, a four-character message number at L135 and a sixty-one character
         * message at L136, which is 4 + 11 + 4 + 61 = 80 and therefore the whole declared result. The
         * enclosing {@code CSUTLDTC-PARM} at L129 is a different and larger figure -- the ten-character
         * date at L130 plus the ten-character mask at L131 plus that result, so 10 + 10 + 80 = 100 --
         * and the two are asserted separately here because attributing the 80 to the whole group is the
         * available mistake.</p>
         *
         * <p>Assumptions: the eleven-character {@code FILLER} at L134 is DROPPED and the drop is
         * recorded, as AAP transformation rule T1 requires of a dropped filler item. The target's
         * verdict type carries a component for the severity, one for the message number and one for the
         * message text, and NONE for the padding, so the drop is observable as the absence of a
         * component rather than merely asserted in prose -- which is what the component-name assertion
         * below checks. Padding exists to reach the declared record length and carries no datum, so
         * nothing downstream can want it.</p>
         *
         * <p>Assumptions: the triple is asserted to be a triple rather than collapsed to a boolean,
         * because the published contract for this surface expresses the reference tolerance for the
         * forgiven number to a client, which a bare accept-or-reject flag could not express at all.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the verdict is a severity, number and message triple, and the filler is dropped")
        void theVerdictCarriesTheTripleAndDropsTheFiller() {
            LanguageEnvironmentResult verdict = DateEditValidator.evaluateWithLanguageEnvironment(
                    "1582-10-14", DateEditValidator.DATE_FORMAT_MASK);

            assertThat(verdict.severity())
                    .as("the severity is the first of the three, from L133")
                    .isNotEqualTo(DateEditValidator.SEVERITY_VALID);
            assertThat(verdict.messageNumber())
                    .as("the message number is the second, from L135")
                    .isEqualTo(DateEditValidator.MSG_NO_UNSUPP_RANGE);
            assertThat(verdict.verdict())
                    .as("the message text is the third, from L136")
                    .isNotBlank();

            // WHY : Assumptions: the result group's own width is asserted against the sum of its four
            //       declared parts rather than against a bare 80, so the assertion states WHERE the
            //       figure comes from. The whole parameter group is 100 and is written out beside it so
            //       the two figures cannot be conflated by a later reader.
            assertThat(DateEditValidator.RESULT_LENGTH)
                    .as("CSUTLDTC-RESULT at L132 to L136 is 4 + 11 + 4 + 61")
                    .isEqualTo(4 + 11 + 4 + 61)
                    .isEqualTo(80);
            assertThat(2 * DateEditValidator.MASKED_DATE_LENGTH + DateEditValidator.RESULT_LENGTH)
                    .as("the whole CSUTLDTC-PARM at L129 to L136 is 10 + 10 + 80, which is not the 80")
                    .isEqualTo(100);

            assertThat(LanguageEnvironmentResult.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .as("the eleven-character FILLER at L134 has no component and is dropped")
                    .contains("severity", "messageNumber", "verdict")
                    .doesNotContain("filler", "padding");
        }

        /**
         * The mask the service edits against is the one the reference declares, not a second spelling.
         *
         * <p>Assumptions: {@code app/cbl/CORPT00C.cbl} L72 declares
         * {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} and passes it as the second part of the
         * parameter group, so the mask is part of the delegated contract rather than a local choice.
         * The shared kernel also publishes the baseline's other, unseparated spelling for callers that
         * need it, which is precisely why the one this caller uses is worth pinning: picking the other
         * would change the accepted width from ten to eight and refuse every bound this surface
         * receives.</p>
         *
         * <p>Trade-offs: the reference takes each bound as SIX separate screen components -- two-digit
         * month, two-digit day and four-digit year per bound, with hyphen separators as {@code FILLER}
         * at L62, L64, L68 and L70 -- and assembles them before editing, whereas this surface takes one
         * ten-character value per bound. What the narrower shape costs is precisely and only the
         * OMISSION sentences: a consolidated value is present whole or absent whole, so an absent bound
         * reports the reference's month-omission sentence for the whole bound and the day and year
         * omission sentences have no input that selects them. The six component RANGE sentences cost
         * nothing, because an assembled bound still carries all three components: they are selected from
         * the slices at the offsets this same group declares. The residual is registered as
         * D-REPORT-DATE-MESSAGE-REACH.</p>
         *
         * <p>This case takes no parameter and yields no value.</p>
         */
        @Test
        @DisplayName("the delegated mask is the ten-character separated one the reference declares")
        void theDelegatedMaskIsTheSeparatedTenCharacterOne() {
            assertThat(DateEditValidator.DATE_FORMAT_MASK)
                    .as("the mask app/cbl/CORPT00C.cbl declares at L72")
                    .isEqualTo("YYYY-MM-DD");
            assertThat(DateEditValidator.MASKED_DATE_LENGTH)
                    .as("which admits exactly the ten positions the two bound fields declare")
                    .isEqualTo("YYYY-MM-DD".length());

            ReportExecutionService.DateRange range = serviceAt(MID_JULY).resolveRange(
                    customRange("2022-01-01", "2022-07-06", MARK),
                    ReportExecutionService.CUSTOM_REPORT_NAME);

            assertThat(range.start()).isEqualTo(LocalDate.of(2022, 1, 1));
            assertThat(range.end()).isEqualTo(LocalDate.of(2022, 7, 6));
        }
    }

    /**
     * Builds a described execution carrying the input this service composes.
     *
     * @param status the orchestration status token
     * @param stopped when the run stopped, or {@code null} while it runs
     * @return the described execution; never {@code null}
     */
    private static DescribeExecutionResponse describedAs(String status, Instant stopped) {
        DescribeExecutionResponse.Builder builder = DescribeExecutionResponse.builder()
                .executionArn(EXECUTION_ARN)
                .status(status)
                .startDate(MID_JULY)
                .input(RECOGNISED_INPUT);
        return stopped == null ? builder.build() : builder.stopDate(stopped).build();
    }
}
