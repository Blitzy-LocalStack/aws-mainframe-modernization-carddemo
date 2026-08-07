package com.carddemo.batch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that every monetary member of this package refuses the three values its column cannot hold,
 * at the assignment that introduced them.
 *
 * <p>The columns are {@code ledger.transactions.amount},
 * {@code ledger.daily_transactions.amount} and
 * {@code ledger.transaction_category_balances.balance}, each declared {@code NUMERIC(11,2) NOT NULL}
 * in {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql}. The three
 * values that column cannot hold are an absent one, a magnitude needing ten integer digits, and a
 * scale finer than two.</p>
 *
 * <p>Assumptions: these need tests because all three used to reach the database. An earlier revision
 * of each mapping left the member nullable and assigned the argument verbatim, so a null was a
 * legal Java state, an over-scale value was coerced by the driver, and a ten-integer-digit magnitude
 * -- which {@code Money.of} admits, its own bound being the widest reference picture
 * {@code PIC S9(10)V99} -- failed at the provider as a numeric-field-overflow naming neither the
 * column nor the row. The reference fields are all {@code PIC S9(09)V99}, so nine is the real bound.</p>
 *
 * <p>Alternatives Considered: asserting the constraint against a real database through the sibling
 * repository integration tests alone. Rejected as insufficient rather than wrong: those tests need a
 * container to run, and the property being asserted here is that the refusal happens BEFORE the row
 * reaches a database at all. A test that can only observe the provider's refusal cannot distinguish a
 * guarded mapping from an unguarded one over a constrained column.</p>
 */
@DisplayName("batch domain: a money member refuses what its NUMERIC(11,2) NOT NULL column cannot hold")
class MoneyColumnInvariantTest {

    /** The nine-integer-digit maximum the reference picture {@code PIC S9(09)V99} admits. */
    private static final BigDecimal WIDEST_ADMISSIBLE = new BigDecimal("999999999.99");

    /** One integer digit past that maximum, which {@code Money.of} would admit and the column would not. */
    private static final BigDecimal ONE_DIGIT_TOO_WIDE = new BigDecimal("1000000000.00");

    /**
     * The posted-transaction amount refuses an absent value and an over-wide one, and reduces scale.
     */
    @Test
    @DisplayName("the posted transaction amount is bounded at nine integer digits and scale two")
    void postedAmountIsBounded() {
        Transaction posted = new Transaction("0000000000000001");

        assertThatThrownBy(() -> posted.setAmount(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> posted.setAmount(ONE_DIGIT_TOO_WIDE))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("integer digits");

        posted.setAmount(new BigDecimal("1.234"));
        assertThat(posted.getAmount()).isEqualByComparingTo("1.23");
        assertThat(posted.getAmount().scale()).isEqualTo(2);

        posted.setAmount(WIDEST_ADMISSIBLE.negate());
        assertThat(posted.getAmount()).isEqualByComparingTo("-999999999.99");
    }

    /**
     * The daily-feed amount is bounded at the constructor, there being no mutator for it.
     *
     * <p>Assumptions: the feed row is immutable in its amount by design, so the constructor is the only
     * assignment there is and is therefore the only place the bound can be asserted.</p>
     */
    @Test
    @DisplayName("the daily feed amount is bounded at construction, its only assignment")
    void feedAmountIsBoundedAtConstruction() {
        assertThatThrownBy(() -> feedRowWithAmount(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> feedRowWithAmount(ONE_DIGIT_TOO_WIDE))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("integer digits");

        assertThat(feedRowWithAmount(new BigDecimal("1.235")).getAmount())
                .isEqualByComparingTo("1.24");
        assertThat(feedRowWithAmount(WIDEST_ADMISSIBLE).getAmount())
                .isEqualByComparingTo("999999999.99");
    }

    /**
     * The category balance is bounded on both of its assignments, and zero remains a value.
     *
     * <p>Assumptions: zero is asserted admissible alongside the refusals, because zero is a real stored
     * balance rather than an absent one -- {@code tests/golden/posting/reject_102_overlimit/tcatbal.expected}
     * carries a row whose balance is exactly zero -- and a guard that rejected it would be worse than the
     * nullability it replaced.</p>
     */
    @Test
    @DisplayName("the category balance is bounded on both assignments and admits zero")
    void categoryBalanceIsBounded() {
        TransactionCategoryBalance.TransactionCategoryBalanceId id =
                new TransactionCategoryBalance.TransactionCategoryBalanceId(7L, "01", "0001");

        assertThatThrownBy(() -> new TransactionCategoryBalance(id, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransactionCategoryBalance(id, ONE_DIGIT_TOO_WIDE))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("integer digits");

        TransactionCategoryBalance row = new TransactionCategoryBalance(id, new BigDecimal("0"));
        assertThat(row.getBalance()).isEqualByComparingTo("0.00");
        assertThat(row.getBalance().scale()).isEqualTo(2);

        assertThatThrownBy(() -> row.setBalance(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> row.setBalance(ONE_DIGIT_TOO_WIDE.negate()))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("integer digits");

        row.setBalance(WIDEST_ADMISSIBLE);
        assertThat(row.getBalance()).isEqualByComparingTo("999999999.99");
    }

    /**
     * Builds one daily-feed row carrying a supplied amount and otherwise-valid members.
     *
     * @param amount the amount to place on the row, which may be {@code null} so that the refusal of an
     *     absent amount is expressible
     * @return the constructed row, never {@code null}
     */
    private static DailyTransaction feedRowWithAmount(BigDecimal amount) {
        return new DailyTransaction("0000000000000001", "01", "0001", "POS", "TEST ROW", amount,
                123_456_789L, "MERCHANT", "CITY", "00000", "4111111111111111",
                LocalDateTime.parse("2022-07-18T10:00:00"),
                LocalDateTime.parse("2022-07-18T10:00:01"));
    }
}
