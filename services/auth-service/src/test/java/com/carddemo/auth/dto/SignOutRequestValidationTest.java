package com.carddemo.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * Verifies the bound on the revoked token, that refusing it never reports it, and that its two
 * declarations agree.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Purpose: sign-out is one of the four operations this service publishes without a token, so its body
 * arrives from an unauthenticated caller and its single component is the longest-lived credential in the
 * system. Both properties are asserted here on the observable outcome -- the problem record a caller
 * actually receives -- rather than on the annotations, because the sibling case in
 * {@code com.carddemo.auth.config.AuthApiContractTest} already pins the annotations against the committed
 * contract and would pass for a record whose provider refused nothing.</p>
 *
 * <p>Purpose: this class additionally pins the ONE property neither record can assert alone. The same
 * value is bounded twice -- once on {@link TokenRefreshRequest} for the renewal and once on
 * {@link SignOutRequest} for the revocation -- because the renewal's constant is private to it and cannot
 * be referenced. Two independent declarations of one figure drift, and the drift is asymmetric in a way a
 * caller would experience as a trap: a token accepted for renewal but refused for revocation leaves a
 * session that can be extended and cannot be ended. The final case compares them.</p>
 *
 * <p>Assumptions: the values submitted below are bounded repetitions of one character, never a plausible
 * token, so nothing in this file is a credential-shaped constant a scanner would have to triage -- and no
 * assertion reproduces a submitted value.</p>
 */
class SignOutRequestValidationTest {

    /** A fixed clock so an emitted timestamp is reproducible rather than wall-clock dependent. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC);

    /** The request path a container would report for the revocation operation. */
    private static final String SIGNOUT_PATH = "/api/v1/auth/signout";

    /**
     * The bound the record declares, restated here rather than read from it.
     *
     * <p>Assumptions: the figure is restated for the reason the sibling renewal case restates it -- a case
     * that read the record's own constant would assert the record equals itself, whereas a restated number
     * fails this class until a change to the bound is deliberate. The agreement between the record's
     * constant and this literal is what the last case checks, so the restatement is verified rather than
     * merely duplicated.</p>
     */
    private static final int DECLARED_BOUND = 8192;

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
     * from the refusing side would pass for an implementation that refused the acceptable width too -- and
     * that failure locks a caller out of ENDING a session, which is the one outcome this operation exists
     * to guarantee.</p>
     */
    @Test
    @DisplayName("a token of exactly the bound is accepted and one character more is refused")
    void theTokenBoundIsInclusive() {
        assertThat(validator.validate(new SignOutRequest("t".repeat(DECLARED_BOUND))))
                .as("a token of exactly the declared bound must be revocable")
                .isEmpty();

        Set<ConstraintViolation<SignOutRequest>> refused =
                validator.validate(new SignOutRequest("t".repeat(DECLARED_BOUND + 1)));
        assertThat(refused).hasSize(1);
        assertThat(refused).first()
                .extracting(violation -> violation.getPropertyPath().toString())
                .isEqualTo("refreshToken");
    }

    /**
     * Confirms the over-length refusal reports the authored sentence rather than a provider default.
     *
     * <p>Assumptions: the provider's own default for a size constraint states a lower bound of zero, which
     * the presence constraint beside it contradicts -- a caller reading both would be told an empty value
     * is acceptable in the same response that refuses one. The authored sentence is asserted for that
     * reason, and the default's wording is asserted absent.</p>
     */
    @Test
    @DisplayName("the over-length refusal names the bound in an authored sentence")
    void theOverLengthRefusalNamesTheBound() {
        ApiError body = refuse(new SignOutRequest("t".repeat(DECLARED_BOUND + 1)));

        assertThat(body.fieldErrors()).extracting(ApiError.FieldError::field)
                .containsExactly("refreshToken");
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
     * only stays absent if something checks it. The token mints access tokens for thirty days, so a
     * refusal quoting even a prefix of it would place that material in a response body and, through the
     * same record, in a log line.</p>
     */
    @Test
    @DisplayName("the refusal echoes no fragment of the token it refused")
    void theRefusalWithholdsTheToken() {
        String submitted = "t".repeat(DECLARED_BOUND + 1);
        ApiError body = refuse(new SignOutRequest(submitted));

        assertThat(body.message()).doesNotContain(submitted.substring(0, 32));
        assertThat(body.fieldErrors()).extracting(ApiError.FieldError::message)
                .noneMatch(sentence -> sentence.contains(submitted.substring(0, 32)));
    }

    /**
     * Confirms the record's own rendering withholds the token, which no response body can undo.
     *
     * <p>Assumptions: the override is asserted here as well as on the response path, because the two
     * exposures have different causes. A response body is emitted deliberately and is guarded by the case
     * above; a rendering escapes through paths nobody writes on purpose -- an interpolated request object
     * in a log line, an assertion message, a framework trace of a failed request -- and only the type's
     * own override closes those.</p>
     */
    @Test
    @DisplayName("the record's rendering withholds the token it carries")
    void theRenderingWithholdsTheToken() {
        String submitted = "t".repeat(64);

        assertThat(new SignOutRequest(submitted).toString())
                .doesNotContain(submitted)
                .contains("SignOutRequest")
                .contains("<withheld>");
    }

    /**
     * Confirms an absent token reports the presence sentence rather than the length one.
     *
     * <p>Assumptions: a blank submission is used rather than a null one, because the two conditions reach
     * the same constraint and blank is the one a client produces by sending an empty field. The presence
     * sentence deliberately says nothing about the token's shape, so a refusal that reported the length
     * sentence here would describe a bound the caller never reached.</p>
     */
    @Test
    @DisplayName("an absent token reports the presence sentence, not the length sentence")
    void anAbsentTokenReportsThePresenceSentence() {
        ApiError body = refuse(new SignOutRequest("   "));

        assertThat(body.fieldErrors()).extracting(ApiError.FieldError::field)
                .containsExactly("refreshToken");
        assertThat(body.fieldErrors()).first()
                .extracting(ApiError.FieldError::message, InstanceOfAssertFactories.STRING)
                .doesNotContain(String.valueOf(DECLARED_BOUND))
                .isEqualTo("Please sign on again to renew your session ...");
    }

    /**
     * Confirms a conforming revocation produces no violation at all.
     */
    @Test
    @DisplayName("a conforming revocation produces no violation")
    void aConformingRevocationProducesNoViolation() {
        assertThat(validator.validate(new SignOutRequest("t".repeat(1200)))).isEmpty();
    }

    /**
     * Confirms the two records bound the same value at the same figure.
     *
     * <p>Purpose: the renewal and the revocation carry the same refresh token and each declares its own
     * maximum, because the renewal's constant is private to that record and cannot be referenced from
     * this one. Two declarations of one figure drift, and the consequence is not symmetric: a bound
     * lowered on the revocation alone would leave a token that renews a session but cannot end it, which
     * is a session an operator cannot close. This case is what makes the duplication safe.</p>
     *
     * <p>Alternatives Considered: publishing the renewal's constant so the revocation could reference it,
     * which would remove the duplication entirely and make this case unnecessary. Rejected because
     * widening a member's visibility to satisfy a test makes the record's surface answerable to the test,
     * and the record's own documentation argues the bound as ITS decision. Reading the private field
     * reflectively keeps the visibility as designed and still fails if the two figures part.</p>
     *
     * @throws ReflectiveOperationException if the renewal record's bound cannot be read, which would mean
     *     the constant was renamed or removed and this agreement can no longer be checked at all
     */
    @Test
    @DisplayName("the renewal and the revocation bound the same token at the same figure")
    void bothRecordsBoundTheTokenIdentically() throws ReflectiveOperationException {
        Field renewalBound = TokenRefreshRequest.class.getDeclaredField("REFRESH_TOKEN_MAX_LENGTH");
        renewalBound.setAccessible(true);

        assertThat(renewalBound.getInt(null))
                .as("a token accepted for renewal and refused for revocation is a session that can be"
                        + " extended and cannot be ended")
                .isEqualTo(SignOutRequest.REFRESH_TOKEN_MAX_LENGTH)
                .isEqualTo(DECLARED_BOUND);
    }

    /**
     * Validates a body and renders the refusal through the shared advice, as an argument resolver would.
     *
     * @param request the body to validate and refuse; must violate at least one constraint
     * @return the problem record the shared advice emitted, never {@code null}
     */
    private static ApiError refuse(SignOutRequest request) {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(request, "signOutRequest");

        validator.validate(request).forEach(violation ->
                binding.addError(new FieldError("signOutRequest",
                        violation.getPropertyPath().toString(), null, false, null, null,
                        violation.getMessage())));

        ApiError body = new GlobalExceptionHandler(CLOCK)
                .onInvalidBody(new MethodArgumentNotValidException(null, binding),
                        new MockHttpServletRequest("POST", SIGNOUT_PATH))
                .getBody();
        assertThat(body).as("the advice must emit a body for a refused revocation").isNotNull();
        return body;
    }
}
