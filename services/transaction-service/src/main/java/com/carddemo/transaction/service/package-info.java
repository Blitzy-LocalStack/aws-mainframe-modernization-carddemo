/**
 * Holds the business rules of the LEDGER bounded context, transcribed paragraph by paragraph from
 * the four migrated online COBOL programs.
 *
 * <p><b>Purpose.</b> This package is the single place a business rule of the transaction and
 * bill-payment screens is permitted to live. It owns validation order, message selection, amount
 * arithmetic, identifier derivation, transaction boundaries and the assembly of the paged list
 * envelope. It owns nothing else. Wire shapes belong to
 * {@code com.carddemo.transaction.dto}; copybook representation concerns, masking and suppression
 * belong to {@code com.carddemo.transaction.mapper}; every query belongs to
 * {@code com.carddemo.transaction.repository}; Spring wiring belongs to
 * {@code com.carddemo.transaction.config}. A rule that has migrated into one of those four
 * packages is filed in the wrong layer, and the layering test named further down fails the build
 * for it rather than leaving the drift to a reviewer's memory.
 *
 * <h2>Four classes, one per migrated program, and no fifth type</h2>
 *
 * <p>Target contract: this package is to hold exactly four annotated service classes. The pairing
 * with the baseline is one to one, and every line count below was counted in the file itself
 * rather than carried over from a summary:
 *
 * <ul>
 *   <li>{@code TransactionViewService} transcribes {@code app/cbl/COTRN01C.cbl}, 330 lines, the
 *       single-record detail screen.</li>
 *   <li>{@code TransactionListService} transcribes {@code app/cbl/COTRN00C.cbl}, 699 lines, the
 *       paged browse screen.</li>
 *   <li>{@code TransactionAddService} transcribes {@code app/cbl/COTRN02C.cbl}, 783 lines, the
 *       capture screen.</li>
 *   <li>{@code BillPaymentService} transcribes {@code app/cbl/COBIL00C.cbl}, 572 lines, the
 *       balance-affecting payment screen.</li>
 * </ul>
 *
 * <p>Alternatives Considered: merging the four into one transaction service, and splitting each
 * into an interface beside an implementation, were both evaluated and both rejected. The migration
 * plan requires that each significant paragraph become a named method so that the traceability
 * matrix can cite paragraph-to-method pairs. A merged class makes such a citation ambiguous the
 * moment two of these programs contain a paragraph of the same name, and they do: a read of the
 * card cross-reference is named {@code READ-CXACAIX-FILE} in both
 * {@code app/cbl/COTRN02C.cbl} at line 576 and {@code app/cbl/COBIL00C.cbl} at line 408. An
 * interface beside an implementation adds a second place a citation could sit while introducing no
 * seam, because nothing in this context has a second implementation to swap in.
 *
 * <p>Trade-offs: the inventory is closed, so a rule that two programs share is transcribed twice
 * rather than lifted into a base class, and no helper or utility type is available to hold it. The
 * repetition is the accepted cost and it is deliberate rather than tolerated: two occurrences are
 * transcriptions of two separate paragraphs whose texts are permitted to diverge, and the message
 * census in this charter shows that in this context they already have. Sharing them would make a
 * single edit silently change two screens.
 *
 * <p>Assumptions: the parent charter at
 * {@code services/transaction-service/src/main/java/com/carddemo/transaction/package-info.java}
 * enumerates three service classes in its subpackage list, omitting the view service. The four
 * named above are this package's closed inventory, and the fourth is not an addition to the
 * migration scope: the parent's own program inventory records
 * {@code app/cbl/COTRN01C.cbl} as the 330-line "Transaction View" detail screen, so its rules
 * need a named home, and giving them one keeps the paragraph-to-method citation for that program
 * unambiguous. The discrepancy is recorded here so that a reader comparing the two enumerations
 * reads a deliberate decision rather than an oversight in either file.
 *
 * <h2>Each significant paragraph becomes one named method</h2>
 *
 * <p>The organising principle of this package is transcription, not redesign. A significant COBOL
 * paragraph becomes one named method, so that
 * {@code docs/architecture/cobol-to-service-traceability.md} can cite a paragraph and a method as
 * a pair and a reviewer can read the two side by side. Verified anchors, offered as the pattern to
 * follow rather than as an exhaustive list: {@code app/cbl/COTRN00C.cbl} declares
 * {@code PROCESS-PAGE-FORWARD} at line 279 and {@code PROCESS-PAGE-BACKWARD} at line 333, and
 * {@code app/cbl/COBIL00C.cbl} declares {@code GET-CURRENT-TIMESTAMP} at line 249.
 *
 * <p>Assumptions: a paragraph's declaration and its call sites are separate citations, and a
 * method's documentation names the declaration. {@code PROCESS-PAGE-FORWARD} is performed from
 * lines 225 and 268 and {@code PROCESS-PAGE-BACKWARD} from line 246, while
 * {@code GET-CURRENT-TIMESTAMP} is performed from line 230. Citing a call site as though it were
 * the paragraph would send the next reader to a branch rather than to the logic.
 *
 * <p>Assumptions: every citation in a class in this package is verified in the file before it is
 * written down. A rationale resting on a line number that does not hold what it claims is worse
 * than one that cites nothing, because it survives review by looking precise. Counting is part of that discipline: the validation
 * paragraph of {@code app/cbl/COTRN02C.cbl} runs from line 235 to line 437, and the span is cited
 * rather than a count of the blocks inside it, because the span was verified and a tally of blocks
 * depends on whether a conditional statement counts as one.
 *
 * <h2>This layer assembles the page envelope</h2>
 *
 * <p>This is the coordination point a class author cannot recover alone, so it is stated here in
 * full. The repository returns ordered rows and nothing else: a forward request selects
 * identifiers strictly greater than the last key of the previous page in ascending order and asks
 * for one row beyond the requested size, and a backward request selects identifiers strictly less
 * than the first key in descending order. It returns no envelope, no availability indicator and no
 * cursor. Assembling {@code com.carddemo.common.web.PageResponse} is therefore work this package
 * does, and it is the only package that does it.
 *
 * <p>Assumptions: the extra row is a probe and is never part of the answer. The last key of a page
 * is taken from the last row actually returned to the caller, never from the probe row, and the
 * existence of the probe row is what sets forward availability. Taking the key from the probe
 * instead advances the cursor one row too far, and the row it names is then skipped on the next
 * request -- a boundary error that no unit test of a single page can see, because each page is
 * internally consistent and only the seam between two pages is wrong.
 *
 * <p>Assumptions: requesting one extra row transcribes the baseline rather than inventing a
 * heuristic. {@code app/cbl/COTRN00C.cbl} fills ten display slots and then performs an eleventh
 * read at line 308, and lines 309 to 313 set its next-page condition from the outcome of that read
 * alone. The eleventh record is never displayed. The Java keeps the same shape so that the
 * availability indicator means what the screen meant.
 *
 * <p>Assumptions: {@code PageResponse} is consumed from the shared kernel and is never re-declared
 * here. Its record contract carries the rows, the first and last row keys, a directional cursor
 * for each direction and an availability indicator for each direction, and its canonical
 * constructor enforces agreement between each cursor and its indicator, refuses a page that
 * carries rows without naming their two ends, and refuses a page that names ends while carrying no
 * rows. A local envelope would have to restate every one of those invariants, and the copy would
 * then be the version that drifts.
 *
 * <p>Assumptions: the cursor components handed to that envelope are sealed tokens, not raw record
 * keys, and its constructor rejects a raw key outright. A service therefore seals a boundary key
 * through {@code com.carddemo.common.web.CursorToken} before building a page, and redeems an
 * incoming cursor through the same type. This is worth stating because the failure is not a
 * compile error: handing the query's own key straight through type-checks perfectly and is refused
 * only when the page is constructed. Trade-offs: sealing costs a call at each boundary and key
 * material that the running service must be given; what it buys is that a primary account number
 * or an account identifier cannot leave in a response body, be held by a client and be replayed on
 * the next request. The key material reaches the service from configuration and is never a
 * committed value.
 *
 * <p>Assumptions: the shared kernel supplies a constructor for the ordinary page that returned
 * rows and separate constructors for the empty page and for the page whose every row was filtered
 * away. A class here selects among them rather than assembling components by hand, because the
 * pairing of a cursor to its direction is exactly the detail that reads correctly while being
 * crossed.
 *
 * <h2>Pagination compares keys and never counts rows</h2>
 *
 * <p>Forward paging compares strictly greater than the last key with an ascending order; backward
 * paging compares strictly less than the first key with a descending order. The comparison is
 * strict in both directions so that the boundary row of the previous page is not served again.
 *
 * <p>Refactoring Rationale: pagination by ordinal position -- an offset paired with a limit -- was
 * available and is rejected, because concurrent insertion changes how many rows precede a given key
 * between one request and the next, so a row can be skipped entirely or served twice. Comparison
 * by key has no such exposure: the boundary is the row itself. The baseline's own sequence shows
 * the concurrency is real rather than theoretical, since {@code app/cbl/COBIL00C.cbl} at lines 212
 * to 217 seeks from high values, reads the preceding row, ends the browse and increments that
 * row's identifier without holding a lock across the sequence. Preserving observable behaviour is
 * the requirement, and ordinal paging would change it.
 *
 * <p>Assumptions: the cursor is one scalar transaction identifier and not a composite.
 * {@code app/cbl/COTRN00C.cbl} declares its first and last browse identifiers as
 * {@code PIC X(16)} at lines 63 and 64, and the state that follows adds a screen ordinal, an
 * availability flag and a selected identifier without adding a second key component. A composite
 * cursor would need a compound comparison the baseline never expresses.
 *
 * <p>Refactoring Rationale: the comparison is stated explicitly in the query rather than inherited
 * from a positioning default. The baseline's browse-start paragraph begins at line 591 of
 * {@code app/cbl/COTRN00C.cbl} and names its dataset, key and key length at lines 593 to 596,
 * while the greater-or-equal option at line 597 is commented out. A reader of the COBOL cannot see
 * which comparison applies; a reader of the query can.
 *
 * <p>No ordinal-position vocabulary appears anywhere in this package. No parameter, field, response
 * component or method name expresses a row's numeric position within a result, a page ordinal, or a
 * count of matching rows, and none is added on request. Assumptions: a count of matching rows is a
 * second query whose answer is already stale when it is read, and the baseline never showed one, so
 * supplying it would invent a number a screen would then have to be trusted with.
 *
 * <h2>Money is exact decimal from column to wire</h2>
 *
 * <p>Every monetary value in this package is a {@code java.math.BigDecimal} at scale 2, reduced
 * with {@code java.math.RoundingMode#HALF_UP}, and it travels through
 * {@code com.carddemo.common.money.Money}. On the wire it is a JSON string, produced by the Jackson
 * module {@code com.carddemo.common.money.MoneyModule} that this module's configuration
 * contributes. No binary approximation type appears in the money path -- neither of the two
 * primitive binary numeric types nor either of their wrapper classes -- and no monetary value is
 * ever emitted as a JSON number. The layering test in the shared kernel asserts that absence, so
 * the prohibition is a failing build rather than a note in this file.
 *
 * <p>Assumptions: a JSON number is the specific hazard, which is why the string form is not a
 * stylistic preference. A great many clients parse a JSON number into a binary approximation of
 * limited precision, and a balance that has survived exact arithmetic all the way to the response
 * is then rounded by the reader rather than by the writer. A string leaves the decision where the
 * contract puts it.
 *
 * <p>Assumptions: the storage form these values come from is zoned decimal with a sign overpunch,
 * not packed decimal. {@code app/cpy/CVTRA05Y.cpy} declares
 * {@code TRAN-AMT PIC S9(09)V99} at line 10 and {@code app/cpy/CVACT01Y.cpy} declares
 * {@code ACCT-CURR-BAL PIC S9(10)V99} at line 7. Decoding either is the codec layer's work, not
 * this package's, and this package receives values already decoded; the declarations are cited
 * because they are the authority for scale and for sign, and both are part of the contract.
 *
 * <p>Assumptions: three display widths appear along one path and they are not interchangeable. The
 * account balance carries ten integer digits, the transaction amount carries nine, and the edited
 * amount field of the detail screen carries eight --
 * {@code app/cbl/COTRN01C.cbl} declares {@code WS-TRAN-AMT PIC +99999999.99} at line 49. A value
 * that fits the account column can therefore exceed what the amount column accepts, and a value
 * that fits the amount column can exceed what the detail screen renders. A width is validated
 * against the field being written, never against the widest width on the path.
 *
 * <h2>The card cross-reference is read over HTTP</h2>
 *
 * <p>Three paragraphs in this context read the card cross-reference:
 * {@code READ-CXACAIX-FILE} at line 576 and {@code READ-CCXREF-FILE} at line 609 of
 * {@code app/cbl/COTRN02C.cbl}, and {@code READ-CXACAIX-FILE} at line 408 of
 * {@code app/cbl/COBIL00C.cbl}, performed there from line 211. In the baseline each is a read of a
 * file the region shares, named in working storage at lines 40 to 42 of
 * {@code app/cbl/COTRN02C.cbl}. In the target that data belongs to a different bounded context:
 * account-service owns {@code account.card_xref} together with the index
 * {@code idx_card_xref_account_id} that takes over from the alternate index the baseline read
 * through.
 *
 * <p>Alternatives Considered: three resolutions were available and two are closed. A Maven
 * dependency on a sibling service is prohibited outright, and importing another service's domain
 * package is prohibited with the layering test failing the build for it. Copying the
 * cross-reference into the ledger schema was the third, and it is rejected because it gives one
 * record two owners and, in time, two texts that disagree. What remains is what transformation rule
 * T5 prescribes where it maps a program-to-program link onto a method call or an HTTP call: a call
 * to account-service, issued from this layer, because this layer is where the paragraph that
 * performed the read now lives.
 *
 * <p>Assumptions: the call goes out through {@code org.springframework.web.client.RestClient}
 * carrying an explicit connect timeout and an explicit read timeout, and the client is built inside
 * the service class that calls it. The sibling configuration charter records that this module
 * deliberately has no client configuration class, on the ground that one shared client would carry
 * a single timeout budget for every caller while a timeout is a property of the call being made.
 * Trade-offs: the value is therefore set at the call site rather than in one place, which is one
 * more thing to keep in step across four classes; what it buys is that a slow cross-context read
 * cannot hold a request thread without bound. Assumptions: that client type ships in the web
 * starter this module already declares, so the ruling adds nothing to the build.
 *
 * <p>Alternatives Considered: a circuit breaker was evaluated and is not added, and no resilience
 * library is introduced to supply one. The hop is inside the private network, behind an internal
 * load balancer, and already bounded by the two timeouts above. A breaker would add a state machine
 * that can open and refuse a call the dependency would in fact have served, which is a new failure
 * mode in exchange for a bound that already exists.
 *
 * <h2>No session state survives a request</h2>
 *
 * <p>The baseline is pseudo-conversational: a CICS task ends at every screen turn, so all
 * continuity between turns lives in one passed structure, {@code CARDDEMO-COMMAREA} at lines 19 to
 * 44 of {@code app/cpy/COCOM01Y.cpy}. That structure does not travel. Its fields reach the target
 * through three separate mechanisms, and one of them reaches nothing at all.
 *
 * <ul>
 *   <li>Identity arrives as validated claims on a signed token. The user type at line 26, with its
 *       administrator and user condition names at lines 27 and 28, becomes group membership that
 *       {@code com.carddemo.common.security.JwtRoleConverter} converts into authorities; its
 *       constants name them verbatim {@code carddemo-admin} and {@code carddemo-user}.
 *       Assumptions: neither name carries a role prefix, so an authorisation predicate names the
 *       authority directly and a role-shaped predicate silently never matches. A class here
 *       references those constants rather than retyping either string.</li>
 *   <li>Selection context arrives in the request path -- the account identifier at line 38, the
 *       card number at line 41, the customer identifier at line 33 -- which makes every request
 *       self-describing and therefore independently authorisable.</li>
 *   <li>Navigation is entirely client-side. The originating and destination program and transaction
 *       fields at lines 21 to 24, and the last map and mapset at lines 43 and 44, have no
 *       server-side counterpart. Nothing in this package decides which screen follows.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the re-entry discriminator is not ported, it disappears.
 * {@code CDEMO-PGM-CONTEXT} at line 29, with its first-entry and re-entry condition names at
 * lines 30 and 31, exists because a CICS task cannot remember whether it has already sent the
 * screen. A
 * handler that answers one request and returns a field-error array has no such question to settle,
 * so the flag has nothing left to discriminate. What is concretely wrong with carrying it forward
 * is that the same flag gated the baseline's field-highlight logic: keeping it would make the
 * presentation of an error depend on a remembered turn count instead of on the response body.
 *
 * <p>No class in this package holds session state, and none holds mutable instance state of any
 * kind; dependencies arrive through the constructor. Assumptions: that is what makes several
 * identical tasks behind one load balancer interchangeable, so any of them can answer a request
 * without a sticky session and without a shared session store.
 *
 * <h2>There is no golden master for this package</h2>
 *
 * <p>All four source programs are online programs that issue CICS commands, and
 * {@code tests/README.md} records at lines 83 to 85 that such programs cannot be run end to end
 * without a CICS runtime, which the runner does not have, so only their extractable
 * field-validation logic is unit-tested. No class in this package may therefore be described as
 * covered by a golden master, and no test written here may be presented as a byte comparison
 * against a reference run. Parity for this package rests on two things and no others: validation
 * logic transcribed faithfully, and the copybook contracts cited throughout this charter.
 *
 * <p>Assumptions: this states what evidence exists, not a lower standard. A test here asserts one
 * transcribed branch, one message string or one boundary against the cited COBOL line, which is a
 * sharper claim per test than a byte comparison makes and a weaker claim in aggregate, because
 * nothing exercises a whole screen. Recording which of the two it is keeps a later reader from
 * inferring coverage that was never available.
 *
 * <p>Assumptions: the reference suite's graded return-code rubric belongs to the batch oracle and
 * not to this module. Its warn tier is a return code the batch programs themselves set -- line 230
 * of {@code app/cbl/CBTRN02C.cbl} moves 4 into the return code when the reject count is above zero
 * -- and it is a batch-service and reference-suite concept. A Maven phase, the documentation gate,
 * the test runner and an assertion each have two outcomes. No build of this module is described as
 * warn-level green, because there is no such state for it to occupy, and importing the vocabulary
 * would make a failure sound survivable.
 *
 * <h2>Message text is verbatim, and its widths are not interchangeable</h2>
 *
 * <p>Transformation rule T8 carries every user-visible string across character for character. A
 * message selected by a class here is reproduced from its originating program or copybook without
 * re-wording, re-casing or re-spacing.
 *
 * <p>Assumptions: the message form for this module is a pair of fields 75 characters wide.
 * {@code app/cpy/CVCRD01Y.cpy} declares {@code CCARD-ERROR-MSG PIC X(75)} at line 28 and
 * {@code CCARD-RETURN-MSG PIC X(75)} at line 29. The low-values sentinel at line 30 is nested under
 * the return message and under that field alone, so the absence it expresses applies to the return
 * message only, and it means null rather than a run of spaces. A blank return message and an absent
 * return message are two states and are represented as two.
 *
 * <p>Assumptions: 80 is not a message regime, and the near miss is named because it looks like one.
 * The work field {@code WS-MESSAGE PIC X(80)} is declared at line 38 of
 * {@code app/cbl/COTRN00C.cbl}, {@code app/cbl/COTRN01C.cbl} and {@code app/cbl/COTRN02C.cbl}, and
 * at line 39 of {@code app/cbl/COBIL00C.cbl}. It is internal: each program moves it into a screen
 * field declared {@code PIC X(78)} -- {@code app/cpy-bms/COTRN00.CPY} line 372,
 * {@code app/cpy-bms/COTRN01.CPY} line 144, {@code app/cpy-bms/COTRN02.CPY} line 144 and
 * {@code app/cpy-bms/COBIL00.CPY} line 78 -- so COBOL drops two bytes on the way out and 80 never
 * reaches an interface. Those symbolic-map file names are upper case on disk, extension included,
 * so a lower-case path does not resolve.
 *
 * <p>Assumptions: two strings differing only in case or in spacing are two constants, and merging
 * them changes what a screen shows. The census for this context is exact. The list program spells
 * its lookup failure with a lower-case noun, {@code 'Unable to lookup transaction...'}, at
 * lines 615, 649 and 683 of {@code app/cbl/COTRN00C.cbl}, while the same failure is spelled with an
 * upper-case noun, {@code 'Unable to lookup Transaction...'}, at line 292 of
 * {@code app/cbl/COTRN01C.cbl}, lines 664 and 693 of {@code app/cbl/COTRN02C.cbl} and lines 463 and
 * 492 of {@code app/cbl/COBIL00C.cbl}. Spacing diverges as well: line 214 of
 * {@code app/cbl/COTRN00C.cbl} carries a space before the ellipsis in
 * {@code 'Tran ID must be Numeric ...'} where line 199 of {@code app/cbl/COTRN02C.cbl} carries none
 * in {@code 'Account ID must be Numeric...'}. Trade-offs: the cost is a catalogue whose neighbouring
 * entries look like duplicates and invite a well-meant consolidation; the cost is accepted because
 * consolidating would silently re-word a screen, and rule T8 does not permit that.
 *
 * <h2>A rejected submission carries one field error</h2>
 *
 * <p>Transformation rule T7 turns the baseline's validation-flag pattern, its not-valid and blank
 * conditions alike, into a per-field error array on
 * {@code com.carddemo.common.error.ApiError} expressed through
 * {@code com.carddemo.common.validation.FieldValidationFlag}, and it preserves the asterisk marker
 * the baseline writes into a field left blank.
 *
 * <p>Alternatives Considered: collecting every failing field and returning them together was
 * evaluated and rejected. The first validation block of {@code app/cbl/COTRN02C.cbl} runs from
 * line 251 to line 320 as a single conditional selection, which takes its first matching branch and
 * leaves the remaining branches unevaluated, and the branch it takes sends the screen straight
 * back. The baseline therefore surfaces exactly one error per submission. Returning all of them
 * would show a user errors the reference never showed, and would also change which error appears
 * first. The array is the shape; one element is the arity.
 *
 * <p>Trade-offs: the array shape is kept even though the arity is one. A scalar would be a second
 * error shape for one context to maintain against a contract the rest of the migration shares, and
 * the client renders an array; the accepted cost is a collection that in this context never holds
 * more than one entry.
 *
 * <h2>Import discipline</h2>
 *
 * <p>Transformation rule T2 turns one copybook inclusion into exactly one type import from the
 * single package that owns that contract. This package imports the entities, the request and
 * response shapes, the mappers and the repositories of its own module, which is why it is authored
 * after all four of them: their public surfaces are its imports.
 *
 * <p>Shared types come only from {@code com.carddemo.common} and are never re-declared here. That
 * set is the money type and its Jackson module, the page envelope and its cursor token, the error
 * contract, the timestamp formatter, the date-edit validator and the validation flag. Assumptions:
 * the house precedent is explicit rather than inferred -- {@code tests/README.md} at lines 540 to
 * 542 records that the reference suite resolves record layouts through one compiler copybook path
 * and never duplicates a layout, keeping it single-sourced. A shared kernel module is that same
 * arrangement expressed as a dependency instead of an include path, and the reason is the same one:
 * a layout that exists twice is a layout that can disagree with itself.
 *
 * <p>No class here imports another service's domain package, and the layering test in the shared
 * kernel fails the build if one does. Assumptions: that prohibition is what keeps the cross-context
 * read above an HTTP call, rather than letting it become, one convenient import at a time, a shared
 * object model with two owners.
 *
 * <h2>Transaction boundaries and conflict reporting</h2>
 *
 * <p>A baseline commit point becomes a declarative transaction boundary on the method transcribing
 * the paragraph that contained it, and a baseline rollback becomes an exception propagating out of
 * that method. Assumptions: propagation is the mechanism, not a caught and logged failure, because
 * the boundary rolls back as the exception leaves and swallowing it would commit a partial change
 * the baseline never committed.
 *
 * <p>Assumptions: a version conflict surfaced as HTTP 409 travels on
 * {@code com.carddemo.common.error.GlobalExceptionHandler}, which this module's configuration makes
 * visible and which already maps a conflict onto that status. No exception handler is written in
 * this package.
 *
 * <p>A primary account number is masked to its last four digits and a card verification value is
 * never returned. Both transformations belong to {@code com.carddemo.transaction.mapper} and are not
 * re-implemented here. Assumptions: masking in two layers is how one of the two comes to be
 * skipped, because each author can see that the other exists and reasonably assumes it ran.
 *
 * <h2>What this package deliberately does not contain</h2>
 *
 * <p>The absences below are decisions, recorded so that a reader looking for one of these things
 * finds a ruling instead of concluding it was forgotten.
 *
 * <ul>
 *   <li>No configuration class, and in particular no client configuration class. The sibling
 *       configuration package is closed at three classes and records the absence of a client
 *       configuration together with its reason; the timeout-carrying client is built here, at the
 *       call site.</li>
 *   <li>No repository and no query of any kind. Every query lives in the repository package.</li>
 *   <li>No request or response record, and no entity. Those are the DTO and domain packages.</li>
 *   <li>No mapper, no masking, no width padding, no sign decoding and no packed-decimal decoding.
 *       Those belong to the mapper package and to the shared codecs.</li>
 *   <li>No controller and no request mapping. Those belong to the API package.</li>
 *   <li>No helper type, no utility type, no abstract base class and no interface beside an
 *       implementation. The inventory is the four classes named at the head of this charter.</li>
 * </ul>
 *
 * <p>Alternatives Considered: a declarative retry annotation on the cross-context read was evaluated
 * and is discouraged here. Switching it on requires an enabling annotation on a configuration class,
 * and this module's configuration package is closed at three classes that this package may not add
 * to. Assumptions: the enabling annotation is the framework's own
 * {@code @EnableResilientMethods}, and it is named here because the annotation a reader is likely to
 * remember from the superseded external retry library has a different name and resolves nowhere in
 * this build. The mandatory and sufficient posture for the one synchronous hop is the explicit
 * connect and read timeout recorded above.
 *
 * <p>Assumptions: the following are out of scope for the migration as a whole and none is introduced
 * here -- an annotation processor that generates members, a generated mapper, a resilience library,
 * a circuit breaker, an application cache tier, a streaming platform and a read replica. A generated
 * member cannot carry a rationale at all, and each of the others adds an operational component the
 * reference system has no counterpart for.
 *
 * <p>There is no saga and no two-phase commit. Assumptions: nothing in this context spans two
 * datastores, so either would introduce intermediate states the reference never produces and the
 * parity argument would have nothing left to stand on.
 *
 * <p>No credential, no endpoint and no connection string is written in this package. Each arrives
 * through the module's configuration from the environment.
 *
 * <p>There is no {@code module-info.java} in this directory or anywhere beneath this module.
 *
 * <h2>What the Explainability convention requires of every class authored here</h2>
 *
 * <p>This charter exists because of the project's Explainability convention, recorded for readers in
 * {@code CONTRIBUTING.md} and {@code docs/CODE_DOCUMENTATION_STANDARD.md}. It attaches a docstring
 * requirement to every function, every class and every module entry point; in Java the module entry
 * point is the package declaration, and this file is the only place documentation for it can be
 * written. There is no alternative location. The convention fixes the form as the language's
 * standard documentation format, which is the block immediately above that declaration.
 *
 * <p>Every type, constructor and method authored in this package carries a Javadoc block stating its
 * purpose, a parameter clause for each parameter it accepts, a return clause when it yields a value,
 * and an exception clause for what it can raise. Assumptions: the exception element is attributed
 * honestly rather than to the loudest authority. The convention's validation gate names purpose,
 * parameters and return values, and it does not name exceptions. The exception duty rests instead on
 * the convention's own docstring-content clause, on the house convention at lines 544 to 549 of
 * {@code tests/README.md} -- which names Purpose, Parameters, Returns and Exceptions together and is
 * therefore stricter than the validation gate read alone -- and on the documentation gate, which is
 * configured to validate declared exceptions. Claiming the validation gate as the authority for that
 * clause would itself be the unfounded rationale the convention rejects, in the very file that
 * instructs four authors to avoid one.
 *
 * <p>Assumptions: once a block exists the gate requires it to be complete. A parameter clause is
 * required for every parameter, a return clause for every returning method, and no clause may be
 * left with an empty description. Clause order is purpose, then parameters, then return value, then
 * exceptions, which is the order the convention enumerates.
 *
 * <p>Assumptions: the convention lets a trivial accessor use a single-line docstring. That
 * grants a form and not an absence -- the docstring is still required, and it must still carry the
 * return clause the gate demands of a returning method. The concession is restated here because
 * this charter is the file an author of these four classes reads first.
 *
 * <h2>A green build is necessary and not sufficient in this package</h2>
 *
 * <p>Assumptions: this is the most consequential paragraph in this charter. The convention's
 * validation gate is conjunctive -- work fails it for missing the docstring, and independently for
 * missing the recorded rationale. The documentation gate mechanises the docstring half and states of
 * itself that it cannot mechanise the other half: it cannot judge whether a comment explains why
 * rather than what, cannot detect a comment that merely restates the code beside it, cannot verify
 * that one of the four categories was named or that a named one was written in the canonical form,
 * cannot identify which implementation choices were non-obvious, and cannot detect an unfounded
 * rationale anywhere outside a summary sentence. Everything it cannot see is carried by the written
 * convention and by review. A class in this package is therefore not finished at the moment the
 * build stops reporting violations.
 *
 * <p>Assumptions: the gap is not a gap in visibility, and reading it as one would be a costly
 * mistake in the opposite direction. The presence check is configured down to private visibility
 * with no annotation exempted and no line-count threshold, so a private method needs a block and
 * that block must be complete. This matters here more than in any sibling package, because a
 * transcribed paragraph naturally becomes a private helper and this package is where paragraphs are
 * transcribed: the page-boundary handlers of the list program, the validation blocks spanning
 * lines 235 to 437 of {@code app/cbl/COTRN02C.cbl}, the timestamp derivation at line 249 of
 * {@code app/cbl/COBIL00C.cbl} and the clearing routines of the view program all arrive as private
 * methods. Each is obliged twice over, by the convention's docstring requirement, which carries no
 * qualification by visibility, and by the gate. Neither obligation reaches the rationale, and the rationale is the
 * half a later reader of the code actually needs.
 *
 * <h2>The four labels, in one written form</h2>
 *
 * <p>Every rationale in this package is tagged with one of exactly four labels, written character
 * for character as {@code Alternatives Considered:}, {@code Refactoring Rationale:},
 * {@code Assumptions:} and {@code Trade-offs:}. Four properties of that form are load-bearing: each
 * label is plural where the convention writes it plural, none is parenthesised, each keeps its trailing
 * colon, and none carries emphasis markup. The hyphen in the fourth is the plain hyphen-minus and no
 * other character resembling it. {@code docs/CODE_DOCUMENTATION_STANDARD.md} is the authority for
 * this form and states it once for every language in the new trees.
 *
 * <p>Assumptions: the label is searched for before it is read. A reviewer auditing this tree against
 * the convention's validation gate has to locate every rationale across several languages, and a literal
 * string search is the only mechanism available in all of them. One spelling makes that search
 * complete; four spellings of one category make it quietly partial, and a rationale a search cannot
 * find is a rationale a review cannot count. The gate offers no help here, because a singular,
 * parenthesised or emphasis-wrapped label passes every check it applies.
 *
 * <p>Trade-offs: this diverges on purpose from the singular idiom that predominates in the
 * repository's reference test suite, so the two trees genuinely do read differently. The plural is
 * the form the governing convention uses, and its validation gate makes that wording the sentence
 * this tree is audited against; matching the older idiom would read more consistently while failing
 * to match the convention actually enforced. The divergence is recorded so that a reader who searches the
 * repository and finds both forms knows which one governs here. The two forms are never mixed inside
 * one file.
 *
 * <p>Assumptions: label text is taken from the governing convention and never copied out of
 * {@code tests/README.md}. Lines 542 and 548 of that file each carry one non-breaking hyphen and no
 * plain hyphen at all, so a label copied from there is invisible to the literal search this
 * convention depends on. This compilation unit is pure ASCII for the same reason.
 *
 * <h2>The comment idiom</h2>
 *
 * <p>An inline comment in this package is a single block sitting immediately above the code it
 * explains. It opens with one of the four labels above and its colon, then gives the reason and what
 * differs under the alternative, and it contains nothing else. Purpose is stated once, in the
 * Javadoc, where the language puts it; inside a Javadoc block the equivalent of the inline form is a
 * labelled sentence, which is how this charter is written throughout. No statement in this package
 * carries a {@code // WHAT:} line.
 *
 * <p>Refactoring Rationale: an earlier draft of this charter authorised the aligned pair
 * {@code // WHAT:} and {@code // WHY :} on statements, citing lines 267 and 270 of the reference test
 * guide as its origin. That authorisation is withdrawn, and the reversal is recorded rather than
 * quietly dropped because the question had already been settled twice the other way.
 * The "The {@code # WHAT:} / {@code # WHY :} idiom -- prose command blocks only" section of
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} confines the twin idiom to fenced
 * command blocks in prose and names {@code .java} first among the files that may not carry a
 * statement-level {@code WHAT:} comment, and the shared-library charter at
 * {@code services/common-lib/src/main/java/com/carddemo/common/package-info.java} had already
 * adopted that pair in Java and then rejected it, because its first line restates the statement it
 * sits above. Keeping the authorisation would have left three governing artifacts in contradiction,
 * which that standard's Precedence note treats as a review failure until reconciled
 * rather than as a choice a package may make locally.
 *
 * <p>Trade-offs: the withdrawn form's one defensible use was naming a migrated contract the Java
 * tokens cannot reveal -- the baseline paragraph being transcribed, or the record path and byte
 * offset a value arrived from -- and that information still has to be recorded. It moves into the
 * Javadoc of the declaration it describes, which is where the convention places purpose in any
 * case, so no provenance is lost and the note is rendered by the language's own tooling. The cost is
 * a few lines of distance between a provenance note and the annotation it explains; the benefit is
 * that nothing in this package restates the statement beneath it, which is the first pattern the
 * convention's forbidden-patterns clause rules out.
 *
 * <p>Assumptions: there is no escape from any of this inside a source file. The configuration
 * deliberately omits every filter that would let a suppression be written into Java, so a marker
 * comment has no effect and a suppression annotation suppresses nothing. The companion suppression
 * file covers generated sources and test fixtures only, and it is not extended to admit a class
 * from this package.
 *
 * <h2>The charter is itself mechanically gated</h2>
 *
 * <p>Assumptions: two checks combine on this file, and each one catches what the other cannot. The
 * first operates on the directory as a file set and requires a {@code package-info.java} to sit
 * beside the audited Java in it; the second walks this file's syntax tree and requires it to carry
 * Javadoc. A bare package declaration satisfies the first and fails the second, which is why the
 * block above is the substance of this file rather than a formality above its one statement. The
 * plugin runs in the Maven validate phase and fails the build on any violation at warning severity
 * or above, so an omission here stops every local build before compilation begins and not only the
 * pipeline.
 *
 * <p>Assumptions: the summary sentence of this block ends with a period and carries no placeholder
 * marker and no unfounded-rationale fragment, because the summary check enforces exactly that and is
 * the one check that reaches the convention's purpose element.
 *
 * <p>Trade-offs: this compilation unit holds one Javadoc block and one package declaration and
 * nothing else -- no annotation, no import, no type, no field, no method and no line comment. It
 * carries no parameter, return, exception, authorship, release or version clause, because none of
 * those has a valid subject on a package declaration, and inventing one would be the omission
 * defect of the convention's incomplete-docstring clause read from the other direction. Version control answers provenance;
 * this block answers responsibility and rationale.
 *
 * <p>Trade-offs: the source is UTF-8 with no byte-order mark and restricts its content to ASCII.
 * Typographic punctuation would read closer to the surrounding prose, but it would make the label
 * and citation searches depend on characters that are hard to tell apart on screen. ASCII keeps the
 * audited label bytes, the operators and the cited paths literal.
 */
package com.carddemo.transaction.service;
