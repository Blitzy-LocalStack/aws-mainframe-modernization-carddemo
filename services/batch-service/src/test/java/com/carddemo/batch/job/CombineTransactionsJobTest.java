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
import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
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
 * Pins the join, the sort order, the absent load-back and the registered divergences of the combine
 * step.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this is the tier-two owner of four contracts of
 * {@link CombineTransactionsJob}, state seven of the nightly chain. First, the PIPELINE JOIN: the
 * combined artefact carries every committed row exactly once, whichever upstream pass wrote it -- the
 * posting pass or the interest pass -- which is the SET the reference assembled by concatenating two
 * datasets and this step reaches from one relation. Second, the SORT ORDER, together with the collation ruling that makes it
 * a correctness requirement rather than a preference. Third, the NO-LOAD-BACK ruling: the run writes
 * a generation and touches no row of the relation it read. Fourth, the REGISTERED DIVERGENCES of this
 * state: the record layout measured against an image composed from the copybook rather than from the
 * layout registry, the artefact's independence of the two named input generations, and the one
 * description pad both row classes carry. Each of those three cites the entry it measures in
 * {@code docs/architecture/cobol-to-service-traceability.md}, so a reader who finds a difference
 * against a reference extract is told where it is accounted for.</p>
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
     * of {@code app/cpy/CVTRA05Y.cpy}. It is taken verbatim from the repository's own published sample
     * data rather than fabricated here: it appears six times in {@code app/data/ASCII/dailytran.txt}
     * and once each in {@code app/data/ASCII/carddata.txt} and {@code app/data/ASCII/cardxref.txt},
     * which are the Apache-2.0 sample datasets the baseline ships and which this project treats as
     * reference-only input. Reusing that value is what lets a reader line this fixture up against the
     * parity oracle's own goldens, where the same number is the subject of every posting scenario
     * under {@code tests/golden/posting/}.</p>
     *
     * <p>Refactoring Rationale: this note used to say the value "fails the industry check digit". It
     * does not -- its Luhn sum is 80, so it satisfies the check -- and the claim mattered because it
     * was offered as the reason the value is safe to commit. The actual reason is its provenance: it is
     * published sample data that identifies no cardholder and no account, and it is already committed
     * to this repository in three places. A false safety rationale is worse than none, because the next
     * author who needs a card number copies the reasoning rather than the value.</p>
     */
    private static final String CARD_NUM = "4859452612877065";

    /** The zero-based offset of the trailing pad, from line 18 of {@code app/cpy/CVTRA05Y.cpy}. */
    private static final int TRAILING_PAD_OFFSET = 330;

    /**
     * The character the reference leaves in a span no statement of either producer writes.
     *
     * <p>Assumptions: it is declared as a {@code char} rather than a {@code byte} because the helper
     * that reports a span's distinct bytes returns characters, so a byte constant would have to be cast
     * at every use and the cast would read as an encoding decision it is not.</p>
     */
    private static final char LOW_VALUE_CHARACTER = '\0';

    /**
     * The positive sign overpunch table, transcribed from {@code tests/helpers/record_codec.py:137}.
     *
     * <p>Assumptions: the table is transcribed from the PARITY ORACLE's own codec and never imported
     * from the encoder under test, because it is one half of the independent expectation
     * {@link #theStagedRecordMatchesAnIndependentlyComposedImage} composes. Importing the encoder's own
     * table would make the expectation a restatement of the code it checks. The index is the low-order
     * digit's value, so {@code '\u007b'} carries a positive zero and {@code 'I'} a positive nine.</p>
     */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /**
     * The negative sign overpunch table, transcribed from {@code tests/helpers/record_codec.py:138}.
     *
     * <p>Assumptions: the negative table is what separates the two sign conventions, which section 5.2
     * of {@code tests/README.md} records as a silent corruption when the wrong one is used, so the
     * oracle carries both rather than deriving one from the other.</p>
     */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /**
     * The record layout transcribed field by field from {@code app/cpy/CVTRA05Y.cpy} lines 5 to 18.
     *
     * <p>Assumptions: this table is the INDEPENDENT half of the byte oracle and is deliberately NOT
     * read from {@code CopybookLayout}. Every other case in this class decodes through the registered
     * layout, which is the right way to assert a field's content and the wrong way to assert the
     * layout itself: an expectation taken from the registry agrees with the encoder by construction and
     * would keep agreeing if both drifted from the copybook together. The widths here are transcribed
     * from the copybook's own picture clauses -- sixteen, two, four, ten, one hundred, nine plus two,
     * nine, fifty, fifty, ten, sixteen, twenty-six, twenty-six and twenty -- which sum to the three
     * hundred and fifty bytes line 2 of that file declares.</p>
     *
     * <p>Assumptions: the signed amount's width is ELEVEN and not thirteen. {@code TRAN-AMT
     * PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:10} declares nine integer and two decimal digit
     * positions; the decimal point is implied and occupies no byte, and the sign is an overpunch on the
     * low-order digit rather than a byte of its own, so eleven digit positions are eleven bytes.</p>
     */
    private static final List<CopybookField> TRANSCRIBED_LAYOUT = List.of(
            new CopybookField("TRAN-ID", 16, FieldKind.TEXT),
            new CopybookField("TRAN-TYPE-CD", 2, FieldKind.TEXT),
            new CopybookField("TRAN-CAT-CD", 4, FieldKind.UNSIGNED_DIGITS),
            new CopybookField("TRAN-SOURCE", 10, FieldKind.TEXT),
            new CopybookField("TRAN-DESC", 100, FieldKind.TEXT),
            new CopybookField("TRAN-AMT", 11, FieldKind.SIGNED_ZONED),
            new CopybookField("TRAN-MERCHANT-ID", 9, FieldKind.UNSIGNED_DIGITS),
            new CopybookField("TRAN-MERCHANT-NAME", 50, FieldKind.TEXT),
            new CopybookField("TRAN-MERCHANT-CITY", 50, FieldKind.TEXT),
            new CopybookField("TRAN-MERCHANT-ZIP", 10, FieldKind.TEXT),
            new CopybookField("TRAN-CARD-NUM", 16, FieldKind.TEXT),
            new CopybookField("TRAN-ORIG-TS", 26, FieldKind.TEXT),
            new CopybookField("TRAN-PROC-TS", 26, FieldKind.TEXT),
            new CopybookField("FILLER", 20, FieldKind.TEXT));

    /**
     * The whole public operation surface of the generation seam this job holds.
     *
     * <p>Assumptions: the list is an ALLOWLIST and not a description. It is asserted equal to the
     * seam's measured surface so that a new operation cannot appear without a decision being taken
     * here, which is what keeps {@code D-COMBINE-GENERATION-BYPASS} checkable: the divergence that
     * entry registers holds because no operation on this collaborator can return a dataset's contents,
     * and a silently added reader would falsify it with no assertion failing.</p>
     */
    private static final List<String> SEAM_OPERATIONS = List.of("allocateNewGeneration",
            "resolveCurrentGeneration", "generationsToScratch", "datasetUri", "stageDataset",
            "scratchGeneration");

    /**
     * The return types that would let an operation hand back a dataset's contents.
     *
     * <p>Assumptions: the set names BYTE AND CHARACTER SOURCES and deliberately excludes the value and
     * coordinate types the seam does return -- {@code String} for a key or a location,
     * {@code DatasetGeneration} for a coordinate, {@code Optional} and {@code List} of that coordinate,
     * and {@code int} for a count. A definition wide enough to include {@code List} would flag
     * {@code generationsToScratch}, which returns coordinates and no bytes, and the assertion would
     * then be measuring the seam's shape rather than whether a payload can leave it.</p>
     */
    private static final List<Class<?>> PAYLOAD_RETURN_TYPES = List.of(byte[].class, InputStream.class,
            Reader.class, ByteBuffer.class, Path.class, File.class, Stream.class);

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

    /**
     * The bytewise collation the owning schema pins the ledger's ordering-critical columns to.
     *
     * <p>Assumptions: the name is the engine's own built-in and is held as a constant rather than
     * written at each of the four use sites, because two of them compare it against a catalogue
     * reading and two apply it in DDL -- and a typo in one of the four would either pass vacuously or
     * leave the shared schema mis-collated for every case that follows.</p>
     */
    private static final String BYTE_WISE_COLLATION = "C";

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
     * Every committed row reaches the artefact exactly once, whichever pass wrote it.
     *
     * <p>Pins the SET the combined generation carries. That set is what the reference assembled by
     * concatenating {@code app/jcl/COMBTRAN.jcl:23-24} and {@code :25-26}, and it is what this step
     * reaches another way. Both producer classes are seeded because a fixture of one class would let the
     * case pass while proving nothing about the other, and each is required EXACTLY ONCE because one
     * record per relation row is precisely what separates this step from a concatenation of the two
     * staged objects -- which in the migrated model would emit the night's accrual rows twice, for the
     * reason {@code D-COMBINE-BACKUP-SUPERSET} records in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Assumptions: this case does NOT show that either named input object contributed a byte, and it
     * no longer claims to. The job reads neither payload -- {@code D-COMBINE-GENERATION-BYPASS}
     * registers that difference and {@link #theCombinedImageIsIndependentOfTheTwoNamedGenerations}
     * measures it. Refactoring Rationale: this case was named for both PRODUCERS' rows and read as a
     * claim about both physical INPUTS, which it could not support: its two rows are seeded into one
     * table and the run resolves the two generations without opening either. The name and the wording
     * now state the property the fixture can actually establish.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("carry every committed row exactly once, whichever pass wrote it")
    void carryEveryCommittedRowExactlyOnceWhicheverPassWroteIt() throws Exception {
        // WHY : Refactoring Rationale: the reference had to CONCATENATE TWO PHYSICAL DATASETS because
        //       its producers wrote to different files -- posting to the master, whose backup
        //       generation app/jcl/COMBTRAN.jcl:23-24 names, and accrual to the generation
        //       app/jcl/COMBTRAN.jcl:25-26 names. In the migrated model both producers commit to ONE
        //       relation: posting writes its rows at app/cbl/CBTRN02C.cbl:442 and accrual writes its
        //       generated rows at app/cbl/CBACT04C.cbl:468, and both land in ledger.transactions. The
        //       union the reference assembled physically is therefore already materialised, so this
        //       case seeds both classes into one table and asserts the artefact carries both, once
        //       each. No concatenation step is invented, because inventing one would require
        //       materialising two intermediate datasets that the migrated model does not produce, and
        //       because state six stages the whole relation AFTER state five has committed its accrual
        //       rows -- so the two objects are no longer disjoint the way the reference's were, and
        //       concatenating them would duplicate every interest row.
        // WHY : Assumptions: this state's correctness DEPENDS on states four and five having
        //       committed, which is exactly what the sequencing of the orchestration guarantees. Both
        //       reference inputs at app/jcl/COMBTRAN.jcl:23-26 are named at the CURRENT generation,
        //       `(0)` rather than `(+1)`, so the reference reads what its predecessors just wrote.
        //       This job depends on the same sequencing for a different reason, and the difference is
        //       stated rather than glossed: its predecessors' COMMITS are what put the rows in the
        //       relation it reads, while their generations are only what its two preconditions require
        //       to EXIST.
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
     * The staged image does not change when the two named input generations do.
     *
     * <p>Pins {@code D-COMBINE-GENERATION-BYPASS} in
     * {@code docs/architecture/cobol-to-service-traceability.md}. The reference SORTS TWO PHYSICAL
     * DATASETS, named at {@code app/jcl/COMBTRAN.jcl:24} and {@code :26}, so every byte it emitted came
     * out of one of them. This job resolves the same two families as a PRECONDITION and composes its
     * output from the relation, so the coordinate each resolution answers with cannot reach the
     * artefact. That is the difference the register entry records, and this case measures it two ways
     * rather than arguing it: the same rows staged under two different pairs of input generation
     * numbers produce byte-identical images, and the seam the job holds publishes no operation that
     * could hand back a payload at all.</p>
     *
     * <p>Assumptions: the second launch uses a DIFFERENT run identifier. The durable step ledger skips
     * a repeat of the same run and step -- the behaviour
     * {@link #skipARepeatOfTheSameRunAndStepInsteadOfDuplicatingIt} owns -- so a second launch under the
     * same identifier would record no work, stage nothing, and leave this case comparing one captured
     * image against itself and passing for the wrong reason.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("stage an image independent of the two named input generations")
    void theCombinedImageIsIndependentOfTheTwoNamedGenerations() throws Exception {
        seed(interestRow(INTEREST_HYPHEN_ID, NEGATIVE_AMOUNT),
                postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));

        byte[] first = runAndCaptureStagedPayload();

        // WHY : Assumptions: the two inputs are re-stubbed to answer DIFFERENT generation numbers, and
        //       that is the only way this case can distinguish reading a payload from resolving a
        //       coordinate. A second run whose inputs answered the same numbers would produce identical
        //       bytes under either implementation, so the comparison would discriminate nothing.
        when(this.generations.resolveCurrentGeneration(DatasetFamily.TRANSACT_BKUP))
                .thenReturn(Optional.of(generation(DatasetFamily.TRANSACT_BKUP, 1)));
        when(this.generations.resolveCurrentGeneration(DatasetFamily.SYSTRAN))
                .thenReturn(Optional.of(generation(DatasetFamily.SYSTRAN, 2)));

        JobExecution second = runCombine(RUN_ID + "-restated-inputs");

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.stagedPayloads).hasSize(2);
        assertThat(this.stagedPayloads.get(1).body())
                .withFailMessage("the staged image changed when only the INPUT generation numbers"
                        + " changed, so a payload of one of them reached the output -- which"
                        + " D-COMBINE-GENERATION-BYPASS records as not happening")
                .isEqualTo(first);

        // WHY : Assumptions: the seam's SURFACE is asserted alongside the two images, because the
        //       images alone are a statement about this job's body and a later change could begin
        //       reading a payload without any assertion here failing. A collaborator that publishes no
        //       payload-returning operation cannot be read from at all, so the independence becomes a
        //       property of the seam rather than of one implementation of one job.
        assertThat(payloadReturningOperationsOf(DatasetGenerationService.class)).isEmpty();
        assertThat(publicOperationNamesOf(DatasetGenerationService.class))
                .containsExactlyInAnyOrderElementsOf(SEAM_OPERATIONS);
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
        //       a CHAR(16) column, and a column comparison resolves through the COLUMN's collation --
        //       which is why the column is declared COLLATE "C", in the owning service's
        //       V3__ledger_bytewise_collation.sql and mirrored in this module's harness script.
        //       Without that declaration the column would inherit the database default, and on an
        //       engine whose default gives punctuation no primary weight the emitted order would differ
        //       from the reference's for exactly the identifiers the accrual pass produces, because
        //       app/cbl/CBACT04C.cbl:476-480 concatenates the ten-character token into the key
        //       unchanged and one committed layout of that token carries hyphens.
        // WHY : Alternatives Considered: asserting the emitted order against a second query ordered by
        //       the same column, which is the shorter case to write. Rejected because both readings
        //       resolve through the SAME collation -- the job walks the derived finder
        //       TransactionRepository.findAllByOrderByTransactionIdAsc, which names no COLLATE clause
        //       of its own and inherits the column's -- so the pair would agree under every collation
        //       and the assertion would hold whatever the engine did. The expectation is therefore
        //       computed from the bytes, which is the only form of the claim that can fail when the
        //       engine's comparison stops matching the reference's.
        // WHY : Refactoring Rationale: that paragraph also said the column was one "the harness script
        //       declares without one", and it no longer is. The owning schema pins it and the harness
        //       mirrors the pin, so this case now observes a column whose collation is declared rather
        //       than inherited from whatever the container's initdb chose. What the pin does NOT do is
        //       make this case redundant: it still measures the emitted order against bytes computed
        //       here, and theEmittedOrderFollowsTheKeyColumnsPinnedCollation is what measures that the
        //       pin is the thing deciding it.
        // WHY : ⚠️ Assumptions: this case does not prove the DEPLOYED column carries that collation --
        //       it proves the order this harness produced was byte order, which is a different claim,
        //       and one that would also hold on a container whose default collation happened to agree.
        //       theDeployedIdentifierColumnIsCollatedByteWise is the case that reads the catalogue and
        //       binds the schema; the two are separate because either can fail without the other.
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
     * The deployed identifier column is collated byte-wise, which is what binds the ordering contract.
     *
     * <p>⚠️ Purpose: this is the case the ordering assertions above cannot be. They read an order back
     * and compare it with an order computed from bytes, so they establish what THIS schema produced;
     * they cannot establish that the schema says so, and a container whose default collation happened to
     * agree with byte order would satisfy them while a deployment whose default did not would fail in
     * production with every test green. Reading the catalogue is the only assertion that fails when the
     * declaration is missing rather than when an environment's default is unhelpful.
     *
     * <p>⚠️ Assumptions: the collation is read from {@code pg_attribute.attcollation} and NOT from
     * {@code pg_indexes.indexdef}, and the distinction is not stylistic. Once a column carries a
     * collation, an index over that column no longer prints a {@code COLLATE} clause of its own, because
     * the index collation equals the column's -- measured directly against PostgreSQL 17.10. An
     * assertion written against the index text would therefore fail on a correctly collated schema and
     * pass on one where only the index had been collated, which is the opposite of what is wanted.
     *
     * <p>⚠️ Assumptions: the free-text {@code description} is asserted to be UNCOLLATED in the same
     * case, because the property being pinned is that the obligation is SCOPED to the fixed-width
     * identifier and code columns. A migration that collated the whole table would satisfy an assertion
     * about {@code transaction_id} alone while changing the ordering of columns nothing asked to change.
     *
     * <p>⚠️ Refactoring Rationale: this half of the assertion named {@code card_num} and required it to
     * be uncollated, on the ground that collating it would reorder the card-ordered access path. That
     * premise was measured and does not hold, so the assertion is re-aimed rather than deleted. Every
     * card number in {@code app/data/ASCII/carddata.txt} is sixteen digits with no other character, and
     * ordering those fifty values under {@code "C"}, under {@code "default"} and under
     * {@code "en-US-x-icu"} on PostgreSQL 17.10 returns the same sequence three times -- zero positions
     * differ, because a linguistic collation has no case, accent or punctuation to weigh in a digit
     * string. The reference agrees: {@code app/jcl/TRANREPT.jcl} sorts this field as
     * {@code TRAN-CARD-NUM,263,16,ZD}, a zoned-decimal compare, which for a fixed-width all-digit field
     * is the byte compare. So the column IS pinned, deliberately, for the reason the owning migration
     * records -- {@code idx_transactions_card_num} replaced a physically re-sorted extract and the pin
     * documents the byte order its golden image was produced under -- and the pin changes no order.
     *
     * <p>Assumptions: {@code description} carries the negative half instead, because it is a column
     * where the collations genuinely diverge: ordering {@code ('a-1','A-1','a 1','b-1','B 1','_x','Zed')}
     * under {@code "C"} and under {@code "en-US-x-icu"} returns different sequences. Asserting the
     * absence of a pin THERE is what would actually fail if a migration collated the whole table, which
     * is the failure this half exists to catch.
     *
     * <p>⚠️ Assumptions: this asserts the HARNESS schema, which is what this module can reach, and the
     * harness declares the clause because the owning service's migration does. That is a two-place
     * literal and the mitigation is stated at the harness declaration: this case is what makes a drift
     * between them fail rather than pass quietly, in the direction that matters -- a harness that lost
     * the clause fails here.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the deployed identifier and card columns are collated byte-wise and free text is not")
    void theDeployedIdentifierColumnIsCollatedByteWise() {
        // WHY : Assumptions: the aliases are abbreviations rather than the words they stand for, because
        //       COLLATION and SCHEMA are reserved words in this engine's grammar -- `collation.collname`
        //       is refused with "syntax error at or near \".\"" rather than with anything naming the
        //       cause, which cost one run to diagnose. Quoting them would also work and would leave the
        //       same trap for the next reader.
        String collationOfColumn = """
                SELECT coalesce(coll.collname, 'default')
                  FROM pg_attribute att
                  JOIN pg_class rel ON rel.oid = att.attrelid
                  JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                  LEFT JOIN pg_collation coll ON coll.oid = att.attcollation
                 WHERE nsp.nspname = 'ledger'
                   AND rel.relname = 'transactions'
                   AND att.attname = ?
                """;

        assertThat(this.jdbc.queryForObject(collationOfColumn, String.class, "transaction_id"))
                .as("the ledger key must be collated \"C\", or ORDER BY on it is a linguistic order"
                        + " and the combine step stops reproducing app/jcl/COMBTRAN.jcl:28-30")
                .isEqualTo("C");
        assertThat(this.jdbc.queryForObject(collationOfColumn, String.class, "card_num"))
                .as("the card-number column is pinned too, because the index that replaced the"
                        + " physically re-sorted extract reads it; measured order-identical under C,"
                        + " default and en-US-x-icu for a sixteen-digit domain, so this is a guarantee"
                        + " rather than a change of order")
                .isEqualTo("C");
        assertThat(this.jdbc.queryForObject(collationOfColumn, String.class, "description"))
                .as("the obligation is scoped to the fixed-width identifier and code columns; a"
                        + " migration that collated the whole table would reorder free text, where the"
                        + " byte and linguistic orders genuinely differ")
                .isEqualTo("default");
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
        //       registers no collation that uses it, so the case has to declare one.
        // WHY : Refactoring Rationale: the declaration was made inline here and is now made through
        //       declareLinguisticCollation, because a second case needs the same collation and two
        //       copies of one DDL statement in one class are two places for a locale tag to drift. That
        //       helper carries the reasoning for the non-deterministic flag and for the surrounding
        //       transaction, both of which applied to this call site verbatim.
        declareLinguisticCollation();

        // WHY : Assumptions: the byte-wise reading still states COLLATE "C" explicitly even though the
        //       column now carries that collation, so this case keeps discriminating if the column's
        //       declaration is ever lost. Relying on the column here would make the comparison below
        //       "the default order versus a shifted order", which is a weaker claim than the one this
        //       case exists to make.
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
     * The job's order is decided by the COLUMN's collation, and that collation is pinned byte-wise.
     *
     * <p>⚠️ Purpose: this is the case the two above could not be. They establish that a byte-wise
     * order and a linguistic order differ, and that the job emits the byte-wise one -- but they
     * establish it on an engine whose own initdb default collation happens to BE byte-wise, so the
     * job's order was correct by accident of the container and production carried no explicit
     * collation anywhere: not on the column, not in the derived finder
     * {@code TransactionRepository.findAllByOrderByTransactionIdAsc}, and not in the harness that
     * mirrors this schema. Deployed against a database created with a linguistic default -- the
     * ordinary case, and the managed-service default -- the same code would have emitted a different
     * order, and the disagreement with {@code SORT FIELDS=(TRAN-ID,A)} at
     * {@code app/jcl/COMBTRAN.jcl:30} would have appeared in the produced dataset rather than in any
     * test.</p>
     *
     * <p>Assumptions: the discriminating collation is applied to the COLUMN and the ACTUAL JOB is then
     * run over it, rather than a second query being ordered with an explicit {@code COLLATE}. That is
     * the only form of the experiment that observes what the job does: the finder carries no
     * {@code COLLATE} clause and cannot, so whatever the column resolves to IS the job's order. Under
     * the linguistic collation the emitted order must therefore DIFFER from the byte-wise one, and the
     * fact that it does is what proves the pin is load-bearing rather than decorative.</p>
     *
     * <p>Assumptions: the pin is asserted in the CATALOGUE as well as through behaviour, because the
     * two fail for different reasons. The catalogue assertion fails if a future migration or harness
     * edit drops the collation, which is the regression this case exists to prevent; the behavioural
     * halves fail if the identifiers stop discriminating or if the finder acquires an ordering of its
     * own. Either alone would leave one of the two silent.</p>
     *
     * <p>⚠️ Assumptions: WHY the container's default happens to be byte-wise is worth naming, because it
     * is the reason every ordering assertion in this class passed for a reason that does not hold in
     * production. This module's pinned image runs musl, which implements no locale-specific collation, so
     * even its {@code en_US.utf8} compares byte-wise; Amazon Aurora PostgreSQL runs glibc and does not.
     * Reading the collation out of the catalogue is the only form of the claim that fails HERE when the
     * pin is absent.</p>
     *
     * <p>⚠️ Assumptions: {@code infra/} is not the place to fix this, and nothing there is asserted,
     * because there is nothing there to assert -- the AWS provider exposes no collation or locale
     * argument for an {@code aws_rds_cluster} on the PostgreSQL engine, so the cluster default is not
     * settable. The column is the only lever, which is why the pin lives in a migration.</p>
     *
     * <p>Measured: with the pin removed from the harness declaration this case is the only one of the
     * twenty-five here that fails, and it fails on the catalogue assertion rather than on an ordering --
     * which is the point, because on this engine the ordering would still have been byte-wise by
     * default. That is the whole shape of the defect being closed, reproduced from the test side.</p>
     *
     * <p>Assumptions: the column is restored in a {@code finally}, so a failure inside the linguistic
     * phase cannot leave the shared container's schema mis-collated for the cases that follow. The
     * restoration is then re-asserted in the catalogue, because a restore that silently failed would
     * hand every later case an engine ordering the key the wrong way.</p>
     *
     * <p>Trade-offs: repinning a primary-key column rewrites the table and rebuilds its index twice
     * inside one case, which is the slowest case in this class. The alternative -- starting a second
     * container with a linguistic initdb default -- was rejected because it doubles the container
     * cost of the module and because a harness that differs from production in a comparison-affecting
     * way makes every ordering assertion in this module a statement about the harness rather than
     * about the schema.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("emit the byte-wise order because the key column is pinned to it, not by default")
    void theEmittedOrderFollowsTheKeyColumnsPinnedCollation() throws Exception {
        seed(interestRow(INTEREST_HYPHEN_ID, NEGATIVE_AMOUNT),
                interestRow(INTEREST_HYPHEN_ID_NEXT, POSITIVE_AMOUNT),
                postedRow(POSTED_DIGITS_ID, POSITIVE_AMOUNT),
                postedRow(POSTED_SEED_ID_NEXT, POSITIVE_AMOUNT),
                postedRow(POSTED_SEED_ID, NEGATIVE_AMOUNT));
        declareLinguisticCollation();

        assertThat(keyColumnCollation())
                .as("the harness must mirror the owning schema's pin from"
                        + " V3__ledger_bytewise_collation.sql")
                .isEqualTo(BYTE_WISE_COLLATION);

        List<String> underLinguisticCollation;
        try {
            repinKeyColumn(LINGUISTIC_COLLATION);
            underLinguisticCollation = emittedIdentifiers(runAndCaptureStagedPayload());
        } finally {
            repinKeyColumn('"' + BYTE_WISE_COLLATION + '"');
        }
        List<String> underThePin = emittedIdentifiers(capturedPayloadOf("batch-run-combine-0003"));

        assertThat(underThePin)
                .as("with the column pinned the job emits the DFSORT CH order of COMBTRAN.jcl:30")
                .containsExactlyElementsOf(byteWiseAscending(underThePin));
        assertThat(underLinguisticCollation)
                .withFailMessage("the job emitted the same order under a linguistic column collation"
                        + " as under the byte-wise pin, so this case is no longer proving that the pin"
                        + " decides the order; either the seeded identifiers stopped discriminating or"
                        + " the ordered read acquired a collation of its own")
                .isNotEqualTo(underThePin);
        assertThat(keyColumnCollation())
                .as("the column must be left pinned for every case that follows in this class")
                .isEqualTo(BYTE_WISE_COLLATION);

        // WHY : Assumptions: the database default is read alongside the column's collation and is
        //       deliberately NOT asserted to be anything in particular. It is read so that a failure
        //       message shows which default the column is being protected FROM -- byte-wise on this
        //       image and linguistic on the deployed engine -- and it is left unasserted because the
        //       whole point of the pin is that the default no longer decides the order.
        assertThat(this.jdbc.queryForObject(
                        "SELECT datcollate FROM pg_database WHERE datname = current_database()",
                        String.class))
                .as("the database default collation, recorded for diagnosis only")
                .isNotBlank();
    }

    /**
     * Declares the linguistic collation this class compares against, committing the declaration.
     *
     * <p>Assumptions: the collation is declared here rather than assumed to exist, and the declaration
     * is idempotent, because two cases in this class need it and neither may depend on the other
     * having run first. It is declared non-deterministic because a shifted punctuation weighting can
     * rank two distinct strings equal at every level, which a deterministic collation may not do.</p>
     *
     * <p>Assumptions: the statement runs inside a transaction so that it COMMITS. The pool's
     * auto-commit is off, so a statement issued outside a transaction is executed and then discarded
     * when the connection is returned -- and because this engine keeps schema changes transactional,
     * the collation would simply not exist by the time the next statement looked for it.</p>
     */
    private void declareLinguisticCollation() {
        this.transactionTemplate.executeWithoutResult(status ->
                this.jdbc.execute("CREATE COLLATION IF NOT EXISTS " + LINGUISTIC_COLLATION
                        + " (provider = icu, locale = 'en-u-ka-shifted', deterministic = false)"));
    }

    /**
     * Repins the transaction master's key column to a named collation, rebuilding its index.
     *
     * @param collation the collation to apply, already quoted or schema-qualified as the engine
     *     requires, of type {@code String}; must not be {@code null}
     */
    private void repinKeyColumn(String collation) {
        this.transactionTemplate.executeWithoutResult(status ->
                this.jdbc.execute("ALTER TABLE ledger.transactions ALTER COLUMN transaction_id"
                        + " SET DATA TYPE CHAR(16) COLLATE " + collation));
    }

    /**
     * Reads the collation the engine records for the transaction master's key column.
     *
     * <p>Assumptions: the catalogue is read through {@code information_schema.columns}, whose
     * {@code collation_name} is NULL for a column that merely inherits the database default and names
     * the collation for a column that pins one. That distinction is the whole point of reading it: a
     * query that only compared orderings could not tell a pinned column from a database whose default
     * happens to agree with the pin, which is exactly the state this case was written to end.</p>
     *
     * @return the recorded collation name, or {@code null} when the column inherits the default
     */
    private String keyColumnCollation() {
        return this.jdbc.queryForObject("select collation_name from information_schema.columns"
                + " where table_schema = 'ledger' and table_name = 'transactions'"
                + " and column_name = 'transaction_id'", String.class);
    }

    /**
     * Runs the job under a distinct run identifier and returns the payload that run staged.
     *
     * <p>Assumptions: the payload is taken by POSITION from the accumulated list rather than by
     * clearing it, because the durable step ledger keys on the run identifier and a repeat of one
     * already recorded is skipped entirely -- so each phase of a two-phase case must launch under an
     * identifier of its own and the earlier payloads necessarily remain in the list.</p>
     *
     * @param runId the orchestrator execution identifier to launch under, of type {@code String};
     *     must not be {@code null} and must not repeat one already used in the same case
     * @return the bytes that run handed to the staging seam, never {@code null}
     * @throws Exception if the framework's own execution path raises
     */
    private byte[] capturedPayloadOf(String runId) throws Exception {
        int before = this.stagedPayloads.size();
        JobExecution execution = runCombine(runId);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.stagedPayloads).hasSize(before + 1);
        return this.stagedPayloads.get(before).body();
    }

    /**
     * Reads the identifiers out of a staged payload in the order the payload carries them.
     *
     * @param payload one whole staged image, of type {@code byte[]}; must not be {@code null}
     * @return the identifiers in emitted order, never {@code null}
     */
    private static List<String> emittedIdentifiers(byte[] payload) {
        return splitIntoRecords(payload).stream()
                .map(CombineTransactionsJobTest::identifierOf)
                .toList();
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

        // WHY : Assumptions: the trailing pad is asserted PRESENT, uniform and carrying the byte the
        //       reference leaves there. Section 6.1 of the fixture contract measures that byte as a low
        //       value on this record, in the posting expectation and in all three interest
        //       expectations, because no statement in either producer touches the item.
        // WHY : Refactoring Rationale: this assertion required a blank and its rationale recorded the
        //       resulting difference from the reference as unavoidable, on the ground that the shared
        //       encoder rebuilds a dropped pad as blanks. The record mapper now writes the measured
        //       byte, so the difference is gone and the assertion states the reference's byte instead of
        //       excusing another one. A staged generation is read positionally by whoever consumes it,
        //       so twenty bytes per record is not a difference that can be argued as immaterial.
        assertThat(record).hasSize(TRANSACTION_RECORD_LENGTH);
        assertThat(spec.field("FILLER").start()).isEqualTo(TRAILING_PAD_OFFSET);
        assertThat(distinctBytes(record, TRAILING_PAD_OFFSET, TRANSACTION_RECORD_LENGTH))
                .containsExactly(LOW_VALUE_CHARACTER);
    }

    /**
     * Each staged record equals an expected image composed from the copybook, byte for byte.
     *
     * <p>Pins the layout half of {@code D-COMBINE-NO-LOADBACK} in
     * {@code docs/architecture/cobol-to-service-traceability.md}, which claims the combined generation
     * preserves the reference's record layout. Every other case in this class reads the emitted bytes
     * through the registered layout, which is the right way to assert a field's CONTENT and the wrong
     * way to assert the LAYOUT: an expectation taken from the registry agrees with the encoder by
     * construction, and the pair could drift from {@code app/cpy/CVTRA05Y.cpy} together with every
     * assertion still passing. This case composes the whole three-hundred-and-fifty-byte image from
     * {@link #TRANSCRIBED_LAYOUT} -- widths transcribed from the copybook's picture clauses -- and the
     * overpunch tables transcribed from the parity oracle's own codec, and compares it against what the
     * step staged.</p>
     *
     * <p>Assumptions: both row classes are composed, because the amount is the one field whose rendering
     * depends on the value's SIGN, and a positive-only expectation would agree with an encoder that had
     * lost the negative half of the overpunch convention.</p>
     *
     * <p>Assumptions: the transcription is cross-checked against the registered layout AFTERWARDS, in a
     * second block, and the ordering matters. The comparison above is the oracle; the cross-check below
     * only reports WHERE a disagreement is, so a drift between the copybook and the registry names the
     * field rather than showing three hundred and fifty characters of diff.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("match an image composed from the copybook rather than from the layout registry")
    void theStagedRecordMatchesAnIndependentlyComposedImage() throws Exception {
        assertThat(transcribedRecordLength()).isEqualTo(TRANSACTION_RECORD_LENGTH);

        seed(interestRow(INTEREST_HYPHEN_ID, NEGATIVE_AMOUNT),
                postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));

        List<byte[]> records = splitIntoRecords(runAndCaptureStagedPayload());

        // WHY : Assumptions: each expectation names its own producer's description pad -- the blank for
        //       the posted row and the low value for the accrual row -- because the job selects the
        //       registered layout per row from the source and description the reference itself writes,
        //       so the two rows of this one stream are padded differently on purpose. Composing both
        //       with one pad would agree with an encoder that had lost the distinction.
        String expectedPosted = composeRecordImage(transcribedValuesFor(POSTED_SEED_ID,
                POSTED_CATEGORY_CD, POSTED_SOURCE, POSTED_DESCRIPTION, POSITIVE_AMOUNT), ' ');
        String expectedInterest = composeRecordImage(transcribedValuesFor(INTEREST_HYPHEN_ID,
                INTEREST_CATEGORY_CD, INTEREST_SOURCE, INTEREST_DESCRIPTION, NEGATIVE_AMOUNT),
                LOW_VALUE_CHARACTER);

        // WHY : Assumptions: the comparison is over the WHOLE record and not field by field, because a
        //       field-by-field comparison would read each span at the offset the reader chose and would
        //       therefore agree with an image whose fields were all placed one byte along. Comparing one
        //       contiguous string is what makes a misplaced field fail.
        assertThat(new String(recordFor(records, POSTED_SEED_ID), StandardCharsets.US_ASCII))
                .isEqualTo(expectedPosted);
        assertThat(new String(recordFor(records, INTEREST_HYPHEN_ID), StandardCharsets.US_ASCII))
                .isEqualTo(expectedInterest);

        // WHY : Assumptions: the two amount spans are additionally asserted as LITERALS read out of the
        //       staged records, at the offset the transcription puts them at. The whole-record
        //       comparisons above already imply these, so the value here is diagnostic and it is worth
        //       the duplication: a sign-convention regression is the failure section 5.2 of
        //       tests/README.md records as silent, and a case that reported it as three hundred and
        //       fifty characters of unequal string would bury which byte moved. Positive seven renders
        //       as `G` and negative four as `M`, so the two literals also state the convention at a
        //       place a reader can check against the tables above.
        int amountOffset = transcribedOffsetOf("TRAN-AMT");
        int amountWidth = transcribedWidthOf("TRAN-AMT");
        assertThat(new String(recordFor(records, POSTED_SEED_ID), amountOffset, amountWidth,
                StandardCharsets.US_ASCII)).isEqualTo("0000005047G");
        assertThat(new String(recordFor(records, INTEREST_HYPHEN_ID), amountOffset, amountWidth,
                StandardCharsets.US_ASCII)).isEqualTo("0000000123M");

        int offset = 0;
        for (CopybookField field : TRANSCRIBED_LAYOUT) {
            CopybookLayout.FieldSpec registered = CopybookLayout.layout("TRAN").field(field.name());
            assertThat(registered.start()).as("registered offset of %s", field.name())
                    .isEqualTo(offset);
            assertThat(registered.length()).as("registered width of %s", field.name())
                    .isEqualTo(field.width());
            offset += field.width();
        }
        assertThat(offset).isEqualTo(TRANSACTION_RECORD_LENGTH);
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
     * Each producer's description pad is emitted as that producer wrote it, both classes side by side.
     *
     * <p>Pins section 6.3 of the fixture contract, which measures the reference's description pad as
     * job-dependent: the accrual pass leaves the tail of the field at low values because it assembles
     * the text with {@code STRING} at {@code app/cbl/CBACT04C.cbl:485-489}, while the posting pass
     * blank-pads it because it moves an already-padded field at {@code app/cbl/CBTRN02C.cbl:429}. This
     * artefact is the only one in the chain that holds both classes at once -- the reference builds it
     * by concatenating {@code TRANSACT.BKUP(0)} and {@code SYSTRAN(0)} at
     * {@code app/jcl/COMBTRAN.jcl:24} and {@code :26} -- so it is the only place the two pads can be
     * compared against each other.</p>
     *
     * <p>Refactoring Rationale: this case previously asserted that both classes carried the SAME pad and
     * recorded the reference's low-value pad as unreproducible, on the ground that a relational row
     * remembers nothing about how its writer padded a text field. That is true of the pad bytes and
     * false of the producer: the accrual pass writes an attribution the reference itself uses to tell
     * its two writers apart -- the {@code System} source at {@code app/cbl/CBACT04C.cbl:484} and the
     * {@code Int. for a/c } prefix at {@code :485} -- so the job selects the layout per row from those
     * two fields and the encoder pads from the layout. The case now asserts the two pads exactly. What
     * was called faking is a schema-carried discriminator: the source is a ten-character closed-domain
     * label, and requiring the description prefix ALSO means a row must match both marks to be
     * misclassified.</p>
     *
     * <p>Refactoring Rationale: the difference is therefore NOT a registered divergence. It was carried
     * for a time as {@code D-COMBINE-DESC-PAD} in
     * {@code docs/architecture/cobol-to-service-traceability.md}, on the reading that a relational row
     * cannot remember how its writer padded a text field; the per-row layout selection this case
     * measures removed the premise, so the entry is withdrawn there rather than left asserting a
     * divergence the emitted bytes no longer show.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("emit each producer's own description pad, both row classes in one stream")
    void emitEachProducersOwnDescriptionPad() throws Exception {
        seed(interestRow(INTEREST_HYPHEN_ID, NEGATIVE_AMOUNT),
                postedRow(POSTED_SEED_ID, POSITIVE_AMOUNT));

        List<byte[]> records = splitIntoRecords(runAndCaptureStagedPayload());
        CopybookLayout.FieldSpec description = CopybookLayout.layout("TRAN").field("TRAN-DESC");
        int accrualPadStart = description.start() + INTEREST_DESCRIPTION.length();
        int postedPadStart = description.start() + POSTED_DESCRIPTION.length();
        int padEnd = description.start() + description.length();

        byte[] interestRecord = recordFor(records, INTEREST_HYPHEN_ID);
        byte[] postedRecord = recordFor(records, POSTED_SEED_ID);

        // WHY : Assumptions: the text is asserted before the pad, and each is asserted for both rows,
        //       because the two claims fail differently. A wrong text means the row was assembled
        //       wrongly; a wrong pad means the row was attributed to the wrong producer, and only the
        //       pair together distinguishes those two.
        assertThat(fieldOf(interestRecord, "TRAN-DESC")).startsWith(INTEREST_DESCRIPTION);
        assertThat(fieldOf(postedRecord, "TRAN-DESC")).startsWith(POSTED_DESCRIPTION);
        assertThat(distinctBytes(interestRecord, accrualPadStart, padEnd))
                .as("the accrual pass leaves exactly the reference's low values behind its text")
                .containsExactly(LOW_VALUE_CHARACTER);
        assertThat(distinctBytes(postedRecord, postedPadStart, padEnd))
                .as("the posting pass blank-pads its own description")
                .containsExactly(' ');
        assertThat(padEnd - accrualPadStart)
                .as("section 6.3 measures the accrual tail as exactly seventy-six bytes")
                .isEqualTo(76);

        // WHY : Assumptions: the trailing pad of BOTH rows is asserted low-valued in the same case,
        //       because the two pad rules are independent and stating them together is what shows a
        //       reader that only ONE of the two differs by producer. A change that made the trailing pad
        //       follow the description pad would pass every other assertion here.
        assertThat(distinctBytes(interestRecord, TRAILING_PAD_OFFSET, TRANSACTION_RECORD_LENGTH))
                .containsExactly(LOW_VALUE_CHARACTER);
        assertThat(distinctBytes(postedRecord, TRAILING_PAD_OFFSET, TRANSACTION_RECORD_LENGTH))
                .containsExactly(LOW_VALUE_CHARACTER);

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
     * Composes one whole record image from the transcribed layout and a value per field.
     *
     * <p>Assumptions: the composition walks {@link #TRANSCRIBED_LAYOUT} in declaration order and
     * concatenates each field's rendered span, so the resulting offsets are a CONSEQUENCE of the
     * transcribed widths rather than a copy of any offset table. That is what makes the result usable as
     * an oracle: if the encoder placed a field one byte off, the comparison fails on content at that
     * field and every field behind it, which is exactly the symptom a fixed-width reader would see.</p>
     *
     * <p>Assumptions: the two PAD REGIONS are rendered with the byte the WRITING PRODUCER leaves in
     * them, and both bytes are transcribed here from the reference rather than read from the encoder.
     * The trailing {@code FILLER} is the low value under both producers, because neither writes it: the
     * posting pass at {@code app/cbl/CBTRN02C.cbl:429} onward moves named fields only, and the accrual
     * pass at {@code app/cbl/CBACT04C.cbl:484-489} does the same, which is what the twenty low values
     * behind every record of {@code tests/golden/posting/}{@code *}{@code /tranfile.expected} measure.
     * The description tail is the caller's, because it is the one span the two producers differ on: the
     * posting pass blank-pads it by moving an already blank-padded feed field, while the accrual pass
     * leaves it at low values because {@code STRING} writes only the characters it was handed.</p>
     *
     * @param values the value each field carries, of type {@code Map<String, Object>}, keyed by the
     *     copybook field name; a field absent from the map renders as an empty text value, which is how
     *     the trailing pad is expressed; must not be {@code null}
     * @param descriptionPad the char the writing producer leaves behind the description text, being the
     *     blank for a posted row and {@link #LOW_VALUE_CHARACTER} for an accrual row
     * @return the whole record as the characters the image is expected to hold, never {@code null}
     * @throws IllegalArgumentException if a value is wider than the field's declared width, because a
     *     truncating oracle would silently agree with a truncating encoder
     */
    private static String composeRecordImage(Map<String, Object> values, char descriptionPad) {
        StringBuilder image = new StringBuilder();
        for (CopybookField field : TRANSCRIBED_LAYOUT) {
            image.append(switch (field.kind()) {
                case TEXT -> leftJustified(
                        (String) values.getOrDefault(field.name(), ""), field.width(),
                        transcribedPadFor(field.name(), descriptionPad));
                case UNSIGNED_DIGITS -> zeroFilled(
                        String.valueOf(values.get(field.name())), field.width());
                case SIGNED_ZONED -> zonedOverpunched(
                        (BigDecimal) values.get(field.name()), field.width());
            });
        }
        return image.toString();
    }

    /**
     * Builds the per-field values one seeded row is expected to render into.
     *
     * <p>Assumptions: the values restate what {@link #row} committed rather than reading the row back,
     * so the expectation is written from the fixture's inputs and not from anything the system under
     * test produced or stored. The two timestamp values are the RENDERED twenty-six-character forms
     * because the copybook declares those spans as text.</p>
     *
     * @param transactionId the sixteen-character identifier the row is keyed on, of type
     *     {@code String}; must not be {@code null}
     * @param categoryCd the four-digit category code, of type {@code String}; must not be {@code null}
     * @param source the source label, of type {@code String}; must not be {@code null}
     * @param description the description text, of type {@code String}; must not be {@code null}
     * @param amount the monetary value, of type {@code BigDecimal} at scale two, which may be negative;
     *     must not be {@code null}
     * @return the value map {@link #composeRecordImage} renders, never {@code null}
     */
    private static Map<String, Object> transcribedValuesFor(String transactionId, String categoryCd,
            String source, String description, BigDecimal amount) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("TRAN-ID", transactionId);
        values.put("TRAN-TYPE-CD", TYPE_CD);
        values.put("TRAN-CAT-CD", categoryCd);
        values.put("TRAN-SOURCE", source);
        values.put("TRAN-DESC", description);
        values.put("TRAN-AMT", amount);
        values.put("TRAN-MERCHANT-ID", String.valueOf(MERCHANT_ID));
        values.put("TRAN-MERCHANT-NAME", MERCHANT_NAME);
        values.put("TRAN-MERCHANT-CITY", MERCHANT_CITY);
        values.put("TRAN-MERCHANT-ZIP", MERCHANT_ZIP);
        values.put("TRAN-CARD-NUM", CARD_NUM);
        values.put("TRAN-ORIG-TS", SEEDED_ORIGINATION_STAMP);
        values.put("TRAN-PROC-TS", SEEDED_PROCESSING_STAMP);
        return values;
    }

    /**
     * Renders a text value into its declared span: left-justified, padded on the right with one byte.
     *
     * @param value the value the field carries, of type {@code String}; must not be {@code null}
     * @param width the field's transcribed width in bytes, of type {@code int}
     * @param pad the char the span is filled with behind the value, which is the blank for every field
     *     except the two the reference's producers leave otherwise
     * @return the rendered span, exactly {@code width} characters, never {@code null}
     * @throws IllegalArgumentException if the value is wider than the span
     */
    private static String leftJustified(String value, int width, char pad) {
        if (value.length() > width) {
            throw new IllegalArgumentException("the oracle was given '" + value + "', which is "
                    + value.length() + " characters and does not fit the " + width
                    + "-byte span app/cpy/CVTRA05Y.cpy declares");
        }
        return value + String.valueOf(pad).repeat(width - value.length());
    }

    /**
     * Names the byte the reference leaves behind one transcribed text field's content.
     *
     * <p>Assumptions: only two of the fourteen transcribed fields have a pad of their own. The trailing
     * {@code FILLER} is the low value under both producers because neither writes it, and the
     * description carries whichever pad the writing producer leaves. Every other text field is filled by
     * a {@code MOVE} into an alphanumeric receiver, which blank-pads over the whole declared width.</p>
     *
     * <p>Alternatives Considered: asking the registered layout which byte applies. Rejected for the same
     * reason {@link #TRANSCRIBED_LAYOUT} is transcribed rather than read: an expectation taken from the
     * encoder's own tables agrees with it by construction, and would keep agreeing if the encoder and
     * the reference drifted apart together.</p>
     *
     * @param fieldName the copybook field name, of type {@code String}; must not be {@code null}
     * @param descriptionPad the char the writing producer leaves behind the description text
     * @return the char this field's declared span is padded with
     */
    private static char transcribedPadFor(String fieldName, char descriptionPad) {
        return switch (fieldName) {
            case "FILLER" -> LOW_VALUE_CHARACTER;
            case "TRAN-DESC" -> descriptionPad;
            default -> ' ';
        };
    }

    /**
     * Renders unsigned digits into their declared span: right-justified, filled on the left with zeroes.
     *
     * @param digits the digits the field carries, of type {@code String}; must not be {@code null}
     * @param width the field's transcribed width in bytes, of type {@code int}
     * @return the rendered span, exactly {@code width} characters, never {@code null}
     * @throws IllegalArgumentException if more digits are given than the span holds
     */
    private static String zeroFilled(String digits, int width) {
        if (digits.length() > width) {
            throw new IllegalArgumentException("the oracle was given " + digits.length()
                    + " digits for a " + width + "-byte unsigned span");
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Renders a signed value into a zoned span whose low-order byte carries the sign as an overpunch.
     *
     * <p>Assumptions: the digits are taken from the value's UNSCALED form at scale two, which is the
     * two-implied-decimal representation {@code S9(09)V99} declares, and the decimal point is written
     * nowhere because the picture clause implies it. The sign then replaces the low-order DIGIT rather
     * than being appended, which is the whole content of the overpunch convention: eleven digit
     * positions carry eleven digits and a sign in eleven bytes.</p>
     *
     * @param value the value the field carries, of type {@code BigDecimal}; must not be {@code null}
     * @param width the field's transcribed width in bytes, of type {@code int}
     * @return the rendered span, exactly {@code width} characters, never {@code null}
     * @throws IllegalArgumentException if the value needs more digit positions than the span holds
     */
    private static String zonedOverpunched(BigDecimal value, int width) {
        String filled = zeroFilled(
                value.abs().setScale(2, RoundingMode.UNNECESSARY).unscaledValue().toString(),
                width);
        int lowOrderDigit = filled.charAt(width - 1) - '0';
        String table = value.signum() < 0 ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
        return filled.substring(0, width - 1) + table.charAt(lowOrderDigit);
    }

    /**
     * Sums the transcribed widths, so the transcription's own arithmetic is asserted rather than assumed.
     *
     * @return the record length the transcribed fields add up to, of type {@code int}
     */
    private static int transcribedRecordLength() {
        return TRANSCRIBED_LAYOUT.stream().mapToInt(CopybookField::width).sum();
    }

    /**
     * Computes one transcribed field's zero-based offset by summing the widths declared ahead of it.
     *
     * <p>Assumptions: the offset is DERIVED from the transcribed widths and never read from the
     * registered layout, for the same reason the expected image is composed rather than decoded: an
     * offset taken from the registry would place the span wherever the encoder placed it.</p>
     *
     * @param fieldName the copybook field name to locate, of type {@code String}; must not be
     *     {@code null}
     * @return the field's zero-based offset within the record, of type {@code int}
     * @throws IllegalArgumentException if the transcription declares no such field
     */
    private static int transcribedOffsetOf(String fieldName) {
        int offset = 0;
        for (CopybookField field : TRANSCRIBED_LAYOUT) {
            if (field.name().equals(fieldName)) {
                return offset;
            }
            offset += field.width();
        }
        throw new IllegalArgumentException(
                "app/cpy/CVTRA05Y.cpy declares no field named '" + fieldName + "'");
    }

    /**
     * Reads one transcribed field's declared width.
     *
     * @param fieldName the copybook field name to measure, of type {@code String}; must not be
     *     {@code null}
     * @return the field's width in bytes, of type {@code int}
     * @throws IllegalArgumentException if the transcription declares no such field
     */
    private static int transcribedWidthOf(String fieldName) {
        return TRANSCRIBED_LAYOUT.stream()
                .filter(field -> field.name().equals(fieldName))
                .mapToInt(CopybookField::width)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "app/cpy/CVTRA05Y.cpy declares no field named '" + fieldName + "'"));
    }

    /**
     * Lists the public instance operations a type declares.
     *
     * <p>Assumptions: only DECLARED methods are read, so nothing inherited from {@code Object} is
     * counted, and synthetic methods the compiler generates are skipped because a bridge method would
     * otherwise appear as a second operation under the same name.</p>
     *
     * @param seam the collaborator type to measure, of type {@code Class<?>}; must not be {@code null}
     * @return the operation names, each once, never {@code null}
     */
    private static List<String> publicOperationNamesOf(Class<?> seam) {
        return Arrays.stream(seam.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Lists the public operations of a type whose return type could carry a dataset's contents.
     *
     * @param seam the collaborator type to measure, of type {@code Class<?>}; must not be {@code null}
     * @return the names of any operations returning a byte or character source, never {@code null}
     */
    private static List<String> payloadReturningOperationsOf(Class<?> seam) {
        return Arrays.stream(seam.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .filter(method -> PAYLOAD_RETURN_TYPES.stream()
                        .anyMatch(payload -> payload.isAssignableFrom(method.getReturnType())))
                .map(Method::getName)
                .distinct()
                .sorted()
                .toList();
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
     * One field of the independently transcribed record layout.
     *
     * @param name the copybook field name, as {@code app/cpy/CVTRA05Y.cpy} spells it, never
     *     {@code null}
     * @param width the field's declared width in bytes, taken from its picture clause rather than from
     *     the registered layout
     * @param kind how the field's value occupies those bytes, never {@code null}
     */
    private record CopybookField(String name, int width, FieldKind kind) {
    }

    /**
     * The three ways a field of the transaction record occupies its declared bytes.
     *
     * <p>Assumptions: three kinds are enough for this record because its fourteen fields use only
     * three picture forms -- {@code X(n)}, {@code 9(n)} and {@code S9(n)V99}. A packed field would need
     * a fourth, and this record declares none; {@code app/cpy/CVEXPORT.cpy} is where packed fields
     * appear and {@code ExportRecordMapperTest} owns them.</p>
     */
    private enum FieldKind {

        /** A {@code PIC X(n)} field: the value left-justified and padded on the right with blanks. */
        TEXT,

        /** A {@code PIC 9(n)} field: the digits right-justified and filled on the left with zeroes. */
        UNSIGNED_DIGITS,

        /** A {@code PIC S9(n)V99} field: zoned digits whose low-order byte carries the sign. */
        SIGNED_ZONED
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
