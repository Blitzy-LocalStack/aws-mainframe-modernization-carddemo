/**
 * Spring Batch job definitions for the CardDemo nightly batch chain, migrated
 * from z/OS JCL.
 *
 * <h2>What is landed, measured against this directory</h2>
 *
 * <p>Assumptions: all seven jobs the roster below describes are LANDED.
 * {@code PreflightDailyTransactionsJob}, {@code PostTransactionsJob}, {@code CalculateInterestJob},
 * {@code BackupTransactionsJob}, {@code CombineTransactionsJob}, {@code ExportJob} and
 * {@code ImportJob} each have a file in this directory, each declares exactly one job bean, and each
 * registers under its own token. Two tests keep that statement true and they measure different things:
 * {@code services/batch-service/src/test/java/com/carddemo/batch/job/BatchJobRosterTest.java} compares
 * the tokens the seven classes register under against the orchestration vocabulary for set equality, and
 * {@code .../job/JobRegistrationCensusTest.java} assembles a context over all seven and asserts
 * bidirectionally that every declared token resolves to a job bean of that name and that no bean carries
 * a name outside the vocabulary.</p>
 *
 * <pre>
 * this directory: 9 java files = 8 classes + 1 charter
 * </pre>
 *
 * <p>Refactoring Rationale: this section has been corrected twice, and the marker line above is what
 * stops a third correction. It first declared every inventory in this charter a target rather than a
 * measurement, which made the charter unfalsifiable -- a reader could not tell an intended class from a
 * forgotten one, and no test could either. It was then replaced by a measured statement that five of
 * seven jobs had landed and that {@code ExportJob} and {@code ImportJob} had no file. Both files then
 * landed, and the statement outlived them: it told a reader that two capabilities were absent while they
 * sat in this directory. The marker line is the form {@code common-lib}'s
 * {@code PackageCharterInventoryTest} re-measures against this directory on every build, so the figure
 * and the enumerated membership are now checked rather than asserted.</p>
 *
 * <p>Assumptions: the eight classes are the seven jobs plus {@code DatasetPayloadWriter}, which is not a
 * job and registers no bean. It is the shared writer the generation-staging jobs use to put a dataset
 * payload into the object store under the S3 generation convention, and it sits here rather than in
 * {@code com.carddemo.batch.service} because it is a mechanism of writing a job's output rather than a
 * transcribed business rule.</p>
 *
 * <p>Assumptions: the export and import pair carries a constraint the other five do not, and it is
 * recorded because it bounds what their tests can prove rather than whether they exist. Both re-express
 * programs that read the customer and card masters -- {@code app/jcl/CBEXPORT.jcl:49-57} names five input
 * data definitions, two of them those masters -- and this module holds no customer entity and no card
 * entity, because the account and card contexts own them; the migrated pair therefore reads what this
 * module does own and is verified against the record layout rather than against a golden master. There is
 * no golden master to verify either against: {@code tests/README.md:53-69} records that
 * {@code CBEXPORT} and {@code CBIMPORT} do not compile under the open-source compiler at all, because
 * both declare a record key on a field that exists only in working storage, so the reference suite skips
 * their integration test. That defect is NOT reproduced here -- the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md} -- and the baseline stays byte-identical.</p>
 *
 * <p>Every type in this package is a job definition and nothing more: it wires
 * readers, processors, writers and step ordering, and it delegates every
 * business rule to {@code com.carddemo.batch.service}. What binds the seven
 * together is not their subject matter — one posts money, another copies a
 * dataset — but their execution shape. Each is selected <em>by name</em> from
 * the process arguments at start-up, runs to completion exactly once, and
 * reports its outcome to its caller through the process exit status. None is
 * reachable over HTTP, none holds state between invocations, and none reads the
 * wall clock. That shape is precisely what a mainframe job step was, and it is
 * the reason these seven definitions sit together while the rules they enforce
 * sit elsewhere.</p>
 *
 * <p>Five of the seven are states in the eleven-state
 * {@code carddemo-daily-batch} Step Functions state machine, each invoked as a
 * Fargate task through the synchronous run-task integration. The other two are
 * operator-invoked and stand outside the nightly chain. Because the
 * orchestrator — not a Java caller — decides whether the next state runs, the
 * two contracts recorded below are this package's real public surface, more so
 * than any method signature it declares.</p>
 *
 * <h2>Job roster</h2>
 *
 * <p>The package defines exactly seven jobs. Baseline paths are cited for
 * provenance only; nothing under {@code app/**} is read at run time and nothing
 * under it is ever modified.</p>
 *
 * <dl>
 *   <dt>{@code PreflightDailyTransactionsJob} — nightly chain state 3</dt>
 *   <dd>Re-expresses {@code app/cbl/CBTRN01C.cbl}. This is the one genuinely
 *       driverless program in the baseline: no job among the thirty-eight files
 *       in {@code app/jcl/} names it, and neither {@code app/proc/} nor
 *       {@code app/scheduler/} references it either, so the state machine
 *       supplies the invocation the baseline never had.</dd>
 *
 *   <dt>{@code PostTransactionsJob} — nightly chain state 4</dt>
 *   <dd>Re-expresses {@code app/cbl/CBTRN02C.cbl}, driven by
 *       {@code app/jcl/POSTTRAN.jcl:23} ({@code EXEC PGM=CBTRN02C}). The
 *       three-write posting unit of work stays a single commit, so the
 *       partial-posting states a saga would expose never become
 *       observable.</dd>
 *
 *   <dt>{@code CalculateInterestJob} — nightly chain state 5</dt>
 *   <dd>Re-expresses {@code app/cbl/CBACT04C.cbl}, driven by
 *       {@code app/jcl/INTCALC.jcl:22}
 *       ({@code EXEC PGM=CBACT04C,PARM='2022071800'}). That ten-character
 *       {@code PARM} is the origin of the business-date assumption recorded at
 *       the foot of this document.</dd>
 *
 *   <dt>{@code BackupTransactionsJob} — nightly chain state 6</dt>
 *   <dd>Re-expresses {@code app/jcl/TRANBKP.jcl}, whose copy step is
 *       {@code app/jcl/TRANBKP.jcl:23} ({@code EXEC PROC=REPROC}, a catalogued
 *       procedure rather than a program). This job is the worked example for
 *       the condition-code inversion rule below, because the baseline job
 *       carries the only soft-warn continuation gate in the whole tree.</dd>
 *
 *   <dt>{@code CombineTransactionsJob} — nightly chain state 7</dt>
 *   <dd>Re-expresses {@code app/jcl/COMBTRAN.jcl}, whose merge step is
 *       {@code app/jcl/COMBTRAN.jcl:22} ({@code EXEC PGM=SORT}). There is no
 *       COBOL program behind this one: the baseline step is a sort utility
 *       invocation, and the migrated job expresses the same ordering as an
 *       ordered query.</dd>
 *
 *   <dt>{@code ExportJob} — outside the nightly chain</dt>
 *   <dd>Re-expresses {@code app/cbl/CBEXPORT.cbl}, driven by
 *       {@code app/jcl/CBEXPORT.jcl:43} ({@code EXEC PGM=CBEXPORT}, no
 *       {@code PARM}).</dd>
 *
 *   <dt>{@code ImportJob} — outside the nightly chain</dt>
 *   <dd>Re-expresses {@code app/cbl/CBIMPORT.cbl}, driven by
 *       {@code app/jcl/CBIMPORT.jcl:22} ({@code EXEC PGM=CBIMPORT}, no
 *       {@code PARM}).</dd>
 * </dl>
 *
 * <p>The driverless observation applies to {@code CBTRN01C} alone. Export and
 * import <em>do</em> have JCL drivers, at the two lines cited above, and a
 * reader who assumes otherwise because those two jobs are absent from the
 * nightly chain has conflated <em>unscheduled</em> with <em>undriven</em>. They
 * are unscheduled: each has a driver, and neither is wired into the nightly
 * sequence.</p>
 *
 * <h2>Job names are an external contract, not an internal label</h2>
 *
 * <p>{@code BatchApplication} resolves the job to run by looking its name up in
 * the Spring Batch job registry, using the token it received as a process
 * argument. It does not import any class in this package. Each job bean's
 * registered name must therefore be byte-identical to its corresponding
 * {@code BatchJobName} token in {@code com.carddemo.batch.dto}:
 * {@code preflight-daily-transactions}, {@code post-transactions},
 * {@code calculate-interest}, {@code backup-transactions},
 * {@code combine-transactions}, {@code export} and {@code import}. A name that
 * differs by one character compiles, deploys, and then fails at run time inside
 * the state machine with an unresolved-job error rather than at build time —
 * which is exactly why the identity is stated here in prose that a reviewer
 * reads, in addition to being asserted by
 * {@code services/batch-service/src/test/java/com/carddemo/batch/job/BatchJobRosterTest.java}, which
 * compares each landed class's own {@code JOB_NAME} constant against the enumeration and refuses a
 * duplicate.</p>
 *
 * <p>Assumptions: each landed job also declares a distinct {@code STEP_NAME}, and the distinctness is
 * asserted rather than assumed. The durable step ledger keys on the run identifier paired with the STEP
 * name, so two jobs sharing a step name would make the second one to run in a given execution report as
 * already complete and skip its work silently -- an omission with no failure to notice it by.</p>
 *
 * <p>Assumptions: the container command supplies the job token
 * and the business date as process arguments. Step Functions passes them
 * through container overrides, so the argument list is the boundary at which an
 * orchestration decision becomes a Java one, and renaming a job bean silently
 * changes an orchestration contract that lives outside this repository's Java
 * sources.</p>
 *
 * <h2>Contract one: the process exit status</h2>
 *
 * <p>A job in this package communicates its outcome to the orchestrator through
 * three, and only three, exit-status tiers:</p>
 *
 * <ul>
 *   <li>{@code 0} — clean completion.</li>
 *   <li>{@code 4} — soft warn. The work completed and downstream states may
 *       still run.</li>
 *   <li>{@code >= 8} — hard failure. The state's catch handler routes to
 *       failure notification.</li>
 * </ul>
 *
 * <p>The warn tier has exactly one origin in the entire baseline.
 * {@code app/cbl/CBTRN02C.cbl:229} reads {@code IF WS-REJECT-COUNT > 0} and
 * {@code app/cbl/CBTRN02C.cbl:230} reads {@code MOVE 4 TO RETURN-CODE}. That
 * statement is the only {@code RETURN-CODE} statement in any of the twelve
 * batch programs under {@code app/cbl/CB*}, so {@code PostTransactionsJob} is
 * the only job in this package that can ever emit {@code 4}. For the other six
 * the warn tier is unreachable by construction, not merely unused:
 * {@code app/cbl/CBTRN01C.cbl}, {@code app/cbl/CBACT04C.cbl},
 * {@code app/cbl/CBEXPORT.cbl} and {@code app/cbl/CBIMPORT.cbl} contain no
 * {@code RETURN-CODE} statement at all, and each either completes cleanly or
 * abends — export and import through the bare {@code CALL 'CEE3ABD'} at
 * {@code app/cbl/CBEXPORT.cbl:579} and {@code app/cbl/CBIMPORT.cbl:484}.</p>
 *
 * <p>The {@code 0} and {@code 4} tiers are not an interpretation of the
 * baseline; they are pinned by fourteen committed golden files named
 * {@code return_code.expected} under {@code tests/golden/}. Ten expect
 * {@code 0} — interest {@code default_fallback}, {@code happy_path} and
 * {@code zero_balance}; posting {@code boundary_exact_limit},
 * {@code boundary_expiry_equal}, {@code empty_input}, {@code happy_path} and
 * {@code zero_balance}; provisioning {@code empty_input} and
 * {@code happy_path}. Four expect {@code 4}, and every one of them is a posting
 * reject scenario: {@code reject_100_card_missing},
 * {@code reject_101_acct_missing}, {@code reject_102_overlimit} and
 * {@code reject_103_expired}. That distribution is the empirical form of the
 * preceding paragraph — no scenario in any other domain expects {@code 4}.</p>
 *
 * <p>Alternatives Considered: two other homes for this
 * contract were evaluated. Copying it into all seven class-level comments was
 * rejected because seven copies of a rule drift the moment one is edited, and
 * the tier that drifts is the one nobody re-derives. Adding an eighth type to
 * this package to hold the narrative was also rejected: the numeric tiers
 * themselves are already modelled by {@code BatchReturnCode} in
 * {@code com.carddemo.batch.dto}, so a further type here would carry no value,
 * only prose — and prose belongs in the package's own documentation entry
 * point, which every reader of any class in the package already lands on.</p>
 *
 * <p>Trade-offs: stating the contract once, away from the
 * classes that implement it, accepts one real cost — a reader of a single job
 * class must follow one hop to find the rule that governs its exit status. That
 * cost was accepted in exchange for a single source of truth, and it is the
 * reason each job class points here rather than restating the tiers.</p>
 *
 * <p>Assumptions: these three tiers are deliberately narrower
 * than the graded vocabulary the baseline uses internally.
 * {@code app/cbl/CBTRN02C.cbl} declares {@code 88 APPL-AOK VALUE 0.} at line
 * 143 and {@code 88 APPL-EOF VALUE 16.} at line 144, pre-sets a paragraph to
 * failure with {@code MOVE 8 TO APPL-RESULT} — at
 * {@code app/cbl/CBTRN02C.cbl:563}, for one — records an I/O failure with
 * {@code MOVE 12 TO APPL-RESULT} at line 569 before routing to
 * {@code 9999-ABEND-PROGRAM}, and signals end of file with
 * {@code MOVE 16 TO APPL-RESULT} at line 352. Those four values are internal
 * control flow. Not one of them ever reaches a process exit status, and
 * treating {@code 12} or {@code 16} as an exit code would invent a tier the
 * baseline never published. The process exit status is also the <em>only</em>
 * legitimate numeric surface for this graded rubric: a Maven, Surefire,
 * Failsafe or JUnit gate is binary, so encoding a tier in a test outcome would
 * either lose the distinction between warn and failure or, worse, turn a
 * correctly-written business reject into a red build.</p>
 *
 * <p>Assumptions: the existing COBOL suite under
 * {@code tests/**} aggregates its own return code to {@code 4}, and for that
 * suite {@code 4} <em>is</em> the green state. Its single cause is documented
 * at {@code tests/README.md:53-69}: {@code CBEXPORT} and {@code CBIMPORT}
 * declare a record key that lives only in working storage, so they do not
 * compile under the open-source compiler, and
 * {@code scripts/build_test_programs.sh} attempts the pair, expects that
 * documented failure, and aggregates a soft warn rather than poisoning an
 * otherwise-clean run. A maintainer who reads that aggregate as a regression,
 * or who gates on {@code 0} where the rubric says {@code 4} passes, will chase
 * a defect that is not there.</p>
 *
 * <h2>Contract two: JCL condition codes invert on the way to a state
 * machine</h2>
 *
 * <p>A JCL {@code COND} is a <em>skip</em> predicate: it states the
 * circumstances in which a step must <em>not</em> run. A Step Functions
 * {@code Choice} is a <em>run</em> predicate: it states the circumstances in
 * which the next state <em>does</em> run. Translating one into the other
 * therefore inverts the sense, and an inversion that is omitted rather than
 * applied produces a chain that runs every step it should have bypassed and
 * bypasses every step it should have run — while still building, deploying and,
 * on a clean night, appearing to work.</p>
 *
 * <p>Both forms present in the baseline read as follows once inverted:</p>
 *
 * <ul>
 *   <li>{@code COND=(0,NE)} — skip when {@code 0} is not equal to the prior
 *       return code, so run only when every predecessor ended cleanly. Target:
 *       the default success edge out of the preceding state, with any non-zero
 *       code picked up by that state's catch handler rather than by a
 *       predicate.</li>
 *   <li>{@code COND=(4,LT)} — skip when {@code 4} is less than the return code,
 *       so run only when the code is {@code 4} or lower. This is the soft-warn
 *       continuation predicate, and it is the mechanism by which tier {@code 4}
 *       of contract one flows onward instead of halting the chain. It occurs
 *       exactly once in the baseline, at {@code app/jcl/TRANBKP.jcl:51}
 *       ({@code EXEC PGM=IDCAMS,COND=(4,LT)}), which is why
 *       {@code BackupTransactionsJob} is the job whose state carries the
 *       inversion worth studying.</li>
 * </ul>
 *
 * <p>One trap justifies documenting this at package level rather than at a
 * single call site: two entirely different constructs share the {@code COND}
 * keyword. {@code app/jcl/TRANREPT.jcl:47} reads
 * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,} and continues on
 * line 48 — a DFSORT record-selection predicate sitting inside the
 * {@code SYSIN} stream of {@code EXEC PGM=SORT} at
 * {@code app/jcl/TRANREPT.jcl:37}, beside
 * {@code SORT FIELDS=(TRAN-CARD-NUM,A)} at line 46. It selects
 * <em>records</em>, not steps. Its target is a SQL {@code WHERE} clause,
 * exactly as the neighbouring {@code SORT FIELDS} becomes an
 * {@code ORDER BY}, and modelling it as a {@code Choice} state would gate an
 * entire step on a condition that was only ever meant to filter rows. The two
 * forms are told apart by position, not by keyword: a step gate begins a JCL
 * statement and so carries the {@code //} prefix, whereas the record filter
 * has no such prefix because it is utility control input rather than JCL.</p>
 *
 * <p>The full inventory is recorded here so that no reader has to re-derive it.
 * Across all thirty-eight files in {@code app/jcl/} there are exactly ten
 * {@code COND=} occurrences:</p>
 *
 * <ul>
 *   <li>one soft-warn step gate — {@code app/jcl/TRANBKP.jcl:51};</li>
 *   <li>eight clean-predecessor step gates —
 *       {@code app/jcl/TXT2PDF1.JCL:26}, {@code app/jcl/CREASTMT.JCL:56},
 *       {@code :66} and {@code :79}, {@code app/jcl/DEFGDGD.jcl:36},
 *       {@code :47}, {@code :59} and {@code :82};</li>
 *   <li>one DFSORT record filter, which is not a step gate at all —
 *       {@code app/jcl/TRANREPT.jcl:47}.</li>
 * </ul>
 *
 * <p>Assumptions: only the first of those three groups belongs
 * to a job in this package. The eight clean-predecessor gates govern statement
 * generation, the reference-data generation jobs and a document-conversion
 * utility, so they are cited here as the evidence that {@code (4,LT)} is
 * genuinely singular rather than as work this package performs.</p>
 *
 * <h2>Assumptions shared by every job in this package</h2>
 *
 * <p>Assumptions: baseline provenance is reference-only. Every
 * {@code app/**} path cited anywhere in this package is cited by path and line
 * so a reader can confirm it, and nothing under {@code app/**} is modified,
 * generated from, or reached at run time. The COBOL is the behavioural oracle
 * against which these jobs are compared, which only holds while it stays
 * byte-identical.</p>
 *
 * <p>Assumptions: restart capability here is net-new, not
 * migrated. There is no baseline checkpoint contract to preserve: the only
 * {@code RESTART=} anywhere in the thirty-eight jobs is commented out —
 * {@code app/jcl/DEFGDGD.jcl:2} carries the {@code //*} comment prefix — and no
 * {@code CHKPT=} appears in any of them. Restartability comes instead from Step
 * Functions redrive resuming an execution at the state that failed, together
 * with the durable {@code batch.batch_run} step ledger that gives each step an
 * idempotency key so a resumed step which already completed is a no-op. Both
 * are a strict improvement over the baseline rather than a port of something
 * that existed, and saying so matters: a reader who assumes a checkpoint
 * contract was inherited will look for baseline semantics to match and find
 * none.</p>
 *
 * <p>Assumptions: generation-dataset retention is bounded at
 * five, and a rolled-off generation is physically deleted rather than merely
 * uncatalogued. Ten generation bases are defined with both {@code LIMIT(5)} and
 * an explicit {@code SCRATCH}: six in {@code app/jcl/DEFGDGB.jcl} at lines 25,
 * 31, 37, 43, 49 and 55; three in {@code app/jcl/DEFGDGD.jcl} at lines 28, 51
 * and 74; and one in {@code app/jcl/DALYREJS.jcl:25}. {@code SCRATCH} is what
 * makes the deletion physical, so the target analogue is retaining five
 * noncurrent object versions and then expiring them, not retaining five and
 * keeping the remainder.</p>
 *
 * <p>Alternatives Considered: that bound is not stated
 * uniformly by the baseline, so the normative form had to be chosen rather
 * than read off. {@code app/jcl/REPTFILE.jcl:26} re-defines the report base
 * under the same name with {@code LIMIT(10)} at line 27 and no
 * {@code SCRATCH} clause, which would imply ten retained generations that
 * survive roll-off. The five-generation scratch form was taken as normative
 * for two reasons: it is the form nine of the ten bases state without
 * contradiction, and {@code app/jcl/DEFGDGB.jcl} is the job whose own comment
 * at line 19 declares its purpose to be defining the bases the project needs,
 * guarding each definition with {@code IF LASTCC=12 THEN SET MAXCC=0} so that
 * re-running it is harmless — whereas the competing definition carries no such
 * guard and would fail outright on a base that already exists. Adopting
 * {@code LIMIT(10)} instead would have retained twice the data for one dataset
 * family on the strength of the definition less likely to have won the
 * race.</p>
 *
 * <p>Assumptions: money is exact fixed point at every hop —
 * {@code BigDecimal} at scale 2, never {@code float} and never {@code double}.
 * The baseline holds these amounts as zoned decimal with sign overpunch and as
 * packed decimal, both of which are exact; routing either through a binary
 * floating-point type would produce plausible amounts that are wrong by cents,
 * and wrong silently. The prohibition is enforced by an architecture test
 * rather than left to convention, so a job in this package cannot introduce a
 * {@code double} into the money path without failing the build.</p>
 *
 * <p>Assumptions: the business date arrives as a job parameter
 * and is never read from the wall clock. {@code app/jcl/INTCALC.jcl:22} injects
 * it as {@code PARM='2022071800'}, and that injection is the entire reason a
 * rerun of a nightly chain reproduces its earlier output byte for byte. A job
 * that consulted the clock would still pass a single-run test and would then
 * diverge on every rerun, which is the failure mode the golden masters exist to
 * catch.</p>
 */
package com.carddemo.batch.job;
