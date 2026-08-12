/**
 * Holds the service-layer unit tests of the LEDGER bounded context, which carry
 * the parity evidence for the four migrated CardDemo online transaction
 * programs.
 *
 * <h2>Purpose, and what an assertion in this package answers for</h2>
 *
 * <p><b>Purpose.</b> This package is where the business rules transcribed from
 * those four programs are actually asserted. A test here takes one production
 * service class, stands it up with its collaborators replaced by mocks, and
 * holds it to what the baseline paragraph it was transcribed from does:
 * validation order, the message a rejected field selects, amount arithmetic,
 * identifier derivation, the boundary a write commits inside, and the assembly
 * of the paged list envelope. It asserts nothing beyond that. A query belongs
 * to the sibling {@code repository} tests, which pay for a real database engine
 * in order to run; a published wire shape belongs to the sibling {@code dto}
 * tests; a copybook representation concern belongs to the sibling
 * {@code mapper} tests; a web-layer status code belongs to the sibling
 * {@code api} tests; and a layering boundary belongs to the sibling
 * {@code architecture} tests.
 *
 * <p>Assumptions: this package mirrors the production package namespace
 * exactly, declaring the same {@code com.carddemo.transaction.service} that the
 * main tree declares. That is what lets a test reach a package-private member
 * of the class it exercises without the class widening its own surface to be
 * testable, which would make the test's convenience a permanent part of the
 * production API.
 *
 * <h2>Parameters, return values and exceptions are declared inapplicable</h2>
 *
 * <p>A package declaration accepts no parameter, returns no value and raises
 * nothing, so this charter carries no parameter, return or exception at-clause.
 * The inapplicability is declared rather than left silent, because the
 * Explainability rule's line 39 forbids a docstring that omits parameters,
 * return values or purpose, and a reader has to be able to tell a declared
 * inapplicability from an oversight.
 *
 * <p>Assumptions: inventing those at-clauses would be worse than verbose.
 * Javadoc has no parameter, return or exception concept for a package, and
 * {@code NonEmptyAtclauseDescription} is active in the shared rule set, so an
 * invented at-clause would either be discarded or reported as empty. Note also
 * that the rule's own validation gate at line 43 names purpose, parameters and
 * return values and does NOT mention exceptions; the exception obligation that
 * binds the methods in the sibling test classes rests on the rule's line 21
 * with its "where applicable" clause, on the house convention at
 * {@code tests/README.md} lines 544 to 549, and on the
 * {@code validateThrows} setting of {@code JavadocMethod} instead. Citing the
 * gate for it would attribute the obligation to a sentence that does not carry
 * it.
 *
 * <h2>The closed inventory: eight files here, seven of them tests</h2>
 *
 * <p>This directory holds exactly eight Java files and no subdirectory. Seven are
 * test classes and the eighth is this charter. Each line count below was
 * counted in the file itself rather than carried over from a summary, and
 * each transaction identifier and screen name is quoted from the transaction
 * inventory in the repository root {@code README.md}:
 *
 * <pre>
 * this directory: 8 java files = 7 tests + 1 charter
 * </pre>
 *
 * <p>Refactoring Rationale: the marker line above was added because "exactly six
 * Java files" was a closed-set claim that nothing checked, and a further test
 * authored without an entry below would have made it silently false. The line and
 * the class names under it are compared with this directory on every build by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java},
 * so the inventory cannot drift from the directory without failing.</p>
 *
 * <p>Refactoring Rationale: the three figures were raised from six, five and one
 * when {@code BillPaymentServiceTest} landed beside the five classes already
 * enumerated. The marker is a MEASUREMENT of the directory, so a new member
 * obliges this line to move rather than obliging the member to justify itself; the
 * check named above reported the stale claim as a failed assertion on the file
 * count, which is the drift it exists to catch working as intended.</p>
 *
 * <p>⚠️ Refactoring Rationale: they were raised again, to eight and seven, when
 * {@code BillPaymentUnitOfWorkIT} landed -- the first member of this directory that
 * is an INTEGRATION test rather than a unit test, and therefore the first whose name
 * ends in {@code IT} rather than {@code Test}. Two consequences are worth stating
 * because neither is visible from the count. The suffix is what selects the runner:
 * Surefire matches {@code *Test} and Failsafe matches {@code *IT}, so a class holding
 * a container must carry the second suffix or it runs in the wrong phase and starts a
 * container during the unit build. And this directory is no longer container-free, so
 * a build host that cannot start one now fails a class here rather than only in the
 * sibling {@code repository} package -- which is stated so the failure is
 * recognisable rather than surprising.</p>
 *
 * <ul>
 *   <li>{@code TransactionViewServiceTest} pins
 *       {@code app/cbl/COTRN01C.cbl}, 330 lines, the "Transaction View"
 *       single-record detail screen, transaction {@code CT01}.</li>
 *   <li>{@code TransactionListServiceTest} pins
 *       {@code app/cbl/COTRN00C.cbl}, 699 lines, the "Transaction List" paged
 *       browse screen, transaction {@code CT00}. It holds the four properties
 *       that decide whether a keyset browse is faithful -- the strictly-past
 *       forward step, the strictly-before backward step with its display
 *       reversal, the surplus eleventh row that answers forward availability
 *       and is then discarded, and the mid-browse insert that is neither
 *       skipped nor served twice -- together with the five verbatim boundary
 *       strings and the selector that chooses between them.</li>
 *   <li>{@code TransactionListServiceCursorBindingTest} pins the one
 *       property of that same browse which a token holder can attack: whose
 *       cursor a page's boundary tokens open for. Alternatives Considered:
 *       folding these cases into the class above, which is where they began.
 *       They were moved out because the sealer they exercise is the one
 *       collaborator held REAL, so a failure here means the cursor seal has
 *       weakened while a failure there means a paging paragraph has been
 *       mistranscribed; with both in one class the class name no longer
 *       distinguishes those two very different diagnoses.</li>
 *   <li>{@code TransactionAddServiceTest} pins
 *       {@code app/cbl/COTRN02C.cbl}, 783 lines, the "Transaction Add" capture
 *       screen, transaction {@code CT02}.</li>
 *   <li>{@code BillPaymentEvaluationOrderTest} pins
 *       {@code app/cbl/COBIL00C.cbl}, 572 lines, the "Bill Payment"
 *       balance-affecting payment screen, transaction {@code CB00}.
 *       Assumptions: it is named for the property it holds rather than for the
 *       class it exercises, because the property is an ORDER -- the reference
 *       settles the account identifier before it reaches its confirmation
 *       evaluation, so a submission deficient in both is answered for the
 *       identifier and not the confirmation. Its name deliberately does not
 *       promise whole-class coverage, which is the obligation the entry below
 *       takes on instead.</li>
 *   <li>{@code BillPaymentServiceTest} pins the same
 *       {@code app/cbl/COBIL00C.cbl} whole, transaction {@code CB00}, the only
 *       balance-affecting write this context migrates. It holds the properties
 *       that survive the write rather than the order that precedes it: the eight
 *       hardcoded literals of lines 220 to 229 against the two live data moves at
 *       lines 224 and 225, the write-then-compute-then-update sequence of lines
 *       233 to 235 against the nightly posting job's opposite order, the
 *       subtraction FORM of line 234 as distinct from its result, the
 *       pre-payment balance that is also the amount paid, the single instant
 *       lines 231 and 232 move to both timestamp members with its
 *       zero-microsecond rendering, the maximum-key derivation of lines 212 to
 *       217 that is a probe and not a page, and the read-for-update lock of line
 *       351 that -- unlike the detail screen's at line 275 of
 *       {@code app/cbl/COTRN01C.cbl} -- is consumed by a real rewrite at line 379
 *       and so is load-bearing.
 *       Assumptions: it does not duplicate the entry above. That class holds an
 *       evaluation ORDER and needs only enough of a converter to reach it; this
 *       one holds what is WRITTEN, which is why it keeps the converter real and
 *       reads the composed row back off the write. A failure in one
 *       therefore localises differently from a failure in the other, which is the
 *       same division the two paged-browse entries above draw.</li>
 *   <li>{@code BillPaymentUnitOfWorkIT} pins the ONE property of that same
 *       {@code app/cbl/COBIL00C.cbl} that neither class above can hold: that the
 *       ledger row at line 233 and the balance reduction at line 235 COMMIT
 *       together, as the one implicit CICS syncpoint makes them.
 *       Assumptions: it is an integration test rather than a unit test because the
 *       property is about commit boundaries, and a mocked collaborator does not
 *       participate in a transaction -- the two classes above observe the writes
 *       being ISSUED, which is a different claim and was satisfied by an
 *       implementation that committed the balance change on a separate HTTP
 *       connection. It runs against a real PostgreSQL container, supplies the
 *       account-owned foreign table through a Testcontainers init script under
 *       {@code src/test/resources/db/testharness}, and opens its own explicit
 *       transactions so that every observation is of COMMITTED state.
 *       Assumptions: it also holds the read-for-update asymmetry, since that too is
 *       an engine behaviour: the paying turn escalates to a {@code ROW SHARE} lock on
 *       the account relation and the reporting turn does not, which is what keeps one
 *       operator's unconfirmed preview from blocking another operator's payment for
 *       the whole of a request.</li>
 * </ul>
 *
 * <p>Assumptions: that list is a measurement of the directory as well as the
 * closed set this charter admits, and the two now agree. Each entry names a
 * file that exists, and the property each holds is stated at the entry
 * rather than in a blanket sentence, so a reader meeting a green run can
 * tell what the run proved from what it did not reach.
 *
 * <p>Refactoring Rationale: the list above is a MEASUREMENT of the directory and
 * not a target contract, and the distinction is load-bearing. A charter has to
 * exist before a sibling class in the same directory can clear the
 * {@code validate} phase, so this file necessarily lands ahead of the classes it
 * inventories -- but a charter that keeps telling a reader to distrust its own
 * inventory once those classes exist inverts its purpose, and a charter naming a
 * class no file carries is the one kind of error a reader cannot correct from the
 * charter alone. Each entry is therefore stated as a name that resolves.
 *
 * <p>Assumptions: the four production classes named in the next section are
 * likewise present, and their own charter at
 * {@code services/transaction-service/src/main/java/com/carddemo/transaction/service/package-info.java}
 * remains the authority for their status. It is cited rather than restated,
 * because two statements of one contract drift apart and a reader then
 * cannot tell which is current.
 *
 * <p>Assumptions: the inventory citation is given at the line numbers the
 * repository root {@code README.md} carries as the tree stands, which are lines
 * 298 to 302, and the five rows appear there in inventory order rather than in
 * the order listed above. The pristine baseline held the same five rows at
 * lines 278 to 282, and the migration section this plan adds to that file moved
 * them down by twenty lines. Both figures are recorded because the sibling
 * charters were authored against the earlier one, so a reader meeting two
 * different citations of one table reads a shift with a known cause rather than
 * an error in either file. The rows themselves are unchanged, so the verifiable
 * claim is the row content and the line numbers are the convenience.
 *
 * <p>Assumptions: the screen name at line 299 is "Transaction View". The
 * plausible mis-citation is "Transaction Detail", which reads naturally beside
 * a detail endpoint and appears nowhere in the inventory. A test method named
 * for a screen that the inventory does not name cannot be found by an operator
 * searching for the screen they are debugging, so the inventory name is the one
 * used throughout this package.
 *
 * <p>Assumptions: a fifth online program sits inside that same block of
 * inventory rows and is deliberately NOT tested here. {@code CR00} /
 * {@code CORPT00C}, "Transaction Reports" at {@code README.md} line 301, falls
 * between {@code CT02} and {@code CB00} in the table and so reads as a fifth
 * transaction of this context. It belongs to reporting-service and is tested
 * there. No test in this package asserts report content, and adding one would
 * put the assertion in a module that does not own the code it describes.
 *
 * <h2>The four units under test, and no interface standing for any of them</h2>
 *
 * <p>Every test class here exercises one annotated service class of the
 * production package of the same name, and between them the six cover four:
 * {@code TransactionViewService}, {@code TransactionListService},
 * {@code TransactionAddService} and {@code BillPaymentService}. Six classes
 * cover four units because the paged browse is covered by two and the payment
 * screen by two -- one for the order in which it evaluates a submission, one for
 * what it writes once it has. None of the
 * four has an interface declared for it, so a test instantiates the class
 * itself rather than a stand-in for it, and a reader looking for the code
 * under test has one place to look.
 *
 * <p>Assumptions: the production package does declare one interface, the
 * outbound port {@code AccountContextClient}, alongside its HTTP implementation
 * {@code RestAccountContextClient}. Neither is a unit under test here: the port
 * is a COLLABORATOR of two of the four services and is mocked, and its
 * implementation is exercised where a real HTTP exchange can be stood up rather
 * than in a Mockito test. The distinction is stated because a reader meeting
 * {@code AccountContextClient} in that directory has to be able to tell an
 * intended outbound port from a layering breach, and the sentence above --
 * which says the four UNITS have no interface -- does not answer that on its
 * own.
 *
 * <p>Assumptions: that shape is settled by the production charter at
 * {@code services/transaction-service/src/main/java/com/carddemo/transaction/service/package-info.java},
 * which records why the four were neither merged into one service nor split
 * into an interface and an implementation, and which is the authority for the
 * paragraph-to-method pairing each test cites. It is cited rather than
 * restated: two statements of one contract drift apart, and a reader then
 * cannot tell which is current.
 *
 * <p>Trade-offs: this package has NO shared abstract base class, no
 * {@code AbstractServiceTest}, no test fixture builder or object mother, no
 * helper or utility class, and no context configuration class. The six tests
 * deliberately share nothing. The compromise accepted is that the annotation
 * and mock declarations repeat across six files. What that buys is twofold.
 * Each file stays readable on its own, so a maintainer debugging one screen
 * never has to read a second file to learn what the setup did. And the
 * directory carries no internal compile dependency, so a change made for one
 * screen cannot alter what another screen's test asserts -- which matters here
 * more than usual, because two of these programs transcribe paragraphs whose
 * texts the baseline permits to diverge, and a shared setup would make one edit
 * silently change two screens. A base class would additionally fall under the
 * documentation obligation itself, since the house convention at
 * {@code tests/README.md} line 544 names a fixture builder and a helper
 * explicitly, so it would add work without removing any.
 *
 * <h2>What a test here mocks, and what it never reaches for</h2>
 *
 * <p>These are Mockito unit tests rather than tests of a running application
 * context, and the mocks stand in for production collaborators declared under
 * {@code src/main}:
 *
 * <ul>
 *   <li>{@code com.carddemo.transaction.repository.TransactionRepository},
 *       which extends {@code JpaRepository<Transaction, String>}.</li>
 *   <li>{@code com.carddemo.transaction.mapper.TransactionMapper}, for the
 *       three transaction screens.</li>
 *   <li>{@code com.carddemo.transaction.mapper.BillPaymentMapper}, for the
 *       payment screen.</li>
 *   <li>{@code com.carddemo.transaction.service.AccountContextClient}, the
 *       outbound port through which the capture screen resolves a card
 *       cross-reference and the payment screen reads an account balance.
 *       Assumptions: it is mocked rather than stood up, because what it reaches
 *       is a different bounded context over HTTP; a test that resolved it for
 *       real would fail for a reason belonging to that context and not to the
 *       paragraph under test.</li>
 * </ul>
 *
 * <p>Assumptions: {@code com.carddemo.common.web.CursorToken} is NOT in that
 * list and is used REAL. It is a per-call parameter of the paged listing rather
 * than an injected collaborator, and the property the browse test asserts -- that
 * a boundary token opens for the subject it was issued to and for no other -- is
 * a property of that type's own authentication code. A mocked sealer would
 * record that a method was called and would pass just as readily against a
 * binding that omitted the subject, which is the defect the case exists to
 * catch, so mocking it would invert the test's purpose. Its key is a literal
 * declared inside the test class; nothing here resolves a key from
 * configuration, which is why no module publishes a sealer bean.
 *
 * <p>Assumptions: the repository's identifier type is {@code String} and not a
 * numeric type. The entity's identity attribute is the {@code String} member
 * {@code tranId}, bound to a constant-length character column, because the
 * baseline transaction identifier is a sixteen-character display field rather
 * than an integer. A mock stubbed against a numeric identifier compiles only
 * until the generic argument is checked, and a test that guesses the wrong
 * identifier type ends up asserting a lookup the production code cannot
 * perform.
 *
 * <p>Assumptions: this directory has NO dependency on a sibling test package.
 * Nothing here is declared in, extends, or reads a type from the {@code api},
 * {@code repository}, {@code mapper} or {@code architecture} test packages, and
 * nothing in those packages is a precondition for running these six classes.
 * The six are runnable as a selection on their own. Introducing such a
 * dependency would couple two kinds of test whose whole reason for sitting in
 * separate packages is that they fail for different reasons and are read by
 * different people.
 *
 * <h2>Single-sourcing: a test consumes a contract, never re-declares one</h2>
 *
 * <p>The baseline compiles every program against a single copybook include
 * path, so a record layout has one definition and cannot drift between two
 * programs. The repository imposes that same discipline on its own COBOL tests
 * at {@code tests/README.md} lines 540 to 542, which resolve record layouts
 * through {@code cobc -I app/cpy} and, in that file's own words, "never
 * duplicate a layout; keep it single-sourced from app/cpy/". The quotation is
 * transliterated to plain characters here for the reason given further down.
 *
 * <p>Refactoring Rationale: the Java analogue of that include path is the
 * shared kernel, and the consequence is a rule for this whole package. A test
 * here does NOT re-declare a record layout, a declared field width, a byte
 * position or a money semantic that the domain entity, the mapper or the
 * fixture material already owns; it CONSUMES them. {@code Money},
 * {@code MoneyModule}, {@code PageResponse}, {@code ApiError},
 * {@code FieldValidationFlag}, {@code TimestampFormatter} and
 * {@code DateEditValidator} are taken from {@code com.carddemo.common} and are
 * never re-implemented per test. A local copy of any of them would reintroduce
 * exactly the drift the include path forecloses, and the drift would be silent,
 * because both copies would go on compiling and the stale copy would go on
 * passing its own assertions.
 *
 * <p>Assumptions: money is exact decimal at every hop and is never carried
 * through a binary approximation type, so an amount asserted here is compared
 * as an exact decimal at a declared scale. An assertion written against a
 * binary approximation passes on most inputs and fails on the cents that
 * matter, which makes it the kind of test that certifies a defect. The
 * prohibition is not left to this sentence: it is also an executable rule in
 * the layering gate the sibling {@code architecture} package owns.
 *
 * <h2>Surefire runs these, and the report directory stays at its default</h2>
 *
 * <p>Every class here carries the {@code Test} suffix, so Surefire collects it
 * and it runs at the Maven {@code test} phase. No declaration is needed for
 * that, because the standard lifecycle already binds it, and none should be
 * added.
 *
 * <p>Assumptions: the runner is left at its DEFAULT report directory,
 * {@code services/transaction-service/target/surefire-reports/}, and the
 * continuous integration pipeline collects from exactly that path. Relocating,
 * renaming or redirecting it would leave the build green while the pipeline
 * published nothing, which is the one failure mode here that looks like
 * success. No test in this package may set a reports directory, and no
 * module-level configuration should be added that does.
 *
 * <p>Assumptions: the Failsafe half of this module's test tree is the
 * {@code RepositoryIT} class in the sibling {@code repository} package, which
 * runs at {@code integration-test} with its result asserted at {@code verify}.
 * It is not in this package and no class here is named for that runner. There
 * is deliberately no second source root such as {@code src/it}: one source root
 * feeds both runners, and adding a second would need its own compile and report
 * wiring whose failure mode is a directory the pipeline collects nothing from.
 *
 * <h2>NO golden master exists for these four programs</h2>
 *
 * <p>Assumptions: no golden master covers any path in this package, and none is
 * claimed. The repository records at {@code tests/README.md} lines 83 to 85
 * that the online {@code CO*} programs cannot be run end to end without a CICS
 * runtime, which the runner does not have, so only their extractable
 * field-validation logic is unit-tested. All four programs this package pins
 * are {@code CO*} programs, so that exemption covers every one of them.
 *
 * <p>Refactoring Rationale: parity here therefore rests on two things instead,
 * and stating what they are is what keeps an assertion from resting on nothing.
 * The first is validation logic transcribed faithfully from the COBOL
 * paragraphs, cited paragraph by paragraph in each test. The second is the
 * copybook record contracts, which the production charters record. No assertion
 * in this package may be justified by pointing at a golden file, and no test
 * here may create, regenerate or otherwise touch anything under
 * {@code tests/golden/} or {@code tests/fixtures/}.
 *
 * <p>Assumptions: the nightly posting program {@code app/cbl/CBTRN02C.cbl},
 * which writes three of the tables this context owns, DOES have golden-master
 * coverage, and it belongs to the batch context rather than to this module.
 * That coverage must not be read as coverage of these four screens, which is
 * precisely the mistake a shared schema invites: the tables are the same
 * tables, and the programs that write them are not the same programs.
 *
 * <h2>Strictly additive to the COBOL parity oracle, a separate suite</h2>
 *
 * <p>Two test suites exist in this repository and they are kept textually
 * distinct in every sentence here, because conflating them is what leads
 * someone to edit the one that must not be edited. The COBOL parity ORACLE
 * lives under {@code tests/} and comprises {@code tests/cobol-unit/},
 * {@code tests/integration/}, {@code tests/e2e/}, {@code tests/fixtures/},
 * {@code tests/golden/}, {@code tests/helpers/} and {@code tests/mocks/}. Each
 * is written with its {@code tests/} prefix so that every one of the seven
 * resolves on its own. The Java suite is the one rooted at
 * {@code services/transaction-service/src/test}.
 *
 * <p>Assumptions: the oracle is reference material. It is never modified, never
 * re-pinned and never replaced, and the six tests in this package are strictly
 * additive to it. Nothing here reaches into its helpers, meaning none of
 * {@code tests/helpers/cobol_runner.py},
 * {@code tests/helpers/golden_compare.py},
 * {@code tests/helpers/vsam_loader.py} or
 * {@code tests/helpers/load_indexed.sh}, because a Java unit test that shelled
 * out to a COBOL harness would make this module's result depend on a compiler
 * and a dialect flag that the oracle owns and that this module has no business
 * pinning.
 *
 * <h2>This gate is binary; the graded rubric belongs to the oracle</h2>
 *
 * <p>Assumptions: the oracle grades its outcome on a mainframe condition-code
 * rubric of 0, 2, 4, 8 and 16 in which a warning-level result is its current
 * green state. That tolerance exists because two baseline programs carry a
 * record-key defect in immutable reference source that is out of scope for this
 * migration, and the rubric belongs exclusively to that oracle under
 * {@code tests/}.
 *
 * <p>Assumptions: a Maven, JUnit, Checkstyle or ArchUnit gate is BINARY -- it
 * passes or it fails. No graded tolerance, no warning tier and no arithmetic on
 * a return code may be introduced in this package; no result of this module's
 * build may be described as warning-level green; and this module's gate is
 * never wired into the oracle's own workflow, whose pipeline is reference
 * material like the suite it runs. Importing the graded rubric here would mean
 * a failing assertion could be reported as an acceptable outcome, which is the
 * one thing a parity test must never be able to do.
 *
 * <p>Assumptions: a return code of 4 does have a distinct and legitimate
 * meaning INSIDE the specification, and the two uses must not be confused.
 * {@code app/cbl/CBTRN02C.cbl} line 229 tests whether the reject count is
 * greater than zero and line 230 moves 4 into the program's return code when it
 * is. That is a behaviour a test may require -- exactly that value from exactly
 * that condition -- and not a build-gate policy to adopt.
 *
 * <h2>The documentation gate reaches this directory, and has no bypass</h2>
 *
 * <p>Assumptions: this directory is NOT suppressed. The companion file
 * {@code config/checkstyle/suppressions.xml} declares exactly two entries, one
 * for sources a build plugin emits under a module's build output directory and
 * one for test fixture material under each module's own
 * {@code src/test/resources/fixtures/} directory. Neither reaches
 * {@code src/test/java}, and that file's own header records the refusal
 * directly, on the ground that {@code includeTestSourceDirectory} is set true
 * in {@code services/pom.xml} precisely so that tests ARE audited. All seven
 * files in this directory are therefore swept in full, and no reader should
 * assume test code is exempt.
 *
 * <p>Assumptions: two checks bear on this file and both must pass. The
 * file-set check {@code JavadocPackage} requires a charter to EXIST in any
 * directory holding an audited compilation unit, which is why this file is
 * authored before the six test classes rather than after them -- without it
 * they could not clear the {@code validate} phase. The syntax-tree check
 * {@code MissingJavadocPackage} requires that charter to CARRY Javadoc. A
 * charter reduced to a bare package statement satisfies the first and fails the
 * second, which is why this one is prose. {@code SummaryJavadoc} additionally
 * requires a first sentence terminated by a period and rejects placeholder
 * markers together with the rule's own two examples of a vague rationale.
 *
 * <p>Assumptions: there is no in-code escape. None of the comment-driven or
 * annotation-driven suppression filters is enabled in the shared rule set, so
 * neither a marker comment nor an annotation suppresses anything here; a
 * suppression has to be a durable entry in
 * {@code config/checkstyle/suppressions.xml}, whose charter does not extend to
 * this tree. Relaxing the gate -- by skipping it, by lowering its threshold, by
 * letting its step continue on error, or by declining to fail on violation --
 * is a breach of the rule rather than a build-configuration choice.
 *
 * <h2>The rationale label canon, and why it diverges from the siblings</h2>
 *
 * <p>Alternatives Considered: every rationale in this file is tagged with
 * one of four labels written plural, unparenthesised, colon-terminated, free
 * of emphasis markup and spelled with the ordinary hyphen character. That
 * form is taken from the Explainability rule's own lines 31 to 34 and is set
 * out character for character in {@code docs/CODE_DOCUMENTATION_STANDARD.md}.
 * Two in-repository dialects were evaluated as the alternative and both were
 * rejected, and they are named here so that nobody later revises these files
 * toward them:
 *
 * <ul>
 *   <li>The parenthesised dialect at {@code tests/helpers/record_codec.py} line
 *       130, which opens its rationale with the category in brackets after a
 *       marker word.</li>
 *   <li>The singular dialect at {@code .github/workflows/tests.yml} lines 19,
 *       23 and 28, which tags its design-decision bullets with the
 *       singular spellings.</li>
 * </ul>
 *
 * <p>Assumptions: the ground for rejecting both is mechanical rather than
 * aesthetic. These labels are searched for as literal strings before they are
 * read by a person, because no linter parses prose across the seven languages
 * this migration spans, so one spelling makes that search complete and several
 * spellings make it silently partial. A rationale a search cannot find is a
 * rationale a review cannot count. Both of those files are reference material
 * and are not retyped, so the two trees do read differently; the plural is
 * chosen because it is the form the rule itself uses and therefore the form its
 * validation gate is audited against. The forms are never mixed inside one
 * file.
 *
 * <p>Assumptions: the labels here are TYPED and are never copied out of
 * {@code tests/README.md}. That file carries a non-breaking hyphen 106 times
 * across 77 lines, and its own list of the four categories at line 548 renders
 * the compromise label with that character rather than with an ordinary hyphen.
 * A non-breaking hyphen is indistinguishable from an ordinary one on screen
 * while behaving differently in a search, so a label copied from there becomes
 * a token that a search for the label fails to find -- which makes a rationale
 * that was genuinely written read as absent to the audit looking for it. The
 * same hazard is why the quotation from lines 540 to 542 further up is
 * transliterated rather than pasted: the word it quotes carries that character
 * in the source.
 *
 * <p>Assumptions: the twin-comment idiom the house uses for shell and
 * configuration blocks, measured at {@code tests/README.md} lines 267 and 270,
 * has no place inside a Javadoc block. Because this compilation unit holds no
 * statements to annotate, every rationale here is carried as a labelled
 * sentence in this Javadoc instead of as an adjacent line comment. The rule's
 * validation gate at line 43 is conjunctive, failing work missing EITHER a
 * docstring or a rationale, so a flawless charter carrying no reasoning would
 * satisfy it no better than reasoning without a charter.
 *
 * <h2>The charter canon, and what this file cites rather than restates</h2>
 *
 * <p>Assumptions: the canon is settled by the parent test charter at
 * {@code services/transaction-service/src/test/java/com/carddemo/transaction/package-info.java},
 * which admits one charter at the subtree root and one in each leaf subpackage.
 * Its current enumeration names seven leaf subpackages -- {@code dto},
 * {@code domain}, {@code api}, {@code service}, {@code repository},
 * {@code mapper} and {@code architecture} -- for eight charters in all. This is
 * the {@code service} one, and all eight now exist: this file was the sixth to
 * be authored, after the root and the {@code dto}, {@code domain},
 * {@code repository} and {@code architecture} charters, and the {@code api} and
 * {@code mapper} charters have since arrived with their packages. The figure is
 * recorded so that a reader can tell a charter that is missing from one that
 * was never intended.
 *
 * <p>Assumptions: {@code dto} and {@code domain} are in that enumeration because
 * each is a kind of test that fits none of the others, and the parent charter is
 * the authority for the set's extent. The enumeration is repeated here rather than
 * only in the parent because a closed set that excludes a package which exists
 * reads as governance while the governed thing sits outside it, so a reader
 * comparing this list against the directory is the intended check.
 *
 * <p>Assumptions: NO charter exists at
 * {@code services/transaction-service/src/test/java}, at
 * {@code services/transaction-service/src/test/java/com}, or at
 * {@code services/transaction-service/src/test/java/com/carddemo}, and the
 * absence is a decision. Each of those three directories holds only a
 * subdirectory and no Java file of its own, so the file-set check that requires
 * a charter cannot reach them and a file placed there would satisfy no gate;
 * the count above admits no ninth charter; and the rule attaches its obligation
 * to a function, a class or a module entry point, none of which a directory
 * holding only a subdirectory has.
 *
 * <p>Assumptions: three documents are the established sources this file cites
 * instead of restating, and each is cited because a second copy of a convention
 * is a second thing to keep in step. The parent test charter above governs the
 * test subtree as a whole, including the runner split and the citation
 * discipline. The main-tree charter at
 * {@code services/transaction-service/src/main/java/com/carddemo/transaction/package-info.java}
 * is this module's established source of documentation convention and of the
 * ledger's data contracts. And {@code docs/CODE_DOCUMENTATION_STANDARD.md}
 * states the label canon, the comment idiom and the tag prohibitions in full.
 * Where that written standard and the linter configuration disagree, the
 * Explainability rule and the migration plan remain authoritative and both
 * implementations are raised to match them; a green linter result cannot narrow
 * the governing requirement.
 *
 * <h2>Citation discipline, and two identifier namespaces that collide</h2>
 *
 * <p>Assumptions: everything under {@code app/} is the behavioural
 * SPECIFICATION for this migration. It is read, cited by path and line, and
 * never modified. A test that needed the baseline altered in order to pass
 * would be asserting something the specification does not say. The framing of
 * any difference between the two is constrained, because the wrong verb turns a
 * documented divergence into a claim that reference source was changed: the one
 * permitted framing is that the baseline does X, the Java implements Y, and the
 * divergence is documented. "Retired" means retired as a migration target and
 * never deleted.
 *
 * <p>Assumptions: every path and line number cited anywhere in this package is
 * verified on disk before it is written, never recalled and never inferred from
 * a neighbouring file. That is why the inventory citation further up carries
 * two line ranges rather than the one the sibling charters carry. An invented
 * citation is exactly the unsupported claim the rule forbids, and it is worse
 * in a test than elsewhere: a comment naming a line that does not hold what the
 * comment claims will be trusted by the next reader precisely because it looks
 * specific.
 *
 * <p>Assumptions: two identifier namespaces collide by number and are kept
 * textually distinct here. The user-specified rule is Explainability and is
 * cited by its line numbers; "Rule 1" always means that rule. The migration
 * plan's transformation rules are numbered T1 to T10 and are always written
 * with the T, where T1 makes the copybook normative, T5 maps each CICS file
 * verb to one target, and T10 is the plan's own echo of the Explainability
 * rule. "Rule 1" never means "T1", and writing one for the other points a
 * reader at a document that says something else entirely.
 *
 * <h2>Authoring notes for this file</h2>
 *
 * <p>Trade-offs: this file is pure ASCII with no byte-order mark and Unix line
 * endings, and its summary sentence ends with a period because the summary
 * check's sentence terminator is left at its default. The alternative was to
 * reproduce the typographic punctuation the migration prose uses, which would
 * read closer to that prose; plain characters were chosen because they make the
 * non-breaking-hyphen failure described above unreachable in this file by
 * construction rather than by care. The cost is plainer punctuation.
 *
 * <p>Trade-offs: this file holds one Javadoc block and one package declaration
 * and nothing else -- no type, no annotation, no import, no field, no line
 * comment. It carries no authorship, release or version at-clause either,
 * because the shared rule set omits the whole Javadoc-formatting family that
 * would ask for them, and the version control history answers those three
 * questions more reliably than a comment maintained by hand. Prose is wrapped
 * at 80 columns to match the parent charter and the nearest sibling charters in
 * this tree, even though no line-length check is enabled; the few lines that
 * exceed it are inline code spans holding paths that cannot be broken, because
 * a line break inside one would insert this comment's margin into the rendered
 * path.
 */
package com.carddemo.transaction.service;
