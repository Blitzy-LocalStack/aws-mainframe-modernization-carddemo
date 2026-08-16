package com.carddemo.card.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of the card update request, carrying the attributes a caller may change together with the
 * concurrency token last read for the card being changed.
 *
 * <p>This shape serves one operation, {@code PUT /api/v1/cards/{cardKey}}. The card it changes is
 * named in the request path -- by the opaque selector the read returned, never by its number -- and is
 * not repeated here, so the body carries three editable attributes

 * and the token, and nothing else. A submission that breaks one of the constraints declared on the
 * components below is refused with 400 and an entry in the per-field array of
 * {@code com.carddemo.common.error.ApiError}, which owns that shape for every context and is never
 * re-declared here. A token that no longer matches the stored one is refused with 409, and the
 * comparison that decides it reads stored state, so it belongs to {@code com.carddemo.card.service}
 * and not to this carrier.</p>
 *
 * <h2>Why these four members and no others</h2>
 *
 * <p>Assumptions: the count is settled by the baseline rather than chosen.  * {@code app/cbl/COCRDUPC.cbl} declares six validation-flag triads across lines 57 to 80, and the
 * first two of them are filters rather than payload: {@code FLG-ACCTFILTER-NOT-OK} and its two
 * companions at lines 58 to 60 qualify the account the operator searched by, and
 * {@code FLG-CARDFILTER-NOT-OK} and its companions at lines 62 to 64 qualify the card number, both of
 * which are lookup identity that a REST path carries instead. That leaves four editable-field triads,
 * for the name at lines 66 to 68, the status at 70 to 72, the expiry month at 74 to 76 and the expiry
 * year at 78 to 80. Two independent counts agree with that reading: there are six edit paragraphs and
 * no seventh, the last being {@code 1260-EDIT-EXPIRY-YEAR} at line 913 with a search for
 * {@code 1270-EDIT} returning no match at all; and there are six blank-marker write sites, at lines
 * 1249, 1259, 1270, 1281, 1294 and 1305, with no seventh. The four editable-field triads reach this
 * wire shape as three components because the contract carries the whole expiry as one separated date,
 * which is the very form the baseline assembles for storage at lines 1467 to 1474; the fourth
 * component is the concurrency token. Four either way.</p>
 *
 * <p>Assumptions: where this record and the published contract disagree about a shape, the contract
 * decides and this file is brought to it. Both artifacts are authored separately and
 * each derives independently from {@code app/cpy/CVACT02Y.cpy} and {@code app/cpy-bms/COCRDUP.CPY}, so
 * a divergence leaves both sides internally consistent and compiles cleanly: nothing in the build
 * reports it, and the contract test at
 * {@code services/card-service/src/test/java/com/carddemo/card/config/CardApiContractTest.java}
 * asserts the contract's own operations, authorities, path parameter and page envelope rather than
 * these members. A disagreement would therefore surface only as a client generated from the published
 * document failing against a running service, which is why one side has to be authoritative in
 * advance. The {@code CardUpdateRequest} schema in
 * {@code services/card-service/src/main/resources/openapi/card-api.yaml} is that side, and the charter
 * of this package fixes the same precedence. Two of its readings are followed here in preference to any
 * other: the expiry travels as TWO members, {@code expirationMonth} and {@code expirationYear}, and the
 * concurrency token travels in this body.</p>
 *
 * <p>Refactoring Rationale: the expiry is two members rather than one assembled date, and the baseline
 * settles it rather than taste. {@code app/cpy-bms/COCRDUP.CPY} declares {@code EXPMONI PIC X(2)} at
 * L84 and {@code EXPYEARI PIC X(4)} at L90 as separate map fields, and
 * {@code app/cbl/COCRDUPC.cbl} edits each behind its OWN validation flag -- the
 * {@code FLG-CARDEXPMON-*} set at L73 to L76 and the {@code FLG-CARDEXPYEAR-*} set at L77 to L80. Two
 * independent flags produce two independent field errors and two independently highlighted fields, so
 * one assembled member would collapse a pair of refusals the screen reports separately and no per-field
 * error could name which half was wrong.</p>
 *
 * <p>Alternatives Considered: a class with generated accessors, produced by an annotation processor,
 * was evaluated against a Java 21 record with explicit members. It is rejected because
 * a generated accessor has no source line able to carry documentation, so the members whose provenance
 * most needs stating -- a width taken from a copybook, a domain restricted to two values, a year window
 * taken from a condition name -- would be exactly the members with nowhere to state it. The record
 * gives the same brevity and stays documentable, and no code generator participates in this file.</p>
 *
 * <p>Alternatives Considered: the constraints below are declarative, and the alternative was imperative
 * checking inside a compact constructor. Bean Validation is genuinely available here
 * rather than conditionally: {@code services/card-service/pom.xml} declares
 * {@code spring-boot-starter-validation} at line 202 with no optional flag. The constructor route is
 * rejected on two independent grounds. It reports by throwing, so it stops at the first fault and
 * cannot describe a submission in which several members are bad, which is precisely what the per-field
 * array exists to carry; and the charter of this package makes a record here an inert carrier that
 * holds what it was constructed with and transforms none of it, which a normalising or throwing
 * constructor would contradict. This record therefore declares no compact constructor, throws nothing
 * and documents no exception.</p>
 *
 * <p>Trade-offs: what the declarative route gives up is real and is named here rather than discovered
 * later. Annotations express one member at a time, so three things the baseline does
 * are deliberately left to {@code com.carddemo.card.service}: the first-wins selection of a single
 * aggregate sentence, which the baseline latches behind {@code IF WS-RETURN-MSG-OFF}; any rule that
 * compares one member against another or against stored state; and the calendar-accurate part of the
 * date check, where month length and the leap year are decided by
 * {@code com.carddemo.common.validation.DateEditValidator} rather than by a regular expression. The
 * constraints here settle the shape a caller is held to, which is the part a caller can be told in
 * advance from the published document.</p>
 *
 * <p>Assumptions: the three editable members are character strings and none of them is a numeric type.
 * The screen declares every one of its expiry inputs as character and none as numeric
 * -- {@code EXPMONI PIC X(2)} at {@code app/cpy-bms/COCRDUP.CPY:84}, {@code EXPYEARI PIC X(4)} at line
 * 90 and {@code EXPDAYI PIC X(2)} at line 96 -- and the baseline then validates the month and the year
 * by a class condition evaluated over a numeric {@code REDEFINES} of the character field, at
 * {@code app/cbl/COCRDUPC.cbl:896} with 898 for the month and at 932 with 934 for the year, rather
 * than by reading a numeric field. The reason is the character wire form and the leading zero that
 * {@code 01} through {@code 09} carry as data, which a numeric member would drop; it is deliberately
 * not an exactness argument, because a two-digit and a four-digit value are both held exactly. The
 * concurrency token is the one whole-number member, because the contract declares it as an integer and
 * because it is an opaque counter with no copybook picture and no leading-zero contract; the
 * digits-only-string rule this package applies to identifiers does not reach it, as the token
 * identifies nothing.</p>
 *
 * <h2>Six shapes deliberately absent</h2>
 *
 * <p>Assumptions: no representation of the card verification value appears here, in full, masked,
 * truncated or implied by a length. This preserves an absence the baseline already had
 * rather than withdrawing something it showed. A case-insensitive search for that value returns no
 * match in any of the six presentation files of this context, being
 * {@code app/cpy-bms/COCRDUP.CPY}, {@code app/cpy-bms/COCRDSL.CPY}, {@code app/cpy-bms/COCRDLI.CPY},
 * {@code app/bms/COCRDUP.bms}, {@code app/bms/COCRDSL.bms} and {@code app/bms/COCRDLI.bms}, so no
 * screen in the set displayed it or accepted it. In the update program the new-value field
 * {@code CCUP-NEW-CVV-CD PIC X(3)} is declared at {@code app/cbl/COCRDUPC.cbl:306}, its enclosing
 * group is cleared by {@code INITIALIZE} at line 586, and the field then appears only as the source of
 * one move at line 1464; a search for a move whose target is that field returns nothing, so no value
 * was ever written into it. What the target adds is encryption of the stored column, and it adds
 * nothing to the wire. A masked member was considered and is not the safer half-measure it can look
 * like: it would still publish that the value exists, how wide it is and which member to send, so
 * total absence is the only rendering that discloses none of those three.</p>
 *
 * <p>Refactoring Rationale: there is no standalone day member, and the baseline is the reason.
 * The program says so in its own words at {@code app/cbl/COCRDUPC.cbl:1119} and 1120,
 * where the comment above the redisplay block records that old values are moved to the non-display
 * fields the user is not allowed to change. Line 1122, which would move the newly entered day to the
 * screen, is commented out, and the active statement on line 1123 moves the pre-edit day instead. The
 * branch that decides it is the one taken when changes have been made: the four editable fields are
 * redisplayed from their new values at lines 1114 to 1117, while the day alone comes from
 * {@code CCUP-OLD-EXPDAY} at line 1123, and across all four arms of that {@code EVALUATE}, at lines
 * 1100 to 1106, 1107 to 1112, 1113 to 1123 and 1124 to 1129, the day is never redisplayed from an
 * entered value. Line 1285 then renders it non-display unconditionally, occupying exactly the slot
 * between the status pair at lines 1274 to 1283 and the month pair at 1287 to 1296 where a seventh
 * validation pair would have sat. Two further readings agree: no {@code 1270-EDIT-EXPIRY-DAY}
 * paragraph exists, so the day is never validated, and the four attribute statements that would set
 * its highlight, at lines 1178, 1185, 1197 and 1205, are commented out; and the detail screen has no
 * day field at all, {@code app/cpy-bms/COCRDSL.CPY} declaring only {@code EXPMONI} at line 84 and
 * {@code EXPYEARI} at line 90.</p>
 *
 * <p>Assumptions: the one fact that runs the other way is stated rather than omitted. Line 621 does
 * move the day input into the new-value group, and the record written at lines 1467 to 1474 does compose
 * a day into the stored date; because the field is non-display and is always redisplayed from the
 * pre-edit snapshot, the day written equals the day read. The baseline holds that invariant emergently,
 * as a consequence of how the screen is painted.</p>
 *
 * <p>Refactoring Rationale: this body therefore carries the MONTH and the YEAR as two members and
 * carries no day at all, rather than carrying the whole ten-character date. An earlier revision carried
 * the whole date, on the reasoning that ten separated characters are the same form line 1467 assembles
 * and that the day-equals-stored-day invariant could be enforced against stored state in
 * {@code com.carddemo.card.service}. Two things were wrong with that. The first is a matter of fact:
 * that service layer does not exist in this context yet, so the invariant was enforced nowhere and the
 * body published a day a caller could set to any value it liked -- which is not a stricter reading of the
 * baseline, it is a capability the baseline never offered, on a field the baseline deliberately rendered
 * non-display. The second is a matter of shape: an invariant that a submitted value must equal the stored
 * value means the submitted value carries no information, and a member that carries no information should
 * not be in the request. Removing the day removes the need to enforce anything about it, which is a
 * smaller and more durable answer than enforcing it correctly.</p>
 *
 * <p>Alternatives Considered: keeping the whole date and refusing any submission whose day differs from
 * the stored one. Rejected because it answers a request that should never have been expressible with a
 * refusal, so every caller has to first read the card in order to learn the one day value its update will
 * be allowed to carry -- and a caller that got it wrong would receive a field error naming a field the
 * screen it is modelled on did not have. Alternatives Considered: keeping the whole date and silently
 * overwriting the submitted day with the stored one. Rejected because it accepts a value and then ignores
 * it, which is the least discoverable of the three behaviours.</p>
 *
 * <p>Trade-offs: the accepted cost is that this request no longer has the same shape as the response,
 * which carries the whole date, so a caller cannot round-trip a detail response straight back as an
 * update. That asymmetry is deliberate and matches the screens: {@code app/cpy-bms/COCRDSL.CPY} declares
 * {@code EXPMONI} at line 84 and {@code EXPYEARI} at line 90 and no day field, while the stored record
 * holds a whole date. The response reports what is stored and the request carries what is editable, and
 * those were never the same set of fields.</p>
 *
 * <p>Assumptions: the day the stored date keeps is therefore supplied by whatever applies the update,
 * from the row's own current value, and no caller can influence it. The two-member form makes that
 * structurally true rather than dependent on a check. This is a documented divergence from the
 * baseline's single ten-character stored field, registered as {@code D-CARD-EXPIRY-MONTH-YEAR} in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Refactoring Rationale: no member asks whether this is a confirmation, a second attempt or a
 * repeat turn. The baseline's two-step save is turn state, not payload:
 * {@code app/cbl/COCRDUPC.cbl:276} and 277 declare {@code CCUP-CHANGE-ACTION PIC X(1)} initialised to
 * low values, with the condition set at lines 278 to 290 including
 * {@code CCUP-CHANGES-NOT-OK VALUE 'E'} at line 285, {@code CCUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'}
 * at 286 and {@code CCUP-CHANGES-OKAYED-AND-DONE VALUE 'C'} at 287, and the waiting step surfaces to
 * the operator as the message {@code Changes validated.Press F5 to save} at lines 166 and 167. The
 * function key was a keystroke and never a field in the data the screen sent, so the two steps
 * collapse into one request that carries its own confirmation by carrying every member. The same
 * reading explains the marker gates, which are turn-scoped in two different ways: lines 1248 and 1258
 * gate on the repeat-entry discriminator of the shared session structure, while lines 1269, 1280, 1293
 * and 1304 gate on the change-action condition. A stateless handler remembers neither, and the
 * repeat-entry discriminator is eliminated across this migration rather than ported.</p>
 *
 * <p>Assumptions: the convention by which a typed asterisk cleared a field is not carried into this
 * body. Inbound, a literal asterisk meant clear: {@code app/cbl/COCRDUPC.cbl:588}
 * carries a comment saying exactly that, and lines 589 to 595 replace the account filter with low
 * values when the operator typed one, with {@code app/cbl/COCRDSLC.cbl} doing the same under the same
 * comment at line 614, for the account at lines 615 to 620 and the card at 622 to 627. That is a
 * terminal affordance with no equivalent in a request body: a caller omits a member or sends it empty,
 * and this body is required whole, so there is nothing for a sentinel character to mean. This is
 * strictly distinct from the outbound asterisk written at lines 1249, 1259, 1270, 1281, 1294 and 1305,
 * which marks a field left empty on the screen that comes back. Same character, opposite direction,
 * different mechanism, and conflating the two would mislead every later reader; the outbound marker
 * belongs to the response and is owned by
 * {@code com.carddemo.common.validation.FieldValidationFlag}.</p>
 *
 * <p>Alternatives Considered: neither the card number nor the account identifier is a member here, and
 * echoing the card number back for confirmation was the alternative. It is rejected
 * because the number is already the path variable of the operation, so carrying it in the body as well
 * would create two sources of truth for one identity and leave open which of them wins when they
 * differ. Taking it from the path alone keeps the request self-describing and independently
 * authorizable. Neither identifier is editable in any case, and the baseline agrees by omitting both
 * from the fields its change comparison tests, at {@code app/cbl/COCRDUPC.cbl:1503} and following.</p>
 *
 * <p>Assumptions: the concurrency token is a member of this body because the contract puts it here.
 * The baseline already implements optimistic concurrency, and this is the target's way
 * of saying the same thing: {@code DATA-WAS-CHANGED-BEFORE-UPDATE} at
 * {@code app/cbl/COCRDUPC.cbl:207} and 208 carries the refusal
 * {@code Record changed by some one else. Please review}, whose two-word spelling of the third word
 * and whose lack of a closing full stop are both carried unchanged under transformation rule T8 and
 * are cited from this program rather than from any other that happens to word it the same way, and the
 * guard at lines 1455 to 1457 branches away from the write when that condition is set. The lock
 * refusal is a separate sentence, {@code Could not lock record for update} at lines 205 to 206, and
 * the two are not interchangeable. The baseline's mechanism is a
 * before-image comparison across the pseudo-conversational gap, snapshotting the pre-edit record into
 * {@code CCUP-OLD-DETAILS} at lines 291 to 301 and comparing it against {@code CCUP-NEW-DETAILS} at
 * lines 303 to 313; a single token expresses that intent, and nothing is lost, because the record lock
 * was never held across the operator's thinking time. The contract requires the token among the members
 * of this schema and its update operation states that all four are required, and the charter of this
 * package both names the token as part of this payload and names a lower bound on it as a constraint
 * expected here. The caller echoes it unchanged from a previous response and never chooses or
 * increments it, and the comparison against stored state is the service's work, so no conflict logic
 * appears in this file.</p>
 *
 * <p>Assumptions: this record carries no monetary member and no timestamp member, and the absence is
 * recorded rather than left silent because silence in a shape derived from a financial record reads as
 * an oversight. The card layout declares exactly seven items and no more, at
 * {@code app/cpy/CVACT02Y.cpy} lines 5 to 11, being the card number, the account identifier, the
 * verification value, the embossed name, the expiry date, the active status and one filler, and there
 * is no amount and no timestamp among them. It follows that {@code com.carddemo.common.money.Money} is
 * imported by nothing here, and that no timestamp type is either: the expiry is a calendar date, and
 * the twenty-six-character stamp a refusal carries belongs to
 * {@code com.carddemo.common.error.ApiError} in the shared kernel.</p>
 *
 * <h2>How a refusal is reported</h2>
 *
 * <p>Refactoring Rationale: the baseline reports a bad submission on two channels at once, and the
 * target keeps both rather than merging them. Each field's own flag is set
 * unconditionally as its edit paragraph runs, so the flags accumulate and several can be set from one
 * submission; the single seventy-five-character aggregate sentence is instead latched first-wins,
 * behind {@code IF WS-RETURN-MSG-OFF}, at fifteen sites in {@code app/cbl/COCRDUPC.cbl} of which
 * twelve are field-scoped, at lines 730, 743, 773, 787, 816, 833, 855, 868, 888, 903, 921 and 939. The
 * fan-out between the two is therefore variable, one submission yielding several field errors and
 * exactly one sentence, so nothing here fixes a count. A blank field is a subset of an error rather
 * than a third peer state, because the blank arm of each paragraph sets two flags, the shared
 * input-error flag and the field's own blank condition. Transformation rule T2 of the migration plan
 * requires each of those shared shapes to be consumed from {@code com.carddemo.common} and declared
 * once: the problem shape and its per-field array from {@code com.carddemo.common.error.ApiError}, the
 * flag states and the blank marker from {@code com.carddemo.common.validation.FieldValidationFlag},
 * and the date edit rules from {@code com.carddemo.common.validation.DateEditValidator}. None of the
 * three is restated in this file. The precedent is the baseline's own single copybook include path,
 * which the repository records at {@code tests/README.md:540-542} as the convention that a layout is
 * never duplicated but kept single-sourced.</p>
 *
 * <p>Trade-offs: no golden-master oracle covers this operation, so the constraints below are held true
 * by transcription and by the tests that assert them rather than by a byte comparison. The three online
 * programs of this context cannot be run end to end without a CICS runtime, as the repository records
 * at {@code tests/README.md:83-85}, so the recorded outputs that hold the batch contexts to a byte
 * comparison have no counterpart here. That is a weaker guarantee, and it is accepted because standing
 * up a terminal region is outside this migration and would not be reproducible in the build.</p>
 *
 * @param embossedName the replacement name to emboss, at most fifty characters from
 *     {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code app/cpy/CVACT02Y.cpy:8} and
 *     {@code CRDNAMEI PIC X(50)} at {@code app/cpy-bms/COCRDUP.CPY:72}, restricted to letters of
 *     either case and the space character, and required to hold at least one character that is not a
 *     space
 * @param activeStatus the replacement active status, exactly one character from
 *     {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:10} and
 *     {@code CRDSTCDI PIC X(1)} at {@code app/cpy-bms/COCRDUP.CPY:78}, restricted to the two upper
 *     case values {@code Y} and {@code N} with no lower case member admitted
 * @param expirationMonth the replacement expiry month, exactly two digits with its leading zero
 *     present, in 01 through 12, from {@code 88 VALID-MONTH VALUES 1 THRU 12} at
 *     {@code app/cbl/COCRDUPC.cbl:95} read over the two-character field it redefines and tested at
 *     line 898
 * @param expirationYear the replacement expiry year, exactly four digits in 1950 through 2099, from
 *     {@code 88 VALID-YEAR VALUES 1950 THRU 2099} at {@code app/cbl/COCRDUPC.cbl:99} and tested at
 *     line 934. There is deliberately no day member: the day the stored date keeps is the day it
 *     already held, which is the invariant the baseline maintains by rendering its day field
 *     non-display
 * @param version the concurrency token last read for this card, a whole number no smaller than zero,
 *     echoed unchanged from a previous response and never chosen or incremented by the caller
 */
public record CardUpdateRequest(

        // WHY : Assumptions: the domain is letters of either case and the space character, and not
        //       blank overall. 1230-EDIT-NAME at app/cbl/COCRDUPC.cbl:806 sets its
        //       flag pessimistically to not-OK at line 808, refuses the field at lines 811 to 813
        //       when it equals low values or spaces or zeros, copies it at line 823, replaces every
        //       letter with a space at lines 824 to 826 using the fifty-two-character alphabet
        //       declared at lines 255 to 257 against the spaces at 258 to 259, and concludes at line
        //       828 that anything still remaining once the letters are gone was not a letter. Two
        //       constraints are needed rather than one, because the pattern alone admits a value made
        //       only of spaces, which lines 811 to 813 refuse; the width is stated separately again
        //       because the pattern bounds the character set and not the length.
        @Size(max = EMBOSSED_NAME_WIDTH)
        String embossedName,

        // WHY : Assumptions: the domain is the two upper case values and nothing else, and one
        //       sentence answers both ways of missing it. The value set
        //       88 FLG-YES-NO-VALID VALUES 'Y', 'N' at app/cbl/COCRDUPC.cbl:91 has no lower case
        //       member, the work field it tests is initialised to 'N' at lines 89 to 90, and
        //       1240-EDIT-CARDSTATUS copies the input at line 861 and tests it at 863. The same
        //       message is latched on both arms of that paragraph, at lines 855 to 856 for the blank
        //       case and at 868 to 869 for a value outside the set, so carrying one constant on both
        //       constraints reproduces the baseline exactly rather than approximating it.
        @Size(max = ACTIVE_STATUS_WIDTH)
        String activeStatus,

        // WHY : Assumptions: the month is two digits with its leading zero present, and the year is
        //       four digits inside a fixed window. For the month,
        //       1250-EDIT-EXPIRY-MON moves the input at app/cbl/COCRDUPC.cbl:896 and tests
        //       IF VALID-MONTH at line 898, which is a class condition over the PIC 9(2) REDEFINES
        //       declared at lines 93 to 94 with its value set at 95; there is no separate numeric
        //       test and no normalisation, notwithstanding the comment at line 894, so 01 through 12
        //       satisfy it while a digit padded with a space on either side does not, and 00 is
        //       already refused by the zeros sentinel of the blank test at lines 883 to 885. For the
        //       year, 1260-EDIT-EXPIRY-YEAR moves at line 932 and tests IF VALID-YEAR at 934 against
        //       88 VALID-YEAR VALUES 1950 THRU 2099 at line 99, over the PIC 9(4) REDEFINES at lines
        //       97 to 98. The separated form the contract requires is zero-padded by construction, so
        //       it preserves that acceptance exactly.
        // WHY : Assumptions: the two paragraphs are not written identically, and the difference is
        //       recorded as an observation about the baseline rather than as anything to act on.
        //       1250-EDIT-EXPIRY-MON sets its own flag to not-OK at
        //       app/cbl/COCRDUPC.cbl:880, before its blank test at lines 883 to 885, whereas
        //       1260-EDIT-EXPIRY-YEAR runs its blank test first at lines 916 to 918 and sets its flag
        //       afterwards at line 930; and the comment at line 928 reads the same as the month
        //       paragraph's at line 895 rather than naming the year window. Neither difference changes
        //       the set of values either paragraph accepts, which is why one shape above covers both
        //       parts and why nothing here is written to match either ordering.
        // WHY : Trade-offs: this component carries two parts that the baseline answers with two
        //       different sentences, so it deliberately carries no message of its own.
        //       A month outside the window is answered by
        //       'Card expiry month must be between 1 and 12' from app/cbl/COCRDUPC.cbl:197-198 and an
        //       unacceptable year by 'Invalid card expiry year' from lines 199 to 200; naming either
        //       one on a single constraint would misreport the other half of the faults. Choosing
        //       between them needs the parts separated, which is service-layer work, and the wording
        //       is carried unchanged in both cases even though the month sentence says between 1 and
        //       12 while the accepted form is zero-padded, because transformation rule T8 forbids
        //       rewording user-visible text to match an implementation detail.
        // WHY : Assumptions: the width is taken from the shared date type rather than written again
        //       here, so that the separated form has one definition across the migration.
        @Size(max = EXPIRATION_MONTH_WIDTH)
        String expirationMonth,

        // WHY : Assumptions: the year is a separate member from the month rather than the two being one
        //       composite, because the baseline answers them with two DIFFERENT sentences -- 'Card
        //       expiry month must be between 1 and 12' at app/cbl/COCRDUPC.cbl:197-198 and 'Invalid
        //       card expiry year' at lines 199 to 200 -- and a composite member could carry only one of
        //       them, misreporting the other half of the faults. Two members let each fault be answered
        //       with a per-field entry naming the part that was wrong, which is what the baseline's two
        //       separate edit paragraphs do.
        @Size(max = EXPIRATION_YEAR_WIDTH)
        String expirationYear,

        // WHY : Assumptions: the token is boxed rather than primitive so that an omitted token is
        //       refused by name. A primitive would bind an absent member to zero and
        //       send a plausible token the caller never supplied, which would be read as a genuine
        //       mismatch and answered with a conflict naming no member; a boxed member arrives null
        //       and is refused with a per-field entry that names it. The lower bound restates the
        //       bound the contract declares, and it is a shape constraint only: absence and a
        //       negative value are answered here, while a token that is well formed but stale is a
        //       comparison against stored state and is answered with 409 by the service.
        @NotNull
        @Min(MINIMUM_VERSION)
        Integer version) {

    /**
     * Declared width of the embossed name, from {@code app/cpy/CVACT02Y.cpy:8} and its screen input at
     * {@code app/cpy-bms/COCRDUP.CPY:72}.
     */
    private static final int EMBOSSED_NAME_WIDTH = 50;

    /**
     * Declared width of the active status, from {@code app/cpy/CVACT02Y.cpy:10} and its screen input at
     * {@code app/cpy-bms/COCRDUP.CPY:78}.
     *
     * <p>Assumptions: this is applied as a lower bound as well as an upper one, because the baseline
     * tests a single character and a longer value would reach the domain test with content the
     * one-character work field at {@code app/cbl/COCRDUPC.cbl:89-90} could not hold.</p>
     */
    private static final int ACTIVE_STATUS_WIDTH = 1;

    /**
     * The smallest token value the contract admits.
     *
     * <p>Assumptions: zero is the value a freshly inserted row carries before its first update, so the
     * bound excludes a negative token without excluding a card that has never been changed.</p>
     */
    private static final long MINIMUM_VERSION = 0L;

    /**
     * Declared width of the expiry month, from the two-character field
     * {@code app/cbl/COCRDUPC.cbl:93-94} declares and its screen input at
     * {@code app/cpy-bms/COCRDUP.CPY:84}.
     *
     * <p>Assumptions: this is applied as a lower bound as well as an upper one, because the baseline
     * tests a class condition over a two-character field and a single digit reaching that field would be
     * padded rather than refused. Requiring exactly two characters is what keeps the leading zero
     * mandatory, which is the form the stored date is assembled from at line 1468.</p>
     */
    private static final int EXPIRATION_MONTH_WIDTH = 2;

    /**
     * Declared width of the expiry year, from the four-character field
     * {@code app/cbl/COCRDUPC.cbl:97-98} declares and its screen input at
     * {@code app/cpy-bms/COCRDUP.CPY:90}.
     */
    private static final int EXPIRATION_YEAR_WIDTH = 4;

    /**
     * The placeholder that stands in for a component this record refuses to render.
     *
     * <p>Assumptions: a fixed marker is used rather than a truncation, because a truncated name is still
     * a name. It names the reason rather than being a row of asterisks, so a reader of a log line can
     * tell the value was withheld deliberately rather than being absent. The literal matches the one
     * {@link CardDetail} and the response records in {@code com.carddemo.auth.dto} use, so a log store
     * holding lines from several migrated shapes shows one placeholder vocabulary rather than one per
     * package.</p>
     */
    private static final String REDACTED_PERSONAL = "REDACTED";

    /**
     * Renders this request for a diagnostic without disclosing the cardholder.
     *
     * <p>Refactoring Rationale: a record's generated rendering prints every component, so any log line,
     * assertion message or exception detail that stringified a rejected update wrote a real cardholder's
     * name and their card's expiry into a durable store. A REJECTED request is exactly the instance most
     * likely to be stringified, because a refusal is what gets logged, so the default rendering was worst
     * on the path it was most often reached from.</p>
     *
     * <p>Assumptions: the status and the concurrency token print in full because neither identifies a
     * person -- one is a single flag from a two-value domain and the other is a row counter -- while the
     * name and the two expiry parts are withheld. The expiry parts are withheld together rather than
     * individually, because a month or a year alone still narrows a cardholder when read beside anything
     * else in the same line, and because {@link CardDetail} withholds the assembled date for the same
     * reason -- a partial expiry beside a partial card number completes two of the three components of a
     * card credential.</p>
     *
     * <p>Refactoring Rationale: an earlier revision printed the expiry, on the ground that it is a card
     * attribute carrying no personal content. That is true of the value in isolation and not of the line
     * it appears in, which is the unit a log store keeps.</p>
     *
     * @return a rendering naming the type, the status and the concurrency token, with the embossed name
     *     and both expiry parts replaced by {@value #REDACTED_PERSONAL}
     */
    @Override
    public String toString() {
        // Assumptions: the generated form's shape is reproduced deliberately, component order included,
        //   so that a reader who knows what a record prints is not led to think some other type produced
        //   this line. Only the withheld values depart from it.
        return "CardUpdateRequest[embossedName=" + REDACTED_PERSONAL
                + ", activeStatus=" + activeStatus
                + ", expirationMonth=" + REDACTED_PERSONAL
                + ", expirationYear=" + REDACTED_PERSONAL
                + ", version=" + version
                + "]";

    }
}
