package com.carddemo.account.dto;

/**
 * The request body of the account update endpoint, and the only EDITING request body this context has.
 *
 * <h2>Where this body is accepted</h2>
 *
 * <p>Assumptions: this is a LIVE contract, and the route, the published operation and the consuming
 * service all exist. {@code com.carddemo.account.api.AccountController#update} binds it as a
 * {@code @Valid @RequestBody} on {@code PUT /api/v1/accounts/{accountId}} beneath a required
 * {@code If-Match} precondition; {@code src/main/resources/openapi/account-api.yaml} publishes that
 * operation and names the {@code AccountUpdateRequest} schema for this shape, component for component;
 * and {@code com.carddemo.account.service.AccountUpdateService} edits it, applies both halves of it and
 * answers with the committed state and the new revision.</p>
 *
 * <p>Refactoring Rationale: this section said the opposite. It opened "What is not yet wired, stated
 * before the contract" and declared this a PENDING TARGET contract that no route accepted, that the
 * published document named no schema for, and whose consuming service "does not exist". Every one of
 * those three statements was true of the tree it was written against and is now false, and the section
 * is REPLACED rather than amended because its framing was the problem: a reader who trusted it would
 * conclude the account update was unreachable and would go looking for the missing route, and a reviewer
 * would take an authored, tested and published write path for an unimplemented one. The obligation the
 * old section discharged -- letting a reader tell a not-yet-wired contract from a broken one -- has no
 * subject any more, because nothing here is unwired.</p>
 *
 * <p>Every component below is one editable field of the baseline account-update screen, carried across
 * field for field from that screen's BMS symbolic map at {@code app/cpy-bms/COACTUP.CPY} and from the
 * program that receives it, {@code app/cbl/COACTUPC.cbl}. The record is transport representation and
 * nothing else: it holds no business rule, reads no row, and performs no conversion.</p>
 *
 * <p>Refactoring Rationale: the sentence that stood here said the account view, the customer reads and the
 * cross-reference lookups "take their selection context from the request path and from query parameters
 * instead, so no other endpoint in this context needs a body", and that is no longer true of any of them.
 * Every machine-facing read on this context now carries its key in a body -- {@link AccountLookupRequest},
 * {@link CardXrefLookupRequest} and {@link CustomerLookupRequest} -- because an identifier in a request line
 * is composed into the load balancer's access record before any application code runs, and nothing inside a
 * service can withdraw it. Those three records are SELECTION bodies carrying one key each; this one is an
 * EDITING body carrying forty-three submitted values, which is the distinction the opening sentence now
 * draws. The end-user account view and update keep their identifier in the path deliberately, and the reason
 * is recorded on {@code AccountController} rather than repeated here.</p>
 *
 * <p>The components are also the keys of the per-field error array the update response carries. That
 * array is keyed at this granularity and not at record granularity, which means the individual year,
 * month and day components, the three national-identifier parts and the six telephone parts are each
 * separately addressable as an error subject, exactly as the baseline addresses them.</p>
 *
 * <h2>Why there are exactly forty-three components</h2>
 *
 * <p>Assumptions: the arity is derived from the baseline rather than chosen, and two independent counts
 * agree on it exactly. First, {@code app/cpy-bms/COACTUP.CPY} is 668 lines and declares its input group
 * at L17 as {@code 01 CACTUPAI.}, its output redefinition at L343 as
 * {@code 01 CACTUPAO REDEFINES CACTUPAI.}, and 54 named data fields in between. Six of those 54 are
 * screen chrome that no client supplies: {@code TRNNAMEI} L24, {@code TITLE01I} L30, {@code CURDATEI}
 * L36, {@code PGMNAMEI} L42, {@code TITLE02I} L48 and {@code CURTIMEI} L54. Two are message lines the
 * server writes rather than reads: {@code INFOMSGI} L318 at {@code PIC X(45)} and {@code ERRMSGI} L324
 * at {@code PIC X(78)}. Three are function-key legends: {@code FKEYSI} L330 at {@code PIC X(21)},
 * {@code FKEY05I} L336 at {@code PIC X(7)} and {@code FKEY12I} L342 at {@code PIC X(10)}. That leaves
 * 54 minus 6 minus 2 minus 3, which is 43. Note that {@code 02 FILLER PIC X(12).} at L18 is the twelve
 * byte terminal input-output-area header the map generator emits ahead of the first field, and it is
 * never a data field at all; the same twelve bytes reappear at L344 inside the output redefinition.</p>
 *
 * <p>Assumptions: the second count is the receiving program's own. {@code app/cbl/COACTUPC.cbl} is 4236
 * lines and applies one identical idiom to each field it accepts from the screen, testing the incoming
 * value against a marker and against spaces before moving it. Counting those sites returns 43, running
 * from L1051 for the account identifier to L1419 for the primary-cardholder indicator. They are
 * preceded by a single {@code INITIALIZE ACUP-NEW-DETAILS} at L1047 and by one early exit at L1060 to
 * L1062, and there is no per-grouping guard anywhere among them. Two counts taken from two unrelated
 * artifacts arriving at the same 43 is what makes this arity a finding rather than a preference.</p>
 *
 * <h2>Why the components are ordered as they are</h2>
 *
 * <p>Assumptions: the order is the receiving program's normalisation order, not the map's declaration
 * order, and the two genuinely differ. In the map, {@code ACSSTTEI} is declared at L234, then
 * {@code ACSZIPCI} at L246, then {@code ACSCITYI} at L252. In the program the sequence is inverted and
 * extended: {@code ACSCITYI} at L1329, then {@code ACSSTTEI} at L1336, then {@code ACSCTRYI} at L1343,
 * then {@code ACSZIPCI} at L1350. The government-issued identifier diverges further still, sitting mid
 * list in the map at L282 while being normalised third from last at L1401. Following the program's
 * order keeps this record aligned with the sequence in which the fields are actually consumed, and both
 * orders are recorded here so that neither is later mistaken for disorder and tidied.</p>
 *
 * <h2>Why every component is a String</h2>
 *
 * <p>Assumptions: the baseline treats each of these values as characters on the wire and as a number
 * only inside arithmetic, and it says so three separate ways. The first is the character-over-numeric
 * redefinition pattern that fills the before-image group opening at {@code app/cbl/COACTUPC.cbl} L669
 * and closing immediately before {@code 05 ACUP-NEW-DETAILS.} at L757: the current balance is declared
 * {@code PIC X(12)} at L675 and redefined as {@code PIC S9(10)V99} at L676 to L677; the account
 * identifier is {@code PIC X(11)} at L671 and redefined {@code PIC 9(11)} at L672 to L673; the credit
 * limit at L678 and the cash credit limit at L681 follow the same shape; the customer identifier is
 * {@code PIC X(09)} at L710 redefined {@code PIC 9(09)} at L711 to L712; and the credit score is
 * {@code PIC X(03)} at L754 redefined {@code PIC 9(03)} at L755 to L756. The character declaration is
 * the storage; the numeric view is a lens opened over it for comparison.</p>
 *
 * <p>Assumptions: the second way is the map itself. Every money field on the update map is alphanumeric
 * rather than numeric: {@code ACRDLIMI} L90, {@code ACSHLIMI} L114, {@code ACURBALI} L138,
 * {@code ACRCYCRI} L144 and {@code ACRCYDBI} L156 are each {@code PIC X(15)}. The third way is a direct
 * contrast between the two maps of this context: {@code app/cpy-bms/COACTUP.CPY} L60 declares
 * {@code ACCTSIDI} as {@code PIC X(11)}, whereas {@code app/cpy-bms/COACTVW.CPY} L60 declares the
 * same-named field with an all-numeric picture of eleven digit positions. This record derives from the update
 * map and therefore takes the alphanumeric form, which is also the form that lets a value failing a
 * digits check reach validation and be reported against its own field. The numeric views are used where
 * the baseline actually compares magnitudes, which is the conflict predicate at L4117, L4119, L4121,
 * L4123 and L4125, each comparing a record field against the numeric redefinition rather than against
 * the character declaration.</p>
 *
 * <h2>Why the money components are not typed as money</h2>
 *
 * <p>Alternatives Considered: typing the credit limit, cash credit limit, current balance, current
 * cycle credit and current cycle debit as the shared {@code Money} type this migration centralises for
 * exact amounts, which is what the response direction of this same package does.
 * Rejected for this record, for a reason of ordering rather than taste. That shared type registers both
 * halves of a Jackson binding: a serialiser at {@code MoneyModule} L271 and a deserialiser at L272. The
 * deserialiser runs during JSON binding, which is strictly before Bean Validation. A malformed amount
 * arriving on a component of that type would therefore fail while the body was still being read, and
 * the caller would receive one unattributed rejection of the whole submission instead of an error naming
 * the field that was wrong. A {@code String} component lets the malformed value survive binding, reach
 * validation and be reported per field, which is the behaviour the baseline has: it sets that field's
 * own validation flag and highlights that field, leaving the other 42 values intact.</p>
 *
 * <p>Assumptions: the asymmetry with the response direction is deliberate and each direction has its own
 * reason. The response record {@code AccountContextView} in this package types its three money
 * components with the shared type at its L40, because on the way out the registered serialiser is
 * exactly what is wanted: it renders an exact amount as a JSON string so that no client parses it as a
 * JSON number. One bounded context, two directions, two typings, one recorded reason each.</p>
 *
 * <p>Assumptions: the fifteen characters these five components declare is a rendering width and not a
 * value width, which is why treating it as a numeric precision would be wrong.
 * {@code app/cbl/COACTUPC.cbl} L370 declares {@code WS-EDIT-CURRENCY-9-2} as {@code PIC X(15)} and L371
 * declares its formatted companion {@code WS-EDIT-CURRENCY-9-2-F} as {@code PIC +ZZZ,ZZZ,ZZZ.99}, an
 * edit mask measuring exactly 15 characters once its sign, its two group separators and its decimal
 * point are counted. The value those 15 characters render is the zoned {@code PIC S9(10)V99} of
 * {@code app/cpy/CVACT01Y.cpy} L7, L8, L9, L13 and L14, which occupies 12 bytes. The extra three
 * characters are punctuation and a sign position, so a component carrying the rendered form is
 * necessarily wider than the amount it denotes.</p>
 *
 * <h2>Why each calendar value arrives as three components</h2>
 *
 * <p>Assumptions: the account open, expiration and reissue dates and the customer date of birth each
 * arrive as three components rather than one, because the baseline validates them a part at a time and
 * reports a part at a time. The map decomposes each one into a four-character year, a two-character
 * month and a two-character day, and the receiving program normalises all twelve parts individually
 * between L1144 and L1204 and again between L1256 and L1270. Composition into a stored date is the
 * mapper's work, not this record's.</p>
 *
 * <p>Assumptions: the composition has a width bridge in it, from eight characters to ten, and the
 * baseline evidences that bridge three independent ways. First, the program's before-image holds each
 * date in eight characters with named parts: the open date is {@code PIC X(08)} at L684 with its year,
 * month and day at L685 to L689; the expiration date is {@code PIC X(08)} at L690 with parts at L691 to
 * L695, whose names abbreviate to {@code ACUP-OLD-EXP-YEAR}, {@code -MON} and {@code -DAY}; the reissue
 * date is {@code PIC X(08)} at L696 with parts at L697 to L701; and the date of birth is
 * {@code PIC X(08)} at L746 with parts at L747 to L751. Second, the output edit variable holds ten
 * characters: L361 declares {@code WS-EDIT-DATE-X} as {@code PIC X(10)} and L362 to L367 redefine it as
 * a four-character year, a one-byte filler, a two-character month, a one-byte filler and a
 * two-character day. Those two one-byte fillers are the separators, and they are the whole of the
 * difference between eight and ten.</p>
 *
 * <p>Assumptions: the third way is arithmetic and admits no other reading. The conflict predicate at
 * L4174 to L4179 compares the ten-character stored date of birth against the eight-character
 * before-image using deliberately mismatched reference modification: position 1 length 4 against
 * position 1 length 4, then position 6 length 2 against position 5 length 2, then position 9 length 2
 * against position 7 length 2. The one-place and two-place displacements are exactly the separators the
 * stored form carries at positions 5 and 8 and the before-image does not. The stored widths this bridges
 * to are {@code PIC X(10)} at {@code app/cpy/CVACT01Y.cpy} L10, L11 and L12 for the three account dates
 * and {@code PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy} L19 for the date of birth. The three account
 * dates reach the same bridge by a second technique in the same paragraph, at L4127 to L4129, L4131 to
 * L4133 and L4135 to L4137, where only the record side is reference-modified and the before-image side
 * is named through its sub-fields. Two techniques for one bridge are recorded here and normalised in
 * neither direction, because each is evidence of the same eight-versus-ten fact.</p>
 *
 * <p>Assumptions: per-part validation is the reason the decomposition exists rather than an artifact of
 * it. The program performs the shared date edit four separate times, once per date, at L1479 to L1481,
 * L1491 to L1493, L1504 to L1506 and L1535 to L1537, and then performs a second, distinct date-of-birth
 * edit at L1540 to L1541, so there are two entry points and not one. The algorithm behind them lives in
 * {@code app/cpy/CSUTLDPY.cpy} with its working storage in {@code app/cpy/CSUTLDWY.cpy}, included at
 * {@code app/cbl/COACTUPC.cbl} L166; three of its work fields are declared by the including program
 * instead, at L151 to L158 as {@code PIC S9(4) COMP-3}, with L152 to L153 giving the divisor an initial
 * value of 4.</p>
 *
 * <h2>Why two three-part splits behave differently</h2>
 *
 * <p>Assumptions: the national identifier and each telephone number both arrive in three parts, and the
 * resemblance stops there. The national identifier splits three, two, four. Its edit structure opens at
 * {@code app/cbl/COACTUPC.cbl} L117: part one is {@code PIC X(3)} at L118 with a numeric redefinition at
 * L119 to L120 carrying a genuine domain restriction at L121 to L123, which excludes the values 0, 666
 * and the range 900 through 999; part two is {@code PIC X(2)} at L124 to L126; part three is
 * {@code PIC X(4)} at L127 to L129; and L130 to L131 redefine the whole group as {@code PIC 9(09)}.
 * Three plus two plus four is nine, and nine is the exact declared width of {@code CUST-SSN} at
 * {@code app/cpy/CVCUS01Y.cpy} L17, so the three parts recompose byte for byte with no separator and no
 * padding introduced anywhere.</p>
 *
 * <p>Assumptions: each telephone number splits three, three, four, and its stored form does carry
 * punctuation, which is the difference that must not be lost. The first number's before-image is
 * {@code PIC X(15)} at L722 and is redefined at L723 to L731 as a one-byte filler, a three-character
 * part, a one-byte filler, a three-character part, a one-byte filler, a four-character part and a
 * two-byte filler. One plus three plus one plus three plus one plus four plus two is 15, matching
 * {@code CUST-PHONE-NUM-1} at {@code app/cpy/CVCUS01Y.cpy} L15 exactly; the second number repeats the
 * shape at L732 to L741 against L16. The punctuation those single-byte fillers reserve is visible in the
 * baseline as commented-out value clauses, at L90 and L91 for one and at L95 and L96 for the other, and
 * the program never writes it: a search for a move of a literal parenthesis or hyphen across all 4236
 * lines returns nothing. So ten characters of a fifteen-character stored value are supplied by the six
 * telephone components and the remainder is reserved space.</p>
 *
 * <p>Assumptions: the two splits are also read at two different granularities, and both are load
 * bearing. The telephone parts are moved individually at L1359 to L1375, using a low-values move on the
 * blank arm, and change detection compares them individually at L1748 to L1750; but the conflict
 * predicate compares each telephone number whole, at L4169 and L4170. The national identifier is
 * compared whole at L4171. A mapper reading only the conflict predicate would conclude these values are
 * atomic, and a mapper reading only the receive paragraph would conclude they are not; both are true at
 * their own stage, and this record sits at the stage where they are parts.</p>
 *
 * <h2>Why no width is enforced at runtime</h2>
 *
 * <p>Trade-offs: each component's declared width is documented on its own tag below and is deliberately
 * not expressed as a constraint annotation, so no value is rejected here for being longer or shorter
 * than the screen field it came from. The reason is that the baseline does not reject on width either;
 * it pads on the way in and truncates on the way out, and it does so measurably. On the companion view
 * screen, {@code app/cbl/COACTVWC.cbl} L515 moves the ten-character customer postal code into
 * {@code ACSZIPCO}, which {@code app/cpy-bms/COACTVW.CPY} L410 declares as {@code PIC X(5)}, discarding
 * five characters; L517 and L518 move the two fifteen-character telephone values into {@code ACSPHN1O}
 * and {@code ACSPHN2O}, which L428 and L440 declare as {@code PIC X(13)}, discarding two more. A hard
 * length rejection in this record would therefore refuse values the reference system accepts and
 * silently shortens, which would be a behavioural change rather than a stricter reading of the same
 * behaviour. The cost accepted is that an over-long value is not stopped at the boundary; it is carried
 * to the validation and mapping stages, where the reference system's own padding and truncation rules
 * are applied and where a per-field error can still be raised. This choice is available because
 * {@code services/common-lib/pom.xml} L229 to L230 declares the constraint-annotation dependency
 * {@code optional}, so the annotations are a compile-scope convenience rather than a runtime guarantee,
 * and a consumer excluding that dependency still binds this record correctly.</p>
 *
 * <h2>Why no blank-marker normalisation happens here</h2>
 *
 * <p>Refactoring Rationale: all 43 of the baseline's receive sites treat an incoming asterisk as
 * equivalent to blank, and this record deliberately does not. That equivalence exists only because the
 * terminal echoed a marker the server had written moments earlier, and the target has no echo to
 * receive. The marker's origin is {@code app/cpy/CSSETATY.cpy}, a procedure-division fragment rather
 * than a data record, parameterised by placeholder names: at L18 to L19 it tests whether a field's
 * validation flag is not-ok or blank, at L21 to L22 it moves the red colour attribute into that field's
 * colour subfield, and at L24 to L25, only for the blank case, it moves an asterisk into that field's
 * output subfield. Two different subfields for two different purposes. The 3270 then returned that
 * asterisk on the next transmission, which is why {@code app/cbl/COACTUPC.cbl} L1051 to L1052 and its
 * 42 counterparts must read it back as blank. A stateless request is not a screen turn, so the asterisk
 * cannot arrive, and treating it as blank here would instead corrupt a legitimate value that happens to
 * begin with one. The marker is preserved in the outward direction only, on the response side, for the
 * blank case it originally signalled.</p>
 *
 * <p>Refactoring Rationale: the highlight's re-entry gate is severed with it, and nothing replaces it.
 * {@code app/cpy/CSSETATY.cpy} L20 conjoins the highlight condition with a re-entry predicate supplied
 * by {@code app/cpy/COCOM01Y.cpy}, which declares a one-digit context field at L29 and its two
 * condition names at L30 and L31 for first entry and for the turn after it. That field was part of the
 * structure the terminal passed back and forth, and this migration has no equivalent of it anywhere, so
 * error presentation in the target is driven entirely by the response body and never by a remembered
 * turn. No component of this record encodes whether a submission is a first attempt, and none should be
 * added, because a stateless handler that answers with a field-error array has no such distinction to
 * make in the first place.</p>
 *
 * <h2>Why there is no account postal code</h2>
 *
 * <p>Assumptions: the account record declares a postal code that this screen never touches, and its
 * absence here is the baseline's, not an omission. {@code app/cpy/CVACT01Y.cpy} L15 declares
 * {@code ACCT-ADDR-ZIP} as {@code PIC X(10)}, yet the field name does not occur once in
 * {@code app/cbl/COACTUPC.cbl} and appears on neither symbolic map. The before-image account block
 * spanning L671 to L708 carries 11 of the 12 non-filler fields that record declares, and the one it
 * leaves out is precisely that postal code; the only postal code it does carry is the customer's, at
 * L721. The postal-code component of this record is therefore the customer's, and it maps to
 * {@code CUST-ADDR-ZIP} at {@code app/cpy/CVCUS01Y.cpy} L14, which is {@code PIC X(10)} against the
 * screen field's five characters. That five-into-ten divergence is a mapper concern, and it is noted
 * here because it is the reason this component is narrower than the column behind it.</p>
 *
 * <h2>Why the city component is an address line</h2>
 *
 * <p>Assumptions: the customer record has no city field at all, so the city component maps to the third
 * address line. A search for a customer city field across {@code app/cpy/} returns nothing, and so does
 * the same search in {@code app/cbl/CBCUS01C.cbl}, the batch program that reads that record. The
 * positive proof is on the view side, where {@code app/cbl/COACTVWC.cbl} L513 moves
 * {@code CUST-ADDR-LINE-3} into the city field of the view map. The widths corroborate it:
 * {@code app/cpy/CVCUS01Y.cpy} L11 declares that line as {@code PIC X(50)} and the screen field is also
 * 50 characters, so the correspondence is exact rather than approximate. The component keeps the name
 * the screen gives it, because that is what a caller filling this form is supplying, and the non-obvious
 * target of the mapping is recorded rather than hidden behind a rename.</p>
 *
 * <h2>Why no validation or error type is declared here</h2>
 *
 * <p>Assumptions: the shape of the per-field error array this request feeds is owned by
 * {@code com.carddemo.common.validation.FieldValidationFlag} and is consumed here rather than
 * re-declared, so this record contributes only the key set. The baseline's own flag surface is a group
 * opening at {@code app/cbl/COACTUPC.cbl} L191 as {@code 05 WS-NON-KEY-FLAGS.} and closing at L352, and
 * it is worth recording that three different sentinel conventions coexist inside that one program.
 * The key-filter convention uses a valid marker of {@code '1'}, a not-ok marker of {@code '0'} and a
 * blank marker of a space, and it governs the two key fields at L183 to L186 and L187 to L190. The
 * non-key convention uses low-values for valid, {@code '0'} for not-ok and {@code 'B'} for blank
 * throughout the L191 to L352 group, matching the shared date-edit working storage at
 * {@code app/cpy/CSUTLDWY.cpy} L43 to L57 exactly. The third convention is different in kind: at L193
 * and at L350 the flag byte holds the field's actual domain value when the field is valid, admitting
 * {@code 'Y'} and {@code 'N'} rather than a sentinel, and those two govern the active-status and
 * primary-cardholder components of this record. A single Java flag type must therefore accommodate all
 * three, which is exactly why it is centralised and why no local variant is introduced here.</p>
 *
 * <h2>Why one component has no validation flag behind it</h2>
 *
 * <p>Assumptions: the group-identifier component is editable and round-trips fully, yet no validation
 * flag exists for it anywhere in the receiving program, so nothing about its content is checked. Its
 * round trip is complete in every other respect: the stored value is copied into the before-image at
 * L3847, the screen value is copied into the new-values group at L1217, the new value is copied into the
 * update record at L4002, change detection compares old against new at L1698 to L1700 after folding case
 * and trimming, and the conflict predicate compares them again at L4139 to L4140 after folding case. A
 * search for a group-identifier validation flag returns nothing, which means the field is accepted as
 * given. That is recorded because the absence is evidence about the baseline rather than a gap in this
 * record, and because a reader who assumed symmetry with the other 42 components would look for a check
 * that was never there.</p>
 *
 * <h2>Why the record is flat and hand-written</h2>
 *
 * <p>Alternatives Considered: nesting the components into date, identifier, address and telephone
 * groups, which would shorten the declaration and read more tidily. Rejected because the baseline
 * offers no evidence for any grouping at this boundary: the receiving program initialises the whole set
 * once with a single {@code INITIALIZE ACUP-NEW-DETAILS} at {@code app/cbl/COACTUPC.cbl} L1047 and then
 * treats all 43 as peers, with no guard around any subset. The companion view program is the contrast
 * that makes the point, because it does guard, using two separately gated blocks at
 * {@code app/cbl/COACTVWC.cbl} L471 to L472 and L493. Inventing a grouping here would assert a structure
 * the reference does not have, and it would additionally break the flat key space the per-field error
 * array depends on.</p>
 *
 * <p>Alternatives Considered: generating the accessors, either with an annotation processor for
 * boilerplate or with a generated mapper for the translation. Both are rejected across this migration and
 * both rejections apply here with force. Generated accessors cannot carry a docstring, and the gate this
 * file is audited by leaves no room for one that does not:
 * {@code config/checkstyle/checkstyle.xml} declares {@code MissingJavadocMethod} at L363 with
 * {@code scope} set to private at L364 and {@code allowMissingPropertyJavadoc} set to false at L365, and
 * it declares {@code MissingJavadocType} at L308 with {@code RECORD_DEF} among its tokens at L310 to
 * L311, so a generated member would fail the build rather than merely go undocumented. A generated
 * mapper has nowhere to record a justification, and this record's translation needs one at almost every
 * field: it composes each calendar value out of three parts across the eight-to-ten width bridge proven
 * at {@code app/cbl/COACTUPC.cbl} L4174 to L4179, recomposes one identifier out of three parts with no
 * separator into {@code PIC 9(09)} per L130 to L131 while leaving another three-part value's separators
 * reserved per L723 to L731, drops the twelve-byte header at {@code app/cpy-bms/COACTUP.CPY} L18,
 * renames the field misspelled at {@code app/cpy/CVACT01Y.cpy} L11, resolves a city onto
 * {@code CUST-ADDR-LINE-3} at {@code app/cpy/CVCUS01Y.cpy} L11, and encrypts the two identifiers at
 * {@code app/cpy/CVCUS01Y.cpy} L17 and L18 whose plain values it accepts. A Java record with an explicit
 * component list gives the same brevity with every member documentable, which is why it is the shape
 * used.</p>
 *
 * <h2>Baseline naming irregularities carried across unchanged</h2>
 *
 * <p>Assumptions: several baseline names are irregular, and they are cited here in their exact on-disk
 * spelling so that a later reader does not correct a citation into something that cannot be found. In
 * {@code app/cbl/COACTUPC.cbl}, L108 declares a telephone-prefix edit field whose name repeats the
 * {@code EDIT} token, and L112 declares the line-number edit field with that same repetition and also
 * without the country qualifier its siblings carry, while both fields' condition names at L109 to L111
 * and following do use the regular form. The flag-suffix convention has four coexisting variants: a
 * singular suffix on the first component of each composite group at L219, L237, L251, L265, L318 and
 * L333; no suffix at all on the second and third components of those groups; and a plural suffix on all
 * three national-identifier components at L135, L139 and L143 and on several scalar fields. On the record
 * side, two account date fields are misspelled in {@code app/cpy/CVACT01Y.cpy} L11 and in the
 * before-image at {@code app/cbl/COACTUPC.cbl} L690, whose own sub-field names at L691 to L695 abbreviate
 * rather than repeat the misspelling. The map's month fields are the short forms and not longer ones, and
 * the government-identifier field's name is not the abbreviation a reader might expect. Every one of
 * these is quoted as found: the baseline is the specification for this migration and is read, never
 * rewritten, so an irregular name is a fact to cite rather than a defect to act on. Where the target
 * chooses a regular Java name over an irregular baseline one, as it does for the two misspelled date
 * fields, the divergence is a documented rename and not a silent one.</p>
 *
 * <h2>Where the concurrency precondition lives, and why it is not a component here</h2>
 *
 * <p>Assumptions: this record carries NO revision, entity tag or before-image component, and the
 * precondition it needs travels instead in the {@code If-Match} request header, which
 * {@code com.carddemo.account.api.AccountController} requires on the update route and which
 * {@code com.carddemo.account.service.AccountUpdateService} enforces before applying anything. The
 * placement follows the sibling response record's own recorded rationale -- {@code AccountUpdateResponse}
 * rejects a version component on the grounds that an optimistic-lock version is transport metadata and
 * belongs in a header rather than in a body a client may hand back -- and a request that declared one
 * while the response published one in a header would have given the same value two homes that could
 * disagree.</p>
 *
 * <p>Refactoring Rationale: the placement is stated here explicitly because its ABSENCE previously read
 * as an omission and was reported as one. Nothing in this file said where the precondition lived, and
 * nothing anywhere emitted or required a revision, so the check the baseline performs had no target form
 * at all: a handler would have loaded the current row, applied the edit and committed, advancing the
 * provider's version from whatever it had just read -- overwriting a concurrent change made during the
 * submitter's think time. That is exactly the loss the reference snapshot exists to prevent. It
 * snapshots the whole pre-edit record into {@code ACUP-OLD-*} from {@code app/cbl/COACTUPC.cbl} L669
 * onward, carries it across the pseudo-conversational gap in the communication area, sets
 * {@code WS-DATACHANGED-FLAG} at L168 when the field-by-field comparison fails, and issues
 * {@code EXEC CICS SYNCPOINT ROLLBACK} at L4095 to L4104 rather than rewriting.</p>
 *
 * <p>Assumptions: a stateless handler holds no snapshot between turns, which is why the submitter
 * returns the revision it was given rather than the values it was given. The migration's
 * pseudo-conversational-state analysis is what makes that the only available shape: the communication
 * area does not travel, so a before-image cannot.</p>
 *
 *
 * @param accountId {@code String} identifying the account to update; screen field {@code ACCTSIDI},
 *     {@code app/cpy-bms/COACTUP.CPY} L60, declared {@code PIC X(11)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1051. Alphanumeric here although the view map declares the
 *     same-named field numeric at {@code app/cpy-bms/COACTVW.CPY} L60, and although the stored column
 *     behind it is {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy} L5
 * @param activeStatus {@code String} carrying the account's active indicator; screen field
 *     {@code ACSTTUSI}, {@code app/cpy-bms/COACTUP.CPY} L66, declared {@code PIC X(1)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1065. Its domain is the two values admitted by the condition at
 *     L193, which is one of the two flags holding a real domain value rather than a sentinel; stored as
 *     {@code ACCT-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT01Y.cpy} L6
 * @param creditLimit {@code String} carrying the account's credit limit as rendered digits; screen field
 *     {@code ACRDLIMI}, {@code app/cpy-bms/COACTUP.CPY} L90, declared {@code PIC X(15)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1073. The 15 characters are the edit-mask width of L371, not a
 *     precision; the stored column is {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at
 *     {@code app/cpy/CVACT01Y.cpy} L8, and the magnitude comparison uses the numeric view at L4119
 * @param cashCreditLimit {@code String} carrying the cash advance limit as rendered digits; screen field
 *     {@code ACSHLIMI}, {@code app/cpy-bms/COACTUP.CPY} L114, declared {@code PIC X(15)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1087. Stored as {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} at
 *     {@code app/cpy/CVACT01Y.cpy} L9, compared through the numeric view at L4121
 * @param currentBalance {@code String} carrying the current balance as rendered digits, signed when
 *     negative; screen field {@code ACURBALI}, {@code app/cpy-bms/COACTUP.CPY} L138, declared
 *     {@code PIC X(15)}, normalised at {@code app/cbl/COACTUPC.cbl} L1101. Stored as
 *     {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} L7, whose before-image is the
 *     clearest instance of the character-over-numeric pattern at L675 to L677, and compared through the
 *     numeric view at L4117
 * @param currentCycleCredit {@code String} carrying the current cycle's credit total as rendered digits;
 *     screen field {@code ACRCYCRI}, {@code app/cpy-bms/COACTUP.CPY} L144, declared {@code PIC X(15)},
 *     normalised at {@code app/cbl/COACTUPC.cbl} L1115. Stored as
 *     {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} L13, compared through
 *     the numeric view at L4123
 * @param currentCycleDebit {@code String} carrying the current cycle's debit total as rendered digits;
 *     screen field {@code ACRCYDBI}, {@code app/cpy-bms/COACTUP.CPY} L156, declared {@code PIC X(15)},
 *     normalised at {@code app/cbl/COACTUPC.cbl} L1129. Stored as
 *     {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} L14, compared through
 *     the numeric view at L4125
 * @param openDateYear {@code String} carrying the four-digit year of the account open date; screen field
 *     {@code OPNYEARI}, {@code app/cpy-bms/COACTUP.CPY} L72, declared {@code PIC X(4)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1144. One of three parts composing
 *     {@code ACCT-OPEN-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy} L10 across the eight-to-ten
 *     bridge, whose before-image is {@code PIC X(08)} at L684
 * @param openDateMonth {@code String} carrying the two-digit month of the account open date; screen
 *     field {@code OPNMONI}, {@code app/cpy-bms/COACTUP.CPY} L78, declared {@code PIC X(2)}, normalised
 *     at {@code app/cbl/COACTUPC.cbl} L1151. Its before-image part is named at L688, and the composed
 *     value occupies positions 6 and 7 of the stored ten characters, as L4128 shows
 * @param openDateDay {@code String} carrying the two-digit day of the account open date; screen field
 *     {@code OPNDAYI}, {@code app/cpy-bms/COACTUP.CPY} L84, declared {@code PIC X(2)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1158. Its before-image part is named at L689, and the composed value
 *     occupies positions 9 and 10 of the stored ten characters, as L4129 shows
 * @param expirationDateYear {@code String} carrying the four-digit year of the account expiration date;
 *     screen field {@code EXPYEARI}, {@code app/cpy-bms/COACTUP.CPY} L96, declared {@code PIC X(4)},
 *     normalised at {@code app/cbl/COACTUPC.cbl} L1167. Composes the record field misspelled
 *     {@code ACCT-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy} L11, which the target names
 *     regularly as a documented rename; the before-image is {@code PIC X(08)} at L690
 * @param expirationDateMonth {@code String} carrying the two-digit month of the account expiration date;
 *     screen field {@code EXPMONI}, {@code app/cpy-bms/COACTUP.CPY} L102, declared {@code PIC X(2)},
 *     normalised at {@code app/cbl/COACTUPC.cbl} L1174. Its before-image part at L694 abbreviates the
 *     misspelled group name rather than repeating it, and the comparison is at L4132
 * @param expirationDateDay {@code String} carrying the two-digit day of the account expiration date;
 *     screen field {@code EXPDAYI}, {@code app/cpy-bms/COACTUP.CPY} L108, declared {@code PIC X(2)},
 *     normalised at {@code app/cbl/COACTUPC.cbl} L1181. Its before-image part is named at L695 and the
 *     comparison is at L4133
 * @param reissueDateYear {@code String} carrying the four-digit year of the account reissue date; screen
 *     field {@code RISYEARI}, {@code app/cpy-bms/COACTUP.CPY} L120, declared {@code PIC X(4)},
 *     normalised at {@code app/cbl/COACTUPC.cbl} L1190. Composes
 *     {@code ACCT-REISSUE-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy} L12, whose before-image is
 *     {@code PIC X(08)} at L696 with parts at L697 to L701
 * @param reissueDateMonth {@code String} carrying the two-digit month of the account reissue date;
 *     screen field {@code RISMONI}, {@code app/cpy-bms/COACTUP.CPY} L126, declared {@code PIC X(2)},
 *     normalised at {@code app/cbl/COACTUPC.cbl} L1197. Its before-image part is named at L700 and the
 *     comparison is at L4136
 * @param reissueDateDay {@code String} carrying the two-digit day of the account reissue date; screen
 *     field {@code RISDAYI}, {@code app/cpy-bms/COACTUP.CPY} L132, declared {@code PIC X(2)}, normalised
 *     at {@code app/cbl/COACTUPC.cbl} L1204. Its before-image part is named at L701 and the comparison
 *     is at L4137
 * @param groupId {@code String} carrying the account's disclosure-group identifier; screen field
 *     {@code AADDGRPI}, {@code app/cpy-bms/COACTUP.CPY} L150, declared {@code PIC X(10)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1213 and moved on at L1217. Stored as
 *     {@code ACCT-GROUP-ID PIC X(10)} at {@code app/cpy/CVACT01Y.cpy} L16. This is the one component for
 *     which the baseline declares no validation flag at all, so its content is accepted as given even
 *     though it round-trips fully through L3847, L4002, L1698 to L1700 and L4139 to L4140
 * @param customerId {@code String} identifying the customer whose details accompany the account; screen
 *     field {@code ACSTNUMI}, {@code app/cpy-bms/COACTUP.CPY} L162, declared {@code PIC X(9)},
 *     normalised at {@code app/cbl/COACTUPC.cbl} L1224. Its before-image is the character-over-numeric
 *     pair at L710 to L712, and the stored column is {@code CUST-ID PIC 9(09)} at
 *     {@code app/cpy/CVCUS01Y.cpy} L5
 * @param ssnPart1 {@code String} carrying the first three characters of the customer's national
 *     identifier; screen field {@code ACTSSN1I}, {@code app/cpy-bms/COACTUP.CPY} L168, declared
 *     {@code PIC X(3)}, normalised at {@code app/cbl/COACTUPC.cbl} L1233. This is the part the baseline
 *     restricts by domain at L121 to L123, excluding 0, 666 and the range 900 through 999; it is
 *     accepted here in the clear over an encrypted transport and is stored encrypted and returned masked
 * @param ssnPart2 {@code String} carrying the middle two characters of the customer's national
 *     identifier; screen field {@code ACTSSN2I}, {@code app/cpy-bms/COACTUP.CPY} L174, declared
 *     {@code PIC X(2)}, normalised at {@code app/cbl/COACTUPC.cbl} L1240. Its edit declaration is at
 *     L124 to L126, and it carries no separator of its own
 * @param ssnPart3 {@code String} carrying the last four characters of the customer's national
 *     identifier; screen field {@code ACTSSN3I}, {@code app/cpy-bms/COACTUP.CPY} L180, declared
 *     {@code PIC X(4)}, normalised at {@code app/cbl/COACTUPC.cbl} L1247. Its edit declaration is at
 *     L127 to L129; with the other two parts it recomposes byte for byte into
 *     {@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy} L17, the whole-group numeric view being
 *     L130 to L131
 * @param dateOfBirthYear {@code String} carrying the four-digit year of the customer's date of birth;
 *     screen field {@code DOBYEARI}, {@code app/cpy-bms/COACTUP.CPY} L186, declared {@code PIC X(4)},
 *     normalised at {@code app/cbl/COACTUPC.cbl} L1256. Composes
 *     {@code CUST-DOB-YYYY-MM-DD PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy} L19, whose before-image is
 *     {@code PIC X(08)} at L746; this is the date whose comparison at L4174 to L4179 supplies the
 *     arithmetic proof of the eight-to-ten bridge
 * @param dateOfBirthMonth {@code String} carrying the two-digit month of the customer's date of birth;
 *     screen field {@code DOBMONI}, {@code app/cpy-bms/COACTUP.CPY} L192, declared {@code PIC X(2)},
 *     normalised at {@code app/cbl/COACTUPC.cbl} L1263. Its before-image part is named at L750, and
 *     L4176 to L4177 compare stored position 6 against before-image position 5
 * @param dateOfBirthDay {@code String} carrying the two-digit day of the customer's date of birth;
 *     screen field {@code DOBDAYI}, {@code app/cpy-bms/COACTUP.CPY} L198, declared {@code PIC X(2)},
 *     normalised at {@code app/cbl/COACTUPC.cbl} L1270. Its before-image part is named at L751, and
 *     L4178 to L4179 compare stored position 9 against before-image position 7. This date is the one
 *     value validated by two entry points, the shared edit at L1535 to L1537 and the specific edit at
 *     L1540 to L1541
 * @param ficoCreditScore {@code String} carrying the customer's credit score as digits; screen field
 *     {@code ACSTFCOI}, {@code app/cpy-bms/COACTUP.CPY} L204, declared {@code PIC X(3)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1279. Its before-image is the character-over-numeric pair at L754 to
 *     L756, and the stored column is {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at
 *     {@code app/cpy/CVCUS01Y.cpy} L22
 * @param firstName {@code String} carrying the customer's given name; screen field {@code ACSFNAMI},
 *     {@code app/cpy-bms/COACTUP.CPY} L210, declared {@code PIC X(25)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1288. Stored as {@code CUST-FIRST-NAME PIC X(25)} at
 *     {@code app/cpy/CVCUS01Y.cpy} L6, so screen and record widths agree exactly
 * @param middleName {@code String} carrying the customer's middle name; screen field
 *     {@code ACSMNAMI}, {@code app/cpy-bms/COACTUP.CPY} L216, declared {@code PIC X(25)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1297. Stored as {@code CUST-MIDDLE-NAME PIC X(25)} at
 *     {@code app/cpy/CVCUS01Y.cpy} L7
 * @param lastName {@code String} carrying the customer's family name; screen field {@code ACSLNAMI},
 *     {@code app/cpy-bms/COACTUP.CPY} L222, declared {@code PIC X(25)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1306. Stored as {@code CUST-LAST-NAME PIC X(25)} at
 *     {@code app/cpy/CVCUS01Y.cpy} L8
 * @param addressLine1 {@code String} carrying the first line of the customer's address; screen field
 *     {@code ACSADL1I}, {@code app/cpy-bms/COACTUP.CPY} L228, declared {@code PIC X(50)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1315. Stored as {@code CUST-ADDR-LINE-1 PIC X(50)} at
 *     {@code app/cpy/CVCUS01Y.cpy} L9
 * @param addressLine2 {@code String} carrying the second line of the customer's address; screen field
 *     {@code ACSADL2I}, {@code app/cpy-bms/COACTUP.CPY} L240, declared {@code PIC X(50)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1322. Stored as {@code CUST-ADDR-LINE-2 PIC X(50)} at
 *     {@code app/cpy/CVCUS01Y.cpy} L10
 * @param city {@code String} carrying the city the screen labels as such; screen field
 *     {@code ACSCITYI}, {@code app/cpy-bms/COACTUP.CPY} L252, declared {@code PIC X(50)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1329. There is no customer city column, so this maps to
 *     {@code CUST-ADDR-LINE-3 PIC X(50)} at {@code app/cpy/CVCUS01Y.cpy} L11, proven positively by
 *     {@code app/cbl/COACTVWC.cbl} L513; widths agree exactly. This is also the first field of the
 *     address block in normalisation order, ahead of the state code the map declares before it
 * @param stateCode {@code String} carrying the customer's state code; screen field {@code ACSSTTEI},
 *     {@code app/cpy-bms/COACTUP.CPY} L234, declared {@code PIC X(2)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1336. Stored as {@code CUST-ADDR-STATE-CD PIC X(02)} at
 *     {@code app/cpy/CVCUS01Y.cpy} L12. Validated against seeded lookup data rather than against a
 *     pattern, which is why no enumeration of state codes appears in this record
 * @param countryCode {@code String} carrying the customer's country code; screen field
 *     {@code ACSCTRYI}, {@code app/cpy-bms/COACTUP.CPY} L258, declared {@code PIC X(3)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1343. Stored as {@code CUST-ADDR-COUNTRY-CD PIC X(03)} at
 *     {@code app/cpy/CVCUS01Y.cpy} L13
 * @param zipCode {@code String} carrying the customer's postal code; screen field {@code ACSZIPCI},
 *     {@code app/cpy-bms/COACTUP.CPY} L246, declared {@code PIC X(5)}, normalised at
 *     {@code app/cbl/COACTUPC.cbl} L1350. This is the customer's postal code and not the account's: the
 *     account record's own {@code ACCT-ADDR-ZIP} at {@code app/cpy/CVACT01Y.cpy} L15 occurs nowhere in
 *     the receiving program. The stored column is {@code CUST-ADDR-ZIP PIC X(10)} at
 *     {@code app/cpy/CVCUS01Y.cpy} L14, twice this component's width, and the view side truncates it the
 *     other way at {@code app/cbl/COACTVWC.cbl} L515
 * @param phone1AreaCode {@code String} carrying the area code of the customer's first telephone number;
 *     screen field {@code ACSPH1AI}, {@code app/cpy-bms/COACTUP.CPY} L264, declared {@code PIC X(3)},
 *     normalised at {@code app/cbl/COACTUPC.cbl} L1357. One of three parts of
 *     {@code CUST-PHONE-NUM-1 PIC X(15)} at {@code app/cpy/CVCUS01Y.cpy} L15, whose before-image
 *     redefinition at L723 to L731 reserves single bytes for separators this record does not carry
 * @param phone1Prefix {@code String} carrying the exchange prefix of the customer's first telephone
 *     number; screen field {@code ACSPH1BI}, {@code app/cpy-bms/COACTUP.CPY} L270, declared
 *     {@code PIC X(3)}, normalised at {@code app/cbl/COACTUPC.cbl} L1364. Its edit field at L108 is one
 *     of the irregularly named ones described above, while its condition names are regular
 * @param phone1LineNumber {@code String} carrying the line number of the customer's first telephone
 *     number; screen field {@code ACSPH1CI}, {@code app/cpy-bms/COACTUP.CPY} L276, declared
 *     {@code PIC X(4)}, normalised at {@code app/cbl/COACTUPC.cbl} L1371. Its edit field at L112 is the
 *     other irregularly named one. The three parts of this number are moved individually at L1359 to
 *     L1375 and compared individually at L1748 to L1750, yet compared whole at L4169
 * @param phone2AreaCode {@code String} carrying the area code of the customer's second telephone number;
 *     screen field {@code ACSPH2AI}, {@code app/cpy-bms/COACTUP.CPY} L288, declared {@code PIC X(3)},
 *     normalised at {@code app/cbl/COACTUPC.cbl} L1378. One of three parts of
 *     {@code CUST-PHONE-NUM-2 PIC X(15)} at {@code app/cpy/CVCUS01Y.cpy} L16, whose before-image
 *     redefinition is at L733 to L741
 * @param phone2Prefix {@code String} carrying the exchange prefix of the customer's second telephone
 *     number; screen field {@code ACSPH2BI}, {@code app/cpy-bms/COACTUP.CPY} L294, declared
 *     {@code PIC X(3)}, normalised at {@code app/cbl/COACTUPC.cbl} L1385
 * @param phone2LineNumber {@code String} carrying the line number of the customer's second telephone
 *     number; screen field {@code ACSPH2CI}, {@code app/cpy-bms/COACTUP.CPY} L300, declared
 *     {@code PIC X(4)}, normalised at {@code app/cbl/COACTUPC.cbl} L1392. This number is compared whole
 *     by the conflict predicate at L4170, matching the treatment of the first
 * @param governmentIssuedId {@code String} carrying the customer's government-issued identifier; screen
 *     field {@code ACSGOVTI}, {@code app/cpy-bms/COACTUP.CPY} L282, declared {@code PIC X(20)},
 *     normalised at {@code app/cbl/COACTUPC.cbl} L1401. Declared mid-list on the map yet normalised third
 *     from last, which is the sharpest instance of the ordering divergence recorded above. Stored as
 *     {@code CUST-GOVT-ISSUED-ID PIC X(20)} at {@code app/cpy/CVCUS01Y.cpy} L18, compared case-folded at
 *     L4172 to L4173, and like the national identifier it is accepted in the clear over an encrypted
 *     transport, stored encrypted and returned masked
 * @param eftAccountId {@code String} carrying the customer's electronic funds transfer account
 *     identifier; screen field {@code ACSEFTCI}, {@code app/cpy-bms/COACTUP.CPY} L306, declared
 *     {@code PIC X(10)}, normalised at {@code app/cbl/COACTUPC.cbl} L1410. Stored as
 *     {@code CUST-EFT-ACCOUNT-ID PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy} L20
 * @param primaryCardHolderIndicator {@code String} carrying whether the customer is the primary
 *     cardholder; screen field {@code ACSPFLGI}, {@code app/cpy-bms/COACTUP.CPY} L312, declared
 *     {@code PIC X(1)}, normalised at {@code app/cbl/COACTUPC.cbl} L1419, the last of the 43 sites. Its
 *     domain is the two values admitted at L350, making it the second of the two flags that hold a real
 *     domain value rather than a sentinel; stored as {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} at
 *     {@code app/cpy/CVCUS01Y.cpy} L21
 */
public record AccountUpdateRequest(
        String accountId,
        String activeStatus,
        String creditLimit,
        String cashCreditLimit,
        String currentBalance,
        String currentCycleCredit,
        String currentCycleDebit,
        String openDateYear,
        String openDateMonth,
        String openDateDay,
        String expirationDateYear,
        String expirationDateMonth,
        String expirationDateDay,
        String reissueDateYear,
        String reissueDateMonth,
        String reissueDateDay,
        String groupId,
        String customerId,
        String ssnPart1,
        String ssnPart2,
        String ssnPart3,
        String dateOfBirthYear,
        String dateOfBirthMonth,
        String dateOfBirthDay,
        String ficoCreditScore,
        String firstName,
        String middleName,
        String lastName,
        String addressLine1,
        String addressLine2,
        String city,
        String stateCode,
        String countryCode,
        String zipCode,
        String phone1AreaCode,
        String phone1Prefix,
        String phone1LineNumber,
        String phone2AreaCode,
        String phone2Prefix,
        String phone2LineNumber,
        String governmentIssuedId,
        String eftAccountId,
        String primaryCardHolderIndicator) {

    /**
     * Renders this submission for a log line, disclosing nothing the caller typed.
     *
     * <p>Refactoring Rationale: a record's generated rendering prints every component, and this record is
     * the SUBMITTED shape -- so the generated form emitted the three national-identifier parts and the
     * government-issued identifier exactly as the caller typed them, in the clear, alongside a name, a
     * whole postal address, two telephone numbers, a date of birth, a credit score and five money values.
     * It is the worst-placed disclosure of the set, because a request record reaches a diagnostic
     * precisely when the request FAILED: a bean-validation failure, a deserialisation failure and an
     * argument-resolution failure each render the offending value. The sensitive-data logging contract in
     * {@code docs/architecture/observability.md} prohibits every one of those values and requires
     * omission rather than abbreviation.</p>
     *
     * <p>Trade-offs: the rendering keeps nothing but the account identifier's PRESENCE and the count of
     * components that arrived populated. Both are diagnostic facts about the submission rather than
     * values from it -- a count says how much of the form was filled in, which is what distinguishes a
     * truncated request body from a rejected field -- and neither can carry a character the caller typed.
     * The cost is that a failed update cannot be traced to a specific account from this string, which the
     * correlation identifier on the request-scoped line already answers.</p>
     *
     * <p>Alternatives Considered: emitting the account identifier itself, on the ground that the caller
     * supplied it and it merely says which account was being edited. Rejected because that contract names
     * account identifiers explicitly, and because a value being caller-supplied is not a property that
     * makes it safe to persist into a log stream -- the caller supplied the national identifier
     * too.</p>
     *
     * @return a rendering naming the type, whether an account identifier was supplied and how many of the
     *     forty-three components arrived populated, and no submitted value, never {@code null}
     */
    @Override
    public String toString() {
        return "AccountUpdateRequest[accountIdSupplied=" + (this.accountId != null
                && !this.accountId.isBlank())
                + ", populatedComponents=" + populatedComponentCount() + ']';
    }

    /**
     * Counts how many components arrived carrying a non-blank value.
     *
     * <p>Assumptions: this exists so the rendering above can report the shape of a submission without
     * reporting any part of its content. The count is computed from the components rather than tracked at
     * construction, because a record has no place to hold derived state and because a stale counter would
     * be worse than no counter.</p>
     *
     * <p>Assumptions: a blank value counts as absent, matching the reference's own treatment. Every
     * presence test in {@code app/cbl/COACTUPC.cbl} compares a screen field against spaces or low values,
     * so a field of blanks is an unfilled field there and is one here.</p>
     *
     * @return the number of components holding a non-{@code null}, non-blank value, between zero and
     *     forty-three
     */
    private int populatedComponentCount() {
        String[] components = {
            this.accountId, this.activeStatus, this.creditLimit, this.cashCreditLimit,
            this.currentBalance, this.currentCycleCredit, this.currentCycleDebit,
            this.openDateYear, this.openDateMonth, this.openDateDay,
            this.expirationDateYear, this.expirationDateMonth, this.expirationDateDay,
            this.reissueDateYear, this.reissueDateMonth, this.reissueDateDay,
            this.groupId, this.customerId,
            this.ssnPart1, this.ssnPart2, this.ssnPart3,
            this.dateOfBirthYear, this.dateOfBirthMonth, this.dateOfBirthDay,
            this.ficoCreditScore, this.firstName, this.middleName, this.lastName,
            this.addressLine1, this.addressLine2, this.city, this.stateCode, this.countryCode,
            this.zipCode, this.phone1AreaCode, this.phone1Prefix, this.phone1LineNumber,
            this.phone2AreaCode, this.phone2Prefix, this.phone2LineNumber,
            this.governmentIssuedId, this.eftAccountId, this.primaryCardHolderIndicator,
        };
        int populated = 0;
        for (String component : components) {
            if (component != null && !component.isBlank()) {
                populated++;
            }
        }
        return populated;
    }
}
