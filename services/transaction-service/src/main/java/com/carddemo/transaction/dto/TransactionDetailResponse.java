package com.carddemo.transaction.dto;

import com.carddemo.common.money.Money;

/**
 * The view response of the migrated transaction-detail screen, carrying every field of the
 * reference transaction record together with one optional return message.
 *
 * <p><b>Purpose.</b> This is the reply body of the transaction-detail read. It is produced by
 * {@code com.carddemo.transaction.service} from a persistence entity that
 * {@code com.carddemo.transaction.mapper} converts, and it is serialised to JSON by the
 * controller. It holds no logic and reaches nothing: every value arrives already in the form it
 * travels in, which is what keeps representation concerns of the reference record format in the
 * one package permitted to hold them. The reference COBOL is the specification, so this shape
 * encodes the field set, the widths and the scale that specification already states rather than
 * redefining them.
 *
 * <h2>Where the component set comes from, and where the order comes from</h2>
 *
 * <p>Two different questions have two different answers, and conflating them is what makes the
 * ordering below look arbitrary. The reference view program moves thirteen record fields into its
 * symbolic map at {@code app/cbl/COTRN01C.cbl} lines 178 to 190, and that sequence is what
 * establishes <em>which</em> fields this response carries. The copybook declaration at
 * {@code app/cpy/CVTRA05Y.cpy} lines 5 to 17 is what establishes the order they are declared in
 * here.
 *
 * <p>Alternatives Considered: declaring the components in the order the reference program emits
 * them was evaluated and rejected. The two orders genuinely differ rather than coinciding --
 * lines 178 to 190 emit the card number second and the merchant block last, while lines 5 to 17
 * declare the card number eleventh and the merchant block sixth through tenth -- so a choice had
 * to be made and either order would compile. Transformation rule T1 makes the copybook normative,
 * and the emit order is a property of the reference presentation: it is the reading order of a
 * fixed-position terminal screen, which is the layout concern of whichever client renders this
 * response and not a property of the record. Ordering by the copybook means this response, the
 * persistence entity, the database columns and the fixed-width extract all enumerate the same
 * fields in the same sequence, so a reader comparing any two of them is comparing like with like.
 * Ordering by the screen would have made the declaration agree with one of the four and disagree
 * with the other three.
 *
 * <p>Assumptions: the record arithmetic is self-checking, which is what lets each width below be
 * verified rather than trusted. Summing the declared widths in declaration order -- 16, 2, 4, 10,
 * 100, 11, 9, 50, 50, 10, 16, 26, 26 and 20 -- gives exactly 350, and the copybook's own header
 * comment at line 2 records the record length as 350. The monetary field accounts for eleven of
 * those bytes, being nine integer digits and two decimal places held as zoned decimal. A width
 * here that disagreed with that copybook would be wrong by construction rather than by opinion.
 *
 * <p>Assumptions: {@code FILLER PIC X(20)} at line 18 of that copybook has no component here, and
 * the drop is recorded rather than left silent. Those bytes pad the record to its declared length
 * and carry no value a caller can read. The drop is also not an invention of this migration: of
 * the fourteen fields the copybook declares, {@code FILLER} is the one absent from the reference
 * program's own move sequence at lines 178 to 190, so the reference screen already carries the
 * record without it. That is a stronger warrant than a design preference would be.
 *
 * <p>Assumptions: the reference transaction record declares no {@code COMP} item and no
 * {@code OCCURS} clause. Its numerics are zoned decimal throughout, so the packed-decimal
 * handling the shared kernel also publishes has no subject here, and no component is a repeating
 * group.
 *
 * <h2>No request counterpart belongs beside this type</h2>
 *
 * <p>Assumptions: the detail screen is the only one of the four in this context that carries two
 * identifier fields, and that is why it needs no request body. {@code app/cpy-bms/COTRN01.CPY}
 * declares {@code TRNIDINI PIC X(16)} at line 60, which is the key the operator keys in, and
 * {@code TRNIDI PIC X(16)} at line 66, which is the key echoed back on the retrieved record.
 * {@code app/cbl/COTRN01C.cbl} line 172 moves the first into the record key before the read at
 * line 173. The key being searched on therefore becomes the identifier segment of the request
 * path, and a path segment is not a body type; the key echoed back is the first component of this
 * response. No {@code TransactionDetailRequest} should be added to this package, because there is
 * no second inbound value for one to carry. That file name is upper case on disk, extension
 * included, so a lower-case path does not resolve.
 *
 * <h2>Money is exact fixed point and travels as a JSON string</h2>
 *
 * <p>Alternatives Considered: typing the amount as a bare arbitrary-precision decimal was
 * evaluated and rejected, and the reason is the type rather than the value. The shared
 * {@code MoneyModule} binds its serialiser and its deserialiser to the {@code Money} type itself,
 * so a component declared as a bare decimal is not reached by either and is written as a plain
 * JSON number instead: it compiles, it runs, its arithmetic is exact, and its wire form is wrong.
 * That failure mode is named here because no assertion about the value would localise it -- only
 * an assertion about the serialised text would. Most clients parse a JSON number into an IEEE-754
 * binary floating point value on receipt, and binary64 cannot exactly represent the two-place
 * decimal fractions this field is built from, so the loss lands in the cents at the one hop a
 * user actually reads. {@code TRAN-AMT PIC S9(09)V99} at line 10 of the copybook is eleven
 * significant decimal digits, which leaves no margin for an approximation.
 *
 * <p>Assumptions: that module is registered by this module's application entry point, which
 * imports it explicitly because component scanning rooted at {@code com.carddemo.transaction}
 * reaches nothing beneath {@code com.carddemo.common}. This record does not register it, does not
 * annotate around it and needs no serialisation annotation of its own.
 *
 * <h2>Identifiers are digit-bearing strings, never numeric types</h2>
 *
 * <p>Assumptions: the baseline settles this itself, declaring each identifier twice over the same
 * bytes -- once as characters and once as a number. {@code app/cpy/CVCRD01Y.cpy} gives
 * {@code CC-ACCT-ID PIC X(11)} at line 34 with {@code CC-ACCT-ID-N PIC 9(11)} redefining it at
 * line 36, {@code CC-CARD-NUM PIC X(16)} at line 37 with its numeric redefinition at line 39, and
 * {@code CC-CUST-ID PIC X(09)} at line 40 with its numeric redefinition at line 42. The character
 * declaration is what the screen and the message carry; the numeric one exists so that arithmetic
 * can reach the same bytes.
 *
 * <p>Assumptions: the reference seed extract settles it beyond the declaration.
 * {@code app/data/ASCII/dailytran.txt} holds 300 records of 350 bytes each, and 30 of them carry
 * a card number whose first character is a zero. A numeric component would discard that leading
 * zero on the way out while still comparing equal on the way in, which is a silent corruption of
 * a tenth of that extract. No example value is reproduced here: a primary account number does not
 * belong in source prose even when it comes from a seed extract, and the aggregate is what the
 * type decision rests on. The symbolic map agrees with the type choice, declaring
 * {@code TCATCDI PIC X(4)} at line 84 and {@code MIDI PIC X(9)} at line 120 where the record
 * declares those same two fields {@code PIC 9(04)} and {@code PIC 9(09)}, so even the reference
 * presentation treats them as characters.
 *
 * <h2>Four narrowings of the reference presentation, each carried at record width</h2>
 *
 * <p>The framing below is the only one used: the baseline does one thing, the Java implements
 * another, and the divergence is documented in the migration traceability register rather than
 * introduced silently. The repository states the same discipline for its own suite at
 * {@code tests/README.md} lines 555 and 556, which encode the specification rather than redefine
 * it. Nothing under {@code app/} is edited by this record or by any statement in this docstring.
 *
 * <p>The amount is the first and the most consequential. {@code TRAN-AMT} holds nine integer
 * digits, and the reference view narrows it to eight for display:
 * {@code app/cbl/COTRN01C.cbl} declares {@code WS-TRAN-AMT PIC +99999999.99} at line 49 and
 * routes the amount through that edited work field at line 177 before writing the map field at
 * line 183. The map field corroborates the ceiling independently, because
 * {@code TRNAMTI PIC X(12)} at line 102 of the symbolic map is exactly a sign, eight digits, a
 * decimal point and two more, and a ninth integer digit would need a thirteenth character
 * position it does not have. This component carries all nine.
 *
 * <p>Assumptions: the edited widths differ between reference screens deliberately, so neither is
 * a general rule to generalise from. {@code app/cbl/COBIL00C.cbl} declares
 * {@code WS-CURR-BAL PIC +9999999999.99} at line 56 -- ten integer digits, matching an account
 * balance rather than a transaction amount -- while its line 55 declares the same eight-digit
 * transaction-amount edit this screen uses. Reading the eight-digit edit as the width of money in
 * general would truncate two digits of a value the reference record demonstrably holds.
 *
 * <p>The remaining three narrowings are plain truncations for display and are carried here at
 * record width: the description at {@code PIC X(100)} appears as {@code TDESCI PIC X(60)} at line
 * 96 of the symbolic map, the merchant name at {@code PIC X(50)} appears as
 * {@code MNAMEI PIC X(30)} at line 126, and the merchant city at {@code PIC X(50)} appears as
 * {@code MCITYI PIC X(25)} at line 132. The merchant postal code is the one field the reference
 * screen does not narrow, matching the record at {@code MZIPI PIC X(10)} on line 138.
 *
 * <p>Trade-offs: carrying record widths means this response can hold a value the reference
 * terminal could not render -- a hundred-character description, or a nine-digit amount. The
 * compromise accepted is that the wire shape is broader than the terminal's, so a client
 * rendering into a fixed-width column has to decide for itself what to do with the surplus.
 * Truncating each component to its display width was rejected because it would discard data the
 * record demonstrably holds and would make the response depend on which screen happened to ask
 * for it.
 *
 * <h2>The two timestamps are formatted text, not date-time objects</h2>
 *
 * <p>Alternatives Considered: typing the two timestamp components as date-time objects was
 * evaluated and rejected. Their default serialisation emits an ISO-8601 form whose date and time
 * are separated by the letter T and whose fractional second is variable in length, and neither
 * matches the twenty-six-character contract these fields carry, whose separator is a space and
 * whose fractional part is always six digits. Carrying the already-formatted text makes the wire
 * form deterministic without a per-field serialisation annotation, and it keeps the conversion in
 * {@code com.carddemo.transaction.mapper}, which is the one package permitted to hold a
 * representation concern. The cost accepted is that a client wanting a date-time object parses one
 * itself, which is the same work the reference screen's own consumer did.
 *
 * <p>Assumptions: {@code com.carddemo.common.time.TimestampFormatter} produces that exact form,
 * and every value reaching these two components has already passed through it. That formatter
 * exposes no argument-free formatting entry point and no accessor that reads an ambient clock;
 * each of its current-instant entry points requires a clock the caller supplies. No component
 * here reads a clock, and no statement in this docstring should be read as implying one could.
 * The reference screen shows only the date portion of each of these fields, at
 * {@code TORIGDTI PIC X(10)} on line 108 and {@code TPROCDTI PIC X(10)} on line 114, so a client
 * reproducing that screen takes the leading ten characters and the full instant remains available
 * to one that does not.
 *
 * <h2>The card number is representable only in its masked form</h2>
 *
 * <p>Assumptions: the masking is performed in {@code com.carddemo.transaction.mapper} and this
 * component's contract is that no other form of the value can appear in it. The record declares
 * {@code TRAN-CARD-NUM PIC X(16)} at line 15 and the reference screen echoes all sixteen
 * characters at {@code CARDNUMI PIC X(16)} on line 72 of the symbolic map; this response carries
 * the last four digits behind a mask instead, which is a documented divergence rather than a
 * transcription of that screen. A caller needing the unmasked value does not obtain it from this
 * response. No card verification value component exists on this type and none may be added: no
 * endpoint in this migration returns one, and the reference record declares none in the first
 * place.
 *
 * <h2>One nullable return message, and no error message</h2>
 *
 * <p>Assumptions: the message width this response answers in is 75, and the choice between the
 * two candidate fields is derived from the copybook rather than from convention.
 * {@code app/cpy/CVCRD01Y.cpy} declares {@code CCARD-ERROR-MSG PIC X(75)} at line 28 and
 * {@code CCARD-RETURN-MSG PIC X(75)} at line 29. The two are the same width and are not
 * interchangeable, because only the second carries a sentinel: line 30 attaches a message-off
 * condition valued at low values to the return message alone, and the error message has none.
 * That asymmetry settles both component decisions -- this response carries one return message and
 * it is nullable, because the sentinel state maps to an absent message, and it carries no
 * error-message component at all, because the error path belongs to
 * {@code com.carddemo.common.error.ApiError} and its handler in the shared kernel.
 *
 * <p>Assumptions: the sentinel is low values and not spaces, and substituting one for the other
 * changes observable behaviour. An absent message and a blank message of seventy-five characters
 * are different states, and a client rendering nothing for the first and an empty band for the
 * second would diverge on exactly the distinction the reference copybook draws. This is the only
 * component of the fourteen that may be null.
 *
 * <p>Assumptions: 80 is not a width of this interface, and the near miss is worth naming because
 * it appears in the reference program itself. {@code app/cbl/COTRN01C.cbl} declares
 * {@code WS-MESSAGE PIC X(80)} at its line 38, which is internal work storage, and line 217 moves
 * it into the seventy-eight-character screen field {@code ERRMSGI PIC X(78)} at line 144 of the
 * symbolic map, truncating two bytes on the way out. An eighty-character message width therefore
 * never reaches an interface at all and must not be published as one.
 *
 * <h2>What this type deliberately does not carry</h2>
 *
 * <p>Assumptions: the six framing fields present on every one of the reference maps appear on no
 * component here. {@code app/cpy-bms/COTRN01.CPY} declares a four-character transaction name at
 * line 24, two forty-character title constants at lines 30 and 48, an eight-character program name
 * at line 42, and an eight-character date and time at lines 36 and 54. The transaction and program
 * names are identities of the reference transaction monitor with no target analogue, the two
 * titles belong to the user interface screen header, and the date and time are clock reads
 * rendered by the client.
 *
 * <p>Refactoring Rationale: the mechanism this response replaces is the pseudo-conversational
 * exchange the reference view performs, and what was wrong with it is a security property rather
 * than a style. The key the read is driven from arrives from the screen field the client hands
 * back, at {@code app/cbl/COTRN01C.cbl} line 172, and continuity between turns rides in the
 * communication area the same client echoes: line 204 zeroes the re-entry discriminator and lines
 * 205 to 208 transfer control with that area attached. A client therefore supplied part of the
 * state the reference program then trusted. This response carries no session state and no
 * re-entry marker of any kind, and none may be added -- no resubmission flag, no first-entry flag
 * and no turn counter. That matters concretely rather than abstractly, because
 * {@code app/cpy/CSSETATY.cpy} line 20 gates the reference field highlight on precisely that
 * discriminator, so error presentation there depended on a remembered turn count. Here it is
 * driven by the response body alone.
 *
 * <p>Alternatives Considered: Lombok and MapStruct were both evaluated and both rejected for this
 * package as a whole; {@code com.carddemo.transaction.dto}'s package charter carries the reasoning,
 * which turns on generated members being undocumentable and on copybook-to-transfer-object mapping
 * being non-mechanical. A Java 21 record with hand-written mapping is what replaces them.
 *
 * <p>Assumptions: no component here is an ordinal position of any kind, and this type imports no
 * persistence entity from {@code com.carddemo.transaction.domain}. A detail read addresses one
 * record by its key, so it has no sequence to page through; the list reply in this package is
 * where the shared cursor envelope applies, and it is never redeclared. The conversion from the
 * entity belongs to {@code com.carddemo.transaction.mapper}, and the prohibition on reaching
 * across that boundary is asserted by the ArchUnit layering rules this module runs against its own
 * classes rather than by convention.
 *
 * <p>Assumptions: the declared widths above are documented rather than annotated on this type. A
 * size constraint is evaluated on a value entering the application, and this record only leaves
 * it, so a constraint here would never be exercised; the request types in this package are where
 * the copybook widths become constraint values.
 *
 * @param transactionId the key identifying this transaction, echoed back from the read, from
 *     {@code TRAN-ID PIC X(16)} at line 5 of {@code app/cpy/CVTRA05Y.cpy}; borne as digit
 *     characters so that a leading zero survives the round trip
 * @param typeCode the two-character transaction type, from {@code TRAN-TYPE-CD PIC X(02)} at line
 *     6 of that copybook; the value the reference-data context's type table is keyed by
 * @param categoryCode the four-digit category within that type, from
 *     {@code TRAN-CAT-CD PIC 9(04)} at line 7; borne as characters because the reference screen
 *     itself declares this field {@code X(4)}, and because a numeric form would drop a leading
 *     zero the four-digit key space allows
 * @param source the ten-character channel the transaction originated through, from
 *     {@code TRAN-SOURCE PIC X(10)} at line 8
 * @param description the free-text narrative of the transaction, from
 *     {@code TRAN-DESC PIC X(100)} at line 9, at the record's hundred characters rather than the
 *     sixty the reference screen displays
 * @param amount the monetary value of the transaction, from {@code TRAN-AMT PIC S9(09)V99} at
 *     line 10, exact at nine integer digits and a scale of two; serialised as a quoted decimal
 *     string, and never as a JSON number
 * @param merchantId the nine-digit identifier of the merchant, from
 *     {@code TRAN-MERCHANT-ID PIC 9(09)} at line 11; borne as characters for the same reason as
 *     the category code
 * @param merchantName the merchant's name, from {@code TRAN-MERCHANT-NAME PIC X(50)} at line 12,
 *     at the record's fifty characters rather than the thirty the reference screen displays
 * @param merchantCity the merchant's city, from {@code TRAN-MERCHANT-CITY PIC X(50)} at line 13,
 *     at the record's fifty characters rather than the twenty-five the reference screen displays
 * @param merchantZip the merchant's postal code, from {@code TRAN-MERCHANT-ZIP PIC X(10)} at line
 *     14; the one field of these four the reference screen carries at full record width
 * @param cardNumber the card the transaction was presented on, from
 *     {@code TRAN-CARD-NUM PIC X(16)} at line 15, masked to its last four digits; the masked form
 *     is the only form representable in this component, and no caller obtains the full value here
 * @param originTimestamp the instant the transaction was originated, from
 *     {@code TRAN-ORIG-TS PIC X(26)} at line 16, as twenty-six characters whose date and time are
 *     separated by a space; the reference screen shows only the leading ten
 * @param processTimestamp the instant the transaction was processed, from
 *     {@code TRAN-PROC-TS PIC X(26)} at line 17, in that same twenty-six-character form
 * @param returnMessage the confirmation or advisory text accompanying a successful read, from
 *     {@code CCARD-RETURN-MSG PIC X(75)} at line 29 of {@code app/cpy/CVCRD01Y.cpy}; null when
 *     there is no message, mirroring that field's low-values sentinel, and the only component
 *     here that may be null
 */
public record TransactionDetailResponse(
    String transactionId,
    String typeCode,
    String categoryCode,
    String source,
    String description,
    // WHY : Assumptions: the shared Jackson module binds its serialiser to this exact type, so
    //       the declared type is what selects the quoted-string wire form. Substituting a bare
    //       decimal here compiles and runs and silently emits a JSON number instead.
    Money amount,
    String merchantId,
    String merchantName,
    String merchantCity,
    String merchantZip,
    // WHY : Assumptions: masking happens upstream in the mapper, so this component never holds
    //       the sixteen characters the reference record declares at CVTRA05Y line 15. Declaring
    //       it here as an ordinary string is deliberate: the constraint is the contract of the
    //       producing mapper, and a distinct type would imply this record could enforce it.
    String cardNumber,
    String originTimestamp,
    String processTimestamp,
    // WHY : Assumptions: CVCRD01Y line 30 attaches a low-values sentinel to this field alone,
    //       and line 28's error message carries none, so absence is representable for this one
    //       field and null is what represents it. Spaces are a different state and are not
    //       substituted for it.
    String returnMessage) {
}
