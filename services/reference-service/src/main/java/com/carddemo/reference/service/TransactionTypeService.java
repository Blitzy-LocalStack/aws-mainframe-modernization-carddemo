package com.carddemo.reference.service;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.observability.ThrowableDigest;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.PageDirection;
import com.carddemo.reference.dto.TransactionTypeCreateRequest;
import com.carddemo.reference.dto.TransactionTypeListRequest;
import com.carddemo.reference.dto.TransactionTypeResponse;
import com.carddemo.reference.dto.TransactionTypeUpdateRequest;
import com.carddemo.reference.mapper.TransactionTypeMapper;
import com.carddemo.reference.repository.TransactionCategoryRepository;
import com.carddemo.reference.repository.TransactionTypeRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the transaction-type rules the baseline reference screens encode.
 *
 * <p>Purpose: this class holds the parent half of the transaction-reference feature. It performs the
 * browse the list screen performs, the keyed read the inquiry performs, and the write behaviours the
 * baseline distinguishes across its three programs. Its only consumer is
 * {@code com.carddemo.reference.api}, which decides HTTP status and nothing else; this class reads no
 * request shape it was not handed and renders nothing beyond the transfer objects the mapper builds.
 *
 * <h2>Where the behaviour comes from</h2>
 *
 * <p>Assumptions: the authority for the browse is {@code COTRTLIC.cbl}, which pages with two Db2
 * cursors rather than with a CICS file browse, so the cursor declarations and not a
 * {@code STARTBR}/{@code READNEXT} sequence are what the paging transcribes. The authority for the
 * writes is those cursors' program together with {@code COTRTUPC.cbl}, and the two disagree in ways
 * that are preserved rather than reconciled.
 *
 * <p>Assumptions: every baseline line number cited anywhere in this class is a PHYSICAL line, readable
 * with {@code sed -n '343p'} or an equivalent. It is not the six-digit sequence printed in columns 1 to
 * 6. In {@code COTRTLIC.cbl} the two agree only as far as physical line 1807; four sequence values are
 * skipped in the block declaring the record count, after which the printed sequence stands four higher
 * than the physical line for the rest of the file. A citation taken from the printed field therefore
 * names the wrong line in exactly the region this class cites most. The same trap is recorded for test
 * authors at lines 460 to 465 of
 * {@code services/reference-service/src/test/java/com/carddemo/reference/repository/package-info.java},
 * and every citation below was verified positionally against the file.
 *
 * <h2>Why the outcomes are typed rather than flagged</h2>
 *
 * <p>Refactoring Rationale: the baseline cannot tell its own write failures apart, and this class is
 * the re-expression of the mechanism that could not. All three error arms of
 * {@code 9200-UPDATE-RECORD} set the SAME condition {@code CA-UPDATE-REQUESTED} -- at lines 1862, 1871
 * and 1881 -- so a caller inspecting that condition cannot distinguish a row that was absent from a
 * lock that timed out from a statement that failed. In the baseline the displayed sentence is the only
 * thing that separates them. Worse, in {@code COTRTUPC.cbl} the conditions ARE the sentence: the names
 * the classifier tests are 88-levels declared on {@code WS-RETURN-MSG PIC X(75)} at line 167, so
 * {@code SET TABLE-UPDATE-FAILED TO TRUE} at line 1568 writes 'Update of record failed' into the very
 * field that the {@code STRING} on the next line then overwrites, leaving the condition it just set
 * untestable. The same happens to {@code RECORD-DELETE-FAILED} at line 1651, and the reason its
 * {@code WHEN OTHER} arm remains distinguishable at all is that line 1652 additionally sets
 * {@code TTUP-DELETE-FAILED}, which lives in a different field and therefore survives. Typed outcomes
 * and distinct exception classes carry the distinction in something that cannot be overwritten by the
 * next statement.
 *
 * <h2>Which conditions this class can and cannot report</h2>
 *
 * <p>Assumptions: the outcome-to-status mapping is the classifier at lines 1580 to 1589 of
 * {@code COTRTUPC.cbl}, reproduced in {@link WriteOutcome} with its precedence intact. Two properties
 * of that classifier are load-bearing and easy to lose. Its success arm is {@code WHEN OTHER}, so
 * success means only that no error condition was recorded -- line 1556 commits and sets no success
 * flag at all. And its data-changed arm selects {@code TTUP-SHOW-DETAILS} rather than a bare refusal,
 * which is why the conflict this class raises carries the stored version rather than a sentence alone.
 *
 * <p>Assumptions: the 409 and 404 renderings are INHERITED and are not re-declared here.
 * {@code ReferenceApplication} imports {@code GlobalExceptionHandler}, which owns the status, the
 * verbatim sentence and the leak-free fallback for every refusal this class raises. Declaring a second
 * {@code @RestControllerAdvice} anywhere in this module would give one refusal two renderings whose
 * precedence depended on classpath order. For the same reason the sentence a conflict displays is not
 * declared in this class: the optimistic-lock literal is already carried once, in
 * {@code ApiError.COACTUPC_RECORD_CHANGED}, whose own documentation records its 46 characters, its
 * two-word spelling of 'some one' and its absent terminating period.
 *
 * <p>Alternatives Considered: declaring the refusals as exception classes nested in this class. Rejected
 * because the shared kernel already publishes the two this feature needs -- a rejected-input type
 * carrying the per-field array, and a contention type carrying a closed set of three conditions of which
 * this feature uses all three. Nesting local twins would give the module two hierarchies for one
 * concern, and the shared handler matches the shared ones, so the local ones would have to be mapped
 * again to reach the same statuses.
 *
 * <h2>Baseline behaviours recorded rather than reconciled</h2>
 *
 * <p>Alternatives Considered: preserving a caller's pattern metacharacters in the description filter as
 * live wildcards, which is what the baseline does. The baseline composes its own containment pattern at
 * physical lines 1155 to 1162 of {@code COTRTLIC.cbl}, wrapping the trimmed input in two percent signs
 * into a {@code PIC X(52)} field -- two wider than the column, which is what the two wildcards occupy --
 * and passes it to {@code LIKE TRIM(...)} at physical lines 348, 364 and 1812 with no escape clause at
 * any of the three, so a percent sign typed by an operator acts as a wildcard. The migrated query keeps
 * the two surrounding wildcards, so the containment search itself is preserved, but escapes the
 * caller's own metacharacters; the reasoning is recorded on the query that does it, and the divergence
 * is documented in {@code docs/architecture/cobol-to-service-traceability.md}. This class does not
 * re-derive the pattern, so the ruling lives in exactly one place instead of being decided twice with a
 * chance of drifting.
 *
 * <p>Assumptions: three further baseline inconsistencies are load-bearing enough to name and are
 * deliberately not smoothed over. The two programs commit in opposite orders -- {@code COTRTLIC.cbl}
 * commits at physical line 1856 and then sets its completion condition at 1857, while
 * {@code COTRTUPC.cbl} sets its condition at physical line 1636 and commits at 1637. The delete refusal
 * in {@code COTRTUPC.cbl} concatenates the vendor text TWICE, at physical lines 1645 and 1646; the Java
 * records it once. And both update paths write a trimmed description while computing the stored length
 * from the UNTRIMMED value -- at physical lines 1841 to 1844 and 1539 to 1542 respectively -- so the
 * declared length of the stored text includes trailing blanks in both. Each is documented in
 * {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>Assumptions: the two flag families this feature touches carry OPPOSITE polarities and are never
 * treated as one. The filter conditions at physical lines 103 to 110 of {@code COTRTLIC.cbl} are the
 * three-state validation triad in which {@code '1'} is valid and {@code '0'} is not-OK, which is what
 * the cursors test when they compare a flag against {@code '1'}; the database-processing flag at lines
 * 25 to 27 of {@code app/app-transaction-type-db2/cpy/CSDB2RWY.cpy} inverts that, with {@code '0'}
 * meaning healthy and {@code '1'} meaning failed. A third, at physical lines 229 to 231, carries the
 * validation prefix while following neither convention. Only the first is a field-validation state, so
 * only the first is expressed through the shared validation flag.
 *
 * <p>Assumptions: the seed this table is loaded from is the VSAM-lineage text file
 * {@code app/data/ASCII/trantype.txt} -- seven rows in 433 bytes -- and NOT the Db2 control card
 * {@code app/app-transaction-type-db2/ctl/DB2LTTYP.ctl}, which loads the same seven codes in upper case
 * and misspells the description of code 06. The text file spells that description correctly, and the
 * control card's spelling is not carried anywhere. Seven seeded rows is also exactly one page, which is
 * why a browse over seeded data alone reports no further page.
 *
 * <h2>Two limits on what this class can claim</h2>
 *
 * <p>Trade-offs: no value in this class is held in IEEE-754 binary form, and a build gate enforces that.
 * The claim previously made in this position -- that the layering test scopes its subject set to the
 * shared money package and therefore does not inspect this class -- was FALSE and is corrected here.
 * Rule A3 in {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * is scoped to {@code com.carddemo..}, the whole analysed root, and that rule set is executed against
 * every module's own compiled classes, so a {@code float}, {@code double}, {@code Float} or
 * {@code Double} declared here as a field, a parameter or a return type would fail the build. This class
 * has no monetary field in any case, its only numeric state being a version counter and a row count. The
 * cost the original wording was trying to record is real but belongs elsewhere: what A3 cannot see is a
 * binary value that exists only as a local or an intermediate expression, and that residue does rest on
 * review.
 *
 * <p>Assumptions: the parity evidence for this class is transcription against the programs, the table
 * definitions and the host structure, and NOT a byte comparison. The existing COBOL oracle covers the
 * batch chain only; there is no golden master for any online path, so no comparison run can confirm this
 * class the way one confirms a batch job. Every behavioural claim above therefore cites the source line
 * it was read from, so that a reader can check the claim rather than trust it.
 *
 * @see WriteOutcome
 */
@Service
public class TransactionTypeService {

    /**
     * The cursor binding this browse seals its positions under.
     *
     * <p>Assumptions: a binding distinct from every other browse is what stops a position minted for
     * one listing being replayed against another. The sealer carries it inside the seal, so a cursor
     * minted by the category browse and presented here is refused rather than interpreted.
     */
    public static final String CURSOR_BINDING = "reference-transaction-type-list";

    /**
     * The number of rows one page publishes.
     *
     * <p>Assumptions: SEVEN, read from the program rather than counted off a map.
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} declares
     * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7.} at physical line 60 and reads it at
     * physical lines 940, 1004, 1018, 1334, 1386, 1657 and 1736. The repository is asked for one more
     * than this so that the further-page flag comes from a surplus row, which is how the baseline
     * settles it too at physical lines 1657 to 1673.
     *
     * <p>Assumptions: the size is decided here and never travels on a request. The published contract
     * declares no page-size parameter, so accepting one would publish a parameter the document does not
     * describe and would let a caller widen a window the baseline holds at seven.
     */
    public static final int PAGE_SIZE = 7;

    /** The response-field identity of the type-code filter. */
    public static final String FIELD_TYPE_CODE = "typeCode";

    /** The response-field identity of the description filter. */
    public static final String FIELD_DESCRIPTION = "description";

    /**
     * The verbatim refusal when a supplied filter matches no row anywhere in the table.
     *
     * <p>Assumptions: carried character for character from physical line 1264 of
     * {@code COTRTLIC.cbl}, including its capital R, which is what distinguishes it from the
     * lower-case sentence at line 254. Transformation rule T8 makes each literal a contract in its own
     * right, so the two are two constants and never one with a substitution.
     */
    public static final String MESSAGE_NO_RECORDS_FOR_FILTER =
            "No Records found for these filter conditions";

    /** The verbatim refusal when no type carries the code asked for. */
    public static final String MESSAGE_TYPE_NOT_FOUND = "Transaction type NOT found...";

    /**
     * The verbatim sentence the list screen shows when the row it was editing has gone.
     *
     * <p>Assumptions: physical line 1864 of {@code COTRTLIC.cbl}, reproduced with the space before its
     * question mark AND its trailing space, both verified byte for byte. It is retained as a constant
     * even though {@link #MESSAGE_TYPE_NOT_FOUND} is what the published operation returns, because the
     * two sentences answer different questions: this one is the answer to an update whose target
     * vanished between the read and the write, which is the condition the baseline reports at SQLCODE
     * +100 on the update rather than on a read.
     */
    public static final String MESSAGE_RECORD_DELETED_BY_OTHERS =
            "Record not found. Deleted by others ? ";

    /**
     * The verbatim sentence shown when a submitted edit differs from the stored row in nothing.
     *
     * <p>Assumptions: physical lines 261 and 262 of {@code COTRTLIC.cbl}, with its terminating period.
     * {@code COTRTUPC.cbl} carries a DIFFERENT sentence for the same condition at its lines 179 and
     * 180, 'No change detected with respect to values fetched.', and the two are not merged: rule T8
     * makes each the contract of the screen that displays it.
     */
    public static final String MESSAGE_NO_CHANGES_DETECTED =
            "No change detected with respect to database values.";

    /**
     * The SQLSTATE a referencing row raises when a parent delete is refused.
     *
     * <p>Assumptions: this is the migrated form of Db2 SQLCODE -532, which
     * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} provokes at its lines 6 and 7 by declaring
     * the category table's foreign key {@code ON DELETE RESTRICT}. The baseline tests for that SQLCODE
     * at physical line 1914 of {@code COTRTLIC.cbl} and at physical line 1638 of {@code COTRTUPC.cbl}.
     */
    static final String SQLSTATE_FOREIGN_KEY_VIOLATION = "23503";

    /**
     * The SQLSTATE a repeated primary key raises.
     *
     * <p>Assumptions: this is the migrated form of Db2 SQLCODE -803, for which the baseline has no
     * branch anywhere. {@code 9700-INSERT-RECORD} carries only a zero arm at physical line 1605 and a
     * {@code WHEN OTHER} at 1607, so a duplicate key reaches the same arm as any other insert failure
     * and is reported with the same sentence.
     */
    static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /**
     * How many links of a cause chain {@link #sqlStateOf(Throwable)} will follow.
     *
     * <p>Assumptions: a bound rather than an open walk, because a chain that contains a cycle would
     * otherwise not terminate. Eight is past the deepest real chain here, which is the provider
     * exception wrapping the driver exception wrapping the server's own, and short enough that a cyclic
     * chain costs nothing.
     */
    private static final int CAUSE_CHAIN_LIMIT = 8;

    /** Where the diagnosis of a refused write is recorded for an operator. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionTypeService.class);

    /** Access to the transaction-type table. */
    private final TransactionTypeRepository types;

    /** Access to the category table, read only to diagnose a refused delete. */
    private final TransactionCategoryRepository categories;

    /**
     * Builds the service over the two repositories it reads.
     *
     * <p>Assumptions: two collaborators and not three. The mapper this class uses is a final class of
     * static members with a private constructor, a shape its own documentation settles deliberately, so
     * it is called rather than injected; asking for it as a constructor parameter would require it to
     * become a bean and would change a decision another file owns.
     *
     * @param types access to the transaction-type table; must not be {@code null}
     * @param categories access to the category table, read only to diagnose a refused delete; must not
     *     be {@code null}
     */
    public TransactionTypeService(
            TransactionTypeRepository types, TransactionCategoryRepository categories) {
        this.types = types;
        this.categories = categories;
    }

    /**
     * Returns one keyset page of transaction types.
     *
     * <p>This transcribes {@code 8000-READ-FORWARD} at physical line 1603 and
     * {@code 8100-READ-BACKWARDS} at physical line 1727 of {@code COTRTLIC.cbl}, together with the
     * filter aggregate at {@code 9100-CHECK-FILTERS} at physical line 1801 and the cross-edit that
     * consumes it at physical line 1251.
     *
     * <p>Trade-offs: the walk is keyed and never offset, and the concrete failure that buys is worth
     * naming. Under a concurrent insert an offset walk shifts every later row by one, so page two skips
     * the row that moved across its boundary and page three repeats one; a walk bounded by the last key
     * it returned cannot, because the bound is a value in the data and not a distance from the start.
     * What is given up is the ability to jump to an arbitrary page number, which neither this contract
     * nor the baseline offers -- the baseline moves one page at a time on PF7 and PF8 at physical lines
     * 766 to 790.
     *
     * <p>Assumptions: the envelope carries no previous-page flag because the baseline computes none.
     * The forward reader establishes its further-page flag from a surplus read at physical lines 1661 to
     * 1673; the backward reader at physical lines 1749 to 1791 performs no such surplus read, and the
     * string {@code CA-PREV-PAGE} does not occur anywhere in the program. The screen decides whether PF7
     * is available from the page number instead, at physical lines 780 and 781. The absence of a fourth
     * component on the envelope is therefore fidelity to the source rather than an omission.
     *
     * <p>Refactoring Rationale: the position is now sealed against the CALLER, the FILTERS and the
     * DIRECTION as well as against this browse, and a direction stated without a position is refused.
     * Both were defects rather than simplifications, and they are described where the shared assembly
     * applies them, on {@code ReferencePaging.binding} and
     * {@code ReferencePaging.requireCursorForDirection}.</p>
     *
     * @param request the validated paging and filter parameters; must not be {@code null}
     * @param cursorToken the sealer that mints and opens the opaque positions; must not be {@code null}
     * @param subject the authenticated caller's identity, sealed into every position this page mints so
     *     that a position is not transferable between callers; must not be {@code null}
     * @return one page of types with both positions sealed and a flag stating whether more follow,
     *     never {@code null}
     * @throws com.carddemo.common.web.CursorToken.InvalidCursorException if the supplied position is not
     *     one this browse minted, for this caller, under these filters and for this direction
     * @throws ClientInputException if a supplied filter matches no row anywhere in the table, which the
     *     baseline reports as a field refusal on the filter rather than as an empty page, or if a paging
     *     direction arrives without the position it would move from
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionTypeResponse> list(
            TransactionTypeListRequest request, CursorToken cursorToken, String subject) {

        ReferencePaging.requireCursorForDirection(request.cursor(), request.direction());

        boolean backward = request.direction() == PageDirection.PREVIOUS;
        Limit limit = Limit.of(PAGE_SIZE + 1);

        // WHY : Assumptions: both filters are normalised through the repository's own helpers rather
        //       than here, so that an absent value, a blank value and a value bearing a pattern
        //       metacharacter mean the same thing at the query as the published contract says they mean
        //       at the boundary. Normalising in two places would let the two drift apart silently.
        String typeCodeFilter = TransactionTypeRepository.typeCodeFilter(request.typeCode());
        String descriptionFilter =
                TransactionTypeRepository.descriptionFilterPattern(request.description());
        boolean filtered = typeCodeFilter != null || descriptionFilter != null;

        // WHY : Assumptions: the NORMALISED filters are what the binding names, not the values as the
        //       caller typed them. Two spellings that normalise to one query -- an absent value and a
        //       blank one -- select the same rows, so binding the raw forms would refuse a position for a
        //       difference the walk itself cannot see.
        String position = ReferencePaging.openPosition(cursorToken, CURSOR_BINDING, subject,
                request.cursor(), request.direction(), typeCodeFilter, descriptionFilter);

        if (filtered) {
            requireFilterMatchesSomething(typeCodeFilter, descriptionFilter, request);
        }

        // WHY : Assumptions: a backward walk needs a position to compare against, and there is no such
        //       thing as a first page read backward -- a first page is read forward by definition. The
        //       baseline reaches its backward reader only from PF7 and only when the page number is not
        //       the first, at physical lines 780 and 781, so a backward request without a position is
        //       answered as a first page rather than refused.
        List<TransactionType> rows = backward && position != null
                ? readBackwardWindow(typeCodeFilter, descriptionFilter, position, filtered, limit)
                : readForwardWindow(typeCodeFilter, descriptionFilter, position, filtered, limit);

        return ReferencePaging.page(rows, PAGE_SIZE, backward, position != null,
                ReferencePaging.binding(CURSOR_BINDING, subject, true, typeCodeFilter,
                        descriptionFilter),
                ReferencePaging.binding(CURSOR_BINDING, subject, false, typeCodeFilter,
                        descriptionFilter),
                cursorToken, TransactionTypeMapper::toResponse, TransactionType::getTypeCd);
    }

    /**
     * Reads the window at or after a position, ascending.
     *
     * <p>This is {@code 8000-READ-FORWARD} at physical lines 1603 to 1723 of {@code COTRTLIC.cbl}.
     *
     * <p>Refactoring Rationale: the seek is STRICTLY greater than the position where the baseline's
     * cursor is greater than or equal to it, and the two agree only because a second baseline mechanism
     * is transcribed away at the same time. Physical line 343 declares
     * {@code WHERE TR_TYPE >= :WS-START-KEY}, which taken alone would re-read the row the caller
     * already holds. It does not, because of what physical line 1673 does: having stored the last
     * DISPLAYED row's key at physical line 1659, the reader performs one more fetch at physical lines
     * 1661 to 1665 and then OVERWRITES the stored key with that surplus row's key. The value the screen
     * carries forward, and hands back at physical lines 768 and 769, is therefore already the NEXT
     * page's first row, which is exactly what an inclusive seek needs. The inclusive predicate and the
     * overwrite are one mechanism and neither is correct alone. The shared envelope fixes the other
     * convention -- its last key is the key of the last row actually published, never the surplus row --
     * so the equivalent seek against it is the strict one. The row sequence is identical: no row is
     * repeated and none is skipped. Only the structure changed, which is what transformation rule T9
     * permits and what rule T8 leaves untouched.
     *
     * @param typeCodeFilter the normalised type-code filter, or {@code null} when absent
     * @param descriptionFilter the normalised description pattern, or {@code null} when absent
     * @param position the key last published, or {@code null} for a first page
     * @param filtered whether either filter is present, which selects the narrowed query
     * @param limit the row bound, one greater than the page size; must not be {@code null}
     * @return the rows in ascending key order, at most one more than the page size, never {@code null}
     */
    private List<TransactionType> readForwardWindow(String typeCodeFilter, String descriptionFilter,
            String position, boolean filtered, Limit limit) {

        if (filtered) {
            return this.types.findFilteredPageAfter(
                    typeCodeFilter, descriptionFilter, position, limit);
        }
        if (position == null) {
            return this.types.findAllByOrderByTypeCdAsc(limit);
        }
        // WHY : Trade-offs: the unfiltered walks are kept as their own arm rather than every page being
        //       routed through the narrowed query with two null arguments. The narrowed query carries
        //       three optional arms the database evaluates per row for a browse that never narrows, and
        //       the baseline has the same two-path shape for the same reason -- its cursor arms are
        //       guarded by the filter flags at physical lines 344 to 350 precisely so that an unset
        //       filter costs nothing. What is given up is one extra branch to read here.
        return this.types.findByTypeCdGreaterThanOrderByTypeCdAsc(position, limit);
    }

    /**
     * Reads the window before a position and returns it ascending.
     *
     * <p>This is {@code 8100-READ-BACKWARDS} at physical lines 1727 to 1799 of {@code COTRTLIC.cbl},
     * whose label carries the plural spelling.
     *
     * <p>Assumptions: this direction needs NO divergence from the baseline predicate, and the proof is
     * two lines. Physical line 1731 primes the seek with
     * {@code MOVE WS-CA-FIRST-TTYPEKEY TO WS-CA-LAST-TTYPEKEY}, so the value compared against is the
     * CURRENT page's first key -- which the screen supplies from that same field at physical lines 782
     * and 783 -- and physical line 359 declares {@code WHERE TR_TYPE < :WS-START-KEY}, already strictly
     * less than it. That is precisely the shared envelope's first-key convention, so the strict
     * comparison transcribes directly.
     *
     * <p>Assumptions: the descending walk is reversed here rather than being read ascending. The rows
     * wanted are the ones NEAREST the position, so the bound has to be applied while walking away from
     * it; an ascending walk bounded the same way would return the rows FURTHEST from the position, which
     * is a different page. Reversing in memory is safe only because the walk was bounded first, so the
     * list being reversed is at most one longer than a page.
     *
     * <p>Assumptions: the baseline does not reach a clean end of set in this direction. Its loop at
     * physical lines 1749 to 1791 has a zero arm and a {@code WHEN OTHER} arm and no
     * {@code WHEN SQLCODE = +100} arm at all, so running off the START of the table falls into
     * {@code WHEN OTHER} at physical line 1777, sets {@code WS-DB2-ERROR} at physical line 1781 and
     * composes a database error message at physical line 1784. The Java returns the rows that exist,
     * which for a position at or before the first key is an empty window; the divergence is documented
     * in {@code docs/architecture/cobol-to-service-traceability.md}.
     *
     * @param typeCodeFilter the normalised type-code filter, or {@code null} when absent
     * @param descriptionFilter the normalised description pattern, or {@code null} when absent
     * @param position the first key of the page the caller currently holds; must not be {@code null}
     * @param filtered whether either filter is present, which selects the narrowed query
     * @param limit the row bound, one greater than the page size; must not be {@code null}
     * @return the rows in ascending key order, at most one more than the page size, never {@code null}
     */
    private List<TransactionType> readBackwardWindow(String typeCodeFilter, String descriptionFilter,
            String position, boolean filtered, Limit limit) {

        List<TransactionType> descending = filtered
                ? new ArrayList<>(this.types.findFilteredPageBefore(
                        typeCodeFilter, descriptionFilter, position, limit))
                : new ArrayList<>(
                        this.types.findByTypeCdLessThanOrderByTypeCdDesc(position, limit));
        Collections.reverse(descending);
        return descending;
    }

    /**
     * Refuses a filter that matches no row anywhere in the table.
     *
     * <p>This is {@code 1290-CROSS-EDITS} at physical lines 1239 to 1267 of {@code COTRTLIC.cbl}, which
     * runs only when at least one filter is valid, performs the aggregate at
     * {@code 9100-CHECK-FILTERS}, and on a zero count sets {@code INPUT-ERROR} at physical line 1252,
     * marks each SUPPLIED filter not-OK at physical lines 1253 to 1259 and displays the sentence at
     * physical line 1264.
     *
     * <p>Assumptions: the aggregate paragraph itself does not test its own result. Physical line 1820
     * simply continues on a zero SQLCODE, so it validates the QUERY and not the count; the count is
     * tested one level up at physical line 1251. The aggregate also carries no comparison against the
     * cursor position, so it answers whether the filter matches anything ANYWHERE rather than anything
     * further on.
     *
     * <p>Assumptions: this is a field refusal and not an empty page, and the distinction matters to a
     * caller. An empty page says only that nothing further lies in the direction asked for, which
     * invites paging onward; this says the filter can never match, which does not. Reporting the first
     * as the second would leave a caller paging forever.
     *
     * <p>Assumptions: only the filters the caller actually supplied are named, matching the two guarded
     * assignments at physical lines 1253 to 1259. Naming a filter the caller left empty would mark a
     * control it never filled in.
     *
     * <p>Alternatives Considered: reporting a failure of the aggregate itself as this same field
     * refusal, which is what the baseline does -- physical line 1823 sets {@code INPUT-ERROR} on ANY
     * non-zero SQLCODE, so a database error and a mistyped filter are reported identically. That is not
     * reproduced: a failing count query is not something a caller can correct, so it is left to
     * propagate and be answered as a server fault. The divergence is documented in
     * {@code docs/architecture/cobol-to-service-traceability.md}.
     *
     * @param typeCodeFilter the normalised type-code filter, or {@code null} when absent
     * @param descriptionFilter the normalised description pattern, or {@code null} when absent
     * @param request the submitted request, read only to name the filters it carried; must not be
     *     {@code null}
     * @throws ClientInputException if no row in the table satisfies the supplied filters
     */
    private void requireFilterMatchesSomething(String typeCodeFilter, String descriptionFilter,
            TransactionTypeListRequest request) {

        if (this.types.countFilterMatches(typeCodeFilter, descriptionFilter) > 0) {
            return;
        }

        List<String> offending = new ArrayList<>(2);
        if (typeCodeFilter != null) {
            offending.add(FIELD_TYPE_CODE);
        }
        if (descriptionFilter != null) {
            offending.add(FIELD_DESCRIPTION);
        }
        throw new ClientInputException(ApiError.CODE_VALIDATION, List.copyOf(offending),
                FieldValidationFlag.NOT_OK, MESSAGE_NO_RECORDS_FOR_FILTER);
    }

    /**
     * Reads one transaction type by its code.
     *
     * <p>Assumptions: this is the keyed read the traceability matrix records against the inquiry path.
     * It is named for the published operation it serves rather than for the column it reads, because the
     * web adapter and the contract document both name it that way and a second name for one single-row
     * read would be surface with no behaviour of its own.
     *
     * @param typeCd the two-character code to read
     * @return the type as the contract publishes it, never {@code null}
     * @throws NoSuchElementException carrying the verbatim refusal when no row holds that code
     */
    @Transactional(readOnly = true)
    public TransactionTypeResponse read(String typeCd) {
        return TransactionTypeMapper.toResponse(require(typeCd));
    }

    /**
     * Adds a transaction type, refusing a code that already exists.
     *
     * <p>This transcribes {@code 9700-INSERT-RECORD} at physical lines 1596 to 1623 of
     * {@code COTRTUPC.cbl}.
     *
     * <p>Refactoring Rationale: the duplicate is classified and the baseline cannot classify it. That
     * paragraph carries only a zero arm at physical line 1605 and a {@code WHEN OTHER} at physical line
     * 1607, with no arm for the duplicate-key SQLCODE, so a repeated primary key is reported with the
     * same 'Error inserting record into:' sentence as a tablespace failure. Because the shared handler
     * renders every integrity violation with the referential sentence, leaving it unclassified here
     * would tell a caller who reused a code to go and delete child records. Reading the SQLSTATE and
     * raising the contention kind that matches is what makes the two reportable apart.
     *
     * <p>Trade-offs: the existence read before the insert is kept even though the constraint would
     * refuse the row anyway. It answers the ordinary case with one clean refusal instead of a rolled-back
     * statement, and it costs one keyed read on a table of seven rows. It is NOT the authority: two
     * callers inserting the same code concurrently can both find nothing, so the unique constraint has
     * to remain the thing that actually decides, which is why the insert is guarded as well as preceded.
     * Both routes raise the same refusal, so a caller cannot tell which one refused it -- and should not
     * be able to.
     *
     * @param request the validated create body; must not be {@code null}
     * @return the stored type as the contract publishes it, never {@code null}
     * @throws RecordConflictException when a type already carries that code, whether that is found by
     *     the read below or by the unique constraint
     * @throws DataIntegrityViolationException when the constraint that refused the insert reports a
     *     state that is neither of the two this service classifies, in which case the violation is
     *     propagated unchanged so that the inherited mapping answers it
     */
    @Transactional
    public TransactionTypeResponse create(TransactionTypeCreateRequest request) {
        if (this.types.findByTypeCd(request.typeCd()).isPresent()) {
            // WHY : Refactoring Rationale: the kind raised is REFERENCED_ROW and it used to be
            //       STALE_VERSION. The published contract states what a caller receives here: creating a
            //       code that already exists "is refused with 409 ... which the shared advice reaches
            //       through the same integrity branch as a restricted delete and therefore answers with
            //       the referential sentence the Conflict response gives", and that consequence is
            //       registered as D-REFERENCE-INTEGRITY-SENTENCE. STALE_VERSION renders a DIFFERENT
            //       sentence -- the before-image data-changed wording -- so the pre-read refusal
            //       contradicted both the document and its own registered divergence, and told a caller
            //       that someone else had edited a row it was trying to create.
            // WHY : Assumptions: the same kind is raised by the constraint path below, so a duplicate
            //       found by this read and a duplicate found by the unique constraint are
            //       indistinguishable to a caller. Answering the raced one differently would make the
            //       answer depend on timing.
            throw new RecordConflictException(RecordConflictException.Kind.REFERENCED_ROW);
        }
        TransactionType candidate = TransactionTypeMapper.toNewEntity(request);
        try {
            // WHY : Refactoring Rationale: an explicit INSERT, where this called save. Neither save nor
            //       saveAndFlush can insert this entity, and that was MEASURED rather than reasoned:
            //       TransactionType carries an assigned String identifier and a primitive long @Version,
            //       so the framework's newness test finds a non-null version and a non-null identifier
            //       and routes every save through EntityManager.merge. Merge loads the row that
            //       identifier names and writes an UPDATE against it, so a create for an existing code
            //       raised an optimistic-locking failure where the stored version differed -- and would
            //       have silently REPLACED the existing description, answering success, wherever the
            //       stored version happened to match the zero on the new instance. Either way the catch
            //       below could not run, so the duplicate classification the published contract depends
            //       on was unreachable for the one condition it exists to answer.
            // WHY : Assumptions: a native INSERT issues its statement immediately rather than at flush,
            //       so the unique-constraint violation arrives INSIDE this try. That is what the catch
            //       needs; a statement deferred to commit would be wrapped by the framework's own
            //       transaction advice and reach the shared handler unclassified.
            this.types.insertType(candidate.getTypeCd(), candidate.getDescription());
            return TransactionTypeMapper.toResponse(candidate);
        } catch (DataIntegrityViolationException failure) {
            throw classifyIntegrityViolation(failure);
        }
    }

    /**
     * Replaces the description of an existing type, reporting a miss rather than inserting.
     *
     * <p>This transcribes {@code 9200-UPDATE-RECORD} at physical lines 1837 to 1894 of
     * {@code COTRTLIC.cbl}: the same statement the maintenance screen issues, but reporting not found on
     * SQLCODE +100 at physical lines 1861 to 1869 instead of inserting. Only the description is written,
     * because that is the only column the baseline {@code UPDATE} sets, at physical lines 1846 to 1850.
     *
     * <p>Assumptions: this is the strict behaviour and it must never become an insert. The maintenance
     * screen's insert-on-miss at physical lines 1558 to 1560 of {@code COTRTUPC.cbl} is a third
     * behaviour that this contract deliberately does not expose, registered as
     * {@code D-REFERENCE-UPSERT-NOT-EXPOSED} in
     * {@code docs/architecture/cobol-to-service-traceability.md} and reasoned out in this package's own
     * {@code package-info}. Making this method insert would make the published 404 unreachable, so a
     * caller who mistyped a code would silently create a type instead of being told the code is unknown.
     *
     * <p>Assumptions: the conflict carries the stored version and not merely a sentence, because the
     * baseline's classifier answers this condition with {@code TTUP-SHOW-DETAILS} at physical line 1586
     * rather than with a bare refusal -- that is, it puts the CURRENT state back on the screen. A refusal
     * that named no version would leave a caller with nothing to retry against, so it would have to
     * re-read and could lose the same race again.
     *
     * <p>Assumptions: the version is compared before anything is written, so a stale write performs no
     * statement at all. The provider's own optimistic check would also refuse it on flush, but its
     * refusal cannot report WHICH revision won, and the whole point of the baseline's before-image is
     * that the row is not held locked across a caller's thinking time.
     *
     * @param typeCd the code of the type to replace
     * @param request the validated replace body carrying the new description and the version read
     * @return the stored type as the contract publishes it, never {@code null}
     * @throws NoSuchElementException carrying the verbatim refusal when no row holds that code
     * @throws RecordConflictException carrying the stored version when the version supplied is stale,
     *     naming the lock condition when the row could not be locked, or naming the referential
     *     condition when the write violated a constraint
     * @throws DataIntegrityViolationException when the constraint that refused the write reports a state
     *     that is neither of the two this service classifies, in which case the violation is propagated
     *     unchanged so that the inherited mapping answers it
     */
    @Transactional
    public TransactionTypeResponse replace(String typeCd, TransactionTypeUpdateRequest request) {
        TransactionType stored = require(typeCd);

        // WHY : Refactoring Rationale: the version precondition is evaluated BEFORE the no-change
        //       short-circuit, where the two used to be the other way round. The published contract makes
        //       the precondition unconditional -- "when the stored row has moved on since that read the
        //       write is refused with 409" -- so a submission carrying a stale token had to be refused
        //       whatever its description said. With the short-circuit first, a caller holding a token
        //       from before somebody else's edit received 200 and the CURRENT row, which reads as
        //       confirmation that its own submission was applied to the state it had read. Both facts it
        //       would infer from that are false: its token was stale, and the row it was handed is not
        //       the row it based the submission on.
        // WHY : Trade-offs: the case this reorders is narrow -- a stale token whose description happens
        //       to equal the stored one -- and the previous ordering was argued from the baseline, on the
        //       ground that a write altering no column can lose no update. That is true of the WRITE and
        //       beside the point for the ANSWER: the baseline reaches its own no-change sentence only
        //       after its before-image comparison has already passed, so evaluating the precondition
        //       first is the baseline's order rather than a departure from it.
        if (stored.getVersion() != request.version()) {
            throw new RecordConflictException(
                    RecordConflictException.Kind.STALE_VERSION, stored.getVersion());
        }

        // WHY : Alternatives Considered: reporting a submission that differs in nothing as the
        //       data-changed conflict, which would have folded two baseline outcomes into one. Rejected
        //       because the baseline keeps them apart and answers them differently: physical lines 743
        //       to 750 of COTRTUPC.cbl run the comparison and, when nothing differs, leave the edit path
        //       WITHOUT reaching the write at all, displaying the sentence at physical lines 179 and 180.
        //       'You changed nothing' is an idempotent no-op; 'someone else changed it' is a conflict.
        if (describesSameStoredValues(stored, request.description())) {
            return TransactionTypeMapper.toResponse(stored);
        }
        boolean lockUnavailable = false;
        DataIntegrityViolationException integrityFailure = null;
        TransactionType saved = null;

        try {
            TransactionTypeMapper.applyUpdate(request, stored);
            // WHY : Refactoring Rationale: saveAndFlush and not save, because the two catches below are
            //       the point of this try. A plain save on a MANAGED row registers the change with the
            //       persistence context and issues the UPDATE when the context is flushed, which for a
            //       transactional method is at commit -- after this method and its catches have returned.
            //       A lock timeout or a constraint refusal would then surface from the transaction's
            //       commit rather than from this statement, so neither catch could run and both of the
            //       classifications this method publishes were unreachable for the conditions they were
            //       written for. Flushing here issues the statement inside the try.
            saved = this.types.saveAndFlush(stored);
        } catch (CannotAcquireLockException failure) {
            // WHY : Assumptions: this is the migrated form of the SQLCODE -911 arm at physical lines
            //       1870 to 1879, which the baseline reaches on a deadlock or a lock timeout. It is
            //       caught apart from the integrity failure below because the baseline reports the
            //       two apart, and a caller told the wrong one retries the wrong way: a lock
            //       timeout should be retried unchanged, while a version that lost must be re-read
            //       first.
            lockUnavailable = true;
        } catch (DataIntegrityViolationException failure) {
            integrityFailure = failure;
        }

        // WHY : Assumptions: the outcome is resolved AFTER the statement rather than before it, which is
        //       where the baseline resolves it too -- its classifier at physical lines 1580 to 1589 runs
        //       below the statement's own EVALUATE, so success is the absence of a recorded error rather
        //       than a flag the successful arm sets.
        // WHY : Refactoring Rationale: the data-changed argument is now the constant false, because the
        //       stale-version precondition is evaluated and thrown BEFORE this write rather than being
        //       carried past it as a flag. The classifier keeps its data-changed arm because it
        //       transcribes the baseline's own precedence order, which the enum documents; what changed is
        //       that this call site can no longer reach that arm, and the wrapping "if not changed"
        //       guard around the write is gone with it rather than left as a condition that is always
        //       true.
        return switch (resolveWriteOutcome(lockUnavailable, integrityFailure != null, false)) {
            case LOCK_ERROR ->
                    throw new RecordConflictException(RecordConflictException.Kind.LOCK_UNAVAILABLE);
            case UPDATE_FAILED -> throw classifyIntegrityViolation(integrityFailure);
            case DATA_CHANGED -> throw new RecordConflictException(
                    RecordConflictException.Kind.STALE_VERSION, stored.getVersion());
            case OK -> TransactionTypeMapper.toResponse(saved);
        };
    }

    /**
     * Deletes a transaction type, leaving the referential refusal to the constraint.
     *
     * <p>This transcribes {@code 9300-DELETE-RECORD} at physical lines 1896 to 1938 of
     * {@code COTRTLIC.cbl} and {@code 9800-DELETE-PROCESSING} at physical lines 1624 to 1666 of
     * {@code COTRTUPC.cbl}. Neither has an arm for a row that is already gone -- the first has no
     * SQLCODE +100 branch at all, so deleting an absent row falls into its {@code WHEN OTHER} at physical
     * line 1926, the one arm that sets no completion flag whatsoever. The keyed read below reports that
     * condition instead.
     *
     * <p>Trade-offs: the child-row count is read and acted on, and it is still NOT the authority. The
     * migration declares the category table's foreign key {@code ON DELETE RESTRICT}, transcribing lines
     * 6 and 7 of {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} and preserving the relationship
     * the baseline indexes as {@code CARDDEMO.X_TRAN_TYPE_CATG}; that constraint is what actually
     * decides, because two callers deleting a type and inserting a category concurrently can each read a
     * count of zero. Reading it first buys a clean refusal in the ordinary case instead of a rolled-back
     * statement; the guarded delete is what makes the refusal correct under a race. The constraint is
     * never dropped, deferred or weakened to make this method simpler.
     *
     * @param typeCd the code of the type to delete
     * @throws NoSuchElementException carrying the verbatim refusal when no row holds that code
     * @throws RecordConflictException when categories still reference the type, whether that is found by
     *     the count below or by the foreign key itself
     * @throws DataIntegrityViolationException when the constraint that refused the delete reports a state
     *     that is neither of the two this service classifies, in which case the violation is propagated
     *     unchanged so that the inherited mapping answers it
     */
    @Transactional
    public void delete(String typeCd) {
        TransactionType stored = require(typeCd);

        long referencing = this.categories.countByTypeCd(typeCd);
        if (referencing > 0) {
            LOG.warn("event=reference.type.delete-restricted typeCd={} referencingCategories={}",
                    typeCd, referencing);
            throw new RecordConflictException(RecordConflictException.Kind.REFERENCED_ROW);
        }

        try {
            this.types.delete(stored);
            // WHY : Refactoring Rationale: the delete is FLUSHED inside the try, because the catch below
            //       is the point of it. A delete only marks the row for removal in the persistence
            //       context; the DELETE statement is issued when the context is flushed, which for a
            //       transactional method is at commit -- after this catch has returned. A category row
            //       inserted against this type between the count above and this statement would then
            //       violate the foreign key at commit rather than here, so the referential
            //       classification was unreachable on exactly the race it exists to answer. There is no
            //       deleteAndFlush, so the flush is issued explicitly.
            this.types.flush();
        } catch (DataIntegrityViolationException failure) {
            throw classifyIntegrityViolation(failure);
        }
    }

    /**
     * Reads a type or raises the verbatim refusal.
     *
     * @param typeCd the code to read
     * @return the stored row, never {@code null}
     * @throws NoSuchElementException carrying the verbatim refusal when no row holds that code
     */
    private TransactionType require(String typeCd) {
        Optional<TransactionType> found = this.types.findByTypeCd(typeCd);
        if (found.isEmpty()) {
            throw new NoSuchElementException(MESSAGE_TYPE_NOT_FOUND);
        }
        return found.get();
    }

    /**
     * Answers whether a submitted description differs from the stored row in nothing.
     *
     * <p>This is {@code 1205-COMPARE-OLD-NEW} at physical lines 783 to 814 of {@code COTRTUPC.cbl}. That
     * paragraph defaults to no-change at physical line 784 and compares the before-image against the
     * submission with {@code FUNCTION UPPER-CASE} applied over {@code FUNCTION TRIM} on both sides, at
     * physical lines 786 to 797.
     *
     * <p>Assumptions: the comparison this class needs had to be written rather than transcribed from the
     * classifier, because the condition the classifier tests is never set. In {@code COTRTUPC.cbl}
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} occurs exactly twice -- declared at physical line 183 and
     * tested at physical line 1585 -- and at no point is it set, so that arm cannot be reached. The
     * program's real comparison is this paragraph, working on a separate one-character flag declared at
     * physical lines 78 to 80.
     *
     * <p>Trade-offs: the comparison is case-insensitive because the baseline's is, and the consequence
     * is worth stating plainly: a submission that changes only letter case is treated as no change and
     * is not written. That is the baseline's behaviour and it is preserved rather than improved, so a
     * caller wanting a case-only correction cannot obtain one through this operation. Comparing
     * case-sensitively would have been a behavioural change with no baseline authority behind it.
     *
     * <p>Assumptions: only the description is compared. The code cannot differ, because it is the key
     * this row was just read by, and the entity declares it not updatable; the baseline compares it at
     * physical lines 786 to 789 only because its screen carries a before-image of a key the operator
     * could have retyped.
     *
     * <p>Alternatives Considered: normalising both sides the way the baseline's {@code FUNCTION TRIM}
     * does, which removes leading as well as trailing blanks. Rejected in favour of normalising with the
     * SAME member that decides what gets stored, which removes trailing blanks only because this package
     * has settled that a leading blank in a description is content rather than padding. The two readings
     * differ on exactly one submission -- a description whose only change is a leading blank -- and this
     * one answers it correctly for a question about whether a WRITE would change anything: comparing
     * under a normalisation other than the one the write applies could report no change and then store a
     * different value.
     *
     * @param stored the row as the database currently holds it; must not be {@code null}
     * @param submitted the description the caller submitted, compared on its normalised value
     * @return {@code true} when the submission would alter no column, {@code false} otherwise
     */
    private static boolean describesSameStoredValues(TransactionType stored, String submitted) {
        // WHY : Refactoring Rationale: both values are stripped on BOTH ends before comparison, where
        //       they were previously put through the STORAGE normalisation, which removes trailing
        //       blanks only. The two are separate decisions and the baseline makes them separately: it
        //       stores through a trim whose behaviour differs across its three writers, but every
        //       description COMPARISON it makes goes through a trim -- COTRTLIC.cbl L1065 and L1069,
        //       COTRTUPC.cbl L791 and L795 -- and a bare FUNCTION TRIM removes blanks from both ends. So
        //       the baseline treats surrounding blanks as insignificant for equality, and reusing the
        //       storage rule here made a submission differing only in leading blanks look like a CHANGE,
        //       which then went to the database as a write the baseline would not have made.
        // WHY : Assumptions: this narrows the divergence rather than widening it. The stored form still
        //       keeps a leading blank -- registered as D-REFERENCE-TRIM-TRAILING-ONLY, because the
        //       baseline is genuinely three-way inconsistent about storage and no one rule can match all
        //       three writers -- but equality now behaves as all three of them do.
        String storedText = strippedForComparison(stored.getDescription());
        String submittedText = strippedForComparison(submitted);
        // WHY : Assumptions: neither value can be absent on the published path -- the column is declared
        //       not null and the request component is constrained non-blank -- so this arm exists only so
        //       that an internal caller cannot turn a missing value into a thrown exception from a
        //       comparison. Two absent values describe the same state; one absent value does not.
        if (storedText == null || submittedText == null) {
            return storedText == null && submittedText == null;
        }
        return storedText.toUpperCase(Locale.ROOT).equals(submittedText.toUpperCase(Locale.ROOT));
    }

    /**
     * Removes blanks from both ends of a description before it is compared for equality.
     *
     * <p>Purpose: this is the target form of the baseline's own comparison discipline. Every description
     * comparison the baseline makes passes its operands through {@code FUNCTION TRIM} with no
     * {@code LEADING} or {@code TRAILING} operand, which strips both ends -- at
     * {@code COTRTLIC.cbl} L1065 and L1069, and at {@code COTRTUPC.cbl} L791 and L795.</p>
     *
     * <p>Assumptions: this is deliberately NOT the storage normalisation. Storage keeps a leading blank,
     * for the reason registered as {@code D-REFERENCE-TRIM-TRAILING-ONLY}; equality ignores blanks at
     * both ends, because that is what the baseline does when it asks whether two descriptions are the
     * same. Using one member for both would force one of the two answers to be wrong.</p>
     *
     * <p>Assumptions: {@code strip} is used rather than {@code trim}, because it removes every Unicode
     * whitespace character rather than only the code points at or below the space. A description arrives
     * from a caller rather than from a fixed-width record, so a non-breaking space is a value it can
     * actually carry, and treating one as content here would report two descriptions a reader cannot
     * tell apart as different.</p>
     *
     * @param value the description as stored or as submitted, which may be {@code null}
     * @return the value with surrounding whitespace removed, or {@code null} when the value is absent
     */
    private static String strippedForComparison(String value) {
        return value == null ? null : value.strip();
    }

    /**
     * Resolves which write outcome a set of recorded conditions selects.
     *
     * <p>This is the classifier at physical lines 1580 to 1589 of {@code COTRTUPC.cbl}, reproduced with
     * its precedence intact: the lock condition wins over the failed statement, which wins over the
     * changed row, and success is what remains.
     *
     * <p>Assumptions: the ORDER is the contract and not an implementation detail, because it is what
     * decides the answer when two conditions hold at once. Testing the failed statement first would
     * report a lock timeout as a server fault, and a caller would stop retrying something it should have
     * retried unchanged.
     *
     * <p>Assumptions: success is the {@code WHEN OTHER} arm at physical lines 1587 and 1588 rather than a
     * condition the successful path sets. Physical line 1556 commits and sets nothing, so 'no error was
     * recorded' IS the success condition. Writing this as a positive test for success would invent a
     * signal the baseline does not have.
     *
     * @param lockUnavailable whether the row could not be locked, the migrated SQLCODE -911 condition
     * @param updateFailed whether the statement itself failed on a constraint
     * @param dataChanged whether the before-image comparison found the row altered underneath
     * @return the single outcome those conditions select, never {@code null}
     */
    private static WriteOutcome resolveWriteOutcome(
            boolean lockUnavailable, boolean updateFailed, boolean dataChanged) {

        if (lockUnavailable) {
            return WriteOutcome.LOCK_ERROR;
        }
        if (updateFailed) {
            return WriteOutcome.UPDATE_FAILED;
        }
        if (dataChanged) {
            return WriteOutcome.DATA_CHANGED;
        }
        return WriteOutcome.OK;
    }

    /**
     * Turns an integrity violation into the contention refusal its SQLSTATE names.
     *
     * <p>Refactoring Rationale: the two conditions are separated here because the baseline separates
     * neither, and because the shared handler cannot separate them either. That handler recognises an
     * integrity violation by class name and answers every one of them with the referential sentence, so
     * an unclassified duplicate key would be reported to a caller as a child-row problem. The baseline's
     * insert has no duplicate-key arm to transcribe -- physical lines 1604 to 1619 of
     * {@code COTRTUPC.cbl} carry only a zero arm and a catch-all -- so the classification is written
     * from the constraint that raised it rather than copied from a branch that does not exist. The
     * divergence is documented in {@code docs/architecture/cobol-to-service-traceability.md}.
     *
     * <p>Refactoring Rationale: what reaches the log is the SQLSTATE and this method's own
     * classification of it, and NOT the provider's message text. The text was logged, and it is the one
     * value on this path that cannot be bounded: a provider composes it, so its content is the
     * provider's choice rather than this system's, and for a constraint violation it characteristically
     * carries the constraint's name together with the BOUND VALUES that violated it -- which on these
     * tables are a transaction type code and, through the referencing constraint, category rows. The
     * migration's logging contract admits a type or category code, a status code, a date, a version
     * counter and a bounded response code, and it admits them because each is a value this system
     * declared and can enumerate. Provider text is none of those things, and a diagnostic whose content
     * is decided elsewhere cannot be reviewed here.
     *
     * <p>Assumptions: nothing needed for triage is lost with it. The two conditions this method
     * distinguishes are distinguished BY the SQLSTATE, so the SQLSTATE plus the classification names the
     * condition exactly; and for the third case -- a state neither constant matches -- the SQLSTATE is
     * the value an operator looks up, which is precisely why it is logged rather than the sentence built
     * around it. Alternatives Considered: keeping the text at debug level on the argument that debug is
     * off in production. Rejected because a level is configuration, not a control: raising it is a
     * one-line change an operator makes while diagnosing exactly the failure that emits the value, so
     * the value would be written at the moment it was most likely to be captured. Also considered:
     * logging the provider's exception CLASS name in its place, which is stable and carries no value;
     * it is not added because the SQLSTATE already identifies the condition more precisely than the
     * class does.
     *
     * <p>Assumptions: no detail reaches a response body either, and the baseline itself is the authority
     * for that. Its own composition ran through {@code CSDB2RPY.cpy}, which builds the message into
     * {@code WS-LONG-MSG PIC X(800)} (declared at physical line 235 of {@code COTRTLIC.cbl}) and then,
     * at line 84 of that copybook, moves the result into a {@code PIC X(75)} field -- discarding 725
     * bytes. Because the vendor text is concatenated LAST, that truncation removes precisely the vendor
     * detail and keeps the prefix, which is why the sentence at physical line 1883 reads 'Update failed
     * with' and simply stops. Its one path that emits the whole 800-byte buffer is
     * {@code SEND-LONG-TEXT} at physical line 2085, and the comment above it at physical lines 2082 and
     * 2083 states that it is primarily for debugging and should not be used in the regular course; it
     * also ends in a bare {@code EXEC CICS RETURN} with no communication area, which abandons the
     * conversation. The caller therefore receives the stable sentence and nothing else.
     *
     * @param failure the violation the provider raised; must not be {@code null}
     * @return the refusal to throw: the contention kind the SQLSTATE names, or the original violation
     *     when the SQLSTATE is neither of the two, so that the inherited mapping still answers it
     */
    private static RuntimeException classifyIntegrityViolation(
            DataIntegrityViolationException failure) {

        String sqlState = sqlStateOf(failure);

        // WHY : Refactoring Rationale: the log line carries the SQLSTATE and a type-and-frame digest, and
        //       no longer the provider's own most-specific-cause text. That text is composed by the
        //       driver and QUOTES the values that violated the constraint -- PostgreSQL appends a detail
        //       clause naming the key columns and the offending key -- so the earlier form copied caller
        //       data into log storage, which is the one destination the masking applied at the API edge
        //       does not reach. This service's own key is a two-character code, but the helper is reached
        //       from three write paths and the hazard is a property of the driver's message rather than
        //       of this table.
        // WHY : Assumptions: nothing an operator acts on is lost. The SQLSTATE names WHICH constraint
        //       class refused the statement, which is what selects the branch below and what an alert
        //       rule matches on, and the digest names the exception chain and the frames that raised it.
        //       The constraint NAME would be the one remaining useful detail, and it is not extracted
        //       because reaching it means parsing the same message this line exists to stop carrying.
        // WHY : Trade-offs: the previous wording defended the vendor text on the ground that the
        //       baseline's own 800-byte buffer truncated it away and that restoring it for an operator
        //       displaced nothing. That reasoning held for the CALLER channel, which is unchanged and
        //       still receives only the stable sentence, and it did not hold for the log channel, where
        //       the value persists and is searchable. The two-channel arrangement is kept; what changed
        //       is that the operator channel now carries provenance rather than content.
        LOG.debug("event=reference.type.integrity-violation sqlState={} failure={}",
                sqlState, ThrowableDigest.of(failure));

        if (SQLSTATE_UNIQUE_VIOLATION.equals(sqlState)) {
            LOG.warn("event=reference.type.duplicate-key sqlState={}", sqlState);
            // WHY : Refactoring Rationale: REFERENCED_ROW, where this returned STALE_VERSION. The two
            //       kinds render different sentences -- the before-image data-changed wording and the
            //       referential wording -- and the published contract states which one a duplicate
            //       create receives: the referential sentence, reached "through the same integrity branch
            //       as a restricted delete", with the consequence registered as
            //       D-REFERENCE-INTEGRITY-SENTENCE. Returning the data-changed kind reported a duplicate
            //       primary key as somebody else's concurrent edit.
            // WHY : Assumptions: the SAME refusal the sequential existence read raises, so a duplicate
            //       caught by the constraint and a duplicate caught by the read are indistinguishable to
            //       a caller. Answering the raced one differently would make the answer depend on
            //       timing.
            return new RecordConflictException(RecordConflictException.Kind.REFERENCED_ROW);
        }
        if (SQLSTATE_FOREIGN_KEY_VIOLATION.equals(sqlState)) {
            LOG.warn("event=reference.type.referenced-row sqlState={}", sqlState);
            return new RecordConflictException(RecordConflictException.Kind.REFERENCED_ROW);
        }

        // WHY : Trade-offs: an unrecognised SQLSTATE is handed back unchanged rather than being
        //       reclassified into whichever of the two looks closest. The inherited mapping already
        //       answers an integrity violation, so returning it preserves exactly the answer this
        //       service gave before any classification existed, and adds no new behaviour on a path
        //       whose cause is not understood.
        LOG.warn("event=reference.type.unclassified-integrity-violation sqlState={}", sqlState);
        return failure;
    }

    /**
     * Finds the SQLSTATE the database reported, by walking the cause chain.
     *
     * <p>Alternatives Considered: reading the state from a provider-specific exception type, or from the
     * subclass the data-access layer raises for a duplicate key. Both were rejected as narrower than the
     * condition being classified: the first ties this class to the persistence provider it happens to
     * run on, and the second only recognises one of the two states that matter here. The driver
     * exception carries the state itself, so the chain is walked for the one interface that is part of
     * the platform and reports it directly.
     *
     * @param failure the exception to inspect; may be {@code null}
     * @return the first non-blank SQLSTATE found in the chain, or {@code null} when the chain reports
     *     none within the bound
     */
    private static String sqlStateOf(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < CAUSE_CHAIN_LIMIT; depth++) {
            if (current instanceof SQLException reported) {
                String state = reported.getSQLState();
                if (state != null && !state.isBlank()) {
                    return state;
                }
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * The outcomes a write can reach, in the precedence the baseline classifier declares.
     *
     * <p>Assumptions: this enum IS the outcome-to-status table, and the order the constants are declared
     * in is the order the classifier at physical lines 1580 to 1589 of {@code COTRTUPC.cbl} tests them
     * in. It is declared as a closed set rather than carried as a boolean because a boolean cannot hold
     * four states, and because the condition it replaces could not hold them either: all three error
     * arms of {@code 9200-UPDATE-RECORD} set one shared condition, at physical lines 1862, 1871 and
     * 1881.
     *
     * <p>Assumptions: the status each outcome reaches is decided by the shared handler that
     * {@code ReferenceApplication} imports, not here. The statuses named below record which answer each
     * outcome is expected to produce so that a reader can check the chain end to end; they are not a
     * second mapping.
     */
    public enum WriteOutcome {

        /**
         * The row could not be locked, so nothing was compared and nothing was written.
         *
         * <p>Assumptions: the migrated form of {@code COULD-NOT-LOCK-REC-FOR-UPDATE}, selected at
         * physical lines 1581 and 1582, which the baseline reaches from SQLCODE -911. Answered as 409,
         * because it is contention a caller may resolve by retrying unchanged.
         */
        LOCK_ERROR,

        /**
         * The statement itself was refused by a constraint.
         *
         * <p>Assumptions: the migrated form of {@code TABLE-UPDATE-FAILED}, selected at physical lines
         * 1583 and 1584. Answered as 409 when the SQLSTATE names a condition a caller can act on, and
         * otherwise left to the inherited mapping.
         */
        UPDATE_FAILED,

        /**
         * The row was altered underneath the caller between the read and the write.
         *
         * <p>Assumptions: the migrated form of the arm selecting {@code TTUP-SHOW-DETAILS} at physical
         * lines 1585 and 1586. Answered as 409 carrying the current state, because that arm puts the
         * stored row back on the screen rather than only refusing.
         */
        DATA_CHANGED,

        /**
         * No error condition was recorded, which is what success means here.
         *
         * <p>Assumptions: the {@code WHEN OTHER} arm at physical lines 1587 and 1588. Answered as 200.
         */
        OK
    }
}
