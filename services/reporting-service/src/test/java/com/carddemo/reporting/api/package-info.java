/**
 * Test-tree charter for the REST adapter package of the reporting and statement context, holding the
 * request-pipeline tests over its two controllers and the contract test that binds their published
 * surface to the document describing it.
 *
 * <p>Purpose: the classes beside this charter assert TRANSPORT behaviour and nothing beneath it. What
 * arrives at a controller is a request body, a request line and a set of authorities; what leaves it is
 * a status code, a media type, a header set and a serialised envelope. Those outputs are decided by
 * argument binding, by declarative validation, by the shared exception advice and by the serialiser
 * configuration, and not one of them is visible to a service unit test, which is handed arguments that
 * are already bound, nor to a repository integration test, which asserts a query rather than a
 * response. The cases here are the only ones in this module that watch a request become a response.
 *
 * <p>This is the TEST-tree charter for the package name. The production charter at
 * {@code services/reporting-service/src/main/java/com/carddemo/reporting/api/package-info.java} owns
 * the controller roster, the transport-only boundary, the statelessness ruling and the data-exposure
 * rules, and it is CITED here rather than restated. Assumptions: a contract stated once can be revised
 * in one place, whereas the same contract stated in two charters drifts until the two disagree, so
 * anything true of the production package is read there and only the test-tree obligations are settled
 * here.
 *
 * <h2>What this directory holds</h2>
 *
 * <p>The inventory below is a MEASUREMENT of this directory rather than a plan for it, and
 * {@code PackageCharterInventoryTest} under
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/} re-measures it on every
 * build. That test parses the marker line, counts the {@code .java} files beside this charter, and
 * additionally requires every name enumerated below to be a file in this same directory, so a class
 * arriving without an entry here and an entry naming a class that has moved both fail the build instead
 * of ageing quietly:
 *
 * <pre>
 * this directory: 4 java files = 3 tests + 1 charter
 * </pre>
 *
 * <ul>
 *   <li>{@code ReportControllerTest} exercises {@code ReportController}: how a report request binds,
 *       which refusals it publishes, what a submission answers with, and how a stored artifact is
 *       streamed. Its reference is the online report-request transaction {@code app/cbl/CORPT00C.cbl},
 *       whose screen is {@code app/bms/CORPT00.bms} over the symbolic map
 *       {@code app/cpy-bms/CORPT00.CPY}.</li>
 *   <li>{@code StatementControllerTest} exercises {@code StatementController}: how a statement is
 *       requested, which of the two published renderings is selected by the negotiated media type, and
 *       what a retrieval discloses. Its references are the statement generator
 *       {@code app/cbl/CBSTM03A.CBL} with its file-handling subprogram {@code app/cbl/CBSTM03B.CBL},
 *       over the record layout {@code app/cpy/COSTM01.CPY}.</li>
 *   <li>{@code ReportingApiContractTest} compares two descriptions of one surface: the committed
 *       contract document against the route table the mapping annotations declare, in both directions,
 *       so a published route with no handler and a handled route with no publication are each a build
 *       failure. It reads the contract from the CLASSPATH rather than through a source path, so the
 *       assertions run against the artifact the service actually publishes.</li>
 * </ul>
 *
 * <p>Assumptions: the sibling classes named throughout the sections below live in OTHER packages and
 * are therefore written into prose rather than into the list above. The inventory test holds every name
 * it finds in a list entry to being a file in THIS directory, so citing a neighbour's class in that
 * position would fail the build while saying something perfectly true. The list is the closed set of
 * this directory; a neighbour is a citation.
 *
 * <p>Trade-offs: the marker line is adopted and the per-class case counts that the same test can also
 * verify are not. The file and member figures move only when a file is added or removed, which is
 * exactly the drift worth catching, whereas a per-class count of cases moves every time any neighbour
 * gains one, which would turn this charter into a build-breaking coupling to three other files for a
 * figure nothing asks it to publish.
 *
 * <h2>How these tests are built</h2>
 *
 * <p>Each controller test stands a real request pipeline up around a hand-constructed controller
 * through a standalone MockMvc setup, registering the handler, the argument resolvers, the message
 * converters and the shared exception advice, and it loads no application context at all. Its
 * collaborators are plain mocks: the report side holds the execution service, the transaction report
 * service and the artifact store; the statement side holds the statement service and the artifact
 * store.
 *
 * <p>Alternatives Considered: a sliced web context, which is the form the migration plan names for a
 * controller test, and which was evaluated and REJECTED for this module. The obstacle is concrete and
 * is the reason the rejection is recorded here rather than left to be rediscovered.
 * {@code com.carddemo.reporting.config.JwtDecoderConfig} declares the token decoder bean itself,
 * building it from {@code NimbusJwtDecoder.withIssuerLocation} over the issuer property
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, so that bean resolves the issuer's
 * discovery document over the network at bean-creation time. ANY context that includes the
 * configuration package therefore fails in an isolated environment for a reason unrelated to the
 * controller under test. A standalone pipeline registers everything these assertions are about and
 * excludes exactly the one bean that cannot be built here. The rejected alternative would have made a
 * transport test depend on network reachability and on a credential this repository must never hold, so
 * no test in this package may be pointed at a real or stand-in issuer.
 *
 * <p>Assumptions: {@code services/reporting-service/src/test/resources/application-test.yml} declares
 * that issuer property EMPTY rather than leaving it absent, and the distinction is deliberate on its
 * side: an inherited placeholder with no fallback, left for the framework to resolve, aborts a context
 * refresh while bean definitions are still loading and reports the condition that read it rather than
 * the value that was missing. That document also declares no orchestration resource identifier and no
 * object-store settings, from which it follows that every context-loading test in this module is an
 * integration test in the sibling {@code com.carddemo.reporting.repository} test package, carrying the
 * {@code IT} suffix and {@code @ActiveProfiles("test")}. No context-loading test exists in THIS tree
 * and none may be added here. Refactoring Rationale: this sentence previously fixed the number of those
 * tests at two, which was already behind the tree it described and would have gone stale again with the
 * next one added. The number was never the point -- what this package needs from that document is the
 * one-sided property that none of them is HERE, which is checkable by grepping this directory for
 * {@code @SpringBootTest} and {@code @ActiveProfiles} and finding neither.
 *
 * <p>Assumptions: the shared exception advice is registered on the pipeline explicitly rather than
 * relied upon, and that registration is itself the subject of several cases. A standalone pipeline
 * resolves no controller advice from a context, so without it a refusal would surface as a raw servlet
 * error and the status and body this context publishes would go unverified. The money module is
 * registered on the response converter for the same class of reason: without it a monetary amount would
 * serialise as a bare JSON number, which is the one encoding the published contract forbids, and a test
 * that omitted it would assert against a payload no deployed service emits. Money travels as a JSON
 * STRING at this boundary so that no client can route it through an IEEE-754 binary64 representation,
 * which is what a JSON number is parsed into by most clients and what destroys exactness at the one
 * boundary a user actually sees. The prohibition on the corresponding Java primitives is owned by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 * which is neither relocated nor duplicated here.
 *
 * <h2>How these tests are collected</h2>
 *
 * <p>Every class in this package ends {@code Test}, which is what makes Surefire collect it. The
 * collection patterns are Surefire's own defaults, because {@code services/pom.xml} declares
 * {@code includes} for one execution only -- {@code architecture-rules}, narrowed at its L1191 to
 * {@code **}{@code /LayeringRulesTest.java} -- and leaves the default test execution untouched. Read
 * from the resolved descriptor of {@code maven-surefire-plugin} 3.5.6, those defaults are four
 * patterns: {@code Test*}, {@code *Test}, {@code *Tests} and {@code *TestCase}. A name outside all four
 * -- {@code *Spec} is the likeliest -- is collected by nothing and silently never runs, which is the
 * worst available outcome for a class whose job is to hold a must-be-green assertion: the build stays
 * green while the cases never run, and such a class looks like coverage indefinitely, whereas a deleted
 * one at least leaves a diff behind. Refactoring Rationale: this paragraph previously grouped
 * {@code Tests} and {@code TestCase} with {@code Spec} as suffixes that are "silently NOT collected".
 * Those two ARE collected by the defaults above, and the error was the dangerous way round: a
 * maintainer could rename a class to {@code ...Tests} in the belief that this stops it running, or
 * discount the warning wholesale on discovering half of it false and then hit the half that is true.
 * The plural-free {@code Test} suffix remains this package's convention, for uniformity rather than
 * because the alternatives are uncollected.
 *
 * <p>The {@code IT} suffix belongs to Failsafe and never appears here; Failsafe's defaults, from the
 * same descriptor set, are {@code IT*}, {@code *IT} and {@code *ITCase}, it runs after packaging and it
 * asserts its results in the verify phase -- so a container-backed class named {@code Test} would start
 * a container inside the unit-test phase and a unit test named {@code IT} would run late or not at all.
 * Nothing in this package overrides the collection patterns, the report directory or the output
 * redirection, and no case is skipped, ignored or allowed to fail.
 *
 * <h2>Which assertions live here, and which do not</h2>
 *
 * <p>Trade-offs: this package proves transport contracts only, and the accepted compromise is that a
 * behavioural regression is caught by a sibling package rather than here. The boundary is drawn
 * deliberately, because a controller test that re-derived business behaviour over mocked collaborators
 * would assert the behaviour of its own mocks. What is owned elsewhere:
 *
 * <ul>
 *   <li>The calendar-range cases belong to {@code ReportExecutionServiceTest} in the sibling
 *       {@code com.carddemo.reporting.service} test package, the one class in this module that pins an
 *       injectable clock and uses it. Among them is the ruling that the monthly preset resolves a WHOLE
 *       calendar month rather than a month-to-date range, asserted over a year roll and a leap
 *       February. {@code ReportController} declares no clock of any kind; the clock is a constructor
 *       parameter of the execution service.</li>
 *   <li>The report line being exactly 133 columns with byte-exact edit masks belongs to the sibling
 *       {@code com.carddemo.reporting.mapper} test package, and specifically to
 *       {@code TransactionReportMapperTest}, whose {@code REPORT_RECORD_LENGTH} is asserted on the
 *       header, the separator rule, the blank band, the detail line and every band of the emitted
 *       block, and to {@code ReportBandLayoutsTest}, whose {@code DECLARED_RECORD_LENGTH} pins the same
 *       width against the three places the baseline declares it. Refactoring Rationale: this entry
 *       previously named {@code TransactionReportServiceTest} as the owner. That class contains no
 *       occurrence of the width at all -- the mapper owns the assembly and the service owns the
 *       selection -- so a reader who went looking for the control where this list sent them would have
 *       found none and could reasonably have concluded the control was missing rather than misfiled.</li>
 *   <li>Correct statement output for one card carrying more transactions than the baseline's inner table
 *       admits, and for more distinct cards than its outer table admits, belongs to
 *       {@code StatementServiceTest}, at
 *       {@code aCardPastTheBaselineInnerTableThresholdStatementsEveryTransaction} and
 *       {@code aRunPastTheBaselineOuterTableThresholdStatementsEveryCard}. Refactoring Rationale: this
 *       entry named that class before either case existed, so it described intent as though it were
 *       coverage. Both cases now exist and are named individually here rather than by class, because
 *       naming the class is what let the claim survive their absence. They drive 513 transactions and 52
 *       distinct cards -- the first value past each measured threshold rather than a comfortable figure
 *       above it -- and they assert that the migrated service imposes NO arity, which is divergence
 *       {@code D-2} in {@code docs/architecture/cobol-to-service-traceability.md} and not a repair of
 *       {@code app/cbl/CBSTM03A.CBL}, which is reference-only and unchanged.</li>
 *   <li>This module's login role being provably unable to write, and no migration directory or
 *       migration tooling existing anywhere in it, belongs to {@code ServiceCatalogInventoryTest} for
 *       the directory census, to {@code DataSourceConfigTest} in the sibling
 *       {@code com.carddemo.reporting.config} test package for the read-only pool posture, and to
 *       {@code ReportingQueryBootstrapIT} and {@code StatementHeadingChunkIT} for the privileges
 *       themselves against a real engine.</li>
 * </ul>
 *
 * <p>Assumptions: no golden master reaches this package. {@code tests/README.md} records at L83 to L85
 * that the online CICS programs cannot be run end to end without a CICS runtime, which is absent on the
 * runner, so only their extractable field-validation logic is unit-tested; the report-request program
 * this package's larger test replaces is one of those online programs, so no golden output for it
 * exists to compare against. The batch report and statement writers DO have golden masters under
 * {@code tests/golden/}, and those belong to the sibling service test package rather than to this one.
 * Nothing here may claim a golden comparison it cannot perform.
 *
 * <h2>Divergence ownership</h2>
 *
 * <p>This test package cites NO behavioural divergence. The record-key divergence carried by
 * {@code app/cbl/CBEXPORT.cbl} and {@code app/cbl/CBIMPORT.cbl}, and the interest-accrual divergence
 * carried by {@code app/cbl/CBACT04C.cbl}, both belong to {@code batch-service} and may not be claimed
 * here. The statement divergence -- the baseline declares two independent tables of fixed arity, and
 * the Java carries no fixed arity anywhere -- is cited only from the sibling service test package and
 * from the statement response type on the production side.
 *
 * <p>Assumptions: the two statement thresholds must never be merged into a single figure, and the
 * warning is recorded here so that a future author in this package does not adopt the conflated phrase
 * from elsewhere. {@code tests/README.md} states across L70 to L82 that there are TWO independent
 * unchecked tables and warns at L71 to L72 that there is therefore no single fifty-one-transaction
 * limit, that figure conflating the two. The measured boundaries are a same-card table that renders 512
 * transactions and overruns on the 513th, and a distinct-card table declared to hold 51 that overruns
 * on the 52nd. The number of transactions a fixture holds, the same-card boundary and the distinct-card
 * boundary are three separate quantities.
 *
 * <h2>Baseline contracts these tests assume</h2>
 *
 * <p>Assumptions: THREE message-width regimes exist on this screen and all three are correct. The
 * baseline's own working buffer is declared {@code 05 WS-MESSAGE PIC X(80)} at
 * {@code app/cbl/CORPT00C.cbl} L39. The screen field is declared {@code ERRMSGI PIC X(78)} at
 * {@code app/cpy-bms/CORPT00.CPY} L120 and {@code ERRMSGO PIC X(78)} at L224 of the same file,
 * corroborated by the {@code ERRMSG} stanza in {@code app/bms/CORPT00.bms}, which carries
 * {@code LENGTH=78}. The carriers that cross a program boundary are declared
 * {@code CCARD-ERROR-MSG PIC X(75)} and {@code CCARD-RETURN-MSG PIC X(75)} at
 * {@code app/cpy/CVCRD01Y.cpy} L28 and L29. The 75-character message is left-justified and
 * blank-padded into the 78-character field. These three are NOT merged and none is treated as an error
 * in the other two.
 *
 * <p>Assumptions: the shared kernel's error package documents a wider set of four width regimes
 * spanning the whole kernel. That is a different SCOPE rather than a contradiction of the three above:
 * this screen carries three, and the 75-character width is the shared boundary both sets own. Recorded
 * so that nobody reconciles the two sets by deleting a width from either.
 *
 * <p>Assumptions: an absent value and a blank value are distinguished at this boundary and are never
 * merged, because both states genuinely arise on this screen. {@code app/cpy/CVCRD01Y.cpy} L30 declares
 * a condition name whose value is the low-value sentinel, and it attaches to the RETURN message at L29
 * only; the error message at L28 carries no sentinel at all. The reason both states occur is visible in
 * {@code app/bms/CORPT00.bms}: the three report-type flags each carry an explicit single-space initial
 * value, while the confirmation field carries no initial clause whatsoever. That is precisely why
 * {@code app/cbl/CORPT00C.cbl} tests the report-type flags against both states at L213, L239 and L256
 * and tests the confirmation field against both states at L464. A response therefore distinguishes an
 * absent member from a blank one, and a case that collapsed the two would pass while describing a
 * screen this one is not.
 *
 * <p>⚠️ Assumptions: {@code app/cpy/CVCRD01Y.cpy} carries a parsing hazard that no test in this package
 * may walk into. An asterisk in column 7 comments out L20, L22, L25, L26, L27, L31, L32 and L33, which
 * between them declare a last-program field, a return-to-program field, a return-flag field with both
 * of its condition names, and a function field with both of its condition names. Those fields DO NOT
 * EXIST and no test here may reference one. The live neighbours are the attention-identifier field at
 * L3, declared {@code PIC X(5)} and carrying sixteen condition names across L4 to L19 for enter, clear,
 * the two program-access keys and the twelve function keys; the next-program field at L21; the
 * next-mapset and next-map fields at L23 and L24; the two 75-character message carriers at L28 and L29;
 * and the sentinel at L30.
 *
 * <p>Assumptions: three fields in that same copybook are declared as characters with a numeric
 * redefinition over them -- an account identifier at L34 and L36, a card number at L37 and L39, and a
 * customer identifier at L40 and L42, each {@code PIC X(n)} redefined as {@code PIC 9(n)}. The baseline
 * treats them as characters on the wire and as numbers only inside arithmetic, so every one of them
 * travels through this transport boundary as a digits-only STRING and never as an integer. That is the
 * package-wide typing assumption for these three members, and a case asserting an integral JSON member
 * for any of them would contradict the layout it claims to follow.
 *
 * <h2>Shared kernel, consumed and never re-declared</h2>
 *
 * <p>The cases here consume the shared kernel and re-declare no part of it: the problem shape, the
 * global exception advice and the structured abend detail from {@code com.carddemo.common.error}; the
 * page envelope and the correlation filter from {@code com.carddemo.common.web}; the monetary type and
 * its serialisation module from {@code com.carddemo.common.money}; the authority converter from
 * {@code com.carddemo.common.security}; the field-validation flag and the date-edit validator from
 * {@code com.carddemo.common.validation}; and the timestamp formatter from
 * {@code com.carddemo.common.time}. Assumptions: this is the Java analogue of compiling every baseline
 * program against a single copybook include path -- one contract, one owner, one place to revise it --
 * which is why a shared concern is consumed from the kernel and never restated inside a service.
 *
 * <p>⚠️ Assumptions: the structured abend fields the error package models are declared at
 * {@code app/cpy/CSMSG02Y.cpy} L21 to L29, where the group item sits at L21 with a 4-character code at
 * L22, an 8-character culprit at L24, a 50-character reason at L26 and a 72-character message at L28.
 * A citation of L45 to L53 for those fields is DEAD and must not be propagated from any document that
 * carries it: the file is 35 lines long, so that range does not exist. One artifact of the same file is
 * worth recording alongside it, because it misleads a reader searching by name: the header comment at
 * L2 names the copybook {@code CABENDD.CPY} rather than the name the file is stored under.
 *
 * <p>Assumptions: paging at this boundary is expressed by key through the kernel's page envelope, which
 * carries the boundary keys and a next-page indicator, and never by a positional index. A case that
 * asserted a positional page selector would describe a paging strategy this system does not implement
 * and would pass while doing so.
 *
 * <h2>Test classpath</h2>
 *
 * <p>Present and used: the Spring Boot test starter, supplying the test engine, the mocking framework,
 * the fluent assertions and the mock request pipeline; the Spring Security test support; the
 * Testcontainers PostgreSQL and test-engine bindings and the Boot Testcontainers support, all consumed
 * by the sibling integration tests rather than here; the architecture-rule engine; and the
 * orchestration and object-store clients from the production side, which are reachable as mocks.
 *
 * <p>Absent BY DESIGN, which is an affirmative statement of this module's shape rather than an
 * omission: any AWS emulator or emulator container binding; schema-migration tooling of any kind, this
 * module owning no relational object and having no migration directory, not even a key disabling
 * migration; the batch framework; any queue starter; an accessor generator; a mapping generator; any
 * resilience or circuit-breaker library; and any cache or streaming platform.
 *
 * <p>Assumptions: no test dependency in this module declares a version. Every one of them resolves
 * through the Spring Boot parent that the aggregator inherits, or through the Testcontainers bill of
 * materials it imports, so a version written here would be a second place to maintain one decision and
 * would silently diverge from the managed one.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>This file exists because user-specified Rule 1 (Explainability) requires a docstring on every
 * module entry point at its line 15, a Java package declaration IS one, and a
 * {@code package-info.java} Javadoc block is the only construct able to carry it. It is in scope for
 * that reason and no other: it appears nowhere in the technical specification's transformation mapping.
 * Of the four content elements the rule enumerates -- purpose at its line 18, parameters at 19, return
 * values at 20 and exceptions or errors at 21 -- only Purpose applies here. A package declaration
 * accepts no parameter, yields no value and raises nothing, so the other three are inapplicable rather
 * than omitted, and no at-clause is invented to stand in for one of them. Assumptions: an invented
 * at-clause would be worse than the omission it disguised, because the completeness check this build
 * runs rejects an at-clause carrying no description, so the empty tag would fail the very gate it was
 * added to satisfy. The obligation this discharges in written form is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}.
 *
 * <p>The obligation cited is the rule's VALIDATION GATE at its line 43 rather than the softer wording
 * earlier in the rule, and the gate is CONJUNCTIVE: it requires the docstring and it separately requires
 * a why-comment on every non-obvious decision, closing with the ruling that code missing either one
 * fails review. Both halves are discharged here, and the second one needs explaining because its usual
 * form does not fit. The rule asks that a comment sit ADJACENT to the code it explains, and a package
 * declaration has no code beside it, so every decision recorded in this charter is carried INSIDE this
 * block as a labelled sentence, each tagged with at least one of the four categories the rule names at
 * its lines 31 to 34. Assumptions: each label is written bare, plural where the rule writes it plural,
 * singular for the refactoring category the rule states in the singular, colon-terminated and free of
 * emphasis markup, because the rules document renders those labels with the colon inside its own
 * emphasis markers -- so the colon belongs to the label while the markers belong to that document's
 * formatting and must never be copied into Java. Two neighbouring registers were deliberately NOT
 * adopted: the parenthesized singular form used by the shell runners under {@code scripts/}, and the
 * paired what-and-why comment idiom used in the fenced examples of {@code tests/README.md}, whose
 * label text additionally carries a non-breaking hyphen at that file's L548, so lifting it would carry
 * an invisible character into a Java source.
 *
 * <p>Alternatives Considered: creating this file as a bare package statement, which is the form that
 * looks sufficient and is not. Two checks in {@code config/checkstyle/checkstyle.xml} act on it and
 * they are not redundant. {@code JavadocPackage} is declared at Checker level and is a file-set check:
 * it asserts the FILE EXISTS for every directory holding audited Java sources. {@code
 * MissingJavadocPackage} is declared inside TreeWalker and asserts the file CARRIES a Javadoc block. An
 * empty or comment-free {@code package-info.java} satisfies the first and FAILS the second, so the
 * obligation is to create the file AND to give it a real block. {@code services/pom.xml} binds the gate
 * to the Maven {@code validate} phase with the engine pinned to 13.8.0, at a violation severity of
 * warning and failing the build on any violation, so both checks fire on every local build BEFORE the
 * compiler runs and not only in continuous integration.
 *
 * <p>Assumptions: those checks reach this tree because the gate is configured to include the test
 * source directory, which was established by observation rather than by inference. The setting is
 * declared at {@code services/pom.xml} line 994, this module's own build file does not override it,
 * and rendering the effective build file for this module resolves it to true inside the documentation
 * gate execution. That value is what makes this FILE mandatory rather than merely conventional.
 * Refactoring Rationale: it was read from the declaring build file and then confirmed against the
 * rendered effective one, because the sibling charter in the service test package records that the
 * effective-build-file rendering could not be executed in this environment; the plugin that renders it
 * now resolves offline, so the stronger reading is available and was taken.
 *
 * <p>Assumptions: both halves of the interlock were then observed FIRING on this very directory, because
 * a gate that has never been seen to fail is indistinguishable from one that cannot. Removing this file
 * and running the validate phase fails the build reporting {@code JavadocPackage} as a missing
 * {@code package-info.java} file, and it is reported against a SIBLING test class at its line 1 rather
 * than against this path, since a missing charter is a property of the directory rather than of any one
 * file. Reducing this file to its package declaration alone fails the build reporting
 * {@code MissingJavadocPackage} against this file at line 1, column 1. Restoring the block returns the
 * audit to zero violations. Assumptions: repeating that experiment requires clearing the plugin's
 * incremental cache first, and skipping that step inverts the result: the plugin audits only what it
 * considers changed, so a second run over an otherwise untouched tree audits nothing and passes,
 * including the run in which this charter has just been removed. Trade-offs: the cache is worth keeping
 * for the build time it saves, and the accepted cost is that it makes the gate look absent to anyone
 * testing it casually, so the honest check clears the cache file or runs from a clean tree and reads the
 * audited-file count rather than only the build status.
 *
 * <p>⚠️ The distinction that must not be lost: the FILE requirement is the conditional half. The Rule 1
 * docstring obligation on the three test classes beside this charter is UNCONDITIONAL, and no value of
 * that setting relaxes it. A configuration flag governs which files a linter reads; it does not govern
 * what the rule requires of the code.
 *
 * <p>Assumptions: nothing under {@code src/test/java} is exempt from any of this.
 * {@code config/checkstyle/suppressions.xml} admits exactly two path patterns, one for generated
 * sources under a build directory and one for fixture material under
 * {@code src/test/resources/fixtures/}, and its filter is declared fail-closed, so a missing
 * suppressions file is an error rather than a silent allow-all. The second pattern covers a RESOURCES
 * path and not a {@code src/test/java} path, a distinction that file itself records as the boundary
 * that matters most about it, since widening the entry would exempt every controller, service and
 * repository test in every module. Every class and every member in this package is therefore fully
 * gated.
 *
 * <p>Assumptions: the ten checks that gate this package are worth naming, so that a future author knows
 * what will refuse a change. {@code MissingJavadocType} runs at private scope over interfaces, classes,
 * enums, annotation types AND records, so a test-local record is covered; {@code MissingJavadocMethod}
 * runs at private scope with its allowed-annotation list emptied, which withdraws the default exemption
 * for an overriding method, so an override must be documented for the linter exactly as Rule 1 already
 * required; {@code MissingJavadocPackage} governs this file; {@code JavadocType} requires a parameter
 * at-clause for every record component on the type's own block; {@code JavadocMethod} validates
 * parameters, return values and declared exceptions across every visibility;
 * {@code NonEmptyAtclauseDescription} refuses an at-clause with no prose; {@code SummaryJavadoc}
 * refuses placeholder markers, Rule 1's own two examples of a vague rationale, and an accessor-shaped
 * opening; {@code AtclauseOrder} fixes the order of the at-clauses; and
 * {@code CommentsIndentation} governs comment placement. Two property names are easy to transpose and
 * are not interchangeable: the missing-Javadoc checks filter with a scope property, while
 * {@code JavadocMethod} filters with an access-modifiers property.
 *
 * <p>⚠️ Assumptions: the summary check's terminator behaves in a way that shapes every sentence in this
 * block. Its period property is deliberately left at the default, and the documented behaviour is that
 * a period NOT followed by whitespace is ignored, so a file name or a picture clause does not end a
 * sentence. This charter is dense with such citations, so every summary sentence in it ends with a
 * period followed by whitespace or a line break; a sentence terminated by a period tight against the
 * next character would leave the check unable to see a terminator at all.
 *
 * <p>Assumptions: there is NO in-code bypass of this gate, and that absence is load-bearing rather than
 * incidental. The rule set configures no suppression filter driven by an annotation, by a comment or by
 * a nearby comment, so a suppression cannot be written into a Java source at all; every exemption is a
 * durable entry in the suppressions file where a reviewer can read the complete list in one place.
 * Neither a magic comment nor a warning-suppression annotation belongs in this package, and neither
 * would have any effect if added. Four further absences are equally deliberate: no check requires a
 * one-line Javadoc form, no check requires a docstring on a field, the Javadoc FORMATTING family is
 * absent so authorship, version and availability tags are never required and are not written here, and
 * no import-control check exists because layering has exactly one owner in the architecture test named
 * earlier.
 *
 * <p>Refactoring Rationale: an earlier revision of this charter described only the contract test and
 * omitted the two controller tests entirely, published no measured inventory of the directory, and
 * recorded none of the baseline contracts the cases here depend on. A charter that enumerates a
 * fraction of its directory reads as a complete roster, so the omission actively misinformed rather
 * than merely underdescribed: a reader would conclude the two request-pipeline tests did not exist and
 * would author one beside those that do. The inventory is now a marker line the build re-measures, so
 * the same drift cannot recur silently.
 */
package com.carddemo.reporting.api;
