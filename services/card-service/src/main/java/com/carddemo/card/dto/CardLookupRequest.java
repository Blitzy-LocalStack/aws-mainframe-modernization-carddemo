package com.carddemo.card.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Carries the one primary account number a lookup resolves to an opaque selector.
 *
 * <h2>What this record is</h2>
 *
 * <p>This is the request body of the lookup operation the published contract at
 * {@code services/card-service/src/main/resources/openapi/card-api.yaml} declares. It exists so a caller
 * that holds a card number -- an operator reading one off a physical card, say -- can reach that card's
 * detail view, which every other route addresses by the opaque selector instead.
 *
 * <h2>Why a number is accepted in a body and never in a path</h2>
 *
 * <p>Assumptions: this record is the ONE place in the whole contract that accepts a card number as an
 * input, and it is a body rather than a request target on purpose. That asymmetry is the disclosure
 * boundary of this context: a load balancer's access log retains the full target of every request and a
 * browser retains its history, while neither retains a request body. The reference never faced the choice
 * because the number it navigated with never left the region -- {@code app/cbl/COCRDLIC.cbl} holds each
 * rendered row's identity in its own working storage and moves it into the communication area on
 * selection, at lines 532 to 534 and 560 to 562 -- so a surrogate in the path is the arrangement that
 * reproduces the reference's exposure rather than widening it.
 *
 * <p>Alternatives Considered: serving this as a query parameter on the list operation, which would make
 * it a GET and so cacheable and bookmarkable. Rejected for precisely those properties: a query string is
 * part of the target and is retained everywhere the path is.
 *
 * @param cardNumber the primary account number to resolve, exactly sixteen digit characters; a masked
 *     rendering taken from a response is refused, because the masked form identifies no row
 */
public record CardLookupRequest(
        @NotBlank
        @Size(min = CARD_NUMBER_WIDTH, max = CARD_NUMBER_WIDTH)
        @Pattern(regexp = CARD_NUMBER_PATTERN) String cardNumber) {

    /**
     * The declared width of a card number.
     *
     * <p>Assumptions: sixteen is the reference width, carried across from
     * {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy} line 5, and it is the length the
     * contract declares for this property both as a minimum and as a maximum.
     */
    private static final int CARD_NUMBER_WIDTH = 16;

    /**
     * The pattern a card number must match.
     *
     * <p>Assumptions: digits only, and exactly the declared width, which is the constraint the reference
     * states in the message it raises for a bad value -- {@code 'Card number if supplied must be a 16
     * digit number'} at {@code app/cbl/COCRDUPC.cbl} lines 193 to 194. Anchoring both ends is what makes
     * a masked rendering fail here rather than being read as a partial number.
     */
    private static final String CARD_NUMBER_PATTERN = "^[0-9]{16}$";

    /**
     * Renders the shape of the submitted value and never the value itself.
     *
     * <p>Assumptions: a full card number is the most sensitive value this context handles, and a record's
     * generated rendering would print it from any statement that interpolated the record -- including a
     * framework log line reporting a failed bind. Overriding makes the withholding a property of the type
     * rather than of every place the type is mentioned.
     *
     * <p>Alternatives Considered: rendering the last four digits, as the masked response projection does.
     * Rejected because a masked response is a value this contract has already decided to disclose to a
     * caller that is entitled to it, whereas a log line has no such entitlement and is retained far
     * longer. The presence flag is enough to tell a submitted body from an absent one, which is the only
     * thing a diagnostic needs from this record.
     *
     * @return a shape-only rendering, reporting whether a number was supplied and never any part of it
     */
    @Override
    public String toString() {
        return "CardLookupRequest[cardNumberSupplied="
                + (cardNumber != null && !cardNumber.isBlank()) + "]";
    }
}
