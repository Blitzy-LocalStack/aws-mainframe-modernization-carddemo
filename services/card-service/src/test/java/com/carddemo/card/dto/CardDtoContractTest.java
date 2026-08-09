package com.carddemo.card.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.security.SealedSelector;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds the three card wire shapes to the two security properties their own documentation claims for
 * them, and to the component set the published contract declares.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Refactoring Rationale: a review found that these records permitted, rather than prevented, three
 * things their documentation said they prevented. The masked-rendering members were bounded by LENGTH
 * alone, and a raw sixteen-digit account number is itself exactly sixteen characters long, so an
 * unmasked number satisfied every one of them and reached responses no administrative authority is
 * required for. The generated {@code toString()} printed every component, so a cardholder's name, their
 * account number and their card's expiry reached any log line that stringified an instance. And the
 * update request carried a caller-controlled expiry DAY on a field the baseline renders non-display and
 * never validates. Each of the three was invisible to the build because each crossed the wire as a
 * string, so each now has an assertion here.</p>
 *
 * <p>Assumptions: these are unit assertions against the types themselves and not against a running
 * application, because this module has no application class yet. That is the weaker form and it is the
 * right one for these three properties: two of them are properties of a CONSTRUCTOR and one is a
 * property of a component LIST, so none of them needs a request to be exercised.</p>
 */
class CardDtoContractTest {

    /**
     * A specimen sixteen-digit primary account number, used as the value every masked member must refuse.
     *
     * <p>Assumptions: this is a test-local literal and not a credential. It is the reserved test prefix
     * followed by a fixed tail, so it identifies no real card.</p>
     */
    private static final String RAW_CARD_NUMBER = "4111111111110011";

    /** The masked rendering the shared masker produces for {@link #RAW_CARD_NUMBER}. */
    private static final String MASKED_CARD_NUMBER = "************0011";

    /** An eleven-digit account identifier of the width the copybook declares. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The embossed name a diagnostic rendering must not disclose. */
    private static final String EMBOSSED_NAME = "JOHN Q PUBLIC";

    /** The stored expiry date a diagnostic rendering must not disclose. */
    private static final String EXPIRATION_DATE = "2026-12-31";

    /** A selector of the published length and alphabet, synthetic and sealing nothing. */
    private static final String SELECTOR = "fake-selector-example-not-a-real-sealed-value-0000000000000";

    /**
     * Asserts that both response shapes refuse an unmasked card number at construction, which is the
     * only boundary a response actually crosses.
     */
    @Test
    @DisplayName("a response shape refuses an unmasked card number at construction")
    void aResponseShapeRefusesAnUnmaskedCardNumber() {
        assertThatThrownBy(() -> new CardSummary(SELECTOR, RAW_CARD_NUMBER, ACCOUNT_ID, "Y"))
                .as("bean validation is not evaluated on a response, so the constructor is the only"
                        + " place an unmasked number can be stopped")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CardDetail(SELECTOR, RAW_CARD_NUMBER, ACCOUNT_ID, EMBOSSED_NAME,
                        EXPIRATION_DATE, "Y", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Asserts that the refusal does not quote the value it refused, since that value is by hypothesis an
     * unmasked account number and an exception message reaches a log.
     */
    @Test
    @DisplayName("the refusal does not echo the unmasked number it refused")
    void theRefusalDoesNotEchoTheNumber() {
        assertThatThrownBy(() -> new CardSummary(SELECTOR, RAW_CARD_NUMBER, ACCOUNT_ID, "Y"))
                .satisfies(refusal -> assertThat(refusal.getMessage())
                        .as("a refusal that quoted the number would write into a log exactly the value"
                                + " the check exists to keep out of one")
                        .doesNotContain(RAW_CARD_NUMBER));
    }

    /**
     * Asserts that a properly masked rendering is accepted, so the constraint refuses the wrong value
     * without also refusing the right one.
     */
    @Test
    @DisplayName("a masked rendering is accepted by both response shapes")
    void aMaskedRenderingIsAccepted() {
        assertThat(new CardSummary(SELECTOR, MASKED_CARD_NUMBER, ACCOUNT_ID, "Y").displayCardNumber())
                .isEqualTo(MASKED_CARD_NUMBER);
        assertThat(new CardDetail(SELECTOR, MASKED_CARD_NUMBER, ACCOUNT_ID, EMBOSSED_NAME,
                        EXPIRATION_DATE, "Y", 0).displayCardNumber())
                .isEqualTo(MASKED_CARD_NUMBER);
    }

    /**
     * Asserts that a value which is neither the full number nor a full-width masked rendering is refused
     * too, so the check is a positive test of the masked form rather than a denial of one known value.
     *
     * @param offered a rendering a mapper might produce by mistake
     */
    @ParameterizedTest
    @ValueSource(strings = {"0011", "****0011", "****************", "************001",
        "************00110", "############0011", "************001a"})
    @DisplayName("a rendering that is not the full-width masked form is refused")
    void aRenderingThatIsNotTheMaskedFormIsRefused(String offered) {
        assertThatThrownBy(() -> new CardSummary(SELECTOR, offered, ACCOUNT_ID, "Y"))
                .as("the check must recognise the masked form rather than merely reject one known"
                        + " unmasked value, or a differently wrong rendering would pass")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Asserts that the diagnostic rendering of every card shape discloses no personal value.
     */
    @Test
    @DisplayName("no diagnostic rendering discloses a name, an account number or an expiry")
    void noDiagnosticRenderingDisclosesAPersonalValue() {
        String summary = new CardSummary(SELECTOR, MASKED_CARD_NUMBER, ACCOUNT_ID, "Y").toString();
        String detail = new CardDetail(SELECTOR, MASKED_CARD_NUMBER, ACCOUNT_ID, EMBOSSED_NAME,
                EXPIRATION_DATE, "Y", 3).toString();
        String request = new CardUpdateRequest(EMBOSSED_NAME, "Y", "12", "2026", 3).toString();

        for (String rendering : List.of(summary, detail, request)) {
            assertThat(rendering)
                    .as("a record's generated rendering prints every component, so each of these has to"
                            + " be overridden rather than trusted")
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(EMBOSSED_NAME)
                    .doesNotContain(RAW_CARD_NUMBER);
        }
        assertThat(detail).doesNotContain(EXPIRATION_DATE);
        assertThat(request).doesNotContain("2026").doesNotContain("12,");

        // WHY : Assumptions: the selector is asserted PRESENT rather than absent. It discloses nothing
        //       without the deployment key, and it is the one value that lets a reader correlate a log
        //       line with a request -- a rendering that withheld it as well would be safe and useless.
        assertThat(summary).contains(SELECTOR);
        assertThat(detail).contains(SELECTOR);
    }

    /**
     * Asserts that the update request carries no expiry-day member, so no caller can influence the day
     * the stored date keeps.
     */
    @Test
    @DisplayName("the update request carries no expiry-day member")
    void theUpdateRequestCarriesNoExpiryDay() {
        List<String> components = Arrays.stream(CardUpdateRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(components)
                .as("the baseline renders its day input non-display, redisplays it from the pre-edit"
                        + " snapshot and never validates it, so an editable day is a capability it does"
                        + " not have")
                .containsExactly("embossedName", "activeStatus", "expirationMonth", "expirationYear",
                        "version")
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("day"));
    }

    /**
     * Asserts that the two response shapes declare the selector first and declare no member able to
     * carry the card number in full.
     */
    @Test
    @DisplayName("a response shape declares the selector and no full card number")
    void aResponseShapeDeclaresTheSelectorAndNoFullNumber() {
        assertThat(Arrays.stream(CardSummary.class.getRecordComponents())
                        .map(RecordComponent::getName).toList())
                .containsExactly("key", "displayCardNumber", "accountId", "activeStatus");
        assertThat(Arrays.stream(CardDetail.class.getRecordComponents())
                        .map(RecordComponent::getName).toList())
                .containsExactly("key", "displayCardNumber", "accountId", "embossedName",
                        "expirationDate", "activeStatus", "version")
                .as("a member named for the card number itself would be a member able to hold it in"
                        + " full, which is the exclusion these shapes exist to hold")
                .doesNotContain("cardNumber");
    }

    /**
     * Asserts that a raw card number cannot be presented as a selector, and that the refusal is the
     * CONSTRUCTOR's rather than the validator's.
     *
     * <p>Refactoring Rationale: the refusal is asserted at construction, and an earlier revision
     * asserted it through bean validation alone -- building the shape with a raw number and expecting a
     * constraint violation to be reported. That stopped being reachable when the record acquired a
     * compact-constructor guard: the guard raises before an instance exists, so no instance can be
     * offered to a validator. Asserting the guard is the stronger claim in any case, because bean
     * validation runs only where a validator is wired and the guard holds for every instance including
     * the ones a test builds directly.</p>
     *
     * <p>Assumptions: the property under test is the LENGTH and not the alphabet. A run of digits IS
     * valid URL-safe base64, so the exact width is the only bound that separates a selector from the
     * value it stands for, and the well-formed selector is asserted acceptable in the same case so the
     * refusal cannot pass by refusing everything.</p>
     */
    @Test
    @DisplayName("a raw card number is refused as a selector at construction")
    void aSelectorMemberRefusesARawCardNumber() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(
                            new CardSummary(SELECTOR, MASKED_CARD_NUMBER, ACCOUNT_ID, "Y")))
                    .as("a selector of the published length and alphabet must satisfy the bounds")
                    .isEmpty();
        }

        assertThatThrownBy(() ->
                        new CardSummary(RAW_CARD_NUMBER, MASKED_CARD_NUMBER, ACCOUNT_ID, "Y"))
                .as("a run of digits IS valid URL-safe base64, so the exact length is the only bound"
                        + " that separates a selector from the value it stands for")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    /**
     * Asserts that the selector length the wire shapes are bounded to is the length the sealer actually
     * produces for a card number, so the literal in the constraint cannot drift from the arithmetic.
     */
    @Test
    @DisplayName("the selector bound matches the length the sealer produces")
    void theSelectorBoundMatchesTheSealerArithmetic() {
        assertThat(SELECTOR).hasSize(SealedSelector.sealedLengthFor(RAW_CARD_NUMBER.length()));
    }

    /**
     * Asserts that the update request bounds each attribute's WIDTH and leaves its DOMAIN to the service.
     *
     * <p>Refactoring Rationale: this case previously asserted the opposite -- that the record itself
     * refused month 13, the unpadded month 1, and the years 1949 and 2100. Those domains are the
     * reference's, taken from {@code 88 VALID-MONTH VALUES 1 THRU 12} at {@code app/cbl/COCRDUPC.cbl:95}
     * and {@code 88 VALID-YEAR VALUES 1950 THRU 2099} at {@code :99}, and they are still enforced and still
     * asserted -- by {@code CardUpdateService}, whose gates also classify a blank field separately from an
     * unacceptable one and report all four attributes together. A declarative constraint runs first, so
     * while the record refused these values the service's classification was unreachable for every caller
     * arriving over HTTP. The record therefore admits them now, and this case pins that so a reinstated
     * constraint fails here rather than quietly taking the parity away again.</p>
     *
     * <p>Assumptions: what the record still refuses is asserted alongside, because "leaves the domain to
     * the service" must not be read as "validates nothing". A value one character past its declared width
     * is still refused here: the reference field physically could not hold it, so the reference has no
     * sentence for it and the service has none either, which makes the declarative refusal the only one
     * available.</p>
     */
    @Test
    @DisplayName("the update request bounds each attribute's width and leaves its domain to the service")
    void theUpdateRequestBoundsWidthAndLeavesTheDomainToTheService() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new CardUpdateRequest(EMBOSSED_NAME, "Y", "12", "2026", 0)))
                    .isEmpty();
            assertThat(validator.validate(new CardUpdateRequest(EMBOSSED_NAME, "Y", "13", "2026", 0)))
                    .as("month 13 must reach the service, which refuses it with the reference sentence")
                    .isEmpty();
            assertThat(validator.validate(new CardUpdateRequest(EMBOSSED_NAME, "Y", "1", "2026", 0)))
                    .as("the unpadded month must reach the service, which classifies it not-ok")
                    .isEmpty();
            assertThat(validator.validate(new CardUpdateRequest(EMBOSSED_NAME, "Y", "12", "1949", 0)))
                    .as("1949 must reach the service, which holds the 1950 to 2099 window")
                    .isEmpty();
            assertThat(validator.validate(new CardUpdateRequest(EMBOSSED_NAME, "Y", "12", "2100", 0)))
                    .as("and so must 2100")
                    .isEmpty();
            assertThat(validator.validate(new CardUpdateRequest(EMBOSSED_NAME, "Y", "123", "2026", 0)))
                    .as("a month one character past its declared width is refused here, because no"
                            + " reference sentence covers an over-long value")
                    .isNotEmpty();
        }
    }
}
