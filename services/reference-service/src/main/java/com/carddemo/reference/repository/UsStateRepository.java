package com.carddemo.reference.repository;

import com.carddemo.reference.domain.UsState;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Access to {@code reference.us_states}, the seeded state allow-list.
 *
 * <p>Purpose: the migrated form of a membership test over the 56 literals the baseline's condition name
 * carries, together with the bounded walks the published browse needs.</p>
 *
 * <p>Assumptions: the whole datum is the code, so there is nothing to filter on and no filtered walk is
 * offered. A read that misses is the migrated form of a state code failing validation, and the caller
 * that cares about it is another service's address check reached over this service's HTTP contract.</p>
 */
@Repository
public interface UsStateRepository extends JpaRepository<UsState, String> {

    /**
     * Reads the first page of state codes in code order.
     *
     * @param limit the bound the caller sets, one greater than the window it publishes
     * @return the bounded rows in ascending code order
     */
    List<UsState> findAllByOrderByStateCodeAsc(Limit limit);

    /**
     * Reads the page strictly after a position, in code order.
     *
     * @param lastKey the code the previous page ended at, excluded
     * @param limit the bound the caller sets
     * @return the bounded rows in ascending code order
     */
    List<UsState> findByStateCodeGreaterThanOrderByStateCodeAsc(String lastKey, Limit limit);

    /**
     * Reads the page strictly before a position, in descending code order.
     *
     * @param firstKey the code the current page begins at, excluded
     * @param limit the bound the caller sets
     * @return the bounded rows in descending code order; the caller reverses them
     */
    List<UsState> findByStateCodeLessThanOrderByStateCodeDesc(String firstKey, Limit limit);

    /**
     * Reads one state by its exact code.
     *
     * @param stateCode the two-character code to read
     * @return the row, or empty when the code is not in the seeded allow-list
     */
    Optional<UsState> findByStateCode(String stateCode);
}
