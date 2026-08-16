/**
 * Unit tests for the service layer of the account bounded context, run against substituted
 * collaborators.
 *
 * <p>ELEVEN classes execute here. Each one owns a rule or an entry point of this context's service
 * layer, and each states in its own descriptor which COBOL program and which physical line it was
 * transcribed from. The roster is written out rather than summarised as a count, because a count tells
 * a reader that a class is missing without telling them which rule went with it.</p>
 *
 * <p>Refactoring Rationale: the roster below is enumerated from the directory rather than from memory,
 * and it previously said TEN while omitting {@code AddressValidationServiceTest}. A roster that is
 * short by one is worse than a bare count, because the count at least admits it is a summary whereas an
 * enumeration reads as exhaustive: a reader auditing the allow-list contract against this list would
 * have concluded that the five {@code app/cpy/CSLKPCDY.cpy} condition-name lists had no owning class
 * and either duplicated it or filed it as a gap. The pairing that made the omission easy to miss is
 * spelled out in the two entries below, because the two address classes assert different halves of the
 * same rule and the similarity of their names is exactly what hid one of them.</p>
 *
 * <ul>
 *   <li>{@code AccountViewServiceTest} asserts the account view's three-hop composition and the four
 *       outcomes it reaches, the exact bytes of every sentence a user reads, the four sentinel values
 *       the input edit distinguishes, and the two report behaviours of {@code app/cbl/CBACT01C.cbl}
 *       that the target does not reproduce, from {@code app/cbl/COACTVWC.cbl}.</li>
 *   <li>{@code AccountUpdateServiceTest} asserts the edit surface of the update path itself -- the
 *       SEVENTEEN edit routines beneath the driver at L1429 of {@code app/cbl/COACTUPC.cbl}, the two
 *       validation-marker regimes that program declares, both of its concurrency signal sites, and the
 *       three places where the target deliberately behaves differently from it.</li>
 *   <li>{@code AccountUpdatePreservationTest} asserts which submitted values an account update applies
 *       and which stored values it preserves, and that a stale precondition changes nothing, from
 *       {@code app/cbl/COACTUPC.cbl}.</li>
 *   <li>{@code AccountAddressValidationTest} asserts that the same update path actually runs the three
 *       address value-domain edits, whose allow-lists come from {@code app/cpy/CSLKPCDY.cpy}. It proves
 *       the edits are REACHED; it deliberately does not restate their contents.</li>
 *   <li>{@code AddressValidationServiceTest} owns those contents: the FIVE condition-name allow-lists
 *       {@code app/cpy/CSLKPCDY.cpy} declares over THREE targets -- the three telephone area-code lists
 *       at its L30, L521 and L931, the state list at its L1013 and the four-character state-and-postal
 *       pairing at its L1073 -- asserted directly against {@code AddressValidationService} rather than
 *       through the update path, so a single missing list is attributable to the edit that lost it.</li>
 *   <li>{@code AccountViewRevisionTest} pins that the account view and the concurrency revision beside
 *       it are derived from ONE read of the same two rows, from {@code app/cbl/COACTVWC.cbl}.</li>
 *   <li>{@code CustomerMasterReadTest} pins the two customer-master reads the view service performs,
 *       the keyed read and the bounded ascending scan, from {@code app/cbl/CBCUS01C.cbl}.</li>
 *   <li>{@code CardXrefByAccountReadTest} pins the two account-keyed cross-reference reads that stand
 *       in for the {@code CXACAIX} alternate index, from {@code app/cbl/CBACT03C.cbl}.</li>
 *   <li>{@code CustomerIdentifierCipherTest} pins what the customer-identifier encipherment produces
 *       and, above all, what it never produces.</li>
 *   <li>{@code InquiryMessageListenerTest} asserts the account-inquiry consumer's answers, its refusals
 *       and its delivery discipline, transcribed from {@code app/app-vsam-mq/cbl/COACCT01.cbl}, whose
 *       fixed reply layout is a wire contract rather than a formatting choice.</li>
 *   <li>{@code RestReferenceAddressLookupTest} covers the outbound synchronous adapter that resolves
 *       the three address allow-lists from the reference context.</li>
 *   <li>{@code InquiryListenerHealthTest} holds the inquiry consumer's health contribution to reporting
 *       the consumer's ACTUAL state, across the four states it distinguishes -- running, stopped,
 *       unregistered, and no registry at all. It has no baseline provenance of its own for the reason its
 *       subject's charter entry records: the reference program ends when a queue open fails, and a
 *       container-hosted consumer expresses that through a health signal instead.</li>
 *   <li>{@code AddressValidationServiceTest} pins the three address value-domain edits against the
 *       FIVE condition-name allow-lists {@code app/cpy/CSLKPCDY.cpy} declares over three targets --
 *       three telephone-area-code lists at lines 30, 521 and 931, the state list at line 1013, and
 *       the state-and-first-two-zip-digits combination list at line 1073 -- so no allow-list is left
 *       without a case. It is distinct from {@code AccountAddressValidationTest}, which asserts that
 *       the UPDATE path actually runs these edits; this class asserts what the edits themselves
 *       decide.</li>
 * </ul>
 *
 * <h2>What this directory holds</h2>
 *
 * <pre>
 * this directory: 13 java files = 12 tests + 1 charter
 * </pre>
 *
 * <p>Refactoring Rationale: that marker line is machine-checked, and it is here because the prose count
 * above was allowed to drift once already. {@code PackageCharterInventoryTest} in {@code common-lib}
 * re-measures the figures against the directory in BOTH directions and holds every enumerated member to
 * being a file beside this charter, so an added class that nobody lists, and a listed class that nobody
 * added, each fail the build instead of surviving as an out-of-date sentence. Leaving the count as prose
 * alone was rejected for exactly the reason this section exists: prose is corrected only when a reader
 * happens to recount, and the omission it hid here was one entry out of ten.</p>
 *
 * <p>Refactoring Rationale: this test package mirrors the production package one-to-one instead of
 * grouping cases by concern -- one folder of validation tests, one of persistence tests, one of
 * messaging tests -- which is the arrangement a reader coming from a layered test suite would expect.
 * Grouping by concern was rejected because {@code docs/architecture/cobol-to-service-traceability.md}
 * cites COBOL-paragraph-to-Java-method pairs, and a pair is only checkable if the test asserting the
 * Java half sits in the package that declares it. Under a by-concern layout the tests for one program
 * scatter across three folders, and nothing then reveals that a paragraph lost its only assertion.</p>
 *
 * <h2>The type boundary of the package under test</h2>
 *
 * <p>Assumptions: the production package {@code com.carddemo.account.service} declares EIGHT types --
 * {@code AccountViewService}, {@code AccountUpdateService}, {@code AddressValidationService},
 * {@code InquiryMessageListener}, {@code InquiryListenerHealth}, {@code RestReferenceAddressLookup},
 * {@code CustomerIdentifierCipher} and the {@code AccountRevision} token -- and there is deliberately no
 * {@code CustomerService} and no
 * {@code CardXrefService} anywhere in the reactor. Neither type exists, so no class here may name,
 * extend or substitute one; a test that mocks a type that does not exist compiles against its own
 * invention and proves nothing about this service. Customer reads and card-cross-reference reads are
 * both served by {@code AccountViewService}, which is why {@code CustomerMasterReadTest} and
 * {@code CardXrefByAccountReadTest} exercise that one collaborator rather than two of their own.</p>
 *
 * <p>Assumptions: the single dependency edge inside this package is {@code AccountUpdateService} onto
 * {@code AddressValidationService}, and it is a consequence of the baseline rather than a layering
 * preference. {@code app/cbl/COACTUPC.cbl} pulls the lookup tables into the update program itself with
 * a {@code COPY CSLKPCDY.} statement at its L602, so the address edits run inside the update path and
 * not beside it. A test that validated an address without going through the update service would
 * therefore be asserting a structure the baseline does not have.</p>
 *
 * <h2>How these classes are selected, and where their results land</h2>
 *
 * <p>Assumptions: every class here ends in {@code Test}, and that suffix is the ONLY thing that selects
 * it. {@code services/pom.xml} configures neither an include pattern nor a reports directory for either
 * test plugin, so both run on their defaults: Surefire claims {@code **}{@code /*Test.java} in the
 * {@code test} phase and Failsafe claims {@code **}{@code /*IT.java} in {@code integration-test}. A
 * class in this package named to end in {@code IT} would be selected by neither plugin. It would not
 * fail -- it would simply be absent from a complete, green report, which is worse than a failing test
 * because a failing test is visible.</p>
 *
 * <p>Trade-offs: keeping the suffix split purely nominal means the name, not the content, decides the
 * phase, and a class can therefore be misfiled by a rename that looks cosmetic. The alternative,
 * annotating or tagging each class and selecting on that, was rejected because the report paths depend
 * on the split staying exactly as configured: {@code .github/workflows/services-ci.yml} collects from
 * {@code services/*}{@code /target/surefire-reports/} and
 * {@code services/*}{@code /target/failsafe-reports/}, which are the plugin defaults. Relocating either
 * directory, or moving selection onto a second mechanism, would leave the build green while the
 * pipeline published nothing.</p>
 *
 * <p>Assumptions: the container-backed work of this module lives in the sibling
 * {@code com.carddemo.account.repository} package, whose {@code *RepositoryIT} and other {@code *IT}
 * classes run under Failsafe against a real PostgreSQL engine. Nothing in THIS package starts a
 * container, a database or a queue, including {@code InquiryMessageListenerTest}, which substitutes its
 * codec, its repository and its ledger. The one class here that does not substitute its whole
 * transport is {@code RestReferenceAddressLookupTest}, and it uses the framework's own
 * {@code MockRestServiceServer} bound to the adapter's builder rather than a live socket, so it still
 * starts nothing and cannot collide with a parallel worker.</p>
 *
 * <p>Assumptions: local verification is {@code mvn -f services/pom.xml clean verify} rather than
 * {@code test}, because that single command is what runs this package's Surefire phase, the sibling
 * package's Failsafe phase and the documentation gate described below in one pass. The module
 * {@code Dockerfile} builds with {@code clean package -DskipTests}, so this tree is not needed for the
 * image to be produced -- but it is still audited during that build, because {@code -DskipTests}
 * suppresses test EXECUTION only, while the documentation gate runs at {@code validate}, which precedes
 * {@code package}, and test sources are compiled regardless.</p>
 *
 * <h2>The absence of a parity oracle</h2>
 *
 * <p>Assumptions: no executable parity oracle exists for any program this context migrates, and that
 * is stated plainly because a reader may reasonably assume one does. Two independent statements in
 * {@code tests/README.md} establish it. Its L83 through L85 record that the online programs cannot be
 * run end to end without a CICS runtime, which the runner does not have, so only their extractable
 * field-validation logic is unit-tested there. And the business rules that suite asserts verbatim,
 * from its L553 onward, name the posting, interest and category-balance programs of other contexts and
 * name none of this context's programs or files at all. Every case in this package is therefore
 * authored directly from the program paragraphs and copybook contracts cited by its own class
 * descriptor, and no golden-master comparison is claimed for any of them.</p>
 *
 * <p>Assumptions: nothing here displaces that suite, and nothing here modifies it. The COBOL
 * three-layer suite under {@code tests/} is the pre-existing functional-parity oracle for the programs
 * it does cover; it is reference material with its own runners and its own pinned toolchain, and these
 * Java tests are strictly additive to it. The two trees are different things and are not the same tree
 * under two names: {@code tests/} is that oracle suite, while this directory is the account service's
 * own test tree. No Maven path in this module reaches into it.</p>
 *
 * <h2>Return codes, and the terminology that goes with them</h2>
 *
 * <p>Assumptions: the graded condition-code convention that {@code tests/README.md} defines in its
 * section 8, at its L412 -- under which a soft code still reads as success and the runners aggregate
 * the worst code seen -- belongs to that suite alone. Every gate over this package is BINARY: Maven,
 * Checkstyle, Surefire, Failsafe and JUnit either pass or fail, and {@code services/pom.xml} says so of
 * itself in as many words. A Java build here is therefore never described as warn-level green, because
 * there is no warn level for it to be green at.</p>
 *
 * <p>Trade-offs: the cost of a binary gate is that a single missing rationale label blocks an otherwise
 * working change, and the mechanisms that would soften it are all refused rather than merely unused --
 * appending a truthy fallback to a gate command, marking a workflow step as tolerating its own failure,
 * passing a skip flag for the tests or for Checkstyle, turning the fail-on-violation flag off, or
 * telling Maven to ignore test failures. Each of those converts a red build into a green one without
 * changing what the code does, which is the one outcome a gate exists to prevent.</p>
 *
 * <h2>Documentation conventions every class here inherits</h2>
 *
 * <p>Assumptions: the written convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}. Where this
 * descriptor and that document appear to differ, the project Explainability rule governs first, the
 * Checkstyle configuration second and the prose standard third.</p>
 *
 * <p>Assumptions: rationale in this package is labelled with exactly four labels, in exactly one
 * spelling -- {@code Alternatives Considered:}, {@code Refactoring Rationale:}, {@code Assumptions:}
 * and {@code Trade-offs:} -- plural, unparenthesised, colon retained, no emphasis markup, and written
 * with the ordinary ASCII hyphen. The labels are taken from the rule that defines them and from
 * nowhere else. That sourcing restriction is not pedantry: L548 of {@code tests/README.md} writes its
 * trade-offs label with a non-breaking hyphen and carries no ordinary hyphen on that line at all, as
 * does its L542, so a label imitated from there is byte-different from every other label in the
 * repository and no plain search will ever find it. That file does corroborate the plural forms; only
 * its hyphen is wrong to copy.</p>
 *
 * <p>Trade-offs: this spelling diverges from the surrounding non-Java material, where the parenthesised
 * singular predominates -- section 11 of {@code tests/README.md} uses it, and so does the greater part
 * of that suite's Python and shell. The compromise accepted is that this package reads differently from
 * that material. What is bought is one spelling across the whole Java tree, the same one
 * {@code services/pom.xml}, both Checkstyle configuration files and the prose standard already use, and
 * it is the spelling the rule itself uses where it names the four categories. The two forms are never
 * mixed within one file.</p>
 *
 * <p>Assumptions: where a class here explains a decision in code rather than in a Javadoc block, it
 * uses the paired form {@code // WHAT:} and {@code // WHY :}, byte-exactly -- no space before the first
 * colon and exactly one before the second, so that the two colons align in a fixed-width font. It is
 * the code form of the idiom {@code tests/README.md} uses with a hash at its L267 and L270. Inside a
 * Javadoc block there is no {@code // WHY :} comment to write, and its equivalent is a prose sentence
 * opening with one of the four labels above, which is the form this descriptor itself uses throughout.</p>
 *
 * <p>Assumptions: a rationale in this package cites the artifact it rests on by path and by PHYSICAL
 * line number, as every citation above does. The rule forbids a vague rationale and separately forbids
 * leaving a non-obvious choice undocumented where a reasonable alternative existed; taken together, in
 * a package whose entire subject is transcribed from another language, an uncited claim about the
 * baseline is a vague rationale, because a reader cannot check it. Line numbers are physical, counted
 * from the first byte of the file, and never the sequence numbers that COBOL sources carry in their own
 * columns.</p>
 *
 * <p>Assumptions: the rule's concession that a trivial accessor may carry a single-line docstring is
 * scoped literally to getters and setters holding no logic. It therefore never reaches a test method: a
 * {@code @Test} method has a purpose that is not derivable from its signature, which is the whole
 * reason the concession is worded the way it is.</p>
 *
 * <p>Assumptions: a test method here that declares a checked exception, or that asserts one is thrown,
 * documents it with {@code @throws}, on three independent grounds. The rule's own docstring
 * specification lists exceptions or errors among the elements a docstring must state where applicable;
 * the house convention at L544 through L549 of {@code tests/README.md} is stricter still, naming
 * Purpose, Parameters, Returns AND Exceptions and calling itself a hard review gate at L549; and
 * {@code JavadocMethod} runs here with its throws validation enabled. Note that this obligation does
 * not come from the rule's Validation Gate clause, whose triad names purpose, parameters and return
 * values and does not mention exceptions at all.</p>
 *
 * <p>Trade-offs: that throws validation has a verified blind spot, so it cannot be the only check.
 * Where a method catches the exception it provokes -- the ordinary shape of a test that asserts a
 * failure -- Checkstyle sees no {@code throws} clause to validate and reports nothing, so a missing
 * {@code @throws} passes the gate. Alternatives Considered: adding a rule to close it. There is none
 * that expresses the condition, so the compensating control is a manual audit of throws coverage in
 * review, and recording the blind spot here is what makes that audit something a reviewer knows to
 * perform rather than something they might.</p>
 *
 * <h2>The Checkstyle contract this package operates under</h2>
 *
 * <p>Assumptions: the gate is {@code com.puppycrawl.tools:checkstyle} pinned at 13.8.0, configured by
 * {@code config/checkstyle/checkstyle.xml}, which declares error severity once at Checker level, limits
 * itself to the {@code java} extension, reads sources as UTF-8, and names
 * {@code config/checkstyle/suppressions.xml} through its own configuration-directory variable with the
 * optional flag false, so a missing companion file stops the build instead of silently suppressing
 * nothing. The execution in {@code services/pom.xml} binds it to {@code validate} with its
 * test-source-directory flag set and its fail-on-violation flag true, so it audits this tree on every
 * local build, before a single class is compiled.</p>
 *
 * <p>Assumptions: ten checks are enabled, and the ones that bear on a file in this package are
 * {@code JavadocPackage}, {@code MissingJavadocPackage}, {@code MissingJavadocType},
 * {@code MissingJavadocMethod}, {@code JavadocType}, {@code JavadocMethod},
 * {@code NonEmptyAtclauseDescription}, {@code SummaryJavadoc}, {@code AtclauseOrder} and
 * {@code CommentsIndentation}. Three of their settings decide what a class here has to write.
 * {@code SummaryJavadoc} runs with its period property at the default, so every Javadoc summary
 * sentence in this package ends with a period, this one included, and its forbidden-fragment pattern
 * additionally rejects a placeholder marker or a bare unspecific rationale standing in for a summary.
 * {@code AtclauseOrder} runs at its default, so at-clauses appear in the order parameters, then return
 * value, then exceptions. And the annotation-exemption list on {@code MissingJavadocMethod} is set
 * EMPTY, so no annotation grants any exemption at all -- not a test annotation, not a lifecycle
 * annotation, not a nested-class or display-name annotation.</p>
 *
 * <p>Assumptions: the two method checks take their visibility filter through DIFFERENTLY NAMED
 * properties, and the names are not interchangeable. {@code MissingJavadocMethod} is configured through
 * a scope property and {@code JavadocMethod} through an access-modifiers property; neither property
 * exists on the other check, so transposing them is not a loosened rule but a configuration that fails
 * to load, taking the whole build with it before any file is read. Both are set to reach every
 * visibility, private included, so a private helper in a test class is documented exactly as a public
 * one is.</p>
 *
 * <p>Assumptions: five absences from that configuration are load-bearing, and none of them may be
 * added here. There is no single-line-Javadoc check, so a one-line Javadoc carrying an at-clause is
 * legal. There is no variable-Javadoc check, so a field needs no Javadoc -- a substituted collaborator
 * or a fixture constant does not need one, though a field embodying a non-obvious decision still owes
 * its {@code // WHY :} comment. There is no Javadoc-style, write-tag or Javadoc-paragraph check, so an
 * authorship, version or since tag is never required and is not written. There is no import-control
 * check, because layering has exactly one owner, {@code LayeringRulesTest} in {@code common-lib}, and
 * re-declaring layering rules in this package would create a second source of truth that could drift
 * from the first. And there is no suppression filter of any kind -- neither the warnings filter nor
 * either comment filter -- which means THERE IS NO IN-CODE BYPASS: an off-switch comment or an
 * annotation-based suppression has no effect whatsoever, and a violation here is fixed rather than
 * silenced.</p>
 *
 * <p>Assumptions: the suppressions file carries exactly two entries, one for generated sources under a
 * build directory and one for fixture resources under a test resources directory. {@code src/test/java}
 * is NOT among them and must not be added, because Rule 1 binds test code as it binds production code;
 * that is the mechanism by which this descriptor is required at all. That file is not modified from
 * here.</p>
 *
 * <h2>Why this descriptor exists, and why it carries no at-clause section</h2>
 *
 * <p>Assumptions: this file exists for two reasons that happen to coincide. The project Explainability
 * rule requires a docstring on every module entry point and hardens that into a review gate whose two
 * halves, the docstring and the rationale, are each independently fatal; in Java a package declaration
 * is that entry point, and {@code package-info.java} is the only compilation unit able to carry a
 * package-level docstring. Mechanically and separately, {@code JavadocPackage} is declared at Checker
 * level, outside the tree walker, where it demands that the FILE exist for any directory holding a
 * processed source file, while {@code MissingJavadocPackage} is declared inside the tree walker, where
 * it demands that the file CARRY a Javadoc block. Both are active, so an empty descriptor, or one
 * holding only a plain block comment, satisfies the first and FAILS the second. Emptying this file down
 * to its {@code package} line stops the build rather than simplifying anything.</p>
 *
 * <p>Assumptions: no {@code package-info.java} exists at {@code src/test/java}, at {@code com}, at
 * {@code com/carddemo} or at {@code com/carddemo/account} in this module, and those four absences are
 * deliberate rather than overlooked. {@code JavadocPackage} is a file-set check that fires only for a
 * directory directly CONTAINING a processed source file, and each of those four holds subdirectories
 * and no source file at all, so neither package check has anything there to fire on. Adding a
 * descriptor at any of the four would be files documenting nothing, each then carrying its own
 * maintenance. The seven leaf packages of this module's test tree each carry exactly one, which is the
 * whole of the obligation. The contrast that makes the rule legible is on the production side of the
 * same module: {@code com/carddemo/account} carries a descriptor under {@code src/main/java} and none
 * under {@code src/test/java}, and the only difference between the two directories is that
 * {@code AccountApplication.java} sits beside the first.</p>
 *
 * <p>Parameters, return values, exceptions or errors. A package declaration accepts no argument, yields
 * no value and raises nothing, so this descriptor carries no such at-clause. The inapplicability is
 * declared rather than passed over so that a reader can tell it from an omission, and inventing a tag
 * to look thorough would both fabricate a contract that does not exist and fail the gate it was added
 * to satisfy, since {@code NonEmptyAtclauseDescription} reports an at-clause with no description as a
 * violation in its own right.</p>
 */
package com.carddemo.account.service;
