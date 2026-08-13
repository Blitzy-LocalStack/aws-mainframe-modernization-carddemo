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

    /**
     * A run description to pair with a submitted outcome, so the cross-check has something to see.
     *
     * <p>⚠️ Refactoring Rationale: the handle is an execution NAME and was an execution ARN. The
     * response contract now publishes the name -- the value the status operation is addressed by -- and
     * refuses a value carrying a character outside the orchestration's alphabet, which an ARN's colons
     * are, so an ARN here would no longer construct at all.
     */
    private static final ReportSubmissionResponse ACCEPTED_RUN = new ReportSubmissionResponse(
            "monthly-2022-07-01-2022-07-31-Zm9vYmFy",
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

        assertThat(cancelled.outcome()).isEqualTo(ReportSubmissionOutcome.Outcome.DECLINED);
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

        assertThat(accepted.outcome()).isEqualTo(ReportSubmissionOutcome.Outcome.STARTED);
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
        assertThatThrownBy(() -> new ReportSubmissionOutcome(
                ReportSubmissionOutcome.Outcome.UNANSWERED, "   ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    /** Asserts a sentence wider than the report map's own message field is refused. */
    @Test
    @DisplayName("a message wider than the map's field is refused")
    void anOverWideMessageIsRefused() {
        String tooWide = "x".repeat(ReportSubmissionOutcome.MESSAGE_WIDTH + 1);

        assertThatThrownBy(() -> new ReportSubmissionOutcome(
                ReportSubmissionOutcome.Outcome.UNANSWERED, tooWide, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(ReportSubmissionOutcome.MESSAGE_WIDTH));
    }

    /** Asserts a message of exactly the declared width is admitted, so the bound is inclusive. */
    @Test
    @DisplayName("a message of exactly the declared width is admitted")
    void aMessageOfExactlyTheDeclaredWidthIsAdmitted() {
        String exact = "x".repeat(ReportSubmissionOutcome.MESSAGE_WIDTH);

        assertThat(new ReportSubmissionOutcome(
                ReportSubmissionOutcome.Outcome.UNANSWERED, exact, null).message()).isEqualTo(exact);
    }

    /** Asserts a body claiming a started run without a handle is refused. */
    @Test
    @DisplayName("a started outcome without a run description is refused")
    void aStartedOutcomeWithoutARunIsRefused() {
        assertThatThrownBy(() -> new ReportSubmissionOutcome(
                ReportSubmissionOutcome.Outcome.STARTED, "started", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must carry the accepted run");
    }

    /** Asserts a body carrying a handle while reporting that nothing started is refused. */
    @Test
    @DisplayName("an outcome that started nothing but carries a run description is refused")
    void anOutcomeThatStartedNothingCarryingARunIsRefused() {
        assertThatThrownBy(() -> new ReportSubmissionOutcome(
                ReportSubmissionOutcome.Outcome.DECLINED, null, ACCEPTED_RUN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry a run description");
    }

    // WHY : ⚠️ Refactoring Rationale: the four cases below are new, and they exist because the boolean
    //       this type used to carry could not express the third turn at all. An unanswered confirmation
    //       was reported exactly like a decline, and the browser client -- reading only the status --
    //       labelled it DECLINED, telling a caller it had cancelled when it had merely not answered yet.
    //       The three factories are asserted to produce three DISTINCT discriminators, and the two
    //       message rules that separate the turns are asserted in both directions.
    /** Asserts an unanswered turn is discriminated from a decline rather than sharing its shape. */
    @Test
    @DisplayName("an unanswered turn is discriminated from a decline")
    void anUnansweredTurnIsDiscriminatedFromADecline() {
        ReportSubmissionOutcome unanswered =
                ReportSubmissionOutcome.unanswered("Confirm to print the Monthly report ...");

        assertThat(unanswered.outcome()).isEqualTo(ReportSubmissionOutcome.Outcome.UNANSWERED);
        assertThat(unanswered.outcome())
                .as("the two turns that share HTTP 200 must not share a discriminator")
                .isNotEqualTo(ReportSubmissionOutcome.cancelled().outcome());
        assertThat(unanswered.message()).isEqualTo("Confirm to print the Monthly report ...");
        assertThat(unanswered.submission()).isNull();
    }

    /** Asserts a decline carrying a sentence is refused, the reference writing none. */
    @Test
    @DisplayName("a declined outcome carrying a sentence is refused")
    void aDeclinedOutcomeCarryingASentenceIsRefused() {
        assertThatThrownBy(() -> new ReportSubmissionOutcome(
                ReportSubmissionOutcome.Outcome.DECLINED, "something", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries no sentence");
    }

    /**
     * Asserts an unanswered outcome without the prompt is refused, the caller having no question.
     *
     * <p>Measured: collapsing the two per-outcome sentence rules into one not-started arm -- the shape a
     * boolean discriminator could express -- fails exactly this case and
     * {@code aDeclinedOutcomeCarryingASentenceIsRefused}, both with {@code Expecting code to raise a
     * throwable}. The pair is what stops a declined turn carrying a sentence it should not have and an
     * unanswered one arriving without the question it exists to ask.</p>
     */
    @Test
    @DisplayName("an unanswered outcome without the prompt is refused")
    void anUnansweredOutcomeWithoutThePromptIsRefused() {
        assertThatThrownBy(() -> new ReportSubmissionOutcome(
                ReportSubmissionOutcome.Outcome.UNANSWERED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must carry the confirmation prompt");
    }

    /** Asserts an absent discriminator is refused rather than defaulted to a turn. */
    @Test
    @DisplayName("an absent discriminator is refused")
    void anAbsentDiscriminatorIsRefused() {
        assertThatThrownBy(() -> new ReportSubmissionOutcome(null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("outcome");
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
