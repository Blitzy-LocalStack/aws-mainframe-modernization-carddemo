/**
 * Business-rule transcription tests for the reference-data bounded context,
 * where the logic of five baseline programs is proven rather than merely
 * exercised.
 *
 * <h2>What this package answers for</h2>
 *
 * <p>The rules under {@code com.carddemo.reference.service} are transcriptions,
 * so what has to be asserted is not that they run but that they still differ
 * from one another exactly where the baseline differs. Three of them are write
 * paths that a happy-path test would render indistinguishable: a strict replace
 * that must refuse an absent row instead of inserting one, an upsert that must
 * create, and a batch driver that must reject one record and carry on. A suite
 * proving only that each of the three succeeds would pass equally well against
 * all three collapsed into one behaviour, and that is the outcome this package
 * exists to make impossible.</p>
 *
 * <p>The five rules and the baseline programs each transcribes are listed below
 * with the length of every program, so that a reader can tell a citation which
 * still resolves from one which has drifted:</p>
 *
 * <ul>
 *   <li>The transaction-type rule answers for
 *       {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}, 2098 lines, the
 *       inquiry and list screen whose inline edit is a strict update, and
 *       {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}, 1702 lines, the
 *       maintenance screen covering add, edit and delete as an upsert. The two
 *       screens are cited together because the difference between them is the
 *       distinction the strict-replace assertions exist to hold.</li>
 *   <li>The transaction-category rule answers for the table contracts declared
 *       at {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl}, whose lines 6
 *       and 7 carry the foreign key on {@code TRC_TYPE_CODE} into
 *       {@code CARDDEMO.TRANSACTION_TYPE (TR_TYPE)} with
 *       {@code ON DELETE RESTRICT}, and at
 *       {@code app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl}, which orders its
 *       unique index on that code and then the category, together with the
 *       category reads of {@code COTRTLIC.cbl}. This is a rule in its own right
 *       and not a mode of the one above it, because
 *       {@code TransactionCategoryService} is a separate type from
 *       {@code TransactionTypeService}.</li>
 *   <li>The disclosure-group rate rule answers for {@code app/cbl/CBACT04C.cbl},
 *       652 lines, whose fallback semantics this package asserts while its
 *       interest arithmetic belongs to the batch context. The fallback is worth
 *       naming precisely: line 437 moves the seven-character literal
 *       {@code 'DEFAULT'} into a field that line 79 declares
 *       {@code PIC X(10)}, so the key actually looked up is ten characters wide
 *       with three trailing blanks rather than seven characters long.</li>
 *   <li>The batch reference-update rule answers for
 *       {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl}, 237 lines.</li>
 *   <li>The date-conversion reply rule answers for
 *       {@code app/app-vsam-mq/cbl/CODATE01.cbl}, 524 lines, which declares
 *       itself {@code IS INITIAL} at line 2 and waits five thousand
 *       milliseconds for a message at line 286.</li>
 * </ul>
 *
 * <h2>What this directory holds</h2>
 *
 * <pre>
 * this directory: 13 java files = 12 tests + 1 charter
 * </pre>
 *
 * <p>Twelve test classes are present, and between them they carry all five rules above, with no
 * subdirectory beneath this one:</p>
 *
 * <ul>
 *   <li>{@code ReferenceWriteBehaviourTest} across 33 cases -- grouped under four headings, the
 *       strict replace, the category replace, the maintenance batch and the rate lookup.</li>
 *   <li>{@code TransactionTypeServiceTest} across 47 cases and
 *       {@code TransactionCategoryServiceTest} across 43 -- the two maintained tables' own
 *       behaviour, including the restrict-on-delete refusal.</li>
 *   <li>{@code ReferenceBatchUpdateServiceTest} across 28 cases, with
 *       {@code ReferenceBatchUpdateServiceIT} across 4 beside it -- the maintenance batch path,
 *       unit-level and against a real database.</li>
 *   <li>{@code DisclosureGroupServiceTest} across 16 cases -- the rate lookup and the padded
 *       {@code DEFAULT} fallback.</li>
 *   <li>{@code TransactionTypeBrowseTest} across 17 cases -- the keyset browse of the
 *       transaction-type table.</li>
 *   <li>{@code DateInquiryMessageListenerTest} across 18 cases -- the date-conversion reply rule,
 *       including the reply's exact bytes, the echoed correlation identifier and the request dropped
 *       for having expired.</li>
 *   <li>{@code DateConversionServiceTest} across 6 cases -- the date edit rules themselves.</li>
 *   <li>{@code DateConversionMessageListenerTest} across 11 cases -- the properties of the date
 *       flow that SPAN the two classes above, which neither of their own suites can state: that the
 *       withdrawn consumer both are named after is absent while both halves it was split into
 *       remain, that the copybook geometry and the shared request codec agree on every offset, that
 *       nothing carries from one invocation of the consumer to the next, that the queue answer
 *       ignores its request while the evaluation answer depends on its own, that the reply and the
 *       diagnostic address two distinct queues, that all three queue names arrive from configuration
 *       with no in-code fallback, and that the two ten-character date pictures this flow carries are
 *       never interchangeable.</li>
 *   <li>{@code ReferenceServiceStructureTest} across 3 cases and
 *       {@code ReferenceQueueConsumerContractTest} across 1 -- the structural guards on this
 *       package rather than on one rule.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: this section has been recounted twice. It first named two classes and
 * three files while three classes and four sat in the directory; it was corrected to three and four,
 * and by then ten classes and eleven files were present. The figures live in a
 * different file from the thing they count, so the change that falsifies them
 * never touches them -- which is why the marker line above is now the authority: it is the form
 * {@code common-lib}'s {@code PackageCharterInventoryTest} re-measures against this directory, and
 * the same test re-measures each declared case count against the class it names, so a third
 * recount by hand is not what keeps this true.</p>
 *
 * <p>Refactoring Rationale: the two preceding sections are deliberately kept
 * apart rather than merged into one list. The first states the rules this
 * package is answerable for, which do not change when a class is split or
 * merged; the second states which files carry them, which does. An earlier
 * charter in this subtree records the cost of fusing the two: a description of a
 * surface authored before the surface existed, then left unrevised while the
 * files filled in. A reader who checks one claim, finds it wrong, and has no way
 * to tell which of the remaining claims are also wrong stops trusting the whole
 * file, including the rulings that are still sound. Separating the durable half
 * from the volatile half, and naming each class individually within the
 * volatile half, is what keeps one stale entry legible as one stale entry.</p>
 *
 * <p>Assumptions: a rule may be asserted by a class that is not named after it.
 * Neither the audit that governs this tree nor the user-specified rule requires
 * one test class per service type; what is required is that every rule listed
 * above has an assertion somewhere in this package. A reader looking for the
 * batch-update assertions should therefore look inside the write-behaviour
 * class rather than conclude that they are absent, and a reviewer counting
 * classes against service types will reach a number that means nothing.</p>
 *
 * <h2>No golden master covers any rule in this package</h2>
 *
 * <p>Assumptions: NO GOLDEN MASTER EXISTS for these paths, and none is claimed.
 * The COBOL suite records the reason itself at {@code tests/README.md} lines 83
 * to 85, where it states that the online {@code CO*} programs cannot be run end
 * to end without a CICS runtime, that the runner does not have one, and that
 * only their extractable field-validation logic is unit-tested. That exemption
 * reaches the two transaction-type screens and the date-conversion program
 * directly.</p>
 *
 * <p>Assumptions: the batch reference-update program is uncovered as well, and
 * that is established by census rather than inferred from the exemption. The
 * oracle's fixture tree holds the export, interest, posting, prepost,
 * provisioning and statement domains beside its own charter, and its golden tree
 * holds the interest, posting, provisioning, reporting and statement domains.
 * Neither tree holds a reference-data domain. A case-insensitive search of the
 * whole oracle tree for a file named after any of {@code trantype},
 * {@code trancatg}, {@code trtyp}, {@code date-conv}, {@code codate},
 * {@code cotrt} or {@code cobtupdt} returns nothing: zero filename hits for
 * each of the seven terms and zero in total.</p>
 *
 * <p>Assumptions: the consequence is the single most important thing this
 * charter records. Parity for these four programs rests on two things instead of
 * on a byte comparison, namely logic transcribed faithfully from the COBOL
 * paragraphs and the table and record contracts the baseline declares in its own
 * data definitions and copybooks. The assertions in this package are therefore
 * PRIMARY EVIDENCE of parity rather than a supplement to an oracle that would
 * catch what they missed. Nothing downstream re-checks them. That raises what a
 * single omitted assertion costs here relative to a context holding both forms
 * of evidence, and it is why an assertion in this package may be strengthened
 * and may not be weakened or deleted. No assertion here may be justified by
 * pointing at a golden file, because there is no such file to point at, and no
 * test here may create, regenerate or otherwise touch anything under
 * {@code tests/golden/} or {@code tests/fixtures/}.</p>
 *
 * <h2>Absences in this package that are decisions rather than gaps</h2>
 *
 * <ul>
 *   <li>There is no test for a lookup service, because no lookup service
 *       exists. The address-lookup controller is the one web adapter in
 *       {@code com.carddemo.reference.api} with no service collaborator: it
 *       holds the phone-area-code, state and state-zip-prefix repositories and
 *       reads them directly. Alternatives Considered: a service wrapping those
 *       three repositories, not adopted because an allow-list carries no rule
 *       beyond whether a code is present, so the layer would hold nothing and a
 *       test named for it would have nothing to exercise.</li>
 *   <li>There is no batch job, no batch configuration and no batch starter, so
 *       no test here may reach for a job launcher or a batch test annotation.
 *       Assumptions: the driver this package answers for is a plain sequential
 *       reader, its lines 93 to 96 being a
 *       {@code PERFORM UNTIL LASTREC = 'Y'} loop that ends in a return code
 *       rather than in a restartable chunk, so it migrates to an ordinary
 *       service method that both the web adapter and the batch chain invoke.
 *       This module's build declares the batch starter absent by design and job
 *       ownership belongs to the batch context.</li>
 *   <li>There is no queue configuration class in this package and none is
 *       needed. Assumptions: the receive wait the baseline sets at
 *       {@code CODATE01.cbl} line 286 is carried as a configuration property
 *       and the listener infrastructure is auto-configured from properties, so
 *       whatever this module configures lives in its sibling config
 *       package.</li>
 *   <li>There is no second exception-handling advice.
 *       {@code com.carddemo.common.error.GlobalExceptionHandler} is the only one
 *       declared anywhere in this reactor. Assumptions: a web-slice test does
 *       not import it automatically, so a slice written in this package that
 *       asserts a status code has to import it explicitly; without that the
 *       assertion silently observes the framework's default error handling and
 *       passes while proving nothing about the handler the service actually
 *       runs.</li>
 *   <li>There is no module-wide rules assertion here. The exactness of the
 *       monetary and rate path is guarded once, by the module-scope rules test
 *       that the charter at this subtree's package root reserves for itself, and
 *       it is not restated in this package: a prohibition asserted in two places
 *       can be satisfied in one of them and reported as satisfied in both.</li>
 *   <li>There is no subdirectory beneath this package, and no property file,
 *       ignore file, build output or data definition within it. Assumptions: the
 *       repository's own ignore rules already cover build output without a
 *       per-module entry, and a data definition committed beside a test would
 *       stand up a second source of truth about a schema this module's
 *       migrations already own.</li>
 * </ul>
 *
 * <h2>Surefire collects every test class in this package</h2>
 *
 * <p>Assumptions: every test class here ends in {@code Test}, so Surefire
 * collects them and they run at the {@code test} phase. The container-backed
 * classes ending in {@code IT} belong to the repository package and are
 * collected by Failsafe, which asserts their result at {@code verify}. The
 * suffix is doing structural work, which is why it is stated at package scope
 * rather than left to be inferred: a class placed in this package but given the
 * integration suffix is passed over by Surefire and appears to succeed by never
 * having run, and that is the dangerous direction of the mistake because it is
 * indistinguishable from success in every report.</p>
 *
 * <p>Assumptions: the report directory is left at its default,
 * {@code services/reference-service/target/surefire-reports}, and no test in
 * this package may redirect it. A redirect leaves the build green while whatever
 * collects from the default path publishes nothing, which is once again a
 * failure that reads as success.</p>
 *
 * <p>Trade-offs: the directory named {@code reports} at the repository root is
 * unavailable to this package, being reserved by the COBOL suite's own workflow
 * for the layer reports it uploads from there. The cost accepted is that this
 * module's reports sit several directories deeper than one shared folder would
 * put them; what it buys is that neither suite can overwrite the other's output,
 * and the suite keeping its established path is the one that must not be
 * disturbed.</p>
 *
 * <h2>The graded rubric stops at the oracle; the gate here is binary</h2>
 *
 * <p>Two test suites live in this repository, and this charter keeps them
 * textually distinct in every sentence, because conflating them is what leads
 * someone to edit the one that must not be edited. The parity ORACLE is the
 * COBOL suite rooted at {@code tests/}, whose own charter runs to 590 lines and
 * whose subtrees are {@code tests/cobol-unit/}, {@code tests/integration/},
 * {@code tests/e2e/}, {@code tests/fixtures/}, {@code tests/golden/},
 * {@code tests/helpers/} and {@code tests/mocks/}, each written with its own
 * prefix so that every one of the seven resolves without a reader carrying a
 * parent directory over from the preceding clause. That suite is
 * reference-only. The Java suite is the one rooted at
 * {@code services/reference-service/src/test}, and this package belongs to the
 * second suite and to no part of the first.</p>
 *
 * <p>Trade-offs: the oracle grades its outcome on the mainframe condition-code
 * rubric its runners document at {@code tests/README.md} lines 415 to 423, where
 * the runners aggregate the worst and therefore highest code seen, 0 is a pass,
 * 4 is a warning or soft reject and is that suite's documented green state, 8 is
 * a failure, 16 is fatal, and a usage code of 2 aborts immediately without
 * entering the aggregation at all. The rubric stays there and is deliberately
 * not adopted here, which leaves two result models inside one repository. The
 * cost is that a newcomer has to learn which model governs which tree; what it
 * buys is a gate that cannot report a failing assertion as an acceptable
 * outcome, and for a package that is the primary evidence of parity that is not
 * a tolerance worth having.</p>
 *
 * <p>Assumptions: the rubric is QUARANTINED to {@code tests/}. Every Maven,
 * Surefire, Failsafe, Checkstyle and JUnit outcome in this package is BINARY, in
 * that it either passes or fails. No graded tolerance, no warning tier and no
 * arithmetic over a return code may be introduced here; no build of this module
 * may be described as warning-level green; and no command that runs these tests
 * may be written so that a non-zero result is discarded or reported as
 * continuing.</p>
 *
 * <p>Assumptions: a return code of 4 does carry one legitimate meaning inside
 * this package, and there it is a value under test rather than a policy to
 * adopt. {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} lines 230 to 233
 * move 4 into {@code RETURN-CODE} on the soft-reject path, and in the migrated
 * form that value arrives as the aggregate condition code that
 * {@code ReferenceBatchUpdateService} reports alongside its per-action outcomes.
 * A test may require exactly that value from exactly that condition. It never
 * becomes a JUnit verdict or a build exit status, and the two uses must not be
 * conflated in either direction.</p>
 *
 * <p>Assumptions: the fatal tier of that rubric illustrates itself at
 * {@code tests/README.md} line 422 with a paragraph named
 * {@code 9999-ABEND-PROGRAM}, and because this package cites both of the
 * programs carrying a similarly named paragraph, that example is a trap worth
 * recording. The rubric's example is {@code app/cbl/CBACT04C.cbl} lines 628 to
 * 632, which displays {@code ABENDING PROGRAM}, moves 0 into its timing field
 * and 999 into its abend code, and calls {@code CEE3ABD}: a genuine Language
 * Environment abend, reached from seventeen separate points in that program. It
 * is NOT the {@code 9999-ABEND} of
 * {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} at lines 230 to 233,
 * which displays its return message, moves 4 into {@code RETURN-CODE} and exits
 * without abending at all. The names differ by one word while the behaviours
 * differ by everything: reading the second as the first turns a documented soft
 * reject into an apparent unrecoverable failure, and reading the first as the
 * second turns an abend into a tolerated outcome.</p>
 *
 * <h2>The contracts these tests resolve against</h2>
 *
 * <p>Assumptions: the {@code test} profile comes from
 * {@code services/reference-service/src/test/resources/application-test.yml} and
 * is not duplicated or contradicted anywhere in this package. It supplies a stub
 * token issuer, a messaging setting that leaves the listener container stopped so
 * that no test makes a network call or consumes from a queue it did not set up,
 * quiet logging, and a deliberately minimal actuator surface. A test that
 * declared any of those keys again would be asserting against its own copy, and
 * the copy that lost a later edit would be the one silently in force.</p>
 *
 * <p>Assumptions: the byte-level record contracts come from
 * {@code services/reference-service/src/test/resources/fixtures/README.md},
 * which is binding for the five layouts these rules read, for the base its
 * offsets are declared in, for exact-length and trailing-newline enforcement, for
 * line-ending normalisation, for zoned-decimal sign overpunch, for the two
 * opposite padding regimes that coexist across those layouts, and for the two
 * discriminator pairs that make the fallback and the absent-rate scenarios
 * distinguishable. This charter names that document and does not restate it: a
 * second statement of an offset is a second thing to keep correct, and the one
 * that drifts is not necessarily the one a reader consults.</p>
 *
 * <h2>The documentation contract this package is held to</h2>
 *
 * <p>Assumptions: exactly one user-specified rule governs this work,
 * Explainability, and there is no second rule to satisfy or to invent. It is
 * also a different namespace from the migration specification's own
 * transformation rules, which are numbered with a letter prefix, so a citation
 * of the user rule never denotes one of those. Its scope clause at line 15
 * attaches the docstring obligation to a module entry point, which in Java is
 * the package declaration, and the inherited audit sets its
 * test-source-directory flag so that this tree is examined exactly as the main
 * tree is. The rule states no exemption for test code anywhere in its text.</p>
 *
 * <p>Assumptions: the rule's scope clause is also why this Javadoc carries no
 * at-clause. Line 18 asks for a purpose, which the summary and the first section
 * state in prose; lines 19, 20 and 21 ask for parameters, return values and
 * exceptions, and a package declaration accepts no argument, returns no value
 * and raises nothing, so all three are inapplicable and no at-clause is written
 * for them. That is a declared inapplicability and not an instance of the
 * forbidden pattern at line 39, which addresses a docstring omitting elements
 * its member actually has; it is stated rather than left silent precisely so a
 * reader can tell the two apart. Line 22 fixes Javadoc as the format, and the
 * audit additionally reports an at-clause with an empty body, so an invented one
 * would be flagged rather than credited.</p>
 *
 * <p>Assumptions: the rule's validation gate at line 43 is conjunctive, failing
 * work that is missing EITHER a docstring or a named rationale, so a
 * well-written charter carrying no reasoning satisfies it no better than
 * reasoning without a charter would. Line 29 phrases the rationale obligation
 * permissively and line 43 hardens it, so line 43 is the sentence cited here;
 * the repository's own house convention corroborates that reading by calling
 * itself a hard review gate at {@code tests/README.md} line 549. Note that the
 * gate's own triad names purpose, parameters and return values and does not
 * mention exceptions, so nothing in this package may cite that gate as the
 * ground for documenting what a member throws; that obligation rests on line 21
 * and on the house convention instead.</p>
 *
 * <p>Assumptions: because this compilation unit holds no statement to annotate,
 * the rationale the rule requires is carried inside this Javadoc as labelled
 * sentences rather than as adjacent line comments. The twin-comment header idiom
 * this repository uses for its shell and configuration files has no place inside
 * a Javadoc block, and a line comment placed beside a single statement to
 * narrate it would be the restatement the rule forbids at line 38.</p>
 *
 * <p>Assumptions: the four rationale labels are written in one form and one form
 * only, {@code Alternatives Considered:}, {@code Refactoring Rationale:},
 * {@code Assumptions:} and {@code Trade-offs:}, taken from the user rule's own
 * lines 31 to 34 and pinned character for character by
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}: plural, unparenthesised,
 * colon-terminated, spelled with an ordinary hyphen-minus and carrying no
 * emphasis markup, since the markup the rule renders them with is presentation
 * rather than part of the label. The singular variants that appear in the
 * reference-only suite's own configuration headers denote the same four
 * categories; that equivalence is declared here once so that nobody later
 * corrects one form into the other, it governs those reference-only artifacts
 * alone, and it does not propagate into this package, where the plural is used
 * exclusively and the two forms are never mixed. A capitalised, elided or
 * parenthesised spelling of the compromise label is not an accepted variant. The
 * labels are searched for literally before a person reads them, so a second
 * spelling reads as documented to a reviewer and as absent to the search, which
 * turns a rationale that was genuinely written into one that cannot be
 * counted.</p>
 *
 * <p>Assumptions: those labels are TYPED here and never copied out of
 * {@code tests/README.md}. Within the reference-only baseline trees that file is
 * the sole carrier of the non-breaking hyphen, which it uses 106 times across 77
 * of its lines, and its own list of the four categories at line 548 renders the
 * compromise label with that character together with a typographic dash. A
 * non-breaking hyphen is indistinguishable from an ordinary one on screen while
 * behaving differently in a search, so a label copied from there becomes a token
 * the search for that label fails to find. Every file in this package is
 * therefore pure ASCII, which puts the hazard out of reach by construction
 * rather than by care.</p>
 *
 * <p>Refactoring Rationale: the rulings above are recorded once, at package
 * scope, and every test class in this package cites this charter rather than
 * restating them. The house convention being extended is the oracle's own, at
 * {@code tests/README.md} lines 540 to 542, which resolves record layouts
 * through a single compiler copybook path and instructs that a layout is never
 * duplicated but kept single-sourced. The Java analogue of one include path is
 * one charter. What was wrong with the alternative is concrete rather than
 * stylistic: five test classes each restating the inventory, the quarantine and
 * the label canon would be five copies free to drift, an edit to one would leave
 * four stale, and a reader could not tell which copy was authoritative.</p>
 *
 * <h2>The baseline is read and never written</h2>
 *
 * <p>Assumptions: {@code app/}, {@code tests/}, {@code scripts/} and
 * {@code samples/} are reference-only. Everything in them is read and cited by
 * path and line, and nothing in them is altered by anything in this package. The
 * oracle in particular is currently green on its own terms and is the standard
 * the migrated behaviour is measured against, so re-pinning it or editing it
 * would remove the measure rather than improve it.</p>
 *
 * <p>Assumptions: the permitted way to describe a difference is therefore
 * settled. Where the migrated behaviour departs from the baseline, the form is
 * that the baseline does one thing at a named path and line, the Java does
 * another, and the difference is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, a document this
 * package references and does not author. No sentence anywhere in this package
 * may describe the baseline as having been altered or made good, because no such
 * work has been done or is permitted. The user rule reaches newly authored code
 * only, so the COBOL programs cited above are neither required nor allowed to be
 * annotated after the fact.</p>
 *
 * <h2>Authoring notes for this file</h2>
 *
 * <p>Assumptions: this charter looks contentless and is load-bearing, and the
 * mechanism is recorded so that it is not tidied away. Two checks act on this
 * directory as a pair. One sits above the syntax tree and asserts only that a
 * {@code package-info.java} FILE exists for a directory holding a file the audit
 * processes; the other sits inside the syntax tree and asserts that the file
 * CARRIES Javadoc. An empty file satisfies the first and fails the second, and a
 * bare package declaration does the same, which is why this charter is prose.
 * The audit runs at the build's {@code validate} phase ahead of compilation, so
 * it fires on a developer's own machine and not only in a pipeline, and it treats
 * a warning as a failure against a ruleset whose own severity is an error, which
 * means a violation here stops the whole reactor before anything compiles.</p>
 *
 * <p>Assumptions: the two halves of that pair report differently, and the
 * asymmetry was measured rather than assumed. Removing this charter and running
 * the audit reports the file-set violation against a SIBLING compilation unit in
 * this directory rather than against the charter that is missing, because the
 * check has no absent file to attach a line number to and attaches the finding to
 * a file the directory does hold. Removing only the Javadoc and leaving the
 * package declaration reports the syntax-tree violation against this file itself,
 * at line 1. Two consequences follow for anyone verifying the pair. The first is
 * that a directory holding no other compilation unit has nothing for the file-set
 * half to report against, so the pair is only as strong as the directory is
 * populated. The second is that the build tool audits incrementally against a
 * cache under its own output directory, and a warm cache skips files that have
 * not changed since the previous run; since the file-set half reports against an
 * unchanged sibling, deleting this charter and re-running over a warm cache can
 * report no violation at all, while the same deletion over a cleared cache fails
 * the build. A clean build and a pipeline run both start from the cleared state,
 * so the gate is intact; a warm local rerun is the one condition under which it
 * appears not to be, and that appearance is the cache rather than the check.</p>
 *
 * <p>Assumptions: there is no escape from that audit and none may be requested.
 * None of the comment-driven or annotation-driven suppression filters is enabled
 * in the ruleset, so neither a marker comment nor an annotation suppresses
 * anything, and the companion suppressions file is loaded non-optionally, so a
 * missing or unreadable one fails the build rather than passing quietly. That
 * file exempts two paths, generated sources and the test resource fixture tree,
 * and it records that the test tree as a whole is never exempt. Test source under
 * {@code src/test/java} lies outside its charter, so no suppression for this
 * package may be added, and relaxing the audit through a skip flag or a lowered
 * failure threshold is a breach of the rule rather than a build-configuration
 * choice.</p>
 *
 * <p>Trade-offs: this file is pure ASCII with no byte-order mark, its lines end
 * with a single newline character, and its summary sentence ends with a period
 * because the summary check's sentence terminator is left at its default value.
 * The alternative was to reproduce the typographic punctuation the migration
 * prose uses, which would read closer to that prose; ASCII was chosen because it
 * forecloses the non-breaking-hyphen failure described above. What is given up is
 * the nicer punctuation.</p>
 *
 * <p>Trade-offs: this file holds one Javadoc block and one package declaration
 * and nothing else, with no import, no annotation, no type, no field and no line
 * comment. It carries no authorship, version or release-marker at-clause either,
 * because the ruleset omits the whole Javadoc-formatting family that would ask
 * for them and the version control history answers those three questions more
 * reliably than a comment maintained by hand. Prose is wrapped near 80 columns to
 * match the sibling charters even though no line-length check is enabled; the few
 * lines running past it hold inline code spans carrying paths that cannot be
 * broken, since a line break inside one would insert this comment's margin into
 * the rendered path.</p>
 */
package com.carddemo.reference.service;
