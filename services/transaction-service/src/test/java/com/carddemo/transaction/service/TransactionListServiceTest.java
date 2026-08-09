package com.carddemo.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.money.Money;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.TransactionListItemResponse;
import com.carddemo.transaction.dto.TransactionListRequest;
import com.carddemo.transaction.mapper.TransactionMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

/**
 * Pins the "Transaction List" keyset browse against the paging paragraphs of {@code COTRN00C}.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link TransactionListService} transcribes {@code app/cbl/COTRN00C.cbl}, 699 lines, transaction
 * {@code CT00}, named "Transaction List" in the inventory table of the repository root
 * {@code README.md} at line 298. This class holds that transcription to the four properties that
 * decide whether a browse is faithful: that a forward step reads STRICTLY past the trailing key in
 * ascending order, that a backward step reads STRICTLY before the leading key in descending order and
 * is then re-ordered for display, that forward availability is answered by one surplus row which is
 * then DISCARDED, and that a row inserted into the middle of the key space while a browse is in
 * flight is neither skipped nor served twice.</p>
 *
 * <p>Assumptions: the screen name is taken from the inventory row rather than paraphrased. The
 * plausible mis-citation is "Transaction Browse", which reads naturally beside a paging endpoint and
 * appears nowhere in that table.</p>
 *
 * <p>Assumptions: the inventory row is cited at line 298 as the tree stands. The pristine baseline
 * carried the same row twenty lines earlier, at line 278, because the migration section this plan adds
 * to {@code README.md} moved it down. Both figures are recorded so that a reader meeting two citations
 * of one table reads a shift with a known cause. The verifiable claim is the row content -- {@code CT00
 * | COTRN00 | COTRN00C | Transaction List} -- and the line number is the convenience. Every other
 * citation below names a file under {@code app/}, which is reference-only and therefore stable.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This type is a test class: no caller constructs
 * it, it yields no value and it raises nothing outside the test engine, so the type itself accepts no
 * parameter, returns nothing and throws nothing. The inapplicability is stated rather than passed over,
 * because the user-specified Explainability rule forbids a docstring that omits parameters, return
 * values or purpose, and a reader has to be able to tell a declared inapplicability from an oversight.
 * Every member below carries its own at-clauses.</p>
 *
 * <h2>Alternatives Considered: the collaborator that is mocked, and the two that are real</h2>
 *
 * <p>Alternatives Considered: mocking BOTH collaborators, which is the shape the sibling detail-screen
 * class uses and the conventional choice for a service test. Rejected here for one specific reason:
 * the reversal a backward scan needs and the construction of the envelope itself are
 * {@link TransactionMapper} methods, not service methods -- the service delegates to
 * {@code orderForDisplay} and {@code toListPage}. A mocked mapper would therefore answer with whatever
 * page this class told it to answer with, and the two load-bearing assertions -- that a descending scan
 * comes back ascending, and that eleven scanned rows yield ten with a further page -- would become
 * restatements of this class's own stubbing rather than observations of the code under test. The
 * repository is mocked because the finder SELECTION is the service's decision and a call record is the
 * only way to see which finder was consulted; the mapper and the cursor sealer are real because the
 * values this class asserts on are theirs to produce.</p>
 *
 * <p>Alternatives Considered: a hand-written stub over {@link TransactionRepository}, reached through a
 * reflective proxy, on the grounds that the mock framework's inline maker attaches an agent to the
 * running virtual machine and the machine reports that attachment on standard error. The attachment
 * warning is real and was measured. It was rejected as a reason all the same, because it is already
 * emitted by six other test classes in this very module -- including the detail-screen class this one
 * is modelled on -- so declining the framework here removes the warning from nothing and leaves the
 * build emitting it regardless. What a proxy would add is roughly a hundred lines of dispatch that the
 * compiler cannot check: a finder renamed on the interface would leave a stub keyed on a name nothing
 * calls, which then has to be bought back with a reflective name-existence case. Naming the finders
 * directly gets that check from the compiler instead.</p>
 *
 * <p>Assumptions: the sealer is a real {@link CursorToken} over synthetic key material. The envelope's
 * two boundaries are opaque tokens by contract, so the only way to assert WHICH row a boundary names is
 * to open the token the service sealed. It is required by the operation's signature and so cannot be
 * omitted.</p>
 *
 * <p>Assumptions: the repository's identifier type is {@link String} and not a numeric type. The
 * entity's identity attribute is the {@code String} member {@code tranId}, because {@code TRAN-ID PIC
 * X(16)} at line 5 of {@code app/cpy/CVTRA05Y.cpy} is a sixteen-character display field. A stub written
 * against a numeric identifier would assert a read the production code cannot perform.</p>
 *
 * <h2>Assumptions: this cursor is ONE scalar, and the neighbouring screen's is not</h2>
 *
 * <p>Assumptions: the browse cursor of this screen is a single sixteen-character scalar, established
 * three ways in the reference. {@code RIDFLD (TRAN-ID)} at line 595 of {@code app/cbl/COTRN00C.cbl}
 * names one field as the record identification field, {@code KEYLENGTH (LENGTH OF TRAN-ID)} at line 596
 * takes the key length from that same one field, and the browse block copied in at lines 61 to 70
 * carries exactly two key members for it -- {@code CDEMO-CT00-TRNID-FIRST PIC X(16)} at line 63 and
 * {@code CDEMO-CT00-TRNID-LAST PIC X(16)} at line 64.</p>
 *
 * <p>Assumptions: the card-list screen is deliberately NOT the model for any of this. Its cursor is a
 * twenty-seven byte COMPOSITE -- {@code WS-CA-LAST-CARD-NUM PIC X(16)} at line 231 of
 * {@code app/cbl/COCRDLIC.cbl} followed by {@code WS-CA-LAST-CARD-ACCT-ID PIC 9(11)} at line 232, under
 * {@code WS-CA-LAST-CARDKEY} at line 230 -- its page holds seven rows rather than ten, and it carries a
 * page-shown flag at lines 239 to 241 whose polarity is inverted against every reading instinct
 * ({@code VALUE 0} means shown, {@code VALUE 9} means not shown), a field this target drops. Assuming a
 * composite key or a seven-row page here would produce a class that passes while asserting the wrong
 * screen's contract, which is why the difference is written down rather than left to be noticed.</p>
 *
 * <h2>Assumptions: NO golden master covers this program</h2>
 *
 * <p>Assumptions: no golden master exists for this path and none is claimed. {@code tests/README.md}
 * lines 83 to 85 record that the online {@code CO*} programs cannot be run end to end without a CICS
 * runtime, which the runner does not have, so only their extractable validation logic is unit-tested.
 * {@code COTRN00C} is one of those programs. Parity therefore rests on two things and is asserted as
 * such: paging and validation logic transcribed branch by branch with each branch cited to its line,
 * and the copybook record contracts. No assertion below is justified by pointing at a committed output
 * file, and nothing here creates, regenerates or reads anything under the oracle's fixture or golden
 * trees.</p>
 *
 * <p>Assumptions: the graded condition-code rubric the oracle aggregates belongs to that suite alone.
 * This class runs under a gate that is binary -- a case either passes or fails -- so nothing below
 * tolerates a degree of failure, and no case here is wired into the oracle's pipeline.</p>
 *
 * <p>Assumptions: the reference program opens no transaction boundary and none is asserted. Across all
 * 699 lines of {@code app/cbl/COTRN00C.cbl} the count of {@code SYNCPOINT} is zero, as is the count of
 * {@code EXEC CICS WRITE} and of {@code REWRITE}: the program reads and never writes, so it needs no
 * commit scope, and the atomicity it does have is the implicit one at task end. A case citing a
 * syncpoint line in this program would be citing a line that does not exist.</p>
 *
 * <h2>Assumptions: determinism is supplied, never read</h2>
 *
 * <p>Assumptions: no case below reads an ambient clock. The one timestamp a list row carries is
 * supplied as a parsed literal, so a rerun compares the same bytes. That literal is the ORIGINATING
 * stamp and not the processing stamp, which are two different members -- {@code TRAN-ORIG-TS PIC X(26)}
 * at line 16 of {@code app/cpy/CVTRA05Y.cpy} and {@code TRAN-PROC-TS PIC X(26)} at line 17 -- and they
 * genuinely differ in the nightly program, where line 436 of {@code app/cbl/CBTRN02C.cbl} moves the
 * originating stamp across from the feed while lines 437 to 438 derive the processing stamp from a
 * clock. Nothing here coerces one form into the other. Each case builds its own rows and its own
 * stand-in, sharing no mutable state, which is what keeps the class safe to run in parallel.</p>
 *
 * <p>Assumptions: nothing here re-declares a shared contract. The money invariant comes from
 * {@link Money}, the envelope from {@link PageResponse}, the cursor sealing from {@link CursorToken},
 * and the record layout from {@link Transaction} and {@link TransactionListItemResponse}. That is the
 * direction {@code tests/README.md} lines 540 to 542 states for the parity oracle's own copybook
 * layouts, applied to this tree.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the list screen: keyset paging over the ordered set, and the boundaries it seals")
class TransactionListServiceTest {

    /**
     * Synthetic key material for the sealer, at the minimum width it accepts.
     *
     * <p>Assumptions: this is test-only material and is not a credential of any environment. It is a
     * literal rather than a generated value so that a sealed token is reproducible within one run, and
     * it is plainly synthetic so that no reader mistakes it for something to protect.</p>
     */
    private static final byte[] CURSOR_KEY =
            "carddemo-transaction-list-test-key-material".getBytes(StandardCharsets.US_ASCII);

    /**
     * The authenticated principal every case produces its pages for.
     *
     * <p>Assumptions: one subject throughout, because the properties asserted here are the page
     * arithmetic and the boundary identifiers, and both are independent of who asked. The subject is
     * folded into the cursor binding, so sealing, opening and listing all have to agree on it.</p>
     */
    private static final String SUBJECT = "keyset-paging-subject";

    /** The originating stamp every row below carries, supplied as a literal rather than read. */
    private static final LocalDateTime ORIGIN_TS = LocalDateTime.parse("2022-06-10T19:27:53");

    /** The amount every row below carries, within the nine integer digits the record declares. */
    private static final BigDecimal AMOUNT = new BigDecimal("504.77");

    /** The card number every row below carries, a synthetic sixteen-digit value. */
    private static final String CARD_NUMBER = "4000123456789010";

    /** The ordered reader of the posted-transaction table, standing in for the reference dataset. */
    @Mock
    private TransactionRepository repository;

    /** The real converter, which owns the display ordering and the envelope construction. */
    private TransactionMapper transactionMapper;

    /** The real sealer the service mints and this class opens boundary tokens with. */
    private CursorToken cursorToken;

    /** The unit under test, rebuilt over a fresh stand-in before each case. */
    private TransactionListService service;

    /**
     * Builds a mapper, a sealer and a service over the fresh stand-in before every case.
     *
     * <p>Assumptions: the service is rebuilt rather than shared because it is constructed around its
     * two collaborators, and a service retained across cases would hold the previous case's stubbing.
     * The class under test keeps no mutable instance state of its own, so rebuilding it costs
     * nothing.</p>
     */
    @BeforeEach
    void setUp() {
        this.transactionMapper = new TransactionMapper();
        this.cursorToken = new CursorToken(CURSOR_KEY, Duration.ofMinutes(15));
        this.service = new TransactionListService(this.repository, this.transactionMapper);
    }

    /**
     * Renders the sixteen-digit identifier of one ordinal.
     *
     * <p>Assumptions: the identifiers are zero-padded to the width the key column declares, so that
     * ascending lexicographic order and ascending ordinal order coincide. That coincidence is the
     * property every ordering assertion below relies on; an unpadded value would sort by length first
     * and each of those assertions would then be measuring something else.</p>
     *
     * @param ordinal the one-based position of the row within the ordered set, of type {@code int}
     * @return the identifier at exactly sixteen digits, never {@code null}
     */
    private static String tranId(int ordinal) {
        return String.format("%016d", ordinal);
    }

    /**
     * Builds one stored row whose identifier encodes its ordinal, so an assertion can name it.
     *
     * <p>Assumptions: all thirteen mapped members are populated and the amount is given nine integer
     * digits or fewer, because {@code TRAN-AMT PIC S9(09)V99} at line 10 of
     * {@code app/cpy/CVTRA05Y.cpy} is the domain the entity normalises against. The
     * {@code FILLER PIC X(20)} of line 18 is absent because the entity drops it.</p>
     *
     * @param ordinal the one-based position of the row within the ordered set, of type {@code int}
     * @return a {@link Transaction} carrying the thirteen members the record contract declares
     */
    private static Transaction row(int ordinal) {
        return new Transaction(
                tranId(ordinal),
                "01",
                "0001",
                "POS TERM",
                "Purchase at Abshire-Lowe",
                AMOUNT,
                800000000L,
                "Abshire-Lowe",
                "North Enoshaven",
                "72112",
                CARD_NUMBER,
                ORIGIN_TS,
                ORIGIN_TS);
    }

    /**
     * Builds an inclusive run of rows in ascending identifier order.
     *
     * @param from the first ordinal to build, one-based, of type {@code int}
     * @param to the last ordinal to build, one-based and not below {@code from}, of type {@code int}
     * @return the rows in ascending identifier order, never {@code null}
     */
    private static List<Transaction> rows(int from, int to) {
        List<Transaction> built = new ArrayList<>(to - from + 1);
        for (int ordinal = from; ordinal <= to; ordinal++) {
            built.add(row(ordinal));
        }
        return List.copyOf(built);
    }

    /**
     * Reverses a run of rows, giving the order a descending query hands them back in.
     *
     * <p>Assumptions: this exists so that a backward case can answer in the order its query DECLARES
     * rather than in the order the page is displayed in. Handing ascending rows to a descending finder
     * would hide the reversal the service performs, which is the one thing that case asserts.</p>
     *
     * @param ascending the rows in ascending identifier order, of type {@code List<Transaction>};
     *     must not be {@code null}
     * @return the same rows in descending identifier order, never {@code null}
     */
    private static List<Transaction> descending(List<Transaction> ascending) {
        List<Transaction> reversed = new ArrayList<>(ascending);
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    /**
     * Builds a list request from its three components.
     *
     * @param transactionIdFilter the positioning identifier, of type {@code String}, or {@code null}
     *     for an opening scan over the whole ordered set
     * @param cursor the sealed cursor, of type {@code String}, or {@code null} when the caller holds
     *     no page yet
     * @param direction the requested direction, of type {@code TransactionListRequest.Direction}, or
     *     {@code null} to accept the forward default
     * @return the request, never {@code null}
     */
    private static TransactionListRequest request(String transactionIdFilter, String cursor,
            TransactionListRequest.Direction direction) {
        return new TransactionListRequest(transactionIdFilter, cursor, direction);
    }

    /**
     * Composes the cursor binding exactly as the service under test composes it.
     *
     * <p>Assumptions: the binding is rebuilt here from the same published parts rather than read from
     * an assembled constant, because the service derives it per call from its query name and the
     * calling subject and publishes no assembled form. Restating the parts is what keeps this class
     * sealing tokens the service will accept.</p>
     *
     * @return the binding the service seals and opens boundary tokens under, never {@code null}
     */
    private static String binding() {
        return CursorToken.binding(TransactionListService.CURSOR_QUERY_NAME, SUBJECT,
                CursorToken.SCOPE_NONE);
    }

    /**
     * Seals a raw identifier into a cursor bound as the service binds them.
     *
     * @param rawKey the raw identifier to seal, of type {@code String}; must not be {@code null}
     * @return the sealed cursor, never {@code null}
     */
    private String seal(String rawKey) {
        return this.cursorToken.seal(binding(), rawKey);
    }

    /**
     * Opens a boundary token the service sealed, so an assertion can name the row it identifies.
     *
     * @param token the sealed boundary token to open, of type {@code String}; must not be
     *     {@code null}
     * @return the raw identifier the token carries, never {@code null}
     */
    private String openBoundary(String token) {
        return this.cursorToken.open(binding(), token);
    }

    /**
     * Runs one request through the service under test.
     *
     * @param listRequest the list request to answer, of type {@code TransactionListRequest}; must not
     *     be {@code null}
     * @return the page envelope the service assembled, never {@code null}
     */
    private PageResponse<TransactionListItemResponse> list(TransactionListRequest listRequest) {
        return this.service.listTransactions(listRequest, this.cursorToken, SUBJECT);
    }

    /**
     * Collects the identifiers a page carries, in the order the page carries them.
     *
     * @param page the page envelope to read, of type
     *     {@code PageResponse<TransactionListItemResponse>}; must not be {@code null}
     * @return the identifiers in page order, never {@code null}
     */
    private static List<String> idsOf(PageResponse<TransactionListItemResponse> page) {
        return page.items().stream().map(TransactionListItemResponse::transactionId).toList();
    }

    /**
     * Names the row cap every unpositioned paging query is issued with.
     *
     * <p>Assumptions: the cap is the page size plus one and the surplus row is the whole mechanism by
     * which forward availability is answered, so it is named here once and asserted by identity at
     * each call site rather than restated as a literal eleven.</p>
     *
     * @return the {@link Limit} carrying one row beyond the page, never {@code null}
     */
    private static Limit probeBoundedLimit() {
        return Limit.of(TransactionMapper.PAGE_SIZE + 1);
    }

    /**
     * An opening request with no positioning identifier reads the whole ordered set ascending.
     *
     * <p>This pins the entry arm of {@code PROCESS-ENTER-KEY}, line 146 of
     * {@code app/cbl/COTRN00C.cbl}, at its blank-filter branch: line 206 tests the keyed-in identifier
     * for spaces or low values and line 207 then moves {@code LOW-VALUES} into the record
     * identification field, which is the lowest point of the collating sequence and therefore a
     * request to begin at the start of the key space. The read that follows is
     * {@code READNEXT-TRANSACT-FILE} at line 624, issued from the fill loop at lines 297 to 303.</p>
     *
     * <p>Assumptions: the assertion is on the CALL RECORD and not only on the rows returned, because
     * two finders can answer identically for one fixture and only the record distinguishes which was
     * consulted. A service that reached the forward finder with a sentinel key would return these same
     * three rows and would fail here, which is the point.</p>
     *
     * <p>Assumptions: the row cap is asserted to be the page size plus one on this path too. The
     * reference reaches its surplus read at line 308 whether or not the caller supplied a starting
     * identifier, so an opening request that asked for exactly ten rows could never answer forward
     * availability at all.</p>
     */
    @Test
    @DisplayName("an opening request with no identifier reads the ordered set ascending, capped at eleven")
    void anOpeningRequestReadsTheOrderedSetAscending() {
        when(repository.findAllByOrderByTranIdAsc(probeBoundedLimit())).thenReturn(rows(1, 3));

        PageResponse<TransactionListItemResponse> page = list(request(null, null, null));

        assertThat(idsOf(page))
                .as("the opening page is the start of the key space, in ascending identifier order")
                .containsExactly(tranId(1), tranId(2), tranId(3));
        verify(repository).findAllByOrderByTranIdAsc(probeBoundedLimit());
        verifyNoMoreInteractions(repository);
    }

    /**
     * A forward step reads STRICTLY past the trailing key, ascending, and never re-reads that key.
     *
     * <p>This pins {@code PROCESS-PAGE-FORWARD}, lines 279 to 332 of {@code app/cbl/COTRN00C.cbl}:
     * line 281 positions the browse, line 295 seeds the row index at one, line 297 loops until the
     * index reaches eleven or the set is exhausted, line 298 performs
     * {@code READNEXT-TRANSACT-FILE} at line 624, line 300 populates the row and line 301 advances the
     * index. It also pins the seek at lines 259 to 263, where a stored trailing key is moved into the
     * record identification field before the browse is positioned on it.</p>
     *
     * <p>Alternatives Considered: reading INCLUSIVELY from the trailing key -- the {@code >=} form --
     * which is the reading a browse positioned on a stored key would ordinarily suggest, and which is
     * what the reference would do had one line been left active. Line 597 of
     * {@code app/cbl/COTRN00C.cbl} is {@code GTEQ}, COMMENTED OUT, inside the browse-positioning block
     * at lines 593 to 600; it is the only occurrence of that option anywhere in the program. With it
     * disabled the browse positions on an EXACT key and the first read then moves off it, so the
     * observable semantics are strictly greater than, not greater than or equal to. The inclusive form
     * was therefore rejected: it would serve the caller's last row again as the first row of the next
     * page, which is a duplicate the reference never produces.</p>
     *
     * <p>Assumptions: the argument recorded is the RAW identifier the cursor carried and not the sealed
     * token, which is also how this case shows the service opens the token before querying. A query
     * issued with the token itself would match no row in any real engine and would surface as an empty
     * page rather than as an error.</p>
     */
    @Test
    @DisplayName("a forward step reads strictly past the trailing key in ascending order")
    void aForwardStepReadsStrictlyPastTheTrailingKeyAscending() {
        when(repository.findByTranIdGreaterThanOrderByTranIdAsc(tranId(4), probeBoundedLimit()))
                .thenReturn(rows(5, 7));

        PageResponse<TransactionListItemResponse> page =
                list(request(null, seal(tranId(4)), TransactionListRequest.Direction.NEXT));

        assertThat(idsOf(page))
                .as("the page begins after the cursor key and never repeats it")
                .containsExactly(tranId(5), tranId(6), tranId(7))
                .doesNotContain(tranId(4));
        assertThat(idsOf(page))
                .as("a forward page is served in ascending identifier order")
                .isSorted();
        verify(repository)
                .findByTranIdGreaterThanOrderByTranIdAsc(tranId(4), probeBoundedLimit());
        verifyNoMoreInteractions(repository);
    }

    /**
     * A backward step reads STRICTLY before the leading key descending, then serves it ascending.
     *
     * <p>This pins {@code PROCESS-PAGE-BACKWARD}, lines 333 to 380 of {@code app/cbl/COTRN00C.cbl}:
     * line 335 positions the browse, line 349 seeds the row index at TEN, line 351 loops until the
     * index falls to zero or the set is exhausted, line 352 performs
     * {@code READPREV-TRANSACT-FILE} at line 658, line 354 populates the row and line 355 DECREMENTS
     * the index. It also pins the seek at lines 236 to 240, where a stored leading key is moved into
     * the record identification field.</p>
     *
     * <p>Refactoring Rationale: the reversal is not an embellishment, it is what the reference already
     * does, expressed differently. Because line 349 starts the index at ten and line 355 counts it
     * down, the FIRST backward read -- the highest identifier below the cursor -- lands in the LAST
     * screen slot and the last backward read lands in the first, so the array the reference sends is
     * ascending by identifier even though every read was descending. A target that returned the query
     * order untouched would render the page upside down against a screen that never did, so the
     * descending query result is re-ordered before it is served.</p>
     *
     * <p>Assumptions: the stand-in answers in the order the descending query DECLARES, so the page
     * coming back ascending is the reversal happening rather than an artefact of the fixture. A page
     * that came back descending would be the reversal missing, and that is the failure this case
     * exists to catch.</p>
     *
     * <p>Assumptions: the surplus row of a backward scan is the LOWEST identifier read, so it is
     * asserted absent from the page. That pins the trim as happening at the far end of the scan in
     * this direction too, rather than at the end of the displayed page, which would drop the wrong
     * row and shift the leading boundary by one.</p>
     *
     * <p>Refactoring Rationale: forward availability is asserted here, and its omission was the
     * defect this paragraph records. A backward step has by definition come from somewhere ahead of
     * it, so a further page forward always exists and the service reports it UNCONDITIONALLY for this
     * direction rather than from the surplus row. Every other case in this class asserts availability,
     * and this was the one direction where it was not asserted at all -- so the one branch that does
     * not read the probe row was the branch with no case over its result. A companion case below
     * drives the same direction with a SHORT scan, where the probe row is absent, precisely so that
     * deriving availability from the probe would fail there rather than pass here by coincidence.</p>
     */
    @Test
    @DisplayName("a backward step reads descending and serves the page in ascending display order")
    void aBackwardStepReadsDescendingAndServesAscending() {
        when(repository.findByTranIdLessThanOrderByTranIdDesc(
                tranId(TransactionMapper.PAGE_SIZE + 2), probeBoundedLimit()))
                .thenReturn(descending(rows(1, TransactionMapper.PAGE_SIZE + 1)));

        PageResponse<TransactionListItemResponse> page =
                list(request(null, seal(tranId(TransactionMapper.PAGE_SIZE + 2)),
                        TransactionListRequest.Direction.PREVIOUS));

        assertThat(idsOf(page))
                .as("a descending scan is re-ordered ascending before it is served")
                .isSorted();
        assertThat(idsOf(page))
                .as("the lowest identifier read is the surplus row and is trimmed away")
                .hasSize(TransactionMapper.PAGE_SIZE)
                .doesNotContain(tranId(1))
                .startsWith(tranId(2))
                .endsWith(tranId(TransactionMapper.PAGE_SIZE + 1));
        assertThat(openBoundary(page.firstKey()))
                .as("the leading boundary names the lowest identifier the page actually carries")
                .isEqualTo(tranId(2));
        assertThat(openBoundary(page.lastKey()))
                .as("the trailing boundary names the highest identifier the page actually carries")
                .isEqualTo(tranId(TransactionMapper.PAGE_SIZE + 1));
        assertThat(page.hasNext())
                .as("a backward step came from a page ahead of it, so a further page forward exists")
                .isTrue();
        verify(repository).findByTranIdLessThanOrderByTranIdDesc(
                tranId(TransactionMapper.PAGE_SIZE + 2), probeBoundedLimit());
        verifyNoMoreInteractions(repository);
    }

    /**
     * A SHORT backward step still reports a further page forward, though it read no surplus row.
     *
     * <p>This pins the same availability arm of {@code PROCESS-PAGE-BACKWARD} as the case above, lines
     * 358 to 370 of {@code app/cbl/COTRN00C.cbl}, on the path where the backward scan exhausts the set
     * before it fills the page. Line 360 issues the further backward read, and the end-of-set arm at
     * lines 362 to 366 records that no further page exists BACKWARD -- it says nothing about forward,
     * because line 339 has already established that the caller arrived from a page ahead.</p>
     *
     * <p>Refactoring Rationale: this case exists to make the previous one falsifiable rather than to
     * add a second reading of the same rule. Forward availability is derived from the surplus row for
     * every other direction, and a page that scanned a full eleven rows reports a further page under
     * either rule -- the correct unconditional one and the incorrect probe-derived one -- so asserting
     * it on a full backward page alone cannot tell the two apart. A backward scan of fewer than eleven
     * rows has NO surplus row, so the probe-derived rule would answer false here and the unconditional
     * rule answers true. That divergence is the whole content of this case.</p>
     *
     * <p>Assumptions: the scan is arranged three rows short of a page rather than one, so the page is
     * unambiguously partial and the absence of the surplus row cannot be read as a trim. The rows are
     * the lowest identifiers in the set, which is what a backward walk that has reached the start of
     * the set returns.</p>
     */
    @Test
    @DisplayName("a short backward step reports a further page forward despite reading no probe row")
    void aShortBackwardStepStillReportsAFurtherPageForward() {
        int shortScanSize = TransactionMapper.PAGE_SIZE - 3;
        when(repository.findByTranIdLessThanOrderByTranIdDesc(
                tranId(shortScanSize + 1), probeBoundedLimit()))
                .thenReturn(descending(rows(1, shortScanSize)));

        PageResponse<TransactionListItemResponse> page =
                list(request(null, seal(tranId(shortScanSize + 1)),
                        TransactionListRequest.Direction.PREVIOUS));

        assertThat(idsOf(page))
                .as("every row read is served, because a short scan carries no surplus row to trim")
                .hasSize(shortScanSize)
                .isSorted()
                .startsWith(tranId(1))
                .endsWith(tranId(shortScanSize));
        assertThat(page.hasNext())
                .as("availability forward is unconditional for a backward step and is NOT derived "
                        + "from the surplus row, which this scan does not hold")
                .isTrue();
        verify(repository).findByTranIdLessThanOrderByTranIdDesc(
                tranId(shortScanSize + 1), probeBoundedLimit());
        verifyNoMoreInteractions(repository);
    }

    /**
     * Eleven rows scanned yield ten served, a further page reported, and the surplus row discarded.
     *
     * <p>This pins the availability arm of {@code PROCESS-PAGE-FORWARD}, lines 305 to 315 of
     * {@code app/cbl/COTRN00C.cbl}: the fill loop having ended, lines 306 to 307 advance the page
     * ordinal, line 308 issues an ELEVENTH {@code READNEXT} purely as a lookahead, line 310 records
     * that a further page exists when that read succeeded and line 312 records that none does when it
     * did not, with the exhausted-set fallback at lines 314 to 315. Line 322 then ends the browse.</p>
     *
     * <p>Assumptions: the surplus row NEVER reaches {@code POPULATE-TRAN-DATA} at line 381. The
     * populating paragraph is performed only from inside the fill loop, at line 300 forward and line
     * 354 backward; the read at line 308 is followed at line 309 by a test of the end-of-set condition
     * and by nothing else. So the eleventh row informs a boolean and is then thrown away, which is why
     * the page is asserted to hold exactly ten and to exclude the eleventh identifier by name.</p>
     *
     * <p>Alternatives Considered: sealing the trailing boundary from the SURPLUS row rather than from
     * the last row served. This is not hypothetical -- it is what the neighbouring card-list screen
     * does. At lines 1207 to 1214 of {@code app/cbl/COCRDLIC.cbl}, on a normal or duplicate response
     * at lines 1208 to 1209, that program records the existence of a further page at lines 1210 to
     * 1211 and then OVERWRITES its stored trailing key with the probe row's own key, moving the
     * account identifier at lines 1212 to 1213 and the card number at line 1214 -- a record the client
     * never saw. It was rejected because the two reference programs disagree and this is the list
     * screen: {@code COTRN00C} keeps only the boolean and discards the row, so the boundary must name
     * the tenth row. Sealing from the eleventh would still produce a well-formed token naming a real
     * record, so the next forward step would silently begin one row too late and that row would never
     * be served to anyone. The boundary is therefore asserted to equal the tenth identifier AND to
     * differ from the eleventh, because the first assertion alone would pass on a fixture where the
     * two happened to coincide.</p>
     */
    @Test
    @DisplayName("eleven rows scanned yield ten served, a further page, and a boundary at the tenth")
    void elevenRowsScannedYieldTenServedWithTheSurplusRowDiscarded() {
        when(repository.findAllByOrderByTranIdAsc(probeBoundedLimit()))
                .thenReturn(rows(1, TransactionMapper.PAGE_SIZE + 1));

        PageResponse<TransactionListItemResponse> page = list(request(null, null, null));

        assertThat(page.items())
                .as("the surplus row informs the further-page answer and is not served")
                .hasSize(TransactionMapper.PAGE_SIZE);
        assertThat(idsOf(page))
                .as("the eleventh identifier read never appears on the page")
                .doesNotContain(tranId(TransactionMapper.PAGE_SIZE + 1));
        assertThat(page.hasNext())
                .as("a surplus row having been read is what reports a further page")
                .isTrue();
        assertThat(openBoundary(page.firstKey()))
                .as("the leading boundary names the first row served")
                .isEqualTo(tranId(1));
        assertThat(openBoundary(page.lastKey()))
                .as("the trailing boundary names the tenth row served, never the eleventh read")
                .isEqualTo(tranId(TransactionMapper.PAGE_SIZE))
                .isNotEqualTo(tranId(TransactionMapper.PAGE_SIZE + 1));
    }

    /**
     * A scan returning exactly one page reports no further page and keeps every row it read.
     *
     * <p>This pins the negative arm of the same availability block, line 312 of
     * {@code app/cbl/COTRN00C.cbl}, reached when the lookahead read at line 308 finds nothing, and the
     * exhausted-set fallback at lines 314 to 315 which records the same answer.</p>
     *
     * <p>Assumptions: a set of exactly the page size is the one input on which a comparison written
     * with the wrong sense misbehaves, because it is the only size at which "more than ten rows were
     * read" and "at least ten rows were read" disagree. A case of nine or of eleven would leave that
     * mistake undetected, so this size is chosen deliberately rather than for roundness.</p>
     */
    @Test
    @DisplayName("exactly one page of rows reports no further page and keeps every row")
    void exactlyOnePageOfRowsReportsNoFurtherPage() {
        when(repository.findAllByOrderByTranIdAsc(probeBoundedLimit()))
                .thenReturn(rows(1, TransactionMapper.PAGE_SIZE));

        PageResponse<TransactionListItemResponse> page = list(request(null, null, null));

        assertThat(page.items())
                .as("no row is trimmed when no surplus row was read")
                .hasSize(TransactionMapper.PAGE_SIZE);
        assertThat(page.hasNext())
                .as("no surplus row having been read is what reports no further page")
                .isFalse();
        assertThat(openBoundary(page.lastKey()))
                .as("the trailing boundary still names the last row served")
                .isEqualTo(tranId(TransactionMapper.PAGE_SIZE));
    }

    /**
     * A row inserted into an already-served region is neither served twice nor allowed to skip a row.
     *
     * <p>This pins the two paging paragraphs acting together across a gap in which the set changed:
     * {@code PROCESS-PAGE-FORWARD} at lines 279 to 332 of {@code app/cbl/COTRN00C.cbl} is re-entered
     * from {@code PROCESS-PF8-KEY} at line 257, whose seek at lines 259 to 263 moves the STORED
     * trailing key into the record identification field rather than any count of rows already shown.
     * Because the resumption point is a key, the read at line 298 resumes from the same place in the
     * collating sequence whatever has been added below it.</p>
     *
     * <p>Alternatives Considered: resuming the second page by counting a fixed number of rows from the
     * start of the ordered set, which is the other way to express "the next ten" and needs no stored
     * key at all. It is rejected because it does not survive an insertion, and the reason is ROW
     * SKIPPING AND REPETITION rather than any property of speed. Inserting one row below the
     * resumption point shifts every later row one place further from the start, so a second page taken
     * by counting would begin on a row the caller has already been shown -- {@code
     * rowAlreadyServedThatCountingWouldRepeat} below is exactly that row, and the second page is
     * asserted NOT to begin on it. A deletion below the resumption point shifts them the other way and
     * a row is passed over unseen. Resuming from the stored key has neither failure, because the key of
     * the last row served does not move when its neighbours change.</p>
     *
     * <p>Assumptions: concurrent insertion into the middle of this key space is a real condition of the
     * baseline and not a theoretical one. Lines 210 to 219 of {@code app/cbl/COBIL00C.cbl} mint a new
     * identifier by reading the current maximum and adding one: line 212 moves {@code HIGH-VALUES} into
     * the record identification field, line 213 positions the browse, line 214 reads backward to reach
     * the highest existing record, line 215 ends the browse, line 216 moves that identifier into a
     * working field and line 217 adds one to it. Nothing holds a lock across that sequence, so two bill
     * payments running together can read the same maximum, and records therefore appear in the key
     * space while a browse is in flight. The commented-out {@code GTEQ} at line 597 of
     * {@code app/cbl/COTRN00C.cbl} reinforces it: with exact positioning, a resumption key that has itself been superseded still resolves to a
     * well-defined place in the sequence.</p>
     *
     * <p>Assumptions: the identifiers are spaced by tens so that a value can be inserted strictly
     * between two of them while both remain sixteen digits wide. The inserted identifier sorts below
     * the resumption key, which is what puts it inside the region the caller has already read.</p>
     */
    @Test
    @DisplayName("a row inserted into an already-served region neither repeats nor skips a row")
    void aRowInsertedIntoAnAlreadyServedRegionNeitherRepeatsNorSkips() {
        List<Transaction> beforeInsert = spacedRows(12);
        when(repository.findAllByOrderByTranIdAsc(probeBoundedLimit()))
                .thenReturn(beforeInsert.subList(0, TransactionMapper.PAGE_SIZE + 1));

        PageResponse<TransactionListItemResponse> firstPage = list(request(null, null, null));
        String resumptionKey = openBoundary(firstPage.lastKey());

        // WHY : Assumptions: the inserted identifier is chosen to sort strictly between the fifth and
        //       sixth rows of the page just served, so it lands squarely inside the region the caller
        //       has already read. An identifier beyond the resumption key would be a plain append and
        //       would exercise none of the interference this case is about.
        Transaction insertedRow = row(55);
        List<Transaction> afterInsert = ascendingWith(beforeInsert, insertedRow);
        when(repository.findByTranIdGreaterThanOrderByTranIdAsc(resumptionKey, probeBoundedLimit()))
                .thenReturn(strictlyAfter(afterInsert, resumptionKey));

        PageResponse<TransactionListItemResponse> secondPage =
                list(request(null, firstPage.lastKey(), TransactionListRequest.Direction.NEXT));

        // WHY : Assumptions: the resumption point is asserted against a WRITTEN-OUT identifier rather
        //       than against a value re-derived from the page, because deriving it would make the two
        //       assertions below agree with each other no matter which row the boundary named. Pinning
        //       it to the tenth row is what makes a boundary sealed one row too far fail here as a
        //       SKIPPED row rather than pass unnoticed.
        assertThat(resumptionKey)
                .as("the browse resumes from the last row served, which is the tenth")
                .isEqualTo(tranId(100))
                .isEqualTo(idsOf(firstPage).get(idsOf(firstPage).size() - 1));

        String rowAlreadyServedThatCountingWouldRepeat =
                afterInsert.get(TransactionMapper.PAGE_SIZE).getTranId();
        assertThat(rowAlreadyServedThatCountingWouldRepeat)
                .as("counting ten rows from the start of the changed set lands on the tenth row served")
                .isEqualTo(tranId(100));
        assertThat(idsOf(firstPage))
                .as("the row counting would have repeated is one the caller has already been served")
                .contains(rowAlreadyServedThatCountingWouldRepeat);
        assertThat(idsOf(secondPage))
                .as("resuming from the stored key repeats no row the caller already holds")
                .doesNotContain(rowAlreadyServedThatCountingWouldRepeat)
                .doesNotContainAnyElementsOf(idsOf(firstPage));
        assertThat(idsOf(secondPage))
                .as("the rows beyond the resumption point are served, and none between them is skipped")
                .containsExactly(tranId(110), tranId(120));
        assertThat(idsOf(secondPage))
                .as("the identifier inserted below the resumption key belongs to the region already read")
                .doesNotContain(insertedRow.getTranId());

        // WHY : Assumptions: the two statements the case is named for are finally asserted together over
        //       the whole changed set, because each alone permits the other's failure. A run that
        //       repeated a row would leave a duplicate here, and one that skipped a row would leave the
        //       set short; only the inserted row is legitimately unserved, having landed behind a cursor
        //       the caller had already passed.
        List<String> everythingServed = new ArrayList<>(idsOf(firstPage));
        everythingServed.addAll(idsOf(secondPage));
        assertThat(everythingServed)
                .as("across both pages no row is served twice")
                .doesNotHaveDuplicates()
                .as("across both pages every row of the changed set is served but the inserted one")
                .containsExactlyElementsOf(afterInsert.stream()
                        .map(Transaction::getTranId)
                        .filter(id -> !id.equals(insertedRow.getTranId()))
                        .toList());
    }

    /**
     * Builds an ordered run of rows whose identifiers are spaced so a value can be inserted between.
     *
     * @param count the number of rows to build, of type {@code int}
     * @return the rows in ascending identifier order, spaced by tens, never {@code null}
     */
    private static List<Transaction> spacedRows(int count) {
        List<Transaction> built = new ArrayList<>(count);
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            built.add(row(ordinal * 10));
        }
        return List.copyOf(built);
    }

    /**
     * Inserts one row into an ordered run and returns the run still in ascending identifier order.
     *
     * @param ordered the existing rows in ascending identifier order, of type
     *     {@code List<Transaction>}; must not be {@code null}
     * @param inserted the row to add, of type {@code Transaction}; must not be {@code null}
     * @return a new run holding every row in ascending identifier order, never {@code null}
     */
    private static List<Transaction> ascendingWith(List<Transaction> ordered, Transaction inserted) {
        List<Transaction> merged = new ArrayList<>(ordered);
        merged.add(inserted);
        merged.sort(java.util.Comparator.comparing(Transaction::getTranId));
        return List.copyOf(merged);
    }

    /**
     * Selects the rows of an ordered run that lie STRICTLY beyond one key, capped as the service caps.
     *
     * <p>Assumptions: the predicate is strictly greater than and not greater than or equal, which is
     * what the finder name it stands in for declares and what the commented-out {@code GTEQ} at line
     * 597 of {@code app/cbl/COTRN00C.cbl} leaves as the browse's behaviour. Emulating it inclusively
     * here would hand back the resumption row itself and the no-repetition assertion would then be
     * measuring this helper's mistake rather than the service's correctness.</p>
     *
     * @param ordered the rows in ascending identifier order, of type {@code List<Transaction>}; must
     *     not be {@code null}
     * @param exclusiveFrom the key to resume strictly beyond, of type {@code String}; must not be
     *     {@code null}
     * @return the qualifying rows in ascending identifier order, holding at most one row beyond the
     *     page, never {@code null}
     */
    private static List<Transaction> strictlyAfter(List<Transaction> ordered, String exclusiveFrom) {
        return ordered.stream()
                .filter(candidate -> candidate.getTranId().compareTo(exclusiveFrom) > 0)
                .limit(TransactionMapper.PAGE_SIZE + 1L)
                .toList();
    }

    /**
     * A scan returning nothing yields an empty page carrying neither boundary nor a further page.
     *
     * <p>This pins the end-of-set arm of {@code READNEXT-TRANSACT-FILE}, line 624 of
     * {@code app/cbl/COTRN00C.cbl}, where line 641 records the set as exhausted before the fill loop at
     * line 297 has placed a single row, so the exhausted-set fallback at lines 314 to 315 is the branch
     * the availability block takes.</p>
     *
     * <p>Assumptions: both boundaries are NULLABLE and absent is their value here, which is the one
     * state in which the envelope carries no key at all. All three components are asserted together
     * because a partially populated empty page is the failure worth catching, and because the envelope
     * refuses a further page reported without a trailing position, so an empty page claiming one would
     * raise rather than return.</p>
     */
    @Test
    @DisplayName("a scan returning nothing yields an empty page with no boundary and no further page")
    void aScanReturningNothingYieldsAnEmptyPage() {
        when(repository.findAllByOrderByTranIdAsc(probeBoundedLimit())).thenReturn(List.of());

        PageResponse<TransactionListItemResponse> page = list(request(null, null, null));

        assertThat(page.items()).as("no rows were read, so none are served").isEmpty();
        assertThat(page.firstKey()).as("the leading boundary is absent on an empty page").isNull();
        assertThat(page.lastKey()).as("the trailing boundary is absent on an empty page").isNull();
        assertThat(page.hasNext()).as("an empty page reports no further page").isFalse();
    }

    /**
     * A single row is served as a page of one naming itself at both boundaries.
     *
     * <p>This pins one pass of the fill loop of {@code PROCESS-PAGE-FORWARD}, lines 297 to 303 of
     * {@code app/cbl/COTRN00C.cbl}, where line 300 populates the single row and line 301 advances the
     * index before line 308's lookahead finds nothing.</p>
     *
     * <p>Assumptions: the two boundaries coincide on a page of one, and asserting they are EQUAL rather
     * than merely both present is what separates a correct single-row page from one whose leading
     * boundary was left unsealed and then filled in from the trailing one.</p>
     */
    @Test
    @DisplayName("a single row is a page of one naming itself at both boundaries")
    void aSingleRowIsAPageOfOneNamingItselfAtBothBoundaries() {
        when(repository.findAllByOrderByTranIdAsc(probeBoundedLimit())).thenReturn(rows(1, 1));

        PageResponse<TransactionListItemResponse> page = list(request(null, null, null));

        assertThat(page.items()).as("one row read is one row served").hasSize(1);
        assertThat(page.hasNext()).as("no surplus row was read").isFalse();
        assertThat(openBoundary(page.firstKey()))
                .as("both boundaries name the only row on the page")
                .isEqualTo(tranId(1))
                .isEqualTo(openBoundary(page.lastKey()));
    }

    /**
     * A positioning identifier is honoured INCLUSIVELY, by a keyed read and then a bounded scan.
     *
     * <p>This pins the numeric arm of {@code PROCESS-ENTER-KEY}, line 146 of
     * {@code app/cbl/COTRN00C.cbl}: line 209 tests the keyed-in identifier for numeric content and line
     * 210 moves it into the record identification field, after which the browse positioned at lines 593
     * to 600 begins AT that key rather than after it, because {@code GTEQ} at line 597 is commented
     * out and exact positioning is what remains.</p>
     *
     * <p>Assumptions: the second read is bounded to the page size and NOT to the page size plus one,
     * because the positioned row already occupies the first of the ten slots. The bound is asserted
     * rather than assumed: a second read admitting eleven would read twelve rows in all and report a
     * further page one row early, and the page served would still look correct.</p>
     */
    @Test
    @DisplayName("a positioning identifier is read inclusively, and the scan that follows is bounded to ten")
    void aPositioningIdentifierIsReadInclusively() {
        when(repository.findById(tranId(4))).thenReturn(Optional.of(row(4)));
        when(repository.findByTranIdGreaterThanOrderByTranIdAsc(
                tranId(4), Limit.of(TransactionMapper.PAGE_SIZE)))
                .thenReturn(rows(5, 6));

        PageResponse<TransactionListItemResponse> page = list(request(tranId(4), null, null));

        assertThat(idsOf(page))
                .as("the positioned row leads the page, so the identifier is honoured inclusively")
                .containsExactly(tranId(4), tranId(5), tranId(6));
        verify(repository).findById(tranId(4));
        verify(repository).findByTranIdGreaterThanOrderByTranIdAsc(
                tranId(4), Limit.of(TransactionMapper.PAGE_SIZE));
        verifyNoMoreInteractions(repository);
    }

    /**
     * A positioning identifier matching no record yields an empty page and issues no following scan.
     *
     * <p>This pins the record-not-found arm of {@code STARTBR-TRANSACT-FILE}, lines 591 to 600 of
     * {@code app/cbl/COTRN00C.cbl}, where line 607 records the set as exhausted so that no fill loop
     * runs at all.</p>
     *
     * <p>Assumptions: the ABSENCE of the following scan is the assertion that matters. A handler that
     * fell through to an unpositioned read would answer with the rows at the start of the key space,
     * which is a page the caller did not ask for and which no assertion on the rows alone would
     * distinguish from a correct answer whenever the set happens to begin at the requested
     * identifier.</p>
     */
    @Test
    @DisplayName("a positioning identifier matching no record yields an empty page and no further read")
    void anUnmatchedPositioningIdentifierYieldsAnEmptyPage() {
        when(repository.findById(tranId(9))).thenReturn(Optional.empty());

        PageResponse<TransactionListItemResponse> page = list(request(tranId(9), null, null));

        assertThat(page.items()).as("no record was positioned, so no row is served").isEmpty();
        assertThat(page.lastKey()).as("an empty page carries no trailing boundary").isNull();
        assertThat(page.hasNext()).as("an empty page reports no further page").isFalse();
        verify(repository).findById(tranId(9));
        verifyNoMoreInteractions(repository);
    }

    /**
     * A backward step with no cursor reads nothing rather than falling back to the opening page.
     *
     * <p>This pins the guard of {@code PROCESS-PF7-KEY}, lines 234 to 252 of
     * {@code app/cbl/COTRN00C.cbl}, whose condition at line 245 reaches the backward paragraph at line
     * 246 only when an earlier page exists, and reports at line 248 otherwise.</p>
     *
     * <p>Assumptions: the two answers are told apart deliberately. A caller asking for the page BEFORE
     * a position it never supplied is asking for something that does not exist, and answering with the
     * opening page would tell it the browse had reached its start when in fact the request was
     * malformed. The reference reaches its earlier page from a stored key at lines 236 to 240 and has no
     * earlier-page-without-a-key state to reproduce.</p>
     *
     * <p>Assumptions: the assertion is that NO read was issued at all, not merely that the page came
     * back empty. An implementation that called the descending finder with an absent key and happened
     * to receive nothing would produce this same empty page while issuing a query the contract does not
     * admit.</p>
     */
    @Test
    @DisplayName("a backward step with no cursor reads nothing and issues no query")
    void aBackwardStepWithoutACursorReadsNothing() {
        PageResponse<TransactionListItemResponse> page =
                list(request(null, null, TransactionListRequest.Direction.PREVIOUS));

        assertThat(page.items()).as("no query was issued, so no row is served").isEmpty();
        assertThat(page.firstKey()).as("the leading boundary is absent").isNull();
        assertThat(page.lastKey()).as("the trailing boundary is absent").isNull();
        assertThat(page.hasNext()).as("no further page is reported").isFalse();
        verifyNoInteractions(repository);
    }

    /**
     * An unstated direction resolves forward, and resolves it on the request rather than in the service.
     *
     * <p>This pins the attention-identifier branch the reference takes when neither paging key was
     * pressed, which enters {@code PROCESS-ENTER-KEY} at line 146 of {@code app/cbl/COTRN00C.cbl}
     * rather than {@code PROCESS-PF7-KEY} at line 234 or {@code PROCESS-PF8-KEY} at line 257.</p>
     *
     * <p>Assumptions: the default is asserted on the request AND through a read, because the two could
     * disagree. The request resolving the default while the service branched on the unresolved member
     * would leave an absent direction taking neither branch, and a case exercising either half alone
     * would still pass.</p>
     */
    @Test
    @DisplayName("an unstated direction resolves forward")
    void anUnstatedDirectionResolvesForward() {
        when(repository.findAllByOrderByTranIdAsc(probeBoundedLimit())).thenReturn(rows(1, 1));

        assertThat(request(null, null, null).effectiveDirection())
                .as("the request resolves an unstated direction to the forward one")
                .isEqualTo(TransactionListRequest.Direction.NEXT);

        PageResponse<TransactionListItemResponse> page = list(request(null, null, null));

        assertThat(page.items()).as("the forward branch is the one that ran").hasSize(1);
        verify(repository).findAllByOrderByTranIdAsc(probeBoundedLimit());
        verifyNoMoreInteractions(repository);
    }

    /**
     * The five boundary strings are carried across character for character and stay five strings.
     *
     * <p>This pins the five originating lines of {@code app/cbl/COTRN00C.cbl} individually: line 248
     * inside the guard of {@code PROCESS-PF7-KEY} at line 234, line 270 inside the guard of
     * {@code PROCESS-PF8-KEY} at line 257, line 608 in the record-not-found arm of
     * {@code STARTBR-TRANSACT-FILE} at line 591, line 642 in the end-of-set arm of
     * {@code READNEXT-TRANSACT-FILE} at line 624, and line 676 in the end-of-set arm of
     * {@code READPREV-TRANSACT-FILE} at line 658.</p>
     *
     * <p>Assumptions: each string is compared against a LITERAL written out here rather than against
     * the constant the service publishes. Comparing a constant with itself would pass whatever that
     * constant said, so the literal is what actually anchors the text to its originating line and makes
     * a drifting constant fail.</p>
     *
     * <p>Refactoring Rationale: three of the five speak of "the top" and differ from one another only
     * by a word -- one says "already at the top", one says "at the top" and one says "have reached the
     * top" -- and the reference emits them from three different paragraphs. Two of the five likewise
     * differ only in the same way about "the bottom". They are therefore asserted separately and then
     * asserted to be free of duplicates, because a selector that answered any one of the three for all
     * three conditions would satisfy every other assertion in this class while changing what a user
     * reads.</p>
     */
    @Test
    @DisplayName("the five boundary strings are verbatim from their five originating lines and all differ")
    void theFiveBoundaryStringsAreVerbatimAndDistinct() {
        assertThat(TransactionListService.MESSAGE_ALREADY_AT_TOP)
                .as("line 248, the earlier-page guard")
                .isEqualTo("You are already at the top of the page...");
        assertThat(TransactionListService.MESSAGE_ALREADY_AT_BOTTOM)
                .as("line 270, the further-page guard")
                .isEqualTo("You are already at the bottom of the page...");
        assertThat(TransactionListService.MESSAGE_AT_TOP)
                .as("line 608, positioning found no record")
                .isEqualTo("You are at the top of the page...");
        assertThat(TransactionListService.MESSAGE_REACHED_BOTTOM)
                .as("line 642, the forward read reached the end of the set")
                .isEqualTo("You have reached the bottom of the page...");
        assertThat(TransactionListService.MESSAGE_REACHED_TOP)
                .as("line 676, the backward read reached the start of the set")
                .isEqualTo("You have reached the top of the page...");

        assertThat(List.of(TransactionListService.MESSAGE_ALREADY_AT_TOP,
                        TransactionListService.MESSAGE_ALREADY_AT_BOTTOM,
                        TransactionListService.MESSAGE_AT_TOP,
                        TransactionListService.MESSAGE_REACHED_BOTTOM,
                        TransactionListService.MESSAGE_REACHED_TOP))
                .as("no two of the five conditions may share a string")
                .doesNotHaveDuplicates();
    }

    /**
     * Each of the five boundary conditions selects its own string, and a page within bounds selects none.
     *
     * <p>This pins the five conditions to the five paragraphs that raise them in
     * {@code app/cbl/COTRN00C.cbl}: an earlier page requested with nothing to go back to is the guard
     * at line 245 failing and reports line 248; a backward read that yields nothing has reached the
     * start of the set and reports line 676; a forward step from a cursor that yields nothing is the
     * availability condition at line 267 not being set and reports line 270; an opening request whose
     * positioning identifier matches no record reports line 608; and an opening request over an empty
     * set is the first forward read reaching the end and reports line 642.</p>
     *
     * <p>Assumptions: the sixth outcome -- a page that met no boundary and therefore carries no message
     * -- is asserted alongside the five, because collapsing the others onto a single always-present
     * string is the most likely defect and it would otherwise go unseen.</p>
     */
    @Test
    @DisplayName("each boundary condition selects its own string and a page within bounds selects none")
    void eachBoundaryConditionSelectsItsOwnString() {
        when(repository.findAllByOrderByTranIdAsc(probeBoundedLimit()))
                .thenReturn(rows(1, TransactionMapper.PAGE_SIZE));
        PageResponse<TransactionListItemResponse> withinBounds = list(request(null, null, null));
        PageResponse<TransactionListItemResponse> empty =
                new PageResponse<>(List.of(), null, null, false, false);
        String cursor = seal(tranId(1));

        assertThat(service.boundaryMessage(
                request(null, null, TransactionListRequest.Direction.PREVIOUS), empty))
                .as("an earlier page asked for with no cursor to go back from")
                .isEqualTo(TransactionListService.MESSAGE_ALREADY_AT_TOP);
        assertThat(service.boundaryMessage(
                request(null, cursor, TransactionListRequest.Direction.PREVIOUS), empty))
                .as("a backward read that reached the start of the set")
                .isEqualTo(TransactionListService.MESSAGE_REACHED_TOP);
        assertThat(service.boundaryMessage(
                request(null, cursor, TransactionListRequest.Direction.NEXT), empty))
                .as("a forward step from a cursor with nothing beyond it")
                .isEqualTo(TransactionListService.MESSAGE_ALREADY_AT_BOTTOM);
        assertThat(service.boundaryMessage(request(tranId(9), null, null), empty))
                .as("positioning on an identifier that matches no record")
                .isEqualTo(TransactionListService.MESSAGE_AT_TOP);
        assertThat(service.boundaryMessage(request(null, null, null), empty))
                .as("an opening request over a set that holds nothing")
                .isEqualTo(TransactionListService.MESSAGE_REACHED_BOTTOM);
        assertThat(service.boundaryMessage(request(null, null, null), withinBounds))
                .as("a page that met no boundary carries no message at all")
                .isEqualTo(TransactionListService.NO_MESSAGE);
    }

    /**
     * The lookup-failure string is the LOWERCASE form the list program uses, not the capitalised one.
     *
     * <p>This pins the three unstructured error arms of {@code app/cbl/COTRN00C.cbl}, which all move the
     * same string: line 615 in {@code STARTBR-TRANSACT-FILE} at line 591, line 649 in
     * {@code READNEXT-TRANSACT-FILE} at line 624 and line 683 in {@code READPREV-TRANSACT-FILE} at line
     * 658. Each is reached from a response code the paragraph does not name, after the diagnostic
     * displays at lines 613, 647 and 681 and the error flag is raised at lines 614, 648 and 682.</p>
     *
     * <p>Assumptions: the capitalised spelling is a SEPARATE string belonging to other programs and is
     * asserted absent here rather than treated as the same message. Merging the two would be a silent
     * one-character change to what a user reads on this screen, and it is the kind of difference a
     * reviewer reading two files side by side is least likely to notice.</p>
     */
    @Test
    @DisplayName("the lookup-failure string is the lowercase form the list program carries")
    void theLookupFailureStringIsTheLowercaseForm() {
        assertThat(TransactionListService.MESSAGE_LOOKUP_FAILED)
                .as("lines 615, 649 and 683 all carry a lowercase initial on the noun")
                .isEqualTo("Unable to lookup transaction...");
        assertThat(TransactionListService.MESSAGE_LOOKUP_FAILED)
                .as("the capitalised spelling belongs to other programs and not to this screen")
                .isNotEqualTo("Unable to lookup Transaction...");
    }

    /**
     * An opening scan that fails abnormally is reported with the reference sentence and its cause.
     *
     * <p>This pins the {@code WHEN OTHER} arm of {@code STARTBR-TRANSACT-FILE}, line 591 of
     * {@code app/cbl/COTRN00C.cbl}: the selection over the response code at line 603 reaches an arm
     * the paragraph does not name at line 613, displays the diagnostic there, raises the error flag at
     * line 614 and moves the sentence at line 615.</p>
     *
     * <p>Refactoring Rationale: this case and the two below replace an assertion that compared
     * {@code MESSAGE_LOOKUP_FAILED} to two string literals and drove nothing. That assertion could not
     * fail while the arm was absent -- and the arm WAS absent: the constant's own documentation said so
     * in as many words, recording that nothing here selected it. So the class held a case named for a
     * failure and a constant carrying the failure's wording, and between them no path that produced
     * either. These cases drive a repository that raises and require the reference sentence to come
     * back, which is the assertion that could not previously exist.</p>
     *
     * <p>Assumptions: the raised type is asserted alongside the message, because the message alone does
     * not determine how the edge answers. The shared advice maps this type to a server failure, and a
     * refusal carrying the right words as, say, an argument fault would render the same sentence under
     * a client-error status -- which is a different observable outcome for the same reference arm.</p>
     *
     * <p>Assumptions: the CAUSE is asserted to be the originating failure rather than discarded. The
     * reference displays the response and reason codes of the failed verb before it sets the message,
     * and the failure object is the equivalent context here; a wrapper that dropped it would satisfy
     * every other assertion in this case while losing the only diagnostic the log receives.</p>
     */
    @Test
    @DisplayName("an opening scan that fails abnormally raises the reference sentence with its cause")
    void anOpeningScanFailureCarriesTheReferenceSentence() {
        RuntimeException storeFailure = new RuntimeException("the ordered read could not be issued");
        when(repository.findAllByOrderByTranIdAsc(probeBoundedLimit())).thenThrow(storeFailure);

        assertThatThrownBy(() -> list(request(null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionListService.MESSAGE_LOOKUP_FAILED)
                .hasCause(storeFailure);
    }

    /**
     * A forward step that fails abnormally is reported with the same sentence.
     *
     * <p>This pins the {@code WHEN OTHER} arm of {@code READNEXT-TRANSACT-FILE}, line 624 of
     * {@code app/cbl/COTRN00C.cbl}: the arm at line 647, the flag at line 648 and the sentence at line
     * 649.</p>
     *
     * <p>Assumptions: the forward path is driven separately from the opening one rather than trusted to
     * share its arm, because the reference spells the arm three times and a target implementing it once
     * has to be shown to reach it from all three. A single case over one path would leave two of the
     * three reference arms with no counterpart under assertion.</p>
     */
    @Test
    @DisplayName("a forward step that fails abnormally raises the same reference sentence")
    void aForwardStepFailureCarriesTheReferenceSentence() {
        RuntimeException storeFailure = new RuntimeException("the forward read could not be issued");
        when(repository.findByTranIdGreaterThanOrderByTranIdAsc(tranId(4), probeBoundedLimit()))
                .thenThrow(storeFailure);

        assertThatThrownBy(() -> list(request(null, seal(tranId(4)),
                TransactionListRequest.Direction.NEXT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionListService.MESSAGE_LOOKUP_FAILED)
                .hasCause(storeFailure);
    }

    /**
     * A backward step that fails abnormally is reported with the same sentence.
     *
     * <p>This pins the {@code WHEN OTHER} arm of {@code READPREV-TRANSACT-FILE}, line 658 of
     * {@code app/cbl/COTRN00C.cbl}: the arm at line 681, the flag at line 682 and the sentence at line
     * 683.</p>
     *
     * <p>Assumptions: this is the third of the three reference arms, so with it every browse verb the
     * program guards has a counterpart here. The sentence is asserted to be the SAME one rather than a
     * per-verb variant, which is what the reference does -- all three arms move one string, and the
     * verb that failed is not observable to the user in either the baseline or this target.</p>
     */
    @Test
    @DisplayName("a backward step that fails abnormally raises the same reference sentence")
    void aBackwardStepFailureCarriesTheReferenceSentence() {
        RuntimeException storeFailure = new RuntimeException("the backward read could not be issued");
        when(repository.findByTranIdLessThanOrderByTranIdDesc(tranId(9), probeBoundedLimit()))
                .thenThrow(storeFailure);

        assertThatThrownBy(() -> list(request(null, seal(tranId(9)),
                TransactionListRequest.Direction.PREVIOUS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionListService.MESSAGE_LOOKUP_FAILED)
                .hasCause(storeFailure);
    }

    /**
     * A positioning read that matches nothing is a page boundary, NOT a lookup failure.
     *
     * <p>This pins the not-found arm of {@code STARTBR-TRANSACT-FILE} at lines 605 to 611 of
     * {@code app/cbl/COTRN00C.cbl}, which sits inside the SAME selection as the normal arm and sets its
     * own message rather than reaching the catch-all at line 613.</p>
     *
     * <p>Refactoring Rationale: this case is the fence around the three above. The failure arm is
     * implemented by a guard around each read, and the emptiness test for an unmatched identifier is
     * deliberately kept OUTSIDE that guard -- so this case is what shows the guard was drawn at the
     * right boundary. Had the emptiness test been enclosed, or had absence been signalled by raising,
     * an ordinary unmatched search would be reported to the user as a server failure and the three
     * cases above would all still pass. The sibling {@code TransactionViewService} records the same
     * hazard at its own equivalent read for the same reason.</p>
     *
     * <p>Assumptions: the page is asserted to come back empty and to carry the not-found boundary
     * message rather than merely to not raise. Asserting only the absence of an exception would pass
     * against a service that swallowed a genuine failure and returned an empty page, which is the
     * opposite error and equally silent.</p>
     */
    @Test
    @DisplayName("an unmatched identifier is a page boundary and is not reported as a lookup failure")
    void anUnmatchedIdentifierIsNotReportedAsALookupFailure() {
        when(repository.findById(tranId(9))).thenReturn(Optional.empty());

        TransactionListRequest unmatched = request(tranId(9), null, null);
        PageResponse<TransactionListItemResponse> page = list(unmatched);

        assertThat(idsOf(page)).as("an unmatched position yields no rows at all").isEmpty();
        assertThat(this.service.boundaryMessage(unmatched, page))
                .as("the boundary carries the not-found branch's own sentence from lines 605 to 611, "
                        + "never the catch-all sentence from line 615")
                .isEqualTo(TransactionListService.MESSAGE_AT_TOP)
                .isNotEqualTo(TransactionListService.MESSAGE_LOOKUP_FAILED);
    }

    /**
     * The numeric-filter refusal keeps the space before its ellipsis that the list program carries.
     *
     * <p>This pins the non-numeric arm of {@code PROCESS-ENTER-KEY} at line 146 of
     * {@code app/cbl/COTRN00C.cbl}: line 211 takes the else branch, line 212 raises the error flag and
     * line 214 moves the refusal string, which the request carries as the message on its own numeric
     * constraint.</p>
     *
     * <p>Assumptions: the space before the ellipsis is part of the string and not typesetting. Line 214
     * carries {@code 'Tran ID must be Numeric ...'} with a space, whereas line 199 of the same program
     * carries {@code 'Invalid selection. Valid value is S'} with no ellipsis at all, so the two
     * neighbouring refusals of one paragraph are punctuated three different ways between them. Both
     * spacings are asserted, the present one by equality and the absent one by inequality, because a
     * reviewer normalising whitespace across a catalogue would otherwise quietly unify them.</p>
     *
     * @throws NoSuchFieldException if the constrained component is renamed, which is the intended
     *     failure rather than a condition to handle
     */
    @Test
    @DisplayName("the numeric-filter refusal keeps its space before the ellipsis")
    void theNumericFilterRefusalKeepsItsSpaceBeforeTheEllipsis() throws NoSuchFieldException {
        String declaredMessage = TransactionListRequest.class
                .getDeclaredField("transactionIdFilter")
                .getAnnotation(jakarta.validation.constraints.Pattern.class)
                .message();

        assertThat(declaredMessage)
                .as("line 214 punctuates with a space before the ellipsis")
                .isEqualTo("Tran ID must be Numeric ...")
                .isNotEqualTo("Tran ID must be Numeric...");
        assertThat("Invalid selection. Valid value is S")
                .as("line 199 of the same paragraph carries no ellipsis at all")
                .doesNotEndWith("...")
                .doesNotContain("...");
    }

    /**
     * A served row carries no selection marker and no message, because neither travelled on the row.
     *
     * <p>This pins the browse block copied in at lines 61 to 70 of {@code app/cbl/COTRN00C.cbl}. The
     * selection marker is {@code CDEMO-CT00-TRN-SEL-FLG PIC X(01)} at line 69 and the identifier it
     * selects is {@code CDEMO-CT00-TRN-SELECTED PIC X(16)} at line 70, and BOTH sit in the passed
     * communication area rather than in the record the row is built from.</p>
     *
     * <p>Assumptions: because the marker travelled in the communication area, its target here is the
     * client's own selection state and not a member of the served row. The evaluation at lines 185 to
     * 196 reads that marker and transfers control to the detail program at lines 188 and 192 to 195,
     * accepting both {@code 'S'} at line 186 and {@code 's'} at line 187; in the target that transfer is
     * a route change the client makes, so no server response needs a place to put the marker. The
     * refusal at line 199 has no published constant on this service for the same reason, and its absence
     * is deliberate rather than an omission.</p>
     *
     * <p>Assumptions: the row is asserted to hold exactly the four members it declares, so a marker or a
     * message member added later fails here rather than silently widening a published contract.</p>
     */
    @Test
    @DisplayName("a served row carries no selection marker and no message member")
    void aServedRowCarriesNoSelectionMarkerAndNoMessage() {
        List<String> components =
                Arrays.stream(TransactionListItemResponse.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList();

        assertThat(components)
                .as("the row carries exactly the four members the record declares")
                .containsExactly("transactionId", "description", "amount", "originTimestamp");
        assertThat(components)
                .as("the selection marker of line 69 travelled in the communication area, not the row")
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("sel"));
        assertThat(components)
                .as("no message member is carried on a row; the boundary string is answered separately")
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("message"));
    }

    /**
     * Nothing carries a re-entry discriminator, so a refusal never depends on which turn it is.
     *
     * <p>This pins the removal of {@code CDEMO-PGM-CONTEXT}. The reference resets it at line 191 of
     * {@code app/cbl/COTRN00C.cbl}, inside the selection evaluation at lines 185 to 196, immediately
     * before transferring control at lines 192 to 195.</p>
     *
     * <p>Refactoring Rationale: that discriminator existed because the reference is
     * pseudo-conversational -- its task ends at every screen turn, so it had to be told whether the
     * data arriving was a first entry or a re-entry. What was wrong with carrying it forward is visible
     * in {@code app/cpy/CSSETATY.cpy}, the templated highlight book: lines 18 to 19 test a field's
     * validation flag for not-ok or blank, and line 20 then ANDs that test with
     * {@code CDEMO-PGM-REENTER}. The highlight is therefore gated on the turn counter, so a field that
     * genuinely failed is left unhighlighted whenever the counter says first entry. A stateless handler
     * has no turn to count and no such gate: every failing request is answered with its field errors.
     * The rest of that book is carried across in meaning -- line 21 moves the error colour into the
     * attribute field at line 22, and the nested test at line 23 additionally moves a literal asterisk
     * into the data field at lines 24 to 25 when the field is blank, so not-ok yields colour alone
     * while blank yields colour and the marker.</p>
     *
     * <p>Assumptions: the absence is asserted structurally over both published shapes rather than
     * described, because a discriminator is exactly the kind of member that gets reintroduced as a
     * convenience by a later caller wanting to suppress a first-turn message.</p>
     */
    @Test
    @DisplayName("no re-entry discriminator is carried, so a refusal never depends on the turn")
    void noReEntryDiscriminatorIsCarried() {
        List<String> requestComponents =
                Arrays.stream(TransactionListRequest.class.getRecordComponents())
                        .map(RecordComponent::getName).toList();
        List<String> rowComponents =
                Arrays.stream(TransactionListItemResponse.class.getRecordComponents())
                        .map(RecordComponent::getName).toList();

        List<String> discriminatorNames = List.of("context", "reenter", "reEnter", "reentry",
                "pgmContext", "turn", "firstEntry");
        for (String forbidden : discriminatorNames) {
            assertThat(requestComponents)
                    .as("the request carries no re-entry discriminator named %s", forbidden)
                    .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT)
                            .contains(forbidden.toLowerCase(java.util.Locale.ROOT)));
            assertThat(rowComponents)
                    .as("a served row carries no re-entry discriminator named %s", forbidden)
                    .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT)
                            .contains(forbidden.toLowerCase(java.util.Locale.ROOT)));
        }
        assertThat(TransactionListRequest.class.getRecordComponents())
                .as("the request has exactly three members, leaving no room for a discriminator")
                .hasSize(3);
    }

    /**
     * A request carries a cursor and a direction only, with no ordinal counting the pages served.
     *
     * <p>This pins the page ordinal of {@code app/cbl/COTRN00C.cbl} as DISPLAY-ONLY. The member is
     * {@code CDEMO-CT00-PAGE-NUM PIC 9(08)} at line 65, and every use of it is presentational: lines 306
     * to 307 advance it, lines 316 to 319 default it, line 324 moves it to the screen field, lines 364
     * and 366 decrement and floor it, and line 373 sends it again. It is compared against a number only
     * at line 245, to decide whether an earlier page exists, and never against a record key.</p>
     *
     * <p>Trade-offs: the rows served are fixed at ten and the count is deliberately NOT a component of
     * the request. In {@code app/cbl/COTRN00C.cbl}, line 297 loops until the index reaches eleven and
     * lines 290 and 349 both work over ten slots, so ten is the screen's own arity. What is given up is a caller's ability to ask for a
     * different number of rows; what is bought is that the answer matches a fixed twenty-four by eighty
     * screen exactly, and that the surplus-row arithmetic has one meaning rather than one per
     * caller.</p>
     *
     * <p>Assumptions: the cursor is a single sixteen-character scalar and the request carries one of
     * them, not a pair. The neighbouring card-list screen carries a twenty-seven byte composite at lines
     * 231 to 232 of {@code app/cbl/COCRDLIC.cbl}; assuming that shape here would need a second
     * component this request does not have and must not grow.</p>
     */
    @Test
    @DisplayName("a request carries a cursor and a direction only, with no ordinal counting pages")
    void aRequestCarriesACursorAndADirectionOnly() {
        List<String> components = Arrays.stream(TransactionListRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(components)
                .as("the request carries exactly the three members the contract declares")
                .containsExactly("transactionIdFilter", "cursor", "direction");
        assertThat(components)
                .as("no ordinal counting the pages already served is carried on the request")
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("num")
                        || name.toLowerCase(java.util.Locale.ROOT).contains("ordinal")
                        || name.toLowerCase(java.util.Locale.ROOT).contains("count"));
        assertThat(TransactionMapper.PAGE_SIZE)
                .as("the rows served are the ten slots COTRN00C lines 290 and 349 work over")
                .isEqualTo(10);
        assertThat(tranId(1))
                .as("the cursor is one scalar at the sixteen characters CVTRA05Y.cpy line 5 declares")
                .hasSize(TransactionMapper.TRANSACTION_ID_WIDTH);
    }

    /**
     * A served amount is exact fixed point at the scale the money contract fixes, never a binary float.
     *
     * <p>This pins the amount member of the record layout, {@code TRAN-AMT PIC S9(09)V99} at line 10 of
     * {@code app/cpy/CVTRA05Y.cpy}, as it survives the journey onto a served row. The reference moves
     * that member into an edited display field inside {@code POPULATE-TRAN-DATA} at line 381 of
     * {@code app/cbl/COTRN00C.cbl} and performs no arithmetic on it.</p>
     *
     * <p>Assumptions: the served member is typed as {@link Money} and that DECLARED type is what selects
     * the quoted-string wire form, so the assertion is on the type as well as on the value. A member
     * declared as a bare decimal would compile, run and carry the right number while emitting it as a
     * bare JSON number, which most clients parse into a binary floating-point value and thereby lose the
     * exactness the balance columns depend on.</p>
     *
     * <p>Assumptions: the scale is asserted to be exactly the two the contract fixes rather than merely
     * numerically equal, because a value carrying a different scale compares equal numerically and
     * renders differently, and rendering is what a statement reader sees.</p>
     */
    @Test
    @DisplayName("a served amount is exact fixed point at scale two, carried as money and not a number")
    void aServedAmountIsExactFixedPointAtScaleTwo() {
        when(repository.findAllByOrderByTranIdAsc(probeBoundedLimit())).thenReturn(rows(1, 1));

        PageResponse<TransactionListItemResponse> page = list(request(null, null, null));
        TransactionListItemResponse served = page.items().get(0);

        assertThat(served.amount())
                .as("the amount is carried as the shared money type, which fixes the wire form")
                .isInstanceOf(Money.class)
                .isEqualTo(Money.of("504.77"));
        assertThat(served.amount().amount().scale())
                .as("the scale is the two the money contract fixes")
                .isEqualTo(Money.SCALE);
        assertThat(served.originTimestamp())
                .as("the stamp served is the originating one, rendered at the declared width")
                .hasSize(26);
    }

    /**
     * Every argument of the paging operation is required, and each is refused on its own.
     *
     * <p>This pins a TARGET-side invariant that has no originating paragraph, and the absence is stated
     * rather than papered over with a citation. The reference cannot express an absent argument at all:
     * its fields are fixed-width, so every one of them always holds something, and what it does instead
     * is substitute a SENTINEL and carry on. Lines 206 to 207 of {@code app/cbl/COTRN00C.cbl} move
     * {@code LOW-VALUES} into the record identification field when the keyed-in identifier is blank,
     * lines 236 to 240 do the same when no leading key has been stored, and lines 259 to 263 move
     * {@code HIGH-VALUES} when no trailing key has. Absence is therefore a hazard this target
     * introduces by having references at all, so it is refused here rather than given a sentinel.</p>
     *
     * <p>Assumptions: the three are asserted separately rather than as one refusal, because they fail
     * for three different reasons and a single assertion would still pass if one check subsumed another.
     * The subject matters most: an absent one reaching the binding composition would compose a binding
     * naming the word for absence, which every caller without a subject would then share, and a cursor
     * sealed under it would be accepted from any of them.</p>
     */
    @Test
    @DisplayName("every argument of the paging operation is required")
    void everyArgumentOfThePagingOperationIsRequired() {
        assertThatThrownBy(() -> service.listTransactions(null, this.cursorToken, SUBJECT))
                .as("the request is required")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.listTransactions(request(null, null, null), null, SUBJECT))
                .as("the sealer is required")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                service.listTransactions(request(null, null, null), this.cursorToken, null))
                .as("the subject is required")
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(repository);
    }

    /**
     * Both arguments of the boundary-string selector are required.
     *
     * <p>This pins a TARGET-side invariant with no originating paragraph, for the reason given above:
     * the reference has no absent value to refuse. It reaches its own message field unconditionally --
     * line 249 after the guard at line 245 of {@code app/cbl/COTRN00C.cbl}, line 271 after the guard at
     * line 267 -- so the question of an unsupplied argument never arises there.</p>
     *
     * <p>Assumptions: the selector reads the request AND the page it produced, so neither is optional and
     * neither has a defensible default. Answering "no boundary met" for a call that never produced a
     * page would be the one answer a caller cannot tell apart from a real one.</p>
     */
    @Test
    @DisplayName("the boundary-string selector requires both of its arguments")
    void theBoundaryStringSelectorRequiresBothArguments() {
        PageResponse<TransactionListItemResponse> empty =
                new PageResponse<>(List.of(), null, null, false, false);

        assertThatThrownBy(() -> service.boundaryMessage(null, empty))
                .as("the request is required")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.boundaryMessage(request(null, null, null), null))
                .as("the page is required")
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(repository);
    }

    /**
     * Both collaborators are required at construction rather than at first use.
     *
     * <p>This pins a TARGET-side invariant with no originating paragraph, and the reason is structural
     * rather than incidental. The reference has no constructor and no injected collaborator to be
     * missing: it names its dataset as a literal in working storage and passes it to each file verb, at
     * line 594 of {@code app/cbl/COTRN00C.cbl} for the browse position, line 627 for the forward read,
     * line 661 for the backward read and line 695 for the browse end. Assembly is therefore a concern
     * this target creates by having collaborators, so it is checked where the assembly happens.</p>
     *
     * <p>Assumptions: refusing at construction is what keeps a half-built service from being held at
     * all. Deferring the check to the first read would surface the fault on a request and attribute an
     * assembly error to whichever caller happened to arrive first.</p>
     */
    @Test
    @DisplayName("both collaborators are required at construction")
    void bothCollaboratorsAreRequiredAtConstruction() {
        assertThatThrownBy(() -> new TransactionListService(null, this.transactionMapper))
                .as("the reader is required")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransactionListService(this.repository, null))
                .as("the converter is required")
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(repository);
    }
}
