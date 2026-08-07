package com.carddemo.reporting.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * Pins what {@link StatementRequest#toString()} may carry into a log line.
 *
 * <p>Refactoring Rationale: this rendering masked the card number and rendered the account identifier in
 * full, arguing explicitly against masking it because "that identifier is a system key rather than
 * protected data" and because withholding it would "make a log unusable while withholding nothing the
 * path had not already carried". The sensitive-data logging contract in
 * {@code docs/architecture/observability.md} names account and customer identifiers in a clause of their
 * own, so the value was protected however it is used; and the appeal to what the request path already
 * carried compares two different surfaces -- a path is seen by the one authenticated caller making the
 * request and is not retained, whereas a log line is retained, aggregated and readable by every holder
 * of log access. This class is what stops that argument returning.</p>
 *
 * <p>Assumptions: the assertions name the values that must be ABSENT rather than checking the shape of
 * what is present, because a rendering can only regress by GAINING a component and an assertion on
 * presence cannot detect a gain. One positive assertion accompanies them so that a rendering reduced to
 * the empty string could not pass by carrying nothing at all.</p>
 *
 * <p>Assumptions: the two components are alternative selectors, so exactly one is supplied on a real
 * request. Both single-selector shapes are therefore exercised as separate cases rather than one
 * both-populated fixture, because the account-selected shape is the one where the rendering has nothing
 * left to say and is exactly where an override would be tempted to fall back to the identifier.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own where they have any.</p>
 */
class StatementRequestRenderingTest {

    /**
     * A card-number-width value assembled rather than written, ending in four distinguishable digits.
     *
     * <p>Assumptions: it is assembled from a repeated digit so that no test fixture in this repository is
     * a card-number-shaped constant a scanner has to triage.</p>
     */
    private static final String CARD_NUMBER = "1".repeat(12) + "2345";

    /**
     * A distinctive eleven-digit account identifier, at the width {@code ACCT-ID PIC 9(11)} declares.
     *
     * <p>Alternatives Considered: a zero-padded identifier such as the seed rows carry. Rejected because
     * a run of zeros collides with the masked card number's own asterisk-and-digit form under a careless
     * assertion, and because a distinctive value cannot occur inside any other token the rendering emits
     * -- which is what makes the absence assertion mean what it says.</p>
     */
    private static final String ACCOUNT_ID = "21820493291";

    /**
     * Confirms a card-selected request renders the mask and no account identifier.
     */
    @Test
    void aCardSelectedRequestRendersTheMaskAndNoAccountIdentifier() {
        String rendered = new StatementRequest(CARD_NUMBER, null).toString();

        assertThat(rendered).startsWith("StatementRequest[");
        assertThat(rendered).contains("*".repeat(12) + "2345");
        assertThat(rendered).doesNotContain(CARD_NUMBER);
        assertThat(rendered).doesNotContain("accountId");
    }

    /**
     * Confirms an account-selected request renders neither the identifier nor its member name.
     *
     * <p>Assumptions: the member name is asserted absent alongside the value, because a rendering
     * emitting {@code accountId=null} would pass a value-only assertion while still announcing that the
     * component is rendered, and the next account-selected request would disclose.</p>
     */
    @Test
    void anAccountSelectedRequestRendersNeitherTheIdentifierNorItsName() {
        String rendered = new StatementRequest(null, ACCOUNT_ID).toString();

        assertThat(rendered).doesNotContain(ACCOUNT_ID);
        assertThat(rendered).doesNotContain("accountId");
    }

    /**
     * Confirms a request carrying both selectors still renders neither identifier in usable form.
     *
     * <p>Assumptions: a real request supplies one selector, so this shape is not a valid submission and is
     * exercised anyway. A rendering is reached on the refusal path as readily as on the accepted one, and
     * an invalid request is precisely the one something gets written about.</p>
     */
    @Test
    void aRequestCarryingBothSelectorsStillRendersNeitherInUsableForm() {
        String rendered = new StatementRequest(CARD_NUMBER, ACCOUNT_ID).toString();

        assertThat(rendered).doesNotContain(CARD_NUMBER);
        assertThat(rendered).doesNotContain(ACCOUNT_ID);
        assertThat(rendered).doesNotContain("accountId");
    }

    /**
     * Confirms a request carrying neither selector renders without raising.
     *
     * <p>Assumptions: an empty request is refused rather than served, and the refusal is reported by a
     * message that may render the request. A rendering that raised on the absent case would replace the
     * refusal diagnostic with an unrelated failure at exactly the moment the diagnostic was wanted.</p>
     */
    @Test
    void anEmptyRequestRendersWithoutRaising() {
        StatementRequest empty = new StatementRequest(null, null);

        assertThatCode(empty::toString).doesNotThrowAnyException();
        assertThat(empty.toString()).doesNotContain("accountId");
    }
}
