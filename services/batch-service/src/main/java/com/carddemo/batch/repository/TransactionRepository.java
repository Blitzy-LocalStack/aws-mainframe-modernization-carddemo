package com.carddemo.batch.repository;

import com.carddemo.batch.domain.Transaction;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.stream.Stream;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the posted-transaction master and walks it in transaction-identifier order.
 *
 * <p>The table behind this interface is {@code ledger.transactions}, and one row of it stands for
 * one settled transaction. It is the output of the nightly chain rather than its input: two migrated
 * jobs insert into it and a third reads it back in order to rebuild the master, which is why this is
 * the one interface in the package that carries both a write surface and an ordered walk.</p>
 *
 * <h2>Why this interface writes a table it does not own</h2>
 *
 * <p>Assumptions: the write here is authorised by a grant and not by ownership. The {@code ledger}
 * schema belongs to {@code transaction-service}; this module reaches it against the same database
 * cluster under a dedicated role holding narrowly-scoped cross-schema write access on
 * {@code ledger}, which the migration plan records at its section 0.4.1.3 as the one documented
 * exception to database-per-service purity in the whole migration. Reading that as a pattern to copy
 * rather than as a bounded concession is the mistake this paragraph exists to prevent.</p>
 *
 * <p>The fact that forces the concession is a single paragraph of the reference program. Posting's
 * {@code 2000-POST-TRANSACTION} paragraph opens at {@code app/cbl/CBTRN02C.cbl:424} and performs
 * three writes in immediate succession: the category-balance update at
 * {@code app/cbl/CBTRN02C.cbl:440}, the account update at {@code app/cbl/CBTRN02C.cbl:441} and the
 * transaction write at {@code app/cbl/CBTRN02C.cbl:442}. <b>No commit verb stands between them, nor
 * between the last of them and the paragraph exit at {@code app/cbl/CBTRN02C.cbl:444}</b> -- the
 * program contains no such verb at any line. The paragraph is therefore observable with all three
 * writes applied or with none, and the migration plan's section 0.4.1.3 requires that to remain one
 * ACID commit. A single transaction spanning the three tables is what preserves it, and a single
 * transaction is only reachable if this module writes {@code ledger} itself.</p>
 *
 * <p>Alternatives Considered: calling {@code transaction-service} over HTTP for the write this
 * module does not own, which is the decomposition a reader is most likely to expect and the one a
 * microservice instinct reaches for first. Rejected because a remote call cannot enlist in the local
 * transaction. The far side commits independently of this side, so the three writes could no longer
 * commit or roll back together, and the partial posting the atomic commit exists to prevent would
 * arrive anyway -- reached by a network timeout instead of by a design choice.</p>
 *
 * <p>Alternatives Considered: a saga, with each of the three writes committed separately and
 * compensating reversals on failure. Rejected on parity rather than on preference. A saga replaces
 * one atomic commit with a sequence of committed steps, and every interval between them is a state
 * the reference cannot produce -- a posted transaction whose category balance has not moved, or a
 * moved account balance with no transaction row. Parity here is judged by comparing committed output
 * against a golden master, so such an interval is flagged as a parity failure and is correctly
 * flagged.</p>
 *
 * <p>Alternatives Considered: a transactional outbox with compensating reversal, which reaches the
 * same decomposition through a different trigger. Rejected for the same reason, and the different
 * trigger is worth addressing because it looks like an answer: an outbox makes the follow-on writes
 * reliable, not simultaneous. Reliability is not the property under test. The property under test is
 * that no intermediate combination is ever observable, and an outbox guarantees the opposite -- that
 * the intermediate state exists and is resolved afterwards.</p>
 *
 * <p>Trade-offs: the accepted cost is that this interface maps a table it does not own, so
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} is normative
 * and this file declares nothing about the table's shape -- no DDL, no index and no constraint. Two
 * indexes that migration owns matter to the reads below and are named here so that no reader looks
 * for them in this file: {@code idx_transactions_card_num} at its line 289, over the card number,
 * and {@code idx_transactions_proc_ts} at its line 311, the non-unique index over the processing
 * stamp. Both are consumed by the query planner when a predicate suits them and neither is declared
 * here. A query in this file naming something that migration does not declare is this file's defect
 * and never that migration's.</p>
 *
 * <h2>The two producers that converge on this table</h2>
 *
 * <p>Assumptions: two independent reference programs write this one master, so the write path must
 * stay neutral about which job is calling it. Posting writes it at
 * {@code app/cbl/CBTRN02C.cbl:562-564}, whose {@code 2900-WRITE-TRANSACTION-FILE} paragraph opens at
 * line 562 and whose write stands at line 564. Interest accrual writes it at
 * {@code app/cbl/CBACT04C.cbl:500}, from the paragraph the interest computation at
 * {@code app/cbl/CBACT04C.cbl:462-465} hands off to. Both emit the same 350-byte layout declared at
 * {@code app/cpy/CVTRA05Y.cpy}, which is why one entity and one interface serve both and why neither
 * job needs a write member of its own.</p>
 *
 * <p>Assumptions: the write itself is the inherited {@code save} and is deliberately not redeclared.
 * Redeclaring an inherited member changes nothing about its behaviour and would put a second
 * documented contract beside the framework's own, so the two could disagree after a later edit. What
 * the two producers need beyond {@code save} is nothing: neither reads a row back, and neither
 * probes for one before writing.</p>
 *
 * <h2>The combine walk orders by transaction identifier, ascending</h2>
 *
 * <p>Assumptions: that ordering is transcribed and not chosen, which is what makes the walk
 * auditable. The reference combine step is a sort utility invocation rather than a program --
 * {@code app/jcl/COMBTRAN.jcl:22} reads {@code EXEC PGM=SORT} -- and it declares its ordering
 * explicitly. The symbol-names definition opens at {@code app/jcl/COMBTRAN.jcl:27} and the symbol
 * itself at {@code app/jcl/COMBTRAN.jcl:28} reads {@code TRAN-ID,1,16,CH}: a sixteen-byte
 * <em>character</em> field at one-based position one, so the identifier occupies the front of the
 * record and is compared as characters. The direction is requested at
 * {@code app/jcl/COMBTRAN.jcl:30} as {@code SORT FIELDS=(TRAN-ID,A)}, where the trailing {@code A}
 * is ascending. The step's inputs are the previous backup generation concatenated with the
 * system-transaction generation at {@code app/jcl/COMBTRAN.jcl:23-26}, and its output is reloaded
 * into the master by the {@code IDCAMS REPRO} step at {@code app/jcl/COMBTRAN.jcl:41-48}. Ascending
 * order on the sixteen-byte identifier is therefore an observable contract of the combined output
 * rather than a preference of this interface.</p>
 *
 * <p>Assumptions: because that comparison is declared as character and not as numeric, the walk
 * below orders over the identifier's character property and must never order over a numeric
 * conversion of it. The two orders differ for identifiers that are not uniformly zero-padded, and
 * the difference is visible in the combined output. The property this interface names is therefore
 * {@code transactionId}, which {@code Transaction} declares as a {@code String} over a
 * {@code CHAR(16)} column, and the character ordering follows from the mapping rather than from a
 * conversion applied at the query.</p>
 *
 * <p>Alternatives Considered: retaining an external sort utility as a staging step, so that the
 * ordering happened outside the database exactly as it does in the reference. Rejected because the
 * migration plan's transformation rule T6 maps a sort utility's {@code SORT} clause onto an
 * {@code ORDER BY} and its {@code INCLUDE} clause onto a {@code WHERE}, so an ordered query is the
 * specified target form. Ordering in the database also removes the intermediate dataset the sort
 * step wrote without changing the byte order of what the reload consumes, which is the only property
 * the comparison examines.</p>
 *
 * <p>Assumptions: the two sort-utility clause forms are distinct and only one of them is in play
 * here, which is worth stating because they share a keyword with the step-gating form and are
 * routinely conflated. A {@code SORT FIELDS=} clause is an <em>ordering</em> directive and becomes an
 * {@code ORDER BY}; an {@code INCLUDE COND=} clause inside a sort step is a <em>record-selection</em>
 * predicate and becomes a {@code WHERE}. This interface implements only the ordering form, and the
 * combine step is a pure ordering step: {@code app/jcl/COMBTRAN.jcl} carries no
 * {@code INCLUDE COND=} clause at all, so there is no selection predicate to migrate alongside it.</p>
 *
 * <h2>Rulings this interface inherits from the package charter</h2>
 *
 * <p>Alternatives Considered: native SQL, which is a natural reach in a batch module because a
 * nightly pass is set-shaped and reads more directly as one statement. Rejected on the timing of the
 * failure it admits. This module runs the persistence provider with schema handling set to
 * {@code validate}, and that pass compares mapping metadata against the deployed table; it never
 * parses the text of a native query. A property path resolving to a column the schema lacks is
 * therefore reported at start-up, before a row is read, whereas a mistyped physical column inside a
 * native statement stays invisible until that statement executes -- which for this module means
 * part-way through a nightly chain with earlier steps already committed. The hazard is sharper here
 * than on a table this module owns, because the batch-local mapping and {@code V1__ledger.sql} were
 * authored separately and do not agree on every physical spelling, so a remembered column name is
 * not evidence of anything. Both members below are derived methods bound to property names declared
 * on {@code Transaction}, and no physical column name appears anywhere in this file.</p>
 *
 * <p>Assumptions: the offset-pagination vocabulary the charter prohibits package-wide appears on no
 * method, parameter or return type here, and the row cap on the second member is
 * {@code org.springframework.data.domain.Limit} for that reason. Positioning by counting rows from
 * the start of an ordered set means an insert landing before the cursor changes how many rows precede
 * it, so a scan positioned that way skips rows it never read and repeats rows it already read. A key
 * already returned keeps its place in the ordering whatever is inserted around it, which is why the
 * cursor below is a key.</p>
 *
 * <p>Assumptions: {@code com.carddemo.common.web.PageResponse} is neither returned nor imported. It
 * is the keyset envelope for HTTP, and it exists because a stateless request handler cannot remember
 * where a caller had reached. This module has no client and no REST surface beyond the actuator
 * health probe, so a continuation key here never leaves the process that read it.</p>
 *
 * <p>Assumptions: no member here declares binary floating point, and the amount on this table is the
 * reason the rule is worth restating on the interface that writes it. {@code Transaction} maps
 * {@code amount} as a {@code BigDecimal} over {@code NUMERIC(11,2)}; the migration plan's
 * transformation rule T3 forbids {@code float}, {@code double} and their boxed forms anywhere in the
 * money path, and the prohibition is an executable ArchUnit assertion rather than a review note. A
 * repository performs no arithmetic, so honouring it here means introducing no numeric parameter
 * type other than the entity's own -- which the two members below satisfy by taking only the
 * identifier and a row cap.</p>
 *
 * <h2>Three members a reader will look for and not find</h2>
 *
 * <p>Alternatives Considered: an existence check on the identifier, so that a job could guard against
 * a duplicate key before writing. Declined because neither producer behaves that way and no caller
 * asks for it. Both writes inspect the store's own answer instead of probing first:
 * {@code app/cbl/CBTRN02C.cbl:564} writes and then tests the resulting file status at
 * {@code app/cbl/CBTRN02C.cbl:566}, and {@code app/cbl/CBACT04C.cbl:500} writes and then tests it at
 * {@code app/cbl/CBACT04C.cbl:501}. A check-then-write is also not atomic against a concurrent
 * writer, so it could not carry the guarantee its name suggests; the primary key
 * {@code pk_transactions} at {@code V1__ledger.sql:275} is what actually enforces uniqueness, and a
 * genuine violation surfaces from the database whether a probe ran or not. It is added when a caller
 * genuinely needs it, with its own recorded rationale, rather than pre-emptively now.</p>
 *
 * <p>Alternatives Considered: a finder bounded by processing date, which the non-unique processing-stamp
 * index makes cheap and which the backup step looks like it should want. Declined because the step does
 * not select at all: {@code app/jcl/TRANBKP.jcl:23} invokes a catalogued copy procedure whose input at
 * {@code app/jcl/TRANBKP.jcl:26-27} is the whole master cluster and whose output at
 * {@code app/jcl/TRANBKP.jcl:29-33} is one new backup generation, with no predicate anywhere in the
 * job. A date-bounded finder would therefore migrate a filter the reference does not apply, and
 * copying fewer rows than the reference copied is a parity failure rather than an optimisation. Were
 * such a finder ever added, it would take the business date as an argument and never read a clock:
 * {@code app/jcl/INTCALC.jcl:22} supplies the business date to the accrual program as
 * {@code PARM='2022071800'}, and a clock read is what makes a rerun produce different output from the
 * run it repeats.</p>
 *
 * <p>Alternatives Considered: a finder bounded by card number, which {@code idx_transactions_card_num}
 * would serve directly. Declined because the card-ordered read belongs to a different bounded context.
 * The statement and report programs are the ones that read this data in card order, and the migration
 * plan's section 0.5.1.9 assigns them to {@code reporting-service} rather than to this module. This
 * interface serves the two producers and the combine walk, and a member for a caller in another
 * service would widen its surface without a caller of its own.</p>
 *
 * @see Transaction
 */
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Opens a forward-only walk of the whole master in ascending transaction-identifier order, which
     * is the ordering the combine step rebuilds the master in.
     *
     * <p>This is the migrated form of a sort utility invocation rather than of a program. The
     * reference step at {@code app/jcl/COMBTRAN.jcl:22} orders its input by the sixteen-byte
     * character identifier declared at {@code app/jcl/COMBTRAN.jcl:28} in the ascending direction
     * requested at {@code app/jcl/COMBTRAN.jcl:30}, and then reloads the result into the master at
     * {@code app/jcl/COMBTRAN.jcl:41-48}. One ordered query expresses that ordering exactly, so the
     * bytes the reload consumes arrive in the same sequence.</p>
     *
     * @return a lazily-populated {@code Stream<Transaction>} delivering every row of the master in
     *     ascending transaction-identifier order, empty when the master holds no rows. The caller
     *     owns two obligations that a materialised return would not impose: it must CONSUME the
     *     stream inside the transaction it already holds, because the underlying database cursor
     *     stays valid only for that transaction's duration, and it must CLOSE the stream, which means
     *     a try-with-resources block at the call site rather than a bare assignment
     * @throws org.springframework.transaction.IllegalTransactionStateException if the caller holds no
     *     transaction when it calls this method, since the propagation declared below is
     *     {@code MANDATORY} and a cursor cannot outlive a transaction that was never started
     */
    // Trade-offs: a Stream return rather than a List. A list was evaluated and rejected because it
    //     materialises the whole master into one heap before the caller sees a single row, and this
    //     is the largest table the module touches -- it accumulates every posted transaction, where
    //     the feed it derives from holds only one day's. The compromise accepted is a heavier
    //     call-site contract: the caller must hold a transaction open for the whole walk and must
    //     close the stream. That cost is bounded and local, whereas the cost a list defers grows with
    //     every night the chain runs, so the heavier contract is the cheaper of the two.
    // Assumptions: the walk is unbounded on purpose, because the step it serves reads the whole
    //     master. app/jcl/COMBTRAN.jcl:23-26 supplies the sort with entire generations rather than a
    //     selection from them, so there is no chunk size in the reference to reproduce; a caller that
    //     wants bounded reads asks the continuation finder below for them instead. Laziness is what
    //     makes an unbounded return safe here: the row count never becomes a heap requirement.
    // Alternatives Considered: the default REQUIRED propagation, which is the shorter annotation and
    //     the one the words "read-only transaction" suggest. Rejected because it is actively
    //     misleading on a method that returns a cursor. REQUIRED would start a transaction when no
    //     caller had one, commit it as this method RETURNED, and hand back a stream whose cursor was
    //     already closed -- so the failure would surface at the first element, inside the caller's
    //     loop, naming neither this method nor the missing transaction. MANDATORY refuses the call
    //     outright and names the actual mistake at the actual call site.
    // Trade-offs: readOnly is declared and is nonetheless inert, and saying so is better than leaving
    //     a reader to discover it. Because propagation is MANDATORY this method always joins a
    //     caller's transaction, and a joined definition's read-only flag does not override the
    //     transaction already in progress. It is retained because it states the intent of the method
    //     at the method, and because it is the flag that would govern if the propagation were ever
    //     relaxed. It is also the one member of this interface where the distinction matters, since
    //     the interface as a whole does carry a write surface.
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    // Assumptions: the fetch-size hint is what makes this stream actually stream. The driver opens a
    //     server-side cursor only when a positive fetch size and a non-auto-commit connection both
    //     hold; with either missing it buffers the whole result client-side, which on this table is
    //     heap exhaustion rather than a slowdown. Only POSITIVITY carries that property, so the
    //     streaming contract of this method rests on the value being above zero and not on which value
    //     it is.
    // Refactoring Rationale: this note used to say the value matches the module-wide
    //     hibernate.jdbc.fetch_size "so the two cannot disagree". They can. The base declares 100 at
    //     application.yml:764, which is where the numeric agreement comes from, but
    //     application-dev.yml:215 narrows the session default to 25 and application-prod.yml:426 widens
    //     it to 250. A query hint is a compile-time constant and cannot track either, so the sentence
    //     asserted an invariant nothing enforces. The same correction is made at the two sibling
    //     interfaces that carried the identical wording, so all three now describe one mechanism.
    // Trade-offs: this hint is therefore an intentional per-query OVERRIDE. It governs the statement it
    //     annotates, so this walk reads 100 rows per round trip under every profile regardless of the
    //     session default. What is given up is per-environment tuning of this walk; what is bought is a
    //     window fixed at the method, which no external property can set to zero and thereby convert
    //     into a full client-side buffer. The constant is aligned with the base value so the default
    //     deployment behaves identically whichever governs.
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "100"))
    Stream<Transaction> findAllByOrderByTransactionIdAsc();

    /**
     * Continues the ordered walk after a given transaction identifier, which is how a restarted step
     * resumes the master rather than reading it from the beginning again.
     *
     * <p>This exists for resumption and not for reading in convenient chunks. A redriven
     * state-machine execution restarts a step that may already have processed part of its input, and
     * this finder is what turns the identifier it resumes from back into the remainder of the
     * walk.</p>
     *
     * @param lastTransactionId the transaction identifier of the last row the caller already
     *     processed, of type {@code String}, treated as an EXCLUSIVE lower bound so that the row
     *     carrying it is not returned again; must not be {@code null}, and a caller starting from the
     *     beginning uses the unbounded walk above rather than passing a sentinel here
     * @param limit the greatest number of rows to return, of type {@code Limit}, which the caller
     *     sets from its own chunk size; must not be {@code null}
     * @return the matching rows as a {@code List<Transaction>} in ascending transaction-identifier
     *     order, holding at most the requested number of rows and never {@code null}. An EMPTY list
     *     means no rows remain after the given identifier, which is the ordinary way this walk ends
     *     rather than an error, since an ordered scan discovers the end by reaching it
     */
    // Assumptions: the predicate is STRICTLY greater rather than greater-or-equal, and the direction
    //     is a correctness decision rather than a stylistic one. The caller resumes from the
    //     identifier of a row it has already processed, so an inclusive bound would hand that row
    //     back and the step would emit it twice into the combined output -- a duplicated record in a
    //     stream the golden master compares byte for byte, not a harmless repeat. A gap in the
    //     identifier sequence cannot cause the opposite failure, because this is a range test and not
    //     an arithmetic step to a computed next value.
    // Assumptions: the identifier is a single-valued cursor, which is what makes a strict comparison
    //     a safe boundary in the first place. app/jcl/TRANFILE.jcl:53 defines the master cluster as
    //     KEYS(16 0) -- a sixteen-byte key at zero-based offset zero, which app/cpy/CVTRA05Y.cpy:5
    //     declares as the whole of TRAN-ID -- and the owning migration keys the table on that column
    //     alone as pk_transactions at V1__ledger.sql:275. The ordering is therefore total, so exactly
    //     one row carries any given boundary value and no tied group can straddle the boundary. That
    //     is the property the sibling feed lacks, which is why its cursor is a generated ordinal and
    //     this one is the business identifier.
    // Assumptions: the identifier the caller passes comes from the framework's own step execution
    //     context, persisted with the step's chunk commit and restored into the same step on a
    //     restart. It does NOT come from batch.batch_run: that table records whether a step of a run
    //     already reached a terminal state, which is the idempotency question a redrive asks before
    //     doing anything, and it holds no cursor column that could answer how far into its input the
    //     step had got. The two mechanisms answer different questions and this finder serves the
    //     second.
    // Alternatives Considered: offset pagination, in either shape the framework offers for it.
    //     Rejected because it does not preserve the behaviour it would replace: positioning by
    //     counting rows means a concurrent insert ahead of the cursor pushes an unread row past the
    //     boundary and a concurrent delete pulls an already-read row back inside it. A keyed browse
    //     has no such behaviour, and this module's correctness is judged by comparing committed
    //     output against a golden master, so a read discipline that can skip or repeat a row is not a
    //     near-equivalent of the reference -- it is a different program.
    // Trade-offs: a keyset query cannot report a total row count without a second query, so no count
    //     is offered and no caller can derive a progress percentage from this interface. Nothing
    //     observable is given up, because the reference reported no total either: app/jcl/COMBTRAN.jcl
    //     hands the sort whole generations and never asks how many records they hold.
    // Alternatives Considered: a Stream return here too, for symmetry with the walk above. Rejected
    //     because the two methods have genuinely different lifetimes. The walk is consumed once
    //     inside one transaction, whereas a bounded chunk is read, its transaction committed, and its
    //     last identifier recorded before the next chunk is asked for -- so a lazy return would be
    //     forced shut at exactly the commit that makes the chunk durable. A list is what a bounded
    //     read returns.
    List<Transaction> findByTransactionIdGreaterThanOrderByTransactionIdAsc(String lastTransactionId,
            Limit limit);
}
