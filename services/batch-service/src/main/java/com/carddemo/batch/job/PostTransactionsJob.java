package com.carddemo.batch.job;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.DailyTransactionRepository;
import com.carddemo.batch.repository.TransactionRejectRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.CategoryBalanceService;
import com.carddemo.batch.service.DailyFeedWatermarkService;
import com.carddemo.batch.service.DatasetGenerationService;
import com.carddemo.batch.service.PostingRecordUnitOfWork;
import com.carddemo.batch.service.PostingValidationService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParametersValidator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Posts the day's transaction feed to the ledger, the account master and the category balances.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this job is state four of the nightly chain and re-expresses
 * {@code app/cbl/CBTRN02C.cbl}, driven by {@code app/jcl/POSTTRAN.jcl:23}
 * ({@code EXEC PGM=CBTRN02C}). It walks the daily transaction feed in key order and, for each
 * record, either posts it or writes it to the reject stream. The order of its four decisions is the
 * reference's own: resolve the card, read the account, validate, then post or reject.</p>
 *
 * <p>Assumptions: every business RULE this job applies is delegated, and so is the per-record
 * application of them. {@code PostingRecordUnitOfWork} applies the decisions to ONE record and
 * issues that record's writes in reference order; under it, which conditions a record fails is
 * {@code PostingValidationService}, which reproduces the short-circuit chain at
 * {@code app/cbl/CBTRN02C.cbl:370-420}; whether a category balance is created or updated is
 * {@code CategoryBalanceService}, which reproduces the two arms at
 * {@code app/cbl/CBTRN02C.cbl:503-524} and {@code app/cbl/CBTRN02C.cbl:526-542}; how a feed record
 * becomes a posted record is {@code DailyTransactionMapper}; how a rejected record becomes the
 * 430-byte stream record and its decomposed row is {@code TransactionRejectRecordMapper}. What this
 * class owns is the WALK over the feed, the unit of work each record is applied inside, the reject
 * stream's assembly and generation, and the graded return code -- which is precisely what a JCL step
 * owned.</p>
 *
 * <p>Refactoring Rationale: the per-record application used to be private to this class, and it was
 * extracted for a reason that had nothing to do with layering taste: a private method cannot be
 * driven by a test, so the repository tests that claim to prove posting atomicity re-implemented the
 * write sequence in their own helpers and one of them omitted the watermark checkpoint entirely. A
 * production ordering, cycle-bucket, sign-test or checkpoint change therefore could not fail them.
 * The transaction boundary deliberately did NOT move with the code -- it is still opened here, once,
 * per record -- so the extraction changes what is reachable by a test and nothing about what
 * commits.</p>
 *
 * <h2>The exit-status contract, and why this is the only job that can warn</h2>
 *
 * <p>Assumptions: {@code app/cbl/CBTRN02C.cbl:229} reads {@code IF WS-REJECT-COUNT > 0} and
 * {@code app/cbl/CBTRN02C.cbl:230} reads {@code MOVE 4 TO RETURN-CODE}. The test is STRICTLY greater
 * than zero, so one reject is enough. That is the only {@code RETURN-CODE} statement in any of the
 * twelve batch programs, so this job is the only one in the package that can emit the warn tier.</p>
 *
 * <p>Alternatives Considered: two other gradings were available and both are rejected on evidence.
 * (a) Treating any reject as a STEP FAILURE. That would break the four committed
 * {@code return_code.expected} golden masters that pin {@code 4} --
 * {@code tests/golden/posting/reject_100_card_missing}, {@code reject_101_acct_missing},
 * {@code reject_102_overlimit} and {@code reject_103_expired} -- and, worse, it would halt the
 * nightly chain on an ordinary business reject, because a card that is absent from the
 * cross-reference is a normal daily occurrence rather than a malfunction. (b) Reporting {@code 0}
 * and leaving the rejects to the log. That would make the orchestrator's {@code Choice} predicate on
 * state four undecidable, since the predicate reads the graded tier and nothing else. The warn tier
 * exists because {@code app/jcl/TRANBKP.jcl:51} carries {@code COND=(4,LT)} -- a downstream step
 * written to RUN after a warn -- so collapsing the tier would remove a distinction the reference's
 * own job stream depends on.</p>
 *
 * <h2>One transaction per record, spanning that record's writes and its checkpoint</h2>
 *
 * <p>Alternatives Considered: a saga, and a transactional outbox with compensating reversals. Both
 * are rejected for the same measurable reason. Each replaces one atomic commit with a sequence of
 * committed steps plus reversals, which makes intermediate states OBSERVABLE -- a posted transaction
 * with an unposted balance, or an updated account with no ledger row. The golden masters compare
 * {@code tranfile.expected}, {@code acctdat.expected} and {@code tcatbal.expected} from the SAME
 * run, so any such intermediate state that survived to comparison would be flagged as a parity
 * failure, and correctly so. The migration plan reaches the same conclusion in its sections 0.4.1.3
 * and 0.4.3, and this class is where that conclusion is executed rather than restated.</p>
 *
 * <p>Trade-offs: keeping one ACID commit costs database-per-service purity. The three rows live in
 * TWO schemas, {@code ledger} and {@code account}, so this module runs under a dedicated role
 * holding narrowly-scoped cross-schema WRITE grants that
 * {@code data-migration/sql/V0__schemas_and_roles.sql} declares. That exception is accepted
 * deliberately, because the alternative changes observable behaviour and the grant does not. It is
 * asserted, not assumed: {@code PostingUnitOfWorkIT} proves the cross-schema writes commit and roll
 * back as one.</p>
 *
 * <p>Assumptions: the boundary is declared HERE and nowhere else, and it brackets ONE RECORD. The
 * step is built with {@code PROPAGATION_NOT_SUPPORTED} so the tasklet body itself runs in no
 * transaction, and {@link #postOneRecord} opens one through a {@code TransactionTemplate} around
 * {@code PostingRecordUnitOfWork.applyOneRecord} -- the validation reads, the three writes and the
 * watermark checkpoint for a single feed record, committed or rolled back together. No class in the
 * sibling {@code ..batch.service} package that participates in that unit carries a
 * {@code Transactional} annotation, precisely so that this file remains the single owner of the
 * boundary; a second boundary opened down there would let a rule commit independently of the writes
 * it informed. The one annotated class there, {@code BatchStepLedgerWriter}, is deliberately outside
 * the unit: it writes this module's own step ledger in a transaction of its own so that a failure
 * cannot roll back the record of itself.</p>
 *
 * <p>Refactoring Rationale: the boundary used to be the STEP's, so one transaction spanned the whole
 * pass -- every record of the feed committed or rolled back together. Three things were wrong with
 * that, and each is a production failure rather than a matter of taste. One rejected-then-failing
 * record at the end of a 300-record night discarded 299 correct postings, which is not what the
 * reference does: {@code app/cbl/CBTRN02C.cbl} commits each record's writes as it makes them and
 * carries on. Row locks taken on the first record were held until the last, so the account and
 * category rows of every posted account stayed locked for the length of the run and any concurrent
 * online update queued behind the whole batch instead of behind one record. And the durable step
 * ledger's own promise -- that a redriven step can tell what the previous attempt achieved -- was
 * weakened, because nothing the pass wrote existed until the pass ended. Per-record scope keeps the
 * three writes atomic -- together with the watermark advance that accounts for them, which is a
 * target addition rather than one of the reference's three -- which is the property the migration
 * plan's section 0.4.1.3 requires and the one {@code PostingUnitOfWorkIT} and
 * {@code AccountRepositoryIT} prove, while making the unit of work the record rather than the
 * night.</p>
 *
 * <p>Trade-offs: a per-record transaction costs one begin and one commit per accepted record instead
 * of one per pass, which is real work at feed scale. It is accepted because the alternative it
 * replaces is not "fewer commits" but "all-or-nothing for the night", and because a partially
 * completed pass is exactly what the durable step ledger and the orchestrator's redrive are built to
 * resume. Assumptions: a rejected record needs no writes to the ledger or the account master at all,
 * so its transaction covers only the decomposed reject row -- the 430-byte stream record is appended
 * AFTER that commit, so a rolled-back reject cannot leave a stream record with no row behind it.</p>
 *
 * <h2>Divergence D-POSTING-ATOMIC-NO-REJECT-109: the reference can post partially, and this
 * cannot</h2>
 *
 * <p>Refactoring Rationale: this heading read "Divergence D-6" and named the wrong difference. The
 * register binds {@code D-6} to the authorization context's distributed commit, and every other
 * citation of that identifier in the repository -- in {@code AuthorizationApplication},
 * {@code FraudMarkingService}, {@code OutboxMessage} and three test charters -- means that one. A
 * second difference under the same letter makes every one of those citations ambiguous in exactly
 * the direction the register's reconcile-by-search discipline depends on, and the register states
 * that its letter scheme is closed for that reason. The difference below is therefore cited by the
 * descriptive identifier the register now carries for it.</p>
 *
 * <p>Refactoring Rationale: this is the strongest correctness improvement in the file, and it is a
 * DIVERGENCE rather than a transcription, so it is stated plainly. In the reference,
 * {@code 2800-UPDATE-ACCOUNT-REC} at {@code app/cbl/CBTRN02C.cbl:554-559} rewrites the account and,
 * on {@code INVALID KEY}, moves reason {@code 109} into the failure fields and then RETURNS NORMALLY
 * at {@code :560}. It writes no reject record and it aborts nothing, so control flows straight on to
 * {@code app/cbl/CBTRN02C.cbl:442}, which writes the transaction anyway. Reason {@code 109} is a
 * DEAD WRITE: only the validation path at {@code app/cbl/CBTRN02C.cbl:446-465} ever reaches the
 * reject writer, and {@code :208} clears the reason before the next record. The reference can
 * therefore leave a category balance updated and a transaction posted while the account update
 * silently failed -- a state no output file records. The per-record transaction makes that state
 * unreachable: a failed account update rolls that record's three writes back. The divergence is registered as
 * {@code D-POSTING-ATOMIC-NO-REJECT-109} in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which this class does not author.</p>
 *
 * <p>Assumptions: rolling back is the WHOLE of the divergence, and no reject row is written for this
 * failure. Reason {@code 109} is as unreachable here as it is in the reference: this class wraps the
 * account write in no handler, so a failed account update rolls its own record's three writes back
 * and the failure propagates out of the pass for the orchestrator's per-state retry to re-run the
 * step, while the reject writer is reached only from the validation branch. Assumptions: records
 * already committed by earlier per-record transactions are NOT undone by that propagation, which is
 * the reference's behaviour rather than a weakening of it -- and the durable step ledger records the
 * failure so a redrive resumes rather than repeats. Alternatives Considered: writing a durable reason-109 row
 * from outside the rolled-back unit of work so the failure were queryable. Rejected because it needs
 * a second transaction boundary inside the one file that declares itself the sole owner of the
 * boundary, and because a row the reference's stream does not carry would break the byte-for-byte
 * reject-stream comparison the golden masters make -- and the failure is already observable as a
 * failed batch state carrying this step's own logged exception. The register records that reasoning
 * in full and records the withdrawn identifier the earlier design was registered under.</p>
 *
 * @see com.carddemo.batch.service.PostingRecordUnitOfWork for the per-record decisions and writes
 *     this job brackets, and the reject predicates and category-balance arms beneath them
 */
@Configuration
public class PostTransactionsJob {

    /**
     * The name this job registers under, which is an orchestration contract rather than a label.
     *
     * <p>Assumptions: the value is taken from the shared token enumeration rather than spelled,
     * because {@code BatchApplication} resolves the job to run by matching the process argument
     * against a registered bean's name. A name that differed by one character would compile and
     * deploy and then fail inside the state machine with an unresolved-job error.</p>
     */
    public static final String JOB_NAME = BatchJobName.POST_TRANSACTIONS.token();

    /** The step name the durable ledger records this job's progress under. */
    public static final String STEP_NAME = JOB_NAME + BatchJobName.STEP_NAME_SUFFIX;

    /**
     * The banner the reference writes before opening any file, reproduced verbatim.
     *
     * <p>From {@code app/cbl/CBTRN02C.cbl:194}.</p>
     */
    public static final String START_BANNER = "START OF EXECUTION OF PROGRAM CBTRN02C";

    /**
     * The banner the reference writes after grading its return code, reproduced verbatim.
     *
     * <p>From {@code app/cbl/CBTRN02C.cbl:232}.</p>
     */
    public static final String END_BANNER = "END OF EXECUTION OF PROGRAM CBTRN02C";

    /**
     * The processed-count label from {@code app/cbl/CBTRN02C.cbl:227}, with ONE space before the
     * colon.
     */
    public static final String PROCESSED_LABEL = "TRANSACTIONS PROCESSED :";

    /**
     * The rejected-count label from {@code app/cbl/CBTRN02C.cbl:228}, with TWO spaces before the
     * colon.
     */
    public static final String REJECTED_LABEL = "TRANSACTIONS REJECTED  :";

    /**
     * The object name the reject stream is staged under inside its allocated generation.
     *
     * <p>Assumptions: the name carries no generation or date component, because
     * {@code DatasetGeneration.keyPrefix()} already places the object under its
     * {@code dt=}/{@code gen=} partition. Encoding either here would duplicate the coordinate and
     * let the two spellings drift.</p>
     */
    public static final String REJECT_DATASET_OBJECT_NAME = "dalyrejs";

    /**
     * Width of each counter as the reference displays it.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:185-186} declares both counters {@code PIC 9(09)}, and an
     * unsigned numeric-display item is emitted zero-padded to its full picture width.</p>
     */
    public static final int COUNTER_DIGITS = 9;

    /** Renders a label immediately followed by its nine-digit zero-padded counter. */
    private static final String COUNTER_LINE = "%s%0" + COUNTER_DIGITS + "d";

    /** Prefix of the temporary file the reject stream is accumulated into before staging. */
    private static final String STAGING_FILE_PREFIX = "carddemo-dalyrejs-";

    /** The operational log this job reports its banners, record counts and staging through. */
    private static final Logger LOG = LoggerFactory.getLogger(PostTransactionsJob.class);

    /** The feed the day's transactions are read from. */
    private final DailyTransactionRepository feed;

    /** The allocator of the reject stream's generation, standing for the {@code (+1)} in the DD. */
    private final DatasetGenerationService generations;

    /** The durable step record that makes a re-run of a completed step a no-op. */
    private final BatchStepLedger ledgerOfSteps;

    /** The stored position that makes this pass consume its own input and not every night's. */
    private final DailyFeedWatermarkService watermark;

    /**
     * Builds the job over the rules, repositories and dataset allocator it composes.
     *
     * @param feed the daily transaction feed; must not be {@code null}
     * @param generations the generation allocator the reject dataset is staged through; must not be
     *     {@code null}
     * @param ledgerOfSteps the durable step ledger; must not be {@code null}
     * @param watermark the feed's consumed position, read under its lock before the walk; must not
     *     be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    // WHY : Refactoring Rationale: an EntityManager parameter stood after the clock, and it is
    //       withdrawn with the per-record boundary that made it meaningless. It existed so the pass
    //       could flush and clear the persistence context between batches, which bounded memory while
    //       ONE transaction spanned the whole feed. A per-record transaction is its own persistence
    //       context: it is created at the record's begin and released at its commit, so nothing
    //       accumulates across records for a clear to discard. Worse than redundant, the pair would
    //       now be illegal -- a shared EntityManager proxy refuses flush() outside a transaction, and
    //       the tasklet body deliberately runs outside one. Keeping an unused collaborator would say
    //       this job still manages a pass-wide context, which is exactly the design that was removed.
    // WHY : Refactoring Rationale: a CardXrefRepository parameter stood between the feed and the
    //       account master, and it is withdrawn. This job resolved the card itself, so it held the
    //       cross-reference; the validation service now owns that read and hands the resolved row
    //       back with its outcome, which leaves nothing here to read it for. Keeping an unread
    //       collaborator would say that this job still reaches the cross-reference table, and a
    //       reader auditing which components touch cardholder data would have to open the body to
    //       find that it does not. The account master stays, because the accumulated balance is
    //       written through it.
    // WHY : Refactoring Rationale: a @SuppressWarnings("checkstyle:ParameterNumber") stood on this
    //       constructor and is withdrawn, because it suppressed nothing. config/checkstyle/checkstyle.xml
    //       configures ten documentation checks and no ParameterNumber module, and it installs only the
    //       file-based SuppressionFilter -- no SuppressWarningsFilter and no SuppressWarningsHolder --
    //       so an annotation of that form could not have reached the gate even if the rule were added.
    //       An inert annotation that reads as an active exemption is worse than no annotation: a reader
    //       auditing which code is exempt from the documentation gate finds an entry here that the
    //       gate's own single suppression file does not carry, and the two artifacts then disagree
    //       about the exemption list. If a parameter-count rule is ever wanted, the rule, the
    //       suppression mechanism and the rationale belong in one edit, made in that ruleset where the
    //       whole exemption list is reviewable in one place.
    // WHY : Refactoring Rationale: six parameters were withdrawn. The account master, the posted
    //       ledger, the reject rows, the validation rule, the category-balance rule and the clock
    //       were all held here solely for the per-record decisions, which now live in
    //       PostingRecordUnitOfWork; this job retains only what the PASS needs -- the feed it walks,
    //       the generation allocator the reject dataset is staged through, the durable step ledger,
    //       and the watermark whose starting position it reads under a lock. Keeping the six would
    //       say this job still writes the account master and the ledger directly, and a reader
    //       auditing which components touch cardholder balances would have to open the body to find
    //       that it does not.
    // WHY : Alternatives Considered: taking the extracted unit of work here as a seventh parameter,
    //       which reads as the obvious replacement for the six. It is not available: this class is a
    //       @Configuration and it DECLARES that unit through a @Bean method below, so a constructor
    //       parameter of that type asks the container to supply a bean whose factory needs this very
    //       instance -- Spring refuses the context with "Requested bean is currently in creation".
    //       The unit is therefore taken by the registration method that assembles the step, beside
    //       the transaction manager it is paired with, and handed down the pass. That is also the
    //       shape the boundary already has: the TransactionTemplate cannot be a field either,
    //       because it is built from a registration-time argument, and the two travel together
    //       precisely because neither is meaningful without the other.
    public PostTransactionsJob(DailyTransactionRepository feed,
            DatasetGenerationService generations, BatchStepLedger ledgerOfSteps,
            DailyFeedWatermarkService watermark) {

        this.feed = Objects.requireNonNull(feed, "feed must not be null");
        this.generations = Objects.requireNonNull(generations, "generations must not be null");
        this.ledgerOfSteps = Objects.requireNonNull(ledgerOfSteps, "ledgerOfSteps must not be null");
        this.watermark = Objects.requireNonNull(watermark, "watermark must not be null");
    }

    /**
     * Registers the per-record unit of work this job brackets, over the rules and repositories it uses.
     *
     * <p>Assumptions: the unit is declared HERE, in the one configuration that consumes it, rather
     * than carrying a stereotype annotation of its own. Three contexts assemble this step -- the
     * deployed application, the posting parity harness and the job-registration census -- and only the
     * first scans the service package, so a stereotype would register the collaborator in one of them
     * and leave the other two resolving a bean that does not exist. One factory method registers it
     * identically in all three.</p>
     *
     * <p>Assumptions: this method opens no transaction and the unit it returns declares none. The
     * boundary is opened per record by {@link #postOneRecord}, which is what keeps it declared in one
     * file.</p>
     *
     * @param accounts the account master the posted amounts are accumulated into; must not be
     *     {@code null}
     * @param ledger the posted-transaction ledger; must not be {@code null}
     * @param rejects the reject stream's decomposed rows; must not be {@code null}
     * @param validation the posting validation rule; must not be {@code null}
     * @param categoryBalances the category-balance rule; must not be {@code null}
     * @param watermark the feed's consumed position, advanced once per record accounted for; must not
     *     be {@code null}
     * @param clock the clock the posted records' processing stamp is read from; must not be
     *     {@code null}
     * @return the per-record unit of work, never {@code null}
     */
    @Bean
    public PostingRecordUnitOfWork postingRecordUnitOfWork(AccountRepository accounts,
            TransactionRepository ledger, TransactionRejectRepository rejects,
            PostingValidationService validation, CategoryBalanceService categoryBalances,
            DailyFeedWatermarkService watermark, Clock clock) {

        return new PostingRecordUnitOfWork(accounts, ledger, rejects, validation, categoryBalances,
                watermark, clock);
    }

    /**
     * Registers the job under the token the orchestrator names it by.
     *
     * @param jobRepository the framework's durable job repository; must not be {@code null}
     * @param transactionManager the transaction manager each RECORD's unit of work is opened on, and
     *     the one the step suspends rather than uses; must not be {@code null}
     * @param validator the shared parameter validator every job in this module is built with; must
     *     not be {@code null}
     * @param perRecord the per-record unit of work each record's decisions and writes are made by,
     *     inside the boundary this method builds around it; must not be {@code null}
     * @return the registered job, never {@code null}
     */
    @Bean
    // WHY : Refactoring Rationale: the factory method is named WITHOUT the "Job" suffix its class
    //       carries, and the difference is load-bearing rather than cosmetic. A `@Configuration`
    //       class registered by type takes a bean id from its own decapitalised class name, so a
    //       `@Bean` method spelled `postTransactionsJob` inside `PostTransactionsJob` claims the
    //       identifier the class itself already holds -- and Spring refuses the context with a
    //       BeanDefinitionOverrideException rather than choosing between them. Dropping the suffix
    //       gives the two definitions distinct identifiers.
    // WHY : Assumptions: nothing selects this bean by its identifier. The command contract iterates
    //       the `Job` beans and compares `getName()` against its argument, and `getName()` comes
    //       from JOB_NAME below, so the identifier is free to change and the job's published token
    //       is not.
    // WHY : Assumptions: the per-record unit arrives as a parameter of this method rather than as a
    //       field of the configuration, and the container resolves it from the @Bean method above.
    //       That is a plain inter-bean reference and not the cycle a constructor parameter would be:
    //       this instance is already built by the time the container calls this method, so the unit's
    //       factory can run. It is declared beside the transaction manager because the pair is the
    //       whole of what a record's writes need -- the work and the boundary it commits inside.
    public Job postTransactions(JobRepository jobRepository,
            PlatformTransactionManager transactionManager, JobParametersValidator validator,
            PostingRecordUnitOfWork perRecord) {

        // WHY : Assumptions: the template is built once per job registration and shared by every
        //       record of the pass, which is safe because a TransactionTemplate is documented as
        //       thread-safe and immutable once configured -- it holds the manager and the definition,
        //       never a transaction. Building one per record would allocate the same object 300 times
        //       a night for no behavioural difference.
        // WHY : Assumptions: the propagation is left at the default REQUIRED rather than set to
        //       REQUIRES_NEW. There is deliberately no ambient transaction for it to join, because the
        //       step suspends its own below, so REQUIRED starts a fresh transaction per record exactly
        //       as REQUIRES_NEW would -- and REQUIRED is the setting that would also do the right thing
        //       if this pass were ever driven from a caller that had one open, by joining it instead of
        //       silently committing inside it.
        TransactionTemplate perRecordUnitOfWork = new TransactionTemplate(transactionManager);

        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(validator)
                .start(new StepBuilder(STEP_NAME, jobRepository)
                        .tasklet((contribution, context) ->
                                runStep(contribution, context, perRecordUnitOfWork, perRecord),
                                transactionManager)
                        // WHY : Assumptions: NOT_SUPPORTED suspends the step's transaction for the
                        //       length of the tasklet body, which is what moves the boundary from the
                        //       pass to the record. Without it the body would run inside a step-wide
                        //       transaction and the per-record template would merely join it, so every
                        //       record would still commit or roll back with the night -- the template
                        //       alone changes nothing. Alternatives Considered: NEVER, which would
                        //       additionally forbid a caller from having one open; rejected because the
                        //       framework itself may hold one around the step's own metadata writes and
                        //       NEVER would fail the step for a transaction that is not ours.
                        .transactionAttribute(taskletRunsOutsideAnyTransaction())
                        .build())
                .build();
    }

    /**
     * Builds the transaction attribute that keeps the tasklet body out of the step's transaction.
     *
     * <p>Assumptions: the attribute is spelled here rather than inline so the propagation setting has
     * one home and the {@link #postTransactions} builder chain stays readable. It carries only the
     * propagation: no timeout, because the orchestrator's per-state {@code TimeoutSeconds} bounds the
     * task and a second, shorter bound here would fail a long night that the state machine still
     * considers healthy; and no isolation, because each record's own transaction takes the datasource
     * default that {@code PostingUnitOfWorkIT} asserts against.</p>
     *
     * @return the propagation attribute for the tasklet step, never {@code null}
     */
    private static DefaultTransactionAttribute taskletRunsOutsideAnyTransaction() {
        DefaultTransactionAttribute suspended = new DefaultTransactionAttribute();
        suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return suspended;
    }

    /**
     * Runs the whole posting pass once, under the durable step record, between the two banners.
     *
     * <p>Assumptions: the injected token is read as a GENERATION DATE and not as a business date, and
     * the distinction is the whole reason {@link BatchConfig#generationDateOf(ChunkContext)} exists.
     * {@code app/jcl/POSTTRAN.jcl:23} carries NO {@code PARM=}, so the reference PROGRAM receives no
     * date and derives no posted field from one, and neither does this job. What the reference STEP
     * does receive is per-run dataset identity, in a data definition rather than a parameter:
     * {@code app/jcl/POSTTRAN.jcl:34-38} allocates the reject stream as
     * {@code DSN=AWS.M2.CARDDEMO.DALYREJS(+1)}. The migration plan's rule T6 turns that
     * {@code (+1)} into a new object-store generation prefix and section 0.4.1.7 fixes the prefix as
     * {@code dt=YYYY-MM-DD/gen=NNNN}, whose date component a migrated step has to be told because
     * section 0.7.5 forbids reading it from a clock. So the parameter is required here as the migrated
     * form of that data definition -- orchestration metadata that names where the reject stream lands
     * -- and it reaches no field of any posted or rejected record.</p>
     *
     * @param contribution the framework's handle for reporting this step's exit status; must not be
     *     {@code null}
     * @param context the chunk context carrying the job parameters; must not be {@code null}
     * @param unitOfWork the per-record transaction boundary each accepted record's three writes and
     *     its watermark checkpoint commit inside; must not be {@code null}
     * @param perRecord the per-record unit of work whose decisions and writes that boundary brackets;
     *     must not be {@code null}
     * @return {@link RepeatStatus#FINISHED} always, because the pass completes within one invocation
     */
    private RepeatStatus runStep(StepContribution contribution, ChunkContext context,
            TransactionTemplate unitOfWork, PostingRecordUnitOfWork perRecord) {

        String runId = BatchConfig.runIdOf(context);
        BusinessDate generationDate = BatchConfig.generationDateOf(context);

        LOG.info(START_BANNER);

        BatchStepLedger.StepOutcome outcome = this.ledgerOfSteps.runStep(
                runId, STEP_NAME, BatchJobName.POST_TRANSACTIONS,
                () -> postEveryFeedRecord(runId, generationDate, unitOfWork, perRecord));

        // WHY : Assumptions: the exit status is set from the ledger's recorded return code rather
        //       than from the counters this invocation produced, so a redriven step that the ledger
        //       reports as already complete reports the tier the ORIGINAL attempt reached.
        //       Recomputing it would report a clean run for a step that had legitimately warned, and
        //       the orchestrator's downstream choice reads that tier.
        if (outcome.returnCode() == BatchReturnCode.SOFT_WARN) {
            contribution.setExitStatus(
                    new ExitStatus(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS));
        }

        LOG.info(END_BANNER);
        return RepeatStatus.FINISHED;
    }

    /**
     * Walks the feed in key order, posting or rejecting each record, and reports the resulting tier.
     *
     * <p>Assumptions: the walk is keyset-paginated on the feed's ingest ordinal rather than
     * offset-paged. The migration plan's section 0.7.4 requires it, and the reason is concrete: an
     * offset page recounts the rows before it on every request, so a row inserted or removed during
     * the walk shifts the window and a record is skipped or seen twice. The ordering key is the
     * ingest ordinal and never the processing timestamp, which is null on every unposted row and so
     * cannot order them at all.</p>
     *
     * <p>Refactoring Rationale: the walk STARTS at the feed's stored consumed position and no longer
     * at the first row, and advances that position as it goes. The old start was
     * correct for the reference and wrong here. {@code app/jcl/POSTTRAN.jcl:30-31} supplies the feed
     * as a flat dataset that is replaced between runs, so reading the whole file IS reading tonight's
     * transactions; the target's feed is a table that accumulates, because its rows are what the
     * three verification passes compare against, so beginning at the first row re-posted every
     * earlier night -- adding those amounts to account balances a second time and inserting a second
     * posted row for each. Every one of those postings is individually valid, so no reject was
     * written and no return code changed: the chain would have reported a clean night.</p>
     *
     * <p>Assumptions: the advance is written inside each RECORD'S OWN transaction, together with
     * the three writes or the reject row it accounts for, rather than once for the whole pass. The
     * position and the postings it accounts for therefore still commit together -- which is the
     * property that matters -- at the granularity the boundary now has. A single advance at the end
     * would be the wrong pairing under a per-record boundary: an interrupted pass would leave a
     * committed prefix of postings behind an unmoved position, and the retry would re-present exactly
     * those rows and post them a second time, which is the defect the watermark exists to prevent.</p>
     *
     * <p>Trade-offs: the starting position is read under its lock in a unit of work of its own, so the
     * lock is no longer held for the length of the night. Two overlapping passes are kept apart by the
     * chain's online-write lease -- which the read's own comment already names as the primary
     * mechanism -- and by the advance being monotonic, so a position can never move backwards. A
     * pass-long lock is not available here at any price: an inner transaction advancing the same row
     * would block on the lock its own pass was holding on another connection.</p>
     *
     * @param runId the orchestrator execution the reject generation is allocated against, and the run
     *     recorded against the advanced watermark; must not be {@code null}
     * @param generationDate the injected date the reject generation is partitioned under, which is
     *     orchestration metadata rather than business input; must not be {@code null}
     * @param unitOfWork the per-record transaction boundary each record's writes and its watermark
     *     advance commit inside; must not be {@code null}
     * @param perRecord the per-record unit of work each record's decisions and writes are made by;
     *     must not be {@code null}
     * @return the tier the pass reached -- warn when any record was rejected, clean otherwise, never
     *     {@code null}
     * @throws IllegalStateException if the reject stream cannot be assembled or staged
     */
    private BatchReturnCode postEveryFeedRecord(String runId, BusinessDate generationDate,
            TransactionTemplate unitOfWork, PostingRecordUnitOfWork perRecord) {

        // WHY : Assumptions: the read takes a row lock and therefore needs a transaction, and the
        //       tasklet body deliberately runs in none -- so it is made inside a unit of work of its
        //       own rather than bare. Called bare it would raise IllegalTransactionStateException on
        //       the first statement of the pass, which is the shape a locking read has when the
        //       boundary it assumed was moved out from under it.
        // WHY : Trade-offs: the lock is consequently released as soon as the position is read, where
        //       it once spanned the night. What still keeps two passes from posting the same rows is
        //       the chain's online-write lease and the monotonic advance below, and the reasoning for
        //       accepting that is recorded on this method.
        long startedAbove = Objects.requireNonNull(
                unitOfWork.execute(status -> this.watermark.consumedThroughForConsumer(
                        DailyFeedWatermarkService.DAILY_TRANSACTION_FEED)),
                "a unit of work must report the consumed position");
        long lastOrdinal = startedAbove;
        long processed = 0L;
        long rejected = 0L;

        // WHY : Trade-offs: an object store has no append, so a per-record write would either rewrite
        //       the whole object each time -- quadratic in the reject count -- or leave one object per
        //       reject, which is not the single fixed-length dataset app/jcl/POSTTRAN.jcl:34-38
        //       allocates. Buffering on the task's ephemeral disk costs one file and bounds memory by
        //       a single record, and it is the same route BackupTransactionsJob takes for the same
        //       reason.
        Path rejectStream = createRejectStreamFile();
        try {
            try (OutputStream sink = Files.newOutputStream(rejectStream)) {
                while (true) {
                    List<DailyTransaction> batch =
                            this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(
                                    lastOrdinal, Limit.of(BatchConfig.CHUNK_SIZE));
                    if (batch.isEmpty()) {
                        break;
                    }

                    for (DailyTransaction feedRecord : batch) {
                        processed++;
                        if (postOneRecord(feedRecord, sink, unitOfWork, perRecord, runId,
                                generationDate)) {
                            rejected++;
                        }
                        lastOrdinal = feedRecord.getIngestSeq();
                    }
                }
            } catch (IOException unwritable) {
                throw new IllegalStateException(
                        "could not write the reject stream to " + rejectStream, unwritable);
            }

            stageRejectStream(rejectStream, runId, generationDate, rejected);

            // WHY : Refactoring Rationale: no watermark write stands here, and one did. It advanced
            //       the position once, after staging, on the argument that a staging failure should
            //       re-present the whole window. That argument belonged to a pass-wide transaction: the
            //       boundary is now the RECORD, so a single advance at the end would sit behind a
            //       committed prefix of postings, and the re-presented window would post them a second
            //       time -- silently, since each one is individually valid. The advance therefore moved
            //       INTO each record's own transaction, and the reasoning is recorded there.
            // WHY : Trade-offs: what that costs is the re-presentation itself. A staging failure now
            //       leaves the records checkpointed and their reject DATASET unstaged, where before the
            //       window came back whole. It is acceptable because the reject ROWS are committed per
            //       record and carry the same three fields the 430-byte record is composed of -- the
            //       verbatim image, the reason code and its description -- so the dataset for a window
            //       is reconstructible from ledger.transaction_rejects, which was not true when the
            //       stream was the only record of what had been rejected.
        } finally {
            // WHY : Assumptions: the temporary file is removed on every path, including a failed
            //       stage, because a batch task's ephemeral disk is finite and a failed step is
            //       retried. Leaving it would let a sequence of retries fill the volume and turn a
            //       transient upload failure into a task that can no longer start.
            deleteQuietly(rejectStream);
        }

        reportCounters(processed, rejected);

        // WHY : Assumptions: the consumed WINDOW is logged as well as the two counters the reference
        //       prints, and it is logged separately so the reference's two lines stay byte-identical.
        //       It is what an operator needs when a pass posts fewer rows than the extract holds: the
        //       counters alone cannot distinguish "the feed was short" from "most of it had already
        //       been posted".
        LOG.info("event=batch.posting.window feed={} above={} through={} processed={}",
                DailyFeedWatermarkService.DAILY_TRANSACTION_FEED, startedAbove, lastOrdinal,
                processed);

        // WHY : Assumptions: the tier is decided by whether ANY record was rejected, not by how many,
        //       matching app/cbl/CBTRN02C.cbl:229 which tests the count against zero rather than
        //       against a threshold. A threshold would let a run with a few rejects report clean, and
        //       the reject stream would then be the only place the rejects appeared.
        return rejected > 0 ? BatchReturnCode.SOFT_WARN : BatchReturnCode.CLEAN;
    }

    /**
     * Writes the reference's two counter lines, byte for byte.
     *
     * <p>Assumptions: the two labels are NOT symmetrical and the asymmetry is deliberate in the
     * reference. {@code app/cbl/CBTRN02C.cbl:227} spells {@code 'TRANSACTIONS PROCESSED :'} with ONE
     * space before the colon and {@code app/cbl/CBTRN02C.cbl:228} spells
     * {@code 'TRANSACTIONS REJECTED  :'} with TWO, because {@code PROCESSED} is one character longer
     * than {@code REJECTED} and the extra space column-aligns the two colons. Both counters are
     * {@code PIC 9(09)} at {@code :185-186}, so each value is emitted zero-padded to nine digits.</p>
     *
     * <p>Assumptions: the posting golden directories hold {@code acctdat.expected},
     * {@code dalyrejs.expected}, {@code return_code.expected}, {@code tcatbal.expected} and
     * {@code tranfile.expected} -- and NO stdout golden -- so these two lines are not byte-compared
     * by the suite. That absence is explicitly NOT licence to reformat them. They remain a log-parity
     * obligation under the migration plan's rule T8, which carries user-visible strings across
     * verbatim, and an operator diffing a Java run against a COBOL run compares them by eye.</p>
     *
     * @param processed the number of feed records read, standing for {@code WS-TRANSACTION-COUNT}
     * @param rejected the number written to the reject stream, standing for {@code WS-REJECT-COUNT}
     */
    private static void reportCounters(long processed, long rejected) {
        LOG.info(String.format(Locale.ROOT, COUNTER_LINE, PROCESSED_LABEL, processed));
        LOG.info(String.format(Locale.ROOT, COUNTER_LINE, REJECTED_LABEL, rejected));
    }

    /**
     * Brackets one record's decisions and writes in a transaction, then appends its reject bytes.
     *
     * <p>Assumptions: the decisions themselves are NOT made here. {@code PostingRecordUnitOfWork}
     * owns them -- the validation, the three writes in reference order, the reject row and the
     * checkpoint -- and this method owns the boundary they are made inside and the append that has to
     * happen outside it. That split is what makes the unit drivable against a real database by a
     * repository test while leaving the boundary declared in exactly one file.</p>
     *
     * @param feedRecord the feed record to post or reject; must not be {@code null}
     * @param rejectStream the sink the 430-byte reject record is appended to; must not be
     *     {@code null}
     * @param unitOfWork the transaction boundary this record's decisions and writes are made inside;
     *     must not be {@code null}
     * @param perRecord the per-record unit of work that makes those decisions and issues those
     *     writes; must not be {@code null}
     * @param runId the orchestrator execution recorded against the advanced watermark; must not be
     *     {@code null}
     * @param generationDate the injected date recorded against the advanced watermark; must not be
     *     {@code null}
     * @return {@code true} when the record was rejected, {@code false} when it was posted
     * @throws IOException if the reject record cannot be appended to the stream
     * @throws IllegalStateException if the validation rule accepted a record without resolving both
     *     the cross-reference and the account
     */
    private boolean postOneRecord(DailyTransaction feedRecord, OutputStream rejectStream,
            TransactionTemplate unitOfWork, PostingRecordUnitOfWork perRecord, String runId,
            BusinessDate generationDate) throws IOException {

        // WHY : Assumptions: the transaction brackets the decisions AND the writes, not the writes
        //       alone, because the account the amount is accumulated into is read by the validation
        //       rule. Reading it in one transaction and writing it in another would reintroduce the
        //       read-modify-write window that the account's @Version column exists to close, and would
        //       do it invisibly -- the optimistic-lock check would still pass, having been performed
        //       against a row nobody held.
        // WHY : Assumptions: the 430-byte stream record is appended AFTER the commit rather than
        //       inside it, so a reject whose row failed to persist cannot appear in the dataset the
        //       golden masters compare. The file append is not transactional and cannot be made so;
        //       ordering it after the commit is what keeps the two in agreement in the direction that
        //       matters, since a committed row whose append then failed fails the whole step.
        Optional<byte[]> rejectRecord = unitOfWork.execute(
                status -> perRecord.applyOneRecord(feedRecord, runId, generationDate));

        if (Objects.requireNonNull(rejectRecord, "a unit of work must report its outcome").isEmpty()) {
            return false;
        }

        rejectStream.write(rejectRecord.get());
        return true;
    }

    /**
     * Creates the temporary file the reject stream is accumulated into.
     *
     * @return the newly created empty file, never {@code null}
     * @throws IllegalStateException if the file cannot be created
     */
    private static Path createRejectStreamFile() {
        try {
            return Files.createTempFile(STAGING_FILE_PREFIX, ".dat");
        } catch (IOException unavailable) {
            throw new IllegalStateException(
                    "could not create the temporary file the reject stream accumulates into",
                    unavailable);
        }
    }

    /**
     * Allocates the reject stream's generation, stages the assembled records into it, and ages out
     * the generations the retention rule no longer keeps.
     *
     * <p>Assumptions: the generation is allocated and staged UNCONDITIONALLY, including on a pass with
     * no rejects at all, and the empty case is not an oversight. {@code app/jcl/POSTTRAN.jcl:34-38}
     * declares the reject DD {@code DISP=(NEW,CATLG,DELETE)} against
     * {@code DSN=AWS.M2.CARDDEMO.DALYREJS(+1)}, so the reference creates a new generation on every run
     * whether or not it writes a record into it. The committed goldens confirm the consequence
     * directly: {@code dalyrejs.expected} is a ZERO-BYTE file in each of the five clean scenarios --
     * {@code happy_path}, {@code boundary_exact_limit}, {@code boundary_expiry_equal},
     * {@code empty_input} and {@code zero_balance} -- and 430 bytes in each of the four reject
     * scenarios. Skipping the allocation when the count is zero would leave a downstream reader facing
     * an absent generation where the reference leaves an empty one.</p>
     *
     * <p>Assumptions: the allocator MEMOISES per family and run, so this {@code (+1)} request returns
     * the identical generation if anything else in the same run asks for the reject family again. That
     * is what makes the reference's habit of naming one generation twice -- writing it under
     * {@code (+1)} and reading it back under {@code (0)} -- reproducible without allocating twice and
     * consuming the retention window at double rate.</p>
     *
     * <p>Assumptions: the retention rule is applied here rather than centrally.
     * {@code app/jcl/DALYREJS.jcl:25-27} defines the base with {@code LIMIT(5)} paired with an explicit
     * {@code SCRATCH}, so an aged-out generation is deleted rather than merely uncatalogued; the
     * allocator's scratch pair reproduces exactly that pairing.</p>
     *
     * @param rejectStream the assembled reject records; must not be {@code null}
     * @param runId the orchestrator execution the allocation belongs to; must not be {@code null}
     * @param generationDate the date the generation is partitioned under; must not be
     *     {@code null}
     * @param rejected the number of records the stream holds, reported for operator traceability
     */
    private void stageRejectStream(
            Path rejectStream, String runId, BusinessDate generationDate, long rejected) {

        DatasetGeneration target =
                this.generations.allocateNewGeneration(
                        DatasetFamily.DALYREJS, generationDate, runId);
        this.generations.stageDataset(target, REJECT_DATASET_OBJECT_NAME, rejectStream);

        int scratched = 0;
        for (DatasetGeneration agedOut : this.generations.generationsToScratch(
                DatasetFamily.DALYREJS)) {
            scratched += this.generations.scratchGeneration(agedOut);
        }

        LOG.info("event=batch.posting.rejects-staged generation={} records={} scratchedObjects={}"
                + " location={}", target.generationNumber(), rejected, scratched,
                this.generations.datasetUri(target));
    }

    /**
     * Removes a temporary file, reporting rather than raising when it cannot be removed.
     *
     * <p>Assumptions: a failed deletion is logged and swallowed rather than raised, because it happens
     * on paths that are already failing and re-raising there would replace the real failure with a
     * cleanup one. The consequence -- a file left behind -- is bounded by the container's lifetime.</p>
     *
     * @param file the file to remove; must not be {@code null}
     */
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException undeletable) {
            LOG.warn("event=batch.posting.temp-file-retained path={} reason={}",
                    file, undeletable.getClass().getName());
        }
    }

    /**
     * Reports the generation-dataset families this job stages into, so a reader need not infer them.
     *
     * @return the single family this job writes, never {@code null}
     */
    public static List<DatasetFamily> stagedFamilies() {
        return List.of(DatasetFamily.DALYREJS);
    }
}
