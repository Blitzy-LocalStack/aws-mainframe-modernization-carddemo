package com.carddemo.batch.config;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.service.BatchStepLedger;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.parameters.DefaultJobParametersValidator;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersValidator;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Owns the chunk-oriented step infrastructure and the job-parameter contract every batch job runs under.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: the seven jobs in {@code com.carddemo.batch.job} share three things that are properties of
 * the MODULE rather than of any one job -- the chunk size a chunk-oriented step commits at, the names of
 * the two job parameters the orchestrator supplies, and the validation that refuses a job started without
 * them. Declaring any of the three seven times would let one copy drift, and the copy that drifts is the
 * one nobody re-derives. They are declared here once.</p>
 *
 * <p>Assumptions: the durable job repository is NOT declared here and is deliberately left to the
 * framework's own auto-configuration, which builds a relational one over this module's data source. That
 * is the correct owner: the repository's tables are created by this module's own schema migration, the
 * data source is already configured with the search path that reaches them, and a repository declared
 * here would have to restate the schema and the table prefix that {@code application.yml} already
 * carries. What this class owns of the durable repository is the CONTRACT that makes restart meaningful,
 * which is the parameter identity described below -- a repository with no stable job-instance identity is
 * a repository that can restart nothing.</p>
 *
 * <h2>The business date identifies a job instance; the run identifier must not</h2>
 *
 * <p>Assumptions: exactly one of the two parameters is identifying. The business date is, because it is
 * what makes a job instance the run of a particular day -- the reference injects it as
 * {@code PARM='2022071800'} at {@code app/jcl/INTCALC.jcl:22} rather than reading a clock, which is the
 * property that makes a rerun reproduce its output byte for byte. The run identifier is NOT, and that is
 * load-bearing rather than incidental: were it identifying, every retry of a step would present a new
 * job instance, the framework would find no completed instance to refuse, and
 * {@code BatchApplication}'s already-complete branch -- the migrated form of resubmitting a job that has
 * already run -- would never be reached. A redriven Step Functions execution would then post the same
 * day's transactions twice.</p>
 *
 * <p>Trade-offs: the consequence is that the run identifier cannot distinguish two runs of one business
 * date at the framework's level, and it is not asked to. It distinguishes them where it matters, in the
 * durable generation reservation and in the step ledger, both of which key on it explicitly.</p>
 */
@Configuration
public class BatchConfig {

    /**
     * The number of feed records a chunk-oriented step processes before committing, one hundred.
     *
     * <p>Assumptions: the reference commits per record -- it has no chunk concept at all, and each
     * iteration of the posting loop at {@code app/cbl/CBTRN02C.cbl:202-217} writes its three records and
     * moves on -- so any chunk size larger than one is a departure from it. The departure is safe for
     * exactly one reason: the loop's iterations are independent, because a daily transaction's three
     * writes touch only the rows its own card resolves to, so committing a hundred of them together
     * produces the same final state as committing them one at a time. What it does NOT preserve is the
     * state visible to a reader midway through the run, and nothing reads it: online writes are quiesced
     * for the batch window and no other step of the chain runs concurrently with posting.</p>
     *
     * <p>Trade-offs: a hundred was chosen over one and over a thousand. One would issue a commit per
     * record and make the step's duration dominated by transaction overhead rather than by work; a
     * thousand would widen the window a failure discards and, more importantly, hold a thousand rows'
     * worth of locks on the account master, where several transactions of one chunk can legitimately
     * name the SAME account and therefore contend. A hundred keeps both costs small, and the value is
     * declared here rather than per job so that changing it is one edit and one review.</p>
     */
    public static final int CHUNK_SIZE = 100;

    /**
     * The name of the non-identifying job parameter carrying the orchestrator execution identifier.
     *
     * <p>Assumptions: the name is spelled here and consumed by {@link #runIdOf(ChunkContext)}, so no job
     * spells it. {@code BatchApplication} supplies it from the {@code CARDDEMO_BATCH_RUN_ID} container
     * variable the state machine injects.</p>
     */
    public static final String RUN_ID_PARAMETER = "runId";

    /**
     * Refuses a job started without both of the parameters every job in this module requires.
     *
     * <p>Assumptions: validation is a bean shared by all seven jobs rather than a check written into each
     * one, because the failure it prevents is identical in all seven and its cost is identical too: a job
     * missing the business date would run against whatever a job reading a clock would produce, which is
     * an output no rerun reproduces. Refusing at start-up turns that into a failure the orchestrator
     * catches on state entry rather than a plausible-looking dataset nobody re-derives.</p>
     *
     * <p>Assumptions: both names are declared REQUIRED and neither is declared optional, so a parameter
     * this module does not name at all is rejected as well as a missing one. That is the stricter of the
     * two available readings and it is chosen deliberately: an unrecognised parameter reaching a job is
     * an orchestration change that has not reached this module, and discovering it at start-up is
     * cheaper than discovering it in the output.</p>
     *
     * @return the validator every job in this module is built with, never {@code null}
     */
    @Bean
    public JobParametersValidator carddemoJobParametersValidator() {
        return new DefaultJobParametersValidator(
                new String[] {BatchApplication.BUSINESS_DATE_PARAMETER, RUN_ID_PARAMETER},
                new String[] {});
    }

    /**
     * Reads the injected business date out of the parameters the running step was started with.
     *
     * <p>Assumptions: the value is read from the step's own execution rather than from a field or a
     * step-scoped bean, so a job holds no state between invocations and reads no clock. That is the
     * property the reference's injected parameter has and the reason a rerun reproduces its output.</p>
     *
     * @param context the chunk context the framework passes into a tasklet; must not be {@code null}
     * @return the business date the run was started for, never {@code null}
     * @throws IllegalStateException if the parameter is absent, which the validator makes unreachable
     *     through the supported entry point and which therefore names a job started some other way
     * @throws IllegalArgumentException if the parameter is present but is not a token the business date
     *     accepts
     */
    public static BusinessDate businessDateOf(ChunkContext context) {
        return new BusinessDate(
                requiredParameter(context, BatchApplication.BUSINESS_DATE_PARAMETER));
    }

    /**
     * Reads the orchestrator execution identifier out of the parameters the running step was started with.
     *
     * @param context the chunk context the framework passes into a tasklet; must not be {@code null}
     * @return the run identifier, never {@code null} and never blank
     * @throws IllegalStateException if the parameter is absent or blank
     */
    public static String runIdOf(ChunkContext context) {
        return requiredParameter(context, RUN_ID_PARAMETER);
    }

    /**
     * Reads one required string parameter out of a running step's job parameters.
     *
     * @param context the chunk context the framework passes into a tasklet; must not be {@code null}
     * @param name the parameter name to read; must not be {@code null}
     * @return the parameter's value, never {@code null} and never blank
     * @throws IllegalStateException if the parameter is absent or holds nothing but whitespace
     */
    private static String requiredParameter(ChunkContext context, String name) {
        JobParameters parameters =
                context.getStepContext().getStepExecution().getJobParameters();
        String value = parameters.getString(name);

        // WHY : Assumptions: blank is rejected as well as absent, because an environment variable
        //       exported with an empty value resolves successfully to the empty string. Without this the
        //       step would run with an empty business date, and the coordinate type's own width check
        //       would then report a malformed token rather than the variable nobody set.
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("job parameter '" + name + "' is required and was not"
                    + " supplied; a job in this module is startable only through the module's own entry"
                    + " point, which supplies both parameters");
        }

        return value;
    }

    /**
     * Supplies the time source the durable step ledger stamps its rows with.
     *
     * <p>WHY this bean exists at all: {@code BatchStepLedger} is a component with a
     * {@code (BatchRunRepository, Clock)} constructor, and the shared kernel's single
     * auto-configuration contributes the correlation filter, the meter filter, the money codec and
     * the error advice -- no clock. Without this bean the context cannot be built, so every value
     * of {@code --job=} fails before any job is looked up. Assumptions: no batch-service test had
     * loaded a context before the jobs landed, which is why a missing bean on the only path that
     * needs it stayed invisible; the job registration census now loads one.</p>
     *
     * <p>WHY UTC rather than the system default zone: a ledger row is compared against rows
     * written by other tasks in other containers, and a started-at that is only interpretable
     * alongside the zone of the host that wrote it cannot be ordered against them. This is the
     * same posture the timestamp formatter in the shared kernel takes.</p>
     *
     * @return a UTC clock; never {@code null}
     */
    @Bean
    public Clock batchClock() {
        return Clock.systemUTC();
    }

    /**
     * Supplies the builder every job turns its unit of work into a step with.
     *
     * @param ledger the durable step ledger providing redrive idempotency; must not be
     *     {@code null}
     * @param runId the orchestrator's execution identifier, which scopes a ledger row so that
     *     tonight's run of a step is a different row from last night's; must not be {@code null}
     *     or blank
     * @return the step builder shared by all seven jobs; never {@code null}
     */
    @Bean
    public LedgerGuardedStep ledgerGuardedStep(
            BatchStepLedger ledger,
            // WHY : the variable name is taken from BatchApplication's own public constant rather
            //       than spelled a second time, so the environment name this module reads cannot
            //       drift between the two places that read it. The default matches that class's
            //       own fallback exactly, so a task started outside an orchestrator still records
            //       ledger rows under a stable identifier instead of failing to start.
            // WHY : Trade-offs: a default means a developer running two jobs locally without
            //       setting the variable shares one run identifier, so the second job's step is
            //       skipped as already complete. That is the correct behaviour for the same step
            //       and a surprise only across different ones; the alternative -- no default --
            //       makes the module unusable outside an orchestrator, which is worse for the
            //       runbook and for every test.
            @Value("${" + BatchApplication.RUN_ID_VARIABLE + ":unorchestrated}") String runId) {

        return new LedgerGuardedStep(ledger, runId);
    }

    /**
     * Builds the one step shape every batch job uses: a single transactional tasklet, guarded by
     * the durable ledger, whose graded return code becomes the step's exit status.
     *
     * <p>Instances are obtained from {@link BatchConfig#ledgerGuardedStep}; the constructor is not
     * public because the run identifier it binds is a property of the running task rather than
     * something a caller chooses.</p>
     */
    public static final class LedgerGuardedStep {

        /**
         * Execution-context key under which a completed body's graded return code is parked for
         * the exit-status listener to read.
         *
         * <p>WHY the value travels through the execution context rather than a field on the
         * builder: one builder instance is shared by every job in the context, and a field would
         * be written by whichever step ran last. The execution context is scoped to the single
         * step execution that wrote it.</p>
         */
        public static final String RETURN_CODE_KEY = "carddemo.batch.returnCode";

        private final BatchStepLedger ledger;

        private final String runId;

        /**
         * Binds the ledger and the run identifier this builder's steps record against.
         *
         * @param ledger the durable step ledger; must not be {@code null}
         * @param runId the orchestrator execution identifier; must not be {@code null} or blank
         * @throws IllegalArgumentException when {@code runId} is blank
         */
        LedgerGuardedStep(BatchStepLedger ledger, String runId) {
            this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
            Objects.requireNonNull(runId, "runId must not be null");
            if (runId.isBlank()) {
                throw new IllegalArgumentException("runId must not be blank");
            }
            this.runId = runId;
        }

        /**
         * Reports the orchestrator execution identifier this builder's steps record against.
         *
         * @return the run identifier; never {@code null} or blank
         */
        public String runId() {
            return this.runId;
        }

        /**
         * Wraps a unit of work as a ledger-guarded, exit-status-graded step.
         *
         * @param stepName the ledger step name, which is also the Spring Batch step name; must not
         *     be {@code null} or blank
         * @param jobRepository the batch job repository the step records its execution in; must
         *     not be {@code null}
         * @param transactionManager the transaction manager whose boundary the body runs inside;
         *     must not be {@code null}
         * @param body the unit of work, which receives the business date the orchestrator supplied
         *     and returns its graded outcome; must not be {@code null}
         * @return the built step; never {@code null}
         * @throws NullPointerException when any argument is {@code null}
         * @throws IllegalArgumentException when {@code stepName} is blank
         */
        public Step build(
                String stepName,
                JobRepository jobRepository,
                PlatformTransactionManager transactionManager,
                StepBody body) {

            Objects.requireNonNull(stepName, "stepName must not be null");
            Objects.requireNonNull(jobRepository, "jobRepository must not be null");
            Objects.requireNonNull(transactionManager, "transactionManager must not be null");
            Objects.requireNonNull(body, "body must not be null");
            if (stepName.isBlank()) {
                throw new IllegalArgumentException("stepName must not be blank");
            }

            Tasklet tasklet = (contribution, chunkContext) -> {
                BusinessDate businessDate = businessDateOf(chunkContext);
                BatchStepLedger.StepOutcome outcome =
                        this.ledger.runStep(this.runId, stepName, () -> body.run(businessDate));
                chunkContext.getStepContext().getStepExecution().getExecutionContext()
                        .putInt(RETURN_CODE_KEY, outcome.returnCode().numericValue());
                return RepeatStatus.FINISHED;
            };

            return new StepBuilder(stepName, jobRepository)
                    .tasklet(tasklet, transactionManager)
                    .listener(new ReturnCodeExitStatusListener())
                    .build();
        }

        /**
         * Reads the business date the orchestrator supplied as a job parameter.
         *
         * <p>WHY it is read from the job parameters and from nowhere else: injecting the date is
         * what makes a rerun reproducible, which is what makes a golden-master comparison possible
         * at all. {@code app/jcl/INTCALC.jcl:22} establishes the contract, passing
         * {@code PARM='2022071800'} rather than letting the program read a clock, and a step that
         * consulted a clock could not be compared against a recorded output.</p>
         *
         * @param chunkContext the running step's context; must not be {@code null}
         * @return the business date; never {@code null}
         * @throws IllegalStateException when the parameter is absent or is not a string, which
         *     means the step was started by something other than the documented command contract
         */
        private static BusinessDate businessDateOf(ChunkContext chunkContext) {
            Map<String, Object> parameters =
                    chunkContext.getStepContext().getJobParameters();
            Object supplied = parameters.get(BatchApplication.BUSINESS_DATE_PARAMETER);
            if (!(supplied instanceof String token)) {
                throw new IllegalStateException("job parameter '"
                        + BatchApplication.BUSINESS_DATE_PARAMETER + "' is absent or is not a"
                        + " string, so no business date can be resolved; a job must be started"
                        + " through the documented --business-date= option");
            }
            return new BusinessDate(token);
        }

        /**
         * A unit of work a job contributes, expressed so that the business date can only reach it
         * as a parameter.
         */
        @FunctionalInterface
        public interface StepBody {

            /**
             * Runs the unit of work.
             *
             * @param businessDate the injected business date; never {@code null}
             * @return the graded outcome, which becomes the step's exit status; must not be
             *     {@code null}
             */
            BatchReturnCode run(BusinessDate businessDate);
        }

        /**
         * Promotes a body's graded return code to the step's exit status.
         *
         * <p>WHY a listener rather than {@code StepContribution.setExitStatus}: the tasklet step
         * folds a contribution's status in with {@code ExitStatus.and}, whose severity ranking
         * maps every code beginning {@code COMPLETED} to one level. The custom warn code therefore
         * compares equal to the incumbent {@code COMPLETED} and the incumbent is kept, so the
         * grade would be silently discarded and a run with rejects would report clean. A listener
         * that returns a status replaces it outright, which is the only route that survives.</p>
         */
        private static final class ReturnCodeExitStatusListener implements StepExecutionListener {

            /**
             * Replaces the exit status when, and only when, the body graded itself as a soft warn.
             *
             * @param stepExecution the completed step execution; must not be {@code null}
             * @return the warn exit status when the body returned one, or {@code null} to leave
             *     the framework's own status in place
             */
            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                // WHY : a step that did not complete already carries a failure status that maps to
                //       the hard-failure exit code, and overwriting it with a warn would report a
                //       failed run as a run that merely had rejects.
                if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
                    return null;
                }
                int recorded = stepExecution.getExecutionContext()
                        .getInt(RETURN_CODE_KEY, BatchReturnCode.CLEAN.numericValue());
                return BatchReturnCode.fromNumericValue(recorded) == BatchReturnCode.SOFT_WARN
                        ? new ExitStatus(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS)
                        : null;
            }
        }
    }
}
