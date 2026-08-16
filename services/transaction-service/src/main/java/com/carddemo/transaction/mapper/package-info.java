/**
 * Hand-written anti-corruption layer between the ledger context's persistence
 * entities and its wire shapes, and the only place in this module where a
 * copybook representation concern may appear.
 *
 * <p><b>Purpose.</b> Every conversion between
 * {@code com.carddemo.transaction.domain} and
 * {@code com.carddemo.transaction.dto} happens here, and nowhere else. The
 * reference record format reaches this package and stops: fixed widths, zoned
 * decimal sign overpunch, {@code FILLER}, account-number masking, suppression
 * of a card verification value, and the baseline's own field spellings are
 * resolved on the way through, so no type on either side of this package has to
 * know that a 350-byte record ever existed. That is the whole reason the layer
 * is worth an extra hop, and it is why the two sibling charters both name this
 * package as the single permitted home for those concerns rather than leaving
 * the boundary to be inferred.
 *
 * <p>Assumptions: inventing those at-clauses would do more than add noise.
 * Javadoc has no parameter, return or exception concept for a package, and the
 * ruleset audits at-clause bodies for emptiness, so a fabricated at-clause
 * would either be discarded by the tool or reported as empty. The rule
 * enumerates four docstring elements at its lines 18 to 21, and exactly one of
 * the four has a subject in this compilation unit; the paragraph above accounts
 * for the other three.
 *
 * <h2>The closed inventory: three files, ALL THREE landed</h2>
 *
 * <p>Three {@code .java} files constitute this package and no more:
 *
 * <ul>
 *   <li>{@code package-info.java} -- this charter. LANDED.</li>
 *   <li>{@code TransactionMapper} -- LANDED. Conversions for the three transaction
 *       screens, serving {@code TransactionListItemResponse},
 *       {@code TransactionDetailResponse}, {@code TransactionAddRequest} and
 *       {@code TransactionAddResponse}.</li>
 *   <li>{@code BillPaymentMapper} -- LANDED. Conversions for bill payment, serving
 *       {@code BillPaymentRequest} and {@code BillPaymentResponse}, both of which are
 *       themselves landed in the sibling {@code dto} package.</li>
 * </ul>
 *
 * <p>Assumptions: the sibling {@code dto} package now holds a THIRD payment shape,
 * {@code BillPaymentPreview}, and this package deliberately does not serve it -- so the inventory is
 * still closed at three and no fourth mapper is missing. That shape carries an account identifier, a
 * balance and a sentence, and all three are already in hand at the service that answers the turn: none
 * is read from a copybook layout, none is padded, narrowed, masked or renamed, and there is therefore
 * no representation concern for this package to absorb. A mapper method for it would translate nothing,
 * which is the one thing this package's own charter says a mapper must not be. Its construction site is
 * the withheld factory on the record itself, which fixes the discriminator the published schema
 * declares constant -- the same discipline {@code BillPaymentResponse.posted} applies on the other
 * arm, reached from this package rather than from the service only because that shape genuinely does
 * convert a ledger row.</p>
 *
 * <p>Assumptions: the inventory is closed at three compilation units and every entry is marked
 * against the file that is actually there. A charter is the one place a reader consults to find out
 * what converts what, so a census that under-reports is worse than none -- it makes a present class
 * look missing and gives a reader no way to tell an unauthored contract from a mislaid one.
 *
 * <p>Assumptions: each mapper is authored alongside the conversion it governs rather than ahead of
 * it. A mapper with no shape to convert into
 * converts nothing that can be exercised, and this package's whole purpose is to be the one place
 * where copybook representation concerns are justified at the point of decision -- masking, padding,
 * narrowing, renaming. Those decisions are written when the conversion they govern is written, and
 * {@code BillPaymentMapper} followed its response record for exactly that reason. The inventory is
 * closed at three either way; what changed is that it is now checkable by listing the directory.
 *
 * <p>Assumptions: a landed mapper is exercisable without its controller. The two classes are
 * pure conversion with no repository, no clock read and no framework dependency beyond the
 * stereotype, so each method is directly callable from a unit test with fabricated arguments.
 * That is what made authoring {@code BillPaymentMapper} correct rather than premature: the
 * copybook representation decisions it records -- the eleven bill-payment literals, the single
 * instant written to both timestamp fields, the pre-payment balance used as the amount -- are
 * decisions of the conversion itself, and they are justified at the point of decision here
 * rather than deferred to whichever service later calls it.
 *
 * <p>Alternatives Considered: the arrangement a reader is most likely to expect
 * is one mapper per persistence entity, which would make six files rather than
 * three, because {@code domain} holds four entities. It is not available, and
 * the reason is a fact about the sibling package rather than a preference here.
 * The {@code dto} package is a closed inventory of fourteen files, eleven of them
 * records -- all eleven authored -- and those eleven serve
 * the four migrated online screens only. Four of the eleven need no mapper of their own.
 * Two are the preview shapes the two write screens answer their non-writing turns with:
 * neither converts a stored row, because on those turns no row exists, so each is composed
 * directly by the service from the value its own validation derived. The other two are the
 * copy-last request and its copied draft, and their exclusion is for a different reason --
 * the draft is composed from the COPIED SUBMISSION the service has already built and
 * validated, not from the stored row a second time, because reading the row again is
 * precisely the divergence the draft exists to remove. No
 * transfer object exists for {@code DailyTransaction}, for
 * {@code TransactionCategoryBalance} or for {@code TransactionReject}, so a
 * mapper for any of the three would have nothing on the far side to map to and
 * would compile only by inventing the shape it converted into.
 *
 * <p>Assumptions: those three entities are reached by other means, and each
 * route is recorded so the absence does not read as an omission. The
 * {@code DailyTransaction} to {@code Transaction} posting projection is the
 * batch context's work, transcribed there from {@code app/cbl/CBTRN02C.cbl}
 * lines 424 to 444, whose three writes commit as one unit; that program is read
 * for the schema contract and never imported. The category balance is written
 * by the same nightly job and read by reporting through a cross-schema view.
 * The reject row keeps the 350-byte record image verbatim -- line 447 of that
 * program moves {@code DALYTRAN-RECORD} into the reject area whole -- so there
 * is no field decomposition for a mapper to perform: a record that has already
 * failed validation cannot be trusted to parse, and retaining its bytes
 * unaltered is what leaves a re-drive possible once the cause is addressed.
 *
 * <p>Assumptions: three counts of a small number meet in this module and are
 * kept textually distinct throughout. The module holds eight Java packages and
 * therefore eight package charters. The {@code dto} package holds fourteen files,
 * which is a different quantity that once happened to share a digit and no longer
 * does -- that quantity moved to twelve when each write screen's non-writing turn
 * gained its own shape and then to fourteen when the copy-last operation gained its own
 * request shape and its own draft, while the package count did not move at all. This package
 * holds three files. None of the three figures is derivable from either of the
 * others, so each is stated where it is owned rather than restated here, and each is
 * verified against its own directory rather than inherited.
 *
 * <h2>Why two mappers and not one</h2>
 *
 * <p>Alternatives Considered: a single {@code LedgerMapper} carrying every
 * conversion in the module was evaluated and rejected. It is the smaller file
 * count, and on a surface reading the two mappers convert the same entity. Five
 * differences make one class carry two contracts that contradict each other,
 * and each of the five is a property of the reference programs rather than a
 * matter of taste:
 *
 * <ul>
 *   <li>The transfer-object sets are disjoint. Nothing
 *       {@code BillPaymentMapper} produces is consumed by a transaction screen,
 *       and nothing {@code TransactionMapper} produces is consumed by bill
 *       payment.</li>
 *   <li>{@code BillPaymentResponse} reports a balance this context does not
 *       own. {@code app/cbl/COBIL00C.cbl} declares the account master at its
 *       lines 41 and 42, reads the account record at lines 345 and 346 and
 *       rewrites it at lines 379 and 380, so bill payment reads and writes
 *       across what is now a context boundary. A shared file handle has no
 *       equivalent here, so the balance arrives as a scalar argument obtained
 *       from the owning context -- a parameter shape no other mapping method in
 *       this package needs, and one that would sit unused on every transaction
 *       conversion if the two were merged.</li>
 *   <li>Bill payment supplies ten values the transaction screens never supply.
 *       Lines 220 to 229 of that program move a fixed type code, a fixed
 *       category, a fixed source, a fixed description, the current account
 *       balance as the amount, the cross-referenced card number, a fixed
 *       merchant identifier, a fixed merchant name and two literal placeholders
 *       for merchant city and merchant postal code. Those ten are verbatim
 *       constants of the payment flow, not defaults of the record.</li>
 *   <li>The two flows stamp time differently, which is set out below in its own
 *       section.</li>
 *   <li>The two flows read the confirmation field differently. In
 *       {@code app/cbl/COBIL00C.cbl} lines 178 to 180 a response of {@code 'N'}
 *       clears the screen, whereas in {@code app/cbl/COTRN02C.cbl} lines 173 to
 *       176 {@code 'N'} falls through alongside blank and low values to the
 *       same confirmation prompt. The same keystroke therefore means "abandon"
 *       in one flow and "you have not answered yet" in the other.</li>
 * </ul>
 *
 * <p>Trade-offs: two classes cost a reader one extra file to open and duplicate
 * the small amount of structure any mapper has. What that buys is that neither
 * class holds a branch on which flow called it. Merging them would introduce
 * exactly such a branch for the timestamp form and again for the confirmation
 * semantics, and a conditional on the caller inside a conversion is where a
 * later change silently applies one flow's rule to the other.
 *
 * <h2>Both code generators are rejected, and the reason is this package</h2>
 *
 * <p>Alternatives Considered: MapStruct, for generating these conversions. It
 * is rejected, and the ground is the same one that makes this package exist at
 * all: the mapping is not mechanical. It drops {@code FILLER}, masks a primary
 * account number to its last four digits, admits no card verification value
 * into any response, carries money as a string so that no client parses it into
 * a binary floating-point double, and preserves the baseline's field spellings
 * rather than tidying them. Every one of those five is a decision a reader
 * cannot recover from the code, so every one needs a justification standing
 * beside the statement that performs it -- and a generated mapper has nowhere
 * to hold one. The rejection is also enforced by absence rather than by
 * convention: no MapStruct coordinate is declared in
 * {@code services/transaction-service/pom.xml} or in the parent aggregator, so
 * an annotation reaching for it would not resolve. That POM records the same
 * rejection in its own dependency commentary, and the ruleset's companion
 * suppression file records it a third time, which is why this charter states it
 * rather than deferring to one of them.
 *
 * <p>Alternatives Considered: Lombok, for generated accessors and builders on
 * whatever intermediate shapes a mapper needs. It is rejected because a
 * generated member arrives with no Javadoc, so the accessors it produces would
 * fail the documentation gate the parent POM binds ahead of compilation: the
 * tool would breach the rule it was introduced to save effort under. There is
 * no annotation-driven escape from that gate either, as the next section
 * records. Java 21 {@code record} types with explicit constructors give the
 * same brevity while leaving every member documentable, and Lombok is likewise
 * absent from both POMs.
 *
 * <h2>No field is renamed in this module</h2>
 *
 * <p>Assumptions: every field name beneath this package matches its copybook
 * exactly, and this is a decision rather than an oversight. A reader who knows
 * the neighbouring services will arrive expecting a spelling change here,
 * because the wider migration does rename three misspelled baseline names in
 * its own target columns. All three belong elsewhere:
 * {@code ACCT-EXPIRAION-DATE} to the account context,
 * {@code CARD-EXPIRAION-DATE} to the card context and
 * {@code PA-MERCHANT-CATAGORY-CODE} to the authorization context. None of the
 * three names occurs in {@code app/cpy/CVTRA05Y.cpy},
 * {@code app/cpy/CVTRA06Y.cpy} or {@code app/cpy/CVTRA01Y.cpy}, which are the
 * only record layouts this context owns, so there is nothing here to rename and
 * a rename introduced for symmetry would invent a divergence from the reference
 * layout in the one package whose job is to absorb such differences rather than
 * create them.
 *
 * <h2>Nothing in this package may be exempted from the documentation gate</h2>
 *
 * <p>Assumptions: the ruleset's companion suppression file admits generated
 * sources and test fixture material and nothing else. It declares two entries,
 * one matching a path segment pair beneath a module's build output directory
 * and one matching a fixtures directory beneath a test resources tree, and its
 * closing block names the plausible additions that must never be made. A mapper
 * package is named there explicitly, ahead of every other trap, on the stated
 * ground that the word reads as "generated" in most codebases and here means
 * the opposite. Nothing beneath a module's main Java source tree may be
 * suppressed at all, so relaxing the gate for this package is a breach of the
 * rule rather than a build-configuration choice, and the remedy for a gate
 * failure here is to write the missing documentation.
 *
 * <p>Alternatives Considered: an in-code bypass is the form the attempt usually
 * takes, and it is unavailable rather than merely discouraged. The ruleset
 * configures none of the three suppression filters that would read a marker
 * comment or an annotation, so a magic comment suppresses nothing and an
 * annotation-based suppression suppresses nothing. Were any of the three
 * configured, the gate could be switched off line by line with no single
 * artifact showing that it had been; their omission is what makes the
 * suppression file a complete and reviewable record.
 *
 * <p>Assumptions: two details of that file are recorded here because they are
 * where an attempt at something already forbidden goes wrong in a way that
 * breaks more than the attempt. Its {@code suppress} element is declared empty
 * and its attribute list has no {@code reason} among the names it admits, so
 * the widely copied idiom that writes a reason onto the element makes the
 * document invalid against its own document type definition; because the filter
 * that reads it is configured to fail closed, the whole reactor then stops
 * before a single class is compiled. Its {@code files} attribute is a regular
 * expression matched against the path, not a shell glob, so a pattern written
 * in glob form either matches nothing or matches far more than its author read
 * it as matching.
 *
 * <p>Assumptions: the gate reaches this package harder than it reaches most.
 * The ruleset widens the visibility scope of its type-level documentation
 * checks all the way down, naming a mapper as the reason, because a mapper is
 * often package-private precisely because it is internal and internal is where
 * an undocumented type costs a later reader most. Because that scope is
 * inclusive downwards, a private helper nested inside a mapper is audited on
 * the same terms as the mapper itself.
 *
 * <h2>The four transformation families</h2>
 *
 * <p>Four kinds of change happen in this package and no fifth. Each is recorded
 * with the reference line that authorises it, so that a later reader can check
 * the decision instead of re-deriving it from the layouts.
 *
 * <p><b>One. {@code FILLER} is dropped, and each drop is recorded.</b>
 * Transformation rule T1 makes the copybook normative, and three of its records
 * end in padding: {@code app/cpy/CVTRA05Y.cpy} line 18 and
 * {@code app/cpy/CVTRA06Y.cpy} line 18 each declare {@code PIC X(20)}, and
 * {@code app/cpy/CVTRA01Y.cpy} line 10 declares {@code PIC X(22)}. Those bytes
 * pad a record to its fixed length -- 350, 350 and 50 respectively -- and carry
 * no value, so no column and no transfer-object component exists for them. The
 * drop is nonetheless recorded per record rather than left implicit, because it
 * is the one transformation that makes the target narrower than the source, and
 * a reader reconciling a 350-byte record against a component list needs the
 * difference accounted for.
 *
 * <p><b>Two. A primary account number is masked to its last four digits in
 * every response, and no card verification value is ever returned.</b> The
 * masking happens here because here is the only place it can happen once: a
 * mask applied in a controller would have to be applied in each controller, and
 * a mask applied on the entity would corrupt the value the posting and
 * reporting paths read.
 *
 * <p>Trade-offs: this narrows what the reference screen showed, and the
 * divergence is stated rather than glossed. {@code app/cbl/COTRN01C.cbl} line
 * 179 moves {@code TRAN-CARD-NUM} straight into the screen field, and
 * {@code app/cpy-bms/COTRN01.CPY} line 72 declares that field
 * {@code CARDNUMI PIC X(16)}, so the reference detail screen renders the full
 * sixteen-digit account number to any operator who reaches it. The Java returns
 * the last four digits instead. What is given up is that an administrator
 * reading a single transaction no longer sees the whole number from this
 * endpoint; what is gained is that the number stops being present in every
 * response body, log line and browser cache that a detail view touches, and the
 * reference screen's audience was a terminal inside a controlled network
 * whereas this response crosses a public edge. Administrative access to a full
 * number, where it is needed, belongs to the card context's own detail endpoint
 * and not to a ledger response.
 *
 * <p>Assumptions: no record this context owns declares a card verification
 * value, so nothing here suppresses one today. The prohibition is stated
 * anyway, because this package is the only place a value joined from another
 * context could enter a response, and a rule recorded before it has a subject
 * is a rule the next author reads before writing the join rather than after.
 *
 * <p><b>Three. Money crosses this package as an exact fixed-point value and
 * leaves it as a JSON string.</b> Transformation rule T3 admits no exception:
 * {@code BigDecimal} carried at scale 2 with {@code RoundingMode.HALF_UP}
 * through {@code Money} from {@code com.carddemo.common.money}, serialised by
 * {@code MoneyModule}. Neither binary floating-point type may appear anywhere
 * in the money path, and a JSON number may not either; the prohibition is
 * asserted by the shared kernel's ArchUnit layering rules, which run against
 * this module's own compiled classes, so it fails a build rather than a review.
 *
 * <p>Assumptions: the amount is zoned decimal with sign overpunch, not packed
 * decimal, so the sign travels inside the final digit byte rather than in a
 * nibble of its own. {@code TRAN-AMT} is declared {@code PIC S9(09)V99} at
 * {@code app/cpy/CVTRA05Y.cpy} line 10, and the seed data shows the encoding at
 * byte level. In {@code app/data/ASCII/dailytran.txt} the eleven amount bytes
 * of the first record read {@code 0000005047G}, whose trailing {@code G}
 * carries both the digit 7 and a positive sign, giving +504.77; the second
 * record's amount ends in a right-brace character, which carries the digit 0
 * together with a negative sign, giving -919.00. A decoder that reads either
 * final byte as an ordinary digit returns the wrong number and loses the sign
 * entirely, and it does so in the second of three hundred records rather than
 * in a rare case, which is why the codec is shared from the kernel and not
 * re-implemented here.
 *
 * <p>Trade-offs: transporting money as a JSON string costs every client an
 * explicit parse and makes a payload marginally larger. A JSON number was
 * rejected because most clients parse one into a binary double, and a value
 * such as 504.77 has no exact binary representation, so the exactness the
 * fixed-point column and the fixed-point Java type are both maintained would be
 * discarded at the last hop, where nothing downstream could detect the loss.
 *
 * <p><b>Four. An identifier becomes a digits-validated string, never a
 * number.</b> The account identifier, the card number and the transaction
 * identifier are fixed-width character values on both sides of this package.
 *
 * <p>Assumptions: the authority for that is in-program rather than in a
 * copybook, and it is the strongest evidence available.
 * {@code app/cbl/COTRN02C.cbl} lines 204 to 207 compute {@code WS-ACCT-ID-N},
 * declared {@code PIC 9(11)} at line 55, from {@code FUNCTION NUMVAL} of the
 * screen field, then move the numeric result back onto that same {@code X(11)}
 * field; lines 218 to 221 do the same for {@code WS-CARD-NUM-N}, declared
 * {@code PIC 9(16)} at line 56, and its {@code X(16)} field. Moving a numeric
 * item onto a character field of matching width left-pads it with zeros, so the
 * reference program deliberately produces a fully zero-padded identifier. The
 * seed data carries the consequence: 30 of the 300 records in
 * {@code app/data/ASCII/dailytran.txt} hold a card number beginning with a zero
 * at the sixteen bytes starting at zero-based offset 262, the second record's
 * {@code 0***********6232} among them -- masked to its last four digits in the
 * same twelve-asterisk form the response contract publishes, because the leading
 * zero and the sixteen-position width are the whole of the evidence and the
 * interior digits would make it a usable card number. A numeric column or a
 * {@code Long}
 * component would discard that leading zero and turn a sixteen-character
 * identifier into a fifteen-digit number, which no longer matches the value
 * stored, printed or indexed.
 *
 * <p>Assumptions: the X-over-9 {@code REDEFINES} pairing in
 * {@code app/cpy/CVCRD01Y.cpy} -- {@code CC-ACCT-ID PIC X(11)} at line 34
 * redefined {@code PIC 9(11)} at line 36, {@code CC-CARD-NUM PIC X(16)} at line
 * 37 redefined {@code PIC 9(16)} at line 39, and {@code CC-CUST-ID PIC X(09)}
 * at line 40 redefined {@code PIC 9(9)} at line 42 -- corroborates the same
 * treatment: character on the wire, numeric only for arithmetic. It is cited as
 * a house idiom and nothing more, because none of the four programs migrated
 * into this module copies that book. A search for its {@code COPY} statement
 * across the reference programs matches five, and all five belong to the
 * account and card contexts. Presenting it as this module's own contract would
 * mis-attribute the authority for a decision the module's own programs already
 * establish on their own.
 *
 * <h2>The message width the target adopts, and the three it does not</h2>
 *
 * <p>Assumptions: four message widths coexist across the reference material and
 * must not be conflated, because each names a different thing:
 *
 * <ul>
 *   <li>{@code PIC X(80)} -- the working-storage message each of the four
 *       migrated programs declares, at line 38 of {@code COTRN00C},
 *       {@code COTRN01C} and {@code COTRN02C} and at line 39 of
 *       {@code COBIL00C}.</li>
 *   <li>{@code PIC X(78)} -- the screen field it is rendered into, declared
 *       {@code ERRMSGO} in each of the four symbolic maps, so the last two
 *       bytes of an eighty-character message are truncated on the way out.</li>
 *   <li>{@code PIC X(75)} -- {@code CCARD-RETURN-MSG} at
 *       {@code app/cpy/CVCRD01Y.cpy} line 29, the catalog form the target
 *       adopts.</li>
 *   <li>{@code PIC X(50)} -- the shared message constants in
 *       {@code app/cpy/CSMSG01Y.cpy}, at lines 18 and 20.</li>
 * </ul>
 *
 * <p>The baseline carries an eighty-character working-storage message rendered
 * into a seventy-eight-character screen field. The Java adopts the
 * seventy-five-character return-message form, whose {@code LOW-VALUES}
 * condition at line 30 of that book maps to an absent message rather than to a
 * blank one. The divergence is documented rather than presented as equivalence.
 *
 * <p>Assumptions: that condition attaches to the return message at line 29
 * only. {@code CCARD-ERROR-MSG} at line 28 of the same book carries no such
 * condition, so an error message has no sentinel value and a blank error
 * message is a blank error message. Mapping {@code LOW-VALUES} to a blank
 * string rather than to absence would make an unset return message
 * indistinguishable from a deliberately empty one, and a client cannot then
 * tell "no message" from "a message consisting of spaces" -- which is exactly
 * the distinction the condition name exists to draw.
 *
 * <h2>Three 26-character timestamp forms, kept distinct</h2>
 *
 * <p>Assumptions: three different values reach a 26-character timestamp field
 * in the reference material, and normalising them to one another is a silent
 * data error rather than a tidy-up. All three are named because each arrives on
 * a path that runs through or beside this package:
 *
 * <ul>
 *   <li><b>Date only, padded.</b> {@code app/cbl/COTRN02C.cbl} lines 464 and
 *       465 move two {@code PIC X(10)} screen fields -- {@code TORIGDTI} at
 *       line 102 and {@code TPROCDTI} at line 108 of
 *       {@code app/cpy-bms/COTRN02.CPY} -- into the two {@code PIC X(26)}
 *       record fields. A ten-byte value moved into a twenty-six-byte character
 *       field is followed by sixteen spaces, so a transaction captured through
 *       that screen carries a date and no time at all, and a
 *       microsecond-precision target column necessarily renders midnight for
 *       it.</li>
 *   <li><b>Zero microseconds.</b> {@code app/cbl/COBIL00C.cbl} line 266 moves
 *       zeros into the microsecond component, and lines 231 and 232 then move
 *       that one assembled value into both the originating and the processing
 *       timestamp, so a bill payment's two timestamps are identical by
 *       construction and its sub-second component is always zero.</li>
 *   <li><b>Database format.</b> {@code app/cbl/CBTRN02C.cbl} lines 437 and 438
 *       obtain a database-format timestamp and move it into the processing
 *       timestamp, which is the form a posted transaction carries.</li>
 * </ul>
 *
 * <p>Assumptions: the 26-byte group is {@code app/cpy/CSDAT01Y.cpy} lines 42 to
 * 55, and its declared widths sum to exactly 26: four for the year, then two
 * each for month, day, hour, minute and second, six for the microseconds, and
 * one each for the six separators. Its separators are present only because
 * {@code INITIALIZE} leaves {@code FILLER} items untouched, so the value
 * clauses at line 48 -- a space, at byte 11 -- and at line 54 -- a period, at
 * byte 20 -- survive an initialization that clears every named component around
 * them. A formatter that emits the digits and assumes the punctuation will
 * follow produces a 26-byte value with the separator positions blank, which
 * parses as neither a date nor a timestamp. Formatting therefore goes through
 * {@code TimestampFormatter} from {@code com.carddemo.common.time}, which emits
 * the pattern in full.
 *
 * <p>Assumptions: a blank timestamp is absent, not the epoch, and the seed data
 * settles which fields are blank. Every one of the 300 records in
 * {@code app/data/ASCII/dailytran.txt} carries twenty-six spaces at the
 * processing-timestamp field, the twenty-six bytes starting at zero-based
 * offset 304, because a daily transaction has not been processed yet when it is
 * staged; the originating timestamp at offset 278 is populated, reading
 * {@code 2022-06-10 19:27:53.000000} in the first record. Decoding twenty-six
 * spaces to a zero instant would date every unprocessed transaction to 1970 and
 * make an unprocessed record indistinguishable from one processed at an absurd
 * time, so blank decodes to no value.
 *
 * <h2>Shared kernel: one import path, as the baseline has one include path</h2>
 *
 * <p>Transformation rule T2 governs imports here: one former {@code COPY}
 * statement becomes exactly one type import, always from the package that owns
 * that contract. Everything shared is consumed from {@code com.carddemo.common}
 * and is never re-declared in this module -- the record codecs for fixed-width,
 * zoned-decimal and packed-decimal material and the layout descriptor that
 * drives them, the money type, the problem shape, the timestamp form, the field
 * validation flag and the keyset page envelope.
 *
 * <p>Refactoring Rationale: re-declaring one of those locally is precisely the
 * failure this discipline exists to prevent, and it is the failure a mapper
 * invites, because a mapper is where a codec is needed and a local copy is
 * always the shorter path. The baseline compiles every program against a single
 * copybook include path, so a layout has one definition and cannot drift
 * between two programs; the repository imposes the same discipline on its own
 * COBOL tests at {@code tests/README.md} lines 540 to 542, which resolve record
 * layouts through {@code cobc -I app/cpy} and never duplicate one. The shared
 * kernel is the Java form of that one include path. A second local copy of a
 * sign-overpunch decoder would reintroduce exactly the drift the include path
 * forecloses, and the drift would be silent, because both copies would go on
 * compiling and only one of them would be revised when the encoding is
 * revisited.
 *
 * <p>Assumptions: this package imports no other service's {@code domain}
 * package, and the prohibition belongs to a build rule rather than to a
 * convention. The shared kernel's layering rules are evaluated against this
 * module's own compiled classes and additionally keep web and cloud-provider
 * types out of {@code domain} and binary floating-point types out of the money
 * path. Cross-context data reaches a mapper as an argument its caller obtained
 * over HTTP, which is why the balance in {@code BillPaymentResponse} arrives as
 * a scalar and not as an imported entity.
 */
package com.carddemo.transaction.mapper;
