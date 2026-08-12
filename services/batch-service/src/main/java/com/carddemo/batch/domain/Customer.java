package com.carddemo.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// WHY : Assumptions: this mapping exists because ONE batch program reads the customer master, and
//       it reads it only to copy it into an export record. app/cbl/CBEXPORT.cbl:243 is that
//       phase and :282-:299 moves eighteen customer fields into the customer view of the export
//       record, and no other batch program in app/cbl/ opens that file at all. So this type is a
//       READ projection of a table another context owns, sized to that one use.
// WHY : Refactoring Rationale: this type is NEW, and its absence was a real gap rather than a
//       deliberate omission. ExportJob emitted three of the reference's five record types and said
//       so in its own class comment, naming the missing Java seam -- an entity and a repository for
//       account.customers and card.cards -- as the whole of what was outstanding, since the SELECT
//       grants already existed. A dataset carrying three of five views imports as a structurally
//       complete file, so the loss was silent in an artefact whose entire purpose is branch
//       migration. This type closes the customer half of it.
// WHY : Alternatives Considered: reaching account-service over HTTP for a bulk customer read, or
//       issuing a native query from the job. Both were rejected where the shortfall was recorded:
//       a table-sized transfer over a request path built for single-row work, and data access in
//       the job layer with native SQL in a module whose repository charter admits none and whose
//       architecture rules assert that boundary as a test. Mapping the table locally under the
//       grant that already exists is the shape the migration plan's section 0.4.1.3 already uses
//       for reporting-service's read-only cross-schema access.
/**
 * A read-only projection of one {@code account.customers} row, as the export step copies it.
 *
 * <p>This is the migrated form of {@code 01 CUSTOMER-RECORD} at {@code app/cpy/CVCUS01Y.cpy} lines
 * 4 to 23, whose own line 2 records the record as {@code RECLN = 500}. The columns mapped here are
 * the eighteen the export moves at {@code app/cbl/CBEXPORT.cbl:282-299}, less the two the target
 * stores enciphered, which the class comment below explains.</p>
 *
 * <p>The table is OWNED by {@code account-service}, whose {@code V1__account.sql} is the sole
 * authority for every column in it. This module holds {@code SELECT} on it -- granted at
 * {@code data-migration/sql/V0__schemas_and_roles.sql:1215}, with schema-wide {@code UPDATE}
 * revoked two lines earlier at 1213 -- and this mapping is deliberately shaped so that no more than
 * that grant permits is even expressible: the type is {@link Immutable}, every column is declared
 * non-updatable, and the interface that reads it extends the narrow
 * {@code org.springframework.data.repository.Repository} rather than a CRUD base.</p>
 *
 * <h2>Two columns are deliberately not mapped, and that is a registered divergence</h2>
 *
 * <p>{@code account.customers} stores the national identifier as {@code ssn_encrypted BYTEA} and the
 * government-issued identifier as {@code govt_issued_id_encrypted BYTEA}. Neither is mapped here and
 * neither is decrypted anywhere in this module, so the export emits nine zero digits for
 * {@code EXP-CUST-SSN} and twenty blanks for {@code EXP-CUST-GOVT-ISSUED-ID} where the reference
 * emits the stored values. The difference is registered as
 * {@code D-EXPORT-PROTECTED-FIELDS-ELIDED} in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Alternatives Considered: mapping the two ciphertext columns and decrypting them here so the
 * export carried the cleartext the reference carries. Rejected, and not on cost. The export dataset
 * is a flat file written to object storage and consumed by an import step, so decrypting into it
 * would move two protected identifiers out of the one place the migration keeps them encrypted and
 * into an artefact with no field-level protection at all -- for a dataset whose only in-tree
 * consumer is the counterpart import. Leaving the columns unmapped makes the cleartext
 * STRUCTURALLY unreachable from this module rather than merely unused, which is the property worth
 * having: a later edit here cannot reintroduce the disclosure by accident, because there is no
 * accessor to reach for.</p>
 *
 * <p>Trade-offs: the accepted cost is stated plainly. An export produced by the migration is not
 * byte-identical to one produced by the reference on the two spans named above, so a reader
 * comparing the two files must expect a difference there and nowhere else, and an operator who
 * needs those identifiers must take them from the owning context under its own disclosure rules
 * rather than from this dataset. The spans stay WELL FORMED rather than being left as holes -- an
 * unsigned display field of nine zeros and twenty blanks are both valid values of their declared
 * pictures -- so the record still decodes and the counterpart import still routes it.</p>
 *
 * @see com.carddemo.batch.repository.CustomerRepository
 * @see com.carddemo.batch.mapper.ExportRecordMapper
 */
@Entity
@Immutable
@Table(name = "customers", schema = "account")
public class Customer {

    /**
     * Maps {@code CUST-ID} at {@code app/cpy/CVCUS01Y.cpy:5}, {@code PIC 9(09)}, offset 0, which the
     * export moves into {@code EXP-CUST-ID} at {@code app/cbl/CBEXPORT.cbl:282}.
     *
     * <p>Assumptions: {@code BIGINT} for a nine-digit identifier is the migration plan's uniform
     * mapping for any {@code PIC 9(n)} serving as a key, and the sibling {@code CardXref} records the
     * same reasoning for the same nine digits. A 32-bit integer would hold the range; one width for
     * every identifier in every schema is what keeps a later widening of a source field from forcing
     * a column type change.</p>
     */
    @Id
    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    /**
     * Maps {@code CUST-FIRST-NAME} at {@code app/cpy/CVCUS01Y.cpy:6}, {@code PIC X(25)}, offset 9.
     */
    @Column(name = "first_name", length = 25, nullable = false, updatable = false)
    private String firstName;

    // WHY : Assumptions: this column is NULLABLE where its two neighbours are not, and the
    //       asymmetry is the owning migration's rather than a liberty taken here. A middle name is
    //       the one name component a customer may legitimately not have, and the reference carries
    //       the absence as twenty-five blanks because a fixed-width record has no other way to
    //       express it. The export re-blanks it on the way out, which is why nothing here has to
    //       decide what an absent middle name means.
    /**
     * Maps {@code CUST-MIDDLE-NAME} at {@code app/cpy/CVCUS01Y.cpy:7}, {@code PIC X(25)}, offset 34.
     */
    @Column(name = "middle_name", length = 25, updatable = false)
    private String middleName;

    /**
     * Maps {@code CUST-LAST-NAME} at {@code app/cpy/CVCUS01Y.cpy:8}, {@code PIC X(25)}, offset 59.
     */
    @Column(name = "last_name", length = 25, nullable = false, updatable = false)
    private String lastName;

    // WHY : Assumptions: the three address lines are three separate columns and not a collection,
    //       because the source declares a FIXED arity -- app/cpy/CVCUS01Y.cpy:9 declares
    //       CUST-ADDR-LINE-1 through :11 CUST-ADDR-LINE-3 as three named fields, and the export
    //       view at app/cpy/CVEXPORT.cpy:29-30 declares the same three as OCCURS 3 TIMES. Three
    //       columns let the schema enforce the arity; an element collection or an array column
    //       would admit a fourth line that no record could carry.
    /**
     * Maps {@code CUST-ADDR-LINE-1} at {@code app/cpy/CVCUS01Y.cpy:9}, {@code PIC X(50)}, offset 84.
     */
    @Column(name = "addr_line_1", length = 50, nullable = false, updatable = false)
    private String addrLine1;

    /**
     * Maps {@code CUST-ADDR-LINE-2} at {@code app/cpy/CVCUS01Y.cpy:10}, {@code PIC X(50)}, offset
     * 134.
     */
    @Column(name = "addr_line_2", length = 50, updatable = false)
    private String addrLine2;

    /**
     * Maps {@code CUST-ADDR-LINE-3} at {@code app/cpy/CVCUS01Y.cpy:11}, {@code PIC X(50)}, offset
     * 184.
     */
    @Column(name = "addr_line_3", length = 50, nullable = false, updatable = false)
    private String addrLine3;

    // WHY : Alternatives Considered: length alone, without the CHAR binding, for the four
    //       fixed-width code columns below. Rejected for the reason the sibling CardXref records at
    //       length: a Java String otherwise binds as VARCHAR, and schema validation then rejects the
    //       owning migration's CHAR column even though the declared widths agree. JdbcTypeCode
    //       selects the standard CHAR binding for reads and for validation while leaving the
    //       physical definition wholly with account-service's migration.
    /**
     * Maps {@code CUST-ADDR-STATE-CD} at {@code app/cpy/CVCUS01Y.cpy:12}, {@code PIC X(02)}, offset
     * 234.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "addr_state_cd", length = 2, nullable = false, updatable = false)
    private String addrStateCd;

    /**
     * Maps {@code CUST-ADDR-COUNTRY-CD} at {@code app/cpy/CVCUS01Y.cpy:13}, {@code PIC X(03)},
     * offset 236.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "addr_country_cd", length = 3, nullable = false, updatable = false)
    private String addrCountryCd;

    /**
     * Maps {@code CUST-ADDR-ZIP} at {@code app/cpy/CVCUS01Y.cpy:14}, {@code PIC X(10)}, offset 239.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "addr_zip", length = 10, nullable = false, updatable = false)
    private String addrZip;

    /**
     * Maps {@code CUST-PHONE-NUM-1} at {@code app/cpy/CVCUS01Y.cpy:15}, {@code PIC X(15)}, offset
     * 249.
     */
    @Column(name = "phone_num_1", length = 15, updatable = false)
    private String phoneNum1;

    /**
     * Maps {@code CUST-PHONE-NUM-2} at {@code app/cpy/CVCUS01Y.cpy:16}, {@code PIC X(15)}, offset
     * 264.
     */
    @Column(name = "phone_num_2", length = 15, updatable = false)
    private String phoneNum2;

    // WHY : Assumptions: the date of birth IS mapped where the two identifiers above are not, and
    //       the line between them is the owning migration's own: it stores the national and
    //       government identifiers as ciphertext and stores this one as a plain DATE. Eliding a
    //       column the target holds in the clear would be a second, unregistered divergence chosen
    //       here rather than a consequence of the storage decision, so the reference's field is
    //       carried across.
    // WHY : Assumptions: the source is a ten-character ISO-ordered text field, PIC X(10) at
    //       app/cpy/CVCUS01Y.cpy:19, and the target is a DATE. The plan's section 0.4.1.3 maps that
    //       picture to DATE precisely because the form is already most-significant-first, so a
    //       lexical comparison and a date comparison agree over it and no ordering changes. The
    //       export re-renders it in the same ISO form on the way out.
    /**
     * Maps {@code CUST-DOB-YYYY-MM-DD} at {@code app/cpy/CVCUS01Y.cpy:19}, {@code PIC X(10)}, offset
     * 308.
     */
    @Column(name = "dob", nullable = false, updatable = false)
    private LocalDate dob;

    /**
     * Maps {@code CUST-EFT-ACCOUNT-ID} at {@code app/cpy/CVCUS01Y.cpy:20}, {@code PIC X(10)}, offset
     * 318.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "eft_account_id", length = 10, nullable = false, updatable = false)
    private String eftAccountId;

    /**
     * Maps {@code CUST-PRI-CARD-HOLDER-IND} at {@code app/cpy/CVCUS01Y.cpy:21}, {@code PIC X(01)},
     * offset 328.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "pri_card_holder_ind", length = 1, nullable = false, updatable = false)
    private String priCardHolderInd;

    // WHY : Assumptions: SMALLINT and Short, not an integer, because the value is bounded by its
    //       own declaration: app/cpy/CVCUS01Y.cpy:22 declares PIC 9(03), so three digits is the
    //       whole domain and the plan's section 0.4.1.3 maps a bounded three-digit score to
    //       SMALLINT. The export view holds the same value as PIC 9(03) COMP-3 at
    //       app/cpy/CVEXPORT.cpy:41, a packed field, so the widening from storage to record happens
    //       in the codec and not here.
    /**
     * Maps {@code CUST-FICO-CREDIT-SCORE} at {@code app/cpy/CVCUS01Y.cpy:22}, {@code PIC 9(03)},
     * offset 329.
     */
    @Column(name = "fico_credit_score", nullable = false, updatable = false)
    private Short ficoCreditScore;

    /**
     * Required by the persistence provider, which materialises rows reflectively.
     */
    protected Customer() {
        // WHY : Assumptions: protected rather than public, and empty rather than defaulting
        //       anything. The provider needs a no-argument constructor to instantiate a row before
        //       populating its fields; application code has no legitimate reason to construct a
        //       projection of a table this module cannot write, and a field defaulted here would be
        //       overwritten by the very next step of hydration while looking like a chosen value.
    }

    /**
     * Builds a complete row from the sixteen values the export reads, in copybook declaration order.
     *
     * <p>Assumptions: the parameter order is the declaration order of
     * {@code app/cpy/CVCUS01Y.cpy:5-22} with the two enciphered fields at lines 17 and 18 omitted, so
     * a reader can check a call site against the copybook rather than against this signature. That
     * ordering is the whole defence against the one real hazard here -- nine consecutive
     * {@code String} parameters, several of them plausible in each other's positions -- because it
     * makes the correct order verifiable against an artefact outside this file.</p>
     *
     * <p>Alternatives Considered: a builder, to remove that transposition hazard outright. Rejected on
     * balance rather than dismissed. It would add a nested type with sixteen documented setters and a
     * build step, and it would still let a caller assign a first name to the middle-name setter -- so
     * it trades a positional hazard for a nominal one while roughly doubling the surface. The sibling
     * {@code Account} projection in this package takes twelve positional arguments for the same
     * reason. Trade-offs: what is accepted is that a transposed call compiles; what limits the damage
     * is that every consumer of this type encodes into a fixed-width record whose fields are asserted
     * by name, so a transposition shows up as a wrong value in a named span rather than as silence.</p>
     *
     * <p>Alternatives Considered: declaring no public constructor at all and letting the persistence
     * provider be the only producer of an instance. Rejected because the export's encoding of this
     * view would then be reachable only through a database, which would make the one property worth
     * asserting cheaply -- that a row becomes the right 500 bytes under the right discriminator --
     * cost a container per assertion.</p>
     *
     * <p>Alternatives Considered: validating the arguments. Refused for the reason the sibling
     * {@code CardXref} records: every value originates in a table {@code account-service} owns, so a
     * guard here would make this module an arbiter of data it holds no authority over and would refuse
     * a read of a row the owning service wrote deliberately.</p>
     *
     * @param customerId the nine-digit identifier that keys this row, {@code CUST-ID} at
     *     {@code app/cpy/CVCUS01Y.cpy:5}
     * @param firstName the first name, {@code CUST-FIRST-NAME} at {@code app/cpy/CVCUS01Y.cpy:6}
     * @param middleName the middle name, {@code CUST-MIDDLE-NAME} at
     *     {@code app/cpy/CVCUS01Y.cpy:7}, or {@code null} when the customer has none
     * @param lastName the last name, {@code CUST-LAST-NAME} at {@code app/cpy/CVCUS01Y.cpy:8}
     * @param addrLine1 the first address line, {@code CUST-ADDR-LINE-1} at
     *     {@code app/cpy/CVCUS01Y.cpy:9}
     * @param addrLine2 the second address line, {@code CUST-ADDR-LINE-2} at
     *     {@code app/cpy/CVCUS01Y.cpy:10}, or {@code null} when the address has only two lines
     * @param addrLine3 the third address line, {@code CUST-ADDR-LINE-3} at
     *     {@code app/cpy/CVCUS01Y.cpy:11}
     * @param addrStateCd the two-character state code, {@code CUST-ADDR-STATE-CD} at
     *     {@code app/cpy/CVCUS01Y.cpy:12}
     * @param addrCountryCd the three-character country code, {@code CUST-ADDR-COUNTRY-CD} at
     *     {@code app/cpy/CVCUS01Y.cpy:13}
     * @param addrZip the ten-character postal code, {@code CUST-ADDR-ZIP} at
     *     {@code app/cpy/CVCUS01Y.cpy:14}
     * @param phoneNum1 the first telephone number, {@code CUST-PHONE-NUM-1} at
     *     {@code app/cpy/CVCUS01Y.cpy:15}, or {@code null} when the customer has none
     * @param phoneNum2 the second telephone number, {@code CUST-PHONE-NUM-2} at
     *     {@code app/cpy/CVCUS01Y.cpy:16}, or {@code null} when the customer has only one
     * @param dob the date of birth, {@code CUST-DOB-YYYY-MM-DD} at
     *     {@code app/cpy/CVCUS01Y.cpy:19}
     * @param eftAccountId the electronic-funds-transfer account identifier,
     *     {@code CUST-EFT-ACCOUNT-ID} at {@code app/cpy/CVCUS01Y.cpy:20}
     * @param priCardHolderInd the primary-card-holder indicator,
     *     {@code CUST-PRI-CARD-HOLDER-IND} at {@code app/cpy/CVCUS01Y.cpy:21}
     * @param ficoCreditScore the three-digit credit score, {@code CUST-FICO-CREDIT-SCORE} at
     *     {@code app/cpy/CVCUS01Y.cpy:22}
     */
    public Customer(Long customerId, String firstName, String middleName, String lastName,
            String addrLine1, String addrLine2, String addrLine3, String addrStateCd,
            String addrCountryCd, String addrZip, String phoneNum1, String phoneNum2,
            LocalDate dob, String eftAccountId, String priCardHolderInd, Short ficoCreditScore) {
        this.customerId = customerId;
        this.firstName = firstName;
        this.middleName = middleName;
        this.lastName = lastName;
        this.addrLine1 = addrLine1;
        this.addrLine2 = addrLine2;
        this.addrLine3 = addrLine3;
        this.addrStateCd = addrStateCd;
        this.addrCountryCd = addrCountryCd;
        this.addrZip = addrZip;
        this.phoneNum1 = phoneNum1;
        this.phoneNum2 = phoneNum2;
        this.dob = dob;
        this.eftAccountId = eftAccountId;
        this.priCardHolderInd = priCardHolderInd;
        this.ficoCreditScore = ficoCreditScore;
    }

    /**
     * Returns the customer identifier this row is keyed on.
     *
     * @return the nine-digit identifier as a {@code Long}, never {@code null} for a hydrated row
     */
    public Long getCustomerId() {
        return this.customerId;
    }

    /**
     * Returns the customer's first name.
     *
     * @return the name as a {@code String}, never {@code null} for a hydrated row
     */
    public String getFirstName() {
        return this.firstName;
    }

    /**
     * Returns the customer's middle name.
     *
     * @return the name as a {@code String}, or {@code null} when the customer has none
     */
    public String getMiddleName() {
        return this.middleName;
    }

    /**
     * Returns the customer's last name.
     *
     * @return the name as a {@code String}, never {@code null} for a hydrated row
     */
    public String getLastName() {
        return this.lastName;
    }

    /**
     * Returns the first address line.
     *
     * @return the line as a {@code String}, never {@code null} for a hydrated row
     */
    public String getAddrLine1() {
        return this.addrLine1;
    }

    /**
     * Returns the second address line.
     *
     * @return the line as a {@code String}, or {@code null} when the address has only two lines
     */
    public String getAddrLine2() {
        return this.addrLine2;
    }

    /**
     * Returns the third address line.
     *
     * @return the line as a {@code String}, never {@code null} for a hydrated row
     */
    public String getAddrLine3() {
        return this.addrLine3;
    }

    /**
     * Returns the two-character state code.
     *
     * @return the code as a {@code String}, never {@code null} for a hydrated row
     */
    public String getAddrStateCd() {
        return this.addrStateCd;
    }

    /**
     * Returns the three-character country code.
     *
     * @return the code as a {@code String}, never {@code null} for a hydrated row
     */
    public String getAddrCountryCd() {
        return this.addrCountryCd;
    }

    /**
     * Returns the ten-character postal code.
     *
     * @return the code as a {@code String}, never {@code null} for a hydrated row
     */
    public String getAddrZip() {
        return this.addrZip;
    }

    /**
     * Returns the first telephone number.
     *
     * @return the number as a {@code String}, or {@code null} when the customer has none
     */
    public String getPhoneNum1() {
        return this.phoneNum1;
    }

    /**
     * Returns the second telephone number.
     *
     * @return the number as a {@code String}, or {@code null} when the customer has only one
     */
    public String getPhoneNum2() {
        return this.phoneNum2;
    }

    /**
     * Returns the date of birth.
     *
     * @return the date as a {@code LocalDate}, never {@code null} for a hydrated row
     */
    public LocalDate getDob() {
        return this.dob;
    }

    /**
     * Returns the electronic-funds-transfer account identifier.
     *
     * @return the identifier as a {@code String}, never {@code null} for a hydrated row
     */
    public String getEftAccountId() {
        return this.eftAccountId;
    }

    /**
     * Returns the primary-card-holder indicator.
     *
     * @return {@code "Y"} or {@code "N"} as a {@code String}, never {@code null} for a hydrated row
     */
    public String getPriCardHolderInd() {
        return this.priCardHolderInd;
    }

    /**
     * Returns the credit score.
     *
     * @return the three-digit score as a {@code Short}, never {@code null} for a hydrated row
     */
    public Short getFicoCreditScore() {
        return this.ficoCreditScore;
    }

    /**
     * Compares two projections by the identifier the row is keyed on.
     *
     * @param other the object to compare with, which may be {@code null} or of any type
     * @return {@code true} when {@code other} is a customer projection carrying an equal, non-null
     *     identifier
     */
    @Override
    public boolean equals(Object other) {
        // WHY : Assumptions: identity is the primary key alone and never the other fifteen
        //       columns, matching the sibling projections in this package. Two hydrations of one row
        //       taken at different times are the same customer even if a name was corrected between
        //       them, and a value-wise comparison would report them as different rows.
        // WHY : Assumptions: a null identifier is never equal, not even to another null one. An
        //       unhydrated instance has no identity to compare, so treating two of them as equal
        //       would let a set silently collapse them.
        if (this == other) {
            return true;
        }
        if (!(other instanceof Customer candidate)) {
            return false;
        }
        return this.customerId != null && this.customerId.equals(candidate.customerId);
    }

    /**
     * Hashes the projection by the identifier the row is keyed on.
     *
     * @return the identifier's hash, or zero when the projection carries no identifier
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.customerId);
    }

    /**
     * Renders the projection for a diagnostic, disclosing no personal data.
     *
     * @return the type name and the identifier, never {@code null}
     */
    @Override
    public String toString() {
        // WHY : Assumptions: every other column of this row is personal data -- names, an address,
        //       telephone numbers, a date of birth and a credit score -- so the rendering carries
        //       the identifier and nothing else. A generated all-fields rendering would put a full
        //       customer record into any log line that interpolated an instance, which is the most
        //       common accidental disclosure there is. The identifier alone is what a reader
        //       correlating a log line to a row needs.
        return "Customer{customerId=" + this.customerId + "}";
    }
}
