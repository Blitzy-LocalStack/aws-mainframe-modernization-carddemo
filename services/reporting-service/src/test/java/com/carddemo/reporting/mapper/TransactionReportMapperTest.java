package com.carddemo.reporting.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.money.Money;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
 * Exercises every member of {@link TransactionReportMapper} against the bytes it is required to
 * emit.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link ReportBandLayouts} and its own test pin the <em>geometry</em> of the seven report
 * bands. Nothing pinned the <em>emitter</em> that fills them: before this class existed the mapper
 * had no executable consumer at all, so the four heading lines, the eight varying items of a detail
 * line, the two joining hyphens that a freshly allocated record has no survivor for, the two
 * distinct money masks and the single column all four money-bearing bands share rested on review
 * alone. This class closes that gap, band by band and rejection by rejection.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a
 * caller invokes, no value it yields and no exception it raises to anything outside the test
 * engine, so the type itself accepts no parameter, returns nothing and throws nothing. The
 * inapplicability is stated rather than passed over, because user-specified Rule 1 (Explainability)
 * forbids a docstring that omits parameters, return values or purpose, and a reader has to be able
 * to tell a declared inapplicability from an oversight. Every member below carries its own
 * parameter, return and exception at-clauses.</p>
 *
 * <h2>Assumptions: expected bytes are transcribed from the copybook, not recomposed</h2>
 *
 * <p>Every band assertion below compares against a literal of exactly
 * {@value #REPORT_RECORD_LENGTH} characters, transcribed from lines 4 to 66 of
 * {@code app/cpy/CVTRA07Y.cpy} and checked against a captured emission. Alternatives Considered:
 * assembling each expectation from the same constants the mapper assembles it from. Rejected
 * because that passes whenever the mapper and the test share a mistake -- a label short by one
 * blank, or a dot leader of the wrong length -- which is the entire failure mode a transcription
 * test exists to catch.</p>
 *
 * <h2>Assumptions: the end-of-file divergences are asserted only as far as this class reaches</h2>
 *
 * <p>Two registered divergences, {@code D-REPORT-GRAND-TOTAL} and
 * {@code D-REPORT-CLOSING-TOTAL}, concern what the <em>emitting sequence</em> does at end of
 * file, and this class emits no sequence. What is checkable here is the half that lives in this
 * mapper: that it holds no accumulator, so a total renders exactly the value it was handed and two
 * calls cannot compound; and that a card-break total is reachable unconditionally, with no argument
 * by which a caller could suppress it for a final group. Both halves are asserted below, and the
 * register entries state what remains uncovered rather than implying that these tests close
 * them.</p>
 *
 * <h2>Trade-offs: each of those two divergences is asserted from both sides</h2>
 *
 * <p>A structural assertion alone is not enough for either. Asserting only that a total encoder
 * holds no accumulator would pass against an encoder that rendered the wrong value, and asserting
 * only that the account-total encoder takes one argument would pass against one whose band landed
 * in the wrong column. So each divergence additionally carries a value-level case built on one
 * concrete two-page run: the grand total is asserted to be the sum of that run's page totals and
 * <strong>not</strong> the value the reference produces by counting the closing amount twice, and
 * the account total is asserted to encode identically for a final group and a middle one. Those
 * are the assertions that would fail if either divergence were quietly reversed.</p>
 *
 * @see TransactionReportMapper
 * @see ReportBandLayouts
 */
@DisplayName("TransactionReportMapper: heading, detail and total band emission")
class TransactionReportMapperTest {

    /**
     * Declared record length of the daily transaction report, transcribed independently here.
     *
     * <p>Assumptions: this is re-declared rather than read from {@link ReportBandLayouts} so that a
     * failure can distinguish a wrong band from a wrong record length. It is established by
     * {@code 01 TRANSACTION-HEADER-2 PIC X(133)} at line 48 of {@code app/cpy/CVTRA07Y.cpy}.</p>
     */
    private static final int REPORT_RECORD_LENGTH = 133;

    /**
     * Zero-based offset at which all four money-bearing bands open their edited amount.
     *
     * <p>Assumptions: the detail band reaches it from 97 characters of preceding items, the page
     * and grand total bands from an 11-character label plus an 86-character dot leader, and the
     * account total band from a 13-character label plus an 84-character leader. Three different
     * sums, one offset, which is why the leaders are declared separately rather than shared.</p>
     */
    private static final int AMOUNT_OFFSET = 97;

    /**
     * Declared width of every edited amount this report emits, in characters.
     *
     * <p>Assumptions: the mask at line 30 and the mask at lines 54,
     * 60 and 66 of {@code app/cpy/CVTRA07Y.cpy} are each one sign position, nine digit positions,
     * two group separators, a decimal point and two decimal positions.</p>
     */
    private static final int AMOUNT_WIDTH = 15;

    /**
     * First business date of the range used by every heading assertion below.
     */
    private static final LocalDate RANGE_START = LocalDate.of(2022, 7, 1);

    /**
     * Last business date of the range used by every heading assertion below.
     */
    private static final LocalDate RANGE_END = LocalDate.of(2022, 7, 31);

    /**
     * First page total of the two-page run the two divergence cases below are built on.
     *
     * <p>Assumptions: these four money vectors are declared together because they are one arithmetic
     * example, not four independent constants: the target's grand total is the sum of the two page
     * totals, and the baseline's is that sum with the closing amount counted once more. Splitting
     * them would let one be edited without the other and leave the example incoherent.</p>
     */
    private static final String FIRST_PAGE_TOTAL = "700.00";

    /**
     * Second and closing page total of that run, which is also its last transaction's amount.
     *
     * <p>Assumptions: those two being the same value is what makes the baseline's repetition
     * expressible at all -- the end-of-file branch adds the last transaction's amount, so on this
     * run it adds exactly this page total again.</p>
     */
    private static final String CLOSING_PAGE_TOTAL = "534.56";

    /** The sum of the two page totals: the grand total this mapper is required to render. */
    private static final String TARGET_GRAND_TOTAL = "1234.56";

    /** That sum with the closing amount counted a second time: the grand total the reference renders. */
    private static final String BASELINE_GRAND_TOTAL = "1769.12";

    /** The rendered form of {@link #TARGET_GRAND_TOTAL} through the always-signed total mask. */
    private static final String RENDERED_TARGET_TOTAL = "+      1,234.56";

    /** The rendered form of {@link #BASELINE_GRAND_TOTAL} through the same mask. */
    private static final String RENDERED_BASELINE_TOTAL = "+      1,769.12";

    /**
     * Decodes an emitted band to a string for comparison against a transcribed literal.
     *
     * @param band the emitted band; must not be {@code null}
     * @return the band decoded as ASCII text, of the same length as the band
     */
    private static String text(byte[] band) {
        return new String(band, StandardCharsets.UTF_8);
    }

    /**
     * Builds a value of a stated length by padding it on the right with blanks.
     *
     * <p>Assumptions: this pads an expectation and never a value the mapper produced, so it cannot
     * mask a padding fault in the mapper.</p>
     *
     * @param value the leading content of the expectation
     * @param width the total width the expectation must reach
     * @return the value followed by enough blanks to reach the width
     */
    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Emits a representative detail line used by several assertions below.
     *
     * <p>Assumptions: the arguments are chosen so every rendering is distinguishable -- a short
     * account identifier so its zero padding shows, a category code of one digit so its zero fill
     * shows, and two descriptions longer than their columns so both narrowings show.</p>
     *
     * @return the emitted detail line, never {@code null}
     */
    private static byte[] representativeDetail() {
        return TransactionReportMapper.encodeDetailLine("0000000000000001", 11L, "01",
                "Regular Sales Draft", 5, "Restaurant and Bar Purchases", "POS TERM  ",
                Money.of("1234.56"));
    }

    /**
     * Returns the fifteen characters a band devotes to its edited amount.
     *
     * @param band the emitted band; must not be {@code null}
     * @return the amount field of the band, exactly {@value #AMOUNT_WIDTH} characters
     */
    private static String amountField(byte[] band) {
        return text(band).substring(AMOUNT_OFFSET, AMOUNT_OFFSET + AMOUNT_WIDTH);
    }

    /**
     * Asserts that encodeNameHeader carries both dates under their labels and pads from 115 to 133.
     */
    @Test
    @DisplayName("encodeNameHeader carries both dates under their labels and pads from 115 to 133")
    void encodeNameHeaderCarriesBothDates() {
        byte[] header = TransactionReportMapper.encodeNameHeader(RANGE_START, RANGE_END);

        assertThat(header).hasSize(REPORT_RECORD_LENGTH);
        assertThat(text(header)).isEqualTo(padded("DALYREPT", 38)
                + padded("Daily Transaction Report", 41)
                + "Date Range: " + "2022-07-01" + " to " + "2022-07-31"
                + " ".repeat(18));

        // WHY : Assumptions: the padding run is asserted as its own fact because this band is the
        //       only one of the seven whose native width, 115, is neither 133 nor the 114 the other
        //       short bands share. A band that reached 133 from the wrong native width would still
        //       be 133 bytes long and would place its two dates elsewhere.
        assertThat(text(header).substring(115)).isEqualTo(" ".repeat(18));
    }

    /**
     * Supplies each date position of the title band paired with a {@code null} argument.
     *
     * @return a stream of argument name and an invocation that passes {@code null} in that position
     */
    private static Stream<Arguments> nullTitleDates() {
        return Stream.of(
                Arguments.of("rangeStart", (Executable) () -> TransactionReportMapper
                        .encodeNameHeader(null, RANGE_END)),
                Arguments.of("rangeEnd", (Executable) () -> TransactionReportMapper
                        .encodeNameHeader(RANGE_START, null)));
    }

    /**
     * Asserts that encodeNameHeader rejects a null date by naming the argument.
     *
     * @param argumentName the name of the argument the rejection must cite
     * @param invocation the call that passes {@code null} in that position
     */
    @ParameterizedTest(name = "encodeNameHeader rejects a null {0}")
    @MethodSource("nullTitleDates")
    @DisplayName("encodeNameHeader rejects a null date by naming the argument")
    void encodeNameHeaderRejectsANullDate(String argumentName, Executable invocation) {
        assertThatThrownBy(invocation::execute)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(argumentName);
    }

    /**
     * Asserts that encodeColumnHeader carries all seven literals including the single byte that
     * closes them.
     */
    @Test
    @DisplayName("encodeColumnHeader carries all seven literals including the single byte that "
            + "closes them")
    void encodeColumnHeaderCarriesAllSevenLiterals() {
        byte[] header = TransactionReportMapper.encodeColumnHeader();

        assertThat(header).hasSize(REPORT_RECORD_LENGTH);
        assertThat(text(header)).isEqualTo(padded("Transaction ID", 17)
                + padded("Account ID", 12)
                + padded("Transaction Type", 19)
                + padded("Tran Category", 35)
                + padded("Tran Source", 14)
                + " "
                + padded("        Amount", 16)
                + " ".repeat(19));

        // WHY : Assumptions: the eight leading blanks of the amount heading are DATA and not
        //       formatting, under AAP Rule T8. They are what right-aligns the word Amount over the
        //       mask beneath it, so the heading's last content character and the detail amount's
        //       last character land in the same column. Asserting that column equality is what
        //       proves the eight blanks are neither six nor ten.
        assertThat(text(header).indexOf("Amount")).isEqualTo(106);
        assertThat(text(header).charAt(97)).isEqualTo(' ');
    }

    /**
     * Asserts that encodeSeparatorRule is one hundred and thirty-three hyphens with no padding.
     */
    @Test
    @DisplayName("encodeSeparatorRule is one hundred and thirty-three hyphens with no padding")
    void encodeSeparatorRuleIsAllHyphens() {
        byte[] rule = TransactionReportMapper.encodeSeparatorRule();

        // WHY : Assumptions: this band is the only one of the seven that is natively the declared
        //       record length, so it is the only one for which the codec's blank fill must never
        //       run. A rule supplied short would be blank-filled and would read as a rule that
        //       stops part way, which is why the literal is supplied at full width.
        assertThat(text(rule)).isEqualTo("-".repeat(REPORT_RECORD_LENGTH));
    }

    /**
     * Asserts that encodeBlankLine is one hundred and thirty-three blanks.
     */
    @Test
    @DisplayName("encodeBlankLine is one hundred and thirty-three blanks")
    void encodeBlankLineIsAllBlanks() {
        assertThat(text(TransactionReportMapper.encodeBlankLine()))
                .isEqualTo(" ".repeat(REPORT_RECORD_LENGTH));
    }

    /**
     * Asserts that encodeHeadingBlock emits the four bands in the reference's own order.
     */
    @Test
    @DisplayName("encodeHeadingBlock emits the four bands in the reference's own order")
    void encodeHeadingBlockEmitsFourBandsInOrder() {
        List<byte[]> block = TransactionReportMapper.encodeHeadingBlock(RANGE_START, RANGE_END);

        assertThat(block).hasSize(4);
        assertThat(block).allSatisfy(line -> assertThat(line).hasSize(REPORT_RECORD_LENGTH));

        // WHY : Assumptions: the order is asserted positionally against the four individual
        //       encoders, because the order IS the content of this method. Lines 325, 329, 333 and
        //       337 of app/cbl/CBTRN03C.cbl emit title, blank, headings, rule with nothing between
        //       them, and a permuted block would still be four correct bands.
        assertThat(text(block.get(0)))
                .isEqualTo(text(TransactionReportMapper.encodeNameHeader(RANGE_START, RANGE_END)));
        assertThat(text(block.get(1)))
                .isEqualTo(text(TransactionReportMapper.encodeBlankLine()));
        assertThat(text(block.get(2)))
                .isEqualTo(text(TransactionReportMapper.encodeColumnHeader()));
        assertThat(text(block.get(3)))
                .isEqualTo(text(TransactionReportMapper.encodeSeparatorRule()));
    }

    /**
     * Asserts that encodeDetailLine places all eight varying items and reseeds the two joining
     * hyphens.
     */
    @Test
    @DisplayName("encodeDetailLine places all eight varying items and reseeds the two joining "
            + "hyphens")
    void encodeDetailLinePlacesEightItemsAndReseedsTwoHyphens() {
        byte[] detail = representativeDetail();

        assertThat(detail).hasSize(REPORT_RECORD_LENGTH);
        assertThat(text(detail)).isEqualTo("0000000000000001"
                + " " + "00000000011"
                + " " + "01" + "-" + "Regular Sales D"
                + " " + "0005" + "-" + padded("Restaurant and Bar Purchases", 29)
                + " " + "POS TERM  "
                + "    " + "       1,234.56"
                + "  " + " ".repeat(19));

        // WHY : Assumptions: both joining hyphens are asserted at their own offsets. The reference
        //       never restates them, because line 362 of app/cbl/CBTRN03C.cbl re-initialises a
        //       record area that leaves FILLER untouched, so the two bytes survive from the first
        //       line to the last. A freshly allocated array has no such survivor, so a mapper that
        //       forgot to seed them would emit a plausible line with two blanks where a reader
        //       expects a code joined to its description.
        assertThat(text(detail).charAt(31)).isEqualTo('-');
        assertThat(text(detail).charAt(52)).isEqualTo('-');
    }

    /**
     * Asserts that encodeDetailLine zero-pads the account identifier and zero-fills the category
     * code.
     */
    @Test
    @DisplayName("encodeDetailLine zero-pads the account identifier and zero-fills the category "
            + "code")
    void encodeDetailLineZeroFillsBothNumericColumns() {
        String detail = text(representativeDetail());

        // WHY : Assumptions: the two numeric columns are filled by two different mechanisms and are
        //       therefore asserted separately. The account identifier is rendered to eleven digits
        //       by this class before the codec sees it, because line 18 of app/cpy/CVTRA07Y.cpy
        //       declares a CHARACTER target that would otherwise left-justify it; the category code
        //       is handed over as a number and filled by the codec, because line 24 declares an
        //       unsigned numeric target. Swapping the two would double-fill one and left-justify
        //       the other.
        assertThat(detail.substring(17, 28)).isEqualTo("00000000011");
        assertThat(detail.substring(48, 52)).isEqualTo("0005");
    }

    /**
     * Asserts that encodeDetailLine narrows both descriptions to their own column widths.
     */
    @Test
    @DisplayName("encodeDetailLine narrows both descriptions to their own column widths")
    void encodeDetailLineNarrowsBothDescriptions() {
        byte[] detail = TransactionReportMapper.encodeDetailLine("T", 1L, "01", "y".repeat(50), 1,
                "z".repeat(50), "S", Money.of("1.00"));

        // WHY : Assumptions: the two columns are 15 and 29 and are asserted with distinct fill
        //       characters, because a single repeated character could not show a narrowing that
        //       used the wrong width for the wrong column. Their sources are both 50, so an
        //       implementation that narrowed both to one width would still produce a full band.
        assertThat(text(detail).substring(32, 47)).isEqualTo("y".repeat(15));
        assertThat(text(detail).substring(53, 82)).isEqualTo("z".repeat(29));
    }

    /**
     * Asserts that the detail mask blanks the sign position of a positive amount and floats a minus
     * for a negative one.
     */
    @Test
    @DisplayName("the detail mask blanks the sign position of a positive amount and floats a minus "
            + "for a negative one")
    void theDetailMaskShowsAMinusOnlyForANegativeAmount() {
        assertThat(amountField(representativeDetail())).isEqualTo("       1,234.56");
        assertThat(amountField(TransactionReportMapper.encodeDetailLine("T", 1L, "01", "d", 1, "c",
                "S", Money.of("-1234.56")))).isEqualTo("-      1,234.56");
    }

    /**
     * Supplies each reference argument of the detail band paired with a {@code null} in its
     * position.
     *
     * @return a stream of argument name and an invocation that passes {@code null} there
     */
    private static Stream<Arguments> nullDetailArguments() {
        Money one = Money.of("1.00");
        return Stream.of(
                Arguments.of("transactionId", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine(null, 1L, "01", "d", 1, "c", "S", one)),
                Arguments.of("transactionTypeCode", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine("T", 1L, null, "d", 1, "c", "S", one)),
                Arguments.of("transactionTypeDescription",
                        (Executable) () -> TransactionReportMapper
                                .encodeDetailLine("T", 1L, "01", null, 1, "c", "S", one)),
                Arguments.of("transactionCategoryDescription",
                        (Executable) () -> TransactionReportMapper
                                .encodeDetailLine("T", 1L, "01", "d", 1, null, "S", one)),
                Arguments.of("transactionSource", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine("T", 1L, "01", "d", 1, "c", null, one)),
                Arguments.of("amount", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine("T", 1L, "01", "d", 1, "c", "S", null)));
    }

    /**
     * Asserts that encodeDetailLine rejects each null reference argument by naming that argument.
     *
     * @param argumentName the name of the argument the rejection must cite
     * @param invocation the call that passes {@code null} in that position
     */
    @ParameterizedTest(name = "encodeDetailLine rejects a null {0}")
    @MethodSource("nullDetailArguments")
    @DisplayName("encodeDetailLine rejects each null reference argument by naming that argument")
    void encodeDetailLineRejectsEachNullArgument(String argumentName, Executable invocation) {
        assertThatThrownBy(invocation::execute)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(argumentName);
    }

    /**
     * Asserts that encodeDetailLine refuses an account identifier the eleven-digit column cannot
     * show.
     */
    @Test
    @DisplayName("encodeDetailLine refuses an account identifier the eleven-digit column cannot "
            + "show")
    void encodeDetailLineRefusesAnOutOfDomainAccountIdentifier() {
        assertThatThrownBy(() -> TransactionReportMapper.encodeDetailLine("T", -1L, "01", "d", 1,
                "c", "S", Money.of("1.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> TransactionReportMapper.encodeDetailLine("T", 100000000000L, "01",
                "d", 1, "c", "S", Money.of("1.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("11 digit positions");
    }

    /**
     * Asserts that encodeDetailLine refuses a category code the four-digit column cannot show.
     */
    @Test
    @DisplayName("encodeDetailLine refuses a category code the four-digit column cannot show")
    void encodeDetailLineRefusesAnOutOfDomainCategoryCode() {
        // WHY : Assumptions: the category code fails inside the codec where the account identifier
        //       fails before it, and the two diagnostics are therefore different types. That is a
        //       consequence of which side performs the fill, asserted here so a reader does not
        //       expect one rule to cover both numeric columns.
        assertThatThrownBy(() -> TransactionReportMapper.encodeDetailLine("T", 1L, "01", "d", -1,
                "c", "S", Money.of("1.00")))
                .isInstanceOf(FixedWidthCodec.FieldCodecException.class)
                .hasMessageContaining("TRAN-REPORT-CAT-CD");
        assertThatThrownBy(() -> TransactionReportMapper.encodeDetailLine("T", 1L, "01", "d", 10000,
                "c", "S", Money.of("1.00")))
                .isInstanceOf(FixedWidthCodec.FieldCodecException.class)
                .hasMessageContaining("needs 5 digits");
    }

    /**
     * Supplies each fixed-width detail argument paired with a value one character too wide.
     *
     * @return a stream of field name and an invocation that supplies the over-wide value
     */
    private static Stream<Arguments> overWideDetailArguments() {
        Money one = Money.of("1.00");
        return Stream.of(
                Arguments.of("TRAN-REPORT-TRANS-ID", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine("x".repeat(17), 1L, "01", "d", 1, "c", "S", one)),
                Arguments.of("TRAN-REPORT-TYPE-CD", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine("T", 1L, "abc", "d", 1, "c", "S", one)),
                Arguments.of("TRAN-REPORT-SOURCE", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine("T", 1L, "01", "d", 1, "c", "x".repeat(11), one)));
    }

    /**
     * Asserts that encodeDetailLine refuses an over-wide value for a column it never narrows.
     *
     * @param fieldName the copybook field name the rejection must cite
     * @param invocation the call that supplies the over-wide value
     */
    @ParameterizedTest(name = "encodeDetailLine refuses an over-wide {0}")
    @MethodSource("overWideDetailArguments")
    @DisplayName("encodeDetailLine refuses an over-wide value for a column it never narrows")
    void encodeDetailLineRefusesAnOverWideFixedWidthArgument(String fieldName,
            Executable invocation) {
        // WHY : Assumptions: three of the five character arguments are refused when over-wide while
        //       the two descriptions are narrowed instead. The difference is not arbitrary: the
        //       three below are declared at the SAME width as their columns, so an over-wide value
        //       is a caller error, whereas the two descriptions are declared more than three times
        //       their columns and being over-wide is their normal condition.
        assertThatThrownBy(invocation::execute)
                .isInstanceOf(FixedWidthCodec.FieldCodecException.class)
                .hasMessageContaining(fieldName);
    }

    /**
     * Asserts that the page total band carries an eleven-character label and an eighty-six-dot
     * leader.
     */
    @Test
    @DisplayName("the page total band carries an eleven-character label and an eighty-six-dot "
            + "leader")
    void thePageTotalBandCarriesItsLabelAndLeader() {
        byte[] band = TransactionReportMapper.encodePageTotal(Money.of("1234.56"));

        assertThat(text(band)).isEqualTo(padded("Page Total", 11) + ".".repeat(86)
                + "+      1,234.56" + " ".repeat(21));
    }

    /**
     * Asserts that the account total band uses a thirteen-character label and an eighty-four-dot
     * leader.
     */
    @Test
    @DisplayName("the account total band uses a thirteen-character label and an eighty-four-dot "
            + "leader")
    void theAccountTotalBandUsesAWiderLabelAndAShorterLeader() {
        byte[] band = TransactionReportMapper.encodeAccountTotal(Money.of("-1234.56"));

        // WHY : Assumptions: this label exactly fills its thirteen characters, so it carries NO
        //       trailing blank where the other two labels each carry one. That is why the leader is
        //       84 rather than 86: 13 plus 84 and 11 plus 86 both reach the same 97. Unifying the
        //       leaders would move this one total two columns right of the other three.
        assertThat(text(band)).isEqualTo("Account Total" + ".".repeat(84)
                + "-      1,234.56" + " ".repeat(21));
    }

    /**
     * Asserts that the grand total band matches the page total band's label and leader pair.
     */
    @Test
    @DisplayName("the grand total band matches the page total band's label and leader pair")
    void theGrandTotalBandMatchesThePageTotalPair() {
        byte[] band = TransactionReportMapper.encodeGrandTotal(Money.of("1234.56"));

        assertThat(text(band)).isEqualTo(padded("Grand Total", 11) + ".".repeat(86)
                + "+      1,234.56" + " ".repeat(21));
    }

    /**
     * Asserts that all four money-bearing bands open their amount on the same column.
     */
    @Test
    @DisplayName("all four money-bearing bands open their amount on the same column")
    void allFourMoneyBearingBandsShareOneAmountColumn() {
        Money amount = Money.of("1234.56");
        List<byte[]> bands = List.of(
                representativeDetail(),
                TransactionReportMapper.encodePageTotal(amount),
                TransactionReportMapper.encodeAccountTotal(amount),
                TransactionReportMapper.encodeGrandTotal(amount));

        // WHY : Assumptions: the shared column is asserted across all four bands together rather
        //       than band by band, because the property being checked is an EQUALITY between four
        //       independently declared pairs of widths. Checking each band's own offset in
        //       isolation would pass for four bands that each placed its amount somewhere else.
        assertThat(bands).allSatisfy(band -> assertThat(amountField(band)).hasSize(AMOUNT_WIDTH));
        assertThat(bands).allSatisfy(band -> assertThat(amountField(band)).endsWith("1,234.56"));
    }

    /**
     * Asserts that the three total bands use the always-signed mask where the detail band uses the
     * leading-minus mask.
     */
    @Test
    @DisplayName("the three total bands use the always-signed mask where the detail band uses the "
            + "leading-minus mask")
    void theTotalBandsAndTheDetailBandUseDifferentMasks() {
        Money positive = Money.of("1234.56");

        // WHY : Assumptions: the two regimes differ in exactly ONE byte for a positive value, the
        //       sign position, so they are asserted against each other for the same magnitude. A
        //       single formatter with a sign flag would let a default decide which of the two a
        //       report line got, and every other byte of the field would still agree.
        assertThat(amountField(representativeDetail()).charAt(0)).isEqualTo(' ');
        assertThat(amountField(TransactionReportMapper.encodePageTotal(positive)).charAt(0))
                .isEqualTo('+');
        assertThat(amountField(TransactionReportMapper.encodeAccountTotal(positive)).charAt(0))
                .isEqualTo('+');
        assertThat(amountField(TransactionReportMapper.encodeGrandTotal(positive)).charAt(0))
                .isEqualTo('+');
    }

    /**
     * Asserts that a zero amount blanks its whole field, because every digit position of both masks
     * suppresses.
     */
    @Test
    @DisplayName("a zero amount blanks its whole field, because every digit position of both masks "
            + "suppresses")
    void aZeroAmountBlanksItsWholeField() {
        Money zero = Money.of("0.00");

        // WHY : Assumptions: this is the one rendering a reader is most likely to call a defect.
        //       Both masks at lines 30 and 54 of app/cpy/CVTRA07Y.cpy declare suppression symbols
        //       in the DECIMAL positions as well as the integer ones, and COBOL blanks an entire
        //       item whose every digit position suppresses when the value is zero -- sign position
        //       included. The statement masks behave differently, because their decimals are digit
        //       positions, and that contrast is asserted in StatementTextMapperTest.
        assertThat(amountField(TransactionReportMapper.encodeDetailLine("T", 1L, "01", "d", 1, "c",
                "S", zero))).isEqualTo(" ".repeat(AMOUNT_WIDTH));
        assertThat(amountField(TransactionReportMapper.encodePageTotal(zero)))
                .isEqualTo(" ".repeat(AMOUNT_WIDTH));
        assertThat(amountField(TransactionReportMapper.encodeAccountTotal(zero)))
                .isEqualTo(" ".repeat(AMOUNT_WIDTH));
        assertThat(amountField(TransactionReportMapper.encodeGrandTotal(zero)))
                .isEqualTo(" ".repeat(AMOUNT_WIDTH));
    }

    /**
     * Supplies the three total encoders paired with a {@code null} amount.
     *
     * @return a stream of argument name and an invocation that passes {@code null}
     */
    private static Stream<Arguments> nullTotals() {
        return Stream.of(
                Arguments.of("pageTotal",
                        (Executable) () -> TransactionReportMapper.encodePageTotal(null)),
                Arguments.of("accountTotal",
                        (Executable) () -> TransactionReportMapper.encodeAccountTotal(null)),
                Arguments.of("grandTotal",
                        (Executable) () -> TransactionReportMapper.encodeGrandTotal(null)));
    }

    /**
     * Asserts that every total encoder rejects a null amount by naming its own argument.
     *
     * @param argumentName the name of the argument the rejection must cite
     * @param invocation the call that passes {@code null}
     */
    @ParameterizedTest(name = "{0} rejects a null amount")
    @MethodSource("nullTotals")
    @DisplayName("every total encoder rejects a null amount by naming its own argument")
    void everyTotalEncoderRejectsANullAmount(String argumentName, Executable invocation) {
        assertThatThrownBy(invocation::execute)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(argumentName);
    }

    /**
     * Supplies every money-bearing encoder paired with an amount too wide for its mask.
     *
     * @return a stream of band label and an invocation that supplies the over-wide amount
     */
    private static Stream<Arguments> overWideAmounts() {
        Money tooWide = Money.of("1000000000.00");
        return Stream.of(
                Arguments.of("detail", (Executable) () -> TransactionReportMapper
                        .encodeDetailLine("T", 1L, "01", "d", 1, "c", "S", tooWide)),
                Arguments.of("pageTotal",
                        (Executable) () -> TransactionReportMapper.encodePageTotal(tooWide)),
                Arguments.of("accountTotal",
                        (Executable) () -> TransactionReportMapper.encodeAccountTotal(tooWide)),
                Arguments.of("grandTotal",
                        (Executable) () -> TransactionReportMapper.encodeGrandTotal(tooWide)));
    }

    /**
     * Asserts that every money-bearing band refuses an amount too wide for its nine-digit mask.
     *
     * @param label the band under test, shown in the case name
     * @param invocation the call that supplies the over-wide amount
     */
    @ParameterizedTest(name = "the {0} band refuses an amount too wide for its mask")
    @MethodSource("overWideAmounts")
    @DisplayName("every money-bearing band refuses an amount too wide for its nine-digit mask")
    void everyMoneyBearingBandRefusesAnOverWideAmount(String label, Executable invocation) {
        // WHY : Assumptions: this asserts registered divergence D-EDIT-MASK-OVERFLOW on all four
        //       bands rather than one. The three accumulators that feed the totals are declared
        //       with exactly the nine integer positions the masks provide at lines 134 to 136 of
        //       app/cbl/CBTRN03C.cbl, so the guard is not an expected path -- it stays reachable
        //       only because the shared money type admits ten.
        assertThatThrownBy(invocation::execute)
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("10 integer digits");
    }

    /**
     * Asserts that the total encoders hold no accumulator, so a total renders exactly the value
     * supplied.
     */
    @Test
    @DisplayName("the total encoders hold no accumulator, so a total renders exactly the value "
            + "supplied")
    void theTotalEncodersHoldNoAccumulator() {
        byte[] first = TransactionReportMapper.encodePageTotal(Money.of("100.00"));
        byte[] again = TransactionReportMapper.encodePageTotal(Money.of("100.00"));
        byte[] second = TransactionReportMapper.encodePageTotal(Money.of("250.00"));

        // WHY : Assumptions: this is the checkable half of registered divergence
        //       D-REPORT-GRAND-TOTAL. The reference adds the last transaction's amount into
        //       the page accumulator a second time at line 200 of app/cbl/CBTRN03C.cbl and carries
        //       it into the grand total at line 297, so a target that accumulated anywhere in this
        //       class could reproduce that doubling silently. Asserting that a repeat call is
        //       IDENTICAL and that a different value renders itself and not a running sum is what
        //       proves no accumulator exists here to do it.
        assertThat(text(again)).isEqualTo(text(first));
        assertThat(amountField(second)).isEqualTo("+        250.00");
        assertThat(amountField(second)).isNotEqualTo(amountField(first));
    }

    /**
     * Asserts that a card-break total is reachable unconditionally, with no argument that could
     * suppress it.
     *
     * @throws NoSuchMethodException if the account total encoder is renamed or its signature
     *     changes
     */
    @Test
    @DisplayName("a card-break total is reachable unconditionally, with no argument that could "
            + "suppress it")
    void aCardBreakTotalIsReachableUnconditionally() throws NoSuchMethodException {
        Method encoder = TransactionReportMapper.class.getMethod("encodeAccountTotal", Money.class);

        // WHY : Assumptions: this is the checkable half of registered divergence
        //       D-REPORT-CLOSING-TOTAL. The reference's end-of-file branch at lines 198 to 203
        //       of app/cbl/CBTRN03C.cbl never performs its card-break paragraph, so the last group
        //       gets no total band; the target closes every group alike. Asserting the signature
        //       carries exactly one parameter, the amount, is what proves no end-of-run flag
        //       exists by which a caller could reintroduce the suppression.
        assertThat(encoder.getParameterCount()).isEqualTo(1);
        assertThat(encoder.getParameterTypes()[0]).isEqualTo(Money.class);
        assertThat(TransactionReportMapper.encodeAccountTotal(Money.of("1.00")))
                .hasSize(REPORT_RECORD_LENGTH);
    }

    /**
     * Asserts that every encoded band is exactly the declared record length and freshly allocated.
     */
    @Test
    @DisplayName("every encoded band is exactly the declared record length and freshly allocated")
    void everyEncodedBandIsExactLengthAndFreshlyAllocated() {
        byte[] first = TransactionReportMapper.encodeSeparatorRule();
        Arrays.fill(first, (byte) 'X');

        // WHY : Assumptions: mutating a returned band and re-encoding is the only way to prove the
        //       emitter holds no shared record area. The reference DOES hold one -- line 362 of
        //       app/cbl/CBTRN03C.cbl re-initialises a record area whose FILLER survives -- so a
        //       reader arriving from that side would reasonably expect a shared buffer here.
        assertThat(text(TransactionReportMapper.encodeSeparatorRule()))
                .isEqualTo("-".repeat(REPORT_RECORD_LENGTH));
        assertThat(List.of(TransactionReportMapper.encodeNameHeader(RANGE_START, RANGE_END),
                        TransactionReportMapper.encodeColumnHeader(),
                        TransactionReportMapper.encodeBlankLine(),
                        representativeDetail(),
                        TransactionReportMapper.encodePageTotal(Money.of("1.00")),
                        TransactionReportMapper.encodeAccountTotal(Money.of("1.00")),
                        TransactionReportMapper.encodeGrandTotal(Money.of("1.00"))))
                .allSatisfy(band -> assertThat(band).hasSize(REPORT_RECORD_LENGTH));
    }

    /**
     * Asserts that the grand total band carries the sum of the page totals and not the value the
     * reference would have produced for the same run.
     */
    @Test
    @DisplayName("the grand total band carries the sum of the page totals and not the reference's "
            + "repetition")
    void theGrandTotalBandCarriesTheSumOfThePageTotalsAndNotTheBaselinesRepetition() {
        // WHY : Assumptions: 700.00 and 534.56 are the two page totals of a two-page run whose last
        //       transaction is the 534.56, chosen so the two expectations differ by exactly one
        //       repetition of the closing amount. The target's grand total is their sum; the
        //       reference's is their sum plus that closing amount once more, because its
        //       end-of-file branch adds the leftover record area into the page accumulator at line
        //       200 and the page-totals paragraph then carries the inflated page total into the
        //       grand total at line 297. Values that did not differ in that one place would satisfy
        //       both readings and pin nothing.
        // WHY : Trade-offs: both expectations are asserted, not just the first. The repetition lives
        //       in the accumulation rather than in this encoder, so an assertion that the band
        //       carries what it was given would hold under either behaviour and would not pin the
        //       divergence at all. This is the value-level half of D-REPORT-GRAND-TOTAL;
        //       theTotalEncodersHoldNoAccumulator above is the structural half.
        byte[] band = TransactionReportMapper.encodeGrandTotal(Money.of(TARGET_GRAND_TOTAL));

        assertThat(band)
                .as("app/cpy/CVTRA07Y.cpy declares every report band 133 bytes wide")
                .hasSize(REPORT_RECORD_LENGTH);
        assertThat(amountField(band))
                .as("the grand total is the plain sum of %s and %s", FIRST_PAGE_TOTAL,
                        CLOSING_PAGE_TOTAL)
                .isEqualTo(RENDERED_TARGET_TOTAL)
                .isNotEqualTo(RENDERED_BASELINE_TOTAL);
    }

    /**
     * Asserts that the account total band encodes the final card group exactly as it encodes any
     * other.
     */
    @Test
    @DisplayName("the account total band encodes the final card group exactly as any other")
    void theAccountTotalBandEncodesTheFinalCardGroupExactlyAsAnyOther() {
        // WHY : Assumptions: one total is encoded TWICE, standing for a middle group and for the last
        //       one, rather than two different totals being compared. Equal input is the whole
        //       premise: the encoder is stateless and carries no notion of position in a run, so
        //       two invocations with equal input must produce equal bytes. That equality IS the
        //       divergence D-REPORT-CLOSING-TOTAL: the reference's end-of-file branch never performs
        //       its card-break paragraph, so its last group has no band to compare with, while here
        //       the last group is closed like every other.
        byte[] middleGroup = TransactionReportMapper.encodeAccountTotal(Money.of(FIRST_PAGE_TOTAL));
        byte[] finalGroup = TransactionReportMapper.encodeAccountTotal(Money.of(FIRST_PAGE_TOTAL));

        assertThat(finalGroup)
                .as("nothing in the encoder distinguishes the last group from an earlier one")
                .isEqualTo(middleGroup)
                .hasSize(REPORT_RECORD_LENGTH);
        assertThat(amountField(finalGroup))
                .as("the account total lands in the same amount column as the other two bands")
                .isEqualTo("+        700.00");
    }

    /**
     * Asserts that the account total encoder is declared once, with no overload a caller could use
     * to suppress the closing band.
     */
    @Test
    @DisplayName("the account total encoder is declared once, with no suppressing overload")
    void theAccountTotalEncoderExposesNoSuppressionParameter() {
        // WHY : Alternatives Considered: asserting only the signature of the single encoder, as
        //       aCardBreakTotalIsReachableUnconditionally above does. Rejected as insufficient on
        //       its own, because a suppression could be reintroduced as a SECOND method rather than
        //       as a parameter of the first -- an overload taking a boolean, or a position -- which a
        //       getMethod lookup for the one-argument form would not notice. Enumerating the
        //       declared methods is what covers that shape.
        List<Method> encoders = Arrays.stream(TransactionReportMapper.class.getDeclaredMethods())
                .filter(method -> "encodeAccountTotal".equals(method.getName()))
                .toList();

        assertThat(encoders)
                .as("exactly one account total encoder is declared, with no suppressing overload")
                .hasSize(1);
        assertThat(encoders.get(0).getParameterTypes())
                .as("its only argument is the total being closed")
                .containsExactly(Money.class);
    }
}
