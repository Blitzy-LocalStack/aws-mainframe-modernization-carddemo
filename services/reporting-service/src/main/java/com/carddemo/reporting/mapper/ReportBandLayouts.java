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
 * instantiated, so the type itself accepts no parameter and yields no value; every member below carries
 * its own at-clauses. The type does raise without any member being called: each descriptor constant
 * chains {@link CopybookLayout.RecordSpec#validateGeometry()} in its initialiser, so a mis-stated
 * offset surfaces at class load as a {@link CopybookLayout.LayoutException} wrapped in an
 * {@link ExceptionInInitializerError} rather than as a wrong report much later. That failure belongs to
 * the class rather than to any member, which is why it is stated here.</p>
 *
 * <h2>133 is a declared record length and never a sum of field widths</h2>
 *
 * <p>Assumptions: the record length is a property of the data set and is anchored three independent
 * ways -- the {@code PIC X(133)} hyphen rule at line 48 of {@code app/cpy/CVTRA07Y.cpy}, the
 * {@code PIC X(133)} output record at line 85 of {@code app/cbl/CBTRN03C.cbl}, and the logical record
 * length at line 78 of {@code app/jcl/TRANREPT.jcl}. Summing a band's declared widths produces a
 * different number: only the separator rule reaches 133 on its own, the two 114-byte bands and the
 * 115-byte name header fall short, and each of the three total bands sums to 112. A reader who took the
 * detail band's 114 for the record length would land every subsequent band nineteen bytes short.</p>
 *
 * <h2>Six of the seven bands are short and carry an explicit pad field</h2>
 *
 * <p>Assumptions: {@link CopybookLayout.RecordSpec#validateGeometry()} is fail-closed on two invariants
 * -- fields contiguous from offset zero, and lengths summing to the declared record length exactly --
 * so a band described short is rejected outright and leaving the shortfall as an undescribed gap is not
 * available. Each of the six short bands therefore declares one trailing blanks-only field named by
 * {@link #FIELD_LINE_PAD}: eighteen bytes on the name header, nineteen on each 114-byte band and
 * twenty-one on each total band. The separator rule needs none.</p>
 *
 * <p>Alternatives Considered: declaring each band at its own summed length and letting a caller pad.
 * Rejected, because the record length would then differ band to band while the data set has exactly
 * one, and the padding decision would move to the call site that this package's charter confines such
 * decisions away from.</p>
 *
 * <h2>Every value must be supplied, because these bands are not registered</h2>
 *
 * <p>Assumptions: {@code FixedWidthCodec} rebuilds omitted padding as blanks only for a layout that
 * {@code CopybookLayout} registers, requiring the supplied descriptor to be the very instance the
 * registry holds; these seven are declared here and deliberately not registered, so none qualifies. The
 * value map handed to {@link FixedWidthCodec#encodeRecord(Map, CopybookLayout.RecordSpec)} must
 * therefore contain an entry for every declared field including the pad, or the encode raises naming
 * the missing field. The seven template methods exist for that reason, each returning a map already
 * holding every invariant field so the mapper supplies only what varies line to line.</p>
 *
 * <h2>All 22 FILLER items in this copybook are content, not padding</h2>
 *
 * <p>Assumptions: {@code app/cpy/CVTRA07Y.cpy} declares 22 {@code FILLER} items and every one carries a
 * {@code VALUE} clause, four of them {@code VALUE ALL} -- the hyphen rule at line 48 and the three dot
 * leaders at lines 53, 59 and 65. Not one may be blanked, because in each case the literal IS the
 * printed data: column headings, the joiners inside a code-and-description pair, the dot leaders
 * carrying the eye across to a total, and the rule under the headings. Each is therefore a real field
 * below, seeded with its literal by the matching template method.</p>
 *
 * <p>Assumptions: the baseline never restates those literals because
 * {@code app/cbl/CBTRN03C.cbl} line 362 opens its detail paragraph with
 * {@code INITIALIZE TRANSACTION-DETAIL-REPORT}, and the language's {@code INITIALIZE} verb skips
 * {@code FILLER}, so those bytes survive untouched from one detail line to the next in a record area
 * that persists. A Java band is assembled into a freshly allocated array with no previous content to
 * survive, which is the whole reason the descriptors below carry {@code FILLER} at all.</p>
 *
 * <p>Alternatives Considered: naming all 22 plainly {@code FILLER} as the copybook does. Rejected on a
 * mechanical ground rather than a stylistic one --
 * {@link FixedWidthCodec#encodeRecord(Map, CopybookLayout.RecordSpec)} resolves values BY FIELD NAME
 * and a map admits one value per key, so seven identically named fields in the detail band would leave
 * six unreachable and the joiner bytes would collide with the space separators. Each is instead named
 * for the copybook line that declares it, so the detail band holds {@code FILLER-L17} through
 * {@code FILLER-L31}.</p>
 *
 * <p>Assumptions: that is not a rename and AAP Rule T1 is not bent by it. {@code FILLER} is the
 * language's keyword for an item that has NO name, so no declared name is being changed; a
 * distinguishing suffix is supplied where the copybook supplied none, exactly as the baseline itself
 * does when it names a padding item {@code SEC-USR-FILLER} at line 23 of
 * {@code app/cpy/CSUSR01Y.cpy}. Every genuinely named field below is carried across character for
 * character.</p>
 *
 * <h2>An edit mask is not a storage regime</h2>
 *
 * <p>Assumptions: {@code CopybookLayout.Kind} offers five regimes and none is an edit mask, while the
 * four amount items here are declared {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} at line 30 and
 * {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} at lines 54, 60 and 66 -- display templates rather than storage. The seam
 * is therefore two-stage and one-directional: {@code CobolEditMask} renders the amount into a
 * 15-character {@link String} first, and this class declares the receiving field as {@code Kind.TEXT}
 * of that width so the codec merely places it.</p>
 *
 * <p>Assumptions: every {@link CopybookLayout.FieldSpec#start()} below is ZERO-based, the convention
 * that record descriptor uses throughout, and the one-based column numbers in the commentary are stated
 * as such wherever used. The conversion runs only one way, {@code zeroBased = oneBased - 1}; applied
 * backwards it shifts a whole band by one byte, and a band shifted by one byte still prints readable
 * text, so nothing would raise.</p>
 *
 * <h2>No field of any band is sensitive, and none is a normalised timestamp</h2>
 *
 * <p>Assumptions: every descriptor leaves {@link CopybookLayout.FieldSpec#sensitive()} and
 * {@link CopybookLayout.FieldSpec#normalizeTs()} false, stated once here rather than repeated at each
 * field. No primary account number appears anywhere in {@code app/cpy/CVTRA07Y.cpy} -- the detail band
 * carries a transaction identifier, an account identifier, a type code and description, a category code
 * and description, a source and an amount, and nothing else -- and narrowing what this report prints
 * would break the byte comparison the parity oracle performs. No band carries a timestamp item either,
 * so there is nothing for that comparison to blank.</p>
 *
 * <h2>These bands have no retrieval key, and every one of them says so</h2>
 *
 * <p>Assumptions: line 78 of {@code app/jcl/TRANREPT.jcl} declares the report data set with a record
 * format of {@code FB} and no key operand at all, which is what a sequential print file is. Nothing
 * reads a report band by key, so every band below is declared through
 * {@link CopybookLayout.RecordSpec#keyless(String, int, java.util.List)} and carries
 * {@link CopybookLayout.RecordSpec#NO_RETRIEVAL_KEY} as its key length.</p>
 *
 * <p>Assumptions: two properties of the source copybook matter to anyone checking a transcription
 * against it. Its clause order is {@code PIC} then {@code VALUE} throughout, whereas
 * {@code app/cbl/CBSTM03A.CBL} writes the two the other way round. And twelve of its literals are
 * CONTINUED onto the following line, so reading such a declaration a line at a time yields a field with
 * a width and no value; every literal below was taken from the rejoined form and verified against the
 * file's bytes, which is how the three whitespace-bearing ones kept their spaces.</p>
 *
 * <h2>Why these descriptors are declared here</h2>
 *
 * <p>Alternatives Considered: extending {@code CopybookLayout}'s registry with these seven. Rejected
 * because that registry describes records READ from and WRITTEN to keyed data sets and shared across
 * bounded contexts, whereas a report band is an OUTPUT layout belonging to the one module that prints
 * it. Registering them would also change their behaviour rather than merely their location, the
 * registry's membership being exactly what the codec's padding test consults.
 * {@code CopybookLayout.FieldSpec} and {@code RecordSpec} are public so a consumer can declare its own
 * descriptors, which is what this class does.</p>
 *
 * <p>Assumptions: declaring them here rather than at each call site is the half of the decision that is
 * not optional. The house convention at lines 540 to 542 of {@code tests/README.md} requires that a
 * record layout never be duplicated and be kept single-sourced; the COBOL side honours it through one
 * copybook include path, and the Java side through one declaration per band that every emitter
 * reads.</p>
 *
 * <p>Alternatives Considered: merging this holder with {@code StatementBandLayouts}. Rejected because
 * their padding obligations are opposite rather than merely different -- six of the seven bands here
 * fall short of 133 and must be blank-padded, whereas every statement band is natively exactly its
 * declared width and must not be padded at all. A single holder would invite one shared padding helper,
 * and a helper correct for one artifact would be wrong for the other.</p>
 *
 * <p>Trade-offs: the cost of everything above is bulk -- 45 field descriptors and 37 name constants to
 * print seven lines of a report, where a shorter version building each band from inline literals at the
 * point of emission would compile. Verbosity in one file is accepted for two properties worth more than
 * brevity here: every offset and literal is stated once, so a call site emits a byte-exact band holding
 * no layout knowledge of its own and two call sites cannot disagree; and each band is proven at class
 * load, so a wrong offset stops the service starting instead of printing a report whose columns have
 * quietly moved.</p>
 *
 * @see CopybookLayout
 * @see CobolEditMask
 */
public final class ReportBandLayouts {

    // Assumptions: 133 is the DECLARED record length of the report data set, established three
    //     ways as the class documentation sets out -- line 48 of app/cpy/CVTRA07Y.cpy, lines 85 and
    //     133 of app/cbl/CBTRN03C.cbl, and line 78 of app/jcl/TRANREPT.jcl. It is deliberately NOT
    //     the sum of any band's field widths; those sums are 115, 114, 114, 133, 112, 112 and 112.
    public static final int REPORT_RECORD_LENGTH = 133;

    // Assumptions: 97 is the zero-based offset at which the amount column opens in all four
    //     bands that print money, which is one-based columns 98 to 112 once the 15-character mask is
    //     placed. It is a shared derived fact rather than a free choice, so it is named once and
    //     every band below is checked against it: 11 + 86 on the page total, 13 + 84 on the account
    //     total, 11 + 86 on the grand total, and 97 bytes of preceding items on the detail band.
    public static final int AMOUNT_COLUMN_OFFSET = 97;

    // Assumptions: 11, 13 and 11 are the declared widths of the three total labels, at lines 51,
    //     57 and 63 of app/cpy/CVTRA07Y.cpy. They are three constants and not one because the page
    //     and grand labels agreeing at 11 is a coincidence of their text lengths, not a shared rule,
    //     and a single constant would let an edit to one label silently move the other two.
    public static final int PAGE_TOTAL_LABEL_WIDTH = 11;

    // Assumptions: 13 at line 57 of app/cpy/CVTRA07Y.cpy, the widest of the three labels by two
    //     bytes. Those two bytes are exactly what the shorter dot leader beside it gives back, which
    //     is the arithmetic recorded on ACCOUNT_TOTAL_LEADER_WIDTH below.
    public static final int ACCOUNT_TOTAL_LABEL_WIDTH = 13;

    // Assumptions: 11 at line 63 of app/cpy/CVTRA07Y.cpy. Declared separately from the page-total
    //     label width for the reason recorded there.
    public static final int GRAND_TOTAL_LABEL_WIDTH = 11;

    // Assumptions: 86, 84 and 86 are the declared widths of the three dot leaders, at lines 53,
    //     59 and 65 of app/cpy/CVTRA07Y.cpy, and they MUST NOT be unified to one width. The three
    //     total bands all place their 15-character amount mask in the same span, one-based columns 98
    //     to 112, and the only reason that is possible is that each leader compensates for its own
    //     label: 11 + 86 = 97, 13 + 84 = 97 and 11 + 86 = 97. Collapsing the three to a single 86
    //     would put the account total's label and leader at 13 + 86 = 99, moving that one mask two
    //     columns right of the other three and breaking the column the report is read down.
    public static final int PAGE_TOTAL_LEADER_WIDTH = 86;

    // Assumptions: 84 at line 59 of app/cpy/CVTRA07Y.cpy, two bytes shorter than its two
    //     siblings because its label is two bytes longer. See PAGE_TOTAL_LEADER_WIDTH for the full
    //     arithmetic and for what unifying the three costs.
    public static final int ACCOUNT_TOTAL_LEADER_WIDTH = 84;

    // Assumptions: 86 at line 65 of app/cpy/CVTRA07Y.cpy. Declared separately from the
    //     page-total leader width for the reason recorded there.
    public static final int GRAND_TOTAL_LEADER_WIDTH = 86;

    // Assumptions: carried character for character from lines 5 and 6 of app/cpy/CVTRA07Y.cpy,
    //     where it is the VALUE of a PIC X(38) item. AAP Rule T8 (user-visible strings are verbatim)
    //     governs every literal in this block: none is trimmed, re-cased or re-spaced. The literal is
    //     eight characters in a 38-byte field, and the codec supplies the remaining 30 blanks.
    public static final String REPORT_SHORT_NAME = "DALYREPT";

    // Assumptions: lines 7 and 8 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(41) item.
    public static final String REPORT_LONG_NAME = "Daily Transaction Report";

    // Assumptions: lines 9 and 10 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(12) item, and it
    //     ends in a SPACE that is part of the literal rather than an artefact of the field width. The
    //     twelve characters are the eleven of the visible words plus that trailing space, so the
    //     literal exactly fills its field and the codec adds nothing. Trimming it would close up the
    //     gap before the start date that the report prints.
    public static final String DATE_RANGE_LABEL = "Date Range: ";

    // Assumptions: line 12 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(04) FILLER, and it
    //     carries a LEADING and a TRAILING space around the two visible letters. All four characters
    //     are the literal, so the field is exactly filled. Losing either space would run the joiner
    //     into one of the two dates it separates.
    public static final String DATE_RANGE_JOINER = " to ";

    // Assumptions: lines 21 and 25 of app/cpy/CVTRA07Y.cpy each declare a PIC X(01) FILLER whose
    //     VALUE is this one byte, and it is the ASCII hyphen-minus that renders a code and its
    //     description as a single printed unit. It is DATA and not padding, and it is NOT a minus
    //     sign: the sign position of the amount on line 30 belongs to that item's own edit mask. The
    //     two occurrences share one constant because they are the same joiner used twice for the same
    //     purpose, once after the type code and once after the category code.
    public static final String CODE_DESCRIPTION_JOINER = "-";

    // Assumptions: lines 34 and 35 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(17) FILLER. The
    //     trailing blanks that separate this heading from the next are the field width doing its
    //     work, not part of the literal, so the literal is the visible words alone.
    public static final String COLUMN_LABEL_TRANSACTION_ID = "Transaction ID";

    // Assumptions: lines 36 and 37 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(12) FILLER.
    public static final String COLUMN_LABEL_ACCOUNT_ID = "Account ID";

    // Assumptions: lines 38 and 39 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(19) FILLER. The
    //     19-byte field spans the type code, the joiner and the type description beneath it.
    public static final String COLUMN_LABEL_TRANSACTION_TYPE = "Transaction Type";

    // Assumptions: lines 40 and 41 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(35) FILLER --
    //     the widest heading field in the band, spanning the category code, its joiner and its
    //     29-byte description beneath it.
    public static final String COLUMN_LABEL_TRAN_CATEGORY = "Tran Category";

    // Assumptions: lines 42 and 43 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(14) FILLER.
    public static final String COLUMN_LABEL_TRAN_SOURCE = "Tran Source";

    // Assumptions: lines 45 and 46 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(16) FILLER, and
    //     the EIGHT LEADING SPACES are part of the literal. Fourteen characters of literal sit in a
    //     16-byte field starting at offset 98, which puts the visible word over the right-hand end of
    //     the amount column beneath it, where a right-justified money value actually prints.
    //     Left-trimming it would move the heading to the column's left edge, away from its data.
    public static final String COLUMN_LABEL_AMOUNT = "        Amount";

    // Assumptions: lines 51 and 52 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(11) FILLER.
    public static final String PAGE_TOTAL_LABEL = "Page Total";

    // Assumptions: lines 57 and 58 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(13) FILLER.
    //     Note that the band this label heads breaks on CARD NUMBER and not on account: line 181 of
    //     app/cbl/CBTRN03C.cbl compares WS-CURR-CARD-NUM, declared PIC X(16) at line 137 of that
    //     program, against the transaction's card number. The label is carried verbatim regardless,
    //     because AAP Rule T8 (user-visible strings are verbatim) admits no correction of wording.
    public static final String ACCOUNT_TOTAL_LABEL = "Account Total";

    // Assumptions: lines 63 and 64 of app/cpy/CVTRA07Y.cpy, the VALUE of a PIC X(11) FILLER.
    public static final String GRAND_TOTAL_LABEL = "Grand Total";

    // Assumptions: line 48 of app/cpy/CVTRA07Y.cpy declares an elementary level-01 item of
    //     PIC X(133) whose VALUE ALL is a hyphen, so the rule line is the only band that is natively
    //     the declared record length. It is built from the record length rather than written out as
    //     133 characters so the two cannot drift apart. It is deliberately NOT derived from
    //     CODE_DESCRIPTION_JOINER even though both are a hyphen: a rule line under the headings and a
    //     joiner inside a code-and-description pair are unrelated decisions in the source, and tying
    //     them together would propagate a change to one into the other.
    public static final String SEPARATOR_RULE = "-".repeat(REPORT_RECORD_LENGTH);

    // Assumptions: the fifteen constants below are the names the value map is KEYED by, and they
    //     are public because the codec resolves values by field name, so an emitter that wrote the
    //     name as a bare string literal would be restating a layout detail this class exists to hold.
    //     Every one is the field name exactly as app/cpy/CVTRA07Y.cpy declares it, per AAP Rule T1
    //     (Copybook is normative). Only the fields whose content VARIES line to line are published;
    //     the invariant ones are seeded by the template methods and are never keyed by a caller.
    public static final String FIELD_REPT_START_DATE = "REPT-START-DATE";

    // Assumptions: line 13 of app/cpy/CVTRA07Y.cpy. The start and end dates are the two variable
    //     items of the name header, and both are PIC X(10) holding an already ISO-ordered date, so
    //     their bytes are placed as text with no reformatting.
    public static final String FIELD_REPT_END_DATE = "REPT-END-DATE";

    // Assumptions: line 16 of app/cpy/CVTRA07Y.cpy, PIC X(16).
    public static final String FIELD_TRAN_REPORT_TRANS_ID = "TRAN-REPORT-TRANS-ID";

    // Assumptions: line 18 of app/cpy/CVTRA07Y.cpy, and it is ALPHANUMERIC PIC X(11) where the
    //     category code four items later is NUMERIC. That asymmetry is in the source and it is
    //     load-bearing: an alphanumeric item is placed left-justified and blank-padded, so an account
    //     identifier shorter than eleven characters prints flush left with trailing blanks and is
    //     NOT zero-filled. Declaring this one numeric to make the two consistent would zero-pad it
    //     and change the printed bytes.
    public static final String FIELD_TRAN_REPORT_ACCOUNT_ID = "TRAN-REPORT-ACCOUNT-ID";

    // Assumptions: line 20 of app/cpy/CVTRA07Y.cpy, PIC X(02).
    public static final String FIELD_TRAN_REPORT_TYPE_CD = "TRAN-REPORT-TYPE-CD";

    // Assumptions: line 22 of app/cpy/CVTRA07Y.cpy, PIC X(15).
    public static final String FIELD_TRAN_REPORT_TYPE_DESC = "TRAN-REPORT-TYPE-DESC";

    // Assumptions: line 24 of app/cpy/CVTRA07Y.cpy, PIC 9(04) -- the one NUMERIC item in the
    //     detail band, which is why its descriptor uses the unsigned display regime rather than text.
    //     The regime choice is argued at the descriptor itself.
    public static final String FIELD_TRAN_REPORT_CAT_CD = "TRAN-REPORT-CAT-CD";

    // Assumptions: line 26 of app/cpy/CVTRA07Y.cpy, PIC X(29) -- the widest description in the
    //     band.
    public static final String FIELD_TRAN_REPORT_CAT_DESC = "TRAN-REPORT-CAT-DESC";

    // Assumptions: line 28 of app/cpy/CVTRA07Y.cpy, PIC X(10).
    public static final String FIELD_TRAN_REPORT_SOURCE = "TRAN-REPORT-SOURCE";

    // Assumptions: line 30 of app/cpy/CVTRA07Y.cpy, declared PIC -ZZZ,ZZZ,ZZZ.ZZ. The value
    //     keyed under this name must already be the rendered 15-character string, because an edit
    //     mask is a display template and not a storage regime, as the class documentation sets out.
    public static final String FIELD_TRAN_REPORT_AMT = "TRAN-REPORT-AMT";

    // Assumptions: line 54 of app/cpy/CVTRA07Y.cpy, declared PIC +ZZZ,ZZZ,ZZZ.ZZ. The three total
    //     items keep their own names rather than sharing one, because a single name across three
    //     bands would make it impossible to tell from a value map which total was being composed.
    public static final String FIELD_REPT_PAGE_TOTAL = "REPT-PAGE-TOTAL";

    // Assumptions: line 60 of app/cpy/CVTRA07Y.cpy, declared PIC +ZZZ,ZZZ,ZZZ.ZZ.
    public static final String FIELD_REPT_ACCOUNT_TOTAL = "REPT-ACCOUNT-TOTAL";

    // Assumptions: line 66 of app/cpy/CVTRA07Y.cpy, declared PIC +ZZZ,ZZZ,ZZZ.ZZ.
    public static final String FIELD_REPT_GRAND_TOTAL = "REPT-GRAND-TOTAL";

    // Assumptions: line 48 of app/cpy/CVTRA07Y.cpy declares the separator rule as an ELEMENTARY
    //     level-01 item with its own PIC and no subordinates, so the band has exactly one field and
    //     that field's name is the band's name. The single-field descriptor is the faithful reading;
    //     modelling it as a group with one child would invent a level the copybook does not have.
    public static final String FIELD_SEPARATOR_RULE = "TRANSACTION-HEADER-2";

    // Assumptions: this field is NOT in app/cpy/CVTRA07Y.cpy. It exists because
    //     RecordSpec.validateGeometry() is fail-closed on an exact sum, so the shortfall between a
    //     band's declared widths and the 133-byte record cannot be left as a gap. The name is
    //     deliberately unlike any copybook name so that a reader can see at a glance that it is a
    //     target-side construct, and it is published so a test can assert on it directly.
    public static final String FIELD_LINE_PAD = "REPORT-LINE-PAD";

    // Assumptions: an empty value in a text field is expanded by the codec to a full field of
    //     blanks, because it places what it is given from the left and blank-fills the remainder. One
    //     constant therefore serves both populations that need blanks: the seven FILLER items declared
    //     VALUE SPACES in app/cpy/CVTRA07Y.cpy, at its lines 17, 19, 23, 27, 29, 31 and 44, and the
    //     trailing pad field of each short band. Writing out the exact number of spaces at each site
    //     was rejected because the count would then be stated twice per field, once as the width and
    //     once as the literal, and the two could disagree.
    private static final String BLANK_CONTENT = "";

    // Assumptions: line 53 of app/cpy/CVTRA07Y.cpy declares VALUE ALL '.' over 86 bytes, so the
    //     leader is built from its own width constant rather than written out. The three leaders are
    //     three separate constants for the reason recorded on PAGE_TOTAL_LEADER_WIDTH: they are 86,
    //     84 and 86, and one shared leader would move the account total out of its column.
    private static final String PAGE_TOTAL_LEADER = ".".repeat(PAGE_TOTAL_LEADER_WIDTH);

    // Assumptions: line 59 of app/cpy/CVTRA07Y.cpy declares VALUE ALL '.' over 84 bytes -- two
    //     fewer than its siblings, giving back exactly the two bytes its longer label consumed.
    private static final String ACCOUNT_TOTAL_LEADER = ".".repeat(ACCOUNT_TOTAL_LEADER_WIDTH);

    // Assumptions: line 65 of app/cpy/CVTRA07Y.cpy declares VALUE ALL '.' over 86 bytes.
    private static final String GRAND_TOTAL_LEADER = ".".repeat(GRAND_TOTAL_LEADER_WIDTH);

    // Assumptions: the 22 constants below name the copybook's 22 FILLER items, each after the
    //     line that declares it, for the reason argued in the class documentation: the codec resolves
    //     values by field name and a map holds one value per key, so seven items all named FILLER
    //     would leave six of them unreachable. Each is a CONSTANT rather than a literal written twice
    //     because every one is used at two sites, in a descriptor and in a template, and a typo
    //     between the two would surface only as a codec failure at run time instead of as a
    //     compilation error. The line-derived suffix keeps each item traceable to its declaration.
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
     */
    public static final CopybookLayout.RecordSpec REPORT_NAME_HEADER = CopybookLayout.RecordSpec.keyless(
            "REPORT-NAME-HEADER", REPORT_RECORD_LENGTH,
            List.of(
                    text("REPT-SHORT-NAME", 0, 38),
                    text("REPT-LONG-NAME", 38, 41),
                    text("REPT-DATE-HEADER", 79, 12),
                    text(FIELD_REPT_START_DATE, 91, 10),
                    text(FILLER_L12, 101, 4),
                    text(FIELD_REPT_END_DATE, 105, 10),

                    // Assumptions: the six items above sum to 115, so 18 bytes are owed to the
                    //     declared 133. validateGeometry() rejects an undescribed shortfall, so the
                    //     pad is declared rather than left implicit. Its content is blanks.
                    text(FIELD_LINE_PAD, 115, 18))).validateGeometry();

    /**
     * The transaction detail band, transcribed from lines 15 to 31 of {@code app/cpy/CVTRA07Y.cpy}.
     *
     * <p>Assumptions: sixteen items are declared in the copybook and eight of them are {@code FILLER}
     * carrying a literal, which is why the descriptor is twice as long as the eight values a caller
     * actually varies. Two of those literals are the hyphen joiners at lines 21 and 25 that render a
     * code and its description as {@code NN-Description}; they are printed data, not spacing.</p>
     *
     * @see #REPORT_PAGE_TOTALS
     */
    public static final CopybookLayout.RecordSpec TRANSACTION_DETAIL_REPORT =
            CopybookLayout.RecordSpec.keyless(
                    "TRANSACTION-DETAIL-REPORT", REPORT_RECORD_LENGTH,
                    List.of(
                            text(FIELD_TRAN_REPORT_TRANS_ID, 0, 16),
                            text(FILLER_L17, 16, 1),
                            text(FIELD_TRAN_REPORT_ACCOUNT_ID, 17, 11),
                            text(FILLER_L19, 28, 1),
                            text(FIELD_TRAN_REPORT_TYPE_CD, 29, 2),

                            // Assumptions: line 21 declares a PIC X(01) FILLER whose VALUE is a
                            //     hyphen, and it is the joiner that binds the type code to the
                            //     description that follows it. It is DATA and must be emitted, and it
                            //     is NOT a sign position -- the amount's sign belongs to that item's
                            //     own edit mask on line 30. The reference program never restates it
                            //     because line 362 of app/cbl/CBTRN03C.cbl re-initialises the band
                            //     and the language's INITIALIZE verb skips FILLER, leaving the byte
                            //     in place; a freshly allocated Java array has no such survivor.
                            text(FILLER_L21, 31, 1),
                            text(FIELD_TRAN_REPORT_TYPE_DESC, 32, 15),
                            text(FILLER_L23, 47, 1),

                            // Alternatives Considered: this is the only NUMERIC item in the band,
                            //     declared PIC 9(04) on line 24 where its neighbours are PIC X, and
                            //     the unsigned display regime is chosen over rendering the digits
                            //     upstream and declaring the field as text. The regimes are not
                            //     interchangeable here. Unsigned display right-justifies and
                            //     ZERO-fills to the declared width, so category 5 prints as 0005,
                            //     which is what PIC 9(04) means. Text left-justifies and BLANK-fills,
                            //     so the same value would print as a 5 followed by three blanks and
                            //     the joiner after it would no longer sit against the description.
                            //     Choosing this regime also keeps the obligation off the caller: the
                            //     value may be supplied as an integral number or as digit text, and
                            //     the codec pads it either way. The consequence accepted is that the
                            //     caller must not hand this field a rendered, blank-padded string.
                            uint(FIELD_TRAN_REPORT_CAT_CD, 48, 4),

                            // Assumptions: line 25 declares the second hyphen joiner, binding the
                            //     category code to its description exactly as line 21 binds the type
                            //     code. The same reasoning recorded on FILLER_L21 applies unchanged.
                            text(FILLER_L25, 52, 1),
                            text(FIELD_TRAN_REPORT_CAT_DESC, 53, 29),
                            text(FILLER_L27, 82, 1),
                            text(FIELD_TRAN_REPORT_SOURCE, 83, 10),
                            text(FILLER_L29, 93, 4),

                            // Assumptions: the amount is declared with an edit mask on line 30,
                            //     and an edit mask is not one of the five storage regimes the record
                            //     descriptor offers. It is therefore declared as text of the mask's
                            //     own width, taken from CobolEditMask so that the 15 is stated once
                            //     in this package, and the value handed to the codec is the already
                            //     rendered string. Its offset of 97 is the shared amount column.
                            text(FIELD_TRAN_REPORT_AMT, AMOUNT_COLUMN_OFFSET,
                                    CobolEditMask.REPORT_AMOUNT_WIDTH),
                            text(FILLER_L31, 112, 2),

                            // Assumptions: the sixteen copybook items sum to 114, so 19 bytes are
                            //     owed to the declared 133. That 114 is also the number a reader
                            //     arrives at who mistakes this band's width for the record length.
                            text(FIELD_LINE_PAD, 114, 19))).validateGeometry();

    /**
     * The column heading band, transcribed from lines 33 to 46 of {@code app/cpy/CVTRA07Y.cpy}.
     *
     * <p>Assumptions: the reference program emits this band third in its heading sequence, after the
     * title band and after a wholly blank line, and follows it with the rule line. That blank line is
     * NOT part of {@code app/cpy/CVTRA07Y.cpy} at all: it is declared as {@code PIC X(133) VALUE SPACES}
     * at line 133 of {@code app/cbl/CBTRN03C.cbl} and moved into the output record at line 329 of that
     * program. This class therefore declares no descriptor for it, and the emission sequence belongs to
     * the mapper. It is recorded here because a reader comparing this band's position against a report
     * listing needs to know why there is a line between the title and the headings.</p>
     */
    public static final CopybookLayout.RecordSpec TRANSACTION_HEADER_1 = CopybookLayout.RecordSpec.keyless(
            "TRANSACTION-HEADER-1", REPORT_RECORD_LENGTH,
            List.of(
                    text(FILLER_L34, 0, 17),
                    text(FILLER_L36, 17, 12),
                    text(FILLER_L38, 29, 19),
                    text(FILLER_L40, 48, 35),
                    text(FILLER_L42, 83, 14),

                    // Assumptions: line 44 declares FILLER PIC X with NO parenthesised count,
                    //     which is width ONE and not an unspecified width. It is easy to read past
                    //     and it cannot be dropped: this single byte closes the preceding headings at
                    //     offset 97, exactly where the amount column opens in the other three bands,
                    //     so omitting it would pull the amount heading one byte left of its column.
                    text(FILLER_L44, 97, 1),
                    text(FILLER_L45, 98, 16),

                    // Assumptions: the seven copybook items sum to 114, so 19 bytes are owed to
                    //     the declared 133, exactly as on the detail band above.
                    text(FIELD_LINE_PAD, 114, 19))).validateGeometry();

    /**
     * The rule line under the headings, transcribed from line 48 of {@code app/cpy/CVTRA07Y.cpy}.
     *
     * <p>Assumptions: the reference program emits this band at THREE separate sites, lines 300, 312 and
     * 337 of {@code app/cbl/CBTRN03C.cbl}, all funnelling into the one physical write at line 345. That
     * repetition is part of the byte stream the parity comparison reads, so an emitter must reproduce
     * every occurrence rather than collapsing them into one.</p>
     */
    public static final CopybookLayout.RecordSpec TRANSACTION_HEADER_2 = CopybookLayout.RecordSpec.keyless(
            "TRANSACTION-HEADER-2", REPORT_RECORD_LENGTH,
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
    public static final CopybookLayout.RecordSpec REPORT_PAGE_TOTALS = CopybookLayout.RecordSpec.keyless(
            "REPORT-PAGE-TOTALS", REPORT_RECORD_LENGTH,
            List.of(
                    text(FILLER_L51, 0, PAGE_TOTAL_LABEL_WIDTH),
                    text(FILLER_L53, PAGE_TOTAL_LABEL_WIDTH, PAGE_TOTAL_LEADER_WIDTH),
                    text(FIELD_REPT_PAGE_TOTAL, AMOUNT_COLUMN_OFFSET,
                            CobolEditMask.REPORT_AMOUNT_WIDTH),

                    // Assumptions: 11 + 86 + 15 is 112, so 21 bytes are owed to the declared 133.
                    //     The same 21 is owed by both sibling total bands, which is a consequence of
                    //     all three sharing the amount column and the mask width, not a coincidence.
                    text(FIELD_LINE_PAD, 112, 21))).validateGeometry();

    /**
     * The card-break total band, transcribed from lines 56 to 60 of {@code app/cpy/CVTRA07Y.cpy}.
     *
     * <p>Assumptions: the label reads as an account total but the roll-up it closes breaks on CARD
     * NUMBER. Line 181 of {@code app/cbl/CBTRN03C.cbl} compares {@code WS-CURR-CARD-NUM}, declared
     * {@code PIC X(16)} at line 137 of that program, against the transaction's card number. The wording
     * is carried across unchanged under AAP Rule T8 (user-visible strings are verbatim); the divergence
     * between the label and the break key is recorded rather than resolved, and choosing the break key
     * belongs to the emitter and not to this class.</p>
     */
    public static final CopybookLayout.RecordSpec REPORT_ACCOUNT_TOTALS = CopybookLayout.RecordSpec.keyless(
            "REPORT-ACCOUNT-TOTALS", REPORT_RECORD_LENGTH,
            List.of(
                    text(FILLER_L57, 0, ACCOUNT_TOTAL_LABEL_WIDTH),
                    text(FILLER_L59, ACCOUNT_TOTAL_LABEL_WIDTH, ACCOUNT_TOTAL_LEADER_WIDTH),
                    text(FIELD_REPT_ACCOUNT_TOTAL, AMOUNT_COLUMN_OFFSET,
                            CobolEditMask.REPORT_AMOUNT_WIDTH),

                    // Assumptions: 13 + 84 + 15 is 112, the same 112 the other two total bands
                    //     reach from their own label and leader pairs, so the same 21 bytes are owed.
                    text(FIELD_LINE_PAD, 112, 21))).validateGeometry();

    /**
     * The grand total band, transcribed from lines 62 to 66 of {@code app/cpy/CVTRA07Y.cpy}.
     *
     * <p>Assumptions: an 11-byte label plus an 86-byte dot leader is 97, matching the page total band's
     * pair exactly. The two agreeing is a consequence of their two labels happening to be declared the
     * same width, not of a shared rule, which is why each keeps its own constants.</p>
     */
    public static final CopybookLayout.RecordSpec REPORT_GRAND_TOTALS = CopybookLayout.RecordSpec.keyless(
            "REPORT-GRAND-TOTALS", REPORT_RECORD_LENGTH,
            List.of(
                    text(FILLER_L63, 0, GRAND_TOTAL_LABEL_WIDTH),
                    text(FILLER_L65, GRAND_TOTAL_LABEL_WIDTH, GRAND_TOTAL_LEADER_WIDTH),
                    text(FIELD_REPT_GRAND_TOTAL, AMOUNT_COLUMN_OFFSET,
                            CobolEditMask.REPORT_AMOUNT_WIDTH),

                    // Assumptions: 11 + 86 + 15 is 112, so 21 bytes are owed to the declared 133.
                    text(FIELD_LINE_PAD, 112, 21))).validateGeometry();

    /**
     * Prevents instantiation of this static descriptor holder.
     *
     * <p>Alternatives Considered: an instantiable class, or one published as a shared instance, was
     * evaluated and rejected. Every member here is a constant or a pure function of nothing, so an
     * instance would advertise a lifecycle that does not exist and would offer a collaborator that no
     * test has any reason to substitute. Declaring the constructor private states that where the
     * language enforces it, and the class is final so no subclass can reopen the decision.</p>
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
