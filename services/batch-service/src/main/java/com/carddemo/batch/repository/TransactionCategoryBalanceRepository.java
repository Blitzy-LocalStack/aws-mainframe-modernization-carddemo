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
 * <p>The table behind this interface is {@code ledger.transaction_category_balances}, and one row of
 * it carries the accumulated balance for one account, one transaction type and one transaction
 * category. It is the only table this module touches that two migrated jobs reach in two
 * structurally different ways, and that single fact shapes every member declared below.</p>
 *
 * <h2>One cluster, two access disciplines</h2>
 *
 * <p>The reference decided its access discipline per program rather than per dataset, and the two
 * programs sharing this cluster decided differently. Posting selects it at
 * {@code app/cbl/CBTRN02C.cbl:57-61} as {@code ORGANIZATION IS INDEXED} with
 * {@code ACCESS MODE IS RANDOM} and {@code RECORD KEY IS FD-TRAN-CAT-KEY}, so it reaches exactly one
 * row at a time by a key it has just assembled at {@code app/cbl/CBTRN02C.cbl:469-471}. Interest
 * accrual selects <b>the same cluster</b> at {@code app/cbl/CBACT04C.cbl:28-32} as
 * {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS SEQUENTIAL} and the same record key, so
 * it never keys into the table at all and instead walks it end to end through the single read at
 * {@code app/cbl/CBACT04C.cbl:326}.</p>
 *
 * <p>Refactoring Rationale: each of those disciplines was a property of a file-control entry sitting
 * hundreds of lines above the statement that depended on it, and neither program could see the
 * other's choice. Both become named, separately documented methods on this one interface, so a
 * reader asking how this table is reached finds both answers in one place instead of in two
 * file-control paragraphs in two programs. The keyed read is {@code findByIdIs} and the ordered walk
 * is {@code findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc}; there is no third way in.</p>
 *
 * <h2>Why this module writes a table it does not own</h2>
 *
 * <p>Assumptions: the write authority here is a GRANT and not ownership. The table belongs to
 * {@code transaction-service}, which creates it in
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql}; this module
 * reaches it against the same database cluster under a dedicated role holding the narrowly scoped
 * cross-schema write grant on {@code ledger} that the migration plan's section 0.4.1.3 records as
 * the one documented exception to database-per-service purity in the whole migration.</p>
 *
 * <p>Alternatives Considered: calling {@code transaction-service} over HTTP to perform the write,
 * which is what database-per-service purity would otherwise require. Rejected because a remote hop
 * cannot enlist in the caller's transaction, and this table's write is the FIRST of three that have
 * to commit together: {@code app/cbl/CBTRN02C.cbl:440-442} performs the category-balance update, the
 * account update and the transaction write in immediate succession with <b>no intervening commit
 * verb</b>, so section 0.4.1.3 requires them to stay one ACID commit. A saga with compensating
 * reversals and a transactional outbox were both weighed and rejected on the same ground: either one
 * makes a partially posted state observable -- a written transaction whose balance has not moved --
 * which does not arise in the reference and which the golden masters would correctly report as a
 * parity failure.</p>
 *
 * <p>Trade-offs: reaching a table this module does not own means the owning migration is the
 * authority on its shape and this interface is passive about it. No data definition, no
 * {@code @Index} and no constraint is declared here or on the entity beyond the identity the
 * mapping needs, so a disagreement about the physical shape is settled by reading
 * {@code V1__ledger.sql} rather than by reading two mappings and guessing which one deployed. The
 * cost accepted is that a change to that table is coordinated across two contexts.</p>
 *
 * <h2>An absent row is a normal outcome, and the whole posting branch turns on it</h2>
 *
 * <p>Refactoring Rationale: the keyed read below replaces the {@code READ} with an
 * {@code INVALID KEY} handler at {@code app/cbl/CBTRN02C.cbl:474-479}, and in that paragraph
 * not-found is an ORDINARY result rather than a failure. Line 473 clears the create flag, the
 * {@code INVALID KEY} branch at line 478 sets it, and line 481 then accepts file status {@code '23'}
 * -- record not found -- ALONGSIDE {@code '00'} as a clean outcome, so only some other status
 * reaches the abend path at lines 489 to 492. Lines 495 to 499 branch on that flag. An empty
 * {@code Optional} from this interface therefore means <b>no balance row exists yet for this
 * key</b>, which is what every first transaction in a category produces; it is not an error, and a
 * caller must neither log it as one nor turn it into a thrown exception.</p>
 *
 * <p>Assumptions: the caller expresses the create-versus-update branch by asking for the row and
 * falling back to the entity's zero-balance constructor when none came back, which is the exact
 * analogue of {@code INITIALIZE TRAN-CAT-BAL-RECORD} at {@code app/cbl/CBTRN02C.cbl:504}. That
 * initialisation is the least obvious statement in the paragraph and the reason it exists is worth
 * recording, because a reader who does not know COBOL file semantics reads it as redundant: line 474
 * reads <b>INTO a working-storage record</b>, so on a not-found that record still holds the previous
 * iteration's bytes, and without the initialisation the previous row's balance would carry into the
 * new row. A Java object is constructed fresh, so that carry-over cannot arise here at all -- but
 * the zero starting balance is still stated explicitly rather than left implied.</p>
 *
 * <p>Assumptions: both reference arms are ADDITIVE and differ only by that initialisation. The
 * create arm initialises, moves the three key components and then adds the transaction amount at
 * {@code app/cbl/CBTRN02C.cbl:504-508} before writing at line 510; the update arm adds the same
 * amount to the value already read at line 527 before rewriting at line 528. The migration plan's
 * section 0.5.1.7 requires the two to remain distinguishable and separately tested, which is why no
 * upsert or conflict-resolving helper is declared here and why the caller must not collapse them
 * into an opaque merge that hides which arm ran. The decision is the caller's, taken on the result
 * of the read below, and the write is then the inherited {@code save}.</p>
 *
 * <h2>The walk ordering, and the arithmetic that settles it</h2>
 *
 * <p>Assumptions: the walk orders by account identifier, then transaction type code, then
 * transaction category code, and that ordering IS the reference key order rather than an inference
 * about it. Two artifacts written independently of each other prove it arithmetically.
 * {@code app/jcl/TCATBALF.jcl:40} defines the cluster with {@code KEYS(17 0)}, a seventeen-byte key
 * beginning at position zero, so the key is the first seventeen bytes of the record and nothing
 * else. {@code app/cpy/CVTRA01Y.cpy:6-8} declares the leading fields as an eleven-digit account
 * identifier, a two-character type code and a four-digit category code, and 11 plus 2 plus 4 is
 * exactly 17 in that declared sequence. The group item at {@code app/cpy/CVTRA01Y.cpy:5} is
 * therefore precisely the primary key, in precisely that column order. The owning migration then
 * declares the same sequence a third time and independently, as the composite primary key
 * {@code (account_id, type_cd, category_cd)}.</p>
 *
 * <p>Assumptions: that ordering has to be HONOURED rather than merely recorded, because a downstream
 * control break depends on it. {@code app/cbl/CBACT04C.cbl:28-32} opens this cluster
 * {@code ACCESS MODE IS SEQUENTIAL}, so accrual receives its rows in key order for free; line 194
 * then detects a change of account by comparing the current row's account identifier against the
 * previous one, and line 196 flushes the account it is leaving. A break on account change is sound
 * only while every row of an account arrives consecutively, which is exactly what a leading account
 * column guarantees. A relational query has no default order at all, so an unordered walk would
 * split one account into several apparent groups and flush it once per group.</p>
 *
 * <p>Alternatives Considered: ordering by the account identifier alone, which is all the control
 * break itself examines. Rejected because it leaves the WITHIN-ACCOUNT order unspecified, and that
 * order is observable: {@code app/cbl/CBACT04C.cbl:193} emits one line per row scanned -- the whole
 * fifty-byte record, whose length {@code app/jcl/TCATBALF.jcl:41} declares as
 * {@code RECORDSIZE(50 50)} -- and that stream is what the golden-master oracle compares. An
 * unspecified order among an account's rows would make a byte-compared output non-deterministic,
 * which is the class of defect a golden master catches late and expensively.</p>
 *
 * <p>Trade-offs: the walk is streamed rather than chunked into independent keyed reads, because the
 * control break needs strictly monotonic account order across the ENTIRE input and a per-chunk query
 * cannot guarantee that an account's rows do not straddle a chunk boundary in a way the caller
 * mishandles. The compromise accepted is a heavier call-site contract than a materialised return
 * would impose: the caller holds one transaction open across the whole walk and closes the stream.
 * That cost is bounded and local, whereas the heap a materialised return needs grows with the
 * table.</p>
 *
 * <h2>One member a reader will look for and not find</h2>
 *
 * <p>Assumptions: <b>no keyset-continuation finder is declared</b>, and the absence follows from the
 * streaming decision above rather than from oversight. The walk is one forward cursor inside one
 * transaction, so a step that fails rolls that transaction back whole and a redriven step re-runs it
 * whole; there is no committed position below the step to resume from, and the resumption unit is
 * therefore the step itself. What makes that safe is the durable step ledger reached by
 * {@code BatchRunRepository}: a step that already recorded completion for a run becomes a no-op
 * instead of repeating its writes, so re-running from the beginning cannot accumulate an amount
 * twice.</p>
 *
 * <p>Assumptions: {@code batch.batch_run} is that step idempotency key and is <b>not</b> a cursor
 * store, which is worth stating because the two are easy to conflate.
 * {@code services/batch-service/src/main/resources/db/migration/V1__batch.sql} declares exactly
 * seven columns on it -- {@code id}, {@code run_id}, {@code step_name}, {@code status},
 * {@code started_at}, {@code finished_at} and {@code return_code} -- and none of them could hold a
 * position. Where the framework does keep a position is its own step execution context, persisted
 * with the step's chunk commit; the package charter records that split in full.</p>
 *
 * <p>Alternatives Considered: declaring a continuation finder anyway, resuming on the account
 * identifier alone. Rejected on correctness rather than on need. With a three-part key an
 * account-only bound is wrong in both directions -- an exclusive bound abandons the remainder of a
 * partly walked account, and an inclusive one hands its already-walked rows back -- and under the
 * additive model of {@code app/cbl/CBTRN02C.cbl:508} and {@code :527} a re-delivered row is an
 * amount accumulated twice rather than a harmless repeat. Expressing it correctly needs a lexicographic
 * predicate over the whole three-part tuple, which is more machinery than any current caller asks
 * for, so it is added when one genuinely needs it and carries its own recorded rationale then rather
 * than standing here now with none.</p>
 *
 * <p>Alternatives Considered: a finder returning one account's rows, and a bounded list read of the
 * whole table. Both are declined because no caller in {@code com.carddemo.batch.job} or
 * {@code com.carddemo.batch.service} needs either, and neither has a counterpart in the behaviour
 * being migrated: accrual scans this table and never keys into it, so a per-account read answers a
 * question the reference never asks. A member with no caller is surface that has to be maintained
 * and that no test can meaningfully exercise.</p>
 *
 * <h2>Rulings this interface inherits from the package charter</h2>
 *
 * <p>Alternatives Considered: native SQL, which is a natural reach in a batch module because a
 * nightly pass is set-shaped and reads more directly as one statement. Rejected on the TIMING of the
 * failure it admits. This module runs the persistence provider with schema handling held at
 * {@code validate}, and that pass compares mapping metadata against the deployed table; it never
 * parses the text of a native query. A property path resolving to a column the schema does not have
 * is therefore reported at start-up, before a row is read, whereas a mistyped physical column inside
 * a native statement stays invisible until that statement executes -- which for this module means
 * part-way through a nightly chain with earlier steps already committed. Both members below are
 * derived methods bound to property names declared on {@link TransactionCategoryBalance}, so no
 * physical column name appears anywhere in this file.</p>
 *
 * <p>Assumptions: the offset-pagination vocabulary the charter prohibits package-wide appears on no
 * method, parameter or return type here, and neither does the HTTP keyset envelope
 * {@code com.carddemo.common.web.PageResponse} or its companion token. Counting rows from the start
 * of an ordered set means a concurrent insert ahead of the cursor pushes an unread row past the
 * boundary and a concurrent delete pulls an already-read row back inside it; a browse by key has no
 * such behaviour, and this module's correctness is judged by comparing committed output against a
 * golden master, so a read discipline that can skip or repeat a row is a different program rather
 * than a near-equivalent. The envelope is absent for a simpler reason: it exists because a stateless
 * request handler cannot remember where a caller had reached, and this module has no client.</p>
 *
 * <p>Assumptions: no arithmetic is performed here and no monetary type other than the entity's own
 * appears. The additive update that {@code app/cbl/CBTRN02C.cbl:508} and {@code :527} perform belongs
 * to {@code com.carddemo.batch.service.CategoryBalanceService}, which forms the new balance through
 * {@code com.carddemo.common.money.Money} so that the single arithmetic boundary the migration plan's
 * transformation rules T3 and T4 require is not duplicated into this package. A repository names a
 * query and returns rows. Rule A3 of
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * fails the build on a binary floating-point type in any member position, including as a generic
 * type argument, so the prohibition is executable rather than advisory.</p>
 *
 * <p>Alternatives Considered: importing the nested identifier type directly, which is what the
 * sibling {@code DisclosureGroupRepository} does and which would shorten the declaration below.
 * Written in qualified {@code TransactionCategoryBalance.TransactionCategoryBalanceId} form instead,
 * so that the identifier is visibly the entity's OWN nested type rather than a same-named type from
 * somewhere else. The entity records at its own nested declaration that this name was chosen over a
 * bare {@code Id} precisely because it appears unqualified in a repository query signature, and the
 * qualified spelling carries that intent one step further at the cost of a longer line.</p>
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
    // Refactoring Rationale: this replaces the READ with an INVALID KEY handler at
    //     app/cbl/CBTRN02C.cbl:474-479. The reference expresses "no such row" as a file status
    //     rather than as a distinct outcome, so the paragraph needs a working-storage flag -- set at
    //     line 478, tested at lines 495 to 499 -- to carry that fact ten statements forward past an
    //     error check that shares the same status field. An Optional carries it in the value itself,
    //     so the flag has no counterpart here and the branch is taken on the result of this call.
    // Alternatives Considered: a three-argument derived finder over the three key properties.
    //     Rejected because the entity models the key as ONE embedded identifier, which is what the
    //     reference models too: app/cbl/CBACT04C.cbl:31 declares RECORD KEY IS FD-TRAN-CAT-KEY, a
    //     group item, not three separate keys. A finder taking the whole key therefore cannot drift
    //     from the entity's own identity type, whereas three loose arguments could be reordered at a
    //     call site and still compile -- and the two code arguments are adjacent short strings, so a
    //     transposition would fail only as a lookup that quietly matches nothing.
    // Alternatives Considered: relying on the inherited findById alone, which expresses the same
    //     query and would leave this interface with one member. Declined for two reasons. The
    //     arm-selecting read is the single most consequential statement in the posting job, and the
    //     traceability citation for app/cbl/CBTRN02C.cbl:474 needs a named, separately documented
    //     method to point at rather than an inherited one whose contract is stated elsewhere; this
    //     block is also where the empty-is-normal semantic above becomes visible to the caller that
    //     depends on it. The existing caller in com.carddemo.batch.service already binds to this
    //     name, so the inherited member is not a free substitute either.
    // Assumptions: the single-result shape is a claim about the SCHEMA rather than a convenience of
    //     the return type. The owning migration keys this table on exactly these three columns as
    //     pk_transaction_category_balances, so the key matches at most one row and the query is
    //     provably single valued. Were that constraint absent, this same signature would be a latent
    //     runtime failure instead, raised the first time a second row matched.
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
    // Refactoring Rationale: this note used to say the value matches the module-wide
    //     hibernate.jdbc.fetch_size "so the two cannot disagree". They can. The base declares 100 at
    //     application.yml:764, which is where the numeric agreement comes from, but
    //     application-dev.yml:215 narrows the session default to 25 and application-prod.yml:426
    //     widens it to 250. A query hint is a compile-time constant and cannot track either, so the
    //     sentence asserted an invariant nothing enforces. The same correction is made at the two
    //     sibling interfaces that carried the identical wording, so all three describe one mechanism.
    // Trade-offs: this hint is therefore an intentional per-query OVERRIDE. It governs the statement
    //     it annotates, so this walk reads 100 rows per round trip under every profile regardless of
    //     the session default. What is given up is per-environment tuning of this walk; what is bought
    //     is a window fixed at the method, which no external property can set to zero and thereby
    //     convert into a full client-side buffer. The constant is aligned with the base value so the
    //     default deployment behaves identically whichever governs.
    // Alternatives Considered: a fetch size of one, which would reproduce the reference's
    //     record-at-a-time pattern literally, since app/cbl/CBACT04C.cbl:326 returns one record per
    //     read. Rejected because parity is owed to the semantics and not to the round-trip count:
    //     the reference advances one row at a time because a sequential read returns one row, not
    //     because rows per input-output operation form part of any compared output.
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "100"))
    Stream<TransactionCategoryBalance> findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc();
}
