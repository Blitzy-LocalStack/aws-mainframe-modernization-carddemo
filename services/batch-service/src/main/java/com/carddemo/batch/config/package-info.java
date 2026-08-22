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
 * <h2>The directory, measured rather than remembered</h2>
 *
 * <p>Assumptions: the roster below names four classes and all four are present, so it is an
 * inventory and not a forecast. Measured at this revision this directory holds this charter,
 * {@code DataSourceConfig}, {@code BatchConfig}, {@code SqsConfig} and
 * {@code SqsBatchFailureReporter}:
 *
 * <pre>
 * this directory: 5 java files = 4 classes + 1 charter
 * </pre>
 *
 * <p>Refactoring Rationale: the fourth class is {@code SqsBatchFailureReporter}, and it is here rather
 * than beside the rule that calls it for one structural reason. It is the ADAPTER half of
 * {@code com.carddemo.batch.service.BatchFailureReporter}, a port declared in the service package
 * beside its only caller, and its three concerns are all this package's own: a queue client, a
 * validated queue address and a serialiser. Placing the adapter beside the port instead would make the
 * service package depend on this one, which already depends on it for the durable step ledger, so the
 * two would form a cycle. It is therefore an admitted exception to the "binds external concerns while
 * the context is being built" reading below, and the exception is stated rather than left for a reader
 * to infer: this class does its work at run time, on the failure path, and not during refresh. What
 * keeps it inside this package's remit is that everything it knows is a binding -- it holds no rule,
 * decides no outcome and reads no business row.</p>
 *
 * <p>Assumptions: the marker line carries NO planned clause, so its two figures differ by exactly one
 * -- the charter itself -- which is the ordinary relationship every other marked charter in the tree
 * states. A planned clause is the wrong tool here because the check that reads it asserts both
 * directions: a reserved name whose file exists fails the build. The marker is measured against this
 * directory on every build by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java},
 * so neither figure can drift silently.</p>
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
 * <h2>The membership canon: three configuration classes, all three delivered</h2>
 *
 * <p>The set is closed at three {@code @Configuration} classes, so the question "which
 * configuration class does this bean belong on" keeps a definite answer as the module grows. Three
 * production classes plus this charter is four {@code .java} files, and there is no fifth type. The
 * directory holds all four.</p>
 *
 * <p>Refactoring Rationale: this section headed itself "two delivered" and its two paragraphs argued at
 * length why {@code SqsConfig}'s entry below was a target description rather than a delivered one, and
 * why an empty configuration would not be authored merely to make the roster true. Both arguments have
 * been answered by the class landing with beans in it, so what they now do is tell a reader to expect
 * an absence that is not there -- the same failure mode in the opposite direction, and the one this
 * charter's own preamble warns about when it says a roster a reader cannot tell apart from an inventory
 * stops being usable. They are replaced rather than amended because a paragraph whose subject is why
 * something is missing has nothing left to say once it arrives. Assumptions: the closed set of three is
 * unchanged and was never in question; only its delivery state was, and
 * {@code services/batch-service/pom.xml} records the same closure independently when it declines to
 * attach a hand-built client to a fourth class this roster forecloses.</p>
 *
 * <p>Assumptions: each of the three was authored only once it could be authored honestly, and the
 * sequence is worth recording because it is the reason the roster and the directory disagreed for as
 * long as they did. {@code DataSourceConfig} came first because a search path and a migration scope are
 * decisions a module needs settled before its first entity. {@code BatchConfig} could not be written
 * ahead of the jobs whose steps it wires, so it was written WITH them: it supplies the time source the
 * ledger stamps its rows with and one nested builder that wraps a job's work in a ledger-guarded step,
 * so a redrive of an already-completed step is a no-op. {@code SqsConfig} came last because it had the
 * least to go on -- no reference batch program names a queue at all -- so its scope had to be derived
 * from the plan and from the one messaging role no other context owns. The durable ledger itself
 * remains {@code BatchStepLedger} in the sibling service package, which is where its transaction
 * posture belongs.</p>
 *
 * <ul>
 *   <li>{@code BatchConfig} owns the module's step infrastructure -- the time source the durable
 *       ledger stamps its rows with, and the nested builder that wraps one unit of work in a
 *       ledger-guarded step -- and the job-parameter contract those steps read. Trade-offs: the step
 *       it builds is a TASKLET rather than a chunk-oriented read-process-write step. A chunk-oriented
 *       step commits per chunk, at a boundary the framework chooses and no reference paragraph
 *       corresponds to, which would make a partial state observable at a place the baseline has none
 *       and a redrive non-idempotent; the tasklet leaves the boundary to the job, and the ledger row
 *       is what makes the redrive a no-op. Assumptions: what each job then DOES with that freedom
 *       differs and is not decided here. {@code PostTransactionsJob} suspends the tasklet's own
 *       boundary and opens one transaction per feed record, matching the reference paragraph that
 *       commits three writes per record; every other job commits once for the pass, and the accepted
 *       cost there is that a very large run holds one transaction open for its duration. It is the
 *       interlock with the
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
 *   <li>{@code SqsConfig} owns publish-only access to the terminal error sink: a bounded synchronous
 *       queue client, and the closed set of message attributes a published event carries, whose
 *       payload shape is fixed by the {@code BatchErrorEvent} record in the sibling {@code dto}
 *       package. Assumptions: the class owns NO listener -- it declares no listener container factory
 *       and no {@code @SqsListener}, and listener startup is switched off in
 *       {@code src/main/resources/application.yml} where the lifecycle concern belongs. The absence is
 *       stated rather than left to be discovered, because this module has no consumer at all: no
 *       reference batch program reads a queue, and the two reference producers of the sink's own name
 *       are online programs of the inquiry extension that migrate to other contexts. Trade-offs: the
 *       class is property-gated, so a task whose
 *       selected job has nothing to report starts and exits cleanly instead of failing on a queue it
 *       never needed. That follows from this module being argument-driven:
 *       {@code BatchApplication} selects the unit of work from its {@code --job=} argument, never
 *       from message arrival, and the queue starter reaches this module only through
 *       {@code io.awspring.cloud:spring-cloud-aws-starter-sqs} in
 *       {@code services/batch-service/pom.xml}. Assumptions: what the gate opens IS validated at
 *       startup -- a blank address, an ordered destination, or a source identifier wider than the
 *       reference field it populates each fail context refresh. What is still discovered at the
 *       first publish is only whether the queue EXISTS, which is deliberately not probed: probing
 *       it would make context refresh depend on a reachable queue and would pass silently in every
 *       test and local run, which is the same objection the sibling contexts record against reading
 *       a queue's own settings at startup.</li>
 *   <li>{@code SqsBatchFailureReporter} owns the STEP-LEVEL occasion, and issues no send of its own.
 *       It satisfies {@code com.carddemo.batch.service.BatchFailureReporter}, the port the durable step
 *       ledger reports a failed step through, by handing the {@code BatchErrorEvent} the sibling
 *       {@code dto} package defines to the service package's {@code BatchErrorPublisher} -- the
 *       module's one sender -- and absorbing anything that escapes it, including an {@code Error}, so
 *       that a reporting problem can never replace the step failure it was reporting. Assumptions:
 *       there are two occasions and one sender. {@code BatchErrorPublisher} also carries the RUN-level
 *       notification the entry point announces as the process exits, which names the graded return code
 *       and no {@code AbendDetail}, while this occasion reports a failed step and carries a redacted
 *       one; the sender admits the first attempt that reaches the sink and suppresses any later attempt
 *       for the same run, so one failed run puts one message on the queue whichever occasion got there
 *       first. Refactoring Rationale: this class used to hold a client, a mapper and the binding and
 *       send for itself, which is what made a single hard failure publish TWICE -- once here with
 *       diagnostics and once from the entry point without them -- against the sender's documented
 *       contract of one notification per failed run. Two senders cannot enforce that contract between
 *       them, because neither can see what the other put on a queue that has no key to deduplicate on.
 *       Refactoring Rationale: this adapter had no bean declaration at all. The ledger takes the port
 *       as an {@code Optional} and the container resolves an absent candidate to empty, so the gap
 *       failed no context, no test and no build -- it silently disabled the step-level report, leaving
 *       every step failure recorded in its ledger row and none of them reaching the terminal error sink
 *       the migration plan provisions at its section 0.4.1.8, while the startup line the gate emits
 *       reported publishing as ENABLED. {@code SqsConfig.batchFailureReporter} now declares it behind
 *       that same gate. Trade-offs: sending is a side effect on a network client and the
 *       binding is a validated value, so they are two classes rather than a method on the record.
 *       That keeps one assertion able to cover the whole attribute contract with no transport
 *       present, and it is the same split the module already applies between the ledger's decision
 *       and the ledger's writer.</li>
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
 * chartered for four classes and five files, and states why there is no published-contract
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
 *       batch role at {@code data-migration/sql/V0__schemas_and_roles.sql:723-724} and holds
 *       {@code BatchRun}, mapped to {@code batch.batch_run}, together with the framework's own job
 *       repository tables.</li>
 *   <li>{@code ledger} is owned by {@code transaction-service} and reached under a cross-schema
 *       write grant, {@code SELECT, INSERT, UPDATE} on its tables at
 *       {@code data-migration/sql/V0__schemas_and_roles.sql:715}. {@code Transaction} and
 *       {@code DailyTransaction} map into it, and so do the category-balance and reject records the
 *       plan assigns this module.</li>
 *   <li>{@code account} is owned by {@code account-service} and reached under a grant narrowed to
 *       one table: {@code SELECT} across the schema at
 *       {@code data-migration/sql/V0__schemas_and_roles.sql:1215}, with {@code UPDATE} granted on
 *       {@code account.accounts} alone at its line 1226. {@code Account} maps to that table and
 *       {@code CardXref} to {@code account.card_xref}, which this role may read and not write.
 *       Assumptions: one named table is the exact privilege because the whole nightly chain holds
 *       only two write sites against this schema, both rewriting an account master that already
 *       exists -- {@code app/cbl/CBTRN02C.cbl:554} and {@code app/cbl/CBACT04C.cbl:356}, each
 *       reading {@code REWRITE FD-ACCTFILE-REC FROM  ACCOUNT-RECORD}. A schema-wide write grant
 *       would also widen silently, since a table added to that schema afterwards would be writable
 *       the moment it was created with nothing recording that it had become so.</li>
 *   <li>{@code reference} is SELECT ONLY, and no write grant exists: its grant at
 *       {@code data-migration/sql/V0__schemas_and_roles.sql:1277} carries {@code SELECT} and
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
 * constraint over {@code (run_id, step_name)} above a surrogate identity key, and that constraint is
 * the mechanism that makes a redriven orchestrator state a no-op for a step that already completed
 * rather than a second application of the same work.</p>
 *
 * <p>Refactoring Rationale: <b>both halves are now operative, and this paragraph used to say they were
 * not.</b> The framework's own job repository is wired here: because the business date is added as an
 * identifying job parameter, a repeat of a business date that already completed is recognised and
 * reported clean, which is the guarantee {@code BatchApplication} implements at its
 * {@code JobInstanceAlreadyCompleteException} branch. The {@code batch.batch_run} half is operative too:
 * every step of all seven job beans runs through the ledger, so each step of each run
 * reads and writes its own ledger row and a mid-chain redrive of one state skips the states that already
 * completed. The earlier text said the table's "only class ... has no production caller while the job
 * beans remain unauthored", which described a state of this module that no longer holds. A charter
 * that understates what it delivers is not a safe error: a reader planning a recovery would have
 * concluded that step-level redrive was unavailable and rebuilt it.</p>
 *
 * <p>Assumptions: the ledger is reached by TWO spellings and not one, and stating only the first would
 * send a reader looking for the wrong thing in five of the seven jobs. Measured at this revision, the
 * two dataset round-trip jobs -- {@code ExportJob} and {@code ImportJob} -- build their step through
 * {@code LedgerGuardedStep} below, which wraps the body and grades the exit status for them; the other
 * five build their own step and call {@code BatchStepLedger.runStep} from inside their own tasklet,
 * because each of them also has to publish counters or banners the shared builder does not know about.
 * Trade-offs: two spellings of one guarantee is a real cost, and it is accepted rather than resolved by
 * forcing all seven through the builder, because the builder's contract is a body that returns a graded
 * outcome and nothing else -- widening it to carry a job's own counters would put five jobs' reporting
 * concerns into this package. What matters for the guarantee is that BOTH spellings reach the same
 * method on the same class, so neither can record a step outcome the other would not.</p>
 *
 * <p>Assumptions: the granularity available is therefore the STEP, and the business-date granularity is
 * the coarser guarantee that still holds beneath it. The two are complementary rather than alternative --
 * a repeated business date is refused by the framework before any step runs, and a redriven execution of
 * the same run identifier is filtered step by step by the ledger.</p>
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
