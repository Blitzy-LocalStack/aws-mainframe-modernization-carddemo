package com.carddemo.transaction.dto;

import com.carddemo.common.money.Money;
import com.carddemo.common.security.CardNumberMasker;

import java.util.Objects;

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
 * {@code ui/src/api/transactions.ts}. All three carry the amount, the discriminator, the resolved
 * account identifier, the resolved card number MASKED to its last four digits and the opaque binding
 * token a confirming turn presents. None of them carries a TRANSACTION identifier, because no row was
 * written, and none of them carries an unmasked primary account number.</p>
 *
 * <p>⚠️ Refactoring Rationale: the overview above stated that no shape here carried an identifier at
 * all, which was false in both directions once the resolved key pair was added: two of the components
 * ARE identifiers. It is corrected rather than deleted, because the fact it was reaching for is real
 * and load-bearing -- the absence of a transaction identifier is what distinguishes this shape from
 * {@link TransactionAddResponse} and what its published schema closes its object around.</p>
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
 * @param resolvedAccountId the account identifier the key resolution settled on, zero-filled to its
 *     eleven declared positions; never {@code null}. ⚠️ It is a component of THIS shape, and of the
 *     copied block that was authored to hold it, because the reference resolves and repaints both key
 *     fields on EVERY turn: {@code app/cbl/COTRN02C.cbl} L166 performs {@code VALIDATE-INPUT-KEY-FIELDS}
 *     for the Enter arm and L473 for the copy arm, and L221 of that paragraph moves the cross-reference's
 *     account identifier into {@code ACTIDINI} on the card key path. Publishing it only on the copy turn
 *     left an ordinary capture unable to show the operator the key the row would carry
 * @param resolvedCardNumberMasked the card number the cross-reference resolved, rendered as twelve
 *     asterisks followed by its last four digits by
 *     {@link com.carddemo.common.security.CardNumberMasker#mask(String)}; never {@code null}. ⚠️ It is
 *     the counterpart of the above: L209 moves the entry's card number over the card field on the account
 *     key path, after which the reference re-sends the screen, so by the time the confirming keystroke is
 *     made the terminal field holds that value and the operator has seen which card the row will carry.
 *     Refactoring Rationale: this component published the SIXTEEN-DIGIT number, arguing that a suffix
 *     cannot distinguish two cards on one account and that the sensitive-data prohibition covers durable
 *     diagnostics alone. Both halves were wrong. AAP section 0.4.1.9 masks a primary account number in
 *     every response except the administrative card-detail endpoint, which this operation is not, and
 *     this contract's own {@code CardNumber} schema states that the unmasked form "appears on requests
 *     only; responses carry the masked form" -- so the body contradicted the document that declares it.
 *     The comparison the full number was published to support is now made by the SERVICE, against
 *     {@link #confirmationToken}, which is a stronger guarantee than asking a client to compare digits
 *     it should never have held
 * @param confirmationToken the opaque, authenticated value a confirming submission presents to prove it
 *     is confirming THIS resolution; never {@code null}. ⚠️ Assumptions: it is minted by
 *     {@code com.carddemo.common.web.CursorToken}, which seals the resolved card number under the
 *     deployment key, binds it to the calling operator and expires it, so a client can neither read the
 *     number out of it nor manufacture one for a card it was never shown. Alternatives Considered:
 *     publishing the masked number alone and letting the confirming turn re-resolve from the submitted
 *     key. Rejected because the cross-reference can change between the two stateless requests, so a
 *     confirmation would silently write against a card the operator was never shown -- the very hazard
 *     the withdrawn full-number publication was defending against
 * @param copied the ten data members a copy-last turn lifted out of the stored row, paired with that
 *     row's identifier, or {@code null} on the ordinary capture operation, which copies nothing. See
 *     {@link CopiedTransactionData} for why the eleventh copied value is the {@link #amount()} above and
 *     for why the member is carried here rather than on a third response shape
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
    String returnMessage,
    // WHY : Assumptions: the two resolved key values are components of this shape and NOT of the copied
    //       block below, so that every withheld answer carries them. The reference repaints both key
    //       fields on every turn (app/cbl/COTRN02C.cbl L166 and L473 both perform
    //       VALIDATE-INPUT-KEY-FIELDS, whose L209 and L221 write them) and then re-sends the screen; the
    //       copied block is attached to the copy turn alone, so holding them there published the repaint
    //       on one of the two turns that perform it.
    String resolvedAccountId,
    // WHY : Assumptions: the masked rendering is a component of this shape and the sixteen-digit value
    //       is a component of nothing. The masking is applied at the one construction site below rather
    //       than by every caller, so no path can build this shape around an unmasked number by omitting
    //       a step; the factory takes the resolved number and this component can only ever hold what
    //       CardNumberMasker returned for it.
    String resolvedCardNumberMasked,
    // WHY : Assumptions: the binding token is a component rather than a response header, because a
    //       client has to carry it back on the NEXT request body and a header would put one half of one
    //       exchange in a different place from the other. Trade-offs: it lengthens the body by a bounded
    //       token in exchange for the confirming write being bound to the previewed resolution without
    //       any primary account number reaching the browser.
    String confirmationToken,
    // WHY : Assumptions: the member is nullable rather than optional, and it is the fourth component
    //       rather than a fourth shape. Both choices are argued at CopiedTransactionData: the
    //       inclusion policy writes every component on every response, so an absent member is not a
    //       state this service can produce, and splitting one nullable member into a third permitted
    //       implementation of the sealed outcome would have duplicated the other three members, the
    //       status mapping and the client path.
    CopiedTransactionData copied) implements TransactionAddOutcome {

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
     * @param resolvedAccountId the account identifier the key resolution settled on; must not be
     *     {@code null}
     * @param resolvedCardNumber the card number the cross-reference resolved, in full; must not be
     *     {@code null}. It is MASKED here and the unmasked value is not retained on the returned shape
     * @param confirmationToken the opaque binding token a confirming submission presents; must not be
     *     {@code null}
     * @return the shape with its discriminator fixed to {@link #CAPTURE_WITHHELD}, its card number
     *     masked and no copied source, never {@code null}
     * @throws NullPointerException if {@code resolvedCardNumber} is {@code null}
     * @throws IllegalArgumentException if {@code resolvedCardNumber} is not sixteen digits, raised by
     *     the masker, which is a service fault rather than a caller error
     */
    public static TransactionAddPreview prompting(Money amount, String returnMessage,
            String resolvedAccountId, String resolvedCardNumber, String confirmationToken) {

        // WHY : Assumptions: the masking happens HERE and not in the mapper, because this shape is
        //       composed directly by the capture path rather than converted from a stored row -- the
        //       service's own note records why -- so there is no mapper on this path to place it in.
        //       Putting it at the single construction site is what makes the component's contract
        //       true by construction rather than by every caller remembering.
        return new TransactionAddPreview(amount, CAPTURE_WITHHELD, returnMessage, resolvedAccountId,
                CardNumberMasker.mask(resolvedCardNumber), confirmationToken, null);
    }

    /**
     * Returns this preview carrying the row a copy-last turn lifted its data members out of.
     *
     * <p>Purpose: the copy-last operation reaches its answer THROUGH the ordinary capture path -- the
     * reference performs {@code PROCESS-ENTER-KEY} at line 495 of {@code app/cbl/COTRN02C.cbl} once the
     * copy block has filled the fields -- so the preview it returns is built by {@link #prompting} and
     * knows nothing about the copy. This method is where the copy operation attaches what it copied,
     * after the shared path has normalised the amount and composed the prompt.
     *
     * <p>Refactoring Rationale: a wither rather than a second factory taking five arguments. The shared
     * path owns the amount and the sentence and must keep owning them: a factory that accepted all four
     * components would let the copy operation state a prompt of its own, and the two operations answering
     * the withheld turn with two different sentences is precisely the divergence the reference does not
     * have.
     *
     * <p>Assumptions: the discriminator is carried across unchanged rather than restated. Nothing about
     * attaching a copied source writes a row, and re-fixing the constant here would be a second place for
     * one published value to be declared.
     *
     * @param source the copied screen state and the identifier of the row it came from; must not be
     *     {@code null}, because a copy turn that reached this point read a row
     * @return a preview identical to this one but naming the copied row, never {@code null}
     * @throws NullPointerException if {@code source} is {@code null}
     */
    public TransactionAddPreview withCopiedSource(CopiedTransactionData source) {
        Objects.requireNonNull(source, "source must not be null");
        return new TransactionAddPreview(this.amount, this.written, this.returnMessage,
                this.resolvedAccountId, this.resolvedCardNumberMasked, this.confirmationToken, source);
    }

    /**
     * Renders this preview WITHOUT the amount it quotes.
     *
     * <p>Purpose. The amount is a monetary value and is prohibited from a diagnostic rendering by
     * {@code docs/architecture/observability.md} L1093 to L1112. Four of this shape's seven components
     * carry restricted data -- the amount, the two resolved keys and the sealed binding token -- so the
     * compiler-generated rendering would have disclosed a money figure, an account number and a sealed
     * card number in one line.</p>
     *
     * <p>Assumptions: the amount is omitted rather than rounded, bucketed or reported as a digit count.
     * Each of those is an abbreviation of a prohibited value, which the rule's first clause forbids for
     * a reason that applies here in particular: this shape exists to be confirmed, so a bucketed figure
     * in a log would be read as the figure that was confirmed.</p>
     *
     * <p>Trade-offs: the three remaining components are kept and they answer the question this shape is
     * logged for -- the discriminator fixed to false proves the turn captured nothing, the sentence is
     * the verbatim confirmation prompt, and the copied source says which kind of turn it was. What is
     * lost is the ability to see the normalised figure a client would be confirming, which the request
     * body and the subsequent capture both carry, and the ability to tell from a log which account a
     * withheld turn was about -- recoverable through the correlation identifier on the same records.</p>
     *
     * <p>Assumptions: the copied source is rendered through its OWN {@code toString}, which carries the
     * identifier of the row that was copied and none of the ten values lifted out of it, for the same
     * rule and the same reason. Omitting the member entirely was rejected: whether a preview names a
     * copied row is the one thing that distinguishes a copy turn from a capture turn in a log, and it is
     * a null-or-not fact rather than a business value.</p>
     *
     * <p>⚠️ Refactoring Rationale: NEITHER resolved key is rendered, where the account identifier was.
     * The note this replaces argued that an account identifier is "a business key this service logs on
     * every request through its correlation fields". That premise is false and is the reason the
     * disclosure was invisible: {@code docs/architecture/observability.md} names account identifiers
     * among the values a durable diagnostic may not hold, and this context's own client module keeps them
     * out of a request LINE for exactly that reason -- an access log outlives the request. The two
     * resolved keys are therefore both omitted, and what remains identifies the TURN rather than the
     * account.</p>
     *
     * <p>Assumptions: the binding token is omitted too, and that is a confidentiality decision rather
     * than a length one. It seals the resolved card number, so a token in a durable log is a sealed
     * primary account number in a durable log -- recoverable by anyone who also holds the deployment key,
     * which is a smaller step than it looks in an environment where logs and secrets are read by
     * overlapping sets of people.</p>
     *
     * <p>Assumptions: the masked card number is omitted as well, even though publishing it in a response
     * body is permitted. A rendering exists to answer "what happened on this turn", and a four-digit
     * suffix answers no question a reader of this log has; carrying it would put a fragment of a primary
     * account number into a durable store for no diagnostic gain.</p>
     *
     * @return a rendering carrying the written discriminator, the return message and the copied row's
     *     identifier, with the amount, both resolved keys, the binding token and every copied value
     *     omitted entirely; never {@code null}
     */
    @Override
    public String toString() {
        return "TransactionAddPreview[written=" + this.written
                + ", returnMessage=" + this.returnMessage
                + ", copied=" + this.copied + ']';
    }
}
