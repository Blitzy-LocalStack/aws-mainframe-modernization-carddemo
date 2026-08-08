package com.carddemo.card.dto;

/**
 * Carries one card's detail together with its full, unmasked primary account number.
 *
 * <h2>What this record is</h2>
 *
 * <p>This is the response body of the administrative read the published contract at
 * {@code services/card-service/src/main/resources/openapi/card-api.yaml} declares, and it is the only
 * shape in this context that carries a card number in full. The contract composes it as the shared detail
 * core plus one additional member, which is why this record repeats the core's components rather than
 * wrapping {@link CardDetail}: the wire shape is one flat object, and a nested member would publish a
 * different document from the one the contract declares.
 *
 * <h2>Why the unmasked number lives on its own shape</h2>
 *
 * <p>Assumptions: separating this from {@link CardDetail} is what makes the disclosure decision structural
 * rather than conditional. The two shapes differ by exactly one member, so a handler cannot accidentally
 * return the unmasked number from the ordinary read -- it would have to return a different type -- and the
 * route that does return it sits behind the administrative authority in
 * {@code com.carddemo.card.config.SecurityConfig}. The alternative, one shape whose number member is
 * populated only for an administrator, would put the decision inside a mapper where a later edit could
 * quietly widen it.
 *
 * <p>Refactoring Rationale: the reference draws the same line, though it draws it with a screen attribute
 * rather than a type. The list and detail maps render the number as ordinary output while the update map
 * protects it, and the number is never something a terminal user types into a navigation field. A separate
 * response type is the target's way of saying the same thing in a medium that has no field attributes.
 *
 * @param key the opaque selector this card answers on, as every other route addresses it
 * @param cardNumber the full primary account number, exactly sixteen digit characters; returned by the
 *     administrative read alone, and never rendered by {@link #toString()}
 * @param accountId the identifier of the account this card belongs to
 * @param embossedName the name embossed on the card
 * @param expirationDate the stored expiration date, as the whole ten-character value
 * @param activeStatus the one-character active-status code
 * @param version the row's optimistic-lock version, which an update must echo back
 */
public record AdminCardDetail(String key, String cardNumber, String accountId, String embossedName,
        String expirationDate, String activeStatus, Integer version) {

    /**
     * Renders the card's identity and status while withholding the unmasked number.
     *
     * <p>Assumptions: the whole reason this shape exists is that it carries a value the ordinary shape does
     * not, so it is exactly the shape whose rendering must not leak. A record's generated rendering would
     * print the number in full from any statement that interpolated it, which would undo the disclosure
     * boundary the type was created to draw.
     *
     * <p>Alternatives Considered: rendering the last four digits, matching the masked projection. Rejected
     * because it would put a fragment of the number into logs that the ordinary read's own response body
     * already carries for entitled callers -- so the fragment adds nothing an operator cannot get
     * legitimately, while making every log line holding this type a partial disclosure.
     *
     * @return the selector, account, status and version, with a constant placeholder for the number
     */
    @Override
    public String toString() {
        return "AdminCardDetail[key=" + key + ", accountId=" + accountId
                + ", activeStatus=" + activeStatus + ", version=" + version
                + ", cardNumber=<withheld>]";
    }
}
