package com.carddemo.reference.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.reference.service.DateConversionService;
import org.hamcrest.Matchers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Drives the date-evaluation route through a real dispatcher rather than by calling the handler.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Purpose: the sibling routing contract in this package compares the published document against the
 * method table, which cannot see what a request to a mapped address does. This class asserts the part that
 * only a dispatcher reaches on this route: that the candidate binds as TEXT rather than as a date, that the
 * parameter constraints are enforced, that an unusable day is reported on with 200 rather than refused, and
 * that the width mismatch the handler relabels renders as the 400 the contract publishes rather than as a
 * 500.
 *
 * <p>Assumptions: the service beneath is REAL rather than mocked, and that is required rather than
 * thorough. Three of the cases below turn on which status a particular VERDICT or refusal produces, and a
 * mocked service would answer whatever it was stubbed with -- the assertion would then be about the stub.
 * The service holds no field, no clock and no client, so there is nothing a substitute would isolate.
 *
 * <p>Assumptions: the shared advice is registered, because two of the outcomes asserted here ARE the
 * advice's -- a rejected parameter and the relabelled width refusal both render through it, and without it
 * they would surface as a servlet-container default that says nothing about the published contract.
 *
 * <p>Assumptions: the dispatcher is assembled with {@code standaloneSetup} and no security chain, so a
 * failure localises to binding, validation, the handler or the advice. Which authority this route demands is
 * asserted in {@code com.carddemo.reference.config} against the chain's own installed authorization
 * managers.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; each method below carries its own.
 */
class DateEvaluationDispatcherTest {

    /** The instant the advice stamps on every problem document, fixed so a body is comparable. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-02-01T00:00:00Z");

    /** The dispatcher under test. */
    private MockMvc mockMvc;

    /**
     * Builds the dispatcher over the real evaluation service with the shared advice registered.
     */
    @BeforeEach
    void buildDispatcher() {
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new DateConversionController(new DateConversionService()))
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    /**
     * A well-formed date with no mask is answered with the verdict and the applied default picture.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a valid date with no mask is answered 200 with the default picture echoed")
    void aValidDateIsAnswered() throws Exception {
        this.mockMvc.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, "2022-07-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value(DateEditValidator.SEVERITY_VALID))
                .andExpect(jsonPath("$.mask").value(DateEditValidator.DATE_FORMAT_MASK))
                .andExpect(jsonPath("$.date").value("2022-07-18"));
    }

    /**
     * A well-formed but unusable day is answered with 200 carrying its verdict, not refused.
     *
     * <p>Purpose: this is the property the route exists for, and it is the one a reader is most likely to
     * assume works the other way. The candidate binds as text precisely so that a value whose usability is
     * in question can be reported ON; a binder that parsed it would refuse exactly the inputs a caller asks
     * about.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a well-formed but unusable day is answered 200 with a non-zero severity")
    void anUnusableDayIsReportedRatherThanRefused() throws Exception {
        this.mockMvc.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, "2022-02-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value(DateEditValidator.SEVERITY_ERROR))
                .andExpect(jsonPath("$.messageNumber").value(Matchers.not(0)));
    }

    /**
     * A candidate whose width disagrees with its picture renders the published 400, never a 500.
     *
     * <p>Assumptions: the status is the whole point of the case. The handler catches the width refusal and
     * re-raises it as the caller-refusal type specifically because the shared advice tests for that type and
     * deliberately not for its supertype -- left to propagate, the same condition would answer 500 and tell
     * a caller its own input was the service's fault. Only a dispatcher can show which of the two a caller
     * actually receives.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a width that disagrees with the picture renders 400 and not 500")
    void aWidthMismatchRendersTheDocumentedRefusal() throws Exception {
        this.mockMvc.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, "20220718")
                        .param(DateConversionController.PARAM_MASK,
                                DateEditValidator.DATE_FORMAT_MASK))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION));
    }

    /**
     * A candidate below the admitted width is refused by the parameter constraint.
     *
     * <p>Assumptions: this asserts that the constraints declared on the handler's parameters are ENFORCED,
     * which a direct handler call cannot show -- a call passes whatever value it likes straight into the
     * body. The framework applies method validation to a controller parameter carrying constraint
     * annotations and raises its own method-validation failure, which the shared advice renders as the
     * published 400.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a candidate shorter than the admitted width is refused 400")
    void aTooShortCandidateIsRefused() throws Exception {
        this.mockMvc.perform(get(DateConversionController.BASE_PATH)
                        .param(DateConversionController.PARAM_DATE, "2022"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION));
    }

    /**
     * A request omitting the required candidate is refused rather than defaulted.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a request omitting the candidate is refused 400")
    void anOmittedCandidateIsRefused() throws Exception {
        this.mockMvc.perform(get(DateConversionController.BASE_PATH))
                .andExpect(status().isBadRequest());
    }
}
