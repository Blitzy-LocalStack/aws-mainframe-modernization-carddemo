package com.carddemo.transaction.dto;

import com.carddemo.common.money.Money;

/**
 * The outcome of a posted bill payment: the key of the transaction written for it, the account and
 * the masked card it was written against, the amount paid, the balance the account was left with,
 * the flag recording that money moved, and the sentence accompanying the turn.
 *
 * <p><b>Purpose.</b> This is the outbound body of the bill-payment endpoint on its created path,
 * produced by {@code com.carddemo.transaction.service} and returned by a controller in
 * {@code com.carddemo.transaction.api}. It holds no logic and reaches nothing: each of its seven
 * components is a value the reference program persists, a value it computes and stores, or a
 * statement about the turn itself. The reference COBOL is the specification, so this shape encodes
 * the field set, the widths and the scale that specification already states rather than redefining
 * them, and the contract published for this endpoint is what fixes which of those values are
 * reported.
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> The seven components are documented
 * in the at-clauses at the end of this block, each naming its component, its type and what it
 * carries. The remaining two docstring elements have no subject in this compilation unit, and
 * the inapplicability is declared rather than left silent so that a reader can tell it from an
 * oversight. This record declares no method and no explicit constructor, so nothing here returns or
 * raises; the accessors are generated from the components rather than authored, and what each
 * returns is the component of the same name, described in its own at-clause. The Explainability
 * rule attaches the exceptions element at its line 21 only where applicable, and at line 39 forbids
 * a docstring that omits an element, so the absence is accounted for here instead of assumed.
 * Neither a return nor an exception at-clause is invented to fill the gap: the repository ruleset
 * audits at-clause bodies for emptiness, so a fabricated tag would be reported rather than
 * credited.
 *
 * <h2>Two money components, one on each side of the subtraction</h2>
 *
 * <p>This is the most consequential fact about this type, and the reference program establishes it
 * by the order of its statements rather than by any comment. {@code app/cbl/COBIL00C.cbl} line 193
 * moves {@code ACCT-CURR-BAL} into its edited work field and line 194 moves that field into the
 * map's balance field, both of them before the confirmation branch at line 210 is reached at all.
 * Line 224 then moves the same untouched {@code ACCT-CURR-BAL} into {@code TRAN-AMT}, which is why
 * the amount paid and the balance the screen showed are one number rather than two. Line 233 writes
 * the transaction; only afterwards does line 234 subtract the amount from the balance, and line 235
 * rewrite the account. Line 242 sends the screen, and no statement between line 194 and line 242
 * repopulates that map field, so the figure the operator reads is the balance as it stood before
 * the payment.
 *
 * <p>Refactoring Rationale: that one number is reported ONCE here, as the single money component
 * {@code currentBalance}, and the ambiguity it used to carry is removed by documentation rather than
 * by a second member. An earlier revision carried a single balance component documented as the
 * pre-payment figure while the contract published for this endpoint described a member of that same
 * name as the post-payment figure, so the two agreed on the name and disagreed on the meaning. That
 * is the most dangerous shape of drift available here, because each document reads coherently on its
 * own and no assertion about a value would localise the difference: both figures are exact two-place
 * decimals of the same magnitude and either is a plausible reading of the other. The document was
 * narrowed to this shape and this component's documentation now states which side of line 234 it is
 * read from, which is the distinction a schema cannot express.
 *
 * <p>Alternatives Considered: publishing the far-side figure as a second money member, with the near
 * side renamed {@code amountPaid}, was evaluated and rejected. It is not an invented value -- line 234
 * computes it and line 235 stores it -- and naming the two sides separately would move the distinction
 * into the component names where a reader cannot miss it, which is a real advantage. What settles it
 * against is that the far-side figure is INVARIABLY {@code 0.00}: line 224 moves the whole balance into
 * the amount and line 234 subtracts that amount from that balance, so a bill payment in this reference
 * pays the balance in full and admits no partial amount. A required member whose every value is zero
 * buys a caller nothing and costs it a second money field to disambiguate from the one that carries the
 * figure, and the shape would no longer correspond field-for-field to the map it migrates. A caller that
 * wants the resulting balance re-reads the account through the context that owns it, which is a second
 * call for a number that is always zero on this path.
 *
 * <p>Assumptions: the edited width on this screen matches its record width, and the match is
 * particular to this screen rather than a general rule. That program declares
 * {@code WS-CURR-BAL PIC +9999999999.99} at line 56 -- a sign, ten integer digits, a point and two
 * more -- and the map field {@code CURBALI PIC X(14)} at line 66 of
 * {@code app/cpy-bms/COBIL00.CPY} is exactly those fourteen positions, so nothing is narrowed on
 * the way out. The transaction screens do narrow: {@code TRAN-AMT PIC S9(09)V99} at line 10 of
 * {@code app/cpy/CVTRA05Y.cpy} holds nine integer digits, and {@code app/cbl/COTRN01C.cbl} declares
 * {@code WS-TRAN-AMT PIC +99999999.99} at line 49, routes the amount through it at line 177 and
 * writes the map field at line 183, showing eight. {@code app/cbl/COTRN02C.cbl} does the same in
 * reverse, parsing into the nine-digit {@code WS-TRAN-AMT-N} at line 58, editing back down through
 * the eight-digit {@code WS-TRAN-AMT-E} at line 59 and rewriting the screen field from it at lines
 * 385 and 386. The asymmetry is deliberate in the reference and is not to be aligned away: this
 * component carries the account balance's ten integer digits because transformation rule T1 makes
 * the record normative, and the shared money type's own magnitude bound, documented there as the
 * domain of {@code PIC S9(10)V99}, is that same ten-integer-digit figure at a scale of two.
 *
 * <p>Assumptions: a submission that moved no money is not answered with this shape at all, and that
 * is what makes the flag component below meaningful. Lines 198 and 199 of that program test the
 * stored balance for being less than or equal to zero -- inclusive of zero -- together with a
 * non-blank account identifier, and answer with the message at line 201 and a cursor placement at
 * line 203, writing nothing; lines 236 to 239 answer a withheld confirmation with the prompt at
 * line 237, likewise writing nothing. The published contract routes both of those turns to a
 * separate and narrower shape returned with a 200 -- an account identifier, the balance a confirmed
 * request would pay, a false flag and the message -- and reserves this shape for the 201 that
 * follows line 233. Every component here therefore has a value on every response that carries it:
 * there is no turn on which the transaction key or the amount paid is absent, which is why the
 * message is the only component of this shape the contract leaves optional -- there is no turn on
 * which the transaction key or the balance is absent.
 *
 * <h2>The transaction key is additive, and why it is reported anyway</h2>
 *
 * <p>Alternatives Considered: omitting this component for strict parity with the reference screen
 * was evaluated and rejected. {@code app/cpy-bms/COBIL00.CPY} exposes three business fields --
 * {@code ACTIDINI PIC X(11)} at line 60, {@code CURBALI PIC X(14)} at line 66 and
 * {@code CONFIRMI PIC X(1)} at line 72 -- so the key is not on the terminal at all. It is, however,
 * already generated and already persisted: lines 212 to 219 of that program move
 * {@code HIGH-VALUES} into the record key, open a browse, read backwards, end the browse, move the
 * key found into a numeric work field and add one to it, and line 233 writes the record under it.
 * The baseline does generate and store that key; the Java additionally reports it; the divergence
 * is documented rather than introduced silently. Rule T9 constrains behaviour, not the breadth of a
 * response, and withholding an already-persisted key would leave a client with no way to reference
 * the payment it had just made -- a terminal operator can re-navigate and find it, an interface
 * caller cannot.
 *
 * <p>Trade-offs: reporting it widens this body beyond what the reference terminal showed. What is
 * bought is that the payment becomes addressable, because the transaction is retrievable at the
 * detail endpoint of this same context under the key returned here. What is given up is that a
 * client comparing this body against the reference screen field for field finds one member with no
 * counterpart there, which is why the provenance is stated on the component itself.
 *
 * <p>Assumptions: the key is minted by the service and is never accepted from a client, and the
 * reference sequence is the concrete reason rather than a preference. Lines 212 to 219 hold no lock
 * across their four verbs -- the browse at lines 441 and 443 is issued with {@code DATASET},
 * {@code RIDFLD}, {@code KEYLENGTH} and the two response fields and nothing else -- so two payments
 * running together can read the same maximum and derive the same next key. Accepting a
 * client-supplied key would add a second way to collide on a key space that already admits one.
 *
 * <p><b>No paging component of any kind belongs on this type, and no paging vocabulary either.</b>
 * The point is stated because that program contains browse verbs which can be mistaken for a
 * cursor. It is not a browse screen: the browse at lines 441 and 443 carries no
 * greater-than-or-equal option, {@code READPREV} at lines 472 and 474 is the only read paired with
 * it, the end-of-file path at lines 487 and 488 moves zeros into the key, and there is no
 * {@code READNEXT} paragraph anywhere in the program's 572 lines. The pair is a maximum-key
 * generator. The shared cursor envelope {@code com.carddemo.common.web.PageResponse} is never wired
 * into the bill-payment path, and no cursor, page number, page size or counted starting position
 * appears here.
 *
 * <p>Alternatives Considered: pagination by ordinal position -- a count of rows skipped from the
 * start of a result set -- was evaluated and rejected for this package as a whole, and the
 * reference material is why that is more than a stylistic preference. An ordinal count is measured
 * against a result set that can change between two reads, so it silently skips and repeats rows
 * when rows are inserted in between, whereas a cursor keyed on the ordering column is unaffected.
 * That is a change in observable behaviour rather than in implementation. The unlocked
 * read-then-increment above is the evidence that the concurrent insert is real and not
 * hypothetical: two payments deriving the same next key land in the same region of the key space
 * while a browse is open over it.
 *
 * <h2>Money is exact fixed point and travels as a JSON string</h2>
 *
 * <p>Alternatives Considered: typing the balance as a bare arbitrary-precision decimal was
 * evaluated and rejected, and the reason is the declared type rather than the value it holds. The
 * shared {@code com.carddemo.common.money.MoneyModule} binds its serialiser and its deserialiser to
 * the {@code Money} type itself, so a component declared as a bare decimal is reached by neither
 * and is written as a plain JSON number instead: it compiles, it runs, its arithmetic is exact, and
 * its wire form is wrong. That failure mode is named because no assertion about the value would
 * localise it -- only an assertion about the serialised text would.
 *
 * <p>Alternatives Considered: a JSON number was evaluated and rejected. Most clients parse one into
 * an IEEE-754 binary floating point value on receipt, and binary64 cannot exactly represent the
 * two-place decimal fractions this field is built from. {@code ACCT-CURR-BAL PIC S9(10)V99} at line
 * 7 of {@code app/cpy/CVACT01Y.cpy} is twelve significant decimal digits, which leaves no margin
 * for an approximation, and this is the one figure of a payment a person actually reads. Underneath
 * the wire form, the shared type is held at a scale of two throughout and reduces with
 * {@code RoundingMode.HALF_UP}; the prohibition on a binary type in the money path is asserted by
 * the ArchUnit layering rules this module runs against its own compiled classes rather than by
 * convention.
 *
 * <p>Assumptions: that module is registered from the shared kernel and not from this module, so
 * nothing here registers it, annotates around it or configures a mapper, and no component needs a
 * serialisation annotation of its own. Two mechanisms in the kernel do the registration: the
 * provider-configuration file naming the module as an implementation of the platform's Jackson
 * module interface, which any mapper built with module discovery picks up, and the kernel's own
 * auto-configuration class, which declares the module as a bean only when no such bean is already
 * present. This module's entry point states the same thing from its side, recording that it
 * receives the money module through the shared kernel rather than registering it itself.
 *
 * <h2>Identifiers are digit-bearing strings, never numeric types</h2>
 *
 * <p>Assumptions: the baseline settles this itself, declaring each identifier twice over the same
 * bytes -- once as characters and once as a number. {@code app/cpy/CVCRD01Y.cpy} gives
 * {@code CC-ACCT-ID PIC X(11)} at line 34 with {@code CC-ACCT-ID-N PIC 9(11)} redefining it at line
 * 36, {@code CC-CARD-NUM PIC X(16)} at line 37 with its numeric redefinition at line 39, and
 * {@code CC-CUST-ID PIC X(09)} at line 40 with its numeric redefinition at line 42. The character
 * declaration is what the screen and the message carry; the numeric one exists so that arithmetic
 * can reach the same bytes.
 *
 * <p>Assumptions: the reference seed extract settles it beyond the declaration.
 * {@code app/data/ASCII/dailytran.txt} holds 300 records of 350 bytes each, and 30 of them carry a
 * card number whose first character is a zero. A numeric component would discard that leading zero
 * on the way out while still comparing equal on the way in, which is a silent corruption of a tenth
 * of that extract. The transaction key is the same case, and the reference program proves it from
 * the other direction: line 219 moves a {@code PIC 9(16)} work field into
 * {@code TRAN-ID PIC X(16)}, so a generated key is a zero-padded run of sixteen digit characters,
 * and the keys in that extract are zero-padded to the same width. No example value is reproduced
 * here: a primary account number does not belong in source prose even when it comes from a seed
 * extract, and the aggregate is what the type decision rests on.
 *
 * <h2>One nullable message, and the copybook asymmetry that decides it</h2>
 *
 * <p>Assumptions: the catalog width here is 75, from {@code app/cpy/CVCRD01Y.cpy}, which declares
 * {@code CCARD-ERROR-MSG PIC X(75)} at line 28 and {@code CCARD-RETURN-MSG PIC X(75)} at line 29.
 * The two are the same width and are not interchangeable, because only one of them carries a
 * sentinel: line 30 attaches a message-off condition valued at low values to the return message
 * alone, and the error message has none. That asymmetry is derived from the copybook rather than
 * chosen by convention, and it settles two component decisions. This type carries exactly one
 * return or confirmation message and it is nullable, because the sentinel state is what null
 * represents. It carries no error-message component at all: the error path belongs to
 * {@code com.carddemo.common.error.ApiError} and the shared advice that produces it.
 *
 * <p>Assumptions: the sentinel is low values and not spaces, and substituting one for the other
 * changes observable behaviour. An absent message and a seventy-five-character blank message are
 * different states, and a client rendering nothing for one and an empty band for the other would
 * diverge on exactly the distinction that copybook draws. This component is therefore null when
 * there is no message and is never a blank-filled string of the catalog width.
 *
 * <p>Assumptions: the reference program sets no message on the one turn this shape answers, so this
 * component is null on a faithful posted response. {@code WS-MESSAGE PIC X(80) VALUE SPACES} at
 * line 39 is that program's only message work field, the confirmed branch at lines 210 to 235 never
 * moves anything into it, and line 293 moves whatever it holds into the screen's message field --
 * so the operator's message band is blank on a posted payment. Blank is not what travels here: null
 * is, because nothing was ever set, and a run of spaces at the declared width would publish a
 * present-and-empty message where the reference published none. The component is declared
 * nonetheless, because the published contract declares it as this shape's one optional member, and
 * because omitting it would make an absent message unrepresentable rather than absent.
 *
 * <p>Assumptions: the sentences in this component's domain are reproduced character for character
 * from the program line that supplies them, as transformation rule T8 requires, trailing ellipsis
 * included. None of the four reaches this shape, and stating where each one goes instead is what
 * shows that none was dropped. Two belong to the turns that answer without writing and travel on
 * the narrower 200 shape the published contract declares for them:
 * {@code 'Confirm to make a bill payment...'} at line 237, the withheld-confirmation turn, and
 * {@code 'You have nothing to pay...'} at line 201, the turn whose balance is not positive under
 * the test at lines 198 and 199. The other two are complaints about one field and travel in the
 * per-field error array instead: {@code 'Invalid value. Valid values are (Y/N)...'} at line 187,
 * and {@code 'Acct ID can NOT be empty...'} at line 161, whose capitalisation is the program's own
 * and which {@code BillPaymentRequest} already carries onto its not-blank constraint. The program's
 * failure sentences are cited by line rather than reproduced, each being the property of the
 * paragraph that detects it: lines 361, 392 and 425; line 368; line 399; line 432; line 456; lines
 * 463 and 492; line 536; and line 543. Two sentences that differ, however slightly, are never
 * merged, and neither is a repetition collapsed: lines 463 and 492 carry the same sentence from two
 * different paragraphs and each keeps its own. Message text lives as a Java constant, never as a
 * resource-bundle entry.
 *
 * <p>Assumptions: three further widths exist in the reference material and none of them governs
 * this component. 50 is the common-message form at {@code app/cpy/CSMSG01Y.cpy}, whose two literals
 * sit on lines 19 and 21 beneath the {@code PIC X(50)} declarations on lines 18 and 20, so the
 * declaration and the literal are two separate constraints and the pair has to be read by eye. 72
 * is the abend message at {@code app/cpy/CSMSG02Y.cpy} line 28, in a file that is 35 lines long, so
 * a citation beyond that is out of range; the shared kernel carries that group and this package
 * does not. 78 is the width of the screen error field itself, at {@code app/cpy-bms/COBIL00.CPY}
 * line 78 on the input half and line 140 on the output half that redefines it from line 79. And 80
 * is not a width of this interface at all: {@code app/cbl/COBIL00C.cbl} declares
 * {@code WS-MESSAGE PIC X(80)} at line 39 as internal work storage, and
 * {@code app/cbl/COTRN01C.cbl} line 217 shows where such a field goes, moving its own
 * eighty-character equivalent into the seventy-eight-character screen field and truncating two
 * bytes on the way out.
 *
 * <h2>What this type deliberately does not carry</h2>
 *
 * <p>Assumptions: of the ten values lines 220 to 229 move onto the transaction the reference
 * program writes, eight have no component here, and naming all ten is what shows the omission to be
 * deliberate rather than partial. Those lines move a type code of {@code '02'}, a category of
 * {@code 2}, a source of {@code 'POS TERM'}, a description of {@code 'BILL PAYMENT - ONLINE'}, the
 * account balance as the amount, the cross-referenced card number, a merchant identifier of
 * {@code 999999999}, a merchant name of {@code 'BILL PAYMENT'} and {@code 'N/A'} for both the
 * merchant city and the merchant postal code. Lines 230 to 232 then move one timestamp into both
 * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} in a single statement. Eight of those are literals
 * of the writing program rather than outcomes of the request, and each is already retrievable at
 * the detail endpoint under the key this response returns. The two exceptions are the amount at
 * line 224 and the card number at line 225: neither is a literal, and both are read from records the
 * request resolved. The amount IS reported here, because line 224 moves the very balance this
 * response's money component carries, so reporting the balance reports the amount. The card number is
 * NOT: {@code app/cpy-bms/COBIL00.CPY} declares no card field at all -- its three business fields are
 * the account identifier at line 60, the balance at line 66 and the confirmation at line 72 -- so the
 * reference screen never shows one, and publishing it would be an additive disclosure decision on a
 * response whose caller already named the account. A client that needs the card the payment was
 * written against reads the transaction the {@code Location} header addresses.
 *
 * <p>Assumptions: the mixed literal forms above are transcribed as the program writes them rather
 * than normalised, because the form tracks the picture class of the field being moved into.
 * {@code '02'} is quoted because {@code TRAN-TYPE-CD} is {@code PIC X(02)} at line 6 of
 * {@code app/cpy/CVTRA05Y.cpy}, while {@code 2} and {@code 999999999} are bare because
 * {@code TRAN-CAT-CD} is {@code PIC 9(04)} at line 7 and {@code TRAN-MERCHANT-ID} is
 * {@code PIC 9(09)} at line 11. Quoting the numeric two here would misreport the reference, so the
 * forms are documented and not tidied.
 *
 * <p>Trade-offs: the written record is not echoed in full on this response. One of its fields is --
 * the amount at line 224, which is the same number as the balance this response carries -- because it
 * states what the payment did rather than how the writing program was configured, and the remainder
 * are not. A client wanting the transaction as stored reads the detail endpoint of this context,
 * which returns it at record width. What is bought is that this body reports the outcome of the
 * operation and the two record values that qualify it; the cost is one further call for a client
 * that wants the whole record, and the key that makes that call possible is the first component
 * below.
 *
 * <p>Assumptions: the card-number component here carries the masked form only, and the unmasked
 * sixteen characters never reach it. Line 225 takes the card number from the cross-reference record
 * read at line 211 and writes all sixteen characters onto the transaction, while the reference map
 * declares no card field at all, so what this component publishes is neither the record form nor a
 * screen field: it is twelve asterisks followed by the last four digits, the one form the published
 * contract admits on any response of this service. The masking is applied in
 * {@code com.carddemo.transaction.mapper} by the shared masker, never in this transfer object,
 * which is why the component is an ordinary string that claims to enforce nothing -- the same
 * reasoning the detail response in this package records for its own masked component. Nothing here
 * carries a card verification value, a credential, a connection string or an endpoint.
 *
 * <p>Assumptions: this record carries no session state and no re-entry marker of any kind -- no
 * resubmission flag, no first-entry flag and no turn counter. The reference distinguishes a first
 * turn from a resubmission only because the communication area survives the turn the client echoes
 * it across, and that discriminator has no counterpart in a stateless response.
 *
 * <p>Assumptions: the six framing fields present on the reference map appear on no component here.
 * {@code app/cpy-bms/COBIL00.CPY} declares a four-character transaction name at line 24, two
 * forty-character title constants at lines 30 and 48, an eight-character program name at line 42
 * and an eight-character date and time at lines 36 and 54. The transaction and program names are
 * identities of the reference transaction monitor with no target analogue, the two titles belong to
 * the user interface screen header, and the date and time are clock reads the client renders. No
 * component here reads a clock.
 *
 * <p>Assumptions: the declared widths above are documented rather than annotated on this type. A
 * size constraint is evaluated on a value entering the application and this record only leaves it,
 * so a constraint here would never be exercised; the request side of this package is where the
 * copybook widths become constraint values. That is also why this file declares no import from
 * {@code jakarta.validation} even though the module puts it on both classpaths.
 *
 * <p>Assumptions: this type imports no persistence entity from
 * {@code com.carddemo.transaction.domain} and reaches no sibling service. Its one import is the
 * shared money type, which is the Java analogue of resolving a record layout through a single
 * compiler include path -- the discipline the repository states for the reference layouts at
 * {@code tests/README.md} lines 540 to 542, where a layout is never duplicated. Converting from the
 * entity belongs to {@code com.carddemo.transaction.mapper}, and the prohibition on reaching
 * across that boundary is asserted by the ArchUnit layering rules this module runs against its own
 * classes.
 *
 * <h2>Five components, and the two the published contract used to add</h2>
 *
 * <p>Refactoring Rationale: this record and
 * {@code services/transaction-service/src/main/resources/openapi/transaction-api.yaml} now describe
 * ONE shape, and an earlier revision of this section recorded that they did not -- the document
 * declared seven members where this record declared four, described its balance member as the figure
 * line 234 computes rather than the figure line 194 displays, and no mapper constructed either shape.
 * Recording the disagreement was not a resolution: a published contract a service contradicts is worse
 * than either shape alone, because a client generated from it fails against a service that is itself
 * correct. The document was narrowed to this shape rather than this shape widened to the document, and
 * the two members it dropped were dropped for reasons specific to each.
 *
 * <p>Refactoring Rationale: the dropped POST-PAYMENT balance was not merely redundant, it was
 * information-free. Line 224 moves the whole balance into the transaction amount and line 234 then
 * subtracts that amount from that balance, so the figure line 234 leaves behind is ALWAYS exactly
 * zero -- a bill payment in this reference pays the balance in full and admits no partial amount. A
 * member whose every value is zero tells a caller nothing, while costing it a second money field to
 * disambiguate from the one that carries the figure. The balance BEFORE the payment is the value the
 * screen displays and the value that was paid, so one money component reports both and its
 * documentation says so.
 *
 * <p>Assumptions: the component's NAME is the reference screen's own label, which is what settles the
 * objection that a member called {@code currentBalance} ought to report the balance after the payment.
 * {@code CURBALI PIC X(14)} at line 66 of {@code app/cpy-bms/COBIL00.CPY} is the field the map declares
 * for it, line 194 fills that field from the untouched balance, and nothing refills it before the send
 * at line 242 -- so on the reference screen the label "current balance" shows the PRE-payment figure on
 * the very turn the payment posts. Publishing the post-payment figure under that label would name the
 * screen's field and report a different number than the screen does.
 *
 * <p>Trade-offs: a client that genuinely needs the figure the account was left holding therefore has to
 * re-read the account, and a client that wants "the amount I just paid" reads a member named for a
 * balance. Both costs are accepted, and the alternative was measured rather than dismissed: publishing
 * a second money member and an {@code amountPaid} beside it makes the pair self-describing, at the
 * price of one member that is invariably {@code 0.00} and of a response that no longer corresponds
 * field-for-field to the map it migrates. The transaction the {@code Location} header addresses carries
 * the amount under its own name for a caller that wants it there.
 *
 * <p>Refactoring Rationale: the dropped masked card number has no face on the reference screen at
 * all. Line 225 moves {@code XREF-CARD-NUM} into the transaction record, so the number is persisted,
 * but {@code app/cpy-bms/COBIL00.CPY} declares no card field and the screen therefore never shows it.
 * Publishing it would be additive rather than parity-preserving, and it would put a masking decision
 * on a response whose caller already knows the account it named. A client that needs the card the
 * payment was written against reads the transaction the {@code Location} header addresses.
 *
 * <p>Assumptions: the flag recording that money moved is RETAINED where the two money members were
 * not, because it is a discriminator rather than a datum. The same contract publishes the
 * withheld-confirmation shape with the same member fixed to the opposite value, so the pair is
 * distinguishable from a body alone -- which is the convention this migration already follows for the
 * sign-on outcome, discriminated by a declared value rather than by the absence of a member. Dropping
 * it here would have left half of a justified pair.
 *
 * <h2>Why a small typed body replaces a whole screen image</h2>
 *
 * <p>Refactoring Rationale: the first mechanism being replaced is the pseudo-conversational send,
 * and what was wrong with it is that the meaning of the balance depended on when in the turn
 * sequence it was read. The reference reports an outcome by rebuilding and resending the entire
 * twenty-four-by-eighty screen image with the balance embedded at a fixed position in it, and the
 * same position holds a pre-payment figure on one turn and an unrefreshed one after the next --
 * lines 193 and 194 fill it, line 234 changes the underlying value, and line 242 sends the field
 * unchanged. A reader of that position cannot tell which figure it holds without tracing the path
 * that produced it. Here the two figures occupy two separately-named components whose meanings are
 * fixed by those names and by the documentation on them, and neither varies by turn.
 *
 * <p>Refactoring Rationale: the second is the edit mask the reference publishes its money through,
 * and what was wrong with it is that it coupled the value's precision to a display width.
 * {@code WS-CURR-BAL PIC +9999999999.99} at line 56 is a presentation declaration, and on the
 * transaction screens the equivalent declaration is one integer digit narrower than the record it
 * renders, so the precision a caller received depended on which screen had answered. Here the
 * component is an exact decimal at the record's own scale and the rendering decision belongs to the
 * client.
 *
 * <p>Alternatives Considered: Lombok and MapStruct were both evaluated and both rejected for this
 * package as a whole; {@code com.carddemo.transaction.dto}'s package charter carries the reasoning,
 * which turns on generated members being undocumentable and on copybook-to-transfer-object mapping
 * being non-mechanical -- it drops {@code FILLER}, masks a primary account number to its last four
 * digits, suppresses a card verification value, encrypts protected identifiers and renames
 * misspelled fields, each of which needs a justification at the mapping site that a generated
 * mapper has nowhere to hold. A Java 21 record with hand-written mapping is what replaces them.
 *
 * @param transactionId the key of the transaction the payment wrote, from
 *     {@code TRAN-ID PIC X(16)} at line 5 of {@code app/cpy/CVTRA05Y.cpy}; generated by the service
 *     and never accepted from a client, and borne as digit characters so that a leading zero
 *     survives, and never absent on this shape because the reference program reaches the generating
 *     sequence only on the confirmed path at line 210, which is the only path this shape answers
 * @param accountId the account whose outstanding balance this payment settled, echoed from the
 *     request, from {@code ACTIDINI PIC X(11)} at line 60 of {@code app/cpy-bms/COBIL00.CPY} and
 *     keyed as {@code CC-ACCT-ID PIC X(11)} at line 34 of {@code app/cpy/CVCRD01Y.cpy}; borne as
 *     digit characters so that a leading zero survives the round trip
 * @param currentBalance the balance as it stood BEFORE the payment, which is therefore also the
 *     amount that was paid, from {@code CURBALI PIC X(14)} at line 66 of that map and
 *     {@code ACCT-CURR-BAL PIC S9(10)V99} at line 7 of {@code app/cpy/CVACT01Y.cpy}; exact at ten
 *     integer digits and a scale of two, serialised as a quoted decimal string and never as a JSON
 *     number, and deliberately not the figure line 234 leaves the account holding
 * @param transactionId the key of the transaction the payment wrote, from
 *     {@code TRAN-ID PIC X(16)} at line 5 of {@code app/cpy/CVTRA05Y.cpy}; generated by the service
 *     and never accepted from a client, and borne as digit characters so that a leading zero
 *     survives, a turn that writes no transaction having no key to report because the reference
 *     program reaches the generating sequence only on the confirmed path at line 210
 * @param paid always {@code true} on this shape, discriminating a posted payment from the
 *     withheld-confirmation shape the same contract publishes with the value fixed to {@code false};
 *     stated as a value rather than left to be inferred from the status code, so that a body logged or
 *     replayed on its own cannot be mistaken for a preview
 * @param returnMessage the confirmation or advisory sentence accompanying the turn, from
 *     {@code CCARD-RETURN-MSG PIC X(75)} at line 29 of {@code app/cpy/CVCRD01Y.cpy}; null when
 *     there is no message, mirroring that field's low-values sentinel at line 30, which is the one
 *     absence this shape models from the copybook rather than from there being nothing to report,
 *     and never a blank-filled string of that width
 */
public record BillPaymentResponse(
    // WHY : Assumptions: the component order below is the property order of the BillPaymentResponse
    //       schema in openapi/transaction-api.yaml, which is also its required-list order. Records
    //       expose their components positionally through the canonical constructor, so matching the
    //       published order keeps a hand-written construction call readable against the document it
    //       implements; TransactionApiContractTest asserts the names and the set, not the order.
    // WHY : Alternatives Considered: strict screen parity would omit this component, since
    //       COBIL00.CPY declares no field for it. Rejected because lines 212 to 219 already mint
    //       the key and line 233 already persists it, so reporting it is additive, while
    //       withholding it would leave a caller unable to reference the payment it just made.
    // WHY : Assumptions: those four verbs hold no lock, so two payments can derive the same next
    //       key. That is why the key is minted by the service and never accepted from a client.
    String transactionId,
    // WHY : Assumptions: CVCRD01Y declares every identifier twice over the same bytes, as
    //       characters at line 34 and as a number at line 36, and 30 of the 300 seed records in
    //       app/data/ASCII/dailytran.txt carry a card number beginning with a zero. A numeric
    //       component would drop such a leading zero on the way out while still comparing equal
    //       on the way in.
    String accountId,
    // WHY : Assumptions: this is the balance BEFORE the payment, and statement order is what
    //       fixes it: COBIL00C.cbl line 193 reads it, line 194 fills the screen field, line 224
    //       reuses the same untouched value as the transaction amount, line 233 writes, and only
    //       line 234 subtracts. Nothing refills that field before line 242 sends it.
    // WHY : Trade-offs: no second component reports the balance line 234 leaves behind, so a
    //       client needing it re-reads the account. Adding one would publish a figure this
    //       screen never displays -- and one that is invariably 0.00, because line 224 moves the
    //       whole balance into the amount that line 234 then subtracts.
    // WHY : Assumptions: MoneyModule binds its serialiser to this exact type, so the declared
    //       type is what selects the quoted-string wire form. Substituting a bare decimal here
    //       compiles and runs and silently emits a JSON number instead.
    Money currentBalance,
    // WHY : Assumptions: the value is fixed rather than computed, and the factory below is what
    //       fixes it, so no caller can construct this shape reporting that no payment was made.
    //       A boolean whose only value is true looks pointless in isolation; what it is for is the
    //       PAIR -- the withheld-confirmation shape carries the same member fixed to false, so a
    //       body read without its status code still says which of the two it is.
    boolean paid,
    // WHY : Assumptions: CVCRD01Y line 30 attaches a low-values sentinel to this field alone,
    //       and line 28's error message carries none, so absence is representable for this one
    //       field and null is what represents it. Spaces are a different state and are not
    //       substituted for it: WS-MESSAGE at COBIL00C.cbl line 39 starts as spaces and the
    //       confirmed branch never sets it, which is nothing set rather than an empty message.
    String returnMessage) {

    /**
     * The only value the payment-posted shape's discriminator takes.
     *
     * <p>Assumptions: named rather than written as a literal at the one site that sets it, so that the
     * published {@code const} in the contract and the value the service emits are compared against one
     * symbol instead of against a bare {@code true} a reader has to interpret.
     */
    public static final boolean PAYMENT_POSTED = true;

    /**
     * Builds the shape for a payment that was posted, fixing the discriminator.
     *
     * <p>Refactoring Rationale: the discriminator is set here rather than accepted as an argument. A
     * component whose contract is that it always carries one value is a component a caller can get
     * wrong, and getting it wrong would emit a body reporting that no money moved from the path that
     * moved it -- indistinguishable, to a client reading the body alone, from the preview shape. Fixing
     * it at the one construction site makes the published {@code const} true by construction rather
     * than by review.
     *
     * <p>Assumptions: the canonical constructor stays available and is not hidden, because deserialising
     * this shape in a test or a client needs it. What this factory removes is the opportunity for
     * SERVICE code to choose the value, which is the only place the choice could be wrong.
     *
     * @param accountId the account that was paid, as digit characters; must not be {@code null}
     * @param currentBalance the balance as it stood before the payment, which is also the amount paid;
     *     must not be {@code null}
     * @param transactionId the key of the transaction the payment wrote, as digit characters; must not
     *     be {@code null}
     * @param returnMessage the accompanying sentence, or {@code null} when there is none
     * @return the shape with its discriminator fixed to {@link #PAYMENT_POSTED}, never {@code null}
     */
    public static BillPaymentResponse posted(String accountId, Money currentBalance,
            String transactionId, String returnMessage) {
        return new BillPaymentResponse(transactionId, accountId, currentBalance, PAYMENT_POSTED,
                returnMessage);
    }
}
