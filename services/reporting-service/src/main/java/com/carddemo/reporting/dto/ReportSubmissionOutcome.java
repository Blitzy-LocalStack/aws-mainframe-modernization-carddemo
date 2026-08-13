package com.carddemo.reporting.dto;

import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * The outcome of one report-submission request, discriminating an accepted run from a deliberate
 * cancellation without treating either as a failure.
 *
 * <p>This type exists because the two outcomes the confirmation gate produces are both successes,
 * and only one of them has a run to describe. The reference program cannot draw that distinction:
 * inside {@code SUBMIT-JOB-TO-INTRDR}, which opens at {@code app/cbl/CORPT00C.cbl} L462, the branch
 * taken for {@code N} or {@code n} runs from L480 to L483 and sets the error flag with no message
 * text and no cursor move, while the branch for an unrecognised answer at L484 to L493 supplies
 * both. At flag level a deliberate cancel is therefore indistinguishable from a validation failure,
 * and the screen redisplays cleared with no feedback about which of the two occurred. That flag is
 * set in the cancel branch for one purpose only, to suppress the success block at L445 to L456,
 * which is gated on the flag being off.
 *
 * <p>Refactoring Rationale: the target splits the two across this one body rather than across two
 * status codes with two shapes. A cancel answers HTTP 200 with {@code submitted} false and no
 * {@code submission}; a validation failure answers HTTP 400 with the shared problem body and its
 * per-field array. That preserves the suppression the flag achieved while giving a caller the
 * feedback a single flag could not carry, and it keeps a cancel out of the error channel, where a
 * client would otherwise have to inspect a message to learn that nothing had gone wrong. The
 * decision is registered once at {@code com.carddemo.reporting.api} and is cited here rather than
 * restated.
 *
 * <p>Alternatives Considered: answering a cancel with HTTP 204 and no body at all, which needs no
 * type. Rejected because a 204 carries no correlation identity in a body a caller may archive, and
 * because it makes a cancel indistinguishable from an accepted run whose description a proxy
 * stripped -- the very conflation the reference already suffers and this shape exists to remove.
 *
 * <p>Alternatives Considered: reusing {@link ReportSubmissionResponse} alone and letting a cancel
 * return it with an empty orchestration handle. Rejected because that component is declared
 * {@code @NotBlank} on that type, so an empty handle is not a value it may carry; relaxing the
 * constraint to admit one would remove the guarantee that a returned handle is observable, which is
 * the only reason a caller reads it.
 *
 * @param outcome which of the three turns this was: {@code STARTED} when a run was accepted,
 *     {@code DECLINED} when the caller answered no, and {@code UNANSWERED} when the confirmation has not
 *     been answered yet
 * @param message a sentence for a person to read, at most the 78 characters the message field of the
 *     report-request map declares at {@code app/cpy-bms/CORPT00.CPY} L120, or {@code null} on a
 *     deliberate cancellation, which the reference answers with a cleared message line
 * @param submission the accepted run, or {@code null} for either turn that started nothing, because a
 *     request that was declined or is still being confirmed has no run to describe
 */
public record ReportSubmissionOutcome(
        Outcome outcome,
        @Size(max = MESSAGE_WIDTH) String message,
        ReportSubmissionResponse submission) {

    /**
     * Declared width of the message this outcome carries.
     *
     * <p>Assumptions: 78 is the width of the report-request map's own message field at
     * {@code app/cpy-bms/CORPT00.CPY} L120, and not the 75 of the shared error message. The two
     * differ and are not reconciled: the shared width governs the problem body a failure returns,
     * whereas this member is the screen line a successful turn writes, and the map declares the
     * wider carrier for it.
     */
    public static final int MESSAGE_WIDTH = 78;

    /**
     * Validates the pairing of the outcome flag with the presence of a run description.
     *
     * @throws IllegalArgumentException if {@code message} is blank, if it exceeds its declared width,
     *     or if the flag and the run description disagree
     */
    public ReportSubmissionOutcome {
        // WHY : Refactoring Rationale: a null message is ADMITTED, where an earlier revision refused
        //       one, and the refusal was the reason a target-invented sentence existed at all. The
        //       cancel branch at app/cbl/CORPT00C.cbl L480 to L483 performs INITIALIZE-ALL-FIELDS,
        //       whose statement at L636 to L646 clears WS-MESSAGE along with every input field, and
        //       then re-displays -- so the operator is shown a blank message line. The published
        //       schema records the same thing, typing this member as string-or-null and saying "Null
        //       on a deliberate cancellation". A constructor that refused null made that outcome
        //       unrepresentable, so a sentence had to be written for it, and the sentence written was
        //       not the reference's because the reference has none. Admitting null removes the cause
        //       rather than the symptom.
        // WHY : Alternatives Considered: representing the cleared line as the EMPTY string instead,
        //       which is closer to the 78 spaces the screen field actually holds. Rejected because
        //       the published schema names null and a client reads the schema, and because an empty
        //       string and a cleared field are indistinguishable to a reader of the wire while null
        //       and an empty string are not -- so the schema's choice is the one that survives being
        //       read by something other than this code.
        // WHY : Assumptions: null and blank are DIFFERENT states and only null is admitted. Blank is
        //       what a fixed-width screen field holds once it has been cleared, and it would reach a
        //       client as a message present and empty -- an empty line rather than no line -- which is
        //       the same distinction the paragraph above draws against the empty string. It is refused
        //       rather than normalised to null, because a caller passing spaces has a field it believes
        //       it filled, and silently turning that into absence hides the mistake at the one boundary
        //       able to report it. The reference draws the same line with the low-values sentinel on the
        //       shared return message at app/cpy/CVCRD01Y.cpy L30.
        if (message != null && message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank: the cancellation branch"
                    + " carries no message at all, which is null, and a blank string of the screen"
                    + " field's width is a message present and empty rather than absent");
        }

        if (message != null && message.length() > MESSAGE_WIDTH) {
            throw new IllegalArgumentException(
                    "message exceeds " + MESSAGE_WIDTH + " characters");
        }

        // WHY : Assumptions: the discriminator and the run description are checked against each other
        //       rather than being allowed to disagree, because a client reads one of them and not both. A
        //       body reporting a started run while carrying no handle, or carrying a handle while
        //       reporting that nothing started, would be read differently by two conforming clients and
        //       neither reading would be wrong. Refusing the pair at construction removes the ambiguity
        //       from the wire instead of documenting a precedence rule for it.
        // WHY : Refactoring Rationale: the check is now driven by the three-valued discriminator rather
        //       than by a boolean, so the two turns that start nothing are checked SEPARATELY -- and the
        //       unanswered turn is the one that carries a sentence while the declined turn does not,
        //       which a single not-started arm could not have expressed.
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (outcome == Outcome.STARTED && submission == null) {
            throw new IllegalArgumentException(
                    "a started outcome must carry the accepted run it describes");
        }
        if (outcome != Outcome.STARTED && submission != null) {
            throw new IllegalArgumentException(
                    "an outcome that started nothing must not carry a run description");
        }
        if (outcome == Outcome.DECLINED && message != null) {
            throw new IllegalArgumentException(
                    "a declined outcome carries no sentence, because the reference clears the message "
                            + "line at app/cbl/CORPT00C.cbl L480 to L483");
        }
        if (outcome == Outcome.UNANSWERED && message == null) {
            throw new IllegalArgumentException(
                    "an unanswered outcome must carry the confirmation prompt, which is the question "
                            + "the reference composes at app/cbl/CORPT00C.cbl L464 to L474");
        }
    }

    /**
     * Builds the outcome of an accepted run.
     *
     * <p>Assumptions: the message stays MANDATORY on this factory even though the component now admits
     * null, and the asymmetry is the point. The reference composes a sentence for a started run at
     * {@code app/cbl/CORPT00C.cbl} L447 to L454 and moves the green attribute into the message colour, so
     * a submitted outcome that carried none would be dropping a string the reference emits -- the mirror
     * of the fault admitting null was needed to fix.
     *
     * @param accepted the description of the accepted run; must not be {@code null}
     * @param message the sentence a caller displays, which the reference composes for this branch; must
     *     not be {@code null}
     * @return an outcome reporting the run as submitted
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code message} is blank or exceeds its declared width
     */
    public static ReportSubmissionOutcome accepted(
            ReportSubmissionResponse accepted, String message) {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(message, "message");
        return new ReportSubmissionOutcome(Outcome.STARTED, message, accepted);
    }

    /**
     * Builds the outcome of a deliberate cancellation, which carries no sentence.
     *
     * <p>Assumptions: this factory takes no argument, and that is the whole of its contract. The
     * reference's cancel branch at {@code app/cbl/CORPT00C.cbl} L480 to L483 clears the message line
     * and re-displays, so there is no sentence to pass and no caller may supply one. A factory
     * accepting a message would let one be invented at each call site, which is exactly what
     * happened before this pair replaced the single one.</p>
     *
     * @return an outcome reporting that nothing was submitted and saying nothing about it
     */
    public static ReportSubmissionOutcome cancelled() {
        return new ReportSubmissionOutcome(Outcome.DECLINED, null, null);
    }

    /**
     * Builds the outcome of a turn on which the confirmation has not been answered yet.
     *
     * <p>Assumptions: this is a distinct factory from {@link #cancelled()} even though both produce
     * the same two members, because the two outcomes differ in the one member that varies and
     * collapsing them into one factory with a nullable argument would make an omitted argument mean
     * "cancelled" by accident. The reference reaches this turn at L464 to L474 and composes a PROMPT
     * naming the report, which is a question rather than a report of anything having happened.</p>
     *
     * @param prompt the reference's confirmation prompt, as the caller composed it; must not be
     *     {@code null}, because a prompt that said nothing would leave the caller with no question
     * @return an outcome reporting that nothing was submitted and carrying the prompt
     * @throws NullPointerException if {@code prompt} is {@code null}
     * @throws IllegalArgumentException if {@code prompt} exceeds its declared width
     */
    public static ReportSubmissionOutcome unanswered(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        return new ReportSubmissionOutcome(Outcome.UNANSWERED, prompt, null);
    }

    /**
     * Which of the three turns a submission request produced.
     *
     * <p>⚠️ Refactoring Rationale: this REPLACES a boolean {@code submitted} member, and the replacement
     * closes a defect a review found. There are three outcomes -- a run was started, the caller declined,
     * or the confirmation has not been answered yet -- and the reference itself distinguishes all three,
     * at L478, L480 and L464 of {@code app/cbl/CORPT00C.cbl}. A boolean cannot carry three states, so the
     * unanswered turn was reported as {@code submitted=false} exactly like a decline, leaving the two
     * distinguishable only by whether {@code message} happened to be null. The browser client duly read
     * the status code alone and labelled every non-created response {@code DECLINED}, so a caller that had
     * merely not answered yet was told it had cancelled. The same argument is already recorded one layer
     * down, on the service's own three-valued confirmation enum; this is the response side of it.
     *
     * <p>Assumptions: the discriminator is a MEMBER rather than only an HTTP status. The status still
     * distinguishes a started run (201) from a turn that started nothing (200), which a client may switch
     * on cheaply; what a status cannot do is separate the two turns that share it, and inferring that from
     * the presence of another member is precisely the inference that produced the wrong label.
     *
     * <p>Alternatives Considered: keeping {@code submitted} and adding this beside it. Rejected because
     * two members would then encode one fact, and nothing would stop them disagreeing -- a body reporting
     * {@code submitted=true} with {@code outcome=DECLINED} would be admitted by the contract and mean
     * nothing.
     */
    public enum Outcome {

        /** A run was accepted for execution, per L478 and L479 of the reference. */
        STARTED,

        /** The caller answered no; nothing ran and nothing is reported, per L480 to L483. */
        DECLINED,

        /** The confirmation has not been answered; the caller is re-prompted, per L464 to L474. */
        UNANSWERED
    }
}
