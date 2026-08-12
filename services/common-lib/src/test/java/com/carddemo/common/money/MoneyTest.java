package com.carddemo.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies money arithmetic, posting boundaries, and Jackson wire behavior against CardDemo
 * source evidence.
 *
 * <p>This test container accepts no parameters, produces no return value, and declares no
 * exceptions. Individual test methods capture expected failures instead of allowing them to
 * escape.
 *
 * <p>The normative arithmetic sources are {@code app/cbl/CBACT04C.cbl:415-468},
 * {@code app/cbl/CBTRN02C.cbl:393-420}, and the signed monetary pictures in
 * {@code app/cpy/CVACT01Y.cpy}, {@code CVTRA01Y.cpy}, {@code CVTRA02Y.cpy}, and
 * {@code CVTRA05Y.cpy}. Fixture values are quoted directly from {@code tests/fixtures/interest}
 * and {@code tests/fixtures/posting}; no fixture resource is copied into this module.
 *
 * <p><strong>Disclosure lookup contract mismatch:</strong> {@code CBACT04C.cbl:422} accepts
 * initial status {@code 00} or {@code 23}, lines 436-439 retry status {@code 23} with
 * {@code DEFAULT   }, and lines 443-458 accept only {@code 00} from that second read. Neither
 * {@link Money} nor {@link MoneyModule} exposes a lookup, key, or file-status API, so reproducing
 * those transitions here would create the prohibited test-only lookup algorithm. This class
 * reports that gap, pins the fixture's ten-character key shape, and exercises only the authored
 * rate-side outcomes; the table-owning service must verify the read transitions.
 *
 * <p><strong>Posting decision contract mismatch:</strong> {@link Money} exposes exact arithmetic,
 * {@link Money#isWithinLimit(Money)}, and {@link Money#exceeds(Money)}, but it exposes no
 * date-bearing decision, reject code, reject description, or result object. Consequently this class
 * can prove the three-term projected balance and inclusive monetary boundary, but it cannot assert
 * expiration, the sequential 103-over-102 overwrite, or the four reason descriptions through a real
 * production result API. {@code CBTRN02C.cbl:385-419} owns 100 /
 * {@code INVALID CARD NUMBER FOUND}, 101 / {@code ACCOUNT RECORD NOT FOUND}, 102 /
 * {@code OVERLIMIT TRANSACTION}, and 103 /
 * {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}; common-lib exposes none as a constant.
 * Duplicating those policies as test constants or an {@code if/else-if} decision would violate the
 * required source-of-truth boundary; the date and result owners must test them where their authored
 * APIs live.
 */
final class MoneyTest {

    /**
     * Verifies symmetric HALF_UP normalization for general positive and negative midpoint
     * amounts.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because both inputs are within the documented money domain.
     */
    @Test
    @DisplayName("general normalization uses HALF_UP on both sides of zero")
    void normalizesGeneralAmountsWithHalfUpAtMidpoints() {
        // WHY : Assumptions: Signed COBOL money pictures make both signs part of the domain, so
        //       a positive-only vector would leave the negative HALF_UP contract unproved.
        Money positive = Money.of(new BigDecimal("1.005"));
        Money negative = Money.of(new BigDecimal("-1.005"));

        assertThat(positive.toPlainString()).isEqualTo("1.01");
        assertThat(negative.toPlainString()).isEqualTo("-1.01");
    }

    /**
     * Verifies the fixture-backed monthly-interest sanity result after the DEFAULT rate resolves.
     *
     * <p>The test accepts no parameters and returns normally after its void assertion. It expects
     * no exception because the balance and resolved rate are valid fixed-point values.
     */
    @Test
    @DisplayName("resolved DEFAULT rate yields the fixture sanity interest")
    void calculatesFixtureInterestAtResolvedDefaultRate() {
        Money balance = Money.of("1000.00");
        BigDecimal resolvedRate = new BigDecimal("15.00");

        assertThat(balance.monthlyInterest(resolvedRate))
                .isEqualTo(Money.of("12.50"));
    }

    /**
     * Verifies the exact padded group-key bytes carried by the DEFAULT fallback fixture.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because it slices a known 50-character fixture record at valid
     * boundaries. This pins evidence only; the absent common-lib lookup API is reported in the
     * class documentation.
     */
    @Test
    @DisplayName("DEFAULT fallback group key is exactly ten characters")
    void pinsDefaultFallbackGroupKeyContentAndWidth() {
        String fixtureRecord = "DEFAULT   01000100150{0000000000000000000000000000";

        // WHY : Assumptions: CBACT04C.cbl:436-439 moves DEFAULT into the CVTRA02Y X(10) field;
        //       quoting discgrp.txt:1 pins its padding without inventing a lookup implementation.
        String fallbackGroupKey = fixtureRecord.substring(0, 10);

        assertThat(fallbackGroupKey).isEqualTo("DEFAULT   ");
        assertThat(fallbackGroupKey).hasSize(10);
    }

    /**
     * Verifies that a zero fixture balance produces zero interest with a present nonzero rate.
     *
     * <p>The test accepts no parameters and returns normally after its void assertion. It expects
     * no exception because both fixture values are present and inside the money domain.
     */
    @Test
    @DisplayName("zero balance at a live fixture rate yields zero interest")
    void calculatesZeroInterestForZeroBalanceAtLiveRate() {
        Money zeroBalance = Money.of("0.00");
        BigDecimal liveRate = new BigDecimal("15.00");

        assertThat(zeroBalance.monthlyInterest(liveRate))
                .isEqualTo(Money.ZERO);
    }

    /**
     * Verifies that a present zero-rate fixture row is valid data rather than a missing lookup.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because the resolved rate is present and valid even though its value
     * is zero.
     */
    @Test
    @DisplayName("present zero rate remains distinct from a missing DEFAULT row")
    void acceptsPresentZeroRateAsSuccessfulResolution() {
        Money balance = Money.of("1000.00");
        BigDecimal presentZeroRate = new BigDecimal("0.00");

        assertThat(presentZeroRate).isEqualByComparingTo("0.00");
        assertThat(balance.monthlyInterest(presentZeroRate))
                .isEqualTo(Money.ZERO);
    }

    /**
     * Verifies the synthetic missing-default equivalent rejects an absent resolved rate.
     *
     * <p>The test accepts no parameters and returns normally after capturing the expected exact
     * {@link NullPointerException} from the assertion. The exception does not escape the test
     * method. This case is synthetic because every DEFAULT fallback fixture contains its second
     * read row.
     */
    @Test
    @DisplayName("synthetic missing DEFAULT row never becomes a silent zero rate")
    void rejectsSyntheticMissingDefaultRate() {
        Money balance = Money.of("1000.00");

        // WHY : Assumptions: CBACT04C.cbl:422 and 436-458 require the second read to return 00;
        //       default_fallback/discgrp.txt contains all 17 rows, so this null case is synthetic.
        NullPointerException failure = assertThrows(
                NullPointerException.class,
                () -> balance.monthlyInterest(null));

        assertThat(failure).isExactlyInstanceOf(NullPointerException.class);
    }

    /**
     * Verifies the two rates on which half up and the reference truncation cannot be told apart.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because all balances and rates are valid monetary inputs.
     *
     * <p>Alternatives Considered: using either of these rates to evidence the rounding contract.
     * Rejected, and recorded here so that nobody adopts one for that purpose: their quotients do not
     * land on a half cent, so half up and truncation agree and the vector proves nothing about which
     * mode ran. The two vectors that DO discriminate are asserted separately below.
     */
    @Test
    @DisplayName("rates whose quotient misses a half cent cannot evidence the rounding contract")
    void documentsRatesThatCannotEvidenceTheRoundingContract() {
        Money balance = Money.of("1000.00");
        BigDecimal fixtureRate = new BigDecimal("25.00");
        BigDecimal syntheticRate = new BigDecimal("2.50");

        assertThat(balance.monthlyInterest(fixtureRate)).isEqualTo(Money.of("20.83"));
        assertThat(balance.monthlyInterest(syntheticRate)).isEqualTo(Money.of("2.08"));
    }

    /**
     * Verifies the first vector on which half up parts company with the reference truncation.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because the source-derived balance and synthetic discriminating rate
     * are valid inputs.
     *
     * <p>Assumptions: the quotient of this vector is {@code 2.2583...}, so the reference program's
     * truncating store gives {@code 2.25} while half up would give {@code 2.26}. Three things are
     * asserted rather than one: that the API produces the reference value, that independently computed
     * reference arithmetic produces the same value, and that the half-up counterfactual produces a
     * DIFFERENT value. The third assertion is what makes this vector evidence -- without it the test
     * would pass under either mode and prove nothing about which one ran.
     *
     * <p>Assumptions: this expectation is the load-bearing statement of the accrual's mode. It demands
     * the value the reference field receives, so an implementation reaching for the general half-up mode
     * fails here rather than shipping a cent of drift.
     */
    @Test
    @DisplayName("1000.00 at 2.71 truncates to 2.25 as the reference does, where half up would give 2.26")
    void truncatesFirstDivergentInterestVectorAsReferenceDoes() {
        Money balance = Money.of("1000.00");
        BigDecimal rate = new BigDecimal("2.71");
        Money production = balance.monthlyInterest(rate);
        BigDecimal referenceTruncation = balance.amount().multiply(rate)
                .divide(Money.MONTHLY_RATE_DIVISOR, Money.SCALE, RoundingMode.DOWN);
        BigDecimal halfUpCounterfactual = balance.amount().multiply(rate)
                .divide(Money.MONTHLY_RATE_DIVISOR, Money.SCALE, RoundingMode.HALF_UP);

        assertThat(production).isEqualTo(Money.of("2.25"));
        assertThat(referenceTruncation).isEqualByComparingTo("2.25");
        assertThat(production.amount()).isEqualByComparingTo(referenceTruncation);
        assertThat(halfUpCounterfactual).isEqualByComparingTo("2.26");
        assertThat(production.amount()).isNotEqualByComparingTo(halfUpCounterfactual);
    }

    /**
     * Verifies the second, independent vector on which half up parts company with truncation.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because the synthetic balance-and-rate combination remains within the
     * fixed-point domain.
     *
     * <p>Assumptions: this vector's quotient is {@code 2.0850} EXACTLY rather than a repeating
     * expansion, which is the cleanest possible statement of the contract -- there is no truncated
     * tail to argue about, only a half cent resolved in one direction or the other. It is the one
     * vector on which the two modes cannot both be right, so it is the vector this contract is pinned
     * to. Two independent vectors are asserted because a single one could be satisfied by an
     * implementation that happened to be right at one input.
     *
     * <p>Assumptions: the required value is {@code 2.08}, which is what the reference statement stores.
     * The accrual is one of the business rules the reference suite asserts verbatim, so a cent of drift
     * in it is a parity failure rather than a rounding preference -- which is why this vector requires
     * the truncated value and not the half-up one.
     */
    @Test
    @DisplayName("1000.80 at 2.50 truncates the exact half cent to 2.08 as the reference does")
    void truncatesSecondDivergentInterestVectorAsReferenceDoes() {
        Money balance = Money.of("1000.80");
        BigDecimal rate = new BigDecimal("2.50");
        Money production = balance.monthlyInterest(rate);
        BigDecimal exactQuotient = balance.amount().multiply(rate)
                .divide(Money.MONTHLY_RATE_DIVISOR, 4, RoundingMode.UNNECESSARY);
        BigDecimal referenceTruncation = balance.amount().multiply(rate)
                .divide(Money.MONTHLY_RATE_DIVISOR, Money.SCALE, RoundingMode.DOWN);
        BigDecimal halfUpCounterfactual = balance.amount().multiply(rate)
                .divide(Money.MONTHLY_RATE_DIVISOR, Money.SCALE, RoundingMode.HALF_UP);

        // Assumptions: UNNECESSARY is used deliberately and would THROW if the quotient were not
        //   exact at four places. That makes the "2.0850 exactly" claim in this test's own
        //   documentation an assertion rather than a comment, so a future edit to the balance or the
        //   rate cannot quietly turn this into an ordinary repeating vector that proves less.
        assertThat(exactQuotient).isEqualByComparingTo("2.0850");
        assertThat(production).isEqualTo(Money.of("2.08"));
        assertThat(referenceTruncation).isEqualByComparingTo("2.08");
        assertThat(production.amount()).isEqualByComparingTo(referenceTruncation);
        assertThat(halfUpCounterfactual).isEqualByComparingTo("2.09");
        assertThat(production.amount()).isNotEqualByComparingTo(halfUpCounterfactual);
    }

    /**
     * Verifies that a negative accrual truncates toward zero rather than away from it.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because the synthetic signed balance is permitted by the source
     * copybooks and remains inside the money domain.
     *
     * <p>Assumptions: a negative accrual is reachable rather than hypothetical, because both the
     * balance picture {@code PIC S9(10)V99} at line 7 of {@code app/cpy/CVACT01Y.cpy} and the rate
     * picture {@code PIC S9(04)V99} at line 9 of {@code app/cpy/CVTRA02Y.cpy} are SIGNED.
     *
     * <p>Assumptions: the direction of the two modes diverges on a negative value and this is the
     * assertion that pins which one is in force. {@link java.math.RoundingMode#DOWN} truncates toward
     * zero, so the negative mirror of the {@code 2.25} vector is {@code -2.25}, whereas half up rounds
     * on magnitude and would give {@code -2.26}. Asserting only the positive vector would leave a
     * mode that rounded away from zero on negatives indistinguishable from this one.
     *
     * <p>Assumptions: the sign case is the sharper of the two directions to state, because "truncates
     * toward zero" and "rounds toward negative infinity" agree on every positive input and part company
     * here -- which is why the mode is {@code DOWN} and not {@code FLOOR}, and why this vector requires
     * {@code -2.25}.
     */
    @Test
    @DisplayName("a negative accrual truncates toward zero, not away from it")
    void truncatesNegativeInterestTowardZero() {
        Money balance = Money.of("-1000.00");
        BigDecimal rate = new BigDecimal("2.71");

        Money production = balance.monthlyInterest(rate);
        BigDecimal referenceTruncation = balance.amount().multiply(rate)
                .divide(Money.MONTHLY_RATE_DIVISOR, Money.SCALE, RoundingMode.DOWN);
        BigDecimal halfUpCounterfactual = balance.amount().multiply(rate)
                .divide(Money.MONTHLY_RATE_DIVISOR, Money.SCALE, RoundingMode.HALF_UP);
        BigDecimal floorCounterfactual = balance.amount().multiply(rate)
                .divide(Money.MONTHLY_RATE_DIVISOR, Money.SCALE, RoundingMode.FLOOR);

        assertThat(production).isEqualTo(Money.of("-2.25"));
        assertThat(referenceTruncation).isEqualByComparingTo("-2.25");
        assertThat(halfUpCounterfactual).isEqualByComparingTo("-2.26");
        assertThat(floorCounterfactual).isEqualByComparingTo("-2.26");
        assertThat(production.amount()).isNotEqualByComparingTo(halfUpCounterfactual);
        assertThat(production.amount()).isNotEqualByComparingTo(floorCounterfactual);
    }

    /**
     * Verifies that monthly interest multiplies at full precision before performing its one
     * scaled division.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because the divisor is nonzero and all operands are valid.
     */
    @Test
    @DisplayName("interest preserves multiply-before-divide arithmetic order")
    void multipliesBeforeDividingInterest() {
        Money balance = Money.of("1000.00");
        BigDecimal rate = new BigDecimal("2.71");
        Money production = balance.monthlyInterest(rate);

        // Assumptions: CBACT04C.cbl:465 parenthesizes the product, and the scale-two
        //   CVTRA01Y/CVTRA02Y operands form a scale-four raw product before division. The
        //   counterfactual uses the SAME rounding mode as production, so the cent it differs by is
        //   attributable to the operation order alone and not to the mode.
        // WHY : Refactoring Rationale: the mode named here is BASELINE_INTEREST_ROUNDING and was
        //   GENERAL_ROUNDING. It had to change with the accrual's mode, and not merely for tidiness:
        //   with production truncating and the counterfactual rounding half up, both orders reach 2.25
        //   at this vector and the final assertion would have passed for the wrong reason -- the two
        //   values agreeing while the test claimed they differed. Holding the mode equal on both sides
        //   is the whole design of this test.
        BigDecimal divideFirstCounterfactual = balance.amount()
                .divide(Money.MONTHLY_RATE_DIVISOR, Money.SCALE, Money.BASELINE_INTEREST_ROUNDING)
                .multiply(rate)
                .setScale(Money.SCALE, Money.BASELINE_INTEREST_ROUNDING);

        assertThat(production).isEqualTo(Money.of("2.25"));
        assertThat(divideFirstCounterfactual).isEqualByComparingTo("2.24");
        assertThat(production.amount()).isNotEqualByComparingTo(divideFirstCounterfactual);
    }

    /**
     * Verifies that each category's interest is reduced before the reduced values are
     * accumulated.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because all three synthetic items and their aggregate remain inside the
     * money domain.
     */
    @Test
    @DisplayName("per-item reduction precedes interest accumulation")
    void reducesEachInterestItemBeforeAccumulation() {
        Money balance = Money.of("1000.00");
        BigDecimal rate = new BigDecimal("1.00");
        Money reducedItem = balance.monthlyInterest(rate);

        // Trade-offs: CBACT04C.cbl:467 adds WS-MONTHLY-INT after its scale-two receive, so each
        //   category's accrual is reduced BEFORE it joins the running total. The 1.00 rate is kept
        //   because its quotient, 0.8333..., loses a third of a cent per term, so three reduced terms
        //   fall a cent short of the once-reduced product and the order is visible in the result.
        // WHY : Assumptions: 2.71 would discriminate here too -- under truncation it gives 2.25 per term
        //   for 6.75, against 6.77 for the once-reduced product -- so the choice of 1.00 is about the
        //   size of the gap rather than about whether one exists. The third-of-a-cent loss per term is
        //   the clearest available demonstration of the reduction point.
        // WHY : Assumptions: the counterfactual's mode is BASELINE_INTEREST_ROUNDING, matching
        //   production, so the cent between the two is attributable to the reduction POINT alone. At
        //   this vector the quotient 2.5 is exact and every mode agrees on it, so the choice changes
        //   no value here; it is made explicit anyway so the test does not depend on that coincidence.
        Money perItemTotal = Money.total(reducedItem, reducedItem, reducedItem);
        BigDecimal reduceOnceCounterfactual = balance.amount()
                .multiply(new BigDecimal("3"))
                .multiply(rate)
                .divide(Money.MONTHLY_RATE_DIVISOR, Money.SCALE, Money.BASELINE_INTEREST_ROUNDING);

        assertThat(reducedItem).isEqualTo(Money.of("0.83"));
        assertThat(perItemTotal).isEqualTo(Money.of("2.49"));
        assertThat(reduceOnceCounterfactual).isEqualByComparingTo("2.50");
        assertThat(perItemTotal.amount()).isNotEqualByComparingTo(reduceOnceCounterfactual);
    }

    /**
     * Verifies that projected balance contains only cycle credit, cycle debit, and transaction
     * amount.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because every fixture value is a valid Money amount.
     */
    @Test
    @DisplayName("projected balance excludes the account current balance")
    void calculatesProjectedBalanceFromExactlyThreeTerms() {
        Money currentBalance = Money.of("193.00");
        Money cycleCredit = Money.of("0.00");
        Money cycleDebit = Money.of("0.00");
        Money transactionAmount = Money.of("2065.00");

        // WHY : Assumptions: CBTRN02C.cbl:403-405 names only cycle credit, cycle debit, and amount;
        //       including the deliberately different 193.00 value would expose a 2258.00 result.
        Money projectedBalance = cycleCredit.minus(cycleDebit).plus(transactionAmount);
        Money currentBalanceCounterfactual = currentBalance.plus(transactionAmount);

        assertThat(projectedBalance).isEqualTo(Money.of("2065.00"));
        assertThat(currentBalanceCounterfactual).isEqualTo(Money.of("2258.00"));
        assertThat(projectedBalance).isNotEqualTo(currentBalanceCounterfactual);
    }

    /**
     * Verifies that the exact-limit posting fixture is accepted by the inclusive money predicate.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because the projected balance and credit limit are valid Money values.
     */
    @Test
    @DisplayName("projected balance exactly at the credit limit is accepted")
    void acceptsProjectedBalanceAtInclusiveCreditLimit() {
        Money cycleCredit = Money.of("0.00");
        Money cycleDebit = Money.of("0.00");
        Money transactionAmount = Money.of("2065.00");
        Money creditLimit = Money.of("2065.00");
        Money projectedBalance = cycleCredit.minus(cycleDebit).plus(transactionAmount);

        // WHY : Alternatives Considered: A value well below the limit would also pass but could
        //       not distinguish CBTRN02C.cbl:407 inclusive >= from an exclusive comparison.
        assertThat(projectedBalance.isWithinLimit(creditLimit)).isTrue();
        assertThat(projectedBalance.exceeds(creditLimit)).isFalse();
    }

    /**
     * Verifies that the one-cent-over fixture crosses the strict rejection side of the money
     * boundary.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because the projected balance and credit limit are valid Money values.
     * The boolean predicate is not a reject-result object and therefore supplies no reason text.
     */
    @Test
    @DisplayName("projected balance one cent over the credit limit is rejected")
    void rejectsProjectedBalanceOneCentOverCreditLimit() {
        Money cycleCredit = Money.of("0.00");
        Money cycleDebit = Money.of("0.00");
        Money transactionAmount = Money.of("2065.01");
        Money creditLimit = Money.of("2065.00");
        Money projectedBalance = cycleCredit.minus(cycleDebit).plus(transactionAmount);

        // WHY : Assumptions: CBTRN02C.cbl:407-420 evaluates expiration after over-limit and lets
        //       103 overwrite 102; treating this boolean as a final reason or if/else-if is invalid.
        assertThat(projectedBalance.isWithinLimit(creditLimit)).isFalse();
        assertThat(projectedBalance.exceeds(creditLimit)).isTrue();
    }

    /**
     * Verifies raw JSON remains a quoted scale-two string for canonical and normalized inputs.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because every value is inside the Money domain and the module is
     * registered directly.
     */
    @Test
    @DisplayName("MoneyModule writes quoted scale-two JSON before any reparse")
    void writesQuotedScaleTwoWireValuesWithoutReparse() {
        ObjectMapper objectMapper = mapper();

        // WHY : Alternatives Considered: Reparsing first would hide a JSON number-versus-string
        //       defect because both token forms could then become the same in-memory amount.
        String canonicalJson = objectMapper.writeValueAsString(Money.of("1234.56"));
        String zeroJson = objectMapper.writeValueAsString(Money.of(new BigDecimal("0")));
        String oneDecimalJson = objectMapper.writeValueAsString(Money.of(new BigDecimal("1.5")));

        assertThat(canonicalJson).isEqualTo("\"1234.56\"");
        assertThat(zeroJson).isEqualTo("\"0.00\"");
        assertThat(oneDecimalJson).isEqualTo("\"1.50\"");
    }

    /**
     * Verifies exponent-capable input is rendered as plain decimal and a negative sign is retained.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because both inputs normalize within the Money magnitude boundary.
     */
    @Test
    @DisplayName("MoneyModule writes plain decimal text and preserves the negative sign")
    void writesPlainDecimalAndPreservesNegativeSign() {
        ObjectMapper objectMapper = mapper();

        String exponentInputJson =
                objectMapper.writeValueAsString(Money.of(new BigDecimal("1E+3")));
        String negativeJson = objectMapper.writeValueAsString(Money.of("-1234.56"));

        assertThat(exponentInputJson).isEqualTo("\"1000.00\"");
        assertThat(exponentInputJson).doesNotContain("E", "e");
        assertThat(negativeJson).isEqualTo("\"-1234.56\"");
    }

    /**
     * Verifies signed cent and magnitude boundaries round-trip with numeric equality and scale two.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because every listed amount is within the documented Money boundary.
     */
    @Test
    @DisplayName("MoneyModule round-trips boundary amounts with exact scale two")
    void roundTripsBoundaryValuesWithScaleTwo() {
        ObjectMapper objectMapper = mapper();
        List<String> values = List.of(
                "0.00",
                "0.01",
                "-0.01",
                "9999999999.99",
                "-9999999999.99");

        for (String value : values) {
            Money original = Money.of(value);
            String json = objectMapper.writeValueAsString(original);
            Money roundTripped = objectMapper.readValue(json, Money.class);

            assertThat(roundTripped.amount())
                    .as("numeric round-trip for %s", value)
                    .isEqualByComparingTo(original.amount());
            assertThat(roundTripped.amount().scale())
                    .as("scale for %s", value)
                    .isEqualTo(Money.SCALE);
        }
    }

    /**
     * Verifies the documented policy rejects an unquoted JSON numeric token.
     *
     * <p>The test accepts no parameters and returns normally after capturing the expected exact
     * {@link MismatchedInputException}. The exception remains inside the assertion and does not
     * escape the test method.
     */
    @Test
    @DisplayName("MoneyModule rejects JSON numeric tokens with MismatchedInputException")
    void rejectsUnquotedJsonNumericToken() {
        ObjectMapper objectMapper = mapper();

        // WHY : Alternatives Considered: Accepting a numeric token cannot reveal whether its
        //       producer already reduced the amount, so MoneyModule requires plain decimal text.
        MismatchedInputException failure = assertThrows(
                MismatchedInputException.class,
                () -> objectMapper.readValue("1234.56", Money.class));

        assertThat(failure)
                .isExactlyInstanceOf(MismatchedInputException.class)
                .hasMessageContaining("Cannot read a monetary amount from a JSON number")
                .hasMessageContaining("JSON string");
    }

    /**
     * Verifies the maximum positive JSON payload preserves the edited-display transport width.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because the value is exactly the documented positive Money boundary.
     */
    @Test
    @DisplayName("maximum positive payload retains PIC +9(10).99 width parity")
    void preservesFullEditedDisplayWidth() {
        ObjectMapper objectMapper = mapper();
        String json = objectMapper.writeValueAsString(Money.of("9999999999.99"));

        // WHY : Assumptions: CCPAURQY.cpy:27 and CCPAURLY.cpy:24 define one sign, ten integer
        //       digits, one point, and two fractional digits; JSON omits only the positive sign.
        String payload = json.substring(1, json.length() - 1);
        String editedDisplay = "+" + payload;

        assertThat(json).isEqualTo("\"9999999999.99\"");
        assertThat(payload).matches("[0-9]{10}\\.[0-9]{2}");
        assertThat(editedDisplay).isEqualTo("+9999999999.99");
        assertThat(editedDisplay).hasSize(14);
    }

    /**
     * Verifies the text factory refuses an over-length amount before it reaches a parser.
     *
     * <p>The test accepts no parameters and returns normally after capturing the expected
     * {@link NumberFormatException}. The exception stays inside the assertion and does not escape.
     *
     * <p>Assumptions: the refusal is bounded by {@link Money#MAX_INPUT_LENGTH} rather than by the
     * domain bound, and the ceiling is checked first because the order is what makes it useful. The
     * exact decimal type's own text constructor accepts a digit count limited only by the length of
     * the text, so a caller-supplied string is the one input to this type whose cost is unbounded;
     * measuring its length costs one comparison and happens before any digit is examined. The vector
     * is one character over the ceiling rather than a large multiple of it, because the boundary is
     * what a wrong comparison operator moves and a long vector would pass under either.
     */
    @Test
    @DisplayName("text longer than MAX_INPUT_LENGTH is refused before parsing")
    void rejectsMonetaryTextLongerThanTheDeclaredInputCeiling() {
        String oneOverTheCeiling = "1".repeat(Money.MAX_INPUT_LENGTH + 1);

        NumberFormatException failure = assertThrows(
                NumberFormatException.class,
                () -> Money.of(oneOverTheCeiling));

        assertThat(failure)
                .isExactlyInstanceOf(NumberFormatException.class)
                .hasMessageContaining("monetary text is 33 characters")
                .hasMessageContaining("exceeding the " + Money.MAX_INPUT_LENGTH);

        // WHY : Assumptions: text of exactly the ceiling length must be admitted, so the refusal above
        //       is a boundary rather than a blanket rejection of long text. The control has to satisfy
        //       the grammar and the domain as well as the length, which is why it is padded with zeros
        //       on both sides of the point rather than being 32 digits: 32 significant digits would be
        //       refused for magnitude and 32 fractional digits for scale, so either would pass this
        //       assertion for the wrong reason. Zeros make the value itself trivial and leave the
        //       LENGTH as the only property under test. It is composed from the two constants so it
        //       tracks them if either moves.
        String atTheCeiling = "0".repeat(Money.MAX_INPUT_LENGTH - Money.MAX_INPUT_SCALE - 1)
                + "." + "0".repeat(Money.MAX_INPUT_SCALE);
        assertThat(atTheCeiling).hasSize(Money.MAX_INPUT_LENGTH);
        assertThat(Money.of(atTheCeiling)).isEqualTo(Money.ZERO);
    }

    /**
     * Verifies the text factory refuses exponent notation rather than expanding it.
     *
     * <p>The test accepts no parameters and returns normally after capturing the expected
     * {@link NumberFormatException}. The exception stays inside the assertion and does not escape.
     *
     * <p>Assumptions: {@code 1.2E3} is a value the exact decimal type would accept and expand to
     * 1200, so this refusal is a deliberate narrowing of that type rather than a syntax error being
     * reported. Two properties make the narrowing necessary. A few characters of exponent can declare
     * an arbitrary number of digit positions, so admitting the notation would reintroduce through the
     * grammar exactly the unbounded cost the length ceiling removes. And the wire form of this
     * contract is a plain decimal string, so a producer emitting an exponent has a defect that is
     * cheaper to report here than to discover as a balance three services away.
     */
    @Test
    @DisplayName("exponent notation is refused by the plain-decimal grammar")
    void rejectsExponentNotationBecauseTheGrammarAdmitsPlainDecimalOnly() {
        NumberFormatException failure = assertThrows(
                NumberFormatException.class,
                () -> Money.of("1.2E3"));

        assertThat(failure)
                .isExactlyInstanceOf(NumberFormatException.class)
                .hasMessageContaining("not a plain decimal amount")
                .hasMessageContaining("with no exponent");

        // WHY : Assumptions: the plainly written equivalent of the refused text must be admitted,
        //       because naming the accepted spelling of the same quantity is what shows the refusal is
        //       about notation and not about the value, so a reader cannot mistake this for a domain
        //       bound.
        assertThat(Money.of("1200.00").toPlainString()).isEqualTo("1200.00");
    }

    /**
     * Verifies the text factory admits exactly MAX_INPUT_SCALE fractional digits and no more.
     *
     * <p>The test accepts no parameters and returns normally after capturing the expected
     * {@link NumberFormatException}. The exception stays inside the assertion and does not escape.
     *
     * <p>Assumptions: both vectors are built from {@link Money#MAX_INPUT_SCALE} rather than from the
     * literal fifteen, so the pair remains a boundary if the constant ever moves. The admitted side
     * is asserted alongside the refused one because a fractional input is REDUCED to
     * {@link Money#SCALE} rather than rejected for carrying more than two places, and a test that
     * showed only the refusal would read as though any third decimal place were an error.
     */
    @Test
    @DisplayName("more fractional digits than MAX_INPUT_SCALE are refused, that many are reduced")
    void rejectsMoreFractionalDigitsThanTheInputGrammarAdmits() {
        String oneFractionalDigitTooMany = "0." + "1".repeat(Money.MAX_INPUT_SCALE + 1);
        String theWidestAdmittedFraction = "0." + "1".repeat(Money.MAX_INPUT_SCALE);

        NumberFormatException failure = assertThrows(
                NumberFormatException.class,
                () -> Money.of(oneFractionalDigitTooMany));

        assertThat(failure)
                .isExactlyInstanceOf(NumberFormatException.class)
                .hasMessageContaining("at most " + Money.MAX_INPUT_SCALE + " digits after a single"
                        + " decimal point");

        // WHY : Assumptions: the widest admitted fraction must be reduced to cents instead of being
        //       refused. 0.111111111111111 reduces to 0.11 under HALF_UP because the first discarded
        //       digit is one, so this vector also shows the reduction is a rounding decision taken by
        //       the general contract rather than a truncation performed by the grammar.
        assertThat(Money.of(theWidestAdmittedFraction).toPlainString()).isEqualTo("0.11");
    }

    /**
     * Verifies an amount above the reference money domain is refused by both factories.
     *
     * <p>The test accepts no parameters and returns normally after capturing the two expected
     * {@link ArithmeticException} instances. Both stay inside their assertions and neither escapes.
     *
     * <p>Assumptions: the bound is {@link Money#MAX_MAGNITUDE}, which is the largest value the
     * twelve-byte zoned picture {@code ACCT-CURR-BAL PIC S9(10)V99} at line 7 of
     * {@code app/cpy/CVACT01Y.cpy} can hold. An amount one cent past it is not merely large, it is
     * unrepresentable in the record the migration has to write back, so admitting it would defer the
     * failure from a factory call to an encode that has already committed to a row.
     *
     * <p>Trade-offs: the refusal message reports the integer-digit COUNT and the bound, never the
     * offending amount. That is asserted here rather than assumed, because the value reaching this
     * guard is caller-supplied text and an exception message becomes a log line; a test that accepted
     * either message shape would let the value be reintroduced into the diagnostic silently.
     */
    @Test
    @DisplayName("magnitude past MAX_MAGNITUDE is refused with a digit count and no amount")
    void rejectsAmountsAboveTheReferenceMoneyDomain() {
        String oneCentPastTheDomain = "10000000000.00";

        ArithmeticException fromText = assertThrows(
                ArithmeticException.class,
                () -> Money.of(oneCentPastTheDomain));
        ArithmeticException fromDecimal = assertThrows(
                ArithmeticException.class,
                () -> Money.of(new BigDecimal(oneCentPastTheDomain)));

        for (ArithmeticException failure : List.of(fromText, fromDecimal)) {
            assertThat(failure)
                    .isExactlyInstanceOf(ArithmeticException.class)
                    .hasMessageContaining("an amount declaring 11 integer digits")
                    .hasMessageContaining("exceeds the reference money domain of "
                            + Money.MAX_MAGNITUDE.toPlainString());
            assertThat(failure).hasMessageNotContaining(oneCentPastTheDomain);
        }

        // WHY : Assumptions: the boundary value itself must be admitted and must render at scale two.
        //       The pair differs by one cent, which is what an inclusive comparison written exclusively
        //       would move, so the admitted side is what makes the refusal a boundary rather than an
        //       approximate limit.
        assertThat(Money.of(Money.MAX_MAGNITUDE).toPlainString()).isEqualTo("9999999999.99");
        assertThat(Money.of(Money.MAX_MAGNITUDE.negate()).toPlainString())
                .isEqualTo("-9999999999.99");
    }

    /**
     * Verifies declared scale and precision are bounded before any reduction is attempted.
     *
     * <p>The test accepts no parameters and returns normally after capturing the three expected
     * {@link ArithmeticException} instances. None escapes its assertion.
     *
     * <p>Assumptions: this guard is reachable only through {@link Money#of(BigDecimal)}, because the
     * text factory's grammar refuses these shapes first, so covering it needs a value constructed
     * directly rather than parsed. Three shapes are used because the guard has three clauses that a
     * partial condition would leave open: a scale above {@link Money#MAX_INPUT_SCALE}, a precision
     * above {@link Money#MAX_INPUT_PRECISION}, and a NEGATIVE scale, which is how exponent notation
     * arrives once it has already been parsed into a decimal and which no positive-only bound would
     * catch.
     *
     * <p>Trade-offs: the message reports the declared scale and precision and never the digits, and
     * that is asserted. The two numbers identify the defect -- a scale of nine figures came from a
     * mis-parsed exponent rather than from a record -- while the digits could be an unbounded
     * quantity of caller-chosen text heading for a log.
     */
    @Test
    @DisplayName("declared scale and precision are bounded before the value is reduced")
    void rejectsDeclaredScaleAndPrecisionBeforeAnyReduction() {
        BigDecimal scaleOnePastTheBound =
                new BigDecimal("0." + "1".repeat(Money.MAX_INPUT_SCALE + 1));
        BigDecimal precisionOnePastTheBound =
                new BigDecimal("1".repeat(Money.MAX_INPUT_PRECISION + 1));
        BigDecimal negativeScaleFromAnExponent = new BigDecimal("1E+30");

        ArithmeticException byScale = assertThrows(
                ArithmeticException.class,
                () -> Money.of(scaleOnePastTheBound));
        ArithmeticException byPrecision = assertThrows(
                ArithmeticException.class,
                () -> Money.of(precisionOnePastTheBound));
        ArithmeticException byNegativeScale = assertThrows(
                ArithmeticException.class,
                () -> Money.of(negativeScaleFromAnExponent));

        String admittedRange = "outside the admitted scale of at most " + Money.MAX_INPUT_SCALE
                + " and precision of at most " + Money.MAX_INPUT_PRECISION;

        assertThat(byScale)
                .isExactlyInstanceOf(ArithmeticException.class)
                .hasMessageContaining("value declares scale " + (Money.MAX_INPUT_SCALE + 1))
                .hasMessageContaining(admittedRange);
        assertThat(byPrecision)
                .isExactlyInstanceOf(ArithmeticException.class)
                .hasMessageContaining("precision " + (Money.MAX_INPUT_PRECISION + 1))
                .hasMessageContaining(admittedRange);
        assertThat(byNegativeScale)
                .isExactlyInstanceOf(ArithmeticException.class)
                .hasMessageContaining("value declares scale -30")
                .hasMessageContaining(admittedRange);

        // WHY : Assumptions: none of the three messages may carry the offending digits. The precision
        //       vector is twenty-eight ones, a string that would be unmistakable in a message, so its
        //       absence is checkable rather than merely intended.
        assertThat(byPrecision).hasMessageNotContaining(precisionOnePastTheBound.toPlainString());

        // WHY : Assumptions: a value sitting exactly ON both admitted bounds must clear this guard and
        //       then be refused by the DOMAIN guard instead, while a value inside both is simply
        //       reduced. The precision bound is deliberately looser than the domain, being
        //       MAX_INPUT_SCALE plus twelve, so no value can be refused BY precision while still
        //       fitting the ten integer digits of the reference picture -- a value at precision 27 with
        //       scale 15 necessarily carries twelve integer digits. Showing that such a value fails
        //       with the domain message rather than the scale-and-precision message is what proves the
        //       two guards are distinct and correctly ordered; a single merged condition would report
        //       the earlier message here and this assertion would catch it.
        BigDecimal onBothAdmittedBounds =
                new BigDecimal("1".repeat(12) + "." + "1".repeat(Money.MAX_INPUT_SCALE));
        assertThat(onBothAdmittedBounds.scale()).isEqualTo(Money.MAX_INPUT_SCALE);
        assertThat(onBothAdmittedBounds.precision()).isEqualTo(Money.MAX_INPUT_PRECISION);
        assertThat(assertThrows(ArithmeticException.class, () -> Money.of(onBothAdmittedBounds)))
                .hasMessageContaining("an amount declaring 12 integer digits")
                .hasMessageNotContaining(admittedRange);

        BigDecimal insideBothBounds = new BigDecimal("1." + "1".repeat(Money.MAX_INPUT_SCALE));
        assertThat(insideBothBounds.scale()).isEqualTo(Money.MAX_INPUT_SCALE);
        assertThat(Money.of(insideBothBounds).toPlainString()).isEqualTo("1.11");
    }

    /**
     * Verifies the three sign predicates partition the domain with no value satisfying two.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It expects no
     * exception because all three vectors are inside the documented money domain.
     *
     * <p>Assumptions: the three predicates are asserted together on the same three vectors rather
     * than one at a time, because what has to hold is that they PARTITION the domain: the failure
     * they guard against is a positive test written with a comparison that also admits zero, which a
     * test of that predicate alone would pass. The smallest non-zero cent is used on both sides
     * because it is the value nearest the divide.
     */
    @Test
    @DisplayName("isPositive, isNegative and isZero partition the domain at one cent")
    void partitionsTheDomainAcrossTheThreeSignPredicates() {
        Money smallestPositive = Money.of("0.01");
        Money smallestNegative = Money.of("-0.01");

        assertThat(smallestPositive.isPositive()).isTrue();
        assertThat(smallestPositive.isNegative()).isFalse();
        assertThat(smallestPositive.isZero()).isFalse();

        assertThat(Money.ZERO.isPositive()).isFalse();
        assertThat(Money.ZERO.isNegative()).isFalse();
        assertThat(Money.ZERO.isZero()).isTrue();

        assertThat(smallestNegative.isPositive()).isFalse();
        assertThat(smallestNegative.isNegative()).isTrue();
        assertThat(smallestNegative.isZero()).isFalse();
    }

    /**
     * Verifies multiplication and division reduce under the general contract and refuse bad inputs.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions and after
     * capturing four expected exceptions. Every expected exception stays inside its assertion.
     *
     * <p>Assumptions: the rounding vectors are chosen to land exactly on a half cent, because that is
     * the only input at which {@link Money#GENERAL_ROUNDING} is distinguishable from truncation. Both
     * signs are asserted, since HALF_UP rounds AWAY from zero and a mode that rounded toward it would
     * agree on the positive vector and differ on the negative one.
     *
     * <p>Alternatives Considered: asserting only that the operations return the arithmetically
     * obvious product and quotient. Rejected because these two methods carry a rounding decision the
     * accrual path must NOT use: they reduce with {@link Money#GENERAL_ROUNDING}, half up, while
     * {@link Money#monthlyInterest(BigDecimal)} reduces with {@link Money#BASELINE_INTEREST_ROUNDING},
     * truncation, because that is what the reference statement does. A test that never rounds would
     * not distinguish the two contracts at all, and the half-cent vectors here are the only inputs at
     * which they can be told apart.
     *
     * <p>Assumptions: the {@code 0.03} and {@code -0.05} vectors are the ones that make this test
     * evidence for the general side of the split. Under truncation they would give {@code 0.01} and
     * {@code -0.02}, so an edit that applied the accrual mode to these two methods would fail here
     * rather than passing quietly.
     */
    @Test
    @DisplayName("multipliedBy and dividedBy reduce with HALF_UP away from zero")
    void reducesProductsAndQuotientsUnderTheGeneralContract() {
        assertThat(Money.of("100.00").multipliedBy(new BigDecimal("0.125")).toPlainString())
                .isEqualTo("12.50");
        assertThat(Money.of("0.03").multipliedBy(new BigDecimal("0.5")).toPlainString())
                .isEqualTo("0.02");
        assertThat(Money.of("-0.03").multipliedBy(new BigDecimal("0.5")).toPlainString())
                .isEqualTo("-0.02");

        assertThat(Money.of("10.00").dividedBy(new BigDecimal("3")).toPlainString())
                .isEqualTo("3.33");
        assertThat(Money.of("0.05").dividedBy(new BigDecimal("2")).toPlainString())
                .isEqualTo("0.03");
        assertThat(Money.of("-0.05").dividedBy(new BigDecimal("2")).toPlainString())
                .isEqualTo("-0.03");

        // WHY : Assumptions: a zero divisor must raise rather than yield zero, and both operations must
        //       refuse an absent operand by name. A zero divisor in a monetary calculation means the
        //       caller's own inputs are inconsistent, so substituting zero would post a real amount of
        //       nothing to a real account while reporting success. Naming the parameter in the refusal
        //       is what lets a caller tell which of two arguments was absent.
        assertThat(assertThrows(ArithmeticException.class,
                () -> Money.of("1.00").dividedBy(BigDecimal.ZERO)))
                .hasMessageContaining("zero");
        assertThat(assertThrows(NullPointerException.class,
                () -> Money.of("1.00").dividedBy(null)))
                .hasMessageContaining("divisor must not be null");
        assertThat(assertThrows(NullPointerException.class,
                () -> Money.of("1.00").multipliedBy(null)))
                .hasMessageContaining("factor must not be null");

        // WHY : Assumptions: a product that leaves the domain must be refused rather than carried, so
        //       the domain bound is enforced on the RESULT of an operation and not only on a factory
        //       input, and doubling the boundary value is the shortest way to reach it from two
        //       operands that are each admissible.
        assertThat(assertThrows(ArithmeticException.class,
                () -> Money.of(Money.MAX_MAGNITUDE).multipliedBy(new BigDecimal("2"))))
                .hasMessageContaining("exceeds the reference money domain");
    }

    /**
     * Verifies sign inversion and magnitude are exact at both ends of the signed domain.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It expects no
     * exception, because the reference picture is signed and its domain is symmetric about zero.
     *
     * <p>Assumptions: both operations are asserted at {@link Money#MAX_MAGNITUDE} deliberately. The
     * domain is symmetric, so negating or taking the magnitude of an admissible value cannot produce
     * an inadmissible one; asserting it at the boundary is what proves that symmetry holds rather
     * than assuming it, and an asymmetric bound would fail here and nowhere else.
     *
     * <p>Trade-offs: negated zero is asserted to render as positive zero. The signed zoned form does
     * carry a distinct negative-zero byte, which the codec package preserves on the wire; this type
     * deliberately has no negative zero, so the two contracts differ at exactly one value and the
     * difference is pinned here so it cannot be discovered as a byte mismatch instead.
     */
    @Test
    @DisplayName("negated and absoluteValue stay exact at the signed domain boundary")
    void invertsAndStripsSignWithoutLeavingTheDomain() {
        assertThat(Money.of("1234567890.12").negated().toPlainString())
                .isEqualTo("-1234567890.12");
        assertThat(Money.of(Money.MAX_MAGNITUDE).negated().toPlainString())
                .isEqualTo("-9999999999.99");
        assertThat(Money.ZERO.negated().toPlainString()).isEqualTo("0.00");

        assertThat(Money.of("-0.01").absoluteValue().toPlainString()).isEqualTo("0.01");
        assertThat(Money.of("0.01").absoluteValue().toPlainString()).isEqualTo("0.01");
        assertThat(Money.of(Money.MAX_MAGNITUDE.negate()).absoluteValue().toPlainString())
                .isEqualTo("9999999999.99");
        assertThat(Money.ZERO.absoluteValue().toPlainString()).isEqualTo("0.00");
    }

    /**
     * Verifies numeric equality, its hash-code contract and ordering agree with one another.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It expects no
     * exception, including from the comparison against {@code null} and against a foreign type, both
     * of which are contracted to report inequality rather than to raise.
     *
     * <p>Assumptions: the three amounts are written at three different scales -- one, three and two
     * decimal places -- and every one of them is canonical at {@link Money#SCALE} by the time it is
     * compared, which is what makes them a real test of the override. The underlying decimal's own
     * equality is scale-sensitive and would report these as three distinct values, so a class that
     * inherited it, or that derived its hash from the decimal instead of from cents, would place two
     * equal amounts in different buckets of a hash-based collection and pass every test that only
     * ever compared them directly.
     */
    @Test
    @DisplayName("equal amounts written at different scales are equal, hash alike and compare zero")
    void honoursTheEqualObjectsEqualHashContractAtScaleTwo() {
        Money fromOnePlace = Money.of("1.5");
        Money fromThreePlaces = Money.of(new BigDecimal("1.500"));
        Money fromTwoPlaces = Money.of("1.50");

        assertThat(fromOnePlace).isEqualTo(fromThreePlaces).isEqualTo(fromTwoPlaces);
        assertThat(fromOnePlace.hashCode())
                .isEqualTo(fromThreePlaces.hashCode())
                .isEqualTo(fromTwoPlaces.hashCode());
        assertThat(fromOnePlace.compareTo(fromTwoPlaces)).isZero();

        // WHY : Assumptions: the hash must be the one derived from cents rather than from the decimal,
        //       and naming the expected value pins WHICH consistent hash is implemented, so a later
        //       change to a different-but-still-consistent derivation is visible as a failure here
        //       instead of as a silent change to every collection's bucket distribution.
        assertThat(fromOnePlace.unscaledCents()).isEqualTo(150L);
        assertThat(fromOnePlace.hashCode()).isEqualTo(Long.hashCode(150L));

        // WHY : Assumptions: ordering must be antisymmetric across one cent, and inequality must be
        //       reported rather than raised for null and for a foreign type. An ordering asserted in
        //       one direction only passes for a comparison that returns the same sign both ways, which
        //       is the shape a subtraction of unscaled values can take when one side is negated by
        //       mistake.
        Money oneCentMore = Money.of("1.51");
        assertThat(fromOnePlace.compareTo(oneCentMore)).isNegative();
        assertThat(oneCentMore.compareTo(fromOnePlace)).isPositive();
        assertThat(fromOnePlace.equals(null)).isFalse();
        assertThat(fromOnePlace.equals("1.50")).isFalse();
    }

    /**
     * Asserts that the picture-bounded factory admits a narrow picture's domain and refuses beyond it.
     *
     * <p>The nine-integer-digit boundary is the one that matters for the persisted ledger columns:
     * {@code TRAN-AMT PIC S9(09)V99} and {@code TRAN-CAT-BAL PIC S9(09)V99} both map to
     * {@code NUMERIC(11,2)}, so an amount the widest picture admits must be refused here. Both signs
     * are asserted because a signed picture bounds the magnitude and not the value.
     *
     * <p>The method accepts no parameters, returns nothing, and expects
     * {@link ArithmeticException} from each over-domain amount.
     */
    @Test
    @DisplayName("ofPicture bounds a nine-digit picture where of() admits the ten-digit domain")
    void ofPictureBoundsANarrowerPictureThanTheWidestReferenceField() {
        BigDecimal nineDigitMaximum = new BigDecimal("999999999.99");
        BigDecimal oneCentOver = new BigDecimal("1000000000.00");

        assertThat(Money.ofPicture(nineDigitMaximum, 9).amount()).isEqualByComparingTo(
                nineDigitMaximum);
        assertThat(Money.ofPicture(nineDigitMaximum.negate(), 9).amount()).isEqualByComparingTo(
                nineDigitMaximum.negate());

        // Assumptions: the same value is legal under the widest picture, which is what makes the
        //   narrower bound load-bearing rather than redundant with of().
        assertThat(Money.of(oneCentOver).amount()).isEqualByComparingTo(oneCentOver);

        ArithmeticException positiveOverflow =
                assertThrows(ArithmeticException.class, () -> Money.ofPicture(oneCentOver, 9));
        assertThat(positiveOverflow.getMessage()).contains("999999999.99").doesNotContain(
                "1000000000");
        assertThrows(ArithmeticException.class, () -> Money.ofPicture(oneCentOver.negate(), 9));
    }

    /**
     * Asserts that the picture-bounded factory reduces before it measures and refuses a bad width.
     *
     * <p>Reduction before measurement is the documented ordering: a value carrying a third decimal
     * place is rounded to cents and then judged, so an amount that only exceeds the picture in digits
     * it does not keep is accepted. An integer-digit count outside one to ten names no reference
     * picture and is refused as a programming error rather than silently widening the bound.
     *
     * <p>The method accepts no parameters, returns nothing, and expects
     * {@link IllegalArgumentException} for each out-of-range width.
     */
    @Test
    @DisplayName("ofPicture reduces before bounding and refuses a width no picture declares")
    void ofPictureReducesBeforeBoundingAndRefusesAnUndeclaredWidth() {
        assertThat(Money.ofPicture(new BigDecimal("999999999.994"), 9).amount())
                .isEqualByComparingTo(new BigDecimal("999999999.99"));
        assertThat(Money.ofPicture(new BigDecimal("0.005"), 1).amount())
                .isEqualByComparingTo(new BigDecimal("0.01"));

        assertThrows(IllegalArgumentException.class,
                () -> Money.ofPicture(BigDecimal.ONE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> Money.ofPicture(BigDecimal.ONE, Money.MAX_PICTURE_INTEGER_DIGITS + 1));
        assertThrows(NullPointerException.class, () -> Money.ofPicture(null, 9));
    }

    /**
     * Creates a new Jackson 3 mapper with the authored Money module registered directly.
     *
     * <p>The method accepts no parameters and does not start a framework context. It returns a
     * fresh {@link ObjectMapper} and expects no exception from static module registration.
     *
     * @return a new {@link ObjectMapper} configured with {@link MoneyModule}
     */
    private ObjectMapper mapper() {
        return JsonMapper.builder()
                .addModule(new MoneyModule())
                .build();
    }
}
