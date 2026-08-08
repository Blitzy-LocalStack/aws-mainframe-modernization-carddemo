package com.carddemo.card.service;

import com.carddemo.card.domain.Card;
import com.carddemo.card.dto.AdminCardDetail;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.dto.CardSummary;
import com.carddemo.card.mapper.CardMapper;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves every read of this context: the paged browse, the two detail reads and the number lookup.
 *
 * <h2>What this service is</h2>
 *
 * <p>This is the migrated successor of the reference card list program {@code app/cbl/COCRDLIC.cbl} and
 * the card detail program {@code app/cbl/COCRDSLC.cbl}. The browse replaces the CICS
 * {@code STARTBR}/{@code READNEXT}/{@code READPREV}/{@code ENDBR} sequence the list program drives at
 * {@code app/cbl/COCRDLIC.cbl} lines 1129 to 1376, and the detail read replaces the keyed read the detail
 * program performs.
 *
 * <p>Refactoring Rationale: the reads of this context had no service at all, and that is what this class
 * closes. The repository declared a forward keyset query, a backward keyset query and an account-keyed
 * read; the mapper could mint and open selectors, mask numbers and convert a page; and the contract
 * published five operations. None of it was reachable, because nothing joined the repository to the mapper.
 * Every one of those declarations now has a caller on a live route.
 *
 * <p>Assumptions: the reads of both reference programs sit on ONE class rather than two, and the
 * consolidation is deliberate. The migration plan assigns this context two services, a list service and an
 * update service, and a detail read is a read: splitting it out would add a third class holding one method
 * that shares this class's collaborators, its selector handling and its not-found refusal. The write path
 * is separate because it is a write, which is the seam that carries meaning here.
 *
 * <h2>Why the browse is keyed and not offset</h2>
 *
 * <p>Assumptions: a page is bounded by a key rather than by a row offset, which is what makes it the same
 * browse the reference runs. The list program carries the boundary keys between turns -- its own working
 * storage holds the first and last card number of the rendered page -- and reads forward or backward from
 * one of them. An offset page would skip and repeat rows when a card is inserted between two turns, which
 * is behaviour the reference does not have and which no caller could detect.
 *
 * <p>Assumptions: one row beyond the page is read so that the existence of a further page is discovered
 * rather than guessed, which is how the reference learns the same fact -- it finds more rows than it can
 * show.
 */
@Service
public class CardListService {

    /**
     * The number of rows one browse page carries.
     *
     * <p>Assumptions: seven is the reference window and not a tuning choice. The list program declares
     * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at {@code app/cbl/COCRDLIC.cbl} lines 177 to
     * 178, three separate {@code OCCURS 7 TIMES} arrays at lines 76, 86 and 255, and the map declares
     * exactly {@code CRDSEL1} through {@code CRDSEL7} in {@code app/cpy-bms/COCRDLI.CPY} -- four
     * independent statements of the same number, so it checks itself.
     */
    public static final int PAGE_SIZE = 7;

    /** The cursor binding this browse seals and opens its cursors under. */
    public static final String LIST_BINDING = "card-list";

    /**
     * The refusal raised when no card answers a selector or a submitted number.
     *
     * <p>Assumptions: this is the reference sentence, carried across character for character from
     * {@code 'Did not find this account in cards database'} at {@code app/cbl/COCRDSLC.cbl} lines 201 to
     * 202, which the published contract cites at the same lines.
     *
     * <p>Trade-offs: this sentence reaches the LOG and any in-process caller, and it does NOT reach the
     * HTTP body -- the body carries the shared kernel's own not-found sentence instead. That is stated
     * here rather than worked around, because the discrepancy is deliberate on both sides. The contract
     * references the shared {@code NotFound} response rather than declaring a sentence of its own, and
     * that response's description says a well-formed identifier standing for no row is reported the same
     * way whatever the reason -- so a service-specific sentence would make two deployments' 404 bodies
     * differ where the contract says they must not. The shared advice enforces the same conclusion
     * mechanically: it carries a service's own sentence onto a body only when the sentence ends in an
     * ellipsis, and this reference sentence does not, so it is withheld by the gate rather than by
     * omission here. Alternatives Considered: appending an ellipsis so the gate would pass it. Rejected
     * because it would alter a value whose whole purpose is to be reproduced exactly.
     */
    public static final String MESSAGE_CARD_NOT_FOUND = "Did not find this account in cards database";

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
     * <p>Assumptions: the account filter is passed into the query rather than applied after it, so a
     * filtered page is a full page of matching rows rather than whatever survives filtering a page. It is
     * also what makes the filter an indexed access path: the predicate reads through
     * {@code idx_cards_account_id}, which is the target of the alternate index the reference declares
     * non-unique at {@code app/jcl/CARDFILE.jcl} lines 85 to 87.
     *
     * <p>Assumptions: a backward read arrives in descending order and is reversed before it is rendered,
     * because a published page is always ascending whichever way the browse travelled.
     *
     * @param accountId the account whose cards to list, or {@code null} to list across all accounts
     * @param cursor the sealed cursor from a previous page, or {@code null} for the first page
     * @param backward whether to read the page preceding the cursor rather than the one following it
     * @return one page of masked summaries with its boundary cursors; never {@code null}
     * @throws CursorToken.InvalidCursorException if the cursor is not one this browse sealed
     */
    @Transactional(readOnly = true)
    public PageResponse<CardSummary> list(Long accountId, String cursor, boolean backward) {

        String position = cursor == null ? null : this.cursorToken.open(LIST_BINDING, cursor);
        Limit limit = Limit.of(PAGE_SIZE + 1);

        List<Card> rows = backward && position != null
                ? reversed(this.cards.findBackwardFromCursor(position, accountId, null, limit))
                : this.cards.findForwardFromCursor(position, accountId, null, limit);

        LOG.debug("event=card.list.read accountFiltered={} paged={} backward={}",
                accountId != null, position != null, backward);

        return this.mapper.toSummaryPage(page(rows, backward && position != null));
    }

    /**
     * Reads one card's masked detail by the opaque selector a list row carried.
     *
     * @param cardKey the sealed selector exactly as the client echoed it back
     * @return the card's detail with its number masked; never {@code null}
     * @throws NoSuchElementException if no card answers the selector
     */
    @Transactional(readOnly = true)
    public CardDetail readDetail(String cardKey) {

        return this.mapper.toDetail(require(this.mapper.openCardSelector(cardKey)));
    }

    /**
     * Reads one card's detail with its primary account number disclosed in full.
     *
     * <p>Assumptions: the disclosure is a property of the ROUTE that reaches this method rather than of
     * anything decided here -- the administrative path sits behind its own authority in
     * {@code com.carddemo.card.config.SecurityConfig}. This method exists as a separate entry point, and
     * returns a separate type, so that the ordinary read cannot reach the disclosure by any argument a
     * caller supplies.
     *
     * @param cardKey the sealed selector exactly as the client echoed it back
     * @return the card's detail carrying the unmasked number; never {@code null}
     * @throws NoSuchElementException if no card answers the selector
     */
    @Transactional(readOnly = true)
    public AdminCardDetail readAdminDetail(String cardKey) {

        Card card = require(this.mapper.openCardSelector(cardKey));
        CardDetail masked = this.mapper.toDetail(card);

        LOG.info("event=card.admin.disclosed key={}", masked.key());

        return new AdminCardDetail(masked.key(),
                this.mapper.discloseCardNumberToAdministrator(card), masked.accountId(),
                masked.embossedName(), masked.expirationDate(), masked.activeStatus(),
                masked.version());
    }

    /**
     * Resolves a submitted primary account number to that card's masked detail.
     *
     * <p>Assumptions: this is the only operation in the context that accepts a card number as an input, and
     * it returns the same masked shape the selector-addressed read returns -- so a caller that arrives by
     * number leaves holding a selector and never needs to send the number again.
     *
     * @param cardNumber the sixteen-digit primary account number to resolve
     * @return the card's detail with its number masked; never {@code null}
     * @throws NoSuchElementException if no card holds that number
     */
    @Transactional(readOnly = true)
    public CardDetail lookup(String cardNumber) {

        LOG.info("event=card.lookup.performed");

        return this.mapper.toDetail(require(cardNumber));
    }

    /**
     * Reads a card by its number or raises the not-found refusal.
     *
     * <p>Assumptions: the three single-card reads share this so they cannot disagree about what a missing
     * row means, and so that a selector that opens cleanly but names a deleted row is answered the same way
     * as a number that was never issued. The two are the same fact from a caller's side: no card answers.
     *
     * @param cardNumber the sixteen-digit number to read
     * @return the stored card; never {@code null}
     * @throws NoSuchElementException if no card holds that number
     */
    private Card require(String cardNumber) {

        return this.cards.findById(cardNumber)
                .orElseThrow(() -> new NoSuchElementException(MESSAGE_CARD_NOT_FOUND));
    }

    /**
     * Trims a surplus row off a page and seals the boundary cursors the caller pages on.
     *
     * <p>Assumptions: a backward read's surplus row is the LOWEST one, because that read walked down from
     * the cursor and the list was reversed to ascending afterwards. Trimming the same end in both
     * directions would drop a row the caller should see and keep one it should not.
     *
     * @param rows the rows read, at most the page size plus one, in ascending order
     * @param backward whether the read travelled backward, which decides which end the surplus came from
     * @return the page of rows with sealed boundary cursors; never {@code null}
     */
    private PageResponse<Card> page(List<Card> rows, boolean backward) {

        boolean hasSurplus = rows.size() > PAGE_SIZE;

        List<Card> shown = !hasSurplus ? rows
                : backward ? rows.subList(1, rows.size())
                        : rows.subList(0, PAGE_SIZE);

        if (shown.isEmpty()) {
            return PageResponse.empty();
        }

        return PageResponse.ofRows(new ArrayList<>(shown),
                this.cursorToken.seal(LIST_BINDING, shown.getFirst().getCardNum()),
                this.cursorToken.seal(LIST_BINDING, shown.getLast().getCardNum()),
                hasSurplus);
    }

    /**
     * Returns the rows in the opposite order, without disturbing the list the store returned.
     *
     * <p>Assumptions: a copy is reversed rather than the argument, because the list a repository returns may
     * be a view the persistence context still owns, and reversing it in place would reorder that view as a
     * side effect of rendering a page.
     *
     * @param rows the rows to reverse, in the order the store returned them
     * @return a new list holding the same rows in the opposite order; never {@code null}
     */
    private static List<Card> reversed(List<Card> rows) {

        List<Card> copy = new ArrayList<>(rows);
        return copy.reversed();
    }
}
