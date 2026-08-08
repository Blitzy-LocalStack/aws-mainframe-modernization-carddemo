//=============================================================================
// WHY : Assumptions: the physical shape mapped below -- the table, the schema,
//       and the one column with its SQL type, width and nullability -- was read
//       out of services/reference-service/src/main/resources/db/migration/
//       V1__reference.sql, which the package charter names as the authority for
//       this schema. Nothing here infers a column from a copybook picture
//       clause: the picture determines the width and the character of the data,
//       while the migration determines the name. Where this file and that
//       migration could ever disagree, the migration is right and this file is
//       the defect.
// WHY : Assumptions: every member below carries documentation because the
//       project Explainability rule's validation gate is conjunctive at rule
//       line 43 -- a docstring stating purpose, parameters and return values
//       AND an adjacent comment giving the reason for each non-obvious choice,
//       with a member lacking either one failing review. The written convention
//       those blocks follow is docs/CODE_DOCUMENTATION_STANDARD.md, cited by
//       path and never restated. equals, hashCode and toString are documented
//       in spite of carrying Override, on two independent grounds: the
//       Checkstyle configuration clears its annotation exemption to the empty
//       list, and the rule itself grants an override no exemption either. A
//       green validate phase is therefore not on its own evidence of
//       compliance with the rule.
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
 * One permitted combination of a United States state code and the two leading digits of a postal
 * code, held as reference data so that an address can be validated against the set the baseline
 * admits.
 *
 * <p>The row is the whole of the contract. This lookup answers a single question and holds no
 * attribute beyond the key that answers it: whether a candidate state-and-postal-prefix pair
 * belongs to the admitted set. In the baseline that question is a test against a list of literals,
 * and its migrated form is whether a row exists.</p>
 *
 * <p>Assumptions: no baseline table stands behind this entity, so {@code V1__reference.sql} is the
 * only source of the column name. The baseline carries this lookup as a condition name over a
 * working-storage field, {@code app/cpy/CSLKPCDY.cpy} L1073
 * {@code 88 VALID-US-STATE-ZIP-CD2-COMBO VALUES} written over the L1072 field, rather than as a
 * VSAM record or a Db2 table. There is therefore no legacy column name to inherit, no declared
 * record length to account for, and no padding field in the record sense. The single column is
 * {@code state_zip_cd CHAR(4) NOT NULL} and it is the primary key.</p>
 *
 * <p>Alternatives Considered: the key is one column rather than two, and no association to the
 * sibling state lookup is declared. The baseline composes the four bytes and then tests them as a
 * unit: {@code app/cbl/COACTUPC.cbl} L2540 strings the state code and the first two postal digits
 * {@code INTO US-STATE-AND-FIRST-ZIP2}, and L2542 asks {@code IF VALID-US-STATE-ZIP-CD2-COMBO} of
 * the assembled value. Splitting the key into a state column and a postal-prefix column would
 * replace that one equality with a two-column comparison, and it would invite a many-to-one
 * association to the state lookup which the baseline never expresses and for which
 * {@code V1__reference.sql} declares no foreign key. Obliging every consumer to reassemble two
 * columns before comparing is the cost that split imposes, and it is the same reasoning the
 * package charter records for the column itself.</p>
 *
 * <p>Trade-offs: the single-column key gives up a direct equality path for the question of which
 * postal prefixes a given state admits. Answering that here means matching a prefix of the key
 * rather than comparing a dedicated column. The compromise is accepted because the baseline asks
 * only the membership question, at {@code app/cbl/COACTUPC.cbl} L2542, and no consumer of this
 * lookup asks the other one.</p>
 *
 * <p>Assumptions: the three remaining digits of the postal code have no column here. The group
 * item at {@code app/cpy/CSLKPCDY.cpy} L1071 spans seven characters, of which the L1072 field
 * contributes four; the balance is {@code app/cpy/CSLKPCDY.cpy} L1314
 * {@code 02 LAST-3-OF-ZIP           PIC X(3).}, holding the rest of the postal code being
 * validated. Those three digits belong to the value under validation rather than to the reference
 * data, and they are never stored. The drop is recorded in the same spirit as the drop of a
 * padding field, because a reader comparing a seven-character group against a four-character key
 * could otherwise read the difference as a column left unmapped.</p>
 *
 * <p>Trade-offs: exactly the baseline combinations are stored and none is invented.
 * {@code V2__seed_reference.sql} seeds the set the baseline carries: the literals at
 * {@code app/cpy/CSLKPCDY.cpy} L1073 to L1313 are 240 combinations spanning {@code AA34} to
 * {@code WY83}, and combinations whose postal pair opens with the digit zero do not appear among
 * them. Storing exactly that set is what functional parity means here, because the question this
 * table answers is the one the baseline answers rather than the one a wider enumeration of state
 * and postal-prefix pairs would. Any behavioural consequence of that choice is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which is maintained elsewhere and
 * referenced from here rather than reproduced.</p>
 *
 * <p>Assumptions: the consumer of this lookup sits outside this service and reaches it through the
 * published contract. Address maintenance in {@code account-service}, in its
 * {@code AddressValidationService}, is what reads these rows, and that service neither owns nor
 * seeds them. The shared architecture gate forbids a class under one bounded-context package root
 * from depending on a {@code domain} class owned by a different root, so that service consumes
 * this data as transfer objects over this service's HTTP contract and may never import
 * {@code com.carddemo.reference.domain}. A candidate value absent from the seeded set therefore
 * produces no failure here at all; it surfaces as an address the other service reports as
 * invalid.</p>
 *
 * <p>Assumptions: this type carries no optimistic-lock counter. The package charter's version
 * ruling settles that for every entity of this package, and {@code V1__reference.sql} confirms it
 * by declaring no version column on this table. These rows are seeded lookup data reached by
 * read-only operations, so a counter would be written once and never read while advertising a
 * maintenance path this context does not offer.</p>
 */
@Entity
// WHY : Alternatives Considered: the schema is named on the annotation rather than left to the
//       JDBC search path that the sibling config package pins, which is the package charter's
//       ruling for every mapping here. Relying on the path would couple resolution of this
//       entity to connection configuration owned by a different file, and the profile most
//       likely to lack that pin is the one that starts a throwaway database for an integration
//       test, so the failure would surface as an unresolved table in a test rather than at the
//       place the configuration was made.
@Table(name = "us_state_zip_prefixes", schema = "reference")
public class UsStateZipPrefix {

    /**
     * The four-character token this table exists to hold, and the primary key of its row.
     *
     * <p>Assumptions: the token is a two-letter state code followed by the two leading digits of
     * a postal code, in that order, exactly as the baseline literals at
     * {@code app/cpy/CSLKPCDY.cpy} L1074 through L1313 are written.</p>
     */
    // WHY : Assumptions: String against CHAR(4) because the token is alphanumeric in itself
    //       rather than a number that happens to be stored as text. The baseline declares it
    //       02 US-STATE-AND-FIRST-ZIP2 PIC X(4) at app/cpy/CSLKPCDY.cpy L1072, an alphanumeric
    //       picture, and every one of the 240 literals opens with two LETTERS, the set running
    //       from AA34 to WY83. A numeric column could not hold the first two characters at all,
    //       so no integer mapping is a candidate here and the question of how such a column
    //       would preserve padding does not arise.
    // WHY : Alternatives Considered: an argument from zero padding was tested against this data
    //       and does not carry, and it is recorded so that it is not imported by analogy from
    //       the sibling transaction-category code. That argument is sound where a field's
    //       picture is numeric, which the package charter's first ruling documents for the
    //       category code: app/cpy/CVTRA04Y.cpy L7 declares TRAN-CAT-CD PIC 9(04) and
    //       app/data/ASCII/trancatg.txt opens with the key 010001, so an integer column there
    //       would store 0001 as 1 and a key assembled from it would not locate its own row.
    //       Neither premise holds here: the picture at L1072 is alphanumeric, and counting the
    //       240 literals shows the digit zero never occupying the third character position, so
    //       the padding argument would be a rationale this data does not support.
    // WHY : Assumptions: the column is CHAR rather than a varying-width type, which is the
    //       charter's ruling on the sibling state lookup and is cited here rather than argued
    //       again. Every literal is exactly four characters, so the two candidate types would
    //       store these values identically and the difference is in comparison: bpchar ignores
    //       trailing blanks, so a token arriving padded from a declared-width source still
    //       matches its row, whereas the same probe against a varying-width column would return
    //       nothing and would not raise an error either.
    // WHY : Alternatives Considered: the JDBC type code is pinned to CHAR because a String field
    //       otherwise resolves to VARCHAR, and this module validates its mappings rather than
    //       trusting them -- src/test/resources/application-test.yml sets ddl-auto to validate
    //       precisely so a mapping that disagrees with the Flyway table aborts startup and names
    //       the object. Two narrower alternatives were tried against the real table and both
    //       were rejected on the validator's own report. Declaring length alone yields "found
    //       bpchar (Types#CHAR), but expecting varchar(4) (Types#VARCHAR)". Adding
    //       columnDefinition CHAR(4) yields "expecting char(4) (Types#VARCHAR)": it renames the
    //       type in generated DDL without moving the type CODE, which is the thing the validator
    //       compares, so the disagreement survives it while the annotation looks like it settled
    //       the matter. No attribute of the standard Column annotation sets the type code, so
    //       this one annotation is the only way to make the mapping agree with the column that
    //       the migration declares.
    // WHY : Trade-offs: that annotation is the persistence provider's own rather than the
    //       specification's, so this type names its provider in one place. The compromise is
    //       accepted because the alternative was to widen the column to VARCHAR in
    //       V1__reference.sql, which would invert the authority this file is written under: the
    //       migration is the authority for the physical shape, and editing the schema to suit a
    //       Java default would also discard the trailing-blank comparison the charter relies on
    //       for this lookup and for its two siblings. No architecture gate is affected, since the
    //       domain-isolation rule names AWS SDK, Spring Web and Servlet roots and the charter
    //       records that persistence mapping types are permitted here by design.
    // WHY : Assumptions: the column is declared NOT UPDATABLE and no method reassigns it. The
    //       identifier of a seeded lookup row is its identity rather than one of its attributes, and
    //       here the whole row IS the key -- this type maps a table of one column, so reassigning the
    //       combination would not modify a row, it would replace one row with a different one while
    //       the provider believed it was updating the first. Withholding the write from both the
    //       provider and the caller makes that impossible rather than merely discouraged.
    // WHY : Refactoring Rationale: an earlier revision offered a public setter for this member, which
    //       gave application code a second assembly route that was strictly weaker than the
    //       constructor below -- it accepted a null the constructor refuses, and it accepted a call on
    //       an already-persistent instance, which is the case that corrupts an identity rather than
    //       merely building one badly. Removing it leaves one way to build this row and no way to
    //       alter what it is.
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "state_zip_cd", length = 4, nullable = false, updatable = false)
    private String stateZipCd;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the specification requires a persistence entity to declare a constructor taking
     * no arguments, which the provider invokes before writing the mapped columns into the members
     * above. It is {@code protected} rather than public, which is the policy every other entity in this
     * codebase already follows: 30 of the 33 entity declarations across the eight services declare it
     * protected, and the five public ones were all in this package. The provider reaches a protected
     * constructor, and application code outside this package cannot allocate an unpopulated row and
     * pass it on as though it had been loaded.
     *
     * <p>Refactoring Rationale: this was public, and each of the five gave its own reason -- that a
     * mapper or a test needs to assemble a row member by member. Narrowing costs those callers nothing: the mapper uses the argument-taking form, which stays public, and no fixture constructs this type without arguments at all.
     */
    protected UsStateZipPrefix() {
        // WHY : Assumptions: the body is empty by contract rather than by oversight. Any default
        //       for the key would be indistinguishable from a value read out of a row, so the
        //       field is left unset for the provider or the caller to supply.
    }

    /**
     * Creates an instance holding one permitted state and postal-prefix combination.
     *
     * @param stateZipCd the combination to hold, a {@code String} of four characters made of a
     *     two-letter state code followed by the two leading digits of a postal code, as the
     *     baseline literals at {@code app/cpy/CSLKPCDY.cpy} L1074 through L1313 are written
     */
    public UsStateZipPrefix(String stateZipCd) {
        // WHY : Assumptions: the value is stored as given, with neither trimming nor case
        //       folding. The column is declared width and the baseline compares a four-byte
        //       field against four-byte literals, so a candidate that reached this service from
        //       a declared-width source has to compare as it arrived. Validating the shape here
        //       would also put a business rule inside a mapping type, which the package charter
        //       reserves for the service layer.
        this.stateZipCd = stateZipCd;
    }

    /**
     * Returns the state and postal-prefix combination this row holds.
     *
     * @return the four-character combination as a {@code String}, or {@code null} on an instance
     *     whose field has not been populated
     */
    public String getStateZipCd() {
        return stateZipCd;
    }

    /**
     * Compares this combination with another object for equality on the key alone.
     *
     * @param other the object to compare against, a {@code java.lang.Object} which matches only
     *     when it is another {@code UsStateZipPrefix} holding the same four-character combination
     * @return {@code true} when {@code other} is a {@code UsStateZipPrefix} whose combination
     *     equals this one's, and {@code false} otherwise
     */
    @Override
    public boolean equals(Object other) {
        // WHY : Assumptions: the key is the whole of the identity because it is the whole of the
        //       row. This table has one column, so an equality over every field and an equality
        //       over the identifier are the same comparison here; writing it over the identifier
        //       states which of the two was intended should a column ever be added beside it.
        if (this == other) {
            return true;
        }
        // WHY : Alternatives Considered: a pattern match on the type, rather than comparing
        //       getClass values. The two differ only for a subclass, and the provider may hand
        //       back a proxy whose class is a generated subtype of this one; a getClass equality
        //       would then report a loaded row and its own proxy as different values.
        if (!(other instanceof UsStateZipPrefix candidate)) {
            return false;
        }
        return Objects.equals(stateZipCd, candidate.stateZipCd);
    }

    /**
     * Returns a hash code derived from the key alone, consistent with {@code equals}.
     *
     * @return the hash code of the four-character combination as an {@code int}, and the hash of
     *     an unpopulated instance where the field has not been set
     */
    @Override
    public int hashCode() {
        // WHY : Assumptions: the single field that decides equality decides the hash, which is
        //       the contract a hash-based collection relies on. Objects.hash tolerates an unset
        //       field, so an instance built by the no-argument constructor can be placed in a
        //       set before its key is supplied rather than raising at that point.
        return Objects.hash(stateZipCd);
    }

    /**
     * Returns a diagnostic rendering of this row, naming the type and its key.
     *
     * @return a {@code String} carrying the simple type name and the four-character combination,
     *     intended for a log line or a test failure message rather than for any wire format
     */
    @Override
    public String toString() {
        // WHY : Trade-offs: the key is rendered in full rather than abbreviated. It is reference
        //       data naming a state and two postal digits, so it identifies no person and
        //       carries none of the exposure that argues for masking a primary account number;
        //       withholding it would instead leave a log line that could not be traced back to
        //       a row. The shape is deliberately not a wire format, because the published
        //       representation of this data is the transfer object of this service's contract.
        return "UsStateZipPrefix{stateZipCd=" + stateZipCd + "}";
    }
}
