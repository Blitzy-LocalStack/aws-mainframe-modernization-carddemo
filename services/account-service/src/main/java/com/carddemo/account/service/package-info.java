/**
 * Business rules of the account bounded context, transcribed paragraph by
 * paragraph from the COBOL baseline. This package is the service layer of
 * account-service and it carries decision logic only: the controllers above
 * it validate and shape HTTP, the repositories below it read and write rows,
 * and the classes here own the rules that neither of those layers may hold.
 *
 * <h2>Target contract, not a directory listing</h2>
 *
 * <p>Assumptions: every inventory, file name, class name and count in this charter states the
 * package's <b>target contract</b> as the migration plan assigns it. It is a specification of what
 * this package owns and of what it may never hold, so it is read against the plan rather than against
 * a listing of the directory beside it.</p>
 *
 * <p>Alternatives Considered: deriving the inventory from the directory instead of from the plan.
 * Rejected, because a charter that describes whatever happens to be present cannot say what may
 * <em>not</em> be added, and that is the half of a package contract a reader cannot reconstruct from
 * the files. Stating the closed set costs a charter that has to be revised when the contract itself
 * changes, and buys a boundary a reviewer can enforce against a proposed addition.</p>
 *
 * <p>Each significant COBOL paragraph becomes one named method, so the
 * traceability matrix recorded at
 * {@code docs/architecture/cobol-to-service-traceability.md} can cite a
 * paragraph-to-method pair for every migrated rule instead of naming a class
 * and leaving a reader to search it. Every collaborator arrives through
 * constructor injection.
 *
 * <p>Refactoring Rationale: the baseline reaches its subroutines by static
 * {@code CALL} linkage and holds their state in shared
 * {@code WORKING-STORAGE}, carrying it between programs in the structure at
 * {@code app/cpy/COCOM01Y.cpy} lines 19 to 44, so no rule there can be
 * exercised on its own. A rule whose inputs are constructor and method
 * arguments runs under a plain unit test with no CICS region and no database
 * attached, and obtaining that property is the reason the indirection is
 * introduced.
 *
 * <h2>Service classes and their baseline provenance</h2>
 *
 * <p>The lengths below are the physical lengths of the reference sources.
 * Those sources are read as the specification for this package and are never
 * modified. The roster is complete for this directory, and it is held to the
 * directory mechanically rather than by review:
 *
 * <pre>
 * this directory: 9 java files = 8 classes + 1 charter
 * </pre>
 *
 * <p>Refactoring Rationale: that marker line is not decoration. This roster
 * previously closed at five classes and omitted {@code CustomerIdentifierCipher},
 * which sat beside it -- a reader counting the directory against the list found one
 * more class than the list admitted to, which is the exact failure the list's own
 * final entry warns about. The line is the form
 * {@code common-lib}'s {@code PackageCharterInventoryTest} re-measures, so both
 * figures and every enumerated member below are now checked against this directory
 * on every build, and the next class that lands here fails that check instead of
 * silently falsifying this paragraph.
 *
 * <ul>
 *   <li>{@code AccountViewService} carries the read path, from
 *       {@code app/cbl/COACTVWC.cbl} (941 lines, CICS transaction
 *       {@code CAVW}) and {@code app/cbl/CBACT01C.cbl} (430 lines).</li>
 *   <li>{@code AccountUpdateService} carries the update path, from
 *       {@code app/cbl/COACTUPC.cbl} (4236 lines, CICS transaction
 *       {@code CAUP}), the largest online program in the baseline.</li>
 *   <li>{@code AddressValidationService} carries phone area code, state and
 *       state with ZIP prefix validation, from {@code app/cpy/CSLKPCDY.cpy}
 *       (1318 lines).</li>
 *   <li>{@code InquiryMessageListener} carries the asynchronous inquiry
 *       path, from {@code app/app-vsam-mq/cbl/COACCT01.cbl} (620 lines).</li>
 *   <li>{@code CustomerIdentifierCipher} protects the two customer identifiers
 *       that must not travel or rest in the clear -- the national identifier
 *       {@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy} line 18 and the
 *       government-issued identifier {@code CUST-GOVT-ISSUED-ID PIC X(20)} at line
 *       20 -- which the baseline stores as plain fields of the 500-byte customer
 *       record. It has no reference source of its own: the baseline stores both in
 *       the clear, so the encryption is a documented security correction rather
 *       than a migrated rule. Assumptions: it is a service and not a mapper because
 *       it holds key material and performs a cryptographic operation, where a
 *       mapper is a pure shape conversion; the mapper package calls it rather than
 *       containing it.</li>
 *   <li>{@code RestReferenceAddressLookup} satisfies the lookup contract the
 *       address validator declares, reaching {@code reference-service} over its
 *       published address API. It has no reference source of its own: in the
 *       baseline the lookup lists are a copybook compiled INTO the program, so
 *       the adapter exists because the target moved that data behind a service
 *       boundary and something has to cross it.</li>
 *   <li>{@code InquiryListenerHealth} reports whether the inquiry consumer above
 *       is actually consuming, as a health contribution. It carries no reference
 *       source in the sense the entries above do, and it does carry that program's
 *       DISCIPLINE: {@code app/app-vsam-mq/cbl/COACCT01.cbl} opens all three of its
 *       queues before it takes a single message -- at lines 227, 261 and 318 -- and
 *       performs {@code 8000-TERMINATION} if any open fails, at lines 250, 284 and
 *       319, so a consumer that cannot reach its queues ends rather than lingering.
 *       A container-hosted consumer cannot end itself, so the same intent is
 *       expressed through the signal its platform acts on. Assumptions: it is a
 *       separate class rather than an interface on the listener because the state it
 *       reports belongs to the listener's CONTAINER, which the listener does not own
 *       and does not reach; this class resolves it from the container registry by the
 *       identifier the listener's own annotation declares.</li>
 *   <li>{@code AccountRevision} renders the optimistic concurrency token both
 *       published routes carry, from the before-image comparison
 *       {@code app/cbl/COACTUPC.cbl} declares at line 669 and tests at lines 521
 *       and 522. It carries no reference source of its own because the reference
 *       compares the whole snapshot rather than a token; what survives is the
 *       comparison, and this class is where its RENDERING lives so that the read
 *       path and the write path cannot disagree about the format.</li>
 * </ul>
 *
 * <p>⚠️ Refactoring Rationale: the list above is COMPLETE, and it is stated as
 * complete because a reader counting this package's classes against it must find
 * the same number. It previously omitted {@code CustomerIdentifierCipher} while
 * asserting completeness in the entry above it, so the count was already one
 * short of the directory before {@code AccountRevision} was added. Two of the
 * three entries that carry no migrated program are listed for the same reason:
 * a roster that silently drops the classes without a baseline source describes
 * this package as smaller than it is, and the classes it drops are exactly the
 * ones a reader is least likely to be able to account for.
 *
 * <h2>No class here holds session state</h2>
 *
 * <p>The CICS pseudo-conversational mechanism that made session state
 * necessary does not survive the migration, so no class in this package
 * holds any. {@code app/cpy/COCOM01Y.cpy} declares a 160-byte
 * {@code CARDDEMO-COMMAREA} across lines 19 to 44, grouped into five
 * {@code 05} items at lines 20, 32, 37, 40 and 42. That single structure
 * decomposes into four separate target mechanisms.
 *
 * <ul>
 *   <li>Navigation fields become client side router history.</li>
 *   <li>Identity becomes validated JWT claims. The baseline carries
 *       {@code CDEMO-USER-ID PIC X(08)} at line 25 and
 *       {@code CDEMO-USER-TYPE PIC X(01)} at line 26, whose administrator
 *       and user domain is bounded by the condition names at lines 27 and
 *       28 for {@code 'A'} and {@code 'U'}.</li>
 *   <li>Selection context becomes a member of the REST request BODY, taken
 *       from the customer, account and card fields at lines 33, 38, 39 and
 *       41. Refactoring Rationale: this read "REST path and query
 *       parameters", the wording of §0.7.1 of the technical specification.
 *       This context departs from it -- no handler in the module binds a
 *       path variable, and only the paging cursor and direction are query
 *       parameters -- and the departure is registered as
 *       {@code D-ACCOUNT-SELECTION-IN-BODY} in
 *       {@code docs/architecture/cobol-to-service-traceability.md} §7.4,
 *       which holds the load-balancer access-record reasoning that decides
 *       it and the costs it accepts.</li>
 *   <li>The re-entry discriminator is removed entirely. It is
 *       {@code CDEMO-PGM-CONTEXT} at line 29, with
 *       {@code 88 CDEMO-PGM-ENTER VALUE 0.} at line 30 and
 *       {@code 88 CDEMO-PGM-REENTER VALUE 1.} at line 31, and a stateless
 *       handler that answers with a field error array has no first entry
 *       against re-entry distinction left to draw.</li>
 * </ul>
 *
 * <p>Assumptions: with no Transaction Work Area to fall back on, the whole
 * of the continuity between screen turns lived in that communication area.
 * Both transactions are defined with {@code TWASIZE(0)} in
 * {@code app/csd/CARDDEMO.CSD}, at line 308 for {@code CAUP} and at line
 * 318 for {@code CAVW}, so decomposing the communication area accounts for
 * all of the state the baseline kept and leaves nothing for a server side
 * session store to hold.
 *
 * <h2>Optimistic concurrency is transcribed, not introduced</h2>
 *
 * <p>The account and customer aggregates updated from this package do carry
 * optimistic concurrency versioning, and that is a transcription rather than
 * an addition. {@code app/cbl/COACTUPC.cbl} snapshots the pre-edit record
 * into {@code ACUP-OLD-DETAILS} at line 669, stages the operator's edits in
 * {@code ACUP-NEW-DETAILS} at line 757, and compares the snapshot field by
 * field against the freshly read record in the comparison paragraph at lines
 * 4109 to 4192 before it rewrites anything.
 *
 * <p>Assumptions: the baseline holds no record lock across operator think
 * time, which is precisely why it needs a before image at all. A version
 * column therefore expresses a check the baseline already performs, and the
 * losing writer is rejected on the same condition that the paragraph at
 * {@code app/cbl/COACTUPC.cbl} lines 4109 to 4192 detects. Mapping that
 * rejection onto an HTTP status is owned by the shared exception handler in
 * {@code common-lib} and is deliberately not restated here.
 *
 * <h2>No transactional outbox in this package</h2>
 *
 * <p>This package publishes no reply through a transactional outbox, and the
 * reason is a property of the baseline it migrates rather than a preference.
 * {@code app/app-vsam-mq/cbl/COACCT01.cbl} takes its request under syncpoint
 * at line 347 and puts its reply under syncpoint at line 475, and no
 * no-syncpoint option appears anywhere in that file, so receive, work and
 * reply already commit as one unit and no reply can be stranded between
 * them.
 *
 * <p>Alternatives Considered: adding an outbox here for symmetry with the
 * authorization bounded context was evaluated and rejected. That context
 * needs one because
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} reads with the
 * no-syncpoint option at line 389 and replies with the no-syncpoint option
 * at line 753, which leaves a window in which its database work is committed
 * and its reply is not. Carrying that pattern into this package would add an
 * outbox table, a publisher and a poller in order to close a window that
 * lines 347 and 475 already hold shut, and each of those parts would then
 * need failure handling of its own.
 *
 * <h2>Money</h2>
 *
 * <p>Money is exact scaled decimal at every hop through this package. It is
 * {@code NUMERIC(12,2)} in PostgreSQL, {@code BigDecimal} at scale 2 with
 * {@code RoundingMode.HALF_UP} in Java, and a JSON string on the wire.
 *
 * <p>Trade-offs: carrying an amount as a string costs each caller an
 * explicit parse and makes the payload slightly larger. That is accepted
 * because most clients parse a JSON number into an IEEE 754 binary double,
 * and a binary double cannot hold the scaled decimal values the baseline
 * masters carry, of which {@code ACCT-CURR-BAL PIC S9(10)V99} at
 * {@code app/cpy/CVACT01Y.cpy} line 7 is the exemplar, so the alternative
 * would surrender exactness at the one boundary a cardholder actually reads.
 * IEEE 754 binary floating point appears nowhere in the money path.
 *
 * <p>Assumptions: the documentation obligation these comments discharge is
 * defined once at {@code docs/CODE_DOCUMENTATION_STANDARD.md}, and the
 * rationale categories used above are the ones it names. Presence and
 * completeness are enforced mechanically by
 * {@code config/checkstyle/checkstyle.xml}, which the build binds to the
 * {@code validate} phase, so a package left undocumented fails before a
 * single class is compiled.
 */
package com.carddemo.account.service;
