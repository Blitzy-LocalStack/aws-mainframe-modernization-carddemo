package com.carddemo.reference.service;

import com.carddemo.reference.domain.DisclosureGroup;
import com.carddemo.reference.domain.DisclosureGroup.DisclosureGroupId;
import com.carddemo.reference.dto.DisclosureGroupRateResponse;
import com.carddemo.reference.mapper.DisclosureGroupMapper;
import com.carddemo.reference.repository.DisclosureGroupRepository;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The disclosure-rate lookup, transcribed from the baseline interest program.
 *
 * <p>Purpose: resolves the annual rate that applies to one account group, transaction type and
 * category, substituting the default group when the group asked for has no row. Its consumers are
 * {@code com.carddemo.reference.api} and, across the service boundary over HTTP, the interest
 * accrual.</p>
 *
 * <p>Assumptions: the fallback is a substitution and not a refusal. The baseline reads the rate and,
 * when the read misses, moves the literal default group into the key field and reads again, so an
 * account whose own group is absent still accrues. Answering a refusal instead would stop accrual for
 * an account the baseline accrues for, which is a behavioural change rather than a structural one.</p>
 *
 * <p>Assumptions: the fallback is NOT total, so a terminal miss exists and is handled rather than
 * assumed away. At least one type-and-category pair has no row in any group, the default group
 * included; treating the fallback as exhaustive would leave that case with an absent rate and would
 * accrue nothing while reporting success.</p>
 */
@Service
public class DisclosureGroupService {

    /**
     * The account group substituted when the group asked for has no row.
     *
     * <p>Assumptions: the literal is blank-padded to the declared ten characters HERE rather than
     * relying on the column's comparison semantics. The baseline moves a seven-character literal into a
     * ten-byte alphanumeric field, which left-justifies and space-fills it, so the key it actually reads
     * with carries three trailing spaces -- and that is how the seed stores those rows. The identity type
     * refuses a value that is not exactly ten characters, so the padding is not optional here.</p>
     */
    public static final String DEFAULT_ACCOUNT_GROUP = "DEFAULT   ";

    /** The refusal when neither the group asked for nor the default group has a row. */
    public static final String MESSAGE_RATE_NOT_FOUND = "Disclosure group rate NOT found...";

    /** Read access to the disclosure-group table. */
    private final DisclosureGroupRepository groups;

    /**
     * Builds the service over the repository it reads.
     *
     * @param groups read access to the disclosure-group table; must not be {@code null}
     */
    public DisclosureGroupService(DisclosureGroupRepository groups) {
        this.groups = groups;
    }

    /**
     * Resolves the rate for one three-part key, falling back to the default group on a miss.
     *
     * <p>Assumptions: only the account-group component is replaced by the fallback. The type and the
     * category are carried through unchanged, because the baseline moves the literal into the group
     * field alone and leaves the other two as they were -- a fallback that also generalised the type or
     * the category would return the rate of a different product.</p>
     *
     * <p>Assumptions: the group asked for is never trimmed on either side of the comparison. A trimmed
     * probe would search for a key no row carries, and because a miss falls back rather than failing,
     * the symptom would be silent accrual at the default rate instead of an error.</p>
     *
     * @param acctGroupId the account group asked for, blank-padded to its declared ten characters
     * @param tranTypeCd the two-character transaction type
     * @param tranCatCd the four-digit transaction category with its leading zeros intact
     * @return the rate, reporting which group supplied it
     * @throws NoSuchElementException when neither the group asked for nor the default group has a row
     *     for that type and category, which is a real terminal condition rather than an impossible one
     * @throws IllegalArgumentException if a component is not exactly its declared width
     */
    @Transactional(readOnly = true)
    public DisclosureGroupRateResponse resolveRate(
            String acctGroupId, String tranTypeCd, String tranCatCd) {

        Optional<DisclosureGroup> direct = this.groups.findByIdIs(
                new DisclosureGroupId(acctGroupId, tranTypeCd, tranCatCd));
        if (direct.isPresent()) {
            return DisclosureGroupMapper.toResponse(acctGroupId, direct.get(), false);
        }

        // WHY : Assumptions: the second read replaces the group component ALONE. This is the whole of
        //       the baseline's fallback, and the reason it is a second read rather than a widened
        //       predicate is that the two reads must be distinguishable in the reply -- a caller has to
        //       be able to tell a direct hit from a substitution, or a group missing from the seed has
        //       no symptom at all.
        Optional<DisclosureGroup> fallback = this.groups.findByIdIs(
                new DisclosureGroupId(DEFAULT_ACCOUNT_GROUP, tranTypeCd, tranCatCd));
        if (fallback.isPresent()) {
            return DisclosureGroupMapper.toResponse(acctGroupId, fallback.get(), true);
        }
        throw new NoSuchElementException(MESSAGE_RATE_NOT_FOUND);
    }
}
