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
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One disclosure group and its annual interest rate: the 50-byte record of
 * {@code app/cpy/CVTRA02Y.cpy}.
 *
 * <p>Purpose: the interest accrual reads this table by a three-part key -- an account group, a transaction
 * type and a transaction category, declared at lines 6, 7 and 8 of that copybook -- and takes the annual
 * percentage rate it finds. When the account's own group is not found the reference substitutes the group
 * named {@code DEFAULT}, replacing that one component and carrying the type and category through
 * unchanged; the row this entity maps is what that substituted key reads, which is why the seed must
 * contain a {@code DEFAULT} row for every type and category in use.</p>
 *
 * <p>Assumptions: the rate is exact fixed point and never binary floating point. It is a factor in
 * {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, whose result is written as a
 * generated interest transaction that then posts to a balance, so a representation error in the rate
 * becomes a representation error in money. Transformation rule T3 forbids the money path leaving exact
 * fixed point at any hop, and a rate that multiplies money is on that path.</p>
 *
 * <p>Assumptions: all three key components are bound as CHARACTER data at their declared widths. The
 * category component has a numeric picture and is still character here for the same reason it is on the
 * sibling category entity: a numeric binding would discard the leading zeros the reference writes, so
 * {@code 0005} and {@code 5} would resolve to two different rows for one logical group -- and because a
 * missed lookup falls back to {@code DEFAULT} rather than failing, the consequence of a mismatch is not an
 * error but silently accruing at the wrong rate.</p>
 *
 * <p>Assumptions: this entity carries NO optimistic-lock counter, because the migration declares no
 * {@code version} column on this table and no migrated operation replaces a row in it. The accrual reads
 * it; nothing writes it.</p>
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

    /** The declared scale of the rate column: two fractional digits, hundredth resolution. */
    public static final int INTEREST_RATE_SCALE = 2;

    /** The composite identity of this group. */
    @EmbeddedId
    private DisclosureGroupId id;

    // WHY : Assumptions: the member is BigDecimal because the column is NUMERIC, and the two agree by
    //       intent rather than by accident. A binary floating-point member over a decimal column would
    //       compile and would round-trip most values, and the ones it did not round-trip would be
    //       rates ending in a repeating binary fraction -- which is most of them.
    // WHY : Refactoring Rationale: the precision and scale are stated here as well as in the migration,
    //       and stating them is not redundant. Left off, the provider assumes a default precision and
    //       scale for a decimal member, so its start-up validation would compare the column against a
    //       shape the migration never declared, and any tool generating a definition from this metadata
    //       would emit a column of a different width than the one the seed loads into. Every other
    //       NUMERIC column across the migrated entities carries both, so a reader can tell at the
    //       mapping site what the column holds without opening the migration.
    @Column(name = "interest_rate", nullable = false,
            precision = INTEREST_RATE_PRECISION, scale = INTEREST_RATE_SCALE)
    private BigDecimal interestRate;

    /**
     * Creates an empty instance for the persistence provider to populate.
     */
    protected DisclosureGroup() {
        // WHY : Assumptions: the body is empty because the provider assigns every member reflectively
        //       immediately afterwards.
    }

    /**
     * Creates a group with its whole identity and its rate.
     *
     * @param id the composite identity; must not be {@code null}
     * @param interestRate the annual percentage rate; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public DisclosureGroup(DisclosureGroupId id, BigDecimal interestRate) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.interestRate = Objects.requireNonNull(interestRate, "interestRate must not be null");
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
     * @return the rate as an exact decimal, never {@code null} on a persisted instance
     */
    public BigDecimal getInterestRate() {
        return this.interestRate;
    }

    /**
     * Returns the account-group component.
     *
     * @return the ten-character account group, never {@code null} on a persisted instance
     */
    public String getAcctGroupId() {
        return this.id == null ? null : this.id.getAcctGroupId();
    }

    /**
     * Returns the transaction-type component.
     *
     * @return the two-character type code, never {@code null} on a persisted instance
     */
    public String getTranTypeCd() {
        return this.id == null ? null : this.id.getTranTypeCd();
    }

    /**
     * Returns the transaction-category component.
     *
     * @return the four-digit category code, never {@code null} on a persisted instance
     */
    public String getTranCatCd() {
        return this.id == null ? null : this.id.getTranCatCd();
    }

    /**
     * Compares by composite identity alone.
     *
     * <p>Assumptions: the rate is excluded for the same reason a description is excluded elsewhere -- two
     * reads of one group are the same row whether or not the rate has since been re-seeded.</p>
     *
     * @param other the object to compare against, possibly {@code null}
     * @return {@code true} when the other object is a group with an equal identity
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
     * @return the identity's hash, or zero before an identity is assigned
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.id);
    }

    /**
     * Renders the identity and the rate, neither of which is sensitive.
     *
     * @return a diagnostic rendering, never {@code null}
     */
    @Override
    public String toString() {
        return "DisclosureGroup[id=" + this.id + ", interestRate=" + this.interestRate + "]";
    }

    /** The composite identity of a disclosure group: account group, transaction type, category. */
    @Embeddable
    public static class DisclosureGroupId implements Serializable {

        /** Serialisation identity, required of an embeddable identity type. */
        private static final long serialVersionUID = 1L;

        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "acct_group_id", length = ACCT_GROUP_ID_WIDTH, nullable = false,
                updatable = false)
        private String acctGroupId;

        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "tran_type_cd", length = TRAN_TYPE_CD_WIDTH, nullable = false,
                updatable = false)
        private String tranTypeCd;

        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "tran_cat_cd", length = TRAN_CAT_CD_WIDTH, nullable = false,
                updatable = false)
        private String tranCatCd;

        /**
         * Creates an empty identity for the persistence provider to populate.
         */
        protected DisclosureGroupId() {
            // WHY : Assumptions: empty for the same reason the entity's own no-argument constructor is.
        }

        /**
         * Creates an identity from its three components, each at its declared width.
         *
         * <p>Assumptions: every width is checked for exact equality and the category component is
         * additionally checked to be all digits, for the reason the class note gives: a missed lookup on
         * this table does not fail, it falls back to the {@code DEFAULT} group, so a key spelled one
         * character short accrues silently at the wrong rate instead of reporting anything.</p>
         *
         * @param acctGroupId the ten-character account group, blank-padded to its declared width; must
         *     not be {@code null}
         * @param tranTypeCd the two-character transaction type; must not be {@code null}
         * @param tranCatCd the four-digit transaction category with its leading zeros intact; must not be
         *     {@code null}
         * @throws NullPointerException if any component is {@code null}
         * @throws IllegalArgumentException if any component is not exactly its declared width, or if the
         *     category component holds a character that is not a digit
         */
        public DisclosureGroupId(String acctGroupId, String tranTypeCd, String tranCatCd) {
            this.acctGroupId = exactWidth(acctGroupId, ACCT_GROUP_ID_WIDTH, "acctGroupId");
            this.tranTypeCd = exactWidth(tranTypeCd, TRAN_TYPE_CD_WIDTH, "tranTypeCd");
            this.tranCatCd = exactDigits(tranCatCd, TRAN_CAT_CD_WIDTH, "tranCatCd");
        }

        /**
         * Returns a component that is present and exactly as wide as its column declares.
         *
         * @param candidate the supplied component, possibly {@code null}
         * @param width the declared width
         * @param member the component's name, used only in the refusal message
         * @return the accepted value, never {@code null}
         * @throws NullPointerException if {@code candidate} is {@code null}
         * @throws IllegalArgumentException if the value is not exactly {@code width} characters
         */
        private static String exactWidth(String candidate, int width, String member) {
            Objects.requireNonNull(candidate, member + " must not be null");
            if (candidate.length() != width) {
                throw new IllegalArgumentException(member + " must be exactly " + width
                        + " characters, because it is part of a composite key over a fixed-character"
                        + " column; received " + candidate.length());
            }
            return candidate;
        }

        /**
         * Returns a component that is present, exactly as wide as its column declares, and all digits.
         *
         * @param candidate the supplied component, possibly {@code null}
         * @param width the declared width
         * @param member the component's name, used only in the refusal message
         * @return the accepted value, never {@code null}
         * @throws NullPointerException if {@code candidate} is {@code null}
         * @throws IllegalArgumentException if the value is not exactly {@code width} digits
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
         * @return the ten-character account group, never {@code null} on a populated identity
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
         * @return the four-digit category code, never {@code null} on a populated identity
         */
        public String getTranCatCd() {
            return this.tranCatCd;
        }

        /**
         * Compares all three components by value.
         *
         * @param other the object to compare against, possibly {@code null}
         * @return {@code true} when the other object is an identity with all three components equal
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
         * @return the combined hash of the three components
         */
        @Override
        public int hashCode() {
            return Objects.hash(this.acctGroupId, this.tranTypeCd, this.tranCatCd);
        }

        /**
         * Renders all three components, none of which is sensitive.
         *
         * @return a diagnostic rendering, never {@code null}
         */
        @Override
        public String toString() {
            return "DisclosureGroupId[acctGroupId=" + this.acctGroupId + ", tranTypeCd="
                    + this.tranTypeCd + ", tranCatCd=" + this.tranCatCd + "]";
        }
    }
}
