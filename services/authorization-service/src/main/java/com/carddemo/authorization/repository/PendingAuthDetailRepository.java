package com.carddemo.authorization.repository;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes individual pending authorizations, and pages them by key.
 *
 * <p>This is the migrated form of the browse the baseline performs against the CHILD segment
 * {@code PAUTDTL1} declared in {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd} at line 36.
 * The baseline steps through the segment occurrences beneath one root and re-seeks to resume, holding
 * the position between screen turns; a stateless request handler has nowhere to hold a position, so the
 * position travels in the request as a key instead and the query below seeks straight to it.</p>
 *
 * <p>Assumptions: the paging methods return ONE row more than the caller's page size, and that is the
 * contract rather than an accident. The baseline decides whether a next page exists by discovering one
 * more occurrence than fits on the screen; returning size-plus-one reproduces exactly that, so the
 * caller can set its next-page indicator from the number of rows it received without a second count
 * query. A caller that asks for twenty rows and receives twenty-one must render twenty and report that
 * more exist.</p>
 *
 * <p>Trade-offs: paging is by KEY, not by offset. Under concurrent inserts an offset page skips and
 * repeats rows, which would be a visible behavioural change from a browse that steps by key and never
 * does. The cost is that a caller cannot jump to an arbitrary page number, which the baseline could not
 * do either.</p>
 */
public interface PendingAuthDetailRepository
        extends JpaRepository<PendingAuthDetail, PendingAuthDetailKey> {

    /**
     * Returns the most recent authorizations for an account, newest first.
     *
     * <p>Assumptions: "newest first" means descending by authorization date and then by authorization
     * time, which is the order the supporting index declares, so the query is answered by an index scan
     * rather than a sort. Ordering by a single combined value would need an expression index and would
     * not match the two-part key the baseline compares.</p>
     *
     * @param accountId the account whose authorizations are required; must not be {@code null}
     * @param limit the maximum number of rows to return, which the caller sets to its page size plus
     *     one; must not be {@code null}
     * @return up to {@code limit} authorizations, newest first, empty when the account has none
     */
    List<PendingAuthDetail> findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(
            Long accountId, Limit limit);

    /**
     * Returns the authorizations for an account that are strictly older than a stated position,
     * newest first.
     *
     * <p>Assumptions: the predicate compares the date and time as a PAIR -- a row qualifies when its
     * date is earlier, or when its date is equal and its time is earlier. Comparing the two independently
     * would either skip every row on the boundary date or return rows already shown, which is the
     * classic error in hand-written keyset paging and the reason this predicate is spelled out here
     * rather than derived from a method name.</p>
     *
     * @param accountId the account whose authorizations are required; must not be {@code null}
     * @param authDate the authorization date of the last row already shown; must not be {@code null}
     * @param authTime the authorization time of the last row already shown; must not be {@code null}
     * @param limit the maximum number of rows to return, which the caller sets to its page size plus
     *     one; must not be {@code null}
     * @return up to {@code limit} older authorizations, newest first, empty when none remain
     */
    @Query("""
            select d from PendingAuthDetail d
             where d.id.accountId = :accountId
               and (d.id.authDate < :authDate
                    or (d.id.authDate = :authDate and d.id.authTime < :authTime))
             order by d.id.authDate desc, d.id.authTime desc
            """)
    List<PendingAuthDetail> findOlderThan(@Param("accountId") Long accountId,
            @Param("authDate") Integer authDate, @Param("authTime") Integer authTime, Limit limit);

    /**
     * Returns the authorizations for an account that are strictly newer than a stated position,
     * oldest first.
     *
     * <p>Assumptions: this is the backward page, and it is ordered ASCENDING so that the rows nearest
     * the caller's position are the ones the limit keeps. Ordering it descending and taking the limit
     * would return the newest rows in the table rather than the ones immediately preceding the current
     * page, which is a page the user never asked for. The caller reverses the list before rendering, so
     * the two directions present identically.</p>
     *
     * @param accountId the account whose authorizations are required; must not be {@code null}
     * @param authDate the authorization date of the first row already shown; must not be {@code null}
     * @param authTime the authorization time of the first row already shown; must not be {@code null}
     * @param limit the maximum number of rows to return, which the caller sets to its page size plus
     *     one; must not be {@code null}
     * @return up to {@code limit} newer authorizations, oldest first, empty when none remain
     */
    @Query("""
            select d from PendingAuthDetail d
             where d.id.accountId = :accountId
               and (d.id.authDate > :authDate
                    or (d.id.authDate = :authDate and d.id.authTime > :authTime))
             order by d.id.authDate asc, d.id.authTime asc
            """)
    List<PendingAuthDetail> findNewerThan(@Param("accountId") Long accountId,
            @Param("authDate") Integer authDate, @Param("authTime") Integer authTime, Limit limit);

    /**
     * Finds an authorization by the durable idempotency key, the card and the transaction identifier.
     *
     * <p>Assumptions: this is how a REDELIVERED request is recognised as one already decided. The queue
     * suppresses duplicates only within its deduplication window, so a redelivery after that window
     * reaches the consumer as a new message and must not be decided twice.</p>
     *
     * <p>Refactoring Rationale: the lookup is by the PAIR and not by the transaction identifier alone,
     * and the change is recorded because an earlier revision looked up the identifier on its own. That
     * form was wrong in two directions at once. The identifier is a fifteen-character acquirer value at
     * {@code app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy} line 36, so two acquirers may
     * legitimately issue the same one -- and a lookup by identifier alone would then return the FIRST
     * card's decision to the second card's requester, answering an authorization for one card with the
     * approval or decline recorded for another. It also had no supporting uniqueness at all, so the same
     * query could match more than one row and the single-result contract would fail at runtime rather
     * than at review. The unique constraint {@code uq_pending_auth_detail_card_transaction} declared in
     * {@code V1__authorization.sql} makes this pair unique and indexes it, so this query is one seek and
     * its single result is guaranteed by the schema rather than assumed by this interface.</p>
     *
     * @param cardNum the sixteen-character primary account number the request carried; must not be
     *     {@code null}
     * @param transactionId the acquirer's transaction identifier; must not be {@code null}
     * @return the authorization already recorded for that card and identifier, or an empty optional when
     *     none is
     */
    Optional<PendingAuthDetail> findByCardNumAndTransactionId(String cardNum, String transactionId);

    /**
     * Reads one authorization by its full three-part key and holds it for update.
     *
     * <p>Assumptions: the lock is what makes the fraud write's find-then-insert sequence safe. That
     * write probes {@code auth_fraud} and takes the insert path when it finds nothing, so two requests
     * naming one authorization must not both observe an absent fraud row; both must first read THIS row,
     * so locking it here serialises them before either probe runs. Without the lock the second request
     * would reach a uniqueness violation on the fraud table's primary key, which inside one transaction
     * is unrecoverable rather than retryable, and the caller would receive an internal failure for a
     * request that was merely concurrent.
     *
     * <p>Refactoring Rationale: the lock is a transcription rather than a target-only addition. The
     * reference detail screen re-reads the segment for update inside its fraud path -- {@code MARK-AUTH-FRAUD}
     * at {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl} L233 performs {@code READ-AUTH-RECORD},
     * whose {@code EXEC DLI GU} on the parent at L439 and {@code GNP} on the child at L465 run against an
     * update-capable program communication block, so the occurrence is held before the {@code REPL} at
     * L525 to L528. The migrated form of holding an occurrence for replacement is a pessimistic write
     * lock, and it is declared here rather than left to the provider's default because the default is no
     * lock at all.
     *
     * <p>Trade-offs: this is a PESSIMISTIC lock where the account and card contexts use an optimistic
     * version column. The two situations differ in one respect that decides it: an optimistic version
     * detects a conflict when the write lands, which is fine when the only casualty is that write, but
     * this operation's conflict lands on a DIFFERENT table's primary key, where the failure is a
     * constraint violation rather than a version mismatch and cannot be reported as a contention. The
     * cost accepted is that concurrent marks of one authorization queue rather than one of them failing
     * fast, and the queue is short because the transaction holding the lock performs two writes and
     * commits.
     *
     * @param id the account identifier, decoded Julian authorization date and decoded millisecond
     *     authorization time forming the composite key; must not be {@code null}
     * @return the authorization that key names, held for update until the surrounding transaction ends,
     *     or an empty optional when the key names no row
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PendingAuthDetail> findWithLockById(PendingAuthDetailKey id);

    /**
     * Returns every authorization beneath one account, in the order the reference segment is sequenced in.
     *
     * <p>Purpose: this is the child walk the unload and purge programs perform beneath each root --
     * {@code PAUDBUNL.CBL} paragraph {@code 3000-FIND-NEXT-AUTH-DTL} at L253 and the equivalent scan in
     * {@code CBPAUP0C.cbl} -- each of which issues an unqualified get-next-within-parent until the
     * parent's children are exhausted, with no page boundary anywhere in the loop.
     *
     * <p>Assumptions: the order is DESCENDING on the two decoded key columns, and that is what reproduces
     * the reference order rather than contradicting it. {@code ims/DBPAUTP0.dbd} L37 declares the child's
     * unique sequence field {@code PAUT9CTS} over the first eight bytes of the segment, which hold the
     * NINES COMPLEMENT of the date and time; ascending byte order on a complement is descending order on
     * the value it complements. The key columns here store the DECODED date and time, so descending on
     * them is ascending on the complement -- the twin order the reference walk observes, newest
     * authorization first. Ordering ascending on the decoded columns would emit the file backwards.
     *
     * <p>Trade-offs: the whole child set of one account is returned rather than paged. An account's
     * pending authorizations are bounded by the two four-digit counters its summary carries, so the set is
     * small by construction, and both callers need every child of the account they are positioned on
     * before they can decide anything about the parent. Paging it would add a cursor to a walk whose outer
     * loop is already keyed, for no bound that the data does not already impose.
     *
     * @param accountId the account whose authorizations are required; must not be {@code null}
     * @return every authorization beneath that account, newest first, empty when it has none
     */
    List<PendingAuthDetail> findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(Long accountId);
}
