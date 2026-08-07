package com.carddemo.batch.service;

import com.carddemo.batch.domain.DisclosureGroup;
import com.carddemo.batch.dto.DisclosureGroupKey;
import com.carddemo.batch.dto.InterestRateLookup;
import com.carddemo.batch.repository.DisclosureGroupRepository;
import com.carddemo.common.money.Money;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Accrues monthly interest, transcribed from {@code app/cbl/CBACT04C.cbl}.
 *
 * <p>Purpose: two rules carry this program and both are easy to get subtly wrong. The rate lookup falls
 * back to the group named {@code DEFAULT} when the account's own group is not found, at {@code :415-441},
 * where the reference recognises absence as file status 23 at {@code :428} and re-reads with the
 * account-group component replaced and the transaction type and category carried through unchanged. The
 * accrual itself is {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} at
 * {@code :462-468}.</p>
 *
 * <p>Assumptions: the formula multiplies at full precision and only then divides, which is what
 * transformation rule T4 requires. Dividing first and multiplying second yields a different number of
 * cents on many inputs because the intermediate loses precision the product would have kept, and the
 * golden masters would report it as a parity failure after the fact rather than at the call site. The
 * shared money type performs it in that order and takes the rounding mode explicitly, so no default can
 * be inherited silently.</p>
 *
 * <p>Assumptions: a rate of zero and an absent group are different outcomes and stay different. A group
 * carrying a genuine zero rate accrues nothing because the arithmetic says so; a group that is not found
 * accrues at whatever the {@code DEFAULT} group carries. {@code InterestRateLookup} records which of the
 * two keys answered, so a caller can tell the two apart without repeating the lookup.</p>
 */
@Service
public class InterestCalculationService {

    /**
     * The account-group component the reference substitutes when a group is not found, unpadded.
     *
     * <p>Assumptions: the literal is held at its source width, seven characters, exactly as
     * {@code app/cbl/CBACT04C.cbl:434} writes it into a field of ten. The padding to the declared width
     * is applied by {@link DisclosureGroupKey#ofBlankPaddedAccountGroupId(String, String, int)} rather
     * than written into this constant, so the one type that owns the width owns the padding too and this
     * constant stays comparable with the reference literal character for character.</p>
     */
    public static final String DEFAULT_ACCOUNT_GROUP = "DEFAULT";

    /**
     * The rounding mode the reference's own arithmetic applies, named rather than defaulted.
     *
     * <p>Assumptions: this constant is DOCUMENTATION and not a parameter. The accrual rounds through
     * {@code Money.monthlyInterest}, which fixes the mode at {@code Money.GENERAL_ROUNDING} for every
     * money value in the system, so naming it here records the reference's mode where a reader of this
     * service looks for it without giving this service the ability to apply a different one. The two are
     * asserted equal by this service's own tests, so a divergence between the name and the behaviour
     * fails the build rather than misleading a reader.</p>
     */
    public static final RoundingMode ACCRUAL_ROUNDING = RoundingMode.HALF_UP;

    /** The read-only rates this service looks up. */
    private final DisclosureGroupRepository disclosureGroups;

    /**
     * Builds the service over its repository.
     *
     * @param disclosureGroups the read-only repository over the rate table; must not be {@code null}
     * @throws NullPointerException if {@code disclosureGroups} is {@code null}
     */
    public InterestCalculationService(DisclosureGroupRepository disclosureGroups) {
        this.disclosureGroups = Objects.requireNonNull(disclosureGroups,
                "disclosureGroups must not be null");
    }

    /**
     * Resolves the rate for one disclosure-group key, falling back to the {@code DEFAULT} group.
     *
     * @param requested the key the account's own group, type and category form; must not be {@code null}
     * @return the resolved lookup, recording whether the fallback was applied, or an empty optional when
     *     neither the requested key nor its {@code DEFAULT} derivation resolves
     * @throws NullPointerException if {@code requested} is {@code null}
     */
    public Optional<InterestRateLookup> resolveRate(DisclosureGroupKey requested) {
        Objects.requireNonNull(requested, "requested must not be null");

        Optional<DisclosureGroup> direct = this.disclosureGroups.findByIdIs(
                new DisclosureGroup.DisclosureGroupId(requested.accountGroupId(),
                        requested.transactionTypeCode(), requested.transactionCategoryCodeField()));
        if (direct.isPresent()) {
            return Optional.of(
                    InterestRateLookup.ofDirectHit(requested, direct.get().getInterestRate()));
        }

        // WHY : Assumptions: only the ACCOUNT-GROUP component is replaced. The reference moves the
        //       literal into that one field at :434 and leaves the type and category as they were, so a
        //       fallback that rebuilt the whole key from defaults would look up a different rate
        //       entirely and would silently accrue at it.
        DisclosureGroupKey fallbackKey = DisclosureGroupKey.ofBlankPaddedAccountGroupId(
                DEFAULT_ACCOUNT_GROUP, requested.transactionTypeCode(),
                requested.transactionCategoryCode());
        return this.disclosureGroups
                .findByIdIs(new DisclosureGroup.DisclosureGroupId(fallbackKey.accountGroupId(),
                        fallbackKey.transactionTypeCode(),
                        fallbackKey.transactionCategoryCodeField()))
                .map(group -> InterestRateLookup.ofDefaultGroupFallback(requested,
                        group.getInterestRate()));
    }

    /**
     * Accrues one month of interest on one category balance at a resolved rate.
     *
     * @param categoryBalance the balance to accrue on; must not be {@code null}
     * @param lookup the resolved rate and the key that answered; must not be {@code null}
     * @return the accrued interest, exact at scale two, never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public Money accrue(Money categoryBalance, InterestRateLookup lookup) {
        Objects.requireNonNull(categoryBalance, "categoryBalance must not be null");
        Objects.requireNonNull(lookup, "lookup must not be null");

        // WHY : Refactoring Rationale: the rounding mode is NOT passed at this call site, and an earlier
        //       revision passed it so that the mode this service applies was visible here. The shared
        //       helper fixes the mode instead, at Money.GENERAL_ROUNDING, and a per-caller mode is the
        //       thing that must not exist: transformation rule T3 makes half-up at scale two a property
        //       of every money value in the system, so a signature admitting a mode would admit a caller
        //       that rounded a payment differently from the balance it was applied to, and no test of
        //       either would fail. ACCRUAL_ROUNDING is retained beside this method and asserted equal to
        //       the shared constant, which keeps the reference's own mode named where a reader of this
        //       service looks for it while leaving exactly one place that can change it.
        // WHY : Assumptions: the helper multiplies before dividing, which is the order transformation
        //       rule T4 requires and the one the reference's single COMPUTE statement performs.
        return categoryBalance.monthlyInterest(lookup.resolvedRate());
    }
}
