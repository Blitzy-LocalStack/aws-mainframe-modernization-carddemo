package com.carddemo.authorization.dto;

import com.carddemo.common.money.Money;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Carries the pending-authorization detail screen outward as one projection of 27 components.
 *
 * <h2>Which edge this record serves</h2>
 *
 * <p>Refactoring Rationale: this record is the record of what the 3270 detail screen displayed, and it
 * is <b>not</b> an HTTP body. {@code src/main/resources/openapi/authorization-api.yaml} is the contract of
 * record for this context's HTTP edge, and {@code PendingAuthDetailView} is its Java realisation for this payload; the
 * package charter in {@code package-info.java} states the split once for all twelve types here. An earlier
 * state of this package left the question open, so a reader had two plausible candidates for one payload
 * and no way to choose. Assumptions: this record is retained rather than deleted because it is the only
 * place three reference compositions and this screen's own field widths are recorded, and it is what the
 * browser screen implements. Trade-offs: two types describe one screen, and the compensation is that each
 * now names its edge on itself.</p>
 *
 * <h2>What this record is derived from, and why it is one record rather than two</h2>
 *
 * <p><strong>Purpose.</strong> This is the response body of the pending-authorization detail
 * surface that {@code com.carddemo.authorization.api} exposes in place of the CICS transaction
 * driven by {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl}. It carries data outward and
 * does nothing else: it reaches no datastore, holds no business rule, and decides no
 * authorization. It declares 27 components because 27 is the number the baseline screen carries,
 * and that number is established below rather than asserted.
 *
 * <p><strong>Return values.</strong> Controller code returns one instance of this record as the
 * complete response body; the record does not wrap a second payload or expose an operation with a
 * separate method return.
 *
 * <p>Assumptions: the component count is 27, and three independent derivations agree on it, which
 * is why it is written here as a contract rather than as a reading. Counting the level-02 input
 * items of {@code cpy-bms/COPAU01.cpy} mechanically returns 27. That same file's geometry returns
 * 27 a second way: {@code 01 COPAU1AI.} opens at L17, a 12-byte {@code FILLER} follows at L18, one
 * logical field occupies six lines thereafter, and {@code ERRMSGI} sits at L180, so
 * {@code (180 - 18) / 6} is 27. Enumerating every {@code MOVE} into an output item across
 * {@code cbl/COPAUS1C.cbl} yields the same 27 names and no others, and that third derivation is
 * the one that settles where the boundary falls: it shows the six header components and the
 * message line are fed by the program itself, at L413 to L428 and at L377, so they belong inside
 * the projection rather than beside it.
 *
 * <p>Assumptions: one record covers both halves of the symbolic map, and the reason is structural.
 * {@code cpy-bms/COPAU01.cpy} declares {@code 01 COPAU1AO REDEFINES COPAU1AI.} at L181, so the
 * output half occupies the same storage as the input half and the two are one shape rather than
 * two. The sibling screen corroborates it from the opposite direction:
 * {@code cbl/COPAUS0C.cbl} L547 writes outbound data into a name carrying the input suffix, as
 * {@code MOVE PA-TRANSACTION-ID TO TRNID01I OF COPAU0AI}, which is coherent only under such a
 * redefinition. A second record for the output half would therefore describe the same 27 positions
 * a second time, and the two copies would drift independently.
 *
 * <h2>The message component is 78 positions, and the house 75-position contract does not reach it</h2>
 *
 * <p>Assumptions: the message component admits 78 characters, not the 75 that most of this
 * repository uses. Four declarations agree and all four say {@code PIC X(78)}: this screen's
 * {@code cpy-bms/COPAU01.cpy} L344 and the summary screen's {@code cpy-bms/COPAU00.cpy} L764 on
 * the output half, and {@code cpy-bms/COPAU01.cpy} L180 and {@code cpy-bms/COPAU00.cpy} L390 on
 * the input half. Its feeder is wider still: {@code WS-MESSAGE PIC X(80)} at
 * {@code cbl/COPAUS1C.cbl} L37 is moved into the 78-position item at L377, so the last two
 * characters of a full-width message do not reach the screen in the baseline. The baseline
 * truncates at 78; the target carries 78 and the divergence is recorded here so that a reader
 * meeting an 80-character source string is not surprised by a 78-character contract. The house
 * 75-position contract is a different contract belonging to a different family: it is
 * {@code CCARD-ERROR-MSG PIC X(75)} and {@code CCARD-RETURN-MSG PIC X(75)} at
 * {@code app/cpy/CVCRD01Y.cpy} L28 and L29, and it does not govern this record. That is checkable
 * rather than argued. {@code cbl/COPAUS1C.cbl} includes exactly ten copybooks, at L109, L122,
 * L126, L129, L132, L135, L142, L146, L148 and L149, and {@code CVCRD01Y} is not among them; no
 * file anywhere in {@code app/app-authorization-ims-db2-mq} names it at all. Sizing this component
 * at 75 would reject three characters the screen accepts.
 *
 * <h2>Copybook widths are normative here; symbolic-map widths are screen geometry</h2>
 *
 * <p>Assumptions: where a copybook width and a symbolic-map width disagree, the copybook width is
 * the one every constraint below is sized from. This follows transformation rule T1 of the
 * migration plan, which makes a copybook {@code PICTURE} the normative source of width, type and
 * offset. That plan numbers its transformation rules T1 to T10, and those identifiers are a
 * different namespace from the user-specified rules: T1 here is not Rule 1, and a citation blurring
 * the two sends a reader to the other document entirely.
 *
 * <p>Assumptions: one observation settles the question outright, because it shows a map width
 * changing while the datum does not. The transaction identifier is declared {@code PIC X(16)} on
 * the summary screen, five times over, at {@code cpy-bms/COPAU00.cpy} L156, L204, L252, L300 and
 * L342, and {@code PIC X(15)} on this screen at {@code cpy-bms/COPAU01.cpy} L132, yet both display
 * the single datum {@code PA-TRANSACTION-ID PIC X(15)} at {@code cpy/CIPAUDTY.cpy} L44. One datum
 * cannot have two widths, so at least one map width is not a width at all; it is the number of
 * character cells the screen designer set aside. The copybook is the width that survives the move.
 *
 * <p>Assumptions: six components on this screen have a map width wider than the datum behind them,
 * and each is sized from the copybook. The merchant name is {@code X(25)} on the map and
 * {@code PA-MERCHANT-NAME PIC X(22)} at {@code cpy/CIPAUDTY.cpy} L40. The merchant city is
 * {@code X(25)} on the map and {@code PA-MERCHANT-CITY PIC X(13)} at L41. The merchant postal code
 * is {@code X(10)} on the map and {@code PA-MERCHANT-ZIP PIC X(09)} at L43. The authorization type
 * is {@code X(14)} on the map and {@code PA-AUTH-TYPE PIC X(04)} at L25. The message source is
 * {@code X(10)} on the map and {@code PA-MESSAGE-SOURCE PIC X(06)} at L28. The point-of-sale entry
 * mode is {@code X(4)} on the map and {@code PA-POS-ENTRY-MODE PIC 9(02)} at L38. The consequence
 * of taking the map instead is specific: a constraint sized at 25 would accept a 25-character
 * merchant name that the persistent 22-position field cannot hold, so the request would be admitted
 * here and refused later, at a layer with no field-level answer to give.
 *
 * <p>Assumptions: a second and separate mechanism also produces a map width wider than its content,
 * and it is kept distinct from the six above because it compares a rendered string to a cell count
 * rather than one declared field to another. The authorization date arrives as
 * {@code WS-AUTH-DATE PIC X(08)} at {@code cbl/COPAUS1C.cbl} L53, assembled as
 * {@code MM/DD/YY} from three two-character slices of {@code PA-AUTH-ORIG-DATE} at L297 to L301,
 * and is displayed in an {@code X(10)} cell. The authorization time arrives as
 * {@code WS-AUTH-TIME PIC X(08)} at L54, assembled as {@code HH:MM:SS} at L303 to L306, and is
 * likewise displayed in an {@code X(10)} cell. Here the governing number is the rendered length,
 * 8, because no field of width 10 exists anywhere behind either component.
 *
 * <p>Assumptions: nine further map widths match their content exactly and are therefore quoted in
 * the component descriptions as corroboration rather than as a separate rule. The composed fraud
 * mark fills {@code X(10)} as one status character, one separator and an eight-character date; the
 * composed card expiry fills {@code X(5)} as {@code MM/YY}; the composed response reason fills
 * {@code X(20)} as a four-character code, one separator and fifteen remaining positions; the
 * derived response indicator fills {@code X(1)}; and the processing code {@code X(6)}, merchant
 * category code {@code X(4)}, match status {@code X(1)}, merchant state {@code X(2)} and
 * transaction identifier {@code X(15)} each equal their copybook width outright.
 *
 * <h2>The persisted merchant name arrives at its full declared width</h2>
 *
 * <p>Assumptions: the merchant name is never shortened on its way to storage, so a value read back
 * out may carry trailing spaces. The fraud-marking program writes it to a variable-length column in
 * two statements: {@code cbl/COPAUS2C.cbl} L130 moves {@code LENGTH OF PA-MERCHANT-NAME} into the
 * level-49 length item that precedes the text, and L131 moves the text itself. {@code LENGTH OF} an
 * {@code X(22)} item is the constant 22 regardless of what the item holds, so the declared length
 * always says 22 and the stored value always spans all 22 positions, trailing spaces included. The
 * consequence for a consumer of this record is direct: the merchant name component may arrive
 * space-padded to 22 characters, and any equality test against a stored value has to account for
 * that padding rather than assume it was trimmed.
 *
 * <h2>Monetary form on the wire</h2>
 *
 * <p>Alternatives Considered: the approved amount could travel as a JSON number, which is the
 * shorter and more obvious encoding, and it is rejected in favour of a JSON string. A JSON number
 * is parsed into an IEEE-754 double by most clients, and a decimal cent has no exact binary
 * floating-point value, so the exactness would be lost silently at the boundary a user actually
 * reads rather than loudly where it could be caught. The string form also preserves rather than
 * introduces a contract: the baseline already moves this family of amounts as text and converts
 * with {@code FUNCTION NUMVAL}, at {@code cbl/COPAUA0C.cbl} L376 to L377, so a decimal string is
 * what the authorization flow has always carried. The type is
 * {@code com.carddemo.common.money.Money}, imported from the shared kernel and not restated here,
 * and its wire form is set once by {@code com.carddemo.common.money.MoneyModule} rather than by an
 * annotation on this record. No component of this record is {@code float}, {@code double} or
 * {@code java.lang.Double}, so this carrier cannot introduce binary floating-point into the money
 * path.
 *
 * <p>Assumptions: the display masks the baseline applies to money are presentation forms owned by
 * this context's {@code mapper} package, and this record applies none of them. They are not
 * interchangeable, which is why they are enumerated rather than treated as one: this screen renders
 * through the 12-position {@code WS-AUTH-AMT PIC -zzzzzzz9.99} at {@code cbl/COPAUS1C.cbl} L52, the
 * summary screen through the 12-position {@code WS-DISPLAY-AMT12} at {@code cbl/COPAUS0C.cbl} L56
 * and the 9-position {@code WS-DISPLAY-AMT9} at L57, and the message reply through the 14-position
 * {@code WS-APPROVED-AMT-DIS} at {@code cbl/COPAUA0C.cbl} L66. Reusing one family's mask for
 * another would change the rendered width of an amount.
 *
 * <h2>What this record deliberately does not carry</h2>
 *
 * <p>Trade-offs: this record stays a projection of what the screen displayed rather than widening
 * into the whole authorization segment, and the alternative was genuinely available. Returning
 * every field of {@code cpy/CIPAUDTY.cpy} would spare a client a second call for anything the
 * screen happened to omit. It is not done because five items of that segment have no screen
 * provenance at all: {@code PA-AUTH-ID-CODE} at L29, {@code PA-ACQR-COUNTRY-CODE} at L37,
 * {@code PA-MESSAGE-TYPE} at L27, the raw {@code PA-AUTH-RESP-CODE} at L30, of which only the
 * derived single character survives by way of the test at {@code cbl/COPAUS1C.cbl} L311, and the
 * raw {@code PA-AUTH-ORIG-DATE} and {@code PA-AUTH-ORIG-TIME} at L22 and L23, of which only the
 * rendered forms survive by way of L297 to L306. None of the five is moved to an output item
 * anywhere in that program, so adding one would put a value on the wire that the baseline never
 * put there, which is a behavioural change carrying no baseline support. The cost accepted is
 * stated plainly rather than glossed: a client that needs the authorization identifier code or the
 * acquirer country code has no endpoint here that returns it, and gaining one is a scope decision
 * with a documented divergence attached, not an edit to this record.
 *
 * <p>Assumptions: three further omissions are boundary decisions rather than provenance ones. No
 * card verification value appears here or anywhere in this package. The card number component
 * carries the masked form, reduced to its last four digits by this context's {@code mapper}
 * package, which the migration plan names as the only place representation concerns may appear; an
 * unmasked primary account number is reserved for the administrative card-detail surface, which
 * this is not. And no offset-pagination component appears -- no page number, offset, skip or total
 * count -- because {@code com.carddemo.common.web.PageResponse} is the one page envelope for this
 * reactor, carrying items, opaque cursor tokens and a next-page indicator, and it is imported where
 * a page is returned rather than reproduced piecemeal here. A detail response describes one
 * authorization and is not a page at all.
 *
 * <h2>Shape of the type itself</h2>
 *
 * <p>Alternatives Considered: neither Lombok nor MapStruct is used, and the two are declined for
 * different reasons. Lombok would generate the accessors, but a generated accessor cannot carry the
 * documentation that user-specified Rule 1 (Explainability) L15 requires of it, and a Java 21
 * {@code record} already gives the same brevity while leaving every member documentable, so the
 * generator would cost the documentation and return nothing this form lacks. MapStruct would
 * generate the mapping into this shape, and it is declined because the mapping is not mechanical:
 * it drops {@code FILLER}, which {@code cpy/CIPAUDTY.cpy} declares at L54 purely to reach the
 * record length; it masks a primary account number to its last four digits; it suppresses any card
 * verification value outright; it composes four of the components below from more than one source
 * each; and it changes a baseline field spelling, the merchant category code at
 * {@code cpy/CIPAUDTY.cpy} L36. Each of those needs a justification written at the site where it
 * happens, and a generated mapper has nowhere to hold one.
 *
 * <p>Assumptions: this record declares no persistence annotation and imports nothing from
 * {@code com.carddemo.authorization.domain}, which is the boundary its package charter asserts. It
 * declares no method either, and that is a deliberate consequence of the same charter: masking,
 * rendering and normalisation all belong to {@code mapper}, so there is no behaviour left for a
 * constructor or an override to hold, and the validation below is declarative rather than
 * procedural. Every constraint is a Jakarta Bean Validation annotation whose bound is a named
 * constant declared in the body, so each declared width has exactly one executable position to
 * compare against the copybook line quoted beside it.
 *
 * <p>Assumptions: no component is annotated as required. A detail response legitimately carries
 * absent and blank values -- the fraud mark is a lone separator when no mark exists, by
 * {@code cbl/COPAUS1C.cbl} L349, and the message line is blank whenever the program reports
 * nothing -- so a presence constraint would reject states the baseline produces. Under Jakarta Bean
 * Validation an absent value satisfies both a size and a pattern constraint, so the bounds below
 * describe the shape of a value that is present without demanding that one be.
 *
 * <h2>What the test channel for this record has to assert</h2>
 *
 * <p>Assumptions: {@code services/authorization-service/src/test/java/com/carddemo/authorization}
 * holds no test for this type yet, so the obligations are written out here rather than referred to
 * elsewhere. The tests must assert that this record declares exactly 27 components; that the
 * message component accepts a 78-character value and rejects a 79-character one; that the match
 * status accepts only {@code P}, {@code D}, {@code E} and {@code M}; that the response indicator
 * accepts only {@code A} and {@code D}; that the approved amount serialises as a JSON string and
 * never as a JSON number; and that the six components whose map width exceeds their datum are
 * bounded at the copybook width, meaning 22, 13, 9, 4, 6 and 2 rather than 25, 25, 10, 14, 10
 * and 4.
 *
 * @param transactionName the transaction identifier the screen displays, from {@code TRNNAMEI} at
 *     {@code cpy-bms/COPAU01.cpy} L24; 4 positions, matching
 *     {@code WS-CICS-TRANID PIC X(04) VALUE 'CPVD'} at {@code cbl/COPAUS1C.cbl} L36, which L415
 *     moves to the screen
 * @param title01 the first line of the shared title band, from {@code TITLE01I} at
 *     {@code cpy-bms/COPAU01.cpy} L30; 40 positions, matching {@code CCDA-TITLE01 PIC X(40)} at
 *     {@code app/cpy/COTTL01Y.cpy} L18, which {@code cbl/COPAUS1C.cbl} L413 moves to the screen
 * @param currentDate the rendered current date, from {@code CURDATEI} at
 *     {@code cpy-bms/COPAU01.cpy} L36; 8 positions, being the assembled length of
 *     {@code WS-CURDATE-MM-DD-YY} at {@code app/cpy/CSDAT01Y.cpy} L30, whose members are three
 *     two-digit parts separated by two literal solidus characters, moved to the screen at
 *     {@code cbl/COPAUS1C.cbl} L422
 * @param programName the program identifier the screen displays, from {@code PGMNAMEI} at
 *     {@code cpy-bms/COPAU01.cpy} L42; 8 positions, matching
 *     {@code WS-PGM-AUTH-DTL PIC X(08) VALUE 'COPAUS1C'} at {@code cbl/COPAUS1C.cbl} L33, which
 *     L416 moves to the screen
 * @param title02 the second line of the shared title band, from {@code TITLE02I} at
 *     {@code cpy-bms/COPAU01.cpy} L48; 40 positions, matching {@code CCDA-TITLE02 PIC X(40)} at
 *     {@code app/cpy/COTTL01Y.cpy} L20, which {@code cbl/COPAUS1C.cbl} L414 moves to the screen
 * @param currentTime the rendered current time, from {@code CURTIMEI} at
 *     {@code cpy-bms/COPAU01.cpy} L54; 8 positions, being the assembled length of
 *     {@code WS-CURTIME-HH-MM-SS} at {@code app/cpy/CSDAT01Y.cpy} L36, moved to the screen at
 *     {@code cbl/COPAUS1C.cbl} L428
 * @param cardNumber the card number of the authorization, masked to its last four digits, from
 *     {@code CARDNUMI} at {@code cpy-bms/COPAU01.cpy} L60; 16 positions, matching
 *     {@code PA-CARD-NUM PIC X(16)} at {@code cpy/CIPAUDTY.cpy} L24, which
 *     {@code cbl/COPAUS1C.cbl} L295 moves to the screen; deliberately not constrained to digits,
 *     because the masked form this component receives is not all digits
 * @param authDate the authorization date rendered as {@code MM/DD/YY}, from {@code AUTHDTI} at
 *     {@code cpy-bms/COPAU01.cpy} L66; 8 positions, the length of
 *     {@code WS-AUTH-DATE PIC X(08)} at {@code cbl/COPAUS1C.cbl} L53, rather than the 10 cells the
 *     map sets aside; assembled from three slices of {@code PA-AUTH-ORIG-DATE} at L297 to L301
 * @param authTime the authorization time rendered as {@code HH:MM:SS}, from {@code AUTHTMI} at
 *     {@code cpy-bms/COPAU01.cpy} L72; 8 positions, the length of
 *     {@code WS-AUTH-TIME PIC X(08)} at {@code cbl/COPAUS1C.cbl} L54, rather than the 10 cells the
 *     map sets aside; assembled from three slices of {@code PA-AUTH-ORIG-TIME} at L303 to L306
 * @param authResponse the derived approval indicator, from {@code AUTHRSPI} at
 *     {@code cpy-bms/COPAU01.cpy} L78; exactly one position holding {@code A} or {@code D} and
 *     nothing else, produced by testing {@code PA-AUTH-RESP-CODE} against the approved value
 *     {@code '00'} at {@code cbl/COPAUS1C.cbl} L311 and moving {@code A} at L312 or {@code D} at
 *     L315
 * @param authResponseReason the response reason composed as a four-character code, a separator and
 *     a description, from {@code AUTHRSNI PIC X(20)} at {@code cpy-bms/COPAU01.cpy} L84; 20 positions,
 *     being {@code PA-AUTH-RESP-REASON PIC X(04)} at {@code cpy/CIPAUDTY.cpy} L32, one separator, and
 *     the first 15 characters of {@code DECL-DESC PIC X(16)} at {@code cbl/COPAUS1C.cbl} L73 -- the
 *     sixteenth is truncated by the receiving field, as it is on the screen
 * @param processingCode the processing code, from {@code AUTHCDI} at
 *     {@code cpy-bms/COPAU01.cpy} L90; 6 digits, from {@code PA-PROCESSING-CODE PIC 9(06)} at
 *     {@code cpy/CIPAUDTY.cpy} L33, which {@code cbl/COPAUS1C.cbl} L331 moves to the screen; the
 *     numeric picture is why this component is constrained to digits
 * @param approvedAmount the approved amount of the authorization, from {@code AUTHAMTI} at
 *     {@code cpy-bms/COPAU01.cpy} L96; carried as exact decimal in
 *     {@code com.carddemo.common.money.Money} and serialised as a JSON string, from
 *     {@code PA-APPROVED-AMT PIC S9(10)V99 COMP-3} at {@code cpy/CIPAUDTY.cpy} L35, which
 *     {@code cbl/COPAUS1C.cbl} L308 and L309 render and move to the screen
 * @param posEntryMode the point-of-sale entry mode, from {@code POSEMDI} at
 *     {@code cpy-bms/COPAU01.cpy} L102; 2 digits, from {@code PA-POS-ENTRY-MODE PIC 9(02)} at
 *     {@code cpy/CIPAUDTY.cpy} L38 rather than the 4 cells the map sets aside, which
 *     {@code cbl/COPAUS1C.cbl} L332 moves to the screen
 * @param messageSource the source of the authorization message, from {@code AUTHSRCI} at
 *     {@code cpy-bms/COPAU01.cpy} L108; 6 positions, from
 *     {@code PA-MESSAGE-SOURCE PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L28 rather than the 10 cells
 *     the map sets aside, which {@code cbl/COPAUS1C.cbl} L333 moves to the screen
 * @param merchantCategoryCode the merchant category code, from {@code MCCCDI} at
 *     {@code cpy-bms/COPAU01.cpy} L114; 4 positions, from
 *     {@code PA-MERCHANT-CATAGORY-CODE PIC X(04)} at {@code cpy/CIPAUDTY.cpy} L36, which
 *     {@code cbl/COPAUS1C.cbl} L334 moves to the screen; the migration's name changes the baseline
 *     spelling of that field while the value crosses unchanged
 * @param cardExpiry the card expiry composed as {@code MM/YY}, from {@code CRDEXPI} at
 *     {@code cpy-bms/COPAU01.cpy} L120; 5 positions, being two two-character slices of
 *     {@code PA-CARD-EXPIRY-DATE PIC X(04)} at {@code cpy/CIPAUDTY.cpy} L26 with one separator
 *     inserted between them at {@code cbl/COPAUS1C.cbl} L336 to L338
 * @param authType the authorization type, from {@code AUTHTYPI} at
 *     {@code cpy-bms/COPAU01.cpy} L126; 4 positions, from {@code PA-AUTH-TYPE PIC X(04)} at
 *     {@code cpy/CIPAUDTY.cpy} L25 rather than the 14 cells the map sets aside, which
 *     {@code cbl/COPAUS1C.cbl} L340 moves to the screen
 * @param transactionId the transaction identifier of the authorization, from {@code TRNIDI} at
 *     {@code cpy-bms/COPAU01.cpy} L132; 15 positions, from
 *     {@code PA-TRANSACTION-ID PIC X(15)} at {@code cpy/CIPAUDTY.cpy} L44, which
 *     {@code cbl/COPAUS1C.cbl} L341 moves to the screen; the summary screen displays the same
 *     datum in 16 cells, which is why 15 is taken from the copybook
 * @param matchStatus the match status of the authorization, from {@code AUTHMTCI} at
 *     {@code cpy-bms/COPAU01.cpy} L138; exactly one position drawn from the four values
 *     {@code PA-MATCH-STATUS PIC X(01)} admits at {@code cpy/CIPAUDTY.cpy} L45, namely {@code P}
 *     at L46, {@code D} at L47, {@code E} at L48 and {@code M} at L49, moved to the screen at
 *     {@code cbl/COPAUS1C.cbl} L342
 * @param fraudMark the fraud mark composed as a status character, a separator and the report date,
 *     from {@code AUTHFRDI} at {@code cpy-bms/COPAU01.cpy} L144; 10 positions, being one of the two
 *     values {@code PA-AUTH-FRAUD} admits at {@code cpy/CIPAUDTY.cpy} L51 and L52 plus a separator
 *     plus {@code PA-FRAUD-RPT-DATE PIC X(08)} at L53, assembled at {@code cbl/COPAUS1C.cbl} L345
 *     to L347, or a lone separator when neither value is set, by L349
 * @param merchantName the merchant name, from {@code MERNAMEI} at
 *     {@code cpy-bms/COPAU01.cpy} L150; 22 positions, from
 *     {@code PA-MERCHANT-NAME PIC X(22)} at {@code cpy/CIPAUDTY.cpy} L40 rather than the 25 cells
 *     the map sets aside, which {@code cbl/COPAUS1C.cbl} L352 moves to the screen; may arrive
 *     padded to its full width for the reason set out above
 * @param merchantId the merchant identifier, from {@code MERIDI} at
 *     {@code cpy-bms/COPAU01.cpy} L156; 15 positions, from
 *     {@code PA-MERCHANT-ID PIC X(15)} at {@code cpy/CIPAUDTY.cpy} L39, which
 *     {@code cbl/COPAUS1C.cbl} L353 moves to the screen
 * @param merchantCity the merchant city, from {@code MERCITYI} at
 *     {@code cpy-bms/COPAU01.cpy} L162; 13 positions, from
 *     {@code PA-MERCHANT-CITY PIC X(13)} at {@code cpy/CIPAUDTY.cpy} L41 rather than the 25 cells
 *     the map sets aside, which {@code cbl/COPAUS1C.cbl} L354 moves to the screen
 * @param merchantState the merchant state, from {@code MERSTI} at
 *     {@code cpy-bms/COPAU01.cpy} L168; 2 positions, from
 *     {@code PA-MERCHANT-STATE PIC X(02)} at {@code cpy/CIPAUDTY.cpy} L42, which
 *     {@code cbl/COPAUS1C.cbl} L355 moves to the screen
 * @param merchantZip the merchant postal code, from {@code MERZIPI} at
 *     {@code cpy-bms/COPAU01.cpy} L174; 9 positions, from
 *     {@code PA-MERCHANT-ZIP PIC X(09)} at {@code cpy/CIPAUDTY.cpy} L43 rather than the 10 cells
 *     the map sets aside, which {@code cbl/COPAUS1C.cbl} L356 moves to the screen
 * @param message the screen message line, from {@code ERRMSGI} at
 *     {@code cpy-bms/COPAU01.cpy} L180; 78 positions, corroborated at
 *     {@code cpy-bms/COPAU01.cpy} L344 and on the summary screen at
 *     {@code cpy-bms/COPAU00.cpy} L390 and L764, and fed from the wider
 *     {@code WS-MESSAGE PIC X(80)} at {@code cbl/COPAUS1C.cbl} L37 by way of L377; not the 75
 *     positions used elsewhere in this repository
 */
public record PendingAuthDetailResponse(
        @Size(max = TRANSACTION_NAME_WIDTH) String transactionName,
        @Size(max = TITLE_WIDTH) String title01,
        @Size(max = RENDERED_DATE_WIDTH) String currentDate,
        @Size(max = PROGRAM_NAME_WIDTH) String programName,
        @Size(max = TITLE_WIDTH) String title02,
        @Size(max = RENDERED_TIME_WIDTH) String currentTime,
        @Size(max = CARD_NUMBER_WIDTH) String cardNumber,
        @Size(max = RENDERED_DATE_WIDTH) String authDate,
        @Size(max = RENDERED_TIME_WIDTH) String authTime,
        // WHY : Assumptions: the single character is the whole of what the baseline projects here.
        //       cbl/COPAUS1C.cbl L311 tests PA-AUTH-RESP-CODE against '00' and then moves 'A' at
        //       L312 or 'D' at L315, so the two-character response code itself never reaches the
        //       screen. Carrying the derived character as one component rather than exposing the
        //       code beside it keeps the projection to what L312 and L315 actually produce.
        @Size(max = AUTH_RESPONSE_WIDTH) @Pattern(regexp = AUTH_RESPONSE_DOMAIN) String authResponse,
        // WHY : Assumptions: this is one composed string rather than a code component and a
        //       description component, because the baseline composes it in place: L325 moves the
        //       four-character reason code, L326 overlays a separator at position 5, and L327
        //       moves the description from position 6 onward, with L321 to L323 writing the
        //       '9999' and 'ERROR' pair when the table lookup finds no entry. Splitting it would
        //       invent two fields where the screen carried one, and the '9999' fallback has no
        //       separate code field to live in.
        // WHY : Refactoring Rationale: the width is 20 and an earlier revision made it 21, on the
        //       stated ground that retaining the whole 16-character description was worth one
        //       character of divergence. That reasoning inverted the contract. AUTHRSNO is X(20) at
        //       cpy-bms/COPAU01.cpy L248 -- corroborated by AUTHRSNI X(20) at L84 -- and the MOVE at
        //       cbl/COPAUS1C.cbl L327 targets AUTHRSNO(6:), a reference-modified receiver of exactly
        //       15 positions, so COBOL truncates the sixteenth character on the way in. The
        //       twenty-first character therefore does not exist anywhere in the baseline: not on the
        //       screen, not in the receiving field and not in any stored value. Carrying it made the
        //       payload wider than the only observable form of this value, and it would have let a
        //       projection publish a character the screen it mirrors cannot show. The width and the
        //       truncation it reproduces are registered as D-AUTH-REASON-WIDTH in
        //       docs/architecture/cobol-to-service-traceability.md.
        @Size(max = AUTH_RESPONSE_REASON_WIDTH) String authResponseReason,
        @Size(max = PROCESSING_CODE_WIDTH) @Pattern(regexp = DIGITS_ONLY) String processingCode,
        Money approvedAmount,
        @Size(max = POS_ENTRY_MODE_WIDTH) @Pattern(regexp = DIGITS_ONLY) String posEntryMode,
        @Size(max = MESSAGE_SOURCE_WIDTH) String messageSource,
        @Size(max = MERCHANT_CATEGORY_CODE_WIDTH) String merchantCategoryCode,
        // WHY : Assumptions: the separator is part of the value, not a formatting choice made
        //       downstream. cbl/COPAUS1C.cbl L336 moves the first two characters of
        //       PA-CARD-EXPIRY-DATE, L337 overlays a solidus at position 3, and L338 moves the
        //       remaining two, so the stored four characters reach the screen as five. Carrying
        //       the composed form keeps this component equal to what the screen displayed; a
        //       four-character component would move the composition to every consumer instead.
        @Size(max = CARD_EXPIRY_WIDTH) String cardExpiry,
        @Size(max = AUTH_TYPE_WIDTH) String authType,
        @Size(max = TRANSACTION_ID_WIDTH) String transactionId,
        @Size(max = MATCH_STATUS_WIDTH) @Pattern(regexp = MATCH_STATUS_DOMAIN) String matchStatus,
        // WHY : Assumptions: one component holds two distinct shapes, and both are the baseline's.
        //       When a mark exists, cbl/COPAUS1C.cbl L345 to L347 assemble a status character, a
        //       separator at position 2 and the eight-character report date from position 3;
        //       when none exists, L349 moves a lone separator over the whole field. A pattern
        //       constraint is deliberately not written for this component, because
        //       PA-FRAUD-RPT-DATE is declared PIC X(08) at cpy/CIPAUDTY.cpy L53 and a character
        //       picture does not guarantee digits, so a digit pattern would reject values the
        //       persistent field admits.
        @Size(max = FRAUD_MARK_WIDTH) String fraudMark,
        @Size(max = MERCHANT_NAME_WIDTH) String merchantName,
        @Size(max = MERCHANT_ID_WIDTH) String merchantId,
        @Size(max = MERCHANT_CITY_WIDTH) String merchantCity,
        @Size(max = MERCHANT_STATE_WIDTH) String merchantState,
        @Size(max = MERCHANT_ZIP_WIDTH) String merchantZip,
        @Size(max = MESSAGE_WIDTH) String message) {

    /**
     * Maximum width of the transaction name displayed by this screen.
     *
     * <p>Assumptions: {@code WS-CICS-TRANID PIC X(04)} at
     * {@code cbl/COPAUS1C.cbl} L36 is the four-character value moved to the screen at L415.
     */
    private static final int TRANSACTION_NAME_WIDTH = 4;

    /**
     * Maximum width shared by both title-band lines.
     *
     * <p>Assumptions: {@code CCDA-TITLE01 PIC X(40)} and
     * {@code CCDA-TITLE02 PIC X(40)} at {@code app/cpy/COTTL01Y.cpy} L18 and L20 establish the
     * common 40-character width before {@code cbl/COPAUS1C.cbl} L413 and L414 move the values.
     */
    private static final int TITLE_WIDTH = 40;

    /**
     * Maximum width of each rendered date carried by this projection.
     *
     * <p>Assumptions: the current-date layout at {@code app/cpy/CSDAT01Y.cpy} L30 and
     * {@code WS-AUTH-DATE PIC X(08)} at {@code cbl/COPAUS1C.cbl} L53 both produce eight
     * characters, irrespective of the ten display cells reserved for the authorization date.
     */
    private static final int RENDERED_DATE_WIDTH = 8;

    /**
     * Maximum width of the program identifier.
     *
     * <p>Assumptions: {@code WS-PGM-AUTH-DTL PIC X(08)} at
     * {@code cbl/COPAUS1C.cbl} L33 is the value moved to the screen at L416.
     */
    private static final int PROGRAM_NAME_WIDTH = 8;

    /**
     * Maximum width of each rendered time carried by this projection.
     *
     * <p>Assumptions: the current-time layout at {@code app/cpy/CSDAT01Y.cpy} L36 and
     * {@code WS-AUTH-TIME PIC X(08)} at {@code cbl/COPAUS1C.cbl} L54 both produce eight
     * characters, irrespective of the ten display cells reserved for the authorization time.
     */
    private static final int RENDERED_TIME_WIDTH = 8;

    /**
     * Maximum width of the masked card-number component.
     *
     * <p>Assumptions: {@code PA-CARD-NUM PIC X(16)} at {@code cpy/CIPAUDTY.cpy} L24 is the
     * persistent width moved to the screen by {@code cbl/COPAUS1C.cbl} L295.
     */
    private static final int CARD_NUMBER_WIDTH = 16;

    /**
     * Maximum width of the derived authorization-response indicator.
     *
     * <p>Assumptions: {@code cbl/COPAUS1C.cbl} L311 to L315 projects the two-character response
     * code into exactly one displayed character, either {@code A} or {@code D}.
     */
    private static final int AUTH_RESPONSE_WIDTH = 1;

    /**
     * Value domain of the derived authorization-response indicator.
     *
     * <p>Assumptions: the only assignments in {@code cbl/COPAUS1C.cbl} are {@code A} at L312 and
     * {@code D} at L315, so accepting any other character would exceed the baseline projection.
     */
    private static final String AUTH_RESPONSE_DOMAIN = "[AD]";

    /**
     * Maximum width of the composed authorization-response reason.
     *
     * <p>Assumptions: 20 is the observable width, and it is read from the map rather than summed from
     * the sources. {@code AUTHRSNI PIC X(20)} at {@code cpy-bms/COPAU01.cpy} L84 and
     * {@code AUTHRSNO PIC X(20)} at L248 declare it twice over, and the composition fills exactly
     * those positions: {@code PA-AUTH-RESP-REASON PIC X(04)} at {@code cpy/CIPAUDTY.cpy} L32 into
     * positions 1 to 4, the separator written at {@code cbl/COPAUS1C.cbl} L326 into position 5, and
     * {@code DECL-DESC PIC X(16)} at L73 into {@code AUTHRSNO(6:)} at L327, which is 15 positions --
     * so 4 + 1 + 15, with the description's sixteenth character truncated by the receiver.
     *
     * <p>Assumptions: the no-entry path fills the same 20 positions and confirms the reading. When the
     * table search finds no matching code, L321 to L323 write {@code '9999'}, the separator and
     * {@code 'ERROR'}, which is 4 + 1 + 5 characters into the same field.
     */
    private static final int AUTH_RESPONSE_REASON_WIDTH = 20;

    /**
     * The positions the reason code occupies at the start of the composed reason.
     */
    private static final int REASON_CODE_WIDTH = 4;

    /**
     * The separator the reference program writes into position five of the composed reason.
     */
    private static final char REASON_SEPARATOR = '-';

    /**
     * The positions the description occupies, which is what remains after the code and the separator.
     */
    private static final int REASON_DESCRIPTION_WIDTH =
            AUTH_RESPONSE_REASON_WIDTH - REASON_CODE_WIDTH - 1;

    /**
     * Composes the authorization-response reason exactly as the reference program's three moves do.
     *
     * <p><b>Purpose.</b> This is the executable form of the composition at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl} lines 324 to 327 and of its no-entry
     * counterpart at lines 320 to 322. It exists so that the truncation those moves perform is
     * reproduced by one function rather than restated by each caller that assembles this component.</p>
     *
     * <p>Assumptions: the truncation is the point, not a side effect. The receiving field is 20
     * positions and the description arrives from a 16-character table entry into
     * {@code AUTHRSNO(6:)}, a reference-modified receiver of 15 -- so COBOL discards the sixteenth
     * character. A composer that kept it would produce a value one character wider than the screen and
     * one character wider than the field this response mirrors.</p>
     *
     * <p>Assumptions: both arguments are padded as well as truncated, so the result is ALWAYS exactly
     * 20 characters. A COBOL {@code MOVE} into an alphanumeric field space-fills what the source does
     * not reach, so a three-character code lands in positions 1 to 3 with position 4 blank and the
     * separator still in position 5. Trimming instead would shift the separator, which is the one
     * position a reader uses to tell the code from the description.</p>
     *
     * <p>Alternatives Considered: returning the code and the description as two values and letting the
     * caller join them. Rejected because the baseline composes them in place and the no-entry path has
     * no separate code field at all -- it writes the literal {@code '9999'} into the same positions --
     * so two values would invent a structure the screen does not have and would leave the truncation
     * rule with no home.</p>
     *
     * @param reasonCode the four-character reason, {@code PA-AUTH-RESP-REASON} at
     *     {@code cpy/CIPAUDTY.cpy} line 32, or the literal {@code '9999'} on the no-entry path; must
     *     not be {@code null}
     * @param description the reason description, {@code DECL-DESC PIC X(16)} at
     *     {@code cbl/COPAUS1C.cbl} line 73, or the literal {@code 'ERROR'} on the no-entry path; must
     *     not be {@code null}
     * @return the composed reason, always exactly {@code AUTH_RESPONSE_REASON_WIDTH} characters
     * @throws NullPointerException if {@code reasonCode} or {@code description} is {@code null}
     */
    public static String composeAuthResponseReason(String reasonCode, String description) {
        if (reasonCode == null) {
            throw new NullPointerException("reasonCode must not be null");
        }
        if (description == null) {
            throw new NullPointerException("description must not be null");
        }
        return fitToWidth(reasonCode, REASON_CODE_WIDTH) + REASON_SEPARATOR
                + fitToWidth(description, REASON_DESCRIPTION_WIDTH);
    }

    /**
     * Truncates or space-pads one value to a fixed number of positions.
     *
     * <p>Assumptions: this is the alphanumeric {@code MOVE} rule and nothing more -- keep the leftmost
     * positions, pad on the right with spaces. It is written once here rather than twice in the
     * composer above so that the code and the description cannot come to be fitted by two rules.</p>
     *
     * @param value the value to fit; must not be {@code null}
     * @param width the number of positions the receiving field declares
     * @return the value in exactly {@code width} characters
     */
    private static String fitToWidth(String value, int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Maximum width of the processing code.
     *
     * <p>Assumptions: {@code PA-PROCESSING-CODE PIC 9(06)} at {@code cpy/CIPAUDTY.cpy} L33 is
     * the six-digit value moved to the screen by {@code cbl/COPAUS1C.cbl} L331.
     */
    private static final int PROCESSING_CODE_WIDTH = 6;

    /**
     * Character domain shared by values backed by numeric COBOL pictures.
     *
     * <p>Assumptions: {@code PA-PROCESSING-CODE PIC 9(06)} and
     * {@code PA-POS-ENTRY-MODE PIC 9(02)} at {@code cpy/CIPAUDTY.cpy} L33 and L38 admit decimal
     * digits only, while their Java representation remains text so leading zeroes survive.
     */
    private static final String DIGITS_ONLY = "[0-9]+";

    /**
     * Maximum width of the point-of-sale entry mode.
     *
     * <p>Assumptions: {@code PA-POS-ENTRY-MODE PIC 9(02)} at {@code cpy/CIPAUDTY.cpy} L38 is
     * normative, rather than the four cells reserved by the symbolic map.
     */
    private static final int POS_ENTRY_MODE_WIDTH = 2;

    /**
     * Maximum width of the authorization message source.
     *
     * <p>Assumptions: {@code PA-MESSAGE-SOURCE PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L28 is
     * normative, rather than the ten cells reserved by the symbolic map.
     */
    private static final int MESSAGE_SOURCE_WIDTH = 6;

    /**
     * Maximum width of the merchant category code.
     *
     * <p>Assumptions: {@code PA-MERCHANT-CATAGORY-CODE PIC X(04)} at
     * {@code cpy/CIPAUDTY.cpy} L36 supplies the four-character value while the target component
     * uses the documented {@code merchantCategoryCode} spelling.
     */
    private static final int MERCHANT_CATEGORY_CODE_WIDTH = 4;

    /**
     * Maximum width of the rendered card expiry.
     *
     * <p>Assumptions: {@code cbl/COPAUS1C.cbl} L336 to L338 renders the four characters of
     * {@code PA-CARD-EXPIRY-DATE} with one inserted separator, producing five characters.
     */
    private static final int CARD_EXPIRY_WIDTH = 5;

    /**
     * Maximum width of the authorization type.
     *
     * <p>Assumptions: {@code PA-AUTH-TYPE PIC X(04)} at {@code cpy/CIPAUDTY.cpy} L25 is
     * normative, rather than the fourteen cells reserved by the symbolic map.
     */
    private static final int AUTH_TYPE_WIDTH = 4;

    /**
     * Maximum width of the authorization transaction identifier.
     *
     * <p>Assumptions: {@code PA-TRANSACTION-ID PIC X(15)} at {@code cpy/CIPAUDTY.cpy} L44 is
     * normative even though the summary map reserves sixteen cells for the same datum.
     */
    private static final int TRANSACTION_ID_WIDTH = 15;

    /**
     * Maximum width of the authorization match status.
     *
     * <p>Assumptions: {@code PA-MATCH-STATUS PIC X(01)} at {@code cpy/CIPAUDTY.cpy} L45 carries
     * one status character.
     */
    private static final int MATCH_STATUS_WIDTH = 1;

    /**
     * Value domain of the authorization match status.
     *
     * <p>Assumptions: the level-88 values at {@code cpy/CIPAUDTY.cpy} L46 to L49 define exactly
     * {@code P}, {@code D}, {@code E} and {@code M}.
     */
    private static final String MATCH_STATUS_DOMAIN = "[PDEM]";

    /**
     * Maximum width of the rendered fraud mark.
     *
     * <p>Assumptions: {@code cbl/COPAUS1C.cbl} L345 to L347 combines one status character, one
     * separator and {@code PA-FRAUD-RPT-DATE PIC X(08)} into ten positions.
     */
    private static final int FRAUD_MARK_WIDTH = 10;

    /**
     * Maximum width of the merchant name.
     *
     * <p>Assumptions: {@code PA-MERCHANT-NAME PIC X(22)} at {@code cpy/CIPAUDTY.cpy} L40 is
     * normative, rather than the 25 cells reserved by the symbolic map.
     */
    private static final int MERCHANT_NAME_WIDTH = 22;

    /**
     * Maximum width of the merchant identifier.
     *
     * <p>Assumptions: {@code PA-MERCHANT-ID PIC X(15)} at {@code cpy/CIPAUDTY.cpy} L39 is the
     * value moved to the screen by {@code cbl/COPAUS1C.cbl} L353.
     */
    private static final int MERCHANT_ID_WIDTH = 15;

    /**
     * Maximum width of the merchant city.
     *
     * <p>Assumptions: {@code PA-MERCHANT-CITY PIC X(13)} at {@code cpy/CIPAUDTY.cpy} L41 is
     * normative, rather than the 25 cells reserved by the symbolic map.
     */
    private static final int MERCHANT_CITY_WIDTH = 13;

    /**
     * Maximum width of the merchant state.
     *
     * <p>Assumptions: {@code PA-MERCHANT-STATE PIC X(02)} at {@code cpy/CIPAUDTY.cpy} L42 is the
     * two-character value moved to the screen by {@code cbl/COPAUS1C.cbl} L355.
     */
    private static final int MERCHANT_STATE_WIDTH = 2;

    /**
     * Maximum width of the merchant postal code.
     *
     * <p>Assumptions: {@code PA-MERCHANT-ZIP PIC X(09)} at {@code cpy/CIPAUDTY.cpy} L43 is
     * normative, rather than the ten cells reserved by the symbolic map.
     */
    private static final int MERCHANT_ZIP_WIDTH = 9;

    /**
     * Maximum width of the screen message.
     *
     * <p>Assumptions: both halves of {@code cpy-bms/COPAU01.cpy} declare 78 positions at L180
     * and L344, and {@code cpy-bms/COPAU00.cpy} corroborates that width at L390 and L764.
     */
    private static final int MESSAGE_WIDTH = 78;
}
