package com.carddemo.reporting.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.common.money.Money;
import com.carddemo.reporting.domain.TransactionCategoryBalanceView;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins the category-balance report line against the DFSORT extract it replaces, byte position by byte
 * position.
 *
 * <p>Purpose: {@code app/jcl/PRTCATBL.jcl} is the whole specification of this artifact -- there is no
 * COBOL program in the reference for it, only a sort step -- so the {@code OUTREC} operand list at
 * L53-L56 and the {@code SORTOUT} record length at L61 are the only two statements of what a correct
 * line looks like, and they disagree with each other by one byte. This class asserts the resolution
 * recorded as {@code D-PRTCATBL-LRECL}: forty bytes, with the disputed byte taken out of the trailing
 * padding.
 *
 * <p>Assumptions: the expected bytes are composed here from the FIELD WIDTHS the reference's own
 * {@code SYMNAMES} entries declare -- 11, 2 and 4 at L47-L49 -- rather than from the layout class's
 * own constants. A test that read {@code CategoryBalanceLineLayout.ACCOUNT_ID_WIDTH} to build its
 * expectation would agree with the layout whatever the layout said, including if a width were changed
 * to the wrong value, which is the one failure these assertions exist to catch.
 *
 * <p>Assumptions: the lines are compared as {@code String} decoded from US-ASCII rather than as raw
 * byte arrays. Every character the layout emits is a digit, a period or a blank, all of which are one
 * byte in that encoding, so the two comparisons are equivalent and one of them is readable in a
 * failure message. The width assertions are made on the byte array, because bytes are what the record
 * length is declared in.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own where they have any.
 */
class CategoryBalanceLineLayoutTest {

    /** An account identifier of fewer than eleven digits, so the padding rule is exercised. */
    private static final long ACCOUNT_ID = 6L;

    /** A type code of the declared width, so the field is full without padding. */
    private static final String TYPE_CODE = "01";

    /** A category code carrying a leading zero, which the reference's ZD declaration requires. */
    private static final String CATEGORY_CODE = "0001";

    // WHY : Assumptions: the whole line is asserted as one literal rather than field by field, and the
    //       literal is written with its blanks visible in the source. The defect this artifact is most
    //       exposed to is a separator in the wrong place or a padding run of the wrong length, and
    //       those are the two things a field-by-field assertion cannot see -- each field would pass on
    //       its own while the composed line was wrong.
    /**
     * Asserts the composed line matches the reference's operand order, separators and padding exactly.
     */
    @Test
    @DisplayName("the line composes the reference's seven operands in order, at forty bytes")
    void theLineComposesTheReferenceOperandsInOrder() {
        byte[] line = CategoryBalanceLineLayout.render(row(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE,
                Money.of("1234.56")));

        assertThat(line)
                .as("app/jcl/PRTCATBL.jcl:61 declares DCB=(LRECL=40)")
                .hasSize(40);
        assertThat(decode(line))
                .as("11-digit identifier, blank, 2-char type, blank, 4-digit category, blank,"
                        + " 12-char edited balance, 8 blanks")
                .isEqualTo("00000000006 01 0001 000001234.56        ");
    }

    // WHY : Assumptions: the forty-byte width is asserted for a value that fills EVERY field to its
    //       declared width as well as for the short values above, because the two cases fail
    //       differently. A padding rule that padded to the wrong width shows up on short values; a
    //       composition that had one field too wide shows up only when nothing is padded at all.
    /**
     * Asserts the width holds when every field is filled to its declared width.
     */
    @Test
    @DisplayName("the line is forty bytes when every field is full to its declared width")
    void theLineIsFortyBytesWithEveryFieldFull() {
        byte[] line = CategoryBalanceLineLayout.render(
                row(99999999999L, "ZZ", "9999", Money.of("999999999.99")));

        assertThat(line).hasSize(40);
        assertThat(decode(line)).isEqualTo("99999999999 ZZ 9999 999999999.99        ");
    }

    // WHY : Assumptions: the trailing padding is asserted to be EIGHT blanks and the assertion names
    //       the reference's `9X` in its own message, because this is the one place the record-length
    //       resolution is observable. A future reader who takes the operand list as authoritative
    //       would make it nine, and this is the assertion that tells them which statement was chosen
    //       and where the reasoning is recorded.
    /**
     * Asserts the disputed byte is absorbed in the trailing padding and nowhere else.
     */
    @Test
    @DisplayName("the trailing padding is eight blanks, absorbing the one-byte disagreement")
    void theTrailingPaddingAbsorbsTheDisagreement() {
        String line = decode(CategoryBalanceLineLayout.render(
                row(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE, Money.of("0.00"))));

        assertThat(line.substring(line.length() - CategoryBalanceLineLayout.TRAILING_BLANK_WIDTH))
                .as("the reference's 9X at app/jcl/PRTCATBL.jcl:56 composes 41 bytes against the"
                        + " LRECL=40 at L61; the resolution D-PRTCATBL-LRECL drops one blank")
                .isEqualTo("        ");
        assertThat(line.charAt(line.length() - CategoryBalanceLineLayout.TRAILING_BLANK_WIDTH - 1))
                .as("the character before the padding is the last decimal digit, so exactly one blank"
                        + " was dropped and no value byte was")
                .isEqualTo('0');
    }

    // WHY : Assumptions: the sign is asserted to be ABSENT for a negative balance rather than rendered
    //       somewhere. The reference's mask at app/jcl/PRTCATBL.jcl:56 spells twelve positions with no
    //       sign selector, so a credit balance and a debit balance of the same magnitude render
    //       identically -- which is a genuine loss of information in the baseline, carried across
    //       deliberately, and asserting it is what stops a later reader "fixing" it into a divergence.
    /**
     * Asserts the edit mask renders magnitude only, for every sign, at a constant twelve characters.
     *
     * @param amount the balance to render
     * @param expected the twelve characters the reference's {@code EDIT=(TTTTTTTTT.TT)} produces
     */
    @ParameterizedTest
    @CsvSource({
        "0.00,000000000.00",
        "0.01,000000000.01",
        "1234.56,000001234.56",
        "-1234.56,000001234.56",
        "-0.01,000000000.01",
        "999999999.99,999999999.99",
    })
    @DisplayName("the balance renders under the reference's edit mask, magnitude only")
    void theBalanceRendersUnderTheReferenceEditMask(String amount, String expected) {
        String line = decode(CategoryBalanceLineLayout.render(
                row(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE, Money.of(amount))));

        assertThat(line.substring(20, 32))
                .as("positions 21 through 32 carry the edited balance")
                .isEqualTo(expected);
        assertThat(line).hasSize(40);
    }

    // WHY : Assumptions: the two padding SIDES are asserted against each other in one case, because
    //       they differ and the difference is the reference's, not a choice: L47 and L49 declare ZD so
    //       those two fields are digit-filled from the left, and L48 declares CH so that one is
    //       blank-filled from the right. Getting one of the three wrong produces a line of the correct
    //       length that no consumer can parse.
    /**
     * Asserts the numeric fields pad left with zeroes while the character field pads right with blanks.
     */
    @Test
    @DisplayName("the ZD fields pad left with zeroes and the CH field pads right with blanks")
    void thePaddingSideFollowsTheDeclaredFieldType() {
        String line = decode(CategoryBalanceLineLayout.render(row(7L, "A", "12", Money.of("1.00"))));

        assertThat(line.substring(0, 11))
                .as("TRANCAT-ACCT-ID,1,11,ZD at app/jcl/PRTCATBL.jcl:47")
                .isEqualTo("00000000007");
        assertThat(line.substring(12, 14))
                .as("TRANCAT-TYPE-CD,12,2,CH at app/jcl/PRTCATBL.jcl:48")
                .isEqualTo("A ");
        assertThat(line.substring(15, 19))
                .as("TRANCAT-CD,14,4,ZD at app/jcl/PRTCATBL.jcl:49")
                .isEqualTo("0012");
        assertThat(line.charAt(11)).isEqualTo(' ');
        assertThat(line.charAt(14)).isEqualTo(' ');
        assertThat(line.charAt(19)).isEqualTo(' ');
    }

    // WHY : Assumptions: an over-wide value is asserted to be REFUSED rather than truncated, and that
    //       is the opposite of how the disputed trailing byte is handled. The difference is what is
    //       lost: truncating padding loses nothing, whereas truncating an identifier or a code emits a
    //       line that names a DIFFERENT row -- a report attributing one account's balance to another
    //       is worse than no report.
    /**
     * Asserts a value wider than its declared field is refused rather than silently truncated.
     */
    @Test
    @DisplayName("a value wider than its declared field is refused, not truncated")
    void anOverWideValueIsRefused() {
        List<TransactionCategoryBalanceView> overWide = List.of(
                row(123456789012L, TYPE_CODE, CATEGORY_CODE, Money.of("1.00")),
                row(ACCOUNT_ID, "ABC", CATEGORY_CODE, Money.of("1.00")),
                row(ACCOUNT_ID, TYPE_CODE, "00001", Money.of("1.00")));

        for (TransactionCategoryBalanceView row : overWide) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("truncating a key field would attribute a balance to a different row")
                    .isThrownBy(() -> CategoryBalanceLineLayout.render(row))
                    .withMessageContaining("app/jcl/PRTCATBL.jcl");
        }
    }

    // WHY : Assumptions: a balance needing a tenth integer digit is asserted to raise rather than to
    //       wrap or to widen the line. The reference's mask has nine integer positions and the column
    //       is NUMERIC(11,2), so a tenth digit cannot be stored either -- meaning the refusal reports
    //       a condition the database already prevents, and the assertion documents that the layout
    //       does not paper over it if the column is ever widened without the mask being revisited.
    /**
     * Asserts a balance exceeding the mask's nine integer positions is refused.
     */
    @Test
    @DisplayName("a balance needing a tenth integer digit is refused by the edit mask")
    void aBalanceWiderThanTheMaskIsRefused() {
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> CategoryBalanceLineLayout.render(
                        row(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE, Money.of("1000000000.00"))));
    }

    /**
     * Asserts the layout refuses instantiation, because it holds a record shape and no behaviour.
     *
     * @throws Exception if the constructor cannot be reached reflectively, which would itself be the
     *     failure this case reports
     */
    @Test
    @DisplayName("the layout holder refuses reflective instantiation")
    void theLayoutHolderIsNotInstantiable() throws Exception {
        var constructor = CategoryBalanceLineLayout.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatExceptionOfType(java.lang.reflect.InvocationTargetException.class)
                .isThrownBy(constructor::newInstance)
                .withCauseInstanceOf(AssertionError.class);
    }

    /**
     * Builds one projected balance row without a database.
     *
     * @param accountId the account identifier
     * @param typeCode the transaction type code
     * @param categoryCode the transaction category code
     * @param balance the category balance
     * @return the row
     */
    private static TransactionCategoryBalanceView row(
            Long accountId, String typeCode, String categoryCode, Money balance) {
        return new TransactionCategoryBalanceView(accountId, typeCode, categoryCode, balance);
    }

    /**
     * Decodes a rendered line for readable comparison.
     *
     * @param line the rendered bytes
     * @return the line as text
     */
    private static String decode(byte[] line) {
        return new String(line, StandardCharsets.US_ASCII);
    }
}
