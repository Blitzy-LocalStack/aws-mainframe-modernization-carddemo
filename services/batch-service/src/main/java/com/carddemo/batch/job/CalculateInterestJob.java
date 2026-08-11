package com.carddemo.batch.job;

import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DisclosureGroupKey;
import com.carddemo.batch.dto.InterestRateLookup;
import com.carddemo.batch.repository.TransactionCategoryBalanceRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.InterestCalculationService;
import com.carddemo.common.money.Money;
import java.time.Clock;
import java.time.LocalDateTime;
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
 * Accrues monthly interest onto every account that holds a transaction category balance.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this job is state five of the nightly chain and re-expresses
 * {@code app/cbl/CBACT04C.cbl}, driven by {@code app/jcl/INTCALC.jcl:22}
 * ({@code EXEC PGM=CBACT04C,PARM='2022071800'}). It walks the category balances in key order, accrues
 * interest for each one at its disclosure group's rate, writes one system transaction per accrual, and on
 * each change of account writes the accumulated interest onto the account and resets its cycle
 * totals.</p>
 *
 * <p>Assumptions: the ten-character {@code PARM} the driver passes is the business date, and it is the
 * origin of this module's whole injected-date discipline: the reference reads no clock for it, so a rerun
 * of one day reproduces that day's output byte for byte. It reaches this job as the identifying job
 * parameter and is used for one thing besides identity -- the generated transaction identifiers below
 * begin with it.</p>
 *
 * <h2>The control break, and the one place this job diverges from the reference</h2>
 *
 * <p>Assumptions: the walk is ordered by account, then transaction type, then category, so every row of
 * one account arrives consecutively and a change of account is the signal to write that account's
 * accumulated interest. That is the classic control break at
 * {@code app/cbl/CBACT04C.cbl:194-205}, and the ordering is what makes it correct -- an unordered walk
 * would break on the same account repeatedly and write partial totals.</p>
 *
 * <p>Trade-offs: the reference's loop writes an account's total when the NEXT account arrives, and the
 * last account never has a next account, so the reference never writes the final account's interest. This
 * job writes it. The divergence is deliberate, is registered as {@code D-3} in
 * {@code docs/architecture/cobol-to-service-traceability.md} §7.1, and is the only behavioural
 * difference in
 * this job: omitting it would drop one account's accrual every run, which is a defect rather than a
 * contract. Every other rule here is reproduced exactly, including the two that look wrong at first
 * reading and are not -- the zero-rate skip and the cycle-total reset.</p>
 *
 * <h2>The fee paragraph is a no-op on purpose</h2>
 *
 * <p>Assumptions: {@code app/cbl/CBACT04C.cbl:518-520} declares {@code 1400-COMPUTE-FEES} with the single
 * comment {@code To be implemented} and an immediate exit. It IS performed -- the statement immediately
 * after {@code PERFORM 1300-COMPUTE-INTEREST}, inside the same non-zero-rate branch -- so it runs once per
 * accrual and does nothing. It is preserved here as {@link #computeFees()}, called from exactly that
 * position, rather than dropped. Dropping it would lose the one place the reference records that fee
 * accrual was intended and never written, and a reader comparing the two would have no way to tell an
 * omission from a decision.</p>
 */
@Configuration
public class CalculateInterestJob {

    /** The name this job registers under, taken from the shared token enumeration. */
    public static final String JOB_NAME = BatchJobName.CALCULATE_INTEREST.token();

    /** The step name the durable ledger records this job's progress under. */
    public static final String STEP_NAME = "calculate-interest-step";

    // WHY : Refactoring Rationale: the generated transaction's literal field values, its identifier
    //       suffix width and the rendering of both used to be declared here, beside a private copy of
    //       the paragraph that assembles the row. They now live on InterestCalculationService, which
    //       this file's own charter names as the owner of the paragraph-equivalent methods, and the
    //       register at docs/architecture/cobol-to-service-traceability.md maps 1300-B-WRITE-TX at
    //       app/cbl/CBACT04C.cbl:473 to that type. Two copies of one record's field values is the
    //       failure being removed: the copy here rendered the account identifier as a plain number,
    //       so it produced the fourteen characters "Int. for a/c 1" where :485-489 moves
    //       ACCT-ID PIC 9(11) at its declared width and the committed golden carries the twenty-four
    //       characters "Int. for a/c 00000000001".

    /** The operational log this job reports its accrual counts through. */
    private static final Logger LOG = LoggerFactory.getLogger(CalculateInterestJob.class);

    /** The category balances the accrual walks. */
    private final TransactionCategoryBalanceRepository categoryBalances;

    /** The rule resolving a disclosure-group rate and accruing at it. */
    private final InterestCalculationService interest;

    /** The durable step record that makes a re-run of a completed step a no-op. */
    private final BatchStepLedger ledgerOfSteps;

    /** The clock the generated transactions' stamps are read from. */
    private final Clock clock;

    /**
     * Builds the job over the rules and repositories it composes.
     *
     * @param categoryBalances the category balances to walk, whose ordering by account, type and
     *     category is what makes the control break correct; must not be {@code null}
     * @param interest the accrual rule this job delegates every paragraph of
     *     {@code app/cbl/CBACT04C.cbl} to except the walk itself; must not be {@code null}
     * @param ledgerOfSteps the durable step ledger; must not be {@code null}
     * @param clock the clock the generated stamps are read from; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CalculateInterestJob(TransactionCategoryBalanceRepository categoryBalances,
            InterestCalculationService interest, BatchStepLedger ledgerOfSteps, Clock clock) {

        this.categoryBalances =
                Objects.requireNonNull(categoryBalances, "categoryBalances must not be null");
        this.interest = Objects.requireNonNull(interest, "interest must not be null");
        this.ledgerOfSteps = Objects.requireNonNull(ledgerOfSteps, "ledgerOfSteps must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Registers the job under the token the orchestrator names it by.
     *
     * @param jobRepository the framework's durable job repository; must not be {@code null}
     * @param transactionManager the transaction manager the step commits through; must not be
     *     {@code null}
     * @param validator the shared parameter validator; must not be {@code null}
     * @return the registered job, never {@code null}
     */
    @Bean
    // WHY : Refactoring Rationale: the factory method is named WITHOUT the "Job" suffix its class
    //       carries, and the difference is load-bearing rather than cosmetic. A `@Configuration` class
    //       registered by type takes a bean id from its own decapitalised class name, so a `@Bean`
    //       method spelled `calculateInterestJob` inside `CalculateInterestJob` claims the identifier the class
    //       itself already holds -- and Spring refuses the context with a
    //       BeanDefinitionOverrideException rather than choosing between them. Dropping the suffix
    //       gives the two definitions distinct identifiers.
    // WHY : Assumptions: nothing selects this bean by its identifier. The command contract iterates
    //       the `Job` beans and compares `getName()` against its argument, and `getName()` comes from
    //       JOB_NAME below, so the identifier is free to change and the job's published token is not.
    public Job calculateInterest(JobRepository jobRepository,
            PlatformTransactionManager transactionManager, JobParametersValidator validator) {

        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(validator)
                .start(new StepBuilder(STEP_NAME, jobRepository)
                        .tasklet(this::runStep, transactionManager)
                        .build())
                .build();
    }

    /**
     * Runs the whole accrual pass once, under the durable step record.
     *
     * @param contribution the framework's handle for reporting this step's exit status; must not be
     *     {@code null}
     * @param context the chunk context carrying the job parameters; must not be {@code null}
     * @return {@link RepeatStatus#FINISHED} always
     */
    private RepeatStatus runStep(StepContribution contribution, ChunkContext context) {
        String runId = BatchConfig.runIdOf(context);
        BusinessDate businessDate = BatchConfig.businessDateOf(context);

        this.ledgerOfSteps.runStep(runId, STEP_NAME, () -> accrueEveryCategoryBalance(businessDate));

        // WHY : Assumptions: this job reports no warn tier, and the absence is by construction rather
        //       than an omission. app/cbl/CBACT04C.cbl contains no RETURN-CODE statement at all: it
        //       either completes or abends through 9999-ABEND-PROGRAM, so there is no middle outcome to
        //       report. The package charter records that the warn tier has exactly one origin in the
        //       whole baseline and it is the posting program.
        return RepeatStatus.FINISHED;
    }

    /**
     * Walks the category balances in key order and accrues interest across each account's rows.
     *
     * @param businessDate the injected business date the generated identifiers begin with; must not be
     *     {@code null}
     * @return {@link BatchReturnCode#CLEAN} always, because this job has no warn tier
     */
    private BatchReturnCode accrueEveryCategoryBalance(BusinessDate businessDate) {
        Accrual accrual = new Accrual(businessDate);

        // WHY : Trade-offs: the walk is a streamed whole-table read rather than a keyset-paginated one,
        //       which is the opposite of the posting job's choice, and the difference is deliberate. A
        //       control break is only correct over an UNINTERRUPTED ordered sequence: a paginated walk
        //       would have to carry the open account's running total across page boundaries, and a page
        //       boundary falling inside an account would then be indistinguishable from a control break
        //       unless that state were threaded through. The stream keeps the sequence whole for the
        //       duration of one transaction, which is what the reference's sequential read gave it. The
        //       cost is that the step holds one cursor open for its duration.
        try (Stream<TransactionCategoryBalance> rows = this.categoryBalances
                .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc()) {
            rows.forEach(row -> accrueOneRow(row, accrual));
        }

        // WHY : Refactoring Rationale: the final account's accumulated interest is written here. The
        //       reference writes an account's total only when the next account arrives, so the last
        //       account of the file never receives its accrual -- app/cbl/CBACT04C.cbl:194-205 has no
        //       post-loop flush. Reproducing that omission would silently drop one account's interest
        //       every run, so it is corrected and registered as divergence D-3 in
        //       docs/architecture/cobol-to-service-traceability.md §7.1.
        flushOpenAccount(accrual);

        LOG.info("event=batch.interest.completed rows={} accruals={} accounts={}",
                accrual.rowsRead, accrual.accrualsWritten, accrual.accountsUpdated);
        return BatchReturnCode.CLEAN;
    }

    /**
     * Accrues one category balance, breaking to a new account first when the key has moved on.
     *
     * @param row the category balance being accrued; must not be {@code null}
     * @param accrual the running state of the walk; must not be {@code null}
     */
    private void accrueOneRow(TransactionCategoryBalance row, Accrual accrual) {
        accrual.rowsRead++;
        Long accountId = row.getId().getAccountId();

        if (!accountId.equals(accrual.openAccountId)) {
            flushOpenAccount(accrual);
            accrual.openTo(accountId, this.interest.loadAccount(accountId));

            // WHY : Assumptions: the account is read BEFORE the cross-reference and the card read is
            //       reached only when the account was found, which is the order and the guarding
            //       app/cbl/CBACT04C.cbl:203 and :205 impose. The order matters for more than
            //       fidelity: 1110-GET-XREF-DATA raises when an account has no card row, so reading
            //       it for an account that is itself absent would turn the skip registered as
            //       divergence D-INTEREST-ORPHAN-ROW into a hard failure -- and the reference can
            //       never reach :205 for such a row, because :389 abends inside 1100 first.
            if (accrual.openAccount != null) {
                accrual.openCardNumber = this.interest.loadCrossReference(accountId);
            }
        }

        // WHY : Assumptions: a row whose account cannot be read is skipped rather than abending. The
        //       reference reads the account through 1100-GET-ACCT-DATA and abends when the read fails,
        //       which would abandon every accrual already written in the same run; here the step's single
        //       transaction means an abend discards them anyway, so the outcome differs only in whether
        //       the remaining accounts are attempted. Skipping and logging is the more useful of the two
        //       for an operator, because it names every unresolvable account in one run instead of the
        //       first. Registered as divergence D-INTEREST-ORPHAN-ROW in
        //       docs/architecture/cobol-to-service-traceability.md §7.4.
        if (accrual.openAccount == null) {
            LOG.warn("event=batch.interest.account-absent accountId={}", accountId);
            return;
        }

        DisclosureGroupKey requested = DisclosureGroupKey.ofBlankPaddedAccountGroupId(
                accrual.openAccount.getGroupId(), row.getId().getTypeCd(),
                Integer.parseInt(row.getId().getCategoryCd().trim()));

        // WHY : Assumptions: the lookup answers with a resolved rate or it raises, and there is no
        //       third "not found" result to test for. app/cbl/CBACT04C.cbl:422 accepts file status '23'
        //       as normal and retries under the substituted group at :436-438, and the retry at :443
        //       carries no INVALID KEY clause at all -- so a run in which neither key resolves abends
        //       at :455 rather than continuing with no rate.
        InterestRateLookup lookup = this.interest.rateFor(requested);
        if (!lookup.interestApplicable()) {
            // WHY : Assumptions: a zero rate is skipped rather than accrued at zero, matching
            //       app/cbl/CBACT04C.cbl:214 which guards the whole computation with
            //       IF DIS-INT-RATE NOT = 0. The difference is observable: accruing at zero would write a
            //       transaction of amount zero for every category the account holds, and the reference
            //       writes none. The gate encloses :215 AND :216, so the fee call is skipped with it.
            return;
        }

        // WHY : Assumptions: the per-row value is accumulated ALREADY TRUNCATED, because :467 adds
        //       WS-MONTHLY-INT after the preceding statement has stored it into PIC S9(09)V99. The
        //       account increment is therefore the sum of the truncated terms and not the truncation of
        //       their sum, and the two differ by cents on a multi-category account.
        Money accrued = this.interest.monthlyInterest(Money.of(row.getBalance()), lookup);
        accrual.total = accrual.total.plus(accrued);
        accrual.accrualsWritten++;

        // WHY : Assumptions: ONE row is emitted per CATEGORY BALANCE, because the PERFORM at :468 sits
        //       inside 1300-COMPUTE-INTEREST alongside the accumulate at :467 rather than at the
        //       account level. The suffix advances from the run-scoped counter this walk owns, since
        //       :474 never resets it on a control break.
        accrual.suffix++;
        this.interest.writeInterestTransaction(accountId, accrual.openCardNumber, accrued,
                accrual.businessDate, accrual.suffix, LocalDateTime.now(this.clock));

        this.interest.computeFees();
    }

    /**
     * Writes one account's accumulated interest onto the account and resets its cycle totals.
     *
     * <p>Assumptions: both cycle totals are set to zero rather than left alone, matching
     * {@code app/cbl/CBACT04C.cbl:353-354}, which the delegate applies alongside the balance addition
     * at {@code :352}. This is the statement-cycle boundary: the totals the posting job accumulated
     * during the cycle have now been billed, so the next cycle starts from zero. Leaving them would
     * make the over-limit projection the posting job computes carry a whole prior cycle's activity
     * into the next one.</p>
     *
     * @param accrual the running state of the walk; must not be {@code null}
     */
    private void flushOpenAccount(Accrual accrual) {
        if (accrual.openAccount == null) {
            return;
        }

        // WHY : Refactoring Rationale: the three state changes at app/cbl/CBACT04C.cbl:352-354 used to
        //       be applied here field by field, which put the balance addition and the two cycle resets
        //       in a place a caller could perform partially. They are now one call on
        //       InterestCalculationService, which the register maps 1050-UPDATE-ACCOUNT at :350 to, and
        //       the entity exposes the transition as a single indivisible operation.
        this.interest.flushAccount(accrual.openAccount, accrual.total);
        accrual.accountsUpdated++;
        accrual.openAccount = null;
    }

    /**
     * The running state of one accrual walk: the open account, its running total and its counters.
     *
     * <p>Assumptions: the state is a private mutable holder rather than threaded through the walk as
     * parameters and return values. A control break is inherently stateful -- it is defined by what the
     * PREVIOUS row was -- and the reference holds the same state in working storage. Making it a named
     * type keeps every piece of that state in one place, so a reader can see the whole of what a break
     * resets.</p>
     */
    private static final class Accrual {

        /** The injected business date every generated identifier begins with. */
        private final BusinessDate businessDate;

        /** The account whose rows are currently being accrued, or {@code null} before the first break. */
        private Account openAccount;

        /** The identifier of the account currently open, or {@code null} before the first break. */
        private Long openAccountId;

        /** The card number the open account's generated transactions carry. */
        private String openCardNumber = "";

        /** The interest accumulated for the open account so far. */
        private Money total = Money.ZERO;

        /** The generated-identifier suffix counter, which advances across the whole run. */
        private long suffix;

        /** How many category balances the walk read. */
        private long rowsRead;

        /** How many accruals the walk wrote. */
        private long accrualsWritten;

        /** How many accounts the walk updated. */
        private long accountsUpdated;

        /**
         * Builds the running state for one walk.
         *
         * @param businessDate the injected business date; must not be {@code null}
         */
        private Accrual(BusinessDate businessDate) {
            this.businessDate = businessDate;
        }

        /**
         * Opens a new account, resetting the running total the previous account accumulated.
         *
         * <p>Assumptions: the total is reset here rather than by the flush, matching
         * {@code app/cbl/CBACT04C.cbl:200} which zeroes it AFTER writing the previous account. The
         * ordering matters: resetting inside the flush would zero the total the flush is about to
         * write.</p>
         *
         * @param accountId the account now open; must not be {@code null}
         * @param account the account row, which may be empty when the account cannot be read
         */
        private void openTo(Long accountId, Optional<Account> account) {
            this.openAccountId = accountId;
            this.openAccount = account.orElse(null);

            // WHY : Assumptions: the card number is reset to blank here and populated by the caller
            //       only when the account was found, rather than being taken as a second argument.
            //       app/cbl/CBACT04C.cbl:205 reads the cross-reference AFTER :203 has read the
            //       account, and the migrated read raises on an account with no card row, so opening
            //       an unreadable account must not require a card number to have been resolved for
            //       it. Leaving the previous account's card number in place instead would stamp it on
            //       the next account's generated rows.
            this.openCardNumber = "";
            this.total = Money.ZERO;
        }
    }
}
