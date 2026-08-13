package com.carddemo.card.api;

import com.carddemo.card.dto.AdminCardDetail;
import com.carddemo.card.dto.CardConflictError;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.dto.CardLookupRequest;
import com.carddemo.card.dto.CardPageQuery;
import com.carddemo.card.dto.CardSummary;
import com.carddemo.card.dto.CardUpdateRequest;
import com.carddemo.card.service.CardAdminViewService;
import com.carddemo.card.service.CardListService;
import com.carddemo.card.service.CardRecordConflictException;
import com.carddemo.card.service.CardUpdateService;
import com.carddemo.card.service.CardViewService;
import com.carddemo.common.control.OnlineWriteGateExempt;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the five card operations the published contract declares.
 *
 * <h2>What this adapter is</h2>
 *
 * <p>This is the HTTP surface of the three reference card transactions: the list {@code CCLI}, the detail
 * {@code CCDL} and the update {@code CCUP}, defined at {@code app/csd/CARDDEMO.CSD} lines 347 to 375 and
 * driven by {@code app/cbl/COCRDLIC.cbl}, {@code app/cbl/COCRDSLC.cbl} and {@code app/cbl/COCRDUPC.cbl}.
 * Five operations serve three transactions because the migration splits two concerns the reference
 * merged: the number-to-row resolution the list screen performed inline becomes its own lookup, and the
 * administrative disclosure of a full card number becomes its own route behind its own authority. The
 * roster, its count and the evidence for the count are held once in this package's
 * {@code package-info.java}, and are not restated here.
 *
 * <p>Refactoring Rationale: each handler binds a request, lets a declared constraint judge it and hands
 * the outcome to a service, and none of them decides anything. The reference had no such seam: a single
 * program held terminal input, field edits and file verbs together, which is why
 * {@code app/cbl/COCRDUPC.cbl} runs to 1560 lines and declares its six validation-flag triads at lines
 * 57 to 80 alongside the file operations they guard. Those six edits are transcribed into
 * {@link CardUpdateService}, the browse into {@link CardListService}, and this class keeps only the three
 * things an adapter can own. Splitting it that way is what lets a reader confirm a transcribed rule by
 * reading one service method against one reference paragraph, instead of reading a program that does
 * everything at once.
 *
 * <h2>Why no session state is reconstructed here</h2>
 *
 * <p>Refactoring Rationale: the reference re-entry discriminator has no counterpart on any handler
 * below. {@code app/cpy/COCOM01Y.cpy} declares {@code CDEMO-PGM-CONTEXT} at lines 29 to 31, whose two
 * condition names distinguish a first arrival from a repeat one, and
 * {@code app/cbl/COCRDLIC.cbl} keeps its own local equivalent at lines 130 to 132. A pseudo-conversational
 * task needed that flag because it ended at every screen turn and had to know, on being re-entered,
 * whether the fields it was looking at had already been judged once. The reference field highlight is
 * gated on exactly that flag, at {@code app/cpy/CSSETATY.cpy:20}. A request handled once and answered
 * once has no turn to count, so error presentation here is carried entirely by the response body and
 * nothing in this class remembers a previous request.
 *
 * <p>Assumptions: every user-visible sentence this context can answer with is a named constant on the
 * service or on the shared error type, carried across character for character, and none is re-spelled in
 * this class. The width those sentences are held to is the reference's own {@code X(75)}, which five
 * independent declarations agree on: {@code app/cpy/CVCRD01Y.cpy:28} for {@code CCARD-ERROR-MSG} and
 * {@code :29} for {@code CCARD-RETURN-MSG}, then {@code app/cbl/COCRDLIC.cbl:117},
 * {@code app/cbl/COCRDSLC.cbl:134} and {@code app/cbl/COCRDUPC.cbl:173}. Their off-sentinels do NOT
 * agree and are not treated as though they did: the copybook return field uses {@code LOW-VALUES} at
 * {@code app/cpy/CVCRD01Y.cpy:30}, all three working-storage copies use {@code SPACES} at
 * {@code :118}, {@code :135} and {@code :174}, and the copybook error field declares no sentinel at all.
 * Keeping the sentences in one place per service is what stops a second spelling of one of them
 * appearing here and diverging.
 *
 * <h2>Where disclosure is decided</h2>
 *
 * <p>Assumptions: the rendering of a primary account number is settled in
 * {@code com.carddemo.card.mapper.CardMapper} and is not adjustable from here. Every response type below
 * except the administrative one carries the masked rendering only, and no handler adds a diagnostic,
 * trace or debug member that would reintroduce a full number. The card verification value is absent
 * rather than masked: no schema in {@code openapi/card-api.yaml} declares a member for it, no response
 * type in this context has one, and the reference never displayed or accepted it either -- the value
 * appears in none of the three symbolic maps nor in any of the three mapsets. This is a
 * platform-capability difference and not a repair of the reference: {@code app/csd/CARDDEMO.CSD} defines
 * all three of these transactions {@code CONFDATA(NO)} at lines 353, 363 and 374, beside
 * {@code DUMP(YES) TRACE(YES)}, so the platform offered no suppression of confidential request data and
 * the target supplies one the reference could not have used.
 *
 * <h2>Where authorization is decided</h2>
 *
 * <p>Alternatives Considered: method-level authorization on the administrative handler, which would put
 * the requirement where a reader of this file sees it. Rejected on two specific grounds. It would need
 * {@code @EnableMethodSecurity} switched on in {@code com.carddemo.card.config.SecurityConfig} and would
 * then express the same requirement a second time, so the filter chain and the annotation could
 * disagree and only one of them would be the one that ran. And it would have to spell an authority
 * literal, whereas the naming form the shared token converter produces is owned by that converter rather
 * than settled here, so a literal written from this side could stop matching without anything failing to
 * compile. The chain in {@code SecurityConfig} therefore holds the whole matrix: its rule list places
 * the administrative pattern ahead of both card patterns, and
 * {@code com.carddemo.card.config.CardApiContractTest} asserts that the authority each rule enforces is
 * the one the contract publishes as {@code x-required-authority} for the same path.
 *
 * <p>Trade-offs: the cost of that choice is that the requirement is not visible at the handler site, so
 * a reader of this file alone cannot tell that the administrative read is restricted. It is accepted
 * because the contract test above makes the pairing checkable at build time, which a comment here would
 * not, and because no client-supplied field is consulted anywhere in this class: the group membership
 * that decides the administrative route arrives as a signed claim and is read only by the chain.
 *
 * <h2>Where failures are turned into responses</h2>
 *
 * <p>Assumptions: no handler below catches anything, and every failure leaves this class as the
 * exception the service raised. {@code com.carddemo.common.error.GlobalExceptionHandler}, registered by
 * {@code com.carddemo.card.CardApplication}, is the single place a status and an error body are built:
 * it answers a refused input with 400 and the per-field array, an unmatched identifier with 404, and a
 * concurrency conflict with 409 carrying {@code ApiError.COACTUPC_RECORD_CHANGED}, the reference sentence
 * from {@code app/cbl/COCRDUPC.cbl:207-208}.
 *
 * <p>Alternatives Considered: catching a service failure here to add context before rethrowing it.
 * Rejected because one of the conflicts that advice recognises is identified by walking the cause chain
 * for a fully-qualified class name rather than by catching a type -- the shared kernel declares no
 * persistence dependency, so the optimistic-lock type is not on its compile classpath and cannot be
 * named there. Wrapping a failure in a type of this class's own choosing would leave that walk looking
 * at the wrapper, and the answer would silently become a 500 where the contract publishes a 409. Letting
 * the original propagate untouched is what keeps the recognition working.
 *
 * <p>Trade-offs: this class carries no springdoc annotation, following the convention every adapter in
 * this migration follows: the hand-authored contract is the document of record, and annotating a handler
 * would put a second, editable copy of it in the code and make drift expressible at all.
 */
@RestController
public class CardController {

    /** The collection path the browse and the lookup sit at or beneath. */
    public static final String CARDS_PATH = "/api/v1/cards";

    /**
     * The path of the listing, a literal segment beneath the collection.
     *
     * <p>Refactoring Rationale: the listing was a {@code GET} on {@link #CARDS_PATH} taking its account
     * narrowing, cursor and direction as QUERY PARAMETERS. It is a {@code POST} on this literal path
     * taking them in a body, because a query string is part of the request line and the load balancer
     * writes the request line into its access log itself, before any application code runs. The lookup
     * below already moved a card number off the request line on exactly that basis, and the migration's
     * sensitive-data logging contract names ACCOUNT AND CUSTOMER IDENTIFIERS in the same sentence as the
     * primary account number -- so leaving the account number in the query string applied that finding
     * to only one of the two values it covers. No masking this service performs can bound an access log
     * written before its code runs.</p>
     *
     * <p>Alternatives Considered: a {@code POST} on {@link #CARDS_PATH} itself, which would need no new
     * segment. Rejected because a {@code POST} to a collection reads as a create, and this contract
     * publishes no create for cards deliberately; naming the segment keeps a read looking like a read.
     * The literal resolves ahead of {@link #CARD_PATH} in Spring MVC for the same reason
     * {@link #LOOKUP_PATH} does -- a literal pattern is selected before a templated one -- and a sealed
     * selector cannot spell either literal, because it begins with no version marker and is far
     * longer.</p>
     */
    public static final String SEARCH_PATH = CARDS_PATH + "/search";

    /** The path of the number-to-row lookup, the one operation that accepts a card number. */
    public static final String LOOKUP_PATH = CARDS_PATH + "/lookup";

    /** The path of one card, addressed by its opaque selector. */
    public static final String CARD_PATH = CARDS_PATH + "/{cardKey}";

    /** The path of the administrative read that discloses a full card number. */
    public static final String ADMIN_CARD_PATH = "/api/v1/admin/cards/{cardKey}";

    /**
     * The direction value that reads the page preceding the cursor.
     *
     * <p>Assumptions: the spelling is lower case and is the contract's own, drawn from the two-member
     * paging vocabulary the document publishes rather than chosen here. It is compared as a value rather
     * than bound to an enum because the published schema also declares a default of the forward member,
     * so an ABSENT direction has a defined meaning that this class must honour. What that comparison
     * does not do is enforce the two-member domain: a value outside it reads forward instead of earning a
     * refusal, and the enforcement belongs to the schema-bearing request type where every other member of
     * that body is already constrained. Adding a second check here would put the same domain in two
     * places and let them disagree.</p>
     */
    public static final String DIRECTION_PREVIOUS = "previous";

    /**
     * The subordinate code the published contract assigns to the caller-detected staleness.
     *
     * <p>Refactoring Rationale: the three codes below are declared here because the contract at
     * {@code src/main/resources/openapi/card-api.yaml} says of this status that "Each carries its own
     * subordinate code", names all three values in its examples, and states that the code is what
     * distinguishes the conditions -- while every 409 this context sent carried the empty subordinate
     * code, because the shared renderer emits {@link ApiError#NO_SECONDARY_CODE} and this class copied
     * whatever it emitted. A client following the document to tell the three conditions apart therefore
     * read an empty string on all three. The values are constants rather than literals at the emitting
     * switch so that {@code CardApiContractGateTest} can compare them against the document's own
     * examples; a literal would let the two drift with nothing to notice.
     *
     * <p>Assumptions: this code accompanies {@link CardUpdateService#MESSAGE_RECORD_CHANGED} and is the
     * only one of the three whose body carries the refreshed card, which is what the contract says of
     * it and what {@link #onCardRecordConflict} produces. It corresponds to
     * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} at {@code app/cbl/COCRDUPC.cbl:207-208}: the condition
     * this service detects ITSELF by comparing the submitted token against the stored one, before any
     * write is attempted.
     */
    public static final String CONFLICT_CODE_DATA_CHANGED = "CARD-DATA-CHANGED";

    /**
     * The subordinate code the published contract assigns to a row that could not be taken for update.
     *
     * <p>Assumptions: this code accompanies {@link CardUpdateService#MESSAGE_COULD_NOT_LOCK} and
     * corresponds to {@code 88 COULD-NOT-LOCK-FOR-UPDATE} at
     * {@code app/cbl/COCRDUPC.cbl:205-206}. Two paths reach it, and neither is this service asking for a
     * hold, because it asks for none: a collaborator raising the contention with
     * {@link RecordConflictException.Kind#LOCK_UNAVAILABLE}, and the persistence provider reporting that
     * the row itself could not be acquired. The body carries no refreshed card, because a row that could
     * not be read has no current representation to return -- which is exactly what the contract's own
     * example for this condition shows.
     */
    public static final String CONFLICT_CODE_LOCK_NOT_ACQUIRED = "CARD-LOCK-NOT-ACQUIRED";

    /**
     * The subordinate code the published contract assigns to a write that did not take effect.
     *
     * <p>Assumptions: this code accompanies {@link CardUpdateService#MESSAGE_UPDATE_FAILED} and
     * corresponds to {@code 88 LOCKED-BUT-UPDATE-FAILED} at {@code app/cbl/COCRDUPC.cbl:209-210}, which
     * the reference sets when the row was held, the before-image comparison passed, and the REWRITE
     * itself returned other than normal, tested at {@code :1488-1491}. The target reaches the same state
     * when the flush inside {@code CardUpdateService.writeProcessing} raises an optimistic-lock failure:
     * the row was read, this service's own comparison passed, and the write did not apply.
     *
     * <p>Refactoring Rationale: before this handler existed, that failure was answered by the shared
     * advice as {@link CardUpdateService#MESSAGE_RECORD_CHANGED}, so the one condition the contract
     * spells "the write itself did not succeed" was reported with the sentence and the meaning of a
     * different condition -- and no code path anywhere produced this value at all, leaving a third of the
     * published conflict contract unreachable. Alternatives Considered: leaving it unreachable and
     * withdrawing it from the document. Rejected because the condition is real -- a concurrent writer
     * committing between this service's comparison and its flush produces exactly it -- so withdrawing
     * the code would have removed a client's only way to distinguish a retryable write failure from a
     * stale read.
     */
    public static final String CONFLICT_CODE_WRITE_NOT_APPLIED = "CARD-WRITE-NOT-APPLIED";

    /**
     * The criteria an absent request body stands for.
     *
     * <p>Assumptions: an absent body is an empty query rather than a refusal, so the opening request of
     * the list screen may carry no body at all. The reference screen opens with no criteria entered, and
     * obliging a caller to send a body of three nulls to say so would make the commonest request the most
     * awkward one.</p>
     */
    private static final CardPageQuery EMPTY_QUERY = new CardPageQuery(null, null, null);

    // WHY : Assumptions: the two collaborators are named for what they do to the store rather than after
    //       their types, because the split between them is the only structural fact a reader of this
    //       class needs: four of the five operations read and exactly one writes, which is why exactly
    //       one of them is transactional and this class declares no transaction at all.
    /** Serves the browse, the two detail reads and the lookup. */
    private final CardListService reads;

    /**
     * Serves the two masked single-card reads.
     *
     * <p>Assumptions: this is the transcription of the reference detail program, so it and not the browse
     * service owns the field-state gates and the verbatim sentences those reads report.</p>
     */
    private final CardViewService views;

    /**
     * Serves the one read that renders a full primary account number.
     *
     * <p>Assumptions: the reference is held as its own type rather than reached through {@link #views},
     * so the disclosure is visible in this class's wiring instead of hidden behind an argument.</p>
     */
    private final CardAdminViewService adminViews;

    /** Serves the one edit this context publishes. */
    private final CardUpdateService writes;

    /**
     * Composes the shared half of a conflict body, so this class composes only the card-specific half.
     *
     * <p>Assumptions: the shared advice is held as a collaborator rather than reached statically, because
     * it carries the deployment's clock and reads the request's correlation identity. Holding it is what
     * lets the extended conflict body below reuse the code, the sentence selection, the subsystem, the
     * correlation identity, the masked path and the timestamp instead of restating any of them.</p>
     */
    private final GlobalExceptionHandler conflicts;

    /**
     * Binds the read service and the update service.
     *
     * <p>Assumptions: both collaborators arrive through the constructor and neither is assignable
     * afterwards, so an instance of this class cannot exist in a partly-wired state. Rejecting a null
     * argument here rather than on first use is what makes a wiring mistake a startup failure naming the
     * missing collaborator, instead of a request-time failure naming a line inside a handler.</p>
     *
     * <p>Refactoring Rationale: two collaborators became four. The single-card reads were served by
     * methods on the browse service, while {@code CardViewService} and {@code CardAdminViewService} --
     * the transcriptions of the reference detail program, carrying its field-state gates and its verbatim
     * sentences -- had no caller at all. The two were not equivalent: the browse service answered a
     * card-number miss with the ACCOUNT-path sentence rather than the search-condition one, so the wrong
     * message reached a caller. Wiring the view services and withdrawing the duplicates leaves one
     * implementation of each read, and it is the implementation whose messages match the reference.</p>
     *
     * <p>Refactoring Rationale: four collaborators became five. The fifth is the shared error advice, and
     * it is required rather than optional: the extended conflict body this class composes has to carry the
     * same code, sentence, subsystem, correlation identity, masked path and timestamp as every other
     * conflict in the migration, and the advice is where all six are decided. An optional collaborator
     * would mean a conflict body assembled two different ways depending on wiring, which is the
     * divergence this arrangement exists to prevent.</p>
     *
     * @param reads the service serving the browse; must not be {@code null}
     * @param views the service serving the two masked single-card reads; must not be {@code null}
     * @param adminViews the service serving the one read that discloses a full account number; must not
     *     be {@code null}
     * @param writes the service serving the edit; must not be {@code null}
     * @param conflicts the shared error advice, which composes the shared half of a conflict body; must
     *     not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CardController(CardListService reads, CardViewService views,
            CardAdminViewService adminViews, CardUpdateService writes,
            GlobalExceptionHandler conflicts) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.views = Objects.requireNonNull(views, "views");
        this.adminViews = Objects.requireNonNull(adminViews, "adminViews");
        this.writes = Objects.requireNonNull(writes, "writes");
        this.conflicts = Objects.requireNonNull(conflicts, "conflicts");
    }

    /**
     * Reads one page of cards, optionally narrowed to one account.
     *
     * <p>Alternatives Considered: positioning a page by counting rows from the start of the ordered set
     * was evaluated and rejected, which is why no parameter, no response member and no local name in this
     * class expresses a row ordinal, a page ordinal or a set total. The failure it avoids is specific
     * rather than a preference: the number of rows preceding a position changes underneath a reader when
     * rows are inserted or removed between two requests, so a page positioned by count omits rows it never
     * showed and repeats rows it already showed, while a key already read keeps its place in the ordering
     * whatever is inserted around it. That interleaving is real in this system rather than hypothetical --
     * {@code app/cbl/COBIL00C.cbl:212-217} takes a highest-key reading and increments it across several
     * statements with nothing holding a lock across them. Positioning by key is also the closer
     * transcription: the reference already carries a key pair and a further-page indicator across its
     * screen turns, at {@code app/cbl/COCRDLIC.cbl:230-244}, so the envelope this returns is the same
     * cursor the reference kept rather than a new mechanism.
     *
     * <p>Assumptions: the page holds at most seven rows and this operation publishes no size input,
     * because seven is a reference constant rather than a tunable default. It is declared
     * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at {@code app/cbl/COCRDLIC.cbl:177-178},
     * arithmetically corroborated by the comment at {@code :250} working the row array out as
     * twenty-eight characters by seven rows, and independently visible in the symbolic map as seven row
     * groups running from {@code CRDSEL1I} at {@code app/cpy-bms/COCRDLI.CPY:78} to {@code CRDSEL7I} at
     * {@code :252} with no eighth. {@link CardListService} owns the constant so this class does not
     * restate it, and admitting a size here would let a caller ask for a window the reference had no way
     * to show.
     *
     * <p>Alternatives Considered: a fifth envelope member reporting whether a previous page exists.
     * Rejected because the shared envelope declares exactly four members and every consumer of it
     * declares the same four. Backward availability is instead composed by
     * {@link CardListService#backwardAvailable(PageResponse, boolean)} from the envelope's leading cursor
     * AND the caller's own opening-page state, and the second half is the caller's to supply: a caller
     * can only step backward from a page it reached by stepping forward, so the opening page is the one
     * with nothing before it, which is the condition the reference tests as
     * {@code 88 CA-FIRST-PAGE VALUE 1} at {@code app/cbl/COCRDLIC.cbl:238} over an ordinal it keeps on
     * the terminal side. Refactoring Rationale: this paragraph previously recorded the answer as
     * derivable from the envelope ALONE, which was wrong and was reported as a defect -- every page
     * carrying rows names its own first row, so that reading offered a backward step from the opening
     * page. The migrated home of the ordinal is the browser client's navigation state, and
     * {@code ui/src/screens/cardList} holds it and withholds the backward key on the first page.
     *
     * <p>Assumptions: both narrowings of this operation are OPTIONAL, and the reference says so by the
     * value it pre-sets each edit to rather than in a comment. {@code 2210-EDIT-ACCOUNT.} at
     * {@code app/cbl/COCRDLIC.cbl:1003} pre-sets its flag blank at {@code :1004} and
     * {@code 2220-EDIT-CARD.} at {@code :1036} does the same at {@code :1039}, and neither raises the
     * program's input-error condition on that branch -- so a field left empty is accepted and simply
     * narrows nothing. The detail program pre-sets not-acceptable instead, at
     * {@code app/cbl/COCRDSLC.cbl:648} and {@code :688}, and does raise it, which is what makes ITS fields
     * mandatory. The pre-set value is the optional-versus-mandatory semantic, so it is read off the
     * reference rather than decided here.
     *
     * <p>Refactoring Rationale: the reference row-selection column has no counterpart in this operation's
     * request or its response. {@code app/cbl/COCRDLIC.cbl:77} admits two values through
     * {@code 88 SELECT-OK VALUES 'S', 'U'}, and {@code :78} and {@code :79} name them as a request to
     * view and a request to update. Both are navigation to another screen, which is a client concern in
     * the target, so a page row carries no selection member and this operation accepts no selection
     * input. The per-row error presentation attached to that column has no target representation either,
     * and its shape is why: row one writes the marker character into its own output field at
     * {@code :758} while rows two to seven write a cursor position into {@code CRDSEL<n>L} instead, so the
     * reference does not even address the seven rows uniformly, and a per-field error array keyed by
     * member name has no row-cell address to put either form at.
     *
     * <p>Refactoring Rationale: three of the reference list program's sentences reach no response of this
     * operation, and they are recorded here rather than dropped quietly --
     * {@code WS-INFORM-REC-ACTIONS} at {@code app/cbl/COCRDLIC.cbl:115-116},
     * {@code WS-MORE-THAN-1-ACTION} at {@code :123-124} and {@code WS-INVALID-ACTION-CODE} at
     * {@code :125-126}. All three describe the selection column above: the first instructs an operator
     * which letters to type, and the other two report that too many rows were marked or that a marked
     * letter was neither of the two admitted. With no selection input there is no state any of them can
     * describe. They are registered divergences in
     * {@code docs/architecture/cobol-to-service-traceability.md}, and this operation's own page notices
     * are the separate ones {@link CardListService#pageMessage(PageResponse)} carries.
     *
     * <p>Assumptions: the authenticated caller is accepted as a {@link Principal} and passed to the read
     * service, because a cursor of this browse is bound to the caller it was issued to and to the
     * direction it was issued for. This parameter is NOT part of the published operation and adding it
     * changed nothing in {@code openapi/card-api.yaml}: the framework resolves it from the security
     * context established by the filter chain, so it travels in no body, no query string, no path segment
     * and no header a client controls. A rationale previously recorded on the read service claimed the
     * opposite -- that carrying a subject was "published in openapi/card-api.yaml and is not this class's
     * to change alone" -- and it was wrong twice over, since the contract had already published the
     * direction binding this enables.
     *
     * <p>Assumptions: the principal is dereferenced without a null check, and that is safe rather than
     * optimistic. This module's {@code SecurityConfig} ends in {@code anyRequest().denyAll()} and admits
     * this path only to a named group, so an unauthenticated request is refused by the chain and never
     * reaches this method. A defensive branch here would be unreachable code standing in for a
     * configuration guarantee.
     *
     * @param query the criteria -- an optional account narrowing, an optional cursor and an optional
     *     direction. An absent body lists the whole collection one page at a time, which is the list
     *     screen's initial state
     * @param principal the authenticated caller, supplied by the filter chain; its name is what every
     *     cursor this page mints is bound to, so a cursor cannot be carried between callers
     * @return one page of masked summaries, at most seven, with its boundary cursors and both
     *     availability indicators; never {@code null}
     * @throws ClientInputException if the account narrowing is outside the published domain, or the
     *     cursor is not one this browse sealed for this caller, this narrowing and this direction,
     *     either of which the shared advice renders as HTTP 400
     */
    @OnlineWriteGateExempt(reason =
            "A paged READ of the card master. It is a POST because its narrowing carries an account"
            + " identifier and its position carries a sealed cursor, neither of which may appear in a"
            + " request line. Browsing cards changes nothing, so it stays available while the window"
            + " is closed; the card update on this same controller is a PUT and is not exempt.")
    @PostMapping(path = SEARCH_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public PageResponse<CardSummary> listCards(
            @Valid @RequestBody(required = false) CardPageQuery query, Principal principal) {

        CardPageQuery criteria = query == null ? EMPTY_QUERY : query;

        // WHY : Alternatives Considered: accepting the reference wildcard as a way to clear a narrowing.
        //       The reference detail program does exactly that, normalising a typed asterisk to low
        //       values alongside blanks at app/cbl/COCRDSLC.cbl:614-627 under its own comment at :614.
        //       It is NOT reproduced, because it is an affordance of a constant-width terminal field a
        //       user could not otherwise empty, and a caller of this operation clears a narrowing by
        //       omitting the member. The published pattern admits digits only, so an asterisk earns a
        //       400 naming the member rather than silently widening the query it appeared to narrow.
        //       Blank IS normalised below, and only blank, because that is the one the reference list
        //       program itself accepts at :1003-1004. The other two uses of that character are
        //       untouched by this decision and are not the same thing: the server-written marker
        //       belongs to the per-field error array, and the re-entry gate of
        //       app/cpy/CSSETATY.cpy:20 that guarded it is gone with the discriminator.
        Long account = narrowing(criteria.accountId());

        // WHY : Assumptions: the cursor crosses this method untouched and is never inspected. The token
        //       is sealed, and what it stands for does not match what a reader might infer from the
        //       reference: the reference record identifier at app/cbl/COCRDLIC.cbl:137-141 is a
        //       twenty-seven-byte pair of card number and account identifier, while the store this
        //       browse keys on has the single sixteen-character card number as its key. Only the service
        //       that minted a token knows which of those it holds, so parsing, validating the shape or
        //       rebuilding one here would bind this class to a representation it does not own, and a
        //       refusal raised from a guess would be indistinguishable from a genuine one.
        // WHY : Assumptions: the authenticated name is taken from the security context rather than from
        //       the request body, and it is what binds every cursor this page mints. A member on the body
        //       was rejected outright: a caller supplying its own subject would be choosing the scope its
        //       cursors are checked against, which is the opposite of a check. The filter chain has
        //       already established the name by the time this method runs, so no cost attaches to using
        //       it, and the framework raises before the handler if it is absent.
        return this.reads.list(account, criteria.cursor(),
                DIRECTION_PREVIOUS.equals(criteria.direction()), principal.getName());
    }

    /**
     * Converts a submitted account narrowing to the form the read service accepts.
     *
     * <p>Assumptions: an account identifier travels as DIGIT CHARACTERS and never as a number, and the
     * reference makes the case itself by declaring these identifiers both ways round.
     * {@code app/cpy/CVCRD01Y.cpy} declares character fields and redefines them numerically -- the
     * account at lines 34 to 36, the card number at 37 to 39 and the customer at 40 to 42 -- while
     * {@code app/cbl/COCRDLIC.cbl:139-141} declares the numeric form first and redefines it as
     * characters. A sixteen-digit card number is silently altered by any client that routes it through
     * IEEE-754 binary64, whose significand carries fewer than sixteen decimal digits, and publishing one
     * identifier of that family as a number while the rest travel as text would invite exactly that. The
     * narrowing is therefore published as text, and the integer form is produced here, at the one boundary
     * where the stored column genuinely is one.
     *
     * <p>Assumptions: an absent narrowing and a blank one are the same answer, which is the reference's
     * own reading -- {@code 2210-EDIT-ACCOUNT.} pre-sets its flag blank at
     * {@code app/cbl/COCRDLIC.cbl:1003-1004}, so an empty field narrows nothing rather than narrowing to
     * nothing. Both are folded to no narrowing here so that a caller reaching this class over HTTP and a
     * caller holding it directly agree; over HTTP a present-but-empty member is already refused upstream
     * by the member's own published pattern, which admits eleven digits and nothing shorter.
     *
     * <p>Assumptions: eleven zero digits are a THIRD spelling of the same answer, and this method
     * produces it by arithmetic rather than by a test of its own -- the value parses to zero, and
     * {@code CardListService}'s narrowing gate reads zero as not supplied because
     * {@code app/cbl/COCRDLIC.cbl:1007-1009} places {@code CC-ACCT-ID-N EQUAL ZEROS} in the same
     * disjunction as low values and spaces and sends all three to the same not-supplied exit. So a
     * caller sending zeros is listing across all accounts, exactly as the reference screen does with a
     * zero-filled filter field. The gate records why refusing it instead was rejected.
     *
     * @param accountId the account narrowing exactly as the request body carried it, or {@code null} when
     *     the caller sent none
     * @return the narrowing as the integer the read service expects, or {@code null} when the caller
     *     supplied none
     * @throws NumberFormatException if the value is not the eleven digits the published member is
     *     constrained to, which can only be reached when the declared constraint did not run and is
     *     therefore a wiring defect rather than a caller error
     */
    private static Long narrowing(String accountId) {
        return accountId == null || accountId.isBlank() ? null : Long.valueOf(accountId);
    }

    /**
     * Resolves a submitted primary account number to that card's masked detail.
     *
     * <p>Assumptions: this is a {@code POST} although it performs no write, and the method is chosen for
     * the body rather than for the semantics. A {@code GET} carrying the number would put it in the query
     * string, which is part of the request target and is retained verbatim by the access log the load
     * balancer writes before this method is entered. This is the only operation in the context that
     * accepts a card number at all, and it answers with the same masked shape the selector-addressed read
     * answers with, so a caller that arrives holding a number leaves holding a selector and never has to
     * send the number a second time.
     *
     * @param request the number to resolve; validated against the published schema before this method is
     *     entered
     * @return the card's detail with its number masked, carrying the selector every other route uses;
     *     never {@code null}
     * @throws NoSuchElementException if the number is well formed but names no card, which the shared
     *     advice renders as HTTP 404
     */
    @OnlineWriteGateExempt(reason =
            "A READ of one card, a POST so that the sixteen-digit card number travels in a request"
            + " body instead of a path segment. The number is the one identifier in this migration"
            + " that must never reach an access record, which is why the read is shaped this way and"
            + " why it needs an exemption at all.")
    @PostMapping(path = LOOKUP_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CardDetail lookupCard(@Valid @RequestBody CardLookupRequest request) {

        return this.views.viewByCardNumber(request.cardNumber());
    }

    /**
     * Reads one card's detail, with the primary account number masked to its last four digits.
     *
     * <p>Assumptions: the selector is REQUIRED, unlike either narrowing of the browse, and the asymmetry
     * is the reference's. The detail program pre-sets both of its edits to not-acceptable, at
     * {@code app/cbl/COCRDSLC.cbl:648} and {@code :688}, and raises its input-error condition on the
     * blank branch, so an unidentified card is a refusal there and not a full listing. Expressing that as
     * a path segment rather than an optional member is what makes it structural: a request naming no card
     * does not reach this handler at all.
     *
     * <p>Assumptions: the selector is echoed to the service exactly as received and is never parsed,
     * compared or rebuilt here. The published parameter says as much, and the reason is that the value it
     * seals is not the value a reader would guess: the reference addresses a card through a
     * twenty-seven-byte pair at {@code app/cbl/COCRDLIC.cbl:137-141}, while the stored key is the single
     * sixteen-character card number. Opening the seal is the service's, so a malformed selector is
     * refused by the component that knows what a well-formed one contains.
     *
     * @param cardKey the opaque selector from a list row or a lookup, from the request path
     * @return the card's masked detail; never {@code null}
     * @throws ClientInputException if the selector is not one this deployment sealed, which the shared
     *     advice renders as HTTP 400
     * @throws NoSuchElementException if the selector opens cleanly but names no stored card, which the
     *     shared advice renders as HTTP 404
     */
    @GetMapping(path = CARD_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public CardDetail getCard(@PathVariable(name = "cardKey") String cardKey) {

        return this.views.viewBySelector(cardKey);
    }

    /**
     * Applies the submitted attributes to one card.
     *
     * <p>Refactoring Rationale: the submitted body carries FOUR editable members -- the embossed name,
     * the active status and the two halves of the expiration date. That is the three editable attributes
     * the contract names, the date arriving in two parts because the reference screen presented it in
     * two. Against that, the reference screen offered seven fields for this record: five card attributes
     * and the two search keys. The keys account for two of the three omissions, and the reference agrees
     * they are not editable, because its own before-image comparison tests six fields and includes
     * neither the card number nor the account identifier.
     *
     * <p>Refactoring Rationale: the third omission is the expiry DAY, and it is the reference's own
     * decision rather than this migration's. {@code app/cbl/COCRDUPC.cbl:1119-1120} records in a comment
     * that the field is not offered for change; the statement that would have moved a submitted value is
     * commented out at {@code :1122} while the one restoring the stored value is active at {@code :1123};
     * the field is rendered non-display at {@code :1285}; and the program declares no edit paragraph for
     * it at all, against six edit paragraphs covering the four editable members and the two search keys.
     * Assumptions: the field DOES exist in the symbolic map, at {@code app/cpy-bms/COCRDUP.CPY:96}, so
     * the omission is a decision of the program and not an absence in the screen layout, and stating it
     * the other way round would misdescribe the evidence.
     *
     * <p>Assumptions: the card verification value is likewise absent from the submitted body, and here
     * too the reference is the reason rather than a policy added on top. The program's own field for a
     * newly-submitted verification value is never the target of an assignment anywhere in it: it is
     * declared once and read once, as the SOURCE of a move that carries the stored value back out. The
     * reference accepted no new value for it, so neither does this operation.
     *
     * <p>Alternatives Considered: carrying the concurrency token as an {@code If-Match} request header,
     * which is the conditional-request form and is what the sibling account context uses. Not used here,
     * because this context's published request schema requires the token as a body member and a published
     * request shape is an external interface -- the two contexts differ because their contracts differ,
     * and neither is being bent to match the other from inside a handler. Assumptions: the token is
     * therefore bound as part of the body and validated with it, and the response carries the token the
     * saved row now holds, so a caller making a second change does not have to re-read the card first.
     *
     * @param cardKey the opaque selector naming the card to edit, from the request path
     * @param request the attributes to apply and the version they were read at; validated against the
     *     published schema before this method is entered
     * @return the saved card's masked detail, carrying the new version; never {@code null}
     * @throws ClientInputException if the selector cannot be opened or any submitted attribute fails its
     *     edit, which the shared advice renders as HTTP 400 carrying the per-field array
     * @throws NoSuchElementException if the selector opens cleanly but names no stored card, which the
     *     shared advice renders as HTTP 404
     * @throws CardRecordConflictException if the stored row has moved on from the submitted version,
     *     which {@link #onCardRecordConflict(RecordConflictException, HttpServletRequest)} renders as
     *     HTTP 409 carrying the reference sentence and the card as it now stands
     */
    @PutMapping(path = CARD_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CardDetail updateCard(@PathVariable(name = "cardKey") String cardKey,
            @Valid @RequestBody CardUpdateRequest request) {

        return this.writes.update(cardKey, request);
    }

    /**
     * Reads one card's detail with its primary account number disclosed in full.
     *
     * <p>Assumptions: this sits on its own path so the filter chain can gate it on the administrative
     * authority, and it returns its own type so the ordinary read cannot reach the disclosure by any
     * argument a caller supplies. Both halves of that are needed: the route decides who may ask, and the
     * type decides what can be answered. The wider authority widens what may be RENDERED of the account
     * number and nothing else -- it grants no additional field, and the card verification value is as
     * absent from this response as from every other in the contract.
     *
     * @param cardKey the opaque selector naming the card to read, from the request path
     * @return the card's detail carrying the unmasked primary account number; never {@code null}
     * @throws ClientInputException if the selector is not one this deployment sealed, which the shared
     *     advice renders as HTTP 400
     * @throws NoSuchElementException if the selector opens cleanly but names no stored card, which the
     *     shared advice renders as HTTP 404
     */
    @GetMapping(path = ADMIN_CARD_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public AdminCardDetail getAdminCardDetail(@PathVariable(name = "cardKey") String cardKey) {

        return this.adminViews.viewForAdministrator(cardKey);
    }

    /**
     * Renders a stale-revision refusal as the extended conflict body this contract declares.
     *
     * <p>Refactoring Rationale: this handler exists because {@code CardConflictError} in
     * {@code src/main/resources/openapi/card-api.yaml} declares a member the shared problem shape has no
     * room for, and the route was answering with the shared shape alone. Runtime testing found what a
     * caller received instead: a 409 whose only report of the current state was the version number placed
     * in the field entry's message position, where the document's own example shows the reference sentence
     * there and the version inside a {@code card} object. Following the document therefore led a client to
     * a member that was never sent, and reading the entry led it to a bare number where a sentence was
     * promised.</p>
     *
     * <p>Assumptions: the shared renderer composes the whole of the shared half, and this method changes
     * exactly three things about the result. That division is the point of injecting the advice rather than
     * assembling a body here: the code, the sentence selection, the relational subsystem, the correlation
     * identity, the masked path and the timestamp all stay stated once, in the class every other context
     * answers a conflict through, so this handler cannot drift from them. What it changes is the field
     * entry's message -- to the sentence the contract shows -- the addition of the card, and the
     * subordinate code, which the shared renderer leaves empty because six of the seven published
     * contracts declare one conflict condition and admit no value for it.</p>
     *
     * <p>Refactoring Rationale: the subordinate code was copied from the shared renderer and was therefore
     * always the empty string, while this context's contract names three values and says the code is what
     * distinguishes the three conditions. A client following the document to tell them apart had nothing to
     * read. The code is now selected from the refusal's own kind, in a switch the compiler holds
     * exhaustive.</p>
     *
     * <p>Measured: restoring the copy -- passing {@code shared.secondaryCode()} in place of the selected
     * code -- fails exactly two cases of
     * {@code CardControllerTest.eachConflictConditionPublishesItsOwnSubordinateCode}, the stale-version arm
     * reporting {@code expected:<CARD-DATA-CHANGED> but was:<>} and the lock-unavailable arm reporting
     * {@code expected:<CARD-LOCK-NOT-ACQUIRED> but was:<>}, with the other thirty-seven cases of that class
     * still passing. The two arms for the conditions this contract publishes no value for do not move,
     * which is what shows the empty code there is asserted deliberately rather than by default.</p>
     *
     * <p>Trade-offs: the entry's message becomes the sentence, so the version no longer appears in it.
     * That is the point rather than a loss: the version moves to {@code card.version}, where it is typed
     * as a number beside the rest of the refreshed state instead of being a number formatted into a help
     * string. The shared renderer is deliberately NOT changed to match, because a service with no
     * refreshed representation to send has that entry as its only channel for the version, and taking it
     * away would leave those contexts reporting a conflict with no way to say what to retry against.</p>
     *
     * <p>Assumptions: this handler claims the BASE contention type and not only the card-specific subtype,
     * so every conflict this context raises returns the extended body -- the refreshed card where the
     * condition has one, and the member present and null where it does not. The alternative was to claim
     * the subtype alone and leave the other three conditions to the shared advice, which returns a body
     * with no such member at all; the contract's own examples for those conditions show {@code card:
     * null}, so claiming the base type is what makes the document literally true of every conflict a
     * caller can receive here.</p>
     *
     * <p>Refactoring Rationale: a conflict the STORE reports -- a provider optimistic-lock or
     * pessimistic-lock failure raised by the flush rather than by this context's own comparison -- is not
     * a {@link RecordConflictException} and so cannot reach this handler at all. It used to be answered by
     * the shared advice, which returns a body with no {@code card} member and no subordinate code, so two
     * of the three published conditions were unreachable in the shape the document describes. Those two
     * are now claimed by {@link #onStoreDetectedConflict}, which composes the same extended body through
     * the same private composer; the member is present and null there, which is what the contract's own
     * examples for those two conditions show.</p>
     *
     * @param failure the contention this context raised. When it is the card-specific subtype it carries
     *     the card as it stood at detection; for any other kind it carries none, and the member is
     *     rendered null
     * @param request the request being answered, passed through to the shared renderer for its path and
     *     nothing else
     * @return HTTP 409 carrying the shared problem shape with the subordinate code its kind selects, the
     *     reference sentence in its version entry, and the refreshed card where the condition has one;
     *     never {@code null}
     * @throws IllegalStateException if the shared renderer returns no body, which no path produces and
     *     which would mean the shared conflict contract had changed underneath this method
     */
    @ExceptionHandler(RecordConflictException.class)
    public ResponseEntity<CardConflictError> onCardRecordConflict(
            RecordConflictException failure, HttpServletRequest request) {

        ApiError shared = requireSharedBody(this.conflicts.onRecordConflict(failure, request));

        // WHY : Assumptions: the switch yields a value and declares no default arm, so the compiler
        //       requires every constant of the enumeration to be covered -- which is what makes a fifth
        //       condition impossible to add without this method failing to compile rather than silently
        //       emitting an empty code for it.
        // WHY : Trade-offs: two of the four arms answer NO_SECONDARY_CODE, and that is a statement about
        //       this CONTRACT rather than an omission. A referenced-row breach and a duplicate key are
        //       conditions of a context that owns dependent rows or a caller-supplied key; this context
        //       owns neither -- one table, one system-assigned key, no dependents -- so no card path can
        //       raise them, and card-api.yaml publishes no value for either. Minting a code the document
        //       does not admit would put a value into a body no schema of this service allows, which is
        //       the defect being corrected here in the other direction.
        String secondaryCode = switch (failure.kind()) {
            case STALE_VERSION -> CONFLICT_CODE_DATA_CHANGED;
            case LOCK_UNAVAILABLE -> CONFLICT_CODE_LOCK_NOT_ACQUIRED;
            case REFERENCED_ROW, DUPLICATE_KEY -> ApiError.NO_SECONDARY_CODE;
        };

        // WHY : Assumptions: the card is taken from the refusal by a TYPE test, so a condition that
        //       carries no refreshed row renders the member as null rather than this method having to know
        //       which kinds carry one. Selecting on the kind instead would put a second statement of that
        //       correspondence here, and it would go stale the moment a fifth kind is added.
        CardDetail refreshed = failure instanceof CardRecordConflictException carrying
                ? carrying.card()
                : null;

        return conflictBody(shared, secondaryCode, shared.message(), refreshed);
    }

    /**
     * Renders a contention the persistence provider reported as the conflict body this contract declares.
     *
     * <p>Refactoring Rationale: this handler exists because two of the three conditions the contract
     * publishes under this status are detected by the STORE rather than by this context, and neither could
     * reach {@link #onCardRecordConflict}: a provider failure is not a
     * {@link RecordConflictException}, so the shared advice answered it with the shared shape, the empty
     * subordinate code, and no {@code card} member. One of the two was additionally answered with the
     * WRONG sentence -- an optimistic-lock failure raised by the flush was reported as
     * {@link CardUpdateService#MESSAGE_RECORD_CHANGED}, the sentence belonging to the comparison this
     * service performs before it writes -- and {@link #CONFLICT_CODE_WRITE_NOT_APPLIED} was produced by
     * nothing at all.
     *
     * <p>Assumptions: the two claimed types are siblings under the abstraction's concurrency family
     * rather than one being a subclass of the other, so the parameter is typed as their common supertype
     * while the annotation names exactly the two this method is willing to answer for. A third
     * concurrency failure added to that family in a future version therefore reaches the shared advice, as
     * it does today, instead of being silently relabelled as one of these two.
     *
     * <p>Assumptions: the mapping follows the reference's own three-way split. The optimistic failure is
     * {@code 88 LOCKED-BUT-UPDATE-FAILED} at {@code app/cbl/COCRDUPC.cbl:209-210}: the row was read, this
     * service's own comparison passed, and the write did not apply -- which is precisely a concurrent
     * writer committing in the window between the check and the flush. The pessimistic failure is
     * {@code 88 COULD-NOT-LOCK-FOR-UPDATE} at {@code :205-206}: the row could not be taken at all.
     *
     * <p>Trade-offs: neither body carries a refreshed card, and the contract's own examples for both
     * conditions show the member as null. Obtaining one would mean re-reading the row from inside an
     * exception handler after the transaction that failed has been marked for rollback, which would either
     * read through a doomed transaction or open a second one to answer a refusal.
     *
     * <p>Measured: removing this method's {@code @ExceptionHandler} annotation, so the two failures reach
     * the shared advice as they did before, fails exactly the two cases that exercise them --
     * {@code aProviderOptimisticLockFailureAnswersTheRewriteFailureCondition} and
     * {@code aProviderPessimisticLockFailureAnswersTheLockAcquisitionCondition} -- each reporting the empty
     * subordinate code where a published value was expected, and moves nothing else in the class. That is
     * the defect this handler corrects, reproduced on demand.
     *
     * @param failure the concurrency failure the provider raised; only its type is read, never its text,
     *     because a provider message names tables and columns a caller has no business seeing
     * @param request the request being answered, passed through to the shared renderer for its path
     * @return HTTP 409 carrying the subordinate code and the reference sentence its condition selects,
     *     with the card member present and null; never {@code null}
     * @throws IllegalStateException if the shared renderer answers with no body or with a status other
     *     than 409, either of which would mean the shared classification of these two types had changed
     *     underneath this method and that this handler was about to relabel a different refusal
     */
    @ExceptionHandler({OptimisticLockingFailureException.class,
            PessimisticLockingFailureException.class})
    public ResponseEntity<CardConflictError> onStoreDetectedConflict(
            ConcurrencyFailureException failure, HttpServletRequest request) {

        ApiError shared = requireSharedBody(this.conflicts.onRuntimeFailure(failure, request));
        if (shared.status() != HttpStatus.CONFLICT.value()) {
            throw new IllegalStateException(
                    "the shared renderer no longer answers a provider concurrency failure with 409, so "
                            + "this handler can no longer name which conflict condition it is rendering");
        }

        // WHY : Assumptions: the discrimination is a type test on the failure and not a reading of the
        //       sentence the shared renderer chose, because the sentence is what this method may need to
        //       REPLACE and deriving the code from a value being replaced would couple the two the wrong
        //       way round. The optimistic branch replaces it; the pessimistic branch keeps it, because the
        //       shared renderer's own sentence for that type is already this context's.
        boolean writeNotApplied = failure instanceof OptimisticLockingFailureException;
        String secondaryCode = writeNotApplied
                ? CONFLICT_CODE_WRITE_NOT_APPLIED
                : CONFLICT_CODE_LOCK_NOT_ACQUIRED;
        String message = writeNotApplied
                ? CardUpdateService.MESSAGE_UPDATE_FAILED
                : shared.message();

        return conflictBody(shared, secondaryCode, message, null);
    }

    /**
     * Composes the one conflict body every 409 this class answers is built from.
     *
     * <p>Refactoring Rationale: the two handlers above differ in exactly three values -- the subordinate
     * code, the sentence and whether a refreshed card exists -- so everything else is stated once here.
     * That division is the point of injecting the shared advice rather than assembling a body from
     * scratch: the code, the severity, the relational subsystem, the correlation identity, the masked path
     * and the timestamp all remain owned by the class every other context answers a conflict through, and
     * a third condition cannot be added to this context with a differently-shaped body.
     *
     * <p>Trade-offs: the shape is rebuilt through its canonical constructor with the SAME timestamp the
     * shared renderer stamped, rather than through a factory that would stamp a new one. Two timestamps
     * for one refusal is the kind of difference that costs an hour when a support conversation compares a
     * client's copy of a body against a log line; reusing the stamped value also means this class needs no
     * clock of its own to keep in step with the one the advice holds.
     *
     * @param shared the body the shared renderer composed, read for every value not listed below
     * @param secondaryCode the subordinate code this condition publishes; one of the three constants
     *     above, or {@link ApiError#NO_SECONDARY_CODE} for a condition this contract names no value for
     * @param message the user-visible sentence, carried verbatim from its baseline source
     * @param refreshed the card as it now stands, or {@code null} when the condition has no current
     *     representation to return
     * @return the composed 409 response; never {@code null}
     */
    private static ResponseEntity<CardConflictError> conflictBody(ApiError shared,
            String secondaryCode, String message, CardDetail refreshed) {

        // WHY : Assumptions: the entry is REPLACED rather than appended to, so the array still carries
        //       exactly one entry for one field. Two entries for the version -- one holding the number the
        //       shared renderer wrote and one holding the sentence -- would make an array length no longer
        //       equal the count of faulted fields, which is the property every consumer of this member
        //       relies on.
        // WHY : Assumptions: the field NAME is taken from the entry the shared renderer produced rather
        //       than written here, so the two cannot disagree about what the entry is keyed on. The
        //       renderer owns that key, and a literal here would be a second declaration of it.
        // WHY : Refactoring Rationale: the replacement text is the sentence this refusal carries rather
        //       than the record-changed constant it used to name unconditionally. The two agree for the
        //       one condition that carries a version entry today, so the literal was not wrong; it was a
        //       second statement of which sentence belongs to which condition, sited where a reader would
        //       not think to keep it in step.
        List<ApiError.FieldError> fieldErrors = shared.fieldErrors().stream()
                .map(entry -> new ApiError.FieldError(entry.field(), entry.state(), message))
                .toList();

        ApiError body = new ApiError(shared.code(), secondaryCode, message, shared.severity(),
                shared.subsystem(), shared.status(), shared.correlationId(), shared.path(),
                shared.timestamp(), fieldErrors, shared.abend());

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CardConflictError(body, refreshed));
    }

    /**
     * Reads the body out of a shared renderer's response, refusing an empty one.
     *
     * <p>Assumptions: an absent body is treated as a broken contract with the shared kernel rather than
     * rendered as an empty conflict, because every path through that renderer returns one and a null here
     * would mean the shared conflict contract had changed underneath this class.</p>
     *
     * @param rendered the response the shared renderer produced; may itself be {@code null}
     * @return its body; never {@code null}
     * @throws IllegalStateException if the response or its body is absent
     */
    private static ApiError requireSharedBody(ResponseEntity<ApiError> rendered) {
        ApiError body = rendered == null ? null : rendered.getBody();
        if (body == null) {
            throw new IllegalStateException(
                    "the shared conflict renderer returned no body, so no conflict body can be composed");
        }
        return body;
    }
}
