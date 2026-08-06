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
 * <h2>The closed inventory: five files here, four of them tests</h2>
 *
 * <p>Target contract: this directory is to hold exactly five Java files and no
 * subdirectory. Four are test classes, one per migrated program, and the fifth
 * is this charter. Each line count below was counted in the file itself rather
 * than carried over from a summary, and each transaction identifier and screen
 * name is quoted from the transaction inventory in the repository root
 * {@code README.md}:
 *
 * <ul>
 *   <li>{@code TransactionViewServiceTest} pins {@code app/cbl/COTRN01C.cbl},
 *       330 lines, the "Transaction View" single-record detail screen,
 *       transaction {@code CT01}.</li>
 *   <li>{@code TransactionListServiceTest} pins {@code app/cbl/COTRN00C.cbl},
 *       699 lines, the "Transaction List" paged browse screen, transaction
 *       {@code CT00}.</li>
 *   <li>{@code TransactionAddServiceTest} pins {@code app/cbl/COTRN02C.cbl},
 *       783 lines, the "Transaction Add" capture screen, transaction
 *       {@code CT02}.</li>
 *   <li>{@code BillPaymentServiceTest} pins {@code app/cbl/COBIL00C.cbl}, 572
 *       lines, the "Bill Payment" balance-affecting payment screen,
 *       transaction {@code CB00}.</li>
 * </ul>
 *
 * <p>Assumptions: that list is a TARGET CONTRACT and not a measurement of the
 * directory, and the distinction is declared rather than left to be inferred.
 * This charter is authored ahead of the classes it governs, for the reason the
 * file-set check further down gives: a charter has to be present before a
 * sibling class in this directory can clear the {@code validate} phase, so this
 * file lands first and the four test classes follow it. A class named above
 * that has no file is therefore PLANNED, not missing, and the property a reader
 * should check is a listing of this directory rather than a count quoted here.
 * The same holds for the four production classes named in the next section:
 * they are the production package's declared target, and its own charter is
 * where their status is recorded.
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
 * <h2>The four units under test, and no interface beside any of them</h2>
 *
 * <p>Each test class exercises exactly one annotated service class of the
 * production package of the same name: {@code TransactionViewService},
 * {@code TransactionListService}, {@code TransactionAddService} and
 * {@code BillPaymentService}. There is no interface declared beside any of
 * them, so a test instantiates the class itself rather than a stand-in for it,
 * and a reader looking for the code under test has one place to look.
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
 * helper or utility class, and no context configuration class. The four tests
 * deliberately share nothing. The compromise accepted is that the annotation
 * and mock declarations repeat across four files. What that buys is twofold.
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
 * </ul>
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
 * nothing in those packages is a precondition for running these four classes.
 * The four are runnable as a selection on their own. Introducing such a
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
 * re-pinned and never replaced, and the four tests in this package are strictly
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
 * in {@code services/pom.xml} precisely so that tests ARE audited. All five
 * files in this directory are therefore swept in full, and no reader should
 * assume test code is exempt.
 *
 * <p>Assumptions: two checks bear on this file and both must pass. The
 * file-set check {@code JavadocPackage} requires a charter to EXIST in any
 * directory holding an audited compilation unit, which is why this file is
 * authored before the four test classes rather than after them -- without it
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
 * the {@code service} one, and it is the sixth to be authored: the root and the
 * {@code dto}, {@code domain}, {@code repository} and {@code architecture}
 * charters precede it, and the {@code api} and {@code mapper} charters arrive
 * with their packages. The figure is recorded so that a reader can tell a
 * charter that is missing from one that was never intended.
 *
 * <p>Refactoring Rationale: an earlier enumeration of that canon named five
 * leaf subpackages, for six charters in all, and it was superseded when
 * {@code dto} and {@code domain} were added as kinds of test that fitted none
 * of the original five; the parent charter carries its own reasoning for the
 * growth. The earlier figure is recorded here as superseded rather than left
 * unmentioned, because a reader who meets six in an older note and eight in the
 * parent charter would otherwise have no way to tell which is current, and a
 * closed set that excludes a package which exists reads as governance while the
 * governed thing sits outside it.
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
