package com.carddemo.authorization.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.authorization.domain.AuthFraud;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Settles against a real engine what no mock can about the atomic fraud write.
 *
 * <p><strong>Purpose.</strong> {@link AuthFraudUpserter} is one native statement carrying an
 * {@code ON CONFLICT DO UPDATE} clause and a system-column discriminator. Neither the conflict
 * behaviour, nor which columns the update arm leaves alone, nor the insert-versus-update
 * discriminator has any expression a mock can verify -- a unit test can only prove the statement was
 * ISSUED. This class proves what it DOES.</p>
 *
 * <p>Refactoring Rationale: this test exists because the write it covers was wrong in a way that
 * every existing test passed. The service probed for the fraud row and then branched -- insert when
 * the probe found nothing, mutate when it did -- and under concurrency two investigators marking the
 * SAME authorization both saw an absent row, both took the insert arm, and the second insert violated
 * the primary key and ABORTED the transaction. The reference program does not behave that way: it
 * issues the insert at {@code cbl/COPAUS2C.cbl} L194, tests {@code SQLCODE} for the duplicate-key
 * condition {@code -803} at L199 and performs {@code FRAUD-UPDATE} at L203 and L204. A single-threaded
 * mock-based test cannot see the difference between a handled branch and an unhandled failure, which
 * is why the last case below runs two real transactions at once.
 *
 * <p>Assumptions: the four properties asserted here are exactly the four the repository charter
 * requires a {@code *RepositoryIT} to settle for this table -- that a first write inserts and reports
 * so; that a second write transitions and reports so; that the transition leaves the other
 * twenty-four columns byte-identical; and that two concurrent first marks both succeed. The fifth
 * charter property, that the report date arrives from the server, is asserted by the service test
 * through {@code AuthFraudRepository.currentDate()} and is not restated here.
 *
 * <p>Trade-offs: the persistence unit is built by hand rather than by raising a Spring context,
 * matching {@code PendingAuthSummaryRepositoryIT} and the other integration tests in this package.
 * That costs a little setup and buys a test that starts in well under a second and names exactly the
 * one entity it manages, so a mapping error elsewhere in the module cannot fail it for an unrelated
 * reason.
 */
@Testcontainers
@DisplayName("the atomic fraud write, against a real engine")
class AuthFraudUpserterIT {

    /**
     * The engine image, pinned to the version the deployed cluster runs.
     *
     * <p>Assumptions: the tag matches the other integration tests in this package so one image is
     * pulled for the whole module rather than one per test class.
     */
    private static final String POSTGRES_IMAGE = "postgres:17.10-alpine";

    /** The schema this module owns, and the only one the migration creates here. */
    private static final String SCHEMA_NAME = "authorization";

    /**
     * The connection-level search-path pin.
     *
     * <p>Assumptions: this is REQUIRED rather than tidy. The statement under test names
     * {@code auth_fraud} unqualified, exactly as it does in production, where
     * {@code config/DataSourceConfig} pins the path. Qualifying it in the test would exercise a
     * different statement from the one that ships.
     */
    private static final String SEARCH_PATH_PIN = "SET search_path TO " + SCHEMA_NAME;

    /** A sixteen-digit primary account number, fabricated for this test. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The composed authorization timestamp forming the other half of the key. */
    private static final LocalDateTime AUTH_TS = LocalDateTime.of(2022, 7, 18, 9, 16, 44, 902_000_000);

    /**
     * The complemented ordinal date half of the authorization's own key.
     *
     * <p>Assumptions: this is a COMPLEMENTED value and not a readable date, matching the reference
     * key-ordering device at {@code cbl/COPAUA0C.cbl} L874. Nothing in this test decodes it; it exists
     * so the fabricated authorization carries a well-formed key.
     */
    private static final int AUTH_DATE_KEY = 26_215;

    /**
     * The positional time half of the authorization's own key.
     *
     * <p>Assumptions: read POSITIONALLY this is 09:16:44 and 902 milliseconds. It is deliberately not
     * a count of milliseconds since midnight, which would name no instant at all.
     */
    private static final int AUTH_TIME_KEY = 9_16_44_902;

    /** The account the fabricated authorization belongs to. */
    private static final long ACCOUNT_ID = 111_111_111L;

    /** The customer the fabricated account belongs to. */
    private static final long CUSTOMER_ID = 222_222_222L;

    /** The engine the statements run against. */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /** The pool the persistence unit and the raw assertions share. */
    private static HikariDataSource dataSource;

    /** The persistence unit, closed after the last case. */
    private static LocalContainerEntityManagerFactoryBean persistenceUnit;

    /** The factory each case takes its own persistence context from. */
    private static EntityManagerFactory entityManagerFactory;

    /**
     * Applies the migration and builds a persistence unit managing only the fraud row.
     */
    @BeforeAll
    static void startEngineAndPersistence() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA_NAME)
                .defaultSchema(SCHEMA_NAME)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setConnectionInitSql(SEARCH_PATH_PIN);

        // WHY : Assumptions: the pool holds at least three connections because the concurrency case
        //       below runs two transactions at the same time and this thread holds a third for its
        //       assertions. A smaller pool would make that case block on connection acquisition and
        //       time out, which would look like the deadlock it exists to disprove.
        dataSource.setMaximumPoolSize(4);

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setGenerateDdl(false);

        persistenceUnit = new LocalContainerEntityManagerFactoryBean();
        persistenceUnit.setDataSource(dataSource);
        persistenceUnit.setPersistenceUnitName("carddemo-authorization-fraud-upsert-it");
        persistenceUnit.setManagedTypes(PersistenceManagedTypes.of(AuthFraud.class.getName()));
        persistenceUnit.setJpaVendorAdapter(adapter);
        persistenceUnit.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        persistenceUnit.afterPropertiesSet();
        entityManagerFactory = persistenceUnit.getObject();
    }

    /**
     * Releases the persistence unit and the pool once every case has run.
     *
     * <p>Assumptions: the container is released by the Testcontainers extension; the pool and the
     * persistence unit are this class's own and would keep non-daemon threads alive if left open.
     */
    @AfterAll
    static void stopPersistence() {
        if (persistenceUnit != null) {
            persistenceUnit.destroy();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * Empties the table so each case starts from a known state.
     *
     * @throws SQLException if the statement cannot be issued
     */
    @BeforeEach
    void emptyTheTable() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM auth_fraud")) {
            statement.executeUpdate();
        }
    }

    /**
     * A first write inserts the row and reports that it did.
     */
    @Test
    @DisplayName("a first write inserts the row and reports an insert")
    void aFirstWriteInsertsAndReportsSo() {
        boolean inserted = applyInOwnTransaction(fraudRow(PendingAuthDetail.FRAUD_REPORTED));

        assertThat(inserted)
                .as("the first write of a key has no row to conflict with, so it inserts")
                .isTrue();
        assertThat(storedColumn("auth_fraud")).isEqualTo(PendingAuthDetail.FRAUD_REPORTED);
    }

    /**
     * A second write transitions the two columns the reference update names and reports an update.
     *
     * <p>Assumptions: the second row proposes a DIFFERENT action and a deliberately different snapshot,
     * so the assertions below can tell which arm ran from the data alone rather than only from the
     * returned discriminator. The pairing matters: {@code 'F'} reports fraud and {@code 'R'} releases
     * it, so two marks of one authorization can assert OPPOSITE states and the second must win.
     */
    @Test
    @DisplayName("a second write transitions exactly two columns and reports an update")
    void aSecondWriteTransitionsAndReportsSo() {
        applyInOwnTransaction(fraudRow(PendingAuthDetail.FRAUD_REPORTED));

        AuthFraud second = fraudRow(PendingAuthDetail.FRAUD_REMOVED);
        boolean inserted = applyInOwnTransaction(second);

        assertThat(inserted)
                .as("a row already existed for the key, so the conflict arm ran")
                .isFalse();
        assertThat(storedColumn("auth_fraud"))
                .as("the released state supersedes the reported one")
                .isEqualTo(PendingAuthDetail.FRAUD_REMOVED);
        assertThat(rowCount())
                .as("the conflict arm updates in place and never adds a second row for one key")
                .isEqualTo(1);
    }

    /**
     * The update arm leaves the other twenty-four columns exactly as the first report wrote them.
     *
     * <p>Refactoring Rationale: this is the assertion the previous unit test could not make. It
     * asserted that the SERVICE called {@code applyState} on a detached object, which proves the Java
     * touched two fields; it could not prove the DATABASE left the other twenty-four alone, because no
     * statement ran. Here a second write proposes a wholly different snapshot and every column of it
     * is read back.
     */
    @Test
    @DisplayName("the update arm preserves the first report's twenty-four-column snapshot")
    void theUpdateArmPreservesTheSnapshot() {
        applyInOwnTransaction(fraudRow(PendingAuthDetail.FRAUD_REPORTED));
        Map<String, Object> afterFirst = storedRow();

        applyInOwnTransaction(supersedingRow());
        Map<String, Object> afterSecond = storedRow();

        assertThat(afterSecond)
                .as("the two columns the reference update names at cbl/COPAUS2C.cbl L222-L225 change")
                .containsEntry("auth_fraud", PendingAuthDetail.FRAUD_REMOVED);
        afterFirst.keySet().stream()
                .filter(column -> !"auth_fraud".equals(column))
                .filter(column -> !"fraud_rpt_date".equals(column))
                .forEach(column -> assertThat(afterSecond.get(column))
                        .as("column %s belongs to the first report's snapshot and is not rewritten",
                                column)
                        .isEqualTo(afterFirst.get(column)));
    }

    /**
     * Two concurrent first marks both succeed; neither aborts on the primary key.
     *
     * <p><strong>Purpose.</strong> This is the case the finding was raised for and the reason the
     * write is one statement. Both threads open a transaction and write the SAME key with no
     * coordination between them. Under the previous probe-then-write pair both would have found an
     * absent row, both would have inserted, and the loser's transaction would have aborted with a
     * constraint violation -- losing a mark the reference system would have applied.
     *
     * <p>Assumptions: the assertion is on the PAIR of outcomes rather than on which thread won,
     * because the winner is not determined and must not be. Exactly one insert and exactly one update
     * is the whole contract: it says both calls returned normally, and it says the engine serialised
     * them on the constraint rather than admitting two rows.
     *
     * @throws Exception if either thread fails, or if the barrier is interrupted
     */
    @Test
    @DisplayName("two concurrent first marks both succeed, one inserting and one updating")
    void twoConcurrentFirstMarksBothSucceed() throws Exception {
        CountDownLatch bothReady = new CountDownLatch(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            // WHY : Assumptions: the latch makes the two transactions genuinely overlap. Without it the
            //       first would very likely commit before the second began, and the test would pass
            //       against the DEFECTIVE implementation too -- a sequential pair is exactly the case
            //       the probe handled correctly.
            Future<Boolean> first = threads.submit(() -> {
                bothReady.countDown();
                bothReady.await(10, TimeUnit.SECONDS);
                return applyInOwnTransaction(fraudRow(PendingAuthDetail.FRAUD_REPORTED));
            });
            Future<Boolean> second = threads.submit(() -> {
                bothReady.countDown();
                bothReady.await(10, TimeUnit.SECONDS);
                return applyInOwnTransaction(fraudRow(PendingAuthDetail.FRAUD_REMOVED));
            });

            boolean firstInserted = first.get(30, TimeUnit.SECONDS);
            boolean secondInserted = second.get(30, TimeUnit.SECONDS);

            assertThat(firstInserted ^ secondInserted)
                    .as("exactly one of two concurrent marks inserts and the other takes the update arm;"
                            + " neither fails, which is what the reference -803 branch obtains")
                    .isTrue();
            assertThat(rowCount())
                    .as("the constraint admitted one row, not two")
                    .isEqualTo(1);
        } finally {
            threads.shutdownNow();
        }
    }

    /**
     * Applies one row through the class under test inside its own transaction.
     *
     * <p>Assumptions: the transaction is begun and committed here rather than by Spring, because this
     * test raises no context. The upserter declares {@code Propagation.MANDATORY} so that in
     * production it can never open one of its own; that annotation is not enforced without a
     * transaction manager, and an explicit begin/commit here is the equivalent guarantee -- the
     * statement runs inside a transaction, which is what the concurrency case depends on.
     *
     * @param row the fully projected fraud row to write; must not be {@code null}
     * @return {@code true} when this call inserted, {@code false} when it transitioned an existing row
     */
    private static boolean applyInOwnTransaction(AuthFraud row) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            AuthFraudUpserterImpl upserter = new AuthFraudUpserterImpl();
            upserter.setEntityManager(entityManager);
            entityManager.getTransaction().begin();
            boolean inserted = upserter.upsert(row);
            entityManager.getTransaction().commit();
            return inserted;
        } catch (RuntimeException failure) {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            throw failure;
        } finally {
            entityManager.close();
        }
    }

    /**
     * Builds a fraud row for the fixed key carrying the requested fraud action.
     *
     * @param action the fraud action to record, one of the two the closed domain admits
     * @return a fully projected fraud row for the fixed key; never {@code null}
     */
    private static AuthFraud fraudRow(String action) {
        return AuthFraud.from(detail(), AUTH_TS, ACCOUNT_ID, CUSTOMER_ID, action,
                LocalDate.of(2026, 8, 9));
    }

    /**
     * Builds a row for the same key whose snapshot columns all differ from {@link #fraudRow(String)}.
     *
     * <p>Assumptions: every snapshot value differs so the preservation assertion cannot pass by
     * coincidence. A superseding row carrying the same merchant and the same amounts would satisfy a
     * byte-comparison whether the update arm respected the two-column restriction or not.
     *
     * @return a superseding row proposing the released state and a wholly different snapshot
     */
    private static AuthFraud supersedingRow() {
        PendingAuthDetail different = new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE_KEY, AUTH_TIME_KEY),
                "220719", "101530", CARD_NUMBER, "0200", "2812", "0200", "0001",
                "AUTH99", "05", "9999", "004000",
                new BigDecimal("999.99"), new BigDecimal("888.88"),
                "5812", "826", (short) 9, "MERCHANT999999", "SUPERSEDED MERCHANT",
                "SHELBYVILLE", "NY", "100010000", "TX9999999999999",
                PendingAuthDetail.MATCH_STATUS_DECLINED);
        return AuthFraud.from(different, AUTH_TS, ACCOUNT_ID, CUSTOMER_ID,
                PendingAuthDetail.FRAUD_REMOVED, LocalDate.of(2026, 8, 9));
    }

    /**
     * Builds the authorization the fraud rows are projected from.
     *
     * @return a fabricated authorization carrying the fixed key's account and card
     */
    private static PendingAuthDetail detail() {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE_KEY, AUTH_TIME_KEY),
                "220718", "091644", CARD_NUMBER, "0100", "2712", "0100", "0000",
                "AUTH01", "00", "0000", "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }

    /**
     * The primary key is over the card number and the authorization instant, in that order.
     *
     * <p>Purpose: the upsert's conflict arm names no columns of its own -- it conflicts on the primary
     * key -- so the key's composition IS the statement's idempotency contract. A key over the card
     * number alone would make a second authorization of one card update the first; a key over a wider
     * tuple would make the conflict arm unreachable and every mark insert a new row.
     *
     * <p>Assumptions: the composition is read from the LIVE catalogue through
     * {@code pg_get_indexdef} rather than from the migration text. A test that greps the migration
     * asserts what somebody wrote; this asserts what the engine built, which is the only version a
     * statement can conflict against.
     *
     * <p>Assumptions: the ordering of the two key columns is significant and is asserted as a
     * sequence, not as a set. A composite index serves a lookup on its LEADING column, and every read
     * of this table qualifies on the card number, so a key declared the other way round would leave
     * those reads unable to use it while every uniqueness assertion still passed.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the primary key is over (card_num, auth_ts) in that order")
    void thePrimaryKeyIsOverTheCardAndTheInstantInOrder() {
        assertThat(indexDefinition("pk_auth_fraud"))
                .as("the conflict arm depends on this exact key, read from the live catalogue")
                .contains("UNIQUE")
                .contains("(card_num, auth_ts)");
    }

    /**
     * The recency index leads on the card number ascending and orders the instant descending.
     *
     * <p>Purpose: this is the migrated form of the reference index {@code XAUTHFRD}, whose whole
     * purpose is that the most recent fraud report of one card is the FIRST row read. A
     * default-ascending second column would make it the last, so a bounded read of the latest report
     * would return the oldest while remaining a valid index in every other respect.
     *
     * <p>Assumptions: the direction is asserted by the presence of {@code DESC} on the second column
     * and its absence on the first, because the engine renders only the non-default direction. That is
     * a positive assertion on both columns rather than on one: an index declared descending on both
     * would render {@code DESC} twice and fail the second half.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the recency index is (card_num ASC, auth_ts DESC) in the live catalogue")
    void theRecencyIndexOrdersTheInstantDescending() {
        assertThat(indexDefinition("idx_auth_fraud_card_recent"))
                .as("the newest report of one card must be the first row this index yields")
                .contains("(card_num, auth_ts DESC)")
                .doesNotContain("(card_num DESC");
    }

    /**
     * Reads one index definition back from the engine's own catalogue.
     *
     * <p>Assumptions: the index is resolved by NAME within this schema, so a definition returned
     * cannot belong to a same-named index of another table in another schema.
     *
     * @param indexName the unqualified name of the index or key to read
     * @return the definition the engine reports for it
     * @throws IllegalStateException if the catalogue cannot be read
     */
    private static String indexDefinition(String indexName) {
        String query = "SELECT pg_get_indexdef(i.indexrelid) FROM pg_index i"
                + " JOIN pg_class c ON c.oid = i.indexrelid"
                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE c.relname = ? AND n.nspname = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, indexName);
            statement.setString(2, SCHEMA_NAME);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("the schema holds an index named %s", indexName)
                        .isTrue();
                return rows.getString(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("the catalogue entry for " + indexName
                    + " could not be read", failure);
        }
    }

    /**
     * Reads one column of the single stored fraud row.
     *
     * @param column the column to read
     * @return that column's value, trimmed when it is character data
     */
    private static String storedColumn(String column) {
        Object value = storedRow().get(column);
        return value == null ? null : value.toString().trim();
    }

    /**
     * Reads the whole stored fraud row as a column-to-value map.
     *
     * <p>Assumptions: the read is raw JDBC rather than JPA, because the point is what the DATABASE
     * holds. A JPA read could return a cached entity and would prove nothing about the statement.
     *
     * @return every column of the single stored row, keyed by column name in declaration order
     * @throws IllegalStateException if the row cannot be read, which for this fixed schema means the
     *     engine or the pool is unavailable rather than the data being wrong
     */
    private static Map<String, Object> storedRow() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT * FROM auth_fraud");
                ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).as("exactly one fraud row is stored").isTrue();
            ResultSetMetaData metaData = rows.getMetaData();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 1; column <= metaData.getColumnCount(); column++) {
                row.put(metaData.getColumnName(column), rows.getObject(column));
            }
            return row;
        } catch (SQLException failure) {
            throw new IllegalStateException("the stored fraud row could not be read", failure);
        }
    }

    /**
     * Counts the stored fraud rows.
     *
     * @return the number of rows in the table
     * @throws IllegalStateException if the count cannot be read, which for this fixed schema means the
     *     engine or the pool is unavailable rather than the data being wrong
     */
    private static int rowCount() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT count(*) FROM auth_fraud");
                ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("the fraud rows could not be counted", failure);
        }
    }
}
