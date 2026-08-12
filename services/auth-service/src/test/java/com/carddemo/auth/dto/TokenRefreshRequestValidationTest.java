package com.carddemo.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.api.InstanceOfAssertFactories;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Verifies the bound on the renewal token: that it exists, that it is inclusive, and that refusing a
 * value never reports the value.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Refactoring Rationale: the review found this token bounded on neither side -- no {@code @Size} on
 * the record and no {@code maxLength} in the committed contract -- with both documents justifying the
 * absence by naming a transport request-size limit that did not exist. This operation is the one in the
 * service reachable with no credential, so an unbounded token was an unbounded body from an
 * unauthenticated caller. These cases drive the real validation provider and the shared advice rather
 * than reading the annotation, because the sibling case in
 * {@code com.carddemo.auth.config.AuthApiContractTest} already pins the annotation against the document;
 * what is asserted here is the observable outcome a caller receives.</p>
 *
 * <p>Assumptions: the values submitted below are bounded repetitions of one character, never a plausible
 * token, so nothing in this file is a credential-shaped constant a scanner would have to triage -- and no
 * assertion reproduces a submitted value.</p>
 */
class TokenRefreshRequestValidationTest {

    /** A fixed clock so an emitted timestamp is reproducible rather than wall-clock dependent. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC);

    /** The request path a container would report for the renewal operation. */
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";

    /**
     * The bound the record declares, restated here rather than read from it.
     *
     * <p>Assumptions: the constant is private on the record, and reaching it reflectively would make
     * these cases assert that the record equals itself. Restating the number means a change to the bound
     * fails this class until the change is deliberate, which is the property a pinned bound should
     * have.</p>
     */
    private static final int DECLARED_BOUND = 8192;

    /** An identifier of the declared width, so no case is refused for the wrong component. */
    private static final String USER_ID = "USER0001";

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
     * Confirms a token of exactly the bound is accepted and one character more is refused.
     *
     * <p>Assumptions: the boundary is asserted from both sides in one case, because a bound asserted only
     * from the refusing side would pass for an implementation that refused the acceptable width too --
     * and that failure locks out a caller holding a valid token, which is the outcome the generous bound
     * was chosen to make unreachable.</p>
     */
    @Test
    @DisplayName("a token of exactly the bound is accepted and one character more is refused")
    void theTokenBoundIsInclusive() {
        assertThat(validator.validate(
                new TokenRefreshRequest(USER_ID, "t".repeat(DECLARED_BOUND))))
                .as("a token of exactly the declared bound must renew")
                .isEmpty();

        Set<ConstraintViolation<TokenRefreshRequest>> refused = validator.validate(
                new TokenRefreshRequest(USER_ID, "t".repeat(DECLARED_BOUND + 1)));
        assertThat(refused).hasSize(1);
        assertThat(refused).first()
                .extracting(violation -> violation.getPropertyPath().toString())
                .isEqualTo("refreshToken");
    }

    /**
     * Confirms the over-length refusal reports the authored sentence rather than a provider default.
     *
     * <p>Assumptions: the provider's own default for a size constraint states a lower bound of zero,
     * which the presence constraint beside it contradicts -- a caller reading both would be told an empty
     * value is acceptable in the same response that refuses one. The authored sentence is asserted for
     * that reason, and the default's wording is asserted absent.</p>
     */
    @Test
    @DisplayName("the over-length refusal names the bound in an authored sentence")
    void theOverLengthRefusalNamesTheBound() {
        ApiError body = refuse(new TokenRefreshRequest(USER_ID, "t".repeat(DECLARED_BOUND + 1)));

        assertThat(body.fieldErrors()).extracting(ApiError.FieldError::field)
                .containsExactly("refreshToken");

        // WHY : Assumptions: the sentence is read from the FIELD entry rather than from the aggregate
        //       message. The shared advice latches an aggregate only for a body whose record declares a
        //       reporting order, and this record deliberately declares none -- the reasoning is recorded
        //       on the record itself -- so its aggregate is the generic correct-the-fields sentence and
        //       the authored one reaches the caller as the entry's own help text.
        assertThat(body.fieldErrors()).first()
                .extracting(ApiError.FieldError::message, InstanceOfAssertFactories.STRING)
                .doesNotContain("size must be between")
                .contains(String.valueOf(DECLARED_BOUND))
                .contains("characters");
    }

    /**
     * Confirms the refusal echoes no fragment of the token it refused.
     *
     * <p>Assumptions: this is the security property, and it is asserted as an absence because an absence
     * only stays absent if something checks it. The token is credential material that mints access
     * tokens, so a refusal quoting even a prefix of it would place that material in a response body and,
     * through the same record, in a log line.</p>
     */
    @Test
    @DisplayName("the refusal echoes no fragment of the token it refused")
    void theRefusalWithholdsTheToken() {
        String submitted = "t".repeat(DECLARED_BOUND + 1);
        ApiError body = refuse(new TokenRefreshRequest(USER_ID, submitted));

        assertThat(body.message()).doesNotContain(submitted.substring(0, 32));
        assertThat(body.fieldErrors()).extracting(ApiError.FieldError::message)
                .noneMatch(sentence -> sentence.contains(submitted.substring(0, 32)));
    }

    /**
     * Confirms an absent token still reports the presence sentence rather than the length one.
     *
     * <p>Assumptions: the two conditions have the same remedy -- sign on again -- but different
     * sentences, and this case exists because the presence sentence deliberately says nothing about the
     * token's shape. A refusal that reported the length sentence for an absent value would describe a
     * bound the caller had not reached.</p>
     */
    @Test
    @DisplayName("an absent token reports the presence sentence, not the length sentence")
    void anAbsentTokenReportsThePresenceSentence() {
        ApiError body = refuse(new TokenRefreshRequest(USER_ID, "   "));

        assertThat(body.fieldErrors()).extracting(ApiError.FieldError::field)
                .containsExactly("refreshToken");
        assertThat(body.fieldErrors()).first()
                .extracting(ApiError.FieldError::message, InstanceOfAssertFactories.STRING)
                .doesNotContain(String.valueOf(DECLARED_BOUND))
                .isEqualTo("Please sign on again to renew your session ...");
    }

    /**
     * Confirms a conforming renewal produces no violation at all.
     */
    @Test
    @DisplayName("a conforming renewal produces no violation")
    void aConformingRenewalProducesNoViolation() {
        assertThat(validator.validate(new TokenRefreshRequest(USER_ID, "t".repeat(1200)))).isEmpty();
    }

    /**
     * Validates a body and renders the refusal through the shared advice, as an argument resolver would.
     *
     * @param request the body to validate and refuse; must violate at least one constraint
     * @return the problem record the shared advice emitted, never {@code null}
     */
    private static ApiError refuse(TokenRefreshRequest request) {
        BeanPropertyBindingResult binding =
                new BeanPropertyBindingResult(request, "tokenRefreshRequest");

        validator.validate(request).forEach(violation ->
                binding.addError(new FieldError("tokenRefreshRequest",
                        violation.getPropertyPath().toString(), null, false, null, null,
                        violation.getMessage())));

        ApiError body = new GlobalExceptionHandler(CLOCK)
                .onInvalidBody(new MethodArgumentNotValidException(null, binding),
                        requestFor(REFRESH_PATH))
                .getBody();
        assertThat(body).as("the advice must emit a body for a refused renewal").isNotNull();
        return body;
    }

    /**
     * Builds the servlet request the advice reads the refused path from.
     *
     * @param path the request path; must not be {@code null}
     * @return a POST request for that path, never {@code null}
     */
    private static MockHttpServletRequest requestFor(String path) {
        return new MockHttpServletRequest("POST", path);
    }
}
