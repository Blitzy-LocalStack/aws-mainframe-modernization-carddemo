package com.carddemo.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The interest-rate lookup row that interest accrual reads, and never writes.
 *
 * <p>This is the migrated form of {@code 01 DIS-GROUP-RECORD}, declared at
 * {@code app/cpy/CVTRA02Y.cpy} lines 4 to 10, whose own line 2 records the record as
 * {@code RECLN = 50}. Four named fields become four columns; the trailing {@code FILLER} becomes
 * nothing. One baseline program governs what this type must express: {@code app/cbl/CBACT04C.cbl}
 * accrues interest and reaches this record by its three-part key to obtain the one rate the accrual
 * formula multiplies by.</p>
 *
 * <h2>The derivation, with the byte arithmetic that checks it</h2>
 *
 * <p>Offsets are zero-based and are the audit trail for the mapping: every column below traces back
 * to a byte range of the 50-byte record.</p>
 *
 * <pre>
 * offset  bytes  copybook field (line)      PICTURE     column          SQL type
 *      0     10  DIS-ACCT-GROUP-ID (L6)     X(10)       acct_group_id   CHAR(10)
 *     10      2  DIS-TRAN-TYPE-CD (L7)      X(02)       tran_type_cd    CHAR(2)
 *     12      4  DIS-TRAN-CAT-CD (L8)       9(04)       tran_cat_cd     CHAR(4)
 *     16      6  DIS-INT-RATE (L9)          S9(04)V99   interest_rate   NUMERIC(6,2)
 *     22     28  FILLER (L10)               X(28)       dropped         none
 * </pre>
 *
 * <p>The last row is what makes the mapping checkable: the named fields end at offset 22, the
 * {@code FILLER} occupies the remaining 28 bytes, and 22 plus 28 is the declared 50. The first three
 * rows span offsets 0 to 15 and are the group item {@code DIS-GROUP-KEY} at line 5, so the key is 16
 * bytes wide -- which {@code app/jcl/DISCGRP.jcl} line 40 confirms independently with
 * {@code KEYS(16 0)}, an operand that balances only if the components are 10, 2 and 4 bytes wide.</p>
 *
 * <p>Assumptions: the copybook rather than the file description is normative, under transformation
 * rule T1, and here the difference matters. The file description at {@code app/cbl/CBACT04C.cbl}
 * lines 76 to 82 collapses the remainder into one unnamed {@code FD-DISCGRP-DATA PIC X(34)}, so it
 * cannot say where the rate ends and the padding begins; only {@code app/cpy/CVTRA02Y.cpy} lines 9
 * and 10 separate them, which is what fixes the rate at 6 bytes and the padding at 28. The
 * {@code FILLER} is dropped rather than mapped, also under T1, because trailing padding in a
 * fixed-length record carries no value a column could hold.</p>
 *
 * <p>Assumptions: the physical key order is account group, then transaction type, then transaction
 * category, so the declaration order of the three components in {@link DisclosureGroupId} and the
 * parameter order of its constructor both follow it. The program's three adjacent {@code MOVE}
 * statements assign the last two in the opposite sequence, which is immaterial there because each
 * statement names its destination field; the reasoning for not transcribing that sequence sits beside
 * the key components, where a reader with only those statements in view will meet it.</p>
 *
 * <h2>The table is not this module's to own, and this module cannot write it</h2>
 *
 * <p>{@code reference.disclosure_groups} belongs to {@code reference-service}, which creates and
 * seeds it through its own migrations. AAP 0.4.1.3 scopes this module's cross-schema <b>write</b>
 * grants to the {@code ledger} and {@code account} schemas only, so the role it connects as holds
 * {@code SELECT} and nothing else on {@code reference}; the grants are created by
 * {@code data-migration/sql/V0__schemas_and_roles.sql}.</p>
 *
 * <p>Assumptions: that grant is the enforcement boundary and this type is shaped so the boundary is
 * never reached. It declares no mutator of any kind, which turns an attempted write from a runtime
 * permission failure -- naming a role and a relation, and nothing about the design mistake -- into an
 * expression that does not compile.</p>
 *
 * <h2>A missing DEFAULT row aborts the interest run, and it is not seeded here</h2>
 *
 * <p>The rate lookup has a fallback and it is narrower than it first appears. In
 * {@code 1200-GET-INTEREST-RATE} at {@code app/cbl/CBACT04C.cbl} lines 415 to 440 the status test at
 * line 422 treats both {@code '00'} and {@code '23'} -- found, and not found -- as non-fatal; line 436
 * then tests for {@code '23'} specifically and line 437 moves the literal {@code 'DEFAULT'} into the
 * account-group component <b>alone</b>, leaving type and category as they were, before line 438
 * retries. The retry tolerates no miss: at lines 443 to 460 the read carries <b>no</b>
 * {@code INVALID KEY} clause and the status test at line 446 accepts <b>only</b> {@code '00'}, so a key
 * still absent abends with {@code 'ERROR READING DEFAULT DISCLOSURE GROUP'}. There is no third
 * fallback and no default rate compiled into the program -- <b>the fallback does not resolve to zero
 * interest, it aborts the run.</b></p>
 *
 * <p>Assumptions: <b>the seed requirement is therefore not one row.</b> Because the substitution
 * replaces one component of three, the retry key is the padded {@code 'DEFAULT'} group followed by the
 * <i>original</i> type and category codes, so a distinct row is needed for every
 * {@code (tran_type_cd, tran_cat_cd)} pair any account can present. Reading it as a single row leaves
 * the run abending on the first pair that has no default. {@code reference-service} seeds seventeen
 * such rows in its {@code V2__seed_reference.sql}, one per pair in use.</p>
 *
 * <p>Assumptions: an interest run that aborts on a missing disclosure group is <b>not a defect in this
 * module</b>. This type is a read-only mapping over a table in a schema it did not create and does not
 * seed, so time spent here looking for the cause is time spent in the wrong service.</p>
 *
 * <h2>What this type does not do</h2>
 *
 * <p>It holds a rate and does not apply one. The accrual formula at {@code app/cbl/CBACT04C.cbl}
 * lines 464 to 465 belongs in the service layer through {@code com.carddemo.common.money.Money},
 * which owns the one rounding contract this migration needs. No arithmetic is performed here, and in
 * particular the division that converts an annual percentage into a monthly fraction is not.</p>
 *
 * @see DisclosureGroupId
 */
@Entity
// Alternatives Considered: reusing reference-service's own mapping of this table instead of
//     declaring a second one over it, or hoisting one shared mapping up into common-lib. The
//     first would create a compile-time dependency on another service's domain package, which
//     the architecture gate forbids and the architecture-rules Surefire execution fails a build
//     over rather than attracting a review comment. The second would put a persistence mapping
//     into common-lib, the one artifact all eight service modules depend on, making a
//     reference-data schema change a rebuild of every one of them, and common-lib deliberately
//     ships no persistence contract at all.
//     The consequence is worth stating as a rule: this module and reference-service agree
//     through the physical schema, and never through code.
// Trade-offs: two mappings over one table can drift and no compiler will notice. What is
//     accepted in exchange is that the drift is loud rather than silent, because both mappings
//     are asserted against the same physical schema when their process starts, so a mapping
//     naming a column the schema does not have fails before a row is read. Compile-time
//     agreement is genuinely given up, and that is the price of independent deployability.
@Immutable
// Alternatives Considered: leaving the table name unqualified and letting the pinned
//     connection search path resolve it, which is what this module's sibling mappings for its
//     own schema rely on. Declined for THIS mapping because the schema it names is one this
//     module holds no write grant on at all, and naming it on the annotation puts that boundary
//     where a reader of the entity finds it instead of requiring a trip to the connection
//     configuration to discover that this read crosses a context. This module spans four schemas
//     at three different grant levels, so the qualification is what makes the level legible.
// Assumptions: this declaration is DDL-passive, and that is a hard constraint rather than a
//     preference. It names no index, no unique constraint, no column definition and no key
//     generation strategy, because the table, its columns, its types and its primary key are
//     created by reference-service's V1__reference.sql and its mandatory rows are seeded by that
//     service's V2__seed_reference.sql. The provider is never permitted to emit DDL in this
//     module -- its setting is at most an assertion against the shape that already exists -- so
//     the annotations here DESCRIBE that shape rather than request it. An annotation that
//     requested DDL would be asking a module holding no write grant to create or alter another
//     service's schema, and it would surface as a permission error naming nothing about the
//     actual mistake. Seeding is prohibited on the same ground and by name: a DEFAULT row absent
//     from that schema is supplied by reference-service, never from this module, neither on
//     start-up nor through a migration carried here.
@Table(name = "disclosure_groups", schema = "reference")
public class DisclosureGroup {

    // Alternatives Considered: a JPA association from these key components to the entities
    //     that supply them -- a to-one mapping from the group component to Account, or from the
    //     type and category components to TransactionCategoryBalance -- expressed with a join
    //     column. Rejected on three independent grounds. The join would cross a schema boundary
    //     at a DIFFERENT grant level, so a lazy traversal written innocently in a job would
    //     issue a query this module's role may not be permitted, failing on a permission error
    //     that names nothing about the traversal. The baseline never navigates: it builds the
    //     key field by field and issues a keyed read, at app/cbl/CBACT04C.cbl lines 210 to 213.
    //     Decisively, the fallback at line 437 performs that read again after DELIBERATELY
    //     MUTATING one component of the key and keeping the other two -- searching for a row
    //     that is related to nothing the first key pointed at -- and an object association has
    //     no way to express a lookup whose key is rewritten between attempts. Nor is a database
    //     foreign key implied: reference-service's V1__reference.sql records that the baseline
    //     declares no such relationship, this record being a standalone layout, so a constraint
    //     added here would refuse a disclosure row the baseline accepts. The object graph
    //     therefore stays flat and every query a job issues is visible at the query site.
    // Alternatives Considered: three ways of expressing a three-part key were weighed.
    //     (a) A separate top-level DisclosureGroupId.java. Rejected because the package charter
    //     in package-info.java fixes this package's membership at eight entity types and no
    //     ninth, so a tenth compilation unit here would put the package outside its own declared
    //     contract. (b) An @IdClass over a nested class. Workable, and rejected because it
    //     requires the three components to be restated as fields of the entity as well, which is
    //     precisely the duplication that lets the two copies disagree. (c) A Java record as the
    //     embeddable. Rejected because a record is implicitly final with final fields and no
    //     no-argument constructor, and the provider's instantiation contract for an embedded
    //     identifier is reflective construction followed by field assignment; a plain class with
    //     a protected no-argument constructor is the portable choice. @EmbeddedId over a nested
    //     class also keeps the key addressable as one named object, which is what the fallback
    //     path needs: it replaces one component and reuses the other two, and an object with
    //     three components expresses that far more directly than three loose fields do.
    @EmbeddedId
    private DisclosureGroupId id;

    // Assumptions: NUMERIC(6,2) is the exact image of the PIC S9(04)V99 declared at
    //     app/cpy/CVTRA02Y.cpy line 9 -- four integer digits and two fractional, so six display
    //     bytes, the same 9999.99 ceiling and the same hundredth of a unit of resolution. The
    //     migration declares interest_rate NUMERIC(6,2) NOT NULL to match, and BigDecimal is the
    //     only Java type that carries it without loss. No approximate type is admissible here
    //     under the migration plan's transformation rule T3, and the reason is specific rather
    //     than stylistic: this rate is an operand and not a value that is merely displayed, so a
    //     rate such as 15.00 or 2.50 that no binary floating-point type represents exactly would
    //     carry its error into the multiplication and settle into money a customer is charged.
    // Assumptions: the arithmetic that consumes this value is owned by
    //     com.carddemo.common.money.Money and is never performed here, and the entity's scale is
    //     what makes the order of that arithmetic matter. The baseline parenthesises the
    //     multiplication in its own source -- the balance is multiplied by this rate first, and
    //     only the product is divided -- and because both operands carry scale 2 the raw product
    //     carries scale 4, so the product has to be formed at full precision before anything is
    //     scaled. Dividing first and multiplying second yields a different final cent on many
    //     balances, which is why transformation rule T4 keeps the order and why no helper here
    //     offers a shortcut that would invite it to be reordered.
    @Column(name = "interest_rate", precision = 6, scale = 2, nullable = false)
    private BigDecimal interestRate;

    /**
     * Creates an empty instance for the persistence provider to populate when it materialises a row.
     *
     * <p>Assumptions: the provider instantiates a mapped type reflectively through its no-argument
     * constructor and then writes the members directly, so this constructor exists to satisfy that
     * contract and is not an application entry point. It is {@code protected} rather than
     * {@code public} because the provider reaches a non-public constructor without difficulty while
     * application code cannot, which keeps a partially-populated instance -- one with a null
     * identifier, on which equality would be meaningless -- out of reach of a caller who has a
     * fully-specified constructor available instead.</p>
     */
    protected DisclosureGroup() {
        // Assumptions: the body is deliberately empty because the provider assigns both
        //     members after construction. Initialising anything here would be overwritten on a
        //     hydrate, and initialising the rate to zero would be actively harmful: a row that
        //     failed to populate would then read as a genuine zero rate, which is the one value
        //     that suppresses accrual outright, so a load fault would present as a business rule.
    }

    /**
     * Creates a fully specified disclosure-group row from values already decoded from the record.
     *
     * <p>Assumptions: both arguments arrive in their target representation, not their baseline one.
     * The rate is a signed decimal that {@code com.carddemo.common.codec.ZonedDecimalCodec} has
     * already decoded from zoned decimal with its sign overpunch. This constructor decodes nothing,
     * computes nothing and validates no business rule.</p>
     *
     * <p>Assumptions: this is not an insert path, and no caller should read it as one. The grant
     * this module holds on {@code reference.disclosure_groups} carries {@code SELECT} only, and the
     * type is annotated immutable, so rows originate in {@code reference-service} or in the
     * extract-transform-load load. This constructor serves test fixtures and in-memory
     * construction; a job in normal operation obtains an instance by reading one.</p>
     *
     * @param id the three-part lookup key of account group, transaction type and transaction
     *     category, in that physical order; must not be {@code null}
     * @param interestRate the annual percentage rate for that key, exact at scale 2 and permitted
     *     to be zero or negative because the source picture is signed
     * @throws NullPointerException if {@code id} is {@code null}
     */
    public DisclosureGroup(DisclosureGroupId id, BigDecimal interestRate) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.interestRate = interestRate;
    }

    /**
     * The three-part key that selects this rate: account group, transaction type, then category.
     *
     * @return the composite identifier, whose component order is the copybook's physical order
     */
    public DisclosureGroupId getId() {
        return id;
    }

    /**
     * The annual percentage rate applied to a category balance, exact at scale 2.
     *
     * @return the rate as a signed exact decimal, which may be zero -- a value that suppresses
     *     accrual for the category rather than indicating an absent rate
     */
    public BigDecimal getInterestRate() {
        return interestRate;
    }

    /**
     * The ten-character account group component of this row's key.
     *
     * @return the group identifier with any trailing blanks of its fixed-width column already
     *     stripped, or {@code null} on an instance whose identifier has not been populated
     */
    public String getAcctGroupId() {
        return id == null ? null : id.getAcctGroupId();
    }

    /**
     * The two-character transaction type component of this row's key.
     *
     * @return the transaction type code, or {@code null} on an instance whose identifier has not
     *     been populated
     */
    public String getTranTypeCd() {
        return id == null ? null : id.getTranTypeCd();
    }

    /**
     * The four-character transaction category component of this row's key.
     *
     * @return the transaction category code with its leading zeros intact, or {@code null} on an
     *     instance whose identifier has not been populated
     */
    public String getTranCatCd() {
        return id == null ? null : id.getTranCatCd();
    }

    /**
     * Compares this row with another for relational identity, using the composite key alone.
     *
     * <p>Assumptions: the composite key is this row's whole identity and the row is immutable, so
     * key-based equality and database identity coincide exactly. There is no mutable member that
     * could change while an instance sits in a hash-based collection, which is what makes this
     * type safe to place in one without the caveat a mutable entity needs.</p>
     *
     * @param other the object to compare with, which may be {@code null} or of any type
     * @return {@code true} when the argument is a disclosure group with an equal composite key
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        // Assumptions: a pattern match is used rather than an exact class comparison because
        //     the provider may return an instrumented subclass for a lazily-loaded reference, and
        //     a strict class comparison would then report a row as unequal to itself. No subclass
        //     of this type is authored, so widening the test costs nothing.
        if (!(other instanceof DisclosureGroup that)) {
            return false;
        }
        // Alternatives Considered: including the rate in the comparison was evaluated and
        //     rejected. Two rows cannot share this key, so the key alone already discriminates
        //     every row the table can hold; adding the rate would only let two readings of the
        //     SAME row compare unequal if the rate were ever changed by its owning service
        //     between them, which is the opposite of what identity should express.
        return Objects.equals(id, that.id);
    }

    /**
     * Produces a hash consistent with the composite-key equality above.
     *
     * <p>Assumptions: the value is stable for the life of an instance because the identifier has no
     * mutator and the type is annotated immutable, so an instance placed in a hash-based collection
     * cannot become unfindable there.</p>
     *
     * @return the hash of the composite identifier, or the hash of an absent value on an instance
     *     the persistence provider has not yet populated
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * Renders a short diagnostic summary of this row for a log line or an assertion message.
     *
     * <p>Trade-offs: this is a diagnostic aid and not a serialisation format, and nothing may parse
     * it. It is expressly NOT the fixed-width parity emitter: the 50-byte rendering that a
     * byte-for-byte comparison needs is produced through
     * {@code com.carddemo.common.codec.FixedWidthCodec}, and this method emits neither the declared
     * field widths, nor the zoned-decimal sign overpunch, nor the 28 pad bytes.</p>
     *
     * <p>Assumptions: the three key components are rendered inside single quotes deliberately, so
     * that any leading or trailing space in a value is visible in a log rather than being lost in
     * the surrounding text. An operator diagnosing a lookup that found nothing is looking for
     * exactly that class of difference, and an unquoted rendering would hide it.</p>
     *
     * @return a single-line rendering naming the type, the three key components and the rate
     */
    @Override
    public String toString() {
        return "DisclosureGroup[acctGroupId='" + getAcctGroupId()
                + "', tranTypeCd='" + getTranTypeCd()
                + "', tranCatCd='" + getTranCatCd()
                + "', interestRate=" + interestRate + ']';
    }

    /**
     * The three-part composite key of a disclosure-group row, in the copybook's physical order.
     *
     * <p>This is the migrated form of the group item {@code DIS-GROUP-KEY} at
     * {@code app/cpy/CVTRA02Y.cpy} line 5, which spans the first 16 bytes of the 50-byte record:
     * {@code DIS-ACCT-GROUP-ID PIC X(10)} at line 6, {@code DIS-TRAN-TYPE-CD PIC X(02)} at line 7
     * and {@code DIS-TRAN-CAT-CD PIC 9(04)} at line 8. The baseline reaches the record by this
     * whole group -- {@code app/cbl/CBACT04C.cbl} lines 47 to 51 select the file as
     * {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS RANDOM} and
     * {@code RECORD KEY IS FD-DISCGRP-KEY} -- so the key is one 16-byte value and not three
     * independent lookups.</p>
     *
     * <p>Assumptions: the constructor takes its three arguments in the physical order of the
     * key, which is NOT the order the interest program assigns them in. The parameter order is
     * group, then type, then category, matching {@code app/cpy/CVTRA02Y.cpy} lines 6 to 8, the file
     * description at {@code app/cbl/CBACT04C.cbl} lines 79 to 81, and the primary key the migration
     * declares. The program assigns the category at {@code app/cbl/CBACT04C.cbl} line 211 and the
     * type at line 212, in that sequence, so a caller who transcribes those two statements in
     * source order transposes the two codes. Both are short fixed-width strings, so a transposition
     * is accepted by the compiler and by every signature here, and it fails only as a lookup that
     * matches nothing -- which the baseline then reports as a missing disclosure group rather than
     * as a malformed key. The order is stated in this Javadoc for that reason: it is the one thing
     * a caller of this constructor cannot get wrong safely.</p>
     *
     * <p>Assumptions: the three components are documented as required but are not guarded against
     * {@code null} in the constructor. Guarding them was the alternative and is declined because it
     * would duplicate a constraint the schema already asserts -- all three columns are declared
     * {@code NOT NULL} by the owning service's migration -- and because validating the width and
     * form of a reference field is a representation concern this module's mapper package owns. The
     * cost accepted is that a partially built key can exist in memory and is refused when it
     * reaches the database rather than at the point of construction.</p>
     */
    @Embeddable
    // Assumptions: the persistence specification requires the type of an embedded identifier
    //     to be serializable, because the provider holds a detached copy of the key in its own
    //     structures -- the entry key of the persistence context and of a second-level cache
    //     region -- independently of the entity instance. The interface is therefore implemented
    //     to satisfy that contract rather than for any use this module makes of serialization
    //     itself, and the version below is pinned to a literal rather than left to the default
    //     computation so that recompiling the class cannot change it and invalidate a serialized
    //     form that a cache region is still holding.
    public static class DisclosureGroupId implements Serializable {

        /**
         * The serialization version, pinned to a literal because an embedded identifier is required
         * to be serializable and a computed value would change with any recompilation.
         */
        private static final long serialVersionUID = 1L;

        // Assumptions: this is the LEADING component, and the declaration order of the three
        //     members in this class is load-bearing rather than cosmetic. It is the copybook's
        //     own order at app/cpy/CVTRA02Y.cpy lines 6 to 8, which is the order of the group
        //     item at line 5 and therefore the order of the reference file's 16-byte record key,
        //     and it is confirmed a second time by the file description at
        //     app/cbl/CBACT04C.cbl lines 79 to 81 and a third time by the KEYS(16 0) operand of
        //     app/jcl/DISCGRP.jcl line 40. The owning service's migration declares its primary
        //     key on (acct_group_id, tran_type_cd, tran_cat_cd) to match.
        // Assumptions: CHAR(10) exactly, because the baseline presents this key in two
        //     different widths and only a fixed-character column treats them as one value.
        //     app/cpy/CVTRA02Y.cpy line 6 declares the component PIC X(10);
        //     app/cbl/CBACT04C.cbl line 210 moves ACCT-GROUP-ID into it, itself PIC X(10) at
        //     app/cpy/CVACT01Y.cpy line 16, so that path supplies ten characters; and on a
        //     lookup miss line 437 moves the SEVEN-character literal 'DEFAULT' into that same
        //     ten-byte field, where COBOL left-justifies and space-fills it. The key the retry
        //     searches for is therefore 'DEFAULT' followed by three spaces, while a predicate a
        //     reader would naturally write names the bare literal.
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "acct_group_id", length = 10, nullable = false, updatable = false)
        private String acctGroupId;

        // Assumptions: the SECOND component, DIS-TRAN-TYPE-CD PIC X(02) at
        //     app/cpy/CVTRA02Y.cpy line 7, occupying zero-based offsets 10 and 11. It is carried
        //     as text and not as a number because the picture is alphanumeric, so the two
        //     characters are a code rather than a quantity, and it is fixed-width rather than
        //     variable because it is a key component -- the declared width is part of the
        //     contract, which is what the 16-byte key operand depends on. The owning service
        //     declares it CHAR(2), deliberately the same type the transaction tables give the
        //     same code, so a lookup across them needs no cast.
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "tran_type_cd", length = 2, nullable = false, updatable = false)
        private String tranTypeCd;

        // Assumptions: the TRAILING component, DIS-TRAN-CAT-CD PIC 9(04) at
        //     app/cpy/CVTRA02Y.cpy line 8, occupying zero-based offsets 12 to 15.
        // Alternatives Considered: this member was derived one way from the copybook and then
        //     settled the other way by the migration, and the divergence is recorded rather than
        //     smoothed over. Deriving from the picture alone gives a bounded four-digit numeric
        //     code, which would narrow to a small integer the way a three-digit credit score
        //     does elsewhere in the migration. The owning service's V1__reference.sql instead
        //     declares tran_cat_cd CHAR(4) NOT NULL, and its own note gives the reason: six
        //     independent baseline sources carry this field as CHARACTER, and an integer column
        //     would drop the leading zeros all six depend on, turning 0001 into 1. Being part of
        //     the key makes that stronger here rather than weaker, because 0001 and 1 must not
        //     resolve to two different keys. A text type it is, therefore, and the picture is
        //     recorded above for provenance. Two further reasons make this binding, and not the
        //     narrower one, the only workable choice from inside this module: the provider
        //     asserts its mappings against the physical schema at start-up, so an integer member
        //     over a fixed-character column would fail that assertion before a row was read;
        //     and this module holds no grant that could alter the column to suit the mapping.
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "tran_cat_cd", length = 4, nullable = false, updatable = false)
        private String tranCatCd;

        /**
         * Creates an empty identity for the persistence provider to populate.
         *
         * <p>Assumptions: the specification requires a no-argument constructor on an embeddable
         * type, which the provider calls before assigning the three mapped components reflectively.
         * It is {@code protected} rather than {@code public} so that application code, which has a
         * fully-specified constructor available, cannot reach a key with no components set -- a
         * value on which equality and hashing would both be meaningless.</p>
         */
        protected DisclosureGroupId() {
            // Assumptions: the body is deliberately empty because the provider assigns all
            //     three components after construction, so anything initialised here would be
            //     overwritten on a hydrate. Defaulting a component to spaces or zeros would be
            //     worse than leaving it absent: it would produce a syntactically valid key that
            //     silently matches no row.
        }

        /**
         * Creates a complete lookup key from its three components, in the key's physical order.
         *
         * <p>Assumptions: the parameter order is the physical order of the key and not the order in
         * which {@code app/cbl/CBACT04C.cbl} assigns the components, which differ in their last
         * two. The type code precedes the category code here, as it does in the record and in the
         * primary key, while the program assigns the category at line 211 before the type at line
         * 212. Passing the two codes the other way round compiles, since both are short strings,
         * and produces a key that matches no row.</p>
         *
         * @param acctGroupId the account group identifier, and the component the DEFAULT fallback
         *     replaces; either the bare literal or the space-padded ten-character form is accepted,
         *     because the fixed-width column treats the two as one value
         * @param tranTypeCd the two-character transaction type code
         * @param tranCatCd the four-character transaction category code, with its leading zeros
         *     intact so that 0001 and 1 cannot become two distinct keys
         */
        public DisclosureGroupId(String acctGroupId, String tranTypeCd, String tranCatCd) {
            this.acctGroupId = acctGroupId;
            this.tranTypeCd = tranTypeCd;
            this.tranCatCd = tranCatCd;
        }

        /**
         * The account group identifier, which the DEFAULT fallback substitutes on a lookup miss.
         *
         * @return the group identifier as the fixed-width column presents it, with any trailing
         *     blanks of the declared ten-character width already stripped
         */
        public String getAcctGroupId() {
            return acctGroupId;
        }

        /**
         * The transaction type code this rate applies to.
         *
         * @return the two-character transaction type code
         */
        public String getTranTypeCd() {
            return tranTypeCd;
        }

        /**
         * The transaction category code this rate applies to, within its transaction type.
         *
         * @return the four-character category code, with leading zeros preserved
         */
        public String getTranCatCd() {
            return tranCatCd;
        }

        /**
         * Compares this key with another, treating all three components as significant.
         *
         * <p>Assumptions: all three components participate, because the key is the whole of the
         * identity and dropping any one of them would make two genuinely different rates compare
         * equal. In particular the type and category codes are compared separately rather than as
         * a concatenation, so a pair transposed by a caller is unequal to the correctly ordered
         * pair rather than accidentally matching it.</p>
         *
         * @param other the object to compare with, which may be {@code null} or of any type
         * @return {@code true} when the argument is a key whose three components are all equal
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DisclosureGroupId that)) {
                return false;
            }
            // Assumptions: the components are compared exactly as held, with no trimming and
            //     no padding applied here. Normalising either way was the alternative and is
            //     declined because it would make in-memory equality disagree with the database
            //     for one of the two forms, and a key that behaves one way in a collection and
            //     another in a query is worse than one that is simply literal. It is also
            //     unnecessary: the fixed-width column already strips the trailing blanks of a
            //     character(n) value on the way out, so a value that arrived padded and one that
            //     arrived bare are the same string by the time either reaches this method.
            //     Equality is therefore literal on values the database has already normalised.
            return Objects.equals(acctGroupId, that.acctGroupId)
                    && Objects.equals(tranTypeCd, that.tranTypeCd)
                    && Objects.equals(tranCatCd, that.tranCatCd);
        }

        /**
         * Produces a hash over all three components, consistent with the equality above.
         *
         * <p>Assumptions: every component is included and none has a mutator, so the value is
         * stable for the life of an instance and a key may be used safely in a hash-based
         * collection -- which is exactly how the provider itself uses it, as the entry key of its
         * persistence context.</p>
         *
         * @return the combined hash of the group identifier, the type code and the category code
         */
        @Override
        public int hashCode() {
            return Objects.hash(acctGroupId, tranTypeCd, tranCatCd);
        }

        /**
         * Renders the three components of this key for a log line or an assertion message.
         *
         * <p>Trade-offs: this is a diagnostic aid only and nothing may parse it. The components are
         * quoted individually rather than concatenated into the 16-byte form the baseline keys on,
         * which costs a reader the ability to see the key as the reference system does but makes
         * the boundary between the components unambiguous. That is the more useful property here,
         * because the two failure modes this rendering exists to expose are a transposed pair of
         * codes and a category code that lost a leading zero, and a concatenated 16-character run
         * shows neither of them plainly.</p>
         *
         * @return a single-line rendering naming the type and its three components
         */
        @Override
        public String toString() {
            return "DisclosureGroupId[acctGroupId='" + acctGroupId
                    + "', tranTypeCd='" + tranTypeCd
                    + "', tranCatCd='" + tranCatCd + "']";
        }
    }
}
