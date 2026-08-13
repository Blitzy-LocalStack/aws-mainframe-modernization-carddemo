package com.carddemo.transaction.dto;

import com.carddemo.common.money.Money;

/**
 * The capture result of the migrated transaction-add screen, carrying the generated transaction
 * identifier, the normalised amount and one optional return message.
 *
 * <p><b>Purpose.</b> This is the reply body of the transaction-add write. It is produced by
 * {@code com.carddemo.transaction.service} once the record has been written, and it is serialised
 * to JSON by the controller. It holds no logic and reaches nothing: every value arrives already in
 * the form it travels in, which is what keeps representation concerns of the reference record
 * format in the one package permitted to hold them. The reference COBOL is the specification, so
 * this shape encodes the field set, the widths and the scale that specification already states
 * rather than redefining them.
 *
 * <h2>Three components, and why the full record is deliberately not echoed</h2>
 *
 * <p>Alternatives Considered: giving this response the same fourteen components the detail
 * response carries -- thirteen record fields and one return message -- was evaluated and rejected.
 * Two facts settle it. The reference add map declares no transaction identifier at all:
 * {@code app/cpy-bms/COTRN02.CPY} runs fifteen field groups, beginning at lines 55, 61, 67, 73,
 * 79, 85, 91, 97, 103, 109, 115, 121, 127, 133 and 139 before it redefines the output half at line
 * 145, and thirteen of those fifteen re-display a value the client itself submitted, the
 * fourteenth being the confirmation flag and the fifteenth the message line. Echoing them back
 * would hand the client its own request. And the full shape already exists as
 * {@code TransactionDetailResponse}, addressable at exactly the identifier this response returns,
 * so declaring it a second time would put one contract in two places to maintain. The repository
 * legislates against precisely that duplication for the record layouts themselves, requiring at
 * {@code tests/README.md} lines 540 to 542 that a layout be single-sourced through the compiler
 * include path rather than duplicated. The two values a client cannot reconstruct from its own
 * request are the identifier the server generated and the amount the reference program
 * demonstrably rewrites, and those are the two components present.
 *
 * <h2>The identifier is returned although the reference screen has nowhere to show it</h2>
 *
 * <p>Alternatives Considered: strict parity with the reference screen, meaning omitting the
 * identifier from this response altogether, was evaluated and rejected. The reference program
 * computes that value rather than accepting it: {@code app/cbl/COTRN02C.cbl} opens its add
 * paragraph at line 442, moves high values into the record key at line 444, browses at line 445
 * and reads backwards at line 446 to reach the highest key on file, then increments it. The value
 * is the server's rather than the client's, and a client unable to read it back would hold no way
 * to address the record it had just created, the detail read that carries the remaining twelve
 * fields being keyed by exactly this value. Returning it widens the wire shape relative to the
 * terminal without altering any behaviour the reference program exhibits, which is what
 * transformation rule T9 permits: that rule constrains behaviour, not the breadth of a response.
 *
 * <p>Trade-offs: the generated identifier is a value the reference terminal never displayed, so
 * this response is broader than the screen it replaces. That breadth is the compromise accepted,
 * taken in preference to the alternative, which leaves a created resource unaddressable by its own
 * creator.
 *
 * <p>Assumptions: the generation sequence holds no lock, which is the concrete reason the
 * identifier is generated on the server and returned rather than supplied by the caller. The same
 * browse-backwards-and-increment sequence appears in full at {@code app/cbl/COBIL00C.cbl} lines
 * 212 to 217, and neither program retains the record between the read and the write, so two
 * concurrent captures can observe the same highest key. A caller therefore learns which identifier
 * its own capture received from this response and from nowhere else.
 *
 * <h2>Money is exact fixed point and travels as a JSON string</h2>
 *
 * <p>Alternatives Considered: typing the amount as a bare arbitrary-precision decimal was
 * evaluated and rejected, and the reason is the type rather than the value. The shared
 * {@code MoneyModule} binds its serialiser and its deserialiser to the {@code Money} type itself,
 * so a component declared as a bare decimal is reached by neither and is written as a plain JSON
 * number instead: it compiles, it runs, its arithmetic is exact, and its wire form is wrong. That
 * failure mode is named here because no assertion about the value would localise it -- only an
 * assertion about the serialised text would. Most clients parse a JSON number into an IEEE-754
 * binary floating point value on receipt, and binary64 cannot exactly represent the two-place
 * decimal fractions this field is built from, so the loss lands in the cents at the one hop a user
 * actually reads. {@code TRAN-AMT PIC S9(09)V99} at line 10 of {@code app/cpy/CVTRA05Y.cpy} is
 * eleven significant decimal digits, which leaves no margin for an approximation.
 *
 * <p>Assumptions: that module is registered elsewhere in this module and by no type in this
 * package. This record does not register it, does not annotate around it and needs no serialisation
 * annotation of its own; the quoted, two-place, plain decimal wire form follows from the declared
 * type alone.
 *
 * <h2>The amount is the normalised value, at the record's nine integer digits</h2>
 *
 * <p>Assumptions: the reference program normalises the amount and shows the operator the result,
 * so a normalised value in this component transcribes that behaviour rather than adding to it.
 * {@code app/cbl/COTRN02C.cbl} parses the keyed text through {@code FUNCTION NUMVAL-C} into
 * {@code WS-TRAN-AMT-N} at lines 383 and 384, moves that into the edited {@code WS-TRAN-AMT-E} at
 * line 385, and at line 386 writes the edited form back into the very screen field the operator
 * keyed. The value the record finally receives is that same normalised one, re-parsed from the
 * rewritten field at lines 456 to 458. A canonical sign and a canonical decimal placement are
 * therefore properties of the reference behaviour, and this component carries them.
 *
 * <p>Assumptions: the two work fields differ in width, and the difference is registered rather
 * than absorbed. {@code WS-TRAN-AMT-N} is declared {@code PIC S9(9)V99} at line 58 of that program
 * while {@code WS-TRAN-AMT-E} is declared {@code PIC +99999999.99} at line 59, so the parse widens
 * to nine integer digits and the edit then narrows to eight. The map field corroborates the
 * narrower ceiling independently, because {@code TRNAMTI PIC X(12)} at line 96 of
 * {@code app/cpy-bms/COTRN02.CPY} is exactly a sign, eight digits, a decimal point and two more,
 * and a ninth integer digit would need a thirteenth character position it does not have. The
 * framing here is the only one used: the baseline's edit narrows to eight integer digits, the Java
 * carries the record's nine as {@code TRAN-AMT PIC S9(09)V99} declares them, and the divergence is
 * documented in the migration traceability register rather than introduced silently. The
 * repository states the same discipline for its own suite at {@code tests/README.md} lines 555 and
 * 556, which encode the specification rather than redefine it. Nothing under {@code app/} is
 * edited by this record or by any statement in this docstring.
 *
 * <h2>The identifier is a digit-bearing string, never a numeric type</h2>
 *
 * <p>Assumptions: the baseline settles this itself, declaring each identifier twice over the same
 * bytes, once as characters and once as a number. {@code app/cpy/CVCRD01Y.cpy} gives
 * {@code CC-ACCT-ID PIC X(11)} at line 34 with {@code CC-ACCT-ID-N PIC 9(11)} redefining it at
 * line 36, {@code CC-CARD-NUM PIC X(16)} at line 37 with its numeric redefinition at line 39, and
 * {@code CC-CUST-ID PIC X(09)} at line 40 with {@code PIC 9(9)} redefining it at line 42. The
 * character declaration is what the screen and the message carry; the numeric one exists so that
 * arithmetic can reach the same bytes.
 *
 * <p>Assumptions: the reference seed extract settles it beyond the declaration.
 * {@code app/data/ASCII/dailytran.txt} holds 300 records of 350 bytes each, and 30 of them carry a
 * card number whose first character is a zero. A numeric component would discard that leading zero
 * on the way out while still comparing equal on the way in, a silent corruption of a tenth of that
 * extract. No example value is reproduced here: a primary account number does not belong in source
 * prose even when it comes from a seed extract, and the aggregate is what the type decision rests
 * on. The migration's own derivation table keeps the same discipline on the far side of this
 * boundary, mapping a fixed-width character key to a fixed-width character column rather than to
 * an integer one.
 *
 * <h2>One nullable return message, and no error message</h2>
 *
 * <p>Assumptions: the message width this response answers in is 75, and the choice between the two
 * candidate fields is derived from the copybook rather than from convention.
 * {@code app/cpy/CVCRD01Y.cpy} declares {@code CCARD-ERROR-MSG PIC X(75)} at line 28 and
 * {@code CCARD-RETURN-MSG PIC X(75)} at line 29. The two are the same width and are not
 * interchangeable, because only the second carries a sentinel: line 30 attaches a message-off
 * condition valued at low values to the return message alone, and the error message has none. That
 * asymmetry settles both component decisions -- this response carries one return message and it is
 * nullable, because the sentinel state maps to an absent message, and it carries no error-message
 * component at all, because the error path belongs to
 * {@code com.carddemo.common.error.ApiError} and its handler in the shared kernel.
 *
 * <p>Assumptions: the sentinel is low values and not spaces, and substituting one for the other
 * changes observable behaviour. An absent message and a blank message of seventy-five characters
 * are different states, and a client rendering nothing for the first and an empty band for the
 * second would diverge on exactly the distinction the reference copybook draws. This is the only
 * component of the three that may be null.
 *
 * <p>Assumptions: the text this component carries is the reference program's own, character for
 * character. This shape is the body of a completed capture, so the text it carries is the sentence
 * {@code app/cbl/COTRN02C.cbl} assembles at lines 728 to 733 from
 * {@code 'Transaction added successfully. '} and {@code ' Your Tran ID is '} around the assigned
 * identifier, including the two consecutive spaces that join produces before {@code Your}. The two
 * other strings that branch reaches are cited here because they are easy to attribute to this
 * component and belong elsewhere:
 * {@code 'Confirm to add this transaction...'} at line 178 is the withheld-confirmation turn, which
 * the published contract answers with its own preview shape because the identifier is minted inside
 * {@code ADD-TRANSACTION} at lines 442 to 449, which that turn never reaches, and
 * {@code 'Invalid value. Valid values are (Y/N)...'} at line 184 is a value-domain
 * complaint about one field, so it travels in the per-field error array of
 * {@code com.carddemo.common.error.ApiError} rather than in a message line. Each ends in three full
 * stops, and none is reworded, repunctuated or retitled on its way across.
 *
 * <h2>What this type deliberately does not carry</h2>
 *
 * <p>Assumptions: no timestamp component is present, and the absence is stated so that a reader
 * does not take it for an oversight. The reference add screen carries two ten-character dates the
 * operator keys, whose field groups begin at lines 97 and 103 of
 * {@code app/cpy-bms/COTRN02.CPY}, while the twenty-six-character {@code TRAN-ORIG-TS} and
 * {@code TRAN-PROC-TS} at lines 16 and 17 of {@code app/cpy/CVTRA05Y.cpy} are produced when the
 * record is written and are reachable through the detail read. Neither belongs here: the keyed
 * dates are the client's own input, and the written instants are already addressable at the
 * identifier this response returns. Nor could a component of this record obtain one, because
 * {@code com.carddemo.common.time.TimestampFormatter} exposes no argument-free formatting entry
 * point and no accessor that reads an ambient clock; each of its current-instant entry points
 * requires a clock the caller supplies. No component here reads a clock, and no statement in this
 * docstring should be read as implying that one could.
 *
 * <p>Assumptions: the card number has no component here and none may be added. The reference add
 * screen accepts one at the field group beginning at line 61 of that map, but it is an input the
 * client supplied and this response is not an echo of the request. No card verification value
 * component exists either, and the reference transaction record declares none in the first place.
 * This record likewise carries no session state and no re-entry marker of any kind -- no
 * resubmission flag, no first-entry flag and no turn counter -- and no ordinal position, page
 * number or offset, a capture addressing one new record having no sequence to page through.
 *
 * <p>Assumptions: the reference transaction record declares no {@code COMP} item and no
 * {@code OCCURS} clause. Its numerics are zoned decimal throughout, so the packed-decimal handling
 * the shared kernel also publishes has no subject here, and no component is a repeating group.
 *
 * <p>Assumptions: the declared widths above are documented rather than annotated on this type. A
 * size constraint is evaluated on a value entering the application and this record only leaves it,
 * so a constraint here would never be exercised; the request side of this package is where the
 * copybook widths become constraint values.
 *
 * <p>Assumptions: this type imports no persistence entity from
 * {@code com.carddemo.transaction.domain} and reaches no sibling service. Converting from the
 * entity belongs to {@code com.carddemo.transaction.mapper}, and the prohibition on reaching
 * across that boundary is asserted by the ArchUnit layering rules this module runs against its own
 * classes rather than by convention.
 *
 * <h2>Why a small typed body replaces a whole screen image</h2>
 *
 * <p>Refactoring Rationale: the mechanism this response replaces is the reference program's screen
 * re-transmission, and what was wrong with it is that it fused two unrelated things. A capture
 * outcome there is reported by rebuilding and resending the entire map, each of the two messages
 * at lines 178 and 184 being followed by that same send, so the one fact the client did not
 * already hold -- whether the capture happened, and under which identifier -- arrived inseparable
 * from an echo of the values it had just typed. Here the outcome is a small typed body and the
 * echo is the client's own state, retained by the client. That separation is what makes the three
 * components below sufficient rather than austere.
 *
 * <p>Alternatives Considered: Lombok and MapStruct were both evaluated and both rejected for this
 * package as a whole; {@code com.carddemo.transaction.dto}'s package charter carries the reasoning,
 * which turns on generated members being undocumentable and on copybook-to-transfer-object mapping
 * being non-mechanical. A Java 21 record with hand-written mapping is what replaces them.
 *
 * @param transactionId the identifier the server generated for the captured transaction, from
 *     {@code TRAN-ID PIC X(16)} at line 5 of {@code app/cpy/CVTRA05Y.cpy}; borne as digit
 *     characters so that a leading zero survives the round trip, and present because the reference
 *     add map declares no field for it while the reference program computes it at lines 442 to 446
 * @param amount the normalised monetary value the transaction was captured at, from
 *     {@code TRAN-AMT PIC S9(09)V99} at line 10 of that copybook, exact at nine integer digits and
 *     a scale of two; serialised as a quoted decimal string and never as a JSON number, and
 *     present because it is the one submitted value the reference program rewrites before storing
 * @param returnMessage the confirmation or advisory text accompanying the capture, from
 *     {@code CCARD-RETURN-MSG PIC X(75)} at line 29 of {@code app/cpy/CVCRD01Y.cpy}; null when
 *     there is no message, mirroring that field's low-values sentinel, and the only component here
 *     that may be null
 */
// WHY : ⚠️ Refactoring Rationale: this record implements TransactionAddOutcome and is now the shape of
//       the WRITTEN turn only. It previously answered the unconfirmed turn too, with its identifier left
//       null -- a body the published TransactionAddPreview schema forbids, since that schema declares no
//       transactionId and closes its object, and one that omitted the `written` member that schema marks
//       required. TransactionAddPreview now answers that turn, so every component below is
//       unconditionally present on every body this record produces.

public record TransactionAddResponse(
    // WHY : Assumptions: CVCRD01Y declares every identifier twice over the same bytes, as
    //       characters at line 34 and as a number at line 36, and 30 of the 300 seed records in
    //       app/data/ASCII/dailytran.txt carry a card number beginning with a zero. A numeric
    //       component would drop such a leading zero on the way out while still comparing equal
    //       on the way in.
    String transactionId,
    // WHY : Assumptions: the shared Jackson module binds its serialiser to this exact type, so
    //       the declared type is what selects the quoted-string wire form. Substituting a bare
    //       decimal here compiles and runs and silently emits a JSON number instead.
    Money amount,
    // WHY : Assumptions: CVCRD01Y line 30 attaches a low-values sentinel to this field alone,
    //       and line 28's error message carries none, so absence is representable for this one
    //       field and null is what represents it. Spaces are a different state and are not
    //       substituted for it.
    String returnMessage) implements TransactionAddOutcome {

    /**
     * The value {@link #written()} reports for every instance of this shape.
     *
     * <p>Assumptions: declared as a named constant so that the discriminator this record contributes to
     * the sealed hierarchy is stated once. It is deliberately NOT a record component: the published
     * {@code TransactionCreated} schema closes its object at three members and declares no
     * {@code written} property, so adding a component would emit a fourth member that a strict client
     * rejects. The accessor below satisfies the hierarchy without changing the body.</p>
     */
    public static final boolean CAPTURE_WRITTEN = true;

    /**
     * Reports that a transaction was written, which is unconditionally true of this shape.
     *
     * <p>Assumptions: this shape is returned only from the append path, so the value is a property of
     * the type rather than of an instance. The preview shape carries the opposite value as a real
     * component because its own schema declares one; the asymmetry is the two published schemas'
     * asymmetry rather than an inconsistency here.</p>
     *
     * @return {@link #CAPTURE_WRITTEN}, always
     */
    @Override
    public boolean written() {
        return CAPTURE_WRITTEN;
    }

    /**
     * Renders this response WITHOUT the amount it captured.
     *
     * <p>Purpose. The amount is a monetary value and is prohibited from a diagnostic rendering by
     * {@code docs/architecture/observability.md} L1093 to L1112. This shape is produced on the capture
     * path, so the compiler-generated rendering printed the value of every transaction the service
     * accepted onto the ordinary success path of a log.</p>
     *
     * <p>Assumptions: the generated transaction identifier is kept, which that rule's third clause
     * names as identity disclosing nothing, and it is what makes the omission affordable: the captured
     * amount is a column of the row that identifier names, so withholding it here removes the value
     * from the log without removing it from reach.</p>
     *
     * <p>Trade-offs: the return message is kept verbatim because it is one of the reference sentences
     * carried across character for character rather than data derived from the submission, and a
     * capture that reported an advisory rather than a confirmation is diagnosed from it.</p>
     *
     * @return a rendering carrying the transaction identifier and the return message, with the amount
     *     omitted entirely; never {@code null}
     */
    @Override
    public String toString() {
        return "TransactionAddResponse[transactionId=" + this.transactionId
                + ", returnMessage=" + this.returnMessage + ']';
    }
}
