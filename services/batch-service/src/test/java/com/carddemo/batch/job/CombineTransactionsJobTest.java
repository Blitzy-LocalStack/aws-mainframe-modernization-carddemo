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
import com.carddemo.batch.domain.BatchRun;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.repository.BatchRunRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.BatchStepLedgerWriter;
import com.carddemo.batch.service.DatasetGenerationService;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.codec.ZonedDecimalCodec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Pins the pipeline join, the sort-order translation and the absent load-back of the combine step.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this is the tier-two owner of three contracts of
 * {@link CombineTransactionsJob}, state seven of the nightly chain. First, the PIPELINE JOIN: the
 * combined artefact carries the rows of both upstream producers, the posting pass and the interest
 * pass, each exactly once. Second, the SORT ORDER, together with the collation ruling that makes it
 * a correctness requirement rather than a preference. Third, the NO-LOAD-BACK ruling: the run writes
 * a generation and touches no row of the relation it read.</p>
 *
 * <p>Assumptions: there is NO COBOL PROGRAM behind this state, so nothing here is a transcription of
 * one. {@code app/jcl/COMBTRAN.jcl:22} reads {@code //STEP05R EXEC PGM=SORT}, a sort-utility
 * invocation, and its second step at {@code :41} invokes the dataset utility. The driver is therefore
 * the whole specification, and the cases below cite it by line: {@code :23-24} and {@code :25-26} for
 * the two concatenated inputs, {@code :28} for the sort symbol, {@code :30} for the direction,
 * {@code :33-37} for the output generation and its inherited geometry, and {@code :41-48} for the
 * step this job deliberately has no counterpart to. Field geometry comes from
 * {@code app/cpy/CVTRA05Y.cpy}.</p>
 *
 * <p>Trade-offs: because no committed expectation output exists for this artefact, THIS FILE IS THE
 * SPECIFICATION of the combined image. No {@code combine} domain exists under
 * {@code services/batch-service/src/test/resources/fixtures}, whose four admitted domains section 4.1
 * of that tree's contract fixes as posting, interest, preflight and export; and no combine
 * expectation exists in the parity oracle's tree either. The cost is that these assertions have no
 * independent artefact to disagree with, so a wrong assertion here reads as a passing test. The
 * compensation is that every byte asserted is traced to {@code app/cpy/CVTRA05Y.cpy}, the one
 * normative source for the layout, rather than to a remembered value.</p>
 *
 * <p>Trade-offs: every input is consequently BUILT IN CODE. That is the posture the package charter
 * records for this job and its backup sibling, and the one
 * {@code com.carddemo.batch.service.DatasetGenerationServiceTest} already takes. What is given up is
 * a committed byte image a reviewer can inspect with a hex viewer; what is bought is that the
 * fixture tree gains no domain without a job to drive it, and that the timestamps are chosen rather
 * than observed -- which is why the emitted image here needs NO normalisation at all, unlike the
 * posting domain, which masks the processing stamp, and the interest domain, which masks both.</p>
 *
 * <p>Trade-offs: a real database engine is required here rather than a repository test stand-in, and
 * the reason is specific to this file. The ordering contract asserted below is a property of how the
 * engine compares a {@code CHAR(16)} column, so a stand-in that returned rows in an order this test
 * chose would assert nothing about the ordering at all -- it would assert the arrangement of its own
 * stub. The engine is the pinned image every sibling integration case in this module already pins,
 * so one schema is never interpreted by two engines.</p>
 *
 * <p>Trade-offs: the output sink is a SEAM and never an object store, emulated or otherwise.
 * {@code services/batch-service/pom.xml} carries the container modules for the database engine and
 * for the test framework and no emulator module of any kind, so a case that reached for a bucket
 * would not compile. The staged payload is captured at the seam, which is also the only place the
 * bytes are observable as one contiguous image.</p>
 *
 * <p>Assumptions: this class is named with the plain suffix and never the two-letter integration
 * one. The two runners divide this subtree by class name alone -- {@code services/pom.xml} declares
 * the surefire and failsafe coordinates without an include pattern, so each keeps its default and the
 * suffix is the whole selector -- and the package charter records the consequence of the other
 * choice: the integration runner would collect it at a phase where nothing prepares what it needs,
 * the unit runner would never see it, and the build would still report success.</p>
 *
 * @see CombineTransactionsJob
 */
@Testcontainers
// WHY : Refactoring Rationale: the Parameter Store config-data location is disabled for this
//       context. The base document's `optional:aws-parameterstore:` location builds a management
//       client while configuration is still loading, and a build host with no region configured
//       aborts the context on the unresolved placeholder before a single case runs. The sibling
//       com.carddemo.batch.repository.BatchRunRepositoryIT records the full measurement and the two
//       rejected alternatives; it is cited from here rather than repeated.
@SpringBootTest(
        classes = CombineTransactionsJobTest.CombinedGenerationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.aws.parameterstore.enabled=false")
@ActiveProfiles("test")
@DisplayName("the combine-transactions job")
class CombineTransactionsJobTest {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the version is recorded in prose because a digest states nothing a reader
     * recognises, and this is the digest every sibling integration case in this module already pins.
     * Two cases pinning two engines could disagree about one column's comparison behaviour, and the
     * disagreement would surface as whichever of them ran second.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /** The classpath-relative harness that creates the foreign schemas this case reads and writes. */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /**
     * The injected business date, in the separated ten-character layout.
     *
     * <p>Assumptions: the separated layout is chosen deliberately over the compact one, because it is
     * the layout that makes the collation hazard below REACHABLE. The interest pass concatenates this
     * token unchanged into a sixteen-character identifier at
     * {@code app/cbl/CBACT04C.cbl:476-480}, so the separated layout is what puts a hyphen inside a
     * stored key; the compact layout would produce digits only and the ordering assertion would have
     * nothing to discriminate.</p>
     */
    private static final String BUSINESS_DATE_TOKEN = "2022-07-18";

    /** The injected business date as the job receives it. */
    private static final BusinessDate BUSINESS_DATE = new BusinessDate(BUSINESS_DATE_TOKEN);

    /** The orchestrator execution identifier every case runs under. */
    private static final String RUN_ID = "batch-run-combine-0001";

    /** The declared length of one transaction image, from {@code app/cpy/CVTRA05Y.cpy:2}. */
    private static final int TRANSACTION_RECORD_LENGTH = 350;

    /** The declared width of the sort key, from {@code TRAN-ID,1,16,CH} at {@code COMBTRAN.jcl:28}. */
    private static final int TRANSACTION_ID_LENGTH = 16;

    /**
     * An interest-generated identifier carrying the separated token's hyphens.
     *
     * <p>Assumptions: this value and {@link #POSTED_DIGITS_ID} are the DISCRIMINATING PAIR, and the
     * pair is the whole reason the ordering assertion can fail for the right reason. Compared byte by
     * byte the hyphen at offset four, {@code 0x2D}, sorts below the digit {@code 0x30} that the other
     * value carries there, so this identifier is FIRST. Compared under a collation that gives
     * punctuation no primary weight -- which is what a conventional linguistic collation does -- the
     * hyphens contribute nothing, the remaining digits are compared, and this identifier is LAST. Two
     * identifiers made only of digits cannot distinguish those two orders, so a fixture without this
     * pair would pass under either.</p>
     */
    private static final String INTEREST_HYPHEN_ID = "2022-07-18000001";

    /** The second interest-generated identifier, one suffix step above the first. */
    private static final String INTEREST_HYPHEN_ID_NEXT = "2022-07-18000002";

    /** The posted identifier that forms the discriminating pair with {@link #INTEREST_HYPHEN_ID}. */
    private static final String POSTED_DIGITS_ID = "2022071800000001";

    /** A posted identifier drawn from the shape the seed corpus uses. */
    private static final String POSTED_SEED_ID = "0000000000683580";

    /** A second posted identifier, adjacent to {@link #POSTED_SEED_ID} in the key space. */
    private static final String POSTED_SEED_ID_NEXT = "0000000000683581";

    /**
     * The description the interest pass builds, from {@code app/cbl/CBACT04C.cbl:485-489}.
     *
     * <p>Assumptions: twenty-four characters, the literal followed by an eleven-digit account
     * identifier. The width is quoted from section 6.3 of the fixture contract rather than counted
     * here, and it matters only in that it leaves the rest of the hundred-byte field to a pad.</p>
     */
    private static final String INTEREST_DESCRIPTION = "Int. for a/c 00000000011";

    /** The description a posted row carries, standing in for feed content of the same shape. */
    private static final String POSTED_DESCRIPTION = "Purchase at Abshire-Lowe";

    /**
     * A NEGATIVE amount, carried so the sign overpunch is exercised rather than assumed.
     *
     * <p>Assumptions: negative is not decoration. Section 5.2 of {@code tests/README.md} records that
     * the sign of a zoned-decimal field lives in the LAST byte as an overpunch and that the default
     * convention silently corrupts negative balances, so a fixture of positive amounts only would
     * round-trip identically under both conventions and prove nothing about either.</p>
     */
    private static final BigDecimal NEGATIVE_AMOUNT = new BigDecimal("-12.34");

    /** A positive amount, so the emitted stream carries both signs. */
    private static final BigDecimal POSITIVE_AMOUNT = new BigDecimal("504.77");

    /** The origination instant every seeded row carries, a fixed value in the past. */
    private static final LocalDateTime SEEDED_ORIGINATION_TIME =
            LocalDateTime.of(2022, 6, 10, 19, 27, 53, 0);

    /** The origination instant as the encoder renders it into the twenty-six-byte span. */
    private static final String SEEDED_ORIGINATION_STAMP = "2022-06-10 19:27:53.000000";

    /** The processing instant every seeded row carries, a fixed value in the past. */
    private static final LocalDateTime SEEDED_PROCESSING_TIME =
            LocalDateTime.of(2022, 7, 18, 4, 5, 6, 123456000);

    /** The processing instant as the encoder renders it into the twenty-six-byte span. */
    private static final String SEEDED_PROCESSING_STAMP = "2022-07-18 04:05:06.123456";

    /** The transaction type code both seeded row classes carry. */
    private static final String TYPE_CD = "01";

    /** The category code a seeded posted row carries. */
    private static final String POSTED_CATEGORY_CD = "0001";

    /** The category code a seeded interest row carries. */
    private static final String INTEREST_CATEGORY_CD = "0005";

    /** The source a seeded posted row carries. */
    private static final String POSTED_SOURCE = "POS TERM";

    /** The source a seeded interest row carries. */
    private static final String INTEREST_SOURCE = "System";

    /** The merchant identifier every seeded row carries. */
    private static final long MERCHANT_ID = 800000000L;

    /** The merchant name every seeded row carries. */
    private static final String MERCHANT_NAME = "Abshire-Lowe";

    /** The merchant city every seeded row carries. */
    private static final String MERCHANT_CITY = "North Enoshaven";

    /** The merchant postal code every seeded row carries. */
    private static final String MERCHANT_ZIP = "72112";

    /**
     * The card number every seeded row carries.
     *
     * <p>Assumptions: sixteen characters, the width {@code TRAN-CARD-NUM PIC X(16)} declares at line 15
     * of {@code app/cpy/CVTRA05Y.cpy}. It is fabricated, fails the industry check digit, and identifies
     * nothing.</p>
     */
    private static final String CARD_NUM = "4859452612877065";

    /** The zero-based offset of the trailing pad, from line 18 of {@code app/cpy/CVTRA05Y.cpy}. */
    private static final int TRAILING_PAD_OFFSET = 330;

    /**
     * The name given to the linguistic collation the discrimination case creates.
     *
     * <p>Assumptions: the collation is created by the case rather than assumed present, because the
     * engine pinned by {@code POSTGRES_IMAGE} ships the provider but registers no such named
     * collation. Its locale {@code en-u-ka-shifted} requests punctuation weighting shifted out of the
     * primary level, which is the behaviour a conventional linguistic collation applies and the
     * behaviour the byte-wise contract must survive.</p>
     *
     * <p>Assumptions: the name is SCHEMA-QUALIFIED, and the qualification is required rather than
     * tidy. Line 208 of {@code services/batch-service/src/test/resources/application-test.yml} pins
     * the session search path to {@code batch, ledger, account, reference, card}, and that list
     * deliberately excludes the default schema so that an unqualified name cannot resolve against an
     * object no migration in this repository created -- so a collation created without a schema lands
     * somewhere the session cannot see and every reference to it fails as unknown.</p>
     */
    private static final String LINGUISTIC_COLLATION = "batch.carddemo_shifted_punctuation";

    /** The engine the ordering ruling is measured against. */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /** The relation both producers write and this job reads. */
    @Autowired
    private TransactionRepository ledger;

    /** The durable step ledger's table, read directly to count rows per run and step. */
    @Autowired
    private BatchRunRepository runs;

    /** The durable step ledger the job runs its body under. */
    @Autowired
    private BatchStepLedger ledgerOfSteps;

    /**
     * The context's own transaction manager, handed to the step the job builds.
     *
     * <p>Assumptions: the REAL manager is used and not a resourceless stand-in. The ordered walk the
     * job performs, {@code TransactionRepository.findAllByOrderByTransactionIdAsc}, declares mandatory
     * propagation and returns a cursor-backed stream whose server-side cursor exists only because
     * lines 212-217 of {@code application-test.yml} keep the pool's auto-commit off so the fetch-size
     * hint is honoured; it therefore needs a transaction that actually owns that connection, and a
     * stand-in would satisfy the propagation check and then hand back a stream with nothing behind
     * it.</p>
     */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** A template used to commit each seed before a run, so the run reads committed rows. */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A plain database handle, used for the collation measurement no entity can express. */
    private JdbcTemplate jdbc;

    /** The generation resolver, mocked so the staged payload is observable at the seam. */
    private DatasetGenerationService generations;

    /** The framework's in-memory job repository. */
    private JobRepository jobRepository;

    /** The shared parameter validator the job is built with. */
    private JobParametersValidator validator;

    /** Every payload handed to the staging seam during one case, in the order it was handed over. */
    private List<StagedPayload> stagedPayloads;

    /** Distinguishes the framework identifiers of two launches inside one case. */
    private int launchCounter;

    /**
     * Publishes the container's generated coordinates to the context under construction.
     *
     * @param registry the registry the test framework supplies for late-bound properties; must not be
     *     {@code null}
     */
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties both tables and rebuilds the staging seam with the defaults a clean run sees.
     *
     * <p>Assumptions: the two tables are emptied in a committed transaction rather than rolled back
     * around each case, because the job reads through its own transaction and a case that only
     * rolled back would leave the previous case's rows visible to the next run. Nothing here declares
     * a foreign key between them, matching the owning migrations.</p>
     *
     * @param dataSource the pool the context built from the container's coordinates, taken as a
     *     parameter rather than as a field so the plain database handle is rebuilt per case; must not
     *     be {@code null}
     */
    @BeforeEach
    void emptyTablesAndBuildSeam(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactionTemplate.executeWithoutResult(status -> {
            this.jdbc.update("DELETE FROM ledger.transactions");
            this.jdbc.update("DELETE FROM batch.batch_run");
        });

        this.jobRepository = new ResourcelessJobRepository();
        this.validator = new BatchConfig().carddemoJobParametersValidator();
        this.stagedPayloads = new ArrayList<>();
        this.launchCounter = 0;
        this.generations = mock(DatasetGenerationService.class);

        when(this.generations.resolveCurrentGeneration(any(DatasetFamily.class)))
                .thenAnswer(call -> Optional.of(generation(call.getArgument(0), 4)));
        when(this.generations.allocateNewGeneration(any(DatasetFamily.class),
                any(BusinessDate.class), anyString()))
                .thenAnswer(call -> generation(call.getArgument(0), 5));
        when(this.generations.generationsToScratch(any(DatasetFamily.class))).thenReturn(List.of());
        when(this.generations.datasetUri(any(DatasetGeneration.class)))
                .thenReturn("s3://carddemo-datasets-test/ledger/transact-combined/");

        // WHY : Assumptions: the payload is read INSIDE the answer because the job removes the
        //       temporary file in a finally block that runs as soon as this call returns. Capturing
        //       the path and reading it afterwards would read a file that no longer exists, and the
        //       failure would look like an assertion about bytes rather than about lifetime.
        when(this.generations.stageDataset(any(DatasetGeneration.class), anyString(), any(Path.class)))
                .thenAnswer(call -> {
                    Path body = call.getArgument(2);
                    this.stagedPayloads.add(new StagedPayload(call.getArgument(0),
                            call.getArgument(1), Files.readAllBytes(body)));
                    return "ledger/transact-combined/staged/key";
                });
    }

    /**
     * The job registers under the token the orchestrator names it by, and under no other.
     *
     * <p>Pins the vocabulary {@code com.carddemo.batch.dto.BatchJobName} publishes outward against
     * the name the built job actually reports. The two are stated in separate places -- the token
     * enumeration and the job's own registration -- and only an assertion makes them agree; a token
     * with no job behind it produces a container that validates its argument and then fails inside
     * the state machine.</p>
     */
    @Test
    @DisplayName("register under the combine-transactions token")
    void registerUnderTheCombineTransactionsToken() {
        assertThat(CombineTransactionsJob.JOB_NAME)
                .isEqualTo(BatchJobName.COMBINE_TRANSACTIONS.token())
                .isEqualTo("combine-transactions");
        assertThat(buildJob().getName()).isEqualTo(CombineTransactionsJob.JOB_NAME);

        // WHY : Assumptions: the step name is asserted alongside the job name because the durable
        //       ledger keys on it, so the two identifiers have different lifetimes -- a job may be
        //       renamed in an orchestration definition while ledger rows written under the old step
        //       name still exist. BatchJobName.forStepName is the inverse the ledger relies on, and
        //       asserting the round trip is what stops the suffix from being spelled twice.
        assertThat(CombineTransactionsJob.STEP_NAME)
                .isEqualTo("combine-transactions" + BatchJobName.STEP_NAME_SUFFIX);
        assertThat(BatchJobName.forStepName(CombineTransactionsJob.STEP_NAME))
                .isEqualTo(BatchJobName.COMBINE_TRANSACTIONS);
    }

    /**
     * The combined artefact carries both producers' rows, each exactly once.
     *
     * <p>Pins {@code app/jcl/COMBTRAN.jcl:23-24} and {@code :25-26}, the two concatenated inputs: the
     * backup generation the posting pipeline produced, and the system-transaction generation the
     * interest pipeline produced. Seeding only one class of row would let this case pass while
     * proving nothing at all about the join, which is why both are seeded and both are counted.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("carry both the posted and the interest-generated rows, each exactly once")
    void carryBothProducersRowsExactlyOnce() throws Exception {
        // WHY : Refactoring Rationale: the reference had to CONCATENATE TWO PHYSICAL DATASETS because
        //       its producers wrote to different files -- posting to the master, whose backup
        //       generation app/jcl/COMBTRAN.jcl:23-24 names, and accrual to the generation
        //       app/jcl/COMBTRAN.jcl:25-26 names. In the migrated model both producers commit to ONE
        //       relation: posting writes its rows at app/cbl/CBTRN02C.cbl:442 and accrual writes its
        //       generated rows at app/cbl/CBACT04C.cbl:468, and both land in ledger.transactions. The
        //       union the reference assembled physically is therefore already materialised, so this
        //       case seeds both classes into one table and asserts the artefact carries both. No
        //       concatenation step is invented, because inventing one would require materialising two
        //       intermediate datasets that the migrated model does not produce.
        // WHY : Assumptions: this state's correctness DEPENDS on states four and five having
        //       committed, which is exactly what the sequencing of the orchestration guarantees. Both
        //       reference inputs at app/jcl/COMBTRAN.jcl:23-26 are named at the CURRENT generation,
        //       `(0)` rather than `(+1)`, so the reference reads what its predecessors just wrote and
        //       so does this job.
        seed(interestRow(INTEREST_HYPHEN_ID, NEGATIVE_AMOUNT),
                postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));

        List<byte[]> records = splitIntoRecords(runAndCaptureStagedPayload());

        assertThat(records).hasSize(2);
        assertThat(records.stream().map(CombineTransactionsJobTest::identifierOf).toList())
                .containsExactlyInAnyOrder(INTEREST_HYPHEN_ID, POSTED_SEED_ID);
        // WHY : Alternatives Considered: reading the two records by their position in the stream, which
        //       is shorter to write. Rejected because the position is decided by the ORDERING contract
        //       that a different case owns, so a case about the join would fail whenever the ordering
        //       changed and would name the join as the defect. Selecting by identifier keeps each case
        //       failing only for its own reason.
        assertThat(fieldOf(recordFor(records, INTEREST_HYPHEN_ID), "TRAN-DESC"))
                .startsWith(INTEREST_DESCRIPTION);
        assertThat(fieldOf(recordFor(records, POSTED_SEED_ID), "TRAN-DESC"))
                .startsWith(POSTED_DESCRIPTION);
    }

    /**
     * Both inputs are read at their current generation and neither is allocated.
     *
     * <p>Pins the {@code (0)} references at {@code app/jcl/COMBTRAN.jcl:24} and {@code :26} against
     * the {@code (+1)} reference at {@code :37}: of the three generations the driver names, only the
     * output is new. A run that allocated an input would consume one of the retained generations of a
     * family it does not own, and the upstream step's own history would shorten without any step
     * reporting it.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("read both inputs at the current generation and allocate only the output")
    void readBothInputsCurrentAndAllocateOnlyTheOutput() throws Exception {
        seed(postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));

        JobExecution execution = runCombine();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        verify(this.generations).resolveCurrentGeneration(DatasetFamily.TRANSACT_BKUP);
        verify(this.generations).resolveCurrentGeneration(DatasetFamily.SYSTRAN);
        verify(this.generations, never())
                .allocateNewGeneration(DatasetFamily.TRANSACT_BKUP, BUSINESS_DATE, RUN_ID);
        verify(this.generations, never())
                .allocateNewGeneration(DatasetFamily.SYSTRAN, BUSINESS_DATE, RUN_ID);
        verify(this.generations)
                .allocateNewGeneration(DatasetFamily.TRANSACT_COMBINED, BUSINESS_DATE, RUN_ID);
    }

    /**
     * The records are ordered ascending by the sixteen-byte identifier, compared byte by byte.
     *
     * <p>Pins {@code SORT FIELDS=(TRAN-ID,A)} at {@code app/jcl/COMBTRAN.jcl:30} over the symbol
     * {@code TRAN-ID,1,16,CH} at {@code :28}. The expected sequence is computed here from the raw
     * bytes with an unsigned comparison and is NOT read back from the engine, so the assertion states
     * the contract independently of whatever the engine's default comparison happens to be.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("order the records ascending by the sixteen-byte identifier, byte by byte")
    void orderTheRecordsByteWiseAscendingByIdentifier() throws Exception {
        // WHY : Assumptions: the sort format at app/jcl/COMBTRAN.jcl:28 is `CH`, which is a BYTE-WISE
        //       character comparison, and the direction at :30 is ascending. The migrated read orders
        //       a CHAR(16) column, and a column comparison resolves through the database collation --
        //       so on an engine whose collation gives punctuation no primary weight the emitted order
        //       would differ from the reference's for exactly the identifiers the accrual pass
        //       produces, because app/cbl/CBACT04C.cbl:476-480 concatenates the ten-character token
        //       into the key unchanged and one committed layout of that token carries hyphens.
        // WHY : Alternatives Considered: asserting the emitted order against a second query ordered by
        //       the same column, which is the shorter case to write. Rejected because both readings
        //       would resolve through the SAME collation -- the job walks the derived finder
        //       TransactionRepository.findAllByOrderByTransactionIdAsc, which names no COLLATE clause,
        //       over a column the harness script declares without one -- so the pair would agree under
        //       every collation and the assertion would hold whatever the engine did. The expectation
        //       is therefore computed from the bytes, which is the only form of the claim that can fail
        //       when the engine's comparison stops matching the reference's.
        seed(interestRow(INTEREST_HYPHEN_ID, NEGATIVE_AMOUNT),
                interestRow(INTEREST_HYPHEN_ID_NEXT, POSITIVE_AMOUNT),
                postedRow(POSTED_DIGITS_ID, POSITIVE_AMOUNT),
                postedRow(POSTED_SEED_ID_NEXT, POSITIVE_AMOUNT),
                postedRow(POSTED_SEED_ID, NEGATIVE_AMOUNT));

        List<String> emitted = splitIntoRecords(runAndCaptureStagedPayload()).stream()
                .map(CombineTransactionsJobTest::identifierOf)
                .toList();

        assertThat(emitted).containsExactlyElementsOf(byteWiseAscending(emitted));
        assertThat(emitted).containsExactly(POSTED_SEED_ID, POSTED_SEED_ID_NEXT,
                INTEREST_HYPHEN_ID, INTEREST_HYPHEN_ID_NEXT, POSTED_DIGITS_ID);
    }

    /**
     * The seeded identifiers really do distinguish a byte-wise order from a linguistic one.
     *
     * <p>Pins the discriminating power of the fixture itself rather than a property of the job, and it
     * is here because without it the case above could hold vacuously. It creates a collation that
     * gives punctuation no primary weight -- the behaviour a conventional linguistic collation applies
     * -- orders the same rows under it, and asserts the result DIFFERS from the byte-wise order the
     * previous case requires. If this case ever passes trivially, the identifiers stopped
     * discriminating and the ordering contract stopped being checked.</p>
     */
    @Test
    @DisplayName("prove the seeded identifiers discriminate byte order from linguistic order")
    void proveTheSeededIdentifiersDiscriminateByteOrderFromLinguisticOrder() {
        seed(interestRow(INTEREST_HYPHEN_ID, NEGATIVE_AMOUNT),
                interestRow(INTEREST_HYPHEN_ID_NEXT, POSITIVE_AMOUNT),
                postedRow(POSTED_DIGITS_ID, POSITIVE_AMOUNT),
                postedRow(POSTED_SEED_ID_NEXT, POSITIVE_AMOUNT),
                postedRow(POSTED_SEED_ID, NEGATIVE_AMOUNT));

        // WHY : Assumptions: the collation is created here and not assumed to exist. The engine pinned
        //       by POSTGRES_IMAGE ships the provider that can express punctuation weighting but
        //       registers no collation that uses it, so the case has to declare one; it is declared
        //       non-deterministic because a shifted weighting can rank two distinct strings equal at
        //       every level, which a deterministic collation is not permitted to do.
        // WHY : Assumptions: the declaration is wrapped in a transaction so that it COMMITS. Line 217
        //       of services/batch-service/src/test/resources/application-test.yml sets the pool's
        //       auto-commit to false, so a statement issued outside a transaction is executed and then
        //       discarded when the connection is returned -- and because this engine keeps schema
        //       changes transactional, the collation would simply not exist by the time the next
        //       statement looked for it.
        this.transactionTemplate.executeWithoutResult(status ->
                this.jdbc.execute("CREATE COLLATION IF NOT EXISTS " + LINGUISTIC_COLLATION
                        + " (provider = icu, locale = 'en-u-ka-shifted', deterministic = false)"));

        List<String> byteWise = this.jdbc.queryForList(
                "SELECT transaction_id FROM ledger.transactions"
                        + " ORDER BY transaction_id COLLATE \"C\"", String.class);
        List<String> linguistic = this.jdbc.queryForList(
                "SELECT transaction_id FROM ledger.transactions"
                        + " ORDER BY transaction_id COLLATE " + LINGUISTIC_COLLATION, String.class);

        assertThat(byteWise).containsExactlyElementsOf(byteWiseAscending(byteWise));
        assertThat(linguistic)
                .withFailMessage("the seeded identifiers no longer distinguish a byte-wise order"
                        + " from a linguistic one, so the ordering contract of"
                        + " app/jcl/COMBTRAN.jcl:28 is no longer being checked; restore an identifier"
                        + " carrying a hyphen at a position where the two orders disagree")
                .isNotEqualTo(byteWise);
    }

    /**
     * A second run over the same rows emits an identical image, byte for byte.
     *
     * <p>Pins that the ordering is TOTAL and not merely sorted, and that nothing volatile reaches the
     * artefact. {@code SORT FIELDS=(TRAN-ID,A)} at {@code app/jcl/COMBTRAN.jcl:30} carries no
     * equal-key operand, so the utility's treatment of ties is unspecified; ties cannot arise on
     * either side because the identifier is the relation's whole primary key, and this case is what
     * establishes that the migrated output depends on nothing else.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("emit an identical image on a rerun over the same rows")
    void emitAnIdenticalImageOnARerunOverTheSameRows() throws Exception {
        seed(interestRow(INTEREST_HYPHEN_ID, NEGATIVE_AMOUNT),
                postedRow(POSTED_DIGITS_ID, POSITIVE_AMOUNT),
                postedRow(POSTED_SEED_ID, NEGATIVE_AMOUNT));

        byte[] first = runAndCaptureStagedPayload();

        // WHY : Assumptions: the second launch carries a DIFFERENT run identifier. The durable step
        //       ledger answers a repeat of the same run and step by skipping the body entirely, which
        //       is asserted by its own case below, so reusing the identifier here would compare one
        //       image against nothing. A distinct identifier is what makes this a second genuine pass
        //       over the same rows.
        JobExecution second = runCombine("batch-run-combine-0002");

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.stagedPayloads).hasSize(2);
        assertThat(this.stagedPayloads.get(1).body()).isEqualTo(first);
    }

    /**
     * The run inserts, updates and deletes nothing in the relation it read.
     *
     * <p>Pins the ABSENCE of a counterpart to {@code app/jcl/COMBTRAN.jcl:41-48}, the unconditional
     * copy of the combined dataset back into the transaction master. The row count and every row are
     * asserted unchanged across the run.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("leave every row of the relation exactly as it was")
    void leaveEveryRowOfTheRelationExactlyAsItWas() throws Exception {
        // WHY : Alternatives Considered: porting the reference's second step, the
        //       `REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)` at app/jcl/COMBTRAN.jcl:48, which carries
        //       NO condition operand and therefore always runs. Rejected, and the rejection is only
        //       safe because its counterpart is rejected too: in the reference that copy is what GIVES
        //       the master its contents back, and it is harmless solely because
        //       app/jcl/TRANBKP.jcl:37-60 has already deleted and redefined the cluster as empty. The
        //       migrated model ports NEITHER step -- the backup job does not empty the table, so there
        //       is nothing for a copy to restore, and the rows the copy would write are already
        //       present because the two upstream jobs committed them.
        // WHY : Trade-offs: the two omissions are correct ONLY AS A PAIR, and changing one without the
        //       other is a defect in one of two directions. Reinstating the copy at
        //       app/jcl/COMBTRAN.jcl:41-48 while the table still holds every row would insert each row
        //       a second time and the whole relation would be duplicated; reinstating the
        //       empty-and-redefine at app/jcl/TRANBKP.jcl:37-60 while no copy restores the rows would
        //       discard the night's entire ledger. The backup job's own case owns the other half of
        //       this pair and this case owns the copy half, so a future author who finds one of them
        //       finds the reference to the other.
        // WHY : Assumptions: the PURPOSE the copy served has no counterpart to port. The statement at
        //       app/jcl/COMBTRAN.jcl:48 existed to make the sorted, combined set the master's new
        //       physical contents, and in the migrated model the relation is already the single
        //       authoritative store while the ordering is a property of a query rather than of
        //       storage -- so there is no physical arrangement for a copy to establish.
        seed(interestRow(INTEREST_HYPHEN_ID, NEGATIVE_AMOUNT),
                postedRow(POSTED_DIGITS_ID, POSITIVE_AMOUNT),
                postedRow(POSTED_SEED_ID, NEGATIVE_AMOUNT));
        List<String> before = committedRowImages();
        assertThat(before).hasSize(3);

        JobExecution execution = runCombine();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // WHY : Assumptions: the comparison covers the row COUNT, every VALUE and each row's inserting
        //       transaction stamp, because the paired omission is defeated in three different shapes and
        //       a count alone sees only the first. A reinstated copy against an emptied table shows up
        //       as a changed count; a copy that rewrote rows in place keeps the count and moves the
        //       stamp; and a copy that rewrote them with different text keeps the count and changes a
        //       value. The stamp is the one of the three a value comparison cannot see, which is why it
        //       is included rather than assumed redundant.
        assertThat(committedRowImages()).containsExactlyElementsOf(before);
        assertThat(this.jdbc.queryForObject(
                "SELECT count(*) FROM ledger.transactions", Long.class)).isEqualTo(3L);
    }

    /**
     * Every record is exactly the declared length and the stream carries no delimiter.
     *
     * <p>Pins {@code DCB=(*.SORTIN)} at {@code app/jcl/COMBTRAN.jcl:35}, by which the sort output
     * INHERITS its attributes from its first input, and that input was created fixed-blocked at the
     * declared length by {@code app/jcl/TRANBKP.jcl:31}. The length appears nowhere in this job's own
     * driver, which is why it is asserted against {@code app/cpy/CVTRA05Y.cpy:2} instead.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("emit fixed-length records with no delimiter between them")
    void emitFixedLengthRecordsWithNoDelimiter() throws Exception {
        seed(interestRow(INTEREST_HYPHEN_ID, NEGATIVE_AMOUNT),
                postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));

        byte[] image = runAndCaptureStagedPayload();

        assertThat(image).hasSize(2 * TRANSACTION_RECORD_LENGTH);
        // WHY : Assumptions: absence of a delimiter is asserted by reading the SECOND record's key at
        //       the exact offset a delimiter-free stream puts it, rather than by searching for a
        //       newline. A search would pass on a stream whose separator was some other byte, whereas
        //       a key that lands on its declared offset can only do so if nothing was inserted ahead
        //       of it.
        assertThat(new String(image, TRANSACTION_RECORD_LENGTH, TRANSACTION_ID_LENGTH,
                StandardCharsets.US_ASCII)).isEqualTo(INTEREST_HYPHEN_ID);
        assertThat(image).doesNotContain((byte) '\n').doesNotContain((byte) '\r');
    }

    /**
     * Each field lands on the offset and width the copybook declares.
     *
     * <p>Pins {@code app/cpy/CVTRA05Y.cpy} field by field. Because no committed expectation output
     * exists for this artefact, this case IS the specification of the combined image, so every span is
     * read through the registered layout rather than through an offset written out here.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("place every field on its declared copybook offset")
    void placeEveryFieldOnItsDeclaredCopybookOffset() throws Exception {
        // WHY : Assumptions: decoding goes through the shared registry and the shared codecs and never
        //       through a second decoder written here. Section 3.3 of the fixture contract sanctions
        //       ZonedDecimalCodec in its EBCDIC mode and section 8.1 sanctions CopybookLayout's TRAN
        //       and INTTRAN entries as the Java-side readers, and the trailing-pad handling depends on
        //       the specification instance being the REGISTERED one: FixedWidthCodec's padding test
        //       compares by IDENTITY, requiring CopybookLayout.layout(spec.name()) == spec, so a
        //       hand-built copy of the same geometry silently stops dropping the pad and a local
        //       decoder would then disagree with the encoder about a span that carries no data, which
        //       would read as a field error.
        CopybookLayout.RecordSpec spec = CopybookLayout.layout("TRAN");
        assertThat(spec.reclen()).isEqualTo(TRANSACTION_RECORD_LENGTH);
        assertThat(spec.keyOffset()).isZero();
        assertThat(spec.keyLength()).isEqualTo(TRANSACTION_ID_LENGTH);

        seed(postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));
        byte[] record = splitIntoRecords(runAndCaptureStagedPayload()).get(0);

        // WHY : Assumptions: a text field's expectation is written PADDED to its declared width,
        //       because FixedWidthCodec.decodeRecord returns each span whole and trims nothing. That is
        //       the right behaviour for a fixed-width reader -- a trailing blank inside a hundred-byte
        //       description is indistinguishable from one the writer intended -- and it means an
        //       expectation written as the bare value would fail on padding while every character of
        //       the value matched. Each width is taken from the registered layout read above rather
        //       than counted here, so every entry pins the field's declared width as well as its
        //       content.
        assertThat(FixedWidthCodec.decodeRecord(record, spec))
                .hasSize(13)
                .containsEntry("TRAN-ID", POSTED_SEED_ID)
                .containsEntry("TRAN-TYPE-CD", TYPE_CD)
                .containsEntry("TRAN-CAT-CD", 1L)
                .containsEntry("TRAN-SOURCE",
                        blankPadded(POSTED_SOURCE, spec.field("TRAN-SOURCE").length()))
                .containsEntry("TRAN-DESC",
                        blankPadded(POSTED_DESCRIPTION, spec.field("TRAN-DESC").length()))
                .containsEntry("TRAN-AMT", POSITIVE_AMOUNT)
                .containsEntry("TRAN-MERCHANT-ID", MERCHANT_ID)
                .containsEntry("TRAN-MERCHANT-NAME",
                        blankPadded(MERCHANT_NAME, spec.field("TRAN-MERCHANT-NAME").length()))
                .containsEntry("TRAN-MERCHANT-CITY",
                        blankPadded(MERCHANT_CITY, spec.field("TRAN-MERCHANT-CITY").length()))
                .containsEntry("TRAN-MERCHANT-ZIP",
                        blankPadded(MERCHANT_ZIP, spec.field("TRAN-MERCHANT-ZIP").length()))
                .containsEntry("TRAN-CARD-NUM", CARD_NUM)
                .containsEntry("TRAN-ORIG-TS", SEEDED_ORIGINATION_STAMP)
                .containsEntry("TRAN-PROC-TS", SEEDED_PROCESSING_STAMP)
                .doesNotContainKey("FILLER");

        // WHY : Assumptions: the trailing pad is asserted PRESENT and never trimmed. Section 6.1 of
        //       the fixture contract measures the reference's own pad on this record as low values,
        //       because no statement in either producer touches it, while the shared encoder rebuilds a
        //       dropped pad as blanks. The bytes therefore differ from the reference's on a span that
        //       carries no data at all; what must hold, and what is asserted, is that the span EXISTS
        //       and is uniform, because a reader of a fixed-length dataset positions the next record
        //       by it.
        assertThat(record).hasSize(TRANSACTION_RECORD_LENGTH);
        assertThat(spec.field("FILLER").start()).isEqualTo(TRAILING_PAD_OFFSET);
        assertThat(distinctBytes(record, TRAILING_PAD_OFFSET, TRANSACTION_RECORD_LENGTH))
                .containsExactly(' ');
    }

    /**
     * A negative amount survives the sign overpunch and decodes back to the value stored.
     *
     * <p>Pins {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:10}, an eleven-byte zoned
     * field whose sign is carried as an overpunch in its last byte. Section 5.2 of
     * {@code tests/README.md} records that the wrong sign convention corrupts negative balances
     * silently, so the negative case is what separates the two conventions.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("round-trip a negative amount through the sign overpunch")
    void roundTripANegativeAmountThroughTheSignOverpunch() throws Exception {
        seed(interestRow(INTEREST_HYPHEN_ID, NEGATIVE_AMOUNT),
                postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));

        List<byte[]> records = splitIntoRecords(runAndCaptureStagedPayload());
        CopybookLayout.FieldSpec amount = CopybookLayout.layout("TRAN").field("TRAN-AMT");

        // WHY : Assumptions: the span is asserted to hold NO minus character, because the overpunch is
        //       the whole point -- the sign occupies the same byte as the final digit rather than a
        //       byte of its own, so a leading or trailing minus would mean eleven bytes had become
        //       twelve digits' worth of information and every field behind it would be shifted.
        String negativeSpan = new String(recordFor(records, INTEREST_HYPHEN_ID), amount.start(),
                amount.length(), StandardCharsets.US_ASCII);
        assertThat(negativeSpan).hasSize(11).doesNotContain("-");
        assertThat(ZonedDecimalCodec.decode(negativeSpan, amount.intDigits(), amount.decDigits(),
                amount.signed())).isEqualByComparingTo(NEGATIVE_AMOUNT);

        String positiveSpan = new String(recordFor(records, POSTED_SEED_ID), amount.start(),
                amount.length(), StandardCharsets.US_ASCII);
        assertThat(ZonedDecimalCodec.decode(positiveSpan, amount.intDigits(), amount.decDigits(),
                amount.signed())).isEqualByComparingTo(POSITIVE_AMOUNT);
        assertThat(negativeSpan).isNotEqualTo(positiveSpan);
    }

    /**
     * Both producers' descriptions carry the same pad, and the layout name changes no byte of it.
     *
     * <p>Pins section 6.3 of the fixture contract, which measures the reference's description pad as
     * job-dependent: the accrual pass leaves the tail of the field at low values because it assembles
     * the text with a string verb, while the posting pass blank-pads it because it moves an
     * already-padded field. This case records what the migrated encoder actually emits for the two row
     * classes standing side by side in one stream, which is a thing only this artefact can show.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("emit one description pad for both row classes, whichever layout is named")
    void emitOneDescriptionPadForBothRowClasses() throws Exception {
        // WHY : Assumptions: the two registered layouts differ in ONE COMPARISON FLAG and in nothing
        //       that reaches the emitted bytes. Section 8.1 of the fixture contract records the
        //       derivation, and CopybookLayout.java lines 1843-1844 declare it as
        //       TRAN_LAYOUT.withFieldFlags("INTTRAN", "TRAN-ORIG-TS", true, false) -- a flip of the
        //       normalise marker on one field -- so every offset, width and storage kind is shared and
        //       the description is a plain text field under both, which the encoder blank-pads either
        //       way. The measured consequence is asserted below: naming the derived layout produces a
        //       byte-identical image, so the reference's job-dependent pad is NOT expressible by
        //       choosing a layout.
        // WHY : Trade-offs: the reference's low-value pad, which section 6.3 of the fixture contract
        //       measures as exactly 76 NUL bytes behind the accrual description, is therefore not
        //       reproduced, and the difference is recorded rather than faked. It cannot be recovered
        //       from a relational row at all: the description is stored as text with no memory of how
        //       its writer padded it, and the job encodes every row it reads through one call that
        //       names no layout. Faking the distinction -- by guessing the producer from the
        //       description's leading text -- would make the artefact depend on a literal rather than
        //       on the schema, and would misclassify any row whose text happened to match.
        seed(interestRow(INTEREST_HYPHEN_ID, NEGATIVE_AMOUNT),
                postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));

        List<byte[]> records = splitIntoRecords(runAndCaptureStagedPayload());
        CopybookLayout.FieldSpec description = CopybookLayout.layout("TRAN").field("TRAN-DESC");
        int padStart = description.start() + INTEREST_DESCRIPTION.length();
        int padEnd = description.start() + description.length();

        byte[] interestRecord = recordFor(records, INTEREST_HYPHEN_ID);
        byte[] postedRecord = recordFor(records, POSTED_SEED_ID);
        assertThat(fieldOf(interestRecord, "TRAN-DESC"))
                .isEqualTo(blankPadded(INTEREST_DESCRIPTION, description.length()));
        assertThat(distinctBytes(interestRecord, padStart, padEnd)).containsExactly(' ');
        assertThat(distinctBytes(postedRecord,
                description.start() + POSTED_DESCRIPTION.length(), padEnd)).containsExactly(' ');

        assertThat(CopybookLayout.layout("INTTRAN").field("TRAN-ORIG-TS").normalizeTs()).isTrue();
        assertThat(CopybookLayout.layout("TRAN").field("TRAN-ORIG-TS").normalizeTs()).isFalse();
        assertThat(CopybookLayout.layout("INTTRAN").field("TRAN-DESC")).isEqualTo(description);
    }

    /**
     * One run allocates exactly one new generation, of the combined family and of no other.
     *
     * <p>Pins {@code DISP=(NEW,CATLG,DELETE)} at {@code app/jcl/COMBTRAN.jcl:33} together with the
     * {@code (+1)} reference at {@code :37}, against the base the reference defines in
     * {@code app/jcl/DEFGDGB.jcl:55-57}. The family is asserted explicitly because ten families exist
     * and a coordinate naming the wrong one would place the night's records under a key no reader of
     * this artefact looks beneath.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("allocate exactly one new generation of the combined family")
    void allocateExactlyOneNewGenerationOfTheCombinedFamily() throws Exception {
        // WHY : Assumptions: ONE generation per run is a requirement rather than an incidental fact,
        //       and the reason is in the notation. app/jcl/COMBTRAN.jcl names TRANSACT.COMBINED(+1)
        //       TWICE -- at :37 as the sort's output and again at :44 as the copy's input -- and within
        //       one job a relative reference addresses the SAME physical generation, which is how the
        //       reference's second step reads what its first step wrote. A run that allocated twice
        //       would break that identity and would additionally consume a second of the retained
        //       generations, shortening the history an operator can restore from.
        seed(postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));

        runCombine();

        verify(this.generations, times(1)).allocateNewGeneration(
                any(DatasetFamily.class), any(BusinessDate.class), anyString());
        verify(this.generations, times(1)).allocateNewGeneration(
                DatasetFamily.TRANSACT_COMBINED, BUSINESS_DATE, RUN_ID);
        assertThat(this.stagedPayloads).hasSize(1);
        assertThat(this.stagedPayloads.get(0).generation().family())
                .isEqualTo(DatasetFamily.TRANSACT_COMBINED);
        assertThat(this.stagedPayloads.get(0).objectName())
                .isEqualTo(CombineTransactionsJob.DATASET_OBJECT_NAME);
    }

    /**
     * The staged coordinate renders the date partition and the zero-padded generation.
     *
     * <p>Pins the key convention {@code com.carddemo.batch.dto.DatasetGeneration} publishes, applied
     * to the family {@code app/jcl/COMBTRAN.jcl:37} names. The generation number is rendered to a fixed
     * width because object keys are only ever ordered lexicographically, so an unpadded tenth
     * generation would sort below the second and a reader taking the last key as the current one would
     * read the wrong artefact.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("stage under the date partition and the zero-padded generation prefix")
    void stageUnderTheDatePartitionAndPaddedGenerationPrefix() throws Exception {
        // WHY : Assumptions: the retention rule that keeps five generations is provisioned as bucket
        //       versioning and a lifecycle configuration by infra/modules/s3-datasets, and is not this
        //       job's responsibility; the retained count and the per-run memoisation that keeps one
        //       run's numbering stable are both settled by
        //       com.carddemo.batch.service.DatasetGenerationServiceTest. What is asserted here is only
        //       the job-level observable -- the coordinate a run stages under.
        seed(postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));

        runCombine();

        DatasetGeneration staged = this.stagedPayloads.get(0).generation();
        assertThat(staged.datePartitionSegment()).isEqualTo("dt=" + BUSINESS_DATE_TOKEN);
        assertThat(staged.generationSegment()).isEqualTo("gen=0005");
        assertThat(staged.keyPrefix())
                .isEqualTo(DatasetFamily.TRANSACT_COMBINED.pathSegment()
                        + "dt=" + BUSINESS_DATE_TOKEN + "/gen=0005/");
    }

    /**
     * A clean run reports the clean tier, both as the graded value and as the process status.
     *
     * <p>Pins that this state has no soft-warn outcome to report. A staging step either wrote its
     * generation or did not, so the graded middle tier the posting pass reaches at
     * {@code app/cbl/CBTRN02C.cbl:229-230} has no counterpart here, and a warning arriving from this
     * job would be a defect rather than a business outcome.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("report the clean tier and never the warn tier")
    void reportTheCleanTierAndNeverTheWarnTier() throws Exception {
        // WHY : Trade-offs: the tier is asserted as a RETURNED VALUE and as a PROCESS EXIT STATUS, and
        //       never as a tolerance configured on a test runner. The parity oracle grades a run across
        //       five tiers and aggregates the worst seen, documented in section 8 of tests/README.md,
        //       and borrowing that vocabulary here would let a real failure be configured to read as an
        //       accepted warning. What is given up is the ability to report a partially successful
        //       build; what is bought is that this gate stays binary, which is the only thing a build
        //       step can honestly be.
        // WHY : Assumptions: the mapping from an execution to a status tier, BatchApplication's
        //       exitStatusOf, is package-private to com.carddemo.batch and is therefore unreachable
        //       from com.carddemo.batch.job, so the TWO INPUTS it reads are asserted directly instead
        //       -- the batch status, which it consults first because only that answers completion, and
        //       the framework exit code it compares against the warning constant. Asserting both inputs
        //       pins the same outcome the entry point computes without widening another package's
        //       visibility to suit a test.
        seed(interestRow(INTEREST_HYPHEN_ID, NEGATIVE_AMOUNT),
                postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));

        JobExecution execution = runCombine();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isNotEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
        assertThat(BatchReturnCode.CLEAN.numericValue())
                .isEqualTo(BatchApplication.EXIT_STATUS_CLEAN)
                .isNotEqualTo(BatchApplication.EXIT_STATUS_SOFT_WARN);
        assertThat(recordedStep().getReturnCode()).isEqualTo((short) 0);
    }

    /**
     * A failure at the staging seam reports the hard-failure tier and never the warn tier.
     *
     * <p>Pins that a step that could not write its generation fails outright. The reference's own warn
     * gate is a predecessor's tier read by {@code app/jcl/TRANBKP.jcl:51}, not an outcome this step
     * produces, so the only tiers reachable here are clean and hard failure.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("report the hard-failure tier when the generation cannot be staged")
    void reportTheHardFailureTierWhenTheGenerationCannotBeStaged() throws Exception {
        seed(postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));
        when(this.generations.stageDataset(any(DatasetGeneration.class), anyString(), any(Path.class)))
                .thenThrow(new IllegalStateException("the dataset sink refused the payload"));

        JobExecution execution = runCombine();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(BatchReturnCode.HARD_FAILURE.numericValue())
                .isGreaterThanOrEqualTo(BatchApplication.EXIT_STATUS_HARD_FAILURE)
                .isNotEqualTo(BatchApplication.EXIT_STATUS_SOFT_WARN);
        // WHY : Assumptions: the failed tier is read from the durable row rather than from the
        //       execution, because BatchStepLedgerWriter annotates each write
        //       @Transactional(propagation = REQUIRES_NEW) precisely so the row survives the rollback
        //       of the work that failed. Reading it here is what proves the failure was recorded
        //       durably and not merely raised.
        assertThat(recordedStep().getStatus()).isEqualTo(BatchRun.BatchRunStatus.FAILED);
        assertThat(recordedStep().getReturnCode())
                .isEqualTo((short) BatchReturnCode.HARD_FAILURE.numericValue());
    }

    /**
     * An empty relation produces a valid empty generation and a clean run.
     *
     * <p>Pins that a night with nothing to combine is a success. The reference's sort over two empty
     * inputs writes an empty output and sets no non-zero code, so a migrated step that failed or
     * warned on an empty relation would gate the chain on a condition the reference accepts.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("stage an empty generation and report clean when the relation is empty")
    void stageAnEmptyGenerationAndReportCleanWhenTheRelationIsEmpty() throws Exception {
        JobExecution execution = runCombine();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isNotEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
        assertThat(this.stagedPayloads).hasSize(1);
        assertThat(this.stagedPayloads.get(0).body()).isEmpty();
        assertThat(this.stagedPayloads.get(0).generation().family())
                .isEqualTo(DatasetFamily.TRANSACT_COMBINED);
        assertThat(recordedStep().getReturnCode()).isEqualTo((short) 0);
    }

    /**
     * The durable ledger holds one row for the run and step, carrying the graded outcome.
     *
     * <p>Pins the key the ledger is unique on. Its purpose is that a redriven state can tell whether
     * this step of this run already reached a terminal outcome, which is the question a restart asks
     * before doing anything.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("record one durable row for the run and the step")
    void recordOneDurableRowForTheRunAndTheStep() throws Exception {
        // WHY : Refactoring Rationale: this ledger is a STRICT IMPROVEMENT on the reference and not a
        //       port of anything, and saying so is what stops a reader from hunting for the baseline
        //       contract it reproduces. The only restart operand anywhere in the thirty-eight reference
        //       jobs is commented out, at app/jcl/DEFGDGD.jcl:2, and no checkpoint operand appears in
        //       any of them -- so the reference offers no resumption contract at all, and a durable
        //       per-step record is new capability rather than migrated behaviour.
        seed(postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));

        runCombine();

        assertThat(this.runs.findAll()).hasSize(1);
        BatchRun recorded = recordedStep();
        assertThat(recorded.getRunId()).isEqualTo(RUN_ID);
        assertThat(recorded.getStepName()).isEqualTo(CombineTransactionsJob.STEP_NAME);
        assertThat(recorded.getStatus()).isEqualTo(BatchRun.BatchRunStatus.COMPLETED);
        assertThat(recorded.getReturnCode()).isEqualTo((short) BatchReturnCode.CLEAN.numericValue());
        assertThat(recorded.getFinishedAt()).isNotNull();
    }

    /**
     * A repeat of the same run and step is skipped rather than recorded a second time.
     *
     * <p>Pins the idempotency the redrive of a failed execution relies on. A second pass would allocate
     * a second generation and consume one of the retained generations of the family, so the run would
     * silently shorten the history an operator can restore from without any step reporting it.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("skip a repeat of the same run and step instead of duplicating it")
    void skipARepeatOfTheSameRunAndStepInsteadOfDuplicatingIt() throws Exception {
        seed(postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));

        JobExecution first = runCombine();
        JobExecution second = runCombine();

        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.runs.findAll()).hasSize(1);
        assertThat(recordedStep().getStatus()).isEqualTo(BatchRun.BatchRunStatus.COMPLETED);
        // WHY : Assumptions: the second pass is asserted to have staged NOTHING, because the row count
        //       alone cannot distinguish a skipped body from a body that ran again and happened to
        //       reuse the same durable row. A second staged payload would mean a second generation was
        //       written under a second coordinate, which is the observable the retention rule pays for.
        assertThat(this.stagedPayloads).hasSize(1);
        verify(this.generations, times(1)).allocateNewGeneration(
                any(DatasetFamily.class), any(BusinessDate.class), anyString());
    }

    /**
     * The job refuses to run without the business-date token, even though its driver passes none.
     *
     * <p>Pins the migrated parameter contract against the reference's absence of one.
     * {@code app/jcl/COMBTRAN.jcl} carries NO parameter operand anywhere -- only
     * {@code app/jcl/INTCALC.jcl:22} does, as {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'} --
     * so a reader comparing the two would expect this job to accept a launch without one.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("refuse to run without the business-date token")
    void refuseToRunWithoutTheBusinessDateToken() throws Exception {
        // WHY : Refactoring Rationale: the token is required here although app/jcl/COMBTRAN.jcl passes
        //       none -- only app/jcl/INTCALC.jcl:22 carries a PARM= in this chain -- and the
        //       requirement is earned rather than inherited. The migrated step partitions its
        //       generation under a date segment, so without the token there is no key to write the
        //       artefact beneath; the reference needed no parameter because its output was addressed by
        //       a catalogued generation name that the system resolved. Treating the option as optional
        //       would contradict the shared validator this job is built with: BatchConfig lines 404-407
        //       construct it over the business-date and run-identifier keys as REQUIRED for every one
        //       of the module's jobs.
        // WHY : Assumptions: no run predicate is asserted anywhere in this class, and the absence is
        //       recorded so it does not read as an oversight. app/jcl/COMBTRAN.jcl carries no condition
        //       operand at all -- not on its sort step and not on the copy step at :41 -- so this state
        //       contributes no condition-code inversion example; the single instance in the whole
        //       reference is app/jcl/TRANBKP.jcl:51 and it belongs to the backup job's own case.
        seed(postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));

        JobParameters withoutDate = new JobParametersBuilder()
                .addString(BatchConfig.RUN_ID_PARAMETER, RUN_ID, false)
                .toJobParameters();
        JobExecution execution = execute(buildJob(), withoutDate);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(failure -> assertThat(failure.getMessage())
                        .contains(BatchApplication.BUSINESS_DATE_PARAMETER));
        verify(this.generations, never()).stageDataset(
                any(DatasetGeneration.class), anyString(), any(Path.class));
    }

    /**
     * No clock reading reaches the emitted image; every stamp is the value the row carried.
     *
     * <p>Pins that this step is a copy and not a producer. Section 8.1 of the fixture contract records
     * that the two stamps are volatile in the two producers -- the posting pass generates the
     * processing stamp and the accrual pass generates both -- and that a comparison must mask exactly
     * the volatile one. This artefact needs NO masking, because both stamps arrive from rows that were
     * committed before the step began.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("copy both timestamps from the stored rows and read no clock")
    void copyBothTimestampsFromTheStoredRowsAndReadNoClock() throws Exception {
        seed(interestRow(INTEREST_HYPHEN_ID, NEGATIVE_AMOUNT),
                postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));

        List<byte[]> records = splitIntoRecords(runAndCaptureStagedPayload());

        // WHY : Assumptions: the seeded stamps are fixed values in the past, so a clock read anywhere
        //       on this path would produce a span that cannot equal them. That is what makes the
        //       equality below a real check rather than a restatement -- the assertion has a way to
        //       fail, and it fails on exactly the defect it exists for.
        for (String identifier : List.of(INTEREST_HYPHEN_ID, POSTED_SEED_ID)) {
            byte[] record = recordFor(records, identifier);
            assertThat(fieldOf(record, "TRAN-ORIG-TS")).isEqualTo(SEEDED_ORIGINATION_STAMP);
            assertThat(fieldOf(record, "TRAN-PROC-TS")).isEqualTo(SEEDED_PROCESSING_STAMP);
        }
    }

    /**
     * Runs the job once under the standard run identifier and returns the single staged payload.
     *
     * @return the bytes handed to the staging seam, never {@code null}
     *
     * @throws Exception if the framework's own execution path raises, which the caller declares rather than absorbs
     */
    private byte[] runAndCaptureStagedPayload() throws Exception {
        JobExecution execution = runCombine();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.stagedPayloads).hasSize(1);
        return this.stagedPayloads.get(0).body();
    }

    /**
     * Runs the job once under the standard run identifier.
     *
     * @return the finished job execution, never {@code null}
     *
     * @throws Exception if the framework's own execution path raises, which the caller declares rather than absorbs
     */
    private JobExecution runCombine() throws Exception {
        return runCombine(RUN_ID);
    }

    /**
     * Runs the job once under a caller-chosen run identifier.
     *
     * @param runId the orchestrator execution identifier to launch under, of type {@code String},
     *     which is also the value the durable step ledger keys its row on; must not be {@code null}
     * @return the finished job execution, never {@code null}
     *
     * @throws Exception if the framework's own execution path raises, which the caller declares rather than absorbs
     */
    private JobExecution runCombine(String runId) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER, BUSINESS_DATE_TOKEN, true)
                .addString(BatchConfig.RUN_ID_PARAMETER, runId, false)
                .toJobParameters();
        return execute(buildJob(), parameters);
    }

    /**
     * Builds the job under test over the real relation, the mocked seam and the real step ledger.
     *
     * @return the registered job, never {@code null}
     */
    private Job buildJob() {
        return new CombineTransactionsJob(this.ledger, this.generations, this.ledgerOfSteps)
                .combineTransactions(this.jobRepository, this.transactionManager, this.validator);
    }

    /**
     * Registers an execution for one job and runs it to completion or failure.
     *
     * <p>Assumptions: the instance and the execution are constructed directly and then registered,
     * matching the sibling launcher cases in this package, because the repository's own creation method
     * takes an instance rather than a name. The framework identifiers are drawn from a counter so that
     * two launches inside one case are two distinct executions rather than one replayed.</p>
     *
     * @param job the job to run; must not be {@code null}
     * @param parameters the parameters to launch with, of type {@code JobParameters}, which the job's
     *     own validator inspects before its step starts; must not be {@code null}
     * @return the finished job execution, never {@code null}
     *
     * @throws Exception if the framework's own execution path raises, which the caller declares rather than absorbs
     */
    private JobExecution execute(Job job, JobParameters parameters) throws Exception {
        this.launchCounter++;
        JobInstance instance = new JobInstance((long) this.launchCounter, job.getName());
        JobExecution execution =
                new JobExecution((long) this.launchCounter, instance, parameters);
        this.jobRepository.update(execution);
        job.execute(execution);
        return execution;
    }

    /**
     * Commits the given rows into the relation so a subsequent run reads them.
     *
     * @param rows the transactions to persist, of type {@code Transaction}, each carrying a distinct
     *     identifier because the relation's primary key is that column alone; must not be {@code null}
     *     and must contain no {@code null} element
     */
    private void seed(Transaction... rows) {
        // WHY : Assumptions: the seed is COMMITTED rather than left to a rolled-back wrapper, because
        //       the job opens its own transaction and reads through a cursor; rows visible only inside
        //       an uncommitted wrapper would not be there when that cursor was opened, and the run
        //       would stage an empty artefact while every seeded row still appeared present to the case.
        this.transactionTemplate.executeWithoutResult(status -> this.ledger.saveAll(List.of(rows)));
    }

    /**
     * Builds one posted transaction, the row class the posting pass commits.
     *
     * @param transactionId the sixteen-character identifier the row is keyed on, of type
     *     {@code String}; must not be {@code null}
     * @param amount the monetary value, of type {@code BigDecimal} at scale two, which may be negative
     *     so the sign overpunch is exercised; must not be {@code null}
     * @return the transaction, never {@code null}
     */
    private static Transaction postedRow(String transactionId, BigDecimal amount) {
        return row(transactionId, POSTED_CATEGORY_CD, POSTED_SOURCE, POSTED_DESCRIPTION, amount);
    }

    /**
     * Builds one interest-generated transaction, the row class the accrual pass commits.
     *
     * <p>Assumptions: the description is the shape the accrual pass assembles and the identifier is
     * expected to carry the business-date token, but neither the description's assembly nor the
     * identifier's arithmetic is asserted anywhere here -- both belong to
     * {@code com.carddemo.batch.service.InterestCalculationServiceTest}, and this method only needs a
     * row that is recognisably of that class.</p>
     *
     * @param transactionId the sixteen-character identifier the row is keyed on, of type
     *     {@code String}; must not be {@code null}
     * @param amount the accrued value, of type {@code BigDecimal} at scale two, which may be negative;
     *     must not be {@code null}
     * @return the transaction, never {@code null}
     */
    private static Transaction interestRow(String transactionId, BigDecimal amount) {
        return row(transactionId, INTEREST_CATEGORY_CD, INTEREST_SOURCE, INTEREST_DESCRIPTION,
                amount);
    }

    /**
     * Builds one transaction with every mapped column populated.
     *
     * <p>Assumptions: every column is populated rather than only the structurally required ones,
     * because the emitted image is asserted field by field and a mostly-blank record would leave most
     * of those assertions comparing one pad against another. The processing stamp in particular is
     * mandatory on this table, so a row without one cannot be committed at all.</p>
     *
     * @param transactionId the sixteen-character identifier the row is keyed on, of type
     *     {@code String}; must not be {@code null}
     * @param categoryCd the four-character category code, of type {@code String}; must not be
     *     {@code null}
     * @param source the source label, of type {@code String}, no wider than the ten characters the
     *     copybook declares; must not be {@code null}
     * @param description the description text, of type {@code String}, no wider than the hundred
     *     characters the copybook declares; must not be {@code null}
     * @param amount the monetary value, of type {@code BigDecimal} at scale two; must not be
     *     {@code null}
     * @return the transaction, never {@code null}
     */
    private static Transaction row(String transactionId, String categoryCd, String source,
            String description, BigDecimal amount) {
        Transaction transaction = new Transaction(transactionId);
        transaction.setTypeCd(TYPE_CD);
        transaction.setCategoryCd(categoryCd);
        transaction.setSource(source);
        transaction.setDescription(description);
        transaction.setAmount(amount);
        transaction.setMerchantId(MERCHANT_ID);
        transaction.setMerchantName(MERCHANT_NAME);
        transaction.setMerchantCity(MERCHANT_CITY);
        transaction.setMerchantZip(MERCHANT_ZIP);
        transaction.setCardNum(CARD_NUM);
        transaction.setOrigTs(SEEDED_ORIGINATION_TIME);
        transaction.setProcTs(SEEDED_PROCESSING_TIME);
        return transaction;
    }

    /**
     * Builds one generation coordinate of a family under the injected business date.
     *
     * @param family the family the coordinate belongs to, of type {@code DatasetFamily}; must not be
     *     {@code null}
     * @param number the generation number the coordinate carries, of type {@code int}, within the
     *     range the coordinate itself admits
     * @return the coordinate, never {@code null}
     */
    private static DatasetGeneration generation(DatasetFamily family, int number) {
        return new DatasetGeneration(family, BUSINESS_DATE, number);
    }

    /**
     * Splits one staged image into its fixed-length records.
     *
     * <p>Assumptions: the image is required to be a whole multiple of the declared length, and the
     * requirement is asserted here rather than assumed, because a stream whose length is not a multiple
     * cannot be a fixed-length dataset at all and every offset-based assertion downstream of this
     * method would then be reading across a record boundary.</p>
     *
     * @param image the whole staged payload, of type {@code byte[]}; must not be {@code null}
     * @return the records in the order they appear in the image, never {@code null}
     */
    private static List<byte[]> splitIntoRecords(byte[] image) {
        assertThat(image.length % TRANSACTION_RECORD_LENGTH)
                .withFailMessage("the staged image is %d bytes, which is not a whole multiple of the"
                        + " %d-byte record app/cpy/CVTRA05Y.cpy:2 declares",
                        image.length, TRANSACTION_RECORD_LENGTH)
                .isZero();

        List<byte[]> records = new ArrayList<>();
        for (int offset = 0; offset < image.length; offset += TRANSACTION_RECORD_LENGTH) {
            records.add(Arrays.copyOfRange(image, offset, offset + TRANSACTION_RECORD_LENGTH));
        }
        return records;
    }

    /**
     * Reads the sort key from one record.
     *
     * <p>Assumptions: the key is the record's FIRST sixteen bytes, and the coordinate conversion is
     * stated because an off-by-one here is silent. The symbol {@code TRAN-ID,1,16,CH} at
     * {@code app/jcl/COMBTRAN.jcl:28} is expressed in the sort utility's ONE-based positions, so
     * position one is zero-based offset zero -- which is where {@code app/cpy/CVTRA05Y.cpy:5} places
     * the identifier.</p>
     *
     * @param record one whole record image, of type {@code byte[]}; must not be {@code null}
     * @return the sixteen-character identifier, never {@code null}
     */
    private static String identifierOf(byte[] record) {
        return new String(record, 0, TRANSACTION_ID_LENGTH, StandardCharsets.US_ASCII);
    }

    /**
     * Selects the one record carrying a given identifier.
     *
     * @param records the records of one staged image, of type {@code List<byte[]>}; must not be
     *     {@code null}
     * @param transactionId the sixteen-character identifier to select, of type {@code String}; must not
     *     be {@code null}
     * @return the matching record, never {@code null}
     */
    private static byte[] recordFor(List<byte[]> records, String transactionId) {
        List<byte[]> matches = records.stream()
                .filter(record -> identifierOf(record).equals(transactionId))
                .toList();
        // WHY : Assumptions: EXACTLY one match is required, and the count is asserted here rather than
        //       taking the first. The relation keys on the identifier alone, so a second record
        //       carrying it would mean the step emitted a row twice -- which is the precise failure the
        //       omitted load-back guards against, and taking the first match would conceal it from
        //       every case that reads a record through this helper.
        assertThat(matches)
                .withFailMessage("expected exactly one record for identifier '%s' but the staged"
                        + " image holds %d", transactionId, matches.size())
                .hasSize(1);
        return matches.get(0);
    }

    /**
     * Reads one named field's whole declared span from a record, pad included.
     *
     * @param record one whole record image, of type {@code byte[]}; must not be {@code null}
     * @param fieldName the copybook field name to read, of type {@code String}, which must be declared
     *     by the registered layout; must not be {@code null}
     * @return the field's characters including any pad, never {@code null}
     */
    private static String fieldOf(byte[] record, String fieldName) {
        CopybookLayout.FieldSpec field = CopybookLayout.layout("TRAN").field(fieldName);
        return new String(record, field.start(), field.length(), StandardCharsets.US_ASCII);
    }

    /**
     * Sorts identifiers the way the reference's character sort format compares them.
     *
     * <p>Assumptions: the comparison is over UNSIGNED byte values, which is what the reference's
     * {@code CH} format at {@code app/jcl/COMBTRAN.jcl:28} performs. Java's own byte type is signed, so
     * comparing raw bytes would place every value above the seven-bit range below every value inside
     * it -- an inversion no identifier in this fixture reaches, but one that would make the helper wrong
     * for a record that did.</p>
     *
     * @param identifiers the identifiers to order, of type {@code List<String>}; must not be
     *     {@code null}
     * @return a new list holding the same identifiers in ascending byte-wise order, never {@code null}
     */
    private static List<String> byteWiseAscending(List<String> identifiers) {
        return identifiers.stream()
                .sorted(Comparator.comparing(
                        (String candidate) -> candidate.getBytes(StandardCharsets.US_ASCII),
                        Arrays::compareUnsigned))
                .toList();
    }

    /**
     * Lists the distinct byte values occupying one span of a record.
     *
     * @param record one whole record image, of type {@code byte[]}; must not be {@code null}
     * @param from the inclusive zero-based start of the span, of type {@code int}
     * @param to the exclusive zero-based end of the span, of type {@code int}
     * @return the distinct values as characters, in ascending order, never {@code null}
     */
    private static List<Character> distinctBytes(byte[] record, int from, int to) {
        List<Character> distinct = new ArrayList<>();
        for (int offset = from; offset < to; offset++) {
            // WHY : Assumptions: the byte is masked before it is widened, because Java's byte type is
            //       signed and a pad byte above the seven-bit range would otherwise widen to a negative
            //       value and read as a character nothing in the record carries. The pads asserted here
            //       are inside that range, so the mask changes no current result; it is written so the
            //       helper stays correct for the NUL pad that section 6.1 of the fixture contract
            //       measures on this record in the reference's own output.
            char value = (char) (record[offset] & 0xFF);
            if (!distinct.contains(value)) {
                distinct.add(value);
            }
        }
        return distinct.stream().sorted().toList();
    }

    /**
     * Renders one value as the encoder writes it into a text field: right-padded with blanks.
     *
     * <p>Assumptions: blanks are the pad the shared encoder writes for a text field, which is what
     * makes this the correct expectation for a Java-produced image. Section 6.1 of the fixture contract
     * measures the reference's own pad on some records as a different byte, so this helper states the
     * MIGRATED contract and must not be read as a claim about the reference's bytes.</p>
     *
     * @param value the value the field carries, of type {@code String}, no wider than the field; must
     *     not be {@code null}
     * @param width the field's declared width, of type {@code int}, taken from the registered layout
     *     rather than written out at the call site
     * @return the value padded to the declared width, never {@code null}
     */
    private static String blankPadded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Renders every committed row as one comparable string, in byte-wise identifier order.
     *
     * <p>Assumptions: the ordering clause pins the byte-wise collation explicitly rather than
     * inheriting the database default, so that two readings taken before and after a run are compared
     * in one fixed sequence whatever the engine's default comparison is.</p>
     *
     * @return one string per committed row, never {@code null}
     */
    private List<String> committedRowImages() {
        // WHY : Assumptions: the row's INSERTING TRANSACTION STAMP -- the engine's own xmin system
        //       column, projected in the statement below -- is rendered alongside its values, and it is
        //       what makes this reading sensitive to a write that changes nothing. The engine assigns a
        //       fresh xmin whenever a row version is written, so an update carrying the identical values
        //       still moves it.
        // WHY : Refactoring Rationale: this helper compared values only, and a measurement showed that
        //       insufficient. A load-back would write each record image back through the record mapper,
        //       and that round trip was measured to be exactly value-preserving -- the mapper drops each
        //       field's pad on the way in and the encoder rebuilds it on the way out -- so every column
        //       compared equal and a ported load-back passed unnoticed. The stamp closes that gap: the
        //       claim under test is that this job performs NO write, not merely that no value it can
        //       observe ended up different.
        return this.jdbc.queryForList(
                "SELECT transaction_id || '|' || xmin::text || '|' || amount || '|' || description"
                        + " || '|' || proc_ts"
                        + " FROM ledger.transactions ORDER BY transaction_id COLLATE \"C\"",
                String.class);
    }

    /**
     * Reads the durable ledger row this run recorded for this step.
     *
     * @return the recorded row, never {@code null}
     * @throws java.util.NoSuchElementException if no row exists for the run and step, which means the
     *     step ledger was bypassed rather than that the assertion under way is wrong
     */
    private BatchRun recordedStep() {
        return this.runs
                .findByRunIdAndStepName(RUN_ID, CombineTransactionsJob.STEP_NAME)
                .orElseThrow();
    }

    /**
     * One payload handed to the staging seam, captured with the coordinate it was staged under.
     *
     * @param generation the coordinate the payload was staged under, never {@code null}
     * @param objectName the object name within that generation, never {@code null}
     * @param body the payload's bytes, read before the job removed its temporary copy, never
     *     {@code null}
     */
    private record StagedPayload(DatasetGeneration generation, String objectName, byte[] body) {
    }

    /**
     * The narrow context these cases run against: the two repositories and the durable step ledger.
     *
     * <p>Assumptions: the job under test is NOT a bean of this context and is constructed by the case
     * instead, so that the staging seam can be a test stand-in while the relation and the step ledger
     * are real. Scanning the production job package would register all seven jobs and would require a
     * real generation service, which would need a bucket this module's tests deliberately have no way
     * to provide.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.batch.domain")
    @EnableJpaRepositories("com.carddemo.batch.repository")
    static class CombinedGenerationTestApplication {

        /**
         * Supplies the clock the durable ledger stamps its rows from.
         *
         * @return a clock reading the coordinated universal time zone, never {@code null}
         */
        @Bean
        Clock batchClock() {
            // WHY : Assumptions: a real clock is supplied rather than a fixed one, because no assertion
            //       in this class reads a ledger timestamp for its value -- only for presence. A fixed
            //       clock would additionally make the started and finished stamps equal, and the
            //       ledger's own constraint admits that, so the substitution would buy nothing and
            //       would hide a genuine ordering defect if one ever appeared.
            return Clock.systemUTC();
        }

        /**
         * Registers the durable ledger's writer over the real repository.
         *
         * @param runs the step-ledger repository the writer persists through; must not be {@code null}
         * @param clock the clock the writer stamps rows from; must not be {@code null}
         * @return the production writer, never {@code null}
         */
        @Bean
        BatchStepLedgerWriter batchStepLedgerWriter(BatchRunRepository runs, Clock clock) {
            // WHY : Assumptions: the writer is registered as a BEAN and never constructed inline,
            //       because each of its methods declares a propagation that opens a transaction of its
            //       own and that propagation is applied by a proxy. A directly constructed instance is
            //       unproxied, so its writes would join the step's transaction and would be discarded
            //       with it on the failure path -- which is the one path whose durable record this
            //       class asserts.
            return new BatchStepLedgerWriter(runs, clock);
        }

        /**
         * Registers the durable step ledger with no failure sink attached.
         *
         * @param writer the ledger's writer; must not be {@code null}
         * @return the production ledger, never {@code null}
         */
        @Bean
        BatchStepLedger batchStepLedger(BatchStepLedgerWriter writer) {
            // WHY : Assumptions: no failure sink is supplied, and the empty holder is the supported
            //       state rather than a gap. Lines 729-731 of
            //       services/batch-service/src/test/resources/application-test.yml record that the
            //       profile sets no carddemo.messaging.* key of any kind, which leaves SqsConfig's
            //       conditional queue beans out of every context this package builds, so a deployed
            //       sink is exactly what is absent here.
            return new BatchStepLedger(writer, Optional.empty());
        }
    }
}
