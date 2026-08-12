package com.carddemo.authorization.repository;
import com.carddemo.authorization.domain.PendingAuthSummary;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the per-account pending-authorization summary.
 *
 * <p>This is the migrated form of the access the baseline performs against the ROOT segment
 * {@code PAUTSUM0} of the hierarchical database declared at
 * {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd} L28, whose hundred-byte layout is set out
 * field by field at {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} L19 to L31. Exactly
 * three movements over that segment appear across the eight reference programs, and all three are
 * below: a keyed read of one account, a whole-segment rewrite, and a sequential walk of every root in
 * key order.</p>
 *
 * <p>Assumptions: three movements are served by FOUR declarations, because two of them are declared
 * twice at different strengths and the walk is declared twice at different widths. The keyed read
 * appears once as a guarded modifying statement and once as a plain read; the walk appears once
 * returning whole summaries and once returning only the keys they are addressed by. Refactoring
 * Rationale: the key-projected walk was added for the purge, which DELETES from the very table it is
 * stepping through -- so a page of whole summaries is a page of snapshots that a concurrent purge
 * window or a live authorization may already have moved past, and a caller deciding a deletion from
 * one of them would decide from state the row no longer holds. Projecting the walk to keys lets each
 * summary be read individually once the window's position is fixed, and the alternative of refreshing
 * a loaded instance was rejected as a second statement doing what one correctly-ordered statement
 * already does.</p>
 *
 * <p>The rulings this interface inherits rather than restates -- where the transaction boundary lives,
 * why nothing on this boundary masks a value, why every monetary member is
 * {@link java.math.BigDecimal}, and why the shared kernel is the only intra-reactor dependency
 * available here -- are stated once in this package's {@code package-info.java}. Read that file for
 * them; what follows is only what is specific to this one shape.</p>
 *
 * <p>Assumptions: the write is the INHERITED whole-entity save, and that is the shape the baseline's
 * own update takes rather than a convenience taken here. {@code cbl/COPAUA0C.cbl} L824 to L834 selects
 * between {@code EXEC DLI REPL} and {@code EXEC DLI ISRT} on whether the segment was found, and both
 * statements pass the entire segment, {@code FROM (PENDING-AUTH-SUMMARY)}: replace when found and
 * insert when not, over the whole hundred bytes rather than over the fields that changed. The
 * alternative considered was the narrow two-column upsert that the fraud shape on this same boundary
 * uses, applied here for symmetry; its concrete consequence is that the counters and totals the
 * decision path adds to are not among the columns such a statement names, so they would keep their
 * previous values and the row would come out of a successful write unchanged in exactly the fields the
 * write existed to change. No bulk counter-update query is declared either: the arithmetic that
 * adjusts a parent's totals per qualifying child -- {@code cbl/CBPAUP0C.cbl} L287 to L292, which
 * subtracts one and an amount from the approved pair when the response code is {@code '00'} and from
 * the declined pair otherwise -- belongs to the service that composes the unit of work, so this
 * boundary reads the row and saves it back and holds none of that logic.</p>
 *
 * <p>Refactoring Rationale: the key is the account identifier ALONE, and the consequence of the
 * baseline's structure is that it could not have been anything else. The root carries one unique
 * sequence field, {@code FIELD NAME=(ACCNTID,SEQ,U),START=1,BYTES=6,TYPE=P} at
 * {@code ims/DBPAUTP0.dbd} L30, and the only qualified root retrieval in any of the programs matches
 * on that field and nothing beside it: {@code cbl/COPAUS0C.cbl} L973 to L977 issues
 * {@code EXEC DLI GU} against {@code SEGMENT (PAUTSUM0)} {@code WHERE (ACCNTID = PA-ACCT-ID)}, having
 * moved the account identifier into that field at L971. The alternative key move on the intervening
 * L972 is commented out, so it is dead scaffolding rather than a second access path. The alternative
 * considered here was a composite key mirroring the CHILD segment's {@code (account_id, auth_date,
 * auth_time)}, on the reading that the two shapes in one schema ought to be keyed alike. Its concrete
 * consequences are two: {@code findById} and {@code existsById} would take a key class instead of an
 * identifier and the five callers that pass a bare account would no longer compile against them; and
 * the schema would admit several summary rows for one account, a state the reference root cannot
 * represent at all, so the arithmetic that adds to a counter would have no single row to add to. The
 * migration settles it in the same direction, declaring
 * {@code CONSTRAINT pk_pending_auth_summary PRIMARY KEY (account_id)}, which is why the type argument
 * below is {@link Long} and not a key class.</p>
 *
 * <p>Assumptions: {@code pending_auth_summary} needs NO secondary index, and this is stated
 * affirmatively because the opposite reading is available in the reference material and would be
 * wrong. A second database description exists, {@code ims/DBPAUTX0.dbd}, whose access method is
 * declared {@code ACCESS=(INDEX,VSAM,PROT)} at its L18, and a database whose entire content is an
 * index invites being read as an alternate access path owed a target equivalent. It is not one. That
 * file declares a single six-byte root {@code SEGM NAME=PAUTINDX,PARENT=0,BYTES=6} at L27 and L28,
 * gives it the sequence field {@code FIELD NAME=(INDXSEQ,SEQ,U),START=1,BYTES=6,TYPE=P} at L29, and
 * points it back with {@code LCHILD NAME=(PAUTSUM0,DBPAUTP0), INDEX=ACCNTID} at L30 and L31, while
 * {@code ims/DBPAUTP0.dbd} carries the reciprocal {@code LCHILD ... POINTER=INDX} at L31 and L32 and
 * declares its own access method {@code ACCESS=(HIDAM,VSAM)} at L18. A HIDAM database is required to
 * carry a primary index over its root's sequence field, and the six packed bytes of {@code INDXSEQ}
 * are the six packed bytes {@code ACCNTID} declares: the index inverts the root's own key, not a
 * second field. An index over a field other than the key would have to name that field in an
 * {@code XDFLD} statement, and a search of the whole reference tree finds no {@code XDFLD} in it --
 * not in this context and not in any other. The equivalent of {@code DBPAUTX0} is therefore the
 * PRIMARY KEY itself. The alternative considered was to add an index on
 * {@code pending_auth_summary(account_id)}; its concrete consequence is a second B-tree over the same
 * single column as the primary key, maintained on every insert, update and delete of this table, and
 * answering no query the primary key does not already answer. The specification's warning that
 * alternate indexes are access paths and not decoration is about the record-file masters
 * {@code CARDAIX}, {@code CXACAIX} and {@code TRANSACT.VSAM.AIX}, and not one of those three belongs
 * to this context; the only directional index this module owns is {@code (card_num ASC, auth_ts DESC)}
 * on {@code auth_fraud}, which is a different table reached through a different interface.</p>
 *
 * <p>Assumptions: the key type and the absence of a version member are the {@code domain} package's
 * declarations, consumed here as declared rather than decided here. {@link PendingAuthSummary}
 * declares {@code Long} as its single-column identifier and declares no optimistic-lock version
 * member, and the migration that owns the table declares no version column either. Nothing on this
 * boundary introduces one, and nothing here introduces an identifier class. The alternative -- naming
 * a version member or a key class on this interface because the surrounding contexts have them -- has
 * a concrete consequence: the mapping would assert a column the migration does not create, and the
 * failure arrives at schema validation on startup rather than at compilation, so it would be found by
 * deploying rather than by building.</p>
 *
 * <p>Assumptions: the table this interface reaches is named unqualified and resolves through the
 * connection {@code search_path} that {@code com.carddemo.authorization.config.DataSourceConfig}
 * verifies against the migration's own schema. That dependency is named here, and not left to the
 * package charter alone, because a query written against this interface is silently wrong if the
 * assumption is broken in that one: a connection resolving elsewhere first would read a same-named
 * table in another schema and return rows rather than fail. The alternative was to schema-qualify
 * every statement; its concrete consequence is the schema name compiled into every query in the
 * package, so that pinning it in one place and naming it in every place become two facts that can
 * disagree -- and in this database the qualification would additionally have to carry embedded quotes
 * at every occurrence, because {@code authorization} is a reserved word there.</p>
 */
public interface PendingAuthSummaryRepository extends JpaRepository<PendingAuthSummary, Long> {

    // WHY : Refactoring Rationale: a findWithLockByAccountId declared under
    //       @Lock(LockModeType.PESSIMISTIC_WRITE) stood here and has been WITHDRAWN. It was documented as
    //       a deliberate target-side divergence, and the evidence in that documentation was right: the
    //       baseline holds nothing on this path -- cpy/IMSFUNCS.cpy declares all three get-hold function
    //       codes (FUNC-GHU L19, FUNC-GHN L21, FUNC-GHNP L23) and no program in the reference tree passes
    //       any of them, the codes actually passed being the non-hold FUNC-GU, FUNC-GN and FUNC-GNP, with
    //       both unload views running PROCOPT=GOTP at ims/PAUTBUNL.PSB L18 and ims/DLIGSAMP.PSB L18. A
    //       pessimistic row lock is therefore concurrency machinery the reference system does not have,
    //       and it is not admissible here.
    // WHY : Assumptions: the PROBLEM that documentation identified is real and still has to be solved.
    //       These four members are INCREMENTED, not assigned -- cbl/COPAUA0C.cbl adds to the approved
    //       count and amount at L814 and L815 and to the declined pair at L820 and L821, and
    //       cbl/CBPAUP0C.cbl subtracts from the same four at L287 to L292 -- so two interleaved
    //       read-modify-write sequences lose one contribution and store a total that is simply short,
    //       with nothing in the result to show it. What was wrong was the remedy, not the diagnosis.
    // WHY : Refactoring Rationale: the remedy is to stop reading-then-writing at all. The three
    //       @Modifying statements below perform the arithmetic IN THE DATABASE, so each contribution is
    //       applied to whatever the row holds at the moment the statement runs. Two concurrent
    //       contributions therefore both land -- the engine serialises the two updates on the row and
    //       each adds to the other's result -- which is the property the lock was reached for, obtained
    //       without holding anything across application logic. The decision that precedes them reads the
    //       summary through the non-locking findByAccountId below.
    // WHY : Refactoring Rationale: making the accumulation safe was NOT sufficient, and the first of the
    //       three statements carries a guard for what it left open. Two requests on two different cards of
    //       one account are delivered concurrently by a queue grouped on card number, so both read the same
    //       headroom, both approve, and both contributions then land -- which is the correct accumulation of
    //       an incorrect pair of decisions, and the account's credit balance ends above its credit limit.
    //       The approval statement is therefore QUALIFIED on the same credit check the decision made, so the
    //       engine re-evaluates it against the row as it stands and the caller derives approval from whether
    //       the row changed. Assumptions: only the APPROVAL needs the guard. A decline consumes no credit,
    //       so its counters have nothing to exceed, and the purge reversal only ever reduces.
    // WHY : Alternatives Considered: (a) an optimistic @Version column, which the previous
    //       documentation also rejected. Still rejected, and for a stronger reason than it gave: the
    //       migration declares no version column on this table, and a version check detects a collision
    //       only at commit and then discards decision work a retry has to redo -- whereas an atomic
    //       increment has no collision to detect. (b) SELECT ... FOR UPDATE expressed natively instead of
    //       through @Lock, which is the same lock wearing different clothes. (c) serialising the consumer
    //       to one task, which would preserve the baseline's one-message-at-a-time model exactly but
    //       throw away the throughput the queue's per-card ordering exists to permit.
    // WHY : Trade-offs: the decision now reads counters that a concurrent contribution may already have
    //       moved, where the lock made the reader wait. That is accepted, and it is closer to the
    //       baseline rather than further from it: the baseline reads its root without a hold and decides
    //       on what it read, so a decision made against a summary another task is concurrently updating
    //       is the behaviour being preserved. What must not be lost is the ACCUMULATION, and that is
    //       exactly what these statements make safe.

    /**
     * Copies an account master's two limits onto its stored summary, touching nothing else.
     *
     * <p>Purpose: this is {@code MOVE ACCT-CREDIT-LIMIT TO PA-CREDIT-LIMIT} and
     * {@code MOVE ACCT-CASH-CREDIT-LIMIT TO PA-CASH-LIMIT} at {@code cbl/COPAUA0C.cbl} L810 and L811,
     * expressed as a statement rather than as a field assignment on a loaded instance.</p>
     *
     * <p>Refactoring Rationale: the consumer used to perform this refresh by mutating the managed
     * summary and calling the inherited {@code save}, and that combination LOST CONCURRENT
     * CONTRIBUTIONS. The mapping declares neither a version member nor Hibernate's dynamic-update
     * marker, so the pending change flushed as a WHOLE-ROW update carrying every column from the
     * instance's load-time snapshot -- including the four accumulators. The flush is triggered by the
     * very next statement, because the additive queries below are bulk operations and the provider
     * flushes before running one. So a contribution another transaction had committed after this
     * transaction's read was overwritten with the older counters, and only then was this decision's own
     * contribution added on top of them: the other party's authorization vanished from the account's
     * totals with nothing anywhere reporting it. Assumptions: the exposure is reachable rather than
     * theoretical -- the request queue orders by MESSAGE GROUP and the group is the CARD NUMBER, so two
     * cards belonging to one account are two groups and are delivered in parallel.
     *
     * <p>Assumptions: this statement is safe to interleave with the additive statements below BECAUSE
     * THEIR COLUMN SETS ARE DISJOINT. The two limits are ASSIGNED from the account master -- the
     * reference moves them, so a later write simply wins and there is nothing for an atomic statement
     * to protect -- while the counters, the totals and the held balance are INCREMENTED and are the
     * only members with a lost-update exposure. Two statements over disjoint columns cannot displace
     * one another whichever order the engine serialises them in, which is what makes the pair correct
     * where the entity write was not.
     *
     * <p>Alternatives Considered: (a) folding the limits into each additive statement, giving one
     * statement per arm and four queries in place of three. Rejected because the assignment and the
     * accumulation come from different paragraphs of the reference and are reached on different
     * conditions -- the refresh happens whenever the account master was read, the accumulation on
     * exactly one arm -- so combining them would make a caller that read no account master unable to
     * contribute at all without a second pair of statements. (b) adding a version member to the
     * mapping so the whole-row write could be retried on collision. Rejected for the reason the header
     * above already gives for rejecting it as a lock substitute: the migration declares no version
     * column on this table, and a version check discards decision work a retry then has to redo,
     * whereas an atomic statement has no collision to detect.
     *
     * @param accountId the account whose summary receives the refreshed limits; must not be
     *     {@code null}
     * @param creditLimit the account master's credit limit, exact at scale two; must not be
     *     {@code null}
     * @param cashCreditLimit the account master's cash credit limit, exact at scale two; must not be
     *     {@code null}
     * @return {@code 1} when the account had a summary and it was updated, {@code 0} when it had none
     */
    @Modifying
    @Query("""
            update PendingAuthSummary s
               set s.creditLimit = :creditLimit,
                   s.cashLimit = :cashCreditLimit
             where s.accountId = :accountId
            """)
    int refreshStoredLimits(@Param("accountId") Long accountId,
            @Param("creditLimit") BigDecimal creditLimit,
            @Param("cashCreditLimit") BigDecimal cashCreditLimit);

    /**
     * Reserves an approved authorization's amount only while the account's own limit still admits it.
     *
     * <p>Purpose: this is {@code cbl/COPAUA0C.cbl} L814 and L815 together with the credit balance the same
     * paragraph moves at L817 and the cash balance it assigns at L818, qualified additionally on the credit
     * check the decision was made against. All four members move in ONE statement so a concurrent
     * contribution to the same row cannot displace this one, and the qualification is there because an
     * atomic increment makes the ACCUMULATION safe and leaves the CREDIT DECISION unsafe -- two different
     * properties of the same row.</p>
     *
     * <p>Refactoring Rationale: an UNGUARDED {@code addApprovedAuthorization} stood here and is withdrawn
     * rather than kept beside this one. Keeping both would leave a statement that applies an approval
     * without checking the limit available to any later caller, one method name away from the one that
     * checks -- and the defect this method corrects was precisely an approval applied against headroom that
     * had already been spent. There is one way to apply an approval and it is the checked way.</p>
     *
     * <p>Assumptions: the credit balance moves with the approved pair and not separately, because the
     * entity's own {@code recordApproved} moves all three together and the three are meaningless apart --
     * an approved total that has advanced while the balance has not describes an account no reference
     * program could produce. The cash balance is ASSIGNED zero, because {@code MOVE 0 TO PA-CASH-BALANCE}
     * at L818 is an assignment rather than an accumulation; it is in this statement anyway so that an
     * approval reaches the row exactly once, and its effect is not vacuous -- a summary the extract load
     * rehydrated carries whatever cash balance the stored segment held, and the reference program zeroes it
     * on the first approval thereafter.</p>
     *
     * <p>Refactoring Rationale: the queue is a FIFO queue grouped by card number, so two requests on two
     * DIFFERENT cards of one account are delivered concurrently and decided concurrently. Both read the
     * summary through the non-locking {@link #findByAccountId(Long)}, both compute the same
     * {@code creditLimit - creditBalance} headroom, both find their amount fits, and both then add
     * atomically -- so both contributions land, exactly as intended, and the resulting credit balance
     * exceeds the credit limit. The atomic increment is what makes that outcome reliable rather than
     * intermittent. Nothing in the row afterwards records that a limit was breached, and the reference
     * system cannot produce the state at all because it decides one message at a time.</p>
     *
     * <p>Assumptions: the guarded predicate is {@code creditLimit - creditBalance >= :amount} and the
     * INCLUSIVE operator is required rather than incidental. The reference declines only when the
     * requested amount is STRICTLY GREATER than the available amount --
     * {@code IF WS-TRANSACTION-AMT > WS-AVAILABLE-AMT} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L668 and L676 -- so a request for exactly
     * the available amount is approved. A strict predicate here would refuse the reservation the deciding
     * service admitted, and the two would disagree at precisely the boundary value both are written
     * around.</p>
     *
     * <p>Assumptions: the predicate reads the row's OWN stored members rather than a value the caller
     * carries, which is what makes the reservation atomic. The engine evaluates the condition and applies
     * the four assignments in one statement against the row as it stands at that moment, so a
     * contribution committed by a concurrent authorization between this transaction's read and this
     * statement is already included in the {@code creditBalance} the predicate subtracts. Passing the
     * headroom the caller computed would restore the very stale-read window this method closes.</p>
     *
     * <p>Alternatives Considered: (a) a pessimistic row lock taken before the decision, which is what
     * previously stood on this boundary and was withdrawn for the reason recorded above -- the reference
     * passes no get-hold function code anywhere, so a held row is concurrency machinery the reference
     * system does not have, and it additionally holds the row across up to three outbound HTTP calls.
     * (b) An optimistic version column, which detects the collision only at commit and then discards the
     * decision work a retry must redo, on a table whose migration declares no version column. (c)
     * Serialising the consumer to one task, which reproduces the reference's one-message-at-a-time model
     * exactly and throws away the throughput the queue's per-card grouping exists to permit. A guarded
     * update takes no lock, adds no column, needs no retry and leaves the concurrency the grouping
     * allows.</p>
     *
     * <p>Trade-offs: the caller cannot tell a refused reservation from an absent row by the row count
     * alone, because both answer zero. That is accepted rather than worked around with a second
     * statement: the only caller reaches this method with a summary it has already read or re-read, so
     * the row's existence is established before the statement runs and a zero can only mean the headroom
     * went. A caller without that guarantee must establish it first.</p>
     *
     * <p>⚠️ Assumptions: the approved total and the credit balance are SATURATED at the ceiling the caller
     * supplies, being the greatest magnitude those columns can hold. The guard above bounds this statement's
     * ADDEND against the account's own headroom and so cannot bound the RUNNING TOTAL, because the limit the
     * headroom is measured from is itself stored in one of these narrower columns and is refreshed from a
     * wider account-master field. So a high-limit account reaches the column's bound by accumulation while
     * every individual authorization it approved was inside its limit, and without the saturation the
     * numeric-overflow error that follows rolls back a decision the requester never receives. The reference
     * reaches the same bound by discarding high-order digits at {@code cbl/COPAUA0C.cbl} L815 and L817; the
     * choice of saturation over that is argued on {@code PendingAuthSummary.MONEY_MAX_MAGNITUDE}.
     *
     * <p>Assumptions: only the UPPER bound is expressed, because this statement only ever adds and its
     * addend is a non-negative approved amount, so neither member can be driven below the column's negative
     * bound here. The expiry sweep's statement, which subtracts, bounds both ends.
     *
     * @param accountId the account whose summary receives the reservation; must not be {@code null}
     * @param amount the approved amount to reserve against the account's limit; must not be {@code null}
     * @param ceiling the greatest magnitude the approved total and the credit balance may reach, being
     *     {@code PendingAuthSummary.MONEY_MAX_MAGNITUDE}; must not be {@code null}
     * @return {@code 1} when the limit still admitted the amount and the contribution was applied,
     *     {@code 0} when it did not -- or when the account carries no summary at all
     */
    @Modifying
    @Query("""
            update PendingAuthSummary s
               set s.approvedAuthCount = s.approvedAuthCount + 1,
                   s.approvedAuthAmount = case
                       when s.approvedAuthAmount + :amount > :ceiling then :ceiling
                       else s.approvedAuthAmount + :amount end,
                   s.creditBalance = case
                       when s.creditBalance + :amount > :ceiling then :ceiling
                       else s.creditBalance + :amount end,
                   s.cashBalance = 0
             where s.accountId = :accountId
               and s.creditLimit - s.creditBalance >= :amount
            """)
    int reserveApprovedAuthorization(@Param("accountId") Long accountId,
            @Param("amount") BigDecimal amount, @Param("ceiling") BigDecimal ceiling);

    /**
     * Adds one declined authorization's contribution to an account's summary, atomically.
     *
     * <p>Purpose: this is the write half of {@code cbl/COPAUA0C.cbl} L820 and L821.</p>
     *
     * <p>Assumptions: the credit balance is deliberately NOT moved here, matching the reference
     * paragraph, which moves it on the approved arm only. A declined authorization consumes no credit,
     * so advancing the balance would overstate what the account has committed.
     *
     * <p>⚠️ Assumptions: the running total is SATURATED at the ceiling the caller supplies, which is the
     * greatest magnitude the column can hold. This statement is the most exposed of the three additive ones:
     * the approved arm is qualified on the account's own headroom, so its addend cannot exceed a limit that
     * itself fits the column, while a decline has no such gate and adds a {@code PIC S9(10)V99} requested
     * amount to a {@code PIC S9(09)V99} total whatever its size. Without the saturation a single request of
     * one thousand million raised a numeric-overflow error that rolled the whole decision back, so the
     * requester received no answer at all and the message dead-lettered after five receives -- and repeated
     * in-domain declines could reach the same state by accumulation. The reference reaches its own version of
     * this bound by discarding high-order digits at {@code cbl/COPAUA0C.cbl} L821; the choice of saturation
     * over that is argued on {@code PendingAuthSummary.MONEY_MAX_MAGNITUDE}.
     *
     * <p>Assumptions: only the UPPER bound is expressed, because the addend on this path is never negative
     * -- the wire contract refuses a negative requested amount -- so the sum cannot fall below the column's
     * negative bound through this statement. The expiry sweep's statement, which subtracts, bounds both ends.
     *
     * @param accountId the account whose summary receives the contribution; must not be {@code null}
     * @param amount the requested amount to add to the declined total; must not be {@code null}
     * @param ceiling the greatest magnitude the declined total may reach, being
     *     {@code PendingAuthSummary.MONEY_MAX_MAGNITUDE}; must not be {@code null}
     * @return {@code 1} when the account had a summary and it was updated, {@code 0} when it had none
     */
    @Modifying
    @Query("""
            update PendingAuthSummary s
               set s.declinedAuthCount = s.declinedAuthCount + 1,
                   s.declinedAuthAmount = case
                       when s.declinedAuthAmount + :amount > :ceiling then :ceiling
                       else s.declinedAuthAmount + :amount end
             where s.accountId = :accountId
            """)
    int addDeclinedAuthorization(@Param("accountId") Long accountId,
            @Param("amount") BigDecimal amount, @Param("ceiling") BigDecimal ceiling);

    /**
     * Removes an expired authorization's contribution from an account's summary, atomically.
     *
     * <p>Purpose: this is {@code cbl/CBPAUP0C.cbl} L287 to L292, which subtracts from the same four
     * members the consumer adds to. It is one statement for the same reason the two above are: the
     * purge runs while the consumer may be adding to the row it is subtracting from.</p>
     *
     * <p>Assumptions: BOTH pairs are expressed in one statement rather than two, because a purged
     * authorization is either approved or declined and the caller supplies zero for the arm that does
     * not apply. Two statements would leave a window in which one pair had been reversed and the other
     * had not, which is a state no reference program produces.
     *
     * <p>Assumptions: the counts are decremented by the counts the caller supplies rather than by one,
     * because the purge reverses a whole account's expired children in one pass and the reference
     * paragraph subtracts accumulated totals rather than stepping one at a time.
     *
     * <p>⚠️ Assumptions: both totals are SATURATED at the supplied bound in BOTH directions, because this
     * statement subtracts and the amounts it subtracts come from the wider columns of
     * {@code pending_auth_detail}. An authorization of {@code PIC S9(10)V99} reversed out of a
     * {@code PIC S9(09)V99} total drives the result an order of magnitude below the column's negative bound,
     * and the numeric-overflow error that produced abended the whole run: earlier windows stayed committed,
     * the table was left partly purged, and no further retention could ever complete while such a row
     * existed. Saturating keeps the sweep running over the rest of the table. The reversal is allowed to go
     * negative within the domain, which is divergence D-G recorded on {@code PurgeJob} -- the bound is the
     * column's, not a floor at zero.
     *
     * @param accountId the account whose summary is reduced; must not be {@code null}
     * @param approvedCount how many approved authorizations are being reversed; must not be negative
     * @param approvedAmount their total amount; must not be {@code null}
     * @param declinedCount how many declined authorizations are being reversed; must not be negative
     * @param declinedAmount their total amount; must not be {@code null}
     * @param ceiling the greatest magnitude either total may reach, being
     *     {@code PendingAuthSummary.MONEY_MAX_MAGNITUDE}; must not be {@code null}
     * @param floor the negation of {@code ceiling}, supplied rather than computed in the statement because
     *     the query language has no unary negation of a parameter; must not be {@code null}
     * @return {@code 1} when the account had a summary and it was updated, {@code 0} when it had none
     */
    @Modifying
    @Query("""
            update PendingAuthSummary s
               set s.approvedAuthCount = s.approvedAuthCount - :approvedCount,
                   s.approvedAuthAmount = case
                       when s.approvedAuthAmount - :approvedAmount > :ceiling then :ceiling
                       when s.approvedAuthAmount - :approvedAmount < :floor then :floor
                       else s.approvedAuthAmount - :approvedAmount end,
                   s.declinedAuthCount = s.declinedAuthCount - :declinedCount,
                   s.declinedAuthAmount = case
                       when s.declinedAuthAmount - :declinedAmount > :ceiling then :ceiling
                       when s.declinedAuthAmount - :declinedAmount < :floor then :floor
                       else s.declinedAuthAmount - :declinedAmount end
             where s.accountId = :accountId
            """)
    int reverseExpiredAuthorizations(@Param("accountId") Long accountId,
            @Param("approvedCount") int approvedCount,
            @Param("approvedAmount") BigDecimal approvedAmount,
            @Param("declinedCount") int declinedCount,
            @Param("declinedAmount") BigDecimal declinedAmount,
            @Param("ceiling") BigDecimal ceiling,
            @Param("floor") BigDecimal floor);

    /**
     * Loads one account's summary without holding its row.
     *
     * <p>Purpose: this serves the paths that read the summary and do not go on to rewrite it -- the
     * summary screen, and the guard that resolves the customer behind an account before a pending
     * authorization is marked. It is the same keyed retrieval, without the hold.</p>
     *
     * <p>Assumptions: an absent summary is a NORMAL outcome and not an error, which is why the return
     * type is an {@link Optional} and why nothing here throws for a missing row. The baseline settles
     * this explicitly. Having moved the status of its keyed read into a field at
     * {@code cbl/COPAUS0C.cbl} L979, it evaluates that status at L980 to L996 over exactly two defined
     * outcomes and one error arm: L981 and L982 set a found indicator when the status is good, L983
     * and L984 set a NOT-FOUND indicator when the segment is absent, and only the remaining arm treats
     * the status as a system error, building the message that begins
     * {@code ' System error while reading AUTH Summary: Code:'} at L989. Absence therefore travels as
     * a state the screen renders, not as a failure. The alternative was to throw a not-found exception
     * from this boundary and let the error handler answer it; its concrete consequence is that the
     * summary screen and the fraud guard would answer a request for an account holding no pending
     * authorizations with an error response, where the baseline answers with an empty summary.</p>
     *
     * <p>Assumptions: there is deliberately no end-of-database arm in that evaluation, and none is
     * needed here either, because a retrieval qualified on the root's own unique key either finds its
     * row or does not and cannot run off the end of anything. The two-status collapse documented for
     * the CHILD browse belongs to that browse and is not attributed to this read.</p>
     *
     * <p>Alternatives Considered: this named finder exists alongside the inherited key lookup rather
     * than instead of it. Relying on the inherited lookup alone was evaluated and set aside for one
     * concrete reason: the held read above must be a separate declaration in order to carry its lock
     * annotation, and a boundary offering a held read named for its field beside an unheld read named
     * for the framework would leave two spellings of one retrieval for a caller to choose between.
     * Naming both for the field they match keeps the pair legible as a pair. The inherited whole-entity
     * write and the inherited existence probe are used as they come, and neither is redeclared
     * here.</p>
     *
     * @param accountId the {@link Long} account identifier whose summary is required, matching
     *     {@code ACCNTID} in the reference root; must not be {@code null}
     * @return an {@link Optional} holding the summary, or {@link Optional#empty()} when the account has
     *     no summary yet
     */
    Optional<PendingAuthSummary> findByAccountId(Long accountId);

    /**
     * Reports which of a stated set of accounts already carry a summary row.
     *
     * <p>Assumptions: this exists so a bulk load can settle the presence of a whole CHUNK of accounts in
     * one statement. Refactoring Rationale: the extract loader previously called the identity-presence
     * check once per record, so a chunk of five hundred records issued five hundred round trips before
     * it wrote anything -- and the detail loader issued a second five hundred to check each record's
     * parent, giving the two-probes-per-row cost that made the load's time grow with the extract rather
     * than with the work. One statement per chunk replaces both.</p>
     *
     * <p>Trade-offs: the caller must bound the collection it passes, because a list parameter becomes a
     * list of bind parameters and every engine has a ceiling on those. The loader passes a chunk it has
     * already bounded for its own reasons, so the bound is real rather than asserted here.</p>
     *
     * @param accountIds the accounts to test for presence, as a bounded collection; must not be
     *     {@code null}
     * @return the subset of those identifiers that already carry a summary row, in no defined order
     */
    @Query("select s.accountId from PendingAuthSummary s where s.accountId in :accountIds")
    List<Long> findExistingAccountIds(@Param("accountIds") Collection<Long> accountIds);

    /**
     * Returns summaries whose account lies strictly beyond a stated position, in ascending key order.
     *
     * <p>Purpose: this is the sequential walk of every root that the unload and purge programs perform.
     * Both drive the whole database in key order and stop when it is exhausted: the paragraph
     * {@code 2000-FIND-NEXT-AUTH-SUMMARY} at {@code cbl/PAUDBUNL.CBL} L207 issues an unqualified
     * get-next against the root at L213, and {@code cbl/CBPAUP0C.cbl} reaches the root the same way
     * from its paragraph of the same name at L216. Ascending account order is that walk's order,
     * because the root's sequence field is the account identifier and {@code ims/DBPAUTP0.dbd} L30
     * declares it unique.</p>
     *
     * <p>Alternatives Considered: the walk resumes from the key it last returned, not from a counted
     * position, and the rejected alternative is {@code findAll} with a sorted offset page. A counted
     * page locates its first row by counting from the start of the ordering on every call, so a row
     * inserted or deleted ahead of that point between two calls shifts every later row by one and the
     * walk then skips a row it never saw or returns one it has already handled. That is not a
     * hypothetical exposure here: the purge DELETES from the very table it is walking, so an offset
     * page would shift under its own reader by construction, and a skipped root is a summary whose
     * counters are never reconciled. Resuming from the last key returned reproduces the get-next it
     * stands for exactly, and it does so without any offset, page number or total count appearing on
     * this boundary.</p>
     *
     * <p>Assumptions: the caller opens the walk by passing an identifier below every real one rather
     * than a {@code null}, so a single predicate serves the first call and every later one. An account
     * identifier is positive, so zero is that value; the alternative of a nullable first page would
     * need either a second query or a null-tolerant predicate, and a predicate comparing against
     * {@code null} in SQL matches no row at all, which would return an empty first page and end the
     * walk before it began.</p>
     *
     * @param accountId the {@link Long} account identifier of the last summary already handled, or a
     *     value below every real identifier to open the walk; must not be {@code null}
     * @param limit the {@link Limit} capping how many summaries one call returns, which the caller sets
     *     to its batch size; must not be {@code null}
     * @return a {@link List} of at most {@code limit} summaries in ascending account order, empty when
     *     the walk has passed the last root
     */
    List<PendingAuthSummary> findByAccountIdGreaterThanOrderByAccountIdAsc(Long accountId,
            Limit limit);

    /**
     * Walks the account identifiers of every root above a stated identifier, in key order.
     *
     * <p>This is the same sequential walk as the method above -- the unqualified {@code EXEC DLI GN}
     * against the root at {@code cbl/CBPAUP0C.cbl} L223 to L226 -- projected to the field the root is
     * addressed by. It returns identifiers and not summaries, and that is the whole of its purpose.</p>
     *
     * <p>Purpose: a caller that INTENDS to modify each summary it walks cannot use the summary the walk
     * returned, and the reason is the DELETION this walk feeds rather than any lock. The purge removes
     * rows from the very table it is stepping through, so a page of entities loaded before the window
     * began is a page of snapshots that a concurrent writer -- another purge window, or a live
     * authorization -- may already have moved past. A caller that decided from those snapshots would
     * decide from state the row no longer holds. The arithmetic that follows -- the four counter and
     * total subtractions at {@code cbl/CBPAUP0C.cbl} L287 to L292 -- is for that reason applied by a
     * statement computed in the database and not by writing a loaded instance back, and the deletion
     * decision is taken from a summary read individually, after the window's position was fixed by
     * key. Walking keys is what makes that individual read possible at all.</p>
     *
     * <p>Alternatives Considered: three, and all three were shapes of the pessimistic walk this
     * boundary declines. Refreshing each summary after re-reading it was rejected because it issues a
     * second statement to undo the effect of the first and leaves the correct ordering as a convention
     * a later reader can drop. Detaching the page before re-reading was rejected for the same
     * reason and because it makes correctness depend on a call whose absence is invisible. Walking
     * the page under {@code PESSIMISTIC_WRITE} -- one locking walk instead of a walk plus per-row
     * reads -- was rejected on two counts: it holds every row of a window for the whole window rather
     * than one row at a time, so an online writer for any account in the window waits for all of it;
     * and a lock on this path is concurrency machinery the reference system does not have, for the
     * reason recorded on this interface's own withdrawal note above.</p>
     *
     * <p>Assumptions: the projection is stated as a query rather than derived from the method name.
     * Spring Data derives a projection to a single property only through a typed interface or class
     * projection, and declaring one for a {@code long} would add a type whose only member is the field
     * this query already names. The ordering and the strict comparison are stated in the query for the
     * same reason they are stated in the derived form above: a walk that DELETES from the table it is
     * walking cannot use a counted offset, so the position must be a key and the comparison must
     * exclude it.</p>
     *
     * @param accountId the {@link Long} account identifier of the last root already handled, or a value
     *     below every real identifier to open the walk; must not be {@code null}
     * @param limit the {@link Limit} capping how many identifiers one call returns, which the caller
     *     sets to its window size; must not be {@code null}
     * @return a {@link List} of at most {@code limit} account identifiers in ascending order, empty
     *     when the walk has passed the last root
     */
    @Query("select s.accountId from PendingAuthSummary s where s.accountId > :accountId "
            + "order by s.accountId asc")
    List<Long> findAccountIdsAboveOrderByAccountIdAsc(@Param("accountId") Long accountId, Limit limit);

    /**
     * Inserts one summary row, leaving an existing row for the same account untouched.
     *
     * <p>Purpose: this is the duplicate-tolerant insert the extract loader needs, transcribing the
     * duplicate-status arm at {@code cbl/PAUDBLOD.CBL} L256 to L258 -- which counts a root already in the
     * database and moves to the next record rather than replacing it. The statement reports how many rows
     * it wrote, so the caller distinguishes the two outcomes without asking a second question.
     *
     * <p>Refactoring Rationale: the loader probed with {@code existsById} and then called the inherited
     * save. Those are two statements with a gap between them, so a row created in that gap -- by the
     * online decision path, which inserts a summary for an account that has none, or by a second loader
     * run over the same extract -- was OVERWRITTEN by the save rather than counted as already present.
     * The overwrite is silent and it is not a partial one: the save replaces every column, so counters
     * and totals a live decision had already moved were reset to whatever the extract carried. One
     * statement that inserts or does nothing cannot have that gap.
     *
     * <p>Assumptions: the conflict target is NAMED rather than left implicit, although the primary key is
     * the only constraint on this table today. Naming it means a constraint added later -- a uniqueness
     * rule over the customer, say -- raises rather than being absorbed as though it were the duplicate
     * this statement exists to tolerate. The narrow-tolerance reading is the safe one because the caller
     * COUNTS a zero result as an already-present row, so a target wide enough to swallow an unrelated
     * collision would report a different fault as a skipped duplicate.
     *
     * <p>Assumptions: nothing is updated on conflict, and that is the reference behaviour rather than a
     * simplification. The reference paragraph neither replaces nor merges: it increments its
     * already-present counter and reads the next record, so the row already stored wins in every field.
     *
     * <p>Assumptions: the values are bound from the entity rather than from a positional parameter list
     * of sixteen. A positional list would put the column-to-value correspondence in a second form that a
     * reader has to check against the first, and a transposition of two adjacent columns of the same type
     * -- of which this row has four pairs among its money columns -- would compile and store silently.
     *
     * <p>Assumptions: the caller owns the transaction. A modifying query carries none of its own, so an
     * unwrapped call fails with no active transaction rather than writing outside one.
     *
     * @param row the summary to insert, whose account identifier is the whole key; must not be
     *     {@code null}
     * @return {@code 1} when the row was written, {@code 0} when the account already had a summary
     */
    @Modifying
    @Query(value = """
            INSERT INTO pending_auth_summary (
                account_id, customer_id, auth_status, account_status_1, account_status_2,
                account_status_3, account_status_4, account_status_5, credit_limit, cash_limit,
                credit_balance, cash_balance, approved_auth_cnt, declined_auth_cnt,
                approved_auth_amt, declined_auth_amt)
            VALUES (
                :#{#row.accountId}, :#{#row.customerId}, :#{#row.authStatus},
                :#{#row.accountStatus1}, :#{#row.accountStatus2}, :#{#row.accountStatus3},
                :#{#row.accountStatus4}, :#{#row.accountStatus5}, :#{#row.creditLimit},
                :#{#row.cashLimit}, :#{#row.creditBalance}, :#{#row.cashBalance},
                :#{#row.approvedAuthCount}, :#{#row.declinedAuthCount},
                :#{#row.approvedAuthAmount}, :#{#row.declinedAuthAmount})
            ON CONFLICT (account_id) DO NOTHING
            """, nativeQuery = true)
    int insertSummaryIfAbsent(@Param("row") PendingAuthSummary row);
}
