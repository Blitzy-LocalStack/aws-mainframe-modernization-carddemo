package com.carddemo.transaction.repository;

import com.carddemo.common.error.RecordConflictException;
import com.carddemo.transaction.domain.TransactionCategoryBalance;
import com.carddemo.transaction.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Issues the insert and the update of {@link TransactionCategoryBalanceWriter} as two distinct
 * statements, so that which reference paragraph ran stays visible in what the database receives.
 *
 * <h2>Assumptions: the class NAME is what wires this in, not an annotation</h2>
 *
 * <p>Spring Data composes a repository from its declared fragments by locating, for each fragment
 * interface, a class whose simple name is that interface's simple name followed by the configured
 * postfix -- {@code Impl} by default. This class is therefore named
 * {@code TransactionCategoryBalanceWriterImpl} because {@link TransactionCategoryBalanceWriter} is
 * named {@code TransactionCategoryBalanceWriter}, and renaming either one without the other silently
 * un-wires the fragment: the repository bean still starts and the two declared methods fail at their
 * first call. The pairing is stated here because nothing in the compiler enforces it.</p>
 *
 * <p>Assumptions: this is the FIRST custom repository fragment in the migration, so the convention
 * above has no precedent elsewhere in the tree to be read off. It is written out rather than assumed
 * for that reason.</p>
 *
 * <p>Alternatives Considered: a stereotype annotation on this class. Declined because fragment
 * resolution does not use one, and adding it would register a SECOND bean of this type -- one found by
 * the fragment mechanism and one by component scanning -- which is a confusing state to debug for no
 * gain. The sibling repository interfaces in this package omit their own stereotype annotation for the
 * matching reason recorded there, so the omission here is consistent rather than an oversight.</p>
 *
 * <h2>Assumptions: both methods detect their conflict BEFORE writing, not from a caught exception</h2>
 *
 * <p>Each method reads the target row first and refuses on what that read reports: a present row is a
 * conflict for the create path, an absent row is a conflict for the update path. The alternative --
 * writing first and translating the failure the provider raises -- was declined because it cannot be
 * done without naming a provider exception type in production code. The duplicate a flush reports
 * arrives as the provider's own constraint-violation type rather than as a specification type, so a
 * catch clause reliable enough to translate it would have to import Hibernate, and the shared kernel's
 * own error advice refuses exactly that coupling: it recognises persistence failures by
 * FULLY-QUALIFIED NAME through a cause-chain walk rather than by class literal, for that same
 * reason.</p>
 *
 * <p>Trade-offs: a read-then-write pair leaves a window in which another transaction can insert or
 * delete the row between the two statements, which a single conditional statement would not have. The
 * window is not silent, and that is what makes the trade acceptable. An insert landing in the create
 * path's window is refused by the composite primary key, and a delete landing in the update path's
 * window is caught by the provider's own affected-row check on a versionless update; either way the
 * write does not happen and the caller's transaction rolls back. Both then reach a client as HTTP 409,
 * because the shared advice's cause-chain walk recognises the framework's translated
 * data-integrity and optimistic-locking failures and renders each as a conflict. What the window
 * costs is the SENTENCE the client reads, not the refusal.</p>
 *
 * <h2>Assumptions: neither method opens a transaction</h2>
 *
 * <p>The transaction requirement is declared on the fragment interface as mandatory participation,
 * so both methods join the caller's unit of work and neither can start one of its own. That is
 * deliberate rather than an omission: the reference posting program commits the category balance, the
 * account record and the posted transaction as ONE unit of work -- {@code app/cbl/CBTRN02C.cbl}
 * performs the three writes in sequence at lines 440 to 442 -- so a write here that committed on its
 * own would make a partial posting observable, which the migration plan explicitly refuses.</p>
 */
class TransactionCategoryBalanceWriterImpl implements TransactionCategoryBalanceWriter {

    /**
     * The persistence context both statements are issued through, supplied by the container.
     *
     * <p>Assumptions: injected as a context rather than as a factory, so the instance handed over is
     * the one bound to the caller's transaction. A factory would hand over an unbound unit that
     * neither joins the caller's unit of work nor sees the row the caller has already read, which is
     * precisely the state both methods below depend on.</p>
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Inserts the row through the persistence context, refusing a key that is already taken.
     *
     * <p>Assumptions: the guard, the insert and the flush are three statements in the order the
     * reference create path uses them, and the flush is what makes this method's outcome final
     * before it returns. The contract, the conflict a caller is expected to act on and the
     * alternatives weighed against this shape are all recorded on
     * {@link TransactionCategoryBalanceWriter#createRow(TransactionCategoryBalance)} and are not
     * restated here.</p>
     *
     * @param row the row to insert, carrying its complete composite identity; must not be
     *     {@code null}, and neither may its identity
     * @throws NullPointerException if {@code row} is {@code null} or carries no identity
     * @throws RecordConflictException carrying
     *     {@link RecordConflictException.Kind#STALE_VERSION} if the composite key is already
     *     present, whether from an earlier statement of this transaction or from a committed one
     */
    @Override
    public void createRow(TransactionCategoryBalance row) {
        Objects.requireNonNull(row, "row must not be null");
        TransactionCategoryBalanceId id =
                Objects.requireNonNull(row.getId(), "row.id must not be null");

        // WHY : Assumptions: the guard is a keyed read rather than a count, because a count over a
        //       whole-key predicate answers the same question with a statement whose result still has
        //       to be interpreted, while a keyed read is the statement the reference program itself
        //       issues before it chooses an arm -- READ TCATBAL-FILE at app/cbl/CBTRN02C.cbl line 474.
        //       In the ordinary call sequence this costs no query at all: the caller has already read
        //       the row through the repository to make that very choice, so the instance is in the
        //       persistence context and this read is answered from it.
        if (this.entityManager.find(TransactionCategoryBalance.class, id) != null) {
            // WHY : Assumptions: refusing here is what separates this method from the merge it
            //       replaces. A merge finding this same row would have UPDATED it, discarding the
            //       amount its inserting writer had accumulated, and would have raised nothing. The
            //       reference WRITE at line 510 has no such outcome: writing an existing key against
            //       an indexed dataset fails, and the paragraph's status gate at line 512 accepts
            //       only '00'.
            throw new RecordConflictException(RecordConflictException.Kind.STALE_VERSION);
        }

        this.entityManager.persist(row);

        // WHY : Refactoring Rationale: the flush is EXPLICIT, and it is what makes a failure here
        //       attributable to this row. Persist only schedules the insert, so without a flush a
        //       duplicate arriving through the window above would surface at commit -- by which point
        //       the failure names the transaction rather than the row, the stack passes through the
        //       transaction interceptor rather than through this call, and every write the caller
        //       issued afterwards is discarded with no indication of which one caused it.
        // WHY : Trade-offs: flushing also writes any other change pending in the context ahead of
        //       schedule, which is a cost. It is accepted because the reference behaviour it mirrors
        //       is synchronous in exactly the same way: the WRITE at line 510 either succeeds or sets
        //       a file status the very next statement inspects, so the reference program never
        //       proceeds past an unreported duplicate either.
        this.entityManager.flush();
    }

    /**
     * Updates the row's balance by mutating the managed instance, refusing a key that is absent.
     *
     * <p>Assumptions: the read is what establishes presence and the flush is what issues the
     * statement, so the update is in place rather than bulk -- the reason that matters, and the bulk
     * alternative it displaces, are recorded at the point of the decision below. The contract and
     * the conflict a caller is expected to act on are recorded on
     * {@link TransactionCategoryBalanceWriter#updateBalance(
     * TransactionCategoryBalance.TransactionCategoryBalanceId, java.math.BigDecimal)}.</p>
     *
     * @param id the complete composite identity of the row to update; must not be {@code null}
     * @param balance the new balance, already computed by the calling service; must not be
     *     {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws java.lang.ArithmeticException if {@code balance} falls outside the domain the shared
     *     money contract admits, which the entity's mutator reports before reducing the value
     * @throws RecordConflictException carrying
     *     {@link RecordConflictException.Kind#STALE_VERSION} if no row carries the composite key
     */
    @Override
    public void updateBalance(TransactionCategoryBalanceId id, BigDecimal balance) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(balance, "balance must not be null");

        TransactionCategoryBalance managed =
                this.entityManager.find(TransactionCategoryBalance.class, id);

        if (managed == null) {
            // WHY : Assumptions: refusing an absent row is the other half of what separates these two
            //       methods from the merge they replace. A merge finding no row would have INSERTED
            //       one, recreating a row another writer had deleted and raising nothing. The
            //       reference REWRITE at app/cbl/CBTRN02C.cbl line 528 has no such outcome: it is
            //       reached only through the ELSE of the branch at line 495, which requires the read
            //       at line 474 to have found the record.
            throw new RecordConflictException(RecordConflictException.Kind.STALE_VERSION);
        }

        // WHY : Alternatives Considered: a bulk update statement carrying the whole key in its
        //       predicate, whose affected-row count this method would read. Declined because a bulk
        //       statement bypasses the persistence context, so an instance the caller loaded earlier
        //       in the same transaction -- which, being the create-versus-update discriminator, it
        //       always has -- would still hold the OLD balance afterwards. Correcting that needs
        //       either a refresh, which is a second query, or a context clear, which detaches the
        //       account and transaction rows the same unit of work is about to write. Mutating the
        //       managed instance leaves the context and the database agreeing without either.
        // WHY : Assumptions: the mutator replaces the balance rather than adding to it, because the
        //       addition belongs to the caller. Both reference arms add the same amount and differ
        //       only in the base -- INITIALIZE then ADD at lines 504 and 508, ADD alone at line 527 --
        //       and choosing the base is the branch at line 495, which is the calling service's rule
        //       and not this fragment's.
        managed.setBalance(balance);

        // WHY : Assumptions: the flush is what turns the mutation into the UPDATE that mirrors the
        //       REWRITE at line 528, issued while this method can still be named as its origin. It is
        //       also where a delete landing in the window above is caught: the provider checks the
        //       affected-row count of a versionless update and raises a stale-state failure when it is
        //       zero, which the framework translates into the optimistic-locking failure the shared
        //       advice already renders as a conflict.
        // WHY : Trade-offs: a caller supplying the balance the row already holds leaves nothing dirty,
        //       so this flush issues no statement and a delete landing in the window goes unreported.
        //       Accepted because nothing was to be written in that case either -- the guard above has
        //       already refused the only outcome that would have corrupted a balance, which is a write
        //       to a row that is not there.
        this.entityManager.flush();
    }
}
