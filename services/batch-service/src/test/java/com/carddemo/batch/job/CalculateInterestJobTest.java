package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DisclosureGroup;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchJobParameters;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.dto.DatasetGeneration.GenerationReference;
import com.carddemo.batch.dto.DisclosureGroupKey;
import com.carddemo.batch.dto.InterestRateLookup;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DisclosureGroupRepository;
import com.carddemo.batch.repository.TransactionCategoryBalanceRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.DatasetGenerationService;
import com.carddemo.batch.service.InterestCalculationService;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.ZonedDecimalCodec;
import com.carddemo.common.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
 * Pins the argument contract, the walk structure and the committed-expectation parity of the
 * interest accrual job.
 *
 * <p>Purpose: this is the structural-tier case file for {@link CalculateInterestJob}, state five of
 * the nightly chain, which re-expresses {@code app/cbl/CBACT04C.cbl} under the driver
 * {@code app/jcl/INTCALC.jcl:22}. Four contracts are settled here and nowhere else in this module.
 * First, the BUSINESS-DATE JOB PARAMETER: that it is required, that a launch without it is refused,
 * that it is never derived from a clock, that both committed ten-character layouts survive the
 * launch unaltered, and that a token of any other width fails the run. Second, the JOB-LEVEL
 * CONTROL BREAK: that rows are consumed grouped by account, that each change of account flushes
 * exactly once, that the first row flushes nothing, and that one transaction is emitted per CATEGORY
 * row. Third, the CORRECTED FINAL-ACCOUNT FLUSH registered as divergence {@code D-3}. Fourth,
 * INTEREST-DOMAIN PARITY against the three committed expectation trees under
 * {@code tests/golden/interest}.</p>
 *
 * <p>Assumptions: the accrual ARITHMETIC is settled by the sibling service tier and is not
 * re-derived here. {@code com.carddemo.batch.service.InterestCalculationServiceTest} owns the
 * multiply-before-divide ordering of {@code app/cbl/CBACT04C.cbl:464-465}, the rounding mode that
 * statement does not name, the per-category-row reduction at {@code :467}, the {@code DEFAULT}
 * disclosure-group substitution at {@code :436-439} and the zero-rate gate at {@code :214}; the
 * sibling {@code DatasetGenerationServiceTest} owns the retained-generation count and its per-run
 * memoisation. A case here that restated any of them would create a second declaration of one
 * contract, which is how two declarations come to disagree.</p>
 *
 * <p>Assumptions: the job is RUN through the framework rather than having its tasklet invoked
 * directly, for the reason the sibling {@code PostTransactionsJobTest} records: a real execution
 * over an in-memory job repository and a no-op transaction manager exercises the step lifecycle,
 * the parameter validator the job is built with, and the exit-status propagation from step to job --
 * and the third of those is how the orchestrating state machine learns the tier.</p>
 *
 * <p>Trade-offs: no case here starts an application context, selects a profile or requests a
 * database container, and the omission is deliberate rather than a shortcut. The charter at
 * {@code services/batch-service/src/test/java/com/carddemo/batch/job/package-info.java} assigns
 * container-backed cases to {@code com.carddemo.batch.repository}, where they are named for the
 * integration runner and run at verify against an environment that runner prepares; a container
 * started from this package would be collected by the unit runner at a phase that prepares nothing.
 * What is given up is the ability to observe real multi-schema resolution and real grant
 * enforcement, which that package already proves. What is bought is that every ruling below is
 * about which calls this job makes and which bytes it emits -- properties a database would answer
 * identically while adding a container start to each of them.</p>
 *
 * <p>Trade-offs: two collaborator regimes are used in one class. The structural cases supply a
 * mocked {@link InterestCalculationService}, because they assert the ORDER and the COUNT of the
 * calls the walk makes. The expectation-parity cases and the final-flush case supply the REAL rule
 * over mocked repositories, because a mocked rule cannot show the account state a flush produces --
 * an assertion on a mock's own argument would hold whether or not the transition was applied. The
 * cost is that a reader must notice which regime a case is in; the two are separated into named
 * groups for exactly that reason, and splitting them into two top-level classes was declined because
 * the charter closes this directory's roster at six case files.</p>
 *
 * <p>Refactoring Rationale: no committed expectation is ever regenerated from this file. The parity
 * oracle suite ships a guarded update gate, documented in section 12 of {@code tests/README.md},
 * and nothing of the kind exists here: every expectation file below is opened read-only and there is
 * no environment switch, no update argument and no write-if-missing branch anywhere in this class.
 * The asymmetry is the point -- that suite's expectations describe the REFERENCE, whose behaviour is
 * fixed, so regenerating one records a corrected reading of an unchanged program, whereas a switch
 * here would rewrite the expectation to match whatever the migrated code currently produces and turn
 * this module's one independent check into a restatement of its own output.</p>
 */
@DisplayName("the interest accrual job")
class CalculateInterestJobTest {

    /**
     * The business-date token the cases inject, in the separated ten-character layout.
     *
     * <p>Assumptions: this is the token the three committed scenario expectations were produced
     * with. Section 8.2 of {@code services/batch-service/src/test/resources/fixtures/README.md}
     * measures that all three interest scenario expectations carry the separated layout while both
     * interest end-to-end expectations carry the compact one, so a parity case has to use this
     * one.</p>
     */
    private static final String BUSINESS_DATE = "2024-01-15";

    /**
     * The business-date token the reference's own driver injects, in the compact ten-character
     * layout.
     *
     * <p>Assumptions: read from {@code app/jcl/INTCALC.jcl:22},
     * {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'} -- the only {@code PARM=} on any
     * migrated batch program, and exactly ten characters.</p>
     */
    private static final String COMPACT_BUSINESS_DATE = "2022071800";

    /** The orchestrator execution identifier the cases run under. */
    private static final String RUN_ID = "batch-run-0001";

    /** The account whose record the master can be read for. */
    private static final long READABLE_ACCOUNT = 11111111111L;

    /** A second readable account, used where a control break has to be observable. */
    private static final long SECOND_ACCOUNT = 33333333333L;

    /** The account whose record is absent from the master. */
    private static final long ORPHANED_ACCOUNT = 22222222222L;

    /** The card the readable account's cross-reference resolves to. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The card the second readable account's cross-reference resolves to. */
    private static final String SECOND_CARD_NUMBER = "4111111111113333";

    /** The disclosure-group identifier the structural cases resolve a rate under. */
    private static final String GROUP_ID = "A000000000";

    /** The transaction type code every category row in this file carries. */
    private static final String TYPE_CODE = "01";

    /** The four-character transaction category code the driving rows carry. */
    private static final String CATEGORY_CODE = "0001";

    /** The accrual the mocked rule reports for one category row. */
    private static final String ACCRUED_INTEREST = "2.08";

    /** The category balance the structural cases drive the accrual from. */
    private static final String CATEGORY_BALANCE = "1000.00";

    /** The job-instance identifier the cases run under, which no assertion depends on. */
    private static final long INSTANCE_ID = 1L;

    /** The job-execution identifier the cases run under, which no assertion depends on. */
    private static final long EXECUTION_ID = 1L;

    /**
     * The instant the fixed clock reads, deliberately on a different day from every token used
     * here.
     *
     * <p>Assumptions: the day differs from both {@link #BUSINESS_DATE} and
     * {@link #COMPACT_BUSINESS_DATE} on purpose, so that a job which read the clock for its business
     * date could not accidentally produce the value a case asserts.</p>
     */
    private static final LocalDateTime CLOCK_INSTANT = LocalDateTime.of(2022, 7, 18, 1, 2, 3);

    /**
     * The three committed scenario directories of the interest domain.
     *
     * <p>Assumptions: the same three names appear again in the parameter source of the
     * expectation-parity case below, and the duplication is unavoidable rather than an oversight -- an
     * annotation's value must be a compile-time constant, so a parameterised case cannot read this
     * list. The two are kept honest by the same mechanism: every scenario named in either place has
     * its expectation file existence asserted when it is read, so a name in one and not the other
     * fails rather than passing quietly.</p>
     */
    private static final List<String> SCENARIOS =
            List.of("happy_path", "default_fallback", "zero_balance");

    /** The domain directory the interest fixtures and expectations both live under. */
    private static final String INTEREST_DOMAIN = "interest";

    /**
     * The classpath prefix of this module's own committed interest fixture images.
     *
     * <p>Refactoring Rationale: the driving inputs were read from the parity oracle's
     * {@code tests/fixtures/interest} tree and are now read from this module's own, which the build
     * packages onto the test classpath. Two things were wrong with reaching outside. The module tree
     * was committed and documented and READ BY NOTHING, so its scenarios were carried in the build as
     * dead weight and a corruption in one of them would have reached a release unremarked. And a case
     * whose inputs live outside its own module passes in a checkout where the module's inputs are
     * missing, which is the opposite of what a module's test tree is for. The expectation files stay
     * where they are, because those describe the REFERENCE and belong to the oracle; only the inputs
     * moved. {@link #everyDrivingFixtureMatchesItsOracleDerivation} is what keeps the two trees
     * agreeing now that only one of them is read.</p>
     */
    private static final String FIXTURE_INTEREST_ROOT = "fixtures/" + INTEREST_DOMAIN + "/";

    /**
     * The four driving inputs every interest scenario commits.
     *
     * <p>Assumptions: the list is declared once and used by both the parity case's reads and the
     * provenance case's comparison, so a file added to the tree and consumed by the run cannot escape
     * the drift check, and one named here and absent from the tree fails both.</p>
     */
    private static final List<String> DRIVING_FIXTURES =
            List.of("acctdata.txt", "cardxref.txt", "discgrp.txt", "tcatbal.txt");

    /** The byte a fixed-width record file terminates each stored record with. */
    private static final byte LINE_FEED = 0x0A;

    /** The generation number the stubbed allocator answers with, being the first of a family. */
    private static final int FIRST_GENERATION = 1;

    /** The object key the stubbed allocator reports for a staged payload. */
    private static final String STAGED_OBJECT_KEY = "ledger/systran/a/staged/key";

    /**
     * The customer identifier the cross-reference doubles carry, on which nothing asserts.
     *
     * <p>Assumptions: the accrual path reads only the card number out of a cross-reference row --
     * {@code app/cbl/CBACT04C.cbl:495} moves {@code XREF-CARD-NUM} and nothing else -- so the customer
     * component is present because the record declares it and not because any ruling depends on
     * it.</p>
     */
    private static final long UNASSERTED_CUSTOMER_ID = 0L;

    /** The registry name of the layout the interest job's generated records are compared under. */
    private static final String INTEREST_LAYOUT = "INTTRAN";

    /** The registry name of the account master layout the expectation records are decoded under. */
    private static final String ACCOUNT_LAYOUT = "ACCOUNT";

    /** The registry name of the category-balance layout the driving fixtures are decoded under. */
    private static final String CATEGORY_BALANCE_LAYOUT = "TCATBAL";

    /** The registry name of the disclosure-group layout the rate fixtures are decoded under. */
    private static final String DISCLOSURE_GROUP_LAYOUT = "DISGROUP";

    /** The registry name of the cross-reference layout the card fixtures are decoded under. */
    private static final String CROSS_REFERENCE_LAYOUT = "XREF";

    /**
     * The opening banner text, verbatim from {@code app/cbl/CBACT04C.cbl:181}.
     *
     * <p>Assumptions: it is spelled out here rather than read from the job, whose own copy is private.
     * A test that reached for the production constant could only prove the job agrees with itself; the
     * claim worth making is that it agrees with the reference program, so the reference's text is
     * transcribed and cited.</p>
     */
    private static final String START_BANNER_TEXT = "START OF EXECUTION OF PROGRAM CBACT04C";

    /** The closing banner text, verbatim from {@code app/cbl/CBACT04C.cbl:230}. */
    private static final String END_BANNER_TEXT = "END OF EXECUTION OF PROGRAM CBACT04C";

    /**
     * The event name of the per-row observation that stands in for {@code CBACT04C.cbl:193}.
     *
     * <p>Assumptions: the event NAME is matched rather than the whole rendered line, because the line's
     * remaining content -- ordinal, type code, category code -- is what the case then asserts field by
     * field. Matching the full line would restate the assertion inside the filter and would fail for a
     * reason the report could not distinguish from a missing row.</p>
     */
    private static final String ROW_READ_EVENT = "event=batch.interest.row-read";

    /** The low-value byte the reference leaves in the two pad regions of a generated record. */
    private static final byte LOW_VALUE = 0x00;

    /** The blank byte a fixed-width encode rebuilds a dropped pad region with. */
    private static final byte BLANK = 0x20;

    /** The category balances the walk reads. */
    private TransactionCategoryBalanceRepository categoryBalances;

    /** The rate resolution and accrual arithmetic, mocked for the structural cases. */
    private InterestCalculationService interest;

    /** The durable step ledger, stubbed to evaluate its body. */
    private BatchStepLedger ledgerOfSteps;

    /** The generation resolver the staged {@code SYSTRAN} output is allocated through. */
    private DatasetGenerationService generations;

    /** The framework's in-memory job repository. */
    private JobRepository jobRepository;

    /** The job under test, built over the mocked collaborators. */
    private Job job;

    /**
     * Builds the mocked collaborators and the job over them, with a rate that does accrue.
     *
     * <p>Assumptions: a fresh set is built per case, because every case asserts call counts and a
     * shared mock would carry one case's calls into the next.</p>
     */
    @BeforeEach
    void buildJob() {
        this.categoryBalances = mock(TransactionCategoryBalanceRepository.class);
        this.interest = mock(InterestCalculationService.class);
        this.ledgerOfSteps = mock(BatchStepLedger.class);
        this.generations = mock(DatasetGenerationService.class);

        // WHY : Assumptions: the step ledger stub EVALUATES the body it is handed. A
        //       default-returning mock would run none of the accrual and every count asserted below
        //       would be zero, so the cases would pass while exercising nothing at all.
        stubLedgerToEvaluateItsBody(this.ledgerOfSteps);
        stubGenerationsToAnswer(this.generations);

        // WHY : Assumptions: the key is built through the SAME factory the job itself calls, so a
        //       blank-stripped group id reaches its declared ten-character width the one way
        //       production reaches it. Constructing the record canonically here would need a
        //       hand-padded literal, which is a second padding mechanism that could drift from the
        //       first.
        DisclosureGroupKey key =
                DisclosureGroupKey.ofBlankPaddedAccountGroupId(GROUP_ID, TYPE_CODE, 1);
        when(this.interest.rateFor(any()))
                .thenReturn(new InterestRateLookup(key, key, new BigDecimal("2.50")));
        when(this.interest.monthlyInterest(any(), any()))
                .thenReturn(Money.of(new BigDecimal(ACCRUED_INTEREST)));
        when(this.interest.loadCrossReference(READABLE_ACCOUNT)).thenReturn(CARD_NUMBER);
        when(this.interest.loadCrossReference(SECOND_ACCOUNT)).thenReturn(SECOND_CARD_NUMBER);

        // WHY : Assumptions: the accrual write is stubbed to RETURN the row it would have persisted,
        //       because the walk appends that row's fixed-width image to the staged generation. A
        //       default-returning mock would hand the encoder a null and turn every case below into a
        //       staging failure. The identifier is assembled from the arguments the job actually
        //       passes -- the raw business-date token and the run-scoped suffix -- so the stub cannot
        //       disagree with the identifier rule the job is being tested against.
        when(this.interest.writeInterestTransaction(anyLong(), anyString(), any(), any(), anyLong(),
                any())).thenAnswer(call -> generatedRow(
                        call.getArgument(0), call.getArgument(1), call.getArgument(3),
                        call.getArgument(4), call.getArgument(5)));

        CalculateInterestJob configuration = new CalculateInterestJob(this.categoryBalances,
                this.interest, this.ledgerOfSteps, this.generations, fixedClock());

        this.jobRepository = new ResourcelessJobRepository();
        this.job = configuration.calculateInterest(
                this.jobRepository, new ResourcelessTransactionManager(), sharedValidator());
    }

    /** The name the orchestrator selects this job by, and the name its step is recorded under. */
    @Nested
    @DisplayName("the registered name")
    class RegisteredName {

        /**
         * The job registers under the shared token and not under a name spelled here.
         *
         * <p>Pins the token the package charter records for state five of the nightly chain against
         * {@code com.carddemo.batch.dto.BatchJobName}, which is what
         * {@code com.carddemo.batch.BatchApplication} resolves a {@code --job=} argument through.</p>
         */
        @Test
        @DisplayName("register under the token the shared vocabulary publishes")
        void theRegisteredNameIsTheSharedToken() {
            assertThat(CalculateInterestJobTest.this.job.getName())
                    .isEqualTo(BatchJobName.CALCULATE_INTEREST.token())
                    .isEqualTo(CalculateInterestJob.JOB_NAME)
                    .isEqualTo("calculate-interest");

            // WHY : Assumptions: the token is asserted against the enumeration AND against a literal,
            //       and the literal is not redundant. The enumeration comparison alone would hold if
            //       both were renamed together, and the name is part of the orchestration definition
            //       outside this repository -- a state machine names the job in a container command,
            //       so a coordinated rename inside the reactor would still break the deployed chain.
            assertThat(BatchApplication.JOB_NAMES).contains(CalculateInterestJob.JOB_NAME);
        }

        /**
         * The durable ledger's step name is derived from the token rather than spelled separately.
         *
         * <p>Assumptions: the step name is the key the durable ledger records a run under, together
         * with the run identifier, so a step name that drifted from the job token would make a
         * redriven execution look like a different step and re-run work already recorded.</p>
         */
        @Test
        @DisplayName("record the step under the token plus the shared suffix")
        void theLedgerStepNameDerivesFromTheToken() {
            assertThat(CalculateInterestJob.STEP_NAME)
                    .isEqualTo(BatchJobName.CALCULATE_INTEREST.token() + BatchJobName.STEP_NAME_SUFFIX);
            assertThat(BatchJobName.forStepName(CalculateInterestJob.STEP_NAME))
                    .isEqualTo(BatchJobName.CALCULATE_INTEREST);
        }
    }

    /**
     * The business-date job parameter: required, opaque, ten characters, and never clock-derived.
     *
     * <p>Assumptions: this group is where the module's whole injected-date discipline is settled,
     * because {@code app/cbl/CBACT04C.cbl} is the ONLY migrated batch program that is a subprogram
     * taking a formal parameter -- {@code :180} is {@code PROCEDURE DIVISION USING EXTERNAL-PARMS} --
     * and {@code app/jcl/INTCALC.jcl:22} carries the only {@code PARM=} on any of them. There is no
     * other job whose launch could pin it.</p>
     */
    @Nested
    @DisplayName("the business-date parameter contract")
    class BusinessDateParameterContract {

        /**
         * A launch without the business date is refused by the validator the job is built with.
         *
         * <p>Refactoring Rationale: the parameter is REQUIRED rather than defaulted, which is the
         * migration's correction of a baseline in which only one step of the chain carried a date at
         * all -- {@code app/jcl/INTCALC.jcl:22} is the sole {@code PARM=}. Section 11 of
         * {@code tests/README.md} records why the correction goes this way: a business date is
         * injected as a parameter rather than read from the wall clock precisely so that a rerun of
         * one day reproduces that day's output, and a default would silently restore the clock
         * reading it exists to remove.</p>
         *
         * <p>Assumptions: the refusal is asserted through the job's own validator rather than by
         * starting the job, because a job started with invalid parameters never reaches its step.
         * What is settled is that the shared validator really is attached to THIS job -- a job built
         * without one would validate nothing and would run until the step read the parameter.</p>
         */
        @Test
        @DisplayName("refuse to run when the business date is absent")
        void aMissingBusinessDateIsRefused() {
            JobParameters withoutTheDate = new JobParametersBuilder()
                    .addString(BatchConfig.RUN_ID_PARAMETER, RUN_ID, false)
                    .toJobParameters();

            assertThat(CalculateInterestJobTest.this.job.getJobParametersValidator()).isNotNull();
            assertThat(CalculateInterestJobTest.this.catchValidation(withoutTheDate))
                    .as("a launch missing the injected business date is refused, not defaulted")
                    .isNotNull();
        }

        /**
         * The container command line that starts this job refuses to parse without the option.
         *
         * <p>Assumptions: the refusal is driven through {@code BatchApplication.parseArguments},
         * which is the module's ONLY argument parser and therefore the only path a deployed task
         * takes. Reassembling the parse here would be a second parser differing from the first only
         * in who wrote it, and a suite covering the copy could stay green while the parsing that
         * actually runs drifted away from it.</p>
         */
        @Test
        @DisplayName("refuse the command line when the business-date option is absent")
        void theCommandLineRefusesToStartWithoutTheOption() {
            String[] withoutTheOption = {BatchApplication.JOB_OPTION + CalculateInterestJob.JOB_NAME};

            assertThatThrownBy(() -> BatchApplication.parseArguments(withoutTheOption))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(BatchApplication.BUSINESS_DATE_OPTION);
        }

        /**
         * Both committed ten-character layouts reach the job byte for byte as supplied.
         *
         * <p>Pins {@code app/cbl/CBACT04C.cbl:476-480}, which {@code STRING}s
         * {@code PARM-DATE PIC X(10)} ({@code :178}) beside a {@code PIC 9(06)} counter
         * ({@code :173}) {@code DELIMITED BY SIZE} into a sixteen-character identifier. The token is
         * copied and never parsed; the program contains no date-parsing code at all.</p>
         *
         * <p>Alternatives Considered: exercising one layout and treating the other as equivalent,
         * which is the intuitive reading of "the business date" and is provably wrong here. Two
         * layouts are committed, both exactly ten characters: {@code app/jcl/INTCALC.jcl:22} passes
         * the compact {@code 2022071800}, while {@code tests/golden/interest/happy_path/}
         * {@code transact.expected} opens with {@code 2024-01-15000001}, hyphens intact. Section 8.2
         * of {@code services/batch-service/src/test/resources/fixtures/README.md} measures that the
         * two generated records are otherwise byte-identical and differ only in that ten-character
         * prefix, so forcing either layout provably breaks the other. This is the reason
         * {@code com.carddemo.batch.dto.BusinessDate} exposes a RAW token accessor separately from
         * its optional calendar-parsing one, and why a naive rendering of a parsed date is the
         * specific defect both layouts exist to catch: applied to the compact token it would emit
         * {@code 2024-01-15000001}-shaped bytes where {@code 2022071800000001} is expected, the same
         * sixteen characters and silently the wrong ones.</p>
         *
         * @param token the committed ten-character business-date layout under exercise, supplied as
         *     the {@code businessDate} job parameter and expected back unaltered
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @ParameterizedTest(name = "the token {0} reaches the job unaltered")
        @ValueSource(strings = {COMPACT_BUSINESS_DATE, BUSINESS_DATE})
        @DisplayName("carry both committed ten-character layouts through unaltered")
        void bothCommittedTenCharacterTokensReachTheJobUnaltered(String token) throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, CATEGORY_CODE, CATEGORY_BALANCE));
            CalculateInterestJobTest.this.stageReadableAccount();

            JobExecution execution = CalculateInterestJobTest.this.run(token);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(token).hasSize(BatchApplication.BUSINESS_DATE_LENGTH);
            assertThat(CalculateInterestJobTest.this.capturedBusinessDates())
                    .as("every collaborator receives the token exactly as the launch supplied it")
                    .isNotEmpty()
                    .allSatisfy(carried -> assertThat(carried.token()).isEqualTo(token));
        }

        /**
         * The business date comes from the argument even though the job also reads a clock.
         *
         * <p>Assumptions: this is the assertion that makes "never clock-derived" observable rather
         * than asserted by absence. The fixed clock this class installs reads
         * {@code 2022-07-18T01:02:03} while the injected token is {@code 2024-01-15}, and the job
         * demonstrably reaches the clock -- {@code app/cbl/CBACT04C.cbl:496-498} performs one
         * timestamp read and moves it into both stamps of the generated record, so the stamp argument
         * carries the clock's instant. The two values are therefore captured from the same call: the
         * stamp proves the clock was available, and the token proves the date did not come from
         * it.</p>
         *
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @Test
        @DisplayName("take the business date from the argument while reading the clock for stamps")
        void theBusinessDateIsNotDerivedFromTheClock() throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, CATEGORY_CODE, CATEGORY_BALANCE));
            CalculateInterestJobTest.this.stageReadableAccount();

            CalculateInterestJobTest.this.run(BUSINESS_DATE);

            ArgumentCaptor<BusinessDate> carriedDate = ArgumentCaptor.forClass(BusinessDate.class);
            ArgumentCaptor<LocalDateTime> carriedStamp =
                    ArgumentCaptor.forClass(LocalDateTime.class);
            verify(CalculateInterestJobTest.this.interest).writeInterestTransaction(anyLong(),
                    anyString(), any(Money.class), carriedDate.capture(), anyLong(),
                    carriedStamp.capture());

            assertThat(carriedDate.getValue().token()).isEqualTo(BUSINESS_DATE);
            assertThat(carriedStamp.getValue()).isEqualTo(CLOCK_INSTANT);
            assertThat(carriedStamp.getValue().toLocalDate())
                    .as("the clock reads a different day from the token, so the token cannot have"
                            + " come from the clock")
                    .isNotEqualTo(LocalDate.parse(BUSINESS_DATE));
        }

        /**
         * A token of any width other than ten fails the run rather than being adjusted.
         *
         * <p>Assumptions: the width IS the contract, taken from
         * {@code PARM-DATE PIC X(10)} at {@code app/cbl/CBACT04C.cbl:178}. Ten characters plus the
         * six digits of {@code WS-TRANID-SUFFIX PIC 9(06)} at {@code :173} fill
         * {@code TRAN-ID PIC X(16)} exactly, so a nine-character token would leave a digit of the
         * suffix outside the identifier and an eleven-character one would push a digit past its
         * end -- in both cases producing an identifier that is still the declared width and still
         * all-legal characters, which no downstream reader could reject.</p>
         *
         * <p>Assumptions: the refusal is observed at the RUN and not at the validator, because the
         * shared validator checks only that the two parameters are present. The width gate sits on
         * the path the step takes when it reads the parameter, which is what this case pins: a
         * mis-sized token reaches the step and fails it, rather than being trimmed or padded into
         * something acceptable.</p>
         *
         * @param token a business-date token whose length is not ten, one shorter and one longer than
         *     the declared width
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @ParameterizedTest(name = "the token {0} fails the run")
        @ValueSource(strings = {"2024-01-1", "2024-01-155"})
        @DisplayName("fail the run for a token that is not exactly ten characters")
        void aTokenOfTheWrongWidthFailsTheRun(String token) throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, CATEGORY_CODE, CATEGORY_BALANCE));
            CalculateInterestJobTest.this.stageReadableAccount();

            assertThat(token.length()).isNotEqualTo(BatchApplication.BUSINESS_DATE_LENGTH);

            JobExecution execution = CalculateInterestJobTest.this.run(token);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(failureTextOf(execution))
                    .contains("not exactly " + BatchApplication.BUSINESS_DATE_LENGTH);
            verify(CalculateInterestJobTest.this.interest, never()).writeInterestTransaction(
                    anyLong(), anyString(), any(), any(), anyLong(), any());
        }

        /**
         * An unrecognised option on the command line is ignored rather than rejected.
         *
         * <p>Assumptions: the tolerance is deliberate and is what lets an orchestration container
         * override carry arguments this module does not read without failing the task. The two
         * options this module defines are the job token and the business date; anything else on the
         * line is left to the framework's own command-line property source.</p>
         */
        @Test
        @DisplayName("ignore an unrecognised option instead of refusing the command line")
        void anUnrecognisedOptionIsIgnored() {
            String[] withAnExtraOverride = {
                BatchApplication.JOB_OPTION + CalculateInterestJob.JOB_NAME,
                BatchApplication.BUSINESS_DATE_OPTION + COMPACT_BUSINESS_DATE,
                "--carddemo-unrecognised-override=whatever",
            };

            BatchJobParameters parsed = BatchApplication.parseArguments(withAnExtraOverride);

            assertThat(parsed.jobName()).isEqualTo(BatchJobName.CALCULATE_INTEREST);
            assertThat(parsed.requireBusinessDate().token()).isEqualTo(COMPACT_BUSINESS_DATE);
        }
    }

    /**
     * The numeric result this job can and cannot report.
     *
     * <p>Trade-offs: the result is asserted in exactly two forms -- the value the application
     * returns, and the process exit status the orchestrating state machine reads -- and never as a
     * tolerance configured on a test runner. The graded five-tier rubric of section 8 of
     * {@code tests/README.md} belongs to the parity oracle suite, which aggregates the worst code
     * seen across its layers; Maven and the two class runners here are binary. What is given up is
     * the ability to report a partially successful build; what is bought is that a real failure
     * cannot be configured to read as an accepted warning, which is the only way the graded form
     * could be wired into a Java gate.</p>
     */
    @Nested
    @DisplayName("the numeric result")
    class NumericResult {

        /**
         * A clean run reports the zero tier that all three committed expectations record.
         *
         * <p>Assumptions: this job can NEVER report the soft-warn tier, and the absence is
         * mechanical rather than incidental: {@code app/cbl/CBACT04C.cbl} contains no
         * {@code RETURN-CODE} statement in any of its 652 lines and writes no reject stream, so it
         * either completes or abends through {@code 9999-ABEND-PROGRAM}. The three committed
         * {@code return_code.expected} files corroborate it, and they are read here rather than
         * paraphrased so the corroboration is a check and not a claim.</p>
         *
         * @throws Exception if the framework's own execution path raises, or an expectation file
         *     cannot be read from the repository tree
         */
        @Test
        @DisplayName("report the clean tier the committed expectations record, never the warn tier")
        void aCleanRunReportsTheZeroTierTheExpectationsRecord() throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, CATEGORY_CODE, CATEGORY_BALANCE));
            CalculateInterestJobTest.this.stageReadableAccount();

            JobExecution execution = CalculateInterestJobTest.this.run(BUSINESS_DATE);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isNotEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
            assertThat(BatchReturnCode.CLEAN.numericValue())
                    .isEqualTo(BatchApplication.EXIT_STATUS_CLEAN);

            for (String scenario : SCENARIOS) {
                assertThat(expectationText(scenario, "return_code.expected").trim())
                        .as("the committed expectation for scenario %s", scenario)
                        .isEqualTo(Integer.toString(BatchReturnCode.CLEAN.numericValue()));
            }
        }

        /**
         * A failing rate lookup reports the failure tier and is distinguishable from the warn tier.
         *
         * <p>Assumptions: the abend CONDITION itself -- that the {@code DEFAULT} retry at
         * {@code app/cbl/CBACT04C.cbl:443-455} accepts only file status {@code '00'}, so a missing
         * {@code DEFAULT} row abends rather than yielding a zero rate -- is settled by the sibling
         * service tier. What is settled here is the MAPPING: a failure of that kind leaves the
         * execution short of completion, which {@code BatchApplication.exitStatusOf} maps to the
         * hard-failure tier, and the exit code is not the warn code. The two tiers are distinct
         * numbers and the failure tier does not permit the downstream state to run.</p>
         *
         * @throws Exception if the framework's own execution path raises
         */
        @Test
        @DisplayName("report the failure tier, not the warn tier, when a lookup abends")
        void aFailingRateLookupReportsTheFailureTier() throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, CATEGORY_CODE, CATEGORY_BALANCE));
            CalculateInterestJobTest.this.stageReadableAccount();
            when(CalculateInterestJobTest.this.interest.rateFor(any())).thenThrow(
                    new IllegalStateException("no disclosure group is keyed by the substituted key"));

            JobExecution execution = CalculateInterestJobTest.this.run(BUSINESS_DATE);

            assertThat(execution.getStatus()).isNotEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isNotEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
            assertThat(BatchReturnCode.HARD_FAILURE.numericValue())
                    .isEqualTo(BatchApplication.EXIT_STATUS_HARD_FAILURE)
                    .isNotEqualTo(BatchApplication.EXIT_STATUS_SOFT_WARN)
                    .isGreaterThanOrEqualTo(BatchApplication.EXIT_STATUS_SOFT_WARN);
            assertThat(BatchReturnCode.HARD_FAILURE.permitsDownstreamRun()).isFalse();
        }
    }

    /**
     * The control break: how the walk groups its rows, and what each change of account produces.
     *
     * <p>Assumptions: every ruling in this group is about the LOOP STRUCTURE and not about the
     * accrual it delegates. The reference's break is at {@code app/cbl/CBACT04C.cbl:194-206}, guarded
     * by {@code WS-FIRST-TIME} at {@code :195}, and the flush it performs is
     * {@code 1050-UPDATE-ACCOUNT} at {@code :350-370} -- whose three state changes belong to the
     * sibling service tier and are asserted there against the real rule.</p>
     */
    @Nested
    @DisplayName("the control break")
    class TheControlBreak {

        /**
         * Each change of account flushes exactly once, and the first row flushes nothing.
         *
         * <p>Pins {@code app/cbl/CBACT04C.cbl:194-198}: the break fires on
         * {@code TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM}, and the {@code WS-FIRST-TIME} guard takes
         * the {@code ELSE} arm on the very first row so that no account is written before one has
         * been accumulated. Two accounts with two category rows each is the smallest input that
         * separates the three counts: two flushes rather than four proves the break is per account,
         * and two rather than three proves the first row took the guard.</p>
         *
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @Test
        @DisplayName("flush once per account change and never on the first row")
        void eachAccountChangeFlushesOnceAndTheFirstRowDoesNot() throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, "0001", CATEGORY_BALANCE),
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, "0002", CATEGORY_BALANCE),
                    balanceRow(SECOND_ACCOUNT, TYPE_CODE, "0001", CATEGORY_BALANCE),
                    balanceRow(SECOND_ACCOUNT, TYPE_CODE, "0002", CATEGORY_BALANCE));
            Account first = CalculateInterestJobTest.this.stageReadableAccount();
            Account second = CalculateInterestJobTest.this.stageAccount(SECOND_ACCOUNT);

            JobExecution execution = CalculateInterestJobTest.this.run(BUSINESS_DATE);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            verify(CalculateInterestJobTest.this.interest, times(2))
                    .flushAccount(any(Account.class), any(Money.class));

            InOrder breaks = inOrder(CalculateInterestJobTest.this.interest);
            breaks.verify(CalculateInterestJobTest.this.interest)
                    .flushAccount(eq(first), any(Money.class));
            breaks.verify(CalculateInterestJobTest.this.interest)
                    .flushAccount(eq(second), any(Money.class));

            // WHY : Assumptions: the account is read ONCE PER GROUP and not once per category row,
            //       because app/cbl/CBACT04C.cbl performs 1100-GET-ACCT-DATA at :203 from INSIDE the
            //       break. A per-row read would produce identical numbers and identical output, so
            //       nothing but a call count can observe the difference.
            verify(CalculateInterestJobTest.this.interest, times(1)).loadAccount(READABLE_ACCOUNT);
            verify(CalculateInterestJobTest.this.interest, times(1)).loadAccount(SECOND_ACCOUNT);
        }

        /**
         * One transaction is emitted per CATEGORY row, not per account.
         *
         * <p>Pins the position of the write rather than its content: the {@code PERFORM} at
         * {@code app/cbl/CBACT04C.cbl:468} sits inside {@code 1300-COMPUTE-INTEREST} beside the
         * accumulate at {@code :467}, so it runs once for every qualifying category balance. A
         * three-category account therefore yields three rows and one flush.</p>
         *
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @Test
        @DisplayName("emit one transaction per category row and one flush per account")
        void oneTransactionIsWrittenPerCategoryRow() throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, "0001", CATEGORY_BALANCE),
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, "0002", CATEGORY_BALANCE),
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, "0003", CATEGORY_BALANCE));
            CalculateInterestJobTest.this.stageReadableAccount();

            CalculateInterestJobTest.this.run(BUSINESS_DATE);

            verify(CalculateInterestJobTest.this.interest, times(3)).writeInterestTransaction(
                    anyLong(), anyString(), any(), any(), anyLong(), any());
            verify(CalculateInterestJobTest.this.interest, times(1))
                    .flushAccount(any(Account.class), any(Money.class));
        }

        /**
         * The generated row's card number is resolved BY ACCOUNT, once per account group.
         *
         * <p>Assumptions: the target satisfies this access path with a real secondary index rather
         * than with a keyed read of the cross-reference. {@code app/jcl/INTCALC.jcl:31-32} mounts
         * {@code XREFFIL1} over {@code CARDXREF.VSAM.AIX.PATH}, the by-account alternate index, and
         * the migrated equivalent is the index {@code idx_card_xref_account_id} -- so the lookup is
         * keyed on the account and the card number comes back from it, which is the order
         * {@code :205} and {@code :495} impose.</p>
         *
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @Test
        @DisplayName("resolve the card number by account, once per account group")
        void theCardNumberIsResolvedByAccount() throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, "0001", CATEGORY_BALANCE),
                    balanceRow(SECOND_ACCOUNT, TYPE_CODE, "0001", CATEGORY_BALANCE));
            CalculateInterestJobTest.this.stageReadableAccount();
            CalculateInterestJobTest.this.stageAccount(SECOND_ACCOUNT);

            CalculateInterestJobTest.this.run(BUSINESS_DATE);

            verify(CalculateInterestJobTest.this.interest, times(1))
                    .loadCrossReference(READABLE_ACCOUNT);
            verify(CalculateInterestJobTest.this.interest, times(1))
                    .loadCrossReference(SECOND_ACCOUNT);
            verify(CalculateInterestJobTest.this.interest).writeInterestTransaction(
                    eq(READABLE_ACCOUNT), eq(CARD_NUMBER), any(), any(), anyLong(), any());
            verify(CalculateInterestJobTest.this.interest).writeInterestTransaction(
                    eq(SECOND_ACCOUNT), eq(SECOND_CARD_NUMBER), any(), any(), anyLong(), any());
        }

        /**
         * The category-balance master is only ever read, never written.
         *
         * <p>Assumptions: the reference opens {@code TCATBALF} for input and never rewrites a row,
         * which is why {@code tests/golden/interest} commits no category-balance expectation at all
         * while {@code tests/golden/posting} does. A job that rewrote one would produce an artifact
         * the interest domain has no expectation for, so no comparison would catch it -- only a call
         * count can.</p>
         *
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @Test
        @DisplayName("read the category-balance master and write nothing back to it")
        void theCategoryBalanceMasterIsOnlyRead() throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, CATEGORY_CODE, CATEGORY_BALANCE));
            CalculateInterestJobTest.this.stageReadableAccount();

            CalculateInterestJobTest.this.run(BUSINESS_DATE);

            verify(CalculateInterestJobTest.this.categoryBalances)
                    .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc();
            verifyNoMoreInteractions(CalculateInterestJobTest.this.categoryBalances);
        }

        /**
         * A row whose account cannot be read accrues nothing, and the walk continues past it.
         *
         * <p>Registers as {@code D-INTEREST-ORPHAN-ROW}. The reference abends on that read, so it
         * never reaches the accounts after it. Both assertions matter: nothing is invented for the
         * account that does not exist, and the account that does exist still accrues -- which is the
         * whole reason the skip is preferred to the abend.</p>
         *
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @Test
        @DisplayName("skip a row whose account cannot be read and still accrue the next account")
        void anOrphanedRowIsSkippedAndTheWalkContinues() throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(ORPHANED_ACCOUNT, TYPE_CODE, CATEGORY_CODE, "500.00"),
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, CATEGORY_CODE, CATEGORY_BALANCE));
            when(CalculateInterestJobTest.this.interest.loadAccount(ORPHANED_ACCOUNT))
                    .thenReturn(Optional.empty());
            Account readable = CalculateInterestJobTest.this.stageReadableAccount();

            JobExecution execution = CalculateInterestJobTest.this.run(BUSINESS_DATE);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            verify(CalculateInterestJobTest.this.interest, times(1))
                    .flushAccount(any(Account.class), any(Money.class));
            verify(CalculateInterestJobTest.this.interest)
                    .flushAccount(eq(readable), any(Money.class));
            verify(CalculateInterestJobTest.this.interest, times(1)).writeInterestTransaction(
                    any(), any(), any(), any(), anyLong(), any());
        }

        /**
         * A zero rate accrues nothing and skips the fee seam with it.
         *
         * <p>Pins {@code app/cbl/CBACT04C.cbl:214}, which guards the whole computation with
         * {@code IF DIS-INT-RATE NOT = 0}, and the extent of that guard: it encloses {@code :215} AND
         * {@code :216}, so the fee paragraph is skipped alongside the accrual. This is NOT a
         * divergence, and it is asserted here so that the two divergences in this file are
         * distinguishable from the behaviour the reference shares -- accruing at zero would write a
         * transaction of amount zero for every category an account holds, and the reference writes
         * none.</p>
         *
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @Test
        @DisplayName("write no transaction and perform no fee step when the rate is zero")
        void aZeroRateAccruesNothing() throws Exception {
            DisclosureGroupKey key =
                    DisclosureGroupKey.ofBlankPaddedAccountGroupId(GROUP_ID, TYPE_CODE, 1);
            when(CalculateInterestJobTest.this.interest.rateFor(any()))
                    .thenReturn(new InterestRateLookup(key, key, BigDecimal.ZERO));
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, CATEGORY_CODE, CATEGORY_BALANCE));
            CalculateInterestJobTest.this.stageReadableAccount();

            JobExecution execution = CalculateInterestJobTest.this.run(BUSINESS_DATE);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            verify(CalculateInterestJobTest.this.interest, never()).writeInterestTransaction(
                    any(), any(), any(), any(), anyLong(), any());
            verify(CalculateInterestJobTest.this.interest, never()).computeFees();
        }

        /**
         * An empty master completes cleanly and writes nothing at all.
         *
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @Test
        @DisplayName("complete cleanly over an empty category-balance master")
        void anEmptyMasterWritesNothing() throws Exception {
            CalculateInterestJobTest.this.stageRows();

            JobExecution execution = CalculateInterestJobTest.this.run(BUSINESS_DATE);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            verify(CalculateInterestJobTest.this.interest, never())
                    .flushAccount(any(Account.class), any(Money.class));
            verify(CalculateInterestJobTest.this.interest, never()).writeInterestTransaction(
                    any(), any(), any(), any(), anyLong(), any());
        }
    }

    /** The dataset generation this run's output is staged under. */
    @Nested
    @DisplayName("the SYSTRAN generation")
    class TheSystranGeneration {

        /**
         * The run allocates a NEW generation of the {@code SYSTRAN} family and stages its payload
         * under it.
         *
         * <p>Pins {@code app/jcl/INTCALC.jcl:37-41}, whose output data definition carries
         * {@code DISP=(NEW,CATLG,DELETE)} and names {@code AWS.M2.CARDDEMO.SYSTRAN(+1)} -- the new
         * generation, not the current one. The family matters as much as the reference form: three
         * datasets in this chain carry transaction records and only this one is this step's output, so
         * a job that named the master or the backup family would make the downstream merge at
         * {@code app/jcl/COMBTRAN.jcl:24,26} read one input twice.</p>
         *
         * <p>Assumptions: the retained-generation count and the per-run memoisation that keeps one
         * run's numbering stable belong to the sibling {@code DatasetGenerationServiceTest} and are
         * not re-asserted here. What is asserted is the job's own request: the right family, a new
         * generation rather than a resolution of the current one, and the prefix convention the
         * coordinate renders.</p>
         *
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @Test
        @DisplayName("allocate a new SYSTRAN generation and stage the payload beneath it")
        void aNewSystranGenerationIsAllocatedAndStaged() throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, CATEGORY_CODE, CATEGORY_BALANCE));
            CalculateInterestJobTest.this.stageReadableAccount();

            CalculateInterestJobTest.this.run(BUSINESS_DATE);

            verify(CalculateInterestJobTest.this.generations).allocateNewGeneration(
                    eq(DatasetFamily.SYSTRAN), any(BusinessDate.class), eq(RUN_ID));
            verify(CalculateInterestJobTest.this.generations, never())
                    .resolveCurrentGeneration(any(DatasetFamily.class));

            ArgumentCaptor<DatasetGeneration> staged =
                    ArgumentCaptor.forClass(DatasetGeneration.class);
            verify(CalculateInterestJobTest.this.generations).stageDataset(staged.capture(),
                    eq(CalculateInterestJob.DATASET_OBJECT_NAME), any(Path.class));

            DatasetGeneration target = staged.getValue();
            assertThat(target.family()).isEqualTo(DatasetFamily.SYSTRAN);
            assertThat(target.datePartitionSegment()).isEqualTo("dt=" + BUSINESS_DATE);
            assertThat(target.generationSegment()).isEqualTo("gen=0001");
            assertThat(target.keyPrefix())
                    .endsWith("dt=" + BUSINESS_DATE + "/gen=0001/");
            assertThat(GenerationReference.NEW.jclNotation())
                    .as("the reference form the driver's data definition spells")
                    .isEqualTo("(+1)");
        }
    }

    /** What the run announces about itself, and the one clock read it stamps a generated row with. */
    @Nested
    @DisplayName("the emitted output")
    class TheEmittedOutput {

        /**
         * Both banners carry the reference program's text character for character, in order.
         *
         * <p>Pins {@code app/cbl/CBACT04C.cbl:181}, which displays
         * {@code START OF EXECUTION OF PROGRAM CBACT04C} before the walk, and {@code :230}, which
         * displays {@code END OF EXECUTION OF PROGRAM CBACT04C} after the last file is closed. Those
         * two lines are the whole of the reference program's framing output, and an operator reading a
         * migrated run's log identifies the step by them.</p>
         *
         * <p>Assumptions: the banner text is asserted where the migrated job actually emits it, which
         * is a structured log event and not process stdout. The reference wrote to stdout because a
         * job's SYSOUT was the only channel it had; the migrated task ships its log to the group the
         * observability module provisions, so the CHANNEL deliberately differs while the TEXT does
         * not. Asserting the text through the log is therefore the strongest available form of this
         * parity claim, and asserting a stdout write instead would assert a channel the target does
         * not use.</p>
         *
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @Test
        @DisplayName("both banners carry the reference program's verbatim text, in order")
        void bothBannersCarryTheVerbatimReferenceText() throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, CATEGORY_CODE, CATEGORY_BALANCE));
            CalculateInterestJobTest.this.stageReadableAccount();

            List<String> emitted = emittedWhile(
                    () -> CalculateInterestJobTest.this.run(BUSINESS_DATE));

            assertThat(emitted)
                    .as("the framing banners the reference displays at :181 and :230")
                    .anySatisfy(line -> assertThat(line).contains(START_BANNER_TEXT))
                    .anySatisfy(line -> assertThat(line).contains(END_BANNER_TEXT));

            int opened = indexOfLineContaining(emitted, START_BANNER_TEXT);
            int closed = indexOfLineContaining(emitted, END_BANNER_TEXT);

            // WHY : Assumptions: both positions are asserted to be found BEFORE they are ordered.
            //       The search reports a miss as -1, and -1 is less than every real position, so an
            //       ordering assertion on its own would pass VACUOUSLY for a run that never emitted
            //       the opening banner at all. Establishing presence first is what makes the ordering
            //       claim mean what it says.
            assertThat(opened)
                    .as("the opening banner the reference displays at :181 was emitted")
                    .isNotNegative();
            assertThat(closed)
                    .as("the closing banner the reference displays at :230 was emitted")
                    .isNotNegative();
            assertThat(opened)
                    .as("the opening banner precedes the closing one, as :181 precedes :230")
                    .isLessThan(closed);
        }

        /**
         * One observation is emitted per input row, in read order, and the closing event totals them.
         *
         * <p>Pins {@code app/cbl/CBACT04C.cbl:193}, whose {@code DISPLAY TRAN-CAT-BAL-RECORD} sits
         * inside the read loop immediately after {@code :192} increments the record count, so the
         * reference emits exactly one observation per input category-balance row, in the key order the
         * indexed read returns. Three rows are staged here: three per-row events are asserted, in
         * order, and the closing event is asserted to total three.</p>
         *
         * <p>Refactoring Rationale: this case previously accepted the closing total ALONE and argued
         * that the count was the parity-bearing part of {@code :193}. The count is not the whole of it:
         * a total says how many rows were read and not which, and it cannot show that the walk visited
         * them in key order or that it visited each exactly once -- which is what a reader following the
         * reference's own trace uses the per-row lines for. The job now emits one event per row and this
         * case asserts the cardinality and the ORDER, keeping the closing total as the separate claim it
         * always was.</p>
         *
         * <p>Assumptions: the record's fifty BYTES are asserted ABSENT rather than present, and the
         * absence is a governing rule rather than a gap this case tolerates.
         * {@code TRAN-CAT-BAL-RECORD} carries {@code TRANCAT-ACCT-ID} and {@code TRAN-CAT-BAL}, and
         * {@code docs/architecture/observability.md} requires a prohibited value to be OMITTED rather
         * than abbreviated, naming the account identifier and every monetary amount among them. So the
         * two protected values are asserted to appear in no emitted line, and the row's disclosable
         * identity -- ordinal, type code, category code -- is asserted to appear in each. The divergence
         * is registered as {@code D-INTEREST-ROW-DISPLAY-WITHHELD}.</p>
         *
         * <p>Assumptions: no processed-and-rejected counter pair is asserted, and its ABSENCE is
         * asserted instead. {@code CBACT04C} declares no reject stream and displays no counter pair
         * anywhere in its 652 lines -- the two verbatim counter lines belong to the posting reference
         * and are pinned by {@code PostTransactionsJobTest}. This is also why
         * {@code com.carddemo.batch.dto.BatchRunSummary} ships no stdout rendering of its own.</p>
         *
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @Test
        @DisplayName("emit one observation per input row in read order, and total them at the close")
        void theFinishingEventCountsEveryRowItWalked() throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, CATEGORY_CODE, CATEGORY_BALANCE),
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, "0002", CATEGORY_BALANCE),
                    balanceRow(SECOND_ACCOUNT, TYPE_CODE, CATEGORY_CODE, CATEGORY_BALANCE));
            CalculateInterestJobTest.this.stageReadableAccount();
            CalculateInterestJobTest.this.stageAccount(SECOND_ACCOUNT);

            List<String> emitted = emittedWhile(
                    () -> CalculateInterestJobTest.this.run(BUSINESS_DATE));

            List<String> perRow = emitted.stream()
                    .filter(line -> line.contains(ROW_READ_EVENT))
                    .toList();

            // WHY : Assumptions: the per-row lines are filtered and then compared as an ORDERED list,
            //       because both properties of :193 are at stake and each fails differently. A wrong
            //       count means a row was skipped or visited twice; a wrong order means the walk did not
            //       read in key order, which is what makes the control break correct. Asserting the
            //       count alone would pass a walk that emitted the same three lines in any sequence.
            assertThat(perRow)
                    .as("one observation per input row the reference displays at :193")
                    .hasSize(3);
            assertThat(perRow.get(0)).contains("rowOrdinal=1", "typeCd=" + TYPE_CODE,
                    "categoryCd=" + CATEGORY_CODE);
            assertThat(perRow.get(1)).contains("rowOrdinal=2", "categoryCd=0002");
            assertThat(perRow.get(2)).contains("rowOrdinal=3", "categoryCd=" + CATEGORY_CODE);

            // WHY : Assumptions: the two protected values are asserted absent from EVERY emitted line
            //       and not only from the per-row ones, because the disclosure rule is about the line a
            //       group retains rather than about which statement wrote it. The account identifier is
            //       searched for at its full declared width, which is the form the record carries it in.
            String accountDigits = String.format("%011d", READABLE_ACCOUNT);
            assertThat(emitted)
                    .as("the account identifier is omitted rather than abbreviated")
                    .noneSatisfy(line -> assertThat(line).contains(accountDigits));
            assertThat(emitted)
                    .as("the balance the record carries is omitted from every line")
                    .noneSatisfy(line -> assertThat(line).contains(CATEGORY_BALANCE));

            assertThat(emitted)
                    .as("the closing event totals the rows the walk read")
                    .anySatisfy(line -> assertThat(line).contains(END_BANNER_TEXT)
                            .contains("rows=3"));

            assertThat(emitted)
                    .as("the reference declares no reject stream, so no counter pair is announced")
                    .noneSatisfy(line -> assertThat(line).containsIgnoringCase("REJECTED"));
        }

        /**
         * The generated row carries ONE clock read in both of its stamps.
         *
         * <p>Pins {@code app/cbl/CBACT04C.cbl:496-498}, which performs a single
         * {@code FUNCTION CURRENT-DATE} read and moves that one value into the origination stamp at
         * {@code :497} and the processing stamp at {@code :498}. Posting differs: it copies the
         * origination stamp through from the daily feed and generates only the processing stamp, so
         * there the two are independent.</p>
         *
         * <p>Trade-offs: this asserts the PROPERTY the interest layout selection encodes rather than
         * the selection itself, because the selection is not byte-observable.
         * {@code com.carddemo.batch.mapper.TransactionRecordMapper} documents at its own null-selector
         * guard that its two registry entries "differ in which timestamp a parity comparison
         * normalises" -- they encode identical bytes, so no assertion over the staged image can
         * distinguish them. What CAN be observed is the condition that makes the interest entry the
         * correct one: both stamps holding the same value. A run that ever generated a differing pair
         * would make the interest normalisation policy wrong, and this case is what would catch
         * it.</p>
         *
         * @throws Exception if a committed expectation cannot be read or the execution path raises
         */
        @Test
        @DisplayName("one clock read reaches both stamps of the generated row")
        void bothGeneratedStampsCarryTheOneClockRead() throws Exception {
            Account only = account(READABLE_ACCOUNT, "194.00", "0.00", "0.00", GROUP_ID);
            GoldenRun run = CalculateInterestJobTest.this.runRealAccrual(BUSINESS_DATE,
                    accountsById(only), Map.of(READABLE_ACCOUNT, CARD_NUMBER),
                    Map.of(rateKey(GROUP_ID, TYPE_CODE, CATEGORY_CODE), new BigDecimal("15.00")),
                    List.of(balanceRow(READABLE_ACCOUNT, TYPE_CODE, CATEGORY_CODE,
                            CATEGORY_BALANCE)));

            List<byte[]> staged = splitRecords(run.stagedPayload(),
                    CopybookLayout.layout(INTEREST_LAYOUT).reclen());
            assertThat(staged)
                    .as("one generated row, without which the stamps below would not exist")
                    .hasSize(1);

            String image = new String(staged.getFirst(), StandardCharsets.ISO_8859_1);
            String origination = textField(image, INTEREST_LAYOUT, "TRAN-ORIG-TS");
            String processing = textField(image, INTEREST_LAYOUT, "TRAN-PROC-TS");

            assertThat(origination)
                    .as("the origination stamp the single read at :496 was moved into at :497")
                    .isEqualTo(processing)
                    .isNotBlank();
        }

        /**
         * Reports the messages the job's own logger formats while a body runs.
         *
         * <p>Assumptions: the appender is attached to the job class's logger and detached in a finally
         * block, following the capture idiom already used by
         * {@code com.carddemo.batch.service.BatchServicesTest}. Attaching to the root logger instead
         * would collect the framework's own step and execution chatter, and a case asserting that no
         * line mentions a counter would then be asserting something about Spring Batch.</p>
         *
         * @param body the action to run while capturing; must not be {@code null}
         * @return every message the job's logger formatted, in emission order
         * @throws Exception if the body raises, which is propagated rather than swallowed so a broken
         *     run fails the case that called this
         */
        private List<String> emittedWhile(ThrowingBody body) throws Exception {
            ch.qos.logback.classic.Logger jobLogger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(CalculateInterestJob.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
                    new ch.qos.logback.core.read.ListAppender<>();
            ch.qos.logback.classic.Level restore = jobLogger.getLevel();
            captured.start();
            jobLogger.addAppender(captured);
            jobLogger.setLevel(ch.qos.logback.classic.Level.INFO);
            try {
                body.run();
                return captured.list.stream()
                        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .toList();
            } finally {
                jobLogger.detachAppender(captured);
                captured.stop();
                jobLogger.setLevel(restore);
            }
        }

        /**
         * Reports the position of the first captured message containing a fragment.
         *
         * <p>Alternatives Considered: raising when nothing matches, so that a caller could not misread
         * a miss as a position. Rejected because the ordering claim and the presence claim then arrive
         * as one failure and the report would not say which of the two banners was missing. A miss is
         * returned as {@code -1} and every caller asserts presence before ordering, which reports the
         * two conditions separately.</p>
         *
         * @param emitted the captured messages to search; must not be {@code null}
         * @param fragment the text to look for; must not be {@code null}
         * @return the zero-based position of the first match, or {@code -1} when nothing matches
         */
        private int indexOfLineContaining(List<String> emitted, String fragment) {
            for (int position = 0; position < emitted.size(); position++) {
                if (emitted.get(position).contains(fragment)) {
                    return position;
                }
            }
            return -1;
        }
    }

    /** A body that a capturing helper runs and whose checked failures it propagates. */
    private interface ThrowingBody {

        /**
         * Runs the body.
         *
         * @throws Exception if the body fails, which the caller propagates
         */
        void run() throws Exception;
    }

    /** The durable step record, and the fee paragraph the reference declares and never implements. */
    @Nested
    @DisplayName("the step ledger and the fee seam")
    class TheStepLedgerAndTheFeeSeam {

        /**
         * The run is recorded once, keyed by the orchestrator run identifier and the step name.
         *
         * <p>Refactoring Rationale: the durable step record is a strict IMPROVEMENT over the baseline
         * rather than a port of one. The only {@code RESTART=} anywhere in the thirty-eight members of
         * {@code app/jcl} is commented out, at {@code app/jcl/DEFGDGD.jcl:2}, and there is no
         * {@code CHKPT=} in any of them -- so a failed step had to be resubmitted by hand, from the
         * top, with no record of what had already completed. Keying the record on the run identifier
         * and the step name is what makes a repeat of the same pair a no-op instead of a second
         * pass.</p>
         *
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @Test
        @DisplayName("record the run once under the run identifier and the step name")
        void theRunIsRecordedOnceUnderTheRunAndStepPair() throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, CATEGORY_CODE, CATEGORY_BALANCE));
            CalculateInterestJobTest.this.stageReadableAccount();

            CalculateInterestJobTest.this.run(BUSINESS_DATE);

            verify(CalculateInterestJobTest.this.ledgerOfSteps, times(1)).runStep(eq(RUN_ID),
                    eq(CalculateInterestJob.STEP_NAME), eq(BatchJobName.CALCULATE_INTEREST), any());
        }

        /**
         * A step the ledger reports as already complete performs no work at all.
         *
         * <p>Assumptions: this is the observable half of the idempotence claim. When the ledger
         * answers that the pair was already recorded, the body it was handed is never evaluated, so
         * the walk reads nothing, accrues nothing, allocates no generation and rewrites no account --
         * a repeat is a no-op rather than a duplicate. The ledger's own decision procedure belongs to
         * the sibling service tier; what a job-level case can settle is that the job routes its whole
         * pass through the ledger and holds no work outside it.</p>
         *
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @Test
        @DisplayName("perform no work when the ledger reports the step already complete")
        void aStepAlreadyRecordedRunsNothing() throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, CATEGORY_CODE, CATEGORY_BALANCE));
            CalculateInterestJobTest.this.stageReadableAccount();
            when(CalculateInterestJobTest.this.ledgerOfSteps.runStep(anyString(), anyString(),
                    any(BatchJobName.class), any()))
                    .thenReturn(new BatchStepLedger.StepOutcome(BatchReturnCode.CLEAN, true));

            JobExecution execution = CalculateInterestJobTest.this.run(BUSINESS_DATE);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            verify(CalculateInterestJobTest.this.categoryBalances, never())
                    .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc();
            verify(CalculateInterestJobTest.this.generations, never()).allocateNewGeneration(
                    any(DatasetFamily.class), any(BusinessDate.class), anyString());
            verify(CalculateInterestJobTest.this.interest, never())
                    .flushAccount(any(Account.class), any(Money.class));
        }

        /**
         * The fee seam is performed once per accrual and produces nothing.
         *
         * <p>Assumptions: reproducing the stub faithfully -- rather than dropping the call or
         * inventing a fee calculation -- is what preserves parity.
         * {@code app/cbl/CBACT04C.cbl:518-520} declares {@code 1400-COMPUTE-FEES} as the single
         * comment {@code To be implemented} followed by an immediate {@code EXIT}, and {@code :216}
         * performs it once per qualifying category row. So the count is observable and the output is
         * not: exactly as many fee steps as accruals, and no additional row on the staged generation
         * beyond the one the accrual wrote.</p>
         *
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @Test
        @DisplayName("perform the fee step once per accrual and write no fee row")
        void theFeeSeamIsPerformedAndWritesNothing() throws Exception {
            CalculateInterestJobTest.this.stageRows(
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, "0001", CATEGORY_BALANCE),
                    balanceRow(READABLE_ACCOUNT, TYPE_CODE, "0002", CATEGORY_BALANCE));
            CalculateInterestJobTest.this.stageReadableAccount();

            byte[][] payload = new byte[1][];
            captureStagedPayload(CalculateInterestJobTest.this.generations, payload);

            CalculateInterestJobTest.this.run(BUSINESS_DATE);

            verify(CalculateInterestJobTest.this.interest, times(2)).computeFees();
            assertThat(payload[0])
                    .as("two accruals produce two records and no third fee record")
                    .hasSize(2 * CopybookLayout.layout(INTEREST_LAYOUT).reclen());
        }
    }

    /**
     * The corrected final-account flush, registered as divergence {@code D-3}.
     *
     * <p>Refactoring Rationale: the reference writes an account's accumulated interest when the NEXT
     * account arrives, and the last account never has a next one, so the last account is never
     * written back. The omission is structural rather than incidental and the code LOOKS as though it
     * handles the case: {@code app/cbl/CBACT04C.cbl:219-221} carries an
     * {@code ELSE PERFORM 1050-UPDATE-ACCOUNT} that exists for exactly this purpose and is
     * UNREACHABLE, because {@code 1000-TCATBALF-GET-NEXT} at {@code :325-348} sets the end-of-file
     * flag the moment the read reports file status {@code '10'} and the enclosing
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} at {@code :188} re-tests that flag before the body can
     * be entered again. That same paragraph is the corroboration: it does not perform the flush on
     * end of file either, so {@code :219-221} is the only final-flush path in the program and it never
     * runs. What the last account loses compounds rather than staying local, because
     * {@code 1050-UPDATE-ACCOUNT} at {@code :350-370} makes three state changes and the unreachable
     * arm skips all three -- {@code :352} adds the accrual to the balance while {@code :353} and
     * {@code :354} zero the two cycle accumulators, so an unflushed account also carries a whole prior
     * cycle's activity into the next cycle's credit-limit projection. The COBOL is NOT fixed:
     * everything under {@code app/} is reference-only. The Java implements the correct behaviour and
     * the difference is registered as {@code D-3} in
     * {@code docs/architecture/cobol-to-service-traceability.md} rather than absorbed silently or
     * reproduced to make an expectation pass.</p>
     */
    @Nested
    @DisplayName("the corrected final-account flush")
    class TheCorrectedFinalAccountFlush {

        /**
         * The final account receives the same three state changes every other account receives.
         *
         * <p>Assumptions: this case supplies the REAL accrual rule over mocked repositories, because
         * the property under assertion is the account's STATE after the walk and a mocked rule would
         * only record that a call was made. The opening cycle accumulators are deliberately non-zero
         * -- the committed fixtures carry zeros, so a reset asserted against them would hold whether
         * or not it happened.</p>
         *
         * <p>Assumptions: a single-account walk is the smallest input that shows the difference,
         * because in it EVERY account is the final one, so a job reproducing the reference's omission
         * would leave the balance at its opening value and both accumulators untouched.</p>
         *
         * @throws Exception if the framework's own execution path raises, which no case here provokes
         */
        @Test
        @DisplayName("apply the accrual and reset both cycle totals on the final account")
        void theFinalAccountIsFlushedWithItsAccrualAndItsCycleReset() throws Exception {
            Account only = account(READABLE_ACCOUNT, "194.00", "310.00", "-45.00", GROUP_ID);
            GoldenRun walk = CalculateInterestJobTest.this.runRealAccrual(BUSINESS_DATE,
                    accountsById(only),
                    Map.of(READABLE_ACCOUNT, CARD_NUMBER),
                    Map.of(rateKey(GROUP_ID, TYPE_CODE, CATEGORY_CODE), new BigDecimal("15.00")),
                    List.of(balanceRow(READABLE_ACCOUNT, TYPE_CODE, CATEGORY_CODE,
                            CATEGORY_BALANCE)));

            assertThat(walk.execution().getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(only.getCurrBal())
                    .as("the accrual of 12.50 on 1000.00 at 15.00 per cent reaches the balance")
                    .isEqualByComparingTo("206.50");
            assertThat(only.getCurrCycCredit()).isEqualByComparingTo("0.00");
            assertThat(only.getCurrCycDebit()).isEqualByComparingTo("0.00");
            assertThat(walk.stagedPayload())
                    .hasSize(CopybookLayout.layout(INTEREST_LAYOUT).reclen());
        }
    }

    /**
     * Parity against the three committed interest expectation trees.
     *
     * <p>Assumptions: interest-domain normalisation blanks BOTH twenty-six-character timestamp
     * fields, and the asymmetry with the posting domain is deliberate rather than an inconsistency to
     * be harmonised. {@code app/cbl/CBACT04C.cbl:496} performs one timestamp read and {@code :497-498}
     * move that one value into both stamps, so both are run-generated and both must be masked; the
     * posting program copies its originating stamp from its input record and so must ASSERT that
     * field. Section 8.1 of {@code services/batch-service/src/test/resources/fixtures/README.md}
     * records the measurement, and the shared registry expresses it as two layouts -- {@code TRAN}
     * normalises only the processing stamp, and {@code INTTRAN} is the derivation that additionally
     * flags the originating one. The spans blanked below are read from the layout's own flags rather
     * than from offsets spelled here, so selecting the wrong layout would be a failure and not a
     * silent mismatch.</p>
     *
     * <p>Refactoring Rationale: the comparison used to map every low-value byte to a blank on BOTH
     * sides before comparing, and it no longer does -- the two pad regions are now reproduced instead of
     * normalised away. Section 6.3 of that same contract measures that this program builds its
     * description with {@code STRING} at {@code app/cbl/CBACT04C.cbl:485-489}, which writes only the
     * characters it was given and leaves the remainder of the receiving field as the low values the
     * record area held, whereas the posting program uses {@code MOVE} and space-pads; and section 6.1
     * measures the trailing pad of every committed transaction expectation as low values for the same
     * reason, no statement in either program touching it. The record mapper now writes both of those
     * bytes, taking the description's from the layout the caller names, so the staged generation is
     * compared BYTE FOR BYTE with only the two run-generated timestamp spans masked. The canonicalisation
     * was a normalisation a reader had to trust; ninety-six bytes of every generated record are now
     * asserted rather than mapped, and a downstream consumer reading the generation positionally sees
     * the bytes this case compares.</p>
     *
     * <p>Assumptions: the guard that every low value in the expectation lies inside one of the two pad
     * regions is KEPT even though nothing is canonicalised any more, because it pins a different
     * property: that the two regions really are the only places the reference leaves a low value, which
     * is what makes the mapper's two pad declarations complete rather than merely sufficient for these
     * three scenarios.</p>
     */
    @Nested
    @DisplayName("the committed interest expectations")
    class TheCommittedInterestExpectations {

        /**
         * Each committed scenario is reproduced: the tier, the generated records and the account
         * images.
         *
         * <p>Trade-offs: the account comparison is narrowed for exactly one account of exactly the
         * scenarios where {@code D-3} manifests, and the narrowing is carried as a per-scenario
         * PARAMETER rather than applied globally. The three expectation images were produced by the
         * reference, which does not flush its final account, while this job does -- so the final
         * account's expected balance is short by whatever that account accrued. Measured from the
         * committed files: {@code tests/golden/interest/happy_path/acctdat.expected} and
         * {@code tests/golden/interest/default_fallback/acctdat.expected} each differ from their own
         * driving fixture in exactly ONE of two records, the FIRST, whose balance moves from
         * {@code 194.00} to {@code 206.50}, leaving the final account at its opening {@code 158.00};
         * and {@code tests/golden/interest/zero_balance/acctdat.expected} is byte-identical to its
         * fixture, because a zero balance accrues nothing so the omitted flush changes no byte. The
         * divergence parameter is therefore {@code 12.50} for the first two scenarios and
         * {@code 0.00} for the third, which means the third is compared at full parity by
         * construction. Weakening the comparison for every account, or for every scenario, would stop
         * testing the one account the reference does rewrite -- which section 7.3 of
         * {@code services/batch-service/src/test/resources/fixtures/README.md} names as the only
         * observable rewrite in the whole domain.</p>
         *
         * @param scenario the name of a committed scenario directory shared by
         *     {@code tests/fixtures/interest} and {@code tests/golden/interest}
         * @param finalAccountDivergence the exact decimal amount by which this job's final-account
         *     balance is expected to exceed the committed image, being what {@code D-3} omits; zero
         *     where the omission changes no byte
         * @throws Exception if the framework's own execution path raises, or a fixture or expectation
         *     file cannot be read from the repository tree
         */
        @ParameterizedTest(name = "the {0} expectation is reproduced")
        @CsvSource({
            "happy_path, 12.50",
            "default_fallback, 12.50",
            "zero_balance, 0.00",
        })
        @DisplayName("reproduce the committed tier, generated records and account images")
        void theCommittedScenarioIsReproduced(String scenario, String finalAccountDivergence)
                throws Exception {

            Map<Long, Account> accounts =
                    accountsFrom(fixtureRecords(scenario, "acctdata.txt", ACCOUNT_LAYOUT));
            GoldenRun walk = CalculateInterestJobTest.this.runRealAccrual(BUSINESS_DATE, accounts,
                    cardsFrom(fixtureRecords(scenario, "cardxref.txt", CROSS_REFERENCE_LAYOUT)),
                    ratesFrom(fixtureRecords(scenario, "discgrp.txt", DISCLOSURE_GROUP_LAYOUT)),
                    balancesFrom(fixtureRecords(
                            scenario, "tcatbal.txt", CATEGORY_BALANCE_LAYOUT)));

            assertThat(walk.execution().getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(expectationText(scenario, "return_code.expected").trim())
                    .isEqualTo(Integer.toString(BatchReturnCode.CLEAN.numericValue()));

            assertGeneratedRecordsMatch(scenario, walk.stagedPayload());
            assertAccountImagesMatch(scenario, accounts, new BigDecimal(finalAccountDivergence));
        }

        /**
         * Every input this module drives the accrual with is byte-identical to its oracle derivation.
         *
         * <p>This case exists because the case above stopped reading the oracle's fixture tree. The
         * inputs are now this module's own copies on the test classpath, for the reasons recorded on
         * {@link #FIXTURE_INTEREST_ROOT} -- and two byte-identical trees where only one is read is
         * precisely the arrangement in which the unread one drifts. Section 2 of
         * {@code services/batch-service/src/test/resources/fixtures/README.md} declares the module tree
         * a derivation of the oracle's rather than an independent authoring, so the identity is a
         * stated contract and this is where it is checked.</p>
         *
         * <p>Assumptions: the comparison is over RAW BYTES including the trailing newline, and nothing
         * is normalised on either side. A derivation that agreed after normalisation would not be a
         * derivation: the sign overpunches of section 3.3, the line-ending rule of section 3.8 and the
         * trailing-newline rule of section 3.9 are all byte-level contracts, so any transformation
         * applied before comparing would hide a drift in one of the three.</p>
         *
         * <p>Assumptions: the oracle file's existence is asserted rather than skipped over when
         * absent. {@code tests/fixtures} is a committed, reference-only part of this repository, so its
         * absence means an incomplete checkout, and a skip would report a green drift check that
         * compared nothing.</p>
         *
         * @throws IOException if an oracle fixture cannot be read from the repository tree
         */
        @Test
        @DisplayName("drive from inputs byte-identical to their reference-only derivation")
        void everyDrivingFixtureMatchesItsOracleDerivation() throws IOException {
            for (String scenario : SCENARIOS) {
                for (String file : DRIVING_FIXTURES) {
                    byte[] driven = moduleFixtureBytes(scenario, file);
                    byte[] derivedFrom = Files.readAllBytes(oracleFile("fixtures", scenario, file));

                    assertThat(driven)
                            .as("this module's %s/%s against tests/fixtures/%s/%s/%s, byte for byte",
                                    scenario, file, INTEREST_DOMAIN, scenario, file)
                            .isEqualTo(derivedFrom);
                }
            }
        }

        /**
         * Compares the staged generation against the committed transaction expectation, record by
         * record.
         *
         * @param scenario the committed scenario whose expectation is being compared against
         * @param stagedPayload the concatenated bytes the run staged, being one fixed-width record
         *     after another with no delimiter between them
         * @throws IOException if the expectation file cannot be read from the repository tree
         */
        private void assertGeneratedRecordsMatch(String scenario, byte[] stagedPayload)
                throws IOException {

            int reclen = CopybookLayout.layout(INTEREST_LAYOUT).reclen();
            List<byte[]> expected = expectationRecordBytes(scenario, "transact.expected", reclen);
            List<byte[]> staged = splitRecords(stagedPayload, reclen);

            // WHY : Assumptions: the record count is asserted before the contents, because a payload
            //       holding the wrong number of records would otherwise be reported as a byte
            //       difference in whichever record happened to align badly, and the diff would point
            //       at a field rather than at the count.
            assertThat(expected)
                    .as("the committed transaction expectation for scenario %s holds records, so"
                            + " the comparison below cannot pass over an empty pair", scenario)
                    .isNotEmpty();
            assertThat(staged)
                    .as("the run stages one record per accrual for scenario %s", scenario)
                    .hasSameSizeAs(expected);

            for (int index = 0; index < expected.size(); index++) {
                assertLowValuesOnlyInPadRegions(expected.get(index));
                assertThat(withRunGeneratedStampsMasked(staged.get(index)))
                        .as("record %d of the %s expectation", index, scenario)
                        .isEqualTo(withRunGeneratedStampsMasked(expected.get(index)));
            }
        }

        /**
         * Compares every account this run touched against the committed account expectation.
         *
         * @param scenario the committed scenario whose expectation is being compared against
         * @param accounts the account rows the run was driven over, keyed by identifier, whose state
         *     the walk mutated in place
         * @param finalAccountDivergence the amount by which the final account's balance is expected
         *     to exceed the committed image, being what divergence {@code D-3} omits
         * @throws IOException if the expectation file cannot be read from the repository tree
         */
        private void assertAccountImagesMatch(String scenario, Map<Long, Account> accounts,
                BigDecimal finalAccountDivergence) throws IOException {

            List<String> expected = expectationTextRecords(scenario, "acctdat.expected",
                    ACCOUNT_LAYOUT);
            assertThat(expected)
                    .as("the committed account expectation for scenario %s holds records, so the"
                            + " comparison below cannot pass over an empty pair", scenario)
                    .isNotEmpty()
                    .hasSameSizeAs(accounts.values());

            for (int index = 0; index < expected.size(); index++) {
                String image = expected.get(index);
                long accountId = unsignedField(image, ACCOUNT_LAYOUT, "ACCT-ID");
                Account actual = accounts.get(accountId);
                assertThat(actual)
                        .as("account %d of the %s expectation was driven by this run", accountId,
                                scenario)
                        .isNotNull();

                boolean isFinalAccount = index == expected.size() - 1;
                BigDecimal expectedBalance =
                        signedField(image, ACCOUNT_LAYOUT, "ACCT-CURR-BAL");
                BigDecimal target = isFinalAccount
                        ? expectedBalance.add(finalAccountDivergence)
                        : expectedBalance;

                assertThat(actual.getCurrBal())
                        .as("the balance of account %d in scenario %s", accountId, scenario)
                        .isEqualByComparingTo(target);
                assertThat(actual.getCurrCycCredit())
                        .isEqualByComparingTo(
                                signedField(image, ACCOUNT_LAYOUT, "ACCT-CURR-CYC-CREDIT"));
                assertThat(actual.getCurrCycDebit())
                        .isEqualByComparingTo(
                                signedField(image, ACCOUNT_LAYOUT, "ACCT-CURR-CYC-DEBIT"));
            }
        }
    }

    /**
     * One completed run of the job, together with the bytes it staged.
     *
     * <p>Assumptions: the staged bytes are captured DURING the staging call rather than read back
     * afterwards, because the job deletes its temporary file on every path including success, so a
     * read afterwards would find nothing. Capturing during the call observes the bytes that actually
     * left.</p>
     *
     * @param execution the finished execution, whose status and exit status carry the tier
     * @param stagedPayload the concatenated fixed-width records the run staged into its generation,
     *     with no delimiter between them
     */
    private record GoldenRun(JobExecution execution, byte[] stagedPayload) {
    }

    /**
     * Runs the job once over the REAL accrual rule, driven by explicit reference data.
     *
     * <p>Assumptions: the four repositories are the only doubles, so every rule between the walk and
     * the data is the production one -- the rate resolution with its {@code DEFAULT} retry, the
     * accrual arithmetic, the generated record's field values and the three-part account flush. That
     * is what lets a case here assert account STATE and staged BYTES rather than call counts.</p>
     *
     * @param token the ten-character business-date token to launch with, supplied verbatim as the
     *     identifying job parameter
     * @param accountsById the account rows the account master answers with, keyed by identifier; the
     *     walk mutates these instances in place, so a caller asserts on them after the run
     * @param cardByAccount the card number the by-account cross-reference answers with, keyed by
     *     account identifier
     * @param rateByGroupKey the disclosure-group rate keyed by the sixteen-character composite of
     *     group identifier, transaction type code and four-character category code
     * @param balances the category balances the master streams, in the key order an ordered query
     *     would return them
     * @return the finished run and the bytes it staged, never {@code null}
     * @throws Exception if the framework's own execution path raises
     */
    private GoldenRun runRealAccrual(String token, Map<Long, Account> accountsById,
            Map<Long, String> cardByAccount, Map<String, BigDecimal> rateByGroupKey,
            List<TransactionCategoryBalance> balances) throws Exception {

        DisclosureGroupRepository groups = mock(DisclosureGroupRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        CardXrefRepository crossReferences = mock(CardXrefRepository.class);
        TransactionRepository ledger = mock(TransactionRepository.class);

        // WHY : Assumptions: both saving repositories echo their argument back, because the rule
        //       returns what the repository returned and an unstubbed mock would answer null -- so the
        //       walk would stage a null record and every case would fail on a null reference rather
        //       than on the ruling it was written for.
        when(accounts.findByAccountId(anyLong())).thenAnswer(
                call -> Optional.ofNullable(accountsById.get(call.<Long>getArgument(0))));
        when(accounts.save(any(Account.class))).thenAnswer(call -> call.getArgument(0));
        when(ledger.save(any(Transaction.class))).thenAnswer(call -> call.getArgument(0));

        when(crossReferences.findFirstByAccountIdOrderByCardNumAsc(anyLong())).thenAnswer(call -> {
            Long accountId = call.getArgument(0);
            String cardNumber = cardByAccount.get(accountId);
            return cardNumber == null ? Optional.empty()
                    : Optional.of(new CardXref(cardNumber, UNASSERTED_CUSTOMER_ID, accountId));
        });

        // WHY : Assumptions: the rate is looked up by the composite the production rule builds, read
        //       back off the key object rather than matched against a pre-composed one. The group
        //       component arrives blank-padded to its declared ten characters and the category
        //       component zero-padded to four, and the DEFAULT retry substitutes only the group
        //       component -- so composing the lookup key from the object's own three parts is what
        //       makes the direct hit and the retry both resolvable from one reference table.
        when(groups.findByIdIs(any())).thenAnswer(call -> {
            DisclosureGroup.DisclosureGroupId requested = call.getArgument(0);
            BigDecimal rate = rateByGroupKey.get(requested.getAcctGroupId()
                    + requested.getTranTypeCd() + requested.getTranCatCd());
            return rate == null ? Optional.empty()
                    : Optional.of(new DisclosureGroup(requested, rate));
        });

        TransactionCategoryBalanceRepository master =
                mock(TransactionCategoryBalanceRepository.class);
        when(master.findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc())
                .thenReturn(balances.stream());

        DatasetGenerationService allocator = mock(DatasetGenerationService.class);
        stubGenerationsToAnswer(allocator);
        byte[][] payload = new byte[1][];
        captureStagedPayload(allocator, payload);

        BatchStepLedger stepLedger = mock(BatchStepLedger.class);
        stubLedgerToEvaluateItsBody(stepLedger);

        CalculateInterestJob configuration = new CalculateInterestJob(master,
                new InterestCalculationService(groups, accounts, crossReferences, ledger),
                stepLedger, allocator, fixedClock());

        JobRepository repository = new ResourcelessJobRepository();
        Job realJob = configuration.calculateInterest(
                repository, new ResourcelessTransactionManager(), sharedValidator());

        return new GoldenRun(execute(realJob, repository, token), payload[0]);
    }

    /**
     * Runs the mock-backed job once with both required parameters and returns its execution.
     *
     * @param token the ten-character business-date token to launch with
     * @return the finished job execution, never {@code null}
     * @throws Exception if the framework's own execution path raises
     */
    private JobExecution run(String token) throws Exception {
        return execute(this.job, this.jobRepository, token);
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
     * Arranges the rows the mock-backed walk reads, in the order an ordered query would return them.
     *
     * @param rows the category balances to serve; may be empty
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
        return stageAccount(READABLE_ACCOUNT);
    }

    /**
     * Arranges a readable account record for one account identifier.
     *
     * @param accountId the identifier the account master will answer for
     * @return the account the master will return, so a case can assert it was the one written
     */
    private Account stageAccount(long accountId) {
        Account account = account(accountId, "194.00", "0.00", "0.00", GROUP_ID);
        when(this.interest.loadAccount(accountId)).thenReturn(Optional.of(account));
        return account;
    }

    /**
     * Collects every business date the mock-backed run handed to a collaborator.
     *
     * <p>Assumptions: BOTH consumers are captured, because they consume the token differently and a
     * case asserting only one would miss a normalisation applied in the other. The generated
     * identifier concatenates the token verbatim, while the dataset coordinate derives a
     * calendar-shaped prefix from it, so the token itself has to arrive unaltered at each.</p>
     *
     * @return every business date the run passed to the generation allocator and to the accrual
     *     write, in that order; never {@code null}
     */
    private List<BusinessDate> capturedBusinessDates() {
        ArgumentCaptor<BusinessDate> allocated = ArgumentCaptor.forClass(BusinessDate.class);
        verify(this.generations).allocateNewGeneration(
                any(DatasetFamily.class), allocated.capture(), anyString());

        ArgumentCaptor<BusinessDate> written = ArgumentCaptor.forClass(BusinessDate.class);
        verify(this.interest, atLeastOnce()).writeInterestTransaction(anyLong(), anyString(),
                any(Money.class), written.capture(), anyLong(), any(LocalDateTime.class));

        List<BusinessDate> carried = new ArrayList<>(allocated.getAllValues());
        carried.addAll(written.getAllValues());
        return carried;
    }

    /**
     * Registers one execution and runs a job through the framework's own step lifecycle.
     *
     * <p>Assumptions: the instance and the execution are constructed directly and then registered
     * rather than obtained from a convenience overload, because the repository's own creation method
     * takes an instance -- so a case that asked it for one by name would depend on an overload the
     * interface this job is built against does not declare.</p>
     *
     * @param target the job to run; must not be {@code null}
     * @param repository the in-memory repository the execution is registered with; must not be
     *     {@code null}
     * @param token the ten-character business-date token supplied as the identifying parameter
     * @return the finished execution, never {@code null}
     * @throws Exception if the framework's own execution path raises
     */
    private static JobExecution execute(Job target, JobRepository repository, String token)
            throws Exception {

        JobParameters parameters = new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER, token, true)
                .addString(BatchConfig.RUN_ID_PARAMETER, RUN_ID, false)
                .toJobParameters();

        JobInstance instance = new JobInstance(INSTANCE_ID, CalculateInterestJob.JOB_NAME);
        JobExecution execution = new JobExecution(EXECUTION_ID, instance, parameters);
        repository.update(execution);
        target.execute(execution);
        return execution;
    }

    /**
     * Builds the validator every job in this module is constructed with.
     *
     * <p>Assumptions: the shared configuration class supplies it rather than a validator assembled
     * here, so a case observes the parameter rule production applies. A locally built validator would
     * be a second rule that could accept a launch the deployed job refuses.</p>
     *
     * @return the shared parameter validator, never {@code null}
     */
    private static JobParametersValidator sharedValidator() {
        return new BatchConfig().carddemoJobParametersValidator();
    }

    /**
     * Builds the fixed clock every run in this file reads its record stamps from.
     *
     * <p>Assumptions: the clock is fixed rather than the system one, because the generated record
     * carries the clock's instant in both timestamp fields and a moving value would make the staged
     * bytes differ between two runs of the same case.</p>
     *
     * @return a clock frozen at {@link #CLOCK_INSTANT} in UTC, never {@code null}
     */
    private static Clock fixedClock() {
        return Clock.fixed(CLOCK_INSTANT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    /**
     * Stubs a step ledger to EVALUATE the body it is handed and report what the body returned.
     *
     * <p>Assumptions: a default-returning mock would run none of the accrual, so every count and
     * every byte asserted would be that of a job which did nothing. This stub is the smallest
     * faithful stand-in for a ledger that has no prior record of the step.</p>
     *
     * @param ledger the mocked step ledger to stub; must not be {@code null}
     */
    private static void stubLedgerToEvaluateItsBody(BatchStepLedger ledger) {
        when(ledger.runStep(anyString(), anyString(), any(BatchJobName.class), any()))
                .thenAnswer(call -> new BatchStepLedger.StepOutcome(
                        call.<Supplier<BatchReturnCode>>getArgument(3).get(), false));
    }

    /**
     * Stubs a generation resolver to answer with a real coordinate rather than a mock's default.
     *
     * <p>Assumptions: the walk allocates its coordinate before reading a single row and reports the
     * allocated number when it finishes, so a default-returning mock would hand the job a null
     * generation and every case would fail on the report rather than on the behaviour it pins.
     * Retention is stubbed empty because no case in this file is about aged-out generations -- that
     * rule belongs to the sibling service tier.</p>
     *
     * @param allocator the mocked generation resolver to stub; must not be {@code null}
     */
    private static void stubGenerationsToAnswer(DatasetGenerationService allocator) {
        when(allocator.allocateNewGeneration(any(DatasetFamily.class), any(BusinessDate.class),
                anyString())).thenAnswer(call -> new DatasetGeneration(
                        call.getArgument(0), call.getArgument(1), FIRST_GENERATION));
        when(allocator.generationsToScratch(any(DatasetFamily.class))).thenReturn(List.of());
        when(allocator.datasetUri(any(DatasetGeneration.class)))
                .thenReturn("s3://carddemo-datasets-test/ledger/systran/");
        when(allocator.stageDataset(any(DatasetGeneration.class), anyString(), any(Path.class)))
                .thenReturn(STAGED_OBJECT_KEY);
    }

    /**
     * Stubs a generation resolver to record the exact bytes the run stages.
     *
     * @param allocator the mocked generation resolver to stub; must not be {@code null}
     * @param sink a single-element holder the staged bytes are written into
     */
    private static void captureStagedPayload(DatasetGenerationService allocator, byte[][] sink) {
        when(allocator.stageDataset(any(DatasetGeneration.class), anyString(), any(Path.class)))
                .thenAnswer(call -> {
                    sink[0] = Files.readAllBytes(call.<Path>getArgument(2));
                    return STAGED_OBJECT_KEY;
                });
    }

    /**
     * Builds the generated interest row the mocked accrual write is stubbed to return.
     *
     * <p>Assumptions: the field values are the literals {@code app/cbl/CBACT04C.cbl:482-498} moves --
     * type {@code 01}, the stored four-character category {@code 0005}, the source {@code System} and
     * the eleven-digit description -- so the image the walk stages is the reference's own row shape
     * rather than an arbitrary one the encoder merely accepts. The identifier is assembled from the
     * arguments the walk supplies, so the stub cannot disagree with the token it was handed.</p>
     *
     * @param accountId the account the accrual belongs to, rendered into the description at its
     *     declared eleven digits
     * @param cardNumber the card the by-account cross-reference resolved for that account
     * @param businessDate the injected business date, whose RAW token opens the identifier
     * @param suffix the run-scoped identifier suffix, rendered at its declared six digits
     * @param stamp the instant the walk read from its clock, moved into both timestamp fields
     * @return the generated row, never {@code null}
     */
    private static Transaction generatedRow(long accountId, String cardNumber,
            BusinessDate businessDate, long suffix, LocalDateTime stamp) {

        Transaction row = new Transaction(businessDate.token() + String.format("%06d", suffix));
        row.setTypeCd(InterestCalculationService.INTEREST_TYPE_CODE);
        row.setCategoryCd(InterestCalculationService.INTEREST_CATEGORY_CODE_FIELD);
        row.setSource(InterestCalculationService.INTEREST_SOURCE);
        row.setDescription(InterestCalculationService.INTEREST_DESCRIPTION_PREFIX
                + String.format("%011d", accountId));
        row.setAmount(new BigDecimal(ACCRUED_INTEREST));
        row.setMerchantId(0L);
        row.setMerchantName("");
        row.setMerchantCity("");
        row.setMerchantZip("");
        row.setCardNum(cardNumber);
        row.setOrigTs(stamp);
        row.setProcTs(stamp);
        return row;
    }

    /**
     * Builds one category balance of one account, type and category.
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
     * Builds one account row with the four members the accrual reads or writes.
     *
     * @param accountId the eleven-digit account identifier
     * @param balance the opening balance as exact decimal text
     * @param cycleCredit the opening cycle credit total as exact decimal text
     * @param cycleDebit the opening cycle debit total as exact decimal text
     * @param groupId the disclosure-group identifier, at its declared ten-character width
     * @return the account, never {@code null}
     */
    private static Account account(long accountId, String balance, String cycleCredit,
            String cycleDebit, String groupId) {

        return new Account(accountId, "Y", new BigDecimal(balance), new BigDecimal("5000.00"),
                new BigDecimal("500.00"), LocalDate.of(2014, 11, 20), LocalDate.of(2025, 5, 20),
                LocalDate.of(2025, 5, 20), new BigDecimal(cycleCredit),
                new BigDecimal(cycleDebit), "A000000000", groupId);
    }

    /**
     * Collects accounts into the identifier-keyed map the real-rule runner answers reads from.
     *
     * @param rows the accounts to hold, in the order they are to be walked
     * @return a mutable insertion-ordered map keyed by account identifier, never {@code null}
     */
    private static Map<Long, Account> accountsById(Account... rows) {
        Map<Long, Account> byId = new LinkedHashMap<>();
        for (Account row : rows) {
            byId.put(row.getAccountId(), row);
        }
        return byId;
    }

    /**
     * Composes the sixteen-character disclosure-group lookup key the real-rule runner is keyed on.
     *
     * @param groupId the group identifier at its declared ten-character width
     * @param typeCd the two-character transaction type code
     * @param categoryCd the four-character transaction category code
     * @return the concatenated composite key, never {@code null}
     */
    private static String rateKey(String groupId, String typeCd, String categoryCd) {
        return groupId + typeCd + categoryCd;
    }

    /**
     * Flattens every failure an execution recorded, with its causes, into one searchable string.
     *
     * @param execution the finished execution whose recorded failures are wanted
     * @return the concatenated failure messages, empty when the run recorded none; never
     *     {@code null}
     */
    private static String failureTextOf(JobExecution execution) {
        StringBuilder text = new StringBuilder();
        for (Throwable failure : execution.getAllFailureExceptions()) {
            // WHY : Assumptions: the chain is walked rather than only the top message read, because a
            //       framework wraps a tasklet's own exception before recording it, so the message that
            //       names the refused value sits on a cause. The self-referential guard is what stops
            //       a throwable whose cause is itself from looping here forever.
            Throwable link = failure;
            while (link != null) {
                text.append(link.getMessage()).append(System.lineSeparator());
                link = link.getCause() == link ? null : link.getCause();
            }
        }
        return text.toString();
    }

    /**
     * Reads one of this module's own committed fixtures as fixed-width records under a named layout.
     *
     * <p>Assumptions: the resource is read from the CLASSPATH rather than from a repository-relative
     * path, which is what makes it this module's own copy and not the oracle's. The reason the two are
     * not interchangeable is recorded on {@link #FIXTURE_INTEREST_ROOT}.</p>
     *
     * <p>Assumptions: the bytes are decoded with {@code ISO_8859_1} and then measured against the
     * layout's declared record length, exactly as the expectation reader beside this one does. A
     * single-byte encoding is required rather than convenient -- section 3.3 of
     * {@code services/batch-service/src/test/resources/fixtures/README.md} makes the low-order byte of
     * every money field a sign overpunch, and a multi-byte decode would fold those bytes into
     * replacement characters and shift every offset after them.</p>
     *
     * @param scenario the committed scenario directory the fixture belongs to
     * @param file the fixture file name inside that directory
     * @param layoutName the registry name whose declared record length every line must match
     * @return the records, one per line, each exactly the declared length; never {@code null}
     */
    private static List<String> fixtureRecords(String scenario, String file, String layoutName) {
        int reclen = CopybookLayout.layout(layoutName).reclen();
        String resource = FIXTURE_INTEREST_ROOT + scenario + "/" + file;
        List<String> records = new ArrayList<>();
        for (String line : new String(moduleFixtureBytes(scenario, file), StandardCharsets.ISO_8859_1)
                .split("\n", -1)) {
            String record = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (record.isEmpty()) {
                continue;
            }
            assertThat(record)
                    .as("record geometry of the committed fixture %s", resource)
                    .hasSize(reclen);
            records.add(record);
        }
        return records;
    }

    /**
     * Reads one of this module's own committed fixture images from the test classpath.
     *
     * @param scenario the committed scenario directory the fixture belongs to
     * @param file the fixture file name inside that directory
     * @return the resource's bytes exactly as committed, never {@code null}
     * @throws IllegalStateException if the resource is absent from the classpath, which means this
     *     module's fixture tree and this class disagree about what is committed
     */
    private static byte[] moduleFixtureBytes(String scenario, String file) {
        String resource = FIXTURE_INTEREST_ROOT + scenario + "/" + file;
        try (InputStream stream =
                CalculateInterestJobTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("the committed fixture " + resource
                        + " is not on the test classpath, so this case has no input to drive");
            }
            return stream.readAllBytes();
        } catch (IOException unreadable) {
            throw new IllegalStateException(
                    "the committed fixture " + resource + " could not be read as bytes", unreadable);
        }
    }

    /**
     * Reads one committed expectation as fixed-width text records under a named layout.
     *
     * @param scenario the committed scenario directory the expectation belongs to
     * @param file the expectation file name inside that directory
     * @param layoutName the registry name whose declared record length every line must match
     * @return the records, one per line, each exactly the declared length; never {@code null}
     * @throws IOException if the expectation cannot be read from the repository tree
     */
    private static List<String> expectationTextRecords(String scenario, String file,
            String layoutName) throws IOException {

        return fixedWidthRecords(oracleFile("golden", scenario, file),
                CopybookLayout.layout(layoutName).reclen());
    }

    /**
     * Reads one committed expectation whole, without imposing a record geometry on it.
     *
     * @param scenario the committed scenario directory the expectation belongs to
     * @param file the expectation file name inside that directory
     * @return the file's entire contents; never {@code null}
     * @throws IOException if the expectation cannot be read from the repository tree
     */
    private static String expectationText(String scenario, String file) throws IOException {
        return Files.readString(oracleFile("golden", scenario, file), StandardCharsets.ISO_8859_1);
    }

    /**
     * Reads one committed expectation as raw fixed-width record BYTES.
     *
     * <p>Assumptions: the bytes are read rather than decoded to text, because the generated
     * transaction expectation carries low values in two pad regions and the comparison is over bytes.
     * The file stores one record per line and the record length assertion is what proves no record
     * itself contained the terminator the split is performed on.</p>
     *
     * @param scenario the committed scenario directory the expectation belongs to
     * @param file the expectation file name inside that directory
     * @param reclen the declared record length every extracted record must have
     * @return the records in file order, never {@code null}
     * @throws IOException if the expectation cannot be read from the repository tree
     */
    private static List<byte[]> expectationRecordBytes(String scenario, String file, int reclen)
            throws IOException {

        byte[] raw = Files.readAllBytes(oracleFile("golden", scenario, file));
        List<byte[]> records = new ArrayList<>();
        int start = 0;
        for (int index = 0; index <= raw.length; index++) {
            if (index < raw.length && raw[index] != LINE_FEED) {
                continue;
            }
            if (index > start) {
                records.add(Arrays.copyOfRange(raw, start, index));
            }
            start = index + 1;
        }

        assertThat(records)
                .as("every committed record is exactly %d bytes", reclen)
                .allSatisfy(record -> assertThat(record).hasSize(reclen));
        return records;
    }

    /**
     * Splits a staged payload into the fixed-width records it concatenates.
     *
     * <p>Assumptions: the payload carries {@code N} records of the declared length end to end with NO
     * delimiter between them, which is what a sequential fixed-length output is -- the committed
     * expectation stores one record per line only so a reader can inspect it, and that terminator is
     * not part of any record.</p>
     *
     * @param payload the concatenated bytes the run staged
     * @param reclen the declared record length to split on
     * @return the records in staged order, never {@code null}
     */
    private static List<byte[]> splitRecords(byte[] payload, int reclen) {
        assertThat(payload).as("the run staged a payload").isNotNull();
        assertThat(payload.length % reclen)
                .as("the staged payload is a whole number of %d-byte records", reclen)
                .isZero();

        List<byte[]> records = new ArrayList<>();
        for (int offset = 0; offset < payload.length; offset += reclen) {
            records.add(Arrays.copyOfRange(payload, offset, offset + reclen));
        }
        return records;
    }

    /**
     * Reads a file as fixed-width records, asserting the geometry of every line.
     *
     * <p>Assumptions: the decoding charset maps every byte to exactly one character, so a character
     * offset stays equal to a byte offset. A multi-byte decoding would shift every offset after the
     * first non-ASCII byte and could substitute a replacement character for one that did not
     * decode.</p>
     *
     * @param file the file to read, which is opened read-only and never written
     * @param reclen the declared record length every non-empty line must have
     * @return the records, one per line, never {@code null}
     * @throws IOException if the file cannot be read
     */
    private static List<String> fixedWidthRecords(Path file, int reclen) throws IOException {
        List<String> records = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
            if (line.isEmpty()) {
                continue;
            }
            assertThat(line)
                    .as("record geometry of %s", file)
                    .hasSize(reclen);
            records.add(line);
        }
        return records;
    }

    /**
     * Locates one reference-only fixture or expectation file inside the repository.
     *
     * <p>Assumptions: the file is opened READ-ONLY and there is no path in this class that writes
     * one. A missing file is asserted rather than skipped over, because a skipped comparison reads as
     * a pass, so the one check that pins this job to an authority outside this module would go quiet
     * exactly when it stopped running.</p>
     *
     * @param tree either the fixture tree or the expectation tree of the parity oracle suite
     * @param scenario the committed scenario directory inside the interest domain
     * @param file the file name inside that directory
     * @return the path of that file, which is asserted to exist; never {@code null}
     */
    private static Path oracleFile(String tree, String scenario, String file) {
        Path candidate = repositoryRoot()
                .resolve(Path.of("tests", tree, INTEREST_DOMAIN, scenario, file));

        assertThat(candidate)
                .as("reference-only %s artifact for interest scenario %s", tree, scenario)
                .isRegularFile();
        return candidate;
    }

    /**
     * Locates the repository root by walking up from the directory the test process runs in.
     *
     * <p>Assumptions: the root is discovered rather than reached with a preset number of parent
     * steps, because the test runner starts each module in its own base directory -- so a literal pair
     * of parent steps is correct for a module build and wrong for anything started elsewhere. Two
     * independent markers are required because either alone appears in more than one place in this
     * tree.</p>
     *
     * @return the first ancestor directory, starting with the working directory itself, that holds
     *     both the reference program and the interest expectations; never {@code null}
     * @throws IllegalStateException if no ancestor of the working directory holds both markers, which
     *     means the case is running outside a checkout of this repository; the message names the
     *     directory searched from and both markers looked for
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            boolean hasReferenceProgram =
                    Files.isRegularFile(candidate.resolve(Path.of("app", "cbl", "CBACT04C.cbl")));
            boolean hasExpectations = Files.isDirectory(
                    candidate.resolve(Path.of("tests", "golden", INTEREST_DOMAIN)));
            if (hasReferenceProgram && hasExpectations) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("repository root not found above "
                + Path.of("").toAbsolutePath() + "; expected an ancestor holding both"
                + " app/cbl/CBACT04C.cbl and tests/golden/" + INTEREST_DOMAIN);
    }

    /**
     * Masks the run-generated timestamp spans of one record so the rest can be compared byte for byte.
     *
     * <p>Assumptions: ONE transformation is applied and no other. The spans of every field the chosen
     * layout flags as a normalisable timestamp are blanked, which for the interest layout is BOTH
     * twenty-six-character stamps, because {@code app/cbl/CBACT04C.cbl:496} reads the clock once and
     * {@code :497-498} move that one value into both. Every other byte of the record -- including the
     * seventy-six low values behind the description and the twenty in the trailing pad -- is compared as
     * it stands.</p>
     *
     * <p>Refactoring Rationale: this method also mapped every low-value byte to a blank, which closed a
     * gap between the reference's padding and a fixed-width encode that rebuilt a dropped pad as blanks.
     * The gap is closed in the encoder instead, so the mapping is gone: a comparison that normalises a
     * byte cannot tell a reader what a consumer of the generation will actually read at that offset,
     * and ninety-six bytes of every record were inside the normalised set.</p>
     *
     * @param image one fixed-width record image, which is copied rather than modified in place
     * @return the copy with only the run-generated stamps blanked, never {@code null}
     */
    private static byte[] withRunGeneratedStampsMasked(byte[] image) {
        byte[] masked = image.clone();
        for (CopybookLayout.FieldSpec field : CopybookLayout.layout(INTEREST_LAYOUT).fields()) {
            if (field.normalizeTs()) {
                Arrays.fill(masked, field.start(), field.end(), BLANK);
            }
        }
        return masked;
    }

    /**
     * Asserts that the committed record carries low values only where the reference leaves them.
     *
     * <p>Assumptions: this guard is what makes the encoder's two pad declarations COMPLETE rather than
     * merely sufficient. The mapper writes a low value in exactly two regions -- the description's tail,
     * which {@code STRING} never writes, and the trailing pad, which no statement in the program
     * touches -- so every low value the reference actually emits must lie inside one of them. A
     * committed record carrying one anywhere else would mean a third region exists that the encoder
     * blank-fills, which is a difference the byte-for-byte comparison would report without saying
     * why.</p>
     *
     * <p>Refactoring Rationale: the guard previously existed to keep a canonicalisation honest, by
     * proving that mapping the low value to the blank could not mask a difference in a field carrying
     * data. Nothing is canonicalised any more, so the reason is restated rather than the guard being
     * removed with the mapping: the property it pins outlives the mapping it was written for.</p>
     *
     * @param image one committed record image, read and never modified
     */
    private static void assertLowValuesOnlyInPadRegions(byte[] image) {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(INTEREST_LAYOUT);
        CopybookLayout.FieldSpec description = spec.field("TRAN-DESC");
        CopybookLayout.FieldSpec trailingPad = spec.field("FILLER");

        for (int index = 0; index < image.length; index++) {
            if (image[index] != LOW_VALUE) {
                continue;
            }
            boolean insideDescription =
                    index >= description.start() && index < description.end();
            boolean insidePad = index >= trailingPad.start() && index < trailingPad.end();
            assertThat(insideDescription || insidePad)
                    .as("byte %d of the committed record is a low value outside the description and"
                            + " the trailing pad, so a third pad region exists that the encoder"
                            + " blank-fills", index)
                    .isTrue();
        }
    }

    /**
     * Decodes one signed zoned-decimal field out of a fixed-width record image.
     *
     * <p>Assumptions: the shared codec is the ONLY decoder used, in its overpunch mode, and its
     * geometry comes from the layout registry rather than from offsets spelled here. Section 5.2 of
     * {@code tests/README.md} records that the reference is compiled with the EBCDIC sign convention
     * and that the default silently corrupts negative balances, so a hand-rolled decoder is the one
     * error whose symptom is a plausible wrong number rather than a failure.</p>
     *
     * @param image one fixed-width record image
     * @param layoutName the registry name the field is declared in
     * @param fieldName the declared field name to decode
     * @return the exact decoded value at the field's declared scale, never {@code null}
     */
    private static BigDecimal signedField(String image, String layoutName, String fieldName) {
        CopybookLayout.FieldSpec field = CopybookLayout.layout(layoutName).field(fieldName);
        return ZonedDecimalCodec.decode(image.substring(field.start(), field.end()),
                field.intDigits(), field.decDigits(), field.signed());
    }

    /**
     * Reads one unsigned numeric field out of a fixed-width record image.
     *
     * <p>Assumptions: an unsigned picture carries plain digits with NO overpunch, so it is parsed as
     * digits rather than routed through the overpunch decoder. Sniffing the last byte of such a field
     * would turn a data character into a sign and produce a negative identifier that is still the
     * declared width and still all-legal characters.</p>
     *
     * @param image one fixed-width record image
     * @param layoutName the registry name the field is declared in
     * @param fieldName the declared field name to read
     * @return the field's value as a whole number
     */
    private static long unsignedField(String image, String layoutName, String fieldName) {
        CopybookLayout.FieldSpec field = CopybookLayout.layout(layoutName).field(fieldName);
        return Long.parseLong(image.substring(field.start(), field.end()));
    }

    /**
     * Reads one text field out of a fixed-width record image, padding intact.
     *
     * <p>Assumptions: the declared width is returned WITHOUT trimming, because a trailing blank is
     * part of a fixed-width value here -- the disclosure-group identifier is looked up at its declared
     * ten characters, and a trimmed one would compose a shorter composite key that addresses no
     * row.</p>
     *
     * @param image one fixed-width record image
     * @param layoutName the registry name the field is declared in
     * @param fieldName the declared field name to read
     * @return the field's characters at their declared width, never {@code null}
     */
    private static String textField(String image, String layoutName, String fieldName) {
        CopybookLayout.FieldSpec field = CopybookLayout.layout(layoutName).field(fieldName);
        return image.substring(field.start(), field.end());
    }

    /**
     * Builds the account rows one committed scenario drives its accrual over.
     *
     * @param images the account master records of that scenario, in file order
     * @return an insertion-ordered map keyed by account identifier, never {@code null}
     */
    private static Map<Long, Account> accountsFrom(List<String> images) {
        Map<Long, Account> byId = new LinkedHashMap<>();
        for (String image : images) {
            long accountId = unsignedField(image, ACCOUNT_LAYOUT, "ACCT-ID");
            byId.put(accountId, new Account(accountId,
                    textField(image, ACCOUNT_LAYOUT, "ACCT-ACTIVE-STATUS"),
                    signedField(image, ACCOUNT_LAYOUT, "ACCT-CURR-BAL"),
                    signedField(image, ACCOUNT_LAYOUT, "ACCT-CREDIT-LIMIT"),
                    signedField(image, ACCOUNT_LAYOUT, "ACCT-CASH-CREDIT-LIMIT"),
                    LocalDate.parse(textField(image, ACCOUNT_LAYOUT, "ACCT-OPEN-DATE")),
                    LocalDate.parse(textField(image, ACCOUNT_LAYOUT, "ACCT-EXPIRAION-DATE")),
                    LocalDate.parse(textField(image, ACCOUNT_LAYOUT, "ACCT-REISSUE-DATE")),
                    signedField(image, ACCOUNT_LAYOUT, "ACCT-CURR-CYC-CREDIT"),
                    signedField(image, ACCOUNT_LAYOUT, "ACCT-CURR-CYC-DEBIT"),
                    textField(image, ACCOUNT_LAYOUT, "ACCT-ADDR-ZIP"),
                    // WHY : Assumptions: the group identifier is carried at its declared ten
                    //       characters and is NOT trimmed, because one committed scenario stores it
                    //       BLANK on purpose so that the composed key misses and the DEFAULT retry
                    //       runs. Trimming would leave the same behaviour by accident today and would
                    //       stop distinguishing a blank group from a present one the moment a scenario
                    //       carried a shorter non-blank value.
                    textField(image, ACCOUNT_LAYOUT, "ACCT-GROUP-ID")));
        }
        return byId;
    }

    /**
     * Builds the category balances one committed scenario drives its accrual over.
     *
     * @param images the category-balance records of that scenario, in file order
     * @return the balances in the order an ordered query would return them, never {@code null}
     */
    private static List<TransactionCategoryBalance> balancesFrom(List<String> images) {
        List<TransactionCategoryBalance> balances = new ArrayList<>();
        for (String image : images) {
            balances.add(new TransactionCategoryBalance(new TransactionCategoryBalanceId(
                    unsignedField(image, CATEGORY_BALANCE_LAYOUT, "TRANCAT-ACCT-ID"),
                    textField(image, CATEGORY_BALANCE_LAYOUT, "TRANCAT-TYPE-CD"),
                    textField(image, CATEGORY_BALANCE_LAYOUT, "TRANCAT-CD")),
                    signedField(image, CATEGORY_BALANCE_LAYOUT, "TRAN-CAT-BAL")));
        }
        return balances;
    }

    /**
     * Builds the disclosure-group rate table one committed scenario resolves against.
     *
     * @param images the disclosure-group records of that scenario, in file order
     * @return the rate keyed by the sixteen-character composite of group, type and category; never
     *     {@code null}
     */
    private static Map<String, BigDecimal> ratesFrom(List<String> images) {
        Map<String, BigDecimal> byKey = new LinkedHashMap<>();
        for (String image : images) {
            byKey.put(rateKey(
                    textField(image, DISCLOSURE_GROUP_LAYOUT, "DIS-ACCT-GROUP-ID"),
                    textField(image, DISCLOSURE_GROUP_LAYOUT, "DIS-TRAN-TYPE-CD"),
                    textField(image, DISCLOSURE_GROUP_LAYOUT, "DIS-TRAN-CAT-CD")),
                    signedField(image, DISCLOSURE_GROUP_LAYOUT, "DIS-INT-RATE"));
        }
        return byKey;
    }

    /**
     * Builds the by-account cross-reference one committed scenario resolves card numbers through.
     *
     * @param images the cross-reference records of that scenario, in file order
     * @return the card number keyed by account identifier, never {@code null}
     */
    private static Map<Long, String> cardsFrom(List<String> images) {
        Map<Long, String> byAccount = new LinkedHashMap<>();
        for (String image : images) {
            byAccount.put(unsignedField(image, CROSS_REFERENCE_LAYOUT, "XREF-ACCT-ID"),
                    textField(image, CROSS_REFERENCE_LAYOUT, "XREF-CARD-NUM"));
        }
        return byAccount;
    }
}
