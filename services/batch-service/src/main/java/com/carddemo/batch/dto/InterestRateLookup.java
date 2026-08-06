package com.carddemo.batch.dto;

import java.math.BigDecimal;

import com.carddemo.common.money.Money;

/**
 * The resolved outcome of one disclosure-group rate lookup: the key that was asked for, the key
 * that answered, and the rate it answered with.
 *
 * <p>This is the migrated result of {@code 1200-GET-INTEREST-RATE} at
 * {@code app/cbl/CBACT04C.cbl} lines 415 to 440 together with its retry
 * {@code 1200-A-GET-DEFAULT-INT-RATE} at lines 443 to 459. The rate is
 * {@code DIS-INT-RATE PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy} line 9 -- the field following
 * the 16-byte key that {@link DisclosureGroupKey} models, inside the 50-byte record whose length
 * that copybook's line 2 records as {@code RECLN = 50} and whose cluster
 * {@code app/jcl/DISCGRP.jcl} line 40 defines with {@code KEYS(16 0)}. Under the migration plan's
 * transformation rule T1 the copybook, and not the file description that repeats it at
 * {@code app/cbl/CBACT04C.cbl} lines 79 to 81, is the normative source for the width and scale
 * below.</p>
 *
 * <h2>Three outcomes, and the third is the one that gets collapsed</h2>
 *
 * <p>A lookup has three distinguishable results, and only the first two are obvious from the shape
 * of the paragraph that produces them:</p>
 *
 * <ol>
 *   <li><b>Direct hit.</b> The requested key resolved a row. The requested and effective keys are
 *       equal and {@link #defaultGroupFallbackApplied()} answers {@code false}.</li>
 *   <li><b>DEFAULT fallback.</b> The requested key resolved nothing, so the account-group component
 *       alone was replaced and the read retried. {@link #defaultGroupFallbackApplied()} answers
 *       {@code true}.</li>
 *   <li><b>Zero rate.</b> A row was resolved -- by either of the paths above -- and the rate it
 *       carries is zero. {@link #interestApplicable()} answers {@code false}, and the consequence
 *       is not a zero amount but <b>no accrual record at all</b>.</li>
 * </ol>
 *
 * <p>Assumptions: the third outcome is a gate rather than a value, and it suppresses the accrual
 * entirely. {@code app/cbl/CBACT04C.cbl} line 214 reads {@code IF DIS-INT-RATE NOT = 0} and its
 * {@code END-IF} at line 217 closes over <b>both</b> of the two statements between them: line 215
 * performs {@code 1300-COMPUTE-INTEREST} and line 216 performs {@code 1400-COMPUTE-FEES}. The
 * first of those is what emits the generated accrual transaction -- it computes at lines 464 and
 * 465, adds to the running total at line 467, and then performs {@code 1300-B-WRITE-TX} at line
 * 468, the paragraph beginning at line 473 that assembles and writes the record and moves the
 * computed amount into {@code TRAN-AMT} at line 490. So on a zero rate the reference program
 * computes nothing, writes nothing and adds nothing to the account's running total.</p>
 *
 * <p>Alternatives Considered: computing the accrual unconditionally and letting the arithmetic
 * produce the zero, on the reasoning that a balance multiplied by a zero rate and divided by 1200
 * is {@code 0.00} and therefore harmless. Rejected, and the reason it is rejected is the one thing
 * worth carrying away from this type. The defect would not be a wrong amount -- the amount would be
 * exactly right -- it would be <b>extra rows</b>: one generated accrual transaction per zero-rate
 * category balance, none of which appears in the reference output. A golden-master comparison would
 * report those as unexpected records rather than as an arithmetic difference, which reads as a data
 * problem and sends a maintainer to the fixtures rather than to line 214. Modelling the gate as a
 * published predicate is what keeps the decision at the point where the evidence is.</p>
 *
 * <p>Trade-offs: the gate is <b>reported</b> here and applied by the caller, rather than being
 * enforced by refusing to construct a zero-rate lookup. A zero rate is a legitimate resolved value
 * -- the field is a signed four-digit picture and zero is inside its domain -- so refusing it would
 * misreport a successful read as a failure, and it would also lose the distinction between a
 * resolved zero and an absent row, which is exactly the distinction lines 422 and 446 are careful
 * to keep apart. The accepted cost is that a caller that ignores {@link #interestApplicable()} can
 * still accrue on a zero rate; the mitigation is that the predicate is the only reason this type
 * exists in preference to a bare {@code BigDecimal}, and it is named for the question it answers.
 * The rate is never coerced or substituted: a resolved zero stays zero.</p>
 *
 * <h2>What the two file statuses meant, and why no status is carried here</h2>
 *
 * <p>Assumptions: a missing disclosure-group row is an <b>ordinary, expected</b> outcome and not an
 * error. {@code app/cbl/CBACT04C.cbl} line 422 accepts a status of {@code '00'} or {@code '23'} --
 * found, and not found -- as equally non-fatal, setting the success indicator for both; only some
 * other status takes the else arm at line 425 and reaches the display of
 * {@code 'ERROR READING DISCLOSURE GROUP FILE'} at line 431 and the abend after it. The read at
 * line 416 carries an {@code INVALID KEY} branch that merely displays
 * {@code 'DISCLOSURE GROUP RECORD MISSING'} and {@code 'TRY WITH DEFAULT GROUP CODE'} at lines 418
 * and 419, which is the same statement of intent in the program's own words.</p>
 *
 * <p>Assumptions: a missing DEFAULT row, by contrast, <b>is</b> a hard failure, and that asymmetry
 * is why the fallback cannot be modelled as "resolves to zero interest". Lines 443 to 459 are
 * {@code 1200-A-GET-DEFAULT-INT-RATE}: the read at line 444 carries no {@code INVALID KEY} clause
 * at all, and the status test at line 446 accepts {@code '00'} and nothing else, so a key still
 * absent after the substitution reaches
 * {@code 'ERROR READING DEFAULT DISCLOSURE GROUP'} at line 455 and abends at line 458. There is no
 * third fallback and no rate compiled into the program. An instance of this type therefore
 * represents a lookup that <b>succeeded</b>; the absence of a DEFAULT row is expressed by the
 * lookup raising rather than by any state this record can hold, and it is why the reference seed
 * data has to carry DEFAULT-group rows.</p>
 *
 * <p>Trade-offs: neither {@code '00'} nor {@code '23'} is carried as a component. Their whole
 * meaning is already expressed -- the distinction between them is
 * {@link #defaultGroupFallbackApplied()}, and every other status is a raised exception rather than
 * a value. Carrying the raw status as well would put a VSAM file-status code, a concept with no
 * counterpart in the target datastore, into a transfer object whose consumers cannot act on it, and
 * it would create a second way to ask the same question that could disagree with the first.</p>
 *
 * <h2>Why both keys are carried</h2>
 *
 * <p>Assumptions: the fallback replaces the account-group component <b>alone</b>.
 * {@code app/cbl/CBACT04C.cbl} line 437 moves {@code 'DEFAULT'} into
 * {@code FD-DIS-ACCT-GROUP-ID} and into no other field, so when the retry at line 444 runs the
 * transaction category and type assigned at lines 211 and 212 are still in place. The retry
 * therefore asks for the same transaction type and category under the group named {@code DEFAULT},
 * and one component of three changes. {@link DisclosureGroupKey#withDefaultAccountGroupId()} is the
 * single place that derivation is expressed, and the compact constructor below admits no effective
 * key that is not either the requested key itself or exactly that derivation of it.</p>
 *
 * <p>Trade-offs: both keys are carried rather than one key and a boolean. The extra component costs
 * a field and buys the answer to the question a maintainer actually asks when a posted interest
 * amount looks wrong, which is not "did the fallback fire" but "what was asked for". Under the
 * single-key design the requested group identifier is overwritten by the substitution and is gone
 * -- exactly as it is in the reference program, where lines 436 to 438 overwrite the field in
 * place -- so the record could report that a substitution happened while no longer being able to
 * say what it displaced.</p>
 *
 * <p>Refactoring Rationale: returning a resolved result replaces reading a rate out of shared
 * mutable state. In the reference program the rate lives in the record area
 * {@code DIS-GROUP-RECORD}, and the {@code INVALID KEY} branch at lines 417 to 419 only displays --
 * it does not clear that area. Between the failed read at line 416 and the retry at line 444 the
 * rate field therefore still holds whatever the previous successful read left in it, so the value
 * of {@code DIS-INT-RATE} is momentarily unrelated to the key being resolved. Nothing observable
 * depends on that window, because line 436 routes straight to the retry before line 214 tests the
 * rate. It is a structural difference rather than a behavioural one: a resolved value returned from
 * a lookup has no window in which it can be read early, and the immutability of a record is what
 * removes it rather than any check written here.</p>
 *
 * <h2>The arithmetic contract, which this type records and does not perform</h2>
 *
 * <p>The accrual statement is at {@code app/cbl/CBACT04C.cbl} lines 464 and 465: line 464 reads
 * {@code COMPUTE WS-MONTHLY-INT} and line 465 reads
 * {@code = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. Four properties of it are recorded here because
 * this is where the rate that feeds it is defined, and all four are executed elsewhere.</p>
 *
 * <p>Assumptions: <b>the product is formed before the quotient</b>, and the parentheses that say so
 * are in the reference source itself at line 465. The balance is
 * {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy} line 9 and the rate is scale
 * 2, so the raw product carries <b>scale 4</b> before the division reduces it. Dividing first and
 * multiplying second reduces to two places at the wrong step and loses those intermediate digits,
 * which changes the resulting cents. The migration plan's transformation rule T4 requires the order
 * be preserved for this reason.</p>
 *
 * <p>Assumptions: <b>the quotient truncates toward zero</b>; it is not rounded half up. No
 * {@code ROUNDED} phrase appears on the statement at lines 464 and 465, and none appears on any
 * statement anywhere in that program -- a count of the phrase across all <b>652</b> of its lines
 * returns <b>0</b>. An unrounded COBOL {@code COMPUTE} storing into a fixed-scale field truncates,
 * and both receiving fields are fixed-scale: {@code WS-MONTHLY-INT} and {@code WS-TOTAL-INT} are
 * declared {@code PIC S9(09)V99} at lines 168 and 169 with no {@code VALUE} clause. The shared
 * kernel records this as its own named contract, {@link Money#BASELINE_INTEREST_ROUNDING}, which is
 * deliberately a different value from {@link Money#GENERAL_ROUNDING} -- the truncating mode governs
 * this path even though half-up governs general money arithmetic, and the two are not reconciled
 * into one mode.</p>
 *
 * <p>Alternatives Considered: rounding the quotient half up, and flooring it. Both were evaluated
 * and both diverge on inputs that occur constantly. Half-up differs by a cent whenever the
 * unrounded quotient falls on or above a half cent: a balance of {@code 1000.80} at a rate of
 * {@code 2.50} forms the product {@code 2502.0000} at scale 4, whose quotient by 1200 truncates to
 * {@code 2.08} and rounds half up to {@code 2.09}. One cent per category balance accumulates
 * through the running total at line 467 into every account balance updated at line 352. Flooring
 * agrees with truncation on a positive value and disagrees on a negative one, where truncation
 * moves toward zero and flooring away from it, and negative values are reachable rather than
 * hypothetical here: the receiving fields at lines 168 and 169 are signed pictures and so is the
 * rate, {@code PIC S9(04)V99}.</p>
 *
 * <p>Assumptions: the account increment is <b>the sum of truncated values</b> and never the
 * truncation of a sum. Line 467 adds each transaction's own already-truncated interest into
 * {@code WS-TOTAL-INT}; line 200 resets that total only when the account identifier changes, which
 * line 194 detects; and line 352, inside {@code 1050-UPDATE-ACCOUNT}, adds the accumulated total to
 * {@code ACCT-CURR-BAL}. Accumulating at full precision and truncating once at the end is the
 * natural improvement to reach for and it changes account balances, because truncation does not
 * distribute over addition.</p>
 *
 * <p>Trade-offs: <b>none of that arithmetic is implemented here.</b> The product, the quotient, the
 * divisor and the rounding mode all live in {@link Money} -- the accrual is
 * {@link Money#monthlyInterestTruncated(BigDecimal)} -- and this record is a passive carrier that
 * names the divisor through {@link #MONTHLY_RATE_DIVISOR} and publishes the gate through
 * {@link #interestApplicable()}. The compromise is real: a caller holding a resolved rate cannot
 * reach an amount without also holding a balance, so this type is less convenient than one carrying
 * a computed-interest helper. It is accepted because a convenience method here would be a second
 * definition of the interest contract, and only the one in the shared kernel is covered by the
 * kernel's own tests and by the architecture rules that forbid inexact types in the money path. The
 * migration plan's transformation rule T2 requires shared concerns to be reached only from
 * {@code common-lib}, and the interest contract is the shared concern in question.</p>
 *
 * <h2>Scale, sign and equality</h2>
 *
 * <p>Refactoring Rationale: the scale-2 invariant is delegated to {@link Money#of(BigDecimal)}
 * rather than asserted here with a scale comparison of its own. The invariant is one thing --
 * exact fixed point at two decimal places -- and it is wanted identically by every transfer object
 * that carries a decimal, so implementing it per type would create as many definitions of "scale 2"
 * as there are types, which is how two of them come to disagree. Delegating means this record
 * inherits whatever the kernel decides that phrase means, including the input bounds the kernel
 * applies before reducing.</p>
 *
 * <p>Assumptions: the rate is <b>signed</b>, because {@code PIC S9(04)V99} at
 * {@code app/cpy/CVTRA02Y.cpy} line 9 carries the {@code S}, and this type deliberately does
 * <b>not</b> constrain the sign. Line 214 tests the rate against zero and nothing else, so a
 * negative rate takes the same branch a positive one does and accrues; rejecting it here would
 * refuse a value the reference record can hold and the reference program processes. The magnitude
 * <i>is</i> constrained, to the four integer digits the picture declares -- see
 * {@link #MAX_RATE_MAGNITUDE}.</p>
 *
 * <p>Assumptions: value equality is this type's contract, comparing all three components, and the
 * compact constructor is what makes it well behaved. Because the rate is reduced to two places on
 * the way in, a lookup built from {@code 2.5} and one built from {@code 2.50} hold the identical
 * canonical rate and are <b>equal</b>. That is the whole benefit of canonicalising rather than
 * storing what arrived: {@link BigDecimal#equals(Object)} compares scale as well as value, so two
 * records carrying the same rate at different scales would otherwise be unequal while every
 * arithmetic and comparison operation treated them as one number. The generated
 * {@code equals}, {@code hashCode} and {@code toString} are all left in place, since component
 * equality over canonical components is exactly the equality wanted.</p>
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
    // WHY : Assumptions: this is an alias for the shared kernel's constant and not a second
    //       declaration of 1200. The value is published here because a caller holding a rate needs
    //       to read the divisor from the type that carries the rate, but declaring
    //       new BigDecimal("1200") again would create two literals that a later edit could move
    //       apart -- and the arithmetic that consumes it lives on Money, so the kernel's copy is
    //       the one that would still be used while this one silently disagreed. Transformation
    //       rule T2 requires shared concerns to be reached only from common-lib; an alias reaches
    //       it rather than restating it.
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
        // WHY : Assumptions: an absent component is reported as IllegalArgumentException to match
        //       the sibling records in this package -- DisclosureGroupKey and BusinessDate both
        //       raise it for an absent or mis-shaped component -- so a rejection from any of them
        //       reads the same way. The rate is null-checked HERE, before the kernel sees it,
        //       precisely to keep that consistency: Money.of raises NullPointerException on null,
        //       which is the correct contract for the kernel and the wrong one for this package,
        //       and checking first is what decides which of the two a caller meets.
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

        // WHY : Assumptions: the effective key is admitted only if it is the requested key itself
        //       or exactly the requested key's DEFAULT derivation, because those are the only two
        //       keys app/cbl/CBACT04C.cbl can have resolved a rate with -- the read at line 416
        //       uses the assembled key, and the only other read, at line 444, follows the single
        //       substitution at line 437. Any other pair describes a lookup that did not happen.
        // WHY : Alternatives Considered: comparing the group components and asserting the other two
        //       match, which is the same check written out by hand. Rejected because it would
        //       restate DisclosureGroupKey's derivation rule here, giving the fallback two
        //       definitions that could drift apart; deriving the admissible key from the requested
        //       one instead means this check is expressed in terms of the derivation it is
        //       guarding, so a change to that derivation cannot leave this constructor behind. It
        //       also covers both failure modes with one comparison: a differing type or category
        //       code matches neither admissible key, and so does a group identifier that is
        //       neither the one asked for nor the DEFAULT group.
        if (!effectiveKey.equals(requestedKey)
                && !effectiveKey.equals(requestedKey.withDefaultAccountGroupId())) {
            throw new IllegalArgumentException("interest-rate lookup effective key "
                    + effectiveKey + " is neither the requested key " + requestedKey
                    + " nor its DEFAULT-group derivation; the reference fallback replaces the"
                    + " account group component alone and carries the transaction type and"
                    + " category through unchanged");
        }

        // WHY : Refactoring Rationale: the two-place reduction is delegated to the shared kernel
        //       rather than performed here with a setScale of this type's own. "Exact fixed point
        //       at two places" is one invariant wanted by every decimal-carrying transfer object,
        //       and a private reduction per type is how two of them come to reduce differently --
        //       one padding a short value and another refusing it, for instance. The reduction is
        //       a representation change and not the accrual: the accrual's own rounding is the
        //       truncating contract named on this type, and reducing the rate's representation
        //       under the kernel's general contract does not touch it, because the rate arrives
        //       from a two-place column and has nothing to lose.
        resolvedRate = Money.of(resolvedRate).amount();

        // WHY : Assumptions: the magnitude is bounded to the four integer digits of
        //       PIC S9(04)V99 at app/cpy/CVTRA02Y.cpy line 9, and the bound is applied to the
        //       magnitude rather than to the signed value because the picture is signed and its
        //       negative domain mirrors its positive one. The check is here and not left to the
        //       kernel because the kernel's bound is the ten-integer-digit domain of a balance,
        //       so a rate of a million would satisfy it while having no representation in the six
        //       bytes this field occupies.
        // WHY : Trade-offs: this rejects rather than saturating or truncating the high-order
        //       digits. Rejecting stops a mis-decoded rate at the point it entered, where the key
        //       that produced it is still in hand; carrying it forward would multiply a balance by
        //       a number the reference record cannot hold and post the result as interest, which
        //       is a plausible-looking amount that no comparison against the source data would
        //       flag as malformed.
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
        // WHY : Assumptions: the effective key is DERIVED here rather than accepted as an
        //       argument, so a caller cannot supply a pair the fallback could not have produced --
        //       a wholesale default key, for instance, which would resolve the rate of a different
        //       transaction type and category and so return a plausible number rather than fail.
        //       The null check the derivation needs is the compact constructor's, and it cannot
        //       run first because the derivation is an argument to it, so the receiver is tested
        //       here to keep the package's IllegalArgumentException in front of the
        //       NullPointerException a direct call would otherwise raise.
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
        // WHY : Assumptions: app/cbl/CBACT04C.cbl line 214, IF DIS-INT-RATE NOT = 0, gates BOTH
        //       statements it encloses -- line 215's 1300-COMPUTE-INTEREST, which is what writes
        //       the accrual record through 1300-B-WRITE-TX at line 468, and line 216's
        //       1400-COMPUTE-FEES -- so a zero rate produces no record, not a zero-amount record.
        //       Ignoring this predicate would emit one spurious transaction per zero-rate category
        //       balance, and the resulting parity failure reports as unexpected ROWS rather than as
        //       a wrong amount, which points a maintainer at the fixtures instead of at line 214.
        // WHY : Assumptions: the comparison is compareTo against zero and never equals.
        //       BigDecimal.equals compares scale as well as unscaled value, so 0, 0.0 and 0.00 are
        //       three unequal objects that are all numerically zero; the rate reaches this type
        //       from a NUMERIC(6,2) column through a driver that is free to hand back any of them,
        //       and although the compact constructor canonicalises to two places, an equals-based
        //       gate would be correct only for as long as that canonicalisation stays in place.
        //       compareTo answers the numeric question that line 214 asks, at any scale.
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
        // WHY : Alternatives Considered: answering with effectiveKey.isDefaultAccountGroup()
        //       alone, which is the shorter form and is wrong in one reachable case. An account
        //       whose own group identifier IS the DEFAULT group resolves its row on the first
        //       read, so app/cbl/CBACT04C.cbl line 422 sees status '00', line 436 does not fire
        //       and no substitution happens -- yet the effective key names the DEFAULT group, so
        //       the shorter form would report a fallback that never occurred. Requiring that the
        //       requested key did NOT already name that group asks the question line 436 actually
        //       asks, which is whether the MOVE at line 437 ran.
        // WHY : Assumptions: under the compact constructor's invariant this is exactly
        //       "the effective key differs from the requested key", since the only admitted
        //       difference IS the DEFAULT substitution. It is written in terms of the group
        //       component rather than as an inequality of whole keys because that is the component
        //       line 437 replaces, so the code names the thing that changed.
        return effectiveKey.isDefaultAccountGroup() && !requestedKey.isDefaultAccountGroup();
    }
}
