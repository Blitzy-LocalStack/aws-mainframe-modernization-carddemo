package com.carddemo.batch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.service.CategoryBalanceService;
import com.carddemo.batch.service.DailyFeedWatermarkService;
import com.carddemo.batch.service.PostingRecordUnitOfWork;
import com.carddemo.batch.service.PostingValidationService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Establishes the durable behaviour of the accumulating feed's consumed position, against PostgreSQL.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>Refactoring Rationale: {@code batch.daily_feed_watermark} is the SECOND table this module owns and
 * it had no engine-backed test of any kind, while this package's charter claimed complete coverage of
 * the module's owned persistence. That combination is the defect: the row decides where the nightly
 * posting pass RESUMES, so a regression in it does not corrupt one record -- it either re-posts a night
 * already posted, which under the additive model of {@code app/cbl/CBTRN02C.cbl:202-219} adds every
 * amount to its account balance a second time, or it skips a night, which loses the postings outright.
 * Neither failure is visible in a single record's assertions and neither can be reached with a mocked
 * repository, because every property that protects against them -- a primary key that refuses a second
 * consumer, a row lock that serialises two passes, a rollback that restores a position -- is an engine
 * behaviour.</p>
 *
 * <p>Assumptions: the row exists because the migrated feed is not the reference's feed.
 * {@code app/jcl/POSTTRAN.jcl:30-31} supplies {@code AWS.M2.CARDDEMO.DALYTRAN.PS} as a flat sequential
 * dataset that is REPLACED between runs, so "the whole file" and "tonight's transactions" are the same
 * set and {@code CBTRN02C} needs no cursor at all. The target's feed accumulates, its rows being the
 * audit trail the verification passes compare against, so the identity the replaced dataset used to
 * carry has to be carried by something -- and this table is that something. There is therefore no
 * baseline paragraph to transcribe here and no golden master to compare against: every case below is a
 * statement about the MIGRATION's own mechanism, and each one says which failure it exists to catch.</p>
 *
 * <h2>What this class owns, and the line it does not cross</h2>
 *
 * <p>Assumptions: {@code DailyFeedWatermarkServiceTest} beside it already owns the DECISIONS --
 * that an absent row reads as nothing consumed, that a request at or below the stored position is a
 * no-op, that a negative position is refused, that the locked and unlocked reads are distinct members.
 * Those are assertions over a mocked repository and they remain correct. What is added here is
 * everything that mock cannot host: that the migration really creates the table the mapping validates
 * against, that each declared check really refuses the row it exists to refuse, that the lock really
 * serialises, that a rollback really restores, and that the advance really commits with the postings it
 * accounts for. Nothing here re-asserts a decision the unit test owns.</p>
 *
 * <p>Assumptions: the co-commit cases drive the PRODUCTION per-record unit,
 * {@code PostingRecordUnitOfWork.applyOneRecord}, rather than calling the watermark service and a
 * repository in sequence. Calling the two in sequence would prove that two writes made inside one
 * template commit together, which is a property of the template; driving the unit proves that the
 * production code path pairs them, which is the property that decides whether a re-run double-posts.</p>
 *
 * <p>Assumptions: {@code AccountRepositoryIT} in this package also reads this table, and the overlap is
 * deliberate and disjoint. That class starts from an ABSENT row every time and owns the checkpoint as
 * one of four durable effects of one accepted record. This class owns the states that class never
 * reaches -- an EXISTING position advanced, an existing position surviving a refusal, two passes
 * competing for the same row -- and it owns the table's own schema. Neither is a copy of the other.</p>
 *
 * <p>Trade-offs: the two concurrency cases hold a transaction open for a bounded interval on a
 * background thread, which is slower and more intricate than every other case here. That cost is
 * accepted because the two properties they establish -- what happens when two first passes race, and
 * what happens when two later passes overlap -- are the only ones on which the table's whole purpose
 * turns, and neither can be observed by a single-threaded test. Both are bounded: every wait has a
 * timeout, the pool's four connections are enough for two writers and an observer, and the profile
 * pins {@code lock_timeout=30000} so a genuinely stuck lock fails the case rather than hanging it.</p>
 *
 * <p>Assumptions: no diagnostic in this class renders a whole record or an unmasked primary account
 * number. The posting cases resolve through a cross-reference keyed by a card number, and the fixture
 * that number uses carries the unassigned {@code 9900} prefix and fails the Luhn check for the reason
 * {@code AccountRepositoryIT} asserts mechanically; only its last four digits appear in a message.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
@Testcontainers
// WHY : Refactoring Rationale: the Parameter Store config-data location is DISABLED for this context
//       for the reason every sibling integration test in this package records: application.yml
//       declares an `optional:aws-parameterstore:` location whose resolution builds an SSM client
//       while configuration is still loading, and on a build host with no AWS_REGION that client is
//       built from an unresolved placeholder and the context aborts before a single assertion runs.
//       The flag stops the LOAD rather than the resolution, which is why it is sufficient.
@SpringBootTest(
        classes = DailyFeedWatermarkRepositoryIT.WatermarkPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.aws.parameterstore.enabled=false")
@ActiveProfiles("test")
class DailyFeedWatermarkRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every sibling integration test in this build pins, so one
     * engine serves the whole suite and two classes cannot disagree about one schema. The version is
     * recorded in prose because a digest states nothing a reader recognises.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The classpath-relative path of the harness that supplies the foreign schemas.
     *
     * <p>Assumptions: this class needs the script for its two co-commit cases and for the mapping
     * validation that precedes every case. The table under test arrives from this module's own
     * {@code db/migration/V2__batch_feed_watermark.sql} through Flyway, but the provider validates
     * EVERY entity in the scanned package against the deployed schema, and the posting unit writes
     * {@code account.accounts}, {@code ledger.transaction_category_balances} and
     * {@code ledger.transactions} -- none of which this module's migrations create or may create.</p>
     */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /** The one feed this deployment carries, named by its record layout rather than its table. */
    private static final String FEED = DailyFeedWatermarkService.DAILY_TRANSACTION_FEED;

    /** The run identifier the seeding advance is attributed to. */
    private static final String SEEDING_RUN_ID = "watermark-it-seed";

    /** The run identifier a competing or later advance is attributed to. */
    private static final String COMPETING_RUN_ID = "watermark-it-competitor";

    /** The business-date token the seeding advance is attributed to, ten characters as declared. */
    private static final String SEEDING_BUSINESS_DATE = "2022-07-18";

    /** The business-date token a competing or later advance is attributed to. */
    private static final String COMPETING_BUSINESS_DATE = "2022-07-19";

    /** The instant the seeding advance is stamped with, minted from the settable clock. */
    private static final LocalDateTime SEEDING_INSTANT = LocalDateTime.of(2022, 7, 18, 2, 5, 30);

    /**
     * The instant a later advance is stamped with, one hour after the seeding instant.
     *
     * <p>Assumptions: it differs from {@link #SEEDING_INSTANT} deliberately, and the difference is what
     * makes the "changes nothing" and "restores the previous position" cases discriminating. The clock
     * is moved to this instant before the advance that must NOT take effect, so a stored timestamp that
     * still reads the seeding instant proves the row was not rewritten -- with a fixed clock both
     * instants would be identical and a rewriting implementation would pass.</p>
     */
    private static final LocalDateTime LATER_INSTANT = SEEDING_INSTANT.plusHours(1);

    /** The consumed position the seeding advance stores, above the sequence's first ordinals. */
    private static final long SEEDED_POSITION = 5L;

    /** The position a later advance moves the seeded row to, above the seeded one. */
    private static final long ADVANCED_POSITION = 10L;

    /** The account every posting case resolves to through the cross-reference. */
    private static final long ACCOUNT_ID = 10000000077L;

    /** The customer the cross-reference row names, fabricated and identifying nobody. */
    private static final long CUSTOMER_ID = 900000077L;

    /**
     * The card number the posting cases resolve the account through.
     *
     * <p>Assumptions: sixteen digits carrying the unassigned {@code 9900} major-industry prefix and a
     * deliberately failing Luhn check, which is the convention this repository holds every fabricated
     * card number to. {@code AccountRepositoryIT} asserts both properties mechanically; this class
     * reuses the value rather than inventing a second one so one grep finds every use.</p>
     */
    private static final String CARD_NUM = "9900000000000078";

    /** The last four digits, the only part of the card number any message here may render. */
    private static final String CARD_LAST_FOUR = "0078";

    /** The transaction type the posting cases accumulate under. */
    private static final String TYPE_CD = "01";

    /** The transaction category the posting cases accumulate under. */
    private static final String CATEGORY_CD = "0001";

    /** The amount the posted record carries, from the committed happy-path parity vector. */
    private static final BigDecimal POSTED_AMOUNT = new BigDecimal("504.77");

    /** The balance the seeded account opens at, from the same parity vector. */
    private static final BigDecimal OPENING_BALANCE = new BigDecimal("193.00");

    /** The credit limit the seeded account carries, high enough that no case is rejected for it. */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("2065.00");

    /** The cash credit limit the seeded account carries. */
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("500.00");

    /** The account's expiration date, ahead of every posted record's origin stamp. */
    private static final LocalDate EXPIRATION_DATE = LocalDate.of(2024, 12, 13);

    /** The account's open date. */
    private static final LocalDate OPEN_DATE = LocalDate.of(2014, 11, 20);

    /** The account's reissue date. */
    private static final LocalDate REISSUE_DATE = LocalDate.of(2024, 5, 20);

    /** The account's zip code, at the padded width the copybook declares. */
    private static final String ADDR_ZIP = "12345     ";

    /** The disclosure group the account belongs to, at the padded width the copybook declares. */
    private static final String GROUP_ID = "DEFAULT   ";

    /** The account's status, active so no case is rejected for it. */
    private static final String ACTIVE_STATUS = "Y";

    /** The origin stamp every seeded feed record carries, ahead of no expiration date. */
    private static final LocalDateTime ORIGINATED_AT = LocalDateTime.of(2022, 6, 10, 19, 27, 53);

    /** The business date the posting unit is driven with. */
    private static final BusinessDate BUSINESS_DATE = new BusinessDate("2022-07-18");

    /** The run identifier the posting unit is driven with. */
    private static final String POSTING_RUN_ID = "watermark-it-posting";

    /** The bound every latch wait and every task result is taken under. */
    private static final long WAIT_SECONDS = 20L;

    /**
     * How long the holding transaction stays open after it has flushed, in milliseconds.
     *
     * <p>Trade-offs: a sleep is used to hold a transaction open, and it is the one timing device in this
     * class. What it buys is that the second transaction reaches its statement while the first is still
     * uncommitted, which is the whole arrangement; what it costs is a bounded delay in two cases. It is
     * NOT what the assertions depend on -- each of the two concurrency cases is written so that a second
     * transaction which failed to block would produce a DIFFERENT, asserted-against outcome rather than
     * a slower identical one, so a sleep that expired early would fail the case rather than weaken
     * it.</p>
     */
    private static final long HOLD_MILLIS = 800L;

    /**
     * The container every assertion in this class runs against, started once for the class.
     *
     * <p>Assumptions: the type is imported from {@code org.testcontainers.postgresql} and not from
     * {@code org.testcontainers.containers}, matching every sibling here: Testcontainers 2.0.5 ships
     * both, the replacement is not generic, and so the declaration carries no type argument.</p>
     */
    // WHY : Alternatives Considered: an in-memory engine, which would start in milliseconds rather than
    //       the twenty seconds a container costs. Rejected because every property this class exists for
    //       IS an engine behaviour: a primary key refusing a second consumer at the moment two
    //       transactions insert it, SELECT ... FOR UPDATE blocking one transaction until another
    //       commits, four CHECK predicates firing at their statements, and a rollback discarding an
    //       in-flight advance. A substitute implements those differently or not at all, so each
    //       assertion would either not compile as written or pass while saying nothing about the engine
    //       the nightly chain runs against.
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /** The consumed-position rule under test, injected as the production service. */
    @Autowired
    private DailyFeedWatermarkService watermarks;

    /** The production per-record unit, driven by the two co-commit cases. */
    @Autowired
    private PostingRecordUnitOfWork perRecord;

    /** The account master, used to seed the row the posting cases resolve to. */
    @Autowired
    private AccountRepository accounts;

    /** The unposted feed, read through the same continuation finder the posting step walks with. */
    @Autowired
    private DailyTransactionRepository feed;

    /** The persistence context, used to persist the cross-reference seed and to force flushes. */
    @Autowired
    private EntityManager entityManager;

    /** The transaction boundary every advance below is issued inside. */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** The pool, so a case can read the table from a session that is not the one writing it. */
    @Autowired
    private DataSource dataSource;

    /** The clock the service stamps advances from, moved by a case that needs two instants. */
    @Autowired
    private SettableClock clock;

    /** A plain JDBC handle, for the catalog reads and the post-commit reads no entity can express. */
    private JdbcTemplate jdbc;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * <p>Assumptions: the Flyway credential pair is registered beside the datasource pair because
     * {@code application.yml} binds {@code spring.flyway.user} and {@code spring.flyway.password} to
     * placeholders with no fallback, and Boot consults those keys precisely when no connection-details
     * bean supplies them, which is this module's case. No connection literal appears here: a container
     * assigns its host port as it starts, so a literal authored beforehand would either address nothing
     * or, worse because it would pass, address whatever database happened to be listening.</p>
     *
     * @param registry the registry the framework supplies for late-bound properties; must not be
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
     * Empties every table a case writes, and returns the clock to the seeding instant.
     *
     * <p>Assumptions: the watermark table is emptied FIRST and that is load-bearing rather than tidy.
     * The rule advances a position monotonically, so a row surviving from an earlier case would make the
     * next case's advance a silent no-op and its assertion report the earlier case's ordinal -- which is
     * a green case that establishes nothing.</p>
     *
     * <p>Assumptions: the feed's identity sequence is NOT reset by these deletes, so ordinals rise
     * across the class run and no case may compare a stored position against a literal. Every posting
     * case below compares against the ordinal its own seeding statement returned.</p>
     *
     * <p>This setup method takes no parameter and returns no value.</p>
     */
    @BeforeEach
    void resetAndSeed() {
        this.jdbc = new JdbcTemplate(this.dataSource);
        this.clock.moveTo(SEEDING_INSTANT);
        this.transactionTemplate.executeWithoutResult(status -> {
            this.jdbc.update("DELETE FROM batch.daily_feed_watermark");
            this.jdbc.update("DELETE FROM ledger.transactions");
            this.jdbc.update("DELETE FROM ledger.transaction_category_balances");
            this.jdbc.update("DELETE FROM ledger.transaction_rejects");
            this.jdbc.update("DELETE FROM ledger.daily_transactions");
            this.jdbc.update("DELETE FROM account.accounts");
            this.jdbc.update("DELETE FROM account.card_xref");
        });
    }

    /**
     * Confirms the migration creates the table with its primary key and all four of its checks.
     *
     * <p>Purpose: the mapping is validated against the deployed schema at context start, so a missing
     * COLUMN fails every case here loudly. A missing CONSTRAINT fails nothing, because the provider's
     * validation compares columns and types and not predicates -- so the constraints that protect the
     * table are exactly the part of its DDL that can go missing silently. This case reads them from the
     * catalog by NAME.</p>
     *
     * <p>Assumptions: the five names are enumerated rather than counted.
     * {@code db/migration/V2__batch_feed_watermark.sql} declares {@code pk_daily_feed_watermark} at
     * L212, {@code ck_daily_feed_watermark_ordinal} at L219, {@code ck_daily_feed_watermark_feed_name}
     * at L232, {@code ck_daily_feed_watermark_run_id} at L233 and
     * {@code ck_daily_feed_watermark_business_date} at L242, and a count would go stale silently the
     * moment a sixth was added while an enumeration leaves the omission visible.</p>
     *
     * <p>Assumptions: {@code NOT NULL} is asserted separately from the checks, because the entity
     * declares all five columns non-nullable and the reason it can -- that absence of a ROW means the
     * feed has never been consumed, so no column ever has to be invented -- is a schema fact.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the migration creates the watermark table with its key and its four checks")
    void theMigrationCreatesTheWatermarkTableWithItsKeyAndItsFourChecks() {
        List<String> constraints = this.jdbc.queryForList(
                "SELECT conname FROM pg_constraint"
                        + " WHERE conrelid = 'batch.daily_feed_watermark'::regclass"
                        + " AND contype IN ('p', 'c') ORDER BY conname",
                String.class);

        assertThat(constraints)
                .as("the migration declares one primary key and four checks, named")
                .containsExactly(
                        "ck_daily_feed_watermark_business_date",
                        "ck_daily_feed_watermark_feed_name",
                        "ck_daily_feed_watermark_ordinal",
                        "ck_daily_feed_watermark_run_id",
                        "pk_daily_feed_watermark");

        List<String> keyColumns = this.jdbc.queryForList(
                "SELECT a.attname FROM pg_constraint c"
                        + " JOIN pg_attribute a ON a.attrelid = c.conrelid"
                        + " AND a.attnum = ANY (c.conkey)"
                        + " WHERE c.conname = 'pk_daily_feed_watermark'",
                String.class);

        assertThat(keyColumns)
                .as("the key is the feed name ALONE, which is what makes the single-result read"
                        + " contract on findByFeedName earned rather than assumed")
                .containsExactly("feed_name");

        List<Map<String, Object>> columns = this.jdbc.queryForList(
                "SELECT column_name, is_nullable FROM information_schema.columns"
                        + " WHERE table_schema = 'batch' AND table_name = 'daily_feed_watermark'"
                        + " ORDER BY column_name");

        assertThat(columns)
                .as("all five columns are declared, and every one of them is NOT NULL")
                .containsExactly(
                        Map.of("column_name", "business_date", "is_nullable", "NO"),
                        Map.of("column_name", "feed_name", "is_nullable", "NO"),
                        Map.of("column_name", "last_ingest_seq", "is_nullable", "NO"),
                        Map.of("column_name", "run_id", "is_nullable", "NO"),
                        Map.of("column_name", "updated_at", "is_nullable", "NO"));
    }

    /**
     * Confirms each declared check refuses the row it exists to refuse.
     *
     * <p>Purpose: a named constraint that does not fire is worse than an absent one, because the
     * catalog assertion above would still pass. Each of the four is provoked by a statement that
     * violates exactly it, and the refusal is asserted to name that constraint -- so a predicate
     * written against the wrong column would fail here rather than pass twice.</p>
     *
     * <p>Assumptions: raw SQL is used rather than the entity, and that is the point of the case. The
     * entity's constructor refuses a negative ordinal, a blank name and a mis-sized date token in
     * memory, so every one of these rows is unreachable through the mapping -- which is exactly why the
     * database must refuse them too. The mapping protects this application; the constraint protects the
     * table from every other writer, including a migration, an operator and the ETL package.</p>
     *
     * <p>Assumptions: the blank cases use spaces rather than the empty string, because the predicates
     * are {@code btrim(...) <> ''} and an empty string would be refused by a simpler reading. A run of
     * spaces is the value a fixed-width mainframe extract actually produces, and it is what the trim
     * exists for.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("each declared check refuses the row it exists to refuse")
    void eachDeclaredCheckRefusesTheRowItExistsToRefuse() {
        assertThatThrownBy(() -> insertDirectly(FEED, -1L, SEEDING_RUN_ID, SEEDING_BUSINESS_DATE))
                .as("a negative ordinal is refused, an ordinal being a count of rows consumed")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_daily_feed_watermark_ordinal");

        assertThatThrownBy(() ->
                insertDirectly("   ", SEEDED_POSITION, SEEDING_RUN_ID, SEEDING_BUSINESS_DATE))
                .as("a blank feed name is refused, a row that governs no named feed governing nothing")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_daily_feed_watermark_feed_name");

        assertThatThrownBy(() ->
                insertDirectly(FEED, SEEDED_POSITION, "     ", SEEDING_BUSINESS_DATE))
                .as("a blank run identifier is refused, the attribution being why the column exists")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_daily_feed_watermark_run_id");

        assertThatThrownBy(() ->
                insertDirectly(FEED, SEEDED_POSITION, SEEDING_RUN_ID, "2022-07-1"))
                .as("a business-date token of nine characters is refused, the token being fixed at ten")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_daily_feed_watermark_business_date");

        assertThat(storedRows())
                .as("and no refused row was left behind, each statement having been rolled back whole")
                .isZero();
    }

    /**
     * Confirms an absent row reads as nothing consumed, through both of the rule's reads.
     *
     * <p>Purpose: this is the state the very first pass runs in, and the state every case in this class
     * begins in. It is asserted through both reads because the rule declares two -- a locked one for a
     * consumer that is about to advance the position, and an unlocked one for a reader that only reports
     * it -- and a reader that treated absence as an error would fail the first night rather than posting
     * it.</p>
     *
     * <p>Assumptions: the locked read is issued inside a transaction and the unlocked read outside one,
     * which is the arrangement each is declared for rather than a convenience. The next case establishes
     * that the locked read REFUSES the call when there is no transaction to lock within.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an absent row reads as nothing consumed through both reads")
    void anAbsentRowReadsAsNothingConsumedThroughBothReads() {
        long lockedRead = this.transactionTemplate.execute(status ->
                this.watermarks.consumedThroughForConsumer(FEED));

        assertThat(lockedRead)
                .as("the consumer's locked read reports nothing consumed rather than failing")
                .isEqualTo(DailyFeedWatermarkService.NOTHING_CONSUMED);
        assertThat(this.watermarks.consumedThroughForReader(FEED))
                .as("and so does the reporting read, which needs no transaction of its own")
                .isEqualTo(DailyFeedWatermarkService.NOTHING_CONSUMED);
        assertThat(storedRows())
                .as("and neither read created the row it did not find")
                .isZero();
    }

    /**
     * Confirms the locked read is refused when the caller holds no transaction.
     *
     * <p>Purpose: the lock is the protection against two overlapping passes, and a lock released before
     * its holder uses the value protects nothing. {@code findByFeedName} therefore declares
     * {@code Propagation.MANDATORY}, and this case is the proof that the declaration has teeth: without
     * it the framework would START a transaction for the call and commit it as the call returned,
     * releasing the row lock while the caller still believed it held one, and every later case here
     * would pass while the protection was silently absent.</p>
     *
     * <p>Assumptions: the unlocked read is exercised in the same case as the CONTRAST, because the
     * refusal only means something if the alternative succeeds. One member refuses outside a
     * transaction and the other does not, which is the distinction between the two reads stated as
     * behaviour rather than as a comment.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the locked read is refused when the caller holds no transaction")
    void theLockedReadIsRefusedOutsideACallerTransaction() {
        assertThatThrownBy(() -> this.watermarks.consumedThroughForConsumer(FEED))
                .as("MANDATORY propagation names the mistake at the call site instead of opening a"
                        + " boundary that would release the lock before the caller used it")
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(this.watermarks.consumedThroughForReader(FEED))
                .as("the reporting read is unaffected, taking no lock and so needing no caller"
                        + " transaction to hold one within")
                .isEqualTo(DailyFeedWatermarkService.NOTHING_CONSUMED);
    }

    /**
     * Confirms the first advance inserts the row carrying its full attribution.
     *
     * <p>Purpose: the create arm of the rule, read from the committed table rather than from the
     * persistence context. All five columns are asserted, not only the ordinal: the run identifier and
     * the business-date token are what let an operator reading a recovered night say WHICH execution
     * left the feed where it is, and a create arm that stored the position but not the attribution would
     * satisfy every functional assertion while making that impossible.</p>
     *
     * <p>Assumptions: the read-back is a plain SQL select issued after the commit, so no first-level
     * cache can answer it. Reading through the repository inside the same transaction would return the
     * instance the rule had just modified in memory, which is true of an implementation that never
     * reached the database at all.</p>
     *
     * <p>Assumptions: the stamp is asserted against the settable clock's instant rather than against
     * the wall clock, so the case states a real value instead of a range. The rule mints it from its
     * injected clock, which is why one is injected.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the first advance inserts the row carrying its full attribution")
    void theFirstAdvanceInsertsTheRowWithItsFullAttribution() {
        boolean advanced = advanceInItsOwnTransaction(
                SEEDED_POSITION, SEEDING_RUN_ID, SEEDING_BUSINESS_DATE);

        assertThat(advanced)
                .as("the rule reports that it moved the position, an absent row being nothing consumed")
                .isTrue();

        Map<String, Object> stored = storedRow();

        assertThat(stored.get("last_ingest_seq"))
                .as("the consumed position is stored as the exclusive bound the next pass reads past")
                .isEqualTo(SEEDED_POSITION);
        assertThat(stored.get("run_id"))
                .as("the advancing execution is recorded, so a recovered night can be attributed")
                .isEqualTo(SEEDING_RUN_ID);
        assertThat(stored.get("business_date"))
                .as("the injected business-date token is recorded verbatim, not parsed and reformatted")
                .isEqualTo(SEEDING_BUSINESS_DATE);
        assertThat(stored.get("updated_at"))
                .as("the stamp is the one the injected clock reported, not the wall clock's")
                .isEqualTo(Timestamp.valueOf(SEEDING_INSTANT));
        assertThat(storedRows())
                .as("and exactly one row governs the feed, the key admitting no second")
                .isEqualTo(1L);
    }

    /**
     * Confirms two concurrent first advances leave exactly one committed consumer.
     *
     * <p>Purpose: this is the one gap the row lock cannot close, and the repository's own contract says
     * so: a lock on a row that does not exist protects nothing, so two first passes both find nothing
     * and both insert. The property that saves the data is therefore not the lock but the PRIMARY KEY,
     * and this case is where that claim is tested rather than asserted. The outcome the contract
     * predicts -- one completed pass and one wholly failed one, never two consumers of the same rows --
     * is what is measured.</p>
     *
     * <p>Assumptions: the arrangement makes the race real rather than hoping for it. The first
     * transaction advances, FLUSHES so its insert is really at the server, signals, and only then holds
     * for a bounded interval before committing; the second starts on that signal, so its own locked read
     * runs while the first insert is still uncommitted and therefore invisible -- it finds nothing, as
     * the contract describes -- and its insert then blocks on the duplicate key until the first commits.
     * Without the flush the first insert would still be in a queue and the second would insert cleanly,
     * which is a different situation with the same shape.</p>
     *
     * <p>Assumptions: the refusal is asserted to name {@code pk_daily_feed_watermark} rather than merely
     * to be an integrity violation, because the whole point is WHICH constraint saved the data. A
     * failure naming a check constraint would mean the arrangement had gone wrong.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws InterruptedException if the coordinating waits are interrupted, which fails the case
     * @throws ExecutionException if a task fails for a reason the case does not provoke
     * @throws TimeoutException if a task does not finish within the class's bound, which fails the case
     *     rather than hanging the build
     */
    @Test
    @DisplayName("two concurrent first advances leave exactly one committed consumer")
    void twoConcurrentFirstAdvancesLeaveExactlyOneConsumer()
            throws InterruptedException, ExecutionException, TimeoutException {

        CountDownLatch firstHasInserted = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = pool.submit(() ->
                    this.transactionTemplate.execute(status -> {
                        boolean moved = this.watermarks.recordConsumedThrough(
                                FEED, SEEDED_POSITION, SEEDING_RUN_ID, SEEDING_BUSINESS_DATE);
                        this.entityManager.flush();
                        firstHasInserted.countDown();
                        sleepForTheHold();
                        return moved;
                    }));

            Future<Boolean> second = pool.submit(() -> {
                awaitOrFail(firstHasInserted);
                return this.transactionTemplate.execute(status -> this.watermarks
                        .recordConsumedThrough(FEED, SEEDED_POSITION, COMPETING_RUN_ID,
                                COMPETING_BUSINESS_DATE));
            });

            assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the transaction that inserted first commits and reports the advance")
                    .isTrue();
            assertThatThrownBy(() -> second.get(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("and the second is refused by the KEY rather than producing a second consumer,"
                            + " the constraint being named so a failure at a check would not pass here")
                    .hasCauseInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("pk_daily_feed_watermark");
        }

        assertThat(storedRows())
                .as("exactly one row governs the feed after the race")
                .isEqualTo(1L);
        assertThat(storedRow().get("run_id"))
                .as("and it is attributed to the pass that committed, the other having rolled back"
                        + " whole -- including whatever it had posted")
                .isEqualTo(SEEDING_RUN_ID);
    }

    /**
     * Confirms the pessimistic lock serialises two transactions advancing the same row.
     *
     * <p>Purpose: this is what the lock is FOR, and it is unobservable without two connections. Two
     * passes that overlap after the first night must not both read the same position and both post the
     * same rows; the lock makes the second wait and then see what the first left.</p>
     *
     * <p>Assumptions: the discriminating assertion is the second transaction's RESULT and not a
     * measured duration, which is what makes this case a proof rather than a timing heuristic. The
     * second transaction asks to advance to exactly the position the first is advancing to. If it were
     * serialised it reads {@code 10} and refuses its own request as a no-op, returning {@code false}. If
     * it were NOT serialised it would read the pre-existing {@code 5} -- the first transaction's update
     * being uncommitted and therefore invisible -- find {@code 10} greater, and return {@code true},
     * having credited itself with consuming rows the first transaction was posting. So {@code false}
     * cannot be produced by an unlocked read, and no clock is consulted to establish it.</p>
     *
     * <p>Assumptions: the stored attribution is asserted as well, because it says which pass the
     * surviving position belongs to. A last-writer-wins implementation would leave the same ordinal
     * attributed to the wrong run, and an operator recovering the night would be pointed at an execution
     * that consumed nothing.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws InterruptedException if the coordinating waits are interrupted, which fails the case
     * @throws ExecutionException if a task fails for a reason the case does not provoke
     * @throws TimeoutException if a task does not finish within the class's bound, which fails the case
     *     rather than hanging the build
     */
    @Test
    @DisplayName("the pessimistic lock serialises two transactions advancing the same row")
    void thePessimisticLockSerialisesTwoAdvancingTransactions()
            throws InterruptedException, ExecutionException, TimeoutException {

        advanceInItsOwnTransaction(SEEDED_POSITION, SEEDING_RUN_ID, SEEDING_BUSINESS_DATE);
        CountDownLatch firstHoldsTheLock = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = pool.submit(() ->
                    this.transactionTemplate.execute(status -> {
                        boolean moved = this.watermarks.recordConsumedThrough(
                                FEED, ADVANCED_POSITION, SEEDING_RUN_ID, SEEDING_BUSINESS_DATE);
                        this.entityManager.flush();
                        firstHoldsTheLock.countDown();
                        sleepForTheHold();
                        return moved;
                    }));

            Future<Boolean> second = pool.submit(() -> {
                awaitOrFail(firstHoldsTheLock);
                return this.transactionTemplate.execute(status -> this.watermarks
                        .recordConsumedThrough(FEED, ADVANCED_POSITION, COMPETING_RUN_ID,
                                COMPETING_BUSINESS_DATE));
            });

            assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the transaction holding the lock advances the position")
                    .isTrue();
            assertThat(second.get(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("and the second read the position the first had COMMITTED rather than the one"
                            + " it superseded, so it refused its own request as a no-op -- which is a"
                            + " result an unlocked read cannot produce")
                    .isFalse();
        }

        Map<String, Object> stored = storedRow();

        assertThat(stored.get("last_ingest_seq"))
                .as("the surviving position is the one the serialised advance left")
                .isEqualTo(ADVANCED_POSITION);
        assertThat(stored.get("run_id"))
                .as("attributed to the pass that actually consumed the rows, not to the one that was"
                        + " made to wait and then found nothing to do")
                .isEqualTo(SEEDING_RUN_ID);
    }

    /**
     * Confirms an advance at or below the stored position changes nothing at all.
     *
     * <p>Purpose: monotonicity is what makes a retried step safe. A step re-run after a partial night
     * asks to consume through a position the table may already be past, and the rule must treat that as
     * a no-op rather than winding the feed BACKWARDS -- a rewind re-presents rows that were already
     * posted, and under the additive posting model every one of their amounts lands on an account
     * balance a second time.</p>
     *
     * <p>Assumptions: the equal and the lower request are both exercised, because they fail differently
     * if the comparison is written with the wrong operator. A strict {@code <} would accept the EQUAL
     * request and rewrite the row with a later stamp and a different run, which is why the stamp and the
     * attribution are asserted here and not only the ordinal.</p>
     *
     * <p>Assumptions: the clock is MOVED between the seeding advance and the refused one, so the stored
     * stamp discriminates. With a fixed clock both instants would be identical and an implementation
     * that rewrote the row on an equal request would pass this case unchanged.</p>
     *
     * @param requested the position the refused advance asks for, being either exactly the stored one or
     *     one below it
     */
    @ParameterizedTest(name = "an advance to {0} against a stored 5 changes nothing")
    @ValueSource(longs = {5L, 4L})
    @DisplayName("an advance at or below the stored position changes nothing")
    void anAdvanceAtOrBelowTheStoredPositionChangesNothing(long requested) {
        advanceInItsOwnTransaction(SEEDED_POSITION, SEEDING_RUN_ID, SEEDING_BUSINESS_DATE);
        this.clock.moveTo(LATER_INSTANT);

        boolean advanced = advanceInItsOwnTransaction(
                requested, COMPETING_RUN_ID, COMPETING_BUSINESS_DATE);

        assertThat(advanced)
                .as("the rule reports that it did NOT move the position, so a caller can tell a no-op"
                        + " from an advance")
                .isFalse();

        Map<String, Object> stored = storedRow();

        assertThat(stored.get("last_ingest_seq"))
                .as("the stored position is untouched, never wound back to %s", requested)
                .isEqualTo(SEEDED_POSITION);
        assertThat(stored.get("run_id"))
                .as("and so is the attribution, which a rewriting implementation would have replaced")
                .isEqualTo(SEEDING_RUN_ID);
        assertThat(stored.get("business_date"))
                .as("and the business date, for the same reason")
                .isEqualTo(SEEDING_BUSINESS_DATE);
        assertThat(stored.get("updated_at"))
                .as("and the stamp still reads the seeding instant although the clock has moved on,"
                        + " which is the assertion that a no-op really wrote nothing")
                .isEqualTo(Timestamp.valueOf(SEEDING_INSTANT));
    }

    /**
     * Confirms a rolled-back advance leaves the previously stored position durable.
     *
     * <p>Purpose: the position exists to say what has been POSTED, so it must share the fate of the
     * postings it accounts for. If an advance survived the rollback of the transaction that made it, the
     * next pass would resume past rows that were never posted -- losing them silently, which is the
     * failure that leaves no trace anywhere.</p>
     *
     * <p>Assumptions: the advance is flushed before the rollback is requested, so the update really
     * reached the server and was really discarded by it. An unflushed change would be discarded by the
     * persistence context alone and the case would establish nothing about the engine.</p>
     *
     * <p>Assumptions: the clock is moved first, so the stamp of the surviving row shows which advance it
     * came from. A row carrying the later stamp would be the rolled-back one having partly survived.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a rolled-back advance leaves the previously stored position durable")
    void aRolledBackAdvanceLeavesThePreviousPositionDurable() {
        advanceInItsOwnTransaction(SEEDED_POSITION, SEEDING_RUN_ID, SEEDING_BUSINESS_DATE);
        this.clock.moveTo(LATER_INSTANT);

        this.transactionTemplate.executeWithoutResult(status -> {
            boolean advanced = this.watermarks.recordConsumedThrough(
                    FEED, ADVANCED_POSITION, COMPETING_RUN_ID, COMPETING_BUSINESS_DATE);
            assertThat(advanced)
                    .as("the advance is made and reported inside the transaction, so what follows is a"
                            + " rollback of real work rather than of nothing")
                    .isTrue();
            this.entityManager.flush();
            status.setRollbackOnly();
        });

        Map<String, Object> stored = storedRow();

        assertThat(stored.get("last_ingest_seq"))
                .as("the committed position survives and the rolled-back one is gone")
                .isEqualTo(SEEDED_POSITION);
        assertThat(stored.get("run_id"))
                .as("with its own attribution, so the surviving row is the earlier one entire")
                .isEqualTo(SEEDING_RUN_ID);
        assertThat(stored.get("updated_at"))
                .as("and its own stamp, although the clock had moved before the discarded advance")
                .isEqualTo(Timestamp.valueOf(SEEDING_INSTANT));
    }

    /**
     * Confirms a rolled-back first advance leaves no row at all.
     *
     * <p>Purpose: the create arm's half of the rollback property, which is a different statement from
     * the update arm's. An insert that survived its transaction's rollback would leave the feed marked
     * as consumed through a position nothing had posted, and because the first pass is the one with no
     * previous row to fall back on, the whole night would be lost with no record that it had been
     * attempted.</p>
     *
     * <p>Assumptions: absence is asserted twice -- as a row count and through the rule's own reporting
     * read -- because the second is what the next pass actually consults. A table with no row and a rule
     * that reported a position anyway would still resume in the wrong place.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a rolled-back first advance leaves no row at all")
    void aRolledBackFirstAdvanceLeavesNoRowAtAll() {
        this.transactionTemplate.executeWithoutResult(status -> {
            this.watermarks.recordConsumedThrough(
                    FEED, SEEDED_POSITION, SEEDING_RUN_ID, SEEDING_BUSINESS_DATE);
            this.entityManager.flush();
            status.setRollbackOnly();
        });

        assertThat(storedRows())
                .as("the inserted row went back with the transaction that inserted it")
                .isZero();
        assertThat(this.watermarks.consumedThroughForReader(FEED))
                .as("so the next pass resumes from the beginning, which is where the failed pass"
                        + " started")
                .isEqualTo(DailyFeedWatermarkService.NOTHING_CONSUMED);
    }

    /**
     * Confirms the checkpoint and the posting writes commit as one unit.
     *
     * <p>Purpose: this is the property the whole table exists for, and it is asserted over the
     * PRODUCTION per-record unit rather than over two calls this test made in sequence. The unit accepts
     * one feed record, writes the category balance, the account and the posted transaction, and advances
     * this position -- and if the advance were not in the same transaction as those writes, a crash
     * between them would either lose the postings while marking them consumed or keep them while
     * presenting the record again.</p>
     *
     * <p>Assumptions: the starting position is a COMMITTED advance rather than an absent row, which is
     * what distinguishes this case from the equivalent in {@code AccountRepositoryIT}. That one asserts
     * the checkpoint's insert; this one asserts its UPDATE from a real previous position, which is the
     * state every night after the first runs in.</p>
     *
     * <p>Assumptions: the previous position is the ordinal of a first seeded feed row, so it is
     * genuinely below the record under test without any arithmetic on a sequence this class does not
     * control. The feed's identity sequence is not reset between cases, so a literal previous position
     * would pass only on whichever case happened to run first.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the checkpoint and the posting writes commit as one unit")
    void theCheckpointAndThePostingWritesCommitAsOneUnit() {
        seedAccountAndCrossReference();
        long previouslyConsumed = seedFeedRecord("0000000000000066");
        advanceInItsOwnTransaction(previouslyConsumed, SEEDING_RUN_ID, SEEDING_BUSINESS_DATE);
        long ordinal = seedFeedRecord("0000000000000077");

        this.transactionTemplate.executeWithoutResult(status -> {
            Optional<byte[]> rejected =
                    this.perRecord.applyOneRecord(feedRecord(ordinal), POSTING_RUN_ID, BUSINESS_DATE);
            assertThat(rejected)
                    .as("the record was accepted, so what commits is the posting arm and not the"
                            + " reject arm -- card ending %s", CARD_LAST_FOUR)
                    .isEmpty();
        });

        Map<String, Object> stored = storedRow();

        assertThat(stored.get("last_ingest_seq"))
                .as("the position advanced to the ordinal of the record that was posted")
                .isEqualTo(ordinal);
        assertThat(stored.get("run_id"))
                .as("attributed to the posting run rather than to the pass that left the previous"
                        + " position")
                .isEqualTo(POSTING_RUN_ID);
        assertThat(stored.get("business_date"))
                .as("carrying the business date the posting run was driven with")
                .isEqualTo(BUSINESS_DATE.token());
        assertThat(postedRows())
                .as("and the posted transaction is committed too, which is what makes the advance"
                        + " truthful")
                .isEqualTo(1L);
    }

    /**
     * Confirms a refused posting leaves the stored position untouched.
     *
     * <p>Purpose: the other half of the same pairing, and the half that decides whether a failure loses
     * data. A record whose posting is refused has NOT been consumed, so the position must not move; if
     * it moved, the next pass would resume past a record nothing ever posted and the amount would never
     * reach the account at all.</p>
     *
     * <p>Assumptions: the refusal is provoked at the posted write by a feed row carrying no transaction
     * identifier -- {@code ledger.daily_transactions.transaction_id} is nullable, the pre-posting feed
     * having no usable natural key, while {@code ledger.transactions.transaction_id} is that table's
     * {@code NOT NULL} primary key. So the row is one the feed admits and the posted master must refuse,
     * and the refusal arises inside the production unit rather than being thrown by this test.</p>
     *
     * <p>Assumptions: the previous position is asserted to be EXACTLY what it was, attribution and stamp
     * included, rather than merely "not the record's ordinal". A partial rewrite that kept the ordinal
     * and replaced the attribution would leave an operator reading the failed night pointed at the run
     * that failed to consume anything.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a refused posting leaves the stored position untouched")
    void aRefusedPostingLeavesTheStoredPositionUntouched() {
        seedAccountAndCrossReference();
        long previouslyConsumed = seedFeedRecord("0000000000000066");
        advanceInItsOwnTransaction(previouslyConsumed, SEEDING_RUN_ID, SEEDING_BUSINESS_DATE);
        this.clock.moveTo(LATER_INSTANT);
        long ordinal = seedFeedRecord(null);

        assertThatThrownBy(() -> this.transactionTemplate.executeWithoutResult(status ->
                this.perRecord.applyOneRecord(feedRecord(ordinal), POSTING_RUN_ID, BUSINESS_DATE)))
                .as("the posted master refuses a row with no identifier to be keyed by")
                .isInstanceOf(DataAccessException.class);

        Map<String, Object> stored = storedRow();

        assertThat(stored.get("last_ingest_seq"))
                .as("the position stands where the last successful pass left it")
                .isEqualTo(previouslyConsumed);
        assertThat(stored.get("run_id"))
                .as("with that pass's attribution and not the failed run's")
                .isEqualTo(SEEDING_RUN_ID);
        assertThat(stored.get("updated_at"))
                .as("and that pass's stamp, although the clock moved before the refused advance")
                .isEqualTo(Timestamp.valueOf(SEEDING_INSTANT));
        assertThat(postedRows())
                .as("and nothing was posted, so the unchanged position is the honest one")
                .isZero();
    }

    /**
     * Advances the feed's position inside a transaction of its own, and commits it.
     *
     * @param position the consumed position to record; must not be negative
     * @param runId the execution the advance is attributed to; must not be {@code null}
     * @param businessDate the ten-character business-date token; must not be {@code null}
     * @return {@code true} when the rule moved the position, {@code false} when it treated the request
     *     as a no-op
     */
    private boolean advanceInItsOwnTransaction(long position, String runId, String businessDate) {
        return Boolean.TRUE.equals(this.transactionTemplate.execute(status ->
                this.watermarks.recordConsumedThrough(FEED, position, runId, businessDate)));
    }

    /**
     * Inserts one watermark row by statement, bypassing the mapping's own validation.
     *
     * <p>Assumptions: this is how the check-constraint case reaches rows the entity cannot construct.
     * It is deliberately the only place in this class that writes this table without going through
     * production code, and it exists to prove the database refuses what the mapping also refuses.</p>
     *
     * @param feedName the feed name to write, which one case makes blank on purpose
     * @param position the ordinal to write, which one case makes negative on purpose
     * @param runId the run identifier to write, which one case makes blank on purpose
     * @param businessDate the token to write, which one case makes nine characters on purpose
     * @throws DataIntegrityViolationException if the database refuses the row, which every use of this
     *     method provokes deliberately
     */
    private void insertDirectly(String feedName, long position, String runId, String businessDate) {
        this.transactionTemplate.executeWithoutResult(status -> this.jdbc.update(
                "INSERT INTO batch.daily_feed_watermark"
                        + " (feed_name, last_ingest_seq, run_id, business_date, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                feedName, position, runId, businessDate,
                Timestamp.valueOf(SEEDING_INSTANT)));
    }

    /**
     * Reads the one watermark row for the daily feed, as committed.
     *
     * <p>Assumptions: a plain select rather than a repository read, so no persistence context can
     * answer it with an instance a case has just modified. Every column is returned because the
     * attribution columns are asserted as often as the ordinal.</p>
     *
     * @return the row's five columns keyed by column name, never {@code null}
     * @throws IllegalStateException if no row is stored, the arrangement then being broken rather than
     *     the case having a result
     */
    private Map<String, Object> storedRow() {
        List<Map<String, Object>> rows = this.jdbc.queryForList(
                "SELECT feed_name, last_ingest_seq, run_id, business_date, updated_at"
                        + " FROM batch.daily_feed_watermark WHERE feed_name = ?",
                FEED);

        if (rows.size() != 1) {
            throw new IllegalStateException("expected exactly one watermark row for " + FEED
                    + " but found " + rows.size());
        }
        return rows.getFirst();
    }

    /**
     * Counts the watermark rows stored for the daily feed.
     *
     * @return the number of rows, being zero or one
     */
    private long storedRows() {
        Long count = this.jdbc.queryForObject(
                "SELECT count(*) FROM batch.daily_feed_watermark WHERE feed_name = ?",
                Long.class, FEED);
        return count == null ? 0L : count;
    }

    /**
     * Counts the posted transaction rows, so a checkpoint can be paired with what it accounts for.
     *
     * @return the number of rows in the posted master
     */
    private long postedRows() {
        Long count = this.jdbc.queryForObject(
                "SELECT count(*) FROM ledger.transactions", Long.class);
        return count == null ? 0L : count;
    }

    /**
     * Commits the account and cross-reference rows the posting cases resolve through.
     *
     * <p>Assumptions: the cross-reference is written through the persistence context and not through
     * its repository, because that interface extends the bare marker base and declares no write method
     * at all -- this module only ever READS the cross-reference, and persisting the seed directly is
     * what lets the read path the production unit exercises stay read-only.</p>
     *
     * <p>Assumptions: the account's accumulators start at zero and its credit limit is well above the
     * posted amount, so validation ACCEPTS the record and the case reaches the writes it is about. The
     * validation rule projects its over-limit decision from the accumulators rather than from the
     * running balance, which is why the balance is the parity vector's and the accumulators are not.</p>
     */
    private void seedAccountAndCrossReference() {
        this.transactionTemplate.executeWithoutResult(status -> {
            this.accounts.save(new Account(ACCOUNT_ID, ACTIVE_STATUS,
                    OPENING_BALANCE, CREDIT_LIMIT, CASH_CREDIT_LIMIT, OPEN_DATE, EXPIRATION_DATE,
                    REISSUE_DATE, BigDecimal.ZERO, BigDecimal.ZERO, ADDR_ZIP, GROUP_ID));
            this.entityManager.persist(new CardXref(CARD_NUM, CUSTOMER_ID, ACCOUNT_ID));
            this.entityManager.flush();
            this.entityManager.clear();
        });
    }

    /**
     * Commits one unposted feed record and returns the ingestion ordinal the engine assigned it.
     *
     * <p>Assumptions: the row is inserted by STATEMENT and not through a repository, because the feed's
     * mapping is immutable and its interface declares no mutator -- this module only reads the feed, the
     * rows being produced upstream by the extract-and-load package.</p>
     *
     * <p>Assumptions: the ordinal is RETURNED by the statement rather than assumed, the column being
     * an identity whose sequence the per-case deletes do not reset.</p>
     *
     * @param transactionId the identifier the feed row carries, or {@code null} for the case that
     *     provokes the posted master's refusal
     * @return the ingestion ordinal the engine assigned
     * @throws IllegalStateException if the statement returns no ordinal, the arrangement then being
     *     broken rather than the case having a result
     */
    private long seedFeedRecord(String transactionId) {
        Long ordinal = this.transactionTemplate.execute(status -> this.jdbc.queryForObject(
                "INSERT INTO ledger.daily_transactions (transaction_id, type_cd, category_cd,"
                        + " source, description, amount, merchant_id, merchant_name, merchant_city,"
                        + " merchant_zip, card_num, orig_ts)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING ingest_seq",
                Long.class, transactionId, TYPE_CD, CATEGORY_CD, "POS       ",
                "Watermark unit of work fixture", POSTED_AMOUNT, 900000001L, "Fixture Merchant",
                "Fixture City", "12345     ", CARD_NUM, ORIGINATED_AT));

        if (ordinal == null) {
            throw new IllegalStateException(
                    "the seeded feed record returned no ingestion ordinal, so no unit of work can be"
                            + " driven over it");
        }
        return ordinal;
    }

    /**
     * Reads one seeded feed record back through the production continuation finder.
     *
     * <p>Assumptions: the read goes through the finder the posting step itself walks with, called with
     * the ordinal one below the wanted row and a limit of one, so a case cannot reach a row by a path
     * the production step does not have.</p>
     *
     * @param ingestSeq the ordinal the seeding statement returned
     * @return the feed record at that ordinal, as the production step would read it, never {@code null}
     * @throws IllegalStateException if the row is absent or another row answered, either of which is a
     *     broken arrangement rather than a result
     */
    private DailyTransaction feedRecord(long ingestSeq) {
        List<DailyTransaction> read =
                this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(ingestSeq - 1L, Limit.of(1));

        if (read.isEmpty() || !Long.valueOf(ingestSeq).equals(read.getFirst().getIngestSeq())) {
            throw new IllegalStateException("the seeded feed record at ordinal " + ingestSeq
                    + " did not answer the continuation finder, so the arrangement is broken");
        }
        return read.getFirst();
    }

    /**
     * Holds the calling transaction open for the bounded interval the two race cases need.
     *
     * @throws IllegalStateException if the wait is interrupted, which makes the arrangement unusable
     *     rather than the case failed
     */
    private static void sleepForTheHold() {
        try {
            Thread.sleep(HOLD_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the holding transaction was interrupted", interrupted);
        }
    }

    /**
     * Waits for a coordinating latch under the class's bound, failing rather than waiting forever.
     *
     * @param latch the latch the other task counts down; must not be {@code null}
     * @throws IllegalStateException if the latch does not open within the bound, or the wait is
     *     interrupted, either of which is a broken arrangement rather than a result
     */
    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "the first transaction did not signal within " + WAIT_SECONDS + " seconds");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the coordinating wait was interrupted", interrupted);
        }
    }

    /**
     * A clock whose instant the tests move, so two advances can carry two different stamps.
     *
     * <p>Refactoring Rationale: a FIXED clock would make every stamp identical, and three cases here
     * turn on a stamp that must NOT have changed. With one instant those cases would pass against an
     * implementation that rewrote the row on every request, which is precisely the defect they exist to
     * catch. A settable clock is the smallest thing that makes the assertion discriminating.</p>
     *
     * <p>Assumptions: the field is volatile because the two race cases read it from a background thread
     * while the test thread has written it, and the zone is fixed at UTC so a stamp is reproducible on
     * any build host.</p>
     */
    static final class SettableClock extends Clock {

        /** The instant this clock currently reports, written by the test thread. */
        private volatile Instant now = SEEDING_INSTANT.toInstant(ZoneOffset.UTC);

        /**
         * Moves the clock to a local date and time, interpreted at UTC.
         *
         * @param moment the local date and time this clock reports from now on; must not be
         *     {@code null}
         */
        void moveTo(LocalDateTime moment) {
            this.now = moment.toInstant(ZoneOffset.UTC);
        }

        /**
         * Reports the zone this clock interprets its instant in.
         *
         * @return {@link ZoneOffset#UTC} always, never {@code null}
         */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * Reports a clock over the same instant in another zone.
         *
         * <p>Assumptions: the same instance is returned whatever zone is requested, because nothing in
         * this module asks for another zone and a clock that silently ignored the request would be
         * worse than one that never receives it. The stamp is stored zone-less, matching
         * {@code batch.batch_run}, so UTC is the only interpretation in play.</p>
         *
         * @param zone the requested zone, which is accepted and not applied
         * @return this clock, never {@code null}
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /**
         * Reports the instant this clock currently stands at.
         *
         * @return the instant last set, never {@code null}
         */
        @Override
        public Instant instant() {
            return this.now;
        }
    }

    /**
     * The minimal Spring Boot configuration this class runs against.
     *
     * <p>Assumptions: no component scan is declared, and that omission is deliberate. This module's own
     * application class scans the bounded context and so registers eight job definitions, a queue
     * client, an object-store client and a parameter-store client -- none of which a watermark
     * assertion needs and each of which is a further way for one to fail for an unrelated reason.
     * Naming the two persistence packages leaves auto-configuration to build the pool from the
     * properties the container registered, and the four beans below are the production collaborators
     * the cases drive.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.batch.domain")
    @EnableJpaRepositories("com.carddemo.batch.repository")
    static class WatermarkPersistenceTestApplication {

        /**
         * Supplies the one clock every stamped write in this class is minted from.
         *
         * <p>Assumptions: exactly ONE clock bean is declared, and its declared type is the concrete
         * settable one. Publishing the same instance twice -- once as {@code SettableClock} for the
         * test to move and once as {@code Clock} for the services -- was tried and is wrong: two
         * beans then satisfy an injection point of type {@code Clock} and the context fails as
         * ambiguous. One bean of the subtype satisfies both injection points, so the instance the
         * cases move is provably the instance the services stamp from.</p>
         *
         * @return the clock the cases move and the services stamp from, never {@code null}
         */
        @Bean
        SettableClock clock() {
            return new SettableClock();
        }

        /**
         * Supplies the production consumed-position rule.
         *
         * @param watermarks the repository the rule reads and writes through; must not be {@code null}
         * @param clock the clock the rule stamps advances from; must not be {@code null}
         * @return the production service, never {@code null}
         */
        @Bean
        DailyFeedWatermarkService dailyFeedWatermarkService(
                DailyFeedWatermarkRepository watermarks, Clock clock) {
            return new DailyFeedWatermarkService(watermarks, clock);
        }

        /**
         * Supplies the production posting reject chain.
         *
         * @param crossReferences the cross-reference the chain resolves a card through; must not be
         *     {@code null}
         * @param accounts the account master the chain reads; must not be {@code null}
         * @return the production service, never {@code null}
         */
        @Bean
        PostingValidationService postingValidationService(
                CardXrefRepository crossReferences, AccountRepository accounts) {
            return new PostingValidationService(crossReferences, accounts);
        }

        /**
         * Supplies the production category-balance accumulation rule.
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
         * Supplies the production per-record unit of work the co-commit cases drive.
         *
         * <p>Assumptions: it is constructed here from production collaborators rather than mocked,
         * because the two cases that use it exist to establish that the production code path pairs a
         * checkpoint with the writes it accounts for. A double would pair whatever the test told it
         * to.</p>
         *
         * @param accounts the account master the unit updates; must not be {@code null}
         * @param ledger the posted master the unit inserts into; must not be {@code null}
         * @param rejects the reject rows the unit writes on the other arm; must not be {@code null}
         * @param validation the reject chain the unit consults first; must not be {@code null}
         * @param balances the accumulation rule the unit calls first on the accepted arm; must not be
         *     {@code null}
         * @param watermark the consumed-position rule the unit checkpoints through; must not be
         *     {@code null}
         * @param clock the clock the unit stamps a posted row's processing instant from; must not be
         *     {@code null}
         * @return the production component, never {@code null}
         */
        @Bean
        PostingRecordUnitOfWork postingRecordUnitOfWork(AccountRepository accounts,
                TransactionRepository ledger, TransactionRejectRepository rejects,
                PostingValidationService validation, CategoryBalanceService balances,
                DailyFeedWatermarkService watermark, Clock clock) {

            return new PostingRecordUnitOfWork(accounts, ledger, rejects, validation, balances,
                    watermark, clock);
        }
    }
}
