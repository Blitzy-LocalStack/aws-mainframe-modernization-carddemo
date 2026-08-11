package com.carddemo.batch.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the batch module's single PostgreSQL data source and guards its four-schema session posture.
 *
 * <h2>What this class owns, and what it deliberately does not</h2>
 *
 * <p>It owns three things: the construction of the canonical {@code SET search_path} statement from
 * the ordered schema list this module declares it needs, the pool factory itself, and the start-up
 * guards that refuse a configuration disagreeing with the contract recorded below. It declares no
 * entity, no repository, no query and no business rule. The posting validation chain and the
 * interest control break belong to {@code com.carddemo.batch.service}, the units of work to
 * {@code com.carddemo.batch.job}, the record-layout and sign-overpunch decoding to
 * {@code com.carddemo.batch.mapper}, and the table mappings to {@code com.carddemo.batch.domain}.
 * It also declares no published-contract, identity or request-filtering bean, because the package
 * charter beside this file closes the package at three configuration classes and records there why
 * no resource-server chain exists in this module at all.</p>
 *
 * <h2>Four schemas, one data source, one commit</h2>
 *
 * <p>This is the module's defining oddity and the reason this class carries guards its siblings do
 * not need. Every other bounded context in the reactor reaches exactly one schema, so a search-path
 * mistake there has nothing to resolve against and fails loudly at the first unqualified statement.
 * This module reaches four from one pool, under a role whose privileges are deliberately unequal, so
 * an unqualified statement under a reordered path would still resolve -- against a real table in the
 * wrong schema -- and the failure would present as a plausible row rather than as an error.</p>
 *
 * <ul>
 *   <li>{@code batch} is OWNED and is the only schema this module migrates. It is created for the
 *       batch owner at {@code data-migration/sql/V0__schemas_and_roles.sql:713}, and the runtime
 *       role receives {@code SELECT, INSERT, UPDATE} on its tables at that file's line 973 while
 *       {@code CREATE} is explicitly revoked from it at line 966.</li>
 *   <li>{@code ledger} is owned by {@code transaction-service} and reached under a cross-schema
 *       write grant, {@code SELECT, INSERT, UPDATE} at
 *       {@code data-migration/sql/V0__schemas_and_roles.sql:1115}.</li>
 *   <li>{@code account} is owned by {@code account-service} and reached under a grant narrowed to
 *       one table: schema-wide {@code UPDATE} is revoked at
 *       {@code data-migration/sql/V0__schemas_and_roles.sql:1166}, {@code SELECT} is granted at line
 *       1168, and {@code UPDATE} is granted on {@code account.accounts} alone at line 1179.</li>
 *   <li>{@code reference} is SELECT ONLY, granted at
 *       {@code data-migration/sql/V0__schemas_and_roles.sql:1215}, with no write grant of any kind.
 *       {@code DisclosureGroupRepository} extends the narrow
 *       {@code org.springframework.data.repository.Repository} rather than a CRUD interface so that
 *       a mutator is not expressible against that schema at compile time.</li>
 * </ul>
 *
 * <p>Assumptions: the ordered path is {@code batch, ledger, account, reference} with the owned
 * schema FIRST, and the ordering is part of the contract rather than a preference. The grants above
 * are created by {@code data-migration/sql/V0__schemas_and_roles.sql} and by the services that own
 * the three foreign schemas; this class configures the path and creates none of the privileges it
 * depends on.</p>
 *
 * <h2>The transaction posture, and what silently breaks if it is widened</h2>
 *
 * <p>One non-distributed data source, the single transaction manager Spring Boot auto-configures
 * over the one entity manager factory, and no coordinator of any kind: no XA data source, no JTA
 * transaction manager, no distributed-transaction coordinator and no second transaction manager
 * bean. This class therefore declares no {@code PlatformTransactionManager} at all, and the absence
 * is deliberate rather than an omission.</p>
 *
 * <p>Assumptions: that posture is what keeps the reference's three writes one atomic commit.
 * {@code app/cbl/CBTRN02C.cbl:424} opens {@code 2000-POST-TRANSACTION.}, and its line 440 performs
 * {@code 2700-UPDATE-TCATBAL}, line 441 performs {@code 2800-UPDATE-ACCOUNT-REC} and line 442
 * performs {@code 2900-WRITE-TRANSACTION-FILE}, with the paragraph bodies at lines 467, 545 and 562.
 * Those three writes land in {@code ledger.transaction_category_balances}, {@code account.accounts}
 * and {@code ledger.transactions} -- two schemas, one commit. If a second transaction manager ever
 * appears in this context, that one commit silently becomes two, and a run could then leave a posted
 * transaction whose category balance has not moved. Nothing would fail; the state would simply
 * become observable, and the golden-master comparison would report it as a parity difference.</p>
 *
 * <p>Alternatives Considered: a saga with compensating reversals, and a transactional outbox with
 * reversal, were both evaluated for the posting unit of work and both rejected on that same ground
 * -- each replaces one atomic commit with committed steps plus reversals, which makes the
 * partial-posting state above observable when no such state exists in the reference. Two-phase
 * commit was rejected for a different reason: all three writes land in one PostgreSQL database, so
 * there is nothing to coordinate. The migration plan records the alternative it took at its section
 * 0.4.1.3, the one documented exception to database-per-service purity, which is the narrowly-scoped
 * cross-schema grant enumerated above.</p>
 *
 * <h2>Why this module's pool posture differs from an online service's</h2>
 *
 * <p>A batch task is not a scaled-down web service. It is invoked once through a synchronous
 * run-task call from an orchestrator state, runs one unit of work and exits, so there is no warm-up
 * phase to ramp into and no business request surface. Four settings follow from that and are each
 * guarded below: auto-commit is off so a cursor actually streams, the pool has a floor rather than a
 * ceiling-shaped default, initialisation fails fast, and leak detection is on. A fifth follows by
 * omission: a session {@code statement_timeout} is deliberately NOT tightened here, because the
 * outer bound on a batch step is the orchestrator's per-state timeout, which every state carries per
 * the migration plan's section 0.4.1.7.</p>
 *
 * <h2>The property-key contract this class reads</h2>
 *
 * <p>Assumptions: every key below is read by this class and supplied by
 * {@code services/batch-service/src/main/resources/application.yml} and its {@code -dev},
 * {@code -prod} and {@code -test} profiles. The required value or shape is stated with each key,
 * because a key named without its required shape is not an actionable contract. A key marked
 * REQUIRED carries no fallback on purpose: a fallback would let the task start against a value
 * nobody chose, and where this class's assumed fallback differed from the framework's real default
 * the guard would report a value the pool was not using.</p>
 *
 * <ul>
 *   <li>{@code spring.datasource.url}, {@code spring.datasource.username} and
 *       {@code spring.datasource.password} -- REQUIRED, and read by the framework rather than by
 *       this class. Their values originate as infrastructure outputs surfaced through Parameter
 *       Store and Secrets Manager, so no literal, no fallback and no example value for any of the
 *       three appears in this file or in any profile.</li>
 *   <li>{@code spring.datasource.hikari.connection-init-sql} -- REQUIRED, and exactly one statement
 *       of the form {@code SET search_path TO <schema>[, <schema>...]} naming the ordered list below
 *       and nothing after it.</li>
 *   <li>{@code carddemo.batch.datasource.search-path} -- the ordered, comma-separated schema list
 *       this module declares it needs, defaulting to {@code batch,ledger,account,reference}. The
 *       owned schema must be first.</li>
 *   <li>{@code spring.datasource.hikari.auto-commit} -- REQUIRED, and must be {@code false}.</li>
 *   <li>{@code spring.datasource.hikari.maximum-pool-size} -- REQUIRED, and at least three.</li>
 *   <li>{@code spring.datasource.hikari.minimum-idle} -- an integer from zero up to the ceiling
 *       above, defaulting to the ceiling.</li>
 *   <li>{@code spring.datasource.hikari.initialization-fail-timeout} -- positive milliseconds,
 *       defaulting to HikariCP's own default of one.</li>
 *   <li>{@code spring.datasource.hikari.connection-timeout} -- positive milliseconds, defaulting to
 *       HikariCP's own default of thirty thousand.</li>
 *   <li>{@code spring.datasource.hikari.leak-detection-threshold} -- positive milliseconds. Its
 *       fallback is HikariCP's own default of zero, which this class refuses, so an unset key fails
 *       the task rather than silently disabling the detector.</li>
 *   <li>{@code spring.datasource.hikari.data-source-properties.options} -- REQUIRED, and must bound
 *       both {@code lock_timeout} and {@code idle_in_transaction_session_timeout} while naming no
 *       {@code statement_timeout}.</li>
 *   <li>{@code spring.flyway.default-schema} and {@code spring.flyway.schemas} -- REQUIRED, and both
 *       must name the owned schema and no other, which is what keeps migration confined to it.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto} -- REQUIRED, and must be a mode that emits no DDL.
 *       The deployed profiles set {@code validate}; the harness profile sets {@code none}.</li>
 * </ul>
 *
 * <p>Assumptions: four further keys are set by those profiles, are NOT read by this class, and are
 * named here because a reader looking for them in this file would otherwise conclude they were
 * forgotten. {@code spring.flyway.locations} keeps migration at this module's own
 * {@code classpath:db/migration}; {@code spring.flyway.create-schemas} is false in a deployed
 * environment because {@code data-migration/sql/V0__schemas_and_roles.sql} pre-creates every schema
 * and role; {@code spring.flyway.baseline-on-migrate} is false because that file creates the owned
 * schema EMPTY, so a baseline would mark this module's own migration as already applied and skip it;
 * and {@code spring.jpa.open-in-view} is false because no persistence context in this module should
 * outlive the transaction that opened it. Assumptions: {@code org.flywaydb:flyway-database-postgresql}
 * is a mandatory runtime companion of {@code org.flywaydb:flyway-core} rather than an optional extra
 * -- PostgreSQL support moved out of core, so core alone fails at RUN time with a missing-plugin
 * error and never at compile time. {@code services/batch-service/pom.xml} declares both, and neither
 * is re-pinned there because both versions are parent-managed.</p>
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    /** The property prefix the pool's own settings are bound from. */
    private static final String HIKARI_PREFIX = "spring.datasource.hikari";

    /** The key carrying the one statement the pool runs on every physical connection it opens. */
    private static final String INIT_SQL_KEY = HIKARI_PREFIX + ".connection-init-sql";

    /** The key carrying the ordered schema list this module declares it needs. */
    private static final String SEARCH_PATH_KEY = "carddemo.batch.datasource.search-path";

    /** The key carrying the pool's auto-commit posture. */
    private static final String AUTO_COMMIT_KEY = HIKARI_PREFIX + ".auto-commit";

    /** The key carrying the pool's connection ceiling. */
    private static final String MAX_POOL_SIZE_KEY = HIKARI_PREFIX + ".maximum-pool-size";

    /** The key carrying the number of connections the pool keeps ready. */
    private static final String MIN_IDLE_KEY = HIKARI_PREFIX + ".minimum-idle";

    /** The key bounding how long pool start-up waits for its first connection. */
    private static final String INIT_FAIL_TIMEOUT_KEY = HIKARI_PREFIX + ".initialization-fail-timeout";

    /** The key bounding how long a caller waits for a connection from the pool. */
    private static final String CONNECTION_TIMEOUT_KEY = HIKARI_PREFIX + ".connection-timeout";

    /** The key after which a checked-out connection is reported as possibly leaked. */
    private static final String LEAK_DETECTION_KEY = HIKARI_PREFIX + ".leak-detection-threshold";

    /** The key deciding whether the pool commits its own initialisation statement. */
    private static final String ISOLATE_INTERNAL_QUERIES_KEY =
            HIKARI_PREFIX + ".isolate-internal-queries";

    /** The key carrying the driver-level session parameters applied at connection start-up. */
    private static final String SESSION_OPTIONS_KEY = HIKARI_PREFIX + ".data-source-properties.options";

    /** The key naming the one schema this module migrates. */
    private static final String MIGRATION_SCHEMA_KEY = "spring.flyway.default-schema";

    /** The key listing every schema migration is allowed to manage. */
    private static final String MIGRATION_SCHEMAS_KEY = "spring.flyway.schemas";

    /** The key carrying the provider's schema-management mode. */
    private static final String DDL_AUTO_KEY = "spring.jpa.hibernate.ddl-auto";

    /** The ordered schema list this module falls back to when the search-path key is unset. */
    private static final String DEFAULT_SEARCH_PATH = "batch,ledger,account,reference";

    /** HikariCP's own default initialisation-failure timeout, in milliseconds. */
    private static final String HIKARI_DEFAULT_INIT_FAIL_TIMEOUT_MS = "1";

    /** HikariCP's own default connection-acquisition timeout, in milliseconds. */
    private static final String HIKARI_DEFAULT_CONNECTION_TIMEOUT_MS = "30000";

    /** HikariCP's own default leak-detection threshold, which disables the detector. */
    private static final String HIKARI_DEFAULT_LEAK_DETECTION_MS = "0";

    /** The smallest pool ceiling under which a streaming step cannot deadlock itself. */
    private static final int MINIMUM_VIABLE_POOL_SIZE = 3;

    /** The session parameter that bounds how long a statement waits for a contended row lock. */
    private static final String LOCK_TIMEOUT_SETTING = "lock_timeout";

    /** The session parameter that bounds how long a session may hold an idle open transaction. */
    private static final String IDLE_IN_TRANSACTION_SETTING = "idle_in_transaction_session_timeout";

    /** The session parameter this module deliberately leaves unbounded at session level. */
    private static final String STATEMENT_TIMEOUT_SETTING = "statement_timeout";

    /** The schema name that must never enter the search path. */
    private static final String DEFAULT_NAMESPACE = "public";

    /**
     * Any mention of the default namespace as a whole word inside a normalised statement.
     *
     * <p>Assumptions: the word boundary is what keeps this from matching a legitimately named schema
     * that merely contains those letters, so a schema called {@code publications} is not refused.</p>
     */
    private static final Pattern DEFAULT_NAMESPACE_MENTION =
            Pattern.compile(".*\\b" + DEFAULT_NAMESPACE + "\\b.*");

    /** The provider schema-management modes that emit DDL and are therefore refused. */
    private static final Set<String> DDL_EMITTING_MODES =
            Set.of("create", "create-drop", "create-only", "update", "drop");

    /** The leading words every accepted search-path statement begins with, once normalised. */
    private static final String SEARCH_PATH_PREFIX = "set search_path";

    /**
     * The whole accepted shape of a search-path statement, anchored so nothing may follow it.
     *
     * <p>Assumptions: the capture group holds the comma-separated schema list, each element an
     * unquoted lower-case identifier. Anchoring both ends is what refuses a second statement
     * appended after a semicolon, and refusing quoted identifiers is what keeps one written form.</p>
     */
    private static final Pattern SEARCH_PATH_STATEMENT = Pattern.compile(
            "^set search_path (?:to|=) ([a-z0-9_$]+(?: *, *[a-z0-9_$]+)*)$");

    /** One {@code -c name=value} session parameter inside the driver's options string. */
    private static final Pattern SESSION_OPTION =
            Pattern.compile("-c\\s+([a-z_]+)\\s*=\\s*([A-Za-z0-9]+)");

    /** A run of one or more whitespace characters, collapsed during normalisation. */
    private static final String WHITESPACE_RUN = "\\s+";

    /** Every character that is not a decimal digit, stripped when testing a timeout for zero. */
    private static final String NON_DIGIT = "[^0-9]";

    /**
     * Reads the session posture a pooled connection actually carries, in one round trip.
     *
     * <p>Assumptions: the backend process identifier is selected alongside the posture so the
     * verifier can prove two readings came from two DISTINCT physical connections rather than from
     * the same one handed out twice.</p>
     */
    private static final String SESSION_POSTURE_QUERY = "SELECT current_setting('search_path'),"
            + " current_schema(), current_setting('" + LOCK_TIMEOUT_SETTING + "'),"
            + " current_setting('" + IDLE_IN_TRANSACTION_SETTING + "'), pg_backend_pid()";

    /** The ordered schema list this module resolves unqualified objects against. */
    private final List<String> searchPath;

    /** The one schema this module owns and migrates, which must lead the search path. */
    private final String ownedSchema;

    /**
     * Establishes the schema-resolution contract and refuses any configuration that breaks it.
     *
     * <p>Every check here runs while the context is still being built, which is the only moment at
     * which refusing costs nothing: the orchestrator sees a task that failed to start, its per-state
     * catch handler routes the failure to notification, and no record of the feed has been read.</p>
     *
     * @param connectionInitSql the {@link String} statement, from
     *     {@code spring.datasource.hikari.connection-init-sql}, that the pool runs on every physical
     *     connection it opens; must be exactly one {@code SET search_path} statement
     * @param declaredSearchPath the ordered {@code List<String>} of schema names, from
     *     {@code carddemo.batch.datasource.search-path}, that this module declares it needs, with the
     *     owned schema first
     * @param migrationSchema the {@link String} name of the one schema this module migrates, from
     *     {@code spring.flyway.default-schema}; must not be blank
     * @param migrationSchemas the {@code List<String>} of schemas migration may manage, from
     *     {@code spring.flyway.schemas}; must name the migration schema and nothing else
     * @param schemaManagementMode the {@link String} provider mode, from
     *     {@code spring.jpa.hibernate.ddl-auto}; must be a mode that emits no DDL
     * @throws IllegalStateException if the declared statement is not exactly the canonical statement
     *     built from the ordered list, if the owned schema does not lead that list, if migration
     *     reaches beyond the owned schema, or if the provider is configured to emit DDL
     */
    public DataSourceConfig(
            @Value("${" + INIT_SQL_KEY + "}") String connectionInitSql,
            @Value("${" + SEARCH_PATH_KEY + ":" + DEFAULT_SEARCH_PATH + "}")
                    List<String> declaredSearchPath,
            @Value("${" + MIGRATION_SCHEMA_KEY + "}") String migrationSchema,
            @Value("${" + MIGRATION_SCHEMAS_KEY + "}") List<String> migrationSchemas,
            @Value("${" + DDL_AUTO_KEY + "}") String schemaManagementMode) {

        this.searchPath = requireOrderedSearchPath(declaredSearchPath);
        this.ownedSchema = requireSchemaName(migrationSchema, MIGRATION_SCHEMA_KEY);

        // WHAT: the cross-check that keeps the migration schema and the resolution schema one schema.
        // WHY : Assumptions: two independent settings decide where an unqualified object lands. The
        //       pool's statement decides where a runtime statement resolves, and
        //       spring.flyway.default-schema decides where migration writes. Each looks correct on
        //       its own, so a disagreement would migrate one schema and write into another; checking
        //       that the owned schema LEADS the path proves the two agree rather than deriving one
        //       from the other. Leading matters specifically because the framework's job-repository
        //       tables carry an unqualified default prefix and resolve through the path's first
        //       entry, which is where db/migration/V1__batch.sql creates them.
        requireOwnedSchemaLeadsSearchPath(this.searchPath, this.ownedSchema);

        requireDeclaredSearchPathStatement(connectionInitSql, this.searchPath);
        requireMigrationConfinedTo(migrationSchemas, this.ownedSchema);
        requireNoGeneratedDdl(schemaManagementMode);
    }

    /**
     * Builds the pool and refuses a pool posture a run-and-exit batch task cannot rely on.
     *
     * <p>Assumptions: the values checked here are the values the pool will use. Each parameter's
     * fallback is HikariCP's OWN documented default, so where a key is absent this method validates
     * what the library will actually apply rather than a figure invented here. The one deliberate
     * exception is the leak-detection threshold, whose library default disables the detector and is
     * therefore refused, so an unset key fails the task instead of silently switching it off.</p>
     *
     * @param properties the {@link DataSourceProperties} carrying the driver, URL, username and
     *     password the framework resolved from external configuration; must not be {@code null}
     * @param autoCommit the {@code boolean} pool auto-commit posture from
     *     {@code spring.datasource.hikari.auto-commit}; must be {@code false}
     * @param maximumPoolSize the {@code int} connection ceiling from
     *     {@code spring.datasource.hikari.maximum-pool-size}; must be at least three
     * @param minimumIdle the {@code int} number of connections kept ready from
     *     {@code spring.datasource.hikari.minimum-idle}, defaulting to the ceiling; must not exceed it
     * @param initializationFailTimeoutMillis the {@code long} milliseconds pool start-up waits for
     *     its first connection, from {@code spring.datasource.hikari.initialization-fail-timeout};
     *     must be positive
     * @param connectionTimeoutMillis the {@code long} milliseconds a caller waits for a connection,
     *     from {@code spring.datasource.hikari.connection-timeout}; must be positive
     * @param leakDetectionThresholdMillis the {@code long} milliseconds after which a checked-out
     *     connection is reported, from {@code spring.datasource.hikari.leak-detection-threshold};
     *     must be positive
     * @param isolateInternalQueries the {@code boolean} posture from
     *     {@code spring.datasource.hikari.isolate-internal-queries}, defaulting to {@code true};
     *     must be {@code true}, because it is what commits the schema pinning
     * @param sessionOptions the {@link String} driver options from
     *     {@code spring.datasource.hikari.data-source-properties.options}; must bound the lock and
     *     idle-in-transaction timeouts and must name no statement timeout
     * @return the configured {@link HikariDataSource}, whose remaining settings are bound from
     *     {@code spring.datasource.hikari}, never {@code null}
     * @throws IllegalStateException if auto-commit is on, if internal-query isolation is off, if the
     *     ceiling is below three, if the idle floor exceeds the ceiling, if any of the three timeouts
     *     is not positive, or if the session options leave a required bound off or add a statement
     *     timeout
     */
    @Bean
    @ConfigurationProperties(HIKARI_PREFIX)
    public HikariDataSource dataSource(
            DataSourceProperties properties,
            @Value("${" + AUTO_COMMIT_KEY + "}") boolean autoCommit,
            @Value("${" + MAX_POOL_SIZE_KEY + "}") int maximumPoolSize,
            @Value("${" + MIN_IDLE_KEY + ":${" + MAX_POOL_SIZE_KEY + "}}") int minimumIdle,
            @Value("${" + INIT_FAIL_TIMEOUT_KEY + ":" + HIKARI_DEFAULT_INIT_FAIL_TIMEOUT_MS + "}")
                    long initializationFailTimeoutMillis,
            @Value("${" + CONNECTION_TIMEOUT_KEY + ":" + HIKARI_DEFAULT_CONNECTION_TIMEOUT_MS + "}")
                    long connectionTimeoutMillis,
            @Value("${" + LEAK_DETECTION_KEY + ":" + HIKARI_DEFAULT_LEAK_DETECTION_MS + "}")
                    long leakDetectionThresholdMillis,
            @Value("${" + ISOLATE_INTERNAL_QUERIES_KEY + ":true}") boolean isolateInternalQueries,
            @Value("${" + SESSION_OPTIONS_KEY + "}") String sessionOptions) {

        // WHAT: the auto-commit contract the module's streaming readers depend on.
        // WHY : Assumptions: the driver opens a server-side cursor only when a positive fetch size
        //       and a non-auto-commit connection BOTH hold, so with auto-commit on the fetch-size
        //       hint at DailyTransactionRepository:219 does nothing and the driver materialises the
        //       whole daily feed in the client. Nothing errors in that state; the task simply gets
        //       slower and eventually exhausts the heap, which is why this is a gate rather than a
        //       note. Trade-offs: switching it off also stops the framework toggling it per
        //       transaction, and the accepted cost is that a Stream finder must be consumed INSIDE a
        //       transaction and closed in a try-with-resources, since an unclosed one pins its
        //       connection for the whole step.
        requireAutoCommitDisabled(autoCommit);

        // WHAT: the setting that makes the pinning statement outlive the transaction it runs in.
        // WHY : Assumptions: in PostgreSQL a SET is TRANSACTIONAL -- rolling back the transaction it
        //       ran in discards it. HikariCP runs the initialisation statement while setting up each
        //       physical connection, and with auto-commit off that statement opens a transaction
        //       nobody commits, so the first rollback on that connection silently reverts the search
        //       path to the server default. This was measured, not inferred: with this setting absent
        //       the pool reported holding the correct statement while a pooled connection resolved
        //       ["$user", public], because the provider had already opened, used and returned that
        //       connection while reading the database metadata. Switching internal-query isolation on
        //       makes HikariCP commit after the initialisation statement, which is what makes the
        //       pinning durable for the life of the session.
        // WHY : Alternatives Considered: appending an explicit commit to the statement itself was
        //       rejected because it makes the value two statements, which the single-statement guard
        //       refuses and which would depend on the driver accepting a simple-query batch. Setting
        //       the path through the driver's own startup options was rejected as a change of
        //       mechanism rather than a repair of this one -- it would move the pinning into the same
        //       string that carries the session bounds and out of the setting the plan names.
        requireIsolatedInternalQueries(isolateInternalQueries);

        // WHAT: the pool floor, which is a correctness bound rather than a sizing preference.
        // WHY : Assumptions: a streaming cursor pins one connection for a whole step while the batch
        //       framework commits step and job execution state in a SEPARATE transaction, so a
        //       ceiling of one self-deadlocks and two leaves nothing for migration, the health probe
        //       or the two-connection start-up proof below. Three is the smallest ceiling under which
        //       all of those can coexist.
        // WHY : Trade-offs: the idle floor DEFAULTS to the ceiling, which removes the ramp-up a
        //       long-lived server benefits from and is the right shape here because the task runs one
        //       unit of work and exits, and would otherwise pay pool growth cost inside the batch
        //       window. That default is also HikariCP's own for this setting, so the module inherits
        //       library behaviour rather than inventing it. The shipped profiles deliberately set the
        //       floor BELOW the ceiling instead -- the development profile at zero so an idle pool
        //       cannot hold a scale-to-zero database awake, the production profile at an intermediate
        //       value -- and that is admitted rather than refused, because only the ceiling carries a
        //       correctness bound. What this method does refuse is a floor ABOVE the ceiling, which
        //       HikariCP otherwise normalises silently and which is always an operator error.
        requireViablePoolBounds(maximumPoolSize, minimumIdle);

        // WHAT: the fail-fast and leak-visibility bounds for a task with no operator at the console.
        // WHY : Assumptions: a positive initialisation timeout makes an unreachable database fail at
        //       START-UP, where the orchestrator records a clean failed state, rather than part-way
        //       through a step with a feed partly read; a zero value would let the pool start empty
        //       and a negative one would skip the check entirely. The leak threshold is positive
        //       because an unclosed Stream pins its connection for the whole step and that is
        //       otherwise invisible.
        // WHY : Trade-offs: the idle timeout and maximum lifetime are deliberately NOT tuned here.
        //       They govern how a pool behaves over hours of continuous service, and this process
        //       exits when its unit of work ends, so a value chosen for them would imply a lifecycle
        //       this task does not have. They are left at library defaults and named as inert rather
        //       than given invented figures.
        requirePositiveMillis(initializationFailTimeoutMillis, INIT_FAIL_TIMEOUT_KEY);
        requirePositiveMillis(connectionTimeoutMillis, CONNECTION_TIMEOUT_KEY);
        requirePositiveMillis(leakDetectionThresholdMillis, LEAK_DETECTION_KEY);

        requireStreamingSessionOptions(sessionOptions);

        HikariDataSource pool =
                properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();

        // WHAT: the same setting applied to the instance, so the guard above is not merely advice.
        // WHY : Assumptions: property binding runs after this method returns and applies only keys
        //       that are actually present, so a value set here is a DEFAULT the deployed profiles may
        //       override. No shipped profile names this key, so this assignment is what supplies it,
        //       and the guard above is what refuses a profile that later switches it off.
        pool.setIsolateInternalQueries(isolateInternalQueries);
        return pool;
    }

    /**
     * Supplies the callback that proves the session posture on two distinct physical connections.
     *
     * <p>Assumptions: a {@link SmartInitializingSingleton} callback runs after regular singleton
     * initialisation, so migration has already completed by the time it inspects a pooled connection
     * and what it reads is the posture a step will see. That ordering matters beyond tidiness: a
     * migration engine sharing this pool can leave a session setting applied to a connection that
     * later serves a step, and this callback is what would catch it.</p>
     *
     * @param dataSource the {@link HikariDataSource} whose pooled connections are inspected; must not
     *     be {@code null}
     * @return a {@link SmartInitializingSingleton} that aborts context initialisation when the
     *     effective session posture differs from the declared contract, never {@code null}
     */
    @Bean
    public SmartInitializingSingleton batchSessionPostureVerifier(HikariDataSource dataSource) {
        // WHAT: the deferral of the proof to the point at which the pool is fully configured.
        // WHY : Trade-offs: this spends two pool acquisitions before the first step runs, and that is
        //       the cheapest moment at which they can be spent. The alternative is worse than a late
        //       error rather than merely later: PostgreSQL accepts a search path naming a schema that
        //       is absent or out of order, so without this proof the first unqualified statement of a
        //       nightly run is what decides where rows land, and it decides silently.
        // WHAT: the statement the pool ACTUALLY holds, read back for use in a failure message.
        // WHY : Assumptions: the value bound onto the pool and the value this class validated come
        //       from two different mechanisms -- property binding onto the pool instance, and a
        //       resolved placeholder on the constructor -- so they can disagree. Quoting the pool's
        //       own copy in a failure is what distinguishes "the statement never reached the pool"
        //       from "the statement reached the pool and the database disagreed with it", and those
        //       two have entirely different remedies.
        return () -> verifySessionPosture(
                dataSource, dataSource.getConnectionInitSql(), this.searchPath, this.ownedSchema);
    }

    /**
     * Builds the one statement form this module accepts for pinning the schema search path.
     *
     * <p>Alternatives Considered: three other mechanisms were evaluated for applying the path, and
     * each fails on something concrete. Embedding {@code options=-c search_path=...} in the JDBC URL
     * was rejected because the URL arrives from external configuration and this class must neither
     * synthesise nor mutate it, so an operator edit could remove the pin with nothing recording that
     * it had gone. HikariCP's own {@code schema} property was rejected because it calls
     * {@code java.sql.Connection#setSchema}, which on PostgreSQL sets a SINGLE schema and cannot
     * express an ordered list of four at all. Schema-qualifying the mappings instead was rejected as
     * a substitute rather than as a complement: all eight entities in
     * {@code com.carddemo.batch.domain} already declare {@code @Table(schema = ...)}, which was
     * verified entity by entity, so for the provider this path is genuine defence in depth -- but the
     * framework's job-repository tables carry an unqualified default prefix, migration resolves its
     * own history table the same way, and any native statement does too, so for those three
     * consumers the path is load-bearing and nothing else supplies it.</p>
     *
     * @param schemas the ordered {@code List<String>} of schema names to pin, owned schema first;
     *     must not be {@code null} or empty
     * @return the canonical {@link String} statement pinning exactly those schemas in that order,
     *     never {@code null}
     */
    private static String canonicalSearchPathStatement(List<String> schemas) {
        return "SET search_path TO " + String.join(", ", schemas);
    }

    /**
     * Accepts an ordered schema list only if every entry could resolve an object unambiguously.
     *
     * @param declaredSchemas the {@code List<String>} of schema names read from configuration; must
     *     name at least one schema, with no blank, no duplicate and no default namespace
     * @return the same names trimmed and lower-cased, in the order declared, never {@code null}
     * @throws IllegalStateException if the list is absent or empty, if any entry is blank, if an
     *     entry repeats, or if an entry names the default namespace
     */
    private static List<String> requireOrderedSearchPath(List<String> declaredSchemas) {
        if (declaredSchemas == null || declaredSchemas.isEmpty()) {
            throw new IllegalStateException(SEARCH_PATH_KEY
                    + " must name at least one schema, ordered with the owned schema first");
        }

        List<String> normalised = declaredSchemas.stream()
                .map(schema -> schema == null ? "" : schema.trim().toLowerCase(Locale.ROOT))
                .toList();

        for (String schema : normalised) {
            if (schema.isEmpty()) {
                throw new IllegalStateException(
                        SEARCH_PATH_KEY + " must not contain a blank schema name");
            }
            if (DEFAULT_NAMESPACE.equals(schema)) {
                throw new IllegalStateException(SEARCH_PATH_KEY + " must not admit the "
                        + DEFAULT_NAMESPACE + " namespace, where an unqualified name could resolve"
                        + " against an object no migration in this repository created");
            }
        }

        // WHAT: the duplicate check, which guards resolution order rather than tidiness.
        // WHY : Assumptions: a repeated schema is not harmless noise. The list is an ordered
        //       resolution sequence, so a repeat means one of the two positions is dead and a reader
        //       comparing this list against the grants cannot tell which position was intended.
        if (Set.copyOf(normalised).size() != normalised.size()) {
            throw new IllegalStateException(SEARCH_PATH_KEY
                    + " must name each schema once; a repeated entry leaves it ambiguous which"
                    + " position was intended as the resolution order");
        }
        return normalised;
    }

    /**
     * Accepts a schema name from configuration only when it actually names something.
     *
     * @param schemaName the {@link String} value read from configuration; must not be blank
     * @param propertyKey the {@link String} key the value came from, quoted back in any failure so an
     *     operator is told which setting to correct
     * @return the trimmed, lower-cased schema name, never {@code null}
     * @throws IllegalStateException if the value is {@code null} or blank
     */
    private static String requireSchemaName(String schemaName, String propertyKey) {
        if (schemaName == null || schemaName.isBlank()) {
            throw new IllegalStateException(
                    propertyKey + " must name the one schema this module owns and migrates");
        }
        return schemaName.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Requires the owned schema to lead the resolution order rather than merely appear in it.
     *
     * @param schemas the ordered {@code List<String>} search path, already normalised; must not be
     *     empty
     * @param ownedSchema the {@link String} schema this module owns and migrates
     * @throws IllegalStateException if the first entry of the path is not the owned schema
     */
    private static void requireOwnedSchemaLeadsSearchPath(List<String> schemas, String ownedSchema) {
        if (!ownedSchema.equals(schemas.get(0))) {
            throw new IllegalStateException("The first schema on " + SEARCH_PATH_KEY + " is '"
                    + schemas.get(0) + "' but " + MIGRATION_SCHEMA_KEY + " names '" + ownedSchema
                    + "'; an unqualified object would then be created by migration in one schema and"
                    + " resolved at run time in another");
        }
    }

    /**
     * Requires the statement the pool runs to be exactly the canonical statement, and nothing more.
     *
     * <p>Assumptions: HikariCP hands this string to {@code java.sql.Statement#execute} once per
     * physical connection, which is precisely why it must be ONE statement. A second statement
     * appended after a semicolon would rely on the driver accepting a simple-query batch and would
     * then run on every connection the pool ever opens, so the pattern is anchored at both ends to
     * refuse one. This module therefore sets no other session parameter here: the remaining session
     * settings arrive through the driver's own options parameter instead, which keeps this statement
     * free of any dependence on batched simple-query behaviour.</p>
     *
     * @param declaredStatement the {@link String} statement read from
     *     {@code spring.datasource.hikari.connection-init-sql}; must not be blank
     * @param expectedSchemas the ordered {@code List<String>} the statement must pin, owned schema
     *     first
     * @throws IllegalStateException if the statement is blank, does not begin by pinning the search
     *     path, admits the default namespace, is not exactly one statement of the accepted form, or
     *     pins a schema list differing in content or order from the expected one
     */
    private static void requireDeclaredSearchPathStatement(
            String declaredStatement, List<String> expectedSchemas) {

        if (declaredStatement == null || declaredStatement.isBlank()) {
            throw new IllegalStateException(INIT_SQL_KEY + " must pin the schema search path to "
                    + canonicalSearchPathStatement(expectedSchemas) + " and must not be blank");
        }

        String normalised = normalise(declaredStatement);
        if (!normalised.startsWith(SEARCH_PATH_PREFIX)) {
            throw new IllegalStateException(
                    INIT_SQL_KEY + " must begin by pinning the schema search path");
        }
        if (DEFAULT_NAMESPACE_MENTION.matcher(normalised).matches()) {
            throw new IllegalStateException(INIT_SQL_KEY + " must not admit the "
                    + DEFAULT_NAMESPACE + " namespace to the search path");
        }

        Matcher matched = SEARCH_PATH_STATEMENT.matcher(normalised);
        if (!matched.matches()) {
            throw new IllegalStateException(INIT_SQL_KEY + " must be exactly one statement of the"
                    + " form SET search_path TO <schema>[, <schema>...] with unquoted names and"
                    + " nothing after it; a second statement here would run on every connection the"
                    + " pool opens");
        }

        List<String> pinned = parseSchemaList(matched.group(1));
        if (!expectedSchemas.equals(pinned)) {
            throw new IllegalStateException(INIT_SQL_KEY + " pins " + pinned + " but "
                    + SEARCH_PATH_KEY + " declares " + expectedSchemas + "; the statement must read "
                    + canonicalSearchPathStatement(expectedSchemas)
                    + " so the two settings cannot drift apart");
        }
    }

    /**
     * Requires migration to manage the owned schema and no other service's schema.
     *
     * @param migrationSchemas the {@code List<String>} of schemas read from
     *     {@code spring.flyway.schemas}; must name the owned schema and nothing else
     * @param ownedSchema the {@link String} schema this module owns
     * @throws IllegalStateException if the list is absent, empty, or names any schema other than the
     *     owned one
     */
    private static void requireMigrationConfinedTo(
            List<String> migrationSchemas, String ownedSchema) {

        // WHAT: the boundary that keeps this module out of three other services' migrations.
        // WHY : Assumptions: this module reaches four schemas but owns exactly one, and the other
        //       three are migrated by the services that own them. Widening this list would let this
        //       module's own migration create or alter an object another service's migration also
        //       describes, and two owners of one table is a divergence no version-ordered history can
        //       reconcile. The narrow list is also consistent with the privileges actually granted:
        //       CREATE is revoked from the runtime role at
        //       data-migration/sql/V0__schemas_and_roles.sql:966, so the intent is stated here rather
        //       than left to a privilege to enforce.
        if (migrationSchemas == null || migrationSchemas.isEmpty()) {
            throw new IllegalStateException(
                    MIGRATION_SCHEMAS_KEY + " must name the one schema this module migrates");
        }

        List<String> normalised = migrationSchemas.stream()
                .map(schema -> schema == null ? "" : schema.trim().toLowerCase(Locale.ROOT))
                .toList();

        if (!List.of(ownedSchema).equals(normalised)) {
            throw new IllegalStateException(MIGRATION_SCHEMAS_KEY + " names " + normalised
                    + " but this module owns '" + ownedSchema + "' alone; migration here must never"
                    + " create, alter or seed another service's schema");
        }
    }

    /**
     * Requires the persistence provider to be configured in a mode that emits no schema definition.
     *
     * <p>Alternatives Considered: {@code none} rather than {@code validate} was the alternative for
     * the deployed profiles, and {@code validate} is what they set. Seven of this module's eight
     * mappings describe tables in {@code ledger}, {@code account} and {@code reference} that this
     * module's own migration never creates, so no migration of its own will ever resolve a drift in
     * them; {@code none} would defer such a drift to the first statement that touched the changed
     * column, part way through a run with a feed already partly read, whereas validation reports it
     * before the first record is read. This method admits {@code none} as well as {@code validate}
     * because the test harness profile sets it deliberately, and what is refused is narrower and
     * unambiguous: any mode that would have the provider EMIT definitions.</p>
     *
     * <p>Assumptions: choosing validation accepts a real cross-service ordering dependency -- this
     * task cannot start until the services owning those three schemas have applied their own
     * migrations. That is accepted rather than worked around, because a task that could start
     * without them could not post into their tables anyway, and for a nightly chain a clean refusal
     * to start is the better failure.</p>
     *
     * @param schemaManagementMode the {@link String} mode read from
     *     {@code spring.jpa.hibernate.ddl-auto}; must not be blank and must emit no DDL
     * @throws IllegalStateException if the mode is blank or is one of the modes that emit DDL
     */
    private static void requireNoGeneratedDdl(String schemaManagementMode) {
        if (schemaManagementMode == null || schemaManagementMode.isBlank()) {
            throw new IllegalStateException(DDL_AUTO_KEY
                    + " must state the provider's schema-management mode explicitly, because the"
                    + " framework's own fallback differs by environment");
        }

        String normalised = schemaManagementMode.trim().toLowerCase(Locale.ROOT);
        if (DDL_EMITTING_MODES.contains(normalised)) {
            throw new IllegalStateException(DDL_AUTO_KEY + " is '" + normalised
                    + "', which has the provider emit schema definitions; migration is the sole"
                    + " source of table definitions in this module and it manages one schema only");
        }
    }

    /**
     * Requires the pool to hand out connections with auto-commit switched off.
     *
     * @param autoCommit the {@code boolean} posture read from
     *     {@code spring.datasource.hikari.auto-commit}; must be {@code false}
     * @throws IllegalStateException if auto-commit is switched on
     */
    private static void requireAutoCommitDisabled(boolean autoCommit) {
        if (autoCommit) {
            throw new IllegalStateException(AUTO_COMMIT_KEY + " must be false; with auto-commit on"
                    + " the driver ignores the fetch-size hint on this module's Stream finders and"
                    + " materialises the whole result in the client instead of opening a"
                    + " server-side cursor");
        }
    }

    /**
     * Requires the pool to commit its own initialisation statement rather than leave it uncommitted.
     *
     * @param isolateInternalQueries the {@code boolean} posture read from
     *     {@code spring.datasource.hikari.isolate-internal-queries}; must be {@code true}
     * @throws IllegalStateException if internal-query isolation is switched off, which with
     *     auto-commit disabled would leave the schema pinning subject to the first rollback
     */
    private static void requireIsolatedInternalQueries(boolean isolateInternalQueries) {
        if (!isolateInternalQueries) {
            throw new IllegalStateException(ISOLATE_INTERNAL_QUERIES_KEY + " must be true while "
                    + AUTO_COMMIT_KEY + " is false; a SET is transactional in PostgreSQL, so without"
                    + " it the schema pinning in " + INIT_SQL_KEY + " runs in a transaction nobody"
                    + " commits and the first rollback on that connection reverts the search path to"
                    + " the server default");
        }
    }

    /**
     * Requires a pool whose ceiling can carry a streaming step and its execution bookkeeping at once.
     *
     * @param maximumPoolSize the {@code int} connection ceiling; must be at least three
     * @param minimumIdle the {@code int} number of connections kept ready; must not be negative and
     *     must not exceed the ceiling
     * @throws IllegalStateException if the ceiling is below three, or if the idle floor is negative
     *     or above the ceiling
     */
    private static void requireViablePoolBounds(int maximumPoolSize, int minimumIdle) {
        if (maximumPoolSize < MINIMUM_VIABLE_POOL_SIZE) {
            throw new IllegalStateException(MAX_POOL_SIZE_KEY + " is " + maximumPoolSize
                    + " but must be at least " + MINIMUM_VIABLE_POOL_SIZE + "; a streaming cursor"
                    + " pins one connection for a whole step while execution state commits on a"
                    + " second, so a ceiling of one deadlocks and two leaves no headroom for"
                    + " migration or the health probe");
        }
        if (minimumIdle < 0) {
            throw new IllegalStateException(
                    MIN_IDLE_KEY + " is " + minimumIdle + " but must not be negative");
        }
        if (minimumIdle > maximumPoolSize) {
            throw new IllegalStateException(MIN_IDLE_KEY + " is " + minimumIdle + " but "
                    + MAX_POOL_SIZE_KEY + " is " + maximumPoolSize + "; a floor above the ceiling is"
                    + " silently normalised by the pool, so it is refused here where it is still"
                    + " visible as the operator error it always is");
        }
    }

    /**
     * Requires a millisecond bound to be positive, so the interaction it governs is really bounded.
     *
     * @param millis the {@code long} configured number of milliseconds; must be greater than zero
     * @param propertyKey the {@link String} key the value came from, quoted back in any failure so an
     *     operator is told which setting to correct
     * @return the accepted value, unchanged, so a caller may bound and assign in one expression
     * @throws IllegalStateException if the value is zero or negative
     */
    private static long requirePositiveMillis(long millis, String propertyKey) {
        if (millis <= 0) {
            throw new IllegalStateException(propertyKey + " must be a positive number of"
                    + " milliseconds but was " + millis + "; zero or negative leaves the interaction"
                    + " it governs unbounded, which for a task nobody is watching means it waits"
                    + " instead of failing");
        }
        return millis;
    }

    /**
     * Requires the driver session options to bound waiting without bounding legitimate work.
     *
     * <p>Alternatives Considered: a tight {@code statement_timeout} alongside the two bounds below,
     * which is what the online bounded contexts set and what this module deliberately does not. It is
     * right for an interactive request, where a slow statement is a user waiting; it is wrong here,
     * because it would cancel a legitimate long set-based step in the middle of its transaction. The
     * outer bound on a batch step belongs outside the process, in the orchestrator's per-state
     * timeout, which the migration plan requires every state to carry at its section 0.4.1.7 and
     * which fails the state cleanly and can retry it with backoff. Presence of a session-level
     * statement timeout is therefore refused rather than merely left unset, so the decision is
     * auditable instead of being a comment somebody later deletes.</p>
     *
     * <p>Trade-offs: bounding the lock wait and the idle-in-transaction window IS right, and the two
     * bounds buy different things. A step blocked behind another writer fails and can be retried
     * rather than holding the batch window open; and a stuck task cannot pin locks indefinitely while
     * doing nothing. The reference sets the precedent for failing rather than waiting:
     * {@code app/cbl/CBACT04C.cbl:356} performs {@code REWRITE FD-ACCTFILE-REC FROM  ACCOUNT-RECORD}
     * and its line 357 reads {@code IF  ACCTFILE-STATUS  = '00'}, accepting only a clean status and
     * taking the abend path at line 360 otherwise. The accepted cost is that a genuinely contended
     * step fails a state that then has to be retried.</p>
     *
     * @param sessionOptions the {@link String} value read from
     *     {@code spring.datasource.hikari.data-source-properties.options}; must not be blank
     * @throws IllegalStateException if the value is blank, if either required bound is absent or set
     *     to zero, or if it names a statement timeout
     */
    private static void requireStreamingSessionOptions(String sessionOptions) {
        if (sessionOptions == null || sessionOptions.isBlank()) {
            throw new IllegalStateException(SESSION_OPTIONS_KEY + " must bound both "
                    + LOCK_TIMEOUT_SETTING + " and " + IDLE_IN_TRANSACTION_SETTING);
        }

        Map<String, String> declared = parseSessionOptions(sessionOptions);
        requireBoundedTimeout(
                declared.get(LOCK_TIMEOUT_SETTING), LOCK_TIMEOUT_SETTING, SESSION_OPTIONS_KEY);
        requireBoundedTimeout(declared.get(IDLE_IN_TRANSACTION_SETTING),
                IDLE_IN_TRANSACTION_SETTING, SESSION_OPTIONS_KEY);

        if (declared.containsKey(STATEMENT_TIMEOUT_SETTING)) {
            throw new IllegalStateException(SESSION_OPTIONS_KEY + " names " + STATEMENT_TIMEOUT_SETTING
                    + ", which this module leaves unbounded at session level on purpose; a batch step"
                    + " is bounded by the orchestrator state that invoked it, and a session bound"
                    + " would cancel a legitimate long step inside its own transaction");
        }
    }

    /**
     * Reads the {@code -c name=value} session parameters out of a driver options string.
     *
     * @param sessionOptions the {@link String} options value to read; must not be {@code null}
     * @return a {@code Map<String, String>} of parameter name to configured value, preserving the
     *     order in which the parameters appeared, never {@code null}
     */
    private static Map<String, String> parseSessionOptions(String sessionOptions) {
        Map<String, String> parsed = new LinkedHashMap<>();
        Matcher options = SESSION_OPTION.matcher(sessionOptions.toLowerCase(Locale.ROOT));
        while (options.find()) {
            parsed.put(options.group(1), options.group(2));
        }
        return parsed;
    }

    /**
     * Requires a timeout value to be present and to denote something other than "no bound at all".
     *
     * @param reportedValue the {@link String} value as configured or as the session reports it, which
     *     may be {@code null} when the setting was never named
     * @param settingName the {@link String} name of the session parameter being checked
     * @param source the {@link String} description of where the value came from, quoted back so a
     *     failure names the configuration key or the connection it was read from
     * @throws IllegalStateException if the value is absent or denotes zero
     */
    private static void requireBoundedTimeout(
            String reportedValue, String settingName, String source) {

        if (reportedValue == null) {
            throw new IllegalStateException(
                    source + " names no " + settingName + ", which leaves it unbounded");
        }
        if (denotesZero(reportedValue)) {
            throw new IllegalStateException(source + " reports " + settingName + " as '"
                    + reportedValue + "', which switches the bound off");
        }
    }

    /**
     * Decides whether a timeout value denotes zero, whatever unit suffix it carries.
     *
     * <p>Assumptions: PostgreSQL accepts and reports a timeout with a unit suffix, so the same bound
     * appears as {@code 30000}, {@code 30s} or {@code 500ms} depending on how it was written and how
     * the server chose to render it. Comparing against the digits alone is what makes the check
     * independent of that rendering; a value carrying no digits at all is treated as zero, because it
     * is unparseable and refusing it is safer than assuming it bounds anything.</p>
     *
     * @param value the {@link String} timeout value to inspect; must not be {@code null}
     * @return {@code true} when the value carries no digits or only zeros, {@code false} otherwise
     */
    private static boolean denotesZero(String value) {
        String digits = value.replaceAll(NON_DIGIT, "");
        return digits.isEmpty() || digits.chars().allMatch(digit -> digit == '0');
    }

    /**
     * Reduces a statement to the one written form the accepted pattern is expressed against.
     *
     * @param statement the {@link String} statement or setting value to reduce; must not be
     *     {@code null}
     * @return the value trimmed, lower-cased, with runs of whitespace collapsed to one space and a
     *     single trailing semicolon removed, never {@code null}
     */
    private static String normalise(String statement) {
        String collapsed =
                statement.trim().toLowerCase(Locale.ROOT).replaceAll(WHITESPACE_RUN, " ").trim();
        if (collapsed.endsWith(";")) {
            return collapsed.substring(0, collapsed.length() - 1).trim();
        }
        return collapsed;
    }

    /**
     * Splits a comma-separated schema list into its entries, in the order they appear.
     *
     * @param schemaList the {@link String} comma-separated list to split; must not be {@code null}
     * @return the {@code List<String>} of trimmed entries in declaration order, never {@code null}
     */
    private static List<String> parseSchemaList(String schemaList) {
        return List.of(schemaList.split(",")).stream().map(String::trim).toList();
    }

    /**
     * Proves the declared session posture is the posture the database actually applies.
     *
     * <p>Assumptions: the second connection is acquired while the first is still held, which is what
     * forces the pool to open a SECOND physical connection rather than hand the same one back. The
     * two backend process identifiers are compared to prove exactly that, because the property under
     * test is that the pool's initialisation statement runs per connection and not once per pool -- a
     * proof that read the same connection twice would pass without testing anything.</p>
     *
     * @param dataSource the {@link DataSource} whose pooled connections are inspected; must not be
     *     {@code null}
     * @param configuredStatement the {@link String} initialisation statement the pool itself holds,
     *     which may be {@code null} when nothing was bound onto it, quoted back in any failure
     * @param expectedSchemas the ordered {@code List<String>} the session must resolve against
     * @param ownedSchema the {@link String} schema that must lead that path and must exist
     * @throws IllegalStateException if either connection reports a posture differing from the
     *     declared contract, if the two readings came from the same physical connection, or if the
     *     posture cannot be read at all
     */
    private static void verifySessionPosture(DataSource dataSource, String configuredStatement,
            List<String> expectedSchemas, String ownedSchema) {

        try (Connection first = dataSource.getConnection()) {
            SessionPosture firstPosture = readSessionPosture(first);
            requireExpectedPosture(firstPosture, configuredStatement, expectedSchemas, ownedSchema,
                    "the first pooled connection");

            try (Connection second = dataSource.getConnection()) {
                SessionPosture secondPosture = readSessionPosture(second);
                requireExpectedPosture(secondPosture, configuredStatement, expectedSchemas,
                        ownedSchema, "a second pooled connection");
                requireDistinctBackends(firstPosture, secondPosture);

                // WHAT: the release of both sessions with no transaction left open behind them.
                // WHY : Assumptions: auto-commit is off by contract, so even a read opens a
                //       transaction. Returning a connection with one still open would leave it
                //       counted against the idle-in-transaction bound checked just above, so the
                //       proof would have created the very condition that bound exists to catch.
                second.rollback();
            }
            first.rollback();
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read the session posture from a pooled connection", failure);
        }
    }

    /**
     * Reads one connection's effective session posture in a single round trip.
     *
     * @param connection the {@link Connection} to inspect; must not be {@code null}
     * @return the {@link SessionPosture} the connection reports, never {@code null}
     * @throws SQLException if the posture query cannot be issued or read
     * @throws IllegalStateException if the database returns no row for the posture query
     */
    private static SessionPosture readSessionPosture(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(SESSION_POSTURE_QUERY)) {

            if (!row.next()) {
                throw new IllegalStateException(
                        "The database returned no row while reading the session posture");
            }
            return new SessionPosture(row.getString(1), row.getString(2), row.getString(3),
                    row.getString(4), row.getInt(5), connection.getAutoCommit());
        }
    }

    /**
     * Requires one connection's reported posture to match the contract this class declared.
     *
     * @param posture the {@link SessionPosture} read from the connection; must not be {@code null}
     * @param configuredStatement the {@link String} initialisation statement the pool holds, which
     *     may be {@code null} when nothing was bound onto it
     * @param expectedSchemas the ordered {@code List<String>} the session must resolve against
     * @param ownedSchema the {@link String} schema that must lead the path and must exist
     * @param source the {@link String} description of which connection was read, quoted back so a
     *     failure says which one disagreed
     * @throws IllegalStateException if auto-commit is on, if no schema resolves, if the effective
     *     path differs in content or order, if the leading schema is not the owned one, or if either
     *     session bound is switched off
     */
    private static void requireExpectedPosture(SessionPosture posture, String configuredStatement,
            List<String> expectedSchemas, String ownedSchema, String source) {

        if (posture.autoCommit()) {
            throw new IllegalStateException(source + " was handed out with auto-commit on even"
                    + " though " + AUTO_COMMIT_KEY + " is false; a Stream finder on such a"
                    + " connection would materialise its whole result instead of streaming");
        }
        if (posture.searchPath() == null || posture.currentSchema() == null) {
            throw new IllegalStateException(source + " resolved no schema at all; the path set by "
                    + INIT_SQL_KEY + " names no schema that exists in this database");
        }

        List<String> effective = parseSchemaList(normalise(posture.searchPath()));
        if (!expectedSchemas.equals(effective)) {
            throw new IllegalStateException(source + " resolves against " + effective + " but "
                    + SEARCH_PATH_KEY + " declares " + expectedSchemas + "; an unqualified statement"
                    + " under that order would still resolve, against a real table in the wrong"
                    + " schema. The pool holds " + INIT_SQL_KEY + " as ["
                    + configuredStatement + "]");
        }
        if (!ownedSchema.equals(posture.currentSchema())) {
            throw new IllegalStateException(source + " resolves '" + posture.currentSchema()
                    + "' first rather than the owned schema '" + ownedSchema + "'; the leading"
                    + " schema on the declared path does not exist in this database");
        }

        requireBoundedTimeout(posture.lockTimeout(), LOCK_TIMEOUT_SETTING, source);
        requireBoundedTimeout(
                posture.idleInTransactionTimeout(), IDLE_IN_TRANSACTION_SETTING, source);
    }

    /**
     * Requires two posture readings to have come from two different physical connections.
     *
     * @param first the {@link SessionPosture} read from the connection acquired first; must not be
     *     {@code null}
     * @param second the {@link SessionPosture} read from the connection acquired while the first was
     *     still held; must not be {@code null}
     * @throws IllegalStateException if both readings report the same backend process, which would
     *     mean the pool returned one connection twice and nothing about per-connection
     *     initialisation was proved
     */
    private static void requireDistinctBackends(SessionPosture first, SessionPosture second) {
        if (first.backendProcessId() == second.backendProcessId()) {
            throw new IllegalStateException("Both posture readings came from backend process "
                    + first.backendProcessId() + ", so the pool returned one connection twice and"
                    + " the per-connection initialisation of " + INIT_SQL_KEY + " was not proved");
        }
    }

    /**
     * The session posture one pooled connection reports, captured in a single round trip.
     *
     * @param searchPath the effective ordered path the session resolves unqualified objects against
     * @param currentSchema the first schema on that path which actually exists in the database
     * @param lockTimeout the effective lock-wait bound, as the server chose to render it
     * @param idleInTransactionTimeout the effective idle-in-transaction bound, as rendered
     * @param backendProcessId the server-side process identifier, which distinguishes one physical
     *     connection from another
     * @param autoCommit whether the connection was handed out with auto-commit switched on
     */
    private record SessionPosture(
            String searchPath,
            String currentSchema,
            String lockTimeout,
            String idleInTransactionTimeout,
            int backendProcessId,
            boolean autoCommit) {
    }
}
