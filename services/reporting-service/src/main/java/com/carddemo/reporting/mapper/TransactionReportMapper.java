package com.carddemo.reporting.mapper;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Emits the eight bands of the 133-column daily transaction report, byte for byte.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class is the emission half of the report seam. {@link ReportBandLayouts} declares where
 * every byte of a band goes and which literals it carries; {@link CobolEditMask} renders an amount
 * into the display form its picture clause declares; and this class composes a value map for one
 * band, hands it to {@link FixedWidthCodec#encodeRecord(Map, CopybookLayout.RecordSpec)} and
 * returns the resulting record. It is the boundary at which declared widths, trailing blanks,
 * {@code FILLER} content, zero-padding, leftmost truncation and sign placement are allowed to
 * appear, and the package charter beside this file confines them to exactly here.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This type is a static emitter that cannot be
 * instantiated, so it accepts no parameter, yields no value and raises nothing of its own; every
 * member below carries its own parameter, return and exception at-clauses. The inapplicability is
 * written out rather than passed over, because user-specified Rule 1 (Explainability) forbids at
 * its line 39 a docstring that omits parameters, return values or purpose, and a reader has to be
 * able to tell a declared inapplicability from an omission. One error can arise without any member
 * being called: the blank-line descriptor declared below chains
 * {@link CopybookLayout.RecordSpec#validateGeometry()} in its initialiser, so a mis-stated width
 * surfaces as a {@link CopybookLayout.LayoutException} wrapped in an initialisation error at class
 * load rather than as a wrong report much later.</p>
 *
 * <h2>Assumptions: 133 is a declared record length and never a sum of field widths</h2>
 *
 * <p>Every record this class returns is exactly 133 bytes, and that number is a property of the
 * data set the report is written to rather than an arithmetic result. It is anchored three
 * independent ways. Line 48 of {@code app/cpy/CVTRA07Y.cpy} declares an elementary level-01 item of
 * {@code PIC X(133)}. Line 85 of {@code app/cbl/CBTRN03C.cbl} declares the output record at that
 * width and line 133 of the same program declares a blank line at it. Line 78 of
 * {@code app/jcl/TRANREPT.jcl} names the same 133 as the logical record length of the output data
 * set. Summing the declared widths of a band yields a different number every time -- 115 for the
 * title band, 114 for the detail band and for the first heading band, 133 for the rule line alone,
 * and 112 for each of the three total bands -- so the record length must never be derived from one
 * of those sums.</p>
 *
 * <p>Assumptions: the blank right-padding that carries six of the seven copybook bands from their
 * native width out to 133 is performed by
 * {@link FixedWidthCodec#encodeRecord(Map, CopybookLayout.RecordSpec)} against a descriptor that
 * declares the shortfall as a trailing field, and never by this class. The codec allocates an array
 * of exactly the declared record length and fills each declared interval, so a band cannot come
 * back short or long. Padding a column by hand here would put the same width in two places, and the
 * two would disagree the first time one of them was edited.</p>
 *
 * <h2>The eight bands, and the one emission sequence this class owns</h2>
 *
 * <p>Seven bands are declared in {@code app/cpy/CVTRA07Y.cpy} and reach this class through
 * {@link ReportBandLayouts}: the title band at lines 4 to 13, the detail band at lines 15 to 31,
 * the column heading band at lines 33 to 46, the rule line at line 48, and the page, card-break and
 * grand total bands at lines 50 to 54, 56 to 60 and 62 to 66. The eighth is not in that copybook at
 * all: it is the wholly blank line declared {@code PIC X(133) VALUE SPACES} at line 133 of
 * {@code app/cbl/CBTRN03C.cbl} and moved into the output record at line 329 of that program.</p>
 *
 * <p>Alternatives Considered: obtaining the blank-line descriptor from {@link ReportBandLayouts}
 * rather than declaring it here. That holder deliberately declares none, on the ground that it
 * transcribes {@code app/cpy/CVTRA07Y.cpy} and the blank line is not in it, and it hands the
 * emission sequence to this class instead. Reusing the rule-line descriptor with blank content was
 * also evaluated: it would produce the correct 133 bytes, because the codec blank-fills the
 * remainder of a short value, but every diagnostic the codec raised would then name the rule line
 * while describing a blank line. {@link CopybookLayout.RecordSpec} and
 * {@link CopybookLayout#text(String, int, int)} are public so that a consumer may declare a
 * descriptor of its own, so the blank line is declared below under its own baseline name and still
 * encoded through the one codec entry point.</p>
 *
 * <p>Assumptions: the heading sequence is a single baseline paragraph and is reproduced as one
 * method rather than left to a caller to order. Lines 325, 329, 333 and 337 of
 * {@code app/cbl/CBTRN03C.cbl} emit the title band, then the blank line, then the column heading
 * band, then the rule line, in that order and with no other content between them. The blank line
 * exists only at that one position, so a caller assembling the four bands itself would have nothing
 * to check its ordering against.</p>
 *
 * <p>Assumptions: the rule line is emitted at THREE separate sites in the baseline -- lines 300,
 * 312 and 337 of {@code app/cbl/CBTRN03C.cbl} -- all three funnelling into the single physical
 * write at line 345 of that program, which raises an internal result code of 12 and abends on any
 * status other than {@code '00'}. Every one of those occurrences is part of the byte stream the
 * parity comparison reads. This class therefore exposes the rule line as a band an emitter calls as
 * many times as the sequence requires, and collapsing repeated occurrences into one would shorten
 * the artifact.</p>
 *
 * <h2>Assumptions: three truncations are this class's obligation, and each is unconditional</h2>
 *
 * <p>{@link FixedWidthCodec} raises a field failure when a character value is wider than the field
 * it is given, rather than shortening it, so nothing downstream will truncate on this class's
 * behalf. Three narrowings therefore have to be performed here, and all three come from an
 * alphanumeric move in the baseline that left-justifies its source and discards the surplus on the
 * right.</p>
 *
 * <pre>
 * source                       declared at                     target                width
 * TRAN-TYPE-DESC       PIC X(50)  app/cpy/CVTRA03Y.cpy line 6   TRAN-REPORT-TYPE-DESC    15
 * TRAN-CAT-TYPE-DESC   PIC X(50)  app/cpy/CVTRA04Y.cpy line 8   TRAN-REPORT-CAT-DESC     29
 * XREF-ACCT-ID         PIC 9(11)  app/cpy/CVACT03Y.cpy line 7   TRAN-REPORT-ACCOUNT-ID   11
 * </pre>
 *
 * <p>Assumptions: a fourth narrowing exists in this module and is NOT this class's. The statement
 * generator moves a 100-character description declared at line 28 of {@code app/cpy/COSTM01.CPY}
 * into a 49-character band item at line 135 of {@code app/cbl/CBSTM03A.CBL}, at line 677 of that
 * program. It belongs to {@code StatementTextMapper}, and it is named here only so that the set of
 * four is visible in one place and nobody looks for it in this file.</p>
 *
 * <h2>Assumptions: the account identifier zero-pads by regime change, not by format choice</h2>
 *
 * <p>{@code XREF-ACCT-ID} is declared {@code PIC 9(11)} at line 7 of {@code app/cpy/CVACT03Y.cpy},
 * which is a numeric display item of eleven character positions, and {@code TRAN-REPORT-ACCOUNT-ID}
 * is declared {@code PIC X(11)} at line 18 of {@code app/cpy/CVTRA07Y.cpy}, which is alphanumeric
 * at the same width. The move at line 364 of {@code app/cbl/CBTRN03C.cbl} therefore transfers the
 * source's character form, and a numeric display item carries its leading zeros in that form.
 * Account 11 consequently prints as eleven characters of which nine are zeros, and not as two
 * digits behind nine blanks.</p>
 *
 * <p>Assumptions: the same copybook shows the asymmetry that makes this deliberate rather than
 * incidental. Its category code at line 24 is itself declared {@code PIC 9(04)}, so the codec's
 * unsigned display regime zero-fills that field on its own, whereas the account identifier's target
 * is alphanumeric and the codec blank-fills an alphanumeric field. The zero-padding for the
 * identifier has to be produced deliberately in this class; for the category code it must NOT be,
 * because a value already rendered to width would then be zero-filled a second time or rejected as
 * too wide.</p>
 *
 * <h2>Assumptions: values are placed as received, and nothing here is trimmed or stripped</h2>
 *
 * <p>Every value this class places is used exactly as it arrives. Leading blanks are data, because
 * the baseline's alphanumeric move preserves them and a leading-blank removal would shift the whole
 * field left inside its column. Trailing blanks need no removal either, because the codec
 * blank-fills the remainder of a short value, so a value that arrives already blank-padded to its
 * source width and the same value trimmed to its content produce identical bytes. The fields this
 * covers are the transaction identifier, the two-character type code and the ten-character source,
 * which are placed at their declared widths; the two descriptions, whose leftmost characters are
 * taken before placement; and the account identifier, which never reaches a character operation at
 * all because it arrives as an integral number and is rendered from its digits.</p>
 *
 * <p>Alternatives Considered: taking the account identifier as text rather than as an integral
 * number. Text would leave the zero-padding at the mercy of whatever an upstream projection did
 * with the value -- a trimmed {@code "11"} and a blank-padded {@code "         11"} would each have
 * to be recognised and re-padded here -- whereas an integral number has exactly one rendering and
 * the zero-padding becomes unconditional. The consequence accepted is that a caller holding the
 * identifier as characters has to parse it, which is the correct place for that failure to
 * surface.</p>
 *
 * <p>Assumptions: a value wider than the field it is placed into is a caller error and is left to
 * fail. The three equal-width fields are declared at exactly the width of the source that feeds
 * them -- sixteen characters at line 5 of {@code app/cpy/CVTRA05Y.cpy} into sixteen at line 16 of
 * {@code app/cpy/CVTRA07Y.cpy}, two at line 6 into two at line 20, ten at line 8 into ten at line
 * 28 -- so the baseline move cannot overflow and an over-wide value means the input did not come
 * from the declared source. Silently shortening it would hide that, so the codec's field failure is
 * allowed to propagate.</p>
 *
 * <h2>Assumptions: two edit-mask regimes that must never be unified</h2>
 *
 * <p>The detail amount is declared {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} at line 30 of
 * {@code app/cpy/CVTRA07Y.cpy} and is rendered through
 * {@link CobolEditMask#formatReportDetailAmount(Money)}: fifteen characters, with a minus for a
 * negative value and a BLANK for a positive one. All three totals are declared
 * {@code PIC +ZZZ,ZZZ,ZZZ.ZZ}, at lines 54, 60 and 66 of the same copybook, and are rendered
 * through {@link CobolEditMask#formatReportTotalAmount(Money)}: the same fifteen characters, with a
 * sign always printed. One byte separates the two, and they are reached through two methods rather
 * than one method with a sign argument, so that no default can decide which of the two an artifact
 * gets.</p>
 *
 * <p>Assumptions: every digit position of both masks is a suppression position, the two decimal
 * positions included, so a ZERO amount renders as fifteen BLANKS in both regimes -- not a zero, not
 * a bare decimal point with two zero digits, and not a signed zero. A grouping comma standing to
 * the left of the first significant digit is blanked with the digits around it. Those behaviours
 * belong to {@link CobolEditMask} and are stated here because a reader checking a report against
 * this class would otherwise expect a numeric field where the artifact carries blanks.</p>
 *
 * <p>Assumptions: an edit mask is not a storage regime, and {@code CopybookLayout.Kind} offers no
 * constant resembling one. The rendering is therefore two-stage and one-directional: the mask
 * produces a fifteen-character {@link String} first, and the descriptor receives it as a character
 * field of that width so the codec merely places it. Handing the codec a {@link Money} for one of
 * these fields would fail rather than mis-render, because a character field requires a character
 * value.</p>
 *
 * <h2>Assumptions: all four masks land in one-based columns 98 to 112, by different arithmetic</h2>
 *
 * <p>The three total bands reach that column from a label and a dot leader, and the leaders differ
 * by exactly as much as the labels do: 11 plus 86 for the page total, 13 plus 84 for the card-break
 * total, 11 plus 86 for the grand total, each summing to 97 zero-based bytes before the mask. The
 * detail band reaches the same 97 by accumulation instead, from 16 + 1 + 11 + 1 + 2 + 1 + 15 + 1 +
 * 4 + 1 + 29 + 1 + 10 + 4. Unifying the three leaders at 86 would place the card-break total's mask
 * at 99 and move that one total two columns right of the other three, which is why
 * {@link ReportBandLayouts} declares the three pairs out in full instead of deriving them from a
 * shared width.</p>
 *
 * <h2>Assumptions: the business-date range is supplied by the caller and no clock is read</h2>
 *
 * <p>The title band renders {@code 'Date Range: '} at lines 9 and 10 of
 * {@code app/cpy/CVTRA07Y.cpy} -- twelve characters, the trailing space included -- then a
 * ten-character start date at line 11, then {@code ' to '} at line 12 with both of its spaces, then
 * a ten-character end date at line 13. Both dates arrive as arguments. Nothing in this class
 * consults a clock, so a rerun over the same data produces the same bytes.</p>
 *
 * <p>Assumptions: the range that reaches this band must be the same range the row selection used,
 * and the reference job does not make that true of itself. Lines 43 and 44 of
 * {@code app/jcl/TRANREPT.jcl} declare the sort utility's own comparison literals, while lines 73
 * and 74 of the same job supply a separate parameter data set that {@code app/cbl/CBTRN03C.cbl}
 * reads at its lines 122 to 125 for this heading alone. Two independent sources of one date range
 * is the single most likely cause of a heading that disagrees with the rows beneath it, so the
 * target passes ONE range into both the selection predicate and this band. The predicate itself is
 * the record-selection {@code INCLUDE COND=} at lines 47 and 48 of that job, which becomes a query
 * restriction and is not modelled here; it is named because it is the other half of the
 * agreement.</p>
 *
 * <h2>Assumptions: money arrives as Money, and this class neither computes nor rounds</h2>
 *
 * <p>Every amount parameter is {@link Money}, a decimal held at scale two. The type matters beyond
 * convenience: the shared Jackson module binds its serialiser to the {@link Money} type, so a
 * monetary value carried in the arbitrary-precision decimal type instead would compile, run, and
 * emit a JSON number where a string is required. A JSON number is parsed into an IEEE-754 binary
 * value by most clients, which destroys exactness at the boundary a user actually reads, and the
 * loss is silent because the value still looks like money.</p>
 *
 * <p>Assumptions: AAP Rule T4 (arithmetic order is preserved) has nothing to constrain here,
 * because this class performs no arithmetic. It receives totals that an emitter has already
 * accumulated and formats them at the scale they carry, and it performs no rounding: rounding is a
 * decision about a monetary result and belongs where the result is computed, whereas a formatter
 * that also rounded would change a value while claiming to render it.</p>
 *
 * <p>Assumptions: the three accumulators the total bands are fed from are declared
 * {@code PIC S9(09)V99} at lines 134, 135 and 136 of {@code app/cbl/CBTRN03C.cbl}, which is exactly
 * the nine integer positions both masks provide, so the report's own totals cannot overflow a mask.
 * {@link Money} nonetheless accepts ten integer digits, so {@link CobolEditMask} keeps its
 * magnitude guard and that guard is reachable from this class.</p>
 *
 * <h2>Alternatives Considered: the roll-up chain is not what its band titles suggest</h2>
 *
 * <p>Two readings of the accumulation are intuitive and both are wrong, so both are recorded.
 * First, the grand total is a sum of PAGE totals and never receives a card-break total: line 297 of
 * {@code app/cbl/CBTRN03C.cbl} adds the page total into the grand total inside the page-total
 * paragraph, and no other statement feeds it. Second, a single transaction feeds BOTH the page and
 * the card-break accumulator in one statement at lines 287 and 288 of that program, before the
 * detail write at line 289, so the two accumulators are siblings rather than one being derived from
 * the other.</p>
 *
 * <p>Alternatives Considered: grouping the card-break total on the account identifier, which is
 * what its band literal {@code 'Account Total'} suggests. The break is on the CARD NUMBER: line 181
 * of {@code app/cbl/CBTRN03C.cbl} compares {@code WS-CURR-CARD-NUM}, declared {@code PIC X(16)} at
 * line 137 of that program, against the current record's card number, guarded by the first-time
 * flag at line 182, and performs the card-break paragraph at line 183 before saving the new card at
 * line 185. The cross-reference lookup at line 187 fires once per break, which is exactly why one
 * account identifier is stable across a card's run of transactions. Grouping on the account
 * identifier would emit a different number of total bands. The literal is carried across unchanged
 * under AAP Rule T8 (user-visible strings are verbatim) and the divergence between the wording and
 * the break key is recorded rather than reconciled.</p>
 *
 * <h2>Assumptions: two end-of-file behaviours, and what the target does instead</h2>
 *
 * <p>The end-of-file branch at lines 198 to 203 of {@code app/cbl/CBTRN03C.cbl} adds a transaction
 * amount into the page accumulator at line 200 and into the card-break accumulator at line 201
 * using the record area left over from the last successful read, and then performs the page totals
 * at line 202 and the grand totals at line 203. Two consequences follow. The last transaction's
 * amount is added a second time, so the closing page total and the grand total each carry it twice.
 * And the card-break paragraph is never performed on that path, so the final card group gets no
 * total band of its own.</p>
 *
 * <p>Trade-offs: the target reproduces neither artifact. It counts each amount once and it emits a
 * card-break total for the last group, which is why {@link #encodeAccountTotal(Money)} is available
 * unconditionally rather than being suppressed at the end of the run. The compromise accepted is
 * that two byte comparisons against a captured baseline artifact will differ in those two places
 * and must be read against the register rather than treated as a defect: both divergences are
 * entered in {@code docs/architecture/cobol-to-service-traceability.md}, which owns that register,
 * and neither is an edit to anything under {@code app/}. AAP Rule T9 (structure changes, behaviour
 * does not) requires exactly this -- that a behavioural difference be a deliberate, recorded
 * decision rather than a silent one.</p>
 *
 * <h2>Alternatives Considered: method names the baseline's paragraph numbering cannot supply</h2>
 *
 * <p>The baseline reuses two paragraph-number prefixes. {@code 1120-} names three paragraphs, the
 * card-break totals at line 306 of {@code app/cbl/CBTRN03C.cbl}, the headings paragraph at line 324
 * and the detail paragraph at line 361; {@code 1110-} names two, the page totals at line 293 and
 * the grand totals at line 318. Naming the Java members after those prefixes is not available,
 * because Java requires distinct signatures, so each member below is named for the band it emits.
 * That is a naming divergence forced by the target language and not a change of behaviour: the
 * emission order, the repetition and the byte content are all unchanged.</p>
 *
 * <h2>Assumptions: nothing is masked here, and no field is renamed</h2>
 *
 * <p>No primary account number is emitted by this report at all. The detail band at lines 15 to 31
 * of {@code app/cpy/CVTRA07Y.cpy} carries a transaction identifier, an account identifier, a type
 * code and its description, a category code and its description, a source and an amount, and no
 * card number among them. Masking here would therefore protect nothing while rewriting bytes the
 * parity comparison expects untouched, so data-exposure narrowing belongs to
 * {@code ReportingDtoMapper} alone. For the same reason none of the three baseline field renames
 * named by AAP Rule T1 (Copybook is normative) reaches this package, and a reader should not look
 * for a fourth.</p>
 *
 * <h2>What the parity oracle covers here, and what it does not</h2>
 *
 * <p>Both halves are worth stating. {@code app/cbl/CBTRN03C.cbl} is a batch program, and lines 40
 * to 46 of {@code tests/README.md} record that ten of the twelve batch programs run standalone and
 * are fully automatable, so this report DOES have golden-master coverage and its output can be
 * compared artifact against artifact. Byte-exactness is consequently a checkable obligation here
 * rather than an aspiration. {@code app/cbl/CORPT00C.cbl} is an online program and has none: the
 * same lines, and lines 83 to 85 of the same file, record that the online programs cannot run end
 * to end without a terminal-monitor runtime, so only their extractable field-validation logic is
 * unit-tested.</p>
 *
 * <p>Assumptions: the graded return-code rubric that the COBOL oracle uses, and the aggregate
 * warn-level result it treats as its green state, belong to that suite alone and do not cross into
 * this module. This module's gate is binary -- the build has zero violations or it fails -- and
 * describing a Java build in the oracle's terms would import a tolerance this side does not
 * have.</p>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Every member below carries Javadoc stating its purpose, each parameter with its name, type and
 * description, its return value, and every exception it can raise, because user-specified Rule 1
 * (Explainability) requires that at its lines 15 to 21 without qualifying by visibility. The
 * private helpers and the private constructor are documented on the same terms as the public
 * members for that reason, and the ruleset that mechanises it audits both at private scope through
 * {@code MissingJavadocMethod} and {@code JavadocMethod} in
 * {@code config/checkstyle/checkstyle.xml}. Where two sources appear to disagree the order of
 * precedence is Rule 1 first, then that ruleset, then {@code docs/CODE_DOCUMENTATION_STANDARD.md},
 * and prose last.</p>
 *
 * <p>Assumptions: a clean build of that ruleset is NOT evidence of compliance with the rule. The
 * ruleset can see that a tag is present and that something is written after it; it cannot see
 * whether a justification is specific, whether the alternative named was really considered, or
 * whether an inherited-documentation reference stands in for an explanation. Rule 1's validation
 * gate at its line 43 is read by a human against the code, and the mechanical checks narrow what
 * has to be read rather than replacing the reading.</p>
 *
 * <p>Assumptions: suppression is unavailable in this package and must not be introduced. The
 * companion file {@code config/checkstyle/suppressions.xml} names a mapper package as something
 * never to suppress, on the ground that a mapper is among the highest-value documentation targets
 * in the repository, and the ruleset enables none of the three in-code suppression filters, so a
 * suppression comment or annotation has no effect here even where one is written. A violation in
 * this file is resolved by documenting the code and never by narrowing the gate.</p>
 *
 * <h2>Baseline framing</h2>
 *
 * <p>Everything under {@code app/} is reference material and remains byte-identical; no statement
 * in this file describes an edit to it. Two framings are used and no third: the baseline does one
 * thing, this class does another, and the difference is registered; or this class encodes a stated
 * rule. Every citation names the copybook or program that owns the declaration it refers to,
 * because a data name in this corpus does not identify a field on its own -- {@code TRAN-TYPE-CD}
 * and {@code TRAN-CAT-CD} are declared at lines 6 and 7 of both {@code app/cpy/CVTRA05Y.cpy} and
 * {@code app/cpy/CVTRA04Y.cpy}, and the same two-character type code is called {@code TRAN-TYPE} at
 * line 5 of {@code app/cpy/CVTRA03Y.cpy}. The baseline itself qualifies rather than trusting a
 * name, at lines 189, 191, 193, 365 and 367 of {@code app/cbl/CBTRN03C.cbl}.</p>
 *
 * @see ReportBandLayouts
 * @see CobolEditMask
 */
public final class TransactionReportMapper {

    // WHY : Assumptions: 15 is the width TRAN-REPORT-TYPE-DESC is declared at, on line 22 of
    //       app/cpy/CVTRA07Y.cpy, and the source that feeds it is TRAN-TYPE-DESC declared PIC X(50)
    //       on line 6 of app/cpy/CVTRA03Y.cpy inside a 60-byte record. The move at line 366 of
    //       app/cbl/CBTRN03C.cbl is alphanumeric, so it left-justifies and discards the surplus on
    //       the right; the 35 characters beyond position 15 never reach the report. The owning
    //       copybook is named because the same corpus declares a differently named 50-character
    //       description on line 8 of app/cpy/CVTRA04Y.cpy, and confusing the two would narrow the
    //       wrong field.
    private static final int TYPE_DESCRIPTION_WIDTH = 15;

    // WHY : Assumptions: 29 is the width TRAN-REPORT-CAT-DESC is declared at, on line 26 of
    //       app/cpy/CVTRA07Y.cpy, and its source is TRAN-CAT-TYPE-DESC declared PIC X(50) on line 8
    //       of app/cpy/CVTRA04Y.cpy inside a 60-byte record. The move at line 368 of
    //       app/cbl/CBTRN03C.cbl discards the surplus on the right exactly as the type
    //       description's does. This is a second constant rather than a reuse of the 15 above
    //       because the two targets are declared at different widths in the same band, and one
    //       shared width would silently narrow one of them.
    private static final int CATEGORY_DESCRIPTION_WIDTH = 29;

    // WHY : Assumptions: 11 is both the source and the target width, and that equality is the whole
    //       mechanism. XREF-ACCT-ID is declared PIC 9(11) on line 7 of app/cpy/CVACT03Y.cpy and
    //       TRAN-REPORT-ACCOUNT-ID is declared PIC X(11) on line 18 of app/cpy/CVTRA07Y.cpy, so the
    //       move at line 364 of app/cbl/CBTRN03C.cbl transfers a numeric display item's character
    //       form into an alphanumeric item of equal size, and that character form carries its
    //       leading zeros.
    private static final int ACCOUNT_ID_WIDTH = 11;

    // WHY : Assumptions: 10 is the width REPT-START-DATE and REPT-END-DATE are each declared at, on
    //       lines 11 and 13 of app/cpy/CVTRA07Y.cpy, and the same 10 is the width of the two date
    //       items the reference program reads into WS-DATEPARM-RECORD at its lines 123 and 125. The
    //       value is already in year-month-day order in the baseline, which is why a lexical
    //       comparison over it was equivalent to a date comparison and why the target can render it
    //       without reordering anything.
    private static final int REPORT_DATE_WIDTH = 10;

    // WHY : Assumptions: the blank line is named for the working-storage item that declares it,
    //       WS-BLANK-LINE at line 133 of app/cbl/CBTRN03C.cbl, rather than for a copybook item,
    //       because app/cpy/CVTRA07Y.cpy does not declare it at all. One constant serves as both
    //       the record name and its single field name so that a codec diagnostic names the baseline
    //       item a reader can look up, instead of naming the rule line whose descriptor would
    //       otherwise have been reused.
    private static final String BLANK_LINE_ITEM_NAME = "WS-BLANK-LINE";

    // WHY : Assumptions: RecordSpec requires a key of at least one byte because every layout it was
    //       shaped for is a keyed data set, whereas line 78 of app/jcl/TRANREPT.jcl declares this
    //       report with a record format of FB and no key operand, so no band has a retrieval key at
    //       all. One byte at offset zero is the smallest declaration the descriptor accepts, and it
    //       is named so that a reader meets this explanation rather than inferring intent from a
    //       bare digit.
    private static final int NO_RETRIEVAL_KEY_LENGTH = 1;

    // WHY : Assumptions: the companion offset for the nominal key above. It is a separate constant
    //       rather than a reused zero because the descriptor takes length and offset as two
    //       arguments in sequence, and two same-valued literals in adjacent argument positions are
    //       exactly the pair a later edit transposes without the compiler objecting.
    private static final int NO_RETRIEVAL_KEY_OFFSET = 0;

    // WHY : Assumptions: an empty value is expanded by the codec into a field of blanks, because it
    //       places what it is given from the left and blank-fills the remainder, and the blank byte
    //       it fills with is the target charset's own. Writing 133 spaces out here was rejected
    //       because the count would then be stated twice, once as the field width and once as the
    //       literal, and a later edit to one would not be caught by the other.
    private static final String BLANK_LINE_CONTENT = "";

    // WHY : Assumptions: this descriptor is declared here and not in ReportBandLayouts because that
    //       holder transcribes app/cpy/CVTRA07Y.cpy and this item is not in it; line 133 of
    //       app/cbl/CBTRN03C.cbl declares it PIC X(133) VALUE SPACES and line 329 of that program
    //       moves it into the output record between the title band and the column headings. It is
    //       modelled as one field spanning all 133 bytes, which is the faithful reading of an
    //       elementary level-01 item, and validateGeometry() is chained so a mis-stated width fails
    //       at class load rather than emitting a short line.
    private static final CopybookLayout.RecordSpec BLANK_LINE = new CopybookLayout.RecordSpec(
            BLANK_LINE_ITEM_NAME, ReportBandLayouts.REPORT_RECORD_LENGTH,
            NO_RETRIEVAL_KEY_LENGTH, NO_RETRIEVAL_KEY_OFFSET,
            List.of(CopybookLayout.text(BLANK_LINE_ITEM_NAME, 0,
                    ReportBandLayouts.REPORT_RECORD_LENGTH))).validateGeometry();

    /**
     * Prevents instantiation of this static emitter.
     *
     * <p>Alternatives Considered: an injectable instance was evaluated and rejected. Every member
     * of this class is a pure function of its arguments and of two constant descriptor holders, so
     * an instance would advertise a lifecycle that does not exist and would offer a collaborator no
     * test has any reason to substitute; a test that wanted different bytes would change the
     * arguments, not the emitter. Declaring the constructor private states that where the language
     * enforces it, and the class is final so no subclass can reopen the decision.</p>
     *
     * <p>Assumptions: this constructor is documented in full even though it is private and empty,
     * because user-specified Rule 1 (Explainability) requires a docstring on every function at its
     * line 15 without qualifying by visibility, and {@code MissingJavadocMethod} in
     * {@code config/checkstyle/checkstyle.xml} audits constructors at private scope.</p>
     */
    private TransactionReportMapper() {
    }

    /**
     * Encodes the report title band, carrying the business-date range it was run for.
     *
     * <p>Assumptions: the band is transcribed from lines 4 to 13 of {@code app/cpy/CVTRA07Y.cpy}.
     * Its short name, long name, the twelve-character {@code 'Date Range: '} label and the
     * four-character {@code ' to '} joiner are invariant and are seeded by
     * {@link ReportBandLayouts#nameHeaderTemplate()}; the two dates are the only items that vary,
     * which is why they are the only two arguments. The reference program fills the same two items
     * at its lines 277 and 278, once per run, on first entry to the report-writing paragraph.</p>
     *
     * <p>Assumptions: both dates are supplied by the caller and no clock is consulted, so a rerun
     * over the same rows produces the same bytes. The range passed here must be the same range the
     * row selection used; lines 43 and 44 of {@code app/jcl/TRANREPT.jcl} and lines 73 and 74 of
     * the same job are two independent date sources in the reference, and the target collapses them
     * to one for that reason.</p>
     *
     * @param rangeStart the inclusive first business date of the reporting range, rendered into the
     *     ten-character item declared at line 11 of {@code app/cpy/CVTRA07Y.cpy}; must not be
     *     {@code null}
     * @param rangeEnd the inclusive last business date of the reporting range, rendered into the
     *     ten-character item declared at line 13 of {@code app/cpy/CVTRA07Y.cpy}; must not be
     *     {@code null}
     * @return a newly allocated {@code byte[]} of exactly
     *     {@link ReportBandLayouts#REPORT_RECORD_LENGTH} bytes, blank-padded from the band's native
     *     115
     * @throws NullPointerException if either date is {@code null}
     * @throws IllegalArgumentException if either date does not render to exactly ten characters,
     *     which happens only outside the year range the declared width admits
     * @throws FixedWidthCodec.FieldCodecException if a value cannot be placed in its declared
     *     interval
     * @throws CopybookLayout.LayoutException if the band descriptor is invalid, which its
     *     class-load geometry check has already excluded
     */
    public static byte[] encodeNameHeader(LocalDate rangeStart, LocalDate rangeEnd) {
        Objects.requireNonNull(rangeStart, "rangeStart must not be null");
        Objects.requireNonNull(rangeEnd, "rangeEnd must not be null");

        Map<String, Object> band = ReportBandLayouts.nameHeaderTemplate();
        band.put(ReportBandLayouts.FIELD_REPT_START_DATE,
                renderReportDate(rangeStart, ReportBandLayouts.FIELD_REPT_START_DATE));
        band.put(ReportBandLayouts.FIELD_REPT_END_DATE,
                renderReportDate(rangeEnd, ReportBandLayouts.FIELD_REPT_END_DATE));
        return FixedWidthCodec.encodeRecord(band, ReportBandLayouts.REPORT_NAME_HEADER);
    }

    /**
     * Encodes the column heading band, which has no varying content.
     *
     * <p>Assumptions: all seven items of the band at lines 33 to 46 of {@code app/cpy/CVTRA07Y.cpy}
     * are {@code FILLER} carrying a literal, so {@link ReportBandLayouts#columnHeaderRecord()}
     * returns a complete value map and this method takes no argument. Two of those literals are
     * easy to lose. The item at line 44 is declared {@code FILLER PIC X} with no parenthesised
     * count, which is a width of one and not an unspecified width, and that single byte is what
     * closes the headings at zero-based offset 97 where the amount column opens. The item at lines
     * 45 and 46 is the amount heading, whose literal carries eight leading spaces that position it
     * over the mask beneath it; under AAP Rule T8 (user-visible strings are verbatim) those spaces
     * are data and are not normalised.</p>
     *
     * @return a newly allocated {@code byte[]} of exactly
     *     {@link ReportBandLayouts#REPORT_RECORD_LENGTH} bytes, blank-padded from the band's native
     *     114
     * @throws FixedWidthCodec.FieldCodecException if a heading literal cannot be placed in its
     *     declared interval
     * @throws CopybookLayout.LayoutException if the band descriptor is invalid, which its
     *     class-load geometry check has already excluded
     */
    public static byte[] encodeColumnHeader() {
        return FixedWidthCodec.encodeRecord(ReportBandLayouts.columnHeaderRecord(),
                ReportBandLayouts.TRANSACTION_HEADER_1);
    }

    /**
     * Encodes the rule line that separates a heading block or closes a total band.
     *
     * <p>Assumptions: the band is declared at line 48 of {@code app/cpy/CVTRA07Y.cpy} as an
     * elementary level-01 item of {@code PIC X(133) VALUE ALL} hyphens, so it is the only one of
     * the seven copybook bands that is natively the declared record length and the only one that
     * needs no padding at all. The literal is supplied at full width by
     * {@link ReportBandLayouts#separatorRuleRecord()} rather than left to the codec's fill, because
     * the codec fills a short value's remainder with BLANKS and this item's declared value is
     * hyphens the whole way across.</p>
     *
     * <p>Assumptions: the reference program emits this band at three separate sites, lines 300, 312
     * and 337 of {@code app/cbl/CBTRN03C.cbl}, and every occurrence is part of the byte stream the
     * parity comparison reads. This method is therefore called once per occurrence and repeated
     * occurrences are never collapsed, because collapsing them would shorten the artifact by a line
     * each time.</p>
     *
     * @return a newly allocated {@code byte[]} of exactly
     *     {@link ReportBandLayouts#REPORT_RECORD_LENGTH} bytes, every one of them a hyphen
     * @throws FixedWidthCodec.FieldCodecException if the rule literal cannot be placed in its
     *     declared interval
     * @throws CopybookLayout.LayoutException if the band descriptor is invalid, which its
     *     class-load geometry check has already excluded
     */
    public static byte[] encodeSeparatorRule() {
        return FixedWidthCodec.encodeRecord(ReportBandLayouts.separatorRuleRecord(),
                ReportBandLayouts.TRANSACTION_HEADER_2);
    }

    /**
     * Encodes the wholly blank line that stands between the title band and the column headings.
     *
     * <p>Assumptions: this band is not declared in {@code app/cpy/CVTRA07Y.cpy}. It is
     * {@code WS-BLANK-LINE}, declared {@code PIC X(133) VALUE SPACES} at line 133 of
     * {@code app/cbl/CBTRN03C.cbl} and moved into the output record at line 329 of that program,
     * which is its only occurrence anywhere in the report. It is encoded through the same codec
     * entry point as every other band, against the descriptor declared in this class, so its width
     * is proven by the same geometry check rather than by a literal counted out by hand.</p>
     *
     * @return a newly allocated {@code byte[]} of exactly
     *     {@link ReportBandLayouts#REPORT_RECORD_LENGTH} bytes, every one of them a blank
     * @throws FixedWidthCodec.FieldCodecException if the blank content cannot be placed in the
     *     declared interval
     * @throws CopybookLayout.LayoutException if the descriptor declared in this class is invalid,
     *     which its class-load geometry check has already excluded
     */
    public static byte[] encodeBlankLine() {
        // WHY : Assumptions: a single-entry immutable map is enough because this band has exactly
        //       one declared field and the codec only reads the map it is given. The template
        //       methods on ReportBandLayouts return mutable maps because their callers must add
        //       varying values; there is nothing varying to add here.
        return FixedWidthCodec.encodeRecord(
                Map.of(BLANK_LINE_ITEM_NAME, BLANK_LINE_CONTENT), BLANK_LINE);
    }

    /**
     * Encodes the four-band heading block in the order the baseline emits it.
     *
     * <p>Assumptions: the order is a single baseline paragraph and is reproduced here rather than
     * left to a caller. Lines 325, 329, 333 and 337 of {@code app/cbl/CBTRN03C.cbl} emit the title
     * band, then the blank line, then the column heading band, then the rule line, with no other
     * content between them, and the blank line occurs at that one position only. A caller
     * assembling the four itself would have nothing to check its ordering against, and an ordering
     * error would produce a readable report with its heading block permuted.</p>
     *
     * <p>Assumptions: the reference program performs this paragraph at two places, once on first
     * entry at its line 279 and again after each page total at its line 284, so a run emits the
     * block as many times as it starts a page. This method is therefore called once per occurrence,
     * and the page-break test itself -- the remainder test at line 282 of that program against a
     * page size declared {@code PIC 9(03) COMP-3 VALUE 20} at its line 131 -- belongs to the
     * emitting sequence and not here.</p>
     *
     * @param rangeStart the inclusive first business date of the reporting range, passed through to
     *     the title band; must not be {@code null}
     * @param rangeEnd the inclusive last business date of the reporting range, passed through to
     *     the title band; must not be {@code null}
     * @return an unmodifiable list of exactly four records, each
     *     {@link ReportBandLayouts#REPORT_RECORD_LENGTH} bytes, in emission order
     * @throws NullPointerException if either date is {@code null}
     * @throws IllegalArgumentException if either date does not render to exactly ten characters
     * @throws FixedWidthCodec.FieldCodecException if a value cannot be placed in its declared
     *     interval
     * @throws CopybookLayout.LayoutException if a band descriptor is invalid, which the class-load
     *     geometry checks have already excluded
     */
    public static List<byte[]> encodeHeadingBlock(LocalDate rangeStart, LocalDate rangeEnd) {
        // WHY : Assumptions: the four calls are written out in the baseline's own order so that the
        //       sequence is readable against lines 325, 329, 333 and 337 of app/cbl/CBTRN03C.cbl at
        //       a glance. Building the list from a loop over a band collection was rejected: the
        //       order is the entire content of this method, and a collection would state it
        //       somewhere a reader has to go and find.
        return List.of(
                encodeNameHeader(rangeStart, rangeEnd),
                encodeBlankLine(),
                encodeColumnHeader(),
                encodeSeparatorRule());
    }

    /**
     * Encodes one transaction detail line of the report.
     *
     * <p>Assumptions: the eight arguments are the eight items the reference program moves into the
     * band, at lines 363 to 370 of {@code app/cbl/CBTRN03C.cbl}, and they are declared in that
     * order so the method reads against those eight statements one for one. Their sources are named
     * with their owning copybooks because the names alone do not identify them: the transaction
     * identifier, type code, category code, source and amount come from
     * {@code app/cpy/CVTRA05Y.cpy} at its lines 5, 6, 7, 8 and 10; the account identifier from
     * {@code app/cpy/CVACT03Y.cpy} line 7; the type description from {@code app/cpy/CVTRA03Y.cpy}
     * line 6; and the category description from {@code app/cpy/CVTRA04Y.cpy} line 8. The reference
     * qualifies the two colliding names explicitly at its lines 365 and 367 for the same
     * reason.</p>
     *
     * <p>Assumptions: the eight remaining items of the band are {@code FILLER} carrying literals
     * and are seeded by {@link ReportBandLayouts#detailTemplate()}, so this method supplies exactly
     * the eight that vary. Two of those literals are the single hyphens at lines 21 and 25 of
     * {@code app/cpy/CVTRA07Y.cpy} that join a code to the description after it, rendering the pair
     * as one printed unit. They are DATA and not padding, and neither is a sign position -- the
     * amount's sign belongs to its own mask at line 30 of that copybook. The reference never
     * restates them because line 362 of {@code app/cbl/CBTRN03C.cbl} opens the detail paragraph by
     * re-initialising the band and that statement leaves {@code FILLER} untouched, so the two bytes
     * are written once into a record area that persists and survive every subsequent line. A band
     * assembled into a freshly allocated array has no such survivor, which is why they are seeded
     * explicitly on every call.</p>
     *
     * <p>Alternatives Considered: collecting the eight arguments into a carrier type. Rejected
     * because the eight correspond one for one with the eight moves at lines 363 to 370 of
     * {@code app/cbl/CBTRN03C.cbl}, and a carrier would introduce a second place for the same field
     * set to be declared -- the failure mode the house convention at lines 540 to 542 of
     * {@code tests/README.md} exists to prevent, where two statements of one layout disagree. The
     * consequence accepted is a long parameter list whose order is load-bearing, which the
     * parameter documentation below states item by item.</p>
     *
     * @param transactionId the transaction identifier, declared {@code PIC X(16)} at line 5 of
     *     {@code app/cpy/CVTRA05Y.cpy} and placed at its declared width; must not be {@code null}
     *     and must not exceed sixteen characters
     * @param accountId the account identifier reached through the cross-reference, declared
     *     {@code PIC 9(11)} at line 7 of {@code app/cpy/CVACT03Y.cpy} and printed zero-padded to
     *     eleven digits; must not be negative and must not need more than eleven digits
     * @param transactionTypeCode the two-character transaction type code, declared
     *     {@code PIC X(02)} at line 6 of {@code app/cpy/CVTRA05Y.cpy} and placed at its declared
     *     width; must not be {@code null} and must not exceed two characters
     * @param transactionTypeDescription the transaction type description, declared
     *     {@code PIC X(50)} at line 6 of {@code app/cpy/CVTRA03Y.cpy}, of which the leftmost
     *     fifteen characters are printed; must not be {@code null}
     * @param transactionCategoryCode the transaction category code, declared {@code PIC 9(04)} at
     *     line 7 of {@code app/cpy/CVTRA05Y.cpy}, supplied as an integral number and zero-filled to
     *     four positions by the codec; must not be negative and must not need more than four digits
     * @param transactionCategoryDescription the transaction category description, declared
     *     {@code PIC X(50)} at line 8 of {@code app/cpy/CVTRA04Y.cpy}, of which the leftmost
     *     twenty-nine characters are printed; must not be {@code null}
     * @param transactionSource the ten-character transaction source, declared {@code PIC X(10)} at
     *     line 8 of {@code app/cpy/CVTRA05Y.cpy} and placed at its declared width; must not be
     *     {@code null} and must not exceed ten characters
     * @param amount the transaction amount, declared {@code PIC S9(09)V99} at line 10 of
     *     {@code app/cpy/CVTRA05Y.cpy}, rendered through the leading-minus mask of line 30 of
     *     {@code app/cpy/CVTRA07Y.cpy}; must not be {@code null}
     * @return a newly allocated {@code byte[]} of exactly
     *     {@link ReportBandLayouts#REPORT_RECORD_LENGTH} bytes, blank-padded from the band's native
     *     114
     * @throws NullPointerException if any of the six reference arguments is {@code null}, being the
     *     five character arguments and the amount
     * @throws IllegalArgumentException if the account identifier is negative or needs more than
     *     eleven digits
     * @throws ArithmeticException if the magnitude of the amount needs more than the nine integer
     *     positions the mask provides
     * @throws IllegalStateException if the mask composes a value of the wrong width, which reports
     *     a defect in {@link CobolEditMask} rather than a fault of the caller
     * @throws FixedWidthCodec.FieldCodecException if a value cannot be placed in its declared
     *     interval, which includes a character value wider than its field and a category code that
     *     is negative or wider than four digits
     * @throws CopybookLayout.LayoutException if the band descriptor is invalid, which its
     *     class-load geometry check has already excluded
     */
    public static byte[] encodeDetailLine(
            String transactionId,
            long accountId,
            String transactionTypeCode,
            String transactionTypeDescription,
            int transactionCategoryCode,
            String transactionCategoryDescription,
            String transactionSource,
            Money amount) {

        // WHY : Assumptions: the five character arguments are checked here rather than left to fail
        //       inside the codec, which rejects a null as "not a character sequence" and names the
        //       field but not the argument. Naming the argument is what lets a caller find its own
        //       omission, and the amount is checked for the same reason even though the mask would
        //       also reject it.
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(transactionTypeCode, "transactionTypeCode must not be null");
        Objects.requireNonNull(transactionTypeDescription,
                "transactionTypeDescription must not be null");
        Objects.requireNonNull(transactionCategoryDescription,
                "transactionCategoryDescription must not be null");
        Objects.requireNonNull(transactionSource, "transactionSource must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        Map<String, Object> band = ReportBandLayouts.detailTemplate();
        band.put(ReportBandLayouts.FIELD_TRAN_REPORT_TRANS_ID, transactionId);
        band.put(ReportBandLayouts.FIELD_TRAN_REPORT_ACCOUNT_ID,
                zeroPaddedIdentifier(accountId, ACCOUNT_ID_WIDTH,
                        ReportBandLayouts.FIELD_TRAN_REPORT_ACCOUNT_ID));
        band.put(ReportBandLayouts.FIELD_TRAN_REPORT_TYPE_CD, transactionTypeCode);
        band.put(ReportBandLayouts.FIELD_TRAN_REPORT_TYPE_DESC,
                leftmostCharacters(transactionTypeDescription, TYPE_DESCRIPTION_WIDTH));

        // WHY : Assumptions: the category code is handed over as an integral number and NOT as a
        //       rendered string, because line 24 of app/cpy/CVTRA07Y.cpy declares its target PIC
        //       9(04) and the codec's unsigned display regime right-justifies and zero-fills it to
        //       four positions. Rendering it here would either be zero-filled a second time or be
        //       rejected as too wide, and it is the one item of this band whose padding is not this
        //       class's to perform.
        band.put(ReportBandLayouts.FIELD_TRAN_REPORT_CAT_CD, transactionCategoryCode);
        band.put(ReportBandLayouts.FIELD_TRAN_REPORT_CAT_DESC,
                leftmostCharacters(transactionCategoryDescription, CATEGORY_DESCRIPTION_WIDTH));
        band.put(ReportBandLayouts.FIELD_TRAN_REPORT_SOURCE, transactionSource);

        // WHY : Assumptions: the detail amount takes the leading-minus mask declared at line 30 of
        //       app/cpy/CVTRA07Y.cpy, which prints a BLANK sign position for a positive value, and
        //       it is reached through its own method rather than through a shared one carrying a
        //       sign argument. The three total bands below take the always-signed mask of lines 54,
        //       60 and 66 of that copybook, and the two regimes differ in that one byte; a single
        //       call site with a flag would let a default decide which of the two a report line
        //       got.
        band.put(ReportBandLayouts.FIELD_TRAN_REPORT_AMT,
                CobolEditMask.formatReportDetailAmount(amount));
        return FixedWidthCodec.encodeRecord(band, ReportBandLayouts.TRANSACTION_DETAIL_REPORT);
    }

    /**
     * Encodes the page total band that closes a report page.
     *
     * <p>Assumptions: the band is transcribed from lines 50 to 54 of {@code app/cpy/CVTRA07Y.cpy},
     * and its eleven-character label and eighty-six-character dot leader are seeded by
     * {@link ReportBandLayouts#pageTotalsTemplate()} so the amount is the only item this method
     * supplies. That 11 plus 86 is 97, which places the mask in one-based columns 98 to 112 -- the
     * same span the other three money-bearing bands use, reached from a different pair in each
     * case.</p>
     *
     * <p>Alternatives Considered: computing the total here from the lines already emitted. This
     * class performs no accumulation: the reference adds each transaction amount into the page
     * accumulator at line 287 of {@code app/cbl/CBTRN03C.cbl} and into the card-break accumulator
     * at line 288 in one statement, then resets the page accumulator at line 298 after emitting
     * this band, and that sequencing belongs to the emitter that owns the row loop. A formatter
     * that also accumulated would have to be told when a page ended, which is the emitter's
     * knowledge and not the copybook's.</p>
     *
     * <p>Assumptions: the grand total is fed from THIS total and not from the card-break total.
     * Line 297 of {@code app/cbl/CBTRN03C.cbl} adds the page total into the grand total inside the
     * page-total paragraph, and no other statement feeds the grand total, so a reading in which the
     * grand total sums the card-break totals is intuitive and wrong. The emitter owns that chain;
     * it is recorded here because this is the band the chain runs through.</p>
     *
     * @param pageTotal the accumulated total for the page being closed, declared
     *     {@code PIC S9(09)V99} at line 134 of {@code app/cbl/CBTRN03C.cbl}, rendered through the
     *     always-signed mask of line 54 of {@code app/cpy/CVTRA07Y.cpy}; must not be {@code null}
     * @return a newly allocated {@code byte[]} of exactly
     *     {@link ReportBandLayouts#REPORT_RECORD_LENGTH} bytes, blank-padded from the band's native
     *     112
     * @throws NullPointerException if {@code pageTotal} is {@code null}
     * @throws ArithmeticException if the magnitude of the total needs more than the nine integer
     *     positions the mask provides
     * @throws IllegalStateException if the mask composes a value of the wrong width, which reports
     *     a defect in {@link CobolEditMask} rather than a fault of the caller
     * @throws FixedWidthCodec.FieldCodecException if a value cannot be placed in its declared
     *     interval
     * @throws CopybookLayout.LayoutException if the band descriptor is invalid, which its
     *     class-load geometry check has already excluded
     */
    public static byte[] encodePageTotal(Money pageTotal) {
        Objects.requireNonNull(pageTotal, "pageTotal must not be null");

        Map<String, Object> band = ReportBandLayouts.pageTotalsTemplate();
        band.put(ReportBandLayouts.FIELD_REPT_PAGE_TOTAL,
                CobolEditMask.formatReportTotalAmount(pageTotal));
        return FixedWidthCodec.encodeRecord(band, ReportBandLayouts.REPORT_PAGE_TOTALS);
    }

    /**
     * Encodes the total band that closes a card group, titled as an account total.
     *
     * <p>Assumptions: the band is transcribed from lines 56 to 60 of {@code app/cpy/CVTRA07Y.cpy},
     * and its label is thirteen characters where the other two total labels are eleven, so its dot
     * leader is eighty-four where theirs are eighty-six. That 13 plus 84 reaches the same 97
     * zero-based bytes, which is what keeps this amount in one-based columns 98 to 112 with the
     * other three. Unifying the three leaders would move this one total two columns right of the
     * rest.</p>
     *
     * <p>Alternatives Considered: grouping this total on the account identifier, which the band's
     * {@code 'Account Total'} literal invites. The reference breaks on the CARD NUMBER: line 181 of
     * {@code app/cbl/CBTRN03C.cbl} compares {@code WS-CURR-CARD-NUM}, declared {@code PIC X(16)} at
     * line 137 of that program, against the current record's card number; the break is guarded by
     * the first-time flag at line 182 and performs the card-break paragraph at line 183 before
     * saving the new card at line 185 and performing the cross-reference lookup at line 187.
     * Because that lookup fires once per break, one account identifier is stable across a card's
     * run of transactions -- which is why the two readings produce the same identifier on the
     * detail lines and a different NUMBER of total bands. The literal is carried across unchanged
     * under AAP Rule T8 (user-visible strings are verbatim) and the divergence between the wording
     * and the break key is recorded rather than reconciled.</p>
     *
     * <p>Trade-offs: this band is available unconditionally, including for the last card group of a
     * run. The reference emits no such closing band, because its end-of-file branch at lines 198 to
     * 203 of {@code app/cbl/CBTRN03C.cbl} performs the page totals at line 202 and the grand totals
     * at line 203 and never the card-break paragraph. The target emits it, so the last group is
     * closed like every other; the compromise accepted is that a byte comparison against a captured
     * baseline artifact will show one additional band at the end, which is entered in
     * {@code docs/architecture/cobol-to-service-traceability.md} rather than treated as a
     * defect.</p>
     *
     * @param accountTotal the accumulated total for the card group being closed, declared
     *     {@code PIC S9(09)V99} at line 135 of {@code app/cbl/CBTRN03C.cbl}, rendered through the
     *     always-signed mask of line 60 of {@code app/cpy/CVTRA07Y.cpy}; must not be {@code null}
     * @return a newly allocated {@code byte[]} of exactly
     *     {@link ReportBandLayouts#REPORT_RECORD_LENGTH} bytes, blank-padded from the band's native
     *     112
     * @throws NullPointerException if {@code accountTotal} is {@code null}
     * @throws ArithmeticException if the magnitude of the total needs more than the nine integer
     *     positions the mask provides
     * @throws IllegalStateException if the mask composes a value of the wrong width, which reports
     *     a defect in {@link CobolEditMask} rather than a fault of the caller
     * @throws FixedWidthCodec.FieldCodecException if a value cannot be placed in its declared
     *     interval
     * @throws CopybookLayout.LayoutException if the band descriptor is invalid, which its
     *     class-load geometry check has already excluded
     */
    public static byte[] encodeAccountTotal(Money accountTotal) {
        Objects.requireNonNull(accountTotal, "accountTotal must not be null");

        Map<String, Object> band = ReportBandLayouts.accountTotalsTemplate();
        band.put(ReportBandLayouts.FIELD_REPT_ACCOUNT_TOTAL,
                CobolEditMask.formatReportTotalAmount(accountTotal));
        return FixedWidthCodec.encodeRecord(band, ReportBandLayouts.REPORT_ACCOUNT_TOTALS);
    }

    /**
     * Encodes the grand total band that closes the report.
     *
     * <p>Assumptions: the band is transcribed from lines 62 to 66 of {@code app/cpy/CVTRA07Y.cpy}.
     * Its eleven-character label and eighty-six-character dot leader match the page total band's
     * pair, which follows from their two labels being declared the same width rather than from any
     * rule shared between them, so the two pairs stay separately declared in
     * {@link ReportBandLayouts}.</p>
     *
     * <p>Trade-offs: the total this band is handed differs from the reference's by the last
     * transaction's amount. The end-of-file branch at lines 198 to 203 of
     * {@code app/cbl/CBTRN03C.cbl} adds an amount into the page accumulator at line 200 and into
     * the card-break accumulator at line 201 from the record area left over from the last
     * successful read, and then performs the page totals at line 202, which carries that amount
     * into the grand total at line 297 for a second time. The target counts each amount once, so
     * its grand total is the sum of its page totals with no repetition; the compromise accepted is
     * that a byte comparison against a captured baseline artifact will differ in this field, which
     * is entered in {@code docs/architecture/cobol-to-service-traceability.md} rather than treated
     * as a defect.</p>
     *
     * @param grandTotal the accumulated total for the whole report, declared {@code PIC S9(09)V99}
     *     at line 136 of {@code app/cbl/CBTRN03C.cbl}, rendered through the always-signed mask of
     *     line 66 of {@code app/cpy/CVTRA07Y.cpy}; must not be {@code null}
     * @return a newly allocated {@code byte[]} of exactly
     *     {@link ReportBandLayouts#REPORT_RECORD_LENGTH} bytes, blank-padded from the band's native
     *     112
     * @throws NullPointerException if {@code grandTotal} is {@code null}
     * @throws ArithmeticException if the magnitude of the total needs more than the nine integer
     *     positions the mask provides
     * @throws IllegalStateException if the mask composes a value of the wrong width, which reports
     *     a defect in {@link CobolEditMask} rather than a fault of the caller
     * @throws FixedWidthCodec.FieldCodecException if a value cannot be placed in its declared
     *     interval
     * @throws CopybookLayout.LayoutException if the band descriptor is invalid, which its
     *     class-load geometry check has already excluded
     */
    public static byte[] encodeGrandTotal(Money grandTotal) {
        Objects.requireNonNull(grandTotal, "grandTotal must not be null");

        Map<String, Object> band = ReportBandLayouts.grandTotalsTemplate();
        band.put(ReportBandLayouts.FIELD_REPT_GRAND_TOTAL,
                CobolEditMask.formatReportTotalAmount(grandTotal));
        return FixedWidthCodec.encodeRecord(band, ReportBandLayouts.REPORT_GRAND_TOTALS);
    }

    /**
     * Takes the leftmost characters of a description for a narrower report column.
     *
     * <p>Assumptions: this reproduces the alphanumeric move the baseline performs at lines 366 and
     * 368 of {@code app/cbl/CBTRN03C.cbl}, which left-justifies its source in the receiving item
     * and discards whatever will not fit on the right. It is applied to the fifty-character type
     * description declared at line 6 of {@code app/cpy/CVTRA03Y.cpy} and to the fifty-character
     * category description declared at line 8 of {@code app/cpy/CVTRA04Y.cpy}, whose targets are
     * fifteen and twenty-nine characters at lines 22 and 26 of {@code app/cpy/CVTRA07Y.cpy}.</p>
     *
     * <p>Assumptions: the narrowing is unconditional and is never preceded by a whitespace removal.
     * The baseline narrows the item as it stands, so a description whose fifty characters include
     * trailing blanks yields fifteen or twenty-nine characters that may themselves end in blanks,
     * and the codec blank-fills a value shorter than its field anyway. Removing leading whitespace
     * would be worse than unnecessary: leading blanks are content in an alphanumeric move, and
     * dropping them would shift the printed text left inside its column.</p>
     *
     * <p>Alternatives Considered: raising an error on an over-wide value instead of narrowing it,
     * which is what the codec does for the equal-width fields of this band. Rejected here because a
     * value wider than the target is the NORMAL case for these two columns rather than a caller
     * error -- the sources are declared more than three times the width of their targets -- so
     * refusing them would reject every description longer than its column and print no report at
     * all.</p>
     *
     * @param value the description as received, of any length, taken as it stands; must not be
     *     {@code null}
     * @param declaredWidth the declared character width of the receiving report column, which is
     *     the number of leftmost characters kept
     * @return the value unchanged when it is no wider than the column, otherwise its leftmost
     *     {@code declaredWidth} characters
     * @throws NullPointerException if {@code value} is {@code null}, which every caller in this
     *     class has already excluded by argument name
     */
    private static String leftmostCharacters(String value, int declaredWidth) {
        return value.length() <= declaredWidth ? value : value.substring(0, declaredWidth);
    }

    /**
     * Renders a numeric identifier zero-padded to the declared character width of its report
     * column.
     *
     * <p>Assumptions: this produces the character form a numeric display item carries, which is
     * what the move at line 364 of {@code app/cbl/CBTRN03C.cbl} transfers. {@code XREF-ACCT-ID} is
     * declared {@code PIC 9(11)} at line 7 of {@code app/cpy/CVACT03Y.cpy} and its target
     * {@code TRAN-REPORT-ACCOUNT-ID} is declared {@code PIC X(11)} at line 18 of
     * {@code app/cpy/CVTRA07Y.cpy}, so the move is from numeric display into alphanumeric at equal
     * size and the leading zeros travel with the digits. Because the target is alphanumeric the
     * codec blank-fills rather than zero-fills it, so the padding has to be produced here; the
     * category code of the same band is the opposite case, since line 24 of that copybook declares
     * it numeric and the codec zero-fills it without help.</p>
     *
     * <p>Alternatives Considered: a locale-aware numeric formatter, either the general-purpose
     * decimal formatter or a zero-padding format specifier. Both were rejected outright because
     * both consult the default locale unless one is passed, and a virtual machine started under a
     * locale whose numbering system is not the Western one renders the digits in that system's
     * characters instead. The report would then be the right length and the wrong bytes, which is
     * the failure this class exists to prevent. Rendering the digits through the integral type's
     * own conversion and prefixing the zeros is locale-independent by construction, so the hazard
     * cannot be reintroduced by configuration.</p>
     *
     * @param identifier the numeric identifier to render, taken as an integral number so that its
     *     rendering has exactly one form; must not be negative
     * @param declaredWidth the declared character width of the receiving report column, which is
     *     the total number of digit positions the result carries
     * @param fieldName the declared name of the receiving field, used only to name the field in a
     *     failure message
     * @return the identifier as exactly {@code declaredWidth} decimal digits, zero-padded on the
     *     left
     * @throws IllegalArgumentException if the identifier is negative, which a numeric display item
     *     cannot represent, or if it needs more digits than the column declares
     */
    private static String zeroPaddedIdentifier(long identifier, int declaredWidth,
            String fieldName) {
        // WHY : Assumptions: a negative value is rejected rather than rendered, because line 7 of
        //       app/cpy/CVACT03Y.cpy declares the source PIC 9(11) with no sign, so no negative
        //       value can reach this column from the baseline. Rendering one would emit a minus
        //       where a digit belongs and shift the remaining digits, producing a line of the right
        //       length carrying a different account.
        if (identifier < 0) {
            throw new IllegalArgumentException(fieldName
                    + " is an unsigned numeric display column and cannot render a negative"
                    + " identifier, but was " + identifier);
        }

        String digits = Long.toString(identifier);
        if (digits.length() > declaredWidth) {
            throw new IllegalArgumentException(fieldName + " declares " + declaredWidth
                    + " digit positions but " + identifier + " needs " + digits.length());
        }
        return "0".repeat(declaredWidth - digits.length()) + digits;
    }

    /**
     * Renders a business date into the ten-character form the report heading declares.
     *
     * <p>Assumptions: {@code REPT-START-DATE} and {@code REPT-END-DATE} are each declared
     * {@code PIC X(10)} at lines 11 and 13 of {@code app/cpy/CVTRA07Y.cpy}, and the reference fills
     * them at lines 277 and 278 of {@code app/cbl/CBTRN03C.cbl} from the two ten-character items of
     * {@code WS-DATEPARM-RECORD} declared at its lines 123 and 125. That parameter record holds the
     * dates already in year-month-day order, so the target's own year-month-day rendering
     * reproduces the same bytes with no reordering, and the date arrives as a date rather than as
     * characters so a malformed value cannot reach the column at all.</p>
     *
     * <p>Alternatives Considered: a pattern-driven formatter. Rejected because the year-month-day
     * rendering the date type produces on its own is already the declared form and is
     * locale-independent, whereas a pattern would add a second statement of the same format that
     * could drift from the copybook, and a pattern built without an explicit locale would be
     * subject to the numbering-system hazard recorded on the identifier helper above.</p>
     *
     * <p>Assumptions: the width is verified rather than assumed. The rendering is ten characters
     * for every year from 0000 through 9999 and longer outside that span, and although the codec
     * would also refuse an over-wide value its message names the field and the byte count rather
     * than the date, so the check is made here where the date is still in hand.</p>
     *
     * @param date the business date to render into the heading; must not be {@code null} and must
     *     fall in a year the declared width admits
     * @param fieldName the declared name of the receiving field, used only to name the field in a
     *     failure message
     * @return the date as exactly {@link #REPORT_DATE_WIDTH} characters in year-month-day order
     * @throws IllegalArgumentException if the date does not render to exactly the declared width
     */
    private static String renderReportDate(LocalDate date, String fieldName) {
        String rendered = date.toString();
        if (rendered.length() != REPORT_DATE_WIDTH) {
            throw new IllegalArgumentException(fieldName + " declares " + REPORT_DATE_WIDTH
                    + " character positions but " + date + " renders as " + rendered.length()
                    + "; the declared width admits only the years 0000 through 9999");
        }
        return rendered;
    }
}
