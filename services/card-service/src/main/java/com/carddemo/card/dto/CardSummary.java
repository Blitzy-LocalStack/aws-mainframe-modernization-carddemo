package com.carddemo.card.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One row of the keyset-paged card list, carrying the three values the baseline list screen
 * displayed for a card.
 *
 * <p>The shape comes from the row that screen painted. The program holds the screen body as a
 * twenty-eight-character group repeated seven times, declared at
 * {@code app/cbl/COCRDLIC.cbl:252-260}, and its three data fields are
 * {@code WS-ROW-ACCTNO PIC X(11)} at line 258, {@code WS-ROW-CARD-NUM PIC X(16)} at line 259 and
 * {@code WS-ROW-CARD-STATUS PIC X(1)} at line 260 -- eleven plus sixteen plus one, being the
 * twenty-eight characters the program's own comment states at line 250. The symbolic map agrees
 * field for field, exposing {@code ACCTNO1I PIC X(11)}, {@code CRDNUM1I PIC X(16)} and
 * {@code CRDSTS1I PIC X(1)} for the first row at lines 84, 90 and 96 of
 * {@code app/cpy-bms/COCRDLI.CPY}. Three data fields per row, and three components here.</p>
 *
 * <p>This record is an inert carrier: it holds the values it was constructed with and transforms
 * none of them. The masking of the card number is performed in {@code com.carddemo.card.mapper} and
 * nowhere else, which the charter of this package establishes as the single place that decision is
 * taken.</p>
 *
 * <p>The envelope around one page of these rows is not declared here.
 * {@code com.carddemo.common.web.PageResponse} carries it, with exactly four components --
 * {@code items}, {@code firstKey}, {@code lastKey} and {@code hasNext} -- declared once at
 * {@code services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java:231-235}, and
 * its serialised form is the {@code CardPage} schema of the contract.</p>
 *
 * <p>{@code services/card-service/src/main/resources/openapi/card-api.yaml} is the contract of
 * record for this shape, at its {@code CardSummary} schema on line 1373. The component names, the
 * order they are declared in and every bound each one carries are that schema's, taken from its
 * property declarations on lines 1416, 1429 and 1441 and from its closed property set on lines 1392
 * to 1396.</p>
 *
 * <h2>Why this shape is what it is</h2>
 *
 * <p>Assumptions: the row carries exactly three values, and the baseline settles that count rather
 * than judgement settling it. The twenty-eight-character group at
 * {@code app/cbl/COCRDLIC.cbl:258-260} decomposes as eleven plus sixteen plus one and leaves
 * nothing over, the program's own comment at line 250 states the same arithmetic across its seven
 * rows, and the symbolic map exposes three data fields per row and no fourth. The embossed name and
 * the expiry date are absent because the list screen displayed neither; both belong to the detail
 * shapes of this package, which are read one card at a time.</p>
 *
 * <p>Assumptions: no component here carries money and none carries a timestamp, and that absence is
 * recorded rather than left silent, because silence in a shape derived from a financial record reads
 * as an oversight. The card layout declares exactly seven items and no more --
 * {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:5}, {@code CARD-ACCT-ID PIC 9(11)} at
 * line 6, the three-digit verification value at line 7, {@code CARD-EMBOSSED-NAME PIC X(50)} at
 * line 8, {@code CARD-EXPIRAION-DATE PIC X(10)} at line 9,
 * {@code CARD-ACTIVE-STATUS PIC X(01)} at line 10 and {@code FILLER PIC X(59)} at line 11 -- and
 * there is no amount and no timestamp among them. So the shared money type is imported by nothing
 * here, and the twenty-six-character timestamp a refusal carries belongs to the shared problem
 * shape rather than to any card row. The trailing padding is dropped rather than carried, because it
 * pads the record to its declared width and is not data.</p>
 *
 * <p>Assumptions: no representation of the card verification value appears on this record -- not in
 * full, not masked, not truncated, and not implied by a width. This preserves an absence the
 * baseline already had rather than withdrawing something it showed: a case-insensitive search for
 * that field's three-letter short name returns nothing in any of the six presentation files of this
 * context, being {@code app/cpy-bms/COCRDSL.CPY}, {@code app/cpy-bms/COCRDLI.CPY},
 * {@code app/cpy-bms/COCRDUP.CPY}, {@code app/bms/COCRDSL.bms}, {@code app/bms/COCRDLI.bms} and
 * {@code app/bms/COCRDUP.bms}, so no screen in the set displayed it or accepted it as input. The
 * value does exist in the stored record, as the third item of the layout cited above, and what the
 * migrated platform adds for it is encryption at rest -- a platform-capability difference rather
 * than a change to the wire, since the file resources the baseline defines carry neither recovery
 * nor journalling. A masked member was considered and is not the safer half-measure it can look
 * like: it would still publish that the value exists, how wide it is and which member to attack, so
 * complete absence is the only rendering that discloses nothing. That field's identifier is cited here
 * by path, line and position rather than written out, so that a screen scanning this tree for it
 * cannot mistake a citation for a disclosure.</p>
 *
 * <p>Assumptions: the row-selection and separator fields the list screen carried beside each row are
 * terminal affordances rather than data, and neither has a counterpart here. They existed so an
 * operator could mark a row and transmit the whole screen at once, and a client instead calls the
 * detail or update operation for the card it wants. Their own layout shows they are not part of the
 * record: {@code app/cpy-bms/COCRDLI.CPY} declares a separator input for rows two through seven
 * only, at lines 108, 138, 168, 198, 228 and 258, and a search for a first-row separator returns
 * nothing on either the input or the output side of that map -- so the seven rows are not even
 * uniform in them, and inventing the missing one would invent a field the baseline never had. The
 * first row carries its selection field alone, at lines 73, 74, 76 and 78. The twenty-eighth
 * character of the row is the active status and not a selection marker.</p>
 *
 * <p>Alternatives Considered: counted-position paging, in which a request states how far into the
 * ordered set to begin. Rejected, because a boundary defined by a count moves when the set changes
 * beneath it: one row inserted between two reads pushes a row across the boundary, so a caller
 * walking the list never sees that row and sees another twice. Concurrent insertion is not
 * hypothetical in this application -- the baseline's own bill-payment path takes the next
 * transaction identifier with an unlocked read-then-increment, moving high values into the key at
 * {@code app/cbl/COBIL00C.cbl:212}, browsing at line 213, reading backward at line 214, ending the
 * browse at line 215 and then adding one to the value it read at lines 216 and 217, so two callers
 * can be inside that sequence at once. The second half of the argument is that the baseline never
 * used a counted position as a control either: its symbolic map declares the input half of the
 * screen counter, {@code PAGENOI PIC X(3)}, at {@code app/cpy-bms/COCRDLI.CPY:60}, and
 * {@code app/cbl/COCRDLIC.cbl} never reads it, while the counter that program declares at line 237
 * reaches the map at exactly one site, line 667, where it writes the output half alone. No component
 * here counts pages, counts the rows in the ordered set, or positions a row by anything other than a
 * key.</p>
 *
 * <p>Refactoring Rationale: the cursor keys and the more-pages flag are consumed from the shared
 * kernel rather than restated beside this row. Transformation rule T2 of the migration plan requires
 * exactly that -- a shared concern is imported from {@code com.carddemo.common} alone -- and the
 * baseline is the precedent for the discipline rather than an exception to it: every program
 * resolves its record layouts through one compiler include path, a convention the repository states
 * at {@code tests/README.md:540-542}, where it asks that a layout never be duplicated but be kept
 * single-sourced from {@code app/cpy}. An envelope declared here would be that duplication, and
 * because eight bounded contexts page the same way, the copy that drifted would produce paging that
 * differs between screens without a single build failing to say so. What the baseline held between
 * screen turns -- the first and last key of the displayed rows, at
 * {@code app/cbl/COCRDLIC.cbl:230-235} -- is therefore carried by {@code firstKey} and
 * {@code lastKey} on the envelope, while the screen-number and last-page-displayed indicators at
 * lines 237 to 241 are dropped, the second of them having a polarity no client needs to learn.</p>
 *
 * <p>Assumptions: an identifier travels as a digits-only string rather than as a numeric component,
 * and the baseline settles that rather than taste settling it. It keeps both representations of the
 * same storage and moves between them deliberately: {@code app/cpy/CVCRD01Y.cpy} declares
 * {@code CC-ACCT-ID PIC X(11) VALUE SPACES} at lines 34 to 35 with
 * {@code CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC 9(11)} at line 36, and declares
 * {@code CC-CARD-NUM PIC X(16)} at lines 37 to 38 with {@code CC-CARD-NUM-N} over it as
 * {@code PIC 9(16)} at line 39. The decisive evidence is the name the baseline gave the group
 * holding the character forms: {@code app/cbl/COCRDUPC.cbl:103} opens
 * {@code 05 CICS-OUTPUT-EDIT-VARS.} and carries {@code CARD-ACCT-ID-X PIC X(11)} at line 104
 * redefined as {@code PIC 9(11)} at lines 105 to 106, and {@code CARD-CARD-NUM-X PIC X(16)} at line
 * 110 redefined as {@code PIC 9(16)} at lines 111 to 112, so the character form is the baseline's
 * own presentation form and the numeric view is a lens onto it. That program reaches for the numeric
 * view only where a number is required, moving {@code CC-ACCT-ID-N} into the record it writes at
 * line 1463; and {@code app/cbl/COCRDLIC.cbl} tests the numeric views for zero at lines 1009 and
 * 1044 while the two lines above each test the character views for low values and for spaces. The
 * wire form here is the presentation form the baseline itself used.</p>
 *
 * <p>Assumptions: the sixteen-digit card number carries a second reason of its own, independent of
 * the one above. The exact-integer ceiling of IEEE-754 binary floating point is two to the
 * fifty-third power, that is 9,007,199,254,740,992, which is itself a sixteen-digit number. The
 * sixteen-digit domain therefore extends beyond the exact-integer range, so exactness is not
 * guaranteed across it. The claim is deliberately no stronger than that: the smallest sixteen-digit
 * value, 1,000,000,000,000,000, sits below that ceiling and is held exactly, so the hazard belongs
 * to the upper part of the domain and not to all of it. A value transported as text is not subject
 * to it anywhere in the domain, because a client that never parses the value as a number cannot
 * re-round it.</p>
 *
 * <p>Assumptions: the eleven-digit account identifier is a string for different reasons, and naming
 * the wrong one would be a false claim rather than a harmless simplification. Its largest value,
 * 99,999,999,999, is far below the ceiling named above and is held exactly, so exactness is not at
 * stake for it at all. Its grounds are three. First, the baseline's own character wire form, cited
 * two paragraphs above. Second, leading zeros, which an eleven-character fixed-width field carries
 * as data and which a numeric component drops -- the contract's own example for the member is
 * {@code 00000000011} at {@code card-api.yaml:1440}, which a numeric rendering could not reproduce.
 * Third, one uniform digits-only constraint discipline across both identifiers, so that a reviewer
 * has one rule to check rather than two.</p>
 *
 * <p>Trade-offs: {@code displayCardNumber} carries a maximum width and no digit pattern, where the
 * alternative was the sixteen-digit pattern the request side uses. A digit pattern here would refuse
 * the very value this component exists to carry: the mapper renders the number masked, and the
 * contract's own example for the member is {@code ************0011} at
 * {@code card-api.yaml:1428}, which is not sixteen digits. The bound declared below is therefore the
 * bound the contract declares, {@code maxLength: 16} on line 1418, with no pattern and no minimum
 * beside it. The cost is that the sixteen-digit discipline rests entirely elsewhere -- on the
 * request side, at the path parameter on line 685 and the list filter on line 797, and on the
 * administrative detail schema at line 1763, each declaring {@code ^[0-9]{16}$} -- and on the mapper
 * that produces the rendering. Nothing in this record would catch a mapper that emitted an unmasked
 * number, which is why the disclosure boundary of this context is asserted by the contract test
 * rather than by a constraint here.</p>
 *
 * <p>Assumptions: {@code activeStatus} is one character drawn from an uppercase pair, and the
 * baseline's own condition name settles the set: {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'} at
 * {@code app/cbl/COCRDUPC.cbl:91}, applied by moving the submitted status into the checked field at
 * line 861 and testing the condition at line 863, with that field initialised to {@code 'N'} at
 * lines 89 to 90. The set contains no lowercase member, so no lowercase value is accepted here and
 * none is folded to uppercase either -- folding would accept an input the baseline refused, which is
 * a behavioural change rather than a convenience. The contract states the same domain as an
 * enumeration of two values at {@code card-api.yaml:1443-1445}.</p>
 *
 * <p>Alternatives Considered: masking the number inside this record, either by normalising it in a
 * constructor or by rendering it behind an accessor. Rejected, because the decision would then be
 * spread across every record that carries the number, and auditing it would mean reading all of them
 * and satisfying oneself that no path had been missed. Concentrating it in
 * {@code com.carddemo.card.mapper} leaves one class to inspect, and that matters here more than it
 * would elsewhere, because the two detail shapes of this context are cut from one shared core: the
 * same members reach a caller holding only the user authority and a caller holding the
 * administrative one, and only the second may additionally receive the number in full. Because this
 * record transforms nothing, an unmasked value can reach a response only by passing through the
 * mapper, which is what keeps the mapper the single auditable place that disclosure is decided.</p>
 *
 * <p>Alternatives Considered: a class with generated accessors, by way of an annotation processor.
 * Rejected because a generated accessor offers no line able to carry the documentation Rule 1
 * requires at its line 15, so the components whose provenance most needs stating -- a width taken
 * from a copybook, a domain of two values -- would be exactly the components with nowhere to state
 * it. A Java 21 record gives the same brevity with members that can be documented, so no code
 * generator participates in this shape.</p>
 *
 * <p>Assumptions: where this record and the contract could be read differently about the shape, the
 * contract governs and this file is what moves. Both derive from the same card layout independently,
 * so a divergence between them raises no compile failure -- it surfaces only as a client generated
 * from the published document failing against a running service -- and declaring one side
 * authoritative in advance is what removes the ambiguity, because there is no compile-time signal to
 * fall back on. That is not hypothetical here. The masked member is named
 * {@code displayCardNumber} because the contract names it so on line 1416, and because two
 * consumers already written against the contract read it under that name: the browser client at
 * {@code ui/src/api/cards.ts:23-27} and the contract test at
 * {@code services/card-service/src/test/java/com/carddemo/card/config/CardApiContractTest.java:470-473},
 * which asserts that schema's property set exactly. A shorter name here would have compiled, and
 * would have serialised a member no consumer reads.</p>
 *
 * <p>Trade-offs: the components are declared in the order the contract declares its properties, on
 * lines 1416, 1429 and 1441, and that order is load-bearing in a way a reader should not have to
 * discover. All three are strings, so a construction supplying them in another order would compile
 * and would serialise silently, putting an account number where a rendering belongs. Aligning the
 * declaration with the contract, and with the browser client's interface that already follows it,
 * leaves one order to follow rather than two; what is given up is that the order cannot be checked
 * by the compiler, so it is stated here where whatever constructs a row will read it.</p>
 *
 * <p>Assumptions: no constraint below declares message text. The charter of this package puts
 * user-visible strings with the error model and with the user interface, a refusal is answered by
 * the per-field array of {@code com.carddemo.common.error.ApiError} whose entry names the offending
 * member and its validation state, and the baseline holds no literal for a rejected response row --
 * it validated submissions, not the rows it painted. Writing a message at the constraint would put a
 * user-visible string in a package that does not own one, and would have to invent wording no
 * baseline literal supports.</p>
 *
 * <p>Assumptions: no golden-master oracle exists for any path this row serves. The online programs
 * behind it cannot be run end to end without a CICS runtime, as the repository records at
 * {@code tests/README.md:83-85}, so the recorded outputs that hold the batch contexts to a byte
 * comparison have no counterpart here. Parity for this edge rests on the transcribed rules and on
 * the tests that assert them, which is a weaker guarantee than a byte comparison and is stated
 * rather than implied.</p>
 *
 * @param displayCardNumber the masked rendering that identifies this card to a person, present and
 *     at most sixteen characters; the mapper produces it as the card number's last four digits
 *     behind a masking prefix, so it is never the full number on this shape whatever authority the
 *     caller holds, and it is accepted as an input nowhere in the contract
 * @param accountId the account this card belongs to, as exactly eleven digit characters with leading
 *     zeros significant, carried across {@code CARD-ACCT-ID PIC 9(11)} at
 *     {@code app/cpy/CVACT02Y.cpy:6}; it travels beside the ordering key rather than forming part of
 *     it, so it is data on the row and not a position
 * @param activeStatus whether the card is active, as exactly one uppercase character drawn from
 *     {@code Y} and {@code N}, carried across {@code CARD-ACTIVE-STATUS PIC X(01)} at
 *     {@code app/cpy/CVACT02Y.cpy:10} and matching the two-value domain the stored column admits
 */
public record CardSummary(

        // WHY : Trade-offs: a width and no digit pattern, where the request side declares both.
        //       The mapper delivers this value already masked, so a sixteen-digit pattern would
        //       refuse every valid rendering; the contract declares the width alone for it.
        @NotBlank
        @Size(max = 16)
        String displayCardNumber,

        // WHY : Assumptions: the contract states this bound twice, as a length on lines 1431 and
        //       1432 and as a pattern on line 1433, and both are reproduced so that a bad
        //       submission is refused with an entry naming this member rather than with a parse
        //       failure naming none. The anchors restate the pattern rather than extend it, because
        //       a constraint pattern is matched against the whole value.
        @NotBlank
        @Size(min = 11, max = 11)
        @Pattern(regexp = "^[0-9]{11}$")
        String accountId,

        // WHY : Alternatives Considered: an enumerated Java type for the two values, rejected
        //       because the contract declares a string enumeration and a wire type that admits
        //       exactly the two accepted characters keeps a third one refusable as a field error
        //       rather than as a deserialisation failure that names no member.
        @NotBlank
        @Pattern(regexp = "^[YN]$")
        String activeStatus) {
}
