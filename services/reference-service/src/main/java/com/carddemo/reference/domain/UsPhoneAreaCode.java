//=============================================================================
// services/reference-service/.../domain/UsPhoneAreaCode.java
//
// Assumptions: every column name, SQL type, width and nullability bound below
//     was read out of db/migration/V1__reference.sql, which is the only source
//     for this table because it has no baseline DDL to inherit a name from.
//     Each binding is written out explicitly rather than left to an implicit
//     naming strategy, which would derive a different name, without reporting
//     anything, as soon as either name moved.
//=============================================================================
package com.carddemo.reference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persistence mapping of one accepted North American phone area code together with the baseline
 * sub-list that code belongs to.
 *
 * <p>This type maps {@code reference.us_phone_area_codes}, and the existence of a row is very
 * nearly the whole of the contract it carries. The reference baseline holds these codes as
 * condition names over a working-storage field rather than as a record, so there is no record
 * layout, no declared record length and no padding member to account for. The baseline test is
 * whether a candidate value appears in a list of literals, and the migrated form of that test is
 * whether a row exists and which class the row carries. </p>
 *
 * <h2>Baseline lineage</h2>
 *
 * <p>{@code app/cpy/CSLKPCDY.cpy} is the sole source, read as reference material and never
 * modified. L24 declares the value being validated as
 * {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX}, and three condition names sit over that one
 * three-byte field:</p>
 *
 * <pre>
 * CSLKPCDY.cpy  condition name              literals  maps to code_class
 * L30           VALID-PHONE-AREA-CODE            490   every row, either class
 * L521          VALID-GENERAL-PURP-CODE          410   'G'
 * L931          VALID-EASY-RECOG-AREA-CODE        80   'E'
 *                                             ------
 *                                            410 + 80 = 490, and the two share no member
 * </pre>
 *
 * <h2>Decisions</h2>
 *
 * <p>Refactoring Rationale: the structure being replaced is a condition-name list compiled into a
 * program, and it cannot carry across unchanged for two reasons. The literals reach their single
 * consumer, {@code app/cbl/COACTUPC.cbl}, through a copy directive, so the allow-list is part of
 * that program's compiled image and amending one code means recompiling it. And membership is
 * unqueryable, because a condition name yields a truth value and never the set behind it, so the
 * baseline can answer whether one candidate is acceptable but cannot enumerate what is. A relation
 * answers both from data, which is what lets this context publish the lookup to its caller over a
 * contract. What deliberately does not change is the set itself, or which class of it the baseline
 * accepts. </p>
 *
 * <p>Assumptions: <b>one table carrying a two-value classification column, because the two
 * sub-lists are a partition of the broad list.</b> The counts above are computed rather than
 * estimated, none of the three lists contains a duplicate, the two sub-lists share no member, and
 * their union leaves nothing over in either direction. Every one of the 490 codes therefore belongs
 * to exactly one class, which is what a single table with one classification column expresses:
 * because the partition is total the column is not nullable, and because it is disjoint one column
 * suffices where a pair of independent flags would additionally admit both-true and both-false rows
 * the copybook cannot express. </p>
 *
 * <p>Assumptions: <b>the classification column is load-bearing behaviour rather than a modelling
 * convenience.</b> {@code app/cpy/CSLKPCDY.cpy} is copied by exactly one program, and that
 * program's phone-area-code check at {@code app/cbl/COACTUPC.cbl} L2298 reads
 * {@code IF VALID-GENERAL-PURP-CODE}, so acceptance is restricted to the 410-member general-purpose
 * class while the broad and easily-recognisable lists are declared and tested by no program at all.
 * A table without this column would accept the 80 easily-recognisable codes the baseline declines,
 * and would do so without reporting anything. The program's other two checks belong to sibling
 * entities: L2495 to {@code UsState} and L2542 to {@code UsStateZipPrefix}. </p>
 *
 * <p>Alternatives Considered: <b>two separate tables</b>, one per sub-list, rejected because the
 * two sets partition one domain the copybook keeps on one field, so two tables would duplicate the
 * key space and turn broad-list membership into a union across two relations rather than a
 * primary-key probe. And <b>a single table with no classification column</b>, rejected because it
 * cannot express the general-purpose restriction the baseline enforces, leaving a reader unable to
 * tell a general-purpose code from an easily-recognisable one. </p>
 *
 * <p>Trade-offs: the cost of keeping the restriction is that the general-purpose probe is a
 * two-column predicate rather than a one-column one. That is accepted because the cheaper shapes
 * cannot state which sub-list a code came from, which is the only property distinguishing an
 * accepted code from a declined one. </p>
 *
 * <p>Assumptions: <b>the key is character data three bytes wide.</b>
 * {@code app/cpy/CSLKPCDY.cpy} L24 declares
 * {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX}, an alphanumeric field of three positions, and
 * every literal on all three lists is a quoted three-character value compared as characters rather
 * than evaluated as a number. The declared width is part of the contract, so the column is
 * {@code CHAR(3)} and the member is a {@code String}. Two consequences make the choice worth
 * stating rather than assuming. Refactoring Rationale: an earlier revision of this paragraph
 * justified the character binding by claiming that the easily-recognisable list at L931 opens with a
 * code demonstrating a leading zero. It does not -- that list opens with {@code '200'}, and no seeded
 * area code begins with a zero at all, because the North American numbering plan has never assigned
 * one. The claim was therefore false evidence for a conclusion that is nonetheless correct, and false
 * evidence is worse than none: a reader checking it finds it does not hold and has no way to tell
 * whether the conclusion survives. What actually decides the binding is the declared type itself. The
 * source field is alphanumeric, every literal on all three lists is a quoted three-character value,
 * and the comparison the baseline performs is a character comparison of a fixed-width field -- so the
 * width and the character semantics ARE the contract, and a numeric column would be a different
 * contract that merely happens to round-trip today's values. The stronger form of the point is that it
 * does not depend on the data: were a code beginning with zero ever assigned, a numeric column would
 * lose it silently, and a binding chosen from the declared type is already correct for that day
 * whereas one chosen from the current list would have to be revisited. {@code CHAR} rather than a varying-width type also matters,
 * because a blank-padded comparison ignores trailing spaces, so a value arriving from a
 * declared-width source still matches its row where a varying-width column would return nothing
 * and would raise no error either. Both members below therefore declare the character JDBC binding
 * explicitly, since a {@code String} left to its default would present as varying width and would
 * no longer describe the column V1 declares; the rationale sits beside each member. </p>
 *
 * <p>Assumptions: <b>there is no baseline table for this entity, so {@code V1__reference.sql} is
 * the only source of column names,</b> stated so that a reader does not go looking for a definition
 * that does not exist. The source is a set of 88-level literal lists over a working-storage field,
 * so unlike the transaction-type and transaction-category entities there is no earlier column name
 * to carry across and no declared record length to reconcile. If this mapping and that migration
 * ever disagree, the migration is right and this file is the defect. </p>
 *
 * <p>Assumptions: <b>the reader of this lookup sits in another bounded context and may not import
 * this type.</b> The consumer is {@code account-service} and its address validation, which neither
 * owns nor seeds this table, and
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * forbids a class under one bounded-context root from depending on a {@code domain} class owned by
 * another, so that service reaches this data through this service's published contract. One
 * consequence is easy to mistake for a defect here: a code absent from the seed does not fail in
 * this service at all, it surfaces as an address declined during account maintenance in the other
 * one. </p>
 *
 * <p>Trade-offs: <b>the rows are exactly the 490 the baseline declares, and no code is
 * invented.</b> Functional parity is measured against the copybook lists and not against the
 * administrator's present-day numbering plan, so a code the baseline omits is omitted here even
 * where an external register carries it, and a code the baseline carries is kept even where an
 * external register has retired it -- widening the set would accept an address the baseline
 * declines. Loading those rows belongs to {@code V2__seed_reference.sql}, which derives each row's
 * class from which copybook list contains the code rather than from the shape of its digits. </p>
 *
 * <p>Assumptions: <b>this entity declares no optimistic-locking version member.</b>
 * {@code V1__reference.sql} declares no version column on {@code us_phone_area_codes}, so a mapped
 * counter would name a column that does not exist. The table declares none because this is seeded
 * lookup data with read-only operations, so a counter would be written once and never read while
 * advertising a maintenance path this context does not offer for these rows. </p>
 */
@Entity
@Table(name = "us_phone_area_codes", schema = "reference")
public class UsPhoneAreaCode {

    /**
     * The classification value marking a code as general purpose, spelled as the schema stores it.
     *
     * <p>Assumptions: the literal is {@code 'G'}, one of the two values the
     * {@code ck_us_phone_area_codes_class} check constraint of {@code V1__reference.sql} admits,
     * and it stands for the 410-literal {@code VALID-GENERAL-PURP-CODE} list at
     * {@code app/cpy/CSLKPCDY.cpy} L521. This constant exists because a single letter cannot say
     * what it means: a call site comparing against a bare {@code "G"} would leave a reader unable to
     * tell which of the copybook's three lists was intended, and the letter is exactly the kind of
     * value whose meaning has to be named somewhere. This class is that somewhere. It is also the
     * value account-service's address validation selects on, because L2298 of
     * {@code app/cbl/COACTUPC.cbl} tests that list and no other. </p>
     */
    public static final String CODE_CLASS_GENERAL_PURPOSE = "G";

    /**
     * The classification value marking a code as easily recognisable, spelled as the schema stores
     * it.
     *
     * <p>Assumptions: the literal is {@code 'E'}, the second and last value the
     * {@code ck_us_phone_area_codes_class} check constraint of {@code V1__reference.sql} admits, and
     * it stands for the 80-literal {@code VALID-EASY-RECOG-AREA-CODE} list at
     * {@code app/cpy/CSLKPCDY.cpy} L931. Naming it matters more than naming its counterpart, because
     * a row carrying this value is a code the baseline declines during account maintenance, and a
     * reader who assumed every stored row was an accepted one would have no way to see that from
     * the letter alone. Codes of this class are mostly repeated-digit and toll-free forms, but that
     * regularity is a consequence of the administrator's list rather than its definition, so class
     * membership is read from the row and never inferred from the digits. </p>
     */
    public static final String CODE_CLASS_EASILY_RECOGNISABLE = "E";

    // Assumptions: three characters because app/cpy/CSLKPCDY.cpy L24 declares
    //     01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX, and character rather than numeric because
    //     every literal on the three lists is quoted and compared as characters. The column name
    //     is area_cd, taken from the us_phone_area_codes block of V1__reference.sql, which is the
    //     only source there is for it; the name is bound explicitly so that renaming this member
    //     cannot move the column an implicit naming strategy would otherwise derive from it.
    // Alternatives Considered: the JDBC type code selects the standard character binding
    //     because relying on the declared length alone does not reproduce V1's declared type. A
    //     Java String otherwise selects the JDBC VARCHAR binding, and schema validation then
    //     rejects this schema's CHAR(3) column even though the two widths agree; this service's
    //     own test profile sets ddl-auto to validate expressly to catch that drift, so the
    //     mismatch would abort a repository test at context startup. Spelling the physical type
    //     into a columnDefinition was rejected as well, because that duplicates vendor DDL inside
    //     a mapping which has no authority to create the table, leaving the definition in two
    //     places no build compares. The type code leaves the physical definition wholly with
    //     V1__reference.sql and is the mechanism the auth, batch, transaction and authorization
    //     contexts already use for the identical reason.
    // Assumptions: the column is declared NOT UPDATABLE and no method reassigns it. The
    //     identifier of a seeded lookup row is its identity rather than one of its attributes:
    //     this three-character code is the value a telephone number is validated
    //     against, so reassigning it in place would move a row's identity while the seed data that
    //     declared it stayed unchanged. Withholding the write from both the provider and the caller
    //     makes that impossible rather than merely discouraged.
    // Refactoring Rationale: an earlier revision offered a public setter for this member, which
    //     gave application code a second assembly route that was strictly weaker than the
    //     constructor below -- it accepted a null the constructor refuses, and it accepted a call on
    //     an already-persistent instance, which is the case that corrupts an identity rather than
    //     merely building one badly. Removing it leaves one way to build this row and no way to
    //     alter what it is.
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "area_cd", length = 3, nullable = false, updatable = false)
    private String areaCode;

    // Assumptions: one character, not nullable, and constrained by V1__reference.sql to the
    //     two values the constants above name. It is a String rather than a boolean because the
    //     schema stores 'G' and 'E' and a boolean cannot carry either, and rather than a
    //     persisted enumeration because that would bind the stored letter to a Java constant
    //     name; the class documentation records both alternatives. The column name is code_class,
    //     again from V1 and bound explicitly.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "code_class", length = 1, nullable = false)
    private String codeClass;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the persistence specification requires an entity to declare a constructor
     * taking no arguments, which the provider invokes before writing the {@code area_cd} and
     * {@code code_class} columns into the two members above. Visibility is public rather than
     * narrowed because this type is a mutable mapping with declared setters, so a caller assembling
     * a row member by member -- a mapper, or a test building a lookup row -- needs the same entry
     * point the provider uses, and offering it a narrower one would only invite reflection to reach
     * past it. </p>
     */
    public UsPhoneAreaCode() {
        // Assumptions: the body is empty by design rather than unfinished. The provider assigns both
        // members directly after construction on every load, so a default written here would be
        // overwritten before any caller could observe it. Defaulting the classification to either
        // constant would be worse than useless: it would let a row that failed to load its class
        // read as a legitimate member of one of the two copybook lists.
    }

    /**
     * Creates an instance from an area code and the baseline class that code belongs to.
     *
     * <p>Alternatives Considered: this constructor validates neither argument, and rejecting one
     * here was evaluated and refused. The pair is authoritative rather than user-supplied: it
     * originates in the literal lists of {@code app/cpy/CSLKPCDY.cpy} and reaches the schema through
     * {@code V2__seed_reference.sql}, so a guard here would duplicate the
     * {@code ck_us_phone_area_codes_class} check constraint that the migration already applies at
     * the one place that sees every row. Refusing a value the database accepts would also make a
     * load fail rather than a write, which reports the problem at the wrong end. The compromise
     * accepted is that a caller assembling an instance by hand can build a classification the
     * constraint would decline; that instance is refused on insert, and the accessors stay trivial
     * in the sense Rule 1 L23 uses, which an argument-checking type could not claim. </p>
     *
     * @param areaCode the {@code String} holding the three-character area code that keys this row,
     *     one of the 490 literals
     *     declared under {@code VALID-PHONE-AREA-CODE} at {@code app/cpy/CSLKPCDY.cpy} L30 and
     *     carried in the {@code area_cd} column
     * @param codeClass the {@code String} holding the one-character baseline class of that code,
     *     either
     *     {@link #CODE_CLASS_GENERAL_PURPOSE} for the L521 list or
     *     {@link #CODE_CLASS_EASILY_RECOGNISABLE} for the L931 list, carried in the
     *     {@code code_class} column
     */
    public UsPhoneAreaCode(String areaCode, String codeClass) {
        this.areaCode = areaCode;
        this.codeClass = codeClass;
    }

    /**
     * Returns the three-character area code this row is keyed by.
     *
     * @return the {@code String} value of the {@code area_cd} column, one of the 490 literals
     *     declared at
     *     {@code app/cpy/CSLKPCDY.cpy} L30
     */
    public String getAreaCode() {
        return areaCode;
    }

    /**
     * Returns the baseline class this area code belongs to.
     *
     * @return the {@code String} value of the {@code code_class} column, equal to either
     *     {@link #CODE_CLASS_GENERAL_PURPOSE} or {@link #CODE_CLASS_EASILY_RECOGNISABLE}
     */
    public String getCodeClass() {
        return codeClass;
    }

    /**
     * Replaces the baseline class this area code belongs to.
     *
     * @param codeClass the {@code String} class to store in the {@code code_class} column, either
     *     {@link #CODE_CLASS_GENERAL_PURPOSE} or {@link #CODE_CLASS_EASILY_RECOGNISABLE}
     */
    public void setCodeClass(String codeClass) {
        this.codeClass = codeClass;
    }

    /**
     * Reports whether another object denotes the same area-code row as this one.
     *
     * <p>Alternatives Considered: equality rests on the area code alone and deliberately excludes
     * the classification. Including it was the alternative and was rejected on the ground that the
     * area code is the entire primary key, as the {@code pk_us_phone_area_codes} constraint of
     * {@code V1__reference.sql} declares, so it alone settles which row is meant. Comparing the
     * class as well would let two representations of one row compare unequal whenever one of them
     * had not loaded or had been reclassified, which would make row identity depend on an attribute
     * of the row rather than on its key. The classification remains reachable through its accessor
     * for a caller that needs to distinguish the two baseline sub-lists. </p>
     *
     * @param other the {@code Object} to compare against, which may be of any type and may be null
     * @return the {@code boolean} true when the argument is an area-code row carrying an equal area
     *     code, and false
     *     otherwise
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UsPhoneAreaCode that)) {
            return false;
        }
        return Objects.equals(this.areaCode, that.areaCode);
    }

    /**
     * Returns a hash consistent with the key-only equality above.
     *
     * <p>Assumptions: only the area code is hashed, for the same reason only the area code is
     * compared. Drawing the hash from the classification as well would move an instance already held
     * in a hash-based collection the moment that attribute changed, which would break the contract
     * this method shares with equality. </p>
     *
     * @return the {@code int} hash of the three-character area code, or zero when no area code has
     *     been assigned
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.areaCode);
    }

    /**
     * Returns a diagnostic rendering of the area code and its baseline class.
     *
     * <p>Assumptions: both members are rendered in full because neither is sensitive. A
     * numbering-plan area code is public information published by the administrator named at
     * {@code app/cpy/CSLKPCDY.cpy} L26 to L28, and the classification is a single letter derived
     * from which copybook list carries the code, so the masking this migration applies to primary
     * account numbers, card verification values and national identifiers has nothing to act on
     * here. Withholding either value would leave a reader of a failed address validation without the
     * two facts that explain it. </p>
     *
     * @return the {@code String} single-line rendering naming the type, the area code and the
     *     classification
     */
    @Override
    public String toString() {
        return "UsPhoneAreaCode[areaCode=" + areaCode + ", codeClass=" + codeClass + "]";
    }
}
