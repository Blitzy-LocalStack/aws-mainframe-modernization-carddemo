package com.carddemo.authorization.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the persistence contract of the pending-authorization DETAIL table against a real engine.
 *
 * <p>Purpose: this class asserts the five things about {@code pending_auth_detail} that only a
 * running PostgreSQL instance can answer, plus the one behavioural contract that belongs to the
 * repository rather than to the schema. The schema half is the composition of
 * {@code pk_pending_auth_detail}; the two key-domain checks
 * {@code ck_pending_auth_detail_auth_date_domain} and
 * {@code ck_pending_auth_detail_auth_time_domain}; the two reply-code checks
 * {@code ck_pending_auth_detail_auth_resp_code} and
 * {@code ck_pending_auth_detail_auth_resp_reason}; that
 * {@code fk_pending_auth_detail_summary} makes a detail row unreachable without its summary; and the
 * column-level storage contracts that a mapping error would otherwise carry silently into
 * production. The behavioural half is the look-ahead row a paging query is required to return. The
 * package charter beside this file carries the rulings this class conforms to and does not restate.
 *
 * <p>Assumptions: three constraints a reader auditing this file for complete constraint coverage
 * will look for are absent DELIBERATELY, because the package charter assigns them elsewhere and its
 * non-duplication rule forbids a second assertion of one contract.
 * {@code ck_pending_auth_detail_auth_fraud},
 * {@code ck_pending_auth_detail_match_status} and
 * {@code uq_pending_auth_detail_card_transaction} are all proved by
 * {@code com.carddemo.authorization.fixtures.PendingAuthFraudDomainRepositoryIT}, which reaches each
 * of them by name. The two intentionally invalid images that drive those proofs,
 * {@code pautdtl1-match-status-invalid.bin} and {@code pautdtl1-auth-fraud-invalid.bin}, are for the
 * same reason not loaded here at all; neither could serve a purpose in this file without either
 * duplicating that class or being neutralised into a contrived one. They are named rather than
 * silently skipped so that the omission reads as a boundary and not as a gap.
 *
 * <p>Assumptions: the three domains those constraints carry are nevertheless recorded here, because
 * this file is where a reader arrives looking for them and because a constraint that transcribes a
 * closed condition-name set needs that provenance stated wherever it is discussed. Each is a
 * PROMOTION of a copybook condition-name set into the schema and not a port of an existing database
 * constraint: {@code app/app-authorization-ims-db2-mq/ddl/AUTHFRDS.ddl} L23 and L24 declare
 * {@code MATCH_STATUS} and {@code AUTH_FRAUD} as bare single-character columns with no check of any
 * kind, and {@code dcl/AUTHFRDS.dcl} L46 and L47 agree. The match-status domain is the four
 * condition names at {@code cpy/CIPAUDTY.cpy} L46 to L49 -- {@code PA-MATCH-PENDING} 'P',
 * {@code PA-MATCH-AUTH-DECLINED} 'D', {@code PA-MATCH-PENDING-EXPIRED} 'E' and
 * {@code PA-MATCHED-WITH-TRAN} 'M'. Only two of the four can arise at creation, because
 * {@code cbl/COPAUA0C.cbl} L902 to L906 sets 'P' when the reply approved and 'D' otherwise with no
 * third branch; 'E' arrives later from the expiry sweep and 'M' from transaction posting, so the
 * domain has to admit four values that no single write path produces. The fraud domain is the two
 * condition names at {@code cpy/CIPAUDTY.cpy} L51 and L52, 'F' and 'R', WIDENED to admit a blank --
 * and the widening is load-bearing rather than lenient, because {@code cbl/COPAUA0C.cbl} L908 and
 * L909 blank that field unconditionally on the straight-line path immediately before the insert at
 * L913 to L919, so a domain refusing a blank would refuse every row the reference system creates.
 *
 * <p>Trade-offs: a real engine in a container is used rather than an in-memory database, at the cost
 * of container start-up on every run and of a container runtime the host must provide. Every
 * property this class asserts is specific to the target engine -- a check constraint carrying a
 * modular decomposition, a fixed-width character column that pads on read while its one varying
 * neighbour does not, and a foreign key that cascades -- so an in-memory substitute would either
 * reject the migration outright or accept it with different semantics, and a green run against it
 * would establish nothing about the schema that is deployed.
 *
 * <p>Assumptions: this class asserts NO update method and NO transaction boundary, and both absences
 * are contracts rather than omissions. {@code PendingAuthDetailRepository} declares no update and no
 * bulk delete; the fraud state is applied by mutating a re-read managed row inside the SERVICE's
 * transaction, so a boundary asserted here would be asserting the service's. The reference system
 * writes the same change two different widths at once, which is why the target normalises rather
 * than copies: against the hierarchical store it is a whole-segment replace naming the segment with
 * no field list, at {@code cbl/COPAUS1C.cbl} L520 to L528, while against the relational store it is
 * a two-column update, at {@code cbl/COPAUS2C.cbl} L222 to L229. The target keeps the narrow shape
 * on both sides and the divergence is registered in the traceability document.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PendingAuthDetailRepositoryIT.PersistenceContext.class)
@DisplayName("pending_auth_detail: composite key, key domains, reply-code domains, parentage, paging")
class PendingAuthDetailRepositoryIT {

    /** Digest-pinned engine image, matching the one the sibling container-backed class uses. */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /** The schema name as a BARE identifier, for the migration engine's own two settings. */
    private static final String SCHEMA_NAME = "authorization";

    // WHY : Assumptions: the same word appears twice in this file in two forms that are not
    //       interchangeable. In SQL TEXT it must be quoted, because `authorization` is a reserved
    //       word and an unquoted occurrence is a syntax error; in a migration-engine PROPERTY it must
    //       be bare, because that engine quotes an identifier itself and a value carrying quote
    //       characters would name a schema whose name contains them. Both forms are declared so that
    //       neither site has to reason about the distinction at the point of use.
    /** The schema name as a QUOTED identifier, for interpolation into SQL text. */
    private static final String SCHEMA = "\"" + SCHEMA_NAME + "\"";

    /** Logical layout name of the 200-byte detail segment in the shared copybook registry. */
    private static final String LAYOUT = "PAUTDTL";

    /** Declared length of one detail segment image, per the database descriptor. */
    private static final int SEGMENT_LENGTH = 200;

    /** Stride of one record in the account-prefixed unload form. */
    private static final int PREFIXED_RECORD_LENGTH = 206;

    /** Width of the account prefix, derived rather than restated so the two lengths cannot drift. */
    private static final int ACCOUNT_PREFIX_WIDTH = PREFIXED_RECORD_LENGTH - SEGMENT_LENGTH;

    /** Zero-based offset of the trailing filler in the detail segment. */
    private static final int FILLER_OFFSET = 183;

    /** Declared width of the trailing filler. */
    private static final int FILLER_WIDTH = 17;

    /** All-nines constant the reference system subtracts the Julian date from. */
    private static final int DATE_COMPLEMENT_BASE = 99_999;

    /** All-nines constant the reference system subtracts the millisecond time from. */
    private static final int TIME_COMPLEMENT_BASE = 999_999_999;

    /** Engine state for a violated check constraint. */
    private static final String CHECK_VIOLATION = "23514";

    /** Engine state for a violated foreign-key constraint. */
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    /** Engine state for a required column left unsupplied. */
    private static final String NOT_NULL_VIOLATION = "23502";

    /** Name of the primary key under test, exactly as the migration declares it. */
    private static final String PRIMARY_KEY = "pk_pending_auth_detail";

    /** Name of the check bounding the Julian date component of the key. */
    private static final String AUTH_DATE_CHECK = "ck_pending_auth_detail_auth_date_domain";

    /** Name of the check bounding the millisecond time component of the key. */
    private static final String AUTH_TIME_CHECK = "ck_pending_auth_detail_auth_time_domain";

    /** Name of the check closing the reply response-code domain. */
    private static final String RESP_CODE_CHECK = "ck_pending_auth_detail_auth_resp_code";

    /** Name of the check closing the reply reason-code domain. */
    private static final String RESP_REASON_CHECK = "ck_pending_auth_detail_auth_resp_reason";

    /** Name of the foreign key that makes a detail row unreachable without its summary. */
    private static final String SUMMARY_FOREIGN_KEY = "fk_pending_auth_detail_summary";

    /** The reference 200-byte detail record, used for the column-by-column assertions. */
    private static final String CANONICAL = "fixtures/pautdtl1-canonical.bin";

    /** Two children either side of a year boundary, loaded beneath the recorded parent summary. */
    private static final String NEWYEAR_PAIR = "fixtures/pautdtl1-newyear-pair.bin";

    /** The recorded parent summary the year-boundary children belong to. */
    private static final String PURGE_PARENT = "fixtures/pautsum0-purge-parent.bin";

    /** Three same-day children whose file order is deliberately not chronological. */
    private static final String ORDER_SAME_DAY = "fixtures/pautdtl1-order-same-day-times.bin";

    /** A key whose time complement is all high-order nines, decoding to a four-digit value. */
    private static final String TIME_LEADING_NINES = "fixtures/pautdtl1-time-leading-nines.bin";

    /** A key whose uninverted form is an ordinary-looking nine-digit number. */
    private static final String COMPLEMENT_TRAP = "fixtures/pautdtl1-raw-complement-trap.bin";

    /** Four valid children with distinct keys, used as this class's paging dataset. */
    private static final String FOUR_CHILDREN = "fixtures/pautdtl1-match-status-domain.bin";

    /** Three children carrying the three imageable report-date states. */
    private static final String REPORT_DATE_STATES = "fixtures/pautdtl1-auth-fraud-domain.bin";

    /** A merchant name of seven characters followed by fifteen blanks. */
    private static final String MERCHANT_NO_TRIM = "fixtures/pautdtl1-merchant-name-notrim.bin";

    /** The widest amount the packed money declaration admits. */
    private static final String WIDEST_AMOUNT = "fixtures/pautdtl1-amount-ten-integer-digits.bin";

    /** Two records straddling the two-digit-year pivot. */
    private static final String DATE_FORMATS = "fixtures/pautdtl1-date-formats.bin";

    /** The unload form that carries an account prefix ahead of each segment. */
    private static final String PREFIXED_UNLOAD = "fixtures/unload-prefixed-detail-206.bin";

    /** The unload form that carries the bare segment and nothing else. */
    private static final String GSAM_UNLOAD = "fixtures/unload-gsam-detail-200.bin";

    // WHY : Assumptions: every method below uses its OWN account identifier, so no method depends on
    //       a row another inserted and none has to run after another. The charter requires distinct
    //       key values per method; distinct ACCOUNTS is the strongest form of that, because the
    //       account is the leading key column, so two methods cannot collide even if they reuse a
    //       date and time. The two identifiers that are not free to choose are named separately
    //       below, because a recorded image fixes them.
    /** Account used by the column-by-column assertions over the reference record. */
    private static final long CANONICAL_ACCOUNT = 10_000_000_201L;

    /** Account used by the ordering, keyset and look-ahead assertions. */
    private static final long PAGING_ACCOUNT = 10_000_000_202L;

    /** Account used by the two decoded-key assertions. */
    private static final long DECODED_KEY_ACCOUNT = 10_000_000_203L;

    /** Account used by the millisecond-resolution assertion. */
    private static final long MILLISECOND_ACCOUNT = 10_000_000_204L;

    /** Account used by the wide-money assertion. */
    private static final long MONEY_ACCOUNT = 10_000_000_205L;

    /** Account used by the no-trim assertion. */
    private static final long NO_TRIM_ACCOUNT = 10_000_000_206L;

    /** Account used by the reply-code acceptance and refusal assertions. */
    private static final long REPLY_CODE_ACCOUNT = 10_000_000_207L;

    /** Account used by the key-domain refusal assertions. */
    private static final long KEY_DOMAIN_ACCOUNT = 10_000_000_208L;

    /** Account used by the report-date storage assertion. */
    private static final long REPORT_DATE_ACCOUNT = 10_000_000_209L;

    /** Account used by the pivot-pair and expiry assertions. */
    private static final long PIVOT_ACCOUNT = 10_000_000_210L;

    // WHY : Assumptions: this identifier is NOT free to choose. It is the account the recorded
    //       parent summary image carries, and the year-boundary children are documented as pairing
    //       with that parent, so the pairing is read out of the image rather than restated as a
    //       literal -- which is what makes the pairing itself assertable.
    /** Account deliberately absent from the summary table, for the parentage refusal. */
    private static final long ORPHAN_ACCOUNT = 10_000_000_299L;

    /** Customer identifier attached to every parent summary this class inserts. */
    private static final long CUSTOMER_ID = 451L;

    /**
     * The container this class owns, started before any Spring context can ask it for a connection.
     *
     * <p>Assumptions: the image is pinned by DIGEST rather than by tag, so the schema these
     * assertions run against cannot change underneath them when a tag is republished. A tag is
     * mutable and a digest is not, and every constraint asserted below is engine behaviour.
     */
    private static final PostgreSQLContainer POSTGRES;

    // WHY : Alternatives Considered: the container is started HERE, in a static initialiser, rather
    //       than through the container library's JUnit integration. That integration starts a
    //       container from its own before-all callback, and the Spring test extension builds the
    //       application context from a before-all callback of its own; the relative order of two
    //       extensions' callbacks is not something this class can state, so the data source bean
    //       could be asked to connect to a container that has not started. A static initialiser runs
    //       at class load, which is strictly before either callback, so the ordering question does
    //       not arise. The accepted cost is that the container is not stopped by the library's
    //       lifecycle and is reaped by its resource-reaper container instead.
    // WHY : Assumptions: the migration is applied here too, in the same initialiser and before the
    //       context exists, because the entity metadata is mapped to tables this migration creates
    //       and nothing else creates them.
    static {
        POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);
        POSTGRES.start();

        // WHY : Assumptions: Flyway is TWO artifacts here and not one. The parent build pins
        //       flyway-core and flyway-database-postgresql to a single version, and the companion is
        //       not redundant beside the core: from Flyway 10 onward the engine support moved out of
        //       core, so core alone resolves and compiles and then fails at RUN time on the first
        //       migration. The failure appears only once a container is already up, which is why the
        //       pairing is worth a note at the one place that triggers it.
        // WHY : Assumptions: schema creation is enabled and the schema name is BARE in these two
        //       settings, matching the test profile. Flyway quotes an identifier itself, so a value
        //       carrying quote characters would name a schema whose name contains them; the opposite
        //       requirement applies to the search-path statement below, which is SQL text in which
        //       the word must stay quoted because it is reserved.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA_NAME)
                .defaultSchema(SCHEMA_NAME)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /**
     * Supplies the persistence beans this class needs, and nothing else.
     *
     * <p>Purpose: this configuration exists so the assertions below can drive the REAL
     * {@code PendingAuthDetailRepository} -- its declared query text, its ordering and its row limit
     * -- rather than a hand-written statement that merely resembles it.
     *
     * <p>Alternatives Considered: starting the module's own application context, which would have
     * supplied the same repository through auto-configuration. Rejected on two independent grounds.
     * The context would additionally stand up the security filter chain, which resolves its token
     * issuer eagerly while the context is built, and the queue client, whose region and queue
     * references the test profile deliberately does not supply -- so a persistence assertion would
     * fail on an identity provider being unreachable, and the failure would name neither the table
     * nor the query. Separately, the two narrower mechanisms that would normally avoid that are not
     * available to this module: neither a persistence test slice nor the container service-connection
     * support is on its test classpath, so there is nothing to narrow the context WITH. Declaring the
     * three beans directly is what remains, and it has the incidental merit that the whole of what
     * this class depends on is visible in one place.
     */
    @Configuration
    @EnableJpaRepositories(basePackages = "com.carddemo.authorization.repository")
    static class PersistenceContext {

        /**
         * Builds the pooled data source every bean below and every raw statement here shares.
         *
         * <p>Assumptions: the connection initialisation statement pins the search path to this
         * context's schema, which is the same mechanism the deployed configuration uses, and the
         * schema name is QUOTED because it is a reserved word in SQL. Pinning it rather than
         * qualifying each table is what lets the entity mappings and the statements in this file
         * name their tables unqualified, so a removed pin fails loudly instead of being masked by
         * qualification that would pass either way.
         *
         * @return a pool bound to this class's container, whose connections resolve unqualified
         *     names in the authorization schema
         */
        @Bean
        DataSource dataSource() {
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl(POSTGRES.getJdbcUrl());
            pool.setUsername(POSTGRES.getUsername());
            pool.setPassword(POSTGRES.getPassword());
            pool.setConnectionInitSql("SET search_path TO " + SCHEMA);

            // WHY : Trade-offs: a small pool, because this class runs one statement at a time and a
            //       larger one would only hold idle connections open against a container that is
            //       discarded at the end of the run.
            pool.setMaximumPoolSize(4);
            return pool;
        }

        /**
         * Builds the entity manager factory over the entities of this bounded context.
         *
         * <p>Assumptions: schema generation stays OFF, and the setting is the difference between
         * asserting the deployed schema and asserting a different one. Generated definitions are
         * emitted from entity metadata, which carries neither of the two key-domain checks, neither
         * reply-code check nor the cascading foreign key -- so the assertions written to prove those
         * objects exist would run GREEN against a schema that does not contain them. A false pass is
         * strictly worse than a failure, which is why the migration applied above is the only
         * producer of this schema.
         *
         * @param dataSource the pool to map entities over; supplied by {@link #dataSource()}
         * @return a factory scanning only this context's entity package
         */
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory =
                    new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.carddemo.authorization.domain");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.getJpaPropertyMap().put("hibernate.hbm2ddl.auto", "none");
            return factory;
        }

        /**
         * Builds the transaction manager the assertions below drive a template from.
         *
         * @param entityManagerFactory the factory to manage transactions for; supplied by
         *     {@link #entityManagerFactory(DataSource)}
         * @return a transaction manager bound to that factory
         */
        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }

    /** The repository under test, wired from the configuration above. */
    @Autowired
    private PendingAuthDetailRepository repository;

    /** Transaction manager the template below is built from. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Clears both tables so that each method starts from an empty schema.
     *
     * <p>Assumptions: the child is deleted BEFORE the parent, and the order is not cosmetic. The
     * foreign key asserted below cascades from summary to detail, so deleting the parent first would
     * succeed by taking its children with it and would leave this method's own correctness resting on
     * the very constraint one of the assertions below exists to prove. Deleting the child explicitly
     * keeps the reset independent of the thing under test.
     */
    @BeforeEach
    void clearBothTables() {
        execute("DELETE FROM " + SCHEMA + ".pending_auth_detail");
        execute("DELETE FROM " + SCHEMA + ".pending_auth_summary");
    }

    /**
     * Opens a connection whose search path resolves this context's unqualified table names.
     *
     * <p>Assumptions: raw connections are used ALONGSIDE the repository rather than instead of it,
     * and the division is deliberate. A statement issued here is the only way to present a value the
     * mapped types refuse to construct -- the key type validates the same two domains the key checks
     * do, so an out-of-domain date can reach the engine no other way -- and it is also how each
     * refusal gets a transaction of its own.
     *
     * @return an open connection in autocommit mode with the search path pinned
     * @throws SQLException if the container refuses the connection or the search-path statement fails
     */
    private static Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + SCHEMA);
        }
        return connection;
    }

    /**
     * Runs one setup statement, turning any failure into an unchecked setup error.
     *
     * <p>Assumptions: this is for statements that are expected to SUCCEED. A failure here is a broken
     * fixture rather than an assertion outcome, so it is raised as a setup error and never reaches a
     * refusal assertion, which would otherwise be able to pass on the wrong exception.
     *
     * @param sql the complete statement to run; must not be {@code null}
     * @throws IllegalStateException if the statement fails
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
     * Inserts the parent summary a detail row needs before it can exist.
     *
     * <p>Assumptions: only the two identity columns are supplied, because the migration defaults
     * every money column and both counters to zero. Supplying them here would restate defaults this
     * class does not assert and would drift from the migration silently if one changed.
     *
     * @param accountId the account the summary is for; becomes the parent of any detail row keyed on
     *     it
     */
    private static void insertParent(long accountId) {
        execute("INSERT INTO " + SCHEMA + ".pending_auth_summary (account_id, customer_id) VALUES ("
                + accountId + ", " + CUSTOMER_ID + ")");
    }

    /**
     * Reads a recorded byte image from the test classpath.
     *
     * @param name the classpath-relative resource name of the image; must not be {@code null}
     * @return the whole image, exactly as recorded and with no terminator allowance
     * @throws AssertionError if the resource is absent from the test classpath
     * @throws UncheckedIOException if the resource is present but cannot be read
     */
    private static byte[] bytes(String name) {
        try (InputStream stream = PendingAuthDetailRepositoryIT.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (stream == null) {
                throw new AssertionError("fixture " + name + " is not on the test classpath");
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException("fixture " + name + " could not be read", failure);
        }
    }

    /**
     * Slices one record out of a multi-record image at a stated stride.
     *
     * <p>Assumptions: the stride is a PARAMETER rather than the segment length, because two of the
     * images this class loads carry a record longer than the segment. Hard-coding the segment length
     * here would silently mis-slice the account-prefixed form into overlapping windows that still
     * decode, which is the failure mode this signature exists to prevent.
     *
     * @param image the whole recorded image; must not be {@code null}
     * @param ordinal the zero-based index of the wanted record
     * @param stride the number of bytes one record occupies
     * @return a copy of the requested record
     */
    private static byte[] record(byte[] image, int ordinal, int stride) {
        int start = ordinal * stride;
        return Arrays.copyOfRange(image, start, start + stride);
    }

    /**
     * Decodes one 200-byte detail segment through the shared copybook registry.
     *
     * @param segment exactly {@link #SEGMENT_LENGTH} bytes of detail segment; must not be
     *     {@code null}
     * @return every declared field keyed by its copybook name, the trailing filler included
     */
    private static Map<String, Object> decode(byte[] segment) {
        return FixedWidthCodec.decodeRecord(segment, CopybookLayout.layout(LAYOUT));
    }

    /**
     * Returns one character field exactly as recorded, with no trimming of any kind.
     *
     * <p>Assumptions: no trimming, because the declared width is part of the record contract and the
     * trailing blanks of a character field are stored data rather than presentation. A convenience
     * that stripped them here would make the no-trim assertion below unable to fail.
     *
     * @param fields a decoded segment; must not be {@code null}
     * @param name the copybook field name to read; must name a character field of that segment
     * @return the field's characters at their full declared width
     */
    private static String text(Map<String, Object> fields, String name) {
        return String.valueOf(fields.get(name));
    }

    /**
     * Returns one numeric field of a decoded segment as an exact decimal.
     *
     * <p>Assumptions: the value is carried through its string form into {@code BigDecimal} and never
     * through a binary floating-point type. Money in this record is packed decimal and every hop from
     * the image to the column has to stay exact, so the one conversion that could lose a cent is
     * excluded structurally rather than by care.
     *
     * @param fields a decoded segment; must not be {@code null}
     * @param name the copybook field name to read; must name a numeric field of that segment
     * @return the field's value with its declared scale preserved
     */
    private static BigDecimal number(Map<String, Object> fields, String name) {
        return new BigDecimal(String.valueOf(fields.get(name)));
    }

    /**
     * Recovers the real Julian date from the complemented span a segment stores.
     *
     * <p>Assumptions: the stored span is the NINES COMPLEMENT of the real value, and subtracting it
     * from the all-nines constant is the reference system's own inverse. The reference encodes on
     * write and decodes on every read with the same constant, at {@code cbl/COPAUS2C.cbl} L107 for
     * the time and against its own width for the date, so this is a transcription of that arithmetic
     * and not a convention chosen here.
     *
     * @param fields a decoded segment; must not be {@code null}
     * @return the real Julian date in the five-digit form the target column stores
     */
    private static int decodedDate(Map<String, Object> fields) {
        return DATE_COMPLEMENT_BASE - number(fields, "PA-AUTH-DATE-9C").intValueExact();
    }

    /**
     * Recovers the real millisecond time from the complemented span a segment stores.
     *
     * @param fields a decoded segment; must not be {@code null}
     * @return the real time in the nine-digit hour-minute-second-millisecond form the target column
     *     stores
     */
    private static int decodedTime(Map<String, Object> fields) {
        return TIME_COMPLEMENT_BASE - number(fields, "PA-AUTH-TIME-9C").intValueExact();
    }

    /**
     * Renders an unsigned display field back to its full declared width.
     *
     * <p>Assumptions: the codec decodes an unsigned display field to an integral value, which
     * discards leading zeros that the target column is declared wide enough to keep. Re-padding here
     * is what preserves them; passing the decoded number's own string form would store a
     * six-character code as one character followed by blanks, and the column would then hold a value
     * that never existed in the record.
     *
     * @param fields a decoded segment; must not be {@code null}
     * @param name the copybook field name to read; must name an unsigned display field
     * @param width the declared digit count of that field
     * @return the value zero-padded on the left to exactly {@code width} characters
     */
    private static String zeroPadded(Map<String, Object> fields, String name, int width) {
        return String.format("%0" + width + "d", number(fields, name).longValueExact());
    }

    /**
     * Builds a managed entity from a decoded segment under a stated key.
     *
     * <p>Assumptions: the reconstituting factory is used rather than the ordinary constructor,
     * because the constructor admits only the two match statuses a newly decided authorization can
     * carry while a recorded image may hold any of the four. The account identifier, the two key
     * components and the transaction identifier are parameters rather than being read from the image,
     * because every method here places its rows under its own account and several derive more than
     * one row from a single recorded record.
     *
     * @param fields a decoded segment; must not be {@code null}
     * @param accountId the account the row belongs beneath
     * @param authDate the decoded Julian date component of the key
     * @param authTime the decoded millisecond time component of the key
     * @param transactionId the acquirer transaction identifier to store; must be distinct per card
     * @return an unsaved entity carrying every column of that segment
     */
    private static PendingAuthDetail entity(Map<String, Object> fields, long accountId,
            int authDate, int authTime, String transactionId) {
        return PendingAuthDetail.rehydrated(
                new PendingAuthDetailKey(accountId, authDate, authTime),
                text(fields, "PA-AUTH-ORIG-DATE"),
                text(fields, "PA-AUTH-ORIG-TIME"),
                text(fields, "PA-CARD-NUM"),
                text(fields, "PA-AUTH-TYPE"),
                text(fields, "PA-CARD-EXPIRY-DATE"),
                text(fields, "PA-MESSAGE-TYPE"),
                text(fields, "PA-MESSAGE-SOURCE"),
                text(fields, "PA-AUTH-ID-CODE"),
                text(fields, "PA-AUTH-RESP-CODE"),
                text(fields, "PA-AUTH-RESP-REASON"),
                zeroPadded(fields, "PA-PROCESSING-CODE", 6),
                number(fields, "PA-TRANSACTION-AMT"),
                number(fields, "PA-APPROVED-AMT"),
                text(fields, "PA-MERCHANT-CATAGORY-CODE"),
                text(fields, "PA-ACQR-COUNTRY-CODE"),
                number(fields, "PA-POS-ENTRY-MODE").shortValueExact(),
                text(fields, "PA-MERCHANT-ID"),
                text(fields, "PA-MERCHANT-NAME"),
                text(fields, "PA-MERCHANT-CITY"),
                text(fields, "PA-MERCHANT-STATE"),
                text(fields, "PA-MERCHANT-ZIP"),
                transactionId,
                text(fields, "PA-MATCH-STATUS"));
    }

    /**
     * Saves entities in one transaction and returns nothing.
     *
     * <p>Assumptions: a template is used rather than a transactional annotation on the test method,
     * because an annotation would enrol every assertion in the method in ONE transaction that is
     * rolled back at the end. Reads would then see uncommitted rows and a refusal would poison every
     * later statement in the same method, which is precisely the hazard the charter records.
     *
     * @param rows the entities to persist; must not be {@code null}
     */
    private void saveAll(List<PendingAuthDetail> rows) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> rows.forEach(repository::save));
    }

    /**
     * Runs one repository read in its own transaction and returns the rows it produced.
     *
     * @param read the repository call to make; must not be {@code null}
     * @return whatever that call returned, never {@code null}
     */
    private List<PendingAuthDetail> read(Supplier<List<PendingAuthDetail>> read) {
        return new TransactionTemplate(transactionManager).execute(status -> read.get());
    }

    /**
     * Inserts one detail row by statement, so a value the mapped types refuse can reach the engine.
     *
     * <p>Assumptions: every column is bound, and the seven that vary across the callers are
     * parameters while the rest come from the decoded image. The parameterised seven are the ones
     * whose domains or widths are under test; binding the remainder from the image keeps each
     * refusal attributable to the single value the caller changed.
     *
     * @param accountId the account the row belongs beneath
     * @param authDate the value to store in the Julian date key column, in or out of domain
     * @param authTime the value to store in the millisecond time key column, in or out of domain
     * @param fields a decoded segment supplying every remaining column; must not be {@code null}
     * @param transactionId the acquirer transaction identifier to store
     * @param authRespCode the reply response code to store, or {@code null} for SQL null
     * @param authRespReason the reply reason code to store, or {@code null} for SQL null
     * @param fraudReportDate the fraud report date to store, or {@code null} for SQL null
     * @throws SQLException if the engine refuses the row, which is what a refusal assertion inspects
     */
    private static void insertByStatement(long accountId, int authDate, int authTime,
            Map<String, Object> fields, String transactionId, String authRespCode,
            String authRespReason, String fraudReportDate) throws SQLException {
        String sql = """
                INSERT INTO %s.pending_auth_detail (
                    account_id, auth_date, auth_time, auth_orig_date, auth_orig_time, card_num,
                    auth_type, card_expiry_date, message_type, message_source, auth_id_code,
                    auth_resp_code, auth_resp_reason, processing_code, transaction_amt,
                    approved_amt, merchant_category_code, acqr_country_code, pos_entry_mode,
                    merchant_id, merchant_name, merchant_city, merchant_state, merchant_zip,
                    transaction_id, match_status, auth_fraud, fraud_rpt_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?)
                """.formatted(SCHEMA);
        try (Connection connection = connection();
                PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setLong(1, accountId);
            insert.setInt(2, authDate);
            insert.setInt(3, authTime);
            insert.setString(4, text(fields, "PA-AUTH-ORIG-DATE"));
            insert.setString(5, text(fields, "PA-AUTH-ORIG-TIME"));
            insert.setString(6, text(fields, "PA-CARD-NUM"));
            insert.setString(7, text(fields, "PA-AUTH-TYPE"));
            insert.setString(8, text(fields, "PA-CARD-EXPIRY-DATE"));
            insert.setString(9, text(fields, "PA-MESSAGE-TYPE"));
            insert.setString(10, text(fields, "PA-MESSAGE-SOURCE"));
            insert.setString(11, text(fields, "PA-AUTH-ID-CODE"));
            insert.setString(12, authRespCode);
            insert.setString(13, authRespReason);
            insert.setString(14, zeroPadded(fields, "PA-PROCESSING-CODE", 6));
            insert.setBigDecimal(15, number(fields, "PA-TRANSACTION-AMT"));
            insert.setBigDecimal(16, number(fields, "PA-APPROVED-AMT"));
            insert.setString(17, text(fields, "PA-MERCHANT-CATAGORY-CODE"));
            insert.setString(18, text(fields, "PA-ACQR-COUNTRY-CODE"));
            insert.setShort(19, number(fields, "PA-POS-ENTRY-MODE").shortValueExact());
            insert.setString(20, text(fields, "PA-MERCHANT-ID"));
            insert.setString(21, text(fields, "PA-MERCHANT-NAME"));
            insert.setString(22, text(fields, "PA-MERCHANT-CITY"));
            insert.setString(23, text(fields, "PA-MERCHANT-STATE"));
            insert.setString(24, text(fields, "PA-MERCHANT-ZIP"));
            insert.setString(25, transactionId);
            insert.setString(26, text(fields, "PA-MATCH-STATUS"));
            insert.setString(27, text(fields, "PA-AUTH-FRAUD"));
            insert.setString(28, fraudReportDate);
            insert.executeUpdate();
        }
    }

    /**
     * Decodes a packed-decimal account prefix into the identifier it carries.
     *
     * <p>Assumptions: the prefix is packed decimal, two digits to a byte with the sign occupying the
     * low-order nibble of the last byte, so six bytes hold eleven digits and one sign. The unload
     * program declares that prefix as an eleven-digit packed root key, and the recorded images carry
     * a positive sign nibble throughout, so the sign is read and required to be non-negative rather
     * than assumed away.
     *
     * @param prefix exactly {@link #ACCOUNT_PREFIX_WIDTH} bytes of packed decimal; must not be
     *     {@code null}
     * @return the account identifier those bytes encode
     */
    private static long unpackAccount(byte[] prefix) {
        StringBuilder digits = new StringBuilder();
        for (int index = 0; index < prefix.length; index++) {
            digits.append((char) ('0' + ((prefix[index] >> 4) & 0x0F)));
            if (index < prefix.length - 1) {
                digits.append((char) ('0' + (prefix[index] & 0x0F)));
            }
        }
        int sign = prefix[prefix.length - 1] & 0x0F;
        assertThat(sign).as("the packed prefix carries a positive or unsigned sign nibble")
                .isIn(0x0A, 0x0C, 0x0E, 0x0F);
        return Long.parseLong(digits.toString());
    }

    /**
     * Attempts a detail insert that omits the account column entirely.
     *
     * <p>Assumptions: only the columns the table declares as required are supplied, minus the account
     * itself, so the one thing missing from the statement is the very identity a bare segment cannot
     * provide. Supplying the optional columns as well would add nothing and would risk a second
     * constraint reaching the engine first.
     *
     * @param fields a decoded segment supplying the required non-key columns; must not be
     *     {@code null}
     * @throws SQLException always in practice, carrying the engine's not-null refusal
     */
    private static void insertWithoutAccount(Map<String, Object> fields) throws SQLException {
        String sql = "INSERT INTO " + SCHEMA + ".pending_auth_detail (auth_date, auth_time,"
                + " card_num, transaction_amt, approved_amt, transaction_id, match_status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = connection();
                PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setInt(1, decodedDate(fields));
            insert.setInt(2, decodedTime(fields));
            insert.setString(3, text(fields, "PA-CARD-NUM"));
            insert.setBigDecimal(4, number(fields, "PA-TRANSACTION-AMT"));
            insert.setBigDecimal(5, number(fields, "PA-APPROVED-AMT"));
            insert.setString(6, text(fields, "PA-TRANSACTION-ID"));
            insert.setString(7, text(fields, "PA-MATCH-STATUS"));
            insert.executeUpdate();
        }
    }

    /**
     * Runs a statement expected to be refused and returns the engine's own error detail.
     *
     * <p>Assumptions: a refusal is asserted by the CONSTRAINT the engine names and not merely by an
     * exception arriving. A duplicate key, a null violation and a check violation are all
     * indistinguishable to an assertion that only requires a failure, so such an assertion can pass
     * while the constraint it was written for is absent. Unwrapping to the engine's error detail is
     * what makes the identity available.
     *
     * @param statement the statement to run, which is required to fail; must not be {@code null}
     * @return the engine's error detail, carrying both the state and the constraint name
     */
    private static ServerErrorMessage refusalOf(ThrowingCallable statement) {
        Throwable raised = catchThrowable(statement);
        assertThat(raised).as("the statement was expected to be refused").isNotNull()
                .isInstanceOf(PSQLException.class);
        ServerErrorMessage detail = ((PSQLException) raised).getServerErrorMessage();
        assertThat(detail).as("the refusal carried no server error detail").isNotNull();
        return detail;
    }

    /**
     * Reads the column names of one constraint's index, in the order the index declares them.
     *
     * <p>Assumptions: the LIVE catalogue is queried rather than the migration text, because the
     * subject is what the engine built and not what the file said. A test that parsed the migration
     * would pass whenever the file was well written, including when the statement had never run.
     *
     * @param constraintName the constraint whose key columns are wanted; must not be {@code null}
     * @return the key column names in index order, empty when no such constraint exists
     * @throws IllegalStateException if the catalogue query itself fails, which is a broken harness
     *     rather than an assertion outcome and is raised distinctly so it cannot be mistaken for one
     */
    private static List<String> keyColumnsOf(String constraintName) {
        String sql = """
                SELECT a.attname
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                  JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON TRUE
                  JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
                 WHERE c.conname = ? AND n.nspname = ?
                 ORDER BY k.ord
                """;
        List<String> columns = new ArrayList<>();
        try (Connection connection = connection();
                PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, constraintName);
            query.setString(2, SCHEMA_NAME);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("catalogue query failed for " + constraintName, failure);
        }
        return columns;
    }

    /**
     * Reads one column of one detail row as text, exactly as the engine returns it.
     *
     * @param column the column to read; must be a column of the detail table
     * @param accountId the account of the wanted row
     * @param transactionId the acquirer transaction identifier of the wanted row
     * @return the column's value, or empty when the row or the value is absent
     * @throws IllegalStateException if the read itself fails, which is a broken harness rather than
     *     an assertion outcome
     */
    private static Optional<String> storedText(String column, long accountId,
            String transactionId) {
        String sql = "SELECT " + column + " FROM " + SCHEMA + ".pending_auth_detail"
                + " WHERE account_id = ? AND transaction_id = ?";
        try (Connection connection = connection();
                PreparedStatement query = connection.prepareStatement(sql)) {
            query.setLong(1, accountId);
            query.setString(2, transactionId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).as("no detail row for transaction " + transactionId)
                        .isTrue();
                return Optional.ofNullable(rows.getString(1));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("read of " + column + " failed", failure);
        }
    }

    /**
     * Counts the detail rows beneath one account.
     *
     * @param accountId the account to count beneath
     * @return the number of detail rows currently keyed on that account
     * @throws IllegalStateException if the count query itself fails, which is a broken harness rather
     *     than an assertion outcome
     */
    private static int rowCount(long accountId) {
        String sql = "SELECT count(*) FROM " + SCHEMA + ".pending_auth_detail WHERE account_id = ?";
        try (Connection connection = connection();
                PreparedStatement query = connection.prepareStatement(sql)) {
            query.setLong(1, accountId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).as("count query returned no row").isTrue();
                return rows.getInt(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("count query failed", failure);
        }
    }

    /**
     * Proves the primary key is the three-part composite and names its columns in key order.
     *
     * <p>Assumptions: the account identifier is part of the key even though the child segment
     * declares no account field of its own, and this is the single most consequential structural
     * decision in this table. {@code cpy/CIPAUDTY.cpy} opens at {@code PA-AUTHORIZATION-KEY} on L19,
     * a group of exactly two packed children -- {@code PA-AUTH-DATE-9C PIC S9(05) COMP-3} on L20 in
     * three bytes and {@code PA-AUTH-TIME-9C PIC S9(09) COMP-3} on L21 in five -- and not one of the
     * segment's remaining items is an account identifier; the parent carries it instead, as
     * {@code PA-ACCT-ID} at {@code cpy/CIPAUSMY.cpy} L19. In the hierarchical store the parent key is
     * INHERITED rather than stored, which the descriptor states arithmetically: the eight bytes of
     * {@code FIELD NAME=(PAUT9CTS,SEQ,U),START=1,BYTES=8,TYPE=C} at {@code ims/DBPAUTP0.dbd} L37 plus
     * the six of {@code FIELD NAME=(ACCNTID,SEQ,U),START=1,BYTES=6,TYPE=P} at L30 are exactly the
     * fourteen of {@code KEYLEN=14} at {@code ims/PSBPAUTB.psb} L17. A relational table has no
     * hierarchy to inherit through, so the account has to be a real column and part of the key.
     *
     * <p>Assumptions: the two segment components alone would NOT do, and the reason is not
     * fastidiousness. The segment key is unique only within one parent, because that is the only
     * scope the hierarchy enforces it in, so two accounts recording an authorization in the same
     * millisecond would collide the moment the account left the key.
     */
    @Test
    @DisplayName("the primary key is (account_id, auth_date, auth_time) in that order")
    void thePrimaryKeyIsTheThreePartCompositeInKeyOrder() {
        assertThat(keyColumnsOf(PRIMARY_KEY))
                .as("the live catalogue's key columns for " + PRIMARY_KEY)
                .containsExactly("account_id", "auth_date", "auth_time");
    }

    /**
     * Proves the account-prefixed unload form carries an account identifier and loads with it.
     *
     * <p>Assumptions: this image is 824 bytes of four 206-byte records, each being a six-byte packed
     * account prefix followed by the 200-byte segment, so the prefix width is the difference between
     * the stride and the registered segment length and is derived here rather than restated. The
     * prefix is the eleven-digit account identifier as packed decimal, which occupies six bytes; the
     * four prefixes are the alternating pair 10000000001, 10000000002, 10000000001, 10000000002. The
     * segment behind each prefix decodes under the ordinary 200-byte layout, whose key spans are the
     * nines complements at offsets 0 to 2 and 3 to 7.
     *
     * <p>Assumptions: the reference system MATERIALISES the inherited key itself the moment it has to
     * write the child outside the hierarchy, which is why this form exists at all rather than being
     * invented for the target. Its unload program declares the child output record as a packed root
     * key followed by the 200-byte child and fills that prefix from the parent's account before
     * writing, so the composite key below is that same hierarchic path made explicit.
     *
     * @throws SQLException if the engine refuses any of the four rows, which would mean the prefix
     *     failed to supply a usable account identifier
     */
    @Test
    @DisplayName("the prefixed unload form supplies the account identifier and every record loads")
    void theAccountPrefixedUnloadFormCarriesTheAccountAndLoads() throws SQLException {
        byte[] image = bytes(PREFIXED_UNLOAD);
        assertThat(image.length % PREFIXED_RECORD_LENGTH)
                .as("the prefixed image is a whole number of 206-byte records").isZero();
        assertThat(ACCOUNT_PREFIX_WIDTH).as("the prefix width the stride implies").isEqualTo(6);

        int records = image.length / PREFIXED_RECORD_LENGTH;
        List<Long> accounts = new ArrayList<>();
        Set<Long> seededParents = new LinkedHashSet<>();
        for (int ordinal = 0; ordinal < records; ordinal++) {
            byte[] whole = record(image, ordinal, PREFIXED_RECORD_LENGTH);
            byte[] prefix = Arrays.copyOfRange(whole, 0, ACCOUNT_PREFIX_WIDTH);
            byte[] segment = Arrays.copyOfRange(whole, ACCOUNT_PREFIX_WIDTH, PREFIXED_RECORD_LENGTH);
            assertThat(segment).as("the segment behind the prefix").hasSize(SEGMENT_LENGTH);

            long accountId = unpackAccount(prefix);
            accounts.add(accountId);

            // WHY : Assumptions: the parent is seeded once per DISTINCT account, tracked explicitly
            //       rather than inferred from whether children already exist. The two derivations
            //       agree on this image and would diverge on any image whose first record for an
            //       account were not its only one, so the explicit set is what keeps the seeding
            //       correct independently of record order.
            if (seededParents.add(accountId)) {
                insertParent(accountId);
            }
            Map<String, Object> fields = decode(segment);
            insertByStatement(accountId, decodedDate(fields), decodedTime(fields), fields,
                    text(fields, "PA-TRANSACTION-ID"), text(fields, "PA-AUTH-RESP-CODE"),
                    text(fields, "PA-AUTH-RESP-REASON"), null);
        }

        assertThat(accounts).as("the four prefixes, two parents alternating")
                .containsExactly(10_000_000_001L, 10_000_000_002L, 10_000_000_001L,
                        10_000_000_002L);
        assertThat(rowCount(10_000_000_001L)).as("children under the first parent").isEqualTo(2);
        assertThat(rowCount(10_000_000_002L)).as("children under the second parent").isEqualTo(2);
    }

    /**
     * Proves the bare unload form offers no account identifier, so no row can be keyed from it alone.
     *
     * <p>Assumptions: this image is 800 bytes of four 200-byte records with no prefix at all -- the
     * records are the segment length exactly, which is what makes it the negative half of the pair
     * above. Its first six bytes are therefore not an account: they are the segment's own key, the
     * three-byte date complement followed by the first three bytes of the five-byte time complement.
     *
     * <p>Assumptions: the segment layout is asked whether it declares an account field, rather than
     * the question being answered by inspection here, so the assertion tracks the registry and cannot
     * drift from it. The contrast with the parent layout is the point: the parent's registered field
     * set opens with its account identifier and the child's contains no such field under any name.
     *
     * <p>Assumptions: the engine has the last word, and it refuses the row for the reason this test
     * is about. Omitting the account column from an insert reaches a not-null violation naming
     * {@code account_id}, so "cannot be inserted at all" is an outcome the database states rather
     * than a claim this file makes. That asymmetry is worth naming: BOTH unload shapes of the PARENT
     * table load, because the parent record is self-contained, and only the child has a form that
     * cannot.
     */
    @Test
    @DisplayName("the bare unload form has no account identifier and cannot key a row")
    void theBareUnloadFormOffersNoAccountIdentifier() {
        byte[] image = bytes(GSAM_UNLOAD);
        assertThat(image.length % SEGMENT_LENGTH)
                .as("the bare image is a whole number of 200-byte records").isZero();

        List<String> childFields = CopybookLayout.layout(LAYOUT).fields().stream()
                .map(CopybookLayout.FieldSpec::name).toList();
        assertThat(childFields).as("the child segment declares no account field")
                .doesNotContain("PA-ACCT-ID");
        assertThat(CopybookLayout.layout("PAUTSUM0").fields().stream()
                .map(CopybookLayout.FieldSpec::name).toList())
                .as("the parent segment does declare one").contains("PA-ACCT-ID");

        Map<String, Object> fields = decode(record(image, 0, SEGMENT_LENGTH));
        ServerErrorMessage refusal = refusalOf(() -> insertWithoutAccount(fields));
        assertThat(refusal.getSQLState()).as("state of a missing required column")
                .isEqualTo(NOT_NULL_VIOLATION);
        assertThat(refusal.getColumn()).as("the column the engine named")
                .isEqualTo("account_id");
    }

    /**
     * Proves a detail row cannot exist without the summary its account names.
     *
     * <p>Assumptions: in the reference system this is STRUCTURAL rather than declared -- a child
     * segment is reachable only beneath its root -- so a relational target has to assert it, and the
     * assertion has to be reachable. The insert below supplies a well-formed row in every respect
     * except that no summary carries its account, so the only constraint it can offend is the
     * parentage one, and the engine's own naming of that constraint is what the assertion reads.
     *
     * <p>Trade-offs: this refusal has a method to itself, as every refusal here does. This engine
     * abandons a whole transaction at the first statement that raises, so an acceptance asserted after
     * a refusal in the same transaction fails on the abandoned transaction rather than on its own
     * subject -- and the failure names the transaction, so it reads as a defect in the code under
     * test. That was verified rather than assumed while this file was written: co-locating a refused
     * insert and a valid one inside a single transaction makes the valid one fail with state 25P02,
     * reporting that the transaction is aborted and commands are ignored until it ends, which says
     * nothing at all about the row it was given. Every refusal here is issued on a connection of its
     * own in autocommit mode, so each statement is its own transaction and none can poison another.
     * More methods than a reader might expect is the accepted cost of failures that mean what they
     * say.
     */
    @Test
    @DisplayName("a detail row with no summary is refused by the parentage constraint")
    void aDetailRowWithoutItsSummaryIsRefused() {
        Map<String, Object> fields = decode(bytes(CANONICAL));
        ServerErrorMessage refusal = refusalOf(() -> insertByStatement(ORPHAN_ACCOUNT,
                decodedDate(fields), decodedTime(fields), fields, "TXNORPHAN000001",
                text(fields, "PA-AUTH-RESP-CODE"), text(fields, "PA-AUTH-RESP-REASON"), null));

        assertThat(refusal.getSQLState()).as("state of a violated parentage constraint")
                .isEqualTo(FOREIGN_KEY_VIOLATION);
        assertThat(refusal.getConstraint()).as("the constraint the engine named")
                .isEqualTo(SUMMARY_FOREIGN_KEY);
        assertThat(rowCount(ORPHAN_ACCOUNT)).as("no orphan row survived").isZero();
    }

    /**
     * Proves removing a summary removes its children, reproducing the hierarchical delete.
     *
     * <p>Assumptions: the cascade is what reproduces a root taking its children with it, which is the
     * behaviour the hierarchy gives for free and a relational schema must declare. The expiry sweep
     * relies on the ordering this implies -- it removes the child at its own paragraph and only
     * afterwards the root -- so the target states the same dependency as a foreign key, and a reversed
     * order is refused by the database rather than merely diverging.
     */
    @Test
    @DisplayName("deleting a summary cascades to every detail row beneath it")
    void deletingASummaryRemovesItsChildren() {
        Map<String, Object> fields = decode(bytes(CANONICAL));
        insertParent(CANONICAL_ACCOUNT);
        saveAll(List.of(entity(fields, CANONICAL_ACCOUNT, decodedDate(fields), decodedTime(fields),
                "TXNCASCADE00001")));
        assertThat(rowCount(CANONICAL_ACCOUNT)).as("the child is present before the delete")
                .isEqualTo(1);

        execute("DELETE FROM " + SCHEMA + ".pending_auth_summary WHERE account_id = "
                + CANONICAL_ACCOUNT);

        assertThat(rowCount(CANONICAL_ACCOUNT)).as("the child went with its parent").isZero();
    }

    /**
     * Proves a read by the whole composite key returns every column of the reference record intact.
     *
     * <p>Assumptions: the image is one 200-byte record whose offsets this assertion depends on
     * throughout -- the two complemented key spans at 0 to 2 and 3 to 7, the original date and time at
     * 8 and 14, the card number at 20, the authorization type at 36, the expiry at 40, the message
     * type and source at 44 and 50, the identification code at 56, the response code at 62 and its
     * reason at 64, the processing code at 68, the two seven-byte packed amounts at 74 and 81, the
     * category code at 88, the country code at 92, the entry mode at 95, the merchant identifier at
     * 97, the merchant name at 112, the city at 134, the state at 147, the postal code at 149, the
     * transaction identifier at 158, the match status at 173, the fraud flag at 174 and the report
     * date at 175. Both amounts are packed decimal in seven bytes carrying twelve digits and one sign
     * nibble, so the leading nibble of the first byte is padding and must be zero.
     *
     * <p>Refactoring Rationale: one column is deliberately NOT named as the record names it. The
     * baseline spells the category field {@code PA-MERCHANT-CATAGORY-CODE} at
     * {@code cpy/CIPAUDTY.cpy} L36, transposing letters in the word, and that spelling is not
     * confined to the copybook -- it is the live Db2 column name at {@code ddl/AUTHFRDS.ddl} L14 and
     * the host variable at {@code dcl/AUTHFRDS.dcl} L68 and L69, so it reached persisted state and
     * running code. The target column is {@code merchant_category_code} and the accessor is
     * {@code getMerchantCategoryCode}. The divergence is taken exactly once, at the schema boundary,
     * and is registered in the data-model mapping document; the baseline itself is reference-only and
     * keeps its own spelling untouched. Carrying that spelling forward would have propagated it into a
     * column, an entity member, a transfer object and a browser client, where every later reader would
     * have had to learn it.
     *
     * <p>Assumptions: amounts are compared by VALUE and not by representation, because a comparison
     * sensitive to scale would call a two-place amount unequal to the same amount at one place and the
     * failure would look like a money defect rather than a formatting one.
     */
    @Test
    @DisplayName("a read by the whole key returns every column of the reference record")
    void aReadByTheWholeKeyReturnsEveryColumn() {
        Map<String, Object> fields = decode(bytes(CANONICAL));
        int authDate = decodedDate(fields);
        int authTime = decodedTime(fields);
        insertParent(CANONICAL_ACCOUNT);
        saveAll(List.of(entity(fields, CANONICAL_ACCOUNT, authDate, authTime, "TXN000000000001")));

        PendingAuthDetail stored = read(() -> repository
                .findById(new PendingAuthDetailKey(CANONICAL_ACCOUNT, authDate, authTime))
                .map(List::of).orElse(List.of())).get(0);

        assertThat(stored.getId().getAccountId()).as("account").isEqualTo(CANONICAL_ACCOUNT);
        assertThat(stored.getId().getAuthDate()).as("Julian date").isEqualTo(23_180);
        assertThat(stored.getId().getAuthTime()).as("millisecond time").isEqualTo(143_025_123);
        assertThat(stored.getAuthOrigDate()).as("original date").isEqualTo("230629");
        assertThat(stored.getAuthOrigTime()).as("original time").isEqualTo("143025");
        assertThat(stored.getCardNum()).as("card number").isEqualTo("4000123456789010");
        assertThat(stored.getAuthType()).as("authorization type").isEqualTo("PURC");
        assertThat(stored.getCardExpiryDate()).as("card expiry").isEqualTo("1227");
        assertThat(stored.getMessageType()).as("message type").isEqualTo("0100  ");
        assertThat(stored.getMessageSource()).as("message source").isEqualTo("POS   ");
        assertThat(stored.getAuthIdCode()).as("identification code").isEqualTo("A00001");
        assertThat(stored.getAuthRespCode()).as("response code").isEqualTo("00");
        assertThat(stored.getAuthRespReason()).as("response reason").isEqualTo("0000");
        assertThat(stored.getProcessingCode()).as("processing code").isEqualTo("000000");
        assertThat(stored.getTransactionAmount()).as("transaction amount")
                .isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(stored.getApprovedAmount()).as("approved amount")
                .isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(stored.getMerchantCategoryCode()).as("category code, target spelling")
                .isEqualTo("5411");
        assertThat(stored.getAcqrCountryCode()).as("country code").isEqualTo("840");
        assertThat(stored.getPosEntryMode()).as("entry mode").isEqualTo((short) 5);
        assertThat(stored.getMerchantId()).as("merchant identifier").isEqualTo("MERCH0000000001");
        assertThat(stored.getMerchantName()).as("merchant name").isEqualTo("ACME HARDWARE         ");
        assertThat(stored.getMerchantCity()).as("merchant city").isEqualTo("SEATTLE      ");
        assertThat(stored.getMerchantState()).as("merchant state").isEqualTo("WA");
        assertThat(stored.getMerchantZip()).as("merchant postal code").isEqualTo("98101    ");
        assertThat(stored.getTransactionId()).as("transaction identifier")
                .isEqualTo("TXN000000000001");
        assertThat(stored.getMatchStatus()).as("match status").isEqualTo("P");
    }

    /**
     * Proves the key columns hold the DECODED values and never the complement the segment stores.
     *
     * <p>Assumptions: this image is one 200-byte record built so that its uninverted key looks
     * entirely ordinary. Its stored spans are the complements 75899 at offset 0 and 879999999 at
     * offset 3, which invert to Julian 24100 and a time of 120000000 -- noon exactly -- and the
     * inversion is corroborated inside the record itself by the separate character span at offset 14
     * holding {@code 120000}. The uninverted time is a plausible nine-digit number whose leading pair
     * is 87, an hour that cannot exist, which is the only thing distinguishing the two readings.
     *
     * <p>Assumptions: this confusion is NOT schema-detectable and that is precisely why it needs an
     * assertion on the stored value. Both key columns are plain integers with domain checks wide
     * enough to admit either reading for the date, so a system that persisted the complement would
     * insert cleanly, order every list backwards and render every date and time wrong, and no
     * constraint anywhere would object. Reading the column back and comparing it against the inverted
     * value is the only thing that catches it.
     *
     * <p>Alternatives Considered: storing the complement as the reference does, which would have kept
     * traversal ascending and required no direction to be declared. Rejected because no column would
     * then hold a date: the reference has to undo the complement the moment it needs date arithmetic,
     * which its own expiry sweep does with the same constant before it can subtract one date from
     * another. Storing decoded values makes an age comparison a comparison, and pays for it by having
     * to state the ordering direction explicitly, which the ordering assertion below does.
     *
     * <p>Alternatives Considered: giving the two components proper temporal types, a date and a time
     * carrying milliseconds, instead of two integers. Rejected because the reference key is a single
     * eight-byte CHARACTER sequence field, so splitting it into two typed temporal columns would change
     * the collation the ordering depends upon; two integers preserve both the arity of the original key
     * and the comparison semantics the paging predicate is built on.
     *
     * <p>Trade-offs: those integers cost legibility, and the cost is real rather than notional -- a
     * reader of a row sees 24100 and 120000000 where a date and a time would have shown a date and a
     * time, and every rendering has to reconstruct them. What it buys is exact, collation-independent
     * ordering and a paging predicate that is a plain tuple comparison over two integers, with no
     * temporal type's own comparison rules interposed between the key and the order it produces.
     */
    @Test
    @DisplayName("the key columns hold the decoded values, not the stored complement")
    void theKeyColumnsHoldDecodedValuesRatherThanTheComplement() {
        Map<String, Object> fields = decode(bytes(COMPLEMENT_TRAP));
        assertThat(number(fields, "PA-AUTH-DATE-9C").intValueExact())
                .as("the complement the segment stores").isEqualTo(75_899);
        assertThat(number(fields, "PA-AUTH-TIME-9C").intValueExact())
                .as("the complement the segment stores").isEqualTo(879_999_999);

        int authDate = decodedDate(fields);
        int authTime = decodedTime(fields);
        insertParent(DECODED_KEY_ACCOUNT);
        saveAll(List.of(entity(fields, DECODED_KEY_ACCOUNT, authDate, authTime,
                "TXN000000000021")));

        PendingAuthDetail stored = read(() -> repository
                .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(DECODED_KEY_ACCOUNT)).get(0);

        assertThat(stored.getId().getAuthDate()).as("the stored date is the inverted value")
                .isEqualTo(24_100);
        assertThat(stored.getId().getAuthTime()).as("the stored time is the inverted value")
                .isEqualTo(120_000_000);
        assertThat(stored.getAuthOrigTime()).as("the record's own character span corroborates noon")
                .isEqualTo("120000");
    }

    /**
     * Proves a complement whose leading digits are nines inverts to a much shorter value.
     *
     * <p>Assumptions: this image is one 200-byte record whose five-byte time span at offset 3 holds
     * the complement 999998999, inverting to 1000 -- one second past midnight expressed in
     * milliseconds. Its three-byte date span at offset 0 holds 75899, inverting to Julian 24100. All
     * eight key bytes fall outside the printable digit range, which is the property that makes the
     * record worth keeping separately from its near sibling.
     *
     * <p>Assumptions: the decoded value is FOUR characters wide where the stored complement is nine,
     * so an implementation that skipped the inversion is caught here on width as well as on value.
     * That is a different failure signal from the sibling assertion above, whose stored and decoded
     * times are both nine characters wide so that only the value can betray it -- which is why both
     * records exist and neither is redundant.
     */
    @Test
    @DisplayName("a time complement of leading nines inverts to a four-digit value")
    void aTimeComplementOfLeadingNinesInvertsToAShortValue() {
        Map<String, Object> fields = decode(bytes(TIME_LEADING_NINES));
        assertThat(number(fields, "PA-AUTH-TIME-9C").intValueExact())
                .as("the complement the segment stores").isEqualTo(999_998_999);

        int authDate = decodedDate(fields);
        int authTime = decodedTime(fields);
        assertThat(authTime).as("the inverted time").isEqualTo(1_000);
        assertThat(String.valueOf(authTime)).as("four characters against the stored nine")
                .hasSize(4);

        insertParent(PIVOT_ACCOUNT);
        saveAll(List.of(entity(fields, PIVOT_ACCOUNT, authDate, authTime, "TXN000000000020")));

        PendingAuthDetail stored = read(() -> repository
                .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(PIVOT_ACCOUNT)).get(0);
        assertThat(stored.getId().getAuthTime()).as("the column holds the inverted value")
                .isEqualTo(1_000);
        assertThat(stored.getId().getAuthDate()).as("and its date").isEqualTo(24_100);
    }

    /**
     * Proves two authorizations in the same second coexist, so the key resolves to the millisecond.
     *
     * <p>Assumptions: this image is 600 bytes of three 200-byte records that all share Julian 24095,
     * and two of them share the same SECOND. Their five-byte time complements at offset 3 are
     * {@code 83 69 54 49 9C} inverting to 163045500 and {@code 83 69 54 24 9C} inverting to
     * 163045750 -- 16:30:45.500 and 16:30:45.750 -- with a third at {@code 90 84 99 99 9C} inverting
     * to 91500000. The nine decoded digits partition as hour, minute, second and MILLISECOND, which
     * the reference states by slicing them in exactly that order at {@code cbl/COPAUS2C.cbl} L108 to
     * L111.
     *
     * <p>Trade-offs: keeping the millisecond costs a key column that reads as a bare integer rather
     * than as a time, and what it buys is that this pair can exist at all. Truncating to second
     * resolution would map both records onto one key and the second insert would be refused as a
     * duplicate -- a legitimate authorization turned into a constraint violation -- so the precision
     * is a correctness property of the key and not a nicety of measurement.
     */
    @Test
    @DisplayName("two authorizations in the same second coexist under the composite key")
    void twoAuthorizationsInOneSecondCoexist() {
        byte[] image = bytes(ORDER_SAME_DAY);
        Map<String, Object> earlier = decode(record(image, 0, SEGMENT_LENGTH));
        Map<String, Object> later = decode(record(image, 2, SEGMENT_LENGTH));

        assertThat(decodedTime(earlier)).as("the first of the same-second pair")
                .isEqualTo(163_045_500);
        assertThat(decodedTime(later)).as("the second of the same-second pair")
                .isEqualTo(163_045_750);
        assertThat(decodedTime(earlier) / 1_000).as("both fall in one second")
                .isEqualTo(decodedTime(later) / 1_000);
        assertThat(decodedDate(earlier)).as("and on one day").isEqualTo(decodedDate(later));

        insertParent(MILLISECOND_ACCOUNT);
        saveAll(List.of(
                entity(earlier, MILLISECOND_ACCOUNT, decodedDate(earlier), decodedTime(earlier),
                        "TXN000000000010"),
                entity(later, MILLISECOND_ACCOUNT, decodedDate(later), decodedTime(later),
                        "TXN000000000012")));

        assertThat(rowCount(MILLISECOND_ACCOUNT)).as("both rows survived the composite key")
                .isEqualTo(2);
    }

    /**
     * Seeds this class's paging dataset and returns the four keys in newest-first order.
     *
     * <p>Assumptions: the image is 800 bytes of four 200-byte records that all share Julian 24110 and
     * step hourly through 10:00, 11:00, 12:00 and 13:00, so their decoded times are 100000000,
     * 110000000, 120000000 and 130000000 read from the five-byte complement span at offset 3. The four
     * keys are therefore DISTINCT, which is what lets one transaction seed all four, and their file
     * order is ascending so the newest-first order is the reverse of it. Each record carries its own
     * transaction identifier at offset 158, so the pairing of card number and identifier stays unique
     * without this method inventing one.
     *
     * @return the four decoded times in the order a newest-first read must produce them
     */
    private List<Integer> seedFourChildren() {
        byte[] image = bytes(FOUR_CHILDREN);
        insertParent(PAGING_ACCOUNT);
        List<PendingAuthDetail> rows = new ArrayList<>();
        List<Integer> times = new ArrayList<>();
        for (int ordinal = 0; ordinal < image.length / SEGMENT_LENGTH; ordinal++) {
            Map<String, Object> fields = decode(record(image, ordinal, SEGMENT_LENGTH));
            times.add(decodedTime(fields));
            rows.add(entity(fields, PAGING_ACCOUNT, decodedDate(fields), decodedTime(fields),
                    text(fields, "PA-TRANSACTION-ID")));
        }
        saveAll(rows);

        assertThat(times).as("the four seeded times in file order")
                .containsExactly(100_000_000, 110_000_000, 120_000_000, 130_000_000);
        return times.reversed();
    }

    /**
     * Proves the opening page arrives newest first, in descending date then descending time.
     *
     * <p>Assumptions: the ordering is DESCENDING on both key components, and the reason it has to be
     * declared at all is the complement. The hierarchical retrieval walks twins in ASCENDING order over
     * the sequence field {@code FIELD NAME=(PAUT9CTS,SEQ,U),START=1,BYTES=8,TYPE=C} at
     * {@code ims/DBPAUTP0.dbd} L37, and a plain character sequence field offers no descending option at
     * all -- so ascending order over a complemented value IS descending order over the value it
     * complements, and the reference presents an account's authorizations newest first without ever
     * naming a sort.
     *
     * <p>Refactoring Rationale: the reference obtained newest-first as a SIDE EFFECT of the complement
     * encoding combined with an ascending character key; the target stores decoded values and obtains
     * the same observable order by declaring it. The order is identical and only its provenance
     * changes -- from implicit in an encoding to legible in a clause -- which is what makes this a
     * restatement of existing behaviour rather than a new one.
     */
    @Test
    @DisplayName("the opening page is ordered newest first on both key components")
    void theOpeningPageIsOrderedNewestFirst() {
        List<Integer> newestFirst = seedFourChildren();

        List<PendingAuthDetail> page = read(() -> repository
                .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(PAGING_ACCOUNT, Limit.of(4)));

        assertThat(page.stream().map(row -> row.getId().getAuthTime()).toList())
                .as("the four rows newest first").containsExactlyElementsOf(newestFirst);
        assertThat(page.stream().map(row -> row.getId().getAuthDate()).toList())
                .as("all four share one Julian date").containsOnly(24_110);
    }

    /**
     * Proves paging forward from a cursor yields strictly older rows, skipping and repeating none.
     *
     * <p>Assumptions: the same four-record image seeds this, so the cursor can be a real row's key and
     * the expected remainder is known exactly. Paging forward from the SECOND row of the newest-first
     * order must yield the two rows older than it and must exclude the cursor row itself and the row
     * newer than it.
     *
     * <p>Alternatives Considered: the intuitive keyset shape, a greater-than comparison ordered
     * ascending, which is what a reader who has not followed the complement writes. Rejected because it
     * is not merely wrong but SILENTLY wrong: it compiles, it runs, it returns real rows in a plausible
     * order, and it walks progressively further BACK through history on every page, so the defect
     * surfaces only as a user seeing a page that is not the next one. Nothing in the schema contradicts
     * it and no recorded output for this domain exists to compare against, which leaves an assertion
     * that pages forward from a known cursor as the only thing that distinguishes the two forms.
     *
     * <p>Alternatives Considered: comparing the two key components INDEPENDENTLY rather than as a pair,
     * which a first attempt at the predicate tends to do. Rejected because the two failure modes are
     * complementary and both are silent -- a comparison on the date alone drops every remaining row
     * that shares the boundary date, and a disjunction assembled carelessly returns rows the caller has
     * already been shown. The pair comparison has one boundary case and it is the cursor row, which
     * strictness excludes.
     *
     * <p>Alternatives Considered: locating the page by counting rows from the start of the ordering.
     * Rejected because a count is stable only while nothing is inserted ahead of the position, and this
     * table is written continuously by the queue consumer and thinned by the expiry sweep, so a row
     * would be silently repeated or silently passed over -- neither of which resuming from a VALUE can
     * do.
     */
    @Test
    @DisplayName("paging forward from a cursor returns strictly older rows and never the cursor row")
    void pagingForwardFromACursorReturnsStrictlyOlderRows() {
        List<Integer> newestFirst = seedFourChildren();
        int cursorTime = newestFirst.get(1);

        List<PendingAuthDetail> next = read(() -> repository.findOlderThan(PAGING_ACCOUNT, 24_110,
                cursorTime, Limit.of(4)));

        assertThat(next.stream().map(row -> row.getId().getAuthTime()).toList())
                .as("only the rows strictly older than the cursor, newest first")
                .containsExactly(newestFirst.get(2), newestFirst.get(3));
        assertThat(next.stream().map(row -> row.getId().getAuthTime()))
                .as("the cursor row itself is excluded").doesNotContain(cursorTime);
        assertThat(next.stream().map(row -> row.getId().getAuthTime()))
                .as("and so is the row newer than it").doesNotContain(newestFirst.get(0));
    }

    /**
     * Proves the paging query returns one row MORE than the page size when a further page exists.
     *
     * <p>Assumptions: the repository returns up to the caller's size plus one and does NOT strip the
     * extra row, and this is the contract most often got backwards. The service layer discards it,
     * reads whether a further page exists from whether it arrived, mints the cursor tokens and
     * assembles the shared envelope -- so a test asserting a list of exactly the page size would be
     * asserting the SERVICE's behaviour against the repository. The envelope is neither built nor named
     * here for the same reason.
     *
     * <p>Assumptions: the reference discovers the next-page condition the same way, by reading one more
     * record than fits. Its list screen fills a five-row array under a bounded index and then, after
     * the loop has closed, issues one further retrieval for no purpose other than to discover whether a
     * further row exists, setting its next-page flag from the outcome; that sixth row's key is never
     * stored, so the page's closing cursor stays the key of the last row DISPLAYED. A limit of size
     * plus one with the closing cursor taken from the last row returned is therefore a structural
     * equivalent rather than an approximation.
     *
     * <p>Trade-offs: a repository handing back a row its caller must discard is mildly surprising, and
     * it is accepted because it answers "is there a further page?" within the one query that fetches
     * the page, where the alternative is a second query counting rows the caller will never show.
     */
    @Test
    @DisplayName("the paging query returns size plus one when a further page exists")
    void thePagingQueryReturnsTheLookAheadRow() {
        List<Integer> newestFirst = seedFourChildren();
        int pageSize = 2;

        List<PendingAuthDetail> withLookAhead = read(() -> repository
                .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(PAGING_ACCOUNT,
                        Limit.of(pageSize + 1)));

        assertThat(withLookAhead).as("two rows for the page plus one look-ahead row")
                .hasSize(pageSize + 1);
        assertThat(withLookAhead.stream().map(row -> row.getId().getAuthTime()).toList())
                .as("still newest first, the last being the look-ahead")
                .containsExactly(newestFirst.get(0), newestFirst.get(1), newestFirst.get(2));
    }

    /**
     * Proves the paging query returns only the remainder when fewer than size plus one rows are left.
     *
     * <p>Assumptions: the same four-record image seeds this, and the cursor is placed so that exactly
     * one row remains older than it. Asking for a page of two -- three rows including the look-ahead --
     * must then yield one row and not three, because a short result is how exhaustion is expressed. An
     * exhausted page is a normal terminal outcome and never an error, which is why an empty or short
     * list arrives rather than anything being raised.
     */
    @Test
    @DisplayName("the paging query returns only the remainder when the look-ahead row is absent")
    void thePagingQueryReturnsTheRemainderWhenFewerRowsExist() {
        List<Integer> newestFirst = seedFourChildren();
        int cursorTime = newestFirst.get(2);

        List<PendingAuthDetail> tail = read(() -> repository.findOlderThan(PAGING_ACCOUNT, 24_110,
                cursorTime, Limit.of(3)));

        assertThat(tail.stream().map(row -> row.getId().getAuthTime()).toList())
                .as("the one remaining row, with no look-ahead to report a further page")
                .containsExactly(newestFirst.get(3));

        List<PendingAuthDetail> beyondTheOldest = read(() -> repository
                .findOlderThan(PAGING_ACCOUNT, 24_110, newestFirst.get(3), Limit.of(3)));
        assertThat(beyondTheOldest).as("past the oldest row the result is empty, not an error")
                .isEmpty();
    }

    /**
     * Proves two children of one recorded parent coexist across a year boundary.
     *
     * <p>Assumptions: two images pair here and the pairing is read rather than restated. The parent is
     * one 100-byte summary record whose six-byte packed account span at offset 0 carries 10000000001;
     * the children are 400 bytes of two 200-byte records, the first at Julian 23365 with a time
     * complement inverting to 235959999 and the second at Julian 24001 inverting to 1000 -- the last
     * millisecond of one year and one second into the next. The account the children are loaded beneath
     * is taken from the parent image, so this assertion would fail if the two fixtures were ever
     * changed apart.
     *
     * <p>Assumptions: the year boundary is where a key that carried only a day-of-year would collide,
     * and it does not here because the stored value is the full five-digit combined form. The two rows
     * differ in BOTH components, so their coexistence is evidence about the composite key rather than
     * about either column alone. The expiry arithmetic that spans the same boundary belongs to the
     * sweep's own tests and is not attempted here.
     */
    @Test
    @DisplayName("two children of the recorded parent coexist across a year boundary")
    void twoChildrenOfTheRecordedParentSpanAYearBoundary() {
        byte[] parentImage = bytes(PURGE_PARENT);
        assertThat(parentImage).as("the recorded parent summary").hasSize(100);
        long parentAccount = unpackAccount(Arrays.copyOfRange(parentImage, 0,
                ACCOUNT_PREFIX_WIDTH));
        assertThat(parentAccount).as("the account the parent image carries")
                .isEqualTo(10_000_000_001L);
        insertParent(parentAccount);

        byte[] children = bytes(NEWYEAR_PAIR);
        Map<String, Object> yearEnd = decode(record(children, 0, SEGMENT_LENGTH));
        Map<String, Object> yearStart = decode(record(children, 1, SEGMENT_LENGTH));
        assertThat(decodedDate(yearEnd)).as("the closing Julian of one year").isEqualTo(23_365);
        assertThat(decodedTime(yearEnd)).as("its last millisecond").isEqualTo(235_959_999);
        assertThat(decodedDate(yearStart)).as("the opening Julian of the next").isEqualTo(24_001);
        assertThat(decodedTime(yearStart)).as("one second in").isEqualTo(1_000);

        saveAll(List.of(
                entity(yearEnd, parentAccount, decodedDate(yearEnd), decodedTime(yearEnd),
                        text(yearEnd, "PA-TRANSACTION-ID")),
                entity(yearStart, parentAccount, decodedDate(yearStart), decodedTime(yearStart),
                        text(yearStart, "PA-TRANSACTION-ID"))));

        List<PendingAuthDetail> both = read(() -> repository
                .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(parentAccount));
        assertThat(both.stream().map(row -> row.getId().getAuthDate()).toList())
                .as("the newer year first").containsExactly(24_001, 23_365);
    }

    /**
     * Proves the Julian date column refuses values inside its digit envelope that name no real day.
     *
     * <p>Assumptions: a RANGE is not the domain of this column, and the two values below are why. Both
     * fit the five-digit envelope and both address nothing: 24367 is day 367 of a year that has at most
     * 366, and 10000 is day ZERO of year ten. The declared check therefore decomposes the packed value
     * and bounds the day-of-year component separately from the whole, so a plausible number that names
     * no row is refused at the one place every writer passes through -- the queue consumer, the marking
     * flow and the extract load alike.
     *
     * <p>Assumptions: the statement path is used rather than the mapped types, and it is the only path
     * that can reach this constraint. The key type validates the identical domain in Java and raises
     * before a statement is prepared, so a test driving the repository would prove the Java guard and
     * leave the database guard unverified -- passing while the constraint was absent. Both guards are
     * wanted, and this file's subject is the second.
     *
     * <p>Trade-offs: two refusals sit in one method here and that is safe for a specific reason rather
     * than by luck. Each insert opens its own connection in autocommit mode, so each statement is its
     * own transaction and a refusal abandons nothing that a later statement needs. What would be unsafe
     * is sharing one transaction across a refusal and anything after it, which is why no acceptance is
     * asserted in this method and why each CONSTRAINT keeps a method of its own.
     */
    @Test
    @DisplayName("the date column refuses day 367 and day zero, naming its own check")
    void theDateColumnRefusesDaysThatNameNoRealDate() {
        Map<String, Object> fields = decode(bytes(CANONICAL));
        insertParent(KEY_DOMAIN_ACCOUNT);

        ServerErrorMessage dayTooHigh = refusalOf(() -> insertByStatement(KEY_DOMAIN_ACCOUNT,
                24_367, 120_000_000, fields, "TXNDATE00000001", "00", "0000", null));
        assertThat(dayTooHigh.getSQLState()).as("state of a violated check").isEqualTo(
                CHECK_VIOLATION);
        assertThat(dayTooHigh.getConstraint()).as("day 367 is refused by the date check")
                .isEqualTo(AUTH_DATE_CHECK);

        ServerErrorMessage dayZero = refusalOf(() -> insertByStatement(KEY_DOMAIN_ACCOUNT,
                10_000, 120_000_000, fields, "TXNDATE00000002", "00", "0000", null));
        assertThat(dayZero.getSQLState()).as("state of a violated check").isEqualTo(CHECK_VIOLATION);
        assertThat(dayZero.getConstraint()).as("day zero is refused by the same check")
                .isEqualTo(AUTH_DATE_CHECK);

        assertThat(rowCount(KEY_DOMAIN_ACCOUNT)).as("neither refused row survived").isZero();
    }

    /**
     * Proves the millisecond time column refuses an impossible minute and an impossible second.
     *
     * <p>Assumptions: the nine digits partition as hour, minute, second and millisecond, so the check
     * bounds the minute and the second at 59 each while the hour and the millisecond are bounded by the
     * 235959999 ceiling and by the modulus itself. The two values below are each inside that ceiling and
     * each names nothing: 126000000 is minute 60 of hour twelve, and 120060000 is second 60 of minute
     * zero. Only the minute rule can reject the first and only the second rule can reject the second, so
     * one named constraint is reached by two independent routes.
     *
     * <p>Trade-offs: the two refusals share a method for the reason recorded on the date check above --
     * each insert is its own connection and therefore its own transaction -- and no acceptance is
     * asserted alongside them.
     */
    @Test
    @DisplayName("the time column refuses minute 60 and second 60, naming its own check")
    void theTimeColumnRefusesImpossibleMinutesAndSeconds() {
        Map<String, Object> fields = decode(bytes(CANONICAL));
        insertParent(KEY_DOMAIN_ACCOUNT);

        ServerErrorMessage minuteSixty = refusalOf(() -> insertByStatement(KEY_DOMAIN_ACCOUNT,
                24_110, 126_000_000, fields, "TXNTIME00000001", "00", "0000", null));
        assertThat(minuteSixty.getSQLState()).as("state of a violated check")
                .isEqualTo(CHECK_VIOLATION);
        assertThat(minuteSixty.getConstraint()).as("minute 60 is refused by the time check")
                .isEqualTo(AUTH_TIME_CHECK);

        ServerErrorMessage secondSixty = refusalOf(() -> insertByStatement(KEY_DOMAIN_ACCOUNT,
                24_110, 120_060_000, fields, "TXNTIME00000002", "00", "0000", null));
        assertThat(secondSixty.getSQLState()).as("state of a violated check")
                .isEqualTo(CHECK_VIOLATION);
        assertThat(secondSixty.getConstraint()).as("second 60 is refused by the same check")
                .isEqualTo(AUTH_TIME_CHECK);

        assertThat(rowCount(KEY_DOMAIN_ACCOUNT)).as("neither refused row survived").isZero();
    }

    /**
     * Proves a DECLINED response code is stored, so declined authorizations remain recordable.
     *
     * <p>Assumptions: the copybook offers only ONE condition name on this field,
     * {@code 88 PA-AUTH-APPROVED VALUE '00'} at {@code cpy/CIPAUDTY.cpy} L31, and a single condition
     * name is an approval TEST rather than a value domain -- the reasons a decline carries live in the
     * separate four-character reason field at L32. What closes the domain instead is the producer: the
     * decision paragraph moves exactly two codes and has no third branch, so '00' and '05' are the only
     * values that path can write. The recorded year-boundary image corroborates that a declined code
     * really does reach a stored row, because its second record carries '05' with reason '4100'.
     *
     * <p>Alternatives Considered: promoting the lone condition name to a check requiring '00'. Rejected
     * outright, and this assertion is the fence around that rejection: such a check would make every
     * declined authorization unstorable, and the reference system stores declined authorizations
     * routinely. Without an assertion that a non-'00' code is accepted, nothing would stop a later
     * reader "completing" the pattern from the condition name alone.
     *
     * <p>Assumptions: a blank and SQL null are accepted too, and both are ordinary rather than lenient.
     * The column is nullable because an extract row may carry no reply at all, and a two-character
     * character field that nothing was moved into holds two spaces rather than a null -- so the two
     * states are distinct and both real.
     *
     * @throws SQLException if any of the four accepted values is refused, which would mean the domain
     *     is narrower than the producer requires
     */
    @Test
    @DisplayName("'00', '05', a blank and SQL null are all accepted response codes")
    void theResponseCodeDomainAdmitsApprovedDeclinedBlankAndNull() throws SQLException {
        Map<String, Object> canonical = decode(bytes(CANONICAL));
        Map<String, Object> declined = decode(record(bytes(NEWYEAR_PAIR), 1, SEGMENT_LENGTH));
        assertThat(text(declined, "PA-AUTH-RESP-CODE"))
                .as("the recorded declined code the image carries").isEqualTo("05");
        assertThat(text(declined, "PA-AUTH-RESP-REASON")).as("with its recorded reason")
                .isEqualTo("4100");
        insertParent(REPLY_CODE_ACCOUNT);

        insertByStatement(REPLY_CODE_ACCOUNT, 24_110, 100_000_000, canonical, "TXNRESP00000001",
                "00", "0000", null);
        insertByStatement(REPLY_CODE_ACCOUNT, 24_110, 110_000_000, canonical, "TXNRESP00000002",
                "05", "4100", null);
        insertByStatement(REPLY_CODE_ACCOUNT, 24_110, 120_000_000, canonical, "TXNRESP00000003",
                "  ", "    ", null);
        insertByStatement(REPLY_CODE_ACCOUNT, 24_110, 130_000_000, canonical, "TXNRESP00000004",
                null, null, null);

        assertThat(rowCount(REPLY_CODE_ACCOUNT)).as("all four states were accepted").isEqualTo(4);
        assertThat(storedText("auth_resp_code", REPLY_CODE_ACCOUNT, "TXNRESP00000002"))
                .as("the declined code survived unchanged").contains("05");
        assertThat(storedText("auth_resp_code", REPLY_CODE_ACCOUNT, "TXNRESP00000004"))
                .as("the unsupplied code is SQL null").isEmpty();
    }

    /**
     * Proves a response code outside the producer's two values is refused by its own check.
     *
     * <p>Assumptions: the value below is well formed and simply not one the producer can write, so the
     * only constraint it can offend is the response-code check, and the engine's naming of that check is
     * what the assertion reads. Requiring merely that an exception arrives would be satisfied by a
     * duplicate key or a missing required column just as well, so the identity is asserted rather than
     * the failure.
     *
     * <p>Assumptions: this refusal has a method of its own even though the connection boundary would
     * tolerate company, because the value under test and the constraint under test are one pair and a
     * failure here should name nothing else.
     */
    @Test
    @DisplayName("a third response code is refused, naming the response-code check")
    void aThirdResponseCodeIsRefused() {
        Map<String, Object> fields = decode(bytes(CANONICAL));
        insertParent(REPLY_CODE_ACCOUNT);

        ServerErrorMessage refusal = refusalOf(() -> insertByStatement(REPLY_CODE_ACCOUNT, 24_110,
                140_000_000, fields, "TXNRESP00000005", "99", "0000", null));

        assertThat(refusal.getSQLState()).as("state of a violated check").isEqualTo(CHECK_VIOLATION);
        assertThat(refusal.getConstraint()).as("the constraint the engine named")
                .isEqualTo(RESP_CODE_CHECK);
        assertThat(rowCount(REPLY_CODE_ACCOUNT)).as("the refused row did not survive").isZero();
    }

    /**
     * Proves every reason code the decision paragraph can write is accepted.
     *
     * <p>Assumptions: the domain is EIGHT values and each one is a statement in the same decision
     * paragraph rather than a value taken from a scheme specification -- the approved reason set
     * unconditionally, then one of seven selected only on a decline: not found, insufficient funds, card
     * not active, account closed, card fraud, merchant fraud and a catch-all. The published contract
     * closes the same eight plus null, so the constraint and that contract state one domain; an
     * unconstrained column would let a stored value exist that no response could carry, and the failure
     * would then surface on a READ of somebody else's row rather than on the write that caused it.
     *
     * @throws SQLException if any of the eight is refused, which would mean the schema is narrower than
     *     the decision paragraph
     */
    @Test
    @DisplayName("all eight reason codes the decision paragraph writes are accepted")
    void everyReasonCodeTheProducerWritesIsAccepted() throws SQLException {
        Map<String, Object> fields = decode(bytes(CANONICAL));
        insertParent(REPLY_CODE_ACCOUNT);
        List<String> reasons = List.of("0000", "3100", "4100", "4200", "4300", "5100", "5200",
                "9000");

        // WHY : Assumptions: the eight keys step by ONE MINUTE, which is 100000 in this column's
        //       hour-minute-second-millisecond encoding, and the step size is load-bearing rather than
        //       arbitrary. Stepping by a whole hour-position digit instead would carry the minute
        //       component past 59 by the eighth row, and the domain check would then refuse a row this
        //       assertion expects to be accepted -- so the test would fail on the key rather than on
        //       the reason code it is about.
        int firstTime = 100_000_000;
        int oneMinute = 100_000;
        for (int index = 0; index < reasons.size(); index++) {
            String reason = reasons.get(index);
            String code = "0000".equals(reason) ? "00" : "05";
            insertByStatement(REPLY_CODE_ACCOUNT, 24_110, firstTime + index * oneMinute, fields,
                    String.format("TXNREAS%08d", index), code, reason, null);
        }

        assertThat(rowCount(REPLY_CODE_ACCOUNT)).as("all eight reason codes were accepted")
                .isEqualTo(reasons.size());
    }

    /**
     * Proves a reason code the decision paragraph never writes is refused by its own check.
     *
     * <p>Trade-offs: constraining a reason code makes a future decline reason a migration rather than a
     * code change, and that cost is accepted because the alternative fails in a worse place -- the
     * published response contract already enumerates these eight, so an unconstrained column would
     * admit a row the response schema cannot describe.
     */
    @Test
    @DisplayName("an unlisted reason code is refused, naming the reason-code check")
    void anUnlistedReasonCodeIsRefused() {
        Map<String, Object> fields = decode(bytes(CANONICAL));
        insertParent(REPLY_CODE_ACCOUNT);

        ServerErrorMessage refusal = refusalOf(() -> insertByStatement(REPLY_CODE_ACCOUNT, 24_110,
                150_000_000, fields, "TXNREAS99999999", "05", "7777", null));

        assertThat(refusal.getSQLState()).as("state of a violated check").isEqualTo(CHECK_VIOLATION);
        assertThat(refusal.getConstraint()).as("the constraint the engine named")
                .isEqualTo(RESP_REASON_CHECK);
        assertThat(rowCount(REPLY_CODE_ACCOUNT)).as("the refused row did not survive").isZero();
    }

    /**
     * Proves both money columns accept the widest value the packed declaration admits.
     *
     * <p>Assumptions: this image is one 200-byte record whose two seven-byte packed money spans at
     * offsets 74 and 81 both hold {@code 09 99 99 99 99 99 9C}, which is 9999999999.99 -- ten integer
     * digits and two decimals, the twelve the declaration
     * {@code PIC S9(10)V99 COMP-3} carries. Seven bytes give fourteen nibbles, of which twelve are
     * digits and one is the sign, so the LEADING nibble of the first byte is padding and must be zero;
     * a first byte of {@code 99} rather than {@code 09} would be a thirteen-digit value the declaration
     * cannot hold. The target columns are twelve-digit decimals with two places, which the reference Db2
     * table agrees with at {@code ddl/AUTHFRDS.ddl} L12 and L13.
     *
     * <p>Assumptions: this is one half of a pair and means little alone. The wide precision is asserted
     * here as ACCEPTANCE, and the summary table's narrower eleven-digit money is asserted as refusal of
     * the same magnitude by {@code PendingAuthSummaryRepositoryIT}; together they show the two
     * precisions are genuinely different rather than incidentally so.
     *
     * <p>Assumptions: negating this shape would move the sign into the low nibble of the last byte while
     * the high nibble still carried a digit, so the terminal byte would read as one composite value and
     * not as a bare sign. That is recorded because a reader checking the encoding by eye expects the
     * sign to occupy a byte of its own, and here it does not.
     */
    @Test
    @DisplayName("both money columns accept ten integer digits and two decimals")
    void bothMoneyColumnsAcceptTheWidestDeclaredAmount() {
        Map<String, Object> fields = decode(bytes(WIDEST_AMOUNT));
        BigDecimal widest = new BigDecimal("9999999999.99");
        assertThat(number(fields, "PA-TRANSACTION-AMT")).as("the transaction amount as recorded")
                .isEqualByComparingTo(widest);
        assertThat(number(fields, "PA-APPROVED-AMT")).as("the approved amount as recorded")
                .isEqualByComparingTo(widest);

        int authDate = decodedDate(fields);
        int authTime = decodedTime(fields);
        insertParent(MONEY_ACCOUNT);
        saveAll(List.of(entity(fields, MONEY_ACCOUNT, authDate, authTime, "TXN000000000060")));

        PendingAuthDetail stored = read(() -> repository
                .findById(new PendingAuthDetailKey(MONEY_ACCOUNT, authDate, authTime))
                .map(List::of).orElse(List.of())).get(0);
        assertThat(stored.getTransactionAmount()).as("the stored transaction amount")
                .isEqualByComparingTo(widest);
        assertThat(stored.getApprovedAmount()).as("the stored approved amount")
                .isEqualByComparingTo(widest);
        assertThat(stored.getTransactionAmount().scale()).as("two decimal places are preserved")
                .isEqualTo(2);
    }

    /**
     * Proves trailing blanks in the merchant fields are stored rather than trimmed away.
     *
     * <p>Assumptions: this image is one 200-byte record carrying the seven characters {@code ACME CO}
     * followed by fifteen blanks in the twenty-two-byte merchant name at offset 112, with the city at
     * offset 134 in thirteen bytes, the state at 147 in two and the postal code at 149 in nine, each
     * likewise blank-padded to its declared width. Its packed money span at offset 74 holds 812.45 and
     * its terminal byte is {@code 5C}, whose low nibble is the positive sign -- an incidental
     * confirmation that the record's sign nibbles are the expected ones.
     *
     * <p>Assumptions: the declared width is part of the record contract and the trailing blanks are
     * stored data rather than presentation, so trimming on the way in would change bytes the reference
     * preserves. The reference is explicit about it on this very field: it sets the length half of the
     * varying-length host variable to the FULL declared width regardless of content, moving a constant
     * twenty-two before it moves the text, so the padding travels with the value.
     *
     * <p>Assumptions: the merchant name is the one column here declared as VARYING rather than fixed,
     * mirroring the reference Db2 table where it is the only such column among otherwise fixed ones,
     * and that is exactly what makes it the load-bearing assertion. Its three neighbours are fixed-width
     * character columns, so the engine re-pads them on read and a trim would be invisible; the varying
     * column does NOT re-pad, so if anything trimmed the value the stored width would be seven and this
     * assertion would fail. The neighbours are asserted alongside it so the record's geometry is checked
     * as a whole, but the name is where a trim can actually be caught.
     */
    @Test
    @DisplayName("the merchant name keeps its trailing blanks, and its neighbours their widths")
    void theMerchantFieldsAreStoredAtTheirFullDeclaredWidths() {
        Map<String, Object> fields = decode(bytes(MERCHANT_NO_TRIM));
        assertThat(text(fields, "PA-MERCHANT-NAME")).as("the recorded name at its declared width")
                .isEqualTo("ACME CO               ").hasSize(22);
        assertThat(number(fields, "PA-TRANSACTION-AMT")).as("the incidental positive-sign amount")
                .isEqualByComparingTo(new BigDecimal("812.45"));

        int authDate = decodedDate(fields);
        int authTime = decodedTime(fields);
        insertParent(NO_TRIM_ACCOUNT);
        saveAll(List.of(entity(fields, NO_TRIM_ACCOUNT, authDate, authTime, "TXN000000000050")));

        PendingAuthDetail stored = read(() -> repository
                .findById(new PendingAuthDetailKey(NO_TRIM_ACCOUNT, authDate, authTime))
                .map(List::of).orElse(List.of())).get(0);

        assertThat(stored.getMerchantName()).as("the varying column kept all twenty-two characters")
                .isEqualTo("ACME CO               ").hasSize(22);
        assertThat(stored.getMerchantCity()).as("the city at its fixed width")
                .isEqualTo("PORTLAND     ").hasSize(13);
        assertThat(stored.getMerchantState()).as("the state at its fixed width").isEqualTo("OR")
                .hasSize(2);
        assertThat(stored.getMerchantZip()).as("the postal code at its fixed width")
                .isEqualTo("97201    ").hasSize(9);
    }

    /**
     * Proves the processing code keeps its leading zeros while the entry mode becomes a small integer.
     *
     * <p>Assumptions: two adjacent unsigned display fields of the reference record take two DIFFERENT
     * target types, and the reason is the role each plays rather than the shape each declares. The
     * six-digit processing code at offset 68 becomes a six-character column and the two-digit entry mode
     * at offset 95 becomes a small integer; the reference record holds {@code 000000} and {@code 05}
     * respectively.
     *
     * <p>Alternatives Considered: giving the processing code a numeric type as well, which its
     * all-digits declaration invites. Rejected because its leading zeros are SIGNIFICANT -- the recorded
     * value is six zeros, a distinct code that a numeric type would collapse to the single digit zero,
     * after which nothing could recover the original width. The entry mode has no such property: it is a
     * small bounded quantity where the two characters and the number five carry the same information,
     * and the reference Db2 side already declares it a small integer both in the table and in its host
     * structure. Assumptions: the codec decodes an unsigned display field to an integral value, so the
     * zeros have to be re-established on the way into the character column -- which is what the padding
     * helper does, and why passing the decoded number's own string form would have stored one character
     * and five blanks.
     */
    @Test
    @DisplayName("the processing code keeps six digits while the entry mode is a small integer")
    void theProcessingCodeKeepsLeadingZerosAndTheEntryModeIsNumeric() {
        Map<String, Object> fields = decode(bytes(CANONICAL));
        assertThat(zeroPadded(fields, "PA-PROCESSING-CODE", 6))
                .as("the recorded code re-established at its declared width").isEqualTo("000000");

        int authDate = decodedDate(fields);
        int authTime = decodedTime(fields);
        insertParent(CANONICAL_ACCOUNT);
        saveAll(List.of(entity(fields, CANONICAL_ACCOUNT, authDate, authTime, "TXN000000000001")));

        PendingAuthDetail stored = read(() -> repository
                .findById(new PendingAuthDetailKey(CANONICAL_ACCOUNT, authDate, authTime))
                .map(List::of).orElse(List.of())).get(0);

        assertThat(stored.getProcessingCode()).as("all six digits, none collapsed")
                .isEqualTo("000000").hasSize(6);
        assertThat(stored.getPosEntryMode()).as("the entry mode as a number")
                .isEqualTo((short) 5);
    }

    /**
     * Proves the card expiry is stored as the bare four characters, with no separator.
     *
     * <p>Assumptions: this is a four-character field at offset 40 holding month then year with nothing
     * between them, and the reference record holds {@code 1227}. The oblique a user sees is added by the
     * SCREEN program and belongs to presentation: it copies the first two characters, moves a separator
     * into the third position and copies the remaining two, at {@code cbl/COPAUS1C.cbl} L336 to L338.
     * Storing five characters would carry that presentation into the record and would put every field
     * after offset 43 one byte out, so the segment would no longer measure its declared 200.
     *
     * <p>Assumptions: the pivot pair is loaded here as well, because it is the only image whose two
     * expiries differ, and its second record is the only one anywhere in this directory whose two-digit
     * year lands above the pivot. Its records are 200 bytes each and hold {@code 0826} and {@code 1299}
     * at offset 40. How a two-digit year widens to four is a MAPPING decision and is asserted where the
     * mapper is the subject; what is asserted here is only that the column stores the four characters it
     * was given, because the reference never widens a year at all and a repository test that took a
     * position on the century would be taking one the record does not state.
     */
    @Test
    @DisplayName("the card expiry stores four characters with no separator")
    void theCardExpiryIsStoredAsBareMonthAndYear() {
        Map<String, Object> canonical = decode(bytes(CANONICAL));
        assertThat(text(canonical, "PA-CARD-EXPIRY-DATE")).as("the recorded expiry")
                .isEqualTo("1227").hasSize(4).doesNotContain("/");

        byte[] pivotImage = bytes(DATE_FORMATS);
        Map<String, Object> belowPivot = decode(record(pivotImage, 0, SEGMENT_LENGTH));
        Map<String, Object> abovePivot = decode(record(pivotImage, 1, SEGMENT_LENGTH));
        assertThat(text(belowPivot, "PA-CARD-EXPIRY-DATE")).as("the expiry below the pivot")
                .isEqualTo("0826");
        assertThat(text(abovePivot, "PA-CARD-EXPIRY-DATE")).as("the expiry above the pivot")
                .isEqualTo("1299");

        insertParent(PIVOT_ACCOUNT);
        saveAll(List.of(
                entity(belowPivot, PIVOT_ACCOUNT, decodedDate(belowPivot), decodedTime(belowPivot),
                        text(belowPivot, "PA-TRANSACTION-ID")),
                entity(abovePivot, PIVOT_ACCOUNT, decodedDate(abovePivot), decodedTime(abovePivot),
                        text(abovePivot, "PA-TRANSACTION-ID"))));

        List<PendingAuthDetail> stored = read(() -> repository
                .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(PIVOT_ACCOUNT));
        assertThat(stored.stream().map(PendingAuthDetail::getCardExpiryDate).toList())
                .as("newest first, each expiry four bare characters")
                .containsExactly("1299", "0826");
        assertThat(stored).allSatisfy(row ->
                assertThat(row.getCardExpiryDate()).as("no separator reached the column").hasSize(4));
    }

    /**
     * Proves the report date stores an eight-character value and a SQL null as distinct states.
     *
     * <p>Assumptions: this image is 600 bytes of three 200-byte records that share Julian 24120 and step
     * hourly, whose eight-byte report-date span at offset 175 holds {@code 04/29/24} in the first two
     * records and eight blanks in the third. The reference produces that eight-character form by asking
     * the transaction monitor for the current time and formatting it as month, day and two-digit year
     * with separators, at {@code cbl/COPAUS2C.cbl} L91 to L101.
     *
     * <p>Assumptions: the target column on THIS table is eight characters and not a date, and the
     * distinction matters because one concept has three widths across the reference. The segment form is
     * the eight characters above; the Db2 host structure widens it to ten at {@code dcl/AUTHFRDS.dcl}
     * L84; and the Db2 column itself is a real date at {@code ddl/AUTHFRDS.ddl} L25. The migration keeps
     * the character form here and the real date on the fraud table it migrates from that DDL, so the two
     * representations still differ in the target exactly as they differ in the reference, and the
     * conversion happens at the one boundary that writes the relational row.
     *
     * <p>Assumptions: a blank and a null are BOTH ordinary and neither is invented. A row loaded from an
     * extract that carried no value at all is null here, and a row the producer blanked is eight spaces
     * -- the producer blanks the field unconditionally on the path that creates every detail row -- so
     * the column is nullable and the two states are asserted separately. The one-plus-one-plus-eight
     * display form a screen builds from the flag and this date is presentation and is deliberately not
     * asserted anywhere in this file.
     *
     * @throws SQLException if either state is refused, which would mean the column cannot hold what the
     *     producer writes
     */
    @Test
    @DisplayName("the report date holds eight characters or SQL null as distinct states")
    void theReportDateHoldsEightCharactersOrNull() throws SQLException {
        byte[] image = bytes(REPORT_DATE_STATES);
        Map<String, Object> reported = decode(record(image, 0, SEGMENT_LENGTH));
        Map<String, Object> blanked = decode(record(image, 2, SEGMENT_LENGTH));
        assertThat(text(reported, "PA-FRAUD-RPT-DATE")).as("the recorded eight-character form")
                .isEqualTo("04/29/24").hasSize(8);
        assertThat(text(blanked, "PA-FRAUD-RPT-DATE")).as("the recorded blank form")
                .isEqualTo("        ").hasSize(8);
        insertParent(REPORT_DATE_ACCOUNT);

        insertByStatement(REPORT_DATE_ACCOUNT, decodedDate(reported), decodedTime(reported),
                reported, "TXN000000000040", "00", "0000", text(reported, "PA-FRAUD-RPT-DATE"));
        insertByStatement(REPORT_DATE_ACCOUNT, decodedDate(blanked), decodedTime(blanked), blanked,
                "TXN000000000042", "00", "0000", null);

        assertThat(storedText("fraud_rpt_date", REPORT_DATE_ACCOUNT, "TXN000000000040"))
                .as("the populated report date survived at its declared width")
                .contains("04/29/24");
        assertThat(storedText("fraud_rpt_date", REPORT_DATE_ACCOUNT, "TXN000000000042"))
                .as("the unsupplied report date is SQL null and not a zero date").isEmpty();
    }

    /**
     * Proves the trailing filler reaches no column while the record still measures its declared length.
     *
     * <p>Assumptions: the filler is seventeen bytes at offset 183, the last field of the segment, and it
     * pads the record to the 200 bytes the descriptor declares rather than holding data. It is DROPPED
     * from the target table, and the drop is recorded in the data-model mapping document; nothing in the
     * schema corresponds to it, which is what this assertion establishes by counting.
     *
     * <p>Assumptions: the 200-byte length has four independent corroborations and this assertion uses
     * three of them. Summing the declared field widths of {@code cpy/CIPAUDTY.cpy} L19 to L54 gives 200,
     * which is what the registry's own geometry check enforces; the descriptor declares
     * {@code SEGM NAME=PAUTDTL1,PARENT=((PAUTSUM0,)),BYTES=200} at {@code ims/DBPAUTP0.dbd} L36; the
     * sequential form of the same record declares {@code RECORD=(200),RECFM=F} at
     * {@code ims/PADFLDBD.DBD} L27 under a sequential access method; and every recorded image in this
     * directory is a whole number of 200-byte records with no terminator allowance.
     *
     * <p>Assumptions: the field at offsets 14 to 19 is REAL CONTENT and not padding, which is worth
     * stating because it sits between two date-like fields and reads as though it could be slack. It is
     * the original time of the authorization, and the record carries it in addition to the complemented
     * key time; treating it as filler would drop a value the reference reads.
     */
    @Test
    @DisplayName("the trailing filler reaches no column and the record still measures 200 bytes")
    void theTrailingFillerReachesNoColumn() {
        byte[] segment = bytes(CANONICAL);
        assertThat(segment).as("the recorded segment at its declared length")
                .hasSize(SEGMENT_LENGTH);
        assertThat(CopybookLayout.layout(LAYOUT).reclen()).as("the registered record length")
                .isEqualTo(SEGMENT_LENGTH);

        CopybookLayout.FieldSpec filler = CopybookLayout.layout(LAYOUT).fields().stream()
                .filter(field -> "FILLER".equals(field.name())).findFirst().orElseThrow();
        assertThat(filler.start()).as("where the filler begins").isEqualTo(FILLER_OFFSET);
        assertThat(filler.length()).as("how wide it is").isEqualTo(FILLER_WIDTH);
        assertThat(filler.start() + filler.length()).as("and that it closes the record")
                .isEqualTo(SEGMENT_LENGTH);
        assertThat(CopybookLayout.layout(LAYOUT).fields().stream()
                .filter(field -> "PA-AUTH-ORIG-TIME".equals(field.name())).findFirst()
                .orElseThrow().start()).as("the real content between the date fields")
                .isEqualTo(14);

        Map<String, Object> fields = decode(segment);
        int authDate = decodedDate(fields);
        int authTime = decodedTime(fields);
        insertParent(CANONICAL_ACCOUNT);
        saveAll(List.of(entity(fields, CANONICAL_ACCOUNT, authDate, authTime, "TXN000000000001")));

        assertThat(columnCount()).as("the table carries one column per mapped field and no filler")
                .isEqualTo(CopybookLayout.layout(LAYOUT).fields().size());
    }

    /**
     * Counts the columns the detail table declares.
     *
     * <p>Assumptions: the count is compared against the segment's field count rather than against a
     * literal, so the two move together. They coincide at twenty-eight for a reason worth stating: the
     * segment declares twenty-eight fields including its trailing filler, and the table declares
     * twenty-eight columns being those twenty-seven mapped fields plus the synthesized account. The
     * filler is dropped and the account is added, so one departure offsets the other exactly -- which
     * means this assertion pins BOTH facts at once and would fail if either changed alone.
     *
     * @return the number of columns currently declared on the detail table
     * @throws IllegalStateException if the catalogue query itself fails, which is a broken harness
     *     rather than an assertion outcome
     */
    private static int columnCount() {
        String sql = """
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = ? AND table_name = 'pending_auth_detail'
                """;
        try (Connection connection = connection();
                PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, SCHEMA_NAME);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).as("the column count query returned no row").isTrue();
                return rows.getInt(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("column count query failed", failure);
        }
    }
}
