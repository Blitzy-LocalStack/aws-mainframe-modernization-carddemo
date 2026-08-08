/**
 * REST surface of the reference-data context, and the one layer where a request is bound and validated.
 *
 * <h2>Purpose</h2>
 *
 * <p>Every type in this package is a controller. A controller binds a request, validates it, calls one
 * collaborator and returns a transfer object. None of them carries a business rule: the rules
 * transcribed from the baseline programs belong to {@code com.carddemo.reference.service}, the request
 * and response shapes belong to {@code com.carddemo.reference.dto}, and the only shared-kernel types
 * reached for here are {@code com.carddemo.common.web.PageResponse} and
 * {@code com.carddemo.common.web.CursorToken}.</p>
 *
 * <p>Assumptions: no controller declares itself to the container. The module entry point
 * {@code com.carddemo.reference.ReferenceApplication} carries {@code @SpringBootApplication} at line 32
 * of its own source, and it sits at the package root {@code com.carddemo.reference}, so the component
 * scan started from that root covers this package as one of its subpackages. A controller is discovered
 * by being annotated and by being here; it needs no registration entry and no configuration of its
 * own.</p>
 *
 * <p>On parameters, return values and exceptions: a package declaration accepts no argument, returns no
 * value and raises nothing, so this descriptor deliberately carries none of the three corresponding
 * at-clauses. The inapplicability is stated rather than left silent, because the Explainability rule
 * lists a docstring that omits its parameters or return values among its forbidden patterns at line 39,
 * and a reader has to be able to tell a declared inapplicability from an oversight. Assumptions: Javadoc
 * models no parameter, return or exception concept for a package, and the repository ruleset audits
 * at-clause bodies for emptiness through {@code NonEmptyAtclauseDescription}, so an invented empty
 * at-clause would be reported rather than credited.</p>
 *
 * <h2>The surface, and which baseline each part answers for</h2>
 *
 * <p>The controllers, all rooted at {@code /api/v1/reference}:</p>
 *
 * <ul>
 *   <li>{@code TransactionTypeController} -- the transaction-type collection and item, from
 *       {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}, the inquiry and list screen, and
 *       {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}, the maintenance screen.</li>
 *   <li>{@code TransactionCategoryController} -- the transaction-category collection and item, from the
 *       same two programs, keyed as the first labelled decision below records.</li>
 *   <li>{@code DisclosureGroupController} -- the interest-rate read, from paragraph
 *       {@code 1200-GET-INTEREST-RATE} of {@code app/cbl/CBACT04C.cbl} over the record layout at
 *       {@code app/cpy/CVTRA02Y.cpy}.</li>
 *   <li>{@code AddressLookupController} -- the three seeded allow-lists of telephone area codes, states
 *       and state-with-postal-prefix pairs, drawn from the condition-name lists of
 *       {@code app/cpy/CSLKPCDY.cpy}.</li>
 *   <li>{@code DateEvaluationController} -- the date evaluation, delegating as the third labelled
 *       decision below records.</li>
 *   <li>{@code ReferenceMaintenanceController} -- the batched maintenance action, from
 *       {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl}.</li>
 * </ul>
 *
 * <p>Assumptions: that inventory is not a directory listing a reader has to take on trust. The set of
 * controller classes and the set of operations the published contract declares are compared in both
 * directions, and the declared-operation total is asserted as well, by
 * {@code ReferenceApiRoutingContractTest} in this module's test tree -- the two set inclusions at its
 * lines 66 and 82 and the total at its line 98. An operation declared without a handler, a handler
 * added without an operation, or a silent narrowing of the surface each fail the build rather than
 * review.</p>
 *
 * <p>Alternatives Considered: restating a controller total in prose here, as a reader-facing summary.
 * Rejected, because a total in a comment and a total in an assertion are two claims that can disagree
 * while only one of them is checked, and the enclosing charter at
 * {@code com.carddemo.reference} shows exactly that hazard by naming a total one greater than the class
 * list at lines 47 to 52 of that test. Naming each class instead is more specific and cannot drift
 * arithmetically.</p>
 *
 * <h2>Why the category surface is keyed on its parent rather than nested beneath it</h2>
 *
 * <p>Alternatives Considered: the transaction category could have been published as a nested
 * sub-resource of its type, its collection hanging below the type item and its handlers folded into
 * {@code TransactionTypeController}. The parentage that suggests it is real, and it is what settles the
 * item path either way: {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L5 declares
 * {@code PRIMARY KEY(TRC_TYPE_CODE,TRC_TYPE_CATEGORY)}, and L6-L7 declare
 * {@code FOREIGN KEY TRC_TYPE_CODE (TRC_TYPE_CODE) REFERENCES CARDDEMO.TRANSACTION_TYPE (TR_TYPE) ON
 * DELETE RESTRICT}, so a category is a child whose identity is incomplete without the type it belongs
 * to. The published contract resolves it the other way round: the collection is a root collection, and
 * the item carries both key parts as path variables in the order that primary key declares them, type
 * before category. Parentage is therefore expressed by the key inside the path rather than by nesting
 * the collection, and the handlers sit on a class of their own. Nesting was rejected on two grounds --
 * it leaves a category collection unreachable without naming a parent, which is precisely what the
 * contract's root collection exists to allow, and it would place the item's operations behind a path
 * segment repeating a key the item already carries. Assumptions: where this descriptor and
 * {@code src/main/resources/openapi/reference-api.yaml} could disagree about a path, a method, a status
 * or a schema, that document is the authority and this text defers to it.</p>
 *
 * <h2>Why a restricted delete answers 409 without this package handling it</h2>
 *
 * <p>Refactoring Rationale: the baseline has the database refuse the delete and then has the program
 * compose a sentence about the refusal. {@code TRNTYCAT.ddl} L6-L7 carries {@code ON DELETE RESTRICT},
 * so Db2 answers {@code SQLCODE -532}; {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} L1914
 * branches on exactly that value with {@code WHEN SQLCODE = -532}, going on at L1923 to tell the
 * operator {@code 'Please delete associated child records first:'}. The migrated path keeps every hop of
 * that chain and moves only where the sentence is composed: the same constraint raises PostgreSQL
 * {@code SQLSTATE 23503}, the framework surfaces it as {@code DataIntegrityViolationException},
 * {@code com.carddemo.common.error.GlobalExceptionHandler} recognises it, and the caller receives HTTP
 * 409. The baseline is unchanged and remains the behavioural oracle; the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Two consequences bind this package. No type here declares {@code @RestControllerAdvice}, and none
 * declares a handler for that condition, because one handler is selected per exception type and a second
 * declaration inside this module would take precedence unpredictably -- the observable symptom being the
 * 409 reverting to a 500. And the refusal must never present as a 5xx at all: the browser screen for the
 * transaction-type list renders the 409 as a blocked delete, whereas a 500 carrying a driver message
 * would both lose that presentation and publish the schema and constraint names that the unmapped
 * alternative exposes to a client.</p>
 *
 * <p>Refactoring Rationale: a referential refusal and a duplicate key stay distinguishable to a caller
 * instead of collapsing into one undifferentiated conflict, and the baseline offers no branch to
 * inherit for the second of them. The gap is in the online write path rather than only in batch: the
 * {@code 9700-INSERT-RECORD} paragraph of {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}, from
 * L1596 to its exit at L1621, evaluates only {@code WHEN SQLCODE = ZERO} and {@code WHEN OTHER}, and a
 * search for the duplicate-key code {@code -803} counts zero occurrences in that program and zero in
 * {@code COTRTLIC.cbl}, so an insert onto an existing key lands in the same catch-all as a connection
 * failure. The two conditions reach a caller by different routes and carry different wording -- one is
 * detected before the write is attempted, the other is a translated database refusal of it -- because
 * their remedies differ: delete the children, against choose another code. Neither may be intercepted
 * locally, since handling either one here would be the second declaration ruled out above.</p>
 *
 * <h2>Why two date masks coexist and are not unified</h2>
 *
 * <p>Assumptions: this package answers for two date contracts that look similar enough to be mistaken
 * for one, and they are deliberately left separate. The edit-and-validate contract is the ten-character
 * ISO ordering {@code YYYY-MM-DD}, because {@code com.carddemo.common.validation.DateEditValidator}
 * standardises on that form for every context that edits a date. The conversion contract is the United
 * States ordering {@code MM-DD-YYYY} with a companion {@code HH:MM:SS} time, and it comes from
 * {@code app/app-vsam-mq/cbl/CODATE01.cbl}, whose {@code EXEC CICS FORMATTIME} requests
 * {@code MMDDYYYY(WS-MMDDYYYY)} at L349 with {@code DATESEP('-')} at L350 and {@code TIME(WS-TIME)} at
 * L351 with {@code TIMESEP} at L352, and whose reply is then composed at L355 from
 * {@code STRING  'SYSTEM DATE : ' WS-MMDDYYYY} and at L356 from {@code 'SYSTEM TIME : ' WS-TIME}. The
 * decisive evidence that these were never one contract is a count: a search for {@code CSUTLDTC} in
 * that conversion program returns zero occurrences, so it never calls the date-edit utility at all and
 * there is no shared mask to recover.</p>
 *
 * <p>Assumptions: delegating the edit rules to one shared validator is the faithful shape here rather
 * than a liberty taken, because the baseline had already made the mask a parameter.
 * {@code app/cbl/CSUTLDTC.cbl} is a callable subprogram whose L88 reads
 * {@code PROCEDURE DIVISION USING LS-DATE, LS-DATE-FORMAT, LS-RESULT}, over
 * {@code LS-DATE PIC X(10)} at L84, {@code LS-DATE-FORMAT PIC X(10)} at L85 and
 * {@code LS-RESULT PIC X(80)} at L86 -- a date, the mask to read it by, and a result. Those rules are
 * therefore not re-implemented anywhere in this package; a controller binds the request and hands it
 * on.</p>
 *
 * <h2>Why every list is a keyset walk, and what a caller gives up for it</h2>
 *
 * <p>Trade-offs: every list operation here answers with
 * {@code com.carddemo.common.web.PageResponse}, whose four components are exactly {@code items}, the rows
 * of the page; {@code firstKey} and {@code lastKey}, the sealed positions of its first and last row, each
 * absent precisely when the page is empty; and {@code hasNext}. There is no previous-page flag, no page
 * number, no last-page-displayed indicator and no page-size member, and none may be added, because that
 * type belongs to the shared kernel and is answered for there. Backward availability is not a separate
 * claim at all: it is the presence of {@code firstKey}, which is the position a caller sends back to
 * retreat. What a caller gives up is that one derivation. What it buys is page boundaries that hold
 * under concurrent inserts, which an offset scan cannot promise, since an offset both skips and repeats
 * rows once a row is inserted ahead of the position. Reading forward asks for keys strictly beyond the
 * last-row key in ascending order; reading backward asks for keys strictly before the first-row key in
 * descending order. There is no offset pagination and no offset helper in this package.</p>
 *
 * <p>Assumptions: the page size is stated PER ENDPOINT rather than once for the package, because two of
 * the three list families here have a baseline antecedent and one does not.
 *
 * <ul>
 *   <li>The transaction-type browse publishes <b>seven</b> rows, and it is seven because the baseline
 *       names it as a constant rather than because a screen was measured:
 *       {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} L60 declares
 *       {@code 05  WS-MAX-SCREEN-LINES     PIC S9(4)      COMP VALUE 7} and loops to that bound at
 *       L940 and L1004, and {@code app/app-transaction-type-db2/cpy-bms/COTRTLI.cpy} carries exactly
 *       {@code TRTSEL1I} through {@code TRTSEL7I}. Counting rows on the map alone would have produced
 *       the same number by coincidence and would have left the next reader unable to tell an intended
 *       limit from an artifact of a layout. This one is INHERITED: the migration plan lists page
 *       boundaries among the preserved contracts, so it is not available to be chosen.</li>
 *   <li>The transaction-category browse publishes <b>seven</b> as well, and here the number is CHOSEN.
 *       The extension ships two maps only, {@code COTRTLI.bms} and {@code COTRTUP.bms}, so no baseline
 *       screen browses categories and no boundary exists to preserve. Seven is taken from the sibling
 *       browse so that two lists paged in the same session step by the same amount.</li>
 *   <li>The three address-lookup browses -- area code, state and state/ZIP prefix -- publish
 *       <b>twenty</b>, and that figure is ADDITIVE. Those domains exist in the baseline only as
 *       condition-name allow-lists inside {@code app/cpy/CSLKPCDY.cpy}, tested against rather than
 *       displayed, and no map browses any of them, so there is no row count to inherit. The reasoning
 *       for twenty over seven is written at {@code AddressLookupController.PAGE_SIZE}.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: this paragraph asserted a single package-wide size of seven while the
 * services published ten for both reference browses and the controller published twenty for the
 * lookups, so a reader consulting the charter for the shape of a page would have been wrong about every
 * endpoint in the package. The two reference browses are corrected to seven in code, because the type
 * browse's number is a preserved contract and the category browse follows it; the lookups keep twenty
 * and are recorded as additive. The claim is stated per endpoint rather than as one number because that
 * is what is true, and because collapsing three different provenances into one figure is how the
 * original error became possible.</p>
 *
 * <p>Assumptions: no page size is a request parameter in any of these operations, so none of these
 * numbers is negotiable by a caller. {@code openapi/reference-api.yaml} declares no page-size, page-
 * number, offset or total-pages parameter anywhere, which is what keeps the figures above a property of
 * the published contract rather than of a particular request.</p>
 *
 * <p>Assumptions: the baseline was itself already walking by key rather than scanning by position, so
 * this is a transcription and not a redesign. The same program declares {@code C-TR-TYPE-FORWARD} at
 * L339 with {@code ORDER BY TR_TYPE} at L351 and {@code C-TR-TYPE-BACKWARD} at L355 with
 * {@code ORDER BY TR_TYPE DESC} at L367; it fetches forward at L1627, takes one extra row at L1662 to
 * learn whether another page exists, and fetches backward at L1754. Ascending forward, descending
 * backward, and one row beyond the page to settle the more-rows question: that is the keyset shape,
 * arrived at independently.</p>
 *
 * <p>Trade-offs: the baseline also displayed a page number while paging by key, and that combination is
 * worth naming so it is not read as licence. {@code app/app-transaction-type-db2/cpy-bms/COTRTLI.cpy}
 * L60 declares {@code PAGENOI PIC X(3)}, an output field on a screen whose cursors are the two declared
 * above. A displayed ordinal is a client-side tally in the migrated form, and displaying one does not
 * license paging by offset, because the ordinal is derived from how many pages a caller has walked and
 * never from a row's position in the table.</p>
 *
 * <p>Assumptions: a page boundary leaves this package sealed rather than in the clear. A controller
 * holds the sealer from {@code com.carddemo.common.web.CursorToken} and hands the opened position to its
 * collaborator, so the key a boundary is built from is never expressed to a client and the layer that
 * reads rows never mints a position. Alternatives Considered: letting the service hold the sealer.
 * Rejected, because it would put key material in the layer furthest from the request boundary and would
 * make a unit test of a rule require a signing key it has no use for.</p>
 *
 * <h2>Why nothing here remembers a previous turn</h2>
 *
 * <p>Refactoring Rationale: the baseline is pseudo-conversational, so continuity between screen turns
 * lives in storage echoed to the terminal and handed back. Two such buffers belong to this context: the
 * shared communication area {@code CARDDEMO-COMMAREA} declared at {@code app/cpy/COCOM01Y.cpy} L19, and
 * the maintenance screen's own staging area {@code WS-THIS-PROGCOMMAREA} at
 * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} L294 carrying
 * {@code 10 TTUP-CHANGE-ACTION PIC X(1)} at L296. Neither is carried across. The shared one decomposes
 * into four separate mechanisms:</p>
 *
 * <ul>
 *   <li>Navigation becomes routing in the browser. The transfer graph the baseline walks with
 *       {@code EXEC CICS XCTL} between transactions {@code CTLI}, {@code CTTU} and the menu is a set of
 *       client-side route changes, so no request carries a next-program field and no response names
 *       one.</li>
 *   <li>Identity becomes validated token claims, converted by
 *       {@code com.carddemo.common.security.JwtRoleConverter}. This is a change in what can be asserted,
 *       not only in where it is held: {@code COCOM01Y.cpy} L26 declares
 *       {@code 10 CDEMO-USER-TYPE PIC X(01)} with the quoted condition values
 *       {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} at L27 and {@code 88 CDEMO-USRTYP-USER VALUE 'U'} at
 *       L28, in storage the client hands back, whereas a signed claim is not something a client can
 *       state about itself.</li>
 *   <li>Selection becomes a path variable. The chosen transaction-type code arrives in the request path
 *       rather than being remembered between turns, which is what lets each request be authorised on its
 *       own.</li>
 *   <li>The re-entry discriminator disappears entirely. {@code COCOM01Y.cpy} L29 declares
 *       {@code 10 CDEMO-PGM-CONTEXT PIC 9(01)} with the bare condition values
 *       {@code 88 CDEMO-PGM-ENTER VALUE 0} at L30 and {@code 88 CDEMO-PGM-REENTER VALUE 1} at L31 --
 *       quoted for the user type four lines above, unquoted here, which is a difference worth reading
 *       carefully rather than assuming. A stateless handler answering with a field-error array has no
 *       first-entry-against-re-entry distinction left to draw, so no request parameter, header or body
 *       member in this package reports a re-entry, a turn count or a resubmission.</li>
 * </ul>
 *
 * <p>Trade-offs: dropping that discriminator severs a coupling that changes what a caller sees on a
 * first submission. {@code app/cpy/CSSETATY.cpy} gates its error highlighting on
 * {@code AND CDEMO-PGM-REENTER} at L20, so the baseline decides how to present an error partly from a
 * remembered turn count, and a first-entry validation failure highlights nothing. Presentation here is
 * driven purely by the response body, so the same failure marks its fields immediately. That is intended
 * and accepted: the alternative was to reintroduce a turn counter for no purpose beyond suppressing
 * feedback a caller has already earned. The baseline is unchanged; the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <h2>Why authority is decided once for the module and never at a method here</h2>
 *
 * <p>Assumptions: no controller in this package carries a method-level authority annotation, and their
 * absence is a decision rather than an omission. {@code com.carddemo.reference.config.SecurityConfig}
 * decides authority by HTTP method for the whole module -- every write requires the administrative group
 * authority and every read requires either group authority -- and the published contract states the same
 * model. Alternatives Considered: annotating each handler as well, for locality. Rejected, because two
 * declarations able to answer one question can disagree with no rule for which wins, and the failure is
 * silent in the direction that matters: the looser of the two is the one a reader would not notice.</p>
 *
 * <h2>Why three of the reads reach a repository with no service between</h2>
 *
 * <p>Alternatives Considered: a lookup service wrapping the three seeded allow-list repositories was
 * evaluated and rejected, and the sibling {@code com.carddemo.reference.service} charter records the same
 * decision. Those three tables carry no rule beyond whether a code is present, drawn from the
 * condition-name lists of {@code app/cpy/CSLKPCDY.cpy}, so a service over them would forward a call and
 * add a file. The two transaction-reference tables are the opposite case -- a version comparison, a
 * referential refusal and three distinct write behaviours -- and every one of those goes through a
 * service. The compromise accepted is that this package is not uniform in depth, which is why the
 * asymmetry is recorded here rather than left for a reader to interpret as an oversight.</p>
 *
 * <h2>The contract is the authority, and two of its wire types invite the wrong guess</h2>
 *
 * <p>Assumptions: {@code src/main/resources/openapi/reference-api.yaml} is the endpoint and schema
 * contract of record, written to OpenAPI 3.1, served by springdoc from this module and consumed by the
 * browser client {@code ui/src/api/reference.ts}. It declares 400, 401, 403, 404 and 409 as first-class
 * responses alongside the success statuses, so a caller can be written against refusals rather than
 * discovering them. Where this descriptor and that document could disagree, the document wins.</p>
 *
 * <p>Two of its declared types are worth naming because the intuitive reading of each is the wrong
 * one:</p>
 *
 * <ul>
 *   <li>The category code is a string of exactly four digits and never an integer, because its leading
 *       zeros are part of the key: the seeded codes begin {@code 0001}, and an integer would return
 *       {@code 1} where the key is {@code 0001}. Three character-typed sources agree against the two
 *       copybooks that declare it numeric -- {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L3
 *       declares {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL}, the migrated column is {@code CHAR(4)}, and
 *       the schema pattern admits four digits only -- while {@code app/cpy/CVTRA02Y.cpy} L8 declares
 *       {@code DIS-TRAN-CAT-CD PIC 9(04)}. Nothing here performs arithmetic on it.</li>
 *   <li>The interest rate is a string and never a JSON number. {@code app/cpy/CVTRA02Y.cpy} L9 declares
 *       {@code DIS-INT-RATE PIC S9(04)V99}, and the rate is an operand rather than a display value: it
 *       is multiplied in the accrual computation of {@code app/cbl/CBACT04C.cbl}. A client handed a JSON
 *       number parses it into an IEEE-754 binary approximation and carries that inexactness into every
 *       figure derived from it, so the string form is what stops the wire boundary from being where
 *       exactness is lost. Every monetary and rate value crossing this package is an exact decimal at
 *       every hop, and the ArchUnit rule {@code LayeringRulesTest} asserts the absence of the IEEE-754
 *       binary types by name rather than leaving it to review.</li>
 * </ul>
 *
 * <h2>Every function key the baseline offered has an operation to reach</h2>
 *
 * <p>Assumptions: the keyboard itself belongs to the browser, but the completeness obligation belongs
 * here. {@code app/cpy/CSSTRPFY.cpy}, 85 lines, normalises the terminal's attention identifier into named
 * flags: paragraph {@code YYYY-STORE-PFKEY.} at L17 opens an {@code EVALUATE TRUE} at L21 that closes at
 * L78. Every action reachable by a key on the two screens this context answers for has an operation on
 * the controllers above, because a key with no operation would be a capability lost in translation rather
 * than a decision.</p>
 *
 * <p>Two details of that copybook are counter-intuitive and were settled by counting rather than by
 * reading:</p>
 *
 * <ul>
 *   <li>The terminal constants are unpadded and the target flags are padded, so the two naming schemes do
 *       not line up. The copybook tests {@code DFHPF1} through {@code DFHPF9} with a single digit -- a
 *       search for {@code DFHPF0} counts zero occurrences, so a nine-key constant is never written with a
 *       leading zero -- and continues with {@code DFHPF10} to {@code DFHPF12}, while every flag it sets
 *       is zero-padded, {@code CCARD-AID-PFK01} through {@code CCARD-AID-PFK12}, declared over
 *       {@code 10 CCARD-AID PIC X(5)} at {@code app/cpy/CVCRD01Y.cpy} L3 with condition values
 *       {@code 'ENTER'}, {@code 'CLEAR'}, {@code 'PA1  '}, {@code 'PA2  '} and {@code 'PFK01'} through
 *       {@code 'PFK12'}. All twelve of the upper-bank aliases fold onto the lower bank, from
 *       {@code DFHPF13} at L54 to the last at L77, both mapping to {@code CCARD-AID-PFK12} in that final
 *       pair, so an upper-bank key is indistinguishable from its lower-bank equivalent by design. The
 *       copybook holds only {@code SET} statements: it declares no picture, no group item and no
 *       condition name, all of which live in {@code CVCRD01Y.cpy}.</li>
 *   <li>Its {@code EVALUATE} has 28 branches and no otherwise-branch, so an unrecognised attention
 *       identifier leaves the flag exactly as it was rather than clearing it. The invalid-key message is
 *       consequently produced by the caller testing the flags, never by the normaliser. The migrated
 *       equivalent is an unrecognised action being refused by the request binding, which is why the
 *       contract declares 400 on every operation.</li>
 * </ul>
 *
 * <p>Assumptions: the two screens do not offer the same keys, and the difference is asserted by absence
 * rather than inferred. The maintenance screen names its keys as separate map fields --
 * {@code app/app-transaction-type-db2/cpy-bms/COTRTUP.cpy} declares {@code FKEYSI PIC X(21)} at L84 and
 * then {@code FKEY04I PIC X(9)} at L90 to confirm a delete, {@code FKEY05I PIC X(8)} at L96 to add or
 * save, {@code FKEY06I PIC X(6)} at L102 and {@code FKEY12I PIC X(10)} at L108 to cancel -- and it
 * declares no seventh or eighth key field at all, so there is no paging on that screen and no paging
 * operation to provide for it. The list screen names none of them: a search for a key field in
 * {@code app/app-transaction-type-db2/cpy-bms/COTRTLI.cpy} counts zero occurrences, because its legend is
 * unnamed literal text in the map, so its inventory has to be read from the program rather than from the
 * symbolic map. What that map does name is the paging ordinal {@code PAGENOI PIC X(3)} at L60 and the two
 * filters {@code TRTYPEI PIC X(2)} at L66 and {@code TRDESCI PIC X(50)} at L72.</p>
 *
 * <h2>How a validation failure is reported</h2>
 *
 * <p>Refactoring Rationale: the baseline reports a validation failure by setting a one-byte flag per
 * field and letting the map-attribute template read it. Here the same outcome becomes a per-field error
 * array on the response body, carried by {@code com.carddemo.common.validation.FieldValidationFlag},
 * including the asterisk marker the template writes for a blank field. Three properties of the baseline
 * flag decide how it must be read, and each is easy to invert:</p>
 *
 * <ul>
 *   <li>The low-value byte means valid, not unset. {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}
 *       L95 declares {@code 88 FLG-TRANFILTER-ISVALID VALUE LOW-VALUES}, and
 *       {@code app/cpy/CVCRD01Y.cpy} L30 corroborates the same convention independently with
 *       {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES}. Reading an all-low byte as absent rather than
 *       as acceptable would invert every verdict in the package.</li>
 *   <li>Blank is a kind of error and not a third state beside it. L96 declares
 *       {@code 88 FLG-TRANFILTER-NOT-OK VALUE '0'} and L97 declares
 *       {@code 88 FLG-TRANFILTER-BLANK VALUE 'B'} as peer values of the one byte at L94, and
 *       {@code app/cpy/CSSETATY.cpy} tests them together in a single disjunction at L18-L19,
 *       {@code IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK)}. A controller therefore asks the
 *       derived predicate {@code isError()} and never compares against a code, so the two cannot drift
 *       apart into two questions.</li>
 *   <li>Key and non-key flags are distinguished structurally, and that distinction is preserved. L94
 *       declares the key's flag as a standalone {@code 05  WS-EDIT-TTYP-FLAG PIC X(1)}, whereas L99 opens
 *       a group {@code 05 WS-NON-KEY-FLAGS} with {@code 10  WS-EDIT-DESC-FLAGS PIC X(1)} nested beneath
 *       it at L100. A failure on the key and a failure on a described attribute are not
 *       interchangeable.</li>
 * </ul>
 *
 * <p>Assumptions: the asterisk is presentation and never a domain value. {@code app/cpy/CSSETATY.cpy}
 * moves the error colour into the field's colour subfield at L21-L22 and moves {@code '*'} into the
 * field's output subfield at L24-L25 -- two different targets, one styling the field and the other
 * writing into it -- so the marker travels as a rendering instruction beside the error and never as the
 * value of the field it marks.</p>
 *
 * <h2>User-visible wording is reproduced as declared, including its irregularities</h2>
 *
 * <p>Assumptions: the message contract widths are the working-storage widths and not the wider screen
 * fields that display them -- 40 characters for an informational message and 75 for an error.
 * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} L142 declares
 * {@code 05  WS-INFO-MSG PIC X(40)} and L167 declares {@code 05  WS-RETURN-MSG PIC X(75)}, and the 75 is
 * corroborated independently by {@code app/cpy/CVCRD01Y.cpy} L28-L29, which declare both
 * {@code CCARD-ERROR-MSG} and {@code CCARD-RETURN-MSG} as {@code PIC X(75)}. The map fields are wider on
 * both screens, and taking a width from them would enlarge the contract to accommodate padding: the
 * maintenance map declares {@code INFOMSGI PIC X(45)} at L72 and {@code ERRMSGI PIC X(78)} at L78, and
 * the list map the same pair at L222 and L228.</p>
 *
 * <p>Trade-offs: two irregularities in the declared wording are reproduced character for character
 * rather than tidied. {@code COTRTUPC.cbl} L161 declares
 * {@code 'Changes validated.Press F5 to save'} with no space after the period, and L184 declares
 * {@code 'Record changed by some one else. Please review'} with 'some one' as two words. The notation for
 * the keys themselves is inconsistent across the same program and is likewise left alone, as at L150,
 * {@code 'Press F05 to add. F12 to cancel'}. Harmonising any of them would be a behavioural change with
 * no requirement behind it, and an operator or a test comparing text would see a difference the migration
 * did not need to introduce; the cost accepted is that the wording looks unpolished on purpose.</p>
 *
 * <p>Assumptions: two declared strings are deliberately not reachable from this surface, and both are
 * recorded so their absence reads as a decision. {@code COTRTUPC.cbl} L196 declares
 * {@code 'Looks Good.... so far'}, which is a developer placeholder rather than a message to an operator
 * and is not carried across. L174 declares
 * {@code 'Name can only contain alphabets and spaces'}, which is declared and set nowhere in either
 * program -- a search for it as a target of a move counts zero occurrences -- and its wording disagrees
 * with the character class the program actually applies:
 * {@code 01  LIT-ALL-ALPHA-FROM     PIC X(52) VALUE SPACES} at L249 spans 26 upper-case and 26
 * lower-case letters and admits no space. Any pattern constraint declared in this package follows the
 * character class rather than the unreachable sentence, so that sentence is emitted by no validation path
 * here.</p>
 *
 * <h2>Why this descriptor exists</h2>
 *
 * <p>Assumptions: user-specified Rule 1, Explainability, requires a docstring on every module entry
 * point at its line 15, and in Java the entry point of a package is its package declaration, which only
 * a {@code package-info.java} can carry Javadoc for. Its Validation Gate at line 43 is conjunctive: a
 * docstring stating purpose and, separately, a rationale for each non-obvious decision, with either one
 * missing failing review. That is why this file states both what the package is for and why each of the
 * decisions above was taken instead of its alternative, and why every rationale names a source line, a
 * declared width or a named constraint rather than asserting a preference.</p>
 *
 * <p>Assumptions: two checks in {@code config/checkstyle/checkstyle.xml} enforce that pairing and
 * neither is sufficient alone. {@code JavadocPackage} runs over the file set and requires that this file
 * exist; {@code MissingJavadocPackage} runs over the parsed tree and requires that it carry Javadoc. A
 * file holding nothing but a package declaration satisfies the first and fails the second, which is the
 * gap the pairing closes. Both are bound to the {@code validate} phase, so a lapse stops the reactor
 * ahead of compilation rather than at review. The written convention the block above follows, including
 * the plural colon-terminated form of the four rationale labels and why that form differs from the
 * singular idiom of the repository's reference-only suite, is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}; the two forms are never mixed inside one file.</p>
 */
package com.carddemo.reference.api;
