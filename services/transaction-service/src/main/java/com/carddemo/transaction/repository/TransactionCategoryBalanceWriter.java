package com.carddemo.transaction.repository;

import com.carddemo.common.error.RecordConflictException;
import com.carddemo.transaction.domain.TransactionCategoryBalance;
import java.math.BigDecimal;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two write shapes the reference posting program issues against the category-balance dataset,
 * declared separately so that a caller's choice of path survives into the statement the database
 * receives.
 *
 * <h2>Why this fragment exists rather than the inherited save method</h2>
 *
 * <p>Refactoring Rationale: {@code TransactionCategoryBalanceRepository} previously offered only the
 * inherited {@code save}, and its own documentation asserted that "the inherited {@code save} covers
 * both paths, which is precisely why no third member is needed to express them" -- an empty
 * {@code Optional} leading to a save that inserts, a present one to a save that updates. That is not
 * what happens. The identifier of {@link TransactionCategoryBalance} is an {@code @EmbeddedId} that
 * every constructor assigns, and the entity carries no version attribute, so the provider's newness
 * test reads a non-null identifier, concludes the instance is not new, and routes <b>both</b> branches
 * through {@code EntityManager.merge}. One code path served two outcomes, and the provider decided
 * insert against update internally from a {@code SELECT} it issued itself.</p>
 *
 * <p>Assumptions: that opacity is not merely inelegant, it loses two races the reference system
 * cannot lose. A merge on the create path finds a row another writer inserted first and silently
 * UPDATES it, discarding that writer's amount instead of accumulating onto it -- where the reference
 * {@code WRITE} at {@code app/cbl/CBTRN02C.cbl} line 510 would have been refused by the duplicate key.
 * A merge on the update path finds the row gone and silently INSERTS it, recreating a row another
 * writer deleted -- where the reference {@code REWRITE} at line 528 would have failed its key check.
 * Both failures leave a plausible row behind and raise nothing, which is the worst available
 * behaviour for a money column.</p>
 *
 * <p>Alternatives Considered: making the entity implement {@code Persistable} so that the newness test
 * could be answered explicitly, which is the conventional remedy for an assigned identifier. Declined
 * because it answers the wrong question: it would let a caller's create route to {@code persist}, but
 * the caller would still express its intent by calling one method and hoping the entity's own state
 * flag agreed with it. A transient flag that says "treat this as new" is a second place for the
 * intent to live and a second place for it to be wrong. Two named methods put the intent in the call
 * itself, where the reference source puts it -- two separately named paragraphs reached from one
 * predicate.</p>
 *
 * <p>Alternatives Considered: an upsert -- a single {@code INSERT ... ON CONFLICT DO UPDATE} -- which
 * would be one round trip and would lose neither race. Declined for the reason the migration already
 * records at its own lines about this table, and the reason the parity oracle requires: the reference
 * behaviour is two separately named paragraphs, {@code 2700-A-CREATE-TCATBAL-REC} at lines 503 to 524
 * and {@code 2700-B-UPDATE-TCATBAL-REC} at lines 526 to 542, and {@code tests/README.md} states that
 * the create-versus-update branch is exercised both ways. An upsert collapses them into one statement
 * whose outcome a test cannot distinguish, which is the same opacity as the merge with better
 * performance.</p>
 *
 * <h2>Assumptions: the arithmetic is NOT here</h2>
 *
 * <p>Both reference paths add an amount to a base and differ only in whether that base is zero or the
 * value just read -- {@code INITIALIZE} then {@code ADD} at lines 504 and 508 on the create path,
 * {@code ADD} alone at line 527 on the update path. Deciding which path runs, and computing the value,
 * belongs to the calling service. This fragment receives a value already computed and is responsible
 * only for the statement that carries it, which is why the update method takes a balance rather than
 * an increment: an increment would put the addition in two places, and the two would eventually
 * disagree about rounding.</p>
 *
 * <h2>Trade-offs: mandatory participation is declared here, on the interface</h2>
 *
 * <p>Both methods below carry {@code Propagation.MANDATORY}, which is the only transaction attribute
 * anywhere in this package and needs its reason recorded, because the repository interface beside this
 * one states that no transaction-boundary annotation belongs on it. That statement holds and this
 * annotation does not contradict it: {@code MANDATORY} declares that these methods never OWN a
 * boundary. It joins the caller's unit of work and refuses outright when there is none, which is the
 * opposite of the read-only attribute that statement rejects -- that one would fragment a caller's
 * unit of work into a separate transaction per query.</p>
 *
 * <p>Alternatives Considered: declaring nothing and relying on the caller. Declined because the
 * repository infrastructure supplies a DEFAULT attribute to every method it proxies, taken from the
 * read-only class-level attribute of its own base implementation. A write reaching this fragment
 * outside a caller's transaction would then run in a read-only one, and the refusal would arrive from
 * the database as a rejected statement rather than from the boundary as a stated precondition.
 * Declaring the attribute on the interface method, which is the method the transaction interceptor
 * resolves, is what forecloses that.</p>
 *
 * <p>Assumptions: the attribute is declared on this interface rather than on the implementing class
 * because the interceptor sits on the repository PROXY and resolves the attribute from the invoked
 * interface method. An annotation on the implementation would be read by nothing and would be exactly
 * the kind of inert prose this project's documentation rule treats as a defect.</p>
 */
public interface TransactionCategoryBalanceWriter {

    /**
     * Inserts a category-balance row that does not yet exist.
     *
     * <p>Assumptions: this issues an {@code INSERT} and never an {@code UPDATE}, mirroring the
     * {@code WRITE} at {@code app/cbl/CBTRN02C.cbl} line 510. A row already carrying this composite
     * key is therefore a conflict rather than a value to overwrite, which is the reference outcome:
     * that {@code WRITE} against an existing key fails, it does not replace.</p>
     *
     * @param row the row to insert, carrying its complete composite identity and the balance the
     *     calling service computed; must not be {@code null}, and neither may its identity
     * @throws NullPointerException if {@code row} is {@code null}, or if it carries no identity, since
     *     an insert has no key to refuse or accept without one
     * @throws RecordConflictException carrying {@link RecordConflictException.Kind#STALE_VERSION} if a
     *     row with this composite key already exists, which means another writer inserted it between
     *     this caller's read and this call; the caller's remedy is to re-read and take the update path
     * @throws org.springframework.transaction.IllegalTransactionStateException if the caller holds no
     *     transaction, which the mandatory attribute above refuses rather than starting one
     * @throws org.springframework.dao.DataIntegrityViolationException if a competing insert lands
     *     between this method's own guard and its flush, which the composite primary key refuses; the
     *     shared error advice renders that as the same HTTP 409 as the declared conflict above
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void createRow(TransactionCategoryBalance row);

    /**
     * Updates the balance of a category-balance row that already exists.
     *
     * <p>Assumptions: this issues an {@code UPDATE} against an existing key and never an
     * {@code INSERT}, mirroring the {@code REWRITE} at {@code app/cbl/CBTRN02C.cbl} line 528. An
     * {@code UPDATE} matching no row is silent in SQL, so the absence of the row is established by a
     * keyed read before the statement is issued rather than inferred from it afterwards -- the same
     * order the reference program uses, whose read at line 474 is what admits the {@code REWRITE} at
     * all.</p>
     *
     * @param id the complete composite identity of the row to update; must not be {@code null}
     * @param balance the new balance, already computed by the calling service at scale 2; must not be
     *     {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws java.lang.ArithmeticException if {@code balance} falls outside the domain the shared
     *     money contract admits, which the entity's own mutator reports before reducing the value
     * @throws RecordConflictException carrying {@link RecordConflictException.Kind#STALE_VERSION} if no
     *     row carries this composite key, which means another writer deleted it between this caller's
     *     read and this call; the caller's remedy is to re-read and take the create path
     * @throws org.springframework.transaction.IllegalTransactionStateException if the caller holds no
     *     transaction, which the mandatory attribute above refuses rather than starting one
     * @throws org.springframework.dao.OptimisticLockingFailureException if a competing delete lands
     *     between this method's own guard and its flush, which the provider detects from the affected
     *     row count of an unversioned update; the shared error advice renders that as the same HTTP 409
     *     as the declared conflict above
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void updateBalance(TransactionCategoryBalance.TransactionCategoryBalanceId id,
            BigDecimal balance);
}
