package com.carddemo.transaction.dto;

import com.carddemo.common.money.Money;

/**
 * What a bill payment would pay, returned on a turn that paid nothing.
 *
 * <p><b>Purpose.</b> {@code app/cbl/COBIL00C.cbl} ends without paying on three of its branches, and
 * this shape answers all three. The confirmation prompt at lines 236 to 239 reports the balance beside
 * {@code 'Confirm to make a bill payment...'} from line 237. The nothing-to-pay advisory at lines 200
 * to 204 reports the balance beside {@code 'You have nothing to pay...'} from line 198. The refusal at
 * lines 178 to 181 performs {@code CLEAR-CURRENT-SCREEN} at line 180 and sets the error flag at line
 * 181, moving no sentence into the message field and leaving no balance on the screen -- so it reports
 * neither.</p>
 *
 * <p>This record is the Java form of the {@code BillPaymentPreview} schema in
 * {@code openapi/transaction-api.yaml} and of the {@code BillPaymentPreview} interface in
 * {@code ui/src/api/transactions.ts}. All three name the balance {@code payableBalance} and none of
 * them carries a transaction identifier, because no transaction was written.</p>
 *
 * <p>⚠️ Refactoring Rationale: this record exists because {@link BillPaymentResponse} was being
 * returned on the non-paying turns with its identifier left null. That body was invalid against its own
 * published schema -- the preview schema sets {@code additionalProperties: false} and does not declare
 * {@code transactionId}, so a strict client rejected it -- and it reported the balance under
 * {@code currentBalance}, a member name the contract and the browser client do not use for this
 * outcome, so a lenient client read no balance at all. The two outcomes are two closed objects in the
 * contract and are therefore two types here.
 *
 * <p>⚠️ Assumptions: the balance is NULLABLE, and the one branch that leaves it absent is the refusal.
 * That branch reaches {@code CLEAR-CURRENT-SCREEN} at line 180 without ever reaching
 * {@code READ-ACCTDAT-FILE} at line 343 -- only lines 177 and 184 do -- so the reference has no balance
 * to report there and clears the field instead of filling it. Reporting one would mean reading an
 * account the reference does not read on that branch, which additionally turns a refusal on an unknown
 * identifier into a not-found answer where the baseline simply clears the screen. Absence is therefore
 * the faithful value, and it is spelled as null rather than as zero: zero is a balance the account
 * might genuinely carry, and the nothing-to-pay branch reports exactly that.
 *
 * <p>Alternatives Considered: a fourth shape for the refusal, carrying no balance member at all.
 * Rejected because it would publish a third closed object for one operation, and a client would then
 * have to distinguish three bodies where the reference distinguishes two outcomes -- paid and not
 * paid. A nullable member on one shape says the same thing with one schema, and the published
 * description records exactly which branch leaves it absent.
 *
 * <p>Assumptions: the account identifier is echoed on every one of the three branches, including the
 * refusal. It is the value the client submitted rather than anything read from storage, so echoing it
 * requires no account interaction, and a client correlating an answer with the form it sent needs it.
 *
 * <p>Alternatives Considered: Lombok and MapStruct, both rejected for this package as a whole; the
 * package charter carries the reasoning, which turns on generated members being undocumentable and on
 * copybook-to-transfer-object mapping being non-mechanical. A Java 21 record with hand-written
 * construction is what replaces them.
 *
 * @param accountId the account the preview was requested for, echoed from the request, from
 *     {@code ACTIDINI PIC X(11)} at line 60 of {@code app/cpy-bms/COBIL00.CPY} and keyed as
 *     {@code CC-ACCT-ID PIC X(11)} at line 34 of {@code app/cpy/CVCRD01Y.cpy}; borne as digit
 *     characters so that a leading zero survives the round trip, and never {@code null}
 * @param payableBalance the balance a confirmed request would pay in full, from
 *     {@code ACCT-CURR-BAL PIC S9(10)V99} at line 7 of {@code app/cpy/CVACT01Y.cpy} and displayed
 *     through {@code CURBALI PIC X(14)} at line 66 of that map; exact at ten integer digits and a
 *     scale of two, serialised as a quoted decimal string and never as a JSON number, and
 *     {@code null} on the refusal branch alone, which clears the screen without reading the account
 * @param paid always {@code false} on this shape, discriminating a turn that paid nothing from the
 *     posted shape the same contract publishes with the value fixed to {@code true}; stated as a value
 *     rather than left to be inferred from the status code, so that a body logged or replayed on its
 *     own cannot be mistaken for a payment
 * @param returnMessage the sentence accompanying the turn, from {@code CCARD-RETURN-MSG PIC X(75)} at
 *     line 29 of {@code app/cpy/CVCRD01Y.cpy}; the confirmation prompt from line 237, the
 *     nothing-to-pay advisory from line 198, or {@code null} on the refusal branch, which emits none
 *     and whose absence is that field's own low-values sentinel at line 30 rather than a blank-filled
 *     string of that width
 */
public record BillPaymentPreview(
    // WHY : Assumptions: the component order below is the property order of the BillPaymentPreview
    //       schema in openapi/transaction-api.yaml. Records expose their components positionally
    //       through the canonical constructor, so matching the published order keeps a hand-written
    //       construction call readable against the document it implements; TransactionApiContractTest
    //       asserts the names and the set, not the order.
    // WHY : Assumptions: CVCRD01Y declares every identifier twice over the same bytes, as characters
    //       at line 34 and as a number at line 36. A numeric component would drop a leading zero on
    //       the way out while still comparing equal on the way in, which is why this is a String.
    String accountId,
    // WHY : Assumptions: MoneyModule binds its serialiser to this exact type, so the declared type is
    //       what selects the quoted-string wire form. Substituting a bare decimal here compiles and
    //       runs and silently emits a JSON number instead, which transformation rule T3 forbids.
    Money payableBalance,
    // WHY : Assumptions: the discriminator is a component rather than a constant accessor so that it
    //       appears in the serialised body, which is what the published const: false describes. Its
    //       value is fixed at the two factories below rather than chosen by a caller.
    boolean paid,
    String returnMessage) implements BillPaymentOutcome {

    /**
     * The value {@link #paid} carries on every instance of this shape.
     *
     * <p>Assumptions: declared as a named constant rather than written as a literal at each factory,
     * because the published schema fixes it with {@code const: false} and a single declaration is what
     * keeps the two factories from disagreeing with the document or with each other.</p>
     */
    public static final boolean PAYMENT_WITHHELD = false;

    /**
     * Builds the answer for a turn that reports the balance without paying it.
     *
     * <p>Purpose: this serves the confirmation prompt at lines 236 to 239 and the nothing-to-pay
     * advisory at lines 200 to 204, which differ only in the sentence they carry.</p>
     *
     * <p>Refactoring Rationale: the discriminator is set here rather than accepted as an argument. A
     * component whose contract is that it always carries one value is a component a caller can get
     * wrong, and getting it wrong would emit a body reporting that money moved from a path that moved
     * none. Fixing it at the construction site makes the published constant true by construction rather
     * than by review.</p>
     *
     * @param accountId the account the preview was requested for, as digit characters; must not be
     *     {@code null}
     * @param payableBalance the balance a confirmed request would pay in full; must not be
     *     {@code null}
     * @param returnMessage the sentence for this turn, being the prompt or the advisory; must not be
     *     {@code null}, the one branch that carries no sentence being {@link #cleared(String)}
     * @return the shape with its discriminator fixed to {@link #PAYMENT_WITHHELD}, never {@code null}
     */
    public static BillPaymentPreview reporting(String accountId, Money payableBalance,
            String returnMessage) {
        return new BillPaymentPreview(accountId, payableBalance, PAYMENT_WITHHELD, returnMessage);
    }

    /**
     * Builds the answer for the refused turn, which reports neither a balance nor a sentence.
     *
     * <p>Purpose: this is the refusal at lines 178 to 181 of {@code app/cbl/COBIL00C.cbl}, whose
     * {@code CLEAR-CURRENT-SCREEN} at line 180 blanks the display fields and whose error flag at line
     * 181 is a control-flow short-circuit rather than a reported error -- the branch moves nothing into
     * the message field.</p>
     *
     * <p>Assumptions: a SEPARATE factory rather than passing two nulls to the one above, so the two
     * shapes of answer are distinguishable at the call site and a null balance cannot arrive on a
     * reporting turn by accident. It also gives the refusal branch somewhere to carry its own
     * justification, which is the reason the balance and the sentence are both absent.</p>
     *
     * <p>Assumptions: no sentence is invented here, and this is the single most likely place for one to
     * be added by mistake. "Payment cancelled" is the obvious candidate and no line of the reference
     * emits it; transformation rule T8 carries user-visible strings verbatim, which means a turn the
     * reference answers with silence is answered with silence.</p>
     *
     * @param accountId the account the refused submission named, as digit characters; must not be
     *     {@code null}
     * @return the shape with its discriminator fixed to {@link #PAYMENT_WITHHELD} and no balance or
     *     sentence, never {@code null}
     */
    public static BillPaymentPreview cleared(String accountId) {
        return new BillPaymentPreview(accountId, null, PAYMENT_WITHHELD, null);
    }

    /**
     * Renders this preview WITHOUT the account it previews or the balance it quotes.
     *
     * <p>Purpose. Two of the four components are prohibited from a diagnostic rendering by
     * {@code docs/architecture/observability.md} L1093 to L1112: the account identifier by name, and the
     * payable balance as a monetary amount belonging to an identified account. The compiler-generated
     * rendering would print both, and a preview reaches a log on the ORDINARY path rather than an
     * exceptional one -- it is the body of a successful turn -- so the exposure would not be confined to
     * failures.</p>
     *
     * <p>Assumptions: the two are omitted rather than abbreviated, which is that rule's first clause.
     * Abbreviating a balance is masking, and masking has one owner per bounded context in its
     * {@code mapper} package; a second rule written here would give one value two renderings and make
     * neither authoritative.</p>
     *
     * <p>Trade-offs: what remains is the pair that says WHICH TURN this was -- the discriminator fixed to
     * false on this shape, and the sentence the turn carried. Both are safe for a different reason each:
     * the discriminator is a bounded status, and the sentence is one of three message constants carried
     * character for character from {@code CCARD-RETURN-MSG} rather than data about the account. The cost
     * is that an operator cannot see from a log line which account was previewed or what it owed, and it
     * is paid down by the correlation identifier {@code com.carddemo.common.web.CorrelationIdFilter}
     * publishes on every request-scoped line, which ties this body to the request that produced it.</p>
     *
     * @return a rendering carrying the paid discriminator and the return message, with the account
     *     identifier and the payable balance omitted entirely; never {@code null}
     */
    @Override
    public String toString() {
        return "BillPaymentPreview[paid=" + this.paid
                + ", returnMessage=" + this.returnMessage + ']';
    }
}
