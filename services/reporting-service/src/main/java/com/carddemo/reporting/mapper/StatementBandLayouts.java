package com.carddemo.reporting.mapper;

import com.carddemo.common.codec.CopybookLayout;
import java.util.List;

/**
 * Declares the seventeen 80-character statement bands and the literals they carry.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class is the single declaration of the byte geometry of every band the plain-text
 * cardholder statement is assembled from, together with every literal those bands carry. It holds
 * seventeen {@link CopybookLayout.RecordSpec} descriptors, one per {@code ST-LINE} group declared
 * at lines 86 to 146 of {@code app/cbl/CBSTM03A.CBL}, and the label, heading, sentinel and rule
 * literals those groups declare through their {@code VALUE} clauses. Nothing here places a byte,
 * formats a number, reads a clock or performs input or output.</p>
 *
 * <p>{@code StatementTextMapper} is the only consumer. {@code StatementHtmlMapper} does not use
 * this class at all, because the HTML artifact is a separate 100-character record: it is declared
 * {@code 01 FD-HTMLFILE-REC PIC X(100).} at line 47 of {@code app/cbl/CBSTM03A.CBL}, corroborated
 * at line 94 of {@code app/jcl/CREASTMT.JCL} and again by the {@code PIC X(100)} items at line 149
 * and at lines 221 to 223 of the program, and it is emitted as a stream of fragments from one
 * reusable buffer rather than assembled from a band table. An HTML descriptor declared here would
 * therefore describe an artifact that no caller builds that way.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a static declaration holder that
 * cannot be instantiated, so the type itself accepts no parameter, yields no value and raises
 * nothing. The inapplicability is stated rather than passed over, because user-specified Rule 1
 * (Explainability) forbids at its line 39 a docstring that omits parameters, return values or
 * purpose, and a reader has to be able to tell a declared inapplicability from an oversight. Every
 * member below carries its own parameter, return and exception at-clauses.</p>
 *
 * <h2>Assumptions: 80 is declared, every band is natively 80, and nothing here is padded</h2>
 *
 * <p>The statement record length is 80 characters. It is declared
 * {@code 01 FD-STMTFILE-REC PIC X(80).} at line 45 of {@code app/cbl/CBSTM03A.CBL}, under the
 * {@code FD STMT-FILE.} at line 44, and corroborated by the data-set attributes
 * {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} at line 89 of {@code app/jcl/CREASTMT.JCL}.</p>
 *
 * <p><b>Every one of the seventeen bands sums to exactly 80 from its own declared pictures, so no
 * band here is padded and none may be.</b> That is the opposite of the 133-column daily
 * transaction report, where six of the seven bands of {@code app/cpy/CVTRA07Y.cpy} are short of
 * their declared length and every one of the six must be blank-padded out to it. This paragraph
 * exists to stop that generalisation being carried across, because the two obligations look like
 * one problem and are not: padding a statement band would overrun the 80-character record, while
 * declining to pad a report band would leave it short. A maintainer who reads only one of the two
 * descriptor holders must still be able to see both halves of the contrast, which is why it is
 * stated here in full rather than by reference.</p>
 *
 * <p>Assumptions: the same 80 also appears at lines 69 and 73 of {@code app/jcl/CREASTMT.JCL},
 * inside the two {@code DISP=(MOD,DELETE,DELETE)} stanzas of the step at line 66, and those two
 * occurrences carry no information. That step invokes a utility that writes no data, so the
 * attributes it declares govern nothing. The load-bearing occurrence is the one at line 89, in the
 * step at line 79 that runs the statement program itself.</p>
 *
 * <h2>Alternatives Considered: extending the shared registry instead of declaring here</h2>
 *
 * <p>Adding these seventeen descriptors to {@link CopybookLayout}'s own registry was evaluated and
 * rejected. That registry holds fourteen layouts in two labelled populations, and every one of the
 * fourteen is a data-set record: the eleven {@code BASE_MASTER} entries {@code ACCOUNT},
 * {@code CARD}, {@code CUSTOMER}, {@code XREF}, {@code DALYTRAN}, {@code TRAN}, {@code DISGROUP},
 * {@code TCATBAL}, {@code SECUSER}, {@code TRANCAT} and {@code TRANTYPE}, each transcribed from a
 * copybook that defines a persistent data set, and the three {@code DERIVED} entries {@code TRNX},
 * {@code REJECT} and {@code INTTRAN}, each built from a base master. A statement band is not a
 * record of any data set. It is a print band assembled into a line and written to a sequential
 * file, which is why none of the seventeen was ever a registry entry and why
 * {@link CopybookLayout.FieldSpec} and {@link CopybookLayout.RecordSpec} are public: so that a
 * caller can declare a layout the registry does not carry. Registering an output band would also
 * put it inside a table whose accessors report data-set provenance and parity-oracle round-trip
 * coverage, neither of which is a property a print band has.</p>
 *
 * <p>Declaring them once here rather than inline at each call site follows the convention this
 * repository already applies to itself. Lines 540 to 542 of {@code tests/README.md} resolve record
 * layouts through a single compiler include path and require that a layout is never duplicated but
 * kept single-sourced. A duplicated layout is not untidy but dangerous: two copies of one geometry
 * that disagree in a single width shift every column after it, and a band read one character out
 * of alignment still looks like text, so nothing raises.</p>
 *
 * <h2>Alternatives Considered: one descriptor holder for both artifacts</h2>
 *
 * <p>Merging this class with {@code ReportBandLayouts} was evaluated and rejected, and the ground
 * is structural rather than stylistic. The two artifacts stand in <em>opposite</em> relations to
 * their declared lengths, as set out above, so a single holder would present two contradictory
 * padding obligations behind one interface. The natural next step from such a holder -- one shared
 * padding helper -- is correct for neither artifact: applied to a report band it is mandatory, and
 * applied to a statement band it corrupts the record. Two types make the boundary a compile-time
 * fact rather than a comment a reader may skim past.</p>
 *
 * <h2>Assumptions: the codec seam, and where an edit mask is not allowed to appear</h2>
 *
 * <p>An edit mask is not a field kind. {@link CopybookLayout.Kind} carries exactly five constants,
 * {@code TEXT}, {@code UINT}, {@code ZONED}, {@code PACKED} and {@code BINARY}, and there is
 * deliberately no sixth for a print mask. The two edited amount widths in these bands are
 * therefore declared {@code TEXT} of length {@value #STATEMENT_AMOUNT_LENGTH}: the consumer
 * formats the value into a {@code String} through {@link CobolEditMask} first, and the codec then
 * places that string and pads it as text. Expecting the codec to apply a mask yields either a
 * compilation failure, when no such constant is found, or silent corruption, when the nearest
 * numeric kind is chosen and re-encodes an already-edited string as digits.</p>
 *
 * <p>Assumptions: {@link CopybookLayout.FieldSpec#start()} is ZERO-based and
 * {@link CopybookLayout.FieldSpec#end()} is exclusive, so a field occupies the half-open interval
 * {@code [start, end)}. Every offset in this file is computed on that basis. The reference sources
 * state their own offsets one-based -- the sort control at lines 41 and 42 of
 * {@code app/jcl/TRANREPT.jcl} names positions 263 and 305 for what are offsets 262 and 304 --
 * so a one-based figure copied into a {@code start} component shifts its field by one character
 * and every field after it with it.</p>
 *
 * <p>Assumptions: placement and padding both go through
 * {@code com.carddemo.common.codec.FixedWidthCodec.encodeRecord}, which returns a {@code byte[]}
 * of exactly {@link CopybookLayout.RecordSpec#reclen()} bytes. For these bands its padding is a
 * no-op, because each band is already 80, but it remains the single placement mechanism and no
 * caller pads a column by hand. A per-column padding helper would reproduce, once per band, a
 * decision the codec already makes once, and the two would diverge the first time a width
 * changed.</p>
 *
 * <p>Assumptions: that codec keys its value map by exact field name, and it grants its
 * blank-on-absence shortcut only to a layout its own registry holds. A locally declared band is
 * not registered, so <b>every</b> field of every band below has to be supplied explicitly by the
 * consumer; an omitted one is reported as a missing required field rather than quietly blanked.
 * That is the mechanical reason the {@code FILLER} naming rule below matters.</p>
 *
 * <h2>Assumptions: FILLER is named by occurrence, because the value map is keyed by name</h2>
 *
 * <p>The reference program declares every one of these padding and literal items as
 * {@code FILLER}, which in COBOL is precisely the absence of a name and cannot be referenced from
 * procedural code. This class names the n-th such item of a band {@code FILLER-n}, counting from
 * one in declaration order, so that each is addressable.</p>
 *
 * <p>Alternatives Considered: naming all of them {@code FILLER}, as the shared registry does. That
 * works there because each of its fourteen layouts carries exactly one {@code FILLER}, its
 * trailing pad. Here it would be wrong rather than merely ambiguous. Five of these bands carry
 * several {@code FILLER} items with <em>different</em> literal content -- {@code ST-LINE6},
 * {@code ST-LINE11}, {@code ST-LINE13}, {@code ST-LINE14} and {@code ST-LINE14A} -- and a map
 * keyed by name could hold only one entry for them, so one value would be placed into every
 * interval that shared the key. In {@code ST-LINE14A} that would put the ten-character label
 * {@value #TOTAL_EXPENDITURE_LABEL} into the one-character currency position as well.</p>
 *
 * <p>Alternatives Considered: giving each item a descriptive name for what it carries, such as a
 * name meaning the left rule of the opening banner. Rejected because such a name cannot be
 * checked against anything. An occurrence ordinal is verifiable by counting {@code FILLER} items
 * down the band in {@code app/cbl/CBSTM03A.CBL}, and it keeps the token {@code FILLER} searchable,
 * whereas invented vocabulary is a second thing to keep in step with the reference and two authors
 * would not choose the same words.</p>
 *
 * <h2>Assumptions: INITIALIZE skips FILLER, so every literal must be declared here</h2>
 *
 * <p>Line 459 of {@code app/cbl/CBSTM03A.CBL} opens {@code 5000-CREATE-STATEMENT} at line 458 with
 * {@code INITIALIZE STATEMENT-LINES.}, and that statement leaves {@code FILLER} untouched. In the
 * reference, therefore, every {@code VALUE ALL} rule run and every label literal is established
 * once when storage is laid out and survives every subsequent statement, while only the named
 * items are blanked. An assembly that rebuilds a band field by field has no such carry-over. Each
 * literal is consequently declared as a constant below and each carries its own descriptor entry,
 * so that the consumer emits it explicitly instead of relying on a residue this design does not
 * have.</p>
 *
 * <p>Assumptions: the {@code FILLER} items of these bands are of two kinds and the descriptor
 * describes both. A {@code VALUE ALL} run and a label literal are <em>content</em>, and their
 * bytes are part of the artifact. A {@code VALUE SPACES} item is a <em>declared blank</em>, and its
 * bytes are blanks the record genuinely contains. Both are emitted through the descriptor, which
 * is what lets the geometry check below prove that a band tiles its 80 characters exactly.</p>
 *
 * <h2>Assumptions: this program writes VALUE before PIC</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} places the {@code VALUE} clause <em>before</em> the
 * {@code PICTURE} clause, as at line 69 with {@code 05 WS-SAVE-CARD VALUE SPACES PIC X(16).} and
 * throughout all seventeen bands. {@code app/cpy/CVTRA07Y.cpy}, the source of the report bands,
 * writes them the other way round. Neither order is more correct and both are valid, but a
 * transcription that assumes a single order reads one of the two sources wrongly, so the width and
 * the literal of every field below were read from the clause that declares each rather than from
 * a position within the line.</p>
 *
 * <h2>Assumptions: no band emits a card number, so no field here is marked sensitive</h2>
 *
 * <p>{@link CopybookLayout.FieldSpec#sensitive()} is {@code false} on every field declared below,
 * and the reason is counter-intuitive enough to prove rather than assert: <b>the 80-character
 * statement contains no primary account number at all.</b> In {@code app/cbl/CBSTM03A.CBL} the card
 * number appears only in roles that are not output -- a table key declared
 * {@code 10 WS-CARD-NUM PIC X(16).} at line 227, a read-key restore at line 421 and a table
 * populate at line 827 -- alongside the control-break comparand {@code WS-SAVE-CARD} declared at
 * line 69. None of the seventeen bands carries it. Masking here would therefore protect nothing
 * and would rewrite bytes the parity comparison expects untouched, so it would break byte parity
 * while appearing prudent. Data-exposure narrowing belongs to {@code ReportingDtoMapper}
 * alone.</p>
 *
 * <p>Assumptions: {@link CopybookLayout.FieldSpec#normalizeTs()} is likewise {@code false}
 * throughout, because no band carries a timestamp. The two timestamp items of the statement's
 * input record, declared {@code PIC X(26)} at lines 34 and 35 of {@code app/cpy/COSTM01.CPY}, are
 * read by the program and reach no {@code ST-LINE} field, so this artifact has nothing for the
 * parity comparison to blank before comparing.</p>
 *
 * <h2>Assumptions: the statement file has no key, and the descriptor cannot say so</h2>
 *
 * <p>{@code SELECT STMT-FILE ASSIGN TO STMTFILE.} at line 39 of {@code app/cbl/CBSTM03A.CBL}
 * declares neither an organisation nor a record key, and the data set it is assigned to is
 * sequential, {@code RECFM=FB} at line 89 of {@code app/jcl/CREASTMT.JCL}. A statement band has no
 * key field of any kind. {@link CopybookLayout.RecordSpec} cannot express that, because its
 * constructor requires a key of at least one byte, so each band below declares its key as the
 * whole 80 characters at offset zero: the band's identity is its entire content.</p>
 *
 * <p>Alternatives Considered: declaring a one-byte key at offset zero as a placeholder. Rejected
 * because it would nominate a byte that is not distinguishing. The first character of six of the
 * seventeen bands is a literal shared with other bands -- an asterisk in {@code ST-LINE0} and
 * {@code ST-LINE15}, a hyphen in {@code ST-LINE5}, {@code ST-LINE10} and {@code ST-LINE12}, and a
 * blank in several more -- so a reader or a tool taking that component at face value would treat
 * an identical value across distinct bands as a key. Declaring the whole extent states the absence
 * of a key subfield instead of disguising it.</p>
 *
 * <h2>Assumptions: the geometry check is fail-closed and every band passes it unaided</h2>
 *
 * <p>{@link CopybookLayout.RecordSpec#validateGeometry()} asserts two invariants and throws rather
 * than using an assertion statement: fields must be contiguous from offset zero, which forbids a
 * gap and an overlap in one condition, and their lengths must sum to the declared record length
 * exactly. Every band below is passed through it at class initialisation, so a wrong offset is
 * reported when this class loads rather than when an artifact is written.</p>
 *
 * <p>Because each band is natively 80, all seventeen satisfy that check with <b>no pad field
 * added</b>. That is a further structural difference from the report bands, six of which need an
 * explicit trailing pad entry to satisfy the same invariant. If the check ever rejects a band here,
 * the offsets in that band are wrong and the offsets are what must be re-derived; the declared
 * length of 80 is a property of the data set and is never the thing to adjust.</p>
 *
 * <h2>Assumptions: the two edited amount regimes, and the sign at the other end</h2>
 *
 * <p>Three fields below hold an edited amount and all three are
 * {@value #STATEMENT_AMOUNT_LENGTH} characters wide. {@code ST-CURR-BAL} at line 113 of
 * {@code app/cbl/CBSTM03A.CBL} is declared {@code PIC 9(9).99-} and <em>preserves</em> leading
 * zeros; {@code ST-TRANAMT} at line 137 and {@code ST-TOTAL-TRAMT} at line 142 are declared
 * {@code PIC Z(9).99-} and <em>blank</em> them. Those are the regimes numbered 6 and 7 in
 * {@link CobolEditMask}, and they are otherwise the same shape, so the leading-zero behaviour is
 * the only thing distinguishing them and interchanging the two produces a value that still fills
 * the field.</p>
 *
 * <p>Assumptions: the statement's sign is <b>trailing</b> and the report's is <b>leading</b>, and
 * the two are different widths. The mask characters above place the sign in the last position of
 * thirteen. The report masks at lines 30, 54, 60 and 66 of {@code app/cpy/CVTRA07Y.cpy} place it
 * in the first position of fifteen. No method or width of one artifact substitutes for the
 * other.</p>
 *
 * <h2>Trade-offs: seventeen descriptors and every literal, written out</h2>
 *
 * <p>Declaring all seventeen bands field by field, with every label, sentinel and rule literal as
 * its own constant, is markedly longer than holding the band contents as seventeen 80-character
 * strings and slicing them. The compromise is accepted for two concrete returns. The geometry
 * check can prove that a band tiles its record exactly, which a monolithic string cannot be asked
 * about at all; and each width and each literal sits beside the line of
 * {@code app/cbl/CBSTM03A.CBL} that declares it, so a reviewer checks a single clause rather than
 * counting characters inside a string. The costs are equally concrete: this file is long, and a
 * width that changed in the reference has to be changed here by hand. Both are preferred to an
 * artifact whose 80 characters are asserted by a string literal nobody can verify by reading.</p>
 *
 * <h2>Assumptions: the text labels differ from the HTML labels by one character</h2>
 *
 * <p>The three colon labels declared at lines 108, 112 and 117 of {@code app/cbl/CBSTM03A.CBL} put
 * the colon in the <em>last</em> of twenty positions, with nothing after it. The corresponding
 * labels on the HTML side of the same program carry one blank after the colon. The two forms
 * differ by exactly one character and must never be harmonised: each is the declared content of a
 * different record at a different declared length, and a byte comparison of either artifact
 * against its golden output fails on a single character as readily as on a hundred. The HTML form
 * is {@code StatementHtmlMapper}'s concern, and it is recorded here so the divergence is visible
 * from both ends rather than looking like an inconsistency in one of them.</p>
 *
 * <h2>Baseline framing</h2>
 *
 * <p>Everything under {@code app/} is reference material and remains byte-identical; no statement
 * in this file describes an edit to it. This class encodes the declared geometry and the declared
 * literals of the reference program. Where the migrated behaviour differs from the reference the
 * difference is registered in {@code docs/architecture/cobol-to-service-traceability.md}, which
 * owns that register; nothing in this class defines one, because a declared width and a declared
 * literal admit no divergence to register.</p>
 *
 * <p>The documentation obligation this file answers is user-specified Rule 1 (Explainability),
 * whose validation gate stands at its line 43, mechanised by the Javadoc modules of
 * {@code config/checkstyle/checkstyle.xml} and set out for Java in
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}. Where the three could be read differently the order
 * of precedence is the rule first, then the rule set, then the standard. Every literal below is
 * carried character for character under AAP Rule T8 (user-visible strings are verbatim): no blank
 * is normalised, no label is shortened and the three colons are not brought into line with one
 * another.</p>
 *
 * @see CobolEditMask
 * @see CopybookLayout
 */
public final class StatementBandLayouts {

    // WHY : Assumptions: 80 is the DECLARED record length of the statement data set, not a sum of
    //       anything. It is declared 01 FD-STMTFILE-REC PIC X(80). at line 45 of
    //       app/cbl/CBSTM03A.CBL and repeated as LRECL=80 in the data-set attributes at line 89 of
    //       app/jcl/CREASTMT.JCL. The constant is public so that a caller and a test assert
    //       against one source of truth rather than each restating the literal, and so that a band
    //       width can be checked against the data set rather than against a sibling band.
    public static final int STATEMENT_LINE_LENGTH = 80;

    // WHY : Assumptions: 20 is the declared width shared by the three colon labels of the basic
    //       details block and by the three value items that follow them, at lines 108, 109, 112,
    //       117 and 118 of app/cbl/CBSTM03A.CBL. One constant serves label and value alike because
    //       the reference declares the same PIC X(20) for both, and that equality is what makes
    //       each of those bands split 20, 20 and 40.
    public static final int BASIC_DETAIL_ITEM_LENGTH = 20;

    // WHY : Assumptions: 13 is the declared width of both statement amount masks -- nine digit
    //       positions, one decimal point, two decimal positions and one trailing sign -- at line
    //       113 and at lines 137 and 142 of app/cbl/CBSTM03A.CBL. The same figure is published as
    //       CobolEditMask.STATEMENT_AMOUNT_WIDTH; it is restated here because this class declares
    //       the interval a formatted string is placed into, and that interval has to be assertable
    //       without the descriptor depending on the formatter.
    public static final int STATEMENT_AMOUNT_LENGTH = 13;

    // WHY : Assumptions: 31 and 32, not one shared width. The opening banner at line 86 of
    //       app/cbl/CBSTM03A.CBL wraps an 18-character sentinel and the closing banner at line 143
    //       wraps a 16-character one, so the asterisk runs absorb the two-character difference:
    //       31 + 18 + 31 is 80, and 32 + 16 + 32 is also 80. Two constants therefore state a real
    //       asymmetry, and a single shared width would make one of the two banners 78 or 82.
    public static final int OPENING_ASTERISK_RUN_LENGTH = 31;

    public static final int CLOSING_ASTERISK_RUN_LENGTH = 32;

    // WHY : Assumptions: the rule bands at lines 101, 120 and 126 of app/cbl/CBSTM03A.CBL each
    //       declare one subordinate item and no other, at lines 102, 121 and 127 respectively, and
    //       each of the three is FILLER VALUE ALL '-' PIC X(80), so the run occupies the whole
    //       record and there is nothing beside it. It is declared as its own constant rather than
    //       reusing STATEMENT_LINE_LENGTH because the two are equal by coincidence of this
    //       artifact: the run length is a property of those picture clauses, and a reader tracing
    //       why a rule band is 80 characters long should land on a clause and not on the data set.
    public static final int HYPHEN_RULE_LENGTH = 80;

    // WHY : Assumptions: the three labels below are carried character for character from lines
    //       108, 112 and 117 of app/cbl/CBSTM03A.CBL under AAP Rule T8 (user-visible strings are
    //       verbatim). Each is exactly 20 characters with the colon in the LAST position and no
    //       blank after it, and the run of blanks before each colon differs between them -- nine,
    //       four and nine -- because the label texts differ in length and the colons were aligned
    //       by hand in the reference. Bringing the three into line with one another, or adding the
    //       blank after the colon that the HTML labels of the same program carry, would change the
    //       bytes of a record that is compared byte for byte.
    public static final String ACCOUNT_ID_LABEL = "Account ID         :";

    public static final String CURRENT_BALANCE_LABEL = "Current Balance    :";

    public static final String FICO_SCORE_LABEL = "FICO Score         :";

    // WHY : Assumptions: 13 characters of content inside a 14-character declaration, which is what
    //       line 105 of app/cbl/CBSTM03A.CBL states: PIC X(14) carrying a 13-character VALUE. The
    //       constant holds the 13 declared characters and no more, while the descriptor declares
    //       the 14-character interval, so the one remaining position is a blank the record
    //       genuinely contains -- see the band that carries it for why that makes the visible text
    //       stand off centre.
    public static final String BASIC_DETAILS_HEADING = "Basic Details";

    // WHY : Assumptions: 20 characters INCLUDING the trailing blank, exactly filling the PIC X(20)
    //       at line 124 of app/cbl/CBSTM03A.CBL. The trailing blank is declared content and not an
    //       accident of the source line: the text alone is 19 characters, and dropping the blank
    //       would shorten the item and shift the 30 blanks that follow it.
    public static final String TRANSACTION_SUMMARY_HEADING = "TRANSACTION SUMMARY ";

    // WHY : Assumptions: the three column headings below come from lines 129, 130 and 131 of
    //       app/cbl/CBSTM03A.CBL and each carries blanks that are part of its declared value. The
    //       first is 16 characters filling PIC X(16). The second is 16 characters of content
    //       inside PIC X(51), so 35 further blanks follow it and the constant deliberately does
    //       NOT carry them -- the descriptor states the 51 and the two facts stay separate. The
    //       third has TWO LEADING blanks and is 13 characters, which is what right-aligns the
    //       heading over the 13-character amount mask directly below it.
    public static final String TRAN_ID_HEADING = "Tran ID         ";

    public static final String TRAN_DETAILS_HEADING = "Tran Details    ";

    public static final String TRAN_AMOUNT_HEADING = "  Tran Amount";

    // WHY : Assumptions: 10 characters filling the PIC X(10) at line 139 of app/cbl/CBSTM03A.CBL,
    //       with the colon last and no blank after it. The abbreviation is the reference's own and
    //       is not expanded here, because the item is 10 characters wide and any expansion would
    //       either overrun it or need a narrower label.
    public static final String TOTAL_EXPENDITURE_LABEL = "Total EXP:";

    // WHY : Assumptions: the currency symbol is a SEPARATE one-character FILLER, declared at line
    //       136 and again at line 141 of app/cbl/CBSTM03A.CBL, and it is NOT part of either amount
    //       mask. Neither PIC 9(9).99- nor PIC Z(9).99- contains a currency position, so an edit
    //       mask that emitted one would produce 14 characters where 13 are declared and would push
    //       every character after it out of place. Holding the symbol here keeps it addressable as
    //       the byte it is.
    public static final String CURRENCY_SYMBOL = "$";

    // WHY : Assumptions: a single blank, declared as its own FILLER VALUE ' ' PIC X(01) at line 134
    //       of app/cbl/CBSTM03A.CBL. It separates the transaction identifier from the description
    //       and is declared rather than derived because the codec requires a value for every field
    //       of an unregistered layout, so this one character has to be supplied explicitly.
    public static final String SINGLE_BLANK = " ";

    // WHY : Assumptions: both sentinels are declared with VALUE ALL, at lines 88 and 145 of
    //       app/cbl/CBSTM03A.CBL, and in BOTH cases the ALL degenerates to a plain VALUE because
    //       the literal already fills its declaration exactly: 18 characters in PIC X(18) and 16
    //       in PIC X(16). Nothing repeats. Only the asterisk runs of 31 and 32 and the hyphen runs
    //       of 80 genuinely repeat their single character, which is why those three are built from
    //       a width below while these two are literals.
    public static final String START_OF_STATEMENT_SENTINEL = "START OF STATEMENT";

    public static final String END_OF_STATEMENT_SENTINEL = "END OF STATEMENT";

    // WHY : Assumptions: the three repeated runs carry the content of the five VALUE ALL clauses
    //       that genuinely repeat -- the asterisks at lines 87, 89, 144 and 146 and the hyphens at
    //       lines 102, 121 and 127 of app/cbl/CBSTM03A.CBL -- and they are derived from the widths
    //       above rather than written out, so a run and the descriptor interval it fills cannot
    //       disagree. A hand-written run of 31 or 80 identical characters is unreadable and
    //       uncheckable by eye, and a run one character short of its interval is exactly the error
    //       the geometry check cannot catch, because the descriptor would still tile correctly
    //       while the value placed into it fell short.
    public static final String OPENING_ASTERISK_RUN = "*".repeat(OPENING_ASTERISK_RUN_LENGTH);

    public static final String CLOSING_ASTERISK_RUN = "*".repeat(CLOSING_ASTERISK_RUN_LENGTH);

    public static final String HYPHEN_RULE = "-".repeat(HYPHEN_RULE_LENGTH);

    // WHY : Assumptions: zero is the only offset a whole-record key can start at, and it is named
    //       so that the keyless declaration below reads as a deliberate statement rather than as
    //       two unexplained numbers. The statement file has no key at all -- line 39 of
    //       app/cbl/CBSTM03A.CBL declares neither an organisation nor a record key, and line 89 of
    //       app/jcl/CREASTMT.JCL gives the data set a sequential record format -- so see the class
    //       documentation for why the descriptor nonetheless names the whole band as its key.
    private static final int BAND_KEY_OFFSET = 0;

    // WHY : Trade-offs: every offset below, and every width that is a plain declared character
    //       count, is written as an integer literal rather than through a named constant. That is
    //       the same compromise the shared kernel's field factories were shaped for: a declaration
    //       reads against its own picture clause, so text("ST-ADD1", 0, 50) is checkable against
    //       05 ST-ADD1 PIC X(50). at a glance instead of by resolving a name. The two exceptions
    //       are the amount width and the three repeated runs, where the figure is a count of mask
    //       positions or of repetitions rather than a declared character count, so the name
    //       carries information the literal does not. The cost is that a reader verifying an
    //       offset adds the preceding widths by hand; the return is that each line can be read
    //       against one clause of app/cbl/CBSTM03A.CBL without leaving it.

    // WHY : Assumptions: the opening banner at line 86 of app/cbl/CBSTM03A.CBL is three items --
    //       an asterisk run of 31 at line 87, the 18-character sentinel at line 88 and a second
    //       asterisk run of 31 at line 89 -- so 31 + 18 + 31 is 80. The two runs are equal to each
    //       other here and two characters narrower than the closing banner's, which is the
    //       asymmetry recorded on OPENING_ASTERISK_RUN_LENGTH above.
    public static final CopybookLayout.RecordSpec ST_LINE0 = declareBand("ST-LINE0",
            CopybookLayout.text("FILLER-1", 0, OPENING_ASTERISK_RUN_LENGTH),
            CopybookLayout.text("FILLER-2", 31, 18),
            CopybookLayout.text("FILLER-3", 49, OPENING_ASTERISK_RUN_LENGTH));

    // WHY : Assumptions: the 75 declared for ST-NAME at line 91 of app/cbl/CBSTM03A.CBL is exactly
    //       the sum of the three 25-character name items at lines 6 to 8 of app/cpy/CVCUS01Y.cpy
    //       that lines 462 to 469 assemble into it, so the item cannot be overrun by its own
    //       source and the five characters beyond it are declared blanks rather than spare room.
    public static final CopybookLayout.RecordSpec ST_LINE1 = declareBand("ST-LINE1",
            CopybookLayout.text("ST-NAME", 0, 75),
            CopybookLayout.text("FILLER-1", 75, 5));

    // WHY : Assumptions: the 50 declared for ST-ADD1 at line 94 of app/cbl/CBSTM03A.CBL matches
    //       CUST-ADDR-LINE-1 PIC X(50) at line 9 of app/cpy/CVCUS01Y.cpy, which line 470 moves
    //       into it whole, so this band carries one source item at its own width and nothing is
    //       lost. The same holds for the second address line in the band below.
    public static final CopybookLayout.RecordSpec ST_LINE2 = declareBand("ST-LINE2",
            CopybookLayout.text("ST-ADD1", 0, 50),
            CopybookLayout.text("FILLER-1", 50, 30));

    public static final CopybookLayout.RecordSpec ST_LINE3 = declareBand("ST-LINE3",
            CopybookLayout.text("ST-ADD2", 0, 50),
            CopybookLayout.text("FILLER-1", 50, 30));

    // WHY : Assumptions: this band is a SINGLE item of the full record width, ST-ADD3 PIC X(80) at
    //       line 100 of app/cbl/CBSTM03A.CBL, and it carries no FILLER at all -- unlike the two
    //       address bands above, which are 50 plus 30. The reason is arithmetic rather than
    //       stylistic: lines 472 to 481 assemble it from four source items, CUST-ADDR-LINE-3
    //       PIC X(50), CUST-ADDR-STATE-CD PIC X(02), CUST-ADDR-COUNTRY-CD PIC X(03) and
    //       CUST-ADDR-ZIP PIC X(10) at lines 11 to 14 of app/cpy/CVCUS01Y.cpy, separated by three
    //       single blanks, which is 68 characters at full occupancy. That does not fit the 50 the
    //       two bands above declare, so the item is declared full width. Adding a FILLER here to
    //       make the band resemble its neighbours would push the total past 80.
    public static final CopybookLayout.RecordSpec ST_LINE4 = declareBand("ST-LINE4",
            CopybookLayout.text("ST-ADD3", 0, STATEMENT_LINE_LENGTH));

    // WHY : Assumptions: this is the FIRST of THREE separately declared, byte-identical hyphen rule
    //       bands, at lines 101, 120 and 126 of app/cbl/CBSTM03A.CBL, whose single subordinate
    //       items stand at lines 102, 121 and 127. All three are declared here as distinct
    //       descriptors and must stay distinct. They are not interchangeable names for
    //       one constant, because the emission count depends on their separate identities: the
    //       twenty write sites of the program emit ST-LINE5 twice, at lines 492 and 494, ST-LINE10
    //       once, at line 498, and ST-LINE12 three times, at lines 500, 502 and 435 -- SIX rule
    //       lines per statement. Collapsing them to one descriptor would lose that count, and a
    //       reader who concluded they could be deduplicated would produce a statement with the
    //       wrong number of lines while every individual line still matched.
    public static final CopybookLayout.RecordSpec ST_LINE5 = declareBand("ST-LINE5",
            CopybookLayout.text("FILLER-1", 0, HYPHEN_RULE_LENGTH));

    // WHY : Assumptions: the heading of this band is 13 characters inside a 14-character
    //       declaration, at line 105 of app/cbl/CBSTM03A.CBL, so ONE trailing blank sits inside the
    //       item and the visible text is NOT centred: 33 + 14 + 33 is 80, but the text occupies
    //       columns 34 to 46 of 80 and therefore stands one column left of centre. Widening the
    //       leading blanks to 34 to centre it, or shortening the item to 13, would each change a
    //       record that is compared byte for byte, so the off-centre heading is carried as
    //       declared.
    public static final CopybookLayout.RecordSpec ST_LINE6 = declareBand("ST-LINE6",
            CopybookLayout.text("FILLER-1", 0, 33),
            CopybookLayout.text("FILLER-2", 33, 14),
            CopybookLayout.text("FILLER-3", 47, 33));

    // WHY : Assumptions: the value item receives ACCT-ID PIC 9(11) from line 5 of
    //       app/cpy/CVACT01Y.cpy at line 483 of app/cbl/CBSTM03A.CBL, and the target is character
    //       rather than numeric, so eleven digits land left-aligned in twenty positions with nine
    //       trailing blanks. The item is declared TEXT here for that reason and not as an unsigned
    //       numeric: the artifact holds the moved characters, and describing it numerically would
    //       invite a re-encode that right-aligned and zero-filled them.
    public static final CopybookLayout.RecordSpec ST_LINE7 = declareBand("ST-LINE7",
            CopybookLayout.text("FILLER-1", 0, BASIC_DETAIL_ITEM_LENGTH),
            CopybookLayout.text("ST-ACCT-ID", 20, BASIC_DETAIL_ITEM_LENGTH),
            CopybookLayout.text("FILLER-2", 40, 40));

    // WHY : Assumptions: this band ends in TWO CONSECUTIVE declared-blank items, PIC X(07) at line
    //       114 of app/cbl/CBSTM03A.CBL and PIC X(40) at line 115, and they are two separate
    //       declarations rather than one 47-character item. Both appear below and both must be
    //       emitted, because a field-by-field assembly supplies a value per descriptor entry and
    //       the codec reports a missing one rather than inferring it. Merging them into a single
    //       47-character entry would still tile the band correctly and would still pass the
    //       geometry check, which is exactly why the split is recorded here: the error would be
    //       invisible to every mechanical check and visible only as a descriptor that no longer
    //       transcribes its source.
    // WHY : Assumptions: the amount item is regime 6, PIC 9(9).99- at line 113, which PRESERVES
    //       leading zeros and places its sign LAST. It receives ACCT-CURR-BAL PIC S9(10)V99 from
    //       line 7 of app/cpy/CVACT01Y.cpy at line 484, which carries TEN integer digits into NINE
    //       mask positions, so a balance of a thousand million or more has to be narrowed by the
    //       caller rather than silently truncated by the descriptor. It is declared TEXT of length
    //       13 because an edit mask is not a field kind, as the class documentation sets out.
    public static final CopybookLayout.RecordSpec ST_LINE8 = declareBand("ST-LINE8",
            CopybookLayout.text("FILLER-1", 0, BASIC_DETAIL_ITEM_LENGTH),
            CopybookLayout.text("ST-CURR-BAL", 20, STATEMENT_AMOUNT_LENGTH),
            CopybookLayout.text("FILLER-2", 33, 7),
            CopybookLayout.text("FILLER-3", 40, 40));

    // WHY : Assumptions: the value item receives CUST-FICO-CREDIT-SCORE PIC 9(03) from line 22 of
    //       app/cpy/CVCUS01Y.cpy at line 485 of app/cbl/CBSTM03A.CBL, so three digits land
    //       left-aligned in a 20-character item. The width is the label's width rather than the
    //       score's, which is why this band splits 20, 20 and 40 like the account band above it
    //       even though its value needs three characters.
    public static final CopybookLayout.RecordSpec ST_LINE9 = declareBand("ST-LINE9",
            CopybookLayout.text("FILLER-1", 0, BASIC_DETAIL_ITEM_LENGTH),
            CopybookLayout.text("ST-FICO-SCORE", 20, BASIC_DETAIL_ITEM_LENGTH),
            CopybookLayout.text("FILLER-2", 40, 40));

    // WHY : Assumptions: the SECOND of the three hyphen rule bands, at line 120 of
    //       app/cbl/CBSTM03A.CBL with its subordinate at line 121. Byte-identical in content to
    //       ST-LINE5 and ST-LINE12 and deliberately a separate descriptor, for the emission-count
    //       reason recorded on ST-LINE5.
    public static final CopybookLayout.RecordSpec ST_LINE10 = declareBand("ST-LINE10",
            CopybookLayout.text("FILLER-1", 0, HYPHEN_RULE_LENGTH));

    // WHY : Assumptions: the heading of this band fills its declaration exactly -- 20 characters
    //       including the trailing blank, at line 124 of app/cbl/CBSTM03A.CBL -- so unlike the
    //       basic-details heading above there is no slack inside the item and the split is 30, 20
    //       and 30. The two headings are therefore centred differently for a reason internal to
    //       each, and neither should be adjusted to match the other.
    public static final CopybookLayout.RecordSpec ST_LINE11 = declareBand("ST-LINE11",
            CopybookLayout.text("FILLER-1", 0, 30),
            CopybookLayout.text("FILLER-2", 30, BASIC_DETAIL_ITEM_LENGTH),
            CopybookLayout.text("FILLER-3", 50, 30));

    // WHY : Assumptions: the THIRD of the three hyphen rule bands, at line 126 of
    //       app/cbl/CBSTM03A.CBL with its subordinate at line 127, and the one emitted most often
    //       -- three times per statement, at lines 500, 502 and 435. Byte-identical in content to
    //       ST-LINE5 and ST-LINE10 and deliberately a separate descriptor, for the reason recorded
    //       on ST-LINE5.
    public static final CopybookLayout.RecordSpec ST_LINE12 = declareBand("ST-LINE12",
            CopybookLayout.text("FILLER-1", 0, HYPHEN_RULE_LENGTH));

    // WHY : Assumptions: the three column headings of this band, at lines 129 to 131 of
    //       app/cbl/CBSTM03A.CBL, are declared 16, 51 and 13 while their literals are 16, 16 and
    //       13 characters long. The middle item is the one to watch: its content occupies the
    //       first 16 of 51 positions and 35 declared blanks follow, so the heading row aligns with
    //       the transaction band below it only because that band's description item is 49
    //       characters and is preceded by a 16-character identifier and one blank. The 13-character
    //       amount heading carries two LEADING blanks, which is what right-aligns it over the
    //       13-character amount mask directly beneath.
    public static final CopybookLayout.RecordSpec ST_LINE13 = declareBand("ST-LINE13",
            CopybookLayout.text("FILLER-1", 0, 16),
            CopybookLayout.text("FILLER-2", 16, 51),
            CopybookLayout.text("FILLER-3", 67, STATEMENT_AMOUNT_LENGTH));

    // WHY : Assumptions: the currency character at line 136 of app/cbl/CBSTM03A.CBL is its own
    //       one-character FILLER standing immediately before the amount, and it is NOT part of the
    //       mask. PIC Z(9).99- declares no currency position, so a formatter that emitted one
    //       would return 14 characters for a 13-character item and displace the whole tail of the
    //       band. The single blank at line 134 is likewise a declared character rather than a gap.
    // WHY : Assumptions: the description item is 49 characters, at line 135, while its source
    //       TRNX-DESC is PIC X(100) at line 28 of app/cpy/COSTM01.CPY, so the move at line 677
    //       TRUNCATES to the first 49 characters. The 49 declared here is the target width and the
    //       truncation is the consumer's obligation; widening this item to hold the source would
    //       overrun the record.
    // WHY : Assumptions: the amount item is regime 7, PIC Z(9).99- at line 137, which BLANKS
    //       leading zeros. It is the same 13-character shape as regime 6 in ST-LINE8 and differs
    //       from it in nothing else, so the two are never interchangeable: substituting one for the
    //       other yields a value that still fills the item and still parses, and only a byte
    //       comparison would show it.
    public static final CopybookLayout.RecordSpec ST_LINE14 = declareBand("ST-LINE14",
            CopybookLayout.text("ST-TRANID", 0, 16),
            CopybookLayout.text("FILLER-1", 16, 1),
            CopybookLayout.text("ST-TRANDT", 17, 49),
            CopybookLayout.text("FILLER-2", 66, 1),
            CopybookLayout.text("ST-TRANAMT", 67, STATEMENT_AMOUNT_LENGTH));

    // WHY : Assumptions: the trailer band at line 138 of app/cbl/CBSTM03A.CBL puts the 56 declared
    //       blanks of line 140 between the 10-character label of line 139 and the currency
    //       character, so the amount lands at offset 67 -- the SAME offset as the amount of
    //       ST-LINE14 above, which is what columns the total under the detail amounts. The currency
    //       character at line 141 is again its own one-character FILLER and not part of the mask,
    //       and the amount at line 142 is regime 7, identical in shape to the detail amount and
    //       distinct from the balance in ST-LINE8.
    public static final CopybookLayout.RecordSpec ST_LINE14A = declareBand("ST-LINE14A",
            CopybookLayout.text("FILLER-1", 0, 10),
            CopybookLayout.text("FILLER-2", 10, 56),
            CopybookLayout.text("FILLER-3", 66, 1),
            CopybookLayout.text("ST-TOTAL-TRAMT", 67, STATEMENT_AMOUNT_LENGTH));

    // WHY : Assumptions: the closing banner at line 143 of app/cbl/CBSTM03A.CBL wraps a
    //       16-character sentinel in asterisk runs of 32, at lines 144 to 146, so 32 + 16 + 32 is
    //       80. The runs are one character wider at each end than the opening banner's because its
    //       sentinel is two characters longer; the pair of banners is symmetrical in total width
    //       and asymmetrical in composition, and using one run width for both would make one of
    //       them the wrong length.
    public static final CopybookLayout.RecordSpec ST_LINE15 = declareBand("ST-LINE15",
            CopybookLayout.text("FILLER-1", 0, CLOSING_ASTERISK_RUN_LENGTH),
            CopybookLayout.text("FILLER-2", 32, 16),
            CopybookLayout.text("FILLER-3", 48, CLOSING_ASTERISK_RUN_LENGTH));

    // WHY : Assumptions: the seventeen are held in DECLARATION order, which is the order the
    //       reference declares them at lines 86 to 146 of app/cbl/CBSTM03A.CBL, and NOT in emission
    //       order. The two differ: the emission sequence repeats ST-LINE5 and ST-LINE12 and is
    //       split across three paragraphs of the program, so a list in emission order would be a
    //       twenty-entry sequence and would belong to the consumer that walks it rather than to a
    //       holder that declares geometry. Keeping declaration order makes this list checkable
    //       against the copybook block by reading straight down it.
    private static final List<CopybookLayout.RecordSpec> BANDS = List.of(
            ST_LINE0, ST_LINE1, ST_LINE2, ST_LINE3, ST_LINE4, ST_LINE5, ST_LINE6, ST_LINE7,
            ST_LINE8, ST_LINE9, ST_LINE10, ST_LINE11, ST_LINE12, ST_LINE13, ST_LINE14, ST_LINE14A,
            ST_LINE15);

    /**
     * Prevents instantiation of this static declaration holder.
     *
     * <p>Alternatives Considered: an instantiable class, or one exposing a shared instance, was
     * evaluated and rejected. Every member here is a constant transcribed from a declaration in
     * {@code app/cbl/CBSTM03A.CBL} and there is no state to hold, so an instance would advertise a
     * lifecycle that does not exist and would invite injection of something with nothing to
     * inject. Declaring the constructor private states that intent where the language enforces it,
     * and the class is final so that no subclass can reopen the decision.</p>
     *
     * <p>Assumptions: this constructor is documented in full rather than left bare because
     * user-specified Rule 1 (Explainability) attaches its docstring obligation at line 15 to every
     * function, class and module entry point and names no visibility at all, so a private member is
     * in scope exactly as a public one is. The rule set enforces that reading as well: the
     * {@code MissingJavadocMethod} module of {@code config/checkstyle/checkstyle.xml} is configured
     * at private scope, and {@code JavadocMethod} audits every access modifier, so an undocumented
     * private constructor fails the build rather than deferring to review.</p>
     */
    private StatementBandLayouts() {
    }

    /**
     * Returns the seventeen band descriptors in the order the reference program declares them.
     *
     * <p>Assumptions: the returned list is the immutable one built at class initialisation and is
     * not copied per call. {@code List.of} is unmodifiable and each
     * {@link CopybookLayout.RecordSpec} it holds copies its own field list defensively, so nothing
     * a caller can do through this reference alters the geometry another caller reads. Returning
     * the same instance rather than a fresh copy is what makes that immutability worth having,
     * because a per-call copy would be a second thing to keep honest with no property gained.</p>
     *
     * <p>Assumptions: the order is DECLARATION order, not emission order, for the reason recorded
     * beside the list itself. A caller assembling a statement walks its own sequence and does not
     * take this order as the order to write.</p>
     *
     * @return an unmodifiable list of the seventeen statement band descriptors, in the declaration
     *     order of lines 86 to 146 of {@code app/cbl/CBSTM03A.CBL}; never {@code null} and never
     *     empty
     */
    public static List<CopybookLayout.RecordSpec> bands() {
        return BANDS;
    }

    /**
     * Returns the band descriptor of the given declared group name.
     *
     * <p>Assumptions: lookup is by the group name the reference declares, so {@code ST-LINE14A} is
     * spelled with its trailing letter and {@code ST-LINE0} through {@code ST-LINE15} carry no
     * leading zero. A name that matches nothing is rejected rather than answered with a null, so
     * a misspelling surfaces at the lookup that made it instead of as a failure inside the codec
     * one call later. A {@code null} name is rejected on the same path, because no declared group
     * name equals it.</p>
     *
     * <p>Alternatives Considered: holding a name-keyed map instead of scanning the list. Rejected
     * because seventeen entries are scanned faster than a hash is computed and, more to the point,
     * a map would be a second structure to keep in step with the list; the shared kernel resolves
     * a field within a record by the same linear scan, at
     * {@link CopybookLayout.RecordSpec#field(String)}, and for the same reason.</p>
     *
     * <p>Alternatives Considered: raising {@link CopybookLayout.LayoutException} here, which is
     * what {@link CopybookLayout.RecordSpec#field(String)} raises for the analogous failure. It is
     * not available: that type's constructor is package-private inside the shared codec package,
     * deliberately so, because its own documentation scopes it to reporting a violated geometry
     * contract at a declaration site. An unknown band name is not a geometry violation -- the
     * seventeen descriptors are all well formed -- so the platform argument exception is raised
     * instead. A caller loses nothing by it, because {@code LayoutException} extends that same type
     * and any handler written for the broader one already catches both.</p>
     *
     * @param bandName the declared group name of the wanted band, matched exactly as
     *     {@code app/cbl/CBSTM03A.CBL} spells it at lines 86 to 146
     * @return the matching band descriptor; never {@code null}
     * @throws IllegalArgumentException if no band carries that name, in which case the message
     *     names the requested band and lists the seventeen declared names, so the caller can see
     *     which of the two spellings is wrong
     */
    public static CopybookLayout.RecordSpec band(String bandName) {
        for (CopybookLayout.RecordSpec candidate : BANDS) {
            if (candidate.name().equals(bandName)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("no statement band is named " + bandName
                + "; the seventeen declared band names are ST-LINE0 through ST-LINE13, ST-LINE14,"
                + " ST-LINE14A and ST-LINE15");
    }

    /**
     * Declares one statement band at the record geometry all seventeen bands share.
     *
     * <p>Assumptions: three of the five components of {@link CopybookLayout.RecordSpec} are the
     * same for every band, so they are applied here once instead of being restated seventeen
     * times. The record length is the declared 80 of line 45 of {@code app/cbl/CBSTM03A.CBL}. The
     * key is the whole 80 characters at offset zero, because the statement file has no key at all
     * -- {@code SELECT STMT-FILE ASSIGN TO STMTFILE.} at line 39 declares neither an organisation
     * nor a record key -- and the descriptor cannot express a key of zero bytes, as the class
     * documentation sets out at length.</p>
     *
     * <p>Assumptions: {@link CopybookLayout.RecordSpec#validateGeometry()} is called on every band
     * as it is declared, so a wrong offset or width is reported when this class loads rather than
     * when a statement is written. That check is fail-closed on contiguity from offset zero and on
     * the field lengths summing to the declared length exactly, and every band here satisfies it
     * with no pad entry added because each is natively 80. A rejection therefore means the offsets
     * passed in are wrong; the declared length is a property of the data set and is never the
     * thing to change in response.</p>
     *
     * <p>Alternatives Considered: writing the constructor call out at each of the seventeen
     * declaration sites, as the shared kernel's own registry does. Rejected here for a reason that
     * does not apply there: its fourteen layouts each carry a genuinely different record length,
     * key length and key offset, so restating them is informative, whereas seventeen repetitions
     * of one identical triple would bury the one component that differs and would give the keyless
     * declaration seventeen places to be explained instead of one.</p>
     *
     * @param bandName the declared group name of the band, used as the descriptor name and in the
     *     message of any geometry rejection
     * @param fields the band's fields in declaration order, contiguous from offset zero and
     *     summing to exactly 80 characters
     * @return the validated band descriptor
     * @throws NullPointerException if {@code fields} is {@code null} or holds a {@code null}
     *     element, which {@code List.of} rejects rather than admitting into a descriptor
     * @throws CopybookLayout.LayoutException if the band name is {@code null} or blank, if no field
     *     is supplied, or if the fields are not contiguous from offset zero or do not sum to
     *     exactly 80 characters
     */
    private static CopybookLayout.RecordSpec declareBand(String bandName,
            CopybookLayout.FieldSpec... fields) {
        return new CopybookLayout.RecordSpec(bandName, STATEMENT_LINE_LENGTH,
                STATEMENT_LINE_LENGTH, BAND_KEY_OFFSET, List.of(fields)).validateGeometry();
    }
}
