package com.carddemo.reference.repository;

import com.carddemo.reference.domain.TransactionCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Access to {@code reference.transaction_categories}, the child table of the transaction types.
 *
 * <p>Purpose: the migrated form of the keyed read and cursor browse over the category table. The
 * composite key means a browse orders on two columns rather than one, and a position therefore names
 * both halves.</p>
 *
 * <p>Assumptions: the walks are expressed as JPQL rather than as derived method names because a
 * two-column keyset predicate is a row comparison -- a row belongs to the page after a position when
 * its type is greater, or its type is equal and its category is greater. A derived name can express
 * neither the disjunction nor the grouping, and the near miss that a derived name WOULD express, a
 * conjunction of two greater-thans, silently omits every row whose type advanced while its category
 * fell.</p>
 *
 * <p>Assumptions: this repository also answers the existence question the parent delete depends on.
 * The migration declares the foreign key {@code ON DELETE RESTRICT}, so the database is what refuses a
 * restricted delete; the count here lets the parent service report that refusal as the migrated
 * message rather than letting a constraint violation surface as an unhandled failure.</p>
 */
@Repository
public interface TransactionCategoryRepository
        extends JpaRepository<TransactionCategory, TransactionCategory.TransactionCategoryId> {

    /**
     * Reads the first page of categories in composite-key order.
     *
     * @param limit the bound the caller sets, one greater than the window it publishes
     * @return the bounded rows ordered by type then category, ascending
     */
    @Query(
            "select c from TransactionCategory c order by c.id.typeCd asc, c.id.catCd asc")
    List<TransactionCategory> findFirstPage(Limit limit);

    /**
     * Reads the page strictly after a two-part position.
     *
     * @param typeCd the type half of the position
     * @param catCd the category half of the position
     * @param limit the bound the caller sets
     * @return the bounded rows ordered by type then category, ascending
     */
    @Query("""
            select c from TransactionCategory c
            where c.id.typeCd > :typeCd
               or (c.id.typeCd = :typeCd and c.id.catCd > :catCd)
            order by c.id.typeCd asc, c.id.catCd asc""")
    List<TransactionCategory> findPageAfter(
            @Param("typeCd") String typeCd, @Param("catCd") String catCd, Limit limit);

    /**
     * Reads the page strictly before a two-part position, in descending order.
     *
     * @param typeCd the type half of the position
     * @param catCd the category half of the position
     * @param limit the bound the caller sets
     * @return the bounded rows ordered by type then category, descending; the caller reverses them
     */
    @Query("""
            select c from TransactionCategory c
            where c.id.typeCd < :typeCd
               or (c.id.typeCd = :typeCd and c.id.catCd < :catCd)
            order by c.id.typeCd desc, c.id.catCd desc""")
    List<TransactionCategory> findPageBefore(
            @Param("typeCd") String typeCd, @Param("catCd") String catCd, Limit limit);

    /**
     * Reads the first page of the categories of one type.
     *
     * @param typeCd the type to narrow to
     * @param limit the bound the caller sets
     * @return the bounded rows of that type in ascending category order
     */
    @Query(
            "select c from TransactionCategory c where c.id.typeCd = :typeCd order by c.id.catCd asc")
    List<TransactionCategory> findFirstPageOfType(@Param("typeCd") String typeCd, Limit limit);

    /**
     * Reads the page of one type strictly after a category position.
     *
     * @param typeCd the type to narrow to
     * @param catCd the category the previous page ended at, excluded
     * @param limit the bound the caller sets
     * @return the bounded rows of that type in ascending category order
     */
    @Query("""
            select c from TransactionCategory c
            where c.id.typeCd = :typeCd and c.id.catCd > :catCd
            order by c.id.catCd asc""")
    List<TransactionCategory> findPageOfTypeAfter(
            @Param("typeCd") String typeCd, @Param("catCd") String catCd, Limit limit);

    /**
     * Reads the page of one type strictly before a category position, descending.
     *
     * @param typeCd the type to narrow to
     * @param catCd the category the current page begins at, excluded
     * @param limit the bound the caller sets
     * @return the bounded rows of that type in descending category order
     */
    @Query("""
            select c from TransactionCategory c
            where c.id.typeCd = :typeCd and c.id.catCd < :catCd
            order by c.id.catCd desc""")
    List<TransactionCategory> findPageOfTypeBefore(
            @Param("typeCd") String typeCd, @Param("catCd") String catCd, Limit limit);

    /**
     * Counts the categories that reference one transaction type.
     *
     * <p>Assumptions: this exists so a parent delete can report the migrated refusal rather than
     * letting the database's referential refusal escape as an unhandled failure. It does not replace
     * that constraint: two callers deleting and inserting concurrently could each see a count of zero,
     * so the constraint remains the authority and this count is the diagnosis.</p>
     *
     * @param typeCd the type whose children are counted
     * @return the number of categories referencing that type, zero when none do
     */
    @Query(
            "select count(c) from TransactionCategory c where c.id.typeCd = :typeCd")
    long countByTypeCd(@Param("typeCd") String typeCd);

    /**
     * Reads one category by both halves of its key.
     *
     * @param id the composite identity to read
     * @return the row, or empty when no row carries that identity
     */
    Optional<TransactionCategory> findByIdIs(TransactionCategory.TransactionCategoryId id);
}
