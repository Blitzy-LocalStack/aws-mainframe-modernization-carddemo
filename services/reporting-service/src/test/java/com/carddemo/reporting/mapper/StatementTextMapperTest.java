package com.carddemo.reporting.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.money.Money;
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
 * @see StatementTextMapper
 * @see StatementBandLayouts
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
     * @param band the emitted band; must not be {@code null}
     * @return the band decoded as ASCII text, of the same length as the band
     */
    private static String text(byte[] band) {
        return new String(band, StandardCharsets.UTF_8);
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
        assertThat(assembled).hasSize(StatementTextMapper.ASSEMBLED_NAME_WIDTH);
        assertThat(assembled)
                .isEqualTo("A".repeat(25) + " " + "B".repeat(25) + " " + "C".repeat(23));
        assertThat(StatementTextMapper.NAME_ASSEMBLY_CAPACITY
                - StatementTextMapper.ASSEMBLED_NAME_WIDTH).isEqualTo(3);
    }

    /**
     * Supplies one over-wide argument per position of the name assembly.
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
        assertThatThrownBy(() -> StatementTextMapper.assembleName(first, middle, last))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(argumentName)
                .hasMessageContaining(String.valueOf(StatementTextMapper.NAME_PART_WIDTH));
    }

    /**
     * Supplies one {@code null} argument per position of the name assembly.
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
     * Asserts that prepareHeaderFields places every component at the exact width its band
     * item declares.
     */
    @Test
    @DisplayName("prepareHeaderFields places every component at the exact width its band item "
            + "declares")
    void prepareHeaderFieldsPlacesEveryComponentAtItsDeclaredWidth() {
        StatementTextMapper.PreparedHeaderFields fields = representativeHeader();

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
     * Asserts that preparedHeaderFields derives the markup name instead of storing an eighth
     * component.
     */
    @Test
    @DisplayName("PreparedHeaderFields derives the markup name instead of storing an eighth "
            + "component")
    void preparedHeaderFieldsDerivesTheMarkupNameRatherThanStoringIt() {
        StatementTextMapper.PreparedHeaderFields fields = representativeHeader();

        // WHY : Refactoring Rationale: the narrowed name was once an eighth stored component whose
        //       agreement with the assembled name nothing checked, so an instance could hold the
        //       narrowing of one name beside another name entirely. Asserting the component count
        //       here is what stops it being reintroduced: a stored component would restore the
        //       unchecked pair and this assertion would fail before any behaviour changed.
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
     * Supplies the three emission entry points paired with a {@code null} prepared-field argument.
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
}
