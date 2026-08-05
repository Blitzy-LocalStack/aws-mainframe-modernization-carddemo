package com.carddemo.reporting.dto;

import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.common.validation.FieldValidationFlag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Selects a transaction report by type and, for a caller-supplied run, by an inclusive range.
 *
 * <h2>Why there are thirteen components</h2>
 *
 * <p>This is the request body of the report-request surface that {@code com.carddemo.reporting.api}
 * exposes in place of the 3270 screen {@code CORPT0A}, and the number of components below is
 * arithmetic rather than preference. {@code app/bms/CORPT00.bms} declares 42 {@code DFHMDF}
 * definitions inside the map opened by {@code DFHMDI} at L26 and sized {@code SIZE=(24,80)} at L28,
 * and only 17 of the 42 carry a name. The other 25 are constant captions, separators and the
 * function-key legend: they address no storage, they reach no symbolic map, and there is nothing
 * about them for a request to carry. The derivation base is therefore 17 and never 42.
 *
 * <p>Six of those 17 are the two three-part range composites -- {@code SDTMM} at L127,
 * {@code SDTDD} at L138 and {@code SDTYYYY} at L149 for the lower bound, and {@code EDTMM} at L166,
 * {@code EDTDD} at L177 and {@code EDTYYYY} at L188 for the upper -- and each triple is consolidated
 * here into one value. So the count is 17 minus 6 plus 2, which is 13, and the 13 components declared
 * below are the whole of the screen's named input and output with nothing set aside.
 *
 * <h2>What a rejection still has to be able to say</h2>
 *
 * <p>The consolidation costs nothing a caller can observe, and that claim is checkable rather than
 * asserted, because every message the baseline raises against an individual part of a range remains
 * reachable as a per-component entry. {@code app/cbl/CORPT00C.cbl} raises six against an empty part,
 * for the lower bound's month at L261, its day at L268 and its year at L275 and for the upper bound's
 * month at L282, its day at L289 and its year at L296; six more against a part that is present and
 * malformed, at L331, L340 and L348 for the lower bound and L357, L366 and L374 for the upper; two
 * against a range whose parts are individually well formed but which name no real date, at L400 and
 * L420; and one when no report type was selected at all, at L438. Those 15 strings are reproduced
 * verbatim by the presentation layer and are deliberately not declared in this package, which holds
 * no message catalogue.
 *
 * <p>Assumptions: the reply those messages come from is a structured triple and not a boolean, which
 * is why a per-component entry can name a part at all. {@code CSUTLDTC-PARM} at
 * {@code app/cbl/CORPT00C.cbl} L129 to L136 passes a 10-character value at L130 and a 10-character
 * mask at L131, and receives a result of a 4-character severity code at L133, an 11-character
 * {@code FILLER} at L134, a 4-character message number at L135 and a 61-character message at L136 --
 * 100 characters in total, and the {@code FILLER} is a declared member of that total rather than
 * padding a summary may drop. Severity, number and text therefore all survive into the error payload.
 * The calendar decision behind them belongs to {@code DateEditValidator} under transformation rule
 * T2, which turns one former copybook inclusion into one import from the single owner of the
 * contract, so no part of it is restated here.
 *
 * <h2>The two consolidations, and the shape they produce</h2>
 *
 * <p>Trade-offs: the screen accepts each bound as three separate parts and never as one value, so
 * consolidating each triple into a single 10-character string is a narrower wire shape than the one
 * the baseline offers, and the compromise accepted is that a caller can no longer submit a month
 * without a year. Nothing observable is given up for it. The parts of a rejected bound are still
 * named individually, by the twelve per-part messages listed above, and the assembled form is
 * already what the baseline itself works in: {@code WS-START-DATE} at L60 and {@code WS-END-DATE} at
 * L66 each hold a 4-character year, then a 2-character month, then a 2-character day, separated by
 * the literal hyphen {@code FILLER}s at L62 and L64 and at L68 and L70, in that order -- so the
 * composite the baseline assembles is character for character the form
 * {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} names at L72. Two further sources agree:
 * {@code DateEditValidator} publishes the same mask, and the batch path is driven by the literal
 * sort symbols {@code PARM-START-DATE,C'2022-01-01'} and {@code PARM-END-DATE,C'2022-07-06'} at
 * {@code app/jcl/TRANREPT.jcl} L43 and L44.
 *
 * <p>Assumptions: the confirmation flag is a component of this record and not an artifact of the
 * terminal, so it is carried rather than dropped. {@code CONFIRMI PIC X(1)} at
 * {@code app/cpy-bms/CORPT00.CPY} L114 drives three distinct outcomes in
 * {@code app/cbl/CORPT00C.cbl}, and all three matter to a caller: absent, tested at L464, produces
 * its own prompt composed at L465 to L470 and submits nothing; {@code Y} or {@code y} at L478
 * proceeds; {@code N} or {@code n} at L480 clears the screen's fields instead of submitting. Dropping
 * it would collapse those three outcomes into one and would submit a report the baseline would have
 * asked about first.
 *
 * <h2>Two presets whose ranges are not derived alike</h2>
 *
 * <p>Assumptions: the two preset report types resolve their ranges by different means and to
 * different kinds of bound, and both are reproduced as they stand rather than regularised. The
 * whole-of-month preset at {@code app/cbl/CORPT00C.cbl} L213 to L236 sets the lower bound to the
 * first of the current month at L217 to L219 and then computes the upper bound: it moves to the first
 * of the following month, carrying into the next year when the month would exceed twelve at L223 to
 * L228, and steps back one position through
 * {@code FUNCTION DATE-OF-INTEGER(FUNCTION INTEGER-OF-DATE(...) - 1)} at L229 to L230. The
 * whole-of-year preset at L239 to L252 sets the lower bound to the first of January at L243 to L246
 * and the upper bound from the two literal values {@code '12'} and {@code '31'} at L248 and L249.
 * Two consequences follow that a reader should not mistake for defects. Each preset spans a complete
 * calendar period rather than running up to the current date, so for most of a period its upper bound
 * names a date that has not arrived; and the month preset's bound is calendar-derived, landing on
 * whichever of 28, 29, 30 or 31 the month has, while the year preset's is two constants. The resolved
 * type name is held in {@code WS-REPORT-NAME PIC X(10)} at L58 and is {@code 'Monthly'} at L214,
 * {@code 'Yearly'} at L240 or {@code 'Custom'} at L433.
 *
 * <p>Assumptions: resolving a preset reads a clock exactly once, in the layer that handles the
 * request, through an injected time source -- and this record reads no clock at all, declares no
 * resolved bound of its own and carries no member that could hold one. What the report generator
 * receives is always an explicit pair of bounds. That is the property the parity oracle depends on:
 * the baseline batch path injects its range as the two sort symbols at
 * {@code app/jcl/TRANREPT.jcl} L43 and L44 rather than reading the calendar, so a rerun of the same
 * request produces the same output and a golden comparison means something.
 *
 * <h2>Decisions taken on this type</h2>
 *
 * <p>Assumptions: every component is a string, on the strength of the baseline treating its own
 * fields the same way -- {@code app/cpy/CVCRD01Y.cpy} declares each identifier twice over the same
 * bytes, as characters at L34, L37 and L40 and as a number at L36, L39 and L42, and the character
 * declaration is the one the screen and the message carry. The register at
 * {@code com.carddemo.reporting.dto} owns that reading for the whole package. Applied here it is
 * exact rather than approximate: the three selection marks and the confirmation flag are single
 * characters with no numeric meaning at all, the six header components are captions and stamps, and
 * the two bounds are the 10-character separated form named at L72.
 *
 * <p>Alternatives Considered: typing the two bounds as {@code java.time.LocalDate} was evaluated and
 * rejected, and the reason is the one thing this record exists to protect. A date-typed component is
 * converted while the request body is being bound, so a malformed value is refused before any
 * validation runs, and what reaches the caller is a single unreadable-body failure naming no field.
 * That would collapse the twelve per-part messages at {@code app/cbl/CORPT00C.cbl} L261 to L374
 * and the two whole-date messages at L400 and L420 into one generic parse error and discard the
 * field-level error contract outright. A string-typed bound arrives intact, is checked for shape by
 * the constraint on the component, and is converted by the service afterwards, so every fault is
 * reported against the component it belongs to.
 *
 * <p>Refactoring Rationale: the baseline decides whether to mark a faulted field by consulting
 * remembered state, and this record replaces that mechanism rather than porting it.
 * {@code app/cbl/CORPT00C.cbl} reaches the shared session structure through
 * {@code COPY COCOM01Y.} at L138; {@code CDEMO-PGM-CONTEXT PIC 9(01)} at
 * {@code app/cpy/COCOM01Y.cpy} L29 and its condition names at L30 and L31 distinguish first entry
 * from repeat entry, while {@code app/cpy/CSSETATY.cpy} L17 to L27 gates both the red-attribute move
 * and its literal marker on that remembered value. A stateless handler has nothing to remember, so
 * field-error presentation here is driven entirely by what the response body says; the register at
 * {@code com.carddemo.reporting.dto} records the elimination in full. The consequence for this type
 * is concrete: {@code errorMessage} exists so that the symbolic map round-trips whole, and a value
 * arriving in it is never read as authority for whether this request is faulted. That authority
 * rests with {@code com.carddemo.common.error.ApiError} and the per-component entries it carries from
 * {@code FieldValidationFlag}.
 *
 * <p>Assumptions: three message widths coexist across this one screen and none is normalised onto
 * another. The session carriers are 75 characters at {@code app/cpy/CVCRD01Y.cpy} L28 and L29, with
 * the sentinel at L30 subordinate to the second of them alone; this screen's band is 78 at
 * {@code app/cpy-bms/CORPT00.CPY} L120, left-justified and blank-padded; and the program's own
 * working message is {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/CORPT00C.cbl} L39. Each is
 * correct at its declared width, the widest is the one this record's message component asserts
 * because it is the one the screen declares, and widening any of them to meet another would assert a
 * contract no artifact states.
 *
 * <p>Assumptions: a declared width quoted in this file comes from the symbolic map's value item and
 * from nothing else, because the map's overlay groups occupy no bytes. Each screen field is a
 * five-part group in {@code app/cpy-bms/CORPT00.CPY} in which a group introduced by
 * {@code FILLER REDEFINES} addresses the attribute byte a second time rather than a new one, and
 * seventeen such groups appear at L21 through L117 on a stride of exactly six. A walk down the
 * declarations that counts every group it meets therefore reports each byte position one further
 * along per group, seventeen too far by the end of the map, which is why the overlays are excluded
 * from any such reading rather than trusted to cancel out. A second hazard is worth recording beside
 * it, because it silently under-reports the roster: a search for the value items that requires a
 * letters-only name ahead of the {@code I} suffix misses {@code TITLE01I} at L30 and
 * {@code TITLE02I} at L48, whose names carry digits, and so finds 15 fields where there are 17.
 *
 * @param transactionName the transaction identifier the screen echoes into its header band, at most
 *     4 characters as {@code TRNNAMEI PIC X(4)} declares at {@code app/cpy-bms/CORPT00.CPY} L24;
 *     display material that a caller may return unchanged, and never read here as authority for what
 *     is being requested
 * @param title01 the upper title band as the screen received it, at most 40 characters per
 *     {@code TITLE01I PIC X(40)} at L30; its constant text originates in
 *     {@code app/cpy/COTTL01Y.cpy} and is composed for display rather than supplied by a caller
 * @param currentDate the calendar stamp the screen shows in its header, at most 8 characters per
 *     {@code CURDATEI PIC X(8)} at L36; it records what the terminal displayed and is emphatically
 *     not a bound of the range being requested, which the two bound components below carry
 * @param programName the program identifier echoed into the header band, at most 8 characters per
 *     {@code PGMNAMEI PIC X(8)} at L42; it names the baseline unit a screen came from, and nothing
 *     in this context dispatches on it
 * @param title02 the lower title band as the screen received it, at most 40 characters per
 *     {@code TITLE02I PIC X(40)} at L48, sharing both the origin and the display-only standing of
 *     the upper band above
 * @param currentTime the clock stamp the screen shows in its header, at most 8 characters per
 *     {@code CURTIMEI PIC X(8)} at L54; like the calendar stamp beside it this is display material,
 *     and no preset bound is ever derived from it
 * @param monthly the mark selecting the whole-of-month preset, one character per
 *     {@code MONTHLYI PIC X(1)} at L60, or {@code null} when that preset was not chosen;
 *     {@code MONTHLY} at {@code app/bms/CORPT00.bms} L80 is the only definition on the map carrying
 *     {@code IC}, so it is where a client places the cursor on first render
 * @param yearly the mark selecting the whole-of-year preset, one character per
 *     {@code YEARLYI PIC X(1)} at L66, or {@code null} when that preset was not chosen
 * @param custom the mark selecting a caller-supplied range, one character per
 *     {@code CUSTOMI PIC X(1)} at L72, or {@code null} when no such range is being supplied; this is
 *     the one selection under which the two bounds below are read at all
 * @param startDate the inclusive lower bound as a single 10-character value in the separated
 *     {@code YYYY-MM-DD} form of {@code app/cbl/CORPT00C.cbl} L72, consolidating the three parts
 *     declared at {@code app/cpy-bms/CORPT00.CPY} L78, L84 and L90, or {@code null} when a preset
 *     supplies the range instead
 * @param endDate the inclusive upper bound on exactly the terms of the lower bound above,
 *     consolidating the three parts declared at L96, L102 and L108, or {@code null} when a preset
 *     supplies the range instead
 * @param confirm the confirmation answer, one character per {@code CONFIRMI PIC X(1)} at L114
 *     restricted to {@code Y} or {@code N} in either case, or {@code null} when no answer was
 *     supplied -- a third state the baseline distinguishes at L464 and answers with a prompt of its
 *     own rather than treating as a refusal
 * @param errorMessage the message band as the screen received it, at most 78 characters per
 *     {@code ERRMSGI PIC X(78)} at L120; carried so the symbolic map round-trips whole, and never
 *     read on input as authority for whether this request is faulted
 */
public record ReportRequest(
        @Size(max = TRANSACTION_NAME_WIDTH) String transactionName,
        @Size(max = TITLE_WIDTH) String title01,
        @Size(max = STAMP_WIDTH) String currentDate,
        @Size(max = PROGRAM_NAME_WIDTH) String programName,
        @Size(max = TITLE_WIDTH) String title02,
        @Size(max = STAMP_WIDTH) String currentTime,
        @Size(max = SELECTOR_WIDTH) @Pattern(regexp = SELECTOR_MARK) String monthly,
        @Size(max = SELECTOR_WIDTH) @Pattern(regexp = SELECTOR_MARK) String yearly,
        @Size(max = SELECTOR_WIDTH) @Pattern(regexp = SELECTOR_MARK) String custom,
        @Size(max = DateEditValidator.MASKED_DATE_LENGTH) @Pattern(regexp = ISO_DATE) String startDate,
        @Size(max = DateEditValidator.MASKED_DATE_LENGTH) @Pattern(regexp = ISO_DATE) String endDate,
        @Size(max = CONFIRM_WIDTH) @Pattern(regexp = CONFIRM_ANSWER) String confirm,
        @Size(max = ERROR_MESSAGE_WIDTH) String errorMessage) {

    /**
     * Number of positions the baseline declares for the transaction identifier.
     *
     * <p>Assumptions: 4 is read from {@code TRNNAMEI PIC X(4)} at {@code app/cpy-bms/CORPT00.CPY}
     * L24 and corroborated from the other side of the same contract by {@code LENGTH=4} on
     * {@code TRNNAME} at {@code app/bms/CORPT00.bms} L34. Both artifacts agree for all 17 named
     * fields of this map, so each width quoted in this file is a contract confirmed twice rather
     * than a single reading.
     *
     * <p>Alternatives Considered: writing the number straight into the constraint annotation was the
     * obvious alternative and is rejected. A named constant leaves exactly one line to compare
     * against {@code app/cpy-bms/CORPT00.CPY} L24, whereas a literal inside an annotation is a
     * number with no stated provenance sitting where nobody looks for one.
     */
    private static final int TRANSACTION_NAME_WIDTH = 4;

    /**
     * Number of positions the baseline declares for each of the two title bands.
     *
     * <p>Assumptions: 40 is read from {@code TITLE01I PIC X(40)} at L30 and
     * {@code TITLE02I PIC X(40)} at L48, corroborated by {@code LENGTH=40} at
     * {@code app/bms/CORPT00.bms} L38 and L61. One constant serves both because the two fields are
     * the same band declared twice, differing only in screen row, and a change to the band would
     * have to move both together or the header would no longer align.
     */
    private static final int TITLE_WIDTH = 40;

    /**
     * Number of positions the baseline declares for each of the two header stamps.
     *
     * <p>Assumptions: 8 is read from {@code CURDATEI PIC X(8)} at L36 and
     * {@code CURTIMEI PIC X(8)} at L54, corroborated by {@code LENGTH=8} at
     * {@code app/bms/CORPT00.bms} L47 and L70. The two share one constant because they are one
     * header stamp pair whose initial values at L50 and L74 are both 8-character templates.
     *
     * <p>Alternatives Considered: folding the program identifier below into this constant as well,
     * since it also declares 8. Rejected because the agreement is a coincidence of width and not a
     * shared contract: a header stamp is a rendering of a clock reading and an identifier names a
     * program, so one constant governing both would let an edit made for one silently redefine the
     * other.
     */
    private static final int STAMP_WIDTH = 8;

    /**
     * Number of positions the baseline declares for the program identifier.
     *
     * <p>Assumptions: 8 is read from {@code PGMNAMEI PIC X(8)} at L42, corroborated by
     * {@code LENGTH=8} on {@code PGMNAME} at {@code app/bms/CORPT00.bms} L57. It is kept apart from
     * the stamp width above for the reason recorded there.
     */
    private static final int PROGRAM_NAME_WIDTH = 8;

    /**
     * Number of positions the baseline declares for each report-type selection mark.
     *
     * <p>Assumptions: 1 is read from {@code MONTHLYI PIC X(1)} at L60,
     * {@code YEARLYI PIC X(1)} at L66 and {@code CUSTOMI PIC X(1)} at L72, corroborated by
     * {@code LENGTH=1} at {@code app/bms/CORPT00.bms} L80, L94 and L108. The three share one
     * constant because they are one mutually exclusive selection expressed as three marks, and
     * {@code app/cbl/CORPT00C.cbl} reads them as a single decision in one {@code EVALUATE} at L213,
     * L239 and L256.
     */
    private static final int SELECTOR_WIDTH = 1;

    /**
     * Number of positions the baseline declares for the confirmation answer.
     *
     * <p>Assumptions: 1 is read from {@code CONFIRMI PIC X(1)} at
     * {@code app/cpy-bms/CORPT00.CPY} L114, corroborated by {@code LENGTH=1} on {@code CONFIRM} at
     * {@code app/bms/CORPT00.bms} L206.
     *
     * <p>Alternatives Considered: reusing the selector width above, which carries the same number.
     * Rejected because the two govern different value domains -- a selection mark admits any
     * character the baseline does not read as absence, while a confirmation answer admits only
     * {@code Y} or {@code N} in either case at {@code app/cbl/CORPT00C.cbl} L478 and L480 -- so a
     * single constant would tie four unrelated copybook lines to one edit site and invite a change
     * intended for one field to redefine the other three.
     */
    private static final int CONFIRM_WIDTH = 1;

    /**
     * Number of positions the baseline declares for the message band.
     *
     * <p>Assumptions: 78 is read from {@code ERRMSGI PIC X(78)} at
     * {@code app/cpy-bms/CORPT00.CPY} L120, corroborated by {@code LENGTH=78} on {@code ERRMSG} at
     * {@code app/bms/CORPT00.bms} L218. This is the widest field on the map and it is the width this
     * record asserts, deliberately not the 75 of the session carriers at
     * {@code app/cpy/CVCRD01Y.cpy} L28 and L29, for the reason recorded on this type.
     */
    private static final int ERROR_MESSAGE_WIDTH = 78;

    /**
     * Expression a present report-type selection mark has to match in full.
     *
     * <p>Assumptions: the domain is every character the baseline does not read as absence, and no
     * narrower set, because the baseline itself narrows no further. Each mark is examined only by
     * {@code WHEN <field> NOT = SPACES AND LOW-VALUES} at {@code app/cbl/CORPT00C.cbl} L213, L239
     * and L256, an abbreviated combined relation that reads as neither of the two padding
     * characters, and the only other place the marks appear is the {@code INITIALIZE} at L636 to
     * L638 that returns them to spaces. So the two characters excluded here are exactly
     * {@link FieldValidationFlag#ABSENT_INPUT_LOW_VALUE} and
     * {@link FieldValidationFlag#ABSENT_INPUT_SPACE}, written as escapes so the expression stays
     * 7-bit readable, and any other single character selects.
     *
     * <p>Alternatives Considered: restricting the marks to a specific letter, as a screen convention
     * might suggest. Rejected outright because the only accepting tests at
     * {@code app/cbl/CORPT00C.cbl} L213, L239 and L256 distinguish content from the two padding
     * values rather than naming a letter; refusing any other content here would reject input the
     * baseline processes and change observable behaviour rather than tighten an unstated contract.
     *
     * <p>Trade-offs: measured against the constructor below, this expression is belt-and-braces
     * rather than load-bearing, and saying so is the point. A one-character value made only of
     * padding is already reported absent by that constructor and never reaches a constraint, and a
     * longer value is already refused by the one-character widths at
     * {@code app/cpy-bms/CORPT00.CPY} L60, L66 and L72. What the expression buys is that the domain
     * is published: bean-validation constraints are what the service's OpenAPI contract is derived
     * from and what a caller reads, whereas a domain living only inside a private helper is enforced
     * without ever being stated.
     */
    private static final String SELECTOR_MARK = "[^\\x00 ]";

    /**
     * Expression a present range bound has to match in full.
     *
     * <p>Assumptions: this asserts shape and nothing else -- four digits, a hyphen, two digits, a
     * hyphen, two digits -- which is the separated form
     * {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} names at {@code app/cbl/CORPT00C.cbl}
     * L72 and which {@code DateEditValidator} publishes as the mask its own linkage declares. The
     * width follows from the parts rather than being asserted twice: the three declared at
     * {@code app/cpy-bms/CORPT00.CPY} L78, L84 and L90 sum to 8, the two hyphen {@code FILLER}s at
     * {@code app/cbl/CORPT00C.cbl} L62 and L64 add 2, and 10 is exactly
     * {@link DateEditValidator#MASKED_DATE_LENGTH}, which is why that constant and not a local
     * number bounds the two bound components above.
     *
     * <p>Alternatives Considered: a stricter expression that also bounded the month to 1 through 12
     * and the day to the length of that month. Rejected, and this is the most consequential
     * rejection on this type. A calendar-aware expression would refuse a well-shaped but impossible
     * bound as one shape violation naming the whole value, which is precisely the outcome the twelve
     * per-part messages at {@code app/cbl/CORPT00C.cbl} L331 to L374 and the two whole-range
     * messages at L400 and L420 exist to avoid. Shape here and calendar in
     * {@code DateEditValidator} keeps each message reachable and honours transformation rule T2,
     * which forbids restating a contract another package owns.
     *
     * <p>Assumptions: the digit class is written as an explicit range rather than the shorthand.
     * The shorthand broadens to every decimal digit in Unicode once Unicode character-class mode is
     * turned on by an embedded flag expression, so a bound carrying a non-Latin digit would satisfy
     * a constraint that neither the copybook field nor the sort symbols at
     * {@code app/jcl/TRANREPT.jcl} L43 and L44 can hold.
     */
    private static final String ISO_DATE = "[0-9]{4}-[0-9]{2}-[0-9]{2}";

    /**
     * Expression a present confirmation answer has to match in full.
     *
     * <p>Assumptions: the domain is {@code Y} and {@code N} in either case and nothing else, read
     * from the two accepting branches at {@code app/cbl/CORPT00C.cbl} L478 and L480 rather than
     * from the {@code '(Y/N)'} caption at {@code app/bms/CORPT00.bms} L213 to L217, because the
     * caption prompts a user while the branches decide the outcome. Anything else falls to the
     * baseline's own third branch at L484 to L492, which refuses the value by name, so this
     * expression does real work: a one-character answer of {@code X} passes both the width
     * constraint and the constructor below, and only this rejects it.
     *
     * <p>Alternatives Considered: accepting the upper-case forms alone and folding case in the
     * service. Rejected because both cases are accepted by separate literals in the baseline's own
     * branches at L478 and L480, so refusing a lower-case answer here would reject input the
     * baseline processes.
     */
    private static final String CONFIRM_ANSWER = "[YyNn]";

    /**
     * Normalises all thirteen components onto this record's absence contract.
     *
     * <p>Assumptions: normalising at construction rather than inside each accessor means the stored
     * state and the returned state are one state, so no caller has to know which of the two it is
     * holding. An instance cannot come into being without passing through here, which is what makes
     * the contract hold by construction rather than by every caller remembering it.
     *
     * <p>Assumptions: trailing padding is what there is to remove, because a character field of
     * declared width is left-justified and padded on the right, and every component here descends
     * from such a field -- 4 positions at {@code app/cpy-bms/CORPT00.CPY} L24 at the narrow end and
     * 78 at L120 at the wide end. Padding at the start of such a field is content rather than
     * padding, so it is left in place for the width and shape constraints above to report.
     *
     * <p>Alternatives Considered: throwing from this constructor when a component exceeds its
     * declared width was evaluated and rejected. An exception raised during request-body binding
     * does not arrive as a per-component entry; it surfaces as a single unreadable-body failure, so
     * a caller sending a 79-character message band would be told the body was unreadable rather than
     * that the {@code ERRMSGI PIC X(78)} contract at {@code app/cpy-bms/CORPT00.CPY} L120 was
     * exceeded. Width and shape are asserted by the constraints in the header instead, so both kinds
     * of malformed input take one route and both name their field. Consequently no {@code @throws}
     * tag appears on this constructor: there is no exception to document rather than one omitted.
     *
     * <p>Assumptions: nothing here decides how the components relate to one another, and the
     * omission is deliberate. Requiring that exactly one report type be selected is a rule about a
     * run rather than about a field -- the baseline states it at {@code app/cbl/CORPT00C.cbl} L438
     * with {@code 'Select a report type to print report...'} -- and composing a run is business
     * logic, which the register at {@code com.carddemo.reporting.dto} closes this package to.
     * Neither bound is converted to a date here either, for the reason recorded on this type: a
     * bound has to survive arrival intact so that a fault in it can be reported against the
     * component it belongs to.
     *
     * @param transactionName the transaction identifier as it arrived, which may be {@code null},
     *     may carry trailing padding and may be entirely padding; it is stored with the padding
     *     removed, or as {@code null} when it carries no content
     * @param title01 the upper title band on the same three terms as the transaction identifier
     *     above
     * @param currentDate the header calendar stamp on those same terms; a stamp of blanks is stored
     *     as absent rather than as a blank run, so an unpopulated header cannot be mistaken
     *     downstream for a populated one
     * @param programName the program identifier on those same terms
     * @param title02 the lower title band on those same terms
     * @param currentTime the header clock stamp on those same terms
     * @param monthly the whole-of-month selection mark on those same terms; because a mark of
     *     padding is stored as absent, a selected preset is exactly a non-null component here, which
     *     is the same test the baseline performs at L213
     * @param yearly the whole-of-year selection mark on those same terms, and absent on the same
     *     test the baseline performs at L239
     * @param custom the caller-supplied-range selection mark on those same terms, and absent on the
     *     same test the baseline performs at L256
     * @param startDate the inclusive lower bound as it arrived, which may be {@code null} and may
     *     carry trailing padding; it is stored trimmed and unparsed, so the shape constraint above
     *     rather than a conversion failure is what reports a malformed bound
     * @param endDate the inclusive upper bound on exactly the terms of the lower bound above
     * @param confirm the confirmation answer as it arrived, on the three terms of the transaction
     *     identifier above; an answer of padding is stored as absent, which is the state the
     *     baseline tests for at L464 and answers with a prompt rather than a refusal
     * @param errorMessage the message band as it arrived, on those same terms; it is normalised for
     *     consistency with the other twelve and not because anything in this context reads it
     */
    public ReportRequest {
        // Assumptions: the thirteen components are normalised independently and in the order the
        //   header declares them, because no component's treatment depends on another's. A rule
        //   relating two of them would be a rule about the run, and this record holds none, so
        //   nothing here inspects one component while handling a different one.
        transactionName = normalise(transactionName);
        title01 = normalise(title01);
        currentDate = normalise(currentDate);
        programName = normalise(programName);
        title02 = normalise(title02);
        currentTime = normalise(currentTime);
        monthly = normalise(monthly);
        yearly = normalise(yearly);
        custom = normalise(custom);
        startDate = normalise(startDate);
        endDate = normalise(endDate);
        confirm = normalise(confirm);
        errorMessage = normalise(errorMessage);
    }

    /**
     * Removes the trailing padding a declared-width field carries and reports an empty value as
     * absent.
     *
     * <p>Assumptions: the decision about what counts as absent is delegated to
     * {@link FieldValidationFlag#isNeverSupplied(String)} rather than taken here, and that
     * delegation is the whole substance of this method -- the loop above it only strips padding.
     * Delegating keeps one reading of absence for every validated field in the migration, and that
     * reading is the two-armed test the baseline performs on a field's value rather than on its
     * attribute marker, which is exactly what {@code app/cbl/CORPT00C.cbl} does at L213 and L464.
     *
     * <p>Alternatives Considered: {@link String#trim()} and {@link String#isBlank()} were evaluated
     * and each fails differently. The former also removes
     * {@link FieldValidationFlag#ABSENT_INPUT_LOW_VALUE}, which the shared-kernel test still needs
     * to see, and the latter counts any whitespace as absent while counting a run of low-value
     * characters as present, so one destroys the evidence and the other misreads it.
     *
     * <p>Assumptions: the absence test runs after the trim and not before it, and the order is
     * observable on one specific input. {@link FieldValidationFlag#isNeverSupplied(String)} reads a
     * value as absent when it is wholly one padding character or wholly the other, never a mixture,
     * so low-value characters followed by {@link FieldValidationFlag#ABSENT_INPUT_SPACE} match
     * neither arm on arrival and would be reported present. Trimming first reduces the value to
     * {@link FieldValidationFlag#ABSENT_INPUT_LOW_VALUE} characters alone, which the test does read
     * as absent.
     *
     * <p>Trade-offs: this helper is near-identical to the private normaliser on
     * {@link StatementRequest}, and the duplication is accepted rather than extracted. Extracting it
     * would mean either introducing a utility type into a package whose register admits only request
     * and response types, or reaching into that sibling record's private surface; the part worth
     * sharing, the reading of absence, is already single-sourced in
     * {@link FieldValidationFlag#isNeverSupplied(String)}, leaving only a three-line strip to repeat.
     *
     * @param raw the component value as it arrived, which may be {@code null} when the caller
     *     supplied nothing for that component at all
     * @return the value with its trailing padding removed, or {@code null} when the value carries no
     *     content by the shared kernel's absence test
     */
    private static String normalise(String raw) {
        if (raw == null) {
            return null;
        }

        // Assumptions: the padding character is taken from the shared kernel rather than written as
        //   a literal blank here, because that constant is declared as the byte FOUND IN an input
        //   field and is deliberately kept distinct there from the same byte's meaning when it is
        //   STORED IN a validation marker. Reaching for the constant keeps this loop attached to the
        //   layer it actually operates on.
        int end = raw.length();
        while (end > 0 && raw.charAt(end - 1) == FieldValidationFlag.ABSENT_INPUT_SPACE) {
            end--;
        }

        String stripped = raw.substring(0, end);
        return FieldValidationFlag.isNeverSupplied(stripped) ? null : stripped;
    }
}
