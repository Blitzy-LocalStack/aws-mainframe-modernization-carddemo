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
import java.util.Objects;
import java.util.Optional;
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
 * Stages the posted and system-generated transactions together, in key order, as one combined generation.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this job is state seven of the nightly chain and re-expresses {@code app/jcl/COMBTRAN.jcl},
 * whose merge step at {@code app/jcl/COMBTRAN.jcl:22} invokes the sort utility -- there is no COBOL behind
 * it either. The reference concatenates two inputs, {@code TRANSACT.BKUP(0)} at
 * {@code app/jcl/COMBTRAN.jcl:24} and {@code SYSTRAN(0)} at {@code :26}, sorts the result by transaction
 * identifier ascending, and writes {@code TRANSACT.COMBINED(+1)} at {@code :37}.</p>
 *
 * <h2>Why the merge is one ordered query and the load-back step is gone</h2>
 *
 * <p>Refactoring Rationale: the reference's two inputs are two SEQUENTIAL DATASETS because its posted
 * transactions and its system-generated interest transactions were written to different files -- the
 * posting step to the master and the interest step to {@code SYSTRAN}, at
 * {@code app/jcl/INTCALC.jcl:41}. In the migrated model both land in the SAME table: the interest job
 * writes its generated transactions straight into the ledger, so what the reference had to concatenate is
 * already one relation. The merge is therefore an ordered read of that relation, which is what the
 * migration plan's transformation rule T6 assigns to a sort step, and it produces byte-identical output
 * for the same reason the reference's sort did -- both order by the same key.</p>
 *
 * <p>Refactoring Rationale: the reference follows the sort with a second step at
 * {@code app/jcl/COMBTRAN.jcl:41-48} that copies the combined dataset BACK into the transaction master.
 * That step has no migrated counterpart and needs none: it exists to fold the system-generated
 * transactions into the master, and in the migrated model they were never outside it. Reproducing it would
 * read every row of the ledger and write each one back over itself, which is not a no-op -- it would touch
 * every row's version and every index entry -- so it is dropped rather than reproduced. The divergence
 * is registered as {@code D-COMBINE-NO-LOADBACK} in
 * {@code docs/architecture/cobol-to-service-traceability.md} §7.4.</p>
 *
 * <p>Assumptions: the two current generations are still RESOLVED, even though neither is read for its
 * bytes. Resolving them is what reproduces the reference's own precondition: both inputs are named
 * {@code DISP=SHR}, so a run in which the backup step had not produced a generation would fail at
 * allocation rather than silently merge one input. Here an absent generation fails the step for the same
 * reason and names which family was missing.</p>
 */
@Configuration
public class CombineTransactionsJob {

    /** The name this job registers under, taken from the shared token enumeration. */
    public static final String JOB_NAME = BatchJobName.COMBINE_TRANSACTIONS.token();

    /** The step name the durable ledger records this job's progress under. */
    public static final String STEP_NAME = "combine-transactions-step";

    /** The name of the single object each staged generation of this family holds. */
    public static final String DATASET_OBJECT_NAME = "transact.combined";

    /** The prefix of the temporary file the combined output is streamed into before upload. */
    private static final String STAGING_FILE_PREFIX = "carddemo-transact-combined-";

    /** The operational log this job reports its record counts through. */
    private static final Logger LOG = LoggerFactory.getLogger(CombineTransactionsJob.class);

    /** The relation both merge inputs now live in. */
    private final TransactionRepository ledger;

    /** The resolver that reads the two current generations and allocates the combined one. */
    private final DatasetGenerationService generations;

    /** The durable step record that makes a re-run of a completed step a no-op. */
    private final BatchStepLedger ledgerOfSteps;

    /**
     * Builds the job over the relation it reads and the generation resolver it stages through.
     *
     * @param ledger the transaction relation; must not be {@code null}
     * @param generations the generation resolver; must not be {@code null}
     * @param ledgerOfSteps the durable step ledger; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CombineTransactionsJob(TransactionRepository ledger,
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
    //       method spelled `combineTransactionsJob` inside `CombineTransactionsJob` claims the identifier the class
    //       itself already holds -- and Spring refuses the context with a
    //       BeanDefinitionOverrideException rather than choosing between them. Dropping the suffix
    //       gives the two definitions distinct identifiers.
    // WHY : Assumptions: nothing selects this bean by its identifier. The command contract iterates
    //       the `Job` beans and compares `getName()` against its argument, and `getName()` comes from
    //       JOB_NAME below, so the identifier is free to change and the job's published token is not.
    public Job combineTransactions(JobRepository jobRepository,
            PlatformTransactionManager transactionManager, JobParametersValidator validator) {

        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(validator)
                .start(new StepBuilder(STEP_NAME, jobRepository)
                        .tasklet(this::runStep, transactionManager)
                        .build())
                .build();
    }

    /**
     * Runs the merge once, under the durable step record.
     *
     * @param contribution the framework's handle for reporting this step's exit status; must not be
     *     {@code null}
     * @param context the chunk context carrying the job parameters; must not be {@code null}
     * @return {@link RepeatStatus#FINISHED} always
     */
    private RepeatStatus runStep(StepContribution contribution, ChunkContext context) {
        String runId = BatchConfig.runIdOf(context);
        BusinessDate businessDate = BatchConfig.businessDateOf(context);

        this.ledgerOfSteps.runStep(runId, STEP_NAME, () -> combineIntoNewGeneration(runId, businessDate));
        return RepeatStatus.FINISHED;
    }

    /**
     * Resolves both inputs, stages the ordered output as a new generation, and applies retention.
     *
     * @param runId the orchestrator execution the allocation belongs to; must not be {@code null}
     * @param businessDate the injected business date the generation is partitioned under; must not be
     *     {@code null}
     * @return {@link BatchReturnCode#CLEAN}, because the merge either completes or raises
     */
    private BatchReturnCode combineIntoNewGeneration(String runId, BusinessDate businessDate) {
        DatasetGeneration backupInput = requireCurrentGeneration(DatasetFamily.TRANSACT_BKUP);
        DatasetGeneration systemInput = requireCurrentGeneration(DatasetFamily.SYSTRAN);

        DatasetGeneration target = this.generations.allocateNewGeneration(
                DatasetFamily.TRANSACT_COMBINED, businessDate, runId);

        Path staged = writeOrderedTransactionsToTemporaryFile();
        try {
            this.generations.stageDataset(target, DATASET_OBJECT_NAME, staged);
        } finally {
            deleteQuietly(staged);
        }

        int scratched = 0;
        for (DatasetGeneration agedOut
                : this.generations.generationsToScratch(DatasetFamily.TRANSACT_COMBINED)) {
            scratched += this.generations.scratchGeneration(agedOut);
        }

        LOG.info("event=batch.combine.completed backupInput={} systemInput={} generation={}"
                        + " scratchedObjects={} location={}",
                backupInput.generationNumber(), systemInput.generationNumber(),
                target.generationNumber(), scratched, this.generations.datasetUri(target));
        return BatchReturnCode.CLEAN;
    }

    /**
     * Resolves one input family's current generation, failing the step when the family holds none.
     *
     * @param family the input family to resolve; must not be {@code null}
     * @return the current generation of that family, never {@code null}
     * @throws IllegalStateException if the family holds no generation, which is the migrated form of the
     *     reference's allocation failure on a {@code DISP=SHR} dataset that was never created
     */
    private DatasetGeneration requireCurrentGeneration(DatasetFamily family) {
        Optional<DatasetGeneration> current = this.generations.resolveCurrentGeneration(family);
        return current.orElseThrow(() -> new IllegalStateException("dataset family "
                + family.mainframeBaseName() + " holds no generation, so the combine step has nothing"
                + " to name as an input; the step that writes that family has not run for any business"
                + " date"));
    }

    /**
     * Streams every transaction, ordered by identifier, into a temporary file in the record form.
     *
     * <p>Assumptions: the ordering is by transaction identifier ascending, which is the sort the reference
     * declares at {@code app/jcl/COMBTRAN.jcl:29} over the symbol {@code TRAN-ID,1,16,CH} it defines on the
     * line above. The key is the record's first sixteen characters, so a character sort of it and a sort of
     * the migrated column agree byte for byte.</p>
     *
     * @return the temporary file holding the combined output, never {@code null}
     * @throws IllegalStateException if the file cannot be created or written
     */
    private Path writeOrderedTransactionsToTemporaryFile() {
        final Path staged;
        try {
            staged = Files.createTempFile(STAGING_FILE_PREFIX, ".dat");
        } catch (IOException unavailable) {
            throw new IllegalStateException(
                    "could not create the temporary file the combined output streams through",
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
                    "could not write the combined output to " + staged, unwritable);
        }

        return staged;
    }

    /**
     * Removes a temporary file, reporting rather than raising when it cannot be removed.
     *
     * @param file the file to remove; must not be {@code null}
     */
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException undeletable) {
            LOG.warn("event=batch.combine.temp-file-retained path={} reason={}",
                    file, undeletable.getClass().getName());
        }
    }
}
