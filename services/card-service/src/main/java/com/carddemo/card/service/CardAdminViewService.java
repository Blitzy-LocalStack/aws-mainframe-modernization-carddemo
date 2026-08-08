package com.carddemo.card.service;

import com.carddemo.card.domain.Card;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.mapper.CardMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
 */
@Service
public class CardAdminViewService {

    /**
     * The response member carrying the unmasked primary account number.
     *
     * <p>Assumptions: the name matches the {@code cardNumber} property of the {@code AdminCardDetail}
     * schema in {@code openapi/card-api.yaml}. It is a constant rather than a literal at the point of
     * use so that the contract name and the emitted name have one source.</p>
     */
    public static final String CARD_NUMBER_MEMBER = "cardNumber";

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
     * <p>Assumptions: the response is assembled as an insertion-ordered map rather than as a record.
     * The contract composes {@code AdminCardDetail} as the shared core plus one member, and a record
     * would have to restate all six core members in a second type -- two shapes that must agree, which
     * is how they come to disagree. Building from the core shape means a member added to
     * {@link CardDetail} appears here without an edit, and the insertion order keeps the rendered
     * member order equal to the core shape's own.</p>
     *
     * @param selector the opaque selector this service minted for the card; must not be {@code null}
     * @return the card's core state plus its full sixteen-digit account number, never {@code null}
     * @throws com.carddemo.common.error.ClientInputException if the selector is not one this service
     *     issued or can no longer be opened
     * @throws org.springframework.web.server.ResponseStatusException with a not-found status if the
     *     selector opened cleanly but names no stored row
     * @throws NullPointerException if {@code selector} is {@code null}
     */
    @Transactional(readOnly = true)
    public Map<String, Object> viewForAdministrator(String selector) {
        Objects.requireNonNull(selector, "selector must not be null");

        Card card = this.cardView.loadBySelector(selector);
        CardDetail core = this.mapper.toDetail(card);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("key", core.key());
        response.put("displayCardNumber", core.displayCardNumber());
        response.put("accountId", core.accountId());
        response.put("embossedName", core.embossedName());
        response.put("expirationDate", core.expirationDate());
        response.put("activeStatus", core.activeStatus());
        response.put("version", core.version());

        // WHY : Assumptions: the disclosure is the LAST member added, so the rendered order is the core
        //       shape's order followed by the one administrative addition. That ordering is what makes
        //       the two responses diffable against each other in a review or a capture: the shared
        //       prefix is byte-identical and the difference is a single trailing member, rather than one
        //       member interleaved somewhere a reader has to search for.
        response.put(CARD_NUMBER_MEMBER, this.mapper.discloseCardNumberToAdministrator(card));

        return response;
    }
}
