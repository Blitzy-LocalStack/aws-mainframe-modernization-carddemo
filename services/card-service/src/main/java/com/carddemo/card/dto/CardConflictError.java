package com.carddemo.card.dto;

import com.carddemo.common.error.ApiError;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.Objects;

/**
 * The body of the card-service conflict response: the shared error shape plus the refreshed card.
 *
 * <p>Purpose: this is the concrete form of {@code CardConflictError} in
 * {@code src/main/resources/openapi/card-api.yaml}, which declares itself as
 * {@code allOf: [ApiError, {card}]} -- every member of the shared shape, referenced rather than
 * restated, and one further optional member carrying the card as it now stands. It is used by the
 * conflict status alone; the other four failures of this contract return the shared shape
 * unextended.</p>
 *
 * <p>Refactoring Rationale: this type exists because a {@code ResponseEntity<ApiError>} structurally
 * cannot carry the extra member, and the update route was answering with one. Runtime testing found the
 * consequence: a stale submission received a 409 whose only report of the current state was the version
 * number placed in the field entry's message position, where the contract's own example shows a sentence
 * there and the version inside a {@code card} object. A caller following the document therefore found a
 * member that was never sent and a sentence that was a bare number.</p>
 *
 * <p>Assumptions: the shared shape is INCLUDED rather than copied. Its members are reached through the
 * single {@link ApiError} component below, so a member added to the shared shape appears here without
 * this file being edited and cannot be spelled differently here than there. The alternative -- restating
 * the eleven members and adding a twelfth -- is what the contract itself declines to do, and it would
 * give a generated client two classes for one shape.</p>
 *
 * <p>Trade-offs: the inclusion is expressed with {@link JsonUnwrapped}, so the serialised body is FLAT
 * -- the shared members and {@code card} sit side by side at the top level, with {@code card} last,
 * exactly as the contract's example shows. The cost is that this record cannot be round-tripped by a
 * deserialiser through the same annotation, because an unwrapped component gives the parser no property
 * to bind. That cost is nil here: this is a response shape and nothing in this system reads it. Where a
 * consumer does need to parse it -- {@code ui/src/api/types.ts} -- it parses the flat JSON the contract
 * declares and never this Java type.</p>
 *
 * <p>Assumptions: the card is the masked {@link CardDetail} and never {@link AdminCardDetail}, so a
 * failure cannot widen what a response discloses. The update operation the conflict arises on carries
 * {@code x-required-authority: carddemo-user}, and admitting the administrative shape here would let an
 * ordinary caller obtain a full primary account number by provoking a conflict.</p>
 *
 * @param error the shared problem shape, carrying the code, the sentence, the correlation identity, the
 *     masked path and the per-field array; never {@code null}
 * @param card the card as it now stands, carrying the version token a retry must echo; {@code null} for
 *     the conditions in which the write did not proceed and so left no new state to report
 */
// WHY : Assumptions: the null card is SERIALISED rather than omitted. The contract types the member as
//       CardDetail or null and its own example for the two write-not-applied conditions shows
//       "card: null", so a body that dropped the member would not satisfy the document -- and a client
//       distinguishing "no refreshed card" from "an older server that does not send one" needs the
//       difference between a present null and an absent member.
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CardConflictError(@JsonUnwrapped ApiError error, CardDetail card) {

    /**
     * Validates the shared shape is present and retains both components.
     *
     * <p>Assumptions: only the shared shape is required. The card is optional by contract, so a
     * {@code null} there is a legitimate state of this body rather than a partly-built instance.</p>
     *
     * @throws NullPointerException if {@code error} is {@code null}, because a conflict body with no
     *     problem shape would serialise as a lone card and would satisfy no declared response
     */
    public CardConflictError {
        Objects.requireNonNull(error, "error must not be null");
    }
}
