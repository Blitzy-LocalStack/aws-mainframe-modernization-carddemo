/**
 * Spring configuration package of the batch bounded context, holding infrastructure beans only.
 *
 * <h2>Why a file that declares no type exists here</h2>
 *
 * <p>This charter is rule-forced rather than plan-forced, and that is the first thing a reader
 * needs, because the migration plan enumerates this package by naming its {@code @Configuration}
 * classes and nothing else, at its section 0.4.1.2. The single user-specified rule is what puts
 * this file in scope: the Explainability rule attaches its docstring obligation to every module
 * entry point at its L15, a Java {@code package} declaration is that entry point, and a
 * {@code package-info.java} Javadoc block is the only place documentation for it can be written.
 * There is no alternative location, so the block below is not decoration on this file; it is the
 * file's whole reason to exist.</p>
 *
 * <p>Assumptions: two checks in {@code config/checkstyle/checkstyle.xml} make that obligation
 * build-fatal rather than aspirational, and they are deliberately paired because either one alone
 * is satisfiable without documenting anything. {@code JavadocPackage}, a file-set check at Checker
 * level at {@code config/checkstyle/checkstyle.xml:245}, asserts that this FILE exists in any
 * directory holding a compilation unit the audit processed, and this directory holds
 * {@code @Configuration} classes, so it fires. {@code MissingJavadocPackage}, a syntax-tree check
 * at {@code config/checkstyle/checkstyle.xml:378}, asserts that the file CARRIES Javadoc. A bare
 * {@code package} statement here would satisfy the first and fail the second. Both run bound to
 * the Maven {@code validate} phase at {@code violationSeverity} warning with
 * {@code failOnViolation} true, so the pairing fails a contributor's own build before compilation
 * rather than only a pipeline's, and there is no escape written into the source: the three
 * suppression filters that would permit one are all absent from that ruleset, leaving
 * {@code config/checkstyle/suppressions.xml} -- whose two entries reach generated sources and test
 * fixtures alone -- as the only place an exemption can be recorded at all.</p>
 *
 * <p>Assumptions: this charter carries no parameter, return or exception at-clause, and the
 * omission is declared rather than left silent. The Explainability rule names parameters at its
 * L19, return values at its L20 and exceptions at its L21; a package declaration accepts no
 * parameter, returns no value and raises nothing, so those three elements are structurally
 * inapplicable and only the Purpose element at its L18 remains to satisfy. Inventing at-clauses on
 * a package in order to look complete would produce exactly the hollow docstring its L39 forbids,
 * so the inapplicability is stated and a reader can tell it from an oversight. The obligation is
 * real for every other file in this package, and it rests on the right authority: an exception
 * at-clause is required by that rule's L21, by the house convention at
 * {@code tests/README.md:544-549} which names Purpose, Parameters, Returns and Exceptions, and by
 * {@code JavadocMethod} with {@code validateThrows} true at
 * {@code config/checkstyle/checkstyle.xml:451} -- and NOT by that rule's Validation Gate at its
 * L43, whose triad names purpose, parameters and return values and omits exceptions. Attributing
 * it to L43 would be the unsupported-rationale offence its L41 names, committed inside the
 * sentence claiming to observe it.</p>
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: every class name and count below describes this package's <b>target
 * contract</b> as the migration plan assigns it, not the set of files present beside this one at
 * the checkpoint that authored it. The migration lands its artifacts in plan order, so a class
 * named here that has no file yet is <b>planned</b>, not missing. Measured at this revision this
 * directory still holds only this charter and {@code DataSourceConfig}; {@code BatchConfig} and
 * {@code SqsConfig} remain assigned to other indexes of the same plan and are described below as
 * targets. The distinction is declared
 * because the roster otherwise reads as present tense, and a roster a reader cannot tell apart
 * from an inventory stops being usable the moment one named class turns out to be absent. The
 * parent charter at {@code com.carddemo.batch} makes the same declaration for the subtree as a
 * whole, and the two agree deliberately.</p>
 *
 * <h2>Purpose</h2>
 *
 * <p>This package binds external concerns to the module while the application context is being
 * built, and holds nothing else. The module it configures is the batch bounded context: the
 * migrated form of the scheduling, datastore and messaging plumbing that carried the twelve
 * {@code CB*} batch programs of {@code app/cbl} under JCL and JES2 against indexed files. No class
 * here declares an entity, a repository, a query, a business rule or a route. The posting
 * validation chain, the interest formula and the control break transcribed from the baseline COBOL
 * paragraphs belong to the sibling {@code service} package; the chunk readers and writers to
 * {@code job}; the fixed-width, sign-overpunch and packed-decimal concerns to {@code mapper}; and
 * the table mappings to {@code domain}. A reader asking why a transaction was rejected, or how a
 * monthly interest figure was reached, will not find the answer in this directory.</p>
 *
 * <h2>The membership canon: three configuration classes at the target, one delivered</h2>
 *
 * <p>The set is closed at three {@code @Configuration} classes, so the question "which
 * configuration class does this bean belong on" keeps a definite answer as the module grows. Three
 * production classes plus this charter is four {@code .java} files at the target, and there is no fifth
 * type.</p>
 *
 * <p>Refactoring Rationale: exactly ONE of the three is delivered. {@code DataSourceConfig} is present;
 * {@code BatchConfig} and {@code SqsConfig} are not, and every capability the two entries below describe
 * -- the chunk-oriented step infrastructure, the durable job repository, the job-parameter interlock, the
 * publish-only error sink and its message attributes -- is consequently a TARGET description and not a
 * delivered one. That distinction is restated here because the entries themselves read in the present
 * tense, and a reader who took them at face value would look for a chunk-step bean, a job repository or a
 * message publisher and find none. The prose is left in place rather than deleted because it is the
 * agreed contract for those two classes and is what the authoring of each must satisfy; what is corrected
 * is the impression that satisfying it has already happened.</p>
 *
 * <p>Assumptions: the two absent classes are NOT authored as empty configurations to make the roster
 * true. A configuration class contributing no bean would be a placeholder occupying the name of a
 * reviewed contract, and the step infrastructure in particular cannot be authored honestly ahead of the
 * jobs whose steps it wires. What HAS landed in their place is narrower and real: the durable step ledger
 * is exercised through {@code BatchStepLedger} in the sibling service package rather than through a job
 * repository bean, so the redrive no-op behaviour exists and is asserted even though the framework's own
 * batch infrastructure is not yet wired.</p>
 *
 * <ul>
 *   <li>{@code BatchConfig} owns the chunk-oriented step infrastructure, the durable job
 *       repository, and the job-parameter contract those steps read. It is the interlock with the
 *       command contract declared on {@code BatchApplication}, whose {@code --job=} option selects
 *       one of seven job tokens and whose {@code --business-date=} option reaches a job under the
 *       parameter key {@code businessDate}. Assumptions: a step that read the business date from
 *       anywhere but a job parameter would break the reproducibility
 *       {@code app/jcl/INTCALC.jcl:22} establishes, where
 *       {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'} injects the date rather than letting
 *       the program read a clock, and a rerun that cannot reproduce its output cannot be compared
 *       against a golden master at all. Assumptions: this is the reactor's ONLY
 *       {@code BatchConfig}. The migration plan scopes the class to this module at its section
 *       0.4.1.2, and the build agrees rather than merely asserting it:
 *       {@code org.springframework.boot:spring-boot-starter-batch} is a real dependency element in
 *       exactly one module descriptor, {@code services/batch-service/pom.xml}, and the five
 *       sibling service descriptors name it only inside comments recording its deliberate
 *       absence.</li>
 *   <li>{@code DataSourceConfig} owns the single non-distributed data source; the ordered
 *       connection search path over the four schemas listed below; pool sizing bound from
 *       {@code spring.datasource.hikari} for a task that runs once and exits; the posture that
 *       schema migration alone emits table definitions and this module emits none; and the
 *       transaction posture, which is one auto-configured transaction manager over one data source
 *       and no coordinator of any kind.</li>
 *   <li>{@code SqsConfig} owns publish-only access to the terminal error sink, and the message
 *       attributes a published event carries, whose shape is fixed by the {@code BatchErrorEvent}
 *       record in the sibling {@code dto} package. Trade-offs: it is property-gated and its
 *       listener does not start with the context, so a task whose selected job publishes nothing
 *       starts and exits cleanly instead of failing on a queue it never needed, and a context
 *       started for one job cannot begin consuming work the orchestrator did not select. That
 *       follows from this module being argument-driven: {@code BatchApplication} selects the unit
 *       of work from its {@code --job=} argument, never from message arrival, and the SQS starter
 *       reaches this module only through
 *       {@code io.awspring.cloud:spring-cloud-aws-starter-sqs} in
 *       {@code services/batch-service/pom.xml}. The accepted cost is that a misconfigured queue is
 *       discovered at the first publish rather than at startup.</li>
 * </ul>
 *
 * <h2>The negative boundary: no OpenApiConfig and no SecurityConfig</h2>
 *
 * <p>This is what a reader who knows the plan's five-class shape for a service configuration
 * package will actively come looking for, so it is stated once, here, with every corroboration it
 * has. An absence in a configuration package is invisible, and leaving it unstated is precisely
 * the undocumented-choice offence the Explainability rule names at its L40.</p>
 *
 * <p>Alternatives Considered: an administrative endpoint letting an operator trigger a job over
 * HTTP was evaluated, and it is the alternative both omitted classes would exist to serve. It is
 * rejected because the only invocation path the migration plan defines for this module, at its
 * section 0.4.1.7, is the synchronous run-task call from an orchestrator state, which already
 * passes job selection and the business date as container command overrides -- the same
 * select-a-program-by-name-and-hand-it-arguments shape the baseline used at
 * {@code app/jcl/POSTTRAN.jcl:23}, which reads {@code //STEP15 EXEC PGM=CBTRN02C}. A second
 * invocation path would bring a second authorization surface to design, test and defend for no
 * operational gain, and two paths able to start the same nightly job are two paths able to start
 * it twice from arguments that can disagree. A filter chain would then be guarding nothing that
 * changes state, which is why no resource-server configuration is excluded here rather than
 * merely unwritten.</p>
 *
 * <p>Assumptions: three further corroborations agree with that decision, and each is checkable on
 * its own. First, of the eight service rows in the migration plan's transformation mapping, the
 * batch row at its section 0.5.1.7 is the single row that omits the published interface contract
 * directory; the other seven all include it. Second,
 * {@code services/batch-service/pom.xml} declares no interface-documentation starter, no
 * {@code org.springframework.boot:spring-boot-starter-security} and no
 * {@code org.springframework.boot:spring-boot-starter-oauth2-resource-server}, so either omitted
 * class would fail to compile in this module; {@code spring-boot-starter-web} is carried solely so
 * the actuator health indicator can answer the container image probe, and that listener is bound
 * to the loopback address rather than to every interface, which is what keeps its footprint to its
 * one client. Third, the parent charter at {@code com.carddemo.batch} already records this
 * boundary for the subtree, and this paragraph restates it consistently rather than competing with
 * it.</p>
 *
 * <h2>Symmetry with the authorization bounded context's configuration package</h2>
 *
 * <p>Assumptions: the asymmetry between the two configuration packages is a documented and
 * symmetric design decision rather than an omission at either end, and each end records its own
 * half so that neither absence has to be inferred from the other package's presence.
 * {@code com.carddemo.authorization.config} is chartered for four {@code @Configuration} classes
 * -- a resource-server filter chain, published-contract metadata, its own data source and its
 * queue infrastructure -- which with its own charter is five {@code .java} files; and that charter
 * states why there is no {@code BatchConfig} there, citing the same plan section 0.4.1.2 that
 * scopes the class here, and noting that the module declares no batch starter. This package is
 * chartered for three classes and four files, and states why there is no published-contract
 * metadata and no resource-server filter chain here. A reader arriving from either charter finds
 * the mirror fact written down.</p>
 *
 * <h2>Four schemas, one data source, one commit</h2>
 *
 * <p>This is the module's defining oddity and the fact that most often gets a change here wrong.
 * Every other module in the reactor reaches one schema; this one reaches four from a single data
 * source, under a role whose privileges are deliberately unequal.</p>
 *
 * <ul>
 *   <li>{@code batch} is OWNED, and is the only schema this module owns. It is created for the
 *       batch role at {@code data-migration/sql/V0__schemas_and_roles.sql:514-515} and holds
 *       {@code BatchRun}, mapped to {@code batch.batch_run}, together with the framework's own job
 *       repository tables.</li>
 *   <li>{@code ledger} is owned by {@code transaction-service} and reached under a cross-schema
 *       write grant, {@code SELECT, INSERT, UPDATE} on its tables at
 *       {@code data-migration/sql/V0__schemas_and_roles.sql:715}. {@code Transaction} and
 *       {@code DailyTransaction} map into it, and so do the category-balance and reject records the
 *       plan assigns this module.</li>
 *   <li>{@code account} is owned by {@code account-service} and reached under a grant narrowed to
 *       one table: {@code SELECT} across the schema at
 *       {@code data-migration/sql/V0__schemas_and_roles.sql:768}, with {@code UPDATE} granted on
 *       {@code account.accounts} alone at its line 779. {@code Account} maps to that table and
 *       {@code CardXref} to {@code account.card_xref}, which this role may read and not write.
 *       Assumptions: one named table is the exact privilege because the whole nightly chain holds
 *       only two write sites against this schema, both rewriting an account master that already
 *       exists -- {@code app/cbl/CBTRN02C.cbl:554} and {@code app/cbl/CBACT04C.cbl:356}, each
 *       reading {@code REWRITE FD-ACCTFILE-REC FROM  ACCOUNT-RECORD}. A schema-wide write grant
 *       would also widen silently, since a table added to that schema afterwards would be writable
 *       the moment it was created with nothing recording that it had become so.</li>
 *   <li>{@code reference} is SELECT ONLY, and no write grant exists: its grant at
 *       {@code data-migration/sql/V0__schemas_and_roles.sql:815} carries {@code SELECT} and
 *       nothing further. {@code DisclosureGroup} maps to {@code reference.disclosure_groups}, and
 *       a job here reads a rate rather than maintaining one.</li>
 * </ul>
 *
 * <p>Alternatives Considered: a saga, and a transactional outbox with compensating reversal, were
 * both evaluated for the posting unit of work and both rejected on the same concrete ground. The
 * baseline commits three writes together: {@code app/cbl/CBTRN02C.cbl:440} performs
 * {@code 2700-UPDATE-TCATBAL}, line 441 performs {@code 2800-UPDATE-ACCOUNT-REC} and line 442
 * performs {@code 2900-WRITE-TRANSACTION-FILE}, with the paragraph bodies at lines 467, 545 and
 * 562, and that work spans {@code ledger} and {@code account}. Either decomposition replaces one
 * atomic commit with committed steps plus reversals, which makes partial-posting states observable
 * -- a posted transaction whose category balance has not moved, or a rewritten account balance
 * with no matching transaction row -- and no such state exists in the baseline, so the
 * golden-master comparison would report it as a parity failure and would be right to. The
 * migration plan records the alternative it took at its section 0.4.1.3: this module is the one
 * documented exception to database-per-service purity, running under a dedicated batch database
 * role that holds narrowly-scoped cross-schema write grants and nothing wider, so the unit of work
 * stays a single atomic commit over one data source. Nothing in this package therefore configures
 * a coordinator, a distributed transaction manager or an outbox, and a change introducing one
 * would be changing observable behaviour rather than structure.</p>
 *
 * <p>Assumptions: this package configures the search path but creates none of the privileges it
 * depends on. The schemas, the per-service roles and every grant cited above belong to
 * {@code data-migration/sql/V0__schemas_and_roles.sql}, and the definitions of the three foreign
 * schemas belong to the services that own them; this module owns migrations for {@code batch}
 * alone and never creates, alters or seeds another service's schema. Ordering within the search
 * path is part of the contract rather than a preference: an unqualified statement under a
 * reordered path still resolves, against a real table in the wrong schema, so the failure would
 * present as a plausible row rather than as an error.</p>
 *
 * <p>Refactoring Rationale: the search path carried a fifth entry, {@code card}, on the reading
 * that the daily-feed preflight validates against the card master. That reading is wrong. The
 * program opens the card file at {@code app/cbl/CBTRN01C.cbl:309}, which reads
 * {@code OPEN INPUT CARD-FILE}, and never issues a read against it; the cross-reference it does
 * read, at {@code app/cbl/CBTRN01C.cbl:229}, is mapped by this module into
 * {@code account.card_xref}; and no table mapping in this module names the {@code card} schema. The
 * entry was withdrawn because a resolution candidate no statement can reach adds nothing but the
 * ability to shadow a future name.</p>
 *
 * <h2>The durable step ledger, and what it does not replace</h2>
 *
 * <p>{@code BatchConfig}'s job repository together with the {@code batch.batch_run} table is this
 * module's analogue of a mainframe restart directive. {@code BatchRun} declares a uniqueness
 * constraint over {@code (run_id, step_name)} above a surrogate identity key, which is what makes a
 * redriven orchestrator state a no-op for a step that already completed rather than a second
 * application of the same work.</p>
 *
 * <p>Refactoring Rationale: there was no checkpoint contract to carry across, and the absence is
 * stated as an absence. The only {@code RESTART=} anywhere in the thirty-eight jobs of
 * {@code app/jcl} is commented out, at {@code app/jcl/DEFGDGD.jcl:2}, which reads
 * {@code //*  RESTART=STEP30}; and no {@code CHKPT=} appears in any of those thirty-eight jobs at
 * all. So the baseline resumes a failed run by an operator editing and resubmitting the job, the
 * Java expresses resumption as orchestrator redrive plus this durable ledger, and the divergence
 * is registered in {@code docs/architecture/cobol-to-service-traceability.md} with every other
 * intentional one. Assumptions: describing the ledger as a port of an existing contract would
 * misdescribe the baseline, and describing the baseline as deficient would invite an edit to
 * material the migration plan places out of scope for change at its section 0.2.2 -- everything
 * under {@code app/**} is reference-only and stays byte-identical, which is exactly what lets the
 * three-layer suite rooted at {@code tests/} serve as the parity oracle. That suite states the
 * same principle for itself at {@code tests/README.md:31-33}, which records that the programs,
 * copybooks, job control and seed data under {@code app/} are reference only and are never
 * modified. The distinction matters as soon as someone asks which behaviour parity testing is
 * entitled to assume.</p>
 *
 * <h2>Money is exact fixed point, named here because the cost of missing it is silent</h2>
 *
 * <p>Assumptions: the money representation is implemented in the shared kernel rather than in this
 * package, and it is named at package level anyway because an inexact amount does not fail loudly
 * -- it returns a plausible figure that is wrong. The migration plan's transformation rule T3 fixes
 * the representation at every hop: {@code NUMERIC(p,2)} in SQL, {@code BigDecimal} at scale 2 in
 * Java, and a string rather than a number on any wire, because a JSON number is parsed into an
 * IEEE-754 binary value by most clients and exactness is destroyed at precisely the boundary a
 * user sees. The types {@code float}, {@code double}, {@code java.lang.Float} and
 * {@code java.lang.Double} are forbidden in the money path, and the prohibition is asserted rather
 * than requested: rule A3 of
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * fails the build for any type declaring one of them in a field, parameter or return position, and
 * that rule class is scanned into this module's own test run. The normative field is
 * {@code app/cpy/CVACT01Y.cpy:7}, which reads
 * {@code 05  ACCT-CURR-BAL                     PIC S9(10)V99.} -- twelve display bytes of zoned
 * decimal carrying the sign as an overpunch on the final byte, NOT packed decimal, which is why
 * the decoding concern belongs to {@code mapper} and the arithmetic concern to the shared kernel,
 * and neither of them here.</p>
 *
 * <h2>Runtime posture, and what this charter is subordinate to</h2>
 *
 * <p>This file declares no type, exports no API, imports nothing and has no sibling-package
 * dependency at run time; removing it would change no behaviour and would still fail the build. It
 * is subordinate to the parent charter at {@code com.carddemo.batch} and restates that charter's
 * layering contract rather than competing with it. Two clauses of that contract bind every class in
 * this directory. Assumptions: the only intra-repository Maven dependency this module declares is
 * {@code common-lib}, and no class here imports another service module's table-mapping package; the
 * prohibition belongs to the {@code architecture-rules} test execution declared in
 * {@code services/pom.xml}, which runs the shared layering rules against each module's own
 * compiled classes, so it is a rule a build evaluates rather than a convention a review hopes for.
 * And a job in this module reports its outcome to the orchestrator through the container process
 * exit status and through nothing else, so no bean configured here carries a result channel of its
 * own.</p>
 *
 * <p>Trade-offs: the graded condition-code rubric belongs to the COBOL three-layer
 * functional-parity oracle suite rooted at {@code tests/}, and to the batch container's process
 * exit status, and to nowhere else in this package. That suite grades its aggregate on a scale and
 * treats a warning-level aggregate as its own success state; the Java tooling on this side is
 * binary, and Maven, the documentation gate, the unit-test runner and the assertion library each
 * either pass or fail. Borrowing the oracle's vocabulary for a Maven result would hide a real
 * failure behind a tolerated one. The accepted cost is that two adjacent parts of one repository
 * count success differently and a reader has to know which is speaking. Note also that the oracle
 * suite rooted at {@code tests/} is not this module's own test tree at
 * {@code services/batch-service/src/test}; the two are kept textually distinct for the same
 * reason.</p>
 *
 * <p>Authors of the three configuration classes should read this file first, because it fixes the
 * conventions their Javadoc is audited against, and there are two. Assumptions: this file is
 * restricted to ASCII, so where a cited source carries a non-breaking hyphen or an em dash it uses
 * an ASCII hyphen-minus or a pair of them, with wording otherwise unchanged.
 * {@code tests/README.md} is the one file in the repository that uses the non-breaking hyphen, and
 * it uses it inside the very rationale labels this tree has to reproduce, so copying a label from
 * there yields one that looks right and searches wrong. Assumptions: the four rationale labels are
 * written in exactly one form, the plural unparenthesised form with the colon retained and no
 * emphasis markup, as {@code docs/CODE_DOCUMENTATION_STANDARD.md:203-226} fixes it. Trade-offs:
 * that form diverges from the singular idiom of the repository's reference-only trees, which
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md:236-240} records against
 * {@code .github/workflows/tests.yml}. The divergence is accepted because a label is read by a
 * search before it is read by a person, and a rationale a search cannot find is a rationale a
 * review cannot count; the two forms are never mixed inside one file.</p>
 */
package com.carddemo.batch.config;
