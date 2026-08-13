package com.carddemo.reporting.repository;

import com.carddemo.reporting.domain.TransactionCategoryBalanceView;
import com.carddemo.reporting.domain.TransactionCategoryBalanceView.TransactionCategoryBalanceKey;
import jakarta.persistence.QueryHint;
import java.util.stream.Stream;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads transaction-category balances in the order the category-balance report prints them.
 *
 * <p>Purpose: supply {@code app/jcl/PRTCATBL.jcl} with its input. That job's {@code STEP10R} declares
 * {@code SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)} at line 52, and this is the
 * one method that expresses it. There is no second way into the relation from this context.</p>
 *
 * <p>Assumptions: the interface extends the NARROW {@link Repository} marker rather than
 * {@code JpaRepository}, so this context gains exactly the one read below and none of the save,
 * delete, flush or count operations a full repository would publish. That is not defensive style: the
 * relation is a view, the login role holds {@code SELECT} only, and a published {@code save} would
 * compile cleanly and fail at run time on a permission -- the narrow marker moves that failure to
 * compile time and states the read-only remit in the type.</p>
 *
 * <p>Assumptions: the ordering is expressed as a derived query over the embedded key's own members
 * rather than as a {@code @Query} string, so the three sort columns and their sequence are read from
 * the key type instead of being spelled again. The method name is long as a consequence, and that is
 * the trade accepted: a renamed key member breaks the build here, whereas a string would keep
 * compiling and would order by whatever the old name still resolved to.</p>
 *
 * <p>Documentation convention: {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 *
 * <p>Baseline lineage: citations are provenance only. Nothing under {@code app/} is read at run time
 * and nothing under it is altered by this migration.</p>
 */
public interface CategoryBalanceReportRepository
        extends Repository<TransactionCategoryBalanceView, TransactionCategoryBalanceKey> {

    /**
     * Walks every transaction-category balance in account, type then category order.
     *
     * <p>Assumptions: the walk is UNBOUNDED, matching the reference. {@code app/jcl/PRTCATBL.jcl}
     * feeds its sort the whole unloaded file at lines 44-45 with no {@code INCLUDE} and no date
     * parameter, so there is no selection to reproduce and a bounded variant would print a subset the
     * reference prints in full. Laziness is what makes that safe: the row count becomes a number of
     * round trips rather than a heap requirement.</p>
     *
     * <p>Trade-offs: a {@code Stream} return rather than a {@code List}. A list would materialise
     * every category of every account before the first line was rendered, and the report writes
     * sequentially, so nothing is gained by holding them. The cost is a heavier call-site contract --
     * the caller must hold the transaction open for the whole walk and must close the stream in a
     * try-with-resources block -- which is the same contract the sibling report walk imposes.</p>
     *
     * <p>Alternatives Considered: the default {@code REQUIRED} propagation. Rejected because it is
     * actively misleading on a method returning a cursor: it would start a transaction when no caller
     * had one, commit it as this method returned, and hand back a stream whose cursor was already
     * closed -- so the failure would surface at the first element and name neither this method nor
     * the missing transaction. {@code MANDATORY} refuses the call and names the mistake at the call
     * site.</p>
     *
     * <p>This operation accepts no parameters.</p>
     *
     * @return a lazily-populated {@code Stream<TransactionCategoryBalanceView>} in ascending account
     *     identifier, then ascending type code, then ascending category code, empty when the relation
     *     holds no row. The caller must consume it inside its own transaction and must close it
     * @throws org.springframework.transaction.IllegalTransactionStateException if the caller holds no
     *     transaction, because a cursor cannot outlive a transaction that was never started
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    // Assumptions: the fetch-size hint is what makes this stream actually stream. A server-side
    // cursor opens only when a positive fetch size and a non-auto-commit connection both hold; with
    // either missing the driver buffers the whole result client-side. Only POSITIVITY carries that
    // property, so the streaming contract rests on the value being above zero rather than on which
    // value it is, and the constant is aligned with the module's base session default so the default
    // deployment behaves identically whichever governs.
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "100"))
    Stream<TransactionCategoryBalanceView>
            findAllByOrderByKeyAccountIdAscKeyTypeCodeAscKeyCategoryCodeAsc();
}
