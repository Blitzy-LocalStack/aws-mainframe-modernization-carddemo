package com.carddemo.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds the create-user body's declared constraints to the rule its published schema states.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Refactoring Rationale: the review found the record and its published schema disagreeing about
 * whitespace. The record annotates its two names and its identifier {@code @NotBlank}, which refuses a
 * value of spaces; the schema declared {@code minLength: 1} alone, which admits one -- so a caller
 * generating a request from the contract could build a body the contract accepts and this record refuses.
 * The schema was corrected to publish the rule as a pattern, and the contract test in
 * {@code com.carddemo.auth.config.AuthApiContractTest} asserts that correction from the document's side.
 * This class asserts the other side: that the record actually refuses what the document now says it
 * refuses, and refuses it with the reference's own sentence.</p>
 *
 * <p>Assumptions: this record had NO executable evidence of any kind before this class. Its four
 * character components carry eight constraints and five authored sentences between them, and the only
 * test that named the type at all read it reflectively for its annotations. A parity claim asserted
 * from one side only is half a claim, which is why the two classes are written as a pair.</p>
 *
 * <p>Alternatives Considered: driving the refusals through the shared exception advice, as the sign-on
 * body's test does, so that the emitted aggregate sentence is asserted as well. Rejected for this record
 * because the aggregate ordering rule is a property of the advice and of the {@code FieldOrdering}
 * contract, both of which that sibling test already covers against a record that implements it; this
 * record declares no field order, so routing through the advice would assert the advice's fallback rather
 * than anything about this record.</p>
 */
class CreateUserRequestValidationTest {

    /** The sentence the reference reports for an absent given name, at app/cbl/COUSR01C.cbl L120. */
    private static final String FIRST_NAME_REQUIRED = "First Name can NOT be empty...";

    /** The sentence the reference reports for an absent family name, at app/cbl/COUSR01C.cbl L126. */
    private static final String LAST_NAME_REQUIRED = "Last Name can NOT be empty...";

    /** The sentence the reference reports for an absent identifier, at app/cbl/COUSR01C.cbl L132. */
    private static final String USER_ID_REQUIRED = "User ID can NOT be empty...";

    /** The sentence the reference reports for an absent role, at app/cbl/COUSR01C.cbl L144. */
    private static final String USER_TYPE_REQUIRED = "User Type can NOT be empty...";

    /** The authored sentence reported when the role is neither admitted value. */
    private static final String USER_TYPE_DOMAIN = "User Type must be A or U...";

    /** The provider factory, opened once because building one is expensive and it is stateless. */
    private static ValidatorFactory factory;

    /** The provider under test. */
    private static Validator validator;

    /**
     * Opens the validation provider once for the whole class.
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
        factory.close();
    }

    /**
     * Builds a body whose every component conforms, so one component can be varied per case.
     *
     * @param firstName the given name to submit
     * @param lastName the family name to submit
     * @param userId the identifier to submit
     * @param userType the role to submit
     * @return a body carrying those four values, which are the whole declared surface
     */
    private static CreateUserRequest body(String firstName, String lastName, String userId,
            String userType) {

        return new CreateUserRequest(firstName, lastName, userId, userType);
    }

    /**
     * Reads the messages of the violations a body raises.
     *
     * @param request the body to validate; must not be {@code null}
     * @return every violation message, in no defined order
     */
    private static Set<String> messagesFor(CreateUserRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getMessage)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Confirms a conforming body raises no violation at all.
     *
     * <p>Assumptions: asserted first and asserted separately, because every refusal case below is only
     * meaningful if the surrounding values are known to be acceptable. Without this case a refusal could
     * be produced by a component the case was not varying.</p>
     */
    @Test
    @DisplayName("a conforming body raises no violation")
    void aConformingBodyRaisesNoViolation() {
        assertThat(validator.validate(body("Ambrose", "Bierce", "USER0001", "U"))).isEmpty();
    }

    /**
     * Confirms every whitespace-only value of the three free-text components is refused.
     *
     * <p>Assumptions: whitespace rather than the empty string is the case under test, because the empty
     * string was already refused by the schema's minimum length and whitespace was not. Three shapes of
     * whitespace are submitted -- spaces, a tab and a newline, and a single space -- so a constraint that
     * happened to test only for the space character would fail here.</p>
     *
     * @param blank the whitespace-only value to submit in each of the three positions
     */
    @ParameterizedTest(name = "blank = [{0}]")
    @ValueSource(strings = {"   ", "\t\n", " "})
    @DisplayName("a whitespace-only name or identifier is refused with the reference sentence")
    void aWhitespaceOnlyValueIsRefused(String blank) {
        assertThat(messagesFor(body(blank, "Bierce", "USER0001", "U")))
                .containsExactly(FIRST_NAME_REQUIRED);
        assertThat(messagesFor(body("Ambrose", blank, "USER0001", "U")))
                .containsExactly(LAST_NAME_REQUIRED);
        assertThat(messagesFor(body("Ambrose", "Bierce", blank, "U")))
                .containsExactly(USER_ID_REQUIRED);
    }

    /**
     * Confirms a whitespace-only role is refused, and by the presence sentence rather than the domain one.
     *
     * <p>Assumptions: the role carries three constraints at once -- presence, an exact length of one, and
     * a two-value domain -- so a single space satisfies the length and violates the other two. Both
     * sentences are therefore expected, and asserting only one would leave it unclear which constraint had
     * actually fired.</p>
     */
    @Test
    @DisplayName("a whitespace-only role is refused by presence and by domain together")
    void aWhitespaceOnlyRoleIsRefusedTwice() {
        assertThat(messagesFor(body("Ambrose", "Bierce", "USER0001", " ")))
                .containsExactlyInAnyOrder(USER_TYPE_REQUIRED, USER_TYPE_DOMAIN);
    }

    /**
     * Confirms a null value in any of the four character positions is refused.
     *
     * <p>Assumptions: null is asserted alongside whitespace because the two are different failures with
     * the same sentence, and a constraint that covered only one of them would read as covering both. The
     * published schema requires all five properties, so a body omitting one arrives here as null.</p>
     */
    @Test
    @DisplayName("a null value in any character position is refused with that position's sentence")
    void aNullValueIsRefused() {
        assertThat(messagesFor(body(null, "Bierce", "USER0001", "U")))
                .containsExactly(FIRST_NAME_REQUIRED);
        assertThat(messagesFor(body("Ambrose", null, "USER0001", "U")))
                .containsExactly(LAST_NAME_REQUIRED);
        assertThat(messagesFor(body("Ambrose", "Bierce", null, "U")))
                .containsExactly(USER_ID_REQUIRED);
        assertThat(messagesFor(body("Ambrose", "Bierce", "USER0001", null)))
                .containsExactly(USER_TYPE_REQUIRED);
    }

    /**
     * Confirms this body declares no subject reference at all, so none can be sent on it.
     *
     * <p>Refactoring Rationale: this replaces a case asserting that an ABSENT subject reference is
     * refused. That case described a fifth component this record no longer carries: accepting the
     * subject let a caller decide which pool identity a new row authenticates as, so the component was
     * withdrawn and the account is provisioned by
     * {@code com.carddemo.auth.service.CognitoUserProvisioningService}, which reads the subject out of
     * the provider's create response. Asserting the ABSENCE of the component is the stronger claim: a
     * validation case would only have fixed what happens when the value is omitted, whereas this fixes
     * that there is no value to supply. The published contract agrees -- {@code CreateUserRequest} in
     * {@code openapi/auth-api.yaml} sets {@code additionalProperties: false} and requires the same four
     * members -- so a body naming a subject is refused with 400 rather than silently ignored.</p>
     */
    @Test
    @DisplayName("the request body declares no subject reference")
    void theRequestBodyDeclaresNoSubjectReference() {
        assertThat(CreateUserRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("firstName", "lastName", "userId", "userType");
    }

    /**
     * Confirms a value one character past each declared width is refused, and the width itself accepted.
     *
     * <p>Assumptions: the two sides of each boundary are asserted in one case so the pair cannot drift
     * apart. The widths are the copybook's own -- twenty for each name at app/cpy/CSUSR01Y.cpy L19 and
     * L20, eight for the identifier at L18 -- and the sentence names the width rather than repeating a
     * digit, so a change to a constant moves both the constraint and its message together.</p>
     */
    @Test
    @DisplayName("each declared width is accepted and one character past it is refused")
    void eachDeclaredWidthBoundsItsComponent() {
        assertThat(validator.validate(body("A".repeat(20), "B".repeat(20), "C".repeat(8), "A")))
                .isEmpty();

        assertThat(messagesFor(body("A".repeat(21), "Bierce", "USER0001", "U")))
                .containsExactly("First Name must be at most 20 characters...");
        assertThat(messagesFor(body("Ambrose", "B".repeat(21), "USER0001", "U")))
                .containsExactly("Last Name must be at most 20 characters...");
        assertThat(messagesFor(body("Ambrose", "Bierce", "C".repeat(9), "U")))
                .containsExactly("User ID must be at most 8 characters...");
    }

    /**
     * Confirms the role admits exactly the two values the reference declares and nothing else.
     *
     * <p>Assumptions: the lower-case forms are asserted refused as well as the unrelated letter. The
     * reference domain at app/cpy/COCOM01Y.cpy L26 to L28 is two upper-case literals, and a case-insensitive
     * constraint would accept a value the stored column's check constraint then rejects -- moving the
     * refusal from a 400 the caller can act on to a 500 it cannot.</p>
     */
    @Test
    @DisplayName("the role admits A and U only, in upper case")
    void theRoleAdmitsTheTwoDeclaredValuesOnly() {
        assertThat(validator.validate(body("Ambrose", "Bierce", "USER0001", "A"))).isEmpty();
        assertThat(validator.validate(body("Ambrose", "Bierce", "USER0001", "U"))).isEmpty();

        for (String rejected : Set.of("a", "u", "X", "1")) {
            assertThat(messagesFor(body("Ambrose", "Bierce", "USER0001", rejected)))
                    .as("role [%s] must be refused", rejected)
                    .containsExactly(USER_TYPE_DOMAIN);
        }
    }

    /**
     * Confirms a value carrying whitespace around real characters is accepted, not refused.
     *
     * <p>Assumptions: this is the negative half of the whitespace rule and it is the half a careless
     * tightening breaks. The rule is presence of a non-whitespace character, not absence of whitespace, so
     * a family name written with a leading blank must still be accepted -- the reference's own screen
     * fields are blank-padded and it never trims them.</p>
     */
    @Test
    @DisplayName("whitespace around real characters is accepted")
    void whitespaceAroundRealCharactersIsAccepted() {
        assertThat(validator.validate(body(" Ambrose ", " Bierce ", "USER0001", "U"))).isEmpty();
    }

    /**
     * Confirms the diagnostic rendering withholds the two names and the subject reference.
     *
     * <p>Assumptions: asserted here rather than only in a rendering-specific test because the refusal
     * path above is precisely where the exposure arises -- the framework puts the bound target into the
     * message of the exception a failed constraint raises, so a body that fails validation is a body
     * whose rendered form reaches a log. The two components that DO print are asserted present as well,
     * because a rendering that identified no row would correlate nothing.</p>
     */
    @Test
    @DisplayName("the diagnostic rendering withholds the names and the subject reference")
    void theDiagnosticRenderingWithholdsPersonalComponents() {
        String rendered = body("Ambrose", "Bierce", "USER0001", "U").toString();

        assertThat(rendered).doesNotContain("Ambrose")
                .doesNotContain("Bierce")
                .contains("USER0001")
                .contains("userType=U")
                .contains("firstName=REDACTED")
                .contains("lastName=REDACTED");
    }
}
