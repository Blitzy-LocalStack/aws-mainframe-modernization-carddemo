package com.carddemo.batch.repository;

import com.carddemo.batch.domain.Customer;
import jakarta.persistence.QueryHint;
import java.util.stream.Stream;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Walks the customer master in key order for the branch-migration export, and does nothing else.
 *
 * <p>The table behind this interface is {@code account.customers}, and the account context OWNS it.
 * One migrated program reads it: {@code app/cbl/CBEXPORT.cbl} declares the customer file
 * {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS SEQUENTIAL}, opens it
 * {@code OPEN INPUT} among the five inputs at {@code app/cbl/CBEXPORT.cbl:200-233}, and reads it to
 * end of file at {@code app/cbl/CBEXPORT.cbl:260}, writing one {@code 'C'} record per row. That one
 * ordered walk is the whole of what this interface has to offer.</p>
 *
 * <h2>Refactoring Rationale: why this interface exists now, having deliberately not existed</h2>
 *
 * <p>The charter beside this file recorded the interface roster as closed, and the domain charter
 * recorded that no customer mapping was missing. Both statements were reasoned from
 * {@code app/cbl/CBTRN01C.cbl}, which opens the customer file and never reads a record from it, and
 * both were sound for that program. Neither extended to {@code app/cbl/CBEXPORT.cbl}, which reads all
 * five masters -- so the export emitted three of its five record types and warned about the shortfall
 * on every run. This interface is the seam that closes it. No grant work was needed:
 * {@code data-migration/sql/V0__schemas_and_roles.sql:1140} grants the batch role usage on the
 * {@code account} schema and {@code :1215} grants it {@code SELECT} on every table in that schema,
 * which includes this one.</p>
 *
 * <h2>Why the base type is narrowed rather than inherited</h2>
 *
 * <p>Alternatives Considered: {@code JpaRepository} and {@code CrudRepository} were both evaluated and
 * both rejected, because each inherits {@code save}, {@code saveAll}, {@code delete} and
 * {@code deleteAll}. <b>This module holds no write grant on the {@code account} schema's customer
 * table</b> -- its cross-schema write grants are scoped to the rows posting and accrual amend, and a
 * customer row is not among them -- and {@link Customer} carries Hibernate's immutability marker. An
 * inherited mutator would therefore compile cleanly, pass review, and fail AT THE DATABASE partway
 * through an operator-invoked export. {@code org.springframework.data.repository.Repository}
 * contributes no member of its own while still giving Spring Data enough to build a proxy, so the
 * reachable surface is exactly the one method declared below. Trade-offs: a convenience method must be
 * declared explicitly rather than inherited, which is a small amount of extra declaration in exchange
 * for making an unavailable capability unreachable. This is the same ruling
 * {@link DisclosureGroupRepository} records for the same reason.</p>
 *
 * <h2>Assumptions: the two protected columns are unreachable from here</h2>
 *
 * <p>{@code account.customers.ssn_encrypted} and {@code account.customers.govt_issued_id_encrypted}
 * hold envelopes that only the account context can open, and {@link Customer} does not map either
 * one. A row returned by this interface therefore cannot carry a national or government-issued
 * identifier in any form, enciphered or otherwise. That is a structural property of the mapping rather
 * than a promise about callers, and it is what keeps this read from being a disclosure: the export
 * writes a redacted value into those two spans, registered as divergence
 * {@code D-EXPORT-PROTECTED-SPANS-REDACTED} in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * @see Customer
 * @see com.carddemo.batch.job.ExportJob
 */
public interface CustomerRepository extends Repository<Customer, Long> {

    /**
     * Walks every customer row in ascending identifier order.
     *
     * <p>Assumptions: the ordering is declared on the method name rather than left to the query
     * planner. The reference reads an indexed file {@code ACCESS MODE IS SEQUENTIAL}, so its rows
     * arrive in record-key order; a relational read guarantees no order unless one is asked for, and
     * the export's sequence numbers are assigned in emit order -- so an unordered read would emit the
     * same set of records under different sequence numbers on two runs of the same data.</p>
     *
     * <p>Trade-offs: a stream rather than a list, matching the sibling account, cross-reference and
     * ledger interfaces. The customer master is unbounded in principle, so materialising it would hold
     * every row in the heap at once and make the export's memory profile a function of how long the
     * institution has been in business. The cost is a resource the caller has to release, which is why
     * the obligation is stated on the return tag below and why the export opens it in a
     * try-with-resources block; a stream left unclosed holds a cursor open for the rest of the
     * transaction.</p>
     *
     * @return a lazily-evaluated {@code Stream<Customer>} over every customer in ascending identifier
     *     order; never {@code null}, possibly empty, and the CALLER owns closing it
     * @throws org.springframework.dao.InvalidDataAccessApiUsageException if the stream is opened
     *     without a surrounding transaction, which {@link Propagation#MANDATORY} refuses outright
     *     rather than letting a cursor be opened that nothing keeps alive
     */
    // Assumptions: MANDATORY propagation is applied here where the package's single-row reads leave
    //     the default in place, and the distinction is the stream. A Stream holds an open server-side
    //     cursor that must not outlive the transaction which opened it, so a stream read made with no
    //     surrounding transaction is a defect worth refusing at the boundary rather than diagnosing
    //     from a closed-connection failure several frames away. The export step supplies that
    //     transaction, so the requirement costs its only caller nothing.
    // Trade-offs: readOnly is retained even though it is not what enforces read-only access -- the
    //     narrow base type above, the absent write grant and the entity's immutability marker are. It
    //     earns its place by stating the method's intent at the method and by setting the flush mode
    //     to manual, so the surrounding persistence context cannot be flushed as a side effect of
    //     this walk.
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    // Assumptions: the fetch-size hint is what makes this stream actually stream. The driver opens a
    //     server-side cursor only when a positive fetch size and a non-auto-commit connection both
    //     hold; with either missing it buffers the whole result client-side, which on an unbounded
    //     master is heap exhaustion rather than a slowdown. Only POSITIVITY carries that property, so
    //     the streaming contract rests on the value being above zero and not on which value it is.
    // Trade-offs: the hint is a per-query OVERRIDE of the session default, which the profiles set to
    //     25 in development and 250 in production while the base sets 100. What is given up is
    //     per-environment tuning of this one walk; what is bought is a window fixed at the method,
    //     which no external property can set to zero and thereby turn into a full client-side buffer.
    //     The constant is aligned with the base value so the default deployment behaves identically
    //     whichever governs, and it matches the three sibling walks for the same reason.
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "100"))
    Stream<Customer> findAllByOrderByCustomerIdAsc();
}
