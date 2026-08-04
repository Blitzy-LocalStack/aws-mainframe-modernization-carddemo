/**
 * Wire shapes for the four migrated transaction screens: the request and
 * response types forming the boundary between HTTP/JSON and the service layer
 * of the LEDGER bounded context.
 *
 * <h2>Target contract, and the tree state that authored it</h2>
 *
 * <p>Assumptions: every type name and every inventory figure in this charter
 * states the package's target contract as the migration plan assigns it, not
 * the set of files sitting beside this one at the checkpoint that authored it.
 * At that checkpoint this directory holds this charter and nothing else. A type
 * named below that has no file yet is therefore planned rather than missing,
 * and a figure below is a target total rather than a measurement of the
 * directory.
 *
 * <p>Alternatives Considered: withholding this charter until the seven records
 * it governs exist. Rejected, because the charter is what their authors work
 * from -- which shape belongs here, which may not, and where each declared
 * width comes from -- so a package holding records but no stated contract is
 * exactly the state in which a locally declared page envelope or a numeric
 * identifier gets added. The cost accepted is that the inventory reads as
 * present tense unless the distinction is declared, which is what the paragraph
 * above is for.
 *
 * <p><b>Purpose.</b> This package holds the wire shapes of the four migrated
 * online transaction screens -- list, view, add and bill payment -- as request
 * and response types. It is the boundary between HTTP/JSON and
 * {@code com.carddemo.transaction.service}: a controller validates and binds a
 * request type declared here, a service answers with a response type declared
 * here, and nothing in between carries a representation concern of the
 * reference record format. The reference COBOL is the specification, so these
 * shapes encode the field set, the widths and the scales it already states
 * rather than redefining them.
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A package
 * declaration accepts no parameter, returns no value and raises nothing, so
 * this charter carries no parameter, return or exception at-clause. The
 * inapplicability is declared rather than left silent, because the
 * Explainability rule's line 39 forbids a docstring that omits parameters,
 * return values or purpose, and a reader has to be able to tell a declared
 * inapplicability from an oversight. Of the four docstring elements the rule
 * enumerates at its lines 18 to 21, exactly one -- Purpose, at line 18 -- has a
 * subject in this compilation unit; this paragraph accounts for the other
 * three. Inventing the missing three would be worse than noise: Javadoc has no
 * parameter, return or exception concept for a package, and the repository
 * ruleset audits at-clause bodies for emptiness, so a fabricated tag would be
 * either discarded or reported.
 *
 * <h2>The closed inventory: eight files</h2>
 *
 * <p>Eight {@code .java} files constitute this package and no more. Seven are
 * records; the eighth is this charter. Each record is named with the reference
 * program and symbolic map it derives from, because that provenance is the only
 * authority for its component set:
 *
 * <ul>
 *   <li>{@code package-info.java} -- this charter.</li>
 *   <li>{@code TransactionListRequest} -- the list filter and cursor, from
 *       {@code app/cbl/COTRN00C.cbl} and {@code app/cpy-bms/COTRN00.CPY}. It
 *       carries the optional starting transaction identifier the reference
 *       screen accepts in {@code TRNIDINI PIC X(16)} at line 66 of that map,
 *       one opaque cursor and one paging direction.</li>
 *   <li>{@code TransactionListItemResponse} -- one row of the list, from the
 *       same two files. The reference row is five map fields at lines 72 to 96
 *       of that map, of which the populating paragraph at {@code COTRN00C.cbl}
 *       line 381 writes four: the identifier, a date derived from the
 *       originating timestamp, the description and the amount. The fifth, the
 *       one-character selection marker {@code SEL0001I} at line 72, is an input
 *       affordance of the terminal and is not carried.</li>
 *   <li>{@code TransactionDetailResponse} -- the view response, from
 *       {@code app/cbl/COTRN01C.cbl} lines 178 to 190 and
 *       {@code app/cpy-bms/COTRN01.CPY}. Those thirteen consecutive move
 *       statements are the component set, and their order is the reading order
 *       of the reference screen.</li>
 *   <li>{@code TransactionAddRequest} -- the capture payload, from
 *       {@code app/cbl/COTRN02C.cbl} and {@code app/cpy-bms/COTRN02.CPY} lines
 *       60 to 138.</li>
 *   <li>{@code TransactionAddResponse} -- the capture result, from the same two
 *       files.</li>
 *   <li>{@code BillPaymentRequest} -- the payment payload, from
 *       {@code app/cbl/COBIL00C.cbl} and {@code app/cpy-bms/COBIL00.CPY} lines
 *       60 to 72, being the account identifier at {@code ACTIDINI PIC X(11)}
 *       and the one-character confirmation at {@code CONFIRMI PIC X(1)}.</li>
 *   <li>{@code BillPaymentResponse} -- the payment result, from the same two
 *       files. The reference program reports the balance through
 *       {@code CURBALI PIC X(14)} at line 66 of that map.</li>
 * </ul>
 *
 * <p>Assumptions: two different counts of eight meet in this module and must
 * not be conflated. The root charter of this module records that the module
 * holds eight Java packages and therefore exactly eight package charter files.
 * The eight above is a different quantity entirely: it is the file count of
 * this one package. The two figures are independent, and a reader reconciling
 * one against the other would conclude that seven charters are missing.
 *
 * <p>Trade-offs: the inventory is closed rather than open-ended, so a shape
 * that a fifth screen would need does not belong here even when it would be
 * convenient. What is bought is that the boundary of this bounded context is
 * legible from one list: a type appearing here that is not in that list is
 * either a shape another package owns or a screen this context does not serve.
 * The cost is that extending the context is a deliberate edit to this charter
 * rather than a silent addition beside it, which is intended.
 *
 * <h2>The naming convention this package holds itself to</h2>
 *
 * <p>Every type here ends in {@code Request} or {@code Response}, following the
 * migration plan's own file pattern for this layer. The convention is stated
 * rather than assumed because one member of the inventory invites a departure
 * from it: {@code TransactionListItemResponse} is an element type rather than a
 * whole reply, so a bare noun would read more naturally for it. It keeps the
 * suffix all the same, because the suffix is what makes the closed inventory
 * self-checking -- a file in this directory whose name ends in neither word is
 * visibly outside the list without anyone having to consult the list.
 *
 * <h2>Where every width, type and scale comes from</h2>
 *
 * <p>Assumptions: transformation rule T1 makes the reference copybook
 * normative, so no width here is chosen. {@code app/cpy/CVTRA05Y.cpy} declares
 * {@code 01 TRAN-RECORD} at line 4 and fourteen subordinate fields at lines 5
 * to 18, and every component width, type and scale in this package derives from
 * that declaration. Bean Validation size constraints take the copybook picture
 * widths as their values and nothing else.
 *
 * <p>Assumptions: {@code jakarta.validation} is on both the compile and the
 * runtime classpath of this module. It does not arrive transitively from the
 * shared kernel, which marks the validation starter optional so that a module
 * with no web tier does not inherit one;
 * {@code services/transaction-service/pom.xml} re-declares it for that reason.
 * A component annotated with a size constraint in a module that had not
 * re-declared it would compile and then fail at run time rather than at build
 * time.
 *
 * <p>The arithmetic of that record is self-checking, which is what lets every
 * width in this package be verified rather than trusted. Summing the declared
 * field widths in declaration order -- 16, 2, 4, 10, 100, 11, 9, 50, 50, 10,
 * 16, 26, 26 and 20 -- gives exactly 350, and the copybook's own header comment
 * at line 2 records the record length as 350. A component width that disagrees
 * with that copybook is therefore wrong by construction rather than by opinion.
 *
 * <p>Assumptions: {@code FILLER PIC X(20)} at line 18 of that copybook has no
 * component here, and the drop is recorded rather than left silent. Those bytes
 * pad the record to its declared length and carry no value a caller can supply
 * or read. The drop is also not an invention of this migration: the reference
 * view program moves thirteen record fields to its map at
 * {@code app/cbl/COTRN01C.cbl} lines 178 to 190, and {@code FILLER} is the one
 * record field absent from that sequence, so the reference screen already
 * carries the record without it.
 *
 * <p>Assumptions: the reference transaction record contains no {@code COMP}
 * item and no {@code OCCURS} clause. Its numerics are zoned decimal throughout,
 * so the packed-decimal handling the shared kernel also publishes has no
 * subject in this package, and no component here is a repeating group.
 *
 * <h2>Four divergences from the reference presentation, each registered</h2>
 *
 * <p>The framing below is the only one used: the baseline does one thing, the
 * Java implements another, and the divergence is documented in the migration
 * traceability register rather than introduced silently. The repository states
 * the same discipline for its own suite at {@code tests/README.md} lines 555
 * and 556, which encode the specification rather than redefine it. Nothing
 * under {@code app/} is edited by this package or by any statement in this
 * charter.
 *
 * <p><b>One. Money is exact fixed point and travels as a JSON string.</b> Every
 * monetary component here is typed {@code com.carddemo.common.money.Money},
 * never {@code java.math.BigDecimal} and never an IEEE-754 binary floating
 * point type.
 *
 * <p>Assumptions: the type matters and not merely the value, because
 * {@code MoneyModule} binds its serialiser to the {@code Money} type. A
 * component declared as a bare arbitrary-precision decimal is therefore
 * serialised as a plain JSON number: it compiles, it runs, its arithmetic is
 * exact, and its wire form is wrong. That is the failure mode this paragraph
 * exists to name, because no test of the value would localise it.
 *
 * <p>Alternatives Considered: a JSON number was evaluated and rejected. Most
 * clients parse one into an IEEE-754 binary floating point value on receipt,
 * and binary64 cannot exactly represent the two-place decimal fractions these
 * fields are built from. The margin leaves no room for the approximation:
 * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy} line 10 is
 * eleven significant decimal digits, and the balance a bill payment reports,
 * {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} line 7,
 * is twelve. Exactness that the database, the domain and the arithmetic all
 * preserve would be surrendered at the one hop a user actually reads.
 *
 * <p>Assumptions: that module is registered by this module's application entry
 * point, which imports it explicitly because component scanning rooted at
 * {@code com.carddemo.transaction} reaches nothing beneath
 * {@code com.carddemo.common}. No type in this package registers it, annotates
 * around it or restates it, and a component here needs no serialisation
 * annotation of its own.
 *
 * <p><b>Two. Identifiers are digits-validated strings, never numeric types.</b>
 * The account identifier, the card number, the transaction identifier, the
 * merchant identifier and the category code all cross this boundary as strings
 * constrained to digits.
 *
 * <p>Assumptions: the baseline itself justifies this, declaring each identifier
 * twice over the same bytes -- once as characters and once as a number.
 * {@code app/cpy/CVCRD01Y.cpy} gives {@code CC-ACCT-ID PIC X(11)} at line 34
 * with {@code CC-ACCT-ID-N PIC 9(11)} redefining it at line 36,
 * {@code CC-CARD-NUM PIC X(16)} at line 37 with its numeric redefinition at
 * line 39, and {@code CC-CUST-ID PIC X(09)} at line 40 with its numeric
 * redefinition at line 42. The character declaration is what the screen and the
 * message carry; the numeric one exists so arithmetic can reach the same bytes.
 *
 * <p>Assumptions: the reference seed data settles it beyond the declaration.
 * {@code app/data/ASCII/dailytran.txt} holds 300 records, of which 30 carry a
 * card number with a leading zero -- the second record's is
 * {@code 0927987108636232} -- and a numeric component would discard that zero
 * on the way out while still comparing equal on the way in. The symbolic maps
 * agree: {@code app/cpy-bms/COTRN01.CPY} declares {@code TCATCDI PIC X(4)} at
 * line 84 and {@code MIDI PIC X(9)} at line 120 where the record declares the
 * same two fields {@code PIC 9(04)} and {@code PIC 9(09)}, so even the
 * reference presentation treats them as characters.
 *
 * <p><b>Three. Amounts carry the record's nine integer digits, not the screen's
 * eight.</b> {@code TRAN-AMT} is {@code PIC S9(09)V99}, and both reference
 * screens narrow it to eight integer digits for display:
 * {@code app/cbl/COTRN01C.cbl} declares {@code WS-TRAN-AMT PIC +99999999.99} at
 * line 49 and routes the amount through it at line 177 before writing the map
 * field at line 183, while {@code app/cbl/COTRN02C.cbl} validates only eight
 * digits at line 341, parses into the nine-digit {@code WS-TRAN-AMT-N} at lines
 * 383 to 386, edits back down through the eight-digit {@code WS-TRAN-AMT-E} and
 * rewrites the screen field from it. The components here carry all nine.
 *
 * <p>Assumptions: the widths differ between screens on purpose, so neither is a
 * general rule. {@code app/cbl/COBIL00C.cbl} declares
 * {@code WS-CURR-BAL PIC +9999999999.99} at line 56 -- ten integer digits,
 * matching the account balance rather than the transaction amount -- and its
 * map field {@code CURBALI PIC X(14)} at line 66 of
 * {@code app/cpy-bms/COBIL00.CPY} is exactly a sign, ten digits, a decimal
 * point and two more. Taking the transaction amount's eight-digit edit as the
 * width for a balance would truncate two digits of a value the reference record
 * holds.
 *
 * <p><b>Four. Record widths are carried in preference to display widths.</b>
 * Six fields are narrowed by the reference presentation and are carried here at
 * record width, each narrowing recorded so that a reader meeting the wider
 * value is not surprised by it: the description at {@code PIC X(100)} appears
 * as {@code TDESCI PIC X(60)} on the detail screen and as
 * {@code TDESC01I PIC X(26)} in a list row; the amount's nine integer digits
 * appear as eight; the merchant name at {@code PIC X(50)} appears as
 * {@code MNAMEI PIC X(30)}; the merchant city at {@code PIC X(50)} appears as
 * {@code MCITYI PIC X(25)}; and each of the two twenty-six-character timestamps
 * appears as a ten-character date, at {@code TORIGDTI} and {@code TPROCDTI}.
 *
 * <p>Assumptions: the list row narrows further still, and the extra step is
 * worth naming because it is a reformat rather than a truncation.
 * {@code app/cbl/COTRN00C.cbl} lines 384 to 388 take the twenty-six-character
 * originating timestamp, extract the last two digits of the year, the month and
 * the day, and assemble an eight-character date held in
 * {@code WS-TRAN-DATE PIC X(08)} at line 57 whose initial value shows its
 * shape. A component reproducing that eight-character form would carry a
 * two-digit year and would not be reconcilable with the record.
 *
 * <p>Trade-offs: carrying record widths means a response can hold a value the
 * reference terminal could not render -- a hundred-character description, or a
 * nine-digit amount. The compromise accepted is that the wire shape is broader
 * than the terminal's, so a client rendering into a fixed-width column has to
 * decide for itself what to do with the surplus. The alternative, truncating
 * each component to its display width, was rejected because it would discard
 * data the record demonstrably holds and would make the response depend on
 * which screen happened to ask for it.
 *
 * <h2>A rejected submission answers with a per-field error array</h2>
 *
 * <p>Transformation rule T7 turns the reference validation flags into a
 * structured per-field error array, carried by
 * {@code com.carddemo.common.error.ApiError} with its per-field entries typed
 * by {@code com.carddemo.common.validation.FieldValidationFlag}. Three
 * properties of the reference mechanism are published here because each one is
 * a decision a reader cannot recover from the code.
 *
 * <p>Assumptions: the blank state is a subset of the error state rather than a
 * third alternative to it. {@code app/cpy/CSSETATY.cpy} lines 18 and 19 form a
 * single disjunctive test over the not-ok flag and the blank flag, so the
 * highlight fires for either. A caller therefore asks the flag whether the
 * field is in error and treats blank as a refinement of that answer, rather
 * than comparing the flag to the blank state as though the two were exclusive.
 *
 * <p>Assumptions: the literal asterisk marker the reference writes into a blank
 * field is presentational, and the copybook proves it by writing the two
 * effects into two different places. Line 21 moves the red attribute into the
 * field's colour subfield at line 22; lines 24 and 25 move the asterisk into
 * the field's output subfield. The symbolic maps confirm those are separate
 * declarations: {@code app/cpy-bms/COBIL00.CPY} declares
 * {@code ERRMSGC PICTURE X} at line 136 and {@code ERRMSGO PIC X(78)} at line
 * 140. The marker is preserved for the blank case as a property of the error
 * entry, not as a character prepended to a value component.
 *
 * <p>Assumptions: the per-field array is keyed by field identifier, and all
 * four reference programs are why. Each one signals which field failed by
 * moving minus one into that field's length subfield, which is how the terminal
 * places the cursor on the offending field -- for instance
 * {@code app/cbl/COTRN02C.cbl} at lines 347, 362, 377, 404, 424 and 434,
 * {@code app/cbl/COBIL00C.cbl} at lines 203 and 239, and
 * {@code app/cbl/COTRN00C.cbl} at lines 201 and 216, among many such sites in
 * each program. The key is what carries that behaviour across; the length
 * subfield itself is a terminal mechanism and is not a component of any type
 * here.
 *
 * <h2>Message width, and the one asymmetry that shapes a response</h2>
 *
 * <p>Assumptions: the catalog width this package answers in is 75, from
 * {@code app/cpy/CVCRD01Y.cpy}, which declares
 * {@code CCARD-ERROR-MSG PIC X(75)} at line 28 and
 * {@code CCARD-RETURN-MSG PIC X(75)} at line 29. The two are the same width and
 * are not interchangeable, because only one of them carries a sentinel: line 30
 * attaches a message-off condition valued at low values to the return message
 * alone, and the error message has none.
 *
 * <p>That asymmetry is derived from the copybook rather than chosen, and it
 * settles two component decisions. A response type here carries at most one
 * confirmation or return message component, and it is nullable, because the
 * sentinel state maps to an absent message. A response type here carries no
 * error-message component at all, because the error path belongs to
 * {@code ApiError} and its handler in the shared kernel, which is where a
 * message accompanying a rejected submission is emitted.
 *
 * <p>Assumptions: the sentinel is low values and not spaces, and substituting
 * one for the other changes observable behaviour. An absent message and a
 * seventy-five-character blank message are different states, and a client
 * rendering nothing for one and an empty band for the other would diverge on
 * exactly the distinction the reference copybook draws.
 *
 * <p>Assumptions: three further widths exist in the reference material and none
 * of them governs a component here. 50 is the common-message form at
 * {@code app/cpy/CSMSG01Y.cpy}, where the two literals at lines 19 and 21 each
 * measure 49 characters against a declared {@code PIC X(50)} -- 43 visible
 * characters followed by six spaces, and 40 followed by nine -- so the compiler
 * pads one further space at load and the literal and the declared width are two
 * separate constraints. 72 is the abend message at {@code app/cpy/CSMSG02Y.cpy}
 * line 28, one member of a four-member group running from line 21 to line 29 of
 * a file that is 35 lines long, so a citation beyond that is out of range; the
 * shared kernel carries that group and this package does not. 78 is the width
 * of the screen error field itself, at {@code app/cpy-bms/COBIL00.CPY} lines 78
 * and 140, {@code app/cpy-bms/COTRN00.CPY} lines 372 and 728, and both
 * {@code app/cpy-bms/COTRN01.CPY} and {@code app/cpy-bms/COTRN02.CPY} at lines
 * 144 and 272. Those file names are upper case on disk, extension included, so
 * a lower-case path does not resolve.
 *
 * <p>Assumptions: 80 is not a width of this interface, and the near miss is
 * worth naming because it appears in all four reference programs.
 * {@code app/cbl/COTRN00C.cbl}, {@code app/cbl/COTRN01C.cbl} and
 * {@code app/cbl/COTRN02C.cbl} each declare {@code WS-MESSAGE PIC X(80)} at
 * their line 38, which is internal work storage: {@code COTRN01C.cbl} line 217
 * moves it into the seventy-eight-character screen field, truncating two bytes
 * on the way out, so 80 never reaches an interface at all.
 *
 * <h2>What this package deliberately does not declare</h2>
 *
 * <p>This list is as much a part of the charter as the inventory is, because an
 * absence in a package is invisible and an author who cannot see why something
 * is missing supplies it.
 *
 * <p><b>No page envelope.</b> The list answers in
 * {@code com.carddemo.common.web.PageResponse}, whose shape is
 * {@code PageResponse<T>(List<T> items, String firstKey, String lastKey, boolean hasNext)}
 * -- four components and one type parameter, both cursors opaque and either
 * absent. {@code TransactionListItemResponse} is only the element type that
 * envelope carries, and the envelope is never redeclared here.
 *
 * <p>Alternatives Considered: an envelope local to this package was evaluated
 * and rejected. It would duplicate a contract the shared kernel already owns,
 * and transformation rule T2 exists to prevent exactly that, requiring one
 * former copybook inclusion to become one type import from the one package that
 * owns the contract. The repository states the same discipline for the
 * reference layouts at {@code tests/README.md} lines 540 to 542, which resolve
 * every record layout through a single compiler include path and never
 * duplicate one; the shared kernel is the Java analogue of that include path.
 * What would be wrong with a local copy is concrete rather than stylistic: two
 * envelopes disagreeing about whether a further page exists is a defect no test
 * in either service would localise, and the disagreement would surface as
 * inconsistent paging between screens rather than as a build failure.
 *
 * <p><b>No ordinal paging component of any kind</b>, so no page number, no
 * counted starting position and no page index appears on any type here, nor in
 * any prose above implying one exists. Paging is expressed by that envelope and
 * an opaque cursor, and by nothing else.
 *
 * <p>Alternatives Considered: pagination by ordinal position -- a count of rows
 * skipped from the start of the result set -- was evaluated and rejected. It
 * silently skips and repeats rows when rows are inserted between two reads,
 * because the count is measured against a result set that has changed, whereas
 * a cursor keyed on the ordering column is unaffected. That is a change in
 * observable behaviour rather than in implementation. The reference material
 * shows the concurrent insert is real and not hypothetical:
 * {@code app/cbl/COBIL00C.cbl} lines 212 to 217 mint the next transaction
 * identifier by reading the highest existing key and adding one, holding no
 * lock across the sequence, so two payments running together can read the same
 * maximum and land in the same region of the key space.
 *
 * <p>Assumptions: two cursors are carried rather than one because the reference
 * screen needs both. {@code app/cbl/COTRN00C.cbl} routes the backward key at
 * lines 125 to 128 to a paragraph that reads the first key of the page shown,
 * at lines 236 and 239, and the forward key to a paragraph that reads the last
 * key, at lines 259 and 262. A request therefore carries one cursor together
 * with the direction it is to be read in, which is what makes a single cursor
 * component sufficient on the request while both are required on the response.
 *
 * <p>Assumptions: the last-key cursor is the key of the last returned row and
 * not of the row that proved a further page exists. {@code COTRN00C.cbl} line
 * 297 fills exactly ten rows, and the eleventh read at line 308 is not followed
 * by the populating paragraph, so the reference discards that row and uses it
 * only to set the more-to-come flag at lines 310 and 312. A cursor taken from
 * the discarded row would skip it on the following page.
 *
 * <p><b>No error type, no validation flag and no exception handler.</b> All
 * three belong to the shared kernel, as named above. A local copy of any of
 * them would reintroduce the drift transformation rule T2 forecloses, and it
 * would drift silently, because both copies would go on compiling.
 *
 * <p><b>No session state, and no re-entry marker of any kind.</b> No type here
 * carries a resubmission flag, a first-entry flag, a turn counter or any
 * equivalent of the reference re-entry discriminator.
 *
 * <p>Refactoring Rationale: the mechanism being replaced is the
 * pseudo-conversational communication area at {@code app/cpy/COCOM01Y.cpy}
 * lines 19 to 44, and what was wrong with it is a security property rather than
 * a style. That structure is storage the client hands back on the following
 * turn, so a client could in principle assert its own user type; identity now
 * arrives as a signed claim, which a client cannot assert, and selection
 * context arrives in the request path, which makes each request independently
 * authorizable. The re-entry discriminator at line 29 of that copybook, with
 * its two condition names at lines 30 and 31, has no successor at all: a
 * stateless handler answering with a per-field error array has no
 * first-entry-versus-re-entry distinction left to make. That matters concretely
 * here, because {@code app/cpy/CSSETATY.cpy} line 20 gates the field highlight
 * on precisely that discriminator, so in this package error presentation is
 * driven by the response body alone and never by a remembered turn count.
 *
 * <p><b>No header chrome.</b> Every one of the four symbolic maps carries the
 * same six framing fields, verified at {@code app/cpy-bms/COBIL00.CPY} lines
 * 24, 30, 36, 42, 48 and 54 and identically positioned in the other three: a
 * four-character transaction name, two forty-character title constants, an
 * eight-character program name, and an eight-character date and time. None
 * appears on any type here. The transaction and program names are identities of
 * the reference transaction monitor with no target analogue; the two titles
 * belong to the user interface screen header; and the date and time are clock
 * reads rendered by the client.
 *
 * <p><b>No clock read.</b> The two twenty-six-character timestamps are
 * formatted by {@code com.carddemo.common.time.TimestampFormatter} to exactly
 * the twenty-six-character form whose date and time are separated by a space
 * rather than by a letter.
 *
 * <p>Assumptions: that formatter exposes no argument-free formatting entry
 * point and no accessor that reads an ambient clock. Its two current-instant
 * entry points each require a clock the caller supplies, so an instant always
 * arrives from outside. No type here reads a clock, and no statement in this
 * charter should be read as implying one could.
 *
 * <p><b>No Lombok and no MapStruct.</b>
 *
 * <p>Alternatives Considered: Lombok's generated accessors cannot carry the
 * Javadoc the Explainability rule requires at its line 15, and the ruleset
 * grants no annotation-based exemption that would excuse a generated member, so
 * a Lombok-built type either fails the gate or has to be suppressed out of it;
 * a Java 21 {@code record} with an explicit constructor gives the same brevity
 * with members that can be documented. MapStruct is rejected on a separate
 * ground: copybook-to-transfer-object mapping is not mechanical. It drops
 * {@code FILLER}, masks a primary account number to its last four digits,
 * suppresses a card verification value entirely, encrypts protected identifiers
 * and renames misspelled baseline fields, and each of those needs a
 * justification at the mapping site that a generated mapper has nowhere to
 * hold.
 *
 * <p><b>No module descriptor, no package HTML file, and no authorship, revision
 * or version at-clause.</b> This build is classpath-based and no module
 * descriptor exists anywhere in the repository; {@code package-info.java} is
 * the canonical carrier of package documentation and the one the ruleset
 * audits; and the ruleset omits the whole family of checks that would ask for
 * those three tags, which version control answers more reliably than a comment
 * maintained by hand.
 *
 * <h2>The layering contract this package sits inside</h2>
 *
 * <p>A type here is a wire shape and imports no persistence entity from
 * {@code com.carddemo.transaction.domain}. The conversion between the two
 * belongs to {@code com.carddemo.transaction.mapper}, which is the only package
 * permitted to hold a representation concern of the reference record format --
 * the {@code FILLER} drop, account-number masking, suppression of a card
 * verification value, encryption of a protected identifier, and the baseline's
 * field spellings. Shared shapes are consumed from {@code com.carddemo.common}
 * and are never re-declared here.
 *
 * <p>Assumptions: layering has exactly one owner, and it is a test rather than
 * a convention. Cross-service imports of another service's domain package are
 * forbidden, and the prohibition is asserted by the ArchUnit layering rules
 * this module runs against its own classes, which additionally keep binary
 * floating-point types out of the money path. Checkstyle's import-control
 * module is deliberately absent from the ruleset for that reason: configuring
 * it would stand up a second engine enforcing an overlapping half of one
 * constraint, and a reader could then no longer tell which of the two owned a
 * given boundary.
 *
 * <p>Assumptions: that rule class is authored at another index of the same
 * migration plan, so at the checkpoint that authored this charter no engine
 * enforces layering and the boundary is carried by review. This module declares
 * the ArchUnit engine at test scope in its own build manifest rather than
 * inheriting it, because the shared kernel binds no test-jar goal and so
 * publishes no test classes for another module to depend on.
 *
 * <h2>The documentation contract this package is held to</h2>
 *
 * <p>One user-specified rule governs this migration: Explainability. Its line
 * 15 requires a docstring on every function, class and module entry point, and
 * it attaches no visibility qualifier to that requirement, so no visibility
 * narrows it. A package declaration is the language's module entry point, and
 * {@code package-info.java} is the only compilation unit able to carry
 * package-level Javadoc, which is why this file exists at all -- the
 * migration's own file inventory would not have required it. Its line 22 names
 * Javadoc as the format for Java.
 *
 * <p>Every non-obvious decision above carries one of exactly four labels,
 * worded as the rule words them at its lines 31 to 34:
 * {@code Alternatives Considered:}, {@code Refactoring Rationale:},
 * {@code Assumptions:} and {@code Trade-offs:}. The plural, unparenthesised,
 * colon-terminated and unemphasised form is the only accepted spelling. The
 * emphasis markup that surrounds those four categories in the rule text and in
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} is the typography of those media
 * and is not part of a label; Java source carries the bare label.
 *
 * <p>Assumptions: the label is read by a literal search before it is read by a
 * person, and that is why one spelling is mandatory rather than preferred. A
 * reviewer auditing this tree against the rule's validation gate has to find
 * every rationale across several languages, and no linter parses prose in a
 * container definition, an infrastructure file or a schema migration, so a
 * fixed-string search is the only mechanism that reaches all of them. A
 * rationale a search cannot find is a rationale a review cannot count. The
 * words also appear in these paragraphs as ordinary English, which is not a
 * label: a label is the token followed immediately by its colon, opening the
 * rationale it introduces.
 *
 * <p>The rule's validation gate at its line 43 is conjunctive. A complete
 * docstring and a labelled rationale explaining why are each independently
 * fatal when absent, because the gate closes by failing code missing either
 * one. This charter is therefore not compliant merely by being thorough: the
 * labelled paragraphs are the second limb, and the gate is cited from line 43
 * rather than from line 29, whose weaker wording states the same obligation as
 * a recommendation.
 *
 * <p>Assumptions: there is no conflict to resolve between that rule and the
 * conventions this repository already follows, and the agreement is stated
 * rather than left implied. The rule's four categories at lines 31 to 34 are
 * the same four the house convention names at {@code tests/README.md} lines 544
 * to 549, in the same singular and plural pattern, and that convention calls
 * itself a hard review gate at line 549. This migration extends an established
 * house convention to a polyglot tree rather than introducing a competing one.
 *
 * <p>Assumptions: the rule reaches only newly authored code. The COBOL baseline
 * under {@code app/} is reference material, cited by path and line and never
 * edited, so no retro-documentation of it is required -- or permitted. The
 * repository's own suite under {@code tests/} already satisfies the equivalent
 * house convention and is likewise untouched.
 *
 * <h2>The mechanical gate, and why a green build is not the whole rule</h2>
 *
 * <p>Assumptions: two checks in the ruleset require this file and neither is
 * redundant. {@code JavadocPackage} asserts that a charter exists in a
 * directory whose Java sources the audit processed;
 * {@code MissingJavadocPackage} asserts that the charter carries Javadoc. A
 * file reduced to a bare package statement satisfies the first and fails the
 * second, which is why this one is prose. The gate is bound to the build's
 * validate phase ahead of compilation, so it runs on a developer's own machine
 * and not only in a pipeline.
 *
 * <p>Assumptions: no escape written into this source can relax that. The
 * ruleset enables the file-based suppression filter alone and none of the three
 * comment-driven or annotation-driven filters, so neither a marker comment nor
 * an annotation suppresses anything. The filter is configured to fail when its
 * companion file is absent, and that companion's charter reaches generated
 * sources and test fixture material only; nothing beneath a service's main
 * source tree may be suppressed, and a mapper package is named there among the
 * entries it forbids precisely because a mapper reads as generated and is not.
 *
 * <p>Assumptions: a green run of that gate is necessary and not sufficient, and
 * the shortfall is specific rather than a general caution. The ruleset records
 * in its own header that it mechanises the docstring half of the rule and
 * cannot mechanise the inline-comment half, because judging a comment means
 * reading what it means. In particular it cannot decide whether a comment
 * explains why rather than what, cannot detect a comment that merely restates
 * the code beside it, cannot identify which choices were non-obvious, and --
 * the item that bears directly on the label canon above -- cannot verify that
 * one of the four categories was actually documented, nor that a category which
 * is named uses the canonical form, since a singular, parenthesised or
 * emphasis-wrapped label passes every module in the file. The canonical form is
 * consequently a review obligation that no build failure will remind anyone of.
 *
 * <p>Assumptions: the one summary check that does grip rationale text is narrow
 * and worth knowing when a build fails on it. {@code SummaryJavadoc} rejects a
 * summary containing a placeholder marker or either of the two vague rationales
 * the rule names as its own examples of what fails, and it rejects a summary
 * opening as a getter or setter description. Its sentence terminator is left at
 * the default, where a period not followed by whitespace does not end a
 * sentence, so a summary here ends with a period followed by whitespace or a
 * line break. {@code CommentsIndentation} governs the placement of any comment,
 * and the ruleset configures no naming, whitespace, import-order, line-length,
 * metrics or header check at all.
 *
 * <h2>Authoring notes for this file</h2>
 *
 * <p>Trade-offs: this file holds one Javadoc block and one package declaration
 * and nothing else -- no type, no annotation, no import and no line comment. A
 * package declaration needs no import, and anything that would require one
 * belongs in one of the seven records instead. The inline-comment obligation is
 * discharged inside the block, by labelled paragraphs opening the rationale
 * they introduce, because a compilation unit holding one declaration and no
 * statements has nothing to annotate adjacently.
 *
 * <p>Trade-offs: this file is pure ASCII with no byte-order mark, and the
 * restriction is load-bearing rather than cosmetic. The house convention this
 * charter cites is written at {@code tests/README.md} lines 542 and 548 with a
 * non-breaking hyphen where an ordinary one is expected. That character is
 * indistinguishable from an ordinary hyphen on screen yet behaves differently
 * in a search, so copying the fourth label from there would turn it into a
 * token a search for the label fails to find. This charter cites both of those
 * lines, and everything drawn from them is retyped rather than pasted, using
 * the ordinary hyphen-minus the rule itself uses; restricting the whole file to
 * ASCII makes that failure mode unreachable. The build declares one character
 * set for the source encoding and for the gate's own reading of it, and ASCII
 * is a strict subset of it, so nothing is lost mechanically.
 *
 * <p>Trade-offs: prose is wrapped at 80 columns to match the sibling charters
 * and build manifests in this tree, even though the ruleset enables no
 * line-length check and so does not require it. Exactly one line exceeds that
 * width and should be left as it is: the page envelope's declared shape, which
 * is quoted and cannot be broken, because a line break inside an inline code
 * span would insert the comment margin into the middle of the quotation and the
 * shape would then read as something the shared kernel does not declare.
 */
package com.carddemo.transaction.dto;
