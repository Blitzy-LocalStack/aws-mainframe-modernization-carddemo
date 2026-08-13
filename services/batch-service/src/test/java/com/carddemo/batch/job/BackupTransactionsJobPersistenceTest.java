package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.repository.BatchRunRepository;
import com.carddemo.batch.repository.TransactionCategoryBalanceRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.BatchStepLedgerWriter;
import com.carddemo.batch.service.DatasetGenerationService;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.ZonedDecimalCodec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
 * Pins the backup step's persistence contract against a real relation rather than against a double.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: {@code app/jcl/TRANBKP.jcl:37-46} deletes the transaction cluster and its alternate index
 * and {@code :51-60} re-creates the cluster empty, and {@link BackupTransactionsJob} deliberately
 * reproduces neither -- a relational target needs no periodic reallocation and PostgreSQL maintains its
 * indexes in the same commit as the row change. Ported literally, that pair would empty
 * {@code ledger.transactions} on every nightly run, so the omission is the single most consequential
 * decision the step carries. This class is where the omission is proven against the ENGINE: it seeds
 * distinguishable rows, launches the real job over the real relation, and compares every persisted
 * column of every row before and after.</p>
 *
 * <p>Refactoring Rationale: the claim already existed in the sibling
 * {@link BackupTransactionsJobTest}, asserted against a list the test itself owned plus an exhaustive
 * Mockito interaction check. That check is genuinely strict in one direction -- it fails on ANY mutating
 * call the job is not supposed to make, including one added to the repository interface later -- but it
 * cannot establish the claim a reader takes from it, which is that the rows are still in the table
 * afterwards. A repository double answers whatever it was told to answer, so a job that truncated
 * through a mechanism no interaction on that interface expresses -- a native statement, a cascade, a
 * schema-level action -- would satisfy every verification there. Both halves are kept, in the two places
 * each is provable: the interaction census stays there and the surviving rows are read back here.</p>
 *
 * <p>Assumptions: this class carries the PLAIN test suffix and starts a database container, which is the
 * same shape as {@link CombineTransactionsJobTest} and {@link PreflightDailyTransactionsJobTest} rather
 * than an exception to a rule. This module's integration-test suffix marks the three cases that assert a
 * repository contract itself; a container-backed job case is not one of those, and the suffix carries no
 * claim about whether a runtime is available -- every runner this repository defines provides one.</p>
 *
 * <p>Assumptions: the staging seam is the ONE double, so the artefact's bytes are observable without a
 * bucket. Everything between the job and the data is real: the relation, its ordered cursor, the record
 * encoder and the durable step ledger.</p>
 *
 * <p>Trade-offs: the pad regime each row class carries is NOT re-asserted here and is left with the
 * sibling class, which drives both classes side by side in one artefact. What this class adds is that
 * the artefact describes the rows the ENGINE holds; asserting the byte-level pad here as well would
 * duplicate a ruling that already has an owner and would make two files fail for one defect.</p>
 *
 * @see BackupTransactionsJob
 * @see BackupTransactionsJobTest
 */
@Testcontainers
// WHY : Refactoring Rationale: the Parameter Store config-data location is disabled for this context,
//       for the reason the sibling com.carddemo.batch.repository.BatchRunRepositoryIT measures and
//       records in full: the base document's `optional:aws-parameterstore:` location builds a
//       management client while configuration is still loading, and a host with no region configured
//       aborts the context on the unresolved placeholder before a single case runs.
@SpringBootTest(
        classes = BackupTransactionsJobPersistenceTest.BackupPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.aws.parameterstore.enabled=false")
@ActiveProfiles("test")
@DisplayName("the transaction backup job against a real relation")
class BackupTransactionsJobPersistenceTest {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every sibling container-backed case in this module pins, and
     * the version is spelled out in prose because a digest states nothing a reader recognises. Two cases
     * pinning two engines could disagree about one column's behaviour, and the disagreement would
     * surface as whichever of them ran second.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /** The classpath-relative harness that creates the schemas and tables these cases read and write. */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /** The relation the step copies and must leave untouched. */
    private static final String MASTER_TABLE = "ledger.transactions";

    /** The injected business date, in the separated ten-character layout. */
    private static final String BUSINESS_DATE_TOKEN = "2022-07-18";

    /** The injected business date as the job receives it. */
    private static final BusinessDate BUSINESS_DATE = new BusinessDate(BUSINESS_DATE_TOKEN);

    /** The orchestrator execution identifier every case runs under. */
    private static final String RUN_ID = "batch-run-backup-0001";

    /** The registry name of the layout the copied records are laid out under. */
    private static final String MASTER_LAYOUT = "TRAN";

    /** The generation number the staging seam reports for a newly allocated coordinate. */
    private static final int FIRST_GENERATION = 1;

    /** The object key the staging seam reports, which no assertion depends on. */
    private static final String STAGED_OBJECT_KEY =
            "ledger/transact.bkup/dt=2022-07-18/gen=0001/transact.bkup";

    /** The transaction type code every seeded row carries. */
    private static final String TYPE_CD = "01";

    /** The category code a posted row carries. */
    private static final String POSTED_CATEGORY_CD = "0001";

    /** The source label a posted row carries, from {@code app/data/ASCII/dailytran.txt}. */
    private static final String POSTED_SOURCE = "POS TERM";

    /** The description a posted row carries, from the committed posting expectations. */
    private static final String POSTED_DESCRIPTION = "Purchase at Abshire-Lowe";

    /** The merchant identifier every seeded row carries. */
    private static final long MERCHANT_ID = 800000000L;

    /** The merchant name every seeded row carries. */
    private static final String MERCHANT_NAME = "Abshire-Lowe";

    /** The merchant city every seeded row carries. */
    private static final String MERCHANT_CITY = "North Enoshaven";

    /** The merchant postal code every seeded row carries. */
    private static final String MERCHANT_ZIP = "72112";

    /** The originating stamp every seeded row carries. */
    private static final LocalDateTime SEEDED_ORIGINATION_TIME =
            LocalDateTime.of(2022, 6, 10, 19, 27, 53);

    /** The processing stamp every seeded row carries. */
    private static final LocalDateTime SEEDED_PROCESSING_TIME =
            LocalDateTime.of(2022, 7, 18, 1, 2, 3);

    /** The engine the persistence ruling is measured against. */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /** The relation the job reads and must not write. */
    @Autowired
    private TransactionRepository ledger;

    /**
     * The category-balance relation the same step unloads, autowired so the run reads the real table.
     *
     * <p>Assumptions: the REAL repository is autowired rather than a double, because this class's whole
     * subject is what a run does to the relations it touches, and a double cannot show that a table
     * survived. The table is left empty by every case here: the families this class asserts on are the
     * ledger ones, and an empty category-balance unload is a valid generation for the same reason the
     * empty-relation case below records.</p>
     */
    @Autowired
    private TransactionCategoryBalanceRepository categoryBalances;

    /** The durable step ledger's table, emptied between cases so each run records its own row. */
    @Autowired
    private BatchRunRepository runs;

    /** The durable step ledger the job runs its body under. */
    @Autowired
    private BatchStepLedger ledgerOfSteps;

    /**
     * The context's own transaction manager, handed to the step the job builds.
     *
     * <p>Assumptions: the REAL manager is used and not a resourceless stand-in, because the ordered walk
     * the job performs returns a cursor-backed stream that requires a transaction actually owning the
     * connection. A stand-in satisfies the propagation check and then hands back a stream with nothing
     * behind it, so every case would compare an empty artefact against an untouched table and pass.</p>
     */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** A template used to commit each seed before a run, so the run reads committed rows. */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A plain database handle, used for the snapshots no entity mapping can express. */
    private JdbcTemplate jdbc;

    /** The generation resolver, mocked so the staged payload is observable at the seam. */
    private DatasetGenerationService generations;

    /** The framework's in-memory job repository. */
    private JobRepository jobRepository;

    /** The shared parameter validator the job is built with. */
    private JobParametersValidator validator;

    /**
     * The family each staged payload was handed over for, in the order they were handed over.
     *
     * <p>Refactoring Rationale: the capture used to be payloads alone, on the reading that a run stages
     * one artefact. One step now stages THREE families -- the full copy, the card-ordered daily subset
     * and the category-balance unload -- so a positional list can no longer say which artefact a case is
     * asserting on. Keeping the family beside the bytes is what lets every ruling in this class stay
     * about the family it was written for.</p>
     */
    private List<DatasetFamily> stagedFamilies;

    /** Every payload handed to the staging seam during one case, in the order it was handed over. */
    private List<byte[]> stagedPayloads;

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
     * <p>Assumptions: the tables are emptied in a COMMITTED transaction rather than rolled back around
     * each case, because the job reads through a transaction of its own -- rows visible only inside an
     * uncommitted wrapper would not be there when its cursor opened, and a case would then compare an
     * empty artefact against rows it believed present.</p>
     *
     * @param dataSource the pool the context built from the container's coordinates, taken as a
     *     parameter so the plain handle is rebuilt per case; must not be {@code null}
     */
    @BeforeEach
    void emptyTablesAndBuildSeam(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactionTemplate.executeWithoutResult(status -> {
            this.jdbc.update("DELETE FROM " + MASTER_TABLE);
            this.jdbc.update("DELETE FROM batch.batch_run");
        });

        this.jobRepository = new ResourcelessJobRepository();
        this.validator = new BatchConfig().carddemoJobParametersValidator();
        this.stagedPayloads = new ArrayList<>();
        this.stagedFamilies = new ArrayList<>();
        this.launchCounter = 0;
        this.generations = mock(DatasetGenerationService.class);

        // WHY : Assumptions: the allocator answers with a REAL coordinate rather than a mock's null,
        //       because the job reports the allocated number after staging and a null would fail every
        //       case inside a log statement -- a failure about the stub rather than about the step.
        when(this.generations.allocateNewGeneration(any(DatasetFamily.class),
                any(BusinessDate.class), anyString()))
                .thenAnswer(call -> new DatasetGeneration(
                        call.getArgument(0), call.getArgument(1), FIRST_GENERATION));
        when(this.generations.generationsToScratch(any(DatasetFamily.class))).thenReturn(List.of());
        when(this.generations.datasetUri(any(DatasetGeneration.class)))
                .thenReturn("s3://carddemo-datasets-test/ledger/transact.bkup/");

        // WHY : Assumptions: the bytes are captured DURING the staging call rather than read back
        //       afterwards, because the job deletes its temporary file on every path including success,
        //       so a read afterwards would find nothing and an empty comparison would pass.
        when(this.generations.stageDataset(any(DatasetGeneration.class), anyString(),
                any(Path.class))).thenAnswer(call -> {
                    this.stagedFamilies.add(call.<DatasetGeneration>getArgument(0).family());
                    this.stagedPayloads.add(Files.readAllBytes(call.<Path>getArgument(2)));
                    return STAGED_OBJECT_KEY;
                });
    }

    /**
     * Every persisted row survives the run with every column unchanged.
     *
     * <p>Pins the omission of {@code app/jcl/TRANBKP.jcl:37-46} and {@code :51-60} against the engine.
     * Three rows are seeded and every column of every row is read back and compared, so a delete, a
     * truncate, a re-create and an in-place rewrite are all failures here.</p>
     *
     * <p>Assumptions: whole ROWS are compared rather than a count, because the two ways this could fail
     * are not both visible in a count. A ported cluster delete would empty the table and change the
     * count; a step that stamped a copied row -- marking it archived, say -- would leave the count
     * identical and the data wrong.</p>
     *
     * <p>Assumptions: the three seeded rows are made DISTINGUISHABLE from one another in amount, sign
     * and card number, so a comparison cannot pass by coincidence on rows that were swapped, and the
     * negative amount additionally puts a sign overpunch through the encoder on the way out.</p>
     *
     * @throws Exception if the framework's own execution path raises, which this case does not provoke
     */
    @Test
    @DisplayName("leave every persisted row present with every column unchanged")
    void everyPersistedRowSurvivesTheRunWithEveryColumnUnchanged() throws Exception {
        seed(postedRow("0000000000000001", new BigDecimal("504.77"), "4859452612877065"),
                postedRow("0000000000000002", new BigDecimal("-125.50"), "4111111111111111"),
                postedRow("0000000000000003", new BigDecimal("0.00"), "4111111111111111"));
        List<Map<String, Object>> before = snapshotOfMaster();
        assertThat(before)
                .as("the seed committed, so the comparison below is between two populated snapshots")
                .hasSize(3);

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(snapshotOfMaster())
                .as("a ported cluster delete would have emptied this table and a ported re-create"
                        + " would have replaced its rows")
                .isEqualTo(before);
    }

    /**
     * The staged artefact describes the rows the engine holds, one record each and in key order.
     *
     * <p>Pins {@code app/jcl/TRANBKP.jcl:41-48}, the {@code IDCAMS REPRO} that copies the cluster to the
     * backup generation: the artefact is the master's own content, not a projection of it. The rows are
     * read back from the database AFTER the run and the artefact is compared against those, so the claim
     * is about what was persisted rather than about what the case handed the job.</p>
     *
     * <p>Assumptions: three fields are compared per record -- the identifier the relation is keyed on,
     * the amount, and the card number -- and not the whole 350 bytes. Those three carry the row's
     * identity, its money and its owner, which is what makes a record recognisable as one persisted row;
     * the byte-level composition of a record, including the two pad regimes, is owned by
     * {@link BackupTransactionsJobTest}, which drives both row classes side by side. Comparing the whole
     * image here would restate that ruling and make two files fail for one defect.</p>
     *
     * <p>Assumptions: the amount is decoded through the shared zoned-decimal codec in its overpunch mode
     * rather than parsed as text. Section 5.2 of {@code tests/README.md} records that the reference is
     * compiled with the EBCDIC sign convention and that the default silently corrupts negative
     * balances, so a hand-rolled decode is the one error whose symptom is a plausible wrong number
     * rather than a failure -- and one seeded row is negative precisely to reach it.</p>
     *
     * @throws Exception if the framework's own execution path raises, which this case does not provoke
     */
    @Test
    @DisplayName("stage one record per persisted row, in key order, carrying that row's own values")
    void theStagedArtefactDescribesTheRowsTheEngineHolds() throws Exception {
        seed(postedRow("0000000000000001", new BigDecimal("504.77"), "4859452612877065"),
                postedRow("0000000000000002", new BigDecimal("-125.50"), "4111111111111111"),
                postedRow("0000000000000003", new BigDecimal("0.00"), "4111111111111111"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<Map<String, Object>> persisted = snapshotOfMaster();
        List<byte[]> records = splitIntoRecords(onlyStagedPayload());

        assertThat(records)
                .as("one record per row the engine holds, so neither an extra nor a missing record"
                        + " can pass")
                .hasSameSizeAs(persisted);

        for (int index = 0; index < persisted.size(); index++) {
            Map<String, Object> row = persisted.get(index);
            byte[] record = records.get(index);
            assertThat(textField(record, "TRAN-ID"))
                    .as("record %d carries the identifier of the row at the same position in key"
                            + " order", index)
                    .isEqualTo(String.valueOf(row.get("transaction_id")));
            assertThat(signedField(record, "TRAN-AMT"))
                    .as("record %d carries the persisted amount exactly", index)
                    .isEqualByComparingTo((BigDecimal) row.get("amount"));
            assertThat(textField(record, "TRAN-CARD-NUM"))
                    .as("record %d carries the persisted card number", index)
                    .isEqualTo(String.valueOf(row.get("card_num")));
        }
    }

    /**
     * A second run over the same relation changes no row and stages the same bytes.
     *
     * <p>Pins re-runnability against the engine. {@code app/jcl/TRANBKP.jcl} is re-runnable because its
     * own delete step tolerates an absent cluster, so an orchestrator that redrives a later state and
     * re-enters this one must find the step survivable here too. A redrive that emitted different bytes
     * from unchanged rows, or that changed a row on its second pass, would turn a recoverable night into
     * a manual one.</p>
     *
     * <p>Assumptions: the two launches are two distinct framework executions rather than one replayed,
     * and both stage into the same coordinate, because the seam is stubbed to allocate the same
     * generation number twice. That is what makes the byte comparison a comparison of two runs rather
     * than of two differently-named artefacts.</p>
     *
     * @throws Exception if the framework's own execution path raises, which this case does not provoke
     */
    @Test
    @DisplayName("survive a redrive, leaving the rows unchanged and emitting the same bytes")
    void aRedriveLeavesTheRowsUnchangedAndEmitsTheSameBytes() throws Exception {
        seed(postedRow("0000000000000001", new BigDecimal("504.77"), "4859452612877065"),
                postedRow("0000000000000002", new BigDecimal("-125.50"), "4111111111111111"));
        List<Map<String, Object>> before = snapshotOfMaster();

        JobExecution first = runBackup();
        JobExecution second = runBackup("batch-run-backup-0002");

        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<byte[]> copies = stagedPayloadsOf(DatasetFamily.TRANSACT_BKUP);
        assertThat(copies).hasSize(2);
        assertThat(copies.get(1))
                .as("the same rows must copy to the same bytes on a redriven night")
                .isEqualTo(copies.get(0));
        assertThat(snapshotOfMaster())
                .as("neither pass may touch the relation it read")
                .isEqualTo(before);
    }

    /**
     * An empty relation stages an empty artefact and gains no row.
     *
     * <p>Pins the allocation being unconditional: {@code app/jcl/TRANBKP.jcl:33-37} declares the backup
     * generation {@code DISP=(NEW,CATLG,DELETE)}, so a generation exists after every run regardless of
     * how many records went into it. A downstream reader therefore faces an empty generation rather than
     * an absent one.</p>
     *
     * <p>Assumptions: the table is asserted empty BEFORE the run rather than assumed empty from the
     * reset having run, and asserted empty afterwards as well -- which is the half that says a
     * copy-out step does not write anything back on the way through.</p>
     *
     * @throws Exception if the framework's own execution path raises, which this case does not provoke
     */
    @Test
    @DisplayName("stage an empty artefact from an empty relation and write no row to it")
    void anEmptyRelationStagesAnEmptyArtefactAndGainsNoRow() throws Exception {
        assertThat(snapshotOfMaster())
                .as("the arrangement is the ABSENCE of rows, so the emptiness is asserted rather than"
                        + " assumed from the reset having run")
                .isEmpty();

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(onlyStagedPayload())
                .as("the generation is allocated and staged even with nothing to copy into it")
                .isEmpty();
        assertThat(snapshotOfMaster())
                .as("a copy-out step writes nothing back, so an empty relation stays empty")
                .isEmpty();
    }

    /**
     * Runs the job once under the standard run identifier.
     *
     * @return the finished job execution, never {@code null}
     * @throws Exception if the framework's own execution path raises, which the caller declares rather
     *     than absorbs
     */
    private JobExecution runBackup() throws Exception {
        return runBackup(RUN_ID);
    }

    /**
     * Runs the job once under a caller-chosen run identifier.
     *
     * @param runId the orchestrator execution identifier to launch under, which is also the key the
     *     durable step ledger writes its row under; must not be {@code null}
     * @return the finished job execution, never {@code null}
     * @throws Exception if the framework's own execution path raises, which the caller declares rather
     *     than absorbs
     */
    private JobExecution runBackup(String runId) throws Exception {
        // WHY : Assumptions: a second launch inside one case carries a DIFFERENT run identifier,
        //       because the durable ledger keys its row on that identifier and reports a step it
        //       already recorded as complete as a no-op. Reusing one identifier would make the second
        //       launch skip its body, and the redrive case would then compare one run's bytes with
        //       themselves.
        Job job = new BackupTransactionsJob(this.ledger, this.categoryBalances, this.generations,
                        this.ledgerOfSteps)
                .backupTransactions(this.jobRepository, this.transactionManager, this.validator);

        JobParameters parameters = new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER, BUSINESS_DATE_TOKEN, true)
                .addString(BatchConfig.RUN_ID_PARAMETER, runId, false)
                .toJobParameters();

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
     * @param rows the transactions to persist, each carrying a distinct identifier because the
     *     relation's primary key is that column alone; must not be {@code null}
     */
    private void seed(Transaction... rows) {
        this.transactionTemplate.executeWithoutResult(status -> this.ledger.saveAll(List.of(rows)));
    }

    /**
     * Takes a comparable image of every row of the master, column by column.
     *
     * <p>Assumptions: the read goes through a plain query rather than the entity mapping, because the
     * regression this snapshot exists to catch is a MUTATION and the entity would report only the
     * columns it maps. A column the entity does not carry -- the optimistic-lock version among them --
     * is exactly where a step that touched a row it should not have would show up.</p>
     *
     * <p>Assumptions: the ordering is explicit, so two snapshots of one table are comparable as lists.
     * An unordered read is free to return the same rows in a different sequence after a write, which
     * would fail the comparison for a reason that is not the one under assertion.</p>
     *
     * @return one map per row, in key order, never {@code null}
     */
    private List<Map<String, Object>> snapshotOfMaster() {
        return this.jdbc.queryForList(
                "SELECT * FROM " + MASTER_TABLE + " ORDER BY transaction_id");
    }

    /**
     * Reports the single payload the run staged, asserting that there was exactly one.
     *
     * @return the staged bytes, never {@code null}
     */
    private byte[] onlyStagedPayload() {
        List<byte[]> ofFamily = stagedPayloadsOf(DatasetFamily.TRANSACT_BKUP);
        assertThat(ofFamily)
                .as("one run stages one artefact of the family this class rules on")
                .hasSize(1);
        return ofFamily.get(0);
    }

    /**
     * Reports the payloads one case staged for a nominated family, in the order they were staged.
     *
     * <p>Assumptions: the selection is by FAMILY and not by position, because the step stages three
     * families per run and the position of any one of them is an implementation order this class does
     * not rule on.</p>
     *
     * @param family the family to select, of type {@code DatasetFamily}; must not be {@code null}
     * @return the payloads staged for that family, never {@code null}
     */
    private List<byte[]> stagedPayloadsOf(DatasetFamily family) {
        List<byte[]> selected = new ArrayList<>();
        for (int i = 0; i < this.stagedFamilies.size(); i++) {
            if (this.stagedFamilies.get(i) == family) {
                selected.add(this.stagedPayloads.get(i));
            }
        }
        return selected;
    }

    /**
     * Splits one staged image into its fixed-length records.
     *
     * <p>Assumptions: the image is required to be a whole multiple of the declared length and the
     * requirement is asserted rather than assumed, because an image whose length is not a multiple has
     * lost or gained bytes somewhere and every field comparison after the first would then be reported
     * against a misaligned record.</p>
     *
     * @param image the concatenated records the run staged; must not be {@code null}
     * @return the records in staged order, never {@code null}
     */
    private static List<byte[]> splitIntoRecords(byte[] image) {
        int reclen = CopybookLayout.layout(MASTER_LAYOUT).reclen();
        assertThat(image.length % reclen)
                .as("the staged image is a whole number of %d-byte records", reclen)
                .isZero();

        List<byte[]> records = new ArrayList<>();
        for (int offset = 0; offset < image.length; offset += reclen) {
            records.add(Arrays.copyOfRange(image, offset, offset + reclen));
        }
        return records;
    }

    /**
     * Reads one text field out of a staged record, at its declared offset and width.
     *
     * <p>Assumptions: the decoding charset maps every byte to exactly one character, so a character
     * offset stays equal to a byte offset. A multi-byte decoding would shift every offset after the
     * first non-ASCII byte.</p>
     *
     * @param record one fixed-width record image; must not be {@code null}
     * @param fieldName the declared field name to read; must be declared by the layout
     * @return the field's characters exactly as staged, padding included, never {@code null}
     */
    private static String textField(byte[] record, String fieldName) {
        CopybookLayout.FieldSpec field = CopybookLayout.layout(MASTER_LAYOUT).field(fieldName);
        return new String(record, field.start(), field.length(), StandardCharsets.ISO_8859_1);
    }

    /**
     * Decodes one signed zoned-decimal field out of a staged record.
     *
     * @param record one fixed-width record image; must not be {@code null}
     * @param fieldName the declared field name to decode; must be declared by the layout
     * @return the exact decoded value at the field's declared scale, never {@code null}
     */
    private static BigDecimal signedField(byte[] record, String fieldName) {
        CopybookLayout.FieldSpec field = CopybookLayout.layout(MASTER_LAYOUT).field(fieldName);
        return ZonedDecimalCodec.decode(textField(record, fieldName), field.intDigits(),
                field.decDigits(), field.signed());
    }

    /**
     * Builds one posted transaction, the row class the posting pass commits.
     *
     * @param transactionId the sixteen-character identifier the row is keyed on; must not be
     *     {@code null}
     * @param amount the monetary value at scale two, which may be negative so the sign overpunch is
     *     exercised; must not be {@code null}
     * @param cardNum the sixteen-character card number the row names; must not be {@code null}
     * @return the transaction, never {@code null}
     */
    private static Transaction postedRow(String transactionId, BigDecimal amount, String cardNum) {
        Transaction transaction = new Transaction(transactionId);
        transaction.setTypeCd(TYPE_CD);
        transaction.setCategoryCd(POSTED_CATEGORY_CD);
        transaction.setSource(POSTED_SOURCE);
        transaction.setDescription(POSTED_DESCRIPTION);
        transaction.setAmount(amount);
        transaction.setMerchantId(MERCHANT_ID);
        transaction.setMerchantName(MERCHANT_NAME);
        transaction.setMerchantCity(MERCHANT_CITY);
        transaction.setMerchantZip(MERCHANT_ZIP);
        transaction.setCardNum(cardNum);
        transaction.setOrigTs(SEEDED_ORIGINATION_TIME);
        transaction.setProcTs(SEEDED_PROCESSING_TIME);
        return transaction;
    }

    /**
     * The narrow context these cases run against: the two repositories and the durable step ledger.
     *
     * <p>Assumptions: the job under test is NOT a bean of this context and is constructed by the case,
     * so that the staging seam can be a stand-in while the relation and the step ledger are real.
     * Scanning the production job package would register all seven jobs and would require a real
     * generation service, which needs a bucket this module's tests deliberately cannot provide.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.batch.domain")
    @EnableJpaRepositories("com.carddemo.batch.repository")
    static class BackupPersistenceTestApplication {

        /**
         * Supplies the clock the durable ledger stamps its rows from.
         *
         * @return a clock reading the coordinated universal time zone, never {@code null}
         */
        @Bean
        Clock batchClock() {
            // WHY : Assumptions: a real clock is supplied rather than a fixed one, because no assertion
            //       in this class reads a ledger timestamp for its value. A fixed clock would also make
            //       the started and finished stamps equal, which the ledger's constraint admits, so the
            //       substitution would buy nothing and would hide a genuine ordering defect.
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
            //       unproxied, so its writes would join the step's transaction and be discarded with it
            //       on a failure path.
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
            //       state rather than a gap: the test profile sets no carddemo.messaging.* key, so
            //       SqsConfig's conditional queue beans are out of every context this package builds.
            return new BatchStepLedger(writer, Optional.empty());
        }
    }
}
