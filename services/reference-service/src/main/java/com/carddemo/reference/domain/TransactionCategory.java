//=============================================================================
// WHY : Assumptions: every column name, declared width and nullability stated
//       below was read out of
//       services/reference-service/src/main/resources/db/migration/
//       V1__reference.sql, which this package's charter in package-info.java
//       names as the authority for the physical shape of the reference schema.
//       Where this file and that migration could ever disagree, the migration
//       is right and this file is the defect.
// WHY : Assumptions: every path beginning app/ is reference material. It is
//       read as the specification for what this type must carry and is cited
//       by path and line, never modified.
//=============================================================================
package com.carddemo.reference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One transaction category of the {@code reference} schema: a transaction type, a category code
 * beneath it, the description a user reads, and the counter that guards a concurrent replace.
 *
 * <h2>Purpose</h2>
 *
 * <p>This type maps a Java object onto a row of {@code reference.transaction_categories} and holds
 * no behaviour beyond the guards that keep a row well formed. Its identity is composite because the
 * source record makes it composite: {@code app/cpy/CVTRA04Y.cpy} groups
 * {@code TRAN-TYPE-CD PIC X(02)} at L6 and {@code TRAN-CAT-CD PIC 9(04)} at L7 together under the
 * L5 group {@code TRAN-CAT-KEY}, and every reader of that record keys on both halves at once.</p>
 *
 * <p>Refactoring Rationale: one entity stands in for two physical representations of one logical
 * row. The baseline maintains the category twice over. It is the 60-byte indexed record that
 * {@code app/cbl/CBTRN03C.cbl} reads, declaring the file across L45 to L49 with
 * {@code RECORD KEY IS FD-TRAN-CAT-KEY} and taking its layout at L108 through
 * {@code COPY CVTRA04Y.}; and it is the relational table
 * {@code CARDDEMO.TRANSACTION_TYPE_CATEGORY} that
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} defines at L1 and that the extension
 * maintenance screens read and write. The target holds one table, so a description edited through a
 * screen is the same row a report resolves. The divergence is recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <h2>The category code is character data, not a number</h2>
 *
 * <p>Assumptions: {@code cat_cd} is {@code CHAR(4)} in the schema and {@code String} here. It is
 * never a boxed integral type and never a primitive one. This departs deliberately from applying
 * the migration's copybook-is-normative rule mechanically, and the departure is argued rather than
 * assumed because a reasonable alternative genuinely exists: two copybooks declare the field
 * numeric, and a numeric picture taken mechanically would become an integer column. Five sources
 * bear on the choice, and they do not all agree.</p>
 *
 * <ol>
 *   <li>{@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L3 declares
 *       {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL}.</li>
 *   <li>{@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl} generates the host variable across
 *       two lines, {@code 10 DCL-TRC-TYPE-CATEGORY} at L42 carrying {@code PIC X(4).} at L43.</li>
 *   <li>{@code app/data/ASCII/trancatg.txt} stores every code zero-padded to four digits, the whole
 *       distinct set running {@code 0001} through {@code 0005}, and keys its rows by positional
 *       concatenation: its first key is literally {@code 010001}, the type {@code 01} followed by
 *       the category {@code 0001}.</li>
 *   <li>{@code app/cpy/CVTRA04Y.cpy} L7 declares {@code TRAN-CAT-CD PIC 9(04)}, a numeric picture.
 *       This is the counter-evidence rather than support for the decision.</li>
 *   <li>{@code app/cpy/CVTRA02Y.cpy} L8 declares {@code DIS-TRAN-CAT-CD PIC 9(04)}, the second
 *       numeric declaration, on the disclosure record that keys on this same code.</li>
 * </ol>
 *
 * <p>Three character-typed sources outweigh the two numeric declarations. The numeric pictures
 * constrain which characters the field may hold, which this type enforces on construction; the
 * character-typed sources constrain how the value is stored and compared, which is what a column
 * type decides. An integral member would discard the leading zeros that all five sources write:
 * {@code 0001} would be held as 1, and a key assembled from that value would not locate its own
 * row. A further corroboration is arithmetic rather than declarative. {@code app/jcl/TRANCATG.jcl}
 * defines {@code KEYS(6 0)} at L40 against {@code RECORDSIZE(60 60)} at L41, and a six-byte key at
 * offset zero balances only as the two bytes of the type code beside four character positions of
 * the category code. The package charter carries this as its first ruling; the evidence is restated
 * here because this is the member that ruling governs.</p>
 *
 * <p>Assumptions: {@code type_cd} is {@code CHAR(2)} and {@code String} for the same reason, with
 * no counter-evidence at all because every source agrees. {@code app/cpy/CVTRA04Y.cpy} L6 declares
 * {@code TRAN-TYPE-CD PIC X(02)}; {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L2 declares
 * {@code TRC_TYPE_CODE CHAR(2) NOT NULL}; and
 * {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl} L39 generates
 * {@code 10 DCL-TRC-TYPE-CODE PIC X(2).} Unlike the category code it is not constrained to digits,
 * and the seed data shows why the two halves are guarded differently.</p>
 *
 * <h2>What the row carries, and what it leaves behind</h2>
 *
 * <p>Assumptions: the description is a mandatory column rather than an optional one.
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L4 declares
 * {@code TRC_CAT_DATA VARCHAR(50) NOT NULL} and {@code app/cpy/CVTRA04Y.cpy} L8 declares
 * {@code TRAN-CAT-TYPE-DESC PIC X(50)}. It is also the value the lookup exists to retrieve:
 * {@code app/cbl/CBTRN03C.cbl} assembles the composite key across L191 to L194 and reads the record
 * at L195 through {@code PERFORM 1500-C-LOOKUP-TRANCATG} for no purpose other than resolving this
 * description onto a report line, so a mapping without it would leave that path holding a row it
 * cannot use. The member takes the migration's own column name verbatim; that is not a rename of
 * the baseline's {@code TRC_CAT_DATA}, and this module renames nothing.</p>
 *
 * <p>Assumptions: the description is treated as varying width, and its trailing blanks are padding
 * rather than data. A declared-width alphanumeric source pads every value out to its width, so the
 * blanks carry no information, which is why the migration declares this column {@code VARCHAR(50)}
 * where it declares both key components {@code CHAR}. The package charter's second ruling settles
 * the opposite case deliberately: the {@code CHAR(10)} account-group id of {@code DisclosureGroup}
 * must keep its padding on both sides of every comparison. The two rulings differ because that
 * padding participates in a key and this padding does not, and the charter holds the evidence for
 * the distinction so that the asymmetry reads as intended rather than as an oversight.</p>
 *
 * <p>Refactoring Rationale: the two-part host variable the baseline needs for a varying-width
 * column collapses into a single member. {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl}
 * opens {@code 10 DCL-TRC-CAT-DATA} at L45 and pairs a length halfword across L47 to L48,
 * {@code 49 DCL-TRC-CAT-DATA-LEN PIC S9(4) USAGE COMP}, with the text across L50 to L51,
 * {@code 49 DCL-TRC-CAT-DATA-TEXT PIC X(50)}. That pairing is a host-language artefact: the
 * program has no varying-width string type, so the length has to travel beside the bytes. A Java
 * {@code String} and a PostgreSQL {@code VARCHAR} each carry their length intrinsically, so the
 * halfword has nothing to map onto and disappears. Its disappearance is recorded here in the same
 * spirit as a padding field's.</p>
 *
 * <p>Assumptions: the L9 {@code FILLER PIC X(04)} of {@code app/cpy/CVTRA04Y.cpy} is not carried
 * across, and the drop is recorded once for this record as the migration's convention requires:
 * 2 plus 4 plus 50 plus 4 accounts for all 60 bytes the L2 header declares. Any reader of the flat
 * record must skip those four bytes by offset and must not reach them by trimming, because in
 * {@code app/data/ASCII/trancatg.txt} they hold the literal {@code 0000} on every one of the 18
 * rows rather than blanks. Trimming would leave them inside the value.</p>
 *
 * <p>Assumptions: this entity carries an optimistic-lock counter, and the package charter's version
 * ruling is what places it here. That ruling grants a counter to exactly two of the package's six
 * entities, naming this type alongside {@code TransactionType}, and maps it onto the
 * {@code version BIGINT NOT NULL DEFAULT 0} column that {@code V1__reference.sql} declares for both
 * of their tables. The reason is the maintenance path: these two are the only tables this context
 * replaces a row in, through the extension's reference-data screens, so without the counter two
 * editors of one category could each overwrite the other's revision. The four seeded lookup
 * entities carry none, because their tables declare none.</p>
 *
 * <h2>The referential rule, and what it is deliberately not</h2>
 *
 * <p>Assumptions: the foreign key on {@code type_cd} is declared by the migration with
 * restrict-on-delete semantics, preserving what
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} states across L6 to L7, a
 * {@code FOREIGN KEY} on {@code TRC_TYPE_CODE} referencing
 * {@code CARDDEMO.TRANSACTION_TYPE (TR_TYPE)} with {@code ON DELETE RESTRICT}. It guards three
 * delete paths, each of which removes a parent type rather than a category:
 * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} L1901;
 * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} L1628, whose program takes this record's
 * layout at L288 through {@code EXEC SQL INCLUDE DCLTRCAT}; and
 * {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} L202. The baseline answers a refused delete
 * with Db2 {@code SQLCODE -532} and treats it as a distinct user-visible outcome rather than as a
 * failure. The target raises PostgreSQL {@code SQLSTATE 23503}, which the framework surfaces as a
 * data-integrity violation and which {@code com.carddemo.common.error.GlobalExceptionHandler} in
 * {@code common-lib} answers as HTTP 409. That mapping is inherited and is deliberately not
 * restated: this service declares no advice of its own, and this file adds no advice, no exception
 * translator and no catch block, so the status a refused delete produces cannot drift away from the
 * shared one.</p>
 *
 * <p>Alternatives Considered: the parent type is not mapped as an association. An association on
 * {@code type_cd} would map a column the embedded identifier already maps, so it would have to be
 * declared non-insertable and non-updatable, or else routed through an identifier-derived mapping.
 * Either form states one column twice, and two statements of one column can drift apart. It would
 * also put a lazy proxy on every instance and invite a traversal issuing one query per row on the
 * list endpoints, over a reference table small enough that a single join is cheaper. It would buy no
 * enforcement either, because the constraint above is enforced by the database.</p>
 *
 * <p>Trade-offs: the compromise accepted is that an instance cannot navigate to its type, so a
 * caller that needs the parent description joins explicitly or issues a second read. The sibling
 * {@code service} and {@code mapper} packages do exactly that, which places the cost on the two
 * paths that want the parent instead of on every path that reads a category.</p>
 *
 * <p>Alternatives Considered: no index is declared on this type. The baseline's unique index
 * {@code X_TRAN_TYPE_CATG}, which {@code app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl} names at
 * L1 and defines at L3 over {@code (TRC_TYPE_CODE ASC, TRC_TYPE_CATEGORY ASC)}, is already
 * satisfied: the migration enforces the composite primary key with a unique index on exactly those
 * two columns in exactly that order. Declaring an index here as well was rejected because a mapping
 * annotation takes effect only when a schema is generated from these types, which is not how this
 * schema is built, so the annotation would describe an object the migration owns and a reader
 * comparing the two could not tell which one the database actually holds.</p>
 */
@Entity
@Table(name = "transaction_categories", schema = "reference")
public class TransactionCategory {

    /** The declared width of the type-code component, from {@code TRAN-TYPE-CD PIC X(02)}. */
    public static final int TYPE_CD_WIDTH = 2;

    /** The declared width of the category-code component, from {@code TRAN-CAT-CD PIC 9(04)}. */
    public static final int CAT_CD_WIDTH = 4;

    /**
     * The closed character domain of a stored category code, as a regular expression.
     *
     * <p>Refactoring Rationale: published here, beside the width it accompanies, for the same reason the
     * type code's expression is published on {@code TransactionType}: two request-handling classes have to
     * refuse a malformed code at their boundary -- this record's own item routes and the disclosure-group
     * read, whose third key component is this same code -- and a copy in each is a copy that can drift
     * into disagreeing with the other without anything failing.</p>
     *
     * <p>Assumptions: it is the {@code TransactionCategoryCode} schema's own expression from
     * {@code openapi/reference-api.yaml}: exactly four digits with leading zeros retained. The digits are
     * characters and never arithmetic, which is why the expression is over the characters rather than a
     * numeric range -- a range would admit a value written without its leading zeros, and that value names
     * a different stored key.</p>
     */
    public static final String CAT_CD_PATTERN = "^[0-9]{4}$";

    /** The declared width of the description, from {@code TRAN-CAT-TYPE-DESC PIC X(50)}. */
    public static final int DESCRIPTION_WIDTH = 50;

    /** The composite identity of this category. */
    @EmbeddedId
    private TransactionCategoryId id;

    // WHY : Assumptions: bound as varying width rather than as a blank-padded type, because the
    //       migration declares this column VARCHAR(50). The class documentation above records why
    //       the padding on the source field is not part of the value, and why the charter's ruling
    //       on the disclosure account-group id deliberately goes the other way.
    @Column(name = "description", length = DESCRIPTION_WIDTH, nullable = false)
    private String description;

    // WHY : Assumptions: a primitive rather than a boxed wrapper, and NOT NULL restated here as
    //       well as in the migration, for the reason the sibling type records at its own version
    //       member: the column is declared NOT NULL DEFAULT 0 so it can never deliver an absent
    //       value, and a mapping that under-states nullability describes a column the schema does
    //       not have. The counter itself is placed here by the charter's version ruling, not by a
    //       choice made in this file.
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the specification requires a no-argument constructor, which the provider calls
     * before assigning the mapped members reflectively. It is {@code protected} rather than public
     * so that application code, which has a fully specified constructor available, cannot obtain an
     * instance whose identity has never been set.</p>
     */
    protected TransactionCategory() {
        // WHY : Assumptions: the body is empty because the provider assigns every mapped member
        //       reflectively immediately afterwards, so a default written here would be discarded.
    }

    /**
     * Creates a category from its whole identity and its description.
     *
     * @param id the composite identity of the category; must not be {@code null}
     * @param description the description this category presents, no wider than its declared width;
     *     must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if the description is wider than its declared width
     */
    public TransactionCategory(TransactionCategoryId id, String description) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(description, "description must not be null");
        if (description.length() > DESCRIPTION_WIDTH) {
            throw new IllegalArgumentException("description must not exceed " + DESCRIPTION_WIDTH
                    + " characters, the width the migration declares; received "
                    + description.length());
        }
        this.description = description;
    }

    /**
     * Returns the composite identity.
     *
     * @return the identity, never {@code null} on a persisted instance
     */
    public TransactionCategoryId getId() {
        return this.id;
    }

    /**
     * Returns the transaction type this category belongs to.
     *
     * @return the two-character type code, never {@code null} on a persisted instance
     */
    public String getTypeCd() {
        return this.id == null ? null : this.id.getTypeCd();
    }

    /**
     * Returns the category code.
     *
     * @return the four-digit category code with its leading zeros intact, never {@code null} on a
     *     persisted instance
     */
    public String getCatCd() {
        return this.id == null ? null : this.id.getCatCd();
    }

    /**
     * Returns the description.
     *
     * @return the description, never {@code null} on a persisted instance
     */
    public String getDescription() {
        return this.description;
    }

    /**
     * Replaces the description, applying the same width guard the constructor applies.
     *
     * <p>Purpose: the replace operation reads a row, changes its description and lets the provider
     * write it back, so the description is the one mapped member that has to be mutable. Both key
     * halves stay immutable, their columns mapped as non-updatable, because changing a key in place
     * would move the row rather than revise it, and the published contract expresses that as a
     * delete followed by a create.</p>
     *
     * <p>Assumptions: the guard is repeated here rather than trusted to the caller. A value
     * arriving through this member has not necessarily passed the request constraints, since a
     * batch path or a test can reach it directly, and a description one character over the declared
     * width would otherwise be refused by the database at flush time with no indication of which
     * row or which member caused it.</p>
     *
     * @param description the replacement description, no wider than its declared width; must not be
     *     {@code null}
     * @throws NullPointerException if {@code description} is {@code null}
     * @throws IllegalArgumentException if the description is wider than its declared width
     */
    public void setDescription(String description) {
        Objects.requireNonNull(description, "description must not be null");
        if (description.length() > DESCRIPTION_WIDTH) {
            throw new IllegalArgumentException("description must not exceed " + DESCRIPTION_WIDTH
                    + " characters, the width the migration declares; received "
                    + description.length());
        }
        this.description = description;
    }

    /**
     * Returns the optimistic-lock counter the persistence provider maintains.
     *
     * @return the stored version, zero on a newly built instance and on every seeded row
     */
    public long getVersion() {
        return this.version;
    }

    /**
     * Compares two categories by composite identity alone.
     *
     * <p>Assumptions: equality is the identity and not the description, because two instances of
     * one category read at different moments are the same row whether or not its description has
     * since been edited. Including the description would make a managed instance stop equalling its
     * own detached copy the moment either side was revised, which is the opposite of what a caller
     * holding both expects.</p>
     *
     * @param other the object to compare against, possibly {@code null}
     * @return {@code true} when the other object is a category whose identity is equal to this one
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionCategory category)) {
            return false;
        }
        return Objects.equals(this.id, category.id);
    }

    /**
     * Hashes the composite identity alone, consistently with {@link #equals(Object)}.
     *
     * <p>Assumptions: the identity is the only member hashed, for the same reason it is the only
     * member compared. Hashing the description as well would move an instance between buckets when
     * it was edited, so a set that already held it could no longer find it.</p>
     *
     * @return the identity's hash, or zero before an identity has been assigned
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.id);
    }

    /**
     * Renders the identity and the description for a diagnostic.
     *
     * <p>Assumptions: every member rendered here is reference data that a log may carry. Neither
     * key component nor the description identifies a person or an account, and the version counter
     * is deliberately left out because it changes on every revision and would make otherwise equal
     * renderings of one row differ.</p>
     *
     * @return a diagnostic rendering of the identity and the description, never {@code null}
     */
    @Override
    public String toString() {
        return "TransactionCategory[id=" + this.id + ", description=" + this.description + "]";
    }

    /**
     * The composite identity of a transaction category: its type code together with its category
     * code.
     *
     * <p>Purpose: this type carries the two components the source record groups under
     * {@code TRAN-CAT-KEY} at {@code app/cpy/CVTRA04Y.cpy} L5, and it exists so that the pair can be
     * passed, compared and used as a map key as one value. Both components are held as character
     * data at their declared widths for the reasons the enclosing type documents.</p>
     *
     * <p>Alternatives Considered: a nested embeddable identifier referenced by an embedded
     * identifier mapping, rather than either of the two alternatives the package charter weighed and
     * rejected. A standalone top-level identifier file was rejected because the contents of this
     * package are a closed set of seven compilation units, one descriptor beside six entities, and
     * separate identifier files would make nine and break the shape a reader is told to expect. An
     * identifier-class mapping was rejected because it obliges the identifier fields to be declared
     * a second time on the entity itself, and two declarations of one field can drift apart.
     * Grouping the two components into a single object also mirrors the source, which groups them
     * under one key name rather than leaving them adjacent and unrelated.</p>
     *
     * <p>Assumptions: the name {@code TransactionCategory.TransactionCategoryId} and the component
     * names {@code typeCd} and {@code catCd} are a published contract that the package charter
     * settles. The sibling {@code repository}, {@code service} and {@code mapper} packages consume
     * that exact name for keyed reads, so it is not a detail this file may rename.</p>
     */
    @Embeddable
    public static class TransactionCategoryId implements Serializable {

        /** Serialisation identity, required of an embeddable identifier type. */
        private static final long serialVersionUID = 1L;

        // WHY : Assumptions: bound as a blank-padded character type, not as a varying-width one,
        //       because the migration declares this column CHAR(2) and because a padded probe
        //       arriving from a declared-width source has to match its stored row. Mapped
        //       non-updatable because a key is not revised in place; the enclosing type's setter
        //       documentation records why.
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "type_cd", length = TYPE_CD_WIDTH, nullable = false, updatable = false)
        private String typeCd;

        // WHY : Assumptions: bound as a blank-padded character type for the same reason, and never
        //       as an integral type. The five sources that settle this are enumerated on the
        //       enclosing type, whose leading-zero argument is the whole reason this member is not
        //       a number.
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "cat_cd", length = CAT_CD_WIDTH, nullable = false, updatable = false)
        private String catCd;

        /**
         * Creates an empty identity for the persistence provider to populate.
         *
         * <p>Assumptions: an embeddable identifier needs a no-argument constructor for the same
         * reason the entity does, and it is {@code protected} for the same reason: application code
         * has the two-argument form available and should not be able to hold a half-built key.</p>
         */
        protected TransactionCategoryId() {
            // WHY : Assumptions: empty because the provider assigns both components reflectively,
            //       exactly as it does for the enclosing entity.
        }

        /**
         * Creates an identity from its two components, each at its declared width.
         *
         * <p>Assumptions: both widths are checked for exact equality rather than as maxima, and the
         * category code is additionally checked to be all digits, which is where the numeric
         * pictures of {@code app/cpy/CVTRA04Y.cpy} L7 and {@code app/cpy/CVTRA02Y.cpy} L8 are
         * honoured. A blank-padded column ignores trailing blanks when comparing, which forgives a
         * value short on the right and does nothing for one short on the left, so {@code 5} would be
         * stored as {@code 5} followed by three blanks and would resolve to a different row from
         * {@code 0005}: two spellings of one logical category, each satisfying every declared
         * constraint. Rejecting the short form on construction is what keeps the seed file's
         * zero-padded spelling the only one that reaches the schema.</p>
         *
         * <p>Assumptions: the type code is checked only for width and not for digits, because it is
         * declared alphanumeric at {@code app/cpy/CVTRA04Y.cpy} L6 and the schema declares it
         * {@code CHAR(2)}. Requiring digits there would refuse a code the contract permits, even
         * though every value in {@code app/data/ASCII/trancatg.txt} happens to be numeric today.</p>
         *
         * @param typeCd the two-character type code, alphanumeric at its declared width; must not be
         *     {@code null}
         * @param catCd the four-digit category code with its leading zeros intact; must not be
         *     {@code null}
         * @throws NullPointerException if either component is {@code null}
         * @throws IllegalArgumentException if either component is not exactly its declared width, or
         *     if the category code holds a character that is not a digit
         */
        public TransactionCategoryId(String typeCd, String catCd) {
            Objects.requireNonNull(typeCd, "typeCd must not be null");
            Objects.requireNonNull(catCd, "catCd must not be null");
            if (typeCd.length() != TYPE_CD_WIDTH) {
                throw new IllegalArgumentException("typeCd must be exactly " + TYPE_CD_WIDTH
                        + " characters; received " + typeCd.length());
            }
            if (catCd.length() != CAT_CD_WIDTH) {
                throw new IllegalArgumentException("catCd must be exactly " + CAT_CD_WIDTH
                        + " digits with its leading zeros intact; received " + catCd.length());
            }
            for (int index = 0; index < catCd.length(); index++) {
                char character = catCd.charAt(index);
                if (character < '0' || character > '9') {
                    throw new IllegalArgumentException("catCd must be exactly " + CAT_CD_WIDTH
                            + " digits, because its source picture is numeric and zero-filled;"
                            + " received a non-digit at position " + (index + 1));
                }
            }
            this.typeCd = typeCd;
            this.catCd = catCd;
        }

        /**
         * Returns the type code.
         *
         * @return the two-character type code, never {@code null} on a populated identity
         */
        public String getTypeCd() {
            return this.typeCd;
        }

        /**
         * Returns the category code.
         *
         * @return the four-digit category code with its leading zeros intact, never {@code null} on
         *     a populated identity
         */
        public String getCatCd() {
            return this.catCd;
        }

        /**
         * Compares two identities by both components.
         *
         * <p>Assumptions: an identifier type has to compare by value rather than by reference for
         * the provider to recognise two reads of one row as the same entity and for the type to work
         * as a map key. Both components participate, because either one differing selects a
         * different row.</p>
         *
         * @param other the object to compare against, possibly {@code null}
         * @return {@code true} when the other object is an identity whose two components are both
         *     equal to this one's
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TransactionCategoryId identity)) {
                return false;
            }
            return Objects.equals(this.typeCd, identity.typeCd)
                    && Objects.equals(this.catCd, identity.catCd);
        }

        /**
         * Hashes both components, consistently with {@link #equals(Object)}.
         *
         * <p>Assumptions: both components are hashed because both are compared. Hashing only the
         * type code would be correct but would collide every category beneath one type, which is
         * precisely the set a lookup iterates.</p>
         *
         * @return the combined hash of the two components
         */
        @Override
        public int hashCode() {
            return Objects.hash(this.typeCd, this.catCd);
        }

        /**
         * Renders both components for a diagnostic.
         *
         * <p>Assumptions: both components are reference codes that a log may carry, so neither is
         * masked. They are rendered separately rather than concatenated, even though the baseline
         * keys on the concatenation, so that a reader can see which half is which.</p>
         *
         * @return a diagnostic rendering of the two components, never {@code null}
         */
        @Override
        public String toString() {
            return "TransactionCategoryId[typeCd=" + this.typeCd + ", catCd=" + this.catCd + "]";
        }
    }
}
