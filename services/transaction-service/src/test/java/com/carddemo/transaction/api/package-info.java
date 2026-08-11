/**
 * Web-edge tests of the transaction ledger, each standing one controller up alone over stubbed
 * collaborators and asserting only what crosses the HTTP boundary.
 *
 * <p>Purpose: this package holds the controller tests of transaction-service, the LEDGER bounded
 * context. A test here drives a request through one controller and asserts the status code, the response
 * body's shape, the per-field error array and the message text a refusal carries; it reaches no database,
 * no queue and no token issuer. The rules those controllers delegate to are asserted in the sibling
 * {@code service} package and the queries behind them in the sibling {@code repository} package, so a
 * failure raised here points at the edge and nowhere else.
 *
 * <p>Assumptions: a package declaration accepts no parameter, returns no value and raises nothing, so
 * this charter carries no parameter, return or exception at-clause. The inapplicability is stated rather
 * than left silent so a reader can tell it from an oversight, and no at-clause is invented to fill the
 * gap: {@code NonEmptyAtclauseDescription} is active in {@code config/checkstyle/checkstyle.xml}, so a
 * fabricated tag would be reported as empty rather than read as thorough.
 *
 * <h2>The three test classes in this directory</h2>
 *
 * <dl>
 *   <dt>{@code TransactionControllerTest}</dt>
 *   <dd>Covers the THREE endpoints of {@code TransactionController}: the cursor-keyed browse over the
 *       ledger, the single-record read beneath {@code /{transactionId}}, and the capture that creates a
 *       record. The browse assertions carry the keyset contract, so a request names a cursor rather than
 *       an ordinal position and whether a further batch exists is read from the envelope rather than
 *       inferred from the item count.</dd>
 *
 *   <dt>{@code BillPaymentControllerTest}</dt>
 *   <dd>Covers the ONE endpoint of {@code BillPaymentController}, the balance-affecting payment.
 *       Assumptions: the re-key confirmation the baseline screen performs is a client concern, so what is
 *       asserted here is the endpoint's own behaviour -- that a submission is validated, that a refusal
 *       carries both its per-field error array and the reference's own sentence, and that money crosses
 *       the boundary as a JSON string rather than as a JSON number.</dd>
 *
 *   <dt>{@code TransactionApiRoutingContractTest}</dt>
 *   <dd>Holds the published contract and the delivered routes to each other in both directions, so a
 *       published operation with no route fails it and a delivered route with no published operation
 *       fails it too. It is named here because a charter that states a closed set while a file sits
 *       outside that set reads as governance over a directory it does not actually govern.</dd>
 * </dl>
 *
 * <h2>The four baseline programs these tests are answerable to</h2>
 *
 * <p>Every assertion in this package traces to one of the four online programs below. They are reference
 * material: read, cited by path and line, never modified. The transaction identifiers and screen names
 * are quoted from the {@code Online Components} table under the {@code Application Inventory} heading of
 * the repository root {@code README.md}, at lines 298 to 302, so that a name used in a test method and a
 * name an operator already knows are the same name:
 *
 * <ul>
 *   <li>{@code CT00} / {@code COTRN00} / {@code app/cbl/COTRN00C.cbl} -- "Transaction List", line
 *       298</li>
 *   <li>{@code CT01} / {@code COTRN01} / {@code app/cbl/COTRN01C.cbl} -- "Transaction View", line
 *       299</li>
 *   <li>{@code CT02} / {@code COTRN02} / {@code app/cbl/COTRN02C.cbl} -- "Transaction Add", line
 *       300</li>
 *   <li>{@code CB00} / {@code COBIL00} / {@code app/cbl/COBIL00C.cbl} -- "Bill Payment", line 302</li>
 * </ul>
 *
 * <p>Assumptions: the screen name at line 299 is "Transaction View". The plausible mis-citation is
 * "Transaction Detail", which reads naturally beside a single-record read and appears nowhere in that
 * table; a test method named for a screen the inventory does not list cannot be found by an operator
 * searching for the screen they are debugging.
 *
 * <p>Alternatives Considered: citing those five rows by line number alone. Rejected because the heading
 * named above is the durable half of the citation and the numbers are the perishable half. The root
 * {@code README.md} is one of only three pre-existing files this migration modifies at all, and the
 * migration section it gained moved this table down by twenty lines, so a bare line number here ages by
 * exactly as much as that section grows. A reader who finds a number stale should search the heading and
 * the transaction identifier, neither of which moves.
 *
 * <p>One nearby program is deliberately absent from that list, and the absence is recorded so that nobody
 * later completes the set: {@code CR00} / {@code CORPT00} / {@code CORPT00C}, "Transaction Reports" at
 * {@code README.md} line 301, sits between {@code CT02} and {@code CB00} in that same table and so reads
 * as a fifth transaction of this context. It belongs to the reporting context and is tested in that
 * module. No test in this package asserts report content, and no controller behind it serves a report
 * route.
 *
 * <h2>How the shared kernel's cross-cutting beans reach a test here</h2>
 *
 * <p>Assumptions: this is the fact most easily got wrong in this package, because getting it wrong yields
 * a PASSING test that asserts a body the service will never send. In a running service the money codec,
 * the single error advice, the correlation filter and the common meter tags all arrive unbidden: the
 * shared kernel names {@code com.carddemo.common.CardDemoCommonAutoConfiguration} in the registration
 * file {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, held
 * under {@code services/common-lib/src/main/resources}, and that registration is what bridges the
 * component scan rooted at {@code com.carddemo.transaction} and the shared components under
 * {@code com.carddemo.common}. The production {@code com.carddemo.transaction.config.OpenApiConfig}
 * records that arrangement and declares none of those beans itself.
 *
 * <p>Assumptions: none of that reaches a test in this package, because no test here starts an application
 * context. Each controller test builds its entry point through {@code MockMvcBuilders.standaloneSetup}
 * and therefore has to register by hand the two pieces its assertions depend on -- the message converter
 * carrying {@code MoneyModule}, without which money renders as a JSON number instead of a string, and
 * {@code GlobalExceptionHandler}, without which a refusal renders in the framework's own default shape
 * instead of the shared problem envelope with its per-field error array. A class that omits either one
 * still runs green while asserting the wrong body, which is why the requirement is recorded once here
 * rather than rediscovered per class.
 *
 * <p>Alternatives Considered: importing the production configuration into a Spring slice so those
 * registrations arrive instead of being written out. Rejected on two grounds that hold independently of
 * each other. A context for this module resolves a token issuer and a database, neither of which a build
 * agent provides, which is the same constraint that makes the routing-contract test read mapping
 * annotations rather than a started context. And that configuration is not a carrier of those beans, so
 * importing it would supply none of them; each shared registration is guarded by a missing-bean
 * condition, and a local declaration written to force the issue REPLACES the guarded one silently,
 * dropping the conditions the guard carried.
 *
 * <p>Trade-offs: hand-registration repeats those two lines in each setup method instead of inheriting
 * them from one place, and a further controller test added here would repeat them again. That was
 * accepted because the wiring then sits at the top of the class whose assertions depend on it, whereas a
 * shared base class would put the reason a body has its shape one file away from the assertion about that
 * shape.
 *
 * <h2>No golden master covers any program in this package</h2>
 *
 * <p>Assumptions: NO GOLDEN MASTER EXISTS for anything this package tests, and none is claimed. The
 * repository records at {@code tests/README.md} lines 83 to 85 that the online {@code CO*} CICS programs
 * cannot be run end to end without a CICS runtime, which the runner does not have, so only their
 * extractable field-validation logic is unit-tested. All four programs listed above are {@code CO*}
 * programs, so that exemption covers every one of them without remainder.
 *
 * <p>These tests are strictly additive to the COBOL parity oracle under {@code tests/}, which is never
 * modified, never re-pinned and never replaced. No assertion in this package may be justified by pointing
 * at a golden file, and nothing under {@code tests/golden/} or {@code tests/fixtures/} may be created or
 * touched from here. Parity for these four screens rests instead on validation logic transcribed from the
 * COBOL paragraphs and asserted in the sibling {@code service} package.
 *
 * <p>Trade-offs: the graded return-code rubric the oracle reports through, in which a warn level is the
 * documented green state, carries no meaning for this package. A Maven and JUnit outcome is binary, and
 * no grading, tolerance or return-code arithmetic may be introduced here to imitate the oracle.
 *
 * <h2>The layering boundary a test here respects</h2>
 *
 * <p>A test in this package exercises the REST edge and nothing beneath it, mirroring the production
 * decomposition rather than merely resembling it: a controller validates its input and delegates,
 * business rules live in {@code com.carddemo.transaction.service}, data access in
 * {@code com.carddemo.transaction.repository}, and copybook representation concerns -- declared field
 * widths, sign overpunch, masking, the renamed baseline field names -- in
 * {@code com.carddemo.transaction.mapper} and nowhere else. The production {@code api} package imports no
 * type from {@code ..domain..}, {@code ..repository..} or {@code ..mapper..}.
 *
 * <p>Assumptions: that boundary is held by an executable test rather than by this paragraph. The test is
 * {@code TransactionLayeringRulesTest} in the sibling {@code architecture} test package, and it must never
 * be authored as a second class named {@code LayeringRulesTest}; the charter cited below owns that naming
 * ruling and the reason a literal simple name is load-bearing there.
 *
 * <h2>Why this charter exists as a file of its own</h2>
 *
 * <p>Refactoring Rationale: what this replaces is a directory of test classes carrying no stated
 * contract, and that state fails a build outright rather than merely reading thin. Two checks in
 * {@code config/checkstyle/checkstyle.xml} interlock over it. {@code JavadocPackage} is declared at the
 * top level of that configuration rather than inside its syntax-tree container, which makes it a file-set
 * check firing for any directory holding a Java source the audit processed, so the FILE has to exist.
 * {@code MissingJavadocPackage} is declared inside that container, so the file has to CARRY Javadoc. An
 * empty charter satisfies the first and fails the second. This directory is inside the audit because
 * {@code config/checkstyle/suppressions.xml} exempts only generated sources and the fixture resources,
 * never {@code src/test/java}. Above the linter, the user-specified Explainability rule attaches its
 * docstring obligation at line 15 to every module entry point, and in Java that entry point is the
 * package declaration, which no other file can document.
 *
 * <p>Refactoring Rationale: the second thing wrong with holding no charter is duplication. Without one,
 * the hand-registration requirement and the no-golden-master boundary stated above would each have to be
 * restated in every class in this directory, and a fact restated per class drifts per class. Stating it
 * once is the discipline the repository already applies to record layouts at {@code tests/README.md} lines
 * 540 to 542, which resolve a layout through a single include path instead of copying it, applied here to
 * a package's contract instead of to a copybook.
 *
 * <p>Assumptions: no in-source escape hatch exists for either check. That configuration wires no
 * suppression filter of any kind, so neither a magic comment nor an annotation can exempt this file, and
 * the gate runs at the Maven {@code validate} phase ahead of compilation on every local build rather than
 * only in the pipeline. Weakening the gate is therefore not available as a way of satisfying it.
 *
 * <h2>What this charter deliberately does not restate</h2>
 *
 * <p>The subtree charter at
 * {@code services/transaction-service/src/test/java/com/carddemo/transaction/package-info.java} already
 * owns the runner split that class-name suffixes carry, the ruling on the layering class's name, the count
 * of charter files this subtree admits and the additive relationship to the COBOL oracle, and this file
 * cites it rather than repeating any of it, because two statements of one convention drift apart and a
 * reader then cannot tell which of them is current.
 *
 * <h2>What a green run of this package proves, and what it does not</h2>
 *
 * <p>Trade-offs: because every collaborator is a stub, these tests verify the HTTP contract -- status
 * codes, body shape, validation wiring and the message text a refusal carries -- and not one line of
 * business behaviour. The transcribed rules are pinned by the
 * sibling {@code service} package and the data-access behaviour by the {@code RepositoryIT} classes
 * against a real database container. The compromise is deliberate: standing a full context up for every
 * endpoint would cost the two dependencies named above and would still not isolate the edge, since a
 * broken query would then redden a test whose name claims a status code is wrong and send a reader to the
 * controller instead of to the repository.
 *
 * <p>Trade-offs: a stubbed collaborator also cannot catch a drift between what a controller expects of a
 * service and what that service returns. That gap is closed inside this directory rather than left open,
 * by the routing-contract test holding the published operations and the delivered routes to each other.
 */
package com.carddemo.transaction.api;
