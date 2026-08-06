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
     * Confirms a card number written with any accepted separator is masked, with the separators kept.
     *
     * <p>Refactoring Rationale: an earlier revision of this test asserted the OPPOSITE -- that a
     * separated card number passed through unchanged -- and described that as a documented limitation
     * whose cost was bounded because every value reaching the masker came from a sixteen-character
     * display field too narrow to hold a separator. That bound does not hold. The masker's second
     * operation exists for text a caller assembled, and both of its real callers admit separators: an
     * error body echoes the path a caller invented, and {@code com.carddemo.common.web.CorrelationIdFilter}
     * publishes an identity that may contain a hyphen, a dot or an underscore. So the pass-through was
     * a disclosure path, not a bounded limitation, and the assertion is inverted rather than kept.</p>
     *
     * <p>Assumptions: masking preserves the separators and the character width, so the rendering still
     * reads as the value it stands for; only digits are replaced. All four accepted separators are
     * asserted rather than one, because the rule admits a set and a rule that admits a set is only
     * pinned by exercising the set.</p>
     */
    @Test
    @DisplayName("a separated card number is masked and its separators are preserved")
    void separatedCardNumberIsMasked() {
        assertThat(CardNumberMasker.maskEmbeddedCardNumbers("4111-1111-1111-1111"))
                .isEqualTo("****-****-****-1111");
        assertThat(CardNumberMasker.maskEmbeddedCardNumbers("4111 1111 1111 1111"))
                .isEqualTo("**** **** **** 1111");
        assertThat(CardNumberMasker.maskEmbeddedCardNumbers("4111.1111.1111.1111"))
                .isEqualTo("****.****.****.1111");
        assertThat(CardNumberMasker.maskEmbeddedCardNumbers("4111_1111_1111_1111"))
                .isEqualTo("****_****_****_1111");
    }

    /**
     * Confirms the contiguous and the separated form of one number are both masked to the same tail.
     *
     * <p>Assumptions: pairing the two in one test is what makes the rule readable. Neither form
     * survives, both reveal the same final four digits, and each keeps its own character width -- so
     * the presence of a separator changes the rendering's punctuation and nothing about its safety.</p>
     */
    @Test
    @DisplayName("the contiguous and separated forms of one number are both masked")
    void bothContiguousAndSeparatedFormsAreMasked() {
        String separated = "4111-1111-1111-1111";
        String contiguous = separated.replace("-", "");

        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(separated))
                .isNotEqualTo(separated)
                .endsWith("1111")
                .hasSameSizeAs(separated);
        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(contiguous))
                .isNotEqualTo(contiguous)
                .endsWith("1111")
                .hasSameSizeAs(contiguous);
    }

    /**
     * Confirms a separated candidate embedded in a request route is masked without disturbing the route.
     *
     * <p>Assumptions: this is the site the widened rule was added for. A caller that types a separated
     * card number into a path gets that path echoed in the refusal body and written to the accompanying
     * log line, so the run has to be masked in place while the route segments around it survive.</p>
     */
    @Test
    @DisplayName("a separated candidate inside a route is masked and the route is preserved")
    void separatedRunInPathIsMasked() {
        assertThat(CardNumberMasker.maskEmbeddedCardNumbers("/api/v1/cards/4111-1111-1111-1111/detail"))
                .isEqualTo("/api/v1/cards/****-****-****-1111/detail");
    }

    /**
     * Confirms the separated rule does not fire on the shapes the migration renders in full.
     *
     * <p>Assumptions: the regression risk the widened rule carries is a false positive on a separated
     * digit sequence that is not a card number, and the migration has one such sequence everywhere --
     * the twenty-six-character processing timestamp
     * {@code 'YYYY-MM-DD HH:MM:SS.mmmmmm'} that {@code com.carddemo.common.time.TimestampFormatter}
     * emits. Its groups are four, two, two, two, two, two and six characters, so no group after the
     * first is four digits long and the candidate ends at the first mismatch. An ISO date and a
     * dotted version string are asserted alongside because both appear in log lines this masker sees.</p>
     *
     * <p>Trade-offs: the rule measures group widths rather than validating a card number, so a
     * separated run of four four-digit groups that is not a card number is masked anyway -- the same
     * positional trade-off the contiguous rule already accepts, and it fails toward withholding.</p>
     */
    @Test
    @DisplayName("timestamps, dates and version strings are not treated as separated card numbers")
    void separatedNonCardShapesAreUntouched() {
        String processingTimestamp = "2022-07-18 09:30:00.123456";
        String isoDate = "2022-07-18";
        String versionString = "1.11.21";

        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(processingTimestamp))
                .isSameAs(processingTimestamp);
        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(isoDate)).isSameAs(isoDate);
        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(versionString)).isSameAs(versionString);
    }

    /**
     * Confirms a candidate whose separators are not uniform is refused, as is a group of the wrong width.
     *
     * <p>Assumptions: uniformity is what keeps the rule narrow, so it is asserted directly. A value
     * that mixes a hyphen and a space is not a written card number in any convention, and a leading
     * group of five digits is not one either. Both are returned unchanged, and the second is the
     * off-by-one assertion for the group-width test.</p>
     */
    @Test
    @DisplayName("mixed separators and mis-sized groups are refused by the separated rule")
    void nonUniformSeparatedCandidatesAreRefused() {
        String mixedSeparators = "4111-1111 1111-1111";
        String oversizedFirstGroup = "41111-1111-1111-1111";

        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(mixedSeparators)).isSameAs(mixedSeparators);
        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(oversizedFirstGroup))
                .isSameAs(oversizedFirstGroup);
    }
}
