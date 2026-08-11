package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.dto.DisclosureGroupKey;
import com.carddemo.batch.dto.InterestRateLookup;
import com.carddemo.batch.repository.TransactionCategoryBalanceRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.DatasetGenerationService;
import com.carddemo.batch.service.InterestCalculationService;
import com.carddemo.common.money.Money;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Pins the two behaviours of the interest job that differ from the reference, and the one that does not.
 *
 * <p>Purpose: this job carries the only two registered divergences in the batch module -- it writes the
 * final account group's accrual, which {@code app/cbl/CBACT04C.cbl} never reaches, and it skips a balance
 * row whose account cannot be read where the reference abends. Both are claimed in the job's own comments
 * and registered as {@code D-3} and {@code D-INTEREST-ORPHAN-ROW}, and a registered divergence that no
 * test exercises is a claim rather than a behaviour. This class exercises both, and the zero-rate gate the
 * reference does have, so the difference between what is divergent and what is reproduced is asserted
 * rather than described.</p>
 *
 * <p>Assumptions: the job is RUN through the framework and its collaborators are mocked, for the reasons
 * the sibling {@code PostTransactionsJobTest} records: the step lifecycle, the parameter validator and the
 * exit-status propagation are all part of what the orchestrator relies on, while every ruling here is about
 * which writes the walk performs.</p>
 *
 * <p>Assumptions: the durable step ledger is stubbed to EVALUATE its body. A default-returning mock would
 * run none of the accrual and every verification below would hold against a job that did nothing.</p>
 */
@DisplayName("the interest accrual job")
class CalculateInterestJobTest {

    /** The business date the cases inject, in the ten-character separated layout. */
    private static final String BUSINESS_DATE = "2022-07-18";

    /** The orchestrator execution identifier the cases run under. */
    private static final String RUN_ID = "batch-run-0001";

    /** The account whose record the master can be read for. */
    private static final long READABLE_ACCOUNT = 11111111111L;

    /** The account whose record is absent from the master. */
    private static final long ORPHANED_ACCOUNT = 22222222222L;

    /** The card the readable account's cross-reference resolves to. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The job-instance identifier the cases run under, which no assertion depends on. */
    private static final long INSTANCE_ID = 1L;

    /** The job-execution identifier the cases run under, which no assertion depends on. */
    private static final long EXECUTION_ID = 1L;

    /** The category-balance master being walked. */
    private TransactionCategoryBalanceRepository categoryBalances;

    /** The rate resolution and accrual arithmetic. */
    private InterestCalculationService interest;

    /** The durable step ledger, stubbed to evaluate its body. */
    private BatchStepLedger ledgerOfSteps;

    /** The generation resolver the staged {@code SYSTRAN} output is allocated through. */
    private DatasetGenerationService generations;

    /** The framework's in-memory job repository. */
    private JobRepository jobRepository;

    /** The job under test. */
    private Job job;

    /**
     * Builds the mocked collaborators and the job over them, with a rate that does accrue.
     *
     * <p>Assumptions: a fresh set is built per case, because every case asserts call counts and a shared
     * mock would carry one case's calls into the next.</p>
     */
    @BeforeEach
    void buildJob() {
        this.categoryBalances = mock(TransactionCategoryBalanceRepository.class);
        this.interest = mock(InterestCalculationService.class);
        this.ledgerOfSteps = mock(BatchStepLedger.class);
        this.generations = mock(DatasetGenerationService.class);

        when(this.ledgerOfSteps.runStep(anyString(), anyString(), any())).thenAnswer(call -> {
            BatchReturnCode outcome = call.<Supplier<BatchReturnCode>>getArgument(2).get();
            return new BatchStepLedger.StepOutcome(outcome, false);
        });

        // WHY : Assumptions: the generation resolver is stubbed to answer rather than left defaulting,
        //       because the walk allocates the SYSTRAN coordinate before reading a single row and
        //       reports the allocated number when it finishes. A default-returning mock would hand the
        //       job a null generation and every case below would fail on the report rather than on the
        //       accrual behaviour it is written to pin. Retention is stubbed empty for the same reason
        //       the sibling GenerationStagingJobsTest does it: no case here is about aged-out
        //       generations.
        when(this.generations.allocateNewGeneration(any(DatasetFamily.class), any(BusinessDate.class),
                anyString())).thenAnswer(call -> new DatasetGeneration(
                        call.getArgument(0), call.getArgument(1), 1));
        when(this.generations.generationsToScratch(any(DatasetFamily.class))).thenReturn(List.of());
        when(this.generations.datasetUri(any(DatasetGeneration.class)))
                .thenReturn("s3://carddemo-datasets-test/ledger/systran/");
        when(this.generations.stageDataset(any(DatasetGeneration.class), anyString(), any(Path.class)))
                .thenReturn("ledger/systran/a/staged/key");

        // WHY : Assumptions: the key is built through the SAME factory the job itself calls at
        //       CalculateInterestJob.java:270, so a blank-stripped group id reaches its declared
        //       ten-character width the one way production reaches it. Constructing the record
        //       canonically here would need a hand-padded literal, which is a second padding
        //       mechanism that could drift from the first.
        DisclosureGroupKey key = DisclosureGroupKey.ofBlankPaddedAccountGroupId("DEFAULT", "01", 1);
        when(this.interest.rateFor(any()))
                .thenReturn(new InterestRateLookup(key, key, new BigDecimal("2.50")));
        when(this.interest.monthlyInterest(any(), any()))
                .thenReturn(Money.of(new BigDecimal("2.08")));
        when(this.interest.loadCrossReference(any())).thenReturn(CARD_NUMBER);

        // WHY : Assumptions: the accrual write is stubbed to RETURN the row it would have persisted,
        //       because the walk appends that row's fixed-width image to the staged generation. A
        //       default-returning mock would hand the encoder a null and turn every case below into a
        //       staging failure. The identifier is assembled from the arguments the job actually passes
        //       -- the raw business-date token and the run-scoped suffix -- so the stub cannot disagree
        //       with the identifier rule the job is being tested against.
        when(this.interest.writeInterestTransaction(anyLong(), anyString(), any(), any(), anyLong(),
                any())).thenAnswer(call -> generatedRow(
                        call.getArgument(0), call.getArgument(3), call.getArgument(4)));

        Clock clock = Clock.fixed(
                LocalDateTime.of(2022, 7, 18, 1, 2, 3).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        CalculateInterestJob configuration = new CalculateInterestJob(this.categoryBalances,
                this.interest, this.ledgerOfSteps, this.generations, clock);

        this.jobRepository = new ResourcelessJobRepository();
        JobParametersValidator validator = new BatchConfig().carddemoJobParametersValidator();
        this.job = configuration.calculateInterest(
                this.jobRepository, new ResourcelessTransactionManager(), validator);
    }

    /**
     * The final account group's accrual is written, which the reference never reaches.
     *
     * <p>Registers as {@code D-3}. The reference writes an account's total when the NEXT account arrives
     * and its loop cannot re-enter the body once the read reports end of file, so the last group's
     * accumulated interest is never applied. A single-account walk is the smallest input that shows the
     * difference, because in it EVERY account is the final one.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("write the final account group's accrual, which the reference never reaches")
    void theFinalAccountGroupReceivesItsAccrual() throws Exception {
        stageRows(balanceRow(READABLE_ACCOUNT, "01", "0001", "1000.00"));
        Account account = stageReadableAccount();

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // WHY : Refactoring Rationale: the flush is asserted as a CALL carrying the accumulated total,
        //       and the two cycle amounts it zeroes are no longer asserted here. The three state
        //       changes at app/cbl/CBACT04C.cbl:352-354 belong to InterestCalculationService, which the
        //       register maps 1050-UPDATE-ACCOUNT at :350 to, and that collaborator is a mock in this
        //       class -- so asserting the zeroed amounts here would assert the mock rather than the
        //       rule, and would pass whether or not the rule still performed the reset. The reset is
        //       asserted against the real implementation in InterestCalculationServiceTest.
        verify(this.interest).flushAccount(account, Money.of(new BigDecimal("2.08")));
        verify(this.interest).writeInterestTransaction(eq(READABLE_ACCOUNT), eq(CARD_NUMBER),
                any(Money.class), any(BusinessDate.class), eq(1L), any(LocalDateTime.class));
    }

    /**
     * A balance row whose account cannot be read accrues nothing, and the walk continues past it.
     *
     * <p>Registers as {@code D-INTEREST-ORPHAN-ROW}. The reference abends on that read, so it never
     * reaches the accounts after it. Both assertions matter: nothing is invented for the account that does
     * not exist, and the account that does exist still accrues -- which is the whole reason the skip is
     * preferred to the abend.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("skip a row whose account cannot be read and still accrue the next account")
    void anOrphanedRowIsSkippedAndTheWalkContinues() throws Exception {
        stageRows(
                balanceRow(ORPHANED_ACCOUNT, "01", "0001", "500.00"),
                balanceRow(READABLE_ACCOUNT, "01", "0001", "1000.00"));
        when(this.interest.loadAccount(ORPHANED_ACCOUNT)).thenReturn(Optional.empty());
        Account readable = stageReadableAccount();

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // Exactly one account is written, and it is the one that could be read.
        verify(this.interest, times(1)).flushAccount(any(Account.class), any(Money.class));
        verify(this.interest).flushAccount(eq(readable), any(Money.class));
        // Exactly one accrual reaches the ledger: none for the orphaned account.
        verify(this.interest, times(1)).writeInterestTransaction(any(), any(), any(), any(),
                anyLong(), any());
    }

    /**
     * A zero rate accrues nothing, which the reference also does.
     *
     * <p>Pins {@code app/cbl/CBACT04C.cbl:213}, which guards the whole computation with
     * {@code IF DIS-INT-RATE NOT = 0}. This is NOT a divergence and is asserted here so that the two above
     * are distinguishable from the behaviour the reference shares: accruing at zero would write a
     * transaction of amount zero for every category an account holds, and the reference writes none.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("write no transaction when the resolved rate is zero")
    void aZeroRateAccruesNothing() throws Exception {
        // WHY : Assumptions: the key is built through the SAME factory the job itself calls at
        //       CalculateInterestJob.java:270, so a blank-stripped group id reaches its declared
        //       ten-character width the one way production reaches it. Constructing the record
        //       canonically here would need a hand-padded literal, which is a second padding
        //       mechanism that could drift from the first.
        DisclosureGroupKey key = DisclosureGroupKey.ofBlankPaddedAccountGroupId("DEFAULT", "01", 1);
        when(this.interest.rateFor(any()))
                .thenReturn(new InterestRateLookup(key, key, BigDecimal.ZERO));
        stageRows(balanceRow(READABLE_ACCOUNT, "01", "0001", "1000.00"));
        stageReadableAccount();

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        verify(this.interest, never()).writeInterestTransaction(any(), any(), any(), any(),
                anyLong(), any());
    }

    /**
     * An empty master completes cleanly and writes nothing at all.
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("complete cleanly over an empty category-balance master")
    void anEmptyMasterWritesNothing() throws Exception {
        stageRows();

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        verify(this.interest, never()).flushAccount(any(Account.class), any(Money.class));
        verify(this.interest, never()).writeInterestTransaction(any(), any(), any(), any(),
                anyLong(), any());
    }

    /**
     * Arranges the rows the walk reads, in the order the ordered query would return them.
     *
     * @param rows the balance rows to serve; may be empty
     */
    private void stageRows(TransactionCategoryBalance... rows) {
        when(this.categoryBalances.findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc())
                .thenReturn(Stream.of(rows));
    }

    /**
     * Arranges a readable account record for the readable account identifier.
     *
     * @return the account the master will return, so a case can assert it was the one written
     */
    private Account stageReadableAccount() {
        Account account = new Account(READABLE_ACCOUNT, "Y", new BigDecimal("100.00"),
                new BigDecimal("5000.00"), new BigDecimal("500.00"), LocalDate.of(2020, 1, 1),
                LocalDate.of(2030, 1, 1), LocalDate.of(2024, 1, 1), new BigDecimal("25.00"),
                new BigDecimal("75.00"), "98101", "DEFAULT");
        when(this.interest.loadAccount(READABLE_ACCOUNT)).thenReturn(Optional.of(account));
        return account;
    }

    /**
     * Builds one balance row of one account, type and category.
     *
     * @param accountId the account the row belongs to
     * @param typeCd the two-character transaction type code
     * @param categoryCd the four-character transaction category code
     * @param balance the row's balance as exact decimal text
     * @return the row, never {@code null}
     */
    private static TransactionCategoryBalance balanceRow(
            long accountId, String typeCd, String categoryCd, String balance) {
        return new TransactionCategoryBalance(
                new TransactionCategoryBalanceId(accountId, typeCd, categoryCd),
                new BigDecimal(balance));
    }

    /**
     * Builds the cross-reference the generated transaction's card number is taken from.
     *
     * @return the cross-reference, never {@code null}
     */
    private static CardXref crossReference() {
        return new CardXref(CARD_NUMBER, READABLE_ACCOUNT, 1L);
    }

    /**
     * Builds the generated interest row the accrual write is stubbed to return.
     *
     * <p>Assumptions: the field values are the literals {@code app/cbl/CBACT04C.cbl:482-498} moves --
     * type {@code 01}, the stored four-character category {@code 0005}, the source {@code System} and the
     * eleven-digit description -- so the image the walk stages is the reference's own row shape rather
     * than an arbitrary one the encoder merely accepts.</p>
     *
     * @param accountId the account the accrual belongs to, rendered into the description at its declared
     *     eleven digits
     * @param businessDate the injected business date, whose RAW token opens the identifier
     * @param suffix the run-scoped identifier suffix, rendered at its declared six digits
     * @return the generated row, never {@code null}
     */
    private static Transaction generatedRow(long accountId, BusinessDate businessDate, long suffix) {
        Transaction row = new Transaction(businessDate.token() + String.format("%06d", suffix));
        row.setTypeCd("01");
        row.setCategoryCd("0005");
        row.setSource("System");
        row.setDescription("Int. for a/c " + String.format("%011d", accountId));
        row.setAmount(new BigDecimal("2.08"));
        row.setMerchantId(0L);
        row.setCardNum(CARD_NUMBER);
        row.setOrigTs(LocalDateTime.of(2022, 7, 18, 1, 2, 3));
        row.setProcTs(LocalDateTime.of(2022, 7, 18, 1, 2, 3));
        return row;
    }

    /**
     * Runs the job once with both required parameters and returns its execution.
     *
     * @return the finished job execution, never {@code null}
     * @throws Exception if the framework's own execution path raises
     */
    private JobExecution run() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER, BUSINESS_DATE, true)
                .addString(BatchConfig.RUN_ID_PARAMETER, RUN_ID, false)
                .toJobParameters();

        JobInstance instance = new JobInstance(INSTANCE_ID, CalculateInterestJob.JOB_NAME);
        JobExecution execution = new JobExecution(EXECUTION_ID, instance, parameters);
        this.jobRepository.update(execution);
        this.job.execute(execution);
        return execution;
    }
}
