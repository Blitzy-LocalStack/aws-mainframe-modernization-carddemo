package com.carddemo.card.service;

import com.carddemo.card.domain.Card;
import com.carddemo.card.dto.CardSummary;
import com.carddemo.card.mapper.CardMapper;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves every read of this context: the paged browse, the two detail reads and the number lookup.
 *
 * <h2>Purpose</h2>
 *
 * <p>This is the migrated successor of the reference card list program {@code app/cbl/COCRDLIC.cbl},
 * transaction {@code CCLI}, together with the keyed detail read of {@code app/cbl/COCRDSLC.cbl}. The
 * browse it publishes stands in for the four-verb indexed browse the list program drives between lines
 * 1129 and 1376 of that file, and the detail reads stand in for a keyed read. It takes no parameter and
 * returns no value at the type level; each member below declares its own.
 *
 * <h2>Refactoring Rationale: four browse verbs and a passed cursor become one query per direction</h2>
 *
 * <p>Refactoring Rationale: the reference holds its browse position in a communication area that the
 * terminal echoes back, because the platform it runs on ends the task at every screen turn -- so the
 * only thing that survives a turn is what the client returns. That structure is declared from line 229
 * of {@code app/cbl/COCRDLIC.cbl} and the transaction is defined with no transaction work area at all,
 * {@code TWASIZE(0)} at {@code app/csd/CARDDEMO.CSD:358}, which is what makes the echoed area the sole
 * carrier. The target platform serves stateless requests instead, and nothing about them ends a task
 * mid-conversation, so the same values travel in the response envelope and come back on the request
 * line. What made the reference arrangement unsuitable here is therefore a platform difference and not
 * a defect in it: on this platform a server-held position would have to be stored somewhere for a
 * caller that may never return, and a client-echoed buffer would have to be trusted rather than
 * verified. The four verbs collapse accordingly, and every line below is in
 * {@code app/cbl/COCRDLIC.cbl} rather than in the resource definition just cited: {@code STARTBR} at
 * {@code :1129} and {@code :1273} with {@code READNEXT} at {@code :1146} and {@code :1197}, and
 * {@code READPREV} at {@code :1294} and {@code :1322}, become one keyset query per direction, and
 * {@code ENDBR} at {@code :1258} and {@code :1376} becomes nothing at all, because a query holds no
 * position to release. Exactly two of each verb appear in that program and those eight are all of them.
 *
 * <p>Refactoring Rationale: the substitution is one-for-one rather than an approximation, and that is
 * the decisive finding rather than a hope. The reference's own browse state is already a cursor over
 * keys: {@code app/cbl/COCRDLIC.cbl:230-232} holds the last card key of the rendered page,
 * {@code :233-235} holds its first, and {@code :242-244} holds nothing but whether a further page
 * exists. No count of consumed rows appears anywhere in that structure, so nothing had to be invented
 * here and nothing had to be discarded.
 *
 * <h2>Paragraph to method correspondence</h2>
 *
 * <p>The reference paragraphs this class carries, so that the traceability matrix can cite pairs rather
 * than a whole file: {@code 9000-READ-FORWARD.} at {@code :1123} and {@code 9100-READ-BACKWARDS.} at
 * {@code :1264} become {@link #list(Long, String, boolean, String)} with
 * {@link #pageOf(List, boolean, Long, String)};
 * {@code 9500-FILTER-RECORDS.} at {@code :1382} becomes the optional predicate of the repository query;
 * {@code 2210-EDIT-ACCOUNT.} at {@code :1003} becomes {@link #accountFilterState(Long)}; and
 * {@code 1400-SETUP-MESSAGE.} at {@code :895} becomes {@link #pageMessage(PageResponse)} with
 * {@link #pagingRefusal(PageResponse, boolean, boolean)}.
 *
 * <p>Assumptions: {@code 1250-SETUP-ARRAY-ATTRIBS.} at {@code :748} and
 * {@code 1300-SETUP-SCREEN-ATTRS.} at {@code :837} are deliberately absent from that list. They set
 * screen attributes and nothing else, so they belong to the browser client rather than here; the only
 * part of them this class carries is the mapping from a not-acceptable field state to a reported field
 * error, which {@link #accountFilterState(Long)} performs.
 *
 * <h2>Assumptions this class depends on</h2>
 *
 * <p>Assumptions: the reads of both reference programs sit on ONE class rather than several, because a
 * detail read is a read and shares this class's store, mapper and not-found refusal. The write path is
 * a separate service because a write is the seam that carries meaning in this context.
 *
 * <p>Assumptions: no comparison against recorded mainframe output is available for anything this class
 * carries, and no claim of that kind is made for it. The repository records at
 * {@code tests/README.md:83-85} that the online programs cannot be run end to end without a CICS
 * runtime, which the runner does not have, and that only their extractable field-validation logic is
 * unit tested. Correctness here therefore rests on transcription fidelity against the cited lines and
 * on this module's own tests, and the citations are given line by line so that each one can be checked.
 *
 * <p>Every path under {@code app/} cited in this file is reference material. It is read as the
 * specification, is never modified, and keeps running; this class is added beside it.
 */
@Service
public class CardListService {

    /**
     * The number of rows one browse page carries.
     *
     * <p>Assumptions: seven is the reference window carried across, not a value chosen here, and it is
     * stated four independent ways in the reference so the transcription checks itself.
     * {@code app/cbl/COCRDLIC.cbl:177-178} declares
     * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7}; the comment at {@code :250} works the same
     * number out as twenty-eight characters by seven rows equalling one hundred and ninety-six; the row
     * array is declared {@code PIC X(196)} at {@code :253} and redefined {@code OCCURS 7 TIMES} at
     * {@code :255} over the twenty-eight-byte row of {@code :258-260}; and the symbolic map carries
     * exactly seven row groups, from {@code CRDSEL1I} at {@code app/cpy-bms/COCRDLI.CPY:78} to
     * {@code CRDSEL7I} at {@code :252}, with no eighth.
     *
     * <p>Assumptions: the same seven is declared as configuration under
     * {@code carddemo.card.list.page-size} in this module's {@code application.yml}, and the published
     * contract fixes it a third time as {@code maxItems: 7} on the page schema of
     * {@code openapi/card-api.yaml}. It is a compile-time constant here because the published browse
     * operation admits no page-size input, so a caller cannot vary it and there is nothing for a
     * per-request value to carry.
     */
    public static final int PAGE_SIZE = 7;

    /** The query name every cursor of this browse is bound to. */
    public static final String LIST_BINDING = "card-list";

    /**
     * The scope element naming the direction a cursor may be presented in.
     *
     * <p>Assumptions: the direction belongs in the binding rather than beside it, because the two
     * boundary cursors of one page are sealed under DIFFERENT bindings and that is what makes them
     * non-interchangeable. The leading cursor is the position a backward step reads from and the
     * trailing cursor is the position a forward step reads from; presenting one where the other belongs
     * would name a row the caller has already been shown, and the authenticated binding refuses it
     * instead of silently repeating a page.</p>
     */
    private static final String CURSOR_SCOPE_BACKWARD = "backward";

    /** The forward counterpart of {@link #CURSOR_SCOPE_BACKWARD}. */
    private static final String CURSOR_SCOPE_FORWARD = "forward";

    /**
     * The scope element used when the browse is not narrowed to an account.
     *
     * <p>Assumptions: the unnarrowed case needs its own element rather than an empty one, because an
     * empty element and an account of zero would otherwise compose to the same scope, and a zero
     * account is exactly what {@link #accountFilterState(Long)} reads as not supplied.</p>
     */
    private static final String CURSOR_SCOPE_ALL_ACCOUNTS = "accounts:all";

    /** The prefix of the scope element naming the one account a cursor was narrowed to. */
    private static final String CURSOR_SCOPE_ACCOUNT_PREFIX = "account:";

    /**
     * The rendering the account narrowing is fixed to inside a cursor scope.
     *
     * <p>Assumptions: the width is the reference's own eleven-digit account domain, and the value is
     * zero-padded to it so that one account has exactly one rendering. Without the padding, an account
     * presented as {@code 42} and the same account presented as {@code 00000000042} would compose two
     * different scopes for one row set, and a cursor sealed under either would be refused for the
     * other.</p>
     */
    private static final String CURSOR_SCOPE_ACCOUNT_FORMAT = "%011d";

    /**
     * The name the account narrowing is reported under when it is not acceptable.
     *
     * <p>Assumptions: this is the member name the published request body declares, so a reported field
     * error names something the caller actually sent rather than the reference field {@code ACCTSIDI}
     * declared at {@code app/cpy-bms/COCRDLI.CPY:66}, which no caller of this service has.
     */
    public static final String FIELD_ACCOUNT_FILTER = "accountId";

    /**
     * The reference refusal for an account narrowing that is not an eleven-digit number.
     *
     * <p>Assumptions: reproduced character for character from {@code app/cbl/COCRDLIC.cbl:1022},
     * including two details that read as mistakes and are not. There is no space after the comma, and
     * the article is {@code A} rather than {@code AN} before the digit count. Both are how the value is
     * authored in the reference, and Rule T8 of the migration carries user-visible text verbatim, so
     * neither is normalised.
     */
    public static final String MESSAGE_ACCOUNT_FILTER_INVALID =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * The reference refusal for a card narrowing that is not a sixteen-digit number, retired with
     * reason.
     *
     * <p>Assumptions: reproduced character for character from {@code app/cbl/COCRDLIC.cbl:1058}, with
     * the same missing space after the comma as the account sentence above. It is carried under Rule T8
     * and is deliberately wired to no branch of this class, because the browse this class publishes has
     * no card-number narrowing to refuse. See {@link #list(Long, String, boolean, String)} for why that filter
     * is not published and what serves it instead.
     */
    public static final String MESSAGE_CARD_FILTER_INVALID =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * The reference refusal for a backward step from the opening page.
     *
     * <p>Assumptions: reproduced from {@code app/cbl/COCRDLIC.cbl:903}, which the reference reaches
     * when the backward paging key arrives while the page ordinal says the opening page, at
     * {@code :901-902}.
     */
    public static final String MESSAGE_NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";

    /**
     * The reference refusal for a forward step from the final page.
     *
     * <p>Assumptions: reproduced from {@code app/cbl/COCRDLIC.cbl:908}, reached when the forward paging
     * key arrives while no further page exists and the final page has already been shown, at
     * {@code :905-907}. It is a DIFFERENT sentence from {@link #MESSAGE_NO_MORE_RECORDS} and the two
     * are never interchanged: this one answers a key press, that one reports what a read found.
     */
    public static final String MESSAGE_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

    /**
     * The reference notice that a read reached the end of the ordered set.
     *
     * <p>Assumptions: reproduced from {@code app/cbl/COCRDLIC.cbl:1219}, where the probe read reports
     * end of file, and from the identical value at {@code :1239}, where the read loop does. Both sites
     * emit it only while the message field is still clear, which is the first-error-wins behaviour
     * recorded on {@link #pageMessage(PageResponse)}.
     */
    public static final String MESSAGE_NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";

    /**
     * The reference notice that a browse matched nothing at all.
     *
     * <p>Assumptions: reproduced from {@code app/cbl/COCRDLIC.cbl:121-122}, which is the value the
     * condition name set at {@code :1244} carries. The reference reaches it when the read loop ends at
     * end of file having placed no row on the opening page, at {@code :1241-1242}. The similar shorter
     * sentence on the line above that set, at {@code :1243}, is COMMENTED OUT in the reference and is
     * therefore not carried anywhere in this class.
     */
    public static final String MESSAGE_NO_RECORDS_FOUND =
            "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /**
     * The reference notice painted alongside a page that carries rows.
     *
     * <p>Assumptions: reproduced from {@code app/cbl/COCRDLIC.cbl:115-116}, the value of the condition
     * name the reference sets at {@code :912} and {@code :919}. Its field is declared {@code PIC X(45)}
     * at {@code :112}, which is narrower than the seventy-five-character error field and wider than the
     * forty characters the two sibling card programs declare for their own notice field, so the three
     * are not interchangeable and only this one is carried here.
     */
    public static final String MESSAGE_INFORM_RECORD_ACTIONS =
            "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

    /**
     * A reference row-selection refusal, retired with reason and carried for the record.
     *
     * <p>Assumptions: reproduced from {@code app/cbl/COCRDLIC.cbl:123-124}. The reference counts the
     * selection marks across its seven row fields at {@code :1079-1082} and raises this when more than
     * one is set, at {@code :1084-1086}. Alternatives Considered: reproducing that count here so the
     * sentence would have a live branch. Rejected because a request in this target names exactly one
     * card, in its own path segment, so two selections cannot be expressed and a branch guarding
     * against them would be unreachable code guarding an impossible state. The literal is carried under
     * Rule T8 so the value survives the migration and the traceability matrix can account for it.
     */
    public static final String MESSAGE_MORE_THAN_ONE_ACTION =
            "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";

    /**
     * The other reference row-selection refusal, retired with reason and carried for the record.
     *
     * <p>Assumptions: reproduced from {@code app/cbl/COCRDLIC.cbl:125-126}, raised at {@code :1112}
     * when a row field holds anything other than the two accepted marks or a blank. Alternatives
     * Considered: carrying the accepted marks into this service as an input domain. Rejected for the
     * same reason as the sentence above -- the marks selected a row on a screen that showed seven at
     * once, and an addressed request has no row to mark. The two operations those marks chose between
     * are published as two separate routes instead, so the choice is made by which route is called.
     */
    public static final String MESSAGE_INVALID_ACTION_CODE = "INVALID ACTION CODE";

    /**
     * The widest account narrowing an eleven-digit field can carry.
     *
     * <p>Assumptions: the reference field is {@code PIC X(11)} overlaid by {@code PIC 9(11)} at
     * {@code app/cpy/CVCRD01Y.cpy:34-36}, so eleven digits is the whole domain and this is the largest
     * value in it. The bound is stated as a number rather than as a digit count because the value
     * arrives here already parsed.
     */
    private static final long ACCOUNT_FILTER_CEILING = 99_999_999_999L;

    /** Records which read ran, never the numbers it carried. */
    private static final Logger LOG = LoggerFactory.getLogger(CardListService.class);

    /** The store this service reads. */
    private final CardRepository cards;

    /** Masks numbers, mints and opens selectors, and converts rows to published shapes. */
    private final CardMapper mapper;

    /** Seals and opens the browse cursors. */
    private final CursorToken cursorToken;

    /**
     * Binds the store, the mapper and the cursor signer.
     *
     * <p>Assumptions: every collaborator arrives through this constructor and none is resolved from a
     * static or ambient source, so an instance is complete once built and can be exercised without an
     * application context. That is what lets the browse be tested against a substituted store, and it
     * is also why this class holds no mutable state of its own: the reference transaction is defined
     * with no transaction work area, {@code TWASIZE(0)} at {@code app/csd/CARDDEMO.CSD:358}, and a
     * stateless successor is what preserves that property under horizontal scaling.
     *
     * @param cards the store this service reads; must not be {@code null}
     * @param mapper the projection and selector mapper; must not be {@code null}
     * @param cursorToken the signer that seals and opens browse cursors; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CardListService(CardRepository cards, CardMapper mapper, CursorToken cursorToken) {
        this.cards = Objects.requireNonNull(cards, "cards");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.cursorToken = Objects.requireNonNull(cursorToken, "cursorToken");
    }

    /**
     * Reads one page of cards, optionally narrowed to one account, forward or backward from a cursor.
     *
     * <p>This is the migrated form of {@code 9000-READ-FORWARD.} at {@code app/cbl/COCRDLIC.cbl:1123}
     * and {@code 9100-READ-BACKWARDS.} at {@code :1264}, together with the post-read filter
     * {@code 9500-FILTER-RECORDS.} at {@code :1382} and the narrowing gate
     * {@code 2210-EDIT-ACCOUNT.} at {@code :1003}.
     *
     * <p>Assumptions: the narrowing is passed into the query rather than applied to its result, so a
     * narrowed page is a full page of matching rows rather than whatever survives filtering a page. It
     * is also what makes the narrowing an indexed access path: the predicate reads through
     * {@code idx_cards_account_id}, the target of the alternate index the reference defines at
     * {@code app/jcl/CARDFILE.jcl:85-88} -- declared there over eleven bytes at offset sixteen, which is
     * the account identifier, and declared {@code NONUNIQUEKEY} on {@code :88}, so one account holding
     * many cards is expected rather than exceptional.
     *
     * <p>Assumptions: a backward read arrives in descending order and is reversed before it is
     * published, because a published page is always ascending whichever way the browse travelled.
     *
     * @param accountId the account whose cards to list, or {@code null} to list across all accounts; a
     *     zero is read as not supplied, for the reference reason recorded on
     *     {@link #accountFilterState(Long)}
     * @param cursor the sealed cursor from a previous page, or {@code null} for the opening page
     * @param backward whether to read the page preceding the cursor rather than the one following it
     * @param subject the authenticated caller every cursor of this page is bound to; must not be blank,
     *     because a blank one would bind every caller's cursors to the same scope and so bind them to
     *     none
     * @return one page of masked summaries with its two boundary cursors and both availability
     *     indicators; never {@code null}
     * @throws ClientInputException if the account narrowing is outside the eleven-digit domain, carrying
     *     the reference refusal and the field it belongs to
     * @throws CursorToken.InvalidCursorException if the cursor is not one this browse sealed for this
     *     caller, this narrowing and this direction
     */
    @Transactional(readOnly = true)
    public PageResponse<CardSummary> list(Long accountId, String cursor, boolean backward,
            String subject) {

        // WHY : Assumptions: the narrowing gate runs before anything is read, and the two narrowings
        //       of the reference list screen are OPTIONAL. The reference says so by the value it
        //       pre-sets each gate to rather than by a comment.
        //       2210-EDIT-ACCOUNT. at app/cbl/COCRDLIC.cbl:1003 opens by setting its flag to BLANK at
        //       :1004, and 2220-EDIT-CARD. at :1036 does the same at :1039, so a field left empty is
        //       accepted and simply narrows nothing. The sibling detail program pre-sets NOT-OK
        //       instead, at app/cbl/COCRDSLC.cbl:648 and :688, which is what makes ITS fields
        //       mandatory. The pre-set value IS the optional-versus-mandatory semantic, so it is read
        //       off the reference rather than decided here.
        Long narrowing = acceptedAccountFilter(accountId);

        // WHY : Refactoring Rationale: the binding is now COMPOSED from the query name, the caller's
        //       name and a direction scope, through CursorToken.binding(String, String, String) -- the
        //       same composition the paging service of the auth context uses at
        //       UserService.cursorBinding. It was previously the bare query-name literal, and the
        //       rationale recorded for that was wrong on both of its claims. It said this layer had no
        //       subject to bind to: the browse is served on an authenticated route -- this module's
        //       SecurityConfig ends in anyRequest().denyAll() and admits this path only to a named
        //       group -- so a validated caller name is available and the controller now carries it in.
        //       It also said widening the signature was "published in openapi/card-api.yaml and is not
        //       this class's to change alone": a java.security.Principal parameter is resolved by the
        //       framework from the security context, appears in no request body, query string, path or
        //       header, and therefore changes no published operation at all. Nothing had to be
        //       published to close this.
        // WHY : Assumptions: the contract already PROMISED both bindings before either existed, which
        //       makes this the implementation catching up rather than a widening. card-api.yaml states
        //       that the direction is carried in the seal of each token so that replaying a backward
        //       cursor as a forward step "cannot silently return the wrong page", and lists a cursor
        //       "sealed for the other direction" among the refusals of the published 400. A token bound
        //       to the query alone satisfied neither sentence: it was redeemable by any authorized
        //       caller and in either direction.
        // WHY : Assumptions: the cursor is opened rather than trusted, and an absent one means the
        //       opening page. The reference is in that state when it positions from a communication
        //       area it has just initialised, at app/cbl/COCRDLIC.cbl:462-463 before the forward read
        //       at :478. Opening is what replaces the reference's trust in an echoed buffer: the value
        //       inside the token is verified before it reaches a predicate, and a token this browse did
        //       not seal is refused rather than used.
        // WHY : Refactoring Rationale: the binding used to be the bare query name, and the note here
        //       recorded that as deliberate on the grounds that the published operation carried no
        //       subject into this layer, so there was nothing to bind one to. That was true of the
        //       signature and false as a conclusion: the operation runs behind an authenticated filter
        //       chain, so the subject was available one frame up and the fix was to carry it, which is
        //       what the added parameter does. Leaving it out made every minted cursor presentable by
        //       ANY caller and in EITHER direction -- a token issued to one subject browsing one account
        //       forward opened unchanged for a different subject stepping backward over a different
        //       narrowing, and the row it named was returned as though the caller had reached it. The
        //       harm was not disclosure of the token's contents, which authenticated encryption already
        //       withholds; it was that a position is only meaningful within the query that produced it,
        //       so a cursor honoured outside that query silently answers from a place the caller never
        //       saw.
        // WHY : Assumptions: all four narrowing facts go into the binding rather than one or two of
        //       them, because a cursor is safe to honour only when every predicate that shaped the page
        //       it came from is the same -- the query, the caller, the account narrowing and the
        //       direction. CursorToken.scope composes the last two length-prefixed, so no combination of
        //       elements can be mistaken for another, and the whole is authenticated as associated data
        //       rather than carried in the token, so it cannot be edited by the holder.
        String position = cursor == null ? null
                : this.cursorToken.open(listCursorBinding(narrowing, subject, backward), cursor);

        // WHY : Assumptions: one row MORE than the page holds is requested, and that surplus row is the
        //       reference's own technique rather than an optimisation added here. The reference zeroes
        //       its row counter at app/cbl/COCRDLIC.cbl:1140, sets its further-page condition at :1141
        //       and enters the loop at :1144; when the counter reaches the screen limit at :1191 it
        //       leaves the loop at :1192 and issues one EXTRA read at :1197 whose only purpose is to
        //       discover whether another row exists. Requesting size plus one asks the store the same
        //       question in one round trip, and the surplus row is placed nowhere.
        // WHY : Alternatives Considered: asking the store for a count of the matching set alongside the
        //       page. Rejected because it answers a question the reference never asks, so reporting it
        //       would be behaviour this migration invented rather than carried across, and because the
        //       envelope this method returns has no component to carry a total in.
        Limit limit = Limit.of(PAGE_SIZE + 1);

        // WHY : Assumptions: the keyset predicate is the SINGLE sixteen-character card number in both
        //       directions and never a composite of it with the account identifier, which is the
        //       highest-consequence assumption in this class because a two-column predicate would page
        //       differently at every boundary where two cards share a number prefix. All six record
        //       identifiers of the reference browse name the X(16) member WS-CARD-RID-CARDNUM and pass
        //       ITS length as the key length -- at app/cbl/COCRDLIC.cbl:1131-1132 and :1150-1151 and
        //       :1201-1202 going forward, and at :1275-1276 and :1298-1299 and :1326-1327 going
        //       backward -- never the twenty-seven-byte group that encloses it at :137-141. Every
        //       statement that would have added the account identifier to that record identifier is
        //       commented out, at :449, :476, :491, :507 and :577. The browse also runs on the base
        //       cluster, named by the literal 'CARDDAT ' at :213-214, and the cluster definition agrees
        //       from outside the program entirely with KEYS(16 0) at app/jcl/CARDFILE.jcl:54 -- sixteen
        //       bytes at offset zero. Because that key is unique, no tie-breaking column is needed and
        //       paging by it is total.
        // WHY : Alternatives Considered: positioning a page by counting rows from the start of the
        //       ordered set -- offset paging -- was evaluated and REJECTED on a specific correctness
        //       failure. The number of rows preceding a position changes underneath a reader when rows
        //       are inserted or removed between two requests, so a page positioned that way omits rows
        //       it never showed and repeats rows it already showed, whereas a key already read keeps
        //       its place in the ordering whatever is inserted around it. That concurrency is not
        //       hypothetical in this system: app/cbl/COBIL00C.cbl:210-219 takes a highest-key reading
        //       and increments it across six statements with nothing holding a lock across them, so two
        //       readers can interleave there. This is why no signature in this class accepts a page
        //       number, a row offset or a paging abstraction that carries either.
        // WHY : Assumptions: the backward direction is honoured only when a cursor came with it,
        //       because a backward step is expressible only from a page already returned. Falling
        //       through to the forward read otherwise is the reference's own behaviour and not a
        //       convenience: the backward paging key on the opening page still re-reads the page, at
        //       app/cbl/COCRDLIC.cbl:444-453, and only adds the refusal sentence carried by
        //       MESSAGE_NO_PREVIOUS_PAGES.
        boolean readBackward = backward && position != null;

        // WHY : Refactoring Rationale: THIS is the statement the four browse verbs collapse into, so the
        //       correspondence is recorded at the site rather than only in the class documentation. The
        //       reference needs four verbs because an indexed browse is a positioned conversation: it
        //       must be opened at a key, stepped one record at a time, and closed again, which is why
        //       app/cbl/COCRDLIC.cbl issues STARTBR at :1129 and :1273, steps with READNEXT at :1146 and
        //       :1197 or READPREV at :1294 and :1322, and closes with ENDBR at :1258 and :1376. A query
        //       is not a conversation -- it states the whole page as one predicate with an ordering and a
        //       bound -- so opening and closing have nothing to express and disappear, and the stepping
        //       becomes the ordering. What made the four-verb form unsuitable here is that difference in
        //       what the two data stores offer, not any shortcoming of the reference: a positioned
        //       browse holds store-side state for the duration, and this platform serves each request
        //       without one. The ternary is therefore the whole of 9000-READ-FORWARD. and
        //       9100-READ-BACKWARDS., and the two paragraphs' remaining content -- the row counter, the
        //       display array and the probe bookkeeping -- has no counterpart because a list and a
        //       bounded query already carry it.
        // WHY : Assumptions: the third argument of both queries -- the card-number narrowing -- is
        //       always absent here, and that is a published decision rather than an unfinished one. The
        //       reference list screen has two narrowing fields and applies both independently at
        //       app/cbl/COCRDLIC.cbl:1385-1390 and :1396-1401, but the published contract of this
        //       service declares only the account one, because a query string and a path segment are
        //       both written verbatim into the load balancer's access log before any application code
        //       runs, and the second field's value is a primary account number. The second field is
        //       served instead by CardViewService.viewByCardNumber(String), which takes the number in a
        //       request body and answers
        //       with the card itself. The refusal sentence that field would have raised is still
        //       carried, at MESSAGE_CARD_FILTER_INVALID.
        List<Card> rows = readBackward
                ? reversed(this.cards.findBackwardFromCursor(position, narrowing, null, limit))
                : this.cards.findForwardFromCursor(position, narrowing, null, limit);

        // WHY : Refactoring Rationale: a backward read that finds NO row before its cursor is answered
        //       with the page beginning AT that cursor, and never with an empty page. The reference is
        //       explicit about this outcome: the arm at app/cbl/COCRDLIC.cbl:443-444 pairs the backward
        //       paging key with the opening-page condition, moves the page's own first card number into
        //       the record identifier at :445-446 and then performs 9000-READ-FORWARD at :449-450 --
        //       the FORWARD paragraph -- before sending the map at :451-452, while :901-903 adds the
        //       sentence carried by MESSAGE_NO_PREVIOUS_PAGES. So the browse stays where it is and the
        //       operator keeps the rows in front of them. Publishing an exhausted page instead replaced
        //       a caller's rows with nothing, which is the one outcome the reference never produces
        //       from that key press.
        // WHY : Assumptions: re-reading the OPENING page is exactly re-reading forward from the
        //       cursor, so this is a substitution and not an approximation. The backward query returned
        //       no row strictly less than the cursor within this narrowing, which means the cursor names
        //       the lowest key the narrowing holds; the opening page is the first PAGE_SIZE rows of that
        //       same ordering, so it begins at that very row. The alternative -- an inclusive forward
        //       query keyed at the cursor -- would need a third repository predicate to state the same
        //       page, and a predicate that exists only for this branch is one nothing else exercises.
        // WHY : Assumptions: the direction flag is cleared with the read, because everything downstream
        //       of it describes the read that actually ran. The rows now arrive ascending from a forward
        //       query, so the surplus row is the highest rather than the lowest and the trim in
        //       pageOf(List, boolean, Long, String) must take the forward end; the further-page answer
        //       likewise becomes the surplus rather than the unconditional true a backward page carries.
        //       Leaving it set would trim the wrong end and drop the first row of the page it just
        //       recovered.
        // WHY : Assumptions: this leaves the empty envelope reachable only where it is the truth --
        //       a narrowing that matches nothing, or an empty table -- which is the state the reference
        //       reports at :1241-1244 through WS-NO-RECORDS-FOUND rather than through a paging key.
        if (readBackward && rows.isEmpty()) {
            rows = this.cards.findForwardFromCursor(null, narrowing, null, limit);
            readBackward = false;
        }

        // WHY : Assumptions: what is recorded is which read ran and whether it was narrowed, never the
        //       account identifier or any card number. A log line is a durable record, and the whole
        //       reason the card narrowing was withdrawn from the request line above was to keep such a
        //       value out of durable records; reinstating it one hop later in this service's own log
        //       would defeat that.
        LOG.debug("event=card.list.read accountNarrowed={} paged={} backward={}",
                narrowing != null, position != null, readBackward);

        // WHY : Alternatives Considered: assembling the envelope in default methods on CardRepository,
        //       so that the surplus trim and the boundary capture would have one owner shared with any
        //       other browse. That is not available, and the constraint is hard rather than stylistic:
        //       the envelope's canonical constructor admits a cursor component only if it is a token
        //       sealed by CursorToken, and sealing needs key material that an interface cannot hold, so
        //       a default method could offer only a raw key and every non-empty page it built would be
        //       refused. The repository records the same rejection from its own side, under "Where the
        //       envelope is assembled, and why not here". This layer holds the signer, so this layer
        //       assembles the page -- and it does so in ONE private method rather than inline in each
        //       direction, so the two directions cannot drift apart on where the surplus row sits.
        return this.mapper.toSummaryPage(
                pageOf(rows, readBackward, narrowing, subject));
    }

    /**
     * Reports whether a backward step is available from a page a caller holds, given where in the walk
     * that caller stands.
     *
     * <p>Refactoring Rationale: the answer is composed from TWO facts because only one of them is a
     * property of the page, and reading it off the page alone was reported as a defect against this
     * service. A page names the position a backward request seeks from, which the envelope's leading
     * boundary carries; whether a row waits at that position depends on where the CALLER stands in the
     * walk, and the reference keeps that on the terminal side. {@code app/cbl/COCRDLIC.cbl:237-238}
     * declares the one-digit page ordinal with {@code 88 CA-FIRST-PAGE VALUE 1}, {@code :902-903} raises
     * {@code 'NO PREVIOUS PAGES TO DISPLAY'} on that condition without reading anything, and
     * {@code :492} and {@code :508} move the ordinal as the two paging keys are pressed. Every page
     * carrying rows names its own first row, so the leading boundary alone reported a backward step as
     * available on the OPENING page -- the one page the reference refuses it from.
     *
     * <p>Alternatives Considered: publishing the answer as a fifth component of the shared envelope, so
     * that it could be read off the page with no second argument. Rejected because the envelope declares
     * exactly four members and every consumer of it -- this context's contract, the browser client and
     * the sibling paged contracts -- declares the same four; and because a service-computed answer would
     * be a read of the row before a position nobody asked for, stale by the time an operator acted on it.
     * The ordinal's migrated home is the SPA's own navigation state, which is where
     * {@code ui/src/screens/cardList} holds it.
     *
     * <p>Assumptions: the caller supplies the opening-page fact because only the caller has it. This
     * service is stateless, so the request that produced a page is gone by the time the answer is wanted;
     * a caller knows it either from its own page ordinal or, equivalently, from whether it supplied a
     * cursor to obtain the page.
     *
     * @param page the page the caller currently holds; must not be {@code null}
     * @param openingPage whether that page was opened with no cursor, which is the target's form of
     *     {@code 88 CA-FIRST-PAGE VALUE 1} at {@code app/cbl/COCRDLIC.cbl:238}
     * @return {@code true} when a backward step is available -- the page names a leading boundary AND the
     *     caller is not standing on the opening page
     * @throws NullPointerException if {@code page} is {@code null}
     */
    public static boolean backwardAvailable(PageResponse<?> page, boolean openingPage) {
        Objects.requireNonNull(page, "page must not be null");
        return !openingPage && page.firstKey() != null;
    }

    /**
     * Chooses the notice that accompanies a page, transcribing the reference message selection.
     *
     * <p>This is the migrated form of {@code 1400-SETUP-MESSAGE.} at
     * {@code app/cbl/COCRDLIC.cbl:895}, restricted to the arms that report on what a read found.
     *
     * <p>Assumptions: the three outcomes and their precedence are the reference's own. A read that
     * placed no row on the opening page ends at {@code :1241-1244} having set the no-records condition,
     * which overwrites the end-of-file notice the same arm set moments earlier at {@code :1238-1240} --
     * so an empty page reports {@link #MESSAGE_NO_RECORDS_FOUND} and not
     * {@link #MESSAGE_NO_MORE_RECORDS}, and that ordering is reproduced here rather than guessed. A read
     * that returned rows but found no further one reports {@link #MESSAGE_NO_MORE_RECORDS}, the value
     * both end-of-file arms move in at {@code :1219} and {@code :1239}. Anything else reports
     * {@link #MESSAGE_INFORM_RECORD_ACTIONS}, which the reference sets when a further page exists, at
     * {@code :917-919}.
     *
     * @param page the page just produced, whose row count and further-page indicator select the notice;
     *     must not be {@code null}
     * @return the reference notice for that page, verbatim; never {@code null} and never blank
     * @throws NullPointerException if {@code page} is {@code null}
     */
    public static String pageMessage(PageResponse<?> page) {
        Objects.requireNonNull(page, "page must not be null");

        // WHY : Assumptions: an exhausted read and an unmatched search are DIFFERENT sentences in the
        //       reference, and conflating them would lose the distinction an operator reads. The
        //       shorter sentence that sits beside the no-records condition at app/cbl/COCRDLIC.cbl:1243
        //       is commented out there, so the value carried here is the condition's own, declared at
        //       :121-122 of that same program.
        if (page.items().isEmpty()) {
            return MESSAGE_NO_RECORDS_FOUND;
        }

        // WHY : Assumptions: the further-page indicator is what settles this, and it is accurate here
        //       in a way the reference's own is not. The reference decides it from the probe read at
        //       app/cbl/COCRDLIC.cbl:1197 WITHOUT running its filter paragraph over the probed row --
        //       9500-FILTER-RECORDS. is performed at :1159 and :1335 of that program only -- so under an
        //       active narrowing its indicator can report a further page when no further MATCHING row
        //       exists. In this target the narrowing is a predicate of the query, so the surplus row is
        //       narrowed too and the indicator cannot over-report. That is a documented behavioural
        //       divergence under Rule T9 of the
        //       migration, registered in docs/architecture/cobol-to-service-traceability.md: the
        //       reference does the first, this implements the second, and neither is described as a
        //       correction of the other.
        return page.hasNext() ? MESSAGE_INFORM_RECORD_ACTIONS : MESSAGE_NO_MORE_RECORDS;
    }

    /**
     * Reports the refusal a paging step in one direction would earn, when it would earn one.
     *
     * <p>This is the migrated form of the two paging-key arms of {@code 1400-SETUP-MESSAGE.}, at
     * {@code app/cbl/COCRDLIC.cbl:901-904} and {@code :905-909}.
     *
     * <p>Assumptions: these two sentences answer a KEY PRESS and are therefore separate from
     * {@link #pageMessage(PageResponse)}, which reports what a read found. The reference keeps them
     * separate too, which is why {@link #MESSAGE_NO_MORE_PAGES} at {@code :908} and
     * {@link #MESSAGE_NO_MORE_RECORDS} at {@code :1219} are different strings rather than one string
     * used twice. Backward is the seventh paging key and forward the eighth, from the attention
     * identifiers those two arms test.
     *
     * <p>Refactoring Rationale: the BACKWARD arm now refuses the step BOTH when the page names no
     * leading boundary and when the caller stands on the opening page, which is the whole of the
     * reference's own condition at {@code :901-903} over the ordinal declared at {@code :237-238}. It
     * previously refused only the first of the two, and because every page carrying rows names its own
     * first row that left the opening page reporting no refusal at all -- so a caller following this
     * answer issued a backward request the reference would have refused. The opening-page half arrives as
     * an argument rather than being read off the page for the reason recorded on
     * {@link #backwardAvailable(PageResponse, boolean)}: a stateless service does not hold the ordinal.
     *
     * <p>Assumptions: the service also declines to publish an exhausted page for that request, so a
     * caller that ignores this answer and pages back from the opening page anyway keeps its rows -- see
     * the fallback in {@link #list(Long, String, boolean, String)}. The two are complementary rather than
     * redundant: this method is how a client renders the reference's sentence without issuing a request
     * at all, and the fallback is what the reference does when the key is pressed regardless.
     *
     * @param page the page the caller currently holds, from which the step would be taken; must not be
     *     {@code null}
     * @param backward whether the step in question is backward rather than forward
     * @param openingPage whether the page in hand was opened with no cursor, the target's form of
     *     {@code 88 CA-FIRST-PAGE VALUE 1} at {@code app/cbl/COCRDLIC.cbl:238}; read only by the backward
     *     arm, since forward availability is a property of the page alone
     * @return the reference refusal for a step that is not available from this page, or an empty optional
     *     when the step is available; never {@code null}
     * @throws NullPointerException if {@code page} is {@code null}
     */
    public static Optional<String> pagingRefusal(PageResponse<?> page, boolean backward,
            boolean openingPage) {
        Objects.requireNonNull(page, "page must not be null");

        // WHY : Assumptions: the backward answer is the composed availability recorded on
        //       backwardAvailable(PageResponse, boolean), and forward availability is the further-page
        //       indicator rather than the presence of the trailing token. The asymmetry is the
        //       envelope's: a final page still names its trailing boundary, because that is what a
        //       backward step off it seeks from, so reading forward availability from that token's
        //       presence would offer a forward step on every last page.
        // WHY : Assumptions: the opening-page argument is deliberately NOT consulted on the forward arm.
        //       The reference's forward refusal at :905-908 tests its next-page indicator and its
        //       last-page-displayed flag and never the ordinal, and the opening page of a set with more
        //       than one page genuinely does have a page ahead of it.
        if (backward) {
            return backwardAvailable(page, openingPage) ? Optional.empty()
                    : Optional.of(MESSAGE_NO_PREVIOUS_PAGES);
        }

        return page.hasNext() ? Optional.empty() : Optional.of(MESSAGE_NO_MORE_PAGES);
    }

    /**
     * Classifies an account narrowing as acceptable, not supplied, or not acceptable.
     *
     * <p>This is the migrated form of {@code 2210-EDIT-ACCOUNT.} at {@code app/cbl/COCRDLIC.cbl:1003},
     * whose exit is at {@code :1032}.
     *
     * <p>Assumptions: the flag is bound to the shared kernel's SEMANTIC states and never to a byte
     * value, and that distinction is not pedantry here -- the two encodings genuinely disagree. The card
     * programs encode not-acceptable as {@code '0'}, acceptable as {@code '1'} and blank as a space, at
     * {@code app/cbl/COCRDLIC.cbl:62-64} for this field and {@code :66-68} for the card one, while the
     * copybook the shared flag is modelled on encodes acceptable as low values, not-acceptable as
     * {@code '0'} and blank as {@code 'B'}, at {@code app/cpy/CSUTLDWY.cpy:43-57}. Only the
     * not-acceptable code is common to both, so a comparison against a literal byte would be right for
     * one screen and wrong for the other. The shared type admits both spellings explicitly through its
     * alternate acceptable and blank codes, which is the evidence that the states rather than the bytes
     * are the contract.
     *
     * @param accountId the account narrowing as it arrived, already parsed, or {@code null} when the
     *     caller sent none
     * @return the state of that narrowing: not supplied, acceptable, or not acceptable; never
     *     {@code null}
     */
    private static FieldValidationFlag accountFilterState(Long accountId) {

        // WHY : Assumptions: the reference treats a ZERO as not supplied and not as a narrowing on
        //       account zero, and it says so in the same condition that tests for an empty field:
        //       app/cbl/COCRDLIC.cbl:1007-1009 tests low values, spaces AND the numeric overlay equal
        //       to zeros, all three reaching the same not-supplied exit at :1010-1012. Passing a zero
        //       through as a predicate would narrow to an account the reference would have listed
        //       across, which is a behavioural difference no caller could see in the response.
        // WHY : Alternatives Considered: refusing an all-zero narrowing with
        //       MESSAGE_ACCOUNT_FILTER_INVALID, so that a caller which meant to narrow and sent zeros
        //       learns that it did not. Rejected because it is not this screen's behaviour. The
        //       sentence 'Account number must be a non zero 11 digit number' belongs to the card UPDATE
        //       screen at app/cbl/COCRDUPC.cbl:189-192, where the account is a MANDATORY field, and the
        //       polarity of the two screens differs by design: this program pre-sets its filter flag
        //       blank at :1004 and returns through :1010-1012 without raising its input-error
        //       condition, whereas the detail program pre-sets not-acceptable at
        //       app/cbl/COCRDSLC.cbl:648 and :688 and does raise it. Refusing here would import one
        //       screen's mandatory-field rule into the other's optional filter and change an observable
        //       outcome, which the migration admits only as a registered divergence. The published
        //       contract's own account narrowing description carried that sentence and has been
        //       corrected to cite this paragraph instead, so the document and this branch now agree.
        if (accountId == null || accountId == 0L) {
            return FieldValidationFlag.BLANK;
        }

        // WHY : Assumptions: what remains checkable at this layer is the WIDTH of the domain and not
        //       whether the value was numeric. The reference's own gate is a numeric test, at
        //       app/cbl/COCRDLIC.cbl:1017, because the value reaches it as eleven characters that may
        //       hold anything; here it
        //       arrives already parsed, so a non-numeric value was refused before this method -- by the
        //       eleven-digit pattern the request body declares and by the parse the handler performs.
        //       The half of that gate that survives is the one the reference's own refusal sentence
        //       names, an eleven-digit number, because a parsed value can still be wider than eleven
        //       digits or negative and no eleven-digit field could have carried it.
        return accountId < 0L || accountId > ACCOUNT_FILTER_CEILING
                ? FieldValidationFlag.NOT_OK
                : FieldValidationFlag.VALID;
    }

    /**
     * Applies the account narrowing gate and returns the predicate value the query should use.
     *
     * <p>Assumptions: a not-supplied narrowing becomes an absent predicate rather than a refusal,
     * because this screen's narrowings are optional -- the reference pre-sets the blank state at
     * {@code app/cbl/COCRDLIC.cbl:1004} and returns through {@code :1010-1012} without setting the input
     * error. So the blank state raises nothing here, and the screen marker the shared flag would render
     * for a blank field is deliberately not reached on this path: a marker is how the reference draws
     * attention to a field a user should have filled in, and on this screen an empty narrowing is a
     * legitimate request for the whole set.
     *
     * @param accountId the account narrowing as it arrived, already parsed, or {@code null} when the
     *     caller sent none
     * @return the account identifier to narrow the query by, or {@code null} to narrow nothing
     * @throws ClientInputException if the narrowing is outside the eleven-digit domain
     */
    private static Long acceptedAccountFilter(Long accountId) {

        FieldValidationFlag state = accountFilterState(accountId);

        // WHY : Trade-offs: the refusal is raised as ONE field error carrying the reference sentence,
        //       whereas the reference has a single seventy-five-character message field -- declared at
        //       app/cbl/COCRDLIC.cbl:117 with its clear condition at :118 -- guarded by a
        //       first-error-wins latch, so only the first complaint of a submission is ever seen. The
        //       latch is visible at :1056 around the card narrowing's sentence and again at :1218 and
        //       :1238 around the end-of-file notices. What is accepted in exchange is that a caller of
        //       this target may receive an accumulating array of field errors rather than one sentence,
        //       which is a strictly larger answer than the reference could render on a fixed-width line;
        //       what is bought is that a caller fixing two fields need not submit twice to discover the
        //       second. The shared carrier names the field as well as the sentence, so the array can be
        //       rendered back onto the offending control, and it carries the state so the blank marker
        //       of app/cbl/COCRDLIC.cbl:755-759 is rendered by whichever client wants it. That marker
        //       pattern is the one templated at app/cpy/CSSETATY.cpy:17-27, which no card program
        //       includes -- each inlines it -- so it is cited as the pattern and never as a source this
        //       class shares. Its re-entry gate at :20 has no counterpart here at all: a stateless
        //       handler has no first-entry-versus-re-entry distinction to make, so the error is driven
        //       purely by the response rather than by a remembered turn.
        // WHY : Assumptions: the test is against the not-acceptable state SPECIFICALLY and deliberately
        //       not against the shared flag's own error predicate, which answers true for the
        //       never-supplied state as well. That predicate is right for the template it was migrated
        //       from -- app/cpy/CSSETATY.cpy:18-19 asks one disjunction over both conditions, because
        //       the fields it highlights are MANDATORY and a blank one is a complaint. The narrowings
        //       of this screen are optional, established by the blank state the reference pre-sets at
        //       app/cbl/COCRDLIC.cbl:1004, so here a never-supplied narrowing is a legitimate request
        //       for the whole set and must not be reported. This is the concrete reason the flag is
        //       bound by state and not through a convenience predicate: the predicate encodes one
        //       screen's optionality, and the two screens differ.
        if (state == FieldValidationFlag.NOT_OK) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_ACCOUNT_FILTER, state,
                    MESSAGE_ACCOUNT_FILTER_INVALID);
        }

        return state.isValid() ? accountId : null;
    }

    /**
     * Trims the surplus row off a read and seals the two boundary cursors the caller pages on.
     *
     * <p>Assumptions: a backward read's surplus row is the LOWEST one, because that read walked down
     * from the cursor and the rows were reversed to ascending before arriving here. Trimming the same
     * end in both directions would drop a row the caller should see and keep one it should not. The
     * reference reaches the same arrangement from the other side, filling its display array from the
     * bottom upward: it sets its row counter to one past the screen limit at
     * {@code app/cbl/COCRDLIC.cbl:1284-1286} and decrements it at {@code :1307} and {@code :1346} as
     * each row arrives, capturing the leading key when the counter reaches zero at {@code :1347-1353} --
     * so the row it read first lands in the last display position.
     *
     * @param rows the rows read, at most the page size plus one, in ascending order
     * @param backward whether the read travelled backward, which decides which end the surplus came from
     * @param narrowing the account the page was narrowed to, or {@code null} for an unnarrowed page;
     *     part of every cursor's binding so a cursor cannot be carried to a differently narrowed browse
     * @param subject the authenticated caller both boundary cursors are bound to
     * @return the page of rows with both boundary cursors sealed under their own direction; never
     *     {@code null}
     * @throws IllegalArgumentException if a sealed boundary is rejected by the envelope's own cursor
     *     check, which no value produced here can provoke
     */
    private PageResponse<Card> pageOf(List<Card> rows, boolean backward,
            Long narrowing, String subject) {

        boolean hasSurplus = rows.size() > PAGE_SIZE;

        // WHY : Assumptions: WHICH END the surplus row is trimmed from depends on the direction, and
        //       getting it the wrong way round would drop a row the caller should see while keeping one
        //       it should not -- a difference of exactly one row at every page boundary, which is the
        //       kind of error that produces plausible pages. A forward read arrives ascending, so its
        //       surplus is the HIGHEST row and the page is the first PAGE_SIZE of it. A backward read
        //       arrives descending and was reversed to ascending before this method, so its surplus --
        //       the row furthest from the cursor -- is now the LOWEST, and the page is everything after
        //       the first. The reference reaches the same arrangement from the other side by filling its
        //       display array from the bottom upward: app/cbl/COCRDLIC.cbl:1284-1286 sets its row
        //       counter one past the screen limit and :1307 and :1346 decrement it as rows arrive, so
        //       the row it read first lands in the last display position.
        List<Card> shown = !hasSurplus ? rows
                : backward ? rows.subList(1, rows.size())
                        : rows.subList(0, PAGE_SIZE);

        // WHY : Assumptions: an exhausted read is reported through the envelope's own exhausted page
        //       rather than by sealing boundaries over nothing, because that page is the state the
        //       reference reaches on an end-of-file response at app/cbl/COCRDLIC.cbl:1215-1221 and
        //       because the envelope refuses a row-bearing page that names no boundary. Reaching this
        //       with rows already filtered away cannot happen here: the narrowing is a predicate of the
        //       query, so a row that arrives has already matched.
        if (shown.isEmpty()) {
            return PageResponse.empty();
        }

        // WHY : Assumptions: both boundaries are taken from the TRIMMED page, so each names a row the
        //       caller actually received. The reference does something different for its trailing
        //       boundary and the divergence is documented rather than inherited: app/cbl/COCRDLIC.cbl
        //       captures the last displayed row's key at :1194-1195 and then OVERWRITES it with the
        //       probe row's key at :1212-1214, so the value it retains identifies the eighth record of a
        //       page of seven -- a
        //       row the terminal never displayed. Publishing that would advance the cursor one row too
        //       far and drop a row from the following page, and a caller could not verify a token
        //       naming a row it never received. Page boundaries are unaffected by the choice: with seven
        //       rows to a page, resuming strictly after the key of row seven yields rows eight to
        //       fourteen, which is exactly the reference's second page. Registered as a documented
        //       divergence under Rule T9.
        // WHY : Refactoring Rationale: the further-page indicator is NOT the surplus row alone, which is
        //       what this note previously claimed and what the expression previously computed. On a
        //       BACKWARD read a following page necessarily exists -- the caller reached this page by
        //       stepping back from one, so the page it came from is still there -- and the surplus row of
        //       a backward read lies in the other direction entirely. Reporting the surplus for both
        //       directions therefore answered a backward-read caller that nothing lay ahead whenever the
        //       backward read happened to exhaust its own end, and
        //       pagingRefusal(PageResponse, boolean, boolean)
        //       turns that answer into MESSAGE_NO_MORE_PAGES and withholds the step. The observable
        //       consequence was the reported one: stepping back from the opening page and then forward
        //       again was refused instead of returning the page the caller had just left. The two
        //       availability answers are therefore derived from different facts, each stated on its own
        //       branch below.
        // WHY : Assumptions: a duplicate-key response is a NORMAL outcome for this browse and never a
        //       failure, which the reference states by pairing it with the normal response in every arm
        //       that inspects a read -- at :1157 with :1158, at :1208 with :1209, at :1305 with :1306 and
        //       at :1333 with :1334, each proceeding to filter and display the row. It arises because the
        //       secondary access path is declared with a non-unique key, so nothing here models it as an
        //       error either.
        // WHY : Refactoring Rationale: backward availability is NOT reported and this envelope carries
        //       the backward POSITION instead, because the reference answers that question from the
        //       TERMINAL's own page ordinal and never from a read of the file: app/cbl/COCRDLIC.cbl:238
        //       declares 88 CA-FIRST-PAGE VALUE 1 over the one-digit ordinal at :237, :902-903 raises
        //       'NO PREVIOUS PAGES TO DISPLAY' on that condition alone, and :492 and :508 move the
        //       ordinal up and down as the two paging keys are pressed. The ordinal lived in the
        //       communication area the screen carried between turns, so its migrated home is the SPA's
        //       navigation state -- ui/src/screens/cardList holds it and withholds the backward key on
        //       the first page. Publishing a fifth component here would answer from the service what the
        //       reference answers from the client, and would put this envelope out of agreement with the
        //       four members every consumer of it declares.
        // WHY : Assumptions: the leading boundary is sealed under the BACKWARD binding and the trailing
        //       one under the FORWARD binding, because each is only ever presented in one direction. That
        //       is what makes the pair non-interchangeable: a holder who sent the trailing cursor with a
        //       backward request would be naming the row it had just been shown as the row to read back
        //       from, and the binding refuses it rather than returning a page that overlaps the one in
        //       hand. Sealing both under one binding would leave that swap indistinguishable from a
        //       legitimate step.
        return PageResponse.ofRows(new ArrayList<>(shown),
                this.cursorToken.seal(listCursorBinding(narrowing, subject, true),
                        shown.getFirst().getCardNum()),
                this.cursorToken.seal(listCursorBinding(narrowing, subject, false),
                        shown.getLast().getCardNum()),
                backward || hasSurplus);
    }

    /**
     * Composes the authenticated binding every cursor of this browse is sealed and opened under.
     *
     * <p>Assumptions: the binding is associated data rather than payload. {@code CursorToken} carries it
     * into the cipher as the authenticated-but-unencrypted part, so it is never transmitted and cannot be
     * edited by the holder; a token presented under any other binding fails authentication before its
     * contents are decrypted. That is why every predicate that shaped the page belongs here rather than
     * beside the token in the response.</p>
     *
     * <p>Assumptions: the two scope elements are composed through {@link CursorToken#scope(String...)},
     * which length-prefixes each part, so no pair of elements can concatenate into the same string as a
     * different pair. Joining them with a separator character was rejected: a separator has to be
     * escaped inside a part or the composition stops being injective, and an account rendering is exactly
     * the kind of part a future change might widen.</p>
     *
     * @param narrowing the account the browse was narrowed to, or {@code null} when it was not
     * @param subject the authenticated caller, which must not be blank
     * @param backward whether the cursor being sealed or opened is the backward-facing one
     * @return the binding string; never {@code null}
     * <p>Trade-offs: package-private rather than private. Widening it lets this module's own tests assert
     * the exact binding a token must be presented under, which is the property CARD-02 turns on, instead
     * of asserting it indirectly through a round trip that would pass even if the composition were wrong.
     * The cost is that a sibling class in this package could compose a binding of its own; that is
     * accepted because the package holds only this context's services and the alternative -- keeping it
     * private and re-deriving the string inside the test from literals -- would put the scope vocabulary
     * in two places, which is how the two come to disagree.</p>
     *
     * @throws IllegalArgumentException if the subject is blank, raised by the composition itself, because
     *     a blank subject would bind every caller's cursors identically
     */
    static String listCursorBinding(Long narrowing, String subject, boolean backward) {

        String accountScope = narrowing == null ? CURSOR_SCOPE_ALL_ACCOUNTS
                : CURSOR_SCOPE_ACCOUNT_PREFIX
                        + String.format(Locale.ROOT, CURSOR_SCOPE_ACCOUNT_FORMAT, narrowing);

        return CursorToken.binding(LIST_BINDING, subject, CursorToken.scope(
                backward ? CURSOR_SCOPE_BACKWARD : CURSOR_SCOPE_FORWARD, accountScope));
    }

    /**
     * Returns the rows in the opposite order, without disturbing the list the store returned.
     *
     * <p>Assumptions: a copy is reversed rather than the argument, because the list a repository returns
     * may be a view the persistence context still owns, and reversing it in place would reorder that
     * view as a side effect of rendering a page.
     *
     * @param rows the rows to reverse, in the order the store returned them
     * @return a new list holding the same rows in the opposite order; never {@code null}
     */
    private static List<Card> reversed(List<Card> rows) {

        List<Card> copy = new ArrayList<>(rows);
        return copy.reversed();
    }
}
