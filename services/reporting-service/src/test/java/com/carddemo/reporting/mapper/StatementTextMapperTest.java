package com.carddemo.reporting.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.money.Money;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises every member of {@link StatementTextMapper} against the bytes it is required to emit.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link StatementBandLayouts} and its own test pin the <em>geometry</em> of the seventeen
 * statement bands. Nothing pinned the <em>assembler</em> that fills them: before this class existed
 * the mapper had no executable consumer at all, so every width transcription, every blank-delimiter
 * rule, every edit regime and the emission order of all nineteen lines of a card statement rested
 * on review alone. This class closes that gap. It asserts the three assembled items character for
 * character, both wider-target renderings, the description narrowing, both money regimes, the
 * exact eighty bytes of all sixteen header lines, the one transaction line and all three trailer
 * lines, and every documented rejection.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a
 * caller invokes, no value it yields and no exception it raises to anything outside the test
 * engine, so the type itself accepts no parameter, returns nothing and throws nothing. The
 * inapplicability is stated rather than passed over, because user-specified Rule 1 (Explainability)
 * forbids a docstring that omits parameters, return values or purpose, and a reader has to be able
 * to tell a declared inapplicability from an oversight. Every member below carries its own
 * parameter, return and exception at-clauses.</p>
 *
 * <h2>Assumptions: expected bytes are spelled out, not recomposed from the mapper's own inputs</h2>
 *
 * <p>Every band assertion below compares against a literal string of exactly
 * {@value #STATEMENT_RECORD_LENGTH} characters, transcribed from lines 86 to 146 of
 * {@code app/cbl/CBSTM03A.CBL} and checked against a captured emission. Alternatives Considered:
 * building each expectation by concatenating the same constants the mapper concatenates, which
 * reads more compactly. Rejected because such a test passes whenever the mapper and the test make
 * the <em>same</em> mistake, which is the failure mode a transcription test exists to catch -- a
 * label off by one blank, or two bands swapped, would satisfy it. The cost accepted is that a
 * deliberate change to a band requires the literal here to be re-derived from the reference, which
 * is the point.</p>
 *
 * <h2>Assumptions: two divergences from the reference are asserted as divergences</h2>
 *
 * <p>Two behaviours below are deliberately not the reference's, and each is asserted in the form
 * the register records rather than in the form the reference would produce. A balance needing more
 * than nine integer positions raises here where line 484 of {@code app/cbl/CBSTM03A.CBL} silently
 * discards a digit, which is registered as {@code D-EDIT-MASK-OVERFLOW}. And an empty middle name
 * yields a paired blank whose two artifacts then disagree about the same customer, which is
 * registered as {@code D-STMT-PAIRED-BLANK-NAME}. Both identifiers live in
 * {@code docs/architecture/cobol-to-service-traceability.md}; asserting the divergent behaviour
 * here is what keeps the register honest, because a silent repair of either would fail this
 * class.</p>
 *
 * <h2>Assumptions: the two amount regimes are named by picture, never by ordinal alone</h2>
 *
 * <p>{@link CobolEditMask} enumerates seven display and edit regimes, of which exactly two reach
 * this artifact. {@code PIC 9(9).99-}, declared at line 113 of {@code app/cbl/CBSTM03A.CBL} and
 * formatted by {@link CobolEditMask#formatStatementBalance(Money)}, is its regime 6 and PRESERVES
 * leading zeros. {@code PIC Z(9).99-}, declared at lines 137 and 142 and formatted by
 * {@link CobolEditMask#formatStatementAmount(Money)}, is its regime 7 and BLANKS them. Both are 13
 * characters with the sign in the last position.</p>
 *
 * <p><b>Every assertion below names the regime by its picture and its method, and treats the
 * ordinal as a cross-reference rather than as the identifier.</b> Assumptions: an ordinal is
 * meaningful only against one enumeration, and the two regimes of this artifact sit at 6 and 7 of
 * that class's seven, so a number carried in from any other enumeration of the same masks lands one
 * out and names the wrong one of a pair whose whole difficulty is that they are the same width and
 * shape. A picture clause and a method name cannot be off by one.</p>
 *
 * <h2>Assumptions: this class does not re-prove the regimes it relies on</h2>
 *
 * <p>{@code CobolEditMaskTest} owns regime semantics -- suppression, sign placement, grouping,
 * rejection thresholds -- and this class owns <b>placement</b>: where an already-edited
 * 13-character string sits inside an 80-byte record, and which regime each {@code MOVE} site of
 * {@code app/cbl/CBSTM03A.CBL} pairs its source picture with. The few edited strings asserted below
 * are asserted as the pairing evidence at a {@code MOVE} site and not as a re-derivation of the
 * mask. Trade-offs: naming an edited value here does duplicate one expectation that the mask's own
 * test also holds, and the duplication is accepted because the alternative leaves the pairing
 * itself unasserted -- calling the wrong one of two same-width regimes is invisible to a test that
 * only checks widths. What is deliberately not accepted is a second owner for the rules themselves:
 * two owners of one rule drift, and the drift surfaces as two green tests disagreeing about a
 * byte.</p>
 *
 * <h2>Assumptions: TRNX is not TRAN, and the two are never treated as one record</h2>
 *
 * <p>The statement's transaction source is {@code TRNX-RECORD}, declared at line 20 of
 * {@code app/cpy/COSTM01.CPY}: {@code TRNX-KEY} at line 21 is a 16-character card number at line 22
 * followed by a 16-character identifier at line 23, and {@code TRNX-REST} at line 24 carries the
 * remaining 318, for 350 in total. That is a <b>different record type</b> from the {@code TRAN}
 * layout of {@code app/cpy/CVTRA05Y.cpy} that feeds the daily transaction report, with different
 * field order and different offsets, and <b>neither is an alias of the other</b>. Assumptions: the
 * two share a domain, a record length and most field names, which is exactly why conflating them is
 * plausible; doing so would shift every offset downstream of the first difference while every field
 * still decoded as the right kind. This class names {@code TRNX} field pictures from
 * {@code app/cpy/COSTM01.CPY} only -- the description at line 28 and the amount at line 29 -- and
 * cites no offset of the other book at all.</p>
 *
 * <h2>Assumptions: the boundary values are transcribed here rather than read from a fixture</h2>
 *
 * <p>Four boundaries have to be reached for the four narrowings below to be proven rather than
 * merely exercised: a description LONGER than the 49 its band item declares, a balance needing TEN
 * integer digits against a mask holding nine, a name whose middle part is BLANK, and a name whose
 * three parts reach the 78 the assembly can offer a 75-character item. Every one is supplied as a
 * transcribed literal at the point of use, and each carries an {@code Assumptions:} note stating
 * the boundary it reaches and why a value short of it would pass either way.</p>
 *
 * <p>Alternatives Considered: driving them from the module's fixture files. Rejected on measurement
 * rather than preference. The fixture register at {@code src/test/resources/fixtures/README.md}
 * declares four data fixtures and none of them is a {@code TRNX} record, so there is no fixture
 * description to be longer than 49 in the first place; and of the values that do exist, the widest
 * account balance in {@code acctfile.txt} needs 4 integer digits against the ten this artifact must
 * exercise, every middle name in {@code custfile.txt} is non-blank, and its longest three-part name
 * sums to 24 characters against the 78 the assembly can offer. A fixture-driven form would
 * therefore read as though it exercised four boundaries while reaching none of them, which is
 * strictly worse than a literal that reaches them and says so.</p>
 *
 * <h2>Assumptions: table arity is out of scope for this class, and its numbers are not one
 * number</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} declares {@code WS-CARD-TBL OCCURS 51 TIMES} at line 226 with a
 * subordinate {@code WS-TRAN-TBL OCCURS 10 TIMES} at line 228, and {@code WS-TRN-TBL-CTR OCCURS 51
 * TIMES} at line 232. Assumptions: those declarations belong to a service that accumulates a
 * statement, and this class exercises a stateless per-line assembler that holds no table and
 * imposes no arity -- a caller emits one transaction line per transaction, one call each -- so no
 * arity assertion is available here to write. The divergence is registered as {@code D-2} in
 * {@code docs/architecture/cobol-to-service-traceability.md}, and the Java imposes no arity limit
 * at all. The three figures are distinct and are never added or averaged into one: a declared inner
 * arity of 10, a declared and measured outer arity of 51, and a separately measured inner threshold
 * of 512 recorded in {@code tests/README.md}. Presenting any single number as "the statement limit"
 * would misdescribe two of the three.</p>
 *
 * <h2>Assumptions: inline justifications carry a WHY rationale only</h2>
 *
 * <p>Every inline comment below is a single block opening with one of the four category labels of
 * user-specified Rule 1 (Explainability) at its lines 31 to 34, written plural, unparenthesised,
 * colon-terminated and free of emphasis markup, and it gives the reason and what differs under the
 * alternative. Assumptions: no statement here carries a {@code WHAT:} line, because the section
 * "The {@code # WHAT:} / {@code # WHY :} idiom -- prose command blocks only" of
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} confines that twin form to fenced command blocks in
 * prose and names {@code .java} first among the file kinds that may not carry a statement-level
 * {@code WHAT:} comment, on the ground that such a line restates the statement it sits above and is
 * therefore the first forbidden pattern of Rule 1 at its line 38. Purpose is stated once, in the
 * Javadoc, where the language puts it. Every sibling test of this package follows the same form, so
 * the choice is the module's rather than this file's.</p>
 *
 * @see StatementTextMapper
 * @see StatementBandLayouts
 * @see CobolEditMask
 */
@DisplayName("StatementTextMapper: assembly, edit regimes and band emission")
class StatementTextMapperTest {

    /**
     * Declared record length of the plain-text statement, in bytes, transcribed independently here.
     *
     * <p>Assumptions: this is re-declared rather than read from {@link StatementBandLayouts} so
     * that a test failure can distinguish a wrong band from a wrong record length. It is
     * established by {@code 01 FD-STMTFILE-REC PIC X(80).} at line 45 of
     * {@code app/cbl/CBSTM03A.CBL}.</p>
     */
    private static final int STATEMENT_RECORD_LENGTH = 80;

    /**
     * Number of lines the header block emits, transcribed independently from the reference.
     *
     * <p>Assumptions: line 460 of {@code app/cbl/CBSTM03A.CBL} writes the opening banner and lines
     * 488 to 502 write fifteen further bands, which is one plus fifteen.</p>
     */
    private static final int HEADER_BLOCK_LINES = 16;

    /**
     * Number of lines the card trailer emits, transcribed independently from the reference.
     *
     * <p>Assumptions: lines 435, 436 and 437 of {@code app/cbl/CBSTM03A.CBL} write the rule band,
     * the total band and the closing banner, and nothing else.</p>
     */
    private static final int CARD_TRAILER_LINES = 3;

    /**
     * Zero-based offset of the currency character that stands before every statement amount.
     *
     * <p>Assumptions: {@code ST-LINE14} declares a 16-character identifier, a 1-character blank and
     * a 49-character description before it at lines 133 to 136 of {@code app/cbl/CBSTM03A.CBL},
     * which is 66 characters. {@code ST-LINE14A} reaches the same offset from a 10-character label
     * and a 56-character blank run at lines 139 and 140, which is also 66. Both are stated because
     * the equality of the two is the reason the total columns under the detail amounts.</p>
     */
    private static final int CURRENCY_OFFSET = 66;

    /**
     * Number of band descriptors the plain-text statement declares.
     *
     * <p>Assumptions: {@code ST-LINE0} through {@code ST-LINE15} are sixteen names and
     * {@code ST-LINE14A} at line 138 of {@code app/cbl/CBSTM03A.CBL} is a seventeenth, so the count
     * is seventeen and not sixteen. The extra name breaks the numeric run, which is the reason it
     * is the one band a reader counting the series omits, and the reason the count is stated as its
     * own constant instead of being written inline where an off-by-one would read as plausible.</p>
     */
    private static final int STATEMENT_BAND_COUNT = 17;

    /**
     * Declared record length of the markup statement, in bytes, which the plain text never shares.
     *
     * <p>Assumptions: {@code 01 FD-HTMLFILE-REC PIC X(100).} at line 47 of
     * {@code app/cbl/CBSTM03A.CBL} declares the markup record, under a second {@code FD} at line 46
     * that is separate from the plain-text one at line 44. The two data sets are separate in the
     * job as well: {@code app/jcl/CREASTMT.JCL} defines {@code STMTFILE} at lines 87 to 91 with
     * {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} at line 89, and {@code HTMLFILE} at lines 92 to
     * 95 with {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)} at line 94.</p>
     */
    private static final int MARKUP_RECORD_LENGTH = 100;

    /**
     * Sums the declared lengths of a band's fields, independently of the band's declared length.
     *
     * <p>Assumptions: the sum is taken from {@link CopybookLayout.FieldSpec#length()} rather than
     * from the last field's end offset, so a band whose positions overlapped or left a hole would
     * disagree with its own declared length here instead of summing to it by accident.</p>
     *
     * @param band the band descriptor whose declared field lengths are to be summed
     * @return the total of every declared field length of the band
     */
    private static int declaredFieldSum(CopybookLayout.RecordSpec band) {
        return band.fields().stream().mapToInt(CopybookLayout.FieldSpec::length).sum();
    }

    /**
     * Assembles the header fields of a representative statement used by several tests below.
     *
     * <p>Assumptions: one helper is used rather than repeating twelve arguments, and its values are
     * chosen so that every rendering it drives is distinguishable: a three-part name with no
     * internal blank in any part, two distinct address lines, a balance whose integer part is short
     * enough to leave leading zeros visible, and a credit score of three digits.</p>
     *
     * @return prepared header fields for the representative statement, never {@code null}
     */
    private static StatementTextMapper.PreparedHeaderFields representativeHeader() {
        return StatementTextMapper.prepareHeaderFields("John", "Q", "Public",
                "1 Main St", "Apt 2", "Springfield", "IL", "USA", "62701",
                1L, Money.of("194.00"), 750);
    }

    /**
     * Decodes an emitted band to a string for comparison against a transcribed literal.
     *
     * <p>Assumptions: the charset is named explicitly and is the same single-byte charset the
     * placement mechanism encodes with, which is US-ASCII by the default of
     * {@link com.carddemo.common.codec.FixedWidthCodec}. Two consequences follow and both are
     * relied on by every assertion below. A single-byte charset makes "length in characters" and
     * "length in bytes" the same number, so an assertion on a decoded string's length is an
     * assertion about the record's bytes; and naming the charset removes the default charset of the
     * virtual machine from the comparison entirely, which a decode without one would otherwise
     * consult. The equality is asserted rather than assumed, so a band carrying a byte outside the
     * single-byte range fails here instead of shortening a length assertion somewhere else.</p>
     *
     * <p>Alternatives Considered: decoding with the eight-bit Unicode transformation, which
     * produces identical strings for every band this artifact actually emits. Rejected because it
     * is a variable-width charset, so the identity holds only while every byte stays inside the
     * seven-bit range and would stop holding silently the first time one did not -- a decoded
     * string one character shorter than its record would then satisfy an assertion written in
     * characters while the record it came from was wrong.</p>
     *
     * @param band the emitted band; must not be {@code null}
     * @return the band decoded as US-ASCII text, of the same length in characters as the band is in
     *     bytes
     */
    private static String text(byte[] band) {
        String decoded = new String(band, StandardCharsets.US_ASCII);
        assertThat(decoded).hasSize(band.length);
        return decoded;
    }

    /**
     * Builds a value of a stated length by padding it on the right with blanks.
     *
     * <p>Assumptions: this helper exists only to keep an 80-character expectation readable on one
     * source line where its tail is a long run of blanks. It never pads a value the mapper produced
     * -- it pads an expectation -- so it cannot mask a padding fault in the mapper.</p>
     *
     * @param value the leading content of the expectation
     * @param width the total width the expectation must reach
     * @return the value followed by enough blanks to reach the width
     */
    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Asserts that every band closes at its declared length natively, so nothing is padded.
     */
    @Test
    @DisplayName("every band's declared fields sum to eighty natively, so no band needs padding")
    void everyBandSumsToEightyNativelySoNoBandNeedsPadding() {
        assertThat(StatementBandLayouts.bands()).hasSize(STATEMENT_BAND_COUNT);

        // WHY : Assumptions: the field sum is asserted EQUAL to the declared length for all
        //       seventeen bands, which is the property that makes the placement mechanism's padding
        //       step a no-op for this artifact. Every band of lines 86 to 146 of
        //       app/cbl/CBSTM03A.CBL closes at the 80 declared by line 45 from its own subordinate
        //       pictures: 31 plus 18 plus 31, 75 plus 5, 50 plus 30 twice, a single 80, 33 plus 14
        //       plus 33, 20 plus 20 plus 40, 20 plus 13 plus 7 plus 40, 16 plus 51 plus 13, 16 plus
        //       1 plus 49 plus 1 plus 13, 10 plus 56 plus 1 plus 13, 32 plus 16 plus 32, and four
        //       single 80-character rules. Padding any of them would overrun the record.
        assertThat(StatementBandLayouts.bands()).allSatisfy(band -> {
            assertThat(declaredFieldSum(band)).isEqualTo(band.reclen());
            assertThat(band.reclen()).isEqualTo(STATEMENT_RECORD_LENGTH);
        });
    }

    /**
     * Asserts that the statement's padding obligation is the opposite of the report's.
     */
    @Test
    @DisplayName("the statement's declared length is not the report's, and the two padding "
            + "obligations are opposite")
    void theStatementAndTheReportStandInOppositeRelationsToPadding() {
        // WHY : Assumptions: the two declared lengths are asserted UNEQUAL because the two
        //       artifacts of this package have opposite padding obligations and a reader arriving
        //       from either side carries the wrong one across. Line 45 of app/cbl/CBSTM03A.CBL
        //       declares 80 and every statement band closes at it natively; the report's declared
        //       length is 133 and six of its seven bands are SHORT of it in app/cpy/CVTRA07Y.cpy --
        //       115 at lines 4 to 13, 114 at lines 15 to 31, 114 at lines 33 to 46, 133 at line 48,
        //       and 112 at each of lines 50 to 54, 56 to 60 and 62 to 66 -- so those six must be
        //       padded out to it. Carrying the report's padding assumption here would push a band
        //       past 80; carrying this file's no-padding assumption there would leave a band short.
        assertThat(StatementBandLayouts.STATEMENT_LINE_LENGTH)
                .isNotEqualTo(ReportBandLayouts.REPORT_RECORD_LENGTH)
                .isEqualTo(STATEMENT_RECORD_LENGTH);

        // WHY : Assumptions: the report descriptors are asserted to close at 133 rather than at
        //       their native COBOL widths, because ReportBandLayouts transcribes the padding
        //       obligation as explicit trailing positions instead of leaving it to the encoder.
        //       That is the same no-hole property asserted above for the statement, reached from
        //       the opposite starting point, and stating it here is what stops the
        //       pre-transcription native widths cited above from being mistaken for the
        //       descriptors' own.
        assertThat(declaredFieldSum(ReportBandLayouts.TRANSACTION_DETAIL_REPORT))
                .isEqualTo(ReportBandLayouts.REPORT_RECORD_LENGTH);
    }

    /**
     * Asserts that the plain-text record length is never the markup record length.
     */
    @Test
    @DisplayName("eighty and one hundred are never interchangeable, being two separate data sets")
    void eightyAndOneHundredAreNeverInterchangeable() {
        // WHY : Assumptions: the two record lengths are asserted unequal, and the plain-text one is
        //       asserted against the job's own data-set attribute rather than against the program
        //       alone. app/cbl/CBSTM03A.CBL declares the two records at lines 45 and 47 under
        //       separate FD entries at lines 44 and 46, and app/jcl/CREASTMT.JCL corroborates them
        //       independently: LRECL=80 at line 89 for STMTFILE at lines 87 to 91, and a separate
        //       LRECL=100 at line 94 for HTMLFILE at lines 92 to 95. Two independent declarations
        //       agreeing is what makes 80 a contract rather than a transcription that could be off.
        assertThat(STATEMENT_RECORD_LENGTH).isNotEqualTo(MARKUP_RECORD_LENGTH);

        List<byte[]> emitted = StatementTextMapper.emitHeaderBlock(representativeHeader());
        assertThat(emitted).allSatisfy(line -> assertThat(line)
                .hasSize(STATEMENT_RECORD_LENGTH)
                .hasSizeLessThan(MARKUP_RECORD_LENGTH));
    }

    /**
     * Asserts that assembleName cuts each part at its first blank rather than trimming it.
     */
    @Test
    @DisplayName("assembleName cuts each part at its first blank rather than trimming it")
    void assembleNameCutsEachPartAtItsFirstBlankRatherThanTrimmingIt() {
        String assembled = StatementTextMapper.assembleName("Mary Ann", "Beth Ann", "Smith Jones");

        // WHY : Assumptions: the assertion is that the SECOND word of each part is absent, which is
        //       what distinguishes the reference's delimited concatenation at lines 462 to 469 of
        //       app/cbl/CBSTM03A.CBL from a whitespace trim. A trim would keep "Mary Ann" whole and
        //       still produce a plausible name, so asserting only the length would pass.
        assertThat(assembled).isEqualTo(
                padded("Mary Beth Smith ", StatementTextMapper.ASSEMBLED_NAME_WIDTH));
        assertThat(assembled).doesNotContain("Ann").doesNotContain("Jones");
    }

    /**
     * Asserts that assembleName emits three blank literals unconditionally, so an empty
     * middle part yields a paired blank.
     */
    @Test
    @DisplayName("assembleName emits three blank literals unconditionally, so an empty middle "
            + "part yields a paired blank")
    void assembleNameEmitsThreeBlankLiteralsUnconditionally() {
        String assembled = StatementTextMapper.assembleName("Mary Ann", "", "Smith");

        // WHY : Assumptions: this asserts registered divergence D-STMT-PAIRED-BLANK-NAME rather
        //       than a tidy result. The two consecutive blanks at offsets 4 and 5 are what the
        //       markup regime at line 563 of app/cbl/CBSTM03A.CBL truncates on, so the plain-text
        //       band shows the whole name while the markup cell shows the first name alone. A
        //       future revision that collapsed the pair would read as an improvement and would
        //       change the bytes of the first band, which AAP Rule T9 forbids.
        // WHY : Trade-offs: the reference's paired separator is REPRODUCED here rather than
        //       smoothed, and the cost accepted is an assembled name that reads oddly for a
        //       customer with no middle name. Each of the three blank literals at lines 463, 465
        //       and 467 is a separate operand of the concatenation rather than a separator emitted
        //       between non-empty parts, and a blank part contributes nothing because DELIMITED BY
        //       ' ' stops at the first blank, so the two literals around it meet. The assembled
        //       name is a user-visible string, so tidying it would be an undocumented behavioural
        //       change; the baseline produces a paired separator for a blank middle name, the Java
        //       reproduces it, and the behaviour is intentional and registered.
        // WHY : Assumptions: an empty middle part is supplied here because it is the only input
        //       that reaches this artifact at all, and no fixture carries one -- every middle name
        //       in src/test/resources/fixtures/custfile.txt is a non-blank value at its declared
        //       PIC X(25) of line 7 of app/cpy/CUSTREC.cpy.
        assertThat(assembled)
                .isEqualTo(padded("Mary  Smith", StatementTextMapper.ASSEMBLED_NAME_WIDTH));
        assertThat(assembled.charAt(4)).isEqualTo(' ');
        assertThat(assembled.charAt(5)).isEqualTo(' ');
        assertThat(StatementTextMapper.narrowNameForMarkup(assembled)).startsWith("Mary  Smith");
    }

    /**
     * Asserts that assembleName truncates at the declared width when all three parts are
     * full.
     */
    @Test
    @DisplayName("assembleName truncates at the declared width when all three parts are full")
    void assembleNameTruncatesAtTheDeclaredWidthWhenAllThreePartsAreFull() {
        String assembled = StatementTextMapper.assembleName("A".repeat(25), "B".repeat(25),
                "C".repeat(25));

        // WHY : Assumptions: the arithmetic is asserted rather than described. Three 25-character
        //       parts each followed by one blank offer NAME_ASSEMBLY_CAPACITY of 78 characters to a
        //       75-character item, so exactly 3 characters are lost: the trailing blank literal and
        //       the last two characters of the third part. Asserting the surviving run is 23 long
        //       is what proves the loss is 3 and not 2 or 4.
        // WHY : Assumptions: all three parts are filled to their declared PIC X(25) of lines 6, 7
        //       and 8 of app/cpy/CUSTREC.cpy, because the overflow is only reachable at full
        //       occupancy and no fixture reaches it -- the longest three-part name in
        //       src/test/resources/fixtures/custfile.txt sums to 24 characters against the 78 this
        //       assembly can offer, so a fixture-driven case would exercise no overflow at all. The
        //       containing band stays 80 regardless: ST-LINE1 at line 90 of app/cbl/CBSTM03A.CBL is
        //       75 plus the 5 declared blanks of line 92.
        assertThat(assembled).hasSize(StatementTextMapper.ASSEMBLED_NAME_WIDTH);
        assertThat(text(StatementTextMapper.emitHeaderBlock(
                StatementTextMapper.prepareHeaderFields("A".repeat(25), "B".repeat(25),
                        "C".repeat(25), "d", "e", "f", "IL", "USA", "62701", 1L,
                        Money.of("1.00"), 700)).get(1)))
                .hasSize(STATEMENT_RECORD_LENGTH)
                .startsWith(assembled);
        assertThat(assembled)
                .isEqualTo("A".repeat(25) + " " + "B".repeat(25) + " " + "C".repeat(23));
        assertThat(StatementTextMapper.NAME_ASSEMBLY_CAPACITY
                - StatementTextMapper.ASSEMBLED_NAME_WIDTH).isEqualTo(3);
    }

    /**
     * Supplies one over-wide argument per position of the name assembly.
     *
     * <p>Assumptions: exactly one position is over-wide per case and the other two are empty, so a
     * rejection can only have come from the position the case names. Alternatives Considered: one
     * case with all three over-wide, which is shorter. Rejected because the assembly checks its
     * parts in declaration order, so such a case would be satisfied by an implementation that
     * checked the first part and none of the others.</p>
     *
     * @return a stream of position label, first, middle and last name
     */
    private static Stream<Arguments> overWideNameParts() {
        String wide = "x".repeat(StatementTextMapper.NAME_PART_WIDTH + 1);
        return Stream.of(
                Arguments.of("firstName", wide, "", ""),
                Arguments.of("middleName", "", wide, ""),
                Arguments.of("lastName", "", "", wide));
    }

    /**
     * Asserts that assembleName rejects a part wider than the source item that holds it.
     *
     * @param argumentName the name of the argument the rejection must cite
     * @param first the first-name argument for this case
     * @param middle the middle-name argument for this case
     * @param last the last-name argument for this case
     */
    @ParameterizedTest(name = "assembleName rejects an over-wide {0}")
    @MethodSource("overWideNameParts")
    @DisplayName("assembleName rejects a part wider than the source item that holds it")
    void assembleNameRejectsAnOverWidePart(String argumentName, String first, String middle,
            String last) {
        // WHY : Assumptions: the rejection message is asserted to name the offending argument AND
        //       the width it exceeded, not merely to be of the right type. All three parts share
        //       the declared PIC X(25) of lines 6, 7 and 8 of app/cpy/CUSTREC.cpy, so a message
        //       naming only the width cannot tell a caller which of three same-width values was
        //       wrong, and a diagnostic that cannot locate the fault costs the caller the work it
        //       saved here.
        assertThatThrownBy(() -> StatementTextMapper.assembleName(first, middle, last))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(argumentName)
                .hasMessageContaining(String.valueOf(StatementTextMapper.NAME_PART_WIDTH));
    }

    /**
     * Supplies one {@code null} argument per position of the name assembly.
     *
     * <p>Assumptions: the absent value is supplied one position at a time for the same
     * declaration-order reason as the over-wide cases above, and the two are kept as separate
     * providers because they expect different rejections -- an absent value is a contract violation
     * of a different kind from a value too wide for the item that holds it.</p>
     *
     * @return a stream of position label, first, middle and last name
     */
    private static Stream<Arguments> nullNameParts() {
        return Stream.of(
                Arguments.of("firstName", null, "", ""),
                Arguments.of("middleName", "", null, ""),
                Arguments.of("lastName", "", "", null));
    }

    /**
     * Asserts that assembleName rejects a null part by naming the argument.
     *
     * @param argumentName the name of the argument the rejection must cite
     * @param first the first-name argument for this case
     * @param middle the middle-name argument for this case
     * @param last the last-name argument for this case
     */
    @ParameterizedTest(name = "assembleName rejects a null {0}")
    @MethodSource("nullNameParts")
    @DisplayName("assembleName rejects a null part by naming the argument")
    void assembleNameRejectsANullPart(String argumentName, String first, String middle,
            String last) {
        // WHY : Assumptions: an absent value is asserted to be reported as such rather than treated
        //       as an empty part. The two are not interchangeable here: an empty part is VALID
        //       input that contributes nothing and yields the paired blank asserted above, so
        //       coercing an absent value to empty would turn a caller's fault into a plausible
        //       statement naming a customer whose middle name was never supplied.
        assertThatThrownBy(() -> StatementTextMapper.assembleName(first, middle, last))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(argumentName);
    }

    /**
     * Asserts that assembleAddressLine3 joins four parts with four blank literals.
     */
    @Test
    @DisplayName("assembleAddressLine3 joins four parts with four blank literals")
    void assembleAddressLine3JoinsFourPartsWithFourBlankLiterals() {
        String assembled = StatementTextMapper.assembleAddressLine3("Springfield", "IL", "USA",
                "62701");

        // WHY : Assumptions: the trailing blank after the postal code is asserted through the total
        //       width rather than looked for directly, because a blank at the end of a blank-padded
        //       item is indistinguishable from padding. What IS distinguishable is that there are
        //       four separators and not three, which the single-blank gaps between the four parts
        //       show.
        assertThat(assembled).isEqualTo(padded("Springfield IL USA 62701",
                StatementTextMapper.ASSEMBLED_ADDRESS_WIDTH));
    }

    /**
     * Asserts that assembleAddressLine3 cannot overflow, even at full occupancy of all four
     * parts.
     */
    @Test
    @DisplayName("assembleAddressLine3 cannot overflow, even at full occupancy of all four parts")
    void assembleAddressLine3CannotOverflowAtFullOccupancy() {
        String assembled = StatementTextMapper.assembleAddressLine3("A".repeat(50), "IL", "USA",
                "1234567890");

        // WHY : Assumptions: full occupancy is 69 characters against a declared 80, so the result
        //       must carry 11 trailing blanks and lose nothing. This is the asymmetry with the name
        //       assembly above, which loses 3 at its own full occupancy, and asserting both is what
        //       stops a reader assuming one rule covers the two.
        assertThat(assembled).hasSize(StatementTextMapper.ASSEMBLED_ADDRESS_WIDTH);

        // WHY : Assumptions: the trailing run is TWELVE blanks and not eleven. Full occupancy
        //       offers 69 characters -- 50 plus 1, 2 plus 1, 3 plus 1, 10 plus 1 -- of which the
        //       69th is the blank literal that follows the postal code, and the materialisation to
        //       80 adds eleven more. Counting only the padding would miss that literal and read as
        //       an off-by-one in the assembly rather than in the expectation.
        assertThat(assembled).isEqualTo("A".repeat(50) + " IL USA 1234567890" + " ".repeat(12));
        assertThat(StatementTextMapper.ADDRESS_ASSEMBLY_CAPACITY)
                .isLessThan(StatementTextMapper.ASSEMBLED_ADDRESS_WIDTH);
    }

    /**
     * Supplies one over-wide argument per position of the address assembly.
     *
     * <p>Assumptions: the four positions carry four DIFFERENT declared widths -- 50, 2, 3 and 10 at
     * lines 11, 12, 13 and 14 of {@code app/cpy/CUSTREC.cpy} -- so each case must exceed its own
     * width rather than a shared one. A single over-wide value reused across all four would exceed
     * three of them for the wrong reason and would pass against an implementation that checked
     * every part against the widest.</p>
     *
     * @return a stream of position label, address line, state, country and postal code
     */
    private static Stream<Arguments> overWideAddressParts() {
        return Stream.of(
                Arguments.of("addressLine3", "x".repeat(StatementTextMapper.ADDRESS_LINE_WIDTH + 1),
                        "", "", ""),
                Arguments.of("stateCode", "", "x".repeat(StatementTextMapper.STATE_CODE_WIDTH + 1),
                        "", ""),
                Arguments.of("countryCode", "", "",
                        "x".repeat(StatementTextMapper.COUNTRY_CODE_WIDTH + 1), ""),
                Arguments.of("postalCode", "", "", "",
                        "x".repeat(StatementTextMapper.POSTAL_CODE_WIDTH + 1)));
    }

    /**
     * Asserts that assembleAddressLine3 rejects a part wider than the source item that holds
     * it.
     *
     * @param argumentName the name of the argument the rejection must cite
     * @param line3 the third-address-line argument for this case
     * @param state the state-code argument for this case
     * @param country the country-code argument for this case
     * @param postal the postal-code argument for this case
     */
    @ParameterizedTest(name = "assembleAddressLine3 rejects an over-wide {0}")
    @MethodSource("overWideAddressParts")
    @DisplayName("assembleAddressLine3 rejects a part wider than the source item that holds it")
    void assembleAddressLine3RejectsAnOverWidePart(String argumentName, String line3, String state,
            String country, String postal) {
        // WHY : Assumptions: the rejection is asserted per position because this assembly cannot
        //       overflow its target -- 69 characters into the 80 declared at line 100 of
        //       app/cbl/CBSTM03A.CBL -- so a part wider than its SOURCE item is the only width
        //       fault reachable here, and it would otherwise be absorbed silently into the 11
        //       characters of declared slack instead of being reported.
        assertThatThrownBy(
                () -> StatementTextMapper.assembleAddressLine3(line3, state, country, postal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(argumentName);
    }

    /**
     * Asserts that narrowNameForMarkup keeps the leftmost fifty characters, blanks included.
     */
    @Test
    @DisplayName("narrowNameForMarkup keeps the leftmost fifty characters, blanks included")
    void narrowNameForMarkupKeepsTheLeftmostFiftyCharacters() {
        // WHY : Assumptions: the LEFTMOST characters are asserted kept, which is the direction a
        //       COBOL character move into a narrower item takes. Line 560 of app/cbl/CBSTM03A.CBL
        //       moves ST-NAME, declared PIC X(75) at line 91, into L23-NAME, declared PIC X(50) at
        //       line 220, so the last 25 are discarded. A rightmost-25 or a centred narrowing would
        //       also return 50 characters of the same name and would differ in every byte.
        String assembled = StatementTextMapper.assembleName("A".repeat(25), "B".repeat(25),
                "C".repeat(25));

        String narrowed = StatementTextMapper.narrowNameForMarkup(assembled);

        assertThat(narrowed).hasSize(StatementTextMapper.MARKUP_NAME_WIDTH);
        assertThat(narrowed).isEqualTo("A".repeat(25) + " " + "B".repeat(24));
    }

    /**
     * Asserts that narrowNameForMarkup preserves trailing blanks, because the markup regime
     * reads them.
     */
    @Test
    @DisplayName("narrowNameForMarkup preserves trailing blanks, because the markup regime reads "
            + "them")
    void narrowNameForMarkupPreservesTrailingBlanks() {
        String narrowed = StatementTextMapper.narrowNameForMarkup(
                StatementTextMapper.assembleName("Jo", "", "Ng"));

        // WHY : Assumptions: the narrowing must NOT trim. Those trailing blanks are the input to
        //       the paired-blank truncation at line 563 of app/cbl/CBSTM03A.CBL, so a trimmed value
        //       would change what the markup cell shows while leaving this method's own result
        //       looking cleaner.
        assertThat(narrowed).hasSize(StatementTextMapper.MARKUP_NAME_WIDTH);
        assertThat(narrowed).isEqualTo(padded("Jo  Ng", StatementTextMapper.MARKUP_NAME_WIDTH));
    }

    /**
     * Supplies values of the wrong width for the markup narrowing.
     *
     * <p>Assumptions: one candidate below the declared width and one above it are supplied, because
     * the narrowing checks for an EXACT width and a maximum-width check would accept the short one.
     * The short case is the dangerous one: it would be blank-padded when it reached its band, so
     * the plain-text artifact would look correct while the markup rendering received a value whose
     * trailing blanks it needed and did not have.</p>
     *
     * @return a stream of one short value and one over-wide value
     */
    private static Stream<Arguments> wrongWidthAssembledNames() {
        return Stream.of(
                Arguments.of("short", "short"),
                Arguments.of("over-wide",
                        "x".repeat(StatementTextMapper.ASSEMBLED_NAME_WIDTH + 1)));
    }

    /**
     * Asserts that narrowNameForMarkup rejects any width the assembly could not have
     * produced.
     *
     * @param label a short label naming the case, shown in the case name
     * @param candidate the value of the wrong width offered to the narrowing
     */
    @ParameterizedTest(name = "narrowNameForMarkup rejects a {0} assembled name")
    @MethodSource("wrongWidthAssembledNames")
    @DisplayName("narrowNameForMarkup rejects any width the assembly could not have produced")
    void narrowNameForMarkupRejectsAWrongWidth(String label, String candidate) {
        // WHY : Assumptions: a width other than the declared 75 is rejected rather than narrowed,
        //       because this narrowing follows one specific assembly and a value of any other width
        //       did not come from it. Narrowing it anyway would answer a caller who had skipped the
        //       assembly with a plausible 50 characters instead of telling them they had.
        assertThatThrownBy(() -> StatementTextMapper.narrowNameForMarkup(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assembledName")
                .hasMessageContaining(String.valueOf(StatementTextMapper.ASSEMBLED_NAME_WIDTH));
    }

    /**
     * Asserts that renderAccountIdItem left-justifies eleven zero-padded digits and blank-
     * pads to twenty.
     */
    @Test
    @DisplayName("renderAccountIdItem left-justifies eleven zero-padded digits and blank-pads to "
            + "twenty")
    void renderAccountIdItemLeftJustifiesElevenZeroPaddedDigits() {
        String item = StatementTextMapper.renderAccountIdItem(1L);

        // WHY : Assumptions: both halves of this rendering are asserted because each is the
        //       opposite of the other's padding character. The digits keep their LEADING ZEROS,
        //       from the unsigned display picture at line 5 of app/cpy/CVACT01Y.cpy; the nine
        //       characters after them are BLANKS, from the wider character target at line 109 of
        //       app/cbl/CBSTM03A.CBL. A single-string assertion would pass either way round.
        assertThat(item).hasSize(StatementTextMapper.ACCOUNT_ID_ITEM_WIDTH);
        assertThat(item).isEqualTo("00000000001" + " ".repeat(9));
        assertThat(StatementTextMapper.renderAccountIdItem(99999999999L))
                .isEqualTo("99999999999" + " ".repeat(9));
    }

    /**
     * Asserts that renderAccountIdItem refuses an identifier the unsigned picture cannot
     * show.
     */
    @Test
    @DisplayName("renderAccountIdItem refuses an identifier the unsigned picture cannot show")
    void renderAccountIdItemRefusesAnIdentifierTheUnsignedPictureCannotShow() {
        // WHY : Assumptions: the two rejections are asserted to be of DIFFERENT kinds, and the
        //       pairing is deliberate rather than incidental. ACCT-ID is declared PIC 9(11) at line
        //       5 of app/cpy/CVACT01Y.cpy: unsigned, so a negative identifier is a value the
        //       picture has no position to show, while a twelve-digit identifier is one whose
        //       rendering would silently discard its high-order digits. Collapsing the two into one
        //       exception type would lose that distinction at the only place it is visible.
        assertThatThrownBy(() -> StatementTextMapper.renderAccountIdItem(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> StatementTextMapper.renderAccountIdItem(100000000000L))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("12");
    }

    /**
     * Asserts that renderCreditScoreItem places three digits and seventeen blanks.
     */
    @Test
    @DisplayName("renderCreditScoreItem places three digits and seventeen blanks")
    void renderCreditScoreItemPlacesThreeDigitsAndSeventeenBlanks() {
        // WHY : Assumptions: this second wider-target move is asserted separately from the account
        //       identifier's because its widths differ while its rule does not. Line 485 of
        //       app/cbl/CBSTM03A.CBL moves CUST-FICO-CREDIT-SCORE, declared PIC 9(03) at line 22 of
        //       app/cpy/CUSTREC.cpy, into ST-FICO-SCORE, declared PIC X(20) at line 118, so three
        //       digits are followed by SEVENTEEN blanks where the identifier's are followed by
        //       nine. Asserting one move and generalising would leave the other's blank count
        //       unpinned, and the score's leading zeros are asserted too because its source picture
        //       declares digit positions rather than suppression positions.
        assertThat(StatementTextMapper.renderCreditScoreItem(750))
                .isEqualTo("750" + " ".repeat(17))
                .hasSize(StatementTextMapper.CREDIT_SCORE_ITEM_WIDTH);
        assertThat(StatementTextMapper.renderCreditScoreItem(7)).startsWith("007");
    }

    /**
     * Asserts that renderCreditScoreItem refuses a score the unsigned picture cannot show.
     */
    @Test
    @DisplayName("renderCreditScoreItem refuses a score the unsigned picture cannot show")
    void renderCreditScoreItemRefusesAScoreTheUnsignedPictureCannotShow() {
        // WHY : Assumptions: the same two-kinds-of-rejection pairing as the account identifier is
        //       asserted here against a much smaller picture, PIC 9(03) at line 22 of
        //       app/cpy/CUSTREC.cpy, so that a four-digit score is rejected rather than truncated
        //       to three. A truncated score would still be a plausible credit score, which is
        //       precisely why the boundary is asserted rather than left to the shared helper.
        assertThatThrownBy(() -> StatementTextMapper.renderCreditScoreItem(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> StatementTextMapper.renderCreditScoreItem(1000))
                .isInstanceOf(ArithmeticException.class);
    }

    /**
     * Asserts that renderDescriptionItem keeps the leftmost forty-nine characters and blank-
     * pads a shorter one.
     */
    @Test
    @DisplayName("renderDescriptionItem keeps the leftmost forty-nine characters and blank-pads a "
            + "shorter one")
    void renderDescriptionItemKeepsTheLeftmostFortyNineCharacters() {
        // WHY : Assumptions: the two extremes of the source item are asserted here and the boundary
        //       itself is asserted by the triple below, so the two tests are complementary rather
        //       than overlapping. A description at the full declared PIC X(100) of line 28 of
        //       app/cpy/COSTM01.CPY loses 51 characters, and one far shorter than the target is
        //       blank-filled to 49; the boundary cases in between are where an off-by-one lives.
        assertThat(StatementTextMapper.renderDescriptionItem("y".repeat(100)))
                .isEqualTo("y".repeat(StatementTextMapper.DESCRIPTION_ITEM_WIDTH));
        assertThat(StatementTextMapper.renderDescriptionItem("Coffee"))
                .isEqualTo(padded("Coffee", StatementTextMapper.DESCRIPTION_ITEM_WIDTH));
    }

    /**
     * Asserts that renderDescriptionItem narrows before any trim, so a blank at the boundary
     * does not save the tail.
     */
    @Test
    @DisplayName("renderDescriptionItem narrows before any trim, so a blank at the boundary does "
            + "not save the tail")
    void renderDescriptionItemNarrowsWithoutTrimmingFirst() {
        String source = "y".repeat(49) + " " + "z".repeat(20);

        // WHY : Assumptions: this is the population a byte comparison would flag. The 50th
        //       character is a blank, so a trim-then-narrow implementation would still return 49
        //       y-characters and pass -- but the same implementation returns different bytes for a
        //       description with LEADING blanks, which is why the tail is asserted absent rather
        //       than the head asserted present alone.
        assertThat(StatementTextMapper.renderDescriptionItem(source))
                .isEqualTo("y".repeat(49))
                .doesNotContain("z");
    }

    /**
     * Asserts that renderDescriptionItem refuses a description wider than its source item.
     */
    @Test
    @DisplayName("renderDescriptionItem refuses a description wider than its source item")
    void renderDescriptionItemRefusesAnOverWideDescription() {
        assertThatThrownBy(() -> StatementTextMapper.renderDescriptionItem("x".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description")
                .hasMessageContaining(String.valueOf(StatementTextMapper.DESCRIPTION_SOURCE_WIDTH));
    }

    /**
     * Supplies the three descriptions that straddle the declared width of the band item.
     *
     * <p>Assumptions: the three lengths are 48, 49 and 50, one below the declared 49, exactly at it
     * and one above it, because only the case above the boundary can show that the surplus
     * character is discarded and only the case below it can show that the shortfall is
     * blank-filled. A description short of the boundary passes against a narrowing and a
     * non-narrowing implementation alike, so a triple is the smallest set that separates the
     * two.</p>
     *
     * @return a stream of source length, the source description and the 49-character expectation
     */
    private static Stream<Arguments> descriptionsAcrossTheDeclaredWidth() {
        int declared = StatementTextMapper.DESCRIPTION_ITEM_WIDTH;
        String justUnder = "d".repeat(declared - 1);
        String exact = "e".repeat(declared);
        String justOver = "f".repeat(declared) + "!";
        return Stream.of(
                Arguments.of(declared - 1, justUnder, justUnder + " "),
                Arguments.of(declared, exact, exact),
                Arguments.of(declared + 1, justOver, "f".repeat(declared)));
    }

    /**
     * Asserts that the description narrowing is exact at the boundary and on both sides of it.
     *
     * @param sourceLength the length of the description supplied, shown in the case name
     * @param description the description as its 100-character source item holds it
     * @param expected the 49 characters the band item must receive
     */
    @ParameterizedTest(name = "a {0}-character description yields the declared forty-nine")
    @MethodSource("descriptionsAcrossTheDeclaredWidth")
    @DisplayName("renderDescriptionItem is exact at the declared width and on both sides of it")
    void renderDescriptionItemIsExactAtTheDeclaredWidthBoundary(int sourceLength,
            String description, String expected) {
        // WHY : Assumptions: the 50-character case is the one that proves the narrowing, and its
        //       final character is deliberately not the character repeated before it so that its
        //       absence is asserted directly rather than inferred from a length. Line 677 of
        //       app/cbl/CBSTM03A.CBL moves TRNX-DESC, declared PIC X(100) at line 28 of
        //       app/cpy/COSTM01.CPY, into ST-TRANDT, declared PIC X(49) at line 135, and a
        //       character move into a narrower item keeps the LEFTMOST characters.
        assertThat(StatementTextMapper.renderDescriptionItem(description))
                .isEqualTo(expected)
                .hasSize(StatementTextMapper.DESCRIPTION_ITEM_WIDTH);
        assertThat(description).hasSize(sourceLength);
    }

    /**
     * Asserts that a description one character past the boundary loses exactly that character.
     */
    @Test
    @DisplayName("a fiftieth character is dropped rather than displacing the forty-ninth")
    void theFiftiethDescriptionCharacterIsDropped() {
        String source = "g".repeat(StatementTextMapper.DESCRIPTION_ITEM_WIDTH) + "Z";

        // WHY : Assumptions: the discarded character is asserted ABSENT rather than the kept run
        //       asserted present, because a truncation at 48 or at 50 would also leave a run of
        //       g-characters and would also fill the item. Naming the surplus character is what
        //       makes the boundary exact, and it is the boundary the fixture register cannot reach:
        //       src/test/resources/fixtures/README.md declares no TRNX record at all, so no fixture
        //       description exists to be longer than the declared 49.
        assertThat(StatementTextMapper.renderDescriptionItem(source))
                .doesNotContain("Z")
                .isEqualTo("g".repeat(StatementTextMapper.DESCRIPTION_ITEM_WIDTH));
    }

    /**
     * Asserts that the transaction identifier transfers whole, as the control for the narrowings.
     */
    @Test
    @DisplayName("the transaction identifier move is an exact fit, neither narrowed nor padded")
    void theTransactionIdentifierMoveIsAnExactFit() {
        String sixteen = "TRN0000000000042";
        StatementTextMapper.PreparedTransactionFields fields =
                StatementTextMapper.prepareTransactionFields(sixteen, "Coffee", Money.of("1.00"));

        // WHY : Assumptions: an exact-fit transfer is asserted alongside the two narrowing
        //       transfers because it is the control they are read against. Line 676 of
        //       app/cbl/CBSTM03A.CBL moves TRNX-ID, declared PIC X(16) at line 23 of
        //       app/cpy/COSTM01.CPY, into ST-TRANID, declared PIC X(16) at line 133, so source and
        //       target agree and nothing may change. Without it, an implementation that narrowed
        //       everything by one character would still satisfy the two narrowing assertions and
        //       would corrupt this field silently.
        assertThat(fields.transactionId())
                .isEqualTo(sixteen)
                .hasSize(StatementTextMapper.TRANSACTION_ID_WIDTH);
        assertThat(StatementTextMapper.TRANSACTION_ID_WIDTH).isEqualTo(sixteen.length());

        // WHY : Assumptions: the identifier is asserted at its band offset as well as in the
        //       prepared record, because ST-LINE14 at line 132 places it first and follows it with
        //       a one-character declared blank at line 134 before the description at line 135. The
        //       band therefore sums 16 plus 1 plus 49 plus 1 plus 13, and an identifier that filled
        //       17 positions would displace every field after it while still being 80 bytes long.
        String line = text(StatementTextMapper.emitTransactionLine(fields));
        assertThat(line.substring(0, StatementTextMapper.TRANSACTION_ID_WIDTH)).isEqualTo(sixteen);
        assertThat(line.charAt(StatementTextMapper.TRANSACTION_ID_WIDTH)).isEqualTo(' ');
        assertThat(line.substring(StatementTextMapper.TRANSACTION_ID_WIDTH + 1,
                StatementTextMapper.TRANSACTION_ID_WIDTH + 1
                        + StatementTextMapper.DESCRIPTION_ITEM_WIDTH))
                .isEqualTo(padded("Coffee", StatementTextMapper.DESCRIPTION_ITEM_WIDTH));
    }

    /**
     * Asserts that prepareHeaderFields places every component at the exact width its band
     * item declares.
     */
    @Test
    @DisplayName("prepareHeaderFields places every component at the exact width its band item "
            + "declares")
    void prepareHeaderFieldsPlacesEveryComponentAtItsDeclaredWidth() {
        StatementTextMapper.PreparedHeaderFields fields = representativeHeader();

        // WHY : Assumptions: all six character components are asserted in one place because the
        //       preparation takes twelve positional arguments, two of which share the declared
        //       ADDRESS_LINE_WIDTH, so a transposed pair of those two is the one mistake no width
        //       check can catch. Asserting the two address lines against DIFFERENT values is what
        //       separates them, and each expectation is a literal at its declared width so a
        //       component that arrived un-materialised would fail rather than be padded later.

        assertThat(fields.assembledName())
                .isEqualTo(padded("John Q Public", StatementTextMapper.ASSEMBLED_NAME_WIDTH));
        assertThat(fields.addressLine1())
                .isEqualTo(padded("1 Main St", StatementTextMapper.ADDRESS_LINE_WIDTH));
        assertThat(fields.addressLine2())
                .isEqualTo(padded("Apt 2", StatementTextMapper.ADDRESS_LINE_WIDTH));
        assertThat(fields.assembledAddress())
                .isEqualTo(padded("Springfield IL USA 62701",
                        StatementTextMapper.ASSEMBLED_ADDRESS_WIDTH));
        assertThat(fields.accountId()).isEqualTo("00000000001" + " ".repeat(9));
        assertThat(fields.creditScore()).isEqualTo("750" + " ".repeat(17));
    }

    /**
     * Asserts that prepareHeaderFields renders the balance under the regime that keeps
     * leading zeros.
     */
    @Test
    @DisplayName("prepareHeaderFields renders the balance under the regime that keeps leading "
            + "zeros")
    void prepareHeaderFieldsKeepsTheBalanceLeadingZeros() {
        // WHY : Assumptions: the balance regime and the transaction-amount regime are the same 13
        //       characters and differ only in whether the integer positions suppress. Line 113 of
        //       app/cbl/CBSTM03A.CBL declares digit positions and line 137 declares suppression
        //       positions, so the two are asserted side by side for one magnitude: calling the
        //       wrong one still fills the item and still reads as the same number.
        assertThat(representativeHeader().currentBalance()).isEqualTo("000000194.00 ");
        assertThat(StatementTextMapper.prepareTransactionFields("T", "d", Money.of("194.00"))
                .amount()).isEqualTo("      194.00 ");
        assertThat(CobolEditMask.formatStatementBalance(Money.of("0.00")))
                .isEqualTo("000000000.00 ");
    }

    /**
     * Asserts that prepareHeaderFields refuses a balance too wide for the nine-digit mask.
     */
    @Test
    @DisplayName("prepareHeaderFields refuses a balance too wide for the nine-digit mask")
    void prepareHeaderFieldsRefusesAnOverWideBalance() {
        // WHY : Assumptions: this asserts registered divergence D-EDIT-MASK-OVERFLOW. Line 484 of
        //       app/cbl/CBSTM03A.CBL moves a ten-integer-digit balance into a nine-digit field and
        //       silently loses the high-order digit; the target raises instead, so a balance of a
        //       thousand million produces no statement rather than one that understates by at least
        //       that amount.
        assertThatThrownBy(() -> StatementTextMapper.prepareHeaderFields("A", "B", "C", "d", "e",
                "f", "IL", "USA", "62701", 1L, Money.of("1000000000.00"), 700))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("10 integer digits");
    }

    /**
     * Asserts that a balance filling all nine integer positions passes through intact.
     */
    @Test
    @DisplayName("a nine-integer-digit balance passes through intact, so rejection is a boundary "
            + "and not a corruption")
    void aNineIntegerDigitBalancePassesThroughIntact() {
        // WHY : Assumptions: the value one below the rejection threshold is asserted intact so that
        //       the rejection asserted above reads as a boundary rather than as a general failure
        //       on large values. ST-CURR-BAL is declared PIC 9(9).99- at line 113 of
        //       app/cbl/CBSTM03A.CBL, so 999999999.99 is the widest balance the item can hold and
        //       it must survive unchanged; a thousand million is the first value it cannot hold.
        //       The fixture register cannot reach either: the widest balance in
        //       src/test/resources/fixtures/acctfile.txt needs four integer digits, and only a
        //       value at or above a thousand million exercises the narrowing at all.
        StatementTextMapper.PreparedHeaderFields widest = StatementTextMapper.prepareHeaderFields(
                "A", "B", "C", "d", "e", "f", "IL", "USA", "62701", 1L,
                Money.of("999999999.99"), 700);

        assertThat(widest.currentBalance())
                .isEqualTo("999999999.99 ")
                .hasSize(StatementBandLayouts.STATEMENT_AMOUNT_LENGTH);
    }

    /**
     * Asserts that the balance mask carries its sign in the last position and prints no separator.
     */
    @Test
    @DisplayName("the balance mask signs in the trailing position, keeps leading zeros and prints "
            + "no separator")
    void theBalanceMaskSignsInTheTrailingPositionAndPrintsNoSeparator() {
        String positive = StatementTextMapper.prepareHeaderFields("A", "B", "C", "d", "e", "f",
                "IL", "USA", "62701", 1L, Money.of("12.34"), 700).currentBalance();
        String negative = StatementTextMapper.prepareHeaderFields("A", "B", "C", "d", "e", "f",
                "IL", "USA", "62701", 1L, Money.of("-12.34"), 700).currentBalance();

        // WHY : Assumptions: the sign is asserted at the LAST position of thirteen and never at the
        //       first, because the statement's masks at lines 113, 137 and 142 of
        //       app/cbl/CBSTM03A.CBL all place it after the digits while the report's masks at
        //       lines 30, 54, 60 and 66 of app/cpy/CVTRA07Y.cpy place it before them. A
        //       leading-sign rendering would still be a signed amount and would displace all twelve
        //       characters after it.
        assertThat(positive).isEqualTo("000000012.34 ");
        assertThat(negative).isEqualTo("000000012.34-");
        assertThat(positive.charAt(StatementBandLayouts.STATEMENT_AMOUNT_LENGTH - 1))
                .isEqualTo(' ');
        assertThat(negative.charAt(StatementBandLayouts.STATEMENT_AMOUNT_LENGTH - 1))
                .isEqualTo('-');

        // WHY : Assumptions: the absence of a grouping separator is asserted rather than assumed,
        //       because line 113 of app/cbl/CBSTM03A.CBL spells nine consecutive digit positions
        //       with nothing between them where lines 30, 54, 60 and 66 of app/cpy/CVTRA07Y.cpy
        //       break the same nine into groups of three. Two separators would return fifteen
        //       characters into a thirteen-character item.
        assertThat(positive).doesNotContain(",");
        assertThat(StatementTextMapper.prepareHeaderFields("A", "B", "C", "d", "e", "f", "IL",
                "USA", "62701", 1L, Money.of("123456789.00"), 700).currentBalance())
                .isEqualTo("123456789.00 ")
                .doesNotContain(",");
    }

    /**
     * Asserts that one source picture blank-pads into this artifact and zero-pads into the report.
     */
    @Test
    @DisplayName("the same eleven-digit source picture blank-pads here and zero-pads in the report")
    void theSameSourcePictureBlankPadsHereAndZeroPadsInTheReport() {
        long accountId = 7L;
        String statementItem = StatementTextMapper.renderAccountIdItem(accountId);
        String reportField = CobolEditMask.formatUnsignedDigits(accountId,
                StatementTextMapper.ACCOUNT_ID_DIGITS);

        // WHY : Assumptions: both renderings of ONE source picture are asserted here, because
        //       either one alone reads as the general rule and substituting it for the other
        //       produces a plausible byte stream that is wrong. ACCT-ID is declared PIC 9(11) at
        //       line 5 of app/cpy/CVACT01Y.cpy in both paths. Line 483 of app/cbl/CBSTM03A.CBL
        //       moves it into ST-ACCT-ID, declared PIC X(20) at line 109, so the eleven digits land
        //       left-justified with nine BLANKS after them. The report's own account field is
        //       declared exactly eleven wide, so there the digit form transfers whole with its
        //       leading ZEROS and nothing follows it. Both are correct; neither generalises.
        assertThat(statementItem)
                .isEqualTo("00000000007" + " ".repeat(9))
                .hasSize(StatementTextMapper.ACCOUNT_ID_ITEM_WIDTH);
        assertThat(reportField)
                .isEqualTo("00000000007")
                .hasSize(StatementTextMapper.ACCOUNT_ID_DIGITS);

        // WHY : Assumptions: the two are asserted to share a prefix and differ only in the tail, so
        //       the distinction is located precisely. The leading zeros are present in BOTH -- it
        //       is the nine characters after the eleventh that differ, and they are blanks here
        //       rather than further zeros. A reader told only that one pads with zeros and the
        //       other with blanks could otherwise conclude the leading zeros differed too.
        assertThat(statementItem).startsWith(reportField);
        assertThat(statementItem.substring(StatementTextMapper.ACCOUNT_ID_DIGITS))
                .isEqualTo(" ".repeat(9))
                .doesNotContain("0");
    }

    /**
     * Asserts that a zero amount keeps its decimal places here where the report blanks the item.
     */
    @Test
    @DisplayName("a zero statement amount prints its cents where a zero report amount is wholly "
            + "blank")
    void aZeroStatementAmountPrintsItsCentsWhereAZeroReportAmountIsWhollyBlank() {
        String statementZero = StatementTextMapper.prepareCardTrailerFields(Money.ZERO).cardTotal();
        String reportZero = CobolEditMask.formatReportDetailAmount(Money.ZERO);

        // WHY : Assumptions: this is the single most confusable pair in the module, so both zero
        //       renderings are asserted in one place. ST-TOTAL-TRAMT is declared PIC Z(9).99- at
        //       line 142 of app/cbl/CBSTM03A.CBL: its nine integer positions suppress, but its two
        //       decimal positions are ordinary digit positions, so a zero shows nine blanks, a
        //       decimal point, two zeros and a blank sign -- thirteen characters of which three are
        //       not blank. The report's mask at line 30 of app/cpy/CVTRA07Y.cpy suppresses its
        //       decimal places too, so a zero there is fifteen blanks and nothing else.
        assertThat(statementZero)
                .isEqualTo(" ".repeat(9) + ".00 ")
                .hasSize(StatementBandLayouts.STATEMENT_AMOUNT_LENGTH);
        assertThat(reportZero)
                .isEqualTo(" ".repeat(CobolEditMask.REPORT_AMOUNT_WIDTH))
                .isBlank();

        // WHY : Assumptions: the two are asserted UNEQUAL and the statement's is asserted
        //       non-blank, because the four wrong forms a reader reaches for are all shorter and
        //       all parse as the same number: a wholly blank item, a bare decimal point with cents,
        //       an unsuppressed zero before the point, and a signed zero. Naming the exact thirteen
        //       characters is the only assertion that excludes all four.
        assertThat(statementZero).isNotBlank().isNotEqualTo(reportZero);
        assertThat(statementZero).isNotEqualTo("0.00").isNotEqualTo(".00").isNotEqualTo("+0.00");

        // WHY : Assumptions: the balance regime's own zero is asserted beside it, because the two
        //       statement regimes differ only in leading-zero treatment and zero is the magnitude
        //       at which that difference is largest -- nine zeros against nine blanks. Line 113 of
        //       app/cbl/CBSTM03A.CBL spells digit positions where line 142 spells suppression ones.
        assertThat(CobolEditMask.formatStatementBalance(Money.ZERO))
                .isEqualTo("000000000.00 ")
                .hasSameSizeAs(statementZero)
                .isNotEqualTo(statementZero);
    }

    /**
     * Asserts that preparedHeaderFields derives the markup name instead of storing an eighth
     * component.
     */
    @Test
    @DisplayName("PreparedHeaderFields derives the markup name instead of storing an eighth "
            + "component")
    void preparedHeaderFieldsDerivesTheMarkupNameRatherThanStoringIt() {
        StatementTextMapper.PreparedHeaderFields fields = representativeHeader();

        // WHY : Alternatives Considered: storing the narrowed name as an eighth component beside
        //       the assembled one, which is what line 560 of app/cbl/CBSTM03A.CBL literally does by
        //       moving ST-NAME into the separate item L23-NAME declared at line 220. Rejected
        //       because nothing would check that the two components agreed, so an instance could
        //       hold the narrowing of one name beside another name entirely. Pinning the component
        //       list here is what stops the eighth component being reintroduced: it would fail this
        //       assertion before any behaviour changed.
        // WHY : Alternatives Considered: leaving the narrowing where the reference physically puts
        //       it, inside the markup paragraph 5200-WRITE-HTML-NMADBS labelled at line 558 of
        //       app/cbl/CBSTM03A.CBL, so that the markup mapper derived it for itself. Rejected
        //       because one derivation would then exist in two places and the two forms could
        //       drift, and because populate-once-render-twice is the reason the prepared record
        //       exists at all. Relocating it to an accessor of the record the text preparation
        //       returns is a STRUCTURAL change with NO behavioural change, per AAP Rule T9: the
        //       same 50 leftmost characters of the same 75 reach the same consumer, and the byte a
        //       caller sees is unchanged.
        // WHY : Assumptions: the receiving group HTML-L23, opened at line 217 of
        //       app/cbl/CBSTM03A.CBL, is dead as a RENDERING UNIT -- it is never written whole and
        //       carries no closing member for the fragment its line 218 opens -- while its
        //       subordinate L23-NAME at line 220 is LIVE, read at lines 560 and 563. The markup
        //       paragraph rebuilds the element with a STRING into the output record instead of
        //       writing the group, which is why narrowing into that subordinate is a real
        //       obligation even though the group around it emits nothing.
        List<String> componentNames = Arrays.stream(
                        StatementTextMapper.PreparedHeaderFields.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(componentNames).containsExactly("assembledName", "addressLine1", "addressLine2",
                "assembledAddress", "accountId", "currentBalance", "creditScore");
        assertThat(fields.markupName())
                .isEqualTo(StatementTextMapper.narrowNameForMarkup(fields.assembledName()))
                .hasSize(StatementTextMapper.MARKUP_NAME_WIDTH);
    }

    /**
     * Asserts that prepareTransactionFields narrows the description and blanks the amount's
     * leading zeros.
     */
    @Test
    @DisplayName("prepareTransactionFields narrows the description and blanks the amount's leading "
            + "zeros")
    void prepareTransactionFieldsNarrowsAndEditsItsThreeComponents() {
        StatementTextMapper.PreparedTransactionFields fields =
                StatementTextMapper.prepareTransactionFields("TRN0000000000001", "y".repeat(100),
                        Money.of("-4.25"));

        // WHY : Assumptions: the three components are asserted together because the preparation
        //       applies a DIFFERENT rule to each -- the identifier transfers whole by line 676 of
        //       app/cbl/CBSTM03A.CBL, the description narrows by line 677, and the amount is edited
        //       by line 678 under the regime that blanks leading zeros. Asserting one and inferring
        //       the others would leave two of the three rules unexercised on this path.
        assertThat(fields.transactionId()).isEqualTo("TRN0000000000001")
                .hasSize(StatementTextMapper.TRANSACTION_ID_WIDTH);
        assertThat(fields.description())
                .isEqualTo("y".repeat(StatementTextMapper.DESCRIPTION_ITEM_WIDTH));
        assertThat(fields.amount()).isEqualTo("        4.25-");
        assertThat(CobolEditMask.formatStatementAmount(Money.of("0.00")))
                .isEqualTo("         .00 ");
    }

    /**
     * Asserts that prepareCardTrailerFields edits the total under the same regime as a
     * transaction amount.
     */
    @Test
    @DisplayName("prepareCardTrailerFields edits the total under the same regime as a transaction "
            + "amount")
    void prepareCardTrailerFieldsUsesTheAmountRegime() {
        assertThat(StatementTextMapper.prepareCardTrailerFields(Money.of("1000.00")).cardTotal())
                .isEqualTo("     1000.00 ");
        assertThat(StatementTextMapper.prepareCardTrailerFields(Money.of("-1000.00")).cardTotal())
                .isEqualTo("     1000.00-");
    }

    /**
     * The sixteen header lines the reference writes, transcribed character for character.
     *
     * <p>Assumptions: this is declared as a method rather than a constant so that the expectation
     * is rebuilt per test and cannot be mutated by one test for another. Each entry is exactly
     * {@value #STATEMENT_RECORD_LENGTH} characters.</p>
     *
     * <p>Alternatives Considered: writing the hyphen rule out three times, once per declaring band,
     * so that the list mirrored the three separate declarations at lines 101, 120 and 126 of
     * {@code app/cbl/CBSTM03A.CBL} rather than one local value repeated five times. Rejected
     * because the three declarations are byte-identical, so three literals would differ only in
     * name and a reader would look for a difference between them that does not exist. What the
     * repetition actually carries is <b>emission sequence</b>, not byte content -- the first rule
     * band is written at lines 492 and 494 and the third at lines 500 and 502 -- and sequence is
     * asserted positionally by its own test rather than through the shape of this list. Collapsing
     * the expectation is therefore safe precisely because the sequence is pinned elsewhere;
     * collapsing the emission would not be.</p>
     *
     * @return the sixteen expected header lines, in the reference's emission order
     */
    private static List<String> expectedHeaderLines() {
        String rule = "-".repeat(STATEMENT_RECORD_LENGTH);
        return List.of(
                "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31),
                padded("John Q Public", STATEMENT_RECORD_LENGTH),
                padded("1 Main St", STATEMENT_RECORD_LENGTH),
                padded("Apt 2", STATEMENT_RECORD_LENGTH),
                padded("Springfield IL USA 62701", STATEMENT_RECORD_LENGTH),
                rule,
                " ".repeat(33) + "Basic Details" + " ".repeat(34),
                rule,
                padded("Account ID         :00000000001", STATEMENT_RECORD_LENGTH),
                padded("Current Balance    :000000194.00", STATEMENT_RECORD_LENGTH),
                padded("FICO Score         :750", STATEMENT_RECORD_LENGTH),
                rule,
                " ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30),
                rule,
                "Tran ID         " + padded("Tran Details    ", 51) + "  Tran Amount",
                rule);
    }

    /**
     * Asserts that emitHeaderBlock emits sixteen eighty-byte lines, byte for byte, in the
     * reference's order.
     */
    @Test
    @DisplayName("emitHeaderBlock emits sixteen eighty-byte lines, byte for byte, in the "
            + "reference's order")
    void emitHeaderBlockEmitsSixteenLinesByteForByte() {
        List<byte[]> block = StatementTextMapper.emitHeaderBlock(representativeHeader());

        // WHY : Assumptions: the count, every length and every byte are asserted, and the three are
        //       independent. A block of the right length whose lines were reordered would satisfy
        //       the count and the lengths; a block of correct lines missing one would satisfy the
        //       lengths and the bytes of those present. Only the ordered comparison against sixteen
        //       transcribed literals -- line 460 of app/cbl/CBSTM03A.CBL plus the fifteen writes at
        //       lines 488 to 502 -- excludes all three faults at once.

        assertThat(block).hasSize(HEADER_BLOCK_LINES);
        assertThat(block).allSatisfy(line -> assertThat(line).hasSize(STATEMENT_RECORD_LENGTH));
        assertThat(block.stream().map(StatementTextMapperTest::text).toList())
                .containsExactlyElementsOf(expectedHeaderLines());
    }

    /**
     * Asserts that emitHeaderBlock repeats two rule bands rather than collapsing them.
     */
    @Test
    @DisplayName("emitHeaderBlock repeats two rule bands rather than collapsing them")
    void emitHeaderBlockRepeatsTwoRuleBandsRatherThanCollapsingThem() {
        List<String> lines = StatementTextMapper.emitHeaderBlock(representativeHeader()).stream()
                .map(StatementTextMapperTest::text)
                .toList();

        // WHY : Assumptions: the repetition is asserted positionally, because a collapsed block
        //       would leave every remaining line byte-correct and the statement two lines short --
        //       a fault invisible to any per-line check. Lines 492 and 494 of
        //       app/cbl/CBSTM03A.CBL write the first rule band on either side of the basic-details
        //       heading, and lines 500 and 502 do the same around the column headings.
        assertThat(lines.get(5)).isEqualTo(lines.get(7));
        assertThat(lines.get(11)).isEqualTo(lines.get(13));
        assertThat(lines.get(6)).contains("Basic Details");
        assertThat(lines.get(12)).contains("TRANSACTION SUMMARY");
    }

    /**
     * Asserts that a complete statement carries six hyphen rules, five of them in the header
     * block.
     */
    @Test
    @DisplayName("a complete statement carries six hyphen rules, five of them in the header block")
    void aCompleteStatementCarriesSixHyphenRules() {
        // WHY : Assumptions: the rules are counted per SECTION and then summed, rather than only
        //       summed, because the split is what locates a loss. Five fall in the header block,
        //       from lines 492, 494, 498, 500 and 502 of app/cbl/CBSTM03A.CBL, and the sixth in the
        //       card trailer at line 435. A total-only assertion would be satisfied by a block that
        //       lost one rule while the trailer gained one.
        String rule = "-".repeat(STATEMENT_RECORD_LENGTH);
        long inHeader = StatementTextMapper.emitHeaderBlock(representativeHeader()).stream()
                .map(StatementTextMapperTest::text)
                .filter(rule::equals)
                .count();
        long inTrailer = StatementTextMapper
                .emitCardTrailer(StatementTextMapper.prepareCardTrailerFields(Money.of("1.00")))
                .stream()
                .map(StatementTextMapperTest::text)
                .filter(rule::equals)
                .count();

        assertThat(inHeader).isEqualTo(5);
        assertThat(inTrailer).isEqualTo(1);
        assertThat(inHeader + inTrailer)
                .isEqualTo(StatementTextMapper.HYPHEN_RULES_PER_STATEMENT);
    }

    /**
     * Asserts that emitTransactionLine places the currency character in its own position
     * before the amount.
     */
    @Test
    @DisplayName("emitTransactionLine places the currency character in its own position before the "
            + "amount")
    void emitTransactionLinePlacesTheCurrencyCharacterInItsOwnPosition() {
        byte[] line = StatementTextMapper.emitTransactionLine(
                StatementTextMapper.prepareTransactionFields("TRN0000000000001", "Coffee",
                        Money.of("-4.25")));

        assertThat(line).hasSize(STATEMENT_RECORD_LENGTH);
        assertThat(text(line)).isEqualTo("TRN0000000000001 "
                + padded("Coffee", StatementTextMapper.DESCRIPTION_ITEM_WIDTH)
                + "$        4.25-");

        // WHY : Assumptions: the currency character is asserted at its own offset because it is a
        //       separate one-character position declared at line 136 of app/cbl/CBSTM03A.CBL and is
        //       NOT part of the amount mask. A formatter that emitted it would return 14 characters
        //       for a 13-character item and displace the tail of the band.
        assertThat(text(line).charAt(CURRENCY_OFFSET)).isEqualTo('$');
        assertThat(text(line).substring(CURRENCY_OFFSET + 1)).hasSize(13);
    }

    /**
     * Asserts that emitCardTrailer emits three lines and columns the total under the detail
     * amounts.
     */
    @Test
    @DisplayName("emitCardTrailer emits three lines and columns the total under the detail amounts")
    void emitCardTrailerColumnsTheTotalUnderTheDetailAmounts() {
        List<byte[]> trailer = StatementTextMapper
                .emitCardTrailer(StatementTextMapper.prepareCardTrailerFields(Money.of("1000.00")));

        assertThat(trailer).hasSize(CARD_TRAILER_LINES);
        assertThat(trailer).allSatisfy(line -> assertThat(line).hasSize(STATEMENT_RECORD_LENGTH));
        assertThat(trailer.stream().map(StatementTextMapperTest::text).toList()).containsExactly(
                "-".repeat(STATEMENT_RECORD_LENGTH),
                "Total EXP:" + " ".repeat(56) + "$     1000.00 ",
                "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32));

        // WHY : Assumptions: the currency offset of the trailer is asserted EQUAL to the currency
        //       offset of the transaction line, because that equality is the whole purpose of the
        //       56-blank run at line 140 of app/cbl/CBSTM03A.CBL. Resizing that run would still
        //       produce an 80-byte band whose total no longer lines up under the amounts above it.
        assertThat(text(trailer.get(1)).charAt(CURRENCY_OFFSET)).isEqualTo('$');
    }

    /**
     * Asserts that the closing banner uses thirty-two-character asterisk runs where the
     * opening uses thirty-one.
     */
    @Test
    @DisplayName("the closing banner uses thirty-two-character asterisk runs where the "
            + "opening uses thirty-one")
    void theTwoBannersUseDifferentAsteriskRunLengths() {
        String opening = text(StatementTextMapper.emitHeaderBlock(representativeHeader()).get(0));
        String closing = text(StatementTextMapper
                .emitCardTrailer(StatementTextMapper.prepareCardTrailerFields(Money.of("1.00")))
                .get(2));

        // WHY : Assumptions: the two banners are equal in width and unequal in composition, because
        //       the closing sentinel is two characters shorter than the opening one. One run length
        //       would serve neither correctly, so both are asserted rather than one derived from
        //       the other.
        assertThat(opening).hasSize(closing.length());
        assertThat(opening.indexOf("START")).isEqualTo(31);
        assertThat(closing.indexOf("END")).isEqualTo(32);
    }

    /**
     * Asserts that both banner sentinels are exact fits and that they force the two run lengths.
     */
    @Test
    @DisplayName("both sentinels are exact fits, and their two widths are what force runs of "
            + "thirty-one and thirty-two")
    void bothSentinelsAreExactFitsAndForceTheTwoAsteriskRunLengths() {
        // WHY : Assumptions: the two sentinel widths are asserted because they are the arithmetic
        //       that makes the asterisk runs unequal. Line 88 of app/cbl/CBSTM03A.CBL declares
        //       'START OF STATEMENT' in PIC X(18) and line 145 declares 'END OF STATEMENT' in PIC
        //       X(16), both exact fits, so 31 plus 18 plus 31 closes at 80 and 32 plus 16 plus 32
        //       closes at 80 as well. The asymmetry is load-bearing rather than untidy: unifying
        //       the runs at either length breaks whichever banner it is not the right length for.
        assertThat(StatementBandLayouts.START_OF_STATEMENT_SENTINEL)
                .isEqualTo("START OF STATEMENT")
                .hasSize(18);
        assertThat(StatementBandLayouts.END_OF_STATEMENT_SENTINEL)
                .isEqualTo("END OF STATEMENT")
                .hasSize(16);

        assertThat(StatementBandLayouts.OPENING_ASTERISK_RUN_LENGTH).isEqualTo(31);
        assertThat(StatementBandLayouts.CLOSING_ASTERISK_RUN_LENGTH).isEqualTo(32);
        assertThat(StatementBandLayouts.OPENING_ASTERISK_RUN_LENGTH * 2
                + StatementBandLayouts.START_OF_STATEMENT_SENTINEL.length())
                .isEqualTo(STATEMENT_RECORD_LENGTH);
        assertThat(StatementBandLayouts.CLOSING_ASTERISK_RUN_LENGTH * 2
                + StatementBandLayouts.END_OF_STATEMENT_SENTINEL.length())
                .isEqualTo(STATEMENT_RECORD_LENGTH);

        // WHY : Assumptions: the two run lengths are asserted UNEQUAL as well as individually
        //       correct, so that a revision unifying them fails here rather than in one banner's
        //       bytes. Both sums close at 80, which is exactly why a single wrong run length still
        //       produces a plausible-looking banner of the right total width.
        assertThat(StatementBandLayouts.OPENING_ASTERISK_RUN_LENGTH)
                .isNotEqualTo(StatementBandLayouts.CLOSING_ASTERISK_RUN_LENGTH);
    }

    /**
     * Supplies every verbatim label of the statement with the position width that receives it.
     *
     * <p>Assumptions: the expectation is written as a literal beside the constant rather than taken
     * from the constant, so a label whose interior blank run were re-spaced would fail here.
     * Interior runs, one leading pair and one trailing blank are all part of the data, not
     * presentation.</p>
     *
     * @return a stream of the declaring source line, the label constant, the literal it must equal
     *     and the declared width of the position that receives it
     */
    private static Stream<Arguments> verbatimLabels() {
        return Stream.of(
                Arguments.of(105, StatementBandLayouts.BASIC_DETAILS_HEADING, "Basic Details", 14),
                Arguments.of(108, StatementBandLayouts.ACCOUNT_ID_LABEL,
                        "Account ID         :", 20),
                Arguments.of(112, StatementBandLayouts.CURRENT_BALANCE_LABEL,
                        "Current Balance    :", 20),
                Arguments.of(117, StatementBandLayouts.FICO_SCORE_LABEL,
                        "FICO Score         :", 20),
                Arguments.of(124, StatementBandLayouts.TRANSACTION_SUMMARY_HEADING,
                        "TRANSACTION SUMMARY ", 20),
                Arguments.of(129, StatementBandLayouts.TRAN_ID_HEADING, "Tran ID         ", 16),
                Arguments.of(130, StatementBandLayouts.TRAN_DETAILS_HEADING,
                        "Tran Details    ", 51),
                Arguments.of(131, StatementBandLayouts.TRAN_AMOUNT_HEADING, "  Tran Amount", 13),
                Arguments.of(139, StatementBandLayouts.TOTAL_EXPENDITURE_LABEL, "Total EXP:", 10),
                Arguments.of(136, StatementBandLayouts.CURRENCY_SYMBOL, "$", 1));
    }

    /**
     * Asserts that every verbatim label is carried across character for character.
     *
     * @param sourceLine the line of {@code app/cbl/CBSTM03A.CBL} that declares the label
     * @param label the label constant under assertion
     * @param expected the literal the label must equal, blanks included
     * @param positionWidth the declared width of the band position that receives the label
     */
    @ParameterizedTest(name = "the label declared at line {0} is carried across verbatim")
    @MethodSource("verbatimLabels")
    @DisplayName("every verbatim label is carried across character for character, blanks included")
    void everyVerbatimLabelIsCarriedAcrossCharacterForCharacter(int sourceLine, String label,
            String expected, int positionWidth) {
        // WHY : Assumptions: each label is asserted against a literal and its width against the
        //       position that receives it, because a label shorter than its position is completed
        //       by blank padding and one longer than it is rejected outright. Two of the ten are
        //       deliberately shorter: 'Basic Details' is 13 characters in the PIC X(14) of line 105
        //       and 'Tran Details    ' is 16 in the PIC X(51) of line 130, so each is followed by
        //       declared blanks rather than filling its position.
        assertThat(label).isEqualTo(expected);
        assertThat(label.length()).isLessThanOrEqualTo(positionWidth);
        assertThat(sourceLine).isBetween(86, 146);
    }

    /**
     * Asserts that the three basic-detail bands each close at eighty from their own positions.
     */
    @Test
    @DisplayName("the three basic-detail bands close at eighty, and the balance band splits its "
            + "tail into two declared runs")
    void theThreeBasicDetailBandsCloseAtEightyAndTheBalanceBandSplitsItsTail() {
        // WHY : Assumptions: the three bands are asserted individually because two share one shape
        //       and the third does not, and the odd one is the one a reader normalises. ST-LINE7 at
        //       line 107 of app/cbl/CBSTM03A.CBL sums 20 plus 20 plus 40 and ST-LINE9 at line 116
        //       sums the same, but ST-LINE8 at line 111 sums 20 plus 13 plus 7 plus 40 because its
        //       amount item is 13 rather than 20.
        assertThat(declaredFieldSum(StatementBandLayouts.ST_LINE7))
                .isEqualTo(STATEMENT_RECORD_LENGTH);
        assertThat(StatementBandLayouts.ST_LINE7.fields()).hasSize(3);
        assertThat(declaredFieldSum(StatementBandLayouts.ST_LINE9))
                .isEqualTo(STATEMENT_RECORD_LENGTH);
        assertThat(StatementBandLayouts.ST_LINE9.fields()).hasSize(3);

        // WHY : Assumptions: the balance band's FOUR positions are asserted rather than three,
        //       because lines 114 and 115 declare two consecutive blank runs of 7 and 40 where the
        //       neighbouring bands declare one of 40. That is an alignment observation and not a
        //       redundancy: 13 plus 7 is 20, which puts the position after the balance exactly
        //       where the position after the twenty-character value of each neighbour begins.
        //       Merging the two into one run of 47 would tile the band correctly and pass every
        //       geometry check while no longer transcribing its source.
        assertThat(declaredFieldSum(StatementBandLayouts.ST_LINE8))
                .isEqualTo(STATEMENT_RECORD_LENGTH);
        assertThat(StatementBandLayouts.ST_LINE8.fields()).hasSize(4);
        assertThat(StatementBandLayouts.STATEMENT_AMOUNT_LENGTH + 7)
                .isEqualTo(StatementBandLayouts.BASIC_DETAIL_ITEM_LENGTH);
    }

    /**
     * Asserts that declared literals are emitted on every line rather than once at initialisation.
     */
    @Test
    @DisplayName("declared literals are emitted on every line rather than surviving from one "
            + "initialisation")
    void declaredLiteralsAreEmittedOnEveryLineRatherThanSurvivingOneInitialisation() {
        StatementTextMapper.PreparedHeaderFields fields = representativeHeader();
        List<String> first = StatementTextMapper.emitHeaderBlock(fields).stream()
                .map(StatementTextMapperTest::text)
                .toList();
        List<String> second = StatementTextMapper.emitHeaderBlock(fields).stream()
                .map(StatementTextMapperTest::text)
                .toList();

        // WHY : Assumptions: the literals are asserted present on a SECOND emission, because the
        //       reference establishes them once and this assembly must not. Line 459 of
        //       app/cbl/CBSTM03A.CBL opens 5000-CREATE-STATEMENT with INITIALIZE STATEMENT-LINES,
        //       and that statement leaves FILLER untouched, so every label, rule and asterisk run
        //       declared with a VALUE at lines 87 to 146 survives from storage layout and only the
        //       named items are blanked. An assembly that rebuilt each band from nothing has no
        //       such residue, so a literal supplied once would be present on the first statement
        //       and absent from the second -- a fault no single-statement assertion can see.
        assertThat(second).containsExactlyElementsOf(first);
        assertThat(second).containsExactlyElementsOf(expectedHeaderLines());
        assertThat(second.get(6)).contains(StatementBandLayouts.BASIC_DETAILS_HEADING);
        assertThat(second.get(8)).startsWith(StatementBandLayouts.ACCOUNT_ID_LABEL);
        assertThat(second.get(9)).startsWith(StatementBandLayouts.CURRENT_BALANCE_LABEL);
        assertThat(second.get(10)).startsWith(StatementBandLayouts.FICO_SCORE_LABEL);
        assertThat(second.get(14)).startsWith(StatementBandLayouts.TRAN_ID_HEADING);

        // WHY : Assumptions: the trailer is checked the same way and separately, because its
        //       literals come from two further declarations -- 'Total EXP:' at line 139 and the
        //       closing sentinel at line 145 -- that the header block never emits, so a header-only
        //       check would leave both unpinned.
        StatementTextMapper.PreparedTrailerFields trailerFields =
                StatementTextMapper.prepareCardTrailerFields(Money.of("1.00"));
        List<String> trailerFirst = StatementTextMapper.emitCardTrailer(trailerFields).stream()
                .map(StatementTextMapperTest::text)
                .toList();
        assertThat(StatementTextMapper.emitCardTrailer(trailerFields).stream()
                .map(StatementTextMapperTest::text)
                .toList()).containsExactlyElementsOf(trailerFirst);
        assertThat(trailerFirst.get(1)).startsWith(StatementBandLayouts.TOTAL_EXPENDITURE_LABEL);
        assertThat(trailerFirst.get(2)).contains(StatementBandLayouts.END_OF_STATEMENT_SENTINEL);
    }

    /**
     * Supplies the three emission entry points paired with a {@code null} prepared-field argument.
     *
     * <p>Assumptions: all three entry points are covered by one provider because each takes its own
     * prepared-field type, so a check present on one of them says nothing about the other two.
     * Alternatives Considered: three separate tests. Rejected because the assertion is identical in
     * all three cases and three copies would drift, while a provider makes the omission of an entry
     * point visible as a missing row.</p>
     *
     * @return a stream of method label and an invocation that passes {@code null}
     */
    private static Stream<Arguments> nullPreparedFields() {
        return Stream.of(
                Arguments.of("emitHeaderBlock",
                        (Executable) () -> StatementTextMapper.emitHeaderBlock(null)),
                Arguments.of("emitTransactionLine",
                        (Executable) () -> StatementTextMapper.emitTransactionLine(null)),
                Arguments.of("emitCardTrailer",
                        (Executable) () -> StatementTextMapper.emitCardTrailer(null)));
    }

    /**
     * Asserts that every emission entry point rejects null prepared fields by naming the
     * argument.
     *
     * @param label a short label naming the case, shown in the case name
     * @param invocation the emission call that passes {@code null} prepared fields
     */
    @ParameterizedTest(name = "{0} rejects null prepared fields")
    @MethodSource("nullPreparedFields")
    @DisplayName("every emission entry point rejects null prepared fields by naming the argument")
    void everyEmissionEntryPointRejectsNullPreparedFields(String label, Executable invocation) {
        // WHY : Assumptions: the message is asserted to name the argument, because an emission
        //       reached with no prepared fields would otherwise fail later inside the placement
        //       mechanism with a diagnostic naming a band field, which points at this class rather
        //       than at the caller that omitted the argument.
        assertThatThrownBy(invocation::execute)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fields");
    }

    /**
     * Asserts that every emitted line is freshly allocated, so a consumer cannot mutate the
     * next one.
     */
    @Test
    @DisplayName("every emitted line is freshly allocated, so a consumer cannot mutate the next "
            + "one")
    void everyEmittedLineIsFreshlyAllocated() {
        StatementTextMapper.PreparedHeaderFields fields = representativeHeader();
        byte[] first = StatementTextMapper.emitHeaderBlock(fields).get(1);
        Arrays.fill(first, (byte) 'X');

        // WHY : Assumptions: mutating a returned band and re-emitting is the only way to prove the
        //       emitter holds no shared record area. The reference DOES hold one -- line 459 of
        //       app/cbl/CBSTM03A.CBL initialises storage that survives every write -- so a reader
        //       coming from that side would reasonably expect a shared buffer here.
        assertThat(text(StatementTextMapper.emitHeaderBlock(fields).get(1)))
                .isEqualTo(padded("John Q Public", STATEMENT_RECORD_LENGTH));
    }

    /**
     * Asserts that the header rendering withholds every personal and monetary component.
     */
    @Test
    @DisplayName("the header rendering withholds every personal and monetary component")
    void theHeaderRenderingWithholdsEveryPersonalAndMonetaryComponent() {
        String rendered = representativeHeader().toString();

        // WHY : Assumptions: the withheld values are asserted absent one by one rather than by
        //       checking the rendering's length, because the generated record rendering this
        //       replaces printed all seven components and a length check would pass for any
        //       rendering that merely abbreviated them. The account identifier is retained because
        //       it locates the statement and is already the route key of the account reads.
        assertThat(rendered).contains("00000000001");
        assertThat(rendered).doesNotContain("John")
                .doesNotContain("Public")
                .doesNotContain("1 Main St")
                .doesNotContain("Apt 2")
                .doesNotContain("Springfield")
                .doesNotContain("194.00")
                .doesNotContain("750");
    }

    /**
     * Asserts that the transaction rendering withholds the description and the amount.
     */
    @Test
    @DisplayName("the transaction rendering withholds the description and the amount")
    void theTransactionRenderingWithholdsTheDescriptionAndTheAmount() {
        // WHY : Trade-offs: the identifier is retained in the rendering while the description and
        //       the amount are withheld, and the asymmetry is the accepted compromise. The
        //       identifier locates the row and is already the key the transaction is read by, so a
        //       log line that named nothing would be useless for diagnosis; the other two are a
        //       merchant narrative and a monetary value, which the module narrows at its mapping
        //       boundary and must not widen at a diagnostic one. The cost is that a transaction
        //       line cannot be reconstructed from a log entry, which is the intended outcome.
        String rendered = StatementTextMapper
                .prepareTransactionFields("TRN0000000000001", "Confidential merchant",
                        Money.of("-4.25"))
                .toString();

        assertThat(rendered).contains("TRN0000000000001");
        assertThat(rendered).doesNotContain("Confidential").doesNotContain("4.25");
    }

    /**
     * Asserts that the trailer rendering withholds the total and names the component it
     * withheld.
     */
    @Test
    @DisplayName("the trailer rendering withholds the total and names the component it withheld")
    void theTrailerRenderingWithholdsTheTotal() {
        String rendered =
                StatementTextMapper.prepareCardTrailerFields(Money.of("1000.00")).toString();

        assertThat(rendered).contains("cardTotal").contains("withheld");
        assertThat(rendered).doesNotContain("1000.00");
    }

    /**
     * Supplies the three prepared-field types with the component names each must expose.
     *
     * <p>Assumptions: the names are written out rather than derived, because they are the contract
     * the markup rendering of the same statement binds to. A rename made only in the mapper would
     * compile everywhere and would break that consumer, so the names are pinned in a test the
     * rename has to pass through.</p>
     *
     * @return a stream of the prepared-field type and the component names it must expose, in
     *     declaration order
     */
    private static Stream<Arguments> preparedFieldContracts() {
        return Stream.of(
                Arguments.of(StatementTextMapper.PreparedHeaderFields.class,
                        List.of("assembledName", "addressLine1", "addressLine2", "assembledAddress",
                                "accountId", "currentBalance", "creditScore")),
                Arguments.of(StatementTextMapper.PreparedTransactionFields.class,
                        List.of("transactionId", "description", "amount")),
                Arguments.of(StatementTextMapper.PreparedTrailerFields.class,
                        List.of("cardTotal")));
    }

    /**
     * Asserts that each prepared-field type is an immutable record exposing its declared
     * components.
     *
     * @param preparedType the prepared-field type under assertion
     * @param componentNames the component names the type must expose, in declaration order
     */
    @ParameterizedTest(name = "{0} is an immutable record with its declared components")
    @MethodSource("preparedFieldContracts")
    @DisplayName("each prepared-field type is an immutable record exposing its declared components")
    void eachPreparedFieldTypeIsAnImmutableRecord(Class<?> preparedType,
            List<String> componentNames) {
        // WHY : Assumptions: the record kind is asserted rather than taken from the declaration,
        //       because it is what supplies the immutability the two renderings depend on -- final
        //       fields, no setter and component-wise equality. A class rewritten as an ordinary
        //       carrier could keep every accessor name and this test's other assertions would still
        //       pass while a consumer became able to alter values between the two renderings.
        assertThat(preparedType.isRecord()).isTrue();
        assertThat(Arrays.stream(preparedType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList()).containsExactlyElementsOf(componentNames);

        // WHY : Assumptions: every component's backing field is asserted final AND its declared
        //       type asserted to be the platform's immutable character sequence, which together
        //       remove the need for any defensive copy. A component of an array or collection type
        //       would need one, so asserting the types is what keeps that obligation from arriving
        //       unnoticed: the prepared values are edited strings at declared widths, per lines 462
        //       to 485 and 676 to 678 of app/cbl/CBSTM03A.CBL, and none of them is a container.
        assertThat(preparedType.getDeclaredFields()).allSatisfy(field -> {
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            assertThat(field.getType()).isEqualTo(String.class);
        });
        assertThat(preparedType.getMethods())
                .noneSatisfy(method -> assertThat(method.getName()).startsWith("set"));
    }

    /**
     * Asserts that preparing once and rendering twice yields identical bytes and leaves the record
     * unchanged.
     */
    @Test
    @DisplayName("preparing once and rendering twice yields identical bytes and leaves the "
            + "prepared record unchanged")
    void preparingOnceAndRenderingTwiceYieldsIdenticalBytes() {
        StatementTextMapper.PreparedHeaderFields header = representativeHeader();
        StatementTextMapper.PreparedHeaderFields expected = representativeHeader();

        List<String> firstRender = StatementTextMapper.emitHeaderBlock(header).stream()
                .map(StatementTextMapperTest::text)
                .toList();
        List<String> secondRender = StatementTextMapper.emitHeaderBlock(header).stream()
                .map(StatementTextMapperTest::text)
                .toList();

        // WHY : Assumptions: one preparation rendered twice is the property the whole
        //       prepared-record design exists for, and it is asserted on ONE instance rather than
        //       on two equal ones. 01 STATEMENT-LINES is populated once per card at lines 462 to
        //       485 of app/cbl/CBSTM03A.CBL and read by both artifacts -- the plain-text bands at
        //       lines 488 to 502 and the markup paragraph from line 560 -- so a preparation that
        //       were consumed by its first reader would leave the second rendering a different
        //       document.
        assertThat(secondRender).containsExactlyElementsOf(firstRender);

        // WHY : Assumptions: the record is asserted equal to a separately built instance of the
        //       same inputs AFTER rendering, which is what shows rendering consumed nothing.
        //       Component-wise equality is the generated one, so this compares all seven values
        //       rather than an identity, and the derived narrowed name is compared as well because
        //       it is recomputed from a component on every call and would follow any change to it.
        assertThat(header).isEqualTo(expected);
        assertThat(header.markupName()).isEqualTo(expected.markupName());
        assertThat(header.assembledName()).isEqualTo(expected.assembledName());
    }

    /**
     * Asserts that every emission is a pure function of its caller-supplied values.
     */
    @Test
    @DisplayName("every emission is a pure function of its inputs, so no member reads a clock")
    void everyEmissionIsAPureFunctionOfItsInputs() {
        StatementTextMapper.PreparedTransactionFields transaction =
                StatementTextMapper.prepareTransactionFields("TRN0000000000001", "Coffee",
                        Money.of("-4.25"));
        StatementTextMapper.PreparedTrailerFields trailer =
                StatementTextMapper.prepareCardTrailerFields(Money.of("-4.25"));

        // WHY : Assumptions: byte identity across two invocations is asserted for all three
        //       emission methods, because a clock read anywhere in the path would make a
        //       golden-master comparison impossible while every individual band still had the right
        //       width and shape. There is nothing here for a current instant to be a candidate
        //       value for: the two timestamp items of the input record, both declared PIC X(26) at
        //       lines 34 and 35 of app/cpy/COSTM01.CPY, reach no ST-LINE field of lines 86 to 146
        //       of app/cbl/CBSTM03A.CBL, so no band carries a date at all.
        assertThat(text(StatementTextMapper.emitTransactionLine(transaction)))
                .isEqualTo(text(StatementTextMapper.emitTransactionLine(transaction)));
        assertThat(StatementTextMapper.emitCardTrailer(trailer).stream()
                .map(StatementTextMapperTest::text)
                .toList())
                .containsExactlyElementsOf(StatementTextMapper.emitCardTrailer(trailer).stream()
                        .map(StatementTextMapperTest::text)
                        .toList());

        // WHY : Assumptions: rebuilding the prepared values from the same arguments is asserted to
        //       give the same bytes as well, so the purity claim covers preparation and not
        //       emission alone. A clock consulted while preparing would be just as fatal and would
        //       not show up in a re-emission of one already-prepared record.
        assertThat(text(StatementTextMapper.emitTransactionLine(
                StatementTextMapper.prepareTransactionFields("TRN0000000000001", "Coffee",
                        Money.of("-4.25")))))
                .isEqualTo(text(StatementTextMapper.emitTransactionLine(transaction)));

        // WHY : Assumptions: every character of the transaction band is accounted for by a
        //       caller-supplied value or a declared literal, which is the positive form of the same
        //       claim. The band is the identifier, the declared blank of line 134, the narrowed
        //       description, the currency character of line 136 and the edited amount -- 16 plus 1
        //       plus 49 plus 1 plus 13 -- with no position left for anything this class could have
        //       sourced itself.
        assertThat(text(StatementTextMapper.emitTransactionLine(transaction)))
                .isEqualTo(transaction.transactionId() + StatementBandLayouts.SINGLE_BLANK
                        + transaction.description() + StatementBandLayouts.CURRENCY_SYMBOL
                        + transaction.amount());
    }
}
