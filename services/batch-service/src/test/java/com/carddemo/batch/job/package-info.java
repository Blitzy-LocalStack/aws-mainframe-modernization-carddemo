/**
 * Tests over the roster, the step wiring, the argument contract and the unit of work of this
 * module's batch job definitions.
 *
 * <h2>Purpose, position, and what this tier is answerable for</h2>
 *
 * <p>Purpose: the production package of this same name,
 * {@code services/batch-service/src/main/java/com/carddemo/batch/job}, holds job definitions and
 * nothing else. Each one wires a reader, a processor and a writer, fixes the order its rules are
 * applied in, declares the arguments it accepts, and delegates every business rule to
 * {@code com.carddemo.batch.service}. This package asserts exactly that surface: which jobs exist,
 * what they are named, what order they apply their rules in, what they emit, and what numeric
 * result they hand back. It re-derives no business rule.</p>
 *
 * <p>This is the structural tier of the module's test manifest -- the tier the sibling charter at
 * {@code services/batch-service/src/test/java/com/carddemo/batch/service/package-info.java} refers
 * to when it says its sibling tiers assert structure while it settles the rules. The full roster of
 * test packages in this subtree is fixed by the test-root charter at
 * {@code services/batch-service/src/test/java/com/carddemo/batch/package-info.java}, and is
 * deliberately not re-counted here.</p>
 *
 * <p>Alternatives Considered: restating that roster, or its size, in this charter as well, so a
 * reader arriving here would see the whole manifest without opening another file. Rejected on the
 * root charter's own recorded evidence: three of its paragraphs document occasions when a count
 * derived from its roster was amended in one place and not in the other, and it states outright that
 * restating a figure away from the thing it counts is the shape every one of those lapses took. A
 * second copy of that roster here would be a fourth place for the same drift, and this charter has
 * no need of the number -- what a reader needs from this file is which subjects belong to THIS
 * package and which belong to a sibling, and both are stated below against the subjects
 * themselves.</p>
 *
 * <p>Assumptions: the package root is {@code com.carddemo.batch.job} and is not free to move. The
 * migration plan fixes one package root per bounded context in its cross-file dependency section,
 * AAP 0.5.3.1, and a case in this very directory compares the name each job registers under against
 * the vocabulary that plan publishes outward, so a rename here breaks an assertion rather than
 * merely a convention.</p>
 *
 * <p>Assumptions: everything under {@code app/} is read-only evidence. A citation in this package
 * names a reference program, copybook or job by path and line, and that citation is the entire
 * extent of the relationship -- nothing in this tree modifies, re-pins or regenerates a baseline
 * file, and nothing under {@code app/} is read at run time. Where migrated behaviour differs from
 * the reference the difference is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md} rather than absorbed into an
 * assertion. Line numbers refer to the source as committed, and columns 73 to 80 of a COBOL or JCL
 * line carry a sequence field that is not part of the statement.</p>
 *
 * <h2>The closed roster of systems under test</h2>
 *
 * <p>Seven production job types, and no eighth. No test here may invent a subject: each type below
 * was verified present in {@code services/batch-service/src/main/java/com/carddemo/batch/job}, and
 * an assertion naming anything else has nothing to drive. Each is paired with the token that selects
 * it, and the tokens were read from
 * {@code services/batch-service/src/main/java/com/carddemo/batch/dto/BatchJobName.java} rather than
 * retyped from prose.</p>
 *
 * <dl>
 *   <dt>{@code PreflightDailyTransactionsJob} -- token {@code preflight-daily-transactions}</dt>
 *   <dd>State 3 of the nightly chain. Re-expresses {@code app/cbl/CBTRN01C.cbl}, the pre-posting
 *       validation pass.</dd>
 *
 *   <dt>{@code PostTransactionsJob} -- token {@code post-transactions}</dt>
 *   <dd>State 4. Re-expresses {@code app/cbl/CBTRN02C.cbl}, driven by
 *       {@code app/jcl/POSTTRAN.jcl:23}.</dd>
 *
 *   <dt>{@code CalculateInterestJob} -- token {@code calculate-interest}</dt>
 *   <dd>State 5. Re-expresses {@code app/cbl/CBACT04C.cbl}, driven by
 *       {@code app/jcl/INTCALC.jcl:22}.</dd>
 *
 *   <dt>{@code BackupTransactionsJob} -- token {@code backup-transactions}</dt>
 *   <dd>State 6. Re-expresses {@code app/jcl/TRANBKP.jcl}, which is also the job carrying the one
 *       condition-code form discussed below.</dd>
 *
 *   <dt>{@code CombineTransactionsJob} -- token {@code combine-transactions}</dt>
 *   <dd>State 7. Re-expresses {@code app/jcl/COMBTRAN.jcl}, whose sort-merge becomes ordered
 *       SQL.</dd>
 *
 *   <dt>{@code ExportJob} -- token {@code export}</dt>
 *   <dd>Re-expresses {@code app/cbl/CBEXPORT.cbl}. Not a state of the nightly chain.</dd>
 *
 *   <dt>{@code ImportJob} -- token {@code import}</dt>
 *   <dd>Re-expresses {@code app/cbl/CBIMPORT.cbl}. Not a state of the nightly chain.</dd>
 * </dl>
 *
 * <p>Assumptions: the last two are branch-migration utilities and their absence from the chain is a
 * decision rather than an oversight. AAP 0.4.1.7 enumerates eleven states for the
 * {@code carddemo-daily-batch} machine and neither export nor import is among them; both are
 * selected on demand through the {@code --job=} argument that
 * {@code com.carddemo.batch.BatchApplication} validates. Their tokens are also the two bare words
 * in an otherwise kebab-case vocabulary, and that asymmetry is likewise deliberate -- the type
 * declaring them records why regularising it would produce a vocabulary that reads better and
 * rejects the two commands an operator actually sends.</p>
 *
 * <p>Assumptions: state numbering is a property of the orchestration definition and not of any Java
 * member. It is quoted above as provenance so a reader can find a job in the state machine, and a
 * case here must not derive it from a declaration order --
 * {@code services/batch-service/src/main/java/com/carddemo/batch/dto/BatchJobName.java} declares its
 * constants in nightly order for readability and states in the same breath that its own
 * {@code ordinal} is part of no contract and must never be persisted, transmitted, written to the
 * durable step ledger or written into an orchestration definition.</p>
 *
 * <h2>What this directory holds</h2>
 *
 * <pre>
 * this directory: 14 java files = 12 tests + 1 integration test + 1 charter
 *
 * the twelve and the one are counted apart on purpose: the runner separates them, surefire
 * taking the *Test names and failsafe the one *IT name, and the migration plan's per-service
 * row for this module is a count of the first group
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
 * <p>Assumptions: the case figure quoted for {@code ImportJobTest} is 36 DECLARED cases and a run of
 * it reports 48 executed, and the two differ legitimately because three of its methods are
 * parameterised and expand. The declared figure is the one every entry above quotes and the one
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java}
 * re-measures, counting methods annotated as a test, a parameterised test or a repeated test. The
 * distinction is recorded because substituting the executed figure would look like a correction and
 * would fail that check.</p>
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
 * <p>Six contracts belong to this package and to no sibling tier. Each is stated with the evidence
 * it rests on, because a tier that cannot say why it owns a contract is a tier whose contracts get
 * asserted twice.</p>
 *
 * <h3>The soft-warn result, and the two counter lines</h3>
 *
 * <p>Only {@code PostTransactionsJobTest} asserts the soft-warn result, because only
 * {@code PostTransactionsJob} can produce one. The reference decides it at
 * {@code app/cbl/CBTRN02C.cbl:229-230}, where {@code IF WS-REJECT-COUNT > 0} selects
 * {@code MOVE 4 TO RETURN-CODE}: a run that correctly rejected transactions has done its work and
 * must not read as a failure, while the downstream state still needs to know rejects were written.
 * Every other token reports either clean or failed, so a warn result arriving from any of them is a
 * defect rather than a business outcome.</p>
 *
 * <p>Assumptions: the two summary lines are rendered by the posting job itself and are asserted
 * here for that reason. They are declared as {@code PROCESSED_LABEL} and {@code REJECTED_LABEL} on
 * {@code PostTransactionsJob}, and their spacing is ASYMMETRIC in the reference:
 * {@code app/cbl/CBTRN02C.cbl:227} spells {@code 'TRANSACTIONS PROCESSED :'} with ONE space before
 * the colon and line 228 spells {@code 'TRANSACTIONS REJECTED  :'} with TWO, so the colons align
 * under a fixed-pitch terminal. The count that follows is nine zero-padded digits, because
 * {@code WS-REJECT-COUNT} is declared {@code PIC 9(09)} at line 186. Normalising either the spacing
 * or the padding would produce output that reads correctly to a person and fails a byte
 * comparison.</p>
 *
 * <p>Alternatives Considered: asserting those lines against the aggregate summary value instead,
 * which is where a reader looking for counters would first go. Rejected, and not by preference:
 * {@code com.carddemo.batch.dto.BatchRunSummary} deliberately renders nothing at all, and its own
 * charter records that the reference programs use several different summary formats so a single
 * renderer on that type would have to invent one more, and assigns the rendering to the posting job.
 * An assertion placed on the value type would therefore be asserting a method that does not exist,
 * and creating one to satisfy the assertion would introduce the format the value type refuses to
 * choose.</p>
 *
 * <h3>The numeric result is an exit status, never a runner tolerance</h3>
 *
 * <p>Assumptions: the numeric result is asserted in exactly two forms -- the value the application
 * returns, and the process exit status the orchestrating state machine reads. It is never expressed
 * as a tolerance configured on a test runner. {@code com.carddemo.batch.dto.BatchReturnCode} models
 * three outcomes only, clean, soft-warn and hard failure, which is the whole vocabulary available to
 * an assertion here.</p>
 *
 * <p>Trade-offs: the graded rubric that looks applicable is not, and the mismatch is worth naming
 * because borrowing it would be easy and quiet. The parity oracle suite grades a run across five
 * tiers and aggregates the worst code seen, documented in section 8 of {@code tests/README.md}; the
 * fixture contract for this module restates the same boundary in its section 7.1.6, which records
 * that the graded rubric belongs exclusively to that suite and that a return code of 4 is a fixture
 * expectation value rather than a build outcome. Maven, the two class runners and the documentation
 * gate are binary: each either passes or fails. What is given up by declining the richer vocabulary
 * is the ability to report a partially-successful build; what is bought is that a real failure
 * cannot be configured to read as an accepted warning, which is the only way the graded form could
 * be wired into a Java gate.</p>
 *
 * <h3>Condition-code inversion, and the look-alike that is not a step gate</h3>
 *
 * <p>A JCL {@code COND} is a SKIP predicate and a state machine {@code Choice} is a RUN predicate,
 * so every migrated gate inverts the sense. The production charter at
 * {@code services/batch-service/src/main/java/com/carddemo/batch/job/package-info.java} owns that
 * rule and states both baseline forms in full; this package asserts that the jobs behave as it
 * says. The single instance worth studying is {@code app/jcl/TRANBKP.jcl:51},
 * {@code //STEP10 EXEC PGM=IDCAMS,COND=(4,LT)} -- skip when 4 is less than the return code, which
 * inverts to the run predicate that the step proceeds while the code is 4 or lower. Measured: that
 * is the only {@code COND=(4,LT)} anywhere in the thirty-eight files of {@code app/jcl}, and it is
 * what carries the soft-warn tier across a step boundary.</p>
 *
 * <p>Assumptions: one baseline construct looks like a step gate and is not, and conflating the two
 * is a real hazard rather than a theoretical one, because they share a keyword.
 * {@code app/jcl/TRANREPT.jcl:47} reads
 * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,} and continues on line 48. It sits
 * inside the control statements of a sort step declared at {@code app/jcl/TRANREPT.jcl:37}, so it
 * selects RECORDS and not steps: its migrated form is a SQL predicate over the processing date, and
 * modelling it as a run predicate would gate a whole step on a condition that was only ever meant
 * to filter rows. An assertion in this package therefore never treats a record filter as a step
 * result, and never treats a step result as a filter.</p>
 *
 * <h3>The business-date token: opaque, ten characters, and two committed forms</h3>
 *
 * <p>This package owns the job parameter contract for the business date: that it is required, that
 * a job refuses to run without it, that it is never derived from a clock, and that it survives
 * intact. {@code CalculateInterestJobTest} and {@code PostTransactionsJobTest} both exercise it, the
 * latter through its refusal to run without the identifying parameter.</p>
 *
 * <p>Assumptions: the token is required for ALL SEVEN jobs and not only for the one whose reference
 * step carries a parameter, and the point is stated because the baseline evidence suggests
 * otherwise. Only {@code app/jcl/INTCALC.jcl:22} passes a date, as
 * {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}; the entry point nevertheless requires the
 * option for every token, because it also adds the date as an identifying job parameter so that one
 * night is a distinct job instance from the next. A case here that treated the option as optional
 * for six of the seven would contradict the validator it is driving.</p>
 *
 * <p>Assumptions: the token is an OPAQUE ten-character passthrough, held character for character
 * and never normalised, so both committed forms must be exercised and neither may be canonicalised.
 * The reference applies no formatting whatsoever: {@code app/cbl/CBACT04C.cbl:476-480} merely
 * {@code STRING}s a {@code PIC X(10)} parameter alongside a {@code PIC 9(06)} counter
 * {@code DELIMITED BY SIZE} into a sixteen-character identifier, and 10 plus 6 fills it exactly. Two
 * forms are committed, both exactly ten characters -- the compact {@code 2022071800} of the job
 * above, and an ISO-shaped alternative -- and section 8.2 of
 * {@code services/batch-service/src/test/resources/fixtures/README.md} records the measurement that
 * settles it: the two generated records are otherwise byte-identical and differ only in that
 * ten-character prefix, so forcing either layout provably breaks the other. A naive rendering of a
 * date object yields neither form at the required width, which is the specific error both forms
 * exist to catch.</p>
 *
 * <h3>The posting unit of work: the boundary is declared here</h3>
 *
 * <p>The posting pass commits a category-balance row, an account row and a transaction row together,
 * ONCE PER FEED RECORD. The reference performs the three writes in strict sequence at
 * {@code app/cbl/CBTRN02C.cbl:440-442} -- category balance, then account, then transaction -- from a
 * paragraph the read loop at {@code app/cbl/CBTRN02C.cbl:200-226} performs once per record, and the
 * migrated job reproduces that extent: one transaction per record, spanning that record's decisions
 * and its three writes and nothing beyond them.</p>
 *
 * <p>Assumptions: the boundary is declared by construction rather than by annotation, and an
 * assertion has to know which. {@code PostTransactionsJob} builds its tasklet with the caller's
 * transaction manager and opens one transaction per record through a {@code TransactionTemplate} over
 * it, declaring the tasklet itself {@code PROPAGATION_NOT_SUPPORTED} so the pass body holds none; it
 * carries no {@code Transactional} annotation, and a case looking for one would find nothing and could
 * conclude, wrongly, that no boundary exists. The sibling service charter fixes the matching
 * invariant from the other side: NO method in the production service package is annotated
 * {@code Transactional}, precisely so that this one file remains the boundary's only owner. A
 * {@code ServiceTest} must therefore never assert a transaction boundary, and this package is where
 * the declaration is asserted.</p>
 *
 * <p>Refactoring Rationale: the boundary was the STEP's until this revision, so one transaction
 * spanned the whole feed and this charter recorded that as the design. It was wrong in three ways. A
 * failure on the three-hundredth record discarded two hundred and ninety-nine correct postings the
 * reference would have kept, because the reference commits each record as it goes. Every row the pass
 * touched stayed locked for the length of the batch window. And the durable step ledger's promise
 * that a redrive RESUMES rather than repeats cannot be kept by a pass-wide rollback. The migrated
 * form is therefore MORE atomic than the reference within one record -- a failed account write undoes
 * that record's other two writes, where {@code app/cbl/CBTRN02C.cbl:554-560} returns normally and
 * leaves them -- and NO MORE atomic than the reference across records.</p>
 *
 * <p>Trade-offs: what this package can assert about that boundary is its EXTENT, not the durability
 * of a commit. A recording transaction manager makes the begins, commits and rollbacks countable, and
 * where in the record loop each falls is what distinguishes a per-record boundary from a pass-wide
 * one -- so the extent is asserted here. Whether a rolled-back write actually left no row behind
 * cannot be shown with repositories supplied as test doubles, and is therefore proven where it can
 * fail for the right reason, by the integration case in {@code com.carddemo.batch.repository} that
 * drives the cross-schema writes against a real database. Splitting the claim across two tiers costs
 * a reader one extra file; asserting both halves in one tier that cannot fail for the right reason
 * would cost the second check.</p>
 *
 * <h3>Golden-master parity, and the update path this tier does not have</h3>
 *
 * <p>The parity method is fixed: run the job, normalise the values that are legitimately
 * non-deterministic for that domain, compare against committed expectation output, and register any
 * intentional difference as a divergence instead of absorbing it into the assertion. Where a
 * migrated result and a committed expectation disagree, the expectation is right and the assertion
 * is wrong.</p>
 *
 * <p>Assumptions: the INPUTS come from this module's own fixture tree on the test classpath and the
 * EXPECTATIONS come from the parity oracle's committed trees, and the split is deliberate rather than
 * incidental. An input is something this module drives itself with, so it belongs where the build
 * packages it and where a corruption of it fails this module's own build; an expectation describes the
 * REFERENCE, so it belongs to the oracle and is read from there read-only. Both parity classes now
 * follow that split -- {@code PostTransactionsJobTest} for the nine posting scenarios and
 * {@code CalculateInterestJobTest} for the three interest ones.</p>
 *
 * <p>Refactoring Rationale: both classes previously read their inputs from the oracle's fixture tree
 * as well, which left this module's own committed fixture tree read by NOTHING -- documented, packaged
 * into every build, and dead. Two byte-identical trees where only one is read is exactly the
 * arrangement in which the unread one drifts, so redirecting the reads is paired with an explicit
 * drift check: {@code CalculateInterestJobTest} asserts each of its twelve driving images is
 * byte-identical to the oracle image it was derived from, with no normalisation on either side,
 * because the sign overpunch, the line ending and the trailing newline are all byte-level
 * contracts.</p>
 *
 * <p>Refactoring Rationale: no golden is ever regenerated from this package, and the asymmetry with
 * the oracle suite is deliberate rather than an omission. That suite ships a guarded update gate --
 * section 12 of {@code tests/README.md} documents an environment switch and an equivalent
 * update argument on its comparator, multiply guarded and requiring the diff to be reviewed before
 * it is committed. Nothing of the kind exists here: the Java cases in this package read expectation
 * output read-only and expose no update path of any kind, no environment variable, no update flag
 * and no write-if-missing branch. The reason is that the oracle suite's goldens describe the
 * REFERENCE, whose behaviour is fixed, so regenerating them records a corrected reading of an
 * unchanged program; a switch here would instead rewrite the expectation to match whatever the
 * migrated code currently produces, which converts the module's one independent check into a
 * restatement of its own output.</p>
 *
 * <h3>Three narrower contracts</h3>
 *
 * <p>Assumptions: the preflight pass is validate-only and writes no balance. Its reference,
 * {@code app/cbl/CBTRN01C.cbl}, resolves each daily transaction against the cross-reference, the
 * customer and the account before any balance is touched, so a case here that observed a balance
 * mutation would be observing a defect. Its reference also has NO driver anywhere in
 * {@code app/jcl}, which is recorded so that a reader who greps for one and finds nothing knows the
 * omission is known; it is migrated regardless, as state 3, because the validation pass is a real
 * step of the daily cycle.</p>
 *
 * <p>Trade-offs: the export and import round trip is its own specification, because there is no
 * external expectation to compare it against -- no export, import, backup, combine or preflight
 * golden exists anywhere under {@code tests/golden}, whose committed domains are posting, interest,
 * provisioning, reporting and statement. An export FIXTURE does exist, at
 * {@code tests/fixtures/export/happy_path}, carrying one input per record-type discriminator. The
 * assertion available is therefore that a record written and then read back is unchanged, which is
 * weaker than a byte comparison against an independent artifact and is what the evidence supports;
 * inventing a golden here would manufacture the independence it appears to provide.</p>
 *
 * <p>Assumptions: generation handling is asserted at the level of a job's own behaviour -- that a
 * run allocates the generation it will write under, stages its payload beneath it, and that removing
 * a generation already absent is not an error. The retention rule itself belongs to
 * {@code com.carddemo.batch.service.DatasetGenerationService} and is settled by the service tier, as
 * the demarcation below records; it derives from the ten generation bases the reference defines
 * across {@code app/jcl/DEFGDGB.jcl}, {@code app/jcl/DEFGDGD.jcl} and {@code app/jcl/DALYREJS.jcl},
 * every one of them carrying the same retention limit.</p>
 *
 * <h2>Demarcation against the service tier</h2>
 *
 * <p>Assumptions: every ruling about a RULE is settled by the sibling {@code service} tier, against
 * the production type that carries it, and this package consumes those rulings rather than restating
 * them. Settled there and not here: the reject-reason precedence, including that reason 100
 * short-circuits 101 while 102 and 103 are two sequential unguarded blocks so that 103 overwrites
 * 102 and a transaction failing both is reported as 103; the reject description literals and the
 * rendering of the trailer that carries them; the category-balance create and update arms as two
 * separately reachable outcomes; the accrual arithmetic, meaning the half-up rounding mode, the
 * multiply-before-divide order, the reduction applied per category row, the {@code DEFAULT}
 * disclosure-group fallback and the zero-rate gate; and the retained-generation count with its
 * per-run memoisation. A case here that re-asserted any of them would create a second declaration
 * of one contract, and a second declaration is how two declarations come to disagree.</p>
 *
 * <p>The division is cleanest where the two tiers touch the same subject from different sides, so
 * both instances are stated explicitly:</p>
 *
 * <ul>
 *   <li><b>Business date.</b> The service tier owns the string concatenation that produces the
 *       sixteen-character generated identifier. This tier owns the job parameter contract around it:
 *       required, refused when absent, never clock-derived, and accepted in both committed
 *       ten-character forms.</li>
 *   <li><b>Generations.</b> The service tier owns the retained-generation constant and the
 *       memoisation that keeps one run's numbering stable. This tier owns job-level allocation and
 *       the idempotence of a delete.</li>
 * </ul>
 *
 * <p>Alternatives Considered: drawing the line by artifact instead -- everything about dates in one
 * tier, everything about generations in the other. Rejected because it would put an assertion in
 * the tier that cannot make it. Reducing a token to a generated identifier through
 * {@code com.carddemo.batch.dto.BusinessDate} is a function of its arguments and needs no job, while
 * the requirement that a launch supply that token at all is a property of the launch and cannot be
 * observed without one; the same asymmetry holds between
 * {@code com.carddemo.batch.service.DatasetGenerationService} and the job that calls it. A division
 * by artifact reads tidier in a charter and produces assertions that either need an environment
 * their tier does not have, or pass without exercising the thing they name.</p>
 *
 * <h2>Test style, and where inputs come from</h2>
 *
 * <p>Measured at this revision, the twelve types fall into three groups. FIVE build no Spring context
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
 * {@code PostTransactionsJobParityIT}. No case contacts a queue emulator.</p>
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
 * <p>Alternatives Considered: a controller-style case here, mirroring the mock-web test shape the
 * online modules of this reactor use. Rejected on three independent grounds, any one of which
 * settles it. This module is started by an orchestrated container task through the synchronous
 * run-task integration of AAP 0.4.1.7 and takes its job selection and business date as process
 * arguments, so there is no request for such a case to send. The production charter fixes a
 * subpackage map for this context that contains no interface directory, so the case would assert
 * against a package the plan does not create. And {@code services/batch-service/pom.xml} carries web
 * and actuator solely for the health probe while declaring no interface-documentation starter and no
 * security or resource-server starter, so a mock-web case naming those annotations would not
 * compile.</p>
 *
 * <p>Assumptions: no class in this package may be named so that the integration runner collects it.
 * The two runners divide this subtree by class name alone, and the test-root charter owns the full
 * rule; what matters here is the consequence. A name ending in the two-letter integration suffix is
 * collected at the integration phase and asserted at verify, where nothing prepares the environment
 * such a case would expect, while the unit runner never sees it -- so the case is skipped or fails
 * on connection rather than on its assertions, and the build still reports success. Every class here
 * therefore ends in {@code Test}, and that holds for the one case here that does start a container:
 * {@code PreflightDailyTransactionsJobTest} requests one and is nevertheless collected by the UNIT
 * runner, because the runners divide by name and not by what a case does.</p>
 *
 * <p>Refactoring Rationale: this paragraph previously closed by saying that the container-backed cases
 * live in {@code com.carddemo.batch.repository}. That remains true of the three named with the
 * integration suffix, and it is no longer the whole picture, so the sentence is narrowed rather than
 * left to read as a prohibition it never was. The rule this paragraph states is about NAMING, and
 * naming is all it was ever about: a case belongs in the persistence package when its subject is
 * persistence, not merely because it needs a database to observe its subject. The preflight case's
 * subject is a job, and what it needs a database for is to prove that the job wrote nothing to
 * one.</p>
 *
 * <p>Assumptions: this directory is a leaf and holds no subdirectory, no ignore file and no
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
 * <p>Assumptions: that exemption is this compilation unit's alone and does not travel into the six
 * test classes beside it, where a class, a case and a private helper alike do have parameters,
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
 * {@code validate} precedes compilation, removing this file stops the build before any of the six
 * sibling test classes in this directory is compiled.</p>
 *
 * <p>Assumptions: both halves of that pair were confirmed by running them rather than by reading the
 * configuration, and the two failures differ in where they are reported. With this file absent the
 * presence check reports once for the directory, against whichever sibling is audited first --
 * observed as a {@code JavadocPackage} violation on {@code BatchJobRosterTest.java} at line 1 -- so
 * a reader should expect ONE violation naming a test class rather than six naming this one. With the
 * file present but carrying an ordinary block comment instead of Javadoc, the content check reports
 * {@code MissingJavadocPackage} against this file itself. Either way the goal fails the module.</p>
 *
 * <p>Trade-offs: one detail of that experiment is worth recording, because repeating the experiment
 * without it produces the opposite conclusion. The plugin maintains an audit cache under the
 * module's build directory, and an unchanged source file is skipped on a subsequent run. Removing
 * this file alone therefore leaves the six siblings cached and unaudited, the presence check never
 * evaluates this directory, and {@code validate} passes -- which reads as proof that the charter is
 * dispensable. The cache has to be discarded for the check to run. The caching is worth having,
 * because it is what keeps a gate bound to every local build cheap enough to leave bound there; the
 * cost is precisely this one misleading observation, which is why it is written down instead of
 * being left for the next reader to draw the wrong conclusion from.</p>
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
