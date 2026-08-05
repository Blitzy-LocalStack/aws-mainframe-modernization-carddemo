/**
 * Batch bounded context of the CardDemo mainframe-to-AWS migration: the Spring
 * Batch re-expression of the nightly z/OS batch pipeline.
 *
 * <p><b>Purpose.</b> This package and its seven subpackages own the migrated
 * form of the batch work that ran under JCL and JES2 against VSAM: the
 * daily-transaction preflight, transaction posting, interest accrual, the
 * transaction backup and combine steps, and the export and import round trip.
 * Each of those was a job step selected by name, handed its inputs through
 * data definition statements and its business date through a parameter, and
 * judged by the return code it left behind. That shape is preserved
 * deliberately. The platform underneath it changed; the observable behaviour
 * on top of it did not.</p>
 *
 * <p><b>This module is argument-driven, not request-driven, and it is not a
 * server at all.</b> A job here is started by an AWS Step Functions state that
 * runs an ECS Fargate task through the synchronous run-task integration, and
 * the state's container overrides carry the job token and the business date as
 * process arguments. There is no HTTP surface of any kind: no controller, no
 * published interface contract, no route by which anything outside that state
 * machine can start work, and <em>no listening socket</em>. The task runs to
 * completion, the JVM exits, and the invoking state reads the process exit
 * status described under <em>The exit-status contract</em> below.</p>
 *
 * <p><strong>Assumptions:</strong> the absence of a web stack is the mechanism
 * as well as the intent, which is why it is stated this early. This is the one
 * deployable in the reactor whose {@code pom.xml} declares neither
 * {@code spring-boot-starter-web} nor {@code spring-boot-starter-actuator}. With
 * no servlet API on the classpath Spring Boot deduces a non-web application by
 * itself, so no {@code spring.main.web-application-type} property is set and
 * none is needed. An embedded servlet container would be a non-daemon listener
 * that keeps the process alive after the final step has finished, so the task
 * would never reach a terminal state, the synchronous run-task integration would
 * never return, and the nightly chain would stall on a step that had in fact
 * completed correctly.</p>
 *
 * <p><b>This is the only module measured directly against the golden-master
 * parity oracle, which raises the bar for everything inside it.</b> The COBOL
 * three-layer functional-parity oracle suite under {@code tests/} can drive
 * the batch programs end to end, and it cannot do the same for the online
 * ones: {@code tests/README.md} records in its known-limitations section that
 * the online {@code CO*} programs cannot run end to end without a CICS
 * runtime, which the runner does not have. The batch chain is therefore the
 * one place where COBOL output and Java output can be laid side by side and
 * compared byte for byte after timestamp normalisation. Every other module
 * under {@code services/} is held to its transcribed rules; this one is
 * additionally held to committed bytes, and where prose and committed bytes
 * disagree the bytes are right.</p>
 *
 * <h2>Parameters, return values and exceptions: declared inapplicable</h2>
 *
 * <p>A package declaration accepts no parameter, yields no value and raises
 * nothing, so this charter deliberately carries no parameter, return or
 * exception at-clause, and no authorship, availability or revision at-clause
 * either.</p>
 *
 * <p><strong>Assumptions:</strong> the inapplicability is stated rather than
 * left silent because the user-specified Explainability rule -- the single
 * rule governing this project, and the reason this file exists -- names at its
 * line 39 a docstring that omits parameters, return values or purpose among
 * its forbidden patterns, and a reader has to be able to distinguish a
 * declared inapplicability from an oversight. Inventing the at-clauses instead
 * would be worse than useless: Javadoc has no parameter, return or exception
 * concept for a package, and the repository rule set audits at-clause bodies
 * for emptiness, so a fabricated clause would either be discarded or flagged.
 * Of the four docstring elements the rule enumerates at its lines 18 to 21,
 * exactly one applies to this compilation unit, and the paragraphs above
 * account for the other three.</p>
 *
 * <h2>What each subpackage owns</h2>
 *
 * <p>Seven subpackages, and no eighth. The list is closed, so the question
 * "which subpackage does this belong in" keeps a definite answer as the tree
 * grows:</p>
 *
 * <ul>
 *   <li><b>{@code job}</b> -- the seven Spring Batch job definitions:
 *       {@code PreflightDailyTransactionsJob}, {@code PostTransactionsJob},
 *       {@code CalculateInterestJob}, {@code BackupTransactionsJob},
 *       {@code CombineTransactionsJob}, {@code ExportJob} and
 *       {@code ImportJob}. A type here wires readers, processors, writers and
 *       step ordering, and delegates every business rule downward.</li>
 *   <li><b>{@code service}</b> -- the transcribed COBOL business rules:
 *       {@code PostingValidationService}, {@code CategoryBalanceService},
 *       {@code InterestCalculationService} and
 *       {@code DatasetGenerationService}. This is where a COBOL paragraph
 *       becomes a named method, which is what lets the traceability matrix
 *       cite paragraph-to-method pairs rather than gesture at a file.</li>
 *   <li><b>{@code repository}</b> -- {@code BatchRunRepository} plus the
 *       data access over the {@code ledger} and {@code account} schemas this
 *       module reaches under grant. Every file verb of the baseline lands here
 *       and nowhere else.</li>
 *   <li><b>{@code domain}</b> -- {@code BatchRun} plus the local entity
 *       mappings for the granted tables.</li>
 *   <li><b>{@code dto}</b> -- the job argument and result records, taken
 *       field for field from the copybook layouts.</li>
 *   <li><b>{@code mapper}</b> -- the hand-written anti-corruption layer.
 *       This is the only place copybook representation concerns are permitted
 *       to appear: fixed widths, sign overpunch, packed decimal, dropped
 *       {@code FILLER} and the baseline field-name misspellings. Everything
 *       downstream of a mapper works with clean domain objects.</li>
 *   <li><b>{@code config}</b> -- exactly three classes:
 *       {@code BatchConfig}, {@code DataSourceConfig} and {@code SqsConfig}.
 *       There is deliberately no {@code SecurityConfig}: this module opens no
 *       listener, so there is no filter chain for one to configure. The reason
 *       is recorded below under "Boundaries this package does not cross".</li>
 * </ul>
 *
 * <p>Each of those seven carries its own package charter, so this subtree
 * holds eight charter files including this one. Every one of the eight exists
 * for the same reason this one does: the user-specified Explainability rule
 * attaches at its line 15 the docstring duty to every module entry point, a
 * Java package declaration is that entry point, and a package declaration can
 * carry a docstring only in a {@code package-info.java}. The Javadoc block is
 * not decoration on this file; it is the file's entire reason to exist, and a
 * bare {@code package} statement would be a failure rather than a minimum.</p>
 *
 * <p><strong>Assumptions:</strong> no charter file exists at {@code com/} or
 * at {@code com/carddemo/}, and none belongs there. The repository's
 * charter-presence check is a file-set check in
 * {@code config/checkstyle/checkstyle.xml}: it fires only for a directory that
 * contains a compilation unit the audit actually processed. Both of those
 * directories are pure namespace directories holding no compilation unit, so
 * no violation is reachable in either -- while the companion check that audits
 * content would immediately demand real Javadoc from any defensive stub placed
 * there. A stub above this directory would therefore satisfy no check that
 * could ever have fired and expose itself to one that would. This directory is
 * where the files begin.</p>
 *
 * <h2>Baseline lineage: which program or job each artifact re-expresses</h2>
 *
 * <p>Baseline paths below are cited for provenance. Nothing under
 * {@code app/**} is read at run time, and nothing under it is modified by this
 * migration or by anything else. Line numbers refer to the source as
 * committed; columns 73 to 80 of a COBOL or JCL line carry a sequence field
 * and are not part of the statement.</p>
 *
 * <dl>
 *   <dt>{@code app/cbl/CBTRN01C.cbl} into
 *       {@code PreflightDailyTransactionsJob} -- nightly chain state 3</dt>
 *   <dd>The one genuinely driverless program in the baseline. No job among
 *       the thirty-eight files in {@code app/jcl/} names it, so the state
 *       machine supplies an invocation the baseline never had. It is also
 *       strictly read-only: the program contains no {@code WRITE} statement
 *       and no {@code RETURN-CODE} statement at all.</dd>
 *
 *   <dt>{@code app/cbl/CBTRN02C.cbl} into {@code PostTransactionsJob},
 *       {@code PostingValidationService} and {@code CategoryBalanceService} --
 *       nightly chain state 4</dt>
 *   <dd>Driven by {@code app/jcl/POSTTRAN.jcl:23}, which reads
 *       {@code //STEP15 EXEC PGM=CBTRN02C} and carries no {@code PARM}. The
 *       reject stream is a new generation created by that same step at
 *       {@code app/jcl/POSTTRAN.jcl:34-38}, at a record length of 430.</dd>
 *
 *   <dt>{@code app/cbl/CBACT04C.cbl} into {@code CalculateInterestJob} and
 *       {@code InterestCalculationService} -- nightly chain state 5</dt>
 *   <dd>Driven by {@code app/jcl/INTCALC.jcl:22}, which reads
 *       {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}. That
 *       ten-character {@code PARM} is the origin of the business-date
 *       invariant recorded further down.</dd>
 *
 *   <dt>{@code app/jcl/TRANBKP.jcl} into {@code BackupTransactionsJob} --
 *       nightly chain state 6</dt>
 *   <dd>The copy step is {@code app/jcl/TRANBKP.jcl:23}, which reads
 *       {@code //STEP05R EXEC PROC=REPROC,} and continues at
 *       {@code app/jcl/TRANBKP.jcl:24} with
 *       {@code // CNTLLIB=AWS.M2.CARDDEMO.CNTL}. It is a catalogued procedure
 *       invocation, not a direct {@code PGM=IDCAMS} one, and reading it as the
 *       latter loses the control library the procedure resolves its control
 *       cards from. The same job carries the only soft-warn continuation gate
 *       in the tree, at {@code app/jcl/TRANBKP.jcl:51}
 *       ({@code //STEP10 EXEC PGM=IDCAMS,COND=(4,LT)}).</dd>
 *
 *   <dt>{@code app/jcl/COMBTRAN.jcl} into {@code CombineTransactionsJob}
 *       -- nightly chain state 7</dt>
 *   <dd>The merge step is {@code app/jcl/COMBTRAN.jcl:22}
 *       ({@code //STEP05R  EXEC PGM=SORT}); no COBOL program stands behind this
 *       one. It is the join point between the posting and interest pipelines,
 *       and the joining is done by data definition rather than by code: the
 *       concatenated {@code SORTIN} spans {@code app/jcl/COMBTRAN.jcl:23-26},
 *       a named definition at lines 23 to 24 reading {@code TRANSACT.BKUP(0)}
 *       followed by an unnamed continuation definition at lines 25 to 26
 *       reading {@code SYSTRAN(0)}. Missing that second, unnamed definition
 *       drops the system-generated interest transactions from the combined
 *       output entirely, and the ordering itself is declared at
 *       {@code app/jcl/COMBTRAN.jcl:28} as {@code TRAN-ID,1,16,CH} with
 *       {@code app/jcl/COMBTRAN.jcl:30} requesting
 *       {@code SORT FIELDS=(TRAN-ID,A)}.</dd>
 *
 *   <dt>{@code app/cbl/CBEXPORT.cbl} into {@code ExportJob} -- outside the
 *       nightly chain</dt>
 *   <dd>Driven by {@code app/jcl/CBEXPORT.jcl:43}, which reads
 *       {@code //STEP02 EXEC PGM=CBEXPORT} and carries no {@code PARM}.</dd>
 *
 *   <dt>{@code app/cbl/CBIMPORT.cbl} into {@code ImportJob} -- outside the
 *       nightly chain</dt>
 *   <dd>Driven by {@code app/jcl/CBIMPORT.jcl:22}, which reads
 *       {@code //STEP01 EXEC PGM=CBIMPORT} and carries no {@code PARM}.</dd>
 * </dl>
 *
 * <p>Two programs a reader may expect here are elsewhere, and one is retired.
 * {@code app/cbl/CBSTM03A.CBL} and {@code app/cbl/CBSTM03B.CBL}, both with
 * upper-case extensions, generate statements and belong to
 * {@code com.carddemo.reporting}, not to this package.
 * {@code app/cbl/COBSWAIT.cbl} is retired with no target: its driver
 * {@code app/jcl/WAITSTEP.jcl:22} reads {@code //WAIT     EXEC PGM=COBSWAIT}, and
 * a step whose whole function is to wait is expressed by a state transition in
 * the state machine rather than by a job in this package. Note that its name
 * begins {@code CO} rather than {@code CB}, so it counts among the eighteen
 * {@code CO*} programs of {@code app/cbl} and not among the twelve {@code CB*}
 * ones, which is why the twelve-program batch figure is unaffected by its
 * retirement.</p>
 *
 * <h2>The one documented exception to database-per-service purity</h2>
 *
 * <p>This module writes into two schemas it does not own. {@code ledger} is
 * owned by {@code transaction-service} and {@code account} by
 * {@code account-service}, and posting updates both inside one transaction
 * together with its own work. It runs against the single Aurora cluster under
 * a dedicated database role holding narrowly-scoped cross-schema write grants
 * on {@code ledger} and {@code account} and on nothing else, so the posting
 * unit of work stays a single atomic commit.</p>
 *
 * <p><strong>Alternatives Considered:</strong> two decompositions were
 * evaluated for that unit of work and both were rejected on the same concrete
 * ground. A saga would replace one atomic commit with a sequence of committed
 * steps plus compensating reversals; a transactional outbox with compensating
 * reversal would do the same with a different trigger. Either makes
 * partial-posting states observable -- a posted transaction whose category
 * balance has not moved yet, or an updated account balance with no matching
 * transaction row -- and those states do not exist in the baseline. The
 * baseline unit of work is {@code app/cbl/CBTRN02C.cbl:424-444}, the
 * {@code 2000-POST-TRANSACTION} paragraph, which performs
 * {@code 2700-UPDATE-TCATBAL} at line 440, then
 * {@code 2800-UPDATE-ACCOUNT-REC} at line 441, then
 * {@code 2900-WRITE-TRANSACTION-FILE} at line 442, and reaches line 444 with
 * all three applied or none. A golden-master comparison would flag an
 * intermediate state as a parity failure, and it would be right to. The scoped
 * grant keeps the commit atomic and needs no coordinator at all, so it is both
 * the lower-risk option and the one that preserves observable behaviour.</p>
 *
 * <p><strong>Assumptions:</strong> the grants themselves are not created here.
 * They are created by {@code data-migration/sql/V0__schemas_and_roles.sql}
 * together with the schemas and the per-service roles. The schema definitions
 * are not created here either: {@code ledger} migrations are owned by
 * {@code transaction-service} and {@code account} migrations by
 * {@code account-service}. This package never creates, alters or seeds another
 * service's schema, and it owns migrations for the {@code batch} schema
 * alone.</p>
 *
 * <p><strong>Assumptions:</strong> the only intra-repository Maven dependency
 * this module declares is {@code common-lib}. It does not depend on
 * {@code transaction-service} and it does not depend on
 * {@code account-service}. This module and {@code transaction-service} agree
 * through the physical schema and never through code, which is what keeps a
 * shared table from becoming a shared deployable. A service module importing
 * another service module's {@code domain} package is forbidden, and the
 * prohibition belongs to the {@code architecture-rules} Surefire execution
 * declared in {@code services/pom.xml} -- which scans the shared kernel's test
 * artifact into every module and selects the layering rules by the simple name
 * {@code LayeringRulesTest} -- rather than to a convention, so it is a rule a
 * build evaluates rather than a comment nobody runs.</p>
 *
 * <h2>Boundaries this package does not cross</h2>
 *
 * <p>Seven subpackages and their authors read this charter to learn where the
 * edges are, so the edges are stated rather than implied.</p>
 *
 * <p><b>No {@code api} subpackage, no controller, no published interface
 * contract, no {@code OpenApiConfig} and no {@code SecurityConfig}.</b> Of the
 * eight service rows in the migration plan's transformation mapping, this is
 * the single row that omits the published-contract directory, and the module's
 * own {@code pom.xml} matches that by declaring no API documentation starter,
 * no web starter, no actuator and neither security starter. There is also no
 * route for this module at the managed edge: {@code infra/modules/}
 * {@code api-gateway-http} creates none and refuses one by validation.</p>
 *
 * <p><strong>Assumptions:</strong> the absent {@code SecurityConfig} is not an
 * unguarded surface, because there is no surface. An authorization filter chain
 * defends a listener, and this module opens none: with no servlet web
 * application on the classpath both the security and the resource-server
 * autoconfigurations stay inactive, so declaring either starter would add an
 * artifact that installs no filter while leaving a reader convinced something
 * was being defended. The concern those starters would answer is real and is
 * answered one level further in. An unauthenticated caller inside the private
 * application tier reaches an actuator endpoint by opening a socket to a
 * listening port, and this container has none to open, so the health, info and
 * metrics data the other eight modules publish has no analogue here that could
 * be read without a token. The edge reaches the same conclusion from the other
 * side, which is why the validation cited above refuses a {@code /batch} route
 * rather than merely omitting one.</p>
 *
 * <p><strong>Trade-offs:</strong> an operator endpoint cannot be added here
 * without first adding the web starter, which would mean reversing the one-shot
 * lifecycle argument recorded below. That is the intended cost rather than an
 * oversight: it keeps the single invocation path the state machine's synchronous
 * run-task call, so there is no second authorization surface to design and no
 * second argument source that could disagree with the first.</p>
 *
 * <p><strong>Alternatives Considered:</strong> an administrative endpoint that
 * would let an operator trigger a job over HTTP was evaluated and rejected,
 * and {@code services/batch-service/pom.xml} records that rejection alongside
 * the API documentation starter it declines to declare. The only invocation
 * path in the target architecture is the synchronous run-task call from a state
 * machine state, which already passes job selection and the business date as
 * container command arguments. An HTTP trigger would add a second invocation
 * path and, with it, a second authorization surface to design, test and
 * defend, for no operational gain -- and it would give the same job two
 * argument sources that could disagree.</p>
 *
 * <p><strong>Alternatives Considered:</strong> keeping the embedded web stack so
 * that an actuator health endpoint would be reachable over HTTP inside the
 * task's own network namespace, which is what the seven online services do.
 * Rejected, and the dependency removed from
 * {@code services/batch-service/pom.xml} along with the actuator itself. A
 * servlet container is a non-daemon listener, so it holds the JVM open once the
 * last step has finished; under {@code ecs:runTask.sync} the invoking state
 * waits for a terminal task state, so the process outliving its work is not
 * merely surplus but a stall in the nightly chain. An actuator without a web
 * server would meanwhile serve nothing over HTTP and sit in the build with no
 * consumer.</p>
 *
 * <p><strong>Trade-offs:</strong> health for this module is therefore the
 * process exit status and nothing else, which is a genuine divergence from the
 * shared {@code /actuator/health} convention and is matched on the
 * infrastructure side rather than left inconsistent: the batch instantiation of
 * {@code infra/modules/ecs-service} sets {@code attach_load_balancer} and
 * {@code create_service} to false, so no target group and no long-running
 * service exists to probe, and neither {@code infra/modules/alb} nor
 * {@code infra/modules/api-gateway-http} publishes a batch route. What is given
 * up is a periodic liveness signal during a run; what that signal would have
 * reported is only that the interpreter was still up, including while a load was
 * failing, so its absence removes a guarantee this container never provided.</p>
 *
 * <p><b>No ignore files.</b> Neither a {@code .gitignore} nor a
 * {@code .dockerignore} belongs in this module or anywhere in this tree;
 * ignore rules for build output live in the single repository-root
 * {@code .gitignore}.</p>
 *
 * <p><b>Libraries deliberately not adopted, each for a stated reason.</b>
 * Lombok is not used, because generated accessors and constructors cannot
 * carry the Javadoc the user-specified Explainability rule requires, so a
 * Lombok-built class either fails the documentation gate or has to be
 * suppressed out of it; Java 21 {@code record} types with explicit
 * constructors give the same brevity with members that can be documented.
 * MapStruct is not used, because copybook-to-transfer-object mapping is not
 * mechanical -- it drops {@code FILLER}, masks the primary account number to
 * its last four digits, suppresses the card verification value entirely,
 * encrypts the national and government-issued identifiers and renames three
 * misspelled baseline fields -- and each of those decisions needs its
 * justification at the mapping site, which a generated mapper has nowhere to
 * hold. No resilience library and no circuit breaker are added: retry support
 * lives in the framework core the parent dependency management already
 * supplies, the durable retry tier is queue redelivery with a dead-letter
 * queue plus per-state retry in the state machine, and a breaker would add a
 * failure mode without removing one for in-network calls that already carry
 * bounded timeouts. No cache tier, no streaming platform and no read replica:
 * the baseline has none of the three, and parity is the requirement.</p>
 *
 * <h2>Invariants every file in this subtree inherits</h2>
 *
 * <p><b>Money is exact fixed point end to end.</b> The migration plan's
 * transformation rule T3 fixes the representation at every hop:
 * {@code NUMERIC(p,2)} in SQL, {@code BigDecimal} at scale 2 in Java, and a
 * JSON string on any wire. {@code float}, {@code double} and JSON numbers are
 * forbidden in the money path, and the prohibition is asserted by the
 * architecture test rather than requested in prose. A JSON number is parsed
 * into an IEEE-754 double by most clients, which destroys exactness at
 * precisely the boundary a user sees.</p> * <p><strong>Assumptions:</strong>
 * two precisions meet in this module and a reader has to know which is which.
 * Account-side money is {@code PIC S9(10)V99}, twelve display bytes, mapping
 * to {@code NUMERIC(12,2)} -- for example {@code ACCT-CURR-BAL} at
 * {@code app/cpy/CVACT01Y.cpy:7} in the 300-byte account record.
 * Transaction-side money is {@code PIC S9(09)V99}, eleven display bytes,
 * mapping to {@code NUMERIC(11,2)} -- for example {@code TRAN-CAT-BAL} at
 * {@code app/cpy/CVTRA01Y.cpy:9} in the 50-byte category-balance record. The
 * two cross at exactly two sites, and both are additions of a transaction-side
 * amount into an account-side balance: {@code app/cbl/CBTRN02C.cbl:547}
 * ({@code ADD DALYTRAN-AMT  TO ACCT-CURR-BAL}) and
 * {@code app/cbl/CBACT04C.cbl:352}
 * ({@code ADD WS-TOTAL-INT  TO ACCT-CURR-BAL}). Declaring an account balance at
 * the narrower scale would silently truncate the widest legal value rather
 * than raise anything.</p>
 *
 * <p><b>Arithmetic order is preserved.</b> The migration plan's transformation
 * rule T4 forbids reordering an expression the baseline evaluates in a
 * particular sequence. The interest computation is at
 * {@code app/cbl/CBACT04C.cbl:464-465}, where line 464 reads
 * {@code COMPUTE WS-MONTHLY-INT} and line 465 reads
 * {@code = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. It multiplies before it
 * divides and it carries no {@code ROUNDED} phrase.</p>
 *
 * <p><strong>Assumptions:</strong> the Java therefore forms the product at
 * full precision and only then divides, applying scale 2 with
 * {@code RoundingMode.HALF_UP} at that single point. The arithmetic order is
 * part of the behavioural contract and not an implementation detail: dividing
 * first and multiplying second yields a different final cent on many balances,
 * and the golden-master comparison would report it as a parity failure --
 * correctly, but only after the fact.</p>
 *
 * <p><strong>Assumptions:</strong> the rounding mode is pinned by the migration
 * plan's transformation rule T3, which fixes Java money at scale 2 with
 * {@code RoundingMode.HALF_UP} and admits no exception, and it is stated for
 * this accrual specifically in
 * {@code docs/architecture/data-model-and-schema-mapping.md}. The absent
 * {@code ROUNDED} phrase means the reference statement discards its surplus
 * digits instead, so the two differ by one cent on a quotient that lands
 * exactly on a half cent: a category balance of {@code 1000.80} at a rate of
 * {@code 2.50} gives {@code 2.0850} exactly, where this module returns
 * {@code 2.09}. That is a behavioural divergence rather than a difference of
 * expression, so it is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md} with every other
 * intentional divergence. Assumptions: a single mode across the whole money
 * path is what makes it checkable; a second mode for this one job would put an
 * undocumented exception inside the module least able to afford one, since a
 * posting or accrual figure that is wrong in the cents still looks
 * plausible.</p>
 *
 * <p><b>The business date is a job parameter, never a wall-clock read.</b>
 * {@code app/jcl/INTCALC.jcl:22} injects {@code PARM='2022071800'}, and the
 * program receives it through {@code PROCEDURE DIVISION USING EXTERNAL-PARMS}
 * at {@code app/cbl/CBACT04C.cbl:180}, whose linkage group declares
 * {@code PARM-LENGTH PIC S9(04) COMP} at line 177 and
 * {@code PARM-DATE PIC X(10)} at line 178. Injection is what makes a rerun
 * reproducible and a golden comparison possible at all.</p>
 *
 * <p><strong>Assumptions:</strong> the token is an opaque ten-character
 * passthrough, not a validated calendar date in a single canonical layout. The
 * declared picture is {@code X(10)}, alphanumeric, and both the ISO form and
 * the compact form the driver injects appear among the committed golden
 * fixtures, so a job here accepts the token as given rather than reformatting
 * it. The only legitimate clock read anywhere in this module is a record
 * timestamp, and it goes through an injected {@code java.time.Clock} so a test
 * can pin it.</p>
 *
 * <p><b>One former {@code COPY} becomes exactly one import.</b> The migration
 * plan's transformation rule T2 requires that import to come from the single
 * package that owns the contract, and that shared concerns -- money, codecs,
 * error types, the timestamp form, the validation flags, the page envelope --
 * come only from {@code com.carddemo.common} and are never re-declared per
 * service. This is the Java analogue of compiling every COBOL program against
 * one copybook include path. The invocation is recorded at
 * {@code tests/README.md:268} as
 * {@code cobc -fixed -fsign=EBCDIC --std=ibm-strict -I app/cpy}, and
 * {@code tests/README.md:540-542} states the discipline it buys in the house's
 * own words: never duplicate a layout, keep it single-sourced from
 * {@code app/cpy/}.</p>
 *
 * <p><strong>Assumptions:</strong> the worked example is the posting program,
 * which draws five contracts through five {@code COPY} statements:
 * {@code CVTRA06Y} at {@code app/cbl/CBTRN02C.cbl:102}, {@code CVTRA05Y} at
 * line 107, {@code CVACT03Y} at line 112, {@code CVACT01Y} at line 121 and
 * {@code CVTRA01Y} at line 126. Five {@code COPY} statements become five
 * imports, each from the one package that owns that record, and none of the
 * five layouts is restated locally. A 300-byte account record decoded against
 * a 299-byte belief is not a build failure, it is a wrong balance, which is
 * why single-sourcing is an invariant here rather than a preference.</p>
 *
 * <p><b>User-visible strings are verbatim.</b> The migration plan's
 * transformation rule T8 carries every message across character for character
 * from its originating copybook or program. In this module that means the four
 * posting reject descriptions, each paired with its numeric reason:
 * {@code INVALID CARD NUMBER FOUND} with reason 100 at
 * {@code app/cbl/CBTRN02C.cbl:385-386}, {@code ACCOUNT RECORD NOT FOUND} with
 * reason 101 at lines 397 to 398, {@code OVERLIMIT TRANSACTION} with reason
 * 102 at lines 410 to 411, and
 * {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION} with reason 103 at lines
 * 417 to 418. The two boundary conditions those last two guard are inclusive
 * in the baseline and stay inclusive here: {@code app/cbl/CBTRN02C.cbl:407}
 * reads {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL}, so a balance landing
 * exactly on the credit limit posts, and {@code app/cbl/CBTRN02C.cbl:414}
 * reads {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}, so a
 * transaction dated exactly on the expiration date posts. The category-balance
 * write keeps its two distinguishable arms as well, chosen at
 * {@code app/cbl/CBTRN02C.cbl:496} and {@code :498}:
 * {@code 2700-A-CREATE-TCATBAL-REC} when the category row is new and
 * {@code 2700-B-UPDATE-TCATBAL-REC} when it already exists.</p>
 *
 * <h2>Two platform replacements this module makes</h2>
 *
 * <p><strong>Refactoring Rationale:</strong> index building is retired
 * outright. The baseline rebuilds the transaction alternate index with an
 * {@code IDCAMS BLDINDEX} step, at {@code app/jcl/TRANIDX.jcl:52}, because a
 * VSAM alternate index is a separate object that a bulk load leaves stale. The
 * relational target maintains a secondary index transactionally as part of the
 * same commit that writes the row, so there is nothing left for a rebuild step
 * to do. The state that occupies that slot in the nightly chain gathers
 * planner statistics instead. Keeping a literal rebuild would have meant
 * inventing work with no effect and then explaining why it never changes
 * anything.</p>
 *
 * <p><strong>Refactoring Rationale:</strong> restart capability is net-new
 * here, and it is worth being precise about what it replaces, because a reader
 * may assume a checkpoint contract existed and was ported. None did. The only
 * {@code RESTART=} anywhere in the thirty-eight jobs of {@code app/jcl/} is
 * commented out, at {@code app/jcl/DEFGDGD.jcl:2}
 * ({@code //*  RESTART=STEP30}), and no {@code CHKPT=} appears in the tree at
 * all, so a failed baseline run was resumed by an operator editing and
 * resubmitting the job. The target replaces that operator step with two
 * mechanisms working together: state machine redrive resumes a failed
 * execution from the state that failed, and the durable
 * {@code batch.batch_run} ledger gives every step an idempotency key so a
 * resumed step that already completed is a no-op rather than a second
 * application of the same work. Presenting this as a port of an existing
 * contract would misrepresent the baseline; presenting it as an improvement is
 * accurate, and the distinction matters when someone asks which behaviour
 * parity testing is entitled to assume.</p>
 *
 * <p><strong>Assumptions:</strong> generation-dataset retention is a real
 * contract and its arity is easy to understate. Ten generation bases exist,
 * not six: six are defined in {@code app/jcl/DEFGDGB.jcl}, three in
 * {@code app/jcl/DEFGDGD.jcl} and one in {@code app/jcl/DALYREJS.jcl}, every
 * one of them at a five-generation scratch limit. The object-store dataset
 * layer therefore provisions ten prefix families with a
 * five-noncurrent-version lifecycle rule, and a job in this package writes a
 * new generation rather than overwriting a current one.</p>
 *
 * <h2>Two documented divergences this package owns, and one that is not ours</h2>
 *
 * <p>Everything under {@code app/**} is reference-only. The known baseline
 * defects are explicitly out of scope for change in COBOL, so the framing
 * throughout is the same in every case: the baseline does one thing, the Java
 * does another, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. No claim is made
 * anywhere in this module that the baseline itself was altered, because it was
 * not.</p>
 *
 * <p><b>Divergence D-1: the export and import record key.</b>
 * {@code app/cbl/CBEXPORT.cbl:68} and {@code app/cbl/CBIMPORT.cbl:40} both
 * declare {@code RECORD KEY IS EXPORT-SEQUENCE-NUM} on a file whose
 * organization is declared indexed immediately above, at
 * {@code app/cbl/CBEXPORT.cbl:66} and {@code app/cbl/CBIMPORT.cbl:38}. The
 * named field is declared inside {@code 01 EXPORT-RECORD}, at
 * {@code app/cpy/CVEXPORT.cpy:16}, but that group reaches the program through
 * a copybook pulled into working storage rather than through the file
 * description's own record description -- {@code app/cbl/CBEXPORT.cbl:94}
 * opens {@code WORKING-STORAGE SECTION} and line 96 is the
 * {@code COPY CVEXPORT} statement. The field is also {@code PIC 9(9) COMP}, a
 * binary usage, where the strict dialect the suite compiles with requires an
 * indexed record key to be display usage. Two independent reasons, therefore,
 * and no compiler flag reaches either. The migrated jobs key the export record
 * on its sequence number as a first-class column of the record they actually
 * write and read, so the round trip is byte-identical in both directions, and
 * the divergence is registered.</p>
 *
 * <p><strong>Refactoring Rationale:</strong> that single baseline defect is
 * the sole cause of the repository's aggregate warn-level green state, and it
 * must never be reported as a regression introduced by this migration.
 * {@code tests/README.md:53} records that only ten of the twelve batch
 * programs build and run; lines 57 to 58 name the defect at those exact two
 * source lines as one that no compiler flag can fix; and lines 62 to 63 record
 * that the build classifies the pair as known-unsupported, expects the
 * documented failure, and aggregates a soft warn rather than poisoning the
 * aggregate result. A reader who sees that warn and treats it as new has
 * mistaken the oracle's honest accounting of an immutable baseline defect for
 * damage.</p>
 *
 * <p><b>Divergence D-3: the final-account interest flush.</b> The read paragraph
 * {@code 1000-TCATBALF-GET-NEXT} spans {@code app/cbl/CBACT04C.cbl:325-348},
 * and on end of file it does exactly one thing --
 * {@code MOVE 'Y' TO END-OF-FILE} at line 340 -- and never performs
 * {@code 1050-UPDATE-ACCOUNT}. Because the driving loop at
 * {@code app/cbl/CBACT04C.cbl:188} is {@code PERFORM UNTIL END-OF-FILE = 'Y'},
 * which evaluates its condition before the body, the trailing
 * {@code ELSE PERFORM 1050-UPDATE-ACCOUNT} at lines 219 to 220 is unreachable.
 * The accrued interest of the last account in the file is consequently never
 * written back. The house fixture document states this independently:
 * {@code tests/fixtures/interest/happy_path/README.md:9} records this
 * divergence, lines 93 to 97 identify the trailing branch as dead code and state that the
 * last account is never written back, and lines 221 to 222 record the measured
 * outcome -- the final account is not flushed, it remains at its opening
 * figure with its cycle fields unchanged -- describing the behaviour as
 * documented and empirically verified and placing any change to it outside
 * scope.</p>
 *
 * <p><strong>Refactoring Rationale:</strong> the omission compounds, which is
 * why the migrated job flushes two things and not one.
 * {@code 1050-UPDATE-ACCOUNT} does more than add interest: alongside
 * {@code ADD WS-TOTAL-INT  TO ACCT-CURR-BAL} at
 * {@code app/cbl/CBACT04C.cbl:352} it also zeroes the cycle buckets, with
 * {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} at line 353 and
 * {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} at line 354. Skipping the paragraph
 * for the last account therefore leaves that account's cycle buckets carrying
 * the previous cycle's figures as well, so the effect accumulates across
 * cycles instead of costing one account one month of interest once.
 * {@code CalculateInterestJob} flushes the final account's accrued interest
 * and resets its cycle buckets, treating the last account exactly as it treats
 * every other, and the divergence is registered. Naming only the interest half
 * here would leave the compounding half to be rediscovered by whoever next
 * reads a cycle total that will not reconcile.</p>
 *
 * <p><b>Divergence D-2 is not ours.</b> The two independently unchecked
 * statement-generator tables belong to {@code app/cbl/CBSTM03A.CBL} and are
 * owned by {@code com.carddemo.reporting}. It is named here only so that a
 * reader who has the divergence register in hand does not search this subtree
 * for it.</p>
 *
 * <h2>The exit-status contract</h2>
 *
 * <p>A job in this package reports its outcome to the orchestrator through the
 * process exit status, and through nothing else. Three tiers exist:</p>
 *
 * <ul>
 *   <li>{@code 0} -- clean completion with no rejects. The posting program
 *       leaves {@code RETURN-CODE} untouched when its reject counter is zero,
 *       and the committed expectation files agree: the recorded return code is
 *       {@code 0} in the {@code happy_path}, {@code boundary_exact_limit},
 *       {@code boundary_expiry_equal}, {@code empty_input} and
 *       {@code zero_balance} posting scenarios under
 *       {@code tests/golden/posting/}, and in all three interest scenarios
 *       ({@code happy_path}, {@code default_fallback} and
 *       {@code zero_balance}) under {@code tests/golden/interest/}.</li>
 *   <li>{@code 4} -- soft warn. The work completed and produced rejects,
 *       and downstream states may still run. The origin is
 *       {@code app/cbl/CBTRN02C.cbl:229}, which reads
 *       {@code IF WS-REJECT-COUNT > 0}, and line 230, which reads
 *       {@code MOVE 4 TO RETURN-CODE}. The recorded return code is {@code 4}
 *       in the {@code reject_100_card_missing},
 *       {@code reject_101_acct_missing}, {@code reject_102_overlimit} and
 *       {@code reject_103_expired} scenarios under
 *       {@code tests/golden/posting/}.</li>
 *   <li>{@code >= 8} -- hard failure from an input-output error or an
 *       abend, on which the state's catch handler routes to failure
 *       notification. The baseline expresses the same three tiers through its
 *       {@code APPL-RESULT} field: {@code 8} is the value pre-set before an
 *       operation so that a silent no-op still reads as failure, {@code 12}
 *       marks an input-output failure and leads to the abend paragraph, and
 *       {@code 16} is the end-of-file sentinel rather than a severity.</li>
 * </ul>
 *
 * <p><strong>Assumptions:</strong> the warn tier belongs to posting alone.
 * {@code app/cbl/CBACT04C.cbl} contains no {@code RETURN-CODE} statement
 * anywhere, and neither does {@code app/cbl/CBTRN01C.cbl}, so for every job in
 * this package other than {@code PostTransactionsJob} the warn tier is
 * unreachable by construction rather than merely unused. That is why all three
 * interest expectation files record {@code 0} and only the four posting reject
 * scenarios record {@code 4}: a job that emitted {@code 4} from interest
 * accrual would be inventing a tier the baseline has no statement to
 * produce.</p>
 *
 * <p><strong>Trade-offs:</strong> the graded numeric scale belongs to the
 * parity oracle and to the batch container's exit status, and nowhere else in
 * this module. The oracle's rubric under {@code tests/} grades 0, 2, 4, 8 and
 * 16, and treats a warn-level aggregate as its green state. The Java build
 * tooling is binary: Maven, the documentation gate, the unit-test runner, the
 * integration-test runner and the assertion library each either pass or fail,
 * and describing a Java build as warn-level green would be a category error
 * that hides a real failure behind a borrowed vocabulary. The single
 * legitimate numeric surface here is the container's process exit status,
 * which is numeric only because the state machine reads it. The cost of
 * quarantining the two vocabularies this strictly is that two adjacent parts
 * of one repository count success differently and a reader has to know which
 * is speaking; the cost of not doing it is a build that reports success while
 * failing.</p>
 *
 * <h2>Two figures a reader may meet elsewhere in this module</h2>
 *
 * <p>Both of the following were verified against the baseline directly, at the
 * lines the two paragraphs below cite, and both are stated here because the
 * module's own supporting prose is easy to read the other way.</p>
 *
 * <p>First, the export and import jobs do have JCL drivers. They are
 * {@code app/jcl/CBEXPORT.jcl:43} ({@code //STEP02 EXEC PGM=CBEXPORT}) and
 * {@code app/jcl/CBIMPORT.jcl:22} ({@code //STEP01 EXEC PGM=CBIMPORT}). What
 * is true of them is that neither is wired into the nightly chain, which makes
 * them unscheduled rather than undriven. The genuinely driverless program is
 * {@code app/cbl/CBTRN01C.cbl}, and it is the only one; conflating the two
 * properties turns one accurate observation into three inaccurate ones.</p>
 *
 * <p>Second, the business-date token is not a validated date in one canonical
 * layout. {@code app/cbl/CBACT04C.cbl:178} declares it
 * {@code PARM-DATE PIC X(10)}, an alphanumeric field, and the driver at
 * {@code app/jcl/INTCALC.jcl:22} injects the compact form
 * {@code '2022071800'}, while committed golden fixtures exercise the ISO form
 * as well. A job here passes the token through as received. Reformatting it
 * into one canonical layout would change the deterministic transaction
 * identifiers derived from it and break the golden comparison on the committed
 * scenarios, which exercise both accepted forms of the token.</p>
 *
 * <h2>Authoring notes</h2>
 *
 * <p><strong>Trade-offs:</strong> this file is restricted to ASCII. Where the
 * cited sources carry a non-breaking hyphen or an em dash, this charter uses
 * an ASCII hyphen-minus or a pair of ASCII hyphens; wording is otherwise
 * unchanged and only those punctuation code points are normalised.
 * {@code tests/README.md} is the one file in the repository that uses the
 * non-breaking hyphen, and it uses it inside the very words this charter has
 * to reproduce, including its four justification labels. Copying from there
 * would yield a label that looks right, greps wrong, and silently fails an
 * audit searching for the canonical spelling. The cost of the restriction is
 * typographically plainer prose; the gain is that every label and every quoted
 * phrase in this file is byte-predictable.</p>
 *
 * <p><strong>Assumptions:</strong> the four labels used throughout --
 * {@code Alternatives Considered:}, {@code Refactoring Rationale:},
 * {@code Assumptions:} and {@code Trade-offs:} -- are spelled as the
 * Explainability rule presents them at its lines 31 to 34: plural,
 * unparenthesised, each followed immediately by a colon. The rule's own
 * emphasis markers sit outside the colon, which is why the colon is part of
 * the label and not part of the sentence after it.
 * {@code Refactoring Rationale:} is reserved for the four genuine platform
 * replacements above -- retired index building, the net-new restart ledger,
 * and divergences D-1 and D-3 -- because the rule defines that label at line
 * 32 as applying when existing code is replaced, and the Java in this subtree
 * replaces nothing: the COBOL stays byte-identical and keeps running.
 * Labelling an ordinary COBOL-versus-Java difference that way would be false,
 * so those are labelled {@code Alternatives Considered:} or
 * {@code Assumptions:} instead.</p>
 *
 * <p><strong>Assumptions:</strong> the documentation gate that audits this
 * file is {@code config/checkstyle/checkstyle.xml} with its companion
 * {@code config/checkstyle/suppressions.xml}, bound to the Maven
 * {@code validate} phase in {@code services/pom.xml} under the execution id
 * {@code checkstyle-documentation-gate}, so it runs before compilation on
 * every local build and not only in continuous integration. Two of its checks
 * bear on this file and they are a deliberate pair: one asserts that a
 * {@code package-info.java} exists in a directory holding compilation units,
 * and the other asserts that the file carries Javadoc. A file holding nothing
 * but a {@code package} statement satisfies the first and fails the second,
 * which is exactly the outcome the pairing is designed to produce. The rule
 * set admits no in-code bypass -- no comment-based or annotation-based
 * suppression filter is configured -- and the suppression file is loaded
 * fail-closed and scoped to generated sources and test fixtures, so nothing
 * under {@code src/main/java} can be suppressed out of the gate. The
 * Explainability rule's validation gate at line 43 is conjunctive: it closes
 * by stating that code missing either the docstring or the decision rationale
 * fails review, so the two obligations are independently fatal and neither
 * compensates for the other.</p>
 */
package com.carddemo.batch;
