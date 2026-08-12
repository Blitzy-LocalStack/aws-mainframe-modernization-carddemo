package com.carddemo.transaction.dto;

import com.carddemo.common.money.Money;

/**
 * What a capture would store, returned on a turn that wrote nothing.
 *
 * <p><b>Purpose.</b> {@code app/cbl/COTRN02C.cbl} evaluates the confirmation at line 169 with three
 * arms. The affirmative arm at lines 170 to 172 performs the add. The shared arm at lines 173 to 176 --
 * reached for {@code 'N'}, {@code 'n'}, spaces or low values -- moves the prompt
 * {@code 'Confirm to add this transaction...'} at line 178 and re-sends the screen without writing.
 * The remaining arm at line 182 refuses a value the field's domain does not admit and is a rejection
 * rather than a turn. This shape answers the middle arm.</p>
 *
 * <p>This record is the Java form of the {@code TransactionAddPreview} schema in
 * {@code openapi/transaction-api.yaml} and of the {@code TransactionAddPreview} interface in
 * {@code ui/src/api/transactions.ts}. All three carry the amount and the discriminator and none of them
 * carries an identifier, because no row was written.</p>
 *
 * <p>⚠️ Refactoring Rationale: this record exists because {@link TransactionAddResponse} was being
 * returned on the unconfirmed turn with its identifier left null. That body was invalid against its own
 * published schema twice over: the preview schema sets {@code additionalProperties: false} and declares
 * no {@code transactionId}, so a strict client rejected the body outright, and it marks {@code written}
 * required, which the response record does not carry at all -- so a client reading the body alone had no
 * member to distinguish a prompt from a capture and had to infer it from the absence of an identifier.
 * The two outcomes are two closed objects in the contract and are therefore two types here.
 *
 * <p>Assumptions: the amount reported is the NORMALISED one, not the value as submitted, and that is
 * transcription rather than addition. Line 383 converts the keyed characters with
 * {@code FUNCTION NUMVAL-C}, line 385 moves the result into {@code WS-TRAN-AMT-E PIC +99999999.99}
 * declared at line 59, and line 386 moves that edited value back over the input field -- so the operator
 * sees a canonical rendering before the confirmation at line 169 is evaluated at all. Echoing the
 * submitted characters instead would show the caller something the reference has already replaced.
 *
 * <p>Assumptions: the amount is required rather than nullable, because this turn is reached only after
 * the whole validation chain has passed. The reference performs its eight validation blocks across lines
 * 235 to 437 and reaches the confirmation evaluation at line 169 only for a submission that cleared
 * them, so a preview always has a normalised amount to report.
 *
 * <p>Alternatives Considered: Lombok and MapStruct, both rejected for this package as a whole; the
 * package charter carries the reasoning, which turns on generated members being undocumentable and on
 * copybook-to-transfer-object mapping being non-mechanical. A Java 21 record with hand-written
 * construction is what replaces them.
 *
 * @param amount the normalised amount a confirmed submission would be captured at, from
 *     {@code TRAN-AMT PIC S9(09)V99} at line 10 of {@code app/cpy/CVTRA05Y.cpy}; exact at a scale of
 *     two, serialised as a quoted decimal string and never as a JSON number, and never {@code null}
 * @param written always {@code false} on this shape, discriminating a prompt from the capture shape the
 *     same contract publishes with the value fixed to {@code true}; stated as a value rather than left
 *     to be inferred from the status code, so that a body logged or replayed on its own cannot be
 *     mistaken for a capture
 * @param returnMessage the prompt asking for a confirmation, verbatim
 *     {@code 'Confirm to add this transaction...'} from line 178, carried through
 *     {@code CCARD-RETURN-MSG PIC X(75)} at line 29 of {@code app/cpy/CVCRD01Y.cpy}; never
 *     {@code null} on this shape, because the arm that produces it always sets the sentence
 */
public record TransactionAddPreview(
    // WHY : Assumptions: the component order below is the property order of the TransactionAddPreview
    //       schema in openapi/transaction-api.yaml. Records expose their components positionally through
    //       the canonical constructor, so matching the published order keeps a hand-written construction
    //       call readable against the document it implements; TransactionApiContractTest asserts the
    //       names and the set, not the order.
    // WHY : Assumptions: MoneyModule binds its serialiser to this exact type, so the declared type is
    //       what selects the quoted-string wire form. Substituting a bare decimal here compiles and runs
    //       and silently emits a JSON number instead, which transformation rule T3 forbids.
    Money amount,
    // WHY : Assumptions: the discriminator is a component rather than a constant accessor so that it
    //       appears in the serialised body, which is what the published const: false describes. Its
    //       value is fixed at the factory below rather than chosen by a caller.
    boolean written,
    String returnMessage) implements TransactionAddOutcome {

    /**
     * The value {@link #written} carries on every instance of this shape.
     *
     * <p>Assumptions: declared as a named constant rather than written as a literal at the factory,
     * because the published schema fixes it with {@code const: false} and one declaration is what keeps
     * the code from disagreeing with the document.</p>
     */
    public static final boolean CAPTURE_WITHHELD = false;

    /**
     * Builds the answer for the turn that asks for a confirmation without writing.
     *
     * <p>Refactoring Rationale: the discriminator is set here rather than accepted as an argument. A
     * component whose contract is that it always carries one value is a component a caller can get
     * wrong, and getting it wrong would emit a body claiming a capture from a path that wrote nothing.
     * Fixing it at the one construction site makes the published constant true by construction rather
     * than by review.</p>
     *
     * @param amount the normalised amount a confirmed submission would be captured at; must not be
     *     {@code null}
     * @param returnMessage the prompt from line 178 of {@code app/cbl/COTRN02C.cbl}; must not be
     *     {@code null}
     * @return the shape with its discriminator fixed to {@link #CAPTURE_WITHHELD}, never {@code null}
     */
    public static TransactionAddPreview prompting(Money amount, String returnMessage) {
        return new TransactionAddPreview(amount, CAPTURE_WITHHELD, returnMessage);
    }
}
