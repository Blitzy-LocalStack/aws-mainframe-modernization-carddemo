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
     * The lowest authorization date this key admits, the first day of the earliest representable year.
     *
     * <p>Assumptions: the bound is the schema's own, {@code auth_date BETWEEN 1 AND 99366} in
     * {@code ck_pending_auth_detail_auth_date_domain} at L595 of the migration, which follows from the
     * value being a five-digit ordinal date -- a two-digit year followed by a three-digit day of year.
     * Zero is excluded because there is no day zero.</p>
     */
    public static final int AUTH_DATE_MIN = 1;

    /**
     * The highest authorization date this key admits, the last day of a leap year in year 99.
     */
    public static final int AUTH_DATE_MAX = 99_366;

    /**
     * The divisor that isolates the day-of-year part of an ordinal date.
     *
     * <p>Assumptions: the value is five digits with the day of year in the low three, so the day is the
     * remainder modulo one thousand and the year is the quotient. The schema states the same arithmetic
     * as {@code auth_date % 1000 BETWEEN 1 AND 366}.</p>
     */
    private static final int DAY_OF_YEAR_MODULUS = 1_000;

    /** The lowest day-of-year part an ordinal date may carry. */
    private static final int DAY_OF_YEAR_MIN = 1;

    /** The highest day-of-year part an ordinal date may carry, a leap year's last day. */
    private static final int DAY_OF_YEAR_MAX = 366;

    /**
     * The lowest authorization time this key admits, midnight exactly.
     *
     * <p>Assumptions: zero is admissible where day zero is not, because midnight is a real instant. The
     * schema states {@code auth_time BETWEEN 0 AND 235959999} at L598 of the migration.</p>
     */
    public static final int AUTH_TIME_MIN = 0;

    /**
     * The highest authorization time this key admits, one millisecond before midnight.
     *
     * <p>Assumptions: the value is nine digits, {@code HHMMSSmmm} -- two for the hour, two for the
     * minute, two for the second and three for the millisecond -- which the reference program assembles
     * at {@code cbl/COPAUA0C.cbl} L858 to L875 before complementing it into the key.</p>
     */
    public static final int AUTH_TIME_MAX = 235_959_999;

    /** The divisor that isolates the minute part of a nine-digit time. */
    private static final int MINUTE_DIVISOR = 100_000;

    /** The divisor that isolates the second part of a nine-digit time. */
    private static final int SECOND_DIVISOR = 1_000;

    /** The modulus that reduces an isolated time part to its own two digits. */
    private static final int TIME_PART_MODULUS = 100;

    /** The highest value a minute or a second part may carry. */
    private static final int SEXAGESIMAL_MAX = 59;

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
     * <p>Refactoring Rationale: the three parts are VALIDATED here, and they were assigned unchecked. The
     * same invariants were written once, as a helper on the detail mapper, and that helper had no caller
     * -- so every path that built a key, including the decoding of a stored complement and the reopening
     * of a sealed screen selector, could produce a key the schema refuses. Validating in the type means
     * every caller receives one rule rather than each restating it, which matters most for the two callers
     * that build a key from data they did not author: a segment image and a client-echoed selector.</p>
     *
     * <p>Assumptions: the rules are the schema's own, restated rather than invented --
     * {@code ck_pending_auth_detail_auth_date_domain} and {@code ck_pending_auth_detail_auth_time_domain}
     * at L594 to L600 of {@code V1__authorization.sql}. Enforcing them here does not make the constraints
     * redundant: two writers reach these columns, this type and the extract load, and a check in one of
     * them cannot bind the other. What it buys is that the fault is reported where the value was composed
     * instead of as an opaque constraint violation at flush, inside the transaction that had already
     * decided an authorization.
     *
     * <p>Assumptions: the ACCOUNT identifier is required to be positive rather than merely present. The
     * column is {@code BIGINT NOT NULL} with a foreign key to the summary row, and an account identifier
     * is an unsigned eleven-digit value at {@code cpy/CIPAUSMY.cpy} L19, so zero and negative values name
     * no account. This is the one rule the schema does not also state, and it is stated here because the
     * foreign key would report it as a missing parent rather than as a malformed identifier.
     *
     * <p>Assumptions: the two clock values are the DECODED date and time and never the nines complement
     * the segment stores. A complement is bounded by the same two widths but by a different domain -- the
     * complement of the first day of a year is 99998, which is outside the date range above -- so a caller
     * that passed a complement here would be refused, which is the intended outcome and the reason the
     * decode belongs to the mapper.
     *
     * @param accountId the account the authorization belongs to; must not be {@code null} and must be
     *     positive
     * @param authDate the DECODED five-digit ordinal authorization date; must not be {@code null}, must
     *     lie between {@value #AUTH_DATE_MIN} and {@value #AUTH_DATE_MAX}, and its day-of-year part must
     *     lie between one and three hundred and sixty-six
     * @param authTime the DECODED nine-digit authorization time to the millisecond; must not be
     *     {@code null}, must lie between {@value #AUTH_TIME_MIN} and {@value #AUTH_TIME_MAX}, and its
     *     minute and second parts must each be at most fifty-nine
     * @throws NullPointerException if any of the three parts is {@code null}
     * @throws IllegalArgumentException if the account identifier is not positive, or if either clock value
     *     lies outside the domain its column declares; the message names the part and its value, neither
     *     of which is protected data -- a date and a time are rendered by this type's own diagnostic
     *     form, and an account identifier is reported as out of domain without being quoted
     */
    public PendingAuthDetailKey(Long accountId, Integer authDate, Integer authTime) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(authDate, "authDate must not be null");
        Objects.requireNonNull(authTime, "authTime must not be null");
        if (accountId <= 0L) {
            // WHY : Assumptions: the identifier itself is NOT quoted, unlike the two clock values. The
            //       sensitive-data logging contract in docs/architecture/observability.md names account
            //       identifiers in a clause of their own, and this type's own diagnostic form omits it for
            //       that reason; a refusal that quoted it would reintroduce exactly what toString withholds.
            throw new IllegalArgumentException(
                    "accountId must be a positive account identifier, and the value supplied was not");
        }
        this.accountId = accountId;
        this.authDate = requireAuthDateInDomain(authDate);
        this.authTime = requireAuthTimeInDomain(authTime);
    }

    /**
     * Returns the supplied authorization date once it is known to be a five-digit ordinal date.
     *
     * @param candidate the decoded date a caller supplied; must not be {@code null}
     * @return {@code candidate} unchanged, once it is known to be in domain
     * @throws IllegalArgumentException if the value is outside {@value #AUTH_DATE_MIN} to
     *     {@value #AUTH_DATE_MAX}, or its day-of-year part is outside one to three hundred and sixty-six
     */
    private static Integer requireAuthDateInDomain(Integer candidate) {
        int value = candidate;
        if (value < AUTH_DATE_MIN || value > AUTH_DATE_MAX) {
            throw new IllegalArgumentException("authDate must be a five-digit ordinal date between "
                    + AUTH_DATE_MIN + " and " + AUTH_DATE_MAX + " but was: " + value);
        }
        int dayOfYear = value % DAY_OF_YEAR_MODULUS;
        if (dayOfYear < DAY_OF_YEAR_MIN || dayOfYear > DAY_OF_YEAR_MAX) {
            throw new IllegalArgumentException("authDate's day-of-year part must be between "
                    + DAY_OF_YEAR_MIN + " and " + DAY_OF_YEAR_MAX + " but was: " + dayOfYear
                    + " in " + value);
        }
        return candidate;
    }

    /**
     * Returns the supplied authorization time once it is known to be a nine-digit time of day.
     *
     * @param candidate the decoded time a caller supplied; must not be {@code null}
     * @return {@code candidate} unchanged, once it is known to be in domain
     * @throws IllegalArgumentException if the value is outside {@value #AUTH_TIME_MIN} to
     *     {@value #AUTH_TIME_MAX}, or its minute or second part exceeds fifty-nine
     */
    private static Integer requireAuthTimeInDomain(Integer candidate) {
        int value = candidate;
        if (value < AUTH_TIME_MIN || value > AUTH_TIME_MAX) {
            throw new IllegalArgumentException("authTime must be a nine-digit time of day between "
                    + AUTH_TIME_MIN + " and " + AUTH_TIME_MAX + " but was: " + value);
        }
        int minute = (value / MINUTE_DIVISOR) % TIME_PART_MODULUS;
        if (minute > SEXAGESIMAL_MAX) {
            throw new IllegalArgumentException("authTime's minute part must be at most "
                    + SEXAGESIMAL_MAX + " but was: " + minute + " in " + value);
        }
        int second = (value / SECOND_DIVISOR) % TIME_PART_MODULUS;
        if (second > SEXAGESIMAL_MAX) {
            throw new IllegalArgumentException("authTime's second part must be at most "
                    + SEXAGESIMAL_MAX + " but was: " + second + " in " + value);
        }
        return candidate;
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
     * Returns a diagnostic rendering of the two clock parts of this key.
     *
     * <p>Refactoring Rationale: THE ACCOUNT IDENTIFIER IS OMITTED, and an earlier revision rendered it
     * on the argument that "a key is safe to log in full", because it "carries an account identifier
     * and two clock values and no cardholder data". The premise about cardholder data is true and the
     * conclusion does not follow from it. The sensitive-data logging contract in
     * {@code docs/architecture/observability.md} names account and customer identifiers in a clause of
     * their own, so being a key part is not what settles whether a value may be rendered -- the
     * contract asks what the value IS, not what role it plays in an index. The same correction was
     * applied to the composite keys of the batch and transaction contexts, so all three now agree.</p>
     *
     * <p>Trade-offs: the omission is total rather than partial, because abbreviating a protected value
     * IS masking and masking has one owner per context, {@code com.carddemo.authorization.mapper} for
     * this module; an entity key producing its own slightly different rule would give one value two
     * renderings with neither authoritative. The two clock values survive and are the more diagnostic
     * half in practice: an authorization date and an authorization time to the declared precision
     * locate a row in the pending stream to a narrow window, which is what an operator reading a
     * consumer or purge failure is looking for. What is given up is that two accounts' rows recorded in
     * the same instant are no longer distinguishable from a log line alone; the accessor returns the
     * identifier to any caller that needs it, and a request-scoped line already carries the correlation
     * identifier {@code com.carddemo.common.web.CorrelationIdFilter} publishes.</p>
     *
     * <p>Assumptions: this type still has a rendering while {@link PendingAuthDetail} deliberately has
     * none, and the reason is unchanged by the omission. A key rendered without its account identifier
     * still names a position in the pending stream; the detail segment is packed monetary and merchant
     * content throughout, so there is no subset of it worth rendering.</p>
     *
     * @return the authorization date and the authorization time, in key order, and no account
     *     identifier
     */
    @Override
    public String toString() {
        return "PendingAuthDetailKey[authDate=" + this.authDate
                + ", authTime=" + this.authTime + "]";
    }
}
