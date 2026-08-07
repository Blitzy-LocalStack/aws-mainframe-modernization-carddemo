package com.carddemo.batch.repository;

import com.carddemo.batch.domain.DisclosureGroup;
import com.carddemo.batch.domain.DisclosureGroup.DisclosureGroupId;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads the interest rates the accrual job looks its disclosure groups up in.
 *
 * <p>The table behind this interface is {@code reference.disclosure_groups}, which the reference context
 * owns. This module holds no write privilege on that schema at all, which is why every method here is a
 * read and why no write method is to be added: the restriction is a grant rather than a convention, so a
 * write method would compile, deploy and then fail at run time with a privilege error.</p>
 *
 * <p>Assumptions: the fallback to the group named {@code DEFAULT} is NOT expressed here. The reference
 * performs it in {@code app/cbl/CBACT04C.cbl:415-441} by re-reading with the account-group component
 * replaced and the transaction type and category carried through unchanged, which is a second read
 * decided by the outcome of the first -- a rule, not a query. It therefore belongs to the accruing
 * service, which is where {@code InterestRateLookup} records which of the two keys answered.</p>
 */
public interface DisclosureGroupRepository extends JpaRepository<DisclosureGroup, DisclosureGroupId> {

    /**
     * Reads one disclosure group by its whole three-part key.
     *
     * @param id the account group, transaction type and transaction category together; must not be
     *     {@code null}
     * @return the group when one exists, otherwise an empty optional -- which is the relational
     *     equivalent of the reference's file status 23 at {@code app/cbl/CBACT04C.cbl:428}
     */
    Optional<DisclosureGroup> findByIdIs(DisclosureGroupId id);
}
