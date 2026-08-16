package com.carddemo.transaction.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What a copy-last submission carries: the key that selects the account, and the operator's confirmation.
 *
 * <p><b>Purpose.</b> {@code COPY-LAST-TRAN-DATA} at {@code app/cbl/COTRN02C.cbl} line 471 performs
 * {@code VALIDATE-INPUT-KEY-FIELDS} at line 473 and NOTHING else before it reads the row to copy. It then
 * fills the eleven data fields itself, at lines 481 to 492, and only afterwards performs
 * {@code PROCESS-ENTER-KEY} at line 495, which is where those now-populated fields are validated. So the
 * data fields are an OUTPUT of this action and not an input to it, and this shape carries the three members
 * the reference actually reads on the way in.
 *
 * <p>⚠️ Refactoring Rationale: this shape exists because the operation used to be declared over
 * {@link TransactionAddRequest}, whose eleven data members are each {@code @NotBlank} -- so the copy action
 * was reachable only from a screen that was ALREADY FULLY FILLED IN. That is the opposite of what the key
 * exists for: an operator presses it to fill the screen, and pressing it on a blank screen was refused with
 * the first blank field's own sentence before any request was sent. The published document said as much
 * without the consequence being noticed, requiring "eleven data members" of a body whose eleven data
 * members the service was about to overwrite.
 *
 * <p>Alternatives Considered: keeping the shared request shape and having the browser send placeholder
 * values for the eleven members to satisfy it. Rejected because it puts invented data on the wire -- values
 * an operator never keyed, validated as though they had, and distinguishable from real input by nothing --
 * and because a placeholder that happened to pass validation would be written if the service's overwrite
 * were ever to regress. Also considered: relaxing the eleven members to optional on the shared shape.
 * Rejected because they are genuinely required by the CAPTURE operation, and one schema cannot state two
 * different obligations for two operations.
 *
 * <p>Assumptions: the confirmation is carried, so the reference's fall-through at line 495 is preserved --
 * a copy submitted with an affirmative answer already keyed copies AND writes in one turn, exactly as
 * pressing PF5 on a confirmed screen does. Dropping the member would have split one reference turn into
 * two.
 *
 * <p>Assumptions: the key pair's rule is the SHARED one. This record implements
 * {@link TransactionKeySelection} and carries {@link TransactionAddRequest.AtLeastOneKey}, so the refusal
 * for a submission carrying neither key is decided by the same validator the capture operation uses --
 * which is right, because it is the same paragraph of the same program for both.
 *
 * <p>Assumptions: the two key members reuse the widths, patterns and sentences declared on
 * {@link TransactionAddRequest} rather than restating them. They are the same two screen fields, and a
 * second declaration is how two operations come to refuse different values for one control.
 *
 * @param accountId the account identifier the copy is keyed on, from {@code ACTIDINI PIC X(11)} at line 60
 *     of {@code app/cpy-bms/COTRN02.CPY}; the first of the two alternatives, at most eleven characters and
 *     digits-only when present, and the one that resolves first when both are supplied
 * @param cardNumber the card number the copy is keyed on, from {@code CARDNINI PIC X(16)} at line 66 of
 *     the same map; the second alternative, at most sixteen characters and digits-only when present
 * @param confirmation the operator's confirmation, from {@code CONFIRMI PIC X(1)} at line 138 of the map;
 *     at most one character and restricted to the characters the reference recognises, null or blank on the
 *     turn that only copies
 */
@TransactionAddRequest.AtLeastOneKey
public record CopyLastRequest(
    @Size(max = TransactionAddRequest.ACCOUNT_ID_WIDTH)
    @Pattern(regexp = TransactionAddRequest.ACCOUNT_ID_DIGITS,
        message = TransactionAddRequest.ACCOUNT_ID_NOT_NUMERIC)
    String accountId,
    @Size(max = TransactionAddRequest.CARD_NUMBER_WIDTH)
    @Pattern(regexp = TransactionAddRequest.CARD_NUMBER_DIGITS,
        message = TransactionAddRequest.CARD_NUMBER_NOT_NUMERIC)
    String cardNumber,
    @Size(max = TransactionAddRequest.CONFIRM_WIDTH)
    @Pattern(regexp = TransactionAddRequest.CONFIRM_VALUES,
        message = TransactionAddRequest.CONFIRM_INVALID_VALUE)
    String confirmation) implements TransactionKeySelection {

    /**
     * Renders this submission WITHOUT the key it carries.
     *
     * <p>Purpose. An account identifier and a card number are both prohibited from a diagnostic rendering
     * by {@code docs/architecture/observability.md}, and a card number is a primary account number. The
     * compiler-generated rendering would have written whichever of the two the operator keyed into any log
     * line or assertion message that touched this record.</p>
     *
     * <p>Assumptions: WHICH key was supplied is rendered rather than the key itself, because that is the
     * one thing a reader needs -- it names the arm the service took, and the reference's two arms produce
     * two different failure sentences. The confirmation is rendered because it is a single character from a
     * closed domain and it decides whether the turn wrote.</p>
     *
     * @return a rendering naming the key arm and the confirmation, with neither identifier included; never
     *     {@code null}
     */
    @Override
    public String toString() {
        return "CopyLastRequest[keyedBy="
                + (this.accountId == null || this.accountId.isBlank() ? "cardNumber" : "accountId")
                + ", confirmation=" + this.confirmation + ']';
    }
}
