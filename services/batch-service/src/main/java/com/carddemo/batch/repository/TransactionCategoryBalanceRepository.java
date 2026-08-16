package com.carddemo.batch.repository;

import com.carddemo.batch.domain.TransactionCategoryBalance;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.stream.Stream;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the running per-category balances that posting maintains and accrual consumes.
 *
 * <p>The table behind this interface is {@code ledger.transaction_category_balances}, and one row
 * carries the accumulated balance for one account, one transaction type and one transaction category.
 * It is the only table this module touches that two migrated jobs reach in two structurally different
 * ways, and that fact shapes every member declared below.</p>
 *
 * <h2>One cluster, two access disciplines</h2>
 *
 * <p>The reference decided its access discipline per program rather than per dataset. Posting selects
 * the cluster at {@code app/cbl/CBTRN02C.cbl:57-61} as {@code ORGANIZATION IS INDEXED} with
 * {@code ACCESS MODE IS RANDOM} and {@code RECORD KEY IS FD-TRAN-CAT-KEY}, so it reaches exactly one
 * row at a time by a key assembled at {@code :469-471}. Interest accrual selects <b>the same
 * cluster</b> at {@code app/cbl/CBACT04C.cbl:28-32} as {@code ACCESS MODE IS SEQUENTIAL}, so it never
 * keys in at all and walks the table end to end through the single read at {@code :326}. Both
 * disciplines are named methods here -- the keyed read {@code findByIdIs} and the ordered walk
 * {@code findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc} -- and there is no third way in.</p>
 *
 * <h2>Why this module writes a table it does not own</h2>
 *
 * <p>Assumptions: the write authority is a GRANT and not ownership. The table belongs to
 * {@code transaction-service}, which creates it in its {@code V1__ledger.sql}; this module reaches it
 * against the same cluster under a role holding the narrowly scoped cross-schema write grant on
 * {@code ledger} that AAP 0.4.1.3 records as the one documented exception to database-per-service
 * purity.</p>
 *
 * <p>Alternatives Considered: calling {@code transaction-service} over HTTP, which purity would
 * otherwise require, and a saga or transactional outbox in its place. All are rejected on one ground:
 * a remote hop cannot enlist in the caller's transaction, and this write is the first of three that
 * commit together -- {@code app/cbl/CBTRN02C.cbl:440-442} performs the category-balance update, the
 * account update and the transaction write in immediate succession with <b>no intervening commit
 * verb</b>. Any of the alternatives makes a partially posted state observable, a written transaction
 * whose balance has not moved, which does not arise in the reference and which the golden masters
 * would correctly report as a parity failure.</p>
 *
 * <p>Trade-offs: because the table is owned elsewhere, the owning migration is the authority on its
 * shape and this interface is passive about it -- no data definition, no {@code @Index} and no
 * constraint is declared here beyond the identity the mapping needs, so a disagreement about the
 * physical shape is settled by reading {@code V1__ledger.sql} rather than two mappings. The accepted
 * cost is that a change to that table is coordinated across two contexts.</p>
 *
 * <h2>An absent row is a normal outcome, and the posting branch turns on it</h2>
 *
 * <p>Assumptions: the keyed read replaces the {@code READ} with an {@code INVALID KEY} handler at
 * {@code app/cbl/CBTRN02C.cbl:474-479}, where not-found is an ORDINARY result rather than a failure --
 * line 473 clears the create flag, line 478 sets it, and line 481 accepts file status {@code '23'}
 * alongside {@code '00'} as clean, so only some other status reaches the abend path. An empty
 * {@code Optional} therefore means <b>no balance row exists yet for this key</b>, which is what every
 * first transaction in a category produces; it is not an error, and a caller must neither log it as
 * one nor turn it into a thrown exception.</p>
 *
 * <p>Assumptions: both reference arms are ADDITIVE and differ only by an initialisation. The create arm
 * initialises, moves the three key components and adds the amount at {@code :504-508} before writing at
 * {@code :510}; the update arm adds the same amount to the value already read at {@code :527} before
 * rewriting at {@code :528}. AAP 0.5.1.7 requires the two to remain distinguishable and separately
 * tested, which is why no upsert or conflict-resolving helper is declared here: the decision is the
 * caller's, taken on the result of the read, and the write is the inherited {@code save}.</p>
 *
 * <h2>The walk ordering, and the arithmetic that settles it</h2>
 *
 * <p>Assumptions: the walk orders by account identifier, then transaction type code, then transaction
 * category code, and that IS the reference key order rather than an inference about it.
 * {@code app/jcl/TCATBALF.jcl:40} defines the cluster with {@code KEYS(17 0)}, so the key is the first
 * seventeen bytes of the record; {@code app/cpy/CVTRA01Y.cpy:6-8} declares the leading fields as an
 * eleven-digit account identifier, a two-character type code and a four-digit category code, and 11
 * plus 2 plus 4 is exactly 17 in that sequence, so the group item at {@code :5} is precisely the
 * primary key in precisely that column order. The owning migration declares the same sequence a third
 * time as the composite primary key {@code (account_id, type_cd, category_cd)}.</p>
 *
 * <p>Assumptions: the ordering has to be HONOURED and not merely recorded, because a control break
 * depends on it. Accrual receives its rows in key order for free from a sequential open; line 194
 * detects a change of account by comparing the current row's identifier against the previous one and
 * line 196 flushes the account it is leaving. A break on account change is sound only while every row
 * of an account arrives consecutively, and a relational query has no default order, so an unordered
 * walk would split one account into several apparent groups and flush it once per group.</p>
 *
 * <p>Alternatives Considered: ordering by the account identifier alone, which is all the control break
 * examines. Rejected because it leaves the WITHIN-ACCOUNT order unspecified and that order is
 * observable -- {@code app/cbl/CBACT04C.cbl:193} emits one line per row scanned, and that stream is
 * what the golden-master oracle compares byte for byte.</p>
 *
 * <p>Trade-offs: the walk is streamed rather than chunked into independent keyed reads, because the
 * control break needs strictly monotonic account order across the ENTIRE input and a per-chunk query
 * cannot guarantee that an account's rows do not straddle a boundary. The compromise is a heavier
 * call-site contract than a materialised return would impose -- the caller holds one transaction open
 * across the whole walk and closes the stream -- and that cost is bounded and local, whereas the heap
 * a materialised return needs grows with the table.</p>
 *
 * <p>Assumptions: <b>no keyset-continuation finder is declared</b>, which follows from the streaming
 * decision rather than from oversight. The walk is one forward cursor inside one transaction, so a
 * failed step rolls it back whole and a redriven step re-runs it whole; there is no committed position
 * below the step to resume from. What makes that safe is the durable step ledger reached by
 * {@code BatchRunRepository}, where a step that already recorded completion for a run becomes a no-op
 * rather than repeating its writes -- and that ledger is an idempotency key, not a cursor store, its
 * seven columns in {@code V1__batch.sql} holding no position. Where the framework keeps a position is
 * its own step execution context, persisted with the step's chunk commit.</p>
 *
 * <h2>Rulings this interface inherits from the package charter</h2>
 *
 * <p>Alternatives Considered: native SQL, which is a natural reach in a batch module because a nightly
 * pass is set-shaped. Rejected on the TIMING of the failure it admits: this module runs the provider
 * with schema handling held at {@code validate}, which compares mapping metadata against the deployed
 * table and never parses the text of a native query, so a property path resolving to a column the
 * schema lacks is reported at start-up while a mistyped physical column inside a native statement stays
 * invisible until that statement executes -- part-way through a nightly chain with earlier steps
 * already committed. Both members below are derived methods bound to property names declared on
 * {@link TransactionCategoryBalance}, so no physical column name appears in this file.</p>
 *
 * <p>Assumptions: the offset-pagination vocabulary the charter prohibits package-wide appears on no
 * method, parameter or return type here, and neither does the HTTP keyset envelope
 * {@code com.carddemo.common.web.PageResponse}. Counting rows from the start of an ordered set means a
 * concurrent insert ahead of the cursor pushes an unread row past the boundary and a concurrent delete
 * pulls an already-read row back inside it, and this module's correctness is judged by comparing
 * committed output against a golden master, so a read discipline that can skip or repeat a row is a
 * different program. The envelope is absent for a simpler reason: it exists because a stateless request
 * handler cannot remember where a caller had reached, and this module has no client.</p>
 *
 * <p>Assumptions: no arithmetic is performed here and no monetary type other than the entity's own
 * appears. The additive update belongs to {@code com.carddemo.batch.service.CategoryBalanceService},
 * which forms the new balance through {@code com.carddemo.common.money.Money} so that the single
 * arithmetic boundary rules T3 and T4 require is not duplicated into this package. Rule A3 of
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * fails the build on a binary floating-point type in any member position, including as a generic type
 * argument, so the prohibition is executable rather than advisory.</p>
 *
 * <p>Assumptions: the identifier below is written in qualified
 * {@code TransactionCategoryBalance.TransactionCategoryBalanceId} form rather than imported, so that it
 * is visibly the entity's OWN nested type rather than a same-named type from somewhere else. Both of
 * this module's composite-key interfaces spell it that way, which makes it the package convention.</p>
 *
 * @see TransactionCategoryBalance
 */
public interface TransactionCategoryBalanceRepository extends JpaRepository<
        TransactionCategoryBalance, TransactionCategoryBalance.TransactionCategoryBalanceId> {

    /**
     * Reads one running balance by its whole three-part key, which is the read posting branches on.
     *
     * <p>This is the arm-selecting statement of the posting job. A present row sends the caller down
     * the update arm and an absent one sends it down the create arm, and nothing else in the
     * paragraph decides between them.</p>
     *
     * @param id the whole composite key as a
     *     {@code TransactionCategoryBalance.TransactionCategoryBalanceId} -- the account identifier,
     *     the transaction type code and the transaction category code that together form the
     *     seventeen-byte group at {@code app/cpy/CVTRA01Y.cpy:5}; must not be {@code null}
     * @return the one matching row as an {@code Optional<TransactionCategoryBalance>}, or an EMPTY
     *     optional when no balance row exists yet for that key. <b>An empty result is a NORMAL
     *     outcome and not an error</b>: it is what the first transaction in a category produces, it
     *     is the relational equivalent of the file status {@code '23'} that
     *     {@code app/cbl/CBTRN02C.cbl:481} accepts alongside {@code '00'} as clean, and the caller
     *     branches on it rather than reporting it. It must not be logged as a failure and must not
     *     be turned into a thrown exception
     */
    // Assumptions: this is the migrated form of the READ with an INVALID KEY handler at
    //     app/cbl/CBTRN02C.cbl:474-479. The reference expresses "no such row" as a file status rather
    //     than as a distinct outcome, so the paragraph carries a working-storage flag -- set at line
    //     478, tested at lines 495 to 499 -- to move that fact ten statements forward past an error
    //     check sharing the same status field. An Optional carries it in the value itself, so the
    //     branch is taken on the result of this call and no flag has a counterpart here.
    // Alternatives Considered: a three-argument derived finder over the three key properties.
    //     Rejected because the entity models the key as ONE embedded identifier, as the reference does
    //     at app/cbl/CBACT04C.cbl:31 with RECORD KEY IS FD-TRAN-CAT-KEY, a group item. A finder taking
    //     the whole key cannot drift from the entity's own identity type, whereas three loose
    //     arguments could be reordered at a call site and still compile -- and the two code arguments
    //     are adjacent short strings, so a transposition would fail only as a lookup that quietly
    //     matches nothing.
    // Assumptions: the single-result shape is a claim about the SCHEMA rather than a convenience of
    //     the return type. The owning migration keys this table on exactly these three columns as
    //     pk_transaction_category_balances, so the key matches at most one row and the query is
    //     provably single valued. Were that constraint absent, this same signature would be a latent
    //     runtime failure, raised the first time a second row matched.
    Optional<TransactionCategoryBalance> findByIdIs(
            TransactionCategoryBalance.TransactionCategoryBalanceId id);

    /**
     * Opens a forward-only walk of the whole table in composite-key order, which is the sequential
     * drive of the interest-accrual job.
     *
     * <p>This is the migrated form of the reference read loop rather than a convenience over it.
     * Accrual reads to end of file in one pass at {@code app/cbl/CBACT04C.cbl:188-190} and never
     * revisits a row, so one forward cursor expresses it exactly. The ordering is account identifier,
     * then transaction type code, then transaction category code, and the type documentation above
     * records the arithmetic that proves it is the reference key order.</p>
     *
     * @return a lazily populated {@code Stream<TransactionCategoryBalance>} delivering every row of
     *     the table in ascending composite-key order, and an EMPTY stream when the table holds no
     *     rows. The caller owns two obligations a materialised return would not impose: it must
     *     CONSUME the stream inside the transaction it already holds, because the underlying database
     *     cursor stays valid only for that transaction's duration, and it must CLOSE the stream,
     *     which means a try-with-resources block at the call site rather than a bare assignment
     * @throws org.springframework.transaction.IllegalTransactionStateException if the caller holds no
     *     transaction when it calls this method, since the propagation declared below is
     *     {@code MANDATORY} and a cursor cannot outlive a transaction that was never started
     */
    // Trade-offs: a Stream return rather than a materialised one. The reference walk is bounded only
    //     by end of file, and its memory profile is flat in the size of the table rather than linear
    //     in it, which a materialised return abandons by building the whole relation on the heap
    //     before the caller sees a row. The compromise accepted is the heavier call-site contract
    //     stated in the return description; it is bounded and local, whereas the cost a materialised
    //     return defers is unbounded and grows with every posting run that adds a category.
    // Alternatives Considered: the default REQUIRED propagation, which is the shorter annotation and
    //     the one the words "read-only transaction" suggest. Rejected because it is actively
    //     misleading on a method that returns a cursor: REQUIRED would start a transaction when no
    //     caller had one, commit it as this method RETURNED, and hand back a stream whose cursor was
    //     already closed -- so the failure would surface at the first element, inside the caller's
    //     loop, naming neither this method nor the missing transaction. MANDATORY refuses the call
    //     outright and names the actual mistake at the actual call site.
    // Trade-offs: readOnly is declared and is nonetheless inert, and saying so is better than
    //     leaving a reader to find it out. Because propagation is MANDATORY this method always joins
    //     a caller's transaction, and a joined definition's read-only flag does not override the
    //     transaction already in progress. It is kept because it states the intent of the method at
    //     the method, and because it is the flag that would govern were the propagation ever
    //     relaxed. It is emphatically NOT what keeps this module's writes to this table legitimate:
    //     that rests on the scoped cross-schema grant recorded above.
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    // Assumptions: the fetch-size hint is what makes this stream actually stream. The driver opens a
    //     server-side cursor only when a positive fetch size and a non-auto-commit connection both
    //     hold; with either missing it buffers the whole result client-side, which on a large table
    //     is heap exhaustion rather than a slowdown. Only POSITIVITY carries that property, so the
    //     streaming contract of this method rests on the value being above zero and not on which
    //     value it is.
    // Trade-offs: the hint is an intentional per-query OVERRIDE and cannot track the session default,
    //     because a query hint is a compile-time constant while the profiles differ -- the base
    //     declares 100 at application.yml:764, the development profile narrows the session default to
    //     25 and the production profile widens it to 250. This walk therefore reads 100 rows per round
    //     trip under every profile. What is given up is per-environment tuning of this walk; what is
    //     bought is a window fixed at the method, which no external property can set to zero and
    //     thereby convert into a full client-side buffer. The constant is aligned with the base value
    //     so the default deployment behaves identically whichever governs.
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "100"))
    Stream<TransactionCategoryBalance> findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc();
}
