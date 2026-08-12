package com.carddemo.card.service;

import com.carddemo.card.dto.CardDetail;
import com.carddemo.common.error.RecordConflictException;
import java.util.Objects;

/**
 * The stale-revision refusal this context raises, carrying the card as it now stands.
 *
 * <p>Purpose: this narrows the shared contention type by adding the one thing the shared type cannot
 * hold -- a rendered representation of the contended row. The published contract's conflict body
 * declares that representation as a required part of the changed-row condition, so the refusal has to
 * carry it from the point the conflict is detected to the point the body is composed.</p>
 *
 * <p>Refactoring Rationale: the row is captured at DETECTION rather than re-read when the body is
 * composed. Both would answer "the card as it now stands", and capturing is chosen for two independent
 * reasons: the detecting method already holds the freshly read row, so a re-read would be a second query
 * for a value in hand; and a re-read could observe a third caller's write, which would return a version
 * that never contended with anything and would leave the caller resubmitting against a state the refusal
 * never saw.</p>
 *
 * <p>Assumptions: this is a SUBTYPE rather than a new unrelated exception, which is what keeps the
 * shared advice's contention handling in force for every other conflict of every other context. Spring
 * selects the most specific handler for a thrown type, so the controller-local handler for this type
 * composes the extended body while the shared handler continues to answer the base type unchanged --
 * including the three conditions of this same contract that carry no refreshed card.</p>
 *
 * <p>Trade-offs: the kind is fixed to the stale-revision condition by this type's own constructor rather
 * than being accepted as an argument. The other three kinds report no refreshed card, so a type whose
 * whole purpose is to carry one has no use for them, and fixing the kind means a caller cannot raise this
 * type for a condition whose body would then declare a card the contract says is absent.</p>
 */
public class CardRecordConflictException extends RecordConflictException {

    /**
     * Fixes the serialised form of this type across builds.
     *
     * <p>Assumptions: declared because the parent chain is serialisable, not because any instance is ever
     * serialised. Nothing in this system writes an exception to a stream; the constant exists so the
     * value is a stated one rather than one the compiler derives from the member list, which would change
     * whenever a member is added.</p>
     */
    private static final long serialVersionUID = 1L;

    /**
     * The card as it stood when the conflict was detected, never {@code null}.
     */
    private final transient CardDetail card;

    /**
     * Builds the refusal from the row's current version and its rendered form.
     *
     * @param currentVersion the version the row now holds, which a retry must echo; must not be
     *     {@code null}
     * @param card the masked rendering of the row as it now stands; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}, because a refusal of this type
     *     with no card would compose a body the contract declares as carrying one
     */
    // WHY : Assumptions: the version is passed to the parent as well as being present inside the card,
    //       and the duplication is deliberate rather than an oversight. The parent is what the shared
    //       advice reads, and the shared advice is what selects the sentence, the code and the subsystem
    //       for this body; leaving the parent's version unset would make this type render as a conflict
    //       that reports no version at all if it ever reached the shared handler.
    public CardRecordConflictException(Long currentVersion, CardDetail card) {
        super(Kind.STALE_VERSION, Objects.requireNonNull(currentVersion, "currentVersion"));
        this.card = Objects.requireNonNull(card, "card must not be null");
    }

    /**
     * Returns the card as it stood when the conflict was detected.
     *
     * @return the masked rendering carrying the version a retry must echo, never {@code null}
     */
    public CardDetail card() {
        return this.card;
    }
}
