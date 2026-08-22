package com.carddemo.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Holds the one cross-schema write privilege the nightly batch chain needs on this context's master.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: the posting unit of work commits three writes together. {@code app/cbl/CBTRN02C.cbl}
 * reaches them from {@code 2000-POST-TRANSACTION.} at L424 -- the category balance at L440 and the
 * posted transaction at L442, both in the ledger context, and the account master at L441, rewritten at
 * L554, in THIS one -- and the interest job rewrites the same master on each account control break at
 * {@code app/cbl/CBACT04C.cbl} L356. The target keeps that as one ACID commit, so the batch role has to
 * hold {@code UPDATE} on {@code account.accounts} and must hold nothing else here that lets it change a
 * stored row. This class asserts that graph against a real engine, applied in the order a deployment
 * applies it.
 *
 * <p>Refactoring Rationale: this class exists because that grant was declared and never reached a
 * provisioned database. {@code data-migration/sql/V0__schemas_and_roles.sql} issues it from inside an
 * {@code IF to_regclass('account.accounts') IS NOT NULL} guard, and that script runs BEFORE any table
 * exists, so on a fresh database the guard was false, the block emitted its notice, and nothing re-ran
 * it -- {@code infra/lambda/database_admin.py} exposes a bootstrap action and an analyze action, and its
 * invocation triggers do not change when a service migrates. Posting therefore failed its third write
 * with {@code permission denied for table accounts} on every freshly provisioned environment, correctly
 * rolling the other two back. The remedy is
 * {@code db/migration/V3__batch_account_write_grant.sql}, which runs in THIS context's chain after
 * {@code V1__account.sql} has created the table and under the role that owns it.
 *
 * <p>Alternatives Considered: asserting the grant by reading the migration's text, which is what
 * {@code com.carddemo.common.architecture.CrossSchemaPrivilegeContractTest} now does. Rejected as
 * sufficient on its own, and the defect above is the evidence: a text check confirms a statement is
 * written somewhere, not that the deployment sequence ever executes it. The two are kept as a pair --
 * that class holds the agreement between files, and this one holds what the engine ends up with.
 *
 * <h2>What this class does differently from its siblings</h2>
 *
 * <p>Refactoring Rationale: the other seven classes in this package create the owning role and the
 * schema themselves, in three statements, because what they assert needs a schema and nothing more. This
 * class cannot do that, because the subject IS the shipped provisioning: a harness-authored role graph
 * would be a second definition of the thing under test, and it would pass whatever it was written to
 * say. It therefore applies {@code data-migration/sql/V0__schemas_and_roles.sql} unchanged, by the
 * engine's own client, exactly as {@code docs/runbooks/deploy.md} has an operator apply it -- and then
 * lets the deployed Flyway configuration apply this context's migrations on top, authenticating as the
 * migrator role that bootstrap created.
 *
 * <p>Assumptions: Flyway authenticates as {@code carddemo_account_migrator} here rather than as the
 * container's generated superuser, and that choice is what makes the central assertion mean anything. A
 * superuser may grant on any object, so a migration applied as one would succeed whether or not the
 * deployed identity could have issued it. The migrator holds no privilege of its own -- the bootstrap
 * makes it a member of the owner {@code WITH INHERIT FALSE, SET TRUE} -- so the grant below can only
 * succeed by way of the {@code SET ROLE carddemo_account_owner;} statement the module's own
 * {@code spring.flyway.init-sqls} carries, which is precisely the deployed mechanism.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.
 */
@Testcontainers
// WHY : Refactoring Rationale: BOTH remote config-data locations are DISABLED for this context, for the
//       reason recorded at length on CustomerMasterRepositoryIT in this package: application.yml imports
//       an AWS parameter-store and an AWS secrets-manager location, and LOADING either builds a client
//       from an unresolved region placeholder while configuration is still in progress, which ends
//       context load with a failure that belongs to configuration rather than to any assertion here.
@SpringBootTest(
        classes = BatchAccountWriteGrantIT.BatchGrantPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.aws.parameterstore.enabled=false",
                "spring.cloud.aws.secretsmanager.enabled=false"})
@ActiveProfiles("test")
class BatchAccountWriteGrantIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every sibling integration test in this build pins, so one
     * engine serves the whole suite and two tests cannot disagree about one schema. The version is
     * recorded in prose because a digest states nothing a reader recognises, and the two must be changed
     * together.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /** The shipped bootstrap, repository-relative, applied unchanged before the context is built. */
    private static final String BOOTSTRAP_SCRIPT = "data-migration/sql/V0__schemas_and_roles.sql";

    /**
     * The two database settings the bootstrap requires before it will commit against this container.
     *
     * <p>Assumptions: the first is needed because the bootstrap refuses an unencrypted session, and a
     * throwaway container serves no TLS; the second because it refuses to leave a login role without a
     * credential, and this harness supplies credentials for the two roles it authenticates as rather
     * than for all eight. Both are acknowledgements of a known local condition and neither alters a
     * single grant the script issues.</p>
     */
    private static final List<String> BOOTSTRAP_ACKNOWLEDGEMENTS = List.of(
            "carddemo.bootstrap_allow_insecure",
            "carddemo.bootstrap_allow_missing_credentials");

    /** The login role the deployed Flyway configuration migrates this schema as. */
    private static final String MIGRATOR_ROLE = "carddemo_account_migrator";

    /** The NOLOGIN role that owns this schema and every object in it. */
    private static final String OWNER_ROLE = "carddemo_account_owner";

    /** The nightly batch chain's runtime role, and the grantee under test. */
    private static final String BATCH_ROLE = "carddemo_batch";

    /**
     * The credential this harness gives the two roles it authenticates as.
     *
     * <p>Assumptions: the bootstrap creates every login role WITHOUT a credential on purpose, because a
     * credential in a committed file is a committed credential; its value arrives from a secret store at
     * deploy time. This constant is the throwaway equivalent for a container that is discarded with the
     * run, it is not a value any environment uses, and it confers no privilege -- the grants under test
     * are established entirely by the shipped scripts.</p>
     */
    private static final String HARNESS_CREDENTIAL = "harness-only-credential";

    /** The one table in this schema the nightly chain rewrites. */
    private static final String ACCOUNT_MASTER = "account.accounts";

    /** The account master row every case seeds, inside the eleven digits the record declares. */
    private static final long FIXTURE_ACCOUNT_ID = 90000000001L;

    /** The balance the seeded row starts at, so a successful rewrite is visible as a change. */
    private static final BigDecimal SEEDED_BALANCE = new BigDecimal("100.00");

    /** The balance the batch role writes, differing from the seeded one in both digits and scale. */
    private static final BigDecimal POSTED_BALANCE = new BigDecimal("250.75");

    /**
     * The SQL state PostgreSQL reports for a refused statement.
     *
     * <p>Assumptions: the state is asserted rather than the message text, because the text is localised
     * and version-dependent while the state is defined by the standard. A case that matched on wording
     * would pass or fail on the engine's locale.</p>
     */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** The throwaway table one case creates to prove no default privilege reaches a later table. */
    private static final String LATER_TABLE = "account.batch_grant_probe";

    /**
     * The container every assertion in this class runs against, started once for the class.
     *
     * <p>Assumptions: no initialisation script is supplied through the container, because the bootstrap
     * has to run after the two acknowledgements above are in force and an initialisation script runs
     * before anything else can be set. The type comes from {@code org.testcontainers.postgresql} rather
     * than the deprecated {@code org.testcontainers.containers}, and carries no type argument because
     * the replacement is not generic.</p>
     */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /** A plain JDBC handle over the context's own pool, for the catalog reads no entity can express. */
    private JdbcTemplate jdbc;

    /**
     * Registers the running container's coordinates, authenticating Flyway as the migrator role.
     *
     * <p>Assumptions: the datasource triple uses the container's generated superuser while the Flyway
     * pair uses the migrator. The split is deliberate: the catalog probes below ask about a role the
     * session is not a member of, which only a superuser may do, whereas the migration must run as the
     * unprivileged identity a deployment gives it or the grant it issues proves nothing.</p>
     *
     * @param registry the Spring test property registry that this method adds the container's JDBC URL
     *     and the two credential pairs to as deferred suppliers; must not be {@code null}
     */
    // WHY : Assumptions: the Flyway pair is registered because application.yml binds spring.flyway.user
    //       and spring.flyway.password to placeholders with no fallback, and Boot consults those keys
    //       precisely when no connection-details bean supplies them -- which is this module's case,
    //       since the artifact that would contribute one is deliberately absent from its POM.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", () -> MIGRATOR_ROLE);
        registry.add("spring.flyway.password", () -> HARNESS_CREDENTIAL);
    }

    /**
     * Applies the shipped bootstrap and credentials the two harness identities, before Flyway runs.
     *
     * <p>Assumptions: this runs before the Spring context is created, because JUnit invokes an
     * {@code @BeforeAll} method after the Testcontainers extension has started the static container and
     * before the Spring extension creates the context. That ordering is the whole point of this class:
     * it reproduces the deployed sequence, in which the bootstrap establishes schemas, roles and grants
     * and each service's Flyway chain then runs on top of it.</p>
     *
     * <p>This setup step takes no parameter and returns no value.</p>
     *
     * @throws IllegalStateException if the bootstrap file is absent, if the engine's client refuses any
     *     statement, or if a credential cannot be recorded -- each a broken harness rather than a failed
     *     assertion, and reported as such
     */
    @BeforeAll
    static void applyTheShippedBootstrapBeforeFlywayRuns() {
        for (String setting : BOOTSTRAP_ACKNOWLEDGEMENTS) {
            runPsql("-c", "ALTER DATABASE " + POSTGRES.getDatabaseName() + " SET " + setting + " = 'on'");
        }
        applyBootstrap();
        // WHY : Assumptions: only the two roles this class AUTHENTICATES as are credentialed, and the
        //       remaining six login roles are left exactly as the bootstrap leaves them. Credentialing
        //       all eight would be six statements that no connection here uses, and each one would blur
        //       the line between what the shipped script establishes and what the harness adds.
        runPsql("-c", "ALTER ROLE " + MIGRATOR_ROLE + " WITH PASSWORD '" + HARNESS_CREDENTIAL + "'");
        runPsql("-c", "ALTER ROLE " + BATCH_ROLE + " WITH PASSWORD '" + HARNESS_CREDENTIAL + "'");
    }

    /**
     * Empties the account master and seeds the one row the write cases operate on.
     *
     * <p>Assumptions: the row is written through the context's pool, which authenticates as the
     * container's superuser, rather than through the batch role. Seeding as the subject would make a
     * missing {@code INSERT} refusal indistinguishable from a seed that never landed, and the refusal is
     * one of the properties asserted below.</p>
     *
     * @param dataSource the pool the context built from the container's coordinates; must not be
     *     {@code null}
     */
    @BeforeEach
    void seedOneAccountMaster(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.update("DELETE FROM account.accounts");
        this.jdbc.update("INSERT INTO account.accounts (account_id, active_status, curr_bal,"
                        + " credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date,"
                        + " curr_cyc_credit, curr_cyc_debit, addr_zip, group_id)"
                        + " VALUES (?, 'Y', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                FIXTURE_ACCOUNT_ID, SEEDED_BALANCE, new BigDecimal("5000.00"),
                new BigDecimal("1000.00"), LocalDate.of(2020, 1, 1), LocalDate.of(2030, 12, 31),
                LocalDate.of(2025, 1, 1), new BigDecimal("0.00"), new BigDecimal("0.00"),
                "10001-0000", "ZEROAPR  ");
    }

    /**
     * Confirms the grant migration applied, as the schema owner, through Flyway's own history.
     *
     * <p>Assumptions: the history row and the table's owner are both read, because either alone would
     * leave the assertion incomplete. A recorded migration says a statement ran; the owner says WHO ran
     * it, and the grant is legal only because that identity owns the object. Reading the owner is also
     * what would catch the {@code SET ROLE} in {@code spring.flyway.init-sqls} being lost, which would
     * leave every default-privilege grant in the bootstrap inert.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("Flyway applied V3__batch_account_write_grant.sql as the schema owner")
    void flywayAppliedTheGrantMigrationAsTheSchemaOwner() {
        assertThat(this.jdbc.queryForObject(
                "SELECT count(*) FROM account.flyway_schema_history"
                        + " WHERE version = '3' AND success = true", Integer.class))
                .as("the grant migration must be recorded as applied and successful, which is what makes"
                        + " the privilege arrive from db/migration and not from the harness")
                .isEqualTo(1);

        assertThat(this.jdbc.queryForObject(
                "SELECT pg_catalog.pg_get_userbyid(relowner) FROM pg_catalog.pg_class"
                        + " WHERE oid = 'account.accounts'::regclass", String.class))
                .as("the master must belong to %s, because a GRANT on it is legal only from its owner"
                        + " and the migrator holds no privilege of its own", OWNER_ROLE)
                .isEqualTo(OWNER_ROLE);
    }

    /**
     * Confirms the batch role holds exactly the two privileges the nightly chain uses on the master.
     *
     * <p>Assumptions: {@code SELECT} is asserted beside {@code UPDATE} because the chain needs both and
     * they arrive by different mechanisms -- the read from a schema-wide default privilege in the
     * bootstrap, the write from the named grant in this context's migration. A case asserting only the
     * write would pass while the preflight validation in {@code app/cbl/CBTRN01C.cbl} L29 through L58,
     * which reads this master, was still refused.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the batch role holds SELECT and UPDATE on the account master")
    void theBatchRoleHoldsUpdateOnTheAccountMaster() {
        assertThat(privilege(ACCOUNT_MASTER, "UPDATE"))
                .as("%s must hold UPDATE on %s, or the posting job's third write"
                        + " (app/cbl/CBTRN02C.cbl L554) and the interest job's account rewrite"
                        + " (app/cbl/CBACT04C.cbl L356) are refused inside the nightly window",
                        BATCH_ROLE, ACCOUNT_MASTER)
                .isTrue();
        assertThat(privilege(ACCOUNT_MASTER, "SELECT"))
                .as("%s must hold SELECT on %s, which the daily-feed validation reads", BATCH_ROLE,
                        ACCOUNT_MASTER)
                .isTrue();
    }

    /**
     * Confirms no other write privilege on this schema reached the batch role.
     *
     * <p>Assumptions: the withheld set is checked on every table in the schema, discovered from the
     * catalog rather than listed here, so a table added by a later migration is covered without this
     * case being edited. The narrowness is the reason the cross-schema exception is acceptable at all --
     * {@code account.customers} carries the encrypted national and government-issued identifiers, and no
     * batch step modifies it.</p>
     *
     * <p>Assumptions: {@code INSERT} and {@code DELETE} are withheld on the master too, and that is
     * measured rather than cautious. No batch program creates an account, and the {@code DELETE} verb
     * does not appear in {@code app/cbl/CB*.cbl} at all.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("no account table other than the master is writable, and the master only by UPDATE")
    void theBatchRoleHoldsNoOtherWriteInTheAccountSchema() {
        Map<String, Boolean> held = new LinkedHashMap<>();
        for (String table : accountTables()) {
            for (String withheld : List.of("INSERT", "DELETE", "TRUNCATE")) {
                held.put(table + " " + withheld, privilege(table, withheld));
            }
            if (!ACCOUNT_MASTER.equals(table)) {
                held.put(table + " UPDATE", privilege(table, "UPDATE"));
            }
        }

        assertThat(held)
                .as("the catalog must report at least the four tables this schema holds, or the probe"
                        + " below asserted nothing at all")
                .isNotEmpty();
        assertThat(held.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList())
                .as("each privilege named below reached %s and must not have; the nightly chain rewrites"
                        + " exactly one row shape in this schema and reads the rest, and account.customers"
                        + " carries the encrypted national and government-issued identifiers", BATCH_ROLE)
                .isEmpty();
    }

    /**
     * Confirms the batch role can actually rewrite an account master row, connected as itself.
     *
     * <p>Assumptions: the statement is EXECUTED rather than the catalog consulted, because the two
     * answer different questions. A catalog entry says a privilege exists; executing the statement the
     * posting job executes says the engine permits it -- which is what failed on a freshly provisioned
     * environment while every entry the bootstrap did establish looked correct.</p>
     *
     * <p>Assumptions: the connection is opened directly rather than taken from the context's pool. The
     * pool authenticates as the container's superuser, for which every statement below would succeed
     * regardless of any grant, so it cannot be the instrument here.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the connection cannot be opened, which is a broken harness rather than a
     *     failed assertion
     */
    @Test
    @DisplayName("the batch role rewrites an account master row, as itself, against the engine")
    void theBatchRoleCanRewriteAnAccountMasterRow() throws SQLException {
        try (Connection batch = batchConnection();
                Statement statement = batch.createStatement()) {
            int rewritten = statement.executeUpdate("UPDATE account.accounts SET curr_bal = "
                    + POSTED_BALANCE + " WHERE account_id = " + FIXTURE_ACCOUNT_ID);
            assertThat(rewritten)
                    .as("the rewrite must affect the one seeded row, which is the shape of the account"
                            + " update at app/cbl/CBTRN02C.cbl L554")
                    .isEqualTo(1);
        }

        assertThat(this.jdbc.queryForObject(
                "SELECT curr_bal FROM account.accounts WHERE account_id = ?", BigDecimal.class,
                FIXTURE_ACCOUNT_ID))
                .as("the stored balance must be the posted one, read back through a separate session so"
                        + " the write is proven committed rather than merely accepted")
                .isEqualByComparingTo(POSTED_BALANCE);
    }

    /**
     * Confirms every statement the batch role must not be able to issue is refused by the engine.
     *
     * <p>Assumptions: four statements are attempted rather than one, because the withheld set has two
     * independent dimensions -- other verbs on the master, and the same verb on the other tables -- and
     * a single probe would leave whichever dimension it did not touch unasserted. Each is required to
     * fail with the standard's insufficient-privilege state, so a statement that failed for an unrelated
     * reason cannot be mistaken for a refusal.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the batch role is refused every write this schema withholds from it")
    void theBatchRoleIsRefusedEveryWithheldStatement() {
        Map<String, String> withheld = new LinkedHashMap<>();
        withheld.put("INSERT on the master", "INSERT INTO account.accounts (account_id, active_status,"
                + " curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date,"
                + " reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id) VALUES"
                + " (90000000002, 'Y', 0, 0, 0, DATE '2020-01-01', DATE '2030-12-31',"
                + " DATE '2025-01-01', 0, 0, '10001-0000', 'ZEROAPR  ')");
        withheld.put("DELETE on the master", "DELETE FROM account.accounts");
        withheld.put("UPDATE on the customer master", "UPDATE account.customers SET first_name = 'X'");
        withheld.put("UPDATE on the cross-reference", "UPDATE account.card_xref SET account_id = 1");

        Map<String, String> observed = new LinkedHashMap<>();
        withheld.forEach((description, sql) -> observed.put(description, outcomeOf(sql)));

        assertThat(observed)
                .as("every withheld statement must be attempted, or this case asserts less than it reads")
                .hasSameSizeAs(withheld)
                .allSatisfy((description, outcome) -> assertThat(outcome)
                        .as("%s must be refused to %s with the standard's insufficient-privilege state",
                                description, BATCH_ROLE)
                        .isEqualTo(INSUFFICIENT_PRIVILEGE));
    }

    /**
     * Confirms a table created after the grant does not become writable by the batch role.
     *
     * <p>Assumptions: this is the assertion that separates the shipped fix from the alternative it was
     * chosen over. Widening the bootstrap's account default privileges would satisfy every case above --
     * the batch role would hold {@code UPDATE} on the master -- while also handing it every table this
     * schema gains from then on, silently and with nothing in the catalog to distinguish the two
     * arrangements TODAY. Creating a table as the owner and probing it is what tells them apart.</p>
     *
     * <p>Assumptions: the table is created on one dedicated connection that assumes the owner, and the
     * default privileges under test are keyed on the CREATING role, so creating it as the container's
     * superuser would answer a different question. It is dropped in the same block, so no later case
     * meets it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws SQLException if the connection cannot be opened or the throwaway table cannot be created,
     *     which is a broken harness rather than a failed assertion
     */
    @Test
    @DisplayName("a table created later inherits the read default and no write")
    void aTableCreatedLaterIsNotWritableByTheBatchRole() throws SQLException {
        try (Connection owner = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = owner.createStatement()) {
            statement.execute("SET ROLE " + OWNER_ROLE);
            statement.execute("CREATE TABLE " + LATER_TABLE + " (probe_id BIGINT NOT NULL)");
            try {
                assertThat(privilege(LATER_TABLE, "SELECT"))
                        .as("the schema-wide read default must reach a table created later, which is how"
                                + " %s reads a master this context adds", BATCH_ROLE)
                        .isTrue();
                assertThat(privilege(LATER_TABLE, "UPDATE"))
                        .as("no write default may reach a table created later; the write surface is the"
                                + " single named grant on %s", ACCOUNT_MASTER)
                        .isFalse();
            } finally {
                statement.execute("DROP TABLE " + LATER_TABLE);
            }
        }
    }

    /**
     * Reads one table privilege of the batch role out of the engine's own access-control lists.
     *
     * @param table the schema-qualified table to ask about, such as {@code account.accounts}; must not
     *     be {@code null}
     * @param name the privilege to ask about, spelled as SQL spells it, such as {@code UPDATE}; must not
     *     be {@code null}
     * @return {@code true} when the batch role holds that privilege on that table, {@code false} when it
     *     does not
     * @throws org.springframework.dao.DataAccessException if the probe cannot be executed, which is a
     *     broken harness rather than a failed assertion
     */
    private boolean privilege(String table, String name) {
        return Boolean.TRUE.equals(this.jdbc.queryForObject(
                "SELECT has_table_privilege(?, ?, ?)", Boolean.class, BATCH_ROLE, table, name));
    }

    /**
     * Lists every table this schema holds, schema-qualified, in name order.
     *
     * <p>Assumptions: the catalog is asked rather than a list being written here, so a table a later
     * migration adds is covered by the withheld-privilege case without that case being edited. Flyway's
     * own history table is included, and that is correct rather than incidental: the batch role has no
     * business writing another context's migration history either.</p>
     *
     * @return the qualified table names, never {@code null} or empty
     * @throws org.springframework.dao.DataAccessException if the catalog cannot be read
     */
    private List<String> accountTables() {
        return this.jdbc.queryForList(
                "SELECT format('%I.%I', schemaname, tablename) FROM pg_catalog.pg_tables"
                        + " WHERE schemaname = 'account' ORDER BY tablename", String.class);
    }

    /**
     * Attempts one statement as the batch role and reports how the engine answered.
     *
     * <p>Assumptions: an accepted statement yields the sentinel below rather than an exception, so the
     * caller compares one string against one string for every case and a statement that SUCCEEDED is
     * reported in the same shape as one that failed for the wrong reason. Letting the refusal propagate
     * was the alternative, and it makes the acceptance case -- the failure this test exists to catch --
     * the one outcome with no value to compare.</p>
     *
     * @param sql the statement to attempt; must not be {@code null}
     * @return the SQL state the engine reported, or {@code accepted} when the statement succeeded; never
     *     {@code null}
     * @throws IllegalStateException if the connection itself cannot be opened, which is a broken harness
     *     rather than a failed assertion
     */
    private static String outcomeOf(String sql) {
        try (Connection batch = batchConnection();
                Statement statement = batch.createStatement()) {
            statement.execute(sql);
            return "accepted";
        } catch (SQLException answered) {
            if (answered.getSQLState() == null) {
                throw new IllegalStateException("the engine reported no SQL state for " + sql, answered);
            }
            return answered.getSQLState();
        }
    }

    /**
     * Opens one connection authenticated as the nightly batch role.
     *
     * @return an open connection the caller closes, never {@code null}
     * @throws SQLException if the engine refuses the connection
     */
    private static Connection batchConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), BATCH_ROLE, HARNESS_CREDENTIAL);
    }

    /**
     * Applies the shipped bootstrap unchanged, by the engine's own client inside the container.
     *
     * <p>Assumptions: the file is copied in and executed by {@code psql} rather than sent through the
     * driver, and the reason is syntactic. It wraps itself in an explicit transaction and holds several
     * dollar-quoted procedural blocks whose bodies contain semicolons, so any client-side splitting on a
     * statement terminator would cut them in half. Handing the whole file to the client the shipping
     * documentation tells an operator to use removes the question.</p>
     *
     * @throws IllegalStateException if the file is absent or the client reports a failure
     */
    private static void applyBootstrap() {
        Path source = repositoryRoot().resolve(BOOTSTRAP_SCRIPT);
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("the shipped bootstrap " + source + " is absent, so this"
                    + " test cannot assert anything about the provisioned privilege graph");
        }
        String target = "/tmp/carddemo-bootstrap.sql";
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(source), target);
        runPsql("-v", "ON_ERROR_STOP=1", "-f", target);
    }

    /**
     * Runs the engine's own client inside the container and fails loudly on a non-zero result.
     *
     * @param arguments the client arguments following the connection flags; must not be {@code null}
     * @throws IllegalStateException if the client reports a failure, or if the calling thread is
     *     interrupted while the client is running
     * @throws UncheckedIOException if the client cannot be run at all
     */
    private static void runPsql(String... arguments) {
        List<String> command = new ArrayList<>(List.of(
                "psql", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(), "-q"));
        command.addAll(List.of(arguments));
        try {
            org.testcontainers.containers.Container.ExecResult result =
                    POSTGRES.execInContainer(command.toArray(String[]::new));
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("the engine client refused " + command + ": "
                        + result.getStderr() + result.getStdout());
            }
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot run " + command, cause);
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + command, cause);
        }
    }

    /**
     * Resolves the repository root by walking up from the working directory.
     *
     * <p>Assumptions: the reactor sets the working directory to the module, so the root is found by
     * ascending until a directory carries both the shipped bootstrap and the services tree. Counting a
     * fixed number of parents was the alternative and is rejected because it breaks whenever the module
     * moves, and it breaks by resolving to a directory that exists.</p>
     *
     * @return the repository root, never {@code null}
     * @throws IllegalStateException if no ancestor carries both markers
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(BOOTSTRAP_SCRIPT))
                    && Files.isDirectory(candidate.resolve("services"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("cannot locate the repository root above "
                + Path.of("").toAbsolutePath());
    }

    /**
     * The minimal Spring Boot configuration this class runs against.
     *
     * <p>Assumptions: no component scan is declared, so the queue listener, the identity client and the
     * cipher this module's own application class would register stay out of the context. Naming the two
     * persistence packages leaves the framework's own auto-configuration to build the pool from the
     * properties the container registered and to run Flyway ahead of it, which is the sequence under
     * test.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    // WHY : Assumptions: the two auto-configurations excluded here read deployment values the test
    //       profile deliberately does not carry -- the resource-server one evaluates its decoder
    //       condition against an issuer URI and the queue one builds a client that needs a region, both
    //       bound in application.yml to placeholders with no fallback. Leaving either in ends context
    //       load while the condition is evaluated, reporting a failure that belongs to configuration
    //       rather than to any privilege assertion here. The sibling persistence tests in this package
    //       exclude the same pair, so all of them load the same shape.
    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {OAuth2ResourceServerAutoConfiguration.class, SqsAutoConfiguration.class})
    @EntityScan("com.carddemo.account.domain")
    @EnableJpaRepositories("com.carddemo.account.repository")
    static class BatchGrantPersistenceTestApplication {
    }
}
