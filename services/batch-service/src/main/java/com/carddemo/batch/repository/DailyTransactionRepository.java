package com.carddemo.batch.repository;

import com.carddemo.batch.domain.DailyTransaction;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.stream.Stream;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the unposted daily-transaction feed that drives the preflight and posting jobs.
 *
 * <p>The table behind this interface is {@code ledger.daily_transactions}, and one row of it stands
 * for one transaction that has been extracted and loaded but has not yet been validated or posted.
 * Two migrated jobs consume it and both consume it the same way -- front to back, once, in a single
 * pass -- and each row leaves the feed as either a posted transaction or a rejected one rather than
 * as an update to itself. Nothing in this module writes it, so this interface declares reads and
 * nothing else.</p>
 *
 * <h2>Why every member here is a read</h2>
 *
 * <p>Alternatives Considered: {@code JpaRepository}, which is the base type all three siblings in
 * this package extend and which would have made this file shorter. Rejected because it inherits
 * {@code save}, {@code saveAll}, {@code delete}, {@code deleteAll} and {@code flush}, and this
 * interface must expose none of them. {@code org.springframework.data.repository.Repository} is the
 * marker that gives Spring Data enough to build a proxy while contributing no member of its own, so
 * the reachable surface is exactly the two methods declared below and the read-only guarantee is
 * structural rather than a convention a later edit could relax by adding one call.</p>
 *
 * <p>Assumptions: the feed genuinely has no writer in this module, and the evidence for that is
 * documentary rather than stylistic. {@code app/cbl/CBTRN02C.cbl:29-32} selects it as
 * {@code ORGANIZATION IS SEQUENTIAL} with {@code ACCESS MODE IS SEQUENTIAL} and declares no record
 * key at all; the driving loop at {@code app/cbl/CBTRN02C.cbl:202-219} advances it through the
 * single sequential {@code READ} at {@code app/cbl/CBTRN02C.cbl:346}, and
 * {@code app/cbl/CBTRN01C.cbl:164-186} reads it while writing nothing anywhere. The rows are
 * produced upstream instead, by the extract-and-load package from the seed dataset, and the entity
 * this interface is typed on is mapped {@code @Immutable} to match. A mutator here would offer a
 * capability the domain model itself refuses.</p>
 *
 * <h2>The ordering key, and the two keys it is deliberately not</h2>
 *
 * <p>Both methods below order by the ingestion ordinal the entity declares as {@code ingestSeq}.
 * That is the most consequential decision in this file, because a walk in a different order still
 * returns every row and still produces output a golden-master comparison rejects, so the ordering is
 * part of the contract rather than a convenience.</p>
 *
 * <p>Alternatives Considered: ordering by the feed's PROCESSING STAMP, which its name invites and
 * which a reader arriving from the copybook may reach for first. Rejected because it cannot supply
 * an order at all, and two independent measurements establish that. The seed extract leaves the
 * field blank: reading the twenty-six bytes at zero-based offset 304 across
 * {@code app/data/ASCII/dailytran.txt} finds them blank on 300 of 300 records, while the origination
 * stamp at offset 278 is populated on 300 of 300, so the blankness is specific to this field rather
 * than a gap in the extract, and a blank field decodes to an absent value. And no reference program
 * or job reads the field: a search for {@code DALYTRAN-PROC-TS} across the whole of
 * {@code app/cbl} and {@code app/jcl} returns nothing at all. The stamp that does exist belongs to
 * the POSTED record and is minted only after this walk has already delivered the row --
 * {@code app/cbl/CBTRN02C.cbl:437} obtains a fresh value and {@code app/cbl/CBTRN02C.cbl:438} moves
 * it into {@code TRAN-PROC-TS}, whereas {@code app/cbl/CBTRN02C.cbl:436} copies the origination
 * stamp across from the feed unaltered. Ordering a walk on a column that is absent on every row the
 * walk returns is a silent non-determinism, which is the class of defect a golden master catches
 * late and expensively.</p>
 *
 * <p>Alternatives Considered: ordering by the TRANSACTION IDENTIFIER, which is the more plausible of
 * the two wrong answers, because that identifier is a copybook field, is sixteen characters wide,
 * and is distinct on all 300 records of the current extract. Rejected because the distinctness is a
 * property of one extract rather than a constraint anything asserts. The source feed carries no
 * record key, per the file-control citation above, and {@code app/jcl/POSTTRAN.jcl:30-31} supplies
 * it as the physical sequential dataset {@code AWS.M2.CARDDEMO.DALYTRAN.PS}, so a feed repeating an
 * identifier is a feed the reference pipeline reads twice without complaint; the owning migration
 * declares no unique constraint over that column in consequence. Ordering on it would therefore give
 * a PARTIAL order, and a partial order has no safe page boundary in either direction: a strict
 * comparison drops every remaining row sharing the boundary value, while an inclusive one returns
 * the whole tied group again. Against a feed the posting job walks, the first loses transactions that
 * were never posted and the second posts transactions twice, and both do it silently.</p>
 *
 * <p>Assumptions: the ingestion ordinal is a TOTAL order, which is what removes that choice instead
 * of trading one hazard for another. The owning migration
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} declares it at
 * its line 401 as {@code ingest_seq BIGINT GENERATED BY DEFAULT AS IDENTITY} and keys the table on it
 * alone at its line 537 as {@code pk_daily_transactions}, so it is unique by construction; a
 * single-writer sequential load assigns it in the order it reads, so it is monotonic in arrival order
 * as well, and ordering by it reproduces the order the reference's sequential read covers the dataset
 * in. That migration records the same consequence for paging at its lines 526 to 531. The comparison
 * is also arithmetic over a generated integer, so it raises none of the blank-padding or
 * lexicographic-versus-numeric questions a declared-width character key would.</p>
 *
 * <p>Assumptions: this is the one ordered walk in this package with no key definition to cite, and
 * the absence is the point rather than an oversight. The package charter settles the category-balance
 * ordering from {@code app/jcl/TCATBALF.jcl:40} and the combine ordering from
 * {@code app/jcl/COMBTRAN.jcl:28-30} because both of those datasets are keyed clusters. This dataset
 * is not keyed, so its only order is physical arrival order, and the ingestion ordinal is the column
 * that carries it.</p>
 *
 * <h2>Rulings this interface inherits from the package charter</h2>
 *
 * <p>Alternatives Considered: native SQL, which is a natural reach in a batch module because a
 * nightly pass is set-shaped and reads more directly as one statement. Rejected on the timing of the
 * failure it admits. This module runs the persistence provider with schema handling set to
 * {@code validate}, and that pass compares MAPPING METADATA against the deployed table; it never
 * parses the text of a native query. A property path resolving to a column the schema does not have
 * is therefore reported at start-up, before a row is read, whereas a mistyped physical column inside
 * a native statement stays invisible until that statement executes -- which for this module means
 * part-way through a nightly chain with earlier steps already committed. Both members below are
 * derived methods bound to property names declared on {@code DailyTransaction}, so no physical column
 * name appears anywhere in this file.</p>
 *
 * <p>Assumptions: the offset-pagination vocabulary the charter prohibits package-wide appears on no
 * method, parameter or return type here, and the row cap is
 * {@code org.springframework.data.domain.Limit} for that reason. Counting rows from the start of an
 * ordered set means an insert landing before the cursor changes how many rows precede it, so a scan
 * positioned that way skips rows it never read and repeats rows it already read. This feed is exposed
 * to exactly that, because it is loaded by one process while a job walks it. A key already returned
 * keeps its place in the ordering no matter what is inserted around it, which is why the cursor here
 * is a key.</p>
 *
 * <p>Assumptions: {@code com.carddemo.common.web.PageResponse} is neither returned nor imported. It
 * is the HTTP keyset envelope, and it exists because a stateless request handler cannot remember
 * where a caller had reached. This module has no client and no REST surface beyond the actuator
 * health probe, so a continuation key here never leaves the process that read it.</p>
 *
 * <h2>One member a reader will look for and not find</h2>
 *
 * <p>Alternatives Considered: a finder returning a single feed row by identifier, which is the member
 * a reader expects on any repository and which the narrow base type does not supply for free.
 * Declined because no caller needs one and because neither candidate key would make it single
 * valued. Both consumers of this table scan it -- {@code app/cbl/CBTRN02C.cbl:202-219} and
 * {@code app/cbl/CBTRN01C.cbl:164-186} -- and neither keys into it at any point, so a keyed finder
 * would have no counterpart in the behaviour being migrated. A finder on the transaction identifier
 * could not return at most one row in any case, for the partial-order reason recorded above; a finder
 * on the ingestion ordinal would be single valued but would be answering a question no step asks,
 * since a step that holds an ordinal holds it in order to continue from it, which is what the
 * continuation finder below is for. It is added when a caller genuinely needs it, with its own
 * recorded rationale, rather than pre-emptively now.</p>
 *
 * @see DailyTransaction
 */
public interface DailyTransactionRepository extends Repository<DailyTransaction, Long> {

    /**
     * Opens a forward-only walk of the whole feed in ingestion order, which is the sequential drive
     * of the preflight and posting jobs.
     *
     * <p>This is the migrated form of the reference read loop rather than a convenience over it. The
     * posting driver at {@code app/cbl/CBTRN02C.cbl:202-219} reads to end of file in one pass and
     * never revisits a record, and the preflight at {@code app/cbl/CBTRN01C.cbl:164-186} does the
     * same, so one forward cursor expresses both exactly.</p>
     *
     * @return a lazily-populated {@code Stream<DailyTransaction>} delivering every row of the feed in
     *     ascending ingestion order, empty when the feed holds no rows. The caller owns two
     *     obligations that a materialised return would not impose: it must CONSUME the stream inside
     *     the transaction it already holds, because the underlying database cursor stays valid only
     *     for that transaction's duration, and it must CLOSE the stream, which means a
     *     try-with-resources block at the call site rather than a bare assignment
     * @throws org.springframework.transaction.IllegalTransactionStateException if the caller holds no
     *     transaction when it calls this method, since the propagation declared below is
     *     {@code MANDATORY} and a cursor cannot outlive a transaction that was never started
     */
    // Trade-offs: a Stream return rather than a List. A list was evaluated and rejected because it
    //     materialises the entire feed into one heap before the caller sees a single row, which
    //     abandons the memory profile of the forward-only cursor at
    //     app/cbl/CBTRN02C.cbl:29-32 -- a profile that is flat in the size of the feed rather than
    //     linear in it. The compromise accepted is a heavier call-site contract than a list would
    //     impose: the caller must hold a transaction open for the whole walk and must close the
    //     stream. That cost is bounded and local, whereas the cost a list defers is unbounded and
    //     grows with every extract, so the heavier contract is the cheaper of the two.
    // Assumptions: the walk is unbounded on purpose, which is what distinguishes it from the
    //     continuation finder below and from the bounded walks the sibling interfaces expose. The
    //     reference read has no chunk size because it reads to end of file, and a caller that wants
    //     bounded reads asks the continuation finder for them instead. Laziness is what makes an
    //     unbounded return safe here: the row count never becomes a heap requirement.
    // Alternatives Considered: the default REQUIRED propagation, which is the shorter annotation and
    //     the one the two words "read-only transaction" suggest. Rejected because it is actively
    //     misleading on a method that returns a cursor. REQUIRED would start a transaction when no
    //     caller had one, commit it as this method RETURNED, and hand back a stream whose cursor was
    //     already closed -- so the failure would surface at the first element, inside the caller's
    //     loop, naming neither this method nor the missing transaction. MANDATORY refuses the call
    //     outright and names the actual mistake at the actual call site.
    // Trade-offs: readOnly is declared and is nonetheless inert, and saying so is better than
    //     leaving a reader to discover it. Because propagation is MANDATORY this method always joins
    //     a caller's transaction, and a joined definition's read-only flag does not override the
    //     transaction already in progress. It is retained because it states the intent of the method
    //     at the method, and because it is the flag that would govern if the propagation were ever
    //     relaxed. The enforcement of read-only access rests on the base type above and on the grant,
    //     not on this attribute.
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    // Assumptions: the fetch-size hint is what makes this stream actually stream. The driver opens a
    //     server-side cursor only when a positive fetch size and a non-auto-commit connection both
    //     hold; with either missing it buffers the whole result client-side, which on a large feed is
    //     heap exhaustion rather than a slowdown. The value matches the module-wide
    //     hibernate.jdbc.fetch_size in this module's application.yml deliberately, so the two cannot
    //     disagree, and it is declared HERE as well so the streaming contract of this method does not
    //     depend on a property another profile could change.
    // Alternatives Considered: a fetch size of one, which would reproduce the reference's
    //     record-at-a-time input-output pattern literally. Rejected because parity is owed to the
    //     semantics and not to the round-trip count: app/cbl/CBTRN02C.cbl:202-219 advances one record
    //     at a time because a sequential read returns one record, not because records per
    //     input-output operation form part of any compared output. The annotation's counting flag is
    //     left at its default because this interface declares no counting query for it to reach.
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "100"))
    Stream<DailyTransaction> findAllByOrderByIngestSeqAsc();

    /**
     * Continues the walk after a given ingestion ordinal, which is how a restarted step resumes the
     * feed rather than reprocessing it from the beginning.
     *
     * <p>This exists for resumption and not for reading in convenient chunks. A redriven
     * state-machine execution restarts a step that may already have processed part of its input, and
     * this finder is what turns the ordinal it resumes from back into the remainder of the walk.</p>
     *
     * @param lastIngestSeq the ingestion ordinal of the last row the caller already processed, of
     *     type {@code Long}, treated as an EXCLUSIVE lower bound so that the row carrying it is not
     *     returned again; must not be {@code null}, and a caller starting from the beginning uses the
     *     unbounded walk above rather than passing a sentinel here
     * @param limit the greatest number of rows to return, of type {@code Limit}, which the caller
     *     sets from its own chunk size; must not be {@code null}
     * @return the matching rows as a {@code List<DailyTransaction>} in ascending ingestion order,
     *     holding at most the requested number of rows and never {@code null}. An EMPTY list means no
     *     rows remain after the given ordinal, which is the ordinary way this walk ends rather than an
     *     error, since a sequential read discovers the end by reaching it
     */
    // Assumptions: the predicate is STRICTLY greater rather than greater-or-equal, and the direction
    //     is a correctness decision rather than a stylistic one. The caller resumes from the ordinal
    //     of a row it has already processed, so an inclusive bound would hand that row back and the
    //     step would process it twice; under the additive posting model of
    //     app/cbl/CBTRN02C.cbl:202-219, where each accepted record contributes to a balance, a
    //     reprocessed row is a double-counted amount rather than a harmless repeat. A gap in the
    //     ordinal sequence cannot cause the opposite failure, because this is a range test and not an
    //     arithmetic step to the next value.
    // Assumptions: the ordinal the caller passes comes from the framework's own step ExecutionContext,
    //     persisted with the step's chunk commit and restored into the same step on a restart. It does
    //     NOT come from batch.batch_run: that table's migration declares seven columns -- id, run_id,
    //     step_name, status, started_at, finished_at and return_code -- and none of them could hold a
    //     cursor. The two mechanisms answer different questions, and the package charter records the
    //     split in full: batch.batch_run answers whether a step already completed for a run, which is
    //     the idempotency question a redrive asks before doing anything, while the execution context
    //     answers how far into its input the step had got, which is the question this finder serves.
    // Alternatives Considered: offset pagination, in either shape the framework offers for it.
    //     Rejected because it does not preserve the behaviour it would replace, as the charter records
    //     for the whole package: positioning by counting rows means a concurrent insert ahead of the
    //     cursor pushes an unread row past the boundary and a concurrent delete pulls an already-read
    //     row back inside it. A keyed browse has no such behaviour, and this module's correctness is
    //     judged by comparing committed output against a golden master, so a read discipline that can
    //     skip or repeat a row is not a near-equivalent of the reference -- it is a different program.
    // Trade-offs: a keyset query cannot report a total row count without a second query, so no count
    //     is offered and no caller can derive a progress percentage from this interface. Nothing
    //     observable is given up: app/cbl/CBTRN02C.cbl:206 counts records as it processes them and
    //     never asks the dataset how many it holds, so the reference reported no total either.
    // Alternatives Considered: a Stream return here too, for symmetry with the walk above. Rejected
    //     because the two methods have genuinely different lifetimes. The walk is consumed once inside
    //     one transaction, whereas a bounded chunk is read, its transaction committed, and its last
    //     ordinal recorded before the next chunk is asked for -- so a lazy return would be forced shut
    //     at exactly the commit that makes the chunk durable. A list is what a bounded read returns.
    List<DailyTransaction> findByIngestSeqGreaterThanOrderByIngestSeqAsc(Long lastIngestSeq,
            Limit limit);
}
