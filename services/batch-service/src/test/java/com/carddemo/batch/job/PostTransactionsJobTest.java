package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
import com.carddemo.batch.domain.DailyFeedWatermark;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.carddemo.batch.domain.TransactionReject;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.dto.PostingValidationResult;
import com.carddemo.batch.dto.RejectReason;
import com.carddemo.batch.mapper.AccountRecordMapper;
import com.carddemo.batch.mapper.CardXrefRecordMapper;
import com.carddemo.batch.mapper.DailyTransactionMapper;
import com.carddemo.batch.mapper.TransactionCategoryBalanceRecordMapper;
import com.carddemo.batch.mapper.TransactionRecordMapper;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DailyFeedWatermarkRepository;
import com.carddemo.batch.repository.DailyTransactionRepository;
import com.carddemo.batch.repository.TransactionCategoryBalanceRepository;
import com.carddemo.batch.repository.TransactionRejectRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.CategoryBalanceService;
import com.carddemo.batch.service.DailyFeedWatermarkService;
import com.carddemo.batch.service.DatasetGenerationService;
import com.carddemo.batch.service.PostingRecordUnitOfWork;
import com.carddemo.batch.service.PostingValidationService;
import com.carddemo.batch.service.PostingValidationService.PostingDecision;
import com.carddemo.common.codec.CopybookLayout;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
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
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * Pins the order the posting job applies its rules in, and the writes each outcome produces.
 *
 * <p>Purpose: this job is the one place the four decisions of {@code app/cbl/CBTRN02C.cbl} are sequenced.
 * Which conditions a record fails is settled by the sibling {@code service} tier against
 * {@code PostingValidationService}; what is settled HERE is that the cross-reference is read before the
 * account, that the account is not read at all when the card does not resolve, that an accepted record
 * produces exactly three writes and a rejected one produces exactly one, and that the step reports the
 * warn tier when any record was rejected. One further claim is settled here and nowhere else: that a
 * whole pass over each of the nine committed fixture trees produces, byte for byte, the four output
 * files that tree's committed expectations hold.</p>
 *
 * <p>Assumptions: the job is RUN rather than having its private body invoked. A real job execution over an
 * in-memory job repository and a no-op transaction manager exercises the framework's own step lifecycle,
 * the parameter validator the job is built with, and the exit-status propagation from step to job -- three
 * things a direct call to a tasklet would bypass, and the third of which is how the orchestrator learns
 * the tier.</p>
 *
 * <p>Assumptions: the collaborators are mocked rather than backed by a database, because the structural
 * rulings below are about the ORDER and the COUNT of calls this job makes. A database would answer the
 * same calls and would additionally require four schemas this module does not own. Whether the calls
 * reach real rows belongs to the repository tier.</p>
 *
 * <p>Assumptions: the cases fall into TWO groups that mock different things, and the split is
 * deliberate. The structural cases supply BOTH services as doubles, so that each can name the outcome
 * it exercises without arranging data to provoke it. The committed-expectation case supplies the REAL
 * {@code PostingValidationService} and {@code CategoryBalanceService} and mocks only the five
 * repositories, answering each from the scenario's own decoded fixture -- so the reject reasons, the two
 * inclusive boundaries and the create-versus-update branch are reached by the data rather than declared
 * by a stub, which is what makes a byte comparison against the oracle mean anything. Neither group
 * subsumes the other: a structural case can provoke a combination no fixture holds, and only the
 * fixture-driven one can fail because a real rule disagrees with the reference.</p>
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
 * across a STATE boundary in the target chain. And the EXTENT of the boundary the
 * three writes of {@code app/cbl/CBTRN02C.cbl:440-442} commit inside -- one per record, spanning
 * that record's three writes and nothing beyond them.</p>
 *
 * <p>Assumptions: the third of those four is a TARGET contract, and the two ends of it sit in
 * different places. The FORM of the inversion is transcribed from
 * {@code app/jcl/TRANBKP.jcl:51}, {@code //STEP10 EXEC PGM=IDCAMS,COND=(4,LT)}, which is the only
 * construct in the thirty-eight files of {@code app/jcl} that tolerates a preceding code of 4 at
 * all and therefore the only place the baseline demonstrates the inverted sense of a threshold
 * comparison. The CONSUMER of this program's tier is not that construct and cannot be: a job-control
 * condition is evaluated only against earlier steps of its own job, and {@code CBTRN02C} runs as
 * {@code app/jcl/POSTTRAN.jcl}'s single {@code STEP15} under no condition parameter at all. The
 * consumer is the {@code CheckPostingExitCode} choice state in
 * {@code infra/modules/step-functions-batch/main.tf}, which admits the warn code and lets the next
 * state run. Citing the baseline clause as the consumer would assert a cross-job data path the
 * reference does not have, and a reader who then looked for it would find a gate on another job's
 * own steps.</p>
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
 * <p>Trade-offs: what this class asserts about the transactional boundary is its EXTENT and not the
 * durability of a commit. A recording transaction manager makes the begins, the commits and the
 * rollbacks countable, and where in the record loop each one falls is precisely what distinguishes a
 * per-record boundary from a pass-wide one -- so the extent IS observable here and is asserted here.
 * What is not observable with the repositories supplied as test doubles is whether a rolled-back
 * write left no row behind; that claim is proven where it can fail for the right reason, by
 * {@code services/batch-service/src/test/java/com/carddemo/batch/repository/PostingUnitOfWorkIT.java},
 * which drives the cross-schema writes against a real database and reads the surviving rows back
 * through fresh selects. Splitting the claim across two tiers costs a reader one extra file; making
 * both halves in one tier that cannot fail for the right reason would cost the second check. The
 * cross-schema write grants that case depends on are created by
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

    /**
     * The number of records the multi-record boundary cases stage.
     *
     * <p>Assumptions: three and not two, because a per-record boundary case needs a record BEFORE the
     * one of interest and a record AFTER it -- a predecessor to prove survival and a successor to prove
     * the pass stopped. Two records supply one of the two and never both.</p>
     */
    private static final int THREE_RECORDS = 3;

    /**
     * The one unit of work the pass opens that is not a record's.
     *
     * <p>Assumptions: the feed watermark's starting position is read under a row lock, which requires a
     * transaction, and the tasklet body deliberately runs in none -- so the read is made inside a unit
     * of work of its own. It is a real transaction and therefore indistinguishable from a record's by
     * the matchers below, which is why the boundary cases add it by name instead of counting one more
     * record.</p>
     */
    private static final int WATERMARK_READ_BOUNDARY = 1;

    /** The one-based feed position the rollback-isolation case makes the account write raise on. */
    private static final int FAILING_ORDINAL = 2;

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

    /** The repository-relative location of the parity oracle's committed posting fixture trees. */
    private static final String FIXTURE_POSTING = "tests/fixtures/posting";

    /**
     * The classpath prefix of this module's own committed posting fixture images.
     *
     * <p>Assumptions: this prefix and {@link #FIXTURE_POSTING} name two trees that section 2 of
     * {@code services/batch-service/src/test/resources/fixtures/README.md} records as byte-identical
     * derivations, and both are read here on purpose. The oracle tree is where the parity expectations
     * live and is what the golden-master comparison is against; the module tree is what the build
     * packages onto the test classpath, so reading it is what makes a drift between the two visible in
     * this suite rather than only in review -- and a case that reached outside the module for its
     * inputs would pass in a checkout where this module's own fixtures were missing.</p>
     */
    private static final String FIXTURE_POSTING_ROOT = "fixtures/posting/";

    /** The fixture file naming a scenario's seeded account master. */
    private static final String ACCOUNT_FIXTURE = "acctdata.txt";

    /** The fixture file naming a scenario's seeded card cross-reference. */
    private static final String CROSS_REFERENCE_FIXTURE = "cardxref.txt";

    /** The same image, under the name the classpath-driven harness reads it by. */
    private static final String XREF_FIXTURE = "cardxref.txt";

    /** The fixture file naming a scenario's daily transaction feed. */
    private static final String FEED_FIXTURE = "dailytran.txt";

    /** The fixture file naming a scenario's seeded transaction-category balances. */
    private static final String CATEGORY_BALANCE_FIXTURE = "tcatbal.txt";

    /** The expectation file holding a scenario's posted transaction master. */
    private static final String POSTED_MASTER_FILE = "tranfile.expected";

    /** The expectation file holding a scenario's account master after the pass. */
    private static final String ACCOUNT_MASTER_FILE = "acctdat.expected";

    /** The expectation file holding a scenario's category balances after the pass. */
    private static final String CATEGORY_BALANCE_FILE = "tcatbal.expected";

    /** The same expectation, under the name the classpath-driven harness reads it by. */
    private static final String ACCOUNT_EXPECTATION = "acctdat.expected";

    /** The same expectation, under the name the classpath-driven harness reads it by. */
    private static final String CATEGORY_BALANCE_EXPECTATION = "tcatbal.expected";

    /** The same expectation, under the name the classpath-driven harness reads it by. */
    private static final String LEDGER_EXPECTATION = "tranfile.expected";

    /** The registry name of the account layout the account images are decoded and encoded under. */
    private static final String ACCOUNT_LAYOUT = "ACCOUNT";

    /** The registry name of the cross-reference layout the card images are decoded under. */
    private static final String CROSS_REFERENCE_LAYOUT = "XREF";

    /** The registry name of the feed layout the daily transaction images are decoded under. */
    private static final String FEED_LAYOUT = "DALYTRAN";

    /** The registry name of the posted master layout the ledger rows are encoded under. */
    private static final String POSTED_MASTER_LAYOUT = "TRAN";

    /** The same layout, under the name the classpath-driven harness reads it by. */
    private static final String LEDGER_LAYOUT = "TRAN";

    /** The registry name of the category-balance layout those images are decoded and encoded under. */
    private static final String CATEGORY_BALANCE_LAYOUT = "TCATBAL";

    /** The blank byte a masked timestamp span is filled with before a byte comparison. */
    private static final byte BLANK = 0x20;

    /** The byte the committed files separate one record from the next with. */
    private static final byte LINE_FEED = 0x0A;

    /** The byte the reference leaves in a pad region it never writes. */
    private static final byte LOW_VALUE = (byte) 0x00;

    /**
     * The byte a REWRITTEN category-balance row carries in its pad region.
     *
     * <p>Assumptions: an ASCII zero rather than a blank, which contradicts the general
     * pad-with-spaces rule and is a measured property of the corpus. Section 6.2 of
     * {@code services/batch-service/src/test/resources/fixtures/README.md} records the measurement and
     * the reason the measured byte wins. It applies only to the rewrite arm; a row the pass CREATES
     * carries the low value instead, as the case reading this constant records.</p>
     */
    private static final byte CATEGORY_BALANCE_REWRITE_PAD = (byte) 0x30;

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

    /**
     * The watermark table, mocked so each case states what the feed had already consumed.
     *
     * <p>Assumptions: the SERVICE over it is real, not mocked. The service is where the
     * advance-only rule and the no-op-when-nothing-moved rule live, so mocking it would let this
     * class assert that the job asked without asserting that the answer meant anything.</p>
     */
    private DailyFeedWatermarkRepository watermarks;

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

    /**
     * The per-record unit of work the job under test brackets.
     *
     * <p>Assumptions: held on the instance beside the configuration because the registration method
     * takes it as an argument rather than the configuration holding it, so a case rebuilding the job
     * over a different transaction manager has to hand the same unit back. It is the REAL production
     * component over this class's mocked repositories, never a double of it.</p>
     */
    private PostingRecordUnitOfWork perRecord;

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

        // WHY : Assumptions: an EMPTY watermark row is the default arrangement, so every case in
        //       this class walks the feed from the beginning exactly as it did before the watermark
        //       existed. That keeps every count, every reject byte and every golden comparison here
        //       measuring posting rather than measuring the cursor; the cursor's own behaviour is
        //       asserted by the two cases at the end of this class, which arrange a stored position.
        this.watermarks = mock(DailyFeedWatermarkRepository.class);
        when(this.watermarks.findByFeedName(anyString())).thenReturn(Optional.empty());
        when(this.watermarks.findById(anyString())).thenReturn(Optional.empty());
        DailyFeedWatermarkService watermark =
                new DailyFeedWatermarkService(this.watermarks, clock);

        // WHY : Assumptions: the per-record unit is the REAL PostingRecordUnitOfWork built over this
        //       class's own mocked repositories and rules, not a mock of it. Every case here asserts
        //       what that unit does with those collaborators -- the write order, the cycle buckets,
        //       the reject bytes, the checkpoint -- so mocking it would leave the cases asserting
        //       against a stub of the code under test. What is mocked stays one layer lower, where
        //       the boundary between this module and its database is.
        this.perRecord = new PostingRecordUnitOfWork(this.accounts, this.ledger,
                this.rejects, this.validation, this.categoryBalances, watermark, clock);

        this.configuration = new PostTransactionsJob(this.feed,
                this.generations, this.ledgerOfSteps, watermark);

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
     * @param transactionManager the manager the job opens one transaction per feed record through, and
     *     whose suspended bracket the pass body itself runs inside; must not be {@code null}
     * @return the registered job, never {@code null}
     */
    private Job buildJobOver(PlatformTransactionManager transactionManager) {
        JobParametersValidator validator = new BatchConfig().carddemoJobParametersValidator();
        return this.configuration.postTransactions(
                this.jobRepository, transactionManager, validator, this.perRecord);
    }

    /**
     * An accepted record produces exactly three writes, and no reject row.
     *
     * <p>Pins {@code app/cbl/CBTRN02C.cbl:440-442}, which performs the category-balance update, then
     * the account update, then the transaction write, for every accepted record. The ORDER those three
     * are performed in, and the per-record boundary they commit inside, are asserted separately by
     * {@link #theThreeWritesRunInReferenceOrderInsideTheDeclaredBoundary()} and
     * {@link #eachRecordRunsInsideItsOwnBoundary()}; what is settled here is the COUNT, that an
     * accepted record produces three writes and no reject row.</p>
     *
     * <p>Refactoring Rationale: the link above named
     * {@code theThreeWritesRunInOrderInsideTheOneDeclaredBoundary()}, which is not a method of this
     * class and never was, so the reference resolved to nothing and a reader following it was sent
     * looking for a case that does not exist. It is corrected to the method that makes the claim
     * rather than deleted, because the split between the COUNT asserted here and the ORDER asserted
     * there is exactly what a reader arriving at this case needs told.</p>
     *
     * <p>Trade-offs: an intra-file link is not validated by this build -- the Checkstyle documentation
     * gate this module runs checks that a Javadoc block EXISTS and is complete, not that its references
     * resolve, and enabling {@code doclint}'s reference check would gate the whole reactor on a tool the
     * build does not otherwise run. The mitigation chosen instead is that every such link names a
     * method declared in this class, which a grep for {@code &#123;@link #} against the declaration list
     * verifies; a class whose links left it would earn the doclint step. Refactoring Rationale: this
     * sentence claimed the link was the ONLY intra-file link in the class, which a grep for
     * {@code &#123;@link #} disproves at twelve occurrences -- two of them inside this very block. The
     * mitigation is restated as the property that actually holds, because an unfalsifiable claim about
     * a count is worse than no claim: a reader who checks it finds it false and discounts the
     * surrounding reasoning with it. The name was also corrected against the DECLARATION rather than the declaration
     * renamed to match the link, because the method is referenced by no other member and its own name
     * states which order it pins.</p>
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
     * that rejects were written.</p>
     *
     * <p>Refactoring Rationale: this note cited {@code app/jcl/TRANBKP.jcl:51},
     * {@code //STEP10 EXEC PGM=IDCAMS,COND=(4,LT)}, as the tier's CONSUMER, and that citation is
     * withdrawn because that gate cannot observe this program's return code. A JCL {@code COND}
     * parameter tests only the codes of steps in ITS OWN job, and the two sit in different jobs:
     * {@code CBTRN02C} runs as {@code app/jcl/POSTTRAN.jcl}'s single {@code STEP15}, while that gate
     * conditions {@code STEP10} on {@code TRANBKP}'s own {@code STEP05R} unload and {@code STEP05}
     * delete. Measured: {@code app/jcl/POSTTRAN.jcl} carries no {@code COND=} at all, so NOTHING in
     * the reference JCL gates on the posting code -- it reaches JES and the operator. The tier's
     * authorities are therefore the program, which sets 4 at {@code :230} only when the reject count
     * is positive at {@code :229}, and the suite's own condition-code rubric in
     * {@code tests/README.md} section 8, which assigns 4 to a correctly written business reject.
     * Collapsing the tier in either direction still changes which downstream states run, which is
     * what this case pins.</p>
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
     * warn would let the target chain's {@code CheckPostingExitCode} choice state wave a half-posted
     * pass on to {@code CalculateInterest}.</p>
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
     * accumulated return code" and therefore inverts to "run while the code is 4 or lower".
     * Transcribing the predicate with its original sense would invert which runs proceed, and every
     * clean run would be the one that stopped the chain.</p>
     *
     * <p>Assumptions: that clause is cited for its FORM and not as a data path, and the distinction is
     * load-bearing because the two ends of the contract sit in different systems. It is the only one of
     * the ten condition keywords in the thirty-eight files of {@code app/jcl} that tolerates a
     * preceding 4 -- the other nine being eight {@code COND=(0,NE)} step gates that demand a clean zero
     * and one record filter -- so it is the only place the baseline demonstrates the inverted sense of a
     * threshold comparison. It does not consume this program's code: a job-control condition reads
     * earlier steps of its OWN job, it gates {@code TRANBKP}'s own {@code STEP10}, and posting runs in
     * {@code app/jcl/POSTTRAN.jcl}, which carries no condition parameter. The predicate asserted below
     * is therefore the target chain's: {@code app/cbl/CBTRN02C.cbl:229-230} produces the tier, and the
     * {@code CheckPostingExitCode} choice state in {@code infra/modules/step-functions-batch/main.tf}
     * consumes it, admitting the clean and warn codes and routing anything else to failure.</p>
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
     * The three writes are issued in reference order, inside that record's own boundary.
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
        PlatformTransactionManager transactions = spy(new DistinctBoundaryTransactionManager());
        this.job = buildJobOver(transactions);

        // WHY : Assumptions: exactly ONE record is posted, and the count is load-bearing rather than
        //       incidental. Ordered verification finds a matching SUBSEQUENCE, so over two records the
        //       account write of the first and the ledger write of the second form an ascending pair
        //       whatever order each record's own writes were issued in -- measured: a job with the two
        //       writes transposed satisfies the sequence below over two records and fails it over one.
        //       The EXTENT of the boundary is asserted separately by eachRecordRunsInsideItsOwnBoundary,
        //       where more than one record is what makes that claim observable; over the one record
        //       here a per-record boundary and a pass-wide one are the same single transaction, which
        //       is exactly why this case can assert the order without asserting the extent.
        stageOneRecord(resolvableRecord(new BigDecimal("100.00")));
        stageAcceptedDecision(new BigDecimal("100.00"));

        run();

        // WHY : Assumptions: one ordered verification covers both claims at once, because the boundary
        //       and the order are the same observation read at different granularities -- the three
        //       writes fall between the same pair of manager calls, in the reference sequence. Two
        //       separate verifications could each hold while the writes straddled a commit.
        // WHY : Assumptions: the two bracket calls are matched on their PROPAGATION rather than with a
        //       bare any(). The tasklet step opens a suspended bracket of its own before the record's
        //       unit of work and closes it after, so an any() match would be satisfied by the step's
        //       bracket surrounding the writes -- which every implementation satisfies, including one
        //       that committed each write separately inside it.
        InOrder sequence = inOrder(
                transactions, this.categoryBalances, this.accounts, this.ledger);
        sequence.verify(transactions).getTransaction(
                argThat(PostTransactionsJobTest::isRealUnitOfWork));
        sequence.verify(this.categoryBalances).accumulatePostedTransaction(any(), any());
        sequence.verify(this.accounts).save(any(Account.class));
        sequence.verify(this.ledger).save(any(Transaction.class));
        sequence.verify(transactions).commit(argThat(TransactionStatus::isNewTransaction));
        verify(transactions, never()).rollback(any());
    }

    /**
     * Each feed record runs inside its own boundary, and the pass body itself runs inside none.
     *
     * <p>Pins the extent of the paragraph at {@code app/cbl/CBTRN02C.cbl:424-444} as a PER-RECORD unit
     * of work. {@code 2500-POST-TRANSACTION} is performed once per record from the read loop at
     * {@code app/cbl/CBTRN02C.cbl:200-226}, and the three writes it performs are the only work the
     * reference groups; nothing in the reference makes the whole feed one atomic unit.</p>
     *
     * <p>Refactoring Rationale: the boundary used to be the STEP's, so one transaction spanned every
     * record in the feed and this case asserted exactly one begin. That shape was wrong in three ways
     * and the assertion was pinning the wrong thing. A failure on the three-hundredth record discarded
     * two hundred and ninety-nine correct postings the reference would have kept, because the reference
     * commits each record as it goes. Every row the pass touched stayed locked until the pass ended,
     * so the online tier could not update any account for the length of the batch window. And the
     * durable step ledger promises a redrive RESUMES rather than repeats, which a pass-wide rollback
     * makes untrue. Inverting the claim is therefore the fix, and it is asserted here because the
     * count of begins is the only thing that distinguishes the two shapes -- both write every row and
     * both report the same tier.</p>
     *
     * <p>Assumptions: more than one record is required for the claim to be observable at all -- over a
     * single record a per-pass boundary and a per-record boundary are the same single transaction.
     * Three are staged, two accepted and one rejected, so the count also settles that a REJECTED record
     * takes a boundary of its own rather than sharing one or going without: it writes a reject row,
     * which is transactional work.</p>
     *
     * <p>Assumptions: the two propagations are told apart rather than counted together, because the
     * framework's tasklet step opens a bracket of its own around the body and a bare total would
     * conflate it with the records'. The step's bracket is declared {@code PROPAGATION_NOT_SUPPORTED}
     * by {@code PostTransactionsJob}, which makes it an EMPTY transaction -- the manager creates no
     * transaction for it, so its status reports {@code isNewTransaction()} as {@code false} -- while
     * each record's is {@code PROPAGATION_REQUIRED} and reports {@code true}. Discriminating on those
     * two properties asserts more than a total would: that the body runs outside any transaction AND
     * that each record opens a real one.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("open one transaction per record, and none around the pass body")
    void eachRecordRunsInsideItsOwnBoundary() throws Exception {
        // WHY : Alternatives Considered: a bare mock transaction manager, which is the first thing to
        //       reach for and does not work here. The framework's tasklet step registers a transaction
        //       synchronization inside its bracket, and only a real manager activates synchronization
        //       when it opens one -- a mock returns a status without doing so, and the step then fails
        //       on the registration before it reaches the first write, so the case would fail for a
        //       reason unrelated to the property under assertion. A spy over a manager with real
        //       lifecycle behaviour keeps the begin-and-commit semantics and records the calls as well;
        //       DistinctBoundaryTransactionManager documents why the framework's own double cannot
        //       serve here.
        PlatformTransactionManager transactions = spy(new DistinctBoundaryTransactionManager());
        this.job = buildJobOver(transactions);

        stageRecords(resolvableRecord(new BigDecimal("100.00"), 1L),
                resolvableRecord(new BigDecimal("30.00"), 2L),
                record(UNRESOLVABLE_CARD, new BigDecimal("10.00"), 3L));
        stageDecisionRoutedByCard(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE);

        run();

        // WHY : Assumptions: the expected count is the records PLUS ONE, and the one is named rather
        //       than folded into the number. The pass opens a unit of work of its own before the walk,
        //       for the feed watermark's locking read -- a row lock needs a transaction and the tasklet
        //       body runs in none -- and that transaction is a real one, so the matcher below cannot
        //       tell it from a record's. Asserting records-plus-one is what keeps this case sensitive
        //       to a fourth record's boundary going missing.
        verify(transactions, times(THREE_RECORDS + WATERMARK_READ_BOUNDARY))
                .getTransaction(argThat(PostTransactionsJobTest::isRealUnitOfWork));
        verify(transactions, times(THREE_RECORDS + WATERMARK_READ_BOUNDARY))
                .commit(argThat(TransactionStatus::isNewTransaction));
        verify(transactions, never()).rollback(any());

        // WHY : Assumptions: the step's own bracket is asserted PRESENT and EMPTY rather than ignored.
        //       Were it a real transaction, every per-record template would merely JOIN it under
        //       PROPAGATION_REQUIRED and the whole pass would be one unit of work again -- with the
        //       three begins above still recorded, because a joining template still calls the manager.
        //       This is the assertion that makes the three above mean what they say.
        verify(transactions, times(1)).getTransaction(argThat(PostTransactionsJobTest::isSuspendedBracket));
        verify(transactions, times(1)).commit(argThat(status -> !status.isNewTransaction()));

        verify(this.ledger, times(2)).save(any(Transaction.class));
        verify(this.rejects, times(1)).save(any(TransactionReject.class));
    }

    /**
     * A record that fails rolls back its own writes and leaves the records before it committed.
     *
     * <p>This is the behaviour the per-record boundary exists for, and it is the half of the divergence
     * note {@code D-POSTING-ATOMIC-NO-REJECT-109} that a reader is most likely to get backwards. The
     * migrated job is MORE atomic than the reference within one record -- a failed account write undoes
     * that record's category balance and ledger row, where
     * {@code app/cbl/CBTRN02C.cbl:554-560} returns normally and leaves both -- and NO MORE atomic than
     * the reference across records, because the reference commits each record as it goes and so does
     * this job.</p>
     *
     * <p>Assumptions: the failure is provoked on the SECOND of three records, not the first or the
     * last, so that the case observes a committed predecessor and an unreached successor at once. A
     * failure on the first would leave nothing committed to prove survival, and one on the last would
     * leave nothing unreached to prove the pass stopped.</p>
     *
     * <p>Assumptions: the raising collaborator is the account write, because it is the MIDDLE of the
     * three writes -- the category balance precedes it and the ledger row follows it. A failure there
     * is the only one that can leave a half-written record if the boundary is wrong, and it is exactly
     * the failure {@code app/cbl/CBTRN02C.cbl:554-560} leaves half-written.</p>
     *
     * <p>Assumptions: what survives is asserted through the MANAGER's commit and rollback counts rather
     * than by reading rows back, because the repositories here are test doubles and hold no rows to
     * read. Whether a rolled-back write actually left no row is proven against a real database by
     * {@code services/batch-service/src/test/java/com/carddemo/batch/repository/PostingUnitOfWorkIT.java}.</p>
     *
     * @throws Exception if the framework's own execution path raises, which this case provokes
     *     deliberately and the framework records on the execution rather than rethrowing
     */
    @Test
    @DisplayName("roll back only the failing record and leave its predecessor committed")
    void aFailingRecordRollsBackAloneAndItsPredecessorStaysCommitted() throws Exception {
        PlatformTransactionManager transactions = spy(new DistinctBoundaryTransactionManager());
        this.job = buildJobOver(transactions);

        stageRecords(resolvableRecord(new BigDecimal("100.00"), 1L),
                resolvableRecord(new BigDecimal("30.00"), 2L),
                resolvableRecord(new BigDecimal("10.00"), 3L));
        stageAcceptedDecision(new BigDecimal("100.00"));

        // WHY : Alternatives Considered: chaining thenAnswer().thenThrow() so the second call raises.
        //       Rejected because the ordinal that raises is the WHOLE POINT of this case and a chained
        //       stub states it only by position, so a later edit that added a record before the
        //       failing one would silently move the failure to a different ordinal and the case would
        //       still pass while asserting something else. An explicit counter names the ordinal.
        AtomicInteger accountWrites = new AtomicInteger();
        when(this.accounts.save(any(Account.class))).thenAnswer(call -> {
            if (accountWrites.incrementAndGet() == FAILING_ORDINAL) {
                throw new OptimisticLockingFailureException(
                        "the online tier updated this account first");
            }
            return call.getArgument(0);
        });

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);

        // WHY : Assumptions: two RECORD begins and not three. The first record commits, the second
        //       rolls back and the exception leaves the record loop, so the third is never read -- which
        //       is what makes the failure a STOPPED pass rather than a pass that skipped one record.
        //       One further begin belongs to the watermark's locking read before the walk, and it is
        //       added explicitly rather than absorbed, so a record's boundary going missing still moves
        //       this number.
        verify(transactions, times(2 + WATERMARK_READ_BOUNDARY))
                .getTransaction(argThat(PostTransactionsJobTest::isRealUnitOfWork));
        // WHY : Assumptions: two commits -- the first record's and the watermark read's -- against one
        //       rollback, the failing record's. The read commits because it only reads: it is the same
        //       count in a passing pass and in this one, which is why the rollback below is the
        //       assertion that distinguishes them.
        verify(transactions, times(1 + WATERMARK_READ_BOUNDARY))
                .commit(argThat(TransactionStatus::isNewTransaction));
        verify(transactions, times(1)).rollback(argThat(TransactionStatus::isNewTransaction));

        // WHY : Assumptions: the ledger write is counted because it FOLLOWS the failing write in the
        //       reference order, so exactly one is the signature of a boundary that closed around the
        //       failing record. A pass-wide boundary would show the same count here, which is why the
        //       commit and rollback counts above are the load-bearing half of this case.
        verify(this.ledger, times(1)).save(any(Transaction.class));
        verify(this.categoryBalances, times(2)).accumulatePostedTransaction(any(), any());
    }

    /**
     * A rejected record's row is written inside that record's boundary.
     *
     * <p>Pins the pairing that {@code app/cbl/CBTRN02C.cbl:446-465} performs as one paragraph: the
     * reject is recorded once, and the migrated form records it twice for two different readers -- the
     * 430-byte stream record the golden masters compare byte for byte, and the decomposed row that
     * makes a reject queryable. Only the row is transactional, so only the row can be inside the
     * boundary, and this case fixes which side of the commit each one falls on.</p>
     *
     * <p>Assumptions: the ordered verification is the assertion, not the count. The row write falling
     * between the begin and the commit is what makes a reject whose row failed to persist unable to
     * reach the compared dataset; a count alone would hold with the write on either side.</p>
     *
     * <p>Trade-offs: the stream append cannot be observed from here at all -- it goes to a temporary
     * file the job creates, buffers into and deletes within one call, and the job exposes no seam onto
     * it. What IS asserted is that the record reaches the staged dataset, which the sibling case
     * {@code aRejectedRecordReachesTheDatasetAsOne430ByteRecord} does by reading the staged bytes. The
     * ordering of the append relative to the commit is therefore documented at its call site rather
     * than asserted, and the direction that matters is the one this case does cover: the row is inside
     * the transaction, so nothing can be appended for a row that rolled back.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("write a rejected record's row inside that record's own boundary")
    void aRejectedRecordWritesItsRowInsideItsOwnBoundary() throws Exception {
        PlatformTransactionManager transactions = spy(new DistinctBoundaryTransactionManager());
        this.job = buildJobOver(transactions);

        stageOneRecord(record(UNRESOLVABLE_CARD, new BigDecimal("10.00")));
        stageRejectedDecision(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE, null);

        run();

        InOrder sequence = inOrder(transactions, this.rejects);
        sequence.verify(transactions).getTransaction(argThat(PostTransactionsJobTest::isRealUnitOfWork));
        sequence.verify(this.rejects).save(any(TransactionReject.class));
        sequence.verify(transactions).commit(argThat(TransactionStatus::isNewTransaction));
        verify(transactions, never()).rollback(any());

        // WHY : Assumptions: the posting writes are asserted ABSENT as well, because a boundary that
        //       opened for a reject would be indistinguishable from one that also posted it if only
        //       the transaction calls were counted.
        verify(this.ledger, never()).save(any(Transaction.class));
        verify(this.accounts, never()).save(any(Account.class));
    }

    /**
     * Reports whether a transaction definition is one of the per-record units of work.
     *
     * <p>Assumptions: the discriminator is the propagation and not the object identity, because the
     * definition the manager receives is the {@code TransactionTemplate} the job built and this class
     * holds no reference to it. {@code PROPAGATION_REQUIRED} is the template's default and the job
     * documents at its construction site why it is not {@code REQUIRES_NEW}.</p>
     *
     * @param definition the definition the manager was called with, which may be {@code null}
     * @return {@code true} when the definition asks for a real transaction
     */
    private static boolean isRealUnitOfWork(TransactionDefinition definition) {
        return definition != null
                && definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED;
    }

    /**
     * Reports whether a transaction definition is the tasklet step's own suspended bracket.
     *
     * @param definition the definition the manager was called with, which may be {@code null}
     * @return {@code true} when the definition asks for no transaction at all
     */
    private static boolean isSuspendedBracket(TransactionDefinition definition) {
        return definition != null
                && definition.getPropagationBehavior()
                        == TransactionDefinition.PROPAGATION_NOT_SUPPORTED;
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
     * The job is refused when the generation date is absent, which is the migrated form of its DD.
     *
     * <p>Pins the parameter the reject stream's generation is partitioned under, and pins it as
     * ORCHESTRATION METADATA rather than as a business input. {@code app/jcl/POSTTRAN.jcl:34-38}
     * allocates the reject stream as {@code DSN=AWS.M2.CARDDEMO.DALYREJS(+1)} with
     * {@code DISP=(NEW,CATLG,DELETE)}, so the reference step is told which generation to write in a
     * data definition; the migration plan's rule T6 turns a GDG {@code (+1)} into a new object-store
     * generation prefix and section 0.4.1.7 fixes that prefix as {@code dt=YYYY-MM-DD/gen=NNNN}, whose
     * date component a migrated step must be told because section 0.7.5 forbids reading it from a
     * clock. The parameter is therefore this step's migrated data definition, and requiring it is a
     * transcription of {@code POSTTRAN.jcl} rather than an addition to it.</p>
     *
     * <p>⚠️ Refactoring Rationale: this block previously read the requirement as a "documented
     * DIVERGENCE" on the ground that {@code app/jcl/POSTTRAN.jcl:23} is
     * {@code //STEP15 EXEC PGM=CBTRN02C} with no {@code PARM=}. <b>That reasoning conflated two
     * different baseline mechanisms.</b> The absence of a {@code PARM=} is a fact about the PROGRAM,
     * and it is correctly reflected: neither {@code CBTRN02C} nor the migrated job derives any posted
     * or rejected field from a date. Dataset identity reached that same step by another route
     * entirely, four lines further down the very same file, and the block had cited the {@code EXEC}
     * line while overlooking the {@code DD}. Reading the requirement as an addition also made the
     * justification wrong in kind -- it appealed to job-instance identity and to "reproducibility"
     * rather than to the artifact the parameter actually replaces. The residual difference that IS
     * worth registering is narrower than the block claimed, and it is registered rather than argued
     * here: {@code docs/architecture/cobol-to-service-traceability.md} carries it as
     * {@code D-POSTING-GENERATION-DATE}. Trade-offs: the assertion below is unchanged, because the
     * observable behaviour was never in doubt -- what was wrong was the reason given for it, and a
     * wrong reason attached to a passing case is worse than no reason, since the next reader
     * inherits it.</p>
     *
     * <p>Assumptions: the refusal is read from the job's own validator rather than by starting the job,
     * because a job started with invalid parameters never reaches its step, so there would be nothing
     * to observe. What is settled is that the shared validator really is attached to THIS job -- a job
     * built without one would validate nothing and would run until the step read a parameter that was
     * not there.</p>
     */
    @Test
    @DisplayName("refuse to run without the reject generation's date")
    void aMissingGenerationDateIsRefused() {
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
     * <p>⚠️ <b>This case is not the parity comparison and must not be read as one.</b> Everything it
     * reads comes from the committed files and nothing from a run, so it holds with the job never
     * started -- which is exactly why it is scoped to a RELATIONSHIP BETWEEN TWO EXPECTATION FILES
     * rather than named for parity. The comparison of what a real posting run produces against those
     * files is {@code PostTransactionsJobParityIT} in this package, which launches the job against a
     * database container and compares all four recorded artifacts and the return code for each of the
     * nine trees. Refactoring Rationale: an earlier revision named this case for parity, and the name
     * outran the assertion -- three of the four recorded artifacts were compared by nothing at all, so
     * a balance accumulated into the wrong cycle column or a category row created where the reference
     * updated would have left the suite green. The scope is corrected here and the missing comparison
     * is supplied there rather than widened into this tier, which has no engine to produce a row
     * against.</p>
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
     * Every committed posting scenario is REPRODUCED by running the job over that scenario's own
     * committed fixture images.
     *
     * <p>Refactoring Rationale: this is the case the sibling above cannot be. That one reads two
     * committed files and compares them to each other and to a declared tier, which settles that the
     * expectation trees are internally consistent and settles NOTHING about this module -- it passes
     * unchanged if the job is deleted. And the module's own fixture tree at
     * {@code services/batch-service/src/test/resources/fixtures/posting} was, until this case existed,
     * committed and documented but read by nothing, so its nine scenarios of inputs were carried in the
     * build as dead weight. Both gaps close the same way: decode the inputs, run the real job over
     * them, and compare what it produced against the committed expectations. The sibling is kept
     * because its census of the trees is a different claim and still worth making.</p>
     *
     * <p>Assumptions: the REAL {@code PostingValidationService} and {@code CategoryBalanceService} are
     * wired rather than the mocks every other case here uses, because a mocked decision would make
     * this case assert its own staging rather than the pass. Their own units are asserted by the
     * sibling service tier; what is settled HERE is that the job, those two services and the four
     * record decoders together reproduce the reference's output from the reference's input.</p>
     *
     * <p>Assumptions: what each expectation asserts is DERIVED from the trees rather than declared per
     * scenario, and that is what keeps nine scenarios inside one case without a table of hand-written
     * answers. For each of the three masters the rule is the same: where the committed expectation
     * DIFFERS from the committed input, the pass must have written that master and the row it wrote
     * must equal the expectation; where the two are identical, the pass must not have written it at
     * all. Measured across the nine trees, that rule alone distinguishes every scenario --
     * {@code happy_path}, {@code boundary_exact_limit} and {@code boundary_expiry_equal} differ in the
     * balance and cycle-credit spans of the account and in the balance span of the category row;
     * {@code zero_balance} differs in the same account spans and turns an EMPTY category input into a
     * one-row expectation; and {@code empty_input} and the four {@code reject_10x_*} trees differ
     * nowhere.</p>
     *
     * <p>Assumptions: the category-balance ARM follows from the same rule rather than from a stub.
     * {@code zero_balance} commits an empty {@code tcatbal.txt} and a one-row {@code tcatbal.expected},
     * so only the create arm at {@code app/cbl/CBTRN02C.cbl:503-524} can produce it; the three other
     * posting scenarios commit a row whose balance the expectation moves, so only the update arm at
     * {@code app/cbl/CBTRN02C.cbl:526-542} can produce that. The arm is therefore asserted as the
     * transition each scenario's own files require -- absent-to-present, or present-and-changed -- and
     * neither arm is named by a flag this case set itself.</p>
     *
     * <p>Assumptions: images are compared after the canonicalisation {@link #canonicalisedRecord}
     * applies, and the two transformations it makes are the two the reference and the codec genuinely
     * disagree about; its own note records them and the guard beside it keeps the padding contract
     * asserted rather than discarded.</p>
     *
     * @param scenario the scenario name, which is BOTH the fixture directory under
     *     {@code fixtures/posting} and the expectation tree under {@code tests/golden/posting}
     * @param expectedTier the tier the pass must report for that scenario
     * @throws Exception if the framework's own execution path raises, which no scenario provokes
     */
    @ParameterizedTest(name = "{0} reproduces its expectation tree")
    @MethodSource("committedPostingScenarios")
    @DisplayName("reproduce every committed posting scenario from that scenario's own fixtures")
    void everyCommittedScenarioIsReproducedFromItsOwnFixtures(
            String scenario, BatchReturnCode expectedTier) throws Exception {

        // WHY : Assumptions: this case drives the module's OWN committed fixture copy, which is what
        //       makes its claim different from the oracle-driven case below rather than a second run of
        //       it, and it keeps the boundary-distinguishing transaction manager it has always used so
        //       the per-record commit the pass performs is a real begin and commit here.
        PostingRun run = runPostingScenario(scenario, PostingFixtureSource.MODULE_CLASSPATH,
                new DistinctBoundaryTransactionManager());
        Path tree = goldenPostingRoot().resolve(scenario);

        assertThat(run.tier())
                .as("the tier the pass reported for %s", scenario)
                .isEqualTo(expectedTier);
        assertThat(run.tier().numericValue())
                .as("the tier the pass reported against the committed return code of %s", scenario)
                .isEqualTo(Integer.parseInt(
                        Files.readString(tree.resolve(RETURN_CODE_FILE)).trim()));

        assertRejectStreamReproduced(scenario, run);
        assertAccountMasterReproduced(scenario, run);
        assertCategoryBalanceReproduced(scenario, run);
        assertLedgerRowReproduced(scenario, run);
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
     * Every committed posting scenario is REPRODUCED: its four output files and its tier.
     *
     * <p>Drives the migrated job over each of the nine committed fixture trees under
     * {@code tests/fixtures/posting} with the REAL posting rules -- the production
     * {@code PostingValidationService} over the scenario's own account and cross-reference rows, and
     * the production {@code CategoryBalanceService} over its own seeded balances -- and compares
     * everything the pass produced against that scenario's committed expectation tree under
     * {@code tests/golden/posting}: the reject stream it staged, the posted master row it wrote, the
     * account master it left behind and the category balance it accumulated, byte for byte, plus the
     * tier in both the forms the orchestrator reads.</p>
     *
     * <p>Refactoring Rationale: the parity claim for these nine trees was made by the case above,
     * which reads two committed files and correlates them with each other. That is a real check of the
     * ORACLE -- it catches an expectation tree whose return code and reject stream disagree -- but it
     * never launches the job, so it could not have failed for anything the migrated code did. Nine
     * trees holding four expectation files each were being read as one number and one file size. This
     * case runs the job and compares all four, which is what makes the trees an oracle for this module
     * rather than a fixture inventory.</p>
     *
     * <p>Assumptions: the fixtures are read from the parity oracle's own tree rather than copied into
     * this module's resources, and they are opened READ-ONLY. The module's fixture tree deliberately
     * holds only the files each unit case needs -- section 4 of
     * {@code services/batch-service/src/test/resources/fixtures/README.md} records that per-scenario
     * subsetting -- so five of the nine scenarios are missing at least one of the four files a whole
     * pass needs. Copying the absent ones in would duplicate reference bytes that already exist, and a
     * comparison against a private copy of an oracle is a comparison with itself.</p>
     *
     * <p>Assumptions: ONE span of one record is masked, the posted row's processing timestamp, and
     * nothing else is normalised anywhere. That field is the pass's single genuine clock read, and the
     * committed expectation already carries it blanked because
     * {@code tests/helpers/golden_compare.py} masks it in place -- so the mask makes the two sides
     * comparable without hiding a difference either side could otherwise show. The ORIGINATING stamp
     * beside it is compared as it stands, which is what proves the passthrough at
     * {@code app/cbl/CBTRN02C.cbl:436}; masking both would have let a job that stamped the feed value
     * from its own clock pass.</p>
     *
     * <p>Assumptions: the two padding regimes the expectation trees carry are BOTH exercised without
     * either being special-cased here. Eight scenarios seed a category row, so their committed
     * balance carries the ASCII zeros a read-then-rewrite preserves, and the encode is handed the
     * seed image; {@code zero_balance} seeds none, so its committed balance carries the low values a
     * freshly created record leaves, and the encode is handed no image. The choice is made from
     * whether the key was seeded, which is the same fact the production create-versus-update branch
     * turns on, so a run that took the wrong arm would fail on the pad as well as on the balance.</p>
     *
     * @param scenario the committed tree's directory name, shared by the fixture and expectation roots
     * @param expectedTier the tier this scenario's committed return code resolves to
     * @throws Exception if the framework's own execution path raises, or a committed file cannot be
     *     read from the repository tree
     */
    @ParameterizedTest(name = "the {0} expectation is reproduced")
    @MethodSource("committedPostingScenarios")
    @DisplayName("reproduce every committed posting scenario's four outputs and its tier")
    void theCommittedPostingScenarioIsReproduced(String scenario, BatchReturnCode expectedTier)
            throws Exception {

        // WHY : Assumptions: this case drives the PARITY ORACLE's own fixture tree, so the input side
        //       of the comparison is the reference's rather than this module's copy of it, and it keeps
        //       the framework's own transaction manager it has always used -- the boundary is not what
        //       this case asserts, and the two managers are what the sibling boundary cases separate.
        PostingRun pass = runPostingScenario(scenario, PostingFixtureSource.REFERENCE_ORACLE,
                new ResourcelessTransactionManager());

        assertThat(pass.execution().getStatus())
                .as("the %s pass completed", scenario)
                .isEqualTo(BatchStatus.COMPLETED);

        BatchReturnCode committed = BatchReturnCode.fromNumericValue(Integer.parseInt(
                Files.readString(oracleFile("golden", scenario, RETURN_CODE_FILE)).trim()));
        assertThat(committed)
                .as("the committed return code of scenario %s", scenario)
                .isEqualTo(expectedTier);

        // WHY : Assumptions: the tier is read off the EXIT STATUS rather than off a returned value,
        //       because that is the form the orchestrator's Choice state reads and it is the only form
        //       a launched run exposes. The equality is asserted in both directions -- warned exactly
        //       when the committed code is the warn tier -- so a pass that warned on a clean scenario
        //       fails as loudly as one that stayed silent on a rejecting scenario.
        boolean warned = BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS
                .equals(pass.execution().getExitStatus().getExitCode());
        assertThat(warned)
                .as("scenario %s warns exactly when its committed code is the warn tier", scenario)
                .isEqualTo(committed == BatchReturnCode.SOFT_WARN);

        assertRejectStreamMatches(scenario, pass.rejectStream());
        assertPostedRecordsMatch(scenario, pass.savedLedgerRows());
        assertAccountImagesMatch(scenario, pass.accountsById());
        assertCategoryBalanceImagesMatch(scenario, pass);
    }

    /**
     * The walk crosses the commit interval, visiting every feed record exactly once.
     *
     * <p>Pins the continuation of the keyset walk at
     * {@code PostTransactionsJob.postEveryFeedRecord}, which reads
     * {@code BatchConfig.CHUNK_SIZE} rows at a time and advances its cursor to the last ordinal it
     * saw. One record MORE than the interval is staged, so the walk has to make a second read that
     * returns a non-empty page and a third that returns nothing before it stops.</p>
     *
     * <p>Refactoring Rationale: every other case in this class stages a feed smaller than the commit
     * interval, so the loop's continuation and its cursor arithmetic were reached by no case at all --
     * a walk that reset its cursor to zero would have looped forever on a real feed and passed every
     * case here, and one that advanced past the page's last row would have silently dropped a record.
     * The two failures are opposite and both are invisible below the interval, which is why the
     * boundary is crossed here rather than assumed.</p>
     *
     * <p>Assumptions: the cursor positions the walk asks from are captured and asserted as an ORDERED
     * list, not merely counted. The sequence is the property: it must open before the first ordinal,
     * resume from the last ordinal of the first page and then from the last ordinal of the second, and
     * a count alone would accept a walk that asked from zero three times.</p>
     *
     * <p>Assumptions: the identifiers of the posted rows are collected and compared against the
     * identifiers of the staged feed, so a record processed twice and a record never processed are
     * distinguishable failures rather than one wrong total. The feed builder derives each identifier
     * from the record's own ordinal, which is what makes that comparison possible.</p>
     *
     * <p>Assumptions: no flush-and-clear pair is asserted here, and the absence is deliberate rather
     * than an omission. A per-record transaction IS its own persistence context, so nothing
     * accumulates across records for a page-boundary clear to discard; the inline note at the foot of
     * this case records the two verifications that stood here and why they were withdrawn with the
     * collaborator they observed. What remains asserted is the WALK itself -- the cursors each page
     * resumed from, the sizes of the three pages, and one posting per staged record in feed order.</p>
     *
     * @throws Exception if the framework's own execution path raises, which this case does not provoke
     */
    @Test
    @DisplayName("cross the commit interval, visiting every feed record exactly once")
    void theWalkCrossesTheCommitIntervalVisitingEveryRecordExactlyOnce() throws Exception {
        int staged = BatchConfig.CHUNK_SIZE + 1;
        List<DailyTransaction> feedRows = new ArrayList<>();
        for (long ordinal = 1; ordinal <= staged; ordinal++) {
            feedRows.add(resolvableRecord(new BigDecimal("1.00"), ordinal));
        }

        List<Long> resumedFrom = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        when(this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(anyLong(), any(Limit.class)))
                .thenAnswer(call -> {
                    long cursor = call.<Long>getArgument(0);
                    Limit limit = call.getArgument(1);
                    resumedFrom.add(cursor);
                    List<DailyTransaction> page = feedRows.stream()
                            .filter(row -> row.getIngestSeq() > cursor)
                            .limit(limit.max())
                            .toList();
                    pageSizes.add(page.size());
                    return page;
                });
        stageAcceptedDecision(new BigDecimal("1.00"));
        when(this.categoryBalances.accumulatePostedTransaction(any(), any())).thenReturn(
                new CategoryBalanceService.Outcome(CategoryBalanceService.Arm.CREATED,
                        new BigDecimal("1.00")));

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(resumedFrom)
                .as("the walk opens before the first ordinal and then resumes from each page's last")
                .containsExactly(0L, (long) BatchConfig.CHUNK_SIZE, (long) staged);
        assertThat(pageSizes)
                .as("a full page, a short page and the empty page that ends the walk")
                .containsExactly(BatchConfig.CHUNK_SIZE, 1, 0);

        ArgumentCaptor<Transaction> posted = ArgumentCaptor.forClass(Transaction.class);
        verify(this.ledger, times(staged)).save(posted.capture());
        assertThat(posted.getAllValues())
                .extracting(Transaction::getTransactionId)
                .as("every staged record is posted exactly once, in feed order")
                .containsExactlyElementsOf(feedRows.stream()
                        .map(DailyTransaction::getTransactionId)
                        .toList());

        // WHY : Refactoring Rationale: two verifications stood here, that the pass flushed and cleared
        //       the persistence context once per page. They are withdrawn with the collaborator itself:
        //       a per-record transaction IS its own persistence context, created at the record's begin
        //       and released at its commit, so nothing accumulates across records for a clear to
        //       discard -- and a flush outside a transaction, which the tasklet body now runs in, is
        //       refused by a shared EntityManager proxy. What this case exists to pin is the WALK, and
        //       the assertions above pin it: the cursors the pages resumed from, the page sizes, and
        //       one posting per staged record in feed order.
        assertThat(loggedLines())
                .as("the processed counter reports every record the two pages carried")
                .anySatisfy(line -> assertThat(line).isEqualTo(String.format("%s%09d",
                        PostTransactionsJob.PROCESSED_LABEL, staged)));
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
     * <p>Refactoring Rationale: the walk itself moved to {@link #oracleTree(String)} when the fixture
     * trees became readable too, because one literal search written twice is the shape a reader has to
     * check twice. This method is kept as the name the expectation cases already read by.</p>
     *
     * @return the directory holding the nine posting expectation trees, never {@code null}
     * @throws IllegalStateException if no ancestor of the working directory holds that directory
     */
    private static Path goldenPostingRoot() {
        return oracleTree(GOLDEN_POSTING);
    }

    /**
     * Locates one of the parity oracle's posting trees by walking up from the module directory.
     *
     * <p>Assumptions: an absent tree raises rather than skipping the cases that read it, for the same
     * reason the expectation locator gives: both trees are committed, permanent artifacts of this
     * repository, so their absence means the checkout is incomplete and a skip would report a green
     * run that compared nothing.</p>
     *
     * @param relative the repository-relative path of the tree wanted, being either the fixture root or
     *     the expectation root; must not be {@code null}
     * @return that directory, never {@code null}
     * @throws IllegalStateException if no ancestor of the working directory holds it
     */
    private static Path oracleTree(String relative) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path tree = candidate.resolve(relative);
            if (Files.isDirectory(tree)) {
                return tree;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("no ancestor of " + Path.of("").toAbsolutePath()
                + " holds " + relative + "; the parity oracle's trees are"
                + " committed artifacts and a checkout without them is incomplete");
    }

    /**
     * Locates one committed fixture or expectation file of one posting scenario.
     *
     * <p>Assumptions: the file is opened READ-ONLY and this class holds no path that writes one, which
     * is the same asymmetry the case above records: the oracle's expectations describe the reference,
     * so a switch here would rewrite them to match whatever the migrated code currently produces.</p>
     *
     * @param tree either {@code fixtures} or {@code golden}; must name one of the two oracle trees
     * @param scenario the committed scenario directory inside the posting domain
     * @param file the file name inside that directory
     * @return the path of that file, asserted to exist; never {@code null}
     */
    private static Path oracleFile(String tree, String scenario, String file) {
        Path root = "golden".equals(tree) ? goldenPostingRoot() : oracleTree(FIXTURE_POSTING);
        Path candidate = root.resolve(scenario).resolve(file);
        assertThat(candidate)
                .as("reference-only %s artifact for posting scenario %s", tree, scenario)
                .isRegularFile();
        return candidate;
    }

    /**
     * Reads one committed file as raw fixed-width record BYTES.
     *
     * <p>Assumptions: the bytes are read rather than decoded to text, because a posted record carries
     * low values in its trailing pad and a category balance carries either those or ASCII zeros, and
     * the comparison is over bytes. The committed files store one record per line, so the terminator
     * is what the split is performed on and the declared width is then asserted -- which is also the
     * proof that no record itself contained a line feed.</p>
     *
     * @param tree either {@code fixtures} or {@code golden}
     * @param scenario the committed scenario directory inside the posting domain
     * @param file the file name inside that directory
     * @param reclen the declared record length every extracted record must have
     * @return the records in file order, empty for an empty file; never {@code null}
     * @throws IOException if the file cannot be read from the repository tree
     */
    private static List<byte[]> oracleRecords(String tree, String scenario, String file, int reclen)
            throws IOException {

        byte[] raw = Files.readAllBytes(oracleFile(tree, scenario, file));
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
                .as("every committed record of %s/%s/%s is exactly %d bytes", tree, scenario, file,
                        reclen)
                .allSatisfy(record -> assertThat(record).hasSize(reclen));
        return records;
    }

    /**
     * Splits a staged payload into the fixed-width records it concatenates.
     *
     * <p>Assumptions: the payload holds record after record with NO delimiter between them, which is
     * what a sequential fixed-length dataset is. The committed expectation stores one record per line
     * only so that a reader can inspect it, and that terminator belongs to no record.</p>
     *
     * @param payload the concatenated bytes the run staged; must not be {@code null}
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
     * Blanks the run-generated processing-timestamp span of one record, leaving every other byte.
     *
     * <p>Assumptions: the span is taken from the LAYOUT's own normalisable flag rather than from an
     * offset written here, so the one field the registry marks run-generated is the one field masked.
     * For the posted master that is {@code TRAN-PROC-TS} alone; {@code TRAN-ORIG-TS} sits beside it
     * unflagged and is therefore compared as it stands, which is what proves the passthrough.</p>
     *
     * @param image one fixed-width record image, copied rather than modified in place
     * @param layoutName the registry name whose flags select the spans to blank
     * @return the copy with only the run-generated stamp blanked, never {@code null}
     */
    private static byte[] withProcessingStampMasked(byte[] image, String layoutName) {
        byte[] masked = image.clone();
        for (CopybookLayout.FieldSpec field : CopybookLayout.layout(layoutName).fields()) {
            if (field.normalizeTs()) {
                Arrays.fill(masked, field.start(), field.end(), BLANK);
            }
        }
        return masked;
    }

    /**
     * Reports the declared record length of one registered layout.
     *
     * @param layoutName the registry name whose declared length is wanted; must be registered
     * @return that layout's record length in bytes
     */
    private static int reclenOf(String layoutName) {
        return CopybookLayout.layout(layoutName).reclen();
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
     * One completed pass over one committed scenario, with everything it produced or left behind.
     *
     * <p>Assumptions: the staged reject bytes are captured DURING the staging call rather than read
     * back afterwards, because the job deletes its temporary file on every path including success -- so
     * a read afterwards would find nothing at all and an empty comparison would pass.</p>
     *
     * <p>Refactoring Rationale: this record carries the union of what two separate result shapes
     * carried, because two harnesses that built the same graph were consolidated into
     * {@link #runPostingScenario}. The list components report WRITE ORDER and are what an assertion
     * derived from "did the reference write this master at all" reads; the map components report the
     * END STATE and are what a whole-image comparison reads. Both are kept because they answer
     * different questions about the same pass: an empty list proves the pass left a master untouched,
     * which a map holding the seeded row cannot distinguish from a rewrite of the same bytes.</p>
     *
     * @param execution the finished execution, whose status and exit status carry the tier
     * @param tier the return code the pass reported through the step ledger, which is the value the
     *     ledger's own outcome carries and the exit status is then derived from
     * @param rejectStream the concatenated 430-byte reject records the pass staged, with no delimiter
     *     between them
     * @param savedLedgerRows the ledger rows the pass wrote, in the order it wrote them
     * @param savedRejectRows the queryable reject rows the pass wrote, in write order
     * @param savedAccounts the account rows the pass wrote, in write order, empty when it wrote none
     * @param savedBalances the category-balance rows the pass wrote, in write order, empty when it
     *     wrote none
     * @param openingLookups one entry per category-balance opening read, {@code true} where a row
     *     already existed, which is what distinguishes the create arm from the update arm
     * @param accountsById the account rows the pass was driven over, whose state it mutated in place
     * @param balancesByKey the category balances after the pass, being the seeded rows as mutated plus
     *     any the pass created
     * @param seedImages the committed image of each category balance the scenario SEEDED, keyed the
     *     same way, so an update-arm row can be re-encoded over the bytes it was read from
     */
    private record PostingRun(JobExecution execution, BatchReturnCode tier, byte[] rejectStream,
            List<Transaction> savedLedgerRows, List<TransactionReject> savedRejectRows,
            List<Account> savedAccounts, List<TransactionCategoryBalance> savedBalances,
            List<Boolean> openingLookups, Map<Long, Account> accountsById,
            Map<TransactionCategoryBalanceId, TransactionCategoryBalance> balancesByKey,
            Map<TransactionCategoryBalanceId, byte[]> seedImages) {
    }

    /**
     * Where one scenario's four input images are read from.
     *
     * <p>Assumptions: the two sources hold the same bytes today and are still two independent
     * declarations. {@link #REFERENCE_ORACLE} reads the parity oracle's own fixture tree under
     * {@code tests/fixtures/posting}, which is reference-only and is never written by this module;
     * {@link #MODULE_CLASSPATH} reads this module's committed copy under
     * {@code src/test/resources/fixtures/posting}, whose contract and per-scenario census
     * {@code BatchFixtureContractTest} pins separately. Driving both is what keeps a divergence
     * between the copy and the reference observable: a run over one source alone would pass while the
     * other drifted.</p>
     *
     * <p>Alternatives Considered: collapsing the two onto the reference tree, which would have made
     * the consolidated runner take one argument fewer. Rejected because the module tree is what this
     * module ships and what a checkout without the oracle can still exercise, so dropping it would
     * narrow the claim these cases make rather than merely simplify how it is made.</p>
     */
    private enum PostingFixtureSource {

        /** The parity oracle's committed fixture tree, read from the repository working tree. */
        REFERENCE_ORACLE {
            /**
             * Reads the image from the reference tree, asserting the declared width as it splits.
             *
             * @param scenario the scenario directory under {@code tests/fixtures/posting}; must not be
             *     {@code null}
             * @param file the image's file name inside that directory; must not be {@code null}
             * @param layoutName the registered layout whose record length every extracted record is
             *     asserted against; must not be {@code null}
             * @return one entry per record, in file order, never {@code null}
             * @throws IOException if the file cannot be read from the working tree
             */
            @Override
            List<byte[]> records(String scenario, String file, String layoutName) throws IOException {
                return oracleRecords("fixtures", scenario, file, reclenOf(layoutName));
            }
        },

        /** This module's committed fixture tree, read from the test classpath. */
        MODULE_CLASSPATH {
            /**
             * Reads the image from the test classpath, where the production decoder checks the width.
             *
             * @param scenario the scenario directory under this module's fixture root; must not be
             *     {@code null}
             * @param file the image's file name inside that directory; must not be {@code null}
             * @param layoutName ignored on this arm, because the classpath reader splits on the line
             *     feed alone and the production decoder is what refuses a record of the wrong width
             * @return one entry per record, in file order, never {@code null}
             */
            @Override
            List<byte[]> records(String scenario, String file, String layoutName) {
                return fixtureRecords(scenario, file);
            }
        };

        /**
         * Reads every record of one committed input image of one scenario.
         *
         * @param scenario the scenario directory name, shared by both trees; must not be {@code null}
         * @param file the image's file name inside that directory; must not be {@code null}
         * @param layoutName the registered layout whose declared record length applies, used by the
         *     tree that asserts the width as it reads and ignored by the tree that derives it from the
         *     production decoder; must not be {@code null}
         * @return one entry per record, in file order, empty for an empty image, never {@code null}
         * @throws IOException if the image cannot be read from the tree
         */
        abstract List<byte[]> records(String scenario, String file, String layoutName)
                throws IOException;
    }

    /**
     * Runs the job once over the REAL posting rules, driven by one committed fixture tree.
     *
     * <p>Assumptions: the five repositories are the only doubles, so every rule between the walk and
     * the data is the production one -- the cross-reference read, the account read and the guard
     * between them, both inclusive boundaries, the reject composition, the balance accumulation with
     * its create-versus-update branch and the two timestamps of a posted row. That is what lets the
     * calling case assert BYTES against the committed expectations rather than call counts.</p>
     *
     * <p>Assumptions: the doubles answer from the decoded fixture rather than from a stubbed return, so
     * a read of a key the fixture does not hold answers EMPTY the way the real repository would. That
     * is what makes the four reject scenarios reachable: reason 100 needs the card read to miss and
     * reason 101 needs the account read to miss, and neither is arranged here -- both fall out of the
     * fixture the reference itself was driven over.</p>
     *
     * <p>Assumptions: the repositories are backed by MAPS rather than by stubs returning fixed
     * answers, because the pass reads a row it has just written -- the category balance is read for its
     * opening value and written back -- and a fixed answer would let a create arm masquerade as an
     * update. The account map holds the SAME instances the walk mutates and the balance map is written
     * back by the saving double, so the state compared afterwards is the state the pass left rather
     * than a copy taken before it; a double that echoed its argument without storing it would leave a
     * created category row invisible to the comparison.</p>
     *
     * <p>Assumptions: every write is ALSO appended to a list in call order, beside the map that holds
     * the end state. The lists are what let an assertion say "the reference wrote this master exactly
     * once" or "left it untouched", which a map alone cannot express, and the presence of each opening
     * category read is recorded for the same reason: both arms end in a save of the same type, so a
     * caller reading only the saves cannot tell them apart.</p>
     *
     * <p>Assumptions: the feed answer honours the ordinal and the limit it is given rather than
     * returning everything once, and the ordinals are assigned 1..N in committed file order because
     * the fixture is a flat file with no ordinal column while the walk is keyed on one. Section 3.9 of
     * {@code services/batch-service/src/test/resources/fixtures/README.md} makes file order the feed's
     * order, so numbering by position reproduces what the loader would have assigned; an answer that
     * ignored the ordinal would either loop forever or hide a walk that failed to advance.</p>
     *
     * <p>Assumptions: the four record images are decoded with the PRODUCTION mappers, so the offsets
     * this arrangement depends on are the offsets the pass depends on. Section 5.3 of the fixture
     * contract records the two-source cross-check behind those offsets, and a reader written here
     * would be a second place they could drift.</p>
     *
     * <p>Refactoring Rationale: this is ONE runner where two stood -- {@code runRealPosting}, which
     * drove the reference oracle's fixtures through {@code ResourcelessTransactionManager}, and
     * {@code runOverFixtures}, which drove this module's committed copy through
     * {@code DistinctBoundaryTransactionManager} and returned a second result shape. The two built the
     * same repository and service graph twice, with the same six doubles, the same real
     * {@code PostingValidationService}, {@code CategoryBalanceService} and
     * {@code DailyFeedWatermarkService}, and the same fixed clock -- so a change to the job's
     * collaborators had to be made in two places, and each observed only the subset of the pass its
     * own result record happened to expose. The two axes they genuinely differed on are the two
     * parameters below; everything else was duplication, and one arrangement observing everything is
     * what lets both callers keep every assertion they already made.</p>
     *
     * <p>Alternatives Considered: keeping two runners and extracting only the shared graph into a
     * helper. Rejected because the two result shapes were the actual maintenance cost -- each caller's
     * assertions were written against whichever fields its own harness exposed, so a claim provable
     * from one pass could not be made from the other without adding a field to one record and leaving
     * the other behind.</p>
     *
     * @param scenario the committed scenario directory name shared by the fixture and expectation
     *     roots; must name one of the nine
     * @param source the tree the four input images are read from; must not be {@code null}
     * @param transactionManager the manager the job opens its per-record boundary through, which the
     *     caller supplies because the two doubles in this class observe that boundary differently;
     *     must not be {@code null}
     * @return the finished pass and everything it produced, never {@code null}
     * @throws Exception if the framework's own execution path raises, or a committed fixture cannot be
     *     read from the tree it was asked of
     */
    private PostingRun runPostingScenario(String scenario, PostingFixtureSource source,
            PlatformTransactionManager transactionManager) throws Exception {

        Map<Long, Account> accountsById = new LinkedHashMap<>();
        for (byte[] image : source.records(scenario, ACCOUNT_FIXTURE, ACCOUNT_LAYOUT)) {
            Account seeded = AccountRecordMapper.toEntity(image);
            accountsById.put(seeded.getAccountId(), seeded);
        }

        Map<String, CardXref> crossReferencesByCard = new LinkedHashMap<>();
        CardXrefRecordMapper crossReferenceMapper = new CardXrefRecordMapper();
        for (byte[] image : source.records(scenario, CROSS_REFERENCE_FIXTURE,
                CROSS_REFERENCE_LAYOUT)) {
            CardXref seeded = crossReferenceMapper.toEntity(image);
            crossReferencesByCard.put(seeded.getCardNum(), seeded);
        }

        List<DailyTransaction> feedRows = new ArrayList<>();
        long ordinal = FIRST_ORDINAL - 1L;
        for (byte[] image : source.records(scenario, FEED_FIXTURE, FEED_LAYOUT)) {
            DailyTransaction feedRecord = DailyTransactionMapper.toEntity(image);
            assignIngestSeq(feedRecord, ++ordinal);
            feedRows.add(feedRecord);
        }

        Map<TransactionCategoryBalanceId, TransactionCategoryBalance> balancesByKey =
                new LinkedHashMap<>();
        Map<TransactionCategoryBalanceId, byte[]> seedImages = new LinkedHashMap<>();
        for (byte[] image : source.records(scenario, CATEGORY_BALANCE_FIXTURE,
                CATEGORY_BALANCE_LAYOUT)) {
            TransactionCategoryBalance seeded =
                    TransactionCategoryBalanceRecordMapper.toEntity(image);
            balancesByKey.put(seeded.getId(), seeded);
            seedImages.put(seeded.getId(), image);
        }

        DailyTransactionRepository feedRepository = mock(DailyTransactionRepository.class);
        when(feedRepository.findByIngestSeqGreaterThanOrderByIngestSeqAsc(anyLong(),
                any(Limit.class))).thenAnswer(call -> {
                    long cursor = call.<Long>getArgument(0);
                    Limit limit = call.getArgument(1);
                    return feedRows.stream()
                            .filter(row -> row.getIngestSeq() > cursor)
                            .limit(limit.max())
                            .toList();
                });

        List<Account> savedAccounts = new ArrayList<>();
        AccountRepository accountRepository = mock(AccountRepository.class);
        when(accountRepository.findByAccountId(anyLong())).thenAnswer(
                call -> Optional.ofNullable(accountsById.get(call.<Long>getArgument(0))));
        when(accountRepository.save(any(Account.class))).thenAnswer(call -> {
            Account written = call.getArgument(0);
            savedAccounts.add(written);
            accountsById.put(written.getAccountId(), written);
            return written;
        });

        CardXrefRepository crossReferenceRepository = mock(CardXrefRepository.class);
        when(crossReferenceRepository.findByCardNum(anyString())).thenAnswer(
                call -> Optional.ofNullable(crossReferencesByCard.get(call.<String>getArgument(0))));

        List<TransactionCategoryBalance> savedBalances = new ArrayList<>();
        List<Boolean> openingLookups = new ArrayList<>();
        TransactionCategoryBalanceRepository balanceRepository =
                mock(TransactionCategoryBalanceRepository.class);
        when(balanceRepository.findByIdIs(any(TransactionCategoryBalanceId.class)))
                .thenAnswer(call -> {
                    Optional<TransactionCategoryBalance> found = Optional.ofNullable(
                            balancesByKey.get(call.<TransactionCategoryBalanceId>getArgument(0)));
                    // WHY : Assumptions: the PRESENCE of each opening read is recorded, not just its
                    //       value, because it is the only place the create-versus-update arm becomes
                    //       observable from outside the service -- both arms end in a save of the same
                    //       type, so a caller reading only the saves cannot tell them apart.
                    openingLookups.add(found.isPresent());
                    return found;
                });
        when(balanceRepository.save(any(TransactionCategoryBalance.class))).thenAnswer(call -> {
            TransactionCategoryBalance written = call.getArgument(0);
            savedBalances.add(written);
            balancesByKey.put(written.getId(), written);
            return written;
        });

        List<Transaction> posted = new ArrayList<>();
        TransactionRepository ledgerRepository = mock(TransactionRepository.class);
        when(ledgerRepository.save(any(Transaction.class))).thenAnswer(call -> {
            Transaction written = call.getArgument(0);
            posted.add(written);
            return written;
        });

        List<TransactionReject> rejectRows = new ArrayList<>();
        TransactionRejectRepository rejectRepository = mock(TransactionRejectRepository.class);
        when(rejectRepository.save(any(TransactionReject.class))).thenAnswer(call -> {
            TransactionReject written = call.getArgument(0);
            rejectRows.add(written);
            return written;
        });

        // WHY : Assumptions: the captured payload starts as an EMPTY array rather than as null, so a
        //       run that staged nothing at all is compared as an empty stream instead of failing on a
        //       null before the comparison is reached. That the job always stages -- an empty
        //       generation on a clean pass included -- is asserted separately by
        //       aCleanPassStillStagesAnEmptyRejectGeneration, so the default stands in for nothing
        //       that any scenario here reaches.
        byte[][] staged = {new byte[0]};
        DatasetGenerationService allocator = mock(DatasetGenerationService.class);
        // WHY : Assumptions: the allocated generation echoes the business date the job PASSED rather
        //       than a date composed here, so a job that allocated a generation for some other day
        //       would show up as a mismatched coordinate instead of being normalised away by the
        //       double.
        when(allocator.allocateNewGeneration(any(DatasetFamily.class), any(BusinessDate.class),
                anyString())).thenAnswer(call -> new DatasetGeneration(
                        call.getArgument(0), call.getArgument(1), 1));
        when(allocator.generationsToScratch(any(DatasetFamily.class))).thenReturn(List.of());
        when(allocator.datasetUri(any(DatasetGeneration.class)))
                .thenReturn("s3://carddemo-datasets-test/ledger/dalyrejs/");
        when(allocator.stageDataset(any(DatasetGeneration.class), anyString(), any(Path.class)))
                .thenAnswer(call -> {
                    staged[0] = Files.readAllBytes(call.<Path>getArgument(2));
                    return "ledger/dalyrejs/dt=2022-07-18/gen=0001/dalyrejs";
                });

        BatchReturnCode[] reported = {null};
        BatchStepLedger stepLedger = mock(BatchStepLedger.class);
        when(stepLedger.runStep(anyString(), anyString(), any(BatchJobName.class), any()))
                .thenAnswer(call -> {
                    reported[0] = call.<Supplier<BatchReturnCode>>getArgument(3).get();
                    return new BatchStepLedger.StepOutcome(reported[0], false);
                });

        Clock clock = Clock.fixed(POSTED_AT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        // WHY : Assumptions: the watermark service is REAL over an unstubbed table, which is the same
        //       arrangement the shared setup uses and for the same reason: an unstubbed table answers
        //       with no stored row, so the walk starts at the beginning exactly as the committed
        //       expectations were derived against, while the advance-only rule and the per-record
        //       checkpoint stay the real ones rather than a permissive double.
        DailyFeedWatermarkService watermark =
                new DailyFeedWatermarkService(mock(DailyFeedWatermarkRepository.class), clock);
        PostingRecordUnitOfWork realPerRecord = new PostingRecordUnitOfWork(accountRepository,
                ledgerRepository, rejectRepository,
                new PostingValidationService(crossReferenceRepository, accountRepository),
                new CategoryBalanceService(balanceRepository), watermark, clock);
        PostTransactionsJob realConfiguration = new PostTransactionsJob(feedRepository,
                allocator, stepLedger, watermark);

        JobRepository repository = new ResourcelessJobRepository();
        Job realJob = realConfiguration.postTransactions(
                repository, new ResourcelessTransactionManager(), sharedValidator(), realPerRecord);

        JobParameters parameters = new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER, BUSINESS_DATE, true)
                .addString(BatchConfig.RUN_ID_PARAMETER, RUN_ID, false)
                .toJobParameters();
        JobInstance instance = new JobInstance(INSTANCE_ID, PostTransactionsJob.JOB_NAME);
        JobExecution execution = new JobExecution(EXECUTION_ID, instance, parameters);
        repository.update(execution);
        realJob.execute(execution);

        assertThat(execution.getStatus())
                .as("the pass over the %s fixtures must complete", scenario)
                .isEqualTo(BatchStatus.COMPLETED);

        return new PostingRun(execution, reported[0], staged[0], posted, rejectRows, savedAccounts,
                savedBalances, openingLookups, accountsById, balancesByKey, seedImages);
    }

    /**
     * Builds the validator every job in this module is constructed with.
     *
     * <p>Assumptions: the shared configuration class supplies it rather than a validator assembled
     * here, so the real-rule pass observes the parameter rule production applies. A locally built
     * validator would be a second rule that could accept a launch the deployed job refuses.</p>
     *
     * @return the shared parameter validator, never {@code null}
     */
    private static JobParametersValidator sharedValidator() {
        return new BatchConfig().carddemoJobParametersValidator();
    }

    /**
     * Compares the staged reject stream against the committed one, record by record.
     *
     * <p>Assumptions: NOTHING is masked in a reject record, including the processing-timestamp span the
     * posted comparison does mask. {@code app/cbl/CBTRN02C.cbl:447} moves the whole feed record into
     * the reject as a GROUP move, so the span carries whatever the feed carried -- which the committed
     * fixtures leave blank because an unposted feed row has no processing stamp. Masking it here would
     * hide a job that stamped the copy from its own clock.</p>
     *
     * @param scenario the committed scenario being compared against
     * @param stagedRejects the concatenated bytes the pass staged; must not be {@code null}
     * @throws IOException if the committed expectation cannot be read from the repository tree
     */
    private static void assertRejectStreamMatches(String scenario, byte[] stagedRejects)
            throws IOException {

        List<byte[]> expected = oracleRecords("golden", scenario, REJECT_STREAM_FILE,
                REJECT_RECORD_BYTES);
        List<byte[]> actual = splitRecords(stagedRejects, REJECT_RECORD_BYTES);

        // WHY : Assumptions: the count is asserted before the contents, because a stream holding the
        //       wrong number of records would otherwise be reported as a byte difference in whichever
        //       record happened to align badly and the diff would point at a field rather than at the
        //       count. Five of the nine scenarios expect NO record, and the same equality is what
        //       makes that a real claim rather than an empty loop.
        assertThat(actual)
                .as("the reject stream of scenario %s holds one record per rejected feed record",
                        scenario)
                .hasSameSizeAs(expected);

        for (int index = 0; index < expected.size(); index++) {
            assertThat(actual.get(index))
                    .as("reject record %d of the %s expectation", index, scenario)
                    .isEqualTo(expected.get(index));
        }
    }

    /**
     * Compares the rows the pass posted against the committed transaction master.
     *
     * @param scenario the committed scenario being compared against
     * @param posted the ledger rows the pass wrote, in the order it wrote them
     * @throws IOException if the committed expectation cannot be read from the repository tree
     */
    private static void assertPostedRecordsMatch(String scenario, List<Transaction> posted)
            throws IOException {

        List<byte[]> expected = oracleRecords("golden", scenario, POSTED_MASTER_FILE,
                reclenOf(POSTED_MASTER_LAYOUT));

        assertThat(posted)
                .as("the posted master of scenario %s holds one record per accepted feed record",
                        scenario)
                .hasSameSizeAs(expected);

        for (int index = 0; index < expected.size(); index++) {
            // WHY : Assumptions: the row is encoded under the POSTED_MASTER layout by name rather than
            //       through the single-argument encode, because the two layouts differ in the pad they
            //       write behind the description -- blank for a posted row, low values for an accrual
            //       -- and naming the layout is what asserts this job's rows take the posting arm.
            byte[] encoded = TransactionRecordMapper.toRecord(posted.get(index),
                    TransactionRecordMapper.Layout.POSTED_MASTER);
            assertThat(withProcessingStampMasked(encoded, POSTED_MASTER_LAYOUT))
                    .as("posted record %d of the %s expectation", index, scenario)
                    .isEqualTo(withProcessingStampMasked(expected.get(index),
                            POSTED_MASTER_LAYOUT));
        }
    }

    /**
     * Compares every account the pass touched against the committed account master.
     *
     * <p>Assumptions: the comparison is over the whole 300-byte image rather than over the three money
     * fields the pass writes, which is only sound because the mapper round-trips a committed account
     * image byte for byte. Comparing the whole record is the stronger claim: it also says the pass
     * changed NOTHING else, and an account master whose zip code or group identifier had been rewritten
     * would fail here rather than in whatever later scenario first depended on it.</p>
     *
     * @param scenario the committed scenario being compared against
     * @param accountsById the account rows the pass was driven over, keyed by identifier
     * @throws IOException if the committed expectation cannot be read from the repository tree
     */
    private static void assertAccountImagesMatch(String scenario, Map<Long, Account> accountsById)
            throws IOException {

        List<byte[]> expected = oracleRecords("golden", scenario, ACCOUNT_MASTER_FILE,
                reclenOf(ACCOUNT_LAYOUT));

        assertThat(expected)
                .as("the committed account master of scenario %s holds records, so the comparison"
                        + " below cannot pass over an empty pair", scenario)
                .isNotEmpty()
                .hasSameSizeAs(accountsById.values());

        for (byte[] image : expected) {
            long accountId = AccountRecordMapper.toEntity(image).getAccountId();
            Account actual = accountsById.get(accountId);
            assertThat(actual)
                    .as("account %d of the %s expectation was driven by this pass", accountId,
                            scenario)
                    .isNotNull();
            assertThat(AccountRecordMapper.toRecord(actual))
                    .as("the account master image of account %d in scenario %s", accountId,
                            scenario)
                    .isEqualTo(image);
        }
    }

    /**
     * Compares every category balance the pass left behind against the committed one.
     *
     * <p>Assumptions: a row the scenario SEEDED is re-encoded over its own seed image and a row the
     * pass CREATED is encoded without one, because the two carry different pad bytes and the
     * expectation trees hold both. Measured across the nine: the eight seeded scenarios commit a pad of
     * twenty-two ASCII zeros, which is what the reference's read-then-rewrite preserves, and
     * {@code zero_balance} commits twenty-two low values, which is what a freshly created record area
     * leaves. Selecting on whether the key was seeded is the same fact the production
     * create-versus-update branch turns on, so a pass that took the wrong arm fails on the pad as well
     * as on the balance.</p>
     *
     * @param scenario the committed scenario being compared against
     * @param pass the finished pass, whose balance map and seed images are both read
     * @throws IOException if the committed expectation cannot be read from the repository tree
     */
    private static void assertCategoryBalanceImagesMatch(String scenario, PostingRun pass)
            throws IOException {

        List<byte[]> expected = oracleRecords("golden", scenario, CATEGORY_BALANCE_FILE,
                reclenOf(CATEGORY_BALANCE_LAYOUT));

        assertThat(expected)
                .as("the committed category balance of scenario %s holds records, so the comparison"
                        + " below cannot pass over an empty pair", scenario)
                .isNotEmpty()
                .hasSameSizeAs(pass.balancesByKey().values());

        for (byte[] image : expected) {
            TransactionCategoryBalanceId key =
                    TransactionCategoryBalanceRecordMapper.toEntity(image).getId();
            TransactionCategoryBalance actual = pass.balancesByKey().get(key);
            assertThat(actual)
                    .as("the category row %s of the %s expectation exists after the pass", key,
                            scenario)
                    .isNotNull();

            byte[] seedImage = pass.seedImages().get(key);
            byte[] encoded = seedImage == null
                    ? TransactionCategoryBalanceRecordMapper.toRecord(actual)
                    : TransactionCategoryBalanceRecordMapper.toRecord(actual, seedImage);
            assertThat(encoded)
                    .as("the category balance image of %s in scenario %s", key, scenario)
                    .isEqualTo(image);
        }
    }

    /**
     * A stored consumed position moves the walk's starting point, so nothing already posted is re-read.
     *
     * <p>Refactoring Rationale: this is the acceptance case for the defect the watermark closes. The
     * reference's feed is a flat dataset that {@code app/jcl/POSTTRAN.jcl:30-31} presents fresh each
     * run, so {@code app/cbl/CBTRN02C.cbl:202-219} reading it to end of file reads exactly one night.
     * The target's feed is a table that accumulates -- its rows are what the three verification passes
     * compare against -- so a walk from the first row re-posted every earlier night on every later
     * night, adding those amounts to account balances again. Nothing rejected and no return code
     * changed, so the only observable difference was in the balances themselves.</p>
     *
     * <p>Assumptions: the assertion is on the ORDINAL the feed was asked for, not on a count of rows
     * posted. The bound is what carries the property: a pass that asked from zero and happened to
     * receive one row would post correctly and still be wrong, because the rows it did not receive
     * are an artefact of the stub rather than of the cursor.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("start the walk above the stored consumed position")
    void aStoredWatermarkMovesTheWalkStart() throws Exception {
        long alreadyConsumed = 250L;
        when(this.watermarks.findByFeedName(DailyFeedWatermarkService.DAILY_TRANSACTION_FEED))
                .thenReturn(Optional.of(new DailyFeedWatermark(
                        DailyFeedWatermarkService.DAILY_TRANSACTION_FEED, alreadyConsumed,
                        "batch-run-0000", BUSINESS_DATE, POSTED_AT)));
        when(this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(anyLong(), any(Limit.class)))
                .thenReturn(List.of());

        run();

        ArgumentCaptor<Long> from = ArgumentCaptor.forClass(Long.class);
        verify(this.feed, times(1))
                .findByIngestSeqGreaterThanOrderByIngestSeqAsc(from.capture(), any(Limit.class));
        assertThat(from.getValue()).isEqualTo(alreadyConsumed);
    }

    /**
     * A pass that consumed rows advances the stored position to the last ordinal it walked.
     *
     * <p>Assumptions: the advance is asserted through the ENTITY the pass mutated rather than through a
     * save call, because the row already existed and the pass therefore moves a managed instance. A
     * verification of {@code save} would pass on an implementation that replaced the locked instance
     * and would fail on the correct one, so it would be asserting the mechanism instead of the
     * outcome.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("advance the stored consumed position to the last ordinal walked")
    void aConsumingPassAdvancesTheStoredPosition() throws Exception {
        DailyFeedWatermark stored = new DailyFeedWatermark(
                DailyFeedWatermarkService.DAILY_TRANSACTION_FEED, 0L, "batch-run-0000",
                BUSINESS_DATE, POSTED_AT);
        when(this.watermarks.findByFeedName(DailyFeedWatermarkService.DAILY_TRANSACTION_FEED))
                .thenReturn(Optional.of(stored));
        DailyTransaction feedRecord = resolvableRecord(new BigDecimal("100.00"));
        long walkedTo = feedRecord.getIngestSeq();
        stageOneRecord(feedRecord);
        stageAcceptedDecision(new BigDecimal("100.00"));

        run();

        assertThat(stored.getLastIngestSeq()).isEqualTo(walkedTo);
        assertThat(stored.getRunId()).isEqualTo(RUN_ID);
        assertThat(stored.getBusinessDate()).isEqualTo(BUSINESS_DATE);
    }

    /**
     * A pass over an empty feed leaves the stored position exactly as it found it.
     *
     * <p>Assumptions: an empty night must be a NO-OP on the watermark and not a write of the same
     * value, because a write would move the recorded run and business date onto a run that consumed
     * nothing -- and those two columns are what an operator reads to find out which run consumed a
     * range. The position itself would be unchanged either way, which is precisely why the run
     * identifier is the assertion that can tell the two apart.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("leave the stored position untouched over an empty feed")
    void anEmptyFeedLeavesTheStoredPositionUntouched() throws Exception {
        DailyFeedWatermark stored = new DailyFeedWatermark(
                DailyFeedWatermarkService.DAILY_TRANSACTION_FEED, 250L, "batch-run-0000",
                BUSINESS_DATE, POSTED_AT);
        when(this.watermarks.findByFeedName(DailyFeedWatermarkService.DAILY_TRANSACTION_FEED))
                .thenReturn(Optional.of(stored));
        when(this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(anyLong(), any(Limit.class)))
                .thenReturn(List.of());

        run();

        assertThat(stored.getLastIngestSeq()).isEqualTo(250L);
        assertThat(stored.getRunId()).isEqualTo("batch-run-0000");
        verify(this.watermarks, never()).save(any(DailyFeedWatermark.class));
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

    /**
     * Runs the real pass over one scenario's committed fixture images and reports what it produced.
     *
     * <p>Assumptions: the four record images are decoded with the PRODUCTION mappers, so the offsets
     * this arrangement depends on are the offsets the pass depends on. Section 5.3 of
     * {@code services/batch-service/src/test/resources/fixtures/README.md} records the two-source
     * cross-check behind those offsets, and a reader written here would be a second place they could
     * drift.</p>
     *
     * <p>Assumptions: the repositories are backed by MAPS rather than by stubs returning fixed answers,
     * because the pass reads a row it has just written -- the category balance is read for its opening
     * value and written back -- and a fixed answer would let a create arm masquerade as an update. The
     * maps are also what let the account the validation rule read be the same object the account write
     * mutates, which is the arrangement the production persistence context provides.</p>
     *
     * <p>Assumptions: the feed answer honours the ordinal and the limit it is given rather than
     * returning everything once. The pass walks the feed by keyset and stops when a read comes back
     * empty, so an answer that ignored the ordinal would either loop forever or hide a walk that failed
     * to advance.</p>
     *
     * @param scenario the scenario name, which is both a fixture directory and an expectation tree
     * @return everything the pass produced, for the assertions to compare against the trees, never
     *     {@code null}
     * @throws Exception if the framework's own execution path raises
     */
    private FixtureRun runOverFixtures(String scenario) throws Exception {
        List<DailyTransaction> feedRecords = new ArrayList<>();
        long ordinal = FIRST_ORDINAL;
        for (byte[] image : fixtureRecords(scenario, FEED_FIXTURE)) {
            DailyTransaction feedRecord = DailyTransactionMapper.toEntity(image);
            assignIngestSeq(feedRecord, ordinal++);
            feedRecords.add(feedRecord);
        }

        Map<Long, Account> accountsById = new LinkedHashMap<>();
        for (byte[] image : fixtureRecords(scenario, ACCOUNT_FIXTURE)) {
            Account account = AccountRecordMapper.toEntity(image);
            accountsById.put(account.getAccountId(), account);
        }

        CardXrefRecordMapper xrefMapper = new CardXrefRecordMapper();
        Map<String, CardXref> xrefsByCard = new LinkedHashMap<>();
        for (byte[] image : fixtureRecords(scenario, XREF_FIXTURE)) {
            CardXref xref = xrefMapper.toEntity(image);
            xrefsByCard.put(xref.getCardNum(), xref);
        }

        Map<TransactionCategoryBalanceId, TransactionCategoryBalance> balancesById =
                new LinkedHashMap<>();
        for (byte[] image : fixtureRecords(scenario, CATEGORY_BALANCE_FIXTURE)) {
            TransactionCategoryBalance balance =
                    TransactionCategoryBalanceRecordMapper.toEntity(image);
            balancesById.put(balance.getId(), balance);
        }

        DailyTransactionRepository feedRepository = mock(DailyTransactionRepository.class);
        when(feedRepository.findByIngestSeqGreaterThanOrderByIngestSeqAsc(
                anyLong(), any(Limit.class))).thenAnswer(call -> {
                    long after = call.getArgument(0);
                    int window = call.<Limit>getArgument(1).max();
                    return feedRecords.stream()
                            .filter(candidate -> candidate.getIngestSeq() > after)
                            .limit(window)
                            .toList();
                });

        CardXrefRepository xrefRepository = mock(CardXrefRepository.class);
        when(xrefRepository.findByCardNum(anyString())).thenAnswer(
                call -> Optional.ofNullable(xrefsByCard.get(call.<String>getArgument(0))));

        List<Account> savedAccounts = new ArrayList<>();
        AccountRepository accountRepository = mock(AccountRepository.class);
        when(accountRepository.findByAccountId(anyLong())).thenAnswer(
                call -> Optional.ofNullable(accountsById.get(call.<Long>getArgument(0))));
        when(accountRepository.save(any(Account.class))).thenAnswer(call -> {
            Account saved = call.getArgument(0);
            savedAccounts.add(saved);
            accountsById.put(saved.getAccountId(), saved);
            return saved;
        });

        List<TransactionCategoryBalance> savedBalances = new ArrayList<>();
        List<Boolean> openingLookups = new ArrayList<>();
        TransactionCategoryBalanceRepository balanceRepository =
                mock(TransactionCategoryBalanceRepository.class);
        when(balanceRepository.findByIdIs(any(TransactionCategoryBalanceId.class)))
                .thenAnswer(call -> {
                    Optional<TransactionCategoryBalance> found = Optional.ofNullable(
                            balancesById.get(call.<TransactionCategoryBalanceId>getArgument(0)));
                    // WHY : Assumptions: the PRESENCE of each opening read is recorded, not just its
                    //       value, because it is the only place the create-versus-update arm becomes
                    //       observable from outside the service -- both arms end in a save of the same
                    //       type, so a caller reading only the saves cannot tell them apart.
                    openingLookups.add(found.isPresent());
                    return found;
                });
        when(balanceRepository.save(any(TransactionCategoryBalance.class))).thenAnswer(call -> {
            TransactionCategoryBalance saved = call.getArgument(0);
            savedBalances.add(saved);
            balancesById.put(saved.getId(), saved);
            return saved;
        });

        List<Transaction> savedLedgerRows = new ArrayList<>();
        TransactionRepository ledgerRepository = mock(TransactionRepository.class);
        when(ledgerRepository.save(any(Transaction.class))).thenAnswer(call -> {
            Transaction saved = call.getArgument(0);
            savedLedgerRows.add(saved);
            return saved;
        });

        List<TransactionReject> savedRejectRows = new ArrayList<>();
        TransactionRejectRepository rejectRepository = mock(TransactionRejectRepository.class);
        when(rejectRepository.save(any(TransactionReject.class))).thenAnswer(call -> {
            TransactionReject saved = call.getArgument(0);
            savedRejectRows.add(saved);
            return saved;
        });

        byte[][] staged = {new byte[0]};
        DatasetGenerationService stagedGenerations = mock(DatasetGenerationService.class);
        when(stagedGenerations.allocateNewGeneration(any(DatasetFamily.class),
                any(BusinessDate.class), anyString()))
                .thenAnswer(call -> new DatasetGeneration(
                        call.getArgument(0), new BusinessDate(BUSINESS_DATE), 1));
        when(stagedGenerations.generationsToScratch(any(DatasetFamily.class)))
                .thenReturn(List.of());
        when(stagedGenerations.datasetUri(any(DatasetGeneration.class)))
                .thenReturn("s3://carddemo-datasets-test/ledger/dalyrejs/");
        when(stagedGenerations.stageDataset(any(DatasetGeneration.class), anyString(),
                any(Path.class))).thenAnswer(call -> {
                    staged[0] = Files.readAllBytes(call.<Path>getArgument(2));
                    return "ledger/dalyrejs/dt=2022-07-18/gen=0001/dalyrejs";
                });

        BatchReturnCode[] reported = {null};
        BatchStepLedger steps = mock(BatchStepLedger.class);
        when(steps.runStep(anyString(), anyString(), any(BatchJobName.class), any()))
                .thenAnswer(call -> {
                    reported[0] = call.<Supplier<BatchReturnCode>>getArgument(3).get();
                    return new BatchStepLedger.StepOutcome(reported[0], false);
                });

        Clock fixtureClock = Clock.fixed(POSTED_AT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        // WHY : Assumptions: the watermark service is REAL over an unstubbed table, the same
        //       arrangement the oracle-driven harness and the shared setup use. An unstubbed table
        //       answers with no stored row, so the walk starts at the beginning -- which is what these
        //       expectation trees were derived against -- while the per-record checkpoint the job now
        //       writes goes through the real advance-only rule rather than a permissive double.
        DailyFeedWatermarkService fixtureWatermark = new DailyFeedWatermarkService(
                mock(DailyFeedWatermarkRepository.class), fixtureClock);
        PostTransactionsJob fixtureConfiguration = new PostTransactionsJob(feedRepository,
                stagedGenerations, steps, fixtureWatermark);

        this.configuration = fixtureConfiguration;
        this.perRecord = new PostingRecordUnitOfWork(accountRepository, ledgerRepository,
                rejectRepository, new PostingValidationService(xrefRepository, accountRepository),
                new CategoryBalanceService(balanceRepository), fixtureWatermark, fixtureClock);
        this.jobRepository = new ResourcelessJobRepository();
        this.job = buildJobOver(new DistinctBoundaryTransactionManager());

        JobExecution execution = run();
        assertThat(execution.getStatus())
                .as("the pass over the %s fixtures must complete", scenario)
                .isEqualTo(BatchStatus.COMPLETED);

        return new FixtureRun(reported[0], staged[0], savedAccounts, savedBalances, savedLedgerRows,
                savedRejectRows, openingLookups);
    }

    /**
     * Everything one fixture-driven pass produced, for comparison against the committed trees.
     *
     * @param tier the return code the pass reported
     * @param rejectStream the bytes the pass staged as the reject dataset, never {@code null}
     * @param savedAccounts the account rows the pass wrote, in write order, never {@code null}
     * @param savedBalances the category-balance rows the pass wrote, in write order, never
     *     {@code null}
     * @param savedLedgerRows the transaction rows the pass wrote, in write order, never {@code null}
     * @param savedRejectRows the reject rows the pass wrote, in write order, never {@code null}
     * @param openingLookups one entry per category-balance read, {@code true} where a row already
     *     existed, which is what distinguishes the create arm from the update arm, never {@code null}
     */
    private record FixtureRun(BatchReturnCode tier, byte[] rejectStream, List<Account> savedAccounts,
            List<TransactionCategoryBalance> savedBalances, List<Transaction> savedLedgerRows,
            List<TransactionReject> savedRejectRows, List<Boolean> openingLookups) {
    }

    /**
     * Asserts the staged reject stream against the scenario's committed reject expectation.
     *
     * <p>Assumptions: the payload is compared as BYTES rather than field by field, because
     * {@code app/cbl/CBTRN02C.cbl:447} moves the whole feed record across as a GROUP move -- no value is
     * re-encoded on the way -- so byte equality is the actual contract and a field comparison would be
     * a weaker restatement of it. The code, the description and the card span are then asserted
     * individually as well, because those three are what an operator reads off the record and a whole
     * record comparison reports a difference in any of them the same way.</p>
     *
     * @param scenario the scenario name
     * @param run what the pass produced; must not be {@code null}
     * @throws IOException if a committed expectation file cannot be read
     */
    private static void assertRejectStreamReproduced(String scenario, PostingRun run)
            throws IOException {

        byte[] committed = expectationImage(scenario, REJECT_STREAM_FILE);
        if (committed.length == 0) {
            assertThat(run.rejectStream())
                    .as("%s expects no reject record at all", scenario).isEmpty();
            assertThat(run.savedRejectRows())
                    .as("%s expects no reject row either", scenario).isEmpty();
            return;
        }

        assertThat(committed)
                .as("the committed reject record of %s", scenario).hasSize(REJECT_RECORD_BYTES);
        assertThat(run.rejectStream())
                .as("the reject record the pass staged for %s", scenario)
                .hasSize(REJECT_RECORD_BYTES);
        assertThat(canonicalisedRecord(run.rejectStream(), FEED_LAYOUT))
                .as("the reject record the pass staged for %s, byte for byte", scenario)
                .isEqualTo(canonicalisedRecord(committed, FEED_LAYOUT));

        String produced = new String(run.rejectStream(), StandardCharsets.ISO_8859_1);
        String expected = new String(committed, StandardCharsets.ISO_8859_1);
        int descriptionEnd = REJECT_PAYLOAD_BYTES + REJECT_CODE_WIDTH + REJECT_DESC_WIDTH;
        CopybookLayout.FieldSpec card =
                CopybookLayout.layout(FEED_LAYOUT).field("DALYTRAN-CARD-NUM");

        assertThat(produced.substring(REJECT_PAYLOAD_BYTES,
                REJECT_PAYLOAD_BYTES + REJECT_CODE_WIDTH))
                .as("the four-character reason code of %s", scenario)
                .isEqualTo(expected.substring(REJECT_PAYLOAD_BYTES,
                        REJECT_PAYLOAD_BYTES + REJECT_CODE_WIDTH));
        assertThat(produced.substring(REJECT_PAYLOAD_BYTES + REJECT_CODE_WIDTH, descriptionEnd))
                .as("the verbatim reason description of %s", scenario)
                .isEqualTo(expected.substring(REJECT_PAYLOAD_BYTES + REJECT_CODE_WIDTH,
                        descriptionEnd));
        assertThat(produced.substring(card.start(), card.end()))
                .as("the card span the rejected payload carries for %s", scenario)
                .isEqualTo(expected.substring(card.start(), card.end()));

        assertThat(run.savedRejectRows())
                .as("%s expects exactly one queryable reject row", scenario).hasSize(1);
        assertThat(String.format("%0" + REJECT_CODE_WIDTH + "d",
                run.savedRejectRows().get(0).getReasonCode()))
                .as("the reason the queryable row carries for %s, against the wire form", scenario)
                .isEqualTo(expected.substring(REJECT_PAYLOAD_BYTES,
                        REJECT_PAYLOAD_BYTES + REJECT_CODE_WIDTH));
    }

    /**
     * Asserts the account master the pass wrote against the scenario's committed expectation.
     *
     * <p>Assumptions: whether a write is expected AT ALL is derived by comparing the committed
     * expectation against the committed input rather than declared per scenario. Where the two agree
     * the reference wrote nothing, so a pass that wrote the row unchanged would be a divergence this
     * comparison catches; where they differ the row was written and must match.</p>
     *
     * @param scenario the scenario name
     * @param run what the pass produced; must not be {@code null}
     * @throws IOException if a committed file cannot be read
     */
    private static void assertAccountMasterReproduced(String scenario, PostingRun run)
            throws IOException {

        byte[] committed = expectationImage(scenario, ACCOUNT_EXPECTATION);
        byte[] opening = fixtureImage(scenario, ACCOUNT_FIXTURE);
        assertPadRegionCarriesOnly(committed, ACCOUNT_LAYOUT, BLANK);

        byte[] expected = canonicalisedRecord(committed, ACCOUNT_LAYOUT);
        if (Arrays.equals(canonicalisedRecord(opening, ACCOUNT_LAYOUT), expected)) {
            assertThat(run.savedAccounts())
                    .as("%s expects the account master left untouched", scenario).isEmpty();
            return;
        }

        assertThat(run.savedAccounts())
                .as("%s expects exactly one account write", scenario).hasSize(1);
        Account posted = run.savedAccounts().get(0);
        assertThat(canonicalisedRecord(AccountRecordMapper.toRecord(posted), ACCOUNT_LAYOUT))
                .as("the account master the pass wrote for %s, byte for byte", scenario)
                .isEqualTo(expected);

        Account expectedAccount = AccountRecordMapper.toEntity(committed);
        assertThat(posted.getCurrBal())
                .as("the balance the pass accumulated for %s", scenario)
                .isEqualByComparingTo(expectedAccount.getCurrBal());
        assertThat(posted.getCurrCycCredit())
                .as("the cycle credit the pass accumulated for %s", scenario)
                .isEqualByComparingTo(expectedAccount.getCurrCycCredit());
        assertThat(posted.getCurrCycDebit())
                .as("the cycle debit the pass accumulated for %s", scenario)
                .isEqualByComparingTo(expectedAccount.getCurrCycDebit());
    }

    /**
     * Asserts the category balance the pass wrote, and which of the two arms produced it.
     *
     * <p>Assumptions: the ARM is derived from the scenario's own two files -- whether the committed
     * input already holds a row under the key the expectation carries -- and compared against what the
     * service's opening read actually found. Declaring the arm per scenario would state the answer this
     * case exists to check.</p>
     *
     * @param scenario the scenario name
     * @param run what the pass produced; must not be {@code null}
     * @throws IOException if a committed file cannot be read
     */
    private static void assertCategoryBalanceReproduced(String scenario, PostingRun run)
            throws IOException {

        List<byte[]> committed = expectationRecords(scenario, CATEGORY_BALANCE_EXPECTATION);
        List<byte[]> opening = fixtureRecords(scenario, CATEGORY_BALANCE_FIXTURE);
        assertThat(committed)
                .as("%s expects exactly one category balance", scenario).hasSize(1);

        int keyLength = CopybookLayout.layout(CATEGORY_BALANCE_LAYOUT).keyLength();
        byte[] expectedKey = Arrays.copyOf(committed.get(0), keyLength);
        boolean rowExistedBefore = opening.stream()
                .anyMatch(row -> Arrays.equals(Arrays.copyOf(row, keyLength), expectedKey));

        // WHY : Assumptions: the pad byte a TCATBAL expectation carries is DECIDED BY THE ARM that
        //       wrote the row, and the two are not the same byte. Measured across the nine committed
        //       trees: the eight whose input already holds this key carry 0x30 -- the seed row's own
        //       padding, which app/cbl/CBTRN02C.cbl:526-542 rewrites without touching -- while
        //       zero_balance alone carries 0x00, because its input holds no row and :503-524 CREATES
        //       one into a record area no MOVE reaches, exactly the mechanism section 6.1 of the
        //       fixture contract documents for the output TRAN pad. The byte is therefore derived from
        //       the arm rather than fixed, and section 6.2 of that contract carries the same
        //       measurement so a fixture author is not left to rediscover it from a failing diff.
        byte measuredPad = rowExistedBefore ? CATEGORY_BALANCE_REWRITE_PAD : LOW_VALUE;
        assertPadRegionCarriesOnly(committed.get(0), CATEGORY_BALANCE_LAYOUT, measuredPad);

        if (sameImages(committed, opening, CATEGORY_BALANCE_LAYOUT)) {
            assertThat(run.savedBalances())
                    .as("%s expects the category balances left untouched", scenario).isEmpty();
            return;
        }

        assertThat(run.savedBalances())
                .as("%s expects exactly one category-balance write", scenario).hasSize(1);
        assertThat(canonicalisedRecord(TransactionCategoryBalanceRecordMapper.toRecord(
                run.savedBalances().get(0)), CATEGORY_BALANCE_LAYOUT))
                .as("the category balance the pass wrote for %s, byte for byte", scenario)
                .isEqualTo(canonicalisedRecord(committed.get(0), CATEGORY_BALANCE_LAYOUT));

        assertThat(run.openingLookups())
                .as("%s expects one category-balance read, for the one accepted record", scenario)
                .containsExactly(rowExistedBefore);
    }

    /**
     * Asserts the transaction rows the pass wrote against the scenario's committed expectation.
     *
     * <p>Assumptions: the processing stamp is excluded from the byte comparison and asserted
     * separately as a value, because the expectation carries it BLANK by normalisation while the row
     * the pass wrote carries the injected clock reading. Section 8.1 of
     * {@code services/batch-service/src/test/resources/fixtures/README.md} fixes that asymmetry for the
     * posting domain, and it also fixes that the ORIGINATING stamp is not normalised -- so that field
     * stays inside the byte comparison and a pass that overwrote it with the clock would fail here.</p>
     *
     * @param scenario the scenario name
     * @param run what the pass produced; must not be {@code null}
     * @throws IOException if a committed file cannot be read
     */
    private static void assertLedgerRowReproduced(String scenario, PostingRun run)
            throws IOException {

        List<byte[]> committed = expectationRecords(scenario, LEDGER_EXPECTATION);
        if (committed.isEmpty()) {
            assertThat(run.savedLedgerRows())
                    .as("%s expects no posted transaction", scenario).isEmpty();
            return;
        }

        assertThat(run.savedLedgerRows())
                .as("the posted transactions of %s", scenario).hasSize(committed.size());
        for (int index = 0; index < committed.size(); index++) {
            assertThat(canonicalisedRecord(
                    TransactionRecordMapper.toRecord(run.savedLedgerRows().get(index)),
                    LEDGER_LAYOUT))
                    .as("posted transaction %d of %s, byte for byte", index + 1, scenario)
                    .isEqualTo(canonicalisedRecord(committed.get(index), LEDGER_LAYOUT));
            assertThat(run.savedLedgerRows().get(index).getProcTs())
                    .as("the processing stamp of posted transaction %d of %s comes from the clock",
                            index + 1, scenario)
                    .isEqualTo(POSTED_AT);
        }
    }

    /**
     * Canonicalises one record image so that only bytes both sides can agree about remain comparable.
     *
     * <p>Assumptions: three transformations are applied and no others, and every one is applied to BOTH
     * images so neither side is favoured. The low value is mapped to the blank, which reconciles the pad
     * regions the reference leaves untouched against the blank a fixed-width encode rebuilds a dropped
     * pad with. The span of every field the layout flags as a normalisable timestamp is blanked, which
     * for the posting layouts is the PROCESSING stamp only. And the declared {@code FILLER} span is
     * blanked, because section 6.2 of
     * {@code services/batch-service/src/test/resources/fixtures/README.md} measured three different
     * padding bytes across these records -- a space, a low value and an ASCII zero -- while
     * {@code FixedWidthCodec} has no record-specific padding knowledge and rebuilds every one of them
     * as a blank. Comparing that span would fail on padding that carries no data; leaving it
     * unasserted would drop a measured contract, which is why
     * {@link #assertPadRegionCarriesOnly} asserts it against the measured byte instead.</p>
     *
     * @param image one fixed-width record image, copied rather than modified in place
     * @param layoutName the registered layout whose timestamp and pad spans apply
     * @return the canonicalised copy, never {@code null}
     */
    private static byte[] canonicalisedRecord(byte[] image, String layoutName) {
        byte[] canonical = image.clone();
        for (int index = 0; index < canonical.length; index++) {
            if (canonical[index] == LOW_VALUE) {
                canonical[index] = BLANK;
            }
        }
        for (CopybookLayout.FieldSpec field : CopybookLayout.layout(layoutName).fields()) {
            if (field.normalizeTs() || "FILLER".equals(field.name())) {
                Arrays.fill(canonical, field.start(), field.end(), BLANK);
            }
        }
        return canonical;
    }

    /**
     * Asserts a committed image's pad region carries only the byte section 6.1 measured for it.
     *
     * <p>Assumptions: this is what keeps blanking the pad span in {@link #canonicalisedRecord} honest.
     * The padding byte differs per record and is a measured property of the corpus rather than a
     * consequence of the picture clause, so dropping it from the comparison without asserting it
     * anywhere would let a fixture or an expectation drift onto the general rule unnoticed -- which
     * section 6.2 records as the hardest class of failure to read off a diff, because every field the
     * record carries is still correct.</p>
     *
     * @param image one committed image, read and never modified
     * @param layoutName the registered layout whose {@code FILLER} span applies
     * @param measured the byte section 6.1 of the fixture contract measured for that record
     */
    private static void assertPadRegionCarriesOnly(byte[] image, String layoutName, byte measured) {
        CopybookLayout.FieldSpec pad = CopybookLayout.layout(layoutName).field("FILLER");
        byte[] padding = Arrays.copyOfRange(image, pad.start(), pad.end());
        byte[] uniform = new byte[padding.length];
        Arrays.fill(uniform, measured);
        assertThat(padding)
                .as("the %s pad region of a committed image, against the measured byte %d",
                        layoutName, measured)
                .isEqualTo(uniform);
    }

    /**
     * Reports whether two lists of record images agree after canonicalisation.
     *
     * @param left one list of images; must not be {@code null}
     * @param right the other list of images; must not be {@code null}
     * @param layoutName the registered layout the canonicalisation applies
     * @return {@code true} when the lists hold the same images in the same order
     */
    private static boolean sameImages(List<byte[]> left, List<byte[]> right, String layoutName) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!Arrays.equals(canonicalisedRecord(left.get(index), layoutName),
                    canonicalisedRecord(right.get(index), layoutName))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads every record of one committed fixture image from the test classpath.
     *
     * <p>Assumptions: the resource is read as BYTES and split on the line feed alone. Section 3.3 of the
     * fixture contract makes the low-order byte of every money field a sign overpunch, so a character
     * decode would be free to alter it; section 3.8 fixes the line ending as a bare line feed; and
     * section 3.11 makes a ZERO-BYTE file a legitimate input meaning "no records", which is why an
     * empty result is returned rather than raised.</p>
     *
     * @param scenario the scenario directory name under {@link #FIXTURE_POSTING_ROOT}
     * @param fileName the image's file name within that directory
     * @return one entry per record, in file order, never {@code null}
     */
    private static List<byte[]> fixtureRecords(String scenario, String fileName) {
        return splitRecords(fixtureImage(scenario, fileName));
    }

    /**
     * Reads one committed fixture image from the test classpath, with its line terminator removed.
     *
     * @param scenario the scenario directory name under {@link #FIXTURE_POSTING_ROOT}
     * @param fileName the image's file name within that directory
     * @return the image with any trailing carriage return or line feed removed, never {@code null}
     * @throws IllegalStateException if the resource is absent from the classpath or cannot be read,
     *     which means this module's fixture tree and this class disagree about what is committed
     */
    private static byte[] fixtureImage(String scenario, String fileName) {
        String resource = FIXTURE_POSTING_ROOT + scenario + "/" + fileName;
        try (InputStream stream =
                PostTransactionsJobTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("the committed fixture " + resource
                        + " is not on the test classpath, so this case has no input to drive");
            }
            return trimTerminator(stream.readAllBytes());
        } catch (IOException unreadable) {
            throw new IllegalStateException(
                    "the committed fixture " + resource + " could not be read as bytes", unreadable);
        }
    }

    /**
     * Reads every record of one committed expectation file.
     *
     * @param scenario the expectation tree's directory name under {@code tests/golden/posting}
     * @param fileName the expectation file's name within that tree
     * @return one entry per record, in file order, never {@code null}
     * @throws IOException if the file cannot be read
     */
    private static List<byte[]> expectationRecords(String scenario, String fileName)
            throws IOException {
        return splitRecords(expectationImage(scenario, fileName));
    }

    /**
     * Reads one committed expectation file, with its trailing line terminator removed.
     *
     * <p>Assumptions: read STRICTLY READ-ONLY, as the case driving this states at length -- this class
     * offers no switch, no argument and no write-if-missing branch that could rewrite one.</p>
     *
     * @param scenario the expectation tree's directory name under {@code tests/golden/posting}
     * @param fileName the expectation file's name within that tree
     * @return the file's bytes with any trailing carriage return or line feed removed, never
     *     {@code null}
     * @throws IOException if the file cannot be read
     */
    private static byte[] expectationImage(String scenario, String fileName) throws IOException {
        return trimTerminator(
                Files.readAllBytes(goldenPostingRoot().resolve(scenario).resolve(fileName)));
    }

    /**
     * Splits a fixed-width file image into its records.
     *
     * @param image the whole file, already free of its trailing terminator; must not be {@code null}
     * @return one entry per line, empty lines dropped, never {@code null}
     */
    private static List<byte[]> splitRecords(byte[] image) {
        List<byte[]> records = new ArrayList<>();
        int start = 0;
        for (int index = 0; index <= image.length; index++) {
            if (index == image.length || image[index] == '\n') {
                byte[] record = trimTerminator(Arrays.copyOfRange(image, start, index));
                if (record.length > 0) {
                    records.add(record);
                }
                start = index + 1;
            }
        }
        return records;
    }

    /**
     * Removes any trailing carriage returns and line feeds from a byte image.
     *
     * <p>Assumptions: the terminator is stripped here and the record's declared width is then checked by
     * the production decoder itself, rather than by a second length constant written down in this
     * class. Section 3.9 of the fixture contract makes the trailing newline a determinism rule rather
     * than part of the record.</p>
     *
     * @param bytes the image to trim; must not be {@code null}
     * @return the trimmed copy, which may be empty, never {@code null}
     */
    private static byte[] trimTerminator(byte[] bytes) {
        int length = bytes.length;
        while (length > 0 && (bytes[length - 1] == '\n' || bytes[length - 1] == '\r')) {
            length--;
        }
        return Arrays.copyOf(bytes, length);
    }

    /**
     * A transaction manager that begins a DISTINCT transaction for each template that asks for one.
     *
     * <p>Alternatives Considered: the framework's own {@code ResourcelessTransactionManager}, which
     * every other case in this class uses and which the boundary-extent cases cannot use. Measured:
     * that double keeps a thread-bound LIST of transaction objects, appends one on every
     * {@code doGetTransaction} and reports an existing transaction whenever the list holds more than
     * one -- and it only drains the list when a status it created was a NEW transaction. The tasklet
     * step's own bracket is declared {@code PROPAGATION_NOT_SUPPORTED}, which produces an EMPTY status
     * that never drains, so every per-record template that follows it is told a transaction already
     * exists and PARTICIPATES instead of beginning one. Its statuses then report
     * {@code isNewTransaction()} as {@code false} and its {@code doBegin}, {@code doCommit} and
     * {@code doRollback} are never reached, which makes a per-record boundary indistinguishable from a
     * pass-wide one through exactly the observations these cases need. That is an artefact of the
     * double and not of the design: the production manager is a {@code JpaTransactionManager}, which
     * binds its existing-transaction decision to a bound persistence context and therefore begins a
     * real transaction per record and unbinds it on completion.</p>
     *
     * <p>Assumptions: suspension is implemented rather than left to the base class, which throws. The
     * production shape requires it -- a {@code PROPAGATION_NOT_SUPPORTED} bracket opened while a
     * transaction is active must suspend it -- so a double that could not suspend would pass only
     * because nothing in these cases opens the bracket inside a transaction, and would stop passing the
     * moment the surrounding wiring changed for a reason unrelated to this job.</p>
     *
     * <p>Assumptions: the transaction object is a bare {@code Object} identity and holds no resource,
     * because nothing here needs one -- the repositories are test doubles and hold no rows. What the
     * cases read is which of the manager's own lifecycle methods ran and how often, and identity is
     * enough to make each begin, commit and rollback attributable to one template.</p>
     */
    private static final class DistinctBoundaryTransactionManager
            extends AbstractPlatformTransactionManager {

        /** The serialization identifier the abstract base class requires of its subclasses. */
        private static final long serialVersionUID = 1L;

        /** The transaction currently active on this thread, or {@code null} when none is. */
        private transient Object active;

        /**
         * Reports the active transaction, or a fresh identity when none is active.
         *
         * @return the transaction object the base class will then test for existence, never
         *     {@code null}
         */
        @Override
        protected Object doGetTransaction() {
            return this.active != null ? this.active : new Object();
        }

        /**
         * Reports whether the supplied transaction is the one already active on this thread.
         *
         * @param transaction the transaction object {@link #doGetTransaction()} returned; must not be
         *     {@code null}
         * @return {@code true} when a transaction is already active and this is it
         */
        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return this.active != null && this.active == transaction;
        }

        /**
         * Marks the supplied transaction active, standing for opening a real one.
         *
         * @param transaction the transaction being begun; must not be {@code null}
         * @param definition the definition it was begun under; must not be {@code null}
         */
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            this.active = transaction;
        }

        /**
         * Completes the transaction, which holds no resource to flush.
         *
         * @param status the status of the transaction being committed; must not be {@code null}
         */
        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // WHY : Assumptions: the commit is deliberately empty. A commit here has to be OBSERVABLE
            //       and nothing more -- the base class records the call on the spy the cases verify --
            //       because the durability of a committed write is proven against a real database by
            //       PostingUnitOfWorkIT and cannot be proven against test doubles that hold no rows.
        }

        /**
         * Abandons the transaction, which holds no resource to discard.
         *
         * @param status the status of the transaction being rolled back; must not be {@code null}
         */
        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // WHY : Assumptions: empty for the same reason doCommit is; see the note there.
        }

        /**
         * Detaches the active transaction so a suspended bracket can be opened over it.
         *
         * @param transaction the transaction being suspended; must not be {@code null}
         * @return the suspended transaction, for {@link #doResume} to restore
         */
        @Override
        protected Object doSuspend(Object transaction) {
            this.active = null;
            return transaction;
        }

        /**
         * Reattaches a previously suspended transaction.
         *
         * @param transaction the transaction being resumed into, which may be {@code null}
         * @param suspendedResources whatever {@link #doSuspend} returned; must not be {@code null}
         */
        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            this.active = suspendedResources;
        }

        /**
         * Clears the thread's active transaction once it has completed either way.
         *
         * @param transaction the completed transaction; must not be {@code null}
         */
        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            this.active = null;
        }
    }
}
