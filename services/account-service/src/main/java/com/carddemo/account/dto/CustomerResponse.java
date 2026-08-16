package com.carddemo.account.dto;

/**
 * Response wire contract of the customer read endpoints, derived field for field from the baseline
 * customer record.
 *
 * <p>Each component below corresponds to exactly one {@code 05} level item of
 * {@code 01 CUSTOMER-RECORD}, which is declared at {@code app/cpy/CVCUS01Y.cpy} L4 and runs from L5
 * through L22. That copybook is normative for this record: a component's name, its documented width,
 * and the decision to carry it at all are taken from that declaration and from nothing else.</p>
 *
 * <p>Assumptions: no narrower published projection existed to inherit, because the reading program
 * has none. {@code app/cbl/CBCUS01C.cbl} selects the customer file at L29, keys it with
 * {@code RECORD KEY IS FD-CUST-ID} at L32, includes this same copybook with {@code COPY CVCUS01Y.} at
 * L45, and then emits the entire group item with {@code DISPLAY CUSTOMER-RECORD} at L78 and again at
 * L96. It selects no subset of fields and it masks nothing, so the whole record is the only shape the
 * baseline publishes. The keyed read at L32 is also why the customer identifier is the resource key of
 * these endpoints and the first component here.</p>
 *
 * <h2>How this shape was chosen</h2>
 *
 * <p>Alternatives Considered: a class with machine-generated accessors, and a machine-generated
 * mapping framework to populate it. Both were rejected, on two separate grounds. An
 * accessor-generating annotation processor emits members that carry no documentation of their own,
 * and {@code config/checkstyle/checkstyle.xml} sets {@code allowMissingPropertyJavadoc} to
 * {@code false} at L358 with {@code scope} set to {@code private} at L357, so those members would
 * fail a gate that runs before this file is compiled. A generated mapping framework fails for an
 * unrelated reason: the translation into this record is not mechanical. It drops a {@code FILLER}, it
 * masks two identifiers, and it carries one component whose name deliberately disagrees with the
 * screen label the baseline renders that value under. Each of those needs a justification recorded at
 * the point of use, and generated code has nowhere to hold one. A record with an explicit component
 * list keeps the brevity of generation and leaves every component documentable.</p>
 *
 * <p>Assumptions: {@code FILLER PIC X(168)} at {@code app/cpy/CVCUS01Y.cpy} L23 has no counterpart
 * component here. It is padding that brings the group item up to its declared length rather than a
 * value any reader consumes. Arithmetic settles that this omission is the only one: the eighteen
 * declared widths named below sum to 332, and 332 + 168 = 500, which is the RECLN 500 the copybook
 * states in its own header comment at L2. A component set summing to anything else would have either
 * lost a field or invented one, so the total is a self-check rather than a remark.</p>
 *
 * <p>Assumptions: every component is declared {@code String}, including the three whose COBOL picture
 * is numeric: {@code CUST-ID} at L5, {@code CUST-SSN} at L17, and {@code CUST-FICO-CREDIT-SCORE} at
 * L22 of {@code app/cpy/CVCUS01Y.cpy}. All three are identifiers or codes rather than arithmetic
 * operands, and the baseline already treats them that way. Inside the before-image group opened at
 * {@code app/cbl/COACTUPC.cbl} L669, {@code ACUP-OLD-CUST-ID-X} is declared {@code PIC X(09)} at L710
 * and only then redefined as {@code PIC 9(09)} at L711 to L712, and
 * {@code ACUP-OLD-CUST-FICO-SCORE-X} is declared {@code PIC X(03)} at L754 and redefined as
 * {@code PIC 9(03)} at L755 to L756. Characters on the wire, numbers only in arithmetic. A numeric
 * Java type would additionally discard a leading zero, which each of the three can carry because each
 * is a positional code of a declared width.</p>
 *
 * <p>Assumptions: no component of this record carries an amount of money, which is why the file
 * declares no import at all. {@code app/cpy/CVCUS01Y.cpy} declares no {@code S9(n)V99} field anywhere
 * across L5 to L22. The five money fields of this bounded context belong to the account record
 * instead, at {@code app/cpy/CVACT01Y.cpy} L7, L8, L9, L13, and L14, so the absence here is a property
 * of the customer record rather than a gap in this file. It is written down so that a later reader does
 * not introduce an exact-decimal type into this record looking for one.</p>
 *
 * <p>Trade-offs: the widths named in the component descriptions below are documentation, and no length
 * constraint is declared on this record. A runtime length rejection was considered and rejected,
 * because it would refuse values the baseline accepts. The baseline pads a short value out to the
 * declared width rather than rejecting it, and on output it truncates rather than failing:
 * {@code app/cbl/COACTVWC.cbl} L515 moves ten characters into a five-character screen field, and L517
 * and L518 each move fifteen characters into thirteen. A response contract that rejected what the
 * source system stores would make records unreadable through this endpoint that remain readable
 * through the baseline. The cost accepted is that a caller cannot learn a width from the type
 * signature and has to read it here.</p>
 *
 * <h2>The one component whose name disagrees with its screen label</h2>
 *
 * <p>Assumptions: {@code addressLine3} is named after the copybook field rather than after the label
 * the account view screen renders it under, which is the city. There is no {@code CUST-CITY} field
 * available to name it after: {@code grep -rn "CUST-CITY" app/cpy/} returns no match, and
 * {@code grep -c "CUST-CITY" app/cbl/CBCUS01C.cbl} returns zero. What exists instead is a move,
 * {@code app/cbl/COACTVWC.cbl} L513 sending {@code CUST-ADDR-LINE-3} into the screen's city field, and
 * the two widths agree exactly at fifty characters: {@code PIC X(50)} at
 * {@code app/cpy/CVCUS01Y.cpy} L11 and {@code ACSCITYI PIC X(50)} at
 * {@code app/cpy-bms/COACTVW.CPY} L192. Naming the component for the screen would assert a stored
 * field that does not exist, while naming it for the copybook keeps the derivation checkable against
 * one cited line. This is the concrete mapping that is not one to one, and it is the case that rules
 * out generating the translation.</p>
 *
 * <h2>What this record withholds</h2>
 *
 * <p>Trade-offs: the national identifier and the government-issued identifier are held encrypted and
 * are published here masked, which the component names {@code ssnMasked} and
 * {@code governmentIssuedIdMasked} state on their face. This is an addition rather than a port: the
 * baseline publishes both in full. {@code app/cbl/COACTVWC.cbl} L496 to L504 composes
 * {@code CUST-SSN} for display as {@code CUST-SSN(1:3)}, a separator, {@code CUST-SSN(4:2)}, a
 * separator, and {@code CUST-SSN(6:4)}, which is nine digits plus two separators, eleven characters
 * into the {@code ACSTSSNI PIC X(12)} declared at {@code app/cpy-bms/COACTVW.CPY} L132; and L519 moves
 * {@code CUST-GOVT-ISSUED-ID} to its screen field with no transformation at all. The cost is real and
 * is worth naming: a caller of this endpoint cannot reconcile either identifier against the source
 * system from the response alone and has to hold its own copy to do so. Publishing what the baseline
 * publishes was the alternative, and it was declined because these two are the record's only directly
 * identifying credentials while a response body is a document a caller may store or log.</p>
 *
 * <p>Alternatives Considered: recording a declined option beside the chosen one continues a practice
 * the baseline already keeps, rather than imposing a new one. {@code app/cbl/COACTVWC.cbl} L495 is a
 * commented-out direct move of {@code CUST-SSN} to the same screen field, left in the source
 * immediately above the composition at L496 to L504 that supersedes it, so the author's discarded
 * option is still readable. The telephone punctuation is treated the same way at
 * {@code app/cbl/COACTUPC.cbl} L91 and L96, where the separator characters survive only as
 * commented-out {@code VALUE} clauses against the one-character {@code FILLER} items declared at L90
 * and L95.</p>
 *
 * <h2>Why this record is not reused on the account view</h2>
 *
 * <p>Alternatives Considered: nesting this record inside the account view response instead of letting
 * that contract carry its own customer grouping. Rejected, because the account view is derived at
 * screen widths and four of those disagree with the record widths carried here. The national
 * identifier is a single {@code PIC X(12)} field at {@code app/cpy-bms/COACTVW.CPY} L132 against
 * {@code PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy} L17; both telephone numbers are
 * {@code PIC X(13)} at {@code app/cpy-bms/COACTVW.CPY} L204 and L216 against {@code PIC X(15)} at
 * {@code app/cpy/CVCUS01Y.cpy} L15 and L16; and the postal code is {@code PIC X(5)} at
 * {@code app/cpy-bms/COACTVW.CPY} L186 against {@code PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy} L14.
 * Reuse would silently adopt the wrong width on all four, and on the national identifier it would also
 * discard the composed-with-separators form built at {@code app/cbl/COACTVWC.cbl} L496 to L504. Two
 * shapes cost more declarations and buy a package in which every width is the width its own cited
 * declaration states.</p>
 *
 * @param customerId the customer identifier, and the resource key of these endpoints, from
 *     {@code CUST-ID} at {@code app/cpy/CVCUS01Y.cpy} L5, declared {@code PIC 9(09)}: nine
 *     characters, digits only
 * @param firstName the customer's given name, from {@code CUST-FIRST-NAME} at
 *     {@code app/cpy/CVCUS01Y.cpy} L6, declared {@code PIC X(25)}: twenty-five characters
 * @param middleName the customer's middle name, from {@code CUST-MIDDLE-NAME} at
 *     {@code app/cpy/CVCUS01Y.cpy} L7, declared {@code PIC X(25)}: twenty-five characters
 * @param lastName the customer's family name, from {@code CUST-LAST-NAME} at
 *     {@code app/cpy/CVCUS01Y.cpy} L8, declared {@code PIC X(25)}: twenty-five characters
 * @param addressLine1 the first line of the postal address, from {@code CUST-ADDR-LINE-1} at
 *     {@code app/cpy/CVCUS01Y.cpy} L9, declared {@code PIC X(50)}: fifty characters
 * @param addressLine2 the second line of the postal address, from {@code CUST-ADDR-LINE-2} at
 *     {@code app/cpy/CVCUS01Y.cpy} L10, declared {@code PIC X(50)}: fifty characters
 * @param addressLine3 the third line of the postal address, from {@code CUST-ADDR-LINE-3} at
 *     {@code app/cpy/CVCUS01Y.cpy} L11, declared {@code PIC X(50)}: fifty characters. This is the
 *     line that carries the city: {@code app/cbl/COACTVWC.cbl} L513 moves it into the account view
 *     screen's city field, whose {@code ACSCITYI PIC X(50)} at {@code app/cpy-bms/COACTVW.CPY} L192
 *     is the same width
 * @param stateCode the state or province code, from {@code CUST-ADDR-STATE-CD} at
 *     {@code app/cpy/CVCUS01Y.cpy} L12, declared {@code PIC X(02)}: two characters
 * @param countryCode the country code, from {@code CUST-ADDR-COUNTRY-CD} at
 *     {@code app/cpy/CVCUS01Y.cpy} L13, declared {@code PIC X(03)}: three characters
 * @param zipCode the postal code, from {@code CUST-ADDR-ZIP} at {@code app/cpy/CVCUS01Y.cpy} L14,
 *     declared {@code PIC X(10)}: ten characters, not five. The account view screen field is narrower,
 *     {@code ACSZIPCI PIC X(5)} at {@code app/cpy-bms/COACTVW.CPY} L186, and
 *     {@code app/cbl/COACTVWC.cbl} L515 moves ten characters into it, so the baseline truncates on that
 *     screen; this component is derived at record width and carries all ten, and the divergence is
 *     documented rather than reconciled
 * @param phoneNumber1 the first telephone number, from {@code CUST-PHONE-NUM-1} at
 *     {@code app/cpy/CVCUS01Y.cpy} L15, declared {@code PIC X(15)}: fifteen characters. The internal
 *     layout is declared in the baseline itself, where {@code app/cbl/COACTUPC.cbl} L722 holds the
 *     same fifteen characters and L723 to L731 redefine them as a one-character separator slot, a
 *     three-character part, a separator slot, a three-character part, a separator slot, a
 *     four-character part, and a two-character pad, summing 1 + 3 + 1 + 3 + 1 + 4 + 2 to fifteen
 *     exactly. The account view screen carries thirteen, {@code ACSPHN1I PIC X(13)} at
 *     {@code app/cpy-bms/COACTVW.CPY} L204, and {@code app/cbl/COACTVWC.cbl} L517 moves fifteen into
 *     thirteen, dropping precisely that trailing two-character pad, since 1 + 3 + 1 + 3 + 1 + 4 is
 *     thirteen
 * @param phoneNumber2 the second telephone number, from {@code CUST-PHONE-NUM-2} at
 *     {@code app/cpy/CVCUS01Y.cpy} L16, declared {@code PIC X(15)}: fifteen characters, carrying the
 *     same internal layout as the first. The account view screen again carries thirteen,
 *     {@code ACSPHN2I PIC X(13)} at {@code app/cpy-bms/COACTVW.CPY} L216, with
 *     {@code app/cbl/COACTVWC.cbl} L518 performing the same fifteen into thirteen move
 * @param ssnMasked the national identifier, MASKED, never the whole value, derived from
 *     {@code CUST-SSN} at {@code app/cpy/CVCUS01Y.cpy} L17, declared {@code PIC 9(09)}: nine
 *     characters at rest, held encrypted, and published here only in masked form
 * @param governmentIssuedIdMasked the government-issued identifier, MASKED, never the whole value,
 *     derived from {@code CUST-GOVT-ISSUED-ID} at {@code app/cpy/CVCUS01Y.cpy} L18, declared
 *     {@code PIC X(20)}: twenty characters at rest, held encrypted, and published here only in masked
 *     form
 * @param dateOfBirth the date of birth, from {@code CUST-DOB-YYYY-MM-DD} at
 *     {@code app/cpy/CVCUS01Y.cpy} L19, declared {@code PIC X(10)}: ten characters already ordered
 *     year, month, day, so a lexical comparison of two values orders them as dates. This is the only
 *     date the customer record declares
 * @param eftAccountId the electronic funds transfer account identifier, from
 *     {@code CUST-EFT-ACCOUNT-ID} at {@code app/cpy/CVCUS01Y.cpy} L20, declared {@code PIC X(10)}: ten
 *     characters
 * @param primaryCardHolderIndicator the primary card holder indicator, from
 *     {@code CUST-PRI-CARD-HOLDER-IND} at {@code app/cpy/CVCUS01Y.cpy} L21, declared
 *     {@code PIC X(01)}: one character
 * @param ficoCreditScore the credit score, from {@code CUST-FICO-CREDIT-SCORE} at
 *     {@code app/cpy/CVCUS01Y.cpy} L22, declared {@code PIC 9(03)}: three characters, digits only. It
 *     is a bounded small integer and is not an amount of money
 */
public record CustomerResponse(
    String customerId,
    String firstName,
    String middleName,
    String lastName,
    String addressLine1,
    String addressLine2,
    // WHY : Assumptions: this component is named for the copybook field, not for the screen label.
    //       app/cbl/COACTVWC.cbl L513 moves CUST-ADDR-LINE-3 into the account view screen's city
    //       field, and no CUST-CITY field exists anywhere under app/cpy to name it after. Renaming
    //       this to a city would assert a stored field the baseline does not declare.
    String addressLine3,
    String stateCode,
    String countryCode,
    // WHY : Assumptions: ten characters, not the five the account view screen shows.
    //       app/cpy/CVCUS01Y.cpy L14 declares PIC X(10) while ACSZIPCI at
    //       app/cpy-bms/COACTVW.CPY L186 declares PIC X(5), and app/cbl/COACTVWC.cbl L515 moves the
    //       wider value into the narrower field. This contract is derived at record width, so
    //       narrowing it here to match the screen would discard five characters the record holds.
    String zipCode,
    // WHY : Assumptions: fifteen characters, which is the stored width at
    //       app/cpy/CVCUS01Y.cpy L15 and L16, not the thirteen the account view screen carries at
    //       app/cpy-bms/COACTVW.CPY L204 and L216. The two differ by exactly the trailing
    //       two-character pad that app/cbl/COACTUPC.cbl L731 declares, so a thirteen-character
    //       component would silently adopt the screen's truncation as though it were the layout.
    String phoneNumber1,
    String phoneNumber2,
    // WHY : Trade-offs: both identifiers below are published masked, and the baseline publishes
    //       neither that way: app/cbl/COACTVWC.cbl L496 to L504 renders the national identifier in
    //       full with separators, and L519 passes the government-issued identifier through
    //       untouched. Masking costs the caller the ability to reconcile against the source system
    //       from the response alone; carrying the whole values would instead put the record's only
    //       directly identifying credentials into a document a caller may store or log.
    String ssnMasked,
    String governmentIssuedIdMasked,
    String dateOfBirth,
    String eftAccountId,
    String primaryCardHolderIndicator,
    String ficoCreditScore) {

  /**
   * Renders this projection for a log line, disclosing no part of the customer it describes.
   *
   * <p>Refactoring Rationale: a record's generated rendering prints every component, and on this type
   * that is a name, a whole postal address, two telephone numbers, a date of birth, a credit score, an
   * electronic-funds account identifier and the customer identifier -- the whole customer master row in
   * plain text, reachable from any diagnostic that renders an instance. The two protected identifiers
   * were already masked before reaching this record, so they were never the exposure; everything beside
   * them was.</p>
   *
   * <p>Trade-offs: the rendering keeps ONE component, the primary-cardholder indicator, and nothing
   * else. The sensitive-data logging contract in {@code docs/architecture/observability.md} admits a
   * status code as non-disclosing identity, and that indicator is exactly a status code -- a single
   * character saying whether this customer is the primary cardholder. Every other component is either
   * named among the prohibited values, is free text about a person, or is a date of birth. The cost is
   * that this rendering identifies no row at all; the correlation identifier the shared kernel puts on
   * every request-scoped line is what locates the event instead.</p>
   *
   * <p>Alternatives Considered: keeping the two masked identifier components, on the ground that they
   * are already masked and therefore already safe. Rejected because they are masked to a FIXED marker
   * rather than to a suffix, so emitting them adds two constants to every line and says nothing at all
   * -- unlike the entity's own rendering, where naming a withheld field is what distinguishes a
   * deliberate withholding from a forgotten one. An entity is edited by hand and benefits from that
   * signal; a generated record rendering is compared against its component list, where a missing
   * component is already visible.</p>
   *
   * @return a rendering naming the type and the primary-cardholder indicator only, never {@code null}
   */
  @Override
  public String toString() {
    return "CustomerResponse[primaryCardHolderIndicator=" + this.primaryCardHolderIndicator + ']';
  }
}
