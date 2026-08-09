package com.carddemo.authorization.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.authorization.domain.AuthFraud;
import com.carddemo.authorization.domain.AuthFraudKey;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Pins the schema truth of {@code auth_fraud} onto a real engine: two catalogue objects and one upsert.
 *
 * <p>Purpose: this class settles the four properties of the migrated Db2 fraud table that only a live
 * engine can answer, and that {@code AuthFraudRepository} states it relies on rather than restates.
 * They are: the primary key is the composite {@code (card_num, auth_ts)} in that order and is called
 * {@code pk_auth_fraud}; a SECOND catalogue object {@code idx_auth_fraud_card_recent} exists over the
 * same pair with the second component DESCENDING, which is what makes the newest-first read an index
 * scan rather than a sort; the upsert behaves correctly in BOTH directions, inserting a row that is
 * absent and replacing exactly two columns on a row that is present; and the report date on either path
 * comes from the engine rather than from this process.
 *
 * <p>Refactoring Rationale: this class did not exist while the package charter beside it enumerated it
 * as one of four, and while {@code AuthFraudRepository}'s own header listed five things it relies on
 * "a {@code *RepositoryIT} in this module's own test tree" to settle. Both documents were therefore
 * asserting coverage that was nowhere in the reactor, and the reason that is worse than an ordinary
 * gap is that the gap was invisible in exactly the direction it mattered: a reader auditing whether
 * the descending index survived the migration would find two documents saying it was asserted, stop
 * looking, and never discover that an all-ascending index would have passed every check that actually
 * ran. Writing the class was preferred to narrowing the two documents because the Agent Action Plan
 * fixes the index itself as a preserved contract -- "index on {@code (card_num ASC, auth_ts DESC)}
 * matching {@code XAUTHFRD}" -- so narrowing would have left an explicitly required property
 * unverified while making the paperwork consistent.
 *
 * <p>Assumptions: F1, the primary key is the COMPOSITE {@code (card_num, auth_ts)} and not the account
 * identifier the sibling tables key on. {@code app/app-authorization-ims-db2-mq/ddl/AUTHFRDS.ddl}
 * declares the table this one migrates and its key is over the card and the authorization timestamp,
 * which is what lets one card carry a history of reported authorizations; keying on the account would
 * collapse every authorization of an account onto one row. The column ORDER is part of the property
 * rather than incidental: a key declared {@code (auth_ts, card_num)} would admit the same rows and
 * would make the card-prefixed range scan below impossible, so the assertion reads the catalogue's
 * ordinal positions rather than a set of names.
 *
 * <p>Assumptions: F2, the index is a SEPARATE object from the primary key even though it names the same
 * two columns, and its second component is descending. In PostgreSQL a primary key is backed by an
 * ascending B-tree, so a query ordering {@code card_num} ascending and {@code auth_ts} descending can
 * walk that index only backwards in its entirety -- which does not produce the required ordering across
 * more than one card. The mixed-direction index is the object that does. Alternatives Considered:
 * relying on the primary key alone and letting the planner sort. Rejected because the read is bounded
 * by a limit taken from the most recent end, so a sort would materialise a card's whole history to
 * return the newest few, and because {@code AuthFraudRepository#findFraudHistoryForCard} declares the
 * ordering as its contract rather than as a convenience.
 *
 * <p>Assumptions: F3, the direction is asserted from the catalogue's own definition text and not from
 * the index's existence or its name. An index created over {@code (card_num, auth_ts)} with no
 * direction modifiers would carry the same name and would satisfy any check that merely looked the
 * name up, which is the precise failure mode {@code AuthFraudRepository}'s header names when it asks
 * for "the DIRECTION of the index ... taken from the catalogue rather than from the index's
 * existence".
 *
 * <p>Assumptions: F4, the replace path touches exactly two columns. The reference {@code UPDATE} at
 * {@code cbl/COPAUS2C.cbl} L222 to L225 sets the fraud indicator and the current date and nothing
 * else, so the twenty-five-column snapshot a row took when it was first inserted has to survive a
 * second report unchanged. That is asserted by comparing the whole row before and after, rather than
 * by checking the two columns that were meant to change, because only the whole-row comparison can
 * fail on a column that changed and was not supposed to.
 *
 * <p>Trade-offs: the persistence unit is given the whole {@code domain} package rather than the one
 * entity this class asserts, which is the arrangement {@code PendingAuthDetailRepositoryIT} already
 * uses and is not the arrangement {@code PendingAuthSummaryRepositoryIT} uses. The difference is
 * forced: this entity's identifier is an {@code @EmbeddedId} of type {@code AuthFraudKey}, so naming
 * the entity alone would leave the embeddable unmapped and the unit would fail to build, whereas the
 * summary entity's identifier is a plain scalar and can be mapped alone. Mapping the sibling entities
 * costs nothing at run time here -- mapping a type requires only that its table exist, which the
 * migration guarantees, and no case below reads or writes one.
 *
 * <p>Trade-offs: the class name ends in {@code RepositoryIT} because the module's failsafe
 * configuration includes exactly that suffix, so any other name would leave it unrun.
 */
@Testcontainers
class AuthFraudRepositoryIT {

    /** The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine. */
    // WHY : Assumptions: this is deliberately the SAME digest every other integration test in this
    //       module names. Two integration tests pinning two engines could disagree about one
    //       catalogue shape, and the disagreement would surface as whichever ran second; pinning by
    //       digest rather than by tag is what makes the reference immutable, since a publisher may
    //       rebuild and republish a minor tag on a new base layer.
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /** The container every case in this class runs against, started once for the class. */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /** The unquoted schema name, for the Flyway configuration and for catalogue predicates. */
    private static final String SCHEMA_NAME = "authorization";

    /**
     * The schema name as it must appear inside a statement.
     */
    // WHY : Assumptions: the name must be QUOTED. It is a reserved word the parser otherwise reads as
    //       the AUTHORIZATION keyword, which yields a syntax error rather than a missing-schema error
    //       and so points at the wrong thing; data-migration/sql/V0__schemas_and_roles.sql records the
    //       same hazard for the same reason.
    private static final String QUOTED_SCHEMA = "\"authorization\"";

    /** The statement each pooled connection runs so an unqualified table name resolves here. */
    private static final String SEARCH_PATH_PIN = "SET search_path TO " + QUOTED_SCHEMA;

    /** The table under assertion, unqualified because the search path is pinned. */
    private static final String TABLE = "auth_fraud";

    /** The name the migration gives the composite primary key. */
    private static final String PRIMARY_KEY_NAME = "pk_auth_fraud";

    /** The name the migration gives the mixed-direction secondary index. */
    private static final String RECENT_INDEX_NAME = "idx_auth_fraud_card_recent";

    /** The card whose history every case writes, sixteen characters as the column declares. */
    private static final String CARD_NUMBER = "4000000000000007";

    /** A second card, so a card-prefixed read can be shown not to cross into another card. */
    private static final String OTHER_CARD_NUMBER = "4000000000000015";

    /** The earlier of the two authorizations written for {@link #CARD_NUMBER}. */
    private static final LocalDateTime EARLIER_AUTH_TS =
            LocalDateTime.of(2022, 3, 14, 9, 15, 30, 123_456_000);

    /** The later of the two authorizations written for {@link #CARD_NUMBER}. */
    private static final LocalDateTime LATER_AUTH_TS =
            LocalDateTime.of(2022, 3, 14, 21, 45, 5, 654_321_000);

    /** The report-fraud indicator, one of the two the closed domain admits. */
    private static final String REPORTED = "F";

    /** The resolved indicator, the other member of that domain and the replace path's target. */
    private static final String RESOLVED = "R";

    /** The account the written rows name, carried as a plain column rather than a foreign key. */
    private static final long ACCOUNT_ID = 10_000_000_001L;

    /** The customer the written rows name, likewise a plain column. */
    private static final long CUSTOMER_ID = 451L;

    /** The transaction amount written, twelve-digit precision as the column declares. */
    private static final BigDecimal TRANSACTION_AMOUNT = new BigDecimal("1234.56");

    /** The approved amount written, deliberately different from the transaction amount. */
    private static final BigDecimal APPROVED_AMOUNT = new BigDecimal("1200.00");

    /** The insert every case drives, naming every column a row needs. */
    private static final String INSERT_FRAUD_ROW = """
            INSERT INTO auth_fraud (
                card_num, auth_ts, auth_type, card_expiry_date, message_type, message_source,
                auth_id_code, auth_resp_code, auth_resp_reason, processing_code, transaction_amt,
                approved_amt, merchant_category_code, acqr_country_code, pos_entry_mode,
                merchant_id, merchant_name, merchant_city, merchant_state, merchant_zip,
                transaction_id, match_status, auth_fraud, fraud_rpt_date, acct_id, cust_id)
            VALUES (?, ?, 'AUTH', '2612', 'MSG001', 'SRC001', 'ID0001', '00', '0000', 'PROC01',
                ?, ?, '5411', '840', 1, 'MERCHANT0000001', 'ACME HARDWARE', 'SPRINGFIELD',
                'IL', '627010000', ?, 'M', ?, ?, ?, ?)
            """;

    /** The catalogue question that settles which columns the primary key names, in order. */
    private static final String PRIMARY_KEY_COLUMNS = """
            SELECT kcu.column_name
              FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON kcu.constraint_schema = tc.constraint_schema
               AND kcu.constraint_name = tc.constraint_name
             WHERE tc.table_schema = ?
               AND tc.table_name = ?
               AND tc.constraint_type = 'PRIMARY KEY'
             ORDER BY kcu.ordinal_position
            """;

    /** The catalogue question that settles what the primary-key constraint is called. */
    private static final String PRIMARY_KEY_CONSTRAINT_NAME = """
            SELECT tc.constraint_name
              FROM information_schema.table_constraints tc
             WHERE tc.table_schema = ?
               AND tc.table_name = ?
               AND tc.constraint_type = 'PRIMARY KEY'
            """;

    /** The catalogue question that returns one index's own definition text. */
    private static final String INDEX_DEFINITION = """
            SELECT indexdef
              FROM pg_indexes
             WHERE schemaname = ?
               AND tablename = ?
               AND indexname = ?
            """;

    /** The catalogue question that enumerates every index object on the table. */
    private static final String INDEX_NAMES = """
            SELECT indexname
              FROM pg_indexes
             WHERE schemaname = ?
               AND tablename = ?
             ORDER BY indexname
            """;

    /** Every column of one row, so a replace can be compared against the row it replaced. */
    private static final String SELECT_WHOLE_ROW =
            "SELECT * FROM auth_fraud WHERE card_num = ? AND auth_ts = ?";

    /** The pool every persistence context and every direct read in this class draws from. */
    private static HikariDataSource dataSource;

    /** The single-entity persistence unit this class assembles, held for teardown. */
    private static LocalContainerEntityManagerFactoryBean persistenceUnit;

    /** The factory every persistence context in this class is created from. */
    private static EntityManagerFactory entityManagerFactory;

    /**
     * Applies the module's own migration, then assembles the one-entity persistence unit.
     *
     * <p>Assumptions: the order is not interchangeable. The migration runs first so the table exists
     * before any persistence context opens, and automatic definition emission is off, so nothing else
     * would create it. Schema creation is enabled for the migration although the deployed
     * configuration disables it, because deployment relies on the bootstrap that owns the eight
     * schemas having run first and a bare container has had no bootstrap.</p>
     *
     * <p>Trade-offs: a migration failure is deliberately not caught. It aborts the class before any
     * case runs, which reports the migration as the cause instead of letting every case fail on a
     * missing table and leaving a reader to infer why.</p>
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
        dataSource.setMaximumPoolSize(4);

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        // WHY : Assumptions: the provider is told to emit no definition, which restates the deployed
        //       profile's own setting rather than relying on a default. Emitting one would replace the
        //       migrated table -- and with it the mixed-direction index this class reads -- with a
        //       table derived from entity metadata, on which the index simply would not exist.
        adapter.setGenerateDdl(false);

        persistenceUnit = new LocalContainerEntityManagerFactoryBean();
        persistenceUnit.setDataSource(dataSource);
        persistenceUnit.setPersistenceUnitName("carddemo-authorization-fraud-it");
        persistenceUnit.setPackagesToScan(AuthFraud.class.getPackageName());
        persistenceUnit.setJpaVendorAdapter(adapter);
        // WHY : Assumptions: no default schema is handed to the provider, mirroring the deployed
        //       configuration's deliberate omission of one. The pin belongs to the connection, so a
        //       provider-level default would give this schema a second resolution route that could
        //       stay correct while the connection's own pin was wrong.
        persistenceUnit.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        persistenceUnit.afterPropertiesSet();
        entityManagerFactory = persistenceUnit.getObject();
    }

    /**
     * Releases the persistence unit and the pool once every case has run.
     *
     * <p>Assumptions: the container is released by the Testcontainers extension and is deliberately not
     * closed here, whereas the pool and the persistence unit are this class's own and would keep
     * non-daemon threads alive after the last case if they were left open.</p>
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
     * Empties the table before each case.
     *
     * <p>Assumptions: no parent row is created, unlike the sibling detail cases, because
     * {@code auth_fraud} declares no foreign key at all -- it carries {@code acct_id} and
     * {@code cust_id} as plain columns. That is a property of the migration rather than an oversight:
     * a fraud row is a snapshot taken at the moment of a report and must survive the purge of the
     * authorization it describes, which a foreign key would forbid.</p>
     */
    @BeforeEach
    void emptyTheTable() {
        execute("DELETE FROM auth_fraud");
    }

    /**
     * Opens a pooled connection whose search path is already pinned to the migrated schema.
     *
     * @return a live connection, which the caller closes
     * @throws SQLException if the pool cannot supply a connection
     */
    private static Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Runs one statement that is expected to succeed.
     *
     * @param sql the statement to run
     * @throws IllegalStateException if the statement fails, because every caller here is setup and a
     *     setup failure must abort rather than be mistaken for a property under assertion
     */
    private static void execute(String sql) {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException("setup statement failed: " + sql, failure);
        }
    }

    /**
     * Writes one fraud row directly, bypassing the entity, so a replace has something to replace.
     *
     * @param cardNum the sixteen-character card the row belongs to
     * @param authTs the authorization instant that completes the key
     * @param fraudState the indicator to record
     * @param reportDate the report date to record
     * @throws IllegalStateException if the insert fails, which is a setup fault
     */
    private static void insertRow(String cardNum, LocalDateTime authTs, String fraudState,
            LocalDate reportDate) {
        try (Connection connection = connection();
                PreparedStatement insert = connection.prepareStatement(INSERT_FRAUD_ROW)) {
            insert.setString(1, cardNum);
            insert.setObject(2, authTs);
            insert.setBigDecimal(3, TRANSACTION_AMOUNT);
            insert.setBigDecimal(4, APPROVED_AMOUNT);
            insert.setString(5, "TRAN0000000000" + (authTs.getHour() < 12 ? "1" : "2"));
            insert.setString(6, fraudState);
            insert.setObject(7, reportDate);
            insert.setLong(8, ACCOUNT_ID);
            insert.setLong(9, CUSTOMER_ID);
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("could not seed a fraud row", failure);
        }
    }

    /**
     * Reads one row as a column-name-to-value map, so two readings can be compared whole.
     *
     * @param cardNum the card half of the key
     * @param authTs the timestamp half of the key
     * @return every column of that row, keyed by column name in catalogue order
     * @throws SQLException if the read fails, which is a setup fault rather than the property asserted
     * @throws AssertionError if the row is absent, because every caller has just written it
     */
    private static Map<String, Object> readWholeRow(String cardNum, LocalDateTime authTs)
            throws SQLException {
        try (Connection connection = connection();
                PreparedStatement read = connection.prepareStatement(SELECT_WHOLE_ROW)) {
            read.setString(1, cardNum);
            read.setObject(2, authTs);
            try (ResultSet rows = read.executeQuery()) {
                assertThat(rows.next()).as("a row for %s at %s", cardNum, authTs).isTrue();
                ResultSetMetaData shape = rows.getMetaData();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 1; column <= shape.getColumnCount(); column++) {
                    row.put(shape.getColumnName(column), rows.getObject(column));
                }
                return row;
            }
        }
    }

    /**
     * Runs one single-column catalogue query and collects its rows in the order returned.
     *
     * @param sql the query to run
     * @param arguments the bind values, in order
     * @return the single column of every row returned, in order
     * @throws SQLException if the query fails, which is a setup fault
     */
    private static List<String> queryColumn(String sql, String... arguments) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement query = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                query.setString(index + 1, arguments[index]);
            }
            try (ResultSet rows = query.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
                return values;
            }
        }
    }

    /**
     * Runs one unit of work against the repository inside a committed transaction.
     *
     * <p>Assumptions: the context is created per call and closed in a {@code finally}, which is the
     * arrangement {@code PendingAuthSummaryRepositoryIT} established in this package. A context shared
     * across cases would carry an identity map between them, so a later case could read an entity the
     * engine never received.</p>
     *
     * @param <R> the result type the work produces
     * @param work what to do with the repository; run once
     * @return whatever the work returned
     * @throws RuntimeException if the work or the commit fails, rethrown after the rollback so a
     *     refusal assertion can read the provider's own exception chain
     */
    private static <R> R inTransaction(Function<AuthFraudRepository, R> work) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            AuthFraudRepository repository =
                    new JpaRepositoryFactory(entityManager).getRepository(AuthFraudRepository.class);
            entityManager.getTransaction().begin();
            try {
                R result = work.apply(repository);
                entityManager.flush();
                entityManager.getTransaction().commit();
                return result;
            } catch (RuntimeException failure) {
                if (entityManager.getTransaction().isActive()) {
                    entityManager.getTransaction().rollback();
                }
                throw failure;
            }
        } finally {
            entityManager.close();
        }
    }

    /**
     * Runs one read against the repository through a context that has written nothing.
     *
     * <p>Assumptions: a fresh context is the whole point. A context that had written the row would
     * answer from its identity map, so the read would pass on an entity that never reached the
     * engine.</p>
     *
     * @param <R> the result type the read produces
     * @param read what to read; run once
     * @return whatever the read returned
     */
    private static <R> R readThrough(Function<AuthFraudRepository, R> read) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return read.apply(
                    new JpaRepositoryFactory(entityManager).getRepository(AuthFraudRepository.class));
        } finally {
            entityManager.close();
        }
    }

    /**
     * Confirms the primary key is the ordered composite the migration declares, under its own name.
     *
     * <p>Assumptions: F1 as recorded on this class. The live catalogue is interrogated rather than the
     * migration text, because the text is the INPUT to this schema and reading it back would assert
     * only that a file says what it says. Ordinal position is what makes the result a key SHAPE rather
     * than an unordered set, so a key declared the other way round could not pass by returning its
     * columns in a convenient order.</p>
     *
     * @throws SQLException if the container refuses a connection or either catalogue query fails,
     *     which is a setup fault rather than the property under test and must surface as itself
     */
    @Test
    @DisplayName("pk_auth_fraud is the composite (card_num, auth_ts), in that order")
    void thePrimaryKeyIsTheOrderedCompositeOfCardAndTimestamp() throws SQLException {
        assertThat(queryColumn(PRIMARY_KEY_COLUMNS, SCHEMA_NAME, TABLE))
                .as("the primary key columns of %s.%s in ordinal order", SCHEMA_NAME, TABLE)
                .containsExactly("card_num", "auth_ts");
        assertThat(queryColumn(PRIMARY_KEY_CONSTRAINT_NAME, SCHEMA_NAME, TABLE))
                .as("the name the engine holds for that key")
                .containsExactly(PRIMARY_KEY_NAME);
    }

    /**
     * Confirms the fraud access path is TWO catalogue objects and not one.
     *
     * <p>Assumptions: F2 as recorded on this class. The set of index objects is read whole rather than
     * looked up by name, because the property is that the secondary index exists ALONGSIDE the key's
     * own index; a name lookup would pass on a schema where the two had been merged into one object
     * carrying the wanted name.</p>
     *
     * @throws SQLException if the catalogue query fails, which is a setup fault
     */
    @Test
    @DisplayName("the fraud access path is two catalogue objects: the key's index and the recent index")
    void theFraudAccessPathIsTwoCatalogueObjects() throws SQLException {
        assertThat(queryColumn(INDEX_NAMES, SCHEMA_NAME, TABLE))
                .as("every index object on %s.%s", SCHEMA_NAME, TABLE)
                .containsExactly(RECENT_INDEX_NAME, PRIMARY_KEY_NAME);
    }

    /**
     * Confirms the recent index descends on the timestamp, read from the engine's own definition.
     *
     * <p>Assumptions: F3 as recorded on this class. The definition text is the assertion subject
     * because an index created over the same two columns with no direction modifiers would carry the
     * same name, occupy the same catalogue row, and satisfy every check that merely looked the name
     * up. The ascending form is asserted ABSENT as well as the descending form present, so a
     * definition carrying both -- which cannot happen, but which a laxer assertion would tolerate --
     * still fails.</p>
     *
     * @throws SQLException if the catalogue query fails, which is a setup fault
     */
    @Test
    @DisplayName("idx_auth_fraud_card_recent descends on auth_ts, per the engine's own definition")
    void theRecentIndexDescendsOnTheTimestamp() throws SQLException {
        List<String> definitions =
                queryColumn(INDEX_DEFINITION, SCHEMA_NAME, TABLE, RECENT_INDEX_NAME);
        assertThat(definitions).as("the definition of %s", RECENT_INDEX_NAME).hasSize(1);
        String definition = definitions.get(0);
        assertThat(definition)
                .as("the engine's rendering of %s, which must show the descending component",
                        RECENT_INDEX_NAME)
                .contains("card_num")
                .contains("auth_ts DESC");
        assertThat(definition)
                .as("an all-ascending index would satisfy a name check and must not satisfy this one")
                .doesNotContain("auth_ts)")
                .doesNotContain("auth_ts ASC");
    }

    /**
     * Confirms the insert direction of the upsert: an absent row is created and is then addressable.
     *
     * <p>Assumptions: the row is written by a direct insert rather than through the entity, and is then
     * read back through a persistence context that has written nothing. Writing it through the entity
     * would make the read answerable from that context's own identity map, so the case could pass on a
     * row that never reached the engine -- and the property under assertion here is that the composite
     * key ADDRESSES a stored row, which only a context that did not write it can establish.</p>
     */
    @Test
    @DisplayName("the upsert's insert direction creates the row and it is addressable by its key")
    void theInsertDirectionCreatesAnAddressableRow() {
        LocalDate reportDate = readThrough(AuthFraudRepository::currentDate);
        AuthFraudKey key = new AuthFraudKey(CARD_NUMBER, LATER_AUTH_TS);
        // WHY : Assumptions: the result is bound to a typed local before it is asserted on. Handing the
        //       generic helper's result straight to the matcher leaves the type variable to be inferred
        //       from an overload set that includes a predicate form, and the compiler then reports an
        //       ambiguity rather than the type mismatch a reader would expect.
        Optional<AuthFraud> beforeInsert = readThrough(repository -> repository.findById(key));
        assertThat(beforeInsert)
                .as("no row may exist before the insert direction runs")
                .isEmpty();

        insertRow(CARD_NUMBER, LATER_AUTH_TS, REPORTED, reportDate);

        Optional<AuthFraud> found = readThrough(repository -> repository.findById(key));
        assertThat(found).as("the inserted row, addressed by its composite key").isPresent();
        AuthFraud row = found.orElseThrow();
        assertThat(row.getAuthFraud()).as("the indicator the insert recorded").isEqualTo(REPORTED);
        assertThat(row.getFraudRptDate()).as("the report date the insert recorded")
                .isEqualTo(reportDate);
        assertThat(row.getAcctId()).as("the account the row names").isEqualTo(ACCOUNT_ID);
        assertThat(row.getCustId()).as("the customer the row names").isEqualTo(CUSTOMER_ID);
    }

    /**
     * Confirms the replace direction touches the two columns the reference update names and no other.
     *
     * <p>Assumptions: F4 as recorded on this class. The whole row is captured before and after, and the
     * two columns that are permitted to move are removed from both captures before they are compared.
     * Comparing what is LEFT is what makes the assertion able to fail on a column nobody thought
     * about; asserting the two movers directly would pass on a replace that also cleared the merchant
     * name.</p>
     *
     * @throws SQLException if either whole-row read fails, which is a setup fault
     */
    @Test
    @DisplayName("the upsert's replace direction moves auth_fraud and fraud_rpt_date and nothing else")
    void theReplaceDirectionLeavesEveryOtherColumnAsItWas() throws SQLException {
        LocalDate firstReport = LocalDate.of(2022, 3, 15);
        insertRow(CARD_NUMBER, LATER_AUTH_TS, REPORTED, firstReport);
        Map<String, Object> before = readWholeRow(CARD_NUMBER, LATER_AUTH_TS);

        LocalDate secondReport = readThrough(AuthFraudRepository::currentDate);
        inTransaction(repository -> {
            AuthFraud row = repository
                    .findById(new AuthFraudKey(CARD_NUMBER, LATER_AUTH_TS))
                    .orElseThrow();
            row.applyState(RESOLVED, secondReport);
            return row;
        });

        Map<String, Object> after = readWholeRow(CARD_NUMBER, LATER_AUTH_TS);
        assertThat(after.get("auth_fraud")).as("the indicator the replace records").isEqualTo(RESOLVED);
        assertThat(String.valueOf(after.get("fraud_rpt_date")))
                .as("the report date the replace records")
                .isEqualTo(secondReport.toString());
        assertThat(secondReport)
                .as("the second report is dated on or after the first, so the dates are comparable")
                .isAfterOrEqualTo(firstReport);

        Map<String, Object> untouchedBefore = new LinkedHashMap<>(before);
        Map<String, Object> untouchedAfter = new LinkedHashMap<>(after);
        untouchedBefore.remove("auth_fraud");
        untouchedBefore.remove("fraud_rpt_date");
        untouchedAfter.remove("auth_fraud");
        untouchedAfter.remove("fraud_rpt_date");
        assertThat(untouchedAfter)
                .as("every column the reference update does not name must survive the replace")
                .isEqualTo(untouchedBefore);
        assertThat(untouchedAfter)
                .as("the surviving columns are the snapshot itself, so there must be many of them")
                .hasSize(before.size() - 2);
    }

    /**
     * Confirms the card-recent access path reads newest first and does not cross into another card.
     *
     * <p>Assumptions: the two properties are asserted in one case because neither means much alone. An
     * ordering assertion over one card's rows would pass on a read with no card predicate at all, and
     * a card-isolation assertion would pass on a read that returned the oldest row first. The other
     * card's row is dated BETWEEN this card's two, so a read that ignored the predicate would return
     * it in the middle of the result and fail on order as well as on membership.</p>
     *
     * <p>Refactoring Rationale: the access path is exercised by a STATEMENT here rather than through a
     * repository method, because this interface deliberately publishes no card-history reader. That
     * withdrawal is argued on the interface itself -- an uncalled query whose first parameter is an
     * unmasked primary account number is a standing invitation to build a fraud-history route by
     * calling it -- and the same paragraph directs that the obligation be asserted against the schema
     * rather than against a Java method that may not exist. What the parity requirement actually names
     * is the INDEX, and this case shows that the index answers the question it was declared for.</p>
     *
     * @throws SQLException if the catalogue or the table cannot be read, which fails the case rather
     *     than skipping it
     */
    @Test
    @DisplayName("the card-recent index answers newest first and only for the card asked about")
    void theCardHistoryReadsNewestFirstAndIsCardIsolated() throws SQLException {
        LocalDate reportDate = LocalDate.of(2022, 3, 16);
        insertRow(CARD_NUMBER, EARLIER_AUTH_TS, REPORTED, reportDate);
        insertRow(CARD_NUMBER, LATER_AUTH_TS, RESOLVED, reportDate);
        insertRow(OTHER_CARD_NUMBER, LocalDateTime.of(2022, 3, 14, 15, 0, 0), REPORTED, reportDate);

        List<String> history = queryColumn(
                "SELECT auth_ts FROM auth_fraud WHERE card_num = ? ORDER BY auth_ts DESC",
                CARD_NUMBER);

        assertThat(history)
                .as("the whole history of the card under test, newest first, and nothing else")
                .containsExactly(LATER_AUTH_TS.toString().replace('T', ' '),
                        EARLIER_AUTH_TS.toString().replace('T', ' '));
    }

    /**
     * Confirms the report date comes from the engine and not from this process.
     *
     * <p>Assumptions: the repository's own value is compared with the engine's {@code CURRENT_DATE}
     * read over a separate connection, not with {@code LocalDate.now()}. Comparing against this
     * process's clock is the assertion that would pass while the property was broken, because the two
     * agree whenever the container and the runner happen to share a zone -- which is most of the time
     * and never at the boundary where the difference matters.</p>
     *
     * @throws SQLException if the direct read fails, which is a setup fault
     */
    @Test
    @DisplayName("currentDate() returns the engine's date, not this process's")
    void theReportDateComesFromTheEngine() throws SQLException {
        List<String> engineDate = queryColumn("SELECT CURRENT_DATE::text");
        assertThat(engineDate).as("the engine's own current date").hasSize(1);
        assertThat(readThrough(AuthFraudRepository::currentDate))
                .as("the date a fraud report is stamped with")
                .isEqualTo(LocalDate.parse(engineDate.get(0)));
    }
}
