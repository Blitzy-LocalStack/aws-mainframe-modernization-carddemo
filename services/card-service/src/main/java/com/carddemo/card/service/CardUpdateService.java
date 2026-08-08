package com.carddemo.card.service;

import com.carddemo.card.domain.Card;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.dto.CardUpdateRequest;
import com.carddemo.card.mapper.CardMapper;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.common.error.RecordConflictException;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies an edit to one card's three editable attributes.
 *
 * <h2>What this service is</h2>
 *
 * <p>This is the migrated successor of the reference card update program {@code app/cbl/COCRDUPC.cbl}. It
 * carries the three attributes that program lets a user change -- the embossed name, the active status and
 * the expiration month and year -- and nothing else: the card number is the key, and the account it belongs
 * to is not editable from a card screen.
 *
 * <h2>How the reference's own concurrency check becomes the target's</h2>
 *
 * <p>Assumptions: the reference already performs an optimistic check across its pseudo-conversational gap
 * and this service performs the same check natively. The reference reads the row for update and compares
 * the stored values against the snapshot it showed the user before rewriting, because a CICS read-for-update
 * lock is not held across a user's think time; it commits with {@code EXEC CICS SYNCPOINT} at
 * {@code app/cbl/COCRDUPC.cbl} line 470. The target expresses that as a version the client echoes back,
 * so the comparison the reference codes by hand is the one the persistence provider makes.
 *
 * <p>Assumptions: the submitted version is checked here as well as by the provider, and the duplication is
 * deliberate rather than redundant. Checking first turns a stale submission into a refusal that names the
 * current version, before any column is touched; leaving it to the provider alone would report the same
 * conflict only after the row had been loaded and mutated, and a caller in an in-process test would see a
 * failure from a layer it was not exercising. The provider's own check remains the backstop for the
 * narrower race between this read and this write.
 *
 * <p>Trade-offs: a conflict is raised rather than the edit being merged. Merging would need a rule for
 * which side wins per attribute, and the reference has no such rule -- it refuses and redisplays, which is
 * exactly what a refusal carrying the current state lets a client do.
 */
@Service
public class CardUpdateService {

    /** Records which edit ran, never the values it carried. */
    private static final Logger LOG = LoggerFactory.getLogger(CardUpdateService.class);

    /** The store this service reads and writes. */
    private final CardRepository cards;

    /** Opens selectors, applies the submitted attributes and projects the result. */
    private final CardMapper mapper;

    /**
     * Binds the store and the mapper.
     *
     * @param cards the store this service reads and writes; must not be {@code null}
     * @param mapper the selector, update and projection mapper; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public CardUpdateService(CardRepository cards, CardMapper mapper) {
        this.cards = Objects.requireNonNull(cards, "cards");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Applies the submitted attributes to the card the selector names.
     *
     * <p>Assumptions: the loaded row is mutated rather than replaced, so the provider writes the columns
     * that changed and the row's key, account and encrypted verification value are untouched by an edit
     * that mentions none of them.
     *
     * <p>Assumptions: the response is projected from the SAVED row rather than from the request, so the
     * version it carries is the one a subsequent edit must echo and the expiration date it carries is the
     * whole stored value rather than the two parts that were submitted. A caller can therefore edit twice
     * in succession using only what this returns.
     *
     * @param cardKey the sealed selector exactly as the client echoed it back
     * @param request the validated attributes to apply; must not be {@code null}
     * @return the saved card's masked detail, carrying the new version; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws NoSuchElementException if no card answers the selector
     * @throws RecordConflictException if the submitted version is not the row's current version, which the
     *     shared advice renders as the reference's record-changed refusal
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public CardDetail update(String cardKey, CardUpdateRequest request) {

        Objects.requireNonNull(request, "request");

        String cardNumber = this.mapper.openCardSelector(cardKey);
        Card stored = this.cards.findById(cardNumber)
                .orElseThrow(() -> new NoSuchElementException(
                        CardListService.MESSAGE_CARD_NOT_FOUND));

        requireCurrentVersion(stored, request.version());

        Card saved = this.cards.saveAndFlush(this.mapper.applyUpdate(request, stored));

        LOG.info("event=card.updated version={}", saved.getVersion());

        return this.mapper.toDetail(saved);
    }

    /**
     * Refuses an edit whose submitted version is not the row's current one.
     *
     * <p>Assumptions: the refusal reports the row's CURRENT version so a client can re-read, re-render and
     * resubmit without a second round trip to discover what it should have sent. That is the target's form
     * of the reference redisplaying the row it refused to overwrite.
     *
     * @param stored the row as it currently stands
     * @param submitted the version the caller echoed back, which may be {@code null}
     * @throws RecordConflictException if the submitted version is absent or does not match the row's
     */
    private static void requireCurrentVersion(Card stored, Integer submitted) {

        if (submitted == null || submitted != stored.getVersion()) {
            LOG.info("event=card.update.refused reason=stale-version current={}", stored.getVersion());
            throw new RecordConflictException(RecordConflictException.Kind.STALE_VERSION,
                    (long) stored.getVersion());
        }
    }
}
