package com.carddemo.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.common.validation.FieldValidationFlag;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the response shape's message-off latch and the two published contracts it is held to.
 *
 * <p>Assumptions: this class exists because the shape had no dedicated suite of its own and was
 * covered only through the advice that emits it. Two of its properties cannot be reached that way at
 * all. The message-off latch is a pure function of the shape and is exercised by the shared address
 * validator rather than by a handler, and the aggregate message band is a property of every message
 * the system can emit rather than of any one response, so both are asserted here against the shape
 * itself.</p>
 *
 * <p>Assumptions: every timestamp is read from a fixed clock rather than the wall clock, so a
 * comparison of two shapes built in the same test is a comparison of their content. The shape reads
 * no ambient clock of its own, which is what makes that possible.</p>
 */
class ApiErrorTest {

    /** A fixed clock so an emitted timestamp is reproducible rather than wall-clock dependent. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC);

    /** A synthetic correlation identity, standing in for the one the filter publishes. */
    private static final String CORRELATION_ID = "01K9Z4QW8N2M7X3P5R6T";

    /** A request path with no card number in it, so masking cannot obscure an assertion. */
    private static final String PATH = "/api/v1/accounts/00000000011";

    /**
     * Confirms a shape carrying a real message never has it overwritten.
     *
     * <p>Assumptions: this is the half of the latch the reference validator relies on. The four
     * {@code IF WS-RETURN-MSG-OFF} sites at lines 218, 233, 263 and 305 of
     * {@code app/cpy/CSUTLDPY.cpy} guard every aggregate write, so the FIRST failure a validation pass
     * finds is the one a user reads even when later failures also write.</p>
     */
    @Test
    @DisplayName("an aggregate message already present is never overwritten")
    void anExistingMessageIsNeverOverwritten() {
        ApiError first = ApiError.of(ApiError.CODE_VALIDATION, "first message", 400, CORRELATION_ID,
                PATH, CLOCK);

        ApiError latched = first.latchMessage("second message");

        assertThat(latched.message()).isEqualTo("first message");
        assertThat(latched).isSameAs(first);
    }

    /**
     * Confirms an absent, empty or blank aggregate message is the message-off state and can still
     * receive its first message.
     *
     * <p>Refactoring Rationale: only {@code null} used to count as message-off, so a shape whose
     * message was empty or blank silently refused its first real message -- the inverse of the
     * first-message-wins behaviour the latch exists to reproduce. The reference off-state is declared
     * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} over {@code WS-RETURN-MSG PIC X(75)}, at line 174 of
     * {@code app/cbl/COCRDUPC.cbl} and line 250 of
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}, so a blank aggregate is off and not
     * set.</p>
     *
     * <p>Assumptions: the three inputs are asserted as one parametrised case because all three describe
     * the same condition arriving by three routes -- a caller that set nothing, a mapper that defaulted
     * to empty, and a value padded out to its declared width. The reference cannot tell them apart and
     * neither may this.</p>
     *
     * @param offState the {@link String} aggregate message representing the off state, or the literal
     *     {@code "null"} marker standing for an absent message
     */
    @ParameterizedTest(name = "the message-off state {0} accepts its first message")
    @ValueSource(strings = {"null", "", "   "})
    @DisplayName("an absent, empty or blank message is message-off and accepts its first message")
    void messageOffStateAcceptsItsFirstMessage(String offState) {
        String message = "null".equals(offState) ? null : offState;
        ApiError off = ApiError.of(ApiError.CODE_VALIDATION, message, 400, CORRELATION_ID, PATH,
                CLOCK);

        ApiError latched = off.latchMessage("now set");

        assertThat(latched.message()).isEqualTo("now set");
    }

    /**
     * Confirms an all-NUL aggregate message is NOT the message-off state and therefore latches.
     *
     * <p>Refactoring Rationale: this case was formerly a fourth parameter of the off-state list above,
     * which made the aggregate message overwritable by low values as well as by spaces. That was
     * broader than the state the reference declares. The off state this latch reproduces is
     * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} over {@code WS-RETURN-MSG PIC X(75)}, at line 174 of
     * {@code app/cbl/COCRDUPC.cbl}; low values are the off state of a DIFFERENT field --
     * {@code CCARD-RETURN-MSG}, initialised to {@code LOW-VALUES} at line 21 of
     * {@code app/cpy/CSMSG01Y.cpy} -- and conflating the two made the aggregate lose a message it had
     * already been given whenever the earlier value happened to be low values rather than spaces.</p>
     *
     * <p>Assumptions: the case is retained rather than deleted because deleting it would leave the
     * narrowing invisible. It is inverted instead, so the narrowed rule is a build-enforced fact.
     * Reaching this state over HTTP is not possible -- a JSON string of NUL characters is not something
     * a mapper in this system produces -- so the assertion documents the predicate's edge rather than a
     * reachable request, and it is the predicate that had to narrow.</p>
     */
    @Test
    @DisplayName("an all-NUL message is not message-off and is not overwritten")
    void allNulMessageIsNotMessageOff() {
        String lowValues = "\u0000\u0000";
        ApiError set = ApiError.of(ApiError.CODE_VALIDATION, lowValues, 400, CORRELATION_ID, PATH,
                CLOCK);

        ApiError latched = set.latchMessage("now set");

        assertThat(latched.message()).isEqualTo(lowValues);
        assertThat(latched).isSameAs(set);
    }

    /**
     * Confirms the latch refuses a null candidate rather than clearing a message with it.
     *
     * <p>Assumptions: a null candidate is a caller defect rather than a message-off signal. The
     * off-state belongs to the shape being written TO, never to the value being written, so accepting
     * null here would let a later validation pass erase the first failure a user was shown.</p>
     */
    @Test
    @DisplayName("a null latch candidate is refused")
    void aNullLatchCandidateIsRefused() {
        ApiError off = ApiError.of(ApiError.CODE_VALIDATION, null, 400, CORRELATION_ID, PATH, CLOCK);

        assertThatThrownBy(() -> off.latchMessage(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("candidate");
    }

    /**
     * Confirms the latch and the field array accumulate independently of one another.
     *
     * <p>Assumptions: the reference sets field markers unconditionally beside a latched aggregate, at
     * lines 18 and 19 of {@code app/cpy/CSSETATY.cpy}, so a second failing field must still mark itself
     * even though the aggregate sentence is already spoken for. Asserting both on one shape is what
     * shows the two are not sharing a guard.</p>
     */
    @Test
    @DisplayName("field entries accumulate while the aggregate message stays latched")
    void fieldEntriesAccumulateIndependentlyOfTheLatch() {
        ApiError first = ApiError.of(ApiError.CODE_VALIDATION, "first message", 400, CORRELATION_ID,
                PATH, CLOCK);

        ApiError withTwo = first
                .withFieldError(new ApiError.FieldError("openDate", FieldValidationFlag.NOT_OK, "bad"))
                .withFieldError(new ApiError.FieldError("zip", FieldValidationFlag.BLANK, "missing"))
                .latchMessage("second message");

        assertThat(withTwo.message()).isEqualTo("first message");
        assertThat(withTwo.fieldErrors()).hasSize(2);
        assertThat(withTwo.hasFieldErrors()).isTrue();
        assertThat(first.fieldErrors()).isEmpty();
        assertThat(withTwo.fieldErrors().get(1).screenMarker())
                .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);
    }

    /**
     * Confirms the emitted field array cannot be mutated by whoever received it.
     *
     * <p>Assumptions: the array is the client's rendering instruction, and a caller that could append
     * to the list it was handed could add a field entry to a response already built. Sealing it is what
     * makes the accumulate-by-copy discipline above the only way to add one.</p>
     */
    @Test
    @DisplayName("the emitted field array is immutable")
    void theEmittedFieldArrayIsImmutable() {
        ApiError emitted = ApiError.ofFieldErrors("Please correct the highlighted fields", 400,
                CORRELATION_ID, PATH,
                List.of(new ApiError.FieldError("openDate", FieldValidationFlag.NOT_OK, "bad")),
                CLOCK);

        assertThatThrownBy(() -> emitted.fieldErrors()
                .add(new ApiError.FieldError("zip", FieldValidationFlag.NOT_OK, "bad")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Confirms every aggregate message this system can emit fits the published rendering band.
     *
     * <p>Assumptions: this is where the message half of the shape's construction contract is decided.
     * The band is {@code 10 CCARD-ERROR-MSG PIC X(75)}, which is a rendering constraint the client
     * honours, so a message that overruns it is a defect discovered at render time -- in a fixed-height
     * band, on a screen -- unless something decides it earlier. Refusing an over-width message inside
     * the factory was rejected because that factory's whole purpose is to RENDER a failure, and a throw
     * there turns a message eight characters too long into no response body at all. Every message the
     * system emits is a named constant, so the property is decidable without running anything, and
     * asserting the whole set here is what places the failure at build time.</p>
     *
     * @param message the {@link String} published message constant to measure against the band
     */
    @ParameterizedTest(name = "the band admits {0}")
    @ValueSource(strings = {
        ApiError.CSMSG01Y_THANK_YOU,
        ApiError.CSMSG01Y_INVALID_KEY,
        ApiError.CSUTLDPY_NO_31_DAYS,
        ApiError.CSUTLDPY_NO_30_DAYS,
        ApiError.CSUTLDPY_NOT_LEAP_YEAR,
        ApiError.COACTUPC_RECORD_CHANGED,
        GlobalExceptionHandler.MESSAGE_ACCOUNT_LOCK_FAILED,
        GlobalExceptionHandler.MESSAGE_CUSTOMER_LOCK_FAILED,
        GlobalExceptionHandler.MESSAGE_LOCK_UNAVAILABLE,
        GlobalExceptionHandler.MESSAGE_UPDATE_FAILED,
        GlobalExceptionHandler.MESSAGE_REFERENCED_ROW,
        GlobalExceptionHandler.MESSAGE_INTERNAL,
        GlobalExceptionHandler.MESSAGE_VALIDATION_FAILED,
        GlobalExceptionHandler.MESSAGE_MALFORMED_REQUEST,
        GlobalExceptionHandler.MESSAGE_NOT_FOUND,
        GlobalExceptionHandler.MESSAGE_FORBIDDEN})
    @DisplayName("every published aggregate message fits the 75-character rendering band")
    void everyPublishedMessageFitsTheRenderingBand(String message) {
        assertThat(message).isNotBlank();
        assertThat(message.length()).isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);
    }

    /**
     * Confirms the composed validator messages also fit the band, including at the longest label the
     * shared validator is driven with.
     *
     * <p>Assumptions: the validator's aggregate is not a constant -- it is a field label followed by a
     * fixed sentence -- so measuring the constants alone would leave the composed form unmeasured. The
     * labels asserted here are the ones the reference screens use for the three date fields, and the
     * longest sentence is the leap-year one, so the pair asserted is the widest composition the
     * validator can produce.</p>
     */
    @Test
    @DisplayName("a composed date-edit message fits the rendering band at its widest label")
    void composedDateEditMessagesFitTheRenderingBand() {
        List<String> labels = List.of("Open Date", "Expiry Date", "Date of Birth",
                "Account Reissue Date");

        for (String label : labels) {
            String composed = label + ApiError.CSUTLDPY_NOT_LEAP_YEAR;
            assertThat(composed.length())
                    .as("composed message for label %s", label)
                    .isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);
        }

        assertThatThrownBy(() -> DateEditValidator.validate("Open Date", "2023-6-15"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Confirms the severity a shape derives from its status separates a rejection from a failure.
     *
     * <p>Assumptions: the two tiers are what an alert channel is filtered on, so a rejection that
     * carried the failure severity would fill that channel with requests that were merely wrong. The
     * boundary is asserted at 400 and at 500 rather than at a representative value from each band,
     * because a boundary is the only place this derivation can be wrong.</p>
     */
    @Test
    @DisplayName("a 4xx shape is a warning and a 5xx shape is critical")
    void severityFollowsTheStatusBand() {
        assertThat(ApiError.of(ApiError.CODE_VALIDATION, "m", 400, CORRELATION_ID, PATH, CLOCK)
                .severity()).isEqualTo(ApiError.Severity.WARNING);
        assertThat(ApiError.of(ApiError.CODE_CONFLICT, "m", 409, CORRELATION_ID, PATH, CLOCK)
                .severity()).isEqualTo(ApiError.Severity.WARNING);
        assertThat(ApiError.of(ApiError.CODE_INTERNAL, "m", 500, CORRELATION_ID, PATH, CLOCK)
                .severity()).isEqualTo(ApiError.Severity.CRITICAL);
        assertThat(ApiError.of(ApiError.CODE_INTERNAL, "m",
                ApiError.INTERNAL_SERVER_ERROR_STATUS, CORRELATION_ID, PATH, CLOCK).severity())
                .isEqualTo(ApiError.Severity.CRITICAL);
    }

    /**
     * Confirms a shape built without a code still carries a machine code a client can switch on.
     *
     * <p>Assumptions: an absent code is a caller defect rather than a state a client should have to
     * handle, and the fallback resolves it to the internal code rather than to an empty string so that
     * a client matching on the code member never meets a blank one.</p>
     *
     * <p>Assumptions: the emitted code is asserted to be WIDER than the recorded baseline code width,
     * not equal to it. That width is provenance -- {@code ERR-CODE-1 PIC X(09)} -- and the migrated
     * codes deliberately spend more characters on being legible to whoever reads one, so an assertion
     * of equality would be asserting a constraint the shape states it does not keep.</p>
     */
    @Test
    @DisplayName("an absent code falls back to the internal code")
    void anAbsentCodeFallsBackToTheInternalCode() {
        ApiError emitted = ApiError.of(null, "m", 500, CORRELATION_ID, PATH, CLOCK);

        assertThat(emitted.code()).isEqualTo(ApiError.CODE_INTERNAL);
        assertThat(emitted.code()).isNotBlank();
        assertThat(emitted.code().length()).isGreaterThan(ApiError.CODE_WIDTH);
    }

    /**
     * Confirms an abend-carrying shape publishes only the client-safe external form of its detail.
     *
     * <p>Assumptions: the operator-facing components are the ones that would name a program, a
     * subsystem or an internal reason, so the factory reduces the detail before it reaches the body
     * rather than trusting each call site to. Asserting the reduction on the emitted shape is what
     * makes it a property of the response rather than of the caller.</p>
     */
    @Test
    @DisplayName("an abend shape publishes only the external form of its detail")
    void anAbendShapePublishesOnlyTheExternalDetail() {
        AbendDetail internal = new AbendDetail("APP0", "COACTUPC", "UNEXPECTED", "internal text");

        ApiError emitted = ApiError.ofAbend(GlobalExceptionHandler.MESSAGE_INTERNAL, CORRELATION_ID,
                PATH, internal, CLOCK);

        assertThat(emitted.abend()).isNotNull();
        assertThat(emitted.abend().abendMsg()).isEqualTo(AbendDetail.EXTERNAL_ABEND_MSG);
        assertThat(emitted.abend().abendCulprit().trim()).isEmpty();
        assertThat(emitted.abend().abendReason().trim()).isEmpty();
        assertThat(emitted.message()).isEqualTo(GlobalExceptionHandler.MESSAGE_INTERNAL);
        assertThat(emitted.status()).isEqualTo(ApiError.INTERNAL_SERVER_ERROR_STATUS);
    }
}
