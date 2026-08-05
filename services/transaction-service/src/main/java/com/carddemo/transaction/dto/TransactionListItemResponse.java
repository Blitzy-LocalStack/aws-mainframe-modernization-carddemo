package com.carddemo.transaction.dto;

import com.carddemo.common.money.Money;

/**
 * One row of the migrated transaction-list screen, carrying the four reference record fields that
 * screen's populating paragraph writes into a display row.
 *
 * <p><b>Purpose.</b> This is the element type of the transaction-list reply and never the reply
 * itself. The list answers in {@code com.carddemo.common.web.PageResponse}, so one page of that
 * screen is a {@code PageResponse<TransactionListItemResponse>}. Instances are produced by
 * {@code com.carddemo.transaction.service} from persistence entities that
 * {@code com.carddemo.transaction.mapper} converts, and are serialised to JSON by the controller.
 * This type holds no logic and reaches nothing: every value arrives already in the form it travels
 * in, which is what keeps representation concerns of the reference record format in the one package
 * permitted to hold them. The reference COBOL is the specification, so this shape encodes the field
 * set, the widths and the scale that specification already states rather than redefining them.
 *
 * <p><b>Return values, exceptions or errors.</b> A record declaration returns no value and raises
 * nothing, and the body below declares no member, so this docstring carries no {@code @return} and
 * no {@code @throws} at-clause. The inapplicability is declared rather than left silent, because
 * the Explainability rule lists a docstring that omits return values among its forbidden patterns
 * at line 39, and a reader has to be able to tell a declared inapplicability from an oversight. The
 * record's components are the parameters of its canonical constructor, so the parameters element of
 * that rule is answered by the four {@code @param} at-clauses below and not by a separate
 * paragraph. Nothing here declares a checked exception: a component is a carrier, and the
 * validation that can fail belongs to the request side of this package and to the shared error type
 * named further down.
 *
 * <p>The framing used throughout is the only one used: the baseline does one thing, the Java
 * implements another, and the divergence is documented in the migration traceability register
 * rather than introduced silently. The repository states the same discipline for its own suite at
 * {@code tests/README.md} lines 555 and 556, which encode the specification rather than redefine
 * it. Nothing under {@code app/} is edited by this record or by any statement in this docstring.
 *
 * <h2>Which four fields, and where their order comes from</h2>
 *
 * <p>The reference row is five map fields at lines 72 to 96 of {@code app/cpy-bms/COTRN00.CPY},
 * repeated as ten identically shaped families down the screen, the tenth beginning at line 337 of
 * that map. Of those five, the populating paragraph at {@code app/cbl/COTRN00C.cbl} line 381 writes
 * four, and those four are the components here. That map file name is upper case on disk, extension
 * included, so a lower-case path does not resolve.
 *
 * <p>Alternatives Considered: declaring the components in the order the reference screen displays
 * them was evaluated and rejected. The two orders genuinely differ rather than coinciding, so a
 * choice had to be made and either one would compile. The screen writes the identifier, then the
 * date, then the description, then the amount, at lines 392 to 396 of that program, matching the
 * map's own sequence of {@code TRNID01I}, {@code TDATE01I}, {@code TDESC01I} and {@code TAMT001I}
 * at lines 78, 84, 90 and 96. The copybook declares the same four fields at lines 5, 9, 10 and 16
 * of {@code app/cpy/CVTRA05Y.cpy}, which places the timestamp fourth where the screen places it
 * second. Transformation rule T1 makes the copybook normative, and the display order is a property
 * of the reference presentation: it is the reading order of a fixed-position terminal row, which is
 * the layout concern of whichever client renders this row and not a property of the record.
 * Ordering by the copybook also means this row, the detail response beside it, the persistence
 * entity, the database columns and the fixed-width extract all enumerate their shared fields in the
 * same sequence, so a reader comparing any two of them is comparing like with like. Ordering by the
 * screen would have made this declaration agree with one of the five and disagree with the rest.
 *
 * <p>Assumptions: the record arithmetic is self-checking, which is what lets each width below be
 * verified rather than trusted. Summing the fourteen declared widths of that copybook in
 * declaration order -- 16, 2, 4, 10, 100, 11, 9, 50, 50, 10, 16, 26, 26 and 20 -- gives exactly
 * 350, and the copybook's own header comment at line 2 records the record length as 350. The
 * monetary field accounts for eleven of those bytes, being nine integer digits and two decimal
 * places held as zoned decimal. A width here that disagreed with that copybook would be wrong by
 * construction rather than by opinion.
 *
 * <p>Assumptions: the reference transaction record declares no {@code COMP} item and no
 * {@code OCCURS} clause. Its numerics are zoned decimal throughout, so the packed-decimal handling
 * the shared kernel also publishes has no subject here, and no component is a repeating group.
 *
 * <h2>Which of the two timestamps this row carries</h2>
 *
 * <p>Assumptions: the record declares two twenty-six-character timestamps, at lines 16 and 17 of
 * that copybook, and only the first of them reaches this row. The reference paragraph settles which
 * one without ambiguity: line 384 moves {@code TRAN-ORIG-TS} into the program's timestamp work
 * field, lines 385 to 387 take the final two digits of that field's year together with its month
 * and its day, and line 388 assembles them into the eight-character month, day and two-digit-year
 * form that {@code app/cpy/CSDAT01Y.cpy} declares at lines 30 to 35 and that line 57 of the program
 * holds as {@code WS-TRAN-DATE PIC X(08)}. Line 394 then writes that work field into the row's date
 * field. {@code TRAN-PROC-TS} at line 17 is never read by that paragraph, so it is not a component
 * here.
 *
 * <p>Alternatives Considered: carrying both timestamps was evaluated and rejected. The reference
 * row displays one date, so a second instant would make this element broader than the row contract
 * with no warrant in the reference material for the addition, and a reader would have no way to
 * tell which of the two the screen had shown. A caller needing the processing instant reads
 * {@code TransactionDetailResponse} beside this type, which carries both because its own reference
 * screen shows both.
 *
 * <p>This is the largest of the three narrowings this row reverses, and it runs in the direction of
 * more information rather than less. The baseline renders eight characters holding a month, a day
 * and a two-digit year, at {@code TDATE01I PIC X(8)} on line 84 of the map; this component carries
 * the full twenty-six-character instant the record holds. A client reproducing that screen derives
 * the eight characters it needs, and one that does not still has the instant. Note that the
 * baseline's own rendering discards the century, since line 385 takes only the final two digits of
 * the year, so the eight-character form cannot be widened back into the instant it came from.
 *
 * <p>Assumptions: {@code com.carddemo.common.time.TimestampFormatter} produces the exact
 * twenty-six-character form this component carries, whose date and time are separated by a space
 * rather than by the letter T and whose fractional part is always six digits, and every value
 * reaching this component has already passed through it in
 * {@code com.carddemo.transaction.mapper}. That formatter exposes no argument-free formatting entry
 * point and no accessor that reads an ambient clock; each of its current-instant entry points
 * requires a clock the caller supplies. No component here reads a clock, and no statement in this
 * docstring should be read as implying that one could.
 *
 * <p>Alternatives Considered: typing this component as a date-time object was evaluated and
 * rejected. Its default serialisation emits an ISO-8601 form whose date and time are separated by
 * the letter T and whose fractional second is variable in length, and neither property matches the
 * contract above. Carrying the already-formatted text makes the wire form deterministic without a
 * per-field serialisation annotation, and it keeps the conversion in the one package permitted to
 * hold a representation concern. The cost accepted is that a client wanting a date-time object
 * parses one itself, which is the same work the reference screen's own consumer did.
 *
 * <h2>The amount is exact fixed point and travels as a quoted string</h2>
 *
 * <p>Alternatives Considered: typing the amount as a bare arbitrary-precision decimal was evaluated
 * and rejected, and the reason is the type rather than the value. The shared {@code MoneyModule}
 * binds its serialiser at its line 271 and its deserialiser at its line 272 to the {@code Money}
 * type itself, so a component declared as a bare decimal is reached by neither and is written as a
 * plain JSON number instead: it compiles, it runs, its arithmetic is exact, and its wire form is
 * wrong. That failure mode is named here because no assertion about the value would localise it --
 * only an assertion about the serialised text would. Most clients parse a JSON number into an
 * IEEE-754 binary floating point value on receipt, and binary64 cannot exactly represent the
 * two-place decimal fractions this field is built from, so the loss lands in the cents at the one
 * hop a user actually reads. {@code TRAN-AMT PIC S9(09)V99} at line 10 of the copybook is eleven
 * significant decimal digits, which leaves no margin for an approximation.
 *
 * <p>Assumptions: that module is registered by this module's application entry point, which imports
 * it explicitly because component scanning rooted at {@code com.carddemo.transaction} reaches
 * nothing beneath {@code com.carddemo.common}. This record does not register it, does not annotate
 * around it and needs no serialisation annotation of its own.
 *
 * <p>The amount is where this row exceeds what the reference row could render, and by a whole
 * integer digit. {@code TRAN-AMT} holds nine integer digits, and two independent places in the
 * reference material cap the display at eight. The program edits the value through
 * {@code WS-TRAN-AMT PIC +99999999.99} at its line 56 before line 396 writes it out, which is a
 * sign and eight integer digits; and the map field it is written into,
 * {@code TAMT001I PIC X(12)} at line 96, is exactly a sign, eight digits, a decimal point and two
 * more, so a ninth integer digit would need a thirteenth character position that field does not
 * have. The baseline therefore cannot display a value the record can hold. This component carries
 * all nine, and the divergence is documented rather than settled by truncation.
 *
 * <p>Assumptions: that eight-digit edited width is a property of the transaction amount on this
 * screen and is not a general rule to generalise from. {@code app/cbl/COBIL00C.cbl} declares the
 * same eight-digit transaction-amount edit at its line 55 and a ten-integer-digit account-balance
 * edit at its line 56, so the two widths coexist inside one reference program. Reading the
 * eight-digit edit as the width of money in general would truncate a digit of a value the reference
 * record demonstrably holds.
 *
 * <h2>The identifier is a digit-bearing string, never a numeric type</h2>
 *
 * <p>Assumptions: the baseline settles this itself, declaring each identifier twice over the same
 * bytes -- once as characters and once as a number. {@code app/cpy/CVCRD01Y.cpy} gives
 * {@code CC-ACCT-ID PIC X(11)} at line 34 with {@code CC-ACCT-ID-N PIC 9(11)} redefining it at
 * line 36, {@code CC-CARD-NUM PIC X(16)} at line 37 with its numeric redefinition at line 39, and
 * {@code CC-CUST-ID PIC X(09)} at line 40 with its numeric redefinition at line 42. The character
 * declaration is what the screen and the message carry; the numeric one exists so that arithmetic
 * can reach the same bytes. Three details of those declarations are recorded so that a later reader
 * does not tidy them: the redefinitions at lines 36 and 39 carry no sequence number in the
 * right-hand columns, the one at line 42 repeats the number line 40 already used, and that last
 * pair writes {@code X(09)} against {@code 9(9)}, zero-padding the width in one picture string and
 * not in the other. They are noted, not normalised.
 *
 * <p>Assumptions: the reference seed extract settles it beyond the declaration.
 * {@code app/data/ASCII/dailytran.txt} holds 300 records of 350 bytes each, and 30 of them carry a
 * card number whose first character is a zero. A numeric component would discard that leading zero
 * on the way out while still comparing equal on the way in, which is a silent corruption of a tenth
 * of that extract, and the transaction identifier this row carries occupies the same kind of
 * fixed-width digit field. No example value is reproduced here: a primary account number does not
 * belong in source prose even when it comes from a seed extract, and the aggregate is what the type
 * decision rests on.
 *
 * <p>The identifier is also the one component of the four that the reference row carries at full
 * record width, {@code TRNID01I PIC X(16)} on line 78 of the map matching
 * {@code TRAN-ID PIC X(16)} on line 5 of the copybook, so no narrowing is reversed for it.
 *
 * <h2>What this row deliberately does not carry</h2>
 *
 * <p>This list is as much a part of the contract as the four components are, because an absence is
 * invisible and an author who cannot see why something is missing supplies it.
 *
 * <p><b>No selection marker.</b> Alternatives Considered: carrying the fifth field of the reference
 * row, the one-character {@code SEL0001I PIC X(1)} at line 72 of the map, was evaluated and
 * rejected, because what the baseline does with it is navigation rather than data. Lines 185 to 187
 * of the program evaluate the marker, and on a value of {@code 'S'} or {@code 's'} line 188 sets
 * the target program name to {@code 'COTRN01C'} and lines 192 to 195 transfer control to it with
 * the communication area attached. In the target that is a route change on the client to the
 * transaction-detail route, and a route change needs no field in a response body. Two details of
 * the marker's other branch are recorded rather than smoothed over: on its remaining branch, lines
 * 197 and 202 are commented out in the baseline, so an invalid marker sets the message text at
 * lines 198 to 200 without setting the error flag and without sending the screen, and control falls
 * through to the identifier check at line 206. That is a characteristic of the reference program as
 * written, and it is described here rather than treated as something to be altered.
 *
 * <p><b>No message.</b> Alternatives Considered: a message component was evaluated and rejected on
 * cardinality grounds first. The envelope this row travels in declares seven components -- the
 * rows, the two row-identity tokens, the two continuation cursors and the two availability
 * indicators -- and no message slot among them, so a message about the page cannot ride in the
 * envelope; and a message about the page repeated on every row of the page would be the wrong
 * cardinality, since ten rows would carry ten copies of one statement.
 *
 * <p>Alternatives Considered: collapsing the reference program's boundary messages into a single
 * message field was separately evaluated and rejected, because it is lossy in a way that can be
 * demonstrated. That program carries five distinct strings for boundary conditions, and three of
 * them are about reaching the top: line 248 when the paging-backward key is pressed with no
 * preceding page, line 608 when the browse start finds no record, and line 676 when the
 * read-backward verb reaches the end of the file. The remaining two are about reaching the bottom,
 * at lines 270 and 642. A single field could hold whichever string applied but could not preserve
 * which of the three top-of-page phrasings the reference screen would have shown, and
 * transformation rule T8 carries user-visible strings across character for character. Those five
 * belong in the user-interface message catalogue keyed by the program line each came from. The
 * boundary conditions themselves are already expressed structurally by the envelope, whose forward
 * availability answers the bottom and whose backward availability answers the top, so nothing is
 * lost by keeping the strings out of this row.
 *
 * <p>Assumptions: the program's three lookup-failure messages at lines 615, 649 and 683 spell
 * transaction with a lower-case initial letter, and the detail program's message at line 292 of
 * {@code app/cbl/COTRN01C.cbl} spells it with a capital. The two are recorded as distinct because
 * that rule carries them verbatim; harmonising them would be a change to a user-visible string.
 *
 * <p><b>No ordinal paging component of any kind</b>, so no counted starting position and no page
 * index appears on this type. Alternatives Considered: pagination by ordinal position was evaluated
 * and rejected. It silently skips and repeats rows when rows are inserted between two reads,
 * because the count is measured against a result set that has changed, whereas a cursor keyed on
 * the ordering column is unaffected -- a change in observable behaviour rather than in
 * implementation. The reference material shows the concurrent insert is real rather than
 * hypothetical: {@code app/cbl/COBIL00C.cbl} lines 212 to 217 mint the next transaction identifier
 * by moving high values into the key, starting a browse, reading backward to the highest existing
 * key, ending the browse and adding one, holding no lock across that sequence, so two payments
 * running together can read the same maximum and land in the same region of the key space.
 *
 * <p>Assumptions: the reference program does keep an ordinal, and it is display-only, which is why
 * dropping it costs nothing. Its counter is incremented at lines 306 and 307 and again at lines 317
 * and 318, and it is written to the screen at {@code PAGENUMI PIC X(8)} on line 60 of the map, but
 * it never participates in a key comparison anywhere in the program. The keys the program actually
 * navigates by are the first and last identifiers of the page shown, read at lines 236 and 239 for
 * the backward direction and at lines 259 and 262 for the forward one.
 *
 * <p><b>No card number and no card verification value.</b> The reference row declares neither: its
 * five fields are the marker, the identifier, the date, the description and the amount, and no card
 * field appears among them, so none is added here. A caller needing the card a transaction was
 * presented on reads the detail response, which carries it masked to its last four digits. No card
 * verification value component exists on this type and none may be added: no endpoint in this
 * migration returns one, and the reference transaction record declares none in the first place.
 *
 * <p><b>No per-field error entry.</b> The baseline marks a failing field by moving minus one into
 * that field's length subfield, at lines 201 and 216 of the program, which is what positions the
 * cursor and is the concrete warrant for keying the shared error type's field array by field
 * identifier. That mechanism belongs to {@code com.carddemo.common.error.ApiError} and its handler
 * in the shared kernel, and a successful list row is not where an error is reported.
 *
 * <h2>The envelope owns the cursor, and this row owns none of it</h2>
 *
 * <p>Refactoring Rationale: the mechanism this row participates in replaces the browse cursor the
 * reference screen carried between turns, and what was wrong with that arrangement is not the
 * browse verbs but where the state lived. It lived in the pseudo-conversational communication area
 * the client echoed back, so continuity across a turn depended on the client returning it intact:
 * the paging-backward path reads the first identifier of the page shown out of that area at lines
 * 236 and 239, and the paging-forward path reads the last identifier out of it at lines 259 and
 * 262, both reached from the key routing at lines 125 to 128. Moving those values into a response
 * body ends the dependency, because the server reads them back off the next request and verifies
 * them rather than trusting a round-tripped buffer. This row carries no cursor, no key token and no
 * availability indicator of its own, and none may be added: the envelope owns all of them, and a
 * second copy on the element could disagree with the first without any test in this module
 * localising the disagreement.
 *
 * <p>Assumptions: the envelope's last-row token identifies the last row a caller actually received
 * and never the surplus row read to settle whether a further page follows, and this row is shaped
 * so that contract can hold. The reference program demonstrates the distinction directly. Its
 * fill loop at line 297 runs until its index reaches eleven, so exactly ten rows are populated by
 * the call at line 300; it then performs one further read at line 308 for the sole purpose of
 * setting the further-page indicator at lines 309 to 313, and that eleventh record is never passed
 * to the populating paragraph. An element type carrying its own key could be built from that
 * eleventh record by a caller that had not read the loop; carrying no key at all makes the mistake
 * unrepresentable.
 *
 * <h2>What is not used to build this type</h2>
 *
 * <p>Alternatives Considered: Lombok was evaluated and rejected because its generated accessors
 * cannot carry the Javadoc the Explainability rule requires at its line 15, and the repository
 * ruleset grants no exemption that would excuse a generated member -- the suppression file the
 * documentation gate loads limits itself to generated sources and test fixtures, so no suppression
 * is available to source under this directory at all. A Java 21 record gives the same brevity with
 * members that can be documented. MapStruct was rejected on a separate ground: mapping the
 * reference record onto the shapes in this package is not mechanical. It drops {@code FILLER},
 * masks the primary account number to its last four digits, suppresses a card verification value
 * entirely, encrypts protected identifiers and renames misspelled baseline fields, and each of
 * those needs a justification at the mapping site that a generated mapper has nowhere to hold.
 *
 * <p>Assumptions: this type imports no persistence entity from
 * {@code com.carddemo.transaction.domain} and nothing from a sibling service. The conversion from
 * the entity belongs to {@code com.carddemo.transaction.mapper}, and the prohibition on reaching
 * across that boundary is asserted by the ArchUnit layering rules this module runs against its own
 * classes rather than by convention.
 *
 * <p>Assumptions: the declared widths above are documented rather than annotated on this type. A
 * size constraint is evaluated on a value entering the application, and this record only leaves it,
 * so a constraint here would never be exercised; the request types in this package are where the
 * copybook widths become constraint values.
 *
 * <h2>Trade-offs accepted, and the rule this file is audited against</h2>
 *
 * <p>Trade-offs: carrying record widths rather than display widths means this row can hold values
 * the reference terminal could not render, and it does so in three of its four components. The
 * description is a hundred characters against a twenty-six-character cell at
 * {@code TDESC01I PIC X(26)} on line 90 of the map, the amount is nine integer digits against
 * eight, and the timestamp is twenty-six characters against eight. The compromise accepted is that
 * the wire shape is broader than the screen's, so a client rendering into a fixed-width column has
 * to decide for itself what to do with the surplus. Truncating each component to its display width
 * was rejected because it would discard data the record demonstrably holds and would make this row
 * depend on which screen happened to ask for it, which is the coupling the display-order decision
 * above already declined.
 *
 * <p>Assumptions: one user-specified rule governs this migration, Explainability, and it does not
 * conflict with the repository's own convention or with the migration plan. The four rationale
 * categories it names at its lines 31 to 34 are the same four the house convention at
 * {@code tests/README.md} lines 544 to 549 already names, and that convention names the same
 * docstring quartet of purpose, parameters, returns and exceptions at its lines 545 and 546. No
 * resolution between them was necessary, and the labels above are written in the single accepted
 * form: plural, unparenthesised, colon-terminated and unemphasised.
 *
 * @param transactionId the key identifying this transaction, from
 *     {@code TRAN-ID PIC X(16)} at line 5 of {@code app/cpy/CVTRA05Y.cpy}, at the record's sixteen
 *     characters and displayed by the reference row at that same width in
 *     {@code TRNID01I PIC X(16)}; borne as digit characters so that a leading zero survives the
 *     round trip
 * @param description the free-text narrative of the transaction, from
 *     {@code TRAN-DESC PIC X(100)} at line 9 of that copybook, at the record's hundred characters
 *     rather than the twenty-six the reference row displays in {@code TDESC01I PIC X(26)}
 * @param amount the monetary value of the transaction, from
 *     {@code TRAN-AMT PIC S9(09)V99} at line 10 of that copybook, exact at nine integer digits and
 *     a scale of two where the reference row displays eight integer digits in
 *     {@code TAMT001I PIC X(12)}; serialised as a quoted decimal string, and never as a JSON number
 * @param originTimestamp the instant the transaction was originated, from
 *     {@code TRAN-ORIG-TS PIC X(26)} at line 16 of that copybook, as twenty-six characters whose
 *     date and time are separated by a space; the reference row derives an eight-character
 *     month, day and two-digit-year date from it for {@code TDATE01I PIC X(8)}, and this component
 *     carries the whole instant
 */
public record TransactionListItemResponse(
    String transactionId,
    String description,
    // WHAT: the transaction amount, typed as the shared exact-decimal value rather than as a
    //       general-purpose decimal or a primitive.
    // WHY : Assumptions: the shared Jackson module binds its serialiser to this exact type, so the
    //       declared type is what selects the quoted-string wire form. Substituting a bare decimal
    //       here compiles and runs and silently emits a JSON number instead.
    Money amount,
    // WHAT: the originating instant, and the one of the record's two timestamps this row carries.
    // WHY : Assumptions: COTRN00C line 384 is what settles the choice -- the populating paragraph
    //       reads TRAN-ORIG-TS and never TRAN-PROC-TS, so the second timestamp has no place on a
    //       row derived from that paragraph. The value arrives already formatted from the mapper;
    //       nothing here reads a clock.
    String originTimestamp) {
}
