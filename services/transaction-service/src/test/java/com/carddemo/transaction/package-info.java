/**
 * Root test package of the transaction-service module, holding the tests that
 * pin the LEDGER bounded context to the behaviour of its COBOL baseline.
 *
 * <h2>Target contract, not a directory listing</h2>
 *
 * <p>Assumptions: every inventory, class name and count in this charter states the subtree's
 * <b>target contract</b> as the migration plan assigns it -- the eight subpackages it owns and the
 * class names reserved in each. It is read against the plan rather than against a listing of the
 * directory beside it, and it says as much about which test class may <em>not</em> be added to a
 * subpackage as about which belongs there.
 *
 * <p>What is present today, so that the distinction above is checkable rather
 * than merely declared: all eight subpackages exist, each carrying its charter,
 * and six of them carry test classes -- {@code architecture} holds
 * {@code TransactionLayeringRulesTest}, {@code MoneyPathGateProofTest} and
 * {@code KeysetPaginationGateProofTest}; {@code domain} holds
 * {@code FixedWidthMappingTest}, {@code FeedRowIdentityTest} and
 * {@code OccurrenceIdentityTest}; {@code dto} holds
 * {@code TransactionApiContractTest} and {@code TransactionAddRequestTest};
 * {@code fixtures} holds {@code TransactionFixtureContractTest};
 * {@code mapper} holds {@code BillPaymentMappingTest}; and {@code repository}
 * holds {@code TransactionRepositoryIT}. That is eleven test classes. The
 * {@code api} and {@code service} subpackages hold their charters and no test
 * class yet, so every class named for them below is planned.
 *
 * <p>Refactoring Rationale: this paragraph has now been wrong three times, in the
 * same direction each time, which is why it is written as a verifiable enumeration
 * rather than a summary. It first stated that the directory held this charter and
 * nothing else and that none of the subpackages existed. It was then corrected to
 * "four of the seven subpackages exist" naming two test classes, and that in turn
 * went stale as the architecture, dto and repository suites landed and as the
 * {@code api} and {@code mapper} charters were authored. Its third revision read
 * "all seven subpackages ... that is seven test classes", and went stale the same
 * way once the {@code fixtures} consumer, the two further {@code domain} identity
 * tests and the {@code mapper} suite landed. A state sentence that goes stale is
 * worse than no state sentence, because a reader who checks it against the file
 * system and finds it wrong has no way to tell which of the remaining claims are
 * also stale -- and here the staleness had spread, because the count canon further
 * down was still reasoning from the superseded figure. Every figure in this file
 * is now stated against one listing of the tree.
 *
 * <p>Alternatives Considered: withholding this charter until the tests it
 * governs exist. Rejected, because this file is what the authors of those tests
 * work from -- which test belongs in which subpackage, which class name is
 * reserved, what the closed set is -- so writing it last would leave the
 * subtree with no stated contract during exactly the interval in which one is
 * needed. The cost of authoring it first is that its inventory reads as present
 * tense unless the distinction is declared, which is what the paragraph above
 * is for; that sentence is the single place a reader has to look to tell a
 * target from a measurement.
 *
 * <p>Alternatives Considered: deriving the inventory below from the directory
 * instead of from the plan, which would make the paragraph above unnecessary.
 * Rejected, because a charter that describes whatever happens to be present
 * cannot say what is RESERVED, and a reserved class name is precisely what stops
 * a second test of the same behaviour being written under a third name in a
 * fourth subpackage. Stating the closed set costs a charter that has to be
 * revised when the contract itself changes, and buys a boundary a reviewer can
 * enforce against a proposed addition. The two paragraphs therefore do different
 * work: the inventory below is the plan, and the measurement above is how a
 * reader tells how much of that plan has landed.
 *
 * <p><b>Purpose.</b> This package roots the test tree that holds the migrated
 * transaction ledger to the behaviour of the COBOL it was transcribed from. It
 * mirrors the production package namespace exactly, declaring the same
 * {@code com.carddemo.transaction} that the main tree declares, so a test sits
 * in the same package as the type it exercises and can reach that type's
 * package-private surface without widening the surface itself. The main-tree
 * charter at
 * {@code services/transaction-service/src/main/java/com/carddemo/transaction/package-info.java}
 * is the established source of this module's documentation conventions and of
 * the ledger's data contracts; this charter governs the test subtree alone and
 * cites that file rather than restating it, because two statements of one
 * convention drift apart and a reader then cannot tell which one is current.
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A package
 * declaration accepts no parameters, returns no value and raises nothing, so
 * this charter carries no parameter, return or exception at-clause. The
 * inapplicability is declared rather than left silent, because the
 * Explainability rule's line 39 forbids a docstring that omits parameters,
 * return values or purpose, and a reader has to be able to tell a declared
 * inapplicability from an oversight.
 *
 * <p>Assumptions: fabricating those at-clauses would not merely add noise.
 * Javadoc has no parameter, return or exception concept for a package, and
 * {@code NonEmptyAtclauseDescription} is active in the ruleset, so an invented
 * at-clause would either be discarded or reported as empty. The rule enumerates
 * four docstring elements at its lines 18 to 21, and exactly one of the four has
 * a subject in this compilation unit; this paragraph and the one above it
 * account for the other three.
 *
 * <h2>Baseline provenance: the four programs these tests are answerable to</h2>
 *
 * <p>Every assertion beneath this package traces to one of the four online
 * programs below. They are reference material, read but never modified. The
 * transaction identifiers and screen names are quoted from the transaction
 * inventory in the repository root {@code README.md}, lines 278 to 282, so that
 * the names used here and the names an operator already knows are the same
 * names:
 *
 * <ul>
 *   <li>{@code CT00} / {@code app/cbl/COTRN00C.cbl}, 699 lines --
 *       "Transaction List", the paged browse screen</li>
 *   <li>{@code CT01} / {@code app/cbl/COTRN01C.cbl}, 330 lines --
 *       "Transaction View", the single-record detail screen</li>
 *   <li>{@code CT02} / {@code app/cbl/COTRN02C.cbl}, 783 lines --
 *       "Transaction Add", the capture screen</li>
 *   <li>{@code CB00} / {@code app/cbl/COBIL00C.cbl}, 572 lines --
 *       "Bill Payment", the balance-affecting payment screen</li>
 * </ul>
 *
 * <p>Assumptions: the screen name at line 279 is "Transaction View". The
 * plausible mis-citation is "Transaction Detail", which reads naturally beside
 * a detail endpoint and appears nowhere in the inventory. A test method named
 * for the wrong screen cannot be found by an operator searching for the screen
 * they are debugging, so the inventory name is the one used throughout this
 * subtree.
 *
 * <p>Two nearby programs are deliberately absent from that list, and each
 * absence is recorded because each one is easy to assume into this subtree and
 * would put a test in the wrong module:
 *
 * <ul>
 *   <li>{@code CR00} / {@code CORPT00C}, "Transaction Reports" at
 *       {@code README.md} line 281, sits between {@code CT02} and {@code CB00}
 *       in the inventory and so reads as a fifth transaction of this context.
 *       It belongs to the reporting context and is tested there. No test here
 *       asserts report content.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl}, the nightly posting program, 731 lines,
 *       writes three of the four tables this context owns and is nonetheless
 *       the batch context's program. It is read here for the schema contract
 *       only: for the reject record layout and the posted-transaction field
 *       set. The two modules agree through the physical {@code ledger} schema,
 *       meaning column names, types and decimal scale, and never through code,
 *       so no test here imports a batch type and no test here asserts a posting
 *       job's behaviour.</li>
 * </ul>
 *
 * <h2>The eight subpackages, and what each one holds</h2>
 *
 * <p>Every test class in this subtree sits in one of eight leaf subpackages, and
 * the subpackage a test belongs to is decided by the kind of test it is rather
 * than by the program it covers:
 *
 * <ul>
 *   <li><b>{@code dto}</b> -- the published-contract tests, which compare a
 *       request or response record with the independently authored OpenAPI
 *       document that describes it, together with the constraint tests on a
 *       request record's own declarations. Two classes:
 *       {@code TransactionApiContractTest} and
 *       {@code TransactionAddRequestTest}. A test here needs neither a
 *       container nor a test double, because both sides of the comparison are
 *       declarations.</li>
 *   <li><b>{@code domain}</b> -- the entity-mapping, identity and
 *       diagnostic-rendering tests. Three classes:
 *       {@code FixedWidthMappingTest}, covering the explicit fixed-length
 *       binding on every {@code CHAR} column and the values an entity rendering
 *       withholds; {@code FeedRowIdentityTest}, covering the generated
 *       ingestion-sequence identity the feed table carries instead of a
 *       copybook key; and {@code OccurrenceIdentityTest}, covering the
 *       composite identity of a per-occurrence row. All three cover properties
 *       that regress without failing any other assertion in the module.</li>
 *   <li><b>{@code api}</b> -- the web-layer slice tests, each standing up the
 *       REST layer alone with its collaborators replaced. Two classes:
 *       {@code TransactionControllerTest}, covering the three transaction
 *       endpoints, and {@code BillPaymentControllerTest}, covering the single
 *       bill-payment endpoint.</li>
 *   <li><b>{@code service}</b> -- the business-rule unit tests, one class per
 *       migrated program, so the closed set is exactly four:
 *       {@code TransactionViewServiceTest}, {@code TransactionListServiceTest},
 *       {@code TransactionAddServiceTest} and {@code BillPaymentServiceTest}.
 *       These are where the validation logic transcribed from the COBOL
 *       paragraphs is actually asserted.</li>
 *   <li><b>{@code repository}</b> -- the data-access integration tests, named
 *       with the {@code RepositoryIT} suffix and backed by a real PostgreSQL
 *       container rather than an in-memory substitute, because the behaviour
 *       under test includes the keyset access paths and a deliberately
 *       non-unique index that an in-memory engine cannot express.</li>
 *   <li><b>{@code mapper}</b> -- the anti-corruption-layer tests, named with
 *       the {@code MapperTest} suffix. They are the only tests here that may
 *       reason about copybook representation concerns, because {@code mapper}
 *       is the only production package permitted to carry them.</li>
 *   <li><b>{@code fixtures}</b> -- the executable consumer of this module's
 *       scenario fixture directories, named with the
 *       {@code FixtureContractTest} suffix. One class:
 *       {@code TransactionFixtureContractTest}. It is its own kind of test
 *       because its subject is the fixture MATERIAL rather than any Java type:
 *       it decodes each record file through the registered production layouts
 *       and asserts that the bytes still carry the boundary pair each scenario
 *       README claims for them, which is the one regression no test of a class
 *       can see.</li>
 *   <li><b>{@code architecture}</b> -- this module's own layering gate,
 *       together with the two tests whose whole purpose is to demonstrate that
 *       the gate can fail. A gate nobody has watched fail is indistinguishable
 *       from a gate with no subjects, which is the failure those two tests
 *       exist to rule out.</li>
 * </ul>
 *
 * <p>Assumptions: the four-class set in {@code service} follows from the four
 * migrated programs and not from the production class list, which carries no
 * class per screen by construction -- a read-only view and a paged browse can
 * share a collaborator while still needing their transcribed rules asserted
 * separately. Sizing that package from the production classes instead would
 * leave one of the four screens with no unit test of its own.
 *
 * <p>Refactoring Rationale: the roster read five subpackages before {@code dto}
 * and {@code domain} were added to it, and it read five while {@code dto} already
 * held a test class -- and it then read seven while {@code fixtures} already held
 * one, so twice over the closed set excluded a package that existed, which makes
 * a closed set worse than an open one: it reads as governance while the governed
 * thing sits outside it. Each of the three additions is its own kind of
 * test rather than a variant of an existing one. A contract test compares two
 * declarations and could not go in {@code api}, which stands up a web layer, nor
 * in {@code service}, which asserts a transcribed rule. A mapping-and-rendering
 * test asserts what a persistence annotation binds and what an object discloses,
 * which is neither a query -- so not {@code repository}, whose tests pay for a
 * container -- nor a copybook representation concern, so not {@code mapper}. A
 * fixture-contract test has no Java subject at all: it asserts the bytes of a
 * resource, so it could not go in {@code domain}, which asserts a mapping, nor in
 * {@code dto}, which compares two declarations. The house form is the same in
 * four sibling modules, which is the second reason it is admitted here as a
 * package rather than folded into one of the other seven.
 *
 * <h2>The Surefire and Failsafe split is carried by class names alone</h2>
 *
 * <p>Which runner executes a test, and therefore at which point in the build a
 * failure surfaces, is decided entirely by the suffix on its class name:
 *
 * <ul>
 *   <li>A class ending {@code Test} is collected by Surefire and runs at the
 *       {@code test} phase. It needs no declaration, because the standard
 *       lifecycle already binds it.</li>
 *   <li>A class ending {@code IT}, which in this subtree means the
 *       {@code RepositoryIT} classes, is collected by Failsafe and runs at
 *       {@code integration-test} with its result asserted at {@code verify}, so
 *       a failure surfaces as a build failure rather than as a silently skipped
 *       assertion.</li>
 * </ul>
 *
 * <p>Both plugins are configured by {@code services/pom.xml} and neither is
 * declared by this module, whose sole build plugin is
 * {@code spring-boot-maven-plugin}. The {@code RepositoryIT} suffix already
 * matches Failsafe's default include pattern, so no include configuration is
 * declared and none should be added.
 *
 * <p>Assumptions: both runners are left at their DEFAULT report directories,
 * {@code target/surefire-reports} and {@code target/failsafe-reports}, and the
 * continuous integration pipeline collects from exactly those two paths under
 * {@code services/*}. Relocating, renaming or redirecting either one would make
 * the build green while the pipeline published nothing, which is the one failure
 * mode here that looks like success. No test in this subtree may set a reports
 * directory, and no module-level configuration should be added that does.
 *
 * <p>Trade-offs: the {@code IT} suffix distinguishes the Failsafe half inside
 * the standard {@code src/test/java} source root rather than a second root such
 * as {@code src/it} or {@code src/integration-test}. The cost accepted is that a
 * naming convention is doing structural work, so a container-backed test
 * misnamed {@code Test} runs in the wrong phase and one misnamed {@code IT}
 * appears to pass by never running at all. What it buys is that one source root
 * feeds both runners and both keep writing to the default report directories the
 * pipeline reads; adding a second source root would need its own compile and
 * report wiring, and getting that wiring wrong strands the very directories the
 * pipeline collects from.
 *
 * <h2>The layering gate: one inherited class, one additive class, one name</h2>
 *
 * <p>The layering invariants of the migrated decomposition -- no AWS SDK or web
 * type inside a {@code ..domain..} package, no service importing another
 * service's domain package, and no binary floating-point type anywhere in the
 * money path -- are asserted as an executable test rather than described as a
 * convention, and this module runs them against its own compiled classes.
 *
 * <p>Assumptions: that shared class is authored once, at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 * and it reaches this module through three cooperating declarations rather than
 * through inheritance, which never moves a dependency's test classes onto a
 * consumer's test classpath. The shared kernel binds a {@code test-jar}
 * execution that publishes its architecture package as a classified artifact;
 * this module declares that artifact at test scope; and
 * {@code services/pom.xml} configures Surefire's {@code dependenciesToScan} so
 * the runner looks inside it and runs what it finds on this module's own test
 * classpath, which is what puts this module's classes in front of the rules.
 * Remove any one of the three and the gate silently stops running while every
 * build stays green. This module also declares the ArchUnit engine itself at
 * test scope, because test scope is not transitive and a discovered rule class
 * without the engine fails to load rather than failing an assertion.
 *
 * <p>The class authored in {@code architecture} here is therefore ADDITIVE to
 * that shared class and not a replacement for it. It is named
 * {@code TransactionLayeringRulesTest}, and it must never be a second class
 * named {@code LayeringRulesTest}.
 *
 * <p>Assumptions: that naming is load-bearing against a literal string, not a
 * matter of taste. The {@code architecture-rules} Surefire execution in
 * {@code services/pom.xml} selects the shared class by the pattern
 * {@code **}{@code /LayeringRulesTest.java}, a simple name written as a
 * literal. A second class of that simple name in this module would be selected
 * by that same pattern, so the execution meant to run one shared class would
 * run two different classes whose reports are told apart only by the module they
 * ran in, and a reader could no longer tell which class produced a given result.
 * A distinct simple name keeps the inherited gate and this module's own gate
 * separable in the runner, in the report and in the log.
 *
 * <p>Alternatives Considered: expressing these same package boundaries through
 * Checkstyle's {@code ImportControl} module, which is already available given
 * the documentation gate this build already runs and would need no new
 * dependency. It is deliberately absent from
 * {@code config/checkstyle/checkstyle.xml} so that layering has exactly one
 * owner. Configuring it would stand up a second engine enforcing an overlapping
 * half of one constraint, and a reader auditing a boundary could then no longer
 * tell which of the two owned it; two enforcement points for one invariant drift
 * apart, and the drift is silent because each one keeps passing on its own
 * terms. Because the single owner is a test rather than a written convention, it
 * cannot rot: a boundary crossed makes it fail.
 *
 * <h2>The count canon, and the charters that deliberately do not exist</h2>
 *
 * <p>This subtree holds nine package charter files: this one, and one in each of
 * the eight subpackages named above. All nine exist today. The figure is recorded
 * so that a reader can tell a charter that is missing from a charter that was
 * never intended, and so that the negative boundary below rests on arithmetic
 * that can be re-checked rather than on an argument that has to be re-made.
 *
 * <p>Refactoring Rationale: this paragraph previously said five of the eight
 * existed and that three were still to arrive, and the sentence that follows it
 * cited the canon as admitting "exactly six charters". Those two figures were
 * never reconcilable with each other -- eight named, five present, six admitted --
 * and the eight is the one the enumeration supports, being this charter plus the
 * seven subpackages. The subtree has since reached that target: the {@code api}
 * and {@code mapper} charters were authored, so eight were present and the canon
 * admitted eight. It then went stale once more, in the same direction, when the
 * {@code fixtures} subpackage landed with a charter of its own: nine files over
 * eight subpackages, which is the figure stated above. The arithmetic the
 * negative boundary rests on is stated once, in this paragraph, and the boundary
 * now cites it rather than a second figure -- which is also why the boundary is
 * written as a reference to this paragraph instead of repeating a number that
 * would then have to be found and changed twice.
 *
 * <p>Assumptions: NO charter file exists at
 * {@code services/transaction-service/src/test/java}, at
 * {@code services/transaction-service/src/test/java/com}, or at
 * {@code services/transaction-service/src/test/java/com/carddemo}, and the
 * absence is a decision rather than an oversight. It rests on three independent
 * grounds, and their mutual independence is what makes it safe: any one settles
 * it alone. First the mechanism -- the ruleset's charter-presence check sits at
 * the top level of the configuration rather than inside its syntax-tree
 * container, which makes it a file-set check that fires only for a directory
 * containing a {@code .java} file the audit processed, and the audit is
 * restricted to that extension; each of those three levels holds only a
 * subdirectory, so no violation is reachable and a file placed there would
 * satisfy no gate. Second the count canon above, which admits exactly nine
 * charters and which a tenth would break. Third the rule itself, independently
 * of any linter: it attaches the docstring obligation to a function, a class or
 * a module entry point, and a directory holding only a subdirectory has none of
 * the three.
 *
 * <p>Assumptions: the tree corroborates that convention where it has already
 * been built, and the stable property to check is the absence of a Java file
 * rather than a count of folders, which changes as the plan lands its
 * artifacts. In this repository's shared kernel,
 * {@code services/common-lib/src/test/java/com/carddemo/common} holds
 * subdirectories and no Java file of its own, and
 * {@code services/auth-service/src/test/java/com/carddemo} holds one
 * subdirectory and no Java file of its own. Neither carries a charter, and every
 * charter that does exist across the modules sits in a directory that also holds
 * Java sources.
 *
 * <p>Trade-offs: the cost is that a reader browsing generated documentation
 * meets three package pages carrying no description. That was accepted over
 * authoring three files whose whole content would be a sentence pointing at this
 * one, because a charter that defers has to be kept in step with the file it
 * defers to and it invites the next author to add a fourth. Recording the
 * boundary here rather than leaving it silent is required in its own right: the
 * rule's line 40 forbids leaving a non-obvious choice undocumented where a
 * reasonable alternative exists, and mirroring a charter at every path level for
 * symmetry is exactly such an alternative.
 *
 * <p>Assumptions: the nine-file count holds only while no test class sits
 * directly in this directory, and all test classes belong to the eight leaf
 * subpackages. Were a test class ever placed here, nothing about this file would
 * change: it is already present and already carries Javadoc, which is the whole
 * reason it is authored unconditionally rather than on discovering that
 * something in this directory needs it.
 *
 * <h2>Strictly additive to the COBOL parity oracle, which is a different suite</h2>
 *
 * <p>Two test suites exist in this repository and they are kept textually
 * distinct in every sentence of this subtree, because conflating them is what
 * leads someone to edit the one that must not be edited. The COBOL parity
 * ORACLE lives under {@code tests/} and comprises its three layers,
 * {@code tests/cobol-unit/}, {@code tests/integration/} and {@code tests/e2e/},
 * together with {@code tests/fixtures/}, {@code tests/golden/},
 * {@code tests/helpers/} and {@code tests/mocks/}. Each is written with its
 * {@code tests/} prefix so that every one of the seven resolves on its own,
 * rather than depending on a reader carrying the parent directory over from the
 * preceding clause. The Java suite is the one rooted at this package, under
 * {@code services/transaction-service/src/test}. The oracle is never modified,
 * never re-pinned and never replaced, and the tests in this subtree are strictly
 * additive to it.
 *
 * <p>Assumptions: NO GOLDEN MASTER EXISTS for this module's paths, and none is
 * claimed. The repository records at {@code tests/README.md} lines 83 to 85 that
 * the online {@code CO*} programs cannot be run end to end without a CICS
 * runtime, which the runner does not have, so only their extractable
 * field-validation logic is unit-tested. All four programs migrated into this
 * module are {@code CO*} programs, so the exemption covers every one of them.
 * Parity here therefore rests on two things instead: validation logic
 * transcribed faithfully from the COBOL paragraphs, and the copybook record
 * contracts the main-tree charter records. No test in this subtree may create,
 * regenerate or otherwise touch anything under {@code tests/golden/} or
 * {@code tests/fixtures/}, and no assertion here may be justified by pointing at
 * a golden file.
 *
 * <p>Assumptions: the batch posting program that shares this schema does have
 * golden-master coverage, and it belongs to the batch context rather than to
 * this module. Its coverage must not be read as coverage of these four screens,
 * which is precisely the mistake a shared schema invites: the tables are the
 * same tables, and the programs that write them are not the same programs.
 *
 * <h2>Citation discipline: the baseline is the specification, and it is read-only</h2>
 *
 * <p>Everything under {@code app/} is the behavioural SPECIFICATION for this
 * migration. It is reference material: read it, cite it by path and line, and
 * never modify it. A test that needed the baseline changed in order to pass
 * would be asserting something the specification does not say.
 *
 * <p>Assumptions: the framing of any difference between the two is constrained,
 * because the wrong verb turns a documented divergence into a claim that
 * reference source was altered. Never write that anything in {@code app/} was
 * fixed, corrected, patched, remediated or repaired. The one permitted framing
 * is that the baseline does X, the Java implements Y, and the divergence is
 * documented. That wording survives review because each of its three clauses is
 * independently checkable, whereas "corrected" asserts a change to a file this
 * migration is forbidden to touch.
 *
 * <p>Assumptions: every path and line number cited anywhere in this subtree is
 * verified on disk before it is written, never recalled and never inferred from a
 * neighbouring file. An invented citation is exactly the unsupported claim the
 * Explainability rule forbids, and it is worse in a test than elsewhere: a test
 * comment naming a line that does not say what the comment claims will be trusted
 * by the next reader precisely because it looks specific.
 *
 * <p>Assumptions: file extension case differs by directory in the baseline and a
 * citation with the wrong case does not resolve on a case-sensitive filesystem.
 * {@code app/cbl} holds 29 files with the lower-case extension and 2 with the
 * upper-case one, those two being {@code CBSTM03A.CBL} and
 * {@code CBSTM03B.CBL}, for 31 in total. {@code app/cpy} holds 29 lower-case
 * and 1 upper-case, that one being {@code COSTM01.CPY}, for 30 in total. All 17
 * files in {@code app/cpy-bms} carry the UPPER-CASE extension, with no
 * lower-case exception at all, so a lower-case path into that directory never
 * resolves. Repository-wide totals are larger than the per-directory ones
 * because the extension trees and the oracle's own COBOL contribute to them, so
 * a repository-wide file count is never a count of migrated programs.
 *
 * <p>Assumptions: two identifier namespaces collide by number and are kept
 * textually distinct throughout this subtree. The user-specified rule is
 * Explainability and is cited by its line numbers. The migration plan's
 * transformation rules are numbered T1 to T10 and are always written with the T.
 * "Rule 1" never means "T1", and writing one for the other points a reader at a
 * document that says something else entirely.
 *
 * <h2>Single-sourcing: a test consumes a contract, it never re-declares one</h2>
 *
 * <p>The baseline compiles every program against a single copybook include path,
 * so a record layout has one definition and cannot drift between two programs.
 * The repository imposes that same discipline on its own COBOL tests at
 * {@code tests/README.md} lines 540 to 542, which resolve record layouts through
 * {@code cobc -I app/cpy} and never duplicate a layout.
 *
 * <p>Assumptions: the Java analogy is exact, and it is the reason the shared
 * kernel exists. What {@code -I app/cpy} together with {@code COPY CVTRA06Y.} is
 * to a COBOL program, a Maven dependency on the shared kernel together with
 * {@code import com.carddemo.common.money.Money;} is to a class in this subtree.
 *
 * <p>Refactoring Rationale: the consequence is a rule for the whole subtree. A
 * test must NOT re-declare a record layout, a field width, an offset or a money
 * semantic that the domain entity, the mapper or the fixture material already
 * owns; it CONSUMES them. Codec vectors, money arithmetic and its wire form, the
 * keyset page envelope, the timestamp form and the field-validation flag model
 * are all taken from {@code com.carddemo.common} and are never re-implemented per
 * test. A local copy of any of them would reintroduce exactly the drift the
 * include path forecloses, and the drift would be silent, because both copies
 * would go on compiling and the stale copy would go on passing its own
 * assertions.
 *
 * <h2>The build gate here is binary; the graded rubric belongs to the oracle</h2>
 *
 * <p>Assumptions: the COBOL parity oracle grades its outcome on a mainframe
 * condition-code rubric of 0, 2, 4, 8 and 16 in which a warning-level result is
 * its current green state. That tolerance exists for one reason: two baseline
 * programs carry an unfixable record-key defect in immutable reference source
 * that is out of scope for this migration. The rubric belongs exclusively to that
 * oracle under {@code tests/}.
 *
 * <p>Assumptions: a Maven, JUnit, Checkstyle or ArchUnit gate is BINARY -- it
 * passes or it fails. No graded tolerance, no warning tier and no arithmetic on
 * a return code may be introduced anywhere in this subtree; no result of this
 * module's build may be described as warning-level green; and this module's gate
 * is never wired into the oracle's own workflow, whose pipeline is reference
 * material like the suite it runs. Importing the graded rubric here would mean a
 * failing assertion could be reported as an acceptable outcome, which is the one
 * thing a parity test must never be able to do.
 *
 * <p>Assumptions: a return code of 4 does have a distinct and legitimate meaning
 * INSIDE the specification, and the two uses must not be confused.
 * {@code app/cbl/CBTRN02C.cbl} lines 229 and 230 test whether the reject count
 * is greater than zero and move 4 into the program's return code when it is.
 * That is a behaviour to assert -- a test may require exactly that value from
 * that condition -- and not a build-gate policy to adopt.
 *
 * <h2>The documentation contract this subtree is held to</h2>
 *
 * <p>One user-specified rule governs this migration: Explainability. It binds
 * this file on three independent grounds, cited together because each survives if
 * another is contested. Its scope clause at line 15 attaches the docstring
 * obligation to a module entry point, which in Java is the package declaration,
 * and the rule states no exemption for tests anywhere in its text. The inherited
 * documentation gate sets {@code includeTestSourceDirectory} to true so that
 * {@code src/test/java} is audited, and {@code config/checkstyle/suppressions.xml}
 * declines to suppress it, recording that the tests are where the rules
 * transcribed from the COBOL are actually asserted and so are the classes a
 * reviewer most needs explained. And the repository's own house convention at
 * {@code tests/README.md} section 12 binds every new test, fixture builder,
 * helper, mock and runner routine to a docstring together with a rationale naming
 * one of four categories, and calls itself a hard review gate.
 *
 * <p>Assumptions: this file is authored unconditionally, without waiting on any
 * of those three grounds being settled, because the two outcomes are not
 * symmetric. A charter that CARRIES Javadoc can never produce a violation, since
 * the content check fires only on one that LACKS it; a charter that is absent can
 * produce one, from the file-set check that requires it to exist in any directory
 * holding an audited compilation unit. The cheap action is therefore the safe one
 * in both worlds, which is why its cost was never weighed against the
 * probability that the gate reaches here.
 *
 * <p>Assumptions: the four rationale labels, their one permitted written form and
 * the inline comment idiom this Java tree uses are established by the main-tree
 * charter cited above and stated in full at
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}. They are cited rather than
 * restated, because a second copy of a convention is a second thing to keep in
 * step. Where that written standard and the linter configuration disagree,
 * Rule 1 and the AAP remain authoritative and both implementations are
 * corrected upward to match them. A green linter result cannot narrow the
 * governing requirement.
 *
 * <p>Assumptions: the labels are TYPED in this subtree and never copied out of
 * {@code tests/README.md}. That file carries a non-breaking hyphen 106 times
 * across 77 lines, and its own list of the four categories at line 548 renders
 * the compromise label with that character rather than with an ordinary hyphen. A
 * non-breaking hyphen is indistinguishable from an ordinary one on screen while
 * behaving differently in a search, so a label copied from there becomes a token
 * that a search for the label fails to find -- which makes a rationale that was
 * genuinely written read as absent to the audit looking for it.
 *
 * <p>Assumptions: the rule's validation gate at line 43 is conjunctive, failing
 * work that is missing EITHER a docstring or a rationale, so a flawless charter
 * carrying no reasoning does not satisfy it any more than reasoning without a
 * charter would. Because this compilation unit holds no statements to annotate,
 * the rationale is carried inside this Javadoc as labelled sentences rather than
 * as adjacent line comments; the twin-comment idiom the tree uses for command
 * blocks has no place inside a Javadoc block. Note also that the gate's own
 * triad names purpose, parameters and return values and does not mention
 * exceptions, so no exception at-clause anywhere in this subtree may be justified
 * by citing that gate.
 *
 * <p>Assumptions: there is no in-code escape from the gate. None of the three
 * comment-driven or annotation-driven suppression filters is enabled in the
 * ruleset, so neither a marker comment nor an annotation suppresses anything; a
 * suppression has to be a durable entry in the companion file, whose whole
 * charter reaches generated sources and test fixture material only. Test source
 * under {@code src/test/java} is not within that charter and no suppression for it
 * may be requested. Relaxing the gate is a breach of the rule rather than a
 * build-configuration choice.
 *
 * <p>Assumptions: the gate is local rather than merely a pipeline step, being
 * bound to the build's {@code validate} phase ahead of compilation, so a missing
 * or empty charter breaks the build on a developer's own machine. Three checks
 * bear on this file by name: the file-set check requiring a charter to exist in
 * this directory, the syntax-tree check requiring that charter to carry Javadoc,
 * and the summary check requiring a first sentence and rejecting placeholder
 * markers and the rule's own two examples of a vague rationale. A charter reduced
 * to a bare package statement would satisfy the first and fail the second, which
 * is why this one is prose.
 *
 * <h2>Authoring notes for this file</h2>
 *
 * <p>Trade-offs: this file is pure ASCII with no byte-order mark, and its
 * summary sentence ends with a period because the summary check's sentence
 * terminator is left at its default. The alternative was to reproduce the
 * typographic punctuation the migration prose uses, which would read closer to
 * that prose; ASCII was chosen because it makes the non-breaking-hyphen failure
 * described above unreachable in this file by construction rather than by care.
 * The cost is plainer punctuation.
 *
 * <p>Trade-offs: this file holds one Javadoc block and one package declaration
 * and nothing else -- no type, no annotation, no import, no field and no line
 * comment. It carries no authorship, version or release-marker at-clause either,
 * because the ruleset omits the whole Javadoc-formatting family that would ask
 * for them, and the version control history answers those three questions more
 * reliably than a comment maintained by hand. Prose is wrapped at 80 columns to
 * match the sibling charters in this tree, even though no line-length check is
 * enabled; the few lines that exceed it are inline code spans holding paths that
 * cannot be broken, because a line break inside one would insert this comment's
 * margin into the rendered path.
 */
package com.carddemo.transaction;
