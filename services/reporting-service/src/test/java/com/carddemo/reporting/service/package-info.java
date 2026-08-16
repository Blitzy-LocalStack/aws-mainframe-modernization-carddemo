/**
 * Unit tests for the business behaviour of the reporting bounded context: the two report writers, the
 * statement composer, and the request edge that submits a run and reports what became of one.
 *
 * <p>Purpose: the services in this context sit between a request and a read that no test above them
 * can see. A controller test mocks the service, so it cannot tell a statement selected by its whole
 * card number from one selected by a display mask; a repository integration test proves a query parses
 * but never chooses between queries. The decisions that go wrong in this layer are decisions about
 * WHICH row is read, HOW MANY rows are read, and -- at the request edge -- WHICH branch a selection or
 * a confirmation answer resolves to. All three are visible only in the arguments a service hands its
 * collaborators, which is what the cases here assert on.
 *
 * <p>This is the TEST-tree charter for the package. The production charter for the same package name,
 * at {@code services/reporting-service/src/main/java/com/carddemo/reporting/service/package-info.java},
 * owns the service roster, the layering boundary and the COBOL program each service replaces; it is
 * cited here and deliberately not restated. Assumptions: one statement of a contract can be corrected
 * in one place, whereas the same statement in two charters drifts and then contradicts itself, so
 * anything true of the production package is read there and only the test-tree obligations are stated
 * here.
 *
 * <h2>What this directory holds</h2>
 *
 * <p>The inventory below is a MEASUREMENT of this directory rather than a plan for it, and it is
 * re-measured on every build by {@code PackageCharterInventoryTest} under
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/}. That test parses the
 * marker line, counts the {@code .java} files beside this charter, and additionally requires every
 * name enumerated below to be a file in this same directory -- so a class arriving without an entry
 * here, and an entry naming a class that has moved, both fail the build rather than ageing quietly:
 *
 * <pre>
 * this directory: 7 java files = 6 tests + 1 charter
 * </pre>
 *
 * <p>Trade-offs: the marker line is adopted here, and the per-class case counts that the same test can
 * also verify are not. The file and member figures change only when a file is added or removed, which
 * is exactly the drift worth catching; a per-class count of test methods changes every time any of six
 * neighbouring classes gains a case, which would turn this charter into a build-breaking coupling to
 * six other files for a figure nothing asks it to publish. Adopting the marker needs no ceremony by
 * design -- that test treats its own minimum as a floor rather than an exact count, so a further
 * charter opting in cannot fail it, while deleting a marker to silence a failure has to lower the
 * floor visibly.
 *
 * <ul>
 *   <li>{@code TransactionReportServiceTest} exercises {@code TransactionReportService}: how the
 *       transaction report detects a broken dimension, how it pages, and what it groups on. The
 *       reference is {@code app/cbl/CBTRN03C.cbl} (649 lines), laid out by
 *       {@code app/cpy/CVTRA07Y.cpy} (73 lines) and driven in the baseline by
 *       {@code app/jcl/TRANREPT.jcl} (84 lines).</li>
 *   <li>{@code StatementServiceTest} exercises {@code StatementService}: how a statement is selected,
 *       how much it reads, and what its artifact locations disclose. The reference is
 *       {@code app/cbl/CBSTM03A.CBL} (924 lines) with its file-handling subprogram
 *       {@code app/cbl/CBSTM03B.CBL} (230 lines), over the record layout
 *       {@code app/cpy/COSTM01.CPY} (38 lines) and the job {@code app/jcl/CREASTMT.JCL} (97
 *       lines).</li>
 *   <li>{@code ReportExecutionServiceTest} exercises {@code ReportExecutionService}: which report type
 *       a selection resolves to, which confirmation answer a request carries, what range each preset
 *       expands to, and what is handed to the orchestrator. The reference is
 *       {@code app/cbl/CORPT00C.cbl} (649 lines), whose submission path is the JOBS transient data
 *       queue defined across L499 to L505 of {@code app/csd/CARDDEMO.CSD}, with DDNAME(INREADER) on
 *       L501.</li>
 *   <li>{@code CategoryBalanceReportServiceTest} exercises {@code CategoryBalanceReportService}: what
 *       the generation pass writes, in what order, what it totals, and what it does with a sink that
 *       fails mid-run. Its reference is {@code app/jcl/PRTCATBL.jcl} (66 lines), which carries no
 *       COBOL program at all -- the report is a DFSORT step, so the job IS the specification.</li>
 *   <li>{@code ReportSubmissionNamingTest} covers the identity a submission is started under, which
 *       is what decides which repeat submissions are refused.</li>
 *   <li>{@code StatementRunIndexTest} covers the two value types that make a run-wide statement
 *       artifact addressable per card.</li>
 * </ul>
 *
 * <p>Assumptions: the sibling classes named in the sections below live in OTHER packages and are
 * therefore written into prose rather than into the list above. The inventory test holds every name it
 * finds in a list entry to being a file in THIS directory, so citing a neighbour's class in that
 * position would fail the build while saying something true. The list is the closed set of this
 * directory; a neighbour is a citation.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>This file exists because user-specified Rule 1 (Explainability) requires a docstring on every
 * module entry point at its line 15, a Java package declaration is one, and a
 * {@code package-info.java} Javadoc block is the only construct able to carry it. Of the four content
 * elements the rule enumerates -- purpose at line 18, parameters at 19, return values at 20 and
 * exceptions or errors at 21 -- only Purpose applies here. A package declaration accepts no parameter,
 * yields no value and raises nothing, so the other three are inapplicable rather than omitted, and no
 * at-clause is invented to stand in for one of them. Assumptions: an invented at-clause would be worse
 * than the omission it disguises, because the completeness module that audits this build rejects an
 * at-clause carrying no description, so the empty tag fails the gate it was added to satisfy.
 *
 * <p>Two Checkstyle checks act on this file and neither is redundant. {@code JavadocPackage}, declared
 * at Checker level in {@code config/checkstyle/checkstyle.xml}, is a file-set check: it asserts the
 * FILE EXISTS for every package holding audited Java sources. {@code MissingJavadocPackage}, declared
 * inside TreeWalker in the same file, asserts the file CARRIES a Javadoc block. An empty or
 * comment-free {@code package-info.java} satisfies the first and fails the second, which is why the
 * obligation is to create the file AND to give it a real block. {@code services/pom.xml} binds the
 * gate to the Maven {@code validate} phase with the Checkstyle engine pinned to 13.8.0, so both fire
 * on every local build before {@code javac} runs, and not only in CI.
 *
 * <p>Assumptions: those two checks reach this tree because {@code services/pom.xml} sets
 * {@code includeTestSourceDirectory} to true, at line 994, and this module's own POM does not
 * override it. That value is what makes this file MANDATORY rather than merely conventional, and the
 * rationale recorded beside it in the parent is that the Explainability rule makes no exemption for
 * tests. Refactoring Rationale: the value was read from the declaring POM and then confirmed by
 * running the gate rather than by rendering an effective POM, because {@code help:effective-pom}
 * cannot execute in this environment -- the Maven help plugin is absent from the pre-warmed local
 * repository and the build runs offline. Deleting this file fails the build naming
 * {@code JavadocPackage}, and stripping this block while leaving the package declaration fails it
 * naming {@code MissingJavadocPackage}; observing both is a stronger reading of the setting than any
 * rendered POM, because it demonstrates the checks actually firing on this directory. Both were
 * observed, each reported against this package: the first against a sibling test class in it, since a
 * missing charter is a property of the directory rather than of any one file, and the second against
 * this file at line 1.
 *
 * <p>Assumptions: repeating that experiment requires clearing the plugin's incremental cache first, and
 * skipping that step inverts the result. The Checkstyle plugin keeps a cache under this module's
 * {@code target} directory and audits only what it considers changed, so a second run over an otherwise
 * untouched tree audits ZERO files and passes -- including the run in which this charter has just been
 * deleted. Trade-offs: the cache is worth keeping for the build time it saves, and the cost is that it
 * makes the gate look absent to anyone testing it casually. The honest check is to remove the cache
 * file, or to run the phase from {@code clean}, and then read the audited-file count in the generated
 * checkstyle result rather than reading only the build status.
 *
 * <p>The distinction that must not be lost: the FILE requirement is the conditional half. The Rule 1
 * docstring obligation on the six test classes beside this charter is UNCONDITIONAL, and no value of
 * that setting relaxes it. A configuration flag governs which files a linter reads; it does not govern
 * what the rule requires.
 *
 * <p>Assumptions: nothing under {@code src/test/java} is exempt from any of this.
 * {@code config/checkstyle/suppressions.xml} admits exactly two path patterns --
 * {@code target/generated-sources/} and {@code src/test/resources/fixtures/} -- and its filter is
 * declared fail-closed, so a missing suppressions file is an error rather than a silent allow-all.
 * Test SOURCES are outside that charter, which is the single most misunderstood fact about this tree:
 * the fixtures exemption covers generated and fixture DATA, never a test class.
 *
 * <p>There is also no in-code bypass of any kind. All three of the filters that would provide one --
 * the annotation-driven filter, the comment-driven filter and its nearby-comment variant -- are absent
 * from the configuration, which names each of them and the reason it was left out in the excluded-module
 * section at its own L617 to L618. Assumptions: the consequence is worth stating in full, because the
 * mechanism people reach for first is the one that does least. An off-switch comment marker, or an
 * annotation naming a check, suppresses NOTHING in this build: with no filter to read either of them
 * they are inert text, so the only thing such a marker records is an intention to bypass a gate that
 * cannot be bypassed. Refactoring Rationale: the three filter names are cited at that location rather
 * than copied to here, on the same ground as the production charter above -- the configuration is the
 * authority on its own inventory, and a second copy of it in a test charter is a copy that goes stale
 * without anything failing.
 *
 * <p>The prose standard this configuration partially mechanises is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, which is authoritative over the configuration wherever
 * the two disagree. Assumptions: a passing Checkstyle run means the docstring half of Rule 1 is
 * satisfied and no more. Whether a rationale explains why rather than restating what, and whether a
 * non-obvious choice was left undocumented, are judgements no module in that configuration can make,
 * and they remain a review obligation under the rule's own validation gate at line 43.
 *
 * <h2>Naming, and what the wrong suffix costs</h2>
 *
 * <p>Every unit test class in this package MUST end in {@code Test}. {@code services/pom.xml} binds
 * Surefire to that pattern, so a class named with any other suffix -- {@code Tests},
 * {@code TestCase}, {@code Spec} -- is not collected. Assumptions: the failure mode is the reason this
 * is stated as a rule rather than a convention. An uncollected class does not run and therefore cannot
 * fail: it is absent from the run, absent from the report, and green. For a body of cases whose whole
 * job is to hold a must-be-green assertion, silence is the worst available outcome, because a deleted
 * test at least leaves a diff behind while a misnamed one looks like coverage forever.
 *
 * <p>The {@code IT} suffix belongs to Failsafe and never appears here. That split is the module's
 * convention rather than this directory's, and it is load-bearing: Failsafe runs after packaging and
 * asserts its results in {@code verify}, so a container-backed class named {@code Test} would start a
 * container inside the unit-test phase, and a unit test named {@code IT} would not run until later or
 * would not run at all. The module's integration tests live in the sibling
 * {@code com.carddemo.reporting.repository} test package, as {@code ReportingQueryBootstrapIT} and
 * {@code StatementHeadingChunkIT}, and every class here is a plain unit test measured in milliseconds.
 *
 * <h2>Which assertions live here, and which do not</h2>
 *
 * <p>Two of the four claims this bounded context has to make about its output are made here, and the
 * third is made one package over.
 *
 * <ul>
 *   <li>A statement renders correct output for one card carrying more transactions than the baseline's
 *       inner same-card table admits. Asserted HERE, by
 *       {@code StatementServiceTest.aCardPastTheBaselineInnerTableThresholdStatementsEveryTransaction},
 *       which drives 513 -- the first value past the threshold {@code tests/README.md} measures -- and
 *       asserts every line reaches the document, the heading reports the true count, and the last line
 *       is the expected one by identity so a truncation that preserved the count could not pass.</li>
 *   <li>A statement renders correct output for more distinct cards than the baseline's outer card table
 *       admits. Asserted HERE, by
 *       {@code StatementServiceTest.aRunPastTheBaselineOuterTableThresholdStatementsEveryCard}, which
 *       drives 52 across a chunk boundary and asserts a statement and a contiguous index entry for every
 *       one of them.</li>
 *   <li>The report line being exactly 133 columns with byte-exact edit masks is NOT asserted here. It
 *       belongs to the sibling {@code com.carddemo.reporting.mapper} test package --
 *       {@code TransactionReportMapperTest} for the emitted widths and the masks,
 *       {@code ReportBandLayoutsTest} for the declared width and the per-band arithmetic -- because the
 *       mapper assembles the line and the services in this package only decide which rows reach it.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: this list previously read "Three of the four claims ... are made here" and
 * attributed the 133-column control to {@code TransactionReportServiceTest} and both arity controls to
 * {@code StatementServiceTest}. All three attributions were wrong in the same direction, and the
 * direction matters: each named a real class that did not contain the control, so the charter read as
 * evidence of coverage that did not exist and nothing in the build contradicted it. The two arity
 * controls now exist and are named case by case rather than class by class -- naming the class is what
 * allowed the claim to survive their absence -- and the 133-column control is attributed to the package
 * that has always owned it. Both arity cases assert that the migrated service imposes NO arity, which is
 * divergence {@code D-2} in {@code docs/architecture/cobol-to-service-traceability.md}; the baseline's
 * two unchecked tables in {@code app/cbl/CBSTM03A.CBL} are reference-only and are left exactly as they
 * are.</p>
 *
 * <p>The fourth claim -- that this module's database role is provably unable to write, and that no
 * {@code db/migration} directory or Flyway artifact exists anywhere in it -- is NOT made here, and no
 * case in this package may claim it. Assumptions: its real owners were established by reading the
 * tree rather than by assumption, because a charter that names the wrong owner sends the next reader
 * to a file that cannot answer them. The migration-directory census belongs to
 * {@code ServiceCatalogInventoryTest} in {@code com.carddemo.common.architecture}, which walks the
 * reactor and renders this module's migration path as none; the read-only pool posture belongs to
 * {@code DataSourceConfigTest} in the sibling {@code com.carddemo.reporting.config} test package,
 * which requires a pool not declared read-only to stop the service at startup; and the privileges
 * themselves are asserted against a real engine by the two integration tests named above.
 *
 * <p>Two complementary boundaries close the set. The sibling {@code com.carddemo.reporting.mapper}
 * test package owns per-band byte arithmetic and the edit-mask cases -- among them
 * {@code CobolEditMaskTest}, {@code ReportBandLayoutsTest}, {@code StatementBandLayoutsTest},
 * {@code TransactionReportMapperTest}, {@code StatementTextMapperTest} and
 * {@code StatementHtmlMapperTest} -- and the sibling repository test package owns cursor behaviour and
 * fixture geometry. Trade-offs: this package asserts service-level behaviour and CITES those siblings
 * rather than re-deriving their expectations. Re-deriving a column offset here would cost one more
 * place for the same number to be wrong in, and the two copies would then disagree silently, with each
 * suite passing against its own arithmetic. The accepted cost is that a reader chasing an exact byte
 * position has one more file to open.
 *
 * <h2>Three numbers, and why they must never be collapsed into one</h2>
 *
 * <p>The statement generator's two tables carry THREE distinct figures. Conflating any two of them
 * produces a sentence that is confidently wrong, and the sentence is wrong in a way that survives
 * review because it sounds specific.
 *
 * <ul>
 *   <li>10 is the DECLARED inner arity. {@code app/cbl/CBSTM03A.CBL} L228 declares
 *       {@code 10 WS-TRAN-TBL OCCURS 10 TIMES} inside the record opened at L225.</li>
 *   <li>512 is the MEASURED inner, same-card overrun. A single card renders up to 512 transactions and
 *       the 513th overruns that inner table and takes the program down with a segmentation fault,
 *       recorded under the marker {@code F-STMT-INNER-OVERFLOW}.</li>
 *   <li>51 is the DECLARED and measured outer, distinct-card limit. {@code app/cbl/CBSTM03A.CBL} L226
 *       declares {@code 05 WS-CARD-TBL OCCURS 51 TIMES}, with the parallel counter table at L232
 *       declaring {@code 05 WS-TRN-TBL-CTR OCCURS 51 TIMES}. Up to 51 distinct cards render and the
 *       52nd overruns the outer table, recorded under {@code F-STMT-OUTER-OVERFLOW}.</li>
 * </ul>
 *
 * <p>Assumptions: the declared arity is NOT the failure boundary, and that is precisely why three
 * figures exist where a reader expects one. Neither table is bounds-checked, so a write past the
 * declared subscript does not fail at the tenth element; it keeps writing into adjacent storage and
 * fails only when the overrun leaves the region the process owns. The inner table therefore declares
 * 10 and survives to 512, and anyone reasoning from the declaration alone would predict a failure two
 * orders of magnitude early, while anyone reasoning from 512 alone would believe the declaration says
 * 512.
 *
 * <p>The phrase "~51 transactions" is FORBIDDEN anywhere in this package -- in a class name, a method
 * name, an inline comment, a Javadoc sentence, a display name or a fixture name. It merges two
 * independent defects into one wrong sentence: it attaches the outer table's card limit to the inner
 * table's transaction axis, which hides the far larger same-card limit and mislabels a table overrun as
 * a transaction-count issue. {@code tests/README.md} L70 to L82 states the same conclusion in its own
 * words -- that there are two independent unchecked tables and therefore no single "~51 transactions"
 * limit, that figure conflating the two -- and records its own reasoning at L80 to L82. That is the
 * citation to follow; the stale plan reference appearing beside it in that file at L60, L77 and L85 is
 * not carried forward here.
 *
 * <p>What this package asserts about those thresholds is the Java behaviour, not the baseline's. The
 * baseline declares fixed tables and does not bounds-check them; the Java composes a statement with no
 * fixed arity anywhere, which is divergence D-2, and the two cases named above pin correct output far
 * beyond BOTH thresholds rather than stopping short of either. The COBOL under {@code app/} is read as
 * the specification and is never edited, so this is a documented divergence rather than a change to the
 * reference. Assumptions: only D-2 belongs to this context. The export and import record-key
 * divergence D-1, at {@code app/cbl/CBEXPORT.cbl} line 68 and {@code app/cbl/CBIMPORT.cbl} line 40,
 * and the interest-flush divergence D-3 both belong to {@code batch-service}, and neither is claimed,
 * re-derived or re-tested here.
 *
 * <h2>The clock rule, which is per class in both directions</h2>
 *
 * <p>Getting this wrong in EITHER direction is a defect, so the split is stated per class rather than
 * per package.
 *
 * <p>The four generator and value-type classes -- {@code TransactionReportServiceTest},
 * {@code StatementServiceTest}, {@code CategoryBalanceReportServiceTest} and
 * {@code StatementRunIndexTest} -- prove that what they exercise consults NO clock at all. A date range
 * arrives as a parameter. Assumptions: the reference establishes this rather than taste.
 * {@code app/jcl/TRANREPT.jcl} hard-codes its two bounds as DFSORT symbols at L43 and L44,
 * {@code PARM-START-DATE,C'2022-01-01'} and {@code PARM-END-DATE,C'2022-07-06'}, and an injected date
 * is exactly what makes a rerun byte-reproducible against a golden master. A generator that read the
 * wall clock would produce a different report every day from identical input, which is not a
 * performance question but a correctness one: the comparison it is meant to satisfy could never pass
 * twice.
 *
 * <p>The two request-edge classes -- {@code ReportExecutionServiceTest} and
 * {@code ReportSubmissionNamingTest} -- are the classes permitted a clock, because the request edge
 * resolves its date presets and its submission identity exactly once, from the moment the request
 * arrives. Both use a REAL fixed clock rather than a stubbed one. Alternatives Considered: stubbing the
 * time source to return a prepared date. Rejected, because the property under test is the derivation --
 * which range a preset expands to, and which identity a submission is started under -- and a stub that
 * returns the answer asserts the stub rather than the derivation. A fixed clock supplies an input and
 * leaves the computation under test.
 *
 * <h2>What this package does not contain</h2>
 *
 * <p>Each entry is an absence to preserve, not an omission to correct.
 *
 * <ul>
 *   <li>No {@code SpringBootTest} and no context-loading test of any kind. Every class here is a plain
 *       unit test that wires its subject by constructor and mocks its collaborators. Assumptions: the
 *       module does hold exactly one context-loading test, and it is NOT in this package --
 *       {@code ReportingQueryBootstrapIT} in the sibling repository test package is the one class that
 *       loads a Spring context, under the {@code test} profile, reaching a database through a
 *       container. The claim is scoped to this package precisely because the module-wide version of it
 *       would be false.</li>
 *   <li>No emulated AWS endpoint. {@code org.testcontainers:testcontainers-localstack} is absent from
 *       this module's POM by design, and the POM records the reasoning: what is worth proving about the
 *       state-machine submission is which input is sent and that a failure propagates, and a
 *       Mockito-verified assertion on the start-execution request proves both without a container.</li>
 *   <li>No {@code Container} or {@code ServiceConnection} database container. The module does depend on
 *       the PostgreSQL Testcontainers modules, and that mechanism belongs to the two integration tests
 *       in the sibling repository test package. Assumptions: the dependency being present is not a
 *       licence to use it here -- starting a container is measured in seconds against unit tests
 *       measured in milliseconds, and a container in this package would buy nothing, since the
 *       decisions asserted here are visible in a captured argument.</li>
 *   <li>No data-definition statement, no {@code db/migration} directory and no Flyway artifact. This
 *       context owns no table, so a migration directory in this module would be an affirmative defect
 *       rather than a gap.</li>
 *   <li>No ArchUnit rule declarations. {@code LayeringRulesTest}, under
 *       {@code services/common-lib/src/test/java/com/carddemo/common/architecture/}, is the sole owner
 *       of the layering and money prohibitions, including the ban on floating-point types anywhere in
 *       the money path and on one service importing another's domain package. Assumptions: those rules
 *       are discovered by package name, so relocating or duplicating them here would leave two
 *       enforcers of one boundary, and the weaker of the two would then silently define the rule.</li>
 * </ul>
 *
 * <p>One further absence is about vocabulary rather than mechanism. Nothing here describes a Java build
 * as warning-level anything. Assumptions: the graded condition-code rubric, in which 4 is a soft warn,
 * belongs exclusively to the COBOL parity oracle under {@code tests/}; Maven, Checkstyle, Surefire,
 * Failsafe and JUnit are binary, so a run either passes or fails and there is no partial credit to
 * report. The oracle's own standing aggregate of 4 arises solely from the out-of-scope D-1 defect and is
 * its documented green state rather than a regression, which is exactly why the two vocabularies are
 * kept apart: borrowing the graded one for a build here would make a failure sound survivable.
 *
 * <h2>The label register, stated once for the whole package</h2>
 *
 * <p>Every rationale in this package is tagged with one of exactly four labels, written
 * character-for-character as Alternatives Considered, Refactoring Rationale, Assumptions or Trade-offs,
 * each closed by a colon. Five properties of that form are binding, and each one is a way the label has
 * actually been written wrongly:
 *
 * <ul>
 *   <li>PLURAL where the rule writes it plural. A singular Trade-off or Assumption is not a permitted
 *       abbreviation.</li>
 *   <li>UNPARENTHESISED. The parenthesised form is wrong here. Assumptions: that form is not imaginary
 *       and not merely hypothetical -- it is the established idiom of the reference tree, appearing in
 *       {@code scripts/test_env.sh} and in {@code tests/README.md}, where a bullet opens with a
 *       parenthesised singular category. That tree is reference-only and is not retyped, so the two
 *       trees genuinely read differently and the idiom must not leak across.</li>
 *   <li>ASCII hyphen-minus only, in Trade-offs. Assumptions: {@code tests/README.md} L548 genuinely
 *       carries a non-breaking hyphen inside that very word, so copying the label text from that file
 *       imports a character no grep for the canonical form will match. The labels are taken from Rule 1
 *       lines 31 to 34 only. The same file is also the reason not to take the ELEMENT names from it: at
 *       L546 it renames them to Returns and Exceptions, where the rule writes return values at line 20
 *       and exceptions or errors at line 21.</li>
 *   <li>TRAILING COLON retained. A label with the colon dropped names the category without reading as a
 *       label.</li>
 *   <li>NO EMPHASIS MARKUP. Assumptions: the rules document renders these labels in its own raw
 *       markdown wrapped in asterisk pairs, and those asterisks are that document's presentation rather
 *       than part of the label. A Javadoc block has no markdown renderer, so transcribing them
 *       literally would emit the asterisks into the comment. The bare label text is what belongs
 *       here.</li>
 * </ul>
 *
 * <p>Assumptions: the reason one spelling is enforced is that the label is read by grep before it is
 * read by a person. Auditing this tree against the rule's validation gate means finding every rationale
 * across seven languages, several of which no linter parses at all, so a literal string search is the
 * only mechanism that spans them. One spelling makes that search complete; four spellings of one
 * category make it silently partial, and a rationale a search cannot find is a rationale a review
 * cannot count.
 *
 * <p>The house idiom for NON-Javadoc comments in this tree pairs a what-line whose colon follows the
 * keyword immediately with a why-line carrying one space before its colon, so the two colons align in a
 * column. That idiom originates in the reference suite, at {@code tests/README.md} L178 and L180, and
 * again at L217 and L218, L254 and L256, and L267 and L270. Trade-offs: it is deliberately NOT used
 * inside a Javadoc block, here or in any class beside this one. A Javadoc block is rendered prose whose
 * first sentence is audited as a summary, and a bare what-line placed there narrates the code, which is
 * the one thing the rule forbids outright. Inside Javadoc the equivalent is a labelled sentence opening
 * with one of the four canonical labels above; the paired form stays in implementation comments, where
 * the alignment it buys is worth having.
 */
package com.carddemo.reporting.service;
