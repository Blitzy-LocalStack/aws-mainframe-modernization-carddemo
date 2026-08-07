package com.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the rendering of a submission carries no value the migration's logging contract
 * protects, whichever of the two key alternatives the submission supplied.
 *
 * <p>Refactoring Rationale: this class previously asserted that EVERY component of the record was
 * rendered, and named the account identifier, the description, the amount and the four merchant fields
 * among them. It was pinning a disclosure rather than a contract. The sensitive-data logging contract
 * in {@code docs/architecture/observability.md} names account and customer identifiers in a clause of
 * their own and covers persistence-bound values as a class, so seven of the fourteen components were
 * protected the whole time; the assertions on those seven are inverted here. The card-number masking
 * the class was originally written for was correct and is unchanged.</p>
 *
 * <p>Assumptions: the sixteen-digit value is assembled from a repeated digit rather than written as a
 * literal, so that no test fixture in this repository is a card-number-shaped constant a scanner has
 * to triage.</p>
 */
class TransactionAddRequestTest {

    /**
     * A card-number-width value assembled rather than written, ending in four distinguishable digits.
     */
    private static final String CARD_NUMBER = "1".repeat(12) + "2345";

    /**
     * The account identifier the fixture submits, which no rendering may carry.
     *
     * <p>Assumptions: it is named as a constant rather than repeated as a literal so that the absence
     * assertions and the fixture cannot drift apart -- an assertion against a value the fixture no
     * longer submits would pass while proving nothing.</p>
     */
    private static final String ACCOUNT_ID = "00000000011";

    /**
     * Confirms the rendering masks the card number and withholds the account identifier entirely.
     *
     * <p>Refactoring Rationale: both halves are asserted in one case because they are one decision, as
     * they were before -- but the decision is the opposite one. What is asserted is no longer an
     * asymmetry between a protected value and a system key; it is that the one identifier a rendering
     * may name is named only through the shared mask, and the other is not named at all. Masking is
     * available for the card number because a mask is what
     * {@code com.carddemo.common.security.CardNumberMasker} exists to produce, and it is not available
     * for the account identifier because nothing owns a masking rule for that value in this context.</p>
     */
    @Test
    @DisplayName("the rendering masks the card number and withholds the account identifier")
    void renderingMasksTheCardNumberAndWithholdsTheAccountIdentifier() {
        String rendered = request(CARD_NUMBER).toString();

        assertThat(rendered)
                .doesNotContain(CARD_NUMBER)
                .contains("maskedCardNumber=" + "*".repeat(12) + "2345")
                .doesNotContain(ACCOUNT_ID)
                .doesNotContain("accountId");
    }

    /**
     * Confirms a submission that supplied no card number renders without a failure.
     *
     * <p>Assumptions: an absent card number is valid input rather than an edge case -- the reference
     * fills whichever key alternative was omitted from the cross-reference -- so the rendering has to
     * survive it. A rendering that raised here would replace a validation diagnostic with an
     * unrelated failure at exactly the moment the diagnostic was wanted.</p>
     *
     * <p>Assumptions: this is also the case in which a rendering has the least left to say, because the
     * one masked component is absent too, so the account identifier's absence is asserted again here
     * rather than only above. An override tempted to fall back to the account identifier when no card
     * number was supplied would satisfy every other case in this class.</p>
     */
    @Test
    @DisplayName("an account-only submission renders without raising and still withholds the account")
    void accountOnlySubmissionRenders() {
        String rendered = request(null).toString();

        assertThat(rendered)
                .contains("maskedCardNumber=null")
                .doesNotContain(ACCOUNT_ID)
                .doesNotContain("accountId");
    }

    /**
     * Confirms the seven surviving components are rendered and the seven protected ones are not.
     *
     * <p>Assumptions: the two halves belong in one case because the risk runs both ways. A component
     * dropped by oversight would silently reduce what a diagnostic says without failing anything, and a
     * protected component added back would silently disclose; asserting only one half would leave the
     * other regression undetectable. Each protected value is asserted absent by its value AND by its
     * member name, because a rendering emitting {@code amount=null} would pass a value-only assertion
     * while announcing that the component is rendered.</p>
     */
    @Test
    @DisplayName("the surviving components are rendered and the protected ones are not")
    void theSurvivingComponentsAreRenderedAndTheProtectedOnesAreNot() {
        String rendered = request(CARD_NUMBER).toString();

        assertThat(rendered)
                .startsWith("TransactionAddRequest[")
                .endsWith("]")
                .contains("typeCode=01")
                .contains("categoryCode=0001")
                .contains("source=POS")
                .contains("originDate=2026-01-15")
                .contains("processDate=2026-01-16")
                .contains("confirmation=Y");

        assertThat(rendered)
                .doesNotContain("GROCERY PURCHASE")
                .doesNotContain("description")
                .doesNotContain("125.50")
                .doesNotContain("amount")
                .doesNotContain("000000000")
                .doesNotContain("CORNER STORE")
                .doesNotContain("SEATTLE")
                .doesNotContain("98101")
                .doesNotContain("merchant");
    }

    /**
     * Builds a submission whose only variable is the card number.
     *
     * @param cardNumber the card number to place on the request, or {@code null} for an account-only
     *     submission
     * @return a populated request, valid in shape, carrying {@code cardNumber}
     */
    private static TransactionAddRequest request(String cardNumber) {
        return new TransactionAddRequest(ACCOUNT_ID, "01", "0001", "POS", "GROCERY PURCHASE",
                Money.of("125.50"), "000000000", "CORNER STORE", "SEATTLE", "98101", cardNumber,
                "2026-01-15", "2026-01-16", "Y");
    }
}
