package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.CustomerRepository;
import com.carddemo.batch.repository.DailyTransactionRepository;
import com.carddemo.batch.repository.TransactionCategoryBalanceRepository;
import com.carddemo.batch.repository.TransactionRejectRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.DailyFeedWatermarkService;
import com.carddemo.batch.service.CategoryBalanceService;
import com.carddemo.batch.service.DatasetGenerationService;
import com.carddemo.batch.service.InterestCalculationService;
import com.carddemo.batch.service.PostingValidationService;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Asserts that every job name the command contract accepts resolves to a registered job bean, and
 * that no job bean exists under a name the contract does not accept.
 *
 * <p>This is the assertion the reviewed defect would have failed. The finding recorded that no job
 * beans existed at all, so every accepted value of {@code --job=} was reported as "no job is
 * registered under the name". The census is bidirectional because a one-way check passes while a job
 * is registered under a misspelled name that nothing can ever select.</p>
 */
class JobRegistrationCensusTest {

    /**
     * Builds a context runner carrying the step infrastructure, all seven job configurations and a
     * double for every collaborator they inject.
     *
     * <p>WHY doubles rather than a database: this test asks whether the beans can be BUILT and
     * NAMED, which is a wiring question. A job bean's construction reads no data -- it assembles a
     * step and returns it -- so a double is sufficient and keeps the census independent of
     * infrastructure. What the doubles cannot substitute for is the context assembly itself, which
     * is the whole point of the test.</p>
     *
     * @return the configured runner; never {@code null}
     */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        BatchConfig.class,
                        PreflightDailyTransactionsJob.class,
                        PostTransactionsJob.class,
                        CalculateInterestJob.class,
                        BackupTransactionsJob.class,
                        CombineTransactionsJob.class,
                        ExportJob.class,
                        ImportJob.class)
                .withBean(BatchStepLedger.class, () -> mock(BatchStepLedger.class))
                .withBean(JobRepository.class, () -> mock(JobRepository.class))
                .withBean(PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean(DailyTransactionRepository.class,
                        () -> mock(DailyTransactionRepository.class))
                // WHY : Assumptions: the watermark SERVICE is contributed rather than its repository,
                //       because that is the collaborator the two feed-reading jobs declare. It carries
                //       no stub behaviour for the same reason the export sources above carry none:
                //       this census asserts which beans are REGISTERED and never runs a job body.
                .withBean(DailyFeedWatermarkService.class,
                        () -> mock(DailyFeedWatermarkService.class))
                .withBean(CardXrefRepository.class, () -> mock(CardXrefRepository.class))
                // WHY : Assumptions: the customer and card projections are contributed here because
                //       ExportJob's bean method takes all five export sources. They carry no stub
                //       behaviour, because this census asserts which beans are REGISTERED and never
                //       runs a job body; a stubbed return would suggest otherwise.
                //       Refactoring Rationale: they are contributed at all because the export job now
                //       reads all five masters app/cbl/CBEXPORT.cbl reads. It previously read three, so
                //       this census could register every job bean without either seam existing -- which
                //       is exactly why the shortfall reached a review rather than a build. Each is
                //       contributed ONCE: the runner refuses a second definition of the same bean name,
                //       so a repeated contribution fails every case in this class on context start-up
                //       rather than on its own subject.
                .withBean(CustomerRepository.class, () -> mock(CustomerRepository.class))
                .withBean(CardRepository.class, () -> mock(CardRepository.class))
                .withBean(AccountRepository.class, () -> mock(AccountRepository.class))
                .withBean(TransactionRepository.class, () -> mock(TransactionRepository.class))
                .withBean(TransactionRejectRepository.class,
                        () -> mock(TransactionRejectRepository.class))
                .withBean(TransactionCategoryBalanceRepository.class,
                        () -> mock(TransactionCategoryBalanceRepository.class))
                .withBean(PostingValidationService.class, () -> mock(PostingValidationService.class))
                .withBean(CategoryBalanceService.class, () -> mock(CategoryBalanceService.class))
                .withBean(InterestCalculationService.class,
                        () -> mock(InterestCalculationService.class))
                .withBean(DatasetGenerationService.class, () -> mock(DatasetGenerationService.class))
                .withBean(S3Client.class, () -> mock(S3Client.class))
                // WHY : Refactoring Rationale: a mocked persistence context used to be contributed here
                //       because PostTransactionsJob took one through its constructor to flush a
                //       pass-wide unit of work. That job now opens ONE transaction per feed record
                //       through a TransactionTemplate over the registered transaction manager, and a
                //       transaction of its own is its own persistence context, so no job in this module
                //       takes an EntityManager and the contribution is withdrawn. Leaving it would
                //       register a bean this census claims a job needs, which is the kind of stale
                //       fixture that lets a genuinely missing collaborator pass unnoticed.
                .withPropertyValues(
                        "carddemo.dataset.bucket=carddemo-datasets-test",
                        "CARDDEMO_BATCH_RUN_ID=census-run");
    }

    /**
     * Every declared job token resolves to a job bean whose own name is that token.
     *
     * <p>The name and not the bean identifier is asserted, because the command contract resolves a
     * job by iterating the job beans and comparing {@code getName()} against the argument. A bean
     * named correctly under a differently-named bean id is selectable; a bean whose id is right and
     * whose name is wrong is not.</p>
     */
    @Test
    @DisplayName("every declared job name resolves to a registered job bean")
    void everyDeclaredJobNameResolvesToARegisteredBean() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();

            Set<String> registered = context.getBeansOfType(Job.class).values().stream()
                    .map(Job::getName)
                    .collect(Collectors.toSet());

            assertThat(registered)
                    .as("a token with no job bean is a value of --job= that fails at run time")
                    .containsAll(Arrays.stream(BatchJobName.values())
                            .map(BatchJobName::token)
                            .collect(Collectors.toSet()));
        });
    }

    /**
     * No job bean is registered under a name the command contract cannot select.
     *
     * <p>A job registered under an undeclared name can never be started, because the only route to
     * starting one is the {@code --job=} argument, which is validated against the declared tokens
     * before any bean is looked up.</p>
     */
    @Test
    @DisplayName("no job bean carries a name outside the declared tokens")
    void noJobBeanCarriesAnUndeclaredName() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();

            Set<String> declared = Arrays.stream(BatchJobName.values())
                    .map(BatchJobName::token)
                    .collect(Collectors.toSet());

            assertThat(context.getBeansOfType(Job.class).values().stream()
                    .map(Job::getName)
                    .collect(Collectors.toSet()))
                    .as("an unselectable job bean is dead weight the orchestrator cannot reach")
                    .isSubsetOf(declared);
        });
    }

    /**
     * The number of registered job beans equals the number of declared tokens.
     *
     * <p>WHY a count as well as the two set comparisons: the containment assertions both pass if two
     * job beans are registered under the same name, which Spring permits because bean ids differ.
     * Two jobs answering to one token makes which of them runs depend on bean iteration order.</p>
     */
    @Test
    @DisplayName("the registered job count equals the declared token count")
    void theRegisteredJobCountEqualsTheDeclaredTokenCount() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(Job.class))
                    .as("one job bean per declared token, and no duplicate answering to one name")
                    .hasSize(BatchJobName.values().length);
        });
    }

    /**
     * The context supplies the clock the durable step ledger requires.
     *
     * <p>WHY this is asserted separately from the census: the census uses a double for the ledger, so
     * it would still pass with no clock producer in the module. The real ledger is a component with a
     * {@code (BatchRunRepository, Clock)} constructor and the shared kernel contributes no clock, so
     * without this producer the deployed context cannot be built and no job can run -- which is
     * precisely the failure that stayed invisible while every unit test passed.</p>
     */
    @Test
    @DisplayName("the module supplies the clock the step ledger requires")
    void theModuleSuppliesTheClockTheLedgerRequires() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(Clock.class);
        });
    }

    /**
     * The step infrastructure binds the orchestrator's run identifier.
     *
     * <p>The run identifier scopes a ledger row, so tonight's run of a step is a different row from
     * last night's. Binding it from the environment variable the command contract publishes is what
     * makes a redrive of the same orchestration execution find the completed row and skip.</p>
     */
    @Test
    @DisplayName("the step builder binds the orchestrator run identifier")
    void theStepBuilderBindsTheOrchestratorRunIdentifier() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(BatchConfig.LedgerGuardedStep.class).runId())
                    .isEqualTo("census-run");
        });
    }
}
