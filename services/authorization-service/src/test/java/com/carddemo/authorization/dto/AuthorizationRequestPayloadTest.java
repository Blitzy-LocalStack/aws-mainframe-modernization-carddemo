package com.carddemo.authorization.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the generated rendering of a received authorization message cannot carry an unmasked
 * primary account number.
 *
 * <p>Assumptions: the sixteen-digit value is assembled from a repeated digit rather than written as a
 * literal, so that no test fixture in this repository is a card-number-shaped constant a scanner has
 * to triage.</p>
 */
class AuthorizationRequestPayloadTest {

    /**
     * A card-number-width value assembled rather than written, ending in four distinguishable digits.
     */
    private static final String CARD_NUMBER = "1".repeat(12) + "2345";

    /**
     * Confirms the rendering masks the card number at its own width.
     */
    @Test
    @DisplayName("the rendering masks the card number")
    void renderingMasksTheCardNumber() {
        String rendered = payload().toString();

        assertThat(rendered)
                .doesNotContain(CARD_NUMBER)
                .contains("maskedCardNumber=" + "*".repeat(12) + "2345");
    }

    /**
     * Confirms every component is still NAMED, and that the seven sensitive ones name a withheld value.
     *
     * <p>Assumptions: this guards the specific regression an override invites on an eighteen-component
     * record. A hand-written rendering replaces a generated one that named every component, and a
     * component omitted by oversight would silently reduce what a consumer failure reports without
     * failing anything -- which matters here because a refusal on any one of the eighteen is the usual
     * reason this rendering is produced. Every component name is therefore still asserted present.</p>
     *
     * <p>Refactoring Rationale: seven of the eighteen are now asserted to render a withheld MARKER
     * rather than their value, where this case previously asserted all eighteen values present. The seven
     * are exactly the seven that {@code com.carddemo.common.codec.CsvAuthCodec} lists as sensitive wire
     * fields for this record -- the card expiry date, the amount, the four merchant identity components
     * and the transaction identifier -- and asserting their values present pinned a rendering that put a
     * cardholder's purchase into a log line. The distinction between "component absent" and "component
     * present with its value withheld" is asserted explicitly, because collapsing the two would let a
     * component quietly disappear from the rendering.</p>
     */
    @Test
    @DisplayName("every component is named, and the seven sensitive ones are withheld")
    void everyComponentIsRendered() {
        String rendered = payload().toString();

        assertThat(rendered)
                .startsWith("AuthorizationRequestPayload[")
                .endsWith("]")
                .contains("authDate=260115")
                .contains("authTime=143000")
                .contains("authType=01")
                .contains("cardExpiryDate=<withheld>")
                .contains("messageType=0100")
                .contains("messageSource=POS")
                .contains("processingCode=000000")
                .contains("transactionAmount=<withheld>")
                .contains("merchantCategoryCode=5411")
                .contains("acquirerCountryCode=840")
                .contains("posEntryMode=05")
                .contains("merchantId=<withheld>")
                .contains("merchantName=<withheld>")
                .contains("merchantCity=<withheld>")
                .contains("merchantState=WA")
                .contains("merchantZip=<withheld>")
                .contains("transactionId=<withheld>");

        assertThat(rendered)
                .doesNotContain("2812")
                .doesNotContain("125.50")
                .doesNotContain("CORNER STORE")
                .doesNotContain("SEATTLE")
                .doesNotContain("981010000")
                .doesNotContain("000000000000001");
    }

    /**
     * Confirms the reply counterpart masks its card number too.
     *
     * <p>Assumptions: the reply is asserted in this class rather than in one of its own because the
     * two renderings are one decision -- a reply echoes the request's card number, so masking one and
     * not the other would leave the value disclosed on the path that is logged more often, since a
     * reply is assembled, committed and published and each step can fail with the payload in
     * hand.</p>
     */
    @Test
    @DisplayName("the reply rendering masks the card number it echoes")
    void replyRenderingMasksTheCardNumber() {
        String rendered = new AuthorizationReplyPayload(CARD_NUMBER, "000000000000001", "143000",
                "00", "0000", Money.of("125.50")).toString();

        // Refactoring Rationale: the identifier and the amount are asserted WITHHELD, where this case
        //   previously asserted the identifier's value present. Both are named in the codec's
        //   sensitive-field set for the reply record, and the two layers that render the same reply have
        //   to agree or the value reaches the log through whichever of them a failure happens to touch.
        assertThat(rendered)
                .startsWith("AuthorizationReplyPayload[")
                .doesNotContain(CARD_NUMBER)
                .doesNotContain("000000000000001")
                .doesNotContain("125.50")
                .contains("maskedCardNumber=" + "*".repeat(12) + "2345")
                .contains("transactionId=<withheld>")
                .contains("authIdCode=143000")
                .contains("authResponseCode=00")
                .contains("authResponseReason=0000")
                .contains("approvedAmount=<withheld>");
    }

    /**
     * Builds a populated message payload.
     *
     * @return a payload whose every component carries a distinguishable value
     */
    private static AuthorizationRequestPayload payload() {
        return new AuthorizationRequestPayload("260115", "143000", CARD_NUMBER, "01", "2812", "0100",
                "POS", "000000", Money.of("125.50"), "5411", "840", "05", "000000000",
                "CORNER STORE", "SEATTLE", "WA", "981010000", "000000000000001");
    }
}
