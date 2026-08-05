package com.carddemo.common.codec;

import com.carddemo.common.codec.CsvAuthCodec.AuthMessageFormatException;
import com.carddemo.common.codec.CsvAuthCodec.AuthReply;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.OpaqueIdentifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link CsvAuthCodec} to the exact bytes the reference authorization program consumes and
 * emits, using golden vectors rather than round trips alone.
 *
 * <p>Assumptions: the reference for every expectation here is
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} together with the two payload copybooks
 * {@code CCPAURQY.cpy} and {@code CCPAURLY.cpy} beside it. Three statements in that program decide
 * everything this class asserts, and each is cited again at the test that depends on it: the
 * {@code UNSTRING ... DELIMITED BY ','} at lines 354 to 374, whose ordinal-nine receiver is
 * {@code WS-TRANSACTION-AMT-AN PIC X(13)} at line 63; the
 * {@code COMPUTE ... = FUNCTION NUMVAL(WS-TRANSACTION-AMT-AN)} at lines 376 to 377; and the
 * {@code MOVE WS-APPROVED-AMT TO WS-APPROVED-AMT-DIS} at line 720 into the mask
 * {@code PIC -zzzzzzzzz9.99} declared at line 66, joined into the reply buffer by the
 * {@code STRING ... DELIMITED BY SIZE} at lines 722 to 731.</p>
 *
 * <p>Alternatives Considered: asserting only that {@code decode(encode(x))} returns {@code x}. That
 * was the shape this class replaces and it cannot fail on the two defects that matter, because both
 * of them are symmetric: a codec that emits a fourteen-character request amount also accepts one,
 * and a codec that emits {@code +0000000100.00} where COBOL emits {@code        100.00} also parses
 * its own output. The bytes are therefore written out as literals below -- one per sign and cents
 * case -- and each literal's length is asserted alongside its content, because a length mismatch and
 * a content mismatch have different causes and a combined assertion would hide which occurred.</p>
 *
 * <p>Trade-offs: the golden strings are written with explicit space runs rather than assembled from
 * a helper. A helper would make the file shorter and would also reproduce, in the test, the very
 * padding logic under test -- so a mistake in the production loop and the same mistake in the helper
 * would agree and the test would pass. Written out, the expectation is independent of the code it
 * checks. The cost accepted is that a reader must count spaces, so every literal is accompanied by
 * an explicit statement of its width and of what occupies each region.</p>
 *
 * <p>Assumptions: no test here reads a clock, a file, an environment variable or a network resource.
 * Every expectation is a literal transcribed from the reference source, which is what lets this
 * class run with no database, no emulator and no COBOL compiler present.</p>
 *
 * <p>Parameters, return values and exceptions at type level: declared inapplicable. A class
 * declaration accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.</p>
 */
class CsvAuthCodecTest {

    /** The card number these tests submit, and which must never appear in an output or a message. */
    private static final String CARD_NUM = "4111111111111111";

    /** The transaction identifier these tests submit. */
    private static final String TRANSACTION_ID = "000000000000123";

    /** Key material of the required width, fixed so that a token is reproducible across instances. */
    private static final byte[] KEY =
            "carddemo-opaque-id-key-for-tests".getBytes(StandardCharsets.UTF_8);

    /**
     * A request whose every character field is exactly its declared width, so that only the money
     * field varies between the vectors below.
     *
     * <p>Assumptions: the values are shaped like the baseline's rather than chosen freely -- a
     * six-character date and time as {@code PA-RQ-AUTH-DATE} and {@code PA-RQ-AUTH-TIME} declare, a
     * sixteen-digit card number as {@code PA-RQ-CARD-NUM} declares, and a fifteen-character
     * transaction identifier as {@code PA-RQ-TRANSACTION-ID} declares. Filling every field to its
     * width keeps the assertions below about the money token alone: no other field can contribute a
     * pad byte that shifts the comparison.</p>
     *
     * @param amount the transaction amount to place in the ordinal-nine position
     * @return a request carrying {@code amount} and fully-populated character fields
     */
    private static AuthRequest requestWith(Money amount) {
        return new AuthRequest(
                "240715",
                "101530",
                "4111111111111111",
                "PURC",
                "1229",
                "AUTH01",
                "POS001",
                "000000",
                amount,
                "5411",
                "840",
                "05",
                "MERCHANT0000001",
                "ACME SUPERMARKET NO 12",
                "SEATTLE   WA ",
                "WA",
                "981010000",
                "TRN000000000001");
    }

    /**
     * A reply whose every character field is exactly its declared width, so that only the money field
     * varies between the vectors below.
     *
     * @param amount the approved amount to place in the sixth position
     * @return a reply carrying {@code amount} and fully-populated character fields
     */
    private static AuthReply replyWith(Money amount) {
        return new AuthReply(
                "4111111111111111",
                "TRN000000000001",
                "A00001",
                "00",
                "0000",
                amount);
    }

    /**
     * The request amount is rendered in thirteen characters, the width of the reference receiver.
     *
     * <p>Assumptions: this is the defect the split renderings exist to prevent. The receiver is
     * {@code WS-TRANSACTION-AMT-AN PIC X(13)} at line 63 of {@code COPAUA0C.cbl}, and an
     * {@code UNSTRING} receiver truncates on the right, so a fourteen-character token would lose its
     * final cent digit before {@code FUNCTION NUMVAL} ran at line 377.</p>
     */
    @Test
    void requestAmountIsThirteenCharactersWide() {
        assertEquals(13, CsvAuthCodec.REQUEST_MONEY_WIDTH);
        assertEquals(13, CsvAuthCodec.formatRequestMoney(Money.of("100.99"), "amt").length());
        assertEquals(13, CsvAuthCodec.REQUEST_FIELD_WIDTHS.get(8));
    }

    /**
     * A positive request amount renders as ten zero-padded digits, a point and two cents, unsigned.
     *
     * <p>Assumptions: the sign position is dropped rather than a digit position, because
     * {@code 9999999999.99} -- the largest value {@code PIC +9(10).99} at line 27 of
     * {@code CCPAURQY.cpy} can express -- is thirteen characters unsigned and fourteen signed, and
     * {@code FUNCTION NUMVAL} reads an unsigned token as positive.</p>
     */
    @Test
    void requestAmountGoldenVectorPositiveWithCents() {
        assertEquals("0000000100.99", CsvAuthCodec.formatRequestMoney(Money.of("100.99"), "amt"));
    }

    /**
     * A whole-dollar request amount still carries both cent digits.
     *
     * <p>Assumptions: the two fractional positions are literal digits in every case, so a value with
     * no cents is padded to {@code .00} rather than shortened. A shortened token would move the
     * following comma and shift every later field for the reference reader.</p>
     */
    @Test
    void requestAmountGoldenVectorPositiveWithoutCents() {
        assertEquals("0000000100.00", CsvAuthCodec.formatRequestMoney(Money.of("100.00"), "amt"));
    }

    /**
     * A zero request amount renders as all ten integer digits zero.
     */
    @Test
    void requestAmountGoldenVectorZero() {
        assertEquals("0000000000.00", CsvAuthCodec.formatRequestMoney(Money.ofCents(0L), "amt"));
    }

    /**
     * The maximum expressible request amount fits the receiver exactly with no truncation.
     *
     * <p>Assumptions: this is the vector that proves dropping the sign position rather than a digit
     * position was the correct trade. Ten nines and two nines is the largest value the copybook
     * picture admits, and it renders in exactly thirteen characters, so the whole baseline domain
     * survives the receiver.</p>
     */
    @Test
    void requestAmountGoldenVectorMaximumMagnitude() {
        assertEquals("9999999999.99",
                CsvAuthCodec.formatRequestMoney(Money.of("9999999999.99"), "amt"));
    }

    /**
     * A negative request amount spends one position on the sign and nine on digits.
     *
     * <p>Assumptions: the copybook's sign position permits a negative value even though the only
     * producer contract in the repository, {@code tests/mocks/mq_request_stub.py}, validates the
     * field as non-negative. Where a negative value is supplied it is rendered rather than rejected,
     * still in exactly thirteen characters.</p>
     */
    @Test
    void requestAmountGoldenVectorNegative() {
        assertEquals("-000000100.99", CsvAuthCodec.formatRequestMoney(Money.of("-100.99"), "amt"));
        assertEquals(13, CsvAuthCodec.formatRequestMoney(Money.of("-100.99"), "amt").length());
    }

    /**
     * A negative amount needing all ten integer digits is rejected by name, never truncated.
     *
     * <p>Assumptions: emitting a fourteenth character would be worse than failing, because the
     * reference receiver discards it silently and the resulting value is wrong by a factor of ten in
     * the cents. The failure message names the field and the two counts so that the rejection is
     * actionable.</p>
     */
    @Test
    void negativeRequestAmountBeyondNineDigitsIsRejected() {
        AuthMessageFormatException thrown = assertThrows(AuthMessageFormatException.class,
                () -> CsvAuthCodec.formatRequestMoney(new BigDecimal("-1000000000.00"), "amt"));
        assertTrue(thrown.getMessage().contains("amt"));
        assertTrue(thrown.getMessage().contains("10 integer digits"));
    }

    /**
     * A positive reply amount renders in the zero-suppressed mask with a blank sign position.
     *
     * <p>Assumptions: the expectation is fourteen characters -- one blank sign position, then the ten
     * integer positions of which the leading seven are suppressed to blanks, then the point and two
     * cents. That is {@code PIC -zzzzzzzzz9.99} at line 66 of {@code COPAUA0C.cbl} applied to
     * {@code 100.99}: a fixed sign-control position emits a space for a non-negative value, and each
     * {@code z} emits a space in place of a leading zero.</p>
     */
    @Test
    void replyAmountGoldenVectorPositiveWithCents() {
        String rendered = CsvAuthCodec.formatReplyMoney(Money.of("100.99"), "amt");
        assertEquals("        100.99", rendered);
        assertEquals(14, rendered.length());
        assertEquals(' ', rendered.charAt(0));
    }

    /**
     * A whole-dollar reply amount keeps both cent digits behind the suppressed run.
     */
    @Test
    void replyAmountGoldenVectorPositiveWithoutCents() {
        assertEquals("        100.00", CsvAuthCodec.formatReplyMoney(Money.of("100.00"), "amt"));
    }

    /**
     * A zero reply amount renders as ten blanks then {@code 0.00}, never as a blank field.
     *
     * <p>Assumptions: the single {@code 9} immediately left of the point in the mask is a forced
     * digit position that zero suppression cannot reach, which is what keeps a zero visible. A mask
     * of ten {@code z} positions would have emitted a field of blanks and a bare {@code .00}, and the
     * reference {@code FUNCTION NUMVAL} on the far side would then have read no integer digit at
     * all.</p>
     */
    @Test
    void replyAmountGoldenVectorZero() {
        String rendered = CsvAuthCodec.formatReplyMoney(Money.ofCents(0L), "amt");
        assertEquals("          0.00", rendered);
        assertEquals(14, rendered.length());
    }

    /**
     * A negative reply amount emits {@code -} in the sign position and suppresses to its left of the
     * digits.
     */
    @Test
    void replyAmountGoldenVectorNegative() {
        String rendered = CsvAuthCodec.formatReplyMoney(Money.of("-100.99"), "amt");
        assertEquals("-       100.99", rendered);
        assertEquals(14, rendered.length());
        assertEquals('-', rendered.charAt(0));
    }

    /**
     * The maximum reply amount fills every integer position, so nothing is suppressed.
     */
    @Test
    void replyAmountGoldenVectorMaximumMagnitude() {
        assertEquals(" 9999999999.99",
                CsvAuthCodec.formatReplyMoney(Money.of("9999999999.99"), "amt"));
    }

    /**
     * No rendering emits a {@code +}, because no baseline rendering does.
     *
     * <p>Assumptions: this is asserted directly rather than left implied by the vectors above,
     * because a {@code +} is what an intuitive implementation produces for a signed picture and it is
     * the exact byte an earlier revision emitted. The reply mask's leading {@code -} emits a blank
     * for a non-negative value and the request rendering has no sign position at all, so a plus sign
     * anywhere in either output is a regression.</p>
     */
    @Test
    void neitherRenderingEmitsAPlusSign() {
        assertFalseContains(CsvAuthCodec.formatReplyMoney(Money.of("1.00"), "amt"));
        assertFalseContains(CsvAuthCodec.formatRequestMoney(Money.of("1.00"), "amt"));
        assertFalseContains(CsvAuthCodec.formatReplyMoney(Money.ofCents(0L), "amt"));
        assertFalseContains(CsvAuthCodec.formatRequestMoney(Money.ofCents(0L), "amt"));
    }

    /**
     * Asserts that a rendered money token carries no {@code +} character.
     *
     * @param rendered the rendered token to inspect
     */
    private static void assertFalseContains(String rendered) {
        assertTrue(rendered.indexOf('+') < 0, "rendering must not emit '+', was '" + rendered + "'");
    }

    /**
     * The whole request payload is 169 characters with seventeen interior delimiters and no trailing
     * one.
     *
     * <p>Assumptions: 169 rather than 170, because the ordinal-nine field is thirteen characters. The
     * constant and the width table are asserted together with the emitted length so that a change to
     * one without the others fails here rather than at a consumer.</p>
     */
    @Test
    void requestPayloadLengthMatchesTheEmittedWidths() {
        String payload = CsvAuthCodec.encodeRequest(requestWith(Money.of("100.99")));

        assertEquals(169, CsvAuthCodec.REQUEST_WIRE_LENGTH);
        assertEquals(152, CsvAuthCodec.REQUEST_DECLARED_WIDTH_SUM);
        assertEquals(CsvAuthCodec.REQUEST_WIRE_LENGTH, payload.length());
        assertEquals(17, payload.chars().filter(character -> character == ',').count());
        assertEquals(CsvAuthCodec.REQUEST_DECLARED_WIDTH_SUM,
                CsvAuthCodec.REQUEST_FIELD_WIDTHS.stream().mapToInt(Integer::intValue).sum());
    }

    /**
     * The request payload carries the money token unsigned and thirteen wide in the ninth field.
     *
     * <p>Assumptions: the field is located by splitting on the delimiter rather than by byte offset,
     * because the reference intake is an {@code UNSTRING DELIMITED BY ','} and has no offsets to
     * address.</p>
     */
    @Test
    void requestPayloadCarriesTheThirteenCharacterAmountInTheNinthField() {
        String payload = CsvAuthCodec.encodeRequest(requestWith(Money.of("100.99")));
        String[] fields = payload.split(",", -1);

        assertEquals(18, fields.length);
        assertEquals("0000000100.99", fields[8]);
    }

    /**
     * The whole reply payload is 63 characters with six delimiters, the sixth trailing.
     *
     * <p>Assumptions: the trailing delimiter is part of the emitted form rather than an artefact --
     * the {@code STRING} at lines 722 to 731 of {@code COPAUA0C.cbl} pairs a {@code ','} literal with
     * every one of the six values, including the sixth at line 727.</p>
     */
    @Test
    void replyPayloadLengthMatchesTheDeclaredWidths() {
        String payload = CsvAuthCodec.encodeReply(replyWith(Money.of("100.99")));

        assertEquals(63, CsvAuthCodec.REPLY_WIRE_LENGTH);
        assertEquals(CsvAuthCodec.REPLY_WIRE_LENGTH, payload.length());
        assertEquals(6, payload.chars().filter(character -> character == ',').count());
        assertEquals(',', payload.charAt(payload.length() - 1));
    }

    /**
     * The reply payload is byte-identical to the buffer the reference program builds.
     *
     * <p>Assumptions: this is the single most valuable assertion in the class, because it compares a
     * whole emitted buffer against one transcribed from the reference statements rather than
     * comparing this codec with itself. The literal is the six values of
     * {@link #replyWith(Money)} joined by the {@code STRING} at lines 722 to 731, with the sixth
     * rendered through the mask at line 66.</p>
     */
    @Test
    void replyPayloadGoldenBuffer() {
        String payload = CsvAuthCodec.encodeReply(replyWith(Money.of("100.99")));

        assertEquals("4111111111111111,TRN000000000001,A00001,00,0000,        100.99,", payload);
    }

    /**
     * A reply built by the reference program decodes to the amount it carried.
     *
     * <p>Assumptions: the decode direction has to accept the mask's blank sign position, its leading
     * blanks and its shortened digit run, because that is what the reference emits. This vector is
     * therefore the reference's own output text, not this codec's.</p>
     */
    @Test
    void replyFromTheReferenceMaskDecodes() {
        AuthReply decoded = CsvAuthCodec.decodeReply(
                "4111111111111111,TRN000000000001,A00001,00,0000,        100.99,");

        assertEquals(Money.of("100.99"), decoded.approvedAmount());
        assertEquals("4111111111111111", decoded.cardNum());
        assertEquals("A00001", decoded.authIdCode());
    }

    /**
     * A zero-amount reply from the reference mask decodes to zero rather than failing.
     *
     * <p>Assumptions: the zero case is the one where the mask's blanks are widest, so it is the case
     * most likely to be rejected by a decoder that expects a fixed digit count.</p>
     */
    @Test
    void zeroReplyFromTheReferenceMaskDecodes() {
        AuthReply decoded = CsvAuthCodec.decodeReply(
                "4111111111111111,TRN000000000001,      ,05,5100,          0.00,");

        assertEquals(Money.ofCents(0L), decoded.approvedAmount());
        assertEquals("05", decoded.authRespCode());
        assertEquals("5100", decoded.authRespReason());
    }

    /**
     * Every emitted request survives the reference receiver's width without truncation.
     *
     * <p>Assumptions: this test models the receiver rather than trusting the constant. It takes the
     * ninth token of an emitted payload, truncates it to the thirteen characters
     * {@code WS-TRANSACTION-AMT-AN} holds, and asserts the truncation changed nothing -- which is
     * precisely the property that failed when the field was fourteen characters wide.</p>
     */
    @Test
    void emittedRequestAmountSurvivesTheReferenceReceiver() {
        for (String amount : new String[] {"0.00", "0.01", "100.99", "9999999999.99"}) {
            String token = CsvAuthCodec.encodeRequest(requestWith(Money.of(amount))).split(",", -1)[8];
            String asReceived = token.length() <= 13 ? token : token.substring(0, 13);

            assertEquals(token, asReceived, "token would be truncated by PIC X(13) for " + amount);
            assertEquals(Money.of(amount), CsvAuthCodec.parseMoney(asReceived, "amt"),
                    "value changed under the reference receiver for " + amount);
        }
    }

    /**
     * Confirms an over-long payload is refused on its length, so a delimiter flood cannot provoke work
     * proportional to what its sender chose to send.
     */
    @Test
    @DisplayName("a payload beyond the declared bound is refused before it is parsed")
    void oversizedPayloadIsRefused() {
        String flood = ",".repeat(CsvAuthCodec.MAX_PAYLOAD_LENGTH + 1);

        assertThatThrownBy(() -> CsvAuthCodec.decodeRequest(flood))
                .isInstanceOf(AuthMessageFormatException.class)
                .hasMessageContaining("beyond the " + CsvAuthCodec.MAX_PAYLOAD_LENGTH);
    }

    /**
     * Confirms the same bound is applied on the byte path, which decides how many bytes become
     * characters and therefore has to check before converting.
     */
    @Test
    @DisplayName("an over-long transport buffer length is refused on the byte path")
    void oversizedBufferLengthIsRefused() {
        byte[] buffer = new byte[CsvAuthCodec.MAX_PAYLOAD_LENGTH + 8];

        assertThatThrownBy(() -> CsvAuthCodec.decodeRequest(buffer, buffer.length))
                .isInstanceOf(AuthMessageFormatException.class)
                .hasMessageContaining("beyond the " + CsvAuthCodec.MAX_PAYLOAD_LENGTH);
    }

    /**
     * Confirms a payload carrying a control character is refused and that the refusal reports the
     * position rather than the payload.
     */
    @Test
    @DisplayName("a payload carrying a control character is refused, and is not quoted back")
    void controlCharacterIsRefused() {
        String withNewline = CsvAuthCodec.encodeRequest(request(CARD_NUM, TRANSACTION_ID))
                .replace(",A   ,", ",A\n  ,");

        assertThatThrownBy(() -> CsvAuthCodec.decodeRequest(withNewline))
                .isInstanceOf(AuthMessageFormatException.class)
                .hasMessageContaining("control character at zero-based position")
                .hasMessageNotContaining(CARD_NUM);
    }

    /**
     * Confirms a field-count failure names the counts and never the payload, since the payload of a
     * malformed authorization message still carries a card number.
     */
    @Test
    @DisplayName("a field-count refusal reports counts and never the payload")
    void fieldCountRefusalDoesNotQuoteThePayload() {
        String truncated = "20220718,120000," + CARD_NUM + ",A";

        assertThatThrownBy(() -> CsvAuthCodec.decodeRequest(truncated))
                .isInstanceOf(AuthMessageFormatException.class)
                .hasMessageContaining("delimited fields but the copybook declares")
                .hasMessageNotContaining(CARD_NUM);
    }

    /**
     * Confirms an over-wide component is refused with the two widths and without the value, on the path
     * a producer reaches when it supplies a field its copybook line cannot hold.
     */
    @Test
    @DisplayName("an over-wide component is refused without being quoted")
    void overWideComponentIsRefusedWithoutBeingQuoted() {
        String tooWide = CARD_NUM + "9999";

        assertThatThrownBy(() -> request(tooWide, TRANSACTION_ID))
                .isInstanceOf(AuthMessageFormatException.class)
                .hasMessageContaining("the copybook declares 16")
                .hasMessageNotContaining(tooWide);
    }

    /**
     * Confirms a money token that is not the declared edited picture is refused by length and shape
     * alone, so a malformed amount does not travel into a log.
     */
    @Test
    @DisplayName("a malformed money token is refused without being quoted")
    void malformedMoneyTokenIsRefusedWithoutBeingQuoted() {
        String token = "+00000012X4.99";

        assertThatThrownBy(() -> CsvAuthCodec.parseMoney(token, "PA-RQ-TRANSACTION-AMT"))
                .isInstanceOf(AuthMessageFormatException.class)
                .hasMessageNotContaining(token);
    }

    /**
     * Confirms the correlation identity is opaque, bounded and free of the two values it stands for.
     */
    @Test
    @DisplayName("the correlation identity carries neither the card number nor the transaction id")
    void correlationIdentityIsOpaque() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);

        String correlation = request(CARD_NUM, TRANSACTION_ID).correlationKey(tokeniser);

        assertThat(correlation)
                .hasSize(OpaqueIdentifier.TOKEN_LENGTH)
                .doesNotContain(CARD_NUM)
                .doesNotContain(TRANSACTION_ID);
    }

    /**
     * Confirms the correlation identity is stable, which is what lets a reply be paired with its request
     * by equality now that the pair itself no longer travels.
     */
    @Test
    @DisplayName("the correlation identity is stable for one card and transaction pair")
    void correlationIdentityIsStable() {
        String first = request(CARD_NUM, TRANSACTION_ID)
                .correlationKey(new OpaqueIdentifier(KEY));
        String second = request(CARD_NUM, TRANSACTION_ID)
                .correlationKey(new OpaqueIdentifier(KEY));

        assertThat(first).isEqualTo(second);
    }

    /**
     * Confirms both legs derive one opaque correlation identity, so the queue can pair and
     * deduplicate the exchange without publishing either business identifier.
     */
    @Test
    @DisplayName("request and reply derive the same opaque correlation identity")
    void requestAndReplyCorrelationIdentitiesMatch() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);
        String requestKey = request(CARD_NUM, TRANSACTION_ID).correlationKey(tokeniser);
        String replyKey = new AuthReply(
                CARD_NUM,
                TRANSACTION_ID,
                "A12345",
                "00",
                "0000",
                Money.of("1000.00"))
                .correlationKey(tokeniser);

        assertThat(replyKey)
                .isEqualTo(requestKey)
                .hasSize(OpaqueIdentifier.TOKEN_LENGTH)
                .doesNotContain(CARD_NUM)
                .doesNotContain(TRANSACTION_ID);
    }

    /**
     * Confirms a different transaction on the same card yields a different correlation identity, so two
     * exchanges cannot be paired with one another.
     */
    @Test
    @DisplayName("a different transaction yields a different correlation identity")
    void correlationIdentityDistinguishesTransactions() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);

        String first = request(CARD_NUM, TRANSACTION_ID).correlationKey(tokeniser);
        String second = request(CARD_NUM, "000000000000124").correlationKey(tokeniser);

        assertThat(first).isNotEqualTo(second);
    }

    /**
     * Confirms the ordering group is stable per card and independent of the transaction, which is what
     * preserves per-card first-in-first-out ordering without the card number becoming queue metadata.
     */
    @Test
    @DisplayName("the ordering group is per card, stable, and carries no part of the card number")
    void orderingGroupIsPerCardAndOpaque() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);

        String first = request(CARD_NUM, TRANSACTION_ID).orderGroup(tokeniser);
        String second = request(CARD_NUM, "000000000000124").orderGroup(tokeniser);
        String other = request("4111111111111112", TRANSACTION_ID).orderGroup(tokeniser);

        assertThat(first).isEqualTo(second).doesNotContain(CARD_NUM);
        assertThat(other).isNotEqualTo(first);
    }

    /**
     * Confirms the ordering group and the correlation identity of one card are unrelated, so an observer
     * of queue metadata and of an application log cannot join them.
     */
    @Test
    @DisplayName("the ordering group and the correlation identity are scoped apart")
    void purposesAreScopedApart() {
        OpaqueIdentifier tokeniser = new OpaqueIdentifier(KEY);
        AuthRequest authRequest = request(CARD_NUM, TRANSACTION_ID);

        assertThat(authRequest.orderGroup(tokeniser))
                .isNotEqualTo(authRequest.correlationKey(tokeniser));
    }

    /**
     * Confirms an identity cannot be obtained without a tokeniser, so there is no path back to the
     * value-bearing correlation this codec used to return.
     */
    @Test
    @DisplayName("an identity cannot be derived without key material")
    void identityRequiresKeyMaterial() {
        AuthRequest authRequest = request(CARD_NUM, TRANSACTION_ID);

        assertThatThrownBy(() -> authRequest.correlationKey(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> authRequest.orderGroup(null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Confirms a well-formed request still round-trips, so none of the hardening above has narrowed the
     * contract the reference program emits.
     */
    @Test
    @DisplayName("a well-formed request round-trips through encode and decode")
    void wellFormedRequestRoundTrips() {
        AuthRequest original = request(CARD_NUM, TRANSACTION_ID);

        String payload = CsvAuthCodec.encodeRequest(original);

        assertThat(payload).hasSize(CsvAuthCodec.REQUEST_WIRE_LENGTH);
        assertThat(CsvAuthCodec.decodeRequest(payload)).isEqualTo(original);
    }

    /**
     * Builds a well-formed request whose card number and transaction identifier are the caller's.
     *
     * <p>Assumptions: every other component is a fixed, valid value inside its declared width, because
     * these tests are about the two identified fields and about payload handling. Fixing the rest keeps
     * each expectation about one variable.</p>
     *
     * @param cardNum the card number to carry
     * @param transactionId the transaction identifier to carry
     * @return the assembled request
     */
    private static AuthRequest request(String cardNum, String transactionId) {
        return new AuthRequest(
                "220718",
                "120000",
                cardNum,
                "A",
                "1231",
                "0100",
                "POS",
                "000",
                Money.of("1000.00"),
                "5411",
                "USA",
                "90",
                "MERCH000000001",
                "MERCHANT NAME",
                "CITY",
                "TX",
                "75001",
                transactionId);
    }

}
