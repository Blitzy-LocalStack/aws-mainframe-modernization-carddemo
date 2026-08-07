package com.carddemo.reference.dto;

import com.carddemo.common.money.Money;

/**
 * The interest rate that applies to one account group, transaction type and category, satisfying the
 * contract schema {@code DisclosureGroupRate}.
 *
 * <p>Purpose: the outbound shape of the rate lookup that interest accrual performs once per balance
 * row. It reports both the group asked for and the group actually applied, so that a caller can tell
 * a direct hit from a fallback without inferring it.</p>
 *
 * <p>Assumptions: reporting the applied group beside the requested one is what makes the fallback
 * observable, and observability here is not cosmetic. {@code app/cbl/CBACT04C.cbl} reads the rate at
 * L415, and when the read misses it moves the literal {@code 'DEFAULT'} into the group field at L437
 * and reads again, so the rate returned may belong to a group the caller never named. A reply
 * carrying only the rate would be indistinguishable in the two cases, and an account accruing at the
 * default rate because its own group is missing from the seed is a data defect that would then have
 * no symptom at all.</p>
 *
 * <p>Alternatives Considered: answering 404 on a miss instead of falling back was evaluated and
 * rejected, because the baseline does not fail -- it substitutes and continues. Refusing would change
 * observable behaviour, which transformation rule T9 forbids, and would stop accrual for an account
 * the baseline accrues for.</p>
 *
 * @param requestedAcctGroupId the account group the caller asked for, exactly as received
 * @param appliedAcctGroupId the account group whose row supplied the rate; equal to the requested
 *     group on a direct hit and the blank-padded default group on a fallback
 * @param tranTypeCd the two-character transaction type, carried through unchanged by a fallback
 * @param tranCatCd the four-digit transaction category, carried through unchanged by a fallback
 * @param interestRate the annual percentage rate as an exact decimal, serialised as a JSON string by
 *     {@code com.carddemo.common.money.MoneyModule} so that no client parses it into a binary
 *     floating-point value; it is an operand of the accrual computation at L464 to L465 of that
 *     program, so its exactness reaches money a customer is charged
 * @param defaultGroupApplied {@code true} when the rate came from the default group rather than from
 *     the group requested
 */
public record DisclosureGroupRateResponse(
        String requestedAcctGroupId,
        String appliedAcctGroupId,
        String tranTypeCd,
        String tranCatCd,
        Money interestRate,
        boolean defaultGroupApplied) {
}
