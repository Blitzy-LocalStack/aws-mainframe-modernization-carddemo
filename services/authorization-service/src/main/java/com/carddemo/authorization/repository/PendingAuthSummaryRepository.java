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
 * appears once holding the row and once not; the walk appears once returning whole summaries and once
 * returning only the keys they are addressed by. Refactoring Rationale: the key-projected walk was
 * added for the purge, which must not mutate a summary it read before taking the lock -- a row already
 * loaded into the persistence context is returned from it again by a later locking read, so the state
 * the caller then adjusts is the state read BEFORE the lock, which is the read-modify-write the lock
 * exists to prevent. Projecting the walk to keys means the entity is loaded for the first time under
 * the lock, and the alternative of refreshing the loaded instance was rejected as a second statement
 * doing what one correctly-ordered statement already does.</p>
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
     * Adds one approved authorization's contribution to an account's summary, atomically.
     *
     * <p>Purpose: this is the write half of {@code cbl/COPAUA0C.cbl} L814 and L815, plus the credit
     * balance the same paragraph moves at L817 and the cash balance it assigns at L818. All four members
     * move in ONE statement so a concurrent contribution to the same row cannot displace this one.</p>
     *
     * <p>Assumptions: the credit balance moves with the approved pair and not separately, because the
     * entity's own {@code recordApproved} moves all three together and the three are meaningless apart
     * -- an approved total that has advanced while the balance has not describes an account no
     * reference program could produce.
     *
     * <p>Refactoring Rationale: the cash balance is ASSIGNED zero here and was previously left
     * untouched, and the fourth member joins the statement for the same reason the other three are in
     * it. {@code MOVE 0 TO PA-CASH-BALANCE} at L818 is an assignment rather than an accumulation, so it
     * is the one member of the four for which a concurrent contribution cannot lose anything -- but
     * splitting it out would mean an approval reached the row through two statements, and a reader
     * comparing the reference branch's four statements with a three-member update would have to go
     * looking for the fourth. Its effect is not vacuous: a summary the extract load rehydrated carries
     * whatever cash balance the stored segment held, and the reference program zeroes it on the first
     * approval thereafter, so omitting the assignment left a seeded value standing that the reference
     * clears. It is expressed as a literal zero rather than as a parameter because the reference moves a
     * literal.
     *
     * <p>Assumptions: the statement reports the number of rows it changed, and the caller is expected
     * to treat zero as "no summary for this account" rather than ignoring it. That is the same
     * condition the withdrawn read reported as an empty {@link Optional}, moved to the write.
     *
     * @param accountId the account whose summary receives the contribution; must not be {@code null}
     * @param amount the approved amount to add to both the approved total and the credit balance; must
     *     not be {@code null}
     * @return {@code 1} when the account had a summary and it was updated, {@code 0} when it had none
     */
    @Modifying
    @Query("""
            update PendingAuthSummary s
               set s.approvedAuthCount = s.approvedAuthCount + 1,
                   s.approvedAuthAmount = s.approvedAuthAmount + :amount,
                   s.creditBalance = s.creditBalance + :amount,
                   s.cashBalance = 0
             where s.accountId = :accountId
            """)
    int addApprovedAuthorization(@Param("accountId") Long accountId,
            @Param("amount") BigDecimal amount);

    /**
     * Adds one declined authorization's contribution to an account's summary, atomically.
     *
     * <p>Purpose: this is the write half of {@code cbl/COPAUA0C.cbl} L820 and L821.</p>
     *
     * <p>Assumptions: the credit balance is deliberately NOT moved here, matching the reference
     * paragraph, which moves it on the approved arm only. A declined authorization consumes no credit,
     * so advancing the balance would overstate what the account has committed.
     *
     * @param accountId the account whose summary receives the contribution; must not be {@code null}
     * @param amount the requested amount to add to the declined total; must not be {@code null}
     * @return {@code 1} when the account had a summary and it was updated, {@code 0} when it had none
     */
    @Modifying
    @Query("""
            update PendingAuthSummary s
               set s.declinedAuthCount = s.declinedAuthCount + 1,
                   s.declinedAuthAmount = s.declinedAuthAmount + :amount
             where s.accountId = :accountId
            """)
    int addDeclinedAuthorization(@Param("accountId") Long accountId,
            @Param("amount") BigDecimal amount);

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
     * @param accountId the account whose summary is reduced; must not be {@code null}
     * @param approvedCount how many approved authorizations are being reversed; must not be negative
     * @param approvedAmount their total amount; must not be {@code null}
     * @param declinedCount how many declined authorizations are being reversed; must not be negative
     * @param declinedAmount their total amount; must not be {@code null}
     * @return {@code 1} when the account had a summary and it was updated, {@code 0} when it had none
     */
    @Modifying
    @Query("""
            update PendingAuthSummary s
               set s.approvedAuthCount = s.approvedAuthCount - :approvedCount,
                   s.approvedAuthAmount = s.approvedAuthAmount - :approvedAmount,
                   s.declinedAuthCount = s.declinedAuthCount - :declinedCount,
                   s.declinedAuthAmount = s.declinedAuthAmount - :declinedAmount
             where s.accountId = :accountId
            """)
    int reverseExpiredAuthorizations(@Param("accountId") Long accountId,
            @Param("approvedCount") int approvedCount,
            @Param("approvedAmount") BigDecimal approvedAmount,
            @Param("declinedCount") int declinedCount,
            @Param("declinedAmount") BigDecimal declinedAmount);

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
     * returned. Every such caller must take the row lock first, and a locking read of a row that is
     * already in the persistence context hands back the instance loaded by the earlier unlocked read
     * rather than the state visible once the lock is held. The arithmetic that follows -- the four
     * counter and total subtractions at {@code cbl/CBPAUP0C.cbl} L287 to L292 -- would then be applied
     * to a snapshot a concurrent writer has already moved past, and the resulting row would be short by
     * exactly that writer's contribution with nothing in it to show the loss. Walking keys makes the
     * locking read the FIRST read of the row, so the state adjusted is the state the lock protects.</p>
     *
     * <p>Alternatives Considered: three. Refreshing each summary after locking it was rejected because
     * it issues a second statement to undo the effect of the first and leaves the correct ordering as a
     * convention a later reader can drop. Detaching the page before locking was rejected for the same
     * reason and because it makes correctness depend on a call whose absence is invisible. Reading the
     * page with the lock already applied -- one locking walk instead of a walk plus per-row locks --
     * was rejected because it holds every row of a window for the whole window rather than one row at a
     * time, so an online writer for any account in the window waits for all of it.</p>
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
