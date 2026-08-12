package com.carddemo.common.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.InquiryRequestCodec.InquiryRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the fixed-width framing both inquiry request/reply flows exchange.
 *
 * <h2>Purpose</h2>
 * <p>The layout under test is a wire contract shared by two consumers and by whatever produces their requests,
 * and nothing in any build compares this transcription against the reference programs that fix it. This class
 * asserts the three properties a producer and a consumer must agree on -- the field offsets, the total message
 * length, and what happens to a payload that is not exactly that length -- plus the numeric test one of the two
 * flows applies to the key.</p>
 *
 * <p>Assumptions: the widths are asserted as literals rather than computed from the constants they came from.
 * Deriving them would make this class agree with the codec by construction and prove nothing about whether
 * either agrees with the copybook.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no parameter,
 * return or exception section.</p>
 */
class InquiryRequestCodecTest {

    /**
     * Verifies the three declared widths sum to the message length.
     *
     * <p>Assumptions: this is the arithmetic the copybook itself performs -- four plus eleven plus 985 -- and
     * it is asserted because a wrong width in any one of the three would shift the fields that follow without
     * producing an error anywhere.</p>
     */
    @Test
    @DisplayName("the three declared widths sum to the message length")
    void theWidthsSumToTheMessageLength() {
        assertThat(InquiryRequestCodec.FUNCTION_WIDTH).isEqualTo(4);
        assertThat(InquiryRequestCodec.KEY_WIDTH).isEqualTo(11);
        assertThat(InquiryRequestCodec.FILLER_WIDTH).isEqualTo(985);
        assertThat(InquiryRequestCodec.MESSAGE_LENGTH).isEqualTo(1000);
        assertThat(InquiryRequestCodec.FUNCTION_WIDTH
                + InquiryRequestCodec.KEY_WIDTH
                + InquiryRequestCodec.FILLER_WIDTH)
                .isEqualTo(InquiryRequestCodec.MESSAGE_LENGTH);
    }

    /**
     * Verifies a full-length request splits at the copybook's offsets.
     */
    @Test
    @DisplayName("a full-length request splits at the copybook offsets")
    void aFullLengthRequestSplitsAtTheCopybookOffsets() {
        String payload = "INQA" + "00000000123" + " ".repeat(985);

        InquiryRequest request = InquiryRequestCodec.decode(payload);

        assertThat(request.function()).isEqualTo("INQA");
        assertThat(request.key()).isEqualTo("00000000123");
        assertThat(request.hasUsableKey()).isTrue();
        assertThat(request.keyValue()).isEqualTo(123L);
    }

    /**
     * Verifies a short payload is padded rather than refused.
     *
     * <p>Assumptions: this is the behaviour of the reference program's group move, which space-pads a shorter
     * source. Refusing here would dead-letter a message the baseline answered, so the leniency is fidelity
     * rather than laxity.</p>
     */
    @Test
    @DisplayName("a short payload is padded rather than refused")
    void aShortPayloadIsPadded() {
        InquiryRequest request = InquiryRequestCodec.decode("INQA1");

        assertThat(request.function()).isEqualTo("INQA");
        assertThat(request.key()).hasSize(InquiryRequestCodec.KEY_WIDTH).isEqualTo("1          ");
        assertThat(request.hasUsableKey())
                .as("a key padded with spaces is not a number, so the guard must refuse it")
                .isFalse();
    }

    /**
     * Verifies an empty payload decodes to blank fields rather than raising.
     */
    @Test
    @DisplayName("an empty payload decodes to blank fields")
    void anEmptyPayloadDecodesToBlanks() {
        InquiryRequest request = InquiryRequestCodec.decode("");

        assertThat(request.function()).isEqualTo("    ");
        assertThat(request.trimmedFunction()).isEmpty();
        assertThat(request.key()).isEqualTo("           ");
        assertThat(request.hasUsableKey()).isFalse();
    }

    /**
     * Verifies an over-long payload is truncated on the way in.
     *
     * <p>Assumptions: truncation inbound mirrors the group move, whose target is shorter than an over-long
     * source. The OUTBOUND direction deliberately refuses instead, which the framing test below asserts.</p>
     */
    @Test
    @DisplayName("an over-long payload is truncated on the way in")
    void anOverLongPayloadIsTruncated() {
        InquiryRequest request =
                InquiryRequestCodec.decode("INQA00000000123" + "X".repeat(2000));

        assertThat(request.function()).isEqualTo("INQA");
        assertThat(request.key()).isEqualTo("00000000123");
    }

    /**
     * Verifies a null payload is a caller defect rather than a wire condition.
     */
    @Test
    @DisplayName("a null payload is refused")
    void aNullPayloadIsRefused() {
        assertThatThrownBy(() -> InquiryRequestCodec.decode(null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Verifies the function comparison strips only trailing padding and is case sensitive.
     *
     * <p>Assumptions: the leading-space case is asserted because trimming both ends would make {@code " INQ"}
     * equal {@code "INQ "}, and a producer that failed to left-justify its code would then be answered as if
     * it had not. The lower-case case is asserted because the reference test is a COBOL literal comparison,
     * which is case sensitive, so accepting {@code "inqa"} would answer a request the baseline refused.</p>
     */
    @Test
    @DisplayName("the function comparison strips only trailing padding and is case sensitive")
    void theFunctionComparisonIsExact() {
        assertThat(InquiryRequestCodec.decode("INQA00000000123")
                .isFunction(InquiryRequestCodec.FUNCTION_ACCOUNT_INQUIRY)).isTrue();
        assertThat(InquiryRequestCodec.decode("inqa00000000123")
                .isFunction(InquiryRequestCodec.FUNCTION_ACCOUNT_INQUIRY)).isFalse();
        assertThat(InquiryRequestCodec.decode(" INQ00000000123")
                .isFunction(InquiryRequestCodec.FUNCTION_ACCOUNT_INQUIRY)).isFalse();
        assertThat(InquiryRequestCodec.decode(" INQ00000000123").trimmedFunction())
                .as("a leading space is significant and is not stripped")
                .isEqualTo(" INQ");
    }

    /**
     * Verifies the key guard is the reference program's own test.
     *
     * <p>Assumptions: the all-zero key is the case the reference guard exists for -- {@code WS-KEY > ZEROES}
     * is false for it -- so it must be refused even though it is entirely numeric.</p>
     */
    @Test
    @DisplayName("the key guard refuses zero, blanks and non-digits and accepts a positive number")
    void theKeyGuardMatchesTheReferenceTest() {
        assertThat(InquiryRequestCodec.decode("INQA00000000000").hasUsableKey())
                .as("an all-zero key fails WS-KEY > ZEROES")
                .isFalse();
        assertThat(InquiryRequestCodec.decode("INQA           ").hasUsableKey()).isFalse();
        assertThat(InquiryRequestCodec.decode("INQA0000000012X").hasUsableKey()).isFalse();
        assertThat(InquiryRequestCodec.decode("INQA00000000001").hasUsableKey()).isTrue();
        assertThat(InquiryRequestCodec.decode("INQA99999999999").keyValue()).isEqualTo(99_999_999_999L);
    }

    /**
     * Verifies reading a number out of an unusable key is refused.
     *
     * <p>Assumptions: refusing here rather than returning zero is what stops a caller that skipped the guard
     * from looking up account zero and reporting whatever it finds.</p>
     */
    @Test
    @DisplayName("reading a number out of an unusable key is refused")
    void readingANumberOutOfAnUnusableKeyIsRefused() {
        InquiryRequest request = InquiryRequestCodec.decode("INQA           ");

        assertThatThrownBy(request::keyValue)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hasUsableKey");
    }

    /**
     * Verifies framing pads a reply body to the message length and refuses an over-long one.
     *
     * <p>Assumptions: the outbound direction refuses where the inbound direction truncates, and the asymmetry
     * is deliberate. An over-long INBOUND payload is something a producer sent and the baseline tolerated; an
     * over-long OUTBOUND body is a defect in this system's own formatting, and truncating it would silently
     * drop the end of a reply.</p>
     */
    @Test
    @DisplayName("framing pads a reply and refuses an over-long one")
    void framingPadsAndRefuses() {
        assertThat(InquiryRequestCodec.frame("ACCOUNT ID : 1"))
                .hasSize(InquiryRequestCodec.MESSAGE_LENGTH)
                .startsWith("ACCOUNT ID : 1")
                .endsWith(" ");
        assertThat(InquiryRequestCodec.frame("X".repeat(InquiryRequestCodec.MESSAGE_LENGTH)))
                .hasSize(InquiryRequestCodec.MESSAGE_LENGTH);
        assertThatThrownBy(() -> InquiryRequestCodec.frame(
                "X".repeat(InquiryRequestCodec.MESSAGE_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("silently drop");
    }

    /**
     * Verifies a hand-built request at the wrong width is refused.
     */
    @Test
    @DisplayName("a hand-built request at the wrong width is refused")
    void aHandBuiltRequestAtTheWrongWidthIsRefused() {
        assertThatThrownBy(() -> new InquiryRequest("INQ", "00000000001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WS-FUNC");
        assertThatThrownBy(() -> new InquiryRequest("INQA", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WS-KEY");
    }

    // WHY : Refactoring Rationale: this case asserted that the rendering CONTAINED the key, on the reading
    //       that an account identifier is safe because the system calls it internal. The observability
    //       contract requires an identifier to be omitted from a durable field rather than shortened, so
    //       the assertion is now an ABSENCE. The function code moved the same way for a different reason:
    //       four characters of unconstrained wire content reaching a journal line through a record's own
    //       rendering is CWE-117 by the shortest route available.
    /**
     * Verifies the diagnostic rendering omits the filler, the key and the raw function code.
     *
     * <p>Assumptions: the filler is 985 characters neither flow reads and a producer may put anything in it,
     * so rendering it would place unexamined wire content into a log line. The same is true of the four
     * characters of the function field, and the key is an account identifier.</p>
     */
    @Test
    @DisplayName("the diagnostic rendering omits the filler, the key and the raw function")
    void theRenderingOmitsTheFiller() {
        String rendered = InquiryRequestCodec.decode(
                "FRGE00000000123" + "SECRET".repeat(100)).toString();

        assertThat(rendered)
                .doesNotContain("SECRET")
                .doesNotContain("00000000123")
                .doesNotContain("FRGE")
                .contains(InquiryRequestCodec.FUNCTION_LABEL_UNRECOGNISED)
                .contains("keyUsable=true");
    }

    // WHY : Assumptions: the classification is asserted to be one of the three declared tokens for EVERY
    //       input tried, including inputs carrying a line terminator and a forged event prefix. Asserting
    //       only that the forged text is absent would pass for a method that returned the empty string, so
    //       the membership assertion is what establishes that a caller always gets something to log.
    /**
     * Verifies the function classification is closed and never echoes wire content.
     */
    @Test
    @DisplayName("the function classification is closed and echoes no wire content")
    void theFunctionClassificationIsClosed() {
        String forged = "\nev";
        for (String function : java.util.List.of("INQA", "inqa", "    ", " INQ", "XXXX", forged, "\u0000AB\u0000")) {
            String label = InquiryRequestCodec
                    .decode(function + "00000000001").functionLabel();
            assertThat(label)
                    .as("the label for %s must be one of the three declared tokens",
                            function.replace("\n", "\\n").replace("\u0000", "\\0"))
                    .isIn(InquiryRequestCodec.FUNCTION_ACCOUNT_INQUIRY,
                            InquiryRequestCodec.FUNCTION_LABEL_BLANK,
                            InquiryRequestCodec.FUNCTION_LABEL_UNRECOGNISED);
        }

        assertThat(InquiryRequestCodec.decode("INQA00000000001").functionLabel())
                .isEqualTo(InquiryRequestCodec.FUNCTION_ACCOUNT_INQUIRY);
        assertThat(InquiryRequestCodec.decode("    00000000001").functionLabel())
                .isEqualTo(InquiryRequestCodec.FUNCTION_LABEL_BLANK);
        assertThat(InquiryRequestCodec.decode("inqa00000000001").functionLabel())
                .as("the reference comparison is case sensitive, so a lower-case code is not the literal")
                .isEqualTo(InquiryRequestCodec.FUNCTION_LABEL_UNRECOGNISED);
    }

    // WHY : Assumptions: the diagnostic is asserted by OFFSET and not by content, because a consumer of the
    //       error sink locates every value positionally. A case that only checked the fields were present
    //       would pass for a buffer whose gaps had moved, which is the one way this contract breaks.
    /**
     * Verifies the error diagnostic places every field at the offset the reference group declares.
     */
    @Test
    @DisplayName("the error diagnostic places every field at its declared offset")
    void theErrorDiagnosticIsPositional() {
        String composed = InquiryRequestCodec.errorDiagnostic(
                "4000-PROCESS-REQUEST-REPLY", "ERROR WHILE READING ACCTFILE", "carddemo-error", "detail");

        int messageAt = InquiryRequestCodec.DIAGNOSTIC_PARAGRAPH_WIDTH
                + InquiryRequestCodec.DIAGNOSTIC_GAP_WIDTH;
        int queueAt = InquiryRequestCodec.DIAGNOSTIC_PREFIX_LENGTH
                - InquiryRequestCodec.DIAGNOSTIC_QUEUE_NAME_WIDTH;

        assertThat(composed.substring(0, InquiryRequestCodec.DIAGNOSTIC_PARAGRAPH_WIDTH))
                .as("a 26-character paragraph name arrives as its leading 25")
                .isEqualTo("4000-PROCESS-REQUEST-REPL");
        assertThat(composed.substring(messageAt,
                messageAt + InquiryRequestCodec.DIAGNOSTIC_MESSAGE_WIDTH))
                .as("a 28-character return message arrives as its leading 25")
                .isEqualTo("ERROR WHILE READING ACCTF");
        assertThat(composed.substring(queueAt,
                queueAt + InquiryRequestCodec.DIAGNOSTIC_QUEUE_NAME_WIDTH).trim())
                .isEqualTo("carddemo-error");
        assertThat(composed.substring(InquiryRequestCodec.DIAGNOSTIC_PREFIX_LENGTH))
                .as("the failure detail occupies space the baseline leaves blank")
                .isEqualTo("detail");
        assertThat(InquiryRequestCodec.frame(composed))
                .hasSize(InquiryRequestCodec.MESSAGE_LENGTH);
    }

    // WHY : Assumptions: an over-long detail is asserted to be TRUNCATED rather than to raise, because the
    //       raise would happen on the reporting path and would replace the fault being reported.
    /**
     * Verifies an over-long failure detail is truncated to what the message length leaves.
     */
    @Test
    @DisplayName("an over-long failure detail is truncated rather than refused")
    void anOverLongDetailIsTruncated() {
        String composed = InquiryRequestCodec.errorDiagnostic(null, null, null,
                "x".repeat(InquiryRequestCodec.MESSAGE_LENGTH * 2));

        assertThat(composed).hasSize(InquiryRequestCodec.MESSAGE_LENGTH);
        assertThat(InquiryRequestCodec.frame(composed))
                .hasSize(InquiryRequestCodec.MESSAGE_LENGTH);
    }

    /**
     * Verifies the utility holder cannot be instantiated.
     *
     * @throws Exception if the declared constructor cannot be reflected, which would mean it was removed
     */
    @Test
    @DisplayName("the utility holder cannot be instantiated")
    void theHolderCannotBeInstantiated() throws Exception {
        var constructor = InquiryRequestCodec.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance)
                .hasRootCauseInstanceOf(AssertionError.class);
    }
}
