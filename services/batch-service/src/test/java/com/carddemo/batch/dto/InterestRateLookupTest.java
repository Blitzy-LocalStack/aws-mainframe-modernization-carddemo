package com.carddemo.batch.dto;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.carddemo.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies that {@link InterestRateLookup} reports all three lookup outcomes and performs none of
 * the accrual arithmetic it documents.
 *
 * <p>Every expectation is pinned to an immutable reference artifact rather than to a value chosen
 * for the convenience of the test, and each citation sits beside the expectation it fixes. The
 * artifacts are {@code app/cpy/CVTRA02Y.cpy}, whose line 9 declares
 * {@code DIS-INT-RATE PIC S9(04)V99}; {@code app/cpy/CVTRA01Y.cpy}, whose line 9 declares
 * {@code TRAN-CAT-BAL PIC S9(09)V99}; and {@code app/cbl/CBACT04C.cbl}, whose line 214 gates the
 * accrual on a non-zero rate, lines 422 and 436 to 438 select the DEFAULT fallback, lines 443 to 459
 * refuse a second miss, and lines 464 and 465 carry the accrual statement itself.</p>
 *
 * <p>Assumptions: three of the expectations below defend properties a compiler cannot, and they are
 * why this class exists rather than being conveniences around a three-component record. The first is
 * the ZERO-RATE GATE: a rate of zero suppresses the accrual record entirely, so a lookup that
 * reported the rate without reporting the gate would let a caller emit one spurious transaction per
 * zero-rate category balance. The second is that the gate compares numerically rather than by
 * object equality, since {@code 0} and {@code 0.00} are numerically equal and not
 * {@link Object#equals(Object)}-equal. The third is the SCOPE of the DEFAULT substitution: line 437
 * replaces the account-group component alone, so a derivation replacing all three would resolve the
 * rate of a different transaction type and category and return a plausible number rather than
 * fail.</p>
 *
 * <p>Alternatives Considered: asserting the absence of arithmetic by reasoning about it in review
 * rather than in code. Rejected. The prohibition on computing interest here is the whole reason the
 * shared kernel owns one definition of the accrual contract, and a prohibition defended only by
 * review is one a later edit can cross without anything objecting, so
 * {@link #noArithmeticIsPerformedInsideTheLookupRecord()} reads the source and asserts it
 * mechanically. Reading source in a test is unusual and is confined to that single expectation for
 * that single reason.</p>
 */
class InterestRateLookupTest {

    /**
     * An ordinary account group identifier at the ten-character width the key component declares.
     */
    private static final String ORDINARY_GROUP_ID = "GROUP01   ";

    /**
     * A second ordinary account group identifier, used to build a key that is neither the requested
     * group nor the DEFAULT group.
     */
    private static final String FOREIGN_GROUP_ID = "GROUP02   ";

    /**
     * A transaction type code at the two-character width of {@code DIS-TRAN-TYPE-CD PIC X(02)} at
     * {@code app/cpy/CVTRA02Y.cpy} line 7.
     */
    private static final String TYPE_CODE = "01";

    /**
     * A transaction category code inside the range {@code DIS-TRAN-CAT-CD PIC 9(04)} admits.
     */
    private static final int CATEGORY_CODE = 5;

    /**
     * A non-zero rate at the two-place scale of {@code DIS-INT-RATE PIC S9(04)V99}.
     */
    private static final BigDecimal NON_ZERO_RATE = new BigDecimal("2.50");

    /**
     * The requested key every expectation below starts from.
     *
     * @return a key naming an ordinary account group, never {@code null}
     */
    private static DisclosureGroupKey requestedKey() {
        return new DisclosureGroupKey(ORDINARY_GROUP_ID, TYPE_CODE, CATEGORY_CODE);
    }

    /**
     * Confirms a rate of zero reports the accrual as inapplicable, at either written scale.
     *
     * <p>{@code app/cbl/CBACT04C.cbl} line 214 reads {@code IF DIS-INT-RATE NOT = 0} and its
     * {@code END-IF} at line 217 encloses both line 215's {@code 1300-COMPUTE-INTEREST} -- which
     * writes the accrual record through {@code 1300-B-WRITE-TX} at line 468 -- and line 216's
     * {@code 1400-COMPUTE-FEES}. A zero rate therefore produces no record at all, which is why this
     * predicate has to answer {@code false} rather than a caller relying on an amount of
     * {@code 0.00}.</p>
     *
     * <p>Assumptions: {@code new BigDecimal("0")} is asserted alongside {@code 0.00} deliberately,
     * because it is the case an {@link Object#equals(Object)}-based gate gets wrong. The two are
     * numerically equal and are not {@code equals}-equal, since {@link BigDecimal} equality compares
     * scale, and a rate arriving from a {@code NUMERIC(6,2)} column can be handed back at either
     * scale.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("zero rate suppresses the accrual, and scale-0 zero is caught as compareTo not equals")
    void zeroRateReportsInterestInapplicableAtEveryScaleProvingCompareToRatherThanEquals() {
        assertThat(InterestRateLookup
                .ofDirectHit(requestedKey(), new BigDecimal("0.00"))
                .interestApplicable())
                .isFalse();

        assertThat(InterestRateLookup
                .ofDirectHit(requestedKey(), new BigDecimal("0"))
                .interestApplicable())
                .isFalse();

        assertThat(new BigDecimal("0").equals(new BigDecimal("0.00")))
                .as("the two zeros are NOT equals-equal, which is what an equals-based gate misses")
                .isFalse();
        assertThat(new BigDecimal("0").compareTo(new BigDecimal("0.00")))
                .as("the two zeros ARE compareTo-equal, which is the comparison line 214 asks for")
                .isZero();
    }

    /**
     * Confirms the smallest representable non-zero rate, and a negative rate, both accrue.
     *
     * <p>Assumptions: a negative rate accrues because {@code app/cbl/CBACT04C.cbl} line 214 tests
     * the rate against zero and nothing else, so the sign takes no branch of its own; and
     * {@code DIS-INT-RATE PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy} line 9 carries the
     * {@code S}, so a negative value is representable in the reference record rather than
     * hypothetical.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("one cent of rate accrues, and so does a negative rate, since line 214 tests only zero")
    void smallestNonZeroRateAndNegativeRateBothReportInterestApplicable() {
        assertThat(InterestRateLookup
                .ofDirectHit(requestedKey(), new BigDecimal("0.01"))
                .interestApplicable())
                .isTrue();

        assertThat(InterestRateLookup
                .ofDirectHit(requestedKey(), new BigDecimal("-1.25"))
                .interestApplicable())
                .isTrue();
    }

    /**
     * Confirms a direct hit carries one key twice and reports no substitution.
     *
     * <p>This is the {@code '00'} arm of {@code app/cbl/CBACT04C.cbl} line 422: the first read found
     * the row, so line 436 does not fire.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("direct hit: requested key equals effective key and no fallback is reported")
    void directHitFactoryCarriesTheRequestedKeyAsTheEffectiveKeyAndReportsNoFallback() {
        InterestRateLookup lookup = InterestRateLookup.ofDirectHit(requestedKey(), NON_ZERO_RATE);

        assertThat(lookup.effectiveKey()).isEqualTo(lookup.requestedKey());
        assertThat(lookup.defaultGroupFallbackApplied()).isFalse();
        assertThat(lookup.resolvedRate()).isEqualByComparingTo(NON_ZERO_RATE);
    }

    /**
     * Confirms the fallback replaces the account group alone and reports that it fired.
     *
     * <p>All three properties are asserted separately because only two of them distinguish this
     * derivation from a wholesale default key: {@code app/cbl/CBACT04C.cbl} line 437 moves
     * {@code 'DEFAULT'} into {@code FD-DIS-ACCT-GROUP-ID} and into no other field, so the type and
     * category assigned at lines 211 and 212 are still in place when the retry at line 444 runs.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("fallback: group id becomes DEFAULT, type and category carry through, fallback reported")
    void defaultGroupFallbackFactoryReplacesTheGroupAloneAndReportsTheSubstitution() {
        InterestRateLookup lookup =
                InterestRateLookup.ofDefaultGroupFallback(requestedKey(), NON_ZERO_RATE);

        assertThat(lookup.effectiveKey().accountGroupId())
                .isEqualTo(DisclosureGroupKey.DEFAULT_ACCOUNT_GROUP_ID);
        assertThat(lookup.effectiveKey().transactionTypeCode()).isEqualTo(TYPE_CODE);
        assertThat(lookup.effectiveKey().transactionCategoryCode()).isEqualTo(CATEGORY_CODE);
        assertThat(lookup.defaultGroupFallbackApplied()).isTrue();

        assertThat(lookup.requestedKey().accountGroupId())
                .as("the requested key still says what was asked for, which a boolean would lose")
                .isEqualTo(ORDINARY_GROUP_ID);
    }

    /**
     * Confirms an account whose own group is DEFAULT reports no substitution on a direct hit.
     *
     * <p>Assumptions: this is the case that separates the two-term predicate from the shorter
     * {@code effectiveKey().isDefaultAccountGroup()}. Such an account resolves its row on the first
     * read, so {@code app/cbl/CBACT04C.cbl} line 422 sees {@code '00'}, line 436 does not fire and
     * the substitution at line 437 never runs -- yet the effective key does name the DEFAULT group,
     * so the shorter form would report a fallback that did not happen.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("a real group named DEFAULT resolving directly is not reported as a fallback")
    void directHitOnAGroupAlreadyNamedDefaultIsNotReportedAsASubstitution() {
        DisclosureGroupKey alreadyDefault = new DisclosureGroupKey(
                DisclosureGroupKey.DEFAULT_ACCOUNT_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

        assertThat(InterestRateLookup.ofDirectHit(alreadyDefault, NON_ZERO_RATE)
                .defaultGroupFallbackApplied())
                .isFalse();
    }

    /**
     * Confirms an effective key differing in its type or category component is refused.
     *
     * <p>Neither pair describes a lookup {@code app/cbl/CBACT04C.cbl} can have performed: the read
     * at line 416 uses the assembled key and the only other read, at line 444, follows the single
     * substitution at line 437, which leaves the type and category untouched.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("effective key differing in type or category is refused by the constructor")
    void effectiveKeyDifferingInTypeOrCategoryIsRejected() {
        DisclosureGroupKey otherType =
                new DisclosureGroupKey(ORDINARY_GROUP_ID, "02", CATEGORY_CODE);
        DisclosureGroupKey otherCategory =
                new DisclosureGroupKey(ORDINARY_GROUP_ID, TYPE_CODE, CATEGORY_CODE + 1);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() ->
                        new InterestRateLookup(requestedKey(), otherType, NON_ZERO_RATE))
                .withMessageContaining("neither the requested key");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() ->
                        new InterestRateLookup(requestedKey(), otherCategory, NON_ZERO_RATE))
                .withMessageContaining("neither the requested key");
    }

    /**
     * Confirms an effective key naming a third group, neither requested nor DEFAULT, is refused.
     *
     * <p>{@code app/cbl/CBACT04C.cbl} line 437 substitutes exactly one value, so a group identifier
     * that is neither the one asked for nor the DEFAULT group cannot have resolved the rate.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("effective key naming a third group is refused by the constructor")
    void effectiveKeyNamingAGroupThatIsNeitherRequestedNorDefaultIsRejected() {
        DisclosureGroupKey foreign =
                new DisclosureGroupKey(FOREIGN_GROUP_ID, TYPE_CODE, CATEGORY_CODE);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new InterestRateLookup(requestedKey(), foreign, NON_ZERO_RATE))
                .withMessageContaining("DEFAULT-group derivation");
    }

    /**
     * Confirms the rate is reduced to two places rather than refused, and that two places survive.
     *
     * <p>Assumptions: the reduction is the shared kernel's, reached through
     * {@link Money#of(java.math.BigDecimal)}, and the kernel normalises rather than rejecting -- it
     * pads a short scale and reduces a long one. So a scale-0 rate gains two places, a scale-3 rate
     * loses one, and a rate already at {@link Money#SCALE} passes through byte for byte. The target
     * column is {@code NUMERIC(6,2)}, so a rate arriving with a third decimal place is an input
     * defect rather than a value with information to preserve.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("rate is normalised to two places: scale 0 is padded, scale 3 reduced, scale 2 kept")
    void rateIsNormalisedToTwoDecimalPlacesRatherThanBeingRefused() {
        assertThat(InterestRateLookup.ofDirectHit(requestedKey(), new BigDecimal("3"))
                .resolvedRate())
                .isEqualTo(new BigDecimal("3.00"));

        assertThat(InterestRateLookup.ofDirectHit(requestedKey(), new BigDecimal("1.239"))
                .resolvedRate()
                .scale())
                .isEqualTo(Money.SCALE);

        assertThat(InterestRateLookup.ofDirectHit(requestedKey(), NON_ZERO_RATE).resolvedRate())
                .isEqualTo(NON_ZERO_RATE);
    }

    /**
     * Confirms the rate domain is the four integer digits of {@code PIC S9(04)V99}, both signs.
     *
     * <p>Assumptions: the bound is applied to the magnitude, because the picture is signed and its
     * negative domain mirrors its positive one, and it is narrower than the shared kernel's own
     * bound, which is the ten-integer-digit domain of a balance rather than of a rate.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("rate domain admits 9999.99 and its negative, and refuses one cent beyond either")
    void rateOutsideTheFourIntegerDigitDomainIsRejectedInBothDirections() {
        assertThat(InterestRateLookup
                .ofDirectHit(requestedKey(), InterestRateLookup.MAX_RATE_MAGNITUDE)
                .resolvedRate())
                .isEqualByComparingTo(InterestRateLookup.MAX_RATE_MAGNITUDE);

        assertThat(InterestRateLookup.MAX_RATE_MAGNITUDE).isEqualTo(new BigDecimal("9999.99"));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> InterestRateLookup
                        .ofDirectHit(requestedKey(), new BigDecimal("10000.00")))
                .withMessageContaining("PIC S9(04)V99");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> InterestRateLookup
                        .ofDirectHit(requestedKey(), new BigDecimal("-10000.00")))
                .withMessageContaining("PIC S9(04)V99");
    }

    /**
     * Confirms the documented split between the two rejection types is real and not aspirational.
     *
     * <p>Assumptions: a rate whose declared scale or precision is pathological is refused by the
     * shared kernel <b>before</b> it is reduced, and the kernel signals that as
     * {@link ArithmeticException}; a rate that reduces cleanly and sits inside the kernel's own
     * ten-integer-digit bound but outside this type's four-digit rate domain is refused here as
     * {@link IllegalArgumentException}. Both are documented on the constructor, so both are asserted
     * -- a {@code @throws} clause naming an unreachable exception is documentation a reader cannot
     * rely on.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("kernel refuses a pathological scale as ArithmeticException; the rate domain refuses locally")
    void bothDocumentedRejectionTypesAreReachableAndComeFromTheLayerThatOwnsThem() {
        assertThatExceptionOfType(ArithmeticException.class)
                .as("declared precision the kernel refuses before reducing")
                .isThrownBy(() -> InterestRateLookup
                        .ofDirectHit(requestedKey(), new BigDecimal("1E+40")));

        assertThatExceptionOfType(ArithmeticException.class)
                .as("declared scale beyond what the kernel admits")
                .isThrownBy(() -> InterestRateLookup
                        .ofDirectHit(requestedKey(), new BigDecimal("0.1234567890123456789012")));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("inside the kernel's balance domain, outside this type's rate domain")
                .isThrownBy(() -> InterestRateLookup
                        .ofDirectHit(requestedKey(), new BigDecimal("50000.00")))
                .withMessageContaining("PIC S9(04)V99");
    }

    /**
     * Confirms the published divisor is twelve hundred and is the shared kernel's own constant.
     *
     * <p>{@code app/cbl/CBACT04C.cbl} line 465 reads
     * {@code = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. The identity assertion is what proves the
     * value is aliased rather than re-declared, so a change to the kernel's divisor cannot leave a
     * second literal behind here.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("divisor is 1200 and is the shared kernel's constant rather than a second literal")
    void monthlyRateDivisorIsTwelveHundredAndIsAliasedFromTheSharedKernel() {
        assertThat(InterestRateLookup.MONTHLY_RATE_DIVISOR)
                .isEqualByComparingTo(new BigDecimal("1200"));
        assertThat(InterestRateLookup.MONTHLY_RATE_DIVISOR)
                .as("aliased, not re-declared, so there is one literal 1200 in the codebase")
                .isSameAs(Money.MONTHLY_RATE_DIVISOR);
    }

    /**
     * Confirms every component is required, and that an absent one is refused consistently.
     *
     * <p>Assumptions: the exception type is {@link IllegalArgumentException} for all three, matching
     * the sibling records in this package so a rejection reads the same across it. The rate is
     * checked before the shared kernel sees it precisely to keep that consistency, since the kernel
     * raises {@link NullPointerException} for an absent amount.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("null requested key, effective key or rate are each refused as IllegalArgumentException")
    void everyComponentIsRequiredAndAnAbsentOneIsRefusedWithThePackageExceptionType() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new InterestRateLookup(null, requestedKey(), NON_ZERO_RATE))
                .withMessageContaining("requested key");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new InterestRateLookup(requestedKey(), null, NON_ZERO_RATE))
                .withMessageContaining("effective key");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new InterestRateLookup(requestedKey(), requestedKey(), null))
                .withMessageContaining("resolved rate");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> InterestRateLookup.ofDirectHit(null, NON_ZERO_RATE))
                .withMessageContaining("requested key");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> InterestRateLookup.ofDefaultGroupFallback(null, NON_ZERO_RATE))
                .withMessageContaining("requested key");
    }

    /**
     * Confirms value equality over the three components, including across written rate scales.
     *
     * <p>Assumptions: {@code 2.50} and {@code 2.5} yield EQUAL lookups, because the constructor
     * reduces the rate to {@link Money#SCALE} places before it is stored. That is the benefit of
     * canonicalising rather than storing what arrived: {@link BigDecimal#equals(Object)} compares
     * scale, so two records carrying the same rate at different scales would otherwise be unequal
     * while every comparison treated them as one number.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     */
    @Test
    @DisplayName("value equality holds over components, and 2.5 equals 2.50 after canonicalisation")
    void valueEqualityComparesComponentsAndIsUnaffectedByTheWrittenRateScale() {
        InterestRateLookup twoPlaces =
                InterestRateLookup.ofDirectHit(requestedKey(), new BigDecimal("2.50"));
        InterestRateLookup onePlace =
                InterestRateLookup.ofDirectHit(requestedKey(), new BigDecimal("2.5"));

        assertThat(onePlace).isEqualTo(twoPlaces);
        assertThat(onePlace).hasSameHashCodeAs(twoPlaces);

        assertThat(InterestRateLookup.ofDirectHit(requestedKey(), new BigDecimal("2.51")))
                .isNotEqualTo(twoPlaces);
        assertThat(InterestRateLookup.ofDefaultGroupFallback(requestedKey(), NON_ZERO_RATE))
                .as("a differing effective key makes two lookups unequal")
                .isNotEqualTo(twoPlaces);
    }

    /**
     * Confirms the record performs no accrual arithmetic, by reading its own source.
     *
     * <p>Assumptions: the accrual belongs to {@link Money#monthlyInterest(BigDecimal)} and to
     * nothing else, so that one definition of the contract exists rather than two. This
     * expectation reads the source with comments and documentation removed, because the prose
     * legitimately names the very operations the code must not perform -- it cites the multiplication
     * and the division at {@code app/cbl/CBACT04C.cbl} lines 464 and 465, and it names the rejected
     * scale reduction -- so a search over the raw file would match its own documentation.</p>
     *
     * <p>Alternatives Considered: asserting this through reflection over the declared methods, which
     * would prove no member is NAMED for the arithmetic but not that no member PERFORMS it, and a
     * computation hidden inside a differently-named member is exactly the drift worth catching.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     *
     * @throws IOException if the record's own source file cannot be read from the module tree, which
     *     is a failure of the expectation rather than of the type under test
     */
    @Test
    @DisplayName("no accrual arithmetic is performed inside the record, only documented")
    void noArithmeticIsPerformedInsideTheLookupRecord() throws IOException {
        String code = executableSourceOfLookupRecord();

        assertThat(code)
                .as("the product belongs to the shared kernel, not to this record")
                .doesNotContain(".multiply(");
        assertThat(code)
                .as("the quotient belongs to the shared kernel, not to this record")
                .doesNotContain(".divide(");
        assertThat(code)
                .as("no rounding mode is applied here; the kernel owns the one money contract")
                .doesNotContain("RoundingMode")
                .doesNotContain("HALF_UP")
                .doesNotContain("setScale");
        assertThat(code)
                .as("inexact types are forbidden in the money path and architecture-tested")
                .doesNotContain("double")
                .doesNotContain("float")
                .doesNotContain("Double");
        assertThat(code)
                .as("the zero gate must compare numerically, so no equals-based zero test may exist")
                .doesNotContain("equals(BigDecimal.ZERO)")
                .contains("compareTo(BigDecimal.ZERO)");
    }

    /**
     * Confirms the record's documentation states the accrual contract it does not execute.
     *
     * <p>Assumptions: these are documentation expectations and they are asserted because the
     * contract is the reason this type is preferred to a bare decimal. The rounding mode, the
     * multiply-before-divide order and the sum-of-reduced-values property are each invisible in the
     * reference source -- the ABSENCE of a {@code ROUNDED} phrase is what establishes the first --
     * so a reader who cannot find them here has no other place to find them.</p>
     *
     * <p>Refactoring Rationale: this expectation has required three different wordings as the accrual's
     * disposition moved -- "rounded half up" with "C-ROUNDING", then "truncated toward zero" with two
     * mode constants, and now "reduced half up" with the single constant the kernel declares and the
     * divergence identifier restored. It requires the identifier as well as the mode because the mode
     * alone would not tell a reader that the reference reduces differently, and that difference is the
     * fact a parity reviewer needs from this type's documentation.</p>
     *
     * <p>This zero-argument test returns no value; failed expectations surface as assertion
     * errors.</p>
     *
     * @throws IOException if the record's own source file cannot be read from the module tree, which
     *     is a failure of the expectation rather than of the type under test
     */
    @Test
    @DisplayName("documentation records the rounding mode, the order and the reduction point")
    void arithmeticContractIsDocumentedEvenThoughItIsExecutedElsewhere() throws IOException {
        String source = Files.readString(lookupRecordSourcePath());

        assertThat(source)
                .as("the accrual's rounding contract, and the divergence from the reference it implies")
                .contains("reduced half up")
                .contains("GENERAL_ROUNDING")
                .contains("C-ROUNDING");
        assertThat(source)
                .as("the order of the two operations, and the scale-4 intermediate it protects")
                .contains("the product is formed before the quotient")
                .contains("scale 4");
        assertThat(source)
                .as("the accumulation property that rounding does not distribute over")
                .contains("the sum of per-category reduced values");
        assertThat(source)
                .as("the discriminating vector that makes the rounding choice concrete, in both"
                        + " directions, so the registered divergence stays legible")
                .contains("2.09")
                .contains("2.08");
        assertThat(source)
                .as("the arithmetic is stated to live in the shared kernel")
                .contains("monthlyInterest");
    }

    /**
     * Locates the source file of the record under test inside this module's main source tree.
     *
     * @return the path of {@code InterestRateLookup.java}, resolved relative to the module the test
     *     runs in so it holds from the aggregator and from the module directory alike
     */
    private static Path lookupRecordSourcePath() {
        // WHY : Assumptions: the path is resolved from the current working directory rather than
        //       from the class path, because Maven Surefire runs each module with its own base
        //       directory and a compiled class carries no route back to the source that produced
        //       it. Resolving from the module base is what makes this hold whether the reactor is
        //       started at services/ or at services/batch-service/.
        return Path.of("src", "main", "java", "com", "carddemo", "batch", "dto",
                "InterestRateLookup.java");
    }

    /**
     * Reads the record's source with its documentation and comments removed.
     *
     * @return the executable text of {@code InterestRateLookup.java}, never {@code null}
     * @throws IOException if the source file cannot be read from the module tree
     */
    private static String executableSourceOfLookupRecord() throws IOException {
        String source = Files.readString(lookupRecordSourcePath());

        // WHY : Assumptions: block comments are stripped before line comments, and the block
        //       pattern is applied in DOTALL so a Javadoc paragraph spanning many lines is removed
        //       whole. Stripping line comments first would leave the interior lines of a Javadoc
        //       block behind, since those begin with an asterisk rather than a double slash, and
        //       the surviving prose cites the multiplication and division this expectation is
        //       asserting the absence of -- so the order decides whether the assertion means
        //       anything at all.
        String withoutBlockComments = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL)
                .matcher(source)
                .replaceAll(Matcher.quoteReplacement(""));

        return withoutBlockComments.replaceAll("//[^\\n]*", "");
    }
}
