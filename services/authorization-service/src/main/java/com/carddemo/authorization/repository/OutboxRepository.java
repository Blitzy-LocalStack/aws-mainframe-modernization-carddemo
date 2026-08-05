package com.carddemo.authorization.repository;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the transactional outbox that holds authorization replies awaiting publication.
 *
 * <p>This interface has no baseline counterpart, because the baseline has no outbox: it commits its
 * database work at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} line 335 and publishes its
 * reply separately at line 753, so a failure between the two loses a reply the data says was produced.
 * The rows this interface manages are written inside the deciding transaction and published afterwards,
 * which closes that window. The divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Assumptions: the mapped table name is unqualified and resolves through the connection
 * {@code search_path} pinned by {@code com.carddemo.authorization.config.DataSourceConfig}, exactly as
 * this package's charter requires.</p>
 */
public interface OutboxRepository extends JpaRepository<AuthReplyOutbox, Long> {

    /**
     * Claims a batch of unpublished replies for one publisher instance, oldest first.
     *
     * <p>Trade-offs: the query takes a row lock and SKIPS rows another transaction already holds, which
     * is what lets more than one publisher instance drain the outbox concurrently without two of them
     * sending the same reply. Locking without skipping would make the second instance wait for the
     * first, turning a horizontally-scaled publisher into a serial one; not locking at all would let both
     * send. Skipping is safe here because the rows are independent -- ordering between replies is
     * preserved by the queue's message group, not by the order in which the publisher happens to claim
     * them.</p>
     *
     * <p>Assumptions: the ordering is by the identity key, which is monotonic, so a claim drains in
     * insertion order. Ordering by the creation timestamp would be ambiguous for two rows written inside
     * the same microsecond, which two authorizations on one card can be.</p>
     *
     * <p>Assumptions: the predicate reads only the rows with no publication instant, which the partial
     * index over exactly that predicate answers, so the claim stays a small index scan however large the
     * published history grows.</p>
     *
     * <p>Trade-offs: the statement is NATIVE rather than derived, because the row-lock-with-skip clause
     * has no portable equivalent in the query language and a derived method cannot express it. The cost
     * is that the table name appears in this one string, which is why the unqualified-name assumption
     * above is stated on this interface rather than left to the entity mapping.</p>
     *
     * @param batchSize the maximum number of rows to claim in one pass
     * @return the claimed rows, oldest first, empty when nothing is pending
     */
    @Query(value = """
            select * from auth_reply_outbox
             where published_at is null
             order by outbox_id
             limit :batchSize
             for update skip locked
            """, nativeQuery = true)
    List<AuthReplyOutbox> claimUnpublished(@Param("batchSize") int batchSize);

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
