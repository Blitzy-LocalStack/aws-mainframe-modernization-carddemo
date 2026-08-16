package com.carddemo.reference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.reference.dto.DateConversionRequest;
import com.carddemo.reference.dto.DateConversionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts the date-edit verdict the synchronous reference route reports.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this class covers the one member of a withdrawn queue consumer that had no queue concern in
 * it. That consumer declared a second {@code @SqsListener} on the request queue this module no longer
 * consumes, and was removed for it; the verdict member moved to {@link DateConversionService}, and no test
 * named the member while it lived on the consumer.
 *
 * <p>Refactoring Rationale: this paragraph previously named the sibling consumer
 * {@code DateInquiryMessageListener} as the class that shared the request queue. That class has since been
 * withdrawn from this module too -- the whole inquiry exchange is served by one consumer in
 * {@code account-service}, dispatching on the four-character function code -- so the reference would now
 * point at a type that does not exist, and a reader following it would conclude the queue route still
 * lives here. The shared queue is named without naming a class, because the class that answers it is not
 * this module's to cite.
 *
 * <p>Assumptions: what is asserted here is the WIRING of the shared rules into the published shape -- which
 * mask is applied when none is sent, that the mask actually applied is echoed rather than the one
 * submitted, that all four verdict members are carried across, and which refusal a mismatched width
 * raises. The date rules themselves are asserted against the baseline in {@code common-lib}'s own tests for
 * {@link DateEditValidator}, and restating them here would give one rule two owners that could disagree.
 *
 * <p>Assumptions: the expected feedback-code NAME for a valid date is {@code INVALID_DATE}, which reads as
 * a mistake and is not one. The baseline declares that condition at {@code app/cbl/CSUTLDTC.cbl} line 62
 * with a token of eight zero bytes, and its lines 129 and 130 select that same condition to report the date
 * VALID -- the name means the opposite of what it says. It is asserted with the name the baseline uses,
 * because a caller branches on the name this service publishes.
 *
 * <p>Assumptions: the service is constructed directly rather than injected, because it holds no field, no
 * clock and no client. There is nothing to substitute and nothing to configure, which is the property that
 * made splitting it out of the consumer possible at all.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; each method below carries its own.
 */
class DateConversionServiceTest {

    /** The service under test; stateless, so one instance serves every case. */
    private final DateConversionService service = new DateConversionService();

    /**
     * A well-formed date in the default picture, taken from the reference's own ordering.
     */
    private static final String VALID_MASKED_DATE = "2022-07-18";

    /**
     * Verifies a date sent with no mask is read against the published default and echoes that default.
     *
     * <p>Assumptions: the ECHOED mask is asserted and not only the verdict, because the echo is what tells a
     * caller which picture its answer was produced under. A caller that omitted the mask cannot otherwise
     * distinguish the default from a picture the service chose per input.</p>
     */
    @Test
    @DisplayName("an absent mask applies the published default and the default is echoed back")
    void anAbsentMaskAppliesTheDefault() {
        DateConversionResponse verdict =
                this.service.convert(new DateConversionRequest(VALID_MASKED_DATE, null));

        assertThat(verdict.mask()).isEqualTo(DateEditValidator.DATE_FORMAT_MASK);
        assertThat(verdict.date()).isEqualTo(VALID_MASKED_DATE);
        assertThat(verdict.severity()).isEqualTo(DateEditValidator.SEVERITY_VALID);
        assertThat(verdict.feedbackCode())
                .as("the baseline's own name for the acceptable verdict, which reads as its opposite")
                .isEqualTo(DateEditValidator.FeedbackCode.INVALID_DATE.name());
        assertThat(verdict.messageNumber()).isZero();
        assertThat(verdict.verdict()).isNotBlank();
    }

    /**
     * Verifies a mask sent as blank defaults exactly as an absent one does.
     *
     * <p>Assumptions: blank and absent are treated alike deliberately, and the case exists because the two
     * arrive by different routes -- a caller omitting the parameter and a caller sending it empty -- and a
     * reader cannot tell from the request shape alone that the second is not a picture of zero characters.
     * Rejecting blank would refuse a request the default was designed to serve.</p>
     */
    @Test
    @DisplayName("a blank mask defaults exactly as an absent mask does")
    void aBlankMaskDefaultsToo() {
        DateConversionResponse fromBlank =
                this.service.convert(new DateConversionRequest(VALID_MASKED_DATE, "   "));
        DateConversionResponse fromAbsent =
                this.service.convert(new DateConversionRequest(VALID_MASKED_DATE, null));

        assertThat(fromBlank).isEqualTo(fromAbsent);
    }

    /**
     * Verifies an explicitly supplied picture is applied and echoed rather than replaced by the default.
     *
     * <p>Assumptions: the baseline's eight-character picture is used, because it is the one the reference
     * utility itself declares and it differs from the default in width as well as in separators -- so a
     * regression that ignored the submitted mask would fail on the width rather than only on the echo.</p>
     */
    @Test
    @DisplayName("a supplied picture is applied and echoed rather than defaulted")
    void aSuppliedMaskIsApplied() {
        DateConversionResponse verdict = this.service.convert(new DateConversionRequest(
                "20220718", DateEditValidator.BASELINE_DATE_FORMAT_MASK));

        assertThat(verdict.mask()).isEqualTo(DateEditValidator.BASELINE_DATE_FORMAT_MASK);
        assertThat(verdict.date()).isEqualTo("20220718");
        assertThat(verdict.severity()).isEqualTo(DateEditValidator.SEVERITY_VALID);
    }

    /**
     * Verifies an unusable day is REPORTED ON rather than refused.
     *
     * <p>Purpose: this is the whole point of the operation. The candidate below is a well-formed
     * ten-character value naming a day that does not exist, and a service that refused it would answer a
     * generic binding failure where the caller asked for a specific verdict.</p>
     *
     * <p>Assumptions: the severity and the message number are asserted as two independent members rather
     * than collapsed into an acceptance flag, because two baseline callers accept a rejected evaluation when
     * the message number is the one they tolerate while the shared driver accepts none. Collapsing them here
     * would take that reading away from the caller.</p>
     */
    @Test
    @DisplayName("a well-formed but unusable date is reported on rather than refused")
    void anUnusableDateIsReportedOn() {
        DateConversionResponse verdict =
                this.service.convert(new DateConversionRequest("2022-02-30", null));

        assertThat(verdict.severity()).isNotEqualTo(DateEditValidator.SEVERITY_VALID);
        assertThat(verdict.messageNumber()).isNotZero();
        assertThat(verdict.feedbackCode())
                .isNotEqualTo(DateEditValidator.FeedbackCode.INVALID_DATE.name());
        assertThat(verdict.date()).isEqualTo("2022-02-30");
        assertThat(verdict.mask()).isEqualTo(DateEditValidator.DATE_FORMAT_MASK);
    }

    /**
     * Verifies a candidate whose width disagrees with its picture raises rather than answering a verdict.
     *
     * <p>Assumptions: this is the ONE input-dependent refusal this member raises, and the route above it
     * relabels exactly this case as a caller refusal so the contract's published 400 is what a caller
     * receives. Asserting it here fixes which case that relabelling covers, so a later change that made a
     * second condition raise the same type would not silently inherit the 400.</p>
     */
    @Test
    @DisplayName("a candidate whose width disagrees with its picture raises")
    void aWidthMismatchRaises() {
        DateConversionRequest mismatched = new DateConversionRequest(
                "20220718", DateEditValidator.DATE_FORMAT_MASK);

        assertThatThrownBy(() -> this.service.convert(mismatched))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies an absent request is refused at the boundary rather than dereferenced.
     */
    @Test
    @DisplayName("an absent request is refused rather than dereferenced")
    void anAbsentRequestIsRefused() {
        assertThatThrownBy(() -> this.service.convert(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("request");
    }
}
