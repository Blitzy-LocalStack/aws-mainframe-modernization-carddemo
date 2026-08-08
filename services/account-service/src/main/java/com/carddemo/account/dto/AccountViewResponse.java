package com.carddemo.account.dto;

import com.carddemo.common.money.Money;

/**
 * The response body of the account view endpoint, carrying the account key, the two field groupings the
 * baseline screen populates under two different guards, and the two message lines that screen displays.
 *
 * <h2>The field census this contract is closed against</h2>
 *
 * <p>Assumptions: the symbolic map {@code app/cpy-bms/COACTVW.CPY} is the field inventory of the view
 * screen, and it is arithmetically closed rather than sampled. Its input group opens
 * {@code 01 CACTVWAI.} at L17 and its output group opens {@code 01 CACTVWAO REDEFINES CACTVWAI.} at
 * L241, so every named data field of the screen lies between them, and the last one is
 * {@code ERRMSGI} at L240. Counting the {@code 02 <name>I PIC} declarations in that span gives
 * thirty-seven, and they partition exactly: six screen-chrome fields, one key, ten account fields,
 * eighteen customer fields and two message fields. This record publishes the key, the account ten, the
 * customer eighteen and the message two -- thirty-one of the thirty-seven -- and the six it omits are
 * accounted for immediately below rather than left as a gap a reader has to reconstruct.</p>
 *
 * <p>Assumptions: the plan's figure of one hundred fields for this screen counts {@code DFHMDF}
 * definitions in {@code app/bms/COACTVW.bms}, which include the protected label fields that render
 * captions. The thirty-seven counted here are the named data fields of the symbolic map. Both figures
 * describe the same screen and neither is reconciled into the other, because a caption is not a
 * transported value and a transported value has no caption in this contract.</p>
 *
 * <p>Assumptions: the six screen-chrome fields are deliberately not components of this record. They are
 * the transaction name at {@code app/cpy-bms/COACTVW.CPY} L24, the first title band at L30, the current
 * date at L36, the program name at L42, the second title band at L48 and the current time at L54. Each
 * is supplied by the presentation shell rather than by this bounded context: the migration plan
 * assigns them to {@code ui/src/layout/ScreenHeader.tsx} with the title text taken from
 * {@code app/cpy/COTTL01Y.cpy}. Transporting a program name or a clock reading in a response body would
 * publish the server's own identity and time as if they were account data. Note also that the
 * {@code 02 FILLER PIC X(12)} at L18 is the terminal input-output-area header the map begins with, and
 * it is never a field of anything.</p>
 *
 * <h2>Why two nested groupings and not one flat record</h2>
 *
 * <p>Alternatives Considered: one flat record of thirty-seven components, or of the thirty-one this
 * contract publishes, with every field independently nullable. It was rejected on evidence from the
 * program that populates the screen. {@code app/cbl/COACTVWC.cbl} fills the two groupings in two
 * separately guarded blocks whose guards are not the same predicate. The account block is guarded by a
 * disjunction, {@code IF FOUND-ACCT-IN-MASTER} at L471 continued by {@code OR FOUND-CUST-IN-MASTER} at
 * L472, and runs its ten moves at L473 through L490. The customer block is guarded by the single
 * condition {@code IF FOUND-CUST-IN-MASTER} at L493 and runs its eighteen moves at L494 through L522.
 * Each grouping is therefore populated as a unit or left unpopulated as a unit, and never partly. A
 * nested record states that directly -- one reference is present or absent -- whereas eighteen
 * independently nullable components would admit every combination of presence across those eighteen,
 * almost all of them states the screen cannot reach, and would leave a reader no way to tell "no
 * customer record was found" from "the customer record was found and its fields happened to be
 * blank".</p>
 *
 * <p>Assumptions: the account guard is a disjunction, so the ten account fields are emitted on a turn
 * where only the customer record was located. That is the baseline's behaviour at L471 and L472, it is
 * recorded here as observed, and it is not altered by this contract.</p>
 *
 * <p>Alternatives Considered: declaring the two groupings as separate top-level files in this package.
 * Rejected because the package charter at
 * {@code services/account-service/src/main/java/com/carddemo/account/dto/package-info.java} scopes this
 * directory to the request and response shapes of its endpoint families, and a grouping that exists
 * only as part of one response is not a response of its own; nesting keeps it where its only meaning
 * is. The shared kernel resolves the same question the same way, holding its per-field error entry
 * inside {@code com.carddemo.common.error.ApiError} rather than beside it.</p>
 *
 * <p>Alternatives Considered: generating the accessors with an annotation processor, or generating the
 * copybook-to-record mapping with a mapping framework. Both were rejected for the same measurable
 * reason. Lombok is not used, because an accessor it generates has no source location at which the
 * Javadoc this tree requires could be written, and the gate at
 * {@code config/checkstyle/checkstyle.xml} L363 to L366 sets
 * {@code allowMissingPropertyJavadoc} to false with {@code allowedAnnotations} cleared to empty, so a
 * generated member would fail rather than be waived. MapStruct is not used, because the mapping this
 * contract is the far side of is not mechanical: it drops padding, masks two identifiers, truncates
 * three fields, renames one, and maps a third address line onto a city component. Every one of those
 * needs a justification at its own site, and a generated mapper has nowhere to hold one. Java records
 * with explicit documentation give the same brevity with members that can carry it, which is the shape
 * recorded at {@code docs/CODE_DOCUMENTATION_STANDARD.md} L436 to L448.</p>
 *
 * <h2>The account key transports as a digits-only string</h2>
 *
 * <p>Assumptions: {@code accountId} is a {@code String} of digits rather than an integral type, and the
 * two symbolic maps disagree about that in a way worth recording. The view map declares
 * {@code ACCTSIDI PIC 99999999999} at {@code app/cpy-bms/COACTVW.CPY} L60, which is numeric, while the
 * update map declares {@code ACCTSIDI PIC X(11)} at {@code app/cpy-bms/COACTUP.CPY} L60, which is
 * alphanumeric. This contract takes the alphanumeric form, so that one identifier has one wire shape
 * across the two screens that carry it instead of two shapes a client would have to branch on.</p>
 *
 * <p>Assumptions: the baseline itself treats these values as characters in transport and as numbers
 * only inside arithmetic, and it says so structurally. The before-image block
 * {@code 05 ACUP-OLD-DETAILS.} at {@code app/cbl/COACTUPC.cbl} L669, running to L756 where
 * {@code 05 ACUP-NEW-DETAILS.} opens at L757, declares each such value once as characters and once
 * again as a numeric redefinition of those same bytes: the account key as {@code PIC X(11)} at L671
 * redefined {@code PIC 9(11)} at L672 and L673, the balance as {@code PIC X(12)} at L675 redefined
 * {@code PIC S9(10)V99} at L676 and L677, the credit limit at L678 and the cash credit limit at L681
 * the same way, and the credit score as {@code PIC X(03)} at L754 redefined {@code PIC 9(03)} at L755
 * and L756. Which view the program reaches for tells the rest: the conflict comparison at L4117, L4119,
 * L4121, L4123 and L4125 compares the five amounts against the numeric redefinitions, never against
 * the character declarations. Every money field on both maps is alphanumeric as well --
 * {@code app/cpy-bms/COACTVW.CPY} L78, L90, L102, L108 and L120 are each {@code PIC X(15)} -- so
 * character transport with numeric interpretation is the baseline's own division, and this contract
 * keeps it.</p>
 *
 * <h2>The two message lines, at the program-side width</h2>
 *
 * <p>Assumptions: the widths that bind are the ones the program declares, not the ones the screen
 * reserves. {@code app/cbl/COACTVWC.cbl} declares {@code 05 WS-INFO-MSG PIC X(40)} at L110 and
 * {@code 05 WS-RETURN-MSG PIC X(75)} at L117, while the screen fields that display them are wider at
 * {@code INFOMSGI PIC X(45)} on {@code app/cpy-bms/COACTVW.CPY} L234 and {@code ERRMSGI PIC X(78)} at
 * L240. The program width is the contract because it bounds what can ever be produced; the screen
 * width only bounds what can be shown, and the surplus is presentation padding. Neither width belongs
 * to {@code app/cpy/CSMSG01Y.cpy}, whose L17 opens a two-message table of
 * {@code CCDA-MSG-THANK-YOU PIC X(50)} at L18 and L19 and {@code CCDA-MSG-INVALID-KEY PIC X(50)} at
 * L20 and L21; that fifty-character shape is a separate house convention and is not this contract.</p>
 *
 * <p>Assumptions: {@code returnMessage} carries one latched value and is not a rendering of a list.
 * The write at {@code app/cbl/COACTVWC.cbl} L671 to L673 is wrapped in {@code IF WS-RETURN-MSG-OFF} at
 * L670, and that condition is {@code VALUE SPACES} at L118, so the field is written only while still
 * unwritten: the first error to occur is the one reported and a later error does not replace it. A
 * consumer must therefore read this component as the single latched message and infer nothing from it
 * about how many things were wrong. Note that the two empty states are distinguishable in the
 * baseline and are not collapsed here: {@code WS-NO-INFO-MESSAGE} at L111 and L112 accepts spaces and
 * low values alike, so an absent message is represented as an absent value rather than as a blank
 * string, which would erase the distinction between a message that was never set and a message set to
 * blanks.</p>
 *
 * <p>Assumptions: message text is reproduced byte for byte and its whitespace is never normalised,
 * which matters because this program's text is not self-consistent. The literal actually moved into
 * {@code WS-RETURN-MSG} sits at {@code app/cbl/COACTVWC.cbl} L672 and reads
 * {@code Account Filter must  be a non-zero 11 digit number} -- two spaces between the third and
 * fourth words, a hyphen inside {@code non-zero}, and the word {@code Filter}. The condition names
 * declared for the same failure at L125 to L126 and at L127 to L128 read
 * {@code Account number must be a non zero 11 digit number} instead, with one space, no hyphen and
 * the word {@code number}. The emitted literal at L672 is what a user sees, so it is the one this
 * contract reproduces, and neither its spacing nor its hyphen may be tidied. The same discipline
 * applies to the trailing spaces held inside the quotes of the exit message at L119 and L120 and to
 * the informational values at L113 to L114 and L115 to L116.</p>
 *
 * <h2>No per-field error array, and no re-entry discriminator</h2>
 *
 * <p>Assumptions: this response carries the single latched message and no array of per-field errors,
 * because the view screen performs no per-field validation to populate one. Its whole input surface is
 * one key filter with three states, declared at {@code app/cbl/COACTVWC.cbl} L58 as
 * {@code WS-EDIT-ACCT-FLAG PIC X(1)} with the condition names at L59, L60 and L61 for not-valid, valid
 * and blank, read at L465 and again at L547 to L548, L557 and L561, with a companion customer flag at
 * L62. A structured per-field array belongs to the update contract, whose program declares a flag per
 * screen field. Should a structured error surface ever be needed on this path it is
 * {@code com.carddemo.common.error.ApiError} from the shared kernel, imported rather than restated
 * here.</p>
 *
 * <p>Refactoring Rationale: the baseline's error presentation is gated on a session flag, and that gate
 * does not survive into a stateless request. {@code app/cpy/CSSETATY.cpy} is the template being
 * replaced, and it is a procedure-division macro fragment rather than a data record: it tests a field's
 * validation flags at L18 and L19, moves the red colour attribute into that field's colour subfield at
 * L21 and L22, tests specifically for blank at L23, moves a literal asterisk into that field's output
 * subfield at L24 and L25, and closes at L26 and L27. The clause that does not survive is L20, which
 * conjoins the whole assignment with the pseudo-conversational flag declared at
 * {@code app/cpy/COCOM01Y.cpy} L29 as {@code CDEMO-PGM-CONTEXT PIC 9(01)} with its two condition names
 * at L30 and L31, so in the baseline a field is highlighted only on a turn that is itself a second
 * visit. What that approach cost is the reason to replace it: presentation state had to be remembered
 * across a turn boundary for the highlight to work at all, and remembering it is exactly the property
 * that prevents a service being scaled horizontally without a session store. Error presentation here is
 * driven purely by this response body, and no session discriminator, resubmission flag or turn counter
 * appears among these components or may be added to them. The related first-visit test at
 * {@code app/cbl/COACTVWC.cbl} L462 has no stateless analogue either: the prompt path is selected by the
 * absence of a path parameter, and the key echo at L465 to L469 becomes the presence or absence of
 * {@code accountId}.</p>
 *
 * @param accountId the account this response describes, transported as digits only,
 *     {@code ACCTSIDI} at {@code app/cpy-bms/COACTVW.CPY} L60 and {@code ACCT-ID PIC 9(11)} at
 *     {@code app/cpy/CVACT01Y.cpy} L5, which {@code app/cbl/CBACT01C.cbl} L32 confirms as the account
 *     file's record key; echoed to the screen at {@code app/cbl/COACTVWC.cbl} L466 and L468; absent on
 *     the prompt path, where the baseline instead sets its prompt state at L462 and L463
 * @param account the ten account fields, present when the account block at
 *     {@code app/cbl/COACTVWC.cbl} L471 and L472 ran and {@code null} when it did not; absent as a
 *     whole rather than field by field, because that block is guarded as a whole
 * @param customer the eighteen customer fields, present when the customer block at
 *     {@code app/cbl/COACTVWC.cbl} L493 ran and {@code null} when it did not; absent as a whole for the
 *     same reason
 * @param informationMessage the informational line, {@code 05 WS-INFO-MSG PIC X(40)} at
 *     {@code app/cbl/COACTVWC.cbl} L110, displayed through {@code INFOMSGI PIC X(45)} at
 *     {@code app/cpy-bms/COACTVW.CPY} L234; verbatim from its declaration, and {@code null} rather than
 *     blank when no message is set, per the two sentinels at L111 and L112
 * @param returnMessage the single latched return or error line,
 *     {@code 05 WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COACTVWC.cbl} L117, displayed through
 *     {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COACTVW.CPY} L240; first error wins per the
 *     {@code IF WS-RETURN-MSG-OFF} guard at L670, so this is one message and never a joined list, and
 *     it is {@code null} rather than blank when unset
 */
public record AccountViewResponse(
    String accountId,
    AccountDetail account,
    CustomerDetail customer,
    String informationMessage,
    String returnMessage) {

  /**
   * Renders this response for a log line, disclosing neither the account nor the customer.
   *
   * <p>Refactoring Rationale: a record's generated rendering prints every component, and two of the five
   * here are themselves records whose own generated renderings print everything they hold. This type
   * therefore emitted the account identifier, five money amounts, three dates, a name, a whole postal
   * address, two telephone numbers, a date of birth and a credit score in one string -- the most
   * disclosing single rendering in this bounded context, and one reached from any diagnostic that names
   * an instance. The sensitive-data logging contract in {@code docs/architecture/observability.md} names
   * account identifiers, customer identifiers and monetary amounts among its prohibited values and
   * requires that a prohibited value be OMITTED rather than abbreviated, so no component of either
   * nested record may appear and the identifier is not shortened.</p>
   *
   * <p>Trade-offs: what remains are the two message channels and the PRESENCE of each nested group,
   * stated as a boolean rather than as its content. Presence is what the caller-visible outcome turns
   * on -- the reference reaches a state at {@code app/cbl/COACTVWC.cbl} L471 and L472 where only the
   * customer record was located -- so a diagnostic saying which groups were populated is the one fact
   * about this response worth writing down, and it discloses nothing. The messages are carried because
   * both are migrated operator text from a fixed-width field, never a value read from a row.</p>
   *
   * <p>Assumptions: the nested records are NOT given renderings of their own that this method then
   * composes. A rendering on {@code AccountDetail} or {@code CustomerDetail} would have to withhold
   * every component it holds, leaving a constant string, and a constant string that varies with nothing
   * is a rendering a reader will eventually mistake for information. Reporting presence from here says
   * the same thing once, at the level where the two groups can be compared.</p>
   *
   * @return a rendering naming the type, both message channels and whether each nested group was
   *     populated, and no value from either group, never {@code null}
   */
  @Override
  public String toString() {
    return "AccountViewResponse[accountPresent=" + (this.account != null)
        + ", customerPresent=" + (this.customer != null)
        + ", informationMessage=" + this.informationMessage
        + ", returnMessage=" + this.returnMessage + ']';
  }

  /**
   * The ten account fields of the view screen, populated or absent as one unit rather than field by
   * field.
   *
   * <h2>Which ten, and why exactly ten</h2>
   *
   * <p>Assumptions: the ten are the account-region fields of the symbolic map, at
   * {@code app/cpy-bms/COACTVW.CPY} L66, L72, L78, L84, L90, L96, L102, L108, L114 and L120, and the
   * components below are declared in that same screen order so the two can be read side by side. Their
   * values come from the 300-byte account record {@code 01 ACCOUNT-RECORD.} at
   * {@code app/cpy/CVACT01Y.cpy} L4, whose non-padding fields occupy L5 through L16, and the moves that
   * connect them are the block at {@code app/cbl/COACTVWC.cbl} L473 through L490.</p>
   *
   * <p>Assumptions: every declared width in this grouping is taken from a {@code PICTURE} clause and
   * never chosen. A component sourced from a character field carries that field's declared width -- the
   * active status at {@code PIC X(01)} on {@code app/cpy/CVACT01Y.cpy} L6 is one character, the three
   * date fields at L10, L11 and L12 are ten each, and the group identifier at L16 is ten -- and a
   * component sourced from an amount carries the scale of its picture rather than a display width.</p>
   *
   * <h2>Five amounts, and why the type is Money</h2>
   *
   * <p>Assumptions: exactly five of these ten are amounts, and they are identifiable without judgement:
   * {@code app/cpy/CVACT01Y.cpy} declares {@code PIC S9(10)V99} at L7, L8, L9, L13 and L14, and nowhere
   * else in that record. All five are zoned decimal with a sign overpunch rather than packed decimal --
   * a search of that copybook for a {@code COMP} usage or an {@code OCCURS} clause returns no match --
   * so none is an array and none needs nibble decoding.</p>
   *
   * <p>Assumptions: exactly three of these ten are dates, at L10, L11 and L12. Two further fields are
   * also declared {@code PIC X(10)} and are not dates: the postal code at L15, which this contract does
   * not carry at all for the reason given below, and the group identifier at L16, which is a
   * disclosure-group code. Width alone does not make a date, so neither is treated as one.</p>
   *
   * <p>Alternatives Considered: typing the five amounts as a general-purpose arbitrary-precision
   * decimal, or emitting them as JSON numbers. Both were rejected, and the first fails silently, which
   * is why it is recorded here rather than assumed. The shared module binds its serialiser and its
   * deserialiser to the {@link Money} type by class, at
   * {@code services/common-lib/src/main/java/com/carddemo/common/money/MoneyModule.java} L271 and L272,
   * so a component typed as a general decimal is simply not matched by it and renders as a bare JSON
   * number: the code compiles, the service starts, the response is well formed, and the amount has
   * become inexact at precisely the boundary a balance or a credit limit is compared at. Emitting a
   * number deliberately has the same end: most clients parse a JSON number into an IEEE-754 binary
   * floating point value, and {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} L7
   * carries twelve significant digits, which such a representation cannot hold exactly. {@link Money}
   * therefore both fixes the in-memory representation and selects the string wire form in one
   * decision.</p>
   *
   * <p>Assumptions: neither screen's rendering is the value, which is the reason an amount is
   * transported rather than a formatted field. The view program moves all five amounts straight from
   * their {@code PIC S9(10)V99} record fields into {@code PIC X(15)} screen fields with no edit mask
   * whatsoever, at {@code app/cbl/COACTVWC.cbl} L475, L477, L479, L482 and L485. The update program
   * renders the same values through a mask, declaring
   * {@code WS-EDIT-CURRENCY-9-2 PIC X(15)} at {@code app/cbl/COACTUPC.cbl} L370 beside
   * {@code WS-EDIT-CURRENCY-9-2-F PIC +ZZZ,ZZZ,ZZZ.99} at L371, whose picture measures fifteen
   * characters and is exactly why the screen amount width is fifteen rather than the twelve bytes the
   * value occupies in storage. Two screens, two renderings, one value: this contract carries the value
   * at a scale of two and leaves rendering to the client.</p>
   *
   * <h2>Two documented absences and one rename</h2>
   *
   * <p>Assumptions: the record's {@code FILLER PIC X(178)} at {@code app/cpy/CVACT01Y.cpy} L17 has no
   * component here, because it is padding to a fixed record length rather than data. The arithmetic
   * closes that claim: the twelve declared fields at L5 through L16 measure 11 + 1 + 12 + 12 + 12 + 10
   * + 10 + 10 + 12 + 12 + 10 + 10 = 122 bytes, and 122 + 178 = 300, which is the RECLN 300 the
   * copybook's own header states at L2. The padding is therefore fully accounted for and carries no
   * value that could be lost by dropping it.</p>
   *
   * <p>Assumptions: {@code ACCT-ADDR-ZIP} at {@code app/cpy/CVACT01Y.cpy} L15 is deliberately not a
   * component, and four independent checks establish that it is unused rather than overlooked. It
   * appears zero times in {@code app/cbl/COACTVWC.cbl} and zero times in {@code app/cbl/COACTUPC.cbl}.
   * The update program's before-image account block at {@code app/cbl/COACTUPC.cbl} L671 through L708
   * snapshots eleven of the twelve non-padding account fields and this is the one it leaves out; the
   * only postal code anywhere in that block is {@code ACUP-OLD-CUST-ADDR-ZIP} at L721, which belongs to
   * the customer. And neither symbolic map declares an account postal-code field at all -- both
   * {@code ACSZIPCI} occurrences are the customer's. An account-level postal code consequently has no
   * reader in the baseline, so publishing one would invent a field rather than migrate it. Contrast
   * {@code ACCT-GROUP-ID} at L16, which is carried below precisely because it does have readers: the
   * view move at {@code app/cbl/COACTVWC.cbl} L490, and on the update side the snapshot at
   * {@code app/cbl/COACTUPC.cbl} L3847, the screen read at L1217, the write back at L4002, the change
   * test at L1698 to L1700 and the conflict comparison at L4139 to L4140.</p>
   *
   * <p>Refactoring Rationale: one component is renamed relative to its source field and only one. The
   * baseline declares {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy} L11, misspelling
   * "expiration", and this contract names it {@code expirationDate}; the baseline spelling survives in
   * the citation on that component so the lineage stays traceable, and the baseline file itself is
   * untouched. The baseline is already inconsistent about the same word, which is why carrying the
   * misspelling forward would not even buy consistency: {@code app/cbl/COACTUPC.cbl} L690 declares
   * {@code ACUP-OLD-EXPIRAION-DATE PIC X(08)} but names its own subdivisions
   * {@code ACUP-OLD-EXP-YEAR}, {@code ACUP-OLD-EXP-MON} and {@code ACUP-OLD-EXP-DAY} at L693, L694 and
   * L695, abbreviating rather than repeating the misspelling. Transformation rule T1 of the migration
   * plan keeps every other field name as declared, so no second rename is made here.</p>
   *
   * @param activeStatus the account's active-status indicator, {@code ACSTTUSI PIC X(1)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L66 from {@code ACCT-ACTIVE-STATUS PIC X(01)} at
   *     {@code app/cpy/CVACT01Y.cpy} L6, moved at {@code app/cbl/COACTVWC.cbl} L473
   * @param openDate the date the account was opened, {@code ADTOPENI PIC X(10)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L72 from {@code ACCT-OPEN-DATE PIC X(10)} at
   *     {@code app/cpy/CVACT01Y.cpy} L10, moved at {@code app/cbl/COACTVWC.cbl} L487; carried as the
   *     ten-character text the record holds, which is already year-month-day ordered
   * @param creditLimit the account's credit limit, {@code ACRDLIMI PIC X(15)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L78 from {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at
   *     {@code app/cpy/CVACT01Y.cpy} L8, moved unmasked at {@code app/cbl/COACTVWC.cbl} L477
   * @param expirationDate the date the account expires, {@code AEXPDTI PIC X(10)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L84 from the field the baseline declares as
   *     {@code ACCT-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy} L11, moved at
   *     {@code app/cbl/COACTVWC.cbl} L488; this component's name is the one rename described above
   * @param cashCreditLimit the account's cash credit limit, {@code ACSHLIMI PIC X(15)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L90 from {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} at
   *     {@code app/cpy/CVACT01Y.cpy} L9, moved unmasked at {@code app/cbl/COACTVWC.cbl} L479 and L480
   * @param reissueDate the date the account was last reissued, {@code AREISDTI PIC X(10)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L96 from {@code ACCT-REISSUE-DATE PIC X(10)} at
   *     {@code app/cpy/CVACT01Y.cpy} L12, moved at {@code app/cbl/COACTVWC.cbl} L489
   * @param currentBalance the account's posted balance, {@code ACURBALI PIC X(15)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L102 from {@code ACCT-CURR-BAL PIC S9(10)V99} at
   *     {@code app/cpy/CVACT01Y.cpy} L7, moved unmasked at {@code app/cbl/COACTVWC.cbl} L475; the
   *     twelve-significant-digit field the string wire form exists for
   * @param currentCycleCredit the credit posted in the current cycle, {@code ACRCYCRI PIC X(15)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L108 from {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at
   *     {@code app/cpy/CVACT01Y.cpy} L13, moved unmasked at {@code app/cbl/COACTVWC.cbl} L482 and L483
   * @param groupId the account's disclosure-group code, {@code AADDGRPI PIC X(10)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L114 from {@code ACCT-GROUP-ID PIC X(10)} at
   *     {@code app/cpy/CVACT01Y.cpy} L16, moved at {@code app/cbl/COACTVWC.cbl} L490; a code and not a
   *     date, despite sharing the ten-character width of the three date fields
   * @param currentCycleDebit the debit posted in the current cycle, {@code ACRCYDBI PIC X(15)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L120 from {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at
   *     {@code app/cpy/CVACT01Y.cpy} L14, moved unmasked at {@code app/cbl/COACTVWC.cbl} L485
   */
  public record AccountDetail(
      String activeStatus,
      String openDate,
      Money creditLimit,
      String expirationDate,
      Money cashCreditLimit,
      String reissueDate,
      Money currentBalance,
      Money currentCycleCredit,
      String groupId,
      Money currentCycleDebit) {
    /**
     * Renders the account detail WITHOUT its money values.
     *
     * <p>Assumptions: all five amounts are withheld together rather than individually, because the
     * balance, the two limits and the two cycle totals are equally an identified account's financial
     * position and there is no reading on which one of them is safe to print and another is not. The
     * three dates and the two codes are kept: a date and a status are what an expiry or an activation
     * question is debugged with, and neither states a sum.</p>
     *
     * @return a rendering carrying the dates, the status and the group, with the amounts withheld, never
     *     {@code null}
     */
    @Override
    public String toString() {
      return "AccountDetail[activeStatus=" + this.activeStatus
          + ", openDate=" + this.openDate
          + ", expirationDate=" + this.expirationDate
          + ", reissueDate=" + this.reissueDate
          + ", groupId=" + this.groupId
          + ", amounts=" + WITHHELD
          + "]";
    }
  }

  /**
   * The eighteen customer fields of the view screen, carried at the widths that screen declares rather
   * than the widths the stored record declares.
   *
   * <h2>Which eighteen, and why screen widths rather than record widths</h2>
   *
   * <p>Assumptions: the eighteen are the customer-region fields of the symbolic map, at
   * {@code app/cpy-bms/COACTVW.CPY} L126, L132, L138, L144, L150, L156, L162, L168, L174, L180, L186,
   * L192, L198, L204, L210, L216, L222 and L228, and the components below are declared in that same
   * screen order. Their values come from the 500-byte customer record
   * {@code 01 CUSTOMER-RECORD.} at {@code app/cpy/CVCUS01Y.cpy} L4, whose non-padding fields occupy L5
   * through L22, and the moves that connect them are the block at {@code app/cbl/COACTVWC.cbl} L494
   * through L522.</p>
   *
   * <p>Alternatives Considered: composing this response from the customer-shaped response record the
   * customer read paths of this same context publish, so that one customer shape served both. It was
   * rejected because the two are derived from different sources and disagree measurably.
   * {@code CustomerResponse} and its cross-reference counterpart {@code CardXrefResponse} are derived at
   * record widths; this grouping is derived at screen widths, because it is the view screen's contract.
   * Four fields differ, each verified against the moves in {@code app/cbl/COACTVWC.cbl}: the national
   * identifier, which the screen composes rather than copies; both telephone numbers, which the screen
   * truncates by two characters each; and the postal code, which the screen truncates by five. Reusing
   * a record-width shape would silently publish a wider value than this screen has ever shown on four
   * components at once, and nothing in a build log would report it.</p>
   *
   * <p>Assumptions: every declared width in this grouping is taken from a {@code PICTURE} clause. Where
   * the screen and the record agree, as they do for the three name fields at {@code PIC X(25)} on
   * {@code app/cpy/CVCUS01Y.cpy} L6, L7 and L8 and for the two long address lines at {@code PIC X(50)}
   * on L9 and L10, the shared width is used. Where they disagree, the four cases named above, the
   * screen width is used and the divergence is stated on the component itself.</p>
   *
   * <h2>The composed national identifier, and the baseline's own rejected alternative</h2>
   *
   * <p>Alternatives Considered: the baseline author evaluated a direct copy for the national identifier
   * and rejected it, and the rejected line is still in the source. {@code app/cbl/COACTVWC.cbl} L495 is
   * a commented-out direct move of {@code CUST-SSN} to the screen field, replaced by the composition at
   * L496 through L504, which builds the value from three substrings joined by two hyphens and stores it
   * with {@code DELIMITED BY SIZE}. That is why the screen field is twelve characters at L132 while the
   * stored field is nine digits at {@code app/cpy/CVCUS01Y.cpy} L17: nine digits plus two hyphens
   * occupy eleven of the twelve. The commented-out line is cited rather than ignored because keeping a
   * rejected alternative visible beside the chosen one is the practice this file follows in its own
   * rationale paragraphs, and the baseline established it here first.</p>
   *
   * <p>Assumptions: the two telephone numbers truncate by exactly the padding their stored form carries,
   * which is what makes the truncation lossless in the baseline. The stored fields are
   * {@code PIC X(15)} at {@code app/cpy/CVCUS01Y.cpy} L15 and L16; the update program's before-image
   * subdivides that same fifteen-character shape into a leading marker, a three-digit area code, a
   * separator, a three-digit prefix, a separator, a four-digit line number and a trailing
   * {@code FILLER PIC X(2)} at {@code app/cbl/COACTUPC.cbl} L731 and L741. Those parts measure
   * 1 + 3 + 1 + 3 + 1 + 4 = 13, and 13 + 2 = 15, so the thirteen characters the screen fields at L204
   * and L216 accept are exactly the populated ones and the two characters the direct moves at
   * {@code app/cbl/COACTVWC.cbl} L517 and L518 drop are exactly the pad.</p>
   *
   * <p>Assumptions: the postal code is the one genuine narrowing, and it is recorded as observed rather
   * than altered. The stored field is {@code CUST-ADDR-ZIP PIC X(10)} at
   * {@code app/cpy/CVCUS01Y.cpy} L14 and the screen field is {@code ACSZIPCI PIC X(5)} at
   * {@code app/cpy-bms/COACTVW.CPY} L186, and the move at {@code app/cbl/COACTVWC.cbl} L515 is direct,
   * so the baseline shows five of the ten characters on this screen. This contract carries what the
   * screen carries and the divergence between the two widths is stated on the component; widening it
   * here would publish through this endpoint a value the screen it mirrors has never shown.</p>
   *
   * <p>Assumptions: the city component is not a one-to-one rename of a like-named field, because no
   * like-named field exists. The move at {@code app/cbl/COACTVWC.cbl} L513 takes
   * {@code CUST-ADDR-LINE-3} into the city screen field, and a search for a customer city field returns
   * no match anywhere in {@code app/cpy/} nor in {@code app/cbl/CBCUS01C.cbl}. The third address line is
   * therefore the city by use rather than by name, the two widths agree exactly at {@code PIC X(50)},
   * and this component is named for the role the screen assigns it so a reader is not sent looking for
   * a field that is not there.</p>
   *
   * <h2>Padding, and the two identifiers this contract masks</h2>
   *
   * <p>Assumptions: the record's {@code FILLER PIC X(168)} at {@code app/cpy/CVCUS01Y.cpy} L23 has no
   * component here, for the same reason as its counterpart in the account grouping: it is padding to a
   * fixed record length. The arithmetic closes it -- the eighteen declared fields at L5 through L22
   * measure 332 bytes, and 332 + 168 = 500, the RECLN 500 the copybook's own header states at L2.</p>
   *
   * <p>Trade-offs: the national identifier and the government-issued identifier are stored encrypted
   * and are returned masked, and this is an addition rather than a migration -- the baseline does
   * neither. It renders the national identifier in full, composing all nine digits into the screen field
   * at {@code app/cbl/COACTVWC.cbl} L496 through L504, and passes the government-issued identifier
   * through unmasked at L519. Masking costs the caller the ability to reconcile either value against the
   * source system from this response alone, and that cost is accepted: a response body is logged, cached
   * and forwarded by intermediaries that the field's sensitivity does not travel with, and a caller that
   * genuinely needs the full value needs an audited path to it rather than every reader of every
   * response having one. The two component names say masked so that no call site can consume either one
   * believing it holds the whole value. Consistently with the same reasoning, no card verification value
   * appears anywhere in this contract; neither {@code app/cpy/CVACT01Y.cpy} nor
   * {@code app/cpy/CVCUS01Y.cpy} declares one, and no endpoint of this migration returns one.</p>
   *
   * @param customerId the customer this account belongs to, transported as digits only,
   *     {@code ACSTNUMI PIC X(9)} at {@code app/cpy-bms/COACTVW.CPY} L126 from
   *     {@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy} L5, moved at
   *     {@code app/cbl/COACTVWC.cbl} L494
   * @param ssnMasked the customer's national identifier, masked and never whole,
   *     {@code ACSTSSNI PIC X(12)} at {@code app/cpy-bms/COACTVW.CPY} L132 from
   *     {@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy} L17, which the baseline composes
   *     whole with two hyphens at {@code app/cbl/COACTVWC.cbl} L496 through L504; the masking is this
   *     migration's addition and the component is named for it
   * @param dateOfBirth the customer's date of birth, {@code ACSTDOBI PIC X(10)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L138 from {@code CUST-DOB-YYYY-MM-DD PIC X(10)} at
   *     {@code app/cpy/CVCUS01Y.cpy} L19, moved at {@code app/cbl/COACTVWC.cbl} L507; carried whole
   *     rather than split into parts, as the record and the screen both hold it
   * @param ficoCreditScore the customer's credit score, {@code ACSTFCOI PIC X(3)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L144 from {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at
   *     {@code app/cpy/CVCUS01Y.cpy} L22, moved at {@code app/cbl/COACTVWC.cbl} L505 and L506; a
   *     bounded three-digit integer transported as digits, and not an amount
   * @param firstName the customer's first name, {@code ACSFNAMI PIC X(25)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L150 from {@code CUST-FIRST-NAME PIC X(25)} at
   *     {@code app/cpy/CVCUS01Y.cpy} L6, moved at {@code app/cbl/COACTVWC.cbl} L508
   * @param middleName the customer's middle name, {@code ACSMNAMI PIC X(25)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L156 from {@code CUST-MIDDLE-NAME PIC X(25)} at
   *     {@code app/cpy/CVCUS01Y.cpy} L7, moved at {@code app/cbl/COACTVWC.cbl} L509
   * @param lastName the customer's last name, {@code ACSLNAMI PIC X(25)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L162 from {@code CUST-LAST-NAME PIC X(25)} at
   *     {@code app/cpy/CVCUS01Y.cpy} L8, moved at {@code app/cbl/COACTVWC.cbl} L510
   * @param addressLine1 the first line of the customer's address, {@code ACSADL1I PIC X(50)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L168 from {@code CUST-ADDR-LINE-1 PIC X(50)} at
   *     {@code app/cpy/CVCUS01Y.cpy} L9, moved at {@code app/cbl/COACTVWC.cbl} L511
   * @param stateCode the customer's state code, {@code ACSSTTEI PIC X(2)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L174 from {@code CUST-ADDR-STATE-CD PIC X(02)} at
   *     {@code app/cpy/CVCUS01Y.cpy} L12, moved at {@code app/cbl/COACTVWC.cbl} L514
   * @param addressLine2 the second line of the customer's address, {@code ACSADL2I PIC X(50)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L180 from {@code CUST-ADDR-LINE-2 PIC X(50)} at
   *     {@code app/cpy/CVCUS01Y.cpy} L10, moved at {@code app/cbl/COACTVWC.cbl} L512
   * @param zipCode the customer's postal code as this screen shows it, {@code ACSZIPCI PIC X(5)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L186 from the wider {@code CUST-ADDR-ZIP PIC X(10)} at
   *     {@code app/cpy/CVCUS01Y.cpy} L14, narrowed by the direct move at
   *     {@code app/cbl/COACTVWC.cbl} L515; five of the ten stored characters, as described above
   * @param city the customer's city, {@code ACSCITYI PIC X(50)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L192 from {@code CUST-ADDR-LINE-3 PIC X(50)} at
   *     {@code app/cpy/CVCUS01Y.cpy} L11, moved at {@code app/cbl/COACTVWC.cbl} L513; sourced from the
   *     third address line because the customer record declares no city field
   * @param countryCode the customer's country code, {@code ACSCTRYI PIC X(3)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L198 from {@code CUST-ADDR-COUNTRY-CD PIC X(03)} at
   *     {@code app/cpy/CVCUS01Y.cpy} L13, moved at {@code app/cbl/COACTVWC.cbl} L516
   * @param phoneNumber1 the customer's first telephone number, {@code ACSPHN1I PIC X(13)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L204 from the wider {@code CUST-PHONE-NUM-1 PIC X(15)} at
   *     {@code app/cpy/CVCUS01Y.cpy} L15, narrowed by the direct move at
   *     {@code app/cbl/COACTVWC.cbl} L517 to exactly the thirteen populated characters
   * @param governmentIssuedIdMasked the customer's government-issued identifier, masked and never
   *     whole, {@code ACSGOVTI PIC X(20)} at {@code app/cpy-bms/COACTVW.CPY} L210 from
   *     {@code CUST-GOVT-ISSUED-ID PIC X(20)} at {@code app/cpy/CVCUS01Y.cpy} L18, which the baseline
   *     passes through unmasked at {@code app/cbl/COACTVWC.cbl} L519; the masking is this migration's
   *     addition and the component is named for it
   * @param phoneNumber2 the customer's second telephone number, {@code ACSPHN2I PIC X(13)} at
   *     {@code app/cpy-bms/COACTVW.CPY} L216 from the wider {@code CUST-PHONE-NUM-2 PIC X(15)} at
   *     {@code app/cpy/CVCUS01Y.cpy} L16, narrowed by the direct move at
   *     {@code app/cbl/COACTVWC.cbl} L518 to exactly the thirteen populated characters
   * @param eftAccountId the customer's electronic-transfer account identifier,
   *     {@code ACSEFTCI PIC X(10)} at {@code app/cpy-bms/COACTVW.CPY} L222 from
   *     {@code CUST-EFT-ACCOUNT-ID PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy} L20, moved at
   *     {@code app/cbl/COACTVWC.cbl} L520
   * @param primaryCardHolderIndicator whether the customer is the primary card holder,
   *     {@code ACSPFLGI PIC X(1)} at {@code app/cpy-bms/COACTVW.CPY} L228 from
   *     {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} at {@code app/cpy/CVCUS01Y.cpy} L21, moved at
   *     {@code app/cbl/COACTVWC.cbl} L521 and L522; the single character the record declares, not a
   *     boolean, so an unset value stays distinguishable from a negative one
   */
  public record CustomerDetail(
      String customerId,
      String ssnMasked,
      String dateOfBirth,
      String ficoCreditScore,
      String firstName,
      String middleName,
      String lastName,
      String addressLine1,
      String stateCode,
      String addressLine2,
      String zipCode,
      String city,
      String countryCode,
      String phoneNumber1,
      String governmentIssuedIdMasked,
      String phoneNumber2,
      String eftAccountId,
      String primaryCardHolderIndicator) {
    /**
     * Renders the customer detail WITHOUT the personal data it carries.
     *
     * <p>Assumptions: only the customer identifier is kept, for the reason recorded on the enclosing
     * type's rendering. Seventeen of this record's eighteen components are personal data, including the
     * two already-masked identifiers -- a mask on two components is no help to the other fifteen.</p>
     *
     * <p>Trade-offs: presence is reported for the three optional components so an absent middle name,
     * second address line or second telephone number is still debuggable.</p>
     *
     * @return a rendering carrying the identifier, three presence flags and the withheld marker, never
     *     {@code null}
     */
    @Override
    public String toString() {
      // WHY : Refactoring Rationale: the customer identifier is OMITTED here and used to be rendered
      //       verbatim. docs/architecture/observability.md L1075 to L1081 states the rule this now
      //       obeys -- a prohibited value is omitted rather than abbreviated, and it names the customer
      //       identifier among the prohibited ones -- and the sibling projection of the same data,
      //       CustomerResponse, already withheld it. One value rendered two ways makes neither
      //       authoritative, and the disclosing one was the projection an update response carries, so
      //       it reached a log on the ordinary success path rather than an exceptional one.
      // WHY : Alternatives Considered: reporting the identifier's presence, the way the three optional
      //       components below are reported. Rejected because presence carries no information here: the
      //       component is required and is therefore always present, so the flag would be a constant
      //       dressed as an observation. The primary-cardholder indicator is rendered in its place for
      //       the reason the rule's third clause gives -- what remains is identity that discloses
      //       nothing, and a one-character status flag is exactly that -- and it is the same component
      //       CustomerResponse renders, so the two projections now agree.
      return "CustomerDetail[primaryCardHolderIndicator=" + this.primaryCardHolderIndicator
          + ", middleNamePresent=" + (this.middleName != null)
          + ", addressLine2Present=" + (this.addressLine2 != null)
          + ", phoneNumber2Present=" + (this.phoneNumber2 != null)
          + ", personalData=" + WITHHELD
          + "]";
    }
  }
  /**
   * The marker published in place of every withheld component.
   *
   * <p>Assumptions: the same literal the {@code Customer} entity, {@code CustomerResponse} and
   * {@code com.carddemo.account.mapper.CustomerMapper} publish, so a withholding looks identical
   * wherever a reader meets one.</p>
   */
  private static final String WITHHELD = "[REDACTED]";
}
