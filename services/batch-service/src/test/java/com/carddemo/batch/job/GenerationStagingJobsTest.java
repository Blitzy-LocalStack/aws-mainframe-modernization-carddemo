package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.DatasetGenerationService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.job.parameters.JobParametersValidator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;

/**
 * Pins that the two generation-writing jobs drive the whole generation lifecycle, in order.
 *
 * <p>Purpose: the generation service holds the {@code (+1)}, {@code (0)} and {@code LIMIT(5) SCRATCH}
 * semantics of {@code app/jcl/DEFGDGB.jcl}, and its own sibling test proves those semantics against an
 * in-memory object store. What is settled HERE is the other half: that a landed job actually reaches them.
 * Reviewing this module found the service's operations published with no production caller at all, and a
 * charter sentence claiming a caller is not evidence of one -- only an execution is. Each case below runs a
 * real job and asserts which coordinate it allocates, that it stages under that coordinate, and that it
 * applies the retention rule afterwards.</p>
 *
 * <p>Assumptions: the generation service is MOCKED rather than backed by the in-memory object store the
 * service's own test uses. Every ruling here is about which calls a job makes, with which coordinates, and
 * in which order; whether those calls produce the right keys, allocate generation one for an empty family
 * and count retention across business dates is settled by {@code DatasetGenerationServiceTest} against a
 * real store. Backing this test with a store as well would restate those rulings while making the
 * call-order assertions harder to read, and a failure would no longer localise to one tier.</p>
 *
 * <p>Assumptions: the durable step ledger is stubbed to EVALUATE the body it is handed, matching the
 * sibling {@code PostTransactionsJobTest}. A mock returning a default would run none of the staging work,
 * so every verification below would hold vacuously against a job that did nothing.</p>
 *
 * <p>Assumptions: the jobs are run through the framework rather than by invoking their private bodies,
 * because the parameter validator the job is built with, the step lifecycle and the propagation of a
 * step failure into the job's status are all part of what the orchestrator relies on, and a direct call
 * would bypass all three.</p>
 */
@DisplayName("the generation-writing batch jobs")
class GenerationStagingJobsTest {

    /** The business date the cases inject, in the ten-character separated layout. */
    private static final String BUSINESS_DATE_TOKEN = "2022-07-18";

    /** The injected business date as the jobs receive it. */
    private static final BusinessDate BUSINESS_DATE = new BusinessDate(BUSINESS_DATE_TOKEN);

    /** The orchestrator execution identifier the cases run under. */
    private static final String RUN_ID = "batch-run-0001";

    /** The job-instance identifier the cases run under, which no assertion depends on. */
    private static final long INSTANCE_ID = 1L;

    /** The job-execution identifier the cases run under, which no assertion depends on. */
    private static final long EXECUTION_ID = 1L;

    /** The declared length of one posted-transaction image, from {@code app/cpy/CVTRA05Y.cpy}. */
    private static final int TRANSACTION_RECORD_LENGTH = 350;

    /** The transaction master both jobs stream. */
    private TransactionRepository ledger;

    /** The generation resolver whose lifecycle these jobs are asserted to drive. */
    private DatasetGenerationService generations;

    /** The durable step ledger, stubbed to evaluate its body. */
    private BatchStepLedger ledgerOfSteps;

    /** The framework's in-memory job repository. */
    private JobRepository jobRepository;

    /** The shared parameter validator both jobs are built with. */
    private JobParametersValidator validator;

    /**
     * Builds the mocked collaborators with the defaults a clean staging run sees.
     *
     * <p>Assumptions: a fresh set is built per case rather than shared, because every case asserts call
     * counts or call order and a shared mock would carry one case's calls into the next.</p>
     */
    @BeforeEach
    void buildCollaborators() {
        this.ledger = mock(TransactionRepository.class);
        this.generations = mock(DatasetGenerationService.class);
        this.ledgerOfSteps = mock(BatchStepLedger.class);
        this.jobRepository = new ResourcelessJobRepository();
        this.validator = new BatchConfig().carddemoJobParametersValidator();

        when(this.ledgerOfSteps.runStep(anyString(), anyString(), any(BatchJobName.class), any())).thenAnswer(call -> {
            BatchReturnCode outcome = call.<Supplier<BatchReturnCode>>getArgument(3).get();
            return new BatchStepLedger.StepOutcome(outcome, false);
        });

        // WHY : Assumptions: the master is empty by default. Only one case below is about the bytes that
        //       reach the staged file; the rest are about which coordinate is allocated and what happens
        //       to the aged-out generations, and an empty master keeps those cases free of record
        //       construction that none of their assertions read.
        when(this.ledger.findAllByOrderByTransactionIdAsc()).thenReturn(Stream.empty());

        when(this.generations.allocateNewGeneration(any(DatasetFamily.class), any(BusinessDate.class),
                anyString())).thenAnswer(call -> generation(call.getArgument(0), 1));
        when(this.generations.resolveCurrentGeneration(any(DatasetFamily.class)))
                .thenAnswer(call -> Optional.of(generation(call.getArgument(0), 4)));
        when(this.generations.generationsToScratch(any(DatasetFamily.class))).thenReturn(List.of());
        when(this.generations.datasetUri(any(DatasetGeneration.class)))
                .thenReturn("s3://carddemo-datasets-test/a/prefix/");
        when(this.generations.stageDataset(any(DatasetGeneration.class), anyString(), any(Path.class)))
                .thenReturn("a/staged/key");
    }

    /**
     * The backup job allocates, stages under what it allocated, then applies retention -- in that order.
     *
     * <p>Pins {@code app/jcl/TRANBKP.jcl:33}, which names {@code TRANSACT.BKUP(+1)} as the copy's target,
     * against a base defined {@code LIMIT(5)} with {@code SCRATCH}. The order matters rather than merely
     * the calls: staging before allocating would write under a coordinate no allocation reserved, and
     * applying retention before staging would count the new generation as absent and could retain six.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("allocate the backup generation, stage into it, then apply retention")
    void backupAllocatesStagesThenAppliesRetention() throws Exception {
        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        InOrder lifecycle = inOrder(this.generations);
        lifecycle.verify(this.generations).allocateNewGeneration(
                DatasetFamily.TRANSACT_BKUP, BUSINESS_DATE, RUN_ID);
        // WHY : Assumptions: the two settled arguments are wrapped in eq() because the third is a matcher.
        //       Mockito refuses a verification that mixes raw values with matchers, and the temporary path
        //       is chosen inside the job, so it can only be matched rather than named.
        lifecycle.verify(this.generations).stageDataset(
                eq(generation(DatasetFamily.TRANSACT_BKUP, 1)),
                eq(BackupTransactionsJob.DATASET_OBJECT_NAME), any(Path.class));
        lifecycle.verify(this.generations).generationsToScratch(DatasetFamily.TRANSACT_BKUP);
    }

    /**
     * Every generation the retention rule names is scratched, and none that it does not.
     *
     * <p>Pins the {@code SCRATCH} half of {@code LIMIT(5) SCRATCH}: the rule decides WHICH generations age
     * out and the job is what actually removes them, so a job that consulted the rule and ignored its
     * answer would leave a family growing without bound while every log line reported retention.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("scratch exactly the generations the retention rule names")
    void backupScratchesEveryGenerationTheRuleNames() throws Exception {
        DatasetGeneration oldest = generation(DatasetFamily.TRANSACT_BKUP, 1);
        DatasetGeneration nextOldest = generation(DatasetFamily.TRANSACT_BKUP, 2);
        when(this.generations.generationsToScratch(DatasetFamily.TRANSACT_BKUP))
                .thenReturn(List.of(oldest, nextOldest));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        verify(this.generations).scratchGeneration(oldest);
        verify(this.generations).scratchGeneration(nextOldest);
    }

    /**
     * Nothing is scratched when the family is inside the retained window.
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("scratch nothing when the retention rule names none")
    void backupScratchesNothingWhenTheRuleNamesNone() throws Exception {
        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        verify(this.generations, never()).scratchGeneration(any(DatasetGeneration.class));
    }

    /**
     * The master's rows reach the staged file, and the temporary file does not outlive the step.
     *
     * <p>Pins two things one run proves together: the copy is a byte image of the master at the record
     * length {@code app/jcl/TRANBKP.jcl:58} declares, and the temporary file the copy streams through is
     * removed once staged. The size is read INSIDE the staging call, because by the time the step returns
     * the file is expected to be gone -- which is the second assertion.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("stream the master into the staged file and remove the temporary copy")
    void backupStagesTheMasterAndRemovesTheTemporaryFile() throws Exception {
        AtomicBoolean masterClosed = new AtomicBoolean(false);
        when(this.ledger.findAllByOrderByTransactionIdAsc()).thenReturn(
                Stream.of(postedRow()).onClose(() -> masterClosed.set(true)));

        AtomicLong stagedBytes = new AtomicLong(-1L);
        AtomicBoolean presentWhileStaging = new AtomicBoolean(false);
        Path[] stagedPath = new Path[1];
        when(this.generations.stageDataset(any(DatasetGeneration.class), anyString(), any(Path.class)))
                .thenAnswer(call -> {
                    Path body = call.getArgument(2);
                    stagedPath[0] = body;
                    presentWhileStaging.set(Files.exists(body));
                    stagedBytes.set(Files.size(body));
                    return "a/staged/key";
                });

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(presentWhileStaging).isTrue();
        assertThat(stagedBytes).hasValue(TRANSACTION_RECORD_LENGTH);
        assertThat(Files.exists(stagedPath[0]))
                .withFailMessage("the temporary copy outlived the step at %s", stagedPath[0])
                .isFalse();
        // WHY : Assumptions: the master stream is asserted CLOSED rather than merely consumed. The
        //       repository returns a cursor-backed stream, so a job that read it without closing it would
        //       leak a database cursor per run -- a leak no output difference reveals.
        assertThat(masterClosed).isTrue();
    }

    /**
     * The combine job resolves both current inputs before it allocates its own generation.
     *
     * <p>Pins {@code app/jcl/COMBTRAN.jcl:24} and {@code :26}, the only two {@code (0)} references in the
     * whole baseline, which name the backup and system-transaction families as the merge's inputs. Both
     * are resolved first so that a missing input fails the step before anything is allocated or written.
     * </p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("resolve both current inputs before allocating the combined generation")
    void combineResolvesBothInputsBeforeAllocating() throws Exception {
        JobExecution execution = runCombine();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        InOrder lifecycle = inOrder(this.generations);
        lifecycle.verify(this.generations).resolveCurrentGeneration(DatasetFamily.TRANSACT_BKUP);
        lifecycle.verify(this.generations).resolveCurrentGeneration(DatasetFamily.SYSTRAN);
        lifecycle.verify(this.generations).allocateNewGeneration(
                DatasetFamily.TRANSACT_COMBINED, BUSINESS_DATE, RUN_ID);
        lifecycle.verify(this.generations).stageDataset(
                eq(generation(DatasetFamily.TRANSACT_COMBINED, 1)),
                eq(CombineTransactionsJob.DATASET_OBJECT_NAME), any(Path.class));
    }

    /**
     * A missing input generation fails the step by name, before anything is allocated or staged.
     *
     * <p>Pins the migrated form of the reference's allocation failure on a {@code DISP=SHR} dataset that
     * was never created: the reference cannot start the step at all, so the migrated step must not
     * silently combine one input, and the family it could not name has to appear in the failure -- an
     * operator resolving a broken nightly chain needs to know WHICH upstream step did not run.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("fail by name when an input family holds no generation")
    void combineFailsByNameWhenAnInputHoldsNoGeneration() throws Exception {
        when(this.generations.resolveCurrentGeneration(DatasetFamily.SYSTRAN))
                .thenReturn(Optional.empty());

        JobExecution execution = runCombine();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(failure -> assertThat(failure.getMessage())
                        .contains(DatasetFamily.SYSTRAN.mainframeBaseName()));
        verify(this.generations, never()).allocateNewGeneration(
                any(DatasetFamily.class), any(BusinessDate.class), anyString());
        verify(this.generations, never()).stageDataset(
                any(DatasetGeneration.class), anyString(), any(Path.class));
    }

    /**
     * Runs the backup job once with both required parameters and returns its execution.
     *
     * @return the finished job execution, never {@code null}
     * @throws Exception if the framework's own execution path raises
     */
    private JobExecution runBackup() throws Exception {
        BackupTransactionsJob configuration =
                new BackupTransactionsJob(this.ledger, this.generations, this.ledgerOfSteps);
        Job job = configuration.backupTransactions(
                this.jobRepository, new ResourcelessTransactionManager(), this.validator);
        return execute(job, BackupTransactionsJob.JOB_NAME);
    }

    /**
     * Runs the combine job once with both required parameters and returns its execution.
     *
     * @return the finished job execution, never {@code null}
     * @throws Exception if the framework's own execution path raises
     */
    private JobExecution runCombine() throws Exception {
        CombineTransactionsJob configuration =
                new CombineTransactionsJob(this.ledger, this.generations, this.ledgerOfSteps);
        Job job = configuration.combineTransactions(
                this.jobRepository, new ResourcelessTransactionManager(), this.validator);
        return execute(job, CombineTransactionsJob.JOB_NAME);
    }

    /**
     * Registers an execution for one job and runs it.
     *
     * <p>Assumptions: the instance and the execution are constructed directly and then registered, the
     * way the sibling {@code PostTransactionsJobTest} does, because the repository's own creation method
     * takes an instance rather than a name.</p>
     *
     * @param job the job to run; must not be {@code null}
     * @param jobName the name the instance is registered under; must not be {@code null}
     * @return the finished job execution, never {@code null}
     * @throws Exception if the framework's own execution path raises
     */
    private JobExecution execute(Job job, String jobName) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER, BUSINESS_DATE_TOKEN, true)
                .addString(BatchConfig.RUN_ID_PARAMETER, RUN_ID, false)
                .toJobParameters();

        JobInstance instance = new JobInstance(INSTANCE_ID, jobName);
        JobExecution execution = new JobExecution(EXECUTION_ID, instance, parameters);
        this.jobRepository.update(execution);
        job.execute(execution);
        return execution;
    }

    /**
     * Builds one generation coordinate of a family under the injected business date.
     *
     * @param family the family the coordinate belongs to; must not be {@code null}
     * @param number the generation number the coordinate carries
     * @return the coordinate, never {@code null}
     */
    private static DatasetGeneration generation(DatasetFamily family, int number) {
        return new DatasetGeneration(family, BUSINESS_DATE, number);
    }

    /**
     * Builds one posted transaction the record mapper accepts.
     *
     * <p>Assumptions: the field values are the ones the sibling {@code TransactionRecordMapperTest}
     * round-trips, so their widths are known to satisfy the fixed-width fields the encoder checks. Only
     * the key and the amount are structurally required; the rest are populated so the emitted image is a
     * realistic 350 bytes rather than a mostly-blank one.</p>
     *
     * @return the transaction, never {@code null}
     */
    private static Transaction postedRow() {
        Transaction row = new Transaction("0000000000683580");
        row.setTypeCd("01");
        row.setCategoryCd("0001");
        row.setSource("POS TERM");
        row.setDescription("Purchase at Abshire-Lowe");
        row.setAmount(new BigDecimal("504.77"));
        row.setMerchantId(800000000L);
        row.setMerchantName("Abshire-Lowe");
        row.setMerchantCity("North Enoshaven");
        row.setMerchantZip("72112");
        row.setCardNum("4859452612877065");
        row.setOrigTs(LocalDateTime.of(2022, 6, 10, 19, 27, 53));
        return row;
    }
}
