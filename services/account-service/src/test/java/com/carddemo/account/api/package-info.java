/**
 * Web-layer slice tests for the three REST adapters of the account bounded context.
 *
 * <p>Every class in this package is built with {@code @WebMvcTest}, so the web layer of
 * account-service stands up alone and each collaborator beneath it arrives as a mock. The subject
 * under test is the production package {@code com.carddemo.account.api} and nothing deeper: what a
 * request must look like to be accepted, what status and body a response carries, which authority a
 * route demands, and that no handler depends on a previous call having happened. The dependency that
 * makes that slicing possible is declared at
 * {@code services/account-service/pom.xml} lines 449 to 452, which records that
 * {@code spring-boot-starter-test} supplies MockMvc so the controller tests can be sliced
 * "instead of booting the full context and a database for what are request and response
 * assertions".
 *
 * <p>Assumptions: every contract this charter states about an artifact outside this directory is
 * cited from a descriptor that states it, and never from the artifact itself, so a reader can confirm
 * each claim against the descriptor that owns it. {@code JavadocPackage} at
 * {@code config/checkstyle/checkstyle.xml} line 245 is why this charter is required rather than
 * optional: it demands a {@code package-info.java} in any directory holding an audited source file,
 * so the charter and the classes it governs stand or fall together.
 *
 * <h2>The three classes, and the reference programs that specify them</h2>
 *
 * <p>Three classes execute here and no fourth does. The lengths given are the physical lengths of
 * the reference sources, counted rather than recalled. Those sources are the behavioural
 * specification for this package: they are read, and they are never modified.
 *
 * <ul>
 *   <li>{@code AccountControllerTest} covers {@code com.carddemo.account.api.AccountController},
 *       whose specification is two online programs rather than one.
 *       {@code app/cbl/COACTVWC.cbl}, 941 lines, declares its purpose on line 4 as "Accept and
 *       process Account View request" and is reached through the CICS transaction defined as
 *       {@code DEFINE TRANSACTION(CAVW) GROUP(CARDDEMO)} at {@code app/csd/CARDDEMO.CSD} line 317,
 *       whose program is named on line 318. Its read path issues three separate keyed
 *       {@code EXEC CICS READ} verbs, at lines 727, 776 and 826, which is why the target view
 *       response composes cross-reference, account and customer data into one document and why this
 *       package asserts the shape of that composition rather than three shapes.
 *       {@code app/cbl/COACTUPC.cbl}, 4236 lines and the largest online program in the baseline,
 *       declares its purpose on line 4 as "Accept and process ACCOUNT UPDATE" and is reached through
 *       {@code DEFINE TRANSACTION(CAUP) GROUP(CARDDEMO)} at {@code app/csd/CARDDEMO.CSD} line 306,
 *       whose program is named on line 308. It commits with a bare {@code SYNCPOINT} on line 953 and
 *       abandons the unit of work with {@code SYNCPOINT ROLLBACK} on line 4100. Those two lines fix
 *       what belongs here and what does not: the conflict <b>status</b> a caller receives is a
 *       transport contract and is asserted here, while whether a given edit constitutes a conflict
 *       is behaviour and is asserted in the sibling service package.</li>
 *   <li>{@code CustomerControllerTest} covers
 *       {@code com.carddemo.account.api.CustomerController}, specified by
 *       {@code app/cbl/CBCUS01C.cbl}, 178 lines, which declares its purpose on line 5 as "Read and
 *       print customer data file." That program keys its file on {@code FD-CUST-ID}, declared
 *       {@code RECORD KEY} on line 32 and typed {@code PIC 9(09)} on line 39, with the remaining
 *       {@code PIC X(491)} of the record carried opaquely on line 40. The field-level layout it
 *       copies in on line 45 is {@code app/cpy/CVCUS01Y.cpy}, 26 lines, which declares a 500-byte
 *       record on line 2, opens {@code 01 CUSTOMER-RECORD.} on line 4 and closes its fields with
 *       {@code FILLER PIC X(168)} on line 23. That trailing filler
 *       is padding to the fixed record length and carries no data, so no response asserted here
 *       exposes a field derived from it.</li>
 *   <li>{@code CardXrefControllerTest} covers
 *       {@code com.carddemo.account.api.CardXrefController}, specified by
 *       {@code app/cbl/CBACT03C.cbl}, also 178 lines, which declares its purpose on line 5 as "Read
 *       and print account cross reference data file." It keys on {@code FD-XREF-CARD-NUM}, declared
 *       {@code RECORD KEY} on line 32 and typed {@code PIC X(16)} on line 39, with
 *       {@code PIC X(34)} of opaque remainder on line 40. Its layout, copied in on line 45, is
 *       {@code app/cpy/CVACT03Y.cpy}, 11 lines, declaring a 50-byte record on line 2, opening
 *       {@code 01 CARD-XREF-RECORD.} on line 4 and carrying {@code XREF-ACCT-ID PIC 9(11)} on
 *       line 7. That last field is the reason this adapter offers a lookup by account and not only
 *       by card: the baseline reaches the same rows through a separate CICS access path, defined as
 *       {@code DEFINE FILE(CXACAIX) GROUP(CARDDEMO)} at {@code app/csd/CARDDEMO.CSD} line 63, and
 *       the target reaches them through a secondary index on the same column, so the route exists
 *       here because the access path exists there.</li>
 * </ul>
 *
 * <p>Where the target does not reproduce the reference, the framing in this package stays factual
 * and stays in one direction: the baseline does one thing, the Java encodes another, and the
 * divergence is registered in {@code docs/architecture/cobol-to-service-traceability.md}, which is
 * named as that register by the descriptor at
 * {@code services/account-service/src/main/java/com/carddemo/account/service/package-info.java}
 * lines 27 to 28. The reference source is never described here as having been put right, because it
 * is not altered at all.
 *
 * <h2>What this package does not hold, and where each of those things lives</h2>
 *
 * <p>The omissions matter more than the inventory, because an omission is what sends a reader
 * hunting here for an assertion that was never meant to be here.
 *
 * <ul>
 *   <li>No {@code *ServiceTest}. The transcribed validation chains, the per-program validation
 *       order read as behaviour, and the dirty-check semantics of the update path are asserted in
 *       the sibling {@code com.carddemo.account.service} test package against mocked repositories.
 *       A rule asserted in neither place would be genuinely untested; a rule asserted once, there,
 *       is correctly placed, and asserting it in both would give a later change two places to
 *       update and one place to forget.</li>
 *   <li>No {@code *RepositoryIT}. Repository behaviour and the schema constraints this service owns
 *       are verified in the sibling {@code com.carddemo.account.repository} test package, which runs
 *       under Failsafe against a real container rather than under Surefire. Nothing in this package
 *       starts a database, starts a container, applies a schema migration or reaches a managed
 *       identity pool, and no assertion here may be written as though one of those were
 *       available.</li>
 *   <li>No fixtures and no test resources. Fixed-width record images derived from the copybook
 *       layouts belong under {@code src/test/resources}, a different tree entirely, and the
 *       suppressions charter has a dedicated entry for that location at
 *       {@code config/checkstyle/suppressions.xml} lines 250 to 251 precisely because a 500-byte
 *       record image has no purpose, parameters or return value to document.</li>
 *   <li>No {@code module-info.java}. This module builds as a plain jar, declared
 *       {@code packaging jar} at {@code services/account-service/pom.xml} line 138, and is
 *       assembled onto the class path by Spring Boot's repackage goal. Adding a module descriptor
 *       would impose a second, stricter readability model on a test tree whose dependencies are
 *       already resolved by Maven scope.</li>
 *   <li>No second {@code package-info.java}, anywhere above this one. The reason is the first
 *       decision recorded below.</li>
 * </ul>
 *
 * <h2>The Surefire selection contract, which is the only thing that makes these tests run</h2>
 *
 * <p>Every class in this package ends in {@code Test}, and that suffix is not a naming preference.
 * The reactor splits its two test phases by class name alone, stated at {@code services/pom.xml}
 * lines 65 to 67: Surefire runs the {@code *Test} classes in {@code test}, and Failsafe runs the
 * {@code *IT} classes in {@code integration-test}, asserting their results in {@code verify}.
 * {@code services/pom.xml} lines 1024 to 1033 record that both halves are deliberately left at their
 * default include patterns, so Surefire selects on {@code **}{@code /*Test.java} and Failsafe on
 * {@code **}{@code /*IT.java}, and no include configuration is declared for either.
 *
 * <p>Assumptions: the hazard that follows is silent, which is why it is written down rather than
 * left to be discovered. A class placed in this package and named to end in {@code IT} matches
 * neither Surefire's include pattern nor this package's purpose. It would not run under
 * {@code mvn test} at all, and it would not fail either -- it would simply never be selected, and
 * the report would be complete and green with one class missing from it. A web-layer slice test that
 * silently does not execute is worse than an absent one, because the absent one is visible. The
 * {@code IT} suffix is reserved for the container-backed repository tests in the sibling
 * {@code com.carddemo.account.repository} package, which this module's own descriptor names as
 * {@code *RepositoryIT} at {@code services/account-service/pom.xml} lines 439 to 445.
 *
 * <p>Assumptions: the report directory is equally load-bearing and equally easy to break.
 * {@code services/pom.xml} leaves {@code reportsDirectory} at the Maven defaults, so unit-test
 * results land in {@code target/surefire-reports}; the module descriptor preserves
 * {@code target/failsafe-reports} for integration-test results. CI report consumers must use those
 * stable locations. Nothing in this package may relocate them, because a test run can remain green
 * while an external publisher silently collects no results.
 *
 * <h2>The label canon</h2>
 *
 * <p>Assumptions: {@code docs/CODE_DOCUMENTATION_STANDARD.md} is the authority for the rationale
 * labels the three sibling classes use, and this charter is a pointer to it rather than a
 * restatement of it. Its section at line 216 fixes the four labels -- {@code Alternatives
 * Considered:}, {@code Refactoring Rationale:}, {@code Assumptions:} and {@code Trade-offs:} -- in
 * one written form: plural, unparenthesised, colon retained, no emphasis markup, and never mixed
 * inside one file. Label text is taken from that document and from nowhere else, because the older
 * reference material writes the same words with non-breaking hyphens in 106 places, and a label that
 * is byte-different is a rationale a literal search cannot find -- which is a rationale a review
 * cannot count. Inside a Javadoc block there is no line comment to mark, so the equivalent of an
 * adjacent rationale is a prose sentence opened by one of the four labels, which is the form every
 * rationale in this file uses.
 *
 * <h2>Decision record</h2>
 *
 * <p>Assumptions: this is the only {@code package-info.java} in this module's test tree above the
 * leaf packages, and the four places that do not have one are empty on purpose rather than by
 * oversight. There is no such file at {@code src/test/java}, none at {@code com}, none at
 * {@code com/carddemo} and none at {@code com/carddemo/account}. The mechanism is the pairing of two
 * modules that sit on opposite sides of the Checkstyle architecture. {@code JavadocPackage}, at
 * {@code config/checkstyle/checkstyle.xml} line 245, is a file-set check that fires only for a
 * directory containing a processed {@code .java} file; those four directories hold subdirectories
 * and nothing else, so it has nothing to fire on in any of them. {@code MissingJavadocPackage}, at
 * line 378 of the same file, only ever inspects a {@code package-info.java} that already exists.
 * Neither check therefore asks for a descriptor at those depths, and the project Explainability rule
 * has no entry point to attach one to either, because a directory that declares no package is not a
 * module entry point. Two already-landed trees corroborate this at the identical relative depth:
 * {@code services/auth-service/src/test/java} carries a descriptor at
 * {@code .../auth/api} and at {@code .../auth/repository} and at no shallower level, holding zero
 * files of its own at each of its four upper depths; and
 * {@code services/common-lib/src/test/java/com/carddemo/common} holds three subdirectories and zero
 * files, so it too carries none. Adding a second descriptor anywhere above this one would document
 * a package that declares nothing.
 *
 * <p>Assumptions: this file must not be emptied, and the asymmetry that makes an empty one dangerous
 * is worth stating because the two checks fail in opposite directions. An empty
 * {@code package-info.java} holding nothing but a bare {@code package} statement <b>satisfies</b>
 * {@code JavadocPackage}, because that module asserts only that the file exists and, in the words of
 * its own note at {@code config/checkstyle/checkstyle.xml} lines 238 to 240, "does not read what is
 * in it" and would pass a file "holding nothing but an ordinary block comment". The same file's note
 * at lines 370 to 376 records the other half: {@code MissingJavadocPackage} asserts that the
 * declaration carries a Javadoc block, so an empty file <b>fails</b> it. Remove either module and
 * the requirement is half-enforced; empty this file and the build stops. A future reader tempted to
 * simplify this descriptor down to its {@code package} line would be removing the only thing the
 * second module is looking for.
 *
 * <p>Trade-offs: the documentation gate runs before compilation, on test sources as well as main
 * sources, and on every local build rather than only in a pipeline. The execution is
 * {@code checkstyle-documentation-gate} at {@code services/pom.xml} line 729, bound to the Maven
 * {@code validate} phase on line 730 with the {@code check} goal on line 732, and configured
 * with {@code failOnViolation} true on line 819, {@code violationSeverity} warning on line 820 and
 * {@code includeTestSourceDirectory} true on line 847; the engine is pinned to Checkstyle 13.8.0 on
 * line 373. Three consequences follow for this file specifically. A missing Javadoc block here stops
 * the build before javac has read one character, so the failure names a documentation requirement
 * rather than a compilation error. The threshold of warning means there is no tolerated middle band:
 * a warning is a failure. And because {@code validate} is the first phase of the lifecycle, a build
 * that skips tests still runs this gate -- skipping tests suppresses Surefire, which is bound far
 * later, and cannot suppress {@code validate}, so the image build that this module's planned
 * {@code Dockerfile} performs over the jar described at
 * {@code services/account-service/pom.xml} lines 64 to 65 is still audited against this file even
 * though it never executes a single test in it. The compromise accepted is a slower inner loop for a
 * documentation rule that cannot quietly rot, and this module's own descriptor states the cost in
 * the same terms at {@code services/account-service/pom.xml} lines 68 to 73, where the build is
 * recorded as failing "deliberately and with no tolerance" on a missing or incomplete Javadoc
 * comment, with "No suppression, no relaxation and no severity downgrade of that gate" appearing
 * anywhere in that file "and none may be added".
 *
 * <p>Assumptions: there is no in-code bypass, so satisfying the gate is the only way through it.
 * {@code config/checkstyle/checkstyle.xml} lines 588 to 595 record that
 * {@code SuppressWarningsFilter}, {@code SuppressionCommentFilter} and
 * {@code SuppressWithNearbyCommentFilter} are all deliberately absent, on the ground that each would
 * let the gate "be switched off line by line, across any number of files, with no single artifact
 * anywhere showing that it had been switched off". The practical effect is that a
 * {@code CHECKSTYLE:OFF} comment and a suppression annotation are both inert here: they are not
 * discouraged, they simply do nothing. The one filter that is configured is the file-based
 * {@code SuppressionFilter} at lines 226 to 229, whose {@code optional} flag is false, so the
 * charter it reads is fail-closed and a missing charter is an error rather than a silent
 * pass-everything. That charter, {@code config/checkstyle/suppressions.xml}, contains exactly two
 * entries: generated sources at lines 213 to 214 and test fixture material at lines 250 to 251.
 * {@code src/test/java} is deliberately not among them, and its own note at lines 237 to 238 states
 * that the fixture entry "must never be widened to src/test/java/", because doing so "would exempt
 * every controller, service and repository test in all nine modules". Lines 245 to 246 of the same
 * file close the loop, recording that {@code includeTestSourceDirectory} is set true so that those
 * tests are audited. This package is inside the gate by design, and the only route to a green build
 * is to meet it.
 *
 * <h2>What the sibling classes owe</h2>
 *
 * <p>Assumptions: no annotation and no visibility earns an exemption in this package.
 * {@code MissingJavadocType}'s skipped-annotations list is left at its default, so
 * {@code @WebMvcTest}, {@code @Test}, {@code @Nested}, {@code @DisplayName} and a mocked-bean
 * annotation confer nothing; and {@code MissingJavadocMethod} is configured at private scope with its
 * allowed-annotations list cleared, so a private helper and an overriding method each need a block of
 * their own. Every class, every test method and every private helper here therefore carries Javadoc,
 * with a parameter tag for each parameter and for each component of any nested record, a return tag
 * on each method that returns a value, and a throws tag for each exception a signature declares.
 *
 * <p>The one place the machine asks for less than the rule is fields: {@code JavadocVariable} is
 * deliberately absent, recorded at {@code config/checkstyle/checkstyle.xml} lines 563 to 567 on the
 * ground that the rule scopes its docstring requirement to functions, classes and module entry points
 * and a field is none of the three. A MockMvc field, an object mapper or a mocked collaborator
 * therefore needs no block, and a field that genuinely needs explaining takes an adjacent rationale
 * comment instead. This is recorded so that nobody gold-plates it and nobody mistakes the absence for
 * an oversight.
 *
 * <p>Layering is not this package's concern and must not be re-declared in it.
 * {@code config/checkstyle/checkstyle.xml} lines 605 to 619 record that {@code ImportControl} is
 * deliberately excluded because layering has exactly one owner: the ArchUnit rules that
 * {@code services/pom.xml} lines 996 to 1012 scan into every module through a Surefire execution
 * named {@code architecture-rules}, selected there by the simple name {@code LayeringRulesTest}. A
 * second engine enforcing an overlapping half of one constraint would leave a reader unable to tell
 * which of the two owned a given boundary.
 *
 * <h2>This gate is binary</h2>
 *
 * <p>Maven, Checkstyle, Surefire and JUnit each report pass or fail and nothing between.
 * {@code services/pom.xml} lines 69 to 71 state it directly: the exit status of this build "is
 * BINARY: it either passes or it fails. There is no tolerated warning level." The graded return-code
 * rubric of five codes aggregated to the worst code seen, documented in section 8 of
 * {@code tests/README.md} from line 412, governs that suite alone and has no application to this
 * module. No build of this package is ever reported as having passed with tolerated warnings,
 * because the violation threshold is warning and therefore no such state exists for it.
 *
 * <p>Assumptions: {@code tests/README.md} and this file describe two different test trees, and
 * conflating them would misattribute both. That file documents the pre-existing COBOL
 * functional-parity oracle suite -- 590 lines describing a three-layer suite of COBOL unit,
 * single-program integration and golden-master end-to-end tests -- and it, along with everything it
 * runs, is REFERENCE-ONLY and is neither modified nor re-pinned by this migration. This tree is the
 * Java module's own test tree, it is additive, and it replaces nothing there. What the two do share
 * is the obligation: that file states at its lines 544 and 549 that every new test, fixture builder,
 * helper, mock and runner routine must carry a docstring and that this "is a hard review gate",
 * which is the same conclusion the project Explainability rule reaches at its line 43 from the other
 * direction.
 *
 * <h2>Why this descriptor exists at all</h2>
 *
 * <p>The project Explainability rule requires a docstring on every module entry point at its line
 * 15, and in Java the entry point of a package is the package declaration.
 * {@code package-info.java} is the only compilation unit able to carry package-level Javadoc, which
 * makes this file load-bearing rather than decorative;
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} names it in exactly those terms at its lines 338 to
 * 339, as the charter that is the module entry point for Java. That clause is the sole reason this
 * file exists: the migration requirements alone would have produced three controller tests and no
 * descriptor beside them.
 *
 * <p>Assumptions: one structural consequence explains an omission that would otherwise resemble the
 * very thing the rule forbids. This compilation unit contains a single statement, so the decision
 * rationale the rule's validation gate asks for has no adjacent executable line to sit beside; it is
 * carried inside this block under the rule's own category labels, which is the only placement a
 * package makes available. The parameter, return-value and exception elements of the rule's
 * docstring specification, at its lines 19 to 21, describe callable code, and a package declaration
 * is not callable -- it accepts no argument, yields no value and raises nothing. Those three elements
 * are therefore omitted deliberately rather than written out empty, and the omission is not a
 * stylistic preference: {@code NonEmptyAtclauseDescription} at
 * {@code config/checkstyle/checkstyle.xml} line 470 reports an at-clause carrying no description as
 * a violation in its own right, so an empty tag would fail the very gate it was added to satisfy.
 */
package com.carddemo.account.api;
