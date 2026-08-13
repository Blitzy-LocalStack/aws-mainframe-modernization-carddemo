package com.carddemo.account.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Records, finds and retires the reply already produced for one account-inquiry request.
 *
 * <p><b>Purpose.</b> The asynchronous inquiry exchange transcribed from
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl} answers a request by sending a reply and then returning, and
 * the queue acknowledges the request only on that clean return. Everything between the send and the
 * acknowledgement is a window in which the reply exists and the request does not yet know it, so a task
 * killed there, a container cycled there or an acknowledgement lost there leaves the request visible again
 * and the next delivery sends a SECOND reply bearing the same correlation identifier as the first. This
 * class is the durable side of the remedy: a reply is recorded under the identity the QUEUE SERVICE
 * assigned the delivery and committed BEFORE it is sent, so a redelivery can discover that the answer
 * was already produced. Refactoring Rationale: the key was the identity the PRODUCER supplied, which is
 * neither authenticated nor unique per request, so a reused correlation identifier suppressed a second
 * genuine inquiry; the caller chooses the key and
 * {@link com.carddemo.account.service.InquiryMessageListener} records why it now prefers the
 * broker's.</p>
 *
 * <p>Assumptions: the three statements are NATIVE and not JPQL, and the claim is why. A claim has to
 * insert a row if and only if no row holds that key, and report which of the two happened, in ONE round
 * trip -- {@code INSERT ... ON CONFLICT DO NOTHING} is the only construct that does so, and JPQL has no
 * form of it. Expressing the same intent as a read followed by a conditional insert would leave the
 * decision to a gap between two statements, which is precisely where two concurrent deliveries of one
 * request would both decide they were first.</p>
 *
 * <p>Alternatives Considered: a Spring Data JPA repository over a mapped entity, which is what the other
 * five repositories in this package are. Rejected because the claim is not expressible through one, as
 * above, and because a mapped entity would let any member of this module read or write the ledger through
 * the persistence context -- including flushing a stale copy over a row a concurrent delivery had already
 * advanced. Three named statements over one table claim nothing else and offer nothing else.</p>
 *
 * <p>Alternatives Considered: {@code SELECT ... FOR UPDATE} to serialise two concurrent deliveries of one
 * request. Rejected because there is nothing to lock until the row exists, so the first two deliveries
 * would both find nothing and both proceed; the primary key does the same work at the moment of insertion,
 * which is the only moment at which it can be done.</p>
 *
 * <p>Trade-offs: this narrows duplication to a single crash window rather than eliminating it. A task that
 * dies after the send and before {@link #markSent} leaves the row PENDING, so the redelivery re-sends the
 * recorded reply and the requester receives two copies of one answer -- identical copies, since the
 * payload is stored verbatim rather than recomposed. Closing that window entirely would require the send
 * and the mark to commit together across two resource managers, which is the two-phase commit AAP section
 * 0.7.6 records as eliminated. What is bought is that the window is now one specific failure instead of
 * every redelivery, and that the duplicate is byte-identical to the original rather than potentially
 * carrying a balance that moved in between.</p>
 *
 * <p>Parameters, return values, exceptions or errors: this class declares no constructor a caller supplies
 * arguments to and yields no value of its own; each member carries its own at-clauses. The inapplicability
 * is stated rather than passed over, because the Explainability rule forbids a docstring that omits
 * parameters or return values and a reader must be able to tell a declared inapplicability from an
 * oversight.</p>
 */
@Repository
public class InquiryReplyLedger {

    /**
     * The state a recorded reply is in before its send has been observed to succeed.
     *
     * <p>Assumptions: published so the consumer and its tests name the same value the {@code CHECK}
     * constraint in {@code V2__account_inquiry_reply_ledger.sql} admits. A second spelling of it in either
     * place would be a state the database refuses.</p>
     */
    public static final String STATUS_PENDING = "PENDING";

    /**
     * The state a recorded reply is in once its send has succeeded.
     */
    public static final String STATUS_SENT = "SENT";

    /**
     * Claims the answer to one request, inserting the row only when no row holds that key.
     *
     * <p>Assumptions: the conflict target is the PRIMARY KEY and the action is to do nothing, so the
     * statement reports one affected row when this delivery is the first to answer the request and zero
     * when another delivery already has. The count is the decision; nothing else in the exchange has to
     * be consulted to reach it.</p>
     */
    private static final String CLAIM_REPLY = """
            insert into account.inquiry_reply_ledger (
                request_key, status, reply_payload, reply_to_queue_url,
                correlation_id, message_id, claimed_at, attempts)
            values (
                :requestKey, '""" + STATUS_PENDING + """
            ', :payload, :destination,
                :correlationId, :messageId, :claimedAt, 0)
            on conflict (request_key) do nothing
            """;

    /**
     * Reads the recorded state and the reply bytes for one request key.
     */
    private static final String FIND_CLAIM = """
            select status, reply_payload, reply_to_queue_url, correlation_id, message_id
            from account.inquiry_reply_ledger
            where request_key = :requestKey
            """;

    /**
     * Marks a claimed reply as sent, and counts the send.
     *
     * <p>Assumptions: the update is guarded on the PENDING state, so a second delivery that re-sent the
     * reply cannot advance a row another delivery already retired -- and the affected-row count therefore
     * tells the caller whether this delivery was the one that retired it.</p>
     */
    private static final String MARK_SENT = """
            update account.inquiry_reply_ledger
               set status = '""" + STATUS_SENT + """
            ',
                   sent_at = :sentAt,
                   attempts = attempts + 1
             where request_key = :requestKey
               and status = '""" + STATUS_PENDING + """
            '
            """;

    /** The bind name of the request key, so the three statements cannot spell it differently. */
    private static final String PARAM_REQUEST_KEY = "requestKey";

    /**
     * The persistence context the native statements are issued through.
     *
     * <p>Assumptions: field injection, which is how a {@code @PersistenceContext} proxy is supplied and
     * the arrangement the sibling native-statement repository in {@code transaction-service} uses. The
     * proxy resolves the transaction-bound entity manager per call, so this bean holds no connection and
     * carries no per-request state.</p>
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Records this delivery's answer, if no earlier delivery has already recorded one.
     *
     * <p>Assumptions: the caller must COMMIT this write before it sends the reply. The whole guarantee
     * rests on that order -- a reply sent before its record is committed is a reply a redelivery cannot
     * discover, which is the state this ledger exists to remove.</p>
     *
     * @param requestKey the delivery's durable identity, broker-assigned where one is present; must not
     *     be {@code null} or blank
     * @param payload the framed reply, stored verbatim so a re-send sends the same bytes; must not be
     *     {@code null}
     * @param destination the resolved reply destination, recorded so a re-send goes where the first send
     *     went; must not be {@code null}
     * @param correlationId the correlation identity to echo on the reply, or {@code null} if the request
     *     supplied none
     * @param messageId the message identity to echo on the reply, or {@code null} if the request supplied
     *     none
     * @param claimedAt the instant the claim is taken, which the pruning statement reads; must not be
     *     {@code null}
     * @return {@code true} when this delivery took the claim, {@code false} when a row already held the
     *     key and this delivery is a redelivery
     */
    public boolean claim(String requestKey, String payload, String destination,
            String correlationId, String messageId, LocalDateTime claimedAt) {

        Query insert = this.entityManager.createNativeQuery(CLAIM_REPLY);
        insert.setParameter(PARAM_REQUEST_KEY, requestKey);
        insert.setParameter("payload", payload);
        insert.setParameter("destination", destination);
        insert.setParameter("correlationId", correlationId);
        insert.setParameter("messageId", messageId);
        insert.setParameter("claimedAt", claimedAt);
        return insert.executeUpdate() == 1;
    }

    /**
     * Reads the reply an earlier delivery recorded for this request.
     *
     * @param requestKey the delivery's durable identity, broker-assigned where one is present; must not
     *     be {@code null} or blank
     * @return the recorded claim, or an empty optional when no delivery has recorded one
     */
    public Optional<RecordedReply> find(String requestKey) {
        Query select = this.entityManager.createNativeQuery(FIND_CLAIM);
        select.setParameter(PARAM_REQUEST_KEY, requestKey);

        // WHY : Assumptions: the result is taken as a LIST and reduced, rather than through a
        //       single-result call. A single-result call raises when nothing matches, and nothing
        //       matching is the ordinary first-delivery outcome rather than a failure -- so the exchange
        //       would be driving its normal path through an exception.
        List<?> rows = select.getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.getFirst();
        return Optional.of(new RecordedReply(
                (String) row[0], (String) row[1], (String) row[2], (String) row[3], (String) row[4]));
    }

    /**
     * Retires a claimed reply, recording that it reached the queue.
     *
     * <p>Assumptions: the caller must COMMIT this write only AFTER the send has returned. Marking first
     * would suppress the re-send of a reply that never reached the queue, which turns a duplicated answer
     * into a missing one -- the worse of the two failures, because a requester waiting on an answer that
     * will never arrive has no signal at all.</p>
     *
     * @param requestKey the delivery's durable identity, broker-assigned where one is present; must not
     *     be {@code null} or blank
     * @param sentAt the instant the send returned; must not be {@code null}
     * @return {@code true} when this call retired the claim, {@code false} when it was already retired
     */
    public boolean markSent(String requestKey, LocalDateTime sentAt) {
        Query update = this.entityManager.createNativeQuery(MARK_SENT);
        update.setParameter(PARAM_REQUEST_KEY, requestKey);
        update.setParameter("sentAt", sentAt);
        return update.executeUpdate() == 1;
    }

    /**
     * One recorded answer, as an earlier delivery left it.
     *
     * <p>Assumptions: this is a repository-layer carrier and not a wire shape. Nothing serialises it and
     * no endpoint returns it -- the consumer reads it to decide between suppressing a duplicate and
     * re-sending a recorded reply -- so it is declared here rather than in the transfer-object package,
     * which would describe it as something a client receives.</p>
     *
     * @param status the recorded state, {@link #STATUS_PENDING} or {@link #STATUS_SENT}; never
     *     {@code null}, because the column is declared {@code NOT NULL} under a {@code CHECK}
     * @param payload the framed reply exactly as it was recorded; never {@code null}
     * @param destination the reply destination the first send resolved; never {@code null}
     * @param correlationId the correlation identity to echo, or {@code null} if the request supplied none
     * @param messageId the message identity to echo, or {@code null} if the request supplied none
     */
    public record RecordedReply(String status, String payload, String destination,
            String correlationId, String messageId) {

        /**
         * Reports whether the recorded reply has already reached the queue.
         *
         * @return {@code true} when the answer was sent and a redelivery must be suppressed
         */
        public boolean sent() {
            return STATUS_SENT.equals(this.status);
        }
    }
}
