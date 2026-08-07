package com.carddemo.reference.repository;

import com.carddemo.reference.domain.UsStateZipPrefix;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Access to {@code reference.us_state_zip_prefixes}, the seeded state-and-postal-prefix allow-list.
 *
 * <p>Purpose: the migrated form of a membership test over the 240 four-character literals the
 * baseline's condition name carries, together with the bounded walks the published browse needs.</p>
 *
 * <p>Assumptions: the key is the concatenated four characters and is never split. The baseline builds
 * the four bytes and tests the pair as a unit, so a query over one half alone would answer a question
 * the baseline never asks -- and a prefix whose leading postal digit is zero would be lost the moment
 * that half were treated as a number.</p>
 */
@Repository
public interface UsStateZipPrefixRepository extends JpaRepository<UsStateZipPrefix, String> {

    /**
     * Reads the first page of prefixes in key order.
     *
     * @param limit the bound the caller sets, one greater than the window it publishes
     * @return the bounded rows in ascending key order
     */
    List<UsStateZipPrefix> findAllByOrderByStateZipCdAsc(Limit limit);

    /**
     * Reads the page strictly after a position, in key order.
     *
     * @param lastKey the key the previous page ended at, excluded
     * @param limit the bound the caller sets
     * @return the bounded rows in ascending key order
     */
    List<UsStateZipPrefix> findByStateZipCdGreaterThanOrderByStateZipCdAsc(
            String lastKey, Limit limit);

    /**
     * Reads the page strictly before a position, in descending key order.
     *
     * @param firstKey the key the current page begins at, excluded
     * @param limit the bound the caller sets
     * @return the bounded rows in descending key order; the caller reverses them
     */
    List<UsStateZipPrefix> findByStateZipCdLessThanOrderByStateZipCdDesc(
            String firstKey, Limit limit);

    /**
     * Reads one prefix by its exact four-character key.
     *
     * @param stateZipCd the state code followed by the two leading postal digits
     * @return the row, or empty when the pair is not in the seeded allow-list
     */
    Optional<UsStateZipPrefix> findByStateZipCd(String stateZipCd);
}
