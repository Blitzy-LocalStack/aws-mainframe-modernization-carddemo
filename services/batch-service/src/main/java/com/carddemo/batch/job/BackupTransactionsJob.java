package com.carddemo.batch.job;

import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.mapper.TransactionRecordMapper;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.DatasetGenerationService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
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
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Copies the whole transaction master into a new generation of the backup family.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this job is state six of the nightly chain and re-expresses {@code app/jcl/TRANBKP.jcl},
 * whose copy step at {@code app/jcl/TRANBKP.jcl:23} invokes a catalogued procedure rather than a program
 * -- there is no COBOL behind it. The procedure copies the transaction master to
 * {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)} at {@code app/jcl/TRANBKP.jcl:33}, which is a new generation
 * of a base defined {@code LIMIT(5)} with {@code SCRATCH}. This job does both halves: it stages the copy
 * as a new generation and it scratches whatever that generation pushed out of the retained window.</p>
 *
 * <p>Assumptions: the records are written in the fixed-width form the reference's own datasets carry,
 * through {@code TransactionRecordMapper}, rather than in any convenient serialisation. The staged
 * generation's whole purpose is that it can be read back by something other than this module -- the
 * combine step reads it, and the migration's verification harness compares it against the reference's own
 * output -- so its bytes are the contract.</p>
 *
 * <h2>The soft-warn gate this job's driver carries, and where it went</h2>
 *
 * <p>Assumptions: {@code app/jcl/TRANBKP.jcl:51} carries {@code COND=(4,LT)}, the only soft-warn
 * continuation gate in the whole reference tree. A JCL condition is a SKIP predicate, so it reads "skip
 * when four is less than the prior return code" -- that is, run when the code is four or lower. It is the
 * predecessor's tier that decides, not this job's, so the gate belongs to the state machine's own choice
 * state and not here. This job reports clean or fails; it never reports the warn tier, because the copy
 * either happened or did not.</p>
 */
@Configuration
public class BackupTransactionsJob {

    /** The name this job registers under, taken from the shared token enumeration. */
    public static final String JOB_NAME = BatchJobName.BACKUP_TRANSACTIONS.token();

    /** The step name the durable ledger records this job's progress under. */
    public static final String STEP_NAME = "backup-transactions-step";

    /** The name of the single object each staged generation of this family holds. */
    public static final String DATASET_OBJECT_NAME = "transact.bkup";

    /** The prefix of the temporary file the copy is streamed into before upload. */
    private static final String STAGING_FILE_PREFIX = "carddemo-transact-bkup-";

    /** The operational log this job reports its record counts through. */
    private static final Logger LOG = LoggerFactory.getLogger(BackupTransactionsJob.class);

    /** The transaction master being copied. */
    private final TransactionRepository ledger;

    /** The resolver that allocates the new generation and applies the retention rule. */
    private final DatasetGenerationService generations;

    /** The durable step record that makes a re-run of a completed step a no-op. */
    private final BatchStepLedger ledgerOfSteps;

    /**
     * Builds the job over the master it copies and the generation resolver it stages through.
     *
     * @param ledger the transaction master; must not be {@code null}
     * @param generations the generation resolver; must not be {@code null}
     * @param ledgerOfSteps the durable step ledger; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public BackupTransactionsJob(TransactionRepository ledger,
            DatasetGenerationService generations, BatchStepLedger ledgerOfSteps) {

        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.generations = Objects.requireNonNull(generations, "generations must not be null");
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
    //       method spelled `backupTransactionsJob` inside `BackupTransactionsJob` claims the identifier the class
    //       itself already holds -- and Spring refuses the context with a
    //       BeanDefinitionOverrideException rather than choosing between them. Dropping the suffix
    //       gives the two definitions distinct identifiers.
    // WHY : Assumptions: nothing selects this bean by its identifier. The command contract iterates
    //       the `Job` beans and compares `getName()` against its argument, and `getName()` comes from
    //       JOB_NAME below, so the identifier is free to change and the job's published token is not.
    public Job backupTransactions(JobRepository jobRepository,
            PlatformTransactionManager transactionManager, JobParametersValidator validator) {

        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(validator)
                .start(new StepBuilder(STEP_NAME, jobRepository)
                        .tasklet(this::runStep, transactionManager)
                        .build())
                .build();
    }

    /**
     * Runs the copy once, under the durable step record.
     *
     * @param contribution the framework's handle for reporting this step's exit status; must not be
     *     {@code null}
     * @param context the chunk context carrying the job parameters; must not be {@code null}
     * @return {@link RepeatStatus#FINISHED} always
     */
    private RepeatStatus runStep(StepContribution contribution, ChunkContext context) {
        String runId = BatchConfig.runIdOf(context);
        BusinessDate businessDate = BatchConfig.businessDateOf(context);

        this.ledgerOfSteps.runStep(runId, STEP_NAME, () -> copyToNewGeneration(runId, businessDate));
        return RepeatStatus.FINISHED;
    }

    /**
     * Allocates the new generation, streams the master into it, then applies the retention rule.
     *
     * @param runId the orchestrator execution the allocation belongs to; must not be {@code null}
     * @param businessDate the injected business date the generation is partitioned under; must not be
     *     {@code null}
     * @return {@link BatchReturnCode#CLEAN}, because a copy either completes or raises
     */
    private BatchReturnCode copyToNewGeneration(String runId, BusinessDate businessDate) {
        DatasetGeneration target = this.generations.allocateNewGeneration(
                DatasetFamily.TRANSACT_BKUP, businessDate, runId);

        Path staged = writeMasterToTemporaryFile();
        try {
            this.generations.stageDataset(target, DATASET_OBJECT_NAME, staged);
        } finally {
            // WHY : Assumptions: the temporary file is removed on every path, including a failed upload,
            //       because a batch task's ephemeral disk is finite and a failed step is retried. Leaving
            //       it would let a sequence of retries fill the volume and turn a transient upload failure
            //       into a task that can no longer start.
            deleteQuietly(staged);
        }

        int scratched = 0;
        for (DatasetGeneration agedOut
                : this.generations.generationsToScratch(DatasetFamily.TRANSACT_BKUP)) {
            scratched += this.generations.scratchGeneration(agedOut);
        }

        LOG.info("event=batch.backup.completed generation={} scratchedObjects={} location={}",
                target.generationNumber(), scratched, this.generations.datasetUri(target));
        return BatchReturnCode.CLEAN;
    }

    /**
     * Streams every transaction, in key order, into a temporary file in the reference's record form.
     *
     * <p>Assumptions: the copy streams through a temporary file rather than being assembled in memory. A
     * transaction master is as large as the ledger, so an in-memory copy would bound this step by heap;
     * ephemeral disk is what a batch task has more of, and it is what the reference's own sequential output
     * dataset was.</p>
     *
     * @return the temporary file holding the copy, never {@code null}
     * @throws IllegalStateException if the file cannot be created or written
     */
    private Path writeMasterToTemporaryFile() {
        final Path staged;
        try {
            staged = Files.createTempFile(STAGING_FILE_PREFIX, ".dat");
        } catch (IOException unavailable) {
            throw new IllegalStateException(
                    "could not create the temporary file the transaction copy streams through",
                    unavailable);
        }

        try (OutputStream sink = Files.newOutputStream(staged)) {
            try (Stream<Transaction> rows = this.ledger.findAllByOrderByTransactionIdAsc()) {
                for (Transaction row : (Iterable<Transaction>) rows::iterator) {
                    sink.write(TransactionRecordMapper.toRecord(row));
                }
            }
        } catch (IOException unwritable) {
            deleteQuietly(staged);
            throw new IllegalStateException(
                    "could not write the transaction copy to " + staged, unwritable);
        }

        return staged;
    }

    /**
     * Removes a temporary file, reporting rather than raising when it cannot be removed.
     *
     * <p>Assumptions: a failed deletion is logged and swallowed rather than raised, because it happens on
     * paths that are already failing and re-raising there would replace the real failure with a cleanup
     * one. The consequence -- a file left behind -- is bounded by the container's lifetime.</p>
     *
     * @param file the file to remove; must not be {@code null}
     */
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException undeletable) {
            LOG.warn("event=batch.backup.temp-file-retained path={} reason={}",
                    file, undeletable.getClass().getName());
        }
    }

    /**
     * Reports the families this job stages into, so a reader need not infer them from the body.
     *
     * @return the single family this job writes, never {@code null}
     */
    public static List<DatasetFamily> stagedFamilies() {
        return List.of(DatasetFamily.TRANSACT_BKUP);
    }
}
