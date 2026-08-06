package com.carddemo.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.validation.FieldValidationFlag.FieldError;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the three-state validation flag: its reference character codes, the alternate spellings the
 * baseline also uses, the blank-only screen marker, and the conversion into the structured field errors
 * a response body carries.
 *
 * <p>Assumptions: the flag has three states and not two, and the distinction between an invalid value
 * and an absent one is the whole reason for the third. The baseline renders an extra marker for the
 * absent case only, so a design collapsing the two would lose a user-visible behaviour. Every test here
 * asserts the three states separately for that reason.</p>
 *
 * <p>Assumptions: the character codes are asserted as literals as well as through the published
 * constants, because these characters are what a reference flag area actually contains -- a low value, a
 * digit zero and a letter B -- and a constant that drifted from them would decode a real record's flags
 * into the wrong states while still returning valid-looking values.</p>
 */
class FieldValidationFlagTest {

    /**
     * Confirms the enumeration carries exactly the three reference states.
     */
    @Test
    @DisplayName("carries exactly the three reference states")
    void carriesExactlyThreeStates() {
        assertThat(FieldValidationFlag.values())
            .containsExactly(
                FieldValidationFlag.VALID,
                FieldValidationFlag.NOT_OK,
                FieldValidationFlag.BLANK);
    }

    /**
     * Confirms each state's published code is the character the reference flag area contains.
     */
    @Test
    @DisplayName("each state's code is the reference character")
    void eachStateCarriesTheReferenceCharacter() {
        assertThat(FieldValidationFlag.VALID.code()).isEqualTo('\u0000');
        assertThat(FieldValidationFlag.NOT_OK.code()).isEqualTo('0');
        assertThat(FieldValidationFlag.BLANK.code()).isEqualTo('B');

        assertThat(FieldValidationFlag.VALID_CODE).isEqualTo('\u0000');
        assertThat(FieldValidationFlag.NOT_OK_CODE).isEqualTo('0');
        assertThat(FieldValidationFlag.BLANK_CODE).isEqualTo('B');
    }

    /**
     * Confirms the valid state is the only one that reports as valid, and both others as errors.
     *
     * <p>Assumptions: the two predicates are asserted together for every state because they must be
     * exact complements. A state that reported neither, or both, would let a caller branch on one
     * predicate and get a different answer than a caller branching on the other.</p>
     *
     * @param flag the state under test
     * @param expectedValid whether the state reports as valid
     */
    @ParameterizedTest
    @CsvSource({"VALID, true", "NOT_OK, false", "BLANK, false"})
    @DisplayName("the valid predicate and the error predicate are exact complements")
    void predicatesAreExactComplements(FieldValidationFlag flag, boolean expectedValid) {
        assertThat(flag.isValid()).isEqualTo(expectedValid);
        assertThat(flag.isError()).isEqualTo(!expectedValid);
    }

    /**
     * Confirms only the absent state carries the extra screen marker.
     *
     * <p>Assumptions: the reference moves a literal asterisk into a field that was never supplied, in
     * addition to colouring it, and does not do so for a field that was supplied but invalid. That
     * asymmetry is a user-visible behaviour, so both directions are asserted.</p>
     */
    @Test
    @DisplayName("only the absent state carries the extra screen marker")
    void onlyTheAbsentStateCarriesTheMarker() {
        assertThat(FieldValidationFlag.BLANK.requiresBlankMarker()).isTrue();
        assertThat(FieldValidationFlag.BLANK.screenMarker())
            .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER)
            .isEqualTo("*");

        assertThat(FieldValidationFlag.NOT_OK.requiresBlankMarker()).isFalse();
        assertThat(FieldValidationFlag.NOT_OK.screenMarker())
            .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER)
            .isEmpty();
        assertThat(FieldValidationFlag.VALID.requiresBlankMarker()).isFalse();
        assertThat(FieldValidationFlag.VALID.screenMarker()).isEmpty();
    }

    /**
     * Confirms each reference code decodes back to the state that publishes it.
     *
     * <p>Assumptions: the decode is what reads a real flag area, so it is the direction that must accept
     * every spelling the baseline writes. The round trip through {@code code()} is asserted so the two
     * directions cannot drift from each other.</p>
     *
     * @param flag the state under test
     */
    @ParameterizedTest
    @CsvSource({"VALID", "NOT_OK", "BLANK"})
    @DisplayName("each state's own code decodes back to that state")
    void codeRoundTripsThroughDecode(FieldValidationFlag flag) {
        assertThat(FieldValidationFlag.fromCode(flag.code())).isEqualTo(flag);
    }

    /**
     * Confirms the alternate spellings the baseline also writes decode to the right states.
     *
     * <p>Assumptions: the reference does not use one character per state consistently -- a digit one and
     * a space appear as alternate valid and blank markers in different programs. Accepting both is what
     * lets one decoder read every program's flag area, and refusing them would fail on real data while
     * passing every test written against the primary spellings alone.</p>
     */
    @Test
    @DisplayName("the alternate reference spellings decode to the same states")
    void alternateSpellingsDecodeToTheSameStates() {
        assertThat(FieldValidationFlag.ALTERNATE_VALID_CODE).isEqualTo('1');
        assertThat(FieldValidationFlag.ALTERNATE_BLANK_CODE).isEqualTo(' ');

        assertThat(FieldValidationFlag.fromCode(FieldValidationFlag.ALTERNATE_VALID_CODE))
            .isEqualTo(FieldValidationFlag.VALID);
        assertThat(FieldValidationFlag.fromCode(FieldValidationFlag.ALTERNATE_BLANK_CODE))
            .isEqualTo(FieldValidationFlag.BLANK);
    }

    /**
     * Confirms an unrecognised code is refused rather than defaulted to a state.
     *
     * <p>Assumptions: defaulting would be the dangerous direction in either sense -- defaulting to valid
     * would let a corrupt flag area report a clean record, and defaulting to an error would report a
     * failure the data does not contain. Refusing names the offending character instead.</p>
     *
     * @param unrecognised a character no reference program writes into a flag area
     */
    @ParameterizedTest
    @ValueSource(chars = {'X', '2', '9', 'b', '\t', '\u00ff'})
    @DisplayName("an unrecognised code is refused rather than defaulted")
    void unrecognisedCodeIsRefused(char unrecognised) {
        assertThatThrownBy(() -> FieldValidationFlag.fromCode(unrecognised))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Confirms the absent-input test recognises every spelling of absence the baseline uses.
     *
     * <p>Assumptions: this is the predicate the error model's message latch now reuses, so its domain
     * decides whether a blank aggregate message reads as the message-off state. All three reference
     * markers are asserted -- a low value, spaces and the empty string -- because a predicate that
     * covered only null would leave the latch with the defect it was fixed for.</p>
     *
     * @param neverSupplied a value standing for input the user never gave
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "        ", "\u0000", "\u0000\u0000"})
    @DisplayName("every reference spelling of absence reads as never supplied")
    void everySpellingOfAbsenceReadsAsNeverSupplied(String neverSupplied) {
        assertThat(FieldValidationFlag.isNeverSupplied(neverSupplied)).isTrue();
    }

    /**
     * Confirms a null value and a value carrying content are each answered correctly.
     */
    @Test
    @DisplayName("null reads as never supplied and content does not")
    void nullReadsAsNeverSuppliedAndContentDoesNot() {
        assertThat(FieldValidationFlag.isNeverSupplied(null)).isTrue();
        assertThat(FieldValidationFlag.isNeverSupplied("0")).isFalse();
        assertThat(FieldValidationFlag.isNeverSupplied(" x ")).isFalse();
        assertThat(FieldValidationFlag.isNeverSupplied("\u0000x")).isFalse();
    }

    /**
     * Confirms the published absent-input markers are the reference characters.
     */
    @Test
    @DisplayName("the published absent-input markers are the reference characters")
    void publishedAbsentMarkersAreTheReferenceCharacters() {
        assertThat(FieldValidationFlag.ABSENT_INPUT_LOW_VALUE).isEqualTo('\u0000');
        assertThat(FieldValidationFlag.ABSENT_INPUT_SPACE).isEqualTo(' ');
    }

    /**
     * Confirms only an error state converts into a field error a response body would carry.
     *
     * <p>Assumptions: returning an empty optional for the valid state is what lets a caller map every
     * field uniformly and collect the result, without writing the is-it-an-error test at each call site.
     * The two error states must both produce one, since a client renders an absent field and an invalid
     * field the same way.</p>
     */
    @Test
    @DisplayName("only an error state converts into a field error")
    void onlyAnErrorStateConvertsIntoAFieldError() {
        assertThat(FieldValidationFlag.VALID.toFieldError("acctId", "message"))
            .isEqualTo(Optional.empty());

        Optional<FieldError> notOk = FieldValidationFlag.NOT_OK.toFieldError("acctId", "bad value");
        Optional<FieldError> blank = FieldValidationFlag.BLANK.toFieldError("acctId", "required");

        assertThat(notOk).isPresent();
        assertThat(blank).isPresent();
        assertThat(notOk.get().field()).isEqualTo("acctId");
        assertThat(notOk.get().state()).isEqualTo(FieldValidationFlag.NOT_OK);
        assertThat(notOk.get().message()).isEqualTo("bad value");
        assertThat(blank.get().state()).isEqualTo(FieldValidationFlag.BLANK);
    }

    /**
     * Confirms a converted field error carries the state's own screen marker.
     */
    @Test
    @DisplayName("a converted field error carries the state's screen marker")
    void convertedFieldErrorCarriesTheScreenMarker() {
        FieldError blank = FieldValidationFlag.BLANK.toFieldError("acctId", "required").orElseThrow();
        FieldError notOk = FieldValidationFlag.NOT_OK.toFieldError("acctId", "invalid").orElseThrow();

        assertThat(blank.screenMarker()).isEqualTo("*");
        assertThat(notOk.screenMarker()).isEmpty();
    }

    /**
     * Confirms collecting errors returns one entry per error field and skips the valid ones.
     *
     * <p>Assumptions: the collection is what builds the array a response body carries, so it must omit
     * the valid fields entirely rather than including them with an empty message. A client renders one
     * highlight per entry, so an entry for a valid field would highlight a field the user got right.</p>
     */
    @Test
    @DisplayName("collecting errors returns one entry per error field and omits the valid ones")
    void collectingErrorsOmitsTheValidFields() {
        Map<String, FieldValidationFlag> flags = new java.util.LinkedHashMap<>();
        flags.put("acctId", FieldValidationFlag.VALID);
        flags.put("cardNum", FieldValidationFlag.NOT_OK);
        flags.put("expiryDate", FieldValidationFlag.BLANK);

        Map<String, String> messages = Map.of(
            "acctId", "ignored",
            "cardNum", "card number is invalid",
            "expiryDate", "expiry date must be supplied");

        List<FieldError> collected = FieldValidationFlag.collectErrors(flags, messages::get);

        assertThat(collected).hasSize(2);
        assertThat(collected).extracting(FieldError::field)
            .containsExactly("cardNum", "expiryDate");
        assertThat(collected).noneSatisfy(
            error -> assertThat(error.field()).isEqualTo("acctId"));
    }

    /**
     * Confirms collecting from an all-valid map returns an empty list rather than a null.
     *
     * <p>Assumptions: an empty list is what a response body serialises as an empty array, which a client
     * reads as no field errors. Returning a null would make the absence of errors indistinguishable from
     * a serialisation the client failed to parse.</p>
     */
    @Test
    @DisplayName("collecting from an all-valid map returns an empty list")
    void collectingFromAnAllValidMapReturnsEmpty() {
        List<FieldError> collected = FieldValidationFlag.collectErrors(
            Map.of("acctId", FieldValidationFlag.VALID, "cardNum", FieldValidationFlag.VALID),
            field -> "unreachable, because no field is in error");

        assertThat(collected).isNotNull().isEmpty();
    }

    /**
     * Confirms the collected list is immutable, so a caller cannot alter a reported result.
     */
    @Test
    @DisplayName("the collected list is immutable")
    void collectedListIsImmutable() {
        List<FieldError> collected = FieldValidationFlag.collectErrors(
            Map.of("cardNum", FieldValidationFlag.NOT_OK), field -> "invalid");

        assertThatThrownBy(collected::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Confirms a field error refuses a null field name or a null state at construction.
     *
     * <p>Assumptions: a field error with no field name cannot be rendered against anything, so admitting
     * one would produce an entry a client silently drops -- reporting a validation failure the user never
     * sees.</p>
     */
    @Test
    @DisplayName("a field error refuses a null field name or a null state")
    void fieldErrorRefusesNullFieldNameOrState() {
        assertThatThrownBy(() -> new FieldError(null, FieldValidationFlag.NOT_OK, "message"))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new FieldError("acctId", null, "message"))
            .isInstanceOf(RuntimeException.class);
    }
}
