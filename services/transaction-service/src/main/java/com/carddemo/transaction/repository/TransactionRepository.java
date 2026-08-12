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
 * <p>Every relational equivalent of a file operation the baseline issues against its {@code TRANSACT}
 * dataset is either declared in this interface or inherited into it, and none is expressed anywhere
 * else in this module. The package charter beside this file makes that the boundary: no controller,
 * service, mapper, DTO or domain type may hold a query. The record layout, the byte positions the two
 * indexes rest on and the full VSAM-to-relational mapping are set out in
 * {@code docs/architecture/data-model-and-schema-mapping.md}; the physical contract is
 * {@code db/migration/V1__ledger.sql}.
 *
 * <p>Assumptions: no method below carries an exception at-clause, and the omission is uniform. Every
 * member here is a query; a query raises no checked exception, and the unchecked data-access failures
 * the framework translates are not conditions a caller handles per call site -- they surface through
 * this module's shared web error contract. Declaring a speculative at-clause on each member would add
 * unverifiable claims rather than facts.
 *
 * <p>Assumptions: AAP transformation rule T5 maps each CICS file verb to exactly one target, and this
 * interface is where each target lives. A keyed {@code READ} ({@code app/cbl/COTRN01C.cbl} line 269)
 * becomes the inherited {@code findById}; a {@code WRITE} ({@code app/cbl/COBIL00C.cbl} line 512)
 * becomes the inherited {@code save}; and the four-verb browse ensemble of
 * {@code app/cbl/COTRN00C.cbl} -- {@code STARTBR} at line 593, {@code READNEXT} at line 626,
 * {@code READPREV} at line 660, {@code ENDBR} at line 694 -- becomes ONE keyset query per direction
 * rather than four members. {@code ENDBR} has no target at all: a query holds no position between
 * calls, so there is no browse to close.
 *
 * <p>Alternatives Considered: reading {@code app/cbl/COBIL00C.cbl} as a second list implementation,
 * which its browse-verb paragraphs at lines 441, 472 and 501 superficially invite. Rejected on
 * measurement: those 572 lines contain no forward-read verb at all, and lines 212 to 217 perform a seek
 * from high values, one backward read, an end-browse and an increment -- a highest-key lookup whose
 * relational equivalent is a maximum over the key column. The honest tally of what this interface
 * replaces is therefore ONE forward and backward browse ({@code COTRN00C} lines 279 to 376) plus TWO
 * maximum-key derivations ({@code COTRN02C} lines 444 to 449 and {@code COBIL00C} lines 212 to 217),
 * and the page envelope must never be wired into the bill-payment path.
 *
 * <p>Assumptions: the baseline browse state is already a cursor over keys, so the mapping is
 * one-to-one and nothing had to be invented. {@code COTRN00C} lines 63 and 64 declare the first and
 * last identifiers of the displayed page as {@code PIC X(16)}, line 66 the availability flag; nothing
 * in that structure counts rows already consumed. The cursor is a SINGLE SCALAR of sixteen characters
 * and not a composite, which is why every keyset method below takes one {@code String} cursor -- a
 * sibling browse in another bounded context composes a longer key, and its shape must not be read into
 * this one.
 *
 * <p>Trade-offs: each paging method hands its caller the whole slice it read, up to page size plus one
 * rows, and does not trim the surplus row. The row cap is a transcription rather than a heuristic:
 * {@code COTRN00C} fills exactly ten slots and then performs an ELEVENTH read at line 308 for no
 * purpose other than to discover whether anything follows, setting the availability flag from that
 * read's outcome alone at lines 309 to 313. The compromise accepted is that a caller must know to
 * render one fewer row than it may receive; what is bought is that this interface stays the file-verb
 * layer under rule T5 and holds no presentation concern. The baseline draws the line in the same place
 * -- its eleventh read is discarded inside the program paragraph, not inside the file access -- and the
 * shared envelope {@code com.carddemo.common.web.PageResponse} is assembled by the service layer,
 * which is why this interface neither returns nor imports it.
 *
 * <p>Refactoring Rationale: the cursor comparisons below are spelled strictly, {@code > lastKey}
 * forward and {@code < firstKey} backward, because the baseline positioning was implicit and a query
 * reader cannot see an implicit default. The start-browse paragraph's greater-or-equal option at
 * {@code COTRN00C} line 597 is COMMENTED OUT, so positioning fell to the file system's default rather
 * than to anything the source states. Stating the predicate makes the boundary an artifact of the
 * query instead of an artifact of the access method.
 *
 * <p>Alternatives Considered: offset pagination, rejected on a defect rather than a preference. An
 * insert landing before a counted position changes how many rows precede it, so a page positioned that
 * way skips rows it never showed and repeats rows it already showed; a key already returned keeps its
 * place no matter what is inserted around it. The concurrency is attested by the baseline itself --
 * nothing holds a lock across {@code COBIL00C} lines 212 to 217, so two payments can read one maximum
 * and both insert into the middle of the key space a browse is walking. Neither framework shape for
 * that mechanism is used: {@code Pageable} emits a SQL {@code OFFSET} clause, and {@code Page}
 * additionally issues a counting query for totals the baseline neither has nor needs. The row cap is
 * {@code Limit} and every return type is {@code List<Transaction>}.
 *
 * <p>Assumptions: three ordered access paths exist and two indexes carry two of them. The primary key
 * on the identifier serves both the keyed lookup and the list screen's paging, which is what keeps that
 * paging a single ordered index scan. {@code idx_transactions_card_num} carries the card-ordered path
 * -- which is NOT the list screen's, because that screen offers one input field, a sixteen-character
 * identifier, and its program refers to no card field at all. {@code idx_transactions_proc_ts} is the
 * surviving half of the batch alternate index at {@code app/jcl/TRANIDX.jcl} line 27: the KEY carries
 * forward, while the {@code BLDINDEX} build step at line 52 has no target because the engine maintains
 * a declared index transactionally. Retired as a migration target never means deleted -- that job
 * stands unmodified on disk.
 *
 * <p>Assumptions: the byte positions those paths rest on agree three independent ways, which is why no
 * position-bound decision here is derived again. Summing the declared widths of
 * {@code app/cpy/CVTRA05Y.cpy} lines 4 to 18 puts the card number at 263 to 278 and the processing
 * timestamp at 305 to 330; {@code app/jcl/TRANREPT.jcl} names one-based 263 at line 41 and 305 at line
 * 42; and the alternate index names zero-based 304, the same one-based 305. The report job types the
 * card field {@code ZD} for the sort utility's purposes while the copybook declares {@code PIC X(16)};
 * rule T1 makes the copybook authoritative, so a leading zero in that column is significant.
 *
 * <p>Alternatives Considered: restating either index as declarative index metadata on the entity's
 * table mapping. Rejected because schema generation is switched off, so such metadata is never acted
 * on: it would neither create nor verify an index, and could drift out of step with the migration that
 * does create them while still reading like a specification. Both indexes are named in prose only, and
 * no declaration in this package shapes DDL. The consequence to keep in mind is that nothing
 * reconciles a query here against a column there at start-up, so a mismatch stays invisible until the
 * query runs -- which is also why every method name below is written against the entity MEMBER
 * ({@code tranId}) and every column reference in this documentation against the migration
 * ({@code transaction_id}).
 *
 * <p>Refactoring Rationale: no transaction boundary is declared here, on the interface or on any
 * method. Rule T5 maps a CICS {@code SYNCPOINT} to a declarative transaction, but in the baseline the
 * syncpoint is issued by the PROGRAM and spans more records than any one file:
 * {@code app/cbl/CBTRN02C.cbl} paragraph {@code 2000-POST-TRANSACTION} at line 424 performs a
 * category-balance update at line 440, an account update at line 441 and the transaction write at line
 * 442, so no single repository could own that unit. Marking a method read-only would additionally
 * fragment a caller's boundary silently, by enlisting a read into a scope the caller did not open. A
 * transactional outbox with compensating reversal, and a saga, were both rejected for these tables
 * because either would make a partial-posting state externally observable that the cited paragraph
 * never exhibits.
 *
 * <p>Trade-offs: no method here participates in optimistic locking and no HTTP 409 originates from
 * this interface. No baseline program compares a before-image over a ledger row -- the list, detail and
 * add programs contain no rewrite statement, and the bill-payment program's single rewrite targets the
 * ACCOUNT record -- and with schema generation off a version attribute would map to a column the
 * migration does not create. What is given up is lost-update detection on a concurrent modification of
 * a posted row; the cost is small because every reference path into this table inserts rather than
 * modifies. Neither is a repository stereotype declared: the framework registers the proxy from the
 * {@code JpaRepository} supertype alone.
 *
 * <p>Assumptions: the identifier column is a declared-width character field, so both the keyset
 * predicates and the maximum below order it LEXICOGRAPHICALLY, and that ordering is total only because
 * every value occupies all sixteen bytes. Both generation schemes fill it completely -- the sequence
 * scheme moves a sixteen-digit numeric work field back into the character field at
 * {@code app/cbl/COTRN02C.cbl} line 451 and {@code app/cbl/COBIL00C.cbl} line 219, and the interest
 * scheme at {@code app/cbl/CBACT04C.cbl} lines 476 to 480 concatenates a ten-character business date
 * with a six-digit suffix. Within the sequence scheme, and only within it, lexicographic order
 * coincides with numeric order, which is measured rather than assumed: across all 300 records of
 * {@code app/data/ASCII/dailytran.txt} every identifier fills sixteen bytes as digits and sorting the
 * column as text yields byte-identical output to sorting it as numbers. The interest scheme leads with
 * a date, so a caller must not read the ordering as arithmetic across both schemes. A declared-width
 * column is also blank-padded on comparison, so a value and the same value with trailing blanks are one
 * value here, exactly as a declared-width comparison in the reference tree treats them.
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
    // Refactoring Rationale: this is a separate member rather than the forward method called
    //     with a sentinel cursor. The baseline spells the absence of a position three different
    //     ways across these programs, as low values, as high values and as zeros, and a fourth
    //     spelling invented here would be a fourth thing a caller could test for wrongly. An
    //     unfiltered ordered read states "from the beginning" in the signature instead.
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
    // Refactoring Rationale: the exclusion is written into the method name as a strict
    //     comparison because the baseline never wrote it anywhere. Its start-browse leaves the
    //     greater-or-equal option commented out at line 597 of app/cbl/COTRN00C.cbl, so whether
    //     the positioning row was included came from the access method's default rather than from
    //     the source. Were this comparison inclusive instead, the first row of every forward page
    //     would repeat the last row of the previous one.
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
    // Trade-offs: the rows come back reversed relative to display order, and the caller
    //     carries the cost of turning them round. Ordering ascending here instead would make the
    //     row cap keep the wrong end of the set -- the first rows of the whole table rather than
    //     the ones immediately preceding the caller's position -- which is a page nobody asked
    //     for. The baseline accepts the same cost in the same direction: line 349 of
    //     app/cbl/COTRN00C.cbl starts its reverse fill at slot ten and line 355 counts down, so it
    //     too reads nearest-first and lands the rows into display order afterwards.
    List<Transaction> findByTranIdLessThanOrderByTranIdDesc(String firstKey, Limit limit);

    /**
     * Reads the opening page of one card's transactions, in ascending identifier order.
     *
     * <p>This is the card-ordered access path, which the baseline obtained by physically re-sorting
     * an extract at line 46 of {@code app/jcl/TRANREPT.jcl} and read through the {@code TRNX-KEY}
     * group at line 21 of {@code app/cpy/COSTM01.CPY} -- the card number at line 22 followed by the
     * transaction identifier at line 23. Constraining the card and ordering by the identifier within
     * it reproduces that key order exactly. The path is served by {@code idx_transactions_card_num}
     * in {@code db/migration/V1__ledger.sql}, non-unique because one card owns many transactions.
     *
     * @param cardNum the sixteen-character card number to constrain to, of type {@code String},
     *     compared at its declared width; must not be {@code null}
     * @param limit the maximum number of rows to read, of type {@code Limit}, which the caller sets
     *     to its page size plus one; must not be {@code null}
     * @return that card's first rows as a {@code List<Transaction>}, ascending by identifier, holding
     *     up to {@code limit} rows -- so up to page size plus one, the surplus row answering forward
     *     availability -- and empty when the card has no transactions
     */
    // Refactoring Rationale: this is emphatically NOT the transaction-list screen's path, and
    //     recording that prevents a plausible misreading. That screen offers a single input field,
    //     a sixteen-character transaction identifier, and app/cbl/COTRN00C.cbl refers to no card
    //     field anywhere, so it pages on the primary key. The ledger migration states the same
    //     division beside the index it creates.
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
    // Assumptions: the cursor is compared against the identifier alone and not against the
    //     card number as well, because the card is held CONSTANT by the equality above. The
    //     pair-wise comparison a two-column keyset predicate would need applies when both ordered
    //     columns vary; here only the second does, so the simple strict comparison is exact rather
    //     than a simplification. Comparing the pair anyway would read as though the card could
    //     change mid-page, which the predicate forbids.
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
    // Trade-offs: the same reversal cost is accepted here as on the unfiltered backward page,
    //     and for the same reason: ordering ascending would make the row cap keep the card's
    //     earliest rows rather than the ones immediately preceding the caller's position.
    List<Transaction> findByCardNumAndTranIdLessThanOrderByTranIdDesc(
            String cardNum, String firstKey, Limit limit);

    /**
     * Reads the transactions processed within a closed range of processing timestamps.
     *
     * <p>This is the relational form of the record-selection predicate at lines 47 and 48 of
     * {@code app/jcl/TRANREPT.jcl}, which admits a record when its processing date is at or after a
     * start parameter AND at or before an end parameter, the two parameters being declared at its
     * lines 43 and 44. The path is served by {@code idx_transactions_proc_ts} in
     * {@code db/migration/V1__ledger.sql}, cited by name rather than by line because a line number
     * in a migration moves whenever a statement above it is edited.
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
    // Alternatives Considered: a derived name using the framework's between-keyword, which is
    //     inclusive at both ends and would have expressed this without a written query. It is
    //     rejected because the inclusivity would then rest on the reader knowing that keyword's
    //     convention, whereas the source states its own boundaries explicitly as at-or-after and
    //     at-or-before. Writing the two comparisons out puts the inclusivity in the query where a
    //     reader can see it, which is the same reason the cursor comparisons above are strict
    //     rather than left to a default. Excluding either end would silently drop every
    //     transaction processed on the first or the last instant of a reporting range.
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
     * Reads the highest transaction identifier currently stored, for the COPY path only.
     *
     * <p>This is the relational form of the backward read at lines 475 to 478 of
     * {@code app/cbl/COTRN02C.cbl}, which positions past the end of the file and reads one record
     * backwards in order to populate the form with the LAST STORED TRANSACTION so an operator can
     * amend a copy of it. Its caller is {@code TransactionAddService.readLatestTransaction}, which
     * follows this identifier with a keyed read because that program keeps the whole record and not
     * only its key.
     *
     * <p>Refactoring Rationale: this member is NOT the identifier allocator and must never be used as
     * one, which is a narrowing of what it previously documented. Its earlier description presented it
     * as "the relational form of the two maximum-key derivations in the reference tree" and explained
     * how the reference handles an empty table "so that the identifier their subsequent increment
     * derives is 1" -- a description of the ALLOCATION use, which read the maximum and had a caller add
     * one to it. Two Fargate tasks behind a load balancer are not serialised the way one CICS region
     * serialised those two transactions, so read-then-add produced the same key twice and one caller
     * received a constraint violation as an internal error. {@link #allocateTransactionId()} took that
     * use over, and this member kept only the one it is still correct for. The description is narrowed
     * rather than the member removed, because the copy path genuinely needs the last stored record and
     * a sequence's next value is not it: the sequence names a row that does not exist yet.
     *
     * <p>Assumptions: the identifier is returned rather than the row, and the caller performs the keyed
     * read. Returning the whole row would put a second projection of the transaction table in this
     * interface for one caller's convenience, and the keyed read it saves is an index lookup.
     *
     * @return the highest stored identifier as an {@code Optional<String>}, or an EMPTY optional when
     *     the table holds no rows at all, which the copy path answers by leaving the form empty
     */
    @Query("select max(t.tranId) from Transaction t")
    Optional<String> findMaxTranId();

    /**
     * Allocates the next transaction identifier atomically, from the database's own allocator.
     *
     * <p>This is the target form of the two maximum-key-plus-one derivations the reference performs.
     * {@code app/cbl/COTRN02C.cbl} positions a browse at high values at line 444, reads backwards at
     * line 446 and adds one to the key it found at line 449; {@code app/cbl/COBIL00C.cbl} performs the
     * same four steps at lines 212 to 217. Under CICS those two transactions were serialised by the
     * region, so read-then-add was safe there.</p>
     *
     * <p>Refactoring Rationale: this method exists because read-then-add is NOT safe here, and
     * {@link #findMaxTranId()} was being used for it -- that member now documents itself as the copy
     * path's read alone, so the two uses cannot be confused again. Two Fargate tasks behind a load balancer are not
     * serialised: both read the same maximum, both add one, and both attempt the same primary key. One
     * succeeds and the other fails on a constraint violation that reaches the caller as an internal
     * error rather than as anything it can act on. Allocating in the database makes the increment
     * indivisible, so the two callers receive different values without either waiting on the other.</p>
     *
     * <p>Assumptions: a NATIVE query, because sequence allocation has no expression in the object
     * query language -- there is no portable way to say "advance a sequence" in it. The sequence is
     * named in full, schema included, so the statement does not depend on the connection's search path
     * being set to this schema at the moment it runs.</p>
     *
     * <p>Assumptions: the value is returned as a {@code Long} and the caller renders it as sixteen
     * zero-padded digit characters. The rendering is deliberately NOT done here: the column is
     * {@code CHAR(16)} because {@code app/cpy/CVTRA05Y.cpy} line 5 declares an alphanumeric picture and
     * leading zeros are significant, and formatting is presentation rather than data access. Rule T5
     * turns file verbs into repository members, and rendering is neither.</p>
     *
     * <p>Assumptions: the allocation is NOT undone by a rollback, which is a property of sequences
     * rather than an oversight. A transaction that allocates and then fails leaves a gap in the
     * identifier space, and nothing in the reference tree reads a gap as meaningful -- the report job
     * orders by processing timestamp and card number rather than by identifier arithmetic. Gapless
     * allocation would require serialising every writer behind a single lock, which is the cost this
     * method exists to avoid.</p>
     *
     * <p>Assumptions: this serves the two INTERACTIVE write paths only. The interest job composes its
     * identifiers from a business-date prefix and a per-run suffix, at
     * {@code app/cbl/CBACT04C.cbl} lines 474 to 480, and does not allocate here -- which is why the
     * allocator's own migration filters to all-digit identifiers when it positions itself past the
     * loaded data.</p>
     *
     * @return the next identifier as a positive number, never {@code null}; the caller pads it to
     *     sixteen digit characters
     */
    @Query(value = "select nextval('ledger.transaction_id_seq')", nativeQuery = true)
    Long allocateTransactionId();
}
