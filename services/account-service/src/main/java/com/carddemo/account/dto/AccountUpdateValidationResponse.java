package com.carddemo.account.dto;

import com.carddemo.common.error.ApiError;
import java.util.List;
import java.util.Objects;

/**
 * The verdict of the account-update edits, reported without writing anything.
 *
 * <h2>Why this operation exists at all</h2>
 *
 * <p>Assumptions: the baseline's account-update screen is a TWO-TURN dialogue and the first turn is not
 * cosmetic. {@code 2000-DECIDE-ACTION}'s show-details arm reads
 * {@code IF INPUT-ERROR OR NO-CHANGES-DETECTED} and performs {@code CONTINUE}, leaving the action where
 * it is, and only otherwise sets {@code ACUP-CHANGES-OK-NOT-CONFIRMED} -- at
 * {@code app/cbl/COACTUPC.cbl} L2584 to L2591. So the edits have already run when that decision is
 * taken, and the confirmation prompt {@code Changes validated.Press F5 to save} is a claim the program
 * has earned. Only the later turn performs {@code 9600-WRITE-PROCESSING}.</p>
 *
 * <p>Refactoring Rationale: without this operation a browser client had two choices and both were
 * wrong. It could advance to the confirmation state having validated nothing -- displaying a sentence
 * asserting that the edits passed, when the twenty-four rules had not run -- or it could submit the
 * write in order to discover the verdict, which is precisely what the first turn must not do. The
 * screen took the first option. This carries the verdict on its own so the first turn can be truthful.</p>
 *
 * <h2>Why a refusal is a 200 and not a 400</h2>
 *
 * <p>Assumptions: a refused value is the successful ANSWER to the question this operation asks, so the
 * response is a 200 carrying the refusal rather than a 400. The write operation is the opposite case
 * and is deliberately left as it is: there, a refusal means the request could not be carried out, so
 * it is an error status. The distinction is between asking whether a value would be accepted and
 * asking for it to be applied.</p>
 *
 * <p>Trade-offs: a caller must therefore read {@link #inputError()} rather than rely on the status
 * code, which is a small cost paid once in the client. The alternative -- a 400 whose body a client
 * parses for field entries -- was rejected because it makes an ordinary, expected outcome
 * indistinguishable from a malformed request, and because an intermediary is entitled to treat a 4xx
 * as a failure worth logging or retrying differently.</p>
 *
 * @param fieldErrors one entry per refused property, in the order the edits reached them, empty when
 *     every value was accepted; never {@code null}
 * @param message the sentence the screen shows for this verdict, or {@code null} when there is none;
 *     it is the baseline's own wording, including its no-change sentence
 * @param inputError whether any submitted value was refused, which is the baseline's
 *     {@code INPUT-ERROR} condition
 * @param noChangesFound whether the submission matched the stored rows in every compared field, which
 *     is the baseline's {@code NO-CHANGES-DETECTED} condition
 */
public record AccountUpdateValidationResponse(List<ApiError.FieldError> fieldErrors, String message,
        boolean inputError, boolean noChangesFound) {

    /**
     * Seals the entry array so a caller retaining the list it passed cannot alter a published verdict.
     *
     * <p>Assumptions: the copy is defensive rather than tidy, matching the equivalent guard on the
     * service's own verdict type. A record's accessor hands back the same reference it was given, so
     * without the copy a caller could mutate a response after it had been returned.</p>
     */
    public AccountUpdateValidationResponse {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    /**
     * Whether the edits admit advancing to the confirmation turn.
     *
     * <p>Assumptions: this is the baseline's decision expressed once, on the side that owns it, rather
     * than left for each client to recompose from the two flags. The arm advances when NEITHER
     * condition holds -- {@code IF INPUT-ERROR OR NO-CHANGES-DETECTED ... CONTINUE ELSE SET
     * ACUP-CHANGES-OK-NOT-CONFIRMED} at {@code app/cbl/COACTUPC.cbl} L2584 to L2591 -- so a client that
     * recombined them itself could get the polarity wrong in a way nothing would catch.</p>
     *
     * @return {@code true} when nothing was refused and something changed
     */
    public boolean confirmable() {
        return !this.inputError && !this.noChangesFound;
    }

    /**
     * Renders the verdict without its entries, which may name submitted values.
     *
     * <p>Assumptions: the entries are summarised by COUNT and never by content, because a field entry
     * carries the wording of a refusal alongside the property it refers to and this type is logged.
     * The two booleans and the count are what a reader needs to follow a turn.</p>
     *
     * @return a diagnostic description, never {@code null}
     */
    @Override
    public String toString() {
        return "AccountUpdateValidationResponse[inputError=" + this.inputError
                + ", noChangesFound=" + this.noChangesFound
                + ", fieldErrors=" + Objects.requireNonNullElse(this.fieldErrors, List.of()).size()
                + "]";
    }
}
