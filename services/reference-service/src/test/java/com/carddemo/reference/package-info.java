/**
 * Root test package of the reference-service module, holding the tests that pin
 * the reference-data bounded context to the behaviour of its COBOL baseline.
 *
 * <h2>Target contract, and the present state that makes it checkable</h2>
 *
 * <p>Assumptions: every name and every count in this charter states this
 * subtree's <b>target contract</b> as the migration plan assigns it, so it is
 * read against that plan rather than against a listing of the directory beside
 * it. It says as much about what may <em>not</em> be added to a package as
 * about what belongs there, and that second half is the half a reader cannot
 * reconstruct from the files.</p>
 *
 * <p>What is present in the tree, enumerated so the
 * distinction above is checkable rather than merely declared: EIGHT
 * subpackages exist, each carrying its own charter, and ALL EIGHT
 * carry at least one test class. {@code api} holds FOUR --
 * {@code ReferenceApiRoutingContractTest},
 * {@code DateEvaluationDispatcherTest},
 * {@code ReferenceParameterConstraintTest} and
 * {@code DateConversionRefusalTest}. {@code config} holds THREE --
 * {@code ReferenceApiContractTest},
 * {@code SecurityConfigTest} and {@code SqsConfigTest}. {@code domain} holds ONE,
 * {@code ReferenceKeyCanonicalityTest}. {@code dto} holds TWO --
 * {@code ReferenceWireContractTest} and
 * {@code DomainRefusalDisclosureTest}. {@code fixtures} holds TWO --
 * {@code ReferenceFixtureContractTest} and {@code ReferenceFixtureTest}.
 * {@code mapper} holds THREE -- {@code DateInquiryReplyMapperTest},
 * {@code ReferenceDescriptionTrimTest} and {@code TransactionCategoryMapperTest}.
 * {@code service} holds TEN --
 * {@code DateInquiryMessageListenerTest},
 * {@code ReferenceWriteBehaviourTest}, {@code TransactionTypeBrowseTest},
 * {@code DateConversionServiceTest}, {@code DisclosureGroupServiceTest},
 * {@code ReferenceBatchUpdateServiceTest},
 * {@code ReferenceQueueConsumerContractTest},
 * {@code ReferenceServiceStructureTest}, {@code TransactionCategoryServiceTest} and
 * {@code TransactionTypeServiceTest}. The eighth, {@code repository}, holds NINE --
 * {@code TransactionTypeRepositoryIT},
 * {@code TransactionCategoryRepositoryIT},
 * {@code DisclosureGroupRepositoryIT},
 * {@code UsPhoneAreaCodeRepositoryIT}, {@code UsStateRepositoryIT},
 * {@code UsStateZipPrefixRepositoryIT}, {@code PhoneAreaCodeRepositoryIT},
 * {@code StateRepositoryIT} and {@code StateZipPrefixRepositoryIT}, together with
 * the container base type the first of those hosts. This package root holds this
 * charter and {@code ReferenceMoneyPathRulesTest}. That is TWENTY-SIX classes named
 * {@code Test}, collected by Surefire, and NINE named {@code IT}, collected by
 * Failsafe -- thirty-five in total.</p>
 *
 * <p>Refactoring Rationale: the totals above are re-measured, and the two figures they
 * replaced -- fifteen and six, twenty-one in total -- had gone stale by fourteen classes.
 * Two further claims of that paragraph had also been overtaken: it described
 * {@code ReferenceMoneyPathRulesTest} as a name reserved rather than a file, and that file
 * now sits in this directory, and it credited {@code api}, {@code config},
 * {@code mapper} and {@code service} with fewer classes than each holds. The per-subpackage
 * figures are given so a reader re-measuring finds one stale entry rather than one stale
 * total, and each subpackage charter carries the directory marker line that
 * {@code common-lib}'s {@code PackageCharterInventoryTest} re-measures on every build, which
 * is where the counting now happens.</p>
 *
 * <p>Refactoring Rationale: an earlier revision of the enumeration read "SEVEN of the
 * eight carry at least one test class", listed ten classes, and stated that
 * "this module contributes no class named {@code IT}". Every one of those three
 * claims was false against the tree, and the third was the one that could
 * mislead a reader into a wrong conclusion rather than merely an incomplete
 * one: {@code repository} holds nine classes named {@code IT}, so a reader
 * taking this charter at its word would have concluded that this module's
 * Failsafe run collects nothing and that a green {@code mvn test} was therefore
 * the whole of its verification. The totals are stated as two rather
 * than one because the two suffixes are collected by DIFFERENT plugins in
 * different lifecycle phases, and a single total conceals which command runs
 * which half.</p>
 *
 * <p>Refactoring Rationale: this enumeration read "seven subpackages exist,
 * each carrying its own charter, and each carries at least one test class",
 * and listed seven while eight directories were present -- {@code repository}
 * was absent from both the count and the list. The count was the smaller half
 * of the error: the clause "each carries at least one test class" was the part
 * that could mislead, because it converts an empty package into an invisible
 * one. A reader auditing this tree against the charter would have found a
 * directory the charter did not mention, and the only conclusions available
 * would have been that the charter was stale or that the directory was
 * unauthorised; neither is true. That earlier correction left the ten-class
 * total in place, and the total is the part the paragraph ABOVE has since had to
 * recount: it was accurate when it was written and had gone stale by five
 * classes, which is exactly why a count has to be measured against the
 * directory rather than derived from a list a reader is not obliged to
 * re-verify. The paragraph above supersedes this one on every number; this one
 * is retained because it records how the omission arose.</p>
 *
 * <p>Refactoring Rationale: the paragraph above is written as an enumeration a
 * reader can check entry by entry instead of as a summary, and the reason is
 * recorded experience rather than preference. The main-tree charter of this
 * same context carries a labelled rationale admitting that its description of
 * the exposed surface was authored before any of that surface existed and was
 * then left unrevised while the packages filled in. A state sentence that goes
 * stale is worse than no state sentence at all: a reader who checks one claim,
 * finds it wrong, and has no way to tell which of the remaining claims are
 * also wrong stops trusting the whole file, including the rulings that are
 * still sound. Naming each class individually is what keeps one stale entry
 * legible as one stale entry instead of discrediting the charter around
 * it.</p>
 *
 * <h2>Why this subtree mirrors the main tree package for package</h2>
 *
 * <p>Refactoring Rationale: this test tree repeats the layer packages of
 * {@code src/main/java/com/carddemo/reference} rather than organising itself
 * by test kind, by scenario or by runner. The baseline it replaces reached its
 * data contracts through a copybook include path, so one {@code COPY}
 * statement admitted exactly one record layout and every program that needed
 * that layout named the same one. The migration keeps that property by turning
 * each former {@code COPY} into a single type import from the one package that
 * owns the contract, which is the whole reason a shared kernel module exists.
 * A test tree arranged on any other axis would break that correspondence in
 * the place it matters most: a reader holding a failing assertion could no
 * longer walk from the test to the one package that owns the contract under
 * test, and a test needing a type from a second layer would have no principled
 * home. Mirroring costs a directory per layer even where a layer has one test
 * class, and buys the property that the location of a test is derivable from
 * the code it covers rather than remembered.</p>
 *
 * <p>Assumptions: the package root is {@code com.carddemo.reference}, fixed by
 * the migration plan's list of nine package roots and identical to the main
 * tree's root, which is what lets a test read a package-private member of the
 * type it covers without that type widening its visibility for the test's
 * benefit. The mirror is deliberately not total in two directions, and both
 * are decisions rather than omissions. The main tree's {@code repository}
 * package is mirrored here by a directory holding a CHARTER AND NO TEST CLASS,
 * because the assertions that would live there need a database and this module
 * contributes no container-backed class -- so the package exists to record that
 * emptiness and its reason, rather than being absent and leaving a reader to
 * guess whether the coverage was considered. This tree's {@code fixtures}
 * package has no counterpart in the main tree,
 * because the fixed-width fixture records it reads are test material with no
 * production peer; that package's own charter records why a fixture without an
 * executable consumer documents an intention instead of asserting a fact. A
 * reader should not read either asymmetry as a set waiting to be
 * completed.</p>
 *
 * <p>Refactoring Rationale: the first asymmetry read "has no counterpart here",
 * which described the intent correctly and the tree incorrectly -- the
 * counterpart directory is present, carrying its charter. The distinction
 * matters in the one direction a reader acts on: told the package is absent,
 * a reader adding container-backed coverage would create the directory and
 * author a new charter, and the ruling the existing charter records about why
 * that coverage does not belong in this module would be silently replaced
 * rather than argued with. Stating that the package exists and is deliberately
 * empty routes that reader to the ruling instead.</p>
 *
 * <p>Assumptions: {@code com/carddemo} above this directory is an
 * organisational segment of the package path holding no compilation unit, so
 * it carries no charter of its own and none should be added. The file-set
 * check that requires a charter fires only for a directory holding a file the
 * audit processes, and that one holds none; the Explainability rule attaches
 * its obligation to a module entry point, and a directory with no compilation
 * unit has no entry point to carry a docstring. The absence there is
 * therefore the correct state and not a gap in this subtree's coverage.</p>
 *
 * <h2>What this subtree is answerable to</h2>
 *
 * <p>Assumptions: everything beneath {@code app/} is the behavioural oracle
 * for this migration and is read, cited and never modified. No test in this
 * subtree may write to that tree, and no divergence from it may be described
 * here as though the baseline had been altered: the permitted form is that the
 * baseline does one thing at a named path and line, the migrated code does
 * another, and the difference is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which this
 * subtree references and does not author. The programs this context answers
 * for are:</p>
 *
 * <ul>
 *   <li>{@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}, the
 *       transaction-type inquiry and list screen.</li>
 *   <li>{@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}, the
 *       transaction-type maintenance screen covering add, edit and
 *       delete.</li>
 *   <li>{@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl}, the batch
 *       reference maintenance program.</li>
 *   <li>{@code app/app-vsam-mq/cbl/CODATE01.cbl}, the queue-driven
 *       date-conversion request and reply.</li>
 *   <li>the disclosure-rate lookup semantics of {@code app/cbl/CBACT04C.cbl},
 *       whose fallback behaviour this subtree asserts and whose interest
 *       arithmetic belongs to the batch context.</li>
 * </ul>
 *
 * <p>Assumptions: the date-edit rules those screens apply are not transcribed
 * into this module and no test here may assert a second copy of them. They
 * live in {@code com.carddemo.common.validation.DateEditValidator}, so one set
 * of rules serves every context that edits a date and a test that restated
 * them would be asserting against its own copy rather than against the rules
 * the service actually runs.</p>
 *
 * <h2>The inventory this package root owns</h2>
 *
 * <p>This directory is reserved for two files and admits no third: this
 * charter, and {@code ReferenceMoneyPathRulesTest}, the module-wide rules test
 * that guards the exactness of the money and rate path. That test owns the
 * assertion and this charter does not restate its mechanics, because a
 * prohibition asserted in two places can be satisfied in one of them and
 * reported as satisfied in both. Everything narrower than a module-wide rule
 * belongs in the subpackage that mirrors the layer it covers.</p>
 *
 * <p>Alternatives Considered: putting that rules test in an
 * {@code architecture} subpackage, which is where two sibling modules of this
 * reactor keep their equivalent gate proofs alongside their layering rules.
 * Not adopted here, and the reason is a property of this module rather than a
 * preference: those siblings hold three such classes each, enough that a
 * directory earns its name, whereas this module has one module-wide rule.
 * Adding a subpackage with no counterpart in the main tree to hold a single
 * class would break the mirroring property established above for the sake of
 * one file, and the mirroring is what lets a reader derive a test's location
 * from the code it covers. Root placement is not an invention either: three
 * other modules in this reactor already keep a module-scope test class at their
 * own test root. What is given up is the uniformity of finding gate proofs
 * under the same subpackage name in every module, which is why the divergence
 * is recorded here rather than left to be noticed.</p>
 *
 * <p>Assumptions: two absences in the subpackages are decisions, recorded here
 * so that a reader does not complete a set. There is no per-controller test
 * class in {@code api}: the REST surface is held to its published document by
 * one routing-contract test that compares document and handlers in both
 * directions, so an operation declared with no handler and a handler with no
 * declared operation each fail the build, which per-controller classes would
 * not catch between them. And there is no test for a lookup service, because
 * no lookup service exists to test -- the address-lookup controller is the one
 * controller in this context with no service collaborator, reading its three
 * seeded allow-list repositories directly, so a test named for a collaborator
 * that the design deliberately omits would have nothing to exercise.</p>
 *
 * <p>Assumptions: no test in this subtree may assert an offset-paged read, in
 * this package root or in any subpackage. The baseline browses by key and
 * carries the cursor key between turns, and offset paging skips and repeats
 * rows under concurrent inserts, so an assertion written against an offset
 * would pass while describing behaviour the baseline does not have. Paging
 * assertions are written against the key columns and the page envelope the
 * shared kernel publishes.</p>
 *
 * <h2>The Surefire and Failsafe split is carried by class names alone</h2>
 *
 * <p>Which runner executes a test, and therefore at which point in the build a
 * failure surfaces, is decided entirely by the suffix on its class name:</p>
 *
 * <ul>
 *   <li>A class ending {@code Test} is collected by Surefire and runs at the
 *       {@code test} phase, needing no declaration because the standard
 *       lifecycle already binds it.</li>
 *   <li>A class ending {@code IT}, which in this subtree means a
 *       {@code RepositoryIT} class, is collected by Failsafe and runs at
 *       {@code integration-test} with its result asserted at {@code verify},
 *       so a failure surfaces as a build failure rather than as a silently
 *       skipped assertion.</li>
 * </ul>
 *
 * <p>Assumptions: the suffix is doing structural work, which is exactly why it
 * is stated at package scope. A container-backed test misnamed {@code Test}
 * runs under Surefire with no container and fails for the wrong reason, and a
 * unit test misnamed {@code IT} is passed over by Surefire and appears to
 * succeed by never having run. The second failure mode is the dangerous one,
 * because it looks identical to success in every report.</p>
 *
 * <p>Assumptions: both runners are left at their DEFAULT report directories,
 * {@code services/reference-service/target/surefire-reports} and
 * {@code services/reference-service/target/failsafe-reports}, and neither
 * plugin is declared by this module, whose only build plugin is the Spring
 * Boot one. Relocating, renaming or redirecting either directory would make
 * the build green while the pipeline that collects from those two paths
 * published nothing, which is the one failure mode here that reads as success.
 * No test in this subtree may set a reports directory and no module-level
 * configuration that does so may be added.</p>
 *
 * <p>Trade-offs: the directory name {@code reports} at the repository root is
 * unavailable to this subtree, being reserved by the existing COBOL suite's
 * own workflow for its three layers' output. The cost accepted is that this
 * module's reports sit two directories deeper than a single shared folder
 * would put them; what it buys is that neither suite can overwrite the other's
 * output, and the suite that must not be disturbed is the one that keeps its
 * established path.</p>
 *
 * <p>Assumptions: the only intra-reactor dependency of this module is
 * {@code common-lib}, which is the first of the nine modules in the reactor
 * while this module is the sixth, so the shared kernel and its test artifact
 * are always built before anything here compiles. No test in this subtree may
 * reach into a sibling service module. A shared assertion that two contexts
 * both need belongs in {@code common-lib}, and a service-specific one belongs
 * in the service that owns the behaviour; a test that imported a sibling would
 * couple two deployables through their test code, which is the coupling the
 * bounded contexts exist to prevent.</p>
 *
 * <h2>Strictly additive to the COBOL parity oracle, which is a different
 * suite</h2>
 *
 * <p>Two test suites exist in this repository and they are kept textually
 * distinct in every sentence of this subtree, because conflating them is what
 * leads someone to edit the one that must not be edited. The parity ORACLE is
 * the COBOL suite rooted at {@code tests/}, comprising {@code tests/cobol-unit/},
 * {@code tests/integration/}, {@code tests/e2e/}, {@code tests/fixtures/},
 * {@code tests/golden/}, {@code tests/helpers/} and {@code tests/mocks/} --
 * each written with its own prefix so that every one of the seven resolves
 * without a reader carrying the parent directory over from the preceding
 * clause. The Java suite is the one rooted at this package, under
 * {@code services/reference-service/src/test}. The oracle is never modified,
 * never re-pinned and never replaced, and it records that property of itself at
 * {@code tests/README.md} lines 587 to 589, where it states that the suite is
 * additive and test-only and that production code under {@code app/} is never
 * modified. The tests in this subtree are additive to it on the same
 * terms.</p>
 *
 * <p>Assumptions: NO GOLDEN MASTER EXISTS for this context's paths, and none is
 * claimed. The oracle records at {@code tests/README.md} lines 83 to 85 that
 * the online {@code CO*} programs cannot be run end to end without a CICS
 * runtime, which the runner does not have, so only their extractable
 * field-validation logic is unit-tested. That exemption covers the
 * transaction-type screens and the date-conversion program directly, and the
 * batch reference maintenance program has no fixture or golden directory of
 * its own either: the oracle's fixture tree holds the export, interest,
 * posting, prepost, provisioning and statement domains, and its golden tree
 * holds the interest, posting, provisioning, reporting and statement domains.
 * Neither tree holds a reference-data domain.</p>
 *
 * <p>Assumptions: the consequence is that parity for this context rests on two
 * things instead of on a byte comparison -- logic transcribed faithfully from
 * the COBOL paragraphs, and the table and record contracts declared in the
 * baseline's own data definitions and copybooks. The assertions in this subtree
 * are therefore PRIMARY evidence of parity rather than a supplement to a
 * golden comparison, which raises what an omission costs here relative to a
 * context that has both. No test in this subtree may create, regenerate or
 * otherwise touch anything under {@code tests/golden/} or
 * {@code tests/fixtures/}, and no assertion here may be justified by pointing
 * at a golden file, because there is no such file to point at.</p>
 *
 * <h2>The build gate here is binary; the graded rubric belongs to the
 * oracle</h2>
 *
 * <p>Trade-offs: the oracle grades its outcome on the mainframe
 * condition-code rubric its runners document at {@code tests/README.md} lines
 * 414 to 423, where the runners aggregate the worst code seen, a usage code of
 * 2 is excluded from that aggregation altogether, and a warning-level
 * aggregate is the suite's documented green state. That rubric is retained
 * there and deliberately NOT adopted here, which leaves two different result
 * models in one repository. The cost is that a newcomer must learn which model
 * governs which tree; what it buys is a Java gate that cannot report a failing
 * assertion as an acceptable outcome, and for a suite that is the primary
 * evidence of parity that is not a tolerance worth having.</p>
 *
 * <p>Assumptions: the rubric is QUARANTINED to {@code tests/}. A Maven,
 * Surefire, Failsafe, Checkstyle, ArchUnit or JUnit outcome in this subtree is
 * BINARY -- it passes or it fails. No graded tolerance, no warning tier and no
 * arithmetic on a return code may be introduced anywhere here; no result of
 * this module's build may be described as warning-level green; and this
 * module's gate is never wired into the oracle's own workflow, which is
 * reference material exactly like the suite it runs.</p>
 *
 * <p>Assumptions: a return code of 4 does have a distinct and legitimate
 * meaning INSIDE the specification, and the two uses must never be conflated.
 * {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} lines 230 to 233 move
 * 4 into {@code RETURN-CODE} on its soft-reject path. That is a behaviour this
 * subtree may require exactly -- a test may assert that value from that
 * condition -- and not a build-gate policy to adopt.</p>
 *
 * <p>Assumptions: the fatal tier of that rubric illustrates itself, at
 * {@code tests/README.md} line 422, with a paragraph named
 * {@code 9999-ABEND-PROGRAM}, and the near-identical names of two paragraphs
 * this context cites make that example a trap worth naming. The rubric's
 * example is {@code app/cbl/CBACT04C.cbl} lines 628 to 632, which displays
 * {@code ABENDING PROGRAM}, moves 0 to its timing field and 999 to its abend
 * code, and calls {@code CEE3ABD} -- a genuine Language Environment abend. It
 * is NOT the {@code 9999-ABEND} of
 * {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} at lines 230 to 233,
 * which displays its return message, moves 4 into {@code RETURN-CODE} and
 * exits without abending at all. This is the one subtree that cites both
 * programs, so the confusion is reachable here in a way it is not elsewhere,
 * and reading the second as the first would turn a documented soft reject into
 * an apparent unrecoverable failure.</p>
 *
 * <h2>The two outcomes this subtree may never leave unasserted</h2>
 *
 * <p>Assumptions: deleting a transaction type that a category still references
 * answers HTTP 409, and never HTTP 500. The constraint is declared in the
 * baseline at {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} lines 6 and
 * 7, where the foreign key on {@code TRC_TYPE_CODE} references
 * {@code CARDDEMO.TRANSACTION_TYPE (TR_TYPE)} with {@code ON DELETE RESTRICT}.
 * The chain the migrated form travels is that the restriction the baseline
 * reported as a negative constraint code arrives as PostgreSQL SQLSTATE
 * {@code 23503}, the driver's failure is translated into a data-integrity
 * violation, and {@code com.carddemo.common.error.GlobalExceptionHandler}
 * renders that as 409. A 500 carrying a driver stack trace at the end of that
 * chain is a defect and not an alternative reading of the constraint, because
 * the baseline refuses the delete deliberately whereas a 500 reports that the
 * service did not know what happened. This assertion may be strengthened and
 * may never be weakened or removed.</p>
 *
 * <p>Assumptions: the disclosure-group row keyed {@code DEFAULT} exists once
 * this module's second migration has run, and the rate lookup falls back to it
 * when a group key is absent. {@code app/cbl/CBACT04C.cbl} line 437 moves the
 * literal {@code 'DEFAULT'} into {@code FD-DIS-ACCT-GROUP-ID} on the branch
 * taken when the read reported a missing record, and line 79 declares that
 * field {@code PIC X(10)}, so the literal's seven characters are blank-padded
 * to ten and the key actually looked up is ten characters wide with three
 * trailing blanks. A seed row carrying a seven-character key would therefore
 * not be the row the baseline finds, which is why the width is asserted and not
 * only the presence. This assertion is never omitted: the fallback is the only
 * thing standing between an absent group key and an account accruing no
 * interest at all.</p>
 *
 * <h2>The documentation contract this subtree is held to</h2>
 *
 * <p>Assumptions: one user-specified rule governs this migration,
 * Explainability, and there is no second rule to satisfy or to invent. It is
 * also a separate namespace from the migration specification's own
 * transformation rules, which are numbered with a letter prefix, so a citation
 * of the user rule never denotes one of those. It binds this file on three
 * independent grounds, cited together because each survives if another is
 * contested. Its scope clause at line 15 attaches the docstring obligation to a
 * module entry point, which in Java is the package declaration, and the rule
 * states no exemption for test code anywhere in its text. The inherited
 * documentation gate sets {@code includeTestSourceDirectory} to true so that
 * {@code src/test/java} is audited. And the repository's own house convention
 * at {@code tests/README.md} lines 544 to 549 binds every new test, fixture
 * builder, helper, mock and runner routine to a docstring together with a
 * rationale naming one of four categories, and calls itself a hard review
 * gate.</p>
 *
 * <p>Assumptions: no conflict exists between those grounds, and this subtree
 * extends an established convention rather than importing a new one. The four
 * category names the user rule lists at its lines 31 to 34 are the same four
 * the house convention names, so the obligation a reviewer of this tree applies
 * is the one this repository already applied to its own suite.</p>
 *
 * <p>Assumptions: the rule's own scope clause is why this file exists and also
 * why its Javadoc carries no at-clause. Line 18 asks for a purpose, which this
 * charter states in prose; lines 19, 20 and 21 ask for parameters, return
 * values and exceptions, and a package declaration accepts no argument,
 * returns no value and raises nothing, so all three are inapplicable here and
 * no at-clause is written for them. That omission is a declared
 * inapplicability rather than an instance of the forbidden pattern at line 39,
 * which addresses a docstring that omits elements its member actually has; the
 * inapplicability is stated rather than left silent precisely so a reader can
 * tell the two apart. Line 22 fixes the format as Javadoc, and the ruleset
 * additionally audits at-clause bodies for emptiness, so an invented empty
 * at-clause would be reported rather than credited.</p>
 *
 * <p>Assumptions: the rule's validation gate at line 43 is conjunctive, failing
 * work missing EITHER a docstring or a named rationale, so a well-written
 * charter carrying no reasoning satisfies it no better than reasoning without a
 * charter would. Because this compilation unit holds no statement to annotate,
 * the rationale required by lines 27 to 29 is carried inside this Javadoc as
 * labelled sentences rather than as adjacent line comments, and the
 * twin-comment idiom this repository uses for shell and configuration headers
 * has no place inside a Javadoc block. Note also that the gate's own triad
 * names purpose, parameters and return values and does not mention exceptions,
 * so no exception at-clause anywhere in this subtree may be justified by
 * citing that gate; the obligation to document what a member throws rests on
 * line 21, on the house convention above, and on the ruleset's own validation
 * of thrown types.</p>
 *
 * <p>Assumptions: the four rationale labels are written in one form and one
 * form only -- {@code Alternatives Considered:}, {@code Refactoring
 * Rationale:}, {@code Assumptions:} and {@code Trade-offs:} -- plural,
 * unparenthesised, colon-terminated and unemphasised, as
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes them character for
 * character. The singular variants that appear in the repository's existing
 * reference-only suite denote the same four categories, and the plural form
 * governs every Java file in these new trees; that equivalence is stated here
 * once for this subtree and is not repeated in any sibling or subpackage. A
 * capitalised or elided spelling of the compromise label is not an accepted
 * variant. The labels are searched for literally before a person reads them, so
 * a second spelling reads as documented to a reviewer and as absent to the
 * search.</p>
 *
 * <p>Assumptions: the labels are TYPED in this subtree and never copied out of
 * {@code tests/README.md}. That file carries a non-breaking hyphen 106 times
 * across 77 of its lines, and its own list of the four categories at line 548
 * renders the compromise label with that character together with a typographic
 * dash. A non-breaking hyphen is indistinguishable from an ordinary one on
 * screen while behaving differently in a search, so a label copied from there
 * becomes a token that a search for the label fails to find, which makes a
 * rationale that was genuinely written read as absent to the audit looking for
 * it.</p>
 *
 * <p>Assumptions: the gate reaches every construct in this subtree, not only
 * its top-level classes. Each test class, each nested class, each test method,
 * each fixture builder and each private helper carries its own docstring and,
 * where it makes a non-obvious choice, its own labelled rationale. The gate is
 * bound to the build's {@code validate} phase ahead of compilation, so it runs
 * on a developer's own machine and not only in the pipeline, and it treats a
 * warning as a failure against a ruleset whose own severity is an error.</p>
 *
 * <p>Assumptions: there is no escape from that gate and none may be requested.
 * None of the three comment-driven or annotation-driven suppression filters is
 * enabled in the ruleset, so neither a marker comment nor an annotation
 * suppresses anything, and the companion suppressions file is loaded
 * non-optionally so a missing or unreadable one fails the build rather than
 * passing silently. That file exempts exactly two paths, generated sources and
 * the test resource fixture tree, and it records that the test tree as a whole
 * is never exempt. Test source under {@code src/test/java} is outside its
 * charter, so no suppression for this subtree may be added, and relaxing the
 * gate through a skip flag or a lowered failure threshold is a breach of the
 * rule rather than a build-configuration choice.</p>
 *
 * <h2>Authoring notes for this file</h2>
 *
 * <p>Assumptions: this charter looks contentless and is load-bearing, and the
 * mechanism is recorded so that it is not tidied away. Two checks act on this
 * directory as a pair. One sits above the syntax tree and asserts only that a
 * {@code package-info.java} FILE exists for any directory holding a file the
 * audit processes; the other sits inside the syntax tree and asserts that the
 * file CARRIES Javadoc. An empty file satisfies the first and fails the second,
 * and a bare package declaration does the same, which is why this charter is
 * prose. The two outcomes are not symmetric either: a charter that carries
 * Javadoc can never produce a violation, since the content check fires only on
 * one that lacks it, whereas an absent charter can. Note the precise trigger,
 * because it is easy to misread -- the file-set check fires for a directory
 * that holds a processed compilation unit, and this directory holds exactly one
 * as this charter is written, namely this file, which satisfies the check for
 * itself. Adding the reserved rules test to this directory without this charter
 * present is what would fail the build, so this file is the thing that makes
 * that sibling addable.</p>
 *
 * <p>Trade-offs: this file is pure ASCII with no byte-order mark, its lines end
 * with a single newline character, and its summary sentence ends with a period
 * because the summary check's sentence terminator is left at its default value.
 * The alternative was to reproduce the typographic punctuation the migration
 * prose uses, which would read closer to that prose; ASCII was chosen because
 * it puts the non-breaking-hyphen failure described above out of reach in this
 * file by construction rather than by care. What is given up is the nicer
 * punctuation.</p>
 *
 * <p>Trade-offs: this file holds one Javadoc block and one package declaration
 * and nothing else -- no import, no annotation, no type, no field and no line
 * comment. It carries no authorship, version or release-marker at-clause
 * either, because the ruleset omits the whole Javadoc-formatting family that
 * would ask for them and the version control history answers those three
 * questions more reliably than a comment maintained by hand. Prose is wrapped
 * near 80 columns to match the sibling charters even though no line-length
 * check is enabled; the few lines that run past it hold inline code spans
 * carrying paths that cannot be broken, because a line break inside one would
 * insert this comment's margin into the rendered path.</p>
 */
package com.carddemo.reference;
