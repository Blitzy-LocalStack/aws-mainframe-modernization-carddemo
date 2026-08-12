package com.carddemo.reporting.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises what the submission outcome admits into a response body and what it refuses.
 *
 * <p>Assumptions: this class exists because the record's rules are the published contract's rules. The
 * document declares {@code message} required and nullable and {@code submission} required and nullable,
 * and it is the record's own construction guard that decides whether a body matching that declaration is
 * reachable at all. An earlier revision refused a null message outright, so the one state the document
 * singled out for a cancellation was the one state the service could not produce -- a disagreement no
 * controller test could surface, because the controller could only ever pass what the record accepted.
 *
 * <p>Alternatives Considered: leaving the construction rules to be covered indirectly by the controller
 * tests, which do exercise both outcomes end to end. Rejected because those tests reach construction
 * through the two factory methods only, so the refusals that no factory can trigger -- a blank sentence,
 * an over-wide one, a flag disagreeing with the run description -- would go unexercised, and each of
 * them protects a distinct wire-level defect.
 */
class ReportSubmissionOutcomeTest {

    /** A run description to pair with a submitted outcome, so the cross-check has something to see. */
    private static final ReportSubmissionResponse ACCEPTED_RUN = new ReportSubmissionResponse(
            "arn:aws:states:us-east-1:000000000000:execution:carddemo-report:1",
            "Monthly",
            "MONTHLY",
            "Monthly Transaction Report",
            "2022-07-01",
            "2022-07-31",
            "2022-07-18 22:10:31.000000");

    /** Asserts a cancellation carries no message, which is the branch the reference writes nothing on. */
    @Test
    @DisplayName("a cancellation carries a null message and no run")
    void aCancellationCarriesNoMessageAndNoRun() {
        ReportSubmissionOutcome cancelled = ReportSubmissionOutcome.cancelled();

        assertThat(cancelled.submitted()).isFalse();
        assertThat(cancelled.message())
                .as("app/cbl/CORPT00C.cbl L480 to L483 moves nothing into the message on this branch,"
                        + " so a sentence here would be one the reference never emitted")
                .isNull();
        assertThat(cancelled.submission()).isNull();
    }

    /** Asserts an accepted run carries both the sentence the reference composes and its handle. */
    @Test
    @DisplayName("an accepted run carries its sentence and its handle")
    void anAcceptedRunCarriesItsSentenceAndItsHandle() {
        ReportSubmissionOutcome accepted =
                ReportSubmissionOutcome.accepted(ACCEPTED_RUN, "Monthly report submitted for printing ...");

        assertThat(accepted.submitted()).isTrue();
        assertThat(accepted.message()).isEqualTo("Monthly report submitted for printing ...");
        assertThat(accepted.submission()).isSameAs(ACCEPTED_RUN);
    }

    // WHY : Assumptions: blank is asserted separately from null because the two are different states
    //       and only one is admitted. A fixed-width screen field holds spaces when nothing has been
    //       written to it, so a caller porting that field directly would supply spaces and mean
    //       absence; refusing it is what makes the caller say which it meant.
    /** Asserts a blank sentence is refused rather than treated as an absent one. */
    @Test
    @DisplayName("a blank message is refused, where an absent one is admitted")
    void aBlankMessageIsRefused() {
        assertThatThrownBy(() -> new ReportSubmissionOutcome(false, "   ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    /** Asserts a sentence wider than the report map's own message field is refused. */
    @Test
    @DisplayName("a message wider than the map's field is refused")
    void anOverWideMessageIsRefused() {
        String tooWide = "x".repeat(ReportSubmissionOutcome.MESSAGE_WIDTH + 1);

        assertThatThrownBy(() -> new ReportSubmissionOutcome(false, tooWide, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(ReportSubmissionOutcome.MESSAGE_WIDTH));
    }

    /** Asserts a message of exactly the declared width is admitted, so the bound is inclusive. */
    @Test
    @DisplayName("a message of exactly the declared width is admitted")
    void aMessageOfExactlyTheDeclaredWidthIsAdmitted() {
        String exact = "x".repeat(ReportSubmissionOutcome.MESSAGE_WIDTH);

        assertThat(new ReportSubmissionOutcome(false, exact, null).message()).isEqualTo(exact);
    }

    /** Asserts a body claiming a submission without a handle is refused. */
    @Test
    @DisplayName("a submitted outcome without a run description is refused")
    void aSubmittedOutcomeWithoutARunIsRefused() {
        assertThatThrownBy(() -> new ReportSubmissionOutcome(true, "started", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must carry the accepted run");
    }

    /** Asserts a body carrying a handle while claiming nothing was started is refused. */
    @Test
    @DisplayName("a cancelled outcome carrying a run description is refused")
    void aCancelledOutcomeCarryingARunIsRefused() {
        assertThatThrownBy(() -> new ReportSubmissionOutcome(false, null, ACCEPTED_RUN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry a run description");
    }

    /** Asserts the accepted factory still requires the sentence the reference composes for that branch. */
    @Test
    @DisplayName("the accepted factory refuses a null message even though the component admits one")
    void theAcceptedFactoryStillRequiresItsMessage() {
        assertThatThrownBy(() -> ReportSubmissionOutcome.accepted(ACCEPTED_RUN, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("message");
    }
}
