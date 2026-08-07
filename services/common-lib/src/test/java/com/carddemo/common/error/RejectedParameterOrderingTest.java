package com.carddemo.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.validation.FieldValidationFlag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Locks the order in which {@link GlobalExceptionHandler} reports two constraints failing on one
 * request parameter.
 *
 * <p>Purpose: a handler parameter that carries both a presence constraint and a shape constraint fails
 * BOTH when the caller supplies an empty value, and the validation provider evaluates the two in no
 * defined order. The aggregate sentence of the emitted problem shape is taken from the first per-field
 * entry, so without a deliberate order that sentence is whichever constraint the provider happened to
 * report first -- not stable between provider versions, and not matchable against a published example.
 * The baseline programs edit presence first and STOP, so the presence sentence is the correct one.</p>
 *
 * <p>Assumptions: the class asserts the property at TWO levels because neither level is sufficient on
 * its own. The request-level tests prove the whole path -- provider, framework validation mechanism,
 * advice and response body -- reports both constraints, takes the aggregate from the first entry, and
 * separates the blank state from the not-acceptable one. The ranking test proves the ORDER itself,
 * because the validation provider reports the constraints of one element in an order derived from a hash
 * set: a request-level test observes whichever order that hash gives and, when the hash already gives
 * the wanted order, cannot fail even if the ranking is deleted outright. That was verified, not assumed
 * -- neutralising the ranking leaves the request-level tests green.</p>
 *
 * <p>Alternatives Considered: constructing the framework's validation failure by hand and invoking the
 * advice method directly. Rejected because building that failure requires assembling the framework's own
 * per-parameter result objects, which fixes the very ordering under test into the fixture -- the test
 * would then assert that a list this test built stayed in the order this test built it in.</p>
 *
 * <p>Trade-offs: the request-level tests stand up a servlet mock and a validation provider, so they are
 * slower than the pure-unit tests around them. Accepted because the contract they guard -- which
 * sentence becomes the aggregate, and which state each entry carries -- is observable only in an emitted
 * body.</p>
 */
@DisplayName("GlobalExceptionHandler parameter-failure ordering")
final class RejectedParameterOrderingTest {

    /**
     * The route the probe controller publishes, referenced by every request below.
     */
    private static final String PROBE_ROUTE = "/probe";

    /**
     * The sentence the presence constraint reports, which must become the aggregate.
     */
    private static final String PRESENCE_SENTENCE = "Please enter Acct Id...";

    /**
     * The sentence the shape constraint reports, which must never become the aggregate for an empty
     * value.
     */
    private static final String SHAPE_SENTENCE = "Acct Id must be Numeric ...";

    /**
     * The number of per-field entries an empty value produces, one per failed constraint.
     */
    private static final int BOTH_CONSTRAINTS = 2;

    /**
     * The instant the advice's clock is pinned to, so an emitted timestamp is reproducible.
     */
    private static final String PINNED_INSTANT = "2026-08-07T00:00:00Z";

    /**
     * The mock servlet the assertions drive.
     */
    private MockMvc mockMvc;

    /**
     * Builds a standalone mock servlet over the probe controller and the shared advice.
     *
     * <p>Assumptions: the advice is registered as controller advice rather than instantiated, because
     * the property under test is that the FRAMEWORK routes a parameter failure into it. Constructing it
     * and calling it would bypass the routing that the ordering depends on.</p>
     */
    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(Instant.parse(PINNED_INSTANT), ZoneOffset.UTC)))
                .build();
    }

    /**
     * An empty parameter reports its presence sentence first, and as the aggregate.
     *
     * <p>Assumptions: both entries are asserted present, not just the first. Reporting only the presence
     * failure would also satisfy an aggregate assertion, so asserting the count keeps this test honest
     * about which behaviour it locks -- both constraints are reported, in a fixed order.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an empty parameter reports the presence constraint first and as the aggregate")
    void anEmptyParameterReportsThePresenceConstraintFirst() throws Exception {
        this.mockMvc.perform(get(PROBE_ROUTE).param("accountId", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.message").value(PRESENCE_SENTENCE))
                .andExpect(jsonPath("$.fieldErrors.length()").value(BOTH_CONSTRAINTS))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("accountId"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(PRESENCE_SENTENCE))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.BLANK.name()))
                .andExpect(jsonPath("$.fieldErrors[1].message").value(SHAPE_SENTENCE));
    }

    /**
     * A supplied but wrongly shaped parameter reports only its shape sentence.
     *
     * <p>Assumptions: this is the companion case that proves the ordering did not simply hard-code the
     * presence sentence as the aggregate. A value that IS supplied cannot fail the presence constraint,
     * so exactly one entry is emitted and it carries the not-acceptable state rather than the blank
     * one.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a supplied but wrongly shaped parameter reports only the shape sentence")
    void aSuppliedButWronglyShapedParameterReportsOnlyTheShapeSentence() throws Exception {
        this.mockMvc.perform(get(PROBE_ROUTE).param("accountId", "not-digits"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(SHAPE_SENTENCE))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(SHAPE_SENTENCE))
                .andExpect(jsonPath("$.fieldErrors[0].state")
                        .value(FieldValidationFlag.NOT_OK.name()));
    }

    /**
     * A parameter satisfying both constraints reaches the handler.
     *
     * <p>Assumptions: included so that a probe controller whose constraints rejected EVERYTHING could
     * not make the two assertions above pass vacuously.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a parameter satisfying both constraints reaches the handler")
    void aValidParameterReachesTheHandler() throws Exception {
        this.mockMvc.perform(get(PROBE_ROUTE).param("accountId", "00000000011"))
                .andExpect(status().isOk());
    }

    /**
     * Every presence constraint ranks ahead of every shape constraint, whatever the value was.
     *
     * <p>Assumptions: the code arrays are built in the shape the framework emits -- most specific first,
     * bare constraint name last -- as observed from
     * {@code MethodValidationAdapter$ViolationMessageSourceResolvable}. The bare name is the element the
     * ranking reads, so a fixture that omitted it would assert nothing about real input.</p>
     *
     * <p>Assumptions: the shape constraints listed here are the ones the services actually place on
     * request parameters, so the assertion covers the real combinations rather than an abstract pair.</p>
     */
    @Test
    @DisplayName("every presence constraint ranks ahead of every shape constraint")
    void everyPresenceConstraintRanksAheadOfEveryShapeConstraint() {
        for (String presence : new String[] {"NotNull", "NotBlank", "NotEmpty"}) {
            for (String shape : new String[] {"Pattern", "Size", "Min", "Max", "Digits"}) {
                assertThat(GlobalExceptionHandler.constraintRank(resolvable(presence)))
                        .as("%s must rank ahead of %s", presence, shape)
                        .isLessThan(GlobalExceptionHandler.constraintRank(resolvable(shape)));
            }
        }
    }

    /**
     * A resolvable carrying no codes at all ranks with the shape constraints rather than failing.
     *
     * <p>Assumptions: the ranking runs on an error path, so an unexpected resolvable must degrade to a
     * defined rank instead of raising. A failure inside this advice would turn a rejected request into an
     * internal one, which is the single outcome an error path must not produce.</p>
     */
    @Test
    @DisplayName("a resolvable carrying no codes ranks with the shape constraints")
    void aResolvableCarryingNoCodesRanksWithTheShapeConstraints() {
        MessageSourceResolvable empty = new DefaultMessageSourceResolvable(
                new String[0], null, SHAPE_SENTENCE);
        assertThat(GlobalExceptionHandler.constraintRank(empty))
                .isEqualTo(GlobalExceptionHandler.constraintRank(resolvable("Pattern")));
    }

    /**
     * Builds a resolvable carrying the code array the framework emits for one failed constraint.
     *
     * @param constraint the bare constraint annotation name, which the framework places last
     * @return a resolvable whose codes end with {@code constraint}, as a real parameter failure's do
     */
    private static MessageSourceResolvable resolvable(String constraint) {
        return new DefaultMessageSourceResolvable(new String[] {
            constraint + ".probeController#probe.accountId",
            constraint + ".accountId",
            constraint + ".java.lang.String",
            constraint,
        }, null, SHAPE_SENTENCE);
    }

    /**
     * A controller whose only job is to carry two constraints on one parameter.
     *
     * <p>Purpose: gives the framework a real handler method to validate, so that the parameter failure
     * arrives at the shared advice by the same route a service's own controller would send it. It is
     * declared here rather than in a service module because the behaviour under test belongs to the
     * shared advice, and a test that named a service's route would move when that route moved.</p>
     *
     * <p>Assumptions: the two sentences are the pending-authorization account-scope pair carried from
     * lines 268 to 269 and 277 to 278 of {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl}. They
     * are used here because they are the pair whose ordering the reference program fixes -- it edits
     * blank at line 264 and reaches its numeric edit only at line 273 -- so an assertion on them is an
     * assertion about reference behaviour rather than about invented text.</p>
     */
    @RestController
    static final class ProbeController {

        /**
         * Accepts an account scope constrained both for presence and for shape.
         *
         * @param accountId the value under test, rejected as blank when empty and as wrongly shaped
         *     when it is not eleven digits
         * @return a fixed body, since no assertion here inspects a successful response beyond its status
         */
        @GetMapping(PROBE_ROUTE)
        String probe(@RequestParam
                @NotBlank(message = PRESENCE_SENTENCE)
                @Pattern(regexp = "^[0-9]{11}$", message = SHAPE_SENTENCE)
                final String accountId) {
            return accountId;
        }
    }
}
