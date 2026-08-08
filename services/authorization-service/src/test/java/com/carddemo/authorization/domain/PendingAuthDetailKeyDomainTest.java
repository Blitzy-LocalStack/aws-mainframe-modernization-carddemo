package com.carddemo.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that {@link PendingAuthDetailKey} enforces the domain its own columns declare.
 *
 * <p><b>Purpose.</b> The composite key is the migrated form of the child segment's eight-byte sequence
 * field, {@code FIELD NAME=(PAUT9CTS,SEQ,U),START=1,BYTES=8,TYPE=C} at
 * {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd} L37, decoded into three columns. The schema
 * bounds two of those columns with check constraints -- {@code ck_pending_auth_detail_auth_date_domain}
 * and {@code ck_pending_auth_detail_auth_time_domain} in {@code db/migration/V1__authorization.sql} --
 * and this class asserts the type refuses the same values the columns refuse.
 *
 * <p>Refactoring Rationale: the constructor previously accepted anything, including nulls and a
 * calendar-shaped eight-digit date, and the only domain check anywhere was in a mapper helper that no
 * production path called. The consequence was that every caller reached the database before an
 * out-of-domain value was noticed, where it surfaced as a check-constraint violation at flush time --
 * naming a constraint rather than an argument, arriving after the transaction had done other work, and
 * unrecoverable inside it. Moving the check into the type means one rule serves the listener, the loader,
 * the screen selector and every test alike.
 *
 * <p>Assumptions: refusals are asserted by MESSAGE CONTENT and not merely by exception type, because the
 * whole value of moving the check here is that a caller learns which part was wrong. An assertion on the
 * type alone would pass for a refusal that named nothing.
 *
 * <p>Assumptions: the two clock values are the DECODED date and time. The complement the segment stores
 * is bounded by the same widths but a different domain -- the complement of the first day of a year is
 * 99998, outside the date range -- so a caller that passed a complement is refused, and one case below
 * asserts exactly that rather than leaving it to a reader to infer.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
class PendingAuthDetailKeyDomainTest {

    /** A synthetic eleven-digit account identifier, at the declared width of {@code PA-ACCT-ID}. */
    private static final Long ACCOUNT_ID = 21_820_493_291L;

    /** A valid ordinal date: year 26, day 218. */
    private static final int VALID_DATE = 26_218;

    /** A valid time of day: 09:16:44.123. */
    private static final int VALID_TIME = 91_644_123;

    /**
     * Every part of the key is required, and each refusal names the part.
     *
     * <p>Assumptions: the parts are refused BY NAME rather than by position, because all three are boxed
     * numbers and a caller that transposed the date and the time would otherwise get a message that could
     * not tell it which argument to look at.</p>
     */
    @Test
    @DisplayName("all three key parts are required, and each refusal names the part")
    void everyPartIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PendingAuthDetailKey(null, VALID_DATE, VALID_TIME))
                .withMessageContaining("accountId");
        assertThatNullPointerException()
                .isThrownBy(() -> new PendingAuthDetailKey(ACCOUNT_ID, null, VALID_TIME))
                .withMessageContaining("authDate");
        assertThatNullPointerException()
                .isThrownBy(() -> new PendingAuthDetailKey(ACCOUNT_ID, VALID_DATE, null))
                .withMessageContaining("authTime");
    }

    /**
     * A non-positive account identifier is refused, and the refusal does not quote it.
     *
     * <p>Assumptions: the value's ABSENCE from the message is asserted as well as the refusal, because the
     * sensitive-data logging contract names account identifiers in a clause of its own and this type's own
     * diagnostic form omits the identifier for that reason. A refusal that quoted it would reintroduce
     * precisely what the rendering withholds, and refusal messages reach the same logs.</p>
     */
    @Test
    @DisplayName("a non-positive account identifier is refused without the value being quoted")
    void aNonPositiveAccountIdentifierIsRefusedWithoutBeingQuoted() {
        for (long invalid : new long[] {0L, -1L, -21_820_493_291L}) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PendingAuthDetailKey(
                            Long.valueOf(invalid), VALID_DATE, VALID_TIME))
                    .withMessageContaining("accountId must be a positive account identifier")
                    .withMessageNotContaining(String.valueOf(Math.abs(invalid)));
        }
    }

    /**
     * The ordinal-date range is exactly the range the check constraint declares, inclusive at both ends.
     *
     * <p>Assumptions: the boundary values are asserted to be ACCEPTED and the values one step outside to
     * be refused, in one case, because a range check can only be wrong at its ends and an assertion that
     * tests only the middle detects neither an off-by-one nor a missing check.</p>
     */
    @Test
    @DisplayName("the ordinal date accepts 1 through 99366 inclusive and refuses either neighbour")
    void theOrdinalDateRangeMatchesTheCheckConstraint() {
        assertThatCode(() -> new PendingAuthDetailKey(
                ACCOUNT_ID, PendingAuthDetailKey.AUTH_DATE_MIN, VALID_TIME))
                .doesNotThrowAnyException();
        assertThatCode(() -> new PendingAuthDetailKey(
                ACCOUNT_ID, PendingAuthDetailKey.AUTH_DATE_MAX, VALID_TIME))
                .doesNotThrowAnyException();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PendingAuthDetailKey(
                        ACCOUNT_ID, PendingAuthDetailKey.AUTH_DATE_MIN - 1, VALID_TIME))
                .withMessageContaining("authDate must be a five-digit ordinal date between");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PendingAuthDetailKey(
                        ACCOUNT_ID, PendingAuthDetailKey.AUTH_DATE_MAX + 1, VALID_TIME))
                .withMessageContaining("authDate must be a five-digit ordinal date between");
    }

    /**
     * A date whose day-of-year part is out of range is refused even when the whole value is in range.
     *
     * <p>Assumptions: this is the second half of the check constraint, and it is the half a plain range
     * test misses. 26000 and 26400 both lie between one and 99366, so a bounds-only check accepts them,
     * yet neither names a day: the first has day zero and the second day four hundred. The constraint
     * expresses this as a modulus on the stored value, and so does the type.</p>
     *
     * <p>Assumptions: 366 is accepted rather than 365, because the reference field is an ordinal date with
     * no year context at the point the key is built, so a leap year's last day has to be representable.
     * Rejecting 366 would make every 31 December of a leap year unstorable.</p>
     */
    @Test
    @DisplayName("a day-of-year part of 0 or above 366 is refused, and 366 itself is accepted")
    void theDayOfYearPartIsBoundedIndependentlyOfTheWholeValue() {
        assertThatCode(() -> new PendingAuthDetailKey(ACCOUNT_ID, 24_366, VALID_TIME))
                .doesNotThrowAnyException();

        for (int invalid : new int[] {26_000, 26_400, 26_999}) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("%d is inside the whole-value range but names no day of year", invalid)
                    .isThrownBy(() -> new PendingAuthDetailKey(ACCOUNT_ID, invalid, VALID_TIME))
                    .withMessageContaining("authDate's day-of-year part must be between");
        }
    }

    /**
     * The time-of-day range is exactly the range the check constraint declares, inclusive at both ends.
     *
     * <p>Assumptions: the low bound is ZERO rather than one, unlike the date's, because midnight exactly
     * is a time and day zero is not a day. The asymmetry is the constraint's own and is asserted so it
     * cannot be "tidied" into agreement with the date bound.</p>
     */
    @Test
    @DisplayName("the time of day accepts 0 through 235959999 inclusive and refuses either neighbour")
    void theTimeOfDayRangeMatchesTheCheckConstraint() {
        assertThatCode(() -> new PendingAuthDetailKey(
                ACCOUNT_ID, VALID_DATE, PendingAuthDetailKey.AUTH_TIME_MIN))
                .doesNotThrowAnyException();
        assertThatCode(() -> new PendingAuthDetailKey(
                ACCOUNT_ID, VALID_DATE, PendingAuthDetailKey.AUTH_TIME_MAX))
                .doesNotThrowAnyException();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PendingAuthDetailKey(
                        ACCOUNT_ID, VALID_DATE, PendingAuthDetailKey.AUTH_TIME_MIN - 1))
                .withMessageContaining("authTime must be a nine-digit time of day between");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PendingAuthDetailKey(
                        ACCOUNT_ID, VALID_DATE, PendingAuthDetailKey.AUTH_TIME_MAX + 1))
                .withMessageContaining("authTime must be a nine-digit time of day between");
    }

    /**
     * The minute and second parts are bounded at fifty-nine independently of the whole value.
     *
     * <p>Assumptions: the clock half of this value is the POSITIONAL form {@code HHMMSS} and not a count
     * of seconds, so its minute and second fields carry two decimal digits each and can hold sixty
     * through ninety-nine. 96044000 is nine hours sixty minutes forty-four seconds -- a value a plain
     * range check accepts and no clock ever showed -- and 90099000 is the same fault one field along.
     * Both are refused, and the refusal names which field.</p>
     */
    @Test
    @DisplayName("a minute or second part above 59 is refused, naming which field")
    void theMinuteAndSecondPartsAreBoundedSexagesimally() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PendingAuthDetailKey(ACCOUNT_ID, VALID_DATE, 96_044_000))
                .withMessageContaining("authTime's minute part must be at most 59");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PendingAuthDetailKey(ACCOUNT_ID, VALID_DATE, 90_099_000))
                .withMessageContaining("authTime's second part must be at most 59");
    }

    /**
     * A caller that passes the stored complement instead of the decoded value is refused.
     *
     * <p>Assumptions: this is the confusion the key type is most likely to be handed, because the segment
     * stores the complement and the columns store the decode. The complement of 24001 is 75998, whose
     * day-of-year part is 998, and the complement of a time of one millisecond past midnight is
     * 999998999, whose whole value exceeds the time range. Both are therefore refused rather than stored
     * as a date and time a thousand years apart from the ones intended.</p>
     */
    @Test
    @DisplayName("a stored nines complement is refused where a decoded value is expected")
    void aStoredComplementIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("99999 - 24001 = 75998, whose day-of-year part is 998")
                .isThrownBy(() -> new PendingAuthDetailKey(ACCOUNT_ID, 75_998, VALID_TIME))
                .withMessageContaining("authDate's day-of-year part must be between");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("999999999 - 1000 = 999998999, which exceeds the nine-digit time range")
                .isThrownBy(() -> new PendingAuthDetailKey(ACCOUNT_ID, VALID_DATE, 999_998_999))
                .withMessageContaining("authTime must be a nine-digit time of day between");
    }

    /**
     * A valid key keeps every part it was given.
     *
     * <p>Assumptions: this accompanies the refusals so that a constructor which rejected everything could
     * not pass this class. It is the one positive case, and it asserts all three parts rather than one.</p>
     */
    @Test
    @DisplayName("a valid key retains all three parts unchanged")
    void aValidKeyRetainsItsParts() {
        PendingAuthDetailKey key = new PendingAuthDetailKey(ACCOUNT_ID, VALID_DATE, VALID_TIME);

        assertThat(key.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(key.getAuthDate()).isEqualTo(VALID_DATE);
        assertThat(key.getAuthTime()).isEqualTo(VALID_TIME);
    }
}
