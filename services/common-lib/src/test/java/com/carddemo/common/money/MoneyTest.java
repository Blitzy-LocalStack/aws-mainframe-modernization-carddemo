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
        // WHAT: Exercise the same discarded midpoint digit on both sides of zero.
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

        assertThat(balance.monthlyInterestTruncated(resolvedRate))
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

        // WHAT: Read only the X(10) group component from the quoted fixture record.
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

        assertThat(zeroBalance.monthlyInterestTruncated(liveRate))
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
        assertThat(balance.monthlyInterestTruncated(presentZeroRate))
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

        // WHAT: Exercise the absent resolved-rate boundary that the Money API actually exposes.
        // WHY : Assumptions: CBACT04C.cbl:422 and 436-458 require the second read to return 00;
        //       default_fallback/discgrp.txt contains all 17 rows, so this null case is synthetic.
        NullPointerException failure = assertThrows(
                NullPointerException.class,
                () -> balance.monthlyInterestTruncated(null));

        assertThat(failure).isExactlyInstanceOf(NullPointerException.class);
    }

    /**
     * Verifies that common rates can agree under DOWN and HALF_UP and therefore cannot prove the
     * production rounding choice.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because all balances and rates are valid monetary inputs.
     */
    @Test
    @DisplayName("non-discriminating rates agree under DOWN and HALF_UP")
    void documentsRatesThatDoNotDiscriminateInterestRounding() {
        Money balance = Money.of("1000.00");
        BigDecimal fixtureRate = new BigDecimal("25.00");
        BigDecimal syntheticRate = new BigDecimal("2.50");

        // WHAT: Retain the fixture rate and the tempting synthetic rate as negative controls.
        // WHY : Alternatives Considered: The synthetic 2.50 rate was rejected as a rounding
        //       regression discriminator because both supported modes produce 2.08 here.
        assertThat(balance.monthlyInterestTruncated(fixtureRate))
                .isEqualTo(Money.of("20.83"));
        assertThat(balance.monthlyInterestHalfUp(fixtureRate))
                .isEqualTo(Money.of("20.83"));
        assertThat(balance.monthlyInterestTruncated(syntheticRate))
                .isEqualTo(Money.of("2.08"));
        assertThat(balance.monthlyInterestHalfUp(syntheticRate))
                .isEqualTo(Money.of("2.08"));
    }

    /**
     * Verifies the first vector that distinguishes production DOWN from a HALF_UP counterfactual.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because the source-derived balance and synthetic discriminating rate
     * are valid inputs.
     */
    @Test
    @DisplayName("1000.00 at 2.71 distinguishes DOWN from HALF_UP")
    void truncatesFirstDiscriminatingInterestVector() {
        Money balance = Money.of("1000.00");
        BigDecimal rate = new BigDecimal("2.71");
        Money production = balance.monthlyInterestTruncated(rate);
        Money halfUpCounterfactual = balance.monthlyInterestHalfUp(rate);

        assertThat(production).isEqualTo(Money.of("2.25"));
        assertThat(halfUpCounterfactual).isEqualTo(Money.of("2.26"));
        assertThat(production).isNotEqualTo(halfUpCounterfactual);
    }

    /**
     * Verifies the second vector that distinguishes production DOWN from a HALF_UP
     * counterfactual.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because the synthetic balance-and-rate combination remains within the
     * fixed-point domain.
     */
    @Test
    @DisplayName("1000.80 at 2.50 independently distinguishes DOWN from HALF_UP")
    void truncatesSecondDiscriminatingInterestVector() {
        Money balance = Money.of("1000.80");
        BigDecimal rate = new BigDecimal("2.50");
        Money production = balance.monthlyInterestTruncated(rate);
        Money halfUpCounterfactual = balance.monthlyInterestHalfUp(rate);

        assertThat(production).isEqualTo(Money.of("2.08"));
        assertThat(halfUpCounterfactual).isEqualTo(Money.of("2.09"));
        assertThat(production).isNotEqualTo(halfUpCounterfactual);
    }

    /**
     * Verifies that signed interest truncates toward zero with DOWN rather than toward negative
     * infinity with FLOOR.
     *
     * <p>The test accepts no parameters and returns normally after its void assertions. It
     * expects no exception because the synthetic signed balance is permitted by the source
     * copybooks and remains inside the money domain.
     */
    @Test
    @DisplayName("signed interest rejects FLOOR as the truncation interpretation")
    void truncatesSignedInterestTowardZeroRatherThanFloor() {
        Money balance = Money.of("-1000.00");
        BigDecimal rate = new BigDecimal("2.71");

        // WHAT: Compare the production helper with the nearest plausible signed alternative.
        // WHY : Alternatives Considered: FLOOR agrees with DOWN for positive amounts but yields
        //       -2.26 here, while the signed receiving field truncates toward zero to -2.25.
        Money production = balance.monthlyInterestTruncated(rate);
        Money floorCounterfactual = balance.monthlyInterest(rate, RoundingMode.FLOOR);

        assertThat(production).isEqualTo(Money.of("-2.25"));
        assertThat(floorCounterfactual).isEqualTo(Money.of("-2.26"));
        assertThat(production).isNotEqualTo(floorCounterfactual);
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
        Money production = balance.monthlyInterestTruncated(rate);

        // WHAT: Contrast the authored formula with an intentionally reordered calculation.
        // WHY : Assumptions: CBACT04C.cbl:465 parenthesizes the product, and the scale-two
        //       CVTRA01Y/CVTRA02Y operands form a scale-four raw product before division.
        BigDecimal divideFirstCounterfactual = balance.amount()
                .divide(Money.MONTHLY_RATE_DIVISOR, Money.SCALE, RoundingMode.DOWN)
                .multiply(rate)
                .setScale(Money.SCALE, RoundingMode.DOWN);

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
    @DisplayName("per-item truncation precedes interest accumulation")
    void truncatesEachInterestItemBeforeAccumulation() {
        Money balance = Money.of("1000.00");
        BigDecimal rate = new BigDecimal("2.71");
        Money reducedItem = balance.monthlyInterestTruncated(rate);

        // WHAT: Compare source-order accumulation with one reduction after aggregating raw input.
        // WHY : Trade-offs: CBACT04C.cbl:467 adds WS-MONTHLY-INT after its scale-two receive;
        //       reducing only once after summing would instead produce the divergent 6.77 value.
        Money perItemTotal = Money.total(reducedItem, reducedItem, reducedItem);
        BigDecimal reduceOnceCounterfactual = balance.amount()
                .multiply(new BigDecimal("3"))
                .multiply(rate)
                .divide(Money.MONTHLY_RATE_DIVISOR, Money.SCALE, RoundingMode.DOWN);

        assertThat(reducedItem).isEqualTo(Money.of("2.25"));
        assertThat(perItemTotal).isEqualTo(Money.of("6.75"));
        assertThat(reduceOnceCounterfactual).isEqualByComparingTo("6.77");
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

        // WHAT: Preserve the fixture current balance as a sentinel outside the projected formula.
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

        // WHAT: Exercise equality and pair it with the one-cent-over sibling test.
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

        // WHAT: Stop at the final money predicate exposed by the authored common-lib API.
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

        // WHAT: Assert the emitted token bytes before any decoder can coerce their representation.
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

        // WHAT: Submit the same decimal glyphs without the required JSON string quotes.
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

        // WHAT: Inspect string payload characters directly without numeric reparsing.
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