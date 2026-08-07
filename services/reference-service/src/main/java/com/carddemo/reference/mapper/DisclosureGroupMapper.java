package com.carddemo.reference.mapper;

import com.carddemo.common.money.Money;
import com.carddemo.reference.domain.DisclosureGroup;
import com.carddemo.reference.dto.DisclosureGroupRateResponse;

/**
 * Converts a stored disclosure group into the rate reply the contract publishes.
 *
 * <p>Purpose: the single place the rate's representation is resolved. The column is an exact scaled
 * decimal and the wire carries a string, and this is where the value crosses between them -- through
 * the shared money type, so that the crossing happens once in the whole system rather than per
 * endpoint.</p>
 *
 * <p>Assumptions: no binary floating-point type appears on this path at any point, and the prohibition
 * is enforced by the shared architecture test rather than by review. The rate is an operand of the
 * accrual computation, so a representation error here would settle into money a customer is charged
 * while leaving the result plausible.</p>
 */
public final class DisclosureGroupMapper {

    /**
     * Prevents instantiation of a type whose whole content is static.
     *
     * @throws AssertionError always, so a reflective instantiation fails loudly rather than yielding a
     *     useless instance
     */
    private DisclosureGroupMapper() {
        throw new AssertionError("DisclosureGroupMapper is not instantiable");
    }

    /**
     * Renders a resolved rate, reporting both the group asked for and the group that supplied it.
     *
     * <p>Assumptions: the requested group is carried through from the caller rather than read back off
     * the entity, because on a fallback the two differ and it is precisely that difference the reply
     * exists to report. Reading both from the entity would make a fallback indistinguishable from a
     * direct hit, and an account accruing at the default rate because its own group is missing from the
     * seed would then have no symptom at all.</p>
     *
     * @param requestedAcctGroupId the account group the caller asked for, exactly as received
     * @param entity the row that supplied the rate, which on a fallback belongs to the default group;
     *     must not be {@code null}
     * @param defaultGroupApplied {@code true} when the row came from the default group
     * @return the rate reply, never {@code null}
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    public static DisclosureGroupRateResponse toResponse(
            String requestedAcctGroupId, DisclosureGroup entity, boolean defaultGroupApplied) {
        return new DisclosureGroupRateResponse(
                requestedAcctGroupId,
                entity.getAcctGroupId(),
                TransactionTypeMapper.trimTrailing(entity.getTranTypeCd()),
                entity.getTranCatCd(),
                Money.of(entity.getInterestRate()),
                defaultGroupApplied);
    }
}
