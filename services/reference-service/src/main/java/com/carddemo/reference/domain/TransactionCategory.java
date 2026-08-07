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
 * One transaction category: the 60-byte record of {@code app/cpy/CVTRA04Y.cpy}.
 *
 * <p>Purpose: a category is identified by a transaction type together with a category code, and it carries
 * a description. The pair is what makes the identity composite: {@code app/cpy/CVTRA04Y.cpy} declares
 * {@code TRAN-TYPE-CD PIC X(02)} and {@code TRAN-CAT-CD PIC 9(04)} adjacently, and the reference reads the
 * record by both together.</p>
 *
 * <p>Assumptions: the two key components are bound as CHARACTER data at their declared widths, not as
 * numbers, even though the second has a numeric picture. A numeric binding would discard the leading
 * zeros the reference writes -- a category moved as the literal {@code '05'} into a {@code PIC 9(04)}
 * field is stored as {@code 0005} -- so {@code 0005} and {@code 5} would resolve to two different rows for
 * one logical category. The migration declares both columns {@code CHAR}, and the provider asserts its
 * mappings against the physical schema at start-up, so a numeric member here would fail that assertion
 * before a row was read.</p>
 *
 * <p>Assumptions: this entity carries NO optimistic-lock counter. The package's third ruling assigns one
 * to exactly two of its six entities, and the migration declares no {@code version} column on this table;
 * the reference maintenance program replaces a TYPE rather than a category, so no operation here competes
 * for a row.</p>
 *
 * <p>Assumptions: the foreign key to the owning type is expressed in the migration as
 * {@code fk_transaction_categories_type} with restrict-on-delete semantics and is deliberately NOT mapped
 * as an association here. Mapping it would give this entity a reference to the type entity and invite a
 * traversal that issues a second query per row, where every consumer of this table needs the two key
 * components and the description and nothing more. The constraint remains enforced by the database, which
 * is where the reference's own {@code XTRNTYCAT} semantic is enforced too.</p>
 */
@Entity
@Table(name = "transaction_categories", schema = "reference")
public class TransactionCategory {

    /** The declared width of the type-code component, from {@code TRAN-TYPE-CD PIC X(02)}. */
    public static final int TYPE_CD_WIDTH = 2;

    /** The declared width of the category-code component, from {@code TRAN-CAT-CD PIC 9(04)}. */
    public static final int CAT_CD_WIDTH = 4;

    /** The declared width of the description, from {@code TRAN-CAT-TYPE-DESC PIC X(50)}. */
    public static final int DESCRIPTION_WIDTH = 50;

    /** The composite identity of this category. */
    @EmbeddedId
    private TransactionCategoryId id;

    // WHY : Assumptions: the description is bound as varying width rather than as fixed, because the
    //       migration declares it VARCHAR(50). Trailing blanks in the source record are padding to the
    //       declared width rather than data, so they are not part of the value the target stores.
    @Column(name = "description", length = DESCRIPTION_WIDTH, nullable = false)
    private String description;

    // WHY : Assumptions: this table carries an optimistic-lock counter and the two lookup tables do
    //       not, and the difference is not arbitrary. This one is maintained through a screen family
    //       that reads a row, shows it to a user and replaces it in a later request, so two users
    //       editing one category can each write over the other's revision unless the write checks the
    //       version it read. The migration declares the column at V1__reference.sql for exactly that
    //       reason and records the rejected alternative there; the counter is the whole-row check that
    //       an echoed-field comparison cannot be.
    // WHY : Assumptions: a primitive rather than a boxed Long, and NOT NULL stated here as well as in
    //       the migration, for the reasons the sibling type records at its own version member: the
    //       column can never deliver an absent value, and a mapping that under-states nullability
    //       describes a column the schema forbids.
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the specification requires a no-argument constructor, which the provider calls
     * before assigning the mapped members reflectively. It is {@code protected} so that application code,
     * which has a fully specified constructor available, cannot reach an instance with no identity set.</p>
     */
    protected TransactionCategory() {
        // WHY : Assumptions: the body is empty because the provider assigns every member reflectively
        //       immediately afterwards, and assigning a default here would be overwritten.
    }

    /**
     * Creates a category with its whole identity and its description.
     *
     * @param id the composite identity; must not be {@code null}
     * @param description the category description; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if the description exceeds its declared width
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
     * <p>Purpose: the replace operation reads a row, changes its description and lets the provider write
     * it back, so the description is the one mapped member that has to be mutable. The two key halves
     * stay immutable -- their columns are mapped {@code updatable = false} -- because changing a key in
     * place would move the row rather than revise it, and the contract expresses that as a delete and a
     * create.</p>
     *
     * <p>Assumptions: the guard is repeated here rather than trusted to the caller. A value arriving
     * through this member has not necessarily passed the request constraints -- a batch path or a test
     * can reach it directly -- and a description one character over the declared width is refused by the
     * database at flush time with no indication of which row or which member caused it.</p>
     *
     * @param description the replacement description; must not be {@code null}
     * @throws NullPointerException if {@code description} is {@code null}
     * @throws IllegalArgumentException if the description exceeds its declared width
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
     * Compares by composite identity alone.
     *
     * <p>Assumptions: equality is the identity and not the description, because two instances of one
     * category read at different times are the same row whether or not its description has since been
     * edited. Including the description would make a managed instance stop equalling its own detached
     * copy after an edit.</p>
     *
     * @param other the object to compare against, possibly {@code null}
     * @return {@code true} when the other object is a category with an equal identity
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
     * @return the identity's hash, or zero before an identity is assigned
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.id);
    }

    /**
     * Renders the identity and the description, neither of which is sensitive.
     *
     * @return a diagnostic rendering, never {@code null}
     */
    @Override
    public String toString() {
        return "TransactionCategory[id=" + this.id + ", description=" + this.description + "]";
    }

    /**
     * The composite identity of a transaction category: its type code and its category code.
     */
    @Embeddable
    public static class TransactionCategoryId implements Serializable {

        /** Serialisation identity, required of an embeddable identity type. */
        private static final long serialVersionUID = 1L;

        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "type_cd", length = TYPE_CD_WIDTH, nullable = false, updatable = false)
        private String typeCd;

        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "cat_cd", length = CAT_CD_WIDTH, nullable = false, updatable = false)
        private String catCd;

        /**
         * Creates an empty identity for the persistence provider to populate.
         */
        protected TransactionCategoryId() {
            // WHY : Assumptions: empty for the same reason the entity's own no-argument constructor is.
        }

        /**
         * Creates an identity from its two components, each at its declared width.
         *
         * <p>Assumptions: both widths are checked for exact equality rather than as maxima, and the
         * category code is additionally checked to be all digits. These columns are fixed-character, whose
         * trailing-blank insensitivity forgives a value short on the right and does nothing for one short
         * on the left, so {@code 5} would be stored as {@code 5} plus three blanks and would resolve to a
         * different row from {@code 0005} -- two spellings of one logical category, each satisfying every
         * declared constraint.</p>
         *
         * @param typeCd the two-character type code; must not be {@code null}
         * @param catCd the four-digit category code with its leading zeros intact; must not be
         *     {@code null}
         * @throws NullPointerException if either component is {@code null}
         * @throws IllegalArgumentException if either component is not exactly its declared width, or if
         *     the category code holds a character that is not a digit
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
         * @return the four-digit category code, never {@code null} on a populated identity
         */
        public String getCatCd() {
            return this.catCd;
        }

        /**
         * Compares both components by value.
         *
         * @param other the object to compare against, possibly {@code null}
         * @return {@code true} when the other object is an identity with both components equal
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
         * @return the combined hash of the two components
         */
        @Override
        public int hashCode() {
            return Objects.hash(this.typeCd, this.catCd);
        }

        /**
         * Renders both components, neither of which is sensitive.
         *
         * @return a diagnostic rendering, never {@code null}
         */
        @Override
        public String toString() {
            return "TransactionCategoryId[typeCd=" + this.typeCd + ", catCd=" + this.catCd + "]";
        }
    }
}
