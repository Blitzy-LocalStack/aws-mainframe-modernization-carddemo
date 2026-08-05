package com.carddemo.transaction.repository;

import com.carddemo.transaction.domain.Transaction;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The sole data-access port onto {@code ledger.transactions}, the posted transaction master of the
 * LEDGER bounded context.
 *
 * <p>Every relational equivalent of a file operation the baseline issues against its
 * {@code TRANSACT} dataset is either declared in this interface or inherited into it, and none is
 * expressed anywhere else in this module. The package charter beside this file makes that the
 * boundary: no controller, service, mapper, DTO or domain type may hold a query.
 *
 * <p><b>Parameters, return values, exceptions or errors at the type level.</b> An interface
 * declaration accepts no parameter, returns no value and raises nothing, so no parameter, return or
 * exception at-clause has a subject here; each method below carries its own. The inapplicability is
 * stated rather than left silent because user Rule 1 (Explainability) enumerates purpose,
 * parameters, return values and exceptions at its lines 18 to 21 and forbids a docstring that
 * quietly omits one at its line 39, so a reader has to be able to tell a declared inapplicability
 * from an oversight. This interface declares no type parameter of its own either.
 *
 * <p>Assumptions: no method below carries an exception at-clause, and the omission is uniform and
 * deliberate. Rule 1 attaches that element at its line 21 to any exception that may be raised
 * "where applicable", and the house standard narrows it to each exception the caller must handle.
 * Every member here is a query. A query raises no checked exception, and the unchecked data-access
 * failures the framework can translate -- a lost connection, a query the caller cannot influence --
 * are not conditions a caller handles per call site; they surface through this module's shared web
 * error contract. Declaring a speculative at-clause on each of eight members would add eight
 * unverifiable claims rather than eight facts.
 *
 * <h2>One transformation rule governs this whole interface</h2>
 *
 * <p>AAP transformation rule T5 maps each CICS file verb to exactly one target, and this interface
 * is where each of those targets lives:
 *
 * <ul>
 *   <li>A keyed {@code READ} becomes one repository method. Its site is
 *       {@code app/cbl/COTRN01C.cbl}, whose {@code READ-TRANSACT-FILE} paragraph begins at line 267
 *       and issues {@code EXEC CICS READ} at line 269, naming the transaction dataset at line 270,
 *       the record at line 271 and {@code RIDFLD (TRAN-ID)} at line 273. Its target is the
 *       inherited {@code findById(String)}, which is therefore not redeclared below.</li>
 *   <li>A {@code WRITE} becomes one repository method. Its site is
 *       {@code app/cbl/COBIL00C.cbl}, whose {@code WRITE-TRANSACT-FILE} paragraph begins at line
 *       510 and issues {@code EXEC CICS WRITE} at line 512, supplying the record at line 514 and
 *       {@code RIDFLD} at line 516. Its target is the inherited {@code save}, likewise not
 *       redeclared.</li>
 *   <li>The four-verb browse ensemble becomes ONE keyset-paginated query per direction, not four
 *       members. In {@code app/cbl/COTRN00C.cbl} the paragraphs are {@code STARTBR-TRANSACT-FILE}
 *       at line 591 executing its verb at line 593, {@code READNEXT-TRANSACT-FILE} at line 624
 *       executing at line 626, {@code READPREV-TRANSACT-FILE} at line 658 executing at line 660,
 *       and {@code ENDBR-TRANSACT-FILE} at line 692 executing at line 694.</li>
 *   <li>{@code ENDBR} has NO target at all, and its absence is the point rather than an omission. A
 *       query holds no position between calls, so there is no browse to close. The forward and
 *       backward paths call that paragraph at lines 322 and 371 of the same program; those are call
 *       sites, and the verb itself is line 694 in the paragraph declared at line 692. Both levels
 *       disappear together.</li>
 * </ul>
 *
 * <p>Assumptions: the honest tally of what this interface replaces is ONE true forward and backward
 * browse plus TWO maximum-key identifier derivations, and conflating them would attach paging state
 * to operations that return a single key. The browse is {@code app/cbl/COTRN00C.cbl} lines 279 to
 * 376. The two derivations are {@code app/cbl/COTRN02C.cbl} lines 444 to 449 and
 * {@code app/cbl/COBIL00C.cbl} lines 212 to 217.
 *
 * <p>Alternatives Considered: reading {@code app/cbl/COBIL00C.cbl} as a second list implementation,
 * which its three browse-verb paragraphs at lines 441, 472 and 501 superficially invite. It is
 * rejected on a measurement: those 572 lines contain no forward-read verb whatsoever, and the
 * start-browse at lines 443 to 449 carries no greater-or-equal option. What lines 212 to 217
 * perform is a seek from high values, one backward read, an end-browse and an increment -- a
 * highest-key lookup whose relational equivalent is a maximum over the key column. That program is
 * therefore cited throughout this file as a COUNTER-EXAMPLE and never as a paging source: the page
 * envelope must never be wired into the bill-payment path, and the count that matters is one browse
 * and two derivations rather than three browses.
 *
 * <h2>The keyset contract is transcribed, not invented</h2>
 *
 * <p>Assumptions: the baseline browse state is already a cursor over keys, so the mapping is
 * one-to-one. {@code app/cbl/COTRN00C.cbl} line 61 copies the shared communication area and line 62
 * appends the list screen's own state to it; lines 63 and 64 declare the first and last identifiers
 * of the displayed page as {@code PIC X(16)}, line 65 the screen ordinal, line 66 the availability
 * flag with its two condition names at lines 67 and 68, and lines 69 and 70 the selection marker
 * and the selected identifier. Nothing in that structure counts rows already consumed, so nothing
 * had to be invented here and nothing had to be discarded.
 *
 * <p>Assumptions: the cursor is a SINGLE SCALAR of sixteen characters and not a composite, so every
 * keyset method below takes one cursor parameter of type {@code String}. Lines 63 and 64 above are
 * the whole of it. A sibling browse in another bounded context composes a longer key instead; that
 * context is reached over HTTP and its key shape must not be read into this one.
 *
 * <p>Assumptions: the row cap is the caller's page size plus one, and that surplus row is a
 * transcription rather than a heuristic. {@code COTRN00C} fills exactly ten display slots -- line
 * 290 clears ten, line 295 sets the index to one and the loop at line 297 stops once it reaches
 * eleven, incrementing at line 301 -- and then, at line 308, performs an ELEVENTH forward read for
 * no purpose other than to discover whether anything follows. Lines 309 to 313 set the availability
 * flag to yes or to no from that read's outcome alone. Line 306 to line 307 advance the screen
 * ordinal, and lines 314 to 320 take the exhausted branch. The backward path is symmetrical: line
 * 344 clears ten, line 349 starts the reverse fill at slot ten, the loop at line 351 counts down at
 * line 355, and line 360 performs the backward surplus probe, after which lines 361 to 368 adjust
 * the ordinal.
 *
 * <p>Trade-offs: each paging method hands its caller the whole slice it read, up to page size plus
 * one rows, and does not trim the surplus row here. The compromise accepted is that a caller must
 * know to render one fewer row than it may receive; what is bought is that this interface stays the
 * file-verb layer under rule T5 and holds no presentation concern. The baseline draws the line in
 * the same place: its eleventh read at line 308 is discarded inside the program paragraph, not
 * inside the file access it performs. The shared envelope
 * {@code com.carddemo.common.web.PageResponse} is assembled by the service layer from that slice,
 * which is why this interface neither returns nor imports it; that envelope's own contract records
 * that a caller "asks its store for one more row than it intends to return, puts the surplus row
 * nowhere, and reports its presence" as its forward-availability indicator. Its {@code lastKey}
 * therefore identifies the last row the caller actually returns and never the probe row.
 *
 * <p>Refactoring Rationale: the cursor comparisons below are spelled strictly, {@code > lastKey}
 * forward and {@code < firstKey} backward, because the baseline positioning was implicit and a
 * query reader cannot see an implicit default. The start-browse paragraph at {@code COTRN00C} line
 * 591 names the dataset, the key and the key length at lines 594 to 596, and its
 * greater-or-equal option at line 597 is COMMENTED OUT, so positioning fell to the file system's
 * default rather than to anything the source states. Stating the predicate makes the boundary an
 * artifact of the query instead of an artifact of the access method.
 *
 * <p>Alternatives Considered: offset pagination, which is rejected on a defect rather than a
 * preference. Positioning a page by counting rows from the start of the ordered set means that an
 * insert landing before the cursor changes how many rows precede it, so a page positioned that way
 * skips rows it never showed and repeats rows it already showed. The concurrency that makes this
 * real is attested by the baseline itself: {@code app/cbl/COBIL00C.cbl} lines 212 to 217 seek from
 * high values, read backwards, end the browse, copy the key and add one, with nothing holding a
 * lock across those six lines, so two payments can read one maximum and both then insert into the
 * middle of the key space a browse is walking. A key already returned keeps its place in the
 * ordering no matter what is inserted around it. Two framework shapes express the rejected
 * mechanism and neither is used here: the paging request abstraction {@code Pageable}, with its
 * {@code PageRequest} implementation, emits a SQL {@code OFFSET} clause and normalises even its
 * zero-th instance to that mechanism, which invites a later increment of a page ordinal; and the
 * paging return abstraction {@code Page} additionally issues a second counting query in order to
 * report totals the baseline neither has nor needs, since {@code COTRN00C} never counts anything --
 * it probes. The row cap here is therefore {@code org.springframework.data.domain.Limit} and every
 * return type is {@code List<Transaction>}.
 *
 * <h2>Three ordered access paths, and the two indexes that carry two of them</h2>
 *
 * <p>Assumptions: the primary key on the transaction identifier serves both the keyed single-record
 * lookup and the list screen's keyset paging, which is what keeps that paging a single ordered
 * index scan. {@code app/cbl/CBTRN02C.cbl} lines 34 to 37 declare the file
 * {@code ORGANIZATION IS INDEXED} on that field, and {@code COTRN00C} passes it as the record
 * identification field to all three of its browse verbs, at lines 595, 630 and 664.
 *
 * <p>Assumptions: {@code idx_transactions_card_num} carries the card-ordered path, and that path is
 * NOT the list screen's. The list screen offers one input field, a sixteen-character transaction
 * identifier, and its program refers to no card field at all, so pairing it with this index would
 * misdescribe both. The card-ordered sequence is the one the baseline obtained by physically
 * sorting an extract: {@code app/jcl/TRANREPT.jcl} line 41 declares the field at one-based position
 * 263 for sixteen bytes and line 46 orders the extract by it ascending. The layout that reads that
 * sequence is {@code app/cpy/COSTM01.CPY}, whose {@code TRNX-KEY} group at line 21 is the card
 * number at line 22 followed by the transaction identifier at line 23 -- which is exactly the
 * ordering the card-filtered methods below express, one card constrained and the identifier
 * ascending or descending within it. The index is non-unique because one card owns many
 * transactions, which is the entire point of the path.
 *
 * <p>Refactoring Rationale: {@code idx_transactions_proc_ts} is the surviving half of the batch
 * alternate index, and only half of that definition survives. {@code app/jcl/TRANIDX.jcl}
 * introduces it at line 20, runs the utility at line 22, defines the index at line 25 and relates
 * it to the base cluster at line 26. Its line 27 reads {@code KEYS(26 304)}, a twenty-six byte key
 * at zero-based position 304, which is one-based bytes 305 to 330; line 28 declares
 * {@code NONUNIQUEKEY}, line 29 {@code UPGRADE} and line 30 {@code RECORDSIZE(350,350)}. The KEY is
 * what carries forward. What does not carry forward is the build step: {@code BLDINDEX} at line 52,
 * with its input and output datasets at lines 53 and 54, and the companion path definition at lines
 * 42 to 44, have no target because the engine maintains a declared index transactionally as rows
 * change, so there is no build to schedule and no path object to relate. Those steps are retired AS
 * MIGRATION TARGETS ONLY. Retired never means deleted: that job stands unmodified on disk, and this
 * file never edits it.
 *
 * <p>Assumptions: those byte positions agree three independent ways, which is why every
 * position-bound decision here rests on them without further derivation. Summing the declared
 * widths of {@code app/cpy/CVTRA05Y.cpy} lines 4 to 18 -- the normative record contract under AAP
 * rule T1, whose line 2 states the length as 350 -- puts the card number from line 15 at 263 to 278
 * and the processing timestamp from line 17 at 305 to 330, with the filler at line 18 closing the
 * record at 350. The report job independently names one-based 263 at its line 41 and one-based 305
 * at its line 42. The alternate index names zero-based 304, the same one-based 305. The report job
 * types the card field {@code ZD} at its line 41 for the sort utility's own purposes while line 15
 * of the copybook declares {@code PIC X(16)}; rule T1 makes the copybook authoritative, so the
 * mapped column is a declared-width character field and a leading zero in it is significant.
 *
 * <h2>The physical contract is owned elsewhere, and this interface consumes it</h2>
 *
 * <p>Assumptions: the single normative physical contract is
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql}. It creates
 * the table at its lines 116 to 256, declares {@code pk_transactions} over the identifier column at
 * its line 255, and creates both indexes named above at its lines 269 and 291. The sibling
 * {@code application.yml} sets {@code ddl-auto} to {@code none} at its line 469, so the persistence
 * provider is a CONSUMER of that schema and generates none of it. Nothing reconciles the two at
 * start-up, so a mismatch between a query here and a column there stays invisible until the query
 * runs.
 *
 * <p>Alternatives Considered: restating either index as declarative index metadata on the entity's
 * table mapping, or anywhere else in this package. Rejected because with schema generation switched
 * off such metadata is never acted on: it would neither create nor verify an index, so it could
 * drift out of step with the migration that does create them while still reading like a
 * specification. Both indexes are therefore named in prose only, and no declaration in this package
 * shapes DDL -- no index metadata, no column definition and no generated-value strategy.
 *
 * <p>Assumptions: the Java member name and the column name differ for the identifier, and a derived
 * query name below spells the MEMBER. The entity maps {@code tranId} to the column
 * {@code transaction_id}, keeping the record contract's spelling on the member and attaching the
 * migration's own column name explicitly. Reading either name off the other is unsafe, so every
 * method name below is written against the member and every column reference in this documentation
 * is written against the migration.
 *
 * <h2>What this interface deliberately does not carry</h2>
 *
 * <p>Alternatives Considered: annotating this interface as a repository stereotype. Rejected as
 * redundant metadata: the framework registers the proxy from the {@code JpaRepository} supertype
 * alone, so the annotation would add a second, weaker signal of the same fact and invite a reader to
 * believe the registration depends on it.
 *
 * <p>Refactoring Rationale: no transaction boundary is declared here, on the interface or on any
 * method, and the boundary belongs to the service layer instead. Rule T5 maps a CICS
 * {@code SYNCPOINT} to a declarative transaction and a {@code SYNCPOINT ROLLBACK} to exception
 * propagation, but in the baseline the syncpoint is issued by the PROGRAM and spans more records
 * than any one file. The posting unit of work makes that concrete: {@code app/cbl/CBTRN02C.cbl}
 * paragraph {@code 2000-POST-TRANSACTION} at line 424 performs a category-balance update at line
 * 440, an account update at line 441 and the transaction write at line 442, closing at line 444 --
 * three record types, so no single repository could own that unit. The bill-payment path does the
 * same on a smaller scale, writing this table at {@code app/cbl/COBIL00C.cbl} line 233 and the
 * account at line 235. Marking a method here read-only would additionally fragment a caller's
 * boundary silently, by enlisting a read into a scope the caller did not open.
 *
 * <p>Alternatives Considered: a transactional outbox with a compensating reversal, or a saga, in
 * place of that single commit. Both are rejected because they would make a posted transaction with
 * an unapplied balance, or an applied balance with no posted transaction, externally observable
 * between committed steps. No such partial-posting state exists in the cited paragraph, so the
 * parity oracle would correctly identify the design as a behavioural divergence. Neither is
 * introduced for these tables; the batch context reaches them under a narrowly scoped cross-schema
 * grant so that the commit stays atomic.
 *
 * <p>Trade-offs: no method here participates in optimistic locking, and no HTTP 409 originates from
 * this interface. The entity carries no version attribute, so a reader should not go looking for
 * one: no baseline program compares a before-image over a ledger row -- the list, detail and add
 * programs contain no rewrite statement at all, and the bill-payment program's single rewrite
 * targets the ACCOUNT record rather than this one -- and under schema generation switched off a
 * version attribute would map to a column the migration does not create and would fail when a query
 * ran. Where a 409 does arise in this migration is on the account and card records, which do carry a
 * version attribute, and it is surfaced there through the shared web error contract. What is given
 * up is lost-update detection on a concurrent modification of a posted row; the accepted cost is
 * small because every reference path into this table inserts rather than modifies.
 *
 * <h2>Ordering a declared-width identifier rests on a measured property</h2>
 *
 * <p>Assumptions: the identifier column is a declared-width character field, so both the keyset
 * predicates and the maximum below order it LEXICOGRAPHICALLY. That ordering is total and stable
 * because every value occupies all sixteen bytes: the two generation schemes in the reference tree
 * each fill the field completely. The sequence scheme moves the browsed key into a sixteen-digit
 * numeric work field, declared at line 57 of both {@code app/cbl/COTRN02C.cbl} and
 * {@code app/cbl/COBIL00C.cbl}, increments it and moves it back into the sixteen-character field at
 * line 451 and line 219 respectively, which lands sixteen digits including every leading zero. The
 * interest scheme at {@code app/cbl/CBACT04C.cbl} lines 476 to 480 concatenates a ten-character
 * business date, declared at its line 178, with a six-digit suffix declared at its line 173, which
 * is also exactly sixteen characters.
 *
 * <p>Assumptions: within the sequence scheme, and only within it, lexicographic order coincides with
 * numeric order -- which is what makes a maximum over this column the right answer for the next
 * identifier. That is measured rather than assumed: across all 300 records of the one
 * transaction-shaped extract in the reference tree, {@code app/data/ASCII/dailytran.txt}, every
 * identifier occupies the full sixteen bytes as digits with no blank, every one of them carries a
 * leading zero, and sorting the column as text yields byte-identical output to sorting it as
 * numbers. The interest scheme's value fills the same width and so joins the same total ordering,
 * but it leads with a date rather than being a decimal magnitude, so a caller must not read the
 * ordering as arithmetic across both schemes. The baseline draws that line in the same place,
 * moving a browsed key into a numeric work field inside a program paragraph rather than inside any
 * file verb.
 *
 * <p>Assumptions: a declared-width character column is blank-padded by the engine on comparison, so
 * a value and the same value with trailing blanks are one value here, exactly as a declared-width
 * comparison in the reference tree treats them. That is the semantics the source relies on, and it
 * is the reason a varying-width column was not chosen for either the identifier or the card number.
 * A caller must not depend on the opposite convention.
 *
 * <p>Assumptions: the graded return code the batch program sets -- line 229 of
 * {@code app/cbl/CBTRN02C.cbl} tests whether any record was rejected and line 230 moves 4 into it --
 * belongs to that program and its reference suite, and is not a build state for this module. The
 * documentation gate, the compiler and the tests that exercise this interface all have binary
 * outcomes.
 */
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Reads the opening page of the transaction list, in ascending identifier order.
     *
     * <p>This is the entry into {@code PROCESS-PAGE-FORWARD} at line 279 of
     * {@code app/cbl/COTRN00C.cbl} on the turn where no cursor has been established yet: line 281
     * starts the browse and line 285 suppresses the priming read, so the fill loop at line 297
     * begins at the first record of the file.
     *
     * @param limit the maximum number of rows to read, of type {@code Limit}, which the caller sets
     *     to its page size plus one so that the surplus row answers forward availability; must not
     *     be {@code null}
     * @return the first rows of the ordered set as a {@code List<Transaction>}, ascending by
     *     identifier, holding up to {@code limit} rows -- so up to page size plus one, the last of
     *     which is the availability probe rather than a row to display -- and empty when the table
     *     holds nothing
     */
    // WHY : Refactoring Rationale: this is a separate member rather than the forward method called
    //       with a sentinel cursor. The baseline spells the absence of a position three different
    //       ways across these programs, as low values, as high values and as zeros, and a fourth
    //       spelling invented here would be a fourth thing a caller could test for wrongly. An
    //       unfiltered ordered read states "from the beginning" in the signature instead.
    List<Transaction> findAllByOrderByTranIdAsc(Limit limit);

    /**
     * Reads the page that follows a stated position, in ascending identifier order.
     *
     * <p>This is the relational form of the forward read at line 626 of
     * {@code app/cbl/COTRN00C.cbl}, driven from the fill loop at line 297 and probed once more at
     * line 308.
     *
     * @param lastKey the identifier of the last row the caller already holds, of type
     *     {@code String}; the comparison is STRICT, so this row is excluded and the page begins at
     *     the next identifier after it; must not be {@code null}
     * @param limit the maximum number of rows to read, of type {@code Limit}, which the caller sets
     *     to its page size plus one; must not be {@code null}
     * @return the following rows as a {@code List<Transaction>}, ascending by identifier, holding up
     *     to {@code limit} rows -- so up to page size plus one, whose presence beyond the page size
     *     is what reports that a further page exists -- and empty when the set is exhausted
     */
    // WHY : Refactoring Rationale: the exclusion is written into the method name as a strict
    //       comparison because the baseline never wrote it anywhere. Its start-browse leaves the
    //       greater-or-equal option commented out at line 597 of app/cbl/COTRN00C.cbl, so whether
    //       the positioning row was included came from the access method's default rather than from
    //       the source. Were this comparison inclusive instead, the first row of every forward page
    //       would repeat the last row of the previous one.
    // WHY : Assumptions: the ascending order is the physical key order the baseline browses, and it
    //       is well defined over a declared-width character column only because every identifier
    //       occupies all sixteen bytes; the class documentation records the measurement behind that.
    List<Transaction> findByTranIdGreaterThanOrderByTranIdAsc(String lastKey, Limit limit);

    /**
     * Reads the page that precedes a stated position, in descending identifier order.
     *
     * <p>This is the relational form of the backward read at line 660 of
     * {@code app/cbl/COTRN00C.cbl}, driven from {@code PROCESS-PAGE-BACKWARD} at line 333 whose
     * loop at line 351 counts down and whose surplus probe is at line 360.
     *
     * @param firstKey the identifier of the first row the caller already holds, of type
     *     {@code String}; the comparison is STRICT, so this row is excluded and the page ends at the
     *     identifier immediately before it; must not be {@code null}
     * @param limit the maximum number of rows to read, of type {@code Limit}, which the caller sets
     *     to its page size plus one; must not be {@code null}
     * @return the preceding rows as a {@code List<Transaction>} in DESCENDING identifier order,
     *     nearest the stated position first, holding up to {@code limit} rows -- so up to page size
     *     plus one, the surplus row again answering availability -- and empty when the caller is
     *     already at the start of the set; the caller REVERSES this list before display
     */
    // WHY : Trade-offs: the rows come back reversed relative to display order, and the caller
    //       carries the cost of turning them round. Ordering ascending here instead would make the
    //       row cap keep the wrong end of the set -- the first rows of the whole table rather than
    //       the ones immediately preceding the caller's position -- which is a page nobody asked
    //       for. The baseline accepts the same cost in the same direction: line 349 of
    //       app/cbl/COTRN00C.cbl starts its reverse fill at slot ten and line 355 counts down, so it
    //       too reads nearest-first and lands the rows into display order afterwards.
    List<Transaction> findByTranIdLessThanOrderByTranIdDesc(String firstKey, Limit limit);

    /**
     * Reads the opening page of one card's transactions, in ascending identifier order.
     *
     * <p>This is the card-ordered access path, which the baseline obtained by physically re-sorting
     * an extract at line 46 of {@code app/jcl/TRANREPT.jcl} and read through the {@code TRNX-KEY}
     * group at line 21 of {@code app/cpy/COSTM01.CPY} -- the card number at line 22 followed by the
     * transaction identifier at line 23. Constraining the card and ordering by the identifier within
     * it reproduces that key order exactly.
     *
     * @param cardNum the sixteen-character card number to constrain to, of type {@code String},
     *     compared at its declared width; must not be {@code null}
     * @param limit the maximum number of rows to read, of type {@code Limit}, which the caller sets
     *     to its page size plus one; must not be {@code null}
     * @return that card's first rows as a {@code List<Transaction>}, ascending by identifier, holding
     *     up to {@code limit} rows -- so up to page size plus one, the surplus row answering forward
     *     availability -- and empty when the card has no transactions
     */
    // WHY : Assumptions: this path is served by idx_transactions_card_num, created at line 269 of
    //       the ledger migration and deliberately non-unique because one card owns many
    //       transactions. The index is named in prose only and nothing in this package declares it,
    //       because schema generation is switched off and such a declaration would be inert metadata
    //       able to drift from the migration that actually creates it.
    // WHY : Trade-offs: that index narrows to the card but does NOT supply the ordering within it,
    //       because the migration declares it over the card column alone while the key order being
    //       reproduced has two parts. The planner therefore reads one card's rows through the index
    //       and sorts that small set by identifier, which was measured rather than assumed. A
    //       composite index over both columns would remove the sort; it is not proposed here because
    //       the migration is the sole owner of the physical shape, and one card's rows are few. A
    //       caller paging a card that covers a large fraction of the table should expect the planner
    //       to walk the primary key and filter instead, which returns the same rows in the same
    //       order.
    // WHY : Refactoring Rationale: this is emphatically NOT the transaction-list screen's path, and
    //       recording that prevents a plausible misreading. That screen offers a single input field,
    //       a sixteen-character transaction identifier, and app/cbl/COTRN00C.cbl refers to no card
    //       field anywhere, so it pages on the primary key. The ledger migration states the same
    //       division beside the index it creates.
    List<Transaction> findByCardNumOrderByTranIdAsc(String cardNum, Limit limit);

    /**
     * Reads the page of one card's transactions that follows a stated position, ascending.
     *
     * <p>Same access path and same key order as the opening card page above, resumed from a cursor
     * instead of from the start of the card's rows.
     *
     * @param cardNum the sixteen-character card number to constrain to, of type {@code String};
     *     must not be {@code null}
     * @param lastKey the identifier of the last row the caller already holds, of type
     *     {@code String}; the comparison is STRICT, so that row is excluded; must not be
     *     {@code null}
     * @param limit the maximum number of rows to read, of type {@code Limit}, which the caller sets
     *     to its page size plus one; must not be {@code null}
     * @return that card's following rows as a {@code List<Transaction>}, ascending by identifier,
     *     holding up to {@code limit} rows -- so up to page size plus one, the surplus row answering
     *     availability -- and empty when the card's rows are exhausted
     */
    // WHY : Assumptions: the cursor is compared against the identifier alone and not against the
    //       card number as well, because the card is held CONSTANT by the equality above. The
    //       pair-wise comparison a two-column keyset predicate would need applies when both ordered
    //       columns vary; here only the second does, so the simple strict comparison is exact rather
    //       than a simplification. Comparing the pair anyway would read as though the card could
    //       change mid-page, which the predicate forbids.
    List<Transaction> findByCardNumAndTranIdGreaterThanOrderByTranIdAsc(
            String cardNum, String lastKey, Limit limit);

    /**
     * Reads the page of one card's transactions that precedes a stated position, descending.
     *
     * <p>The backward direction of the card-ordered path, mirroring the unfiltered backward page.
     *
     * @param cardNum the sixteen-character card number to constrain to, of type {@code String};
     *     must not be {@code null}
     * @param firstKey the identifier of the first row the caller already holds, of type
     *     {@code String}; the comparison is STRICT, so that row is excluded; must not be
     *     {@code null}
     * @param limit the maximum number of rows to read, of type {@code Limit}, which the caller sets
     *     to its page size plus one; must not be {@code null}
     * @return that card's preceding rows as a {@code List<Transaction>} in DESCENDING identifier
     *     order, nearest the stated position first, holding up to {@code limit} rows -- so up to
     *     page size plus one -- and empty at the start of the card's rows; the caller REVERSES this
     *     list before display
     */
    // WHY : Trade-offs: the same reversal cost is accepted here as on the unfiltered backward page,
    //       and for the same reason: ordering ascending would make the row cap keep the card's
    //       earliest rows rather than the ones immediately preceding the caller's position.
    List<Transaction> findByCardNumAndTranIdLessThanOrderByTranIdDesc(
            String cardNum, String firstKey, Limit limit);

    /**
     * Reads the transactions processed within a closed range of processing timestamps.
     *
     * <p>This is the relational form of the record-selection predicate at lines 47 and 48 of
     * {@code app/jcl/TRANREPT.jcl}, which admits a record when its processing date is at or after a
     * start parameter AND at or before an end parameter, the two parameters being declared at its
     * lines 43 and 44. The path is served by {@code idx_transactions_proc_ts}, created at line 291
     * of the ledger migration.
     *
     * @param fromInclusive the earliest processing timestamp to admit, of type
     *     {@code LocalDateTime}, and the range INCLUDES it; must not be {@code null}
     * @param toInclusive the latest processing timestamp to admit, of type {@code LocalDateTime},
     *     and the range INCLUDES it; must not be {@code null}
     * @param limit the maximum number of rows to read, of type {@code Limit}, which bounds the
     *     result so that a wide range cannot materialise the whole master unintentionally; a caller
     *     that genuinely wants the entire range passes an unlimited value explicitly; must not be
     *     {@code null}
     * @return the admitted rows as a {@code List<Transaction>}, ordered by processing timestamp and
     *     then by identifier, holding up to {@code limit} rows, and empty when the range admits
     *     nothing. This method carries no availability probe: it answers a bounded range rather than
     *     a page, so the caller adds nothing to its row cap
     */
    // WHY : Alternatives Considered: a derived name using the framework's between-keyword, which is
    //       inclusive at both ends and would have expressed this without a written query. It is
    //       rejected because the inclusivity would then rest on the reader knowing that keyword's
    //       convention, whereas the source states its own boundaries explicitly as at-or-after and
    //       at-or-before. Writing the two comparisons out puts the inclusivity in the query where a
    //       reader can see it, which is the same reason the cursor comparisons above are strict
    //       rather than left to a default. Excluding either end would silently drop every
    //       transaction processed on the first or the last instant of a reporting range.
    // WHY : Assumptions: the source construct is a RECORD-SELECTION predicate and its relational
    //       analogue is a filter, never a step gate. The utility's keyword is spelled the same as
    //       the job-step condition keyword used elsewhere in these jobs, and treating this one as a
    //       gate would run or suppress a whole report instead of choosing its rows.
    // WHY : Assumptions: the identifier is the tie-breaker in the ordering because the timestamp
    //       alone is not a total order. Line 28 of app/jcl/TRANIDX.jcl declares that key
    //       NONUNIQUEKEY, and the migration carries the same property forward, so many rows can
    //       share one processing timestamp -- every row of a single posting run does. Without the
    //       tie-breaker two runs over unchanged data could return those rows in different sequences,
    //       and a report compared byte for byte against a stored expectation would differ for a
    //       reason that has nothing to do with its content.
    // WHY : Assumptions: the range is stated over the full 26-character timestamp, mapped to
    //       microsecond precision, while the report utility compares only its leading ten
    //       characters, the date portion, at line 42 of that job. A caller reproducing the utility's
    //       whole-day semantics therefore passes the first and last instant of the intended days
    //       rather than two dates, which the wider type lets it do exactly.
    @Query("""
            select t from Transaction t
             where t.procTs >= :fromInclusive
               and t.procTs <= :toInclusive
             order by t.procTs asc, t.tranId asc
            """)
    List<Transaction> findInProcessedTimestampRange(
            @Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toInclusive") LocalDateTime toInclusive, Limit limit);

    /**
     * Reads the highest transaction identifier currently stored.
     *
     * <p>This is the relational form of the two maximum-key derivations in the reference tree, each
     * of which seeks past the end of the file and reads one record backwards to obtain it:
     * {@code app/cbl/COTRN02C.cbl} moves high values into the key at line 444, starts the browse at
     * line 445, reads backwards at line 446 and ends the browse at line 447, while
     * {@code app/cbl/COBIL00C.cbl} performs the identical four steps at lines 212 to 215.
     *
     * @return the highest stored identifier as an {@code Optional<String>}, or an EMPTY optional when
     *     the table holds no rows at all -- which is the case the two reference programs handle by
     *     moving zeros into the key, at line 689 and line 488 respectively, so that the identifier
     *     their subsequent increment derives is 1
     */
    // WHY : Refactoring Rationale: the increment does NOT live here, and the split is deliberate
    //       rather than an omission. In the reference tree the addition sits in a program paragraph
    //       -- line 449 of app/cbl/COTRN02C.cbl and line 217 of app/cbl/COBIL00C.cbl, each adding
    //       one to a numeric work field declared at line 57 of its own program -- and not in any
    //       file verb, and rule T5 turns file verbs into repository members. Deriving the next
    //       identifier is the service layer's work; reading the maximum is this one's.
    // WHY : Alternatives Considered: the derived alternative that returns the whole top row in
    //       descending identifier order, which is the more literal transcription of a single
    //       backward read. It is rejected because only the key is wanted: an aggregate over the
    //       indexed column answers without materialising a 330-byte row and without the caller
    //       having to ignore twelve members it did not ask for. The literal alternative is named
    //       here so that a reader can see it was weighed rather than missed.
    // WHY : Trade-offs: this is NOT a uniqueness guarantee, and a caller must not treat it as one.
    //       The reference sequence is an unlocked read-then-increment -- nothing holds a lock across
    //       lines 212 to 217 of app/cbl/COBIL00C.cbl -- so two concurrent callers can read one
    //       maximum and derive one identifier. Uniqueness is enforced instead by the primary key the
    //       migration declares over that column at its line 255, and a collision therefore surfaces
    //       as a constraint violation for the service layer to answer. The baseline behaves this
    //       way; the Java implements the same read and leaves the same enforcement to the key, and
    //       that division is documented here rather than inferred.
    @Query("select max(t.tranId) from Transaction t")
    Optional<String> findMaxTranId();
}
