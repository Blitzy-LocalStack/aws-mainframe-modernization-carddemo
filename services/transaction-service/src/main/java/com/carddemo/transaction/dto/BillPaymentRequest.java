package com.carddemo.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The request body of the migrated bill-payment screen, carrying the account to be paid and the
 * one-character confirmation that authorises the payment.
 *
 * <p><b>Purpose.</b> This is the inbound body of the bill-payment submission. A controller in
 * {@code com.carddemo.transaction.api} binds and validates it, and
 * {@code com.carddemo.transaction.service} acts on it. It holds no logic and reaches nothing: the two
 * components are the two values the reference screen accepts from an operator, and every other value
 * the payment needs is read from stored state or minted by the service. The field set and widths come
 * from {@code app/cpy-bms/COBIL00.CPY} and {@code app/cbl/COBIL00C.cbl}, which are read as
 * specification and never modified.
 *
 * <p>Alternatives Considered: a payment-amount component. Rejected because the reference pays the
 * entire outstanding balance and offers no way to pay part of it -- {@code COBIL00C.cbl} line 224 moves
 * the stored balance into the transaction amount and line 234 subtracts the same amount, and the screen
 * carries no amount field. Accepting an amount would let a client perform a partial payment the
 * baseline cannot express, which transformation rule T9 forbids as a behavioural change.
 *
 * <p>Alternatives Considered: typing the confirmation as a boolean. Rejected because the four-way
 * evaluation at {@code COBIL00C.cbl} lines 173 to 191 reports THREE distinct non-approval behaviours
 * -- declined at lines 178 and 179, not yet confirmed at lines 182 and 183, out of domain at line 185
 * -- so a boolean would collapse them and a client could no longer be told which occurred.
 *
 * <p>Alternatives Considered: imperative checks in a compact canonical constructor. Rejected because a
 * throwing constructor fails during deserialisation, before the request object exists, so the failure
 * never reaches the advice that owns the per-field error array; and because these annotations are the
 * same source the service's OpenAPI 3.1 contract is generated from, so a published width and an
 * enforced width cannot drift apart.
 *
 * <p>Alternatives Considered: a value-domain constraint restricting the confirmation to the four
 * accepted letters. Rejected on the reference program's ordering: lines 169, 197 and 208 re-test an
 * error flag, so a blank account identifier at line 159 stops the run before the confirmation is
 * examined. Constraints on one object are evaluated and reported together, so folding that branch into
 * a constraint would let one response carry line 187's message alongside line 161's -- a combination the
 * reference cannot produce.
 *
 * <p>Assumptions: the account identifier is a digit-validated string and never a numeric type. The
 * baseline settles this itself by declaring each identifier twice over the same bytes, once as
 * characters and once as a number -- {@code CC-ACCT-ID PIC X(11)} at {@code app/cpy/CVCRD01Y.cpy} line
 * 34 with {@code CC-ACCT-ID-N PIC 9(11)} redefining it at line 36 -- and 30 of the 300 records in
 * {@code app/data/ASCII/dailytran.txt} carry a card number whose first character is a zero. A numeric
 * component would discard that leading zero on the way out while still comparing equal on the way in.
 *
 * <p>Assumptions: exactly one reference message is carried onto a constraint here. Line 161 is the only
 * message the program emits for this field before any file is read, so it is reproduced character for
 * character as transformation rule T8 requires. The branch messages at lines 187 and 237 belong to the
 * service that decides the branch they announce and are cited by line rather than copied, so no string
 * has two owners; and the reference emits no message at all for an over-long or non-numeric identifier,
 * so none is invented and the framework's default text stands.
 *
 * <p>Assumptions: no paging component belongs on this type, and the point is worth stating because the
 * reference program contains browse verbs that could be mistaken for one. {@code STARTBR} at lines 441
 * and 443 is issued with no greater-or-equal option, {@code READPREV} follows at lines 472 and 474, and
 * there is no {@code READNEXT} anywhere in the program: lines 212 to 217 show the sequence reading the
 * highest existing key and adding one, so it is a maximum-key generator that mints the next transaction
 * identifier, not a cursor. That sequence holds no lock, which is the second reason the identifier is
 * minted by the service and never accepted from a client.
 *
 * <p>Refactoring Rationale: the mechanism replaced is the pseudo-conversational confirmation cycle, and
 * what was wrong with it is that it depended on remembered turn state supplied by the client. The
 * reference distinguishes not-yet-confirmed from declined only because the communication area at
 * {@code app/cpy/COCOM01Y.cpy} lines 19 to 44 survives the turn the client echoes it across. Here the
 * three confirmation states are carried explicitly in one component of one stateless request, error
 * presentation is driven by the response body alone, and no resubmission flag, first-entry flag or turn
 * counter appears on this type.
 *
 * <p>Assumptions: no monetary, timestamp or card-number component appears, and none is an oversight.
 * The amount is not client input, the two 26-character timestamps are set by the program itself at
 * lines 230 to 232, and line 225 takes the card number from the cross-reference record read at line 211
 * rather than from the screen. A reader meeting no reference to the shared exact-decimal money type
 * should read that as the amount being unsupplied, not as the money contract being relaxed.
 *
 * @param accountId the account whose outstanding balance this payment settles, from
 *     {@code ACTIDINI PIC X(11)} at line 60 of {@code app/cpy-bms/COBIL00.CPY} and keyed as
 *     {@code CC-ACCT-ID PIC X(11)} at line 34 of {@code app/cpy/CVCRD01Y.cpy}; required, capped at
 *     the copybook's eleven characters and constrained to digits, and borne as digit characters so
 *     that a leading zero survives the round trip
 * @param confirmation the one-character authorisation of the payment, from
 *     {@code CONFIRMI PIC X(1)} at line 72 of that same map; capped at the copybook's one character
 *     and nullable, because an absent value is the never-confirmed state that drives the prompt branch
 *     rather than a rejected submission, and the accepted letters are matched case insensitively by the
 *     service. The component is named for the wire: the published contract calls this property
 *     confirmation and no property-naming strategy is configured anywhere in this package, so a
 *     component named anything else would leave a conformant client's value unbound
 */
public record BillPaymentRequest(
    // Assumptions: three constraints rather than one because each has a different authority --
    //   requiredness from COBIL00C.cbl line 159, the width from PIC X(11) at COBIL00.CPY line 60, the
    //   digit domain from the numeric redefinition at CVCRD01Y.cpy line 36. Collapsing them into one
    //   pattern would enforce the same values while publishing one opaque expression to the OpenAPI
    //   contract in place of three traceable facts. The digit class is written out as the ten
    //   characters rather than as a shorthand, whose reach depends on a matcher flag Bean Validation
    //   does not set.
    @NotBlank(message = "Acct ID can NOT be empty...")
    @Size(max = 11)
    @Pattern(regexp = "[0-9]{11}")
    String accountId,

    // Assumptions: a width constraint treats a null value as valid, so the never-supplied state needs
    //   no second component and no sentinel. Leaving the letter domain to the service is what keeps
    //   COBIL00C.cbl's short-circuit order intact.
    @Size(max = 1)
    String confirmation) {
}
