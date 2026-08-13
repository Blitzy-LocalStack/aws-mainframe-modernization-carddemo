package com.carddemo.batch.job;

import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DailyTransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.DailyFeedWatermarkService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Reports which of the day's feed records will not resolve, before any of them is posted.
 *
 * <h2>Purpose</h2>
 *
 * <p>This configuration declares state 3 of the nightly chain, {@code PreflightDailyTransactions},
 * and re-expresses {@code app/cbl/CBTRN01C.cbl}. It walks the day's feed in arrival order and, for
 * each record, resolves the card number through the cross-reference and then reads the account,
 * reporting each failure rather than acting on it. It writes nothing at all: the reference program's
 * procedure body between {@code app/cbl/CBTRN01C.cbl:164} and {@code :186} contains no
 * {@code WRITE}, {@code REWRITE} or {@code DELETE} verb, so the migrated pass is read-only by
 * construction and not merely by convention. Running it ahead of posting is what turns a feed
 * carrying unresolvable records from a run that silently produces a reject stream into a run an
 * operator has already been told about.</p>
 *
 * <h2>Why this job exists at all, given the reference never scheduled it</h2>
 *
 * <p>Assumptions: this is the one genuinely driverless program in the reference. No job among the
 * thirty-eight files in {@code app/jcl/} names {@code CBTRN01C} — the token does not occur in that
 * directory at all, not merely as an {@code EXEC PGM=} operand — and neither {@code app/proc/} nor
 * {@code app/scheduler/} references it, so only an integration test ever drove it. The reading taken
 * here is that the absence is a packaging gap in the reference rather than evidence the logic is
 * dead: the procedure body implements a complete pre-posting validation pass over the same two
 * access paths posting itself uses, which is not the shape of abandoned code. The migration plan
 * takes the same reading and assigns the program state 3 of the chain.</p>
 *
 * <p>Refactoring Rationale: what was wrong with the reference arrangement is reachability, not
 * logic. A validation pass reachable only from a test is a pass no operator can run and no chain can
 * depend on, so its findings existed only when someone went looking for them. Giving it a real
 * orchestrated state makes the pre-posting check reproducible on every business date, and makes its
 * findings arrive before the posting run they describe rather than after it. Its position in the
 * chain is therefore a decision this migration makes rather than one it reproduces, and it is placed
 * immediately before posting because a report on a feed that has already been posted describes a
 * file that no longer needs the report.</p>
 *
 * <h2>Why this job can never report the warn tier</h2>
 *
 * <p>Assumptions: the pass reports {@link BatchReturnCode#CLEAN} on every completing run, and the
 * warn tier is unreachable here by construction rather than by choice. {@code app/cbl/CBTRN01C.cbl}
 * declares a bare {@code PROCEDURE DIVISION.} at {@code :154} — a main program taking no parameters
 * — and contains no {@code RETURN-CODE} statement anywhere in its 494 lines, no reject stream and no
 * counters. It either completes and falls through {@code GOBACK} at {@code :197}, or it abends. The
 * migrated outcome is therefore the clean tier or a hard failure at or above eight, and never four.
 * That is not a local convention: {@code BatchRunSummary}'s compact constructor rejects a summary
 * pairing the warn tier with any job other than {@code post-transactions}, so a warn tier escaping
 * this pass would be refused by the transfer type before it could reach a ledger row.</p>
 *
 * <p>Assumptions: an unresolvable record must consequently NOT fail the chain and must NOT warn it.
 * Posting is where such a record becomes reject reason 100 or 101, and it is posting that carries
 * the only warn-tier gate in the tree. Pre-empting that decision here would either stop a chain the
 * reference lets run to completion, or report a tier twice for one record.</p>
 *
 * <h2>Divergence D-7: the reference performs one lookup past end of file</h2>
 *
 * <p>Refactoring Rationale: the loop at {@code app/cbl/CBTRN01C.cbl:164-186} guards too little, and
 * this pass deliberately does not reproduce the consequence. The outer test at {@code :165} opens a
 * block closing at {@code :185}; the inner test at {@code :167} closes with its own {@code END-IF}
 * at {@code :169} and therefore guards ONLY the record display at {@code :168}. The two
 * {@code MOVE} statements at {@code :170-171} and the cross-reference lookup at {@code :172} sit
 * outside that inner guard. So on the iteration where the read at {@code :166} reaches end of file
 * and sets the terminating flag, the program still moves a card number out of a record it did not
 * read and performs one further cross-reference lookup — and, when that resolves, one further
 * account read — against the stale contents of the previous record. The defect is one of guard
 * placement: the result of the read is not tested before its output is consumed.</p>
 *
 * <p>This pass implements the clean loop instead. It reads, and validates only when a record was
 * genuinely read; otherwise it terminates. A run over N records therefore performs exactly N
 * cross-reference lookups, not N+1.</p>
 *
 * <p>Trade-offs: the accepted cost is that a run's total read count differs from the reference's by
 * one lookup pair, so the two are not comparable on read counters. That cost is bounded to counters
 * and never reaches output data, because the extra pair produced no record and no file change — its
 * only visible effect in the reference was one duplicated diagnostic line, emitted when the last
 * real record happened to be unresolvable and its stale card number was looked up a second time.
 * What makes the divergence safe rather than merely defensible is that no byte comparison can
 * observe it: {@code tests/golden/} holds no {@code preflight} tree, so no golden master for
 * {@code CBTRN01C} exists to break. Reproducing the extra lookup would have preserved a read count
 * nothing measures at the price of shipping a known defect.</p>
 *
 * <p>This divergence is registered as {@code D-PREFLIGHT-LOOKUP-PAST-END-OF-FILE} in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which the migration plan designates as
 * the register of every documented behavioural divergence. A reader comparing this loop against the
 * reference should start there.</p>
 *
 * <p>Refactoring Rationale: this citation previously read "registered as D-7 in" that document, and
 * it resolved to the wrong entry. {@code D-7} there is the online header clock, which belongs to a
 * different program, and no entry covered this loop at all — so a reader following the citation
 * would have found an unrelated divergence and concluded this one was registered when it was not.
 * The heading above keeps the number, because {@code D-7} is correct as the class-local label within
 * this file's own documentation, the same way {@code ImportJob} numbers its own {@code D-8} and
 * {@code D-9}; the register-side identifier is a name precisely so that a class-local number can
 * never again collide with a register heading.</p>
 *
 * <h2>Why only three repositories are injected, where the reference opens six files</h2>
 *
 * <p>The reference opens six files at {@code app/cbl/CBTRN01C.cbl:157-162} — the daily feed, the
 * customer master, the cross-reference, the card master, the account master and the transaction
 * master — and closes all six at {@code :188-193}. Its procedure body reads only two of them. The
 * customer master, the card master and the transaction master are opened, held for the whole run and
 * closed again without a single read or write.</p>
 *
 * <p>Alternatives Considered: mirroring the open list, by injecting customer, card and transaction
 * repositories alongside the three this pass reads. Rejected on two independent grounds. It would
 * add three collaborators no statement touches, which the module's layering tests read as dead
 * coupling; and it would tell the next reader that this pass consults data it never consults,
 * turning a faithful transcription into a misleading one. Only the daily feed, the cross-reference
 * and the account master are injected.</p>
 *
 * <p>Assumptions: the discarded three carry no behaviour to preserve, because on z/OS an
 * {@code OPEN} is partly an allocation contract rather than purely a data-access one — it reserves
 * the dataset for the step and can fail the step outright if the dataset is unavailable, which makes
 * an unread {@code OPEN} a meaningful pre-flight assertion in its own right. The target has no
 * dataset to reserve: a connection is drawn from a pool per transaction and schema reachability is
 * granted to a database role once, at provisioning. The allocation half of the reference's open list
 * therefore has no analogue to preserve, and only the genuine data access survives translation.</p>
 *
 * <h2>Why no retry, and no resilience machinery of any kind, is configured here</h2>
 *
 * <p>Alternatives Considered: wrapping the two lookups, or the pass as a whole, in method-level retry
 * — which the framework this module builds on now offers in its core, so adopting it would have cost
 * one annotation and no new dependency. Rejected, because there is nothing here for a retry to
 * salvage. The pass writes nothing, so a failed attempt leaves no partial state that a second attempt
 * would either repair or duplicate; and its two failure modes are a missing cross-reference row and a
 * missing account row, neither of which is transient — retrying a lookup that correctly found nothing
 * returns the same nothing, more slowly. For a genuinely transient fault, such as a connection lost
 * mid-pass, the recovery that already exists is the right one and operates a level up: the step fails,
 * the durable ledger records the failure, and the orchestrator's own per-state retry re-runs the
 * state, at which point the ledger makes an already-completed step a no-op. Adding a retry here would
 * duplicate that tier while masking the failure the tier above needs to observe.</p>
 *
 * <p>Assumptions: no circuit breaker either. Both collaborators are repositories over the one
 * database this module already holds a bounded connection pool to, so there is no remote dependency
 * to trip a breaker on and no fallback a tripped breaker could route to — a preflight report with
 * fabricated results would be worse than none.</p>
 *
 * <h2>Baseline lineage: provenance only</h2>
 *
 * <p>The citations in this file are provenance. Nothing under {@code app/**} is read at run time and
 * nothing under it is altered by this migration — the reference implementation is the behavioural
 * oracle and stays byte-identical. Line numbers refer to the source as committed, and columns 73 to
 * 80 of a COBOL line carry a sequence field that is not part of the statement.</p>
 */
@Configuration
public class PreflightDailyTransactionsJob {

    /** The name this job registers under, taken from the shared token enumeration. */
    public static final String JOB_NAME = BatchJobName.PREFLIGHT_DAILY_TRANSACTIONS.token();

    /** The step name the durable ledger records this job's progress under. */
    public static final String STEP_NAME = JOB_NAME + BatchJobName.STEP_NAME_SUFFIX;

    /**
     * The text the reference displays on entry, from {@code app/cbl/CBTRN01C.cbl:156}.
     *
     * <p>Assumptions: user-visible text migrates character for character, so the literal is held
     * here rather than paraphrased into the surrounding structured event. An operator grepping a
     * container log for the string the reference printed finds it.</p>
     */
    public static final String START_BANNER = "START OF EXECUTION OF PROGRAM CBTRN01C";

    /** The text the reference displays on exit, from {@code app/cbl/CBTRN01C.cbl:195}. */
    public static final String END_BANNER = "END OF EXECUTION OF PROGRAM CBTRN01C";

    /**
     * The leading fragment of the account-missing diagnostic, from
     * {@code app/cbl/CBTRN01C.cbl:178}.
     *
     * <p>Assumptions: the trailing space is part of the literal. The reference writes
     * {@code DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'}, concatenating three pieces with no separator
     * of its own, so the spaces that separate the words live inside the literals and must survive
     * here.</p>
     */
    private static final String ACCOUNT_MISSING_PREFIX = "ACCOUNT ";

    /** The trailing fragment of the account-missing diagnostic, with its leading space. */
    private static final String ACCOUNT_MISSING_SUFFIX = " NOT FOUND";

    /**
     * The leading fragment of the card-unresolvable diagnostic, from
     * {@code app/cbl/CBTRN01C.cbl:181}, with its trailing space.
     */
    private static final String CARD_UNRESOLVED_PREFIX = "CARD NUMBER ";

    /**
     * The middle fragment of the card-unresolvable diagnostic, from
     * {@code app/cbl/CBTRN01C.cbl:182}.
     *
     * <p>Assumptions: the literal ends {@code ID-} with a hyphen and NO trailing space, and begins
     * with one leading space. The reference appends the transaction identifier immediately after the
     * hyphen, so the rendered line reads {@code ...TRANSACTION ID-0000000000000001}. Inserting the
     * space a reader might expect would change text the reference publishes.</p>
     */
    private static final String CARD_UNRESOLVED_SUFFIX =
            " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-";

    /**
     * The redaction written into the account-missing diagnostic in place of the identifier.
     *
     * <p>⚠️ Refactoring Rationale: this replaces a {@code "%011d"} format that interpolated the
     * account identifier itself, and the substitution is a security fix rather than a cosmetic one.
     * The line is emitted at {@code WARN} and therefore reaches durable log storage, so every account
     * whose master row was missing had its identifier published to every holder of log access -- and it
     * was published TWICE, because the same value was also carried as a structured
     * {@code accountId} field on the same statement. The identifier is now omitted from both, and what
     * replaces it here is a fixed run of the redaction character at the field's own declared width, so
     * the line keeps the shape a positional reader expects while carrying nothing that identifies an
     * account.</p>
     *
     * <p>Assumptions: the width is eleven because {@code ACCT-ID} is declared {@code PIC 9(11)} at
     * {@code app/cpy/CVACT01Y.cpy:5}, and a COBOL {@code DISPLAY} of a numeric-display field emits
     * every declared digit position -- so the reference's line is eleven characters wide at this
     * position whatever the identifier's magnitude, and the redaction preserves that.</p>
     *
     * <p>Alternatives Considered: masking to the last four digits, as the sibling card diagnostic in
     * this class does. Rejected because the card mask exists to keep a card RECOGNISABLE to an operator
     * who is looking at a specific card, whereas nothing in this pass needs an account to be
     * recognisable: the line carries the run-local ingestion ordinal, which locates the feed row from
     * which the account can be resolved through the cross-reference. A partial identifier would
     * therefore be residual disclosure bought for no diagnostic gain. Trade-offs: an operator reading
     * only this line cannot name the account, and must follow the ingestion ordinal into the feed to do
     * so; that indirection is the price of the line carrying no identifier at all.</p>
     *
     * <p>⚠️ Assumptions: this paragraph named the TRANSACTION IDENTIFIER as a second way to reach the
     * row, and no longer does, because that identifier is now redacted here too -- see
     * {@link #TRANSACTION_ID_REDACTION}. The ingestion ordinal is the whole of what locates the row, and
     * it is sufficient: it is unique on the feed table and is not a key of any financial record.</p>
     */
    private static final String ACCOUNT_ID_REDACTION = "***********";

    /**
     * The redaction written into the card-unresolved diagnostic in place of the transaction identifier.
     *
     * <p>⚠️ Refactoring Rationale: this replaces the identifier itself, which this class interpolated
     * verbatim on the stated ground that it "identifies nobody". That ground does not hold. The value is
     * {@code TRAN-ID}, the ledger's own primary key, so a holder of log access can join every line
     * carrying it to a posted financial record and to the card and amount on that record -- which is the
     * disclosure the card masking and the account redaction beside it already refuse. It reached durable
     * storage twice per unresolved record, once as a structured field and once inside this diagnostic.
     *
     * <p>Assumptions: the width is sixteen because {@code TRAN-ID} is declared {@code PIC X(16)} at
     * {@code app/cpy/CVTRA06Y.cpy}, and the reference's line is that wide at this position whatever the
     * identifier's content, so the redaction preserves the shape a positional reader expects.
     *
     * <p>Alternatives Considered: keeping a partial identifier, by analogy with the card mask.
     * Rejected for the reason recorded on {@link #ACCOUNT_ID_REDACTION}: a partial value is residual
     * disclosure bought for no diagnostic gain, because the run-local ingestion ordinal on the same line
     * already locates the feed row exactly, and the ledger key is reached from that row by a governed
     * query. Also considered: a keyed opaque token, which would give the log a stable non-reversible
     * handle for the record. Rejected as disproportionate here -- the tokeniser this repository already
     * carries requires key material from the deployment's secret store, and this module has none
     * provisioned, so adopting it would add a secret, a task-definition binding and a configuration
     * property to close a gap the ingestion ordinal already closes.
     *
     * <p>Assumptions: two DEFECT-ASSERTION messages in this class still name the identifier, and their
     * exclusion is deliberate rather than overlooked. Both are {@code IllegalStateException} texts for
     * states no row read from the feed table can be in -- an unhandled inspection outcome, and a feed row
     * with no ingestion ordinal -- so neither is reachable by operating the job, and the second one
     * CANNOT use the ordinal because the fault it reports is the ordinal's absence. A message that named
     * nothing would leave a defect untraceable to any record, which is a worse trade on a path that
     * indicates a programming error rather than a business condition.</p>
     */
    private static final String TRANSACTION_ID_REDACTION = "****************";

    /**
     * The number of trailing digits of a card number that may appear in a log line.
     *
     * <p>Assumptions: four is the width the migration plan's masking rule fixes, and it is the width
     * this module's own cross-reference entity already renders in its diagnostic form.</p>
     */
    private static final int CARD_NUMBER_SUFFIX_WIDTH = 4;

    /** The stand-in a card number too short to mask is rendered as. */
    private static final String CARD_NUMBER_UNAVAILABLE = "none";

    /** The operational log this job reports its findings through. */
    private static final Logger LOG = LoggerFactory.getLogger(PreflightDailyTransactionsJob.class);

    /** The feed the day's transactions are read from. */
    private final DailyTransactionRepository feed;

    /** The cross-reference a card number is resolved through. */
    private final CardXrefRepository crossReferences;

    /** The account master an account identifier is read from. */
    private final AccountRepository accounts;

    /** The durable step record that makes a re-run of a completed step a no-op. */
    private final BatchStepLedger ledgerOfSteps;

    /** The feed's consumed position, read so this report describes the window posting will consume. */
    private final DailyFeedWatermarkService watermark;

    /**
     * Builds the job over the repositories it reads.
     *
     * <p>Alternatives Considered: injecting {@code BatchRunRepository} directly, to perform the
     * {@code (runId, stepName)} idempotency lookup in this class. Rejected because the lookup is
     * only the first of four ledger interactions a guarded step needs — the others being opening a
     * row, marking it completed with the tier it graded, and marking it failed before re-raising —
     * and {@link BatchStepLedger} already owns all four for every job in this package. Reaching past
     * it to the repository would duplicate those transitions here and give two classes the power to
     * write the same row, with no mechanism keeping their notions of a completed step aligned.</p>
     *
     * @param feed the daily transaction feed this pass walks; must not be {@code null}
     * @param crossReferences the card cross-reference a card number is resolved through; must not be
     *     {@code null}
     * @param accounts the account master read for a card that resolved; must not be {@code null}
     * @param ledgerOfSteps the durable step ledger that makes a redriven completed step a no-op;
     *     must not be {@code null}
     * @param watermark the feed's consumed position, read so this pass reports on the SAME window
     *     the posting step will consume; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public PreflightDailyTransactionsJob(DailyTransactionRepository feed,
            CardXrefRepository crossReferences, AccountRepository accounts,
            BatchStepLedger ledgerOfSteps, DailyFeedWatermarkService watermark) {

        this.feed = Objects.requireNonNull(feed, "feed must not be null");
        this.crossReferences =
                Objects.requireNonNull(crossReferences, "crossReferences must not be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.ledgerOfSteps = Objects.requireNonNull(ledgerOfSteps, "ledgerOfSteps must not be null");
        this.watermark = Objects.requireNonNull(watermark, "watermark must not be null");
    }

    /**
     * Registers the job under the token the orchestrator names it by.
     *
     * <p>Assumptions: the registered name is taken from {@link #JOB_NAME}, which is derived from
     * {@code BatchJobName.PREFLIGHT_DAILY_TRANSACTIONS} rather than written out as a literal here.
     * {@code BatchApplication} resolves the job to run by comparing its process argument against
     * each registered bean's own name and imports no class from this package, so a name differing
     * from the enumeration token by one character compiles, deploys, and then fails inside the state
     * machine with an unresolved-job error. Deriving it means the two cannot drift.</p>
     *
     * @param jobRepository the framework's durable job repository the job and its step record
     *     through; must not be {@code null}
     * @param transactionManager the transaction manager the step's tasklet runs under; must not be
     *     {@code null}
     * @param validator the shared parameter validator requiring a business date and a run
     *     identifier; must not be {@code null}
     * @return the registered job, carrying one step, never {@code null}
     */
    @Bean
    // WHY : Refactoring Rationale: the factory method is named WITHOUT the "Job" suffix its class
    //       carries, and the difference is load-bearing rather than cosmetic. A `@Configuration`
    //       class registered by type takes a bean id from its own decapitalised class name, so a
    //       `@Bean` method spelled `preflightDailyTransactionsJob` inside
    //       `PreflightDailyTransactionsJob` claims the identifier the class itself already holds --
    //       and Spring refuses the context with a BeanDefinitionOverrideException rather than
    //       choosing between them. Dropping the suffix gives the two definitions distinct
    //       identifiers.
    // WHY : Assumptions: nothing selects this bean by its identifier. The command contract iterates
    //       the `Job` beans and compares `getName()` against its argument, and `getName()` comes
    //       from JOB_NAME above, so the identifier is free to change and the job's published token
    //       is not.
    public Job preflightDailyTransactions(JobRepository jobRepository,
            PlatformTransactionManager transactionManager, JobParametersValidator validator) {

        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(validator)
                .start(new StepBuilder(STEP_NAME, jobRepository)
                        .tasklet(this::runStep, transactionManager)
                        .build())
                .build();
    }

    /**
     * Runs the whole reporting pass once, under the durable step record.
     *
     * <p>Assumptions: the run identifier is read from the job parameters rather than from an
     * environment variable, so a redrive of the same state machine execution presents the same
     * identifier and the ledger recognises the step as already completed. The pass body is handed to
     * the ledger rather than called directly, which is what makes a redriven completed step a no-op
     * without this method testing for that case itself.</p>
     *
     * <p>Assumptions: no exception is caught here, and the omission is deliberate. The reference
     * reacts to an unreadable feed at {@code app/cbl/CBTRN01C.cbl:219} by displaying
     * {@code 'ERROR READING DAILY TRANSACTION FILE'} and falling through to
     * {@code Z-ABEND-PROGRAM} at {@code :222}, which abends the step; the migrated analogue is a
     * repository call raising, which {@link BatchStepLedger} already marks failed at the hard-failure
     * tier and re-raises so the framework fails the step and the orchestrator's catch handler routes
     * to failure notification. Catching here would swallow exactly the signal that chain needs, and
     * would turn an unreadable feed into a run that reported a clean pass over zero records.</p>
     *
     * @param contribution the framework's handle for reporting this step's exit status; unused,
     *     because this pass reports through the ledger and the step's own completion rather than by
     *     adjusting a contribution, and it is present only to satisfy the tasklet signature
     * @param context the chunk context carrying the job parameters the run identifier is read from;
     *     must not be {@code null}
     * @return {@link RepeatStatus#FINISHED} always, because the pass walks the whole feed in one
     *     invocation rather than being re-entered per chunk
     */
    private RepeatStatus runStep(StepContribution contribution, ChunkContext context) {
        this.ledgerOfSteps.runStep(BatchConfig.runIdOf(context), STEP_NAME,
                BatchJobName.PREFLIGHT_DAILY_TRANSACTIONS, this::reportOnEveryRecord);
        return RepeatStatus.FINISHED;
    }

    /**
     * Walks the feed in arrival order and reports every record whose card or account does not
     * resolve.
     *
     * <p>Alternatives Considered: consuming the feed repository's cursor {@code Stream} instead of
     * its keyset finder. Rejected, and the reason is the shape of the guarantee each one gives. A
     * cursor held open across the whole pass pins its connection for the pass's duration, which is
     * why this module's data source configuration enables leak detection at all; the keyset finder
     * bounds every query to one batch and holds nothing between them, so the walk cannot be the
     * thing that exhausts the pool. Because no {@code Stream} is opened here, there is no cursor for
     * a try-with-resources block to close — the absence of one below is a consequence of this
     * choice, not an oversight of it.</p>
     *
     * <p>Assumptions: the walk is keyed on the feed's ingestion ordinal, which is the entity's
     * mapped identity and preserves the order the reference's sequential read covers the dataset in
     * — the feed is declared {@code ORGANIZATION IS SEQUENTIAL} at
     * {@code app/cbl/CBTRN01C.cbl:30}. It is deliberately NOT keyed on the processing timestamp,
     * which is null on every unposted row and would order the walk arbitrarily where it did not
     * simply exclude rows.</p>
     *
     * <p>Assumptions: the loop terminates on an empty batch rather than on a batch shorter than the
     * limit. A short batch is not a reliable end-of-feed signal, and testing for emptiness costs one
     * extra query per pass while removing the need to reason about that distinction at all.</p>
     *
     * @return {@link BatchReturnCode#CLEAN} always, for the reason recorded on this class: the
     *     reference publishes no return code, so a completing pass has no other outcome to report
     *     and a failing one raises rather than returning
     * @throws IllegalStateException in either of two cases that no row read from the feed table can
     *     produce: a record inspection yielding an outcome this walk has no branch for, which
     *     {@link RecordOutcome}'s three constants presently make unreachable and which is guarded so
     *     that adding a fourth constant without accounting for it here fails loudly on the first
     *     record rather than silently omitting that record from every total; or a record carrying no
     *     ingestion ordinal, as {@link #continuationOrdinalOf(DailyTransaction)} describes
     */
    private BatchReturnCode reportOnEveryRecord() {
        LOG.info("event=batch.preflight.started banner=\"{}\"", START_BANNER);

        // WHY : Refactoring Rationale: this pass starts at the feed's stored consumed position and
        //       no longer at the first row, so it reports on the SAME window the posting step will
        //       consume. The reference's preflight and its posting job read one dataset that was
        //       replaced between runs (app/jcl/POSTTRAN.jcl:30-31), so both necessarily saw the same
        //       records; the target's feed accumulates, so a preflight starting at the beginning
        //       described every night ever loaded while posting described one -- and the counts an
        //       operator reconciles them by would not have matched for a correct run.
        // WHY : Assumptions: the UNLOCKED read is used here, and posting uses the locking one. This
        //       pass writes nothing and advances nothing, so it must not hold a row lock that the
        //       step which does consume would then wait on. It also means this window can be one
        //       night stale if a posting pass overlapped, which is acceptable for a report and is
        //       impossible in the chain, where this state runs before the posting state.
        long lastOrdinal = this.watermark.consumedThroughForReader(
                DailyFeedWatermarkService.DAILY_TRANSACTION_FEED);
        long startedAbove = lastOrdinal;
        long read = 0L;
        long unresolvedCards = 0L;
        long unresolvedAccounts = 0L;

        while (true) {
            List<DailyTransaction> batch = this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(
                    lastOrdinal, Limit.of(BatchConfig.CHUNK_SIZE));
            if (batch.isEmpty()) {
                break;
            }

            // WHY : Refactoring Rationale: every record in this batch was genuinely read, so each one
            //       is inspected -- which is the whole of divergence D-7. The reference reaches its
            //       inspection through a guard that covers only its record display
            //       (app/cbl/CBTRN01C.cbl:167-169), so it inspects once more after end of file using
            //       a card number left over from the previous record. Driving the inspection from the
            //       batch the query returned makes that state unrepresentable: there is no iteration
            //       here in which no record was read.
            for (DailyTransaction feedRecord : batch) {
                read++;
                switch (inspect(feedRecord)) {
                    case CARD_UNRESOLVED -> unresolvedCards++;
                    case ACCOUNT_UNRESOLVED -> unresolvedAccounts++;
                    case RESOLVED -> {
                        // WHY : Assumptions: a record that resolved needs no counter of its own,
                        //       because the reference reports nothing for it -- the successful path at
                        //       app/cbl/CBTRN01C.cbl:173-179 displays only when the account read
                        //       fails. The resolved total is the read total less the two unresolved
                        //       totals, all three of which are reported below.
                    }
                    default -> throw new IllegalStateException(
                            "unhandled preflight inspection outcome for transaction "
                                    + feedRecord.getTransactionId());
                }
                lastOrdinal = continuationOrdinalOf(feedRecord);
            }
        }

        LOG.info("event=batch.preflight.completed read={} unresolvedCards={} unresolvedAccounts={}"
                + " banner=\"{}\"", read, unresolvedCards, unresolvedAccounts, END_BANNER);
        // WHY : Assumptions: the WINDOW is logged on its own line rather than added to the line
        //       above, so the reference's reported totals keep their own event and this pass's
        //       target-only context keeps its. An operator comparing this report against the posting
        //       step's counters needs both windows to see that the two describe the same rows.
        LOG.info("event=batch.preflight.window feed={} above={} through={} read={}",
                DailyFeedWatermarkService.DAILY_TRANSACTION_FEED, startedAbove, lastOrdinal, read);
        return BatchReturnCode.CLEAN;
    }

    /**
     * Reads the ordinal the next batch continues strictly above, refusing a record that carries none.
     *
     * <p>Assumptions: the feed entity documents its ingestion ordinal as non-null on every row read
     * back, because the column is the table's generated primary key and the walk only ever sees rows
     * a query returned. This method asserts that contract at the one point the walk depends on it
     * rather than trusting it silently.</p>
     *
     * <p>Refactoring Rationale: the ordinal was previously assigned straight into the primitive
     * accumulator, which auto-unboxes. A record carrying no ordinal — an instance built through the
     * entity's public constructor, which leaves the generated identity unset, rather than loaded from
     * the feed table — therefore aborted the pass with a bare {@code NullPointerException} naming
     * only the accessor. That failure surfaced part-way through a batch, after some records had
     * already been reported and others never would be, so a log showed a partial pass with no
     * indication that it was partial. Testing the value first turns the same fault into a message
     * naming the offending record and the contract it broke, and it still stops the pass rather than
     * skipping the record — a skipped record would silently drop it from the totals and, because the
     * walk continues strictly above the last ordinal it accepted, would re-read the same batch
     * forever.</p>
     *
     * @param feedRecord the record most recently inspected in the current batch; must not be
     *     {@code null}
     * @return the ordinal the next query continues strictly above
     * @throws IllegalStateException if the record carries no ingestion ordinal, which no row read
     *     from the feed table can do
     */
    private static long continuationOrdinalOf(DailyTransaction feedRecord) {
        Long ordinal = feedRecord.getIngestSeq();
        if (ordinal == null) {
            throw new IllegalStateException("the feed row for transaction "
                    + feedRecord.getTransactionId() + " carries no ingestion ordinal, so the keyset"
                    + " walk cannot continue past it; every row read from the feed table has one, so"
                    + " this record did not come from it");
        }
        return ordinal;
    }

    /**
     * Inspects one feed record against the cross-reference and, only if that resolved, the account
     * master.
     *
     * <p>Assumptions: the account master is consulted ONLY for a record whose card resolved, which is
     * the reference's own short-circuit at {@code app/cbl/CBTRN01C.cbl:173}: the account read at
     * {@code :176} sits inside the block that line opens, and the unresolvable-card branch at
     * {@code :180-184} is its alternative. Reading the account for a record with no cross-reference
     * row would need an account identifier no row supplied.</p>
     *
     * <p>Alternatives Considered: also emitting the subordinate paragraphs' own diagnostics,
     * {@code 'INVALID CARD NUMBER FOR XREF'} at {@code app/cbl/CBTRN01C.cbl:232} and
     * {@code 'INVALID ACCOUNT NUMBER FOUND'} at {@code :246}. Rejected because each describes the
     * same condition as the caller-level diagnostic that follows it, so carrying both would log every
     * unresolved record twice — and the caller-level text is the more useful of each pair, being the
     * one that names the record. Their omission is a deduplication of two renderings of one event,
     * not a dropped condition; both conditions are still reported, once each.</p>
     *
     * <p>Trade-offs: the reference displays the entire 350-byte feed record for every record read at
     * {@code app/cbl/CBTRN01C.cbl:168}, and that is carried here at {@code DEBUG} rather than at
     * {@code INFO}. Two reasons, and the first is disqualifying on its own: the record contains a
     * full primary account number, which the migration plan's data-exposure rule forbids in an
     * operational log, so the entity's own diagnostic form is logged instead — it renders the
     * ingestion ordinal, transaction identifier, type, category and originating timestamp, and
     * neither the card number nor the amount. The second is volume: one record per feed row at
     * {@code INFO} would bury the findings this pass exists to report. The accepted cost is that the
     * per-record trace is off by default and an operator must raise the level for this class to see
     * it.</p>
     *
     * @param feedRecord the record to inspect, already known to have been read from the feed; must
     *     not be {@code null}
     * @return which of the three mutually exclusive paths the record took, never {@code null}
     */
    private RecordOutcome inspect(DailyTransaction feedRecord) {
        // WHY : ⚠️ Refactoring Rationale: this renders three CHOSEN members rather than the entity, and
        //       it rendered the entity. That type's diagnostic form deliberately omits the card number
        //       and the amount, which is why it was used here, but it names the TRANSACTION IDENTIFIER
        //       -- the ledger's primary key -- so raising this class to DEBUG published a ledger key for
        //       every record read. A level that is off by default is not a control: an operator raising
        //       it to diagnose one record writes them all. Alternatives Considered: narrowing the
        //       entity's own rendering, which is where the value originates. Rejected because this is
        //       its ONLY logging consumer, its rendering is a documented contract reasoned about in its
        //       own charter, and the ordinal it also carries already identifies the row here.
        LOG.debug("event=batch.preflight.record ingestSeq={} typeCd={} categoryCd={} origTs={}",
                feedRecord.getIngestSeq(), feedRecord.getTypeCd(), feedRecord.getCategoryCd(),
                feedRecord.getOrigTs());

        Optional<CardXref> resolved = this.crossReferences.findByCardNum(feedRecord.getCardNum());
        if (resolved.isEmpty()) {
            // WHY : ⚠️ Refactoring Rationale: the structured field is the run-local ingestion ORDINAL
            //       and was the transaction identifier. Both name the same feed row; only one of them is
            //       also the ledger's primary key, and that one reached durable log storage for every
            //       unresolved record. See TRANSACTION_ID_REDACTION for the full reasoning, including why
            //       the identifier inside the verbatim diagnostic is redacted rather than kept.
            LOG.warn("event=batch.preflight.card-unresolved ingestSeq={} diagnostic=\"{}\"",
                    feedRecord.getIngestSeq(), cardUnresolvedDiagnostic(feedRecord));
            return RecordOutcome.CARD_UNRESOLVED;
        }

        Long accountId = resolved.get().getAccountId();
        if (this.accounts.findByAccountId(accountId).isEmpty()) {
            // WHY : ⚠️ Refactoring Rationale: the account identifier is gone from this statement. It
            //       used to appear twice on one WARN line -- once as a structured accountId field and
            //       again interpolated into the diagnostic -- so a durable log recorded, for every
            //       account whose master row was missing, an identifier that any holder of log access
            //       could read. What replaces it is identity that locates the record without naming the
            //       account: the run-local ingestion ordinal, which is the feed table's own row
            //       position. It is enough to reach the row and resolve the account through the
            //       cross-reference, which is where that lookup belongs. See ACCOUNT_ID_REDACTION for
            //       why a partial identifier was rejected as well as a full one.
            // WHY : ⚠️ Refactoring Rationale: this statement also carried the TRANSACTION IDENTIFIER,
            //       and the paragraph above described it as identity that "identifies nobody". It is the
            //       ledger's primary key, so it identifies a posted financial record and, through it, a
            //       card and an amount. It is gone from this line; the ordinal that remains locates the
            //       same row without keying anything outside the feed.
            LOG.warn("event=batch.preflight.account-unresolved ingestSeq={}"
                    + " diagnostic=\"{}\"",
                    feedRecord.getIngestSeq(), accountMissingDiagnostic());
            return RecordOutcome.ACCOUNT_UNRESOLVED;
        }
        return RecordOutcome.RESOLVED;
    }

    /**
     * Renders the reference's card-unresolvable diagnostic for one record, with the card number
     * masked.
     *
     * <p>Trade-offs: the template is verbatim and the interpolated card number is not. The reference
     * writes the full sixteen-digit value at {@code app/cbl/CBTRN01C.cbl:181}, and reproducing that
     * would put a primary account number into a log the migration plan requires to carry at most its
     * last four digits. The template is what an operator greps for and the masked suffix is enough to
     * recognise a card. This is the one place the verbatim-text rule is knowingly qualified, and it is
     * qualified only in the interpolated values.</p>
     *
     * <p>⚠️ Refactoring Rationale: the transaction identifier this line ends with is REDACTED too, and
     * was interpolated in full on the ground that it "identifies nobody". It is the ledger's primary
     * key, so it identifies a posted financial record; the ingestion ordinal on the same statement is
     * what locates the record in the feed. See {@link #TRANSACTION_ID_REDACTION}.</p>
     *
     * @param feedRecord the record whose card did not resolve; must not be {@code null}
     * @return the diagnostic line, never {@code null}
     */
    private static String cardUnresolvedDiagnostic(DailyTransaction feedRecord) {
        return CARD_UNRESOLVED_PREFIX + maskedCardNumber(feedRecord.getCardNum())
                + CARD_UNRESOLVED_SUFFIX + TRANSACTION_ID_REDACTION;
    }

    /**
     * Renders the reference's account-missing diagnostic with the identifier redacted.
     *
     * <p>Refactoring Rationale: this method took the identifier as a parameter and interpolated it.
     * It now takes nothing, which is deliberate: a renderer that cannot be handed the value cannot
     * publish it, so the redaction is a property of the signature rather than a rule a future edit
     * could relax. The full reasoning, including the rejected last-four masking, is on
     * {@link #ACCOUNT_ID_REDACTION}.</p>
     *
     * @return the diagnostic line, with the identifier position occupied by the redaction at its
     *     declared eleven-character width, never {@code null}
     */
    private static String accountMissingDiagnostic() {
        return ACCOUNT_MISSING_PREFIX + ACCOUNT_ID_REDACTION + ACCOUNT_MISSING_SUFFIX;
    }

    /**
     * Renders a card number as its last four digits, so a log line never carries a full one.
     *
     * @param cardNumber the card number to mask; may be {@code null}
     * @return the last four characters, or a fixed stand-in when the value is absent or shorter than
     *     four characters, never {@code null}
     */
    private static String maskedCardNumber(String cardNumber) {
        return cardNumber == null || cardNumber.length() < CARD_NUMBER_SUFFIX_WIDTH
                ? CARD_NUMBER_UNAVAILABLE
                : cardNumber.substring(cardNumber.length() - CARD_NUMBER_SUFFIX_WIDTH);
    }

    /**
     * The three mutually exclusive paths one feed record can take through the pass.
     *
     * <p>Alternatives Considered: returning a boolean, or returning the resolved cross-reference row
     * wrapped in an optional. Both were rejected because the pass has to distinguish three outcomes
     * and report a separate total for two of them, and a boolean collapses the two failures into one
     * — which is precisely the distinction the reference draws, having a separate diagnostic and a
     * separate branch for each at {@code app/cbl/CBTRN01C.cbl:177-179} and {@code :180-184}. An
     * optional row would carry the successful case's data the caller does not use, and would still
     * leave the two empty cases indistinguishable.</p>
     *
     * <p>Assumptions: this enumeration is deliberately private and describes an internal control
     * decision only. It is not a completion tier, is never persisted, is never reported as a process
     * exit status, and must not be confused with {@link BatchReturnCode} — which this pass reports
     * exactly one value of, whatever mix of these outcomes it saw.</p>
     */
    private enum RecordOutcome {

        /** The card resolved and the account master held the account it named. */
        RESOLVED,

        /** The cross-reference held no row for the record's card number. */
        CARD_UNRESOLVED,

        /** The card resolved, but the account master held no row for the account it named. */
        ACCOUNT_UNRESOLVED
    }
}
