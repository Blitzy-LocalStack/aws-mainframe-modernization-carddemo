package com.carddemo.reference.repository;

import com.carddemo.reference.domain.TransactionType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Access to {@code reference.transaction_types}, the table the transaction-type screens maintain.
 *
 * <p>Purpose: the migrated form of the baseline's keyed read and cursor browse over the
 * transaction-type table. The keyed finder replaces the read; the three walks replace the
 * start-browse, read-next and read-previous sequence.</p>
 *
 * <p>Assumptions: the identity is the two-character type code, so the inherited keyed operations take a
 * {@code String}. It is character data rather than numeric because a leading zero is part of the code,
 * and a key rendered as {@code 1} where the row stores {@code 01} matches no row.</p>
 */
@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {

    /**
     * Reads the first page of types in code order.
     *
     * @param limit the bound the caller sets, one greater than the window it intends to publish so
     *     that a further-page flag can be established without a count query
     * @return the bounded rows in ascending code order, empty when the table is empty
     */
    List<TransactionType> findAllByOrderByTypeCdAsc(Limit limit);

    /**
     * Reads the page strictly after a position, in code order.
     *
     * @param lastKey the code the previous page ended at; rows equal to it are excluded, so a page
     *     cannot repeat the row a caller has already seen
     * @param limit the bound the caller sets
     * @return the bounded rows in ascending code order
     */
    List<TransactionType> findByTypeCdGreaterThanOrderByTypeCdAsc(String lastKey, Limit limit);

    /**
     * Reads the page strictly before a position, in descending code order.
     *
     * <p>Assumptions: descending order is what makes this a page rather than a scan -- the rows nearest
     * the position are the ones wanted, and a bound applied to an ascending walk would return the rows
     * furthest from it. The caller reverses the result before publishing it.</p>
     *
     * @param firstKey the code the current page begins at; rows equal to it are excluded
     * @param limit the bound the caller sets
     * @return the bounded rows in descending code order
     */
    List<TransactionType> findByTypeCdLessThanOrderByTypeCdDesc(String firstKey, Limit limit);

    /**
     * Reads one type by its exact code.
     *
     * @param typeCd the two-character code to read
     * @return the row, or empty when no row carries that code
     */
    Optional<TransactionType> findByTypeCd(String typeCd);
}
