package com.carddemo.reference.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.reference.domain.DisclosureGroup.DisclosureGroupId;
import com.carddemo.reference.domain.TransactionCategory.TransactionCategoryId;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Proves the two reference composite keys admit exactly one spelling of each component.
 *
 * <p>Purpose: both keys map to fixed-character columns, and a fixed-character comparison forgives a
 * value short on the RIGHT while doing nothing at all for one short on the LEFT. A category code of
 * {@code "5"} is therefore stored as {@code "5"} plus three blanks and compares as a DIFFERENT key
 * from {@code "0005"}. On the category table that would produce two rows for one logical category;
 * on the disclosure table the consequence is worse than a duplicate, because a rate lookup that
 * misses does not fail -- the interest program substitutes the {@code DEFAULT} group and carries on,
 * so a key spelled one character short accrues silently at the wrong rate and reports nothing.</p>
 *
 * <p>Assumptions: the guards under test live in the constructors of the two identity types, which a
 * persistence provider never calls, so a migration test that starts a database cannot reach them.
 * That is the reason these cases exist separately rather than as part of the mapping assertions.</p>
 */
@DisplayName("the reference composite keys")
class ReferenceKeyCanonicalityTest {

    /** The canonical two-character transaction type used throughout. */
    private static final String TYPE_CD = "01";

    /** The canonical four-digit category code used throughout. */
    private static final String CATEGORY_CD = "0005";

    /** The canonical ten-character account group, blank-padded exactly as the seed stores it. */
    private static final String DEFAULT_GROUP = "DEFAULT   ";

    /** Cases over {@code TransactionCategory} and its identity. */
    @Nested
    @DisplayName("on a transaction category")
    class OnATransactionCategory {

        /** A canonically spelled key is accepted and reports its components unchanged. */
        @Test
        @DisplayName("accept the canonical spelling and report it unchanged")
        void acceptsTheCanonicalSpelling() {
            TransactionCategoryId id = new TransactionCategoryId(TYPE_CD, CATEGORY_CD);

            assertThat(id.getTypeCd()).isEqualTo(TYPE_CD);
            assertThat(id.getCatCd()).isEqualTo(CATEGORY_CD);
        }

        /**
         * A category code short of its declared width is refused rather than blank-padded.
         *
         * <p>Assumptions: refusing is deliberately preferred to left-padding, because padding cannot
         * distinguish a careless caller from a value that lost its leading zeros somewhere upstream,
         * and silently repairing the second hides the defect that produced it.</p>
         */
        @Test
        @DisplayName("refuse a category code short of its declared width")
        void refusesAShortCategoryCode() {
            assertThatThrownBy(() -> new TransactionCategoryId(TYPE_CD, "5"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("catCd");
        }

        /** A category code carrying a non-digit is refused, because its source picture is numeric. */
        @Test
        @DisplayName("refuse a category code carrying a non-digit")
        void refusesANonDigitCategoryCode() {
            assertThatThrownBy(() -> new TransactionCategoryId(TYPE_CD, "00A5"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("catCd");
        }

        /**
         * A type code is width-checked but NOT digit-checked, and the asymmetry is intended.
         *
         * <p>Assumptions: the source pictures differ. {@code TRAN-TYPE PIC X(02)} is alphanumeric, so
         * a non-digit type code is a value the baseline can hold and this target must accept, whereas
         * {@code TRAN-CAT-CD PIC 9(04)} is numeric and zero-filled. Asserting both halves here keeps
         * a later reader from "tidying" the two guards into one.</p>
         */
        @Test
        @DisplayName("accept a non-numeric type code but refuse one of the wrong width")
        void checksTypeWidthWithoutRequiringDigits() {
            assertThat(new TransactionCategoryId("AB", CATEGORY_CD).getTypeCd()).isEqualTo("AB");

            assertThatThrownBy(() -> new TransactionCategoryId("1", CATEGORY_CD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("typeCd");
        }

        /** Two categories with one identity are equal however their descriptions differ. */
        @Test
        @DisplayName("compare by identity alone, ignoring the description")
        void comparesByIdentityAlone() {
            TransactionCategory first =
                    new TransactionCategory(new TransactionCategoryId(TYPE_CD, CATEGORY_CD), "one");
            TransactionCategory second =
                    new TransactionCategory(new TransactionCategoryId(TYPE_CD, CATEGORY_CD), "two");

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        /** A description wider than the column declares is refused at construction. */
        @Test
        @DisplayName("refuse a description wider than the column declares")
        void refusesAnOverwideDescription() {
            String tooLong = "x".repeat(TransactionCategory.DESCRIPTION_WIDTH + 1);

            assertThatThrownBy(() ->
                    new TransactionCategory(new TransactionCategoryId(TYPE_CD, CATEGORY_CD), tooLong))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("description");
        }

        /**
         * A newly built category begins at the version its column defaults to.
         *
         * <p>Assumptions: the counter is written by the provider rather than by a caller, so the only
         * value a constructed instance can carry is the zero the migration seeds every row with.</p>
         */
        @Test
        @DisplayName("begin at the version the column defaults to")
        void beginsAtTheDefaultVersion() {
            TransactionCategory category =
                    new TransactionCategory(new TransactionCategoryId(TYPE_CD, CATEGORY_CD), "one");

            assertThat(category.getVersion()).isZero();
        }
    }

    /** Cases over {@code DisclosureGroup} and its identity. */
    @Nested
    @DisplayName("on a disclosure group")
    class OnADisclosureGroup {

        /** A canonically spelled key is accepted and reports all three components unchanged. */
        @Test
        @DisplayName("accept the canonical spelling and report it unchanged")
        void acceptsTheCanonicalSpelling() {
            DisclosureGroupId id = new DisclosureGroupId(DEFAULT_GROUP, TYPE_CD, CATEGORY_CD);

            assertThat(id.getAcctGroupId()).isEqualTo(DEFAULT_GROUP);
            assertThat(id.getTranTypeCd()).isEqualTo(TYPE_CD);
            assertThat(id.getTranCatCd()).isEqualTo(CATEGORY_CD);
        }

        /**
         * The unpadded fallback literal is refused, because the key the baseline searches for is padded.
         *
         * <p>Assumptions: the interest program moves the seven-character literal {@code 'DEFAULT'}
         * into a ten-byte alphanumeric field, which left-justifies and space-fills it, so the key
         * actually searched for carries three trailing spaces -- and that is how the seed stores those
         * rows. Refusing the unpadded form here forces a caller to build the key the seed contains
         * instead of one that would silently find nothing.</p>
         */
        @Test
        @DisplayName("refuse the unpadded fallback literal")
        void refusesTheUnpaddedFallbackLiteral() {
            assertThatThrownBy(() -> new DisclosureGroupId("DEFAULT", TYPE_CD, CATEGORY_CD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("acctGroupId");
        }

        /** A category component short of its declared width is refused. */
        @Test
        @DisplayName("refuse a category component short of its declared width")
        void refusesAShortCategoryComponent() {
            assertThatThrownBy(() -> new DisclosureGroupId(DEFAULT_GROUP, TYPE_CD, "5"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tranCatCd");
        }

        /** Two groups with one identity are equal however their rates differ. */
        @Test
        @DisplayName("compare by identity alone, ignoring the rate")
        void comparesByIdentityAlone() {
            DisclosureGroupId id = new DisclosureGroupId(DEFAULT_GROUP, TYPE_CD, CATEGORY_CD);
            DisclosureGroup first = new DisclosureGroup(id, new BigDecimal("15.00"));
            DisclosureGroup second = new DisclosureGroup(
                    new DisclosureGroupId(DEFAULT_GROUP, TYPE_CD, CATEGORY_CD),
                    new BigDecimal("25.00"));

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        /**
         * A rate is carried as an exact decimal, preserving the scale it was given.
         *
         * <p>Assumptions: the assertion compares with {@code isEqualByComparingTo} for the value and
         * inspects the scale separately, because {@code BigDecimal.equals} treats a difference of
         * scale as a difference of value. Both properties matter: the rate multiplies a balance, so
         * the value must be exact, and the column declares two fractional digits.</p>
         */
        @Test
        @DisplayName("carry the rate as an exact decimal at the scale supplied")
        void carriesTheRateExactly() {
            DisclosureGroup group = new DisclosureGroup(
                    new DisclosureGroupId(DEFAULT_GROUP, TYPE_CD, CATEGORY_CD),
                    new BigDecimal("15.00"));

            assertThat(group.getInterestRate()).isEqualByComparingTo("15.00");
            assertThat(group.getInterestRate().scale()).isEqualTo(DisclosureGroup.INTEREST_RATE_SCALE);
        }
    }
}
