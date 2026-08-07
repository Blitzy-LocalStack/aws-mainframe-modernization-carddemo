package com.carddemo.reference.repository;

import com.carddemo.reference.domain.DisclosureGroup;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Read access to {@code reference.disclosure_groups}, the interest-rate lookup.
 *
 * <p>Purpose: the migrated form of the single keyed read the interest accrual performs once per balance
 * row. One finder and nothing else, because that is the whole access pattern the baseline has for this
 * table.</p>
 *
 * <p>Assumptions: no browse is offered, and its absence is deliberate rather than unfinished. The
 * published contract exposes the rate as a read of one three-part key and declares no list operation
 * over this table, so a walk here would serve no endpoint. The baseline agrees: its program resolves a
 * single rate per row rather than enumerating rates.</p>
 *
 * <p>Assumptions: no write operation is exposed either, beyond what the base interface inherits. This
 * table is seeded by the migration that ships beside it and no migrated operation replaces a row in it,
 * which is also why the entity carries no optimistic-lock counter.</p>
 *
 * <p>Assumptions: the group half of the key is never trimmed on either side of the comparison. The
 * baseline's fallback moves a seven-character literal into a ten-byte field, so the key it actually
 * reads with carries three trailing spaces, and the seed stores those rows that way. A trimmed probe
 * would search for a key no row carries and the fallback would find nothing.</p>
 */
@Repository
public interface DisclosureGroupRepository
        extends JpaRepository<DisclosureGroup, DisclosureGroup.DisclosureGroupId> {

    /**
     * Reads one disclosure group by its whole three-part key.
     *
     * @param id the composite identity to read, with the account group blank-padded to its declared
     *     ten characters
     * @return the row, or empty when no row carries that identity, which is the condition that drives
     *     the caller's fallback to the default group rather than a refusal
     */
    Optional<DisclosureGroup> findByIdIs(DisclosureGroup.DisclosureGroupId id);
}
