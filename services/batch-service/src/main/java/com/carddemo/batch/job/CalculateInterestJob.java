package com.carddemo.batch.job;

import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BatchRunSummary;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.dto.DisclosureGroupKey;
import com.carddemo.batch.dto.InterestRateLookup;
import com.carddemo.batch.mapper.TransactionRecordMapper;
import com.carddemo.batch.repository.TransactionCategoryBalanceRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.DatasetGenerationService;
import com.carddemo.batch.service.InterestCalculationService;
import com.carddemo.common.money.Money;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
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
 * <h2>The business date is an opaque ten-character token, not a date this job parses</h2>
 *
 * <p>Assumptions: {@code app/cbl/CBACT04C.cbl:180} is {@code PROCEDURE DIVISION USING EXTERNAL-PARMS},
 * so the reference is a subprogram whose {@code LINKAGE SECTION} declares {@code PARM-DATE PIC X(10)} at
 * {@code :178}. That field has exactly ONE use in the program's 652 lines: {@code :476-480} concatenates
 * it with {@code WS-TRANID-SUFFIX PIC 9(06)} ({@code :173}) {@code DELIMITED BY SIZE} into
 * {@code TRAN-ID PIC X(16)}. Ten characters plus six digits fill that field exactly. The token is
 * therefore copied byte for byte and is never parsed, reformatted or validated as a calendar date on
 * this path -- {@link BusinessDate#token()} is the accessor used, and neither
 * {@link BusinessDate#parseIsoDateForRangeComparison()} nor {@code BusinessDate.identifierPrefix()}
 * takes part in building an identifier.</p>
 *
 * <p>Alternatives Considered: parsing the token and re-rendering it to one canonical layout, which is
 * the intuitive reading of "the business date" and is provably wrong here, because TWO different
 * ten-character layouts are committed to this repository and both must survive unchanged. The driver at
 * {@code app/jcl/INTCALC.jcl:22} passes {@code PARM='2022071800'}, the compact layout, and
 * {@code tests/e2e/test_interest_cycle.py} pins that same value; the golden harness injects the
 * separated layout and {@code tests/golden/interest/happy_path/transact.expected} record one carries
 * {@code TRAN-ID = 2024-01-15000001}, hyphens intact. A compact-normalising implementation would render
 * that day as {@code 2024011500} and miss the golden on every generated row; a separated-only
 * implementation would corrupt the driver's own parameter. Passing the token through untouched is the
 * only behaviour that satisfies both committed forms, which is why the raw accessor is used and the
 * normalising one is deliberately not.</p>
 *
 * <h2>The accrual arithmetic multiplies first and then truncates</h2>
 *
 * <p>Assumptions: {@code app/cbl/CBACT04C.cbl:464-465} is
 * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, and the parenthesised product
 * is formed BEFORE the division. {@link InterestCalculationService#monthlyInterest} owns that statement
 * and multiplies at full precision before dividing with an explicit scale, so the order is never
 * re-expressed here. Dividing first would reduce the intermediate to two decimals and then scale it up
 * again, which yields a different number of cents on many inputs.</p>
 *
 * <p>Trade-offs: the division TRUNCATES rather than rounding half up, which is the one place this module
 * departs from its own shared default. A search for {@code ROUNDED} across all 652 lines of the
 * reference returns nothing, and COBOL truncates an untagged {@code COMPUTE}, so
 * {@code Money.BASELINE_INTEREST_ROUNDING} is {@link java.math.RoundingMode#DOWN} where
 * {@code Money.GENERAL_ROUNDING} is {@link java.math.RoundingMode#HALF_UP}. The compromise accepted is
 * that this one formula reads inconsistently with every other money operation in the module; the
 * alternative -- rounding half up for consistency -- would credit an extra cent on any balance and rate
 * whose exact quotient carries a third decimal, and the committed goldens would show it.</p>
 *
 * <h2>Where the generated transactions go, and the three datasets not to confuse</h2>
 *
 * <p>Assumptions: the generated rows are staged into a new generation of the {@code SYSTRAN} family,
 * which is what {@code app/jcl/INTCALC.jcl:37-41} allocates as
 * {@code DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)} with {@code LRECL=350}, and whose base is defined in
 * {@code app/jcl/DEFGDGB.jcl} with {@code LIMIT(5)} and an explicit {@code SCRATCH}. Three datasets carry
 * transaction records in this chain and only one of them is this job's output: the posting step writes
 * {@code TRANFILE}, the transaction MASTER; the backup step writes {@code TRANSACT.BKUP}; and this step
 * writes the sequential generation its own DD calls {@code TRANSACT} but whose dataset is
 * {@code SYSTRAN}. {@code app/jcl/COMBTRAN.jcl:24,26} later concatenates {@code TRANSACT.BKUP(0)} with
 * {@code SYSTRAN(0)}, so naming this output after either of the other two would silently make the merge
 * read one input twice.</p>
 *
 * <h2>The control break, and the one place this job diverges from the reference</h2>
 *
 * <p>Assumptions: the walk is ordered by account, then transaction type, then category, so every row of
 * one account arrives consecutively and a change of account is the signal to write that account's
 * accumulated interest. That is the classic control break at
 * {@code app/cbl/CBACT04C.cbl:194-205}, and the ordering is what makes it correct -- an unordered walk
 * would break on the same account repeatedly and write partial totals.</p>
 *
 * <p>Refactoring Rationale: the reference's loop writes an account's total when the NEXT account arrives,
 * and the last account never has a next account, so the reference never writes the final account's
 * interest. The omission is structural rather than incidental, and it is worth naming precisely because
 * the code LOOKS as though it handles the case. {@code app/cbl/CBACT04C.cbl:219-220} carries an
 * {@code ELSE PERFORM 1050-UPDATE-ACCOUNT} that exists for exactly this purpose, but it is
 * UNREACHABLE: {@code 1000-TCATBALF-GET-NEXT} at {@code :325-348} sets {@code END-OF-FILE} to
 * {@code 'Y'} the moment the read reports file status {@code '10'}, and the enclosing
 * {@code PERFORM UNTIL END-OF-FILE = 'Y'} at {@code :188} re-tests that flag before the loop body can be
 * entered again, so control leaves the loop without ever taking the {@code ELSE} arm.</p>
 *
 * <p>Refactoring Rationale: what the last account loses is more than one interest credit, because
 * {@code 1050-UPDATE-ACCOUNT} at {@code :350-370} makes THREE state changes and the unreachable arm skips
 * all three -- {@code :352} adds the accumulated interest to {@code ACCT-CURR-BAL}, {@code :353} zeroes
 * {@code ACCT-CURR-CYC-CREDIT} and {@code :354} zeroes {@code ACCT-CURR-CYC-DEBIT} before {@code :356}
 * rewrites the record. So the final account keeps a stale pair of cycle buckets as well as missing its
 * accrual, and the effect compounds rather than staying local: those buckets feed the over-limit
 * projection the posting step computes, so an unreset account carries a whole prior cycle's activity into
 * the next cycle's credit-limit test. This job therefore flushes the final account through the same path
 * as any other control break. The divergence is deliberate and is registered as {@code D-3} in
 * {@code docs/architecture/cobol-to-service-traceability.md} §7.1. Every other rule here is reproduced
 * exactly, including the two that look wrong at first reading and are not -- the zero-rate skip and the
 * cycle-total reset.</p>
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
    public static final String STEP_NAME = JOB_NAME + BatchJobName.STEP_NAME_SUFFIX;

    /** The name of the single object each staged generation of the {@code SYSTRAN} family holds. */
    public static final String DATASET_OBJECT_NAME = "systran";

    /** The prefix of the temporary file the generated rows are streamed into before upload. */
    private static final String STAGING_FILE_PREFIX = "carddemo-systran-";

    /** The banner the reference writes on entry, at {@code app/cbl/CBACT04C.cbl:181}. */
    private static final String START_BANNER = "START OF EXECUTION OF PROGRAM CBACT04C";

    /** The banner the reference writes on exit, at {@code app/cbl/CBACT04C.cbl:230}. */
    private static final String END_BANNER = "END OF EXECUTION OF PROGRAM CBACT04C";

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

    /** The resolver that allocates the {@code SYSTRAN} generation and applies its retention rule. */
    private final DatasetGenerationService generations;

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
     * @param generations the generation resolver standing in for the {@code SYSTRAN(+1)} allocation at
     *     {@code app/jcl/INTCALC.jcl:37-41}; must not be {@code null}
     * @param clock the clock the generated stamps are read from; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CalculateInterestJob(TransactionCategoryBalanceRepository categoryBalances,
            InterestCalculationService interest, BatchStepLedger ledgerOfSteps,
            DatasetGenerationService generations, Clock clock) {

        this.categoryBalances =
                Objects.requireNonNull(categoryBalances, "categoryBalances must not be null");
        this.interest = Objects.requireNonNull(interest, "interest must not be null");
        this.ledgerOfSteps = Objects.requireNonNull(ledgerOfSteps, "ledgerOfSteps must not be null");
        this.generations = Objects.requireNonNull(generations, "generations must not be null");
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
        Accrual accrual = new Accrual(businessDate);

        // WHY : Assumptions: the reference's two DISPLAY banners are carried across as structured log
        //       events rather than dropped or turned into a written output record. app/cbl/CBACT04C.cbl
        //       writes them to SYSOUT at :181 and :230, which is the job log and not a dataset any
        //       downstream step reads, so the operator-visible marker is preserved while the step's
        //       data output stays exactly the one generation it allocates below.
        LOG.info("event=batch.interest.started banner=\"{}\" runId={} businessDate={}",
                START_BANNER, runId, businessDate.token());

        BatchStepLedger.StepOutcome outcome = this.ledgerOfSteps.runStep(
                runId, STEP_NAME, BatchJobName.CALCULATE_INTEREST,
                () -> accrueEveryCategoryBalance(runId, accrual));

        // WHY : Assumptions: WS-RECORD-COUNT at :172 is incremented at :192 and never displayed
        //       anywhere in the reference, so there is no output line to reproduce for it. The count is
        //       surfaced through the shared summary record instead, which exists precisely to carry what
        //       a finished step measured about itself; inventing a printed total here would add an
        //       observable line the baseline does not emit.
        // WHY : Assumptions: the rejected and skipped counters are reported as the walk measured them,
        //       and rejected is structurally zero because this job has no reject path -- the reference
        //       writes no reject stream and contains no RETURN-CODE statement at all, so it either
        //       completes or abends through 9999-ABEND-PROGRAM. The summary type enforces the same
        //       reading: its compact constructor refuses the soft-warn tier for every job but posting.
        BatchRunSummary summary = new BatchRunSummary(runId, STEP_NAME,
                BatchJobName.CALCULATE_INTEREST, outcome.returnCode(), accrual.rowsRead,
                accrual.accrualsWritten, 0L, accrual.rowsSkipped, Map.of());

        LOG.info("event=batch.interest.finished banner=\"{}\" rows={} accruals={} accounts={}"
                        + " skipped={} tier={} stepSkipped={}",
                END_BANNER, summary.recordsRead(), summary.recordsWritten(), accrual.accountsUpdated,
                summary.recordsSkipped(), summary.returnCode(), outcome.skipped());

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
     * <p>Assumptions: the generated rows are streamed into the newly allocated {@code SYSTRAN}
     * generation as they are produced, which is the shape {@code app/cbl/CBACT04C.cbl} itself has -- it
     * opens the output at {@code :186}, writes one record per accrual at {@code :500}, and closes at
     * {@code :228}. Accumulating the run's rows in memory and staging them at the end would give the
     * same bytes, but a category-balance master is as large as the ledger, so it would bound the step by
     * heap where the reference was bounded by sequential output.</p>
     *
     * @param runId the orchestrator execution the generation allocation belongs to; must not be
     *     {@code null}
     * @param accrual the running state of the walk, whose counters the caller reports; must not be
     *     {@code null}
     * @return {@link BatchReturnCode#CLEAN} always, because this job has no warn tier
     * @throws IllegalStateException if the staging file cannot be created, written or uploaded
     */
    private BatchReturnCode accrueEveryCategoryBalance(String runId, Accrual accrual) {
        // WHY : Assumptions: the allocation is idempotent for one (family, business date, run) triple,
        //       so a redriven state that already allocated reads its own recorded generation back rather
        //       than consuming a second one. That is what makes calling this before the walk safe: were
        //       it to allocate afresh per attempt, a retried step would write two generations where
        //       app/jcl/INTCALC.jcl:41 names one and would exhaust the five-generation retention window
        //       at twice the intended rate.
        DatasetGeneration target = this.generations.allocateNewGeneration(
                DatasetFamily.SYSTRAN, accrual.businessDate, runId);

        Path staged = createStagingFile();
        try {
            // WHY : Trade-offs: the walk is a streamed whole-table read rather than a keyset-paginated
            //       one, which is the opposite of the posting job's choice, and the difference is
            //       deliberate. A control break is only correct over an UNINTERRUPTED ordered sequence:
            //       a paginated walk would have to carry the open account's running total across page
            //       boundaries, and a page boundary falling inside an account would then be
            //       indistinguishable from a control break unless that state were threaded through. The
            //       stream keeps the sequence whole for the duration of one transaction, which is what
            //       the reference's sequential read gave it. The cost is that the step holds one cursor
            //       open for its duration.
            try (OutputStream sink = Files.newOutputStream(staged);
                    Stream<TransactionCategoryBalance> rows = this.categoryBalances
                            .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc()) {

                // WHY : Alternatives Considered: iterating the stream with an enhanced for over its
                //       iterator rather than with forEach. forEach cannot let a checked write failure
                //       out of its lambda, so the staging error would have to be wrapped inside the
                //       walk and would lose the row context an operator needs; the for loop propagates
                //       it to the one handler below that names the file.
                for (TransactionCategoryBalance row : (Iterable<TransactionCategoryBalance>)
                        rows::iterator) {
                    accrueOneRow(row, accrual, sink);
                }
            } catch (IOException unwritable) {
                throw new IllegalStateException(
                        "could not write the generated interest transactions to " + staged, unwritable);
            }

            // WHY : Refactoring Rationale: the final account's accumulated interest is written here,
            //       outside the walk, because the reference cannot reach its own equivalent. Its
            //       ELSE PERFORM 1050-UPDATE-ACCOUNT at app/cbl/CBACT04C.cbl:219-220 is unreachable
            //       once :325-348 sets the end-of-file flag that :188 re-tests, so the last account
            //       never receives the three state changes at :352-354. Reproducing that omission would
            //       drop one account's accrual and leave its cycle buckets stale every run, so it is
            //       corrected and registered as divergence D-3 in
            //       docs/architecture/cobol-to-service-traceability.md §7.1.
            flushOpenAccount(accrual);

            this.generations.stageDataset(target, DATASET_OBJECT_NAME, staged);
        } finally {
            // WHY : Assumptions: the temporary file is removed on every path, including a failed
            //       upload, because a batch task's ephemeral disk is finite and a failed step is
            //       retried. Leaving it would let a sequence of retries fill the volume and turn a
            //       transient upload failure into a task that can no longer start.
            deleteQuietly(staged);
        }

        int scratched = 0;
        for (DatasetGeneration agedOut : this.generations.generationsToScratch(DatasetFamily.SYSTRAN)) {
            scratched += this.generations.scratchGeneration(agedOut);
        }

        LOG.info("event=batch.interest.completed rows={} accruals={} accounts={} generation={}"
                        + " scratchedObjects={} location={}",
                accrual.rowsRead, accrual.accrualsWritten, accrual.accountsUpdated,
                target.generationNumber(), scratched, this.generations.datasetUri(target));
        return BatchReturnCode.CLEAN;
    }

    /**
     * Accrues one category balance, breaking to a new account first when the key has moved on.
     *
     * @param row the category balance being accrued; must not be {@code null}
     * @param accrual the running state of the walk; must not be {@code null}
     * @param sink the open staging stream the generated record is appended to; must not be {@code null}
     * @throws IOException if the generated record cannot be appended to the staging stream
     */
    private void accrueOneRow(TransactionCategoryBalance row, Accrual accrual, OutputStream sink)
            throws IOException {

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
            // WHY : Alternatives Considered: resolving the card with a plain unique-result finder
            //       keyed on the account, which is the obvious reading of "read the cross-reference by
            //       account" and is wrong for this access path. app/jcl/INTCALC.jcl:31-32 mounts
            //       XREFFIL1 over the ALTERNATE INDEX PATH, and that index is defined
            //       KEYS(11,25) NONUNIQUEKEY at app/jcl/XREFFILE.jcl:74-75, so an account holding more
            //       than one card has more than one matching row and a unique-result finder would
            //       raise on exactly the data the index exists to support. The delegate uses the
            //       repository's ordered, single-row path instead, taking the lowest card number
            //       ascending, which both tolerates the multi-card account and makes the choice
            //       deterministic -- an unordered "any row" answer could stamp a different card on the
            //       same account between two runs and the goldens compare bytes.
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
        // WHY : Refactoring Rationale: the account identifier was this line's only field and it is gone.
        //       docs/architecture/observability.md names the account identifier among the values a
        //       retained log line may not hold and requires omission rather than abbreviation, so there
        //       is no partial form to fall back to. The row ordinal replaces it: the pass reads rows in
        //       one deterministic order, so the ordinal locates the row within this run's input, and the
        //       skip total the summary already reports says how many such rows there were. What is given
        //       up is joining the line to an account without re-reading the input in the same order --
        //       accepted, because the account is by definition absent from the master, so its number
        //       identifies nothing a reader could then look up.
        if (accrual.openAccount == null) {
            accrual.rowsSkipped++;
            LOG.warn("event=batch.interest.account-absent rowOrdinal={} rowsSkipped={}",
                    accrual.rowsRead, accrual.rowsSkipped);
            return;
        }

        // WHY : Assumptions: the key's components are supplied in the order the COPYBOOK declares them
        //       and NOT in the order the reference moves them. app/cpy/CVTRA02Y.cpy lays DIS-GROUP-KEY
        //       out as DIS-ACCT-GROUP-ID X(10) at offset 0, DIS-TRAN-TYPE-CD X(02) at 10 and
        //       DIS-TRAN-CAT-CD 9(04) at 12, a sixteen-byte key; but app/cbl/CBACT04C.cbl:210-212 moves
        //       group id, then CATEGORY, then TYPE. Move order is irrelevant to a COBOL group item
        //       because each MOVE names its own subordinate field, so the two orders describe the same
        //       bytes -- transcribing the move order into a positional argument list, however, would
        //       silently transpose the type and category components and look right while resolving the
        //       wrong row. The shared key record is declared in physical order for this reason.
        DisclosureGroupKey requested = DisclosureGroupKey.ofBlankPaddedAccountGroupId(
                accrual.openAccount.getGroupId(), row.getId().getTypeCd(),
                Integer.parseInt(row.getId().getCategoryCd().trim()));

        // WHY : Assumptions: the lookup answers with a resolved rate or it raises, and there is no
        //       third "not found" result to test for. app/cbl/CBACT04C.cbl:422 accepts file status '23'
        //       as normal and retries under the substituted group at :436-438, and the retry at :443
        //       carries no INVALID KEY clause at all -- so a run in which neither key resolves abends
        //       at :455 rather than continuing with no rate.
        // WHY : Assumptions: the retry substitutes ONLY the group-id component. :437 moves 'DEFAULT'
        //       into FD-DIS-ACCT-GROUP-ID and leaves the type and category codes exactly as the driving
        //       row supplied them, so the second key is the blank-padded literal followed by the SAME
        //       two-character type and four-character category. The seeding consequence is easy to get
        //       wrong and worth stating where the abend originates: one catch-all row will not do, and
        //       the rate table needs a DEFAULT row per (type, category) pair actually in use. That
        //       seeding belongs to reference-service's V2__seed_reference.sql, so an unresolvable
        //       DEFAULT surfaces here as a hard failure but is not a defect of this job -- an operator
        //       reading the abend should look at the reference data, not at this walk.
        InterestRateLookup lookup = this.interest.rateFor(requested);
        if (!lookup.interestApplicable()) {
            // WHY : Assumptions: a zero rate is skipped rather than accrued at zero, matching
            //       app/cbl/CBACT04C.cbl:214 which guards the whole computation with
            //       IF DIS-INT-RATE NOT = 0. The difference is observable: accruing at zero would write a
            //       transaction of amount zero for every category the account holds, and the reference
            //       writes none. The gate encloses :215 AND :216, so the fee call is skipped with it.
            return;
        }

        // WHY : Assumptions: the arithmetic is delegated whole and is never re-expressed here, because
        //       app/cbl/CBACT04C.cbl:464-465 forms the product ( TRAN-CAT-BAL * DIS-INT-RATE ) BEFORE
        //       dividing by 1200 and the migration's transformation rule T4 forbids re-ordering the two.
        //       The delegate multiplies at full precision and only then divides with an explicit scale
        //       and rounding mode; dividing first would round the intermediate to two decimals and then
        //       scale it back up, which lands on a different cent for many balance-and-rate pairs.
        // WHY : Trade-offs: the delegate truncates rather than rounding half up, which contradicts the
        //       shared money type's own general default and is correct here. The reference carries no
        //       ROUNDED phrase anywhere in its 652 lines, and an untagged COBOL COMPUTE truncates, so
        //       the accrual path uses the DOWN mode the shared type publishes for exactly this baseline
        //       while every other money operation in the module keeps HALF_UP. The inconsistency is
        //       accepted deliberately: a reader who knows the general default will otherwise read this
        //       as a bug, and rounding half up "for consistency" would credit an extra cent whenever the
        //       exact quotient carries a third decimal.
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
        //       :474 never resets it on a control break -- the committed golden shows consecutive
        //       accounts carrying 000001 and 000002, so a per-account counter would repeat 000001 and
        //       collide on the identifier.
        accrual.suffix++;
        Transaction generated = this.interest.writeInterestTransaction(accountId,
                accrual.openCardNumber, accrued, accrual.businessDate, accrual.suffix,
                LocalDateTime.now(this.clock));

        // WHY : Assumptions: the row is appended to the generation as well as persisted, because the
        //       reference writes it to the sequential SYSTRAN output at :500 while the migrated model
        //       needs it in the ledger for the merge step to read. The staged bytes come from the shared
        //       record mapper so the object holds the reference's own 350-byte layout rather than a
        //       convenient serialisation, which is what makes the generation comparable with
        //       tests/golden/interest/happy_path/transact.expected.
        // WHY : Alternatives Considered: the mapper's single-argument overload, which is the obvious
        //       call and silently selects the POSTED_MASTER layout. It is wrong for these rows. The two
        //       registered layouts differ in exactly one flag -- whether TRAN-ORIG-TS is a normalisable
        //       field -- and they differ because the two writers differ: posting carries the
        //       origination stamp through from its feed record verbatim, so that field must be compared
        //       byte for byte, whereas :496-498 reads the clock ONCE and moves the same value into BOTH
        //       stamps, so both are run-generated and a parity comparator has to blank them. Selecting
        //       INTEREST_GENERATED is what tells it so; the committed golden blanks both stamps for
        //       precisely this reason, and the master layout would have made every run's staged
        //       generation miscompare on a field that cannot be stable.
        sink.write(TransactionRecordMapper.toRecord(generated,
                TransactionRecordMapper.Layout.INTEREST_GENERATED));

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
     * Creates the temporary file the generated rows are streamed into before upload.
     *
     * @return the newly created empty staging file, never {@code null}
     * @throws IllegalStateException if the file cannot be created, because the step has no output to
     *     stage without it
     */
    private static Path createStagingFile() {
        try {
            return Files.createTempFile(STAGING_FILE_PREFIX, ".dat");
        } catch (IOException unavailable) {
            throw new IllegalStateException(
                    "could not create the temporary file the generated interest transactions stream"
                            + " through", unavailable);
        }
    }

    /**
     * Removes a temporary file, reporting rather than raising when it cannot be removed.
     *
     * <p>Assumptions: a failed deletion is logged and swallowed rather than raised, because it runs in a
     * finally block that is also reached on paths already failing, and re-raising there would replace the
     * real failure with a cleanup one. The consequence -- a file left behind -- is bounded by the
     * container's lifetime.</p>
     *
     * @param file the file to remove; must not be {@code null}
     */
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException undeletable) {
            LOG.warn("event=batch.interest.temp-file-retained path={} reason={}",
                    file, undeletable.getClass().getName());
        }
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

        /** How many rows the walk skipped because their account could not be read. */
        private long rowsSkipped;

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
