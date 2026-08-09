package com.carddemo.reference.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.reference.dto.DateConversionRequest;
import com.carddemo.reference.dto.DateConversionResponse;
import com.carddemo.reference.service.DateConversionService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the date endpoint's refusal boundary to the ONE condition it is allowed to relabel.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Refactoring Rationale: this handler used to catch {@code IllegalArgumentException} -- the whole
 * supertype -- in order to answer a width disagreement as the four hundred its contract declares. The
 * shared date validator raises that same supertype for four internal invariants: a feedback record whose
 * severity contradicts its own code, an unknown date-component identity, a year outside the four-digit
 * domain, a value too wide for a four-digit field. Every one of those was therefore reported to a caller
 * as a four hundred naming the date it had sent correctly, and a genuine defect in the validator never
 * reached the five-hundred channel the alerting watches. The catch is now the validator's own width
 * condition, and these cases are what keep the two apart.</p>
 *
 * <p>Assumptions: the positive and the negative case are BOTH asserted, because narrowing a catch can fail
 * in two directions. Narrowing it too far stops answering the case the contract declares; not narrowing it
 * at all leaves every invariant relabelled. One case each is what pins the boundary rather than one side
 * of it.</p>
 *
 * <p>Assumptions: user-specified Rule 1 (Explainability) governs this file, and its validation gate is
 * conjunctive -- a member missing either its docstring or the reason for a non-obvious choice fails
 * review, not one or the other. Every member below therefore carries both.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
@DisplayName("the date endpoint refusal boundary")
class DateConversionRefusalTest {

    /**
     * Asserts a width disagreement is answered as a caller refusal keyed by the date parameter.
     *
     * <p>Assumptions: the REAL evaluation service is used here rather than a double, because the property
     * under test is that the condition the validator actually raises is the condition this handler
     * actually catches. A double raising a hand-built exception would pass while the two had drifted
     * apart.</p>
     *
     * <p>Assumptions: the refusal's sentence is asserted to name neither the date nor the mask the caller
     * sent. The caught condition's own message names the mask and both widths, and forwarding it would put
     * up to ten characters of caller input into an operational record and give one refusal a different
     * sentence per request.</p>
     */
    @Test
    @DisplayName("a width disagreement is answered as a caller refusal on the date parameter")
    void aWidthDisagreementIsAnsweredAsACallerRefusal() {
        DateConversionController controller = new DateConversionController(new DateConversionService());

        assertThatThrownBy(() -> controller.evaluateDate("2022-07-1", DateEditValidator.DATE_FORMAT_MASK))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        ClientInputException.class))
                .satisfies(refusal -> {
                    assertThat(refusal.code()).isEqualTo(ApiError.CODE_VALIDATION);
                    assertThat(refusal.fields()).isEqualTo(List.of("date"));
                    assertThat(refusal.getMessage())
                            .doesNotContain("2022-07-1")
                            .doesNotContain(DateEditValidator.DATE_FORMAT_MASK);
                });
    }

    /**
     * Asserts an internal invariant failure is NOT relabelled as a caller refusal.
     *
     * <p>Assumptions: a double IS used here, and it has to be: the invariants this guards against are
     * unreachable through the real validator by any input a caller can send -- that is what makes them
     * invariants. Substituting the collaborator is the only way to present the handler with the supertype
     * it must no longer claim.</p>
     *
     * <p>Assumptions: the assertion is that the failure propagates UNCHANGED, identity included, rather
     * than merely that it is not a client refusal. Propagating a different instance would still lose the
     * original diagnostic that the internal-failure channel renders.</p>
     */
    @Test
    @DisplayName("an internal invariant failure propagates unchanged")
    void anInternalInvariantFailurePropagatesUnchanged() {
        DateConversionService evaluator = mock(DateConversionService.class);
        IllegalArgumentException invariant =
                new IllegalArgumentException("severity 0 contradicts INVALID_DATE");
        when(evaluator.convert(org.mockito.ArgumentMatchers.any(DateConversionRequest.class)))
                .thenThrow(invariant);
        DateConversionController controller = new DateConversionController(evaluator);

        assertThatThrownBy(() -> controller.evaluateDate("2022-07-18", DateEditValidator.DATE_FORMAT_MASK))
                .isSameAs(invariant)
                .isNotInstanceOf(ClientInputException.class);
    }

    /**
     * Asserts an unrecognised picture is RETURNED as a verdict rather than raised.
     *
     * <p>Assumptions: this case is here because it is the reason the narrowed catch loses nothing. It is
     * the other input-shaped failure a caller can provoke on this route, and the validator answers it with
     * the unusable-pattern feedback rather than an exception -- transcribing the reference condition
     * declared at {@code app/cbl/CSUTLDTC.cbl} line 68 and selected at its lines 141 and 142. Asserting it
     * shows the boundary is complete rather than merely narrower.</p>
     */
    @Test
    @DisplayName("an unrecognised picture is returned as a verdict, not raised")
    void anUnrecognisedPictureIsReturnedAsAVerdict() {
        DateConversionController controller = new DateConversionController(new DateConversionService());

        DateConversionResponse verdict = controller.evaluateDate("2022-07-18", "DD/MM/YYYY");

        assertThat(verdict.feedbackCode()).isEqualTo("BAD_PIC_STRING");
        assertThat(verdict.mask()).isEqualTo("DD/MM/YYYY");
    }
}
