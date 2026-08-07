package com.carddemo.batch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the composite category-balance key admits exactly one spelling of each code.
 *
 * <p>Purpose: the key's components map to fixed-character columns, whose trailing-blank insensitivity
 * forgives a value short on the RIGHT and does nothing at all for one short on the LEFT. A category code
 * of {@code "5"} is therefore stored as {@code "5"} plus three blanks and compares as a DIFFERENT key
 * from {@code "0005"}, so two callers spelling one logical code differently would produce two rows for
 * one account, type and category -- and the running balance of that category would become whichever of
 * the two a later read happened to find. Nothing downstream could detect it, because both rows satisfy
 * every declared constraint.</p>
 */
@DisplayName("the category-balance composite key")
class CategoryKeyCanonicalityTest {

    /** The account the cases key on. */
    private static final long ACCOUNT_ID = 11L;

    /** The canonical four-digit category code. */
    private static final String CANONICAL_CATEGORY = "0005";

    /** A key spelled canonically is accepted and reports its components unchanged. */
    @Test
    @DisplayName("accept the canonical spelling and report it unchanged")
    void acceptsTheCanonicalSpelling() {
        TransactionCategoryBalanceId id =
                new TransactionCategoryBalanceId(ACCOUNT_ID, "01", CANONICAL_CATEGORY);

        assertThat(id.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(id.getTypeCd()).isEqualTo("01");
        assertThat(id.getCategoryCd()).isEqualTo(CANONICAL_CATEGORY);
    }

    /** A category code missing its leading zeros is refused rather than padded. */
    @Test
    @DisplayName("refuse a category code that lost its leading zeros")
    void refusesACategoryCodeMissingItsLeadingZeros() {
        assertThatThrownBy(() -> new TransactionCategoryBalanceId(ACCOUNT_ID, "01", "5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("categoryCd")
                .hasMessageContaining("exactly "
                        + TransactionCategoryBalanceId.CATEGORY_CD_WIDTH);
    }

    /** A category code wider than its column is refused. */
    @Test
    @DisplayName("refuse a category code wider than its column")
    void refusesAnOverWideCategoryCode() {
        assertThatThrownBy(() -> new TransactionCategoryBalanceId(ACCOUNT_ID, "01", "00005"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A non-digit category code is refused, because the source picture is numeric and zero-filled. */
    @Test
    @DisplayName("refuse a non-digit category code")
    void refusesANonDigitCategoryCode() {
        assertThatThrownBy(() -> new TransactionCategoryBalanceId(ACCOUNT_ID, "01", "00 5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-digit");
    }

    /** A type code of the wrong width is refused, and digits are not required of it. */
    @Test
    @DisplayName("refuse a type code of the wrong width while admitting non-digits")
    void refusesAWrongWidthTypeCodeButAdmitsNonDigits() {
        assertThatThrownBy(() -> new TransactionCategoryBalanceId(ACCOUNT_ID, "1", CANONICAL_CATEGORY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typeCd");

        // WHY : Assumptions: the type code's source picture is CHARACTER -- TRAN-TYPE-CD PIC X(02) --
        //       so a non-digit spelling is a legitimate value and must not be refused. The asymmetry
        //       with the adjacent numeric category code is the pair most likely to be made uniform by
        //       mistake, in either direction, which is why both halves are asserted here together.
        assertThat(new TransactionCategoryBalanceId(ACCOUNT_ID, "AB", CANONICAL_CATEGORY).getTypeCd())
                .isEqualTo("AB");
    }

    /** An absent component is refused, because equality and hashing are meaningless without it. */
    @Test
    @DisplayName("refuse an absent component")
    void refusesAnAbsentComponent() {
        assertThatThrownBy(() -> new TransactionCategoryBalanceId(null, "01", CANONICAL_CATEGORY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransactionCategoryBalanceId(ACCOUNT_ID, null, CANONICAL_CATEGORY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransactionCategoryBalanceId(ACCOUNT_ID, "01", null))
                .isInstanceOf(NullPointerException.class);
    }

    /** Two keys built from the same canonical components are equal and hash alike. */
    @Test
    @DisplayName("compare equal for the same canonical components")
    void comparesEqualForTheSameCanonicalComponents() {
        TransactionCategoryBalanceId one =
                new TransactionCategoryBalanceId(ACCOUNT_ID, "01", CANONICAL_CATEGORY);
        TransactionCategoryBalanceId other =
                new TransactionCategoryBalanceId(ACCOUNT_ID, "01", CANONICAL_CATEGORY);

        assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
    }
}
