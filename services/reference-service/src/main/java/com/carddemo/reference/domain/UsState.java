//=============================================================================
// WHY : Assumptions: this file is written against exactly one authority for the
//       physical shape of the row it maps, namely
//       services/reference-service/src/main/resources/db/migration/
//       V1__reference.sql, whose us_states block at L424 through L441 declares
//       one column and one constraint and nothing further. The column name in
//       the mapping below is transcribed from that block rather than derived
//       from this class's member name, because the two deliberately differ:
//       an implicit naming strategy would resolve stateCode to state_code,
//       which is a column this schema does not have, and the mapping would
//       then fail against a database that is itself entirely consistent.
// WHY : Trade-offs: the rationale for this type sits in the class block below
//       and beside the member each entry explains, rather than being gathered
//       into this header. A single file-level essay was the alternative and is
//       refused, because the project Explainability rule asks at L27 that a
//       comment sit adjacent to the code it explains, and a decision about one
//       column reads as unattached once it is lifted away from that column.
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
 * Reference lookup of the United States state and territory codes an address is validated against.
 *
 * <p>This type maps {@code reference.us_states}, one admitted code per row. Membership is the whole
 * of its content: the reference baseline asks only whether a candidate code appears in a list of
 * literals, and the migrated form of that question is whether a row exists. It carries no business
 * rule, no request or response shape and no query, because this package's charter in
 * {@code package-info.java} assigns each of those elsewhere; that division is cited here rather
 * than argued again. </p>
 *
 * <h2>Baseline lineage</h2>
 *
 * <p>The source is a condition name over a working-storage item rather than a record layout.
 * {@code app/cpy/CSLKPCDY.cpy} L1012 declares {@code 01 US-STATE-CODE-TO-EDIT  PIC X(2).}, and its
 * L1013 {@code 88 VALID-US-STATE-CODE VALUES} carries the admitted literals over that item, opening
 * at L1014 with {@code 'AL'} and closing at L1069 with {@code 'VI'}. The copybook names three
 * lookup assets in its own header at L2 through L5, and this is the second of them. </p>
 *
 * <p>Exactly one program reads it. {@code app/cbl/COACTUPC.cbl} copies the book at L602, reaches
 * the state edit from L1600 and L1601, and opens the paragraph {@code 1270-EDIT-US-STATE-CD} at
 * L2493. Its L2494 moves the customer address state code into the two-character item, its L2495
 * tests {@code IF VALID-US-STATE-CODE}, and the else branch raises an input error at L2498 and the
 * field flag {@code FLG-STATE-NOT-OK} at L2499. The two other membership tests in that program, at
 * L2298 and L2542, are the concern of this package's sibling lookup entities and not of this type.
 * Every path named here is reference material, cited by path and line and never modified. </p>
 *
 * <p>The declaration at L1012 is immediately preceded by a comment at L1011 that reads as though it
 * introduced the phone area codes rather than the states. It is noted only as a navigational
 * landmark, so that a reader scrolling to L1012 knows they have arrived, and it is cited as it
 * stands. </p>
 *
 * <h2>What is mapped</h2>
 *
 * <pre>
 * CSLKPCDY.cpy  baseline declaration               target column         Java type
 * L1012         US-STATE-CODE-TO-EDIT  PIC X(2)    state_cd  CHAR(2)     String
 * L1013         88 VALID-US-STATE-CODE VALUES      56 rows, loaded by    none
 *                 L1014 'AL' through L1063 'WY'      V2 and not by
 *                 L1064 'DC'                         this type
 *                 L1065 'AS' through L1069 'VI'
 * </pre>
 *
 * <p>One column is the whole mapping, because one column is the whole table. There is no second
 * member to look for and none has been omitted. </p>
 *
 * <h2>Decisions</h2>
 *
 * <p>Assumptions: <b>the admitted domain is 56 codes, and it must not be narrowed to the fifty
 * states.</b> Extracting every literal between L1014 and L1069 of {@code app/cpy/CSLKPCDY.cpy}
 * yields 56 values with no duplicate among them. Their composition is the fifty state codes at
 * L1014 through L1063, then {@code 'DC'} at L1064, then five territory codes: {@code 'AS'} at
 * L1065, {@code 'GU'} at L1066, {@code 'MP'} at L1067, {@code 'PR'} at L1068 and {@code 'VI'} at
 * L1069. A reading that admitted only fifty would reject an address that
 * {@code app/cbl/COACTUPC.cbl} L2495 accepts, because the district and the five territories are
 * members of the very same condition name as the states and the baseline draws no distinction
 * between them. The table this type maps is named {@code us_states}, transcribed from
 * {@code V1__reference.sql} L424 exactly as it stands there; the name identifies the table and does
 * not bound its contents, so it is not a licence to hold fewer rows than the list carries. This is
 * recorded because the narrower reading is the one a reader arrives at from the name alone, and
 * because it would fail quietly: a rejected territory address presents as a data-entry mistake
 * rather than as a missing row. </p>
 *
 * <p>Assumptions: <b>the key is character data of declared width two, so the Java type is
 * {@code String}.</b> {@code app/cpy/CSLKPCDY.cpy} L1012 declares the validated item
 * {@code PIC X(2)}, an alphanumeric two units wide, and L1013 compares it against two-character
 * literals. All 56 literals are alphabetic, so a numeric type could not represent this domain at
 * all rather than merely representing it awkwardly: {@code 'AL'} has no integer value. The declared
 * width is part of the contract and not display padding, which is why {@code V1__reference.sql}
 * L438 declares the column {@code CHAR(2)} and why the width is restated on the mapping below.
 * This package's charter records the consequence that makes the character type the right one: a
 * probe arriving already blank-padded from a declared-width source still matches a {@code bpchar}
 * row, whereas a varying-width column would match nothing and would raise no error either, so the
 * difference would surface only as an address another service reports as invalid. Because a Java
 * {@code String} does not select that binding on its own, the mapping below declares the character
 * JDBC type explicitly; the comment there records what happens when it is left out. </p>
 *
 * <p>Assumptions: <b>no baseline table exists behind this type, so {@code V1__reference.sql} is the
 * only source of the column name, and no descriptive column is invented.</b> The other entities of
 * this package descend from a VSAM record layout or a Db2 definition, and each of those supplies a
 * legacy field name, a declared record length and a padding field to account for. This one descends
 * from a list of {@code 88}-level literals held over a working-storage item, so there is no legacy
 * column name to inherit, no record length to reconcile and no padding field to drop. What matters
 * more is what the list does not carry: between L1014 and L1069 it holds codes only, with no
 * display name and no description anywhere in it. The block at {@code V1__reference.sql} L424
 * through L441 accordingly declares one column, {@code state_cd CHAR(2) NOT NULL}, together with
 * one constraint, {@code pk_us_states}, over that column. This type therefore holds exactly one
 * persistent member. A name or description member was the obvious alternative and is refused twice
 * over: the column does not exist, so the mapping could only fail against the schema, and the
 * values would have to be composed from outside the baseline, which a lookup transcribed for
 * parity has no standing to do. </p>
 *
 * <p>Trade-offs: <b>the rows are exactly the baseline set, and none is invented.</b> Parity with
 * the reference behaviour governs, so what this lookup admits is what
 * {@code app/cpy/CSLKPCDY.cpy} L1013 admits, even where a present-day postal list would differ. The
 * compromise accepted is that a code the baseline never carried is refused here too, which is the
 * right answer for a system whose behaviour is being preserved rather than revised. Populating the
 * rows is not this type's concern in any case: {@code V2__seed_reference.sql} loads all 56 in the
 * copybook's own declaration order, and that migration is the single place where a change to the
 * admitted set would be made and reviewed. Stating the boundary here is what stops a future reader
 * from looking for the list in this file and concluding it went missing. </p>
 *
 * <p>Assumptions: <b>the consumer of this lookup belongs to another bounded context and reaches it
 * through the published contract, never through this type.</b> The address validation that needs
 * these codes runs in account-service, which neither owns nor seeds this table, and
 * {@code V1__reference.sql} L370 through L373 records the same division from the schema side. Rule
 * A2 of the shared architecture test at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * forbids a class under one bounded-context package root from depending on a {@code domain} class
 * owned by a different root, so that service may never import
 * {@code com.carddemo.reference.domain}; it reads this data through this service's HTTP contract
 * and its transfer objects instead. One consequence deserves stating, because the data invites the
 * mistake: a code absent from the seed does not fail here at all, since nothing in this context
 * validates an address. It presents as an address refused during account maintenance in a different
 * service, which is where a reader of that failure should begin. </p>
 *
 * <p>Assumptions: <b>this type declares no optimistic-locking version member.</b> The ruling is
 * this package's rather than this file's: the charter in {@code package-info.java} records that a
 * version counter belongs to the two transaction-reference entities alone, those being the only
 * rows this context replaces, and that the remaining four carry none because their tables declare
 * none. The schema is the authority and it agrees, since the block at {@code V1__reference.sql}
 * L424 through L441 declares no version column, so mapping one would name a column that is not
 * there. The absence also matches the operations this data supports, being loaded once and
 * thereafter read: a counter written once and never compared would advertise a maintenance path
 * this context does not offer. </p>
 *
 * <p>Alternatives Considered: <b>this is an ordinary class, not a Java record, and no annotation
 * processor generates any member of it.</b> A single immutable character member is the shape a
 * record expresses best, so the alternative is a real one. It is unavailable: a record is final,
 * its components are final, and it declares no constructor taking no arguments, while a
 * persistence entity requires exactly those three things of the class the provider instantiates
 * and populates. Generated accessors were rejected on independent ground, that a generated member
 * has no source line on which the Javadoc Rule 1 L15 requires could be written, and no exemption
 * is available to fall back on, because {@code config/checkstyle/checkstyle.xml} enables no
 * comment-driven and no annotation-driven suppression filter at all. Written constructors and
 * written accessors reach the same brevity by a route that leaves every member documentable. </p>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Every member below carries the Javadoc that user-specified Rule 1 (Explainability) requires at
 * L15, in the block form L22 names for Java, stating the elements L18 through L21 enumerate. The
 * three overriding methods are documented in full rather than deferring to their supertype, because
 * L15 grants no exemption and an inherited contract states none of those elements for the
 * implementation that overrides it. The labelled entries above discharge the second half of the
 * conjunctive gate L43 states, using the four categories named at L31 through L34; L40 forbids
 * leaving a choice undocumented where a reasonable alternative existed and L41 forbids a rationale
 * carrying no specific justification, so each entry names the line, the declared width or the count
 * it rests on. A green audit run is evidence for the docstring half of that gate alone, and only a
 * reader can judge the other half. The written convention all of this conforms to is stated once at
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and owned elsewhere; where migrated
 * behaviour departs from the baseline deliberately, the departure is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which is likewise maintained
 * elsewhere and referenced rather than reproduced. </p>
 */
@Entity
@Table(name = "us_states", schema = "reference")
public class UsState {

    // WHY : Assumptions: the column name is state_cd, transcribed from V1__reference.sql L438, while
    //       this member is stateCode. The two differ on purpose -- the column follows the
    //       abbreviated form the schema uses throughout, the member spells the word out -- so the
    //       name attribute is mandatory rather than decorative: an implicit naming strategy would
    //       derive state_code and resolve to nothing. The length and nullable attributes restate
    //       app/cpy/CSLKPCDY.cpy L1012 PIC X(2) and the NOT NULL of L438 at the mapping site, so a
    //       reader auditing either does not have to leave this file to find it.
    // WHY : Alternatives Considered: the JDBC type code is declared because relying on the length
    //       attribute alone does not work, and the failure was observed rather than predicted. A
    //       Java String otherwise selects the JDBC VARCHAR binding, so booting the provider against
    //       the migrated table reports "wrong column type encountered in column [state_cd] in table
    //       [reference.us_states]; found [bpchar (Types#CHAR)], but expecting [varchar(2)
    //       (Types#VARCHAR)]" even though the two widths agree. That matters here specifically:
    //       this service's own test profile sets ddl-auto to validate, so the mismatch would abort
    //       context startup for every repository integration test of this module. Spelling the
    //       physical type into a columnDefinition was the other candidate and is rejected, because
    //       it would copy vendor DDL into a mapping that has no authority to create the table,
    //       leaving the definition in two places no build compares. The type code selects the
    //       standard character binding for reads, writes and validation while leaving the physical
    //       definition wholly with V1__reference.sql, and it is the same mechanism the auth,
    //       transaction, batch and authorization contexts use for the identical reason.
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "state_cd", length = 2, nullable = false)
    private String stateCode;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: a persistence entity must declare a constructor taking no arguments, and the
     * provider invokes it before writing the one mapped column, {@code state_cd}, into the member
     * above. Visibility is public rather than the narrower protected the specification would also
     * accept, because this type guards no invariant -- there is a single two-character member and no
     * relationship between members to keep consistent -- and its instances are assembled by hand in
     * tests as well as by the provider; a narrower constructor would push those callers onto the
     * argument-taking form without making anything safer, since an instance built either way holds
     * whatever code it was given. </p>
     */
    public UsState() {
        // Assumptions: the body is empty by design rather than unfinished. The provider assigns the
        // member declared from app/cpy/CSLKPCDY.cpy L1012 directly after construction, so a value
        // written here would be overwritten on every load.
    }

    /**
     * Creates an instance carrying a state or territory code.
     *
     * <p>Alternatives Considered: this constructor admits its argument without testing it against
     * the 56 literals, and rejecting an unknown code was evaluated and refused. Membership is the
     * question this lookup answers rather than a precondition for building one of its rows, and the
     * baseline asks it in the same place: {@code app/cbl/COACTUPC.cbl} L2495 tests the candidate
     * against the list at the point of validation, not at the point the value is assembled. A guard
     * here would additionally refuse to load a row {@code V2__seed_reference.sql} seeded on purpose,
     * which would make a mapping the arbiter of data it does not own. The compromise accepted is
     * that a caller can build an instance the table would not produce, which is visible at that
     * call site and costs nothing at the one that matters. </p>
     *
     * @param stateCode the {@code String} holding the two-character state or territory code that
     *     keys this row, declared as {@code US-STATE-CODE-TO-EDIT PIC X(2)} at
     *     {@code app/cpy/CSLKPCDY.cpy} L1012 and carried in the {@code state_cd} column
     */
    public UsState(String stateCode) {
        this.stateCode = stateCode;
    }

    /**
     * Returns the two-character state or territory code this row is keyed by.
     *
     * @return the {@code String} value of the {@code state_cd} column, which is what a membership
     *     query matches on
     */
    public String getStateCode() {
        return stateCode;
    }

    /**
     * Replaces the state or territory code this row is keyed by.
     *
     * <p>Assumptions: the member is mutable and reachable because a persistence entity holds its
     * state in non-final members the provider can write, and this key is a natural one taken from
     * {@code app/cpy/CSLKPCDY.cpy} L1013 rather than a generated one, so the value arrives from a
     * caller or a loader instead of from the database. The method exists for that assignment and not
     * as an invitation to rename a seeded row; {@code V2__seed_reference.sql} owns which codes
     * exist, as the entries above record. </p>
     *
     * @param stateCode the {@code String} carrying the two-character code, drawn from the list
     *     that {@code app/cpy/CSLKPCDY.cpy} L1013 declares
     */
    public void setStateCode(String stateCode) {
        this.stateCode = stateCode;
    }

    /**
     * Reports whether another object denotes the same state or territory row as this one.
     *
     * <p>Assumptions: the comparison rests on the code, which is the whole of the key, since
     * {@code V1__reference.sql} L440 declares {@code pk_us_states} over {@code state_cd} alone and
     * this type has no second member to weigh. That no alternative basis exists is itself worth
     * recording, because on the wider entities of this migration the choice between key-only and
     * whole-record equality is a genuine one and a reader may arrive here expecting to find it
     * argued. </p>
     *
     * <p>Assumptions: the test is a pattern match rather than an exact-class comparison, because a
     * persistence provider may hand back a generated subclass of a mapped type, and an exact-class
     * comparison would then report two representations of one row unequal. </p>
     *
     * @param other the {@code Object} to compare against, which may be of any runtime type and may
     *     be null
     * @return a {@code boolean} that is true when the argument is a state lookup row carrying an
     *     equal code, and false otherwise
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UsState that)) {
            return false;
        }
        return Objects.equals(this.stateCode, that.stateCode);
    }

    /**
     * Returns a hash consistent with the code-based equality above.
     *
     * <p>Assumptions: the hash is drawn from the same single member the comparison uses, which is
     * what keeps the two contracts in step. It is computed through {@code Objects.hashCode} rather
     * than by calling the member's own method, so that an instance not yet carrying a code hashes
     * instead of failing; the provider constructs before it populates, so that state is reachable in
     * normal operation rather than only in a test. </p>
     *
     * @return an {@code int} hash of the two-character code, or zero when no code has been assigned
     *     yet
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.stateCode);
    }

    /**
     * Returns a diagnostic rendering of this lookup row.
     *
     * <p>Assumptions: the code is rendered in full because nothing about it is sensitive. A
     * two-character state or territory code is not a primary account number, a card verification
     * value or a national identifier, so the masking this migration applies to account and card data
     * has nothing to act on here, and withholding the value would remove the only thing a reader of
     * a failure needs. </p>
     *
     * <p>Trade-offs: the bracketed form is for diagnostics and is not a published contract. What this
     * service publishes is declared in {@code src/main/resources/openapi/reference-api.yaml} and
     * carried by the types in {@code com.carddemo.reference.dto}, so no response body and no report
     * line is composed from this string and its shape can change without disturbing an interface.
     * The compromise accepted is that a caller who parsed it anyway would be relying on something
     * deliberately left unstable. </p>
     *
     * @return a {@code String} rendering, on a single line, naming the type and the code it carries
     */
    @Override
    public String toString() {
        return "UsState[stateCode=" + stateCode + "]";
    }
}
