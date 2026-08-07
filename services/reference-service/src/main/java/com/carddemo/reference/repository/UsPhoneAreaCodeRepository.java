package com.carddemo.reference.repository;

import com.carddemo.reference.domain.UsPhoneAreaCode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Access to {@code reference.us_phone_area_codes}, one of the three seeded address allow-lists.
 *
 * <p>Purpose: the migrated form of the baseline's membership test. Where the baseline compares a
 * candidate value against a list of literals held as a condition name, this repository answers whether
 * a row exists -- and offers the bounded walks the published browse needs.</p>
 *
 * <p>Assumptions: the classification filter is a separate pair of walks rather than a nullable
 * parameter on the unfiltered ones. A derived query cannot express "equal to the parameter, or
 * unfiltered when it is null" without a disjunction that would defeat the index on the key, so the two
 * cases are two methods and the caller chooses.</p>
 */
@Repository
public interface UsPhoneAreaCodeRepository extends JpaRepository<UsPhoneAreaCode, String> {

    /**
     * Reads the first page of area codes in code order.
     *
     * @param limit the bound the caller sets, one greater than the window it publishes
     * @return the bounded rows in ascending code order
     */
    List<UsPhoneAreaCode> findAllByOrderByAreaCodeAsc(Limit limit);

    /**
     * Reads the page strictly after a position, in code order.
     *
     * @param lastKey the code the previous page ended at, excluded
     * @param limit the bound the caller sets
     * @return the bounded rows in ascending code order
     */
    List<UsPhoneAreaCode> findByAreaCodeGreaterThanOrderByAreaCodeAsc(String lastKey, Limit limit);

    /**
     * Reads the page strictly before a position, in descending code order.
     *
     * @param firstKey the code the current page begins at, excluded
     * @param limit the bound the caller sets
     * @return the bounded rows in descending code order; the caller reverses them
     */
    List<UsPhoneAreaCode> findByAreaCodeLessThanOrderByAreaCodeDesc(String firstKey, Limit limit);

    /**
     * Reads the first page of one classification.
     *
     * @param codeClass the classification to narrow to
     * @param limit the bound the caller sets
     * @return the bounded rows of that class in ascending code order
     */
    List<UsPhoneAreaCode> findByCodeClassOrderByAreaCodeAsc(String codeClass, Limit limit);

    /**
     * Reads the page of one classification strictly after a position.
     *
     * @param codeClass the classification to narrow to
     * @param lastKey the code the previous page ended at, excluded
     * @param limit the bound the caller sets
     * @return the bounded rows of that class in ascending code order
     */
    List<UsPhoneAreaCode> findByCodeClassAndAreaCodeGreaterThanOrderByAreaCodeAsc(
            String codeClass, String lastKey, Limit limit);

    /**
     * Reads the page of one classification strictly before a position, descending.
     *
     * @param codeClass the classification to narrow to
     * @param firstKey the code the current page begins at, excluded
     * @param limit the bound the caller sets
     * @return the bounded rows of that class in descending code order
     */
    List<UsPhoneAreaCode> findByCodeClassAndAreaCodeLessThanOrderByAreaCodeDesc(
            String codeClass, String firstKey, Limit limit);

    /**
     * Reads one area code by its exact value.
     *
     * @param areaCode the three-digit code to read
     * @return the row, or empty when the code is not in the seeded allow-list
     */
    Optional<UsPhoneAreaCode> findByAreaCode(String areaCode);
}
