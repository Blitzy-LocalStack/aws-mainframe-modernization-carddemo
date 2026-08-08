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
 * <p>Purpose: this job is state three of the nightly chain and re-expresses
 * {@code app/cbl/CBTRN01C.cbl}. It walks the day's feed and, for each record, resolves the card through
 * the cross-reference and then reads the account -- reporting each failure rather than acting on it. It
 * writes nothing at all. Running it ahead of posting is what turns a feed with unresolvable records from
 * a run that produces a reject stream into a run an operator has already been told about.</p>
 *
 * <p>Assumptions: this is the one genuinely driverless program in the reference. No job among the
 * thirty-eight files in {@code app/jcl/} names {@code CBTRN01C}, and neither {@code app/proc/} nor
 * {@code app/scheduler/} references it, so the state machine supplies an invocation the reference never
 * had. That is why its position in the chain is a decision this migration makes rather than one it
 * reproduces: it runs immediately before posting, because reporting on a feed after it has been posted
 * would report on a file that no longer needs the report.</p>
 *
 * <p>Assumptions: the pass is read-only and therefore reports the clean tier unconditionally. The
 * reference emits no return code -- {@code app/cbl/CBTRN01C.cbl} contains no {@code RETURN-CODE}
 * statement -- so an unresolvable record here must NOT fail the chain or warn it: posting is where that
 * record becomes reject reason 100 or 101, and pre-empting that decision would either stop a chain the
 * reference lets run or duplicate a tier the reference reports once.</p>
 */
@Configuration
public class PreflightDailyTransactionsJob {

    /** The name this job registers under, taken from the shared token enumeration. */
    public static final String JOB_NAME = BatchJobName.PREFLIGHT_DAILY_TRANSACTIONS.token();

    /** The step name the durable ledger records this job's progress under. */
    public static final String STEP_NAME = "preflight-daily-transactions-step";

    /** The ordinal a walk of the feed starts strictly above, so the first record is included. */
    private static final long BEFORE_FIRST_ORDINAL = 0L;

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

    /**
     * Builds the job over the repositories it reads.
     *
     * @param feed the daily transaction feed; must not be {@code null}
     * @param crossReferences the card cross-reference; must not be {@code null}
     * @param accounts the account master; must not be {@code null}
     * @param ledgerOfSteps the durable step ledger; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public PreflightDailyTransactionsJob(DailyTransactionRepository feed,
            CardXrefRepository crossReferences, AccountRepository accounts,
            BatchStepLedger ledgerOfSteps) {

        this.feed = Objects.requireNonNull(feed, "feed must not be null");
        this.crossReferences =
                Objects.requireNonNull(crossReferences, "crossReferences must not be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.ledgerOfSteps = Objects.requireNonNull(ledgerOfSteps, "ledgerOfSteps must not be null");
    }

    /**
     * Registers the job under the token the orchestrator names it by.
     *
     * @param jobRepository the framework's durable job repository; must not be {@code null}
     * @param transactionManager the transaction manager the step runs under; must not be {@code null}
     * @param validator the shared parameter validator; must not be {@code null}
     * @return the registered job, never {@code null}
     */
    @Bean
    // WHY : Refactoring Rationale: the factory method is named WITHOUT the "Job" suffix its class
    //       carries, and the difference is load-bearing rather than cosmetic. A `@Configuration` class
    //       registered by type takes a bean id from its own decapitalised class name, so a `@Bean`
    //       method spelled `preflightDailyTransactionsJob` inside `PreflightDailyTransactionsJob` claims the identifier the class
    //       itself already holds -- and Spring refuses the context with a
    //       BeanDefinitionOverrideException rather than choosing between them. Dropping the suffix
    //       gives the two definitions distinct identifiers.
    // WHY : Assumptions: nothing selects this bean by its identifier. The command contract iterates
    //       the `Job` beans and compares `getName()` against its argument, and `getName()` comes from
    //       JOB_NAME below, so the identifier is free to change and the job's published token is not.
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
     * @param contribution the framework's handle for reporting this step's exit status; must not be
     *     {@code null}
     * @param context the chunk context carrying the job parameters; must not be {@code null}
     * @return {@link RepeatStatus#FINISHED} always
     */
    private RepeatStatus runStep(StepContribution contribution, ChunkContext context) {
        this.ledgerOfSteps.runStep(BatchConfig.runIdOf(context), STEP_NAME, this::reportOnEveryRecord);
        return RepeatStatus.FINISHED;
    }

    /**
     * Walks the feed in key order and reports every record whose card or account does not resolve.
     *
     * @return {@link BatchReturnCode#CLEAN} always, because a read-only report has no failing outcome
     */
    private BatchReturnCode reportOnEveryRecord() {
        long lastOrdinal = BEFORE_FIRST_ORDINAL;
        long read = 0L;
        long unresolvedCards = 0L;
        long unresolvedAccounts = 0L;

        while (true) {
            List<DailyTransaction> batch = this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(
                    lastOrdinal, Limit.of(BatchConfig.CHUNK_SIZE));
            if (batch.isEmpty()) {
                break;
            }

            for (DailyTransaction feedRecord : batch) {
                read++;
                Optional<CardXref> resolved =
                        this.crossReferences.findByCardNum(feedRecord.getCardNum());

                if (resolved.isEmpty()) {
                    unresolvedCards++;
                    // WHY : Assumptions: the card number is not logged, and the transaction identifier is
                    //       logged in its place. A primary account number in an operational log is a
                    //       disclosure the migration plan's section 0.4.1.9 forbids, and the identifier is
                    //       sufficient for an operator to find the record in the feed.
                    LOG.warn("event=batch.preflight.card-unresolved transactionId={}",
                            feedRecord.getTransactionId());
                } else if (this.accounts.findByAccountId(resolved.get().getAccountId()).isEmpty()) {
                    unresolvedAccounts++;
                    LOG.warn("event=batch.preflight.account-unresolved transactionId={} accountId={}",
                            feedRecord.getTransactionId(), resolved.get().getAccountId());
                }

                lastOrdinal = feedRecord.getIngestSeq();
            }
        }

        LOG.info("event=batch.preflight.completed read={} unresolvedCards={} unresolvedAccounts={}",
                read, unresolvedCards, unresolvedAccounts);
        return BatchReturnCode.CLEAN;
    }
}
