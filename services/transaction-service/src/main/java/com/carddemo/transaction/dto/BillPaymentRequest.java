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
 * {@code com.carddemo.transaction.service} acts on it. It holds no logic and reaches nothing: the
 * two components are the two values the reference screen accepts from an operator, and every other
 * value the payment needs is read from stored state or minted by the service. The reference COBOL
 * is the specification, so this shape encodes the field set and the widths that specification
 * already states rather than redefining them.
 *
 * <h2>Two components, and the reason there is no third</h2>
 *
 * <p>The reference screen is the smallest of the four in this bounded context.
 * {@code app/cpy-bms/COBIL00.CPY} declares exactly three business fields --
 * {@code ACTIDINI PIC X(11)} at line 60, {@code CURBALI PIC X(14)} at line 66 and
 * {@code CONFIRMI PIC X(1)} at line 72 -- of which the balance at line 66 is output only: the
 * reference program writes it at {@code app/cbl/COBIL00C.cbl} line 194 and never reads it back.
 * Two inbound fields therefore become two components, and the account identifier is required
 * because line 159 of that program tests it for blankness before anything else and line 161
 * answers a blank one with its own message.
 *
 * <p>Alternatives Considered: a payment-amount component was evaluated and rejected. The reference
 * pays the entire outstanding balance and offers no way to pay part of it: line 224 of that
 * program moves the stored account balance into the transaction amount, line 234 subtracts that
 * same amount from the balance, and the screen carries no amount field for an operator to key one
 * into. Accepting an amount would let a client perform a partial payment the baseline cannot
 * express, which is a change in observable behaviour rather than in structure, and transformation
 * rule T9 permits the second and forbids the first.
 *
 * <p>Trade-offs: requiring the full balance means a client of this endpoint cannot make a partial
 * payment, and that limitation is accepted deliberately. What is bought is behavioural parity with
 * the reference, which the golden-master comparison can verify; what is given up is flexibility
 * this interface never had. The alternative would introduce an operation with no reference
 * equivalent, so no comparison could establish whether its result was correct.
 *
 * <p><b>No paging component of any kind belongs on this type</b>, and the point is stated here
 * because the reference program does contain browse verbs that could be mistaken for one. It is
 * not a browse screen: {@code STARTBR} at lines 441 and 443 is issued with no
 * greater-than-or-equal option, {@code READPREV} follows at lines 472 and 474, and there is no
 * {@code READNEXT} paragraph anywhere in the program's 572 lines. Lines 212 to 217 show what that
 * sequence is for -- the highest existing key is read and one is added to it, so the pair is a
 * maximum-key generator that mints the next transaction identifier, not a cursor over a result
 * set. The shared cursor envelope is never wired into this path, and no page number, counted
 * starting position or page index appears on this type.
 *
 * <p>Assumptions: that same sequence holds no lock across its four verbs, so two payments running
 * together can read the same maximum. The identifier is consequently minted by the service and is
 * never accepted from a client, which is a second reason no third component appears here.
 *
 * <h2>The confirmation is one character because it carries four outcomes</h2>
 *
 * <p>Lines 173 to 191 of that program are a single four-way evaluation over the confirmation field,
 * and all four outcomes are distinct behaviours rather than variations on one. A value of
 * {@code 'Y'} or {@code 'y'} at lines 174 and 175 proceeds: the cross-reference is read at line
 * 211, the identifier is minted, the transaction is written and the balance is reduced. A value of
 * {@code 'N'} or {@code 'n'} at lines 178 and 179 clears the screen and pays nothing. A blank
 * value at lines 182 and 183 reads the account and prompts, with the message at line 237. Any
 * other value falls to line 185 and is answered by the message at line 187.
 *
 * <p>Alternatives Considered: typing this component as a boolean was evaluated and rejected. A
 * boolean has two states and the reference reports three different behaviours for a submission
 * that is not an approval -- declined at lines 178 and 179, not yet confirmed at lines 182 and 183,
 * and out of domain at line 185 -- so a boolean would collapse the three into one and a client
 * could no longer be told which of them had happened. Accepting the two characters case
 * insensitively is what the reference does by enumerating both cases of each letter, and a
 * one-character string is the type that carries that domain without inventing states or losing
 * them.
 *
 * <p>Assumptions: spaces and low values are distinct byte states in the reference and happen to
 * share the prompt branch only here. Lines 182 and 183 enumerate them separately rather than
 * treating one as the other, and they are not interchangeable in general: the sentinel attached to
 * the return message at {@code app/cpy/CVCRD01Y.cpy} line 30 is specifically low values, while the
 * error message at line 28 of that copybook carries no sentinel at all. Both states reach this
 * component as an absent or empty confirmation, and the shared
 * {@code com.carddemo.common.validation.FieldValidationFlag} is where the never-supplied predicate
 * that unifies them lives, so no component here restates the distinction.
 *
 * <p>Assumptions: the nothing-to-pay guard is a service-layer check over stored state and is
 * deliberately not expressible as a constraint on this type. Lines 198 and 199 of that program
 * test the stored balance for being less than or equal to zero -- inclusive of zero -- together
 * with a non-blank account identifier, and answer with the message at line 201 and a cursor
 * placement at line 203. Both operands of that test are read from the account record, which a
 * request body cannot see, so encoding it here would be impossible rather than merely misplaced.
 * The boundary is named so that no later author mistakes its absence for an omission.
 *
 * <h2>Declarative constraints, and where each one gets its value</h2>
 *
 * <p>Alternatives Considered: imperative checks inside a compact canonical constructor were
 * evaluated and rejected, for three reasons that are each independently sufficient. First, a
 * constructor that throws does so during JSON deserialisation, before the request object exists,
 * so the failure never reaches the advice that owns the per-field error array and the array would
 * be empty for exactly the inputs it exists to describe. Second, these annotations are the same
 * source the OpenAPI 3.1 contract for this service is generated from, so a published width and an
 * enforced width cannot drift apart. Third, a compact constructor is a method node the
 * documentation gate audits, and it would have to carry its own at-clause set duplicating the
 * type-level tags below, which is documentation that says nothing the reader has not just read.
 *
 * <p>Assumptions: the width constraints take their values from the reference picture clauses and
 * from nothing else, per transformation rule T1. The account identifier is capped at eleven
 * because {@code ACTIDINI} is declared {@code PIC X(11)} and the account key it becomes is
 * {@code CC-ACCT-ID PIC X(11)} at {@code app/cpy/CVCRD01Y.cpy} line 34; the confirmation is capped
 * at one because {@code CONFIRMI} is declared {@code PIC X(1)}.
 *
 * <p>Assumptions: the account identifier is a digit-validated string and never a numeric type, and
 * the baseline settles this itself by declaring each identifier twice over the same bytes -- once
 * as characters and once as a number. That copybook gives {@code CC-ACCT-ID PIC X(11)} at line 34
 * with {@code CC-ACCT-ID-N PIC 9(11)} redefining it at line 36,
 * {@code CC-CARD-NUM PIC X(16)} at line 37 with its numeric redefinition at line 39, and
 * {@code CC-CUST-ID PIC X(09)} at line 40 with its numeric redefinition at line 42. The character
 * declaration is what the screen and the message carry; the numeric one exists so that arithmetic
 * can reach the same bytes. {@code app/cbl/COACTUPC.cbl} applies the identical pairing to a
 * monetary field, declaring {@code ACUP-OLD-CURR-BAL PIC X(12)} at line 675 and redefining it
 * {@code PIC S9(10)V99} at lines 676 and 677, so the idiom is the baseline's general treatment of
 * a value that is keyed and displayed as text.
 *
 * <p>Assumptions: the reference seed extract settles it beyond the declaration.
 * {@code app/data/ASCII/dailytran.txt} holds 300 records of 350 bytes each, and 30 of them carry a
 * card number whose first character is a zero. A numeric component would discard that leading zero
 * on the way out while still comparing equal on the way in, which is a silent corruption of a tenth
 * of that extract. No example value is reproduced here: a primary account number does not belong in
 * source prose even when it comes from a seed extract, and the aggregate is what the type decision
 * rests on.
 *
 * <p>Assumptions: the digit pattern is evaluated against the whole value and not as a search within
 * it, because Bean Validation matches a pattern against the entire string. No anchors are written,
 * and adding them would change nothing; their absence should not be read as permitting a partly
 * numeric value.
 *
 * <p>Alternatives Considered: a value-domain constraint restricting the confirmation to the four
 * accepted letters was evaluated and rejected, and the reason is the reference program's ordering
 * rather than a preference about where checks live. Lines 169, 197 and 208 each re-test an error
 * flag, so the reference is a short-circuit chain: a blank account identifier at line 159 stops the
 * run before the confirmation is examined at all. Constraints on one object are evaluated together
 * and reported together, so folding the out-of-domain branch into a constraint would let a single
 * response carry the confirmation message from line 187 alongside the account-identifier message
 * from line 161 -- a combination the reference cannot produce. Keeping the confirmation domain in
 * the service preserves the order, and it also keeps one owner for what a valid confirmation is,
 * rather than two that could disagree.
 *
 * <p>Assumptions: exactly one reference message is carried onto a constraint here, and the
 * selection is deliberate rather than partial. Line 161 is the only message that program emits for
 * this field before any file is read, so it is carried character for character onto the not-blank
 * constraint, capital letters included, as transformation rule T8 requires. The reference emits no
 * message at all for an over-long or non-numeric identifier -- it reads the file and reports the
 * outcome, at line 361 -- so no reference-looking string is invented for those conditions and the
 * framework's own default text stands. Fabricating one would put a string in front of an operator
 * that no line of the specification carries.
 *
 * <p>Assumptions: the branch messages at lines 187 and 237 are cited above by line rather than
 * reproduced here, and the distinction matters because each string has exactly one owner. Those two
 * belong to the service that decides the branch they announce, so copying either into this type
 * would create a second copy that could drift from the first while both went on compiling. Only
 * line 161's message is reproduced, because the constraint that reports it lives here.
 *
 * <p>Assumptions: no not-blank constraint belongs on the confirmation, and a width constraint
 * accepts a null value, which is what makes the never-supplied state representable without a second
 * component. An absent confirmation is a legitimate submission that drives the prompt branch, not a
 * rejected one.
 *
 * <h2>A rejected submission answers with a per-field error array</h2>
 *
 * <p>Transformation rule T7 turns the reference validation flags into a structured per-field error
 * array, carried by {@code com.carddemo.common.error.ApiError} with its per-field entries typed by
 * {@code com.carddemo.common.validation.FieldValidationFlag} and produced by the shared
 * exception-handling advice in that same kernel package. Three properties of the reference
 * mechanism govern how a violation of the constraints above is reported, and each is a decision a
 * reader cannot recover from this file's code.
 *
 * <p>Assumptions: the blank state is a subset of the error state rather than a third alternative to
 * it. {@code app/cpy/CSSETATY.cpy} lines 18 and 19 form a single disjunctive test over the not-ok
 * flag and the blank flag, so the highlight fires for either. A caller therefore asks the shared
 * flag whether the field is in error and treats blank as a refinement of that answer, rather than
 * comparing the flag to the blank state as though the two were exclusive.
 *
 * <p>Assumptions: the literal asterisk marker the reference writes into a blank field is
 * presentational, and the copybook proves it by writing the two effects into two different places.
 * Line 21 of that copybook moves the red attribute into the field's colour subfield at line 22,
 * while lines 24 and 25 move the asterisk into the field's output subfield. The symbolic map
 * confirms those are separate declarations: {@code app/cpy-bms/COBIL00.CPY} declares
 * {@code ERRMSGC PICTURE X} at line 136 and {@code ERRMSGO PIC X(78)} at line 140, seven bytes
 * apart in a layout whose output half redefines its input half at line 79. The marker is preserved
 * for the blank case as a property of the error entry, never as a character prepended to a value.
 *
 * <p>Assumptions: the array is keyed by field identifier because that is what the reference
 * signals. It marks the offending field by moving minus one into that field's length subfield,
 * which is how the terminal places the cursor there -- at line 203 for the account identifier and
 * line 239 for the confirmation, and again at lines 163 and 189 on the two paths above them. The
 * key is what carries that behaviour across; the length subfield itself is a terminal mechanism and
 * is not a component of this type.
 *
 * <p>Refactoring Rationale: the mechanism being replaced is the pseudo-conversational confirmation
 * cycle, and what was wrong with it is that it depended on remembered turn state supplied by the
 * client. The reference distinguishes not-yet-confirmed from declined only because the
 * communication area at {@code app/cpy/COCOM01Y.cpy} lines 19 to 44 survives the turn the client
 * echoes it across, and its re-entry discriminator at line 29, with the two condition names at
 * lines 30 and 31, is what gates the field highlight at {@code app/cpy/CSSETATY.cpy} line 20. Here
 * the three confirmation states are carried explicitly in one component of one stateless request,
 * error presentation is driven by the response body alone, and no turn state exists to be trusted.
 * No resubmission flag, first-entry flag, turn counter or equivalent of that discriminator appears
 * on this type, and none may be added.
 *
 * <h2>What this type deliberately does not carry</h2>
 *
 * <p>Assumptions: this record declares no monetary component and no timestamp component, and
 * neither absence is an oversight. The amount is not client input at all -- line 224 moves the
 * stored balance into the transaction -- and the two twenty-six-character timestamps are set by the
 * reference program itself at lines 230 to 232 from a timestamp it obtains, so a client supplies
 * neither. A reader meeting no reference to the shared exact-decimal money type on this type should
 * read that as the payment amount being unsupplied rather than as the money contract being relaxed;
 * the response side of this package is where the balance is reported.
 *
 * <p>Assumptions: no card-number component appears here either, for the same reason. Line 225
 * takes the card number from the cross-reference record read at line 211, not from the screen, and
 * the symbolic map declares no card field at all. Nothing here carries a card verification value,
 * a credential, a connection string or an endpoint.
 *
 * <p>Assumptions: the six framing fields present on every reference map appear on no component
 * here. {@code app/cpy-bms/COBIL00.CPY} declares a four-character transaction name at line 24, two
 * forty-character title constants at lines 30 and 48, an eight-character program name at line 42,
 * and an eight-character date and time at lines 36 and 54. The transaction and program names are
 * identities of the reference transaction monitor with no target analogue, the two titles belong to
 * the user interface screen header, and the date and time are clock reads rendered by the client.
 *
 * <p>Alternatives Considered: Lombok and MapStruct were both evaluated and both rejected for this
 * package as a whole; {@code com.carddemo.transaction.dto}'s package charter carries the reasoning,
 * which turns on generated members being undocumentable and on copybook-to-transfer-object mapping
 * being non-mechanical. A Java 21 record with hand-written mapping is what replaces them.
 *
 * <p>Assumptions: this type imports no persistence entity from
 * {@code com.carddemo.transaction.domain} and no type belonging to another service. The conversion
 * from this shape into an entity belongs to {@code com.carddemo.transaction.mapper}, and the
 * prohibition on reaching across that boundary is asserted by the ArchUnit layering rules this
 * module runs against its own classes rather than by convention.
 *
 * @param accountId the account whose outstanding balance this payment settles, from
 *     {@code ACTIDINI PIC X(11)} at line 60 of {@code app/cpy-bms/COBIL00.CPY} and keyed as
 *     {@code CC-ACCT-ID PIC X(11)} at line 34 of {@code app/cpy/CVCRD01Y.cpy}; required, capped at
 *     the copybook's eleven characters and constrained to digits, and borne as digit characters so
 *     that a leading zero survives the round trip
 * @param confirmation the one-character authorisation of the payment, from
 *     {@code CONFIRMI PIC X(1)} at line 72 of that same map; capped at the copybook's one
 *     character and nullable, because an absent value is the never-confirmed state that drives the
 *     prompt branch rather than a rejected submission, and the accepted letters are matched case
 *     insensitively by the service. The component is named for the wire: the published contract
 *     calls this property confirmation, no property-naming strategy or per-property annotation is
 *     configured anywhere in this package, so a component named anything else would leave a
 *     conformant client's value unbound and this record holding null on a money-moving request
 */
public record BillPaymentRequest(
    // WHY : Assumptions: three constraints are declared rather than one because each has a
    //       different authority. Requiredness comes from COBIL00C.cbl line 159, the width from the
    //       PIC X(11) at COBIL00.CPY line 60, and the digit domain from the numeric redefinition
    //       at CVCRD01Y.cpy line 36. Collapsing them into a single pattern would compile and
    //       enforce the same values while leaving no way to see which copybook line each came
    //       from, and the OpenAPI contract generated from them would publish one opaque
    //       expression in place of three traceable facts.
    // WHY : Refactoring Rationale: the digit run is EXACT at eleven and an earlier revision of
    //       this component admitted any number of digits. Eleven is the whole identifier, and the
    //       published contract openapi/transaction-api.yaml declares exactly eleven for it, so the
    //       earlier expression accepted values that contract refused - a request one side called
    //       valid and the other called malformed, with nothing comparing them. The committed extract
    //       is zero-padded to eleven, so a shorter run is not a partial identifier but one that
    //       matches no row.
    // WHY : Assumptions: the class shorthand for a digit is written out as the ten characters
    //       rather than as \\d, because that shorthand's reach depends on a matcher flag Bean
    //       Validation does not set, whereas COBIL00C.cbl line 165 accepts the ten ASCII digits and
    //       nothing else. Writing the range removes the dependency.
    @NotBlank(message = "Acct ID can NOT be empty...")
    @Size(max = 11)
    @Pattern(regexp = "[0-9]{11}")
    String accountId,

    // WHY : Assumptions: a width constraint treats a null value as valid, so nullability needs no
    //       second component and no sentinel value to represent it. Leaving the domain to the
    //       service is what keeps COBIL00C.cbl's short-circuit order intact, since a constraint
    //       here would be evaluated alongside the account-identifier constraints above and could
    //       report line 187's message for a submission line 159 already rejected.
    // WHY : Refactoring Rationale: the component is spelled confirmation because a record component
    //       name IS the wire name here -- this package declares no property-naming strategy and no
    //       per-property annotation anywhere -- and the published contract's property is
    //       confirmation. An earlier revision named it confirm, so a client sending exactly what the
    //       contract describes had its value rejected as an unknown property by the schema's closed
    //       object and, had it been admitted, bound nothing: the component would have been null on
    //       every request, which on this endpoint means never confirmed and therefore never paid.
    //       Renaming the component was chosen over annotating it so that the invariant "component
    //       name equals wire name" continues to hold for the whole package, leaving one rule to
    //       check rather than a per-property exception to find.
    // WHY : Assumptions: a shorter identifier would be an unknown property to that contract,
    //       and `spring.jackson.deserialization.fail-on-unknown-properties` is true in this
    //       module's application.yml, so every contract-conformant body would be refused
    //       before any constraint above was evaluated.
    @Size(max = 1)
    String confirmation) {
}
