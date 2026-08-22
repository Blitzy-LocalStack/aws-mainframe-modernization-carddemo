/**
 * Tests over the roster, the step wiring, the argument contract and the unit of work of this
 * module's batch job definitions.
 *
 * <h2>Purpose and test boundary</h2>
 *
 * <p>The production package of this same name,
 * {@code services/batch-service/src/main/java/com/carddemo/batch/job}, holds job definitions and
 * nothing else: each wires a reader, a processor and a writer, fixes the order its rules are applied
 * in, declares the arguments it accepts, and delegates every business rule to
 * {@code com.carddemo.batch.service}. This package asserts exactly that surface -- which jobs exist,
 * what they are named, what order they apply their rules in, what they emit, and what numeric result
 * they hand back -- and re-derives no business rule.
 *
 * <p>Assumptions: the package root {@code com.carddemo.batch.job} is fixed by AAP 0.5.3.1, and a case
 * here compares the name each job registers under against the vocabulary that plan publishes
 * outward, so a rename breaks an assertion rather than merely a convention.
 *
 * <p>Assumptions: everything under {@code app/} is read-only evidence. A citation names a reference
 * program, copybook or job by path and line, and that citation is the entire extent of the
 * relationship -- nothing here modifies or re-pins a baseline file and nothing under {@code app/} is
 * read at run time. Where migrated behaviour differs from the reference the difference is registered
 * in {@code docs/architecture/cobol-to-service-traceability.md} rather than absorbed into an
 * assertion. Line numbers refer to the source as committed, and columns 73 to 80 of a COBOL or JCL
 * line carry a sequence field that is not part of the statement.
 *
 * <h2>The closed roster of systems under test</h2>
 *
 * <p>Seven production job types, each paired with the token that selects it, the tokens read from
 * {@code com.carddemo.batch.dto.BatchJobName} rather than retyped from prose. A test here may not
 * invent an eighth subject.
 *
 * <dl>
 *   <dt>{@code PreflightDailyTransactionsJob} -- token {@code preflight-daily-transactions}</dt>
 *   <dd>State 3, re-expressing {@code app/cbl/CBTRN01C.cbl}, the pre-posting validation pass.</dd>
 *
 *   <dt>{@code PostTransactionsJob} -- token {@code post-transactions}</dt>
 *   <dd>State 4, re-expressing {@code app/cbl/CBTRN02C.cbl}, driven by
 *       {@code app/jcl/POSTTRAN.jcl:23}.</dd>
 *
 *   <dt>{@code CalculateInterestJob} -- token {@code calculate-interest}</dt>
 *   <dd>State 5, re-expressing {@code app/cbl/CBACT04C.cbl}, driven by
 *       {@code app/jcl/INTCALC.jcl:22}.</dd>
 *
 *   <dt>{@code BackupTransactionsJob} -- token {@code backup-transactions}</dt>
 *   <dd>State 6, re-expressing {@code app/jcl/TRANBKP.jcl} -- the one job whose specification is a
 *       JCL file rather than a program, and the one carrying a condition-code form other than the
 *       clean-predecessor gate.</dd>
 *
 *   <dt>{@code CombineTransactionsJob} -- token {@code combine-transactions}</dt>
 *   <dd>State 7, re-expressing {@code app/jcl/COMBTRAN.jcl}, whose sort-merge becomes ordered
 *       SQL.</dd>
 *
 *   <dt>{@code ExportJob} -- token {@code export}</dt>
 *   <dd>Re-expresses {@code app/cbl/CBEXPORT.cbl}; not a state of the nightly chain.</dd>
 *
 *   <dt>{@code ImportJob} -- token {@code import}</dt>
 *   <dd>Re-expresses {@code app/cbl/CBIMPORT.cbl}; not a state of the nightly chain.</dd>
 * </dl>
 *
 * <p>Assumptions: export and import are branch-migration utilities and their absence from the
 * nightly chain is deliberate -- AAP 0.4.1.7 enumerates eleven states and neither is among them, so
 * both are selected on demand through the {@code --job=} argument
 * {@code com.carddemo.batch.BatchApplication} validates. Their tokens are the two bare words in an
 * otherwise kebab-case vocabulary because they are the two commands an operator actually sends.
 * State numbers are provenance quoted from the orchestration definition: no case may derive one from
 * declaration order, because {@code BatchJobName} declares its constants in nightly order for
 * readability while stating that its {@code ordinal} is part of no contract.
 *
 * <h2>What this directory holds</h2>
 *
 * <pre>
 * this directory: 14 java files = 13 tests + 1 charter
 * </pre>
 *
 * <ul>
 *   <li>{@code JobRegistrationCensusTest} across 5 cases -- assembles a context over all seven job
 *       configurations and asserts bidirectionally that every declared token resolves to a job bean
 *       of that name and that no bean carries a name outside the vocabulary.</li>
 *   <li>{@code BatchJobRosterTest} across 5 cases -- the same agreement read from the classes
 *       themselves by reflection, plus distinctness of the registered names and of the durable
 *       ledger step names.</li>
 *   <li>{@code PostTransactionsJobTest} across 33 cases -- the posting job's step, the PER-RECORD
 *       transactional boundary and the reference order of the three writes inside it, the isolation of
 *       a failing record from the records committed before it, the boundary a rejected record's row is
 *       written inside, its reject-count result in both the forms the orchestrator can read, the two
 *       counter lines it renders, the inverted downstream-run predicate, the two timestamps a posted
 *       row carries, its generation handling, the walk's continuation across the commit interval, the
 *       three cases covering the durable feed watermark -- that a stored position moves the walk
 *       start, that a consuming pass advances it, and that an empty feed leaves it untouched -- and
 *       its byte-for-byte parity against all four output files of every committed expectation tree,
 *       driven both from the parity oracle's trees and from THIS MODULE's own fixture images. THREE of
 *       the 33 are parameterised one row per committed expectation tree, of which there are nine, so
 *       the class contributes 57 executed cases rather than 33.</li>
 *   <li>{@code DatasetJobBodiesTest} across 14 cases and {@code GenerationStagingJobsTest} across 6
 *       -- what the dataset-writing jobs emit and the generation prefix they emit it under.</li>
 *   <li>{@code CalculateInterestJobTest} across 27 cases -- the interest job's registered name, its
 *       business-date parameter contract, its numeric result, its control break, its corrected
 *       final-account flush, its generation allocation, its emitted output, its step record, its
 *       parity against the three committed interest expectations driven from THIS MODULE's own
 *       fixture images, and the byte-for-byte identity of those images with the reference-only
 *       derivation they were taken from. Three of the 27 are parameterised, so the class contributes
 *       31 executed cases rather than 27.</li>
 *   <li>{@code ImportJobTest} across 37 cases -- the export-to-import round trip that stands in for
 *       the golden that does not exist, the five-way record-type dispatch with its counted unknown
 *       arm, the six fixed-width outputs including the one no driver allocates, the pipe-delimited
 *       diagnostic record, the refusal of a truncated artefact with nothing published, and the four
 *       divergences the pair registers.</li>
 *   <li>{@code CombineTransactionsJobTest} across 24 cases -- the combine job's pipeline join, the
 *       byte-wise ordering its sort-utility driver declares -- asserted both as an emitted order and as
 *       the DEPLOYED collation of the key column, which are separate claims because either can fail
 *       without the other -- the column collation that DECIDES that ordering rather than the
 *       container's default deciding it, the whole staged record measured against an image composed
 *       from the copybook rather than from the layout registry, the artefact's independence of the two
 *       input generations it names but never reads, the description pad each of the two row classes
 *       carries in one stream, and the absence of a load-back into the relation it reads.</li>
 *   <li>{@code BackupTransactionsJobTest} across 27 cases -- the backup job's condition-code
 *       inversion, the tolerated-not-found semantics of its removal step, the deliberate omission of
 *       its wipe-and-re-create pair, the 350-byte form of the image it emits, the per-row producer
 *       padding that image carries, and the THREE generation families one step now stages: the full
 *       copy, the card-ordered daily subset bounded to the business date by a half-open window, and the
 *       50-byte category-balance unload. Two of the twenty-seven are parameterised, so the runner
 *       reports forty-two executions; the figure stated here is the declared-case count this module's
 *       charter inventory measures, which counts annotated members rather than executions.</li>
 *   <li>{@code BackupTransactionsJobPersistenceTest} across 4 cases -- the same job's effect on the
 *       RELATION rather than on its artefact, launched against a database container: every persisted
 *       column of every row unchanged across a run, the staged artefact describing the rows the engine
 *       holds, a redrive changing no row and emitting the same bytes, and an empty relation staging an
 *       empty artefact. It exists because a repository double cannot show that a row survived; the
 *       sibling class keeps the exhaustive interaction census, which is strict in the other
 *       direction.</li>
 *   <li>{@code ExportJobTest} across 29 cases -- the export dataset's own SHAPE: the 500-byte record,
 *       every field of the 40-byte common prefix at its declared offset, the five 460-byte payload
 *       views, the single monotonic sequence number that spans them, the per-field codec routing one
 *       record with three storage regimes demands, the two attribution literals and their configured
 *       overrides, and the statement of divergence D-1.</li>
 *   <li>{@code PreflightDailyTransactionsJobTest} across 15 cases -- the pre-posting pass's read-only
 *       guarantee, proven by snapshotting four tables across a real run; the unreachability of the
 *       soft-warn tier; its three input outcomes and their two diagnostics; divergence {@code D-7};
 *       the durable step row that makes a redriven state a no-op; the walk's continuation across the
 *       commit interval into a second page; the initially empty feed; and the absence of the feed's
 *       raw transaction identifier from every line the pass logs at any level, which is asserted with
 *       the class driven to {@code DEBUG} so the per-record trace is covered too.</li>
 *   <li>{@code PostTransactionsJobParityIT} across 3 cases, 11 executed -- the posting job's PARITY
 *       against every committed expectation tree: one parameterised case per tree comparing all four
 *       recorded artifacts and the aggregate return code after a real launch, one case asserting that
 *       the two normalised spans hold exactly what the normalisation policy claims on both sides, and
 *       one census case requiring the enumeration to match both the expectation root and this module's
 *       fixture root. It is the only type here that runs the real job through the framework's own
 *       job operator against a database container, and the only one comparing recorded bytes rather
 *       than a value the case itself stated.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the census above is a MEASUREMENT of this directory and not a plan for
 * it, and the distinction is stated because the two are converging here rather than agreeing. The
 * migration plan's per-service test rows imply one test type per job type, which would name a
 * {@code PreflightDailyTransactionsJobTest}, a {@code BackupTransactionsJobTest}, a
 * {@code CombineTransactionsJobTest}, an {@code ExportJobTest} and an {@code ImportJobTest}. ALL FIVE
 * now exist and every one is listed above, so this directory and the plan's per-service test rows
 * agree exactly; the paragraphs below record, per class, why each earns a type of its own rather than
 * being folded into a behaviour-organised sibling. {@code DatasetJobBodiesTest} and
 * {@code GenerationStagingJobsTest} remain organised by the behaviour under assertion -- what a
 * dataset job emits, and the generation it emits under -- so a reader should expect deliberate,
 * bounded overlap between them and the per-job types rather than a partition.</p>
 *
 * <p>Refactoring Rationale: this paragraph once said that none of the five existed and that all five
 * subjects were covered elsewhere, and both halves were amended together rather than only the count.
 * The preflight pass is the one of the five whose subject the dataset cases could NOT have covered:
 * what it owns is that a run writes nothing at all, which is observable only against real tables and
 * is asserted nowhere else in this module. Leaving the earlier wording in place while the classes
 * existed would have told a reader that filenames they can see in this directory are absent, which is
 * precisely the drift the census note above exists to prevent.</p>
 *
 * <p>Alternatives Considered: covering the export-to-import ROUND TRIP from the producing side as
 * well, as a second assertion inside {@code ExportJobTest}, which is the symmetrical shape the plan's
 * rows imply. Rejected because what those two jobs share is a SINGLE specification -- a record written
 * by {@code ExportJob} and read back unchanged by {@code ImportJob} -- so it is one assertion spanning
 * two runs and has to have one owner. It is owned from the consuming side because that is the
 * direction the production types declare: {@code ImportJob} carries {@code ExportJob} among its
 * dependencies and an {@code @see} to it, so the consumer already knows the producer, while a
 * producer-side round-trip assertion would have to reach forward to a type its own subject exists
 * independently of. What is given up is a per-class home for the round trip on the producing side;
 * what is bought is that the pair's one specification cannot be half-asserted in two places that
 * drift apart. This is why {@code ExportJobTest} exists but does NOT re-assert the round trip: the
 * two classes divide the pair by subject, geometry on the producing side and round trip on the
 * consuming side, rather than duplicating one claim.</p>
 *
 * <p>Assumptions: the case figure quoted for {@code ImportJobTest} is 37 DECLARED cases and a run of
 * it reports 49 executed, and the two differ legitimately because three of its methods are
 * parameterised and expand. The declared figure is the one every entry above quotes and the one
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java}
 * re-measures, counting methods annotated as a test, a parameterised test or a repeated test. The
 * distinction is recorded because substituting the executed figure would look like a correction and
 * would fail that check.
 * ⚠️ Refactoring Rationale: this paragraph said 36 declared and 48 executed while the entry above
 * quoted 37, so the charter carried both an unmeasured figure and its own contradiction. Both are now
 * the measured pair -- 34 methods annotated as a test plus 3 parameterised is 37 declared, and
 * {@code target/surefire-reports/com.carddemo.batch.job.ImportJobTest.txt} reports {@code Tests run:
 * 49}. The 36/48 pair was the worse of the two failure modes available: a reader reconciling the two
 * statements would have taken the paragraph that explains the declared-versus-executed distinction as
 * the authority over the entry that merely quotes a number, and would then have "corrected" the entry
 * DOWN to the wrong figure and failed the check this very paragraph warns about.</p>
 *
 * <p>Refactoring Rationale: this paragraph said 36 declared and 48 executed. Both were one short.
 * The entry above it has said 37 since the case was added, because the inventory check re-measures
 * that entry from the annotations and would have failed on any other figure -- and 37 is what the
 * annotations give today: 34 plain cases and 3 parameterised ones. Nothing re-measured the two
 * figures in THIS paragraph, so the addition moved the enforced one and left the unenforced pair
 * behind, and the file then asserted two different declared counts for one class. The executed
 * figure is now the one a run reports, 49, taken from this module's surefire report for the class
 * rather than computed from the declared count and the number of parameterised methods, because the
 * expansion factor is a property of each parameter source and is not derivable from the annotation
 * count. The lesson generalises past this paragraph: an unenforced restatement of an enforced figure
 * is drift waiting to happen, which is why every other figure in this charter is stated against the
 * thing it counts.</p>
 *
 * <p>Trade-offs: the combine case therefore OVERLAPS {@code DatasetJobBodiesTest} and
 * {@code GenerationStagingJobsTest} rather than replacing either, and the overlap is bounded
 * deliberately. Those two settle which calls the job makes, with which coordinates and in which order,
 * against test doubles; the combine case settles the four things a test double cannot show -- that
 * the emitted order is the byte-wise order its driver declares rather than whatever collation the
 * column carries, that both upstream producers' rows reach one artefact exactly once each, that the run
 * writes nothing back, and that each staged record equals a three-hundred-and-fifty-byte image composed
 * from {@code app/cpy/CVTRA05Y.cpy} rather than decoded through the registry the encoder itself
 * uses. What is given up is that this directory holds cases that need a database engine, which
 * are slower than every doubles-based case beside them and are the cases here that cannot run without
 * a container runtime; what is bought is that the ordering contract is checked against a real
 * comparison instead of against an arrangement the test itself chose.</p>
 *
 * <p>Assumptions: {@code BackupTransactionsJobTest} earns a class of its own, and the reason is a
 * property of its subject rather than a change of convention. Its job is the only one in this package
 * with no COBOL program behind it -- {@code app/jcl/TRANBKP.jcl:23} copies through a catalogued
 * procedure and its other two steps are utility invocations -- so that file is a SPECIFICATION rather
 * than a transcription, and it additionally carries two rulings that belong to no dataset-emission
 * grouping: the inversion of the baseline's only {@code COND=(4,LT)} at
 * {@code app/jcl/TRANBKP.jcl:51}, and the deliberate non-porting of the cluster delete-and-re-create
 * at {@code :37-46} and {@code :51-60} whose literal translation would empty the transaction table
 * nightly. Grouping either ruling under a behaviour-organised class would leave the most consequential
 * decision in this package without an obvious home.</p>
 *
 * <p>Refactoring Rationale: {@code ExportJobTest} was added rather than folded into
 * {@code DatasetJobBodiesTest} because its subject is not the behaviour that file is organised around.
 * {@code DatasetJobBodiesTest} asserts what each dataset job EMITS -- the key, the record count, the
 * discriminators present -- across five jobs at once. What was missing was the export record's own
 * GEOMETRY: each prefix field at its declared offset and width, each payload view closing at 460
 * bytes, the four-byte binary sequence field, one gapless sequence across every type, and the packed,
 * display and binary spans of one record decoded through their own codecs with negative values. Those
 * properties are specific to one record layout and are the ones a wrong {@code USAGE} assumption
 * breaks silently, since a mis-sized field still leaves the record at its declared 500 bytes; adding
 * them to a file organised by job would have made that file two files in one. It is also the one place
 * divergence D-1 is stated authoritatively rather than in passing.</p>
 *
 * <p>Assumptions: the roster cases exist because this module states what it can run in TWO
 * independent places -- the token list {@code com.carddemo.batch.BatchApplication} validates an
 * argument against, and the set of names the job beans in the production package register under --
 * and nothing but a test makes them agree. A token accepted by the argument check with no bean
 * behind it produces a container that starts, validates its arguments and then fails inside the
 * state machine, so the gap is decidable at build time and is worth deciding there.
 * {@code JobRegistrationCensusTest} decides it from an assembled context and
 * {@code BatchJobRosterTest} decides it again by reflection, which is why two cases cover what looks
 * like one claim.</p>
 *
 * <p>Refactoring Rationale: those cases previously enumerated two tokens as having no landed job,
 * {@code export} and {@code import}, and compared the vocabulary against the union of the landed set
 * and that declared gap. Both jobs are now present as
 * {@code services/batch-service/src/main/java/com/carddemo/batch/job/ExportJob.java} and
 * {@code ImportJob.java}, so all seven tokens resolve to a bean, the enumerated gap has been removed
 * and the assertion is set equality directly -- every advertised token has a landed job, and no
 * landed job sits outside the vocabulary. A declared gap that outlives the artifacts it described
 * weakens the assertion silently, because the union it is compared against grows to cover whatever
 * is missing.</p>
 *
 * <h2>The contracts this tier owns</h2>
 *
 * <ul>
 *   <li><b>The soft-warn result.</b> Only {@code PostTransactionsJob} can produce one:
 *       {@code app/cbl/CBTRN02C.cbl:229-230} selects a return code of 4 when the reject count is
 *       positive, so a run that correctly rejected transactions must not read as a failure while the
 *       downstream state still learns rejects were written. Every other token reports either clean or
 *       failed.</li>
 *   <li><b>The numeric result is an exit status.</b> It is the value the orchestrated task hands
 *       back, never a tolerance a runner applies, so a case asserts the code the job produced and not
 *       the outcome a launcher chose to report.</li>
 *   <li><b>Condition-code inversion.</b> A JCL {@code COND} is a SKIP predicate and a state-machine
 *       choice is a RUN predicate, so every gate is translated with its sense inverted;
 *       {@code app/jcl/TRANBKP.jcl:51} is the baseline's only {@code COND=(4,LT)} and is therefore the
 *       one place the reference demonstrates the inverted sense of a threshold comparison.
 *       Assumptions: that clause supplies the FORM and never a data path, and the distinction decides
 *       where a reader looks for the other end of the contract. A job-control condition is evaluated
 *       against earlier steps of its OWN job, so it gates {@code TRANBKP}'s {@code STEP10} and cannot
 *       observe posting, which runs as {@code app/jcl/POSTTRAN.jcl}'s single {@code STEP15} under no
 *       condition parameter. The soft-warn continuation is consequently a TARGET contract:
 *       {@code app/cbl/CBTRN02C.cbl:229-230} produces the tier and the {@code CheckPostingExitCode}
 *       choice state in {@code infra/modules/step-functions-batch/main.tf} admits it -- the same file
     *       recording at {@code L121}-{@code L133} that the {@code TRANBKP} gate itself becomes NO
     *       state, retiring with the delete-and-redefine mechanism it protected -- so
 *       {@code PostTransactionsJobTest} asserts the target predicate rather than a cross-job baseline
 *       dependency the reference does not have. Assumptions: an {@code INCLUDE COND=} inside a sort
 *       step is a RECORD-selection predicate and becomes a {@code WHERE} clause, never a choice state;
 *       the two forms share a keyword and conflating them is the hazard this contract exists to
 *       name.</li>
 *   <li><b>The business-date token.</b> Required, refused when absent, opaque, ten characters, never
 *       clock-derived, and accepted in both committed forms -- which is what makes a rerun
 *       reproducible.</li>
 *   <li><b>The posting unit of work.</b> A category-balance row, an account row and a transaction row
 *       commit together, so the boundary is asserted here rather than inferred from the service tier's
 *       rules.</li>
 *   <li><b>Golden-master parity.</b> Run the job, normalise only the values that are legitimately
 *       non-deterministic, and compare recorded bytes. Assumptions: this tier has no golden-update
 *       path -- an expectation tree is edited deliberately and reviewed, because a test that can
 *       rewrite its own expectation cannot fail for the reason it exists.</li>
 * </ul>
 *
 * <h2>Demarcation against the service tier</h2>
 *
 * <p>Assumptions: every ruling about a RULE is settled by the sibling {@code service} tier against
 * the production type that carries it, and this package consumes those rulings rather than restating
 * them. Settled there: reject-reason precedence and the reject description literals; the
 * category-balance create and update arms; the accrual arithmetic, meaning the rounding mode, the
 * multiply-before-divide order, the {@code DEFAULT} disclosure-group fallback and the zero-rate gate;
 * and the retained-generation count with its per-run memoisation. A case here that re-asserted any of
 * them would be a second declaration of one contract, which is how two declarations come to disagree.
 *
 * <p>Where both tiers touch one subject the division is by what each can observe. The service tier
 * owns the reduction of a business-date token to a generated identifier, a function of its arguments
 * needing no job; this tier owns the requirement that a launch supply the token at all, a property of
 * the launch that cannot be observed without one. The same asymmetry divides the generation constant
 * from job-level allocation and the idempotence of a delete. Alternatives Considered: dividing by
 * artifact instead -- every date concern in one tier, every generation concern in the other --
 * rejected because it reads tidier in a charter and produces assertions that either need an
 * environment their tier does not have or pass without exercising the thing they name.
 *
 * <h2>Shared fixture and naming rules</h2>
 *
 * <p>Assumptions: no class in this package may be named so that the integration runner collects it.
 * The two runners divide this subtree by class name alone, and a name ending in the integration
 * suffix is collected at verify, where nothing prepares the environment such a case expects -- so it
 * would skip or fail on connection while the build still reported success. Every class here therefore
 * ends in {@code Test}, including {@code PreflightDailyTransactionsJobTest}, which requests a database
 * container and is nevertheless collected by the unit runner, because the runners divide by name and
 * not by what a case does. The one class carrying the integration suffix is the parity case, whose
 * subject is recorded bytes compared after a real launch.
 *
 * <h2>Test style, and where inputs come from</h2>
 *
 * <p>Measured at this revision, the thirteen types fall into three groups. FIVE build no Spring context
 * at all -- {@code BatchJobRosterTest}, {@code CalculateInterestJobTest}, {@code DatasetJobBodiesTest},
 * {@code GenerationStagingJobsTest} and {@code PostTransactionsJobTest} -- constructing their subject
 * directly and supplying their collaborators themselves rather than through a container. Most of
 * those collaborators are test doubles; the exception is deliberate and is
 * {@code PostTransactionsJobTest}'s fixture-driven parity case, which wires the REAL
 * {@code PostingValidationService} and {@code CategoryBalanceService} over map-backed repositories,
 * because a mocked decision would make a parity comparison assert its own staging rather than the
 * pass. FOUR assemble a narrow context with a
 * context runner in order to observe bean registration, which is the one thing a directly constructed
 * subject cannot show: {@code JobRegistrationCensusTest}, {@code BackupTransactionsJobTest},
 * {@code ExportJobTest} and {@code ImportJobTest}. FOUR select the test profile and start a context
 * against a database container -- {@code CombineTransactionsJobTest},
 * {@code PreflightDailyTransactionsJobTest}, {@code BackupTransactionsJobPersistenceTest} and
 * {@code PostTransactionsJobParityIT}. No case contacts a queue emulator.
 * ⚠️ Refactoring Rationale: this opened on "the twelve types" while its own three groups name
 * thirteen, {@code PostTransactionsJobParityIT} among them, and the paragraph immediately below
 * already divides THIRTEEN. Thirteen is the figure this section needs, because its subject is what
 * shape a case takes -- and the integration test's shape is exactly the one a reader most needs
 * accounted for. The twelve of the marker above is a different measurement and stays twelve: it
 * counts the {@code *Test} names surefire runs, apart from the one {@code *IT} name failsafe runs,
 * because the migration plan's per-service row counts the first group. Both figures are correct for
 * what they measure, which is why the fix is to name the right one here rather than to make the two
 * agree.</p>
 *
 * <p>ELEVEN of the thirteen RUN their job; only {@code BatchJobRosterTest} and
 * {@code JobRegistrationCensusTest} do not, because their subject is registration read by reflection
 * and by context assembly rather than execution. Nine of the eleven run it over the framework's
 * resourceless in-memory job repository, which is what puts the step lifecycle, the attached parameter
 * validator and the exit-status propagation from step to job under assertion without needing an
 * application -- and that includes two of the container-backed cases, because a real relation and a
 * real framework job repository are independent choices. The remaining two launch through a job
 * operator against the container database, for two different reasons:
 * {@code PreflightDailyTransactionsJobTest} launches through the framework's job-operator test support
 * because its subject is what the run did NOT write, and {@code PostTransactionsJobParityIT} launches
 * through the job operator itself over the framework's JDBC job repository -- created there by this
 * module's own migration -- because its subject is the bytes a real run produced.</p>
 *
 * <p>Refactoring Rationale: this paragraph once stated that no case started a full application,
 * selected a profile, requested a database container or launched a job, and every one of those four
 * claims has since become false. They are corrected against a re-measurement of the directory rather
 * than softened, because a reader uses this paragraph to decide what shape a new case may take, and a
 * charter's false claim costs more than a missing one: a reader deciding how to assert a job's result
 * would take the old sentence as ruling a launch out, leaving the private body as the only apparent
 * way in. The grouping is stated as three explicit rosters rather than as a count with one named
 * exception, because a count with an exception is what drifted -- it stayed readable while becoming
 * wrong, and naming every member makes the next divergence visible instead of plausible.</p>
 *
 * <p>Refactoring Rationale: the grouped-rosters paragraph that opens this section opened on "the
 * twelve types" while its three rosters named thirteen members, and the paragraph directly beneath it
 * already said "ELEVEN of the thirteen", so the section contradicted itself in three lines. The
 * opening figure is corrected to thirteen, which is what the directory holds: the marker above counts
 * 12 tests and 1 integration test apart because two different runners own them, and every one of the
 * thirteen appears in exactly one roster below -- the integration test in the third. The lower figure
 * was the surefire-only half of that split used where the whole directory was meant, so the sentence
 * was internally contradicted by its own membership lists and by its own successor sentence. Stating
 * thirteen here does not disturb the marker, because the marker states the split rather than the
 * total and is re-measured from the directory by the inventory check.</p>
 *
 * <p>Assumptions: the four container-backed cases are the ONLY four here that need a database
 * engine, and each is an exception on a stated ground rather than by preference. What the combine case
 * settles is the order the engine returns a sixteen-byte character column in, which the reference's sort
 * utility declares as a byte-wise comparison at {@code app/jcl/COMBTRAN.jcl:28}; a repository test
 * double would return rows in whatever order the case itself arranged, so the assertion would restate
 * its own stub and hold under every collation. What the preflight case settles is that a run leaves
 * every table unchanged, and with repositories replaced by doubles there are no tables, so that
 * assertion would pass whatever the pass wrote. What the backup persistence case settles is the same
 * kind of claim for the one job whose reference driver DELETES and re-creates the cluster it copies:
 * the omission of {@code app/jcl/TRANBKP.jcl:37-46} and {@code :51-60} is observable only as rows that
 * are still there afterwards, and a double answers whatever it was told to answer. What the parity case
 * settles is that a real posting run reproduces four recorded datasets byte for byte, and every one of
 * those bytes comes from a row an engine stored: with doubles the accumulated balance, the
 * created-versus-updated category row and the posted row would all be values the case itself handed
 * back, so the comparison would be the harness agreeing with itself. A case in this directory needing a
 * container for any OTHER reason is not covered by these four exceptions -- a case whose subject is a
 * repository CONTRACT belongs to {@code com.carddemo.batch.repository}, which is chartered for them, and
 * the ground admitted here is a JOB's effect on a relation, not the relation's own mapping.</p>
 *
 * <p>Assumptions: launching a job is available rather than forbidden, and the distinction matters
 * for whoever writes the next case. The module's test profile at
 * {@code services/batch-service/src/test/resources/application-test.yml} pins job auto-launch off
 * so that a bare context refresh cannot post transactions or accrue interest against whatever
 * database it resolved, and it records that this does NOT disable deliberate launching -- the
 * launcher remains available, which is also how the deployed task runs. A case that needs a real
 * chunk boundary therefore launches deliberately, with the profile selected, and seeds past the
 * commit-interval constant; the framework's own batch test support is on this module's classpath for
 * exactly that purpose.</p>
 *
 * <p>Assumptions: two of that profile's registered omissions bear directly on a case written here,
 * and both are numbered entries in its own register of deliberate omissions. Its entry 7 sets no
 * {@code carddemo.messaging} property of any kind, which leaves the conditional bean roster of
 * {@code com.carddemo.batch.config.SqsConfig} out of every context this package builds, so a context
 * here starts without a terminal error sink and that is a supported state rather than a gap. Its
 * entry 5 defines no default job name and no default business date, because both arrive only as the
 * two command-line arguments {@code com.carddemo.batch.BatchApplication} defines, supplied in
 * deployment through container overrides. A default for either would let a case pass while reading a
 * value the deployed task never supplies.</p>
 *
 * <p>Assumptions: byte-level input geometry comes from one document and is never restated in a
 * case. {@code services/batch-service/src/test/resources/fixtures/README.md} is the master
 * byte-encoding contract for this module -- record widths, field offsets, sign overpunch, padding
 * bytes, line endings and the determinism rules -- and a case that needs geometry cites it BY
 * SECTION rather than copying a table out of it. Its section 4.1 fixes the domains that tree admits:
 * posting, interest, preflight and export, and no others.</p>
 *
 * <p>Trade-offs: {@code BackupTransactionsJob} and {@code CombineTransactionsJob} consequently build
 * their inputs in code, because section 4.1 of
 * {@code services/batch-service/src/test/resources/fixtures/README.md} admits four domains and
 * neither {@code backup} nor {@code combine} is among them. That is the same posture
 * {@code com.carddemo.batch.service.DatasetGenerationServiceTest} already takes. The cost is that an
 * input for those two jobs is expressed as constructor calls rather than as a committed byte image a
 * reader can inspect directly; the benefit is that the fixture tree contains no domain without a job
 * to drive it, which is what section 4.2 of that same document declines for {@code statement} and
 * {@code provisioning} on the ground that it would put one record layout under two owners.</p>
 *
 * <p>Assumptions: the preflight and prepost naming correspondence is real and neither name is
 * wrong. This module's fixture domain is {@code preflight}, matching the job name a reader finds in
 * the sources and in the state machine, while the oracle suite calls the same behaviour
 * {@code prepost} at {@code tests/fixtures/prepost}. Section 4.2 of the fixture contract records the
 * correspondence so that nobody corrects one tree to the other. The two names denote the same
 * reference program, and the oracle suite is read-only in any case.</p>
 *
 * <h2>Boundaries this package does not cross</h2>
 *
 * <p>Alternatives Considered: a controller-style mock-web case, mirroring the shape the online
 * modules use. Rejected on three independent grounds, any one of which settles it: this module is
 * started by an orchestrated container task and takes its job selection and business date as process
 * arguments, so there is no request to send; the production charter fixes a subpackage map with no
 * interface directory, so the case would assert against a package the plan does not create; and
 * {@code services/batch-service/pom.xml} carries web and actuator solely for the health probe, so a
 * case naming interface-documentation or resource-server annotations would not compile.
 *
 * <p>Assumptions: this directory is a leaf holding no subdirectory, no ignore file and no
 * architecture-rule configuration. The layering invariants are owned by the pinned gate under
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture}, which this module
 * executes over its own compiled classes; a local copy or a local configuration would be a second
 * place a boundary could be relaxed while the first still read as intact. Ignore rules for build
 * output are carried by the single repository-root file, which a second one here would shadow for
 * this subtree alone.</p>
 *
 * <h2>Parameters, return values and exceptions: declared inapplicable</h2>
 *
 * <p>A package declaration accepts no parameter, yields no value and raises nothing, so this
 * charter carries no parameter, return or exception at-clause, and no authorship, availability or
 * revision at-clause either.</p>
 *
 * <p>Assumptions: the inapplicability is stated rather than left silent because the user-specified
 * Explainability rule lists a docstring that omits parameters, return values or purpose among its
 * forbidden patterns, and a reader has to be able to tell a declared inapplicability from an
 * oversight. Fabricating the at-clauses instead would be worse than useless: Javadoc has no
 * parameter, return or exception concept for a package, and the {@code NonEmptyAtclauseDescription}
 * module configured in {@code config/checkstyle/checkstyle.xml} audits at-clause bodies for
 * emptiness, so an invented clause would either be discarded by the tool or reported by it.</p>
 *
 * <p>Assumptions: that exemption is this compilation unit's alone and does not travel into the
 * thirteen test classes beside it, where a class, a case and a private helper alike do have parameters,
 * return values and thrown types to document. The obligation there rests on the rule's
 * docstring-elements clause, on the house convention section 12 of {@code tests/README.md} states
 * for every new test, fixture builder, helper, mock and runner routine, and on the
 * {@code MissingJavadocMethod} and {@code JavadocMethod} modules in
 * {@code config/checkstyle/checkstyle.xml}, which enforce presence and at-clause coverage down to
 * private visibility.</p>
 *
 * <h2>Why this charter exists, and why deleting it fails the build</h2>
 *
 * <p>Assumptions: this file is rule-mandated rather than migration-mandated, and saying so is the
 * honest answer to why an apparently content-free compilation unit sits in a test directory. The
 * migration plan's test requirements alone would never have produced it; the user-specified
 * Explainability rule requires a docstring on every module entry point, and in Java the entry point
 * of a package is its package declaration, which only a {@code package-info.java} can carry. AAP
 * 0.2.1.6 lists the artifact classes the rule forces into scope for exactly this reason.</p>
 *
 * <p>Assumptions: the file is load-bearing rather than decorative, so it must not be tidied away as
 * clutter. Two Checkstyle modules enforce it independently and neither is redundant:
 * {@code JavadocPackage}, configured at Checker level in
 * {@code config/checkstyle/checkstyle.xml}, inspects the file set and requires this file to EXIST in
 * any directory holding an audited source file, while {@code MissingJavadocPackage}, inside the tree
 * walker, requires it to CARRY Javadoc. A charter reduced to a bare package statement satisfies the
 * first and fails the second. The gate runs at Maven's {@code validate} phase under the execution
 * that {@code services/pom.xml} declares, with violations failing the build at warning severity and
 * test sources explicitly included, and {@code config/checkstyle/suppressions.xml} suppresses only
 * generated sources and the fixture resource tree -- not {@code src/test/java}. Because
 * {@code validate} precedes compilation, removing this file stops the build before any of the
 * thirteen sibling test classes in this directory is compiled.</p>
 *
 * <p>Assumptions: both halves of that pair were confirmed by running them rather than by reading the
 * configuration, and the two failures differ in where they are reported. With this file absent the
 * presence check reports once for the directory, against whichever sibling is audited first --
 * observed as a {@code JavadocPackage} violation on {@code BatchJobRosterTest.java} at line 1 -- so
 * a reader should expect ONE violation naming a test class rather than thirteen naming this one. With the
 * file present but carrying an ordinary block comment instead of Javadoc, the content check reports
 * {@code MissingJavadocPackage} against this file itself. Either way the goal fails the module.</p>
 *
 * <p>Trade-offs: one detail of that experiment is worth recording, because repeating the experiment
 * without it produces the opposite conclusion. The plugin maintains an audit cache under the
 * module's build directory, and an unchanged source file is skipped on a subsequent run. Removing
 * this file alone therefore leaves the thirteen siblings cached and unaudited, the presence check never
 * evaluates this directory, and {@code validate} passes -- which reads as proof that the charter is
 * dispensable. The cache has to be discarded for the check to run. The caching is worth having,
 * because it is what keeps a gate bound to every local build cheap enough to leave bound there; the
 * cost is precisely this one misleading observation, which is why it is written down instead of
 * being left for the next reader to draw the wrong conclusion from.</p>
 *
 * <p>Refactoring Rationale: the three paragraphs above, and the at-clause exemption paragraph before
 * them, each said SIX sibling test classes. Six was never this directory's population at the time:
 * the four sentences were authored in the same change that raised the directory from six test
 * classes to eleven, so the figure recorded the state the author measured BEFORE adding their own
 * five and was already understated when it was written; two more classes have arrived since. All
 * four now say thirteen, re-measured from the directory rather than from each other. Nothing about
 * the experiment's conclusion changes -- the presence check still reports once per directory and the
 * cache still has to be discarded before it runs -- which is exactly why the figure could be wrong
 * without any observation contradicting it, and why it is corrected here rather than dropped: the
 * sentences use the count to tell a reader how many violations to EXPECT, so a wrong count sends
 * someone looking for the wrong evidence. Trade-offs: a sibling count taken once and restated four
 * times is the shape that failed, and the honest alternative -- deleting the count and saying "each
 * sibling" -- was rejected only because two of the four sentences predict how MANY reports a reader
 * will see, which is the whole value of writing the experiment down.</p>
 *
 * <p>Assumptions: this compilation unit holds one statement, so the rationale the rule's
 * inline-comment half asks for has no adjacent executable line to sit beside and is carried inside
 * this block under the four canonical labels, which is the only placement a package makes available.
 * The written convention every block here follows is {@code docs/CODE_DOCUMENTATION_STANDARD.md},
 * cited by path and never restated.</p>
 *
 * <p>Assumptions: the rule grants test code no exemption, and there is no conflict between it and
 * house convention to resolve. Section 12 of {@code tests/README.md} already imposes the identical
 * duty on every new test, fixture builder, helper, mock and runner routine in the oracle suite, and
 * calls it a hard review gate. The two agree; the only difference is that the labels in the new
 * trees are written in the plural form the rule itself uses, which the documentation standard fixes
 * as their one permitted written form.</p>
 */
package com.carddemo.batch.job;
