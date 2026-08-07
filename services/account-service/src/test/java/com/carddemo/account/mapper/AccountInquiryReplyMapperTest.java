package com.carddemo.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.account.domain.Account;
import com.carddemo.common.codec.InquiryRequestCodec;
import com.carddemo.common.codec.ZonedDecimalCodec;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact bytes of the account-inquiry reply.
 *
 * <h2>Purpose</h2>
 * <p>The reply is put with a string format indicator, so its twenty-two field widths, its label text and its
 * monetary encoding are the interface -- a consumer reads each value by offset. This class asserts the whole
 * 252-character block, the offset of every monetary field, and the sign encoding, because a positional contract
 * cannot be checked any other way.</p>
 *
 * <p>Assumptions: the monetary assertions are made against the shared zoned-decimal codec's own output rather
 * than against a hand-written literal. The reference fields are declared {@code PIC S9(10)V99} DISPLAY, so on
 * the wire they are twelve characters with the sign overpunched into the last digit and no decimal point
 * present -- a form no reader guesses correctly, and one whose canonical spelling belongs to the codec.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no parameter,
 * return or exception section.</p>
 */
class AccountInquiryReplyMapperTest {

    /**
     * The mapper under test, in its real form.
     */
    private final AccountInquiryReplyMapper mapper = new AccountInquiryReplyMapper();

    /**
     * Builds an account row with distinct values in every published field.
     *
     * <p>Assumptions: every value is DIFFERENT from every other, including the three monetary ones. Repeated
     * values would let a field-ordering error pass, because two swapped fields holding the same value are
     * indistinguishable.</p>
     *
     * @return the row, never {@code null}
     */
    private static Account account() {
        return new Account(12_345_678_901L, "Y",
                new BigDecimal("1234.56"), new BigDecimal("5000.00"), new BigDecimal("500.00"),
                LocalDate.of(2020, 1, 15), LocalDate.of(2027, 12, 31), LocalDate.of(2024, 6, 1),
                new BigDecimal("111.11"), new BigDecimal("222.22"), "12345", "DEFAULT   ");
    }

    /**
     * Renders a monetary value the way the wire carries it.
     *
     * @param value the amount
     * @return the twelve-character zoned rendering, never {@code null}
     */
    private static String zoned(String value) {
        return ZonedDecimalCodec.encode(new BigDecimal(value), 10, 2, true);
    }

    /**
     * Verifies the block is exactly its declared length.
     *
     * <p>Assumptions: this is the one property every consumer reading by offset depends on, and the mapper
     * asserts it internally too. Asserting it here as well proves that internal check is reachable rather than
     * theoretical, and fixes the number a future reader can rely on.</p>
     */
    @Test
    @DisplayName("the reply block is exactly its declared length")
    void theBlockIsExactlyItsDeclaredLength() {
        assertThat(this.mapper.accountFound(account()))
                .hasSize(AccountInquiryReplyMapper.REPLY_BLOCK_LENGTH);
        assertThat(AccountInquiryReplyMapper.REPLY_BLOCK_LENGTH).isEqualTo(252);
    }

    /**
     * Verifies the whole block matches the reference layout character for character.
     */
    @Test
    @DisplayName("the whole block matches the reference layout character for character")
    void theWholeBlockMatchesTheReferenceLayout() {
        String expected = "ACCOUNT ID : " + "12345678901"
                + "ACCOUNT STATUS : " + "Y"
                + "BALANCE : " + zoned("1234.56")
                + "CREDIT LIMIT : " + zoned("5000.00")
                + "CASH LIMIT : " + zoned("500.00")
                + "OPEN DATE : " + "2020-01-15"
                + "EXPR DATE : " + "2027-12-31"
                + "REIS DATE : " + "2024-06-01"
                + "CREDIT BAL : " + zoned("111.11")
                + "DEBIT BAL : " + zoned("222.22")
                + "GROUP ID : " + "DEFAULT   ";

        assertThat(this.mapper.accountFound(account())).isEqualTo(expected);
    }

    /**
     * Verifies every label crosses verbatim, including the spacing around each colon.
     *
     * <p>Assumptions: the labels are asserted individually because tidying any one of them -- removing the
     * space before a colon, for instance -- shifts every following field by a character while looking like a
     * formatting improvement in a diff.</p>
     */
    @Test
    @DisplayName("every label crosses verbatim including the spacing around its colon")
    void everyLabelCrossesVerbatim() {
        String block = this.mapper.accountFound(account());

        for (String label : new String[] {
            "ACCOUNT ID : ", "ACCOUNT STATUS : ", "BALANCE : ", "CREDIT LIMIT : ",
            "CASH LIMIT : ", "OPEN DATE : ", "EXPR DATE : ", "REIS DATE : ",
            "CREDIT BAL : ", "DEBIT BAL : ", "GROUP ID : "}) {
            assertThat(block).as("the label %s must cross verbatim", label).contains(label);
        }
    }

    /**
     * Verifies money is carried as a twelve-character zoned decimal with no decimal point.
     *
     * <p>Assumptions: the absence of a decimal point is asserted explicitly, because the intuitive rendering
     * would include one and would then be the wrong width -- so a consumer decoding by the copybook would read
     * the following label as part of the number.</p>
     */
    @Test
    @DisplayName("money is a twelve-character zoned decimal with no decimal point")
    void moneyIsZonedWithNoDecimalPoint() {
        String block = this.mapper.accountFound(account());
        int balanceStart = block.indexOf("BALANCE : ") + "BALANCE : ".length();
        String balance = block.substring(balanceStart, balanceStart + 12);

        assertThat(balance).hasSize(12).doesNotContain(".").isEqualTo(zoned("1234.56"));
    }

    /**
     * Verifies a negative balance carries its sign in the overpunched last digit, not as a leading minus.
     *
     * <p>Assumptions: this is the case a naive rendering gets wrong in a way no length check catches. A leading
     * minus would occupy a character the layout has no room for, pushing a digit out; the overpunch keeps the
     * field at twelve characters and encodes the sign in the final byte, which is what the reference field's
     * declaration means.</p>
     */
    @Test
    @DisplayName("a negative balance carries an overpunched sign rather than a leading minus")
    void aNegativeBalanceIsOverpunched() {
        Account overdrawn = new Account(1L, "Y",
                new BigDecimal("-45.67"), new BigDecimal("100.00"), new BigDecimal("10.00"),
                LocalDate.of(2020, 1, 1), LocalDate.of(2027, 1, 1), LocalDate.of(2024, 1, 1),
                BigDecimal.ZERO, BigDecimal.ZERO, "12345", "DEFAULT");

        String block = this.mapper.accountFound(overdrawn);
        int balanceStart = block.indexOf("BALANCE : ") + "BALANCE : ".length();
        String balance = block.substring(balanceStart, balanceStart + 12);

        assertThat(balance).hasSize(12).doesNotContain("-").isEqualTo(zoned("-45.67"));
        assertThat(ZonedDecimalCodec.decode(balance, 10, 2, true))
                .isEqualByComparingTo(new BigDecimal("-45.67"));
        assertThat(block).hasSize(AccountInquiryReplyMapper.REPLY_BLOCK_LENGTH);
    }

    /**
     * Verifies an account identifier shorter than its field is left-padded with zeros.
     *
     * <p>Assumptions: zeros rather than spaces, because the reference field is {@code PIC 9(11)} and a numeric
     * display field is zero-filled. Space-filling would make the value fail a numeric decode at the
     * consumer.</p>
     */
    @Test
    @DisplayName("a short account identifier is left-padded with zeros")
    void aShortIdentifierIsZeroPadded() {
        Account small = new Account(7L, "Y",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDate.of(2020, 1, 1), LocalDate.of(2027, 1, 1), LocalDate.of(2024, 1, 1),
                BigDecimal.ZERO, BigDecimal.ZERO, "12345", "X");

        assertThat(this.mapper.accountFound(small)).startsWith("ACCOUNT ID : 00000000007");
    }

    /**
     * Verifies a text value shorter than its field is padded to the field width.
     *
     * <p>Assumptions: the group identifier is the case to assert, because it is the only published text field
     * wider than one character. Its field is ten characters and a shorter value must be padded, or the reply
     * would be short and every consumer reading a fixed-length record would mis-align.</p>
     */
    @Test
    @DisplayName("a short text value is padded to its field width")
    void aShortTextValueIsPadded() {
        Account shortGroup = new Account(1L, "Y",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDate.of(2020, 1, 1), LocalDate.of(2027, 1, 1), LocalDate.of(2024, 1, 1),
                BigDecimal.ZERO, BigDecimal.ZERO, "12345", "X");

        String block = this.mapper.accountFound(shortGroup);

        assertThat(block).hasSize(AccountInquiryReplyMapper.REPLY_BLOCK_LENGTH);
        assertThat(block).endsWith("GROUP ID : X" + " ".repeat(9));
    }

    /**
     * Verifies a null monetary column FAILS rather than being published as a zero balance.
     *
     * <p>Assumptions: reflection is used to produce the null, and it is the only way to produce one. Every
     * monetary column is declared {@code NOT NULL} in the migration, mapped {@code nullable = false} on the
     * entity, and rejected by the entity's public constructor, so no database row and no ordinary construction
     * can carry one. What reflection stands in for is the single remaining path: schema drift reaching the
     * entity through the field-access hydration Hibernate performs on the protected no-argument
     * constructor.</p>
     *
     * <p>Assumptions: the assertion is that this FAILS. An earlier shape of the mapper substituted zero, which
     * would have reported an account's balance as {@code 0.00} to the requester -- a financial misstatement
     * that looks like a valid answer, which is strictly worse than an exception. This test is what keeps a
     * future author from reinstating the substitution as a defensive nicety.</p>
     *
     * @throws Exception if the entity's no-argument constructor or its field cannot be reached, which would
     *     mean the entity no longer supports the hydration path this test simulates
     */
    @Test
    @DisplayName("a null monetary column fails rather than publishing a zero balance")
    void aNullMonetaryColumnFailsLoudly() throws Exception {
        var constructor = Account.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Account hydrated = constructor.newInstance();
        for (var field : new String[] {"accountId", "activeStatus", "creditLimit", "cashCreditLimit",
            "openDate", "expirationDate", "reissueDate", "currentCycleCredit", "currentCycleDebit",
            "addressZip", "groupId"}) {
            var member = Account.class.getDeclaredField(field);
            member.setAccessible(true);
            member.set(hydrated, switch (field) {
                case "accountId" -> 1L;
                case "activeStatus" -> "Y";
                case "openDate", "expirationDate", "reissueDate" -> LocalDate.of(2020, 1, 1);
                case "addressZip" -> "12345";
                case "groupId" -> "X";
                default -> BigDecimal.ZERO;
            });
        }

        assertThatThrownBy(() -> this.mapper.accountFound(hydrated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("curr_bal")
                .hasMessageContaining("report this account's position as zero");
    }

    /**
     * Verifies the two invalid-parameters sentences are different, and each matches the reference text.
     *
     * <p>Assumptions: the difference is the assertion. The reference program produces two distinct sentences
     * from two distinct causes -- a lookup that ran and found nothing, and a request that was never eligible
     * for one -- and merging them would make those causes indistinguishable to the requester.</p>
     */
    @Test
    @DisplayName("the two invalid-parameters sentences are distinct and each matches the reference text")
    void theTwoInvalidSentencesAreDistinct() {
        String notFound = this.mapper.accountNotFound("00000000123");
        String invalid = this.mapper.invalidRequest("00000000123", "BADF");

        assertThat(notFound).isEqualTo("INVALID REQUEST PARAMETERS ACCT ID : 00000000123");
        assertThat(invalid)
                .isEqualTo("INVALID REQUEST PARAMETERS ACCT ID : 00000000123FUNCTION : BADF");
        assertThat(invalid).isNotEqualTo(notFound);
    }

    /**
     * Verifies the key is echoed with its leading zeros intact.
     *
     * <p>Assumptions: the reference program strings {@code WS-KEY} itself, a {@code PIC 9(11)} display field,
     * so the zeros are present. Rendering the number instead would drop them and change the sentence a
     * requester compares against.</p>
     */
    @Test
    @DisplayName("the echoed key keeps its leading zeros")
    void theEchoedKeyKeepsItsLeadingZeros() {
        assertThat(this.mapper.accountNotFound("00000000001")).endsWith("00000000001");
    }

    /**
     * Verifies framing pads each of the three reply forms to the message length.
     */
    @Test
    @DisplayName("framing pads every reply form to the message length")
    void framingPadsEveryReplyForm() {
        for (String body : new String[] {
            this.mapper.accountFound(account()),
            this.mapper.accountNotFound("00000000001"),
            this.mapper.invalidRequest("00000000001", "BADF")}) {
            assertThat(this.mapper.frame(body))
                    .hasSize(InquiryRequestCodec.MESSAGE_LENGTH)
                    .startsWith(body);
        }
    }

    /**
     * Verifies an over-wide text value is refused rather than truncated.
     *
     * <p>Assumptions: refusing is what stops a silent field shift. Truncating a ten-character group identifier
     * that arrived eleven characters long would keep the block at its declared length while moving nothing --
     * but it would publish a different group, so the value is refused instead.</p>
     */
    @Test
    @DisplayName("an over-wide text value is refused rather than truncated")
    void anOverWideValueIsRefused() {
        Account wide = new Account(1L, "Y",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDate.of(2020, 1, 1), LocalDate.of(2027, 1, 1), LocalDate.of(2024, 1, 1),
                BigDecimal.ZERO, BigDecimal.ZERO, "12345", "TOOLONGGROUPID");

        assertThatThrownBy(() -> this.mapper.accountFound(wide))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shift every following field");
    }

    /**
     * Verifies a null account is refused.
     */
    @Test
    @DisplayName("a null account is refused")
    void aNullAccountIsRefused() {
        assertThatThrownBy(() -> this.mapper.accountFound(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.mapper.accountNotFound(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.mapper.invalidRequest("00000000001", null))
                .isInstanceOf(NullPointerException.class);
    }
}
