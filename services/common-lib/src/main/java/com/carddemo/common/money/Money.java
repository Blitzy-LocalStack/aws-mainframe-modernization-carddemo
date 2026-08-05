package com.carddemo.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Carries an exact monetary amount in memory and performs every arithmetic operation the migrated
 * CardDemo services perform on money.
 *
 * <p>Every balance, credit limit, cycle credit, cycle debit, transaction amount and accrued interest
 * figure anywhere in the migrated system is created, combined and compared through this type. It is
 * immutable, it is always held at a scale of exactly {@code SCALE} decimal places, and it never
 * represents an amount as an IEEE-754 binary value at any point in its life.</p>
 *
 * <h2>The normative declaration</h2>
 *
 * <p>One reference declaration fixes the shape of money for the whole migration. Line 7 of
 * {@code app/cpy/CVACT01Y.cpy}, the 300-byte account record, declares
 * {@code 05  ACCT-CURR-BAL                     PIC S9(10)V99.} -- ten integer digits, two decimal
 * digits and a sign. Lines 8, 9, 13 and 14 of the same record declare the credit limit, the cash
 * credit limit, the cycle credit and the cycle debit with that identical picture, so the widest money
 * field in the base masters is settled: twelve significant decimal digits at a scale of two, signed.
 * {@code MAX_MAGNITUDE} below is that domain expressed as a bound, and it is enforced on every value
 * this type admits.</p>
 *
 * <p>Assumptions: the base-master money fields are zoned decimal with a sign overpunch and are not
 * packed decimal. A search of all eleven base-master copybooks for a {@code COMP} usage or an
 * {@code OCCURS} clause returns no match, so no base-master money field is packed and none is an
 * array. Packed decimal does occur in the migration -- in the export record layout
 * {@code app/cpy/CVEXPORT.cpy} and pervasively in the two authorization segment layouts -- but never
 * in a base master. That split is why byte-level and character-level encoding of money lives in the
 * sibling {@code codec} package and in the reporting service, and none of it lives here: this type is
 * the in-memory value those renderings decode to and encode from.</p>
 *
 * <h2>Contract one: exact fixed point, never a binary approximation</h2>
 *
 * <p>Transformation rule T3 of the migration plan pins one representation per layer and admits no
 * exception: {@code NUMERIC(p,2)} in the database, {@link BigDecimal} carried at scale 2 in Java,
 * {@code Decimal} in the extract-transform-load code, and a JSON <em>string</em> on the wire. IEEE-754
 * binary arithmetic is excluded from the money path entirely -- neither of the language's two binary
 * primitive types, neither of their wrapper types, and never a bare JSON number either.</p>
 *
 * <p>Trade-offs: the excluded numeric type names are described in the preceding paragraph rather than
 * spelled. Spelling them would make this file match a search for the very tokens the money path must
 * not contain, and that search is one of the checks this tree is audited with, so a literal mention
 * would produce a hit that has to be explained away on every audit. The description is unambiguous,
 * since the language has exactly two IEEE-754 binary primitive types and one wrapper type for each.
 * The package descriptor beside this file states the same prohibition the same way, so the two are
 * consistent by construction.</p>
 *
 * <p>Assumptions: the scale is a property of the declaration and not a display preference. Two places
 * after an implied decimal point is what {@code PIC S9(10)V99} means, so a representation that is
 * exact at two places is a correctness requirement. The reason is concrete rather than theoretical:
 * the two-place decimal fractions such a field is built from, {@code 0.01} among them, have no finite
 * binary expansion, so a binary parse must approximate the value it was handed. An amount wrong in
 * its last cent flips an inclusive boundary comparison, and both posting boundaries in the reference
 * baseline are inclusive.</p>
 *
 * <h2>Contract two: two rounding contracts, and correction C-ROUNDING</h2>
 *
 * <p>This type exposes two distinct rounding contracts and does not collapse them into one.</p>
 *
 * <ul>
 *   <li>The <b>general contract</b> reduces a result to cents with {@code GENERAL_ROUNDING}, which is
 *       {@link RoundingMode#HALF_UP}. It governs {@link #of(BigDecimal)},
 *       {@link #multipliedBy(BigDecimal)} and {@link #dividedBy(BigDecimal)}.</li>
 *   <li>The <b>interest formula</b> reduces its quotient with a rounding mode the caller states
 *       explicitly, through {@link #monthlyInterest(BigDecimal, RoundingMode)}. No overload of it
 *       applies a mode the call site did not name, and the two named convenience forms,
 *       {@link #monthlyInterestTruncated(BigDecimal)} and {@link #monthlyInterestHalfUp(BigDecimal)},
 *       carry the chosen mode in their own names.</li>
 * </ul>
 *
 * <p>Assumptions: the baseline behaviour of the accrual path is truncation toward zero, derived from
 * the reference program rather than inferred from convention. Lines 462 to 468 of
 * {@code app/cbl/CBACT04C.cbl} hold the accrual paragraph, whose statement at lines 464 and 465 is
 * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. The receiving field is
 * declared at line 168 of the same program as {@code 05 WS-MONTHLY-INT            PIC S9(09)V99.}, so
 * the result is stored at exactly two decimal places and surplus precision has to go somewhere; and
 * the statement carries no {@code ROUNDED} phrase, nor does any other statement in the program. A
 * store into a fixed-scale field without that phrase discards the surplus digits rather than rounding
 * them.</p>
 *
 * <p>Trade-offs: the divergence between those two modes is registered as <b>C-ROUNDING</b>, and it is
 * named here so that a later reader does not reconcile this API back to a single mode without knowing
 * what the reconciliation costs. Two sibling documents state the money contract as scale 2 with
 * half-up unconditionally -- this module's own README and
 * {@code docs/architecture/data-model-and-schema-mapping.md} -- while the reference program truncates
 * on the accrual path specifically. Making the mode an explicit parameter of the accrual entry point
 * costs an accrual caller one more decision; what it buys is that the decision is visible at the call
 * site, named in the method name when a convenience form is used, and assertable by a test either
 * way. The alternative -- one mode, chosen here, applied silently -- costs nothing at the call site
 * and hides which of two documented behaviours the system actually implements.</p>
 *
 * <p>Trade-offs: the divergence is one cent, and only on a quotient landing exactly on a half cent, so
 * nobody should mistake C-ROUNDING for an unresolved question. On the vectors the reference fixtures
 * carry the two modes agree exactly -- a balance of {@code 1000.00} at a rate of {@code 2.50} yields
 * {@code 2.08333...} and both return {@code 2.08}. They part company only on an exact half cent, as
 * with {@code 1000.80} at {@code 2.50}, where the quotient is {@code 2.0850} exactly, truncation
 * returns {@code 2.08} and half-up returns {@code 2.09}. C-ROUNDING is therefore an API-contract
 * difference before it is ever a value difference.</p>
 *
 * <p>Alternatives Considered: collapsing the two contracts into a single half-up surface, recording
 * the baseline truncation in prose only. It has a real advantage -- one arithmetic surface, and one
 * architecture rule assertable without a carve-out -- and was not adopted because the accrual entry
 * point would then apply a mode no call site named, and the one behaviour a parity comparison against
 * the reference goldens most needs to reproduce would not be reachable through this type at all. Also
 * considered: an overload of {@link #monthlyInterest(BigDecimal, RoundingMode)} taking no mode and
 * defaulting to half-up. Rejected for the same reason in a smaller form; an overload that omits the
 * mode is exactly the silent application this contract exists to prevent.</p>
 *
 * <h2>Immutability and construction</h2>
 *
 * <p>Every instance is immutable and every instance is canonical: the single field is a
 * {@link BigDecimal} whose scale is always exactly {@code SCALE}, established once during construction
 * and never altered. Construction is private, and the three named factories
 * {@link #of(BigDecimal)}, {@link #of(String)} and {@link #ofCents(long)} are the only ways to obtain
 * a value.</p>
 *
 * <p>Trade-offs: this is a final class with a private constructor rather than a record. A record would
 * be shorter and would generate the component accessor, and its compact constructor could canonicalise
 * the value. It was not used because a record's canonical constructor is unavoidably part of the type's
 * API at the record's own access level, so {@code new Money(someDecimal)} would remain callable
 * alongside the factories -- and the distinction between {@link #of(BigDecimal)}, which interprets its
 * argument as an amount, and {@link #ofCents(long)}, which interprets its argument as an unscaled
 * count of cents, is exactly the distinction a nameless constructor erases. A private constructor puts
 * validation in one place and forces every call site to name which interpretation it means. The cost is
 * the explicit accessor, {@code equals}, {@code hashCode} and {@code toString} written out below, all
 * of which a record would have generated. Generating them with an annotation processor instead was
 * rejected because a generated member cannot carry the documentation this tree requires at every
 * visibility, and this module's Checkstyle configuration exempts no member from it.</p>
 *
 * <h2>What this type deliberately does not offer</h2>
 *
 * <p>Trade-offs: there is no operation that sums a series of unrounded values and reduces the total
 * once at the end. The omission is deliberate, because the reference accrual path reduces every term
 * before accumulating it. Line 467 of {@code app/cbl/CBACT04C.cbl} performs
 * {@code ADD WS-MONTHLY-INT  TO WS-TOTAL-INT} inside the accrual paragraph, so it fires once per
 * transaction category and each addend has already been stored into the two-place field at line 168;
 * line 200 resets the running total only on a change of account, guarded by the account-change test at
 * line 194; and line 352 adds the accumulated total to the account balance a single time, in
 * {@code 1050-UPDATE-ACCOUNT}. The account increment is therefore the sum of the reduced terms and not
 * the reduction of their unreduced sum, and those two quantities differ. An API offering only the
 * latter would silently produce a different account balance, which is why {@link #total(Money...)}
 * sums values that have already been reduced and performs no rounding of its own.</p>
 *
 * <p>Assumptions: no method here reads a clock, and no method here accepts a business date. The
 * reference accrual program takes its business date as a parameter -- {@code PARM-DATE PIC X(10)}
 * declared in the linkage section at lines 175 to 178 of {@code app/cbl/CBACT04C.cbl}, received by the
 * {@code PROCEDURE DIVISION USING EXTERNAL-PARMS} header at line 180 -- so reproducibility of an
 * accrual run is structural in the baseline rather than a matter of care. Nothing about a monetary
 * amount depends on the current instant, so this type has no date-bearing member for a caller to be
 * tempted to fill from a clock.</p>
 *
 * <p>Both reference programs cited throughout this file are read and cited only. They are never
 * edited, and no behaviour of theirs is altered in place.</p>
 */
public final class Money implements Comparable<Money> {

    /**
     * The number of decimal places every monetary amount is held at, which is two and never varies.
     *
     * <p>Assumptions: two is not a house preference, it is read off the normative declaration. Line 7
     * of {@code app/cpy/CVACT01Y.cpy} declares {@code PIC S9(10)V99}, and the {@code V99} of that
     * picture is an implied decimal point followed by exactly two digit positions. Every other money
     * field in the base masters carries the same {@code V99}, and the interest work field at line 168
     * of {@code app/cbl/CBACT04C.cbl} carries it too, as {@code PIC S9(09)V99}. A single scale
     * therefore describes every monetary value in the migration, which is why it is a constant here
     * rather than a parameter anywhere.</p>
     */
    public static final int SCALE = 2;

    /**
     * The rounding mode applied whenever a general monetary result must be reduced to cents.
     *
     * <p>Assumptions: this is the mode transformation rule T3 of the migration plan states for the
     * money path, and it governs {@link #of(BigDecimal)}, {@link #multipliedBy(BigDecimal)} and
     * {@link #dividedBy(BigDecimal)}. It deliberately does NOT govern
     * {@link #monthlyInterest(BigDecimal, RoundingMode)}, whose mode is supplied by the caller for the
     * reason recorded as C-ROUNDING on this class.</p>
     */
    public static final RoundingMode GENERAL_ROUNDING = RoundingMode.HALF_UP;

    /**
     * The rounding mode that reproduces the reference accrual program's own arithmetic exactly.
     *
     * <p>Assumptions: the reference program truncates toward zero on the accrual path, and this
     * constant names that behaviour so a caller wanting parity with the goldens does not have to
     * rediscover it. The derivation is on this class in full: the accrual statement at lines 464 and
     * 465 of {@code app/cbl/CBACT04C.cbl} stores into a two-place field declared at line 168 and
     * carries no {@code ROUNDED} phrase, and no statement anywhere in that program's 652 lines carries
     * one either.</p>
     *
     * <p>Alternatives Considered: {@link RoundingMode#FLOOR} was evaluated as the truncating mode and
     * rejected. The two modes agree on a positive value and disagree on a negative one, where
     * {@code DOWN} truncates toward zero while {@code FLOOR} moves away from it, and a negative value
     * is reachable here rather than hypothetical: both {@code PIC S9(09)V99} at line 168 and
     * {@code PIC S9(10)V99} at line 7 of {@code app/cpy/CVACT01Y.cpy} are SIGNED pictures, and the rate
     * itself is signed too, declared {@code PIC S9(04)V99} at line 9 of {@code app/cpy/CVTRA02Y.cpy}.
     * Truncation toward zero is what a store into a fixed-scale field does irrespective of sign, so
     * {@code DOWN} is the mode that matches and {@code FLOOR} is the mode that would diverge on exactly
     * the inputs a signed picture exists to admit.</p>
     */
    public static final RoundingMode BASELINE_INTEREST_ROUNDING = RoundingMode.DOWN;

    /**
     * The divisor that turns an annual percentage rate into a monthly fractional rate, which is 1200.
     *
     * <p>Assumptions: the literal is 1200 because that is the literal the reference statement uses, at
     * line 465 of {@code app/cbl/CBACT04C.cbl}. It carries two conversions at once, twelve months to
     * the year and a hundred to convert a percentage to a fraction, and it is preserved as the single
     * combined literal rather than split into its two factors so that the target performs one division
     * where the baseline performs one division.</p>
     */
    public static final BigDecimal MONTHLY_RATE_DIVISOR = new BigDecimal("1200");

    /**
     * The largest magnitude a monetary amount may carry, being the domain of {@code PIC S9(10)V99}.
     *
     * <p>Assumptions: ten integer digits and two decimal digits is the widest money picture in the base
     * masters, declared at line 7 of {@code app/cpy/CVACT01Y.cpy} and repeated at lines 8, 9, 13 and
     * 14, so {@code 9999999999.99} is the exact upper bound of what any money field in the reference
     * records can hold. The bound is applied to the magnitude rather than to the signed value because
     * the picture is signed and its negative domain is the mirror of its positive one.</p>
     *
     * <p>Trade-offs: enforcing this bound means an arithmetic result that overflows the reference domain
     * raises instead of being carried silently. The compromise is real -- a caller summing a large
     * enough series of report totals can be stopped by it -- and it is accepted because the alternative
     * is worse in a way that is hard to detect. An amount exceeding this bound cannot round-trip
     * through the twelve-byte zoned field or through the fifteen-character report mask; encoding it
     * would drop its high-order digits and yield a materially smaller number that still looks like
     * money. Raising at the point the value is formed names the problem where it can still be
     * diagnosed.</p>
     */
    public static final BigDecimal MAX_MAGNITUDE = new BigDecimal("9999999999.99");

    /**
     * The greatest number of characters an amount in text form may carry.
     *
     * <p>Alternatives Considered: admitting any length the transport delivered and relying on
     * {@code MAX_MAGNITUDE} to reject what does not belong was evaluated and rejected, because the
     * bound is reached too late. The domain check runs on a value that has already been built and
     * already been reduced to {@code SCALE}, and both of those steps cost work proportional to the
     * text they were handed. A bound applied to the characters costs one comparison and runs before
     * any of it.</p>
     *
     * <p>Assumptions: the widest well-formed value this type admits is a sign, ten integer digits, a
     * point and the fractional digits {@code MAX_INPUT_SCALE} allows, which is 26 characters. The
     * bound is set at 32 so that a value carrying a leading zero or two, which is exactly what a
     * fixed-width reference field produces, is still admitted rather than rejected for its padding.</p>
     */
    public static final int MAX_INPUT_LENGTH = 32;

    /**
     * The greatest number of fractional digits a caller-supplied value may carry before reduction.
     *
     * <p>Assumptions: a value wider than {@code SCALE} is reduced rather than refused, which is this
     * type's general contract, so some tolerance above two is required. Fifteen is chosen because it
     * is wider than every fractional field in the reference records -- the widest is the two decimal
     * digits of {@code PIC S9(10)V99} and the interest rate's two at line 7 of
     * {@code app/cpy/CVTRA02Y.cpy} -- and wider than any intermediate the interest division produces,
     * while remaining small enough that reduction is a constant-cost operation.</p>
     *
     * <p>Trade-offs: the bound converts a class of denial-of-service input into a rejected value. A
     * value declaring an enormous number of fractional places, whether written as digits or implied by
     * exponent notation, forces the reduction to materialise every one of those places before it can
     * discard them; a request carrying such a value would occupy processor time and heap far out of
     * proportion to its size. The cost accepted is that a caller with a legitimate need for more than
     * fifteen fractional places must reduce the value itself first, and no such caller exists in this
     * migration.</p>
     */
    public static final int MAX_INPUT_SCALE = 15;

    /**
     * The greatest number of significant digits a caller-supplied value may carry before reduction.
     *
     * <p>Assumptions: twelve significant digits is the reference domain, being the ten integer and two
     * decimal digits of {@code PIC S9(10)V99}. The bound here is set at {@code MAX_INPUT_SCALE} plus
     * those twelve so that a value inside the domain carrying the widest admitted fraction still
     * passes, and so that the check bounds work rather than duplicating the domain check that
     * {@code MAX_MAGNITUDE} already performs on the reduced value.</p>
     */
    public static final int MAX_INPUT_PRECISION = MAX_INPUT_SCALE + 12;

    /**
     * The one grammar an amount in text form may take: an optional sign, digits, and a bounded
     * fraction, with no exponent.
     *
     * <p>Alternatives Considered: relying on the exact decimal type's own syntax and rejecting
     * afterwards whatever fell outside the domain. Rejected because that syntax admits exponent
     * notation, and an exponent lets four characters declare a value whose reduction to cents would
     * have to produce billions of digit positions -- a disproportionate allocation provoked by a
     * payload smaller than this sentence. This grammar admits no exponent at all, so the digit count
     * of a value is bounded by the character count of the text that carried it.</p>
     *
     * <p>Assumptions: a leading plus sign is admitted because the reference report masks emit one --
     * {@code +ZZZ,ZZZ,ZZZ.ZZ} at {@code app/cpy/CVTRA07Y.cpy} -- and a value read back from such a
     * rendering would otherwise be refused for a character the reference itself wrote. A bare decimal
     * point with no integer digit is not admitted, because every reference field declares its integer
     * positions and a value that declares none did not come from one.</p>
     */
    private static final Pattern PLAIN_DECIMAL =
            Pattern.compile("[+-]?[0-9]{1,20}(\\.[0-9]{1," + MAX_INPUT_SCALE + "})?");

    /**
     * The zero amount, canonical at {@code SCALE} decimal places.
     *
     * <p>Assumptions: a shared constant is safe to expose because this type is immutable, so no caller
     * can mutate the instance others hold. It is declared after {@code MAX_MAGNITUDE} deliberately,
     * because the constructor it invokes validates against that bound and static initialisers run in
     * declaration order; reordering the two would validate against a null bound.</p>
     */
    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE));

    /**
     * The amount, always non-null and always at exactly {@code SCALE} decimal places.
     */
    private final BigDecimal amount;

    /**
     * Wraps an amount that has already been reduced to the canonical scale.
     *
     * <p>Assumptions: every caller of this constructor has already applied {@code SCALE} and a rounding
     * mode, so this constructor rounds nothing. That division of labour is what lets the accrual path
     * apply the caller's mode and the general path apply {@code GENERAL_ROUNDING} while both end up
     * holding an identically canonical value.</p>
     *
     * @param canonicalAmount the amount to wrap, which must already carry exactly {@code SCALE} decimal
     *     places; callers reach this constructor only through a factory or an arithmetic method that
     *     has established that
     * @throws ArithmeticException if the magnitude of {@code canonicalAmount} exceeds
     *     {@code MAX_MAGNITUDE}, or if its scale is not exactly {@code SCALE}
     */
    private Money(BigDecimal canonicalAmount) {
        // WHY : Assumptions: the scale assertion guards an internal contract rather than user input,
        //       and it is kept because it is the one check that would catch a future arithmetic method
        //       added here that forgot to reduce its result. Without it such a method would produce a
        //       Money whose scale silently differed from every other instance, and equality and the
        //       cent accessor both depend on the scale being uniform.
        if (canonicalAmount.scale() != SCALE) {
            throw new ArithmeticException(
                    "amount must be reduced to scale " + SCALE + " before construction, but scale was "
                            + canonicalAmount.scale());
        }

        this.amount = requireWithinDomain(canonicalAmount);
    }


    /**
     * Creates an amount from a decimal value, reducing it to cents under the general contract.
     *
     * @param value the amount to carry; must not be {@code null}. A value carrying more than
     *     {@code SCALE} decimal places is reduced with {@code GENERAL_ROUNDING}, and a value carrying
     *     fewer is padded, so the result is canonical either way
     * @return an immutable amount at exactly {@code SCALE} decimal places
     * @throws NullPointerException if {@code value} is {@code null}, because a monetary field under this
     *     contract has no representation for an absent amount and a caller holding one has a defect to
     *     address rather than a zero to substitute
     * @throws ArithmeticException if {@code value} declares a scale outside the range bounded by
     *     {@code MAX_INPUT_SCALE} and {@code MAX_INPUT_PRECISION}, or a precision above
     *     {@code MAX_INPUT_PRECISION}, both of which are checked before any reduction is attempted; or
     *     if the magnitude of the reduced value exceeds {@code MAX_MAGNITUDE}
     */
    public static Money of(BigDecimal value) {
        return new Money(canonicalize(value, "value"));
    }

    /**
     * Creates an amount from its decimal text form, reducing it to cents under the general contract.
     *
     * <p>This is the factory the JSON wire form reads through, since the wire carries money as a string
     * rather than as a number.</p>
     *
     * @param text the amount in decimal text, such as {@code "-1234.56"}; must not be {@code null},
     *     must be at most {@code MAX_INPUT_LENGTH} characters, and must match the plain-decimal
     *     grammar of an optional sign, at least one digit and at most {@code MAX_INPUT_SCALE} digits
     *     after a single decimal point. Exponent notation is not admitted
     * @return an immutable amount at exactly {@code SCALE} decimal places
     * @throws NullPointerException if {@code text} is {@code null}
     * @throws NumberFormatException if {@code text} is longer than {@code MAX_INPUT_LENGTH} or is not
     *     a plain decimal amount, which is raised rather than absorbed so that a malformed payload is
     *     reported at the boundary that received it instead of becoming a zero further in. Neither
     *     message quotes {@code text}, because the value is caller-supplied and the message is bound
     *     for a log
     * @throws ArithmeticException if the magnitude of the reduced value exceeds {@code MAX_MAGNITUDE}
     */
    public static Money of(String text) {
        Objects.requireNonNull(text, "text must not be null");

        // WHY : Assumptions: the length is checked before the grammar and the grammar before the
        //       parse, so the amount of work an untrusted value can provoke is settled by two
        //       comparisons on characters. The order is the point: the exact decimal type's string
        //       constructor accepts exponent notation, and an exponent is a compact way to declare an
        //       enormous number of digit positions -- a handful of characters can ask for a value that
        //       the reduction below would have to materialise place by place before it could discard
        //       any of them. Rejecting on shape first means such a value never reaches a constructor
        //       at all.
        if (text.length() > MAX_INPUT_LENGTH) {
            throw new NumberFormatException(
                    "monetary text is " + text.length() + " characters, exceeding the "
                            + MAX_INPUT_LENGTH + " a plain decimal amount may carry");
        }

        // WHY : Alternatives Considered: parsing with a locale-aware decimal formatter was evaluated
        //       and rejected. The wire form of this contract is a fixed, locale-independent decimal
        //       string, and a locale-aware parse would read the group separator of the report mask as
        //       a decimal point under a locale that uses the comma that way, turning a thousand into
        //       one. The plain-decimal grammar below accepts exactly one syntax in every locale,
        //       which is the property wanted here.
        //       Alternatives Considered: catching the failure the reduction raises on an
        //       exponent-notation value, rather than refusing the notation up front. Rejected because
        //       the failure arrives only after the work has been done, so the cost is paid whether the
        //       value is accepted or not.
        if (!PLAIN_DECIMAL.matcher(text).matches()) {
            throw new NumberFormatException(
                    "monetary text is not a plain decimal amount; the accepted form is an optional"
                            + " sign, at least one digit, and at most " + MAX_INPUT_SCALE
                            + " digits after a single decimal point, with no exponent");
        }

        return of(new BigDecimal(text));
    }

    /**
     * Creates an amount from an unscaled count of cents, without rounding anything.
     *
     * <p>Assumptions: this is the factory the zoned and packed decoders in the sibling {@code codec}
     * package read through. Both of those forms carry an integer with an implied decimal point rather
     * than a real one -- the {@code V} of {@code PIC S9(10)V99} occupies no byte -- so a decoder
     * naturally produces the digits as a whole number and the scale separately. Interpreting that whole
     * number here, rather than having each decoder build a decimal string and re-parse it, keeps the
     * conversion exact and keeps the implied point in one place.</p>
     *
     * @param cents the amount expressed as a whole number of cents, so {@code -206501} means
     *     {@code -2065.01}; the sign is carried by the argument itself
     * @return an immutable amount at exactly {@code SCALE} decimal places
     * @throws ArithmeticException if the resulting magnitude exceeds {@code MAX_MAGNITUDE}
     */
    public static Money ofCents(long cents) {
        // WHY : Assumptions: BigDecimal.valueOf(long, int) applies the scale as an implied decimal
        //       point rather than as a division, so it is exact by construction and no rounding mode
        //       is involved. Dividing by a hundred instead would introduce a quotient and therefore a
        //       rounding decision where the encoding has none.
        return new Money(BigDecimal.valueOf(cents, SCALE));
    }

    /**
     * Returns the amount as a decimal value at exactly {@code SCALE} decimal places.
     *
     * @return the amount, never {@code null} and never at any other scale
     */
    public BigDecimal amount() {
        return amount;
    }

    /**
     * Returns the amount as a whole number of cents, exactly and without rounding.
     *
     * <p>Assumptions: the conversion cannot lose information, because the scale is invariantly
     * {@code SCALE} and the magnitude is bounded by {@code MAX_MAGNITUDE}, so the unscaled value is at
     * most twelve digits and every twelve-digit integer is representable in the return type.</p>
     *
     * @return the amount in cents, so {@code -2065.01} yields {@code -206501}
     */
    public long unscaledCents() {
        return amount.unscaledValue().longValueExact();
    }

    /**
     * Reports whether this amount is exactly zero.
     *
     * @return {@code true} when the amount is zero
     */
    public boolean isZero() {
        return amount.signum() == 0;
    }

    /**
     * Reports whether this amount is below zero.
     *
     * @return {@code true} when the amount is negative
     */
    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /**
     * Reports whether this amount is above zero.
     *
     * @return {@code true} when the amount is positive
     */
    public boolean isPositive() {
        return amount.signum() > 0;
    }


    /**
     * Adds another amount to this one, exactly and without rounding.
     *
     * <p>Assumptions: the addition cannot round, and that property is what makes it the right primitive
     * for the reference accrual accumulator. Adding two values that both carry {@code SCALE} decimal
     * places yields a value carrying {@code SCALE} decimal places with no digits discarded, so a series
     * of these additions reproduces line 467 of {@code app/cbl/CBACT04C.cbl},
     * {@code ADD WS-MONTHLY-INT  TO WS-TOTAL-INT}, term for term. See {@link #total(Money...)} for the
     * reason no operation here rounds a total instead.</p>
     *
     * @param addend the amount to add; must not be {@code null}
     * @return a new amount that is the exact sum, at {@code SCALE} decimal places
     * @throws NullPointerException if {@code addend} is {@code null}
     * @throws ArithmeticException if the magnitude of the sum exceeds {@code MAX_MAGNITUDE}
     */
    public Money plus(Money addend) {
        Objects.requireNonNull(addend, "addend must not be null");

        return new Money(amount.add(addend.amount));
    }

    /**
     * Subtracts another amount from this one, exactly and without rounding.
     *
     * <p>Assumptions: the reference posting path needs an exact difference rather than a rounded one.
     * Lines 403 to 405 of {@code app/cbl/CBTRN02C.cbl} compute the projected balance as the cycle credit
     * less the cycle debit plus the transaction amount, and that projection is then compared against the
     * credit limit at line 407. A difference rounded anywhere in that chain could move the projection
     * across an inclusive boundary by one cent, which is the whole quantity the boundary turns on.</p>
     *
     * @param subtrahend the amount to subtract; must not be {@code null}
     * @return a new amount that is the exact difference, at {@code SCALE} decimal places
     * @throws NullPointerException if {@code subtrahend} is {@code null}
     * @throws ArithmeticException if the magnitude of the difference exceeds {@code MAX_MAGNITUDE}
     */
    public Money minus(Money subtrahend) {
        Objects.requireNonNull(subtrahend, "subtrahend must not be null");

        return new Money(amount.subtract(subtrahend.amount));
    }

    /**
     * Multiplies this amount by a factor and reduces the product to cents under the general contract.
     *
     * <p>Assumptions: this is the general-contract multiplication and it applies
     * {@code GENERAL_ROUNDING}. It is deliberately NOT the operation the accrual formula uses. An
     * accrual caller that multiplied by a rate here and then divided by
     * {@code MONTHLY_RATE_DIVISOR} would reduce the intermediate product to cents before the division
     * consumed it, which changes the result; {@link #monthlyInterest(BigDecimal, RoundingMode)} exists
     * precisely so that the product is never reduced mid-formula.</p>
     *
     * @param factor the value to multiply by; must not be {@code null}. It may carry any scale, since
     *     the product is reduced afterwards
     * @return a new amount that is the reduced product, at {@code SCALE} decimal places
     * @throws NullPointerException if {@code factor} is {@code null}
     * @throws ArithmeticException if the magnitude of the reduced product exceeds
     *     {@code MAX_MAGNITUDE}
     */
    public Money multipliedBy(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor must not be null");

        return new Money(amount.multiply(factor).setScale(SCALE, GENERAL_ROUNDING));
    }

    /**
     * Divides this amount by a divisor and reduces the quotient to cents under the general contract.
     *
     * @param divisor the value to divide by; must not be {@code null} and must not be zero
     * @return a new amount that is the reduced quotient, at {@code SCALE} decimal places
     * @throws NullPointerException if {@code divisor} is {@code null}
     * @throws ArithmeticException if {@code divisor} is zero, or if the magnitude of the reduced
     *     quotient exceeds {@code MAX_MAGNITUDE}
     */
    public Money dividedBy(BigDecimal divisor) {
        Objects.requireNonNull(divisor, "divisor must not be null");

        // WHY : Alternatives Considered: returning zero on a zero divisor was evaluated and rejected.
        //       A zero divisor in a monetary calculation means the caller's own inputs are inconsistent
        //       -- a per-unit figure with no units, or a rate with no period -- and substituting zero
        //       would post a real amount of nothing to a real account while reporting success. The
        //       scaled divide raises on a zero divisor of its own accord, so the exception is the
        //       platform's rather than a local invention.
        return new Money(amount.divide(divisor, SCALE, GENERAL_ROUNDING));
    }

    /**
     * Returns this amount with its sign inverted.
     *
     * @return a new amount of equal magnitude and opposite sign, at {@code SCALE} decimal places
     */
    public Money negated() {
        // WHY : Assumptions: BigDecimal.negate preserves the scale of its operand, so the result is
        //       already canonical and no reduction is needed. The domain bound is symmetric about zero
        //       because the reference picture is signed, so negating a value that was admissible cannot
        //       produce one that is not.
        return new Money(amount.negate());
    }

    /**
     * Returns the magnitude of this amount, discarding its sign.
     *
     * @return a new amount that is the absolute value, at {@code SCALE} decimal places
     */
    public Money absoluteValue() {
        return new Money(amount.abs());
    }

    /**
     * Sums amounts that have each already been reduced to cents, adding no rounding of its own.
     *
     * <p>Trade-offs: this is an accumulator and emphatically not a reduce-at-the-end operation, and the
     * distinction is the reason no method with the latter behaviour exists on this type. The reference
     * accrual path reduces every term before accumulating it: line 467 of
     * {@code app/cbl/CBACT04C.cbl} performs {@code ADD WS-MONTHLY-INT  TO WS-TOTAL-INT} inside the
     * accrual paragraph, so it fires once per transaction category with an addend that has already been
     * stored into the two-place field declared at line 168. Line 200 resets the running total only on a
     * change of account, guarded by the account-change test at line 194, and line 352 adds the
     * accumulated total to the account balance once, in {@code 1050-UPDATE-ACCOUNT}. The account
     * increment is therefore the sum of the reduced terms; the reduction of their unreduced sum is a
     * different quantity, and a helper computing it would move a real balance by real cents while
     * looking like a convenience. What is given up by omitting that helper is that a caller wanting it
     * must be explicit about wanting it, and that is the intended cost.</p>
     *
     * @param amounts the amounts to sum; neither the array nor any element may be {@code null}. An empty
     *     invocation is legal and yields {@code ZERO}, which matches the reference program's own reset
     *     of its accumulator to zero before the first term of an account
     * @return a new amount that is the exact sum of the arguments, at {@code SCALE} decimal places
     * @throws NullPointerException if {@code amounts} is {@code null} or contains a {@code null} element
     * @throws ArithmeticException if the magnitude of the running sum exceeds {@code MAX_MAGNITUDE} at
     *     any point, which is checked on each addition rather than only on the result
     */
    public static Money total(Money... amounts) {
        Objects.requireNonNull(amounts, "amounts must not be null");

        Money running = ZERO;
        for (Money addend : amounts) {
            // WHY : Assumptions: plus performs the domain check on every intermediate, so an overflow is
            //       reported at the term that caused it rather than at the end. Summing the unscaled
            //       cents into a primitive integer accumulator instead would be marginally cheaper and
            //       would lose that: the primitive would wrap silently on overflow, turning a total too
            //       large for the reference field into a plausible negative amount.
            running = running.plus(addend);
        }

        return running;
    }


    /**
     * Computes one month's interest on this balance at an annual percentage rate, reducing the quotient
     * with a rounding mode the caller states.
     *
     * <p>This reproduces the reference accrual statement at lines 464 and 465 of
     * {@code app/cbl/CBACT04C.cbl}, {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) /
     * 1200}, with the order of its two operations preserved literally.</p>
     *
     * <p>Trade-offs: the product is formed at full precision and the single reduction happens at the
     * division, never before it. Reordering to divide first is forbidden by transformation rule T4 of
     * the migration plan, which requires the arithmetic order to be preserved, and the reason is
     * arithmetic rather than stylistic. The rate is itself a two-place value -- {@code DIS-INT-RATE} is
     * declared {@code PIC S9(04)V99} at line 9 of {@code app/cpy/CVTRA02Y.cpy} -- and the balance is a
     * two-place value, so their raw product carries FOUR decimal places before the division consumes it.
     * Reducing that product to two places first discards two digits the division would otherwise have
     * used, and no rounding mode applied afterwards recovers them: on a balance of {@code 1000.80} at a
     * rate of {@code 2.50}, multiplying first yields {@code 2.0850} while dividing first at an
     * intermediate two places yields {@code 2.0750}, a discrepancy of two cents from the reordering
     * alone. The multiplication is parenthesised in the reference source itself at line 465, so this
     * order is a preserved instruction and not an inference. What the full-precision product costs is a
     * wider intermediate; what it buys is the only result the reference goldens will agree with.</p>
     *
     * <p>Assumptions: the mode is a required parameter rather than a default, which is the API-contract
     * half of the divergence recorded as C-ROUNDING on this class. Pass {@code BASELINE_INTEREST_ROUNDING}
     * to reproduce the reference program's own truncation, or {@code GENERAL_ROUNDING} to apply the mode
     * the migration plan states for the money path generally. The two named forms
     * {@link #monthlyInterestTruncated(BigDecimal)} and {@link #monthlyInterestHalfUp(BigDecimal)} carry
     * that choice in their names for call sites that would rather not restate it.</p>
     *
     * @param annualRatePercentage the annual rate as a percentage, so {@code 15.00} means fifteen per
     *     cent; must not be {@code null}. It is a percentage and not a fraction because
     *     {@code MONTHLY_RATE_DIVISOR} carries the conversion, exactly as the reference literal does
     * @param roundingMode the mode applied to the quotient, and applied nowhere else in this
     *     computation; must not be {@code null}
     * @return one month's interest on this balance, at {@code SCALE} decimal places
     * @throws NullPointerException if {@code annualRatePercentage} or {@code roundingMode} is
     *     {@code null}
     * @throws ArithmeticException if the magnitude of the result exceeds {@code MAX_MAGNITUDE}
     */
    public Money monthlyInterest(BigDecimal annualRatePercentage, RoundingMode roundingMode) {
        Objects.requireNonNull(annualRatePercentage, "annualRatePercentage must not be null");
        Objects.requireNonNull(roundingMode, "roundingMode must not be null");

        // WHY : Assumptions: BigDecimal.multiply sets the product's scale to the sum of the operand
        //       scales, so a two-place balance times a two-place rate yields a four-place product with
        //       every digit retained. That is the intermediate the reference statement's parenthesised
        //       multiplication produces, and retaining it is what the next line then reduces exactly
        //       once.
        BigDecimal product = amount.multiply(annualRatePercentage);

        // WHY : Trade-offs: the scaled divide is used rather than an unscaled divide followed by a
        //       separate reduction. An unscaled divide would raise on any quotient with a non-terminating
        //       expansion, and this quotient frequently has one -- a balance of 1000.00 at a rate of 2.50
        //       gives 2.08333... -- so the alternative would fail on ordinary inputs. Supplying the scale
        //       and the mode to the division itself also makes it structurally impossible for a second
        //       rounding to occur later in this method, which is the property the preserved arithmetic
        //       order depends on.
        BigDecimal monthlyInterest = product.divide(MONTHLY_RATE_DIVISOR, SCALE, roundingMode);

        return new Money(monthlyInterest);
    }

    /**
     * Computes one month's interest on this balance, truncating toward zero as the reference program
     * does.
     *
     * <p>Assumptions: truncation toward zero is the reference accrual program's own behaviour, derived
     * on {@code BASELINE_INTEREST_ROUNDING} above from the absence of any {@code ROUNDED} phrase across
     * all 652 lines of {@code app/cbl/CBACT04C.cbl} together with the two-place receiving field declared
     * at line 168. This is the form to call when a result must agree with the reference goldens.</p>
     *
     * @param annualRatePercentage the annual rate as a percentage; must not be {@code null}
     * @return one month's interest on this balance, truncated toward zero, at {@code SCALE} decimal
     *     places
     * @throws NullPointerException if {@code annualRatePercentage} is {@code null}
     * @throws ArithmeticException if the magnitude of the result exceeds {@code MAX_MAGNITUDE}
     */
    public Money monthlyInterestTruncated(BigDecimal annualRatePercentage) {
        return monthlyInterest(annualRatePercentage, BASELINE_INTEREST_ROUNDING);
    }

    /**
     * Computes one month's interest on this balance, rounding the quotient half up.
     *
     * <p>Assumptions: half up is the mode transformation rule T3 of the migration plan states for the
     * money path generally, so this is the plan-aligned form. It differs from
     * {@link #monthlyInterestTruncated(BigDecimal)} by at most one cent, and only where the quotient
     * lands exactly on a half cent; the divergence and its measured vectors are recorded as C-ROUNDING
     * on this class.</p>
     *
     * @param annualRatePercentage the annual rate as a percentage; must not be {@code null}
     * @return one month's interest on this balance, rounded half up, at {@code SCALE} decimal places
     * @throws NullPointerException if {@code annualRatePercentage} is {@code null}
     * @throws ArithmeticException if the magnitude of the result exceeds {@code MAX_MAGNITUDE}
     */
    public Money monthlyInterestHalfUp(BigDecimal annualRatePercentage) {
        return monthlyInterest(annualRatePercentage, GENERAL_ROUNDING);
    }


    /**
     * Reports whether this projected balance is permitted by a credit limit, inclusively.
     *
     * <p>Assumptions: this is the reference guard transcribed literally. Line 407 of
     * {@code app/cbl/CBTRN02C.cbl} reads {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL}, with the
     * projected balance on the right, so the comparison is {@code limit >= projected} and it is
     * INCLUSIVE. A projected balance landing exactly on the limit passes. The projected balance itself
     * is accumulated at lines 403 to 405 from the CYCLE accumulators and the transaction amount -- the
     * cycle credit less the cycle debit plus the amount -- and NOT from the current balance, which is a
     * distinct field of the same record; a caller computing the projection from
     * {@code ACCT-CURR-BAL} would be comparing the wrong quantity however correct this operator is.</p>
     *
     * <p>Assumptions: the boundary is directly observable in the reference fixtures rather than only in
     * the source. In {@code tests/fixtures/posting/boundary_exact_limit} the account carries a credit
     * limit of {@code +2065.00} with both cycle accumulators at zero and the driving transaction carries
     * {@code +2065.00}, so the projection is {@code 2065.00}, the comparison holds and the transaction
     * posts. Its sibling scenario {@code reject_102_overlimit} differs only in the transaction amount,
     * {@code +2065.01}, so the projection is {@code 2065.01}, the comparison fails and the transaction
     * is rejected. The first fixture's own notes state the conclusion in the same terms, that
     * documenting both sides pins the operator to the inclusive form rather than the strict one.</p>
     *
     * @param creditLimit the limit to test against, being the account credit limit; must not be
     *     {@code null}
     * @return {@code true} when this projected balance is permitted, including the case where it equals
     *     the limit exactly
     * @throws NullPointerException if {@code creditLimit} is {@code null}
     */
    public boolean isWithinLimit(Money creditLimit) {
        Objects.requireNonNull(creditLimit, "creditLimit must not be null");

        // WHY : Assumptions: the operands are ordered limit-first to mirror the reference statement's own
        //       operand order at line 407, so the two can be read side by side without mentally
        //       transposing them. compareTo is used rather than equality plus an ordering test because it
        //       ignores scale, and while every instance here is canonical at SCALE that invariant is then
        //       not something this comparison has to depend on.
        return creditLimit.amount.compareTo(this.amount) >= 0;
    }

    /**
     * Reports whether this projected balance breaches a credit limit, strictly.
     *
     * <p>Assumptions: the reject condition is STRICTLY GREATER. It is the exact negation of the
     * inclusive guard at line 407 of {@code app/cbl/CBTRN02C.cbl} described on
     * {@link #isWithinLimit(Money)}: because the guard is {@code limit >= projected}, the reject arm at
     * lines 409 to 413 is reached only when {@code projected} is strictly greater than {@code limit}. A
     * reject predicate written with an inclusive operator would reject the exactly-at-limit transaction
     * that the reference posts, so the two statements have to be held together -- the PASS test is
     * inclusive, therefore the REJECT test is strict.</p>
     *
     * <p>Assumptions: a caller acting on this result must not order the over-limit and expiration
     * checks with a short-circuit, because the reference program does not. Its validation entry point at
     * lines 370 to 378 DOES short-circuit the first two reasons: line 372 guards the account lookup at
     * line 373 with a test that the failure reason is still zero, so reason 100 suppresses reason 101.
     * Inside the account lookup, however, the over-limit test at lines 407 to 413 and the expiration
     * test at lines 414 to 420 are two SEQUENTIAL and UNGUARDED blocks -- line 413 closes the first and
     * line 414 opens the second with no test of the reason between them. When a transaction breaches
     * both, the assignment of reason 103 at line 417 therefore OVERWRITES the assignment of reason 102
     * at line 410, and the reported reason is 103. Expressing that chain in the target as a conditional
     * followed by an else-if in declaration order would report 102 instead and diverge from the
     * reference on exactly the transactions that fail both tests. The reference messages for the two
     * are {@code OVERLIMIT TRANSACTION} at line 411 and
     * {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION} at line 418, carried across
     * character for character.</p>
     *
     * @param creditLimit the limit to test against, being the account credit limit; must not be
     *     {@code null}
     * @return {@code true} when this projected balance strictly exceeds the limit, and therefore
     *     {@code false} when it equals the limit exactly
     * @throws NullPointerException if {@code creditLimit} is {@code null}
     */
    public boolean exceeds(Money creditLimit) {
        // WHY : Trade-offs: this is written as the negation of the guard rather than as an independent
        //       strict comparison, which costs one indirection and buys the guarantee that the two can
        //       never disagree. Two hand-written comparisons are how an inclusive guard and a strict
        //       reject drift into overlapping or leaving a gap at the boundary during a later edit, and
        //       the boundary is the one value both exist to decide. The null check is performed by the
        //       guard, so it is not repeated here.
        return !isWithinLimit(creditLimit);
    }

    /**
     * Orders this amount against another by numeric value.
     *
     * @param other the amount to compare against; must not be {@code null}
     * @return a negative integer, zero or a positive integer as this amount is less than, equal to or
     *     greater than {@code other}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    @Override
    public int compareTo(Money other) {
        Objects.requireNonNull(other, "other must not be null");

        return amount.compareTo(other.amount);
    }

    /**
     * Compares this amount with another object for numeric equality.
     *
     * <p>Assumptions: equality is decided by numeric comparison rather than by the underlying decimal's
     * own equality, which additionally requires equal scales. Every instance of this type is canonical
     * at {@code SCALE}, so the two definitions agree today; numeric comparison is used because it
     * continues to be correct if that invariant is ever reached by a different route, whereas
     * scale-sensitive equality would begin reporting two equal amounts as different.</p>
     *
     * @param other the object to compare against, which may be {@code null} or of any type
     * @return {@code true} when {@code other} is a {@code Money} of the same numeric value
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        // WHY : Assumptions: the pattern form binds the narrowed reference in the same test that
        //       establishes the type, so the cast cannot be written against a type the test did not
        //       check. A separate test and cast is the shape in which those two drift apart during a
        //       later edit, and this class compiles at a language level where the pattern form is
        //       available.
        if (!(other instanceof Money that)) {
            return false;
        }

        return amount.compareTo(that.amount) == 0;
    }

    /**
     * Returns a hash code consistent with numeric equality.
     *
     * <p>Assumptions: the hash is derived from the amount in cents rather than from the underlying
     * decimal's own hash, and the substitution is required rather than cosmetic. That decimal's hash
     * incorporates its scale, so it would be inconsistent with the scale-insensitive equality defined
     * above; the cent value is scale-independent by construction and is exact for every amount this
     * type admits, so equal amounts necessarily hash alike.</p>
     *
     * @return the hash code for this amount
     */
    @Override
    public int hashCode() {
        return Long.hashCode(unscaledCents());
    }

    /**
     * Renders this amount as plain decimal text, which is also its JSON wire form.
     *
     * <p>Assumptions: the rendering never uses exponent notation and always shows exactly
     * {@code SCALE} decimal places, so {@code 0} renders as {@code 0.00} and a negative amount carries a
     * leading minus. This is the form the sibling module serialises to and the form
     * {@link #of(String)} reads back, so a value that makes a round trip through JSON returns
     * identical.</p>
     *
     * @return the amount as plain decimal text, never {@code null}
     */
    public String toPlainString() {
        return amount.toPlainString();
    }

    /**
     * Renders this amount for logs and diagnostics, identically to its wire form.
     *
     * <p>Alternatives Considered: the conventional value-object rendering, which wraps the value in the
     * type name and the field name, was evaluated and rejected. A monetary amount is read far more often
     * in a log line beside a reference field value than it is read as a Java object, and the surrounding
     * decoration has to be stripped by eye every time that comparison is made. Emitting exactly the wire
     * form means a log line, a JSON payload and a decoded reference record all show the same characters
     * for the same amount. The cost is that a log line must name the amount itself, since the rendering
     * no longer names its own type.</p>
     *
     * @return the amount as plain decimal text, never {@code null}
     */
    @Override
    public String toString() {
        return toPlainString();
    }


    /**
     * Reduces a caller-supplied value to the canonical scale under the general contract.
     *
     * <p>Assumptions: the parameter name is passed in rather than hard-coded so that the failure message
     * names the argument the caller actually wrote. The two public factories reaching this helper accept
     * differently named arguments, and a message naming a parameter that does not appear at the call site
     * sends a maintainer to read this class instead of their own.</p>
     *
     * @param value the value to reduce; must not be {@code null}
     * @param parameterName the name of the caller's parameter, used only to compose the failure message
     * @return the value at exactly {@code SCALE} decimal places
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws ArithmeticException if {@code value} declares a scale or a precision outside the bounds
     *     {@code MAX_INPUT_SCALE} and {@code MAX_INPUT_PRECISION} set, which is checked before the
     *     reduction so that an extreme declaration is refused rather than materialised
     */
    private static BigDecimal canonicalize(BigDecimal value, String parameterName) {
        Objects.requireNonNull(value, parameterName + " must not be null");

        // WHY : Assumptions: the shape of the value is bounded BEFORE it is reduced, and the order is
        //       load-bearing rather than tidy. Reduction to SCALE costs work proportional to the digit
        //       positions the value declares, so a value declaring an extreme scale or an extreme
        //       exponent -- which a caller can construct directly, without passing through the text
        //       factory's grammar -- would consume processor time and heap in the reduction itself,
        //       before any domain check could reject it. Checking the declared scale and precision
        //       costs two comparisons on metadata the value already carries.
        //       Alternatives Considered: bounding only through MAX_MAGNITUDE on the reduced value.
        //       Rejected because that bound is reached after the expensive step, so it detects the
        //       value without preventing the cost of admitting it.
        if (value.scale() > MAX_INPUT_SCALE || value.scale() < -MAX_INPUT_PRECISION
                || value.precision() > MAX_INPUT_PRECISION) {
            // WHY : Trade-offs: the message reports the declared scale and precision and never the
            //       digits. Those two numbers are what identify the defect -- a value arriving with a
            //       scale of nine figures came from a mis-parsed exponent, not from a record -- while
            //       the digits themselves could be an unbounded quantity of attacker-chosen text
            //       heading for a log, which is the exposure this whole check exists to remove.
            throw new ArithmeticException(
                    parameterName + " declares scale " + value.scale() + " and precision "
                            + value.precision() + ", outside the admitted scale of at most "
                            + MAX_INPUT_SCALE + " and precision of at most " + MAX_INPUT_PRECISION);
        }

        // WHY : Assumptions: setScale with an explicit mode is used rather than a bare setScale. The bare
        //       form raises whenever a reduction would discard a non-zero digit, which would make a
        //       three-place input an error instead of an amount; naming the mode makes the reduction the
        //       documented general-contract behaviour and keeps the padding case, where the input carries
        //       fewer places than SCALE, working identically.
        return value.setScale(SCALE, GENERAL_ROUNDING);
    }

    /**
     * Confirms an amount lies within the domain of the reference money picture.
     *
     * @param scaledAmount the amount to check, already at {@code SCALE} decimal places
     * @return the same amount, unchanged, so that this helper can be used in an assignment
     * @throws ArithmeticException if the magnitude of {@code scaledAmount} exceeds
     *     {@code MAX_MAGNITUDE}, because such an amount cannot be encoded in the twelve-byte zoned field
     *     declared at line 7 of {@code app/cpy/CVACT01Y.cpy} nor in the fifteen-character report masks of
     *     {@code app/cpy/CVTRA07Y.cpy}
     */
    private static BigDecimal requireWithinDomain(BigDecimal scaledAmount) {
        if (scaledAmount.abs().compareTo(MAX_MAGNITUDE) > 0) {
            // WHY : Refactoring Rationale: this message previously quoted the offending amount, on the
            //       reasoning that the digits identify whether a mis-decoded record or a mis-scaled
            //       intermediate produced it. That was withdrawn. An overflow is reached from
            //       caller-supplied text, so quoting the amount copies attacker-chosen content into an
            //       exception whose text becomes a log line, an alert and an error response -- a value
            //       under one party's control being rendered by another, which is the exposure a
            //       diagnostic must not create. The integer digit count and the bound identify the
            //       defect exactly as well: an amount is over the domain because it declares more than
            //       ten integer digits, and the count says how many.
            //       Trade-offs: a maintainer diagnosing a mis-decoded field can no longer read the
            //       value out of the log and must reproduce the decode. That cost is accepted because
            //       the field's identity travels with the decoder's own diagnostic, which names the
            //       field and its offset without naming its content.
            throw new ArithmeticException(
                    "an amount declaring " + (scaledAmount.precision() - scaledAmount.scale())
                            + " integer digits exceeds the reference money domain of "
                            + MAX_MAGNITUDE.toPlainString());
        }

        return scaledAmount;
    }
}
