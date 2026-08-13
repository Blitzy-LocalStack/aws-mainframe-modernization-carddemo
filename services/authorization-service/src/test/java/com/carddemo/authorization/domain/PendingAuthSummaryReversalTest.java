package com.carddemo.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
     * A reversal that would leave the four-digit domain saturates at the bound instead of raising.
     *
     * <p>⚠️ Refactoring Rationale: this case asserted a REFUSAL -- {@code IllegalStateException} naming
     * "approvedAuthCount would reach -10000" -- and it was inverted because the refusal was the defect,
     * not the contract. On the purge path a raise abends the sweep mid-table, so the rows already deleted
     * stay deleted and the ones behind them do not, leaving the extract in a state neither the reference
     * nor a rerun can reconstruct. {@code cbl/CBPAUP0C.cbl} L288 subtracts with no size clause and no
     * floor test, so the reference never stops here. The policy is now saturation at
     * {@link PendingAuthSummary#COUNTER_MIN}, reported by the writer that knows the account.</p>
     *
     * <p>Assumptions: the counter rests EXACTLY at the bound rather than wrapping. That is the property
     * worth asserting, because the arithmetic is performed in {@code int} and a cast applied to the
     * subtraction's result would wrap -9999 - 1 to a positive value with no diagnostic -- a wrapped
     * counter reads as a plausible total, where one resting at the bound is recognisable as saturated.</p>
     *
     * <p>Assumptions: the AMOUNT is asserted to move even though the counter could not. The two members
     * are independent columns with independent domains, and the money member has its own saturation
     * argued on {@code MONEY_MAX_MAGNITUDE}; refusing the amount because the counter saturated would
     * discard a subtraction the reference performed. The divergence is registered as
     * {@code D-SUMMARY-COUNTER-SATURATION}.</p>
     */
    @Test
    @DisplayName("a reversal below the four-digit minimum saturates at the bound and still moves the total")
    void aReversalBelowThePictureMinimumSaturates() {
        PendingAuthSummary summary = PendingAuthSummary.rehydrated(ACCOUNT_ID, CUSTOMER_ID, null,
                null, null, null, null, null, ZERO, ZERO, ZERO, ZERO,
                Short.valueOf((short) -9999), Short.valueOf((short) 0), new BigDecimal("500.00"),
                ZERO);

        summary.reverseApproved(new BigDecimal("125.00"));

        assertThat(summary.getApprovedAuthCount())
                .as("the counter rests at the bound rather than wrapping to a positive value")
                .isEqualTo((short) PendingAuthSummary.COUNTER_MIN);
        assertThat(summary.getApprovedAuthAmount())
                .as("the money member has its own domain and its subtraction is unaffected")
                .isEqualByComparingTo(new BigDecimal("375.00"));
    }

    /**
     * The declined counter saturates at the same bound, and the two members narrow independently.
     *
     * <p>Assumptions: both arms are asserted because the saturation lives in one shared helper and a
     * change that reached only the approved arm would leave the declined arm raising -- which is exactly
     * the shape of the defect this pair replaces, where the aggregate's policy and the statements that
     * write it disagreed. Asserting one arm would not detect it.</p>
     *
     * <p>Assumptions: the classifier {@link PendingAuthSummary#exceedsCounterDomain(int)} is asserted
     * directly alongside the arm, because the writers report narrowing by consulting it rather than by
     * comparing before and after; a classifier that disagreed with the narrowing would silence the report
     * while the value still moved, and no assertion on the summary alone would show it.</p>
     */
    @Test
    @DisplayName("the declined counter saturates at the same bound and the classifier agrees")
    void theDeclinedCounterSaturatesAtTheSameBound() {
        PendingAuthSummary summary = PendingAuthSummary.rehydrated(ACCOUNT_ID, CUSTOMER_ID, null,
                null, null, null, null, null, ZERO, ZERO, ZERO, ZERO,
                Short.valueOf((short) 0), Short.valueOf((short) -9999), ZERO,
                new BigDecimal("90.00"));

        summary.reverseDeclined(new BigDecimal("40.00"));

        assertThat(summary.getDeclinedAuthCount()).isEqualTo((short) PendingAuthSummary.COUNTER_MIN);
        assertThat(summary.getDeclinedAuthAmount()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(summary.getApprovedAuthCount())
                .as("the other arm is untouched by this arm's saturation")
                .isEqualTo((short) 0);

        assertThat(PendingAuthSummary.exceedsCounterDomain(PendingAuthSummary.COUNTER_MIN - 1))
                .as("the classifier the writers consult recognises the value that was narrowed")
                .isTrue();
        assertThat(PendingAuthSummary.exceedsCounterDomain(PendingAuthSummary.COUNTER_MIN)).isFalse();
        assertThat(PendingAuthSummary.narrowedCounterToStoredDomain(PendingAuthSummary.COUNTER_MIN - 1))
                .isEqualTo(Short.valueOf((short) PendingAuthSummary.COUNTER_MIN));
    }

    /**
     * The increment saturates at the four-digit maximum rather than refusing the authorization.
     *
     * <p>⚠️ Purpose: this is the outage the saturation policy exists to remove, asserted from the domain
     * side. An account that has recorded 9999 approvals used to raise on the ten-thousandth, which rolled
     * the whole message back, redelivered it, and dead-lettered it after five receives -- so the account
     * stopped being answerable permanently. Here the tenth-thousandth approval is RECORDED: the counter
     * rests at {@link PendingAuthSummary#COUNTER_MAX} and the decision proceeds.</p>
     *
     * <p>Assumptions: the approved TOTAL is asserted to advance in the same call, because the outage was
     * not "the counter stopped counting" but "the authorization was not answered at all"; a policy that
     * saturated the counter and skipped the total would leave the row's two members describing different
     * sets of authorizations.</p>
     */
    @Test
    @DisplayName("the approved counter saturates at the four-digit maximum and still records the amount")
    void theApprovedCounterSaturatesAtTheMaximum() {
        PendingAuthSummary summary = PendingAuthSummary.rehydrated(ACCOUNT_ID, CUSTOMER_ID, null,
                null, null, null, null, null, ZERO, ZERO, ZERO, ZERO,
                Short.valueOf((short) 9999), Short.valueOf((short) 0), new BigDecimal("100.00"),
                ZERO);

        summary.recordApproved(new BigDecimal("25.00"));

        assertThat(summary.getApprovedAuthCount())
                .as("the ten-thousandth approval is recorded, not refused")
                .isEqualTo((short) PendingAuthSummary.COUNTER_MAX);
        assertThat(summary.getApprovedAuthAmount()).isEqualByComparingTo(new BigDecimal("125.00"));
    }
}
