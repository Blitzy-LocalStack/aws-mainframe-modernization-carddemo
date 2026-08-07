package com.carddemo.authorization.repository;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the transactional outbox that holds authorization replies awaiting publication.
 *
 * <p>This interface has no baseline counterpart, because the baseline has no outbox: it puts its reply
 * at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 461 and writes and commits its
 * decision only afterwards, at line 464 and line 335 respectively, so a failure after the put leaves a
 * reply on the queue that no committed row accounts for. The rows this interface manages are written
 * inside the deciding transaction and published afterwards, which closes that window. The divergence is
 * registered as {@code D-5} in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Assumptions: the mapped table name is unqualified and resolves through the connection
 * {@code search_path} pinned by {@code com.carddemo.authorization.config.DataSourceConfig}, exactly as
 * this package's charter requires.</p>
 */
public interface OutboxRepository extends JpaRepository<AuthReplyOutbox, Long> {

    /**
     * Claims the OLDEST unpublished reply of each ordering group, oldest group first.
     *
     * <p>Refactoring Rationale: this claimed the oldest unpublished rows globally before, and the change
     * is a correctness fix rather than a tuning one. A first-in-first-out queue orders messages within a
     * group only AFTER it has accepted them, so the order in which a publisher calls send is the order
     * the group is delivered in. A global claim could hand one pass two rows of one group and, when the
     * older one's send failed while the drain continued, place the newer reply in the group ahead of the
     * older one -- reversing two answers for one card, which is the single guarantee the group identifier
     * exists to provide. Claiming at most one row per group makes that reversal unrepresentable: a later
     * row of the same group is not claimable while its predecessor is still pending, so a failed send
     * leaves the group blocked at its own head until it succeeds or expires.</p>
     *
     * <p>Assumptions: the head of a group is its LOWEST identity value, not its earliest creation
     * timestamp. The identity is generated strictly increasing and is unique, so it totally orders the
     * rows of a group; the creation timestamp defaults to the statement timestamp, so two rows written in
     * one transaction share it and their relative order would be undefined.</p>
     *
     * <p>Trade-offs: the query takes a row lock and SKIPS rows another transaction already holds, which
     * is what lets more than one publisher instance drain the outbox concurrently without two of them
     * sending the same reply. Skipping is safe now that a claim is per group: a skipped row is a group
     * another instance is already publishing in order, and the group this instance skipped is exactly the
     * group it must not advance.</p>
     *
     * <p>Trade-offs: the statement is NATIVE rather than derived, because neither the row-lock-with-skip
     * clause nor the per-group minimum has a portable equivalent in the query language. The cost is that
     * the table name appears in these strings, which is why the unqualified-name assumption above is
     * stated on this interface rather than left to the entity mapping.</p>
     *
     * @param batchSize the maximum number of groups to claim a head row from in one pass
     * @return the claimed head rows, one per group and oldest group first, empty when nothing is pending
     */
    @Query(value = """
            select * from auth_reply_outbox o
             where o.published_at is null
               and o.outbox_id = (select min(h.outbox_id)
                                    from auth_reply_outbox h
                                   where h.published_at is null
                                     and h.order_group_token = o.order_group_token)
             order by o.outbox_id
             limit :batchSize
             for update skip locked
            """, nativeQuery = true)
    List<AuthReplyOutbox> claimGroupHeads(@Param("batchSize") int batchSize);

    /**
     * Claims the next unpublished replies of ONE ordering group, in order, after a row just published.
     *
     * <p>Assumptions: this is what keeps throughput from collapsing to one reply per group per drain now
     * that a claim is per group. Once a group's head has been sent successfully, the rows behind it may
     * be sent in the same pass without any risk of reordering, because they are claimed in ascending
     * identity order and each send is awaited before the next is issued.</p>
     *
     * <p>Assumptions: the predicate is bounded BELOW by the identity just published rather than
     * re-reading the group's head, so a row this pass has already handled cannot be claimed twice, and
     * the partial index on the group token and the identity answers the predicate directly.</p>
     *
     * @param orderGroupToken the group to advance, as the keyed token stored on the row; must not be
     *     {@code null}
     * @param afterOutboxId the identity of the row just published; only higher identities are claimed
     * @param batchSize the maximum number of follow-on rows to claim
     * @return the claimed rows of that group in ascending identity order, empty when the group is drained
     */
    @Query(value = """
            select * from auth_reply_outbox
             where published_at is null
               and order_group_token = :orderGroupToken
               and outbox_id > :afterOutboxId
             order by outbox_id
             limit :batchSize
             for update skip locked
            """, nativeQuery = true)
    List<AuthReplyOutbox> claimGroupFollowers(@Param("orderGroupToken") String orderGroupToken,
            @Param("afterOutboxId") long afterOutboxId, @Param("batchSize") int batchSize);

    /**
     * Deletes replies that were published before a cut-off instant.
     *
     * <p>Assumptions: the predicate requires a publication instant to be PRESENT, so a pending row can
     * never be selected however old it is. That is the one property this method must have: an unpublished
     * row is the reply this table exists to guarantee, and deleting one would lose an answer the data
     * says was produced -- reintroducing, from the retention side, the exact window the outbox closes.</p>
     *
     * <p>Alternatives Considered: deleting a row as it is published, which would keep the table empty and
     * need no sweep at all. Rejected because a short published history is what lets an operator answer
     * whether a reply was ever sent for a given transaction, which is precisely the question the
     * baseline's separate queue and database units of work made unanswerable. The retention window is
     * configured rather than fixed, so the answer stays available for as long as an operator needs it and
     * no longer.</p>
     *
     * <p>Trade-offs: the statement is a bulk delete rather than a load-then-remove loop, so it does not
     * pass through the persistence context and no entity callback runs. Nothing on this entity has a
     * callback, and loading a retention window's worth of rows to delete them one at a time would read
     * every payload -- each of which carries a primary account number -- into memory for no purpose.</p>
     *
     * @param cutoff the instant published rows must precede to be deleted, in coordinated universal
     *     time; must not be {@code null}
     * @return how many rows were deleted
     */
    @Modifying
    @Query("""
            delete from AuthReplyOutbox r
             where r.publishedAt is not null
               and r.publishedAt < :cutoff
            """)
    int deletePublishedBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Counts the replies still awaiting publication.
     *
     * <p>Assumptions: this is the number a monitor alarms on. A backlog that grows without bound means
     * the publisher is failing rather than merely busy, and the queue's own depth cannot show it --
     * an unpublished reply has never reached the queue.</p>
     *
     * @return how many rows have no publication instant recorded
     */
    long countByPublishedAtIsNull();
}
