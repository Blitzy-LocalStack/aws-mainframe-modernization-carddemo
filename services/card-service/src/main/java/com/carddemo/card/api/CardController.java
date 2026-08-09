package com.carddemo.card.api;

import com.carddemo.card.dto.AdminCardDetail;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.dto.CardLookupRequest;
import com.carddemo.card.dto.CardPageQuery;
import com.carddemo.card.dto.CardSummary;
import com.carddemo.card.dto.CardUpdateRequest;
import com.carddemo.card.service.CardAdminViewService;
import com.carddemo.card.service.CardListService;
import com.carddemo.card.service.CardUpdateService;
import com.carddemo.card.service.CardViewService;
import com.carddemo.common.control.OnlineWriteGateExempt;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.web.PageResponse;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.http.MediaType;
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
     * @param reads the service serving the browse; must not be {@code null}
     * @param views the service serving the two masked single-card reads; must not be {@code null}
     * @param adminViews the service serving the one read that discloses a full account number; must not
     *     be {@code null}
     * @param writes the service serving the edit; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CardController(CardListService reads, CardViewService views,
            CardAdminViewService adminViews, CardUpdateService writes) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.views = Objects.requireNonNull(views, "views");
        this.adminViews = Objects.requireNonNull(adminViews, "adminViews");
        this.writes = Objects.requireNonNull(writes, "writes");
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
     * Rejected because the shared envelope declares exactly four members and backward availability is already
     * derivable from one of them -- {@link CardListService#backwardAvailable(PageResponse)} reads it as
     * the presence of the leading cursor. Assumptions: a caller can only step backward from a page it
     * reached by stepping forward, so the opening page is the one with nothing before it, which is the
     * condition the reference tests as {@code 88 CA-FIRST-PAGE VALUE 1} at
     * {@code app/cbl/COCRDLIC.cbl:238}.
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
     * @throws RecordConflictException if the stored row has moved on from the submitted version, which
     *     the shared advice renders as HTTP 409 carrying the reference sentence
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
}
