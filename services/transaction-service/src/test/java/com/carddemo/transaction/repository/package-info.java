/**
 * Holds the Testcontainers-backed repository integration tests of the LEDGER
 * bounded context, which exercise the four Spring Data JPA interfaces of
 * {@code com.carddemo.transaction.repository} against a real PostgreSQL engine.
 *
 * <h2>Purpose, and the rule elements with no subject here</h2>
 *
 * <p><b>Purpose.</b> This package is the persistence-layer test boundary of
 * transaction-service. Every class in it starts a PostgreSQL container, lets
 * Flyway build the {@code ledger} schema from
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql},
 * and then asserts that the four repository interfaces declared in the main
 * tree's package of the same name reach the rows that migration defines,
 * through the keys and in the order the COBOL baseline reached them. Nothing
 * here substitutes a mock for a database. The main-tree charter at
 * {@code services/transaction-service/src/main/java/com/carddemo/transaction/repository/package-info.java}
 * owns the query contracts and the access-path rulings; this charter governs
 * their verification and cites that file rather than restating it, because two
 * statements of one contract drift apart and a reader then cannot tell which
 * of them is current. The subtree charter at
 * {@code services/transaction-service/src/test/java/com/carddemo/transaction/package-info.java}
 * owns the conventions shared by all five test subpackages and is cited the
 * same way.
 *
 * <h2>Target contract: naming a class here is not a claim it is on disk</h2>
 *
 * <p>Assumptions: the inventory below states the package contract the migration
 * plan assigns, and it is not a listing of the directory. A class named here
 * belongs to this package and a class absent from it does not, whichever files
 * the directory happens to hold; the present contents are read from the
 * directory itself or from
 * {@code mvn -f services/transaction-service/pom.xml test} rather than from
 * this comment, which no comment could keep accurate. The subtree charter
 * states the same reading convention once for all five subpackages, and
 * {@code services/transaction-service/src/test/resources/application-test.yml}
 * states it again for the profile those tests activate.
 *
 * <p>Alternatives Considered: letting the package carry no charter and leaving
 * the closed set, the reserved class names and the inherited rulings to be
 * rediscovered per test. Rejected on two independent grounds. Each test in this
 * package inherits its isolation, determinism, paging and single-sourcing
 * rulings from here, so without the charter every one of them would be restated
 * four times and could drift three ways. And the documentation gate treats a
 * directory as a file set: {@code JavadocPackage} demands a
 * {@code package-info.java} in any directory holding an audited compilation
 * unit, so an integration test here without this file beside it fails the
 * build.
 *
 * <h2>The closed set: four integration tests and no fifth type</h2>
 *
 * <p>Exactly five {@code .java} files constitute this package -- this charter, and one integration
 * test per repository interface -- and ALL FIVE are landed. The count no longer mirrors the main-tree
 * package, which stands at seven: that package's category-balance write path is a fragment interface
 * and its implementation beside the repository, and all three are exercised by the single integration
 * test named for that repository. A write member is not a fourth access path deserving a test of its
 * own; it is part of the contract of the interface that composes it.
 *
 * <p>Refactoring Rationale: this paragraph twice reported fewer landed files than the directory holds
 * -- first TWO of five and then FOUR of five -- and the distinction between planned and landed was
 * stated precisely so that a reader would not go looking for a file that was never written. The
 * inverse failure is the one that actually occurred: a reader told a test is absent writes it again.
 * The markers are therefore re-read from the directory whenever this charter is touched, and the
 * relationship to the main-tree count is stated as a ratio rather than as an identity, so a new
 * fragment there does not silently falsify a number here.
 *
 * <ul>
 *   <li>{@code package-info.java}, this charter;</li>
 *   <li>{@code TransactionRepositoryIT}, which pins the three ordered access
 *       paths onto {@code ledger.transactions}: the
 *       {@code transaction_id CHAR(16)} primary key behind constraint
 *       {@code pk_transactions}, the card-ordered path served by
 *       {@code idx_transactions_card_num}, and the NON-UNIQUE
 *       {@code idx_transactions_proc_ts} that carries the retired batch
 *       alternate key. It also pins the two facts every other test in this
 *       package depends on and none of them should re-assert: that Flyway
 *       applied the migration, and that an unqualified entity resolves to
 *       {@code ledger} rather than to whatever else the connection can
 *       see;</li>
 *   <li>{@code DailyTransactionRepositoryIT}, which pins
 *       {@code ledger.daily_transactions}. Two properties distinguish it. The
 *       first is nullability: {@code proc_ts} is NULLABLE on this table while it
 *       is {@code NOT NULL} on {@code transactions}. The second is its key:
 *       because the program that reads the feed front to back declares no
 *       record key at all, no value the feed carries is unique, so none of the
 *       thirteen copybook columns carries a key or an index and the table's
 *       primary key {@code pk_daily_transactions} is over the generated
 *       ingestion sequence {@code ingest_seq}. What that test has to pin is
 *       therefore that a chunked scan returns BOTH rows of a feed repeating one
 *       transaction identifier and that each stays individually addressable;</li>
 *   <li>{@code TransactionCategoryBalanceRepositoryIT}, which pins
 *       {@code ledger.transaction_category_balances} -- the only one of the
 *       four tables whose key is composite, spanning account identifier, type
 *       code and category code -- together with the create-versus-update
 *       decision, asserted along both paths separately. It is also the only
 *       test here that drives a composed write fragment rather than derived
 *       queries alone, so it additionally pins what that fragment refuses: a
 *       create onto a key another writer holds, an update against a key no row
 *       carries, and either member called without a caller's transaction;</li>
 *   <li>{@code TransactionRejectRepositoryIT}, which pins
 *       {@code ledger.transaction_rejects} and the 430-byte reject contract,
 *       keyed like the feed above on a generated ordinal --
 *       {@code reject_seq} -- because the same record image rejected on two
 *       runs is two legitimate rows. The baseline appends to that stream and
 *       never reads it back, which makes the read side of this repository a
 *       migration affordance rather than a transcribed behaviour, and makes
 *       this test the only place the shape of the stream is checked at all.</li>
 * </ul>
 *
 * <p>Alternatives Considered: extracting the container declaration, the
 * profile annotation and the row builders into a shared abstract base class
 * that the four tests extend, or into a {@code @TestConfiguration} they
 * import. Rejected, and that rejection is why there is no sixth file here: no
 * shared abstract base class, no test-utility class, no fixture-loader class
 * and no {@code @TestConfiguration}. A base class holding a container is
 * shared mutable state, and the concrete consequence is that rows one test
 * inserts become rows another test reads, so a failure names the test that ran
 * afterwards rather than the test that caused it. Each test in this package
 * owns its own schema state instead, which is what lets any one of the four be
 * run alone and still mean something. The cost accepted is that the container
 * field and the profile annotation are declared four times; what is bought is
 * that removing, renaming or reordering any one test cannot change what
 * another one proves.
 *
 * <h2>Baseline provenance: what these tests are answerable to</h2>
 *
 * <p>Every assertion in this package traces to reference material under
 * {@code app/}, which is the behavioural specification for this migration. It
 * is read, cited by path and line, and never modified. Four online programs
 * supply the access paths this package verifies:
 *
 * <ul>
 *   <li>{@code app/cbl/COTRN00C.cbl}, the transaction-list browse driver and
 *       the origin of the keyset paging path;</li>
 *   <li>{@code app/cbl/COTRN01C.cbl}, whose paragraph
 *       {@code READ-TRANSACT-FILE.} at line 267 issues
 *       {@code EXEC CICS READ} at line 269 and supplies
 *       {@code RIDFLD (TRAN-ID)} at line 273. That keyed single-record read is
 *       what a find-by-identifier repository method replaces, and it is the
 *       reason the primary-key path is asserted separately from the list
 *       query rather than folded into it;</li>
 *   <li>{@code app/cbl/COTRN02C.cbl}, transaction add, and the origin of the
 *       identifier-allocation and duplicate-key concerns;</li>
 *   <li>{@code app/cbl/COBIL00C.cbl}, bill payment, whose own record
 *       positioning is a maximum-key derivation rather than a second list
 *       implementation -- a distinction the main-tree charter draws from the
 *       source and that this package must not blur by attaching a page
 *       envelope to it.</li>
 * </ul>
 *
 * <p>Two further programs are read for the physical contract alone, and
 * neither of them belongs to this module:
 *
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl} is the nightly posting program of the
 *       batch context. It writes three of the four tables this context owns,
 *       and it is read here only for the schema contract and for the reject and
 *       create-versus-update semantics: the reject layout at its lines 82 to 84
 *       and 176 to 182, the sequential feed and reject file selections at its
 *       lines 29 to 31 and 46 to 47, and the create-versus-update branch at its
 *       lines 495 to 499. The two modules agree through the physical
 *       {@code ledger} schema, meaning column names, types, nullability and
 *       decimal scale, and never through code. No test here imports a batch
 *       type, and no test here asserts a posting job's behaviour;</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} is read for one fact. Its
 *       {@code SELECT TCATBAL-FILE} block spans lines 28 to 32, and line 31
 *       states {@code RECORD KEY IS FD-TRAN-CAT-KEY}. That is what makes the
 *       category-balance group genuinely the record key rather than a
 *       convenient grouping of three fields, and therefore what
 *       {@code TransactionCategoryBalanceRepositoryIT} is entitled to assert
 *       about a composite identifier.</li>
 * </ul>
 *
 * <p>The record layouts come from three copybooks, cited so that a fixture
 * row's width is never guessed. {@code app/cpy/CVTRA05Y.cpy} is 21 lines and
 * declares {@code TRAN-RECORD} at a record length of 350.
 * {@code app/cpy/CVTRA06Y.cpy} is 21 lines and declares
 * {@code DALYTRAN-RECORD} at the same 350, structurally identical field for
 * field, with only the {@code DALYTRAN-} prefix differing.
 * {@code app/cpy/CVTRA01Y.cpy} is 13 lines and declares
 * {@code TRAN-CAT-BAL-RECORD} at 50.
 *
 * <p>Assumptions: the reject stream has no copybook at all, which is why only
 * three are named for four tables. Its 430-byte layout is declared inline in
 * the posting program, as a 350-byte record beside an 80-byte trailer at that
 * program's lines 82 to 84, with the trailer decomposed at its lines 180 to
 * 182 into a four-digit reason and a 76-character description. A test looking
 * for a fourth copybook will not find one, and must not synthesise a layout to
 * stand in for it; the migration is the normative shape of that table.
 *
 * <h2>A real engine, because the paths under test are engine-specific</h2>
 *
 * <p>Assumptions: these tests run against a PostgreSQL container, and an
 * in-memory substitute is excluded rather than merely unused. The behaviour
 * under test is engine-specific: the keyset access paths and the deliberately
 * NON-UNIQUE index over the processing timestamp cannot be exercised on H2.
 * That index is the surviving half of the batch alternate index over
 * {@code app/cpy/CVTRA05Y.cpy} field {@code TRAN-PROC-TS} at zero-based offset
 * 304, and its non-uniqueness is load-bearing rather than incidental: many
 * transactions share one processing timestamp, so a unique index would reject
 * the second row of any posting run. In PostgreSQL a plain
 * {@code CREATE INDEX} is non-unique, so the property is carried by the
 * ABSENCE of {@code UNIQUE} in the migration -- which means a test that cannot
 * insert two rows sharing one timestamp is not meeting a fussier engine, it is
 * observing a broken contract and must fail. This suite exists to prove those
 * paths, so on a substitute engine it would prove nothing.
 * {@code services/transaction-service/pom.xml} records the same reason beside
 * the Testcontainers artifacts it declares at test scope, so the exclusion is
 * stated where the dependency decision was taken as well as here.
 *
 * <h2>How a connection arrives, and how the schema is reached</h2>
 *
 * <p>Assumptions: connection coordinates arrive as a bean and never as text.
 * Every test in this package annotates its {@code PostgreSQLContainer} field
 * {@code @ServiceConnection}, which contributes a
 * {@code JdbcConnectionDetails} bean that the auto-configuration reads in
 * preference to any {@code spring.datasource} property. Nothing is hand-wired,
 * so nothing can drift from what the test actually connects to. No URL, host,
 * port, user name, password or other endpoint value appears anywhere in this
 * package, and none may be added: the container's port is assigned at run
 * time, so a written connection string either addresses nothing or addresses
 * whichever database happens to be listening on a developer's machine, and the
 * second failure mode is the worse of the two because it passes.
 * {@code @DynamicPropertySource} reaches the same end and is excluded as an
 * either/or rather than offered as a second route, because a package
 * documenting two ways in lets an author pick the one this classpath does not
 * support and discover it by compilation failure.
 *
 * <p>Trade-offs: every test in this package carries
 * {@code @ActiveProfiles("test")}, and that annotation is load-bearing rather
 * than conventional.
 * {@code services/transaction-service/src/test/resources/application-test.yml}
 * resolves only while the profile is active, and it holds the only surviving
 * mechanism that reaches the {@code ledger} schema. Two of the three
 * candidates are unavailable here. The JDBC URL is GENERATED by the container,
 * so no schema parameter can ride on it. And the pooled connection's
 * search-path statement cannot be relied upon, because PostgreSQL accepts a
 * search path naming a schema that does not exist: measured on PostgreSQL 17,
 * setting the path to an absent schema succeeds and then reports nothing, so
 * that statement can neither reach the schema on a bare container nor report
 * that it failed to. What remains is that profile's Flyway default-schema and
 * Hibernate default-schema properties, with its create-schemas allowance and
 * its schemas list making {@code ledger} exist inside a container database
 * that starts empty. Omitting the annotation therefore does not merely degrade
 * a run, it fails it, on a missing schema, before any assertion executes. The
 * compromise accepted is that a mandatory annotation is enforced by convention
 * and by that failure rather than by the compiler. The profile file is already
 * authored and is cited here rather than duplicated, because a second copy of
 * a profile key is a second place to change it.
 *
 * <p>Assumptions: Flyway needs two artifacts on the test classpath and not
 * one. {@code flyway-core} and {@code flyway-database-postgresql} are both
 * managed at 13.0.0 by {@code services/pom.xml}. From Flyway 10 onwards the
 * database-specific support was moved out of core into companion artifacts, so
 * core on its own resolves and compiles perfectly and then fails as the
 * application context starts, with no PostgreSQL support registered. That
 * failure arrives at run time and not at build time, which is exactly why it
 * is written down where the tests that would meet it can be read: the symptom
 * is a container that starts, a schema that is never built, and four suites
 * failing on absent tables.
 *
 * <h2>Keyset paging only: no offset, no page number, no total count</h2>
 *
 * <p>The vocabulary of this package is bounded. A forward page selects keys
 * strictly greater than the last key returned and orders them ascending; a
 * backward page selects keys strictly less than the first key returned and
 * orders them descending; each asks for one row more than the page size, so
 * availability is discovered by that surplus probe row rather than counted.
 * The envelope those rows are assembled into is
 * {@code com.carddemo.common.web.PageResponse}, which is imported from the
 * shared kernel and never re-declared. No offset, page-number or total-count
 * term belongs in a query, a method name or an assertion here.
 *
 * <p>Alternatives Considered: offset pagination, which is the shorter query
 * and the one a reader is likelier to reach for. Rejected because under
 * concurrent inserts the number of rows preceding a returned key changes
 * between requests, so an offset-paged browse SKIPS and REPEATS rows -- a
 * change in observable behaviour that browse-by-key does not have. A test
 * written against offset semantics would be asserting the wrong contract, and
 * it would pass while doing so.
 *
 * <p>Assumptions: the page size is not a configurable property and no test may
 * introduce one. It lives in code beside the query and its surplus probe,
 * where the two can be read together, and the test profile records that
 * absence as a deliberate decision. A tunable size would let a test drift from
 * the screen contract the baseline establishes while still reporting success.
 *
 * <h2>Isolation and determinism: no residue, and no ambient clock</h2>
 *
 * <p>Assumptions: each test in this package owns its schema state rather than
 * depending on residue another test left behind, and the discipline is
 * inherited rather than invented here. {@code tests/README.md} section 11,
 * whose heading is at line 475, states it for the parity oracle: a fresh
 * workspace provisioned per test and torn down afterwards at its lines 479 to
 * 480, business dates injected rather than read from the wall clock at its
 * lines 488 to 489, and parallel-safety following from tests sharing nothing at
 * its lines 490 to 498. Only the discipline transfers; none of the mechanics
 * that section describes do.
 *
 * <p>Assumptions: determinism for the 26-character processing timestamp
 * arrives as a BEAN and not as a property, and the test profile deliberately
 * sets nothing for it. The shared kernel's timestamp formatter takes the clock
 * as a parameter and throws rather than reading an ambient one, so a test
 * supplies a clock pinned to a single instant through {@code Clock.fixed}. The
 * consequence for this package is a flat prohibition: no ambient current-time
 * read appears in any assertion path. A test that reads the wall clock and
 * compares a stored value against it passes for a reason unrelated to the code
 * under test, and fails whenever the two reads straddle a boundary -- a
 * failure that reproduces only at the hour it was introduced.
 *
 * <p>Alternatives Considered: reading the ambient clock and normalising the
 * value before comparison, which is the parity oracle's own technique.
 * Rejected for this package, because normalisation exists to make a byte
 * comparison against a committed file stable and no committed comparison file
 * covers these paths at all. Injecting determinism at the source is the only
 * mechanism available here, and it is also the more exact of the two: a
 * normalised comparison masks the value it cannot predict, while an injected
 * clock makes that value predictable and therefore assertable in full.
 *
 * <p>Assumptions: the oracle's flat-to-indexed load is NOT ported.
 * {@code tests/README.md} lines 481 to 484 describe
 * {@code tests/helpers/load_indexed.sh} and
 * {@code tests/helpers/vsam_loader.py} as its analogue of the mainframe
 * record-copy utility, needed because the batch programs declare indexed
 * organisation and so cannot read a flat fixture directly. The equivalent here
 * is the PostgreSQL container together with Flyway, so nothing in this package
 * loads an indexed file, shells out to that script or imports that module. The
 * oracle's parallel-execution mechanics are likewise not ported: this suite is
 * collected and run by its own build plugin, and the worker-sizing guidance in
 * that section belongs to the tool it names.
 *
 * <h2>Single-sourcing: a test consumes a contract, it never re-declares one</h2>
 *
 * <p>{@code tests/README.md} lines 540 to 542 impose that discipline on the
 * oracle's own COBOL tests: layouts resolve through the compiler's copybook
 * include path, and a layout is never duplicated but kept single-sourced. The
 * analogue holds here exactly, and it is the reason the shared kernel exists
 * at all.
 *
 * <p>Refactoring Rationale: a test in this package therefore CONSUMES a record
 * layout, a field width, an offset, a money semantic or a timestamp form that
 * the domain entity, the mapper, the migration or the sibling
 * {@code src/test/resources/fixtures/README.md} already owns; it does not
 * re-state one. Codec vectors, money arithmetic and its wire form, the keyset
 * page envelope, the timestamp formatter and the field-validation flag model
 * are taken from {@code com.carddemo.common} and are never re-implemented per
 * test. A local copy of any of them would reintroduce precisely the drift an
 * include path forecloses, and the drift would be silent: both copies would go
 * on compiling, and the stale one would go on passing its own assertions.
 *
 * <h2>The suffix decides the runner, and the report directories are defaults</h2>
 *
 * <p>The {@code IT} suffix on every class in this package is structural rather
 * than decorative. A class whose name ends {@code Test} is collected by
 * Surefire and runs at the {@code test} phase; a class whose name ends
 * {@code IT} is collected by Failsafe, runs at {@code integration-test} and has
 * its result asserted at {@code verify}, so a failure surfaces as a build
 * failure rather than as a silently skipped assertion. Both plugins are
 * configured by {@code services/pom.xml} and neither is declared by this
 * module, whose sole build plugin is the Spring Boot packaging plugin. The
 * {@code RepositoryIT} naming already matches Failsafe's default include
 * pattern, so no include configuration exists and none should be added.
 *
 * <p>Assumptions: both runners are left at their DEFAULT report directories,
 * {@code target/surefire-reports} and {@code target/failsafe-reports}, and the
 * continuous integration workflow collects from exactly those two paths under
 * {@code services/*}. Relocating, renaming or redirecting either one would
 * leave the build green while the workflow published nothing, which is the one
 * failure mode here that looks like success. No test in this package may set a
 * reports directory, and no module-level configuration may be added that does.
 *
 * <p>Trade-offs: the suffix distinguishes the Failsafe half inside the
 * ordinary {@code src/test/java} source root, and no second root such as
 * {@code src/it} or {@code src/integration-test} is introduced. The cost
 * accepted is that a naming convention is doing structural work, so a
 * container-backed test misnamed {@code Test} runs at the wrong point in the
 * build and one misnamed {@code IT} appears to pass by never running at all.
 * What it buys is that one source root feeds both runners and both keep writing
 * to the directories the workflow reads; a second root would need its own
 * compile and report wiring, and getting that wiring wrong strands exactly the
 * directories being collected from.
 *
 * <h2>No golden master covers these paths, and none is claimed</h2>
 *
 * <p>Assumptions: {@code tests/README.md} lines 83 to 85 record that the online
 * {@code CO*} programs cannot be run end to end without a CICS runtime, which
 * the runner does not have, so only their extractable field-validation logic is
 * unit-tested. All four online programs this module migrates are {@code CO*}
 * programs, so that exemption covers every one of them. Verification in this
 * package therefore rests on the physical contract -- keys, ordering,
 * nullability and index-compatible behaviour -- and not on a byte comparison
 * against a committed file. No assertion here may be justified by pointing at a
 * golden file, and no test here may create, regenerate or otherwise touch
 * anything under {@code tests/golden/} or {@code tests/fixtures/}.
 *
 * <p>Assumptions: the batch posting program that shares this schema does have
 * golden-master coverage, and it belongs to the batch context rather than to
 * this module. Its coverage must not be read as coverage of these four tables,
 * which is precisely the mistake a shared schema invites: the tables are the
 * same tables, and the programs that write them are not the same programs.
 *
 * <h2>The charter canon, and the charters that deliberately do not exist</h2>
 *
 * <p>Assumptions: this file is one of the six package charters of the test
 * subtree -- the subtree root and one in each of its five leaf subpackages --
 * and NO charter exists at
 * {@code services/transaction-service/src/test/java}, at
 * {@code services/transaction-service/src/test/java/com} or at
 * {@code services/transaction-service/src/test/java/com/carddemo}. That
 * absence is a decision and not an oversight, and it rests on two independent
 * grounds, either of which settles it alone. First the canon: the subtree
 * charter admits exactly six charters, and a seventh would break the count that
 * makes a charter which is missing distinguishable from one that was never
 * intended. Second the mechanism: {@code JavadocPackage} is declared above the
 * syntax-tree container in {@code config/checkstyle/checkstyle.xml}, which makes
 * it a file-set check that fires only for a directory CONTAINING an audited
 * {@code .java} file, and the audit is restricted to that one extension; each of
 * those three directories holds only a subdirectory, so no violation is
 * reachable there and a charter placed in one of them would satisfy no gate.
 *
 * <h2>This gate is binary, and it is local</h2>
 *
 * <p>Assumptions: the documentation gate is bound to the build's
 * {@code validate} phase, ahead of compilation, so a missing or Javadoc-less
 * charter breaks the build on a developer's own machine rather than only in the
 * workflow. Three of the configured checks bear on this file by name:
 * {@code JavadocPackage} requires it to EXIST in this directory,
 * {@code MissingJavadocPackage} requires it to CARRY Javadoc, and
 * {@code SummaryJavadoc} requires a first sentence terminated by a period while
 * rejecting placeholder markers and the rule's own two examples of a vague
 * rationale. A charter reduced to a bare package statement satisfies the first
 * and fails the second, which is why this one is prose.
 *
 * <p>Assumptions: there is no escape from that gate inside this file. The
 * ruleset configures none of the comment-driven or annotation-driven
 * suppression filters, so neither a marker comment nor an annotation suppresses
 * anything; a suppression has to be a durable entry in the companion file,
 * whose whole charter reaches generated sources and fixture material under
 * {@code src/test/resources/fixtures/} only. Test source under
 * {@code src/test/java} sits outside that charter, and the companion file
 * records the reason it stops short of it. No suppression for this package may
 * be requested, and relaxing the gate is a breach of the rule rather than a
 * build-configuration choice.
 *
 * <p>Assumptions: the outcome of this module's build is BINARY -- it passes or
 * it fails. The parity oracle grades itself on a mainframe condition-code
 * rubric in which a warning tier is its current green state, and that rubric
 * belongs exclusively to the oracle under {@code tests/}. No graded tolerance,
 * no warning tier and no arithmetic on a return code may be introduced in this
 * package, and no result of this module's build may be described as
 * warning-level green. A non-zero return code does have a distinct and
 * legitimate meaning INSIDE the specification, the posting program setting one
 * when it has rejected a record, and that is a behaviour a test may require of
 * that program rather than a build policy to adopt here.
 *
 * <h2>Citation discipline</h2>
 *
 * <p>Assumptions: every path and line number cited above was verified on disk,
 * case-sensitively, before it was written, and none was recalled or inferred
 * from a neighbouring file. Extension case differs by directory in the
 * baseline, so a citation carrying the wrong case does not resolve on a
 * case-sensitive filesystem. An invented citation costs more in a test than
 * elsewhere: a comment naming a line that does not say what the comment claims
 * will be trusted by the next reader precisely because it looks specific.
 *
 * <p>Assumptions: the framing of any difference between baseline and migration
 * is constrained, because the wrong verb turns a documented divergence into a
 * claim that reference source was altered. Nothing under {@code app/} is
 * described here as having been fixed, corrected, patched, remediated or
 * repaired. The one permitted framing is that the baseline does X, the Java
 * implements Y, and the divergence is documented -- wording that survives
 * review because each of its three clauses is independently checkable.
 */
package com.carddemo.transaction.repository;
