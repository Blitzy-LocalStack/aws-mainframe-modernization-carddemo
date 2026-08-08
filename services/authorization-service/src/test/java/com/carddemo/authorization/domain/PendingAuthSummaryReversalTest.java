package com.carddemo.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts the arithmetic the purge performs against a summary when one of its authorizations expires.
 *
 * <p><b>Purpose.</b> {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl}
 * {@code 4000-CHECK-IF-EXPIRED} reverses an expiring authorization out of its parent's running totals: its
 * approved arm at L288 to L289 subtracts one from the approved count and the authorization's APPROVED
 * amount from the approved total, and its declined arm at L291 to L292 subtracts one from the declined
 * count and the authorization's TRANSACTION amount from the declined total. Neither arm touches a balance.
 *
 * <p>Assumptions: the three properties asserted here are each a place an implementation naturally goes
 * wrong. The two arms take their amount from different columns, so a single amount parameter shared
 * between them is silently wrong for every decline. The credit balance is added to on approval and NOT
 * given back on expiry, so a symmetric implementation would release a balance the reference system leaves
 * standing. And the counters are signed, so a floor at zero would refuse a reversal the reference performs.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
class PendingAuthSummaryReversalTest {

    /** The account the fixture summary belongs to. */
    private static final Long ACCOUNT_ID = 10_000_000_001L;

    /** The customer the fixture account belongs to. */
    private static final Long CUSTOMER_ID = 900_000_001L;

    /** An exact zero at the scale every money column stores. */
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /**
     * Builds an empty summary, the state a newly-created root is in.
     *
     * @return a summary with both counters at zero and every amount at an exact zero
     */
    private static PendingAuthSummary emptySummary() {
        return new PendingAuthSummary(ACCOUNT_ID, CUSTOMER_ID);
    }

    /**
     * An approved reversal undoes exactly what an approved record did, except the balance.
     *
     * <p>Assumptions: the balance is asserted to be UNCHANGED by the reversal and still carrying the
     * amount the record added, which is divergence D-PURGE-BALANCE recorded on the entity. Asserting the
     * value rather than merely not asserting it is what makes the asymmetry visible: a future symmetric
     * implementation would fail here rather than passing silently.</p>
     */
    @Test
    @DisplayName("reversing an approval undoes its count and total but leaves the credit balance")
    void reversingAnApprovalLeavesTheCreditBalance() {
        PendingAuthSummary summary = emptySummary();
        BigDecimal amount = new BigDecimal("100.00");

        summary.recordApproved(amount);
        assertThat(summary.getApprovedAuthCount()).isEqualTo((short) 1);
        assertThat(summary.getApprovedAuthAmount()).isEqualByComparingTo(amount);
        assertThat(summary.getCreditBalance()).isEqualByComparingTo(amount);

        summary.reverseApproved(amount);
        assertThat(summary.getApprovedAuthCount()).isEqualTo((short) 0);
        assertThat(summary.getApprovedAuthAmount()).isEqualByComparingTo(ZERO);
        assertThat(summary.getCreditBalance())
                .as("the reference purge releases no balance, so the reservation stands")
                .isEqualByComparingTo(amount);
    }

    /**
     * A declined reversal undoes exactly what a declined record did, and touches no balance either way.
     */
    @Test
    @DisplayName("reversing a decline undoes its count and total and never touches a balance")
    void reversingADeclineTouchesNoBalance() {
        PendingAuthSummary summary = emptySummary();
        BigDecimal amount = new BigDecimal("50.00");

        summary.recordDeclined(amount);
        assertThat(summary.getDeclinedAuthCount()).isEqualTo((short) 1);
        assertThat(summary.getDeclinedAuthAmount()).isEqualByComparingTo(amount);
        assertThat(summary.getCreditBalance()).isEqualByComparingTo(ZERO);

        summary.reverseDeclined(amount);
        assertThat(summary.getDeclinedAuthCount()).isEqualTo((short) 0);
        assertThat(summary.getDeclinedAuthAmount()).isEqualByComparingTo(ZERO);
        assertThat(summary.getCreditBalance()).isEqualByComparingTo(ZERO);
    }

    /**
     * Each arm moves only its own pair of members.
     *
     * <p>Assumptions: this is the cross-contamination check, and it is worth its own case because the four
     * members are two pairs of the same two types. An implementation that reversed the approved count
     * against the declined total would satisfy both cases above and fail here.</p>
     */
    @Test
    @DisplayName("each reversal arm moves only its own counter and its own total")
    void eachArmMovesOnlyItsOwnPair() {
        PendingAuthSummary summary = emptySummary();
        summary.recordApproved(new BigDecimal("200.00"));
        summary.recordDeclined(new BigDecimal("75.00"));

        summary.reverseApproved(new BigDecimal("200.00"));

        assertThat(summary.getApprovedAuthCount()).isEqualTo((short) 0);
        assertThat(summary.getApprovedAuthAmount()).isEqualByComparingTo(ZERO);
        assertThat(summary.getDeclinedAuthCount())
                .as("the declined counter belongs to the other arm")
                .isEqualTo((short) 1);
        assertThat(summary.getDeclinedAuthAmount()).isEqualByComparingTo(new BigDecimal("75.00"));
    }

    /**
     * A counter at zero reverses to minus one rather than being floored.
     *
     * <p>Assumptions: the picture is SIGNED four digits and the schema's own
     * {@code ck_pending_auth_summary_counts} admits the negative half, so a negative counter is a
     * representable state. An extract whose counters understate its children can produce one, and flooring
     * it here would refuse a reversal the reference performs without complaint -- turning an out-of-step
     * extract into a failed purge rather than a visible value.</p>
     */
    @Test
    @DisplayName("a counter already at zero reverses to minus one rather than being floored")
    void aCounterAtZeroGoesNegative() {
        PendingAuthSummary summary = emptySummary();

        summary.reverseApproved(ZERO);
        summary.reverseDeclined(ZERO);

        assertThat(summary.getApprovedAuthCount()).isEqualTo((short) -1);
        assertThat(summary.getDeclinedAuthCount()).isEqualTo((short) -1);
    }

    /**
     * A reversal that would leave the four-digit domain is refused and the summary is left unchanged.
     *
     * <p>Assumptions: the bound is the PICTURE's -9999 and not the halfword's -32768, for the same reason
     * the increment's bound is 9999 and not 32767 -- the schema's check constraint enforces the narrower
     * range, so a value outside it would be refused by the column at flush time with no field named.</p>
     *
     * <p>Assumptions: the summary is asserted UNCHANGED after the refusal, because a partially applied
     * reversal -- total moved, counter refused -- would leave the row internally inconsistent, which is
     * worse than the refusal it was trying to report.</p>
     */
    @Test
    @DisplayName("a reversal below the four-digit minimum is refused and changes nothing")
    void aReversalBelowThePictureMinimumIsRefused() {
        PendingAuthSummary summary = PendingAuthSummary.rehydrated(ACCOUNT_ID, CUSTOMER_ID, null,
                null, null, null, null, null, ZERO, ZERO, ZERO, ZERO,
                Short.valueOf((short) -9999), Short.valueOf((short) 0), ZERO, ZERO);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> summary.reverseApproved(ZERO))
                .withMessageContaining("approvedAuthCount would reach -10000")
                .withMessageContaining("PIC S9(04) COMP");

        assertThat(summary.getApprovedAuthCount()).isEqualTo((short) -9999);
        assertThat(summary.getApprovedAuthAmount()).isEqualByComparingTo(ZERO);
    }
}
