package com.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the generated rendering of a submission cannot carry an unmasked primary account
 * number, whichever of the two key alternatives the submission supplied.
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
     * Confirms the rendering masks the card number and keeps the account identifier readable.
     *
     * <p>Assumptions: both halves are asserted in one test because they are one decision -- the
     * asymmetry between a protected value and a system key is the point, and asserting only the
     * masking would let a later change mask both and still pass.</p>
     */
    @Test
    @DisplayName("the rendering masks the card number and leaves the account identifier readable")
    void renderingMasksTheCardNumberOnly() {
        String rendered = request(CARD_NUMBER).toString();

        assertThat(rendered)
                .doesNotContain(CARD_NUMBER)
                .contains("maskedCardNumber=" + "*".repeat(12) + "2345")
                .contains("accountId=00000000011");
    }

    /**
     * Confirms a submission that supplied no card number renders without a failure.
     *
     * <p>Assumptions: an absent card number is valid input rather than an edge case -- the reference
     * fills whichever key alternative was omitted from the cross-reference -- so the rendering has to
     * survive it. A rendering that raised here would replace a validation diagnostic with an
     * unrelated failure at exactly the moment the diagnostic was wanted.</p>
     */
    @Test
    @DisplayName("an account-only submission renders without raising")
    void accountOnlySubmissionRenders() {
        String rendered = request(null).toString();

        assertThat(rendered).contains("maskedCardNumber=null").contains("accountId=00000000011");
    }

    /**
     * Confirms no other component was dropped while the masking was added.
     *
     * <p>Assumptions: this guards the specific regression an override invites. A hand-written
     * rendering replaces a generated one that named every component, so a component omitted by
     * oversight would silently reduce what a diagnostic says without failing anything.</p>
     */
    @Test
    @DisplayName("every component of the record appears in the rendering")
    void everyComponentIsRendered() {
        String rendered = request(CARD_NUMBER).toString();

        assertThat(rendered)
                .startsWith("TransactionAddRequest[")
                .endsWith("]")
                .contains("typeCode=01")
                .contains("categoryCode=0001")
                .contains("source=POS")
                .contains("description=GROCERY PURCHASE")
                .contains("amount=")
                .contains("merchantId=000000000")
                .contains("merchantName=CORNER STORE")
                .contains("merchantCity=SEATTLE")
                .contains("merchantZip=98101")
                .contains("originDate=2026-01-15")
                .contains("processDate=2026-01-16")
                .contains("confirmation=Y");
    }

    /**
     * Builds a submission whose only variable is the card number.
     *
     * @param cardNumber the card number to place on the request, or {@code null} for an account-only
     *     submission
     * @return a populated request, valid in shape, carrying {@code cardNumber}
     */
    private static TransactionAddRequest request(String cardNumber) {
        return new TransactionAddRequest("00000000011", "01", "0001", "POS", "GROCERY PURCHASE",
                Money.of("125.50"), "000000000", "CORNER STORE", "SEATTLE", "98101", cardNumber,
                "2026-01-15", "2026-01-16", "Y");
    }
}
