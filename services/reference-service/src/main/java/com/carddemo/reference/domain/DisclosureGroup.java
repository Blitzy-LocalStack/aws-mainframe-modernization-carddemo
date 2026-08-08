//=============================================================================
// WHY : Assumptions: every column name, declared width, precision, scale and
//       nullability stated below was read out of
//       services/reference-service/src/main/resources/db/migration/
//       V1__reference.sql, which this package's charter in package-info.java
//       names as the authority for the physical shape of the reference schema.
//       Where this file and that migration could ever disagree, the migration
//       is right and this file is the defect.
// WHY : Assumptions: every path beginning app/ is reference material. It is
//       read as the specification for what this type must carry and is cited
//       by path and line, never modified. Where the migrated behaviour departs
//       from it deliberately, the departure is registered in
//       docs/architecture/cobol-to-service-traceability.md, which is
//       maintained elsewhere and referenced from here rather than reproduced.
//=============================================================================
package com.carddemo.reference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One disclosure group and the annual percentage rate it discloses: the 50-byte record of
 * {@code app/cpy/CVTRA02Y.cpy}, whose L2 header declares that length.
 *
 * <h2>Purpose</h2>
 *
 * <p>This type maps a Java object onto a row of {@code reference.disclosure_groups} and holds no
 * behaviour beyond the guards that keep a row well formed. Its identity is composite because the
 * source record makes it composite: L5 of that copybook groups {@code DIS-ACCT-GROUP-ID PIC X(10)}
 * at L6, {@code DIS-TRAN-TYPE-CD PIC X(02)} at L7 and {@code DIS-TRAN-CAT-CD PIC 9(04)} at L8
 * together under the name {@code DIS-GROUP-KEY}, and the one program that reads the record keys on
 * all three at once. {@code app/jcl/DISCGRP.jcl} confirms the arithmetic from a second, unrelated
 * direction: L40 defines {@code KEYS(16 0)} against {@code RECORDSIZE(50 50)} at L41, and a
 * sixteen-byte key at offset zero balances only as 10 plus 2 plus 4.</p>
 *
 * <p>Assumptions: the sole baseline reader of this record is the interest accrual,
 * {@code app/cbl/CBACT04C.cbl}, and {@code COPY CVTRA02Y} appears in no other program. That program
 * assembles the key at L210 to L212, reads at L416, and takes the rate it finds. The rate is
 * therefore not a value this context merely displays; it is an operand of a money computation
 * performed elsewhere, which is what makes the typing decisions below load-bearing rather than
 * cosmetic.</p>
 *
 * <h2>The account-group component is ten characters wide and is never trimmed</h2>
 *
 * <p>Assumptions: {@code acct_group_id} is {@code CHAR(10)}, and neither a stored value nor a probe
 * may be trimmed on either side of a comparison. The blank padding is part of the stored key rather
 * than a formatting artefact, and three independent sources establish it.</p>
 *
 * <ol>
 *   <li>{@code app/data/ASCII/discgrp.txt} holds 51 records of 50 bytes carrying exactly three
 *       distinct group values at 17 rows each, and two of the three are seven characters padded out
 *       to exactly ten: the default group and the zero-rate group each occupy their first seven
 *       bytes and leave the next three blank.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} L437 moves the seven-character literal {@code 'DEFAULT'} into
 *       {@code FD-DIS-ACCT-GROUP-ID}, which L79 of that same program declares {@code PIC X(10)}.
 *       An alphanumeric move is left-justified and blank-filled to the width of the receiving
 *       field, so the key the program then searches for carries three trailing blanks.</li>
 *   <li>{@code app/jcl/DISCGRP.jcl} L40 {@code KEYS(16 0)} confirms that the ten-character
 *       component participates in a sixteen-byte key at offset zero, so its declared width is part
 *       of the key rather than of its presentation.</li>
 * </ol>
 *
 * <p>What breaks if either side is trimmed is worth naming concretely rather than leaving as a
 * caution. The baseline falls back to the default group when a rate lookup misses: L436 tests the
 * file status for {@code '23'}, L437 substitutes the group component, and L438 re-reads through
 * {@code 1200-A-GET-DEFAULT-INT-RATE} at L443. A trimmed probe searches for a key no row carries,
 * so the substituted read misses as well, and the baseline's response to that is not a quiet empty
 * result: L455 reports {@code 'ERROR READING DEFAULT DISCLOSURE GROUP'}, L457 reports the status
 * and L458 transfers to {@code 9999-ABEND-PROGRAM} at L628, which calls {@code CEE3ABD} with an
 * abend code of 999 at L632. The Java implements the same substitution as an ordinary second keyed
 * read whose empty result the caller handles, and any divergence is documented in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Trade-offs: choosing {@code CHAR(10)} means PostgreSQL blank-pads what it stores and ignores
 * trailing blanks when it compares, so the sibling {@code repository} and {@code service} packages
 * build their probes in the padded form and the identity below refuses an unpadded one outright.
 * The alternative of {@code VARCHAR(10)} holding trimmed values was weighed and not taken: it
 * would silently change the key domain the migration declares, leaving the padded value loaded from
 * the seed and the unpadded literal the fallback supplies as two distinct keys that select
 * different rows, and the mismatch would surface as an account accruing at the wrong rate rather
 * than as an error anyone could see.</p>
 *
 * <h2>The rate is an exact scaled decimal and never leaves that form</h2>
 *
 * <p>Assumptions: {@code interest_rate} is {@code NUMERIC(6,2)} in the schema and
 * {@code java.math.BigDecimal} at a scale of exactly two here. The baseline field is
 * {@code DIS-INT-RATE PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy} L9, a zoned decimal carrying
 * its sign as an overpunch in the trailing byte, which is itself an exact base-ten encoding. The
 * three distinct rate values in {@code app/data/ASCII/discgrp.txt} occupy six bytes each: five
 * digits followed by one overpunch byte holding the final digit together with the sign. The stored
 * forms are {@code 00150} then the positive-zero overpunch for 15.00, {@code 00250} then the same
 * byte for 25.00, and {@code 00000} then the same byte for 0.00. That trailing byte is identical in
 * all 51 rows, so no seeded rate is negative. Transformation rule T3 of the migration plan excludes
 * {@code float}, {@code double}, {@code java.lang.Float}, {@code java.lang.Double} and a bare JSON
 * number from the money and rate path, and this member is on that path.</p>
 *
 * <p>Trade-offs: the reason that exclusion is absolute here rather than proportionate is that the
 * rate is an operand and not an output. {@code app/cbl/CBACT04C.cbl} computes, at L462 to L465,
 * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}; L467 adds that result
 * into the account total and L468 writes it out as a generated interest transaction that then posts
 * to a balance. A rate such as 15.00 or 2.50 has no exact binary representation, so a
 * representation error in the stored rate enters the multiplication before the division consumes it
 * and settles into money a statement reports and a customer is charged. The compromise accepted is
 * that arithmetic on this member is more verbose than an operator on a primitive, which is a cost
 * paid at the keyboard rather than in the ledger.</p>
 *
 * <p>Assumptions: the boundary of this type's responsibility runs exactly at the column. The
 * multiplication is parenthesised in the reference statement at L465, and transformation rule T4
 * requires the Java to form that product at full precision and only then divide with an explicit
 * scale and rounding mode -- but that obligation belongs to the interest calculation in
 * {@code batch-service} and to {@code com.carddemo.common.money.Money}, not here. This type
 * therefore holds a value and never computes one: it performs no multiplication, no division and no
 * rounding at all.</p>
 *
 * <p>Assumptions: the rounding contract is likewise cited and not restated. {@code Money} declares
 * one mode, {@code Money.GENERAL_ROUNDING}, which is {@code RoundingMode.HALF_UP}, and its own
 * documentation records that this single mode governs every reduction it performs including
 * {@code Money.monthlyInterest(BigDecimal)}, because a selectable mode would be a second money
 * contract in disguise. {@code Money.MONTHLY_RATE_DIVISOR} preserves the reference literal 1200 of
 * L465 as one combined divisor, and {@code Money.monthlyInterest(BigDecimal)} reproduces L464 to
 * L465 by forming the product first and reducing exactly once at the division. The token
 * {@code ROUNDED} appears nowhere in the 652 lines of {@code app/cbl/CBACT04C.cbl}, so the
 * reference program truncates toward zero where the target rounds half up; that difference is
 * registered as divergence C-ROUNDING in
 * {@code docs/architecture/cobol-to-service-traceability.md} and is settled there rather than in
 * this file.</p>
 *
 * <p>Alternatives Considered: this member is a {@code BigDecimal} rather than a
 * {@code com.carddemo.common.money.Money}. {@code Money} was weighed and deliberately not used on
 * the entity, and the conversion is placed one layer out instead:
 * {@code com.carddemo.reference.mapper.DisclosureGroupMapper} calls {@code Money.of} on the value
 * this member returns when it builds the published reply. Two properties follow from putting it
 * there. An entity is never serialised to a caller, a transfer object is, so the JSON-string
 * rendering that keeps a client from parsing a rate into a binary value belongs beside the transfer
 * object rather than beside the column; and {@code Money}'s arithmetic contract stays where
 * arithmetic actually happens instead of being reachable from a persistence type that is forbidden
 * to compute. The cost accepted is one conversion at the mapper.</p>
 *
 * <p>Assumptions: what the build does and does not establish about the paragraphs above is worth
 * stating exactly, so that a green build is never mistaken for proof. Architecture rule A3 in
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * takes the whole {@code com.carddemo} namespace as its subject set, this package included, and
 * rejects the four binary types named above in a field, a parameter or a return type, including as
 * a generic type argument; it is published as a test artifact and re-run inside every module, so a
 * binary member introduced here would fail the build rather than merely fail review. What that rule
 * cannot see is arithmetic. It reads declared member types only, so it does not establish that a
 * scale is two, that a rounding mode is the one {@code Money} declares, or that a product was
 * formed before a quotient. Those properties are carried by {@code Money} and by the tests over it,
 * and the exclusion itself rests on transformation rule T3 rather than on the rule that happens to
 * mechanise part of it.</p>
 *
 * <h2>A rate of zero is a value, not an absence</h2>
 *
 * <p>Assumptions: zero is a meaningful member of this domain and it suppresses interest generation
 * rather than signalling a missing rate. {@code app/cbl/CBACT04C.cbl} L214 guards the whole
 * computation with {@code IF DIS-INT-RATE NOT = 0}, so a zero rate skips both L215
 * {@code PERFORM 1300-COMPUTE-INTEREST} and L216 {@code PERFORM 1400-COMPUTE-FEES} and produces no
 * transaction at all. The seed makes the same point by weight of data: 30 of the 51 rows in
 * {@code app/data/ASCII/discgrp.txt} carry 0.00, and all 17 rows of the zero-rate group carry it,
 * a group whose name is its semantics. This member is therefore never null and holds an explicit
 * two-place zero where the rate is zero. Mapping a zero to null, or a null to zero, would change
 * what the L214 guard means: the first would turn a deliberate suppression into an absent lookup
 * and the second would turn a genuinely absent row into a silent decision not to accrue.</p>
 *
 * <p>Trade-offs: a nullable column was available and was rejected for exactly that reason. It would
 * have offered two spellings of one outcome, null and zero, that the guard above cannot
 * distinguish, and every reader would then have had to decide which spelling meant what. The
 * compromise accepted is that a caller must supply a rate explicitly and cannot leave it unset,
 * which the constructor enforces rather than leaving to the database to refuse at flush time.</p>
 *
 * <h2>What the row carries, and what it leaves behind</h2>
 *
 * <p>Assumptions: {@code tran_cat_cd} is {@code CHAR(4)} and {@code String} here, never an integral
 * type, even though {@code app/cpy/CVTRA02Y.cpy} L8 declares {@code DIS-TRAN-CAT-CD PIC 9(04)} and
 * a numeric picture taken mechanically would become an integer column. The five sources that settle
 * this are enumerated on the sibling {@code TransactionCategory} and the ruling is recorded once in
 * this package's {@code package-info.java}; neither is restated here. What matters at this member
 * is that the two representations stay identical: the same four-character category code appears in
 * both tables, so a divergence would make one table's codes incomparable with the other's, and the
 * shared leading zeros are the whole reason the code is character data.</p>
 *
 * <p>Assumptions: {@code acct_group_id} and {@code tran_type_cd} are {@code String} for the more
 * ordinary reason that their pictures are alphanumeric. {@code app/cpy/CVTRA02Y.cpy} L6 declares
 * {@code PIC X(10)} and L7 declares {@code PIC X(02)}, and {@code app/jcl/DISCGRP.jcl} L40
 * corroborates both widths inside {@code KEYS(16 0)}. Neither is constrained to digits, so neither
 * is checked for digits below, even though every group value and type code the seed happens to hold
 * today would satisfy such a check.</p>
 *
 * <p>Assumptions: the L10 {@code FILLER PIC X(28)} of {@code app/cpy/CVTRA02Y.cpy} is not carried
 * across, and the drop is recorded once for this record as transformation rule T1 requires:
 * 10 plus 2 plus 4 plus 6 plus 28 accounts for all 50 bytes the L2 header declares, so no field is
 * left unexamined. Any reader of the flat record must skip those 28 bytes by offset and must never
 * reach them by trimming, because in {@code app/data/ASCII/discgrp.txt} they hold 28 ASCII zero
 * characters on every one of the 51 rows rather than blanks, so a trim would leave all of them
 * inside the value. {@code app/cbl/CBACT04C.cbl} L82 corroborates the boundary from the other side
 * by modelling the whole tail as one opaque {@code FD-DISCGRP-DATA PIC X(34)}, which is the six
 * bytes of the rate beside the 28 of the padding.</p>
 *
 * <p>Assumptions: no association is mapped to {@code TransactionType} or to
 * {@code TransactionCategory}, although the type and category components look as though they should
 * reference one. The migration declares no foreign key from this table, and the baseline declares no
 * such relationship either: {@code app/cpy/CVTRA02Y.cpy} is a standalone VSAM layout, the cluster
 * defined at {@code app/jcl/DISCGRP.jcl} L36 stands on its own, and the program performs no
 * referential check because it looks the three components up as one key. Mapping an association
 * would state columns the embedded identity already states, and adding a constraint the schema does
 * not declare would make the seed load ordering-sensitive and could refuse a row the baseline
 * accepts. This is recorded so that a reader does not supply the missing constraint as an
 * improvement.</p>
 *
 * <p>Assumptions: this entity carries no optimistic-lock counter. The version ruling in this
 * package's {@code package-info.java} grants one to exactly two of the six entities and names this
 * type among the four that carry none, because their tables declare none; {@code V1__reference.sql}
 * declares no {@code version} column on {@code disclosure_groups}, and the sibling
 * {@code repository} exposes no write member over it. The accrual reads this table; no migrated
 * operation replaces a row in it.</p>
 *
 * <h2>What this revision changed, and why</h2>
 *
 * <p>Refactoring Rationale: an earlier revision of this type carried the rate as a
 * {@code BigDecimal} but assigned it exactly as supplied, with no reduction to the column's declared
 * scale. That left the stored scale at the caller's discretion, so an integral 15 and a
 * {@code BigDecimal.ZERO} both settled at a scale of zero while a two-place 15.00 settled at two.
 * The consequence was not theoretical. {@code BigDecimal.equals} treats a difference of scale as a
 * difference of value, so two instances of one rate could compare unequal while comparing equal
 * numerically; and the zero-rate ruling above depends on a zero being an explicit two-place value,
 * because that is the form the column holds and the form the accrual's guard is evaluated against.
 * The reduction is now applied once at every assignment route, which is why it sits in the
 * constructor and in the one mutator rather than in the accessor.</p>
 *
 * <p>Refactoring Rationale: the same revision documented neither the no-trim ruling nor the
 * zero-rate ruling, although both are relied on outside this file: the sibling {@code repository}
 * states the no-trim rule in its own contract, and a test beside this package already asserts that
 * the unpadded seven-character fallback literal is refused. A rule enforced by code in one place and
 * asserted by a test in another, while unexplained at the type that embodies it, is a rule a future
 * reader can weaken without ever seeing the argument against doing so. Both are now recorded here
 * with the evidence that establishes them, together with an explicit note on what the architecture
 * rule mechanising the binary-type exclusion does and does not establish, so that its green result
 * is not read as covering the arithmetic properties it cannot see.</p>
 */
@Entity
@Table(name = "disclosure_groups", schema = "reference")
public class DisclosureGroup {

    /** The declared width of the account-group component, from {@code DIS-ACCT-GROUP-ID PIC X(10)}. */
    public static final int ACCT_GROUP_ID_WIDTH = 10;

    /** The declared width of the type component, from {@code DIS-TRAN-TYPE-CD PIC X(02)}. */
    public static final int TRAN_TYPE_CD_WIDTH = 2;

    /** The declared width of the category component, from {@code DIS-TRAN-CAT-CD PIC 9(04)}. */
    public static final int TRAN_CAT_CD_WIDTH = 4;

    /** The declared precision of the rate column, from {@code DIS-INT-RATE PIC S9(04)V99}. */
    public static final int INTEREST_RATE_PRECISION = 6;

    /** The declared scale of the rate column: two decimal places, hundredth resolution. */
    public static final int INTEREST_RATE_SCALE = 2;

    /** The composite identity of this group. */
    @EmbeddedId
    private DisclosureGroupId id;

    // WHY : Assumptions: BigDecimal over a NUMERIC column, and the two agree by intent rather than
    //       by accident. A binary member here would compile and would round-trip some values; the
    //       ones it did not round-trip would be rates ending in a repeating binary expansion, which
    //       is most of them. The class documentation records why that matters for a member that is
    //       an operand of a money computation rather than a displayed figure.
    // WHY : Assumptions: precision and scale are declared here as well as in the migration, and
    //       stating them is not redundant. Omitted, the provider assumes its own default precision
    //       and scale for a decimal member, so the start-up validation the integration-test profile
    //       runs would compare this column against a shape the migration never declared. Declaring
    //       both also lets a reader see at the mapping site what the column holds.
    // WHY : Assumptions: nullable = false restates the migration's NOT NULL deliberately. A mapping
    //       that under-states nullability describes a column the schema does not have, and here it
    //       would additionally admit the null-versus-zero ambiguity the class documentation rejects.
    @Column(name = "interest_rate", nullable = false,
            precision = INTEREST_RATE_PRECISION, scale = INTEREST_RATE_SCALE)
    private BigDecimal interestRate;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the specification requires a no-argument constructor, which the provider calls
     * before assigning the mapped members reflectively. It is public rather than narrower because
     * that is this package's prevailing choice, and because the guards that matter are on the
     * assignment routes rather than on the allocation: the identity can only ever be supplied
     * through the constructor below, and every rate assignment passes the same canonicalisation.</p>
     */
    public DisclosureGroup() {
        // WHY : Assumptions: the body is empty because the provider assigns every mapped member
        //       reflectively immediately afterwards, so a default written here would be discarded.
    }

    /**
     * Creates a group from its whole identity and its rate.
     *
     * <p>Assumptions: the rate is canonicalised to the column's declared scale once, here, so that
     * every instance carries the same two-place form however a caller spelled it. Doing it at
     * construction rather than at the accessor is what keeps the accessor a plain return.</p>
     *
     * @param id the composite identity of the group; must not be {@code null}
     * @param interestRate the annual percentage rate, which is reduced to the column's declared
     *     scale without rounding; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws ArithmeticException if the rate carries more decimal places than the column declares,
     *     because reducing it would discard a digit rather than reformat one
     * @throws IllegalArgumentException if the rate needs more integer digits than the column
     *     declares
     */
    public DisclosureGroup(DisclosureGroupId id, BigDecimal interestRate) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.interestRate = canonicalRate(interestRate);
    }

    /**
     * Returns the composite identity.
     *
     * @return the identity, never {@code null} on a persisted instance
     */
    public DisclosureGroupId getId() {
        return this.id;
    }

    /**
     * Returns the annual percentage rate.
     *
     * @return the rate as an exact decimal at the column's declared scale, zero where the group
     *     discloses no interest, never {@code null} on a persisted instance
     */
    public BigDecimal getInterestRate() {
        return this.interestRate;
    }

    /**
     * Returns the account-group component of the identity.
     *
     * @return the ten-character account group with its blank padding intact, or {@code null} before
     *     an identity has been assigned
     */
    public String getAcctGroupId() {
        return this.id == null ? null : this.id.getAcctGroupId();
    }

    /**
     * Returns the transaction-type component of the identity.
     *
     * @return the two-character type code, or {@code null} before an identity has been assigned
     */
    public String getTranTypeCd() {
        return this.id == null ? null : this.id.getTranTypeCd();
    }

    /**
     * Returns the transaction-category component of the identity.
     *
     * @return the four-digit category code with its leading zeros intact, or {@code null} before an
     *     identity has been assigned
     */
    public String getTranCatCd() {
        return this.id == null ? null : this.id.getTranCatCd();
    }

    /**
     * Replaces the rate, applying the same canonicalisation the constructor applies.
     *
     * <p>Purpose: the rate is the one mapped member of this type that is not part of its key, so it
     * is the only member a reload of the disclosure data can revise. Both the identity and its three
     * components stay immutable and their columns are mapped non-updatable, because changing a key
     * in place would move the row rather than revise it.</p>
     *
     * <p>Assumptions: the canonicalisation is repeated here rather than trusted to the caller,
     * because a value reaching this member has not necessarily passed through the constructor. A
     * rate carrying a third decimal place would otherwise be reduced by the driver at flush time,
     * with nothing to indicate which row or which member had lost a digit.</p>
     *
     * @param interestRate the replacement annual percentage rate, which is reduced to the column's
     *     declared scale without rounding; must not be {@code null}
     * @throws NullPointerException if {@code interestRate} is {@code null}
     * @throws ArithmeticException if the rate carries more decimal places than the column declares
     * @throws IllegalArgumentException if the rate needs more integer digits than the column
     *     declares
     */
    public void setInterestRate(BigDecimal interestRate) {
        this.interestRate = canonicalRate(interestRate);
    }

    /**
     * Returns a rate that is present, at the column's declared scale, and within its declared width.
     *
     * <p>Alternatives Considered: the scale is applied with no rounding mode, so an inexact
     * reduction raises instead of quietly resolving. Supplying a mode was the obvious alternative
     * and was rejected twice over. It would put a rounding decision in a persistence type that the
     * class documentation forbids to compute, and it would create a second rounding contract beside
     * the one {@code com.carddemo.common.money.Money} declares for the accrual, which is precisely
     * the divergence that produces an unexplained cent. A rate with a third decimal place is not a
     * value this column can hold, so refusing it names the problem where a caller can still fix its
     * own input; reducing it would invent a rate nobody supplied.</p>
     *
     * <p>Assumptions: a value already at the declared scale passes through unchanged, and one at a
     * smaller scale is widened exactly, so an integral 15 and a two-place 15.00 both settle as the
     * same stored form. That is what lets a caller supply a whole-number rate, and a zero, without
     * having to know the column's scale.</p>
     *
     * <p>Assumptions: the integer-digit ceiling is four, from the {@code NUMERIC(6,2)} the migration
     * declares and the {@code PIC S9(04)V99} it derives from, so the domain runs to 9999.99. The
     * check reads the value's own precision and scale rather than computing on it. A negative rate
     * is not refused: the source picture is signed, so its negative domain is declared even though
     * every rate the seed holds carries the positive-zero overpunch, and refusing one here would
     * reject a value the contract admits.</p>
     *
     * @param candidate the supplied rate, possibly {@code null}
     * @return the accepted rate at exactly the declared scale, never {@code null}
     * @throws NullPointerException if {@code candidate} is {@code null}
     * @throws ArithmeticException if {@code candidate} carries more decimal places than the column
     *     declares, raised by the exact reduction rather than by this method
     * @throws IllegalArgumentException if {@code candidate} needs more integer digits than the
     *     column declares
     */
    private static BigDecimal canonicalRate(BigDecimal candidate) {
        Objects.requireNonNull(candidate, "interestRate must not be null");
        BigDecimal canonical = candidate.setScale(INTEREST_RATE_SCALE);
        int integerDigits = canonical.precision() - canonical.scale();
        if (integerDigits > INTEREST_RATE_PRECISION - INTEREST_RATE_SCALE) {
            throw new IllegalArgumentException("interestRate must fit "
                    + (INTEREST_RATE_PRECISION - INTEREST_RATE_SCALE) + " integer digits and "
                    + INTEREST_RATE_SCALE + " decimal places, the precision and scale the migration"
                    + " declares; received " + canonical.toPlainString());
        }
        return canonical;
    }

    /**
     * Compares two groups by composite identity alone.
     *
     * <p>Assumptions: equality is the identity and not the rate, because two instances of one group
     * read at different moments are the same row whether or not its rate has since been revised.
     * Including the rate would make a managed instance stop equalling its own detached copy as soon
     * as either side was reloaded, which is the opposite of what a caller holding both expects.</p>
     *
     * @param other the object to compare against, possibly {@code null}
     * @return {@code true} when the other object is a group whose identity is equal to this one's
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DisclosureGroup group)) {
            return false;
        }
        return Objects.equals(this.id, group.id);
    }

    /**
     * Hashes the composite identity alone, consistently with {@link #equals(Object)}.
     *
     * <p>Assumptions: the identity is the only member hashed, for the same reason it is the only
     * member compared. Hashing the rate as well would move an instance between buckets when the rate
     * was revised, so a set that already held it could no longer find it.</p>
     *
     * @return the identity's hash, or zero before an identity has been assigned
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.id);
    }

    /**
     * Renders the identity and the rate for a diagnostic.
     *
     * <p>Assumptions: every member rendered here is reference data that a log may carry. Neither the
     * group, the type, the category nor the rate identifies a person, an account or a card, so
     * nothing is masked. The rate is rendered as it is stored, at its declared scale, so a
     * diagnostic shows the value the accrual would read.</p>
     *
     * @return a diagnostic rendering of the identity and the rate, never {@code null}
     */
    @Override
    public String toString() {
        return "DisclosureGroup[id=" + this.id + ", interestRate=" + this.interestRate + "]";
    }

    /**
     * The composite identity of a disclosure group: an account group, a transaction type beneath it
     * and a transaction category beneath that.
     *
     * <p>Purpose: this type carries the three components the source record groups under
     * {@code DIS-GROUP-KEY} at {@code app/cpy/CVTRA02Y.cpy} L5, and it exists so that the triple can
     * be passed, compared and used as a map key as one value. All three are held as character data at
     * their declared widths for the reasons the enclosing type documents, and each is refused unless
     * it is spelled exactly as the seed stores it.</p>
     *
     * <p>Alternatives Considered: a nested embeddable identity referenced by an embedded identity
     * mapping, rather than either of the two alternatives this package's charter weighed. Grouping the
     * three components into one object mirrors the source, which names them collectively at L5 rather
     * than leaving them adjacent and unrelated, and it matches the sibling
     * {@code TransactionCategory}, whose identity is built the same way so that the two composite
     * keys of this package are assembled identically. A standalone top-level identity file was
     * rejected because the contents of this package are a closed set of seven compilation units, one
     * descriptor beside six entities, and separate identity files would break the shape a reader is
     * told to expect. An identity-class mapping was rejected because it obliges the identity fields
     * to be declared a second time on the entity itself, and two declarations of one field can drift
     * apart.</p>
     *
     * <p>Assumptions: the name {@code DisclosureGroup.DisclosureGroupId} and the component names
     * {@code acctGroupId}, {@code tranTypeCd} and {@code tranCatCd} are a published contract that
     * this package's charter settles. The sibling {@code repository}, {@code service} and
     * {@code mapper} packages consume that exact name for keyed reads, including for the substituted
     * read that resolves the default group, so it is not a detail this file may rename or relocate.</p>
     */
    @Embeddable
    public static class DisclosureGroupId implements Serializable {

        /** Serialisation identity, required of an embeddable identity type. */
        private static final long serialVersionUID = 1L;

        // WHY : Assumptions: bound as a blank-padded character type rather than a varying-width one,
        //       because the migration declares this column CHAR(10) and because the padding is part
        //       of the key. The enclosing type's documentation carries the three sources that settle
        //       this and names what a trimmed probe costs. Mapped non-updatable because a key is not
        //       revised in place; the rate mutator above records why that asymmetry exists.
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "acct_group_id", length = ACCT_GROUP_ID_WIDTH, nullable = false,
                updatable = false)
        private String acctGroupId;

        // WHY : Assumptions: blank-padded for the same reason, at the CHAR(2) the migration declares,
        //       so a padded probe arriving from a declared-width source matches its stored row. Under
        //       a varying-width type the same probe would return nothing and would not raise either.
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "tran_type_cd", length = TRAN_TYPE_CD_WIDTH, nullable = false,
                updatable = false)
        private String tranTypeCd;

        // WHY : Assumptions: blank-padded and never an integral type, at the CHAR(4) the migration
        //       declares. The five sources that settle this are enumerated on the sibling
        //       TransactionCategory and the ruling is held in this package's package-info; the
        //       leading-zero argument they turn on is the whole reason this member is not a number.
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "tran_cat_cd", length = TRAN_CAT_CD_WIDTH, nullable = false,
                updatable = false)
        private String tranCatCd;

        /**
         * Creates an empty identity for the persistence provider to populate.
         *
         * <p>Assumptions: an embeddable identity needs a no-argument constructor for the same reason
         * the enclosing entity does, and it is public for the same reason: the guard that matters is
         * on the three-argument form below, which is the only route application code has to a
         * populated key.</p>
         */
        public DisclosureGroupId() {
            // WHY : Assumptions: empty because the provider assigns all three components
            //       reflectively, exactly as it does for the enclosing entity.
        }

        /**
         * Creates an identity from its three components, each at its declared width.
         *
         * <p>Assumptions: every width is checked for exact equality rather than as a maximum, and the
         * category component is additionally checked to be all digits, which is where the numeric
         * picture at {@code app/cpy/CVTRA02Y.cpy} L8 is honoured. A blank-padded column ignores
         * trailing blanks when comparing, which forgives a value short on the right and does nothing
         * for one short on the left, so {@code 5} would resolve to a different row from {@code 0005}:
         * two spellings of one logical category, each satisfying every declared constraint.</p>
         *
         * <p>Assumptions: the exact-width check on the account group is what refuses the unpadded
         * fallback literal. {@code app/cbl/CBACT04C.cbl} L437 substitutes a seven-character literal
         * into a ten-byte field and so searches with three trailing blanks, and the seed stores those
         * rows that way, so refusing the seven-character spelling here obliges a caller to build the
         * key the table actually contains rather than one that would find nothing and fall through to
         * the abend path at L455 and L458.</p>
         *
         * @param acctGroupId the ten-character account group, blank-padded to its declared width;
         *     must not be {@code null}
         * @param tranTypeCd the two-character transaction type, alphanumeric at its declared width;
         *     must not be {@code null}
         * @param tranCatCd the four-digit transaction category with its leading zeros intact; must
         *     not be {@code null}
         * @throws NullPointerException if any component is {@code null}
         * @throws IllegalArgumentException if any component is not exactly its declared width, or if
         *     the category component holds a character that is not a digit
         */
        public DisclosureGroupId(String acctGroupId, String tranTypeCd, String tranCatCd) {
            this.acctGroupId = exactWidth(acctGroupId, ACCT_GROUP_ID_WIDTH, "acctGroupId");
            this.tranTypeCd = exactWidth(tranTypeCd, TRAN_TYPE_CD_WIDTH, "tranTypeCd");
            this.tranCatCd = exactDigits(tranCatCd, TRAN_CAT_CD_WIDTH, "tranCatCd");
        }

        /**
         * Returns a component that is present and exactly as wide as its column declares.
         *
         * <p>Assumptions: the refusal message names the component, because all three components of
         * this key are strings of similar shape and a message that omitted the name would leave a
         * caller guessing which of the three it had spelled wrongly.</p>
         *
         * @param candidate the supplied component, possibly {@code null}
         * @param width the declared width the component must occupy exactly
         * @param member the component's name, used only in the refusal message
         * @return the accepted value, never {@code null}
         * @throws NullPointerException if {@code candidate} is {@code null}
         * @throws IllegalArgumentException if the value is not exactly {@code width} characters
         */
        private static String exactWidth(String candidate, int width, String member) {
            Objects.requireNonNull(candidate, member + " must not be null");
            if (candidate.length() != width) {
                throw new IllegalArgumentException(member + " must be exactly " + width
                        + " characters, because it is part of a composite key over a blank-padded"
                        + " column; received " + candidate.length());
            }
            return candidate;
        }

        /**
         * Returns a component that is present, exactly as wide as its column declares, and all digits.
         *
         * <p>Assumptions: the digit test is written over the characters rather than by parsing the
         * value, because parsing would accept a sign or surrounding blanks that the source picture
         * does not admit, and it would discard the leading zeros the stored key depends on.</p>
         *
         * @param candidate the supplied component, possibly {@code null}
         * @param width the declared width the component must occupy exactly
         * @param member the component's name, used only in the refusal message
         * @return the accepted value, never {@code null}
         * @throws NullPointerException if {@code candidate} is {@code null}
         * @throws IllegalArgumentException if the value is not exactly {@code width} characters, or
         *     if it holds a character that is not a digit
         */
        private static String exactDigits(String candidate, int width, String member) {
            exactWidth(candidate, width, member);
            for (int index = 0; index < candidate.length(); index++) {
                char character = candidate.charAt(index);
                if (character < '0' || character > '9') {
                    throw new IllegalArgumentException(member + " must be exactly " + width
                            + " digits with its leading zeros intact, because its source picture is"
                            + " numeric and zero-filled; received a non-digit at position "
                            + (index + 1));
                }
            }
            return candidate;
        }

        /**
         * Returns the account-group component.
         *
         * @return the ten-character account group with its blank padding intact, never {@code null}
         *     on a populated identity
         */
        public String getAcctGroupId() {
            return this.acctGroupId;
        }

        /**
         * Returns the transaction-type component.
         *
         * @return the two-character type code, never {@code null} on a populated identity
         */
        public String getTranTypeCd() {
            return this.tranTypeCd;
        }

        /**
         * Returns the transaction-category component.
         *
         * @return the four-digit category code with its leading zeros intact, never {@code null} on a
         *     populated identity
         */
        public String getTranCatCd() {
            return this.tranCatCd;
        }

        /**
         * Compares two identities by all three components.
         *
         * <p>Assumptions: an identity type has to compare by value rather than by reference for the
         * provider to recognise two reads of one row as the same entity and for the type to work as a
         * map key. All three components participate, because any one of them differing selects a
         * different row -- which is exactly what the substituted read of the default group relies on,
         * since it differs from the original probe in the group component alone.</p>
         *
         * @param other the object to compare against, possibly {@code null}
         * @return {@code true} when the other object is an identity whose three components are all
         *     equal to this one's
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DisclosureGroupId identity)) {
                return false;
            }
            return Objects.equals(this.acctGroupId, identity.acctGroupId)
                    && Objects.equals(this.tranTypeCd, identity.tranTypeCd)
                    && Objects.equals(this.tranCatCd, identity.tranCatCd);
        }

        /**
         * Hashes all three components, consistently with {@link #equals(Object)}.
         *
         * <p>Assumptions: all three are hashed because all three are compared. Hashing the group
         * component alone would be correct but would collide every rate row of one group, which in
         * this table is the set of 17 rows a lookup ranges over.</p>
         *
         * @return the combined hash of the three components
         */
        @Override
        public int hashCode() {
            return Objects.hash(this.acctGroupId, this.tranTypeCd, this.tranCatCd);
        }

        /**
         * Renders all three components for a diagnostic.
         *
         * <p>Assumptions: all three are reference codes that a log may carry, so none is masked. They
         * are rendered separately rather than concatenated, even though the baseline keys on the
         * concatenation, so that a reader can tell which component is which and can see the account
         * group's blank padding instead of losing it inside a sixteen-character run.</p>
         *
         * @return a diagnostic rendering of the three components, never {@code null}
         */
        @Override
        public String toString() {
            return "DisclosureGroupId[acctGroupId=" + this.acctGroupId + ", tranTypeCd="
                    + this.tranTypeCd + ", tranCatCd=" + this.tranCatCd + "]";
        }
    }
}
