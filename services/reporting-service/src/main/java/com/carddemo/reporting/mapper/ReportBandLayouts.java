package com.carddemo.reporting.mapper;

import static com.carddemo.common.codec.CopybookLayout.text;
import static com.carddemo.common.codec.CopybookLayout.uint;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declares the seven output band layouts of the 133-column daily transaction report.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class is the single place the byte geometry and the invariant literal content of the daily
 * transaction report are stated. It transcribes the seven level-01 items of
 * {@code app/cpy/CVTRA07Y.cpy} into {@link CopybookLayout.RecordSpec} descriptors, and it carries the
 * label, joiner and rule literals those bands print. {@code TransactionReportMapper} is its consumer:
 * this class declares, the mapper composes a value map and hands it to
 * {@link FixedWidthCodec#encodeRecord(Map, CopybookLayout.RecordSpec)}, which places and pads every
 * byte.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a static holder that cannot be
 * instantiated, so the type accepts no parameter, yields no value and raises nothing of its own. The
 * inapplicability is stated rather than passed over, because user-specified Rule 1 (Explainability)
 * forbids at its line 39 a docstring that omits parameters, return values or purpose, and a reader has
 * to be able to tell a declared inapplicability from an oversight. Every member below carries its own
 * parameter, return and exception at-clauses. One error can arise from this class without any member
 * being called: each descriptor constant chains {@link CopybookLayout.RecordSpec#validateGeometry()}
 * in its initialiser, so a mis-stated offset surfaces as a {@link CopybookLayout.LayoutException}
 * wrapped in an initialisation error at class load rather than as a wrong report much later.</p>
 *
 * <h2>Assumptions: 133 is a declared record length and never a sum of field widths</h2>
 *
 * <p>The report's record length is a property of the data set it is written to, and it is anchored
 * three independent ways. Line 48 of {@code app/cpy/CVTRA07Y.cpy} declares an elementary level-01 item
 * of {@code PIC X(133)} whose value is a row of hyphens. Line 85 of {@code app/cbl/CBTRN03C.cbl}
 * declares the output record as {@code PIC X(133)} and line 133 of the same program declares a blank
 * line at the same width. Line 78 of {@code app/jcl/TRANREPT.jcl} names the same 133 as the logical
 * record length of the output data set.</p>
 *
 * <p>Summing the declared widths of a band produces a different number, and it is worth writing the
 * seven sums out so that nobody derives the record length from one of them. The name header sums to
 * 115, the detail band to 114, the first column header to 114, the separator rule to 133, and each of
 * the three total bands to 112. Only the separator rule reaches 133 on its own. A reader who took the
 * detail band's 114 for the record length would land every band written after it nineteen bytes
 * short.</p>
 *
 * <h2>Assumptions: six of the seven bands are short and carry an explicit pad field</h2>
 *
 * <p>{@link CopybookLayout.RecordSpec#validateGeometry()} is fail-closed on two invariants: fields must
 * be contiguous from offset zero, and their lengths must sum to the declared record length exactly. A
 * band described short is therefore rejected outright, and leaving the shortfall as an undescribed gap
 * is not available. Each of the six short bands consequently declares one trailing field, named by
 * {@link #FIELD_LINE_PAD}, whose only content is blanks: eighteen bytes on the name header, nineteen on
 * each of the two 114-byte bands and twenty-one on each of the three total bands. The separator rule
 * needs none.</p>
 *
 * <p>Alternatives Considered: declaring each band at its own summed length rather than at 133 and
 * letting a caller pad. Rejected, because the record length would then differ band to band while the
 * data set has exactly one, and the padding decision would move to the call site that the package
 * charter beside this file confines such decisions away from. Declaring the pad keeps every band the
 * same width as the data set and keeps the geometry provable.</p>
 *
 * <h2>Assumptions: every value must be supplied for these bands, because they are not registered</h2>
 *
 * <p>{@code FixedWidthCodec} rebuilds omitted padding as blanks only for a layout that
 * {@code CopybookLayout} registers, which its padding test establishes by requiring that the supplied
 * descriptor be the very instance the registry holds. These seven bands are declared here and are
 * deliberately not in that registry, so none of them qualifies. The consequence is exact: the value map
 * handed to {@link FixedWidthCodec#encodeRecord(Map, CopybookLayout.RecordSpec)} must contain an entry
 * for every declared field of the band, the pad field included, or the encode raises a codec failure
 * naming the missing field. The seven template methods on this class exist for precisely that reason;
 * each returns a map already holding every invariant field of its band, so the mapper supplies only the
 * values that vary from line to line.</p>
 *
 * <h2>Assumptions: all 22 FILLER items in this copybook are content, not padding</h2>
 *
 * <p>{@code app/cpy/CVTRA07Y.cpy} declares 22 {@code FILLER} items and every single one of them carries
 * a {@code VALUE} clause, so the proportion here is not most of them but all of them. Four of those
 * clauses are {@code VALUE ALL}: the hyphen rule at line 48 and the three dot leaders at lines 53, 59
 * and 65. Not one {@code FILLER} in this file may be blanked, because in each case the literal IS the
 * printed data: the column headings, the joiners inside a code-and-description pair, the dot leaders
 * that carry the eye across to a total, and the rule line under the headings. Every one is therefore
 * declared as a real field below and seeded with its literal by the matching template method.</p>
 *
 * <p>Assumptions: the baseline gets away with never restating those literals because
 * {@code app/cbl/CBTRN03C.cbl} line 362 opens its detail paragraph with
 * {@code INITIALIZE TRANSACTION-DETAIL-REPORT}, and the language's {@code INITIALIZE} verb skips
 * {@code FILLER}. The two joiner bytes and every space {@code FILLER} therefore survive untouched from
 * one detail line to the next in a record area that persists. A Java band is assembled into a freshly
 * allocated array with no previous content to survive, so this class has to declare those items and
 * their literals explicitly. That is the whole reason the descriptors below carry {@code FILLER} at
 * all.</p>
 *
 * <h2>Alternatives Considered: naming the 22 FILLER items apart rather than all alike</h2>
 *
 * <p>Each {@code FILLER} below is named for the copybook line that declares it, so the detail band
 * holds {@code FILLER-L17} through {@code FILLER-L31} and the two column-heading bands hold
 * {@code FILLER-L34} through {@code FILLER-L65}. Naming all 22 plainly {@code FILLER}, as the copybook
 * does, was evaluated and rejected on a hard mechanical ground rather than a stylistic one:
 * {@link FixedWidthCodec#encodeRecord(Map, CopybookLayout.RecordSpec)} resolves values BY FIELD NAME,
 * and a map admits one value per key. Seven identically named fields in the detail band would leave six
 * of them unreachable, and the two joiner bytes would collide with the space separators, so the record
 * could not be composed at all. {@link CopybookLayout.RecordSpec#field(String)} would likewise resolve
 * only the first of them.</p>
 *
 * <p>Assumptions: this is not a rename and AAP Rule T1 (Copybook is normative) is not bent by it.
 * {@code FILLER} is the language's keyword for an item that has NO name, so there is no declared name
 * being changed; a distinguishing suffix is being supplied where the copybook supplied none. The
 * baseline itself does the same thing when it needs to, naming a padding item
 * {@code SEC-USR-FILLER} at line 23 of {@code app/cpy/CSUSR01Y.cpy}. Every genuinely named field below
 * is carried across character for character, and the suffix scheme is line-derived so each item stays
 * traceable to the declaration it came from.</p>
 *
 * <h2>Assumptions: an edit mask is not a storage regime</h2>
 *
 * <p>{@code CopybookLayout.Kind} offers exactly five regimes, and none of them is an edit mask. The
 * four amount items in this copybook are declared {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} at line 30 and
 * {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} at lines 54, 60 and 66, which are display templates rather than storage.
 * The seam is therefore two-stage and one-directional: {@code CobolEditMask} renders the amount into a
 * 15-character {@link String} first, and this class declares the receiving field as
 * {@code Kind.TEXT} of that width so the codec merely places it. No descriptor here names a mask, and
 * reaching for a regime shaped like a mask on {@code CopybookLayout.Kind} will not find one.</p>
 *
 * <p>Assumptions: every {@link CopybookLayout.FieldSpec#start()} below is ZERO-based, which is the
 * convention that record descriptor uses throughout. The one-based column numbers that appear in the
 * commentary are stated as such wherever they are used, and the conversion runs only one way,
 * {@code zeroBased = oneBased - 1}. Applied backwards it shifts a whole band by one byte, and a band
 * shifted by one byte still prints readable text, so nothing would raise.</p>
 *
 * <h2>Assumptions: no field of any band is sensitive, and none is a normalised timestamp</h2>
 *
 * <p>Every descriptor below leaves {@link CopybookLayout.FieldSpec#sensitive()} and
 * {@link CopybookLayout.FieldSpec#normalizeTs()} false, and the reason is stated once here rather than
 * repeated 50 times. No primary account number appears anywhere in {@code app/cpy/CVTRA07Y.cpy}: the
 * detail band carries a transaction identifier, an account identifier, a type code and description, a
 * category code and description, a source and an amount, and nothing else. Marking a field sensitive
 * would in any case not mask anything, since the flag only marks; and narrowing what this report prints
 * would break the byte comparison the parity oracle performs. No band carries a timestamp item either,
 * so there is nothing for the parity comparison to blank.</p>
 *
 * <h2>Assumptions: these bands have no retrieval key, and the descriptor requires one anyway</h2>
 *
 * <p>Line 78 of {@code app/jcl/TRANREPT.jcl} declares the report data set with a record format of
 * {@code FB} and no key operand at all, which is what a sequential print file is. Nothing reads a
 * report band by key. {@link CopybookLayout.RecordSpec} nonetheless requires a key length of at least
 * one byte, because every layout it was shaped for is a keyed data set, so each band below declares the
 * nominal minimum of {@link #NO_RETRIEVAL_KEY_LENGTH} at offset {@link #NO_RETRIEVAL_KEY_OFFSET}. Those
 * two constants are named rather than written as bare digits so that a reader meets the explanation at
 * the point of use and does not mistake the value for a real key.</p>
 *
 * <h2>Assumptions: the copybook writes PIC before VALUE, and its literals wrap</h2>
 *
 * <p>Two properties of this source file matter to anyone checking a transcription against it. Its
 * clause order is {@code PIC} and then {@code VALUE} throughout, whereas
 * {@code app/cbl/CBSTM03A.CBL} writes the two the other way round, so a reader or a parser that
 * internalises one order mis-reads the other file. And twelve of its literals are CONTINUED onto the
 * following line: lines 5 to 6, 7 to 8, 9 to 10, 34 to 35, 36 to 37, 38 to 39, 40 to 41, 42 to 43, 45
 * to 46, 51 to 52, 57 to 58 and 63 to 64 each hold one literal across two lines. Reading such a
 * declaration a line at a time yields a field with a width and no value, so the continuation has to be
 * rejoined before the literal is read. Every literal below was taken from the rejoined form and
 * verified against the file's bytes, which is how the three whitespace-bearing ones kept their
 * spaces.</p>
 *
 * <h2>Alternatives Considered: declaring these descriptors here rather than in the shared kernel</h2>
 *
 * <p>{@code CopybookLayout} holds a registry of stored-record layouts, and no report band is among
 * them. Extending that registry with these seven was evaluated and rejected. The registry describes
 * records that are READ from and WRITTEN to keyed data sets and that several bounded contexts share;
 * a report band is an OUTPUT layout belonging to the one module that prints it, and nothing outside
 * this module has any use for it. Registering them would also change their behaviour rather than merely
 * their location, because that registry's membership is exactly what the codec's padding test consults,
 * as recorded above. {@code CopybookLayout.FieldSpec} and {@code CopybookLayout.RecordSpec} are public
 * so that a consumer can declare its own descriptors, which is what this class does.</p>
 *
 * <p>Assumptions: declaring them HERE rather than at each call site is the half of the decision that is
 * not optional. The house convention is stated at lines 540 to 542 of {@code tests/README.md}, which
 * requires that a record layout never be duplicated and be kept single-sourced; the COBOL side honours
 * it through one copybook include path, and the Java side honours it through one declaration per band
 * that every emitter reads.</p>
 *
 * <h2>Alternatives Considered: keeping the report bands apart from the statement bands</h2>
 *
 * <p>The statement descriptors live in their own type, {@code StatementBandLayouts}, and merging the two
 * holders was rejected because their padding obligations are opposite rather than merely different. Six
 * of the seven bands here fall short of the declared 133 and must each be blank-padded to reach it,
 * whereas every statement band is natively exactly its declared width and must not be padded at all. A
 * single holder would invite one shared padding helper, and a helper correct for one artifact would be
 * wrong for the other. Two types make that boundary structural instead of a comment a reader may
 * skim.</p>
 *
 * <p>Trade-offs: the cost of everything above is bulk. This class declares 45 field descriptors and 37
 * name constants to print seven lines of a report, and a shorter version that built each band from
 * inline literals at the point of emission would compile. The compromise accepted is verbosity in one
 * file in exchange for two properties worth more than brevity here. Every offset and every literal is
 * stated once, so a call site emits a byte-exact band without holding any layout knowledge of its own
 * and two call sites cannot disagree. And each band is proven at class load, so a wrong offset stops
 * the service starting instead of printing a report whose columns have quietly moved.</p>
 *
 * <p>Alternatives Considered: the documentation itself is split two ways below, and the split is
 * deliberate rather than uneven. Each of the seven band descriptors carries a Javadoc block, because a
 * band is what a consumer navigates to and what it needs told about it does not fit on one line; each of
 * the 37 name, literal and width constants carries an adjacent inline justification instead, because
 * each states a single fact traceable to a single copybook line. Giving all 44 declarations Javadoc was
 * evaluated and rejected: {@code config/checkstyle/checkstyle.xml} deliberately omits the field-level
 * Javadoc check and records why in its own commentary, user-specified Rule 1 (Explainability) scopes its
 * docstring clause at line 15 to functions, classes and module entry points, and 37 further blocks would
 * bury the seven that a reader actually comes here for. Every one of those constants is nonetheless
 * justified, which is the clause that does apply to them, at line 27.</p>
 *
 * @see CopybookLayout
 * @see CobolEditMask
 */
public final class ReportBandLayouts {

    // WHY : Assumptions: 133 is the DECLARED record length of the report data set, established three
    //       ways as the class documentation sets out -- line 48 of app/cpy/CVTRA07Y.cpy, lines 85 and
    //       133 of app/cbl/CBTRN03C.cbl, and line 78 of app/jcl/TRANREPT.jcl. It is deliberately NOT
    //       the sum of any band's field widths; those sums are 115, 114, 114, 133, 112, 112 and 112.
    public static final int REPORT_RECORD_LENGTH = 133;

    // WHY : Assumptions: 97 is the zero-based offset at which the amount column opens in all four
    //       bands that print money, which is one-based columns 98 to 112 once the 15-character mask is
    //       placed. It is a shared derived fact rather than a free choice, so it is named once and
    //       every band below is checked against it: 11 + 86 on the page total, 13 + 84 on the account
    //       total, 11 + 86 on the grand total, and 97 bytes of preceding items on the detail band.
    public static final int AMOUNT_COLUMN_OFFSET = 97;

    // WHY : Assumptions: RecordSpec requires a key of at least one byte because every layout it was
    //       shaped for is a keyed data set, whereas line 78 of app/jcl/TRANREPT.jcl declares this
    //       report RECFM=FB with no key operand, so no band has a retrieval key at all. One byte at
    //       offset zero is the smallest declaration the descriptor accepts, and it is named so a
    //       reader meets this explanation instead of inferring intent from a bare digit.
    private static final int NO_RETRIEVAL_KEY_LENGTH = 1;

    // WHY : Assumptions: the companion offset for the nominal key above. It is a separate constant
    //       rather than a reused zero because the descriptor takes length and offset as two arguments
    //       in sequence, and two same-valued literals in adjacent argument positions are exactly the
    //       pair a later edit transposes without the compiler objecting.
    private static final int NO_RETRIEVAL_KEY_OFFSET = 0;

    // WHY : Assumptions: 11, 13 and 11 are the declared widths of the three total labels, at lines 51,
    //       57 and 63 of app/cpy/CVTRA07Y.cpy. They are three constants and not one because the page
    //       and grand labels agreeing at 11 is a coincidence of their text lengths, not a shared rule,
    //       and a single constant would let an edit to one label silently move the other two.
    public static final int PAGE_TOTAL_LABEL_WIDTH = 11;

    // WHY : Assumptions: 13 at line 57 of app/cpy/CVTRA07Y.cpy, the widest of the three labels by two
    //       bytes. Those two bytes are exactly what the shorter dot leader beside it gives back, which
    //       is the arithmetic recorded on ACCOUNT_TOTAL_LEADER_WIDTH below.
    public static final int ACCOUNT_TOTAL_LABEL_WIDTH = 13;

    // WHY : Assumptions: 11 at line 63 of app/cpy/CVTRA07Y.cpy. Declared separately from the page-total
    //       label width for the reason recorded there.
    public static final int GRAND_TOTAL_LABEL_WIDTH = 11;

    // WHY : Assumptions: 86, 84 and 86 are the declared widths of the three dot leaders, at lines 53,
    //       59 and 65 of app/cpy/CVTRA07Y.cpy, and they MUST NOT be unified to one width. The three
    //       total bands all place their 15-character amount mask in the same span, one-based columns 98
    //       to 112, and the only reason that is possible is that each leader compensates for its own
    //       label: 11 + 86 = 97, 13 + 84 = 97 and 11 + 86 = 97. Collapsing the three to a single 86
    //       would put the account total's label and leader at 13 + 86 = 99, moving that one mask two
    //       columns right of the other three and breaking the column the report is read down.
    public static final int PAGE_TOTAL_LEADER_WIDTH = 86;

    // WHY : Assumptions: 84 at line 59 of app/cpy/CVTRA07Y.cpy, two bytes shorter than its two
    //       siblings because its label is two bytes longer. See PAGE_TOTAL_LEADER_WIDTH for the full
    //       arithmetic and for what unifying the three costs.
    public static final int ACCOUNT_TOTAL_LEADER_WIDTH = 84;

    // WHY : Assumptions: 86 at line 65 of app/cpy/CVTRA07Y.cpy. Declared separately from the
    //       page-total leader width for the reason recorded there.
    public static final int GRAND_TOTAL_LEADER_WIDTH = 86;

    // WHY : Assumptions: carried character for character from lines 5 and 6 of app/cpy/CVTRA07Y.cpy,
    //       where it is the VALUE of a PIC X(38) item. AAP Rule T8 (user-visible strings are verbatim)
    //       governs every literal in this block: none is trimmed, re-cased or re-spaced. The literal is
    //       eight characters in a 38-byte field, and the codec supplies the remaining 30 blanks.
    public static final String REPORT_SHORT_NAME = "DALYREPT";

    // WHY : Assumptions: lines 7 and 8 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(41) item.
    public static final String REPORT_LONG_NAME = "Daily Transaction Report";

    // WHY : Assumptions: lines 9 and 10 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(12) item, and it
    //       ends in a SPACE that is part of the literal rather than an artefact of the field width. The
    //       twelve characters are the eleven of the visible words plus that trailing space, so the
    //       literal exactly fills its field and the codec adds nothing. Trimming it would close up the
    //       gap before the start date that the report prints.
    public static final String DATE_RANGE_LABEL = "Date Range: ";

    // WHY : Assumptions: line 12 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(04) FILLER, and it
    //       carries a LEADING and a TRAILING space around the two visible letters. All four characters
    //       are the literal, so the field is exactly filled. Losing either space would run the joiner
    //       into one of the two dates it separates.
    public static final String DATE_RANGE_JOINER = " to ";

    // WHY : Assumptions: lines 21 and 25 of app/cpy/CVTRA07Y.cpy each declare a PIC X(01) FILLER whose
    //       VALUE is this one byte, and it is the ASCII hyphen-minus that renders a code and its
    //       description as a single printed unit. It is DATA and not padding, and it is NOT a minus
    //       sign: the sign position of the amount on line 30 belongs to that item's own edit mask. The
    //       two occurrences share one constant because they are the same joiner used twice for the same
    //       purpose, once after the type code and once after the category code.
    public static final String CODE_DESCRIPTION_JOINER = "-";

    // WHY : Assumptions: lines 34 and 35 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(17) FILLER. The
    //       trailing blanks that separate this heading from the next are the field width doing its
    //       work, not part of the literal, so the literal is the visible words alone.
    public static final String COLUMN_LABEL_TRANSACTION_ID = "Transaction ID";

    // WHY : Assumptions: lines 36 and 37 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(12) FILLER.
    public static final String COLUMN_LABEL_ACCOUNT_ID = "Account ID";

    // WHY : Assumptions: lines 38 and 39 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(19) FILLER. The
    //       19-byte field spans the type code, the joiner and the type description beneath it.
    public static final String COLUMN_LABEL_TRANSACTION_TYPE = "Transaction Type";

    // WHY : Assumptions: lines 40 and 41 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(35) FILLER --
    //       the widest heading field in the band, spanning the category code, its joiner and its
    //       29-byte description beneath it.
    public static final String COLUMN_LABEL_TRAN_CATEGORY = "Tran Category";

    // WHY : Assumptions: lines 42 and 43 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(14) FILLER.
    public static final String COLUMN_LABEL_TRAN_SOURCE = "Tran Source";

    // WHY : Assumptions: lines 45 and 46 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(16) FILLER, and
    //       the EIGHT LEADING SPACES are part of the literal. Fourteen characters of literal sit in a
    //       16-byte field starting at offset 98, which puts the visible word over the right-hand end of
    //       the amount column beneath it, where a right-justified money value actually prints.
    //       Left-trimming it would move the heading to the column's left edge, away from its data.
    public static final String COLUMN_LABEL_AMOUNT = "        Amount";

    // WHY : Assumptions: lines 51 and 52 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(11) FILLER.
    public static final String PAGE_TOTAL_LABEL = "Page Total";

    // WHY : Assumptions: lines 57 and 58 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(13) FILLER.
    //       Note that the band this label heads breaks on CARD NUMBER and not on account: line 181 of
    //       app/cbl/CBTRN03C.cbl compares WS-CURR-CARD-NUM, declared PIC X(16) at line 137 of that
    //       program, against the transaction's card number. The label is carried verbatim regardless,
    //       because AAP Rule T8 (user-visible strings are verbatim) admits no correction of wording.
    public static final String ACCOUNT_TOTAL_LABEL = "Account Total";

    // WHY : Assumptions: lines 63 and 64 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(11) FILLER.
    public static final String GRAND_TOTAL_LABEL = "Grand Total";

    // WHY : Assumptions: line 48 of app/cpy/CVTRA07Y.cpy declares an elementary level-01 item of
    //       PIC X(133) whose VALUE ALL is a hyphen, so the rule line is the only band that is natively
    //       the declared record length. It is built from the record length rather than written out as
    //       133 characters so the two cannot drift apart. It is deliberately NOT derived from
    //       CODE_DESCRIPTION_JOINER even though both are a hyphen: a rule line under the headings and a
    //       joiner inside a code-and-description pair are unrelated decisions in the source, and tying
    //       them together would propagate a change to one into the other.
    public static final String SEPARATOR_RULE = "-".repeat(REPORT_RECORD_LENGTH);

    // WHY : Assumptions: the fifteen constants below are the names the value map is KEYED by, and they
    //       are public because the codec resolves values by field name, so an emitter that wrote the
    //       name as a bare string literal would be restating a layout detail this class exists to hold.
    //       Every one is the field name exactly as app/cpy/CVTRA07Y.cpy declares it, per AAP Rule T1
    //       (Copybook is normative). Only the fields whose content VARIES line to line are published;
    //       the invariant ones are seeded by the template methods and are never keyed by a caller.
    public static final String FIELD_REPT_START_DATE = "REPT-START-DATE";

    // WHY : Assumptions: line 13 of app/cpy/CVTRA07Y.cpy. The start and end dates are the two variable
    //       items of the name header, and both are PIC X(10) holding an already ISO-ordered date, so
    //       their bytes are placed as text with no reformatting.
    public static final String FIELD_REPT_END_DATE = "REPT-END-DATE";

    // WHY : Assumptions: line 16 of app/cpy/CVTRA07Y.cpy, PIC X(16).
    public static final String FIELD_TRAN_REPORT_TRANS_ID = "TRAN-REPORT-TRANS-ID";

    // WHY : Assumptions: line 18 of app/cpy/CVTRA07Y.cpy, and it is ALPHANUMERIC PIC X(11) where the
    //       category code four items later is NUMERIC. That asymmetry is in the source and it is
    //       load-bearing: an alphanumeric item is placed left-justified and blank-padded, so an account
    //       identifier shorter than eleven characters prints flush left with trailing blanks and is
    //       NOT zero-filled. Declaring this one numeric to make the two consistent would zero-pad it
    //       and change the printed bytes.
    public static final String FIELD_TRAN_REPORT_ACCOUNT_ID = "TRAN-REPORT-ACCOUNT-ID";

    // WHY : Assumptions: line 20 of app/cpy/CVTRA07Y.cpy, PIC X(02).
    public static final String FIELD_TRAN_REPORT_TYPE_CD = "TRAN-REPORT-TYPE-CD";

    // WHY : Assumptions: line 22 of app/cpy/CVTRA07Y.cpy, PIC X(15).
    public static final String FIELD_TRAN_REPORT_TYPE_DESC = "TRAN-REPORT-TYPE-DESC";

    // WHY : Assumptions: line 24 of app/cpy/CVTRA07Y.cpy, PIC 9(04) -- the one NUMERIC item in the
    //       detail band, which is why its descriptor uses the unsigned display regime rather than text.
    //       The regime choice is argued at the descriptor itself.
    public static final String FIELD_TRAN_REPORT_CAT_CD = "TRAN-REPORT-CAT-CD";

    // WHY : Assumptions: line 26 of app/cpy/CVTRA07Y.cpy, PIC X(29) -- the widest description in the
    //       band.
    public static final String FIELD_TRAN_REPORT_CAT_DESC = "TRAN-REPORT-CAT-DESC";

    // WHY : Assumptions: line 28 of app/cpy/CVTRA07Y.cpy, PIC X(10).
    public static final String FIELD_TRAN_REPORT_SOURCE = "TRAN-REPORT-SOURCE";

    // WHY : Assumptions: line 30 of app/cpy/CVTRA07Y.cpy, declared PIC -ZZZ,ZZZ,ZZZ.ZZ. The value
    //       keyed under this name must already be the rendered 15-character string, because an edit
    //       mask is a display template and not a storage regime, as the class documentation sets out.
    public static final String FIELD_TRAN_REPORT_AMT = "TRAN-REPORT-AMT";

    // WHY : Assumptions: line 54 of app/cpy/CVTRA07Y.cpy, declared PIC +ZZZ,ZZZ,ZZZ.ZZ. The three total
    //       items keep their own names rather than sharing one, because a single name across three
    //       bands would make it impossible to tell from a value map which total was being composed.
    public static final String FIELD_REPT_PAGE_TOTAL = "REPT-PAGE-TOTAL";

    // WHY : Assumptions: line 60 of app/cpy/CVTRA07Y.cpy, declared PIC +ZZZ,ZZZ,ZZZ.ZZ.
    public static final String FIELD_REPT_ACCOUNT_TOTAL = "REPT-ACCOUNT-TOTAL";

    // WHY : Assumptions: line 66 of app/cpy/CVTRA07Y.cpy, declared PIC +ZZZ,ZZZ,ZZZ.ZZ.
    public static final String FIELD_REPT_GRAND_TOTAL = "REPT-GRAND-TOTAL";

    // WHY : Assumptions: line 48 of app/cpy/CVTRA07Y.cpy declares the separator rule as an ELEMENTARY
    //       level-01 item with its own PIC and no subordinates, so the band has exactly one field and
    //       that field's name is the band's name. The single-field descriptor is the faithful reading;
    //       modelling it as a group with one child would invent a level the copybook does not have.
    public static final String FIELD_SEPARATOR_RULE = "TRANSACTION-HEADER-2";

    // WHY : Assumptions: this field is NOT in app/cpy/CVTRA07Y.cpy. It exists because
    //       RecordSpec.validateGeometry() is fail-closed on an exact sum, so the shortfall between a
    //       band's declared widths and the 133-byte record cannot be left as a gap. The name is
    //       deliberately unlike any copybook name so that a reader can see at a glance that it is a
    //       target-side construct, and it is published so a test can assert on it directly.
    public static final String FIELD_LINE_PAD = "REPORT-LINE-PAD";

    // WHY : Assumptions: an empty value in a text field is expanded by the codec to a full field of
    //       blanks, because it places what it is given from the left and blank-fills the remainder. One
    //       constant therefore serves both populations that need blanks: the seven FILLER items declared
    //       VALUE SPACES in app/cpy/CVTRA07Y.cpy, at its lines 17, 19, 23, 27, 29, 31 and 44, and the
    //       trailing pad field of each short band. Writing out the exact number of spaces at each site
    //       was rejected because the count would then be stated twice per field, once as the width and
    //       once as the literal, and the two could disagree.
    private static final String BLANK_CONTENT = "";

    // WHY : Assumptions: line 53 of app/cpy/CVTRA07Y.cpy declares VALUE ALL '.' over 86 bytes, so the
    //       leader is built from its own width constant rather than written out. The three leaders are
    //       three separate constants for the reason recorded on PAGE_TOTAL_LEADER_WIDTH: they are 86,
    //       84 and 86, and one shared leader would move the account total out of its column.
    private static final String PAGE_TOTAL_LEADER = ".".repeat(PAGE_TOTAL_LEADER_WIDTH);

    // WHY : Assumptions: line 59 of app/cpy/CVTRA07Y.cpy declares VALUE ALL '.' over 84 bytes -- two
    //       fewer than its siblings, giving back exactly the two bytes its longer label consumed.
    private static final String ACCOUNT_TOTAL_LEADER = ".".repeat(ACCOUNT_TOTAL_LEADER_WIDTH);

    // WHY : Assumptions: line 65 of app/cpy/CVTRA07Y.cpy declares VALUE ALL '.' over 86 bytes.
    private static final String GRAND_TOTAL_LEADER = ".".repeat(GRAND_TOTAL_LEADER_WIDTH);

    // WHY : Assumptions: the 22 constants below name the copybook's 22 FILLER items, each after the
    //       line that declares it, for the reason argued in the class documentation: the codec resolves
    //       values by field name and a map holds one value per key, so seven items all named FILLER
    //       would leave six of them unreachable. Each is a CONSTANT rather than a literal written twice
    //       because every one is used at two sites, in a descriptor and in a template, and a typo
    //       between the two would surface only as a codec failure at run time instead of as a
    //       compilation error. The line-derived suffix keeps each item traceable to its declaration.
    private static final String FILLER_L12 = "FILLER-L12";

    private static final String FILLER_L17 = "FILLER-L17";

    private static final String FILLER_L19 = "FILLER-L19";

    private static final String FILLER_L21 = "FILLER-L21";

    private static final String FILLER_L23 = "FILLER-L23";

    private static final String FILLER_L25 = "FILLER-L25";

    private static final String FILLER_L27 = "FILLER-L27";

    private static final String FILLER_L29 = "FILLER-L29";

    private static final String FILLER_L31 = "FILLER-L31";

    private static final String FILLER_L34 = "FILLER-L34";

    private static final String FILLER_L36 = "FILLER-L36";

    private static final String FILLER_L38 = "FILLER-L38";

    private static final String FILLER_L40 = "FILLER-L40";

    private static final String FILLER_L42 = "FILLER-L42";

    private static final String FILLER_L44 = "FILLER-L44";

    private static final String FILLER_L45 = "FILLER-L45";

    private static final String FILLER_L51 = "FILLER-L51";

    private static final String FILLER_L53 = "FILLER-L53";

    private static final String FILLER_L57 = "FILLER-L57";

    private static final String FILLER_L59 = "FILLER-L59";

    private static final String FILLER_L63 = "FILLER-L63";

    private static final String FILLER_L65 = "FILLER-L65";

    /**
     * The report title band, transcribed from lines 4 to 13 of {@code app/cpy/CVTRA07Y.cpy}.
     *
     * <p>Assumptions: this is the band that renders the reporting date range, and the range it renders
     * has to be the same one the query filtered on. The reference job carries two independent date
     * sources: lines 43 and 44 of {@code app/jcl/TRANREPT.jcl} declare the sort utility's own
     * comparison literals, while lines 73 and 74 of the same job supply a separate parameter data set
     * that {@code app/cbl/CBTRN03C.cbl} reads for this heading. The two must agree, and nothing in the
     * reference makes them agree. The target consequently passes ONE date range into both the selection
     * predicate and this band, so the heading cannot describe a window the data does not come from.</p>
     *
     * <p>Assumptions: the sort utility's {@code INCLUDE COND=} at lines 47 and 48 of
     * {@code app/jcl/TRANREPT.jcl} is a RECORD-SELECTION predicate over the processing date and not a
     * step gate, so it becomes a query restriction rather than anything this class models. It is named
     * here because it is the other half of the date agreement described above.</p>
     */
    public static final CopybookLayout.RecordSpec REPORT_NAME_HEADER = new CopybookLayout.RecordSpec(
            "REPORT-NAME-HEADER", REPORT_RECORD_LENGTH,
            NO_RETRIEVAL_KEY_LENGTH, NO_RETRIEVAL_KEY_OFFSET,
            List.of(
                    text("REPT-SHORT-NAME", 0, 38),
                    text("REPT-LONG-NAME", 38, 41),
                    text("REPT-DATE-HEADER", 79, 12),
                    text(FIELD_REPT_START_DATE, 91, 10),
                    text(FILLER_L12, 101, 4),
                    text(FIELD_REPT_END_DATE, 105, 10),

                    // WHY : Assumptions: the six items above sum to 115, so 18 bytes are owed to the
                    //       declared 133. validateGeometry() rejects an undescribed shortfall, so the
                    //       pad is declared rather than left implicit. Its content is blanks.
                    text(FIELD_LINE_PAD, 115, 18))).validateGeometry();

    /**
     * The transaction detail band, transcribed from lines 15 to 31 of {@code app/cpy/CVTRA07Y.cpy}.
     *
     * <p>Assumptions: sixteen items are declared in the copybook and eight of them are {@code FILLER}
     * carrying a literal, which is why the descriptor is twice as long as the eight values a caller
     * actually varies. Two of those literals are the hyphen joiners at lines 21 and 25 that render a
     * code and its description as {@code NN-Description}; they are printed data, not spacing.</p>
     *
     * <p>Assumptions: the amount item lands at zero-based offset 97, which is one-based columns 98 to
     * 112 -- the identical span the three total bands place their own masks in. The detail band reaches
     * it by accumulating 97 bytes of preceding items rather than by any leader, so the agreement between
     * the four bands is arithmetic rather than declared and a change to any width above the amount would
     * silently break it.</p>
     *
     * @see #REPORT_PAGE_TOTALS
     */
    public static final CopybookLayout.RecordSpec TRANSACTION_DETAIL_REPORT =
            new CopybookLayout.RecordSpec(
                    "TRANSACTION-DETAIL-REPORT", REPORT_RECORD_LENGTH,
                    NO_RETRIEVAL_KEY_LENGTH, NO_RETRIEVAL_KEY_OFFSET,
                    List.of(
                            text(FIELD_TRAN_REPORT_TRANS_ID, 0, 16),
                            text(FILLER_L17, 16, 1),
                            text(FIELD_TRAN_REPORT_ACCOUNT_ID, 17, 11),
                            text(FILLER_L19, 28, 1),
                            text(FIELD_TRAN_REPORT_TYPE_CD, 29, 2),

                            // WHY : Assumptions: line 21 declares a PIC X(01) FILLER whose VALUE is a
                            //       hyphen, and it is the joiner that binds the type code to the
                            //       description that follows it. It is DATA and must be emitted, and it
                            //       is NOT a sign position -- the amount's sign belongs to that item's
                            //       own edit mask on line 30. The reference program never restates it
                            //       because line 362 of app/cbl/CBTRN03C.cbl re-initialises the band
                            //       and the language's INITIALIZE verb skips FILLER, leaving the byte
                            //       in place; a freshly allocated Java array has no such survivor.
                            text(FILLER_L21, 31, 1),
                            text(FIELD_TRAN_REPORT_TYPE_DESC, 32, 15),
                            text(FILLER_L23, 47, 1),

                            // WHY : Alternatives Considered: this is the only NUMERIC item in the band,
                            //       declared PIC 9(04) on line 24 where its neighbours are PIC X, and
                            //       the unsigned display regime is chosen over rendering the digits
                            //       upstream and declaring the field as text. The regimes are not
                            //       interchangeable here. Unsigned display right-justifies and
                            //       ZERO-fills to the declared width, so category 5 prints as 0005,
                            //       which is what PIC 9(04) means. Text left-justifies and BLANK-fills,
                            //       so the same value would print as a 5 followed by three blanks and
                            //       the joiner after it would no longer sit against the description.
                            //       Choosing this regime also keeps the obligation off the caller: the
                            //       value may be supplied as an integral number or as digit text, and
                            //       the codec pads it either way. The consequence accepted is that the
                            //       caller must not hand this field a rendered, blank-padded string.
                            uint(FIELD_TRAN_REPORT_CAT_CD, 48, 4),

                            // WHY : Assumptions: line 25 declares the second hyphen joiner, binding the
                            //       category code to its description exactly as line 21 binds the type
                            //       code. The same reasoning recorded on FILLER_L21 applies unchanged.
                            text(FILLER_L25, 52, 1),
                            text(FIELD_TRAN_REPORT_CAT_DESC, 53, 29),
                            text(FILLER_L27, 82, 1),
                            text(FIELD_TRAN_REPORT_SOURCE, 83, 10),
                            text(FILLER_L29, 93, 4),

                            // WHY : Assumptions: the amount is declared with an edit mask on line 30,
                            //       and an edit mask is not one of the five storage regimes the record
                            //       descriptor offers. It is therefore declared as text of the mask's
                            //       own width, taken from CobolEditMask so that the 15 is stated once
                            //       in this package, and the value handed to the codec is the already
                            //       rendered string. Its offset of 97 is the shared amount column.
                            text(FIELD_TRAN_REPORT_AMT, AMOUNT_COLUMN_OFFSET,
                                    CobolEditMask.REPORT_AMOUNT_WIDTH),
                            text(FILLER_L31, 112, 2),

                            // WHY : Assumptions: the sixteen copybook items sum to 114, so 19 bytes are
                            //       owed to the declared 133. That 114 is also the number a reader
                            //       arrives at who mistakes this band's width for the record length.
                            text(FIELD_LINE_PAD, 114, 19))).validateGeometry();

    /**
     * The column heading band, transcribed from lines 33 to 46 of {@code app/cpy/CVTRA07Y.cpy}.
     *
     * <p>Assumptions: every one of this band's seven copybook items is a {@code FILLER} carrying a
     * literal, so the band has no variable content at all and its template returns a complete record.
     * The headings are not evenly spaced: their widths are 17, 12, 19, 35, 14, 1 and 16, each sized to
     * span the detail-band items beneath it rather than the heading text itself.</p>
     *
     * <p>Assumptions: the reference program emits this band third in its heading sequence, after the
     * title band and after a wholly blank line, and follows it with the rule line. That blank line is
     * NOT part of {@code app/cpy/CVTRA07Y.cpy} at all: it is declared as {@code PIC X(133) VALUE SPACES}
     * at line 133 of {@code app/cbl/CBTRN03C.cbl} and moved into the output record at line 329 of that
     * program. This class therefore declares no descriptor for it, and the emission sequence belongs to
     * the mapper. It is recorded here because a reader comparing this band's position against a report
     * listing needs to know why there is a line between the title and the headings.</p>
     */
    public static final CopybookLayout.RecordSpec TRANSACTION_HEADER_1 = new CopybookLayout.RecordSpec(
            "TRANSACTION-HEADER-1", REPORT_RECORD_LENGTH,
            NO_RETRIEVAL_KEY_LENGTH, NO_RETRIEVAL_KEY_OFFSET,
            List.of(
                    text(FILLER_L34, 0, 17),
                    text(FILLER_L36, 17, 12),
                    text(FILLER_L38, 29, 19),
                    text(FILLER_L40, 48, 35),
                    text(FILLER_L42, 83, 14),

                    // WHY : Assumptions: line 44 declares FILLER PIC X with NO parenthesised count,
                    //       which is width ONE and not an unspecified width. It is easy to read past
                    //       and it cannot be dropped: this single byte closes the preceding headings at
                    //       offset 97, exactly where the amount column opens in the other three bands,
                    //       so omitting it would pull the amount heading one byte left of its column.
                    text(FILLER_L44, 97, 1),
                    text(FILLER_L45, 98, 16),

                    // WHY : Assumptions: the seven copybook items sum to 114, so 19 bytes are owed to
                    //       the declared 133, exactly as on the detail band above.
                    text(FIELD_LINE_PAD, 114, 19))).validateGeometry();

    /**
     * The rule line under the headings, transcribed from line 48 of {@code app/cpy/CVTRA07Y.cpy}.
     *
     * <p>Assumptions: this is the only band declared as an ELEMENTARY level-01 item, carrying its own
     * {@code PIC X(133)} and {@code VALUE ALL} with no subordinates, so it is the only one that is
     * natively the declared record length and the only one needing no pad field. It is modelled as a
     * one-field record whose single field spans all 133 bytes, which is the faithful reading of an
     * elementary item.</p>
     *
     * <p>Assumptions: the reference program emits this band at THREE separate sites, lines 300, 312 and
     * 337 of {@code app/cbl/CBTRN03C.cbl}, all funnelling into the one physical write at line 345. That
     * repetition is part of the byte stream the parity comparison reads, so an emitter must reproduce
     * every occurrence rather than collapsing them into one.</p>
     */
    public static final CopybookLayout.RecordSpec TRANSACTION_HEADER_2 = new CopybookLayout.RecordSpec(
            "TRANSACTION-HEADER-2", REPORT_RECORD_LENGTH,
            NO_RETRIEVAL_KEY_LENGTH, NO_RETRIEVAL_KEY_OFFSET,
            List.of(text(FIELD_SEPARATOR_RULE, 0, REPORT_RECORD_LENGTH))).validateGeometry();

    /**
     * The page total band, transcribed from lines 50 to 54 of {@code app/cpy/CVTRA07Y.cpy}.
     *
     * <p>Assumptions: an 11-byte label plus an 86-byte dot leader is 97, which places this band's
     * 15-character amount mask at zero-based offset 97, one-based columns 98 to 112. The two sibling
     * total bands below reach the same 97 from different pairs, and the arithmetic is the whole reason
     * the three leaders differ. This band and the two below are declared out in full, rather than built
     * by a shared helper taking the widths as arguments, so that the 11 and 86 here sit beside the 13
     * and 84 and the 11 and 86 that follow and can be read against each other at a glance. A shared
     * helper would let one edit move all three.</p>
     */
    public static final CopybookLayout.RecordSpec REPORT_PAGE_TOTALS = new CopybookLayout.RecordSpec(
            "REPORT-PAGE-TOTALS", REPORT_RECORD_LENGTH,
            NO_RETRIEVAL_KEY_LENGTH, NO_RETRIEVAL_KEY_OFFSET,
            List.of(
                    text(FILLER_L51, 0, PAGE_TOTAL_LABEL_WIDTH),
                    text(FILLER_L53, PAGE_TOTAL_LABEL_WIDTH, PAGE_TOTAL_LEADER_WIDTH),
                    text(FIELD_REPT_PAGE_TOTAL, AMOUNT_COLUMN_OFFSET,
                            CobolEditMask.REPORT_AMOUNT_WIDTH),

                    // WHY : Assumptions: 11 + 86 + 15 is 112, so 21 bytes are owed to the declared 133.
                    //       The same 21 is owed by both sibling total bands, which is a consequence of
                    //       all three sharing the amount column and the mask width, not a coincidence.
                    text(FIELD_LINE_PAD, 112, 21))).validateGeometry();

    /**
     * The card-break total band, transcribed from lines 56 to 60 of {@code app/cpy/CVTRA07Y.cpy}.
     *
     * <p>Assumptions: a 13-byte label plus an 84-byte dot leader is 97, the same offset the other three
     * money-bearing bands place their masks at. This is the band whose leader is two bytes shorter, and
     * it is shorter by exactly the two bytes its longer label consumed. Unifying the three leaders at 86
     * would put this mask at 99 and move this one total two columns right of the rest.</p>
     *
     * <p>Assumptions: the label reads as an account total but the roll-up it closes breaks on CARD
     * NUMBER. Line 181 of {@code app/cbl/CBTRN03C.cbl} compares {@code WS-CURR-CARD-NUM}, declared
     * {@code PIC X(16)} at line 137 of that program, against the transaction's card number. The wording
     * is carried across unchanged under AAP Rule T8 (user-visible strings are verbatim); the divergence
     * between the label and the break key is recorded rather than resolved, and choosing the break key
     * belongs to the emitter and not to this class.</p>
     */
    public static final CopybookLayout.RecordSpec REPORT_ACCOUNT_TOTALS = new CopybookLayout.RecordSpec(
            "REPORT-ACCOUNT-TOTALS", REPORT_RECORD_LENGTH,
            NO_RETRIEVAL_KEY_LENGTH, NO_RETRIEVAL_KEY_OFFSET,
            List.of(
                    text(FILLER_L57, 0, ACCOUNT_TOTAL_LABEL_WIDTH),
                    text(FILLER_L59, ACCOUNT_TOTAL_LABEL_WIDTH, ACCOUNT_TOTAL_LEADER_WIDTH),
                    text(FIELD_REPT_ACCOUNT_TOTAL, AMOUNT_COLUMN_OFFSET,
                            CobolEditMask.REPORT_AMOUNT_WIDTH),

                    // WHY : Assumptions: 13 + 84 + 15 is 112, the same 112 the other two total bands
                    //       reach from their own label and leader pairs, so the same 21 bytes are owed.
                    text(FIELD_LINE_PAD, 112, 21))).validateGeometry();

    /**
     * The grand total band, transcribed from lines 62 to 66 of {@code app/cpy/CVTRA07Y.cpy}.
     *
     * <p>Assumptions: an 11-byte label plus an 86-byte dot leader is 97, matching the page total band's
     * pair exactly. The two agreeing is a consequence of their two labels happening to be declared the
     * same width, not of a shared rule, which is why each keeps its own constants.</p>
     */
    public static final CopybookLayout.RecordSpec REPORT_GRAND_TOTALS = new CopybookLayout.RecordSpec(
            "REPORT-GRAND-TOTALS", REPORT_RECORD_LENGTH,
            NO_RETRIEVAL_KEY_LENGTH, NO_RETRIEVAL_KEY_OFFSET,
            List.of(
                    text(FILLER_L63, 0, GRAND_TOTAL_LABEL_WIDTH),
                    text(FILLER_L65, GRAND_TOTAL_LABEL_WIDTH, GRAND_TOTAL_LEADER_WIDTH),
                    text(FIELD_REPT_GRAND_TOTAL, AMOUNT_COLUMN_OFFSET,
                            CobolEditMask.REPORT_AMOUNT_WIDTH),

                    // WHY : Assumptions: 11 + 86 + 15 is 112, so 21 bytes are owed to the declared 133.
                    text(FIELD_LINE_PAD, 112, 21))).validateGeometry();

    /**
     * Prevents instantiation of this static descriptor holder.
     *
     * <p>Alternatives Considered: an instantiable class, or one published as a shared instance, was
     * evaluated and rejected. Every member here is a constant or a pure function of nothing, so an
     * instance would advertise a lifecycle that does not exist and would offer a collaborator that no
     * test has any reason to substitute. Declaring the constructor private states that where the
     * language enforces it, and the class is final so no subclass can reopen the decision.</p>
     *
     * <p>Assumptions: the constructor is documented in full even though it is private and empty,
     * because user-specified Rule 1 (Explainability) requires a docstring on every function without
     * qualifying by visibility, and the ruleset that mechanises it audits constructors at private scope
     * through {@code MissingJavadocMethod} in {@code config/checkstyle/checkstyle.xml}.</p>
     */
    private ReportBandLayouts() {
    }

    /**
     * Returns all seven report band descriptors in the order the copybook declares them.
     *
     * <p>Assumptions: the order is the copybook's own, being the title band, the detail band, the two
     * heading bands and then the page, card-break and grand total bands. It is deliberately NOT the
     * order a report emits them in, which interleaves them and repeats the rule line at three separate
     * sites; declaring one order here and calling it an emission sequence would misrepresent both.</p>
     *
     * <p>Trade-offs: publishing the collection duplicates access that the seven constants already
     * provide individually. It is published anyway because the one property that has to hold across all
     * seven -- that every band is exactly the declared record length -- can only be asserted over the
     * whole set, and a test that enumerated the constants by hand would silently stop covering an eighth
     * band if one were ever added.</p>
     *
     * @return an unmodifiable list of the seven band descriptors, each already proven to tile its 133
     *     declared bytes contiguously from offset zero
     */
    public static List<CopybookLayout.RecordSpec> allBands() {
        return List.of(
                REPORT_NAME_HEADER,
                TRANSACTION_DETAIL_REPORT,
                TRANSACTION_HEADER_1,
                TRANSACTION_HEADER_2,
                REPORT_PAGE_TOTALS,
                REPORT_ACCOUNT_TOTALS,
                REPORT_GRAND_TOTALS);
    }

    /**
     * Builds a value map for the title band holding every invariant field it declares.
     *
     * <p>Assumptions: the caller completes the returned map with {@link #FIELD_REPT_START_DATE} and
     * {@link #FIELD_REPT_END_DATE} before encoding. Those two are the only items of this band that vary,
     * and both are declared {@code PIC X(10)} holding an already ISO-ordered date, so their bytes are
     * placed as text. Every other field, including the trailing pad, is seeded here because these
     * descriptors are not registered with {@code CopybookLayout} and the codec therefore demands a value
     * for every declared field rather than rebuilding any of them.</p>
     *
     * <p>Trade-offs: a freshly allocated map is returned on every call rather than a shared instance
     * being handed out. That costs one allocation per emitted band. In exchange the caller may write
     * into the result freely, which is the whole point of a template, and no two bands under composition
     * can see each other's values -- a hazard that would surface as a report line carrying the previous
     * line's date range rather than as any kind of failure.</p>
     *
     * @return a mutable map keyed by declared field name, holding the short name, the long name, the
     *     date-range label, the date joiner and the blank pad, and awaiting the two dates
     */
    public static Map<String, Object> nameHeaderTemplate() {
        Map<String, Object> band = new LinkedHashMap<>();
        band.put("REPT-SHORT-NAME", REPORT_SHORT_NAME);
        band.put("REPT-LONG-NAME", REPORT_LONG_NAME);
        band.put("REPT-DATE-HEADER", DATE_RANGE_LABEL);
        band.put(FILLER_L12, DATE_RANGE_JOINER);
        band.put(FIELD_LINE_PAD, BLANK_CONTENT);
        return band;
    }

    /**
     * Builds a value map for the detail band holding every invariant field it declares.
     *
     * <p>Assumptions: the caller completes the returned map with all eight varying items -- the
     * transaction identifier, the account identifier, the type code and description, the category code
     * and description, the source and the rendered amount. The nine entries seeded here are the eight
     * {@code FILLER} literals of lines 17 to 31 of {@code app/cpy/CVTRA07Y.cpy} plus the trailing pad,
     * and none of them may be omitted or blanked: four are single-byte separators between columns, two
     * are the wider blank runs of four and two bytes that open and close the amount column, and two are
     * the hyphen joiners that make a code and its description read as a single printed unit.</p>
     *
     * <p>Assumptions: the category code is seeded by nobody and must be supplied as an integral number
     * or as digit text, not as a rendered string, because its field is declared unsigned display and the
     * codec zero-fills it to four positions. The amount, in contrast, must arrive already rendered by
     * {@code CobolEditMask}, because an edit mask is not a storage regime.</p>
     *
     * @return a mutable map keyed by declared field name, holding the eight literal separators and
     *     joiners and the blank pad, and awaiting the eight varying items
     */
    public static Map<String, Object> detailTemplate() {
        Map<String, Object> band = new LinkedHashMap<>();
        band.put(FILLER_L17, BLANK_CONTENT);
        band.put(FILLER_L19, BLANK_CONTENT);
        band.put(FILLER_L21, CODE_DESCRIPTION_JOINER);
        band.put(FILLER_L23, BLANK_CONTENT);
        band.put(FILLER_L25, CODE_DESCRIPTION_JOINER);
        band.put(FILLER_L27, BLANK_CONTENT);
        band.put(FILLER_L29, BLANK_CONTENT);
        band.put(FILLER_L31, BLANK_CONTENT);
        band.put(FIELD_LINE_PAD, BLANK_CONTENT);
        return band;
    }

    /**
     * Builds the complete value map for the column heading band, which has no varying content.
     *
     * <p>Assumptions: all seven copybook items of this band are {@code FILLER} carrying a literal, so
     * the returned map is complete and may be encoded as it stands. The single-byte item of line 44 is
     * seeded like any other; it is the byte that closes the headings at offset 97, where the amount
     * column opens, so dropping it from either the descriptor or this map would move the amount heading
     * out of its column.</p>
     *
     * @return a mutable map holding all seven heading literals and the blank pad, complete and ready to
     *     encode without further entries
     */
    public static Map<String, Object> columnHeaderRecord() {
        Map<String, Object> band = new LinkedHashMap<>();
        band.put(FILLER_L34, COLUMN_LABEL_TRANSACTION_ID);
        band.put(FILLER_L36, COLUMN_LABEL_ACCOUNT_ID);
        band.put(FILLER_L38, COLUMN_LABEL_TRANSACTION_TYPE);
        band.put(FILLER_L40, COLUMN_LABEL_TRAN_CATEGORY);
        band.put(FILLER_L42, COLUMN_LABEL_TRAN_SOURCE);
        band.put(FILLER_L44, BLANK_CONTENT);
        band.put(FILLER_L45, COLUMN_LABEL_AMOUNT);
        band.put(FIELD_LINE_PAD, BLANK_CONTENT);
        return band;
    }

    /**
     * Builds the complete value map for the rule line under the headings.
     *
     * <p>Assumptions: the band is one elementary item of 133 hyphens, so the returned map holds a single
     * entry and needs no pad. The literal is supplied at full width rather than left to the codec's
     * blank fill, because the codec fills the remainder of a short value with BLANKS and this item's
     * declared value is hyphens all the way across.</p>
     *
     * @return a mutable map holding the single 133-character rule line, complete and ready to encode
     */
    public static Map<String, Object> separatorRuleRecord() {
        Map<String, Object> band = new LinkedHashMap<>();
        band.put(FIELD_SEPARATOR_RULE, SEPARATOR_RULE);
        return band;
    }

    /**
     * Builds a value map for the page total band holding its label, its dot leader and its pad.
     *
     * <p>Assumptions: the caller completes the returned map with {@link #FIELD_REPT_PAGE_TOTAL}, already
     * rendered to 15 characters. The 86-character leader seeded here is this band's own and is two
     * characters longer than the card-break band's, which is what keeps both amounts in the same
     * column.</p>
     *
     * @return a mutable map holding the page total label, its 86-character dot leader and the blank pad,
     *     and awaiting the rendered total
     */
    public static Map<String, Object> pageTotalsTemplate() {
        Map<String, Object> band = new LinkedHashMap<>();
        band.put(FILLER_L51, PAGE_TOTAL_LABEL);
        band.put(FILLER_L53, PAGE_TOTAL_LEADER);
        band.put(FIELD_LINE_PAD, BLANK_CONTENT);
        return band;
    }

    /**
     * Builds a value map for the card-break total band holding its label, its dot leader and its pad.
     *
     * <p>Assumptions: the caller completes the returned map with {@link #FIELD_REPT_ACCOUNT_TOTAL},
     * already rendered to 15 characters. The 84-character leader seeded here is deliberately the short
     * one: its label is 13 characters where the other two labels are 11, and 13 plus 84 is the same 97
     * that 11 plus 86 reaches.</p>
     *
     * @return a mutable map holding the card-break total label, its 84-character dot leader and the
     *     blank pad, and awaiting the rendered total
     */
    public static Map<String, Object> accountTotalsTemplate() {
        Map<String, Object> band = new LinkedHashMap<>();
        band.put(FILLER_L57, ACCOUNT_TOTAL_LABEL);
        band.put(FILLER_L59, ACCOUNT_TOTAL_LEADER);
        band.put(FIELD_LINE_PAD, BLANK_CONTENT);
        return band;
    }

    /**
     * Builds a value map for the grand total band holding its label, its dot leader and its pad.
     *
     * <p>Assumptions: the caller completes the returned map with {@link #FIELD_REPT_GRAND_TOTAL},
     * already rendered to 15 characters. This band's label and leader pair matches the page total
     * band's at 11 and 86, which follows from their two labels being declared the same width rather than
     * from any rule shared between them.</p>
     *
     * @return a mutable map holding the grand total label, its 86-character dot leader and the blank
     *     pad, and awaiting the rendered total
     */
    public static Map<String, Object> grandTotalsTemplate() {
        Map<String, Object> band = new LinkedHashMap<>();
        band.put(FILLER_L63, GRAND_TOTAL_LABEL);
        band.put(FILLER_L65, GRAND_TOTAL_LEADER);
        band.put(FIELD_LINE_PAD, BLANK_CONTENT);
        return band;
    }
}
