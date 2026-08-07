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
 * @param submitted whether a run was accepted for execution; {@code false} for a deliberate
 *     cancellation and {@code true} otherwise
 * @param message a sentence for a person to read, at most the 78 characters the message field of the
 *     report-request map declares at {@code app/cpy-bms/CORPT00.CPY} L120; never {@code null}
 * @param submission the accepted run, or {@code null} when {@code submitted} is {@code false},
 *     because a cancelled request has no run to describe
 */
public record ReportSubmissionOutcome(
        boolean submitted,
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
     * @throws NullPointerException if {@code message} is {@code null}
     * @throws IllegalArgumentException if {@code message} exceeds its declared width, or if the flag
     *     and the run description disagree
     */
    public ReportSubmissionOutcome {
        Objects.requireNonNull(message, "message");
        if (message.length() > MESSAGE_WIDTH) {
            throw new IllegalArgumentException(
                    "message exceeds " + MESSAGE_WIDTH + " characters");
        }

        // WHY : Assumptions: the flag and the run description are checked against each other rather
        //       than being allowed to disagree, because a client reads one of them and not both. A
        //       body claiming a submission while carrying no handle, or carrying a handle while
        //       claiming none, would be read differently by two conforming clients, and neither
        //       reading would be wrong. Refusing the pair at construction removes the ambiguity from
        //       the wire instead of documenting a precedence rule for it.
        if (submitted && submission == null) {
            throw new IllegalArgumentException(
                    "a submitted outcome must carry the accepted run it describes");
        }
        if (!submitted && submission != null) {
            throw new IllegalArgumentException(
                    "a cancelled outcome must not carry a run description");
        }
    }

    /**
     * Builds the outcome of an accepted run.
     *
     * @param accepted the description of the accepted run; must not be {@code null}
     * @param message the sentence a caller displays; must not be {@code null}
     * @return an outcome reporting the run as submitted
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code message} exceeds its declared width
     */
    public static ReportSubmissionOutcome accepted(
            ReportSubmissionResponse accepted, String message) {
        Objects.requireNonNull(accepted, "accepted");
        return new ReportSubmissionOutcome(true, message, accepted);
    }

    /**
     * Builds the outcome of a deliberate cancellation.
     *
     * @param message the sentence a caller displays; must not be {@code null}
     * @return an outcome reporting that nothing was submitted
     * @throws NullPointerException if {@code message} is {@code null}
     * @throws IllegalArgumentException if {@code message} exceeds its declared width
     */
    public static ReportSubmissionOutcome cancelled(String message) {
        return new ReportSubmissionOutcome(false, message, null);
    }
}
