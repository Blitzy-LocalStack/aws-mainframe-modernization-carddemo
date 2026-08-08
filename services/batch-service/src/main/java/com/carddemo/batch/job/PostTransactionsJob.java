package com.carddemo.batch.job;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.PostingValidationResult;
import com.carddemo.batch.mapper.DailyTransactionMapper;
import com.carddemo.batch.mapper.TransactionRejectRecordMapper;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DailyTransactionRepository;
import com.carddemo.batch.repository.TransactionRejectRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.CategoryBalanceService;
import com.carddemo.batch.service.PostingValidationService;
import com.carddemo.common.money.Money;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
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

/**
 * Posts the day's transaction feed to the ledger, the account master and the category balances.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this job is state four of the nightly chain and re-expresses
 * {@code app/cbl/CBTRN02C.cbl}, driven by {@code app/jcl/POSTTRAN.jcl:23}
 * ({@code EXEC PGM=CBTRN02C}). It walks the daily transaction feed in key order and, for each record,
 * either posts it or writes it to the reject stream. The order of its four decisions is the reference's
 * own: resolve the card, read the account, validate, then post or reject.</p>
 *
 * <p>Assumptions: every business RULE this job applies is delegated. Which conditions a record fails is
 * {@code PostingValidationService}, which reproduces the short-circuit chain at
 * {@code app/cbl/CBTRN02C.cbl:370-420}; whether a category balance is created or updated is
 * {@code CategoryBalanceService}, which reproduces the two arms at
 * {@code app/cbl/CBTRN02C.cbl:500-539}; how a feed record becomes a posted record is
 * {@code DailyTransactionMapper}; how a rejected record becomes a reject row is
 * {@code TransactionRejectRecordMapper}. What this class owns is the ORDER those rules are applied in and
 * the unit of work they commit within, which is precisely what a JCL step owned.</p>
 *
 * <h2>The exit-status contract, and why this is the only job that can warn</h2>
 *
 * <p>Assumptions: {@code app/cbl/CBTRN02C.cbl:229} reads {@code IF WS-REJECT-COUNT > 0} and
 * {@code app/cbl/CBTRN02C.cbl:230} reads {@code MOVE 4 TO RETURN-CODE}. That is the only
 * {@code RETURN-CODE} statement in any of the twelve batch programs, so this job is the only one in the
 * package that can emit the warn tier. A reject is a correct outcome rather than a failure: the record is
 * written to the reject stream and the chain continues, which is why the tier is a warn and not a
 * failure. The package charter states the three tiers once and this class does not restate them.</p>
 *
 * <h2>One transaction for the step, not one per record</h2>
 *
 * <p>Refactoring Rationale: the reference has no transaction at all. Its files are defined
 * {@code RECOVERY(NONE)} with {@code JOURNAL(NO)} in {@code app/csd/CARDDEMO.CSD}, so each write is
 * immediate and unrecoverable and a failure halfway through leaves the masters half-posted with no record
 * of where it stopped. The migrated step commits once, so a failure discards the whole attempt and the
 * durable step ledger plus the orchestrator's redrive re-runs it from a known state. That is strictly
 * safer than the reference rather than a departure from a guarantee it made, and it is what makes the
 * migration plan's requirement that the three-write posting unit of work stay a single commit -- its
 * section 0.4.1.3 -- true for the step as well as for the record.</p>
 *
 * <p>Trade-offs: a single transaction over an unbounded feed would accumulate every entity it touched in
 * the persistence context and exhaust the heap, so the feed is read in bounded batches and the context is
 * flushed and cleared between them. The cost is that a batch's changes are written to the database before
 * the step commits, which is invisible to any reader because nothing reads these tables during the batch
 * window -- state one of the chain quiesces online writes and no other state runs concurrently -- and the
 * benefit is that memory is bounded by the batch size rather than by the feed's length.</p>
 */
@Configuration
public class PostTransactionsJob {

    /**
     * The name this job registers under, which is an orchestration contract rather than a label.
     *
     * <p>Assumptions: the value is taken from the shared token enumeration rather than spelled, because
     * {@code BatchApplication} resolves the job to run by matching the process argument against a
     * registered bean's name. A name that differed by one character would compile and deploy and then
     * fail inside the state machine with an unresolved-job error.</p>
     */
    public static final String JOB_NAME = BatchJobName.POST_TRANSACTIONS.token();

    /** The step name the durable ledger records this job's progress under. */
    public static final String STEP_NAME = "post-transactions-step";

    /** The ordinal a walk of the feed starts strictly above, so the first record is included. */
    private static final long BEFORE_FIRST_ORDINAL = 0L;

    /** The operational log this job reports its record counts through. */
    private static final Logger LOG = LoggerFactory.getLogger(PostTransactionsJob.class);

    /** The feed the day's transactions are read from. */
    private final DailyTransactionRepository feed;

    /** The cross-reference a card number is resolved to an account through. */
    private final CardXrefRepository crossReferences;

    /** The account master the posted amounts are accumulated into. */
    private final AccountRepository accounts;

    /** The ledger the posted transactions are written to. */
    private final TransactionRepository ledger;

    /** The reject stream a failed record is written to. */
    private final TransactionRejectRepository rejects;

    /** The rule deciding which conditions a record fails. */
    private final PostingValidationService validation;

    /** The rule deciding whether a category balance is created or updated. */
    private final CategoryBalanceService categoryBalances;

    /** The durable step record that makes a re-run of a completed step a no-op. */
    private final BatchStepLedger ledgerOfSteps;

    /** The clock the posted records' processing stamp is read from. */
    private final Clock clock;

    /** The persistence context, flushed and cleared between batches to bound memory. */
    private final EntityManager entityManager;

    /**
     * Builds the job over the rules and repositories it composes.
     *
     * @param feed the daily transaction feed; must not be {@code null}
     * @param crossReferences the card cross-reference; must not be {@code null}
     * @param accounts the account master; must not be {@code null}
     * @param ledger the posted-transaction ledger; must not be {@code null}
     * @param rejects the reject stream; must not be {@code null}
     * @param validation the posting validation rule; must not be {@code null}
     * @param categoryBalances the category-balance rule; must not be {@code null}
     * @param ledgerOfSteps the durable step ledger; must not be {@code null}
     * @param clock the clock the processing stamp is read from; must not be {@code null}
     * @param entityManager the persistence context; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public PostTransactionsJob(DailyTransactionRepository feed, CardXrefRepository crossReferences,
            AccountRepository accounts, TransactionRepository ledger,
            TransactionRejectRepository rejects, PostingValidationService validation,
            CategoryBalanceService categoryBalances, BatchStepLedger ledgerOfSteps, Clock clock,
            EntityManager entityManager) {

        this.feed = Objects.requireNonNull(feed, "feed must not be null");
        this.crossReferences =
                Objects.requireNonNull(crossReferences, "crossReferences must not be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.rejects = Objects.requireNonNull(rejects, "rejects must not be null");
        this.validation = Objects.requireNonNull(validation, "validation must not be null");
        this.categoryBalances =
                Objects.requireNonNull(categoryBalances, "categoryBalances must not be null");
        this.ledgerOfSteps = Objects.requireNonNull(ledgerOfSteps, "ledgerOfSteps must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    /**
     * Registers the job under the token the orchestrator names it by.
     *
     * @param jobRepository the framework's durable job repository; must not be {@code null}
     * @param transactionManager the transaction manager the step commits through; must not be
     *     {@code null}
     * @param validator the shared parameter validator every job in this module is built with; must not be
     *     {@code null}
     * @return the registered job, never {@code null}
     */
    @Bean
    // WHY : Refactoring Rationale: the factory method is named WITHOUT the "Job" suffix its class
    //       carries, and the difference is load-bearing rather than cosmetic. A `@Configuration` class
    //       registered by type takes a bean id from its own decapitalised class name, so a `@Bean`
    //       method spelled `postTransactionsJob` inside `PostTransactionsJob` claims the identifier the class
    //       itself already holds -- and Spring refuses the context with a
    //       BeanDefinitionOverrideException rather than choosing between them. Dropping the suffix
    //       gives the two definitions distinct identifiers.
    // WHY : Assumptions: nothing selects this bean by its identifier. The command contract iterates
    //       the `Job` beans and compares `getName()` against its argument, and `getName()` comes from
    //       JOB_NAME below, so the identifier is free to change and the job's published token is not.
    public Job postTransactions(JobRepository jobRepository,
            PlatformTransactionManager transactionManager, JobParametersValidator validator) {

        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(validator)
                .start(new StepBuilder(STEP_NAME, jobRepository)
                        .tasklet(this::runStep, transactionManager)
                        .build())
                .build();
    }

    /**
     * Runs the whole posting pass once, under the durable step record.
     *
     * @param contribution the framework's handle for reporting this step's exit status; must not be
     *     {@code null}
     * @param context the chunk context carrying the job parameters; must not be {@code null}
     * @return {@link RepeatStatus#FINISHED} always, because the pass completes within one invocation
     */
    private RepeatStatus runStep(StepContribution contribution, ChunkContext context) {
        String runId = BatchConfig.runIdOf(context);

        BatchStepLedger.StepOutcome outcome =
                this.ledgerOfSteps.runStep(runId, STEP_NAME, this::postEveryFeedRecord);

        // WHY : Assumptions: the exit status is set from the ledger's recorded return code rather than
        //       from the counters this invocation produced, so a redriven step that the ledger reports as
        //       already complete reports the tier the ORIGINAL attempt reached. Recomputing it would
        //       report a clean run for a step that had legitimately warned, and the orchestrator's
        //       downstream choice reads that tier.
        if (outcome.returnCode() == BatchReturnCode.SOFT_WARN) {
            contribution.setExitStatus(
                    new ExitStatus(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS));
        }

        return RepeatStatus.FINISHED;
    }

    /**
     * Walks the feed in key order, posting or rejecting each record, and reports the resulting tier.
     *
     * <p>Assumptions: the walk is keyset-paginated on the feed's ingest ordinal rather than offset-paged.
     * The migration plan's section 0.7.4 requires it, and the reason is concrete: an offset page recounts
     * the rows before it on every request, so a row inserted or removed during the walk shifts the window
     * and a record is skipped or seen twice.</p>
     *
     * @return the tier the pass reached -- warn when any record was rejected, clean otherwise, never
     *     {@code null}
     */
    private BatchReturnCode postEveryFeedRecord() {
        long lastOrdinal = BEFORE_FIRST_ORDINAL;
        long processed = 0L;
        long rejected = 0L;

        while (true) {
            List<DailyTransaction> batch = this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(
                    lastOrdinal, Limit.of(BatchConfig.CHUNK_SIZE));
            if (batch.isEmpty()) {
                break;
            }

            for (DailyTransaction feedRecord : batch) {
                processed++;
                if (postOneRecord(feedRecord)) {
                    rejected++;
                }
                lastOrdinal = feedRecord.getIngestSeq();
            }

            // WHY : Assumptions: the context is flushed before it is cleared, because clearing detaches
            //       every managed entity and a pending change on a detached entity is simply lost. The
            //       pair together is what bounds memory to one batch without discarding work.
            this.entityManager.flush();
            this.entityManager.clear();
        }

        LOG.info("event=batch.posting.completed processed={} rejected={}", processed, rejected);

        // WHY : Assumptions: the tier is decided by whether ANY record was rejected, not by how many,
        //       matching app/cbl/CBTRN02C.cbl:229 which tests the count against zero rather than against
        //       a threshold. A threshold would let a run with a few rejects report clean, and the reject
        //       stream would then be the only place the rejects appeared.
        return rejected > 0 ? BatchReturnCode.SOFT_WARN : BatchReturnCode.CLEAN;
    }

    /**
     * Applies the four decisions to one feed record and writes whichever outcome they reach.
     *
     * @param feedRecord the feed record to post or reject; must not be {@code null}
     * @return {@code true} when the record was rejected, {@code false} when it was posted
     */
    private boolean postOneRecord(DailyTransaction feedRecord) {
        Optional<CardXref> crossReference =
                this.crossReferences.findByCardNum(feedRecord.getCardNum());

        // WHY : Assumptions: the account is looked up only when the cross-reference resolved, because
        //       app/cbl/CBTRN02C.cbl:372 gates the account read on the not-invalid-key branch of the
        //       cross-reference read. Reading the account regardless would work and would still reject
        //       correctly, but it would issue a query the reference never issues and would make the
        //       reject reason for an unresolvable card depend on whether some account happened to exist.
        Optional<Account> account = crossReference
                .flatMap(resolved -> this.accounts.findByAccountId(resolved.getAccountId()));

        PostingValidationResult outcome =
                this.validation.validate(feedRecord, crossReference, account);

        if (outcome.isRejected()) {
            this.rejects.save(TransactionRejectRecordMapper.toRejectRow(
                    DailyTransactionMapper.toRecord(feedRecord), outcome));
            return true;
        }

        // WHY : Assumptions: both values are present on the accepted path by construction -- the
        //       validation rule cannot return accepted without having read an account, which it can only
        //       do through a resolved cross-reference -- so unwrapping here is an assertion of that
        //       invariant rather than an unchecked assumption. If it were ever violated the failure would
        //       be immediate and named, which is what the step should do with a broken invariant.
        CardXref resolved = crossReference.orElseThrow(() -> new IllegalStateException(
                "validation accepted a record whose card resolved to no cross-reference"));
        Account posting = account.orElseThrow(() -> new IllegalStateException(
                "validation accepted a record whose cross-reference resolved to no account"));

        applyToCategoryBalance(feedRecord, resolved);
        applyToAccount(feedRecord, posting);
        postToLedger(feedRecord);
        return false;
    }

    /**
     * Accumulates the record's amount into its account, type and category balance.
     *
     * <p>Assumptions: the key is the account from the CROSS-REFERENCE rather than from the feed record,
     * matching {@code app/cbl/CBTRN02C.cbl:503} which moves {@code XREF-ACCT-ID} into the balance key. The
     * feed record carries no account at all, so there is no second candidate -- the note is here because a
     * reader who assumes the feed carries one will look for it.</p>
     *
     * @param feedRecord the record being posted; must not be {@code null}
     * @param resolved the cross-reference the record's card resolved to; must not be {@code null}
     */
    private void applyToCategoryBalance(DailyTransaction feedRecord, CardXref resolved) {
        this.categoryBalances.accumulate(
                new TransactionCategoryBalanceId(resolved.getAccountId(), feedRecord.getTypeCd(),
                        feedRecord.getCategoryCd()),
                Money.of(feedRecord.getAmount()));
    }

    /**
     * Accumulates the record's amount into the account's balance and its cycle totals.
     *
     * <p>Assumptions: the sign test is {@code >= 0} and a NEGATIVE amount is ADDED to the debit total
     * rather than subtracted from it, exactly as {@code app/cbl/CBTRN02C.cbl:543-546} does. The debit
     * total therefore accumulates negatively, which reads as a defect and is not one: the projection the
     * over-limit test computes at {@code app/cbl/CBTRN02C.cbl:403-405} is credit MINUS debit, so a
     * negatively-accumulated debit and a subtracting accumulation would place the projection on opposite
     * sides of the credit limit. Reproducing the sign convention is what keeps the boundary where the
     * reference puts it.</p>
     *
     * @param feedRecord the record being posted; must not be {@code null}
     * @param posting the account the amount is accumulated into; must not be {@code null}
     */
    private void applyToAccount(DailyTransaction feedRecord, Account posting) {
        BigDecimal amount = feedRecord.getAmount();
        posting.setCurrBal(Money.of(posting.getCurrBal()).plus(Money.of(amount)).amount());

        if (amount.signum() >= 0) {
            posting.setCurrCycCredit(
                    Money.of(posting.getCurrCycCredit()).plus(Money.of(amount)).amount());
        } else {
            posting.setCurrCycDebit(
                    Money.of(posting.getCurrCycDebit()).plus(Money.of(amount)).amount());
        }

        this.accounts.save(posting);
    }

    /**
     * Writes the record to the posted ledger under this run's processing stamp.
     *
     * <p>Assumptions: the stamp is taken from the injected clock rather than from the business date,
     * because {@code app/cbl/CBTRN02C.cbl:438} writes the DB2-format timestamp of the moment the record
     * was posted and not the date the run was for. The existing test suite normalises this field before
     * comparing golden masters precisely because it is the one non-deterministic value the posted record
     * carries.</p>
     *
     * @param feedRecord the record being posted; must not be {@code null}
     */
    private void postToLedger(DailyTransaction feedRecord) {
        Transaction posted = DailyTransactionMapper.toPostedTransaction(
                feedRecord, LocalDateTime.now(this.clock));
        this.ledger.save(posted);
    }
}
