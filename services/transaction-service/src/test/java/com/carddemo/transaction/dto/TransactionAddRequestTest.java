package com.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies that the rendering of a submission carries no value the migration's logging contract
 * protects, whichever of the two key alternatives the submission supplied.
 *
 * <p>Refactoring Rationale: this class previously asserted that EVERY component of the record was
 * rendered, and named the account identifier, the description, the amount and the four merchant fields
 * among them. It was pinning a disclosure rather than a contract. The sensitive-data logging contract
 * in {@code docs/architecture/observability.md} names account and customer identifiers in a clause of
 * their own and covers persistence-bound values as a class, so seven of the fourteen components were
 * protected the whole time; the assertions on those seven are inverted here. The card-number masking
 * the class was originally written for was correct and is unchanged.</p>
 *
 * <p>Assumptions: the sixteen-digit value is assembled from a repeated digit rather than written as a
 * literal, so that no test fixture in this repository is a card-number-shaped constant a scanner has
 * to triage.</p>
 *
 * <p>⚠️ Assumptions: the class carries THREE subjects and not one -- the rendering, the key pairing rule
 * and the amount's own constraints -- and the amount cases are here rather than only at the HTTP boundary
 * for a reason. What they assert is that a malformed amount produces a CONSTRAINT VIOLATION naming the
 * {@code amount} property at all, which is the property a boundary response's per-field entry is keyed
 * by; a boundary case reads the rendered entry and would pass just as well if the entry were synthesised
 * somewhere other than from a constraint. Both levels are asserted, and this is the level at which the
 * mechanism itself is visible.</p>
 */
class TransactionAddRequestTest {

    /**
     * A card-number-width value assembled rather than written, ending in four distinguishable digits.
     */
    private static final String CARD_NUMBER = "1".repeat(12) + "2345";

    /**
     * The account identifier the fixture submits, which no rendering may carry.
     *
     * <p>Assumptions: it is named as a constant rather than repeated as a literal so that the absence
     * assertions and the fixture cannot drift apart -- an assertion against a value the fixture no
     * longer submits would pass while proving nothing.</p>
     */
    private static final String ACCOUNT_ID = "00000000011";

    /**
     * Confirms the rendering masks the card number and withholds the account identifier entirely.
     *
     * <p>Refactoring Rationale: both halves are asserted in one case because they are one decision, as
     * they were before -- but the decision is the opposite one. What is asserted is no longer an
     * asymmetry between a protected value and a system key; it is that the one identifier a rendering
     * may name is named only through the shared mask, and the other is not named at all. Masking is
     * available for the card number because a mask is what
     * {@code com.carddemo.common.security.CardNumberMasker} exists to produce, and it is not available
     * for the account identifier because nothing owns a masking rule for that value in this context.</p>
     */
    @Test
    @DisplayName("the rendering masks the card number and withholds the account identifier")
    void renderingMasksTheCardNumberAndWithholdsTheAccountIdentifier() {
        String rendered = request(CARD_NUMBER).toString();

        assertThat(rendered)
                .doesNotContain(CARD_NUMBER)
                .contains("maskedCardNumber=" + "*".repeat(12) + "2345")
                .doesNotContain(ACCOUNT_ID)
                .doesNotContain("accountId");
    }

    /**
     * Confirms a submission that supplied no card number renders without a failure.
     *
     * <p>Assumptions: an absent card number is valid input rather than an edge case -- the reference
     * fills whichever key alternative was omitted from the cross-reference -- so the rendering has to
     * survive it. A rendering that raised here would replace a validation diagnostic with an
     * unrelated failure at exactly the moment the diagnostic was wanted.</p>
     *
     * <p>Assumptions: this is also the case in which a rendering has the least left to say, because the
     * one masked component is absent too, so the account identifier's absence is asserted again here
     * rather than only above. An override tempted to fall back to the account identifier when no card
     * number was supplied would satisfy every other case in this class.</p>
     */
    @Test
    @DisplayName("an account-only submission renders without raising and still withholds the account")
    void accountOnlySubmissionRenders() {
        String rendered = request(null).toString();

        assertThat(rendered)
                .contains("maskedCardNumber=null")
                .doesNotContain(ACCOUNT_ID)
                .doesNotContain("accountId");
    }

    /**
     * Confirms the seven surviving components are rendered and the seven protected ones are not.
     *
     * <p>Assumptions: the two halves belong in one case because the risk runs both ways. A component
     * dropped by oversight would silently reduce what a diagnostic says without failing anything, and a
     * protected component added back would silently disclose; asserting only one half would leave the
     * other regression undetectable. Each protected value is asserted absent by its value AND by its
     * member name, because a rendering emitting {@code amount=null} would pass a value-only assertion
     * while announcing that the component is rendered.</p>
     */
    @Test
    @DisplayName("the surviving components are rendered and the protected ones are not")
    void theSurvivingComponentsAreRenderedAndTheProtectedOnesAreNot() {
        String rendered = request(CARD_NUMBER).toString();

        assertThat(rendered)
                .startsWith("TransactionAddRequest[")
                .endsWith("]")
                .contains("typeCode=01")
                .contains("categoryCode=0001")
                .contains("source=POS")
                .contains("originDate=2026-01-15")
                .contains("processDate=2026-01-16")
                .contains("confirmation=Y");

        assertThat(rendered)
                .doesNotContain("GROCERY PURCHASE")
                .doesNotContain("description")
                .doesNotContain("125.50")
                .doesNotContain("amount")
                .doesNotContain("000000000")
                .doesNotContain("CORNER STORE")
                .doesNotContain("SEATTLE")
                .doesNotContain("98101")
                .doesNotContain("merchant");
    }

    /**
     * The validation provider the key-selection cases drive, opened once for the class.
     *
     * <p>Assumptions: the real provider is used rather than the validator class being called directly,
     * because half of what these cases assert is that the class-level annotation is PRESENT on the
     * record at all. Invoking the validator by hand would pass even if the annotation were removed,
     * which is the change most likely to be made by accident.</p>
     */
    private static ValidatorFactory factory;

    /** The validator drawn from {@link #factory}. */
    private static Validator validator;

    /**
     * Opens the validation provider.
     */
    @BeforeAll
    static void openProvider() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /**
     * Closes the validation provider, releasing the expression factory it holds.
     */
    @AfterAll
    static void closeProvider() {
        if (factory != null) {
            factory.close();
        }
    }

    // WHY : Assumptions: these cases exist because the constraint they drive is PARITY with the
    //       reference rather than a narrowing of it, and the narrowing that once stood here is
    //       withdrawn. A D-ADD-KEY-EXCLUSIVE entry in section 7.4 of
    //       docs/architecture/cobol-to-service-traceability.md registered the refusal of a capture
    //       carrying both keys; it is withdrawn there, and these cases now pin the three arms of
    //       app/cbl/COTRN02C.cbl VALIDATE-INPUT-KEY-FIELDS instead - either key alone accepted,
    //       both accepted, neither refused. Assumptions: the register carries no entry for this
    //       behaviour BECAUSE there is no difference to register, so a reader finding no identifier
    //       cited here is seeing agreement rather than an omission.
    /**
     * Confirms that either key alone satisfies the pairing rule.
     */
    @Test
    @DisplayName("either key alone satisfies the pairing rule")
    void eitherKeyAloneSatisfiesThePairingRule() {
        assertThat(keyViolations(keys(ACCOUNT_ID, null)))
                .as("the account arm alone is what app/cbl/COTRN02C.cbl line 196 accepts")
                .isEmpty();
        assertThat(keyViolations(keys(null, CARD_NUMBER)))
                .as("the card arm alone is what line 210 accepts")
                .isEmpty();
    }

    /**
     * Confirms that a submission carrying BOTH keys is accepted, as the reference accepts it.
     *
     * <p>⚠️ Refactoring Rationale: this case previously asserted that both keys together were REFUSED,
     * naming both fields, and pinned that refusal as a deliberate narrowing of the reference. The
     * narrowing is withdrawn and the case now pins the reference. The reference accepts this submission:
     * its construct at line 195 of {@code app/cbl/COTRN02C.cbl} is {@code EVALUATE TRUE}, so the account
     * arm at line 196 fires whatever the card field holds, and line 209 then moves the cross-reference's
     * own card number over the value the operator keyed. Refusing the pair changed an OUTCOME rather than
     * a message -- a submission the screen accepts became a 400 -- and transformation rule T9 admits a
     * behavioural change only as a documented divergence with a stated reason. The reason offered was that
     * the request could not say which key the caller meant; the reference answers that question itself by
     * resolving the account arm first, so the ambiguity was not real.</p>
     *
     * <p>Assumptions: the constraint that remains is that AT LEAST ONE key is supplied, which is the one
     * arm of the three the reference also refuses.</p>
     */
    @Test
    @DisplayName("both keys together are accepted, as the reference accepts them")
    void bothKeysTogetherAreAcceptedAsTheReferenceAcceptsThem() {
        assertThat(keyViolations(keys(ACCOUNT_ID, CARD_NUMBER)))
                .as("app/cbl/COTRN02C.cbl line 196 fires whatever the card field holds")
                .isEmpty();
    }

    /**
     * Confirms that a submission carrying neither key is refused, naming both fields.
     *
     * <p>Assumptions: this is the one arm of the three the reference also refuses, at lines 224 to 229
     * with the sentence at line 226, so the message the violations carry is that sentence verbatim and
     * is asserted as such.</p>
     */
    @Test
    @DisplayName("neither key is refused with the reference's own sentence")
    void neitherKeyIsRefusedWithTheReferenceSentence() {
        Set<ConstraintViolation<TransactionAddRequest>> raised =
                validator.validate(keys(null, null));

        assertThat(keyViolations(keys(null, null)))
                .containsExactlyInAnyOrder("accountId", "cardNumber");
        assertThat(raised)
                .anyMatch(violation ->
                        TransactionAddRequest.KEY_FIELD_REQUIRED.equals(violation.getMessage()))
                .as("line 226's sentence is carried verbatim, per transformation rule T8")
                .isNotEmpty();
    }

    // WHY : Assumptions: these cases exist because a malformed amount used to reach no constraint at
    //       all. The component was declared as the shared money type, so every value the published
    //       TransactionAmount pattern refuses failed inside a DESERIALISER -- before an object existed
    //       to validate -- and the framework answered an unreadable body with an EMPTY per-field array.
    //       A form could not mark the amount input, because no entry named it. The component carries
    //       the submitted characters now, so binding always succeeds and the two constraints below are
    //       what refuse a value.
    /**
     * Every lexical form the published amount pattern excludes raises the format sentence on the field.
     *
     * <p>Assumptions: the specimens are the forms an operator actually keys, and each fails a different
     * alternative of the disjunction at lines 339 to 351 of {@code app/cbl/COTRN02C.cbl} -- whose four
     * alternatives share one action, so all of them answer with the single sentence at line 345. A JSON
     * number cannot be spelled at this level, which receives a bound object rather than a document; the
     * wire-level case for it is in {@code TransactionCaptureWireContractTest}.</p>
     *
     * @param submitted the characters to place in the amount, of type {@code String}
     * @param why the reason the published pattern excludes them, of type {@code String}, carried so a
     *     failure names the form rather than only the value
     */
    @ParameterizedTest(name = "[{0}] refused: {1}")
    @CsvSource({
        "125.5,one fractional digit where the pattern requires exactly two",
        "125.456,three fractional digits where the pattern requires exactly two",
        "+125.50,a leading plus where the pattern admits only a minus",
        "'1,234.50',a grouping separator the pattern does not admit",
        "' 125.50 ',surrounding whitespace the pattern does not admit",
        "1000000000.99,a tenth integer digit the record cannot hold",
        "abc,text carrying no numeric position at all",
        "1e5,an exponent no position in the record's picture admits",
        "-0.005,a third fractional digit the record's scale cannot hold",
    })
    @DisplayName("refuse each lexical form the published amount pattern excludes")
    void everyExcludedAmountFormRaisesTheFormatSentenceOnTheField(String submitted, String why) {
        assertThat(amountViolations(withAmount(submitted)))
                .as("[%s] must be refused: %s", submitted, why)
                .containsExactly(TransactionAddRequest.AMOUNT_FORMAT);
    }

    /**
     * Every form the record holds is admitted, at both signs and at both ends of the domain.
     *
     * <p>Assumptions: the bounds are submitted as well as an ordinary value, because a constraint that
     * admitted nothing at all would satisfy the refusal cases above on its own. Nine integer digits is
     * the record's domain, from {@code TRAN-AMT PIC S9(09)V99} at line 10 of
     * {@code app/cpy/CVTRA05Y.cpy}.</p>
     *
     * @param submitted the characters to place in the amount, of type {@code String}
     */
    @ParameterizedTest(name = "[{0}] admitted")
    @ValueSource(strings = {"0.00", "125.50", "-125.50", "999999999.99", "-999999999.99", "-0.00"})
    @DisplayName("admit every form the record can hold, at both signs and at the domain's edge")
    void everyFormTheRecordHoldsIsAdmitted(String submitted) {
        assertThat(amountViolations(withAmount(submitted)))
                .as("[%s] is within the record's own domain and must be admitted", submitted)
                .isEmpty();
    }

    /**
     * An empty or absent amount draws the presence sentence, and the format sentence is not raised.
     *
     * <p>Assumptions: both spellings are asserted because the reference tests for both at line 278 of
     * {@code app/cbl/COTRN02C.cbl}, {@code TRNAMTI = SPACES OR LOW-VALUES}: an empty string is the
     * first and an absent member is the second. Both are the blank case rather than a malformed one.</p>
     *
     * <p>Assumptions: the assertion is that EXACTLY ONE sentence is raised, not merely that the presence
     * sentence is among them. A shape constraint that also fired on a blank value would put two entries
     * on one input and a form would show two messages for one empty field, which the reference never
     * does -- its presence chain sends the screen before the shape alternatives are reached.</p>
     */
    @Test
    @DisplayName("an empty or absent amount draws the presence sentence alone")
    void anEmptyOrAbsentAmountDrawsThePresenceSentenceAlone() {
        assertThat(amountViolations(withAmount("")))
                .as("an empty field is the SPACES half of line 278")
                .containsExactly(TransactionAddRequest.AMOUNT_REQUIRED);
        assertThat(amountViolations(withAmount("   ")))
                .as("an all-space field is the same half of line 278")
                .containsExactly(TransactionAddRequest.AMOUNT_REQUIRED);
        assertThat(amountViolations(withAmount(null)))
                .as("an absent member is the LOW-VALUES half of line 278")
                .containsExactly(TransactionAddRequest.AMOUNT_REQUIRED);
    }

    /**
     * The parsed accessor answers the submitted value, and answers nothing for the blank spellings.
     *
     * <p>Assumptions: the accessor is read here rather than only through the service, because it is the
     * single place the submitted characters become the shared money type and every consumer downstream
     * depends on it. Its scale is asserted as well as its value, since the record's picture fixes two
     * fractional positions and a consumer comparing scales would see the difference.</p>
     */
    @Test
    @DisplayName("the parsed accessor answers the submitted amount and nothing for the blank spellings")
    void theParsedAccessorAnswersTheSubmittedAmount() {
        assertThat(withAmount("-125.50").amountValue().toPlainString())
                .as("the characters submitted, read through the shared money type")
                .isEqualTo("-125.50");
        assertThat(withAmount("125.50").amountValue().amount().scale())
                .as("two fractional positions, as TRAN-AMT PIC S9(09)V99 declares")
                .isEqualTo(2);
        assertThat(withAmount("").amountValue())
                .as("a blank amount is not a value to parse, so nothing is answered")
                .isNull();
        assertThat(withAmount(null).amountValue())
                .as("nor is an absent one")
                .isNull();
    }

    /**
     * Builds a submission whose only variable is the amount, valid in every other respect.
     *
     * @param amount the characters to place in the amount, or {@code null} to omit the member
     * @return a submission valid in every other respect, never {@code null}
     */
    private static TransactionAddRequest withAmount(String amount) {
        return new TransactionAddRequest(ACCOUNT_ID, "01", "0001", "POS", "GROCERY PURCHASE",
                amount, "000000000", "CORNER STORE", "SEATTLE", "98101", null,
                "2026-01-15", "2026-01-16", "Y", null);
    }

    /**
     * Returns the sentences the amount's own constraints raised, ignoring every other component.
     *
     * <p>Assumptions: filtered by PROPERTY PATH rather than by message, which is the opposite of the
     * pairing rule's helper below and is deliberate: what these cases assert is which member a client is
     * told to correct, so the property is the subject and the message is the value under test.</p>
     *
     * @param request the submission to validate; must not be {@code null}
     * @return the sentences raised against the {@code amount} property, never {@code null}
     */
    private static List<String> amountViolations(TransactionAddRequest request) {
        return validator.validate(request).stream()
                .filter(violation -> "amount".equals(violation.getPropertyPath().toString()))
                .map(ConstraintViolation::getMessage)
                .toList();
    }

    /**
     * Returns the property names the pairing rule raised a violation against.
     *
     * <p>Assumptions: the set is filtered by the pairing rule's own message rather than taken whole,
     * because a submission with no card number also breaks nothing else while one with both breaks
     * nothing else either -- but a future component constraint could, and this helper must keep
     * reporting the pairing rule alone.</p>
     *
     * @param request the submission to validate; must not be {@code null}
     * @return the property names the pairing rule named, never {@code null}
     */
    private static List<String> keyViolations(TransactionAddRequest request) {
        return validator.validate(request).stream()
                .filter(violation ->
                        TransactionAddRequest.KEY_FIELD_REQUIRED.equals(violation.getMessage()))
                .map(violation -> violation.getPropertyPath().toString())
                .toList();
    }

    /**
     * Builds a submission whose only variables are the two key alternatives.
     *
     * @param accountId the account identifier to submit, or {@code null} to omit it
     * @param cardNumber the card number to submit, or {@code null} to omit it
     * @return a submission valid in every other respect, never {@code null}
     */
    private static TransactionAddRequest keys(String accountId, String cardNumber) {
        // WHY : Assumptions: the binding token is omitted, because these cases are about the two KEY
        //       alternatives and the token is not one of them. A submission carrying none is the
        //       single-turn arm the contract admits, so omitting it keeps every case here on its subject.
        return new TransactionAddRequest(accountId, "01", "0001", "POS", "GROCERY PURCHASE",
                "125.50", "000000000", "CORNER STORE", "SEATTLE", "98101", cardNumber,
                "2026-01-15", "2026-01-16", "Y", null);
    }

    /**
     * Builds a submission whose only variable is the card number.
     *
     * @param cardNumber the card number to place on the request, or {@code null} for an account-only
     *     submission
     * @return a populated request, valid in shape, carrying {@code cardNumber}
     */
    private static TransactionAddRequest request(String cardNumber) {
        return new TransactionAddRequest(ACCOUNT_ID, "01", "0001", "POS", "GROCERY PURCHASE",
                "125.50", "000000000", "CORNER STORE", "SEATTLE", "98101", cardNumber,
                "2026-01-15", "2026-01-16", "Y", null);
    }
}
