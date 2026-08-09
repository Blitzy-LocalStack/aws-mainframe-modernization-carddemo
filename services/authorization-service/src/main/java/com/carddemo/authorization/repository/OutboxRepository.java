package com.carddemo.authorization.repository;

import com.carddemo.authorization.domain.AuthReplyOutbox;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The only route to table {@code auth_reply_outbox}, the transactional outbox that holds
 * authorization replies awaiting publication.
 *
 * <p><strong>Purpose.</strong> Make a reply exactly as durable as the decision it reports. A row
 * arrives here through the inherited {@code save} inside the same local transaction that records the
 * authorization decision, and a publisher later claims it, sends it and marks it. Every citation in
 * this file is relative to {@code app/app-authorization-ims-db2-mq}, which is reference material this
 * migration reads and never modifies. The package-wide rulings that govern every interface here --
 * unqualified table naming, the absent transaction boundary, the prohibition on masking at this layer
 * and the single permitted intra-reactor dependency -- are stated once in this package's
 * {@code package-info.java} and are not restated below.
 *
 * <p>Assumptions: two names in the declaration below will surprise a reader arriving from the domain
 * package, so both are stated rather than left to be rediscovered. First, the record
 * {@code com.carddemo.authorization.domain.OutboxMessage} documents a repository called
 * {@code OutboxMessageRepository}; this package's specification fixes the shorter name
 * {@code OutboxRepository}, and a package specification governs its own children, so the shorter name
 * is the one declared here. There is no alias, no forwarding type and no second interface over this
 * table. Second, that same record declares itself not to be an entity and names
 * {@link AuthReplyOutbox} as the type that maps this table, so the type argument follows the mapping
 * rather than the record: the identifier is the generated identity {@link AuthReplyOutbox} declares
 * over column {@code outbox_id}, which is a {@code Long}.
 *
 * <p>Refactoring Rationale: this table has no counterpart in the reference system, because there a
 * reply is a message and never a record, and the order in which the reference consumer does its work
 * is the reason a record is introduced. That order was established by reading the paragraph
 * structure rather than the line sequence, and it is worth naming explicitly because it runs opposite
 * to the intuitive reading. {@code cbl/COPAUA0C.cbl} enters {@code 2000-MAIN-PROCESS} at L323, which
 * extracts a request at L328 and performs {@code 5000-PROCESS-AUTH} at L330. That paragraph, at L438,
 * computes the decision at L459, then performs the response paragraph at L461 -- whose put is the
 * {@code MQPUT1} at L758, flagged {@code MQPMO-NO-SYNCPOINT} at L753 -- and only afterwards performs
 * the database write at L464. The single {@code EXEC CICS SYNCPOINT} is reached later still, at L334
 * to L336, once the paragraph performed at L330 returns. <b>The verified order is therefore decide,
 * put the reply, write to the database, commit.</b> The reply is published before the writes are even
 * issued, and because the put is outside the unit of work it cannot be backed out by the rollback
 * that would back the writes out.
 *
 * <p>Assumptions: three separate outcomes follow from that order, and all three are recorded because
 * a reader who knows only one of them will design for the wrong one. The dominant outcome is a
 * PHANTOM REPLY, dominant precisely because the put comes first: the reply is delivered at L461, the
 * write at L464 then fails or the task ends before the commit at L335, and the requester holds an
 * approval for a decision no committed row accounts for. The second is a LOST REPLY, where the put
 * itself does not succeed, processing continues and the commit at L335 does, leaving a persisted
 * decision that no reply reports. The third is a LOST REQUEST, because the destructive receive is
 * outside the unit of work as well -- {@code MQGMO-NO-SYNCPOINT} combined with a wait at L389, the
 * call at L400 -- so an interruption before the commit has consumed the request without persisting
 * anything, and the request cannot be presented again to re-derive the answer. Descriptions of this
 * seam that state the ordering the other way round, commit first and publish second, describe only
 * the lost reply; that outcome and the phantom reply are therefore both recorded above, and the
 * verified ordering is named rather than implied, so the record is unambiguous either way.
 *
 * <p>Refactoring Rationale: the target inverts that order. The decision and the row that carries its
 * reply are committed together in one local transaction, and publication happens after the commit,
 * from this table. The alternative was to publish inline from the same call, which is what the
 * reference consumer does; its concrete consequence is that the phantom-reply window above survives
 * the migration unchanged, because a reply put before the write cannot be withdrawn when the write
 * does not commit. Taking this route converts a phantom reply into an at-least-once DELAYED reply,
 * which is the only direction that preserves the invariant that a reply implies a committed decision:
 * a duplicate is discarded by the reply queue on the deduplication identity stored on the row WHEN IT
 * ARRIVES INSIDE THAT QUEUE'S DEDUPLICATION INTERVAL, which is five minutes, whereas a
 * reply for a decision that never committed is not recoverable by anything downstream. Refactoring
 * Rationale: that qualification was absent and the sentence read as though duplicate suppression were
 * unconditional. It is not, and the difference is load-bearing rather than pedantic: a row whose sends
 * fail for longer than five minutes -- an unreachable queue, a permissions change, a claim that keeps
 * losing its race -- can be delivered twice once the queue is reachable again, so the requester carries
 * a real obligation to suppress by the transaction identifier the reply itself carries. Stating
 * suppression as absolute here would have told a requester it had no such obligation. This is
 * divergence D-5 in {@code docs/architecture/cobol-to-service-traceability.md}. Its companion is D-6,
 * the elimination of commit coordination across two resource managers, which follows from the pending
 * detail and the fraud row now living in one schema and is what
 * {@code com.carddemo.authorization.config.DataSourceConfig} expresses at configuration level by
 * declaring a single data source with no distributed-transaction coordinator: the single local
 * transaction is what makes one outbox row sufficient here. No reference source is edited by any of
 * this. The baseline behaves as described above, the target adds a durable reply, the difference is
 * registered rather than made silently, and the migration adds a path without removing one.
 *
 * <p>Assumptions: the sibling {@code account-service} deliberately has no outbox, and the asymmetry
 * between the two contexts is a decision rather than an omission. Its own inquiry program enlists
 * both the receive and the put in the unit of work, so a reply and the data it reports already stand
 * or fall together there and a durable row would guarantee nothing that is not already guaranteed.
 * Reading the absence there as an oversight, and adding an outbox to match, would add a table and a
 * drain to a path that has no window for them to close.
 *
 * <p>Assumptions: the columns a publisher reads off a claimed row each carry the meaning one field of
 * the reference message descriptor carried, and the mapping is recorded here because every one of
 * them has to keep that meaning for a published reply to be the same reply. The correlation identity
 * comes from L745, which moves the saved inbound identifier onto the reply descriptor exactly as it
 * arrived; that identifier is declared {@code PIC X(24)} at L45 and captured from the request at L411
 * to L412, so it originates in the request and is never defaulted at publication. The destination
 * comes from L741 to L742, which set the reply object name from a per-request queue name, so reply
 * routing is per message and dynamic rather than one fixed destination, and it therefore travels in
 * the row rather than being read from configuration by the publisher. The wire format comes from L751,
 * {@code MOVE MQFMT-STRING TO MQMD-FORMAT}, which is {@code text/csv} here; because the payload is
 * declared as a string format, the field order and the delimiter ARE the interface, which is a second
 * reason this layer must not reformat it. The reply deadline comes from L750, and the ordering and
 * deduplication identities ARE the card number and the transaction identifier respectively, the first
 * preserving per-card ordering and the second giving exactly-once acceptance at the queue.
 *
 * <p>Assumptions: the reply deadline is a column on this table and never a field inside the payload,
 * because the reference system carries it in the message DESCRIPTOR at L750 rather than in the
 * message body -- which is also why the sibling reply record carries no expiry component. Two units
 * meet at that point and are easy to conflate. The descriptor field at L750 is denominated in TENTHS
 * of a second, so the fifty moved into it is five seconds; the receive wait set at L242 and applied
 * at L393 is denominated in milliseconds, so the five thousand moved into it is also five seconds.
 * The target transport has no per-message time to live at all, so the deadline lives in the column, a
 * publisher declines a row whose instant has passed and a consumer drops and logs a stale reply,
 * backed by short retention on the reply queue -- the reference reply being non-persistent at L749 is
 * why that retention can be short. The gap and this resolution are recorded in
 * {@code docs/adr/ADR-004-messaging.md}.
 *
 * <p>Assumptions: the payload arrives ALREADY ENCODED and is opaque to this layer, which stores and
 * returns the bytes it was given and neither pads, trims, re-derives nor measures them. The reply is
 * the six items of {@code cpy/CCPAURLY.cpy} L19 to L24, whose declared widths sum to 57 characters of
 * field data, and that is the only length figure this file states. It deliberately states no total
 * wire length and no delimiter count, because more than one defensible figure exists: the emitter at
 * L722 to L731 appends a separator after the sixth item as well as between the pairs, and the control
 * field carried at L730 is declared with a non-zero initial value at L46 and is moved straight to the
 * buffer length at L756, so the count of assembled bytes and the count the transport is asked to send
 * are each arguable and upstream planning artifacts disagree. Adjudicating between them is not this
 * layer's business.
 *
 * <p>Trade-offs: the accepted cost of that opacity is that nothing is validated at the persistence
 * boundary, so an encoding fault surfaces at the consumer rather than at insert. It is accepted
 * because validating here would require this layer to adopt one of those conflicting figures as
 * settled, and a persistence layer that rejected a correctly-encoded reply on a length it had guessed
 * would discard the reply this table exists to guarantee. Encoding belongs to
 * {@code com.carddemo.common.codec.CsvAuthCodec} and the reply's shape to
 * {@code com.carddemo.authorization.dto.AuthorizationReplyPayload}; the absence of any dependency on
 * that codec in this file is the structural expression of the ruling, so the import is not to be added
 * here for validation.
 */
public interface OutboxRepository extends JpaRepository<AuthReplyOutbox, Long> {

    /**
     * Claims the oldest unpublished reply of each ordering group, oldest group first.
     *
     * <p>Assumptions: the claim is an atomic STATUS TRANSITION expressed on the row's CLAIM TOKEN. One
     * statement moves each row it takes from the token value it was observed at to the next one, and
     * returns only the rows whose transition it actually performed, so the row a caller receives has
     * already been claimed by the time it arrives. A second claim running at the same moment observes
     * the same candidate at its old token, tries the same transition and finds the comparison no longer
     * true, so it receives that row not at all rather than receiving it a second time.
     *
     * <p>Refactoring Rationale: the token is {@code claim_version} and it was {@code attempts}. Sharing
     * the attempt counter made the transition move for reasons that were not claims -- a recorded send
     * failure advanced it, and so did retiring an expired row that was never sent -- so a concurrent
     * claim of an untouched row could be refused by a change another pass's reporting had made, and the
     * counter's own value answered no question. {@code V2__authorization_outbox_claim_version.sql} adds
     * the dedicated token and records the overflow this sharing also caused.
     *
     * <p>Trade-offs: single delivery within one pass is a property of the DATA, but it is not the only
     * mechanism at work and the second one has to be stated or a reader will size a connection pool
     * wrongly. The claiming statement is an UPDATE, so it takes an ordinary row write lock on every row
     * it changes and holds that lock, and the connection, until the surrounding transaction ends -- which
     * for {@code OutboxPublisher.drain} is after every send of the pass has returned. A concurrent
     * publisher therefore BLOCKS on the row rather than merely observing a stale token, and only then
     * finds the comparison no longer true. The token is what makes the outcome correct; the write lock is
     * what makes a second publisher wait for it.
     *
     * <p>Alternatives Considered: taking a pessimistic row lock as part of the SELECTION and stepping
     * over rows another transaction already holds -- {@code for update skip locked} -- or requesting the
     * same lock through the persistence annotation that expresses it on a query method. Rejected, but
     * NOT on the ground that it would introduce locking where there is none: the statement below is an
     * UPDATE, so it takes an ordinary row write lock on every row it changes and a concurrent claim
     * waits for it. Refactoring Rationale: this paragraph asserted "lock-wait queueing and
     * deadlock-victim rollback where there is neither today", and the first half of that was false. The
     * true grounds are two. First, correctness here does not DEPEND on the lock -- the token comparison
     * decides, so a claim that waited and then found the token changed receives the row not at all,
     * whereas a skip-locked selection makes the lock itself the arbiter and therefore has to be
     * configured correctly to be safe. Second, deadlock is unreachable rather than merely unlikely,
     * because every claim orders its candidates by {@code outbox_id} ascending and so acquires in one
     * global order; a skip-locked variant would still need that ordering and would gain nothing from it.
     * The reference evidence remains relevant to the SHAPE rather than to the presence of locking:
     * {@code cpy/IMSFUNCS.cpy} declares all three get-hold retrieval codes at L19, L21 and L23 and no
     * program in the reference tree passes any of them to a data-language call, while both unload views
     * run get-only at {@code ims/PAUTBUNL.PSB} L18 and {@code ims/DLIGSAMP.PSB} L18 -- so the reference
     * never asked for a hold, and neither does this statement.
     *
     * <p>Alternatives Considered: draining without a bound, which would let one pass empty the whole
     * backlog. Rejected because a single cycle would then hold a transaction of arbitrary size and
     * would depart from the bounded-batch shape the source works in: the reference consumer declares
     * {@code WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500} at {@code cbl/COPAUA0C.cbl} L40 and
     * ends its loop at L339 once the processed count exceeds it. The bound is taken as a parameter here
     * rather than fixed in the statement, so the value is the caller's configuration and 500 is
     * recorded as the reference figure rather than compiled in.
     *
     * <p>Assumptions: the head of a group is its LOWEST IDENTITY and not its earliest creation instant.
     * The identity is generated strictly increasing and is unique, so it totally orders the rows of a
     * group, whereas the creation instant defaults to the statement timestamp and two rows written in
     * one transaction share it, leaving their relative order undefined and a bounded drain able to
     * repeat or omit one at a batch boundary. The migration states the same choice on the index that
     * serves this statement, so the schema and this method agree rather than each deciding.
     *
     * <p>Assumptions: at most one row per ordering group is claimed, and that is what preserves
     * per-card order rather than an incidental effect of the grouping. A first-in-first-out queue
     * orders messages within a group only after it has accepted them, so the sequence a publisher sends
     * in is the sequence the group is delivered in; a pass holding two rows of one group whose earlier
     * send did not succeed while the drain continued would place the later reply ahead of the earlier
     * one, reversing two answers for one card, which is the single guarantee the group identity exists to
     * provide. A group therefore stays at its own head until that head reaches a terminal state.
     *
     * <p>Assumptions: the statement names its table unqualified and resolves it through the connection
     * search path that {@code com.carddemo.authorization.config.DataSourceConfig} pins to this
     * context's schema. The dependency is named because breaking it there makes this statement silently
     * wrong rather than uncompilable: a connection resolving another schema first would read a
     * same-named table and return rows. Its column names are those
     * {@code src/main/resources/db/migration/V1__authorization.sql} declares for this table.
     *
     * <p>Refactoring Rationale: a claim advances the attempt counter EXACTLY ONCE and moves the row's
     * next-attempt instant to the caller's lease, and both corrections are recorded because an earlier
     * revision of this statement did neither. It incremented on the claim and the publisher incremented
     * again when it recorded a failure reason, so a failed publication was counted twice; and it left
     * the next-attempt instant alone, so the row stayed immediately eligible and the only thing keeping
     * a second publisher off it was the uncommitted row write -- which forced the caller to hold its
     * transaction open across the network send. Setting the instant forward makes the claim durable
     * instead, so the caller can commit and release its connection before it sends anything.
     *
     * <p>Refactoring Rationale: the candidate ordering is (next_attempt_at, outbox_id) rather than
     * outbox_id alone, and the abandonment and attempt-ceiling predicates are new. Ordering by identity
     * alone made the ready set the globally oldest pending rows, so a handful of permanently failing
     * heads were reselected on every poll and, at a batch of that size, every healthy group behind them
     * was STARVED -- a liveness failure retrying could not clear, because the failing rows never stopped
     * being the oldest. A failed row's backoff now pushes its instant forward, which yields its place to
     * a group whose instant has arrived; and a row that has exhausted the caller's attempt ceiling, or
     * that the caller has abandoned, leaves the ready set altogether.
     *
     * <p>Trade-offs: a concurrent claim may return fewer rows than its bound, up to none at all, because
     * the candidates it observed were taken by the pass it raced; those rows are leased to that pass and
     * a later pass takes them once their lease lapses, so the cost is a pass that does less work rather
     * than a reply that goes unsent.
     *
     * <p>Trade-offs: the statement is NATIVE rather than expressed in the query language, because a
     * transition that returns the rows it changed has no portable equivalent there. The accepted cost
     * is that the table and column names appear in this string, which is why the unqualified-name
     * assumption above is stated on this method rather than left to the entity mapping to imply.
     *
     * @param batchSize the greatest number of ordering groups to claim a head row from in this pass,
     *     as a positive count; the reference consumer's own per-invocation figure is 500
     * @param now the instant readiness is judged against, in coordinated universal time; a row whose
     *     next-attempt instant is after it is not a candidate; must not be {@code null}
     * @param leaseUntil the instant each claimed row's next attempt is deferred to, which is how long
     *     this pass owns the row if it neither completes nor fails it; must not be {@code null}
     * @param maxAttempts the attempt count at or above which a row is no longer a candidate, so a
     *     permanently failing reply leaves the ready set instead of being retried forever
     * @return the rows this call has ALREADY CLAIMED, one per ordering group and oldest-ready group
     *     first, so a caller publishes them and does not claim them again; empty when nothing is ready
     *     or when a concurrent pass took every candidate this one observed
     * @throws org.springframework.dao.DataAccessException when the statement cannot be executed, for
     *     instance because the connection's search path does not resolve the table
     */
    @Query(value = """
            with head as (
                select h.outbox_id, h.claim_version
                  from auth_reply_outbox h
                 where h.published_at is null
                   and h.abandoned_at is null
                   and h.next_attempt_at <= :now
                   and h.attempts < :maxAttempts
                   and h.outbox_id = (select min(g.outbox_id)
                                        from auth_reply_outbox g
                                       where g.published_at is null
                                         and g.abandoned_at is null
                                         and g.order_group_id = h.order_group_id)
                 order by h.next_attempt_at, h.outbox_id
                 limit :batchSize
            ),
            claimed as (
                update auth_reply_outbox o
                   set claim_version = o.claim_version + 1,
                       attempts = o.attempts + 1,
                       next_attempt_at = :leaseUntil
                  from head
                 where o.outbox_id = head.outbox_id
                   and o.claim_version = head.claim_version
                   and o.published_at is null
                   and o.abandoned_at is null
                returning o.*
            )
            select * from claimed order by outbox_id
            """, nativeQuery = true)
    List<AuthReplyOutbox> claimGroupHeads(@Param("batchSize") int batchSize,
            @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("maxAttempts") int maxAttempts);

    /**
     * Claims the next unpublished replies of one ordering group, above the identity just handled.
     *
     * <p>Assumptions: this carries the same transition discipline as the head claim above -- the same
     * comparison against the observed claim token, the same write lock that comparison's UPDATE takes,
     * the same unqualified table name resolved through the pinned search path -- and those rulings are
     * not restated here. What differs is only which rows are candidates.
     *
     * <p>Alternatives Considered: not offering this method at all, and letting a group advance by one
     * reply per claim of its head. Rejected because per-group claiming would then bound a group's
     * throughput to one reply per drain interval however long its backlog, which is a throughput
     * characteristic the reference consumer does not have: it processes up to its own batch limit within
     * one invocation. Advancing within a pass is safe against reordering for a specific reason -- the
     * rows are claimed in ascending identity order and each send is awaited before the next is issued --
     * so the ordering guarantee the group identity carries is not weakened by draining further.
     *
     * <p>Assumptions: the candidate set is bounded BELOW by the identity just handled rather than by
     * re-reading the group's head, and that lower bound is what makes the method independent of when a
     * caller's pending in-memory changes reach the database. A row this pass has already handled sits at
     * or below the bound and cannot be a candidate whatever its stored state currently says, so the
     * method cannot hand the same row back twice within one pass. The predicate is the group identity and
     * the identity over unpublished rows only, which is exactly the key and the condition of the second
     * partial index the migration declares for this table, so it is an index scan rather than a scan of
     * the whole pending set.
     *
     * @param orderGroupId the ordering group to advance, which is the card number stored on the row as
     *     the technical specification freezes the group identity; must not be {@code null}
     * @param afterOutboxId the identity of the row just handled, as the exclusive lower bound; only
     *     higher identities of the same group are candidates
     * @param batchSize the greatest number of follow-on rows to claim in this call, as a positive count
     * @param now the instant readiness is judged against, in coordinated universal time; a row whose
     *     next-attempt instant is after it is not a candidate; must not be {@code null}
     * @param leaseUntil the instant each claimed row's next attempt is deferred to, which is how long
     *     this pass owns the row if it neither completes nor fails it; must not be {@code null}
     * @param maxAttempts the attempt count at or above which a row is no longer a candidate, so a
     *     permanently failing reply leaves the ready set instead of being retried forever
     * @return the rows this call has ALREADY CLAIMED, in ascending identity order, so a caller publishes
     *     them and does not claim them again; empty when the group holds no further pending row or when
     *     a concurrent pass took the candidates this one observed
     * @throws org.springframework.dao.DataAccessException when the statement cannot be executed, for
     *     instance because the connection's search path does not resolve the table
     */
    @Query(value = """
            with follower as (
                select f.outbox_id, f.claim_version
                  from auth_reply_outbox f
                 where f.published_at is null
                   and f.abandoned_at is null
                   and f.next_attempt_at <= :now
                   and f.attempts < :maxAttempts
                   and f.order_group_id = :orderGroupId
                   and f.outbox_id > :afterOutboxId
                 order by f.outbox_id
                 limit :batchSize
            ),
            claimed as (
                update auth_reply_outbox o
                   set claim_version = o.claim_version + 1,
                       attempts = o.attempts + 1,
                       next_attempt_at = :leaseUntil
                  from follower
                 where o.outbox_id = follower.outbox_id
                   and o.claim_version = follower.claim_version
                   and o.published_at is null
                   and o.abandoned_at is null
                returning o.*
            )
            select * from claimed order by outbox_id
            """, nativeQuery = true)
    List<AuthReplyOutbox> claimGroupFollowers(@Param("orderGroupId") String orderGroupId,
            @Param("afterOutboxId") long afterOutboxId, @Param("batchSize") int batchSize,
            @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("maxAttempts") int maxAttempts);

    /**
     * Deletes replies that were published before a stated cut-off instant.
     *
     * <p>Assumptions: the predicate requires a publication instant to be PRESENT, so an unpublished row
     * can never be selected however old it is. That is the one property this method must have. An
     * unpublished row is the reply this table exists to guarantee, and deleting one would discard an
     * answer the committed data says was produced, reintroducing from the retention side exactly the
     * outcome the row was written to prevent.
     *
     * <p>Alternatives Considered: removing a row as it is published, which would keep the table empty
     * and need no sweep. Rejected because a short published history is what lets an operator answer
     * whether a reply was ever sent for a given transaction, which is the question the reference
     * system's separate queue and database units of work leave unanswerable. The migration takes the
     * same position on the publication column, recording publication by setting it rather than by
     * deleting the row, so the schema and this method agree. The retention window is the caller's
     * parameter rather than a figure fixed here, so the answer stays available for as long as an
     * operator needs it and no longer.
     *
     * <p>Trade-offs: this is a bulk statement rather than a load-then-remove loop, so it does not pass
     * through the persistence context and no entity callback runs. Nothing on this entity declares a
     * callback, and the accepted alternative would read a retention window's worth of payloads -- each
     * of which carries a primary account number -- into memory purely to delete them one at a time.
     *
     * <p>Refactoring Rationale: the statement deletes a BOUNDED, ORDERED CHUNK rather than everything
     * matching the cut-off, and it is native rather than expressed in the query language because the
     * ordered-and-limited sub-select has no portable equivalent there. An earlier revision deleted the
     * whole matching population in one statement, which is unbounded by construction -- the published
     * side of this table is the part that grows without limit -- so a sweep that had not run for a while,
     * or a widened window, took a table-wide lock footprint and a transaction whose duration nothing
     * bounded. Deleting oldest-first in fixed slices lets the caller loop until a pass deletes fewer
     * rows than the slice, with each slice its own short transaction. The predicate is also served by
     * {@code idx_auth_reply_outbox_published}, which was added with this change; the two partial indexes
     * over the pending side exclude these rows by their own predicate, so before it this delete was a
     * sequential scan of the whole table on every pass.
     *
     * <p>Assumptions: an ABANDONED reply is excluded, so the sweep never removes one. An abandoned row
     * is a reply the committed decision says was owed and that was never delivered; deleting it on an
     * operational timer would destroy the only evidence of that, so it is retained until an operator
     * who has read it removes it.
     *
     * @param cutoff the instant a published row must precede to be deleted, in coordinated universal
     *     time; must not be {@code null}
     * @param chunkSize the greatest number of rows to delete in this call, as a positive count, so one
     *     transaction's work is bounded however large the eligible population is
     * @return how many rows were deleted, which is zero when nothing published precedes the cut-off and
     *     is the chunk size when more rows remain eligible than one call removes
     * @throws org.springframework.dao.DataAccessException when the statement cannot be executed, for
     *     instance because the runtime role holds no delete privilege on the table
     */
    @Modifying
    @Query(value = """
            delete from auth_reply_outbox
             where outbox_id in (select d.outbox_id
                                   from auth_reply_outbox d
                                  where d.published_at is not null
                                    and d.published_at < :cutoff
                                    and d.abandoned_at is null
                                  order by d.published_at, d.outbox_id
                                  limit :chunkSize)
            """, nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") LocalDateTime cutoff,
            @Param("chunkSize") int chunkSize);

    /**
     * Counts the replies still awaiting publication.
     *
     * <p>Assumptions: this is a backlog gauge for a monitor and is not a pagination total; nothing on
     * this boundary pages by position, and no caller receives this figure as an argument. A backlog that
     * grows without bound means the publisher is failing rather than merely busy, and the reply queue's
     * own depth cannot show it, because a reply that has not been published has never reached the queue.
     *
     * @return how many rows carry no publication instant
     * @throws org.springframework.dao.DataAccessException when the count cannot be executed, for
     *     instance because the connection's search path does not resolve the table
     */
    long countByPublishedAtIsNull();
}
