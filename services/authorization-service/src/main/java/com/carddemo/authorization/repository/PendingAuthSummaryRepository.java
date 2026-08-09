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
 * <p>This is the migrated form of the access the baseline performs against the ROOT segment
 * {@code PAUTSUM0} of the hierarchical database declared at
 * {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd} L28, whose hundred-byte layout is set out
 * field by field at {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} L19 to L31. Exactly
 * three movements over that segment appear across the eight reference programs, and all three are
 * below: a keyed read of one account, a whole-segment rewrite, and a sequential walk of every root in
 * key order.</p>
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

    /**
     * Loads one account's summary and holds its row for the rest of the transaction.
     *
     * <p>Purpose: this is the read the authorization consumer performs before it adds to the row's
     * counters and totals, standing for the keyed root retrieval at {@code cbl/COPAUS0C.cbl} L973 to
     * L977. The row is returned held so that the read and the write that follows it are one
     * uninterrupted sequence.</p>
     *
     * <p>Alternatives Considered: the lock is a TARGET-SIDE addition rather than a transcription, and
     * the divergence is recorded here rather than presented as preserved behaviour. The baseline holds
     * nothing on this path: {@code cpy/IMSFUNCS.cpy} declares all three get-hold function codes --
     * {@code FUNC-GHU} at L19, {@code FUNC-GHN} at L21 and {@code FUNC-GHNP} at L23 -- and no program
     * in the reference tree passes any of the three to a data-language call, the retrieval codes
     * actually passed being the non-hold {@code FUNC-GU}, {@code FUNC-GN} and {@code FUNC-GNP}; both
     * unload views run {@code PROCOPT=GOTP}, at {@code ims/PAUTBUNL.PSB} L18 and
     * {@code ims/DLIGSAMP.PSB} L18. What changes is not the data but the concurrency model around it:
     * the baseline decides one message at a time inside a single program bracketed by its own
     * syncpoint, whereas the migrated consumer runs as several tasks that may hold two messages for
     * two cards of the SAME account at once, since the queue preserves order per card rather than per
     * account. Two alternatives were evaluated against that. Taking no lock at all was rejected
     * because these fields are INCREMENTED and not assigned -- {@code cbl/COPAUA0C.cbl} adds to the
     * approved count and amount at L814 and L815 and to the declined pair at L820 and L821, and
     * {@code cbl/CBPAUP0C.cbl} subtracts from the same four at L287 to L292 -- so two interleaved
     * read-modify-write sequences lose one contribution and store a total that is simply short, with
     * nothing in the result to show it. An optimistic version check was rejected because the migration
     * declares no version column on this table, so adopting it would mean adding one, and it detects
     * the collision only at commit, discarding decision work a retry then has to redo. Holding the row
     * from the read makes the second task wait instead.</p>
     *
     * <p>Trade-offs: what this buys is paid for in lock-wait, and the cost is bounded deliberately.
     * The wait applies to one row of one account inside a transaction that performs no network call
     * while holding it, so the exposure is the length of that transaction rather than of a request.
     * The read-only companion below deliberately takes no lock, which is why the two are separate
     * declarations rather than one method carrying a flag.</p>
     *
     * @param accountId the {@link Long} account identifier whose summary is required, matching
     *     {@code ACCNTID} in the reference root; must not be {@code null}
     * @return an {@link Optional} holding the summary with its row held for the transaction, or
     *     {@link Optional#empty()} when the account has no summary yet
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PendingAuthSummary> findWithLockByAccountId(Long accountId);

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
}
