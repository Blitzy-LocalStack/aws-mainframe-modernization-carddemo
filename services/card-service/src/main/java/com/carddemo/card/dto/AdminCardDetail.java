package com.carddemo.card.dto;

import com.carddemo.common.security.MaskedCardNumber;
import java.util.Objects;

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
 * <h2>Why the masked rendering is carried BESIDE the full number</h2>
 *
 * <p>Refactoring Rationale: this record omitted {@code displayCardNumber} and a controller test asserted
 * that it was absent, while the published contract required it and the browser client's type declared it.
 * The composition in {@code card-api.yaml} is {@code CardDetailCore} plus one member with
 * {@code unevaluatedProperties: false}, so the resolved administrative schema requires EIGHT members; this
 * record declared seven, which means every successful administrative body violated the document it is
 * published under. The browser type says the same thing a second way -- {@code AdminCardDetail extends
 * CardDetail extends CardSummary} -- so the masked member was required by the contract and by the client,
 * and absent only from the server and from one assertion.</p>
 *
 * <p>Alternatives Considered: narrowing the shared base instead, so that the core carried no card-number
 * member at all and each variant added its own. Rejected on two grounds. It would REMOVE a member from a
 * published response, which is a breaking change for any consumer already reading it, in order to make the
 * document agree with the implementation rather than the other way round -- and the composition's own
 * {@code unevaluatedProperties: false} beside a single added member is a deliberate statement that this
 * shape is the core PLUS one thing. And it would cost the property that makes one client renderer serve
 * both shapes: every other card screen renders the masked form in the same field, so a shape lacking it
 * forces a branch in the consumer for the one screen that has MORE information rather than less.</p>
 *
 * <p>Assumptions: carrying both discloses nothing the full number does not already disclose, because the
 * masked form is derived from it. The disclosure boundary is unchanged and is still structural: this type
 * exists so that the ordinary read cannot return a number without returning a different type.</p>
 *
 * @param key the opaque selector this card answers on, as every other route addresses it
 * @param displayCardNumber the same card rendered for a person, twelve mask characters followed by the last
 *     four digits, exactly as {@link CardDetail} carries it; required by the resolved administrative schema
 *     and by the browser type, and safe to render in a diagnostic because it is already masked
 * @param accountId the identifier of the account this card belongs to
 * @param embossedName the name embossed on the card
 * @param expirationDate the stored expiration date, as the whole ten-character value
 * @param activeStatus the one-character active-status code
 * @param version the row's optimistic-lock version, which an update must echo back
 * @param cardNumber the full primary account number, exactly sixteen digit characters; returned by the
 *     administrative read alone, and never rendered by {@link #toString()}
 */
public record AdminCardDetail(String key, String displayCardNumber, String accountId,
        String embossedName, String expirationDate, String activeStatus, Integer version,
        String cardNumber) {

    /**
     * Confirms the two card-number members carry the two different forms they are declared to carry.
     *
     * <p>Purpose. This shape is the only one in the context holding both forms, so it is the only one where
     * they can be passed in the wrong order or the same value passed twice. Either mistake compiles -- both
     * components are strings -- and the second would publish an unmasked number in the member every other
     * card screen renders, which is precisely the disclosure the type split exists to prevent.</p>
     *
     * <p>Assumptions: the masked member is checked by the shared rule
     * {@link MaskedCardNumber#require(String, String)} rather than by a local expression, so this shape and
     * {@link CardDetail} admit exactly the same renderings; the full member is checked against its own
     * declared width and character class, which the published schema states as sixteen digits.</p>
     *
     * <p>Assumptions: neither refusal quotes the value it rejected. The value most likely to arrive in the
     * wrong member is an unmasked number, so echoing it would put the number this guard exists to place
     * correctly into an exception message and from there into a log.</p>
     *
     * @throws NullPointerException if {@code key}, {@code displayCardNumber} or {@code cardNumber} is
     *     {@code null}
     * @throws IllegalArgumentException if {@code displayCardNumber} is not a full-width masked rendering,
     *     or if {@code cardNumber} is not exactly sixteen digit characters
     */
    public AdminCardDetail {
        Objects.requireNonNull(key, "key is required");
        MaskedCardNumber.require("displayCardNumber", displayCardNumber);
        Objects.requireNonNull(cardNumber, "cardNumber is required");
        if (!DISCLOSED_NUMBER.matcher(cardNumber).matches()) {
            throw new IllegalArgumentException(
                    "cardNumber must be exactly " + MaskedCardNumber.MASKED_LENGTH + " digit"
                            + " characters, so a masked rendering cannot be published as the disclosed"
                            + " number");
        }
    }

    /**
     * The shape the disclosed number must have, composed from the width the masked rule already publishes.
     *
     * <p>Assumptions: the width is READ from {@link MaskedCardNumber#MASKED_LENGTH} rather than written as
     * sixteen, because the two members are two renderings of one value and a contract that let their widths
     * diverge would be describing two different values.</p>
     */
    private static final java.util.regex.Pattern DISCLOSED_NUMBER =
            java.util.regex.Pattern.compile("^[0-9]{" + MaskedCardNumber.MASKED_LENGTH + "}$");

    /**
     * Renders the card's identity and status while withholding the unmasked number.
     *
     * <p>Assumptions: the whole reason this shape exists is that it carries a value the ordinary shape does
     * not, so it is exactly the shape whose rendering must not leak. A record's generated rendering would
     * print the number in full from any statement that interpolated it, which would undo the disclosure
     * boundary the type was created to draw.
     *
     * <p>Assumptions: the MASKED member is rendered while the disclosed one is not, and the two decisions do
     * not contradict each other. The observability rule sanctions exactly one abbreviation of a card number,
     * the shared masker's own output, and this component arrives already carrying it -- the compact
     * constructor admits no other form -- so printing it performs no abbreviation here and is what lets a
     * line say WHICH card it describes. Producing that same rendering FROM the disclosed member would be a
     * second masking rule outside the mapper that owns masking in this context, which is why the disclosed
     * member is replaced by a placeholder rather than reduced.
     *
     * <p>Refactoring Rationale: the ACCOUNT identifier was rendered IN FULL here while the card number was
     * withheld, which read as a considered position and was not one.
     * {@code docs/architecture/observability.md} L1093 to L1112 names the account identifier among the
     * values a diagnostic rendering must omit, so the shape that exists precisely to concentrate
     * disclosure was the one shape in this package disclosing an identifier completely -- and it did so
     * while its two siblings each abbreviated the same value a third and a fourth way. All three now
     * withhold it, and this file's rendering is the one that changed most.</p>
     *
     * <p>Refactoring Rationale: the placeholder is the literal the other three card shapes declare rather
     * than the {@code <withheld>} marker this method used. That marker belongs to the message-wire shapes
     * of another bounded context, and {@link CardDetail} records the choice not to bring a second
     * vocabulary into this package; leaving it here made that record's claim false while nothing could
     * report it.</p>
     *
     * @return the selector, the ALREADY-MASKED rendering, the status and the version, with one placeholder
     *     standing in for the account identifier and another for the disclosed number
     */
    @Override
    public String toString() {
        return "AdminCardDetail[key=" + key
                + ", displayCardNumber=" + displayCardNumber
                + ", accountId=" + REDACTED_PERSONAL
                + ", activeStatus=" + activeStatus + ", version=" + version
                + ", cardNumber=" + REDACTED_PERSONAL + ']';
    }

    /**
     * The stand-in printed in place of the two withheld values.
     *
     * <p>Assumptions: one literal covers both the account identifier and the card number, because a reader
     * of a log line needs to know that a value was withheld and not which class of value it was -- the
     * component name beside the placeholder already says that. It is the literal {@link CardDetail},
     * {@link CardSummary} and {@code CardUpdateRequest} declare, so all four card shapes print one
     * vocabulary.</p>
     */
    private static final String REDACTED_PERSONAL = "REDACTED";
}
