package com.carddemo.batch.repository;

import com.carddemo.batch.domain.Account;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and rewrites the account master rows the posting and accrual jobs update.
 *
 * <p>Purpose: the reference reads an account by its key and rewrites the same record in place. Posting
 * does it at {@code app/cbl/CBTRN02C.cbl:372} for the read and {@code app/cbl/CBTRN02C.cbl:547-553} for
 * the rewrite; interest accrual does it at {@code app/cbl/CBACT04C.cbl:350-356}. Both are keyed reads
 * followed by a rewrite of the record just read, which is what this interface exposes and nothing
 * more.</p>
 *
 * <p>Assumptions: the table behind this interface is {@code account.accounts}, which the account context
 * owns. This module reaches it under the one cross-schema grant the migration plan allows -- its section
 * 0.4.1.3 records that the three-write posting unit of work stays a single commit and that the batch role
 * therefore holds narrowly-scoped write grants on {@code ledger} and {@code account} only. A method
 * reaching any further schema would compile and then fail at run time with a privilege error rather than
 * at build time, which is why the surface here is deliberately two methods wide.</p>
 *
 * <p>Alternatives Considered: extending the narrow {@code Repository} marker rather than the full
 * {@code JpaRepository}, as {@code DailyTransactionRepository} and {@code TransactionRejectRepository}
 * both do, so that no unused write or delete method is inherited. It was declined here for one concrete
 * reason: the posting unit of work needs the rewrite to participate in the caller's transaction as a
 * MANAGED-ENTITY update -- it mutates the account it just read and lets the flush write it -- and the
 * full interface is what supplies {@code saveAndFlush} for the accrual job's control-break write, where
 * the write has to land before the next account's rows are read. The cost accepted is the inherited
 * delete methods, which no caller in this module uses and which the batch role has no grant for.</p>
 *
 * <p>Assumptions: optimistic concurrency is NOT expressed here and is not needed. The account entity
 * carries a version column, so a concurrent modification is detected by the persistence provider on
 * flush; the batch window runs with online writes quiesced -- state one of the nightly chain sets the
 * read-only flag and state eleven clears it -- so a conflict here means two batch steps overlapped,
 * which is a failure the step must surface rather than merge.</p>
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Reads one account master row by its identifier.
     *
     * <p>Assumptions: an absent row is reported as an empty optional rather than raised, because absence
     * is a business outcome here and not a fault. It is reject reason 101 at
     * {@code app/cbl/CBTRN02C.cbl:393-399}, which the validation service resolves and the posting job
     * writes to the reject stream, so a raised exception would turn a documented reject into an
     * abend.</p>
     *
     * @param accountId the eleven-digit account identifier; must not be {@code null}
     * @return the account when one exists, otherwise an empty optional
     */
    Optional<Account> findByAccountId(Long accountId);

    /**
     * Streams every account in identifier order.
     *
     * <p>Assumptions: the order is by identifier ascending because that is the order the reference's own
     * sequential read of the account master produces, the file being keyed on that column. A job that
     * depends on control breaks therefore sees the same grouping the reference saw.
     *
     * <p>Trade-offs: a stream rather than a list, matching the sibling feed and ledger repositories. The
     * account master is unbounded in principle, so materialising it would hold every row in the heap at
     * once; a stream keeps one row in flight at the cost of the caller having to close it, which is why
     * every caller opens it in a try-with-resources inside a read-only transaction.
     *
     * @return a stream of every account ordered by identifier ascending; never {@code null}, and the caller
     *     closes it
     */
    Stream<Account> findAllByOrderByAccountIdAsc();
}
