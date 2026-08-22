package com.carddemo.reporting.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the 80-character geometry of all seventeen statement bands and every literal they carry.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class is where the byte arithmetic of {@link StatementBandLayouts} stops being a
 * transcription a reviewer has to trust and becomes a fact a build proves. It asserts three
 * separable things about each of the seventeen {@code ST-LINE} groups declared at lines 86 to 146
 * of {@code app/cbl/CBSTM03A.CBL}: that the descriptor declares a record length of
 * {@value #STATEMENT_RECORD_LENGTH}, that the widths transcribed independently here sum to that
 * same figure, and that the fields tile the record contiguously from offset zero. It then asserts
 * every label, heading, sentinel and rule literal character for character, including the internal
 * and trailing blanks that are the easiest bytes in the artifact to lose.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a
 * caller invokes, no value it yields and no exception it raises to anything outside the test
 * engine, so the type itself accepts no parameter, returns nothing and throws nothing. The
 * inapplicability is stated rather than passed over, because user-specified Rule 1
 * (Explainability) forbids at its line 39 a docstring that omits parameters, return values or
 * purpose, and a reader has to be able to tell a declared inapplicability from an oversight. Every
 * member below carries its own parameter, return and exception at-clauses.</p>
 *
 * <h2>Assumptions: the declared length and the summed length are two facts, so both are asserted</h2>
 *
 * <p>{@code reclen} and the sum of the field widths are checked as separate assertions rather than
 * one, because they come from different places and can disagree. The declared
 * {@value #STATEMENT_RECORD_LENGTH} is a property of the data set, established by
 * {@code 01 FD-STMTFILE-REC PIC X(80).} at line 45 of {@code app/cbl/CBSTM03A.CBL} and corroborated
 * by {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} at line 89 of {@code app/jcl/CREASTMT.JCL}, in
 * the step that runs the statement program at line 79 of that job. The sum is a property of the
 * seventeen groups' own picture clauses. A descriptor that declared the right total while
 * mistranscribing two widths that happened to cancel would satisfy a single combined check and
 * still place every column after the first error one or more characters out; asserting the widths
 * item by item and the total separately is what makes that cancellation visible.</p>
 *
 * <h2>Assumptions: every band is natively 80, which is the exact inverse of the report</h2>
 *
 * <p>All seventeen bands reach {@value #STATEMENT_RECORD_LENGTH} from their own declared pictures
 * with nothing added, so <b>the plain-text statement is padding-free and no band here may be
 * padded.</b> That is load-bearing rather than incidental: a consumer that padded a statement band
 * would push bytes past the end of an 80-character record.</p>
 *
 * <p>The 133-column daily transaction report stands in the opposite relation to its declared
 * length. Six of the seven bands of {@code app/cpy/CVTRA07Y.cpy} are short of 133 and reach it only
 * through the padding the shared fixed-width codec applies on encode, which is why that artifact
 * needs an explicit trailing pad entry per short band and this one needs none. The contrast is
 * recorded here, and again on {@link StatementBandLayouts} itself, so that a maintainer who reads
 * only one of the two descriptor holders cannot carry one artifact's padding obligation into the
 * other. Carried the wrong way it is silent in both directions: padding a statement band overruns
 * its record, and declining to pad a report band leaves it short, and a text line that is wrong by
 * a handful of blanks still looks like text.</p>
 *
 * <h2>Assumptions: there are seventeen bands and not sixteen</h2>
 *
 * <p>The count is asserted explicitly because the numeric sequence lies about it.
 * {@code ST-LINE0} through {@code ST-LINE15} is sixteen names, and the seventeenth is
 * {@code ST-LINE14A} at line 138 of {@code app/cbl/CBSTM03A.CBL}, which breaks the sequence by
 * carrying a trailing letter instead of the next number. It is the per-card trailer band holding
 * the {@value StatementBandLayouts#TOTAL_EXPENDITURE_LABEL} label and the statement total, and it
 * is written at line 436 of that program. A holder that stopped at the sixteen numbered names would
 * pass every geometry check it declared and would emit a statement with no total line at all, so
 * the count is pinned rather than inferred from the constants that happen to exist.</p>
 *
 * <h2>Assumptions: the three identical rule bands are three declarations, not one constant</h2>
 *
 * <p>{@code ST-LINE5}, {@code ST-LINE10} and {@code ST-LINE12} are byte-identical -- each is a
 * single {@code FILLER VALUE ALL '-' PIC X(80)}, at lines 102, 121 and 127 respectively -- and the
 * obvious economy is to collapse them into one shared constant. This class asserts all three
 * separately, and the ground is the emission sequence rather than tidiness. The program writes
 * {@code ST-LINE5} twice, at lines 492 and 494, {@code ST-LINE10} once, at line 498, and
 * {@code ST-LINE12} twice at lines 500 and 502 plus once more in the per-card trailer at line 435:
 * six rule lines per statement, from three named sources. A single shared descriptor cannot express
 * that count, so a statement assembled from it would have the wrong number of lines while every
 * individual line still compared equal, and it would break the one-to-one correspondence between
 * band and baseline paragraph that {@code docs/architecture/cobol-to-service-traceability.md}
 * records.</p>
 *
 * <h2>Assumptions: the two banner asterisk runs are 31 and 32, and neither figure serves both</h2>
 *
 * <p>{@code ST-LINE0} splits 31, 18, 31 and {@code ST-LINE15} splits 32, 16, 32. The runs differ
 * because the sentinels do: {@value StatementBandLayouts#START_OF_STATEMENT_SENTINEL} is eighteen
 * characters at line 88 and {@value StatementBandLayouts#END_OF_STATEMENT_SENTINEL} is sixteen at
 * line 145, so the asterisk runs absorb the two-character difference and both banners still total
 * {@value #STATEMENT_RECORD_LENGTH}. Both widths are therefore asserted against their own band. A
 * single shared run width would be wrong for one of the two banners by exactly two characters,
 * which is the sort of error that leaves a banner looking plausible.</p>
 *
 * <h2>Trade-offs: the two consecutive blank items of ST-LINE8 are asserted as two</h2>
 *
 * <p>{@code ST-LINE8} ends in two separately declared blank items, {@code PIC X(07)} at line 114 of
 * {@code app/cbl/CBSTM03A.CBL} and {@code PIC X(40)} at line 115, where one item of 47 characters
 * would produce a byte-identical record. This class asserts both, and the cost is an assertion that
 * looks redundant to a reader who has only the output in mind. It is accepted because no mechanical
 * check can catch the merge: a single 47-character entry tiles the band correctly, sums to
 * {@value #STATEMENT_RECORD_LENGTH} and passes the descriptor's own geometry validation, so the
 * only thing lost would be that the field list stopped transcribing its source, and the only place
 * that can be noticed is here. The baseline declares two items; the Java descriptor declares two
 * items; there is no divergence to register, and this paragraph is an observation about an
 * immutable reference rather than a finding against it.</p>
 *
 * <h2>Alternatives Considered: asserting how the two amount masks render</h2>
 *
 * <p>Three fields hold an edited amount and all three are {@value #STATEMENT_AMOUNT_WIDTH}
 * characters wide: {@code ST-CURR-BAL PIC 9(9).99-} at line 113, which preserves leading zeros, and
 * {@code ST-TRANAMT PIC Z(9).99-} at line 137 with {@code ST-TOTAL-TRAMT PIC Z(9).99-} at line 142,
 * which blank them. The two pictures are the same shape at the same width and differ only in that
 * suppression, so a width assertion alone cannot tell them apart and the temptation is to assert
 * the rendered forms here as well.</p>
 *
 * <p>That was evaluated and rejected. The rendering is owned by {@link CobolEditMask} and is
 * already proven, case by case, in {@code CobolEditMaskTest}; asserting it a second time here would
 * give one contract two owners, and two owners of one contract drift the first time either is
 * amended. This class therefore stops at geometry: that each descriptor allocates
 * {@value #STATEMENT_AMOUNT_WIDTH} characters at the offset its picture implies, with the field
 * kind and digit counts the placement mechanism needs. The distinction the width cannot draw is
 * drawn where the formatter lives, which is also the only place it can be drawn by exercising the
 * behaviour rather than by describing it.</p>
 *
 * <h2>Assumptions: a literal is asserted byte for byte because a blank is data</h2>
 *
 * <p>Every label, heading and sentinel is asserted against its exact declared text, internal and
 * trailing blanks included, under AAP Rule T8, which carries user-visible strings across character
 * for character. Four of them are the reason the assertion cannot be relaxed to a trimmed
 * comparison: {@value StatementBandLayouts#BASIC_DETAILS_HEADING} is thirteen characters inside a
 * fourteen-character item at line 105, so one blank sits inside the item and the visible text
 * stands one column left of centre; the transaction summary heading at line 124 is twenty
 * characters only because it ends in a blank, and the text alone is nineteen;
 * {@value StatementBandLayouts#TRAN_DETAILS_HEADING} is sixteen characters inside a
 * fifty-one-character item at line 130; and the amount heading at line 131 begins with two blanks,
 * which is what right-aligns it over the {@value #STATEMENT_AMOUNT_WIDTH}-character mask directly
 * beneath it. Trimming any of the four still yields readable English and moves bytes the parity
 * comparison expects where they are.</p>
 *
 * <h2>Assumptions: a FILLER carrying a VALUE is content, so its bytes are part of the artifact</h2>
 *
 * <p>Line 459 of {@code app/cbl/CBSTM03A.CBL} opens {@code 5000-CREATE-STATEMENT} with
 * {@code INITIALIZE STATEMENT-LINES.}, and that statement leaves {@code FILLER} untouched. In the
 * reference every {@code VALUE ALL} run and every label literal is therefore established once when
 * storage is laid out and survives every later statement, while only the named items are blanked.
 * An assembly that rebuilds a band field by field has no such carry-over, so each of those literals
 * has to be emitted explicitly, which is why each is a declared constant and each gets its own
 * assertion here rather than being treated as padding that any blank would satisfy.</p>
 *
 * <h2>Assumptions: 80 is the text width and 100 is the HTML width</h2>
 *
 * <p>The same program writes a second artifact at a different length, and the two are never
 * interchangeable. {@code 01 FD-HTMLFILE-REC PIC X(100).} at line 47 declares it, the items
 * {@code HTML-ADDR-LN}, {@code HTML-BSIC-LN} and {@code HTML-TRAN-LN} at lines 221 to 223 are each
 * {@code PIC X(100)}, and {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)} at line 94 of
 * {@code app/jcl/CREASTMT.JCL} confirms it from outside the program. This class asserts
 * {@value #STATEMENT_RECORD_LENGTH} and never {@value #HTML_RECORD_LENGTH}; the 100-character
 * contract belongs to the test that owns the HTML emitter. Both figures are named here so that a
 * reader who meets one of them in this file cannot mistake it for the other, and this is an
 * observation about an immutable reference rather than a finding against it.</p>
 *
 * <p>Assumptions: lines 69 and 73 of {@code app/jcl/CREASTMT.JCL} also carry
 * {@code LRECL=80}, and those two occurrences say nothing about either artifact. They sit inside
 * the {@code DISP=(MOD,DELETE,DELETE)} stanzas of the step at line 66, which invokes a utility that
 * writes no data, so the attributes declared there govern nothing. The corroborating occurrence is
 * the one at line 89.</p>
 *
 * <h2>Alternatives Considered: exercising the formatter or the codec from here</h2>
 *
 * <p>This class imports {@link CopybookLayout} and nothing else from the migration. It does not
 * call {@link CobolEditMask}, it does not call the shared fixed-width codec, and it stands up no
 * Spring context, database container or cloud emulator. Driving a band end to end -- formatting an
 * amount, encoding a record, comparing 80 bytes -- was considered and rejected for this file: it
 * would pass or fail for reasons belonging to three units at once, so a failure would not localise,
 * and the geometry error this file exists to catch would be reported as an output mismatch several
 * layers from its cause. A layout is a declaration, and a declaration is checked by reading it.</p>
 *
 * <p>Assumptions: {@code StatementTextMapper} is the sole consumer of
 * {@link StatementBandLayouts}, and {@code StatementHtmlMapper} does not consume it at all. The
 * HTML emitter builds {@value #HTML_RECORD_LENGTH}-character lines from fragment constants rather
 * than from these descriptors, so no assertion here presumes an edge from the HTML side. Asserting
 * a dependency that does not exist would make this file fail when that emitter changed for reasons
 * this artifact has no stake in.</p>
 *
 * <h2>Trade-offs: the shared checks are parameterized and the singular ones are not</h2>
 *
 * <p>The six properties that every band must have are asserted once through a parameterized test
 * over a transcription of all seventeen, and the properties that belong to one or two bands are
 * asserted in their own methods. The compromise is that a parameterized failure names its band
 * through the case name rather than through the method name, so a reader has to look at the
 * reported case to know which of the seventeen broke. It is accepted because the alternative --
 * seventeen near-identical methods per shared property -- would put the same assertion in
 * seventeen places, and a property amended in sixteen of them is a far worse failure than a
 * failure message that needs one extra glance. Each case carries the reference line it was
 * transcribed from, so a failure cites {@code app/cbl/CBSTM03A.CBL} directly.</p>
 *
 * <h2>Baseline framing and scope</h2>
 *
 * <p>Everything under {@code app/} is reference material and stays byte-identical; no statement in
 * this file describes an edit to it. Where migrated behaviour differs from the reference, the
 * difference is registered in {@code docs/architecture/cobol-to-service-traceability.md}, which
 * owns that register. Nothing in this file registers one, because a declared width and a declared
 * literal admit no divergence: they either transcribe their source or they are wrong.</p>
 *
 * <p>Assumptions: the fixed-arity limits of the statement program's own working-storage tables are
 * out of scope here and are deliberately not restated. They are a service-layer concern belonging
 * to the statement service and to the traceability register, and a layout descriptor has no arity
 * to remove -- the seventeen bands are print-line geometry and carry no table at all.</p>
 *
 * <p>Assumptions: the four justification labels used below -- {@code Alternatives Considered:},
 * {@code Refactoring Rationale:}, {@code Assumptions:} and {@code Trade-offs:} -- are spelled as
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes them: plural, unparenthesised, each closed by
 * its own colon, never wrapped in emphasis markup, and never mixed with any other form inside one
 * file. {@code Refactoring Rationale:} is deliberately unused, because it applies when existing
 * code is replaced and this file replaces nothing. Inline comments carry a canonical rationale and
 * nothing else, with purpose stated once in the Javadoc where the language puts it and no statement
 * carrying a {@code WHAT:} line, as that standard's prose-command-blocks-only section requires of
 * every {@code .java} file.</p>
 *
 * <p>Trade-offs: this file is restricted to printable ASCII, and where a cited source carries a
 * non-breaking hyphen or a dash it uses an ASCII hyphen-minus or a pair of ASCII hyphens. The
 * reason is concrete rather than aesthetic: {@code tests/README.md} spells a justification label
 * with a non-breaking hyphen at its line 548, so text copied from there yields a label that looks
 * correct, greps wrong, and escapes an audit searching for the canonical spelling. The accepted
 * cost is typographically plainer prose.</p>
 *
 * @see StatementBandLayouts
 * @see CopybookLayout
 */
class StatementBandLayoutsTest {

    /** The declared statement record length, from {@code app/cbl/CBSTM03A.CBL} line 45. */
    private static final int STATEMENT_RECORD_LENGTH = 80;

    /** The declared HTML record length, named here only so it can be shown never to be asserted. */
    private static final int HTML_RECORD_LENGTH = 100;

    /** The declared width of each edited amount: nine digits, a point, two digits and a sign. */
    private static final int STATEMENT_AMOUNT_WIDTH = 13;

    /** The number of {@code ST-LINE} groups declared at lines 86 to 146 of the reference program. */
    private static final int DECLARED_BAND_COUNT = 17;

    /** The zero-based offset at which both amount-bearing bands place their edited amount. */
    private static final int AMOUNT_COLUMN_OFFSET = 67;

    /** The declared width shared by each basic-details label and the value item beside it. */
    private static final int BASIC_DETAIL_ITEM_WIDTH = 20;

    /** The width of the opening banner's two asterisk runs, at lines 87 and 89. */
    private static final int OPENING_RUN_WIDTH = 31;

    /** The width of the closing banner's two asterisk runs, at lines 144 and 146. */
    private static final int CLOSING_RUN_WIDTH = 32;

    /**
     * One band's geometry, transcribed by hand from the picture clauses that declare it.
     *
     * <p>Assumptions: the widths here are read from {@code app/cbl/CBSTM03A.CBL} rather than from
     * {@link StatementBandLayouts}, which is the whole point of the type. A transcription taken from
     * the descriptor under test would assert only that the descriptor equals itself, so this record
     * is a second, independent reading of the same reference block and the assertions compare the
     * two readings against each other.</p>
     *
     * <p>Alternatives Considered: carrying the raw picture strings, such as {@code X(31)}, and
     * deriving the widths from them. Rejected because it would put a small picture-clause parser in
     * a test, and a parser is a thing that can itself be wrong -- silently, and in the same
     * direction for every band, which is precisely the failure a second independent reading exists
     * to catch. An integer is checkable against one clause by eye.</p>
     *
     * @param bandName the declared group name, spelled exactly as the reference declares it, which
     *     for the seventeenth band means the trailing letter of {@code ST-LINE14A}
     * @param referenceLine the line of {@code app/cbl/CBSTM03A.CBL} declaring the level-05 group, so
     *     a failure cites the reference rather than only the expected number
     * @param fieldNames the descriptor's field names in declaration order, with each {@code FILLER}
     *     named by its one-based occurrence within the band
     * @param fieldWidths the declared character width of each field, in the same order, transcribed
     *     from that field's own picture clause
     */
    private record BandExpectation(
            String bandName, int referenceLine, List<String> fieldNames, List<Integer> fieldWidths) {

        /**
         * Names this band and the reference line it was transcribed from.
         *
         * <p>Assumptions: this form is what a parameterized case name shows, so it is kept to the
         * two facts that identify the case. The generated record form would print both lists in
         * full, which pushes the band name off the end of a console line and makes a run of
         * seventeen cases unreadable.</p>
         *
         * @return the band name followed by its declaring line in the reference program
         */
        @Override
        public String toString() {
            return bandName + " at CBSTM03A.CBL L" + referenceLine;
        }
    }

    /**
     * Supplies an independent transcription of all seventeen bands in declaration order.
     *
     * <p>Assumptions: every band is enumerated rather than a representative few, because the
     * properties asserted over this stream are exactly the ones that hold for all seventeen or the
     * artifact is broken. Sampling would leave the unexercised bands free to drift, and a band that
     * drifted would still emit 80 readable characters.</p>
     *
     * <p>Assumptions: the order matches lines 86 to 146 of {@code app/cbl/CBSTM03A.CBL}, so the
     * stream can be read straight down against that block, and it is declaration order rather than
     * emission order. The two differ: the program's write sequence repeats two of the rule bands and
     * is split across three paragraphs, so an emission-ordered list would have twenty entries and
     * would belong to the consumer that walks it.</p>
     *
     * @return one argument set per band, each carrying the band name, its declaring line, its field
     *     names in declaration order and each field's declared character width
     */
    private static Stream<Arguments> everyDeclaredBand() {
        return Stream.of(
                Arguments.of(new BandExpectation("ST-LINE0", 86,
                        List.of("FILLER-1", "FILLER-2", "FILLER-3"), List.of(31, 18, 31))),
                Arguments.of(new BandExpectation("ST-LINE1", 90,
                        List.of("ST-NAME", "FILLER-1"), List.of(75, 5))),
                Arguments.of(new BandExpectation("ST-LINE2", 93,
                        List.of("ST-ADD1", "FILLER-1"), List.of(50, 30))),
                Arguments.of(new BandExpectation("ST-LINE3", 96,
                        List.of("ST-ADD2", "FILLER-1"), List.of(50, 30))),
                Arguments.of(new BandExpectation("ST-LINE4", 99,
                        List.of("ST-ADD3"), List.of(80))),
                Arguments.of(new BandExpectation("ST-LINE5", 101,
                        List.of("FILLER-1"), List.of(80))),
                Arguments.of(new BandExpectation("ST-LINE6", 103,
                        List.of("FILLER-1", "FILLER-2", "FILLER-3"), List.of(33, 14, 33))),
                Arguments.of(new BandExpectation("ST-LINE7", 107,
                        List.of("FILLER-1", "ST-ACCT-ID", "FILLER-2"), List.of(20, 20, 40))),
                Arguments.of(new BandExpectation("ST-LINE8", 111,
                        List.of("FILLER-1", "ST-CURR-BAL", "FILLER-2", "FILLER-3"),
                        List.of(20, 13, 7, 40))),
                Arguments.of(new BandExpectation("ST-LINE9", 116,
                        List.of("FILLER-1", "ST-FICO-SCORE", "FILLER-2"), List.of(20, 20, 40))),
                Arguments.of(new BandExpectation("ST-LINE10", 120,
                        List.of("FILLER-1"), List.of(80))),
                Arguments.of(new BandExpectation("ST-LINE11", 122,
                        List.of("FILLER-1", "FILLER-2", "FILLER-3"), List.of(30, 20, 30))),
                Arguments.of(new BandExpectation("ST-LINE12", 126,
                        List.of("FILLER-1"), List.of(80))),
                Arguments.of(new BandExpectation("ST-LINE13", 128,
                        List.of("FILLER-1", "FILLER-2", "FILLER-3"), List.of(16, 51, 13))),
                Arguments.of(new BandExpectation("ST-LINE14", 132,
                        List.of("ST-TRANID", "FILLER-1", "ST-TRANDT", "FILLER-2", "ST-TRANAMT"),
                        List.of(16, 1, 49, 1, 13))),
                Arguments.of(new BandExpectation("ST-LINE14A", 138,
                        List.of("FILLER-1", "FILLER-2", "FILLER-3", "ST-TOTAL-TRAMT"),
                        List.of(10, 56, 1, 13))),
                Arguments.of(new BandExpectation("ST-LINE15", 143,
                        List.of("FILLER-1", "FILLER-2", "FILLER-3"), List.of(32, 16, 32))));
    }

    /**
     * Pins the declared record length of every band to the 80 characters the data set holds.
     *
     * @param expectation the band under test, carrying its name and its declaring reference line
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyDeclaredBand")
    @DisplayName("every band declares a record length of 80 characters")
    void everyBandDeclaresTheStatementRecordLength(BandExpectation expectation) {
        CopybookLayout.RecordSpec band = StatementBandLayouts.band(expectation.bandName());

        // WHY : Assumptions: 80 is a property of the data set and not of any band, so it is asserted
        //       against the same figure for all seventeen. It is declared 01 FD-STMTFILE-REC PIC
        //       X(80). at app/cbl/CBSTM03A.CBL L45 under the FD STMT-FILE. at L44, and corroborated
        //       from outside the program by DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB) at
        //       app/jcl/CREASTMT.JCL L89, in the step that runs CBSTM03A at L79 of that job. A band
        //       declaring anything else would be rejected by the codec at the first write, but it
        //       would be rejected as a record-length error rather than as the transcription error it
        //       actually is, which is why the figure is pinned at the declaration.
        assertThat(band.reclen()).as("declared record length of %s", expectation)
                .isEqualTo(STATEMENT_RECORD_LENGTH);

        // WHY : Assumptions: the descriptor's own published figure is compared against this file's
        //       independent transcription of L45 rather than being taken as the definition. Reading
        //       80 from the class under test and asserting the class equals it would assert nothing;
        //       the two readings have to be able to disagree for the comparison to carry weight.
        assertThat(StatementBandLayouts.STATEMENT_LINE_LENGTH).isEqualTo(STATEMENT_RECORD_LENGTH);
    }

    /**
     * Pins the independently transcribed widths of every band to a native sum of exactly 80.
     *
     * @param expectation the band under test, carrying the widths read from its own picture clauses
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyDeclaredBand")
    @DisplayName("every band's declared widths sum to 80 natively, with nothing padded")
    void everyBandSumsToEightyFromItsOwnPictureClauses(BandExpectation expectation) {
        int transcribedSum = expectation.fieldWidths().stream().mapToInt(Integer::intValue).sum();

        // WHY : Assumptions: this sum is computed from the picture clauses of
        //       app/cbl/CBSTM03A.CBL alone and never from the descriptor, so it is the half of the
        //       comparison that can contradict the class under test. Asserting it separately from
        //       reclen is what catches a pair of mistranscribed widths that cancel: such a band
        //       still totals 80 and still passes a combined check, while every column between the
        //       two errors sits in the wrong place.
        assertThat(transcribedSum).as("native width sum of %s", expectation)
                .isEqualTo(STATEMENT_RECORD_LENGTH);

        // WHY : Assumptions: the descriptor's own field lengths are summed as a third reading, and
        //       the equality of all three is what establishes that this artifact needs NO padding.
        //       That is the exact inverse of the 133-column report, where six of the seven bands of
        //       app/cpy/CVTRA07Y.cpy fall short of their declared length and reach it only through
        //       codec padding. Recording the contrast at the assertion, and not only in the class
        //       documentation, is what stops the report's padding obligation being carried in here,
        //       where it would push bytes past the end of an 80-character record.
        int descriptorSum = StatementBandLayouts.band(expectation.bandName()).fields().stream()
                .mapToInt(CopybookLayout.FieldSpec::length).sum();
        assertThat(descriptorSum).as("descriptor width sum of %s", expectation)
                .isEqualTo(transcribedSum);
    }

    /**
     * Pins every field of every band to the name and width its own picture clause declares.
     *
     * @param expectation the band under test, carrying its field names and widths in declaration
     *     order as transcribed from the reference program
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyDeclaredBand")
    @DisplayName("every field transcribes the name and width of its own declaration")
    void everyFieldTranscribesItsOwnDeclaration(BandExpectation expectation) {
        List<CopybookLayout.FieldSpec> fields =
                StatementBandLayouts.band(expectation.bandName()).fields();

        // WHY : Assumptions: the field COUNT is asserted before any field is inspected, because a
        //       band that merged two adjacent items would otherwise pass every surviving index and
        //       fail only on the last one, reporting the symptom instead of the merge. The two
        //       consecutive blank items ending ST-LINE8 at app/cbl/CBSTM03A.CBL L114 and L115 are
        //       the case this guards: merged into one 47-character item the band still tiles, still
        //       sums to 80 and still writes byte-identical output, so the count is the only
        //       mechanical signal that the field list stopped transcribing its source.
        assertThat(fields).as("field count of %s", expectation)
                .hasSameSizeAs(expectation.fieldNames());

        for (int index = 0; index < expectation.fieldNames().size(); index++) {
            CopybookLayout.FieldSpec field = fields.get(index);

            // WHY : Assumptions: the name is asserted as well as the width because placement is by
            //       NAME, not by position -- the shared codec keys its value map on the field name
            //       and, for a locally declared layout its registry does not hold, reports an
            //       absent key as a missing required field. Each FILLER is therefore named by its
            //       one-based occurrence within the band, and a band whose occurrences were
            //       renumbered would silently place one literal into another literal's interval.
            //       Five bands carry several such items with DIFFERENT literal content -- ST-LINE6,
            //       ST-LINE11, ST-LINE13, ST-LINE14 and ST-LINE14A of app/cbl/CBSTM03A.CBL -- so a
            //       collision there is a wrong literal rather than a wrong blank.
            assertThat(field.name()).as("field %d name of %s", index, expectation)
                    .isEqualTo(expectation.fieldNames().get(index));
            assertThat(field.length()).as("field %d width of %s", index, expectation)
                    .isEqualTo(expectation.fieldWidths().get(index).intValue());
        }
    }

    /**
     * Pins every band to a contiguous tiling of its record from offset zero with no gap or overlap.
     *
     * @param expectation the band under test, used to resolve the descriptor and to cite the
     *     reference line in any failure
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyDeclaredBand")
    @DisplayName("every band tiles its 80 characters contiguously from offset zero")
    void everyBandTilesItsRecordContiguously(BandExpectation expectation) {
        CopybookLayout.RecordSpec band = StatementBandLayouts.band(expectation.bandName());
        int cursor = 0;

        for (CopybookLayout.FieldSpec field : band.fields()) {

            // WHY : Assumptions: a field's start is compared against the previous field's EXCLUSIVE
            //       end, because CopybookLayout.FieldSpec.start() is zero-based and end() is
            //       exclusive, so a field occupies the half-open interval [start, end). One
            //       comparison then rejects a gap and an overlap together. The one-based habit of
            //       the reference sources is the hazard: app/jcl/TRANREPT.jcl L41 and L42 name
            //       positions 263 and 305 for what are offsets 262 and 304 here, and a one-based
            //       figure copied into a start component shifts its field and every field after it
            //       by one character while still decoding to plausible text.
            assertThat(field.start()).as("start of %s in %s", field.name(), expectation)
                    .isEqualTo(cursor);
            cursor = field.end();
        }

        // WHY : Assumptions: the walk has to CLOSE on the declared length, not merely stay inside
        //       it. A band described short would leave the tail blank on encode, which reads as a
        //       formatting quirk rather than as a missing field, and a band described long would
        //       overrun the record. The descriptor's own validateGeometry() asserts the same pair of
        //       invariants at class initialisation; it is re-asserted from outside here so that the
        //       invariant has an owner that survives a change to that method's call site. The figure
        //       it must close on is the 80 of app/cbl/CBSTM03A.CBL L45.
        assertThat(cursor).as("closing offset of %s", expectation).isEqualTo(band.reclen());
    }

    /**
     * Pins the field list of every band to a form no caller can mutate.
     *
     * @param expectation the band under test, used to resolve the descriptor whose list is probed
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyDeclaredBand")
    @DisplayName("every band's field list refuses mutation")
    void everyBandPublishesAnImmutableFieldList(BandExpectation expectation) {
        List<CopybookLayout.FieldSpec> fields =
                StatementBandLayouts.band(expectation.bandName()).fields();
        CopybookLayout.FieldSpec probe = fields.get(0);

        // WHY : Assumptions: these seventeen descriptors are static constants shared by every caller
        //       in the process, so a single successful add or remove through a retained reference
        //       would change the geometry every later assembly uses. The canonical constructor of
        //       CopybookLayout.RecordSpec ends by taking List.copyOf of its field list, which is why
        //       the attempt raises rather than succeeding; the attempt is made here rather than
        //       trusted because immutability by construction is exactly the property that a later
        //       edit to that constructor could remove without any other test noticing.
        assertThatThrownBy(() -> fields.add(probe))
                .as("adding to the field list of %s", expectation)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fields.remove(0))
                .as("removing from the field list of %s", expectation)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Pins every field of every band to character data carrying no digit positions and no flags.
     *
     * @param expectation the band under test, used to resolve the descriptor whose fields are read
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyDeclaredBand")
    @DisplayName("every field is character data with no digit positions and no diagnostic flags")
    void everyFieldIsCharacterDataWithoutDigitPositions(BandExpectation expectation) {
        CopybookLayout.RecordSpec band = StatementBandLayouts.band(expectation.bandName());

        for (CopybookLayout.FieldSpec field : band.fields()) {

            // WHY : Assumptions: every field is TEXT, including the three that hold an edited
            //       amount, because an edit mask is not a field kind -- CopybookLayout.Kind carries
            //       exactly TEXT, UINT, ZONED, PACKED and BINARY and deliberately offers no sixth
            //       constant for a print mask. A band item declared with the nearest numeric kind
            //       would be re-encoded as digits from an already-edited string, which silently
            //       destroys the point, the sign and the suppression the mask just produced.
            assertThat(field.kind()).as("kind of %s in %s", field.name(), expectation)
                    .isEqualTo(CopybookLayout.Kind.TEXT);

            // WHY : Assumptions: both digit counts are zero and the sign flag is false on every
            //       field, which is the mechanical consequence of the kind above: a character field
            //       has no digit positions to declare, and the constructor of
            //       CopybookLayout.FieldSpec rejects a non-numeric field that declares any. The
            //       three edited amounts carry nine integer and two decimal positions in their
            //       PICTURE clauses at app/cbl/CBSTM03A.CBL L113, L137 and L142, and those counts
            //       belong to the formatter that produces the string, not to the interval it is
            //       placed into.
            assertThat(field.intDigits()).as("integer digits of %s", field.name()).isZero();
            assertThat(field.decDigits()).as("decimal digits of %s", field.name()).isZero();
            assertThat(field.signed()).as("sign flag of %s", field.name()).isFalse();

            // WHY : Assumptions: no field is marked for timestamp normalisation and none is marked
            //       sensitive, and the second is counter-intuitive enough to assert rather than
            //       assume: the 80-character statement carries NO primary account number. In
            //       app/cbl/CBSTM03A.CBL the card number appears only as a table key at L227, a
            //       read-key restore at L421 and a table populate at L827, and in the control-break
            //       comparand at L69; no ST-LINE field holds it. Masking here would rewrite bytes
            //       the parity comparison expects untouched, so it would break byte parity while
            //       looking prudent. Nor does any band hold a timestamp: the two PIC X(26) items of
            //       the input record at app/cpy/COSTM01.CPY L34 and L35 reach no band field.
            assertThat(field.normalizeTs()).as("timestamp flag of %s", field.name()).isFalse();
            assertThat(field.sensitive()).as("sensitivity flag of %s", field.name()).isFalse();
        }
    }

    /**
     * Names the seventeen declared bands in the order the reference program declares them.
     *
     * <p>Assumptions: the names are derived from the same transcription the parameterized tests run
     * over, so the two cannot fall out of step. Writing a second literal list here would create a
     * source of truth that could be amended on its own, which is the drift the derivation avoids.</p>
     *
     * @return the seventeen declared group names in declaration order
     */
    private static List<String> declaredBandNames() {
        return everyDeclaredBand()
                .map(arguments -> ((BandExpectation) arguments.get()[0]).bandName())
                .toList();
    }

    /**
     * Pins the descriptor count at seventeen and proves the band that breaks the numbering is one.
     */
    @Test
    @DisplayName("seventeen bands are declared, including the trailer that breaks the numbering")
    void seventeenBandsAreDeclaredIncludingTheTrailerThatBreaksTheNumbering() {

        // WHY : Assumptions: the count is pinned because the numeric sequence understates it.
        //       ST-LINE0 through ST-LINE15 is SIXTEEN names, and the seventeenth is ST-LINE14A at
        //       app/cbl/CBSTM03A.CBL L138, which breaks the sequence by taking a trailing letter
        //       instead of the next number. A holder that stopped at the sixteen numbered names
        //       would satisfy every geometry check it declared, because each band it did declare
        //       would still be correct, and would emit a statement with no total line at all.
        assertThat(StatementBandLayouts.bands()).hasSize(DECLARED_BAND_COUNT);
        assertThat(declaredBandNames()).hasSize(DECLARED_BAND_COUNT);

        // WHY : Assumptions: the trailer is resolved by its exact declared spelling, trailing letter
        //       included, because that spelling is the lookup key and a name-keyed miss is how its
        //       absence would present. It is the per-card trailer carrying the Total EXP label of
        //       L139 and the statement total of L142, and it is written at L436 -- so losing it
        //       loses the one line on the statement that reports a sum.
        CopybookLayout.RecordSpec trailer = StatementBandLayouts.band("ST-LINE14A");
        assertThat(trailer.name()).isEqualTo("ST-LINE14A");
        assertThat(trailer.field("ST-TOTAL-TRAMT").length()).isEqualTo(STATEMENT_AMOUNT_WIDTH);

        // WHY : Assumptions: the sixteen numbered names are asserted present ALONGSIDE the count, so
        //       that a holder which had dropped the trailer and gained some other band would fail on
        //       identity rather than passing on arithmetic. A count alone cannot distinguish
        //       seventeen correct bands from seventeen bands of which one is wrong. The expected
        //       spelling of each is read from the level-05 declarations spanning
        //       app/cbl/CBSTM03A.CBL L86 to L146.
        assertThat(declaredBandNames()).containsExactly("ST-LINE0", "ST-LINE1", "ST-LINE2",
                "ST-LINE3", "ST-LINE4", "ST-LINE5", "ST-LINE6", "ST-LINE7", "ST-LINE8", "ST-LINE9",
                "ST-LINE10", "ST-LINE11", "ST-LINE12", "ST-LINE13", "ST-LINE14", "ST-LINE14A",
                "ST-LINE15");
    }

    /**
     * Pins the zero-preserving balance amount to thirteen characters at its declared offset.
     */
    @Test
    @DisplayName("the current balance allocates thirteen characters after its twenty-character label")
    void theCurrentBalanceAllocatesThirteenCharacters() {
        CopybookLayout.FieldSpec balance =
                StatementBandLayouts.band("ST-LINE8").field("ST-CURR-BAL");

        // WHY : Assumptions: thirteen is what PIC 9(9).99- at app/cbl/CBSTM03A.CBL L113 declares --
        //       nine digit positions, one decimal point, two decimal positions and one TRAILING sign
        //       -- and the picture admits NO grouping comma, so no fourteenth position exists for
        //       one. A mask that inserted thousands separators would return more characters than the
        //       item holds and would displace the whole tail of the band.
        assertThat(balance.length()).isEqualTo(STATEMENT_AMOUNT_WIDTH);
        assertThat(balance.start()).isEqualTo(BASIC_DETAIL_ITEM_WIDTH);
        assertThat(balance.end()).isEqualTo(BASIC_DETAIL_ITEM_WIDTH + STATEMENT_AMOUNT_WIDTH);

        // WHY : Alternatives Considered: asserting here that this picture PRESERVES leading zeros
        //       while the two Z-masked amounts blank them. Rejected: the rendering is owned by
        //       CobolEditMask and is already exercised case by case in CobolEditMaskTest, and one
        //       contract with two owners drifts the first time either is amended. This file's
        //       contract stops at geometry, which is also why the width alone cannot distinguish
        //       9(9).99- from Z(9).99- -- the two are the same shape at the same width, and only the
        //       formatter's own test can tell them apart by exercising them. The three declarations
        //       being distinguished are at app/cbl/CBSTM03A.CBL L113, L137 and L142.
        assertThat(balance.kind()).isEqualTo(CopybookLayout.Kind.TEXT);
        assertThat(StatementBandLayouts.STATEMENT_AMOUNT_LENGTH).isEqualTo(STATEMENT_AMOUNT_WIDTH);
    }

    /**
     * Pins both suppressed amounts to thirteen characters at the single column that aligns them.
     */
    @Test
    @DisplayName("both suppressed amounts allocate thirteen characters at the same column")
    void bothSuppressedAmountsAllocateThirteenCharactersAtTheSameColumn() {
        CopybookLayout.FieldSpec detailAmount =
                StatementBandLayouts.band("ST-LINE14").field("ST-TRANAMT");
        CopybookLayout.FieldSpec totalAmount =
                StatementBandLayouts.band("ST-LINE14A").field("ST-TOTAL-TRAMT");

        // WHY : Assumptions: both are thirteen characters because both are declared PIC Z(9).99-, at
        //       app/cbl/CBSTM03A.CBL L137 and L142 -- nine Z-suppressed integer positions, a point,
        //       two positions that are 9 and are therefore ALWAYS printed, and a trailing sign. That
        //       the cents are 9 rather than Z is why a zero here is not a wholly blank field, and
        //       neither picture admits a grouping comma.
        assertThat(detailAmount.length()).isEqualTo(STATEMENT_AMOUNT_WIDTH);
        assertThat(totalAmount.length()).isEqualTo(STATEMENT_AMOUNT_WIDTH);

        // WHY : Assumptions: the shared offset is the alignment contract, not a coincidence. Both
        //       amounts start at the same column and both bands end at 80, so the total printed by
        //       the trailer at L436 sits directly under the detail amounts written at L679. The
        //       56-character blank run of L140 exists precisely to push the trailer's amount to that
        //       column, so changing it would silently un-column the total.
        assertThat(detailAmount.start()).isEqualTo(AMOUNT_COLUMN_OFFSET);
        assertThat(totalAmount.start()).isEqualTo(AMOUNT_COLUMN_OFFSET);
        assertThat(detailAmount.end()).isEqualTo(STATEMENT_RECORD_LENGTH);
        assertThat(totalAmount.end()).isEqualTo(STATEMENT_RECORD_LENGTH);
    }

    /**
     * Pins the balance band's two consecutive trailing blank items to two separate declarations.
     */
    @Test
    @DisplayName("the balance band ends in two separately declared blank items, not one of 47")
    void theBalanceBandEndsInTwoSeparatelyDeclaredBlankItems() {
        CopybookLayout.RecordSpec band = StatementBandLayouts.band("ST-LINE8");

        // WHY : Assumptions: the baseline declares TWO consecutive blank items here, PIC X(07) at
        //       app/cbl/CBSTM03A.CBL L114 and PIC X(40) at L115, and the Java descriptor declares
        //       two to match. One item of 47 characters would produce a byte-identical record, tile
        //       the band correctly and pass the descriptor's own geometry validation, so no
        //       mechanical check other than this one can see the merge; what would be lost is that
        //       the field list stopped transcribing its source. There is no divergence to register:
        //       the baseline declares two and the migration declares two.
        assertThat(band.fields()).hasSize(4);
        assertThat(band.field("FILLER-2").start()).isEqualTo(33);
        assertThat(band.field("FILLER-2").length()).isEqualTo(7);
        assertThat(band.field("FILLER-3").start()).isEqualTo(40);
        assertThat(band.field("FILLER-3").length()).isEqualTo(40);

        // WHY : Assumptions: the two are asserted ADJACENT as well as separate, because two blank
        //       items of the right widths at the wrong offsets would satisfy the widths above while
        //       leaving a gap between them. Seven at offset 33 must end exactly where forty at
        //       offset 40 begins, and the second must close the record at 80. The two widths are the
        //       PIC X(07) of app/cbl/CBSTM03A.CBL L114 and the PIC X(40) of L115.
        assertThat(band.field("FILLER-2").end()).isEqualTo(band.field("FILLER-3").start());
        assertThat(band.field("FILLER-3").end()).isEqualTo(STATEMENT_RECORD_LENGTH);
    }

    /**
     * Pins the three colon labels of the basic-details block character for character.
     */
    @Test
    @DisplayName("the three colon labels carry their exact internal blank runs and no trailing blank")
    void theThreeColonLabelsCarryTheirExactInternalBlankRuns() {

        // WHY : Assumptions: each label is composed from its text, an explicit blank COUNT and the
        //       colon, rather than written as one opaque literal, so a reviewer can check the count
        //       against app/cbl/CBSTM03A.CBL L108, L112 and L117 instead of counting characters
        //       inside a string. The counts differ -- nine, four and nine -- because the texts differ
        //       in length and the colons were aligned by hand in the reference, so no single count
        //       serves all three and bringing them into line would change bytes that are compared
        //       one for one.
        assertThat(StatementBandLayouts.ACCOUNT_ID_LABEL)
                .isEqualTo("Account ID" + " ".repeat(9) + ":");
        assertThat(StatementBandLayouts.CURRENT_BALANCE_LABEL)
                .isEqualTo("Current Balance" + " ".repeat(4) + ":");
        assertThat(StatementBandLayouts.FICO_SCORE_LABEL)
                .isEqualTo("FICO Score" + " ".repeat(9) + ":");

        // WHY : Assumptions: the colon is the LAST of twenty positions in all three, with nothing
        //       after it. The HTML side of the same program carries one blank after its colon, so the
        //       two forms differ by exactly one character and must never be harmonised: each is the
        //       declared content of a different record at a different declared length, and a byte
        //       comparison fails on one character as readily as on a hundred. The three text-side
        //       items are the PIC X(20) declarations of app/cbl/CBSTM03A.CBL L108, L112 and L117.
        assertThat(StatementBandLayouts.ACCOUNT_ID_LABEL).hasSize(BASIC_DETAIL_ITEM_WIDTH)
                .endsWith(":");
        assertThat(StatementBandLayouts.CURRENT_BALANCE_LABEL).hasSize(BASIC_DETAIL_ITEM_WIDTH)
                .endsWith(":");
        assertThat(StatementBandLayouts.FICO_SCORE_LABEL).hasSize(BASIC_DETAIL_ITEM_WIDTH)
                .endsWith(":");
    }

    /**
     * Pins every heading, sentinel and single-character literal to its exact declared text.
     */
    @Test
    @DisplayName("every heading and sentinel carries its declared blanks, leading and trailing")
    void everyHeadingAndSentinelCarriesItsDeclaredBlanks() {

        // WHY : Assumptions: this heading is thirteen characters and its item is fourteen, at
        //       app/cbl/CBSTM03A.CBL L105, so ONE blank sits inside the item and the visible text
        //       stands one column left of centre in a band that splits 33, 14, 33. The constant holds
        //       the thirteen declared characters and not the fourteenth position, so it is asserted
        //       WITHOUT a trailing blank; padding it to fourteen here would move the boundary between
        //       content and declared blank and hide which of the two the record actually holds.
        assertThat(StatementBandLayouts.BASIC_DETAILS_HEADING).isEqualTo("Basic Details")
                .hasSize(13);

        // WHY : Assumptions: this heading is twenty characters only BECAUSE it ends in a blank, at
        //       L124; the text alone is nineteen. The trailing blank is declared content rather than
        //       an artifact of the source line, so it is asserted explicitly -- a trimmed constant
        //       would still read as correct English and would shorten the item, shifting the thirty
        //       blanks that follow it.
        assertThat(StatementBandLayouts.TRANSACTION_SUMMARY_HEADING)
                .isEqualTo("TRANSACTION SUMMARY ").hasSize(BASIC_DETAIL_ITEM_WIDTH);

        // WHY : Assumptions: the two blanks at the FRONT of the amount heading at L131 are what
        //       right-align it over the thirteen-character amount mask directly beneath it, so they
        //       are leading content and not indentation to be tidied away. The two column headings
        //       beside it carry trailing blank runs of nine and four, from L129 and L130.
        assertThat(StatementBandLayouts.TRAN_AMOUNT_HEADING)
                .isEqualTo(" ".repeat(2) + "Tran Amount").hasSize(STATEMENT_AMOUNT_WIDTH);
        assertThat(StatementBandLayouts.TRAN_ID_HEADING).isEqualTo("Tran ID" + " ".repeat(9))
                .hasSize(16);
        assertThat(StatementBandLayouts.TRAN_DETAILS_HEADING)
                .isEqualTo("Tran Details" + " ".repeat(4)).hasSize(16);

        // WHY : Assumptions: both sentinels are declared with VALUE ALL, at L88 and L145, and in both
        //       cases the ALL degenerates to a plain VALUE because each literal already fills its own
        //       item exactly -- eighteen in PIC X(18) and sixteen in PIC X(16). Nothing repeats, which
        //       is why these two are literals while the asterisk and hyphen runs are built from a
        //       width. Their differing lengths are also what forces the two banners' asterisk runs
        //       apart.
        assertThat(StatementBandLayouts.START_OF_STATEMENT_SENTINEL)
                .isEqualTo("START OF STATEMENT").hasSize(18);
        assertThat(StatementBandLayouts.END_OF_STATEMENT_SENTINEL).isEqualTo("END OF STATEMENT")
                .hasSize(16);

        // WHY : Assumptions: the trailer label at L139 fills its PIC X(10) exactly, colon last and no
        //       blank after it, and the abbreviation is the reference's own. It is not expanded here
        //       because the item is ten characters wide and any expansion would either overrun it or
        //       need a narrower label. The single blank of L134 is likewise declared content: the
        //       codec requires a value for every field of an unregistered layout, so that one
        //       character has to be supplied explicitly rather than left to a default.
        assertThat(StatementBandLayouts.TOTAL_EXPENDITURE_LABEL).isEqualTo("Total EXP:")
                .hasSize(10);
        assertThat(StatementBandLayouts.SINGLE_BLANK).isEqualTo(" ").hasSize(1);
    }

    /**
     * Pins each literal to the item that declares it, distinguishing an exact fit from declared slack.
     */
    @Test
    @DisplayName("each literal fits its declared item, exactly or with the declared blanks after it")
    void eachLiteralFitsTheItemThatDeclaresIt() {

        // WHY : Assumptions: a literal's length and its item's width are two facts, and asserting
        //       only the first would let a correct literal sit in a mis-declared interval. Eight of
        //       these are exact fits, so their literal length and item width are asserted EQUAL; a
        //       later widening of any of those items would leave declared blanks nobody intended.
        //       The nine items asserted here are declared at app/cbl/CBSTM03A.CBL L88, L108, L112,
        //       L117, L124, L129, L131, L139 and L145.
        assertThat(itemWidth("ST-LINE0", "FILLER-2"))
                .isEqualTo(StatementBandLayouts.START_OF_STATEMENT_SENTINEL.length());
        assertThat(itemWidth("ST-LINE7", "FILLER-1"))
                .isEqualTo(StatementBandLayouts.ACCOUNT_ID_LABEL.length());
        assertThat(itemWidth("ST-LINE8", "FILLER-1"))
                .isEqualTo(StatementBandLayouts.CURRENT_BALANCE_LABEL.length());
        assertThat(itemWidth("ST-LINE9", "FILLER-1"))
                .isEqualTo(StatementBandLayouts.FICO_SCORE_LABEL.length());
        assertThat(itemWidth("ST-LINE11", "FILLER-2"))
                .isEqualTo(StatementBandLayouts.TRANSACTION_SUMMARY_HEADING.length());
        assertThat(itemWidth("ST-LINE13", "FILLER-1"))
                .isEqualTo(StatementBandLayouts.TRAN_ID_HEADING.length());
        assertThat(itemWidth("ST-LINE13", "FILLER-3"))
                .isEqualTo(StatementBandLayouts.TRAN_AMOUNT_HEADING.length());
        assertThat(itemWidth("ST-LINE14A", "FILLER-1"))
                .isEqualTo(StatementBandLayouts.TOTAL_EXPENDITURE_LABEL.length());
        assertThat(itemWidth("ST-LINE15", "FILLER-2"))
                .isEqualTo(StatementBandLayouts.END_OF_STATEMENT_SENTINEL.length());

        // WHY : Assumptions: two items are deliberately WIDER than the literal they carry, and the
        //       surplus is asserted as an exact figure rather than merely as slack. One blank follows
        //       the basic-details heading inside the PIC X(14) of L105, and thirty-five follow the
        //       transaction-details heading inside the PIC X(51) of L130. Those blanks are bytes the
        //       record genuinely contains, and the thirty-five are what let the heading row align
        //       with the transaction band below it, whose description item is forty-nine characters
        //       preceded by a sixteen-character identifier and one blank.
        assertThat(itemWidth("ST-LINE6", "FILLER-2")
                - StatementBandLayouts.BASIC_DETAILS_HEADING.length()).isEqualTo(1);
        assertThat(itemWidth("ST-LINE13", "FILLER-2")
                - StatementBandLayouts.TRAN_DETAILS_HEADING.length()).isEqualTo(35);

        // WHY : Assumptions: no literal may EXCEED its item, which is the one failure the two blocks
        //       above cannot express together. A literal longer than its interval is silently
        //       truncated by a placement mechanism that writes into a bounded window, so it would
        //       lose characters without raising anything. The two intervals with slack are the
        //       PIC X(14) of app/cbl/CBSTM03A.CBL L105 and the PIC X(51) of L130.
        // WHY : Assumptions: these literals have to be emitted by the consumer rather than left to
        //       survive in storage, which is why each is a declared constant occupying its own
        //       interval. INITIALIZE STATEMENT-LINES. at app/cbl/CBSTM03A.CBL L459 SKIPS FILLER, so
        //       in the reference every label and every VALUE ALL run is established once when
        //       storage is laid out and survives every later statement. An assembly that rebuilds a
        //       band field by field has no such carry-over, so a FILLER carrying a VALUE is content
        //       whose bytes are part of the artifact and never padding that any blank would satisfy.
        assertThat(StatementBandLayouts.BASIC_DETAILS_HEADING.length())
                .isLessThanOrEqualTo(itemWidth("ST-LINE6", "FILLER-2"));
        assertThat(StatementBandLayouts.TRAN_DETAILS_HEADING.length())
                .isLessThanOrEqualTo(itemWidth("ST-LINE13", "FILLER-2"));
    }

    /**
     * Returns the declared width of one field of one band.
     *
     * <p>Assumptions: this resolves through the public accessors on every call rather than caching
     * a field map, so each assertion that uses it exercises the same lookup path a consumer would.
     * A cached map would make the literal-fit assertions independent of whether a band is reachable
     * by its declared name, which is a property those assertions should depend on.</p>
     *
     * @param bandName the declared group name of the band holding the field
     * @param fieldName the descriptor's name for the field, with each blank or literal item named by
     *     its one-based occurrence within the band
     * @return the declared character width of that field
     */
    private static int itemWidth(String bandName, String fieldName) {
        return StatementBandLayouts.band(bandName).field(fieldName).length();
    }

    /**
     * Pins the three byte-identical rule bands to three separately addressable declarations.
     */
    @Test
    @DisplayName("the three hyphen rule bands stay three declarations, not one shared constant")
    void theThreeHyphenRuleBandsStayThreeDeclarations() {
        List<String> ruleBandNames = List.of("ST-LINE5", "ST-LINE10", "ST-LINE12");

        // WHY : Assumptions: all three are declared at app/cbl/CBSTM03A.CBL L101, L120 and L126, each
        //       with a single subordinate FILLER VALUE ALL '-' PIC X(80) at L102, L121 and L127, so
        //       each is one field occupying the whole record with nothing beside it.
        for (String ruleBandName : ruleBandNames) {
            CopybookLayout.RecordSpec rule = StatementBandLayouts.band(ruleBandName);
            assertThat(rule.fields()).as("field count of %s", ruleBandName).hasSize(1);
            assertThat(rule.field("FILLER-1").length()).as("rule width of %s", ruleBandName)
                    .isEqualTo(STATEMENT_RECORD_LENGTH);
            assertThat(rule.field("FILLER-1").start()).isZero();
        }

        // WHY : Assumptions: they remain THREE because the emission sequence depends on their
        //       separate identities, not because three copies of one geometry are useful. The program
        //       writes ST-LINE5 twice, at L492 and L494, ST-LINE10 once, at L498, and ST-LINE12 twice
        //       at L500 and L502 plus once more in the per-card trailer at L435 -- six rule lines per
        //       statement from three named sources. Collapsed into one descriptor that count becomes
        //       unexpressible, so a statement built from it would carry the wrong number of lines
        //       while every individual line still compared equal, and the one-to-one correspondence
        //       with the baseline paragraph that the traceability register relies on would be lost.
        assertThat(ruleBandNames).allSatisfy(ruleBandName ->
                assertThat(StatementBandLayouts.band(ruleBandName).name()).isEqualTo(ruleBandName));
        assertThat(StatementBandLayouts.band("ST-LINE5"))
                .isNotEqualTo(StatementBandLayouts.band("ST-LINE10"))
                .isNotEqualTo(StatementBandLayouts.band("ST-LINE12"));
        assertThat(StatementBandLayouts.band("ST-LINE10"))
                .isNotEqualTo(StatementBandLayouts.band("ST-LINE12"));

        // WHY : Assumptions: the shared run VALUE is asserted once, separately from the three band
        //       identities, because the content genuinely is one thing while the declarations are
        //       three. Eighty hyphens is what VALUE ALL '-' over a PIC X(80) produces, and building
        //       the expectation by repetition rather than writing eighty characters out keeps it
        //       checkable -- a hand-written run one character short is exactly the error the
        //       descriptor's geometry check cannot catch, because the interval would still tile while
        //       the value placed into it fell short. The three declarations it fills are at
        //       app/cbl/CBSTM03A.CBL L102, L121 and L127.
        assertThat(StatementBandLayouts.HYPHEN_RULE).isEqualTo("-".repeat(STATEMENT_RECORD_LENGTH))
                .hasSize(STATEMENT_RECORD_LENGTH);
        assertThat(StatementBandLayouts.HYPHEN_RULE_LENGTH).isEqualTo(STATEMENT_RECORD_LENGTH);
    }

    /**
     * Pins the two banners to asterisk runs of thirty-one and thirty-two, which are not interchangeable.
     */
    @Test
    @DisplayName("the banner asterisk runs are 31 and 32 because the two sentinels differ in length")
    void theBannerAsteriskRunsAreAsymmetric() {
        CopybookLayout.RecordSpec opening = StatementBandLayouts.band("ST-LINE0");
        CopybookLayout.RecordSpec closing = StatementBandLayouts.band("ST-LINE15");

        // WHY : Assumptions: the opening banner at app/cbl/CBSTM03A.CBL L86 splits 31, 18, 31 across
        //       L87 to L89 and the closing banner at L143 splits 32, 16, 32 across L144 to L146. The
        //       runs differ because the sentinels do -- eighteen characters against sixteen -- so the
        //       asterisk runs absorb the two-character difference and both banners still total 80. A
        //       single shared run width would leave one of the two banners 78 or 82 characters long,
        //       which is an error a reader skims straight past because a banner of asterisks looks
        //       right at any length.
        assertThat(opening.field("FILLER-1").length()).isEqualTo(OPENING_RUN_WIDTH);
        assertThat(opening.field("FILLER-3").length()).isEqualTo(OPENING_RUN_WIDTH);
        assertThat(closing.field("FILLER-1").length()).isEqualTo(CLOSING_RUN_WIDTH);
        assertThat(closing.field("FILLER-3").length()).isEqualTo(CLOSING_RUN_WIDTH);

        // WHY : Assumptions: the asymmetry is asserted as an INEQUALITY as well as by two widths, so
        //       that a later edit unifying the two constants fails here rather than passing on the
        //       arithmetic of one band. The compensation is what the equality of the two totals then
        //       demonstrates: twice the run plus its own sentinel is 80 in each case, for the banner
        //       at app/cbl/CBSTM03A.CBL L86 and for the banner at L143 alike.
        // WHY : Refactoring Rationale: the inequality was written over this class's own two
        //       expectations, 31 against 32, which the compiler folds into a constant -- so the one
        //       edit it exists to catch, StatementBandLayouts declaring both banners at one width,
        //       could not fail it. It is read out of the two descriptors instead. Alternatives
        //       Considered: comparing OPENING_ASTERISK_RUN_LENGTH to CLOSING_ASTERISK_RUN_LENGTH,
        //       which is the pair such an edit would unify; rejected because both are compile-time
        //       constants and would fold the same way. The descriptor fields are read at run time, so
        //       this line survives inlining and fails on the unification itself.
        assertThat(opening.field("FILLER-1").length())
                .isNotEqualTo(closing.field("FILLER-1").length());
        assertThat(StatementBandLayouts.OPENING_ASTERISK_RUN_LENGTH).isEqualTo(OPENING_RUN_WIDTH);
        assertThat(StatementBandLayouts.CLOSING_ASTERISK_RUN_LENGTH).isEqualTo(CLOSING_RUN_WIDTH);
        assertThat(2 * OPENING_RUN_WIDTH + StatementBandLayouts.START_OF_STATEMENT_SENTINEL.length())
                .isEqualTo(STATEMENT_RECORD_LENGTH);
        assertThat(2 * CLOSING_RUN_WIDTH + StatementBandLayouts.END_OF_STATEMENT_SENTINEL.length())
                .isEqualTo(STATEMENT_RECORD_LENGTH);

        // WHY : Assumptions: each run VALUE is built by repeating its own width, so a run and the
        //       interval it fills cannot disagree. Two constants exist rather than one for the same
        //       reason the two widths do, and asserting both against their own width is what stops
        //       the wider run being reused for the narrower banner. The four items they fill are the
        //       PIC X(31) pair at app/cbl/CBSTM03A.CBL L87 and L89 and the PIC X(32) pair at L144
        //       and L146.
        assertThat(StatementBandLayouts.OPENING_ASTERISK_RUN)
                .isEqualTo("*".repeat(OPENING_RUN_WIDTH)).hasSize(OPENING_RUN_WIDTH);
        assertThat(StatementBandLayouts.CLOSING_ASTERISK_RUN)
                .isEqualTo("*".repeat(CLOSING_RUN_WIDTH)).hasSize(CLOSING_RUN_WIDTH);
    }

    /**
     * Pins the currency character to its own single-character field immediately before each amount.
     */
    @Test
    @DisplayName("the currency character is its own single-character field, not part of the mask")
    void theCurrencyCharacterIsItsOwnSingleCharacterField() {
        CopybookLayout.FieldSpec detailCurrency =
                StatementBandLayouts.band("ST-LINE14").field("FILLER-2");
        CopybookLayout.FieldSpec trailerCurrency =
                StatementBandLayouts.band("ST-LINE14A").field("FILLER-3");

        // WHY : Assumptions: the currency character is declared as its own FILLER VALUE '$' PIC X(01)
        //       at app/cbl/CBSTM03A.CBL L136 and again at L141, so it is data occupying one byte of
        //       the record rather than decoration a formatter adds. Neither PIC 9(9).99- nor
        //       PIC Z(9).99- declares a currency position, so a mask that emitted one would return
        //       fourteen characters for a thirteen-character item and displace the whole tail of the
        //       band; keeping it a separate field is what keeps it addressable as the byte it is.
        assertThat(detailCurrency.length()).isEqualTo(1);
        assertThat(trailerCurrency.length()).isEqualTo(1);
        assertThat(StatementBandLayouts.CURRENCY_SYMBOL).isEqualTo("$").hasSize(1);

        // WHY : Assumptions: it stands IMMEDIATELY before the amount in both bands, which is asserted
        //       by adjacency rather than by a literal offset so that the relationship survives a
        //       change to either band's leading widths. Its exclusive end must be the amount's start,
        //       and the amount must still be thirteen characters -- together those two facts state
        //       that the symbol is outside the mask rather than inside it. The two currency items are
        //       at app/cbl/CBSTM03A.CBL L136 and L141, and the amounts they precede at L137 and
        //       L142.
        assertThat(detailCurrency.end())
                .isEqualTo(StatementBandLayouts.band("ST-LINE14").field("ST-TRANAMT").start());
        assertThat(trailerCurrency.end())
                .isEqualTo(StatementBandLayouts.band("ST-LINE14A").field("ST-TOTAL-TRAMT").start());
        assertThat(detailCurrency.end() + STATEMENT_AMOUNT_WIDTH).isEqualTo(STATEMENT_RECORD_LENGTH);
        assertThat(trailerCurrency.end() + STATEMENT_AMOUNT_WIDTH)
                .isEqualTo(STATEMENT_RECORD_LENGTH);
    }

    /**
     * Pins the published band list to declaration order, seventeen entries and refusal of mutation.
     */
    @Test
    @DisplayName("the published band list is in declaration order and refuses mutation")
    void thePublishedBandListIsInDeclarationOrderAndRefusesMutation() {
        List<CopybookLayout.RecordSpec> bands = StatementBandLayouts.bands();

        // WHY : Assumptions: the order is DECLARATION order, matching L86 to L146, and not emission
        //       order. The two differ: the write sequence repeats two rule bands and is split across
        //       three paragraphs, so an emission-ordered list would hold twenty entries and would
        //       belong to the consumer that walks it rather than to a holder that declares geometry.
        //       Pinning declaration order is what lets the list be read straight down against the
        //       reference block.
        assertThat(bands).extracting(CopybookLayout.RecordSpec::name)
                .containsExactlyElementsOf(declaredBandNames());

        // WHY : Assumptions: the list is a shared static constant, so a successful mutation through
        //       this reference would change what every later caller in the process reads. The
        //       attempt is made rather than trusted because it is built with List.of, and an
        //       unmodifiable factory is exactly the kind of detail a later edit can replace with a
        //       mutable collection without any other assertion in this file noticing. What a removal
        //       would drop is one of the seventeen bands of app/cbl/CBSTM03A.CBL L86 to L146, and a
        //       statement assembled from sixteen still writes sixteen valid lines.
        CopybookLayout.RecordSpec probe = bands.get(0);
        assertThatThrownBy(() -> bands.add(probe)).isInstanceOf(UnsupportedOperationException.class);

        // WHY : Assumptions: the accessor returns the same instance on every call rather than a fresh
        //       copy, and that is asserted because it is the property that makes the immutability
        //       above worth having. A per-call copy would be a second thing to keep honest and would
        //       let a caller mutate a copy while believing it had changed the table. The table it
        //       returns holds the seventeen bands of app/cbl/CBSTM03A.CBL L86 to L146.
        assertThat(StatementBandLayouts.bands()).isSameAs(bands);
    }

    /**
     * Pins band lookup to resolving every declared name and rejecting anything else.
     */
    @Test
    @DisplayName("band lookup resolves every declared name and rejects an undeclared one")
    void bandLookupResolvesEveryDeclaredNameAndRejectsAnythingElse() {

        // WHY : Assumptions: every name is resolved through the accessor, including the trailing
        //       letter of ST-LINE14A and the absent leading zero of ST-LINE0 through ST-LINE15,
        //       because that spelling is the lookup key. A descriptor present in the list but
        //       unreachable by its declared name is unusable to a consumer that addresses bands by
        //       name, and the list assertion above cannot detect that. The seventeen spellings come
        //       from the level-05 declarations at app/cbl/CBSTM03A.CBL L86 to L146, the last of them
        //       being ST-LINE14A at L138.
        for (String bandName : declaredBandNames()) {
            assertThat(StatementBandLayouts.band(bandName).name()).isEqualTo(bandName);
        }

        // WHY : Assumptions: an unknown name is REJECTED rather than answered with a null, so a
        //       misspelling surfaces at the lookup that made it instead of as a failure inside the
        //       placement mechanism one call later. The message is asserted to name the requested
        //       band because that is what tells a caller which of two spellings is wrong -- the one
        //       it asked for or the one the reference declares. The probe is deliberately the next
        //       name in the numeric sequence, which app/cbl/CBSTM03A.CBL does not declare: its
        //       seventeenth band is ST-LINE14A at L138 and its highest number is ST-LINE15 at L143.
        assertThatThrownBy(() -> StatementBandLayouts.band("ST-LINE16"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ST-LINE16");

        // WHY : Assumptions: a name differing only in case is rejected too, which is worth pinning
        //       because app/cbl/CBSTM03A.CBL declares every one of these groups in upper case at
        //       L86 to L146, and a case-insensitive lookup would quietly accept a spelling no
        //       reference source contains. A null name travels the same path, since no declared
        //       group name equals it.
        assertThatThrownBy(() -> StatementBandLayouts.band("st-line0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatementBandLayouts.band(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Pins the statement width apart from the HTML width so the two can never be interchanged.
     */
    @Test
    @DisplayName("80 is the text width and 100 is the HTML width, and no band declares 100")
    void theStatementWidthIsNeverTheHtmlWidth() {

        // WHY : Assumptions: the same program writes a second artifact at a DIFFERENT declared
        //       length, and the two are never interchangeable. The HTML record is declared
        //       01 FD-HTMLFILE-REC PIC X(100). at app/cbl/CBSTM03A.CBL L47, its three assembly items
        //       HTML-ADDR-LN, HTML-BSIC-LN and HTML-TRAN-LN are each PIC X(100) at L221 to L223, and
        //       DCB=(LRECL=100,BLKSIZE=800,RECFM=FB) at app/jcl/CREASTMT.JCL L94 confirms it from
        //       outside the program. This is an observation about an immutable reference, not a
        //       finding against it: the baseline writes two artifacts at two lengths and the
        //       migration writes two artifacts at the same two lengths.
        // WHY : Refactoring Rationale: a bare inequality between this class's own 100 and its own 80
        //       stood here and is removed rather than rewritten. The compiler folded it, so no change
        //       to StatementBandLayouts could falsify it, and the two assertions that bracket it say
        //       the same thing against the subject at run time: no band in the published table
        //       declares the markup length, and every band declares the statement length. Its
        //       reference citation is the block above, which is kept.
        assertThat(StatementBandLayouts.bands())
                .noneMatch(band -> band.reclen() == HTML_RECORD_LENGTH);

        // WHY : Assumptions: the 100-character contract belongs to the test that owns the HTML
        //       emitter, and that emitter does not consume these descriptors at all -- it builds its
        //       lines from fragment constants rather than from an 80-character band table. Asserting
        //       an edge to it from here would make this file fail whenever that emitter changed for
        //       reasons this artifact has no stake in, so the boundary is asserted only as the
        //       absence of the HTML length among these bands. Its own declarations are
        //       01 FD-HTMLFILE-REC PIC X(100). at app/cbl/CBSTM03A.CBL L47 and the three PIC X(100)
        //       assembly items at L221 to L223.
        assertThat(StatementBandLayouts.bands())
                .allMatch(band -> band.reclen() == STATEMENT_RECORD_LENGTH);
    }
}
