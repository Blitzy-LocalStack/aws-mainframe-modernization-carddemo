package com.carddemo.authorization.repository;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * Resolves the account a card most recently authorized against.
     *
     * <p>Trade-offs: this is a NARROW substitute for the baseline's cross-reference read at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 472 to 516, which resolves a card
     * to an account through the cross-reference file. That file is owned by the account context, and this
     * context holds no grant on it, so the resolution here uses the one card-to-account association this
     * context DOES own: the account recorded on the card's own previous authorizations, which the ETL
     * loads from the baseline extract along with everything else. When a card has no previous
     * authorization the resolution yields nothing and the caller declines with reason {@code '3100'},
     * which is the very reason the baseline returns on its own {@code CARD-NFOUND-XREF} path at line 704 --
     * so the observable outcome for an unresolvable card is unchanged.</p>
     *
     * <p>Assumptions: "most recently" means the highest date-then-time pair, which the card-number index
     * narrows and the sort then orders. Choosing the earliest instead would resolve to an account the card
     * may since have been reissued against.</p>
     *
     * @param cardNum the sixteen-digit primary account number; must not be {@code null}
     * @param limit how many associations to return, which the caller sets to one because it wants only
     *     the most recent; must not be {@code null}
     * @return the account identifiers this card has authorized against, most recent first, empty when
     *     the card has no previous authorization
     */
    @Query("""
            select d.id.accountId from PendingAuthDetail d
             where d.cardNum = :cardNum
             order by d.id.authDate desc, d.id.authTime desc
            """)
    List<Long> findAccountIdsByCardNum(@Param("cardNum") String cardNum, Limit limit);

    /**
     * Finds an authorization by the acquirer's transaction identifier.
     *
     * <p>Assumptions: this is how a REDELIVERED request is recognised as one already decided. The queue
     * suppresses duplicates only within its deduplication window, so a redelivery after that window
     * reaches the consumer as a new message and must not be decided twice; the transaction identifier is
     * the acquirer's own idempotency key and the supporting index makes the check a single seek.</p>
     *
     * @param transactionId the acquirer's transaction identifier; must not be {@code null}
     * @return the authorization already recorded for that identifier, or an empty optional when none is
     */
    Optional<PendingAuthDetail> findByTransactionId(String transactionId);
}
