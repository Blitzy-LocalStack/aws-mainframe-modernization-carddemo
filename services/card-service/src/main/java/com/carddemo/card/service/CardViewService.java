package com.carddemo.card.service;

import com.carddemo.card.domain.Card;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.mapper.CardMapper;
import com.carddemo.card.repository.CardRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Reads one card, addressed either by its opaque selector or by the number a user typed.
 *
 * <p>This is the migrated form of {@code app/cbl/COCRDSLC.cbl}, the card-detail transaction CCDL
 * defined at {@code app/csd/CARDDEMO.CSD:347-348}. Its file read becomes a keyed repository read under
 * transformation rule T5.</p>
 *
 * <h2>Why there are two ways in rather than one</h2>
 *
 * <p>Assumptions: the two entry points differ in what the caller holds, not in what they return. A
 * caller that has listed cards holds a selector and reaches {@link #viewBySelector(String)}; a caller
 * that has a number a user typed reaches {@link #viewByCardNumber(String)}. Both answer the same
 * shape, including the selector, so a caller that looked a card up can then update it without looking
 * it up again.</p>
 *
 * <p>Assumptions: the number-addressed entry point exists because a primary account number may not
 * appear in a request line. The load balancer writes the request target into a durable access-log
 * object before any application code runs, so a number in a path segment or a query string is
 * persisted verbatim and no downstream masking can redact a log already written. The published
 * contract therefore carries the number in a request BODY, which no access log retains, and this
 * method is what serves it.</p>
 *
 * <h2>Disclosure</h2>
 *
 * <p>Assumptions: neither method here discloses a full account number and neither discloses a card
 * verification value. The masked rendering is produced by the mapper, and the unmasked rendering has
 * exactly one producer -- {@link CardMapper#discloseCardNumberToAdministrator(Card)} -- which this
 * class never calls. That is why the administrative read is a separate service rather than a flag on
 * this one: a boolean parameter would put the disclosure decision on every call site instead of in the
 * type system.</p>
 */
@Service
public class CardViewService {

    /**
     * The reader this service resolves a card through.
     */
    private final CardRepository cards;

    /**
     * The anti-corruption layer that masks the account number and mints the selector.
     */
    private final CardMapper mapper;

    /**
     * Builds the service from its two collaborators.
     *
     * @param cards the card reader; must not be {@code null}
     * @param mapper the mapper that renders a stored row as a detail response; must not be
     *     {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public CardViewService(CardRepository cards, CardMapper mapper) {
        this.cards = Objects.requireNonNull(cards, "cards must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * Reads the card the supplied selector stands for.
     *
     * @param selector the opaque selector this service minted for the card, exactly as a list row or a
     *     previous detail response carried it; must not be {@code null}
     * @return the card with its account number masked to the last four digits, never {@code null}
     * @throws com.carddemo.common.error.ClientInputException if the selector is not one this service
     *     issued or can no longer be opened, which the contract reports as 400 rather than 404 because
     *     the request named nothing this service can resolve
     * @throws ResponseStatusException with {@link HttpStatus#NOT_FOUND} if the selector opened cleanly
     *     but names no stored row
     * @throws NullPointerException if {@code selector} is {@code null}
     */
    @Transactional(readOnly = true)
    public CardDetail viewBySelector(String selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        return this.mapper.toDetail(requireCard(this.mapper.openCardSelector(selector)));
    }

    /**
     * Reads the card the supplied primary account number names.
     *
     * @param cardNumber the sixteen-digit primary account number, taken from the request body; must
     *     not be {@code null}
     * @return the card with its account number masked, and carrying the selector every other
     *     single-card operation addresses it by, never {@code null}
     * @throws ResponseStatusException with {@link HttpStatus#NOT_FOUND} if no card holds that number
     * @throws NullPointerException if {@code cardNumber} is {@code null}
     */
    @Transactional(readOnly = true)
    public CardDetail viewByCardNumber(String cardNumber) {
        Objects.requireNonNull(cardNumber, "cardNumber must not be null");
        return this.mapper.toDetail(requireCard(cardNumber));
    }

    /**
     * Reads the card a selector stands for and returns the stored row itself.
     *
     * <p>Refactoring Rationale: this is package-private and returns the ENTITY rather than a response
     * shape, so that the administrative read and the update path can reuse the selector resolution
     * without going through the masking mapper first and then discarding the result. Exposing it
     * publicly would let a caller outside this package obtain an unmasked row, which is the one thing
     * the mapper boundary exists to prevent.</p>
     *
     * @param selector the opaque selector; must not be {@code null}
     * @return the stored row, never {@code null}
     * @throws ResponseStatusException with {@link HttpStatus#NOT_FOUND} if the selector names no row
     */
    Card loadBySelector(String selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        return requireCard(this.mapper.openCardSelector(selector));
    }

    /**
     * Reads a card by its key, refusing absence.
     *
     * <p>Assumptions: absence is a 404 and not an empty success. The reference reports the same
     * condition as a screen message rather than as a record, and a caller addressing a specific card
     * by key has asked for a row that either exists or does not -- unlike a list, where matching
     * nothing is a legitimate result.</p>
     *
     * @param cardNumber the sixteen-character key
     * @return the stored row, never {@code null}
     * @throws ResponseStatusException with {@link HttpStatus#NOT_FOUND} when no row holds that key
     */
    private Card requireCard(String cardNumber) {
        return this.cards.findById(cardNumber)
                // WHY : Assumptions: the diagnostic names NO card number, and that is the point rather
                //       than terseness. A not-found reply is rendered by the shared error advice into a
                //       response body and a log line, so quoting the number here would put a full
                //       primary account number into both -- reintroducing, through an error path, the
                //       disclosure that keeping the number out of the request target exists to prevent.
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "the card requested does not exist"));
    }
}
