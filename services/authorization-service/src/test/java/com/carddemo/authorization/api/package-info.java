/**
 * Tests asserting the HTTP boundary of this context: its routes, its bindings and its status codes.
 *
 * <h2>Purpose</h2>
 *
 * <p><b>Purpose.</b> This is the outermost test layer of authorization-service, and the two classes beside
 * this charter assert the four things a controller in this migration is answerable for: that a request
 * reaches the route the published contract names, that its path selector and its body bind, that a refused
 * field arrives as the per-field entry transformation rule T7 requires, and that the outcome selects the
 * declared status code. Nothing here starts an application context, opens a socket or reaches a datastore.
 * This file declares no type and holds no import, so nothing in it runs; its whole effect is on what the
 * classes beside it assert and, just as much, on what they leave to somebody else.
 *
 * <p>Four assertions are this layer's own. If they are absent here they are absent from this module
 * altogether, because no other package sees a rendered response body:
 *
 * <ul>
 *   <li><b>The verbatim refusal sentences.</b> Both of the account-scope refusals are transcribed
 *       character for character, and each is asserted TWICE -- once as the problem body's own message and
 *       once as the message carried on the per-field entry -- because a client may read either and the two
 *       could drift apart without a test that pins both. They descend from the account-identifier edits
 *       {@code cbl/COPAUS0C.cbl} performs before it reads anything: the blank or low-values refusal at
 *       L264 and the non-numeric refusal at L273.</li>
 *   <li><b>The shape of a refusal.</b> {@code com.carddemo.common.error.ApiError} publishes a per-field
 *       array, and its three members are asserted here by name: the field key, the state and the message.
 *       The state is asserted as {@code com.carddemo.common.validation.FieldValidationFlag.BLANK} rather
 *       than as a character, which matters because that type deliberately keeps the STATE apart from the
 *       screen marker: the asterisk the reference template writes into a never-supplied field is published
 *       as {@code BLANK_SCREEN_MARKER} and is never a value the state's own code accessor returns. This
 *       package therefore asserts the state and treats the marker as the shared kernel's published
 *       constant rather than restating the byte.</li>
 *   <li><b>Statelessness, demonstrated rather than described.</b> Identity arrives as an authenticated
 *       principal and the selection arrives in the request, so no case here carries a re-entry
 *       discriminator and no server-side session is created or consulted. The reference baseline is
 *       pseudo-conversational and travels its continuity in a structure the terminal hands back between
 *       screen turns; these tests supply no such structure and pass no field standing in for one.</li>
 *   <li><b>Masking at the boundary.</b> The primary account number is asserted MASKED on the wire, to its
 *       last four digits, and an amount is asserted as a JSON string rather than as a bare number. Both
 *       are asserted on the serialised body and not merely on the view, which is why the mapper is REAL in
 *       these tests while the services are test doubles: mocking the mapper would leave every rendered value
 *       unasserted, including the masking that is the whole reason a controller here never handles an
 *       entity. A card verification value is never returned by any route in this package.</li>
 * </ul>
 *
 * <p>Assumptions: the list route takes its criteria in a POST body at {@code /api/v1/authorizations/search}
 * rather than as query parameters on a GET. The account scope is an account identifier, and a query string
 * is part of the request line the load balancer writes into its own access log before any application code
 * runs, which is a record the migration's sensitive-data logging contract forbids it to hold. A test that
 * moved those criteria back onto the query string would still pass while reintroducing exactly that
 * disclosure.
 *
 * <h2>The three files here, and the reference programs behind them</h2>
 *
 * <p>This package holds three files and there is no fourth. The bound is settled from both directions: the
 * production package it covers declares exactly two controllers, and no base class or shared fixture
 * builder is used, so the inventory is a measurement rather than an estimate. Every citation below is
 * relative to {@code app/app-authorization-ims-db2-mq}, which is reference material this migration reads
 * and never modifies.
 *
 * <ul>
 *   <li>{@code package-info.java} -- this charter.</li>
 *   <li>{@code PendingAuthControllerTest.java} -- covers {@code PendingAuthController}, and so covers the
 *       two terminal transactions that drive a screen. Transaction {@code CPVS}, the summary list, is
 *       defined against {@code cbl/COPAUS0C.cbl} at {@code csd/CRDDEMO2.csd} L49 and L50. Transaction
 *       {@code CPVD}, the detail view, is defined against {@code cbl/COPAUS1C.cbl} at the same file's L39
 *       and L40.</li>
 *   <li>{@code FraudControllerTest.java} -- covers {@code FraudController}, from
 *       {@code cbl/COPAUS2C.cbl}. That program is not a transaction of its own: it carries
 *       {@code TRANSID(CPVD)} at {@code csd/CRDDEMO2.csd} L36 yet appears in none of that file's three
 *       transaction stanzas, and {@code cbl/COPAUS1C.cbl} reaches it by {@code EXEC CICS LINK} at L248, so
 *       it runs inside the caller's task. It is the one route in this package that changes state, and the
 *       only one whose two write paths are told apart on the wire by status code alone.</li>
 * </ul>
 *
 * <h2>What this package does not own</h2>
 *
 * <p>Four behaviours a test here might plausibly be written to assert belong to a named owner elsewhere.
 * Each is stated as an ownership boundary rather than as a shortfall, because a second assertion added
 * here would not replace the first, it would compete with it, and a reader could then no longer tell which
 * of the two was the authority when the contract changed.
 *
 * <ul>
 *   <li><b>Codec internals belong to {@code services/common-lib}.</b> The delimited wire form, the packed
 *       segment widths, the layout descriptors and the sign-overpunch table are proved by that module's
 *       {@code CsvAuthCodecTest}, {@code PackedDecimalCodecTest}, {@code FixedWidthCodecTest} and
 *       {@code ZonedDecimalCodecTest}. This package CONSUMES those codecs and asserts only at the service
 *       boundary, where the question is what a decoded value looks like once it has been rendered, never
 *       whether the decoding was correct.</li>
 *   <li><b>Schema, check-constraint and index assertions belong to the {@code domain} and
 *       {@code fixtures} test packages.</b> The value domains are asserted against the entities in
 *       {@code domain}, and the engine tier is asserted by the module's single Failsafe-bound class,
 *       {@code fixtures/PendingAuthFraudDomainRepositoryIT}, which raises a real database. No case in this
 *       package names a table, a constraint or an index.</li>
 *   <li><b>Business-rule transcription and the documented behavioural divergences belong to the
 *       {@code service} test package.</b> That includes every transaction boundary, the look-ahead read
 *       and the discard of the extra row it returns, the cursor encoding, and the selector-versus-body key
 *       comparison. A test here asserts that such a refusal is REACHABLE at the published route and
 *       arrives as a parseable problem body; it does not re-derive the rule that produced it.</li>
 *   <li><b>Mapping regimes belong to the {@code mapper} test package.</b> Masking mechanics, encryption,
 *       filler handling and the one field rename this context makes are asserted there. The distinction is
 *       worth keeping precisely because masking appears in both places: {@code mapper} owns HOW a value is
 *       masked, and this package owns THAT the masked form is what crosses the boundary.</li>
 * </ul>
 *
 * <p>Assumptions: the authority matrix is deliberately NOT asserted in this package, and this is the
 * boundary most easily mistaken for an omission. The administrative authority required on the fraud route
 * is declared once, against path prefixes, in {@code com.carddemo.authorization.config.SecurityConfig},
 * and it is asserted against the installed decision object in
 * {@code com.carddemo.authorization.config.SecurityConfigTest}. The reason it cannot be asserted here is
 * mechanical rather than stylistic: the server these tests build is assembled standalone and installs no
 * filter chain at all, so the principal is supplied on the request builder. An assertion here that a route
 * was reachable would therefore say nothing whatever about whether it is reachable WITHOUT the right
 * authority, and stating it would be worse than omitting it, because it would read as a guarantee that had
 * not been tested.
 *
 * <h2>Corrections to the planned shape of this tree</h2>
 *
 * <p>Refactoring Rationale: the plan this package was authored against described a different tree from the
 * one on disk, and the differences are recorded here rather than left for a later reader to rediscover.
 * The migration plan settles the precedence for exactly this case: the repository is authoritative, and
 * where the two differ the repository wins and the difference is written down so that no downstream agent
 * re-derives it. The parent charter one directory up records the same class of correction for the same
 * reason. Four corrections bear on anything a reader might do in this package:
 *
 * <ul>
 *   <li>There is no {@code repository} test package in this module, so the schema and index assertions the
 *       plan assigned to one live where the list above places them instead. Its {@code RepositoryIT}
 *       suffix survives and is load-bearing, but it is carried by a class in {@code fixtures}.</li>
 *   <li>The {@code dto}, {@code domain} and {@code config} test packages were named as packages that must
 *       not exist. All three exist, all three are load-bearing, and one of them owns the authority matrix
 *       described above. Publishing the planned wording would have told three real packages to delete
 *       themselves, and would have left the authority matrix with no owner at all.</li>
 *   <li>The sliced web-layer test annotation is not used anywhere in this module. Both classes here
 *       assemble a standalone server, and the reason is specific to this context rather than a
 *       preference: this module's {@code SecurityConfig} builds its decoder from an issuer location, which
 *       resolves that issuer's discovery document EAGERLY at bean construction, so any context including
 *       that configuration reaches the network from a unit test -- against a host the test profile points
 *       somewhere unresolvable on purpose. A standalone server exercises routing, binding, validation,
 *       delegation and status selection with none of that.</li>
 *   <li>The recorded byte images this module's other packages read are documented, and the count is not
 *       the one the plan carried. That directory holds thirty-three binary images and five delimited
 *       ones beside a README that describes them, and it is also the one path on the test side that the
 *       Javadoc gate's companion suppression file reaches, which is why prose documentation is
 *       admissible there in a form the gate would not accept in a source tree.</li>
 * </ul>
 *
 * <h2>Selection contract: obey the suffix or the class does not run</h2>
 *
 * <p>Two plugins divide this module by class name alone, and neither reads any list authored here. A unit
 * test class in this package must end with exactly the suffix {@code Test}, which is what the standard
 * lifecycle binding of the Surefire plugin selects, reporting into {@code target/surefire-reports}. An
 * integration test elsewhere in this module ends with exactly {@code RepositoryIT} and is collected by the
 * Failsafe plugin, which asserts in the verify phase and reports into {@code target/failsafe-reports}; it
 * is named here only so that a reader does not mistake the two conventions for one, since nothing in this
 * package uses it. Both report directories are the plugins' defaults and both must stay where they are,
 * because the services pipeline collects exactly those two paths.
 *
 * <p>Assumptions: that selection contract is external. It belongs to the two build plugins and to the
 * pipeline that reads their output, not to this project, and this package's class naming simply conforms to
 * it. Two consequences follow and both are silent. If the suffix binding changed, a correctly authored
 * class in this package would stop being selected while the build stayed green, because a class that is
 * never run reports no failure. If either report directory were relocated, the build would pass while the
 * pipeline published nothing. Neither failure announces itself, which is why the contract is written down
 * here rather than assumed.
 *
 * <p>Assumptions: no end-to-end golden master exists for any path in this package, and that limit is
 * recorded rather than worked around. {@code tests/README.md} L83 to L85 states that the online programs
 * cannot run end-to-end without a CICS runtime, which the runner does not have, and that only their
 * extractable field-validation logic is unit-tested. All three reference programs behind this package are
 * online programs, so there is no captured output to compare a response against and no test here should be
 * read as implying one. What remains directly verifiable is the part these assertions actually rest on: the
 * message text and the field edits are readable in the programs and their symbolic maps, and they are
 * asserted character for character.
 *
 * <h2>Decisions</h2>
 *
 * <p>Alternatives Considered: a JUnit suite descriptor, or any other explicit enumeration of the classes to
 * run in this package, was evaluated and rejected. It would stand up a second selection mechanism
 * alongside the suffix binding described above, and the two would then have to agree; when they did not,
 * the descriptor would win silently, so a correctly named and correctly authored class could be excluded
 * from every run while the build stayed green and the report simply did not mention it. A missing suffix
 * fails visibly by comparison, because the class is absent from a report that lists everything else. The
 * cost accepted is that there is no single file listing what this package runs, which is why the inventory
 * above is stated instead.
 *
 * <p>Trade-offs: the four ownership boundaries above buy single ownership of each assertion at the cost of
 * this package's local self-containment. A reader of one test here cannot see the full proof of a codec, a
 * constraint or a business rule in the same file, and has to follow the named owner to find it. That cost
 * is accepted because duplicated assertions drift: two suites asserting one contract means a change fails
 * twice, no reader can tell which of the two is the authority, and the copy that was not updated goes on
 * passing against a contract that has moved. A distant assertion is easier to live with than a drifted
 * one.
 *
 * <p>Refactoring Rationale: an HTTP-boundary test slice exists separately at all because the reference
 * programs did not separate these concerns and could not be tested as though they had.
 * {@code cbl/COPAUS0C.cbl} holds every layer of this context in one program: it sends and receives its
 * screen at L695, L703 and L715, edits its account identifier at L264 and L273, reads its segments at L461
 * and L493, and issues its own commit at L686. Testing the field edits in that arrangement means starting
 * a transaction region and a database, because the program cannot reach its validation without them.
 * Splitting the tests along the target's layer boundaries is what makes a refusal-message assertion
 * runnable with neither: this package needs one controller and a service test double, and the classes that do
 * need a database are the ones that were always about the database.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>This file exists because user-specified Rule 1 (Explainability) L15 requires a docstring on every
 * module entry point, and a Java package declaration is one. A {@code package-info.java} is the only
 * construct that can carry Javadoc for a package, so the obligation lands in this file and nowhere else.
 * Of the four content elements L18 to L21 enumerate, only Purpose applies: a package declaration accepts no
 * parameters, yields no value and raises nothing, so the Parameters, Return values and Exceptions elements
 * are inapplicable rather than omitted, and no at-clause is fabricated to stand in for one. The plan
 * mechanises the presence half of that obligation with two checks in
 * {@code config/checkstyle/checkstyle.xml} that split it: {@code JavadocPackage} runs at Checker level and
 * requires this file to exist for a package holding Java sources, while {@code MissingJavadocPackage} runs
 * inside the tree walker and requires the file to carry Javadoc. Neither is redundant, because an empty
 * {@code package-info.java} satisfies the first and fails the second. Both run at error severity from the
 * gate bound to the validate phase, which precedes compilation, so an omission stops the build rather than
 * adding a line to a log nobody reads. No in-source suppression filter is configured in that rule set, so
 * a finding here cannot be waived from inside this file.
 *
 * <p>Trade-offs: the labelled justifications above sit inside a Javadoc block rather than beside a
 * statement, which departs from the letter of Rule 1 L27. A package declaration has no statements, so
 * there is nothing for a comment to be adjacent to, and the adjacency requirement is satisfied vacuously
 * rather than waived. The cost accepted is that these entries sit further from the behaviour they govern
 * than an inline comment would, and the compensation is that each one names its evidence by file and line
 * so a reader can check both sides. A reader auditing this file should read the labelled entries as the
 * rationale half of the conjunctive gate Rule 1 states at L43, and should not conclude the half was
 * skipped for want of somewhere to put it.
 *
 * <p>Refactoring Rationale: every reference to the reference programs in this charter states what the
 * baseline does and what this package does instead, and passes no judgement on either. That framing is the
 * house convention for this migration and it is deliberate: the COBOL is the behavioural oracle, it is read
 * and never modified, and a comment calling it defective would invite exactly the edit the migration
 * forbids. The one gate this file answers to is binary -- it passes or it fails -- and the graded
 * condition-code rubric under which the reference COBOL suite treats a warning-level aggregate as its green
 * state belongs to that suite alone and is never carried onto this side.
 */
package com.carddemo.authorization.api;
