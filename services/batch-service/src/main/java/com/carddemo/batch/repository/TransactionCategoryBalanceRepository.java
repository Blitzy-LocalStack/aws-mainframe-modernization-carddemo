package com.carddemo.batch.repository;

import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and writes the running per-category balances the posting job maintains.
 *
 * <p>The table behind this interface is {@code ledger.transaction_category_balances}, which the
 * transaction context owns; this module reaches it through the narrowly scoped cross-schema grant that
 * keeps the three posting writes one atomic commit. The interface exists because
 * {@code 2700-UPDATE-TCATBAL} of {@code app/cbl/CBTRN02C.cbl} performs a keyed read at {@code :474} and
 * then takes one of two arms -- an insert at {@code :500} or an in-place add at {@code :526} -- and both
 * arms have to stay separately observable.</p>
 *
 * <p>Assumptions: no upsert helper is declared and none is to be added. The distinction between the two
 * arms is observable behaviour that the golden masters compare, and a single conflict-resolving statement
 * decides it inside the database where no caller can see which arm ran. The keyed read below is what lets
 * the service decide, and the inherited {@code save} performs whichever arm the decision selected.</p>
 *
 * <p>Assumptions: this interface declares no delete. The reference never removes a category balance --
 * the cluster is refreshed wholesale by its own load job rather than pruned -- so a delete method here
 * would offer a capability the migrated behaviour has no use for over a table this module does not own.</p>
 */
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId> {

    /**
     * Walks the table in composite-key order, which is the order the reference's own cluster is in.
     *
     * <p>Assumptions: the walk is bounded by an explicit limit rather than returning everything, because
     * a batch step reads this table in chunks and an unbounded read would materialise the whole relation
     * into one heap. The bound is the caller's, so a step that genuinely wants the whole table asks for
     * it in as many bounded reads as it takes.</p>
     *
     * @param limit the greatest number of rows to return; must not be {@code null}
     * @return the rows in ascending composite-key order, never {@code null}
     */
    List<TransactionCategoryBalance>
            findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc(Limit limit);

    /**
     * Continues the composite-key walk after a given account, which is the control break the interest
     * job takes.
     *
     * @param accountId the account the previous chunk ended on; must not be {@code null}
     * @param limit the greatest number of rows to return; must not be {@code null}
     * @return the rows for accounts strictly after that one, in ascending composite-key order, never
     *     {@code null}
     */
    List<TransactionCategoryBalance>
            findByIdAccountIdGreaterThanOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc(
                    Long accountId, Limit limit);

    /**
     * Reads every category balance of one account, which is the unit the interest job accrues over.
     *
     * @param accountId the account to read; must not be {@code null}
     * @return that account's rows in ascending type and category order, never {@code null}
     */
    List<TransactionCategoryBalance> findByIdAccountIdOrderByIdTypeCdAscIdCategoryCdAsc(Long accountId);

    /**
     * Reads one running balance by its whole composite key.
     *
     * <p>Assumptions: this is declared even though {@code findById} is inherited with the same meaning,
     * because the arm-selecting read is the single most important statement in the posting job and a
     * named method is what the traceability citation for {@code :474} points at.</p>
     *
     * @param id the whole three-part key; must not be {@code null}
     * @return the row when one exists, otherwise an empty optional
     */
    Optional<TransactionCategoryBalance> findByIdIs(TransactionCategoryBalanceId id);
}
