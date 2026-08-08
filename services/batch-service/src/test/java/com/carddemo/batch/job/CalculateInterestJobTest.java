package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.carddemo.batch.dto.DisclosureGroupKey;
import com.carddemo.batch.dto.InterestRateLookup;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.TransactionCategoryBalanceRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.InterestCalculationService;
import com.carddemo.common.money.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    /** The account master being read and updated. */
    private AccountRepository accounts;

    /** The cross-reference the generated transaction's card number comes from. */
    private CardXrefRepository crossReferences;

    /** The ledger the generated interest transactions are written to. */
    private TransactionRepository ledger;

    /** The rate resolution and accrual arithmetic. */
    private InterestCalculationService interest;

    /** The durable step ledger, stubbed to evaluate its body. */
    private BatchStepLedger ledgerOfSteps;

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
        this.accounts = mock(AccountRepository.class);
        this.crossReferences = mock(CardXrefRepository.class);
        this.ledger = mock(TransactionRepository.class);
        this.interest = mock(InterestCalculationService.class);
        this.ledgerOfSteps = mock(BatchStepLedger.class);

        when(this.ledgerOfSteps.runStep(anyString(), anyString(), any())).thenAnswer(call -> {
            BatchReturnCode outcome = call.<Supplier<BatchReturnCode>>getArgument(2).get();
            return new BatchStepLedger.StepOutcome(outcome, false);
        });

        // WHY : Assumptions: the key is built through the SAME factory the job itself calls at
        //       CalculateInterestJob.java:270, so a blank-stripped group id reaches its declared
        //       ten-character width the one way production reaches it. Constructing the record
        //       canonically here would need a hand-padded literal, which is a second padding
        //       mechanism that could drift from the first.
        DisclosureGroupKey key = DisclosureGroupKey.ofBlankPaddedAccountGroupId("DEFAULT", "01", 1);
        when(this.interest.resolveRate(any()))
                .thenReturn(Optional.of(new InterestRateLookup(key, key, new BigDecimal("2.50"))));
        when(this.interest.accrue(any(), any())).thenReturn(Money.of(new BigDecimal("2.08")));
        when(this.crossReferences.findFirstByAccountIdOrderByCardNumAsc(any()))
                .thenReturn(Optional.of(crossReference()));

        Clock clock = Clock.fixed(
                LocalDateTime.of(2022, 7, 18, 1, 2, 3).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        CalculateInterestJob configuration = new CalculateInterestJob(this.categoryBalances,
                this.accounts, this.crossReferences, this.ledger, this.interest, this.ledgerOfSteps,
                clock);

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
        verify(this.accounts).save(account);
        verify(this.ledger).save(any(Transaction.class));
        // The cycle totals are zeroed on the same write, matching app/cbl/CBACT04C.cbl:342-344.
        assertThat(account.getCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
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
        when(this.accounts.findByAccountId(ORPHANED_ACCOUNT)).thenReturn(Optional.empty());
        Account readable = stageReadableAccount();

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // Exactly one account is written, and it is the one that could be read.
        verify(this.accounts, times(1)).save(any(Account.class));
        verify(this.accounts).save(readable);
        // Exactly one accrual reaches the ledger: none for the orphaned account.
        verify(this.ledger, times(1)).save(any(Transaction.class));
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
        when(this.interest.resolveRate(any()))
                .thenReturn(Optional.of(new InterestRateLookup(key, key, BigDecimal.ZERO)));
        stageRows(balanceRow(READABLE_ACCOUNT, "01", "0001", "1000.00"));
        stageReadableAccount();

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        verify(this.ledger, never()).save(any(Transaction.class));
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
        verify(this.accounts, never()).save(any(Account.class));
        verify(this.ledger, never()).save(any(Transaction.class));
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
        when(this.accounts.findByAccountId(READABLE_ACCOUNT)).thenReturn(Optional.of(account));
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
