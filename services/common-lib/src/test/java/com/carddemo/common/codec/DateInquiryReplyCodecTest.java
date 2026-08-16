package com.carddemo.common.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact bytes of the date-and-time inquiry reply.
 *
 * <h2>Purpose</h2>
 * <p>The reply is put with a string format indicator, so its label text, its separators and its two field
 * widths are the interface -- a consumer reads the date and the time by offset. This class asserts the whole
 * forty-six-character body for a known instant, which is the only way a positional contract can be checked.</p>
 *
 * <p>Assumptions: a known instant is supplied rather than the wall clock being read, so the expected bytes can
 * be written out in full. A test that read the clock could only assert a shape, and a shape assertion would
 * pass for the wrong date ordering.</p>
 *
 * <p>Refactoring Rationale: these cases moved here with the renderer they cover. The renderer was a component
 * of the reference context while that context consumed a date-inquiry queue of its own; both inquiry flows now
 * arrive on the one shared request queue the baseline defines, so the layout is owned by the shared kernel
 * beside the request half of the same wire and the cases travel with it rather than being rewritten.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no parameter,
 * return or exception section.</p>
 */
class DateInquiryReplyCodecTest {

    /**
     * An instant whose month and day differ, so a month-first and a day-first rendering are distinguishable.
     *
     * <p>Assumptions: the twenty-fifth of December is chosen deliberately. For any day up to the twelfth the
     * two orderings are indistinguishable, so a fixture in that range would pass whichever ordering the codec
     * used -- which is exactly how an ordering defect reaches production.</p>
     */
    private static final LocalDateTime INSTANT = LocalDateTime.of(2026, 12, 25, 13, 45, 9);

    /**
     * Verifies the reply body is exactly the reference program's string, character for character.
     */
    @Test
    @DisplayName("the reply body is exactly the reference program's string")
    void theReplyBodyIsExact() {
        assertThat(DateInquiryReplyCodec.systemDateAndTime(INSTANT))
                .isEqualTo("SYSTEM DATE : 12-25-2026SYSTEM TIME : 13:45:09")
                .hasSize(DateInquiryReplyCodec.REPLY_BODY_LENGTH);
    }

    /**
     * Verifies the date is rendered month-first with hyphen separators.
     *
     * <p>Assumptions: this is asserted on its own because it is the one field a well-meaning author would
     * "correct" to the ISO ordering used everywhere else in this migration. The reference program requests
     * {@code MMDDYYYY} with {@code DATESEP('-')}, so month-first is the contract.</p>
     */
    @Test
    @DisplayName("the date is month-first with hyphen separators, not ISO")
    void theDateIsMonthFirst() {
        String body = DateInquiryReplyCodec.systemDateAndTime(INSTANT);

        assertThat(body).contains("12-25-2026");
        assertThat(body)
                .as("the ISO ordering would be a silent change to a positionally parsed value")
                .doesNotContain("2026-12-25");
    }

    /**
     * Verifies both labels cross verbatim, including the spacing around the colon.
     *
     * <p>Assumptions: the spacing is asserted because tidying {@code 'SYSTEM DATE : '} to
     * {@code 'SYSTEM DATE: '} would shift every following character by one and break every consumer reading by
     * offset, while looking like a formatting improvement in a diff.</p>
     */
    @Test
    @DisplayName("both labels cross verbatim including the spacing around the colon")
    void bothLabelsCrossVerbatim() {
        String body = DateInquiryReplyCodec.systemDateAndTime(INSTANT);

        assertThat(body).startsWith("SYSTEM DATE : ").contains("SYSTEM TIME : ");
        assertThat(body.indexOf("SYSTEM DATE : ")).isZero();
        assertThat(body.indexOf("SYSTEM TIME : ")).isEqualTo(24);
    }

    /**
     * Verifies single-digit month, day, hour, minute and second components are zero-padded.
     *
     * <p>Assumptions: padding is what keeps the body at its declared length. An unpadded component would
     * shorten the body and shift the time field, which the codec's own length assertion would catch -- and
     * this test proves that assertion is reachable rather than theoretical.</p>
     */
    @Test
    @DisplayName("single-digit components are zero-padded so the length never varies")
    void singleDigitComponentsArePadded() {
        assertThat(DateInquiryReplyCodec.systemDateAndTime(LocalDateTime.of(2026, 1, 2, 3, 4, 5)))
                .isEqualTo("SYSTEM DATE : 01-02-2026SYSTEM TIME : 03:04:05")
                .hasSize(DateInquiryReplyCodec.REPLY_BODY_LENGTH);
    }

    /**
     * Verifies midnight renders as the twenty-four-hour form rather than as twelve.
     *
     * <p>Assumptions: asserted because a twelve-hour pattern would render midnight as {@code 12:00:00} and
     * would still be the right LENGTH, so the body-length check could not catch it.</p>
     */
    @Test
    @DisplayName("midnight renders in the twenty-four-hour form")
    void midnightRendersInTwentyFourHourForm() {
        assertThat(DateInquiryReplyCodec.systemDateAndTime(LocalDateTime.of(2026, 12, 25, 0, 0, 0)))
                .contains("SYSTEM TIME : 00:00:00");
        assertThat(DateInquiryReplyCodec.systemDateAndTime(LocalDateTime.of(2026, 12, 25, 23, 59, 59)))
                .contains("SYSTEM TIME : 23:59:59");
    }

    /**
     * Verifies framing pads the body to the message length the wire carries, through the shared framing rule.
     */
    @Test
    @DisplayName("framing pads the reply to the message length")
    void framingPadsToTheMessageLength() {
        String framed = DateInquiryReplyCodec.framedSystemDateAndTime(INSTANT);

        assertThat(framed)
                .hasSize(InquiryRequestCodec.MESSAGE_LENGTH)
                .startsWith("SYSTEM DATE : 12-25-2026SYSTEM TIME : 13:45:09");
        assertThat(framed.substring(DateInquiryReplyCodec.REPLY_BODY_LENGTH).chars())
                .allMatch(character -> character == ' ');
    }

    /**
     * Verifies a null instant is refused rather than rendered as something.
     */
    @Test
    @DisplayName("a null instant is refused")
    void aNullInstantIsRefused() {
        assertThatThrownBy(() -> DateInquiryReplyCodec.systemDateAndTime(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DateInquiryReplyCodec.framedSystemDateAndTime(null))
                .isInstanceOf(NullPointerException.class);
    }
}
