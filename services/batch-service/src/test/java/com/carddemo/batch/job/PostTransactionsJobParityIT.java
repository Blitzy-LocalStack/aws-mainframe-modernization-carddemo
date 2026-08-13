package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.mapper.AccountRecordMapper;
import com.carddemo.batch.mapper.CardXrefRecordMapper;
import com.carddemo.batch.mapper.DailyTransactionMapper;
import com.carddemo.batch.mapper.TransactionCategoryBalanceRecordMapper;
import com.carddemo.batch.mapper.TransactionRecordMapper;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.BatchRunRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DailyFeedWatermarkRepository;
import com.carddemo.batch.repository.TransactionCategoryBalanceRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.BatchStepLedgerWriter;
import com.carddemo.batch.service.CategoryBalanceService;
import com.carddemo.batch.service.DatasetGenerationService;
import com.carddemo.batch.service.DailyFeedWatermarkService;
import com.carddemo.batch.service.PostingValidationService;
import com.carddemo.common.codec.CopybookLayout;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the real posting job against a real engine and compares all four outputs with the goldens.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this is the parity harness for {@code app/cbl/CBTRN02C.cbl}. For each of the nine
 * expectation trees committed under {@code tests/golden/posting} it loads that scenario's fixed-width
 * fixtures into PostgreSQL, launches {@code PostTransactionsJob} through the same
 * {@link JobOperator} the container entry point uses, and compares every one of the four artifacts the
 * tree holds -- the account master, the category-balance file, the transaction master and the reject
 * stream -- against the recorded bytes, together with the aggregate return code.</p>
 *
 * <p>⚠️ Refactoring Rationale: the sibling {@code PostTransactionsJobTest} carried a case named for
 * this comparison which read only {@code return_code.expected} and the SIZE of
 * {@code dalyrejs.expected}. <b>Those two facts are properties of the committed files, not of the
 * migrated job.</b> That case passes with the job never started, with every repository mocked, and
 * with the posting logic deleted, because nothing it reads comes from a run -- it compares the oracle
 * with itself. Three of the four artifacts were compared by nothing at all: an account balance
 * accumulated into the wrong cycle column, a category balance that took the create arm where the
 * reference updated, or a posted row missing its originating stamp would every one of them have left
 * the suite green. The case is kept where it is, because the relationship it states between a tree's
 * return code and its reject stream is worth pinning; what it never was is the parity comparison, and
 * this class is that.</p>
 *
 * <h2>What is real, and what is substituted</h2>
 *
 * <p>Assumptions: the job, its validation rule, its accumulation rule, its five repositories, its
 * transaction boundary, its durable step ledger and its database are all REAL. The context imports
 * {@link PostTransactionsJob} and {@link BatchConfig} exactly as the application does, so the
 * {@code Job} launched here is the bean the {@code post-transactions} token resolves to in a deployed
 * task, and the writes land in the two schemas the harness script creates through the same
 * cross-schema grants the deployed role holds.</p>
 *
 * <p>Assumptions: exactly one collaborator is substituted, and the substitution is confined to the
 * object store. {@link DatasetGenerationService} is a mock, because an S3 endpoint is not available to
 * this build and because the artifact under comparison is the dataset the job WRITES rather than the
 * key it lands under. The mock captures the staged file's bytes, so the reject stream compared below is
 * the exact byte sequence the job produced; the coordinate it would have been stored at is asserted by
 * {@code DatasetGenerationServiceTest} and by the generation cases of the sibling unit test, and is
 * deliberately not re-asserted here.</p>
 *
 * <p>Alternatives Considered: substituting the step ledger as well, which the sibling unit test does.
 * Rejected because the ledger is what decides whether the step body runs at all, and a mock that
 * evaluates the body is a mock that has already assumed the answer. Running the real one over
 * {@code batch.batch_run} costs nothing here and means a redrive-suppression defect would surface as
 * nine scenarios producing empty output rather than as nothing.</p>
 *
 * <h2>Why each scenario runs under its own business date</h2>
 *
 * <p>Assumptions: the nine launches carry nine DIFFERENT business dates, one per scenario, and the
 * difference is required rather than incidental. {@code BatchApplication} adds that parameter as
 * IDENTIFYING, so the framework keys the job instance on it; nine launches sharing one date would be
 * nine attempts at one instance and the second would be refused as already complete.</p>
 *
 * <p>Assumptions: this also corroborates {@code D-POSTING-GENERATION-DATE} from the outside. That
 * register entry states the parameter is orchestration metadata which reaches no field of any posted or
 * rejected record; nine runs differing in nothing but that parameter, each matching its recorded bytes
 * exactly, is the observable form of the claim. A job that leaked the parameter into a stored field
 * would fail every scenario here.</p>
 *
 * <h2>The normalisation policy, and why it is this narrow</h2>
 *
 * <p>Assumptions: two classes of span are normalised on BOTH sides before comparison and no others,
 * and each span is located through {@link CopybookLayout} by field name rather than by a literal
 * offset, so the policy moves with the copybook rather than against it.</p>
 *
 * <p>Assumptions: the first class is the non-deterministic processing stamp,
 * {@code TRAN-PROC-TS} at offset 304 of the posted record, which the registry itself marks by setting
 * its normalise flag. The reference suite normalises the same field for the same reason before its own
 * golden comparison, documented at section 11 of {@code tests/README.md}, and the committed
 * {@code tranfile.expected} files carry twenty-six blanks in that span as a result. The span is not
 * merely erased: {@link #theNormalisedSpansHoldExactlyWhatIsClaimedOnBothSides()} asserts the job wrote
 * the injected clock's instant there, so blanking it discards a value that has already been checked.
 * </p>
 *
 * <p>Assumptions: the second class is a dropped {@code FILLER} span. The migration plan's rule T1 drops
 * {@code FILLER} rather than mapping it to a column, so the entity carries no member for it and the
 * encoder rebuilds it as blanks; the reference's two writers disagree about it, so there is no single
 * value to rebuild it as. Measured across the nine trees: the twenty bytes at offset 330 of every
 * {@code tranfile.expected} are LOW VALUES, because
 * {@code app/cbl/CBTRN02C.cbl:426-438} moves the posted fields into a record area individually and
 * never touches its pad; the twenty-two bytes at offset 28 of {@code tcatbal.expected} are ASCII ZEROS
 * in the eight scenarios whose category row was rewritten, inherited from the seed image, and LOW
 * VALUES in {@code zero_balance} alone, whose row was created -- {@code INITIALIZE} at
 * {@code app/cbl/CBTRN02C.cbl:504} does not reach a {@code FILLER} item.</p>
 *
 * <p>Assumptions: the account master and the reject stream are normalised NOWHERE and are compared
 * whole, three hundred and four hundred and thirty bytes respectively. Their pads agree already, and
 * measurably: the account record is REWRITTEN from an image the run read, so its pad is the fixture's
 * blanks and the encoder writes blanks; the reject record's first three hundred and fifty bytes are a
 * GROUP move of the feed image at {@code app/cbl/CBTRN02C.cbl:447}, so its pad is the fixture's blanks
 * too. Trade-offs: normalising every pad uniformly would have been one rule instead of a table, and it
 * is rejected because it would stop comparing seven hundred and thirty bytes that currently do
 * compare.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
@Testcontainers
// WHY : Refactoring Rationale: the Parameter Store config-data location is disabled for this context,
//       for the reason the sibling BatchRunRepositoryIT records in full: application.yml's
//       `optional:aws-parameterstore:` location builds an SSM client while configuration is still
//       loading, and a build host with no AWS_REGION aborts the context on the unresolved
//       placeholder. It is cited rather than repeated.
@SpringBootTest(
        classes = PostTransactionsJobParityIT.PostingParityTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.aws.parameterstore.enabled=false")
@ActiveProfiles("test")
@DisplayName("the posting job against every committed expectation tree")
class PostTransactionsJobParityIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every sibling integration test in this build pins, so one
     * engine serves the whole suite and two tests cannot disagree about one schema. The version is
     * recorded in prose because a digest states nothing a reader recognises.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The classpath-relative path of the harness that supplies the two foreign schemas.
     *
     * <p>Assumptions: this class REQUIRES the script. Its writes land in {@code ledger.transactions},
     * {@code ledger.transaction_category_balances}, {@code ledger.transaction_rejects} and
     * {@code account.accounts}, none of which this module's own migration creates or may create, and
     * its reads additionally need {@code account.card_xref} and {@code ledger.daily_transactions}.
     * The script also leaves the {@code batch} schema empty so Flyway creates this module's own tables
     * under the configuration production uses.</p>
     */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /** The repository-relative directory holding the committed expectation trees. */
    private static final String GOLDEN_POSTING = "tests/golden/posting";

    /** The classpath prefix of this module's copy of the posting fixtures. */
    private static final String FIXTURE_ROOT = "/fixtures/posting/";

    /**
     * The instant the injected clock reports, which every posted row's processing stamp carries.
     *
     * <p>Assumptions: a literal, never a clock read. The stamp is the one non-deterministic value a
     * posted record holds, which is exactly why the parity oracle normalises it; injecting a fixed
     * instant means the value blanked before comparison is nonetheless a value this class can assert.
     * </p>
     */
    private static final LocalDateTime POSTED_AT = LocalDateTime.of(2022, 7, 18, 2, 5, 30);

    /**
     * The first business date the nine launches use, incremented once per scenario.
     *
     * <p>Assumptions: the date is the one the sibling job tests use, so the family is recognisable
     * beside them, and the per-scenario increment exists only to give each launch its own job
     * instance.</p>
     */
    private static final LocalDate FIRST_BUSINESS_DATE = LocalDate.of(2024, 1, 15);

    /** The orchestrator execution identifier prefix, made unique per scenario by the scenario name. */
    private static final String RUN_ID_PREFIX = "parity-";

    /** The file each tree holds for the account master after the run. */
    private static final String ACCOUNT_MASTER_FILE = "acctdat.expected";

    /** The file each tree holds for the category-balance file after the run. */
    private static final String CATEGORY_BALANCE_FILE = "tcatbal.expected";

    /** The file each tree holds for the transaction master after the run. */
    private static final String TRANSACTION_MASTER_FILE = "tranfile.expected";

    /** The file each tree holds for the reject stream the run produced. */
    private static final String REJECT_STREAM_FILE = "dalyrejs.expected";

    /** The file each tree holds for the aggregate return code the run reached. */
    private static final String RETURN_CODE_FILE = "return_code.expected";

    /** The copybook field name of the pad that rule T1 drops on every record that has one. */
    private static final String FILLER = "FILLER";

    /** The copybook field name of the posted record's machine-generated processing stamp. */
    private static final String TRAN_PROC_TS = "TRAN-PROC-TS";

    /** The byte a normalised span is overwritten with on both sides of a comparison. */
    private static final byte NORMALISED_BYTE = (byte) ' ';

    /**
     * The container every scenario runs against, started once for the class.
     *
     * <p>Assumptions: the type comes from {@code org.testcontainers.postgresql} rather than the
     * deprecated {@code org.testcontainers.containers}, and carries no type argument because the
     * replacement is not generic.</p>
     */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /** The job bean the {@code post-transactions} token resolves to, launched as production does. */
    @Autowired
    private Job job;

    /** The framework's launcher, which is the entry point the container command uses. */
    @Autowired
    private JobOperator jobOperator;

    /** The account master, written by the run and read back for comparison. */
    @Autowired
    private AccountRepository accounts;

    /** The posted ledger, written by the run and read back for comparison. */
    @Autowired
    private TransactionRepository ledger;

    /** The category balances, written by the run and read back for comparison. */
    @Autowired
    private TransactionCategoryBalanceRepository categoryBalances;

    /** The object-store allocator, substituted so the staged bytes can be captured. */
    @Autowired
    private DatasetGenerationService generations;

    /** The persistence context, used to seed the two relations no repository here can write. */
    @Autowired
    private EntityManager entityManager;

    /** The boundary the seeding runs inside, and the one the read-backs are taken inside. */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A plain JDBC handle, used only to empty the relations between scenarios. */
    private JdbcTemplate jdbc;

    /** The bytes the run staged as its reject dataset, captured by the substituted allocator. */
    private byte[] stagedRejectStream;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * @param registry the Spring test property registry that this method adds the container's JDBC
     *     URL, user name and credential to as deferred suppliers; must not be {@code null}
     */
    // WHY : Assumptions: the Flyway pair is registered beside the datasource pair because
    //       application.yml binds spring.flyway.user and spring.flyway.password to placeholders with
    //       no fallback, and Boot consults those keys precisely when no connection-details bean
    //       supplies them -- which is this module's case. The repository package charter records the
    //       full reasoning.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties every relation a scenario touches and re-arms the substituted allocator.
     *
     * <p>Assumptions: the durable step ledger is emptied too, and not only the data relations. A run
     * whose {@code (runId, stepName)} pair the ledger already holds is SKIPPED by design, so a
     * surviving row from a previous scenario would make the next one produce no output and the failure
     * would name a comparison rather than the ledger.</p>
     *
     * <p>Assumptions: the framework's own metadata is NOT emptied, because each scenario carries its
     * own business date and therefore its own job instance, so no two scenarios collide there.</p>
     *
     * @param dataSource the pool the context built from the container's coordinates; must not be
     *     {@code null}
     */
    @BeforeEach
    void resetAndArm(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactionTemplate.executeWithoutResult(status -> {
            this.jdbc.update("DELETE FROM ledger.transactions");
            this.jdbc.update("DELETE FROM ledger.transaction_category_balances");
            this.jdbc.update("DELETE FROM ledger.transaction_rejects");
            this.jdbc.update("DELETE FROM ledger.daily_transactions");
            this.jdbc.update("DELETE FROM account.accounts");
            this.jdbc.update("DELETE FROM account.card_xref");
            this.jdbc.update("DELETE FROM batch.batch_run");
        });

        this.stagedRejectStream = null;

        // WHY : Assumptions: the allocator returns a REAL coordinate rather than a mock's null,
        //       because the job logs the allocated number and location after staging and a null
        //       coordinate would fail every scenario with a null dereference raised from a log
        //       statement -- a failure about the stub rather than about the bytes.
        when(this.generations.allocateNewGeneration(
                any(DatasetFamily.class), any(BusinessDate.class), anyString()))
                .thenAnswer(call -> new DatasetGeneration(
                        call.getArgument(0), call.getArgument(1), 1));
        when(this.generations.generationsToScratch(any(DatasetFamily.class))).thenReturn(List.of());
        when(this.generations.datasetUri(any(DatasetGeneration.class)))
                .thenReturn("s3://carddemo-datasets-test/ledger/dalyrejs/");

        // WHY : Assumptions: the staged file is read INSIDE the stub, while the job still holds it.
        //       The job deletes its temporary file on every path once staging returns, so a copy taken
        //       afterwards would read nothing and every reject comparison would compare two empty
        //       arrays and pass.
        when(this.generations.stageDataset(
                any(DatasetGeneration.class), anyString(), any(Path.class)))
                .thenAnswer(call -> {
                    this.stagedRejectStream = Files.readAllBytes(call.<Path>getArgument(2));
                    return "ledger/dalyrejs/staged";
                });
    }

    /**
     * Loads one scenario's fixtures, runs the job and compares all four outputs and the return code.
     *
     * <p>Assumptions: the four artifacts are compared in the order the reference writes them and every
     * one of them is compared, because the four fail independently. An account balance accumulated
     * into the wrong cycle column shows only in {@code acctdat}; a create arm taken where the
     * reference updated shows only in {@code tcatbal}; a dropped originating stamp shows only in
     * {@code tranfile}; a wrong reason code shows only in {@code dalyrejs}. A harness comparing fewer
     * than four leaves the others unasserted.</p>
     *
     * <p>Assumptions: the expected bytes are read from the committed trees STRICTLY READ-ONLY, and this
     * class exposes no way to rewrite one -- no environment switch, no update argument, no
     * write-if-missing branch. The parity oracle does ship such a gate, multiply guarded and requiring
     * the diff to be reviewed, at section 12 of {@code tests/README.md}, and the asymmetry is
     * deliberate: that suite's expectations describe the reference, whose behaviour is fixed, so
     * regenerating one records a corrected reading of an unchanged program. A switch here would rewrite
     * the expectation to match whatever the migrated code currently produces, which converts the one
     * independent check this module has into a restatement of its own output.</p>
     *
     * @param scenario the expectation tree's directory name, which is also this module's fixture
     *     directory name; must name a committed directory under both roots
     * @param scenarioIndex the scenario's position in the enumeration, which supplies its business date
     *     and therefore its job instance
     * @throws Exception if the framework's own launch path raises, or a committed file cannot be read
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("committedPostingScenarios")
    @DisplayName("reproduce every committed byte of all four outputs and the return code")
    void everyCommittedScenarioIsReproducedByteForByte(String scenario, int scenarioIndex)
            throws Exception {

        seedFrom(scenario);

        JobExecution execution = launch(scenario, scenarioIndex);

        Path tree = goldenPostingRoot().resolve(scenario);

        assertThat(readBackAccountMaster())
                .as("%s: the account master after the run", scenario)
                .isEqualTo(expectedRecords(tree.resolve(ACCOUNT_MASTER_FILE)));

        assertThat(normalise(readBackCategoryBalances(), "TCATBAL", Set.of(FILLER)))
                .as("%s: the category-balance file after the run", scenario)
                .isEqualTo(normalise(expectedRecords(tree.resolve(CATEGORY_BALANCE_FILE)),
                        "TCATBAL", Set.of(FILLER)));

        assertThat(normalise(readBackTransactionMaster(), "TRAN", Set.of(FILLER, TRAN_PROC_TS)))
                .as("%s: the transaction master after the run", scenario)
                .isEqualTo(normalise(expectedRecords(tree.resolve(TRANSACTION_MASTER_FILE)),
                        "TRAN", Set.of(FILLER, TRAN_PROC_TS)));

        assertThat(stagedRejectRecords())
                .as("%s: the reject stream the run staged", scenario)
                .isEqualTo(expectedRecords(tree.resolve(REJECT_STREAM_FILE)));

        assertThat(reachedReturnCode(execution))
                .as("%s: the aggregate return code", scenario)
                .isEqualTo(committedReturnCode(tree));
    }

    /**
     * Confirms the two normalised spans hold exactly what the normalisation policy claims they hold.
     *
     * <p>Assumptions: this case exists so that no span is erased without first being read. A
     * normalisation is an admission that two sides cannot agree, and an unasserted one is
     * indistinguishable from a comparison quietly narrowed to make a failure disappear. It runs over
     * {@code happy_path}, the one scenario that posts a transaction AND rewrites an existing category
     * row, so both normalised spans are populated by a real write.</p>
     *
     * <p>Assumptions: the four claims asserted are the four the class header measures -- the job writes
     * the injected instant into the processing stamp, the encoder rebuilds both pads as blanks, the
     * committed posted record's pad holds low values, and the committed category record's pad holds
     * ASCII zeros on this scenario's update arm. If any of the four ever stops holding, the policy
     * above has stopped describing the artifacts and this case says so rather than the comparison
     * silently absorbing it.</p>
     *
     * @throws Exception if the framework's own launch path raises, or a committed file cannot be read
     */
    @Test
    @DisplayName("hold exactly the claimed bytes in every span the comparison normalises")
    void theNormalisedSpansHoldExactlyWhatIsClaimedOnBothSides() throws Exception {
        // WHY : ⚠️ Refactoring Rationale: this case asserted that BOTH produced pads are blanks, and
        //       NEITHER is: both mappers fill the dropped FILLER span with the low value
        //       FRESH_RECORD_PAD. The expectation was a faithful reading of a claim in
        //       TransactionCategoryBalanceRecordMapper's own documentation that the single-argument
        //       overload "writes blanks there", which the code has never done; that sentence is
        //       corrected in the same change, because fixing only this assertion would leave the next
        //       reader to be misled the same way. What the two records do NOT share is whether one
        //       byte can be right at all: every committed expectation for the posted record carries
        //       the low value, so its mapper reproduces images exactly, while the category balance's
        //       expectations disagree -- twenty-two ASCII zeros on this update arm against twenty-two
        //       low values on the zero_balance create arm -- so its single-argument overload is wrong
        //       on one arm by construction and a two-argument overload restores the span from the
        //       source image. That disagreement is the reason these spans are normalised rather than
        //       compared, which is the fact the uniform blanks claim was hiding.

        String scenario = "happy_path";
        seedFrom(scenario);
        launch(scenario, indexOf(scenario));

        CopybookLayout.FieldSpec postedStamp = CopybookLayout.layout("TRAN").field(TRAN_PROC_TS);
        CopybookLayout.FieldSpec postedPad = CopybookLayout.layout("TRAN").field(FILLER);
        CopybookLayout.FieldSpec balancePad = CopybookLayout.layout("TCATBAL").field(FILLER);

        byte[] produced = readBackTransactionMaster();
        byte[] committedPosted =
                expectedRecords(goldenPostingRoot().resolve(scenario).resolve(TRANSACTION_MASTER_FILE));
        byte[] producedBalance = readBackCategoryBalances();
        byte[] committedBalance =
                expectedRecords(goldenPostingRoot().resolve(scenario).resolve(CATEGORY_BALANCE_FILE));

        assertThat(span(produced, postedStamp))
                .as("the job stamped the injected instant, so blanking that span discards a checked"
                        + " value rather than an unchecked one")
                .isEqualTo(com.carddemo.common.time.TimestampFormatter.format(POSTED_AT));

        assertThat(span(produced, postedPad).chars().distinct().toArray())
                .as("rule T1 drops FILLER and TransactionRecordMapper rebuilds the posted pad with the"
                        + " LOW VALUE every committed expectation for this record carries, not with the"
                        + " codec's blank, which is what lets toRecord(toEntity(image)) reproduce an"
                        + " image; so this span AGREES with the committed one byte for byte")
                .containsExactly(0);
        assertThat(span(producedBalance, balancePad).chars().distinct().toArray())
                .as("the category mapper's single-argument overload writes the same low value, which is"
                        + " right for the zero_balance create arm and WRONG for this update arm, whose"
                        + " committed pad carries ASCII zeros -- the disagreement between those two"
                        + " goldens is why a two-argument overload restoring the span from the source"
                        + " image exists, and why this span is normalised rather than compared")
                .containsExactly(0);

        assertThat(span(committedPosted, postedPad).chars().distinct().toArray())
                .as("the committed posted pad holds LOW VALUES, because app/cbl/CBTRN02C.cbl:426-438"
                        + " moves the posted fields individually and never touches the pad")
                .containsExactly(0);
        assertThat(span(committedBalance, balancePad).chars().distinct().toArray())
                .as("the committed category pad holds ASCII ZEROS on this update arm, inherited from"
                        + " the seed image the rewrite carried across")
                .containsExactly('0');
    }

    /**
     * Confirms the enumeration covers exactly the trees on disk, and that the fixtures match them.
     *
     * <p>Assumptions: both roots are enumerated rather than trusted. A parameterised case reads only
     * the rows it is given, so a tenth expectation tree would be compared by nothing at all and its
     * absence from the list would look like a decision; and a tree with no matching fixture directory
     * in this module could not be driven even if it were listed.</p>
     *
     * @throws IOException if either directory cannot be listed
     */
    @Test
    @DisplayName("cover exactly the committed trees, each with a fixture directory of its own")
    void theEnumerationCoversExactlyTheCommittedTreesAndTheirFixtures() throws IOException {
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

        for (String scenario : enumerated) {
            assertThat(getClass().getResource(FIXTURE_ROOT + scenario + "/dailytran.txt"))
                    .as("%s must have a fixture directory in this module", scenario)
                    .isNotNull();
        }
    }

    /**
     * Reports the nine committed posting scenarios and the ordinal each one's business date derives from.
     *
     * <p>Assumptions: the ordinal is declared here rather than computed from the directory listing,
     * because a listing order is not a contract and a scenario's business date has to be stable across
     * runs for a failure to be reproducible from the message alone.</p>
     *
     * @return one argument pair per scenario, each carrying the directory name and its ordinal, never
     *     {@code null}
     */
    private static Stream<Arguments> committedPostingScenarios() {
        return Stream.of(
                Arguments.of("happy_path", 0),
                Arguments.of("empty_input", 1),
                Arguments.of("zero_balance", 2),
                Arguments.of("boundary_exact_limit", 3),
                Arguments.of("boundary_expiry_equal", 4),
                Arguments.of("reject_100_card_missing", 5),
                Arguments.of("reject_101_acct_missing", 6),
                Arguments.of("reject_102_overlimit", 7),
                Arguments.of("reject_103_expired", 8));
    }

    /**
     * Reports the ordinal the enumeration gives one scenario.
     *
     * @param scenario the directory name to look up; must be one the enumeration lists
     * @return that scenario's ordinal, which supplies its business date
     * @throws IllegalArgumentException if the enumeration does not list it, which names a typo rather
     *     than a missing tree
     */
    private static int indexOf(String scenario) {
        return committedPostingScenarios()
                .filter(candidate -> scenario.equals(candidate.get()[0]))
                .map(candidate -> (Integer) candidate.get()[1])
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "no committed posting scenario is named " + scenario));
    }

    /**
     * Loads the four fixed-width fixtures of one scenario into the four relations the run reads.
     *
     * <p>Assumptions: every record is decoded by the PRODUCTION mapper rather than assembled here, so
     * the rows the job reads are the rows the ETL would have loaded and no field is placed by this
     * class's own arithmetic. A hand-built row would let the harness agree with itself about a shape
     * production does not produce.</p>
     *
     * <p>Assumptions: the cross-reference is written through the persistence context and not through
     * its repository, because that interface deliberately extends the bare marker rather than the full
     * CRUD one -- this module only ever READS it -- and persisting the seed directly is what lets the
     * path under test stay read-only.</p>
     *
     * <p>Assumptions: the FEED is written with a statement rather than through the persistence context,
     * and the difference is not stylistic. {@code DailyTransaction} maps {@code ingest_seq} as its
     * identity and deliberately declares NO generation strategy, because this module scans the feed and
     * never inserts into it -- the type's own mapping records the decision and a sibling case asserts
     * the annotation's absence so it cannot be undone silently. Persisting one therefore fails on an
     * unassigned identifier. Assigning ordinals here instead would work and is rejected: the ordinal
     * decides the order the posting walk visits records in, which is the order the reject stream's
     * sequence follows, so it must come from the database identity the ETL would have let assign it
     * rather than from this class's own counter. The values inserted are still the PRODUCTION mapper's
     * decode of the fixture image, so no field is placed by arithmetic written here.</p>
     *
     * @param scenario the fixture directory name; must name a committed directory
     */
    private void seedFrom(String scenario) {
        CardXrefRecordMapper crossReferences = new CardXrefRecordMapper();

        this.transactionTemplate.executeWithoutResult(status -> {
            for (byte[] image : fixtureRecords(scenario, "acctdata.txt", "ACCOUNT")) {
                this.accounts.save(AccountRecordMapper.toEntity(image));
            }
            for (byte[] image : fixtureRecords(scenario, "cardxref.txt", "XREF")) {
                this.entityManager.persist(crossReferences.toEntity(image));
            }
            for (byte[] image : fixtureRecords(scenario, "tcatbal.txt", "TCATBAL")) {
                this.categoryBalances.save(
                        TransactionCategoryBalanceRecordMapper.toEntity(image));
            }
            this.entityManager.flush();
            this.entityManager.clear();
        });

        // WHY : Assumptions: the feed insert is wrapped in a transaction so that it COMMITS. Line 217
        //       of services/batch-service/src/test/resources/application-test.yml sets the pool's
        //       auto-commit to false, so a statement issued outside a transaction is executed and then
        //       discarded when the connection returns to the pool -- measured: without this wrapper
        //       every scenario ran over an empty feed, left its seed unchanged and staged nothing, and
        //       eight of the nine comparisons failed against an unmodified account master. The sibling
        //       CombineTransactionsJobTest records the same hazard for its own out-of-transaction
        //       statement.
        this.transactionTemplate.executeWithoutResult(status -> {
            for (byte[] image : fixtureRecords(scenario, "dailytran.txt", "DALYTRAN")) {
                insertFeedRow(DailyTransactionMapper.toEntity(image));
            }
        });
    }

    /**
     * Inserts one decoded feed row, letting the database identity column assign its ingestion ordinal.
     *
     * <p>Assumptions: every mapped column is supplied and the ordinal is not, which is exactly the
     * shape the ETL's bulk load has. The processing stamp is supplied too even though a feed row's is
     * blank, because the column is nullable precisely so an unposted row can carry no stamp and passing
     * the decoded value states that rather than assuming it.</p>
     *
     * @param feedRow the decoded feed record; must not be {@code null}
     */
    private void insertFeedRow(DailyTransaction feedRow) {
        this.jdbc.update("INSERT INTO ledger.daily_transactions"
                        + " (transaction_id, type_cd, category_cd, source, description, amount,"
                        + " merchant_id, merchant_name, merchant_city, merchant_zip, card_num,"
                        + " orig_ts, proc_ts)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                feedRow.getTransactionId(), feedRow.getTypeCd(), feedRow.getCategoryCd(),
                feedRow.getSource(), feedRow.getDescription(), feedRow.getAmount(),
                feedRow.getMerchantId(), feedRow.getMerchantName(), feedRow.getMerchantCity(),
                feedRow.getMerchantZip(), feedRow.getCardNum(), feedRow.getOrigTs(),
                feedRow.getProcTs());
    }

    /**
     * Launches the real job for one scenario through the framework's own launcher.
     *
     * @param scenario the scenario name, which makes the run identifier unique
     * @param scenarioIndex the scenario's ordinal, which selects its business date and job instance
     * @return the completed execution, never {@code null}
     * @throws Exception if the framework's own launch path raises, which no scenario here provokes
     */
    private JobExecution launch(String scenario, int scenarioIndex) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER,
                        FIRST_BUSINESS_DATE.plusDays(scenarioIndex).toString(), true)
                .addString(BatchConfig.RUN_ID_PARAMETER, RUN_ID_PREFIX + scenario, false)
                .toJobParameters();

        return this.jobOperator.start(this.job, parameters);
    }

    /**
     * Reads the account master back and re-encodes it through the production mapper.
     *
     * <p>Assumptions: the rows are ordered by identifier, which is the order the indexed dataset the
     * expectation was captured from returns them in. A comparison over an unordered read would fail on
     * a fixture holding more than one account for a reason that has nothing to do with a value.</p>
     *
     * @return the concatenated three-hundred-byte images, never {@code null}
     */
    private byte[] readBackAccountMaster() {
        return this.transactionTemplate.execute(status -> {
            try (Stream<Account> rows = this.accounts.findAllByOrderByAccountIdAsc()) {
                return concatenate(rows.map(AccountRecordMapper::toRecord).toList());
            }
        });
    }

    /**
     * Reads the category-balance file back and re-encodes it through the production mapper.
     *
     * @return the concatenated fifty-byte images in composite-key order, never {@code null}
     */
    private byte[] readBackCategoryBalances() {
        return this.transactionTemplate.execute(status -> {
            try (Stream<TransactionCategoryBalance> rows = this.categoryBalances
                    .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc()) {
                return concatenate(
                        rows.map(TransactionCategoryBalanceRecordMapper::toRecord).toList());
            }
        });
    }

    /**
     * Reads the transaction master back and re-encodes it through the production mapper.
     *
     * <p>Assumptions: the walk is the PRODUCTION ordered finder, so the sequence compared here is the
     * sequence the combine and backup jobs would stage, and the byte collation pinned by
     * {@code V3__ledger_bytewise_collation.sql} governs it.</p>
     *
     * @return the concatenated three-hundred-and-fifty-byte images in identifier order, never
     *     {@code null}
     */
    private byte[] readBackTransactionMaster() {
        return this.transactionTemplate.execute(status -> {
            try (Stream<Transaction> rows = this.ledger.findAllByOrderByTransactionIdAsc()) {
                return concatenate(rows.map(TransactionRecordMapper::toRecord).toList());
            }
        });
    }

    /**
     * Reports the reject dataset the run staged, as the job wrote it.
     *
     * <p>Assumptions: a run that staged nothing is reported as an empty array rather than as absent,
     * because the job stages a dataset on every path -- an accepted-only pass stages an EMPTY
     * generation, which is what the reference's newly allocated data definition produces -- so an
     * absent capture would be a defect and is asserted as one by the emptiness comparison itself.</p>
     *
     * @return the staged bytes, or an empty array when the run staged an empty dataset, never
     *     {@code null}
     */
    private byte[] stagedRejectRecords() {
        return this.stagedRejectStream == null ? new byte[0] : this.stagedRejectStream;
    }

    /**
     * Reports the tier the launched execution reached, as the aggregate return code.
     *
     * <p>Assumptions: the tier is read from the execution's exit code rather than from a counter,
     * because the exit code is what the orchestrator branches on -- {@code BatchApplication} maps it to
     * the process status at its own exit -- so it is the value the state machine's choice sees.</p>
     *
     * @param execution the completed execution; must not be {@code null}
     * @return the aggregate return code the run reported
     */
    private static BatchReturnCode reachedReturnCode(JobExecution execution) {
        return BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS
                .equals(execution.getExitStatus().getExitCode())
                ? BatchReturnCode.SOFT_WARN
                : BatchReturnCode.CLEAN;
    }

    /**
     * Reads the aggregate return code one expectation tree recorded.
     *
     * @param tree the tree directory; must hold the return-code file
     * @return the recorded tier
     * @throws IOException if the committed file cannot be read
     */
    private static BatchReturnCode committedReturnCode(Path tree) throws IOException {
        return BatchReturnCode.fromNumericValue(
                Integer.parseInt(Files.readString(tree.resolve(RETURN_CODE_FILE)).trim()));
    }

    /**
     * Reads one committed expectation file back into the concatenated fixed-length records it holds.
     *
     * <p>Assumptions: the file stores one record per line and the separator is removed rather than
     * compared, because the dataset the reference wrote is a fixed-length stream with no separator at
     * all -- the newline is the comparison format's, not the record's. The separator is dropped by
     * position rather than by splitting on the byte, because a posted record's pad contains low values
     * and a category record's contains digits, and a split would be safe today and would stop being
     * safe the moment a record legitimately carried the separator byte.</p>
     *
     * @param expectation the committed file; must exist
     * @return the concatenated records with every line separator removed, never {@code null}
     * @throws IOException if the file cannot be read
     */
    private static byte[] expectedRecords(Path expectation) throws IOException {
        byte[] stored = Files.readAllBytes(expectation);
        byte[] stripped = new byte[stored.length];
        int kept = 0;
        for (byte value : stored) {
            if (value != (byte) '\n' && value != (byte) '\r') {
                stripped[kept++] = value;
            }
        }
        byte[] records = new byte[kept];
        System.arraycopy(stripped, 0, records, 0, kept);
        return records;
    }

    /**
     * Reads one fixture file into the fixed-length records it holds, checking the declared geometry.
     *
     * <p>Assumptions: the record length comes from the registry rather than from a literal, and the
     * file's length is checked against it before any record is decoded. A fixture one byte adrift
     * decodes into plausible values with every field after the fault displaced, which is the failure a
     * length check turns into a message naming the file.</p>
     *
     * @param scenario the fixture directory name; must name a committed directory
     * @param fileName the fixture file within it; must exist
     * @param layoutName the registry name of the record the file holds; must be registered
     * @return the file's records, each exactly one record long, never {@code null}
     * @throws IllegalStateException if the fixture is absent or its length is not a whole number of
     *     records
     * @throws UncheckedIOException if the classpath resource cannot be read, which names a broken
     *     build output rather than a missing artifact
     */
    private List<byte[]> fixtureRecords(String scenario, String fileName, String layoutName) {
        int reclen = CopybookLayout.layout(layoutName).reclen();
        String resource = FIXTURE_ROOT + scenario + "/" + fileName;

        byte[] stored;
        try (InputStream source = getClass().getResourceAsStream(resource)) {
            if (source == null) {
                throw new IllegalStateException("fixture " + resource + " is absent from the"
                        + " classpath; the fixture tree is a committed artifact and a checkout"
                        + " without it is incomplete");
            }
            stored = source.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("could not read fixture " + resource, unreadable);
        }

        byte[] records = withoutLineSeparators(stored);
        if (records.length % reclen != 0) {
            throw new IllegalStateException("fixture " + resource + " holds " + records.length
                    + " bytes, which is not a whole number of " + reclen + "-byte "
                    + layoutName + " records");
        }

        List<byte[]> decoded = new ArrayList<>(records.length / reclen);
        for (int offset = 0; offset < records.length; offset += reclen) {
            byte[] record = new byte[reclen];
            System.arraycopy(records, offset, record, 0, reclen);
            decoded.add(record);
        }
        return decoded;
    }

    /**
     * Removes every line separator from a stored fixture or expectation.
     *
     * @param stored the file's bytes; must not be {@code null}
     * @return a new array holding the same bytes with every carriage return and line feed removed,
     *     never {@code null}
     */
    private static byte[] withoutLineSeparators(byte[] stored) {
        byte[] stripped = new byte[stored.length];
        int kept = 0;
        for (byte value : stored) {
            if (value != (byte) '\n' && value != (byte) '\r') {
                stripped[kept++] = value;
            }
        }
        byte[] result = new byte[kept];
        System.arraycopy(stripped, 0, result, 0, kept);
        return result;
    }

    /**
     * Overwrites the named spans of every record in a stream with one canonical byte.
     *
     * <p>Assumptions: the spans are located through the registry by FIELD NAME, so the policy is stated
     * in copybook terms and moves with the copybook. A literal offset would be correct today and would
     * silently normalise the wrong bytes the first time a field moved.</p>
     *
     * @param records the concatenated fixed-length records to normalise; must be a whole number of
     *     records of the named layout
     * @param layoutName the registry name of the record the stream holds; must be registered
     * @param fieldNames the copybook names of the spans to overwrite; must all be declared by that
     *     layout
     * @return a new array holding the same records with those spans overwritten, never {@code null}
     * @throws IllegalArgumentException if the stream is not a whole number of records
     */
    private static byte[] normalise(byte[] records, String layoutName, Set<String> fieldNames) {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(layoutName);
        int reclen = spec.reclen();
        if (records.length % reclen != 0) {
            throw new IllegalArgumentException(records.length + " bytes is not a whole number of "
                    + reclen + "-byte " + layoutName + " records");
        }

        byte[] normalised = records.clone();
        for (int offset = 0; offset < normalised.length; offset += reclen) {
            for (String fieldName : fieldNames) {
                CopybookLayout.FieldSpec field = spec.field(fieldName);
                java.util.Arrays.fill(normalised, offset + field.start(),
                        offset + field.end(), NORMALISED_BYTE);
            }
        }
        return normalised;
    }

    /**
     * Reads one field's span out of the first record of a stream, as text.
     *
     * @param records the concatenated records; must hold at least one record
     * @param field the field whose span to read; must lie inside the record
     * @return the span's bytes decoded one byte per character, never {@code null}
     */
    private static String span(byte[] records, CopybookLayout.FieldSpec field) {
        return new String(records, field.start(), field.length(), StandardCharsets.ISO_8859_1);
    }

    /**
     * Joins a list of equal-length record images into one stream.
     *
     * @param images the record images in the order they are to be written; must not be {@code null}
     * @return the concatenation, never {@code null} and empty when the list is
     */
    private static byte[] concatenate(List<byte[]> images) {
        int total = images.stream().mapToInt(image -> image.length).sum();
        byte[] joined = new byte[total];
        int offset = 0;
        for (byte[] image : images) {
            System.arraycopy(image, 0, joined, offset, image.length);
            offset += image.length;
        }
        return joined;
    }

    /**
     * Locates the committed expectation root by walking up from the working directory.
     *
     * @return the directory holding the nine expectation trees, never {@code null}
     * @throws IllegalStateException if no ancestor of the working directory holds it, which names an
     *     incomplete checkout rather than a missing test resource
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
     * The Spring Boot configuration this class runs the real job inside.
     *
     * <p>Assumptions: no component scan is declared, so the six sibling job definitions, the queue
     * client and the object-store client this module's application class would register stay out of the
     * context. The two production configurations the posting job needs are imported by type instead,
     * which keeps the context to the persistence layer plus the job under test.</p>
     *
     * <p>Assumptions: the clock is declared primary because {@link BatchConfig} contributes a system
     * clock of its own and a posted record's processing stamp has to be reproducible. A context with
     * two unqualified clocks would fail to start, and one taking the system clock would produce a
     * stamp no expectation can hold.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.batch.domain")
    @EnableJpaRepositories("com.carddemo.batch.repository")
    @Import({BatchConfig.class, PostTransactionsJob.class})
    static class PostingParityTestApplication {

        /**
         * Registers the fixed clock every posted row's processing stamp is read from.
         *
         * @return a clock reporting one instant, never {@code null}
         */
        @Bean
        @Primary
        Clock parityClock() {
            return Clock.fixed(POSTED_AT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        }

        /**
         * Registers the production validation rule over the real cross-reference and account readers.
         *
         * @param crossReferences the cross-reference the rule resolves a card through; must not be
         *     {@code null}
         * @param accounts the account master the rule reads; must not be {@code null}
         * @return the production service, never {@code null}
         */
        @Bean
        PostingValidationService postingValidationService(
                CardXrefRepository crossReferences, AccountRepository accounts) {
            return new PostingValidationService(crossReferences, accounts);
        }

        /**
         * Registers the production accumulation rule over the real category-balance repository.
         *
         * @param balances the category-balance repository the rule reads and writes through; must not
         *     be {@code null}
         * @return the production service, never {@code null}
         */
        @Bean
        CategoryBalanceService categoryBalanceService(
                TransactionCategoryBalanceRepository balances) {
            return new CategoryBalanceService(balances);
        }

        /**
         * Registers the real durable step ledger writer over the real run repository.
         *
         * @param runs the durable step ledger's repository; must not be {@code null}
         * @param clock the clock the ledger stamps its rows from; must not be {@code null}
         * @return the production writer, never {@code null}
         */
        @Bean
        BatchStepLedgerWriter batchStepLedgerWriter(BatchRunRepository runs, Clock clock) {
            return new BatchStepLedgerWriter(runs, clock);
        }

        /**
         * Registers the real durable step ledger with no failure reporter attached.
         *
         * <p>Assumptions: the reporter is absent rather than substituted, because it publishes to a
         * queue this context deliberately does not build a client for and because no scenario here
         * fails a step. The ledger accepts its absence by declaring the dependency optional.</p>
         *
         * @param writer the ledger writer; must not be {@code null}
         * @return the production ledger, never {@code null}
         */
        @Bean
        BatchStepLedger batchStepLedger(BatchStepLedgerWriter writer) {
            return new BatchStepLedger(writer, java.util.Optional.empty());
        }

        /**
         * Registers the substituted object-store allocator, which the class arms per scenario.
         *
         * <p>Assumptions: this is the ONLY substituted collaborator, and it is substituted because an
         * object store is not reachable from this build. The bytes it is handed are the bytes the job
         * produced, which is what the comparison needs; where they would have landed is asserted by
         * {@code DatasetGenerationServiceTest}.</p>
         *
         * @return a mock of the production allocator, never {@code null}
         */
        @Bean
        DatasetGenerationService datasetGenerationService() {
            return mock(DatasetGenerationService.class);
        }

        /**
         * Registers the durable feed watermark the job checkpoints each accepted record against.
         *
         * <p>Assumptions: the REAL service over the REAL repository, not a mock. This class compares
         * produced bytes against a golden tree with a live database behind it, and the watermark is what
         * decides where a resumed pass starts -- so substituting it would leave the one collaborator that
         * can silently re-present or skip a window as the one collaborator the parity run never
         * exercises. Its advance-only rule therefore runs here exactly as it runs in production.</p>
         *
         * <p>Assumptions: the clock is this context's own parity clock rather than the system clock, so
         * the instant the checkpoint records is the fixed instant every other assertion in this class is
         * written against.</p>
         *
         * @param watermarks the repository holding the position row; must not be {@code null}
         * @param clock the parity clock; must not be {@code null}
         * @return the service the job checkpoints through, never {@code null}
         */
        @Bean
        DailyFeedWatermarkService dailyFeedWatermarkService(
                DailyFeedWatermarkRepository watermarks, Clock clock) {
            return new DailyFeedWatermarkService(watermarks, clock);
        }

        /**
         * Registers the boundary the seeding and the read-backs run inside.
         *
         * @param transactionManager the context's single transaction manager; must not be {@code null}
         * @return a template over it, never {@code null}
         */
        @Bean
        TransactionTemplate parityTransactionTemplate(
                org.springframework.transaction.PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }
    }
}
