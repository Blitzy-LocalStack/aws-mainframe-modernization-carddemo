package com.carddemo.batch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.common.money.Money;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.Repository;
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
 * Holds the UNBOUNDED forward-only cursor over {@code ledger.daily_transactions} against a real
 * engine, which is the one member of {@link DailyTransactionRepository} no other class exercises.
 *
 * <h2>Purpose, and the single property this class owns</h2>
 *
 * <p>Purpose: {@link DailyTransactionRepository} declares two reads over the unposted feed, and they
 * have genuinely different lifetimes. One is a lazily-populated cursor over the WHOLE feed, held open
 * for the duration of a caller's transaction; the other is a bounded chunk that is read, committed and
 * resumed from. The bounded one is already proved beside this file. This class proves the cursor: that
 * it delivers every row in ingestion order across more than one round trip to the engine, that it
 * refuses to open at all when the caller holds no transaction, that it closes, and that it is empty
 * rather than broken over an empty feed. It then proves the one boundary the bounded finder's own
 * chunked scan cannot state as a property -- that the continuation predicate EXCLUDES the ordinal it is
 * given -- and audits the interface's reachable surface for the mutators and the row-counting window it
 * must not expose.</p>
 *
 * <p>The cursor is the migrated form of a sequential read rather than a convenience over one.
 * {@code app/cbl/CBTRN02C.cbl:29-32} selects the feed {@code ORGANIZATION IS SEQUENTIAL} with
 * {@code ACCESS MODE IS SEQUENTIAL} and declares no record key, and the driving loop at
 * {@code app/cbl/CBTRN02C.cbl:202-219} advances it through the single {@code READ} at
 * {@code app/cbl/CBTRN02C.cbl:346} -- front to back, once, with no reposition and no backward read
 * anywhere in the program. The preflight consumes the same feed the same way at
 * {@code app/cbl/CBTRN01C.cbl:164-186}.</p>
 *
 * <h2>Where this table comes from at test time, which is not where the sibling's table comes from</h2>
 *
 * <p>Assumptions: {@code ledger.daily_transactions} is created by
 * {@code src/test/resources/db/testharness/test-harness-schemas-and-foreign-tables.sql} and NOT by
 * Flyway, and a reader arriving from {@code BatchRunRepositoryIT} will otherwise carry the opposite
 * assumption -- {@code batch.batch_run} genuinely does arrive through the production migration, because
 * {@code batch} is the one schema this module owns. {@code ledger} belongs to transaction-service, whose
 * migration is unreachable from this test classpath, so the harness supplies it. The harness runs
 * through {@code withInitScript} as the container becomes ready, which is strictly before the context
 * opens the connection Flyway migrates on, and that ordering is what lets the test profile hold
 * {@code spring.jpa.hibernate.ddl-auto: validate} at the same time as leaving {@code batch} for Flyway
 * to create. The package charter beside this file owns the full derivation and is cited rather than
 * repeated.</p>
 *
 * <p>Assumptions: the harness DDL is therefore the normative physical contract at test time, and the
 * {@code DailyTransaction} mapping was verified against it column by column before anything below was
 * asserted -- fourteen columns on both sides, agreeing on every name, width, scale and nullability. Under
 * {@code validate} a disagreement is not one failing assertion here; it aborts the context and fails
 * every class in this package at once, which is why the check precedes the assertions rather than being
 * discovered by them.</p>
 *
 * <h2>What this class does NOT assert, so that no proof is duplicated</h2>
 *
 * <p>Alternatives Considered: restating the proofs the neighbouring tiers own so a reader of this file
 * would not have to open theirs. Rejected, because a rule asserted in two places is a rule that can be
 * relaxed in one while the other still passes, and nothing is then able to report the divergence.</p>
 *
 * <ul>
 *   <li>{@code CrossSchemaFeedRepositoryIT} owns the BOUNDED chunked scan of this same feed -- the
 *       walk-by-cursor-until-empty loop, its chunk count, and the harness post-state. This class
 *       deliberately does not repeat that loop; it asserts the cursor's exclusivity as a named boundary
 *       instead, which a loop that merely terminates cannot state.</li>
 *   <li>{@code PostingUnitOfWorkIT} owns the three-write atomicity proof, the account and transaction
 *       writes, and the daily-subset finder's window. Nothing here writes either of those tables.</li>
 *   <li>{@code BatchRunRepositoryIT} owns {@code batch.batch_run} and the uniqueness over the run and
 *       step pair that makes a redriven step which already completed a no-op. This class cites that
 *       property when it explains what the continuation finder is FOR, and proves none of it.</li>
 *   <li>Tier two's {@code PreflightDailyTransactionsJobTest} owns the preflight job's validate-only
 *       semantics, its read and skipped counters, and its inability to reach the warn tier. Tier two's
 *       {@code PostTransactionsJobTest} owns the graded return code, the inversion of a baseline step
 *       gate into an orchestrator predicate, the declared transaction boundary and job-level parity
 *       against the reference goldens.</li>
 *   <li>Tier one's {@code PostingValidationServiceTest} owns reject-reason precedence, the verbatim
 *       reject literals and both inclusive boundaries. Nothing here asserts validation at all.</li>
 *   <li>No case here asserts a byte image or a fixed-width encoding. The 350-byte layout at
 *       {@code app/cpy/CVTRA06Y.cpy} is the mapper's concern and tier one's; this tier asserts ROWS, and
 *       where a value matters it asserts the field.</li>
 * </ul>
 *
 * <h2>Boundaries</h2>
 *
 * <p>Assumptions: this class writes ROWS into {@code ledger.daily_transactions} and authors no
 * structure of any kind. That DDL belongs to transaction-service's own migration, and the test-time
 * physical shape belongs to the sibling harness; a definition issued from here would be a second
 * definition of a table another context already owns, diverging silently the moment either side changed.
 * No index is declared or asserted for the same reason.</p>
 *
 * <p>Assumptions: every amount, identifier and instant below is a literal, and no case reads a wall
 * clock. A value compared against the current time passes for a reason unrelated to the code under test
 * and fails only when two reads straddle a boundary, which reproduces at the hour it was introduced and
 * at no other. This mirrors the reference suite's own discipline of injecting the business date rather
 * than reading it, recorded at {@code tests/README.md} section 11.</p>
 *
 * <p>Every path under {@code app/} cited here is reference material, read as the specification and never
 * modified. The oracle suite under {@code tests/} and the runners under {@code scripts/} are read the
 * same way and are never modified or re-pinned.</p>
 *
 * <p>Assumptions: no queue emulator and no cloud emulator belongs here. The test profile leaves the
 * messaging properties unset and the module's queue configuration is gated on one of them with no
 * match-if-missing fallback, so the gate stays closed and nothing here needs a transport stood up.</p>
 *
 * @see DailyTransactionRepository
 * @see DailyTransaction
 */
@Testcontainers
// Refactoring Rationale: the Parameter Store config-data location is disabled for this context. The
//     base profile's `optional:aws-parameterstore:` location builds a parameter-store client while
//     configuration is still loading, so a build host with no region configured aborts the context on an
//     unresolved placeholder before a single row is read. The sibling BatchRunRepositoryIT records the
//     full measurement, why disabling the LOAD is sufficient, and the two rejected alternatives; it is
//     cited from here rather than repeated.
@SpringBootTest(
        classes = DailyTransactionRepositoryIT.DailyFeedCursorPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.aws.parameterstore.enabled=false")
@ActiveProfiles("test")
class DailyTransactionRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: this is the digest every sibling integration test in this build already pins. Two
     * classes pinning two engines could disagree about one schema, and the disagreement would surface as
     * whichever of them ran second. The version is recorded in prose beside it because a digest states
     * nothing a reader recognises, and the two are changed together.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The classpath-relative path of the foreign-schema harness the container runs at start.
     *
     * <p>Assumptions: the file NAME is part of the contract rather than a description of the file. It is
     * deliberately not a Flyway migration -- it carries no version prefix and sits outside
     * {@code db/migration} -- because Flyway applying it would place another context's tables under this
     * module's migration history. Neither renaming nor relocating it is a local change.</p>
     */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /**
     * The rows-per-round-trip window the cursor's own query hint fixes.
     *
     * <p>Assumptions: the value is the constant {@link DailyTransactionRepository} declares on the
     * cursor as a fetch-size hint, and the test profile happens to set the same number as the session
     * default. Only the hint governs the statement under test, so this constant tracks the hint. It is
     * held here as a named quantity because the row count below is derived from it rather than chosen.</p>
     */
    private static final int CURSOR_FETCH_SIZE = 100;

    /**
     * The number of rows the cursor cases arrange, one more than a single round trip carries.
     *
     * <p>Trade-offs: arranging strictly more rows than the fetch window costs an extra hundred inserts
     * per case, and that cost is accepted deliberately. A walk fitting inside one round trip never asks
     * the driver for a second batch, so it would pass identically whether the cursor streamed or
     * buffered the whole result -- and the streaming contract is the property this class exists to hold.
     * One row beyond the window is the smallest arrangement that forces the second fetch, so the price
     * paid is the minimum that buys the proof.</p>
     */
    private static final int ROWS_ACROSS_FETCH_BOUNDARY = CURSOR_FETCH_SIZE + 1;

    /**
     * The number of rows the cursor-boundary and money cases arrange.
     *
     * <p>Assumptions: these cases assert a predicate and a column round trip rather than a fetch
     * boundary, so the row count only has to be large enough to place a cursor with rows on both sides
     * of it. Five leaves two rows before the probed ordinal and two after.</p>
     */
    private static final int ROWS_FOR_CURSOR_PROBES = 5;

    /**
     * The transaction type code every arranged row carries.
     *
     * <p>Assumptions: the width is the two characters {@code app/cpy/CVTRA06Y.cpy} declares for
     * {@code DALYTRAN-TYPE-CD}, and the value is a fixture constant carrying no meaning. No case here
     * reads it; it is supplied because the column exists.</p>
     */
    private static final String TYPE_CD = "01";

    /**
     * The transaction category code every arranged row carries.
     *
     * <p>Assumptions: four characters, matching {@code DALYTRAN-CAT-CD} at
     * {@code app/cpy/CVTRA06Y.cpy}. The blank-padded form matters to the key lookups elsewhere in the
     * module and is preserved here so a row arranged by this class is shaped like a row the loader
     * produces.</p>
     */
    private static final String CATEGORY_CD = "0001";

    /**
     * The origination source every arranged row carries, blank-padded to its declared width.
     *
     * <p>Assumptions: ten characters, matching {@code DALYTRAN-SOURCE} at
     * {@code app/cpy/CVTRA06Y.cpy}. The padding is retained rather than trimmed because the column is
     * fixed width and a trimmed value would compare unequal to the padded form the loader writes.</p>
     */
    private static final String SOURCE = "POS       ";

    /**
     * The card number every arranged row carries.
     *
     * <p>Assumptions: sixteen digits, matching {@code DALYTRAN-CARD-NUM} at
     * {@code app/cpy/CVTRA06Y.cpy}. The value is deliberately synthetic rather than drawn from the
     * reference extract, so it identifies no real card and matches no issuer range. It is also kept
     * clear of the range the arranged transaction identifiers occupy, so a reader comparing two
     * sixteen-character columns in a failure message cannot mistake one for the other.</p>
     */
    private static final String FIXTURE_CARD_NUM = "0000000000009901";

    /**
     * The merchant identifier every arranged row carries.
     *
     * <p>Assumptions: nine digits, matching {@code DALYTRAN-MERCHANT-ID} at
     * {@code app/cpy/CVTRA06Y.cpy}, held as a number because the owning schema declares the column as
     * one.</p>
     */
    private static final long FIXTURE_MERCHANT_ID = 900009901L;

    /**
     * The amount the lowest-ordinal arranged row carries, exercising the positive sign.
     *
     * <p>Assumptions: seven integer digits and two decimals, comfortably inside the nine integer digits
     * {@code DALYTRAN-AMT PIC S9(09)V99} permits and inside the {@code NUMERIC(11,2)} the owning
     * migration declares. It is written as a string literal so no binary approximation can enter the
     * value on the way to the engine.</p>
     */
    private static final BigDecimal POSITIVE_AMOUNT = new BigDecimal("1234567.89");

    /**
     * The amount the second-lowest-ordinal arranged row carries, exercising the negative sign.
     *
     * <p>Assumptions: a negative amount is arranged deliberately, because the reference feed genuinely
     * carries them -- fifty of the three hundred rows of {@code app/data/ASCII/dailytran.txt} hold a
     * negative sign overpunch -- and a column that lost the sign would still round-trip every positive
     * fixture without complaint.</p>
     */
    private static final BigDecimal NEGATIVE_AMOUNT = new BigDecimal("-9876543.21");

    /**
     * The amount every arranged row beyond the first two carries.
     *
     * <p>Assumptions: the filler rows exist to make the walk long enough to cross a fetch boundary, so
     * their amount is asserted by no case. It is a fixed literal rather than a varying value so a
     * failure message never depends on which row produced it.</p>
     */
    private static final BigDecimal FILLER_AMOUNT = new BigDecimal("10.00");

    /**
     * The origination instant every arranged row carries, to microsecond resolution.
     *
     * <p>Assumptions: this is a literal and not a clock reading, for the reason the boundaries section
     * above records. It is also the column that makes the processing-stamp case below conclusive: the
     * origination stamp is populated on every arranged row while the processing stamp is absent from
     * every one, so the absence is specific to that column rather than a gap in the arrangement. The
     * reference extract carries the same asymmetry -- reading
     * {@code app/data/ASCII/dailytran.txt} finds the origination stamp populated on all three hundred
     * records and the processing stamp blank on all three hundred.</p>
     */
    private static final LocalDateTime ORIGINATED_AT = LocalDateTime.of(2022, 7, 18, 2, 0, 0);

    /**
     * The number of leading characters a masked card number replaces.
     *
     * <p>Assumptions: twelve of the sixteen declared characters are suppressed, leaving the last four.
     * That is the masking contract the migration plan fixes at its section 0.7.8 for a primary account
     * number outside the one administrative endpoint, and this class holds to it even though its card
     * numbers are synthetic -- a diagnostic that renders a full number is a habit, and a habit formed on
     * fixture data is the one that later prints production data.</p>
     */
    private static final int MASKED_PREFIX_LENGTH = 12;

    /**
     * The engine this class asserts against, started once and destroyed with the class.
     *
     * <p>Alternatives Considered: one shared abstract base class holding the container for every member
     * of this package. Rejected by the package charter and not reopened here: a container held in a base
     * class is shared mutable state, so rows one class inserts are rows another reads, and the failure
     * then names whichever class happened to run second. Trade-offs: the accepted cost is a fourth
     * container start and a fourth copy of this declaration, paid whenever the declaration changes.</p>
     *
     * <p>Alternatives Considered: an in-memory engine. Rejected because the property under test is an
     * engine behaviour -- a server-side cursor delivering an ordered result in more than one batch --
     * which a substitute implements differently or not at all, so a passing assertion would say nothing
     * about the engine the nightly chain runs against. No embedded driver is on this module's
     * classpath.</p>
     */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /** The feed under test, injected as the production repository interface rather than a stand-in. */
    @Autowired
    private DailyTransactionRepository feed;

    /**
     * The transaction boundary the cursor requires and every arrange step commits inside.
     *
     * <p>Assumptions: a boundary is mandatory rather than convenient. The cursor declares
     * {@code Propagation.MANDATORY}, so it refuses to open unless a caller already holds a transaction,
     * and each arrange step is committed on its own so the walk that follows reads a committed table
     * rather than its own open transaction's buffer.</p>
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A plain JDBC handle, used to arrange rows the repository has no method to write. */
    private JdbcTemplate jdbc;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * @param registry the Spring test property registry this method adds the container's JDBC URL, user
     *     name and credential to as deferred suppliers; must not be {@code null}
     */
    // Assumptions: no connection literal appears anywhere in this class or in the test profile, and none
    //     may be introduced. A container assigns its host port as it starts, so a literal authored
    //     beforehand would either address nothing or -- the worse outcome, because it passes -- address
    //     whatever database happened to be listening.
    // Assumptions: the two migration credentials are registered as well and are not redundant with the
    //     datasource pair. The base profile binds the Flyway user and password to placeholders carrying
    //     no fallback, and the framework reads those keys only when no connection-details bean supplies
    //     them instead -- which is this module's case, because the annotation contributing such a bean
    //     ships in an artifact this POM deliberately does not declare. Leaving them unregistered aborts
    //     the context on an unresolved placeholder before any migration runs.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties the feed and opens a JDBC handle before each case.
     *
     * <p>Assumptions: the rows are deleted rather than each case being wrapped in a rolled-back
     * transaction. The cursor joins the caller's transaction by declaration, so an enclosing test
     * transaction would make every walk read that transaction's own uncommitted buffer -- which is
     * exactly the reading the arrange step is designed to avoid, and it would pass whether the engine
     * ordered the result or not. Deleting between cases keeps each one independent in the spirit
     * {@code tests/README.md} section 11 states for the reference suite, which is what lets any single
     * case here be run alone and still mean something.</p>
     *
     * <p>Assumptions: only rows are removed. The statement is data manipulation and not a definition,
     * because the table's structure belongs to the owning migration and its test-time shape to the
     * harness. The narrow repository base offers no bulk delete, which is why the handle below issues
     * it.</p>
     *
     * @param dataSource the pool the context built from the container's coordinates, wrapped here for
     *     the arrange statements; must not be {@code null}
     */
    @BeforeEach
    void emptyFeed(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactionTemplate.executeWithoutResult(
                status -> this.jdbc.update("DELETE FROM ledger.daily_transactions"));
    }

    /**
     * Confirms the cursor delivers every row in ingestion order and keeps that order past the point
     * where the driver must fetch again.
     *
     * <p>This is the migrated form of the read loop at {@code app/cbl/CBTRN02C.cbl:202-219}, whose file
     * is declared {@code ORGANIZATION IS SEQUENTIAL} with {@code ACCESS MODE IS SEQUENTIAL} at
     * {@code app/cbl/CBTRN02C.cbl:29-32}. The reference covers the dataset once, front to back, and the
     * assertion below is that the migrated cursor covers it in the same direction and in a total
     * order.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the cursor delivers every row in ingestion order across a fetch boundary")
    void theCursorDeliversEveryRowInIngestionOrderAcrossAFetchBoundary() {
        this.seedFeedWithDescendingIdentifiers(ROWS_ACROSS_FETCH_BOUNDARY);

        List<DailyTransaction> walked = this.walkedRows();

        assertThat(walked)
                .as("the walk is unbounded, so it delivers the whole feed rather than a window of it")
                .hasSize(ROWS_ACROSS_FETCH_BOUNDARY);
        assertThat(walked.size())
                .as("the arrangement exceeds the %s-row fetch window, so the driver was made to fetch"
                        + " more than once", CURSOR_FETCH_SIZE)
                .isGreaterThan(CURSOR_FETCH_SIZE);

        List<Long> ordinals = ordinalsOf(walked);
        // Assumptions: sorted-and-no-duplicates together mean STRICTLY ascending, and the pair is
        //     asserted rather than only the first and last ordinals. A comparison of the endpoints alone
        //     is satisfied by a result that is out of order in the middle, which is precisely the shape a
        //     second fetch could introduce and the shape this case exists to exclude.
        assertThat(ordinals)
                .as("every ordinal is greater than the one before it, across the whole result")
                .isSorted()
                .doesNotHaveDuplicates();

        // Alternatives Considered: arranging the rows in ascending ordinal order, which is the obvious
        //     fixture and proves nothing. Rows stored in the order they are asserted in come back in
        //     that order under an ordering clause, under a different one, and under none at all, so the
        //     case would pass with the clause deleted. The arrangement writes them in the exact REVERSE
        //     instead, so the sequence asserted below is one only the engine's ordering can produce, and
        //     the two assertions that follow are what say so rather than leaving it to be inferred.
        assertThat(ordinals.getFirst())
                .as("the row delivered FIRST is the row written LAST, so the delivered order is not the"
                        + " order the rows are stored in")
                .isEqualTo(1L);
        assertThat(ordinals.getLast())
                .as("the row delivered LAST is the row written FIRST, closing the reversal at both ends")
                .isEqualTo((long) ROWS_ACROSS_FETCH_BOUNDARY);

        assertThat(identifiersOf(walked))
                .as("the transaction identifiers arrive descending, because they ascend with the storage"
                        + " order the walk reverses")
                .isSortedAccordingTo(Comparator.reverseOrder());
    }

    /**
     * Confirms the cursor orders by the ingestion ordinal and not by the processing stamp, which is
     * absent from every row it returns.
     *
     * <p>{@code app/cpy/CVTRA06Y.cpy} declares {@code DALYTRAN-PROC-TS} on the feed record, and the name
     * invites a reader to treat it as the order the feed arrives in. It cannot serve as one: the harness
     * declares {@code proc_ts} nullable for this table, as the fixture guide under
     * {@code src/test/resources/fixtures} records, and it is absent on every unposted row -- which is
     * every row the feed holds. A stamp exists only on the POSTED record, minted after this walk has
     * already delivered the row.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the cursor orders by the ingestion ordinal while every row's processing stamp is absent")
    void theCursorOrdersByTheIngestionOrdinalAndNeverByTheProcessingStamp() {
        this.seedFeedWithDescendingIdentifiers(ROWS_FOR_CURSOR_PROBES);

        List<DailyTransaction> walked = this.walkedRows();

        assertThat(walked).hasSize(ROWS_FOR_CURSOR_PROBES);
        // Assumptions: the processing stamp is absent on every row, so a walk ordered by it would be
        //     ordered by nothing -- the engine may return an all-null ordering key in any sequence it
        //     likes, and over a handful of rows it will usually return them in physical order, which
        //     LOOKS sorted. That is the defect this case exists to catch: it would pass on a small
        //     fixture and reorder in production, and a golden-master comparison would report it much
        //     later as an unexplained output difference.
        assertThat(walked)
                .as("no row carries a processing stamp, because posting is what assigns one")
                .allSatisfy(row -> assertThat(row.getProcTs()).isNull());
        assertThat(walked)
                .as("the origination stamp IS present on every row, so the absence above is specific to"
                        + " the processing stamp rather than a gap in the arrangement")
                .allSatisfy(row -> assertThat(row.getOrigTs()).isEqualTo(ORIGINATED_AT));

        assertThat(ordinalsOf(walked))
                .as("the ordinal ordering holds even though the stamp a reader might order on is null"
                        + " on every row")
                .isSorted()
                .doesNotHaveDuplicates();
    }

    /**
     * Confirms the cursor refuses to open when the caller holds no transaction.
     *
     * <p>This proof has NO baseline counterpart and the absence is stated rather than implied: a
     * sequential {@code OPEN} in the reference needs no transaction because the reference has none to
     * need. The property is a target-side consequence of returning a lazily-populated cursor, which stays
     * valid only for the duration of the transaction that opened it, and the interface declares
     * {@code Propagation.MANDATORY} precisely so that the mistake is refused at the call site.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the cursor refuses to open when the caller holds no transaction")
    void theCursorRefusesToOpenWithNoTransactionHeld() {
        this.seedFeedWithDescendingIdentifiers(ROWS_FOR_CURSOR_PROBES);

        // Assumptions: the call is wrapped in a closing block even though it is expected to throw. If the
        //     declaration were ever relaxed and the call SUCCEEDED, the unwrapped form would leak the
        //     cursor it opened into the rest of the class, so the failure of this case would be followed
        //     by unrelated failures elsewhere and the first report would not be the useful one.
        assertThatThrownBy(() -> {
            try (Stream<DailyTransaction> rows = this.feed.findAllByOrderByIngestSeqAsc()) {
                rows.count();
            }
        })
                .as("a walk opened with no enclosing transaction is refused outright rather than"
                        + " returning a cursor that is already closed")
                .isInstanceOf(IllegalTransactionStateException.class);

        // Alternatives Considered: asserting the framework's message text as well as the type. Rejected
        //     because the wording is not part of any contract this project controls, so pinning it would
        //     convert a framework upgrade into a failure of a case that is about propagation.
        assertThat(this.walkedRows())
                .as("the same walk inside a transaction succeeds, so the refusal above is about the"
                        + " missing boundary and not about the feed or the mapping")
                .hasSize(ROWS_FOR_CURSOR_PROBES);
    }

    /**
     * Confirms the cursor over an empty feed delivers no row rather than failing or wrapping.
     *
     * <p>An empty feed is a legitimate input rather than an error, and the reference suite ships the
     * vector for it: {@code tests/fixtures/posting/empty_input/dailytran.txt} is an empty file. A
     * sequential read discovers the end of a dataset by reaching it, so reaching it immediately is the
     * same event arriving first.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the cursor over an empty feed delivers no row")
    void theCursorOverAnEmptyFeedDeliversNoRow() {
        List<DailyTransaction> walked = this.walkedRows();

        assertThat(walked)
                .as("an empty feed yields an empty walk, which is how a sequential pass over an empty"
                        + " dataset ends rather than an error")
                .isNotNull()
                .isEmpty();
    }

    /**
     * Confirms the continuation predicate excludes the ordinal it is given and honours the row cap.
     *
     * <p>This proof has NO baseline counterpart either, and saying so matters more here than anywhere
     * else in this class: the reference has no checkpoint contract at all -- the only
     * {@code RESTART=} anywhere is commented out, at {@code app/jcl/DEFGDGD.jcl:2} -- so resumption is a
     * documented improvement rather than a migrated behaviour. The finder exists so that a redriven
     * orchestrator execution can restart a step part-way through its input; whether that step already
     * completed for the run is a different question, answered by {@code batch.batch_run}, whose
     * uniqueness over the run and step pair {@code BatchRunRepositoryIT} proves and this class does
     * not.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the continuation predicate excludes the ordinal it is given and caps its result")
    void theContinuationPredicateExcludesTheOrdinalItIsGiven() {
        this.seedFeedWithDescendingIdentifiers(ROWS_FOR_CURSOR_PROBES);
        List<Long> ordinals = ordinalsOf(this.walkedRows());
        long probed = ordinals.get(1);

        List<Long> continued = ordinalsOf(
                this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(probed, Limit.of(2)));

        // Assumptions: the predicate is STRICTLY greater, and the caller resumes from the ordinal of a
        //     row it has ALREADY processed. An inclusive bound would hand that row back and the step
        //     would process it a second time; under the additive posting model at
        //     app/cbl/CBTRN02C.cbl:202-219, where each accepted record contributes to a running balance,
        //     processing one record twice moves money twice. The exclusion is therefore asserted as a
        //     named boundary rather than inferred from a loop that happens to terminate -- an inclusive
        //     implementation makes such a loop run forever, which reports as a hung build rather than as
        //     a failed assertion naming the predicate.
        assertThat(continued)
                .as("the probed ordinal is absent from its own continuation")
                .doesNotContain(probed);
        assertThat(continued.getFirst())
                .as("the first row returned lies strictly beyond the probed ordinal")
                .isGreaterThan(probed);
        assertThat(continued)
                .as("the continuation resumes at the very next ordinal and stops at the requested cap")
                .containsExactly(ordinals.get(2), ordinals.get(3));

        // Assumptions: a second probe one position earlier pins WHICH row the predicate excludes, not
        //     merely that it excludes one. Moving the cursor to the first ordinal must move the boundary
        //     by exactly one row; a case that probed a single position could be satisfied by an
        //     off-by-one predicate that excluded the wrong row consistently.
        assertThat(ordinalsOf(this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(
                ordinals.getFirst(), Limit.of(1))))
                .as("moving the cursor back one position moves the excluded row back one position")
                .containsExactly(ordinals.get(1));
    }

    /**
     * Confirms a continuation from the last ordinal delivers no row rather than wrapping or failing.
     *
     * <p>The reference discovers the end of the feed by reaching it, in the loop at
     * {@code app/cbl/CBTRN02C.cbl:202-219}, and the migrated equivalent of that event is an empty
     * result. It is the ordinary way a resumed walk ends, which is why nothing here treats it as an
     * error.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a continuation from the last ordinal delivers no row")
    void theContinuationPredicatePastTheLastOrdinalDeliversNoRow() {
        this.seedFeedWithDescendingIdentifiers(ROWS_FOR_CURSOR_PROBES);
        List<Long> ordinals = ordinalsOf(this.walkedRows());

        assertThat(this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(
                ordinals.getLast(), Limit.of(ROWS_FOR_CURSOR_PROBES)))
                .as("no row remains beyond the last ordinal, and the exhausted case is an empty result"
                        + " rather than an absent one")
                .isNotNull()
                .isEmpty();
    }

    /**
     * Confirms the interface exposes no mutator and no row-counting window, by the shape of its
     * declaration rather than by a call that gets rejected.
     *
     * <p>Nothing in the migrated module writes this feed, and the evidence is documentary:
     * {@code app/jcl/POSTTRAN.jcl:30-31} mounts {@code DALYTRAN} as a plain sequential dataset for input
     * only, the posting loop at {@code app/cbl/CBTRN02C.cbl:202-219} reads it and writes elsewhere, and
     * the preflight at {@code app/cbl/CBTRN01C.cbl:164-186} reads it and writes nothing at all. The rows
     * are produced upstream by the extract-and-load package from {@code app/data/ASCII/dailytran.txt},
     * and the entity is mapped immutable to match.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the interface declares two reads, exposing no mutator and no row-counting window")
    void theFeedInterfaceExposesNoMutatorAndNoRowCountingWindow() {
        // Alternatives Considered: proving the read-only property by calling a mutator and asserting the
        //     failure. Impossible AND weaker. Impossible because the narrow base type contributes no
        //     mutator, so such a call does not compile and there is no runtime behaviour to observe.
        //     Weaker because a rejected call proves only that one path was refused at one moment, under
        //     one set of privileges -- and these tests connect as the container's superuser, for which
        //     every table is writable -- whereas an absent method cannot be called from anywhere, by any
        //     caller, under any privileges.
        List<Method> members = declaredMembers();
        List<String> memberNames = new ArrayList<>();
        for (Method member : members) {
            memberNames.add(member.getName());
        }

        assertThat(memberNames)
                .as("the reachable surface is exactly the two reads, so no write, update or delete"
                        + " exists to be called")
                .containsExactlyInAnyOrder(
                        "findAllByOrderByIngestSeqAsc",
                        "findByIngestSeqGreaterThanOrderByIngestSeqAsc");

        assertThat(DailyTransactionRepository.class.getInterfaces())
                .as("the base type is the bare marker rather than a create-read-update-delete base,"
                        + " which is what makes the closed set above closed")
                .containsExactly(Repository.class);
        assertThat(Repository.class.getDeclaredMethods())
                .as("that marker contributes no member of its own, so nothing is inherited into the"
                        + " surface")
                .isEmpty();

        // Alternatives Considered: a blacklist naming the row-counting request and page types the
        //     charter prohibits across this package. Rejected in favour of the closed whitelist below,
        //     which is strictly stronger: a blacklist admits any row-counting request type nobody thought
        //     to name, whereas a whitelist rejects every type that is not one of the two the contract
        //     actually uses. The prohibition itself has a concrete consequence rather than a preference
        //     behind it -- positioning by counting rows from the start of an ordered set means an insert
        //     landing before the cursor changes how many rows precede it, so such a scan skips rows it
        //     never read and returns rows it already read. This feed is exposed to exactly that, being
        //     loaded by one process while a job walks it, and a key already returned keeps its place in
        //     the ordering no matter what is inserted around it.
        for (Method member : members) {
            assertThat(member.getReturnType())
                    .as("a read returns either the lazy cursor or a bounded list, and nothing that"
                            + " carries a page of counted rows")
                    .isIn(Stream.class, List.class);
            for (Class<?> parameter : member.getParameterTypes()) {
                assertThat(parameter)
                        .as("a read accepts either a continuation key or a row cap, and no request"
                                + " object that positions by counting")
                        .isIn(Long.class, Limit.class);
            }
        }
    }

    /**
     * Confirms an amount survives the round trip through the engine exactly, at scale two and with its
     * sign.
     *
     * <p>{@code app/cpy/CVTRA06Y.cpy} declares {@code DALYTRAN-AMT PIC S9(09)V99}, a signed nine-digit
     * amount with two decimal places, which the owning migration carries as {@code NUMERIC(11,2)}. Only
     * this tier can show that the engine preserved both the scale and the sign, because a substitute for
     * the engine would be asserting its own arithmetic rather than the column's.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an amount round-trips through the engine exactly, at scale two and with its sign")
    void aFeedRowKeepsItsAmountExactAtScaleTwoThroughTheEngine() {
        this.seedFeedWithDescendingIdentifiers(ROWS_FOR_CURSOR_PROBES);

        List<DailyTransaction> walked = this.walkedRows();
        DailyTransaction lowest = walked.getFirst();
        DailyTransaction next = walked.get(1);

        // Assumptions: the comparison is by compareTo and never by equals, because equals on this type
        //     is scale-sensitive: a value of 1234567.9 and a value of 1234567.90 are the same amount and
        //     are not equal objects. The engine returns the column at its declared scale, so an equals
        //     comparison would pass or fail on how the expected literal happened to be written rather
        //     than on the amount, which is the opposite of what an exact fixed-point assertion is for.
        assertThat(lowest.getAmount())
                .as("a positive amount is returned as the same amount it was stored as")
                .isEqualByComparingTo(POSITIVE_AMOUNT);
        assertThat(next.getAmount())
                .as("a negative amount keeps its sign, which a column that dropped it would still pass"
                        + " every positive fixture without")
                .isEqualByComparingTo(NEGATIVE_AMOUNT)
                .isNegative();

        assertThat(lowest.getAmount().scale())
                .as("the amount arrives at the shared money scale rather than at whatever scale the"
                        + " literal was written with")
                .isEqualTo(Money.SCALE);
        assertThat(next.getAmount().scale()).isEqualTo(Money.SCALE);

        // Assumptions: the card number is compared in MASKED form on both sides, so neither the actual
        //     nor the expected value can reach a failure message in full. The migration plan masks a
        //     primary account number to its last four digits at its section 0.7.8 outside the single
        //     administrative endpoint, and that contract is honoured here even though these digits are
        //     synthetic -- a diagnostic that renders a full number is a habit, and the habit is formed on
        //     fixture data before it is exercised on real data. The declared width is asserted separately
        //     because it constrains the column without revealing a digit.
        assertThat(maskedCard(lowest.getCardNum()))
                .as("the card number survives the round trip, asserted masked to its last four")
                .isEqualTo(maskedCard(FIXTURE_CARD_NUM));
        assertThat(lowest.getCardNum())
                .as("the column keeps the sixteen characters the copybook declares")
                .hasSize(FIXTURE_CARD_NUM.length());
    }

    /**
     * Loads the given number of feed rows in the REVERSE of ingestion order, so that the order the walk
     * must produce is the order the rows are not stored in.
     *
     * <p>Alternatives Considered: letting the table's identity assign each ordinal, which is what the
     * sibling feed fixture does and what the loader does, and which was measured to be INSUFFICIENT here.
     * Identity assigns in insertion order, so ordinal order and physical order coincide -- and a scan
     * that ordered by nothing at all would then return exactly the sequence an ordered scan returns. That
     * was verified rather than reasoned about: with identity-assigned ordinals, replacing the cursor's
     * ordering with the processing stamp -- a column absent on every row, and therefore no ordering at
     * all -- left every assertion in this class passing. The arrangement below assigns the ordinal
     * EXPLICITLY and DESCENDING against insertion, so physical order is the exact reverse of the order
     * the cursor must deliver, and an unordered or wrongly-ordered scan fails on the first pair of rows.
     * The owning migration declares the column generated BY DEFAULT rather than ALWAYS, which is what
     * permits a supplied value; nothing here would work against an always-generated column, and a change
     * to that declaration would need this fixture revisited.</p>
     *
     * <p>Trade-offs: this class asserts that the repository has no write path and then writes rows
     * itself, and the two are not in conflict -- but the distinction has to be visible or the read-only
     * claim reads as contradicted by its own arrangement. The REPOSITORY exposes no mutator, which is
     * what the surface audit proves and what a caller is bound by. The arrangement below goes around it
     * entirely, through a statement issued on a plain handle, as the extract-and-load package does when
     * it populates this table upstream. The compromise accepted is that the fixture path does not
     * exercise the mapping's write side; there is no write side to exercise, which is the point.</p>
     *
     * <p>Assumptions: a persist through the context is not available even if one were wanted. The entity
     * places its identifier on the ingestion ordinal with no generation strategy and is mapped immutable,
     * so a persist raises an identifier-generation failure demanding a value it exposes no setter for. A
     * statement is therefore the only path, which is why the ordinal can be supplied at all.</p>
     *
     * <p>Assumptions: the insert omits {@code proc_ts}, which therefore lands absent -- a feed row has
     * not been posted, and posting is what assigns a processing stamp. That omission is what the ordering
     * case relies on, so it is deliberate rather than an oversight in the column list.</p>
     *
     * <p>Assumptions: no value written here identifies a real person, account, card or merchant. Every
     * one is a fixture constant declared above, and the stamped column carries a literal instant.</p>
     *
     * @param rowCount how many rows to load, of type {@code int}; must be positive, and callers derive
     *     it from either the fetch window or the number of rows a cursor probe needs on each side of it
     */
    private void seedFeedWithDescendingIdentifiers(int rowCount) {
        this.transactionTemplate.executeWithoutResult(status -> {
            for (int position = 1; position <= rowCount; position++) {
                long ordinal = rowCount - position + 1L;
                this.jdbc.update(
                        "INSERT INTO ledger.daily_transactions"
                                + " (ingest_seq, transaction_id, type_cd, category_cd, source,"
                                + " description, amount, merchant_id, merchant_name, merchant_city,"
                                + " merchant_zip, card_num, orig_ts)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Fixture Merchant', 'Fixture City',"
                                + " '12345     ', ?, ?)",
                        ordinal,
                        identifierFor(position),
                        TYPE_CD,
                        CATEGORY_CD,
                        SOURCE,
                        "Daily feed cursor fixture row " + position,
                        amountFor(ordinal),
                        FIXTURE_MERCHANT_ID,
                        FIXTURE_CARD_NUM,
                        ORIGINATED_AT);
            }
        });
    }

    /**
     * Opens the unbounded cursor inside a transaction, hands it to the given reader, and closes it.
     *
     * <p>Assumptions: both halves of the call-site contract the interface declares are discharged here
     * and nowhere else, which is what makes this the shape a sibling walk can copy. The cursor is opened
     * INSIDE a transaction, because it declares mandatory propagation and a database cursor stays valid
     * only for the transaction that opened it; and it is opened in a closing block, because a lazily
     * populated result that is abandoned rather than closed holds its cursor and its connection until
     * something else reclaims them. Consuming the result outside the boundary -- assigning the stream in
     * one statement and iterating it in another, after the transaction has committed -- is the mistake
     * this helper exists to make unavailable, and it fails at the first element rather than at the
     * call.</p>
     *
     * @param <R> the type the reader reduces the walk to, chosen by the caller
     * @param reader the function applied to the open cursor, evaluated inside the transaction that owns
     *     it; must not be {@code null} and must not let the stream escape
     * @return whatever the reader produced from the walk
     */
    private <R> R walkFeed(Function<Stream<DailyTransaction>, R> reader) {
        return this.transactionTemplate.execute(status -> {
            try (Stream<DailyTransaction> rows = this.feed.findAllByOrderByIngestSeqAsc()) {
                return reader.apply(rows);
            }
        });
    }

    /**
     * Walks the whole feed through the unbounded cursor and returns the rows in the order delivered.
     *
     * <p>Assumptions: the rows are drawn off the cursor one at a time by the collector, so the walk is
     * still the streaming read the interface's fetch-size hint configures rather than a single buffered
     * result. What is materialised is the OUTCOME of the walk, which is what an ordering assertion has
     * to inspect: an order is a property of a sequence and cannot be asserted one element at a time.</p>
     *
     * @return every row of the feed in the order the cursor delivered it, empty when the feed holds no
     *     rows and never {@code null}
     */
    private List<DailyTransaction> walkedRows() {
        return this.walkFeed(Stream::toList);
    }

    /**
     * Extracts the ingestion ordinals from the given rows, preserving their order.
     *
     * @param rows the rows to read the ordinals from, in the order they were delivered; must not be
     *     {@code null}
     * @return the ingestion ordinals in the same order as the rows, never {@code null}
     */
    private static List<Long> ordinalsOf(List<DailyTransaction> rows) {
        List<Long> ordinals = new ArrayList<>();
        for (DailyTransaction row : rows) {
            ordinals.add(row.getIngestSeq());
        }
        return ordinals;
    }

    /**
     * Extracts the transaction identifiers from the given rows, preserving their order.
     *
     * <p>Assumptions: the identifiers are read for an ORDERING assertion and never as a key. The column
     * is business data the feed may repeat -- the owning migration declares no uniqueness over it -- so
     * this class uses it only to demonstrate that the delivered order came from somewhere else.</p>
     *
     * @param rows the rows to read the identifiers from, in the order they were delivered; must not be
     *     {@code null}
     * @return the transaction identifiers in the same order as the rows, never {@code null}
     */
    private static List<String> identifiersOf(List<DailyTransaction> rows) {
        List<String> identifiers = new ArrayList<>();
        for (DailyTransaction row : rows) {
            identifiers.add(row.getTransactionId());
        }
        return identifiers;
    }

    /**
     * Collects the methods the repository interface itself declares.
     *
     * <p>Assumptions: compiler-generated members are excluded, because a bridge or accessor the compiler
     * emits is not part of the surface a caller can reach and counting one would make the closed set
     * assertion fail for a reason unrelated to the contract.</p>
     *
     * @return the interface's own declared methods, excluding compiler-generated members, never
     *     {@code null}
     */
    private static List<Method> declaredMembers() {
        List<Method> members = new ArrayList<>();
        for (Method candidate : DailyTransactionRepository.class.getDeclaredMethods()) {
            if (!candidate.isSynthetic()) {
                members.add(candidate);
            }
        }
        return members;
    }

    /**
     * Chooses the amount for the row carrying the given ingestion ordinal.
     *
     * <p>Assumptions: the amount is keyed on the ORDINAL rather than on the insertion position, and the
     * two differ here because the arrangement inserts in reverse. Keying on the ordinal is what lets the
     * money case name the signed pair as the first two rows the WALK delivers, which is the sequence it
     * actually inspects, without depending on how many rows were arranged or in which order they were
     * written.</p>
     *
     * @param ordinal the ingestion ordinal assigned to the row, of type {@code long}
     * @return the amount that row carries, at scale two and never {@code null}
     */
    private static BigDecimal amountFor(long ordinal) {
        if (ordinal == 1L) {
            return POSITIVE_AMOUNT;
        }
        if (ordinal == 2L) {
            return NEGATIVE_AMOUNT;
        }
        return FILLER_AMOUNT;
    }

    /**
     * Renders a transaction identifier at the declared sixteen-character width.
     *
     * <p>Assumptions: the leading zeros are significant rather than cosmetic, because
     * {@code app/cpy/CVTRA06Y.cpy} declares {@code DALYTRAN-ID} as sixteen alphanumeric characters and
     * the column mirrors that width, so a shorter value would not compare as the same identifier. The
     * root locale is named explicitly because the conversion selects its digits from the formatting
     * locale, and a build host configured for a locale with a different digit set would otherwise
     * produce characters the column's collation orders differently.</p>
     *
     * @param ordinal the number to render, of type {@code int}; must be non-negative to fit the width
     * @return exactly sixteen characters of zero-padded digits, never {@code null}
     */
    private static String identifierFor(int ordinal) {
        return String.format(Locale.ROOT, "%016d", ordinal);
    }

    /**
     * Masks a card number to its last four characters for use in an assertion or a failure message.
     *
     * @param cardNum the card number to mask, of type {@code String}; may be {@code null} or shorter
     *     than the masked prefix
     * @return the card number with its leading characters replaced by asterisks, or an all-asterisk
     *     value when there is nothing safe to reveal, never {@code null}
     */
    private static String maskedCard(String cardNum) {
        // Assumptions: a short or absent value returns all asterisks rather than the value itself. The
        //     guard exists because the alternative is the one failure mode masking must not have: a
        //     helper that fell back to returning its input would render an unmasked number precisely when
        //     the data was unexpected, which is when a diagnostic is most likely to be read and copied.
        if (cardNum == null || cardNum.length() <= MASKED_PREFIX_LENGTH) {
            return "*".repeat(MASKED_PREFIX_LENGTH);
        }
        return "*".repeat(MASKED_PREFIX_LENGTH) + cardNum.substring(MASKED_PREFIX_LENGTH);
    }

    /**
     * The minimal application configuration this class runs against.
     *
     * <p>Assumptions: no component scan is declared, so the module's job definitions, its queue client
     * and its object-store client stay out of the context and no emulator is needed to start it. Naming
     * the two persistence packages leaves the framework's own auto-configuration to build the pool from
     * the properties the container registered, and the cross-schema reach the feed needs comes from the
     * test profile's connection initialisation rather than from a configuration class here.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.batch.domain")
    @EnableJpaRepositories("com.carddemo.batch.repository")
    static class DailyFeedCursorPersistenceTestApplication {
    }
}
