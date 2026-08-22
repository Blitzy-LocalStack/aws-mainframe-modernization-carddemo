package com.carddemo.batch.dto;

import java.math.BigDecimal;

import com.carddemo.common.money.Money;

/**
 * The resolved outcome of one disclosure-group interest-rate lookup: the key asked for, the key that
 * answered, and the rate it answered with.
 *
 * <p>This is the migrated result of {@code 1200-GET-INTEREST-RATE} at
 * {@code app/cbl/CBACT04C.cbl} line 415 together with its fallback
 * {@code 1200-A-GET-DEFAULT-INT-RATE} at line 443. The reference paragraphs leave their answer in
 * shared working storage; this record returns it, so a caller cannot read a rate that belongs to a
 * different key.
 *
 * <h2>Three outcomes, and the third is a gate rather than a value</h2>
 *
 * <p>A lookup has three distinguishable results: a direct hit, a hit on the {@code DEFAULT} account
 * group, and a resolved rate of zero. The third is not an absence -- it is a present row whose rate
 * is zero, and {@code app/cbl/CBACT04C.cbl} line 214 tests the rate and skips the accrual and the
 * generated transaction when it is zero. {@link #interestApplicable()} publishes that gate.
 *
 * <p>Alternatives Considered: computing the accrual unconditionally and letting a zero rate produce
 * a zero amount. Rejected: the reference program writes NO transaction at a zero rate, so an
 * unconditional accrual would emit a row the goldens do not contain, and a zero-amount row is
 * indistinguishable from a genuine zero-value posting once written.
 *
 * <p>Assumptions: a missing disclosure-group row is an ordinary, expected outcome that the reference
 * program handles by re-reading under the {@code DEFAULT} group at line 437, whereas a missing
 * {@code DEFAULT} row is a hard failure -- lines 443 to 458 accept only a successful second read.
 * Neither file status is carried as a component, because a resolved result has already consumed
 * them: what a caller needs is which key answered, which the effective key states directly.
 *
 * <h2>Why both keys are carried</h2>
 *
 * <p>Assumptions: the fallback replaces the account-group component ALONE, carrying the transaction
 * type and category through unchanged, so the two keys differ in at most one component. Both are
 * carried rather than one key and a boolean, because the effective key is what a diagnostic has to
 * quote and a boolean would require the reader to reconstruct it.
 *
 * <h2>The arithmetic contract, which this type records and does not perform</h2>
 *
 * <p>The accrual statement is at {@code app/cbl/CBACT04C.cbl} lines 464 and 465:
 * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}.
 *
 * <p>Assumptions: <b>the product is formed before the quotient</b>, and the parentheses that say so
 * are in the reference source itself at line 465. The balance is {@code PIC S9(09)V99} at
 * {@code app/cpy/CVTRA01Y.cpy} line 9 and the rate is scale 2, so the raw product carries
 * <b>scale 4</b> before the division reduces it. Dividing first reduces at the wrong step and loses
 * those digits, which is why transformation rule T4 requires the order be preserved.
 *
 * <p>Assumptions: <b>the quotient is reduced by discarding its surplus digits</b>, under
 * {@link Money#BASELINE_INTEREST_ROUNDING}, which is the one reduction on the money path that departs
 * from transformation rule T3's half up, and it departs from it to reproduce the reference exactly.
 *
 * <p>Assumptions: the reference program reduces this quotient the same way -- no {@code ROUNDED}
 * phrase appears on the statement nor anywhere else in that program, and an unrounded {@code COMPUTE}
 * storing into the fixed-scale {@code PIC S9(09)V99} fields at lines 168 and 169 discards the surplus
 * digits. Reducing half up would part company with that by one cent on a quotient landing exactly on
 * a half cent: a balance of {@code 1000.80} at a rate of {@code 2.50} forms the scale-4 product
 * {@code 2502.0000}, whose quotient rounds half up to {@code 2.09} where the reference stores
 * {@code 2.08}. The accrual produces {@code 2.08}, because it reduces with
 * {@code Money.BASELINE_INTEREST_ROUNDING}, and no divergence is registered for the reduction.
 * Assumptions: the cent would not have stayed local, which is why this is settled toward the reference
 * rather than registered: line 467 adds each reduced term into the account total and line 352 adds that
 * total to the balance, which the next inclusive over-limit comparison is made against, so a cent here
 * can move a posting decision there. A predecessor of this paragraph described the half-up reading with
 * the cent registered as {@code C-ROUNDING}; that identifier is withdrawn in section 7.5 of the
 * register, because transformation rule T3 is the money path's general default while the plan pins this
 * formula to the reference at its section 0.7.3 and makes the goldens its oracle at 0.7.7.
 *
 * <p>Assumptions: the account increment is <b>the sum of per-category reduced values</b> and never
 * the reduction of a sum. Line 467 adds each transaction's own already-reduced interest into
 * {@code WS-TOTAL-INT}, line 200 resets that total only on the account change line 194 detects, and
 * line 352 adds the accumulated total to {@code ACCT-CURR-BAL}. Reducing once at the end changes
 * account balances, because rounding does not distribute over addition.
 *
 * <p>Trade-offs: <b>none of that arithmetic is implemented here.</b> The product, the quotient, the
 * divisor and the rounding mode all live in {@link Money} -- the accrual is
 * {@link Money#monthlyInterest(BigDecimal)} -- and this record is a passive carrier that names the
 * divisor through {@link #MONTHLY_RATE_DIVISOR} and publishes the gate through
 * {@link #interestApplicable()}. A convenience method here would be a second definition of the
 * interest contract, and transformation rule T2 requires a shared concern be reached only from
 * {@code common-lib}.
 *
 * <h2>Scale, sign and equality</h2>
 *
 * <p>Assumptions: the scale-2 invariant is delegated to {@link Money#of(BigDecimal)} rather than
 * asserted here, so that "scale 2" has one definition rather than one per decimal-carrying type.
 * The rate is <b>signed</b>, because {@code PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy} line 9
 * is a signed picture, and value equality compares all three components.
 *
 * @param requestedKey the key the lookup was asked to resolve, assembled from the account's group
 *     identifier and the category balance's type and category codes as
 *     {@code app/cbl/CBACT04C.cbl} lines 210 to 212 assemble it; never {@code null}, and retained
 *     unchanged even when the fallback displaces its group component
 * @param effectiveKey the key that actually resolved the rate; never {@code null}, and either equal
 *     to {@code requestedKey} or exactly its
 *     {@link DisclosureGroupKey#withDefaultAccountGroupId()} derivation, which is the single
 *     substitution {@code app/cbl/CBACT04C.cbl} line 437 performs
 * @param resolvedRate the annual percentage rate the effective key resolved,
 *     {@code DIS-INT-RATE PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy} line 9; never
 *     {@code null}, always at exactly {@link Money#SCALE} decimal places, signed, and zero when the
 *     accrual is to be suppressed altogether
 */
public record InterestRateLookup(
        DisclosureGroupKey requestedKey,
        DisclosureGroupKey effectiveKey,
        BigDecimal resolvedRate) {

    /**
     * The divisor that converts an annual percentage rate into one month's fractional rate, 1200.
     *
     * <p>The number is 1200 because it carries two conversions at once: twelve months to the year,
     * and a hundred to turn a percentage into a fraction. It is the literal the reference statement
     * divides by, at {@code app/cbl/CBACT04C.cbl} lines 464 and 465, where line 465 reads
     * {@code = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, and it is kept as that one combined literal
     * rather than split into its two factors so the target performs one division where the
     * reference performs one.</p>
     */
    // Assumptions: this is an alias for the shared kernel's constant and not a second
    //     declaration of 1200. The value is published here because a caller holding a rate needs
    //     to read the divisor from the type that carries the rate, but declaring
    //     new BigDecimal("1200") again would create two literals that a later edit could move
    //     apart -- and the arithmetic that consumes it lives on Money, so the kernel's copy is
    //     the one that would still be used while this one silently disagreed. Transformation
    //     rule T2 requires shared concerns to be reached only from common-lib; an alias reaches
    //     it rather than restating it.
    public static final BigDecimal MONTHLY_RATE_DIVISOR = Money.MONTHLY_RATE_DIVISOR;

    /**
     * The greatest magnitude the rate field can hold, being the domain of {@code PIC S9(04)V99}.
     *
     * <p>Four integer digits and two decimal digits is the whole domain of
     * {@code DIS-INT-RATE PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy} line 9, so
     * {@code 9999.99} is the exact upper bound of what the reference record's rate field can carry.
     * The bound is applied to the magnitude rather than to the signed value because the picture is
     * signed and its negative domain mirrors its positive one.</p>
     *
     * <p>Assumptions: this bound is narrower than the shared kernel's own
     * {@link Money#MAX_MAGNITUDE}, and deliberately so. The kernel's bound is the ten-integer-digit
     * domain of the widest money picture in the base masters, because the kernel carries amounts; a
     * rate is not an amount, and its picture declares four integer digits, which is why the target
     * column for it is {@code NUMERIC(6,2)} rather than the {@code NUMERIC(12,2)} a balance takes.
     * Delegating the scale to the kernel therefore does not delegate the width, so the width is
     * checked here. It is published rather than kept private because a caller validating a rate
     * before offering it, and a test asserting the boundary, both need the same number this type
     * rejects against; two copies of it would be two numbers that can disagree.</p>
     */
    public static final BigDecimal MAX_RATE_MAGNITUDE = new BigDecimal("9999.99");

    /**
     * Reduces the rate to its canonical two-place form and refuses a lookup that cannot have
     * happened.
     *
     * <p>Three things are asserted: that every component is present; that the rate lies inside the
     * domain its picture declares, after being reduced to two decimal places by the shared kernel;
     * and that the effective key stands in one of exactly two admissible relationships to the
     * requested key. Nothing else is normalised -- neither key is altered, and the rate's sign is
     * left alone.</p>
     *
     * <p>Successful construction yields this record instance and no separate return value.</p>
     *
     * @param requestedKey the candidate requested key; must be non-null
     * @param effectiveKey the candidate effective key; must be non-null, and must be either equal
     *     to {@code requestedKey} or exactly its
     *     {@link DisclosureGroupKey#withDefaultAccountGroupId()} derivation
     * @param resolvedRate the candidate rate; must be non-null and must not exceed
     *     {@link #MAX_RATE_MAGNITUDE} in magnitude once reduced to {@link Money#SCALE} places
     * @throws IllegalArgumentException if any component is {@code null}, if the effective key
     *     differs from the requested key in any component other than the account group, or differs
     *     in the account group by anything other than becoming
     *     {@link DisclosureGroupKey#DEFAULT_ACCOUNT_GROUP_ID}, or if the reduced rate falls outside
     *     the domain of {@code PIC S9(04)V99}
     * @throws ArithmeticException if {@code resolvedRate} declares a scale or precision the shared
     *     kernel refuses to reduce, which {@link Money#of(BigDecimal)} rejects before attempting
     *     the reduction
     */
    public InterestRateLookup {
        // Assumptions: an absent component is reported as IllegalArgumentException to match
        //     the sibling records in this package -- DisclosureGroupKey and BusinessDate both
        //     raise it for an absent or mis-shaped component -- so a rejection from any of them
        //     reads the same way. The rate is null-checked HERE, before the kernel sees it,
        //     precisely to keep that consistency: Money.of raises NullPointerException on null,
        //     which is the correct contract for the kernel and the wrong one for this package,
        //     and checking first is what decides which of the two a caller meets.
        if (requestedKey == null) {
            throw new IllegalArgumentException(
                    "interest-rate lookup requested key is required and was null");
        }

        if (effectiveKey == null) {
            throw new IllegalArgumentException(
                    "interest-rate lookup effective key is required and was null");
        }

        if (resolvedRate == null) {
            throw new IllegalArgumentException(
                    "interest-rate lookup resolved rate is required and was null; a resolved"
                            + " lookup always carries a rate, and a rate of zero is a value rather"
                            + " than an absence");
        }

        // Assumptions: the effective key is admitted only if it is the requested key itself
        //     or exactly the requested key's DEFAULT derivation, because those are the only two
        //     keys app/cbl/CBACT04C.cbl can have resolved a rate with -- the read at line 416
        //     uses the assembled key, and the only other read, at line 444, follows the single
        //     substitution at line 437. Any other pair describes a lookup that did not happen.
        // Alternatives Considered: comparing the group components and asserting the other two
        //     match, which is the same check written out by hand. Rejected because it would
        //     restate DisclosureGroupKey's derivation rule here, giving the fallback two
        //     definitions that could drift apart; deriving the admissible key from the requested
        //     one instead means this check is expressed in terms of the derivation it is
        //     guarding, so a change to that derivation cannot leave this constructor behind. It
        //     also covers both failure modes with one comparison: a differing type or category
        //     code matches neither admissible key, and so does a group identifier that is
        //     neither the one asked for nor the DEFAULT group.
        if (!effectiveKey.equals(requestedKey)
                && !effectiveKey.equals(requestedKey.withDefaultAccountGroupId())) {
            throw new IllegalArgumentException("interest-rate lookup effective key "
                    + effectiveKey + " is neither the requested key " + requestedKey
                    + " nor its DEFAULT-group derivation; the reference fallback replaces the"
                    + " account group component alone and carries the transaction type and"
                    + " category through unchanged");
        }

        // Refactoring Rationale: the two-place reduction is delegated to the shared kernel rather
        //   than performed here with a setScale of this type's own. "Exact fixed point at two
        //   places" is one invariant wanted by every decimal-carrying transfer object, and a
        //   private reduction per type is how two of them come to reduce differently -- one
        //   padding a short value and another refusing it, for instance. The reduction is a
        //   representation change and not the accrual, and it cannot alter the rate in any case
        //   because the rate arrives from a two-place column with nothing to lose.
        resolvedRate = Money.of(resolvedRate).amount();

        // Assumptions: the magnitude is bounded to the four integer digits of
        //     PIC S9(04)V99 at app/cpy/CVTRA02Y.cpy line 9, and the bound is applied to the
        //     magnitude rather than to the signed value because the picture is signed and its
        //     negative domain mirrors its positive one. The check is here and not left to the
        //     kernel because the kernel's bound is the ten-integer-digit domain of a balance,
        //     so a rate of a million would satisfy it while having no representation in the six
        //     bytes this field occupies.
        // Trade-offs: this rejects rather than saturating or truncating the high-order
        //     digits. Rejecting stops a mis-decoded rate at the point it entered, where the key
        //     that produced it is still in hand; carrying it forward would multiply a balance by
        //     a number the reference record cannot hold and post the result as interest, which
        //     is a plausible-looking amount that no comparison against the source data would
        //     flag as malformed.
        if (resolvedRate.abs().compareTo(MAX_RATE_MAGNITUDE) > 0) {
            throw new IllegalArgumentException("interest-rate lookup resolved rate declares "
                    + (resolvedRate.precision() - resolvedRate.scale())
                    + " integer digits, outside the domain of PIC S9(04)V99 whose greatest"
                    + " magnitude is " + MAX_RATE_MAGNITUDE.toPlainString());
        }
    }

    /**
     * Records a lookup that the requested key resolved on its own, with no substitution.
     *
     * <p>This is the {@code '00'} arm of {@code app/cbl/CBACT04C.cbl} line 422: the read at line
     * 416 found the row, so line 436 does not fire and the key that answered is the key that was
     * asked for. The effective key is set to the requested key by this factory rather than by its
     * caller, so the two cannot be passed in as an inconsistent pair.</p>
     *
     * @param requestedKey the key that resolved the rate directly; must be non-null
     * @param resolvedRate the rate that key resolved; must be non-null and inside the domain of
     *     {@code PIC S9(04)V99}
     * @return a lookup whose requested and effective keys are the same key, and whose
     *     {@link #defaultGroupFallbackApplied()} therefore answers {@code false}; never
     *     {@code null}
     * @throws IllegalArgumentException if either argument is {@code null}, or if the reduced rate
     *     falls outside the domain of {@code PIC S9(04)V99}
     * @throws ArithmeticException if {@code resolvedRate} declares a scale or precision the shared
     *     kernel refuses to reduce
     */
    public static InterestRateLookup ofDirectHit(
            DisclosureGroupKey requestedKey, BigDecimal resolvedRate) {
        return new InterestRateLookup(requestedKey, requestedKey, resolvedRate);
    }

    /**
     * Records a lookup that resolved only after the account group was replaced with DEFAULT.
     *
     * <p>This is the {@code '23'} arm of {@code app/cbl/CBACT04C.cbl} line 422 followed by lines
     * 436 to 438: the requested key found nothing, the account group component alone was replaced,
     * and the retry at line 444 resolved the rate. The rate this factory is given is the one the
     * <b>retry</b> produced, not the value left in the record area by whatever read preceded it.</p>
     *
     * @param requestedKey the key that resolved nothing and whose group component was displaced;
     *     must be non-null
     * @param resolvedRate the rate the DEFAULT-group retry resolved; must be non-null and inside
     *     the domain of {@code PIC S9(04)V99}
     * @return a lookup whose effective key is the requested key's DEFAULT-group derivation, and
     *     whose {@link #defaultGroupFallbackApplied()} therefore answers {@code true} unless the
     *     requested key already named the DEFAULT group; never {@code null}
     * @throws IllegalArgumentException if either argument is {@code null}, or if the reduced rate
     *     falls outside the domain of {@code PIC S9(04)V99}
     * @throws ArithmeticException if {@code resolvedRate} declares a scale or precision the shared
     *     kernel refuses to reduce
     */
    public static InterestRateLookup ofDefaultGroupFallback(
            DisclosureGroupKey requestedKey, BigDecimal resolvedRate) {
        // Assumptions: the effective key is DERIVED here rather than accepted as an
        //     argument, so a caller cannot supply a pair the fallback could not have produced --
        //     a wholesale default key, for instance, which would resolve the rate of a different
        //     transaction type and category and so return a plausible number rather than fail.
        //     The null check the derivation needs is the compact constructor's, and it cannot
        //     run first because the derivation is an argument to it, so the receiver is tested
        //     here to keep the package's IllegalArgumentException in front of the
        //     NullPointerException a direct call would otherwise raise.
        if (requestedKey == null) {
            throw new IllegalArgumentException(
                    "interest-rate lookup requested key is required and was null");
        }

        return new InterestRateLookup(
                requestedKey, requestedKey.withDefaultAccountGroupId(), resolvedRate);
    }

    /**
     * Returns the key the lookup was asked to resolve, before any substitution.
     *
     * @return the requested key, never {@code null}
     */
    public DisclosureGroupKey requestedKey() {
        return requestedKey;
    }

    /**
     * Returns the key that actually resolved the rate.
     *
     * @return the effective key, never {@code null}; equal to {@link #requestedKey()} on a direct
     *     hit, and its DEFAULT-group derivation when the fallback fired
     */
    public DisclosureGroupKey effectiveKey() {
        return effectiveKey;
    }

    /**
     * Returns the resolved annual percentage rate at its canonical two-place scale.
     *
     * @return the rate, never {@code null} and never at any other scale; signed, and possibly
     *     zero, in which case {@link #interestApplicable()} answers {@code false}
     */
    public BigDecimal resolvedRate() {
        return resolvedRate;
    }

    /**
     * Reports whether an accrual is to be performed at all for this resolved rate.
     *
     * <p>This predicate takes no arguments. It answers {@code false} for exactly one reason -- a
     * resolved rate of zero -- and a {@code false} answer means the caller emits <b>no</b> accrual
     * transaction, adds nothing to the account's running total and computes no fee, rather than
     * computing an amount that happens to be {@code 0.00}.</p>
     *
     * @return {@code true} when the resolved rate differs from zero and the accrual is to proceed,
     *     and {@code false} when the rate is zero and the accrual is to be suppressed entirely
     */
    public boolean interestApplicable() {
        // Assumptions: app/cbl/CBACT04C.cbl line 214, IF DIS-INT-RATE NOT = 0, gates BOTH
        //     statements it encloses -- line 215's 1300-COMPUTE-INTEREST, which is what writes
        //     the accrual record through 1300-B-WRITE-TX at line 468, and line 216's
        //     1400-COMPUTE-FEES -- so a zero rate produces no record, not a zero-amount record.
        //     Ignoring this predicate would emit one spurious transaction per zero-rate category
        //     balance, and the resulting parity failure reports as unexpected ROWS rather than as
        //     a wrong amount, which points a maintainer at the fixtures instead of at line 214.
        // Assumptions: the comparison is compareTo against zero and never equals.
        //     BigDecimal.equals compares scale as well as unscaled value, so 0, 0.0 and 0.00 are
        //     three unequal objects that are all numerically zero; the rate reaches this type
        //     from a NUMERIC(6,2) column through a driver that is free to hand back any of them,
        //     and although the compact constructor canonicalises to two places, an equals-based
        //     gate would be correct only for as long as that canonicalisation stays in place.
        //     compareTo answers the numeric question that line 214 asks, at any scale.
        return resolvedRate.compareTo(BigDecimal.ZERO) != 0;
    }

    /**
     * Reports whether the rate was resolved through the DEFAULT-group substitution.
     *
     * <p>This predicate takes no arguments. It distinguishes the two arms of
     * {@code app/cbl/CBACT04C.cbl} line 422 -- a row found under the account's own group, against
     * a row found only after lines 436 to 438 replaced that group with DEFAULT -- which is a
     * distinction a resolved rate alone cannot express, since line 422 treats both as success.</p>
     *
     * @return {@code true} when the effective key names the DEFAULT group and the requested key did
     *     not, meaning the substitution occurred, and {@code false} for a direct hit
     */
    public boolean defaultGroupFallbackApplied() {
        // Alternatives Considered: answering with effectiveKey.isDefaultAccountGroup()
        //     alone, which is the shorter form and is wrong in one reachable case. An account
        //     whose own group identifier IS the DEFAULT group resolves its row on the first
        //     read, so app/cbl/CBACT04C.cbl line 422 sees status '00', line 436 does not fire
        //     and no substitution happens -- yet the effective key names the DEFAULT group, so
        //     the shorter form would report a fallback that never occurred. Requiring that the
        //     requested key did NOT already name that group asks the question line 436 actually
        //     asks, which is whether the MOVE at line 437 ran.
        // Assumptions: under the compact constructor's invariant this is exactly
        //     "the effective key differs from the requested key", since the only admitted
        //     difference IS the DEFAULT substitution. It is written in terms of the group
        //     component rather than as an inequality of whole keys because that is the component
        //     line 437 replaces, so the code names the thing that changed.
        return effectiveKey.isDefaultAccountGroup() && !requestedKey.isDefaultAccountGroup();
    }

    /**
     * Renders both keys and the resolved RATE, which is reference data and not a party's money.
     *
     * <p>Purpose. This renderer exists because the rate is a fixed-point decimal, and the diagnostic rule
     * at {@code docs/architecture/observability.md} L1093 to L1112 withholds monetary values; writing the
     * renderer records the decision not to withhold this one at the site, rather than leaving each reader
     * to decide it again.</p>
     *
     * <p>Assumptions: a disclosure-group interest rate is not a monetary amount, a credit limit or a
     * balance. It is a percentage from a seeded reference row keyed by group, type and category; it belongs
     * to a rate table rather than to an account, and the reference-service publishes it in full on its own
     * rate operation. The equivalent decision, with the same reasoning, is recorded on
     * {@code com.carddemo.reference.dto.DisclosureGroupRateResponse}.</p>
     *
     * <p>Trade-offs: both keys print in full because the pair is the point of this type -- the requested
     * key and the effective one differ exactly when the default group supplied the rate, which is the
     * fallback the interest job's parity depends on, and a rendering showing only one of them could not
     * show that a fallback happened.</p>
     *
     * @return a rendering naming the requested key, the effective key and the resolved rate; never
     *     {@code null}
     */
    @Override
    public String toString() {
        return "InterestRateLookup[requestedKey=" + this.requestedKey
                + ", effectiveKey=" + this.effectiveKey
                + ", resolvedRate=" + this.resolvedRate + ']';
    }
}
