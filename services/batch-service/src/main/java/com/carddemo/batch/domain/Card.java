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

// WHY : Assumptions: this mapping exists because ONE batch program reads the card master, and it
//       reads it only to copy it into an export record. app/cbl/CBEXPORT.cbl:496 is that phase and
//       :535-:540 moves six card fields into the card view of the export record. The sibling
//       app/cbl/CBACT02C.cbl also reads the file, but it prints it and is migrated to
//       card-service, so within THIS module the export is the single reader. So this type is a READ
//       projection of a table another context owns, sized to that one use.
// WHY : Refactoring Rationale: this type is NEW, and its absence was a real gap rather than a
//       deliberate omission. ExportJob emitted three of the reference's five record types and said
//       so in its own class comment, naming the missing Java seam -- an entity and a repository for
//       account.customers and card.cards -- as the whole of what was outstanding, since the SELECT
//       grants already existed. A dataset carrying three of five views imports as a structurally
//       complete file, so the loss was silent in an artefact whose entire purpose is branch
//       migration. This type closes the card half of it.
// WHY : Alternatives Considered: reaching card-service over HTTP for a bulk card read, or issuing a
//       native query from the job. Both were rejected where the shortfall was recorded: a
//       table-sized transfer over a request path built for single-row work, and data access in the
//       job layer with native SQL in a module whose repository charter admits none and whose
//       architecture rules assert that boundary as a test. Mapping the table locally under the
//       grant that already exists is the shape the migration plan's section 0.4.1.3 already uses
//       for reporting-service's read-only cross-schema access.
// WHY : Assumptions: this mapping is DDL-PASSIVE. The table, its columns, its primary key, its
//       status check constraint and its by-account index idx_cards_account_id are all created by
//       the owning service's Flyway migration,
//       services/card-service/src/main/resources/db/migration/V1__card.sql, and this type declares
//       none of them. Hibernate's schema handling in this module is never stronger than an
//       assertion against the shape already present, because application.yml sets
//       ddl-auto: validate.
// WHY : Alternatives Considered: the schema is named explicitly on the annotation rather than left
//       to the connection's search path. Relying on the search path was the alternative and is
//       rejected outright here, because the pool's search path for this module is
//       batch,ledger,account,reference and DOES NOT INCLUDE card at all -- deliberately, since a
//       schema this module only reads has no business being resolvable by position. Naming the
//       schema is therefore not a stylistic preference but the mechanism by which this one table is
//       reachable, and it puts the grant boundary in the source where an auditor will look.
/**
 * A read-only projection of one {@code card.cards} row, as the export step copies it.
 *
 * <p>This is the migrated form of {@code 01 CARD-RECORD} at {@code app/cpy/CVACT02Y.cpy} lines 4 to
 * 11, whose own line 2 records the record as {@code RECLN 150}. The columns mapped here are the six
 * the export moves at {@code app/cbl/CBEXPORT.cbl:535-540}, less the one the target stores
 * enciphered, which the class comment below explains.</p>
 *
 * <p>The table is OWNED by {@code card-service}, whose {@code V1__card.sql} is the sole authority
 * for every column in it. This module holds {@code SELECT} on it and nothing else -- {@code USAGE}
 * on the schema is granted at {@code data-migration/sql/V0__schemas_and_roles.sql:1140} and the
 * table privilege at line 1268, with no {@code INSERT}, {@code UPDATE} or {@code DELETE} granted
 * anywhere in that schema -- and this mapping is deliberately shaped so that no more than that
 * grant permits is even expressible: the type is {@link Immutable}, every column is declared
 * non-updatable, and the interface that reads it extends the narrow
 * {@code org.springframework.data.repository.Repository} rather than a CRUD base.</p>
 *
 * <h2>One column is deliberately not mapped, and that is a registered divergence</h2>
 *
 * <p>{@code card.cards} stores the card verification value as {@code cvv_encrypted BYTEA}. It is not
 * mapped here and is not decrypted anywhere in this module, so the export emits the two-byte
 * encoding of zero for {@code EXP-CARD-CVV-CD} where the reference emits the stored digits. The
 * difference is registered as {@code D-EXPORT-PROTECTED-FIELDS-ELIDED} in
 * {@code docs/architecture/cobol-to-service-traceability.md}, together with the two customer
 * identifiers {@link Customer} elides for the same reason.</p>
 *
 * <p>Alternatives Considered: mapping the ciphertext column and decrypting it here so the export
 * carried the digits the reference carries. Rejected, and not on cost. The export dataset is a flat
 * file written to object storage and consumed by an import step, so decrypting into it would move a
 * verification value out of the one place the migration keeps it encrypted and into an artefact with
 * no field-level protection at all. Leaving the column unmapped makes the cleartext STRUCTURALLY
 * unreachable from this module rather than merely unused, which is the property worth having: a later
 * edit here cannot reintroduce the disclosure by accident, because there is no accessor to reach
 * for.</p>
 *
 * <p>Trade-offs: the accepted cost is stated plainly. An export produced by the migration is not
 * byte-identical to one produced by the reference on the two bytes at {@code app/cpy/CVEXPORT.cpy:96},
 * so a reader comparing the two files must expect a difference there and nowhere else in this view.
 * The span stays WELL FORMED rather than being left as a hole -- two zero bytes are the {@code COMP}
 * encoding of the value zero, which the field's own picture admits -- so the record still decodes and
 * the counterpart import still routes it. The card-service migration records that the baseline never
 * displayed this value on any of its three card screens, so nothing a user could see is lost.</p>
 *
 * @see com.carddemo.batch.repository.CardRepository
 * @see com.carddemo.batch.mapper.ExportRecordMapper
 */
@Entity
@Immutable
@Table(name = "cards", schema = "card")
public class Card {

    // WHY : Alternatives Considered: a numeric key, which the source picture PIC X(16) does not
    //       admit and which the owning migration rejects at length for two concrete reasons -- five
    //       of the fifty records in app/data/ASCII/carddata.txt begin with a zero digit that any
    //       numeric type discards, and sixteen significant digits exceed what an IEEE-754 double
    //       represents exactly. The CHAR binding is declared for the reason the sibling CardXref
    //       records: a Java String otherwise binds as VARCHAR and schema validation then rejects the
    //       owning migration's CHAR column even though the declared widths agree.
    /**
     * Maps {@code CARD-NUM} at {@code app/cpy/CVACT02Y.cpy:5}, {@code PIC X(16)}, offset 0, which the
     * export moves into {@code EXP-CARD-NUM} at {@code app/cbl/CBEXPORT.cbl:535}.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_num", length = 16, nullable = false, updatable = false)
    private String cardNum;

    /**
     * Maps {@code CARD-ACCT-ID} at {@code app/cpy/CVACT02Y.cpy:6}, {@code PIC 9(11)}, offset 16.
     *
     * <p>Assumptions: {@code BIGINT} because eleven digits overflow a 32-bit integer, and an integer
     * type at all because the field is used throughout as an identifier rather than as a quantity.
     * The owning migration's column records the same reasoning.</p>
     */
    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    // WHY : Assumptions: VARCHAR here rather than a CHAR binding, alone among this type's character
    //       columns, because the owning migration declares it VARCHAR(50) and states why: all fifty
    //       records of app/data/ASCII/carddata.txt pad the name to fill PIC X(50), so those trailing
    //       blanks are the record format asserting itself rather than part of a name. The export
    //       re-pads the value to the declared width on the way out, so nothing here has to reinstate
    //       them.
    /**
     * Maps {@code CARD-EMBOSSED-NAME} at {@code app/cpy/CVACT02Y.cpy:8}, {@code PIC X(50)}, offset
     * 30.
     */
    @Column(name = "embossed_name", length = 50, nullable = false, updatable = false)
    private String embossedName;

    // WHY : Assumptions: the source is a ten-character ISO-ordered text field, PIC X(10) at
    //       app/cpy/CVACT02Y.cpy:9, and the target is a DATE. The owning migration establishes the
    //       form from three independent declarations inside app/cbl/COCRDUPC.cbl rather than by
    //       inspection, and the plan's section 0.4.1.3 maps that picture to DATE precisely because
    //       the form is already most-significant-first, so a lexical comparison and a date
    //       comparison agree over it and no ordering changes. The export re-renders it in the same
    //       ISO form on the way out.
    // WHY : Assumptions: the property spells out a word the source field name CARD-EXPIRAION-DATE
    //       abbreviates, matching the owning column rather than the copybook. That correction is one
    //       of exactly three the migration takes, it is recorded in
    //       docs/architecture/data-model-and-schema-mapping.md, and the COBOL field is untouched --
    //       which is why the export field name a few lines below keeps the original spelling.
    /**
     * Maps {@code CARD-EXPIRAION-DATE} at {@code app/cpy/CVACT02Y.cpy:9}, {@code PIC X(10)}, offset
     * 80.
     */
    @Column(name = "expiration_date", nullable = false, updatable = false)
    private LocalDate expirationDate;

    // WHY : Assumptions: a one-character code and not a boolean. app/cpy/CVACT02Y.cpy:10 declares
    //       PIC X(01) and the baseline tests it against the closed two-value domain declared at
    //       app/cbl/COCRDUPC.cbl:89-91. The owning column carries a check constraint over the same
    //       two values, so a third character is refused by the database rather than coerced here.
    /**
     * Maps {@code CARD-ACTIVE-STATUS} at {@code app/cpy/CVACT02Y.cpy:10}, {@code PIC X(01)}, offset
     * 90.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_status", length = 1, nullable = false, updatable = false)
    private String activeStatus;

    /**
     * Required by the persistence provider, which materialises rows reflectively.
     */
    protected Card() {
        // WHY : Assumptions: protected rather than public, and empty rather than defaulting
        //       anything. The provider needs a no-argument constructor to instantiate a row before
        //       populating its fields; application code has no legitimate reason to construct a
        //       projection of a table this module cannot write, and a field defaulted here would be
        //       overwritten by the very next step of hydration while looking like a chosen value.
    }

    /**
     * Builds a complete row from the five values the export reads, in copybook declaration order.
     *
     * <p>Assumptions: the parameter order is the declaration order of
     * {@code app/cpy/CVACT02Y.cpy:5-10} with the verification value at line 7 omitted, so a reader can
     * check a call site against the copybook rather than against this signature. The omission is the
     * one recorded on this type: there is no parameter for a value this projection does not map.</p>
     *
     * <p>Alternatives Considered: declaring no public constructor at all and letting the persistence
     * provider be the only producer of an instance. Rejected because the export's encoding of this
     * view would then be reachable only through a database, which would make the one property worth
     * asserting cheaply -- that a row becomes the right 500 bytes under the right discriminator --
     * cost a container per assertion. The sibling {@code CardXref} and {@code Account} projections in
     * this package expose the same shape for the same reason. Trade-offs: the accepted cost is a second
     * way to obtain an instance; it is bounded by there being no setter, so an instance built here is
     * as immutable as one the provider hydrates.</p>
     *
     * <p>Alternatives Considered: validating the arguments. Refused for the reason the sibling
     * {@code CardXref} records: every value originates in a table {@code card-service} owns, so a guard
     * here would make this module an arbiter of data it holds no authority over and would refuse a read
     * of a row the owning service wrote deliberately. The structural obligations live where they are
     * enforced for every writer -- the owning migration declares four of these columns not-null and
     * constrains the status to two values.</p>
     *
     * @param cardNum the sixteen-character card number that keys this row, {@code CARD-NUM} at
     *     {@code app/cpy/CVACT02Y.cpy:5}
     * @param accountId the eleven-digit identifier of the account holding the card,
     *     {@code CARD-ACCT-ID} at {@code app/cpy/CVACT02Y.cpy:6}
     * @param embossedName the name embossed on the card, {@code CARD-EMBOSSED-NAME} at
     *     {@code app/cpy/CVACT02Y.cpy:8}
     * @param expirationDate the expiration date, {@code CARD-EXPIRAION-DATE} at
     *     {@code app/cpy/CVACT02Y.cpy:9}
     * @param activeStatus the one-character active-status code, {@code CARD-ACTIVE-STATUS} at
     *     {@code app/cpy/CVACT02Y.cpy:10}
     */
    public Card(String cardNum, Long accountId, String embossedName, LocalDate expirationDate,
            String activeStatus) {
        this.cardNum = cardNum;
        this.accountId = accountId;
        this.embossedName = embossedName;
        this.expirationDate = expirationDate;
        this.activeStatus = activeStatus;
    }

    /**
     * Returns the sixteen-character card number this row is keyed on.
     *
     * @return the card number as a {@code String}, never {@code null} for a hydrated row
     */
    public String getCardNum() {
        return this.cardNum;
    }

    /**
     * Returns the identifier of the account the card belongs to.
     *
     * @return the eleven-digit identifier as a {@code Long}, never {@code null} for a hydrated row
     */
    public Long getAccountId() {
        return this.accountId;
    }

    /**
     * Returns the name embossed on the card.
     *
     * @return the name as a {@code String}, never {@code null} for a hydrated row
     */
    public String getEmbossedName() {
        return this.embossedName;
    }

    /**
     * Returns the card's expiration date.
     *
     * @return the date as a {@code LocalDate}, never {@code null} for a hydrated row
     */
    public LocalDate getExpirationDate() {
        return this.expirationDate;
    }

    /**
     * Returns the one-character active-status code.
     *
     * @return {@code "Y"} or {@code "N"} as a {@code String}, never {@code null} for a hydrated row
     */
    public String getActiveStatus() {
        return this.activeStatus;
    }

    /**
     * Compares two projections by the card number the row is keyed on.
     *
     * @param other the object to compare with, which may be {@code null} or of any type
     * @return {@code true} when {@code other} is a card projection carrying an equal, non-null card
     *     number
     */
    @Override
    public boolean equals(Object other) {
        // WHY : Assumptions: identity is the primary key alone and never the other four columns,
        //       matching the sibling projections in this package. Two hydrations of one row taken at
        //       different times are the same card even if the embossed name was corrected between
        //       them, and a value-wise comparison would report them as different rows.
        // WHY : Assumptions: a null card number is never equal, not even to another null one. An
        //       unhydrated instance has no identity to compare, so treating two of them as equal
        //       would let a set silently collapse them.
        if (this == other) {
            return true;
        }
        if (!(other instanceof Card candidate)) {
            return false;
        }
        return this.cardNum != null && this.cardNum.equals(candidate.cardNum);
    }

    /**
     * Hashes the projection by the card number the row is keyed on.
     *
     * @return the card number's hash, or zero when the projection carries none
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.cardNum);
    }

    /**
     * Renders the projection for a diagnostic, disclosing neither the full card number nor the name.
     *
     * @return the type name and the card number with all but its last four digits replaced, never
     *     {@code null}
     */
    @Override
    public String toString() {
        // WHY : Trade-offs: the card number is masked rather than omitted, which is the one place
        //       this rendering differs from the sibling Customer's. A masked account number is what
        //       every diagnostic in this migration carries -- the export record's own rendering does
        //       the same -- because the last four digits are what a reader correlating a log line to
        //       a row actually uses, and the leading twelve are the part that must never reach a log.
        //       The embossed name is dropped outright, since it is personal data with no
        //       correlating value.
        return "Card{cardNum=" + maskedCardNumber() + "}";
    }

    /**
     * Masks all but the last four characters of the card number for a diagnostic.
     *
     * @return the masked number, or {@code "null"} when the projection carries none
     */
    private String maskedCardNumber() {
        // WHY : Assumptions: a value shorter than the retained tail is masked ENTIRELY rather than
        //       returned as-is. The column is CHAR(16) so a short value cannot arrive from the
        //       database, but an instance built in a test can carry one, and returning it unmasked
        //       would make the one case a reader cannot check the one case that discloses.
        if (this.cardNum == null) {
            return "null";
        }
        int retained = 4;
        if (this.cardNum.length() <= retained) {
            return "*".repeat(this.cardNum.length());
        }
        return "*".repeat(this.cardNum.length() - retained)
                + this.cardNum.substring(this.cardNum.length() - retained);
    }
}
