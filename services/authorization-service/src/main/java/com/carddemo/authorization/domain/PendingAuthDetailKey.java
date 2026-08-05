package com.carddemo.authorization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * The three-part key of a {@link PendingAuthDetail} row: account, authorization date and
 * authorization time.
 *
 * <p>This is the migrated form of the hierarchical database's child-segment key. The segment
 * {@code PAUTDTL1} is keyed by {@code PA-AUTH-DATE-9C PIC S9(05) COMP-3} and
 * {@code PA-AUTH-TIME-9C PIC S9(09) COMP-3}, declared together at
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} lines 19 to 21, and it is reached only
 * beneath its root segment, so the account identifier is the third part of the key here even though
 * the child segment does not repeat it.</p>
 *
 * <p>Trade-offs: the account identifier is part of this key rather than being inferred from a
 * relationship. Carrying it explicitly means every detail row can be located by a single predicate
 * without a join, which is what the summary screen's browse needs; the cost is that the value is
 * stored twice, once here and once on the parent row. Inferring it instead would save those eight
 * bytes per row and would force a join onto the one query whose latency the user actually observes.</p>
 *
 * <p>Assumptions: both date and time are integers, not a timestamp. The baseline stores a packed
 * five-digit date and a packed nine-digit time as two independent fields, and the composite key's
 * collation follows from comparing them in that order. Collapsing them into one timestamp column would
 * read more naturally and would silently change the ordering of any two rows whose packed values do
 * not map onto a valid instant -- and the baseline permits such values, because it validates neither.</p>
 */
@Embeddable
public class PendingAuthDetailKey implements Serializable {

    /**
     * The serialization version, fixed because the specification requires an embeddable key to be
     * serializable and a generated value would change with any recompilation.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The account the authorization belongs to, inherited from the root segment's key.
     */
    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    /**
     * The authorization date, {@code PA-AUTH-DATE-9C PIC S9(05) COMP-3} at line 20 of the copybook.
     */
    @Column(name = "auth_date", nullable = false, updatable = false)
    private Integer authDate;

    /**
     * The authorization time, {@code PA-AUTH-TIME-9C PIC S9(09) COMP-3} at line 21 of the copybook.
     */
    @Column(name = "auth_time", nullable = false, updatable = false)
    private Integer authTime;

    /**
     * Creates an empty key for the persistence provider to populate.
     *
     * <p>Assumptions: the specification requires a no-argument constructor on an embeddable type. It is
     * protected because no caller outside this type's hierarchy has a use for a key with no parts.</p>
     */
    protected PendingAuthDetailKey() {
        // Assumptions: empty by design; the provider assigns the three mapped fields after construction.
    }

    /**
     * Creates a fully-specified key.
     *
     * @param accountId the account the authorization belongs to; must not be {@code null}
     * @param authDate the packed authorization date as an integer; must not be {@code null}
     * @param authTime the packed authorization time as an integer; must not be {@code null}
     */
    public PendingAuthDetailKey(Long accountId, Integer authDate, Integer authTime) {
        this.accountId = accountId;
        this.authDate = authDate;
        this.authTime = authTime;
    }

    /**
     * Returns the account the authorization belongs to.
     *
     * @return the account identifier, never {@code null} on a persisted instance
     */
    public Long getAccountId() {
        return this.accountId;
    }

    /**
     * Returns the packed authorization date.
     *
     * @return the authorization date as an integer, never {@code null} on a persisted instance
     */
    public Integer getAuthDate() {
        return this.authDate;
    }

    /**
     * Returns the packed authorization time.
     *
     * @return the authorization time as an integer, never {@code null} on a persisted instance
     */
    public Integer getAuthTime() {
        return this.authTime;
    }

    /**
     * Compares this key with another for equality across all three parts.
     *
     * <p>Assumptions: the specification requires value equality on an embeddable key, because the
     * provider uses it to decide whether two loaded rows are the same row. All three parts participate;
     * omitting any one of them would make two genuinely different authorizations on the same account
     * compare equal, and the provider would then return the first for a lookup of the second.</p>
     *
     * @param other the object to compare against; may be {@code null}
     * @return {@code true} when {@code other} is a key with the same account, date and time
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PendingAuthDetailKey)) {
            return false;
        }
        PendingAuthDetailKey that = (PendingAuthDetailKey) other;
        return Objects.equals(this.accountId, that.accountId)
                && Objects.equals(this.authDate, that.authDate)
                && Objects.equals(this.authTime, that.authTime);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return a hash code derived from all three key parts
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.accountId, this.authDate, this.authTime);
    }

    /**
     * Returns a diagnostic rendering of the three key parts.
     *
     * <p>Assumptions: a key is safe to log in full. It carries an account identifier and two clock
     * values and no cardholder data, which is why this type has a rendering at all while
     * {@link PendingAuthDetail} deliberately does not.</p>
     *
     * @return the three key parts in key order
     */
    @Override
    public String toString() {
        return "PendingAuthDetailKey[accountId=" + this.accountId
                + ", authDate=" + this.authDate
                + ", authTime=" + this.authTime + "]";
    }
}
