package com.carddemo.reporting.mapper;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.money.Money;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Emits the 80-character plain-text cardholder statement, band by band and byte for byte.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class assembles every line of the plain-text account statement through the seventeen band
 * descriptors of {@link StatementBandLayouts}, and it owns the prepared-field set that the markup
 * rendering of the same statement shares. It is the anti-corruption boundary for that artifact:
 * <b>this is the only place in the plain-text statement path where a declared width, a trailing
 * blank, {@code FILLER} content, a truncation or a sign placement may appear.</b> Everything
 * downstream of it carries values rather than layouts.</p>
 *
 * <p>Its three emission methods correspond one for one to the three paragraphs of
 * {@code app/cbl/CBSTM03A.CBL} that write to the statement file, and its three preparation methods
 * populate the values those bands and the markup artifact both read. Nothing here reads a clock,
 * performs arithmetic, rounds a value, opens a file or emits markup.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a static assembler that cannot be
 * instantiated, so the type itself accepts no parameter, yields no value and raises nothing. The
 * inapplicability is stated rather than passed over, because user-specified Rule 1 (Explainability)
 * forbids at its line 39 a docstring that omits parameters, return values or purpose, and a reader
 * must be able to tell a declared inapplicability from an oversight. Every member below carries its
 * own parameter, return and exception at-clauses.</p>
 *
 * <h2>Assumptions: 80 is the declared length, and this artifact needs no padding at all</h2>
 *
 * <p>The statement record length is 80 characters, declared {@code 01 FD-STMTFILE-REC PIC X(80).}
 * at line 45 of {@code app/cbl/CBSTM03A.CBL} under the {@code FD STMT-FILE.} at line 44, and
 * corroborated by the data-set attributes {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} at line 89
 * of {@code app/jcl/CREASTMT.JCL}.</p>
 *
 * <p><b>Every one of the seventeen bands sums to exactly 80 from its own declared pictures, so this
 * artifact requires no band padding whatsoever. That is the OPPOSITE of the 133-column daily
 * transaction report, six of whose seven bands are short of their declared length and every one of
 * those six must be blank-padded out to it.</b> Both halves of the contrast are stated here in full
 * because the two obligations look like one problem and are not, and because a reader may arrive at
 * this file straight from the report side or leave it for the report side. Padding a statement band
 * would push it past 80 and overrun the record; declining to pad a report band would leave that
 * band short. Neither assumption may be carried across.</p>
 *
 * <p>Assumptions: placement nevertheless goes through
 * {@link FixedWidthCodec#encodeRecord(Map, CopybookLayout.RecordSpec)} for every band, which
 * returns a {@code byte[]} of exactly {@link CopybookLayout.RecordSpec#reclen()} bytes. For these
 * bands its padding step is a no-op, but it remains the single placement mechanism and no column is
 * padded by hand here. A per-column padding helper would reproduce, once per band, a decision the
 * codec already makes once, and the two would diverge the first time a width changed.</p>
 *
 * <h2>Assumptions: INITIALIZE skips FILLER, so every literal is emitted explicitly</h2>
 *
 * <p>Line 459 of {@code app/cbl/CBSTM03A.CBL} opens {@code 5000-CREATE-STATEMENT} at line 458 with
 * {@code INITIALIZE STATEMENT-LINES.}, and that statement leaves {@code FILLER} untouched. In the
 * reference, therefore, every {@code VALUE ALL} rule run and every label literal is established
 * once when storage is laid out and survives every later statement, while only the named items are
 * blanked. An assembly that rebuilds each band field by field has no such carry-over, so every
 * literal is supplied explicitly from the constants of {@link StatementBandLayouts}. The codec
 * reinforces that: it grants its blank-on-absence shortcut only to a layout its own registry holds,
 * and a locally declared band is not registered, so an omitted field is reported as missing rather
 * than quietly blanked.</p>
 *
 * <h2>Assumptions: six hyphen rules per statement, from three separately declared bands</h2>
 *
 * <p>The reference writes to the statement file at exactly twenty sites, verified by search: lines
 * 435, 436 and 437 in the card trailer, line 460 for the opening banner, the fifteen consecutive
 * writes at lines 488 to 502, and line 679 for one transaction line. Within that census the three
 * byte-identical hyphen rule bands, declared separately at lines 101, 120 and 126 of
 * {@code app/cbl/CBSTM03A.CBL}, are emitted as follows:</p>
 *
 * <pre>
 * ST-LINE5 lines 492 and 494 2 emissions ST-LINE10 line 498 1 emission ST-LINE12 lines 500, 502 and
 * 435 3 emissions ----------- 6 hyphen rule lines per statement </pre>
 *
 * <p><b>The byte stream depends on that repetition, so a repeated separator band is never
 * collapsed.</b> Two of the six emissions are the same descriptor written twice in a row of
 * fifteen, and a reader who concluded that three identical descriptors could be reduced to one
 * would produce a statement holding the wrong number of lines while every individual line still
 * matched its source. That failure is invisible to any per-line check.</p>
 *
 * <h2>Alternatives Considered: populate once, render twice, and this class owns the values</h2>
 *
 * <p>{@code 01 STATEMENT-LINES.} is populated once per card and <b>both</b> outputs then read from
 * it. The plain-text side writes the bands at lines 488 to 502. The markup paragraph
 * {@code 5200-WRITE-HTML-NMADBS}, labelled at line 558, reads {@code ST-NAME} at line 560,
 * {@code ST-ADD1} at line 571, {@code ST-ADD2} at line 579, {@code ST-ADD3} at line 587,
 * {@code ST-ACCT-ID} at line 614, {@code ST-CURR-BAL} at line 621 and {@code ST-FICO-SCORE} at line
 * 628. Identically, {@code 6000-WRITE-TRANS} populates {@code ST-TRANID}, {@code ST-TRANDT} and
 * {@code ST-TRANAMT} at lines 676 to 678, and then both the plain-text band at line 679 and the
 * three markup cells at lines 687, 699 and 711 read those same items. The markup artifact therefore
 * provably renders the <em>same edited values</em> as the plain text, including the two amount
 * masks verbatim.</p>
 *
 * <p>This class consequently owns the preparation and exposes the results as three immutable nested
 * records, {@link PreparedHeaderFields}, {@link PreparedTransactionFields} and
 * {@link PreparedTrailerFields}. The markup mapper takes those records as input.</p>
 *
 * <p>Alternatives Considered: duplicating the preparation in both mappers. Rejected because the two
 * renderings could then drift, and the evidence above shows the reference cannot drift: one storage
 * item feeds both, so a divergence between the plain-text balance at line 484 and the markup
 * balance cell at line 621 is not expressible in the source at all. Two independent preparations
 * would make it expressible, and the failure would surface as a markup artifact disagreeing with a
 * plain-text artifact about the same amount.</p>
 *
 * <p>Alternatives Considered: hoisting the preparation into a further shared class. Rejected
 * because no specification or rule calls for one, and because it would separate the preparation
 * from the bands it fills. The seven narrowings below exist to satisfy the declared widths of
 * particular {@code ST-LINE} items; a class that knew the narrowings but not the bands would state
 * a width twice, here and there, with nothing tying the two statements together.</p>
 *
 * <h2>Alternatives Considered: three emission methods, mirroring three source paragraphs</h2>
 *
 * <p>The reference emits a statement from three different paragraphs, and this class exposes three
 * emission methods for exactly that reason: {@code 5000-CREATE-STATEMENT} at line 458 writes the
 * opening banner and the header block, {@code 6000-WRITE-TRANS} at line 675 writes one transaction
 * line, and {@code 4000-TRNXFILE-GET} at line 416 writes the card trailer. Alternatives Considered:
 * one method taking the whole statement and looping internally. Rejected because the trailer total
 * is not known when the header is written -- the accumulation runs at line 429, inside the loop,
 * and only line 433 moves it -- so a single method would have to take a pre-computed total
 * alongside the header values and would invite a caller to supply one that the transactions it
 * passed do not sum to. Three methods keep each caller obligation at the point the reference
 * imposes it.</p>
 *
 * <p>Assumptions: the reference reuses paragraph-number prefixes, so {@code 8100-FILE-OPEN} at line
 * 726 and {@code 8100-TRNXFILE-OPEN} at line 730 share one prefix. Java method names must be
 * distinct, so the names here are derived from what each paragraph does rather than from its
 * number. That is a naming divergence forced by the language and nothing more; no paragraph is
 * merged, split or reordered.</p>
 *
 * <h2>Assumptions: three STRING regimes, and a uniform trim is wrong in two of the three</h2>
 *
 * <p>Searching {@code app/cbl/CBSTM03A.CBL} yields these exact counts: {@code DELIMITED BY ' '}
 * appears 7 times, {@code DELIMITED BY '  '} 4 times, {@code DELIMITED BY '*'} 26 times and
 * {@code DELIMITED BY SIZE} 11 times. They are three distinct regimes and only the first belongs to
 * this class:</p>
 *
 * <ul> <li><b>One blank, part assembly.</b> This class's regime. Lines 462 to 469 build
 * {@code ST-NAME} from three name parts, which is 3 uses, and lines 472 to 481 build
 * {@code ST-ADD3} from four address parts, which is 4 uses; 3 plus 4 is exactly the count of 7.
 * Each part transfers only up to its own first blank, so a value holding an internal blank
 * contributes only the characters before it.</li> <li><b>Two blanks, right trim.</b> The markup
 * mapper's regime, at lines 563, 571, 579 and 587 -- exactly 4. Each transfers up to the first
 * PAIRED blank and then re-appends exactly two blanks, so <b>a single trailing blank is
 * preserved</b>.</li> <li><b>An asterisk absent from the data, whole field.</b> Also the markup
 * mapper's, in six blocks: the basic-detail cells at lines 614, 621 and 628 and the transaction
 * cells at lines 687, 699 and 711. Those cells transfer the complete item including its trailing
 * blanks.</li> </ul>
 *
 * <p>Assumptions: because the second and third regimes read whole declared-width items, <b>this
 * class hands the prepared records values at their exact declared width, trailing blanks included,
 * and pre-trims nothing.</b> {@code String.strip()} and {@code String.trim()} are wrong on both
 * counts and are used nowhere in this file: they remove every trailing blank, which destroys the
 * single trailing blank the second regime depends on, and they do not truncate at an internal
 * paired blank, which is the only thing that regime actually does.</p>
 *
 * <h2>Assumptions: the two amount regimes, and the sign at the other end</h2>
 *
 * <p>{@code ST-CURR-BAL}, declared {@code PIC 9(9).99-} at line 113 of
 * {@code app/cbl/CBSTM03A.CBL}, is regime 6 of {@link CobolEditMask}: 13 characters, trailing sign,
 * leading zeros PRESERVED. {@code ST-TRANAMT} at line 137 and {@code ST-TOTAL-TRAMT} at line 142,
 * both declared {@code PIC Z(9).99-}, are regime 7: 13 characters, trailing sign, leading zeros
 * BLANKED. <b>The two are the same width and otherwise the same shape, differing only in
 * leading-zero behaviour, so they are never interchanged</b> -- substituting one would still fill
 * the item and still parse as the same number, and only a byte comparison would show it.</p>
 *
 * <p>Assumptions: the statement's sign is TRAILING at 13 characters, while the report's is LEADING
 * at 15. No method and no width of one artifact substitutes for the other.</p>
 *
 * <p>Assumptions: the currency character is a separate declared byte and never part of a mask. It
 * is its own {@code FILLER PIC X(01)} at line 136 of {@code app/cbl/CBSTM03A.CBL} for the
 * transaction band and at line 141 for the trailer band, and neither {@code PIC 9(9).99-} nor
 * {@code PIC Z(9).99-} declares a currency position. A formatter that emitted one would return 14
 * characters for a 13-character item and displace the whole tail of the band.</p>
 *
 * <p>Assumptions: an edit mask is not a field kind. {@link CopybookLayout.Kind} carries exactly
 * five constants, {@code TEXT}, {@code UINT}, {@code ZONED}, {@code PACKED} and {@code BINARY}, and
 * there is no sixth for a print mask. Each amount is therefore formatted into a {@code String}
 * first and placed by the codec as {@code TEXT} of length
 * {@value StatementBandLayouts#STATEMENT_AMOUNT_LENGTH}.</p>
 *
 * <h2>Assumptions: money is Money, and no locale can reach these bytes</h2>
 *
 * <p>Every money parameter in this class is {@link Money}. The shared kernel's Jackson module binds
 * its serialiser to that type, so a money value carried as the platform arbitrary-precision decimal
 * instead would compile, run, and silently serialise as a JSON number that a client parses through
 * an IEEE-754 binary floating-point value. This class therefore accepts {@code Money} and nothing
 * else for an amount, and no IEEE-754 binary floating-point type appears anywhere in it.</p>
 *
 * <p>Assumptions: this class calls no locale-sensitive formatter. Neither
 * {@code java.text.DecimalFormat} nor {@code String.format} is used anywhere in it, so its output
 * cannot vary with the default locale of the virtual machine. That matters concretely: a machine
 * defaulting to a comma-decimal locale renders a grouped decimal with its separators exchanged,
 * which corrupts the 80-character contract byte for byte while still looking like the same number.
 * Every digit this class emits comes either from {@link CobolEditMask}, which composes its masks
 * character by character, or from the platform's locale-independent integral rendering. Locale
 * invariance is consequently structural here rather than a convention to be observed.</p>
 *
 * <p>Assumptions: this class performs no arithmetic and no rounding. The trailer total arrives
 * already accumulated, exactly as it does in the reference, where {@code WS-TOTAL-AMT} is declared
 * {@code PIC S9(9)V99 COMP-3} at line 65 of {@code app/cbl/CBSTM03A.CBL}, accumulated at line 429,
 * moved through {@code WS-TRN-AMT PIC S9(9)V99} at line 68 by line 433 and only then edited at line
 * 434. Because no product and no quotient is formed here, AAP Rule T4 (arithmetic order is
 * preserved) raises no product-before-quotient concern in this file.</p>
 *
 * <p>Assumptions: no member of this class reads a clock. No band carries a timestamp: the two
 * timestamp items of the statement's input record, declared {@code PIC X(26)} at lines 34 and 35 of
 * {@code app/cpy/COSTM01.CPY}, are read by the reference and reach no {@code ST-LINE} field, so
 * there is nothing here for which a current instant could even be a candidate value.</p>
 *
 * <h2>Assumptions: no band emits a card number, so this class masks nothing</h2>
 *
 * <p>The claim is counter-intuitive enough to prove rather than assert: <b>the 80-character
 * statement contains no primary account number at all.</b> In {@code app/cbl/CBSTM03A.CBL} the card
 * number appears only in roles that are not output -- the table key declared
 * {@code 10 WS-CARD-NUM PIC X(16).} at line 227, the read-key restore at line 421 and the table
 * populate at line 827 -- alongside the control-break comparand {@code WS-SAVE-CARD} declared
 * {@code 05 WS-SAVE-CARD VALUE SPACES PIC X(16).} at line 69. None of the seventeen bands carries
 * it, which is why every {@link CopybookLayout.FieldSpec#sensitive()} in
 * {@link StatementBandLayouts} is {@code false}.</p>
 *
 * <p>This class therefore performs no masking, no suppression and no encryption. <b>Masking here
 * would protect nothing and would rewrite bytes the parity comparison expects untouched, so it
 * would break byte parity while appearing prudent.</b> Data-exposure narrowing belongs to
 * {@code ReportingDtoMapper} alone.</p>
 *
 * <h2>Assumptions: the customer provenance is CUSTREC, and it differs from its twin twice</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} copies four books, at lines 51, 53, 55 and 57: {@code COSTM01},
 * {@code CVACT03Y}, <b>{@code CUSTREC}</b> and {@code CVACT01Y}. The statement's customer
 * provenance is therefore {@code app/cpy/CUSTREC.cpy} and not its twin
 * {@code app/cpy/CVCUS01Y.cpy}. The two books are both 26 lines and geometrically identical, and
 * they diverge in exactly two ways. At line 19 the date-of-birth item is named without inner
 * hyphens in {@code CUSTREC.cpy} and with them in {@code CVCUS01Y.cpy}. And {@code CUSTREC.cpy}
 * indents with tab characters on 17 of its lines while {@code CVCUS01Y.cpy} carries none, which is
 * a byte-level difference beyond the name. <b>Field names and {@code PICTURE} clauses are
 * consequently cited from {@code CUSTREC.cpy} and never column offsets</b>, since a tab makes a
 * column count meaningless, and the books are not treated as interchangeable.</p>
 *
 * <p>Assumptions: the three baseline field renames named by AAP Rule T1 (Copybook is normative)
 * concern an account expiration item, a card expiration item and a merchant category item. None of
 * the three reaches this package, so no member below renames anything and no reader should expect a
 * fourth rename here.</p>
 *
 * <h2>Assumptions: the parity oracle covers this path, and a Java gate is binary</h2>
 *
 * <p>{@code CBSTM03A} and {@code CBSTM03B} are batch programs, and lines 40 to 46 of
 * {@code tests/README.md} establish that ten of the twelve batch programs are fully automatable, so
 * <b>the statement path does have golden-master oracle coverage</b>. The on-demand report path does
 * not: its driver is an online program, and lines 40 to 46 together with lines 83 to 85 of the same
 * document record that the online programs cannot run end to end without a terminal monitor.
 * Neither half of that should be generalised to the other.</p>
 *
 * <p>Assumptions: the graded condition-code rubric of that oracle, and its documented aggregate
 * warn-level result, belong exclusively to the reference suite under {@code tests/}. The build gate
 * for this class is binary: it passes or it fails, and there is no warn tier in it.</p>
 *
 * <p>Assumptions: the reference declares two unchecked working-storage tables, at lines 225 to 233
 * of {@code app/cbl/CBSTM03A.CBL}, whose declared arities are 51 cards and 10 transactions per
 * card. This class holds no table and imposes no arity: a caller emits as many transaction lines as
 * it has transactions, one call each. That divergence is registered as <b>D-2</b> in
 * {@code docs/architecture/cobol-to-service-traceability.md} section 7.1. It is cited by identifier
 * rather than by path alone because that register's own discipline requires it: a claim of
 * registration that names nothing cannot be checked against the register, and the two sides are
 * meant to be reconcilable by search.</p>
 *
 * <h2>Trade-offs: each nested record overrides {@code toString} and nothing else</h2>
 *
 * <p>The generated {@code equals} and {@code hashCode} of all three nested records are the
 * canonical ones, because each record is a set of values with no identity of its own and
 * component-wise comparison is exactly right for that. The generated {@code toString} is
 * <b>replaced</b> in all three, and that is the only override in this file.</p>
 *
 * <p>Refactoring Rationale: the generated rendering of a record prints every component. For these
 * three records that means an account holder's assembled name, both address lines, the assembled
 * address, the edited balance, the credit score, the transaction description and two monetary
 * amounts -- reached by any string concatenation, log template, assertion message or debugger that
 * touches an instance, with no call site having asked for it. An earlier revision of this paragraph
 * declined to write a {@code toString} on the stated grounds that one would carry an account
 * identifier into a log line; that reasoning was inverted, because declining an override does not
 * suppress a rendering, it leaves the generated one in place and discloses strictly more. Each
 * replacement therefore names at most the one component that locates the row -- the account
 * identifier on the header record, the transaction identifier on the per-transaction record, and
 * nothing at all on the trailer record, whose single component is a monetary total -- and each
 * carries the prose that user-specified Rule 1 (Explainability) requires of any method, stating
 * what it withholds and why.</p>
 *
 * <p>Assumptions: none of the three renderings is a parity artifact. The bytes this class emits are
 * produced only by the three emission methods through the shared codec, so a comparison against a
 * golden statement must never be built from a rendering here; the renderings drop declared trailing
 * blanks, which alone would break a byte comparison.</p>
 *
 * @see StatementBandLayouts
 * @see CobolEditMask
 */
public final class StatementTextMapper {

    /**
     * Declared width of each of the three customer name parts, in characters.
     *
     * <p>Assumptions: {@code CUST-FIRST-NAME}, {@code CUST-MIDDLE-NAME} and {@code CUST-LAST-NAME}
     * are each declared {@code PIC X(25)} at lines 6, 7 and 8 of {@code app/cpy/CUSTREC.cpy}. All
     * three share one width, so one constant states it; three constants of equal value would invite
     * a reader to look for a difference between them.</p>
     */
    public static final int NAME_PART_WIDTH = 25;

    /**
     * Declared width of the assembled name item of the first statement band, in characters.
     *
     * <p>Assumptions: {@code ST-NAME} is declared {@code PIC X(75)} at line 91 of
     * {@code app/cbl/CBSTM03A.CBL}.</p>
     */
    public static final int ASSEMBLED_NAME_WIDTH = 75;

    /**
     * Characters the name assembly can produce at full occupancy, before the receiving item bounds
     * it.
     *
     * <p>Assumptions: lines 462 to 469 of {@code app/cbl/CBSTM03A.CBL} concatenate three
     * 25-character parts each followed by one blank literal, including a trailing blank after the
     * third part, so the assembly can offer 78 characters to a 75-character item. The excess of 3
     * is the reason the name assembly can overflow at all, and it is stated as its own constant so
     * the arithmetic is checkable rather than implied.</p>
     */
    public static final int NAME_ASSEMBLY_CAPACITY = 78;

    /**
     * Declared width of the name item the markup rendering narrows to, in characters.
     *
     * <p>Assumptions: {@code L23-NAME} is declared {@code PIC X(50)} at line 220 of
     * {@code app/cbl/CBSTM03A.CBL}, subordinate to the {@code HTML-L23} group opened at line 217.
     * Line 560 moves the 75-character assembled name into it.</p>
     */
    public static final int MARKUP_NAME_WIDTH = 50;

    /**
     * Declared width of each of the three customer address lines, in characters.
     *
     * <p>Assumptions: {@code CUST-ADDR-LINE-1}, {@code CUST-ADDR-LINE-2} and
     * {@code CUST-ADDR-LINE-3} are each declared {@code PIC X(50)} at lines 9, 10 and 11 of
     * {@code app/cpy/CUSTREC.cpy}, and the first two are received by items of the same 50, declared
     * at lines 94 and 97 of {@code app/cbl/CBSTM03A.CBL}. Source and target agree for those two, so
     * neither can lose a character.</p>
     */
    public static final int ADDRESS_LINE_WIDTH = 50;

    /**
     * Declared width of the customer state code, in characters.
     *
     * <p>Assumptions: {@code CUST-ADDR-STATE-CD} is declared {@code PIC X(02)} at line 12 of
     * {@code app/cpy/CUSTREC.cpy}.</p>
     */
    public static final int STATE_CODE_WIDTH = 2;

    /**
     * Declared width of the customer country code, in characters.
     *
     * <p>Assumptions: {@code CUST-ADDR-COUNTRY-CD} is declared {@code PIC X(03)} at line 13 of
     * {@code app/cpy/CUSTREC.cpy}.</p>
     */
    public static final int COUNTRY_CODE_WIDTH = 3;

    /**
     * Declared width of the customer postal code, in characters.
     *
     * <p>Assumptions: {@code CUST-ADDR-ZIP} is declared {@code PIC X(10)} at line 14 of
     * {@code app/cpy/CUSTREC.cpy}.</p>
     */
    public static final int POSTAL_CODE_WIDTH = 10;

    /**
     * Declared width of the assembled third-address item of the fourth statement band, in
     * characters.
     *
     * <p>Assumptions: {@code ST-ADD3} is declared {@code PIC X(80)} at line 100 of
     * {@code app/cbl/CBSTM03A.CBL} and is the whole of its band, unlike the two address bands above
     * it which are 50 plus 30 declared blanks.</p>
     */
    public static final int ASSEMBLED_ADDRESS_WIDTH = 80;

    /**
     * Characters the third-address assembly can produce at full occupancy.
     *
     * <p>Assumptions: lines 472 to 481 of {@code app/cbl/CBSTM03A.CBL} concatenate the 50-character
     * third address line, the 2-character state code, the 3-character country code and the
     * 10-character postal code, each followed by one blank literal, which is FOUR separators rather
     * than three. That is 50 plus 1 plus 2 plus 1 plus 3 plus 1 plus 10 plus 1, or 69 characters,
     * into an 80-character item.</p>
     *
     * <p><b>The address assembly therefore cannot overflow, while the name assembly can.</b> The
     * asymmetry is real and is stated as a constant so that nobody truncates the address
     * defensively: 69 into 80 leaves 11 characters of declared slack, whereas 78 into 75 is 3
     * characters short.</p>
     */
    public static final int ADDRESS_ASSEMBLY_CAPACITY = 69;

    /**
     * Digit positions the account identifier declares.
     *
     * <p>Assumptions: {@code ACCT-ID} is declared {@code PIC 9(11)} at line 5 of
     * {@code app/cpy/CVACT01Y.cpy}.</p>
     */
    public static final int ACCOUNT_ID_DIGITS = 11;

    /**
     * Declared width of the account identifier item of the seventh statement band, in characters.
     *
     * <p>Assumptions: {@code ST-ACCT-ID} is declared {@code PIC X(20)} at line 109 of
     * {@code app/cbl/CBSTM03A.CBL}, so the 11 digits moved into it at line 483 occupy the first 11
     * of 20 positions and 9 declared blanks follow them.</p>
     */
    public static final int ACCOUNT_ID_ITEM_WIDTH = 20;

    /**
     * Digit positions the credit score declares.
     *
     * <p>Assumptions: {@code CUST-FICO-CREDIT-SCORE} is declared {@code PIC 9(03)} at line 22 of
     * {@code app/cpy/CUSTREC.cpy}.</p>
     */
    public static final int CREDIT_SCORE_DIGITS = 3;

    /**
     * Declared width of the credit score item of the ninth statement band, in characters.
     *
     * <p>Assumptions: {@code ST-FICO-SCORE} is declared {@code PIC X(20)} at line 118 of
     * {@code app/cbl/CBSTM03A.CBL}, so the 3 digits moved into it at line 485 are followed by 17
     * declared blanks. The width is the label's width rather than the score's, which is why the
     * band splits 20, 20 and 40 for a value needing three characters.</p>
     */
    public static final int CREDIT_SCORE_ITEM_WIDTH = 20;

    /**
     * Declared width of the transaction identifier item of the transaction band, in characters.
     *
     * <p>Assumptions: {@code ST-TRANID} is declared {@code PIC X(16)} at line 133 of
     * {@code app/cbl/CBSTM03A.CBL} and receives {@code TRNX-ID}, declared {@code PIC X(16)} at line
     * 23 of {@code app/cpy/COSTM01.CPY}, by the move at line 676 of that program. Source and target
     * agree, so this transfer loses nothing.</p>
     */
    public static final int TRANSACTION_ID_WIDTH = 16;

    /**
     * Declared width of the transaction description in its source record, in characters.
     *
     * <p>Assumptions: {@code TRNX-DESC} is declared {@code PIC X(100)} at line 28 of
     * {@code app/cpy/COSTM01.CPY}. It is stated separately from the receiving width below because
     * the two differ, and the difference is the single largest narrowing in this artifact.</p>
     */
    public static final int DESCRIPTION_SOURCE_WIDTH = 100;

    /**
     * Declared width of the transaction description item of the transaction band, in characters.
     *
     * <p>Assumptions: {@code ST-TRANDT} is declared {@code PIC X(49)} at line 135 of
     * {@code app/cbl/CBSTM03A.CBL}, so the move at line 677 discards the last 51 characters of its
     * 100-character source.</p>
     */
    public static final int DESCRIPTION_ITEM_WIDTH = 49;

    /**
     * Lines the header block emits, including the opening banner.
     *
     * <p>Assumptions: line 460 of {@code app/cbl/CBSTM03A.CBL} writes the opening banner and lines
     * 488 to 502 write fifteen further bands, so the block is 1 plus 15 lines. The count is
     * declared here so that a caller can size a buffer and a test can assert the total without
     * recounting the write sites.</p>
     */
    public static final int HEADER_BLOCK_LINE_COUNT = 16;

    /**
     * Lines the card trailer emits.
     *
     * <p>Assumptions: lines 435, 436 and 437 of {@code app/cbl/CBSTM03A.CBL} write a hyphen rule,
     * the total band and the closing banner, in that order.</p>
     */
    public static final int CARD_TRAILER_LINE_COUNT = 3;

    /**
     * Hyphen rule lines a complete single-card statement contains.
     *
     * <p>Assumptions: the three separately declared, byte-identical hyphen rule bands are emitted
     * twice, once and three times respectively, as the class documentation sets out with the
     * emission sites. The total is stated as a constant because it is the one property of this
     * artifact that no per-line check can verify: every individual line would still match its
     * source if two of the six were dropped.</p>
     */
    public static final int HYPHEN_RULES_PER_STATEMENT = 6;

    /**
     * The blank character every declared-blank position of every band is filled with.
     *
     * <p>Assumptions: the reference declares its blank runs {@code VALUE SPACES}, and the codec
     * pads a character item's unused suffix with the blank byte of the target character set. This
     * constant is used only where this class materialises a source item at its own declared width,
     * which is a different operation from band padding and is documented at its own site.</p>
     */
    public static final char BLANK = ' ';

    /**
     * Empty content supplied for a band position the reference declares as blank.
     *
     * <p>Assumptions: the codec left-justifies character content and pads the unused suffix with
     * blanks, so supplying empty content for a declared-blank position yields exactly the run of
     * blanks the reference declares. Alternatives Considered: supplying an explicit run of the
     * position's own width. Rejected because it would restate a width the descriptor already
     * carries, and a run that disagreed with the descriptor by one character would be rejected by
     * the codec as too wide or would silently shorten the run.</p>
     */
    private static final String DECLARED_BLANK = "";

    /**
     * Marker a diagnostic rendering emits in place of a value it declines to disclose.
     *
     * <p>Assumptions: an explicit marker is emitted rather than an empty position, because an empty
     * position reads as an absent value and would send a reader looking for a hydration fault that
     * did not happen. Alternatives Considered: omitting the component name as well, so the
     * rendering named only the type. Rejected because the component name is the part a reader needs
     * in order to know which value was withheld, and it discloses nothing by itself.</p>
     */
    private static final String WITHHELD = "<withheld>";

    /**
     * Carries the once-per-card values that the header bands and the markup rendering both read.
     *
     * <p>Assumptions: every component is a character item at its <b>exact declared width, trailing
     * blanks included</b>, and nothing in it has been trimmed. That is not tidiness but a
     * requirement of the consumer: the markup rendering transfers four values reached through this
     * record up to their first PAIRED blank and then re-appends exactly two blanks, at lines 563,
     * 571, 579 and 587 of {@code app/cbl/CBSTM03A.CBL}, and it transfers three more as whole items
     * including their blanks, at lines 614, 621 and 628. A value handed over already trimmed would
     * make the first of those regimes meaningless and would shorten the second. The first of the
     * four is the narrowed name that {@link #markupName()} derives, and the narrowing keeps the
     * trailing blanks of its leftmost {@value StatementTextMapper#MARKUP_NAME_WIDTH} characters for
     * exactly that reason.</p>
     *
     * <p>Assumptions: the two amount-shaped components are already edited strings rather than
     * numbers, because the reference edits once and both artifacts read the edited item. Line 484
     * moves the balance into the mask and line 621 then embeds that same edited item in the markup,
     * so a markup rendering that formatted the number itself could disagree with the plain text
     * about the same balance.</p>
     *
     * <p>Alternatives Considered: carrying the underlying values and letting each renderer edit
     * them. Rejected for exactly the reason above; the evidence is the single storage item the
     * reference shares between lines 488 to 502 and lines 560 to 628.</p>
     *
     * @param assembledName the assembled customer name at its declared width of
     *     {@value StatementTextMapper#ASSEMBLED_NAME_WIDTH} characters, as the first band carries
     *     it; the narrower width the markup rendering receives is derived from it by
     *     {@link PreparedHeaderFields#markupName()} rather than stored beside it
     * @param addressLine1 the first address line at its declared width of
     *     {@value StatementTextMapper#ADDRESS_LINE_WIDTH} characters
     * @param addressLine2 the second address line at its declared width of
     *     {@value StatementTextMapper#ADDRESS_LINE_WIDTH} characters
     * @param assembledAddress the assembled third address line, state, country and postal code at
     *     the declared width of {@value StatementTextMapper#ASSEMBLED_ADDRESS_WIDTH} characters
     * @param accountId the account identifier item at its declared width of
     *     {@value StatementTextMapper#ACCOUNT_ID_ITEM_WIDTH} characters, being the digits followed
     *     by declared blanks
     * @param currentBalance the balance already edited under the regime that preserves leading
     *     zeros, exactly {@value StatementBandLayouts#STATEMENT_AMOUNT_LENGTH} characters with the
     *     sign in the last position
     * @param creditScore the credit score item at its declared width of
     *     {@value StatementTextMapper#CREDIT_SCORE_ITEM_WIDTH} characters, being the digits
     *     followed by declared blanks
     */
    public record PreparedHeaderFields(
            String assembledName,
            String addressLine1,
            String addressLine2,
            String assembledAddress,
            String accountId,
            String currentBalance,
            String creditScore) {

        /**
         * Validates that every component arrives at exactly the width its band item declares.
         *
         * <p>Assumptions: each component is checked for exact width rather than maximum width, and
         * the difference matters. A short value would still be padded by the codec when it reached
         * its band, so the plain-text artifact would look correct while the markup rendering
         * received a value whose trailing blanks it needed and did not have. Checking here rejects
         * that at the boundary that produced it instead of one artifact later.</p>
         *
         * @param assembledName the assembled name, exactly
         *     {@value StatementTextMapper#ASSEMBLED_NAME_WIDTH} characters
         * @param addressLine1 the first address line, exactly
         *     {@value StatementTextMapper#ADDRESS_LINE_WIDTH} characters
         * @param addressLine2 the second address line, exactly
         *     {@value StatementTextMapper#ADDRESS_LINE_WIDTH} characters
         * @param assembledAddress the assembled third address line, exactly
         *     {@value StatementTextMapper#ASSEMBLED_ADDRESS_WIDTH} characters
         * @param accountId the account identifier item, exactly
         *     {@value StatementTextMapper#ACCOUNT_ID_ITEM_WIDTH} characters
         * @param currentBalance the edited balance, exactly
         *     {@value StatementBandLayouts#STATEMENT_AMOUNT_LENGTH} characters
         * @param creditScore the credit score item, exactly
         *     {@value StatementTextMapper#CREDIT_SCORE_ITEM_WIDTH} characters
         * @throws NullPointerException if any component is {@code null}
         * @throws IllegalArgumentException if any component is not exactly the width its band item
         *     declares
         */
        public PreparedHeaderFields {
            requireExactWidth(assembledName, ASSEMBLED_NAME_WIDTH, "assembledName");
            requireExactWidth(addressLine1, ADDRESS_LINE_WIDTH, "addressLine1");
            requireExactWidth(addressLine2, ADDRESS_LINE_WIDTH, "addressLine2");
            requireExactWidth(assembledAddress, ASSEMBLED_ADDRESS_WIDTH, "assembledAddress");
            requireExactWidth(accountId, ACCOUNT_ID_ITEM_WIDTH, "accountId");

            // WHY : Assumptions: the balance is checked against the amount width of the band rather
            //       than against a width of its own, because the mask at line 113 of
            //       app/cbl/CBSTM03A.CBL and the item that receives it are the same 13 characters.
            //       A value of any other length is not a differently formatted balance but a value
            //       produced by the wrong regime -- the report regimes are 15 characters and would
            //       be caught here rather than displacing the tail of the band.
            requireExactWidth(currentBalance, StatementBandLayouts.STATEMENT_AMOUNT_LENGTH,
                    "currentBalance");
            requireExactWidth(creditScore, CREDIT_SCORE_ITEM_WIDTH, "creditScore");
        }

        /**
         * Returns the assembled name narrowed to the declared width the markup rendering receives.
         *
         * <p>Assumptions: line 560 of {@code app/cbl/CBSTM03A.CBL} moves the assembled name into a
         * narrower item inside the markup group, and line 563 then reads that narrower item. This
         * accessor performs the same narrowing on demand, so the markup rendering reads the value
         * through the same set of prepared fields the plain-text rendering reads, exactly as the
         * package charter describes.</p>
         *
         * <p>Alternatives Considered: three placements were weighed. <b>Storing the narrowed name
         * as an eighth component</b> was the original shape and is rejected: the value is a pure
         * function of {@code assembledName}, the compact constructor checked only its width and
         * never its agreement with the name it is supposed to narrow, so the record admitted an
         * instance whose two name widths disagreed about the same customer, and nothing in the type
         * could detect it. <b>Leaving the narrowing entirely to the markup rendering</b> was
         * rejected because the derivation would then live outside the type that owns the assembled
         * name, and two renderings of one name could drift apart without either being wrong on its
         * own terms. <b>Deriving it here</b> keeps one derivation, makes disagreement structurally
         * impossible, and keeps the member name the markup rendering reads.</p>
         *
         * <p>Trade-offs: the narrowing is recomputed on every call rather than computed once. That
         * cost is a bounded substring of a 75-character string with no allocation beyond the
         * result, and it buys the invariant above; caching it would reintroduce exactly the
         * stored-and-unchecked state this accessor replaces.</p>
         *
         * @return the leftmost {@value StatementTextMapper#MARKUP_NAME_WIDTH} characters of
         *     {@link #assembledName()}, trailing blanks included, never {@code null}
         */
        public String markupName() {
            return narrowNameForMarkup(assembledName);
        }

        /**
         * Renders this record for a log line or an assertion message, naming no component.
         *
         * <p>Refactoring Rationale: ALL SEVEN components are withheld now, and an earlier revision
         * named the account identifier. That revision withheld the other six with care and correct
         * reasoning, and then treated the identifier as the one safe thing left to say -- describing
         * itself as "naming only the account identifier" as though that were the conservative
         * choice. It is not: the sensitive-data logging contract in
         * {@code docs/architecture/observability.md} names account and customer identifiers in a
         * clause of their own. Withholding a name, an address, a balance and a credit score and then
         * emitting the identifier that ties them all to one account defeats most of what the other
         * six omissions bought, because a log holding the identifier per statement is the index into
         * whatever else names it.</p>
         *
         * <p>Trade-offs: the omission is unconditional because a rendering reached through string
         * concatenation, a log template or a debugger cannot be asked to remember to withhold
         * anything. Four of the seven components -- the assembled name, both address lines and the
         * assembled address -- are the account holder's name and postal address, which the migration
         * plan treats as data to be narrowed at a mapping boundary and never widened at a diagnostic
         * one. Two more are the current balance and the credit score, which the sibling view types of
         * this module also omit, so omitting them here keeps one rule across the module rather than
         * two that differ. The seventh is the account identifier, and it now joins them. The cost
         * accepted is that a reader cannot reconstruct a statement header from a log line, nor tell
         * two headers apart; the compensation is that no log line written from this record can carry a
         * name, an address, a balance, a credit score or an account identifier at all.</p>
         *
         * <p>Alternatives Considered: an opaque, non-reversible token derived from the account
         * identifier, so that two headers stayed distinguishable without the identifier being
         * disclosed -- which is what {@code com.carddemo.common.security.OpaqueIdentifier} produces.
         * Rejected here for a structural reason rather than a preference. That type is keyed: it
         * requires deployment key material supplied as a constructor argument, and {@code toString}
         * accepts no argument while a record's members are set by its canonical constructor, so the
         * only way to reach a keyed helper from here is static mutable state. A diagnostic method must
         * not depend on start-up ordering and must not throw, and static state gives it both. An
         * UNKEYED digest was considered in its place and rejected on the shared kernel's own recorded
         * analysis: an eleven-digit identifier is low-entropy enough that a guess can be hashed and
         * confirmed, so an unkeyed token would look opaque while being reversible by enumeration. A
         * withheld marker discloses nothing and claims nothing.</p>
         *
         * <p>Alternatives Considered: reporting each withheld component's length instead of its
         * content, so that a width fault could be diagnosed from a log line. Rejected because every
         * component is checked for its exact declared width by the compact constructor above, so an
         * instance that exists has only one possible length per component and the report would
         * restate seven constants.</p>
         *
         * <p>Assumptions: the marker and its wording are the ones the sibling
         * {@link PreparedTrailerFields#toString()} already uses, so a reader meets one withheld form
         * in this file rather than two, and a log line still says which value reached that point
         * without saying what it was.</p>
         *
         * @return a single-line rendering naming the type and marking its account identifier
         *     withheld; this is a diagnostic form and is never the band rendering, which
         *     {@link StatementTextMapper#emitHeaderBlock(PreparedHeaderFields)} alone produces
         */
        @Override
        public String toString() {
            return "PreparedHeaderFields[accountId=" + WITHHELD + ']';
        }
    }

    /**
     * Carries the per-transaction values that the transaction band and the markup cells both read.
     *
     * <p>Assumptions: all three components are read twice in the reference. Lines 676 to 678 of
     * {@code app/cbl/CBSTM03A.CBL} populate them, line 679 writes the plain-text band from them,
     * and lines 687, 699 and 711 build three markup cells from the same three items. The markup
     * regime there is the whole-item one, so each component is carried at its exact declared width
     * with its trailing blanks intact.</p>
     *
     * @param transactionId the transaction identifier at its declared width of
     *     {@value StatementTextMapper#TRANSACTION_ID_WIDTH} characters
     * @param description the transaction description narrowed to the declared width of
     *     {@value StatementTextMapper#DESCRIPTION_ITEM_WIDTH} characters that the band item
     *     receives
     * @param amount the transaction amount already edited under the regime that blanks leading
     *     zeros, exactly {@value StatementBandLayouts#STATEMENT_AMOUNT_LENGTH} characters with the
     *     sign in the last position
     */
    public record PreparedTransactionFields(
            String transactionId,
            String description,
            String amount) {

        /**
         * Validates that every component arrives at exactly the width its band item declares.
         *
         * @param transactionId the transaction identifier, exactly
         *     {@value StatementTextMapper#TRANSACTION_ID_WIDTH} characters
         * @param description the narrowed description, exactly
         *     {@value StatementTextMapper#DESCRIPTION_ITEM_WIDTH} characters
         * @param amount the edited amount, exactly
         *     {@value StatementBandLayouts#STATEMENT_AMOUNT_LENGTH} characters
         * @throws NullPointerException if any component is {@code null}
         * @throws IllegalArgumentException if any component is not exactly the width its band item
         *     declares
         */
        public PreparedTransactionFields {
            requireExactWidth(transactionId, TRANSACTION_ID_WIDTH, "transactionId");

            // WHY : Assumptions: the description is checked at the 49 the band item declares at
            //       line 135 of app/cbl/CBSTM03A.CBL and NOT at the 100 its source declares at line
            //       28 of app/cpy/COSTM01.CPY. A 100-character value arriving here has not been
            //       narrowed, and admitting it would push the codec into rejecting the whole band
            //       for a too-wide field rather than naming the component that was not narrowed.
            requireExactWidth(description, DESCRIPTION_ITEM_WIDTH, "description");
            requireExactWidth(amount, StatementBandLayouts.STATEMENT_AMOUNT_LENGTH, "amount");
        }

        /**
         * Renders this record for a log line or an assertion message, naming only the transaction
         * identifier.
         *
         * <p>Trade-offs: the description and the amount are omitted and the identifier is kept. The
         * identifier is kept because it locates the row exactly and carries no account holder
         * information of its own, which is the same reason the sibling view types of this module
         * keep theirs. The amount is omitted because it is a monetary figure and those view types
         * omit theirs. The description is omitted because its source is 100 characters of free text
         * transcribed from the transaction record, so what it contains is decided by whoever
         * originated the transaction rather than by this module, and a rendering cannot know
         * whether a given value names a merchant, a person or neither. The cost accepted is that a reader
         * cannot tell from a log line what a transaction was for or what it was worth.</p>
         *
         * @return a single-line rendering naming the type and the transaction identifier, with the
         *     identifier's declared trailing blanks dropped so the line reads cleanly; this is a
         *     diagnostic form and is never the band rendering, which
         *     {@link StatementTextMapper#emitTransactionLine(PreparedTransactionFields)} alone
         *     produces
         */
        @Override
        public String toString() {
            return "PreparedTransactionFields[transactionId=" + transactionId.strip() + ']';
        }
    }

    /**
     * Carries the accumulated card total that the trailer band reads.
     *
     * <p>Assumptions: unlike the header and per-transaction values, this one has no markup
     * consumer. {@code ST-TOTAL-TRAMT} occurs at exactly two places in
     * {@code app/cbl/CBSTM03A.CBL}, its declaration at line 142 and the edited move at line 434,
     * and the markup writes that follow at lines 439 to 454 emit only invariant fragments. It is
     * nonetheless exposed as a prepared value so that all three emission methods take a validated
     * record rather than two taking one and the third taking a bare string.</p>
     *
     * <p>Alternatives Considered: passing the edited total to the trailer emission as a plain
     * string parameter. Rejected because a {@code String} parameter cannot state that its argument
     * must already be edited under the regime that blanks leading zeros and must be exactly
     * {@value StatementBandLayouts#STATEMENT_AMOUNT_LENGTH} characters, whereas a record whose
     * constructor checks that width rejects an unedited or wrongly edited value at the boundary
     * rather than inside the codec, where the diagnostic names a band field rather than the total.
     * A single-component record is accepted as the cost of that.</p>
     *
     * @param cardTotal the accumulated total for the card, already edited under the regime that
     *     blanks leading zeros, exactly {@value StatementBandLayouts#STATEMENT_AMOUNT_LENGTH}
     *     characters with the sign in the last position
     */
    public record PreparedTrailerFields(String cardTotal) {

        /**
         * Validates that the total arrives at exactly the width the trailer band item declares.
         *
         * @param cardTotal the edited card total, exactly
         *     {@value StatementBandLayouts#STATEMENT_AMOUNT_LENGTH} characters
         * @throws NullPointerException if {@code cardTotal} is {@code null}
         * @throws IllegalArgumentException if {@code cardTotal} is not exactly the width the
         *     trailer band item declares
         */
        public PreparedTrailerFields {
            requireExactWidth(cardTotal, StatementBandLayouts.STATEMENT_AMOUNT_LENGTH, "cardTotal");
        }

        /**
         * Renders this record for a log line or an assertion message, naming no component.
         *
         * <p>Trade-offs: the single component is a monetary total, so a rendering that named it
         * would be a rendering of nothing but money, and the sibling view types of this module omit
         * their monetary members from their own renderings. The type name and an explicit
         * withheld marker are emitted instead of the total, so that a log line still says which
         * value reached that point without saying what it was. The alternative of leaving the
         * generated rendering in place was rejected outright: it prints the edited total verbatim,
         * which is the one thing this record must not put into a log line.</p>
         *
         * @return a single-line rendering naming the type and marking its component withheld; this
         *     is a diagnostic form and is never the band rendering, which
         *     {@link StatementTextMapper#emitCardTrailer(PreparedTrailerFields)} alone produces
         */
        @Override
        public String toString() {
            return "PreparedTrailerFields[cardTotal=" + WITHHELD + ']';
        }
    }

    /**
     * Prevents instantiation of this static assembler.
     *
     * <p>Alternatives Considered: an injectable instance, so that a caller could hold a
     * collaborator rather than call a static member. Rejected because every method here is a pure
     * transcription of a declared width, a declared literal or a declared move from
     * {@code app/cbl/CBSTM03A.CBL}, and there is no state to hold. An instance would advertise a
     * lifecycle that does not exist and would invite injection of something with nothing to inject.
     * Declaring the constructor private states that intent where the language enforces it, and the
     * class is final so no subclass can reopen the decision.</p>
     *
     * <p>Assumptions: this constructor is documented in full rather than left bare because
     * user-specified Rule 1 (Explainability) attaches its docstring obligation at line 15 to every
     * function, class and module entry point and names no visibility at all, so a private member is
     * in scope exactly as a public one is. The rule set enforces that reading as well: the
     * {@code MissingJavadocMethod} module of {@code config/checkstyle/checkstyle.xml} is configured
     * at private scope, and {@code JavadocMethod} audits every access modifier, so an undocumented
     * private constructor fails the build rather than deferring to review.</p>
     */
    private StatementTextMapper() {
    }

    /**
     * Assembles the statement name item from three name parts, each cut at its own first blank.
     *
     * <p>Assumptions: lines 462 to 469 of {@code app/cbl/CBSTM03A.CBL} concatenate
     * {@code CUST-FIRST-NAME}, a blank literal, {@code CUST-MIDDLE-NAME}, a blank literal,
     * {@code CUST-LAST-NAME} and a third blank literal into {@code ST-NAME}. Each of the three
     * parts is taken only <b>up to its own first blank</b>, which is the single most surprising
     * behaviour in this artifact: a part holding an internal blank, such as a compound given name,
     * contributes only the characters before that blank and everything after it is discarded. The
     * three trailing blank literals are unconditional and are appended even after an empty
     * part.</p>
     *
     * <p>Assumptions: the concatenation can offer {@value #NAME_ASSEMBLY_CAPACITY} characters --
     * three parts of {@value #NAME_PART_WIDTH} plus three single blanks -- to an item declared
     * {@value #ASSEMBLED_NAME_WIDTH} characters at line 91, so the reference silently discards up
     * to 3 characters when all three parts are full and none holds a blank. This method reproduces
     * that bound exactly, by keeping the leftmost {@value #ASSEMBLED_NAME_WIDTH} characters.</p>
     *
     * <p>Assumptions: the concatenation stops filling at the item's width and the reference leaves
     * the remainder of the item as it stood, which is blank because line 459 blanked the named
     * items immediately before. The result here is consequently padded out with blanks to the full
     * declared width, which reproduces that state without depending on a residue this design does
     * not have.</p>
     *
     * @param firstName the first name as its source item holds it, at most
     *     {@value #NAME_PART_WIDTH} characters
     * @param middleName the middle name as its source item holds it, at most
     *     {@value #NAME_PART_WIDTH} characters; an empty or all-blank value is valid content and
     *     contributes no characters
     * @param lastName the last name as its source item holds it, at most {@value #NAME_PART_WIDTH}
     *     characters
     * @return the assembled name at exactly {@value #ASSEMBLED_NAME_WIDTH} characters, blank-padded
     *     on the right, never {@code null}
     * @throws NullPointerException if any name part is {@code null}
     * @throws IllegalArgumentException if any name part is wider than {@value #NAME_PART_WIDTH}
     *     characters, which its source item cannot hold
     */
    public static String assembleName(String firstName, String middleName, String lastName) {
        // WHY : Assumptions: each part is cut at its first blank and the cut is NOT a trim.
        //       String.strip() and String.trim() would remove trailing blanks and leave an internal
        //       blank standing, which is the opposite of what lines 462 to 469 of
        //       app/cbl/CBSTM03A.CBL do: a part reading "Mary Ann" contributes "Mary" there, and
        //       would contribute "Mary Ann" under either of those methods.
        StringBuilder assembled = new StringBuilder(NAME_ASSEMBLY_CAPACITY);
        assembled.append(upToFirstBlank(firstName, NAME_PART_WIDTH, "firstName")).append(BLANK);
        assembled.append(upToFirstBlank(middleName, NAME_PART_WIDTH, "middleName")).append(BLANK);
        assembled.append(upToFirstBlank(lastName, NAME_PART_WIDTH, "lastName")).append(BLANK);

        // WHY : Assumptions: the three blank literals are appended unconditionally, including after
        //       an empty middle part, because each is a separate operand of the concatenation at
        //       lines 463, 465 and 467 rather than a separator emitted between non-empty parts. An
        //       empty middle part therefore yields two consecutive blanks, and that PAIRED blank is
        //       load-bearing downstream: the markup regime at line 563 truncates at the first
        //       paired blank, so the markup name cell shows the first name alone while this
        //       artifact's first band still shows the whole paired-blank name.
        // WHY : Assumptions: that asymmetry is the REFERENCE's own and is reproduced here exactly,
        //       so it is an observation about the baseline rather than a departure from it. It is
        //       nevertheless recorded, as D-STMT-PAIRED-BLANK-NAME in
        //       docs/architecture/cobol-to-service-traceability.md, and the entry's own category
        //       line says it is reproduced rather than diverged. Recording it is what makes a later
        //       collapse of the paired blank visibly a CHANGE: an artifact this odd is otherwise
        //       repaired on sight, and the repair would then differ from the golden statement in
        //       the bytes of its first band with nothing to point at.
        // WHY : Trade-offs: the blanks are emitted as the reference emits them rather than
        //       collapsed, so this class does not attempt to make the two artifacts agree on a name
        //       whose middle part is empty. Collapsing them would change the bytes of the first
        //       band against a golden master, which AAP Rule T9 (structure changes, behaviour does
        //       not) forbids; the accepted cost is that the two renderings of one name genuinely
        //       differ in that case, exactly as they do in the reference. The same exposure applies
        //       to the assembled address, whose four parts are joined the same way and
        //       right-trimmed at line 587.
        return atDeclaredWidth(assembled.toString(), ASSEMBLED_NAME_WIDTH);
    }

    /**
     * Assembles the statement address item from a street line, state, country and postal code.
     *
     * <p>Assumptions: lines 472 to 481 of {@code app/cbl/CBSTM03A.CBL} concatenate
     * {@code CUST-ADDR-LINE-3}, {@code CUST-ADDR-STATE-CD}, {@code CUST-ADDR-COUNTRY-CD} and
     * {@code CUST-ADDR-ZIP} into {@code ST-ADD3}, each part followed by one blank literal. That is
     * <b>four</b> parts and <b>four</b> separators, including a trailing blank after the postal
     * code, and each part is cut at its own first blank exactly as the name parts are.</p>
     *
     * <p>Assumptions: at full occupancy the concatenation offers
     * {@value #ADDRESS_ASSEMBLY_CAPACITY} characters -- 50 plus 1, plus 2 plus 1, plus 3 plus 1,
     * plus 10 plus 1 -- to an item declared {@value #ASSEMBLED_ADDRESS_WIDTH} characters at line
     * 100. <b>This assembly therefore cannot overflow, unlike the name assembly, which is 3
     * characters short of its own capacity.</b> The asymmetry is stated so that nobody adds a
     * defensive truncation here: there is nothing for it to remove, and adding one would only
     * create a second place where a width could be got wrong.</p>
     *
     * <p>Assumptions: the two address lines above this one are plain moves rather than
     * concatenations, at lines 470 and 471, so no blank-delimiter logic applies to them. Their
     * source and target widths are both {@value #ADDRESS_LINE_WIDTH}, so they transfer whole. This
     * method deliberately covers only the third line, which is the only one assembled.</p>
     *
     * @param addressLine3 the third address line as its source item holds it, at most
     *     {@value #ADDRESS_LINE_WIDTH} characters
     * @param stateCode the state code as its source item holds it, at most
     *     {@value #STATE_CODE_WIDTH} characters
     * @param countryCode the country code as its source item holds it, at most
     *     {@value #COUNTRY_CODE_WIDTH} characters
     * @param postalCode the postal code as its source item holds it, at most
     *     {@value #POSTAL_CODE_WIDTH} characters
     * @return the assembled address at exactly {@value #ASSEMBLED_ADDRESS_WIDTH} characters,
     *     blank-padded on the right, never {@code null}
     * @throws NullPointerException if any part is {@code null}
     * @throws IllegalArgumentException if any part is wider than the width its source item declares
     */
    public static String assembleAddressLine3(String addressLine3, String stateCode,
            String countryCode, String postalCode) {
        StringBuilder assembled = new StringBuilder(ADDRESS_ASSEMBLY_CAPACITY);
        assembled.append(upToFirstBlank(addressLine3, ADDRESS_LINE_WIDTH, "addressLine3"))
                .append(BLANK);
        assembled.append(upToFirstBlank(stateCode, STATE_CODE_WIDTH, "stateCode")).append(BLANK);
        assembled.append(upToFirstBlank(countryCode, COUNTRY_CODE_WIDTH, "countryCode"))
                .append(BLANK);
        assembled.append(upToFirstBlank(postalCode, POSTAL_CODE_WIDTH, "postalCode")).append(BLANK);

        // WHY : Assumptions: the result is still passed through the declared-width materialisation
        //       even though 69 cannot exceed 80, because the item must arrive at its full 80 for
        //       the whole-item markup regime and for the band placement. The truncation branch of
        //       that helper is unreachable from here, which is a property of the arithmetic above
        //       rather than of the helper, and is why no separate no-truncate variant exists.
        return atDeclaredWidth(assembled.toString(), ASSEMBLED_ADDRESS_WIDTH);
    }

    /**
     * Narrows the assembled name to the declared width the markup rendering receives.
     *
     * <p>Assumptions: line 560 of {@code app/cbl/CBSTM03A.CBL} moves {@code ST-NAME}, declared
     * {@value #ASSEMBLED_NAME_WIDTH} characters at line 91, into {@code L23-NAME}, declared
     * {@value #MARKUP_NAME_WIDTH} characters at line 220 inside the group opened at line 217. A
     * character move into a narrower item keeps the <b>leftmost</b> characters, so the last 25 are
     * discarded.</p>
     *
     * <p>Assumptions: the narrowing is unconditional and is not decided by content. The source is
     * read exactly as it stands, so a name whose 75 characters end in blanks yields 50 characters
     * that may themselves end in blanks, and that is correct rather than wasteful: those trailing
     * blanks are what the markup regime at line 563 examines when it looks for a paired blank.</p>
     *
     * @param assembledName the assembled name at exactly {@value #ASSEMBLED_NAME_WIDTH} characters,
     *     as {@link #assembleName(String, String, String)} returns it
     * @return the leftmost {@value #MARKUP_NAME_WIDTH} characters, never {@code null}
     * @throws NullPointerException if {@code assembledName} is {@code null}
     * @throws IllegalArgumentException if {@code assembledName} is not exactly
     *     {@value #ASSEMBLED_NAME_WIDTH} characters, since a value of any other width did not come
     *     from the assembly this narrowing follows
     */
    public static String narrowNameForMarkup(String assembledName) {
        requireExactWidth(assembledName, ASSEMBLED_NAME_WIDTH, "assembledName");
        return leftmostCharacters(assembledName, MARKUP_NAME_WIDTH);
    }

    /**
     * Renders the account identifier into the declared width of its band item.
     *
     * <p>Assumptions: line 483 of {@code app/cbl/CBSTM03A.CBL} moves {@code ACCT-ID}, declared
     * {@code PIC 9(11)} at line 5 of {@code app/cpy/CVACT01Y.cpy}, into {@code ST-ACCT-ID},
     * declared {@code PIC X(20)} at line 109 of that same program. The target is a character item
     * and is <b>wider</b> than its numeric-display source, so the 11 digits land left-justified
     * with 9 trailing <b>blanks</b>.</p>
     *
     * <p>Assumptions: this is the <b>exact opposite</b> of the same source item's behaviour in the
     * daily transaction report, and the contrast is stated here because a reader arriving from that
     * side will otherwise carry the wrong expectation. There, the account identifier moves into
     * a character item of the same 11 characters, so the digit form transfers whole with its
     * leading zeros and the report prints eleven digits and nothing else. Here the receiving item
     * is 20, so COBOL left-justifies and blank-pads on the right. One source field, two renderings,
     * both correct: <b>blank-padded here, not zero-padded</b>. The leading zeros of the 11 digits
     * are present in both; it is the nine characters after them that differ, and they are blanks
     * rather than more zeros.</p>
     *
     * @param accountId the account identifier as a whole number; must not be negative and must fit
     *     {@value #ACCOUNT_ID_DIGITS} digit positions
     * @return the account identifier item at exactly {@value #ACCOUNT_ID_ITEM_WIDTH} characters,
     *     being {@value #ACCOUNT_ID_DIGITS} zero-padded digits followed by declared blanks, never
     *     {@code null}
     * @throws IllegalArgumentException if {@code accountId} is negative, which the unsigned source
     *     picture has no position to show
     * @throws ArithmeticException if {@code accountId} needs more than {@value #ACCOUNT_ID_DIGITS}
     *     digits, because rendering it would discard its high-order digits
     */
    public static String renderAccountIdItem(long accountId) {
        // WHY : Assumptions: the digits come from the unsigned display regime rather than from a
        //       general-purpose integral rendering, because the source picture at line 5 of
        //       app/cpy/CVACT01Y.cpy declares digit positions and not suppression positions, so an
        //       identifier below ten thousand million keeps its leading zeros. A plain integral
        //       rendering would drop them and shift every following character of the item.
        String digits = CobolEditMask.formatUnsignedDigits(accountId, ACCOUNT_ID_DIGITS);
        return atDeclaredWidth(digits, ACCOUNT_ID_ITEM_WIDTH);
    }

    /**
     * Renders the credit score into the declared width of its band item.
     *
     * <p>Assumptions: line 485 of {@code app/cbl/CBSTM03A.CBL} moves
     * {@code CUST-FICO-CREDIT-SCORE}, declared {@code PIC 9(03)} at line 22 of
     * {@code app/cpy/CUSTREC.cpy}, into {@code ST-FICO-SCORE}, declared {@code PIC X(20)} at line
     * 118. Three digits therefore land left-justified with 17 trailing blanks, by the same
     * wider-target rule as the account identifier above.</p>
     *
     * @param creditScore the credit score as a whole number; must not be negative and must fit
     *     {@value #CREDIT_SCORE_DIGITS} digit positions
     * @return the credit score item at exactly {@value #CREDIT_SCORE_ITEM_WIDTH} characters, being
     *     {@value #CREDIT_SCORE_DIGITS} zero-padded digits followed by 17 declared blanks, never
     *     {@code null}
     * @throws IllegalArgumentException if {@code creditScore} is negative, which the unsigned
     *     source picture has no position to show
     * @throws ArithmeticException if {@code creditScore} needs more than
     *     {@value #CREDIT_SCORE_DIGITS} digits
     */
    public static String renderCreditScoreItem(int creditScore) {
        String digits = CobolEditMask.formatUnsignedDigits(creditScore, CREDIT_SCORE_DIGITS);
        return atDeclaredWidth(digits, CREDIT_SCORE_ITEM_WIDTH);
    }

    /**
     * Narrows the transaction description to the declared width of its band item.
     *
     * <p>Assumptions: line 677 of {@code app/cbl/CBSTM03A.CBL} moves {@code TRNX-DESC}, declared
     * {@code PIC X(100)} at line 28 of {@code app/cpy/COSTM01.CPY}, into {@code ST-TRANDT},
     * declared {@code PIC X(49)} at line 135 of that same program. A character move into a narrower
     * item keeps the <b>leftmost</b> characters, so the last 51 are discarded.</p>
     *
     * <p>Assumptions: the source item is read exactly as it stands and is <b>not</b> trimmed first.
     * The reference truncates the declared-width item, so a description whose 100 characters end in
     * blanks yields 49 characters that may themselves end in blanks -- and a description longer
     * than 49 whose 50th character is a blank still loses everything from position 50 on, blank or
     * not. Trimming before narrowing would change which characters survive for any description
     * holding a blank near the boundary, which is precisely the population a byte comparison would
     * flag.</p>
     *
     * @param description the transaction description as its source item holds it, at most
     *     {@value #DESCRIPTION_SOURCE_WIDTH} characters
     * @return the description item at exactly {@value #DESCRIPTION_ITEM_WIDTH} characters,
     *     blank-padded on the right when the source is shorter, never {@code null}
     * @throws NullPointerException if {@code description} is {@code null}
     * @throws IllegalArgumentException if {@code description} is wider than
     *     {@value #DESCRIPTION_SOURCE_WIDTH} characters, which its source item cannot hold
     */
    public static String renderDescriptionItem(String description) {
        requireAtMost(description, DESCRIPTION_SOURCE_WIDTH, "description");

        // WHY : Assumptions: this value is UNTRUSTED free text and it is deliberately NOT escaped
        //       here. app/cpy/CVTRA05Y.cpy line 9 declares TRAN-DESC PIC X(100), so every character
        //       in the code page is admissible in it, and a client supplies it through the
        //       transaction-add request. It reaches two artifacts and only one of them has a grammar
        //       a character can be significant in.
        // WHY : Trade-offs: escaping at this shared point was weighed and rejected, and the reason is
        //       the reason the value is left alone. The item this method produces is the plain-text
        //       statement's own content, which is not markup and which a byte comparison against the
        //       recorded golden output runs on -- so escaping here would break parity on the one side
        //       that has an oracle, in order to protect a side that is not this one. Escaping belongs
        //       at each sink, and the markup sink performs it: StatementHtmlMapper routes every value
        //       it embeds through the shared HtmlTextEncoder text-node encoding, and the divergence
        //       that creates is registered as D-STMT-HTML-ESCAPING in
        //       docs/architecture/cobol-to-service-traceability.md.
        // WHY : Assumptions: the consequence for a consumer of this artifact is stated because it is
        //       not obvious from the bytes. The plain-text statement is text and must be delivered as
        //       text; rendering it as markup would reintroduce exactly what the markup artifact's
        //       encoding removes, and no encoding on this side would prevent that.

        // WHY : Assumptions: narrowing and blank-padding are one operation here because the source
        //       is a declared-width item. A description of 20 characters occupies the first 20 of
        //       the source's 100 and the following 80 are blanks, so the leftmost 49 of that item
        //       are the 20 characters plus 29 blanks -- which is what materialising 20 characters
        //       at the 49 of the target produces. Treating the two cases separately would state the
        //       same result twice with two chances to disagree.
        return atDeclaredWidth(leftmostCharacters(description, DESCRIPTION_ITEM_WIDTH),
                DESCRIPTION_ITEM_WIDTH);
    }

    /**
     * Prepares the once-per-card values that the header bands and the markup rendering both read.
     *
     * <p>Assumptions: this method reproduces the field preparation of lines 462 to 485 of
     * {@code app/cbl/CBSTM03A.CBL} in the order the reference performs it, and it emits nothing.
     * The separation of preparation from emission is what makes one preparation serve two
     * renderings: the reference populates {@code 01 STATEMENT-LINES.} once, writes the plain-text
     * bands from it at lines 488 to 502 and reads the same items into markup at lines 560 to
     * 628.</p>
     *
     * <p>Assumptions: the parameter list mirrors the source items one for one rather than taking a
     * carrier type, which follows the convention the sibling projection mapper in this package
     * already applies to its own statement summary. Trade-offs: a list of this length is more
     * error-prone at a call site than named components would be, and the compensation accepted for
     * it is that every parameter below states the item it comes from and the width that item
     * declares, so a transposed pair is caught by the width check of the very next parameter unless
     * the two happen to share a width. The two address lines do share {@value #ADDRESS_LINE_WIDTH},
     * which is stated here so that the residual risk is on the record rather than implied.</p>
     *
     * @param firstName the first name from its {@value #NAME_PART_WIDTH} -character source item
     * @param middleName the middle name from its {@value #NAME_PART_WIDTH} -character source item
     * @param lastName the last name from its {@value #NAME_PART_WIDTH} -character source item
     * @param addressLine1 the first address line from its {@value #ADDRESS_LINE_WIDTH} -character
     *     source item, moved whole rather than assembled
     * @param addressLine2 the second address line from its {@value #ADDRESS_LINE_WIDTH} -character
     *     source item, moved whole rather than assembled
     * @param addressLine3 the third address line from its {@value #ADDRESS_LINE_WIDTH} -character
     *     source item, which is assembled with the three parts below it
     * @param stateCode the state code from its {@value #STATE_CODE_WIDTH} -character source item
     * @param countryCode the country code from its {@value #COUNTRY_CODE_WIDTH} -character source
     *     item
     * @param postalCode the postal code from its {@value #POSTAL_CODE_WIDTH} -character source item
     * @param accountId the account identifier as a whole number, within {@value #ACCOUNT_ID_DIGITS}
     *     digit positions
     * @param currentBalance the exact current balance at scale two, whose magnitude must be below
     *     one thousand million so that the nine integer positions of its mask can hold it
     * @param creditScore the credit score as a whole number, within {@value #CREDIT_SCORE_DIGITS}
     *     digit positions
     * @return the prepared header values, every one at the exact declared width of the band item
     *     that receives it, never {@code null}
     * @throws NullPointerException if any character parameter or {@code currentBalance} is
     *     {@code null}
     * @throws IllegalArgumentException if a character parameter is wider than the source item it
     *     comes from, or if {@code accountId} or {@code creditScore} is negative
     * @throws ArithmeticException if {@code accountId} or {@code creditScore} needs more digit
     *     positions than its source item declares, or if {@code currentBalance} needs more than
     *     nine integer positions or does not carry exactly two decimal places
     */
    public static PreparedHeaderFields prepareHeaderFields(
            String firstName,
            String middleName,
            String lastName,
            String addressLine1,
            String addressLine2,
            String addressLine3,
            String stateCode,
            String countryCode,
            String postalCode,
            long accountId,
            Money currentBalance,
            int creditScore) {
        Objects.requireNonNull(currentBalance, "currentBalance must not be null");

        String assembledName = assembleName(firstName, middleName, lastName);

        // WHY : Refactoring Rationale: the narrowed markup name is NOT computed here and NOT
        //       carried as a component. Line 560 of app/cbl/CBSTM03A.CBL performs that narrowing inside the
        //       markup paragraph, and its input is the assembled name this method produces, so the
        //       returned record derives it on demand through PreparedHeaderFields.markupName()
        //       instead. Storing it here made the record hold two widths of one name with no check
        //       that they agreed, and a caller reaching the canonical constructor directly could
        //       pair the narrowing of one name with another name entirely.

        // WHY : Assumptions: lines 470 and 471 are plain moves and not concatenations, so no
        //       blank-delimiter rule applies to the first two address lines. Source and target are
        //       both ADDRESS_LINE_WIDTH, at lines 9 and 10 of app/cpy/CUSTREC.cpy and lines 94 and
        //       97 of app/cbl/CBSTM03A.CBL, so each transfers whole and only materialisation at the
        //       declared width remains.
        requireAtMost(addressLine1, ADDRESS_LINE_WIDTH, "addressLine1");
        requireAtMost(addressLine2, ADDRESS_LINE_WIDTH, "addressLine2");

        // WHY : Trade-offs: a balance needing more than nine integer positions is REJECTED here, by
        //       the mask, rather than narrowed. The reference behaves differently: line 484 moves
        //       ACCT-CURR-BAL, declared PIC S9(10)V99 with TEN integer digits at line 7 of
        //       app/cpy/CVACT01Y.cpy, into ST-CURR-BAL, declared PIC 9(9).99- with NINE at line
        //       113, and silently discards one high-order digit for any balance of a thousand
        //       million or more. The Java raises instead, and the divergence is registered as
        //       D-EDIT-MASK-OVERFLOW in docs/architecture/cobol-to-service-traceability.md section
        //       7.4, cited by identifier because a registration claim naming only a path cannot be
        //       checked against the register. The compromise accepted is that such a balance
        //       produces no statement at all rather than a plausible one: a nine-digit string that
        //       silently dropped the leading digit would understate a balance by at least a
        //       thousand million while filling the item and parsing cleanly, and no downstream
        //       check could distinguish it from a correct value.
        String editedBalance = CobolEditMask.formatStatementBalance(currentBalance);

        return new PreparedHeaderFields(
                assembledName,
                atDeclaredWidth(addressLine1, ADDRESS_LINE_WIDTH),
                atDeclaredWidth(addressLine2, ADDRESS_LINE_WIDTH),
                assembleAddressLine3(addressLine3, stateCode, countryCode, postalCode),
                renderAccountIdItem(accountId),
                editedBalance,
                renderCreditScoreItem(creditScore));
    }

    /**
     * Prepares the per-transaction values that the transaction band and the markup cells both read.
     *
     * <p>Assumptions: this method reproduces the three moves of lines 676 to 678 of
     * {@code app/cbl/CBSTM03A.CBL} and emits nothing, for the same populate-once reason as the
     * header preparation above: line 679 writes the plain-text band from these items and lines 687,
     * 699 and 711 build three markup cells from the same three.</p>
     *
     * @param transactionId the transaction identifier from its {@value #TRANSACTION_ID_WIDTH}
     *     -character source item, which the band item receives at the same width
     * @param description the transaction description from its {@value #DESCRIPTION_SOURCE_WIDTH}
     *     -character source item, which the band narrows
     * @param amount the exact transaction amount at scale two, whose magnitude must be below one
     *     thousand million
     * @return the prepared transaction values, each at the exact declared width of the band item
     *     that receives it, never {@code null}
     * @throws NullPointerException if {@code transactionId}, {@code description} or {@code amount}
     *     is {@code null}
     * @throws IllegalArgumentException if {@code transactionId} or {@code description} is wider
     *     than the source item it comes from
     * @throws ArithmeticException if {@code amount} needs more than nine integer positions or does
     *     not carry exactly two decimal places
     */
    public static PreparedTransactionFields prepareTransactionFields(String transactionId,
            String description, Money amount) {
        requireAtMost(transactionId, TRANSACTION_ID_WIDTH, "transactionId");
        Objects.requireNonNull(amount, "amount must not be null");

        // WHY : Assumptions: the amount uses the regime that BLANKS leading zeros, because line 137
        //       of app/cbl/CBSTM03A.CBL declares suppression positions where line 113 declares
        //       digit positions for the balance. The two masks are the same 13 characters and
        //       otherwise the same shape, so calling the balance regime here would still fill the
        //       item and still read as the same number while differing from the reference in up to
        //       nine bytes.
        return new PreparedTransactionFields(
                atDeclaredWidth(transactionId, TRANSACTION_ID_WIDTH),
                renderDescriptionItem(description),
                CobolEditMask.formatStatementAmount(amount));
    }

    /**
     * Prepares the accumulated card total that the trailer band reads.
     *
     * <p>Assumptions: the total arrives already accumulated and this method neither adds nor
     * rounds. The reference accumulates into {@code WS-TOTAL-AMT} at line 429 of
     * {@code app/cbl/CBSTM03A.CBL}, moves it through {@code WS-TRN-AMT} at line 433 and only then
     * edits it at line 434, so the arithmetic sits outside the preparation there as it does
     * here.</p>
     *
     * <p>Assumptions: the total uses the same regime as the per-transaction amount, because line
     * 142 of {@code app/cbl/CBSTM03A.CBL} declares {@code ST-TOTAL-TRAMT} with the same suppression
     * positions as line 137 of that program, and both source items carry nine integer digits --
     * {@code PIC S9(09)V99} at line 29 of {@code app/cpy/COSTM01.CPY} for the transaction amount
     * and {@code PIC S9(9)V99 COMP-3} at line 65 of the program itself for the accumulator. Nine
     * into nine is an exact fit, so no narrowing arises on this path.</p>
     *
     * @param cardTotal the exact accumulated total for the card at scale two, whose magnitude must
     *     be below one thousand million
     * @return the prepared trailer value at the exact declared width of the band item that receives
     *     it, never {@code null}
     * @throws NullPointerException if {@code cardTotal} is {@code null}
     * @throws ArithmeticException if {@code cardTotal} needs more than nine integer positions or
     *     does not carry exactly two decimal places
     */
    public static PreparedTrailerFields prepareCardTrailerFields(Money cardTotal) {
        Objects.requireNonNull(cardTotal, "cardTotal must not be null");
        return new PreparedTrailerFields(CobolEditMask.formatStatementAmount(cardTotal));
    }

    /**
     * Emits the opening banner and the header block of one card statement.
     *
     * <p>Assumptions: the sequence below transcribes line 460 of {@code app/cbl/CBSTM03A.CBL},
     * which writes the opening banner, followed by the fifteen consecutive writes at lines 488 to
     * 502, in exactly that order. The order is the artifact, so it is written out as sixteen
     * statements rather than driven from a table of band names: a table would let the sequence be
     * reordered without any line changing, and the reordering would be invisible to every per-line
     * check.</p>
     *
     * <p>Assumptions: two bands appear twice in this block and neither repetition is collapsed. The
     * first hyphen rule band is written at line 492 and again at line 494, once on each side of the
     * basic-details heading, and the third hyphen rule band is written at line 500 and again at
     * line 502, once on each side of the column headings. Together with the second hyphen rule band
     * at line 498 and the third band's further emission in the card trailer at line 435, a complete
     * statement carries {@value #HYPHEN_RULES_PER_STATEMENT} hyphen rules. Removing either
     * repetition would leave every remaining line byte-correct and the statement two lines
     * short.</p>
     *
     * @param fields the prepared header values, as {@link #prepareHeaderFields} returns them
     * @return the {@value #HEADER_BLOCK_LINE_COUNT} lines of the block in emission order, each
     *     exactly {@value StatementBandLayouts#STATEMENT_LINE_LENGTH} bytes and freshly allocated,
     *     never {@code null}
     * @throws NullPointerException if {@code fields} is {@code null}
     * @throws IllegalArgumentException if a band descriptor and the content supplied for it
     *     disagree on the number of fields, which reports a defect in this class rather than a
     *     fault of the caller
     * @throws CopybookLayout.LayoutException if a band descriptor is not a valid layout
     * @throws FixedWidthCodec.FieldCodecException if a band field cannot be encoded, in particular
     *     when supplied content is wider than the position that receives it
     */
    public static List<byte[]> emitHeaderBlock(PreparedHeaderFields fields) {
        Objects.requireNonNull(fields, "fields must not be null");

        // WHY : Assumptions: every position of every band is supplied explicitly, including the
        //       runs the reference declares as blanks. Line 459 of app/cbl/CBSTM03A.CBL initialises
        //       the band storage and COBOL's INITIALIZE skips FILLER, so there the literal runs and
        //       label texts survive from storage layout and only the named items blank. A rebuild
        //       that starts from nothing has no such residue. The codec reinforces the obligation:
        //       it blanks an absent position only for a layout its own registry holds, and these
        //       bands are declared locally, so an omitted position is reported as a missing
        //       required field.
        return List.of(
                encodeBand(StatementBandLayouts.ST_LINE0,
                        StatementBandLayouts.OPENING_ASTERISK_RUN,
                        StatementBandLayouts.START_OF_STATEMENT_SENTINEL,
                        StatementBandLayouts.OPENING_ASTERISK_RUN),
                encodeBand(StatementBandLayouts.ST_LINE1, fields.assembledName(), DECLARED_BLANK),
                encodeBand(StatementBandLayouts.ST_LINE2, fields.addressLine1(), DECLARED_BLANK),
                encodeBand(StatementBandLayouts.ST_LINE3, fields.addressLine2(), DECLARED_BLANK),
                encodeBand(StatementBandLayouts.ST_LINE4, fields.assembledAddress()),
                encodeBand(StatementBandLayouts.ST_LINE5, StatementBandLayouts.HYPHEN_RULE),
                encodeBand(StatementBandLayouts.ST_LINE6, DECLARED_BLANK,
                        StatementBandLayouts.BASIC_DETAILS_HEADING, DECLARED_BLANK),
                encodeBand(StatementBandLayouts.ST_LINE5, StatementBandLayouts.HYPHEN_RULE),
                encodeBand(StatementBandLayouts.ST_LINE7, StatementBandLayouts.ACCOUNT_ID_LABEL,
                        fields.accountId(), DECLARED_BLANK),

                // WHY : Assumptions: this band ends in TWO consecutive declared-blank positions, of
                //       7 and 40 characters at lines 114 and 115 of app/cbl/CBSTM03A.CBL, and both
                //       are supplied. They are two separate declarations rather than one item of
                //       47. Supplying a single value for a merged 47 would tile the band correctly
                //       and pass every geometry check, which is exactly why the split is honoured
                //       here: the error would be invisible mechanically and visible only as an
                //       assembly that no longer transcribes its source.
                encodeBand(StatementBandLayouts.ST_LINE8,
                        StatementBandLayouts.CURRENT_BALANCE_LABEL, fields.currentBalance(),
                        DECLARED_BLANK, DECLARED_BLANK),
                encodeBand(StatementBandLayouts.ST_LINE9, StatementBandLayouts.FICO_SCORE_LABEL,
                        fields.creditScore(), DECLARED_BLANK),
                encodeBand(StatementBandLayouts.ST_LINE10, StatementBandLayouts.HYPHEN_RULE),
                encodeBand(StatementBandLayouts.ST_LINE11, DECLARED_BLANK,
                        StatementBandLayouts.TRANSACTION_SUMMARY_HEADING, DECLARED_BLANK),
                encodeBand(StatementBandLayouts.ST_LINE12, StatementBandLayouts.HYPHEN_RULE),
                encodeBand(StatementBandLayouts.ST_LINE13, StatementBandLayouts.TRAN_ID_HEADING,
                        StatementBandLayouts.TRAN_DETAILS_HEADING,
                        StatementBandLayouts.TRAN_AMOUNT_HEADING),
                encodeBand(StatementBandLayouts.ST_LINE12, StatementBandLayouts.HYPHEN_RULE));
    }

    /**
     * Emits one transaction line of a card statement.
     *
     * <p>Assumptions: line 679 of {@code app/cbl/CBSTM03A.CBL} writes exactly one band per
     * transaction, so this method returns one line rather than a list. The reference calls its
     * paragraph once per transaction, from the inner loop at line 428, and each call writes one
     * record.</p>
     *
     * <p>Assumptions: the two single-character positions between the named items are declared
     * content and not gaps. The blank at line 134 is declared with an explicit blank value, and the
     * currency character at line 136 is its own one-character position standing immediately before
     * the amount -- <b>it is not part of the amount mask</b>, which declares no currency position
     * at all. A formatter that emitted a currency character would return 14 characters for a
     * 13-character item and displace the tail of the band.</p>
     *
     * @param fields the prepared transaction values, as
     *     {@link #prepareTransactionFields(String, String, Money)} returns them
     * @return one line of exactly {@value StatementBandLayouts#STATEMENT_LINE_LENGTH} bytes,
     *     freshly allocated, never {@code null}
     * @throws NullPointerException if {@code fields} is {@code null}
     * @throws IllegalArgumentException if the band descriptor and the content supplied for it
     *     disagree on the number of fields, which reports a defect in this class rather than a
     *     fault of the caller
     * @throws CopybookLayout.LayoutException if the band descriptor is not a valid layout
     * @throws FixedWidthCodec.FieldCodecException if a band field cannot be encoded
     */
    public static byte[] emitTransactionLine(PreparedTransactionFields fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        return encodeBand(StatementBandLayouts.ST_LINE14,
                fields.transactionId(),
                StatementBandLayouts.SINGLE_BLANK,
                fields.description(),
                StatementBandLayouts.CURRENCY_SYMBOL,
                fields.amount());
    }

    /**
     * Emits the card trailer, being a hyphen rule, the total band and the closing banner.
     *
     * <p>Assumptions: lines 435, 436 and 437 of {@code app/cbl/CBSTM03A.CBL} write those three
     * bands in that order, inside {@code 4000-TRNXFILE-GET} at line 416 and immediately after the
     * per-card transaction loop has ended. The hyphen rule written here is the third of the three
     * separately declared rule bands and is its third emission of the statement, which is what
     * makes the total {@value #HYPHEN_RULES_PER_STATEMENT} rather than five.</p>
     *
     * <p>Assumptions: the closing banner's asterisk runs are
     * {@value StatementBandLayouts#CLOSING_ASTERISK_RUN_LENGTH} characters at each end, one wider
     * than the opening banner's {@value StatementBandLayouts#OPENING_ASTERISK_RUN_LENGTH}, because
     * its sentinel text is two characters shorter. The two banners are equal in total width and
     * unequal in composition, so one run length does not serve both.</p>
     *
     * @param fields the prepared trailer value, as {@link #prepareCardTrailerFields(Money)} returns
     *     it
     * @return the {@value #CARD_TRAILER_LINE_COUNT} lines of the trailer in emission order, each
     *     exactly {@value StatementBandLayouts#STATEMENT_LINE_LENGTH} bytes and freshly allocated,
     *     never {@code null}
     * @throws NullPointerException if {@code fields} is {@code null}
     * @throws IllegalArgumentException if a band descriptor and the content supplied for it
     *     disagree on the number of fields, which reports a defect in this class rather than a
     *     fault of the caller
     * @throws CopybookLayout.LayoutException if a band descriptor is not a valid layout
     * @throws FixedWidthCodec.FieldCodecException if a band field cannot be encoded
     */
    public static List<byte[]> emitCardTrailer(PreparedTrailerFields fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        return List.of(
                encodeBand(StatementBandLayouts.ST_LINE12, StatementBandLayouts.HYPHEN_RULE),

                // WHY : Assumptions: the amount of this band sits at the same offset as the amount
                //       of the transaction band above it, because the 56 declared blanks at line
                //       140 of app/cbl/CBSTM03A.CBL are chosen to put it there. That is what
                //       columns the total under the detail amounts, so the blank run is content of
                //       the artifact rather than filler that could be resized.
                encodeBand(StatementBandLayouts.ST_LINE14A,
                        StatementBandLayouts.TOTAL_EXPENDITURE_LABEL,
                        DECLARED_BLANK,
                        StatementBandLayouts.CURRENCY_SYMBOL,
                        fields.cardTotal()),
                encodeBand(StatementBandLayouts.ST_LINE15,
                        StatementBandLayouts.CLOSING_ASTERISK_RUN,
                        StatementBandLayouts.END_OF_STATEMENT_SENTINEL,
                        StatementBandLayouts.CLOSING_ASTERISK_RUN));
    }

    /**
     * Places one band's content into an exact-width record through the shared codec.
     *
     * <p>Assumptions: content is supplied <b>positionally, in the band's own declaration order</b>,
     * and is bound to field names by walking {@link CopybookLayout.RecordSpec#fields()}. That order
     * is the order the reference declares the band's items, so a call site reads straight down the
     * band as {@code app/cbl/CBSTM03A.CBL} writes it.</p>
     *
     * <p>Alternatives Considered: having each call site build its own name-keyed map. Rejected
     * because the codec keys by exact field name and reports an omitted position as a missing
     * required field, so seventeen hand-built maps would give seventeen opportunities to misspell a
     * name that only differs by an occurrence ordinal -- the padding and literal positions are
     * named by ordinal precisely because five bands carry several of them with different content.
     * Binding positionally cannot misspell a name at all, and the count check below turns a wrong
     * number of values into an immediate rejection instead of a missing-field diagnostic one layer
     * down.</p>
     *
     * <p>Alternatives Considered: assembling each line by concatenating its parts and skipping the
     * codec. Rejected because the codec is the single placement mechanism for this module, it
     * guarantees a result of exactly {@link CopybookLayout.RecordSpec#reclen()} bytes, and it
     * rejects content wider than the position receiving it. Concatenation would silently produce a
     * line of the wrong length whenever any one part were the wrong width, and the wrong length is
     * the one error a per-field review cannot see.</p>
     *
     * @param band the band descriptor whose fields define every target position
     * @param contents the band's content in declaration order, one value per declared field; empty
     *     content is valid and yields the run of blanks the reference declares for that position
     * @return a freshly allocated record of exactly {@link CopybookLayout.RecordSpec#reclen()}
     *     bytes, which is {@value StatementBandLayouts#STATEMENT_LINE_LENGTH} for every statement
     *     band
     * @throws IllegalArgumentException if the number of supplied values differs from the number of
     *     declared fields, which reports a defect in this class rather than a fault of the caller
     * @throws CopybookLayout.LayoutException if the band descriptor is not a valid layout
     * @throws FixedWidthCodec.FieldCodecException if any value cannot be encoded into its position,
     *     in particular when it is wider than that position
     */
    private static byte[] encodeBand(CopybookLayout.RecordSpec band, String... contents) {
        List<CopybookLayout.FieldSpec> declared = band.fields();
        if (contents.length != declared.size()) {
            throw new IllegalArgumentException("band " + band.name() + " declares "
                    + declared.size() + " fields but " + contents.length
                    + " values were supplied for it");
        }

        // WHY : Assumptions: an insertion-ordered map is used so that a diagnostic naming the map
        //       reads in the band's declaration order rather than in hash order. The codec itself
        //       iterates the descriptor and not the map, so ordering has no effect on the bytes; it
        //       affects only how legible a failure is, which is the whole reason to prefer it here.
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < declared.size(); index++) {
            values.put(declared.get(index).name(), contents[index]);
        }

        // WHY : Assumptions: the default character set of this codec entry point is single-byte
        //       United States ASCII, so one character occupies one byte and the 80 declared
        //       characters of the band are 80 bytes. Every literal in these bands is a printable
        //       ASCII character, an asterisk, a hyphen, a currency character, a digit or a blank,
        //       so no value here is unrepresentable. The alternative entry point taking an explicit
        //       character set exists for the mainframe code page and is not used: this artifact is
        //       written to a modern sequential file, and encoding it in that code page would change
        //       every byte.
        return FixedWidthCodec.encodeRecord(values, band);
    }

    /**
     * Materialises a value at a source or target item's own declared width.
     *
     * <p>Assumptions: this reproduces what a COBOL character item <em>is</em> -- a run of exactly
     * its declared width, holding the value left-justified and blanks after it. It is not band
     * padding: band padding is the codec's, applied when content is placed into a position, and
     * this is applied to a prepared value <b>before</b> it reaches a band, so that the prepared
     * records can carry each item at its full width with its trailing blanks intact. The markup
     * regimes at lines 563 to 587 and at lines 614 to 711 of {@code app/cbl/CBSTM03A.CBL} read
     * those blanks, so a prepared value shorter than its item would be a different value to
     * them.</p>
     *
     * <p>Assumptions: a value wider than the declared width is truncated on the right, keeping the
     * leftmost characters, which is what a COBOL move into a narrower character item does and what
     * a concatenation into a shorter item does. Only one caller can reach that branch -- the name
     * assembly, whose {@value #NAME_ASSEMBLY_CAPACITY} characters can exceed the
     * {@value #ASSEMBLED_NAME_WIDTH} of its item -- and the branch is written here rather than
     * there so that the two halves of one item's contract, its width and its overflow rule, sit
     * together.</p>
     *
     * @param value the value to materialise; must not be {@code null}
     * @param declaredWidth the width the receiving item declares, in characters
     * @return a string of exactly {@code declaredWidth} characters, blank-padded on the right when
     *     the value is shorter and truncated on the right when it is longer, never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static String atDeclaredWidth(String value, int declaredWidth) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.length() == declaredWidth) {
            return value;
        }
        if (value.length() > declaredWidth) {
            return value.substring(0, declaredWidth);
        }

        StringBuilder materialised = new StringBuilder(declaredWidth);
        materialised.append(value);
        while (materialised.length() < declaredWidth) {
            materialised.append(BLANK);
        }
        return materialised.toString();
    }

    /**
     * Returns the characters of a source item that precede its first blank.
     *
     * <p>Assumptions: this is the one-blank concatenation regime of {@code app/cbl/CBSTM03A.CBL},
     * used at lines 462 to 469 for the three name parts and at lines 472 to 481 for the four
     * address parts, which is 3 plus 4 and exactly the 7 occurrences a search of that program
     * finds. An operand delimited by a single blank transfers characters up to, and not including,
     * the first blank of the sending item; if the item holds no blank at all the whole item
     * transfers.</p>
     *
     * <p>Assumptions: this is <b>not a trim</b>, and the difference is behavioural rather than
     * cosmetic. A value holding an internal blank contributes only what precedes it, so a
     * compound given name loses its second word and a two-word street line loses everything after
     * the first word. {@code String.strip()} and {@code String.trim()} are the rejected
     * alternatives and are wrong twice over: they would keep the internal blank and everything
     * after it, and they would additionally remove leading blanks that this regime preserves as
     * content under AAP Rule T8 (user-visible strings are verbatim).</p>
     *
     * <p>Assumptions: a value shorter than its declared width needs no explicit padding before the
     * search. Its item is blank-filled to the declared width in the reference, so its first blank
     * stands immediately after the last supplied character, and searching the supplied value alone
     * yields the same characters. A value that occupies its full declared width with no blank in it
     * transfers whole under both readings, so the two agree everywhere.</p>
     *
     * @param value the source item's content; must not be {@code null}
     * @param declaredWidth the width the source item declares, in characters, which bounds the
     *     value
     * @param component the parameter name to name in a rejection, so a caller can tell which of
     *     several same-width parts was too wide
     * @return the characters preceding the first blank, or the whole value when it holds none;
     *     possibly empty, never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is wider than {@code declaredWidth}, which
     *     its source item cannot hold
     */
    private static String upToFirstBlank(String value, int declaredWidth, String component) {
        // WHY : Assumptions: the width guard below also carries the null rejection, so no
        //       separate null check precedes it. Two checks would raise the same exception
        //       with the same message from two places, and the second would be dead for every
        //       input the first admitted.
        requireAtMost(value, declaredWidth, component);

        int firstBlank = value.indexOf(BLANK);
        return firstBlank < 0 ? value : value.substring(0, firstBlank);
    }

    /**
     * Returns the leftmost characters of a value, as a move into a narrower item keeps them.
     *
     * <p>Assumptions: COBOL aligns a character move to the left, so a move into a narrower item
     * discards the <b>rightmost</b> excess. Both narrowings in this artifact behave that way: the
     * assembled name into the markup item at line 560 of {@code app/cbl/CBSTM03A.CBL}, and the
     * transaction description into its band item at line 677.</p>
     *
     * <p>Assumptions: the cut is unconditional and never depends on content. The reference
     * truncates the declared-width item as it stands, so a value whose surviving characters end in
     * blanks keeps those blanks and a value whose discarded characters were all blanks is not
     * treated differently from one whose discarded characters were text.</p>
     *
     * @param value the value to cut; must not be {@code null}
     * @param keptWidth the number of leftmost characters the receiving item declares
     * @return the leftmost {@code keptWidth} characters, or the whole value when it is not longer
     *     than that, never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static String leftmostCharacters(String value, int keptWidth) {
        Objects.requireNonNull(value, "value must not be null");
        return value.length() <= keptWidth ? value : value.substring(0, keptWidth);
    }

    /**
     * Requires a value to be exactly the width its item declares.
     *
     * <p>Assumptions: exact width rather than maximum width is the contract for a value that has
     * already been prepared, because the markup regimes read the trailing blanks of the item. A
     * short value would still be padded once it reached its band, so the plain-text artifact would
     * look correct while the markup rendering received a value it could not interpret; checking
     * here rejects that at the boundary that produced it.</p>
     *
     * @param value the value to check; must not be {@code null}
     * @param declaredWidth the width the item declares, in characters
     * @param component the parameter or component name to name in a rejection
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not exactly {@code declaredWidth}
     *     characters
     */
    private static void requireExactWidth(String value, int declaredWidth, String component) {
        Objects.requireNonNull(value, component + " must not be null");
        if (value.length() != declaredWidth) {
            throw new IllegalArgumentException(component + " must be exactly " + declaredWidth
                    + " characters as its declared item holds it, but is " + value.length());
        }
    }

    /**
     * Requires a value to fit the width its source item declares.
     *
     * <p>Assumptions: a value wider than its source item did not come from that item, so it is
     * rejected rather than silently cut. The distinction from the deliberate narrowings matters: a
     * narrowing is a documented property of a move between two <em>different</em> declared widths,
     * whereas a value exceeding its own source width means the caller has supplied something the
     * reference record could not have held, and cutting it would hide that.</p>
     *
     * @param value the value to check; must not be {@code null}
     * @param declaredWidth the width the source item declares, in characters
     * @param component the parameter name to name in a rejection
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is wider than {@code declaredWidth}
     *     characters
     */
    private static void requireAtMost(String value, int declaredWidth, String component) {
        Objects.requireNonNull(value, component + " must not be null");
        if (value.length() > declaredWidth) {
            throw new IllegalArgumentException(component + " holds " + value.length()
                    + " characters but its declared source item is " + declaredWidth);
        }
    }
}
