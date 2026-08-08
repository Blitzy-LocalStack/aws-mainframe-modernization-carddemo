/**
 * Unit tests over the transcribed business rules of the batch bounded context, and the tier that
 * settles the parity rulings the sibling test tiers consume rather than restate.
 *
 * <h2>Purpose, and what this tier settles on behalf of the others</h2>
 *
 * <p>This is the foundational tier of this module's test manifest. The sibling tiers assert
 * structure: a {@code job} tier covers step ordering and reader, processor and writer wiring, and a
 * {@code repository} tier covers migrated row access against a real database. Neither re-derives a
 * business rule. Every ruling those tiers depend on is settled here, once, against the production
 * type that carries it, and the rulings section below states each one in the form a consuming tier
 * can rely on without returning to the baseline to work it out again.</p>
 *
 * <p>Assumptions: the split works only because each rule in the production package of this same name
 * is a function of its arguments. Every collaborator there arrives through constructor injection and
 * no rule reads mutable static state, so a rule is reachable with mocked collaborators alone and
 * needs no database, no queue and no running step. Were that not so, these tests would need the environment
 * the repository tier needs and the tiering would collapse into a single slow tier.</p>
 *
 * <p>Alternatives Considered: letting each consuming tier derive the rulings it needs directly from
 * the baseline programs. Rejected, because two of the rulings below are counter-intuitive in a
 * specific way -- the reject precedence is MIXED rather than uniform, and both numeric boundaries
 * are inclusive on the passing side -- so an independent re-derivation stands a real chance of
 * inverting one of them. A tier that inverts a ruling still agrees with itself, and the
 * disagreement then surfaces only against committed expectation output, which is the most expensive
 * place to discover it. Settling each ruling once, here, costs a longer charter and removes that
 * failure mode.</p>
 *
 * <h2>The systems under test, and the closed roster</h2>
 *
 * <p>Four pairings, and no fifth. No test here may invent a system under test: each of the four
 * production types below was verified present in
 * {@code services/batch-service/src/main/java/com/carddemo/batch/service}, and an assertion in this
 * package that names anything else has no subject to drive.</p>
 *
 * <dl>
 *   <dt>{@code PostingValidationServiceTest} covers {@code PostingValidationService}</dt>
 *   <dd>The posting reject chain: which conditions a daily transaction fails, and which of those
 *       failures is the one reported.</dd>
 *
 *   <dt>{@code CategoryBalanceServiceTest} covers {@code CategoryBalanceService}</dt>
 *   <dd>The transaction-category-balance maintenance, and specifically the two arms as two
 *       separately reachable outcomes rather than one merged effect.</dd>
 *
 *   <dt>{@code InterestCalculationServiceTest} covers {@code InterestCalculationService}</dt>
 *   <dd>Rate resolution including the {@code DEFAULT} fallback, and the monthly accrual
 *       reduction.</dd>
 *
 *   <dt>{@code DatasetGenerationServiceTest} covers {@code DatasetGenerationService}</dt>
 *   <dd>Generation numbering and the retention rule that stands in for the baseline's generation
 *       data groups.</dd>
 * </dl>
 *
 * <p>Assumptions: {@code BatchStepLedger} is a fifth type in the production package and is
 * deliberately NOT a fifth entry above, because the roster is a roster of transcribed paragraphs and
 * that type transcribes none -- the production charter names it separately for exactly that reason.
 * Its redrive behaviour is nonetheless asserted in this package, since it is a no-container rule
 * over its own arguments like the other four, so a reader counting types in the directory and
 * pairings in this list will find five and four and should not read the difference as an
 * omission.</p>
 *
 * <p>Refactoring Rationale: the pairings above are the target shape and are NOT a description of the
 * current directory, and stating them as though they were is the single most misleading thing this
 * charter could do -- the production charter records the same hazard against its own inventory.
 * Measured at this revision the directory holds <b>three</b> test types, not two and not four:
 *
 * <ul>
 *   <li>{@code PostingValidationServiceTest} -- landed against the first pairing, 9 cases.</li>
 *   <li>{@code DatasetGenerationServiceTest} -- landed against the fourth pairing, 15 cases.</li>
 *   <li>{@code BatchServicesTest} -- 18 cases across four nested groupings: the category-balance
 *       arms and the interest accrual, which are the two roster subjects still unsplit; the
 *       generation discipline; and the durable step ledger.</li>
 * </ul>
 *
 * <p>So every subject in the roster is under assertion and no ruling below is unasserted. What has
 * not happened is the split of the aggregate into the two remaining separately named types,
 * {@code CategoryBalanceServiceTest} and {@code InterestCalculationServiceTest}. A reader consulting
 * this list to find where the accrual is asserted would otherwise search for a file that is not
 * there.</p>
 *
 * <p>Assumptions: the generation subject is consequently asserted in TWO places -- its own landed
 * type and the aggregate's third grouping -- and that overlap is recorded rather than removed. The
 * two are not redundant in substance: the named type drives {@code DatasetGenerationService} directly
 * with mocked collaborators, while the grouping holds the retention rule as one of the aggregate's
 * no-container rulings. Deleting either would be a filing change that removes landed, passing
 * assertions, which is the same trade this charter already declines below.</p>
 *
 * <p>Refactoring Rationale: this paragraph said the directory held two types and named
 * {@code BatchServicesTest} as covering three subjects, and it had already instructed its own
 * amendment -- "the claim to re-measure is the count ... correct only until a type is added or
 * split". A type was added and the count was not re-measured, so the charter under-reported its own
 * coverage: a reader looking for the generation assertions was sent to a nested grouping inside the
 * aggregate and not to the named type that had landed for exactly that subject. The counts are now
 * stated per type so a future divergence is arithmetic rather than impression.</p>
 *
 * <p>Trade-offs: the aggregate is left as it stands rather than split to match the roster. What is
 * given up is the property that a subject's assertions are locatable from its name alone, which is
 * the whole point of the naming convention. What is bought is that no landed, passing assertion is
 * moved by a change whose only motive is filing -- and a nested grouping per subject already keeps
 * the subjects separately reportable, so the coverage boundary the roster describes survives even
 * while the file boundary does not. The claim to re-measure is the count above: it is correct only
 * until a type is added or split, at which point it is the paragraph to amend.</p>
 *
 * <h2>The rulings this package settles</h2>
 *
 * <p>Assumptions: every citation below is to the reference baseline under {@code app/}, which is
 * read as evidence and is never modified, re-pinned or regenerated by anything in this tree. That
 * immutability is precisely what qualifies it to serve as the oracle these rulings are measured
 * against. Line numbers refer to the source as committed, and columns 73 to 80 of a COBOL or JCL
 * line carry a sequence field that is not part of the statement.</p>
 *
 * <h3>Reject-reason precedence is mixed, not uniform</h3>
 *
 * <p>The chain does not resolve by one rule throughout, and the mixture is the contract rather than
 * an accident of transcription. The first two reasons short-circuit: {@code app/cbl/CBTRN02C.cbl}
 * guards the account lookup on the reason still being zero at lines 372 to 373, so reason 100
 * excludes 101, and the account read's {@code NOT INVALID KEY} branch at line 400 encloses both
 * later tests, so reason 101 excludes both 102 and 103. The last two do NOT short-circuit: the
 * balance test at line 407 and the expiration test at line 414 are two sequential and unguarded
 * blocks, with line 413 closing the first and line 414 opening the second and no test of the reason
 * between them. A transaction failing BOTH therefore has the second assignment at line 417
 * overwrite the first at line 410 and is reported as 103.</p>
 *
 * <p>Assumptions: first-reason-wins over reasons 100 and 101, last-writer-wins over reasons 102 and
 * 103, and 103 consequently beats 102. Reading the chain as uniformly first-reason-wins is what a
 * naive transcription produces, and it yields 102 for a transaction that is both over its limit and
 * late, where the baseline yields 103. The combination is ordinary rather than contrived, so this is
 * the ruling most worth having settled in one place.</p>
 *
 * <h3>The five reject sites, and how the code reaches the wire</h3>
 *
 * <p>Five assignment sites exist in {@code app/cbl/CBTRN02C.cbl}, each pairing a numeric reason with
 * a description literal carried across character for character:</p>
 *
 * <ul>
 *   <li>Line 385 assigns 100 with {@code INVALID CARD NUMBER FOUND} at line 386.</li>
 *   <li>Line 397 assigns 101 with {@code ACCOUNT RECORD NOT FOUND} at line 398.</li>
 *   <li>Line 410 assigns 102 with {@code OVERLIMIT TRANSACTION} at line 411.</li>
 *   <li>Line 417 assigns 103 with {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION} at line
 *       418.</li>
 *   <li>Line 556 assigns 109 with {@code ACCOUNT RECORD NOT FOUND} at line 557, on failure of the
 *       account rewrite in {@code 2800-UPDATE-ACCOUNT-REC} rather than on validation.</li>
 * </ul>
 *
 * <p>Assumptions: reason 109 shares its description literal with reason 101 while carrying a
 * different code, so an assertion that matches on description text alone cannot tell the two apart.
 * The two are also reached from unrelated places -- 101 from a failed read during validation, 109
 * from a failed rewrite after a posting decision has already been taken -- so an assertion here
 * matches on the code and treats the shared text as a property of the pair rather than as an
 * identifier of either.</p>
 *
 * <p>Assumptions: the code reaches the reject stream as FOUR characters with leading zeros, not as a
 * minimal-width integer. The field is numeric-display, declared
 * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:181}, and its
 * description neighbour is {@code PIC X(76)} at line 182. Those two widths are a positional contract
 * of the reject stream and not presentation, so an assertion over a rendered reject compares the
 * padded form. The rendering itself is owned by a type in the {@code dto} package and is asserted
 * there; what this package settles is which code and which text a given failure produces.</p>
 *
 * <h3>Both numeric boundaries are inclusive, stated in both framings</h3>
 *
 * <p>The over-limit test at {@code app/cbl/CBTRN02C.cbl:407} reads
 * {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} and takes {@code CONTINUE} when true, so the
 * comparison written in the baseline is the PASS guard and it is inclusive. Restated as the
 * predicate an assertion is usually phrased around, the REJECT condition is therefore strictly
 * greater: a balance landing exactly on the credit limit posts, and one cent beyond it rejects with
 * reason 102. The balance being tested is itself computed at lines 403 to 405 as the cycle credit
 * less the cycle debit plus the transaction amount, so the boundary is a boundary of that
 * computed value rather than of the stored balance.</p>
 *
 * <p>The expiration test at line 414 reads
 * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)} and likewise takes {@code CONTINUE} when
 * true, so a transaction dated EQUAL to the account expiration date POSTS, and only one day past it
 * rejects with reason 103. The reference sub-string takes the first ten characters of the
 * originating timestamp, which is the calendar date portion.</p>
 *
 * <p>Alternatives Considered: stating each boundary once, in whichever direction reads more
 * naturally. Rejected, because the two directions are where an inclusive boundary is lost: the
 * baseline writes the passing comparison and an assertion is usually written around the failing one,
 * so a single-framing statement leaves the reader to invert it, and inverting an inclusive
 * comparison is exactly the step at which it silently becomes exclusive. Both framings are given for
 * both boundaries so no inversion is left to a reader.</p>
 *
 * <h3>The accrual rulings</h3>
 *
 * <p>Five rulings come from {@code app/cbl/CBACT04C.cbl} and every one of them is settled here:</p>
 *
 * <ul>
 *   <li><b>Multiply before divide, at full precision.</b> Lines 464 to 465 read
 *       {@code COMPUTE WS-MONTHLY-INT} then {@code = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, so the
 *       product is formed first and the reduction to a monetary scale happens once, on the quotient.
 *       Dividing first and multiplying second changes the intermediate precision and yields
 *       different cents on ordinary inputs, so the order is preserved as an order.</li>
 *   <li><b>The reduction is per category row, not per account.</b> Line 467 accumulates each
 *       reduced monthly amount into the running total, so a row's amount is reduced to a monetary
 *       scale before it is added. Reducing only the account total instead would accumulate unreduced
 *       remainders across rows and drift by more than the per-row difference.</li>
 *   <li><b>The {@code DEFAULT} group fallback.</b> When the account's own disclosure-group key does
 *       not resolve, line 437 substitutes the literal group {@code DEFAULT} and line 438 re-reads
 *       through {@code 1200-A-GET-DEFAULT-INT-RATE} at line 443. The fallback is a second lookup
 *       under a different key, not a hard-coded rate, so a missing {@code DEFAULT} row is itself an
 *       observable condition.</li>
 *   <li><b>The zero-rate gate.</b> Line 214 reads {@code IF DIS-INT-RATE NOT = 0} and guards BOTH
 *       the accrual at line 215 and the fee step at line 216. A zero rate therefore produces no
 *       generated transaction at all rather than one carrying a zero amount, and the gate is a
 *       comparison of value rather than of representation -- which is why the migrated form compares
 *       through {@code compareTo} and not through equality, since a scaled decimal carrying a
 *       different scale is unequal to zero while comparing equal to it.</li>
 *   <li><b>Flush before reset, then reset the cycle.</b> The control break writes the account before
 *       clearing the accumulator: lines 196 and 220 perform {@code 1050-UPDATE-ACCOUNT} and line 200
 *       clears the running total afterwards, so the total belonging to the account just finished is
 *       committed and not discarded. Within that write, line 352 adds the total to the current
 *       balance and lines 353 to 354 clear the cycle credit and cycle debit, which is the
 *       billing-cycle reset. Clearing the accumulator before the write is the classic control-break
 *       inversion and it silently loses one account's accrual per break.</li>
 * </ul>
 *
 * <p>Assumptions: the control break depends on its feed arriving ordered by account, so that
 * ordering is a contract of the query supplying the rule and not an incidental property of it. An
 * unordered feed produces one accrual per row instead of one per account, and does so without
 * failing.</p>
 *
 * <p>Assumptions: the baseline truncates toward zero where the migrated code rounds half up, and the
 * difference is a documented divergence rather than something for an assertion here to reconcile.
 * The baseline carries no {@code ROUNDED} phrase on the statement at
 * {@code app/cbl/CBACT04C.cbl:464-465} -- measured, not assumed: the phrase appears nowhere in that
 * program's 652 lines and nowhere in {@code app/cbl} at all -- so its target field, declared
 * {@code WS-MONTHLY-INT PIC S9(09)V99} at line 168, discards surplus digits toward zero. The
 * migrated {@code InterestCalculationService} declares {@code ACCRUAL_ROUNDING} as half-up instead.
 * The baseline behaves as it behaves, the Java implements half-up, and the divergence is registered
 * as C-ROUNDING in {@code docs/architecture/cobol-to-service-traceability.md}. An assertion in this
 * package expects the production rounding and cites that register; expecting truncation would fail
 * against production code that is behaving as designed, and quietly changing the production mode to
 * make an assertion pass would retire a divergence by accident.</p>
 *
 * <h3>The two category-balance arms are separately asserted partitions</h3>
 *
 * <p>The baseline selects an arm rather than merging them: {@code 2700-A-CREATE-TCATBAL-REC} at
 * {@code app/cbl/CBTRN02C.cbl:503} writes a new category row, and
 * {@code 2700-B-UPDATE-TCATBAL-REC} at line 526 adds the amount to the existing balance at line 527
 * and rewrites at line 528. The migrated {@code CategoryBalanceService} reports which arm ran, so
 * both are reachable and both are asserted, each by at least one case.</p>
 *
 * <p>Alternatives Considered: asserting only the resulting balance and letting a single upsert
 * resolve the branch. Rejected, because the arm taken is an observable behaviour the oracle suite
 * exercises deliberately in both directions, and a branch resolved inside the database is a branch
 * no assertion can name -- both arms would then be covered by one case that cannot report which way
 * it went, so a change collapsing the create path would leave the suite green.</p>
 *
 * <h3>Generation numbering and the retention count</h3>
 *
 * <p>Five retained generations is a uniform rule here and never a per-family parameter, and the
 * count is the baseline's own across three defining jobs rather than the total of any one of them.
 * {@code app/jcl/DEFGDGB.jcl} defines six bases, at lines 25, 31, 37, 43, 49 and 55;
 * {@code app/jcl/DEFGDGD.jcl} defines three more, at lines 28, 51 and 74; and
 * {@code app/jcl/DALYREJS.jcl} defines the tenth, at line 25. Ten bases, every one carrying
 * {@code LIMIT(5)} with {@code SCRATCH}. The migrated count is the single named constant
 * {@code DatasetGeneration.RETAINED_GENERATION_COUNT}, so an assertion cites the constant rather
 * than the literal five and a change to the rule breaks one declaration instead of ten
 * assertions.</p>
 *
 * <p>Assumptions: each of those ten citations anchors on the base's {@code NAME(} line and not on
 * the {@code DEFINE GENERATIONDATAGROUP} verb one line earlier. Both lines are defensible citations
 * and only a consistent choice makes the ten comparable, so a reader checking line 24 of
 * {@code app/jcl/DALYREJS.jcl} finds the verb while line 25 carries the name.</p>
 *
 * <p>Assumptions: {@code DatasetGenerationService} holds no state between calls. It derives the next
 * generation from the caller's list of those already present, one past the highest, and yields the
 * minimum number when that list is empty so a family's first run matches a freshly defined base. Its
 * retention method returns the generations to REMOVE rather than the survivors, which is what an
 * assertion here compares against; comparing the survivors instead would leave the assertion
 * computing a complement, and a complement of a retention rule is where an off-by-one spares or
 * deletes one generation too many.</p>
 *
 * <h2>Invariants every test here inherits from the production charter</h2>
 *
 * <p>Assumptions: NO method in the production service package carries a transaction annotation, and
 * this is the single most consequential thing to know before writing an assertion here. The
 * production charter fixes the rule, and a scan of that package confirms it holds: not one
 * {@code Transactional} annotation appears on any of its five types. A rule there therefore
 * PARTICIPATES in whatever unit of work its caller has already opened, and opens none of its own.
 * The consequence for this package is direct -- a test here must NOT expect, assert or arrange a
 * transaction boundary. There is none to observe, so an assertion about commit or rollback would be
 * asserting the caller's behaviour through a subject that has no say in it.</p>
 *
 * <p>The boundary is real and it does matter: the posting unit of work commits a transaction row, a
 * category-balance row and an account row together, transcribed from
 * {@code app/cbl/CBTRN02C.cbl:440-442} where {@code 2000-POST-TRANSACTION} performs the three writes
 * in sequence. It simply is not owned here. It belongs to the job tier, which opens it, and to the
 * repository tier, which can observe it against a real database.</p>
 *
 * <p>Alternatives Considered: asserting the atomicity of that three-write unit of work in this
 * package anyway, on the grounds that it is the most safety-relevant property in the module.
 * Rejected, and not merely postponed: with the repositories supplied as mocks there is no
 * unit of work to break, so such an assertion would pass whatever the production propagation
 * actually was, and would pass equally if the boundary were removed altogether. An assertion that
 * cannot fail for the reason it was written is worse than an absent one, because it occupies the
 * place a real check would go and reads as coverage.</p>
 *
 * <p>Assumptions: money is an exact scaled decimal at every hop, at scale 2, and never an
 * approximate binary quantity. The shared architecture rules assert that prohibition rather than
 * request it, so an assertion here neither needs to restate it nor may weaken it. What this package
 * does rely on is the narrower property that a comparison of two monetary values is a comparison of
 * value and not of scale, which is why the zero-rate gate above is settled as a {@code compareTo}
 * ruling rather than an equality one.</p>
 *
 * <h2>Test style: no container, and no application context</h2>
 *
 * <p>Every test here is a plain JUnit 5 test that constructs its subject directly and supplies each
 * repository as a mock. No Spring application context is started, no profile is activated, no
 * database container is requested and no queue emulator is contacted. Concretely: none of the
 * context-bootstrapping annotations appears anywhere in this package, and neither does a profile
 * selection, because there is nothing for a profile to configure when the subject is built by a
 * constructor call in the test itself.</p>
 *
 * <p>Trade-offs: what is given up is the ability to observe wiring. These tests cannot tell whether
 * a bean is declared, whether a property binds, whether the datasource resolves its search path, or
 * whether the queue listener stays parked -- and the last of those is a genuine concern for this
 * module, whose test profile deliberately pins
 * {@code spring.cloud.aws.sqs.listener.auto-startup} to false precisely so that a context refresh
 * starts no listener container and performs no receive. What is bought is that a rule assertion runs
 * in milliseconds, needs no daemon and no network, and fails for exactly one reason: the rule
 * disagreed. Wiring is proven where it can actually be observed, by the job and repository tiers,
 * and the profile at {@code services/batch-service/src/test/resources/application-test.yml} exists
 * for those tiers rather than for this one -- the four subjects above need no profile at all.</p>
 *
 * <h2>Inputs, and where record-layout geometry comes from</h2>
 *
 * <p>Assumptions: this module has no fixture directory, so every test here constructs its inputs in
 * code. Measured at this revision {@code services/batch-service/src/test/resources} holds exactly
 * one file, the profile named just above, and no {@code fixtures} subtree beneath it -- which makes
 * this module the exception among its siblings, several of which do carry one. The absence is worth
 * stating rather than leaving to be discovered: an author looking for a committed byte image to load
 * will not find one, and the answer is to build the value with a constructor and a private helper,
 * not to introduce a directory the boundary section below closes.</p>
 *
 * <p>Alternatives Considered: declaring a local table of offsets and widths inside a test, for those
 * cases that do need a positional layout rather than a constructed object. Rejected, because the
 * layout would then exist in two places and the local copy is the one free to drift. The house
 * convention the oracle suite states for extending itself is explicit on the point: the fourth item
 * of the extension section of {@code tests/README.md} has layouts resolve through the compiler's
 * copybook path and requires that one is never duplicated but kept single-sourced from
 * {@code app/cpy/} -- and the migrated equivalent of that path is
 * {@code com.carddemo.common.codec.CopybookLayout}, with
 * {@code com.carddemo.common.codec.ZonedDecimalCodec} for the sign-overpunch values inside a
 * layout. A test needing geometry takes it from those and never from a table of its own, so a
 * layout amendment reaches every reader at once instead of leaving one behind.</p>
 *
 * <h2>Boundaries this package does not cross</h2>
 *
 * <p>Six additions are plausible enough here to be made without deliberation, and each is closed
 * below with its reason, so that making one is a decision to revisit this list rather than an
 * unnoticed drift.</p>
 *
 * <dl>
 *   <dt>No controller test</dt>
 *   <dd>This module exposes no request surface to test. Its production tree holds exactly seven
 *       subpackages -- {@code job}, {@code service}, {@code repository}, {@code domain},
 *       {@code dto}, {@code mapper} and {@code config} -- with no {@code api} among them, and the
 *       production charter fixes that absence as a boundary rather than a gap. The module is started
 *       as an orchestrated container task and takes its job selection and business date as process
 *       arguments, so a controller test would have no invocation path to drive and would assert
 *       against a package the plan does not create.</dd>
 *
 *   <dt>No integration test</dt>
 *   <dd>The two runners divide this tree by name alone. A name ending in {@code Test} is collected
 *       by the unit runner in the Maven {@code test} phase, with no database available; a name
 *       ending in {@code IT} is collected by the integration runner at {@code integration-test} and
 *       asserted at {@code verify}. Everything in this package is a no-container rule assertion and
 *       belongs to the first, so an {@code IT} suffix here would hand a test that needs nothing to
 *       the runner that provides everything. Integration coverage belongs to the repository
 *       tier.</dd>
 *
 *   <dt>No local configuration for the architecture rules</dt>
 *   <dd>The layering invariants of the migrated decomposition have exactly one owner, the shared
 *       gate under {@code services/common-lib/src/test/java/com/carddemo/common/architecture}, which
 *       this module executes over its own compiled output through the reactor's shared runner
 *       execution. A local copy of those rules, or a local engine configuration tuning them for this
 *       module, would be a second place a boundary could be relaxed while the first still read as
 *       intact, and the shared gate would have no way to report the divergence.</dd>
 *
 *   <dt>No ignore file</dt>
 *   <dd>Build-output ignore rules are carried by the single repository-root file that already covers
 *       every module's output directory. A second one here would shadow it for this subtree alone,
 *       so a rule added at the root would quietly stop applying wherever the local file overlapped
 *       it.</dd>
 *
 *   <dt>No subdirectory, no shared abstract base, no standalone builder</dt>
 *   <dd>This package is a leaf and stays one. A constructed vector is a private helper inside the
 *       one test that needs it, which keeps the value visible in the file whose assertion depends on
 *       it. A shared base or a separate builder would move set-up to a level none of the tests owns,
 *       where a later edit could weaken an assertion's inputs while reading as ordinary
 *       maintenance.</dd>
 *
 *   <dt>No package README</dt>
 *   <dd>The documentation gate narrows itself to Java sources, so a Markdown file here is never
 *       scanned and would satisfy no check. It would also split this package's contract across two
 *       files with nothing keeping them in agreement, and the enforced one is this file.</dd>
 * </dl>
 *
 * <h2>Parameters, return values and exceptions: declared inapplicable</h2>
 *
 * <p>A package declaration accepts no argument, yields no value and raises nothing, so this charter
 * carries no parameter, return or exception at-clause -- and no authorship, availability or revision
 * at-clause either, none of which the shared ruleset requires of anything.</p>
 *
 * <p>Assumptions: the inapplicability is declared rather than left silent, because the project's
 * single user-specified rule lists a docstring that omits parameters, return values or purpose among
 * its forbidden patterns, and a reader has to be able to tell a declared inapplicability from an
 * oversight. Fabricating the at-clauses instead would be worse than useless: Javadoc has no
 * parameter, return or exception concept for a package at all, and the shared ruleset audits
 * at-clause bodies for emptiness, so an invented tag would either be discarded or reported. An
 * exception clause whose body states that nothing is thrown is not an acceptable stand-in either,
 * since it would document an absence the construct is incapable of having.</p>
 *
 * <p>Assumptions: this exemption is this compilation unit's alone and does not travel to the tests
 * beside it. A test type, a test method and a private helper each do have parameters, return values
 * and thrown types to document, and the obligation there rests on the rule's docstring-elements
 * clause, on the house convention the oracle suite states for every new test, helper and mock, and
 * on the shared ruleset's own at-clause validation.</p>
 *
 * <h2>Why this charter exists, and why deleting it fails the build</h2>
 *
 * <p>Assumptions: this file is load-bearing rather than decorative, on two grounds that hold
 * independently of one another. The first is the project's single user-specified rule, which
 * attaches a documentation duty to every module entry point: in Java the entry point of a package is
 * its package declaration, and only a {@code package-info.java} can carry documentation for it, so
 * this file is the only place that duty can be discharged for this package. The second ground is
 * mechanical, and it fires before anything in this directory is compiled.</p>
 *
 * <p>Assumptions: two checks in {@code config/checkstyle/checkstyle.xml} reach this directory and
 * neither is redundant. {@code JavadocPackage}, declared at the outer checker level, inspects the
 * file set and requires a {@code package-info.java} to EXIST in any directory holding an audited
 * source file. {@code MissingJavadocPackage}, declared inside the tree walker, parses the file and
 * requires it to CARRY Javadoc. A charter reduced to a bare package statement satisfies the first
 * and fails the second, which is exactly why prose is the deliverable and mere existence is not.
 * The plugin runs in the Maven {@code validate} phase, fails the build on violation, and counts
 * warning severity as failing, so both fire ahead of compilation. Nor is the audit narrowed away
 * from here: the companion suppression file scopes its only two entries to generated sources and to
 * test RESOURCES, so nothing under {@code src/test/java} is exempt. The consequence is worth stating
 * plainly -- removing this file would fail the sibling tests in this directory, not itself.</p>
 *
 * <p>Trade-offs: no in-source escape hatch exists here, and none is wanted. The annotation-based
 * suppression filter and both comment-based ones are deliberately absent from that configuration, so
 * an in-source suppression comment and an in-source suppression annotation each do nothing to this
 * gate; the file-based filter is the only one configured. The flexibility surrendered is the whole
 * point -- a gate that can be switched off line by line, with no single artifact recording that it
 * was switched off, is not a gate.</p>
 *
 * <p>Assumptions: no charter belongs at {@code com/} or at {@code com/carddemo/} beneath this test
 * root, and none is created. The presence check fires only for directories that hold a source file
 * the audit processes, and both of those are pure namespace segments holding none, so the check
 * cannot fire in either -- while the content check would immediately demand real Javadoc from any
 * defensive stub placed there, turning an unnecessary file into a failing one. The first charter in
 * this chain is the already-authored one at
 * {@code services/batch-service/src/test/java/com/carddemo/batch/package-info.java}, and this
 * directory is where the compilation units of this subtree resume.</p>
 *
 * <p>Assumptions: the graded numeric result vocabulary of the oracle suite under {@code tests/}
 * describes that suite and the batch container's own process exit status, and describes nothing in
 * this package. The runners that collect these tests, and the documentation gate that audits them,
 * are binary: each either passes or fails the build, no tolerance of any kind is configured for any
 * of them, and none may be reported in graded terms. Borrowing the graded vocabulary for a build
 * result here would let a real failure read as an accepted warning.</p>
 */
package com.carddemo.batch.service;
