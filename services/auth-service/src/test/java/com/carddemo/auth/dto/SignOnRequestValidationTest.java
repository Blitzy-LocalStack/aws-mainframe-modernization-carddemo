package com.carddemo.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * Verifies that a refused sign-on body reports the reference's own sentences, in the reference's order.
 *
 * <h2>The parity property under test</h2>
 *
 * <p>Assumptions: the reference gate is a single {@code EVALUATE TRUE} at
 * {@code app/cbl/COSGN00C.cbl} lines 117 to 130 whose first matching branch sends the screen and stops.
 * Two consequences are observable and both are asserted: the sentence displayed is the EARLIEST failing
 * field's, and it is the program's literal rather than a framework's paraphrase -- line 120 writes
 * {@code 'Please enter User ID ...'} and line 125 writes {@code 'Please enter Password ...'}.</p>
 *
 * <p>Refactoring Rationale: the assertions drive the whole path rather than reading the annotations,
 * because the defect being closed lived in the JOIN between two layers. A real provider produces the
 * violations, they are handed to the shared advice exactly as an argument resolver hands them over, and
 * the emitted record is read. Reading the annotation attribute would have passed against the defective
 * code too, since the literals were only ever missing from the response, not from anyone's intent.</p>
 *
 * <p>Assumptions: the credential values used below are whitespace or a bounded repetition, never a
 * plausible secret, so nothing here is a credential-shaped constant a scanner would have to triage.</p>
 */
class SignOnRequestValidationTest {

    /** A fixed clock so an emitted timestamp is reproducible rather than wall-clock dependent. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC);

    /** The sentence the reference displays for an absent identifier, at COSGN00C line 120. */
    private static final String USER_ID_REQUIRED = "Please enter User ID ...";

    /** The sentence the reference displays for an absent credential, at COSGN00C line 125. */
    private static final String PASSWORD_REQUIRED = "Please enter Password ...";

    /** The request path a container would report for the sign-on operation. */
    private static final String SIGNON_PATH = "/api/v1/auth/signon";

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
     * Confirms both presence constraints report the reference literal rather than a provider default.
     *
     * <p>Assumptions: the values submitted are whitespace rather than empty, because whitespace is the
     * case a schema declaring only a minimum length admits and the constraint refuses. Asserting the
     * empty case alone would leave the interesting one untested.</p>
     */
    @Test
    @DisplayName("both presence refusals carry the reference literal verbatim")
    void presenceRefusalsCarryTheReferenceLiterals() {
        Set<ConstraintViolation<SignOnRequest>> violations =
                validator.validate(new SignOnRequest("   ", "        "));

        assertThat(violations).hasSize(2);
        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .containsExactlyInAnyOrder(USER_ID_REQUIRED, PASSWORD_REQUIRED);
    }

    /**
     * Confirms the aggregate message is the identifier's sentence when both components are blank.
     *
     * <p>Refactoring Rationale: this is the assertion the defect would have failed. The provider reports
     * its violations in an order it does not define, so the entries are deliberately handed to the advice
     * in the WRONG order -- credential first -- to prove the ordering comes from the record's declared
     * order and not from the sequence they arrived in.</p>
     */
    @Test
    @DisplayName("an empty screen is answered with the identifier's sentence, not the credential's")
    void emptyScreenIsAnsweredWithTheIdentifierSentence() {
        ApiError body = refuse(new SignOnRequest("   ", "        "));

        assertThat(body.message()).isEqualTo(USER_ID_REQUIRED);
        assertThat(body.fieldErrors()).extracting(ApiError.FieldError::field)
                .containsExactly("userId", "password");
        assertThat(body.fieldErrors()).extracting(ApiError.FieldError::message)
                .containsExactly(USER_ID_REQUIRED, PASSWORD_REQUIRED);
    }

    /**
     * Confirms a blank credential alone is answered with the credential's sentence.
     *
     * <p>Assumptions: this is the reference's second branch, reachable only once the identifier passed,
     * and it is asserted separately so that a rule which always reported the identifier's sentence would
     * fail. The pair of tests together pin the ordering rather than a constant.</p>
     */
    @Test
    @DisplayName("a blank credential alone is answered with the credential's sentence")
    void blankCredentialAloneIsAnsweredWithItsOwnSentence() {
        ApiError body = refuse(new SignOnRequest("USER0001", "   "));

        assertThat(body.message()).isEqualTo(PASSWORD_REQUIRED);
        assertThat(body.fieldErrors()).extracting(ApiError.FieldError::field)
                .containsExactly("password");
    }

    /**
     * Confirms an over-length identifier is refused with an authored sentence, not a provider default.
     *
     * <p>Assumptions: this condition has no reference counterpart -- the screen field
     * {@code USERIDI PIC X(8)} cannot hold a ninth character -- so the sentence is authored for the
     * target. It is asserted anyway, because the provider's default for a size constraint states a lower
     * bound of zero that the presence constraint beside it contradicts, and a caller reading both would
     * be told an empty value is acceptable in the same response that refuses one.</p>
     */
    @Test
    @DisplayName("an over-length identifier is refused with an authored sentence")
    void overLengthIdentifierIsRefusedWithAnAuthoredSentence() {
        ApiError body = refuse(new SignOnRequest("A".repeat(9), "P".repeat(14)));

        assertThat(body.fieldErrors()).extracting(ApiError.FieldError::field)
                .containsExactly("userId");
        assertThat(body.message())
                .doesNotContain("size must be between")
                .contains("8 characters");
    }

    /**
     * Confirms a conforming body produces no violation at all, so the constraints refuse nothing valid.
     *
     * <p>Assumptions: the credential is fourteen characters, the identity provider's configured minimum,
     * because a shorter one would pass here and be refused by the provider and a test using one would
     * assert a value this system cannot actually sign on with.</p>
     */
    @Test
    @DisplayName("a conforming body produces no violation")
    void conformingBodyProducesNoViolation() {
        assertThat(validator.validate(new SignOnRequest("USER0001", "P".repeat(14)))).isEmpty();
    }

    /**
     * Confirms the record's declared order is the reference's, read from the record rather than inferred.
     */
    @Test
    @DisplayName("the declared field order is identifier then credential")
    void declaredFieldOrderIsIdentifierThenCredential() {
        assertThat(new SignOnRequest("USER0001", "P".repeat(14)).fieldOrder())
                .containsExactly("userId", "password");
    }

    /**
     * Validates a body and renders the refusal through the shared advice, as an argument resolver would.
     *
     * <p>Assumptions: the violations are sorted into REVERSE declared order before being handed over, so
     * that a passing assertion cannot be explained by the provider having produced them in the right
     * order already. A provider does not guarantee any order, so relying on the one it happens to
     * produce would make these tests pass or fail for reasons unrelated to the code under test.</p>
     *
     * @param request the body to validate and refuse; must violate at least one constraint
     * @return the problem record the shared advice emitted, never {@code null}
     */
    private static ApiError refuse(SignOnRequest request) {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(request, "signOnRequest");
        List<String> declared = request.fieldOrder();

        validator.validate(request).stream()
                .sorted(Comparator.comparingInt(
                        (ConstraintViolation<SignOnRequest> violation) ->
                                -declared.indexOf(violation.getPropertyPath().toString()))
                        .thenComparing(violation -> violation.getPropertyPath().toString()))
                .forEach(violation -> binding.addError(new FieldError("signOnRequest",
                        violation.getPropertyPath().toString(), null, false, null, null,
                        violation.getMessage())));

        ApiError body = new GlobalExceptionHandler(CLOCK)
                .onInvalidBody(new MethodArgumentNotValidException(null, binding),
                        requestFor(SIGNON_PATH))
                .getBody();
        assertThat(body).isNotNull();
        return body;
    }

    /**
     * Builds a request reporting the given URI, with no query string.
     *
     * @param uri the request URI a container would report, never {@code null}
     * @return a request whose {@code getRequestURI} returns exactly {@code uri}
     */
    private static MockHttpServletRequest requestFor(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }
}
