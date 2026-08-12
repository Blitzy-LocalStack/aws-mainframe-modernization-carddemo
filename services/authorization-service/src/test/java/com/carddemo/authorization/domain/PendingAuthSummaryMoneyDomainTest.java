package com.carddemo.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that no value this summary holds can leave the money domain its own columns declare.
 *
 * <p><b>Purpose.</b> Every money member of the pending-authorization summary segment is
 * {@code PIC S9(09)V99 COMP-3} -- {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} declares the
 * credit limit, the cash limit, the credit balance and the cash balance at L23 to L26 and the two running
 * totals at L29 and L30 -- so the schema derives {@code NUMERIC(11,2)} from them under transformation rule
 * T1. Three of the fields those members receive their values from are one decimal order WIDER: the account
 * master's two limits are {@code PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} L8 and L9, and the requested
 * approved amounts are {@code PIC S9(10)V99} at {@code cpy/CIPAUDTY.cpy} L34 and L35. A value that crosses
 * that boundary unreduced reaches the database, which refuses the whole statement with a numeric-overflow
 * error -- and because the refusal rolls back the message's unit of work, the requester receives NO decision
 * at all and the request dead-letters after five receives. An account with a large limit could not transact
 * even for a small amount.
 *
 * <p>Assumptions: these cases assert SATURATION rather than refusal, and the distinction is the point. The
 * reference program narrows the same values implicitly, with a {@code MOVE} at {@code cbl/COPAUA0C.cbl} L810
 * and L811 and an {@code ADD} at its L821, and a COBOL move or add into a narrower numeric field discards
 * HIGH-ORDER digits -- so one thousand million becomes zero and the next authorization on that account is
 * declined for want of funds. Saturating leaves the stored value one cent short of the bound, which is
 * monotone in its input and keeps the account transacting. The difference from the reference is registered
 * as divergence D-AUTH-SUMMARY-MONEY-DOMAIN in
 * {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
class PendingAuthSummaryMoneyDomainTest {

    /** The account the fixture summary belongs to. */
    private static final Long ACCOUNT_ID = 10_000_000_002L;

    /** The customer the fixture account belongs to. */
    private static final Long CUSTOMER_ID = 900_000_002L;

    /** The greatest magnitude every money column on this row can hold, being nine digits and two. */
    private static final BigDecimal BOUND = new BigDecimal("999999999.99");

    /**
     * One cent past the bound, the smallest value the column cannot hold.
     *
     * <p>Assumptions: the pair of this and {@link #BOUND} is what makes the boundary itself the subject. A
     * case that only tried a value far outside the domain would pass against an implementation whose bound
     * was off by a cent in either direction.</p>
     */
    private static final BigDecimal ONE_CENT_OVER = new BigDecimal("1000000000.00");

    /** The widest amount a {@code PIC S9(10)V99} source field can carry, being the detail table's domain. */
    private static final BigDecimal WIDEST_SOURCE = new BigDecimal("9999999999.99");

    /**
     * Builds an empty summary, the state a newly-created root is in.
     *
     * @return a summary with both counters at zero and every amount at an exact zero
     */
    private static PendingAuthSummary emptySummary() {
        return new PendingAuthSummary(ACCOUNT_ID, CUSTOMER_ID);
    }

    /**
     * The published bound is exactly the nine-digit picture's, to the cent.
     */
    @Test
    @DisplayName("the published bound is exactly what PIC S9(09)V99 can hold")
    void theBoundIsThePictureDomain() {
        assertThat(PendingAuthSummary.MONEY_MAX_MAGNITUDE)
                .as("nine integer digits and two fractional, from cpy/CIPAUSMY.cpy L23")
                .isEqualByComparingTo(BOUND);
    }

    /**
     * The domain test admits the bound itself and refuses one cent past it, in both signs.
     *
     * <p>Assumptions: the NEGATIVE side is asserted because the columns are signed and the expiry sweep
     * subtracts from them, so a total can be driven below the negative bound as readily as above the
     * positive one. A test of the positive side alone would pass against an implementation that compared
     * the signed value rather than its magnitude.</p>
     */
    @Test
    @DisplayName("the bound is admitted, one cent past it is not, and both signs are judged alike")
    void theBoundaryIsInclusiveAndSymmetric() {
        assertThat(PendingAuthSummary.exceedsStoredDomain(BOUND)).isFalse();
        assertThat(PendingAuthSummary.exceedsStoredDomain(BOUND.negate())).isFalse();
        assertThat(PendingAuthSummary.exceedsStoredDomain(ONE_CENT_OVER)).isTrue();
        assertThat(PendingAuthSummary.exceedsStoredDomain(ONE_CENT_OVER.negate())).isTrue();
        assertThat(PendingAuthSummary.exceedsStoredDomain(null))
                .as("an absent amount is nothing to reduce, and the optional members are nullable")
                .isFalse();
    }

    /**
     * An in-domain amount is returned untouched, including its scale.
     *
     * <p>Assumptions: the SCALE is asserted as well as the value, because this helper must not become a
     * second place that rounds money. A decoded packed field can arrive at scale zero, and widening it here
     * would hide from the entity's own scale check whether the value ever carried a third decimal.</p>
     */
    @Test
    @DisplayName("an amount inside the domain is returned unchanged, scale included")
    void anInDomainAmountIsUntouched() {
        BigDecimal scaleZero = new BigDecimal("12");
        assertThat(PendingAuthSummary.narrowedToStoredDomain(scaleZero)).isSameAs(scaleZero);
        assertThat(PendingAuthSummary.narrowedToStoredDomain(BOUND)).isSameAs(BOUND);
        assertThat(PendingAuthSummary.narrowedToStoredDomain(null)).isNull();
    }

    /**
     * An out-of-domain amount saturates at the signed bound rather than truncating or wrapping.
     *
     * <p>Assumptions: the widest value a source field can legally carry is included, because it is the
     * value the reference's own narrowing handles worst -- ten digits truncated to nine leaves the low-order
     * nine, which bears no relation to the amount and is not monotone in it.</p>
     */
    @Test
    @DisplayName("an amount past the bound saturates at the bound, keeping its sign")
    void anOutOfDomainAmountSaturates() {
        assertThat(PendingAuthSummary.narrowedToStoredDomain(ONE_CENT_OVER))
                .isEqualByComparingTo(BOUND);
        assertThat(PendingAuthSummary.narrowedToStoredDomain(ONE_CENT_OVER.negate()))
                .isEqualByComparingTo(BOUND.negate());
        assertThat(PendingAuthSummary.narrowedToStoredDomain(WIDEST_SOURCE))
                .as("the reference's truncation would leave 999999999.99's low-order digits instead")
                .isEqualByComparingTo(BOUND);
    }

    /**
     * A limit wider than this segment stores is reduced instead of overflowing the column.
     *
     * <p>Assumptions: this is the case the runtime failure was observed on. An account holding a credit
     * limit of one thousand million is inside {@code ACCT-CREDIT-LIMIT}'s own domain, so the account context
     * reports it without complaint, and every authorization on that account then failed -- not because the
     * amount was large but because the limit was.</p>
     */
    @Test
    @DisplayName("a credit limit wider than the segment is reduced to the bound, not refused")
    void aWideLimitIsReducedRatherThanRefused() {
        PendingAuthSummary summary = emptySummary();

        summary.refreshLimits(ONE_CENT_OVER, WIDEST_SOURCE);

        assertThat(summary.getCreditLimit()).isEqualByComparingTo(BOUND);
        assertThat(summary.getCashLimit()).isEqualByComparingTo(BOUND);
    }

    /**
     * A limit at the bound is stored exactly, so the reduction cannot be a blanket clamp.
     */
    @Test
    @DisplayName("a credit limit exactly at the bound is stored exactly")
    void aLimitAtTheBoundIsStoredExactly() {
        PendingAuthSummary summary = emptySummary();

        summary.refreshLimits(BOUND, new BigDecimal("500.00"));

        assertThat(summary.getCreditLimit()).isEqualByComparingTo(BOUND);
        assertThat(summary.getCashLimit()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    /**
     * A single requested amount wider than the segment is accumulated as the bound, and still counted.
     *
     * <p>Assumptions: the COUNT is asserted alongside the total, because the requester's answer depends on
     * the contribution being applied at all. The behaviour being fixed was not an inaccurate total -- it was
     * a rolled-back decision, so the account's summary recorded nothing and the requester received nothing.
     * </p>
     */
    @Test
    @DisplayName("a declined amount wider than the segment saturates the total and still counts")
    void aWideDeclinedAmountSaturatesAndCounts() {
        PendingAuthSummary summary = emptySummary();

        summary.recordDeclined(ONE_CENT_OVER);

        assertThat(summary.getDeclinedAuthCount()).isEqualTo((short) 1);
        assertThat(summary.getDeclinedAuthAmount()).isEqualByComparingTo(BOUND);
        assertThat(summary.getCreditBalance())
                .as("a decline reserves nothing, saturated or not")
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    /**
     * Two in-domain contributions that sum past the bound saturate at it.
     *
     * <p>Assumptions: this is why the reduction is applied to the SUM and not to the addend. Each of these
     * two amounts is inside the domain, so an implementation that reduced only what it was given would
     * store their sum and overflow the column on the second one -- and the account would work until it
     * suddenly did not.</p>
     */
    @Test
    @DisplayName("two in-domain declines that sum past the bound saturate rather than overflow")
    void accumulationPastTheBoundSaturates() {
        PendingAuthSummary summary = emptySummary();

        summary.recordDeclined(new BigDecimal("600000000.00"));
        summary.recordDeclined(new BigDecimal("600000000.00"));

        assertThat(summary.getDeclinedAuthCount()).isEqualTo((short) 2);
        assertThat(summary.getDeclinedAuthAmount()).isEqualByComparingTo(BOUND);
    }

    /**
     * An approval past the bound saturates both the approved total and the credit balance.
     *
     * <p>Assumptions: BOTH members are asserted because {@code recordApproved} moves both, and the credit
     * balance is the one the next decision is taken from. A saturated total with an overflowing balance
     * would still fail the insert, so reducing one and not the other would fix nothing.</p>
     */
    @Test
    @DisplayName("an approval past the bound saturates the approved total and the credit balance alike")
    void anApprovalPastTheBoundSaturatesBothMembers() {
        PendingAuthSummary summary = emptySummary();

        summary.recordApproved(new BigDecimal("900000000.00"));
        summary.recordApproved(new BigDecimal("200000000.00"));

        assertThat(summary.getApprovedAuthCount()).isEqualTo((short) 2);
        assertThat(summary.getApprovedAuthAmount()).isEqualByComparingTo(BOUND);
        assertThat(summary.getCreditBalance()).isEqualByComparingTo(BOUND);
    }
}
