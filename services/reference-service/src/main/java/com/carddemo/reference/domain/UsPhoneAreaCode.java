//=============================================================================
// services/reference-service/src/main/java/com/carddemo/reference/domain/
// UsPhoneAreaCode.java
//
// WHY : Assumptions: every column name, SQL type, width and nullability bound
//       below was read out of
//       services/reference-service/src/main/resources/db/migration/
//       V1__reference.sql, which this package's charter names as the authority
//       for the physical shape of the reference schema. That authority carries
//       more weight here than for any sibling entity, because this table has no
//       baseline DDL to inherit a name from: its source is a set of 88-level
//       literal lists inside a copybook, so V1 is not merely the preferred
//       source of column names, it is the only one. Each binding is therefore
//       written out explicitly rather than left to an implicit naming strategy,
//       which would derive area_cd from areaCode by convention and would
//       derive something else, without reporting anything, as soon as either
//       name moved.
// WHY : Trade-offs: the argument for a single table carrying a two-value
//       classification column is set out at length in the class documentation
//       rather than summarised here, because it is the one decision in this
//       file a reader is most likely to undo. The package charter records the
//       same ruling at package scope; the class documentation supplies the
//       arithmetic and the program line the ruling rests on, so that the two
//       can be checked against each other instead of taken on trust.
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
 * <p>{@code app/cpy/CSLKPCDY.cpy} is the sole source, and it is read as reference material and
 * never modified. Its L2 to L5 header names the three assets the copybook carries, of which the
 * North American phone area codes are the first, and its L26 to L28 comment records that the list
 * was obtained from the North American Numbering Plan Administrator's numbering-plan-area report.
 * L24 declares the value being validated as
 * {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX}, written in that form rather than with a
 * parenthesised repeat count. Three condition names sit over that one three-byte field:
 * {@code VALID-PHONE-AREA-CODE} at L30 carrying 490 literals,
 * {@code VALID-GENERAL-PURP-CODE} at L521 carrying 410, and
 * {@code VALID-EASY-RECOG-AREA-CODE} at L931 carrying 80. </p>
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
 * <p>What follows discharges the obligation user-specified Rule 1 (Explainability) states at L43,
 * using the four categories it names at L31 to L34, for every choice in this file that a
 * reasonable alternative could have gone the other way on. L40 forbids leaving such a choice
 * undocumented and L41 forbids a rationale that carries no specific justification, so each entry
 * below names the line, the literal count or the migration block it rests on. </p>
 *
 * <p>Refactoring Rationale: the structure being replaced is a condition-name list compiled into a
 * program, and the reasons it cannot carry across unchanged are worth stating rather than implying.
 * In the baseline the literals live inside {@code app/cpy/CSLKPCDY.cpy} and reach the single program
 * that reads them, {@code app/cbl/COACTUPC.cbl}, through a copy directive, so the allow-list is part
 * of that program's compiled image: amending one code means recompiling the program, and a second
 * consumer cannot consult the list without copying the literals and being recompiled in turn.
 * Membership is also unqueryable, because a condition name yields a truth value and never the set
 * behind it, so the baseline can answer whether one candidate is acceptable but cannot enumerate
 * what is. A relation answers both from data, which is what lets this context publish the lookup to
 * its caller over a contract rather than duplicate the literals into it. What deliberately does not
 * change is the set itself, or which class of it the baseline accepts. </p>
 *
 * <p>Assumptions: <b>one table carrying a two-value classification column, and the two sub-lists
 * are a partition of the broad list.</b> This is the package charter's fourth ruling, and the
 * evidence is a property of the copybook that can be computed rather than estimated. Counting the
 * literals in {@code app/cpy/CSLKPCDY.cpy} gives 490 under {@code VALID-PHONE-AREA-CODE} at L30,
 * 410 under {@code VALID-GENERAL-PURP-CODE} at L521 and 80 under
 * {@code VALID-EASY-RECOG-AREA-CODE} at L931. None of the three lists contains a duplicate. The
 * general-purpose and easily-recognisable sets are <b>disjoint</b>, sharing no member at all, and
 * their union is <b>exhaustive</b> over the broad set: subtracting the union from the 490 leaves
 * nothing, and subtracting the 490 from the union leaves nothing either, which is the same
 * property that 410 plus 80 equalling 490 reflects. Every one of the 490 codes therefore belongs
 * to <b>exactly one</b> class, which is precisely what a single table with one classification
 * column expresses: because the partition is total the column is not nullable and has no absent
 * case to represent, and because it is disjoint one column suffices where a pair of independent
 * flags would additionally admit both-true and both-false rows that the copybook cannot
 * express. </p>
 *
 * <p>Assumptions: <b>the classification column is load-bearing behaviour rather than a modelling
 * convenience, and this is the stronger half of the ruling.</b> {@code app/cpy/CSLKPCDY.cpy} is
 * copied by exactly one program, {@code app/cbl/COACTUPC.cbl}, and that program's phone-area-code
 * check at L2298 reads {@code IF VALID-GENERAL-PURP-CODE}. The baseline therefore validates an
 * area code against the 410-member general-purpose sub-list at that path and line, while the
 * 490-literal broad list at L30 and the 80-literal easily-recognisable list at L931 are declared
 * and tested by no program at all. Acceptance is restricted to the general-purpose class, and the
 * Java preserves that restriction by classifying every stored code. A single table <b>without</b>
 * this column would accept the 80 easily-recognisable codes that the baseline declines, and it
 * would do so without reporting anything, which is why the column is not optional. Any divergence
 * from baseline behaviour is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. The program's other two checks
 * belong to sibling entities and not here: L2495 {@code IF VALID-US-STATE-CODE} to
 * {@code UsState}, and L2542 {@code IF VALID-US-STATE-ZIP-CD2-COMBO} to
 * {@code UsStateZipPrefix}. </p>
 *
 * <p>Alternatives Considered: two shapes were weighed against the single classified table and both
 * were rejected. <b>Two separate tables</b>, one per sub-list, was rejected because the two sets
 * partition one domain that the copybook keeps on one field, so two tables would duplicate the key
 * space and would turn the question of whether a code is a member of the broad list at all into a
 * union across two relations rather than a primary-key probe. <b>A single table with no
 * classification column</b> was rejected because it cannot express the general-purpose restriction
 * the baseline enforces at {@code app/cbl/COACTUPC.cbl} L2298: with membership alone recorded, a
 * reader has no way to tell a general-purpose code from an easily-recognisable one and would
 * accept both. </p>
 *
 * <p>Trade-offs: the cost of keeping the restriction is that a consumer filters by classification
 * instead of simply testing whether a row exists, so the general-purpose probe is a two-column
 * predicate rather than a one-column one. That cost is accepted deliberately. The alternative
 * shapes are cheaper to query and cannot state which of the two baseline sub-lists a code came
 * from, and losing that is losing the only property that distinguishes an accepted code from a
 * declined one. </p>
 *
 * <p>Assumptions: <b>the key is character data three bytes wide.</b>
 * {@code app/cpy/CSLKPCDY.cpy} L24 declares
 * {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX}, an alphanumeric field of three positions, and
 * every literal on all three lists is a quoted three-character value compared as characters rather
 * than evaluated as a number. The declared width is part of the contract, so the column is
 * {@code CHAR(3)} and the member is a {@code String}. Two consequences make the choice worth
 * stating rather than assuming. A numeric column would discard a leading zero, and the
 * easily-recognisable list at L931 opens with {@code '200'}, so a numeric key could not represent
 * the codes of that class faithfully. {@code CHAR} rather than a varying-width type also matters,
 * because a blank-padded comparison ignores trailing spaces, so a value arriving from a
 * declared-width source still matches its row where a varying-width column would return nothing
 * and would raise no error either. Both members below therefore declare the character JDBC binding
 * explicitly, since a {@code String} left to its default would present as varying width and would
 * no longer describe the column V1 declares; the rationale sits beside each member. </p>
 *
 * <p>Assumptions: <b>there is no baseline table for this entity, so
 * {@code V1__reference.sql} is the only source of column names.</b> This is stated explicitly so
 * that a reader does not go looking for a definition that does not exist. The source is a set of
 * 88-level literal lists over a working-storage field in a copybook, not a keyed data set and not
 * a relational table, so unlike the transaction-type and transaction-category entities of this
 * package there is no earlier column name to carry across, no declared record length to reconcile
 * and no padding member to leave unmapped. Both bindings below, {@code area_cd} and
 * {@code code_class}, together with their character type, their widths, their nullability and the
 * two values the check constraint admits, were taken from the {@code us_phone_area_codes} block of
 * that migration. If this mapping and that migration ever disagree, the migration is right and this
 * file is the defect, and this service's test profile is configured to say so at startup rather
 * than leave the disagreement to a query. </p>
 *
 * <p>Assumptions: <b>the reader of this lookup sits in another bounded context and may not import
 * this type.</b> The consumer is {@code account-service} and its address validation, which
 * neither owns nor seeds this table. The shared architecture gate at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * forbids a class under one bounded-context package root from depending on a {@code domain} class
 * owned by a different root, so that service reaches this data through this service's published
 * contract and its transfer objects and may never import {@code com.carddemo.reference.domain}.
 * Sharing this entity across the boundary would give one schema a second owner, so it is not
 * available as a shortcut. One consequence follows from the same arrangement and is easy to
 * mistake for a defect here: a code absent from the seed does not fail in this service at all, it
 * surfaces as an address declined during account maintenance in the other one. </p>
 *
 * <p>Trade-offs: <b>the rows are exactly the 490 the baseline declares, and no code is
 * invented.</b> Functional parity is measured against the copybook lists and not against the
 * administrator's present-day numbering plan, so a code the baseline omits is omitted here even
 * where an external register carries it, and a code the baseline carries is kept even where an
 * external register has retired it. Widening the set would accept an address the baseline
 * declines, which is a behavioural change rather than a data refresh. Loading those rows is owned
 * by {@code V2__seed_reference.sql}, not by this type: that migration inserts all 490, split 410
 * as {@code 'G'} and 80 as {@code 'E'}, and derives each row's class from which copybook list
 * contains the code rather than from the shape of its digits. </p>
 *
 * <p>Assumptions: <b>this entity declares no optimistic-locking version member.</b> The package
 * charter rules that a version counter exists on two of the six entities of this package and on no
 * others, and this is one of the four that carry none. The physical reason is immediate:
 * {@code V1__reference.sql} declares no version column on {@code us_phone_area_codes}, so a mapped
 * counter would name a column that does not exist. The reason the table declares none is that this
 * is seeded lookup data with read-only operations, so a counter here would be written once and
 * never read while advertising a maintenance path that this context does not offer for these
 * rows. </p>
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

    // WHY : Assumptions: three characters because app/cpy/CSLKPCDY.cpy L24 declares
    //       01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX, and character rather than numeric because
    //       every literal on the three lists is quoted and compared as characters. The column name
    //       is area_cd, taken from the us_phone_area_codes block of V1__reference.sql, which is the
    //       only source there is for it; the name is bound explicitly so that renaming this member
    //       cannot move the column an implicit naming strategy would otherwise derive from it.
    // WHY : Alternatives Considered: the JDBC type code selects the standard character binding
    //       because relying on the declared length alone does not reproduce V1's declared type. A
    //       Java String otherwise selects the JDBC VARCHAR binding, and schema validation then
    //       rejects this schema's CHAR(3) column even though the two widths agree; this service's
    //       own test profile sets ddl-auto to validate expressly to catch that drift, so the
    //       mismatch would abort a repository test at context startup. Spelling the physical type
    //       into a columnDefinition was rejected as well, because that duplicates vendor DDL inside
    //       a mapping which has no authority to create the table, leaving the definition in two
    //       places no build compares. The type code leaves the physical definition wholly with
    //       V1__reference.sql and is the mechanism the auth, batch, transaction and authorization
    //       contexts already use for the identical reason.
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "area_cd", length = 3, nullable = false)
    private String areaCode;

    // WHY : Assumptions: one character, not nullable, and constrained by V1__reference.sql to the
    //       two values the constants above name. It is a String rather than a boolean because the
    //       schema stores 'G' and 'E' and a boolean cannot carry either, and rather than a
    //       persisted enumeration because that would bind the stored letter to a Java constant
    //       name; the class documentation records both alternatives. The column name is code_class,
    //       again from V1 and bound explicitly.
    // WHY : Assumptions: the character binding is applied here for the same reason it is applied to
    //       the key above, and it matters just as much on one character as on three. V1 declares
    //       this column CHAR(1), so without the type code the mapping would present VARCHAR and
    //       validation would refuse the table. It also keeps a read faithful: a comparison against
    //       either constant is a comparison against the single character the column stores.
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
     * Replaces the three-character area code this row is keyed by.
     *
     * @param areaCode the {@code String} area code to store in the {@code area_cd} column, three
     *     characters wide
     *     per {@code app/cpy/CSLKPCDY.cpy} L24
     */
    public void setAreaCode(String areaCode) {
        this.areaCode = areaCode;
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
     * <p>Assumptions: the test is a pattern match rather than an exact-class comparison, because a
     * persistence provider may return a generated subclass of a mapped type and an exact-class
     * comparison would then report two representations of one row unequal. </p>
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
     * <p>Trade-offs: this rendering is for diagnostics and is not a published contract. The wire
     * shape is owned by the transfer objects of {@code com.carddemo.reference.dto}, which compose it
     * from the accessors above, so reordering or widening this string cannot disturb what a caller
     * receives. </p>
     *
     * @return the {@code String} single-line rendering naming the type, the area code and the
     *     classification
     */
    @Override
    public String toString() {
        return "UsPhoneAreaCode[areaCode=" + areaCode + ", codeClass=" + codeClass + "]";
    }
}
