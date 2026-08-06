package com.carddemo.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the two masking operations of the shared masker, including the boundaries that decide
 * whether a digit run is treated as a card number.
 *
 * <p>Assumptions: the sixteen-digit values used here are constructed from a repeated digit rather
 * than written as a literal that resembles a real card number, so that no test fixture in this
 * repository is a card-number-shaped constant a scanner would have to triage.</p>
 */
class CardNumberMaskerTest {

    /**
     * A card-number-width value assembled rather than written, ending in four distinguishable digits.
     */
    private static final String CARD_NUMBER = "1".repeat(12) + "2345";

    /**
     * Confirms a known card number keeps its own width and reveals only its final four characters.
     */
    @Test
    @DisplayName("a card number is masked at its own width with four characters visible")
    void cardNumberIsMaskedAtItsOwnWidth() {
        String masked = CardNumberMasker.mask(CARD_NUMBER);

        assertThat(masked).hasSameSizeAs(CARD_NUMBER).endsWith("2345");
        assertThat(masked.substring(0, masked.length() - CardNumberMasker.VISIBLE_TAIL_LENGTH))
                .isEqualTo(String.valueOf(CardNumberMasker.MASK_CHARACTER)
                        .repeat(CARD_NUMBER.length() - CardNumberMasker.VISIBLE_TAIL_LENGTH));
    }

    /**
     * Confirms a value no longer than the visible tail is masked entirely rather than returned whole.
     *
     * <p>Assumptions: this is the boundary the previous per-site implementation got wrong in the
     * opposite direction -- it returned a short value unchanged -- so it is asserted explicitly
     * rather than left to the general case.</p>
     */
    @Test
    @DisplayName("a value at or below the visible-tail length is masked entirely")
    void shortValueIsMaskedEntirely() {
        assertThat(CardNumberMasker.mask("2345")).isEqualTo("****");
        assertThat(CardNumberMasker.mask("5")).isEqualTo("*");
        assertThat(CardNumberMasker.mask("")).isEmpty();
    }

    /**
     * Confirms an absent value stays absent through both operations.
     */
    @Test
    @DisplayName("a null argument is returned as null by both operations")
    void nullIsPreserved() {
        assertThat(CardNumberMasker.mask(null)).isNull();
        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(null)).isNull();
    }

    /**
     * Confirms a card-number-shaped run inside a request path is masked while the route survives.
     */
    @Test
    @DisplayName("a card-number run inside a path is masked and the surrounding route is preserved")
    void embeddedRunInPathIsMasked() {
        String masked =
                CardNumberMasker.maskEmbeddedCardNumbers("/api/v1/cards/" + CARD_NUMBER + "/unmasked");

        assertThat(masked)
                .startsWith("/api/v1/cards/")
                .endsWith("2345/unmasked")
                .doesNotContain(CARD_NUMBER);
    }

    /**
     * Confirms every card-number-shaped run in one text is masked, not only the first.
     */
    @Test
    @DisplayName("every qualifying run in one text is masked")
    void everyQualifyingRunIsMasked() {
        String masked = CardNumberMasker.maskEmbeddedCardNumbers(
                "/api/v1/cards/" + CARD_NUMBER + "/related/" + CARD_NUMBER);

        assertThat(masked).doesNotContain(CARD_NUMBER);
        assertThat(masked.chars().filter(character -> character == CardNumberMasker.MASK_CHARACTER)
                .count())
                .isEqualTo(2L * (CARD_NUMBER.length() - CardNumberMasker.VISIBLE_TAIL_LENGTH));
    }

    /**
     * Confirms the two narrower identifiers that legitimately appear in a request target are left
     * alone, and that the same text instance is returned when nothing qualifies.
     *
     * <p>Assumptions: an eleven-digit account identifier and a fifteen-character transaction
     * identifier are both published unmasked by the migrated contracts, so masking either would be a
     * defect rather than caution. Fifteen digits is also the run length one below the threshold, so
     * this doubles as the off-by-one assertion.</p>
     */
    @Test
    @DisplayName("runs shorter than a card number are untouched and the input instance is returned")
    void shorterRunsAreUntouched() {
        String accountPath = "/api/v1/accounts/00000000011";
        String justBelowThreshold = "/api/v1/transactions/" + "9".repeat(15);

        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(accountPath)).isSameAs(accountPath);
        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(justBelowThreshold))
                .isSameAs(justBelowThreshold);
    }

    /**
     * Confirms a run longer than a card number is masked as well, leaving only its final four digits.
     */
    @Test
    @DisplayName("a run longer than a card number is masked to its last four digits")
    void longerRunIsMasked() {
        String overlongRun = "7".repeat(20);

        String masked = CardNumberMasker.maskEmbeddedCardNumbers("/api/v1/cards/" + overlongRun);

        assertThat(masked).isEqualTo("/api/v1/cards/" + "*".repeat(16) + "7777");
    }

    /**
     * Confirms a non-ASCII decimal digit is not treated as part of a card number.
     *
     * <p>Assumptions: the baseline's card number is a fixed-width display field of ASCII digits, so a
     * run of Arabic-Indic digits cannot be one. This asserts the narrowed digit test rather than the
     * Unicode-wide one, because the Unicode-wide test would mask a value that is not a card number.</p>
     */
    @Test
    @DisplayName("a run of non-ASCII decimal digits is not treated as a card number")
    void nonAsciiDigitsAreNotCardNumbers() {
        String arabicIndicRun = "\u0661".repeat(16);

        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(arabicIndicRun)).isSameAs(arabicIndicRun);
    }

    /**
     * Pins the separator boundary: a card number written with separators is not detected as one.
     *
     * <p>Assumptions: this asserts a LIMITATION rather than a desirable behaviour, and it is asserted
     * deliberately so the boundary is a build-enforced fact instead of something a reader has to infer
     * from the scan. Each separated form below is four runs of four digits, no run reaches the
     * sixteen-digit threshold, and the text is returned unchanged. The limitation is harmless for this
     * migration because every value that reaches this class comes from a sixteen-character display
     * field that cannot hold a separator within its declared width, which the class documentation
     * records with the copybook reference.</p>
     *
     * <p>Trade-offs: pinning a limitation costs a failing test if someone later widens the rule, and
     * that cost is the point. Widening it is a behavioural change with a regression risk the class
     * documentation names -- a timestamp is a separated digit sequence this migration renders in full
     * -- so it should be an explicit decision that updates this test, not an unremarked improvement.</p>
     */
    @Test
    @DisplayName("a separated card number is not detected, which is the documented limitation")
    void separatedCardNumberIsNotDetected() {
        String hyphenated = "4111-1111-1111-1111";
        String spaced = "4111 1111 1111 1111";

        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(hyphenated)).isSameAs(hyphenated);
        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(spaced)).isSameAs(spaced);
    }

    /**
     * Confirms the contiguous form this system actually carries is masked, next to the separated form.
     *
     * <p>Assumptions: pairing the two in one test is what makes the limitation readable. The separated
     * value passes through and the contiguous value does not, so the boundary is the presence of a
     * separator and nothing else -- not the digits, not the length in characters, not the position.</p>
     */
    @Test
    @DisplayName("the contiguous form the baseline carries is masked while the separated form is not")
    void contiguousFormIsMaskedWhereSeparatedFormIsNot() {
        String separated = "4111-1111-1111-1111";
        String contiguous = separated.replace("-", "");

        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(separated)).isSameAs(separated);
        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(contiguous))
            .isNotEqualTo(contiguous)
            .endsWith("1111")
            .hasSameSizeAs(contiguous);
    }
}
