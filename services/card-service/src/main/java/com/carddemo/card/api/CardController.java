package com.carddemo.card.api;

import com.carddemo.card.dto.AdminCardDetail;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.dto.CardLookupRequest;
import com.carddemo.card.dto.CardPageQuery;
import com.carddemo.card.dto.CardSummary;
import com.carddemo.card.dto.CardUpdateRequest;
import com.carddemo.card.service.CardListService;
import com.carddemo.card.service.CardUpdateService;
import com.carddemo.common.web.PageResponse;
import jakarta.validation.Valid;
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
 * {@code CCDL} and the update {@code CCUP}, defined at {@code app/csd/CARDDEMO.CSD} lines 337 to 358 and
 * driven by {@code app/cbl/COCRDLIC.cbl}, {@code app/cbl/COCRDSLC.cbl} and {@code app/cbl/COCRDUPC.cbl}.
 * Five operations serve three transactions because the migration splits two concerns the reference merged:
 * the number-to-row resolution the list screen performed inline becomes its own lookup, and the
 * administrative disclosure of a full card number becomes its own route behind its own authority.
 *
 * <p>Refactoring Rationale: this context published five operations, gated three path patterns in its filter
 * chain, and served nothing -- it had no adapter and no service at all, so the mapper had no construction
 * site and the repository's two keyset queries and its account-keyed read had no caller. Every published
 * operation is mounted here now, and each one drives the repository declaration the contract's own
 * description says it drives.
 *
 * <h2>Why a card number appears in exactly one place</h2>
 *
 * <p>Assumptions: every path in this class names a card by its opaque selector, and the only operation that
 * accepts a card number does so in a request BODY. That asymmetry is the disclosure boundary of this
 * context, and it is structural rather than advisory: a load balancer writes the request line of every
 * request into a durable access-log object before any application code runs, and a browser retains history,
 * while neither retains a body. The reference never faced the choice because the number it navigated with
 * never left the region -- {@code app/cbl/COCRDLIC.cbl} moves it from its own working storage into the
 * communication area on selection, at lines 532 to 534 and 560 to 562.
 *
 * <h2>Where authorization is decided</h2>
 *
 * <p>Assumptions: no authorization annotation appears on any method here, because the filter chain in
 * {@code com.carddemo.card.config.SecurityConfig} has already decided it -- the collection, the subtree and
 * the administrative pattern each carry their own authority rule. A method-level check would be a second
 * rule that could disagree with the first, and the contract's {@code x-required-authority} is asserted
 * against the chain's table by a contract test rather than against annotations here.
 *
 * <p>Trade-offs: this class carries no springdoc annotation, following the convention every adapter in this
 * migration follows: the hand-authored contract is the document of record, and annotating a handler would
 * put a second, editable copy of it in the code and make drift expressible at all.
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

    /** The direction value that reads the page preceding the cursor. */
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

    /** Serves the browse, the two detail reads and the lookup. */
    private final CardListService reads;

    /** Serves the one edit this context publishes. */
    private final CardUpdateService writes;

    /**
     * Binds the read service and the update service.
     *
     * @param reads the service serving the browse, the detail reads and the lookup; must not be
     *     {@code null}
     * @param writes the service serving the edit; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public CardController(CardListService reads, CardUpdateService writes) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.writes = Objects.requireNonNull(writes, "writes");
    }

    /**
     * Reads one page of cards, optionally narrowed to one account.
     *
     * <p>Assumptions: the account filter is validated as eleven digits by {@link CardPageQuery} itself,
     * which is the constraint the reference states in the message it raises for a bad value --
     * {@code 'Account number must be a non zero 11 digit number'} at {@code app/cbl/COCRDUPC.cbl} lines
     * 189 to 192. Declaring it on the body rather than on this parameter is what makes a malformed
     * narrowing a 400 naming the field, instead of a value reaching a repository predicate that would
     * answer with an empty page a caller could not distinguish from a real one.
     *
     * <p>Assumptions: the direction is compared against the published value rather than bound to an enum,
     * because this context's contract declares only the two values and the forward one is the default. A
     * value that is neither reads forward, which is what the contract's default says it should do.
     *
     * <p>Assumptions: this is a POST although it performs no write, for the reason recorded on
     * {@link #SEARCH_PATH}: the narrowing is an account identifier and a query string is written into the
     * load balancer's access log before this method is entered.
     *
     * @param query the criteria -- an optional account narrowing, an optional cursor and an optional
     *     direction. An absent body lists the whole collection one page at a time, which is the list
     *     screen's initial state
     * @return one page of masked summaries with its boundary cursors
     */
    @PostMapping(path = SEARCH_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public PageResponse<CardSummary> listCards(
            @Valid @RequestBody(required = false) CardPageQuery query) {

        CardPageQuery criteria = query == null ? EMPTY_QUERY : query;
        String accountId = criteria.accountId();
        Long account = accountId == null || accountId.isBlank() ? null : Long.valueOf(accountId);
        return this.reads.list(account, criteria.cursor(),
                DIRECTION_PREVIOUS.equals(criteria.direction()));
    }

    /**
     * Resolves a submitted primary account number to that card's masked detail.
     *
     * <p>Assumptions: this is a POST although it performs no write, and the method is chosen for the body
     * rather than for the semantics. A GET carrying the number would put it in the query string, which is
     * part of the request target and is retained by every access log the path is.
     *
     * @param request the number to resolve; validated against the published schema before this method is
     *     entered
     * @return the card's detail with its number masked, carrying the selector every other route uses
     */
    @PostMapping(path = LOOKUP_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CardDetail lookupCard(@Valid @RequestBody CardLookupRequest request) {

        return this.reads.lookup(request.cardNumber());
    }

    /**
     * Reads one card's detail, with the primary account number masked to its last four digits.
     *
     * @param cardKey the opaque selector from a list row or a lookup, from the request path
     * @return the card's masked detail
     */
    @GetMapping(path = CARD_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public CardDetail getCard(@PathVariable(name = "cardKey") String cardKey) {

        return this.reads.readDetail(cardKey);
    }

    /**
     * Applies the submitted attributes to one card.
     *
     * <p>Assumptions: the version travels in the body rather than in an {@code If-Match} header, because
     * that is what this context's published contract declares -- the update schema requires a
     * {@code version} member. The sibling account context puts its precondition in a header instead, and the
     * two differ because their contracts differ; neither is being brought into line with the other here,
     * since a published request shape is an external interface.
     *
     * @param cardKey the opaque selector naming the card to edit, from the request path
     * @param request the attributes to apply and the version they were read at; validated against the
     *     published schema before this method is entered
     * @return the saved card's masked detail, carrying the new version
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
     * type decides what can be answered.
     *
     * @param cardKey the opaque selector naming the card to read, from the request path
     * @return the card's detail carrying the unmasked primary account number
     */
    @GetMapping(path = ADMIN_CARD_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public AdminCardDetail getAdminCardDetail(@PathVariable(name = "cardKey") String cardKey) {

        return this.reads.readAdminDetail(cardKey);
    }
}
