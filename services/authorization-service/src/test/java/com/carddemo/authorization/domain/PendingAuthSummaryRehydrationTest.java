package com.carddemo.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that a persisted summary can be reconstituted with every component it actually holds.
 *
 * <p><b>Purpose.</b> The summary root {@code PAUTSUM0} carries sixteen data components --
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} L19 to L31 -- of which two identifiers, five
 * status slots, four amounts, two counters and two running totals are all part of the stored state. An
 * extract or an unload therefore presents a summary with populated counters and populated balances, and a
 * load has to be able to build one.
 *
 * <p>Refactoring Rationale: the only public construction available was a two-argument constructor that
 * zeroed every other member, and the load path's answer to that was a guard which REFUSED any extract
 * record whose counters or amounts were populated -- so a summary that had ever seen an authorization
 * could not be loaded at all, and the load was usable only against an empty database. The guard is
 * withdrawn and replaced by this factory, which accepts and validates all sixteen. That is the difference
 * between a load that cannot lose data silently and one that cannot run.
 *
 * <p>Assumptions: the ONLINE rules must stay narrower, and one case below asserts that they did. A caller
 * deciding an authorization still cannot set a counter or a balance directly; the only way to move either
 * is through the paired record operations. Widening the online path in order to widen the load path is the
 * regression this class exists to catch.
 *
 * <p>Assumptions: the validation is asserted per component and by message, because sixteen positional
 * arguments of five types are exactly the shape in which a transposition compiles. A refusal that named
 * only its type would leave a caller with no way to tell which of four amounts was at fault.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
class PendingAuthSummaryRehydrationTest {

    /** The account the fixture summary belongs to. */
    private static final Long ACCOUNT_ID = 10_000_000_001L;

    /** The customer the fixture account belongs to. */
    private static final Long CUSTOMER_ID = 900_000_001L;

    /** The credit limit the fixture summary mirrors from the account master. */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");

    /** The cash credit limit the fixture summary mirrors from the account master. */
    private static final BigDecimal CASH_LIMIT = new BigDecimal("1000.00");

    /** The authorized-but-unposted credit balance of the fixture summary. */
    private static final BigDecimal CREDIT_BALANCE = new BigDecimal("300.00");

    /** The cash balance, which the reference program only ever zeroes. */
    private static final BigDecimal CASH_BALANCE = new BigDecimal("0.00");

    /** The running total of approved authorizations, matching {@code pautsum0-purge-parent.bin}. */
    private static final BigDecimal APPROVED_TOTAL = new BigDecimal("300.00");

    /** The running total of declined authorizations, matching {@code pautsum0-purge-parent.bin}. */
    private static final BigDecimal DECLINED_TOTAL = new BigDecimal("150.00");

    /** The approved authorization count, matching {@code pautsum0-purge-parent.bin}. */
    private static final short APPROVED_COUNT = 2;

    /** The declined authorization count, matching {@code pautsum0-purge-parent.bin}. */
    private static final short DECLINED_COUNT = 2;

    /**
     * Rehydrates the fixture summary, with one amount replaceable so a refusal can be provoked.
     *
     * @param creditLimitOverride the credit limit to supply in place of the fixture's
     * @return the rehydrated summary
     */
    private static PendingAuthSummary withCreditLimit(BigDecimal creditLimitOverride) {
        return PendingAuthSummary.rehydrated(ACCOUNT_ID, CUSTOMER_ID, "Y",
                "01", "02", "03", "04", "05",
                creditLimitOverride, CASH_LIMIT, CREDIT_BALANCE, CASH_BALANCE,
                Short.valueOf(APPROVED_COUNT), Short.valueOf(DECLINED_COUNT),
                APPROVED_TOTAL, DECLINED_TOTAL);
    }

    /**
     * Rehydrates the fixture summary, with one counter replaceable so a refusal can be provoked.
     *
     * @param approvedCountOverride the approved counter to supply in place of the fixture's
     * @return the rehydrated summary
     */
    private static PendingAuthSummary withApprovedCount(Short approvedCountOverride) {
        return PendingAuthSummary.rehydrated(ACCOUNT_ID, CUSTOMER_ID, "Y",
                "01", "02", "03", "04", "05",
                CREDIT_LIMIT, CASH_LIMIT, CREDIT_BALANCE, CASH_BALANCE,
                approvedCountOverride, Short.valueOf(DECLINED_COUNT),
                APPROVED_TOTAL, DECLINED_TOTAL);
    }

    /**
     * A fully populated summary is rehydrated with every one of its sixteen components retained.
     *
     * <p>Assumptions: all sixteen are asserted, not a sample, because the fault this replaces was a
     * SILENT one -- values were being discarded or refused rather than mis-stored -- and only an assertion
     * that names every component can prove none is dropped. The five status slots are given five distinct
     * values so a mapping that transposed two of them fails here rather than looking correct.</p>
     */
    @Test
    @DisplayName("a populated summary rehydrates with all sixteen components retained")
    void aPopulatedSummaryRehydratesCompletely() {
        PendingAuthSummary summary = withCreditLimit(CREDIT_LIMIT);

        assertThat(summary.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(summary.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(summary.getAuthStatus()).isEqualTo("Y");
        assertThat(summary.getAccountStatus1()).isEqualTo("01");
        assertThat(summary.getAccountStatus2()).isEqualTo("02");
        assertThat(summary.getAccountStatus3()).isEqualTo("03");
        assertThat(summary.getAccountStatus4()).isEqualTo("04");
        assertThat(summary.getAccountStatus5()).isEqualTo("05");
        assertThat(summary.getCreditLimit()).isEqualByComparingTo(CREDIT_LIMIT);
        assertThat(summary.getCashLimit()).isEqualByComparingTo(CASH_LIMIT);
        assertThat(summary.getCreditBalance()).isEqualByComparingTo(CREDIT_BALANCE);
        assertThat(summary.getCashBalance()).isEqualByComparingTo(CASH_BALANCE);
        assertThat(summary.getApprovedAuthCount()).isEqualTo(APPROVED_COUNT);
        assertThat(summary.getDeclinedAuthCount()).isEqualTo(DECLINED_COUNT);
        assertThat(summary.getApprovedAuthAmount()).isEqualByComparingTo(APPROVED_TOTAL);
        assertThat(summary.getDeclinedAuthAmount()).isEqualByComparingTo(DECLINED_TOTAL);
    }

    /**
     * Every amount is normalised to scale two, whatever scale it arrived at.
     *
     * <p>Assumptions: a scale-zero amount is WIDENED rather than refused, because a packed field decoded
     * with no declared decimal positions yields exactly that and it is the same quantity. Asserting the
     * resulting scale rather than only the value is what distinguishes widening from passing the value
     * through, and the scale matters: a {@code NUMERIC(11,2)} column and a comparison by
     * {@code equals} both see scale.</p>
     */
    @Test
    @DisplayName("an amount of smaller scale is widened to scale two rather than refused")
    void anAmountOfSmallerScaleIsWidened() {
        PendingAuthSummary summary = withCreditLimit(new BigDecimal("5000"));

        assertThat(summary.getCreditLimit()).isEqualByComparingTo(CREDIT_LIMIT);
        assertThat(summary.getCreditLimit().scale()).isEqualTo(2);
    }

    /**
     * An amount of greater scale is refused rather than rounded.
     *
     * <p>Assumptions: refusing is the whole point. A third decimal place in a stored summary is a decode
     * fault, and rounding it here would put a value in the row that no source produced while leaving the
     * fault undetected. The migration forbids silent rounding of money everywhere, and this is where that
     * rule reaches the load path.</p>
     */
    @Test
    @DisplayName("an amount carrying a third decimal place is refused, never rounded")
    void anAmountOfGreaterScaleIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> withCreditLimit(new BigDecimal("5000.005")))
                .withMessageContaining("creditLimit carries 3 decimal places")
                .withMessageContaining("money is never rounded silently");
    }

    /**
     * Every amount is required, because every amount column is declared not null.
     */
    @Test
    @DisplayName("an absent amount is refused, naming the component")
    void anAbsentAmountIsRefused() {
        assertThatNullPointerException()
                .isThrownBy(() -> withCreditLimit(null))
                .withMessageContaining("creditLimit");
    }

    /**
     * The counters are bounded by the picture's four digits and not by the halfword that stores them.
     *
     * <p>Assumptions: the boundary asserted is 9999 accepted and 10000 refused, which is the PICTURE's
     * bound rather than the storage type's 32767. Checking at the wider bound would let this factory build
     * a summary the column's own {@code ck_pending_auth_summary_counts} then refuses, turning a
     * representable-value question into a database error raised at flush time with no field named.</p>
     *
     * <p>Assumptions: the NEGATIVE half of the range is accepted, because the picture is signed and the
     * check constraint admits it. An extract whose counters understate its children can legitimately carry
     * a negative counter, and refusing it here would make the load stricter than both the copybook and
     * the column.</p>
     */
    @Test
    @DisplayName("a counter is bounded at the picture's four digits, signed, not at the halfword's range")
    void theCountersAreBoundedByThePicture() {
        assertThat(withApprovedCount(Short.valueOf((short) 9999)).getApprovedAuthCount())
                .isEqualTo((short) 9999);
        assertThat(withApprovedCount(Short.valueOf((short) -9999)).getApprovedAuthCount())
                .isEqualTo((short) -9999);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> withApprovedCount(Short.valueOf((short) 10_000)))
                .withMessageContaining("approvedAuthCount is 10000")
                .withMessageContaining("PIC S9(04) COMP");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> withApprovedCount(Short.valueOf((short) -10_000)))
                .withMessageContaining("PIC S9(04) COMP");
        assertThatNullPointerException()
                .isThrownBy(() -> withApprovedCount(null))
                .withMessageContaining("approvedAuthCount");
    }

    /**
     * A status slot wider than its column is refused, and an absent one is accepted.
     *
     * <p>Assumptions: absence and over-width are different faults and are treated differently. A blank
     * segment slot decodes to absence, which every one of these five nullable columns admits; a slot
     * carrying three characters cannot be stored in a {@code CHAR(2)} column at all, and refusing it here
     * names the slot where the database would name only the column.</p>
     */
    @Test
    @DisplayName("an over-wide status slot is refused and an absent one is accepted")
    void statusSlotWidthsAreEnforcedAndAbsenceIsAllowed() {
        PendingAuthSummary blanks = PendingAuthSummary.rehydrated(ACCOUNT_ID, CUSTOMER_ID, null,
                null, null, null, null, null,
                CREDIT_LIMIT, CASH_LIMIT, CREDIT_BALANCE, CASH_BALANCE,
                Short.valueOf(APPROVED_COUNT), Short.valueOf(DECLINED_COUNT),
                APPROVED_TOTAL, DECLINED_TOTAL);
        assertThat(blanks.getAuthStatus()).isNull();
        assertThat(blanks.getAccountStatus1()).isNull();
        assertThat(blanks.getAccountStatus5()).isNull();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> PendingAuthSummary.rehydrated(ACCOUNT_ID, CUSTOMER_ID, "Y",
                        "001", "02", "03", "04", "05",
                        CREDIT_LIMIT, CASH_LIMIT, CREDIT_BALANCE, CASH_BALANCE,
                        Short.valueOf(APPROVED_COUNT), Short.valueOf(DECLINED_COUNT),
                        APPROVED_TOTAL, DECLINED_TOTAL))
                .withMessageContaining("accountStatus1 is 3 characters")
                .withMessageContaining("the column declares 2");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> PendingAuthSummary.rehydrated(ACCOUNT_ID, CUSTOMER_ID, "YY",
                        "01", "02", "03", "04", "05",
                        CREDIT_LIMIT, CASH_LIMIT, CREDIT_BALANCE, CASH_BALANCE,
                        Short.valueOf(APPROVED_COUNT), Short.valueOf(DECLINED_COUNT),
                        APPROVED_TOTAL, DECLINED_TOTAL))
                .withMessageContaining("authStatus is 2 characters");
    }

    /**
     * Both identifiers are required and positive, and neither is quoted in a refusal.
     *
     * <p>Assumptions: the value's absence from the message is asserted as well as the refusal, because the
     * sensitive-data logging contract names account and customer identifiers in a clause of their own and
     * a refusal message reaches the same logs a rendering does.</p>
     */
    @Test
    @DisplayName("both identifiers are required and positive, and neither value is quoted")
    void bothIdentifiersAreRequiredAndPositive() {
        assertThatNullPointerException()
                .isThrownBy(() -> PendingAuthSummary.rehydrated(null, CUSTOMER_ID, "Y",
                        "01", "02", "03", "04", "05",
                        CREDIT_LIMIT, CASH_LIMIT, CREDIT_BALANCE, CASH_BALANCE,
                        Short.valueOf(APPROVED_COUNT), Short.valueOf(DECLINED_COUNT),
                        APPROVED_TOTAL, DECLINED_TOTAL))
                .withMessageContaining("accountId");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> PendingAuthSummary.rehydrated(ACCOUNT_ID, Long.valueOf(0L), "Y",
                        "01", "02", "03", "04", "05",
                        CREDIT_LIMIT, CASH_LIMIT, CREDIT_BALANCE, CASH_BALANCE,
                        Short.valueOf(APPROVED_COUNT), Short.valueOf(DECLINED_COUNT),
                        APPROVED_TOTAL, DECLINED_TOTAL))
                .withMessageContaining("customerId must be a positive")
                .withMessageNotContaining(String.valueOf(ACCOUNT_ID));
    }

    /**
     * Widening the load path left the online path unable to set a counter or a balance directly.
     *
     * <p>Assumptions: this is the regression guard on the fix. The easy way to make a populated summary
     * constructible is to publish setters, which would let a request handler move a counter without moving
     * the total that belongs with it and leave a row internally inconsistent with nothing to detect it.
     * The assertion is on the type's public surface rather than on behaviour, because absence of a method
     * is what has to be proved.</p>
     *
     * <p>Assumptions: the two record operations are asserted PRESENT alongside, so a change that removed
     * them -- leaving nothing able to move a counter at all -- could not pass this case.</p>
     */
    @Test
    @DisplayName("no setter exposes a counter or a balance; only the paired record operations move them")
    void theOnlinePathStillCannotSetCountersOrBalancesDirectly() {
        assertThat(PendingAuthSummary.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("setApprovedAuthCount", "setDeclinedAuthCount",
                        "setApprovedAuthAmount", "setDeclinedAuthAmount", "setCreditBalance",
                        "setCashBalance")
                .contains("recordApproved", "recordDeclined", "reverseApproved", "reverseDeclined");
    }
}
