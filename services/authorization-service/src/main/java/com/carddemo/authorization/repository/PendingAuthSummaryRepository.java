package com.carddemo.authorization.repository;

import com.carddemo.authorization.domain.PendingAuthSummary;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/**
 * Reads and writes the per-account pending-authorization summary.
 *
 * <p>This is the migrated form of the keyed access the baseline performs against the ROOT segment
 * {@code PAUTSUM0} of the hierarchical database declared in
 * {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd} at line 28. The segment is retrieved by its
 * account key and rewritten in place, which is what the two operations below express.</p>
 *
 * <p>Assumptions: the mapped table name is unqualified and resolves through the connection
 * {@code search_path} pinned by {@code com.carddemo.authorization.config.DataSourceConfig}, exactly as
 * this package's charter requires.</p>
 */
public interface PendingAuthSummaryRepository extends JpaRepository<PendingAuthSummary, Long> {

    /**
     * Loads a summary for update, taking a row lock that is held until the transaction ends.
     *
     * <p>Trade-offs: this is a PESSIMISTIC lock, not the optimistic version check the account and card
     * contexts use. The difference is deliberate and follows from who writes the row. An account is
     * edited by a person across a screen turn, so a lock cannot be held for the duration and a
     * before-image comparison is the only workable check. This row is read and written inside one short
     * transaction by the authorization consumer, and two messages for the same account can be in flight
     * on two consumers at once; a lock taken at read time makes the second wait, whereas an optimistic
     * check would let it proceed and then fail at commit, discarding work that a retry has to redo.
     * Because the transaction is short and keyed on one row, the lock's cost is a brief wait rather than
     * contention.</p>
     *
     * <p>Assumptions: the counters this row carries are incremented, not assigned, so a lost update
     * would silently under-count rather than fail. That is precisely the class of error a read-time lock
     * removes and an after-the-fact comparison only detects.</p>
     *
     * @param accountId the account whose summary is required; must not be {@code null}
     * @return the locked summary, or an empty optional when the account has none yet
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PendingAuthSummary> findWithLockByAccountId(Long accountId);

    /**
     * Loads a summary without taking a lock, for read-only use.
     *
     * <p>Assumptions: a read that will not write must not take a write lock, because doing so would
     * serialise the summary screen behind the consumer for no benefit. The two methods differ only in
     * their locking, which is why they are two methods rather than one with a flag.</p>
     *
     * @param accountId the account whose summary is required; must not be {@code null}
     * @return the summary, or an empty optional when the account has none yet
     */
    Optional<PendingAuthSummary> findByAccountId(Long accountId);

    /**
     * Returns the summaries whose account is strictly beyond a stated position, in key order.
     *
     * <p>Purpose: this is the sequential walk of the root segment that the unload and purge programs
     * perform. Both drive the whole database in key order -- {@code PAUDBUNL.CBL} paragraph
     * {@code 2000-FIND-NEXT-AUTH-SUMMARY} at L207 and {@code CBPAUP0C.cbl} at its L216 both issue an
     * unqualified get-next against the root and stop when the database is exhausted -- and the root's
     * sequence field is the account identifier, declared unique at {@code ims/DBPAUTP0.dbd} L30.
     *
     * <p>Alternatives Considered: {@code findAll(Sort)} with an offset page. Rejected for the reason
     * recorded across this migration: an offset walk over a table another process is inserting into skips
     * and repeats rows, and this walk's caller DELETES from the very table it is walking, which is the
     * case where an offset shifts under the reader by construction. Keying the walk on the position
     * already read reproduces the get-next it stands for exactly.
     *
     * <p>Assumptions: the opening call passes an identifier below every real one rather than a null, so
     * one predicate serves the whole walk. An account identifier is positive, so zero is that value and
     * the caller does not need a separate first-page query.
     *
     * @param accountId the account of the last summary already read, or a value below every real
     *     identifier to start the walk; must not be {@code null}
     * @param limit the maximum number of summaries to return; must not be {@code null}
     * @return up to {@code limit} summaries in ascending account order, empty when the walk is done
     */
    List<PendingAuthSummary> findByAccountIdGreaterThanOrderByAccountIdAsc(Long accountId,
            Limit limit);
}
