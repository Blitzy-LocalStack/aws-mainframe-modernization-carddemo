package com.carddemo.reference.mapper;

import com.carddemo.common.money.Money;
import com.carddemo.reference.domain.DisclosureGroup;
import com.carddemo.reference.dto.DisclosureGroupRateResponse;

/**
 * Converts a resolved disclosure group into the rate reply the contract publishes.
 *
 * <p>Purpose: this is the one place a disclosure rate crosses from its stored representation to its
 * published one, and the only member of this package that touches the money path. The column holds an
 * exact scaled decimal, the wire carries a quoted string, and the crossing is performed here -- once
 * for the whole service rather than once per endpoint -- by handing the stored value to the shared
 * money type. Its consumer is {@code com.carddemo.reference.service}, which resolves which row
 * answered before calling; this class reads no store, decides no rule and holds no state.</p>
 *
 * <p>Assumptions: the package-scope rulings applied below -- hand-written conversions with no code
 * generator, the boundary at which a value may be trimmed, the register of padding items that are not
 * carried across, and the rate contract -- are settled once in this package's own
 * {@code package-info.java} and are cited from here rather than re-argued. What this file adds is the
 * evidence particular to the fifty-byte record it converts.</p>
 *
 * <h2>The rate is carried across exactly, and nothing here adjusts it</h2>
 *
 * <p>Assumptions: no reduction is needed, so none is performed. {@code app/cpy/CVTRA02Y.cpy} L9
 * declares {@code 05 DIS-INT-RATE PIC S9(04)V99}, four integer digits and two decimal places;
 * {@code V1__reference.sql} L337 declares {@code interest_rate NUMERIC(6,2)}, the exact image of that
 * picture; and {@code DisclosureGroup} already reduces every rate it accepts to the column's declared
 * scale at assignment, supplying no mode, so a value carrying a third decimal place raises there
 * instead of resolving quietly. The value this class receives is therefore already at scale two, and
 * the widest rate the column admits is 9999.99, far inside the magnitude the money type accepts. Both
 * facts together are why the conversion below discards no digit and cannot raise on an ordinary
 * row.</p>
 *
 * <p>Assumptions: a zero rate is a meaningful value of the domain and not a missing one. The seed
 * extract {@code app/data/ASCII/discgrp.txt} holds exactly three distinct rate literals across its 51
 * rows and one of them denotes zero, every row of the {@code ZEROAPR} group carries it, and
 * {@code app/cbl/CBACT04C.cbl} reaches its accrual paragraph at L462 with that zero in hand and
 * produces no interest from it. A zero must therefore never be treated here as absent, defaulted to
 * something else, or normalised away: doing so would turn a group that discloses no interest into a
 * group that discloses an invented rate.</p>
 *
 * <p>Alternatives Considered: applying a reduction mode in this class was evaluated and rejected. Two
 * reduction contracts exist in {@code com.carddemo.common.money.Money} and neither is this class's to
 * exercise. The general one admits a decimal at the money scale under
 * {@code Money.GENERAL_ROUNDING}, half up, which governs every reduction that type performs except
 * one; the accrual one, {@code Money.monthlyInterest(java.math.BigDecimal)}, forms the product at
 * full precision and reduces exactly once at the division under
 * {@code Money.BASELINE_INTEREST_ROUNDING}, truncation, against the combined divisor
 * {@code Money.MONTHLY_RATE_DIVISOR}. The second belongs to the batch job that computes interest and
 * is not called from here at all. Reducing an already-exact operand before either contract sees it
 * would be an adjustment nothing asked for, and its effect would be a cent rather than an
 * exception.</p>
 *
 * <p>Trade-offs: this class is deliberately incurious about the value it moves. It gains no defensive
 * normalisation, so a rate that somehow reached it out of contract would be published rather than
 * intercepted -- and that is the compromise accepted, because the guard belongs at the assignment on
 * the entity where a caller can still act on the refusal, and because a mapper that never adjusts a
 * rate can never be the origin of a cent-level divergence. The stake is that high because the rate is
 * an operand and not a result. {@code app/cbl/CBACT04C.cbl} L464 to L465 state
 * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, with the multiplication
 * parenthesised in the reference source itself rather than by inference, and L467 to L468 add that
 * result to the account total and write it out as a generated interest transaction that then posts to
 * a balance. The other operand, {@code 05 TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy}
 * L9, is a record of the ledger schema owned by a different bounded context, which is the structural
 * reason the arithmetic sits in batch-service and the conversion sits here.</p>
 *
 * <p>Assumptions: the reference program carries no {@code ROUNDED} phrase on that statement, so it
 * truncates toward zero, and the migrated accrual reduces the same way under
 * {@code Money.BASELINE_INTEREST_ROUNDING} -- which is the accrual's own mode and is distinct from
 * the {@code Money.GENERAL_ROUNDING} half up that governs every other reduction. That mode belongs to
 * the accrual and not to this conversion, which applies neither.</p>
 *
 * <p>Assumptions: the migrated accrual therefore differs from the reference by no cent at all, which
 * is why this path carries no registered rounding divergence. The identifier {@code C-ROUNDING} appears
 * in {@code docs/architecture/cobol-to-service-traceability.md} section 7.5 as a withdrawal record and
 * nowhere as a live divergence, and a reader who finds it there should read it that way.</p>
 *
 * <p>Assumptions: no binary floating-point type appears anywhere on this path, and unlike the same
 * prohibition in some sibling contexts it is mechanised rather than left to review. Rule A3 of
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * subjects every production class residing under the analysed root {@code com.carddemo}, not the
 * shared money package alone, and its own note records why: under the narrower scope a transfer
 * object, an entity or a mapper could declare such a member and still pass. {@code services/pom.xml}
 * runs that class against this module's compiled classes through Surefire's
 * {@code dependenciesToScan}, so a breach here fails the build rather than a review.</p>
 *
 * <p>Assumptions: the string form on the wire is produced by
 * {@code com.carddemo.common.money.MoneyModule}, which registers its serialiser against the money
 * type and emits the value through the generator's string method. That module is Jackson
 * configuration rather than a collaborator, so it is deliberately not imported here and there is no
 * call site in this file to look for. The string is the point of the chain and not a preference:
 * most clients parse a JSON number into an IEEE-754 binary value, which cannot represent most
 * two-place decimal fractions exactly, so exactness would be lost at precisely the boundary a user
 * reads. That the reference system reaches the same conclusion is visible in its own messaging
 * payloads, which carry an amount as signed decimal text while storing it in a packed form.</p>
 *
 * <h2>The three key components are published verbatim</h2>
 *
 * <p>Assumptions: the account group identifier occupies all ten of its declared positions and its
 * trailing spaces belong to the stored key rather than to formatting. The seed extract settles it by
 * literal: {@code app/data/ASCII/discgrp.txt} holds three distinct values in the first ten bytes of its
 * 51 rows -- {@code A000000000}, and {@code DEFAULT} and {@code ZEROAPR} each followed by three spaces
 * -- so two of the three depend on that padding to reach their declared width, and
 * {@code app/cbl/CBACT04C.cbl} L437 produces the padded form by moving a seven-character literal into a
 * {@code PIC X(10)} field. {@code V1__reference.sql} carries it across as
 * {@code acct_group_id CHAR(10)}.</p>
 *
 * <p>Trade-offs: the published payload therefore carries trailing spaces inside an identifier, which
 * reads oddly to anyone inspecting the reply. Trimming for tidiness was the alternative and it is
 * rejected, because a consumer that keyed a later lookup with a trimmed value would be searching for
 * a key no row carries, and because the fallback substitutes rather than fails, the symptom would be
 * silent accrual at the default rate instead of an error anyone could see. The declared width is part
 * of the contract, which is exactly why the column is {@code CHAR(10)} and not a varying-length
 * one.</p>
 *
 * <p>Assumptions: the two-character type code and the four-character category code are published on
 * the same terms, verbatim and untrimmed. {@code V1__reference.sql} declares them
 * {@code tran_type_cd CHAR(2)} and {@code tran_cat_cd CHAR(4)} and makes all three components one
 * composite primary key, so no component of a key is altered in transit. The category code is a
 * {@code String} and never a numeric type: {@code app/cpy/CVTRA02Y.cpy} L8 declares
 * {@code 10 DIS-TRAN-CAT-CD PIC 9(04)}, which reads as numeric, yet the seeded codes are zero-padded
 * four-character values such as {@code 0001}, and only the character reading publishes back the value a
 * consumer of the reference system can observe.</p>
 *
 * <h2>The padding item that is not carried across</h2>
 *
 * <p>Assumptions: {@code app/cpy/CVTRA02Y.cpy} L10 declares {@code 05 FILLER PIC X(28)} and it is not
 * carried across, which the copybook rule requires be recorded rather than inferred. The arithmetic that
 * makes the claim checkable, and the corroboration that those bytes hold ASCII zero characters on every
 * seeded row, are recorded at the construction site below rather than twice.</p>
 *
 * <h2>Where the representation boundary runs, and why no decoder belongs here</h2>
 *
 * <p>Assumptions: the rate's reference form is six positions of zoned decimal carrying its sign as an
 * overpunch in the trailing byte -- in {@code app/data/ASCII/discgrp.txt} three distinct literals across
 * all 51 rows, each ending in the same brace character, denoting 0.00, 15.00 and 25.00 at scale two, so
 * no seeded rate is negative. Those bytes never reach this class: decoding happens once, at the
 * migration edge, in {@code com.carddemo.common.codec.ZonedDecimalCodec} and the loader that uses it,
 * and what arrives here has already been read out of a {@code NUMERIC(6,2)} column. A second decoder for
 * one encoding is how two callers come to disagree about a rate, so none is added here.</p>
 *
 * <h2>Two deliberate omissions</h2>
 *
 * <p>Assumptions: there is no list member. The charter of {@code com.carddemo.reference.dto} records
 * that the published document exposes the disclosure rate as a read of one three-part key and offers no
 * browse over the group table, so no collection exists for a page envelope to carry and this class never
 * constructs one. That matches how the data is consumed: {@code app/cbl/CBACT04C.cbl} resolves a single
 * rate per balance row, falling back to the default group, rather than enumerating rates.</p>
 *
 * <p>Assumptions: there is no inbound member either. Disclosure groups are seeded reference data, so
 * the DTO package publishes no create or replace shape for them and there is nothing for an inbound
 * member to accept; the entity carries no revision counter and the sibling repository exposes no
 * write member over the table. Neither omission is an invitation to add the member later for symmetry
 * with the mappers beside this one. Each is absent because the contract it would serve does not
 * exist, and adding one would publish wire surface the document does not declare.</p>
 *
 * <p>Every artifact under {@code app/} cited above is read as the specification for this migration and
 * is never modified.</p>
 */
public final class DisclosureGroupMapper {

    /**
     * Prevents instantiation of a type whose whole content is static.
     *
     * @throws AssertionError always, so a reflective instantiation fails loudly rather than yielding a
     *     useless instance
     */
    private DisclosureGroupMapper() {
        // WHY : Assumptions: a private constructor rather than an abstract class, matching the three
        //       sibling conversions in this package. An abstract class invites a subclass, and this
        //       type has no behaviour to extend.
        throw new AssertionError("DisclosureGroupMapper is not instantiable");
    }

    /**
     * Renders a resolved rate, reporting both the group asked for and the group that supplied it.
     *
     * <p>Assumptions: the group asked for is carried through from the caller rather than read back off
     * the entity, because on a fallback the two differ and it is exactly that difference the reply
     * exists to report. The reference program overwrites its own key to fall back --
     * {@code app/cbl/CBACT04C.cbl} L436 tests the keyed read for the miss status {@code '23'}, L437
     * moves the default literal into the group field, and L438 performs the re-read paragraph that
     * opens at L443 -- so after the second read its field holds the substituted group rather than the one
     * asked about. Reading both values off the entity here would reproduce that loss and make a fallback
     * indistinguishable from a direct hit.</p>
     *
     * <p>Assumptions: the fallback indicator is not derivable from the entity, which is why it is a
     * parameter rather than something computed below. A row reached by substitution is an entirely
     * ordinary row; nothing on it records how it was found. The two-step lookup is performed by
     * {@code com.carddemo.reference.service}, which is the only layer that knows which of its two
     * reads answered, so it states the outcome and this method records it.</p>
     *
     * <p>Alternatives Considered: deriving the indicator here by comparing the group asked for against
     * a default-group literal was evaluated and rejected on two independent grounds. It would embed a
     * data value in conversion code, giving the literal two homes with no rule for when they disagree;
     * and it would report a fallback for a caller that asked about the default group directly, which
     * is a direct hit and a different fact. Reporting the outcome once, at the layer that established
     * it, keeps one decision in one place.</p>
     *
     * <p>Assumptions: the default group rows must exist for a fallback to resolve at all, and
     * {@code V2__seed_reference.sql} seeds them for that purpose. Where even the default group has no
     * row the reference program does not continue: {@code app/cbl/CBACT04C.cbl} L455 emits its
     * read-failure message and L458 performs the abend paragraph. That terminal outcome is what makes
     * the indicator worth publishing rather than keeping internal -- a rate that arrived by
     * substitution signals an account group absent from the seeded data, which is a condition an
     * operator can act on before it becomes that abend.</p>
     *
     * @param requestedAcctGroupId the account group the caller asked about, echoed back exactly as
     *     received; a {@code String} at the ten declared characters of
     *     {@code DIS-ACCT-GROUP-ID PIC X(10)}, neither trimmed nor padded here, and equal to the
     *     applied group whenever the requested group had a row of its own
     * @param entity the row that supplied the rate, which on a fallback belongs to the default group
     *     rather than to the group asked about; must not be {@code null}, and its rate is already at
     *     the column's declared scale because the entity reduces it at assignment
     * @param defaultGroupApplied whether the fallback answered, supplied by the service layer that
     *     performed the two reads because no property of {@code entity} records it; {@code true}
     *     exactly when the requested group had no row and the default group's row was used,
     *     {@code false} when the requested group had its own row, a caller asking about the default
     *     group directly included
     * @return the rate reply carrying the requested group, the applied group, the type, the category,
     *     the rate as a money value and the fallback indicator; never {@code null}
     * @throws NullPointerException if {@code entity} is {@code null}, raised by the first accessor
     *     reached rather than by an explicit guard here
     */
    public static DisclosureGroupRateResponse toResponse(
            String requestedAcctGroupId, DisclosureGroup entity, boolean defaultGroupApplied) {

        // WHY : Assumptions: the component ORDER declared by DisclosureGroupRateResponse is
        //       load-bearing at this call site, because the first two components are both
        //       ten-character group identifiers of the same type. Transposing them would compile and
        //       type-check, and every fallback would then report the applied group as the requested
        //       one -- so the one observable difference a fallback exists to publish would be lost
        //       with nothing to signal it.
        // WHY : Assumptions: app/cpy/CVTRA02Y.cpy L10 declares 05 FILLER PIC X(28) and it is not
        //       carried across. 10 + 2 + 4 + 6 + 28 = 50 accounts for every byte of the record length
        //       its L2 header declares, so nothing is overlooked by omitting it; those bytes pad the
        //       record to a positional length, which a JSON object has no use for, and in the seed
        //       extract they hold ASCII zero characters rather than anything a consumer could read.
        return new DisclosureGroupRateResponse(
                // WHY : Assumptions: verbatim, with the trailing spaces of the ten-character key
                //       intact. Two of the three group identifiers in app/data/ASCII/discgrp.txt are
                //       space-padded from seven characters, and app/cbl/CBACT04C.cbl L437 moves a
                //       seven-character literal into the PIC X(10) field its L79 declares, so the key
                //       the reference program searches with carries that padding. Trimming either of
                //       these two values would publish a key that addresses no row, and because the
                //       lookup falls back rather than failing the symptom would be a wrong rate
                //       instead of an error.
                requestedAcctGroupId,
                entity.getAcctGroupId(),
                // WHY : Assumptions: both remaining key components are published unaltered as well.
                //       The fallback substitutes the account group alone, so echoing the type and the
                //       category back unchanged is what tells a caller the rate belongs to the product
                //       it asked about; and the category code stays a String so the leading zeros of a
                //       value such as 0001 survive, which a numeric type would discard.
                entity.getTranTypeCd(),
                entity.getTranCatCd(),
                // WHY : Trade-offs: the stored decimal is admitted to the money type and nothing else
                //       happens to it -- no reduction mode, no rescaling and no arithmetic. The entity
                //       has already reduced it to the scale of the NUMERIC(6,2) column at
                //       V1__reference.sql L337, so this admission discards no digit, and the column's
                //       four integer digits keep it well inside the magnitude the money type accepts.
                //       The rate is an operand of the accrual at app/cbl/CBACT04C.cbl L464 to L465,
                //       whose multiplication is parenthesised in the reference source itself, so an
                //       adjustment made here would enter that product and settle into money a customer
                //       is charged. The compromise accepted is that this line offers no defensive
                //       normalisation; in exchange it cannot be the origin of a cent-level divergence.
                //       Only the money type is used, never a binary floating-point one, and rule A3 of
                //       the shared layering test fails the build on that rather than leaving it to
                //       review.
                Money.of(entity.getInterestRate()),
                defaultGroupApplied);
    }
}
