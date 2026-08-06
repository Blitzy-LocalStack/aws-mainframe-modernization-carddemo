package com.carddemo.transaction.service;

import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.TransactionListItemResponse;
import com.carddemo.transaction.dto.TransactionListRequest;
import com.carddemo.transaction.mapper.TransactionMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the paged transaction browse of {@code app/cbl/COTRN00C.cbl} into one keyset-paginated
 * query pair, and assembles the page envelope this bounded context answers a list request with.
 *
 * <p><b>Purpose.</b> This class is the migrated form of the "Transaction List" screen, transaction
 * {@code CT00}. It decides which ordered read answers a request, bounds that read, separates the
 * rows a caller receives from the one surplus row that only settles whether a further page follows,
 * seals the two boundary keys, and selects the page-boundary message the reference screen would
 * have shown. It owns nothing else: every query belongs to
 * {@code com.carddemo.transaction.repository}, every representation concern to
 * {@code com.carddemo.transaction.mapper}, and every wire shape to
 * {@code com.carddemo.transaction.dto}.
 *
 * <p>The reference program is 699 lines, counted in the file rather than carried over from a
 * summary. It is read and cited only. Nothing in this class modifies it, and where the migrated
 * behaviour differs the divergence is stated here rather than introduced silently.
 *
 * <h2>Paragraph to method, with every anchor verified in the file</h2>
 *
 * <p>A paragraph's declaration is cited below, never one of its call sites, so that a reader
 * following a citation arrives at the logic rather than at a branch that reaches it.
 *
 * <ul>
 *   <li>{@code MAIN-PARA} at line 95 has NO method here. It is CICS task orchestration: it tests
 *       the communication-area length at line 107, dispatches on the attention identifier at lines
 *       119 to 134 and ends the task at lines 138 to 141. A stateless handler answers one request,
 *       so there is no task to open or end.</li>
 *   <li>{@code PROCESS-ENTER-KEY} at line 146 becomes {@link #processEnterKey}.</li>
 *   <li>{@code PROCESS-PF7-KEY} at line 234 becomes {@link #processPf7Key}.</li>
 *   <li>{@code PROCESS-PF8-KEY} at line 257 becomes {@link #processPf8Key}.</li>
 *   <li>{@code PROCESS-PAGE-FORWARD} at line 279 becomes {@link #processPageForward}.</li>
 *   <li>{@code PROCESS-PAGE-BACKWARD} at line 333 becomes {@link #processPageBackward}.</li>
 *   <li>{@code POPULATE-TRAN-DATA} at line 381 has no method here. Its slot-by-slot moves are
 *       row-to-row conversion, which {@link TransactionMapper#toListItem} performs, reached through
 *       {@link TransactionMapper#toListPage}. Two of its statements are not conversion and are
 *       transcribed here instead: line 393 captures the leading boundary at slot one and lines 438
 *       and 439 capture the trailing boundary at slot ten, which is what
 *       {@link #sealBoundaryKey} seals.</li>
 *   <li>{@code INITIALIZE-TRAN-DATA} at line 450 has no method here. It blanks the ten display
 *       slots, performed from lines 290 to 292 and 344 to 346, because a terminal map retains
 *       whatever the previous turn left in it. A JSON array carries exactly the rows placed in it,
 *       so there is no residue to clear and an empty page is an empty list.</li>
 *   <li>{@code SEND-TRNLST-SCREEN} at line 527 has no method here. Returning the envelope replaces
 *       the map send at lines 534 to 548.</li>
 *   <li>{@code STARTBR-TRANSACT-FILE} at line 591 is SUBSUMED into the choice of finder made by
 *       {@link #processEnterKey}, {@link #processPageForward} and {@link #processPageBackward}. Its
 *       not-found branch at lines 605 to 611 survives as a selected message rather than as a
 *       method.</li>
 *   <li>{@code READNEXT-TRANSACT-FILE} at line 624 is SUBSUMED into the ascending queries issued by
 *       {@link #processPageForward} and {@link #processEnterKey}. Ten reads plus one probe become
 *       one bounded query.</li>
 *   <li>{@code READPREV-TRANSACT-FILE} at line 658 is SUBSUMED into the descending query issued by
 *       {@link #processPageBackward}.</li>
 *   <li>{@code ENDBR-TRANSACT-FILE} at line 692 has NO analogue, and the absence is the point
 *       rather than an omission. It is performed at lines 322 and 371 to release a browse position
 *       held between reads; a query holds no position between calls, so there is no browse to
 *       close.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: four file verbs collapse into one query per direction rather than
 * becoming four methods, because what the four jointly express is a single ordered scan resumed
 * from a key. Transcribing them one-for-one would produce a method that opens a cursor, a method
 * that steps it and a method that closes it, none of which has a subject once the scan is a query,
 * and a reader would then look for state that does not exist.
 *
 * <h2>The page carries ten rows and an eleventh row is read to settle availability</h2>
 *
 * <p>Assumptions: ten is the reference program's own page and not a value chosen here. Line 290
 * clears ten slots with {@code UNTIL WS-IDX > 10}, line 295 sets the index to one, the fill loop at
 * line 297 stops once the index reaches eleven and line 301 increments it. The count is consumed
 * from {@link TransactionMapper#PAGE_SIZE} rather than restated, so one number cannot disagree with
 * itself across two files.
 *
 * <p>Assumptions: the page size is absent from {@link TransactionListRequest} and is therefore not
 * a client's to choose. A different size returns a different set of rows for the same cursor, which
 * is observable behaviour rather than presentation, and every size returns rows that look
 * plausible, so a wrong one would not localise to any test of this class. What is given up is a
 * client's ability to ask for fewer rows over a slow link.
 *
 * <p>Refactoring Rationale: each read asks for one row beyond the page, and that surplus row is a
 * transcription rather than a heuristic. After the ten slots are filled, line 308 performs an
 * ELEVENTH read whose only purpose is to discover whether anything follows, and lines 309 to 313
 * set the availability condition from that read's outcome alone. The backward path does the same at
 * line 360. Deriving availability from the row count instead would report a further page whenever
 * the page happened to be full, which is wrong for every set whose size is a multiple of ten.
 *
 * <p>Refactoring Rationale: the trailing boundary is taken from the LAST ROW THE CALLER RECEIVES
 * and never from that surplus row. The reference proves the distinction rather than merely implying
 * it: the trailing identifier is captured at lines 438 and 439, inside the branch for slot ten of
 * the fill loop, while the probe read at line 308 is never passed to the populating paragraph and
 * so never reaches any slot. Reading the boundary off the probe advances the cursor one row too
 * far, and the row it names is then skipped on the following request -- a defect no test of a
 * single page can see, because each page is internally consistent and only the seam between two
 * pages is wrong.
 *
 * <h2>Comparison by key, never by counted position</h2>
 *
 * <p>Refactoring Rationale: pagination by ordinal position was available and is rejected on a
 * defect rather than a preference. A page positioned by counting rows from the start of an ordered
 * set moves when a row is inserted before the count, so such a page omits rows it never showed and
 * repeats rows it already showed. The concurrency is attested by the reference material rather than
 * hypothetical: {@code app/cbl/COBIL00C.cbl} mints the next transaction identifier over lines 212
 * to 217 by seeking from high values, reading backwards, ending the browse and adding one, holding
 * no lock across the sequence, so two payments can derive one identifier and land in the middle of
 * a key space a browse is walking. A key already returned keeps its place in the ordering whatever
 * is inserted around it.
 *
 * <p>Refactoring Rationale: the comparisons are spelled strictly, greater-than forward and
 * less-than backward, because the reference states no comparison at all. Its browse-start paragraph
 * at line 591 names the dataset, the record identification field and the key length at lines 594 to
 * 596, and its greater-or-equal option at line 597 is COMMENTED OUT, so which rows the position
 * admitted came from the access method's default rather than from anything a reader of the source
 * can see. The baseline positions without that option; the Java states the predicate in the query;
 * the divergence is documented here and in the migration traceability register.
 *
 * <p>Trade-offs: the reference expresses inclusion and exclusion as a CONDITIONAL PRIMING READ and
 * this class expresses the same thing as a CHOICE OF FINDER, and the observable page contents are
 * the same. Line 285 suppresses the forward positioning read for the enter, seventh-function and
 * third-function keys, and line 339 suppresses the backward one for the enter and eighth-function
 * keys. So a forward page reached by the eighth key does consume the positioning record, which is
 * the row already on display, and its page therefore begins after it -- exactly a strict
 * greater-than against the supplied cursor. An entry that pressed no paging key skips that read and
 * begins AT its start key, which is the opening read this class issues instead. The compromise
 * accepted is that a reader comparing the two must map a suppressed read onto a selected query
 * rather than onto a comparison operator sitting in the same place.
 *
 * <p>Refactoring Rationale: the screen ordinal is DROPPED and an opaque cursor carries the position
 * instead. The reference keeps {@code CDEMO-CT00-PAGE-NUM} in the communication area declared at
 * lines 19 to 44 of {@code app/cpy/COCOM01Y.cpy}, incrementing it at lines 306 and 307 and again at
 * lines 317 and 318, decrementing it at lines 363 to 366 and resetting it at line 224 before the
 * forward page at line 225. That structure does not travel, and its re-entry discriminator at lines
 * 29 to 31 of that copybook disappears entirely, because a handler that answers one request has no
 * first-entry-versus-re-entry distinction left to make. What was concretely wrong with carrying the
 * ordinal forward is that it participates in no key comparison anywhere in the program -- its only
 * other use is line 324, which moves it into a display field -- so it could only ever have been a
 * number a client had to be trusted with. This is why {@link TransactionListRequest} carries no
 * page-number component.
 *
 * <p>Assumptions: the cursor is one scalar identifier of sixteen characters, not a composite. Lines
 * 63 and 64 declare the leading and trailing browse identifiers as {@code PIC X(16)}, and the state
 * that follows adds an ordinal, an availability flag and a selected identifier without adding a
 * second key part.
 *
 * <p>Assumptions: lexical order over that identifier coincides with numeric order, and that is the
 * property making a character cursor correct. The entity's identity attribute is the {@code String}
 * member {@code tranId} bound to a constant-width character column, not an integer, and every
 * generated identifier fills all sixteen positions: the sequence scheme moves a browsed key into a
 * sixteen-digit numeric work field and back, so a value carries every leading zero. A comparison
 * over equal-width digit strings therefore orders them as magnitudes. A caller must not extend that
 * reading across the interest scheme's identifiers, which fill the same width but lead with a date.
 *
 * <h2>Five page-boundary messages, three of which speak about the top</h2>
 *
 * <p>Assumptions: transformation rule T8 carries a user-visible string across character for
 * character, so these are five separate constants and merging any two would re-word a screen. Three
 * of them differ only in how they refer to the top of the page, which is the hazard worth naming:
 * a reader searching for "top of the page" meets {@link #MESSAGE_ALREADY_AT_TOP} at line 248,
 * {@link #MESSAGE_AT_TOP} at line 608 and {@link #MESSAGE_REACHED_TOP} at line 676, and
 * consolidating them would produce output that is plausible and wrong.
 *
 * <p>Refactoring Rationale: the boundary condition is detected INSIDE the method that issues the
 * query, not deferred to a caller, because the reference emits the message from inside the browse
 * verb itself. Each of the three verb paragraphs sets the shared end-of-file condition and then
 * performs the screen send in the same breath, at lines 611, 645 and 679. Selecting the message
 * anywhere later would separate it from the read whose outcome chose it.
 *
 * <h2>What this class deliberately does not hold</h2>
 *
 * <p>Alternatives Considered: collecting every failing field and answering with all of them was
 * evaluated and rejected. The reference surfaces exactly one message per submission, because its
 * validation is a conditional selection that takes its first matching branch and sends the screen
 * straight back. The per-field array on {@code com.carddemo.common.error.ApiError}, typed by
 * {@code com.carddemo.common.validation.FieldValidationFlag} and carrying the asterisk marker for a
 * field left blank, therefore holds one element. That array is produced declaratively: the width
 * and digit constraints on {@link TransactionListRequest} carry the reference message of line 214
 * verbatim, and the shared exception advice renders them, so neither type is imported here and no
 * validation is restated. A second implementation of a rule the request type already states could
 * disagree with it while both went on compiling.
 *
 * <p>Assumptions: no drill-down is implemented here. Lines 185 to 187 accept either case of the
 * letter S as a row marker and lines 188 to 195 transfer control to the detail program, which in
 * the target is a client-side route change to the detail route. Nothing in this class decides which
 * screen follows, and the marker never reaches a request.
 *
 * <p>Trade-offs: the partially disabled branch at lines 196 to 202 is reproduced rather than
 * tidied, and it is latent reference behaviour rather than a defect to answer. Line 197 and line
 * 202 are both commented out, so an unrecognised row marker moves its message at lines 198 to 200
 * without raising the error switch and without sending the screen, and control FALLS THROUGH to the
 * start-key test at line 206 and on to the forward page at line 225. The observable consequence is
 * that the page is still rebuilt, which is what {@link #processEnterKey} does for a request whose
 * marker the target never receives. The cost is that a reader expecting symmetry with the
 * numeric-failure branch, which does send the screen at line 217, finds none.
 *
 * <p>Assumptions: there is no golden master for this class and none may be claimed for it.
 * {@code tests/README.md} records at lines 83 to 85 that the online programs cannot be run end to
 * end without a CICS runtime, which the runner does not have, and that only their extractable
 * field-validation logic is unit-tested. The reference here is such a program. Parity rests on the
 * transcribed logic and on the copybook contracts, and on nothing else.
 *
 * <p>Assumptions: no cross-context call is made from this class. The reference reads only its
 * transaction dataset, so there is no cross-reference lookup, no synchronous hop and consequently
 * no timeout to set and no resilience concern to answer.
 */
@Service
public class TransactionListService {

    /**
     * The binding every cursor of this list is sealed and opened under.
     *
     * <p>Assumptions: the binding is authenticated into a token but is not carried by it, so a
     * token sealed under this string cannot be redeemed by a query that opens under another. The
     * value names the resource being paged rather than the direction being paged in, because one
     * page's trailing token is replayed forward and its leading token is replayed backward, and two
     * direction-specific bindings would make a token issued by one step unusable by the other.
     *
     * <p>Refactoring Rationale: it is one constant rather than a literal at each call site because
     * a binding differing by a single character between sealing and opening fails authentication
     * and presents as a rejected cursor rather than as a mismatch, which is expensive to diagnose
     * and trivial to prevent.
     */
    public static final String CURSOR_BINDING = "transaction-list";

    /**
     * The message a backward step reports when there is no earlier page to reach.
     *
     * <p>Assumptions: reproduced character for character from line 248 of
     * {@code app/cbl/COTRN00C.cbl}, where the guard at line 245 admits a backward page only beyond
     * the first screen. It is one of the three that speak about the top and is the only one of them
     * carrying the word "already".
     */
    public static final String MESSAGE_ALREADY_AT_TOP = "You are already at the top of the page...";

    /**
     * The message a forward step reports when no further page follows.
     *
     * <p>Assumptions: reproduced character for character from line 270, taken when the availability
     * condition tested at line 267 is not set.
     */
    public static final String MESSAGE_ALREADY_AT_BOTTOM =
            "You are already at the bottom of the page...";

    /**
     * The message reported when positioning the browse finds no record at the requested key.
     *
     * <p>Assumptions: reproduced character for character from line 608, the not-found branch of the
     * browse-start paragraph at lines 605 to 611. It differs from {@link #MESSAGE_ALREADY_AT_TOP}
     * by the absence of the word "already" and from {@link #MESSAGE_REACHED_TOP} by its verb, and
     * all three are separate strings.
     */
    public static final String MESSAGE_AT_TOP = "You are at the top of the page...";

    /**
     * The message reported when a forward read reaches the end of the set.
     *
     * <p>Assumptions: reproduced character for character from line 642, the end-of-file branch of
     * the forward-read paragraph at lines 639 to 645.
     */
    public static final String MESSAGE_REACHED_BOTTOM =
            "You have reached the bottom of the page...";

    /**
     * The message reported when a backward read reaches the start of the set.
     *
     * <p>Assumptions: reproduced character for character from line 676, the end-of-file branch of
     * the backward-read paragraph at lines 673 to 679. This is the third of the three messages
     * about the top.
     */
    public static final String MESSAGE_REACHED_TOP = "You have reached the top of the page...";

    /**
     * The message the reference reports when a browse verb answers abnormally.
     *
     * <p>Assumptions: reproduced character for character from lines 615, 649 and 683, which spell
     * the noun in LOWER CASE. The same failure is spelled with an upper-case noun as
     * {@code 'Unable to lookup Transaction...'} at line 292 of {@code app/cbl/COTRN01C.cbl}, lines
     * 664 and 693 of {@code app/cbl/COTRN02C.cbl} and lines 463 and 492 of
     * {@code app/cbl/COBIL00C.cbl}. Those are a different constant belonging to those screens, and
     * the two are never merged.
     *
     * <p>Trade-offs: this constant is published and is not selected by any method here, because the
     * condition it describes is an abnormal data-access response rather than a page boundary, and
     * such a failure travels on {@code com.carddemo.common.error.GlobalExceptionHandler} instead of
     * on a page. Publishing it keeps the message catalogue of this screen complete and single
     * sourced; omitting it would leave the only surviving copy of a reference string in a
     * client-side catalogue with nothing tying it to the three lines it came from.
     */
    public static final String MESSAGE_LOOKUP_FAILED = "Unable to lookup transaction...";

    /**
     * The absence of a page-boundary message, expressed as an empty string rather than as null.
     *
     * <p>Assumptions: the reference clears its message work area at lines 102 and 103 before every
     * turn, so a turn that meets no boundary sends a blank message rather than no message at all. A
     * null would introduce a second spelling of the same state for a caller to test for.
     */
    public static final String NO_MESSAGE = "";

    /**
     * The ordered reads this class issues, and its only data-access collaborator.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Converts rows to list items, orders a scan for display and assembles the envelope.
     */
    private final TransactionMapper transactionMapper;

    /**
     * Builds the service over the two collaborators it reads and converts through.
     *
     * <p>Assumptions: both arrive through this constructor and are held immutably, so the class
     * carries no session state and no mutable instance state of any kind. That is what lets several
     * identical tasks behind one load balancer answer a request interchangeably, with no sticky
     * session and no shared session store.
     *
     * @param transactionRepository the ordered-read port onto the transaction master, of type
     *     {@code TransactionRepository}; must not be {@code null}
     * @param transactionMapper the row and envelope converter of this module, of type
     *     {@code TransactionMapper}; must not be {@code null}
     * @throws NullPointerException if either collaborator is {@code null}, because a service
     *     constructed without one would fail on its first request rather than at assembly
     */
    public TransactionListService(TransactionRepository transactionRepository,
            TransactionMapper transactionMapper) {
        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        this.transactionMapper =
                Objects.requireNonNull(transactionMapper, "transactionMapper must not be null");
    }

    /**
     * Answers one page of the transaction list, positioned by the request and bounded to the
     * reference screen's ten rows.
     *
     * <p>This is the migrated whole of the dispatch at lines 119 to 134 of
     * {@code app/cbl/COTRN00C.cbl} reduced to the three cases that read data: a backward step, a
     * forward step, and an entry that pressed no paging key. The remaining cases of that dispatch
     * are navigation, which the target performs client-side.
     *
     * <p>Assumptions: a supplied cursor takes precedence over a supplied start identifier, which is
     * the reference precedence rather than a choice made here. The backward paragraph overwrites the
     * record identification field from the leading stored key at lines 236 to 240 and the forward
     * paragraph overwrites it from the trailing stored key at lines 259 to 263, in both cases
     * without consulting the screen's own input field. Only an entry that pressed no paging key
     * reaches the input field, at lines 206 to 219.
     *
     * <p>Refactoring Rationale: the sealer is a parameter rather than a constructor collaborator,
     * and the shape is forced by a documented property of that type rather than chosen for
     * convenience. {@code com.carddemo.common.web.CursorToken} holds signing key material and is
     * deliberately not a bean anywhere in this migration -- no module publishes one, because a
     * default signing key in a configuration file is a committed secret, and this module's
     * configuration package is closed against adding one. Injecting it would therefore make an
     * application context fail to start on an unsatisfied dependency, which is worse than the
     * exposure it set out to close. Taking it per call leaves the component that owns the key
     * material supplying it, keeps this class a stateless bean with two collaborators, and lets a
     * unit test construct a real sealer beside a mocked repository.
     *
     * <p>Alternatives Considered: publishing a sealer bean from this package so that it could be
     * injected. Rejected because the decision about where a signing key comes from belongs with the
     * component that first needs one, and a bean published here would have to resolve a key in every
     * profile -- which is exactly how a development default becomes the committed secret the sealed
     * cursor exists to prevent.
     *
     * @param request the positioned list request, of type {@code TransactionListRequest}, carrying
     *     an optional start identifier, an optional opaque cursor and the direction that cursor is
     *     read in; must not be {@code null}
     * @param cursorToken the sealer this page's boundary tokens are minted with and the opener an
     *     incoming cursor is redeemed through, of type {@code CursorToken}; must not be
     *     {@code null}
     * @return the page envelope: up to ten list rows in ascending identifier order, the sealed
     *     tokens naming the first and last rows the caller actually receives, and whether a further
     *     page follows; an empty envelope naming no boundary when the position yields no rows
     * @throws NullPointerException if {@code request} or {@code cursorToken} is {@code null}
     * @throws com.carddemo.common.web.CursorToken.InvalidCursorException if a supplied cursor is not
     *     a token this sealer issued for this binding, or was issued longer ago than its lifetime
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionListItemResponse> listTransactions(TransactionListRequest request,
            CursorToken cursorToken) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(cursorToken, "cursorToken must not be null");

        TransactionListRequest.Direction direction = request.effectiveDirection();
        String cursorKey = openCursor(request, cursorToken);

        // Assumptions: the direction selects between the two paging paragraphs exactly as the
        //   attention identifier does at lines 125 to 128, where the seventh function key reaches
        //   the backward paragraph and the eighth reaches the forward one. An entry that pressed
        //   neither resolves to the forward direction, which is the branch line 121 takes into the
        //   enter-key paragraph, and the request type resolves that default for the same reason.
        List<Transaction> scanned =
                direction == TransactionListRequest.Direction.PREVIOUS
                        ? processPf7Key(cursorKey)
                        : processPf8Key(request, cursorKey);

        return assemblePage(scanned, direction, cursorToken);
    }

    /**
     * Selects the page-boundary message the reference screen would have shown for this outcome.
     *
     * <p>Assumptions: the envelope has four members and a message is not one of them, so the
     * selected text is answered separately rather than carried on the page. The published contract
     * of this service assigns these strings to a renderer reading the envelope's forward indicator
     * and its leading token, and the reference itself produces the message and the screen together
     * rather than nesting one inside the other.
     *
     * <p>Refactoring Rationale: each of the five reference conditions maps onto exactly one
     * observable property of a request and its answer, which is what lets five distinct strings
     * survive with no condition left sharing one. A backward step with no cursor is the guard at
     * line 245 failing, so it reports line 248. A backward step that yields nothing is the backward
     * read reaching the start of the set, so it reports line 676. A forward step from a cursor that
     * yields nothing is the availability condition at line 267 not being set, so it reports line
     * 270. An opening request whose start identifier matches no record is the browse position
     * failing to find one, so it reports line 608. An opening request over a set that holds nothing
     * is the first forward read reaching the end of the set, so it reports line 642.
     *
     * @param request the request the page answered, of type {@code TransactionListRequest}, whose
     *     direction, cursor presence and start identifier decide which condition was met; must not
     *     be {@code null}
     * @param page the envelope produced for that request by
     *     {@link #listTransactions(TransactionListRequest, CursorToken)}, of type
     *     {@code PageResponse<TransactionListItemResponse>}; must not be {@code null}
     * @return the applicable message reproduced verbatim from its originating line, or
     *     {@link #NO_MESSAGE} when the page met no boundary
     * @throws NullPointerException if {@code request} or {@code page} is {@code null}
     */
    public String boundaryMessage(TransactionListRequest request,
            PageResponse<TransactionListItemResponse> page) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(page, "page must not be null");

        boolean yieldedNothing = page.items().isEmpty();
        if (request.effectiveDirection() == TransactionListRequest.Direction.PREVIOUS) {
            // Assumptions: the absence of a cursor is what the reference expresses as an ordinal of
            //   one or less at line 245, because an opening request is the first page by
            //   construction and the ordinal was the only thing the reference had to test. Both
            //   forms answer the same question -- is there an earlier page to reach -- and the
            //   cursor form needs no counter to answer it.
            if (!request.hasCursor()) {
                return MESSAGE_ALREADY_AT_TOP;
            }
            return yieldedNothing ? MESSAGE_REACHED_TOP : NO_MESSAGE;
        }
        if (!yieldedNothing) {
            return NO_MESSAGE;
        }
        if (request.hasCursor()) {
            return MESSAGE_ALREADY_AT_BOTTOM;
        }
        // Assumptions: a start identifier that matched no record is the not-found branch at lines
        //   605 to 611 rather than the end-of-file branch at lines 639 to 645, and the two report
        //   different strings. Positioning is by equality because the greater-or-equal option at
        //   line 597 is commented out, so an unmatched key fails to position at all, whereas an
        //   opening request that supplied no identifier positions successfully and then meets the
        //   end of the set on its first read.
        return request.hasTransactionIdFilter() ? MESSAGE_AT_TOP : MESSAGE_REACHED_BOTTOM;
    }

    /**
     * Redeems the request's opaque cursor into the raw key a repository predicate compares against.
     *
     * <p>Assumptions: an absent cursor is answered as {@code null} rather than as an empty string,
     * because the three spellings of absence the request may carry are already folded into one
     * answer by its own accessor, and a second spelling introduced here would be one more value a
     * caller could test for wrongly.
     *
     * <p>Refactoring Rationale: the token is opened rather than read, and the raw key never leaves
     * this class. The reference carried its browse keys in a communication area the client handed
     * back on the following turn, so continuity depended on the client returning storage intact and
     * nothing prevented it from returning storage it had altered. A sealed token authenticated
     * against this binding cannot be altered, extended or moved to another query, and what it
     * carries stays out of the response body.
     *
     * @param request the list request whose cursor is to be redeemed, of type
     *     {@code TransactionListRequest}; must not be {@code null}
     * @param cursorToken the opener the token must have been sealed by, of type
     *     {@code CursorToken}; must not be {@code null}
     * @return the raw sixteen-character key the scan resumes from, or {@code null} when the request
     *     supplied no position and the scan is to begin from its start key instead
     * @throws com.carddemo.common.web.CursorToken.InvalidCursorException if the supplied token is
     *     not one this opener issued for this binding, or was issued longer ago than its lifetime
     */
    private String openCursor(TransactionListRequest request, CursorToken cursorToken) {
        if (!request.hasCursor()) {
            return null;
        }
        return cursorToken.open(CURSOR_BINDING, request.cursor());
    }

    /**
     * Transcribes {@code PROCESS-ENTER-KEY} at line 279's caller, the paragraph declared at line
     * 146: positions the opening page from the request's start identifier, or from the start of the
     * key space when none was supplied.
     *
     * <p>Assumptions: an unsupplied identifier begins the scan at the low end of the key space. Line
     * 206 tests the input field against spaces or low values and line 207 answers it by moving low
     * values into the record identification field, which is the lowest point of the collating
     * sequence, so the opening read is an unfiltered ascending one.
     *
     * <p>Assumptions: an entry that pressed no paging key always rebuilds from the start of its
     * current position rather than continuing from anywhere. Line 224 resets the screen ordinal to
     * zero immediately before line 225 performs the forward page, so the reference discards whatever
     * position the previous turn had reached. A request that supplies no cursor therefore yields the
     * first page of the position its start identifier names.
     *
     * <p>Refactoring Rationale: a supplied identifier is honoured INCLUSIVELY, by reading the row it
     * names and then the rows after it, and that pairing is what the reference does rather than an
     * embellishment. Its browse-start at line 593 positions by equality, the greater-or-equal option
     * at line 597 being commented out, and line 285 then suppresses the positioning read for an
     * entry that pressed no paging key, so the first read of the fill loop at line 297 returns the
     * record AT that key. Resuming strictly after the identifier instead would omit the very row the
     * caller asked to start at, and the omission would look like missing data rather than like a
     * comparison choice.
     *
     * <p>Assumptions: an identifier matching no record yields no rows at all, which is the
     * equality positioning of line 593 failing rather than an empty result from a scan. That is the
     * not-found branch at lines 605 to 611, whose message {@link #boundaryMessage} selects, and it
     * is why this method answers an unmatched identifier with an empty list rather than with the
     * rows that happen to follow it.
     *
     * <p>Assumptions: the identifier arrives at its declared width of sixteen digits, so no padding
     * is applied here. The request type constrains the component to sixteen digits or nothing, and
     * that width is the record key's own -- {@code TRAN-ID PIC X(16)} at line 5 of
     * {@code app/cpy/CVTRA05Y.cpy} -- so a value reaching this method is already a whole key rather
     * than a prefix of one.
     *
     * @param request the list request whose start identifier positions the opening page, of type
     *     {@code TransactionListRequest}; must not be {@code null}
     * @return the rows the opening scan read, ascending by identifier, holding up to one row beyond
     *     the page so that forward availability can be settled, and empty when the position matches
     *     nothing
     */
    private List<Transaction> processEnterKey(TransactionListRequest request) {
        if (!request.hasTransactionIdFilter()) {
            return transactionRepository.findAllByOrderByTranIdAsc(probeBoundedLimit());
        }

        String startKey = request.transactionIdFilter();
        Optional<Transaction> positionedRow = transactionRepository.findById(startKey);
        if (positionedRow.isEmpty()) {
            return List.of();
        }

        // Assumptions: the two reads together are the reference's one positioned browse, and the
        //   second is bounded to the page size rather than to the page size plus one because the
        //   positioned row already occupies the first of the eleven. Asking for eleven here would
        //   read twelve rows in total and report a further page one row too early.
        List<Transaction> followingRows = transactionRepository
                .findByTranIdGreaterThanOrderByTranIdAsc(startKey, Limit.of(TransactionMapper.PAGE_SIZE));

        List<Transaction> scanned = new ArrayList<>(followingRows.size() + 1);
        scanned.add(positionedRow.get());
        scanned.addAll(followingRows);
        return scanned;
    }

    /**
     * Transcribes {@code PROCESS-PF7-KEY} at line 234: reads the page preceding the caller's
     * position, or refuses the step when there is no earlier page to reach.
     *
     * <p>Assumptions: the reference positions this step from the LEADING key of the page on display,
     * moving it into the record identification field at lines 236 to 240, which is the token this
     * class seals as the envelope's leading boundary. The cursor a backward request replays is
     * therefore that same key.
     *
     * <p>Refactoring Rationale: the guard at line 245, which admits a backward page only beyond the
     * first screen, becomes the presence of a cursor. The reference could only ask its screen
     * ordinal because the ordinal was the state it had; a request carrying no position is the
     * opening page by construction, so the same question is answered without a counter -- which is
     * what allows the ordinal to be dropped entirely rather than reproduced.
     *
     * @param cursorKey the raw leading key of the page the caller holds, of type {@code String}, or
     *     {@code null} when the caller holds no page and no backward step is expressible
     * @return the rows the backward scan read, DESCENDING by identifier and nearest the caller's
     *     position first, holding up to one row beyond the page, and empty when no earlier page
     *     exists
     */
    private List<Transaction> processPf7Key(String cursorKey) {
        if (cursorKey == null) {
            return List.of();
        }
        return processPageBackward(cursorKey);
    }

    /**
     * Transcribes {@code PROCESS-PF8-KEY} at line 257: reads the page following the caller's
     * position, or builds the opening page when the caller holds none.
     *
     * <p>Assumptions: the reference positions this step from the TRAILING key of the page on
     * display, moving it into the record identification field at lines 259 to 263, which is the
     * token this class seals as the envelope's trailing boundary.
     *
     * <p>Refactoring Rationale: the availability guard at line 267 is NOT reproduced as a
     * server-side test, and the difference is one of where the answer lives rather than of what it
     * is. The reference could consult a flag it had itself set on the previous turn; a stateless
     * handler holds no previous turn, and the client that received the forward indicator is the
     * party able to withhold the step. A forward request whose scan returns nothing therefore
     * produces the empty page and the message of line 270, which is the same outcome the guard
     * produced, reached one request later.
     *
     * @param request the list request, of type {@code TransactionListRequest}, consulted only when
     *     no cursor was supplied so that the opening page can be positioned from its start
     *     identifier; must not be {@code null}
     * @param cursorKey the raw trailing key of the page the caller holds, of type {@code String},
     *     or {@code null} when the caller holds no page
     * @return the rows the forward scan read, ascending by identifier, holding up to one row beyond
     *     the page, and empty when nothing follows the caller's position
     */
    private List<Transaction> processPf8Key(TransactionListRequest request, String cursorKey) {
        if (cursorKey == null) {
            return processEnterKey(request);
        }
        return processPageForward(cursorKey);
    }

    /**
     * Transcribes {@code PROCESS-PAGE-FORWARD} at line 279: one ascending keyset read standing in
     * for a browse position, ten forward reads and an eleventh probe.
     *
     * <p>Refactoring Rationale: the paragraph's four collaborating file verbs become one query.
     * Line 281 starts the browse, line 285 conditionally consumes the positioning record, the loop
     * at lines 297 to 303 fills ten slots, line 308 probes for an eleventh and line 322 ends the
     * browse. A bounded ordered query expresses all of it, and the two statements that have no
     * counterpart are the ones that manage a cursor the query does not hold.
     *
     * <p>Assumptions: the comparison is strict, so the row the cursor names is excluded and the page
     * begins at the identifier after it. That is what line 285 achieves for this step by consuming
     * the positioning record -- the row already on display -- before the fill loop runs.
     *
     * @param cursorKey the raw identifier of the last row the caller already holds, of type
     *     {@code String}; must not be {@code null}
     * @return the following rows, ascending by identifier, holding up to one row beyond the page so
     *     that the surplus row can settle forward availability
     */
    private List<Transaction> processPageForward(String cursorKey) {
        return transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(cursorKey,
                probeBoundedLimit());
    }

    /**
     * Transcribes {@code PROCESS-PAGE-BACKWARD} at line 333: one descending keyset read standing in
     * for a browse position, ten backward reads and a further probe.
     *
     * <p>Refactoring Rationale: the descending order is the query's, and it mirrors the reference
     * rather than reversing it. Line 335 starts the browse, line 339 conditionally consumes the
     * positioning record, line 349 begins the fill at slot ten, the loop at lines 351 to 357 counts
     * down to slot one as it reads backwards at line 352, line 360 performs the further backward
     * read and line 371 ends the browse. Reading nearest-first and landing the rows into display
     * order afterwards is therefore the reference's own arrangement; ordering ascending in the query
     * instead would let the row cap keep the earliest rows of the whole set rather than the ten
     * immediately preceding the caller, which is a page nobody asked for.
     *
     * <p>Assumptions: the rows this returns are reversed into ascending order before the envelope is
     * assembled, and {@link TransactionMapper#orderForDisplay} performs that step. The reference
     * shows the same asymmetry: it reads descending and displays ascending, because the first row it
     * reads lands in the last slot.
     *
     * @param cursorKey the raw identifier of the first row the caller already holds, of type
     *     {@code String}; must not be {@code null}
     * @return the preceding rows, DESCENDING by identifier and nearest the caller's position first,
     *     holding up to one row beyond the page
     */
    private List<Transaction> processPageBackward(String cursorKey) {
        return transactionRepository.findByTranIdLessThanOrderByTranIdDesc(cursorKey,
                probeBoundedLimit());
    }

    /**
     * Builds the envelope from a raw scan: separates the probe row, orders the remainder for
     * display, seals both boundaries and reports forward availability.
     *
     * <p>Refactoring Rationale: the boundary tokens are sealed from the FIRST AND LAST ROWS THE
     * CALLER RECEIVES, taken after the probe row has been separated and after the rows have been
     * ordered for display. Sealing the trailing boundary from the probe row instead would advance
     * the cursor one row too far and silently skip that row on the following request. The reference
     * captures the same two boundaries from displayed slots rather than from the probe: line 393
     * captures the leading identifier in the branch for slot one and lines 438 and 439 capture the
     * trailing identifier in the branch for slot ten, while the probe read at line 308 never reaches
     * the populating paragraph at all.
     *
     * <p>Refactoring Rationale: the trailing boundary names the page actually returned even when
     * that page is short, where the reference refreshes it only on a full page because its capture
     * sits inside the branch for slot ten. What was wrong with the reference arrangement is that a
     * client paging forward from a stale trailing key re-reads rows it has already seen, and does so
     * silently.
     *
     * <p>Assumptions: a page carrying no rows names no boundary and reports no further page. That is
     * line 315, which sets the no-further-page condition whenever the fill read nothing, and it is
     * also what the envelope requires: reporting a further page without a trailing position would
     * tell a caller to continue with nowhere to continue from.
     *
     * @param scanned the rows the scan returned, of type {@code List<Transaction>}, in the query's
     *     own order and holding up to one row beyond the page; must not be {@code null}
     * @param direction the direction the scan was issued in, of type
     *     {@code TransactionListRequest.Direction}, which decides both whether a reversal is needed
     *     and how forward availability is established; must not be {@code null}
     * @param cursorToken the sealer the two boundary tokens are minted with, of type
     *     {@code CursorToken}; must not be {@code null}
     * @return the assembled page envelope, carrying at most the page size in rows
     * @throws NullPointerException if a returned row carries no identifier, which the envelope's own
     *     conversion refuses because such a row could not be paged away from
     */
    private PageResponse<TransactionListItemResponse> assemblePage(List<Transaction> scanned,
            TransactionListRequest.Direction direction, CursorToken cursorToken) {
        boolean probeRowFound = hasProbeRow(scanned);
        List<Transaction> displayOrderedRows =
                transactionMapper.orderForDisplay(retainedRows(scanned), direction);

        if (displayOrderedRows.isEmpty()) {
            return transactionMapper.toListPage(displayOrderedRows, null, null, false);
        }

        String firstKeyToken =
                sealBoundaryKey(cursorToken, displayOrderedRows.get(0).getTranId());
        String lastKeyToken = sealBoundaryKey(cursorToken,
                displayOrderedRows.get(displayOrderedRows.size() - 1).getTranId());

        return transactionMapper.toListPage(displayOrderedRows, firstKeyToken, lastKeyToken,
                forwardAvailability(direction, probeRowFound));
    }

    /**
     * Reports whether the scan returned the surplus row that answers forward availability.
     *
     * <p>Assumptions: the surplus row is recognised by the scan exceeding the page size, which is
     * sound only because every read is bounded to the page size plus one. That is the same fact line
     * 308 establishes by performing one read past a full page: a read that succeeds means something
     * follows, and a read that does not means nothing does.
     *
     * @param scanned the rows the scan returned, of type {@code List<Transaction>}; must not be
     *     {@code null}
     * @return {@code true} when the scan returned more rows than a page carries, so a further page
     *     exists in the direction scanned; {@code false} otherwise
     */
    private boolean hasProbeRow(List<Transaction> scanned) {
        return scanned.size() > TransactionMapper.PAGE_SIZE;
    }

    /**
     * Separates the rows a caller receives from the surplus row that only settled availability.
     *
     * <p>Assumptions: the surplus row is always the LAST element of the scan, in both directions,
     * because each query orders rows away from the cursor and the cap admits one extra at the far
     * end. On a forward scan that is the row with the highest identifier; on a backward scan, which
     * is ordered descending, it is the row with the lowest. Trimming the first element instead would
     * discard the row nearest the caller's position, which is the one row the page must carry.
     *
     * @param scanned the rows the scan returned, of type {@code List<Transaction>}, holding up to
     *     one row beyond the page; must not be {@code null}
     * @return the rows the caller receives, being the scan with its surplus row removed, or the scan
     *     unchanged when it carried no surplus row
     */
    private List<Transaction> retainedRows(List<Transaction> scanned) {
        if (!hasProbeRow(scanned)) {
            return scanned;
        }
        return scanned.subList(0, TransactionMapper.PAGE_SIZE);
    }

    /**
     * Decides whether a further page follows the page being returned.
     *
     * <p>Assumptions: on a forward page this is the probe read's outcome and nothing else, which is
     * lines 309 to 313 setting the availability condition to yes or to no from that read alone.
     *
     * <p>Refactoring Rationale: on a backward page it is reported unconditionally, and that is the
     * reference behaviour rather than a convenience. The backward paragraph never clears the
     * availability condition: line 242 sets it to yes before the backward page is performed, and the
     * only use the backward path makes of its own further read at line 360 is to adjust the screen
     * ordinal at lines 361 to 368 -- an ordinal this migration drops. The reading is also the only
     * one that can be true, because a caller stepping backward came from the page that lies ahead of
     * the one being returned, so a forward step from here necessarily has somewhere to go. Deriving
     * it from the backward probe instead would answer a different question, namely whether a further
     * page exists BEHIND this one, and a client following that indicator forward would be sent back
     * the way it came.
     *
     * @param direction the direction the scan was issued in, of type
     *     {@code TransactionListRequest.Direction}; must not be {@code null}
     * @param probeRowFound whether the scan returned the surplus row, of type {@code boolean},
     *     which answers availability on a forward page and is not consulted on a backward one
     * @return {@code true} when a further page follows the page being returned; {@code false}
     *     otherwise
     */
    private boolean forwardAvailability(TransactionListRequest.Direction direction,
            boolean probeRowFound) {
        if (direction == TransactionListRequest.Direction.PREVIOUS) {
            return true;
        }
        return probeRowFound;
    }

    /**
     * Seals one boundary identifier into the opaque token the envelope carries.
     *
     * <p>Refactoring Rationale: the raw key is sealed rather than published, and the envelope
     * refuses an unsealed one outright. The reference had no equivalent exposure because its browse
     * keys never left the region; over HTTP a raw key placed in a response body is held by the
     * client and replayed, so a token authenticated against this binding is what keeps a record key
     * out of the response while still letting the client resume the scan.
     *
     * @param cursorToken the sealer the token is minted with, of type {@code CursorToken}; must not
     *     be {@code null}
     * @param boundaryKey the raw identifier of the first or last row this page returned, of type
     *     {@code String}; must not be {@code null} and must not be blank
     * @return the sealed token naming that row, in the shape the envelope requires
     * @throws NullPointerException if {@code boundaryKey} is {@code null}, which would mean a
     *     returned row carried no identifier
     * @throws IllegalArgumentException if {@code boundaryKey} is blank or longer than a sealed
     *     cursor may carry, neither of which a declared-width record key can be
     */
    private String sealBoundaryKey(CursorToken cursorToken, String boundaryKey) {
        return cursorToken.seal(CURSOR_BINDING, boundaryKey);
    }

    /**
     * Bounds one scan to the page plus the single row that settles forward availability.
     *
     * <p>Assumptions: the cap is the page size plus one and the addition is the reference's eleventh
     * read at line 308, not a margin chosen here. The page size itself is consumed from
     * {@link TransactionMapper#PAGE_SIZE} rather than restated, so the ten that this arithmetic
     * rests on cannot drift from the ten the envelope's rows are converted against.
     *
     * @return the row cap every paging query in this class is issued with, admitting one row beyond
     *     a full page
     */
    private Limit probeBoundedLimit() {
        return Limit.of(TransactionMapper.PAGE_SIZE + 1);
    }
}
