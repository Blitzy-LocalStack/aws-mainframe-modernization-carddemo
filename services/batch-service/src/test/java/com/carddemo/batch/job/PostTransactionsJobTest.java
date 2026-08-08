package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.carddemo.batch.domain.TransactionReject;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.PostingValidationResult;
import com.carddemo.batch.dto.RejectReason;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DailyTransactionRepository;
import com.carddemo.batch.repository.TransactionRejectRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.CategoryBalanceService;
import com.carddemo.batch.service.PostingValidationService;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    /** The durable step ledger, stubbed to evaluate its body. */
    private BatchStepLedger ledgerOfSteps;

    /** The job under test. */
    private Job job;

    /** The framework's in-memory job repository. */
    private JobRepository jobRepository;

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
        this.ledgerOfSteps = mock(BatchStepLedger.class);
        EntityManager entityManager = mock(EntityManager.class);

        // WHY : Assumptions: the ledger stub EVALUATES the body it is handed. A default-returning mock
        //       would run none of the posting work and every count asserted below would be zero, so the
        //       cases would pass while exercising nothing at all.
        when(this.ledgerOfSteps.runStep(anyString(), anyString(), any())).thenAnswer(call -> {
            BatchReturnCode outcome = call.<Supplier<BatchReturnCode>>getArgument(2).get();
            return new BatchStepLedger.StepOutcome(outcome, false);
        });

        Clock clock = Clock.fixed(
                LocalDateTime.of(2022, 7, 18, 1, 2, 3).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

        PostTransactionsJob configuration = new PostTransactionsJob(this.feed, this.crossReferences,
                this.accounts, this.ledger, this.rejects, this.validation, this.categoryBalances,
                this.ledgerOfSteps, clock, entityManager);

        this.jobRepository = new ResourcelessJobRepository();
        JobParametersValidator validator = new BatchConfig().carddemoJobParametersValidator();
        this.job = configuration.postTransactions(
                this.jobRepository, new ResourcelessTransactionManager(), validator);
    }

    /**
     * An accepted record produces exactly three writes, and no reject row.
     *
     * <p>Pins {@code app/cbl/CBTRN02C.cbl:441-443}, which performs the category-balance update, then the
     * account update, then the transaction write, in that order and for every accepted record.</p>
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("write the balance, the account and the ledger for an accepted record")
    void anAcceptedRecordProducesThreeWrites() throws Exception {
        DailyTransaction feedRecord = resolvableRecord(new BigDecimal("100.00"));
        stageOneRecord(feedRecord);
        stageResolvedCardAndAccount();
        when(this.validation.validate(any(), any(), any()))
                .thenReturn(PostingValidationResult.accepted(new BigDecimal("100.00")));
        when(this.categoryBalances.accumulate(any(), any())).thenReturn(
                new CategoryBalanceService.Outcome(CategoryBalanceService.Arm.CREATED,
                        new BigDecimal("100.00")));

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isNotEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
        verify(this.categoryBalances).accumulate(
                new TransactionCategoryBalanceId(ACCOUNT_ID, "01", "0001"), com.carddemo.common.money
                        .Money.of(new BigDecimal("100.00")));
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
        stageResolvedCardAndAccount();
        when(this.validation.validate(any(), any(), any())).thenReturn(
                PostingValidationResult.rejected(RejectReason.OVER_CREDIT_LIMIT,
                        new BigDecimal("100.00")));

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
        verify(this.rejects).save(any(TransactionReject.class));
        verify(this.ledger, never()).save(any(Transaction.class));
        verify(this.accounts, never()).save(any(Account.class));
    }

    /**
     * The account is not read at all when the card does not resolve.
     *
     * <p>Pins {@code app/cbl/CBTRN02C.cbl:372}, which gates the account read on the not-invalid-key branch
     * of the cross-reference read. Reading it regardless would still reject correctly, so this is the one
     * ruling the outcome cannot reveal -- only the absence of the call can.</p>
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("skip the account read when the card does not resolve")
    void anUnresolvableCardSkipsTheAccountRead() throws Exception {
        DailyTransaction feedRecord = record(UNRESOLVABLE_CARD, new BigDecimal("10.00"));
        stageOneRecord(feedRecord);
        when(this.crossReferences.findByCardNum(UNRESOLVABLE_CARD)).thenReturn(Optional.empty());
        when(this.validation.validate(any(), any(), any())).thenReturn(
                PostingValidationResult.rejected(
                        RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE, null));

        run();

        verify(this.crossReferences).findByCardNum(UNRESOLVABLE_CARD);
        verify(this.accounts, never()).findByAccountId(anyLong());
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
        Account account = stageResolvedCardAndAccount();
        when(this.validation.validate(any(), any(), any()))
                .thenReturn(PostingValidationResult.accepted(new BigDecimal("-25.00")));
        when(this.categoryBalances.accumulate(any(), any())).thenReturn(
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
     * Stages the cross-reference and the account master so the resolvable card resolves.
     *
     * @return the account the card resolves to, never {@code null}
     */
    private Account stageResolvedCardAndAccount() {
        CardXref resolved = new CardXref(RESOLVABLE_CARD, 987654321L, ACCOUNT_ID);
        Account account = new Account(ACCOUNT_ID, "Y", new BigDecimal("0.00"),
                new BigDecimal("5000.00"), new BigDecimal("500.00"), LocalDate.of(2020, 1, 1),
                LocalDate.of(2030, 1, 1), LocalDate.of(2024, 1, 1), new BigDecimal("0.00"),
                new BigDecimal("0.00"), "98101", "DEFAULT");

        when(this.crossReferences.findByCardNum(RESOLVABLE_CARD)).thenReturn(Optional.of(resolved));
        when(this.accounts.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(account));
        return account;
    }

    /**
     * Builds a feed record naming the resolvable card.
     *
     * @param amount the record's amount; must not be {@code null}
     * @return the record, never {@code null}
     */
    private static DailyTransaction resolvableRecord(BigDecimal amount) {
        return record(RESOLVABLE_CARD, amount);
    }

    /**
     * Builds a feed record naming one card and carrying one amount.
     *
     * @param cardNum the card the record names; must not be {@code null}
     * @param amount the record's amount; must not be {@code null}
     * @return the record, never {@code null}
     */
    private static DailyTransaction record(String cardNum, BigDecimal amount) {
        LocalDateTime stamp = LocalDateTime.of(2022, 7, 18, 0, 0, 0);
        DailyTransaction feedRecord = new DailyTransaction("0000000000000001", "01", "0001", "POS",
                "a purchase", amount, 1L, "a merchant", "a city", "00000", cardNum, stamp, stamp);
        assignIngestSeq(feedRecord, FIRST_ORDINAL);
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
