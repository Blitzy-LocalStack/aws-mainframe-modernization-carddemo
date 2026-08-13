package com.carddemo.card.service;

import com.carddemo.card.domain.Card;
import com.carddemo.card.dto.AdminCardDetail;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.mapper.CardMapper;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads one card with the primary account number rendered in full, for an administrative caller.
 *
 * <p>This is the one place in this service where a full primary account number leaves the process in a
 * response. The published contract states the same thing: {@code AdminCardDetail} is the only schema
 * carrying a {@code cardNumber} member, and it is returned by the administrative read only.</p>
 *
 * <h2>Why this is a separate service rather than a flag on the ordinary read</h2>
 *
 * <p>Alternatives Considered: a boolean parameter on {@link CardViewService}, which is fewer types and
 * one fewer bean. Rejected because it would move the disclosure decision from the type system onto
 * every call site: a caller that passed the wrong literal would widen disclosure silently, and nothing
 * would fail. As a separate bean the widening is visible in the wiring -- only the controller method
 * behind the administrative route holds a reference to this type -- and the authority requirement sits
 * on a distinct path pattern in the security configuration rather than inside a branch.</p>
 *
 * <p>Assumptions: administrative authority widens what may be rendered of the account number and
 * nothing else. The card verification value is absent from this response as it is from every other, and
 * this class never touches it -- the encrypted holder is not read here at all.</p>
 *
 * <p>Assumptions: the masked rendering is present alongside the full one, which the contract requires
 * so that a client holding one type renders either response. That is why this class composes the
 * ordinary detail shape and adds one member rather than composing a different shape.</p>
 *
 * <h2>Why this service is the only administrative read</h2>
 *
 * <p>Refactoring Rationale: this bean existed and nothing called it. The controller behind the
 * administrative route reached a second implementation on {@code CardListService} instead, so the
 * disclosure path this class documents at length was not the path that ran, and the class-level claim
 * above -- that only the controller method behind the administrative route holds a reference to this
 * type -- was false in the direction that matters: no controller method held one at all. The controller
 * is now wired here and the duplicate on the list service is withdrawn, so there is exactly one
 * implementation of the disclosure and the reasoning on this class describes code that executes.</p>
 */
@Service
public class CardAdminViewService {

    /**
     * The logger this service records each disclosure through.
     *
     * <p>Assumptions: a disclosure is the one event in this context worth an INFO record, because it is
     * the only response that renders a full primary account number and an audit reader has to be able to
     * establish that it happened.</p>
     */
    private static final Logger LOG = LoggerFactory.getLogger(CardAdminViewService.class);

    /**
     * The resolver that turns a selector into a stored row.
     */
    private final CardViewService cardView;

    /**
     * The anti-corruption layer that renders the row and, uniquely, discloses the number.
     */
    private final CardMapper mapper;

    /**
     * Builds the service from its two collaborators.
     *
     * @param cardView the selector resolver, reused rather than reimplemented so that a selector is
     *     opened and a row is required in exactly one place; must not be {@code null}
     * @param mapper the mapper that both masks and, on the one method this class calls, discloses; must
     *     not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public CardAdminViewService(CardViewService cardView, CardMapper mapper) {
        this.cardView = Objects.requireNonNull(cardView, "cardView must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * Reads the card a selector stands for, with the account number rendered in full.
     *
     * <p>Refactoring Rationale: the response was assembled as an insertion-ordered
     * {@code Map<String, Object>}, defended on the grounds that a record would have to restate the six
     * core members in a second type. That second type already exists: {@link AdminCardDetail} is what
     * {@code openapi/card-api.yaml} publishes for this operation and what the controller method returns,
     * so the map was not avoiding a duplicate shape -- it WAS the duplicate, and the one of the two that
     * no schema described. A map also cannot carry the contract: {@code Map<String, Object>} documents
     * as a free-form object, so a member renamed or dropped here would not fail any contract check.
     * Returning the record restores one shape with one schema, and the member order the map's insertion
     * order was protecting is now the record's component order, which is fixed by its declaration.</p>
     *
     * <p>Assumptions: the disclosure is recorded by the SELECTOR and never by the number it stands for.
     * An audit reader needs to know that a disclosure happened and which row it concerned; writing the
     * disclosed value would put it in a durable record and make the log a second copy of exactly what
     * the route's authority exists to restrict.</p>
     *
     * @param selector the opaque selector this service minted for the card; must not be {@code null}
     * @return the card's core state plus its full sixteen-digit account number, never {@code null}
     * @throws com.carddemo.common.error.ClientInputException if the selector is not one this service
     *     issued or can no longer be opened
     * @throws java.util.NoSuchElementException if the selector opened cleanly but names no stored
     *     row, which the shared advice renders as HTTP 404
     * @throws NullPointerException if {@code selector} is {@code null}
     */
    @Transactional(readOnly = true)
    public AdminCardDetail viewForAdministrator(String selector) {
        Objects.requireNonNull(selector, "selector must not be null");

        Card card = this.cardView.loadBySelector(selector);
        CardDetail core = this.mapper.toDetail(card);

        LOG.info("event=card.admin.disclosed key={}", core.key());

        // WHY : Refactoring Rationale: the masked rendering is carried across from the core and used to be
        //       dropped here. The resolved administrative schema requires it -- the composition is
        //       CardDetailCore plus one member, closed to anything else -- and the browser type declares it
        //       through two levels of extension, so omitting it made every successful body violate both
        //       while nothing compared the record to either. It is taken from the core rather than
        //       recomputed, so the two shapes cannot disagree about one card's masked form.
        return new AdminCardDetail(core.key(),
                core.displayCardNumber(), core.accountId(),
                core.embossedName(), core.expirationDate(), core.activeStatus(), core.version(),
                this.mapper.discloseCardNumberToAdministrator(card));
    }
}
