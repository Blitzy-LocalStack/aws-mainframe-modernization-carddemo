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
 * this directory: 7 java files = 6 tests + 1 charter
 * </pre>
 *
 * <ul>
 *   <li>{@code JobRegistrationCensusTest} across 5 cases -- assembles a context over all seven job
 *       configurations and asserts bidirectionally that every declared token resolves to a job bean
 *       of that name and that no bean carries a name outside the vocabulary.</li>
 *   <li>{@code BatchJobRosterTest} across 5 cases -- the same agreement read from the classes
 *       themselves by reflection, plus distinctness of the registered names and of the durable
 *       ledger step names.</li>
 *   <li>{@code PostTransactionsJobTest} across 9 cases -- the posting job's step, its single
 *       transactional boundary, its reject-count result and its generation handling.</li>
 *   <li>{@code DatasetJobBodiesTest} across 14 cases and {@code GenerationStagingJobsTest} across 6
 *       -- what the dataset-writing jobs emit and the generation prefix they emit it under.</li>
 *   <li>{@code CalculateInterestJobTest} across 4 cases -- the interest job's control break and its
 *       injected business date.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the census above is a MEASUREMENT of this directory and not a plan for
 * it, and the distinction is stated because the two diverge here in a way a reader will notice. The
 * migration plan's per-service test rows imply one test type per job type, which would name a
 * {@code PreflightDailyTransactionsJobTest}, a {@code BackupTransactionsJobTest}, a
 * {@code CombineTransactionsJobTest}, an {@code ExportJobTest} and an {@code ImportJobTest}. None of
 * those exists, and none is missing coverage: the five subjects they would have covered are asserted
 * by {@code DatasetJobBodiesTest} and {@code GenerationStagingJobsTest}, which are organised by the
 * behaviour under assertion -- what a dataset job emits, and the generation it emits under -- rather
 * than by one class per job. Recording that here is what stops a reader from filing a sixth
 * per-job type against a subject already covered, and from reading the absence of five expected
 * filenames as a gap.</p>
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
 * <p>The posting pass commits a category-balance row, an account row and a transaction row together.
 * The reference performs the three writes in strict sequence at
 * {@code app/cbl/CBTRN02C.cbl:440-442} -- category balance, then account, then transaction -- and
 * the migrated job runs them inside one boundary that it, and only it, declares.</p>
 *
 * <p>Assumptions: the boundary is declared by construction rather than by annotation, and an
 * assertion has to know which. {@code PostTransactionsJob} builds its tasklet with the caller's
 * transaction manager, so the whole pass runs inside a single transaction; it carries no
 * {@code Transactional} annotation, and a case looking for one would find nothing and could
 * conclude, wrongly, that no boundary exists. The sibling service charter fixes the matching
 * invariant from the other side: NO method in the production service package is annotated
 * {@code Transactional}, precisely so that this one file remains the boundary's only owner. A
 * {@code ServiceTest} must therefore never assert a transaction boundary, and this package is where
 * the declaration is asserted.</p>
 *
 * <p>Trade-offs: what this package can assert about that boundary is its DECLARATION, not its
 * atomicity. With repositories supplied as test doubles there is no unit of work to break, so a
 * commit-and-rollback assertion here would pass whatever the real propagation was. The atomicity is
 * therefore proven where it is observable, by the integration case in
 * {@code com.carddemo.batch.repository} that drives the cross-schema writes against a real
 * database. Splitting the claim across two tiers costs a reader one extra file; asserting it in one
 * tier that cannot fail for the right reason would cost the check itself.</p>
 *
 * <h3>Golden-master parity, and the update path this tier does not have</h3>
 *
 * <p>The parity method is fixed: run the job, normalise the values that are legitimately
 * non-deterministic for that domain, compare against committed expectation output, and register any
 * intentional difference as a divergence instead of absorbing it into the assertion. Where a
 * migrated result and a committed expectation disagree, the expectation is right and the assertion
 * is wrong.</p>
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
 * separately reachable outcomes; the accrual arithmetic, meaning the truncating rounding mode, the
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
 * <p>Measured at this revision: five of the six types are plain JUnit 5 cases that construct their
 * subject directly and supply every collaborator as a test double, and the sixth,
 * {@code JobRegistrationCensusTest}, assembles a context over the seven job configurations with a
 * context runner in order to observe bean registration, which is the one thing a directly
 * constructed subject cannot show. No case starts a full application, selects a profile, requests a
 * database container or contacts a queue emulator, and no case launches a job.</p>
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
 * therefore ends in {@code Test} and the container-backed cases live in
 * {@code com.carddemo.batch.repository}, which is chartered for them.</p>
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
