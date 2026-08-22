package com.carddemo.transaction.dto;

import com.carddemo.common.money.Money;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.web.CursorToken;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.regex.Matcher;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The capture payload of the migrated transaction-add screen, carrying the twelve transaction
 * record fields an operator supplies together with one key alternative and one confirmation.
 *
 * <p><b>Purpose.</b> This is the request body of the transaction-add submission. A controller in
 * {@code com.carddemo.transaction.api} binds and validates it, and
 * {@code com.carddemo.transaction.service} consumes the validated value. Beyond the two
 * cross-component predicates declared at the foot of this file it holds no logic, reaches nothing
 * and performs no lookup: every constraint here is decidable from the submitted values alone,
 * which is what keeps representation concerns of the reference record format in the one package
 * permitted to hold them. The reference COBOL is the specification, so this shape encodes the
 * field set, the widths, the scale and the validation rules that specification already states
 * rather than redefining them.
 *
 * <h2>Where the component set comes from, and where the order comes from</h2>
 *
 * <p>Two different questions have two different answers, and conflating them is what would make
 * the ordering below look arbitrary. The symbolic map {@code app/cpy-bms/COTRN02.CPY} declares
 * fourteen operator-supplied fields between {@code ACTIDINI PIC X(11)} at line 60 and
 * {@code CONFIRMI PIC X(1)} at line 138, and that set is what establishes <em>which</em> values
 * this request carries. The copybook declaration at {@code app/cpy/CVTRA05Y.cpy} lines 6 to 17 is
 * what establishes the order the twelve record-derived components are declared in here.
 *
 * <p>Alternatives Considered: declaring all fourteen in the order the reference screen keys them
 * was evaluated and rejected. The two orders genuinely differ rather than coinciding -- the map
 * places the card number second at line 66 and the description sixth at line 90, while the
 * copybook declares the card number eleventh at line 15 and the description fifth at line 9 -- so
 * a choice had to be made and either order would compile. Transformation rule T1 makes the
 * copybook normative, and the screen order is a property of the reference presentation: it is the
 * keying order of a fixed-position terminal, which is the layout concern of whichever client
 * renders the form and not a property of the record. Ordering the record-derived components by the
 * copybook means this request, the sibling response, the persistence entity, the database columns
 * and the fixed-width extract all enumerate the same fields in the same sequence, so a reader
 * comparing any two of them is comparing like with like.
 *
 * <p>Assumptions: the two components that are not record-derived sit outside that sequence
 * deliberately, one at each end. The account identifier comes first because it is not a
 * transaction record field at all -- the copybook declares no account field -- but the key
 * alternative through which the card number is resolved. The confirmation comes last because it is
 * a screen control rather than data: {@code app/cbl/COTRN02C.cbl} lines 169 to 188 branch on it to
 * decide whether the add is performed at all, and no byte of it reaches the record.
 *
 * <p>Assumptions: the record arithmetic is self-checking, which is what lets every width below be
 * verified rather than trusted. Summing the widths that copybook declares in declaration order --
 * 16, 2, 4, 10, 100, 11, 9, 50, 50, 10, 16, 26, 26 and 20 -- gives exactly 350, and its own header
 * comment at line 2 records the record length as 350. A width here that disagreed with it would be
 * wrong by construction rather than by opinion. The monetary field accounts for eleven of those
 * bytes, being nine integer digits and two decimal places held as zoned decimal.
 *
 * <p>Assumptions: two record fields have no component here and each omission has its own reason.
 * {@code FILLER PIC X(20)} at line 18 pads the record to its declared length and carries no value
 * a caller can supply. {@code TRAN-ID PIC X(16)} at line 5 is generated rather than submitted, as
 * the next paragraph records.
 *
 * <p>Alternatives Considered: a transaction-identifier component was evaluated and rejected, and
 * the reference material settles it twice over. The symbolic map declares no transaction-identifier
 * field at all -- the fourteen operator-supplied fields from line 60 to line 138 do not include
 * one -- so there is no inbound value for a component to carry. The identifier is instead minted
 * server-side: {@code app/cbl/COTRN02C.cbl} opens its add paragraph at line 442, moves high values
 * into the record key at line 444, browses at line 445 and reads backwards at line 446 to reach
 * the highest existing key, then adds one at line 449. Accepting an identifier from a client would
 * additionally let a client choose where its record lands in the key space, which the reference
 * program never permits.
 *
 * <p>Assumptions: that minting sequence holds no lock, which is why the identifier is generated
 * behind this boundary rather than in front of it. {@code app/cbl/COBIL00C.cbl} lines 212 to 217
 * perform the identical sequence -- high values, browse, read backwards, end browse, move, add one
 * -- with no read-for-update anywhere in it, so two submissions running together can read the same
 * maximum. Whatever resolves that contention is a concern of the writing service, and a component
 * here would put a client inside it.
 *
 * <h2>At least one key field is supplied, and the account identifier wins</h2>
 *
 * <p>{@code app/cbl/COTRN02C.cbl} lines 193 to 230 encode a three-way branch, and it is the single
 * most consequential rule this record carries. When the account identifier is present it must be
 * numeric, failing which line 199 reports {@code 'Account ID must be Numeric...'}; the program then
 * reads the cross-reference by account at line 208 and moves the card number it finds into the card
 * field at line 209. When the account identifier is absent and the card number present, the card
 * number must be numeric, failing which line 213 reports
 * {@code 'Card Number must be Numeric...'}; the program reads the cross-reference by card at line
 * 222 and moves the account identifier it finds into the account field at line 223. When neither is
 * present, line 226 reports {@code 'Account or Card Number must be entered...'}.
 *
 * <p>Assumptions: whichever key is supplied, the other is a lookup result rather than an input.
 * Lines 209 and 223 are the evidence: each writes the field the operator did not fill from the
 * cross-reference record. Both components here are therefore individually nullable, and the
 * constraint that binds them is that at least one arrives.
 *
 * <p>Alternatives Considered: three ways of expressing that pairing rule were considered, and
 * the reason two were rejected is mechanical rather than stylistic. It cannot be expressed by
 * component ordering, which carries no constraint at all, nor by a per-component annotation, since
 * neither component can see the other. Leaving it to the service layer was rejected because the
 * violation would then be raised after binding and outside the accumulated violation set, so a
 * submission with no key and three other defects would answer with two separate shapes on two
 * separate paths. What is used instead is {@link AtLeastOneKey}, a class-level constraint declared as
 * a NESTED annotation of this record: a class-level constraint is evaluated in the same pass as every
 * component constraint, so the violation joins the same accumulated set and reaches the same per-field
 * array, and its validator attributes the violation to {@code accountId} and {@code cardNumber} so a
 * client can display it beside the two inputs it concerns.
 *
 * <p>Alternatives Considered: declaring that annotation and its validator as files of their own, which
 * is the arrangement a reader is most likely to expect. Rejected because the sibling charter closes
 * this package's inventory at eight files, and the rule is a property of this one payload rather than a
 * shared constraint any other request could reuse, so nesting keeps the inventory closed and keeps the
 * rule beside the components it reads. Nesting the annotation inside the type it annotates is legal and
 * is exercised by this file's own declaration below.
 *
 * <p>Assumptions: the account path rewrites the value it was given. Lines 204 to 207 convert the
 * submitted characters to a number and move that number straight back into the same screen field,
 * which zero-pads it to the field's eleven characters. That round trip is direct evidence for the
 * component typing below, because it is the baseline itself moving between the character view and
 * the numeric view of one set of bytes.
 *
 * <h2>What this record validates, and what it deliberately leaves to the service layer</h2>
 *
 * <p>Assumptions: this record enforces presence, width, digit composition and lexical shape, and
 * nothing that requires reading anything else. The boundary is stated explicitly because the
 * reference program crosses it inside a single paragraph -- the one opening at
 * {@code app/cbl/COTRN02C.cbl} line 235 and closing at line 437 runs the presence chain, the
 * composition tests and the lexical tests and then calls the date-edit utility -- so a later author
 * following that paragraph would carry a lookup into a type that cannot perform one.
 *
 * <p>Semantic date validity is the clearest case and is <em>not</em> enforced here.
 * {@code app/cbl/COTRN02C.cbl} calls the date-edit utility with three arguments at lines 393 to
 * 395, tests the severity at line 397, and at line 400 tolerates one specific condition: when the
 * severity is non-zero the error is raised only if the message number is not {@code '2513'}. That
 * inner test has no alternative branch, closing at line 406, so a date failing the utility with
 * exactly that message number raises nothing at all. Reproducing a rule of that shape needs the
 * utility's own verdict, so leap years, month lengths and that tolerated condition belong to the
 * service layer through {@code com.carddemo.common.validation.DateEditValidator}. Its two messages
 * are {@code 'Orig Date - Not a valid date...'} at line 401 and
 * {@code 'Proc Date - Not a valid date...'} at line 421.
 *
 * <p>Assumptions: the utility's result area is eighty bytes and the layout is worth stating
 * accurately for whoever writes that service-layer call. {@code app/cbl/COTRN02C.cbl} lines 62 to
 * 69 declare the parameter block as a ten-character date, a ten-character format, and a result of a
 * four-character severity, an eleven-character {@code FILLER}, a four-character message number and
 * a sixty-one-character message. Those four sum to eighty and match
 * {@code 01 LS-RESULT PIC X(80)} at line 86 of {@code app/cbl/CSUTLDTC.cbl}. The {@code FILLER} is
 * part of that arithmetic and omitting it would misplace the message number by eleven bytes.
 *
 * <p>Assumptions: the cross-reference lookups and the write are likewise the service layer's, and
 * their messages are named here only to mark the boundary rather than to be carried:
 * {@code 'Account ID NOT found...'} at line 593, {@code 'Unable to lookup Acct in XREF AIX
 * file...'} at line 600, {@code 'Card Number NOT found...'} at line 626, {@code 'Unable to lookup
 * Card # in XREF file...'} at line 633, {@code 'Transaction ID NOT found...'} at line 657,
 * {@code 'Unable to lookup Transaction...'} at lines 664 and 693,
 * {@code 'Tran ID already exist...'} at line 738 and {@code 'Unable to Add Transaction...'} at line
 * 745. Each needs a record this request cannot see.
 *
 * <p>Assumptions: one confirmation outcome is a flow decision rather than a field error and is
 * also the service layer's. {@code app/cbl/COTRN02C.cbl} lines 169 to 188 perform the add for
 * {@code 'Y'} or {@code 'y'}; for {@code 'N'}, {@code 'n'}, spaces or low values line 178 asks
 * {@code 'Confirm to add this transaction...'}, which is a prompt for a further turn rather than a
 * complaint about a value; and for anything else line 184 reports
 * {@code 'Invalid value. Valid values are (Y/N)...'}, which is a value-domain complaint and is
 * carried here. Spaces and low values share that branch without being interchangeable in general:
 * they are two distinct byte states, an absent field and a blank one, and only their handling
 * coincides at this one site.
 *
 * <h2>One error per field, not one per condition</h2>
 *
 * <p>Assumptions: the amount and the two dates are each validated by a single disjunctive
 * construct whose alternatives share one action block, so the reference raises one message per
 * field however many of its conditions hold. The amount at lines 339 to 351 has four alternatives
 * -- line 340 rejects a leading character that is neither a minus nor a plus, line 341 rejects a
 * non-numeric run of eight digits from the second character, line 342 requires a decimal point at
 * the tenth and line 343 requires two numeric characters from the eleventh -- and all four fall
 * into the same block at lines 344 to 348. That describes a twelve-character shape of a sign, eight
 * digits, a point and two digits, which is exactly {@code TRNAMTI PIC X(12)} at line 96 of the map.
 * Each date at lines 353 to 366 and 368 to 381 has five alternatives sharing one block, requiring
 * four numeric characters, a hyphen, two numeric characters, a hyphen and two numeric characters,
 * which is the ten-character shape of {@code TORIGDTI PIC X(10)} at line 102 and
 * {@code TPROCDTI PIC X(10)} at line 108.
 *
 * <p>Alternatives Considered: decomposing each of those into one constraint per alternative was
 * evaluated and rejected, because it would change observable behaviour. A malformed amount would
 * answer with up to four entries for one field where the reference answers with one, and a client
 * rendering the array against the form would show four complaints beside a single input. Each is
 * therefore one constraint whose expression covers the whole shape, and each carries the one
 * message the reference emits: {@code 'Amount should be in format -99999999.99'} at line 345,
 * {@code 'Orig Date should be in format YYYY-MM-DD'} at line 360 and
 * {@code 'Proc Date should be in format YYYY-MM-DD'} at line 375.
 *
 * <p>Assumptions: those three messages end without the trailing ellipsis that almost every other
 * message on these screens carries, and the difference is transcribed rather than tidied. Each
 * ends with the format specimen it names. Transformation rule T8 carries every user-visible string
 * across character for character, so normalising the punctuation of three of them would be a
 * silent behavioural change.
 *
 * <p>Assumptions: a blank field raises the empty message alone and never the composition message
 * as well, matching the reference, where the two live in separate constructs at lines 251 to 320
 * and 322 to 336 and the first sends the screen before the second is reached. Every digit and
 * shape expression here therefore admits the empty spelling, leaving blankness to the presence
 * constraint. A submission blank in one field and malformed in another still reports both, one entry
 * each.
 *
 * <p>Refactoring Rationale: the admitted spelling of blankness is an absent value or an EMPTY one,
 * and an earlier revision also admitted a value made up entirely of white space. The reason it did
 * was sound on the terminal -- the reference selects on a field being spaces or low values, and a
 * fixed-position screen delivers an untouched field as spaces -- and it does not carry over to
 * HTTP, where an unfilled value arrives as an omitted member or an empty string and never as a run
 * of pad bytes. The white-space arm therefore described a value no client sends while widening every
 * expression that carried it, and it left this record disagreeing with
 * {@code openapi/transaction-api.yaml}, which admits no such spelling for any of these fields.
 * The private presence test below still folds white space into absence where a rule asks whether a
 * value was supplied at all, because that question is about a caller's intent rather than about a
 * value's shape.
 *
 * <h2>Accumulation replaces short-circuiting, and the baseline warrants it</h2>
 *
 * <p>Trade-offs: the reference short-circuits and this record accumulates, which is registered as
 * <b>D-ERROR-ACCUMULATION</b> in {@code docs/architecture/cobol-to-service-traceability.md} rather
 * than being an accident. Each reference check sets an error flag, moves one message,
 * positions the cursor and sends the screen immediately, so an operator sees exactly one error per
 * turn even when several fields are wrong. Declarative validation evaluates every constraint and
 * collects every violation, and transformation rule T7 asks for a per-field error array, so
 * accumulation is the intended target shape. What is accepted is that a submission with four
 * defects answers with four entries where the reference would have answered four times with one;
 * what is bought is that a client fixes the form in one pass instead of four round trips.
 *
 * <p>Assumptions: the baseline itself already separates these two channels, which is why the
 * ruling is that the array accumulates while the aggregate message latches to the first failure.
 * {@code app/cpy/CSUTLDPY.cpy} sets its per-field flags unconditionally -- at lines 216 and 217,
 * 231 and 232, 260 to 262, and 302 to 304 -- while guarding each aggregate message behind a
 * message-off test at lines 218, 233, 263 and 305, so the first failure keeps the summary and the
 * later ones still record their own flags. The reference behaviour is not a defect and is not
 * described as one: the target keeps both channels and reads the aggregate from the first entry.
 *
 * <p>Assumptions: the array is keyed by field identifier because the reference says which field
 * failed by moving minus one into that field's length subfield, which is how the terminal places
 * the cursor on the offending field. {@code app/cbl/COTRN02C.cbl} does that at lines 347, 362, 377,
 * 404, 424 and 434 among other sites. The key carries exactly that information across;
 * {@code com.carddemo.common.error.ApiError} declares the entry as a field name, a validation state
 * and a message, so the identifier is where the cursor instruction lands. The minus one itself is a
 * terminal mechanism and is not a component of this record.
 *
 * <p>Assumptions: a blank field is a refinement of an erroneous field rather than an alternative to
 * one. {@code app/cpy/CSSETATY.cpy} lines 18 and 19 form a single disjunctive test over the not-ok
 * flag and the blank flag, so the highlight fires for either. A caller therefore asks
 * {@code com.carddemo.common.validation.FieldValidationFlag} whether a field is in error and treats
 * blank as a narrowing of that answer, rather than comparing the flag to the blank state as though
 * the two were exclusive.
 *
 * <p>Assumptions: the literal asterisk the reference writes into a blank field is presentational,
 * and that copybook proves it by writing its two effects into two different places. Line 21 moves
 * the red attribute into the field's colour subfield at line 22, while lines 24 and 25 move the
 * asterisk into the field's output subfield. The marker is a property of the error entry and is
 * never a character prepended to a value component here.
 *
 * <h2>Money is exact fixed point and travels as a JSON string</h2>
 *
 * <p>Alternatives Considered: typing the amount as a bare arbitrary-precision decimal was
 * evaluated and rejected, and the reason is the type rather than the value.
 * {@code com.carddemo.common.money.MoneyModule} binds both its serialiser and its deserialiser to
 * the {@code Money} type itself, so a component declared as a bare decimal is reached by neither
 * and is read and written as a plain JSON number instead: it compiles, it runs, its arithmetic is
 * exact, and its wire form is wrong. That failure mode is named here because no assertion about
 * the value would localise it -- only an assertion about the serialised text would. Most clients
 * parse a JSON number into an IEEE-754 binary floating point value on receipt, and binary64 cannot
 * exactly represent the two-place decimal fractions this field is built from, so the loss lands in
 * the cents at the one hop a user actually reads. {@code TRAN-AMT PIC S9(09)V99} at line 10 of
 * {@code app/cpy/CVTRA05Y.cpy} is eleven significant decimal digits, which leaves no margin for an
 * approximation.
 *
 * <p>Assumptions: that module is registered by this module's own configuration, outside this
 * package, because component scanning rooted at {@code com.carddemo.transaction} reaches nothing
 * beneath {@code com.carddemo.common}. This record does not register it and does not annotate
 * around it.
 *
 * <p>⚠️ Assumptions: the {@code amount} component is nonetheless declared as CHARACTERS and carries
 * its own reader, which does not contradict the paragraphs above -- it is the same rule applied one
 * step earlier. What travels on the wire is still exactly a JSON string of two-place decimal text,
 * and a JSON number is still refused; the difference is only that the refusal now happens in a
 * CONSTRAINT that can name the field rather than in a reader that cannot. The parsed value is
 * reached through {@link #amountValue()}, so every consumer downstream still works in the shared
 * money type and no arithmetic anywhere in this service sees a bare decimal or a floating-point
 * value. The sibling responses declare the money type directly, because a response is composed by
 * this service and has no submitted characters to preserve.
 *
 * <h2>Identifiers are digits-validated strings, never numeric types</h2>
 *
 * <p>Assumptions: the baseline settles this itself, declaring each identifier twice over the same
 * bytes -- once as characters and once as a number. {@code app/cpy/CVCRD01Y.cpy} gives
 * {@code CC-ACCT-ID PIC X(11)} at line 34 with {@code CC-ACCT-ID-N PIC 9(11)} redefining it at line
 * 36, {@code CC-CARD-NUM PIC X(16)} at line 37 with its numeric redefinition at line 39, and
 * {@code CC-CUST-ID PIC X(09)} at line 40 with its numeric redefinition at line 42. The character
 * declaration is what the screen and the message carry; the numeric one exists so that arithmetic
 * can reach the same bytes. The zero-padded rewrite at lines 204 to 207 of the reference program is
 * that same duality in motion.
 *
 * <p>Assumptions: the reference seed extract settles it beyond the declaration.
 * {@code app/data/ASCII/dailytran.txt} holds 300 records, and 30 of them carry a card number whose
 * first character is a zero. A numeric component would discard that leading zero on the way out
 * while still comparing equal on the way in, which is a silent corruption of a tenth of that
 * extract. No example value is reproduced here: a primary account number does not belong in source
 * prose even when it comes from a seed extract, and the aggregate is what the type decision rests
 * on. The symbolic map agrees with the type choice, declaring {@code TCATCDI PIC X(4)} at line 78
 * and {@code MIDI PIC X(9)} at line 114 where the record declares those same two fields
 * {@code PIC 9(04)} and {@code PIC 9(09)}, so even the reference presentation treats them as
 * characters.
 *
 * <p>Assumptions: the transaction type code is declared as characters in the record and
 * constrained to digits by the screen, and both halves of that are transcribed rather than
 * reconciled. {@code TRAN-TYPE-CD} is {@code PIC X(02)} at line 6 of the copybook, yet lines 322 to
 * 336 of the reference program reject a non-numeric value in it with
 * {@code 'Type CD must be Numeric...'} at line 325. The record's width and the screen's composition
 * rule are two separate constraints from two separate sources, and this component carries both. It
 * is not an inconsistency to correct.
 *
 * <h2>The amount carries the record's nine integer digits, not the screen's eight</h2>
 *
 * <p>{@code TRAN-AMT} holds nine integer digits and the reference screen accepts eight, which is
 * provable byte by byte rather than by inference. Line 341 validates only eight digits from the
 * second character; lines 383 and 384 parse the field into {@code WS-TRAN-AMT-N}, declared
 * {@code PIC S9(9)V99} at line 58 with nine integer digits; line 385 moves that into
 * {@code WS-TRAN-AMT-E}, declared {@code PIC +99999999.99} at line 59 with eight; and line 386
 * writes that narrower edited form back over the screen field. This request accepts all nine, which
 * is registered as <b>D-AMOUNT-RECORD-WIDTH</b> in
 * {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>Assumptions: the edited widths differ between reference screens deliberately, so neither is a
 * general rule to generalise from. {@code app/cbl/COBIL00C.cbl} declares the same eight-digit
 * transaction-amount edit at line 55 and a ten-digit {@code WS-CURR-BAL PIC +9999999999.99} at line
 * 56, matching an account balance rather than a transaction amount. Reading the eight-digit edit as
 * the width of money in general would truncate two digits of a value the reference records
 * demonstrably hold.
 *
 * <p>Assumptions: the shared money type's own domain is wider than this field's and cannot serve as
 * the bound. It admits ten integer digits, because it also carries account balances, so a
 * ten-digit amount would be constructed successfully and would then not fit the nine-digit record.
 * {@link AmountWithinRecordDomain} below closes that gap on the {@code amount} component itself, and
 * its validator measures the SUBMITTED CHARACTERS against {@link #AMOUNT_WIRE_FORM} so that no
 * arbitrary-precision decimal appears anywhere in this file and no normalising parse runs ahead of
 * the measurement.
 *
 * <p>Trade-offs: the message that reports an over-wide amount names the eight-digit specimen while
 * this record accepts nine, and the mismatch is accepted rather than edited away. The reference
 * emits exactly that message for an over-wide integer part -- a ninth integer digit displaces the
 * decimal point from the tenth character, so the test at line 342 fails and line 345 is what the
 * operator sees -- so it is the correct message for the condition. Rewording it to name nine digits
 * was rejected because transformation rule T8 carries user-visible strings across character for
 * character, and an edited message is a behavioural change rather than a structural one. Verification
 * of that string is by transcription against the cited reference lines, not by golden master: the
 * source program is an online CICS program, and {@code tests/README.md} records at its lines 83 to 85
 * that such programs cannot be run end to end without a CICS runtime, so no golden-master oracle
 * exists for this path.
 *
 * <h2>Widths are the record's, and each narrowing is registered</h2>
 *
 * <p>The framing below is the only one used: the baseline does one thing, the Java implements
 * another, and the divergence is documented in the migration traceability register rather than
 * introduced silently. The three narrowed text fields are registered together as
 * <b>D-TEXT-RECORD-WIDTH</b> in {@code docs/architecture/cobol-to-service-traceability.md}, and the
 * amount as <b>D-AMOUNT-RECORD-WIDTH</b> above. The repository states the same discipline for its
 * own suite at
 * {@code tests/README.md} lines 555 and 556, which encode the specification rather than redefine
 * it. Nothing under {@code app/} is edited by this record or by any statement in this docstring.
 *
 * <p>Three text fields are narrowed by the reference presentation and are carried here at record
 * width: the description at {@code PIC X(100)} appears as {@code TDESCI PIC X(60)} at line 90 of
 * the map, the merchant name at {@code PIC X(50)} appears as {@code MNAMEI PIC X(30)} at line 120,
 * and the merchant city at {@code PIC X(50)} appears as {@code MCITYI PIC X(25)} at line 126. The
 * merchant postal code is the one field of the four the screen does not narrow, matching the record
 * at {@code MZIPI PIC X(10)} on line 132.
 *
 * <p>Trade-offs: accepting record widths admits input the reference terminal could not have keyed
 * -- a hundred-character description, or a nine-digit amount. The alternative, constraining each
 * component to its display width, was rejected because it would discard capacity the record
 * demonstrably holds and would make the request depend on which screen happened to submit it. What
 * is accepted is that a client rendering into a fixed-width column has to decide for itself what to
 * do with the surplus.
 *
 * <p>Trade-offs: for the two dates and the confirmation the width constraint and the shape
 * constraint overlap, so a value longer than the field reports against both. The width constraint
 * is retained all the same, because transformation rule T1 makes the copybook width normative and
 * dropping it would leave the record's declared width unstated on the component that carries it.
 * The overlap is unreachable from the reference screen, whose fields are ten and one characters
 * wide.
 *
 * <p>Assumptions: no verbatim message exists for an over-long value, so the framework default is
 * used rather than one invented here. Every field of the reference map is declared at a fixed
 * width, {@code TDESCI PIC X(60)} at line 90 through {@code CONFIRMI PIC X(1)} at line 138, so a
 * longer value could not be keyed and the reference program has no message for the condition.
 * Fabricating one would put text in front of a user that no specification states.
 *
 * <h2>What this record deliberately does not carry</h2>
 *
 * <p>Refactoring Rationale: the mechanism this record replaces is the pseudo-conversational
 * exchange the reference add screen performs, and what was wrong with it is that presentation
 * depended on remembered turn state. {@code app/cpy/COCOM01Y.cpy} declares the communication area
 * at line 19 and a re-entry discriminator at line 29 with condition names for first entry and
 * re-entry at lines 30 and 31, and that structure is storage the client hands back on the following
 * turn. {@code app/cpy/CSSETATY.cpy} line 20 then gates the field highlight on precisely that
 * discriminator, so whether an error was shown at all depended on a counted turn rather than on the
 * validity of the submission. This record carries a single confirmation value and no turn state of
 * any kind, and none may be added -- no resubmission flag, no first-entry flag and no turn counter.
 * A stateless handler answering with a per-field error array has no first-entry-versus-re-entry
 * distinction left to make, so error presentation here is driven by the response body alone.
 *
 * <p>Assumptions: no ordinal component of any kind appears here and none is implied by any
 * statement above. This is a single-record capture with nothing to traverse; the list request in
 * this package is where a cursor applies, and the shared envelope that carries one is never
 * redeclared.
 *
 * <p>Assumptions: none of the framing fields the reference map carries appears here. The map
 * declares them ahead of the operator-supplied block, and they are a transaction and program name
 * that identify the reference transaction monitor, two title constants belonging to the user
 * interface header, and a date and time that are clock reads rendered by the client. The error
 * message field {@code ERRMSGI PIC X(78)} at line 144 is an output field of that map and has no
 * component here either, because a rejected submission answers through the shared error type.
 *
 * <p>Assumptions: no card verification value component exists on this type and none may be added.
 * No endpoint in this migration accepts or returns one, and the reference transaction record
 * declares none in the first place. Nothing here carries a credential, a connection string or an
 * endpoint, and no message constant below echoes a submitted value.
 *
 * <p>Assumptions: this record imports no persistence entity from
 * {@code com.carddemo.transaction.domain} and no type from another service. The conversion to the
 * entity belongs to {@code com.carddemo.transaction.mapper}, and the prohibition on reaching across
 * that boundary is asserted by the ArchUnit layering rules this module runs against its own
 * classes rather than by convention.
 *
 * <h2>Why the constraints are declarative</h2>
 *
 * <p>Alternatives Considered: performing these checks imperatively in a compact canonical
 * constructor was evaluated and rejected for three concrete reasons. First, a constructor that
 * threw would throw during deserialisation, before the handler that owns the per-field error array
 * is reached, so the array would arrive empty for exactly the submissions it exists to describe.
 * Second, these annotations are the source the OpenAPI 3.1 contract this service publishes is
 * generated from, so a published width and an enforced width cannot drift apart. Third, a compact
 * constructor is a documentable node in its own right, and the documentation gate would then
 * require it to carry a tag set duplicating the fourteen at-clauses below.
 *
 * <p>Assumptions: the validation annotations are on this module's compile and runtime classpath.
 * They do not arrive transitively from the shared kernel, which marks the validation starter
 * optional so that a module with no web tier does not inherit one;
 * {@code services/transaction-service/pom.xml} re-declares it for that reason. A component
 * annotated with a size constraint in a module that had not re-declared it would compile and then
 * fail at run time rather than at build time.
 *
 * <p>Alternatives Considered: Lombok and MapStruct were both evaluated and both rejected for this
 * package as a whole; {@code com.carddemo.transaction.dto}'s package charter carries the reasoning,
 * which turns on generated members being undocumentable and on copybook-to-transfer-object mapping
 * being non-mechanical. A Java 21 record with hand-written mapping is what replaces them.
 *
 * <h2>The documentation contract this record is held to</h2>
 *
 * <p>Trade-offs: this file is pure ASCII with no byte-order mark, and the restriction is
 * load-bearing rather than cosmetic. The house convention cited above is written at
 * {@code tests/README.md} line 548 with a non-breaking hyphen where an ordinary one is expected.
 * That character is indistinguishable from an ordinary hyphen on screen yet behaves differently in
 * a search, so copying the fourth label from there would turn it into a token a search for the
 * label fails to find. Everything drawn from that line is retyped rather than pasted, and
 * restricting the whole file to ASCII makes the failure mode unreachable.
 *
 * @param accountId the account the transaction is being captured against, as digit characters;
 *     not a transaction record field at all but the first of the two key alternatives, from
 *     {@code ACTIDINI PIC X(11)} at line 60 of {@code app/cpy-bms/COTRN02.CPY}; at most eleven
 *     characters and digits-only when present, and null when only the card number was supplied, in
 *     which case it is derived from the cross-reference
 * @param typeCode the two-character transaction type, as digit characters, from
 *     {@code TRAN-TYPE-CD PIC X(02)} at line 6 of {@code app/cpy/CVTRA05Y.cpy}; required, at most
 *     two characters, and digits-only despite the record declaring it as characters
 * @param categoryCode the four-digit category within that type, as digit characters, from
 *     {@code TRAN-CAT-CD PIC 9(04)} at line 7; required, at most four characters and digits-only,
 *     borne as characters because the map declares this field {@code X(4)} and because a numeric
 *     form would drop a leading zero the four-digit key space allows
 * @param source the ten-character channel the transaction originated through, as text, from
 *     {@code TRAN-SOURCE PIC X(10)} at line 8; required, at most ten characters, and restricted to
 *     the printable domain {@link #PRINTABLE_TEXT} declares
 * @param description the free-text narrative of the transaction, as text, from
 *     {@code TRAN-DESC PIC X(100)} at line 9; required and at most the record's hundred characters,
 *     which is wider than the sixty the screen keys at {@code TDESCI} on line 90 of the map, and
 *     restricted to the printable domain {@link #PRINTABLE_TEXT} declares
 * @param amount the monetary value of the transaction, as the shared exact-decimal money type,
 *     from {@code TRAN-AMT PIC S9(09)V99} at line 10; required, exact at a scale of two, bounded to
 *     the record's nine integer digits rather than the screen's eight, and carried on the wire as a
 *     quoted decimal string and never as a JSON number
 * @param merchantId the nine-digit identifier of the merchant, as digit characters, from
 *     {@code TRAN-MERCHANT-ID PIC 9(09)} at line 11; required, at most nine characters and
 *     digits-only, borne as characters for the same reason as the category code
 * @param merchantName the merchant's name, as text, from
 *     {@code TRAN-MERCHANT-NAME PIC X(50)} at line 12; required and at most the record's fifty
 *     characters, which is wider than the thirty the screen keys at {@code MNAMEI} on line 120, and
 *     restricted to the printable domain {@link #PRINTABLE_TEXT} declares
 * @param merchantCity the merchant's city, as text, from
 *     {@code TRAN-MERCHANT-CITY PIC X(50)} at line 13; required and at most the record's fifty
 *     characters, which is wider than the twenty-five the screen keys at {@code MCITYI} on line 126,
 *     and restricted to the printable domain {@link #PRINTABLE_TEXT} declares
 * @param merchantZip the merchant's postal code, as text, from
 *     {@code TRAN-MERCHANT-ZIP PIC X(10)} at line 14; required, at most ten characters and
 *     restricted to the printable domain {@link #PRINTABLE_TEXT} declares, and the one field of
 *     these four the screen keys at full record width
 * @param cardNumber the card the transaction is presented on, as digit characters, from
 *     {@code TRAN-CARD-NUM PIC X(16)} at line 15; the second of the two key alternatives, at most
 *     sixteen characters and digits-only when present, and derived from the cross-reference when the
 *     account identifier was supplied -- whether this component was supplied alongside it or not,
 *     because the account arm resolves first and line 209 overwrites whatever arrived here
 * @param originDate the date the transaction was originated, as text in the form
 *     {@code YYYY-MM-DD}; the ten-character date portion of {@code TRAN-ORIG-TS PIC X(26)} at line
 *     16, keyed at {@code TORIGDTI PIC X(10)} on line 102 of the map; required, at most ten
 *     characters, checked here for that lexical shape alone and for semantic validity by the
 *     service layer
 * @param processDate the date the transaction is to be processed, as text in the form
 *     {@code YYYY-MM-DD}; the ten-character date portion of {@code TRAN-PROC-TS PIC X(26)} at line
 *     17, keyed at {@code TPROCDTI PIC X(10)} on line 108 of the map; required, at most ten
 *     characters, and checked to the same depth as the origination date
 * @param confirmation the operator's confirmation, as a single character; a screen control rather
 *     than a record field, from {@code CONFIRMI PIC X(1)} at line 138 of the map; at most one
 *     character and restricted to the affirmative and negative characters the reference recognises,
 *     null or blank when the submission is the first turn of the confirmation exchange
 * @param confirmationToken the opaque binding token a preview published, echoed back so the write is
 *     bound to the resolution the operator was shown; optional, and null or blank on a submission that
 *     confirms in one turn, which is what the reference itself does. It has NO baseline counterpart --
 *     a terminal turn carries its own context, so the reference never has to name which earlier answer
 *     a confirmation belongs to -- and it replaced the unmasked primary account number the preview used
 *     to publish for the same purpose. Bounded and shaped by
 *     {@code com.carddemo.common.web.CursorToken}, and interpreted only by the service that holds the
 *     sealing key
 */
@TransactionAddRequest.AtLeastOneKey
public record TransactionAddRequest(
    // WHY : Assumptions: COTRN02C.cbl line 209 fills the card number from the cross-reference when
    //       this field is the one supplied, so requiring both would reject a submission the
    //       reference accepts. The pairing rule is carried by the AtLeastOneKey constraint on this
    //       record, whose validator is the only place either component can see the other, and which
    //       reports its violation against this component and cardNumber rather than against a name no
    //       submitted field carries.
    @Size(max = ACCOUNT_ID_WIDTH)
    @Pattern(regexp = ACCOUNT_ID_DIGITS, message = ACCOUNT_ID_NOT_NUMERIC)
    String accountId,
    @NotBlank(message = TYPE_CODE_REQUIRED)
    @Size(max = TYPE_CODE_WIDTH)
    // WHY : Assumptions: CVTRA05Y.cpy line 6 declares PIC X(02) while COTRN02C.cbl lines 322 to
    //       336 reject a non-numeric value with the message at line 325. Two sources state two
    //       constraints and both are carried; neither is treated as an error in the other.
    @Pattern(regexp = TYPE_CODE_DIGITS, message = TYPE_CODE_NOT_NUMERIC)
    String typeCode,
    @NotBlank(message = CATEGORY_CODE_REQUIRED)
    @Size(max = CATEGORY_CODE_WIDTH)
    @Pattern(regexp = CATEGORY_CODE_DIGITS, message = CATEGORY_CODE_NOT_NUMERIC)
    String categoryCode,
    // WHY : ⚠️ Refactoring Rationale: this member and the four free-text members below now declare a
    //       CHARACTER DOMAIN, where every one of them previously declared only presence and width. The
    //       five are the externally authored PIC X fields of this capture -- every other member is
    //       already closed by a digit, date, amount or single-character expression -- so they were the
    //       whole of the request's unbounded text surface, and two sinks made that surface load-bearing.
    //       A carriage return or line feed stored in one of them is copied verbatim into the plain-text
    //       statement's eighty-column bands and the transaction report's 133-column records, neither of
    //       which has an escaping mechanism, so one stored value becomes two records and every reader
    //       that counts records is wrong. A code point US-ASCII cannot represent is stored successfully
    //       and then refused by FixedWidthCodec on a LATER run, where it is no longer attributable to a
    //       request. PRINTABLE_TEXT above records why its span is exactly what both sinks can carry.
    // WHY : Assumptions: the constraint is declared here rather than only in the service layer so that
    //       its violation joins the same accumulated set as every other component constraint and reaches
    //       the same per-field array that transformation rule T7 requires. The service layer applies the
    //       same predicate as well, and that is not duplication: TransactionAddService assembles a
    //       capture directly from stored values on the copy-last path, which no boundary constraint sees.
    @NotBlank(message = SOURCE_REQUIRED)
    @Size(max = SOURCE_WIDTH)
    @Pattern(regexp = PRINTABLE_TEXT, message = SOURCE_NOT_PRINTABLE)
    String source,
    // WHY : Trade-offs: COTRN02.CPY line 90 keys sixty and CVTRA05Y.cpy line 9 holds a hundred.
    //       Constraining to sixty would discard capacity the record demonstrably holds; the cost is
    //       that a client rendering a fixed-width column may receive more than it can show.
    // WHY : ⚠️ Refactoring Rationale: an earlier revision of this block argued that NO character-set
    //       constraint should be declared here, on the reading that CVTRA05Y.cpy line 9 declares
    //       TRAN-DESC PIC X(100) and so "admits every character in the code page". The premise was true
    //       and the conclusion did not follow: the code page is a SINGLE-BYTE one, and the reference's
    //       only writer of this field is the unprotected 3270 field TDESC at line 161 of
    //       app/bms/COTRN02.bms, so a supplementary Unicode code point and a C0 control were never
    //       accepted values here -- they are values the HTTP boundary introduced. Refusing them
    //       restores the reference's own domain rather than narrowing it, which is why rule T9 does not
    //       apply: there is no behavioural change to register as a divergence.
    // WHY : Trade-offs: the earlier revision also concluded that safety belongs entirely to output
    //       encoding at the sink, and that reasoning survives for the sink it was written about and for
    //       no other. reporting-service's StatementHtmlMapper does escape every value it embeds, and
    //       the divergence that creates is still registered as D-STMT-HTML-ESCAPING in
    //       docs/architecture/cobol-to-service-traceability.md -- markup needs escaping and this shape
    //       cannot know it is heading for markup. What the reasoning cannot cover is a fixed-width
    //       sink: the plain-text statement's eighty-column bands and the report's 133-column records
    //       have no escape sequence to encode a line feed INTO, so there is no sink-side control to
    //       delegate to and the only place the value can be refused is here. Sink-side escaping and a
    //       boundary domain are answers to two different problems, and this field needs both.
    @NotBlank(message = DESCRIPTION_REQUIRED)
    @Size(max = DESCRIPTION_WIDTH)
    @Pattern(regexp = PRINTABLE_TEXT, message = DESCRIPTION_NOT_PRINTABLE)
    String description,
    // WHY : ⚠️ Refactoring Rationale: this component carries the SUBMITTED CHARACTERS and no longer
    //       the parsed money type, and the change is what makes a malformed amount reportable AS a
    //       field at all. The reference validates the twelve characters the operator keyed, position
    //       by position, at app/cbl/COTRN02C.cbl lines 339 to 351 and answers with one sentence at
    //       line 345. When this component was declared as the money type, every value the published
    //       TransactionAmount pattern refuses failed inside a DESERIALISER -- before any object
    //       existed to validate -- so the framework raised an unreadable-body failure and the answer
    //       was the shared malformed-request sentence with an EMPTY per-field array. A client was told
    //       its body could not be read and never told which member was wrong, which breaks
    //       transformation rule T7's per-field array and rule T8's verbatim sentence together, and a
    //       form could not draw its marker on the amount input because no entry named it. Binding the
    //       characters cannot fail, so the two constraints below run and each reports against amount.
    //       Alternatives Considered: recognising the deserialiser's mismatch in the shared advice and
    //       synthesising the entry there. Rejected because the sentence belongs to this service's
    //       catalogue and the shared kernel cannot know it, and because the advice's own message gate
    //       admits no digit run longer than four -- so "Amount should be in format -99999999.99"
    //       cannot travel that route and degrades to the generic sentence.
    //       Alternatives Considered: a second String component carrying the submitted text beside the
    //       parsed one. Rejected because the published request schema closes its object, so a
    //       fourteenth property would be refused by any strict client, and because two members
    //       describing one value can disagree.
    //       Alternatives Considered: tightening the shared money type's own grammar in common-lib so
    //       that every consumer inherits the strict form. Rejected because that grammar is
    //       deliberately lenient for a reason its own note records -- a value read back from a
    //       report's edit mask carries a leading plus that the reference itself wrote -- so narrowing
    //       it would break the codec paths that depend on the leniency. The narrowing belongs to this
    //       one request, which is where the contract narrows it.
    // WHY : Assumptions: the published contract declares this member a STRING with a pattern, so
    //       carrying it as characters is what the contract already describes rather than a relaxation
    //       of it. The parsed value is available from amountValue() below, which is what every
    //       consumer of the validated request reads, so no caller works with the raw text.
    // WHY : Refactoring Rationale: presence is asserted with the blank-intolerant constraint where
    //       the money type needed the null-intolerant one, because characters have a blank spelling
    //       and the reference tests for it: line 278 refuses SPACES as well as LOW-VALUES. An empty
    //       or all-space amount is therefore the reference's blank case and draws its own sentence
    //       with the blank state, which is the marker app/cpy/CSSETATY.cpy line 19 writes -- where a
    //       null-intolerant constraint would have passed it to the shape test and answered with the
    //       format sentence instead.
    // WHY : Assumptions: the shape constraint below accepts a blank value and leaves it to the
    //       presence constraint above, so exactly ONE violation is reported for one defect. Both
    //       firing would put two entries on one input, which the reference never does -- its eleven
    //       presence tests and its four shape alternatives are separate EVALUATE TRUE constructs, and
    //       each emits a single sentence.
    @NotBlank(message = AMOUNT_REQUIRED)
    @AmountWithinRecordDomain
    @JsonDeserialize(using = WireFormAmountDeserializer.class)
    String amount,
    @NotBlank(message = MERCHANT_ID_REQUIRED)
    @Size(max = MERCHANT_ID_WIDTH)
    @Pattern(regexp = MERCHANT_ID_DIGITS, message = MERCHANT_ID_NOT_NUMERIC)
    String merchantId,
    // WHY : Assumptions: the three merchant text members carry the same domain as the source and the
    //       description above and for the same two sinks, so one expression governs all five rather than
    //       three variants tuned per field. The ordinary punctuation of a merchant name -- ampersand,
    //       apostrophe, hyphen, period, comma, solidus -- is inside that domain, so nothing a producer
    //       legitimately sends is refused; PRINTABLE_TEXT records the measurement that establishes it.
    @NotBlank(message = MERCHANT_NAME_REQUIRED)
    @Size(max = MERCHANT_NAME_WIDTH)
    @Pattern(regexp = PRINTABLE_TEXT, message = MERCHANT_NAME_NOT_PRINTABLE)
    String merchantName,
    @NotBlank(message = MERCHANT_CITY_REQUIRED)
    @Size(max = MERCHANT_CITY_WIDTH)
    @Pattern(regexp = PRINTABLE_TEXT, message = MERCHANT_CITY_NOT_PRINTABLE)
    String merchantCity,
    @NotBlank(message = MERCHANT_ZIP_REQUIRED)
    @Size(max = MERCHANT_ZIP_WIDTH)
    @Pattern(regexp = PRINTABLE_TEXT, message = MERCHANT_ZIP_NOT_PRINTABLE)
    String merchantZip,
    // WHY : Assumptions: COTRN02C.cbl line 223 fills the account identifier from the
    //       cross-reference when this field is the one supplied, which is the mirror of line 209.
    //       Digits-only rather than numeric because 30 of the 300 card numbers in
    //       app/data/ASCII/dailytran.txt begin with a zero that a numeric type would discard.
    @Size(max = CARD_NUMBER_WIDTH)
    @Pattern(regexp = CARD_NUMBER_DIGITS, message = CARD_NUMBER_NOT_NUMERIC)
    String cardNumber,
    // WHY : Assumptions: COTRN02C.cbl lines 353 to 366 are five alternatives sharing a single
    //       action block, so the reference raises one message for a malformed date however many of
    //       its five conditions hold. Five separate constraints would answer with up to five
    //       entries for one input and change what a client renders.
    @NotBlank(message = ORIGIN_DATE_REQUIRED)
    @Size(max = DATE_WIDTH)
    @Pattern(regexp = ISO_DATE_SHAPE, message = ORIGIN_DATE_FORMAT)
    String originDate,
    @NotBlank(message = PROCESS_DATE_REQUIRED)
    @Size(max = DATE_WIDTH)
    @Pattern(regexp = ISO_DATE_SHAPE, message = PROCESS_DATE_FORMAT)
    String processDate,
    // WHY : Assumptions: COTRN02C.cbl lines 169 to 188 answer a blank confirmation with the prompt
    //       at line 178, which asks for another turn rather than complaining about a value, and
    //       answer any unrecognised character with the complaint at line 184. Only the second is a
    //       field error, so blank is admitted here and the prompt is the service layer's to emit.
    // WHY : Refactoring Rationale: the component is spelled confirmation, matching the property the
    //       published contract now declares and the sibling bill-payment request. A record component
    //       name is the wire name in this package -- no property-naming strategy and no
    //       per-property annotation is declared anywhere in it -- so the two spellings had to be
    //       reconciled in one direction or the other, and the wire spelling is the one a client and
    //       three contracts share. The alternative, annotating the component with its wire name, was
    //       rejected for the same reason as on the bill-payment request: one package-wide rule is
    //       cheaper to verify than a per-property exception.
    // WHY : Assumptions: both request bodies of this contract publish the member as confirmation
    //       against one shared single-character schema and set additionalProperties to false, so
    //       two spellings for one screen field would put a client in the position of sending a
    //       different name to two operations that read the same copybook field. With
    //       `spring.jackson.deserialization.fail-on-unknown-properties` true in this module's
    //       application.yml, the wrong one of the two would be refused outright.
    @Size(max = CONFIRM_WIDTH)
    @Pattern(regexp = CONFIRM_VALUES, message = CONFIRM_INVALID_VALUE)
    String confirmation,
    // WHY : ⚠️ Assumptions: this member is OPTIONAL and has no baseline counterpart at all, because the
    //       state it exists for has no baseline counterpart: the reference confirms inside one screen
    //       turn, so it never has to say which earlier answer a confirmation belongs to. Splitting that
    //       turn into two stateless requests is what created the question, and this is the answer to it.
    // WHY : Assumptions: the shape is checked and the VALUE is not interpreted here. Bounding the length
    //       and the alphabet at the boundary means a hostile caller cannot choose how much cryptographic
    //       work an open costs, while the decision about what the token names belongs to the service that
    //       holds the key -- so no constraint here can be mistaken for having verified it.
    // WHY : Alternatives Considered: carrying it as a request HEADER, in the manner of the account
    //       context's If-Match precondition. Rejected because a precondition header states a revision of
    //       the resource being written, and this states which earlier ANSWER is being confirmed; putting
    //       it in the body keeps it beside the confirmation character it qualifies, and keeps one
    //       exchange described in one place.
    @Size(max = CursorToken.MAX_TOKEN_LENGTH)
    @Pattern(regexp = CursorToken.SEALED_SHAPE_PATTERN, message = CONFIRMATION_TOKEN_MALFORMED)
    String confirmationToken) implements TransactionKeySelection {

  /**
   * The declared width of the account identifier, from {@code ACTIDINI PIC X(11)} at line 60 of
   * {@code app/cpy-bms/COTRN02.CPY} and matching {@code CC-ACCT-ID PIC X(11)} at line 34 of
   * {@code app/cpy/CVCRD01Y.cpy}.
   */
  public static final int ACCOUNT_ID_WIDTH = 11;

  /**
   * The declared width of the transaction type code, from {@code TRAN-TYPE-CD PIC X(02)} at line 6
   * of {@code app/cpy/CVTRA05Y.cpy}.
   */
  public static final int TYPE_CODE_WIDTH = 2;

  /**
   * The declared width of the transaction category code, from {@code TRAN-CAT-CD PIC 9(04)} at line
   * 7 of that copybook.
   */
  public static final int CATEGORY_CODE_WIDTH = 4;

  /**
   * The declared width of the transaction source, from {@code TRAN-SOURCE PIC X(10)} at line 8.
   */
  public static final int SOURCE_WIDTH = 10;

  /**
   * The declared width of the transaction description, from {@code TRAN-DESC PIC X(100)} at line 9,
   * which is the record's width and not the sixty characters the reference screen keys.
   */
  public static final int DESCRIPTION_WIDTH = 100;

  /**
   * The declared width of the merchant identifier, from {@code TRAN-MERCHANT-ID PIC 9(09)} at line
   * 11.
   */
  public static final int MERCHANT_ID_WIDTH = 9;

  /**
   * The declared width of the merchant name, from {@code TRAN-MERCHANT-NAME PIC X(50)} at line 12,
   * which is the record's width and not the thirty characters the reference screen keys.
   */
  public static final int MERCHANT_NAME_WIDTH = 50;

  /**
   * The declared width of the merchant city, from {@code TRAN-MERCHANT-CITY PIC X(50)} at line 13,
   * which is the record's width and not the twenty-five characters the reference screen keys.
   */
  public static final int MERCHANT_CITY_WIDTH = 50;

  /**
   * The declared width of the merchant postal code, from {@code TRAN-MERCHANT-ZIP PIC X(10)} at
   * line 14, the one merchant field the reference screen keys at full record width.
   */
  public static final int MERCHANT_ZIP_WIDTH = 10;

  /**
   * The declared width of the card number, from {@code TRAN-CARD-NUM PIC X(16)} at line 15 and
   * matching {@code CC-CARD-NUM PIC X(16)} at line 37 of {@code app/cpy/CVCRD01Y.cpy}.
   */
  public static final int CARD_NUMBER_WIDTH = 16;

  /**
   * The declared width of either submitted date, from {@code TORIGDTI PIC X(10)} at line 102 and
   * {@code TPROCDTI PIC X(10)} at line 108 of {@code app/cpy-bms/COTRN02.CPY}.
   *
   * <p>Assumptions: one constant serves both components because the two are the same width from the
   * same source, and each is the ten-character date portion of a twenty-six-character record
   * timestamp rather than the whole of it.
   */
  public static final int DATE_WIDTH = 10;

  /**
   * The declared width of the confirmation, from {@code CONFIRMI PIC X(1)} at line 138 of that map.
   */
  public static final int CONFIRM_WIDTH = 1;

  /**
   * The exact lexical form a submitted amount must occupy, as the published contract declares it.
   *
   * <p>Assumptions: this is the {@code TransactionAmount} pattern of
   * {@code openapi/transaction-api.yaml} -- an optional minus sign, one to nine integer digits, one
   * decimal point and exactly two fractional digits -- written out here because a request shape
   * cannot read its own contract document at bind time. {@code TransactionApiContractTest} asserts
   * that the published pattern is this expression between anchors, so the two cannot drift silently.
   *
   * <p>Assumptions: the nine integer digits are the RECORD's domain, from
   * {@code TRAN-AMT PIC S9(09)V99} at line 10 of {@code app/cpy/CVTRA05Y.cpy}, so this one
   * expression bounds the MAGNITUDE as well as the shape: no string it admits exceeds 999999999.99,
   * and none carries a scale other than two. The bound is narrower than the shared money type's own
   * domain, which admits ten integer digits because that type also carries account balances such as
   * {@code WS-CURR-BAL PIC +9999999999.99} at line 56 of {@code app/cbl/COBIL00C.cbl}.
   *
   * <p>⚠️ Refactoring Rationale: this expression REPLACES a magnitude bound in whole cents that an
   * earlier revision declared here and applied to the PARSED amount. The bound had to move from the
   * parsed value onto the submitted characters because a parse normalises: the shared money type
   * deliberately admits an under-padded fractional part, a leading plus and grouping separators, so
   * {@code "1234.5"} was accepted, rewritten to {@code 1234.50} and only then measured -- and any
   * arithmetic bound applied after that sees a value canonical by construction. Measuring the
   * characters SUBSUMES the cents bound rather than sitting beside it, which is why the cents bound
   * is gone rather than kept: two authorities on one field are what let the two disagree.
   *
   * <p>Assumptions: no anchor is written into the expression, because both places that apply it
   * match the whole string -- Bean Validation's pattern semantics and
   * {@link java.util.regex.Matcher#matches()} both do. Writing anchors in would also make it unequal
   * to the published pattern, which carries its own.
   */
  public static final String AMOUNT_WIRE_FORM = "-?[0-9]{1,9}\\.[0-9]{2}";

  /**
   * The compiled form of {@link #AMOUNT_WIRE_FORM}, held once for every request the service receives.
   *
   * <p>Assumptions: compiled from that constant rather than restated, so the expression this record
   * applies and the expression the contract test compares against the published schema are the same
   * characters and cannot diverge. It is compiled once as a class constant rather than per call
   * because a validator instance and a service call together apply it on every submission.
   */
  private static final java.util.regex.Pattern AMOUNT_WIRE_FORM_PATTERN =
      java.util.regex.Pattern.compile(AMOUNT_WIRE_FORM);

  /**
   * The expression an exactly-eleven-digit account identifier is held to, admitting absence.
   *
   * <p>Refactoring Rationale: the digit run is EXACT and an earlier revision of this record held
   * every digit field to one shared expression admitting ANY number of digits. Eleven is the
   * whole identifier -- {@code CC-ACCT-ID PIC X(11)} at line 34 of {@code app/cpy/CVCRD01Y.cpy},
   * matching {@code ACTIDINI PIC X(11)} at line 60 of {@code app/cpy-bms/COTRN02.CPY} -- and the
   * committed extract is zero-padded to it, so a shorter run is not a partial identifier but one
   * that matches no row. The published contract declares the same exact width, and an earlier
   * revision of the two disagreed: this record accepted three digits where
   * {@code openapi/transaction-api.yaml} refused them, so a request one side called valid the other
   * called malformed and nothing compared them.
   *
   * <p>Assumptions: the empty arm is retained because this component is individually optional --
   * at least one of it and the card number is supplied, which {@link AtLeastOneKey} decides -- so a
   * caller that supplies the other must be able to omit this one without drawing a composition
   * message for a field it deliberately left out. The same arm on a REQUIRED component, below,
   * exists for the neighbouring reason: the reference keeps presence and composition in separate
   * constructs -- the presence chain at {@code app/cbl/COTRN02C.cbl} lines 251 to 320 and the
   * composition tests at lines 322 to 336 -- and sends the screen from the first before reaching the
   * second, so a field left empty there never draws a composition message and must not draw one
   * here either.
   *
   * <p>Assumptions: white space mixed with digits is rejected rather than trimmed, which is what the
   * reference does. The class test it applies at lines 323 and 329 holds for a field of digits
   * throughout and fails for one carrying a space anywhere, so a partly filled digit field draws the
   * composition message there and draws it here.
   *
   * <p>Assumptions: the upper bound on length is carried by the width constraint on each component
   * rather than by these expressions, so an over-long value reports once.
   */
  public static final String ACCOUNT_ID_DIGITS = "|[0-9]{11}";

  /**
   * The expression an exactly-sixteen-digit card number is held to, admitting absence.
   *
   * <p>Assumptions: sixteen is {@code CARD-NUM PIC X(16)} at line 5 of
   * {@code app/cpy/CVACT02Y.cpy} and {@code TRAN-CARD-NUM PIC X(16)} at line 15 of
   * {@code app/cpy/CVTRA05Y.cpy}, which agree, and 30 of the 300 card numbers in
   * {@code app/data/ASCII/dailytran.txt} begin with a zero -- so the width is part of the value and
   * not merely a ceiling on it.
   */
  public static final String CARD_NUMBER_DIGITS = "|[0-9]{16}";

  /**
   * The expression the exactly-two-digit transaction type code is held to.
   *
   * <p>Assumptions: two is {@code TRAN-TYPE-CD PIC X(02)} at line 6 of
   * {@code app/cpy/CVTRA05Y.cpy}, and the reference tests the field NOT NUMERIC at line 331 of
   * {@code app/cbl/COTRN02C.cbl}. The empty arm is present so that a value left empty reports once,
   * against the presence constraint, rather than drawing a composition message as well.
   */
  public static final String TYPE_CODE_DIGITS = "|[0-9]{2}";

  /**
   * The expression the exactly-four-digit transaction category code is held to.
   *
   * <p>Assumptions: four is {@code TRAN-CAT-CD PIC 9(04)} at line 7 of
   * {@code app/cpy/CVTRA05Y.cpy} and the committed extract carries {@code 0001} at one-based bytes
   * 19 to 22 of the first record of {@code app/data/ASCII/dailytran.txt}, so the leading zeros are
   * data. The empty arm is present so that a value left empty reports once, against the presence
   * constraint.
   */
  public static final String CATEGORY_CODE_DIGITS = "|[0-9]{4}";

  /**
   * The expression the exactly-nine-digit merchant identifier is held to.
   *
   * <p>Assumptions: nine is {@code TRAN-MERCHANT-ID PIC 9(09)} at line 11 of
   * {@code app/cpy/CVTRA05Y.cpy}, and the reference tests it NOT NUMERIC at line 432 of
   * {@code app/cbl/COTRN02C.cbl}. The empty arm is present so that a value left empty reports once,
   * against the presence constraint.
   */
  public static final String MERCHANT_ID_DIGITS = "|[0-9]{9}";

  /**
   * The expression either submitted date is held to, admitting the blank state.
   *
   * <p>Assumptions: this is the shape {@code app/cbl/COTRN02C.cbl} lines 354 to 358 test character
   * group by character group -- four numeric characters, a hyphen, two numeric characters, a hyphen
   * and two numeric characters -- expressed once because those five alternatives share a single
   * action block at lines 359 to 363 and so yield one message rather than five. The framework
   * matches the whole value, so the expression needs no anchors.
   *
   * <p>Alternatives Considered: admitting a run of white space, which an earlier revision did.
   * Rejected because both date components are required, so absence is already reported by their
   * presence constraints, and over HTTP a padded screen field has no counterpart: an unfilled value
   * arrives omitted or empty rather than as spaces. The empty arm is kept so that a caller sending
   * an empty string draws the presence message rather than two messages for one omission.
   */
  public static final String ISO_DATE_SHAPE = "|[0-9]{4}-[0-9]{2}-[0-9]{2}";

  /**
   * The expression the confirmation is held to, admitting the blank state.
   *
   * <p>Assumptions: {@code app/cbl/COTRN02C.cbl} lines 170 to 176 recognise the affirmative and
   * negative characters in both cases, so all four are admitted. The blank state is admitted
   * because lines 175 and 176 place spaces and low values on the negative branch, which asks for
   * another turn rather than reporting a bad value.
   *
   * <p>Refactoring Rationale: the admitted spellings are the empty string and the four letters, and
   * an earlier revision additionally admitted a run of white space. That arm described a value no
   * HTTP client sends -- a screen field is always a character field of its declared width, so an
   * unfilled position reached the reference program as a pad byte, whereas over HTTP absence arrives
   * as an omitted member or the empty string. It also left this record and the published contract
   * disagreeing: {@code openapi/transaction-api.yaml} declares the domain as {@code ^[YyNn]?$},
   * which admits exactly the five spellings this expression now admits.
   */
  public static final String CONFIRM_VALUES = "[YyNn]?";

  /**
   * The character domain every free-text member of this capture is held to: printable US-ASCII.
   *
   * <p>Assumptions: the admitted set is the inclusive span from the space to the tilde, code points
   * {@code 0x20} to {@code 0x7E}, and it is DERIVED from the encoder this migration actually writes
   * these values through rather than chosen for tidiness. {@code FixedWidthCodec} encodes with
   * {@link java.nio.charset.StandardCharsets#US_ASCII} and refuses any text its charset cannot
   * round-trip, so a code point above {@code 0x7E} cannot be written to a fixed-width record at all;
   * and everything below {@code 0x20}, together with {@code 0x7F}, round-trips through US-ASCII
   * intact and is therefore copied verbatim into a record that has no escaping mechanism of any
   * kind. The span between the two is exactly what can both survive the codec and carry no
   * structural meaning in the artifacts it lands in. The shared kernel states the same span for the
   * same two reasons on {@code GlobalExceptionHandler.isPrintableWithinDigitRun}.
   *
   * <p>Assumptions: the space is INSIDE the domain, unlike the neighbouring identifier domain
   * {@code UserService.ADDRESSABLE_USER_ID} declares, which admits letters and digits only. A user
   * identifier is delimited by white space and addressed as a URI path segment, so it can contain
   * neither; a merchant name and a description are prose and routinely do --
   * {@code app/data/ASCII/dailytran.txt} carries spaces in all five of the text spans this
   * expression governs.
   *
   * <p>⚠️ Refactoring Rationale: this sentence named {@code UserService.CANONICAL_USER_ID} and said
   * that domain "begins at {@code 0x21}", which described the identifier expression as it stood when
   * this one was written. Both halves are now wrong: the constant was renamed and its domain narrowed
   * from every printable byte above the space to {@code [A-Za-z0-9]+}, so it no longer begins at a
   * byte value at all. The CONTRAST the sentence draws is unaffected and is restated on the narrower
   * domain rather than deleted -- the space is still outside the identifier domain and still inside
   * this one -- but the reason is now exclusion by enumeration rather than by lower bound, which is
   * the stronger of the two and is why the cross-reference is worth keeping.
   *
   * <p>Assumptions: the ordinary punctuation of a merchant name is admitted rather than enumerated
   * -- the ampersand, apostrophe, hyphen, period, comma and solidus are all inside the span -- so
   * this expression narrows nothing a producer legitimately sends. Measured across all 300 records
   * of the committed extract, the only bytes present in the source, description, merchant name,
   * merchant city and merchant postal code spans are the space, the apostrophe, the comma, the
   * hyphen, the digits and the letters, every one of them inside this domain.
   *
   * <p>⚠️ Refactoring Rationale: this expression REPLACES a decision, recorded on the description
   * component and now corrected there, that no character-set constraint should be declared at all
   * because {@code TRAN-DESC PIC X(100)} at line 9 of {@code app/cpy/CVTRA05Y.cpy} admits every
   * character of its code page. The reading was wrong in one respect that matters: the code page is
   * a SINGLE-BYTE one and the reference's only writer is a 3270 field -- {@code TDESC} at line 161
   * of {@code app/bms/COTRN02.bms} and its four siblings -- so neither a supplementary Unicode code
   * point nor a C0 control was ever an accepted value on this screen. Refusing them is a parity FIX,
   * not the divergence rule T9 would require documenting: it restores a bound the terminal enforced
   * physically and the HTTP boundary removed.
   *
   * <p>Trade-offs: a producer that would have sent an accented Latin letter now receives a field
   * error instead of a capture. That is accepted because the alternative is worse in a way the
   * producer cannot see: the value is stored, and the failure surfaces later inside a batch or
   * reporting run that encodes it US-ASCII, where it is no longer attributable to a request and no
   * longer refusable -- AAP section 0.7.3 and transformation rule T3 make those fixed-width codecs
   * the boundary the data has to survive. Refusing at the boundary is the only place the refusal can
   * still name a field.
   *
   * <p>Assumptions: length is NOT bounded here and stays with each member's own width constraint, so
   * one over-long value reports once; and the empty string is admitted, so an omitted or empty value
   * reports against its presence constraint alone. That is the same separation of presence from
   * composition the digit expressions above record.
   *
   * <p>Assumptions: no anchor is written into the expression, for the reason
   * {@link #AMOUNT_WIRE_FORM} records -- Bean Validation and {@link java.util.regex.Matcher#matches()}
   * both match the whole string, and anchors here would make it unequal to the published pattern,
   * which carries its own.
   */
  public static final String PRINTABLE_TEXT = "[\\x20-\\x7E]*";

  /**
   * The compiled form of {@link #PRINTABLE_TEXT}, held once for every request the service receives.
   *
   * <p>Assumptions: compiled from that constant rather than restated, for the reason
   * {@link #AMOUNT_WIRE_FORM_PATTERN} records: the expression this record applies, the expression the
   * service layer applies to an internally assembled capture, and the expression the contract test
   * compares against the published schema are then the same characters.
   */
  private static final java.util.regex.Pattern PRINTABLE_TEXT_PATTERN =
      java.util.regex.Pattern.compile(PRINTABLE_TEXT);

  /**
   * The message reported when a submitted account identifier is not made up of digits, verbatim
   * from {@code app/cbl/COTRN02C.cbl} line 199.
   */
  public static final String ACCOUNT_ID_NOT_NUMERIC = "Account ID must be Numeric...";

  /**
   * The message reported when a submitted card number is not made up of digits, verbatim from line
   * 213 of that program.
   */
  public static final String CARD_NUMBER_NOT_NUMERIC = "Card Number must be Numeric...";

  /**
   * The message reported when neither key alternative was supplied, verbatim from line 226.
   */
  public static final String KEY_FIELD_REQUIRED = "Account or Card Number must be entered...";

  /**
   * The message reported when the transaction type code is absent, verbatim from line 254.
   */
  public static final String TYPE_CODE_REQUIRED = "Type CD can NOT be empty...";

  /**
   * The message reported when the transaction category code is absent, verbatim from line 260.
   */
  public static final String CATEGORY_CODE_REQUIRED = "Category CD can NOT be empty...";

  /**
   * The message reported when the transaction source is absent, verbatim from line 266.
   */
  public static final String SOURCE_REQUIRED = "Source can NOT be empty...";

  /**
   * The message reported when the transaction description is absent, verbatim from line 272.
   */
  public static final String DESCRIPTION_REQUIRED = "Description can NOT be empty...";

  /**
   * The message reported when the transaction amount is absent, verbatim from line 278.
   */
  public static final String AMOUNT_REQUIRED = "Amount can NOT be empty...";

  /**
   * The message reported when the origination date is absent, verbatim from line 284.
   */
  public static final String ORIGIN_DATE_REQUIRED = "Orig Date can NOT be empty...";

  /**
   * The message reported when the processing date is absent, verbatim from line 290.
   */
  public static final String PROCESS_DATE_REQUIRED = "Proc Date can NOT be empty...";

  /**
   * The message reported when the merchant identifier is absent, verbatim from line 296.
   */
  public static final String MERCHANT_ID_REQUIRED = "Merchant ID can NOT be empty...";

  /**
   * The message reported when the merchant name is absent, verbatim from line 302.
   */
  public static final String MERCHANT_NAME_REQUIRED = "Merchant Name can NOT be empty...";

  /**
   * The message reported when the merchant city is absent, verbatim from line 308.
   */
  public static final String MERCHANT_CITY_REQUIRED = "Merchant City can NOT be empty...";

  /**
   * The message reported when the merchant postal code is absent, verbatim from line 314.
   */
  public static final String MERCHANT_ZIP_REQUIRED = "Merchant Zip can NOT be empty...";

  /**
   * The message reported when the transaction type code is not made up of digits, verbatim from
   * line 325.
   */
  public static final String TYPE_CODE_NOT_NUMERIC = "Type CD must be Numeric...";

  /**
   * The message reported when the transaction category code is not made up of digits, verbatim from
   * line 331.
   */
  public static final String CATEGORY_CODE_NOT_NUMERIC = "Category CD must be Numeric...";

  /**
   * The message reported when the merchant identifier is not made up of digits, verbatim from line
   * 432.
   *
   * <p>Assumptions: the reference tests this one on its own at lines 430 to 436 rather than inside
   * the construct that tests the other two composition rules at lines 322 to 336, which is why its
   * line is far from theirs.
   */
  public static final String MERCHANT_ID_NOT_NUMERIC = "Merchant ID must be Numeric...";

  /**
   * The message reported when the source carries a character outside {@link #PRINTABLE_TEXT}.
   *
   * <p>⚠️ Assumptions: the sentence is AUTHORED rather than transcribed, because the reference has none
   * to transcribe: its writer is a 3270 field on a single-byte code page, so a value it could not
   * represent never reached the program and no branch there reports one. It is worded in the voice of
   * the neighbouring composition refusals -- {@code 'Type CD must be Numeric...'} at line 325 of
   * {@code app/cbl/COTRN02C.cbl} names the field and then its domain -- so a screen renders it beside
   * the same marker as every other field-level refusal.
   *
   * <p>Assumptions: it ends with the ellipsis those sentences end with, which is also what carries it
   * through the shared advice's provenance gate: {@code GlobalExceptionHandler.referenceMessageOrNull}
   * admits a sentence that terminates that way and is itself printable, and degrades anything else to
   * the generic wording. A sentence that named the code points it refuses would carry a digit run and
   * be degraded.
   *
   * <p>Trade-offs: it says {@code printable text} rather than naming US-ASCII or a code-point span.
   * The operator's remedy is to re-key the value with ordinary characters, which this states; the exact
   * admitted span belongs in the published contract and in {@link #PRINTABLE_TEXT}, where a client
   * integrator reads it. The five sentences below are per-field rather than one shared sentence for the
   * same reason every other refusal on this screen is per-field: the field name is how the operator
   * finds the input.
   */
  public static final String SOURCE_NOT_PRINTABLE = "Source must be printable text...";

  /**
   * The message reported when the description carries a character outside {@link #PRINTABLE_TEXT}.
   *
   * <p>Assumptions: authored for the reason {@link #SOURCE_NOT_PRINTABLE} records, and worded to the
   * same pattern so five refusals of one kind read as one kind.
   */
  public static final String DESCRIPTION_NOT_PRINTABLE = "Description must be printable text...";

  /**
   * The message reported when the merchant name carries a character outside {@link #PRINTABLE_TEXT}.
   *
   * <p>Assumptions: authored for the reason {@link #SOURCE_NOT_PRINTABLE} records, and worded to the
   * same pattern so five refusals of one kind read as one kind.
   */
  public static final String MERCHANT_NAME_NOT_PRINTABLE =
      "Merchant Name must be printable text...";

  /**
   * The message reported when the merchant city carries a character outside {@link #PRINTABLE_TEXT}.
   *
   * <p>Assumptions: authored for the reason {@link #SOURCE_NOT_PRINTABLE} records, and worded to the
   * same pattern so five refusals of one kind read as one kind.
   */
  public static final String MERCHANT_CITY_NOT_PRINTABLE =
      "Merchant City must be printable text...";

  /**
   * The message reported when the merchant postal code carries a character outside
   * {@link #PRINTABLE_TEXT}.
   *
   * <p>Assumptions: authored for the reason {@link #SOURCE_NOT_PRINTABLE} records, and worded to the
   * same pattern so five refusals of one kind read as one kind. The field is named {@code Merchant
   * Zip} because that is what the reference's own presence sentence at line 316 of
   * {@code app/cbl/COTRN02C.cbl} calls it.
   */
  public static final String MERCHANT_ZIP_NOT_PRINTABLE = "Merchant Zip must be printable text...";

  /**
   * The message reported when the submitted amount is not a well-formed amount or is wider than the
   * record can hold, verbatim from line 345.
   *
   * <p>Assumptions: this string ends with the format specimen it names and carries no trailing
   * ellipsis, unlike almost every other message on this screen, and transformation rule T8 carries
   * it across character for character rather than normalising it.
   *
   * <p>Trade-offs: the specimen names eight integer digits while this request accepts nine, and the
   * mismatch is accepted rather than edited. The reference does emit exactly this message for an
   * over-wide integer part, because a ninth integer digit displaces the decimal point from the
   * tenth character and the test at line 342 then fails, so it is the right message for the
   * condition.
   * Rewording it to name nine digits was rejected because an edited user-visible string is a
   * behavioural change.
   */
  public static final String AMOUNT_FORMAT = "Amount should be in format -99999999.99";

  /**
   * The message reported when the origination date does not carry the expected shape, verbatim from
   * line 360, ending without a trailing ellipsis.
   */
  public static final String ORIGIN_DATE_FORMAT = "Orig Date should be in format YYYY-MM-DD";

  /**
   * The message reported when the processing date does not carry the expected shape, verbatim from
   * line 375, ending without a trailing ellipsis.
   */
  public static final String PROCESS_DATE_FORMAT = "Proc Date should be in format YYYY-MM-DD";

  /**
   * The message reported when the confirmation carries a character the reference does not
   * recognise, verbatim from line 184.
   *
   * <p>Assumptions: the sibling outcome at line 178, {@code 'Confirm to add this transaction...'},
   * is not declared here because this record never reports it. That one asks for a further turn of
   * the confirmation exchange rather than complaining about a value, so it belongs to the service
   * layer that decides whether to perform the add.
   */
  public static final String CONFIRM_INVALID_VALUE = "Invalid value. Valid values are (Y/N)...";

  /**
   * The message reported when the binding token is not the shape this service issues.
   *
   * <p>⚠️ Assumptions: the sentence is AUTHORED rather than transcribed, and it is authored because the
   * baseline has none to transcribe -- the reference confirms inside one screen turn and so never
   * reports that a confirmation belongs to an answer it cannot recognise. It names the remedy the
   * operator actually has, which is to take the preview again, rather than describing a token they never
   * see. The divergence is registered as {@code D-CONFIRMED-CARD-BINDING} in
   * {@code docs/architecture/cobol-to-service-traceability.md}, beside the guard it belongs to.</p>
   *
   * <p>Assumptions: it is the same sentence
   * {@code TransactionAddService.MESSAGE_CONFIRM_RESOLVED_CARD} carries, and the repetition is
   * deliberate rather than an oversight: a malformed token and a token naming another card are one
   * condition from the operator's side -- this confirmation cannot be trusted to name what they were
   * shown -- and two sentences for it would read on screen as two different problems with two different
   * remedies.</p>
   */
  public static final String CONFIRMATION_TOKEN_MALFORMED =
      "Card Number changed. Review the transaction and confirm again...";

  /**
   * Reports whether submitted amount characters are exactly the wire form the contract publishes.
   *
   * <p><b>Purpose.</b> This is the one statement of the amount's lexical domain, so the boundary
   * constraint and the service layer's own transcription of {@code app/cbl/COTRN02C.cbl} lines 339 to
   * 351 apply the same expression instead of each carrying a copy. Two copies of one shape rule on one
   * field is how a contract and its implementation come to disagree.
   *
   * <p>Assumptions: declared as a predicate ALONGSIDE {@link AmountWithinRecordDomain} rather than
   * instead of it, which is the distinction an earlier revision of this file got wrong. A predicate
   * used AS a class-level constraint reports its violation against a synthetic property name no
   * submitted field carries; a predicate a component-level constraint delegates to reports against the
   * component. The constraint is still what the framework sees, and this is only where its rule is
   * written down.
   *
   * @param submitted the characters a producer sent, which may be {@code null}
   * @return {@code true} when the characters are an optional minus sign, one to nine digits, a decimal
   *     point and exactly two further digits; {@code false} for {@code null} and for every other value
   */
  public static boolean isAmountWireForm(String submitted) {
    // WHY : Assumptions: null answers false rather than true, because this predicate states one thing
    //       -- that these characters ARE the published form -- and an absent value is not. The two
    //       callers each decide what absence means for them: the constraint accepts it and leaves it to
    //       the presence constraint, while the service reaches its own presence check first.
    if (submitted == null) {
      return false;
    }

    Matcher shape = AMOUNT_WIRE_FORM_PATTERN.matcher(submitted);
    return shape.matches();
  }

  /**
   * Reports whether text occupies the printable domain every free-text member of this capture shares.
   *
   * <p><b>Purpose.</b> This is the one statement of that domain, so the five boundary constraints and
   * the service layer's guard over an internally assembled capture apply the same expression instead of
   * each carrying a copy. The service needs its own application of it because
   * {@code TransactionAddService} builds a capture directly from stored values on the copy-last path,
   * which never passes through Bean Validation -- so a row written before this domain existed would
   * otherwise be re-persisted unexamined.
   *
   * <p>Assumptions: {@code null} and the empty string both answer {@code true}, which is the opposite
   * of {@link #isAmountWireForm(String)} and deliberately so. That predicate states that characters ARE
   * a required form, so absence cannot satisfy it; this one states that characters are not OUTSIDE a
   * domain, and absence is not outside it. Answering {@code true} for absence is what keeps presence
   * the sole business of {@code @NotBlank} and of the service's own presence block, so one omitted
   * value draws one sentence.
   *
   * @param submitted the characters a producer sent, or a stored value being re-submitted, which may be
   *     {@code null}
   * @return {@code true} when every character is in the inclusive span from the space to the tilde, and
   *     for {@code null} and the empty string; {@code false} as soon as any other character is present
   */
  public static boolean isPrintableText(String submitted) {
    // WHY : Assumptions: absence short-circuits rather than being matched, because a null cannot be
    //       handed to a matcher and because the empty string is admitted by the expression anyway --
    //       so the two absent spellings answer alike without depending on the expression to do it.
    if (submitted == null) {
      return true;
    }

    Matcher domain = PRINTABLE_TEXT_PATTERN.matcher(submitted);
    return domain.matches();
  }

  /**
   * Parses the submitted amount into the shared money type, once its characters have been validated.
   *
   * <p><b>Purpose.</b> {@link #amount()} carries the characters a producer sent, because the reference
   * edits those characters position by position at {@code app/cbl/COTRN02C.cbl} lines 339 to 351 and a
   * parse would have normalised them first. Every consumer of a VALIDATED request wants the value
   * rather than the characters, and this is the one place the conversion happens -- so no consumer
   * chooses its own parse and no two consumers can choose differently.
   *
   * <p>Assumptions: the parse goes through the shared money type rather than through
   * {@code new BigDecimal(String)}, which is the same decision {@code TransactionAddService} records
   * for the reference's currency-tolerant conversion at lines 383 and 456: the shared type fixes the
   * scale at two with half-up rounding, which is what {@code TRAN-AMT PIC S9(09)V99} at line 10 of
   * {@code app/cpy/CVTRA05Y.cpy} declares, whereas the bare constructor keeps whatever scale the text
   * happened to carry.
   *
   * <p>Assumptions: an absent or blank amount answers {@code null} rather than raising, so a caller's
   * own presence check reads the same as it did when this component was the money type. That matters
   * because the service layer re-checks presence in the reference's own priority order and reports the
   * fifth field's sentence for it; raising here would make that check unreachable.
   *
   * <p>Trade-offs: a value that is neither blank nor the published wire form raises rather than
   * answering {@code null}, and the raise is deliberate. Such a value cannot reach this method through
   * the API -- {@link AmountWithinRecordDomain} refuses it at the boundary and the service refuses it
   * again before parsing -- so reaching here means a caller constructed the record directly and
   * skipped both, and answering {@code null} would let an unvalidated request be captured with no
   * amount at all. Failing loudly at the point of construction is the safer of the two.
   *
   * @return the submitted amount at a scale of two, or {@code null} when the member was absent or
   *     blank
   * @throws IllegalArgumentException if the characters are neither blank nor a value the shared money
   *     type can read, which the component's own constraints make unreachable through the API
   */
  public Money amountValue() {
    // WHY : Assumptions: blank and null are folded together here for the reason the presence
    //       constraint on the component states -- the reference's line 278 refuses SPACES as well as
    //       LOW-VALUES, so both spellings are one absent state and neither is a value to parse.
    if (this.amount == null || this.amount.isBlank()) {
      return null;
    }
    return Money.of(this.amount);
  }

  /**
   * Requires at least one of the two key alternatives, reporting against both of them.
   *
   * <p><b>Purpose.</b> This is the pairing rule of the transaction-add screen expressed as a
   * class-level constraint, because it is the only rule here that has to see two components at
   * once. {@code app/cbl/COTRN02C.cbl} lines 193 to 230 select on the account identifier first and
   * the card number second, and supplying neither reaches the third branch at lines 224 to 229,
   * which is the one refusal the paragraph contains. Inclusive disjunction is what those three
   * branches describe.
   *
   * <p>⚠️ Refactoring Rationale: this constraint required EXACTLY one key and was named
   * {@code ExactlyOneKey}, and that refused submissions the baseline accepts. The paragraph is a
   * selection, not a validation: line 196 tests the account identifier, and because that arm is
   * taken the card-number arm at line 210 is never evaluated at all, so a submission carrying both
   * is processed on the account and the card number the operator supplied is simply discarded --
   * overwritten at line 209 by the cross-reference lookup. There is no branch anywhere in the
   * paragraph that reports a contradiction, and no sentence in the program for one. The previous
   * reading -- that supplying both "is not a richer submission but a contradiction" -- described a
   * rule the reference does not have, and enforcing it answered 400 where the baseline answers a
   * captured transaction, which also meant the account-first precedence the service transcribes
   * could never be exercised: the request was rejected before the service saw it.
   *
   * <p>Trade-offs: a client may now send a card number that disagrees with the account and receive a
   * success naming neither the disagreement nor the value that won. That is accepted because it is
   * the baseline's own behaviour, and reporting the conflict would refuse submissions this screen
   * accepts today. {@code TransactionAddService.validateInputKeyFields} carries the same trade-off
   * note at the point where the discard actually happens.
   *
   * <p>Refactoring Rationale: a class-level constraint whose validator names the two components
   * replaces the {@code @AssertTrue} predicate an even earlier revision declared. Bean Validation derives
   * a property path for a predicate constraint from the METHOD it annotates, so that revision
   * reported its violation against a property named {@code keySelectionValid} -- a name no submitted
   * field carries and no form control can be bound to, so a client received an error it could not
   * display beside an input. The validator below suppresses the default violation and raises one per
   * key component instead, so the two entries a client receives name {@code accountId} and
   * {@code cardNumber} and are actionable. The alternative of leaving the predicate in place and
   * translating the synthetic name downstream was rejected: the translation table would live in the
   * shared error advice, which would then have to know a name declared in one service's request type.
   *
   * <p>Assumptions: the constraint is declared on the type rather than being deferred to the service
   * layer so that its violation joins the same accumulated set as every component constraint and
   * reaches the same per-field array. A check performed after binding would answer on a separate
   * path, so a submission with no key and other defects would produce two shapes instead of one.
   *
   * <p>An annotation type declares no parameters, returns no value and raises nothing, so no
   * parameter, return or exception at-clause appears on this block; the three members below carry
   * their own.
   */
  @Documented
  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  @Constraint(validatedBy = KeySelectionValidator.class)
  public @interface AtLeastOneKey {

    /**
     * The message both raised violations carry.
     *
     * @return the reference wording of {@link TransactionAddRequest#KEY_FIELD_REQUIRED}
     */
    String message() default KEY_FIELD_REQUIRED;

    /**
     * The validation groups this constraint belongs to.
     *
     * @return an empty array, because this request is validated in the default group only
     */
    Class<?>[] groups() default {};

    /**
     * The payload a client may attach to a violation of this constraint.
     *
     * @return an empty array, because no metadata is attached
     */
    Class<? extends Payload>[] payload() default {};
  }

  /**
   * Holds the submitted amount to the exact wire form the record's nine integer digits admit.
   *
   * <p><b>Purpose.</b> {@code TRAN-AMT} is {@code PIC S9(09)V99} at line 10 of
   * {@code app/cpy/CVTRA05Y.cpy}, so nine integer digits is the widest amount the record can hold,
   * and {@link #AMOUNT_WIRE_FORM} is the lexical statement of that domain. The framework's own
   * pattern constraint would report a second violation for a blank value that the presence
   * constraint already reports, which is why this constraint exists rather than a bare
   * {@code @Pattern}: it admits the blank state and leaves it to the constraint that names it.
   *
   * <p>Refactoring Rationale: declared as a component constraint so a violation reports against
   * {@code amount}, the member a client submitted, rather than against the synthetic property name a
   * boolean predicate would have produced.
   *
   * <p>⚠️ Refactoring Rationale: it constrains CHARACTERS where an earlier revision constrained the
   * parsed money type in whole cents. The parsed value is normalised by construction -- see
   * {@link #AMOUNT_WIRE_FORM} -- so measuring it could only ever fail on magnitude, and every
   * lexical defect was left to a deserialiser whose failure is rendered as an unreadable body with no
   * per-field entry at all. One constraint over the characters decides both the shape and the
   * magnitude, and it decides them where a violation can still name the field.
   *
   * <p>An annotation type declares no parameters, returns no value and raises nothing, so no
   * parameter, return or exception at-clause appears on this block; the three members below carry
   * their own.
   */
  @Documented
  @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER,
      ElementType.METHOD})
  @Retention(RetentionPolicy.RUNTIME)
  @Constraint(validatedBy = AmountDomainValidator.class)
  public @interface AmountWithinRecordDomain {

    /**
     * The message a violation carries.
     *
     * @return the reference wording of {@link TransactionAddRequest#AMOUNT_FORMAT}
     */
    String message() default AMOUNT_FORMAT;

    /**
     * The validation groups this constraint belongs to.
     *
     * @return an empty array, because this request is validated in the default group only
     */
    Class<?>[] groups() default {};

    /**
     * The payload a client may attach to a violation of this constraint.
     *
     * @return an empty array, because no metadata is attached
     */
    Class<? extends Payload>[] payload() default {};
  }

  /**
   * Reads the amount as the characters a producer submitted, so a constraint can measure them.
   *
   * <p><b>Purpose.</b> {@code app/cbl/COTRN02C.cbl} validates the amount POSITIONALLY at lines 339 to
   * 351, over the twelve characters the operator keyed: the first must be a sign at line 340, eight
   * characters from the second must be numeric at line 341, the tenth must be a decimal point at line
   * 342 and two characters from the eleventh must be numeric at line 343. All four alternatives share
   * one action block, so however many of them hold the reference emits the single sentence at line 345.
   * Reproducing that needs the submitted characters, and this class is what makes them survive the
   * wire boundary intact: it copies a JSON string through verbatim, normalising nothing.</p>
   *
   * <p>⚠️ Refactoring Rationale: this class no longer REFUSES anything, where an earlier revision
   * matched the published pattern here and reported a mismatch through the deserialisation context. A
   * deserialiser failure is rendered as an unreadable-body 400 carrying the shared malformed-request
   * sentence and an EMPTY per-field array, so every lexically malformed amount produced a response
   * that named no member and carried neither of this screen's two sentences. The measurement moved to
   * {@link AmountWithinRecordDomain}, which runs after binding and reports against {@code amount};
   * all this class now owns is the requirement that binding SUCCEED, because a constraint cannot run
   * on a body that never became an object.</p>
   *
   * <p>Assumptions: a token that is not a JSON string is bound to {@link #NON_TEXTUAL_AMOUNT} rather
   * than refused here, which is what keeps a JSON number out of the money path while still answering
   * with a per-field entry. The marker names the token KIND and never carries the value, so nothing
   * monetary reaches a log through it, and it matches no string {@link #AMOUNT_WIRE_FORM} admits --
   * so the constraint refuses it with this screen's format sentence. Rule T3 requires money on the
   * wire to be a JSON string, and the published request schema declares this member a string, so a
   * producer emitting {@code "amount": 1.00} is refused rather than coerced -- which is what Jackson's
   * own scalar-to-text coercion would otherwise do silently.</p>
   *
   * <p>Alternatives Considered: leaving the member with no deserialiser at all. Rejected because the
   * default text handling ACCEPTS a JSON number by coercing it, so {@code 1.00} and {@code "1.00"}
   * would behave identically and the one rule this migration states about money on the wire would be
   * unenforced at the one boundary that receives it.</p>
   *
   * <p>Alternatives Considered: binding the raw token text for a non-string token, so the constraint
   * could report the offending characters. Rejected on two counts: a JSON number's text often matches
   * the wire form exactly -- {@code 1.00} does -- so the number would be ACCEPTED, and an amount is
   * monetary data that must not travel into a diagnostic.</p>
   */
  public static final class WireFormAmountDeserializer extends ValueDeserializer<String> {

    /**
     * The value bound in place of an amount that arrived as something other than a JSON string.
     *
     * <p>Assumptions: it is deliberately NOT blank, because a blank value is the reference's
     * never-supplied state and would draw {@link TransactionAddRequest#AMOUNT_REQUIRED} -- the wrong
     * sentence for a value that was supplied in the wrong shape. It is also deliberately not a
     * candidate the wire form could admit, so the shape constraint refuses it without exception.
     */
    static final String NON_TEXTUAL_AMOUNT = "(non-textual amount token)";

    /**
     * Reads one amount as text, preserving a JSON string exactly and marking anything else.
     *
     * @param parser the parser positioned on the value to read; never {@code null}
     * @param ctxt the deserialisation context, unused because this method reports no mismatch of its
     *     own and leaves every refusal to the component's constraints; never {@code null}
     * @return the submitted characters verbatim for a JSON string, otherwise
     *     {@link #NON_TEXTUAL_AMOUNT}; never {@code null}
     * @throws JacksonException if the underlying parser fails while reading the token, which is a
     *     transport-level failure rather than a rejected value
     */
    @Override
    public String deserialize(JsonParser parser, DeserializationContext ctxt)
        throws JacksonException {

      JsonToken token = parser.currentToken();
      // WHY : Assumptions: the text is taken verbatim with no trimming at all, which is stricter than
      //       the shared money handler's own reading. That handler strips surrounding whitespace on the
      //       ground that a producer copying a fixed-width field can carry padding; this component's
      //       contract publishes a JSON string with a fixed pattern, so padding here is a malformed
      //       value rather than an artefact of a field width, and trimming it would accept a form the
      //       published pattern refuses.
      if (token == JsonToken.VALUE_STRING) {
        return parser.getString();
      }

      // WHY : Assumptions: a structured token is skipped WHOLE before the marker is returned, because
      //       leaving a parser positioned inside an object or an array it will not read would make the
      //       remaining members of the body unreadable -- turning one rejected field into an
      //       unreadable body, which is the outcome this class exists to prevent. A scalar token needs
      //       no skip: the parser advances past it on its own.
      if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
        parser.skipChildren();
      }

      return NON_TEXTUAL_AMOUNT;
    }

    /**
     * Reports the value type this deserialiser handles.
     *
     * @return the {@link String} class, because this component now carries the submitted characters,
     *     never {@code null}
     */
    @Override
    public Class<?> handledType() {
      return String.class;
    }
  }

  /**
   * Enforces {@link AtLeastOneKey} and attributes its violation to the two key components.
   *
   * <p>Assumptions: the validator is declared here beside the request that first needed it, and it holds
   * no state and reads nothing outside the value handed to it, so a single instance is safe for the
   * framework to share across requests.
   *
   * <p>⚠️ Refactoring Rationale: it is declared over {@link TransactionKeySelection} and no longer over
   * this record, and the note it replaces said the rule was "a property of this one payload and of no
   * other". That stopped being true when the copy-last operation was given a request shape of its own:
   * the rule is {@code VALIDATE-INPUT-KEY-FIELDS} at {@code app/cbl/COTRN02C.cbl} lines 193 to 230, which
   * BOTH of the screen's arms perform -- the Enter arm at lines 133 and 134 and the copy arm at line 473.
   * Bean Validation resolves a validator by assignability, so widening the type parameter to the interface
   * both records implement serves both operations from one decision instead of two that could drift.
   */
  public static final class KeySelectionValidator
      implements ConstraintValidator<AtLeastOneKey, TransactionKeySelection> {

    /**
     * Reports whether at least one of the two key alternatives was supplied.
     *
     * @param request the bound request, which the framework may pass as {@code null} when the body
     *     itself was absent
     * @param context the context a violation is raised through; never {@code null}
     * @return {@code true} when either the account identifier or the card number carries a value,
     *     including when both do, and {@code false} only when neither was supplied
     */
    @Override
    public boolean isValid(TransactionKeySelection request, ConstraintValidatorContext context) {
      // WHY : Assumptions: an absent request is reported valid here rather than false, because the
      //       absence of a body is a different failure with its own diagnostic and reporting a key
      //       error for it would name two fields the client never sent.
      if (request == null) {
        return true;
      }
      // WHY : ⚠️ Assumptions: INCLUSIVE disjunction, where this line used to be exclusive. The
      //       reference's paragraph selects rather than validates -- line 196 takes the account arm
      //       and the card arm at line 210 is then never evaluated -- so a submission carrying both
      //       keys is processed on the account and the card number is discarded at line 209 by the
      //       cross-reference lookup. Only the fall-through at lines 224 to 229 refuses anything, and
      //       it is reached when NEITHER key was supplied. An exclusive test therefore refused a
      //       submission the baseline captures, and refused it before the service's own account-first
      //       precedence could run at all.
      if (isSupplied(request.accountId()) || isSupplied(request.cardNumber())) {
        return true;
      }

      // WHY : Assumptions: the default violation is suppressed and replaced by one violation per key
      //       component, so the accumulated set a client receives names submitted fields only. Both
      //       are raised rather than one, because either field can be the one the operator should
      //       fill in and the request alone cannot say which. Reaching here now means neither was
      //       supplied, so naming both is naming exactly the choice the operator has.
      context.disableDefaultConstraintViolation();
      String template = context.getDefaultConstraintMessageTemplate();
      context.buildConstraintViolationWithTemplate(template)
          .addPropertyNode("accountId")
          .addConstraintViolation();
      context.buildConstraintViolationWithTemplate(template)
          .addPropertyNode("cardNumber")
          .addConstraintViolation();
      return false;
    }
  }

  /**
   * Enforces {@link AmountWithinRecordDomain} against the characters a producer submitted.
   *
   * <p>Assumptions: the validator holds no state and reads nothing outside the value handed to it, so
   * a single instance is safe for the framework to share across requests. The compiled expression is
   * held once as a class constant rather than compiled per call, because a validator instance serves
   * every request the service receives.
   */
  public static final class AmountDomainValidator
      implements ConstraintValidator<AmountWithinRecordDomain, String> {

    /**
     * Reports whether the submitted amount occupies the record's published wire form.
     *
     * @param amount the submitted characters, which are {@code null} when the member was absent and
     *     blank when it was submitted empty
     * @param context the context a violation would be raised through, unused because the default
     *     violation this constraint declares already names the component; never {@code null}
     * @return {@code true} when the member was absent or blank, or when its characters are exactly the
     *     published wire form; {@code false} for every other value, including one whose magnitude
     *     needs a tenth integer digit
     */
    @Override
    public boolean isValid(String amount, ConstraintValidatorContext context) {
      // WHY : Assumptions: an absent or blank amount is reported once, by the presence constraint on
      //       the component, so this validator accepts both rather than reporting a second violation
      //       for one defect. The reference does the same: line 278 of app/cbl/COTRN02C.cbl refuses an
      //       empty field in the presence chain and sends the screen from there, so the shape
      //       alternatives at lines 339 to 343 are never reached for it.
      if (amount == null || amount.isBlank()) {
        return true;
      }

      // WHY : Assumptions: the shared predicate is applied rather than a comparison of this validator's
      //       own, so the boundary and the service layer refuse exactly the same strings. Matching it
      //       also bounds the MAGNITUDE, because the expression admits at most nine integer digits --
      //       which is why no arithmetic comparison follows it and no arbitrary-precision decimal
      //       appears in this file.
      return isAmountWireForm(amount);
    }
  }

  /**
   * Reports whether a submitted character field carries a value at all.
   *
   * <p>Assumptions: the reference tests a submitted field for presence with a single condition that
   * excludes both spaces and low values, at {@code app/cbl/COTRN02C.cbl} lines 196 and 210. A null
   * reference is the absent state that low values represent on a terminal, and an all-white-space
   * value is the blank state that spaces represent, so both map to absence here and neither is
   * treated as a supplied value.
   *
   * @param value the submitted field value, which may be null
   * @return {@code true} when the value is present and is not made up entirely of white space
   */
  private static boolean isSupplied(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * Renders this request for a log or a diagnostic with the primary account number masked.
   *
   * <p>Refactoring Rationale: a record generates a rendering that names every component verbatim,
   * and one of this record's fourteen components is a primary account number. Nothing has to be
   * written wrongly for that rendering to escape: a request interpolated into a log statement,
   * carried in an assertion message, or picked up by a framework tracing a rejected body produces it
   * automatically. Overriding it here makes the masking a property of the TYPE, so it holds for
   * every present and future caller rather than depending on each one remembering -- which matters
   * most on precisely this type, because a request that fails validation is both the case that gets
   * logged and the case in which no handler has yet had a chance to mask anything.</p>
   *
   * <p>Refactoring Rationale: SEVEN OF THE FOURTEEN COMPONENTS ARE NOW OMITTED -- the account
   * identifier, the description, the amount and the four merchant fields -- and an earlier revision
   * rendered all of them, arguing the case for two of them explicitly. It held that the account
   * identifier "is a system key rather than protected data" because it addresses the resource in a
   * request path and the migrated contracts publish it unmasked, and that "the amount is not
   * protected data on its own" because it is frequently the reason a submission was refused. The
   * sensitive-data logging contract in {@code docs/architecture/observability.md} contradicts both:
   * it names account and customer identifiers in a clause of their own, and it covers
   * persistence-bound values as a class, which is what a posted amount becomes. The appeal to the
   * published contract also compares two different surfaces -- a response body reaches one
   * authenticated caller who already holds authority over that account and is not retained, whereas a
   * log line is retained, aggregated and readable by every holder of log access -- so a value being
   * disclosed to an authorised requester is not an argument for disclosing it to everyone who can
   * read a log.</p>
   *
   * <p>Assumptions: the description and the four merchant fields are omitted for a reason of their
   * own, distinct from the two above. Together with the masked card number and the two dates on the
   * same line, a merchant name, city and postal code reconstruct a real cardholder purchase -- what
   * was bought, roughly where and when -- which no single component discloses alone. The description
   * is additionally free text that a submitter authors: {@link #PRINTABLE_TEXT} now bounds which
   * CHARACTERS it may carry, which is what keeps a control character out of a log record, but nothing
   * in this type constrains what it SAYS -- so rendering it verbatim still makes the log the sink for
   * a hundred characters of a submitter's choosing. The same field is why
   * {@code com.carddemo.reporting.mapper.StatementHtmlMapper} encodes before emitting, and a log is no
   * better a place to interpolate submitter-authored prose than a document is.</p>
   *
   * <p>Alternatives Considered: omitting the card number entirely rather than masking it, which is
   * what the sibling {@code com.carddemo.card.domain.Card} rendering does. Rejected HERE, and the
   * difference between the two is the point. Either key alternative may be the one a client supplied
   * -- the reference fills whichever was omitted from the cross-reference -- so a rendering that
   * dropped the card number could not show what a card-only submission actually contained, which is
   * the submission whose validation failures most need diagnosing. Masking rather than omitting is
   * available here because this is a request shape and the mask is produced by the one shared owner
   * of that rule, {@code com.carddemo.common.security.CardNumberMasker}; the entity declines it
   * because an entity producing its own masked form would give one value a second rendering outside
   * the mapper that owns it.</p>
   *
   * <p>Trade-offs: what survives is the two reference codes, the source, the masked card number, the
   * two dates and the confirmation flag -- enough to say WHAT KIND of submission was refused and
   * when, and not enough to say whose it was, what it was worth or where it was made. The cost is
   * real: an operator reading a rejection from logs alone can no longer see the amount that breached
   * a limit or the merchant a submission named, and must read the request body or the row to learn
   * either. It is paid down by the correlation identifier
   * {@code com.carddemo.common.web.CorrelationIdFilter} publishes on every request-scoped line, which
   * ties a rejection to the request that caused it, and by the accessors above, which return every
   * omitted component to any caller that needs one.</p>
   *
   * @return a single-line rendering naming this type, the two reference codes, the source, the card
   *     number reduced to a mask and its last four digits, the two dates and the confirmation flag;
   *     the masked component is labelled as masked so that no reader mistakes it for a value that
   *     could be resubmitted, and no account identifier, monetary value, description or merchant
   *     detail appears at all
   */
  @Override
  public String toString() {
    return "TransactionAddRequest[typeCode="
        + typeCode
        + ", categoryCode="
        + categoryCode
        + ", source="
        + source
        + ", maskedCardNumber="
        + CardNumberMasker.mask(cardNumber)
        + ", originDate="
        + originDate
        + ", processDate="
        + processDate
        + ", confirmation="
        + confirmation
        + ']';
  }
}
