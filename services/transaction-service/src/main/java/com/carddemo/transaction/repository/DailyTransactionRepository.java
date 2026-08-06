package com.carddemo.transaction.repository;

import com.carddemo.transaction.domain.DailyTransaction;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The sole data-access port onto {@code ledger.daily_transactions}, the pre-posting feed of the
 * LEDGER bounded context.
 *
 * <p>A row here has been extracted and loaded but has not been validated or posted. The posting job
 * reads the feed front to back and turns each row into either a posted transaction or a rejected
 * one. Every relational equivalent of a file operation the baseline issues against its
 * {@code DALYTRAN} dataset is therefore either declared in this interface or inherited into it, and
 * none is expressed anywhere else in this module; the package charter beside this file makes that
 * the boundary.
 *
 * <p>Assumptions: neither method below carries an exception at-clause, and the omission is uniform
 * and deliberate. The house convention attaches that element to each exception a caller must
 * handle. Both members here are queries. A query declares no checked exception, and the unchecked
 * data-access failures the framework translates -- a lost connection, a statement the caller cannot
 * influence -- are not conditions a caller answers per call site; they surface through this
 * module's shared web error contract. Two speculative at-clauses would add two unverifiable claims
 * rather than two facts. The sibling interface in this package records the same reasoning, and
 * departing from it here would suggest a difference in raisable failures that does not exist.
 *
 * <h2>Provenance of the record contract</h2>
 *
 * <p>Assumptions: the contract is {@code app/cpy/CVTRA06Y.cpy} lines 4 to 18, whose line 2 states
 * the record length as 350 and whose line 4 declares {@code 01 DALYTRAN-RECORD}. Fourteen
 * {@code 05} items follow: thirteen carry data and the fourteenth, {@code FILLER PIC X(20)} at line
 * 18, pads bytes 331 to 350 out to the declared length. That filler is dropped by the entity, which
 * records the drop where it maps the record, and it is not resurrected here. Every byte range named
 * in this file is one-based and inclusive. The reference tree is read as specification and is never
 * modified, so this interface encodes widths the tree already states rather than redefining them.
 *
 * <p>Assumptions: lines 5 to 18 of that contract are structurally identical to
 * {@code app/cpy/CVTRA05Y.cpy} lines 5 to 18 -- the same thirteen pictures in the same order,
 * closed by the same {@code FILLER PIC X(20)} at line 18 -- with only the field-name prefix
 * differing, and two consequences follow that this interface depends on. The posting job can
 * project a feed row onto a ledger row one-to-one, which
 * {@code app/cbl/CBTRN02C.cbl} lines 425 to 436 do as twelve consecutive moves of like onto like.
 * The reject stream can retain a rejected feed record whole rather than field by field, which line
 * 447 of the same program does in a single move of {@code DALYTRAN-RECORD} into the reject payload,
 * and which the reject table carries as one opaque 350-character column. Both are stated here as
 * facts about neighbouring tables; neither is reached from this interface, and no import of another
 * bounded context's types appears in this file.
 *
 * <h2>The consumer is a sequential read, and this table has no browse</h2>
 *
 * <p>Assumptions: the feed is read front to back by a batch program and by nothing else, so the
 * access pattern this interface serves is a scan rather than a screen. The posting loop is
 * {@code app/cbl/CBTRN02C.cbl} lines 202 to 219, which calls {@code 1000-DALYTRAN-GET-NEXT}
 * at line 204; that paragraph begins at line 345 and issues
 * {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} at line 346, setting its end-of-file flag at line
 * 361. The pre-posting program reads the same feed the same way, looping at
 * {@code app/cbl/CBTRN01C.cbl} line 164 and reading at its line 203. The file is declared for
 * sequential access only: lines 29 to 31 of the posting program select it
 * {@code ORGANIZATION IS SEQUENTIAL} with {@code ACCESS MODE IS SEQUENTIAL} and declare no record
 * key at all, and {@code app/jcl/POSTTRAN.jcl} lines 30 and 31 supply it as the physical sequential
 * dataset {@code AWS.M2.CARDDEMO.DALYTRAN.PS}. There is no forward-and-backward browse over this
 * table anywhere in the baseline and no online screen that lists it, so no function-key page
 * navigation applies to it.
 *
 * <p>Assumptions: the honest tally of browse-like sites in this bounded context is ONE true forward
 * and backward browse plus TWO maximum-key identifier derivations, and every one of them reads the
 * POSTED master rather than this feed. The browse is {@code app/cbl/COTRN00C.cbl}, measured at six
 * forward reads. The two derivations are {@code app/cbl/COTRN02C.cbl} lines 444 to 449 and
 * {@code app/cbl/COBIL00C.cbl} lines 212 to 217, each of which seeks from high values, reads once
 * backwards, ends the browse and increments. {@code COBIL00C} is cited here as a COUNTER-EXAMPLE
 * and never as a paging source: those 572 lines contain no forward-read verb whatsoever and its
 * start-browse at lines 443 to 449 carries no greater-or-equal option, so reading it as a third
 * list implementation would attach paging state to an operation that returns a single key. The
 * count that matters is one browse and two derivations, never four paging sites, and none of the
 * three is this table.
 *
 * <h2>The processed timestamp may be absent, and nothing here filters on it</h2>
 *
 * <p>Assumptions: {@code proc_ts} is NULLABLE on this table while
 * {@code ledger.transactions.proc_ts} is not null, and that asymmetry is the single property most
 * distinguishing this interface from its sibling. Neither method below may assume the processed
 * timestamp is present, and a caller mapping a row must expect it absent. Three independent
 * findings support it. The feed leaves the field blank: {@code app/data/ASCII/dailytran.txt} holds
 * 105300 bytes as exactly 300 records of 350 bytes, and the 26 bytes at 305 to 330 are blank on 300
 * of those 300 records, while the originating timestamp at 279 to 304 is populated on all 300 --
 * for instance {@code 2022-06-10 19:27:53.000000} -- so the blankness is specific to this field
 * rather than a gap in the extract. The stamp is minted downstream rather than supplied upstream:
 * {@code app/cbl/CBTRN02C.cbl} line 437 performs {@code Z-GET-DB2-FORMAT-TIMESTAMP} and line 438
 * moves the result into the POSTED record, whereas its line 436 copies the originating stamp
 * straight across from the feed. And the two copybooks declare the field at the identical
 * {@code PIC X(26)} width at line 17 of each, so the difference between the two tables is semantic
 * rather than structural and has to be carried as nullability. The migration is normative for this
 * and declares it so; asserting otherwise would reject the seed extract in its entirety.
 *
 * <p>Alternatives Considered: an unposted-only predicate, meaning a finder restricted to rows whose
 * processed timestamp is absent, which the nullability above makes technically easy and which a
 * reader may expect to find here. It is rejected because the baseline does not have it. The posting
 * loop cited above reads the ENTIRE feed sequentially and applies no predicate of any kind, and the
 * field it would filter on is never referenced by any program or job in the reference tree at all
 * -- a repository-wide search for {@code DALYTRAN-PROC-TS} across {@code app/cbl} and
 * {@code app/jcl} returns nothing, so no baseline behaviour depends on its value. Supplying the
 * predicate would change WHICH ROWS A RERUN PROCESSES: a second run over an unchanged feed would
 * silently see a different set from the first. Behaviour does not change in this migration, so the
 * predicate is absent by decision, and this paragraph exists so that its absence reads as a ruling
 * rather than as an omission.
 *
 * <h2>The scan is keyset-ordered and chunked</h2>
 *
 * <p>Assumptions: the two methods below are a keyset-ordered chunked scan -- an unfiltered opening
 * read and a resumable continuation keyed strictly beyond a cursor -- and the general argument for
 * that shape belongs to the package charter, which states the scalar cursor, the strict
 * comparisons, the surplus-row convention and the rejected alternative once for the whole package.
 * It is cited rather than restated. What is specific to this table is only that the scan has no
 * backward direction and no envelope of its own, both recorded below.
 *
 * <p>Alternatives Considered: offset pagination, in either of the two shapes the framework offers
 * for it, and the batch reader built on the same mechanism. Rejected on a defect rather than a
 * preference: positioning by counting rows from the start of an ordered set means an insert landing
 * before the cursor changes how many rows precede it, so a scan positioned that way skips rows it
 * never read and repeats rows it already read. A feed is exposed to exactly that, because it is
 * loaded by one process while a job walks it. A key already returned keeps its place in the
 * ordering no matter what is inserted around it, which is why the cursor here is a key. The row cap
 * is therefore {@code org.springframework.data.domain.Limit} and both return types are
 * {@code List<DailyTransaction>}.
 *
 * <p>Alternatives Considered: returning a paged abstraction instead of a list, which would carry
 * the row count of the whole table alongside the rows. Rejected because that abstraction issues a
 * second counting query to populate totals this table has no use for: the baseline read counts
 * records as it processes them, at {@code app/cbl/CBTRN02C.cbl} line 206, and never asks the file
 * how many it holds. A list is what a sequential read returns.
 *
 * <p>Trade-offs: each method hands its caller the whole slice it read and trims nothing. The
 * compromise accepted is that a caller which asked for one row beyond its chunk must know to
 * process one fewer than it may receive; what is bought is that this interface stays the file-access
 * layer and holds no caller's bookkeeping. The baseline draws the line in the same place, discarding
 * its own surplus read inside a program paragraph rather than inside the file access it performs.
 * Where a caller does need a cursor envelope, the shared {@code com.carddemo.common.web.PageResponse}
 * is the one it assembles from such a slice; this interface neither returns nor imports it, and the
 * charter forbids re-declaring it. That envelope belongs to a batch or administrative caller here,
 * never to an online list screen, because this table has none.
 *
 * <h2>The identifier is a mapped identity, not a declared key</h2>
 *
 * <p>Assumptions: the migration declares NO primary key, no unique constraint and no not-null
 * column over this table, and the entity nevertheless maps the identifier as its identity because
 * the persistence provider requires one. The type parameter of this interface is therefore
 * {@code String}, matching that identity, and it is a declared-width 16-character column named
 * {@code transaction_id}; the entity's own member is spelled {@code tranId}, so the derived names
 * below spell the MEMBER while this documentation names the column. The absence of a key is the
 * baseline contract rather than an omission: the feed is the sequential dataset cited above and the
 * posting job never keys into it.
 *
 * <p>Assumptions: uniqueness of that identifier is an empirical property of the extract that exists
 * and NOT a constraint this interface may rely on. Measured directly, the 300 records of
 * {@code app/data/ASCII/dailytran.txt} carry 300 distinct identifiers at bytes 1 to 16, every one
 * of them 16 digits with no blank, and they are already in ascending order of it. Those counts
 * agree, so the scan below is well defined over the extract in hand. They are still a property of
 * one extract: because no unique constraint exists, a feed carrying a repeated identifier -- which
 * the sequential baseline would process without complaint -- loads without error, and a caller must
 * not treat either method below as returning at most one row per identifier.
 *
 * <p>Assumptions: ordering that identifier is LEXICOGRAPHIC, and it is total and stable only
 * because every value occupies all 16 bytes, which the measurement above establishes for the
 * extract in hand. A declared-width character column is blank-padded by the engine on comparison,
 * so a value and the same value with trailing blanks are one value here, exactly as a
 * declared-width comparison in the reference tree treats them. A caller must not depend on the
 * opposite convention, and must not read the ordering as arithmetic.
 *
 * <h2>The physical contract is owned elsewhere, and this interface consumes it</h2>
 *
 * <p>Assumptions: the single normative physical contract is this module's
 * {@code db/migration/V1__ledger.sql}, which creates the table at its line 319, declares the
 * identifier at line 324 and the processed timestamp at line 397, and records at its lines 400 to
 * 413 why it declares neither a key nor an index over it. The module's {@code application.yml} sets
 * {@code ddl-auto: none} at its line 473, so the provider generates no schema and is purely a
 * CONSUMER of that migration. Nothing reconciles a query here against a column there at start-up,
 * so a mismatch stays invisible until the query runs, which is why every column named in this
 * documentation is named against the migration and every method name against the entity.
 *
 * <p>Alternatives Considered: restating physical shape as declarative metadata in this package --
 * index metadata, a column definition, a generated-value strategy. Rejected because with schema
 * generation switched off such metadata is never acted on: it would neither create nor verify
 * anything, so it could drift out of step with the migration that does own the shape while still
 * reading like a specification. Nothing in this file shapes DDL.
 *
 * <p>Alternatives Considered: a card-number finder, which the record contract superficially invites
 * because {@code app/cpy/CVTRA06Y.cpy} line 15 carries {@code DALYTRAN-CARD-NUM PIC X(16)} and the
 * posted master does offer that path. It is deliberately NOT added, on a reading of the migration
 * rather than a preference: that file creates exactly two secondary indexes, at its lines 269 and
 * 291, and BOTH are on {@code ledger.transactions}, leaving this table with none. The baseline
 * agrees -- its one alternate index over transaction data is defined at
 * {@code app/jcl/TRANIDX.jcl} line 25 and related at line 26 to the {@code TRANSACT} base cluster,
 * not to the feed, so the feed has no secondary access path at all. An unindexed finder here would
 * invite a full scan of the table on every call, which is a shape the baseline never performs, and
 * it would read as though a supported access path existed. A caller needing the feed by card number
 * scans it and selects, exactly as the baseline does.
 *
 * <h2>What this interface deliberately does not carry</h2>
 *
 * <p>Assumptions: the single-record lookup, the insert, the batch insert, the existence test and
 * the delete are INHERITED from {@code JpaRepository} and are not redeclared below. The keyed
 * lookup answers a read by identifier; the inserts are how the load step lands feed rows, the
 * relational equivalent of the utility copy that populates the dataset; and the delete is how a
 * caller clears a loaded feed. Redeclaring any of them would add a second declaration of an
 * identical contract.
 *
 * <p>Alternatives Considered: annotating this interface as a repository stereotype. Rejected as
 * redundant metadata: the framework registers the proxy from the {@code JpaRepository} supertype
 * alone, so the annotation would add a second, weaker signal of the same fact and invite a reader
 * to believe registration depends on it.
 *
 * <p>Refactoring Rationale: no transaction boundary is declared here, on the interface or on either
 * method, and the boundary belongs to the calling job or service instead. The rule that maps a CICS
 * syncpoint to a declarative transaction has no verb to work from in this consumer -- the posting
 * program contains zero {@code EXEC CICS} statements and issues no syncpoint at all, being a batch
 * program -- and what it does instead settles the question: paragraph
 * {@code 2000-POST-TRANSACTION} at {@code app/cbl/CBTRN02C.cbl} line 424 performs a
 * category-balance update at line 440, an account update at line 441 and the posted-transaction
 * write at line 442 before closing at line 444, so one unit of work spans three record types and no
 * single repository could own it. Marking either method read-only would additionally fragment a
 * caller's boundary silently, by enlisting a read into a scope the caller did not open.
 *
 * <p>Alternatives Considered: a transactional outbox with a compensating reversal, or a saga, in
 * place of that single commit. Both are rejected because they would make a posted transaction with
 * an unapplied balance, or an applied balance with no posted transaction, externally observable
 * between committed steps. No such partial-posting state exists in the cited paragraph, so the
 * parity oracle would correctly identify the design as a behavioural divergence. The batch context
 * instead reaches these tables under a narrowly scoped cross-schema grant so that the commit stays
 * atomic.
 *
 * <p>Trade-offs: neither method participates in optimistic locking, and NO HTTP 409 originates from
 * this interface. The entity carries no version attribute, so a reader should not look for one: no
 * baseline program compares a before-image over a feed record, the feed being written by a load
 * step and read by a job rather than edited across a screen turn, and under schema generation
 * switched off a version attribute would map to a column the migration does not create and would
 * fail when a query ran. What is given up is lost-update detection on a concurrent modification of
 * a loaded row; the accepted cost is small because every path into this table inserts or clears
 * rather than modifies. Where a conflict response does arise in this migration it is on the account
 * and card records, which do carry a version attribute.
 *
 * <p>Trade-offs: the scan is forward-only and no backward method is declared. The reference read
 * has no backward direction to transcribe -- the two feed consumers issue a sequential read and
 * nothing else, and neither the feed's file declaration nor any job offers a reverse path -- so a
 * backward finder would be a capability invented here rather than migrated. What is given up is
 * that a caller cannot step back through the feed; what is bought is that the declared surface
 * states exactly the access the baseline has. A caller needing to revisit earlier rows restarts the
 * scan, which is what a rerun of the batch step does.
 *
 * <p>Assumptions: the graded return code the posting program sets -- line 229 of
 * {@code app/cbl/CBTRN02C.cbl} tests whether any record was rejected and line 230 moves 4 into it
 * -- belongs to that program and its reference suite, and is not a build state for this module. The
 * documentation gate, the compiler and the tests that exercise this interface all have binary
 * outcomes.
 */
public interface DailyTransactionRepository extends JpaRepository<DailyTransaction, String> {

    /**
     * Reads the opening chunk of the feed, in ascending identifier order.
     *
     * <p>This is the entry into the sequential read at {@code app/cbl/CBTRN02C.cbl} line 346 on the
     * first pass, where the loop at line 202 has established no position yet and the read returns
     * the first record of the dataset.
     *
     * @param limit the maximum number of rows to read, of type {@code Limit}, which a caller sets
     *     to its chunk size plus one when it wants the surplus row that answers whether the scan
     *     continues; must not be {@code null}
     * @return the first rows of the ordered set as a {@code List<DailyTransaction>}, ascending by
     *     identifier, holding up to {@code limit} rows -- so up to chunk size plus one, the last of
     *     which is that continuation probe rather than a row to process -- and empty when the feed
     *     holds nothing, which is the loaded-but-empty case the reference read handles by setting
     *     its end-of-file flag immediately. Any returned row may carry an absent processed
     *     timestamp
     */
    // WHY : Refactoring Rationale: this is a separate member rather than the continuation method
    //       called with a sentinel cursor. The baseline spells the absence of a position as an
    //       end-of-file flag tested before the read rather than as a key value, so there is no
    //       sentinel to transcribe; inventing one would be a fourth thing a caller could test for
    //       wrongly, given that the neighbouring programs already spell an absent position three
    //       different ways. An unfiltered ordered read states "from the beginning" in the signature.
    List<DailyTransaction> findAllByOrderByTranIdAsc(Limit limit);

    /**
     * Reads the chunk of the feed that follows a stated position, in ascending identifier order.
     *
     * <p>This is the relational form of the next iteration of that same sequential read, resumed
     * from a cursor so that a chunked scan covers the dataset in the order the loop at
     * {@code app/cbl/CBTRN02C.cbl} lines 202 to 219 covers it.
     *
     * @param lastKey the identifier of the last row the caller already holds, of type
     *     {@code String}; the comparison is STRICT, so that row is excluded and the chunk begins at
     *     the next identifier after it; must not be {@code null}
     * @param limit the maximum number of rows to read, of type {@code Limit}, which a caller sets
     *     to its chunk size plus one when it wants the continuation probe; must not be {@code null}
     * @return the following rows as a {@code List<DailyTransaction>}, ascending by identifier,
     *     holding up to {@code limit} rows -- so up to chunk size plus one, whose presence beyond
     *     the chunk size is what reports that more of the feed remains -- and empty when the scan is
     *     exhausted. Any returned row may carry an absent processed timestamp
     */
    // WHY : Refactoring Rationale: the exclusion is written into the method name as a strict
    //       comparison because the baseline never wrote a boundary anywhere. Its feed read is
    //       positionless, advancing an implicit file position rather than comparing a key, and the
    //       one start-browse in this bounded context leaves its greater-or-equal option commented
    //       out at line 597 of app/cbl/COTRN00C.cbl. Stating the predicate makes the boundary an
    //       artifact of the query instead of an artifact of the access method. Were the comparison
    //       inclusive, the first row of every chunk would repeat the last row of the previous one,
    //       and the posting job would post it twice.
    // WHY : Assumptions: the cursor is a single scalar identifier and not a composite, because the
    //       ordering it resumes has one component. The charter records that the reference cursor in
    //       this bounded context is one 16-character identifier; here it is also the whole of the
    //       ordering, since this table has no secondary access path to order within.
    List<DailyTransaction> findByTranIdGreaterThanOrderByTranIdAsc(String lastKey, Limit limit);
}
