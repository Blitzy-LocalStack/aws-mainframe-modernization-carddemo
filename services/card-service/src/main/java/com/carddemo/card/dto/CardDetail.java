package com.carddemo.card.dto;

import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.security.MaskedCardNumber;
import com.carddemo.common.security.SealedSelector;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * Carries the full stored state of one card to a caller that may not see its account number.
 *
 * <h2>The two operations this shape answers, and the one it does not</h2>
 *
 * <p>{@code services/card-service/src/main/resources/openapi/card-api.yaml} returns this shape from
 * THREE operations. {@code getCard}, the read at {@code GET /api/v1/cards/{cardKey}}, answers with
 * it; {@code updateCard}, the {@code PUT} on the same path, answers with it as well once the change
 * has been committed, so a caller making a second change does not have to read the card again to
 * obtain a current concurrency token; and {@code lookupCard}, the {@code POST} on
 * {@code /api/v1/cards/lookup}, answers with it after resolving a card number supplied in a request
 * body. All three answers describe the same thing -- one row of {@code card.cards}, reported after
 * whatever the operation did to it -- which is why one shape serves them rather than three shapes
 * describing one row.
 *
 * <p>Alternatives Considered: a fourth operation reads the same row and is deliberately NOT answered
 * by this record. {@code getAdminCardDetail}, at {@code GET /api/v1/admin/cards/{cardKey}},

 * returns the account number in full, and the alternative was to let this one record serve that read
 * too by carrying an extra optional member that only the administrative path populates. The contract
 * rejects that arrangement and this record follows it: an optional member is an absence a service
 * has to remember rather than a shape that cannot express the value, so the full number is declared
 * once on {@code AdminCardDetail}, which composes the same shared core and adds it as required. The
 * disclosure boundary is then something the type system holds rather than something a convention
 * asks for, and {@code CardApiContractTest} asserts it structurally.
 *
 * <p>Trade-offs: the accepted cost is that the administrative shape restates one member rather than
 * inheriting it, because Java records do not inherit components and the contract's shared core --
 * which it names {@code CardDetailCore} and returns from no operation -- is therefore expressed as
 * the six members restated in each detail shape rather than as a record of its own. The package
 * charter permits either expression. Restating six members in two shapes is the smaller duplication
 * than the alternative of a fifth type existing only to be composed, and the members that would
 * drift are held to the same contract document by the same contract test.
 *
 * <p>Trade-offs: a second cost is stated explicitly because it is invisible in the result. An
 * instance of this record does not say which of the two operations produced it -- a read and a
 * committed update return the same six members -- so a reader holding one in isolation cannot tell
 * whether the token it carries was merely observed or was just incremented by a change. The operation
 * that answered says it, and the token's own value says it against a previously held one; the record
 * deliberately says neither, because a member that reported which operation had run would be state
 * about the request rather than about the card, and no member of this shape describes anything but
 * the card.
 *
 * <p>Assumptions: this record is what a caller holding only {@code carddemo-user} ever receives, and
 * nothing here reasons about which authority the caller held. The authority decision belongs to
 * {@code com.carddemo.card.config.SecurityConfig} and the choice of shape to
 * {@code com.carddemo.card.api}; a response type that inspected a caller's role would be deciding
 * disclosure in the one place that cannot see the request.
 *
 * <h2>Where the six components come from</h2>
 *
 * <p>Assumptions: {@code app/cpy/CVACT02Y.cpy}, 14 lines, is the width authority for every component
 * that carries stored card data, and its arithmetic is self-checking. Its line 2 declares the record
 * length as 150 bytes and its lines 4 to 11 declare {@code 01 CARD-RECORD} with seven subordinate
 * items: {@code CARD-NUM PIC X(16)} at line 5, {@code CARD-ACCT-ID PIC 9(11)} at 6, the verification
 * value at 7, {@code CARD-EMBOSSED-NAME PIC X(50)} at 8, {@code CARD-EXPIRAION-DATE PIC X(10)} at 9,
 * {@code CARD-ACTIVE-STATUS PIC X(01)} at 10 and a 59-byte padding item at 11. Those widths sum to
 * 16 + 11 + 3 + 50 + 10 + 1 + 59, which is exactly the declared 150, so a component width that
 * disagrees with this copybook is wrong by construction rather than by opinion.
 * {@code app/cbl/CBACT02C.cbl}, 178 lines, corroborates the layout independently by including it with
 * {@code COPY CVACT02Y.} at its line 45 and reading the card file its line 29 selects.
 *
 * <p>Assumptions: five of this record's seven components are that record's data items less two, and the
 * other two have no counterpart in it. The two exclusions are the verification value at line 7, whose
 * total absence is argued below, and the padding item at line 11, whose drop is recorded below as
 * well. Of the two components with no copybook counterpart, {@code version} is the target-side
 * concurrency token, corresponding to a column rather than to a copybook field, and expressing a
 * discipline that is nevertheless the baseline's own as its own section sets out; and {@code selector}
 * is the target-side addressing token, corresponding to nothing stored at all, and existing for the
 * reason its own section gives.
 *
 * <p>Assumptions: the component order is the addressing token, then the record order -- line 5, then 6,
 * then 8, then 9, then 10, with the two excluded items skipped -- then the concurrency token last. The
 * addressing token leads because it is the identity every operation on this shape takes, which is the
 * position an identity conventionally occupies and the position the sibling list row gives it. That is
 * also the order the contract lists its properties in, and the five stored members appear in the order
 * of the columns {@code services/card-service/src/main/resources/db/migration/V1__card.sql} declares
 * for {@code card.cards}, so all three agree and none was reordered to suit the others.
 *
 * <p>Assumptions: this record carries no monetary amount and no timestamp, and the absence is
 * recorded rather than left silent because silence in a package derived from a financial record reads
 * as an oversight. The seven items just enumerated contain no amount and no timestamp, so
 * {@code com.carddemo.common.money.Money} is imported by nothing here and no timestamp type is
 * either. The one date this record does carry is a calendar date rather than an instant, and the
 * twenty-six-character timestamp a refusal carries belongs to
 * {@code com.carddemo.common.error.ApiError} in the shared kernel rather than to any card shape.
 *
 * <h2>The padding item is dropped, and this paragraph is the record of the drop</h2>
 *
 * <p>Assumptions: the 59-byte item at {@code app/cpy/CVACT02Y.cpy} line 11 occupies byte positions 91
 * through 149 of the 150-byte record and exists to carry the record out to that declared length. It
 * holds no value either end could use: no symbolic map presents it -- {@code app/cpy-bms/COCRDSL.CPY}
 * at 200 lines and {@code app/cpy-bms/COCRDUP.CPY} at 224 declare fields for the account identifier,
 * the card number, the embossed name, the status and the expiry parts, and neither declares a field
 * over the padding -- and no program moves anything into it. Transformation rule T1 of the migration
 * plan drops padding and records the drop per record; this paragraph is that record for this one.
 *
 * <p>Assumptions: the padding travels with the layout wherever the layout is restated, and that is
 * evidence of what it is rather than evidence against dropping it.
 * {@code app/cbl/COCRDUPC.cbl} lines 314 to 321 declare a local write record for the update path
 * which restates the six stored fields in record order and closes with the same 59-byte padding item
 * at line 321, because the padding IS the record's length and a shorter structure would not be the
 * record. What the target stores instead is a row, whose length is the sum of its columns, so
 * {@code V1__card.sql} declares no padding column and this record declares no padding component. A
 * component over those bytes would put a value on the wire that means nothing at either end.
 *
 * <h2>No representation of the verification value, in any form</h2>
 *
 * <p>Assumptions: this record preserves an absence the baseline already had rather than withdrawing
 * something it showed, and the distinction matters because the two would be documented differently.
 * A case-insensitive search for that value's abbreviation returns no match in any of the six
 * presentation files of this context -- {@code app/cpy-bms/COCRDSL.CPY},
 * {@code app/cpy-bms/COCRDLI.CPY}, {@code app/cpy-bms/COCRDUP.CPY}, {@code app/bms/COCRDSL.bms},
 * {@code app/bms/COCRDLI.bms} and {@code app/bms/COCRDUP.bms} -- so no screen in the set displayed it
 * and none accepted it. In the update program, 1560 lines, the new-value field for it is declared at
 * {@code app/cbl/COCRDUPC.cbl} line 306 and occurs exactly twice in the whole program: at that
 * declaration, and as the source of one move at line 1464. Its enclosing group is initialised at line
 * 586 and nothing ever moves a value into the field itself, so the baseline populated it from no
 * input. The target adds encipherment of the stored column and adds nothing at all to the wire.
 *
 * <p>Alternatives Considered: rendering the value masked, truncated, or as a length hint. All three
 * are rejected on the same ground, which is that they disclose more than absence does: a masked
 * member still publishes that the value exists, how wide it is, and which member name to attack, so
 * total absence is the only rendering that discloses nothing. The value is not named anywhere in this
 * file for the same reason, which is why the paragraph above describes it rather than spelling it.
 *
 * <h2>Both identifiers travel as characters, and the two reasons are not the same reason</h2>
 *
 * <p>Assumptions: the baseline settles the character form rather than taste, because it keeps both
 * representations of the same storage and moves between them deliberately.
 * {@code app/cpy/CVCRD01Y.cpy}, 46 lines, declares {@code CC-ACCT-ID PIC X(11) VALUE SPACES} at its
 * lines 34 to 35 with {@code CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC 9(11)} at line 36; it declares
 * {@code CC-CARD-NUM PIC X(16) VALUE SPACES} at lines 37 to 38 with the numeric view over it at line
 * 39; and its lines 40 to 42 do the same for the customer identifier. The decisive evidence is the
 * name the baseline gave the group holding the character forms: {@code app/cbl/COCRDUPC.cbl} line 103
 * opens {@code 05 CICS-OUTPUT-EDIT-VARS.} and carries {@code CARD-ACCT-ID-X PIC X(11)} at line 104
 * redefined as {@code PIC 9(11)} at lines 105 to 106, and {@code CARD-CARD-NUM-X PIC X(16)} at line
 * 110 redefined as {@code PIC 9(16)} at lines 111 to 112. The character form is the baseline's own
 * presentation form and the numeric view is a lens onto it, reached for only where a number is
 * required, as at line 1463 where the numeric account identifier drives the record write.
 *
 * <p>Assumptions: the eleven-digit account identifier is a string for three reasons, and exactness is
 * not among them. Its largest value, 99,999,999,999, is far below the exact-integer ceiling of
 * IEEE-754 binary floating point and is held exactly, so claiming exactness as its ground would be a
 * false claim rather than a harmless simplification. Its grounds are the baseline's character wire
 * form cited immediately above; leading zeros, which an eleven-character field carries as data and
 * which a numeric component drops, the contract's own example for this member being
 * {@code 00000000011}; and one uniform digits-only constraint discipline, so that a reviewer has one
 * rule to check across both identifiers rather than two.
 *
 * <p>Assumptions: the sixteen-digit card number carries a second, independent reason, and it is
 * recorded here even though this record never carries that number in full, because the same value's
 * masked rendering derives from it and its full form travels on the administrative shape under the
 * same discipline. The exact-integer ceiling of IEEE-754 binary floating point is two to the
 * fifty-third power, that is 9,007,199,254,740,992, which is itself a sixteen-digit number. The
 * sixteen-digit domain therefore extends beyond the exact-integer range, so exactness is not
 * guaranteed across it. The claim is deliberately no stronger than that: the smallest sixteen-digit
 * value, 1,000,000,000,000,000, sits below that ceiling and is held exactly, so the hazard belongs to
 * the upper part of the domain rather than to all of it. A value that arrives as a string is not
 * subject to it anywhere in the domain, because a client that never parses the value as a number
 * cannot re-round it.
 *
 * <h2>The masked rendering, and the shape it is held to</h2>
 *
 * <p>Refactoring Rationale: {@code displayCardNumber} declares the masked SHAPE, where it was
 * constrained by width alone. The reasoning for the width alone was that a sixteen-digit pattern of the
 * kind {@code accountId} carries would refuse the very value this member is defined to hold, which is
 * true -- and it left out the third option, a pattern that requires the MASK. The consequence was
 * specific rather than theoretical: a maximum of sixteen characters is satisfied exactly by a full
 * sixteen-digit primary account number, so the one value this component must never carry was the one its
 * constraint admitted, and since the masking is applied by {@code com.carddemo.card.mapper} a path that
 * skipped the call published raw storage with nothing on this record to refuse it.
 *
 * <p>Refactoring Rationale: the earlier revision also recorded a REJECTED alternative that was in
 * substance this fix -- a pattern admitting a run of mask characters followed by four digits -- on the
 * ground that it would copy two constants of {@code com.carddemo.common.security.CardNumberMasker} into
 * a wire contract that does not own them, so a change to the shared masker would leave every response
 * failing validation against a stale second definition. That objection was correct about copying and is
 * answered rather than overruled: the expression is not copied here but read from
 * {@code com.carddemo.common.security.MaskedCardNumber}, which sits beside the masker in the module that
 * owns both and derives the expression from the masker's own constants. There is one definition, in the
 * place that owns it, and this contract references it.
 *
 * <p>Refactoring Rationale: a compact constructor refuses an unmasked value as WELL, because the
 * constraint annotation alone does not close the boundary. Bean validation is evaluated by the framework
 * on a body it is asked to validate and is NOT evaluated on a response it serialises, so a mapper
 * publishing raw storage here would produce a response that violates its own declared constraint and is
 * sent anyway. Every instance this service serialises was constructed, so a check in the constructor is
 * the only one that cannot be bypassed -- and it is expressed by calling the shared guard rather than by
 * matching the expression a second time, so the refusal message is the shared one and names the offending
 * position without echoing the value.
 *
 * <p>Assumptions: the published contract was tightened in the same change, so the two still agree. Its
 * masked members declare the identical pattern and an exact length, which is what keeps this record from
 * refusing a body the contract calls valid -- the condition the earlier reasoning was protecting.

 *
 * <p>Alternatives Considered: masking, encipherment and suppression inside this record, either by
 * normalising a value in the constructor or by rendering it behind an accessor. Those remain rejected,
 * and the distinction from the check just described is the point: the constructor REFUSES a value it
 * should never have been handed and TRANSFORMS nothing. Masking here would move a decision the package
 * charter assigns to {@code com.carddemo.card.mapper}, and it would silently repair a mapper that
 * published raw storage, removing the only signal that it had done so.
 *
 * <h2>The expiry date, and the three independent proofs of its layout</h2>
 *
 * <p>Assumptions: the ten characters are a year, a month and a day in that order, separated by
 * hyphens, and that is proven three independent ways rather than inferred from the separators.
 * First, {@code app/cbl/COCRDUPC.cbl} lines 115 to 123 decompose the same ten positions with a
 * {@code REDEFINES}: a 4-character year at line 117, a 1-character separator at 118, a 2-character
 * month at 119, a second separator at 120 and a 2-character day at 121. Second, the update path
 * assembles the stored value in exactly that order, at lines 1467 to 1474, joining the new year, a
 * literal hyphen, the new month, a second hyphen and the new day into the record's date field.
 * Third, the change comparison addresses the parts by position at lines 1503 to 1508, reading the
 * year at {@code (1:4)}, the month at {@code (6:2)} and the day at {@code (9:2)}, and the assignments
 * that follow it repeat the day reference at line 1516; those positions are only the year, month and
 * day of a date written in that order.
 *
 * <p>Assumptions: because the form is ISO-ordered, a lexical comparison and a date comparison agree
 * on it. That is what let the baseline compare these bytes as text at the lines just cited, and it is
 * what lets the owning schema store a true {@code DATE} column without changing any ordering a screen
 * or a report previously observed.
 *
 * <p>Trade-offs: this component is a string of exactly ten characters rather than a
 * {@code java.time.LocalDate}, and the published contract agrees, declaring the property as a string
 * in the date format. What that buys is a wire form which stays byte-identical to the baseline's ten
 * characters and cannot be reformatted by a serialiser configuration, a locale or a client library
 * that renders dates its own way. What it costs is that a caller wanting date arithmetic parses the
 * value itself, and that the constraint on this component establishes the shape of the ten characters
 * without establishing that they name a real day: the thirty-first of February satisfies the pattern.
 * Calendar validity is a rule rather than a shape, so it is enforced where the transcribed rules live,
 * against {@code com.carddemo.common.validation.DateEditValidator}, and the two messages the baseline
 * raises for an unacceptable month and an unacceptable year are its lines 197 to 198 and 199 to 200.
 *
 * <p>Alternatives Considered: carrying the month and the year as separate components, which is the
 * shape the detail screen actually presented. {@code app/cpy-bms/COCRDSL.CPY} declares
 * {@code EXPMONI PIC X(2)} at its line 84 and {@code EXPYEARI PIC X(4)} at line 90 and declares no
 * day field at all, while {@code app/cpy-bms/COCRDUP.CPY} does declare a 2-character day field at its
 * line 96. Splitting the date is rejected because it would discard the day the record genuinely
 * holds, leaving this shape unable to report what the column stores and unable to round-trip a value
 * through the update it also answers. The whole date travels and a client composes whichever parts a
 * given screen showed.
 *
 * <p>Refactoring Rationale: the member is named {@code expirationDate} where the baseline field is
 * spelled {@code CARD-EXPIRAION-DATE}, at {@code app/cpy/CVACT02Y.cpy} line 9 and again in the update
 * program's local write record at {@code app/cbl/COCRDUPC.cbl} line 319. This is a target-side naming
 * decision and not a claim about the COBOL: the baseline spelling is untouched, which is why every
 * citation in this file reproduces it exactly as the copybook writes it, and the pairing of the two
 * spellings is recorded in {@code docs/architecture/data-model-and-schema-mapping.md} so that a reader
 * tracing the field from either side finds the other. The owning schema names the column
 * {@code expiration_date} to match this member rather than the field.
 *
 * <h2>The status flag admits two upper-case characters and nothing else</h2>
 *
 * <p>Assumptions: the value set comes from {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'} at
 * {@code app/cbl/COCRDUPC.cbl} line 91, over a 1-character work field its lines 89 to 90 declare with
 * an initial value of {@code 'N'}, and the test is applied at lines 861 and 863, where the submitted
 * status is moved into that field and the condition is evaluated. The set contains no lower-case
 * member, and the case sensitivity is load-bearing rather than incidental: a COBOL condition name
 * compares the bytes of its field against the literal it was declared with, so admitting the
 * lower-case forms here would publish as valid a value the baseline condition would have refused and
 * the check constraint on {@code card.cards} would reject on write.
 *
 * <p>Assumptions: the same two values are enforced twice more, and the redundancy is deliberate
 * rather than duplication to be removed. {@code V1__card.sql} constrains the column with
 * {@code ck_cards_active_status}, which admits the pair alone, and the published contract declares the
 * property as an enumeration of the same two, so a caller reads the domain without reading this file.
 * The message the baseline raises when the value is outside the pair is
 * {@code 'Card Active Status must be Y or N'} at lines 195 to 196 of that program; the text belongs to
 * the error model in the shared kernel and is not restated as a constant here.
 *
 * <h2>The concurrency token</h2>
 *
 * <p>Assumptions: the token is a component because the contract declares one, and it is declared
 * there as a required integer with a minimum of zero, which this record expresses as a
 * {@code java.lang.Integer} lower-bounded at zero. It mirrors the {@code version} column
 * {@code V1__card.sql} declares {@code NOT NULL DEFAULT 0}, so a card never updated since it was
 * loaded carries zero rather than carrying nothing. A caller neither chooses nor increments it; the
 * service does, on each successful update, and an update presenting a value other than the stored one
 * is refused with 409 rather than applied.
 *
 * <p>Assumptions: optimistic concurrency is the baseline's own discipline, so the token expresses a
 * mechanism rather than introducing one. {@code app/cbl/COCRDUPC.cbl} snapshots the pre-edit values
 * into a separate group at its lines 291 to 301, compares the stored record against that snapshot
 * field by field at lines 1503 to 1508, sets a condition at line 1511 whose literal reads
 * {@code 'Record changed by some one else. Please review'} -- declared at lines 207 to 208, with
 * {@code some one} as two words and no terminating period -- and the write path then tests that
 * condition at lines 1455 to 1457 and branches away from the write when it is set. It snapshotted the
 * values precisely because the read-for-update lock was never held across an operator's thinking
 * time. One integer now stands for that whole field-by-field comparison, and the package charter
 * records that the update request carries the token last read for the card, so the value this
 * component returns is the value a subsequent change presents.
 *
 * <p>Alternatives Considered: declaring the token as a primitive {@code int}. Rejected, and the
 * reason is specific to this member rather than a general preference for the wrapper. Zero is a
 * legitimate stored value here, being exactly what a never-updated card carries, so a primitive
 * cannot distinguish a token that was absent from a token that was present and zero: a body omitting
 * the member would deserialise to zero silently, and a change presenting that zero would be accepted
 * against precisely the never-updated rows whose stored value is also zero. The wrapper reports the
 * omission as a violated constraint naming this member instead, which is the difference between a
 * refused request and a lost update.
 *
 * <h2>What this record is not, and what it never transforms</h2>
 *
 * <p>Assumptions: this record is an inert carrier. It holds the values it was constructed with and
 * transforms none of them: there is no masking here, no encipherment, no padding removal, no case
 * folding and no date reformatting. A value reaching these components has already been through
 * {@code com.carddemo.card.mapper}, which is where the storage representation is converted -- the
 * account identifier from the {@code BIGINT} column to eleven digit characters, the card number to
 * its masked rendering, the date column to its ten characters, and the blank padding a
 * declared-width character column returns stripped from the values that carry it.
 *
 * <p>Assumptions: no component here carries a message, an error, an informational line, a legend or a
 * function-key list. The message contract those screens rendered is 75 characters wide --
 * {@code CCARD-ERROR-MSG PIC X(75)} at {@code app/cpy/CVCRD01Y.cpy} line 28 and
 * {@code CCARD-RETURN-MSG PIC X(75)} at line 29, whose line 30 declares the off state -- and it
 * belongs, with the per-field error array a refused submission answers with, to
 * {@code com.carddemo.common.error.ApiError} in the shared kernel. Two of the widths a reader might
 * expect to find here are neither of those and belong to the rendering surface rather than to any
 * contract: the detail map declares a 40-character informational field at
 * {@code app/cpy-bms/COCRDSL.CPY} line 96, an 80-character error field at line 102 and a single
 * 75-character legend at line 108, and the list and update maps differ from it in all three. None of
 * those widths appears in this record, and 75 is not to be read as interchangeable with 78 or 80.
 *
 * <p>Assumptions: this shape describes exactly one card, so it carries no positioning member of any
 * kind. The envelope that positions a set of rows by key is
 * {@code com.carddemo.common.web.PageResponse} in the shared kernel, parameterised with
 * {@code CardSummary} for the list operation, and it is never restated in this package.
 *
 * <p>Assumptions: nothing here discriminates a first submission from a resubmission. The baseline's
 * update transaction was pseudo-conversational and had to: {@code app/cbl/COCRDUPC.cbl} declares a
 * 1-character action field at its lines 276 to 277 whose condition names distinguish details not yet
 * fetched, details shown, changes made, changes not confirmed and several failure states, the
 * not-confirmed state being the value {@code 'N'} at line 286. A stateless request carries its own
 * context and a refusal answers with a per-field array, so there is no turn to count and no
 * confirmation state to remember, and no analogue of that field exists here.
 *
 * <p>Assumptions: the terminal convention by which a literal asterisk typed into a filter meant
 * "clear this value" has no analogue here either. {@code app/cbl/COCRDSLC.cbl}, 887 lines, states it
 * in its own comment at line 614 and implements it at lines 615 to 627, substituting low values when
 * the operator typed an asterisk or left the field blank, and the update program does the same. That
 * is an input affordance of a screen with no keyboard-clearing key, and it runs in the opposite
 * direction from the outbound asterisk that marks a blank field on a refusal, which belongs to the
 * shared error model. Neither appears in this record.
 *
 * <p>Refactoring Rationale: {@code toString()} IS overridden to withhold the embossed name and the
 * expiry date and to mask the account identifier, and an earlier revision of this paragraph recorded the
 * opposite decision. It rejected the override on the ground that the package charter admits no
 * transformation in these records and assigns suppression to the mapper alone, and it accepted the
 * consequence knowingly -- that an incidental stringification, in a log line, an assertion message or an
 * exception detail, prints the cardholder's name and the full eleven-digit account identifier. That
 * reasoning conflated two different things. The charter's fifth rule governs the DATA PATH: it refuses a
 * value normalised in a constructor and a value rendered behind an accessor, because either would move a
 * disclosure decision out of the one class that audits it. A string form is on neither path -- it produces
 * no component value and reaches no wire, and every accessor still returns exactly what the mapper
 * supplied -- so the mapper remains the single place that decides what a response discloses. The accepted
 * cost was also mis-sized: the values reaching these components HAVE passed the mapper, but what the
 * mapper decided was what a RESPONSE may carry, and it never had any view on what a log line may carry.
 * A record's generated rendering prints EVERY component, so the cost was unbounded rather than accepted.
 * The sibling records in {@code com.carddemo.auth.dto} reached the same conclusion for the same reason,
 * and this record now matches them.
 *
 * <p>Assumptions: the selector and the masked card rendering print in FULL, because neither discloses
 * anything -- one is opaque without the deployment key and the other is already masked -- and printing the
 * selector is what keeps the rendering useful, since it is the value that correlates the line with a
 * request. The account number prints MASKED rather than withheld, matching {@link CardSummary}: it is an
 * identifier rather than a secret, and masking leaves enough to correlate two lines about one account
 * while disclosing no complete locator. The embossed name and the expiry date are replaced by a fixed
 * marker rather than by a truncation, because a truncated name is still a name and a partial date still
 * narrows a cardholder.
 *
 * <p>Trade-offs: the accepted cost is that a diagnostic no longer shows a value a developer may have
 * wanted, so a test asserting on a rendering has to read the accessor instead. That is a smaller cost
 * than the alternative, which was that every such rendering disclosed the cardholder.

 *
 * <h2>What the test channel has to assert</h2>
 *
 * <p>Assumptions: no executable oracle exists for any path this shape serves, so the obligations
 * below fall on assertions against cited lines rather than against a reference run. The three card
 * programs behind this context are online programs written against the transaction monitor's
 * command-level interface, and {@code tests/README.md} records at its lines 83 to 85 that such
 * programs cannot run end to end without a CICS runtime, which the runner does not have. Every width,
 * order and admissible value stated here is instead verifiable by reading the cited file at the cited
 * line, which is why the citations are exact.
 *
 * <p>Assumptions: nine assertions are required of whatever tests this record. That it declares exactly
 * these seven components in exactly this order, matching the properties the contract lists and, for the
 * five stored ones, the columns the owning migration declares. That it exposes no accessor able to
 * return the card number in full and none for the verification value, which are the two assertions that
 * would fail first if either exclusion documented above were ever undone. That a full-width masked
 * rendering is accepted while a raw sixteen-digit number is REFUSED AT CONSTRUCTION, which is the
 * assertion that holds the disclosure boundary. That the refusal does not quote the value it refused.
 * That the diagnostic rendering carries neither the embossed name nor the unmasked account number.
 * That a selector of the sealed length and alphabet is accepted while a raw number is refused. That an
 * eleven-digit account identifier is
 * accepted while ten digits, twelve digits and eleven characters including a non-digit are each
 * refused, which pins the bound at the copybook width rather than one position either side of it.
 * That the status admits {@code 'Y'} and {@code 'N'} and refuses everything else, meaning a null, an
 * empty value, a blank, the lower-case forms and the two letters together. And that the token refuses
 * both a null and a negative value while accepting zero, since zero is the value a never-updated card
 * carries and a bound that excluded it would refuse every freshly loaded row.
 *
 * <h2>Authoring notes for this file</h2>
 *
 * <p>Alternatives Considered: an annotation processor to generate the accessors, and a mapping
 * generator to produce the translation into this shape. Both are rejected, for different reasons.
 * Generated accessors cannot carry the documentation Rule 1 requires at its line 15, so the members
 * whose provenance most needs stating -- a width read from a copybook, a domain restricted to two
 * characters -- would be the very members with nowhere to state it, while a Java 21 record with
 * explicit components gives the same brevity and remains documentable. A mapping generator is
 * rejected because this mapping is not mechanical: it drops padding, renders one value masked on every
 * response, suppresses another value entirely, converts between a numeric column and digit
 * characters, and carries a baseline field name into a member spelled differently. Each of those needs
 * its justification at the line that performs it, and a generated method body offers no such line.
 *
 * <p>Trade-offs: the cost is hand-written mapping and hand-written component documentation, which is
 * more to read and more to keep true as the contract moves. What it buys is that every decision above
 * is visible where it is taken and reviewable there, which is the standard Rule 1's validation gate
 * sets at its line 43, where a docstring and a labelled rationale together are what a change needs to
 * pass review.
 *
 * <p>Assumptions: this record declares exactly two methods, and the boundary between what they do and
 * what they deliberately do not is worth stating. The compact constructor checks ONE invariant, that the
 * card-number rendering is masked, and checks nothing else: every other constraint on every other
 * component is evaluated by the framework on the boundary this shape crosses, the charter assigns
 * imperative checking to the service layer, and a constructor that validated the whole shape would make
 * this type unusable from a test that deliberately builds an otherwise-invalid instance to assert that
 * validation reports it. The one invariant is checked there because it is the only one whose violation
 * would be a DISCLOSURE rather than a refused request, and because a response is never validated. The
 * second method is the diagnostic rendering, argued in its own paragraph above.
 *
 * <p>Assumptions: where this record and
 * {@code services/card-service/src/main/resources/openapi/card-api.yaml} could be read as disagreeing
 * about the shape, the contract decides and this file is brought to it. Nothing in the build would
 * report such a disagreement: the two artifacts are authored separately and each derives
 * independently from {@code app/cpy/CVACT02Y.cpy}, so a divergence leaves both sides internally
 * consistent, compiles cleanly, and surfaces only as a client generated from the published contract
 * failing against a running service. One concrete instance was resolved that way while this file was
 * authored, and it is recorded because the resolution is not visible from the result: the masked
 * rendering is carried by a member named {@code displayCardNumber}, not by a member named for the card
 * number, and this record therefore declares no member that could hold that number in full.
 *
 * @param key the opaque, deployment-keyed selector this card is addressed by, exactly
 *     {@value #SELECTOR_LENGTH} URL-safe characters in the form {@link SealedSelector} mints. Required
 *     on every response, and carried here as well as on a list row because a caller that reached this
 *     card through {@code lookupCard} never saw a list and would otherwise hold no value with which to
 *     issue the update. It carries no card number, cannot be constructed by a client, and is STABLE for
 *     one card so a route built from it is bookmarkable: the card number was removed from every path and
 *     query in this contract because the load balancer writes the request line into a durable
 *     access-log object before any application code can redact it

 * @param displayCardNumber the card this record describes, rendered for a person as its last four
 *     digits behind a masking prefix and never as an address -- it identifies no row and is accepted
 *     as an input nowhere. Exactly 16 characters, the width of {@code CARD-NUM PIC X(16)} at
 *     {@code app/cpy/CVACT02Y.cpy} line 5, because the shared masker replaces the leading positions
 *     rather than removing them and the rendering is therefore as wide as the value it hides. Required
 *     on every response, and held to exactly twelve mask characters followed by four digits by the
 *     shape {@code com.carddemo.common.security.MaskedCardNumber} declares, so a raw number cannot
 *     satisfy it

 * @param accountId the account this card belongs to, as exactly eleven digit characters and never as
 *     a JSON number, from {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy} line 6 and
 *     stored as {@code account_id}. Required, and never masked, which is why it is the one identifier
 *     on this record that carries the full digits-only constraint. Leading zeros are data and are
 *     preserved
 * @param embossedName the name embossed on the card, at most 50 characters, from
 *     {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code app/cpy/CVACT02Y.cpy} line 8 and stored as
 *     {@code embossed_name}. Restricted to letters and spaces, which is the domain the baseline itself
 *     tests: {@code app/cbl/COCRDUPC.cbl} line 823 copies the submitted name to a work field, lines
 *     824 to 826 remove every letter of the 52-character upper and lower alphabet its lines 255 to 259
 *     declare, and line 828 concludes that anything remaining means a non-letter was present, the
 *     refusal reading {@code 'Card name can only contain alphabets and spaces'} at lines 183 to 184.
 *     The declared width is a maximum rather than an exact length, because the trailing blanks that
 *     fill the stored field are record padding and not part of anybody's name. The blank-value refusal
 *     at lines 811 to 813 of that program is a request-side rule and is deliberately not restated as a
 *     constraint on this response component. Values arrive upper-cased, as lines 1499 to 1501 of that
 *     program upper-case the stored name in place before comparing it, so a caller should expect the
 *     value it reads back to differ in case from the value it sent
 * @param expirationDate the day this card expires, as exactly ten characters in the ISO order year,
 *     month, day separated by hyphens, from {@code CARD-EXPIRAION-DATE PIC X(10)} at
 *     {@code app/cpy/CVACT02Y.cpy} line 9 and stored as {@code expiration_date}. Required. The
 *     constraint establishes the shape of the ten characters and not that they name a real day, for
 *     the reason recorded above
 * @param activeStatus whether the card is active, exactly one upper-case character, {@code 'Y'} or
 *     {@code 'N'} and nothing else, from {@code CARD-ACTIVE-STATUS PIC X(01)} at
 *     {@code app/cpy/CVACT02Y.cpy} line 10 and stored as {@code active_status} under a check
 *     constraint admitting the same pair. Required, and the domain is the one
 *     {@code app/cbl/COCRDUPC.cbl} line 91 declares
 * @param version the server-owned concurrency token for this card, a non-negative integer mirroring
 *     the {@code version} column declared {@code NOT NULL DEFAULT 0}, so a card never updated since it
 *     was loaded carries zero. Required on every response. An update must present the value it last
 *     read here or be refused with 409; a caller never chooses it and never increments it
 */
public record CardDetail(
        // WHY : Assumptions: the bound is an EXACT length and the URL-safe base64 alphabet, which is
        //       what SealedSelector emits for a sixteen-character value. A looser bound would let a raw
        //       card number satisfy this member, because a run of digits IS valid base64 -- the length
        //       is the only thing separating the two. The alphabet is read from SealedSelector rather
        //       than written out here, so the constraint cannot drift from the sealer that mints it.
        @NotNull @Size(min = SELECTOR_LENGTH, max = SELECTOR_LENGTH)
        @Pattern(regexp = SealedSelector.SEALED_SHAPE) String key,
        // WHY : Assumptions: no width bound accompanies the pattern. MaskedCardNumber.DOMAIN fixes
        //       twelve mask characters followed by four digits, so it already admits exactly sixteen
        //       characters and a @Size would restate what the pattern decides.
        @NotNull @Pattern(regexp = MaskedCardNumber.DOMAIN) String displayCardNumber,

        @NotNull @Pattern(regexp = ACCOUNT_ID_DOMAIN) String accountId,
        @NotNull @Size(max = EMBOSSED_NAME_WIDTH) @Pattern(regexp = EMBOSSED_NAME_DOMAIN)
        String embossedName,
        @NotNull @Pattern(regexp = EXPIRATION_DATE_DOMAIN) String expirationDate,
        @NotNull @Pattern(regexp = ACTIVE_STATUS_DOMAIN) String activeStatus,
        @NotNull @Min(INITIAL_VERSION) Integer version) {

    /**
     * The number of characters a sealed card selector occupies.
     *
     * <p>Assumptions: 59 is what {@link SealedSelector#sealedLengthFor(int)} returns for the
     * sixteen-character card number, being a twelve-byte synthetic vector, sixteen bytes of ciphertext
     * and a sixteen-byte authentication tag rendered in unpadded URL-safe base64. It is written here as
     * a literal because a constraint annotation requires a compile-time constant, and
     * {@code SealedSelectorTest} asserts that method against this same figure so the literal cannot
     * drift from the arithmetic.
     *
     * <p>Assumptions: the bound is stated as an exact length rather than a maximum, and that is
     * load-bearing. A run of digits is itself valid URL-safe base64, so the alphabet alone would admit a
     * raw sixteen-digit card number in this member; the length is the only thing that separates a
     * selector from the value it stands for.
     */
    private static final int SELECTOR_LENGTH = 59;

    /**
     * The number of characters the baseline declares for the embossed name.
     *
     * <p>Assumptions: 50 is read from {@code CARD-EMBOSSED-NAME PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy} line 8, and {@code CRDNAMEI PIC X(50)} at
     * {@code app/cpy-bms/COCRDSL.CPY} line 72 states the same width independently, so the figure has
     * two sources and neither is an inference. The stored column is declared with the same maximum.
     */
    private static final int EMBOSSED_NAME_WIDTH = 50;

    /**
     * The expression the account identifier has to match in full.
     *
     * <p>Assumptions: eleven positions of digits comes from {@code CARD-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT02Y.cpy} line 6. No anchor is written because none is needed: a pattern
     * constraint is satisfied only when the whole value matches, so a counted class of exactly eleven
     * admits eleven characters and refuses both ten and twelve without one.
     *
     * <p>Trade-offs: this one expression carries the width as well as the character set, where the
     * published contract states the bound twice, once as a length pair and once as a pattern. The
     * contract states it twice on purpose, because a JSON Schema consumer reads the length keyword
     * without evaluating the expression. Repeating it here as a separate size constraint would instead
     * make one out-of-domain value report two violations naming the same member, so the width is
     * enforced by this expression rather than beside it.
     */
    private static final String ACCOUNT_ID_DOMAIN = "[0-9]{11}";

    /**
     * The expression the embossed name has to match in full.
     *
     * <p>Assumptions: letters and the space are the character set the baseline itself tests, at
     * {@code app/cbl/COCRDUPC.cbl} lines 824 to 826, by removing every letter of the 52-character
     * alphabet its lines 255 to 259 declare in both cases and concluding at line 828 that anything
     * remaining was not a letter. Both cases are admitted here for the same reason the baseline
     * declares both: the stored value is upper-cased in place before comparison, at lines 1499 to
     * 1501, so a value read back is upper case, while a value that has not yet passed that path is
     * not, and one expression has to accept the same name from either side.
     *
     * <p>Assumptions: the width is carried by the size constraint beside this one rather than by this
     * expression, because a repetition of one or more is the only form that expresses the character
     * set without fixing the length, and the length here is a maximum rather than an exact count. The
     * repetition also carries the minimum of one that the contract declares, which is why no separate
     * lower bound is written.
     */
    private static final String EMBOSSED_NAME_DOMAIN = "[A-Za-z ]+";

    /**
     * The expression the expiry date has to match in full.
     *
     * <p>Assumptions: four digits, a hyphen, two digits, a hyphen and two digits is the layout proven
     * three ways on this type, and the counted classes are written with explicit digit ranges rather
     * than with a shorthand class so that the expression admits ASCII digits alone; a shorthand digit
     * class matches decimal digits of other scripts under some engines, which would let a value pass
     * this constraint that the stored {@code DATE} column could not accept.
     *
     * <p>Assumptions: the two separators are ASCII hyphen-minus, the character the baseline writes as
     * a literal between the parts at {@code app/cbl/COCRDUPC.cbl} lines 1468 and 1470. This expression
     * fixes the ten-character width as a consequence of counting every position, which is why no size
     * constraint accompanies it.
     */
    private static final String EXPIRATION_DATE_DOMAIN = "[0-9]{4}-[0-9]{2}-[0-9]{2}";

    /**
     * The expression the active status has to match in full.
     *
     * <p>Assumptions: the two admitted characters come from {@code 88 FLG-YES-NO-VALID VALUES 'Y',
     * 'N'} at {@code app/cbl/COCRDUPC.cbl} line 91. They are written as an explicit two-character
     * class rather than as a wider alternation so that the domain cannot quietly widen, and the class
     * is case-sensitive because the baseline condition compares bytes against the literals it was
     * declared with. Because the class matches exactly one character it also carries the length of one
     * that the contract declares, so no size constraint accompanies it.
     */
    private static final String ACTIVE_STATUS_DOMAIN = "[YN]";

    /**
     * The concurrency token a card carries before it has ever been updated.
     *
     * <p>Assumptions: zero is both the value the owning migration gives the {@code version} column as
     * its default and the minimum the published contract declares for the property, so one constant
     * serves as the lower bound of the constraint and as the documented starting value. It is named
     * for the state it describes rather than for the number, because a bound written as a bare zero
     * would not say why zero is admissible.
     *
     * <p>Assumptions: the bound is inclusive, and that is the load-bearing half of this constant. A
     * lower bound that excluded zero would refuse every freshly loaded row, which is the majority of
     * the table immediately after migration.
     */
    private static final int INITIAL_VERSION = 0;

    /**
     * The placeholder that stands in for a component this record refuses to render.
     *
     * <p>Assumptions: the literal matches the one {@code com.carddemo.card.dto.CardUpdateRequest} and the
     * response records in {@code com.carddemo.auth.dto} use, so a log store holding lines from several
     * migrated shapes shows one placeholder vocabulary rather than one per package. The constant is named
     * for the class of data it hides rather than for the literal, because a reader of this file needs to
     * know which components are withheld and a reader of a log line needs only to know that some were.</p>
     *
     * <p>Alternatives Considered: the {@code <withheld>} marker the authorization payloads and
     * {@code CsvAuthCodec} use. Rejected here for coherence rather than on merit: those are message-wire
     * shapes in another bounded context, and the two card response records and the three auth ones already
     * share this literal, so adopting a second marker inside this package would leave one package printing
     * two vocabularies.</p>
     */
    private static final String REDACTED_PERSONAL = "REDACTED";

    /**
     * Confirms the selector is sealed and the card-number rendering is masked before any instance exists.
     *
     * <p>Refactoring Rationale: the checks live here rather than in the mapper so that no detail can
     * exist carrying a raw key or an unmasked number, whichever path constructed it. They call
     * {@link SealedSelector#hasSealedShape(String)} and {@link MaskedCardNumber#require(String, String)}
     * rather than restating either shape, which is the same pair {@link CardSummary} and
     * {@code com.carddemo.authorization.dto.PendingAuthRowView} apply, so one definition of a sealed
     * selector and one of a masked rendering serve every response type.</p>
     *
     * <p>Assumptions: neither refusal quotes the rejected value. The value most likely to be passed here
     * by mistake is a raw card number, so echoing it would put the number this contract removed from
     * every request line into an exception message and from there into a log.</p>
     *
     * @throws NullPointerException if {@code key} or {@code displayCardNumber} is {@code null}
     * @throws IllegalArgumentException if {@code key} is not a sealed selector, or if
     *     {@code displayCardNumber} is not a full-width masked rendering
     */
    public CardDetail {
        Objects.requireNonNull(key, "key is required");
        if (!SealedSelector.hasSealedShape(key)) {
            throw new IllegalArgumentException(
                    "key must be a sealed selector token, so that a card selector cannot be forged"
                            + " and a raw card number cannot be presented as one");
        }
        MaskedCardNumber.require("displayCardNumber", displayCardNumber);
    }


    /**
     * Renders this shape for diagnostics with the cardholder's name and expiry date withheld.
     *
     * <p>Refactoring Rationale: the reasoning for overriding at all, and for why the package charter does
     * not forbid it, is recorded on the type above; this is the mechanism. An incidental stringification
     * used to print the embossed name as stored and the account identifier in full, which is a personal
     * name joined to an account locator in one line -- and a log store is the destination neither the
     * mapper's masking nor the shared advice's path masking reaches.</p>
     *
     * <p>Trade-offs: which components print in full is the substance of this method. {@code key} prints
     * because it is opaque without the deployment key and is the value that correlates this line with a
     * request. {@code displayCardNumber} prints because it arrives ALREADY MASKED -- the constructor above
     * admits no other form into this shape. {@code activeStatus} prints because it is a two-valued flag
     * carrying no personal content, and {@code version} prints because a concurrency conflict cannot be
     * traced without it. {@code accountId} prints masked, matching {@link CardSummary}.
     * {@code expirationDate} is withheld alongside the embossed name: on its own it identifies nobody,
     * but printed beside a partial card number it completes two of the three components of a card
     * credential, and it has no diagnostic use here that the correlating selector does not already
     * serve.</p>
     *
     * <p>Assumptions: only the string form is narrowed. Component equality and hashing are untouched, the
     * accessors return exactly what the mapper supplied, and the serialised body is unchanged -- the
     * framework writes a response from the accessors and never from this method, so no published property
     * loses a value. The exposure being closed is incidental stringification, not the deliberate act of
     * reading a component the contract publishes.</p>
     *
     * @return a single-line description of this record naming every component in contract order, in which
     *     the account identifier is masked and the embossed name and the expiry date are each represented
     *     by a placeholder and never rendered
     */
    @Override
    public String toString() {
        // Assumptions: the generated form's shape is reproduced deliberately, component order included,
        //   so that a reader who knows what a record prints is not led to think some other type produced
        //   this line. Only the withheld and masked values depart from it.
        return "CardDetail[key=" + key
                + ", displayCardNumber=" + displayCardNumber
                + ", accountId=" + CardNumberMasker.mask(accountId)
                + ", embossedName=" + REDACTED_PERSONAL
                + ", expirationDate=" + REDACTED_PERSONAL
                + ", activeStatus=" + activeStatus
                + ", version=" + version
                + "]";

    }
}
