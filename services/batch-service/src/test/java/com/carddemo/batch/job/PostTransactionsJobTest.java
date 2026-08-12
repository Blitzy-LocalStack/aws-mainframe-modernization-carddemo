package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.domain.TransactionReject;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.dto.PostingValidationResult;
import com.carddemo.batch.dto.RejectReason;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DailyTransactionRepository;
import com.carddemo.batch.repository.TransactionRejectRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.CategoryBalanceService;
import com.carddemo.batch.service.DatasetGenerationService;
import com.carddemo.batch.service.PostingValidationService;
import com.carddemo.batch.service.PostingValidationService.PostingDecision;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
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
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Pins the order the posting job applies its rules in, and the writes each outcome produces.
 *
 * <p>Purpose: this job is the one place the four decisions of {@code app/cbl/CBTRN02C.cbl} are sequenced.
 * Which conditions a record fails is settled by the sibling {@code service} tier against
 * {@code PostingValidationService}; what is settled HERE is that the cross-reference is read before the
 * account, that the account is not read at all when the card does not resolve, that an accepted record
 * produces exactly three writes and a rejected one produces exactly one, and that the step reports the
 * warn tier when any record was rejected.</p>
 *
 * <p>Assumptions: the job is RUN rather than having its private body invoked. A real job execution over an
 * in-memory job repository and a no-op transaction manager exercises the framework's own step lifecycle,
 * the parameter validator the job is built with, and the exit-status propagation from step to job -- three
 * things a direct call to a tasklet would bypass, and the third of which is how the orchestrator learns
 * the tier.</p>
 *
 * <p>Assumptions: the collaborators are mocked rather than backed by a database, because every ruling
 * below is about the ORDER and the COUNT of calls this job makes. A database would answer the same calls
 * and would additionally require four schemas this module does not own. Whether the calls reach real rows
 * belongs to the repository tier.</p>
 *
 * <p>Assumptions: the durable step ledger is stubbed to run its body rather than mocked to skip it,
 * because a mock returning a default would run nothing and every assertion below would pass vacuously.
 * The stub is the smallest faithful stand-in: it evaluates the body and reports what the body returned.</p>
 *
 * <p>Four contracts of the package charter at
 * {@code services/batch-service/src/test/java/com/carddemo/batch/job/package-info.java} land here and
 * nowhere else, because this is the only job that can reach any of them. The soft-warn tier, which
 * {@code app/cbl/CBTRN02C.cbl:229-230} is the sole producer of. The two counter lines that
 * {@code app/cbl/CBTRN02C.cbl:227-228} render. The condition-code inversion that carries that tier
 * across the step boundary at {@code app/jcl/TRANBKP.jcl:51}. And the DECLARATION of the single
 * boundary the three writes of {@code app/cbl/CBTRN02C.cbl:440-442} commit inside.</p>
 *
 * <p>Alternatives Considered: rendering the two counter lines from
 * {@code com.carddemo.batch.dto.BatchRunSummary} and asserting them there, which is where a reader
 * looking for a run's counters would go first. Rejected because that type deliberately renders
 * nothing: its own charter records that the reference programs use several different summary formats,
 * so a single renderer on the shared value would have to invent a fifth, and it assigns the
 * byte-exact colon-aligned posting format to the posting job. An assertion placed on the value type
 * would be asserting a method that does not exist, and adding one to satisfy the assertion would
 * introduce the format that type refuses to choose.</p>
 *
 * <p>Trade-offs: what this class asserts about the transactional boundary is its DECLARATION and not
 * its atomicity. With the repositories supplied as test doubles there is no unit of work to break, so
 * a commit-and-rollback assertion here would hold whatever the real propagation was. The atomicity is
 * proven where it is observable, by
 * {@code services/batch-service/src/test/java/com/carddemo/batch/repository/PostingUnitOfWorkIT.java},
 * which drives the cross-schema writes against a real database and reads the surviving rows back
 * through fresh selects. Splitting the claim across two tiers costs a reader one extra file; making
 * it in one tier that cannot fail for the right reason would cost the check itself. The cross-schema
 * write grants that case depends on are created by
 * {@code data-migration/sql/V0__schemas_and_roles.sql}; neither tier creates them.</p>
 *
 * <p>Trade-offs: the graded numeric rubric that looks applicable here is not, and the mismatch is
 * recorded because borrowing it would be easy and quiet. The parity oracle grades a run across five
 * tiers and aggregates the worst code seen, documented in section 8 of {@code tests/README.md}, and
 * section 7.1.6 of {@code services/batch-service/src/test/resources/fixtures/README.md} restates the
 * same boundary: a return code of 4 is a fixture expectation value in this repository and never a
 * build outcome. {@code com.carddemo.batch.dto.BatchReturnCode} models three tiers only and
 * deliberately omits the reference's own {@code APPL-RESULT} sentinels. What is given up is the
 * ability to report a partially successful build; what is bought is that no real failure can be
 * configured to read as an accepted warning, so every tier below is asserted as a returned value and
 * as a process exit status, never as a tolerance on a runner.</p>
 */
@DisplayName("the transaction posting job")
class PostTransactionsJobTest {

    /** The business date the cases inject, in the ten-character separated layout. */
    private static final String BUSINESS_DATE = "2022-07-18";

    /** The orchestrator execution identifier the cases run under. */
    private static final String RUN_ID = "batch-run-0001";

    /** The card number the resolvable feed record names. */
    private static final String RESOLVABLE_CARD = "4111111111111111";

    /** The card number the unresolvable feed record names. */
    private static final String UNRESOLVABLE_CARD = "4111111111112222";

    /** The account the resolvable card resolves to. */
    private static final long ACCOUNT_ID = 11111111111L;

    /** The feed ordinal the single-record cases use. */
    private static final long FIRST_ORDINAL = 1L;

    /** The job-instance identifier the cases run under, which no assertion depends on. */
    private static final long INSTANCE_ID = 1L;

    /** The job-execution identifier the cases run under, which no assertion depends on. */
    private static final long EXECUTION_ID = 1L;

    /** The instant the injected clock reads, distinct from every feed record's own stamp. */
    private static final LocalDateTime POSTED_AT = LocalDateTime.of(2022, 7, 18, 1, 2, 3);

    /** The originating stamp every feed record carries, which the posted row must reproduce. */
    private static final LocalDateTime ORIGINATED_AT = LocalDateTime.of(2022, 7, 18, 0, 0, 0);

    /** The repository-relative location of the committed posting expectation trees. */
    private static final String GOLDEN_POSTING = "tests/golden/posting";

    /** The expectation file naming a scenario's aggregate return code. */
    private static final String RETURN_CODE_FILE = "return_code.expected";

    /** The expectation file holding a scenario's reject stream. */
    private static final String REJECT_STREAM_FILE = "dalyrejs.expected";

    /** The fixed length of one reject stream record, in bytes. */
    private static final int REJECT_RECORD_BYTES = 430;

    /** The width of the copied feed image that opens a reject stream record, in bytes. */
    private static final int REJECT_PAYLOAD_BYTES = 350;

    /** The width of the zero-padded reason field on the wire, in characters. */
    private static final int REJECT_CODE_WIDTH = 4;

    /** The width of the space-padded reason description on the wire, in characters. */
    private static final int REJECT_DESC_WIDTH = 76;

    /** The feed being read. */
    private DailyTransactionRepository feed;

    /** The cross-reference being resolved through. */
    private CardXrefRepository crossReferences;

    /** The account master being updated. */
    private AccountRepository accounts;

    /** The ledger posted records are written to. */
    private TransactionRepository ledger;

    /** The reject stream rejected records are written to. */
    private TransactionRejectRepository rejects;

    /** The validation rule, mocked so each case chooses the outcome it exercises. */
    private PostingValidationService validation;

    /** The category-balance rule. */
    private CategoryBalanceService categoryBalances;

    /** The generation allocator the reject stream is staged through. */
    private DatasetGenerationService generations;

    /** The durable step ledger, stubbed to evaluate its body. */
    private BatchStepLedger ledgerOfSteps;

    /** The job under test. */
    private Job job;

    /**
     * The cross-reference the staged accepted decision carries.
     *
     * <p>Assumptions: held on the instance so a case can assert that the job passed THAT row on to the
     * category-balance service rather than one it composed itself, which is the property that keeps the
     * balance key derived from {@code XREF-ACCT-ID} as {@code app/cbl/CBTRN02C.cbl:469} requires.</p>
     */
    private CardXref stagedCrossReference;

    /** The framework's in-memory job repository. */
    private JobRepository jobRepository;

    /** The job configuration, held so a case can rebuild the job over a different collaborator. */
    private PostTransactionsJob configuration;

    /** The appender capturing what the job wrote to its own logger during one case. */
    private ListAppender<ILoggingEvent> captured;

    /** The job's own logger, held so the appender can be detached again. */
    private Logger jobLogger;

    /**
     * Builds the mocked collaborators and the job over them.
     *
     * <p>Assumptions: a fresh set is built per case rather than shared, because several cases assert call
     * counts and a shared mock would carry one case's calls into the next.</p>
     */
    @BeforeEach
    void buildJob() {
        this.feed = mock(DailyTransactionRepository.class);
        this.crossReferences = mock(CardXrefRepository.class);
        this.accounts = mock(AccountRepository.class);
        this.ledger = mock(TransactionRepository.class);
        this.rejects = mock(TransactionRejectRepository.class);
        this.validation = mock(PostingValidationService.class);
        this.categoryBalances = mock(CategoryBalanceService.class);
        this.generations = mock(DatasetGenerationService.class);
        this.ledgerOfSteps = mock(BatchStepLedger.class);
        EntityManager entityManager = mock(EntityManager.class);

        // WHY : Assumptions: the ledger stub EVALUATES the body it is handed. A default-returning mock
        //       would run none of the posting work and every count asserted below would be zero, so the
        //       cases would pass while exercising nothing at all.
        when(this.ledgerOfSteps.runStep(anyString(), anyString(), any(BatchJobName.class), any())).thenAnswer(call -> {
            BatchReturnCode outcome = call.<Supplier<BatchReturnCode>>getArgument(3).get();
            return new BatchStepLedger.StepOutcome(outcome, false);
        });

        // WHY : Assumptions: the allocator is stubbed to return a REAL generation rather than a mock's
        //       null, because the job reports the allocated number and location after staging and a
        //       null coordinate would fail every case with a NullPointerException raised from the log
        //       statement -- a failure about the stub rather than about the behaviour under test.
        when(this.generations.allocateNewGeneration(any(DatasetFamily.class),
                any(BusinessDate.class), anyString()))
                .thenAnswer(call -> new DatasetGeneration(
                        call.getArgument(0), new BusinessDate(BUSINESS_DATE), 1));
        when(this.generations.generationsToScratch(any(DatasetFamily.class))).thenReturn(List.of());
        when(this.generations.datasetUri(any(DatasetGeneration.class)))
                .thenReturn("s3://carddemo-datasets-test/ledger/dalyrejs/");
        when(this.generations.stageDataset(any(DatasetGeneration.class), anyString(),
                any(Path.class))).thenReturn("ledger/dalyrejs/dt=2022-07-18/gen=0001/dalyrejs");

        Clock clock = Clock.fixed(POSTED_AT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

        this.configuration = new PostTransactionsJob(this.feed,
                this.accounts, this.ledger, this.rejects, this.validation, this.categoryBalances,
                this.generations, this.ledgerOfSteps, clock, entityManager);

        this.jobRepository = new ResourcelessJobRepository();
        this.job = buildJobOver(new ResourcelessTransactionManager());

        // WHY : Assumptions: the two counter lines reach an operator only through the job's own
        //       logger, because app/cbl/CBTRN02C.cbl:227-228 renders them with DISPLAY and the
        //       migrated job answers that with LOG.info rather than a returned value. An appender is
        //       therefore the only vantage point from which their bytes can be read at all, and it is
        //       attached for every case so that a case asserting counts and a case asserting bytes
        //       observe the same run.
        this.jobLogger = (Logger) LoggerFactory.getLogger(PostTransactionsJob.class);
        this.captured = new ListAppender<>();
        this.captured.start();
        this.jobLogger.addAppender(this.captured);
        this.jobLogger.setLevel(Level.INFO);
    }

    /**
     * Detaches the appender so one case's captured output cannot be read by the next.
     */
    @AfterEach
    void detachAppender() {
        this.jobLogger.detachAppender(this.captured);
        this.captured.stop();
    }

    /**
     * Builds the job over one transaction manager, leaving every other collaborator as staged.
     *
     * <p>Assumptions: the transaction manager is a CONSTRUCTOR-style argument of the registration
     * method rather than a field of the configuration, because {@code PostTransactionsJob} declares
     * its boundary by handing the caller's manager to the step it builds and carries no
     * {@code Transactional} annotation. A case that needs to observe the boundary therefore has to
     * supply its own manager here, and a case looking for an annotation would find none and could
     * conclude, wrongly, that no boundary exists.</p>
     *
     * @param transactionManager the manager whose boundary the whole pass runs inside; must not be
     *     {@code null}
     * @return the registered job, never {@code null}
     */
    private Job buildJobOver(PlatformTransactionManager transactionManager) {
        JobParametersValidator validator = new BatchConfig().carddemoJobParametersValidator();
        return this.configuration.postTransactions(
                this.jobRepository, transactionManager, validator);
    }

    /**
     * An accepted record produces exactly three writes, and no reject row.
     *
     * <p>Pins {@code app/cbl/CBTRN02C.cbl:440-442}, which performs the category-balance update, then
     * the account update, then the transaction write, for every accepted record. The ORDER those three
     * are performed in, and the single boundary they commit inside, are asserted separately by
     * {@link #theThreeWritesRunInOrderInsideTheOneDeclaredBoundary()}; what is settled here is the
     * COUNT, that an accepted record produces three writes and no reject row.</p>
     *
     * <p>Refactoring Rationale: this block cited lines 441 to 443, which is off by one and named a
     * blank line as the third write. The three {@code PERFORM} statements are at 440, 441 and 442,
     * with 443 blank and 444 the paragraph's {@code EXIT}; the citation was corrected against the
     * baseline rather than carried forward, because a citation a reader cannot verify is worse than
     * none -- checking it once and finding a blank line teaches the reader to stop checking.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("write the balance, the account and the ledger for an accepted record")
    void anAcceptedRecordProducesThreeWrites() throws Exception {
        DailyTransaction feedRecord = resolvableRecord(new BigDecimal("100.00"));
        stageOneRecord(feedRecord);
        stageAcceptedDecision(new BigDecimal("100.00"));
        when(this.categoryBalances.accumulatePostedTransaction(any(), any())).thenReturn(
                new CategoryBalanceService.Outcome(CategoryBalanceService.Arm.CREATED,
                        new BigDecimal("100.00")));

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isNotEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
        // WHY : Assumptions: the assertion names the key-COMPOSING entry point and hands it the record
        //       and the cross-reference, rather than a key this test composed. The service documents
        //       itself as the entry point this job calls and derives the account component from
        //       XREF-ACCT-ID per app/cbl/CBTRN02C.cbl:469; asserting a pre-composed key here would let
        //       the job go back to composing its own without this case noticing.
        verify(this.categoryBalances).accumulatePostedTransaction(
                feedRecord, this.stagedCrossReference);
        verify(this.accounts).save(any(Account.class));
        verify(this.ledger).save(any(Transaction.class));
        verify(this.rejects, never()).save(any(TransactionReject.class));
    }

    /**
     * A rejected record produces exactly one write, to the reject stream, and the step warns.
     *
     * <p>Pins {@code app/cbl/CBTRN02C.cbl:214-215} for the single reject write and
     * {@code app/cbl/CBTRN02C.cbl:229-230} for the warn tier -- the only return-code statement in any of
     * the twelve batch programs.</p>
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("write only the reject stream for a rejected record, and warn")
    void aRejectedRecordProducesOneWriteAndWarns() throws Exception {
        DailyTransaction feedRecord = resolvableRecord(new BigDecimal("100.00"));
        stageOneRecord(feedRecord);
        stageRejectedDecision(RejectReason.OVER_CREDIT_LIMIT, new BigDecimal("100.00"));

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
        verify(this.rejects).save(any(TransactionReject.class));
        verify(this.ledger, never()).save(any(Transaction.class));
        verify(this.accounts, never()).save(any(Account.class));
    }

    /**
     * The job resolves nothing itself: one validation call per feed record, and no read of its own.
     *
     * <p>Refactoring Rationale: this case asserted that the job skipped the account read when the card
     * did not resolve, pinning {@code app/cbl/CBTRN02C.cbl:372} at the JOB level. It could only assert
     * that while the job performed the two reads and the guard between them itself -- which is exactly
     * the duplication that made the validation service's own transcription of {@code :370} unreachable
     * in production. The guard now lives in one place and is asserted there, by
     * {@code PostingValidationServiceTest.unresolvedCardLeavesTheAccountPathUnread}, which can observe
     * the skipped read because it drives the real service over stubbed repositories. What is left for
     * this level is the property that keeps it that way: the job reads NEITHER repository, so a
     * revision that reintroduced its own lookup and its own guard fails here.</p>
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("delegate every lookup, reading neither the cross-reference nor the account")
    void theJobPerformsNoLookupOfItsOwn() throws Exception {
        DailyTransaction feedRecord = record(UNRESOLVABLE_CARD, new BigDecimal("10.00"));
        stageOneRecord(feedRecord);
        stageRejectedDecision(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE, null);

        run();

        verify(this.validation, times(1)).validate(feedRecord);
        verify(this.crossReferences, never()).findByCardNum(anyString());
        verify(this.accounts, never()).findByAccountId(anyLong());
        verify(this.rejects).save(any(TransactionReject.class));
    }

    /**
     * A negative amount is ADDED to the debit total rather than subtracted from it.
     *
     * <p>Pins {@code app/cbl/CBTRN02C.cbl:543-546}, whose sign test is {@code >= 0} and whose debit branch
     * ADDS the negative amount. The debit total therefore accumulates negatively, which reads as a defect
     * and is not one: the over-limit projection at {@code app/cbl/CBTRN02C.cbl:403-405} is credit MINUS
     * debit, so subtracting here would place that projection on the other side of the credit limit.</p>
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("accumulate a negative amount into the debit total by addition")
    void aNegativeAmountAccumulatesNegativelyIntoDebit() throws Exception {
        DailyTransaction feedRecord = resolvableRecord(new BigDecimal("-25.00"));
        stageOneRecord(feedRecord);
        Account account = stageAcceptedDecision(new BigDecimal("-25.00"));
        when(this.categoryBalances.accumulatePostedTransaction(any(), any())).thenReturn(
                new CategoryBalanceService.Outcome(CategoryBalanceService.Arm.UPDATED,
                        new BigDecimal("-25.00")));

        run();

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(this.accounts).save(saved.capture());
        assertThat(saved.getValue().getCurrCycDebit()).isEqualByComparingTo("-25.00");
        assertThat(saved.getValue().getCurrCycCredit()).isEqualByComparingTo("0.00");
        assertThat(saved.getValue().getCurrBal()).isEqualByComparingTo("-25.00");
        assertThat(account).isSameAs(saved.getValue());
    }

    /**
     * An empty feed completes cleanly and writes nothing.
     *
     * <p>Pins the {@code empty_input} scenario the existing suite commits a return code of zero for. The
     * step must not warn on an empty feed: no record was rejected, so no reject occurred.</p>
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("complete cleanly over an empty feed")
    void anEmptyFeedCompletesCleanly() throws Exception {
        when(this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(anyLong(), any(Limit.class)))
                .thenReturn(List.of());

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isNotEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
        verify(this.ledger, never()).save(any(Transaction.class));
        verify(this.rejects, never()).save(any(TransactionReject.class));
    }

    /**
     * The job is refused when the run identifier is absent, because the validator requires it.
     *
     * <p>Assumptions: the refusal is asserted through the job's own validator rather than by starting the
     * job, because a job started with invalid parameters never reaches its step. What is settled is that
     * the shared validator really is attached to this job -- a job built without one would validate
     * nothing and would run with a missing parameter until the step read it.</p>
     */
    @Test
    @DisplayName("refuse to run without the run identifier")
    void aMissingRunIdentifierIsRefused() {
        JobParameters incomplete = new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER, BUSINESS_DATE, true)
                .toJobParameters();

        assertThat(this.job.getJobParametersValidator()).isNotNull();
        assertThat(catchValidation(incomplete)).isNotNull();
    }

    /**
     * Runs the job once with both required parameters and returns its execution.
     *
     * @return the completed job execution, never {@code null}
     * @throws Exception if the framework's own execution path raises
     */
    private JobExecution run() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER, BUSINESS_DATE, true)
                .addString(BatchConfig.RUN_ID_PARAMETER, RUN_ID, false)
                .toJobParameters();

        // WHY : Assumptions: the instance and the execution are constructed directly and then registered,
        //       rather than obtained from a convenience overload. The repository's own creation method
        //       takes an instance, so a test that asked it for one by name would depend on an overload
        //       that the framework does not declare on the interface this job is built against.
        JobInstance instance = new JobInstance(INSTANCE_ID, PostTransactionsJob.JOB_NAME);
        JobExecution execution = new JobExecution(EXECUTION_ID, instance, parameters);
        this.jobRepository.update(execution);
        this.job.execute(execution);
        return execution;
    }

    /**
     * Reports the exception the job's validator raises for a set of parameters, or {@code null}.
     *
     * @param parameters the parameters to validate; must not be {@code null}
     * @return the raised exception, or {@code null} when the parameters were accepted
     */
    private Exception catchValidation(JobParameters parameters) {
        try {
            this.job.getJobParametersValidator().validate(parameters);
            return null;
        } catch (Exception refused) {
            return refused;
        }
    }

    /**
     * The reject stream is staged into a newly allocated generation even when nothing was rejected.
     *
     * <p>Pins {@code app/jcl/POSTTRAN.jcl:34-38}, which declares the reject DD
     * {@code DISP=(NEW,CATLG,DELETE)} against {@code DALYREJS(+1)} -- so a generation is created on
     * every run regardless of the reject count. The committed goldens are the corroboration: each of
     * the five clean scenarios holds a ZERO-BYTE {@code dalyrejs.expected} rather than no file, so a
     * job that skipped the allocation when the count was zero would leave a downstream reader facing
     * an absent generation where the reference leaves an empty one.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("allocate and stage the reject generation even on a pass with no rejects")
    void aCleanPassStillStagesAnEmptyRejectGeneration() throws Exception {
        stageOneRecord(resolvableRecord(new BigDecimal("100.00")));
        stageAcceptedDecision(new BigDecimal("100.00"));

        JobExecution execution = run();

        assertThat(execution.getExitStatus().getExitCode())
                .isNotEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
        verify(this.generations)
                .allocateNewGeneration(eq(DatasetFamily.DALYREJS), any(BusinessDate.class), anyString());
        ArgumentCaptor<Path> staged = ArgumentCaptor.forClass(Path.class);
        verify(this.generations).stageDataset(
                any(DatasetGeneration.class), anyString(), staged.capture());
        assertThat(Files.exists(staged.getValue())).isFalse();
    }

    /**
     * A rejected record reaches the staged dataset as exactly one 430-byte record with its trailer.
     *
     * <p>Pins the fixed-length contract {@code app/jcl/POSTTRAN.jcl:36} states as
     * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)}, and the trailer layout at
     * {@code app/cbl/CBTRN02C.cbl:180-182} -- a four-digit zero-padded reason followed by its
     * 76-character space-padded description. The committed golden
     * {@code tests/golden/posting/reject_102_overlimit/dalyrejs.expected} is 430 bytes whose trailer
     * reads {@code 0102OVERLIMIT TRANSACTION}, which is the exact byte sequence asserted here.</p>
     *
     * <p>Assumptions: the payload is captured from the staging call rather than read back from the
     * temporary file, because the job deletes that file on every path -- including success -- so a
     * read afterwards would find nothing. Capturing during the call is what observes the bytes that
     * actually left.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("stage exactly one 430-byte reject record carrying the reason trailer")
    void aRejectedRecordReachesTheDatasetAsOne430ByteRecord() throws Exception {
        stageOneRecord(resolvableRecord(new BigDecimal("100.00")));
        stageRejectedDecision(RejectReason.OVER_CREDIT_LIMIT, new BigDecimal("100.00"));

        byte[][] captured = new byte[1][];
        when(this.generations.stageDataset(
                any(DatasetGeneration.class), anyString(), any(Path.class)))
                .thenAnswer(call -> {
                    captured[0] = Files.readAllBytes(call.<Path>getArgument(2));
                    return "a/staged/key";
                });

        run();

        assertThat(captured[0]).hasSize(REJECT_RECORD_BYTES);
        assertThat(new String(captured[0], StandardCharsets.ISO_8859_1)
                .substring(REJECT_PAYLOAD_BYTES))
                .isEqualTo(RejectReason.OVER_CREDIT_LIMIT.trailerField());
        assertThat(new String(captured[0], StandardCharsets.ISO_8859_1)
                .substring(REJECT_PAYLOAD_BYTES, REJECT_PAYLOAD_BYTES + REJECT_CODE_WIDTH))
                .isEqualTo("0102");
    }

    /**
     * The retention rule is applied after staging, scratching whatever aged out of the window.
     *
     * <p>Pins {@code app/jcl/DALYREJS.jcl:25-27}, which defines the base with {@code LIMIT(5)} paired
     * with an explicit {@code SCRATCH} -- so an aged-out generation is deleted rather than merely
     * uncatalogued. The pairing is what this case observes: a job that allocated and staged but never
     * scratched would grow the family without bound.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("scratch the generations that aged out of the five-generation window")
    void agedOutRejectGenerationsAreScratched() throws Exception {
        stageOneRecord(resolvableRecord(new BigDecimal("100.00")));
        stageAcceptedDecision(new BigDecimal("100.00"));

        DatasetGeneration agedOut =
                new DatasetGeneration(DatasetFamily.DALYREJS, new BusinessDate(BUSINESS_DATE), 1);
        when(this.generations.generationsToScratch(DatasetFamily.DALYREJS))
                .thenReturn(List.of(agedOut));

        run();

        verify(this.generations).scratchGeneration(agedOut);
    }

    /**
     * The job this class drives is the one the orchestrator selects by name.
     *
     * <p>Assumptions: the bidirectional census -- every published token has a job, and every job sits
     * inside the vocabulary -- belongs to {@code JobRegistrationCensusTest}, which observes bean
     * registration, and to {@code BatchJobRosterTest}, which reads the same agreement by reflection.
     * What is settled here is narrower and is not available to either: that the job THIS class builds
     * and launches reports the posting token, so a rename cannot quietly redirect every case in this
     * file onto a different job while they all continue to pass.</p>
     */
    @Test
    @DisplayName("register under the posting token the orchestrator selects")
    void theJobRegistersUnderThePublishedToken() {
        assertThat(this.job.getName()).isEqualTo("post-transactions");
        assertThat(PostTransactionsJob.JOB_NAME)
                .isEqualTo(BatchJobName.POST_TRANSACTIONS.token());
        assertThat(PostTransactionsJob.STEP_NAME)
                .isEqualTo(PostTransactionsJob.JOB_NAME + BatchJobName.STEP_NAME_SUFFIX);
    }

    /**
     * Both counter lines are rendered verbatim, in reference order, with nine-digit counts.
     *
     * <p>Pins {@code app/cbl/CBTRN02C.cbl:227} and {@code app/cbl/CBTRN02C.cbl:228} for the two
     * literals and their order, and {@code app/cbl/CBTRN02C.cbl:185-186} for the width of the counts,
     * where {@code WS-TRANSACTION-COUNT} and {@code WS-REJECT-COUNT} are both declared
     * {@code PIC 9(09)}.</p>
     *
     * <p>Assumptions: the spacing before the two colons is ASYMMETRIC in the reference and the
     * asymmetry is the whole point. Line 227 spells {@code 'TRANSACTIONS PROCESSED :'} with ONE space
     * and line 228 spells {@code 'TRANSACTIONS REJECTED  :'} with TWO, because {@code PROCESSED} is one
     * character longer than {@code REJECTED}; the extra space is what makes both labels 24 characters
     * wide so the colons line up under a fixed-pitch terminal. Asserting the two widths as equal states
     * that property rather than merely restating the literals -- a "tidied" symmetrical pair would keep
     * both literals looking right and would fail this line. These two lines are observable output, so
     * the job may not filter, reformat, prefix or wrap them.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("render both counter lines verbatim, processed before rejected")
    void bothCounterLinesAreRenderedVerbatimInReferenceOrder() throws Exception {
        stageRecords(resolvableRecord(new BigDecimal("100.00"), 1L),
                record(UNRESOLVABLE_CARD, new BigDecimal("10.00"), 2L));
        stageDecisionRoutedByCard(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE);

        run();

        assertThat(PostTransactionsJob.PROCESSED_LABEL).isEqualTo("TRANSACTIONS PROCESSED :");
        assertThat(PostTransactionsJob.REJECTED_LABEL).isEqualTo("TRANSACTIONS REJECTED  :");
        assertThat(PostTransactionsJob.REJECTED_LABEL.length())
                .isEqualTo(PostTransactionsJob.PROCESSED_LABEL.length());
        assertThat(PostTransactionsJob.COUNTER_DIGITS).isEqualTo(9);

        assertThat(loggedLines())
                .containsSubsequence("TRANSACTIONS PROCESSED :000000002",
                        "TRANSACTIONS REJECTED  :000000001");
    }

    /**
     * An empty feed renders both counts as nine zeros rather than omitting either line.
     *
     * <p>Pins the {@code empty_input} scenario, whose committed
     * {@code tests/golden/posting/empty_input/return_code.expected} holds {@code 0} and whose
     * {@code dalyrejs.expected} is zero bytes. The reference reaches
     * {@code app/cbl/CBTRN02C.cbl:227-228} unconditionally, after the read loop rather than inside it,
     * so a run that read nothing still reports two lines.</p>
     *
     * <p>Assumptions: this is where an off-by-one in the padding shows up cleanly, which is why the
     * scenario is exercised rather than dismissed as trivial. Nine zeros is the only rendering a
     * {@code PIC 9(09)} counter can produce for zero; a one-character {@code 0}, an empty field or an
     * absent line would each be a silent divergence that no non-empty feed would reveal, because a
     * non-zero count masks a padding error in its own digits.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("render nine zeros in both counters over an empty feed")
    void anEmptyFeedRendersBothCountsAsNineZeros() throws Exception {
        when(this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(anyLong(), any(Limit.class)))
                .thenReturn(List.of());

        run();

        assertThat(loggedLines())
                .containsSubsequence("TRANSACTIONS PROCESSED :000000000",
                        "TRANSACTIONS REJECTED  :000000000");
    }

    /**
     * A single reject among several accepted records is already enough to warn.
     *
     * <p>Pins {@code app/cbl/CBTRN02C.cbl:229-230}, whose test is {@code IF WS-REJECT-COUNT > 0}
     * against zero and not against a threshold or a proportion.</p>
     *
     * <p>Refactoring Rationale: the tier is 4 rather than 8 because a run that correctly refused a
     * transaction has done its work, so treating it as a failure would stop a nightly chain over an
     * ordinary business outcome; and it is 4 rather than 0 because the downstream state has to learn
     * that rejects were written. Its consumer is {@code app/jcl/TRANBKP.jcl:51},
     * {@code //STEP10 EXEC PGM=IDCAMS,COND=(4,LT)}, the only condition of that form in the
     * thirty-eight files of {@code app/jcl}. Collapsing the tier in either direction changes which
     * downstream steps run.</p>
     *
     * <p>Assumptions: the two observable outputs of one run must agree with each other -- the tier the
     * step reports and the count the rejected line renders. The value type
     * {@code com.carddemo.batch.dto.BatchRunSummary} enforces the same biconditional on its own
     * components and {@code BatchRunSummaryTest} settles all four of its quadrants, so what is checked
     * here is not that rule but this job's compliance with it: a job that warned while rendering a
     * zero count, or rendered a positive count while reporting clean, would satisfy every case in that
     * file and still be wrong.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("warn on exactly one reject among several accepted records")
    void oneRejectAmongSeveralAcceptedRecordsStillWarns() throws Exception {
        stageRecords(resolvableRecord(new BigDecimal("100.00"), 1L),
                record(UNRESOLVABLE_CARD, new BigDecimal("10.00"), 2L),
                resolvableRecord(new BigDecimal("30.00"), 3L));
        stageDecisionRoutedByCard(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE);

        JobExecution execution = run();

        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
        assertThat(loggedLines())
                .containsSubsequence("TRANSACTIONS PROCESSED :000000003",
                        "TRANSACTIONS REJECTED  :000000001");
        verify(this.rejects, times(1)).save(any(TransactionReject.class));
        verify(this.ledger, times(2)).save(any(Transaction.class));
    }

    /**
     * The tier a warned run reaches is the process exit status the orchestrator reads.
     *
     * <p>Pins the two halves of the translation the deployed task performs. A warned run leaves the
     * framework's execution {@code COMPLETED} while carrying
     * {@link BatchApplication#EXIT_CODE_COMPLETED_WITH_WARNINGS}, which is the exact pair
     * {@code BatchApplication} maps onto {@link BatchApplication#EXIT_STATUS_SOFT_WARN}; and the three
     * tiers of {@code com.carddemo.batch.dto.BatchReturnCode} carry the same three numbers the process
     * exits with.</p>
     *
     * <p>Assumptions: the completed-plus-warning PAIR is asserted rather than the exit code alone,
     * because the mapping consults the batch status first: an execution that failed mid-pass can still
     * be carrying whatever exit code a step set before it failed, so a case that read only the exit
     * code would accept a half-posted run as a warning. The mapping function itself is package-private
     * to {@code com.carddemo.batch} and is settled by {@code BatchApplicationTest}, which sits in that
     * package; this case asserts the state this job hands it, which is the half that file cannot
     * observe because it never runs a job.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("leave a warned run in the exact state the exit-status mapping reads as 4")
    void theWarnTierIsAlsoTheProcessExitStatus() throws Exception {
        stageOneRecord(resolvableRecord(new BigDecimal("100.00")));
        stageRejectedDecision(RejectReason.OVER_CREDIT_LIMIT, new BigDecimal("100.00"));

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);

        assertThat(BatchReturnCode.CLEAN.numericValue())
                .isEqualTo(BatchApplication.EXIT_STATUS_CLEAN);
        assertThat(BatchReturnCode.SOFT_WARN.numericValue())
                .isEqualTo(BatchApplication.EXIT_STATUS_SOFT_WARN);
        assertThat(BatchReturnCode.HARD_FAILURE.numericValue())
                .isEqualTo(BatchApplication.EXIT_STATUS_HARD_FAILURE);
    }

    /**
     * A pass that abends is distinguishable from a pass that warned.
     *
     * <p>Pins the boundary between {@code app/cbl/CBTRN02C.cbl:229-230} and the reference's abend path.
     * A rejected record is a business outcome and reaches the warn tier; a pass that cannot complete
     * reaches the failure tier, and the two must not be conflated in either direction. Collapsing warn
     * into failure would stop the nightly chain over an ordinary reject, and collapsing failure into
     * warn would let {@code app/jcl/TRANBKP.jcl:51} wave a half-posted pass downstream.</p>
     *
     * <p>Assumptions: the failure is provoked by making a collaborator raise rather than by asserting
     * on a mocked return code, because the tier has to be reached the way production reaches it -- the
     * exception leaves the tasklet, the framework marks the execution, and the exit code is whatever
     * that leaves behind. A case that stubbed the failure tier directly would assert nothing about
     * this job's own behaviour under a raising collaborator.</p>
     *
     * @throws Exception if the framework's own execution path raises, which this case provokes
     *     deliberately and the framework records on the execution rather than rethrowing
     */
    @Test
    @DisplayName("report the failure tier, not the warn tier, when the pass cannot complete")
    void anAbendedPassIsDistinguishableFromAWarnedPass() throws Exception {
        stageOneRecord(resolvableRecord(new BigDecimal("100.00")));
        when(this.validation.validate(any(DailyTransaction.class)))
                .thenThrow(new IllegalStateException("the account master is unreadable"));

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitCode())
                .isNotEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
        assertThat(BatchReturnCode.HARD_FAILURE.numericValue())
                .isGreaterThan(BatchReturnCode.SOFT_WARN.numericValue());
        verify(this.ledger, never()).save(any(Transaction.class));
    }

    /**
     * Both the clean and the warn tier permit the downstream state to run; the failure tier does not.
     *
     * <p>Refactoring Rationale: the sense of every migrated gate is INVERTED, and this is the one
     * place the inversion is asserted. A JCL {@code COND} is a SKIP predicate and a state machine
     * {@code Choice} is a RUN predicate, so {@code app/jcl/TRANBKP.jcl:51},
     * {@code //STEP10 EXEC PGM=IDCAMS,COND=(4,LT)}, reads "skip this step when 4 is less than the
     * accumulated return code" and therefore inverts to "run while the code is 4 or lower". Producer
     * and consumer are both named because the tier is meaningless without the pair:
     * {@code app/cbl/CBTRN02C.cbl:229-230} is the only statement in the reference batch chain that
     * produces a 4, and that condition is the only one anywhere in the thirty-eight files of
     * {@code app/jcl} that consumes it. Transcribing the predicate with its original sense would
     * invert which runs proceed, and every clean run would be the one that stopped the chain.</p>
     *
     * <p>Assumptions: one baseline construct looks like a step gate and is not, and conflating the two
     * is a real hazard because they share the {@code COND} keyword. {@code app/jcl/TRANREPT.jcl:47}
     * reads {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,} and continues on line 48,
     * inside the control statements of the sort step declared at {@code app/jcl/TRANREPT.jcl:37}, so it
     * selects RECORDS rather than steps: its migrated form is a SQL predicate over the processing date.
     * Modelling it as a run predicate would gate an entire step on a condition written to filter rows,
     * so no assertion here treats a record filter as a step result or the reverse.</p>
     */
    @Test
    @DisplayName("permit the downstream state on the clean and warn tiers only")
    void everyTierAnswersTheInvertedDownstreamRunPredicate() {
        assertThat(BatchReturnCode.CLEAN.permitsDownstreamRun()).isTrue();
        assertThat(BatchReturnCode.SOFT_WARN.permitsDownstreamRun()).isTrue();
        assertThat(BatchReturnCode.HARD_FAILURE.permitsDownstreamRun()).isFalse();
    }

    /**
     * The three writes are issued in reference order, inside the one boundary the job declares.
     *
     * <p>Pins {@code app/cbl/CBTRN02C.cbl:440-442} for the order -- {@code 2700-UPDATE-TCATBAL}, then
     * {@code 2800-UPDATE-ACCOUNT-REC}, then {@code 2900-WRITE-TRANSACTION-FILE} -- and the paragraph
     * they sit in, {@code app/cbl/CBTRN02C.cbl:424-444}, for the unit of work that spans them.</p>
     *
     * <p>Assumptions: the boundary is declared by CONSTRUCTION and not by annotation, so a recording
     * transaction manager is the only way to observe it. {@code PostTransactionsJob} builds its tasklet
     * with the manager its caller hands in and carries no {@code Transactional} annotation, and the
     * sibling service charter fixes the matching invariant from the other side -- NO method in the
     * production {@code com.carddemo.batch.service} package is annotated {@code Transactional},
     * precisely so this one file remains the boundary's only owner. That is why a {@code ServiceTest}
     * may not assert a transaction boundary and why this case exists here.</p>
     *
     * <p>Assumptions: the order is asserted even though a single commit makes it invisible to any
     * reader of the committed state, because it still fixes the sequence in which row locks are taken.
     * Every posting task in the fleet taking them in one order is what keeps two concurrent tasks from
     * deadlocking on the same pair, and a reordering would be a silent divergence from the paragraph
     * this job claims to reproduce.</p>
     *
     * <p>Alternatives Considered: a saga, and a transactional outbox with compensating reversals, were
     * both evaluated for these three writes and both rejected. Either would replace one atomic commit
     * with a sequence of separately committed steps, which makes partial states OBSERVABLE that do not
     * exist in the reference -- a posted transaction with an unposted balance, or an updated account
     * with no ledger row -- and the committed expectation trees would correctly flag every one of them
     * as a parity failure. What was chosen instead is a dedicated database role holding narrowly scoped
     * cross-schema write grants on {@code ledger} and {@code account} only, which keeps the unit of
     * work a single ACID commit; AAP section 0.4.1.3 records it as the one deliberate exception to
     * database-per-service purity in the whole migration.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("issue the balance, account and ledger writes in order inside the boundary")
    void theThreeWritesRunInReferenceOrderInsideTheDeclaredBoundary() throws Exception {
        PlatformTransactionManager transactions = spy(new ResourcelessTransactionManager());
        this.job = buildJobOver(transactions);

        // WHY : Assumptions: exactly ONE record is posted, and the count is load-bearing rather than
        //       incidental. Ordered verification finds a matching SUBSEQUENCE, so over two records the
        //       account write of the first and the ledger write of the second form an ascending pair
        //       whatever order each record's own writes were issued in -- measured: a job with the two
        //       writes transposed satisfies the sequence below over two records and fails it over one.
        //       The per-pass extent of the boundary is asserted separately, where more than one record
        //       is what makes the claim observable.
        stageOneRecord(resolvableRecord(new BigDecimal("100.00")));
        stageAcceptedDecision(new BigDecimal("100.00"));

        run();

        // WHY : Assumptions: one ordered verification covers both claims at once, because the boundary
        //       and the order are the same observation read at different granularities -- the three
        //       writes fall between the same pair of manager calls, in the reference sequence. Two
        //       separate verifications could each hold while the writes straddled a commit.
        InOrder sequence = inOrder(
                transactions, this.categoryBalances, this.accounts, this.ledger);
        sequence.verify(transactions).getTransaction(any());
        sequence.verify(this.categoryBalances).accumulatePostedTransaction(any(), any());
        sequence.verify(this.accounts).save(any(Account.class));
        sequence.verify(this.ledger).save(any(Transaction.class));
        sequence.verify(transactions).commit(any());
        verify(transactions, never()).rollback(any());
    }

    /**
     * The whole pass runs inside one boundary rather than one boundary per record.
     *
     * <p>Pins the extent of the paragraph at {@code app/cbl/CBTRN02C.cbl:424-444} as a step-wide unit
     * of work. The migrated job declares its boundary once, on the step it builds, so a pass over
     * several records opens one transaction and not one per record.</p>
     *
     * <p>Assumptions: more than one record is required for the claim to be observable at all -- with a
     * single record a per-pass boundary and a per-record boundary are the same single transaction. A job
     * that opened one per record would still write every row and still report the same tier, so the only
     * thing that distinguishes the two shapes is the count of begins.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("open one transaction for the whole pass, not one for each record")
    void theWholePassRunsInsideOneBoundary() throws Exception {
        // WHY : Alternatives Considered: a bare mock transaction manager, which is the first thing to
        //       reach for and does not work here. The framework's tasklet step registers a transaction
        //       synchronization inside the boundary, and only a real manager activates synchronization
        //       when it begins one -- a mock returns a status without doing so, and the step then fails
        //       on the registration before it reaches the first write, so the case would fail for a
        //       reason unrelated to the property under assertion. A spy over the manager the other
        //       cases use keeps the real begin-and-commit behaviour and records the calls as well.
        PlatformTransactionManager transactions = spy(new ResourcelessTransactionManager());
        this.job = buildJobOver(transactions);

        stageRecords(resolvableRecord(new BigDecimal("100.00"), 1L),
                resolvableRecord(new BigDecimal("30.00"), 2L),
                record(UNRESOLVABLE_CARD, new BigDecimal("10.00"), 3L));
        stageDecisionRoutedByCard(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE);

        run();

        verify(transactions, times(1)).getTransaction(any());
        verify(transactions, times(1)).commit(any());
        verify(transactions, never()).rollback(any());
        verify(this.ledger, times(2)).save(any(Transaction.class));
        verify(this.rejects, times(1)).save(any(TransactionReject.class));
    }

    /**
     * A posted row keeps the feed's originating stamp and takes its processing stamp from the clock.
     *
     * <p>Pins the two lines that treat the two 26-byte stamps differently.
     * {@code app/cbl/CBTRN02C.cbl:436} moves {@code DALYTRAN-ORIG-TS} into {@code TRAN-ORIG-TS} with no
     * reformatting, so that field is a passthrough and deterministic;
     * {@code app/cbl/CBTRN02C.cbl:437-438} performs {@code Z-GET-DB2-FORMAT-TIMESTAMP} and moves the
     * result into {@code TRAN-PROC-TS}, so that field is a clock reading. Section 8.1 of
     * {@code services/batch-service/src/test/resources/fixtures/README.md} states the resulting
     * comparison rule for the posting domain and is cited rather than restated.</p>
     *
     * <p>Assumptions: the two stamps are given DIFFERENT values by construction -- the feed record
     * originates at midnight and the injected clock reads one hour after it -- so that a conflation is
     * caught. Were they equal, an implementation that overwrote the originating stamp with the clock
     * reading, or copied the feed stamp into both, would satisfy both assertions. Masking both fields
     * for comparison would have the same effect more quietly: it would stop testing the passthrough
     * altogether, which is why the posting rule masks only the processing stamp.</p>
     *
     * <p>Assumptions: this is the only clock read the pass performs, and it is legitimate because the
     * field it feeds is by definition the moment of posting. The business date is deliberately NOT
     * substituted for it, even though that would make the field deterministic, because it would persist
     * a stamp the reference never writes; determinism is recovered at comparison time by masking, and
     * the clock is injected so that this case can pin the value instead of tolerating a range.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("carry the feed originating stamp across and stamp processing from the clock")
    void aPostedRowKeepsTheOriginatingStampAndTakesProcessingFromTheClock() throws Exception {
        DailyTransaction feedRecord = resolvableRecord(new BigDecimal("100.00"));
        stageOneRecord(feedRecord);
        stageAcceptedDecision(new BigDecimal("100.00"));

        run();

        ArgumentCaptor<Transaction> posted = ArgumentCaptor.forClass(Transaction.class);
        verify(this.ledger).save(posted.capture());
        assertThat(posted.getValue().getOrigTs()).isEqualTo(feedRecord.getOrigTs());
        assertThat(posted.getValue().getOrigTs()).isEqualTo(ORIGINATED_AT);
        assertThat(posted.getValue().getProcTs()).isEqualTo(POSTED_AT);
        assertThat(posted.getValue().getProcTs()).isNotEqualTo(posted.getValue().getOrigTs());
    }

    /**
     * The pass is recorded under the orchestrator's run identifier and this step's own name.
     *
     * <p>Refactoring Rationale: the durable step record is an IMPROVEMENT on the reference rather than
     * a port of anything, and saying so matters because a reader looking for the construct it replaces
     * will not find one. The only {@code RESTART=} anywhere in {@code app/jcl} is commented out, at
     * {@code app/jcl/DEFGDGD.jcl:2}, and a search of all thirty-eight files finds no {@code CHKPT=} at
     * all -- so the reference carries no checkpoint contract to preserve. The pair the ledger is keyed
     * by is what makes a redriven step decidable: the run identifier names the orchestrator execution
     * and the step name names the state within it, so the same pair arriving twice is one step being
     * retried rather than two steps that happen to look alike.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("record the pass under the run identifier and this step's name")
    void thePassIsRecordedUnderTheRunIdentifierAndStepName() throws Exception {
        stageOneRecord(resolvableRecord(new BigDecimal("100.00")));
        stageAcceptedDecision(new BigDecimal("100.00"));

        run();

        verify(this.ledgerOfSteps).runStep(eq(RUN_ID), eq(PostTransactionsJob.STEP_NAME),
                eq(BatchJobName.POST_TRANSACTIONS), any());
    }

    /**
     * A redriven step reports the tier the first attempt reached and does no work again.
     *
     * <p>Pins the consequence of {@code app/cbl/CBTRN02C.cbl:229-230} for a retried state: the tier is
     * a property of the pass that wrote the rejects, so a redrive of a step the ledger already holds
     * has to report that recorded tier rather than the tier a run of no records would compute.</p>
     *
     * <p>Assumptions: recomputing the tier on a redrive would report CLEAN for a step that had
     * legitimately warned, because the second attempt writes nothing and would count no rejects. The
     * orchestrator's downstream choice reads that tier, so the recomputed value would wave a pass that
     * produced rejects onward as though it had produced none -- and the reject stream would then be the
     * only place they appeared.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("report the recorded tier on a redrive without repeating the pass")
    void aRedrivenStepReportsTheRecordedTierWithoutRepeatingThePass() throws Exception {
        stageOneRecord(resolvableRecord(new BigDecimal("100.00")));
        stageAcceptedDecision(new BigDecimal("100.00"));

        // WHY : Assumptions: this stub does NOT evaluate the body it is handed, which is the whole
        //       point of the case -- it is the ledger reporting a step it already holds, so the body
        //       must not run. Every other case in this file uses the evaluating stub built in setup.
        when(this.ledgerOfSteps.runStep(anyString(), anyString(), any(BatchJobName.class), any()))
                .thenReturn(new BatchStepLedger.StepOutcome(BatchReturnCode.SOFT_WARN, true));

        JobExecution execution = run();

        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
        verify(this.validation, never()).validate(any(DailyTransaction.class));
        verify(this.ledger, never()).save(any(Transaction.class));
        verify(this.accounts, never()).save(any(Account.class));
        verify(this.rejects, never()).save(any(TransactionReject.class));
    }

    /**
     * The job is refused when the business date is absent, even though its reference step carries none.
     *
     * <p>Assumptions: this is a documented DIVERGENCE from the reference rather than a transcription of
     * it, and the divergence is deliberate. {@code app/jcl/POSTTRAN.jcl:23} is
     * {@code //STEP15 EXEC PGM=CBTRN02C} with no {@code PARM=} at all -- the only {@code PARM=} in the
     * batch chain belongs to the interest job at {@code app/jcl/INTCALC.jcl:22} -- so the reference
     * program receives no date and derives no posted field from one, and the migrated job likewise
     * derives no posted field from it. It is nevertheless REQUIRED here, for two reasons the reference
     * had other mechanisms for: it is an identifying job parameter, so one night is a distinct job
     * instance from the next rather than a rerun of the same one; and it partitions the reject stream's
     * generation, which is what lets a rerun land in a deterministic generation instead of one keyed
     * off a clock. A case that treated the option as optional for this job would contradict the
     * validator it is driving.</p>
     *
     * <p>Assumptions: the refusal is read from the job's own validator rather than by starting the job,
     * because a job started with invalid parameters never reaches its step, so there would be nothing
     * to observe. What is settled is that the shared validator really is attached to THIS job -- a job
     * built without one would validate nothing and would run until the step read a parameter that was
     * not there.</p>
     */
    @Test
    @DisplayName("refuse to run without the business date")
    void aMissingBusinessDateIsRefused() {
        JobParameters incomplete = new JobParametersBuilder()
                .addString(BatchConfig.RUN_ID_PARAMETER, RUN_ID, false)
                .toJobParameters();

        assertThat(this.job.getJobParametersValidator()).isNotNull();
        assertThat(catchValidation(incomplete)).isNotNull();
    }

    /**
     * The queryable reject row decomposes the same 430-byte record the stream carries.
     *
     * <p>Pins the decomposition of the record written at {@code app/cbl/CBTRN02C.cbl:446-448} into the
     * three columns the migrated schema holds -- the copied feed image as a fixed 350-character
     * column, the reason as a small integer, and its description as a bounded string. Section 7.1.2 of
     * {@code services/batch-service/src/test/resources/fixtures/README.md} fixes the distinction this
     * case reads: the reason is FOUR characters zero-padded on the wire and a {@code SMALLINT} in
     * storage, so {@code 0102} and {@code 102} are the same reason in two representations.</p>
     *
     * <p>Assumptions: the two representations are compared against EACH OTHER, taken from the same run,
     * and against no literal of their own. That is what keeps this case out of the sibling tiers'
     * territory -- the reject texts, the precedence between reasons and the field-level rendering of
     * the trailer are settled there, and a literal repeated here would be a second copy of a contract
     * that already has an owner. What only this level can see is whether the row the job made queryable
     * and the record the job put on the wire describe the same reject.</p>
     *
     * <p>Assumptions: the trailer is reconstructed from the row's two columns at their declared widths
     * rather than compared with the padding stripped off. Stripping would discard the very padding the
     * fixed-length record consists of, and the widths would then go unasserted; composing them instead
     * states positively that four plus seventy-six is the eighty bytes that follow the payload, which is
     * the arithmetic {@code app/jcl/POSTTRAN.jcl:36} fixes at 430 for the record as a whole.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("decompose the reject row into the payload, the coded reason and its description")
    void theRejectRowDecomposesTheSameRecordTheStreamCarries() throws Exception {
        stageOneRecord(resolvableRecord(new BigDecimal("100.00")));
        stageRejectedDecision(RejectReason.OVER_CREDIT_LIMIT, new BigDecimal("100.00"));

        byte[][] streamed = new byte[1][];
        when(this.generations.stageDataset(
                any(DatasetGeneration.class), anyString(), any(Path.class)))
                .thenAnswer(call -> {
                    streamed[0] = Files.readAllBytes(call.<Path>getArgument(2));
                    return "a/staged/key";
                });

        run();

        ArgumentCaptor<TransactionReject> row = ArgumentCaptor.forClass(TransactionReject.class);
        verify(this.rejects).save(row.capture());
        String streamRecord = new String(streamed[0], StandardCharsets.ISO_8859_1);

        assertThat(row.getValue().getRawRecord()).hasSize(REJECT_PAYLOAD_BYTES);
        assertThat(streamRecord.substring(0, REJECT_PAYLOAD_BYTES))
                .isEqualTo(row.getValue().getRawRecord());

        String trailer = String.format("%0" + REJECT_CODE_WIDTH + "d%-" + REJECT_DESC_WIDTH + "s",
                row.getValue().getReasonCode(), row.getValue().getReasonDesc());
        assertThat(trailer).hasSize(REJECT_RECORD_BYTES - REJECT_PAYLOAD_BYTES);
        assertThat(streamRecord.substring(REJECT_PAYLOAD_BYTES)).isEqualTo(trailer);
    }

    /**
     * Every committed posting scenario's return code maps onto the tier its reject stream implies.
     *
     * <p>Pins all nine expectation trees under {@code tests/golden/posting} at once. Each holds a
     * {@code return_code.expected} naming the aggregate return code the reference reached and a
     * {@code dalyrejs.expected} holding the reject stream it produced, and the relationship between
     * them is exactly {@code app/cbl/CBTRN02C.cbl:229-230}: the warn tier appears in a tree when, and
     * only when, that tree's reject stream is non-empty. Measured across the nine: the four
     * {@code reject_10x_*} trees each hold {@code 4} with a 431-byte stream, and {@code happy_path},
     * {@code empty_input}, {@code zero_balance}, {@code boundary_exact_limit} and
     * {@code boundary_expiry_equal} each hold {@code 0} with a zero-byte stream.</p>
     *
     * <p>Assumptions: the two {@code boundary_*} trees corroborate the two inclusive boundaries from
     * the outside, which is stronger evidence than reading either condition in isolation.
     * {@code boundary_exact_limit} holding {@code 0} says a balance exactly AT the credit limit posts,
     * and {@code boundary_expiry_equal} holding {@code 0} says a transaction dated equal to the
     * expiration date posts. The conditions themselves are transcribed and asserted by the sibling
     * service tier; this case observes their outcomes and deliberately re-derives neither predicate.</p>
     *
     * <p>Assumptions: the stream sizes are one byte longer than the record they hold, because the
     * comparison stores one record per line and the end-of-file newline is canonicalised. A non-empty
     * stream is therefore asserted as 430 plus one rather than as 430, and trailing padding is never
     * stripped to make the arithmetic come out.</p>
     *
     * <p>Refactoring Rationale: these files are read STRICTLY READ-ONLY and this class exposes no way
     * to rewrite one -- no environment switch, no update argument, no write-if-missing branch. The
     * parity oracle does ship such a gate, multiply guarded and requiring the diff to be reviewed,
     * documented in section 12 of {@code tests/README.md}, and the asymmetry is deliberate: that
     * suite's expectations describe the REFERENCE, whose behaviour is fixed, so regenerating one
     * records a corrected reading of an unchanged program. A switch here would instead rewrite the
     * expectation to match whatever the migrated code currently produces, which converts this module's
     * one independent check into a restatement of its own output.</p>
     *
     * @param scenario the expectation tree's directory name under {@code tests/golden/posting}; must
     *     name a committed directory
     * @param expectedTier the tier that tree's committed return code must resolve to
     * @throws IOException if a committed expectation file cannot be read
     */
    @ParameterizedTest(name = "{0} expects {1}")
    @MethodSource("committedPostingScenarios")
    @DisplayName("agree with the committed return code of every posting scenario")
    void everyCommittedScenarioReturnCodeMatchesItsRejectStream(
            String scenario, BatchReturnCode expectedTier) throws IOException {

        Path tree = goldenPostingRoot().resolve(scenario);
        BatchReturnCode committed = BatchReturnCode.fromNumericValue(
                Integer.parseInt(Files.readString(tree.resolve(RETURN_CODE_FILE)).trim()));
        long rejectStreamBytes = Files.size(tree.resolve(REJECT_STREAM_FILE));

        assertThat(committed).isEqualTo(expectedTier);
        assertThat(committed == BatchReturnCode.SOFT_WARN).isEqualTo(rejectStreamBytes > 0L);
        assertThat(committed.permitsDownstreamRun()).isTrue();
        if (rejectStreamBytes > 0L) {
            assertThat(rejectStreamBytes).isEqualTo(REJECT_RECORD_BYTES + 1L);
        }
    }

    /**
     * The committed posting scenarios are exactly the nine this class enumerates.
     *
     * <p>Pins the census of {@code tests/golden/posting} itself, so that a tree added to or removed
     * from the parity oracle cannot leave the parameterised case above quietly covering a smaller set
     * than exists. Four of the nine expect the warn tier and five expect the clean tier.</p>
     *
     * <p>Assumptions: the directory is enumerated rather than trusted, because the enumeration in this
     * class and the trees on disk are two independent declarations and nothing but this case makes them
     * agree. A parameterised case reads only the rows it is given, so a tenth tree would be tested by
     * nothing at all and its absence from the list would look like a decision.</p>
     *
     * @throws IOException if the expectation directory cannot be listed
     */
    @Test
    @DisplayName("cover every committed posting scenario, four warning and five clean")
    void theCommittedPostingScenariosAreExactlyTheNineEnumerated() throws IOException {
        List<String> onDisk;
        try (Stream<Path> trees = Files.list(goldenPostingRoot())) {
            onDisk = trees.filter(Files::isDirectory)
                    .map(tree -> tree.getFileName().toString())
                    .sorted()
                    .toList();
        }

        List<String> enumerated = committedPostingScenarios()
                .map(scenario -> (String) scenario.get()[0])
                .sorted()
                .toList();

        assertThat(onDisk).isEqualTo(enumerated);
        assertThat(committedPostingScenarios()
                .filter(scenario -> scenario.get()[1] == BatchReturnCode.SOFT_WARN)
                .count()).isEqualTo(4L);
        assertThat(committedPostingScenarios()
                .filter(scenario -> scenario.get()[1] == BatchReturnCode.CLEAN)
                .count()).isEqualTo(5L);
    }

    /**
     * Reports the nine committed posting scenarios and the tier each one's return code must resolve to.
     *
     * <p>Assumptions: the pairs are declared here and the return codes are READ from the committed
     * files, so the case driven by this source compares two independent statements rather than one
     * statement with itself. Declaring the codes here as well would make it a tautology that passes
     * whatever the trees hold.</p>
     *
     * @return one argument pair per scenario, each carrying the tree's directory name and its expected
     *     tier, never {@code null}
     */
    private static Stream<Arguments> committedPostingScenarios() {
        return Stream.of(
                Arguments.of("happy_path", BatchReturnCode.CLEAN),
                Arguments.of("empty_input", BatchReturnCode.CLEAN),
                Arguments.of("zero_balance", BatchReturnCode.CLEAN),
                Arguments.of("boundary_exact_limit", BatchReturnCode.CLEAN),
                Arguments.of("boundary_expiry_equal", BatchReturnCode.CLEAN),
                Arguments.of("reject_100_card_missing", BatchReturnCode.SOFT_WARN),
                Arguments.of("reject_101_acct_missing", BatchReturnCode.SOFT_WARN),
                Arguments.of("reject_102_overlimit", BatchReturnCode.SOFT_WARN),
                Arguments.of("reject_103_expired", BatchReturnCode.SOFT_WARN));
    }

    /**
     * Locates the committed posting expectation trees by walking up from the module directory.
     *
     * <p>Assumptions: the location is SEARCHED for rather than written as a fixed relative path,
     * because the two runners resolve a relative path against the module directory while an invocation
     * from the reactor root resolves it against the repository root, and one literal cannot be correct
     * for both. Walking up until the expectation directory appears is correct from either.</p>
     *
     * <p>Assumptions: an absent directory raises rather than skipping the cases that use it. The parity
     * oracle is a committed, permanent part of this repository, so its absence means the checkout is
     * incomplete; a skip would report a green run that verified nothing about the expectations at all.</p>
     *
     * @return the directory holding the nine posting expectation trees, never {@code null}
     * @throws IllegalStateException if no ancestor of the working directory holds that directory
     */
    private static Path goldenPostingRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path expectations = candidate.resolve(GOLDEN_POSTING);
            if (Files.isDirectory(expectations)) {
                return expectations;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("no ancestor of " + Path.of("").toAbsolutePath()
                + " holds " + GOLDEN_POSTING + "; the parity oracle's expectation trees are"
                + " committed artifacts and a checkout without them is incomplete");
    }

    /**
     * Reports the lines the job wrote to its own logger during the case, in the order it wrote them.
     *
     * <p>Assumptions: the formatted message is read rather than the pattern and its arguments, because
     * the job assembles each counter line with a format call and logs the finished string. Reading the
     * pattern would report the format specifier instead of the nine rendered digits, which is the part
     * under assertion.</p>
     *
     * @return the formatted messages captured so far, never {@code null}
     */
    private List<String> loggedLines() {
        return this.captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Stages the feed to return one record on the first read and nothing afterwards.
     *
     * @param feedRecord the record the feed holds; must not be {@code null}
     */
    private void stageOneRecord(DailyTransaction feedRecord) {
        when(this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(anyLong(), any(Limit.class)))
                .thenReturn(List.of(feedRecord))
                .thenReturn(List.of());
    }

    /**
     * Stages the feed to return several records on the first read and nothing afterwards.
     *
     * <p>Assumptions: every record arrives in ONE batch and the second read is empty, which mirrors a
     * feed smaller than the commit interval rather than a paged walk. The paging itself is covered by
     * the keyset assertions of the repository tier; what a multi-record case here needs is several
     * records inside one pass, so that a per-pass property is distinguishable from a per-record one.</p>
     *
     * @param feedRecords the records the feed holds, in feed order; must not be {@code null} or empty
     */
    private void stageRecords(DailyTransaction... feedRecords) {
        when(this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(anyLong(), any(Limit.class)))
                .thenReturn(List.of(feedRecords))
                .thenReturn(List.of());
    }

    /**
     * Stages a decision per record, accepting the resolvable card and rejecting every other.
     *
     * <p>Assumptions: the decision is selected from the record's CARD NUMBER inside an answer rather
     * than by stubbing each record individually. Per-record stubbing matches on equality, and
     * {@code DailyTransaction} defines equality over its own components, so two fixture records that
     * differed only in a component the fixture happens to share would collide and one case's decision
     * would answer the other's record.</p>
     *
     * @param reason the reason reported for every record the resolvable card does not name; must not be
     *     {@code null}
     */
    private void stageDecisionRoutedByCard(RejectReason reason) {
        Account account = acceptedAccount();
        this.stagedCrossReference = new CardXref(RESOLVABLE_CARD, 987654321L, ACCOUNT_ID);

        when(this.validation.validate(any(DailyTransaction.class))).thenAnswer(call -> {
            DailyTransaction candidate = call.getArgument(0);
            if (RESOLVABLE_CARD.equals(candidate.getCardNum())) {
                return new PostingDecision(
                        PostingValidationResult.accepted(candidate.getAmount()),
                        Optional.of(this.stagedCrossReference), Optional.of(account));
            }
            return new PostingDecision(PostingValidationResult.rejected(reason, null),
                    Optional.empty(), Optional.empty());
        });
        when(this.categoryBalances.accumulatePostedTransaction(any(), any())).thenReturn(
                new CategoryBalanceService.Outcome(CategoryBalanceService.Arm.CREATED,
                        new BigDecimal("100.00")));
    }

    /**
     * Stages an ACCEPTED decision carrying the two records the resolvable card resolves to.
     *
     * <p>Refactoring Rationale: this helper stubbed the two REPOSITORIES and left the job to read
     * them, because the job performed its own card read, its own account read and its own copy of the
     * {@code app/cbl/CBTRN02C.cbl:372} guard. Those reads and that guard now belong to the validation
     * service alone, which hands both records back with its outcome, so what a case has to stage is
     * the DECISION. Staging repositories instead would stub calls the job no longer makes, and every
     * case would then pass while the job received an empty decision.</p>
     *
     * @param projection the projected cycle balance the accepted outcome reports; must not be
     *     {@code null}
     * @return the account the decision carries, never {@code null}
     */
    private Account stageAcceptedDecision(BigDecimal projection) {
        Account account = acceptedAccount();
        this.stagedCrossReference = new CardXref(RESOLVABLE_CARD, 987654321L, ACCOUNT_ID);

        when(this.validation.validate(any(DailyTransaction.class))).thenReturn(
                new PostingDecision(PostingValidationResult.accepted(projection),
                        Optional.of(this.stagedCrossReference), Optional.of(account)));
        return account;
    }

    /**
     * Stages a REJECTED decision for whichever reason a case exercises.
     *
     * <p>Assumptions: the two records are carried back as ABSENT even for a reason that resolved one
     * of them, because no case here asserts them on the reject path -- the job writes the reject row
     * from the feed record and the outcome alone, and reaches neither optional. A case that did assert
     * one would stage it explicitly rather than relying on this helper.</p>
     *
     * @param reason the reason the outcome reports; must not be {@code null}
     * @param projection the projected cycle balance, or {@code null} for a reason assigned before the
     *     account was read
     */
    private void stageRejectedDecision(RejectReason reason, BigDecimal projection) {
        when(this.validation.validate(any(DailyTransaction.class))).thenReturn(
                new PostingDecision(PostingValidationResult.rejected(reason, projection),
                        Optional.empty(), Optional.empty()));
    }

    /**
     * Builds the account the accepted decisions carry, open and inside its credit limit.
     *
     * <p>Assumptions: the account is built by one helper for every case rather than per case, because
     * no case here asserts anything about its opening values -- the boundaries those values would decide
     * belong to the sibling service tier, which drives them against the real rule. What the cases need
     * from it is an account the accepted path can accumulate into.</p>
     *
     * @return a freshly built account, never {@code null}
     */
    private static Account acceptedAccount() {
        return new Account(ACCOUNT_ID, "Y", new BigDecimal("0.00"),
                new BigDecimal("5000.00"), new BigDecimal("500.00"), LocalDate.of(2020, 1, 1),
                LocalDate.of(2030, 1, 1), LocalDate.of(2024, 1, 1), new BigDecimal("0.00"),
                new BigDecimal("0.00"), "98101", "DEFAULT");
    }

    /**
     * Builds a feed record naming the resolvable card, at the first feed ordinal.
     *
     * @param amount the record's amount; must not be {@code null}
     * @return the record, never {@code null}
     */
    private static DailyTransaction resolvableRecord(BigDecimal amount) {
        return record(RESOLVABLE_CARD, amount, FIRST_ORDINAL);
    }

    /**
     * Builds a feed record naming the resolvable card, at one feed ordinal.
     *
     * @param amount the record's amount; must not be {@code null}
     * @param ordinal the feed ordinal the record occupies
     * @return the record, never {@code null}
     */
    private static DailyTransaction resolvableRecord(BigDecimal amount, long ordinal) {
        return record(RESOLVABLE_CARD, amount, ordinal);
    }

    /**
     * Builds a feed record naming one card and carrying one amount, at the first feed ordinal.
     *
     * @param cardNum the card the record names; must not be {@code null}
     * @param amount the record's amount; must not be {@code null}
     * @return the record, never {@code null}
     */
    private static DailyTransaction record(String cardNum, BigDecimal amount) {
        return record(cardNum, amount, FIRST_ORDINAL);
    }

    /**
     * Builds a feed record naming one card, carrying one amount, at one feed ordinal.
     *
     * <p>Assumptions: the record identifier is DERIVED from the ordinal rather than fixed, so that two
     * records in one pass differ in identity as well as in position. The reference layout declares that
     * field sixteen characters wide, {@code DALYTRAN-ID PIC X(16)} in {@code app/cpy/CVTRA06Y.cpy}, so
     * the ordinal is padded to that width rather than written as a bare number.</p>
     *
     * <p>Assumptions: the originating stamp is one hour BEFORE the instant the injected clock reads, so
     * a case comparing the two stamps of a posted row can tell a passthrough from a clock reading. Were
     * the two equal, an implementation that stamped both from the clock would pass.</p>
     *
     * @param cardNum the card the record names; must not be {@code null}
     * @param amount the record's amount; must not be {@code null}
     * @param ordinal the feed ordinal the record occupies, which also supplies its identifier
     * @return the record, never {@code null}
     */
    private static DailyTransaction record(String cardNum, BigDecimal amount, long ordinal) {
        DailyTransaction feedRecord = new DailyTransaction(String.format("%016d", ordinal), "01",
                "0001", "POS", "a purchase", amount, 1L, "a merchant", "a city", "00000", cardNum,
                ORIGINATED_AT, ORIGINATED_AT);
        assignIngestSeq(feedRecord, ordinal);
        return feedRecord;
    }

    /**
     * Assigns the feed ordinal, which the entity deliberately exposes no setter for.
     *
     * <p>Assumptions: the ordinal is the row's database-assigned identifier and the entity declares it
     * unwritable on purpose, so a unit fixture has to set it the way the persistence provider does. The
     * sibling {@code FeedRowIdentityTest} establishes the same approach for the same field, and it asserts
     * separately that the field is not generated by the provider -- so a fixture setting it is supplying a
     * value the loader would otherwise have supplied, not overriding one.</p>
     *
     * @param feedRecord the record to assign the ordinal to; must not be {@code null}
     * @param ordinal the ordinal to assign
     * @throws IllegalStateException if the field is no longer assignable, which would mean the entity's
     *     identity has moved and this fixture is silently building a record with none
     */
    private static void assignIngestSeq(DailyTransaction feedRecord, long ordinal) {
        try {
            Field field = DailyTransaction.class.getDeclaredField("ingestSeq");
            field.setAccessible(true);
            field.set(feedRecord, ordinal);
        } catch (ReflectiveOperationException unassignable) {
            throw new IllegalStateException(
                    "ingestSeq is no longer assignable by reflection", unassignable);
        }
    }
}
