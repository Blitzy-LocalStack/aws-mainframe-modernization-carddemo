package com.carddemo.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the one acceptance rule for a masked primary account number.
 *
 * <p>The subject is the rule three response contracts previously approximated three different ways. Two
 * card contracts declared a maximum width alone, which a full sixteen-digit primary account number
 * satisfies exactly; a pending-authorization detail view checked the length and the first character only,
 * which {@code *234567890123456} satisfies while disclosing fifteen digits. The cases below assert both
 * of those specific values are refused, because they are the values that reached a published response
 * body under the weaker rules.</p>
 *
 * <p>Assumptions: the pattern constant and the imperative guard are asserted to AGREE on every value
 * exercised. They are the two ways this rule is applied -- a contract that declares constraints as
 * annotations reads the constant, a contract that guards its own constructor calls the guard -- and a rule
 * applied two ways is only one rule while the two answers match.</p>
 *
 * <p>Assumptions: the round trip through {@link CardNumberMasker} is asserted, so the production rule and
 * the acceptance rule are shown to be compatible. A masker that produced a rendering this class refused
 * would fail every response rather than catch a fault, and nothing else in the build would notice until a
 * response was serialised.</p>
 */
class MaskedCardNumberTest {

    /**
     * A card number in the form the masker is given one.
     */
    private static final String CARD_NUMBER = "4111111111110011";

    /**
     * The rendering the masker produces for {@link #CARD_NUMBER}.
     */
    private static final String MASKED = "************0011";

    /**
     * The value the weaker length-and-first-character rule admitted.
     *
     * <p>Assumptions: sixteen characters beginning with the mask character, and fifteen digits of a card
     * number after it. This is the value that motivated the shared rule, so it is named as a constant
     * rather than written inline in one case.</p>
     */
    private static final String ONE_MASK_CHARACTER_ONLY = "*234567890123456";

    /**
     * The masked form is accepted, and both ways of applying the rule agree that it is.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the masker's own output is accepted by both the pattern and the guard")
    void theMaskersOutputIsAccepted() {
        assertThat(CardNumberMasker.mask(CARD_NUMBER))
                .as("the production rule and the acceptance rule agree on the same rendering")
                .isEqualTo(MASKED)
                .matches(MaskedCardNumber.DOMAIN);
        assertThat(MaskedCardNumber.isMasked(MASKED)).isTrue();
        MaskedCardNumber.require("displayCardNumber", MASKED);
    }

    /**
     * A full card number is refused, which is the value a maximum-width rule admitted.
     *
     * <p>Assumptions: the refusal message is asserted NOT to contain the candidate. A value reaching this
     * branch may be a primary account number, so quoting it would write the value into the log line that
     * reports its refusal -- the one place the refusal must not put it. The length is asserted present
     * instead, that being the one property of the candidate it is safe to name.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a full card number is refused, and the refusal does not quote it")
    void aFullCardNumberIsRefused() {
        assertThat(MaskedCardNumber.isMasked(CARD_NUMBER)).isFalse();
        assertThatThrownBy(() -> MaskedCardNumber.require("displayCardNumber", CARD_NUMBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayCardNumber")
                .hasMessageContaining("16")
                .hasMessageNotContaining(CARD_NUMBER);
    }

    /**
     * A width-correct value is refused with the position of its first offending character.
     *
     * <p>Assumptions: the probe keeps the declared width so that the LENGTH cannot be what refuses it,
     * which is what makes the position meaningful. A disclosed digit is substituted rather than a symbol,
     * because a disclosed digit is the value that actually leaks and a rule that located a letter but not a
     * digit would pass this case while admitting every real card number.</p>
     *
     * <p>Assumptions: the position is asserted present and the candidate asserted absent in the same
     * message, which pins both halves of the obligation -- locating the fault, and not reproducing the
     * value while doing so.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a width-correct value names the position of the first offending character")
    void aWidthCorrectValueNamesTheOffendingPosition() {
        String disclosedAtFifth = MASKED.substring(0, 4) + '7' + MASKED.substring(5);

        assertThat(disclosedAtFifth).hasSameSizeAs(MASKED);
        assertThatThrownBy(() -> MaskedCardNumber.require("cardNum", disclosedAtFifth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cardNum")
                .hasMessageContaining("position 5")
                .hasMessageNotContaining(disclosedAtFifth);
    }

    /**
     * A non-digit in the visible tail is located at its own position, not at the head of the tail.
     *
     * <p>Assumptions: the tail is probed as well as the masked run because the two halves of the shape are
     * enforced by different terms of the expression, and a position derived from the mask term alone would
     * report the first tail character for every tail fault. The last position is probed specifically
     * because an off-by-one in the scan shows up there and nowhere else.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a non-digit in the visible tail is located at its own position")
    void aVisibleTailFaultIsLocatedAtItsOwnPosition() {
        String letterAtLastPosition = MASKED.substring(0, MASKED.length() - 1) + 'X';

        assertThat(letterAtLastPosition).hasSameSizeAs(MASKED);
        assertThatThrownBy(() -> MaskedCardNumber.require("cardNum", letterAtLastPosition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("position " + MASKED.length());
    }

    /**
     * A value of the wrong width is refused on its width, with no position named.
     *
     * <p>Assumptions: naming a position here would name the WRONG fault. On a value one character short
     * every position from the shortfall onward differs from what the rule wants, so the first difference is
     * an artefact of the length rather than the defect to fix -- which is why the position clause is
     * withheld when the width is wrong, and why that withholding is asserted rather than assumed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a value of the wrong width is refused on its width and names no position")
    void aWrongWidthValueNamesNoPosition() {
        String oneShort = MASKED.substring(1);

        assertThat(oneShort).hasSize(MASKED.length() - 1);
        assertThatThrownBy(() -> MaskedCardNumber.require("cardNum", oneShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(MASKED.length() - 1))
                .hasMessageNotContaining("position");
    }

    /**
     * A partially masked value is refused, which is the value a first-character rule admitted.
     *
     * <p>Assumptions: this and the previous case are the two documented weaker rules, asserted separately
     * because they failed for different reasons -- one on the mask being absent entirely, the other on it
     * being one character long.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("one mask character followed by digits is refused")
    void aPartiallyMaskedValueIsRefused() {
        assertThat(MaskedCardNumber.isMasked(ONE_MASK_CHARACTER_ONLY)).isFalse();
        assertThat(ONE_MASK_CHARACTER_ONLY).doesNotMatch(MaskedCardNumber.DOMAIN);
        assertThatThrownBy(
                () -> MaskedCardNumber.require("cardNum", ONE_MASK_CHARACTER_ONLY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cardNum")
                .hasMessageNotContaining(ONE_MASK_CHARACTER_ONLY);
    }

    /**
     * Every other near-miss shape is refused, and the two applications agree on each one.
     *
     * <p>Assumptions: the six shapes are the ways a value can be close to masked and not be. Too short
     * and too long each break the exact length a truncated number would otherwise be mistaken for; a
     * non-digit tail breaks the part that identifies the card; a mask character inside the tail and a
     * digit inside the prefix each break the boundary between the two runs; and an empty value breaks
     * both. Enumerating them is what keeps a later change to the expression from loosening it in a way no
     * single positive case would catch.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every near-miss shape is refused by both the pattern and the guard")
    void everyNearMissIsRefused() {
        for (String candidate : new String[] {
            "",
            "***********0011",
            "*************0011",
            "************001A",
            "************00*1",
            "***********10011",
        }) {
            assertThat(MaskedCardNumber.isMasked(candidate))
                    .as("the guard refuses %s", candidate)
                    .isFalse();
            assertThat(candidate)
                    .as("the pattern refuses %s, agreeing with the guard", candidate)
                    .doesNotMatch(MaskedCardNumber.DOMAIN);
        }
    }

    /**
     * An absent value is refused by the guard and reported unmasked by the predicate.
     *
     * <p>Assumptions: the two answers differ deliberately and the difference is asserted. The guard raises
     * the absence, because requiredness and shape are both its business at a constructor boundary; the
     * predicate reports {@code false} rather than {@code true}, because a predicate answering {@code true}
     * for an absent value would read as though absence satisfied the masking rule.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an absent value is refused by the guard and is not reported as masked")
    void anAbsentValueIsRefused() {
        assertThat(MaskedCardNumber.isMasked(null)).isFalse();
        assertThatThrownBy(() -> MaskedCardNumber.require("cardNum", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cardNum");
    }

    /**
     * The derived figures follow the masker's own constants rather than repeating them.
     *
     * <p>Assumptions: this asserts the DERIVATION and not the numbers, which is the property that keeps a
     * change to the visible tail from leaving the prefix length behind. The width itself is asserted
     * against the copybook figure of sixteen, which three artifacts already agree on: the copybook line,
     * the stored column and the published contract.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the prefix length is derived from the width and the visible tail")
    void theDerivedFiguresFollowTheMasker() {
        assertThat(MaskedCardNumber.MASKED_LENGTH).isEqualTo(16);
        assertThat(MaskedCardNumber.MASK_PREFIX_LENGTH)
                .isEqualTo(MaskedCardNumber.MASKED_LENGTH - CardNumberMasker.VISIBLE_TAIL_LENGTH);
        assertThat(MaskedCardNumber.DOMAIN)
                .as("the mask character is escaped, or the expression would match values it must refuse")
                .contains("\\" + CardNumberMasker.MASK_CHARACTER);
    }
}
