package com.carddemo.authorization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * The two-part key of a fraud row: the primary account number and the composed authorization
 * timestamp.
 *
 * <p><strong>Purpose.</strong> Address one row of {@code authorization.auth_fraud} by the same two
 * columns {@code ddl/AUTHFRDS.ddl} L28 declares as its primary key, so that the target's upsert names
 * the conflict target the reference program's duplicate-key branch reached.
 *
 * <p>Assumptions: the key is {@code (card_num, auth_ts)} and not the three-part key of the detail row
 * it marks, and the two must not be conflated. The reference {@code UPDATE} at
 * {@code cbl/COPAUS2C.cbl} L226-L228 matches on exactly this pair and nothing else, and the
 * duplicate-key condition its {@code INSERT} tests at L199 is a violation of exactly this constraint.
 * A fraud row is therefore reachable from a card and an instant, while the detail row it describes is
 * reachable from an account and a decoded date and time -- two different addressing schemes over one
 * event, which is a property of the baseline's own split between Db2 and IMS.
 *
 * <p>Assumptions: {@code authTs} is a COMPOSED value that exists in neither IMS segment. Its date part
 * is sliced from the acquirer-supplied original date at {@code cbl/COPAUS2C.cbl} L103-L105 and its
 * time part from the DECODED nines complement at L107-L111, so the instant has a mixed origin. Its
 * three low fractional digits are always zero by construction, because the assembled twenty-three
 * character string at L38-L51 ends in a three-digit millisecond field followed by a literal
 * {@code '000'}, and the conversion mask at L227-L228 reads the last six digits as microseconds.
 * Nobody computing a duration from two such values should read precision into them.
 *
 * <p>Trade-offs: the timestamp is carried as {@code LocalDateTime} rather than as an instant with a
 * zone. The reference value has no zone at all -- it is assembled from characters and read back with
 * a format mask -- so attaching one here would invent information, and the column is
 * {@code TIMESTAMP(6)} without a time zone for the same reason.
 *
 * <p>Alternatives Considered: a single generated surrogate key with a unique constraint over the pair.
 * Rejected because the upsert this key exists to express names its conflict target by column, so a
 * surrogate would add a column that no reference statement mentions while leaving the same pair to be
 * declared unique anyway.
 */
@Embeddable
public class AuthFraudKey implements Serializable {

    /**
     * The serialization identity of this key type.
     *
     * <p>Assumptions: an embeddable identifier must be serializable, and a declared value keeps the
     * identity stable across compilations rather than derived from the member list.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The sixteen-character primary account number the fraud row is filed against.
     *
     * <p>Assumptions: {@code CHAR(16)} holding the digits as characters, matching
     * {@code ddl/AUTHFRDS.ddl} L4 and the detail row's own card column. A leading zero is data in a
     * primary account number, so a numeric column would lose it.
     */
    @Column(name = "card_num", nullable = false, updatable = false, length = 16)
    private String cardNum;

    /**
     * The composed authorization timestamp the fraud row is keyed on.
     */
    @Column(name = "auth_ts", nullable = false, updatable = false)
    private LocalDateTime authTs;

    /**
     * Creates an empty key for the persistence provider to populate.
     *
     * <p>Assumptions: the persistence specification requires a no-argument constructor on an
     * embeddable. It is protected because no caller outside this type's hierarchy has a use for a key
     * with no parts.
     */
    protected AuthFraudKey() {
        // Assumptions: empty by design; the provider assigns both mapped members after construction.
    }

    /**
     * Creates a fully populated key.
     *
     * @param cardNum the sixteen-character primary account number; must not be {@code null}
     * @param authTs the composed authorization timestamp; must not be {@code null}
     * @throws NullPointerException if either part is {@code null}
     */
    public AuthFraudKey(String cardNum, LocalDateTime authTs) {
        this.cardNum = Objects.requireNonNull(cardNum, "cardNum must not be null");
        this.authTs = Objects.requireNonNull(authTs, "authTs must not be null");
    }

    /**
     * Returns the primary account number this key names.
     *
     * @return the sixteen-character card number, never {@code null} on a populated key
     */
    public String getCardNum() {
        return this.cardNum;
    }

    /**
     * Returns the composed authorization timestamp this key names.
     *
     * @return the timestamp, never {@code null} on a populated key
     */
    public LocalDateTime getAuthTs() {
        return this.authTs;
    }

    /**
     * Compares this key with another by both parts.
     *
     * <p>Assumptions: value equality is required rather than optional. The provider uses it to decide
     * whether two loaded rows are the same row, so an identity comparison would make every read of one
     * row look like a read of a different one.
     *
     * @param other the object to compare with, which may be {@code null}
     * @return {@code true} when {@code other} is a key naming the same card and instant
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AuthFraudKey that)) {
            return false;
        }
        return Objects.equals(this.cardNum, that.cardNum) && Objects.equals(this.authTs, that.authTs);
    }

    /**
     * Returns a hash consistent with {@link #equals(Object)}.
     *
     * @return the hash of both key parts
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.cardNum, this.authTs);
    }

    /**
     * Renders this key for a diagnostic with the primary account number masked.
     *
     * <p>Refactoring Rationale: the default rendering of a class names every field verbatim, and one
     * of this key's two fields is a primary account number. Nothing has to be written wrongly for that
     * rendering to escape -- a key interpolated into a log statement, carried in an assertion message
     * or picked up by a provider tracing a constraint violation produces it automatically -- and a
     * constraint violation on this key is exactly the case that gets logged. Overriding it here makes
     * the masking a property of the TYPE rather than of each call site.
     *
     * @return a single-line rendering naming this type, the masked card number and the instant; the
     *     card component is labelled as masked so no reader mistakes it for a usable number
     */
    @Override
    public String toString() {
        return "AuthFraudKey[maskedCardNum=" + maskedCardNum() + ", authTs=" + this.authTs + ']';
    }

    /**
     * Reduces the card number to its last four digits for a diagnostic rendering.
     *
     * <p>Assumptions: the shared masker is not used here, because a domain type may not depend on a
     * package outside the domain layer under this module's architecture rules, and the rendering needed
     * is one fixed reduction rather than the masker's general path handling. The two agree on what
     * survives -- the final four characters -- which is the only property a reader of a diagnostic
     * needs.
     *
     * @return the last four characters preceded by a mask, or a fixed placeholder when the card number
     *     is absent or shorter than four characters
     */
    private String maskedCardNum() {
        if (this.cardNum == null || this.cardNum.length() < 4) {
            return "****";
        }
        return "************" + this.cardNum.substring(this.cardNum.length() - 4);
    }
}
