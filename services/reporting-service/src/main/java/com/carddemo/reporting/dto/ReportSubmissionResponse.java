package com.carddemo.reporting.dto;

import com.carddemo.common.time.TimestampFormatter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.regex.Pattern;

/**
 * Acknowledges that a daily transaction report request has been accepted for execution.
 *
 * <h2>Purpose: acceptance, not completion</h2>
 *
 * <p>This is what the report-request surface of {@code com.carddemo.reporting.api} returns as soon
 * as a request has been accepted and handed to the orchestration that runs it. It reports
 * acceptance and nothing beyond acceptance. No component carries a status, a progress measure, an
 * output location or a record count, because none of those exists at the moment of acceptance: the
 * report the reference job produces is written into {@code FD-REPTFILE-REC PIC X(133)} at
 * {@code app/cbl/CBTRN03C.cbl} L85, and not one of its lines has been written when this value is
 * returned. The caller observes progress through {@code executionName}, which is the name the
 * orchestration accepted the run under and the value the status operation of
 * {@code com.carddemo.reporting.api} is addressed by.
 *
 * <p>The report's own identity and the two range bounds are echoed back rather than left for the
 * caller to reconstruct. Echoing the bounds is not redundant with the request, and the preset
 * clause under Decisions below is the reason: two of the three report types resolve bounds that the
 * caller never supplied.
 *
 * <h2>What this type does not carry</h2>
 *
 * <p>This is a single acknowledgement, neither a browse result nor a report detail line, so it does
 * not declare or import the shared paging envelope; the register at
 * {@code com.carddemo.reporting.dto} owns that decision and the reasoning behind it. It carries no
 * monetary component and no numeric component of any kind, so every one of the seven values below
 * crosses the boundary as a quoted string; the same register owns the reasoning for how a monetary
 * value crosses when one is present. It carries no problem shape and declares no thrown type of
 * its own, both being consumed from the shared kernel on that register's authority. It carries no
 * repeat-entry marker, which the register records as eliminated rather than carried across. And it
 * reaches no stored data: this context owns no table and no index, and nothing here performs or
 * describes a query.
 *
 * <h2>Decisions taken on this type</h2>
 *
 * <p>Refactoring Rationale: {@code executionName} replaces the reference submission mechanism rather
 * than carrying it across, and what was wrong with that mechanism is nameable in three specific
 * places. The reference program submits the report by writing 80-position card images to a CICS
 * transient data queue: {@code app/cbl/CORPT00C.cbl} assembles the cards in working storage at L83
 * to L125, redefines that group at L126 so as to table it as
 * {@code 05 JOB-LINES OCCURS 1000 TIMES PIC X(80).} at L127, and writes the cards one at a time
 * from the loop at L498 to L508 until it meets the internal-reader end sentinel at L124 and L125.
 * The queue itself is declared at
 * {@code app/csd/CARDDEMO.CSD} L499 to L505, with {@code DDNAME(INREADER) ERROROPTION(IGNORE)} at
 * L501 and {@code RECORDSIZE(80)} at L502.
 *
 * <p>Three properties of that approach are not carried forward. First, although the program does
 * inspect the response of its own queue-write command at L525 to L535 and reports a refused command
 * on the screen, the queue resource is declared {@code ERROROPTION(IGNORE)} at L501, so an
 * input-output error on the extrapartition dataset behind the queue is ignored and the task
 * continues: the command still reads as normal, which leaves an accepted submission
 * indistinguishable from a discarded one. Second, nothing identifying the run comes back at all --
 * the acknowledgement path at L445 to L456 composes a screen message and returns no identity, so
 * there is no value to poll with, no value to retry against idempotently and no value to attribute
 * a failure to. Third, the contract is an 80-position card image rather than a typed parameter set,
 * so a malformed card is discovered only when the reader consumes it.
 *
 * <p>{@code executionName} is instead a durable, addressable orchestration handle returned
 * synchronously on acceptance, so the caller holds an identity it can observe and a refused
 * submission is an error response rather than a discarded record. The reference writes card images
 * and returns nothing; the Java returns a handle; the difference is registered as
 * <b>D-REPORT-HANDLE</b> in {@code docs/architecture/cobol-to-service-traceability.md}, which owns
 * that register. This file cites the entry and defines no entry in it.
 *
 * <p>⚠️ Refactoring Rationale: the handle this record returns is the execution NAME, and it was the
 * execution ARN in full. A review established that the ARN made the accepted run unobservable in
 * practice: the status operation of {@code com.carddemo.reporting.api} is addressed by name -- it
 * composes the ARN itself from the state machine it is configured with -- so the only value a caller
 * held was the one value that operation does not accept, and a caller passing it received a refusal
 * against a shape it had been handed. The name was already computed on both accepted paths and
 * discarded. Two further properties make the withdrawal the right correction rather than merely a
 * sufficient one: an ARN carries the account identifier and the region of the deployment that ran the
 * report, which is infrastructure detail no caller of this surface has a use for, and it carries no
 * information the name lacks, since the service derives the one from the other deterministically.
 *
 * <p>Alternatives Considered: returning both, which is the smaller edit and keeps any consumer of the
 * ARN working. Rejected on evidence rather than on principle -- there is no such consumer: the ARN
 * was read by this record's own tests and by nothing else in the repository, so retaining it would
 * publish a component whose only stated purpose was to be quoted, while the name is quotable and is
 * additionally usable. A component that no caller needs is also a component a later reader must
 * assume is load-bearing.
 *
 * <p>Assumptions: {@code executionName} is bounded at {@value #EXECUTION_NAME_WIDTH} positions and to
 * the alphabet the orchestration admits, and that bound is the orchestration's own rather than a
 * copybook's -- the submission this component replaces returned no identity, so there is no reference
 * field to read a width from. It is nonetheless opaque to this record: the value is composed
 * elsewhere, nothing here parses it, and no account identifier, region, queue name, state machine
 * name or endpoint appears anywhere in this file.
 *
 * <p>Assumptions: all seven components are {@code String}, and for the two bounds that is a reading
 * of the reference rather than a convenience. The range is compared as characters and not as dates.
 * {@code app/jcl/TRANREPT.jcl} declares the compared symbol as {@code TRAN-PROC-DT,305,10,CH} at
 * L42, a character-typed field, and selects on it with
 * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)} at L47
 * and L48. Because the separated form named by {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'}
 * at {@code app/cbl/CORPT00C.cbl} L72 orders its parts from most significant to least, a character
 * comparison over it ranks exactly as a calendar comparison would, which is why the reference never
 * converts. Typing a bound as a temporal object here would add a conversion at the boundary without
 * adding a guarantee, and would forfeit the ability to echo back the exact characters that were
 * resolved.
 *
 * <p>Assumptions: that same selection at L47 and L48 is a record-selection predicate, and the
 * service query expresses it as a SQL {@code WHERE} condition, inclusive at both ends because the
 * operators are {@code GE} and {@code LE}. It is emphatically not a step gate and must never be
 * modelled as a state-machine branch: a JCL step gate and a record filter share the keyword
 * {@code COND}, and conflating the two turns a row filter into a control-flow decision. The
 * register at {@code com.carddemo.reporting.dto} records the no-data-access position that keeps the
 * query itself outside this package.
 *
 * <p>Assumptions: {@code reportName} carries one of exactly three values, spelled character for
 * character as the reference spells them. {@code app/cbl/CORPT00C.cbl} holds the name in
 * {@code WS-REPORT-NAME PIC X(10)} at L58 and assigns it exactly three times: {@code 'Monthly'} at
 * L214, {@code 'Yearly'} at L240 and {@code 'Custom'} at L433. Each is singular, each carries a
 * leading capital, and each is carried across unaltered under transformation rule T8; none is
 * pluralised, re-cased, expanded, or derived from an enumeration name by string manipulation. The
 * value is also always present on this path, because the arm that selects no report type at L437 to
 * L442 raises the error flag at L440, leaving the acknowledgement branch guarded by
 * {@code IF NOT ERR-FLG-ON} at L445 reachable only with one of the three names in place.
 *
 * <p>Assumptions: the two preset report types resolve their upper bound by different means, and the
 * asymmetry is deliberate rather than an inconsistency. The whole-of-month preset at L213 to L236
 * sets the lower bound to the current month's first date at L217 to L219, then derives the upper
 * bound at L223 to L234 by advancing the month and stepping back one from the integer date, which
 * lands on the current month's final date. The whole-of-year preset at L239 to L252 sets the lower
 * bound to the first of January at L243 to L246 and takes its upper bound from the two literals
 * {@code '12'} at L250 and {@code '31'} at L251, so it always ends on 31 December. Either preset
 * can therefore resolve an upper bound lying ahead of the point at which the request was accepted,
 * which is the whole reason both bounds are components here rather than being treated as redundant:
 * the caller can see what its preset expanded to. The third type, {@code 'Custom'} at L433, takes
 * both bounds from the caller and is echoed back on the same terms.
 *
 * <p>Assumptions: the two bounds echoed here are the same pair the report job receives, and the
 * batch form of that pair is an 80-position card image which this record does not build.
 * {@code FILLER-3} at {@code app/cbl/CORPT00C.cbl} L117 to L121 lays out
 * {@code PARM-START-DATE-2 PIC X(10)}, a one-character {@code FILLER} holding a space,
 * {@code PARM-END-DATE-2 PIC X(10)} and a 59-character {@code FILLER}, which is 80 positions
 * exactly. Three further artifacts corroborate that layout: {@code app/cbl/CBTRN03C.cbl} reads it
 * back as {@code 01 WS-DATEPARM-RECORD.} at L122 to L125, where the one-character separator is the
 * declared {@code FILLER PIC X(01)} at L124 and not incidental whitespace; the record it is read
 * from is {@code FD-DATEPARM-REC PIC X(80)} at L88; and the queue carrying the same card size
 * declares {@code RECORDSIZE(80)} at {@code app/csd/CARDDEMO.CSD} L502. The job step binds it as
 * the {@code DATEPARM} data definition at {@code app/jcl/TRANREPT.jcl} L73 and L74, and the
 * sort-time equivalents of the same pair are {@code PARM-START-DATE,C'2022-01-01'} at L43 and
 * {@code PARM-END-DATE,C'2022-07-06'} at L44. This record is a JSON payload: the 80-position
 * encoding belongs to {@code com.carddemo.reporting.service} and the shared codec, and is recorded
 * here only so that these two values are recognised as that same contract and are therefore neither
 * widened nor reordered.
 *
 * <p>Assumptions: deciding whether a bound names a real date belongs to
 * {@code com.carddemo.common.validation.DateEditValidator} and to the request half of this pair,
 * not to this record, and one detail of that validator's reference parameter block is recorded here
 * because a shorter summary of it circulates and is incomplete.
 * {@code 01 CSUTLDTC-PARM.} at {@code app/cbl/CORPT00C.cbl} L129 to L136 is 100 positions:
 * {@code CSUTLDTC-DATE PIC X(10)} at L130, {@code CSUTLDTC-DATE-FORMAT PIC X(10)} at L131 and
 * {@code CSUTLDTC-RESULT} at L132, the last comprising
 * {@code CSUTLDTC-RESULT-SEV-CD PIC X(04)} at L133, an 11-character {@code FILLER} at L134,
 * {@code CSUTLDTC-RESULT-MSG-NUM PIC X(04)} at L135 and
 * {@code CSUTLDTC-RESULT-MSG PIC X(61)} at L136. Naming only the severity code, the message number
 * and the message text reaches 69 and omits the {@code FILLER} at L134; a total of 79 omits both
 * that {@code FILLER} and the format field at L131, and those two together are the missing 21
 * positions.
 *
 * <p>Assumptions: {@code shortName} and {@code longName} carry the report's own header identity
 * verbatim from {@code app/cpy/CVTRA07Y.cpy}, whose {@code 01 REPORT-NAME-HEADER.} group opens at
 * L4. {@code REPT-SHORT-NAME PIC X(38)} at L5 holds {@code 'DALYREPT'} and
 * {@code REPT-LONG-NAME PIC X(41)} at L7 holds {@code 'Daily Transaction Report'}; both are carried
 * character for character under transformation rule T8. Each component is bounded at the width the
 * copybook declares, 38 and 41, even though both literals are shorter, because the declared width
 * and not the literal length is what the 133-column header line is composed against.
 *
 * <p>Assumptions: two literals in that same group carry spaces inside the literal, and those spaces
 * are data. {@code REPT-DATE-HEADER PIC X(12)} at L9 holds {@code 'Date Range: '} with a trailing
 * space, and the {@code FILLER PIC X(04)} at L12 separating the two bounds holds {@code ' to '}
 * with a leading space and a trailing space both. Trimming either one alters the header line, so
 * neither may be normalised away. This record carries values only; composing them into the header,
 * together with the two bound positions at L11 and L13, belongs to
 * {@code com.carddemo.reporting.mapper}.
 *
 * <p>Assumptions: {@code submittedAt} is an opaque character value of exactly
 * {@link TimestampFormatter#TIMESTAMP_LENGTH} positions and is never held as a temporal object, for
 * two reasons that both need stating. The reference declares its timestamps as character fields of
 * that same width, {@code TRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy} L16 and
 * {@code TRAN-PROC-TS PIC X(26)} at L17, so a character form is the contract rather than a
 * rendering choice. And an all-blank value of that width is legitimate reference data which has to
 * round-trip unchanged, where a strict conversion would raise on it instead.
 * {@link StatementTransactionResponse} in this package applies the same discipline to both of its
 * timestamps and has a further and stronger reason of its own, which is recorded there.
 * {@link TimestampFormatter} is the producer of well-formed values on the write side and publishes
 * that same width as the single length of the rendering it emits.
 *
 * <p>Alternatives Considered: this constructor refuses a malformed value where the request half of
 * the pair deliberately does not, and the divergence between the two is reasoned rather than
 * accidental. {@link ReportRequest} declines to throw because it is bound from a caller's request
 * body, where an exception raised during binding surfaces as one unreadable-body failure naming no
 * field and would discard the per-field error contract; it therefore asserts shape through
 * constraints and leaves refusal to the validation layer. None of that argument applies to a value
 * this service constructs on the way out. Here a violation is an internal defect rather than caller
 * input, and the alternative of storing it unchecked would let a plausible-looking acknowledgement
 * leave the process carrying a handle nobody can observe, or a bound that does not match what was
 * resolved. Refusing at construction confines that class of defect to the site that produced it.
 *
 * <p>Trade-offs: each declared width is therefore stated twice, once as a constraint annotation on
 * the component and once as a check in the constructor, so one width has two reading sites. The
 * duplication is accepted because the two serve different readers: the annotation is what the
 * published contract of this surface is generated from, so a consumer sees the width without
 * reading the body, while the check is what holds at run time for a value this service builds
 * rather than receives. The compensating discipline is that both sites read the same named
 * constant, so they cannot drift apart, and that constant is always the copybook's declared width
 * and never a rounded one.
 *
 * <p>Everything under {@code app/} that this file cites is reference material and remains
 * byte-identical; no statement here describes an edit to it. Two framings are used and no third:
 * the reference does one thing, the Java does another, and the difference is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md} -- for this record, as the entry
 * {@code D-REPORT-HANDLE}; or the Java encodes a stated rule.
 * The documentation convention this file follows, including the spelling of the four rationale
 * labels above, is {@code docs/CODE_DOCUMENTATION_STANDARD.md}. A type declaration accepts no
 * argument list of its own and raises nothing, so of the four docstring elements only purpose and
 * parameters apply to this block; the seven component parameters follow.
 *
 * @param executionName the {@code String} orchestration handle assigned to the accepted run, never
 *     {@code null} and never blank; it is the value through which the caller observes progress --
 *     the status operation of {@code com.carddemo.reporting.api} takes exactly this value -- it is
 *     opaque to this record, it is bounded at the {@value #EXECUTION_NAME_WIDTH} positions the
 *     orchestration admits, and it has no reference counterpart, the submission it replaces having
 *     returned no identity at all
 * @param reportName the {@code String} resolved report type spelled exactly as the reference spells
 *     it, one of {@code 'Monthly'}, {@code 'Yearly'} or {@code 'Custom'} as assigned at
 *     {@code app/cbl/CORPT00C.cbl} L214, L240 and L433, within the 10 positions
 *     {@code WS-REPORT-NAME PIC X(10)} declares at L58
 * @param shortName the {@code String} short header name of the report, {@code 'DALYREPT'}, bounded
 *     at the 38 positions {@code REPT-SHORT-NAME PIC X(38)} declares at
 *     {@code app/cpy/CVTRA07Y.cpy} L5
 * @param longName the {@code String} long header name of the report,
 *     {@code 'Daily Transaction Report'}, bounded at the 41 positions
 *     {@code REPT-LONG-NAME PIC X(41)} declares at {@code app/cpy/CVTRA07Y.cpy} L7
 * @param startDate the {@code String} resolved inclusive lower bound of the report range, 10
 *     characters in the separated form named at {@code app/cbl/CORPT00C.cbl} L72 and occupying the
 *     10 positions {@code REPT-START-DATE PIC X(10)} declares at {@code app/cpy/CVTRA07Y.cpy} L11;
 *     echoed back so that a caller can see what a preset expanded to
 * @param endDate the {@code String} resolved inclusive upper bound on exactly the terms of the lower
 *     bound above, occupying the 10 positions {@code REPT-END-DATE PIC X(10)} declares at
 *     {@code app/cpy/CVTRA07Y.cpy} L13; inclusive because the selection at
 *     {@code app/jcl/TRANREPT.jcl} L47 and L48 compares the upper end with {@code LE}
 * @param submittedAt the {@code String} point at which the request was accepted, rendered by
 *     {@link TimestampFormatter} as exactly {@link TimestampFormatter#TIMESTAMP_LENGTH} characters
 *     and carried opaquely, an all-blank value of that width being legitimate
 */
public record ReportSubmissionResponse(
        @NotBlank @Size(max = EXECUTION_NAME_WIDTH) String executionName,
        @Size(max = REPORT_NAME_WIDTH) String reportName,
        @Size(max = SHORT_NAME_WIDTH) String shortName,
        @Size(max = LONG_NAME_WIDTH) String longName,
        @Size(max = DATE_WIDTH) String startDate,
        @Size(max = DATE_WIDTH) String endDate,
        @Size(max = TimestampFormatter.TIMESTAMP_LENGTH) String submittedAt) {

    /**
     * Positions the orchestration admits for an execution name.
     *
     * <p>Assumptions: 80 is the orchestration's own published limit on an execution name and is
     * therefore the one width in this record that is NOT read from a copybook -- the submission
     * mechanism this component replaces returned no identity, so there is no reference field to read.
     * The same figure is what {@code reporting-api.yaml} publishes as the {@code ExecutionName}
     * schema and what {@code com.carddemo.reporting.service.ReportExecutionService} derives its
     * submission-key ceiling from, so a value this record accepts is a value that operation can be
     * addressed with.
     *
     * <p>Alternatives Considered: importing the service's own constant so the figure has one
     * declaration. Rejected because it would point a dependency from this package at the service
     * package that constructs its values, inverting the direction every other type here follows and
     * putting a response shape behind a service class. The compensating discipline is the one the
     * copybook widths already use: the figure is stated once, in this constant, with its provenance
     * beside it.
     */
    private static final int EXECUTION_NAME_WIDTH = 80;

    /**
     * Positions the reference declares for the report type name.
     *
     * <p>Assumptions: 10 is read from {@code WS-REPORT-NAME PIC X(10)} at
     * {@code app/cbl/CORPT00C.cbl} L58, the field that every one of the three assignments at L214,
     * L240 and L433 targets. The three values are shorter than that, the longest of them being
     * seven characters, and this bound is deliberately the field's declared width rather than the
     * longest literal: a bound derived from the literals would have to move if the reference ever
     * assigned a fourth name, whereas the declared width is what the reference itself reserves.
     *
     * <p>Alternatives Considered: writing 10 straight into the constraint annotation was the
     * obvious alternative and is rejected on the ground {@link ReportRequest} records for its own
     * widths -- a named constant leaves exactly one line to compare against L58, whereas a literal
     * inside an annotation is a number with no stated provenance sitting where nobody looks for
     * one.
     */
    private static final int REPORT_NAME_WIDTH = 10;

    /**
     * Positions the reference declares for the report's short header name.
     *
     * <p>Assumptions: 38 is read from {@code REPT-SHORT-NAME PIC X(38)} at
     * {@code app/cpy/CVTRA07Y.cpy} L5, whose value literal {@code 'DALYREPT'} occupies only eight
     * of them. The declared width is the bound because the header band is composed against the
     * group declared from L4 onwards and not against the literal, so a component held to the
     * literal's length could not carry a value the copybook admits.
     */
    private static final int SHORT_NAME_WIDTH = 38;

    /**
     * Positions the reference declares for the report's long header name.
     *
     * <p>Assumptions: 41 is read from {@code REPT-LONG-NAME PIC X(41)} at
     * {@code app/cpy/CVTRA07Y.cpy} L7, whose value literal {@code 'Daily Transaction Report'}
     * occupies 24 of them, and the same declared-width reasoning as the short name above applies
     * unchanged.
     */
    private static final int LONG_NAME_WIDTH = 41;

    /**
     * Positions a resolved range bound occupies, in the separated form the reference uses.
     *
     * <p>Assumptions: 10 is corroborated on both sides of the same contract.
     * {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} at {@code app/cbl/CORPT00C.cbl} L72 names
     * the form, the composites at L60 to L71 assemble exactly that many characters from parts
     * separated by the literal hyphen fillers at L62, L64, L68 and L70, and the report header holds
     * the resolved pair in {@code REPT-START-DATE PIC X(10)} at {@code app/cpy/CVTRA07Y.cpy} L11
     * and {@code REPT-END-DATE PIC X(10)} at L13. One constant serves both bounds because the two
     * are the same contract read from the same declarations.
     */
    private static final int DATE_WIDTH = 10;

    /**
     * The whole-of-month report type name, spelled as the reference spells it.
     *
     * <p>Assumptions: the value is the literal assigned at {@code app/cbl/CORPT00C.cbl} L214 and is
     * carried character for character under transformation rule T8. The preset it names resolves
     * the current month's first date as its lower bound at L217 to L219 and the current month's
     * final date as its upper bound at L223 to L234.
     */
    private static final String MONTHLY_REPORT_NAME = "Monthly";

    /**
     * The whole-of-year report type name, spelled as the reference spells it.
     *
     * <p>Assumptions: the value is the literal assigned at {@code app/cbl/CORPT00C.cbl} L240 and is
     * carried character for character under transformation rule T8. The preset it names resolves
     * the first of January as its lower bound at L243 to L246 and 31 December as its upper bound
     * from the literals at L250 and L251.
     */
    private static final String YEARLY_REPORT_NAME = "Yearly";

    /**
     * The caller-supplied-range report type name, spelled as the reference spells it.
     *
     * <p>Assumptions: the value is the literal assigned at {@code app/cbl/CORPT00C.cbl} L433 and is
     * carried character for character under transformation rule T8. It is the one type under which
     * both echoed bounds are the caller's own values, the two presets above having resolved theirs
     * from the calendar.
     */
    private static final String CUSTOM_REPORT_NAME = "Custom";

    /**
     * Shape of a resolved range bound: four digits, a hyphen, two digits, a hyphen, two digits.
     *
     * <p>Assumptions: the shape is the separated form that
     * {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} names at {@code app/cbl/CORPT00C.cbl}
     * L72, which is also the form the two sort symbols carry at {@code app/jcl/TRANREPT.jcl} L43
     * and L44.
     *
     * <p>Assumptions: this checks the shape of a value and deliberately not the calendar, so it
     * admits a date the calendar does not have. That is correct at this boundary: whether a bound
     * names a real date is decided by {@code com.carddemo.common.validation.DateEditValidator}
     * against the parameter block at L129 to L136 on the request path, before anything is accepted
     * at all. By the time this record is built the bounds are already resolved, so re-deciding them
     * here would put that authority in a second place and invite the two to disagree.
     *
     * <p>Trade-offs: the shape is compiled once into this constant rather than handed to
     * {@link String#matches(String)} at each construction, which recompiles it on every call. The
     * cost is one static field held for the lifetime of the class; the alternative pays for a
     * compilation on a path that runs once per accepted request.
     */
    private static final Pattern ISO_DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    /**
     * Alphabet an execution name is composed from: letters, digits, hyphens and underscores.
     *
     * <p>Assumptions: the set is the orchestration's, and it is asserted here because the name is the
     * one component that travels back out as a path segment. A value carrying a character outside this
     * set would compose a target the status operation refuses -- its own path parameter publishes this
     * same alphabet -- so a caller would receive a handle it could not use, which is the defect the
     * name was introduced to close and not a new one to reintroduce.
     *
     * <p>Trade-offs: the shape is compiled once into this constant for the same reason the bound shape
     * above is, rather than handed to {@link String#matches(String)} on a path that runs once per
     * accepted request.
     */
    private static final Pattern EXECUTION_NAME_SHAPE = Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * Accepts the seven resolved values and refuses any that this service should not have produced.
     *
     * <p>Assumptions: the seven components are checked independently and in the order the header
     * declares them, because no component's validity depends on another's. Checking that the two
     * bounds are correctly ordered relative to each other, or that the report type agrees with the
     * bounds a preset would have resolved, would be a rule about a run rather than about a value,
     * and composing a run is service work that the register at
     * {@code com.carddemo.reporting.dto} closes this package to.
     *
     * <p>Assumptions: neither bound is converted and neither header name is padded here.
     * Conversion would lose the exact characters that were resolved, which is the one thing those
     * two components exist to return, and padding a name out to its declared width is composition
     * work belonging to {@code com.carddemo.reporting.mapper} at the point the 133-column header
     * line is built.
     *
     * @param executionName the {@code String} orchestration handle to store; refused when absent,
     *     blank, longer than the {@value #EXECUTION_NAME_WIDTH} positions the orchestration admits, or
     *     carrying a character outside the alphabet it composes names from
     * @param reportName the {@code String} resolved report type to store; refused unless it is one
     *     of the three values the reference assigns at {@code app/cbl/CORPT00C.cbl} L214, L240 and
     *     L433
     * @param shortName the {@code String} short header name to store; refused when absent or longer
     *     than the 38 positions declared at {@code app/cpy/CVTRA07Y.cpy} L5
     * @param longName the {@code String} long header name to store; refused when absent or longer
     *     than the 41 positions declared at {@code app/cpy/CVTRA07Y.cpy} L7
     * @param startDate the {@code String} resolved inclusive lower bound to store; refused unless it
     *     is 10 characters in the separated form named at {@code app/cbl/CORPT00C.cbl} L72
     * @param endDate the {@code String} resolved inclusive upper bound to store, on the terms of the
     *     lower bound above
     * @param submittedAt the {@code String} acceptance timestamp to store; refused unless it is
     *     exactly {@link TimestampFormatter#TIMESTAMP_LENGTH} characters, a width that an all-blank
     *     value satisfies
     * @throws IllegalArgumentException if the handle is absent, blank, over-long or outside the
     *     orchestration's alphabet, if the report type is not one of the three reference values, if
     *     either header name is absent or exceeds its declared width, if either bound is absent or is
     *     not 10 characters in the separated form, or if the timestamp is absent or is not exactly
     *     {@link TimestampFormatter#TIMESTAMP_LENGTH} characters
     */
    public ReportSubmissionResponse {
        executionName = requireExecutionName(executionName);
        reportName = requireReferenceReportName(reportName);
        shortName = requireAtMost(shortName, SHORT_NAME_WIDTH, "shortName");
        longName = requireAtMost(longName, LONG_NAME_WIDTH, "longName");
        startDate = requireResolvedBound(startDate, "startDate");
        endDate = requireResolvedBound(endDate, "endDate");

        // Assumptions: the timestamp is measured and not shaped, because the width is the whole of
        //   its contract here. An all-blank value of this width is legitimate reference data, so a
        //   blankness test or a conversion would refuse a value the reference admits, whereas a
        //   width test accepts it and still catches a value rendered without its fractional part.
        submittedAt = requireExactly(
                submittedAt, TimestampFormatter.TIMESTAMP_LENGTH, "submittedAt");
    }

    /**
     * Returns the orchestration handle through which the caller observes the accepted run.
     *
     * @return the {@code String} execution name, never {@code null} and never blank, opaque to this
     *     record, within the {@value #EXECUTION_NAME_WIDTH} positions the orchestration admits, and
     *     exactly the value the status operation of {@code com.carddemo.reporting.api} is addressed by
     */
    public String executionName() {
        return executionName;
    }

    /**
     * Returns the resolved report type as the reference spells it.
     *
     * @return the {@code String} report type, one of the three values assigned at
     *     {@code app/cbl/CORPT00C.cbl} L214, L240 and L433, within the 10 positions declared at
     *     L58
     */
    public String reportName() {
        return reportName;
    }

    /**
     * Returns the report's short header name.
     *
     * @return the {@code String} short header name, bounded at the 38 positions declared at
     *     {@code app/cpy/CVTRA07Y.cpy} L5 and carried without padding
     */
    public String shortName() {
        return shortName;
    }

    /**
     * Returns the report's long header name.
     *
     * @return the {@code String} long header name, bounded at the 41 positions declared at
     *     {@code app/cpy/CVTRA07Y.cpy} L7 and carried without padding
     */
    public String longName() {
        return longName;
    }

    /**
     * Returns the resolved inclusive lower bound of the report range.
     *
     * @return the {@code String} lower bound as 10 characters in the separated form named at
     *     {@code app/cbl/CORPT00C.cbl} L72, exactly as it was resolved
     */
    public String startDate() {
        return startDate;
    }

    /**
     * Returns the resolved inclusive upper bound of the report range.
     *
     * @return the {@code String} upper bound on the terms of the lower bound above, inclusive
     *     because the selection at {@code app/jcl/TRANREPT.jcl} L47 and L48 compares this end with
     *     {@code LE}
     */
    public String endDate() {
        return endDate;
    }

    /**
     * Returns the point at which the request was accepted, as an opaque character value.
     *
     * @return the {@code String} timestamp, exactly {@link TimestampFormatter#TIMESTAMP_LENGTH}
     *     characters and unparsed, an all-blank value of that width being a legitimate one
     */
    public String submittedAt() {
        return submittedAt;
    }

    /**
     * Requires a component to carry content, used for the one component absence would make useless.
     *
     * <p>Alternatives Considered: absence is tested with {@link String#isBlank()} rather than with
     * the shared kernel's input-absence test. That test reads a value as absent only when it is
     * wholly one padding character or wholly the other, and it exists to interpret a value arriving
     * from a 3270 input field; no component of this record arrives from such a field. Applying a
     * blankness test of any kind to the timestamp would also refuse the legitimate all-blank value
     * recorded on this type, which is why only the handle is tested for content while the timestamp
     * is tested for width.
     *
     * @param value the {@code String} component value as the service supplied it, which may be
     *     {@code null}
     * @param component the {@code String} component name, used to name the offending component in
     *     the refusal
     * @return the same {@code String} unchanged, once it is known to carry content
     * @throws IllegalArgumentException if the value is {@code null}, empty, or entirely whitespace,
     *     because a handle a caller cannot observe is no better than no handle at all
     */
    private static String requireContent(String value, String component) {
        if (value == null || value.isBlank()) {
            // Assumptions: the refusal names the component and deliberately does not reproduce the
            //   value. There is nothing diagnostic in echoing an absent or blank handle, and a
            //   message that quotes an identifier is a message that can be copied somewhere it was
            //   not meant to go.
            throw new IllegalArgumentException(component + " must carry content");
        }

        return value;
    }

    /**
     * Requires the orchestration handle to be a name the status operation can be addressed by.
     *
     * <p>Assumptions: three properties are asserted in the order a maintainer can act on them --
     * content, then width, then alphabet -- so an absent handle is reported as absent, an over-long
     * one names both lengths, and only a value that is present and short enough is measured against
     * the alphabet. Testing the alphabet first would report a shape fault for a value whose real
     * defect was its length.
     *
     * <p>Assumptions: this check exists because the handle is the one component that travels back out
     * as a path segment. Every other component is read and rendered; this one is REPLAYED, so a value
     * this record accepted but the status path refuses would hand a caller an unusable handle -- which
     * is the defect that withdrew the ARN, and re-admitting it through a lax check would restore it in
     * a form that is harder to see.
     *
     * @param value the {@code String} execution name as the service supplied it, which may be
     *     {@code null}
     * @return the same {@code String} unchanged, once it is known to be addressable
     * @throws IllegalArgumentException if the value is absent, blank, longer than
     *     {@value #EXECUTION_NAME_WIDTH} positions, or carries a character outside the alphabet the
     *     orchestration composes names from
     */
    private static String requireExecutionName(String value) {
        String name = requireAtMost(
                requireContent(value, "executionName"), EXECUTION_NAME_WIDTH, "executionName");

        if (!EXECUTION_NAME_SHAPE.matcher(name).matches()) {
            // Assumptions: the refusal describes the admitted alphabet and does NOT reproduce the
            //   offending name, on the same ground the absent-handle refusal above gives: a message
            //   quoting an identifier is a message that can be copied somewhere it was not meant to go.
            throw new IllegalArgumentException(
                    "executionName must carry only letters, digits, hyphens and underscores");
        }

        return name;
    }

    /**
     * Requires the report type to be one of the three values the reference assigns.
     *
     * <p>Alternatives Considered: bounding the value only by the 10 positions declared at
     * {@code app/cbl/CORPT00C.cbl} L58 was the looser alternative and is rejected. That field takes
     * exactly three values on this path, at L214, L240 and L433, and the acknowledgement branch at
     * L445 is unreachable without one of them because the arm selecting no type raises the error
     * flag at L440. A width-only bound would therefore admit a fourth spelling that no reference
     * artifact produces, and the caller would receive a report type it cannot act on.
     *
     * @param value the {@code String} resolved report type as the service supplied it, which may be
     *     {@code null}
     * @return the same {@code String} unchanged, once it is known to be one of the three reference
     *     values
     * @throws IllegalArgumentException if the value is not one of those three, {@code null}
     *     included, since a name outside them cannot have come from the reference selection
     */
    private static String requireReferenceReportName(String value) {
        // Assumptions: the comparison is anchored on the constants rather than on the argument, so a
        //   null argument is refused by the same three tests as a misspelled one and no separate
        //   null branch is needed to reach the same refusal.
        if (!MONTHLY_REPORT_NAME.equals(value)
                && !YEARLY_REPORT_NAME.equals(value)
                && !CUSTOM_REPORT_NAME.equals(value)) {
            throw new IllegalArgumentException(
                    "reportName must be one of " + MONTHLY_REPORT_NAME + ", " + YEARLY_REPORT_NAME
                            + " or " + CUSTOM_REPORT_NAME + ", but was " + value);
        }

        return value;
    }

    /**
     * Requires a component to be present and no wider than the width its copybook declares.
     *
     * <p>Assumptions: the bound is an upper bound and not an exact width, because the two header
     * names it serves hold literals shorter than their declarations -- eight characters in 38 at
     * {@code app/cpy/CVTRA07Y.cpy} L5 and 24 in 41 at L7. Padding a value out to its declared width
     * is header-composition work and belongs to {@code com.carddemo.reporting.mapper}, so a value
     * arriving here unpadded is correct rather than short.
     *
     * @param value the {@code String} component value as the service supplied it, which may be
     *     {@code null}
     * @param width the {@code int} declared width read from the copybook, never a rounded or an
     *     invented number
     * @param component the {@code String} component name, used to name the offending component in
     *     the refusal
     * @return the same {@code String} unchanged, once it is known to fit the declared width
     * @throws IllegalArgumentException if the value is {@code null} or longer than {@code width},
     *     an over-long value being one the reference record could not have held
     */
    private static String requireAtMost(String value, int width, String component) {
        if (value == null) {
            throw new IllegalArgumentException(component + " must be present");
        }

        if (value.length() > width) {
            // Assumptions: the refusal reports both the bound and the length found, because the
            //   width is the copybook's and a maintainer comparing the two needs the actual length
            //   to know whether the value or the bound is the thing that is wrong.
            throw new IllegalArgumentException(
                    component + " must be at most " + width + " characters, but was "
                            + value.length());
        }

        return value;
    }

    /**
     * Requires a component to be present and to occupy exactly the width its declaration reserves.
     *
     * <p>Assumptions: an exact width rather than an upper bound is right for the one component this
     * serves, because {@link TimestampFormatter} renders every value at one width and the reference
     * field is a character field of that same width at {@code app/cpy/CVTRA05Y.cpy} L16 and L17. A
     * value arriving shorter has lost part of the rendering, most likely its fractional part, and a
     * value arriving longer is not the rendering at all.
     *
     * @param value the {@code String} component value as the service supplied it, which may be
     *     {@code null}
     * @param width the {@code int} number of positions the declaration reserves, matched exactly
     * @param component the {@code String} component name, used to name the offending component in
     *     the refusal
     * @return the same {@code String} unchanged, once it is known to occupy exactly that many
     *     positions
     * @throws IllegalArgumentException if the value is {@code null} or does not occupy exactly
     *     {@code width} positions
     */
    private static String requireExactly(String value, int width, String component) {
        if (value == null) {
            throw new IllegalArgumentException(component + " must be present");
        }

        if (value.length() != width) {
            throw new IllegalArgumentException(
                    component + " must be exactly " + width + " characters, but was "
                            + value.length());
        }

        return value;
    }

    /**
     * Requires a range bound to be present and to carry the separated shape the reference uses.
     *
     * <p>Assumptions: the width is asserted before the shape, and the order is what makes a refusal
     * diagnosable rather than merely correct. A value of the wrong width is reported as a width
     * fault naming the two lengths, whereas leaving it to the shape test would report only that the
     * value does not match a shape, which tells a maintainer nothing about which part of it was
     * wrong.
     *
     * @param value the {@code String} resolved bound as the service supplied it, which may be
     *     {@code null}
     * @param component the {@code String} component name, used to name the offending component in
     *     the refusal
     * @return the same {@code String} bound, unchanged and unconverted, holding the exact characters
     *     that were resolved
     * @throws IllegalArgumentException if the bound is absent, does not occupy the 10 positions
     *     declared at {@code app/cpy/CVTRA07Y.cpy} L11 and L13, or does not carry the separated
     *     shape named at {@code app/cbl/CORPT00C.cbl} L72
     */
    private static String requireResolvedBound(String value, String component) {
        String bound = requireExactly(value, DATE_WIDTH, component);

        if (!ISO_DATE.matcher(bound).matches()) {
            throw new IllegalArgumentException(
                    component + " must be four digits, a hyphen, two digits, a hyphen and two"
                            + " digits, but was " + bound);
        }

        return bound;
    }
}
